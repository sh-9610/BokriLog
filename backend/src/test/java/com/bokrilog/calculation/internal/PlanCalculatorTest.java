package com.bokrilog.calculation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.calculation.PlanCalculator.Cashflow;
import com.bokrilog.calculation.PlanCalculator.InputReliability;
import com.bokrilog.calculation.PlanCalculator.Metric;
import com.bokrilog.calculation.PlanCalculator.Nature;
import com.bokrilog.calculation.PlanCalculator.Period;
import com.bokrilog.calculation.PlanCalculator.RejectionReason;
import com.bokrilog.calculation.PlanCalculator.Result;
import com.bokrilog.calculation.PlanCalculator.UnavailableReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code CALC-1.0}·{@code CALENDAR-SEOUL-1.0}의 계약 fixture.
 *
 * <p>ERD-1.0.0 §3.4의 표가 적은 34자리 값을 <b>두 번</b> 대조한다. 계산기의 내부값(canonical로 바꾸기
 * 전)은 표의 값과 수치로 같아야 하고, 기록되는 canonical 문자열은 표의 값을 같은 canonical 규칙
 * (34 유효숫자·scale 24·HALF_EVEN·끝의 0 없음)으로 옮긴 문자열과 글자까지 같아야 한다. 기대값은
 * {@link Conformance}의 표를 읽지 않고 여기 따로 적는다 — 그 표가 틀리면 이 시험이 먼저 깨진다.
 */
class PlanCalculatorTest {

    private static final Instant T0 =
            LocalDateTime.parse("2027-01-04T09:00").atZone(SeoulCalendar.ZONE).toInstant();
    private static final long DAY = 86_400_000L;

    private static Instant day(double days) {
        return T0.plusMillis(Math.round(days * DAY));
    }

    private static PlanCalculator.Input daily(String p0, String rate, String e, List<Cashflow> flows) {
        return new PlanCalculator.Input(p0, T0, day(3), Period.DAILY, rate, e, day(2), flows,
                PlanCalculator.CALCULATION_POLICY, PlanCalculator.CALENDAR_POLICY);
    }

    private static Cashflow flow(Instant at, String id, String usd) {
        return new Cashflow(at, "account-1", id, usd, false);
    }

    private static Result.Calculated calculated(PlanCalculator.Input input) {
        Result result = PlanCalculator.calculate(input);
        assertThat(result).isInstanceOf(Result.Calculated.class);
        return (Result.Calculated) result;
    }

    private static String value(Result.Calculated result, Metric metric) {
        return result.metric(metric).value();
    }

