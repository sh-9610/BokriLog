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
 * Story 4.2·4.3: 광고 공급자 활성의 기본 구성과 ad unit 경로 형식. 시험 컨텍스트는 모두 켜짐을 주입하므로 기본값은 여기서
 * 본다 — 전체 컨텍스트 없이 실제 {@code application.yml}의 기본 문서(프로필 문서 제외)를 그대로 올리고 engagement 설정과 공급자
 * 상태만 바인딩한다(덮어쓰기 없음).
 */
class EngagementPropertiesBindingTest {

    private static final String VALID = "/1234567/bokrilog_test";

    @Test
    void 기본_구성의_공급자는_꺼짐이고_플래그와_형식에_맞는_단위_경로가_함께여야_켜진다() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            EngagementProperties properties = context.getBean(EngagementProperties.class);
            assertThat(properties.adProviderEnabled()).isFalse();
            assertThat(properties.adUnitPath()).isNullOrEmpty();
            assertThat(context.getBean(AdProviderState.class).enabled()).isFalse();
            assertThat(context.getBean(AdProviderState.class).adUnitPath()).isNull();
        });
        // Story 4.3: 플래그만 켜면 단위 경로가 비어 꺼짐이다(기동은 그대로다).
        runner().withPropertyValues("BOKRILOG_AD_PROVIDER_ENABLED=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdProviderState.class).enabled()).isFalse();
        });
        runner().withPropertyValues("BOKRILOG_AD_PROVIDER_ENABLED=true", "BOKRILOG_AD_UNIT_PATH=" + VALID)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AdProviderState.class).enabled()).isTrue();
                    assertThat(context.getBean(AdProviderState.class).adUnitPath()).isEqualTo(VALID);
                });
        // 단위 경로가 있어도 플래그가 꺼짐이면 꺼짐이다.
        runner().withPropertyValues("BOKRILOG_AD_PROVIDER_ENABLED=false", "BOKRILOG_AD_UNIT_PATH=" + VALID)
                .run(context -> {
                    assertThat(context.getBean(AdProviderState.class).enabled()).isFalse();
                    assertThat(context.getBean(AdProviderState.class).adUnitPath()).isNull();
                });
    }

    /** 형식이 틀린 단위 경로는 기동을 막지 않고 공급자를 끈다. */
    @Test
    void 형식이_틀린_단위_경로는_기동을_막지_않고_공급자를_끈다() {
        for (String invalid : List.of("1234567/unit", "/1234567", "/1234567/", "/abc/unit", "/1234567/unit name",
                "/1234567/unit?x=1", "https://example.test/1234567/unit", "/1234567//unit", "/1234567/유닛")) {
            runner().withPropertyValues("BOKRILOG_AD_PROVIDER_ENABLED=true", "BOKRILOG_AD_UNIT_PATH=" + invalid)
                    .run(context -> {
                        assertThat(context).as(invalid).hasNotFailed();
                        assertThat(context.getBean(AdProviderState.class).enabled()).as(invalid).isFalse();
                        assertThat(context.getBean(AdProviderState.class).adUnitPath()).as(invalid).isNull();
                    });
        }
        for (String valid : List.of(VALID, "/1/a", "/1234567/parent/child.unit-2_x")) {
            runner().withPropertyValues("BOKRILOG_AD_PROVIDER_ENABLED=true", "BOKRILOG_AD_UNIT_PATH=" + valid)
                    .run(context -> assertThat(context.getBean(AdProviderState.class).enabled()).as(valid).isTrue());
        }
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
            assertThat(base.getProperty("bokrilog.engagement.ad-unit-path")).as("기본 문서의 값")
                    .hasToString("${BOKRILOG_AD_UNIT_PATH:}");
            return base;
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
