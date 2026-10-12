package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.ResolvableType;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * Story 3.8 AC-3: 역할별 배선.
 *
 * <p>스케줄링(dispatch·heartbeat·주기 측정)은 {@code worker}·{@code all}에만 있고 {@code api}에는 없다. Worker는 공개 포트를
 * 루프백에 묶는다. 운영({@code BOKRILOG_ENV=production})에서 {@code all}은 기동 거부다. 웹이 아닌 일회성 역할에는 역할도
 * 스케줄링도 없다. 컨텍스트를 통째로 띄우지 않고 배선만 본다({@code SchemaValidationConfigTest}와 같은 관용) — Docker가 필요 없다.
 */
class BokrilogRoleTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withUserConfiguration(FirstSnapshotSchedulingConfig.class, BokrilogRoleConfig.class);

    private final ApplicationContextRunner oneShot = new ApplicationContextRunner()
            .withUserConfiguration(FirstSnapshotSchedulingConfig.class, BokrilogRoleConfig.class);

    @Test
    void 기본_역할은_all이고_스케줄링이_있다() {
        this.web.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context.getBean(BokrilogRole.class)).isEqualTo(BokrilogRole.ALL);
            assertThat(context.getBeanNamesForType(customizerType())).isEmpty();
        });
    }

    @Test
    void api_역할은_스케줄링이_없다() {
        this.web.withPropertyValues("bokrilog.role=api").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context.getBean(BokrilogRole.class)).isEqualTo(BokrilogRole.API);
            assertThat(context.getBeanNamesForType(customizerType())).isEmpty();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void worker_역할은_스케줄링이_있고_공개_포트를_루프백에_묶는다() throws Exception {
        this.web.withPropertyValues("bokrilog.role=worker").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context.getBean(BokrilogRole.class)).isEqualTo(BokrilogRole.WORKER);
            String[] names = context.getBeanNamesForType(customizerType());
            assertThat(names).hasSize(1);
            WebServerFactoryCustomizer<ConfigurableWebServerFactory> customizer =
                    (WebServerFactoryCustomizer<ConfigurableWebServerFactory>) context.getBean(names[0]);
            ConfigurableWebServerFactory factory = mock(ConfigurableWebServerFactory.class);
            customizer.customize(factory);
            verify(factory).setAddress(InetAddress.getByName("127.0.0.1"));
        });
    }

    @Test
    void 운영에서_all은_기동_거부이고_api_worker는_뜬다() {
        this.web.withPropertyValues("bokrilog.telemetry.env=production").run(context ->
                assertThat(context).hasFailed());
        this.web.withPropertyValues("bokrilog.telemetry.env=production", "bokrilog.role=all").run(context ->
                assertThat(context).hasFailed());
        this.web.withPropertyValues("bokrilog.telemetry.env=production", "bokrilog.role=api").run(context ->
                assertThat(context).hasNotFailed());
        this.web.withPropertyValues("bokrilog.telemetry.env=production", "bokrilog.role=worker").run(context ->
                assertThat(context).hasNotFailed());
        // 개발·CI의 all은 지금까지의 동작이다.
        this.web.withPropertyValues("bokrilog.telemetry.env=ci", "bokrilog.role=all").run(context ->
                assertThat(context).hasNotFailed());
    }

    @Test
    void 세_값_밖의_역할은_기동_거부다() {
        for (String role : new String[] {"API", "Worker", "both", "migrate"}) {
            this.web.withPropertyValues("bokrilog.role=" + role).run(context -> assertThat(context).as(role).hasFailed());
        }
        assertThatThrownBy(() -> BokrilogRole.parse("ALL")).isInstanceOf(IllegalStateException.class);
        assertThat(BokrilogRole.parse(null)).isEqualTo(BokrilogRole.ALL);
        assertThat(BokrilogRole.parse("")).isEqualTo(BokrilogRole.ALL);
    }

    @Test
    void 웹이_아닌_일회성_역할에는_역할도_스케줄링도_없다() {
        this.oneShot.withPropertyValues("bokrilog.telemetry.env=production").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(BokrilogRole.class)
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    private static ResolvableType customizerType() {
        return ResolvableType.forClassWithGenerics(WebServerFactoryCustomizer.class, ConfigurableWebServerFactory.class);
    }
}
