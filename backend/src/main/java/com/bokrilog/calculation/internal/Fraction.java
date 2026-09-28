package com.bokrilog.calculation.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Objects;

/**
 * 음이 아닌 정확한 유리수. {@code periods}를 구간별로 누적하는 데만 쓴다.
 *
 * <p>구간마다 34자리로 반올림해 더하면 {@code periods(a,c) = periods(a,b) + periods(b,c)}가 마지막
 * 자리에서 깨질 수 있다(CV-10). 그래서 누적은 이 분수로 정확하게 하고 십진 변환은
 * {@link #toDecimal(MathContext)}에서 한 번만 한다.
 *
 * <p>음수는 만들 수 없다. 기간은 겹친 길이를 구간 길이로 나눈 값이라 음수가 될 수 없고, 음수 분자가
 * 들어온다면 그것은 호출자의 시각 순서 오류다 — 부호를 조용히 받아 누적하지 않고 거절한다.
 */
public final class Fraction {

    public static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);

    private final BigInteger numerator;
    private final BigInteger denominator;

    private Fraction(BigInteger numerator, BigInteger denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
    }

    /**
     * {@code numerator / denominator}를 기약분수로 만든다.
     *
     * @throws IllegalArgumentException 분자가 음수이거나 분모가 양수가 아니다
     */
    public static Fraction of(long numerator, long denominator) {
        return of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    static Fraction of(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0) throw new IllegalArgumentException("denominator must be positive");
        if (numerator.signum() < 0) throw new IllegalArgumentException("numerator must not be negative");
        if (numerator.signum() == 0) return ZERO;
        BigInteger gcd = numerator.gcd(denominator);
        return new Fraction(numerator.divide(gcd), denominator.divide(gcd));
    }

    public Fraction plus(Fraction other) {
        return of(this.numerator.multiply(other.denominator).add(other.numerator.multiply(this.denominator)),
                this.denominator.multiply(other.denominator));
    }

    public boolean isZero() {
        return this.numerator.signum() == 0;
    }

    public int signum() {
        return this.numerator.signum();
    }

    /** 한 번의 나눗셈으로 십진수가 된다. 정확히 나누어떨어지면 정확한 값이다. */
    public BigDecimal toDecimal(MathContext context) {
        return new BigDecimal(this.numerator).divide(new BigDecimal(this.denominator), context);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Fraction that && this.numerator.equals(that.numerator)
                && this.denominator.equals(that.denominator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.numerator, this.denominator);
    }

    @Override
    public String toString() {
        return this.numerator + "/" + this.denominator;
    }
}
