package com.bokrilog;

import com.bokrilog.operations.DeletionTombstoneApi;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 일회성 tombstone 내보내기 역할의 진입(Story 3.10, Story 5.3). {@code tombstone-export} 프로필에서만 있다. 두 모드가 있다.
 *
 * <p><b>내보내기 모드(기본).</b> 운영 호스트의 timer({@code deploy/production/tombstone-export.sh}, 15분 주기)가 띄운다. 보존 기한 지난
 * outbox 행을 지우고 실을 행(기한이 지금 + 24시간보다 뒤) <b>전체</b>를 마운트된 파일 하나({@code bokrilog.tombstone.export.file})에 쓴다 —
 * 같은 디렉터리의 임시 이름에 쓴 뒤 이름을 바꾼다. 매번 전체를 쓰므로 "보냈다" 표시가 없고 재시도해도 결과가 같다. 파일은 머리(digest 키
 * ID·만든 시각)와 digest·요청 ID·시각·파기 상태·영향 generation뿐이고, 호스트가 바로 tombstone 전용 age key로 암호화한 뒤 지운다.
 *
 * <p><b>기록 모드({@code bokrilog.tombstone.export.confirm}=실린 수, {@code bokrilog.tombstone.export.id}=내보내기 번호 — 내보내기
 * 모드가 사본 옆 {@code <파일>.export-id}에 쓴다).</b> 호스트가 사본을 올리고 원격 대조·{@code latest} 갱신을 마친
 * <b>뒤에만</b> 같은 역할을 한 번 더 띄워 성공을 기록한다 — 파일을 쓰는 것만으로는 성공이 아니다. Worker가 그 기록의 마지막 시각을 gauge로
 * 내고 경보 {@code TombstoneExportStale}이 멈춘 내보내기를 알린다.
 *
 * <p>로그에는 수만 남는다. 파일 머리의 키 ID 때문에 tombstone digest 키를, 감사 SYSTEM 행 때문에 감사 digest 키를 받는다.
 */
@Component
@Profile("tombstone-export")
class TombstoneExportRunner implements ApplicationRunner {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TombstoneExportRunner.class);

    private final DeletionTombstoneApi tombstones;

    private final String file;

    private final String confirm;

    private final String exportId;

    TombstoneExportRunner(DeletionTombstoneApi tombstones, @Value("${bokrilog.tombstone.export.file:}") String file,
            @Value("${bokrilog.tombstone.export.confirm:}") String confirm,
            @Value("${bokrilog.tombstone.export.id:}") String exportId) {
        this.tombstones = tombstones;
        this.file = file;
        this.confirm = confirm;
        this.exportId = exportId;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!this.confirm.isBlank()) {
            int count = confirmedCount(this.confirm);
            this.tombstones.confirmExport(count, exportIdOf(this.exportId));
            LOG.atInfo().addKeyValue("count", count).addKeyValue("outcome", "CONFIRMED").log("tombstone.export.confirmed");
            return;
        }
        if (this.file.isBlank()) {
            throw new IllegalStateException("내보낼 파일이 정해지지 않았다.");
        }
        DeletionTombstoneApi.Export export = this.tombstones.export();
        Path target = Path.of(this.file);
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        try {
            Files.writeString(partial, DeletionTombstoneApi.render(export.keyId(),
                    java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), export.live()), StandardCharsets.UTF_8);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // 이 내보내기의 번호(기록 모드가 성공을 이 번호로 채운다). 파일 옆에 한 줄로 둔다.
            Files.writeString(exportIdFile(target), Long.toString(export.exportId()) + "\n", StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            throw new UncheckedIOException("tombstone 파일을 쓰지 못했다.", unwritable);
        }
        LOG.atInfo().addKeyValue("count", export.live().size()).addKeyValue("outcome", "EXPORTED")
                .log("tombstone.export.written purged=" + export.purged());
    }

    /** 내보내기 번호 파일: 사본 파일과 같은 디렉터리의 {@code <이름>.export-id}. */
    static Path exportIdFile(Path target) {
        return target.resolveSibling(target.getFileName() + ".export-id");
    }

    /** 기록 모드의 내보내기 번호. 1 이상의 정수(18자리까지)만 받는다. 메시지에 값을 담지 않는다. */
    static long exportIdOf(String value) {
        String stripped = value.strip();
        if (!stripped.matches("[1-9][0-9]{0,17}")) {
            throw new IllegalArgumentException("내보내기 기록의 번호가 1 이상의 정수가 아니다.");
        }
        return Long.parseLong(stripped);
    }

    /** 기록 모드의 수. 0 이상의 정수(9자리까지 — int 범위)만 받는다. 메시지에 값을 담지 않는다. */
    static int confirmedCount(String value) {
        String stripped = value.strip();
        if (!stripped.matches("[0-9]{1,9}")) {
            throw new IllegalArgumentException("내보내기 기록의 tombstone 수가 0 이상의 정수가 아니다.");
        }
        return Integer.parseInt(stripped);
    }
}
