package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Story 3.1 AC-4·AC-5: 추가 전용 원장, 조회의 allowlist 필드·필터·안정 cursor, 대량 추출의 별도 감사 사건, operation 조회.
 */
class OperatorAuditTest extends OperatorTestBase {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    private static final Path OPERATIONS_SOURCES = MAIN_SOURCES.resolve("com/bokrilog/operations");

    /**
     * 원장을 고치거나 지우는 문장. 줄바꿈·공백에 상관없이 그 표를 대상으로 하는 수정·삭제·비우기를 잡는다. 비우기는 표
     * 목록 어디에 있어도 잡는다.
     */
    private static final Pattern LEDGER_MUTATION = Pattern.compile(
            "\\b(?:UPDATE\\s+(?:ONLY\\s+)?operations_audit_event\\b|DELETE\\s+FROM\\s+(?:ONLY\\s+)?operations_audit_event\\b"
                    + "|TRUNCATE\\b[^;\"]*\\boperations_audit_event\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Path REPOSITORY =
            OPERATIONS_SOURCES.resolve("internal/OperatorAuditRepository.java");

    /** 원장 repository의 SQL은 INSERT·SELECT와 삭제 port의 비식별 문장 하나뿐이다. */
    @Test
    void 원장_repository에는_비식별_UPDATE_하나만_있고_DELETE가_없다() throws Exception {
        String source = Files.readString(REPOSITORY, StandardCharsets.UTF_8);
        assertThat(count(source, "UPDATE")).as("UPDATE 문장 수").isEqualTo(1);
        assertThat(count(source, "DELETE")).as("DELETE 문장 수").isZero();
        assertThat(count(source, "TRUNCATE")).isZero();
        assertThat(source).contains("UPDATE operations_audit_event SET actor_user_id = NULL, ");
        Matcher update = Pattern.compile("int eraseActor\\(UUID userId\\) \\{[^}]*UPDATE").matcher(source);
        assertThat(update.find()).as("UPDATE는 eraseActor 안에 있다").isTrue();

        // 원장 표를 문자열로 아는 모듈 소스는 그 repository 하나다 — 다른 자리에 SQL이 생기지 않는다.
        List<Path> naming = new ArrayList<>();
        try (Stream<Path> files = Files.walk(OPERATIONS_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("\"INSERT INTO operations_audit_event")
                        || Files.readString(file, StandardCharsets.UTF_8).contains("FROM operations_audit_event")) {
                    naming.add(file.getFileName());
                }
            }
        }
        assertThat(naming).containsExactly(REPOSITORY.getFileName());

        // 운영 소스 전체에서 원장을 고치거나 지우는 문장은 비식별 UPDATE 하나뿐이다.
        List<String> mutations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = LEDGER_MUTATION.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    mutations.add(file.getFileName() + ":" + matcher.group().split("\\s+")[0].toUpperCase());
                }
            }
        }
        assertThat(mutations).containsExactly("OperatorAuditRepository.java:UPDATE");
    }

    @Test
    void 조회는_allowlist_필드와_필터와_안정_cursor를_준다() throws Exception {
        Signed operator = operator("audit-search", "command,audit");
        UUID actor = operator.user();
        for (int index = 0; index < 5; index++) {
            seed(actor, "op_seed" + index, index % 2 == 0 ? "DENIED" : "REQUESTED");
        }
        var first = auditGet(operator, "limit=2");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(topLevelFieldsOf(first.body())).containsOnlyKeys("items", "nextCursor");
        var page = itemsIn(first.body(), "items");
        assertThat(page).hasSize(2);
        for (Map<String, Object> row : page) {
            assertThat(row.keySet()).isSubsetOf("sequence", "operationId", "actorKind", "actorDigest", "command",
                    "scope", "target", "reason", "outcome", "code", "policyVersion", "requestedAt", "decidedAt",
                    "recordedAt", "correlationId");
            assertThat(row.toString()).doesNotContain(actor.toString(), "keyDigest", "requestDigest");
        }
        // 최신순이고 쪽 사이에 행이 빠지거나 겹치지 않는다.
        List<Object> seen = new ArrayList<>(page.stream().map(row -> row.get("sequence")).toList());
        String cursor = (String) topLevelFieldsOf(first.body()).get("nextCursor");
        while (cursor != null) {
            var next = auditGet(operator, "limit=2&cursor=" + cursor);
            assertThat(next.statusCode()).isEqualTo(200);
            seen.addAll(itemsIn(next.body(), "items").stream().map(row -> row.get("sequence")).toList());
            // 조회 사이에 행이 추가돼도 다음 쪽은 흔들리지 않는다(최신순 keyset).
            seed(actor, "op_late" + seen.size(), "DENIED");
            cursor = (String) topLevelFieldsOf(next.body()).get("nextCursor");
        }
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(seen.stream().map(value -> ((Number) value).longValue()).toList())
                .isSortedAccordingTo((x, y) -> Long.compare(y, x));

        var denied = auditGet(operator, "outcome=DENIED&command=audit-export&scope=AUDIT_LEDGER&limit=200");
        assertThat(itemsIn(denied.body(), "items")).isNotEmpty()
                .allSatisfy(row -> assertThat(row).containsEntry("outcome", "DENIED"));
        LocalDate today = LocalDate.now(SEOUL);
        var dated = auditGet(operator, "from=" + today.minusDays(1) + "&to=" + today.plusDays(1));
        assertThat(itemsIn(dated.body(), "items")).isNotEmpty();
        var empty = auditGet(operator, "from=2001-01-01&to=2001-01-02");
        assertThat(itemsIn(empty.body(), "items")).isEmpty();

        // 데이터베이스가 받지 못하는 시각의 cursor도 500이 아니라 400이다.
        String outOfRange = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("999999999999999.0:1".getBytes(StandardCharsets.US_ASCII));
        for (String bad : List.of("cursor=%21%21", "cursor=" + "QQ", "cursor=" + outOfRange, "limit=0", "limit=abc",
                "outcome=MAYBE",
                "from=2026-13-01", "from=2026-10-07&to=2026-10-01", "command=DROP%20TABLE")) {
            var refused = auditGet(operator, bad);
            assertThat(refused.statusCode()).as(bad).isEqualTo(400);
            assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", "BAD_REQUEST");
        }
        // 상한을 넘는 쪽 크기는 상한으로 접힌다.
        assertThat(auditGet(operator, "limit=9999").statusCode()).isEqualTo(200);
    }

    /** 대량 추출은 그 자체가 별도 감사 사건이고, 묶음은 추출 시점의 원장을 다시 읽어 결정적이다. */
    @Test
    void 추출은_별도_감사_사건이고_결과는_원장을_다시_읽어_같다() throws Exception {
        Signed operator = operator("audit-export", "command,audit");
        seed(operator.user(), "op_before", "DENIED");
        String target = recentTarget();
        String key = key("audit-export");
        var exported = export(operator, target, key);
        String operationId = operationIdIn(exported.body());
        assertThat(outcomesOf(operationId)).containsExactly("REQUESTED", "COMPLETED");
        int rowCount = ((Number) topLevelFieldsOf(exported.body()).get("rowCount")).intValue();
        assertThat(rowCount).isEqualTo(itemsIn(exported.body(), "rows").size()).isEqualTo(2);

        // 추출 뒤에 원장이 늘어도 같은 키의 재전송은 같은 묶음이다.
        seed(operator.user(), "op_after", "DENIED");
        var replayed = confirmExport(after(operator, exported), operationId, phraseOf(target), key);
        assertThat(topLevelFieldsOf(replayed.body())).isEqualTo(topLevelFieldsOf(exported.body()));

        // 완료 행의 사실: 상한 sequence·행 수. 행 묶음 자체는 원장에 다시 쓰이지 않는다.
        var completed = jdbc().sql("SELECT details FROM operations_audit_event WHERE operation_id = :o "
                + "AND outcome = 'COMPLETED'").param("o", operationId).query(String.class).single();
        assertThat(completed).contains("\"rowCount\": 2", "\"upperSequence\"");
    }

    @Test
    void operation_조회는_자기_operation만_시간순으로_답한다() throws Exception {
        Signed operator = operator("audit-operation", "command,audit");
        Signed other = signedIn("audit-operation-other");
        operatorAccess().reinterpret(entry(operator.subject(), "command,audit") + ";"
                + entry(other.subject(), "command"));
        String operationId = operationIdIn(
                prepareExport(operator, recentTarget(), "사유", key("audit-operation")).body());
        cancelExport(operator);

        var mine = operationGet(operator, operationId);
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
        assertThat(itemsIn(mine.body(), "items")).extracting(row -> row.get("outcome"))
                .containsExactly("REQUESTED", "CANCELLED");

        var unknown = get("/api/nope", other.cookie());
        var theirs = operationGet(other, operationId);
        assertThat(theirs.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(theirs.body()).get("code")).isEqualTo(topLevelFieldsOf(unknown.body()).get("code"));
        assertThat(operationGet(operator, "op_missing").statusCode()).isEqualTo(404);
        assertThat(operationGet(operator, "not-an-operation").statusCode()).isEqualTo(404);
    }

    /** 추출 상한: 기간에 10,001행이면 오래된 순 앞쪽 10,000행이고 잘렸다고 말한다. */
    @Test
    void 추출은_상한에서_잘리고_오래된_순_앞쪽을_준다() throws Exception {
        Signed operator = operator("audit-cap", "command,audit");
        jdbc().sql("INSERT INTO operations_audit_event (audit_id, operation_id, actor_user_id, actor_digest, actor_kind, "
                        + "command, scope, target, reason, outcome, policy_version, requested_at, correlation_id) "
                        + "SELECT gen_random_uuid(), 'op_bulk' || n, NULL, :digest, 'SYSTEM', 'audit-export', "
                        + "'AUDIT_LEDGER', '2026-10-01~2026-10-02', '대량 행', 'REQUESTED', 'OPERATOR-COMMAND-1.0', "
                        + "statement_timestamp(), 'test-correlation' FROM generate_series(1, 10001) AS n")
                .param("digest", "c".repeat(64)).update();
        long first = jdbc().sql("SELECT min(sequence) FROM operations_audit_event").query(Long.class).single();
        var exported = export(operator, recentTarget(), key("audit-cap"));
        var fields = topLevelFieldsOf(exported.body());
        assertThat(fields).containsEntry("truncated", true).containsEntry("rowCount", 10_000);
        var rows = itemsIn(exported.body(), "rows");
        assertThat(rows).hasSize(10_000);
        assertThat(((Number) rows.getFirst().get("sequence")).longValue()).isEqualTo(first);
        assertThat(((Number) rows.getLast().get("sequence")).longValue()).isEqualTo(first + 9_999);
    }

    /** KST 기간 경계: 종료일 23:59:59.999999 KST의 행은 들고 다음 날 00:00 KST의 행은 빠진다(조회·추출 둘 다). */
    @Test
    void 기간은_KST_달력_날짜이고_양_끝을_포함한다() throws Exception {
        Signed operator = operator("audit-kst", "command,audit");
        seedAt(operator.user(), "op_last_moment", "2026-10-05T14:59:59.999999Z");
        seedAt(operator.user(), "op_next_day", "2026-10-05T15:00:00Z");
        seedAt(operator.user(), "op_day_start", "2026-10-04T15:00:00Z");
        seedAt(operator.user(), "op_day_before", "2026-10-04T14:59:59.999999Z");

        var listed = auditGet(operator, "from=2026-10-05&to=2026-10-05");
        assertThat(itemsIn(listed.body(), "items")).extracting(row -> row.get("operationId"))
                .containsExactlyInAnyOrder("op_last_moment", "op_day_start");

        var exported = export(operator, "2026-10-05~2026-10-05", key("audit-kst"));
        assertThat(itemsIn(exported.body(), "rows")).extracting(row -> row.get("operationId"))
                .as("추출은 원장 추가 순서다").containsExactly("op_last_moment", "op_day_start");
    }

    private void seedAt(UUID actor, String operationId, String recordedAt) {
        jdbc().sql("INSERT INTO operations_audit_event (audit_id, operation_id, actor_user_id, actor_digest, "
                        + "actor_kind, command, scope, target, reason, outcome, policy_version, requested_at, "
                        + "correlation_id, recorded_at) VALUES (:id, :op, :actor, :digest, 'OPERATOR', 'audit-export', "
                        + "'AUDIT_LEDGER', '2026-10-01~2026-10-02', '경계 행', 'DENIED', 'OPERATOR-COMMAND-1.0', "
                        + "CAST(:at AS timestamptz), 'test-correlation', CAST(:at AS timestamptz))")
                .param("id", UUID.randomUUID()).param("op", operationId).param("actor", actor)
                .param("digest", "d".repeat(64)).param("at", recordedAt).update();
    }

    /** 시험용 원장 행. 원장 SQL의 유일한 자리를 거치지 않는 것은 시험뿐이다. */
    private void seed(UUID actor, String operationId, String outcome) {
        jdbc().sql("INSERT INTO operations_audit_event (audit_id, operation_id, actor_user_id, actor_digest, "
                        + "actor_kind, command, scope, target, reason, outcome, policy_version, requested_at, "
                        + "decided_at, correlation_id, details) VALUES (:id, :op, :actor, :digest, 'OPERATOR', "
                        + "'audit-export', 'AUDIT_LEDGER', '2026-10-01~2026-10-02', '시험 행', :outcome, "
                        + "'OPERATOR-COMMAND-1.0', statement_timestamp(), statement_timestamp(), 'test-correlation', "
                        + "'{}'::jsonb)")
                .param("id", UUID.randomUUID()).param("op", operationId).param("actor", actor)
                .param("digest", "a".repeat(64)).param("outcome", outcome).update();
    }

    private static int count(String source, String word) {
        Matcher matcher = Pattern.compile("\\b" + word + "\\b").matcher(source);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }
}