    /** CV-01·CV-02·CV-03: 표의 34자리 값과 내부값·canonical 값이 함께 맞는다. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
        "CV-01;1250;;1210;1331;40;0.0648;0.7552870090634441087613293051359517",
        "CV-02;1340;100;1320;1452;20;0.083582089552238805970149253731343;0.6818181818181818181818181818181818",
        "CV-03;950;-200;990;1089;-40;0.146315789473684210526315789473684;0.5190311418685121107266435986159170"})
    void approvedVectorsMatchTheTableInternallyAndCanonically(String id, String current, String deposit,
            String plan, String target, String difference, String requiredRate, String goalProgress) {
        List<Cashflow> flows = deposit == null ? List.of() : List.of(flow(day(1), id, deposit));
        PlanCalculator.Input input = daily("1000", "0.1", current, flows);

        Map<Metric, BigDecimal> raw = PlanFormula.rawValues(input);
        assertThat(raw).isNotNull();
        assertThat(raw.get(Metric.PLAN_AMOUNT)).isEqualByComparingTo(plan);
        assertThat(raw.get(Metric.ADJUSTED_TARGET)).isEqualByComparingTo(target);
        assertThat(raw.get(Metric.PLAN_DIFFERENCE)).isEqualByComparingTo(difference);
        assertThat(raw.get(Metric.REQUIRED_RATE)).isEqualByComparingTo(requiredRate);
        assertThat(raw.get(Metric.GOAL_PROGRESS)).isEqualByComparingTo(goalProgress);

        Result.Calculated result = calculated(input);
        assertThat(value(result, Metric.CURRENT_VALUE)).isEqualTo(current);
        assertThat(value(result, Metric.PLAN_AMOUNT)).isEqualTo(Decimals.canonical(new BigDecimal(plan)));
        assertThat(value(result, Metric.ADJUSTED_TARGET)).isEqualTo(Decimals.canonical(new BigDecimal(target)));
        assertThat(value(result, Metric.PLAN_DIFFERENCE)).isEqualTo(Decimals.canonical(new BigDecimal(difference)));
        assertThat(value(result, Metric.REQUIRED_RATE)).isEqualTo(Decimals.canonical(new BigDecimal(requiredRate)));
        assertThat(value(result, Metric.GOAL_PROGRESS)).isEqualTo(Decimals.canonical(new BigDecimal(goalProgress)));
        assertThat(result.inputReliability()).isEqualTo(InputReliability.CONFIRMED);
        assertThat(result.metrics()).allSatisfy(metric -> assertThat(metric.nature()).isEqualTo(Nature.OFFICIAL));
    }

    @Test
    void canonicalFormsOfTheTableValuesAreWrittenOutOnce() {
        // The literal canonical strings, so a change to the canonical rule itself is visible here.
        Result.Calculated cv01 = calculated(daily("1000", "0.1", "1250", List.of()));
        assertThat(value(cv01, Metric.PLAN_AMOUNT)).isEqualTo("1210");
        assertThat(value(cv01, Metric.ADJUSTED_TARGET)).isEqualTo("1331");
        assertThat(value(cv01, Metric.PLAN_DIFFERENCE)).isEqualTo("40");
        assertThat(value(cv01, Metric.REQUIRED_RATE)).isEqualTo("0.0648");
        assertThat(value(cv01, Metric.GOAL_PROGRESS)).isEqualTo("0.755287009063444108761329");
        Result.Calculated cv02 = calculated(daily("1000", "0.1", "1340", List.of(flow(day(1), "in", "100"))));
        assertThat(value(cv02, Metric.REQUIRED_RATE)).isEqualTo("0.083582089552238805970149");
        assertThat(value(cv02, Metric.GOAL_PROGRESS)).isEqualTo("0.681818181818181818181818");
        Result.Calculated cv03 = calculated(daily("1000", "0.1", "950", List.of(flow(day(1), "out", "-200"))));
        assertThat(value(cv03, Metric.PLAN_DIFFERENCE)).isEqualTo("-40");
        assertThat(value(cv03, Metric.REQUIRED_RATE)).isEqualTo("0.146315789473684210526316");
        assertThat(value(cv03, Metric.GOAL_PROGRESS)).isEqualTo("0.519031141868512110726644");
        assertThat(cv03.adjustedPrincipal()).isEqualTo("800");
        assertThat(cv03.periodsElapsed()).isEqualTo("2");
        assertThat(cv03.periodsTotal()).isEqualTo("3");
        assertThat(cv03.periodsRemaining()).isEqualTo("1");
    }

    /** CV-04: 같은 시각 +100, −30, −70은 모두 보존·반영되고 대수합 0이며 순서와 무관하다. */
    @Test
    void sameInstantEventsAreKeptAppliedOnceAndOrderIndependent() {
        List<Cashflow> flows = new ArrayList<>(List.of(flow(day(1), "b-30", "-30"), flow(day(1), "a+100", "100"),
                flow(day(1), "c-70", "-70")));
        Result.Calculated first = calculated(daily("1000", "0.1", "1250", flows));
        assertThat(first.appliedCashflows()).extracting(PlanCalculator.AppliedCashflow::sourceId)
                .containsExactly("a+100", "b-30", "c-70");
        assertThat(first.adjustedPrincipal()).isEqualTo("1000");
        assertThat(value(first, Metric.PLAN_AMOUNT)).isEqualTo("1210");
        assertThat(value(first, Metric.GOAL_PROGRESS)).isEqualTo("0.755287009063444108761329");
        Random shuffle = new Random(20260928L);
        for (int i = 0; i < 10; i++) {
            Collections.shuffle(flows, shuffle);
            Result again = PlanCalculator.calculate(daily("1000", "0.1", "1250", flows));
            assertThat(again).isEqualTo(first);
        }
        // Different values in the same slot are a different input.
        Result other = PlanCalculator.calculate(daily("1000", "0.1", "1250",
                List.of(flow(day(1), "a+100", "100"))));
        assertThat(other.inputHash()).isNotEqualTo(first.inputHash());
    }

    /** CV-05 and the event window {@code t0 < ti <= min(tc, T)}. */
    @Test
    void onlyEventsInsideTheWindowAreApplied() {
        Result.Calculated result = calculated(daily("1000", "0.1", "1250", List.of(
                flow(T0, "at-t0", "100"), flow(day(2), "at-tc", "10"), flow(day(2.5), "after-tc", "1000"),
                flow(day(4), "after-T", "1000"), flow(day(-1), "before-t0", "1000"))));
        assertThat(result.appliedCashflows()).extracting(PlanCalculator.AppliedCashflow::sourceId)
                .containsExactly("at-tc");
        // ti = tc grows for zero periods toward tc and one period toward T.
        assertThat(result.appliedCashflows().getFirst().periodsToBasis()).isEqualTo("0");
        assertThat(result.appliedCashflows().getFirst().periodsToEnd()).isEqualTo("1");
        assertThat(value(result, Metric.PLAN_AMOUNT)).isEqualTo("1220");
        assertThat(value(result, Metric.ADJUSTED_TARGET)).isEqualTo("1342");
        assertThat(result.adjustedPrincipal()).isEqualTo("1010");
    }

