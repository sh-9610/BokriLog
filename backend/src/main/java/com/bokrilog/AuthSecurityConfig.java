package com.bokrilog;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 인증 표면과 세션 정책.
 *
 * <p>루트 패키지가 소유한다. 허용 모듈은 여덟 개로 열거돼 있고 Structural Seed가 이 능력을
 * {@code identity}에 귀속하지만, {@code identity}는 HTTP 표면을 가질 수 없다
 * ({@code identity/package-info.java}, {@code ModuleBoundaryTest}). 두 제약을 함께 만족시키는
 * 배치는 하나뿐이다 — 판정·매핑·조회는 {@code identity} 공개 API가, HTTP 표면과 세션 정책은
 * 루트 패키지가 소유한다. 루트 패키지는 Modulith 모듈이 아니므로 이 배치는 모듈 간 의존
 * 간선을 새로 만들지 않는다. {@code SchemaValidationConfig}가 기동 정책을 루트에 둔 것과 같은
 * 이유다.
 *
 * <p>검증을 손으로 만들지 않는다. Authorization Code + PKCE(S256), 일회성 {@code state}·
 * {@code nonce}, ID Token의 JWS 서명·허용 알고리즘·JWKS와 {@code iss}·{@code aud}·{@code azp}·
 * {@code exp}·{@code nonce} 검증, 세션 고정 방어는 모두 Spring Security OAuth2 Client의 계약이다.
 * 이 클래스가 하는 일은 그 계약을 이 저장소의 경계에 맞추는 배선뿐이다.
 *
 * <p>보안 응답 헤더를 만들지 않는다. CSP를 비롯한 보안 헤더는 {@code deploy/nginx/bokrilog.conf}가
 * 유일한 정의이며, Spring Security의 기본 헤더 writer를 끄지 않으면 진입점과 애플리케이션이 같은
 * 헤더를 각각 실어 값이 두 번 나간다. 그때 헤더 값을 전문 비교하는
 * {@code verify-same-origin.sh}가 먼저 깨진다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다. 같은 이미지의 일회성 migration 역할은
 * {@code spring.main.web-application-type: none}으로 뜨고(AD-12), 그 프로세스에는 {@code HttpSecurity}
 * 자체가 없다. 조건 없이 두면 마이그레이션이 컨텍스트 생성 실패로 끝나 스키마를 바꾸는 유일한
 * 경로가 막힌다. 같은 조건이 {@code AuthFlowHandlers}·{@code AuthSessionService}·
 * {@code AuthSessionController}에도 붙어 인증 표면 전체가 API 프로세스의 것으로 남는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties(AuthSecurityConfig.AuthProperties.class)
class AuthSecurityConfig {

    /** 인증 시작 경로의 접두사. 뒤에 제공자 등록 id가 붙는다({@code /api/auth/authorize/google}). */
    static final String AUTHORIZATION_BASE_URI = "/api/auth/authorize";

    /** 콜백 경로. {@code /api}만 프록시하는 Nginx 블록이 그대로 받는다. */
    static final String CALLBACK_BASE_URI = "/api/auth/callback";

    /** 이 스토리가 지원하는 유일한 제공자. 네이버·카카오는 Story 1.6·1.7이 더한다. */
    static final String GOOGLE_REGISTRATION_ID = "google";

    /** 세션 확인 경로. 로그인 상태 화면이 서버 세션으로 상태를 확인하는 유일한 경로다. */
    static final String SESSION_PATH = "/api/auth/session";

    /** 인증이 필요 없는 서버 경계 확인 경로. Story 1.2의 계약이며 이 스토리가 바꾸지 않는다. */
    static final String STATUS_PATH = "/api/status";

    /**
     * 상태를 바꾸지 않는 메서드. RFC 9110의 safe method에 {@code OPTIONS}를 더한 집합이며
     * Spring Security의 기본 CSRF 제외 집합과 같다.
     */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** CSRF·{@code Origin} 검증이 걸리는 경로 접두사. */
    private static final String PROTECTED_PATH_PREFIX = "/api/auth/";

