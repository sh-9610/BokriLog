package com.bokrilog.calculation;

import com.bokrilog.calculation.internal.Conformance;
import com.bokrilog.calculation.internal.Decimals;
import com.bokrilog.calculation.internal.PlanFormula;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 최초 계획 지표 계산기({@code CALC-1.0}).
 *
 * <p>순수 함수다. 같은 {@link Input}은 호출 순서·시각·스레드와 관계없이 같은 {@link Result}와
 * 같은 canonical input·result hash를 낸다. 사건 순서는 입력 목록의 순서가 아니라
 * {@code (occurredAt, namespace, sourceId)}로 이 계산기가 정한다.
 *
 * <p>정의되지 않는 지표는 {@code 0}으로 두지 않고 지표별 {@link UnavailableReason}을 단다. 입력·
 * 중간값·결과 중 하나라도 표현 범위({@code |x| < 1E24}, 0이 아니면 {@code |x| >= 1E-24})를
 * 벗어나면 계산 전체를 {@link RejectionReason#CALCULATION_RANGE_EXCEEDED}로 거절한다 — 그 경우
 * 계획도 공식 결과도 만들지 않는다는 것이 호출자의 계약이다.
 */
public final class PlanCalculator {

    /** 계산 정책. 공식·경계·정밀도가 바뀌면 새 버전으로 발행하고 기존 결과를 보존한다. */
    public static final String CALCULATION_POLICY = "CALC-1.0";

    /** 달력 정책. {@code t0}의 {@code Asia/Seoul} 현지 날짜·시각에 고정한 일·주·월 경계. */
    public static final String CALENDAR_POLICY = "CALENDAR-SEOUL-1.0";

    /** 표시 정책. 표시만 HALF_UP 소수 둘째 자리이며 {@code -0.00}은 {@code 0.00}이다. */
    public static final String DISPLAY_POLICY = "DISPLAY-1.0";

    /** 정밀도 정책. 모든 산술과 거듭제곱에 같은 문맥을 쓴다. */
    public static final String PRECISION_POLICY = "DECIMAL128-HALF_EVEN";

    /** 계획 경계와 표시의 시간대. */
    public static final String TIME_ZONE = "Asia/Seoul";

    /**
     * 구현 버전. 정책이 같아도 거듭제곱 구현이 바뀌면 마지막 자리가 달라질 수 있으므로 버전 고정
     * 라이브러리 좌표를 함께 적는다({@code build.gradle.kts}).
     */
    public static final String IMPLEMENTATION_VERSION = "bokrilog-calc-1.0.0+big-math-2.3.2";

    private PlanCalculator() {}

    /** 복리 주기. {@code t0}의 서울 현지 시각에 고정된다. */
    public enum Period { DAILY, WEEKLY, MONTHLY }

    /** 여섯 지표. 순서가 곧 화면의 정보 순서다. */
    public enum Metric {
        CURRENT_VALUE,
        PLAN_AMOUNT,
        PLAN_DIFFERENCE,
        ADJUSTED_TARGET,
        REQUIRED_RATE,
        GOAL_PROGRESS
    }

    /** 계산 성격. {@code 계산 불가}는 이 값을 덮지 않고 {@link MetricResult#unavailableReason()}로 따로 둔다. */
    public enum Nature { OFFICIAL, ESTIMATED }

    /** 지표 하나가 정의되지 않는 이유. */
    public enum UnavailableReason {
        /** 현재가치가 0 이하라 남은 필요 등가수익률이 정의되지 않는다. */
        CURRENT_VALUE_NOT_POSITIVE,
        /** 조정 최종 목표가 0 이하다. 목표 기반 비율 둘이 정의되지 않고 입력 신뢰도가 검토 필요가 된다. */
        TARGET_NOT_POSITIVE,
        /** 목표 이익이 0 이하라 목표 수익 달성도가 정의되지 않는다. */
        GOAL_PROFIT_NOT_POSITIVE,
        /**
         * 남은 기간이 0 이하다. 진행 계산에서는 발행 자격({@code tc < T})이 이 경우를 먼저 막고, 완료 계산
         * ({@code u = T})에서는 남은 필요 등가수익률이 언제나 이 이유로 계산 불가다.
         */
        REMAINING_PERIOD_NOT_POSITIVE,
        /**
         * 완료 비교가 불가하다(Story 2.14 CV-11: 첫 종료 후 관측 지연이 24시간을 넘었거나 종료~관측 사이에 확인된
         * 외부 자금흐름이 있다). 관측과 엮인 계획 대비 차이와 목표 수익 달성도만 값이 없다 — 0으로 두지 않는다.
         */
        COMPLETION_NOT_COMPARABLE
    }

    /** 계산 전체의 거절. 이 경우 어떤 지표도 없다. */
    public enum RejectionReason {
        /** 입력·중간값·결과가 표현 범위를 벗어났거나 목표율이 {@code 0..10} 밖이다(CV-12). */
        CALCULATION_RANGE_EXCEEDED,
        /**
         * 입력이 plain decimal이 아니거나 입력 한도 밖이다. 또는 {@code P0 <= 0}이거나 시각 순서·사건
         * 유일성이 성립하지 않는다.
         */
        INPUT_INVALID,
        /** 이 구현이 모르는 계산·달력 정책이다. */
        POLICY_UNSUPPORTED
    }

    /** 계획 입력 신뢰도. 계획 행은 불변이므로 이 상태는 계산 버전이 소유한다. */
    public enum InputReliability { CONFIRMED, REVIEW_REQUIRED }

    /**
     * 외부 자금흐름 하나. 부호 있는 USD 가치이며 입금이 양수다.
     *
     * @param namespace 원천 범위(예: provider account). 같은 시각 사건의 안정 순서 둘째 키다
     * @param sourceId 원천 안의 사건 식별자. 셋째 키다
     * @param usdValue 앞선 {@code CALC-1.0} 환산의 결과(Story 2.6): 34 유효숫자·scale 24 이하
     * @param estimated 추정 가격으로 환산됐는가
     */
    public record Cashflow(Instant occurredAt, String namespace, String sourceId, String usdValue,
            boolean estimated) {
        public Cashflow {
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(usdValue, "usdValue");
        }
    }

    /**
     * 계산 입력. 금액·비율은 지수 표기 없는 plain decimal string이며 {@code CALC-1.0} §3.1의 입력
     * 한도 안이어야 한다 — 금액은 precision 38·scale 18, 목표율은 precision 18·scale 12. 34 유효숫자로
     * 미리 자르지 않는다: 38자리 금액도 받고, 34자리 {@code HALF_EVEN}은 첫 연산에서 적용된다. 표기가
     * 달라도 값이 같으면({@code "1250.00"}과 {@code "1250"}) 같은 입력이다. 한도 밖이면
     * {@link RejectionReason#INPUT_INVALID}다.
     *
     * @param principal 시작금액 {@code P0}. 0보다 커야 한다
     * @param startAt 계획 시작 {@code t0}
     * @param endAt 계획 종료 {@code T}
     * @param targetRate 주기당 목표율 {@code r}(비율, {@code 10% = "0.1"})
     * @param currentValue 기준시각의 현재가치 {@code E(tc)}
     * @param basisAt 계산 기준시각 {@code tc}
     */
    public record Input(String principal, Instant startAt, Instant endAt, Period period, String targetRate,
            String currentValue, Instant basisAt, List<Cashflow> cashflows, String calculationPolicy,
            String calendarPolicy) {
        public Input {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(targetRate, "targetRate");
            Objects.requireNonNull(currentValue, "currentValue");
            Objects.requireNonNull(basisAt, "basisAt");
            cashflows = List.copyOf(Objects.requireNonNull(cashflows, "cashflows"));
            Objects.requireNonNull(calculationPolicy, "calculationPolicy");
            Objects.requireNonNull(calendarPolicy, "calendarPolicy");
        }
    }

    /**
     * 완료 계산 입력(Story 2.14). PRD §4를 {@code u = T}로 적용한다: 기준시각은 계획 종료 {@code T}, 현재가치
     * {@code E}는 {@code T} 이후 첫 정상 관측값이다 — {@code T} 시각의 평가액이 아니며 보간하지 않는다. 흐름은
     * {@code t0 < ti <= T}만 반영하고 그 밖은 버린다.
     *
     * <p>완료 비교 가능 여부는 이 계산기가 스스로 판정한다(CV-11): 관측 지연이 {@link #observationWithinLimit}
     * 안이고 종료 후 확인된 외부 흐름이 없을 때만 비교 가능하다. 거짓이면 계획 대비 차이와 목표 수익 달성도가
     * {@link UnavailableReason#COMPLETION_NOT_COMPARABLE}이다. 관측 시각은 canonical 입력 hash에 들어간다.
     *
     * @param observedValue 첫 종료 후 관측값
     * @param observedMeasuredAt 그 관측의 측정 시각. {@code T} 이상이어야 한다(아니면 입력 오류)
     * @param postEndCashflow {@code T < ti <= observedMeasuredAt}에 확인된 외부 흐름이 있는가
     */
    public record CompletionInput(String principal, Instant startAt, Instant endAt, Period period, String targetRate,
            String observedValue, Instant observedMeasuredAt, boolean postEndCashflow, List<Cashflow> cashflows,
            String calculationPolicy, String calendarPolicy) {
        public CompletionInput {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(targetRate, "targetRate");
            Objects.requireNonNull(observedValue, "observedValue");
            Objects.requireNonNull(observedMeasuredAt, "observedMeasuredAt");
            cashflows = List.copyOf(Objects.requireNonNull(cashflows, "cashflows"));
            Objects.requireNonNull(calculationPolicy, "calculationPolicy");
            Objects.requireNonNull(calendarPolicy, "calendarPolicy");
        }

        /** 완료 비교가 가능한가: 관측 지연이 24시간 이하이고 종료 후 확인된 외부 흐름이 없다. */
        public boolean comparable() {
            return observationWithinLimit(this.endAt, this.observedMeasuredAt) && !this.postEndCashflow;
        }
    }

    /**
     * 첫 종료 후 관측이 완료 비교에 쓰일 수 있는 지연의 상한(CV-11). 정확히 24시간은 비교 가능이다("이하").
     */
    public static final java.time.Duration OBSERVATION_DELAY_LIMIT = java.time.Duration.ofHours(24);

    /** {@code measuredAt - T <= 24시간}이고 {@code measuredAt >= T}인가. 밀리초까지 비교한다. */
    public static boolean observationWithinLimit(Instant endAt, Instant measuredAt) {
        return !measuredAt.isBefore(endAt)
                && java.time.Duration.between(endAt, measuredAt).compareTo(OBSERVATION_DELAY_LIMIT) <= 0;
    }

    /** 지표 하나. 값이 없으면 이유가 있고, 이유가 있으면 값이 없다. */
    public record MetricResult(Metric metric, @Nullable String value, Nature nature,
            @Nullable UnavailableReason unavailableReason) {
        public MetricResult {
            Objects.requireNonNull(metric, "metric");
            Objects.requireNonNull(nature, "nature");
            if ((value == null) == (unavailableReason == null))
                throw new IllegalArgumentException("a metric has a value or a reason, not both");
        }
    }

    /**
     * 계산에 반영된 자금흐름 하나. {@code sequence}는 1부터의 적용 순서다.
     *
     * @param periodsToBasis {@code periods(ti, tc)}
     * @param periodsToEnd {@code periods(ti, T)}
     */
    public record AppliedCashflow(int sequence, Instant occurredAt, String namespace, String sourceId,
            String usdValue, boolean estimated, String periodsToBasis, String periodsToEnd) {}

    /** 계산 결과. */
    public sealed interface Result permits Result.Calculated, Result.Rejected {

        /** canonical 입력 hash. 거절에도 있다 — 같은 입력의 같은 거절을 재현할 수 있어야 한다. */
        String inputHash();

        /**
         * 여섯 지표와 재현 메타데이터.
         *
         * @param adjustedPrincipal {@code AP = P0 + ΣFi}
         * @param periodsElapsed {@code periods(t0, tc)}
         * @param periodsTotal {@code periods(t0, T)}
         * @param periodsRemaining {@code periods(tc, T)}
         * @param estimated 추정 흐름이 하나라도 쓰였는가
         */
        record Calculated(List<MetricResult> metrics, String adjustedPrincipal, String periodsElapsed,
                String periodsTotal, String periodsRemaining, List<AppliedCashflow> appliedCashflows,
                boolean estimated, InputReliability inputReliability, String inputHash, String resultHash,
                String calculationPolicy, String calendarPolicy, String displayPolicy,
                String precisionPolicy, String timeZone, String implementationVersion) implements Result {
            public Calculated {
                metrics = List.copyOf(metrics);
                appliedCashflows = List.copyOf(appliedCashflows);
            }

            /** 그 지표의 결과. 여섯 지표가 언제나 모두 있다. */
            public MetricResult metric(Metric metric) {
                return metrics.stream().filter(m -> m.metric() == metric).findFirst().orElseThrow();
            }
        }

        record Rejected(RejectionReason reason, String inputHash) implements Result {}
    }

    /** 계산한다. 예외를 던지지 않는다 — 범위·입력 오류는 {@link Result.Rejected}다. */
    public static Result calculate(Input input) {
        return PlanFormula.evaluate(Objects.requireNonNull(input, "input"));
    }

    /**
     * 완료 결과를 계산한다(Story 2.14). 진행 계산과 같은 식·같은 정책이며 {@code u = T}다: 경과 주기는 전체
     * 주기, 남은 주기는 0, 계획금액은 조정 최종 목표와 같다. 남은 필요 등가수익률은 언제나
     * {@link UnavailableReason#REMAINING_PERIOD_NOT_POSITIVE}다. 예외를 던지지 않는다.
     */
    public static Result complete(CompletionInput input) {
        return PlanFormula.evaluateCompletion(Objects.requireNonNull(input, "input"));
    }

    /**
     * plain decimal string의 손실 없는 정규 표기: 지수·앞의 0·끝의 0이 없고 0은 {@code "0"}이다
     * ({@code "1250.00"} → {@code "1250"}). 반올림하지 않으므로 값은 바뀌지 않는다. 모양이 틀리면
     * {@code null}이다.
     *
     * <p>저장·hash·응답에 같은 값의 표기 하나만 남기는 데 쓴다. 원문 그대로 두면 Bybit 원문의 끝 0 같은
     * 표기 차이가 canonical 값을 기대하는 쪽에서 값 전체를 거부하게 만든다.
     */
    public static @Nullable String plainDecimal(String raw) {
        return Decimals.notation(Objects.requireNonNull(raw, "raw"));
    }

    /**
     * 승인 고정 예제(CV-01~CV-07·CV-09~CV-12)를 이 구현이 글자까지 재현하는가.
     *
     * <p>호출자는 이 값이 거짓이면 공식 결과를 발행하지 않는다. 한 번 계산해 기억한다 — 입력이 상수라
     * 결과도 상수다.
     */
    public static boolean conformant() {
        return Conformance.passes();
    }
}
