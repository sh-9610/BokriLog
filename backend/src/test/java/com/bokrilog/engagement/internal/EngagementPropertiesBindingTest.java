package com.bokrilog.engagement.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Story 4.2: 광고 공급자 활성의 기본 구성. 시험 컨텍스트는 모두 켜짐을 주입하므로 기본값은 여기서 본다 — 전체 컨텍스트 없이 실제
 * {@code application.yml}의 기본 문서(프로필 문서 제외)를 그대로 올리고 engagement 설정과 공급자 상태만 바인딩한다(덮어쓰기 없음).
 */
class EngagementPropertiesBindingTest {

    @Test
    void 기본_구성의_공급자는_꺼짐이고_환경_변수가_true면_켜진다() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EngagementProperties.class).adProviderEnabled()).isFalse();
            assertThat(context.getBean(AdProviderState.class).enabled()).isFalse();
        });
        runner().withPropertyValues("BOKRILOG_AD_PROVIDER_ENABLED=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdProviderState.class).enabled()).isTrue();
        });
        runner().withPropertyValues("BOKRILOG_AD_PROVIDER_ENABLED=false").run(context ->
                assertThat(context.getBean(AdProviderState.class).enabled()).isFalse());
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(applicationYml()))
                .withUserConfiguration(EngagementConfiguration.class, AdProviderState.class);
    }

    /** {@code application.yml}의 첫 문서(프로필 없는 기본 구성). */
    private static PropertySource<?> applicationYml() {
        try {
            List<PropertySource<?>> documents =
                    new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
            PropertySource<?> base = documents.getFirst();
            assertThat(base.getProperty("bokrilog.engagement.ad-provider-enabled")).as("기본 문서의 값")
                    .hasToString("${BOKRILOG_AD_PROVIDER_ENABLED:false}");
            return base;
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
