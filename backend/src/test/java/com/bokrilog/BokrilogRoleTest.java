package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.MapPropertySource;
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

    /**
     * Story 5.3 Q8: tombstone digest bean의 조건은 모듈 안의 식({@code '${bokrilog.role:all}' != 'api'})이다 — 모듈이 루트 타입
     * {@link BokrilogRole}을 참조하면 순환이라 같은 판정을 식으로 둔다. 그 식은 {@code API}·{@code " api "} 같은 값을 "api가 아님"으로 읽지만,
     * 역할 해석({@link BokrilogRole#parse} — 대소문자·공백을 접지 않는다)이 그 값으로는 servlet 프로세스의 기동을 거부한다. 그래서 API
     * 프로세스가 tombstone 키를 쥐고 뜨는 일은 없다. 정확한 {@code api}는 기동하고 tombstone bean이 없다.
     */
    @Test
    void tombstone_조건이_api로_읽지_않는_역할_값은_역할_해석이_기동을_거부한다() throws Exception {
        Class<?> operations = Class.forName("com.bokrilog.operations.internal.OperationsConfiguration");
        WebApplicationContextRunner tombstone = new WebApplicationContextRunner()
                .withUserConfiguration(BokrilogRoleConfig.class, operations)
                .withPropertyValues("bokrilog.operations.audit-digest-key=" + key(), "bokrilog.tombstone.digest-key=" + key());
        tombstone.withInitializer(context -> role(context, "api")).run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean("operationsTombstoneDigest"));
        tombstone.withInitializer(context -> role(context, "worker")).run(context ->
                assertThat(context).hasNotFailed().hasBean("operationsTombstoneDigest"));
        for (String value : new String[] {"API", " api ", "api ", " api", "Api"}) {
            tombstone.withInitializer(context -> role(context, value)).run(context ->
                    assertThat(context).as("'" + value + "'").hasFailed());
            assertThatThrownBy(() -> BokrilogRole.parse(value)).as("'" + value + "'")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** 공백을 그대로 지키는 속성 출처(환경 변수처럼 — 시험 속성 쌍은 앞뒤 공백을 자른다). */
    private static void role(ConfigurableApplicationContext context, String value) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("role", Map.of("bokrilog.role", value)));
    }

    /** 32바이트 키(base64). 실행 시점에 만든다(gitleaks). */
    private static String key() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
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
