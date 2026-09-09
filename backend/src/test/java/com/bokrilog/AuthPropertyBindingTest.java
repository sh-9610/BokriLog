package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.AuthSecurityConfig.AuthProperties;
import com.bokrilog.AuthSecurityConfig.ProviderClientRegistrations;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * 문서화된 환경 변수 → 실제 {@code application.yml} → 제공자 등록.
 *
 * <p>이 사슬을 지나는 시험이 여기 하나뿐이다. 나머지 green 경로는 전부 그 가운데를 건너뛴다 —
 * {@code AuthFlowTestBase} 계열은 {@code bokrilog.auth.<제공자>.*}를 {@code DynamicPropertyRegistry}로
 * 직접 주입하고, {@code dev-oidc} 프로필은 등록 저장소를 통째로 갈아 끼우며,
 * {@code DevOidcStubProfileTest}·{@code AuthKakaoSignInTest}는 {@code AuthProperties}를 손으로
 * 조립한다. 그래서 {@code application.yml}의 placeholder 이름에 오타가 나도, {@code kakao:} 블록이
 * 통째로 지워져도 시험 묶음 전체가 green이다.
 *
 * <p>그때 깨지는 것은 실제 배포다. 문서가 약속한 환경 변수
 * ({@code BOKRILOG_PUBLIC_BASE_URL}·{@code BOKRILOG_<제공자>_CLIENT_ID}·{@code _CLIENT_SECRET})를
 * 정확히 준 운영자가 등록을 하나도 얻지 못하고, 그 결과는 fail-closed 500이라 <b>"운영자가 구성하지
 * 않았다"와 응답으로 구분되지 않는다</b>. 구성이 옳은데도 옳지 않은 것과 같은 답이 나오는 것이 이
 * 공백의 성질이다.
 *
 * <p>Spring 컨텍스트를 통째로 띄우지 않는다. 확인하려는 것은 데이터베이스 동작이 아니라 "실제 설정
 * 파일이 그 이름들을 실제로 잇는가"이며, {@link ApplicationContextRunner}에
 * {@link ConfigDataApplicationContextInitializer}를 얹으면 그 파일이 실제로 읽히고 Docker도 필요
 * 없다({@code DevOidcStubProfileTest}가 쓰는 것과 같은 관용이다).
 *
 * <p>등록 조립 자체는 다시 보지 않는다. scope·PKCE·엔드포인트·client 인증 방법은
 * {@code DevOidcStubProfileTest}가 소유하고, 여기서 보는 것은 이름이 실제로 이어졌다는 사실 하나다.
 */
class AuthPropertyBindingTest {

    /** 이 애플리케이션이 이름을 아는 제공자. */
    private static final List<String> PROVIDERS =
            List.of(
                    AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                    AuthSecurityConfig.NAVER_REGISTRATION_ID,
                    AuthSecurityConfig.KAKAO_REGISTRATION_ID);

    private static final String PUBLIC_BASE_URL = "https://bokrilog.test";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    // 실제 `application.yml`을 읽는다. 이 한 줄이 이 시험의 전부다.
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(AuthPropertiesBinding.class);

    @Test
    void 문서화된_환경_변수만으로_제공자마다_등록이_만들어진다() {
        for (String provider : PROVIDERS) {
            /*
             * 값은 실행 시점에 만든다. 자격정보 모양 리터럴을 시험 fixture에도 두지 않는다.
             *
             * 이름은 `application.yml` 머리말이 문서로 약속한 그대로다. 환경 변수는 시험이 프로세스
             * 환경을 바꿀 수 없으므로 같은 이름의 property로 준다 — `${BOKRILOG_...:}` placeholder는
             * Environment 전체에서 그 이름을 찾으므로 두 경로가 같은 자리에서 만난다.
             */
            String clientId = "client-" + UUID.randomUUID();
            String clientSecret = UUID.randomUUID().toString();
            String prefix = "BOKRILOG_" + provider.toUpperCase(Locale.ROOT);

            runner.withPropertyValues(
                            "BOKRILOG_PUBLIC_BASE_URL=" + PUBLIC_BASE_URL,
                            prefix + "_CLIENT_ID=" + clientId,
                            prefix + "_CLIENT_SECRET=" + clientSecret)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                ProviderClientRegistrations registrations =
                                        ProviderClientRegistrations.from(
                                                context.getBean(AuthProperties.class));

                                ClientRegistration registration =
                                        registrations.findByRegistrationId(provider);
                                assertThat(registration).as("%s 등록", provider).isNotNull();
                                // 그 제공자의 두 이름이 그 제공자의 자리로 갔다.
                                assertThat(registration.getClientId())
                                        .as("%s client id", provider)
                                        .isEqualTo(clientId);
                                assertThat(registration.getClientSecret())
                                        .as("%s client secret", provider)
                                        .isEqualTo(clientSecret);
                                // 공개 base URL도 이어졌다. 비면 어느 제공자도 만들어지지 않는다.
                                assertThat(registration.getRedirectUri())
                                        .as("%s redirect URI", provider)
                                        .isEqualTo(
                                                PUBLIC_BASE_URL
                                                        + AuthSecurityConfig.CALLBACK_BASE_URI
                                                        + "/"
                                                        + provider);

                                /*
                                 * 나머지는 비어 있다. 이름이 한 자리로 몰리는 오타(모든 제공자가
                                 * `bokrilog.auth.google.*`을 읽는 것 같은)는 위 세 단정을 전부
                                 * 통과하고 이 단정에서만 드러난다.
                                 */
                                for (String other : PROVIDERS) {
                                    if (!other.equals(provider)) {
                                        assertThat(registrations.findByRegistrationId(other))
                                                .as("%s만 구성된 스택의 %s 등록", provider, other)
                                                .isNull();
                                    }
                                }
                            });
        }
    }

    /**
     * {@code bokrilog.auth} 바인딩만 켜는 최소 구성.
     *
     * <p>{@code AuthSecurityConfig}를 쓰지 않는다. 그 클래스는 servlet 웹 애플리케이션에서만
     * 배선되므로({@code @ConditionalOnWebApplication}) 이 runner에서는 아무것도 만들지 않고, 그러면
     * 확인하려는 바인딩 자체가 일어나지 않는다.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class AuthPropertiesBinding {}
}