    /**
     * 인증 설정. 값은 환경이 주고 이 record는 이름만 정한다.
     *
     * <p>{@code publicBaseUrl}은 브라우저가 보는 origin이다. redirect URI를 요청 헤더에서 유도하지
     * 않기 위해 절대 URI를 환경에서 받는다({@code application.yml}의 근거 참조).
     *
     * <p>{@code Google}의 엔드포인트 네 값은 비어 있는 것이 정상이다. 비면 Spring Security의
     * {@code CommonOAuth2Provider.GOOGLE}이 정한 Google 정본 주소를 쓰고 기동 시점에 discovery
     * 왕복을 하지 않는다. 값을 주는 것은 재정의가 필요한 환경뿐이다 — 시험의 local OIDC 스텁과,
     * issuer 별칭을 쓰는 이후 제공자.
     */
    @ConfigurationProperties("bokrilog.auth")
    record AuthProperties(@Nullable String publicBaseUrl, Google google) {

        AuthProperties {
            /*
             * `bokrilog.auth.google`이 통째로 없는 구성에서도 배선이 성립해야 한다. 값이 없으면
             * 빈 값 여섯 개짜리 record로 바꾸고, 아래 `GoogleClientRegistrations`가 "구성되지 않음"
             * 으로 접는다.
             *
             * 정규화가 여기 하나뿐인 것이 요점이다. 그래서 `google()`은 null을 돌려주지 않고,
             * 소비하는 쪽에 null 검사가 남지 않는다 — 남으면 그 분기는 아무도 지나지 않는
             * 죽은 코드가 된다.
             */
            if (google == null) {
                google = new Google(null, null, null, null, null, null);
            }
        }

        record Google(
                @Nullable String clientId,
                @Nullable String clientSecret,
                @Nullable String issuerUri,
                @Nullable String authorizationUri,
                @Nullable String tokenUri,
                @Nullable String jwkSetUri) {}
    }

    /**
     * 이 애플리케이션이 아는 제공자 등록.
     *
     * <p>{@link ClientRegistrationRepository}를 직접 만드는 이유는 세 가지다.
     *
     * <ul>
     *   <li>자격정보가 없는 구성에서 기동이 실패하지 않아야 한다. Boot의 property 매핑은
     *       {@code client-id}가 비면 {@code ClientRegistration.build()}에서 예외를 던지고, 그 예외는
     *       컨텍스트 생성 실패라 개발·CI 스택의 백엔드가 healthy가 되지 못한다. 여기서는 값이
     *       비면 등록을 만들지 않고 "구성되지 않음" 상태로 남는다.
     *   <li>{@code issuer-uri}를 discovery 없이 실어야 한다. Boot의 {@code provider.<id>.issuer-uri}는
     *       기동 시점에 discovery 왕복을 하고, 그 왕복은 Google에 대한 네트워크 의존을 기동
     *       경로에 만든다. {@code CommonOAuth2Provider.GOOGLE}은 {@code issuerUri}까지 정적으로
     *       들고 있어 {@code iss} 검증이 네트워크 없이 성립한다.
     *   <li>PKCE를 명시한다. Spring Security 7의 기본값이 이미 {@code requireProofKey = true}지만,
     *       기본값이 바뀌면 조용히 꺼지는 자리라 계약으로 적는다.
     * </ul>
     */
    static final class GoogleClientRegistrations implements ClientRegistrationRepository {

        private final @Nullable ClientRegistration google;

        private GoogleClientRegistrations(@Nullable ClientRegistration google) {
            this.google = google;
        }

