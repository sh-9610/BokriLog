package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.DeletionTombstoneApi.AffectedGeneration;
import com.bokrilog.operations.DeletionTombstoneApi.DestructionStatus;
import com.bokrilog.operations.DeletionTombstoneApi.GenerationSet;
import com.bokrilog.operations.DeletionTombstoneApi.Tombstone;
import com.bokrilog.operations.OperatorProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
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

    /** V32의 영향 generation·내보내기 기록 표도 같은 repository 한 곳이 SQL로 안다. */
    private static final Pattern OWNED_NAMING = Pattern.compile(
            "(?:INTO|FROM|JOIN)\\s+(?:operations_deletion_backup_generation|operations_tombstone_export)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE = Pattern.compile(
            "\\bUPDATE\\s+(?:ONLY\\s+)?" + TABLE + "\\b|\\bTRUNCATE\\b[^;\"]*\\b" + TABLE + "\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void 표를_SQL로_다루는_운영_클래스는_repository_하나다() throws Exception {
        List<Path> naming = new ArrayList<>();
        List<String> updates = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (SQL_NAMING.matcher(source).find() || OWNED_NAMING.matcher(source).find()) {
                    naming.add(file.getFileName());
                }
                Matcher matcher = UPDATE.matcher(source);
                while (matcher.find()) {
                    updates.add(file.getFileName() + ":" + matcher.group());
                }
            }
        }
        assertThat(naming).containsExactly(REPOSITORY.getFileName());
        // tombstone 행을 바꾸는 문장은 repository 안의 여섯뿐이다(Story 5.3): 집합 닫기 둘(지금·운영 완료 시각), 초과 전환, 파기 완료 전환,
        // 복원의 외부 상태 병합, 처음 DESTROYED로 실리는 내보내기 번호.
        assertThat(updates).containsOnly("DeletionTombstoneRepository.java:UPDATE " + TABLE).hasSize(6);
        String source = Files.readString(REPOSITORY, StandardCharsets.UTF_8);
        // 보존 기한은 늘기만 한다 — 보존 기한을 쓰는 문장은 셋이고 모두 greatest(…destroy_after, …)다.
        assertThat(Pattern.compile("destroy_after\\s*=\\s*greatest\\((?:x\\.)?destroy_after,").matcher(source).results()
                .count()).as("보존 연장 문장").isEqualTo(3);
        assertThat(Pattern.compile("destroy_after\\s*=").matcher(source).results().count())
                .as("보존 기한을 쓰는 문장은 연장 셋뿐이다").isEqualTo(3);
        // 집합은 열린 것만 닫는다(다시 열지 않는다). 파기 완료는 되돌리지 않는다. 초과는 미완료에서 한 번이다.
        assertThat(source).contains("WHERE request_id = :request AND closed_at IS NULL")
                .contains("status <> 'DESTROYED' AND closed_at IS NOT NULL")
                .contains("status = 'PENDING' AND ");
        // 지우는 문장은 백업 파기가 완료됐고 그 상태로 성공한 내보내기에 실렸고 보존 기한이 지난 tombstone(영향 generation은 외래키로 함께)과
        // 35일 지난 내보내기 기록 둘뿐이다. 완료가 아닌 tombstone은 기한이 지나도 지우지 않는다.
        assertThat(count(source, "DELETE")).as("DELETE 문장 수").isEqualTo(2);
        assertThat(source).contains("\"DELETE FROM " + TABLE + " t WHERE t.status = 'DESTROYED' \"")
                .contains("AND t.destroy_after <= statement_timestamp() AND \" + EXPORTED_AS_DESTROYED)")
                .contains("e.exported_at IS NOT NULL AND e.sequence >= t.destroyed_export")
                .contains("DELETE FROM operations_tombstone_export ");
        // 내보내기 번호는 아직 번호가 없는 DESTROYED 행에만 붙인다(처음 DESTROYED로 실리는 내보내기 — 덮어쓰지 않는다).
        assertThat(source).contains("WHERE status = 'DESTROYED' AND destroyed_export IS NULL");
        // 영향 generation의 갱신은 채울 것이 있을 때만이다(같은 값으로 tuple을 쌓지 않는다).
        assertThat(source).contains("DO UPDATE SET").contains("EXCLUDED.oci_purged_at IS NOT NULL)");
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

    /** Story 5.3: 줄은 파기 기한·상태·집합·영향 generation을 더 싣고 되읽힌다. Story 3.10의 네 칸 줄도 읽는다(요청 시각에 닫힌 집합). */
    @Test
    void 파기_상태와_영향_generation이_줄에_실리고_되읽힌다() {
        Instant at = Instant.parse("2026-11-01T01:02:03.120Z");
        Tombstone open = new Tombstone("a".repeat(64), "req-open", at, at.plus(DeletionTombstoneApi.RETENTION),
                DestructionStatus.PENDING, GenerationSet.OPEN, List.of(new AffectedGeneration("gen_20261101", true, false),
                        new AffectedGeneration("gen_20261031", true, true)));
        Tombstone destroyed = new Tombstone("b".repeat(64), "req-done", at, at.plus(Duration.ofDays(40)),
                DestructionStatus.DESTROYED, GenerationSet.CLOSED, List.of());
        assertThat(open.generations()).as("ID 순").extracting(AffectedGeneration::id)
                .containsExactly("gen_20261031", "gen_20261101");
        assertThat(open.backupDeadline()).isEqualTo(at.plus(Duration.ofHours(720)));
        // generation마다 저장소별 파기 확인(OCI·OCI 밖, P 또는 -)이 실린다(Story 5.3 — 복원이 옮긴다).
        assertThat(open.line()).isEqualTo("a".repeat(64) + " req-open " + at + " " + at.plus(DeletionTombstoneApi.RETENTION)
                + " " + at.plus(Duration.ofHours(720)) + " PENDING OPEN gen_20261031:PP,gen_20261101:P-");
        assertThat(destroyed.line()).endsWith(" DESTROYED CLOSED -");
        String text = DeletionTombstoneApi.render(KEY_ID, Instant.parse("2026-11-02T00:00:00Z"), List.of(open, destroyed));
        assertThat(DeletionTombstoneApi.read(text.lines().toList()).tombstones()).containsExactly(open, destroyed);
        // 줄에 원식별자 모양(UUID)·이메일이 없다.
        assertThat(text).doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}-").doesNotContain("@");

        // Story 3.10 모양(네 칸): 요청 시각에 닫힌 집합, 상태 PENDING, 영향 generation 없음.
        Tombstone legacy = Tombstone.parse("c".repeat(64) + " req-old " + at + " " + at.plus(DeletionTombstoneApi.RETENTION));
        assertThat(legacy.set()).isEqualTo(GenerationSet.CLOSED);
        assertThat(legacy.status()).isEqualTo(DestructionStatus.PENDING);
        assertThat(legacy.generations()).isEmpty();
    }

    @Test
    void 파기_상태_줄의_모양_밖은_읽지_않는다() {
        Instant at = Instant.parse("2026-11-01T00:00:00Z");
        String head = "c".repeat(64) + " req-1 " + at + " " + at.plus(DeletionTombstoneApi.RETENTION) + " ";
        String deadline = at.plus(Duration.ofHours(720)).toString();
        for (String bad : List.of(head + at.plus(Duration.ofHours(719)) + " PENDING OPEN -",
                head + deadline + " GONE OPEN -",
                head + deadline + " PENDING HALF -",
                head + deadline + " DESTROYED OPEN -",
                head + deadline + " PENDING OPEN gen_2026110",
                head + deadline + " PENDING OPEN gen_20261101",
                head + deadline + " PENDING OPEN gen_20261101:PX",
                head + deadline + " PENDING OPEN gen_20261101:P-,gen_20261101:PP",
                head + deadline + " PENDING OPEN gen_20261101:--,../x",
                // 백업 파기 완료는 모든 영향 generation이 두 저장소에서 파기됐다는 뜻이다(Q4) — 하나라도 P가 아니면 모양 밖이다.
                head + deadline + " DESTROYED CLOSED gen_20261101:P-",
                head + deadline + " DESTROYED CLOSED gen_20261031:PP,gen_20261101:-P",
                head + deadline + " DESTROYED CLOSED gen_20261101:--",
                head + deadline + " PENDING OPEN",
                head + "yesterday PENDING OPEN -")) {
            assertThatThrownBy(() -> DeletionTombstoneApi.read(List.of(HEADER, bad)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        // 보존 기한은 요청 + 35일보다 짧을 수 없다(늘어난 것은 받는다).
        assertThatThrownBy(() -> new Tombstone("c".repeat(64), "req-1", at, at.plus(Duration.ofHours(839))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Tombstone("c".repeat(64), "req-1", at, at.plus(Duration.ofDays(60))).destroyAfter())
                .isEqualTo(at.plus(Duration.ofDays(60)));
        // 영향 generation 목록이 없으면 NPE가 아니라 모양 밖이다.
        assertThatThrownBy(() -> new Tombstone("c".repeat(64), "req-1", at, at.plus(DeletionTombstoneApi.RETENTION),
                DestructionStatus.PENDING, GenerationSet.OPEN, null)).isInstanceOf(IllegalArgumentException.class);
        // Q4: 생성자도 같다 — DESTROYED인데 파기되지 않은 저장소가 있는 generation은 거부하고, 모두 PP면 받는다(초과·미완료는 무관).
        assertThatThrownBy(() -> new Tombstone("c".repeat(64), "req-1", at, at.plus(DeletionTombstoneApi.RETENTION),
                DestructionStatus.DESTROYED, GenerationSet.CLOSED, List.of(new AffectedGeneration("gen_20261031", true, true),
                        new AffectedGeneration("gen_20261101", true, false))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Tombstone("c".repeat(64), "req-1", at, at.plus(DeletionTombstoneApi.RETENTION),
                DestructionStatus.DESTROYED, GenerationSet.CLOSED, List.of(new AffectedGeneration("gen_20261101", true, true)))
                .status()).isEqualTo(DestructionStatus.DESTROYED);
        assertThat(new Tombstone("c".repeat(64), "req-1", at, at.plus(DeletionTombstoneApi.RETENTION),
                DestructionStatus.OVERDUE, GenerationSet.CLOSED, List.of(new AffectedGeneration("gen_20261101", false, false)))
                .status()).isEqualTo(DestructionStatus.OVERDUE);
        // 기한이 이미 지난 미완료 tombstone(오래 멈춘 실행·초과)도 받는다 — 복원이 거부하면 그 계정을 지키지 못한다.
        Instant old = Instant.parse("2025-01-01T00:00:00Z");
        Tombstone stale = new Tombstone("c".repeat(64), "req-old", old, old.plus(DeletionTombstoneApi.RETENTION),
                DestructionStatus.OVERDUE, GenerationSet.OPEN, List.of());
        assertThat(Tombstone.parse(stale.line())).isEqualTo(stale);
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
