package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.source.SourceConnectionApi;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                new RestoreInvariants.Violations(0, 0, 0, 0, Map.of("identity", residual)),
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

    /**
     * Story 5.3: 내보내기 역할의 기록 모드는 파일을 쓰지 않고 실린 수와 내보내기 번호로 성공을 기록한다. 수는 0 이상의 정수, 번호는 1 이상의
     * 정수만 받는다(값을 메시지에 담지 않는다).
     */
    @Test
    void 내보내기_기록_모드는_파일을_쓰지_않고_성공만_기록한다() throws Exception {
        List<String> confirmed = new ArrayList<>();
        DeletionTombstoneApi tombstones = (DeletionTombstoneApi) Proxy.newProxyInstance(
                DeletionTombstoneApi.class.getClassLoader(), new Class<?>[] {DeletionTombstoneApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("confirmExport")) {
                        confirmed.add(args[0] + "@" + args[1]);
                        return null;
                    }
                    throw new AssertionError("기록 모드가 " + method.getName() + "을 불렀다.");
                });
        Path file = this.work.resolve("never.txt");
        new TombstoneExportRunner(tombstones, file.toString(), " 12 ", " 7 ").run(null);
        assertThat(confirmed).containsExactly("12@7");
        assertThat(file).doesNotExist();
        assertThat(TombstoneExportRunner.exportIdFile(file)).doesNotExist();
        // 번호 없는 기록 모드는 거부한다(어느 사본의 성공인지 모른다).
        assertThatThrownBy(() -> new TombstoneExportRunner(tombstones, file.toString(), "12", "").run(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(confirmed).hasSize(1);
        assertThat(TombstoneExportRunner.exportIdOf("1")).isEqualTo(1L);
        assertThat(TombstoneExportRunner.exportIdOf("123456789012345678")).as("18자리").isEqualTo(123_456_789_012_345_678L);
        for (String bad : List.of("0", "-1", "01", "abc", "1234567890123456789", "")) {
            assertThatThrownBy(() -> TombstoneExportRunner.exportIdOf(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(bad.isEmpty() ? "\u0000" : bad);
        }
        assertThat(TombstoneExportRunner.confirmedCount("0")).isZero();
        assertThat(TombstoneExportRunner.confirmedCount("123456789")).as("int 범위 9자리").isEqualTo(123_456_789);
        for (String bad : List.of("-1", "1.5", "abc", "1234567890", "")) {
            assertThatThrownBy(() -> TombstoneExportRunner.confirmedCount(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(bad.isEmpty() ? "\u0000" : bad);
        }
    }

    /** Story 5.3: 내보내기 모드는 사본 파일과 그 옆 {@code <파일>.export-id}(이 내보내기의 번호 한 줄)를 쓴다 — 기록 모드가 그 번호를 받는다. */
    @Test
    void 내보내기_모드는_사본과_내보내기_번호를_쓴다() throws Exception {
        DeletionTombstoneApi tombstones = (DeletionTombstoneApi) Proxy.newProxyInstance(
                DeletionTombstoneApi.class.getClassLoader(), new Class<?>[] {DeletionTombstoneApi.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("export")) {
                        return new DeletionTombstoneApi.Export("0123456789abcdef", List.of(), 0, 42L);
                    }
                    throw new AssertionError("내보내기 모드가 " + method.getName() + "을 불렀다.");
                });
        Path file = this.work.resolve("tombstones.txt");
        new TombstoneExportRunner(tombstones, file.toString(), "", "").run(null);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith(DeletionTombstoneApi.FILE_HEADER);
        assertThat(TombstoneExportRunner.exportIdFile(file)).hasFileName("tombstones.txt.export-id");
        assertThat(Files.readString(TombstoneExportRunner.exportIdFile(file), StandardCharsets.UTF_8)).isEqualTo("42\n");
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
