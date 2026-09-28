package com.bokrilog.calculation.internal;

import static com.bokrilog.calculation.internal.Decimals.CONTEXT;
import static com.bokrilog.calculation.internal.Decimals.checked;

import ch.obermuhlner.math.big.BigDecimalMath;
import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.calculation.PlanCalculator.AppliedCashflow;
import com.bokrilog.calculation.PlanCalculator.Cashflow;
import com.bokrilog.calculation.PlanCalculator.InputReliability;
import com.bokrilog.calculation.PlanCalculator.Metric;
import com.bokrilog.calculation.PlanCalculator.MetricResult;
import com.bokrilog.calculation.PlanCalculator.Nature;
import com.bokrilog.calculation.PlanCalculator.RejectionReason;
import com.bokrilog.calculation.PlanCalculator.Result;
import com.bokrilog.calculation.PlanCalculator.UnavailableReason;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * PRD §4의 여섯 공식({@code CALC-1.0}).
 *
 * <p>연산 순서가 계약이다(CV와 글자까지 맞춘다):
 * <ul>
 *   <li>{@code Plan = P0·(1+r)^p(t0,tc) + Σ Fi·(1+r)^p(ti,tc)} — 곱과 합마다 34자리.
 *   <li>{@code Target}은 같은 식에서 지수만 {@code p(·,T)}다.
 *   <li>{@code Difference = E − Plan}.
 *   <li>{@code RequiredRate = (Target ÷ E)^(1 ÷ p(tc,T)) − 1}: 나눗셈 → 지수 역수 → 거듭제곱 → 뺄셈.
 *   <li>{@code GoalProgress = (E − AP) ÷ (Target − AP)}, {@code AP = P0 + ΣFi}.
 * </ul>
 *
 * <p>사건은 {@code (occurredAt, namespace, sourceId)} 순으로 정확히 한 번 더한다. 반영 구간은
 * {@code t0 < ti <= min(tc,T)}이며 미래 흐름을 예측하지 않는다. 표시 반올림은 없다 — 중간값은
 * 34자리 그대로 다음 연산에 들어가고, canonical 문자열은 결과를 기록할 때 한 번만 만든다.
 */
public final class PlanFormula {

    private static final Comparator<Cashflow> ORDER = Comparator.comparing(Cashflow::occurredAt)
            .thenComparing(Cashflow::namespace).thenComparing(Cashflow::sourceId);

    private static final BigDecimal MAX_RATE = BigDecimal.TEN;

    private PlanFormula() {}

    public static Result evaluate(PlanCalculator.Input input) {
        List<Cashflow> ordered = input.cashflows().stream().sorted(ORDER).toList();
        String inputHash = inputHash(input, ordered);
        if (!PlanCalculator.CALCULATION_POLICY.equals(input.calculationPolicy())
                || !PlanCalculator.CALENDAR_POLICY.equals(input.calendarPolicy()))
            return new Result.Rejected(RejectionReason.POLICY_UNSUPPORTED, inputHash);
        try {
            return compute(input, ordered, inputHash, new EnumMap<>(Metric.class));
        } catch (Decimals.RangeExceeded range) {
            return new Result.Rejected(RejectionReason.CALCULATION_RANGE_EXCEEDED, inputHash);
        } catch (ArithmeticException overflow) {
            // BigDecimal overflow or underflow of the scale: a value outside any representable range.
            return new Result.Rejected(RejectionReason.CALCULATION_RANGE_EXCEEDED, inputHash);
        } catch (Invalid | IllegalArgumentException invalid) {
            return new Result.Rejected(RejectionReason.INPUT_INVALID, inputHash);
        }
    }

