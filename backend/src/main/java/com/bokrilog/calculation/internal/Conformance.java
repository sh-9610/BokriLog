package com.bokrilog.calculation.internal;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.calculation.PlanCalculator.Cashflow;
import com.bokrilog.calculation.PlanCalculator.InputReliability;
import com.bokrilog.calculation.PlanCalculator.Metric;
import com.bokrilog.calculation.PlanCalculator.Period;
import com.bokrilog.calculation.PlanCalculator.RejectionReason;
import com.bokrilog.calculation.PlanCalculator.Result;
import com.bokrilog.calculation.PlanCalculator.UnavailableReason;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 승인 고정 예제(ERD-1.0.0 §3.4)의 실행 시점 적합성 검사.
 *
 * <p>CV-01~CV-07·CV-09~CV-12를 이 구현으로 다시 계산해 기대 canonical 값과 글자까지 비교한다.
 * 하나라도 어긋나면 {@link #passes()}가 거짓이고 호출자는 공식 결과를 발행하지 않는다. 시험
 * ({@code PlanCalculatorTest})이 같은 예제를 따로 적은 기대값으로 한 번 더 고정한다 — 이 표가 틀리면
 * 그 시험이 먼저 깨진다.
 *
 * <p>기대값은 canonical 형식이다(34 유효숫자·scale 24 이하, HALF_EVEN, 끝의 0 없음). ERD 표가 적은
 * 34자리 값은 내부값이며 그 canonical 형식이 아래 문자열이다.
 */
public final class Conformance {

    /** 예제의 시작. 모든 시각이 주기 경계에 정확히 맞는다. */
    static final Instant T0 = LocalDateTime.parse("2027-01-04T09:00").atZone(SeoulCalendar.ZONE).toInstant();

    private static final long DAY_MILLIS = 86_400_000L;

    private Conformance() {}

    /** 한 번 계산해 기억한다. 입력이 상수라 결과도 상수다. */
    public static boolean passes() {
        return Holder.PASSES;
    }

    private static final class Holder {
        static final boolean PASSES = evaluate();
    }

    static boolean evaluate() {
        try {
            return cv01() && cv02() && cv03() && cv04() && cv05() && cv06() && cv07() && cv09() && cv10()
                    && cv11() && cv12();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static Instant day(int days) {
        return T0.plusMillis(days * DAY_MILLIS);
    }

    static PlanCalculator.Input daily(String principal, String rate, String current, List<Cashflow> flows) {
        return new PlanCalculator.Input(principal, T0, day(3), Period.DAILY, rate, current, day(2), flows,
                PlanCalculator.CALCULATION_POLICY, PlanCalculator.CALENDAR_POLICY);
    }

    static Cashflow flow(Instant at, String sourceId, String usd) {
        return new Cashflow(at, "conformance", sourceId, usd, false);
    }

    private static boolean cv01() {
        return matches(daily("1000", "0.1", "1250", List.of()), "1210", "40", "1331", "0.0648",
                "0.755287009063444108761329");
    }

    private static boolean cv02() {
        return matches(daily("1000", "0.1", "1340", List.of(flow(day(1), "in", "100"))), "1320", "20", "1452",
                "0.083582089552238805970149", "0.681818181818181818181818");
    }

    private static boolean cv03() {
        return matches(daily("1000", "0.1", "950", List.of(flow(day(1), "out", "-200"))), "990", "-40", "1089",
                "0.146315789473684210526316", "0.519031141868512110726644");
    }

    /** 같은 시각 +100, −30, −70은 모두 보존되고 대수합 0이며, 입력 순서와 무관하게 CV-01과 같다. */
    private static boolean cv04() {
        List<Cashflow> flows = List.of(flow(day(1), "c", "-70"), flow(day(1), "a", "100"), flow(day(1), "b", "-30"));
        Result forward = PlanCalculator.calculate(daily("1000", "0.1", "1250", flows));
        Result reversed = PlanCalculator.calculate(daily("1000", "0.1", "1250", flows.reversed()));
        return forward instanceof Result.Calculated calculated && calculated.appliedCashflows().size() == 3
                && forward.equals(reversed)
                && matches(daily("1000", "0.1", "1250", flows), "1210", "40", "1331", "0.0648",
                        "0.755287009063444108761329");
    }

    /** {@code ti = t0}는 P0에 이미 포함된 것으로 보고 반영하지 않는다. */
    private static boolean cv05() {
        PlanCalculator.Input input = daily("1000", "0.1", "1250", List.of(flow(T0, "at-start", "100")));
        Result result = PlanCalculator.calculate(input);
        return result instanceof Result.Calculated calculated && calculated.appliedCashflows().isEmpty()
                && "1000".equals(calculated.adjustedPrincipal())
                && matches(input, "1210", "40", "1331", "0.0648", "0.755287009063444108761329");
    }

    /** 현재가치 0·음수: 금액은 보존하고 필요율만 계산 불가다. */
    private static boolean cv06() {
        Result zero = PlanCalculator.calculate(daily("1000", "0.1", "0", List.of()));
        Result negative = PlanCalculator.calculate(daily("1000", "0.1", "-5", List.of()));
        return zero instanceof Result.Calculated z && negative instanceof Result.Calculated n
                && "0".equals(value(z, Metric.CURRENT_VALUE)) && "-1210".equals(value(z, Metric.PLAN_DIFFERENCE))
                && reason(z, Metric.REQUIRED_RATE) == UnavailableReason.CURRENT_VALUE_NOT_POSITIVE
                && value(z, Metric.GOAL_PROGRESS) != null
                && "-5".equals(value(n, Metric.CURRENT_VALUE)) && "-1215".equals(value(n, Metric.PLAN_DIFFERENCE))
                && reason(n, Metric.REQUIRED_RATE) == UnavailableReason.CURRENT_VALUE_NOT_POSITIVE;
    }

    /** 출금으로 조정 최종 목표가 0 이하면 목표 기반 비율이 계산 불가이고 계획 입력은 검토 필요다. */
    private static boolean cv07() {
        Result withdrawn = PlanCalculator.calculate(daily("1000", "0.1", "1",
                List.of(flow(day(1), "large-out", "-1300"))));
        Result noGrowth = PlanCalculator.calculate(daily("1000", "0", "1250", List.of()));
        return withdrawn instanceof Result.Calculated w && noGrowth instanceof Result.Calculated g
                && "-242".equals(value(w, Metric.ADJUSTED_TARGET)) && "-220".equals(value(w, Metric.PLAN_AMOUNT))
                && reason(w, Metric.REQUIRED_RATE) == UnavailableReason.TARGET_NOT_POSITIVE
                && reason(w, Metric.GOAL_PROGRESS) == UnavailableReason.TARGET_NOT_POSITIVE
                && w.inputReliability() == InputReliability.REVIEW_REQUIRED
                && reason(g, Metric.GOAL_PROGRESS) == UnavailableReason.GOAL_PROFIT_NOT_POSITIVE
                && g.inputReliability() == InputReliability.CONFIRMED;
    }

    /** 1월 31일 시작의 월 경계는 2월 말일, 그다음 3월 31일로 복귀한다(윤년 2028). */
    private static boolean cv09() {
        Instant start = LocalDateTime.parse("2028-01-31T10:00").atZone(SeoulCalendar.ZONE).toInstant();
        SeoulCalendar calendar = SeoulCalendar.anchoredAt(start, Period.MONTHLY, start.plusMillis(90 * DAY_MILLIS));
        return calendar.boundary(1).equals(LocalDateTime.parse("2028-02-29T10:00").atZone(SeoulCalendar.ZONE).toInstant())
                && calendar.boundary(2).equals(LocalDateTime.parse("2028-03-31T10:00").atZone(SeoulCalendar.ZONE)
                        .toInstant());
    }

    /** 한 경계 구간을 임의 시각에서 나눠도 합이 같다 — 정확한 분수로도, canonical 결과로도. */
    private static boolean cv10() {
        Instant start = LocalDateTime.parse("2028-01-31T10:00").atZone(SeoulCalendar.ZONE).toInstant();
        SeoulCalendar calendar = SeoulCalendar.anchoredAt(start, Period.MONTHLY, start.plusMillis(90 * DAY_MILLIS));
        Instant a = calendar.boundary(0);
        Instant c = calendar.boundary(1);
        Instant b = a.plusMillis(1_234_567_891L);
        Fraction whole = calendar.periods(a, c);
        Fraction left = calendar.periods(a, b);
        Fraction right = calendar.periods(b, c);
        String split = Decimals.canonical(left.toDecimal(Decimals.CONTEXT)
                .add(right.toDecimal(Decimals.CONTEXT), Decimals.CONTEXT));
        return whole.equals(left.plus(right)) && whole.equals(Fraction.of(1, 1))
                && Decimals.canonical(whole.toDecimal(Decimals.CONTEXT)).equals(split) && "1".equals(split);
    }

    /** A completion at {@code T = day(3)}: comparable observed at {@code T}, not comparable 24 hours and 1 ms later. */
    static PlanCalculator.CompletionInput completion(String observed, boolean comparable, List<Cashflow> flows) {
        return new PlanCalculator.CompletionInput("1000", T0, day(3), Period.DAILY, "0.1", observed,
                comparable ? day(3) : day(4).plusMillis(1), false, flows, PlanCalculator.CALCULATION_POLICY,
                PlanCalculator.CALENDAR_POLICY);
    }

    /**
     * 첫 종료 후 관측의 완료 비교(Story 2.14). 지연은 24시간 이하만 비교 가능하다(정확히 24시간 포함, 1ms 넘으면
     * 불가). 완료 계산은 {@code u = T}의 같은 식이다: 계획금액 = 조정 최종 목표 = 1331, 관측 1331이면 차이 0·달성도 1,
     * 남은 필요 등가수익률은 남은 기간이 없어 계산 불가. 비교 불가면 관측값은 보존하고 차이·달성도만 값이 없다.
     * 흐름은 {@code t0}의 것은 제외하고 {@code T}의 것은 포함하며 {@code T} 뒤의 것은 제외한다.
     */
    private static boolean cv11() {
        Instant end = day(3);
        if (!PlanCalculator.observationWithinLimit(end, end)
                || !PlanCalculator.observationWithinLimit(end, end.plusMillis(DAY_MILLIS))
                || PlanCalculator.observationWithinLimit(end, end.plusMillis(DAY_MILLIS + 1))
                || PlanCalculator.observationWithinLimit(end, end.minusMillis(1)))
            return false;
        Result confirmed = PlanCalculator.complete(completion("1331", true, List.of()));
        Result held = PlanCalculator.complete(completion("1250", false, List.of()));
        Result bounded = PlanCalculator.complete(completion("1431", true, List.of(flow(T0, "at-start", "100"),
                flow(end, "at-end", "100"), flow(end.plusMillis(1), "after-end", "100"))));
        return confirmed instanceof Result.Calculated c && held instanceof Result.Calculated h
                && bounded instanceof Result.Calculated b
                && "1331".equals(value(c, Metric.PLAN_AMOUNT)) && "1331".equals(value(c, Metric.ADJUSTED_TARGET))
                && "0".equals(value(c, Metric.PLAN_DIFFERENCE)) && "1".equals(value(c, Metric.GOAL_PROGRESS))
                && reason(c, Metric.REQUIRED_RATE) == UnavailableReason.REMAINING_PERIOD_NOT_POSITIVE
                && "0".equals(c.periodsRemaining()) && "3".equals(c.periodsElapsed())
                && "1250".equals(value(h, Metric.CURRENT_VALUE)) && "1331".equals(value(h, Metric.ADJUSTED_TARGET))
                && reason(h, Metric.PLAN_DIFFERENCE) == UnavailableReason.COMPLETION_NOT_COMPARABLE
                && reason(h, Metric.GOAL_PROGRESS) == UnavailableReason.COMPLETION_NOT_COMPARABLE
                && b.appliedCashflows().size() == 1 && "at-end".equals(b.appliedCashflows().getFirst().sourceId())
                && "1431".equals(value(b, Metric.ADJUSTED_TARGET)) && "1100".equals(b.adjustedPrincipal())
                && "0".equals(value(b, Metric.PLAN_DIFFERENCE));
    }

    /** 결과 절대값 {@code >= 1E24}와 {@code r > 10}은 계산 전체를 거절한다. */
    private static boolean cv12() {
        Result large = PlanCalculator.calculate(daily("999999999999999999999", "10", "1", List.of()));
        Result rate = PlanCalculator.calculate(daily("1000", "10.000000000001", "1250", List.of()));
        return large instanceof Result.Rejected l && l.reason() == RejectionReason.CALCULATION_RANGE_EXCEEDED
                && rate instanceof Result.Rejected r && r.reason() == RejectionReason.CALCULATION_RANGE_EXCEEDED;
    }

    private static boolean matches(PlanCalculator.Input input, String plan, String difference, String target,
            String requiredRate, String goalProgress) {
        Result result = PlanCalculator.calculate(input);
        if (!(result instanceof Result.Calculated calculated)) return false;
        Map<Metric, @Nullable String> expected = Map.of(Metric.CURRENT_VALUE, input.currentValue(),
                Metric.PLAN_AMOUNT, plan, Metric.PLAN_DIFFERENCE, difference, Metric.ADJUSTED_TARGET, target,
                Metric.REQUIRED_RATE, requiredRate, Metric.GOAL_PROGRESS, goalProgress);
        for (Map.Entry<Metric, @Nullable String> entry : expected.entrySet()) {
            if (!Objects.equals(entry.getValue(), value(calculated, entry.getKey()))) return false;
        }
        return calculated.inputReliability() == InputReliability.CONFIRMED && !calculated.estimated();
    }

    private static @Nullable String value(Result.Calculated result, Metric metric) {
        return result.metric(metric).value();
    }

    private static @Nullable UnavailableReason reason(Result.Calculated result, Metric metric) {
        return result.metric(metric).unavailableReason();
    }
}