    @Test
    void aDuplicatedEventIdentityIsRejectedInsteadOfAppliedTwice() {
        Result result = PlanCalculator.calculate(daily("1000", "0.1", "1250",
                List.of(flow(day(1), "same", "100"), flow(day(1.5), "same", "100"))));
        assertThat(result).isInstanceOf(Result.Rejected.class);
        assertThat(((Result.Rejected) result).reason()).isEqualTo(RejectionReason.INPUT_INVALID);
    }

    /** CV-06: 현재가치 0·음수는 금액을 보존하고 필요율만 계산 불가다. 0으로 대체하지 않는다. */
    @ParameterizedTest
    @CsvSource({"0,-1210", "-5,-1215"})
    void aNonPositiveCurrentValueKeepsAmountsAndHoldsOnlyTheRequiredRate(String current, String difference) {
        Result.Calculated result = calculated(daily("1000", "0.1", current, List.of()));
        assertThat(value(result, Metric.CURRENT_VALUE)).isEqualTo(current);
        assertThat(value(result, Metric.PLAN_DIFFERENCE)).isEqualTo(difference);
        assertThat(result.metric(Metric.REQUIRED_RATE).value()).isNull();
        assertThat(result.metric(Metric.REQUIRED_RATE).unavailableReason())
                .isEqualTo(UnavailableReason.CURRENT_VALUE_NOT_POSITIVE);
        assertThat(result.metric(Metric.REQUIRED_RATE).nature()).isEqualTo(Nature.OFFICIAL);
        assertThat(value(result, Metric.GOAL_PROGRESS)).startsWith("-");
        assertThat(result.inputReliability()).isEqualTo(InputReliability.CONFIRMED);
    }

    /** CV-07: 출금으로 목표가 0 이하면 목표 기반 비율 둘이 계산 불가이고 입력 신뢰도가 검토 필요다. */
    @Test
    void aNonPositiveTargetHoldsBothRatiosAndNeedsReview() {
        Result.Calculated result = calculated(daily("1000", "0.1", "1",
                List.of(flow(day(1), "large-out", "-1300"))));
        assertThat(value(result, Metric.ADJUSTED_TARGET)).isEqualTo("-242");
        assertThat(value(result, Metric.PLAN_AMOUNT)).isEqualTo("-220");
        assertThat(value(result, Metric.PLAN_DIFFERENCE)).isEqualTo("221");
        assertThat(result.metric(Metric.REQUIRED_RATE).unavailableReason())
                .isEqualTo(UnavailableReason.TARGET_NOT_POSITIVE);
        assertThat(result.metric(Metric.GOAL_PROGRESS).unavailableReason())
                .isEqualTo(UnavailableReason.TARGET_NOT_POSITIVE);
        assertThat(result.inputReliability()).isEqualTo(InputReliability.REVIEW_REQUIRED);
    }

    @Test
    void aZeroGoalProfitHoldsOnlyTheGoalProgress() {
        Result.Calculated result = calculated(daily("1000", "0", "1250", List.of()));
        assertThat(value(result, Metric.ADJUSTED_TARGET)).isEqualTo("1000");
        assertThat(result.metric(Metric.GOAL_PROGRESS).unavailableReason())
                .isEqualTo(UnavailableReason.GOAL_PROFIT_NOT_POSITIVE);
        assertThat(value(result, Metric.REQUIRED_RATE)).isEqualTo("-0.2");
        assertThat(result.inputReliability()).isEqualTo(InputReliability.CONFIRMED);
    }

    /** A negative required rate and a progress outside 0..100% are kept as they are. */
    @Test
    void negativeRatesAndProgressOutsideTheUnitRangeAreNotClipped() {
        Result.Calculated ahead = calculated(daily("1000", "0.1", "1500", List.of()));
        assertThat(value(ahead, Metric.REQUIRED_RATE)).startsWith("-");
        assertThat(new BigDecimal(value(ahead, Metric.GOAL_PROGRESS))).isGreaterThan(BigDecimal.ONE);
        Result.Calculated behind = calculated(daily("1000", "0.1", "900", List.of()));
        assertThat(new BigDecimal(value(behind, Metric.GOAL_PROGRESS))).isNegative();
    }