    private static Result compute(PlanCalculator.Input input, List<Cashflow> ordered, String inputHash,
            Map<Metric, BigDecimal> sink) {
        BigDecimal principal = required(input.principal(), Decimals.Limit.AMOUNT);
        // P0 > 0 is the calculator's own precondition, not only the caller's eligibility rule.
        if (principal.signum() <= 0) throw new Invalid();
        BigDecimal rate = shaped(input.targetRate());
        if (rate.signum() < 0 || rate.compareTo(MAX_RATE) > 0) throw new Decimals.RangeExceeded();
        limited(checked(rate), Decimals.Limit.RATE);
        BigDecimal current = required(input.currentValue(), Decimals.Limit.AMOUNT);
        Instant t0 = input.startAt();
        Instant tc = input.basisAt();
        Instant end = input.endAt();
        if (!t0.isBefore(tc) || !tc.isBefore(end)) throw new Invalid();

        Set<String> identities = new HashSet<>();
        for (Cashflow flow : ordered) {
            if (!identities.add(flow.namespace() + '\u0000' + flow.sourceId())) throw new Invalid();
        }
        Instant horizon = tc.isBefore(end) ? end : tc;
        SeoulCalendar calendar = SeoulCalendar.anchoredAt(t0, input.period(), horizon);
        Instant upper = tc.isBefore(end) ? tc : end;

        BigDecimal base = checked(BigDecimal.ONE.add(rate, CONTEXT));
        BigDecimal elapsed = periods(calendar, t0, tc);
        BigDecimal total = periods(calendar, t0, end);
        BigDecimal remaining = periods(calendar, tc, end);

        BigDecimal plan = checked(principal.multiply(growth(base, elapsed), CONTEXT));
        BigDecimal target = checked(principal.multiply(growth(base, total), CONTEXT));
        BigDecimal adjustedPrincipal = principal;
        boolean estimated = false;
        List<AppliedCashflow> applied = new ArrayList<>();
        for (Cashflow flow : ordered) {
            // ti = t0는 P0에 이미 포함된 것으로 보고, min(tc,T) 뒤의 사건은 반영하지 않는다.
            if (!flow.occurredAt().isAfter(t0) || flow.occurredAt().isAfter(upper)) continue;
            BigDecimal amount = required(flow.usdValue(), Decimals.Limit.RESULT);
            BigDecimal toBasis = periods(calendar, flow.occurredAt(), tc);
            BigDecimal toEnd = periods(calendar, flow.occurredAt(), end);
            plan = checked(plan.add(checked(amount.multiply(growth(base, toBasis), CONTEXT)), CONTEXT));
            target = checked(target.add(checked(amount.multiply(growth(base, toEnd), CONTEXT)), CONTEXT));
            adjustedPrincipal = checked(adjustedPrincipal.add(amount, CONTEXT));
            estimated |= flow.estimated();
            applied.add(new AppliedCashflow(applied.size() + 1, flow.occurredAt(), flow.namespace(),
                    flow.sourceId(), Decimals.canonical(amount), flow.estimated(), Decimals.canonical(toBasis),
                    Decimals.canonical(toEnd)));
        }

        BigDecimal difference = checked(current.subtract(plan, CONTEXT));
        BigDecimal goalProfit = checked(target.subtract(adjustedPrincipal, CONTEXT));
        BigDecimal achievedProfit = checked(current.subtract(adjustedPrincipal, CONTEXT));

        Nature derived = estimated ? Nature.ESTIMATED : Nature.OFFICIAL;
        List<MetricResult> metrics = new ArrayList<>();
        metrics.add(value(sink, Metric.CURRENT_VALUE, current, Nature.OFFICIAL));
        metrics.add(value(sink, Metric.PLAN_AMOUNT, plan, derived));
        metrics.add(value(sink, Metric.PLAN_DIFFERENCE, difference, derived));
        metrics.add(value(sink, Metric.ADJUSTED_TARGET, target, derived));

        UnavailableReason rateReason = current.signum() <= 0 ? UnavailableReason.CURRENT_VALUE_NOT_POSITIVE
                : target.signum() <= 0 ? UnavailableReason.TARGET_NOT_POSITIVE
                : remaining.signum() <= 0 ? UnavailableReason.REMAINING_PERIOD_NOT_POSITIVE
                : null;
        if (rateReason != null) {
            metrics.add(new MetricResult(Metric.REQUIRED_RATE, null, derived, rateReason));
        } else {
            BigDecimal ratio = checked(target.divide(current, CONTEXT));
            BigDecimal exponent = checked(BigDecimal.ONE.divide(remaining, CONTEXT));
            BigDecimal powered = power(ratio, exponent);
            metrics.add(value(sink, Metric.REQUIRED_RATE, checked(powered.subtract(BigDecimal.ONE, CONTEXT)),
                    derived));
        }

        UnavailableReason progressReason = target.signum() <= 0 ? UnavailableReason.TARGET_NOT_POSITIVE
                : goalProfit.signum() <= 0 ? UnavailableReason.GOAL_PROFIT_NOT_POSITIVE
                : null;
        if (progressReason != null) {
            metrics.add(new MetricResult(Metric.GOAL_PROGRESS, null, derived, progressReason));
        } else {
            metrics.add(value(sink, Metric.GOAL_PROGRESS, checked(achievedProfit.divide(goalProfit, CONTEXT)),
                    derived));
        }

        InputReliability reliability = target.signum() <= 0 ? InputReliability.REVIEW_REQUIRED
                : InputReliability.CONFIRMED;
        String canonicalPrincipal = Decimals.canonical(adjustedPrincipal);
        String canonicalElapsed = Decimals.canonical(elapsed);
        String canonicalTotal = Decimals.canonical(total);
        String canonicalRemaining = Decimals.canonical(remaining);
        String resultHash = resultHash(inputHash, metrics, canonicalPrincipal, canonicalElapsed, canonicalTotal,
                canonicalRemaining, applied, estimated, reliability);
        return new Result.Calculated(metrics, canonicalPrincipal, canonicalElapsed, canonicalTotal,
                canonicalRemaining, applied, estimated, reliability, inputHash, resultHash,
                PlanCalculator.CALCULATION_POLICY, PlanCalculator.CALENDAR_POLICY, PlanCalculator.DISPLAY_POLICY,
                PlanCalculator.PRECISION_POLICY, PlanCalculator.TIME_ZONE, PlanCalculator.IMPLEMENTATION_VERSION);
    }

