package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

/** Story 3.8: 대기 문의 수 gauge는 측정하는 역할({@code worker}·{@code all})에만 있다. 첫 답변 카운터는 역할과 무관하다. */
class SupportPendingMetricsRoleTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withUserConfiguration(SupportPendingMetrics.class, SupportMetrics.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(SupportInquiryRepository.class, () -> mock(SupportInquiryRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void api_역할에는_대기_gauge가_없고_첫_답변_카운터는_있다() {
        this.web.withPropertyValues("bokrilog.role=api").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(SupportPendingMetrics.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.find(SupportMetrics.PENDING).gauges()).isEmpty();
            assertThat(registry.find(SupportMetrics.FIRST_ANSWER).counters()).hasSize(3);
        });
    }

    @Test
    void worker와_all_역할에는_대기_gauge가_있다() {
        for (String role : new String[] {"worker", "all"}) {
            this.web.withPropertyValues("bokrilog.role=" + role).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(SupportPendingMetrics.class);
                assertThat(context.getBean(MeterRegistry.class).find(SupportMetrics.PENDING).gauges()).hasSize(1);
            });
        }
    }
}
