package com.bokrilog.sync.internal;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * 다른 패키지의 시험이 실제 {@link WorkerDrain} bean(ObjectProvider 생성자)을 띄우고 그 묶음을 들여다보는 통로(Story 5.2). 운영 코드가
 * 아니다 — {@code OperationsTestHook}과 같은 관용이다.
 */
public final class WorkerDrainTestHook {

    private WorkerDrainTestHook() {}

    /** {@link WorkerDrain}의 bean 형식(시험 컨텍스트에 사용자 구성으로 넣는다). */
    public static Class<?> drainType() {
        return WorkerDrain.class;
    }

    /** 진행 중이 없는 이 프로세스의 작업자({@code FirstSnapshotWorker} 대역)를 등록한다. */
    public static ApplicationContextInitializer<ConfigurableApplicationContext> idleTarget() {
        return context -> ((GenericApplicationContext) context).registerBean(WorkerDrain.Target.class,
                () -> new WorkerDrain.Target() {
                    @Override
                    public void stopClaims() {}

                    @Override
                    public int inFlight() {
                        return 0;
                    }

                    @Override
                    public void heartbeat() {}
                });
    }

    /** 실제 drain bean이 묶은 대상의 진행 중 수. */
    public static int combinedInFlight(ApplicationContext context) {
        return context.getBean(WorkerDrain.class).combined().inFlight();
    }

    /** 실제 drain bean이 묶은 대상 모두의 claim을 멈춘다(종료 drain의 첫 동작). */
    public static void stopAll(ApplicationContext context) {
        context.getBean(WorkerDrain.class).combined().stopClaims();
    }
}
