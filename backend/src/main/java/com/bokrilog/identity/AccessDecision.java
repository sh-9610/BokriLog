package com.bokrilog.identity;

/**
 * 접근 판정 결과.
 *
 * <p>값이 둘뿐인 것이 이 타입의 요점이다. 거부 사유를 enum으로 늘리면 호출자가 사유를 조합해
 * "그 계정이 존재하는가"를 복원할 수 있다. 타 사용자 대상, 존재하지 않는 대상, 삭제된 대상은
 * 모두 같은 {@link #DENIED} 하나로 답한다.
 */
public enum AccessDecision {

    /** 허용한다. */
    ALLOWED,

    /** 거부한다. 사유는 이 값에도, 로그에도 남지 않는다. */
    DENIED
}