    /** {@code (1+r)^p}. 지수가 정수면 big-math가 반복 곱셈을, 아니면 {@code exp(p·ln(1+r))}을 쓴다. */
    private static BigDecimal growth(BigDecimal base, BigDecimal exponent) {
        return power(base, exponent);
    }

    /**
     * 버전 고정 big-math {@code pow}. 결과가 BigDecimal의 지수 범위조차 넘으면 big-math는
     * {@link ArithmeticException}을 던진다 — 그것은 입력 형식 오류가 아니라 표현 범위 초과다
     * ({@code CALCULATION_RANGE_EXCEEDED}).
     */
    private static BigDecimal power(BigDecimal base, BigDecimal exponent) {
        try {
            return checked(BigDecimalMath.pow(base, exponent, CONTEXT));
        } catch (ArithmeticException overflow) {
            throw new Decimals.RangeExceeded();
        }
    }

    private static BigDecimal periods(SeoulCalendar calendar, Instant from, Instant to) {
        return checked(calendar.periods(from, to).toDecimal(CONTEXT));
    }

    private static MetricResult value(Map<Metric, BigDecimal> sink, Metric metric, BigDecimal value, Nature nature) {
        sink.put(metric, value);
        return new MetricResult(metric, Decimals.canonical(value), nature, null);
    }

    /** 모양 → 표현 범위 → 입력 한도 순서로 판정한 입력 값. 반올림하지 않는다. */
    private static BigDecimal required(String raw, Decimals.Limit limit) {
        return limited(checked(shaped(raw)), limit);
    }

    private static BigDecimal shaped(String raw) {
        BigDecimal value = Decimals.parse(raw);
        if (value == null) throw new Invalid();
        return value;
    }

    private static BigDecimal limited(BigDecimal value, Decimals.Limit limit) {
        if (!limit.admits(value)) throw new Invalid();
        return value;
    }