        static GoogleClientRegistrations from(AuthProperties properties) {
            AuthProperties.Google google = properties.google();
            String baseUrl = normalizedBaseUrl(properties.publicBaseUrl());
            if (!hasText(google.clientId()) || !hasText(google.clientSecret()) || baseUrl == null) {
                // fail-closed. 인증 시작은 아래 `AuthSessionController`가 공통 오류 계약으로 답한다.
                return new GoogleClientRegistrations(null);
            }

            ClientRegistration.Builder builder =
                    CommonOAuth2Provider.GOOGLE
                            .getBuilder(GOOGLE_REGISTRATION_ID)
                            .clientId(google.clientId())
                            .clientSecret(google.clientSecret())
                            // 절대 URI다. 요청 Host에서 조립하지 않으므로 Nginx가 포트를 잃는 것과
                            // 무관하고, "사전 등록된 정확한 redirect URI"와 같은 모양이다.
                            .redirectUri(baseUrl + CALLBACK_BASE_URI + "/" + GOOGLE_REGISTRATION_ID)
                            // 기본 동의 범위는 openid뿐이다. `CommonOAuth2Provider.GOOGLE`이 넣는
                            // profile·email을 이 한 줄이 대체한다(`scope`는 더하지 않고 바꾼다).
                            .scope(OidcScopes.OPENID)
                            // UserInfo를 부르지 않는다. Spring Security 7의 `shouldRetrieveUserInfo`는
                            // scope가 아니라 이 URI의 존재로 판단하므로, 비우지 않으면 openid만
                            // 요청했는데도 제공자의 UserInfo 왕복이 일어난다. 우리가 필요한 값은
                            // ID Token의 `iss`·`sub` 둘뿐이다.
                            .userInfoUri(null)
                            .clientSettings(
                                    ClientRegistration.ClientSettings.builder()
                                            .requireProofKey(true)
                                            .build());

            // 엔드포인트 재정의. 값이 없으면 Google 정본 주소가 그대로 남는다.
            if (hasText(google.issuerUri())) {
                builder.issuerUri(google.issuerUri());
            }
            if (hasText(google.authorizationUri())) {
                builder.authorizationUri(google.authorizationUri());
            }
            if (hasText(google.tokenUri())) {
                builder.tokenUri(google.tokenUri());
            }
            if (hasText(google.jwkSetUri())) {
                builder.jwkSetUri(google.jwkSetUri());
            }
            return new GoogleClientRegistrations(builder.build());
        }

        @Override
        public @Nullable ClientRegistration findByRegistrationId(String registrationId) {
            return GOOGLE_REGISTRATION_ID.equals(registrationId) ? this.google : null;
        }

        /**
         * 쓸 수 있는 제공자 등록이 있는가.
         *
         * <p>없으면 인증 필터를 배선하지 않는다. 필터를 두고 등록만 비우면 인증 시작이 필터 안의
         * 예외로 끝나고, 그 예외는 스택과 함께 로그에 실린다. 그것은 "내부 경로·스택을 로그에도
         * 남기지 않는다"와 정면으로 부딪힌다.
         */
        boolean isConfigured() {
            return this.google != null;
        }

