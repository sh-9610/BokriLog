package com.bokrilog.sync;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 동기화 계측의 닫힌 어휘(Story 3.7 AC-3). 메트릭 이름과 tag 값은 여기 있는 것뿐이다.
 *
 * <p>루트 {@code TelemetryAllowlist}가 이 어휘로 허용 표의 동기화 행을 만들고, sync 모듈의 계측이 같은 어휘로 등록한다 — 두
 * 곳이 갈라질 자리가 없다. tag 값은 모두 코드가 정한 enum 문자열이다. 사용자·계획·작업·연결 ID, 금액, Bybit 원문은 tag에도
 * 이름에도 없다.
 *
 * <p><b>정의(분모·기간·환경).</b>
 * <ul>
 *   <li>{@link #ATTEMPTS} {@code bokrilog_sync_attempts_total{kind,trigger}} — <b>유효 시도</b>: admission을 통과해 작업 행이
 *       커밋된 수. 같은 키의 재전송·진행 중 합류·쿨다운·주기 미도래는 시도가 아니다.
 *   <li>{@link #OUTCOMES} {@code bokrilog_sync_outcomes_total{kind,outcome,reason}} — 시도의 결말과 admission 제외. {@code
 *       success}(발행·관측 성공), {@code failure}(실패·검토 필요 — Bybit 오류를 포함하며 임의로 빼지 않는다), {@code
 *       excluded}(제한·검증 보류로 admission이 거절했거나 실행 중 제한·권한·계획 변경으로 막혔다). 실패율의 분모는 {@code
 *       success + failure}다 — {@code excluded}는 시도가 실패한 것이 아니라 정책이 막은 것이다.
 *   <li>{@link #QUEUE_AGE} {@code bokrilog_sync_queue_age_seconds{kind}} — 그 종류에서 가장 오래 기다린 {@code QUEUED} 작업의
 *       나이(초). DB 시각 기준이고 주기 측정이다(15초). 대기 작업이 없으면 0, 측정하지 못했으면 NaN이다.
 * </ul>
 * 기간은 프로세스 수명이다(카운터는 재기동에 0으로 돌아간다 — 비율은 {@code increase}/{@code rate}로 본다). 환경은 공통 tag
 * {@code env}가 가른다.
 */
public final class SyncTelemetry {

    /** 유효 시도 카운터. Prometheus 이름 {@code bokrilog_sync_attempts_total}. */
    public static final String ATTEMPTS = "bokrilog.sync.attempts";

    /** 결말·제외 카운터. Prometheus 이름 {@code bokrilog_sync_outcomes_total}. */
    public static final String OUTCOMES = "bokrilog.sync.outcomes";

    /** 대기 나이 gauge(초). Prometheus 이름 {@code bokrilog_sync_queue_age_seconds}. */
    public static final String QUEUE_AGE = "bokrilog.sync.queue.age";

    /** 작업 종류. tag 값은 {@link #tag()}다. */
    public enum Kind {
        /** 계획의 증분 동기화·정밀 검증·완료 관측(Story 2.11~2.14). 시도·결말을 센다. */
        PLAN_SYNC("plan_sync"),
        /** 첫 스냅샷·후속 기준 스냅샷(Story 2.2·2.15). 시도·결말을 센다. */
        FIRST_SNAPSHOT("first_snapshot"),
        /** 초기 수집(Story 2.4). 대기 나이만. */
        INITIAL_COLLECTION("initial_collection"),
        /** 외부 자금흐름 판정(Story 2.5). 대기 나이만. */
        CLASSIFICATION("classification"),
        /** USD 환산(Story 2.6). 대기 나이만. */
        CONVERSION("conversion"),
        /** 최초 계획 발행(Story 2.7). 대기 나이만. */
        FIRST_PUBLICATION("first_publication");

        private final String tag;

        Kind(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return this.tag;
        }
    }

    /** {@code trigger} tag 값: 계획 동기화의 세 trigger, 시스템 검증, 사용자가 연 첫 스냅샷, 그리고 {@link #OTHER}. */
    public static final Set<String> TRIGGERS = Set.of("ENTRY", "VISIBILITY", "MANUAL", "SYSTEM", "USER", "OTHER");

    public static final String SUCCESS = "success";
    public static final String FAILURE = "failure";
    public static final String EXCLUDED = "excluded";

    /** {@code outcome} tag 값. */
    public static final Set<String> OUTCOME_VALUES = Set.of(SUCCESS, FAILURE, EXCLUDED);

    /** 성공의 {@code reason}. */
    public static final String NONE = "NONE";

    /** 닫힌 목록 밖의 code가 접히는 {@code reason}. 새 code가 tag 값을 열지 못하게 한다. */
    public static final String OTHER = "OTHER";

    /**
     * {@code reason} tag 값: 작업 행의 닫힌 실패·차단 code(V11~V14 CHECK, 첫 스냅샷의 거절 이유), admission 제외 이유
     * ({@code RESTRICTED}·{@code REVIEW_HELD}·{@code LONG_GAP}), 그리고 {@link #NONE}·{@link #OTHER}.
     */
    public static final Set<String> REASONS = java.util.stream.Stream.concat(Arrays.stream(new String[] {
            NONE, OTHER,
            // admission 제외(제한·검증 보류).
            "RESTRICTED", "REVIEW_HELD", "LONG_GAP",
            // 계획 동기화 작업의 실패·검토·차단 code.
            "PROVIDER_UNAVAILABLE", "PROVIDER_TIME_UNTRUSTED", "PROVIDER_END_NOT_REACHED", "SOURCE_REJECTED",
            "CONVERSION_REQUIRED", "CALCULATION_RANGE_EXCEEDED", "CALCULATION_INPUT_INVALID",
            "CALCULATION_POLICY_UNVERIFIED", "SYNC_INTERRUPTED", "SYNC_FAILED", "SOURCE_REQUIRES_REVIEW",
            "SOURCE_EVENT_MISSING", "HISTORY_OUT_OF_RANGE", "CLASSIFICATION_REQUIRES_REVIEW",
            "CONVERSION_REQUIRES_REVIEW", "NOT_PERMITTED", "PLAN_CHANGED", "MEMBERSHIP_CHANGED", "POINTER_CONFLICT",
            "INPUTS_CHANGED", "PLAN_PERIOD_ENDED", "SIGNAL_UNRECORDED"}),
            // 첫 스냅샷 작업의 실패 이유: 제공자 거절 이유(닫힌 enum)와 미분류 실패 UNAVAILABLE(그 enum에 있다).
            Arrays.stream(com.bokrilog.source.SourceRejectionReason.values()).map(Enum::name))
            .collect(Collectors.toUnmodifiableSet());

    private SyncTelemetry() {}

    /** 닫힌 목록의 {@code trigger}. 목록 밖이거나 비어 있으면 {@link #OTHER}. */
    public static String trigger(String trigger) {
        return trigger != null && TRIGGERS.contains(trigger) ? trigger : OTHER;
    }

    /** 닫힌 목록의 {@code reason}. 목록 밖이거나 비어 있으면 {@link #OTHER}. */
    public static String reason(String code) {
        return code != null && REASONS.contains(code) ? code : OTHER;
    }
}
