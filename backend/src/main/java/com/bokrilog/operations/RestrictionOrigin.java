package com.bokrilog.operations;

/**
 * 제한 행을 만든 출처(Story 3.3). 운영자 command(Story 3.2)이거나 강한 신호의 자동 정지다.
 *
 * <p>신호가 만든 제한도 자동으로 풀리지 않는다 — 운영자 화면은 그 행을 {@code 자동 정지}·{@code 수동 검증 필요}로 보이고,
 * 해제는 검증 증거를 확인한 운영자의 몫이다(Story 3.4).
 */
public enum RestrictionOrigin {
    /** 운영자 command({@code restrict-<기능>})가 만들었다. 출처가 없던 옛 행도 여기다. */
    OPERATOR,
    /** 강한 신호가 자동으로 만들었다({@link OperationalSignalApi}). */
    SIGNAL
}
