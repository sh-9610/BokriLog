package com.bokrilog.operations;

/**
 * 운영 제한의 범위(Story 3.2). 제한 하나는 {@code 기능 × scope × target}이다.
 *
 * <p><b>구체성.</b> {@link #specificity()}가 클수록 구체적이다 — 계획 하나가 가장 좁고, 연결·portfolio가 그다음, 모든 사용자에
 * 걸치는 계산 정책 버전, 마지막이 전역이다. 이 순서는 <b>차단을 줄이지 않는다</b>: 허용 행이 없으므로 차단은 일치하는 행의
 * 합집합이다. 순서는 어느 제한이 효력을 갖는지 보고하는 데(활성 목록 정렬, 3.5 공지 매핑)만 쓴다.
 *
 * <p>계획·portfolio·연결 target은 사용자에게 귀속된다({@link #userAttributed()}) — 감사 원장에는 keyed digest로만 남고
 * 계정 삭제 시 제한 행의 target도 digest로 바뀐다.
 */
public enum RestrictionScope {
    /** 그 기능 전체. target은 고정값 {@link #GLOBAL_TARGET}이다. */
    GLOBAL(0, false),
    /** 한 계산 정책 버전(예: {@code CALC-1.0})의 결과. */
    CALCULATION_POLICY_VERSION(1, false),
    /** 한 portfolio. */
    PORTFOLIO(2, true),
    /** 한 제공자 연결. */
    PROVIDER_CONNECTION(3, true),
    /** 한 계획. */
    PLAN(4, true);

    /** 전역 제한의 유일한 target. */
    public static final String GLOBAL_TARGET = "ALL";

    private final int specificity;

    private final boolean userAttributed;

    RestrictionScope(int specificity, boolean userAttributed) {
        this.specificity = specificity;
        this.userAttributed = userAttributed;
    }

    /** 클수록 구체적이다. */
    public int specificity() {
        return this.specificity;
    }

    /** target이 사용자에게 귀속된 원식별자인가. */
    public boolean userAttributed() {
        return this.userAttributed;
    }
}
