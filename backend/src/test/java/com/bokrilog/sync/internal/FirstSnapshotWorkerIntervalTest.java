package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 운영 워커 주기: dispatch 여섯은 {@code application.yml}의 1초, heartbeat는 5초.
 *
 * <p>Docker를 쓰는 시험은 전부 {@code AuthFlowTestBase}가 dispatch 간격을 100ms로 덮어쓴 컨텍스트에서
 * 돈다. 그래서 운영값을 고정하는 시험이 이것 말고는 없다 — 기본값이 바뀌거나 dispatch 하나가 고정
 * 지연으로 되돌아가도 나머지 시험 묶음은 green이다. 실제 설정 파일을 {@link ApplicationContextRunner}로
 * 읽고({@code AuthPropertyBindingTest}와 같은 관용) 애너테이션을 직접 본다. 0 이하의 간격을 거부하는
 * 생성자 guard도 여기서 본다.
 */
class FirstSnapshotWorkerIntervalTest {
    private static final String PLACEHOLDER = "${bokrilog.sync.worker.dispatch-interval}";

    @Test
    void theProductionCadenceIsOneSecondPerDispatchAndFiveSecondsPerHeartbeat() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(Binder.get(context.getEnvironment())
                            .bind("bokrilog.sync.worker.dispatch-interval", Duration.class).get())
                            .isEqualTo(Duration.ofSeconds(1));
                });
        var scheduled = Arrays.stream(FirstSnapshotWorker.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class)).toList();
        assertThat(scheduled.stream().filter(method -> PLACEHOLDER.equals(annotationOf(method).fixedDelayString()))
                .map(Method::getName)).containsExactlyInAnyOrder("dispatch", "dispatchCashflowConversion",
                        "dispatchPlanPublication", "dispatchPlanSync", "dispatchInitialInputs",
                        "dispatchCashflowClassification");
        var heartbeat = scheduled.stream().filter(method -> method.getName().equals("heartbeat")).toList();
        assertThat(heartbeat).singleElement().satisfies(method -> {
            assertThat(annotationOf(method).fixedDelay()).isEqualTo(5000L);
            assertThat(annotationOf(method).fixedDelayString()).isEmpty();
        });
        assertThat(scheduled).hasSize(7);
    }

    @Test
    void theConstructorRefusesAnIntervalThatIsNotPositive() {
        for (Duration refused : new Duration[] {Duration.ZERO, Duration.ofMillis(-1)}) {
            assertThatThrownBy(() -> new FirstSnapshotWorker(null, null, null, null, refused)).as(refused.toString())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("bokrilog.sync.worker.dispatch-interval must be positive");
        }
        assertThat(new FirstSnapshotWorker(null, null, null, null, Duration.ofMillis(1))).isNotNull();
    }

    private static Scheduled annotationOf(Method method) {
        return method.getAnnotation(Scheduled.class);
    }
}
