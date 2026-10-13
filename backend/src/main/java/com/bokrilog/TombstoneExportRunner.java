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
 * 일회성 tombstone 내보내기 역할의 진입(Story 3.10). {@code tombstone-export} 프로필에서만 있다.
 *
 * <p>운영 호스트의 timer({@code deploy/production/tombstone-export.sh}, 15분 주기)가 띄운다. 기한 지난 outbox 행을 지우고 실을 행(기한이
 * 지금 + 24시간보다 뒤) <b>전체</b>를 마운트된 파일 하나({@code bokrilog.tombstone.export.file})에 쓴다 — 같은 디렉터리의 임시 이름에
 * 쓴 뒤 이름을 바꾼다. 매번 전체를 쓰므로 "보냈다" 표시가 없고 재시도해도 결과가 같다. 파일은 머리(digest 키 ID·만든 시각)와 digest·
 * 요청 ID·시각뿐이고, 호스트가 바로 tombstone 전용 age key로 암호화한 뒤 지운다. 로그에는 수만 남는다. 파일 머리의 키 ID 때문에 이
 * 역할도 tombstone digest 키를 받는다.
 */
@Component
@Profile("tombstone-export")
class TombstoneExportRunner implements ApplicationRunner {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TombstoneExportRunner.class);

    private final DeletionTombstoneApi tombstones;

    private final String file;

    TombstoneExportRunner(DeletionTombstoneApi tombstones, @Value("${bokrilog.tombstone.export.file:}") String file) {
        this.tombstones = tombstones;
        this.file = file;
    }

    @Override
    public void run(ApplicationArguments args) {
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
        } catch (IOException unwritable) {
            throw new UncheckedIOException("tombstone 파일을 쓰지 못했다.", unwritable);
        }
        LOG.atInfo().addKeyValue("count", export.live().size()).addKeyValue("outcome", "EXPORTED")
                .log("tombstone.export.written purged=" + export.purged());
    }
}
