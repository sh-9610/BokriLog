package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.AuthSecurityConfig.AuthProperties;
import com.bokrilog.AuthSecurityConfig.ProviderClientRegistrations;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;

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
 * <p>{@code /api/dev-oidc/**}의 노출 여부를 빈으로 단정한다. 모든 경로가 {@link RouterFunction}
 * <b>하나</b>에 실려 있으므로 그 빈의 부재가 곧 경로의 부재다. 컨트롤러 애노테이션으로 흩어 두면
 * "노출되지 않는다"를 확인하려면 서블릿 컨테이너를 띄워야 한다.
 *
 * <p>Story 1.6이 제공자를 둘로, Story 1.7이 셋으로 늘렸다. 그래서 이 파일은 두 가지를 더 본다 —
 * 스텁 빈이 제공자 수만큼 있고 라우터 빈은 여전히 하나이며, fail-closed 판정이 <b>제공자 단위로
 * 독립</b>한다는 것이다. 뒤의 것이 미묘한 자리다: 판정을 한 번에 접으면 한 제공자의 자격정보가
 * 없는 환경에서 나머지 제공자의 로그인까지 죽는다.
 */
class DevOidcStubProfileTest {

    /** 이 애플리케이션이 이름을 아는 제공자. 스텁·등록 단정이 같은 목록을 돈다. */
    private static final List<String> PROVIDERS =
            List.of(
                    AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                    AuthSecurityConfig.NAVER_REGISTRATION_ID,
                    AuthSecurityConfig.KAKAO_REGISTRATION_ID);

    /**
     * 어느 제공자로도 등록되지 않은 이름.
     *
     * <p>실제 제공자 이름을 쓰지 않는다. Story 1.6이 이 탐침을 {@code kakao}로 두었고 Story 1.7이
     * 카카오를 아는 제공자로 만들면서 그 단정은 통과하되 <b>뜻을 잃는</b> 상태가 됐다. 브랜드가 아닌
     * 값이면 다음 제공자가 들어와도 같은 일이 반복되지 않는다.
     */
    private static final String UNREGISTERED_PROVIDER = "unregistered-provider";

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
                    assertThat(context).doesNotHaveBean(ProviderClientRegistrations.class);
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
        assertThat(
                        ProviderClientRegistrations.from(new AuthProperties(null, null, null, null))
                                .isConfigured())
                .isFalse();
        AuthProperties.Provider blank =
                new AuthProperties.Provider("", "", null, null, null, null);
        assertThat(
                        ProviderClientRegistrations.from(
                                        new AuthProperties(
                                                "https://bokrilog.test", blank, blank, blank))
                                .isConfigured())
                .isFalse();
    }

    @Test
    void 한_제공자만_구성되면_그_제공자만_살아_있다() {
        /*
         * fail-closed가 제공자 단위로 독립한다. Story 1.6 전의 `from()`은 세 값을 한 번에 AND로
         * 접었고, 그 모양을 제공자 여럿에 그대로 두면 한 자격정보의 부재가 나머지 로그인까지
         * 죽인다 — 카카오를 등록하지 않은 개발 환경에서 Google 로그인이 사라지는 상태다.
         *
         * 모든 방향을 본다. 한 방향만 단정하면 판정이 어느 한쪽으로 치우친 구현이 통과한다.
         */
        for (String configured : PROVIDERS) {
            ProviderClientRegistrations registrations =
                    ProviderClientRegistrations.from(propertiesFor(configured));

            assertThat(registrations.isConfigured()).isTrue();
            assertThat(registrations.findByRegistrationId(configured)).isNotNull();
            for (String other : PROVIDERS) {
                if (!other.equals(configured)) {
                    assertThat(registrations.findByRegistrationId(other)).isNull();
                }
            }
        }
    }

    @Test
    void 아는_제공자_이름은_구성_여부와_무관한_닫힌_집합이다() {
        /*
         * `AuthSessionController`가 404와 500을 가르는 판정이다. 구성된 등록을 술어로 쓰면 두 답이
         * 모두 404가 되어(등록이 있으면 필터가 302로 끝내므로 컨트롤러에는 언제나 없는 이름이
         * 도달한다) 자격정보 없는 스택의 fail-closed 500이 사라진다.
         */
        for (String provider : PROVIDERS) {
            assertThat(ProviderClientRegistrations.isSupportedProvider(provider)).isTrue();
        }
        // 등록되지 않은 이름의 경로는 존재하지 않는다.
        assertThat(ProviderClientRegistrations.isSupportedProvider(UNREGISTERED_PROVIDER)).isFalse();
        assertThat(ProviderClientRegistrations.isSupportedProvider("")).isFalse();
        assertThat(ProviderClientRegistrations.isSupportedProvider("GOOGLE")).isFalse();
        // 순서가 고정이다. 연결 상태 조회가 이 순서를 그대로 응답에 싣는다.
        assertThat(ProviderClientRegistrations.supportedProviders())
                .containsExactlyElementsOf(PROVIDERS);
    }

    @Test
    void 같은_등록_id가_두_번_적힌_목록은_기동에서_거부된다() {
        /*
         * 이 목록이 `Set`이던 동안에는 중복이 조용히 하나로 접혔다. 순서가 필요해 `List`로 바꾸면서
         * 그 암묵적 guard가 사라졌으므로 명시적으로 둔다 — 중복이 남으면 연결 상태 조회가 같은
         * 제공자를 두 번 답하고, "아는 제공자 집합"이 몇 개인지 읽는 사람이 세어야 한다.
         */
        assertThatThrownBy(
                        () ->
                                ProviderClientRegistrations.rejectDuplicateRegistrationIds(
                                        List.of(
                                                AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                                                AuthSecurityConfig.NAVER_REGISTRATION_ID,
                                                AuthSecurityConfig.GOOGLE_REGISTRATION_ID)))
                .isInstanceOf(IllegalStateException.class)
                // 메시지에 어느 이름인지 적지 않는다. 답은 어느 쪽이든 "이름을 서로 다르게" 하나다.
                .hasMessageNotContaining(AuthSecurityConfig.GOOGLE_REGISTRATION_ID);

        // 서로 다른 목록은 그대로 돌려준다. 실제 목록이 그 경로를 지나 만들어진다.
        assertThat(ProviderClientRegistrations.rejectDuplicateRegistrationIds(PROVIDERS))
                .isEqualTo(PROVIDERS);
    }

    @Test
    void 공개_base_URL은_origin_하나만_담은_절대_URL이어야_한다() {
        /*
         * 이 값은 두 곳에서 쓰인다 — redirect URI 조립과 `SameOriginFilter`의 `Origin` 비교.
         * 경로가 붙은 값은 앞에서 등록값과 다른 URI를 만들고, 뒤에서는 브라우저가 보내는
         * origin과 영구히 어긋나 모든 상태 변경 요청을 403으로 만든다. 두 번째는 조용하다 —
         * 아무 오류도 나지 않고 그냥 전부 거절된다. 스킴 접두사만 보면 그 값이 통과한다.
         */
        assertThat(ProviderClientRegistrations.normalizedBaseUrl("https://bokrilog.test"))
                .isEqualTo("https://bokrilog.test");
        // 끝의 `/`는 정규화가 지운다. 뒤에 경로를 붙여 쓰므로 `//api/...`가 되면 다른 URI다.
        assertThat(ProviderClientRegistrations.normalizedBaseUrl("http://127.0.0.1:18080/"))
                .isEqualTo("http://127.0.0.1:18080");

        assertThat(ProviderClientRegistrations.normalizedBaseUrl("https://bokrilog.test/app")).isNull();
        assertThat(ProviderClientRegistrations.normalizedBaseUrl("https://bokrilog.test?next=/x"))
                .isNull();
        assertThat(ProviderClientRegistrations.normalizedBaseUrl("https://bokrilog.test#f")).isNull();
        // userinfo가 있으면 origin이 아니다.
        assertThat(ProviderClientRegistrations.normalizedBaseUrl("https://someone@bokrilog.test"))
                .isNull();
        // host가 없다. 비교할 origin도, 도달할 주소도 없다.
        assertThat(ProviderClientRegistrations.normalizedBaseUrl("http://")).isNull();
        // 스킴이 없거나 http(s)가 아니다.
        assertThat(ProviderClientRegistrations.normalizedBaseUrl("/api")).isNull();
        assertThat(ProviderClientRegistrations.normalizedBaseUrl("bokrilog.test")).isNull();
        assertThat(ProviderClientRegistrations.normalizedBaseUrl("ftp://bokrilog.test")).isNull();
        // URI로 읽히지 않는 값은 예외가 아니라 빈 값이다. 구성이 잘못된 스택은 기동 실패가
        // 아니라 fail-closed로 남는 편이 계약에 맞다.
        assertThat(ProviderClientRegistrations.normalizedBaseUrl("http://bokri log.test")).isNull();
    }

    @Test
    void 경로가_붙은_공개_base_URL로는_어느_제공자_등록도_만들어지지_않는다() {
        /*
         * `publicBaseUrl`만 세 제공자가 공유하는 값이다. 그것이 비거나 origin이 아니면 redirect URI를
         * 조립할 수 없으므로 어느 제공자도 만들어지지 않는 것이 맞다 — 그 하나가 제공자별 독립의
         * 예외다.
         */
        ProviderClientRegistrations registrations =
                ProviderClientRegistrations.from(
                        new AuthProperties(
                                "https://bokrilog.test/app",
                                credentials(),
                                credentials(),
                                credentials()));

        assertThat(registrations.isConfigured()).isFalse();
        for (String provider : PROVIDERS) {
            assertThat(registrations.findByRegistrationId(provider)).isNull();
        }
    }

    @Test
    void dev_oidc_프로필은_제공자마다_스텁을_두고_라우터는_하나로_유지한다() {
        runner.withPropertyValues(
                        "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                        "bokrilog.auth.public-base-url=https://bokrilog.test")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            /*
                             * 스텁은 제공자 수만큼이다. issuer·client·서명 키가 인스턴스 단위이므로
                             * 인스턴스가 하나면 제공자들이 같은 키로 서명한 토큰을 서로 검증한다.
                             */
                            assertThat(context.getBeansOfType(DevOidcStub.class))
                                    .hasSize(PROVIDERS.size());
                            /*
                             * 라우터는 하나다. 제공자마다 라우터를 만들면 "기본 프로필에서 경로가
                             * 노출되지 않는다"는 단정이 개수를 세는 형태로 바뀌고, 그때 하나가
                             * 남아 있는 상태가 통과한다.
                             */
                            assertThat(context).hasSingleBean(RouterFunction.class);
                            assertThat(context).hasSingleBean(ProviderClientRegistrations.class);
                        });
    }

    @Test
    void dev_oidc_라우터는_제공자마다_경로_넷을_모두_받는다() {
        /*
         * 라우터가 하나라는 사실만 단정하면 그 하나가 어느 제공자의 경로를 들고 있는지는 아무도
         * 보지 않는다. 스텁 표면은 둘이고(개발·CI 스택의 이 라우터, 백엔드 시험의 루프백 서버)
         * 시험이 지나는 것은 뒤의 하나뿐이었다 — 그래서 이 라우터의 네이버 경로가 비어 있어도
         * `build`가 통과했고, 결함은 compose 검증까지 가서야 드러났다. 실제로 그 검증마저
         * 조용히 건너뛴 적이 있어 여기까지 왔다.
         *
         * 서블릿 컨테이너를 띄우지 않고 라우팅만 본다. `ServerRequest.create`가 요청 경로를
         * 스스로 파싱하므로 `RouterFunction#route`를 직접 물어보는 것으로 충분하다.
         */
        runner.withPropertyValues(
                        "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                        "bokrilog.auth.public-base-url=https://bokrilog.test")
                .run(
                        context -> {
                            RouterFunction<?> routes = context.getBean(RouterFunction.class);

                            for (String provider : PROVIDERS) {
                                assertThat(routes(routes, "GET", DevOidcStubConfig.discoveryPath(provider)))
                                        .as("%s discovery", provider)
                                        .isTrue();
                                assertThat(routes(routes, "GET", DevOidcStubConfig.jwksPath(provider)))
                                        .as("%s JWKS", provider)
                                        .isTrue();
                                assertThat(routes(routes, "GET", DevOidcStubConfig.authorizePath(provider)))
                                        .as("%s 인가", provider)
                                        .isTrue();
                                assertThat(routes(routes, "POST", DevOidcStubConfig.tokenPath(provider)))
                                        .as("%s 토큰", provider)
                                        .isTrue();
                                // 토큰 교환은 POST 하나다. GET으로도 열려 있으면 code가 URL에 실린다.
                                assertThat(routes(routes, "GET", DevOidcStubConfig.tokenPath(provider)))
                                        .as("%s 토큰 GET", provider)
                                        .isFalse();
                            }

                            // 이름을 모르는 제공자의 경로는 없다.
                            assertThat(
                                            routes(
                                                    routes,
                                                    "GET",
                                                    DevOidcStubConfig.jwksPath(UNREGISTERED_PROVIDER)))
                                    .isFalse();
                            assertThat(routes(routes, "GET", DevOidcStubConfig.BASE_PATH + "/jwks"))
                                    .isFalse();
                        });
    }

    /** 그 라우터가 이 메서드·경로를 받는지. 핸들러를 실행하지는 않는다. */
    private static boolean routes(RouterFunction<?> routes, String method, String path) {
        return routes.route(
                        ServerRequest.create(
                                new MockHttpServletRequest(method, path),
                                List.<HttpMessageConverter<?>>of()))
                .isPresent();
    }

    @Test
    void dev_oidc_등록은_브라우저와_백엔드의_주소를_갈라_가리킨다() {
        runner.withPropertyValues(
                        "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                        "bokrilog.auth.public-base-url=https://bokrilog.test")
                .run(
                        context -> {
                            ProviderClientRegistrations registrations =
                                    context.getBean(ProviderClientRegistrations.class);
                            assertThat(registrations.isConfigured()).isTrue();

                            for (String provider : PROVIDERS) {
                                ClientRegistration registration =
                                        registrations.findByRegistrationId(provider);
                                assertThat(registration).isNotNull();

                                // 인가 요청은 브라우저가 보낸다. 공개 origin이어야 사용자가 도달한다.
                                assertThat(registration.getProviderDetails().getAuthorizationUri())
                                        .isEqualTo(
                                                "https://bokrilog.test"
                                                        + DevOidcStubConfig.authorizePath(provider));
                                // code 교환과 JWKS 조회는 백엔드가 자기 서블릿 포트로 보낸다. 공개
                                // origin은 compose 네트워크 안에서 해석되지 않는다.
                                assertThat(registration.getProviderDetails().getTokenUri())
                                        .isEqualTo(
                                                "http://127.0.0.1:8080"
                                                        + DevOidcStubConfig.tokenPath(provider));
                                assertThat(registration.getProviderDetails().getJwkSetUri())
                                        .isEqualTo(
                                                "http://127.0.0.1:8080"
                                                        + DevOidcStubConfig.jwksPath(provider));
                                // `iss`는 그 제공자 스텁이 주장하는 값과 한 쌍이다. 갈라지면 검증이
                                // 실패한다. 두 값이 서로 다르다는 것도 함께 본다.
                                assertThat(registration.getProviderDetails().getIssuerUri())
                                        .isEqualTo(
                                                "https://bokrilog.test"
                                                        + DevOidcStubConfig.basePath(provider));
                                // redirect URI는 공개 origin의 콜백 경로다. 요청 헤더에서 유도하지
                                // 않는다.
                                assertThat(registration.getRedirectUri())
                                        .isEqualTo(
                                                "https://bokrilog.test"
                                                        + AuthSecurityConfig.CALLBACK_BASE_URI
                                                        + "/"
                                                        + provider);
                                // 스텁이라고 계약을 무르지 않는다. 동의 범위와 PKCE는 운영과 같다.
                                assertThat(registration.getScopes()).containsExactly("openid");
                                assertThat(registration.getClientSettings().isRequireProofKey())
                                        .isTrue();
                                // 제공자 액세스 토큰을 쓸 경로가 없다. UserInfo 왕복도 하지 않는다.
                                assertThat(
                                                registration
                                                        .getProviderDetails()
                                                        .getUserInfoEndpoint()
                                                        .getUri())
                                        .isNull();
                            }
                        });
    }

    @Test
    void 손으로_조립한_등록은_client_secret_post와_sub를_쓴다() {
        /*
         * 네이버 discovery의 `token_endpoint_auth_methods_supported`는 `[client_secret_post, none]`,
         * 카카오는 `[client_secret_post]` 하나뿐이다. 그래서 Spring Security의
         * 기본값(`client_secret_basic`)으로는 두 제공자 어느 쪽도 토큰 교환이 성립하지 않는다.
         * 그리고 `CommonOAuth2Provider`에 NAVER·KAKAO가 없어 `userNameAttributeName`의 기본값도
         * 없다 — 비면 `OidcUser`의 주체가 정해지지 않는다.
         *
         * Google과 대조해 둔다. 값이 같아지는 회귀는 한쪽만 보면 드러나지 않는다.
         */
        runner.withPropertyValues(
                        "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                        "bokrilog.auth.public-base-url=https://bokrilog.test")
                .run(
                        context -> {
                            ProviderClientRegistrations registrations =
                                    context.getBean(ProviderClientRegistrations.class);

                            for (String provider :
                                    List.of(
                                            AuthSecurityConfig.NAVER_REGISTRATION_ID,
                                            AuthSecurityConfig.KAKAO_REGISTRATION_ID)) {
                                ClientRegistration assembled =
                                        registrations.findByRegistrationId(provider);
                                assertThat(assembled).as("%s 등록", provider).isNotNull();
                                assertThat(assembled.getClientAuthenticationMethod())
                                        .as("%s client 인증", provider)
                                        .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
                                assertThat(
                                                assembled
                                                        .getProviderDetails()
                                                        .getUserInfoEndpoint()
                                                        .getUserNameAttributeName())
                                        .as("%s 주체 claim", provider)
                                        .isEqualTo("sub");
                            }

                            ClientRegistration google =
                                    registrations.findByRegistrationId(
                                            AuthSecurityConfig.GOOGLE_REGISTRATION_ID);
                            assertThat(google).isNotNull();
                            assertThat(google.getClientAuthenticationMethod())
                                    .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
                        });
    }

    @Test
    void 네이버_기본_엔드포인트는_공식_discovery의_정적_주소다() {
        /*
         * 재정의가 없는 구성의 값이다. 기동 시점에 discovery 왕복을 하지 않으므로 이 네 값은 코드가
         * 들고 있고, 그 사실을 시험이 고정한다. OIDC 경로는 `/oauth2/*`이며 레거시 OAuth 2.0 API의
         * `/oauth2.0/*`과 다르다 — 두 계열을 섞으면 ID Token이 오지 않는다.
         */
        ClientRegistration naver =
                ProviderClientRegistrations.from(
                                propertiesFor(AuthSecurityConfig.NAVER_REGISTRATION_ID))
                        .findByRegistrationId(AuthSecurityConfig.NAVER_REGISTRATION_ID);

        assertThat(naver).isNotNull();
        assertThat(naver.getProviderDetails().getIssuerUri()).isEqualTo("https://nid.naver.com");
        assertThat(naver.getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://nid.naver.com/oauth2/authorize");
        assertThat(naver.getProviderDetails().getTokenUri())
                .isEqualTo("https://nid.naver.com/oauth2/token");
        assertThat(naver.getProviderDetails().getJwkSetUri())
                .isEqualTo("https://nid.naver.com/oauth2/jwks");
        // 동의 범위는 `openid` 하나다. discovery가 `profile`도 광고하지만 요청하지 않는다.
        assertThat(naver.getScopes()).containsExactly("openid");
        assertThat(naver.getProviderDetails().getUserInfoEndpoint().getUri()).isNull();
    }

    @Test
    void 카카오_기본_엔드포인트는_공식_discovery의_정적_주소다() {
        /*
         * 네이버와 같은 이유로 이 네 값이 코드에 있다. 다만 JWKS 경로의 모양이 서로 다르다 —
         * 카카오는 issuer 바로 아래의 `/.well-known/jwks.json`이고 네이버는 `/oauth2/jwks`다.
         * 한 제공자의 모양을 다른 제공자에 옮겨 적으면 서명 검증이 통째로 실패하므로, 두 시험이
         * 각자 자기 주소를 전문으로 고정한다.
         */
        ClientRegistration kakao =
                ProviderClientRegistrations.from(
                                propertiesFor(AuthSecurityConfig.KAKAO_REGISTRATION_ID))
                        .findByRegistrationId(AuthSecurityConfig.KAKAO_REGISTRATION_ID);

        assertThat(kakao).isNotNull();
        assertThat(kakao.getProviderDetails().getIssuerUri()).isEqualTo("https://kauth.kakao.com");
        assertThat(kakao.getProviderDetails().getAuthorizationUri())
                .isEqualTo("https://kauth.kakao.com/oauth/authorize");
        assertThat(kakao.getProviderDetails().getTokenUri())
                .isEqualTo("https://kauth.kakao.com/oauth/token");
        assertThat(kakao.getProviderDetails().getJwkSetUri())
                .isEqualTo("https://kauth.kakao.com/.well-known/jwks.json");
        // 동의 범위는 `openid` 하나다. 이메일·프로필 동의를 요청하지 않는다.
        assertThat(kakao.getScopes()).containsExactly("openid");
        assertThat(kakao.getProviderDetails().getUserInfoEndpoint().getUri()).isNull();
        // PKCE도 운영과 같다. 카카오 discovery가 광고하는 방법은 S256 하나다.
        assertThat(kakao.getClientSettings().isRequireProofKey()).isTrue();
    }

    @Test
    void 세_제공자의_기본_issuer가_서로_다르다() {
        /*
         * 조회 키가 `(issuer, subject)`라 issuer가 겹치면 제공자 병합이 열린다. 기본값끼리 겹치는
         * 상태는 `rejectSharedIssuer`가 기동에서 거부하지만, 그 거부가 도는 것과 "기본값이 애초에
         * 서로 다르다"는 것은 다른 사실이다.
         */
        ProviderClientRegistrations registrations =
                ProviderClientRegistrations.from(
                        new AuthProperties(
                                "https://bokrilog.test",
                                credentials(),
                                credentials(),
                                credentials()));

        assertThat(
                        PROVIDERS.stream()
                                .map(registrations::findByRegistrationId)
                                .map(Objects::requireNonNull)
                                .map(registration -> registration.getProviderDetails().getIssuerUri())
                                .distinct()
                                .count())
                .isEqualTo(PROVIDERS.size());
    }

    @Test
    void 내부_base_URL은_구성으로_바꿀_수_있다() {
        runner.withPropertyValues(
                        "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                        "bokrilog.auth.public-base-url=https://bokrilog.test",
                        "bokrilog.auth.dev-oidc.internal-base-url=http://backend:9090/")
                .run(
                        context -> {
                            ProviderClientRegistrations registrations =
                                    context.getBean(ProviderClientRegistrations.class);
                            for (String provider : PROVIDERS) {
                                ClientRegistration registration =
                                        registrations.findByRegistrationId(provider);
                                assertThat(registration).isNotNull();
                                // 끝의 `/`는 정규화가 지운다. `//api/...`는 다른 URI다.
                                assertThat(registration.getProviderDetails().getTokenUri())
                                        .isEqualTo(
                                                "http://backend:9090"
                                                        + DevOidcStubConfig.tokenPath(provider));
                            }
                        });
    }

    @Test
    void 실제_client_id와_함께_활성이면_기동을_거부한다() {
        /*
         * 제공자마다 확인한다. 한쪽만 보면 나머지 제공자의 실제 자격정보가 스텁과 함께 조용히 살아
         * 있고, 그때 어느 쪽이 로그인을 처리했는지 응답만으로 알 수 없다.
         */
        for (String provider : PROVIDERS) {
            runner.withPropertyValues(
                            "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                            "bokrilog.auth.public-base-url=https://bokrilog.test",
                            // 값은 실행 시점에 만든다. 자격정보 모양 리터럴을 시험 fixture에도
                            // 두지 않는다.
                            "bokrilog.auth." + provider + ".client-id=" + UUID.randomUUID())
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                String message = refusalMessage(context.getStartupFailure());
                                assertThat(message).contains("실제 제공자 등록과 함께 활성화할 수 없다");
                                // 어느 환경 변수가 문제인지 적지 않는다. 기동 실패 로그도 로그다.
                                assertThat(message).doesNotContain("BOKRILOG_");
                                assertThat(message).doesNotContain("client-id");
                                // 어느 제공자인지도 적지 않는다. 같은 이유이고 답은 하나뿐이다.
                                assertThat(message).doesNotContain(provider);
                            });
        }
    }

    @Test
    void 실제_client_secret과_함께_활성이면_기동을_거부한다() {
        for (String provider : PROVIDERS) {
            runner.withPropertyValues(
                            "spring.profiles.active=" + DevOidcStubConfig.PROFILE,
                            "bokrilog.auth.public-base-url=https://bokrilog.test",
                            "bokrilog.auth." + provider + ".client-secret=" + UUID.randomUUID())
                    .run(
                            context -> {
                                assertThat(context).hasFailed();
                                assertThat(refusalMessage(context.getStartupFailure()))
                                        .contains("실제 제공자 등록과 함께 활성화할 수 없다");
                            });
        }
    }

    /**
     * 그 제공자만 자격정보를 가진 구성. 나머지는 빈 값이다.
     *
     * <p>제공자마다 성분을 골라 넣는다. 이분 분기({@code google ? … : …})로 두면 제공자가 셋이 되는
     * 순간 "google이 아닌 전부"가 한 자리로 접혀 셋째 제공자의 속성이 조용히 틀린 자리에 주입된다.
     */
    private static AuthProperties propertiesFor(String provider) {
        return new AuthProperties(
                "https://bokrilog.test",
                AuthSecurityConfig.GOOGLE_REGISTRATION_ID.equals(provider) ? credentials() : null,
                AuthSecurityConfig.NAVER_REGISTRATION_ID.equals(provider) ? credentials() : null,
                AuthSecurityConfig.KAKAO_REGISTRATION_ID.equals(provider) ? credentials() : null);
    }

    /** 값은 실행 시점에 만든다. 자격정보 모양 리터럴을 시험 fixture에도 두지 않는다. */
    private static AuthProperties.Provider credentials() {
        return new AuthProperties.Provider(
                "client-" + UUID.randomUUID(), UUID.randomUUID().toString(), null, null, null, null);
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
