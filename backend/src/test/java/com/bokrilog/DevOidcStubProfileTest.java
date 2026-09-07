package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.AuthSecurityConfig.AuthProperties;
import com.bokrilog.AuthSecurityConfig.GoogleClientRegistrations;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.web.servlet.function.RouterFunction;

/**
 * 개발용 OIDC 스텁을 가르는 프로필.
 *
 * <p>스텁은 {@code main}에 있다. 브라우저가 인가 요청을 보내고 백엔드가 code 교환을 보내므로 성공
 * 경로를 브라우저 층에서 검증하려면 한 스텁이 양쪽에서 도달 가능해야 하기 때문이다. 그 배치의
 * 대가가 "운영에서 조용히 켜질 수 있다"이고, 이 시험이 그 대가를 두 겹으로 막는다.
 *
 * <p>Spring 컨텍스트를 통째로 띄우지 않는다. 확인하려는 것은 데이터베이스 동작이 아니라 "어느
 * 프로필에서 어느 빈이 만들어지는가"라는 배선이며, {@link ApplicationContextRunner}면 그것만 정확히
 * 볼 수 있고 Docker도 필요 없다. {@code SchemaValidationConfigTest}가 Flyway 전략 선택에 쓰는 것과
 * 같은 관용이다.
 *
 * <p>{@code /api/dev-oidc/**}의 노출 여부를 빈으로 단정한다. 경로 네 개가 {@link RouterFunction}
 * 하나에 실려 있으므로 그 빈의 부재가 곧 경로의 부재다. 컨트롤러 애노테이션으로 흩어 두면 "노출되지
 * 않는다"를 확인하려면 서블릿 컨테이너를 띄워야 한다.
 */
class DevOidcStubProfileTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(DevOidcStubConfig.class);

    @Test
    void 기본_프로필에는_스텁_빈이_없고_경로도_노출되지_않는다() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DevOidcStub.class);
                    // 라우팅 빈이 없으면 `/api/dev-oidc/**`를 받는 핸들러가 존재하지 않는다.
                    assertThat(context).doesNotHaveBean(RouterFunction.class);
                    // 스텁이 제공자 등록을 가리지도 않는다. 운영 빈 하나만 남는다.
                    assertThat(context).doesNotHaveBean(GoogleClientRegistrations.class);
                });
    }

    @Test
    void 기본_프로필의_제공자_등록은_자격정보가_없으면_구성되지_않는다() {
        /*
         * I/O 매트릭스의 "자격정보 없는 개발·CI 스택" 행이다. 개발·CI compose는 이제 스텁 프로필로
         * 뜨므로 그 계약을 지키는 것은 compose가 아니라 이 단정이다. 등록이 만들어지지 않으면
         * 인증 필터가 배선되지 않고 인증 시작은 공통 오류 계약으로 fail-closed다
         * (`AuthSessionController`).
         */
        assertThat(GoogleClientRegistrations.from(new AuthProperties(null, null)).isConfigured())
                .isFalse();
        assertThat(
                        GoogleClientRegistrations.from(
                                        new AuthProperties(
                                                "https://bokrilog.test",
                                                new AuthProperties.Google("", "", null, null, null, null)))
                                .isConfigured())
                .isFalse();
    }

    @Test
    void 공개_base_URL은_origin_하나만_담은_절대_URL이어야_한다() {
        /*
         * 이 값은 두 곳에서 쓰인다 — redirect URI 조립과 `SameOriginFilter`의 `Origin` 비교.
         * 경로가 붙은 값은 앞에서 등록값과 다른 URI를 만들고, 뒤에서는 브라우저가 보내는
         * origin과 영구히 어긋나 모든 상태 변경 요청을 403으로 만든다. 두 번째는 조용하다 —
         * 아무 오류도 나지 않고 그냥 전부 거절된다. 스킴 접두사만 보면 그 값이 통과한다.
         */
        assertThat(GoogleClientRegistrations.normalizedBaseUrl("https://bokrilog.test"))
                .isEqualTo("https://bokrilog.test");
        // 끝의 `/`는 정규화가 지운다. 뒤에 경로를 붙여 쓰므로 `//api/...`가 되면 다른 URI다.
        assertThat(GoogleClientRegistrations.normalizedBaseUrl("http://127.0.0.1:18080/"))
                .isEqualTo("http://127.0.0.1:18080");

        assertThat(GoogleClientRegistrations.normalizedBaseUrl("https://bokrilog.test/app")).isNull();
        assertThat(GoogleClientRegistrations.normalizedBaseUrl("https://bokrilog.test?next=/x"))
                .isNull();
        assertThat(GoogleClientRegistrations.normalizedBaseUrl("https://bokrilog.test#f")).isNull();
        // userinfo가 있으면 origin이 아니다.
        assertThat(GoogleClientRegistrations.normalizedBaseUrl("https://someone@bokrilog.test"))
                .isNull();
        // host가 없다. 비교할 origin도, 도달할 주소도 없다.
        assertThat(GoogleClientRegistrations.normalizedBaseUrl("http://")).isNull();
        // 스킴이 없거나 http(s)가 아니다.
        assertThat(GoogleClientRegistrations.normalizedBaseUrl("/api")).isNull();
        assertThat(GoogleClientRegistrations.normalizedBaseUrl("bokrilog.test")).isNull();
        assertThat(GoogleClientRegistrations.normalizedBaseUrl("ftp://bokrilog.test")).isNull();
        // URI로 읽히지 않는 값은 예외가 아니라 빈 값이다. 구성이 잘못된 스택은 기동 실패가
        // 아니라 fail-closed로 남는 편이 계약에 맞다.
        assertThat(GoogleClientRegistrations.normalizedBaseUrl("http://bokri log.test")).isNull();
    }

    @Test
    void 경로가_붙은_공개_base_URL로는_제공자_등록이_만들어지지_않는다() {
        assertThat(
                        GoogleClientRegistrations.from(
                                        new AuthProperties(
                                                "https://bokrilog.test/app",
                                                new AuthProperties.Google(
                                                        "client-" + UUID.randomUUID(),
                                                        UUID.randomUUID().toString(),
                                                        null,
                                                        null,
                                                        null,
                                                        null)))
                                .isConfigured())
                .isFalse();
    }

    @Test
    void dev_oidc_프로필은_스텁_빈과_경로를_함께_들인다() {
        runner.withPropertyValues(
                        "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                        "bokrilog.auth.public-base-url=https://bokrilog.test")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(DevOidcStub.class);
                            assertThat(context).hasSingleBean(RouterFunction.class);
                            assertThat(context).hasSingleBean(GoogleClientRegistrations.class);
                        });
    }

    @Test
    void dev_oidc_등록은_브라우저와_백엔드의_주소를_갈라_가리킨다() {
        runner.withPropertyValues(
                        "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                        "bokrilog.auth.public-base-url=https://bokrilog.test")
                .run(
                        context -> {
                            GoogleClientRegistrations registrations =
                                    context.getBean(GoogleClientRegistrations.class);
                            assertThat(registrations.isConfigured()).isTrue();

                            ClientRegistration registration =
                                    registrations.findByRegistrationId(
                                            AuthSecurityConfig.GOOGLE_REGISTRATION_ID);
                            assertThat(registration).isNotNull();

                            // 인가 요청은 브라우저가 보낸다. 공개 origin이어야 사용자가 도달한다.
                            assertThat(registration.getProviderDetails().getAuthorizationUri())
                                    .isEqualTo(
                                            "https://bokrilog.test" + DevOidcStubConfig.AUTHORIZE_PATH);
                            // code 교환과 JWKS 조회는 백엔드가 자기 서블릿 포트로 보낸다. 공개
                            // origin은 compose 네트워크 안에서 해석되지 않는다.
                            assertThat(registration.getProviderDetails().getTokenUri())
                                    .isEqualTo("http://127.0.0.1:8080" + DevOidcStubConfig.TOKEN_PATH);
                            assertThat(registration.getProviderDetails().getJwkSetUri())
                                    .isEqualTo("http://127.0.0.1:8080" + DevOidcStubConfig.JWKS_PATH);
                            // `iss`는 스텁이 주장하는 값과 한 쌍이다. 갈라지면 검증이 실패한다.
                            assertThat(registration.getProviderDetails().getIssuerUri())
                                    .isEqualTo(context.getBean(DevOidcStub.class).issuer());
                            // redirect URI는 공개 origin의 콜백 경로다. 요청 헤더에서 유도하지 않는다.
                            assertThat(registration.getRedirectUri())
                                    .isEqualTo(
                                            "https://bokrilog.test"
                                                    + AuthSecurityConfig.CALLBACK_BASE_URI
                                                    + "/"
                                                    + AuthSecurityConfig.GOOGLE_REGISTRATION_ID);
                            // 스텁이라고 계약을 무르지 않는다. 동의 범위와 PKCE는 운영과 같다.
                            assertThat(registration.getScopes()).containsExactly("openid");
                            assertThat(registration.getClientSettings().isRequireProofKey()).isTrue();
                            // 제공자 액세스 토큰을 쓸 경로가 없다. UserInfo 왕복도 하지 않는다.
                            assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUri())
                                    .isNull();
                        });
    }

    @Test
    void 내부_base_URL은_구성으로_바꿀_수_있다() {
        runner.withPropertyValues(
                        "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                        "bokrilog.auth.public-base-url=https://bokrilog.test",
                        "bokrilog.auth.dev-oidc.internal-base-url=http://backend:9090/")
                .run(
                        context -> {
                            ClientRegistration registration =
                                    context.getBean(GoogleClientRegistrations.class)
                                            .findByRegistrationId(
                                                    AuthSecurityConfig.GOOGLE_REGISTRATION_ID);
                            assertThat(registration).isNotNull();
                            // 끝의 `/`는 정규화가 지운다. `//api/...`는 다른 URI다.
                            assertThat(registration.getProviderDetails().getTokenUri())
                                    .isEqualTo("http://backend:9090" + DevOidcStubConfig.TOKEN_PATH);
                        });
    }

    @Test
    void 실제_client_id와_함께_활성이면_기동을_거부한다() {
        runner.withPropertyValues(
                        "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                        "bokrilog.auth.public-base-url=https://bokrilog.test",
                        // 값은 실행 시점에 만든다. 자격정보 모양 리터럴을 시험 fixture에도 두지 않는다.
                        "bokrilog.auth.google.client-id=" + UUID.randomUUID())
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            String message = refusalMessage(context.getStartupFailure());
                            assertThat(message).contains("실제 제공자 등록과 함께 활성화할 수 없다");
                            // 어느 환경 변수가 문제인지 적지 않는다. 기동 실패 로그도 로그다.
                            assertThat(message).doesNotContain("BOKRILOG_");
                            assertThat(message).doesNotContain("client-id");
                        });
    }

    @Test
    void 실제_client_secret과_함께_활성이면_기동을_거부한다() {
        runner.withPropertyValues(
                        "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                        "bokrilog.auth.public-base-url=https://bokrilog.test",
                        "bokrilog.auth.google.client-secret=" + UUID.randomUUID())
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(refusalMessage(context.getStartupFailure()))
                                    .contains("실제 제공자 등록과 함께 활성화할 수 없다");
                        });
    }

    /**
     * 기동 거부의 실제 메시지.
     *
     * <p>컨테이너가 우리 예외를 {@code BeanCreationException}으로 감싸므로 최상위 메시지에는 빈
     * 이름만 남는다. 단정해야 하는 것은 우리가 쓴 문장이다.
     */
    private static String refusalMessage(@Nullable Throwable failure) {
        assertThat(failure).isNotNull();
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
        assertThat(cause).isInstanceOf(IllegalStateException.class);
        return String.valueOf(cause.getMessage());
    }
}
