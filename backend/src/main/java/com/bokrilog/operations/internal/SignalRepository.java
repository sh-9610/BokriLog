package com.bokrilog.operations.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 판정표 {@code operations_signal_policy}와 신호 기록 {@code operations_signal}에 닿는 유일한 자리(Story 3.3).
 *
 * <p>신호 기록은 추가 전용이다. 변경 문장은 삭제 port의 비식별 하나({@link #eraseTargetUser})뿐이고 행을 지우는 문장은
 * 없다. 감사 원장({@code operations_audit_event})은 여기서 다루지 않는다 — 원장 SQL의 자리는 {@link OperatorAuditRepository}
 * 하나다.
 */
@Repository
class SignalRepository {

    private final JdbcClient jdbc;

    SignalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 이 문장의 DB 시각. 신호 기록 트랜잭션의 첫 문장으로 읽어 감지 시각의 하한으로 쓴다. */
    Instant databaseNow() {
        return this.jdbc.sql("SELECT statement_timestamp()").query((rs, row) -> instant(rs, 1)).single();
    }

    /** 그 버전·종류의 판정표 행. 없으면 빈 값이고, scope 규칙이 깨져 있으면 {@code alternatives}가 비어 있다. */
    Optional<SignalPolicy> policy(String policyVersion, String signalType) {
        return this.jdbc.sql("SELECT policy_version, signal_type, threshold, per_subject_threshold, window_seconds, distinct_subjects, "
                        + "required_evidence, scope_rule FROM operations_signal_policy "
                        + "WHERE policy_version = :version AND signal_type = :type")
                .param("version", policyVersion)
                .param("type", signalType)
                .query(SignalRepository::policy)
                .optional();
    }

    /** 그 작업을 증거({@code jobId}·{@code attemptId})로 가진 신호 행들의 제한. */
    java.util.Set<UUID> restrictionsRaisedBy(UUID jobId) {
        return new java.util.HashSet<>(this.jdbc.sql("SELECT DISTINCT restriction_id FROM operations_signal "
                        + "WHERE evidence->>'jobId' = :job OR evidence->>'attemptId' = :job")
                .param("job", jobId.toString())
                .query(UUID.class)
                .list());
    }

    /** 기록할 신호 행 하나. */
    record NewSignal(UUID signalId, String operationId, String signalType, String policyVersion, String fingerprint,
            String restrictionScope, String scopeDecision, Instant detectedAt, Instant effectiveAt, String evidenceJson,
            @Nullable UUID targetUserId, UUID restrictionId, String outcome, long sliMs) {}

    void insert(NewSignal signal) {
        this.jdbc.sql("INSERT INTO operations_signal (signal_id, operation_id, signal_type, policy_version, fingerprint, "
                        + "restriction_scope, scope_decision, detected_at, effective_at, evidence, target_user_id, "
                        + "restriction_id, outcome, sli_ms) VALUES (:id, :operationId, :type, :version, :fingerprint, "
                        + ":scope, :decision, :detected, :effective, CAST(:evidence AS jsonb), :targetUser, "
                        + ":restriction, :outcome, :sli)")
                .param("id", signal.signalId())
                .param("operationId", signal.operationId())
                .param("type", signal.signalType())
                .param("version", signal.policyVersion())
                .param("fingerprint", signal.fingerprint())
                .param("scope", signal.restrictionScope())
                .param("decision", signal.scopeDecision())
                .param("detected", utc(signal.detectedAt()))
                .param("effective", utc(signal.effectiveAt()))
                .param("evidence", signal.evidenceJson())
                .param("targetUser", signal.targetUserId())
                .param("restriction", signal.restrictionId())
                .param("outcome", signal.outcome())
                .param("sli", signal.sliMs())
                .update();
    }

    /**
     * 삭제 port의 비식별 — 그 사용자에게 귀속된 신호 행의 {@code target_user_id}를 비운다. 증거의 내부 참조와 fingerprint
     * (keyed digest의 hash)는 남는다. 이미 비운 행은 술어가 고르지 않으므로 멱등이다.
     */
    int eraseTargetUser(UUID userId) {
        return this.jdbc.sql("UPDATE operations_signal SET target_user_id = NULL WHERE target_user_id = :user")
                .param("user", userId)
                .update();
    }

    private static SignalPolicy policy(ResultSet rs, int row) throws SQLException {
        Array array = rs.getArray("required_evidence");
        Set<String> required = new LinkedHashSet<>();
        if (array != null) {
            for (Object key : (Object[]) array.getArray()) {
                required.add((String) key);
            }
        }
        var alternatives = SignalPolicy.parse(rs.getString("scope_rule"));
        return new SignalPolicy(rs.getString("policy_version"), rs.getString("signal_type"), rs.getInt("threshold"),
                rs.getInt("per_subject_threshold"), rs.getInt("window_seconds"), rs.getInt("distinct_subjects"), Set.copyOf(required),
                alternatives == null ? List.of() : alternatives);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException("DB 시각을 읽지 못했다.");
        }
        return value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
