package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.operations.OperationalSignalApi;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.Signal;
import com.bokrilog.operations.SignalType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 3.3 AC-1·AC-5: 판정표 {@code OPS-SIGNAL-1.0}은 버전 seed이고 코드의 신호 enum·현행 버전 상수·최소 필수 증거와 같다.
 * 판정표를 읽지 못하거나 행이 없으면 신규 연결+동기화 전역으로 접는다(fail-closed) — 그때도 빈 신호는 승격하지 않는다.
 *
 * <p>고정 fixture는 {@code EPIC-IMPLEMENTATION-READINESS.md} §6.1을 옮긴 것이다. seed를 바꾸려면 새 버전을 발행하고 이
 * fixture와 {@link OperationalSignalApi#POLICY_VERSION}을 함께 바꾼다.
 */
class SignalPolicyTest extends AuthFlowTestBase {

    /** 종류 → threshold|대상당 threshold|window|서로 다른 대상 수|필수 증거|scope 규칙. */
    private static final Map<String, String> FIXTURE = Map.of(
            "WRITE_KEY_STORED", "1|1|0|1|connectionId|NEW_CONNECTION:GLOBAL+SYNC:GLOBAL",
            "CROSS_USER_ACCESS", "1|1|0|1|assertionId|NEW_CONNECTION:GLOBAL+SYNC:GLOBAL+CALCULATION_DISPLAY:GLOBAL",
            "SECRET_EXPOSED", "1|1|0|1|ruleId|NEW_CONNECTION:GLOBAL+SYNC:GLOBAL+CALCULATION_DISPLAY:GLOBAL",
            "RESULT_HASH_MISMATCH", "1|1|0|1|calculationId|CALCULATION_DISPLAY:CALCULATION_POLICY_VERSION"
                    + "|SYNC:GLOBAL+CALCULATION_DISPLAY:GLOBAL",
            "PORTFOLIO_INVARIANT_VIOLATION", "1|1|0|1|jobId|SYNC:PORTFOLIO+CALCULATION_DISPLAY:PORTFOLIO"
                    + "|SYNC:GLOBAL+CALCULATION_DISPLAY:GLOBAL",
            "SOURCE_ID_CONFLICT", "1|1|0|1|jobId,observationId|SYNC:PLAN|SYNC:PROVIDER_CONNECTION"
                    + "|NEW_CONNECTION:GLOBAL+SYNC:GLOBAL",
            "UNKNOWN_TRANSACTION_ENUM", "1|1|0|1|jobId,observationId|SYNC:PROVIDER_CONNECTION"
                    + "|NEW_CONNECTION:GLOBAL+SYNC:GLOBAL",
            "SCHEMA_VALIDATION_BURST", "6|3|300|2|detectorRunId,observedCount,distinctSubjects,minPerSubject,"
                    + "windowSeconds|NEW_CONNECTION:GLOBAL+SYNC:GLOBAL",
            "CREDENTIAL_DECRYPTION_BURST", "2|1|300|2|detectorRunId,observedCount,distinctSubjects,minPerSubject,"
                    + "windowSeconds|NEW_CONNECTION:GLOBAL+SYNC:GLOBAL");

    @Autowired
    private OperationalSignalApi signals;

    @Test
    void 현행_판정표는_9행이고_enum과_최소_필수_증거와_고정_fixture와_같다() {
        assertThat(OperationalSignalApi.POLICY_VERSION).isEqualTo("OPS-SIGNAL-1.0");
        var rows = jdbc().sql("SELECT signal_type, threshold || '|' || per_subject_threshold || '|' || window_seconds "
                        + "|| '|' || distinct_subjects || '|' || array_to_string(required_evidence, ',') || '|' "
                        + "|| scope_rule AS row FROM operations_signal_policy WHERE policy_version = :v")
                .param("v", OperationalSignalApi.POLICY_VERSION)
                .query((rs, n) -> Map.entry(rs.getString("signal_type"), rs.getString("row")))
                .list();
        Map<String, String> seed = new HashMap<>();
        rows.forEach(row -> seed.put(row.getKey(), row.getValue()));
        // 현행 버전 상수가 가리키는 행 집합이 enum과 같다 — 다음 버전의 행이 더해져도 이 판정은 그대로다.
        assertThat(rows).hasSize(SignalType.values().length).hasSize(9);
        assertThat(seed.keySet()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(SignalType.values()).map(Enum::name).toList());
        assertThat(seed).isEqualTo(FIXTURE);
        for (SignalType type : SignalType.values()) {
            Set<String> seeded = Set.of(seed.get(type.name()).split("\\|")[4].split(","));
            assertThat(type.requiredEvidence()).as(type.name()).isEqualTo(seeded);
        }
    }

    /** 모든 행의 scope 규칙이 실제로 읽힌다 — 깨진 규칙은 판정표 장애로 접히므로, 각 종류가 그것으로 접히지 않아야 한다. */
    @Test
    void 모든_행의_scope_규칙이_읽힌다() {
        for (SignalType type : SignalType.values()) {
            jdbc().sql("TRUNCATE TABLE operations_signal, operations_restriction").update();
            var raised = this.signals.raise(new Signal(type, fullSubject(), UUID.randomUUID(), fullEvidence(type),
                    null));
            assertThat(raised.decisions()).as(type.name()).isNotEmpty();
            assertThat(jdbc().sql("SELECT DISTINCT scope_decision FROM operations_signal").query(String.class).list())
                    .as(type.name()).containsExactly("POLICY");
        }
    }

    /**
     * 판정표 행이 없으면 신규 연결+동기화 전역이다. 감사 사유는 여전히 신호 종류이고 {@code POLICY_UNAVAILABLE}은 판정
     * 이유다. 코드의 최소 필수 증거가 빠진 신호는 그때도 승격하지 않는다.
     */
    @Test
    void 판정표_행이_없으면_신규_연결과_동기화를_전역으로_막는다() {
        var row = jdbc().sql("SELECT threshold, per_subject_threshold, window_seconds, distinct_subjects, "
                        + "array_to_string(required_evidence, ','), scope_rule FROM operations_signal_policy "
                        + "WHERE policy_version = :v AND signal_type = 'SOURCE_ID_CONFLICT'")
                .param("v", OperationalSignalApi.POLICY_VERSION)
                .query((rs, n) -> List.<Object>of(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6)))
                .single();
        jdbc().sql("DELETE FROM operations_signal_policy WHERE policy_version = :v AND signal_type = 'SOURCE_ID_CONFLICT'")
                .param("v", OperationalSignalApi.POLICY_VERSION).update();
        try {
            assertThatThrownBy(() -> this.signals.raise(new Signal(SignalType.SOURCE_ID_CONFLICT, fullSubject(),
                    UUID.randomUUID(), Map.of(), null))).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc().sql("SELECT count(*) FROM operations_signal").query(Integer.class).single()).isZero();

            this.signals.raise(new Signal(SignalType.SOURCE_ID_CONFLICT, fullSubject(), UUID.randomUUID(),
                    fullEvidence(SignalType.SOURCE_ID_CONFLICT), null));
            assertFallback("SOURCE_ID_CONFLICT");
        } finally {
            jdbc().sql("INSERT INTO operations_signal_policy (policy_version, signal_type, threshold, "
                            + "per_subject_threshold, window_seconds, distinct_subjects, required_evidence, scope_rule) "
                            + "VALUES (:v, 'SOURCE_ID_CONFLICT', :t, :p, :w, :d, string_to_array(:e, ','), :r)")
                    .param("v", OperationalSignalApi.POLICY_VERSION).param("t", row.get(0)).param("p", row.get(1))
                    .param("w", row.get(2)).param("d", row.get(3)).param("e", row.get(4)).param("r", row.get(5))
                    .update();
        }
    }

    /** 판정표를 읽지 못해도(표가 없다) 같은 전역 제한을 한 트랜잭션에 쓴다 — savepoint가 읽기 실패만 되돌린다. */
    @Test
    void 판정표를_읽지_못하면_신규_연결과_동기화를_전역으로_막는다() {
        jdbc().sql("ALTER TABLE operations_signal_policy RENAME TO operations_signal_policy_away").update();
        try {
            this.signals.raise(new Signal(SignalType.UNKNOWN_TRANSACTION_ENUM, fullSubject(), UUID.randomUUID(),
                    fullEvidence(SignalType.UNKNOWN_TRANSACTION_ENUM), null));
            assertFallback("UNKNOWN_TRANSACTION_ENUM");
        } finally {
            jdbc().sql("ALTER TABLE operations_signal_policy_away RENAME TO operations_signal_policy").update();
        }
    }

    private void assertFallback(String type) {
        assertThat(jdbc().sql("SELECT signal_type || '|' || restriction_scope || '|' || scope_decision || '|' || outcome "
                + "FROM operations_signal ORDER BY restriction_scope").query(String.class).list()).containsExactly(
                type + "|NEW_CONNECTION:GLOBAL|POLICY_UNAVAILABLE|CREATED",
                type + "|SYNC:GLOBAL|POLICY_UNAVAILABLE|CREATED");
        assertThat(jdbc().sql("SELECT feature || ':' || scope || ':' || origin FROM operations_restriction "
                + "ORDER BY feature").query(String.class).list())
                .containsExactly("NEW_CONNECTION:GLOBAL:SIGNAL", "SYNC:GLOBAL:SIGNAL");
        assertThat(jdbc().sql("SELECT DISTINCT reason || '|' || (details->>'scopeDecision') FROM operations_audit_event "
                + "WHERE actor_kind = 'SYSTEM'").query(String.class).list())
                .containsExactly(type + "|" + OperationalSignalApi.POLICY_UNAVAILABLE);
    }

    private static RestrictionSubject fullSubject() {
        return RestrictionSubject.none().withPlan(UUID.randomUUID()).withPortfolio(UUID.randomUUID())
                .withConnection(UUID.randomUUID()).withCalculationPolicy("CALC-1.0");
    }

    /** 그 종류의 필수 증거를 모두 채운 증거. 창 집계는 판정표를 넉넉히 채운다. */
    private static Map<String, String> fullEvidence(SignalType type) {
        Map<String, String> evidence = new HashMap<>();
        for (String key : type.requiredEvidence()) {
            evidence.put(key, switch (key) {
                case "observedCount" -> "10";
                case "distinctSubjects" -> "3";
                case "minPerSubject" -> "3";
                case "windowSeconds" -> "300";
                default -> UUID.randomUUID().toString();
            });
        }
        return evidence;
    }
}
