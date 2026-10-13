package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.source.SourceConnectionApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Story 3.10: 일회성 역할 runner의 문지기. Spring·DB 없이 본다.
 *
 * <ul>
 *   <li>{@link RestoreApplyRunner} — 격리 표지가 없거나 모양 밖이면 적용을 부르지 않고 실패하고, 판정이 실패한 보고면 예외(종료 0이 아님)다.
 *   <li>{@link DeletionSampleRunner} — 리허설 표지가 없거나 운영 환경이면 지우지 않고 실패한다.
 * </ul>
 */
class RestoreRunnersTest {

    @TempDir
    Path work;

    private static final String KEY_ID = "0123456789abcdef";

    /** 적용을 부른 횟수를 세고 정해 둔 보고를 돌려주는 대역. */
    private static final class Recording extends RestoreApplication {

        final AtomicInteger calls = new AtomicInteger();

        private final Report report;

        Recording(Report report) {
            super(null, null, null, null, null, null);
            this.report = report;
        }

        @Override
        Report apply(DeletionTombstoneApi.TombstoneFile copy) {
            this.calls.incrementAndGet();
            return this.report;
        }
    }

    private static RestoreApplication.Report report(long residual, boolean credentialsOpened) {
        return new RestoreApplication.Report(0, 0, 0, 0, 0, 0, new RestoreApplication.Reconciliation(true, 0, 0),
                new RestoreInvariants.Violations(0, 0, 0, 0, residual),
                new SourceConnectionApi.CredentialProbe(1, credentialsOpened ? 1 : 0, true));
    }

    private Path tombstoneFile() throws Exception {
        Path file = this.work.resolve("tombstones.txt");
        Files.writeString(file, DeletionTombstoneApi.render(KEY_ID, Instant.parse("2026-10-13T00:00:00Z"), List.of()),
                StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void 격리_표지가_없으면_적용을_부르지_않는다() throws Exception {
        String file = tombstoneFile().toString();
        for (String marker : List.of("", "bokrilog-production", "bokrilog-restore-", "bokrilog-restore-UPPER",
                "restore-a")) {
            Recording restore = new Recording(report(0, true));
            assertThatThrownBy(() -> new RestoreApplyRunner(restore, file, marker).run(null)).as(marker)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("격리");
            assertThat(restore.calls.get()).as(marker).isZero();
        }
    }

    @Test
    void 판정이_실패한_보고는_예외로_끝난다() throws Exception {
        String file = tombstoneFile().toString();
        Recording passing = new Recording(report(0, true));
        new RestoreApplyRunner(passing, file, "bokrilog-restore-a").run(null);
        assertThat(passing.calls.get()).isEqualTo(1);

        for (RestoreApplication.Report failing : List.of(report(1, true), report(0, false),
                new RestoreApplication.Report(0, 0, 0, 0, 0, 0, new RestoreApplication.Reconciliation(false, 0, 0), null,
                        null))) {
            Recording restore = new Recording(failing);
            assertThatThrownBy(() -> new RestoreApplyRunner(restore, file, "bokrilog-restore-a").run(null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("복원 검증이 실패했다");
            assertThat(restore.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void 머리가_없는_tombstone_파일은_읽지_않는다() throws Exception {
        Path file = this.work.resolve("plain.txt");
        Files.writeString(file, DeletionTombstoneApi.FILE_HEADER + "\n", StandardCharsets.UTF_8);
        Recording restore = new Recording(report(0, true));
        assertThatThrownBy(() -> new RestoreApplyRunner(restore, file.toString(), "bokrilog-restore-a").run(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestoreApplyRunner(restore, this.work.resolve("absent").toString(),
                "bokrilog-restore-a").run(null)).isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(restore.calls.get()).isZero();
    }

    @Test
    void 삭제_표본은_리허설_표지와_비운영_환경이_함께_있어야_돈다() {
        assertThatThrownBy(() -> DeletionSampleRunner.requireRehearsal("ci", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("리허설 표지");
        assertThatThrownBy(() -> DeletionSampleRunner.requireRehearsal("production", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("운영 환경");
        DeletionSampleRunner.requireRehearsal("ci", true);
        DeletionSampleRunner.requireRehearsal("development", true);
        // 표지 없이는 계정을 지우는 자리까지 가지 않는다.
        assertThatThrownBy(() -> new DeletionSampleRunner(null, UUID.randomUUID().toString(), "sample-1", "ci", false)
                .run(null)).hasMessageContaining("리허설 표지");
    }
}
