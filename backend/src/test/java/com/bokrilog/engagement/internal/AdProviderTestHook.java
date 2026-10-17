package com.bokrilog.engagement.internal;

import org.springframework.context.ApplicationContext;

/**
 * 시험이 공급자 활성 여부를 바꾸는 문(Story 4.2). 시험 컨텍스트는 공급자 켜짐(시험 속성)으로 뜨고, 꺼짐을 보는 시험만 이 문으로
 * 잠시 끈다 — 속성을 달리하면 컨텍스트가 하나 더 뜬다({@code AuthFlowTestBase}). 운영 클래스의 {@code internal}을 루트 패키지
 * 시험에 열지 않으려고 같은 패키지의 이 클래스 하나만 둔다.
 */
public final class AdProviderTestHook {

    private AdProviderTestHook() {}

    /** 그 컨텍스트의 공급자 활성 여부를 바꾼다. 시험은 끝나면 켜짐으로 되돌린다. */
    public static void providerEnabled(ApplicationContext context, boolean enabled) {
        context.getBean(AdProviderState.class).reinterpret(enabled);
    }

    /** 판정을 직렬화하는 사용자 advisory 잠금 이름의 접두사. 운영 상수 그대로다(사본이 아니다). */
    public static final String USER_LOCK_PREFIX = AdAttemptRepository.USER_LOCK_PREFIX;

    /** 발급하는 transition 참조의 바이트 수. 운영 상수 그대로다(사본이 아니다). */
    public static final int TRANSITION_BYTES = AdEligibilityService.TRANSITION_BYTES;
}
