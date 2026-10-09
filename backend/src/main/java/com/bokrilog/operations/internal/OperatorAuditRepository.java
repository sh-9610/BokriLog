package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperatorAuditApi.Entry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 감사 원장 {@code operations_audit_event}의 <b>유일한</b> SQL 자리(Story 3.1 AC-4).
 *
 * <p><b>추가 전용이다.</b> 이 파일의 문장은 INSERT와 SELECT, 그리고 삭제 port의 비식별 문장 하나({@link #eraseActor})뿐이다.
 * 원 행을 고치거나 지우는 문장이 없고, {@code OperatorAuditTest}가 이 소스를 훑어 그 사실을 강제한다 — 그래서 이 파일의
 * 주석에도 그 두 SQL 낱말을 대문자로 적지 않는다(시험이 세는 것은 문자열이다).
 *
 * <p><b>추가는 advisory 잠금 하나로 직렬화한다</b>({@link #lockAppends}). 잠금을 쥔 트랜잭션 안에서 {@code sequence}가
 * 배정되고 {@code recorded_at}({@code statement_timestamp()})이 찍히므로 커밋된 행의 두 값이 같은 방향으로 는다. 그래서
 * {@code (recorded_at, sequence)} keyset cursor가 늦게 커밋되는 행을 건너뛰지 않고, 추출의 상한 {@code sequence}가 그 아래
 * 커밋되지 않은 행을 남기지 않는다. 운영 command는 드물어 이 직렬화의 비용은 없다.
 */
@Repository
class OperatorAuditRepository {

    /** 원장 추가 잠금의 키. 다른 advisory 잠금(계획 잠금은 {@code hashtextextended(user_id)})과 겹치지 않는 이름이다. */
    private static final String APPEND_LOCK_NAME = "bokrilog.operations_audit_event.append";

    /** allowlist 필드의 SELECT 목록. 조회·operation·추출이 같은 목록을 쓴다. */
    private static final String ENTRY_COLUMNS =
            "sequence, operation_id, actor_kind, actor_digest, command, scope, target, reason, outcome, "
                    + "details->>'code' AS code, policy_version, requested_at, decided_at, recorded_at, correlation_id";

    private final JdbcClient jdbc;

    OperatorAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 추가할 행 하나. {@code requestedAt}이 {@code null}이면 이 문장의 DB 시각이 요청 시각이다. */
    record NewRow(String operationId, @Nullable UUID actorUserId, String actorDigest, String actorKind,
            String command, String scope, String target, String reason, String outcome, String policyVersion,
            @Nullable Instant requestedAt, boolean decided, String correlationId, String detailsJson) {}

    /** 호출자 트랜잭션 끝까지 추가를 직렬화한다. 트랜잭션 밖에서 부르면 아무것도 지키지 않는다. */
    void lockAppends() {
        this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
                .param("name", APPEND_LOCK_NAME)
                .query((rs, row) -> 1)
                .single();
    }

    /**
     * 행 하나를 추가한다.
     *
     * @return 그 행의 요청 시각
     */
    Instant append(NewRow row) {
        return this.jdbc.sql("INSERT INTO operations_audit_event (audit_id, operation_id, actor_user_id, actor_digest, "
                        + "actor_kind, command, scope, target, reason, outcome, policy_version, requested_at, "
                        + "decided_at, correlation_id, details) VALUES (:auditId, :operationId, :actorUserId, "
                        + ":actorDigest, :actorKind, :command, :scope, :target, :reason, :outcome, :policyVersion, "
                        + "COALESCE(CAST(:requestedAt AS timestamptz), statement_timestamp()), "
                        + "CASE WHEN :decided THEN statement_timestamp() END, :correlationId, "
                        + "CAST(:details AS jsonb)) RETURNING requested_at")
                .param("auditId", UUID.randomUUID())
                .param("operationId", row.operationId())
                .param("actorUserId", row.actorUserId())
                .param("actorDigest", row.actorDigest())
                .param("actorKind", row.actorKind())
                .param("command", row.command())
                .param("scope", row.scope())
                .param("target", row.target())
                .param("reason", row.reason())
                .param("outcome", row.outcome())
                .param("policyVersion", row.policyVersion())
                .param("requestedAt", row.requestedAt() == null ? null
                        : OffsetDateTime.ofInstant(row.requestedAt(), ZoneOffset.UTC))
                .param("decided", row.decided())
                .param("correlationId", row.correlationId())
                .param("details", row.detailsJson())
                .query((rs, index) -> instant(rs, "requested_at"))
                .single();
    }

    /** 지금 원장의 마지막 {@code sequence}. 비었으면 0. {@link #lockAppends} 뒤에 읽어야 정확하다. */
    long maxSequence() {
        Long max = this.jdbc.sql("SELECT COALESCE(max(sequence), 0) FROM operations_audit_event")
                .query(Long.class).single();
        return max == null ? 0L : max;
    }

    /** 기간·상한 안의 행 수. {@code cap}에서 멈춘다. */
    int countWithin(Instant from, Instant until, long upperSequence, int cap) {
        Integer count = this.jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM operations_audit_event "
                        + "WHERE recorded_at >= :from AND recorded_at < :until AND sequence <= :upper LIMIT :cap) w")
                .param("from", utc(from))
                .param("until", utc(until))
                .param("upper", upperSequence)
                .param("cap", cap)
                .query(Integer.class).single();
        return count == null ? 0 : count;
    }

    /** 기간·상한 안의 행, 오래된 순. */
    List<Entry> within(Instant from, Instant until, long upperSequence, int limit) {
        return this.jdbc.sql("SELECT " + ENTRY_COLUMNS + " FROM operations_audit_event "
                        + "WHERE recorded_at >= :from AND recorded_at < :until AND sequence <= :upper "
                        + "ORDER BY sequence LIMIT :limit")
                .param("from", utc(from))
                .param("until", utc(until))
                .param("upper", upperSequence)
                .param("limit", limit)
                .query(OperatorAuditRepository::entry)
                .list();
    }

    /** 조회 조건. {@code null}인 성분은 거르지 않는다. */
    record Search(@Nullable Instant from, @Nullable Instant until, @Nullable String command, @Nullable String scope,
            @Nullable String outcome, @Nullable Instant beforeRecordedAt, long beforeSequence, int limit) {}

    /** 최신순 keyset 조회. {@code limit}개까지. */
    List<Entry> search(Search search) {
        // 조건 문자열은 이 메서드의 고정 조각뿐이다. 값은 모두 이름 붙은 매개변수로 간다.
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        Instant from = search.from();
        if (from != null) {
            where.add("recorded_at >= :from");
            params.put("from", utc(from));
        }
        Instant until = search.until();
        if (until != null) {
            where.add("recorded_at < :until");
            params.put("until", utc(until));
        }
        if (search.command() != null) {
            where.add("command = :command");
            params.put("command", search.command());
        }
        if (search.scope() != null) {
            where.add("scope = :scope");
            params.put("scope", search.scope());
        }
        if (search.outcome() != null) {
            where.add("outcome = :outcome");
            params.put("outcome", search.outcome());
        }
        Instant before = search.beforeRecordedAt();
        if (before != null) {
            where.add("(recorded_at, sequence) < (:beforeAt, :beforeSequence)");
            params.put("beforeAt", utc(before));
            params.put("beforeSequence", search.beforeSequence());
        }
        params.put("limit", search.limit());
        String sql = "SELECT " + ENTRY_COLUMNS + " FROM operations_audit_event"
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY recorded_at DESC, sequence DESC LIMIT :limit";
        return this.jdbc.sql(sql).params(params).query(OperatorAuditRepository::entry).list();
    }

    /** 한 수행자의 operation 행들, 오래된 순. */
    List<Entry> operation(UUID actorUserId, String operationId) {
        return this.jdbc.sql("SELECT " + ENTRY_COLUMNS + " FROM operations_audit_event "
                        + "WHERE operation_id = :operationId AND actor_user_id = :actor ORDER BY sequence")
                .param("operationId", operationId)
                .param("actor", actorUserId)
                .query(OperatorAuditRepository::entry)
                .list();
    }

    /** 같은 수행자·같은 멱등 키 digest의 {@code REQUESTED} 행. */
    record Requested(String operationId, @Nullable String requestDigest, Instant requestedAt, boolean settled) {}

    /**
     * 그 키의 첫 요청 행과, 그 operation이 끝났는지 — 완료·취소, 그리고 권한 회수로 거절된 것({@code ACCESS_REVOKED}).
     * 회수로 맺은 operation을 같은 키로 다시 무장하면 거절 뒤에 완료가 붙는다. 문구 불일치 거절과 실패는 끝이 아니다
     * (같은 무장으로 다시 시도할 수 있다).
     */
    Optional<Requested> requestedByKey(UUID actorUserId, String keyDigest) {
        return this.jdbc.sql("SELECT r.operation_id, r.details->>'requestDigest' AS request_digest, r.requested_at, "
                        + "EXISTS (SELECT 1 FROM operations_audit_event t WHERE t.operation_id = r.operation_id "
                        + "AND t.actor_user_id = r.actor_user_id AND (t.outcome IN ('COMPLETED', 'CANCELLED') "
                        + "OR (t.outcome = 'DENIED' AND t.details->>'code' = :revoked))) "
                        + "AS settled FROM operations_audit_event r WHERE r.actor_user_id = :actor "
                        + "AND r.outcome = 'REQUESTED' AND r.details->>'keyDigest' = :keyDigest "
                        + "ORDER BY r.sequence LIMIT 1")
                .param("actor", actorUserId)
                .param("keyDigest", keyDigest)
                .param("revoked", com.bokrilog.operations.OperatorCommandApi.ACCESS_REVOKED)
                .query((rs, row) -> new Requested(rs.getString("operation_id"), rs.getString("request_digest"),
                        instant(rs, "requested_at"), rs.getBoolean("settled")))
                .optional();
    }

    /**
     * 완료 행의 저장된 결과. 추출은 상한·행 수·잘림을, 제한 생성(Story 3.2)은 제한 ID·기능·scope·효력 시작을 {@code details}에
     * 남긴다. 그 command의 것이 아닌 값은 비어 있다.
     */
    record Completed(String target, @Nullable String keyDigest, long upperSequence, int rowCount, boolean truncated,
            @Nullable UUID restrictionId, @Nullable String feature, @Nullable String restrictionScope,
            @Nullable Instant effectiveFrom) {}

    Optional<Completed> completed(UUID actorUserId, String operationId) {
        return this.jdbc.sql("SELECT target, details->>'keyDigest' AS key_digest, "
                        + "COALESCE(CAST(details->>'upperSequence' AS bigint), 0) AS upper_sequence, "
                        + "COALESCE(CAST(details->>'rowCount' AS integer), 0) AS row_count, "
                        + "COALESCE(CAST(details->>'truncated' AS boolean), false) AS truncated, "
                        + "CAST(details->>'restrictionId' AS uuid) AS restriction_id, details->>'feature' AS feature, "
                        + "details->>'restrictionScope' AS restriction_scope, "
                        + "CAST(details->>'effectiveFrom' AS timestamptz) AS effective_from "
                        + "FROM operations_audit_event WHERE actor_user_id = :actor AND operation_id = :operationId "
                        + "AND outcome = 'COMPLETED' ORDER BY sequence LIMIT 1")
                .param("actor", actorUserId)
                .param("operationId", operationId)
                .query((rs, row) -> new Completed(rs.getString("target"), rs.getString("key_digest"),
                        rs.getLong("upper_sequence"), rs.getInt("row_count"), rs.getBoolean("truncated"),
                        rs.getObject("restriction_id", UUID.class), rs.getString("feature"),
                        rs.getString("restriction_scope"), nullableInstant(rs, "effective_from")))
                .optional();
    }

    /** 완료 행의 사유와 수행자 digest(Story 3.2: 활성 제한 목록이 그것을 만든 operation의 사실로 보인다). */
    record CompletedFacts(String reason, String actorDigest) {}

    /** operation ID마다 첫 완료 행의 사유·수행자 digest. 완료 행이 없는 operation은 빠진다. */
    Map<String, CompletedFacts> completedFacts(java.util.Collection<String> operationIds) {
        if (operationIds.isEmpty()) {
            return Map.of();
        }
        Map<String, CompletedFacts> facts = new LinkedHashMap<>();
        this.jdbc.sql("SELECT DISTINCT ON (operation_id) operation_id, reason, actor_digest FROM operations_audit_event "
                        + "WHERE outcome = 'COMPLETED' AND operation_id IN (:ids) ORDER BY operation_id, sequence")
                .param("ids", List.copyOf(operationIds))
                .query((java.sql.ResultSet rs) -> {
                    facts.put(rs.getString("operation_id"),
                            new CompletedFacts(rs.getString("reason"), rs.getString("actor_digest")));
                });
        return facts;
    }

    /**
     * 삭제 port의 비식별 — 이 파일의 유일한 변경 문장이다. 그 사용자의 actor 귀속만 비우고 비식별 시각을 남긴다.
     * digest·사실·다른 사용자의 행·SYSTEM 행은 그대로다. 이미 비운 행은 술어가 다시 고르지 않으므로 멱등이다.
     */
    int eraseActor(UUID userId) {
        return this.jdbc.sql("UPDATE operations_audit_event SET actor_user_id = NULL, "
                        + "actor_erased_at = statement_timestamp() WHERE actor_user_id = :actor")
                .param("actor", userId)
                .update();
    }

    private static Entry entry(ResultSet rs, int row) throws SQLException {
        return new Entry(rs.getLong("sequence"), rs.getString("operation_id"), rs.getString("actor_kind"),
                rs.getString("actor_digest"), rs.getString("command"), rs.getString("scope"), rs.getString("target"),
                rs.getString("reason"), rs.getString("outcome"), rs.getString("code"),
                rs.getString("policy_version"), instant(rs, "requested_at"), nullableInstant(rs, "decided_at"),
                instant(rs, "recorded_at"), rs.getString("correlation_id"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException("감사 행의 필수 시각이 비어 있다.");
        }
        return value.toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