        /**
         * origin 하나만 담은 절대 base URL로 정규화한다.
         *
         * <p>package-private이다. {@code DevOidcStubConfig}가 스텁 엔드포인트 URI를 같은 base에서
         * 조립하므로 정규화 규칙이 두 곳에 있으면 한쪽만 고쳐지는 날이 온다.
         *
         * <p>스킴 접두사만 보지 않는다. 이 값은 두 곳에서 쓰이는데, redirect URI 조립에서는 경로가
         * 붙은 값이 등록값과 다른 URI를 만들고, {@code SameOriginFilter}의 {@code Origin} 비교에서는
         * 브라우저가 보내는 origin(스킴 + 호스트 + 포트)과 영구히 어긋나 모든 상태 변경 요청이
         * 403이 된다. 두 번째가 조용하다 — 아무 오류도 나지 않고 그냥 전부 거절된다. 그래서
         * {@link URI}로 파싱해 host가 없거나 경로·쿼리·프래그먼트·userinfo가 붙은 값을 거부한다.
         *
         * @return origin만 담긴 절대 URL, 아니면 {@code null}
         */
        static @Nullable String normalizedBaseUrl(@Nullable String value) {
            if (!hasText(value)) {
                return null;
            }
            String trimmed = value.trim();
            // 뒤에 경로를 붙여 쓰므로 끝의 `/`를 남기지 않는다. `//api/...`는 등록값과 다른 URI다.
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            URI uri;
            try {
                uri = new URI(trimmed);
            } catch (URISyntaxException ex) {
                // URI로 읽히지 않는 값은 origin일 수 없다. 예외를 밖으로 내보내지 않는다 —
                // 구성이 잘못된 스택은 기동 실패가 아니라 fail-closed로 남는 편이 계약에 맞다.
                return null;
            }
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                // 상대 경로·스킴 없는 값·`javascript:` 같은 opaque URI가 여기서 걸린다.
                return null;
            }
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                // host가 없으면 비교할 origin이 없고, userinfo가 있으면 origin이 아니다.
                return null;
            }
            String path = uri.getRawPath();
            if ((path != null && !path.isEmpty()) || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            return trimmed;
        }