    /** One estimated flow makes the five dependent metrics estimated; the current value stays official. */
    @Test
    void anEstimatedFlowPropagatesToEveryDependentMetricButNotToTheCurrentValue() {
        Cashflow estimated = new Cashflow(day(1), "account-1", "btc-in", "650.0025", true);
        Result.Calculated result = calculated(daily("1000", "0.1", "0", List.of(estimated)));
        assertThat(result.estimated()).isTrue();
        assertThat(result.metric(Metric.CURRENT_VALUE).nature()).isEqualTo(Nature.OFFICIAL);
        for (Metric metric : List.of(Metric.PLAN_AMOUNT, Metric.PLAN_DIFFERENCE, Metric.ADJUSTED_TARGET,
                Metric.REQUIRED_RATE, Metric.GOAL_PROGRESS)) {
            assertThat(result.metric(metric).nature()).as(metric.name()).isEqualTo(Nature.ESTIMATED);
        }
        // Not computable does not overwrite the nature: the reason is a separate value.
        assertThat(result.metric(Metric.REQUIRED_RATE).unavailableReason())
                .isEqualTo(UnavailableReason.CURRENT_VALUE_NOT_POSITIVE);
    }

    /** CV-09: 1월 31일 월 시작은 2월 29일, 3월 31일로 복귀한다. 평년은 2월 28일이다. */
    @Test
    void monthlyBoundariesClampToTheMonthEndAndReturnToTheOriginalDay() {
        Instant leap = seoul("2028-01-31T10:00");
        SeoulCalendar calendar = SeoulCalendar.anchoredAt(leap, Period.MONTHLY, seoul("2028-06-01T00:00"));
        assertThat(calendar.boundary(1)).isEqualTo(seoul("2028-02-29T10:00"));
        assertThat(calendar.boundary(2)).isEqualTo(seoul("2028-03-31T10:00"));
        assertThat(calendar.boundary(3)).isEqualTo(seoul("2028-04-30T10:00"));
        Instant common = seoul("2027-01-31T10:00");
        SeoulCalendar commonYear = SeoulCalendar.anchoredAt(common, Period.MONTHLY, seoul("2027-04-01T00:00"));
        assertThat(commonYear.boundary(1)).isEqualTo(seoul("2027-02-28T10:00"));
        assertThat(commonYear.boundary(2)).isEqualTo(seoul("2027-03-31T10:00"));
        // A partial first month is measured against that month's real length (29 days in Feb 2028).
        assertThat(calendar.periods(leap, seoul("2028-02-14T22:00"))).isEqualTo(Fraction.of(1, 2));
    }

    @Test
    void dailyAndWeeklyBoundariesFollowTheSeoulLocalTime() {
        Instant start = seoul("2027-03-10T23:30");
        SeoulCalendar daily = SeoulCalendar.anchoredAt(start, Period.DAILY, start.plusMillis(5 * DAY));
        assertThat(daily.boundary(1)).isEqualTo(seoul("2027-03-11T23:30"));
        SeoulCalendar weekly = SeoulCalendar.anchoredAt(start, Period.WEEKLY, start.plusMillis(30 * DAY));
        assertThat(weekly.boundary(2)).isEqualTo(seoul("2027-03-24T23:30"));
        assertThat(weekly.periods(start, seoul("2027-03-14T11:30"))).isEqualTo(Fraction.of(1, 2));
    }

    /** CV-10: 임의 분할의 가법성. 정확한 분수로 성립하고, 경계 구간 분할은 canonical 결과도 같다. */
    @Test
    void periodsAreAdditiveForAnySplit() {
        Instant start = seoul("2028-01-31T10:00");
        SeoulCalendar calendar = SeoulCalendar.anchoredAt(start, Period.MONTHLY, seoul("2029-02-01T00:00"));
        Random random = new Random(7L);
        long span = calendar.boundary(12).toEpochMilli() - start.toEpochMilli();
        for (int i = 0; i < 200; i++) {
            long[] points = {random.nextLong(span), random.nextLong(span), random.nextLong(span)};
            java.util.Arrays.sort(points);
            Instant a = start.plusMillis(points[0]);
            Instant b = start.plusMillis(points[1]);
            Instant c = start.plusMillis(points[2]);
            assertThat(calendar.periods(a, b).plus(calendar.periods(b, c))).isEqualTo(calendar.periods(a, c));
        }
        for (int i = 0; i < 200; i++) {
            Instant a = calendar.boundary(1);
            Instant c = calendar.boundary(2);
            Instant b = a.plusMillis(1 + random.nextLong(c.toEpochMilli() - a.toEpochMilli() - 1));
            BigDecimal split = calendar.periods(a, b).toDecimal(Decimals.CONTEXT)
                    .add(calendar.periods(b, c).toDecimal(Decimals.CONTEXT), Decimals.CONTEXT);
            assertThat(Decimals.canonical(split)).isEqualTo(Decimals.canonical(
                    calendar.periods(a, c).toDecimal(Decimals.CONTEXT))).isEqualTo("1");
        }
    }

