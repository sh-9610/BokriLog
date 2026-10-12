package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bokrilog.sync.SyncTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Story 3.8: 대기 나이 gauge는 측정하는 역할(스케줄링이 도는 {@code worker}·{@code all})에만 있다. API 역할이 NaN gauge를 영원히
 * 내보내면 운영 API 슬롯마다 {@code SyncQueueAgeUnmeasured}가 울린다.
 */
class SyncQueueAgeMetricsRoleTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withUserConfiguration(SyncQueueAgeMetrics.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void api_역할에는_대기_나이_gauge가_없다() {
        this.web.withPropertyValues("bokrilog.role=api").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(SyncQueueAgeMetrics.class);
            assertThat(context.getBean(MeterRegistry.class).find(SyncTelemetry.QUEUE_AGE).gauges()).isEmpty();
        });
    }

    @Test
    void worker와_all_역할에는_대기_나이_gauge가_있다() {
        for (String role : new String[] {"worker", "all"}) {
            this.web.withPropertyValues("bokrilog.role=" + role).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(SyncQueueAgeMetrics.class);
                assertThat(context.getBean(MeterRegistry.class).find(SyncTelemetry.QUEUE_AGE).gauges())
                        .hasSize(SyncTelemetry.Kind.values().length);
            });
        }
    }

    @Test
    void 웹이_아닌_일회성_역할에도_없다() {
        new ApplicationContextRunner().withUserConfiguration(SyncQueueAgeMetrics.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SyncQueueAgeMetrics.class));
    }
}
