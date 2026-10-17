package com.bokrilog.engagement.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bokrilog.engagement.AdTelemetry;
import com.bokrilog.operations.AdvertisingStopApi;
import com.bokrilog.operations.OperationalRestrictionApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

/** Story 4.5: 광고 성능 판정과 표본 gauge는 스케줄링 역할({@code worker}·{@code all})에만 있다 — API 역할에는 없다. */
class AdPerformanceJudgeRoleTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withUserConfiguration(AdPerformanceJudge.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(AdVitalSampleRepository.class, () -> mock(AdVitalSampleRepository.class))
            .withBean(AdvertisingStopApi.class, () -> mock(AdvertisingStopApi.class))
            .withBean(OperationalRestrictionApi.class, () -> mock(OperationalRestrictionApi.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void api_역할에는_판정도_표본_gauge도_없다() {
        this.web.withPropertyValues("bokrilog.role=api").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(AdPerformanceJudge.class);
            assertThat(context.getBean(MeterRegistry.class).find(AdTelemetry.VITAL_SAMPLES).gauges()).isEmpty();
        });
    }

    @Test
    void worker와_all_역할에는_판정과_metric_셋의_gauge가_있다() {
        for (String role : new String[] {"worker", "all"}) {
            this.web.withPropertyValues("bokrilog.role=" + role).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(AdPerformanceJudge.class);
                assertThat(context.getBean(MeterRegistry.class).find(AdTelemetry.VITAL_SAMPLES).gauges()).hasSize(3);
            });
        }
    }
}