    /** CV-12: 결과가 {@code 1E24} 이상이거나 {@code r > 10}이면 계산 전체를 거절한다. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"result,999999999999999999999,10,1", "rate,1000,10.000000000001,1250",
        "input,1000000000000000000000000,0.1,1250", "tiny flow,1000,0.1,1250"})
    void outOfRangeValuesRejectTheWholeCalculation(String name, String p0, String rate, String current) {
        List<Cashflow> flows = name.equals("tiny flow")
                ? List.of(flow(day(1), "tiny", "0.0000000000000000000000001")) : List.of();
        Result result = PlanCalculator.calculate(daily(p0, rate, current, flows));
        assertThat(result).isInstanceOf(Result.Rejected.class);
        assertThat(((Result.Rejected) result).reason()).isEqualTo(RejectionReason.CALCULATION_RANGE_EXCEEDED);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"exponent,1E3", "plus,+1000", "comma,'1,000'", "empty,''", "space,' 1000'"})
    void nonCanonicalInputIsInvalid(String name, String principal) {
        Result result = PlanCalculator.calculate(daily(principal, "0.1", "1250", List.of()));
        assertThat(result).isInstanceOf(Result.Rejected.class);
        assertThat(((Result.Rejected) result).reason()).isEqualTo(RejectionReason.INPUT_INVALID);
    }

    @Test
    void timeOrderAndPolicyAreRequired() {
        PlanCalculator.Input late = new PlanCalculator.Input("1000", T0, day(3), Period.DAILY, "0.1", "1250",
                day(3), List.of(), PlanCalculator.CALCULATION_POLICY, PlanCalculator.CALENDAR_POLICY);
        assertThat(((Result.Rejected) PlanCalculator.calculate(late)).reason()).isEqualTo(RejectionReason.INPUT_INVALID);
        PlanCalculator.Input early = new PlanCalculator.Input("1000", T0, day(3), Period.DAILY, "0.1", "1250",
                T0, List.of(), PlanCalculator.CALCULATION_POLICY, PlanCalculator.CALENDAR_POLICY);
        assertThat(((Result.Rejected) PlanCalculator.calculate(early)).reason()).isEqualTo(RejectionReason.INPUT_INVALID);
        PlanCalculator.Input policy = new PlanCalculator.Input("1000", T0, day(3), Period.DAILY, "0.1", "1250",
                day(2), List.of(), "CALC-2.0", PlanCalculator.CALENDAR_POLICY);
        assertThat(((Result.Rejected) PlanCalculator.calculate(policy)).reason())
                .isEqualTo(RejectionReason.POLICY_UNSUPPORTED);
    }

    /** Partial periods use a non-integer exponent; results stay at 34 digits, scale 24, and repeat exactly. */
    @Test
    void partialPeriodsAreDeterministicAtTheCanonicalPrecision() {
        PlanCalculator.Input input = new PlanCalculator.Input("1234.56", T0.plusMillis(37_123), day(45.3),
                Period.MONTHLY, "0.0123456789", "1300.987654321", day(17.77),
                List.of(new Cashflow(day(3.1), "a", "x-1", "99.99", false),
                        new Cashflow(day(9.9), "a", "x-2", "-12.3456789012345678", true)),
                PlanCalculator.CALCULATION_POLICY, PlanCalculator.CALENDAR_POLICY);
        Result.Calculated first = calculated(input);
        for (int i = 0; i < 3; i++) assertThat(PlanCalculator.calculate(input)).isEqualTo(first);
        for (PlanCalculator.MetricResult metric : first.metrics()) {
            BigDecimal value = new BigDecimal(metric.value());
            assertThat(value.precision()).as(metric.metric().name()).isLessThanOrEqualTo(34);
            assertThat(value.scale()).as(metric.metric().name()).isLessThanOrEqualTo(24);
            assertThat(metric.value()).doesNotContain("E").matches("-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?");
        }
        assertThat(first.inputHash()).matches("[0-9a-f]{64}");
        assertThat(first.resultHash()).matches("[0-9a-f]{64}");
        assertThat(first.implementationVersion()).isEqualTo(PlanCalculator.IMPLEMENTATION_VERSION);
    }

    @Test
    void equalValuesWithDifferentNotationHaveTheSameInputHash() {
        Result a = PlanCalculator.calculate(daily("1000.00", "0.10", "1250.0", List.of(flow(day(1), "x", "100.50"))));
        Result b = PlanCalculator.calculate(daily("1000", "0.1", "1250", List.of(flow(day(1), "x", "100.5"))));
        assertThat(a).isEqualTo(b);
    }

