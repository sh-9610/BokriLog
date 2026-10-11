package com.bokrilog;

import java.lang.reflect.Method;
import org.springframework.context.ApplicationContext;

/**
 * 시험 전용: 모듈 내부의 주기 측정을 지금 한 번 돌린다(Story 3.7). 측정 컴포넌트는 모듈의 {@code internal} 패키지에 있어
 * 루트 시험이 타입으로 닿을 수 없다 — 빈 이름과 메서드 이름으로 부른다. 스케줄(15·60초)을 기다리지 않게 하는 것뿐이다.
 */
final class TelemetryProbe {

    private TelemetryProbe() {}

    static void run(ApplicationContext context, String beanName, String methodName) {
        Object bean = context.getBean(beanName);
        try {
            Method method = bean.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(bean);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(beanName + "." + methodName + "를 부르지 못했다.", failure);
        }
    }
}