    /**
     * canonical 입력 hash. 사건은 안정 순서로, 금액은 원문이 아니라 값으로 적는다 — 같은 값의 다른
     * 표기({@code "1.50"}과 {@code "1.5"})가 다른 hash가 되지 않게 한다. 모양이 틀린 값은 원문 그대로다.
     *
     * <p>필드마다 길이를 앞에 붙인다({@link Fields}). 구분자만으로 이으면 구분자를 담은 식별자 둘
     * ({@code "a|b"+"c"}와 {@code "a"+"b|c"})가 같은 문자열이 되어 서로 다른 입력이 같은 hash가 된다.
     */
    static String inputHash(PlanCalculator.Input input, List<Cashflow> ordered) {
        Fields fields = new Fields("input").add(input.calculationPolicy()).add(input.calendarPolicy())
                .add(PlanCalculator.IMPLEMENTATION_VERSION).add(normalized(input.principal()))
                .add(input.startAt().toEpochMilli()).add(input.endAt().toEpochMilli()).add(input.period())
                .add(normalized(input.targetRate())).add(normalized(input.currentValue()))
                .add(input.basisAt().toEpochMilli()).add(ordered.size());
        for (Cashflow flow : ordered) {
            fields.add("flow").add(flow.occurredAt().toEpochMilli()).add(flow.namespace()).add(flow.sourceId())
                    .add(normalized(flow.usdValue())).add(flow.estimated());
        }
        return sha256(fields.toString());
    }

    private static String resultHash(String inputHash, List<MetricResult> metrics, String adjustedPrincipal,
            String elapsed, String total, String remaining, List<AppliedCashflow> applied, boolean estimated,
            InputReliability reliability) {
        Fields fields = new Fields("result").add(inputHash).add(adjustedPrincipal).add(elapsed).add(total)
                .add(remaining).add(estimated).add(reliability).add(metrics.size());
        for (MetricResult metric : metrics) {
            fields.add("metric").add(metric.metric()).add(metric.value()).add(metric.nature())
                    .add(metric.unavailableReason());
        }
        fields.add(applied.size());
        for (AppliedCashflow flow : applied) {
            fields.add("applied").add(flow.sequence()).add(flow.occurredAt().toEpochMilli()).add(flow.namespace())
                    .add(flow.sourceId()).add(flow.usdValue()).add(flow.estimated()).add(flow.periodsToBasis())
                    .add(flow.periodsToEnd());
        }
        return sha256(fields.toString());
    }

    private static String normalized(String raw) {
        String notation = Decimals.notation(raw);
        return notation == null ? "raw:" + raw : notation;
    }

    /**
     * 모호하지 않은 직렬화: 필드마다 {@code 길이:값;}. 길이를 먼저 읽으면 값 안의 어떤 문자도 필드
     * 경계로 읽히지 않는다. {@code null}은 길이 {@code -1}로 적어 빈 문자열과 구분한다.
     */
    static final class Fields {
        private final StringBuilder payload = new StringBuilder();

        Fields(String kind) {
            add(kind);
        }

        Fields add(@Nullable Object value) {
            if (value == null) {
                this.payload.append("-1;");
                return this;
            }
            String text = value.toString();
            this.payload.append(text.length()).append(':').append(text).append(';');
            return this;
        }

        @Override
        public String toString() {
            return this.payload.toString();
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 입력이 계산 계약 밖이다. */
    private static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Invalid() {
            super(null, null, false, false);
        }
    }

    /**
     * canonical로 바꾸기 전의 34자리 지표 값. 시험이 승인 예제의 34자리 값과 직접 대조하는 창이다.
     * 정의되지 않은 지표는 담기지 않는다. 계산이 거절되면 {@code null}이다.
     */
    static @Nullable Map<Metric, BigDecimal> rawValues(PlanCalculator.Input input) {
        List<Cashflow> ordered = input.cashflows().stream().sorted(ORDER).toList();
        Map<Metric, BigDecimal> sink = new EnumMap<>(Metric.class);
        try {
            compute(input, ordered, inputHash(input, ordered), sink);
        } catch (RuntimeException rejected) {
            return null;
        }
        return sink;
    }
}