    /** {@code P0 > 0} is the calculator's own precondition: it does not rely on the caller's eligibility. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"zero,0", "zero with notation,0.000", "negative,-1"})
    void aNonPositivePrincipalIsInvalid(String name, String principal) {
        Result result = PlanCalculator.calculate(daily(principal, "0.1", "1250", List.of()));
        assertThat(result).isInstanceOf(Result.Rejected.class);
        assertThat(((Result.Rejected) result).reason()).isEqualTo(RejectionReason.INPUT_INVALID);
    }

    /**
     * CALC-1.0 §3.1 input limits: amounts precision 38·scale 18, rates precision 18·scale 12. A 38-digit
     * amount is accepted as it is — the 34-digit context applies at the first operation, not to the input.
     */
    @Test
    void inputsWithinTheCalcInputLimitsAreAcceptedAndBeyondThemInvalid() {
        String thirtyEight = "12345678901234567890.123456789012345678";
        Result.Calculated wide = calculated(daily(thirtyEight, "0.1", "1250", List.of()));
        // E is kept as the official value; P0's 38 digits reach the 34-digit context at the first product.
        assertThat(value(wide, Metric.CURRENT_VALUE)).isEqualTo("1250");
        assertThat(value(wide, Metric.PLAN_AMOUNT)).isEqualTo(Decimals.canonical(
                new BigDecimal(thirtyEight).multiply(new BigDecimal("1.21"), Decimals.CONTEXT)));
        assertThat(calculated(daily("1000", "0.123456789012", "1250.123456789012345678", List.of()))).isNotNull();
        for (PlanCalculator.Input beyond : List.of(
                daily("123456789012345678901.123456789012345678", "0.1", "1250", List.of()),
                daily("1000", "0.1", "1250.0000000000000000001", List.of()),
                daily("1000", "0.1234567890123", "1250", List.of()))) {
            Result result = PlanCalculator.calculate(beyond);
            assertThat(result).isInstanceOf(Result.Rejected.class);
            assertThat(((Result.Rejected) result).reason()).isEqualTo(RejectionReason.INPUT_INVALID);
        }
    }

    /**
     * A power that leaves even BigDecimal's exponent range makes big-math throw {@link ArithmeticException}.
     * That is a range failure, not an invalid input: {@code (1 ÷ 1E-18)^(86_400_000)} with one millisecond
     * of a daily period left.
     */
    @Test
    void aPowerBeyondTheRepresentableRangeIsARangeRejection() {
        PlanCalculator.Input input = new PlanCalculator.Input("1", T0, day(3), Period.DAILY, "0",
                "0.000000000000000001", day(3).minusMillis(1), List.of(), PlanCalculator.CALCULATION_POLICY,
                PlanCalculator.CALENDAR_POLICY);
        Result result = PlanCalculator.calculate(input);
        assertThat(result).isInstanceOf(Result.Rejected.class);
        assertThat(((Result.Rejected) result).reason()).isEqualTo(RejectionReason.CALCULATION_RANGE_EXCEEDED);
    }

