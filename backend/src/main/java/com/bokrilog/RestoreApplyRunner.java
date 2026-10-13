package com.bokrilog;

import com.bokrilog.operations.DeletionTombstoneApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 일회성 복원 적용 역할의 진입(Story 3.10). {@code restore-apply} 프로필에서만 있다.
 *
 * <p>복원 절차({@code deploy/production/restore.sh})가 격리 compose 프로젝트에서 {@code pg_restore}·migration 뒤에, API·Worker를 띄우기
 * <b>전에</b> 한 번 띄운다. 마운트된 파일 하나({@code bokrilog.restore.tombstones-file})가 복호화된 외부 tombstone이다. 웹 서버 없이 뜨고
 * Flyway는 검증만 한다(migration이 이미 돌았다). 결과는 수만 담은 로그 한 줄이다 — 통과면 종료 0, 파일을 읽지 못하거나 대조·불변식·자격정보
 * 검증이 실패하면 기동 실패(0이 아님)이고 절차는 서비스를 띄우지 않는다.
 *
 * <p><b>격리 표지가 없으면 돌지 않는다.</b> 이 역할은 계정을 지우고 세션을 모두 폐기한다 — 운영 DB에 잘못 붙으면 그대로 사고다. 절차가
 * 격리 프로젝트 이름({@code bokrilog.restore.isolated-project}, {@code bokrilog-restore-*})을 넘겨야만 일한다. 스크립트의 이름 검사와
 * 별개로 앱이 한 번 더 막는다.
 */
@Component
@Profile("restore-apply")
class RestoreApplyRunner implements ApplicationRunner {

    /** 격리 프로젝트 이름의 모양({@code restore.sh}와 같다). */
    static final Pattern ISOLATED_PROJECT = Pattern.compile("bokrilog-restore-[a-z0-9][a-z0-9-]{0,30}");

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RestoreApplyRunner.class);

    private final RestoreApplication restore;

    private final String file;

    private final String isolatedProject;

    RestoreApplyRunner(RestoreApplication restore, @Value("${bokrilog.restore.tombstones-file:}") String file,
            @Value("${bokrilog.restore.isolated-project:}") String isolatedProject) {
        this.restore = restore;
        this.file = file;
        this.isolatedProject = isolatedProject;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            requireIsolation(this.isolatedProject);
        } catch (IllegalStateException refused) {
            LOG.atWarn().addKeyValue("code", "RESTORE_NOT_ISOLATED").log("restore.apply.refused");
            throw refused;
        }
        RestoreApplication.Report report = this.restore.apply(tombstones());
        LOG.atInfo().addKeyValue("count", report.matched()).log(report.line());
        if (!report.passed()) {
            throw new IllegalStateException("복원 검증이 실패했다. 서비스를 열지 않는다.");
        }
    }

    /** 격리 표지. 없거나 모양 밖이면 아무것도 하지 않고 실패한다. */
    static void requireIsolation(String project) {
        if (project == null || !ISOLATED_PROJECT.matcher(project).matches()) {
            throw new IllegalStateException("격리 복원 프로젝트 표지가 없다. 복원 적용은 격리 프로젝트에서만 돈다.");
        }
    }

    /** 마운트된 tombstone 파일. 없거나 머리가 없거나 모양 밖이면 실패다 — tombstone 없이 적용하지 않는다. */
    DeletionTombstoneApi.TombstoneFile tombstones() {
        if (this.file.isBlank()) {
            throw new IllegalStateException("tombstone 파일이 정해지지 않았다.");
        }
        try {
            return DeletionTombstoneApi.read(Files.readAllLines(Path.of(this.file), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("tombstone 파일을 읽지 못했다.", unreadable);
        }
    }
}