        private static boolean hasText(@Nullable String value) {
            return value != null && !value.isBlank();
        }
    }

    @Bean
    GoogleClientRegistrations googleClientRegistrations(AuthProperties properties) {
        return GoogleClientRegistrations.from(properties);
    }

    /**
     * 인증 시작 요청을 authorization request로 옮긴다.
     *
     * <p>기본 resolver는 모르는 등록 id에 {@code IllegalArgumentException}을 던지고, 그 예외를 받은
     * 필터가 스택과 함께 로그를 남긴 뒤 500을 보낸다. 여기서 그 경우를 빈 값으로 접어 요청이
     * 필터를 그대로 지나가게 하고, {@code AuthSessionController}가 공통 오류 계약으로 답한다.
     * 얻는 것은 두 가지다 — 로그에 스택이 남지 않고, 모르는 제공자와 구성되지 않은 제공자가 같은
     * 한 곳에서 같은 모양으로 답한다.
     */
    @Bean
    OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            GoogleClientRegistrations registrations) {
        DefaultOAuth2AuthorizationRequestResolver delegate =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION_BASE_URI);
        return new OAuth2AuthorizationRequestResolver() {

            @Override
            public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                try {
                    return delegate.resolve(request);
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }

            @Override
            public @Nullable OAuth2AuthorizationRequest resolve(
                    HttpServletRequest request, String clientRegistrationId) {
                try {
                    return delegate.resolve(request, clientRegistrationId);
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }
        };
    }

    /**
     * 제공자 액세스·리프레시 토큰을 버린다.
     *
     * <p>{@code oauth2Login}은 성공한 흐름의 {@code OAuth2AuthorizedClient}를 저장소에 넣으려 한다.
     * 기본 구현은 그것을 세션에 넣고, 세션은 Spring Session JDBC를 통해 PostgreSQL로 간다. 그러면
     * 저장하지 않기로 한 액세스·리프레시 토큰이 데이터베이스에 남는다. 이 구현은 아무것도 쓰지
     * 않고 아무것도 돌려주지 않아 토큰이 검증 직후 사라진다.
     *
     * <p>지금 이 애플리케이션에는 제공자 API를 호출하는 경로가 없다. 토큰을 보관할 이유가 생기면
     * 그 스토리의 SPEC이 목적·필드·암호화·만료·폐기 시점을 명시한다.
     */
    @Bean
    OAuth2AuthorizedClientRepository discardingAuthorizedClientRepository() {
        return new OAuth2AuthorizedClientRepository() {

            @Override
            public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(
                    String clientRegistrationId, Authentication principal, HttpServletRequest request) {
                return null;
            }

            @Override
            public void saveAuthorizedClient(
                    OAuth2AuthorizedClient authorizedClient,
                    Authentication principal,
                    HttpServletRequest request,
                    HttpServletResponse response) {
                // 버린다. 이 호출이 곧 "토큰을 저장하지 않는다"가 구현으로 성립하는 지점이다.
            }

            @Override
            public void removeAuthorizedClient(
                    String clientRegistrationId,
                    Authentication principal,
                    HttpServletRequest request,
                    HttpServletResponse response) {
                // 저장한 것이 없으므로 지울 것도 없다.
            }
        };
    }

    @Bean
    SecurityFilterChain authSecurityFilterChain(
            HttpSecurity http,
            GoogleClientRegistrations registrations,
            AuthProperties properties,
            AuthFlowHandlers handlers)
            throws Exception {

        http.authorizeHttpRequests(
                        requests ->
                                /*
                                 * 이 스토리는 어떤 경로도 인증으로 막지 않는다.
                                 *
                                 * 막을 사용자 소유 자원이 아직 없고, `anyRequest().authenticated()`를
                                 * 두면 Story 1.2가 고정한 오류 계약이 뒤집힌다 — `/api/nope`의 404와
                                 * `POST /api/status`의 405가 전부 401·302가 되고, 그 회귀는
                                 * `StatusApiTest`와 `verify-same-origin.sh`의 탐침 열한 개에서
                                 * 동시에 드러난다. 로그인 상태 경로의 보호는 서버가 소유한 자원이
                                 * 생기는 스토리가 그 자원과 함께 더한다.
                                 */
                                requests.anyRequest().permitAll())
                /*
                 * 보안 응답 헤더는 Nginx가 유일하게 정의한다. 여기서 켜 두면 같은 헤더가 두 번
                 * 나가고, `Cache-Control`도 Story 1.2가 관측한 응답에 새로 붙는다.
                 */
                .headers(headers -> headers.disable())
                /*
                 * 로그아웃은 Story 1.5의 범위다. 기본 배선은 `/logout` 필터를 등록해 이 스토리가
                 * 만들지 않은 HTTP 표면을 하나 늘린다.
                 */
                .logout(logout -> logout.disable())
                /*
                 * 저장된 요청으로 되돌리지 않는다. 복귀 목적지는 서버가 가진 닫힌 허용 목록이며
                 * (`AuthFlowHandlers`), 요청 캐시는 그 목록을 거치지 않는 두 번째 경로가 된다.
                 */
                .requestCache(cache -> cache.disable())
                /*
                 * 로그인 성공 시 세션 ID를 회전한다. Spring Session의 요청 wrapper가
                 * `changeSessionId()`를 지원하므로 회전이 PostgreSQL의 세션 행에도 반영된다.
                 */
                .sessionManagement(
                        session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                /*
                 * CSRF 토큰은 세션에 귀속한다(기본 `HttpSessionCsrfTokenRepository`). 쿠키
                 * 저장소를 쓰지 않는 것은 "브라우저에는 세션 쿠키 하나뿐"이라는 계약 때문이다.
                 *
                 * 보호 대상을 `/api/auth/` 아래의 상태 변경 요청으로 좁힌다. 전역으로 두면
                 * CsrfFilter가 서블릿보다 먼저 답해 `POST /api/status`가 405 대신 403이 되고
                 * Story 1.2·1.3이 고정한 탐침 응답이 함께 뒤집힌다.
                 */
                .csrf(csrf -> csrf.requireCsrfProtectionMatcher(protectedRequests()))
                /*
                 * 401·403을 공통 오류 계약으로 답한다. 기본 배선은 익명 요청의 접근 거부를
                 * 로그인 페이지 리다이렉트로 바꾸는데, 그러면 API 요청이 302 + HTML로 끝난다.
                 * `sendError`가 컨테이너의 ERROR 디스패치를 지나 `ApiErrorHandler`의 본문을 만든다.
                 */
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        response.sendError(HttpStatus.UNAUTHORIZED.value()))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        response.sendError(HttpStatus.FORBIDDEN.value())))
                // 동일 출처 `Origin` 검증. CSRF 토큰 검사보다 앞에 둔다.
                .addFilterBefore(
                        new SameOriginFilter(
                                GoogleClientRegistrations.normalizedBaseUrl(properties.publicBaseUrl())),
                        CsrfFilter.class)
                // 이미 인증된 세션의 로그인 시작 거절과 복귀 목적지 기록.
                // authorization request를 만드는 필터보다 앞이어야 한다.
                .addFilterBefore(handlers.authorizationStartFilter(), CsrfFilter.class);

        if (registrations.isConfigured()) {
            http.oauth2Login(
                    login ->
                            login
                                    /*
                                     * 생성되는 로그인 페이지를 쓰지 않는다. 인증 화면은 정적 SPA가
                                     * origin 루트에서 제공하며, 여기에 값을 주지 않으면 Spring이
                                     * `/login` HTML 표면을 하나 만든다.
                                     */
                                    .loginPage("/")
                                    .redirectionEndpoint(
                                            redirection -> redirection.baseUri(CALLBACK_BASE_URI + "/*"))
                                    .successHandler(handlers.successHandler())
                                    .failureHandler(handlers.failureHandler()));
            /*
             * authorization endpoint의 base URI는 위 `authorizationRequestResolver` 빈이 소유한다.
             * `authorizationEndpoint().baseUri(...)`는 resolver 빈이 있을 때 무시되므로, 두 곳에
             * 적어 한쪽만 바뀌는 자리를 만들지 않는다.
             */
        }

        return http.build();
    }

    /** CSRF 토큰과 {@code Origin}을 요구하는 요청. {@code /api/auth/} 아래의 상태 변경뿐이다. */
    private static RequestMatcher protectedRequests() {
        return AuthSecurityConfig::isProtectedRequest;
    }

    private static boolean isProtectedRequest(HttpServletRequest request) {
        // 컨텍스트 경로가 비어 있는 배치다(`server.servlet.context-path`를 두지 않는다).
        // 그래서 요청 URI가 그대로 애플리케이션 경로다.
        return !SAFE_METHODS.contains(request.getMethod())
                && request.getRequestURI().startsWith(PROTECTED_PATH_PREFIX);
    }

    /**
     * 상태 변경 요청의 {@code Origin}이 이 서비스의 origin인지 확인한다.
     *
     * <p>CSRF 토큰만으로는 토큰이 새는 경로가 하나 생기면 방어가 통째로 사라진다. 서버가 아는
     * origin과 요청이 주장하는 origin을 함께 보면 그 경로 하나로는 통과하지 못한다.
     *
     * <p>{@code Origin}이 없는 요청도 거절한다. 브라우저는 상태 변경 요청에 이 헤더를 싣고,
     * 싣지 않는 것은 브라우저가 아니다. 공개 base URL이 구성되지 않은 스택에서는 비교할 origin이
     * 없으므로 모두 거절한다 — 그것이 fail-closed다.
     */
    private static final class SameOriginFilter extends OncePerRequestFilter {

        private final @Nullable String expectedOrigin;

        private SameOriginFilter(@Nullable String expectedOrigin) {
            this.expectedOrigin = expectedOrigin;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            if (isProtectedRequest(request)) {
                String origin = request.getHeader("Origin");
                if (this.expectedOrigin == null || !this.expectedOrigin.equals(origin)) {
                    // 어느 쪽이 어긋났는지 응답에 남기지 않는다. 공통 오류 계약의 403 하나다.
                    response.sendError(HttpStatus.FORBIDDEN.value());
                    return;
                }
            }
            filterChain.doFilter(request, response);
        }
    }
}
