package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.DeletionTombstoneApi.Tombstone;
import com.bokrilog.operations.OperatorProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Story 3.10: 삭제 tombstone의 SQL 자리·digest 키 분리·파일 모양. Spring·DB 없이 본다(기한 지난 행의 삭제는 {@code AccountErasureTest}가
 * 실제 DB에서 본다).
 *
 * <p>표를 SQL로 다루는 운영 클래스는 {@link DeletionTombstoneRepository} 하나이고, 운영 소스 어디에도 그 표의 행을 고치는 문장이 없다
 * ({@code BackupEventRepositoryTest}와 같은 관용 — 이 표는 기한 지난 행의 삭제 하나만 더 있다).
 */
class DeletionTombstoneTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    private static final Path REPOSITORY =
            MAIN_SOURCES.resolve("com/bokrilog/operations/internal/DeletionTombstoneRepository.java");

    private static final String TABLE = "operations_deletion_tombstone";

    private static final Pattern SQL_NAMING =
            Pattern.compile("(?:INTO|FROM|JOIN)\\s+" + TABLE + "\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE = Pattern.compile(
            "\\bUPDATE\\s+(?:ONLY\\s+)?" + TABLE + "\\b|\\bTRUNCATE\\b[^;\"]*\\b" + TABLE + "\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void 표를_SQL로_다루는_운영_클래스는_repository_하나다() throws Exception {
        List<Path> naming = new ArrayList<>();
        List<String> updates = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (SQL_NAMING.matcher(source).find()) {
                    naming.add(file.getFileName());
                }
                Matcher matcher = UPDATE.matcher(source);
                while (matcher.find()) {
                    updates.add(file.getFileName() + ":" + matcher.group());
                }
            }
        }
        assertThat(naming).containsExactly(REPOSITORY.getFileName());
        assertThat(updates).isEmpty();
        String source = Files.readString(REPOSITORY, StandardCharsets.UTF_8);
        assertThat(count(source, "UPDATE")).as("UPDATE 문장 수").isZero();
        // 지우는 문장은 기한 지난 행 하나뿐이다.
        assertThat(count(source, "DELETE")).as("DELETE 문장 수").isEqualTo(1);
        assertThat(source).contains("DELETE FROM " + TABLE + " WHERE destroy_after <= statement_timestamp()");
    }

    @Test
    void digest는_전용_키와_tombstone_접두사의_HMAC이다() throws Exception {
        String key = newKey();
        UUID user = UUID.randomUUID();
        String digest = new TombstoneDigest(key).of(user);
        assertThat(digest).matches("[0-9a-f]{64}").doesNotContain(user.toString().replace("-", ""));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"));
        assertThat(digest).isEqualTo(HexFormat.of().formatHex(
                mac.doFinal(("tombstone:" + user).getBytes(StandardCharsets.UTF_8))));
        // 같은 키라도 감사 digest(수행자 접두사)와 같은 값이 되지 않는다.
        assertThat(digest).isNotEqualTo(new ActorDigest(key).of(user));
        // 다른 키면 다른 값이다.
        assertThat(new TombstoneDigest(newKey()).of(user)).isNotEqualTo(digest);
    }

    @Test
    void tombstone_키는_감사_digest_키와_같으면_거부된다() {
        String audit = newKey();
        OperationsConfiguration configuration = new OperationsConfiguration();
        OperatorProperties properties = new OperatorProperties("", audit, null);
        assertThatThrownBy(() -> configuration.operationsTombstoneDigest(audit, properties))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("감사 digest 키와 같다")
                .hasMessageNotContaining(audit);
        assertThatThrownBy(() -> configuration.operationsTombstoneDigest(" " + audit + " ", properties))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configuration.operationsTombstoneDigest("", properties))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("비어 있다");
        assertThatThrownBy(() -> configuration.operationsTombstoneDigest(
                Base64.getEncoder().encodeToString(new byte[16]), properties))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("짧다");
        assertThat(configuration.operationsTombstoneDigest(newKey(), properties)).isNotNull();
        // 같은 바이트의 다른 base64 표기(MIME 줄바꿈)도 같은 키다 — 문자열이 아니라 바이트로 비교한다.
        byte[] raw = Base64.getDecoder().decode(audit);
        String wrapped = Base64.getMimeEncoder(16, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(raw);
        assertThat(wrapped).isNotEqualTo(audit);
        OperatorProperties wrappedAudit = new OperatorProperties("", wrapped, null);
        assertThatThrownBy(() -> configuration.operationsTombstoneDigest(audit, wrappedAudit))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("감사 digest 키와 같다");
    }

    @Test
    void 키_ID는_키마다_다르고_키를_드러내지_않는다() {
        String key = newKey();
        TombstoneDigest digest = new TombstoneDigest(key);
        assertThat(digest.keyId()).matches("[0-9a-f]{16}").isEqualTo(new TombstoneDigest(key).keyId());
        assertThat(new TombstoneDigest(newKey()).keyId()).isNotEqualTo(digest.keyId());
        assertThat(HexFormat.of().formatHex(Base64.getDecoder().decode(key))).doesNotContain(digest.keyId());
    }

    @Test
    void 파일은_머리_한_줄과_tombstone_줄이고_되읽힌다() {
        Instant at = Instant.parse("2026-10-13T01:02:03.120Z");
        Tombstone first = new Tombstone("a".repeat(64), "req-1", at, at.plus(DeletionTombstoneApi.RETENTION));
        Tombstone second = new Tombstone("b".repeat(64), "req_2", at.plusMillis(1),
                at.plusMillis(1).plus(DeletionTombstoneApi.RETENTION));
        Instant exported = Instant.parse("2026-10-13T02:00:00Z");
        String text = DeletionTombstoneApi.render(KEY_ID, exported, List.of(first, second));
        assertThat(text).startsWith(HEADER + "\n");
        assertThat(text.lines()).hasSize(3);
        var file = DeletionTombstoneApi.read(text.lines().toList());
        assertThat(file.keyId()).isEqualTo(KEY_ID);
        assertThat(file.exportedAt()).isEqualTo(exported);
        assertThat(file.tombstones()).containsExactly(first, second);
        assertThat(DeletionTombstoneApi.read(List.of(HEADER)).tombstones()).isEmpty();
        assertThat(DeletionTombstoneApi.render(KEY_ID, exported, List.of())).isEqualTo(HEADER + "\n");
    }

    @Test
    void 모양_밖의_파일과_줄은_읽지_않는다() {
        Instant at = Instant.parse("2026-10-13T00:00:00Z");
        String line = "c".repeat(64) + " req-1 " + at + " " + at.plus(DeletionTombstoneApi.RETENTION);
        assertThatThrownBy(() -> DeletionTombstoneApi.read(List.of(line)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("머리");
        assertThatThrownBy(() -> DeletionTombstoneApi.read(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        // 키 ID·시각이 없는 머리(이 스토리 첫 판의 머리)는 받지 않는다.
        for (String header : List.of(DeletionTombstoneApi.FILE_HEADER, DeletionTombstoneApi.FILE_HEADER + " key=" + KEY_ID,
                DeletionTombstoneApi.FILE_HEADER + " key=XYZ exported=2026-10-13T02:00:00Z",
                DeletionTombstoneApi.FILE_HEADER + " key=" + KEY_ID + " exported=yesterday")) {
            assertThatThrownBy(() -> DeletionTombstoneApi.read(List.of(header))).as(header)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String bad : List.of("C".repeat(64) + " req-1 " + at + " " + at.plus(DeletionTombstoneApi.RETENTION),
                "c".repeat(64) + " req/1 " + at + " " + at.plus(DeletionTombstoneApi.RETENTION),
                "c".repeat(64) + " req-1 " + at + " " + at.plusSeconds(86_400),
                "c".repeat(64) + " req-1 not-a-time " + at,
                "c".repeat(64) + " req-1 " + at)) {
            assertThatThrownBy(() -> DeletionTombstoneApi.read(List.of(HEADER, bad)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        // 원문 사용자 ID 모양(UUID)은 digest 칸에 들어갈 수 없다.
        assertThatThrownBy(() -> new Tombstone(UUID.randomUUID().toString(), "req-1", at,
                at.plus(DeletionTombstoneApi.RETENTION))).isInstanceOf(IllegalArgumentException.class);
    }

    private static final String KEY_ID = "0123456789abcdef";

    private static final String HEADER = DeletionTombstoneApi.FILE_HEADER + " key=" + KEY_ID
            + " exported=2026-10-13T02:00:00Z";

    private static int count(String source, String word) {
        Matcher matcher = Pattern.compile("\\b" + word + "\\b").matcher(source);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    private static String newKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