    @Test
    void aNegativeFractionIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Fraction.of(-1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Fraction.of(0, 5)).isEqualTo(Fraction.ZERO);
        assertThat(Fraction.of(2, 4)).isEqualTo(Fraction.of(1, 2));
    }

    /** Separators inside identifiers cannot make two different inputs serialize to the same hash payload. */
    @Test
    void identifiersContainingSeparatorsDoNotCollide() {
        Result.Calculated a = calculated(daily("1000", "0.1", "1250",
                List.of(new Cashflow(day(1), "a|b", "c", "100", false))));
        Result.Calculated b = calculated(daily("1000", "0.1", "1250",
                List.of(new Cashflow(day(1), "a", "b|c", "100", false))));
        assertThat(a.inputHash()).isNotEqualTo(b.inputHash());
        assertThat(a.resultHash()).isNotEqualTo(b.resultHash());
        Result.Calculated c = calculated(daily("1000", "0.1", "1250",
                List.of(new Cashflow(day(1), "a;1:x", "y", "100", false))));
        Result.Calculated d = calculated(daily("1000", "0.1", "1250",
                List.of(new Cashflow(day(1), "a", "1:x;y", "100", false))));
        assertThat(c.inputHash()).isNotEqualTo(d.inputHash());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"1250.00,1250", "001.50,1.5", "-0.000,0", "0,0", "-12.3400,-12.34", "1000,1000"})
    void plainDecimalNotationIsLossless(String raw, String expected) {
        assertThat(PlanCalculator.plainDecimal(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"1E3", "+1", "'1,000'", "''", "1.", ".5"})
    void plainDecimalRefusesOtherShapes(String raw) {
        assertThat(PlanCalculator.plainDecimal(raw)).isNull();
    }

    @Test
    void theRuntimeConformanceCheckPasses() {
        assertThat(Conformance.evaluate()).isTrue();
        assertThat(PlanCalculator.conformant()).isTrue();
    }

    private static Instant seoul(String local) {
        return LocalDateTime.parse(local).atZone(SeoulCalendar.ZONE).toInstant();
    }

    // ------------------------------------------------------------------------------------------ Story 2.14 (CV-11)

    /** Comparable: observed at T. Not comparable: observed 24 hours and 1 ms after T. */
    private static PlanCalculator.CompletionInput completion(String observed, boolean comparable, List<Cashflow> flows) {
        return completion(observed, comparable ? day(3) : day(4).plusMillis(1), false, flows);
    }

    private static PlanCalculator.CompletionInput completion(String observed, Instant measuredAt,
            boolean postEndCashflow, List<Cashflow> flows) {
        return new PlanCalculator.CompletionInput("1000", T0, day(3), Period.DAILY, "0.1", observed, measuredAt,
                postEndCashflow, flows, PlanCalculator.CALCULATION_POLICY, PlanCalculator.CALENDAR_POLICY);
    }

    private static Result.Calculated completed(PlanCalculator.CompletionInput input) {
        Result result = PlanCalculator.complete(input);
        assertThat(result).isInstanceOf(Result.Calculated.class);
        return (Result.Calculated) result;
    }

    /**
     * CV-11: the completion is the same formulas at {@code u = T}. The plan amount is the target, the difference is the
     * observation minus the target, the goal progress is PRD's, and the required rate has no remaining period.
     */
    @Test
    void theCompletionAppliesTheSameFormulasAtTheEnd() {
        var result = completed(completion("1250", true, List.of()));
        assertThat(value(result, Metric.CURRENT_VALUE)).isEqualTo("1250");
        assertThat(value(result, Metric.PLAN_AMOUNT)).isEqualTo("1331");
        assertThat(value(result, Metric.ADJUSTED_TARGET)).isEqualTo("1331");
        assertThat(value(result, Metric.PLAN_DIFFERENCE)).isEqualTo("-81");
        // (1250 - 1000) / (1331 - 1000)
        assertThat(value(result, Metric.GOAL_PROGRESS))
                .isEqualTo(Decimals.canonical(new BigDecimal(250).divide(new BigDecimal(331), Decimals.CONTEXT)));
        assertThat(result.metric(Metric.REQUIRED_RATE).value()).isNull();
        assertThat(result.metric(Metric.REQUIRED_RATE).unavailableReason())
                .isEqualTo(UnavailableReason.REMAINING_PERIOD_NOT_POSITIVE);
        assertThat(result.periodsElapsed()).isEqualTo("3").isEqualTo(result.periodsTotal());
        assertThat(result.periodsRemaining()).isEqualTo("0");
        assertThat(result.inputReliability()).isEqualTo(InputReliability.CONFIRMED);
        // A progress calculation of the same terms just before T is a different input, and hashes differently.
        assertThat(result.inputHash()).isNotEqualTo(PlanCalculator.calculate(daily("1000", "0.1", "1250", List.of()))
                .inputHash());
        // Even a current value that is not positive is not the reason here: there is no period left.
        assertThat(completed(completion("0", true, List.of())).metric(Metric.REQUIRED_RATE).unavailableReason())
                .isEqualTo(UnavailableReason.REMAINING_PERIOD_NOT_POSITIVE);
    }

    /** CV-11: a completion that is not comparable keeps the observation and the target and has no difference or progress. */
    @Test
    void aNotComparableCompletionHasNoDifferenceOrProgressAndNeverAZero() {
        var result = completed(completion("1250", false, List.of()));
        assertThat(value(result, Metric.CURRENT_VALUE)).isEqualTo("1250");
        assertThat(value(result, Metric.ADJUSTED_TARGET)).isEqualTo("1331");
        for (Metric metric : List.of(Metric.PLAN_DIFFERENCE, Metric.GOAL_PROGRESS)) {
            assertThat(result.metric(metric).value()).as(metric.name()).isNull();
            assertThat(result.metric(metric).unavailableReason()).isEqualTo(UnavailableReason.COMPLETION_NOT_COMPARABLE);
            assertThat(result.metric(metric).nature()).isEqualTo(Nature.OFFICIAL);
        }
        assertThat(result.inputHash()).isNotEqualTo(completed(completion("1250", true, List.of())).inputHash());
    }

    /**
     * AC-5: {@code ti = t0} is in P0 and not applied, {@code ti = T} is applied, {@code ti > T} is not; flows of the same
     * millisecond are each applied once in the stable order, whatever the input order.
     */
    @Test
    void theCompletionAppliesExactlyTheFlowsOfTheClosedEnd() {
        Instant end = day(3);
        List<Cashflow> flows = List.of(flow(T0, "at-start", "100"), flow(end, "at-end-b", "30"),
                flow(end, "at-end-a", "70"), flow(end.plusMillis(1), "after-end", "500"));
        var forward = completed(completion("1431", true, flows));
        var reversed = completed(completion("1431", true, flows.reversed()));
        assertThat(forward).isEqualTo(reversed);
        assertThat(forward.appliedCashflows()).extracting(PlanCalculator.AppliedCashflow::sourceId)
                .containsExactly("at-end-a", "at-end-b");
        assertThat(forward.appliedCashflows()).extracting(PlanCalculator.AppliedCashflow::periodsToEnd)
                .containsOnly("0");
        assertThat(forward.adjustedPrincipal()).isEqualTo("1100");
        assertThat(value(forward, Metric.ADJUSTED_TARGET)).isEqualTo("1431");
        assertThat(value(forward, Metric.PLAN_DIFFERENCE)).isEqualTo("0");
        assertThat(value(forward, Metric.GOAL_PROGRESS)).isEqualTo("1");
    }

    /** CV-11: the 24-hour limit includes exactly 24 hours and excludes one millisecond more, and nothing before T. */
    @Test
    void theObservationLimitIsTwentyFourHoursInclusive() {
        Instant end = day(3);
        assertThat(PlanCalculator.observationWithinLimit(end, end)).isTrue();
        assertThat(PlanCalculator.observationWithinLimit(end, end.plusMillis(DAY))).isTrue();
        assertThat(PlanCalculator.observationWithinLimit(end, end.plusMillis(DAY + 1))).isFalse();
        assertThat(PlanCalculator.observationWithinLimit(end, end.minusMillis(1))).isFalse();
        // The calculator judges the comparability itself, from the observation time and the post-end flow fact.
        assertThat(completed(completion("1250", end.plusMillis(DAY), false, List.of())).metric(Metric.PLAN_DIFFERENCE)
                .value()).isEqualTo("-81");
        assertThat(completed(completion("1250", end.plusMillis(DAY + 1), false, List.of()))
                .metric(Metric.PLAN_DIFFERENCE).unavailableReason()).isEqualTo(UnavailableReason.COMPLETION_NOT_COMPARABLE);
        assertThat(completed(completion("1250", end, true, List.of())).metric(Metric.GOAL_PROGRESS)
                .unavailableReason()).isEqualTo(UnavailableReason.COMPLETION_NOT_COMPARABLE);
        // The observation time is part of the input: two observations of the same value are two inputs.
        assertThat(PlanCalculator.complete(completion("1250", end, false, List.of())).inputHash())
                .isNotEqualTo(PlanCalculator.complete(completion("1250", end.plusMillis(1), false, List.of()))
                        .inputHash());
        // An observation before T is no post-end observation.
        assertThat(PlanCalculator.complete(completion("1250", end.minusMillis(1), false, List.of())))
                .isInstanceOf(Result.Rejected.class);
        // The calculator judges the comparability itself, from the observation time and the post-end flow fact.
        assertThat(completed(completion("1250", end.plusMillis(DAY), false, List.of())).metric(Metric.PLAN_DIFFERENCE)
                .value()).isEqualTo("-81");
        assertThat(completed(completion("1250", end.plusMillis(DAY + 1), false, List.of()))
                .metric(Metric.PLAN_DIFFERENCE).unavailableReason()).isEqualTo(UnavailableReason.COMPLETION_NOT_COMPARABLE);
        assertThat(completed(completion("1250", end, true, List.of())).metric(Metric.GOAL_PROGRESS)
                .unavailableReason()).isEqualTo(UnavailableReason.COMPLETION_NOT_COMPARABLE);
        // The observation time is part of the input: two observations of the same value are two inputs.
        assertThat(PlanCalculator.complete(completion("1250", end, false, List.of())).inputHash())
                .isNotEqualTo(PlanCalculator.complete(completion("1250", end.plusMillis(1), false, List.of()))
                        .inputHash());
        // An observation before T is no post-end observation.
        assertThat(PlanCalculator.complete(completion("1250", end.minusMillis(1), false, List.of())))
                .isInstanceOf(Result.Rejected.class);
        assertThat(PlanCalculator.conformant()).isTrue();
        assertThat(Conformance.evaluate()).isTrue();
    }
}
