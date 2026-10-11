package com.bokrilog.operations;

import java.util.Set;

/**
 * 판정표 {@code OPS-SIGNAL-1.0}이 승인한 강한 신호의 종류(Story 3.3, {@code EPIC-IMPLEMENTATION-READINESS.md} §6.1).
 *
 * <p>이름이 곧 판정표 행의 {@code signal_type}이다. 이 enum과 {@code V19__operations_signal.sql}의 seed가 어긋나면
 * {@code SignalPolicyTest}가 실패한다. 판정표에 없는 것 — 단일 사용자의 인증 만료, timeout, rate limit, 5xx, 가격 결측,
 * 광고 오류, 정상 경합 — 은 여기 없으므로 신호로 승격할 길이 없다.
 *
 * <p>{@link #requiredEvidence()}는 코드가 아는 최소 필수 증거다. 판정표를 읽지 못해 전역으로 접을 때에도 이것은 먼저 본다 —
 * 빈 신호가 판정표 장애를 타고 전역 정지로 승격하지 않게. 판정표 행의 필수 증거와 같아야 한다(시험이 맞춘다).
 *
 * <p>이번 스토리가 실제 감지 지점에 연결한 것은 넷이다({@link #RESULT_HASH_MISMATCH}·{@link
 * #PORTFOLIO_INVARIANT_VIOLATION}·{@link #SOURCE_ID_CONFLICT}·{@link #UNKNOWN_TRANSACTION_ENUM}). Story 3.7이 {@link
 * #SECRET_EXPOSED}를 로그 계층에 연결했다 — 실제 구성 비밀값이 로그에 나타나면 루트 {@code LogSecretGuard}가 {@code
 * ruleId=CONFIGURED_SECRET_IN_LOG}로 올린다. 나머지는 판정표에는 있으나 감지 지점이 후속 스토리다.
 */
public enum SignalType {
    /** 1. 쓰기 가능 Bybit key가 저장 단계에 도달했다. */
    WRITE_KEY_STORED(Set.of("connectionId")),
    /** 2. 교차 사용자 데이터 접근이 성공했거나 공식 포인터가 섞였다. */
    CROSS_USER_ACCESS(Set.of("assertionId")),
    /** 3. secret·token이 redaction canary나 외부 telemetry에서 검출됐다. */
    SECRET_EXPOSED(Set.of("ruleId")),
    /** 4. 같은 canonical input·계산 정책에서 다른 result hash가 나왔다. */
    RESULT_HASH_MISMATCH(Set.of("calculationId")),
    /** 5. portfolio 행 잠금 아래에서 포인터 CAS 불변식이 깨졌다. */
    PORTFOLIO_INVARIANT_VIOLATION(Set.of("jobId")),
    /** 6. 같은 Bybit source ID가 다른 의미(semantic hash)로 관측됐다. */
    SOURCE_ID_CONFLICT(Set.of("jobId", "observationId")),
    /** 7. Transaction Log에 미인식 enum(종류)이 왔다. */
    UNKNOWN_TRANSACTION_ENUM(Set.of("jobId", "observationId")),
    /** 8. schema 검증 실패가 창 안에서 서로 다른 provider account 여럿에 각각 반복됐다. */
    SCHEMA_VALIDATION_BURST(Set.of("detectorRunId", "observedCount", "distinctSubjects", "minPerSubject",
            "windowSeconds")),
    /** 9. credential 복호화 실패가 창 안에서 서로 다른 사용자 여럿에 났다. */
    CREDENTIAL_DECRYPTION_BURST(Set.of("detectorRunId", "observedCount", "distinctSubjects", "minPerSubject",
            "windowSeconds"));

    private final Set<String> requiredEvidence;

    SignalType(Set<String> requiredEvidence) {
        this.requiredEvidence = requiredEvidence;
    }

    /** 코드가 아는 최소 필수 증거 키. */
    public Set<String> requiredEvidence() {
        return this.requiredEvidence;
    }
}
