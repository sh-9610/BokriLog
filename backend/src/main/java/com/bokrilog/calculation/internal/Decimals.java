package com.bokrilog.calculation.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code CALC-1.0}의 십진수 규칙: 입력 한도, 산술 문맥, 표현 범위, canonical 문자열.
 *
 * <p>입력과 결과의 규칙은 다르다. 입력은 {@code CALC-1.0} §3.1의 애플리케이션 입력 한도
 * ({@link Limit}) 안의 plain decimal string이면 되고 34 유효숫자일 필요가 없다 — 38자리 금액도
 * 받는다. 34자리 {@code HALF_EVEN}은 입력을 미리 자르지 않고 <b>첫 연산</b>에서 적용된다. 결과는
 * canonical decimal string(최대 34 유효숫자·scale 24)이다.
 *
 * <p>{@code sync}의 {@code PriceConversionPolicy}와 같은 결과 형식을 쓰지만 그것을 import하지 않는다.
 * 계산 모듈은 다른 모듈에 의존하지 않고 자기 규칙을 가진다.
 */
public final class Decimals {

    /** 모든 산술과 거듭제곱의 문맥. DECIMAL128과 같은 34자리 HALF_EVEN이다. */
    public static final MathContext CONTEXT = new MathContext(34, RoundingMode.HALF_EVEN);

    /** canonical 결과의 최대 소수 자릿수. */
    static final int MAX_SCALE = 24;

    /** 절대값 상한(배타). */
    static final BigDecimal UPPER_BOUND = new BigDecimal("1E24");

    /** 0이 아닌 절대값 하한(포함). 이보다 작은 값은 0으로 만들지 않고 거절한다. */
    static final BigDecimal LOWER_BOUND = new BigDecimal("1E-24");

    /** 입력 문자열의 모양. 지수 표기·부호 {@code +}·locale 구분자를 받지 않는다. */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?[0-9]{1,64}(\\.[0-9]{1,64})?");

    private Decimals() {}

    /**
     * 입력 한도. 소수 자릿수가 {@code scale} 이하이고, 정수 자릿수와 소수 자릿수의 합이
     * {@code precision} 이하다(계획 조건 모듈의 P0·목표율 검사와 같은 판정이다). 표기가 아니라 값으로
     * 판정한다(끝의 0은 세지 않는다): {@code "1.50"}과 {@code "1.5"}는 같은 입력이다.
     *
     * <p>표현 범위({@link #checked})가 한도보다 먼저다. 0이 아닌 {@code |x| < 1E-24}는 소수 자릿수가
     * 한도를 넘어도 형식 오류가 아니라 {@code CALC-1.0}이 정한 범위 오류다.
     */
    enum Limit {
        /** 금액({@code P0}, {@code E}): precision 38·scale 18. */
        AMOUNT(38, 18),
        /** 목표율: precision 18·scale 12. */
        RATE(18, 12),
        /**
         * 앞선 {@code CALC-1.0} 계산의 결과인 금액(Story 2.6의 사건 시점 USD 가치). 그 값은 이미
         * canonical 결과 형식(34 유효숫자·scale 24)이므로 그 한도를 그대로 쓴다.
         */
        RESULT(34, 24);

        final int precision;
        final int scale;

        Limit(int precision, int scale) {
            this.precision = precision;
            this.scale = scale;
        }

        boolean admits(BigDecimal value) {
            if (value.signum() == 0) return true;
            BigDecimal stripped = value.stripTrailingZeros();
            int fraction = Math.max(stripped.scale(), 0);
            int integer = Math.max(stripped.precision() - stripped.scale(), 0);
            return fraction <= this.scale && integer + fraction <= this.precision;
        }
    }

    /** 계산 전체를 멈추는 범위 위반. */
    static final class RangeExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RangeExceeded() {
            super(null, null, false, false);
        }
    }

    /**
     * 입력 문자열을 읽는다. 모양이 틀리면 {@code null}이다. 반올림하지 않는다. 표현 범위는
     * {@link #checked}가, 입력 한도는 {@link Limit#admits}가 따로 판정한다 — 범위가 먼저다.
     */
    static @Nullable BigDecimal parse(String raw) {
        return plain(raw);
    }

    /**
     * 손실 없는 정규 표기: 지수·앞의 0·끝의 0이 없고 0은 {@code "0"}이다. 반올림하지 않는다 — 값이
     * 같으면 표기가 같다. 모양이 틀리면 {@code null}이다.
     */
    public static @Nullable String notation(String raw) {
        BigDecimal value = plain(raw);
        if (value == null) return null;
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static @Nullable BigDecimal plain(String raw) {
        if (!PLAIN_DECIMAL.matcher(raw).matches()) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * 값이 표현 범위 안이면 그대로 돌려준다.
     *
     * @throws RangeExceeded {@code |x| >= 1E24}이거나 0이 아닌 {@code |x| < 1E-24}다
     */
    static BigDecimal checked(BigDecimal value) {
        if (value.signum() == 0) return value;
        BigDecimal magnitude = value.abs();
        if (magnitude.compareTo(UPPER_BOUND) >= 0 || magnitude.compareTo(LOWER_BOUND) < 0)
            throw new RangeExceeded();
        return value;
    }

    /**
     * canonical decimal string. 34 유효숫자·scale 24 이하로 HALF_EVEN 한 번 반올림하고, 끝의 0을
     * 지우며, 0은 {@code "0"}이다.
     *
     * <p>이미 {@link #CONTEXT}로 계산한 값이면 유효숫자 조건은 이미 성립하고 scale만 남는다.
     */
    public static String canonical(BigDecimal value) {
        BigDecimal rounded = value.round(CONTEXT);
        if (rounded.scale() > MAX_SCALE) rounded = rounded.setScale(MAX_SCALE, RoundingMode.HALF_EVEN);
        checked(rounded);
        if (rounded.signum() == 0) return "0";
        return rounded.stripTrailingZeros().toPlainString();
    }
}
