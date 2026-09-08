package com.bokrilog;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
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
@EnableConfigurationProperties({
    AuthSecurityConfig.AuthProperties.class,
    SessionGuard.SessionProperties.class
})
class AuthSecurityConfig {

    /** 인증 시작 경로의 접두사. 뒤에 제공자 등록 id가 붙는다({@code /api/auth/authorize/google}). */
    static final String AUTHORIZATION_BASE_URI = "/api/auth/authorize";

    /** 콜백 경로. {@code /api}만 프록시하는 Nginx 블록이 그대로 받는다. */
    static final String CALLBACK_BASE_URI = "/api/auth/callback";

    /** Google 제공자의 등록 id. */
    static final String GOOGLE_REGISTRATION_ID = "google";

    /** 네이버 제공자의 등록 id. 카카오는 Story 1.7이 더한다. */
    static final String NAVER_REGISTRATION_ID = "naver";

    /** 세션 확인 경로. 로그인 상태 화면이 서버 세션으로 상태를 확인하는 유일한 경로다. */
    static final String SESSION_PATH = "/api/auth/session";

    /**
     * 로그아웃 경로. 이 하나뿐이다.
     *
     * <p>{@code /api/auth/} 아래이므로 아래 {@link #PROTECTED_PATH_PREFIX}의 보호(세션 귀속 CSRF
     * 토큰 + 동일 출처 {@code Origin})가 예외 없이 걸린다. 그것이 이 경로를 여기 둔 이유다.
     */
    static final String LOGOUT_PATH = "/api/auth/logout";

    /**
     * CSRF 토큰이 실리는 요청 헤더 이름.
     *
     * <p>Spring Security의 기본값과 같은 값을 명시한다. 이 문자열은 프런트엔드
     * ({@code frontend/src/content/auth.ts})에도 리터럴로 있다 — 두 계층이 한 계약을 나눠 갖는
     * 자리이며, 갈라지면 로그아웃이 403으로만 끝난다. 오류 code 문자열이 Nginx 구성과 중복되는
     * 것과 같은 종류의 중복이고, 같은 방식으로 시험이 붙잡는다.
     */
    static final String CSRF_HEADER_NAME = "X-CSRF-TOKEN";

    /** 인증이 필요 없는 서버 경계 확인 경로. Story 1.2의 계약이며 이 스토리가 바꾸지 않는다. */
    static final String STATUS_PATH = "/api/status";

    /**
     * 상태를 바꾸지 않는 메서드. RFC 9110의 safe method에 {@code OPTIONS}를 더한 집합이며
     * Spring Security의 기본 CSRF 제외 집합과 같다.
     */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** CSRF·{@code Origin} 검증이 걸리는 경로 접두사. */
    private static final String PROTECTED_PATH_PREFIX = "/api/auth/";

    /*
     * 네이버 OIDC 엔드포인트.
     *
     * 공식 discovery 문서(`https://nid.naver.com/.well-known/openid-configuration`, 2026-09-08 실측)가
     * 광고하는 값을 그대로 옮긴 정적 상수다. 기동 시점에 discovery 왕복을 하지 않는다는 계약 때문에
     * 값이 코드에 있고, `CommonOAuth2Provider`에 NAVER가 없으므로 이것을 들고 있는 자리가 여기다.
     *
     * OIDC 경로는 `/oauth2/*`이고 레거시 OAuth 2.0 API의 `/oauth2.0/*`과 다르다(공식 개발 가이드
     * §3.5). 두 계열을 섞으면 ID Token이 오지 않는다.
     *
     * 자격정보가 아니다. 공개된 제공자 주소이므로 이 리터럴은 gitleaks의 대상이 아니다.
     */
    private static final String NAVER_ISSUER_URI = "https://nid.naver.com";

    private static final String NAVER_AUTHORIZATION_URI = NAVER_ISSUER_URI + "/oauth2/authorize";

    private static final String NAVER_TOKEN_URI = NAVER_ISSUER_URI + "/oauth2/token";

    private static final String NAVER_JWK_SET_URI = NAVER_ISSUER_URI + "/oauth2/jwks";

    /**
     * 인증 설정. 값은 환경이 주고 이 record는 이름만 정한다.
     *
     * <p>{@code publicBaseUrl}은 브라우저가 보는 origin이다. redirect URI를 요청 헤더에서 유도하지
     * 않기 위해 절대 URI를 환경에서 받는다({@code application.yml}의 근거 참조).
     *
     * <p>제공자별 값이 하나씩이다. 두 제공자의 자격정보는 서로를 보지 않으며, 한쪽이 비어도 다른
     * 쪽의 등록과 로그인 경로는 그대로 살아 있다({@link ProviderClientRegistrations#from}).
     *
     * <p>엔드포인트 네 값은 제공자에 따라 의미가 다르다. Google은 비어 있는 것이 정상이며 그때
     * Spring Security의 {@code CommonOAuth2Provider.GOOGLE}이 정한 정본 주소를 쓴다. 네이버는
     * {@code CommonOAuth2Provider}에 없으므로 이 클래스가 공식 discovery 문서의 정적 주소를 기본값으로
     * 들고 있고, 네 값은 그것을 덮는 재정의다. 어느 제공자에서도 기동 시점에 discovery 왕복을 하지
     * 않는다 — 값을 주는 것은 재정의가 필요한 환경(개발·CI의 local OIDC 스텁)뿐이다.
     */
    @ConfigurationProperties("bokrilog.auth")
    record AuthProperties(@Nullable String publicBaseUrl, Provider google, Provider naver) {

        AuthProperties {
            /*
             * `bokrilog.auth.google`·`bokrilog.auth.naver`가 통째로 없는 구성에서도 배선이 성립해야
             * 한다. 값이 없으면 빈 값 여섯 개짜리 record로 바꾸고, 아래
             * `ProviderClientRegistrations`가 그 제공자만 "구성되지 않음"으로 접는다.
             *
             * 정규화가 여기 하나뿐인 것이 요점이다. 제공자가 둘로 늘어도 그 성질은 유지된다 —
             * 그래서 `google()`·`naver()`는 null을 돌려주지 않고, 소비하는 쪽에 null 검사가 남지
             * 않는다. 남으면 그 분기는 아무도 지나지 않는 죽은 코드가 된다.
             */
            if (google == null) {
                google = Provider.empty();
            }
            if (naver == null) {
                naver = Provider.empty();
            }
        }

        /** 제공자 하나의 자격정보와 엔드포인트 재정의. 제공자 이름은 이 record에 없다. */
        record Provider(
                @Nullable String clientId,
                @Nullable String clientSecret,
                @Nullable String issuerUri,
                @Nullable String authorizationUri,
                @Nullable String tokenUri,
                @Nullable String jwkSetUri) {

            static Provider empty() {
                return new Provider(null, null, null, null, null, null);
            }
        }
    }

    /**
     * 이 애플리케이션이 아는 제공자 등록.
     *
     * <p>등록 id → {@link ClientRegistration} 맵이다. 단일 제공자를 필드로 들고 있던 모양을 맵으로
     * 넓힌 것이 Story 1.6의 핵심 변경이며, URL 표면은 이미 제공자 복수형이었다 —
     * {@code DefaultOAuth2AuthorizationRequestResolver}가 base URI 뒤에서 {@code {registrationId}}를
     * 뽑고 콜백은 {@code CALLBACK_BASE_URI + "/*"} 와일드카드다. 그래서 제공자를 늘리는 데 새 경로
     * 배선이 없다.
     *
     * <p>{@link ClientRegistrationRepository}를 직접 만드는 이유는 셋이다.
     *
     * <ul>
     *   <li>자격정보가 없는 구성에서 기동이 실패하지 않아야 한다. Boot의 property 매핑은
     *       {@code client-id}가 비면 {@code ClientRegistration.build()}에서 예외를 던지고, 그 예외는
     *       컨텍스트 생성 실패라 개발·CI 스택의 백엔드가 healthy가 되지 못한다. 여기서는 값이
     *       비면 그 제공자의 등록을 만들지 않고 "구성되지 않음" 상태로 남는다.
     *   <li>{@code issuer-uri}를 discovery 없이 실어야 한다. Boot의 {@code provider.<id>.issuer-uri}는
     *       기동 시점에 discovery 왕복을 하고, 그 왕복은 제공자에 대한 네트워크 의존을 기동 경로에
     *       만든다. Google은 {@code CommonOAuth2Provider.GOOGLE}이, 네이버는 아래
     *       {@link #NAVER_ISSUER_URI} 이하 네 상수가 그 값을 정적으로 들고 있어 {@code iss} 검증이
     *       네트워크 없이 성립한다.
     *   <li>PKCE를 명시한다. Spring Security 7의 기본값이 이미 {@code requireProofKey = true}지만,
     *       기본값이 바뀌면 조용히 꺼지는 자리라 계약으로 적는다.
     * </ul>
     *
     * <p><b>fail-closed가 제공자 단위로 독립한다.</b> 판정을 한 번에 접으면 네이버 자격정보가 없는
     * 환경에서 Google 로그인까지 죽는다. 그래서 아래 {@link #from}은 제공자마다 따로 판정하고 맵에
     * 넣거나 넣지 않는다. {@code publicBaseUrl}만 두 제공자가 공유하는 값이다 — 그것이 비면
     * redirect URI를 조립할 수 없으므로 어느 제공자도 만들어지지 않는 것이 맞다.
     */
    static final class ProviderClientRegistrations implements ClientRegistrationRepository {

        /**
         * 이 애플리케이션이 이름을 아는 제공자.
         *
         * <p>구성 여부와 무관한 닫힌 집합이다. {@code AuthSessionController}가 "모르는 제공자"(404)와
         * "아는 제공자인데 구성되지 않았다"(500)를 가르는 데 쓰며, 그 판정은 아래 맵으로 할 수 없다 —
         * 구성된 제공자의 인증 시작은 필터가 302로 끝내므로 컨트롤러에 도달하는 것은 언제나 맵에
         * 없는 이름이고, 맵을 술어로 쓰면 모든 요청이 404가 되어 fail-closed의 500이 사라진다.
         */
        private static final Set<String> SUPPORTED_REGISTRATION_IDS =
                Set.of(GOOGLE_REGISTRATION_ID, NAVER_REGISTRATION_ID);

        private final Map<String, ClientRegistration> registrations;

        private ProviderClientRegistrations(Map<String, ClientRegistration> registrations) {
            this.registrations = Map.copyOf(registrations);
        }

        static ProviderClientRegistrations from(AuthProperties properties) {
            String baseUrl = normalizedBaseUrl(properties.publicBaseUrl());
            Map<String, ClientRegistration> registrations = new LinkedHashMap<>();
            put(registrations, GOOGLE_REGISTRATION_ID, googleBuilder(), properties.google(), baseUrl);
            put(registrations, NAVER_REGISTRATION_ID, naverBuilder(), properties.naver(), baseUrl);
            rejectSharedIssuer(registrations);
            return new ProviderClientRegistrations(registrations);
        }

        /**
         * 두 등록이 같은 issuer를 가리키는 구성을 기동에서 거부한다.
         *
         * <p>계정 조회의 키가 {@code (issuer, subject)}이고 그 조합이 전역 유일이기 때문이다
         * ({@code AccountLifecycleApi#findUserIdBySocialIdentity}, {@code V1__identity_account.sql}).
         * 그래서 {@code bokrilog.auth.naver.issuer-uri}에 Google의 issuer가 잘못 들어오면 네이버로
         * 로그인한 사용자의 {@code sub}이 Google 쪽 매핑과 같은 namespace에서 조회되고, 그 매핑이
         * 있으면 <b>그 계정의 세션이 개설된다</b>. 두 제공자 식별자의 병합·전환은 이 스토리가 Never로
         * 금지한 것이며 그것이 구성 오류 하나로 열리는 경로가 여기다.
         *
         * <p>조회 키에 제공자를 더하는 방향을 택하지 않는다. 그것은 {@code identity} 공개 API의
         * 변경이고 이 스토리의 Ask First다. 이 검사는 잘못 구성된 프로세스가 아예 뜨지 못하게
         * 하는 것이라 어느 계약도 넓히지 않는다.
         *
         * <p>메시지에 자격정보도 {@code BOKRILOG_} 환경 변수 이름도 어느 제공자인지도 적지 않는다
         * ({@code DevOidcStubConfig}의 기동 guard와 같은 규율이다). 기동 실패 로그도 로그이며 답은
         * 어느 쪽이든 "제공자마다 다른 issuer를 구성한다" 하나다.
         */
        private static void rejectSharedIssuer(Map<String, ClientRegistration> registrations) {
            Set<String> seen = new HashSet<>();
            for (ClientRegistration registration : registrations.values()) {
                String issuer = registration.getProviderDetails().getIssuerUri();
                if (issuer == null) {
                    // 이 배선은 issuer 없는 등록을 만들지 않는다. 그래도 조용히 지나간다 —
                    // 비교할 값이 없는 것은 겹침이 아니다.
                    continue;
                }
                if (!seen.add(issuer)) {
                    throw new IllegalStateException(
                            "두 제공자 등록이 같은 issuer를 가리킨다. 제공자마다 다른 issuer를 구성한다.");
                }
            }
        }

        /**
         * 제공자 하나를 판정해 맵에 넣는다.
         *
         * <p>거부는 그 제공자만 빠지는 것이다. 인증 시작은 {@code AuthSessionController}가 공통 오류
         * 계약으로 답하고, 다른 제공자의 등록은 아무 영향을 받지 않는다.
         */
        private static void put(
                Map<String, ClientRegistration> registrations,
                String registrationId,
                ClientRegistration.Builder builder,
                AuthProperties.Provider provider,
                @Nullable String baseUrl) {
            if (!hasText(provider.clientId())
                    || !hasText(provider.clientSecret())
                    || baseUrl == null) {
                // fail-closed.
                return;
            }

            builder
                    .clientId(provider.clientId())
                    .clientSecret(provider.clientSecret())
                    // 절대 URI다. 요청 Host에서 조립하지 않으므로 Nginx가 포트를 잃는 것과
                    // 무관하고, "사전 등록된 정확한 redirect URI"와 같은 모양이다.
                    .redirectUri(baseUrl + CALLBACK_BASE_URI + "/" + registrationId)
                    /*
                     * 기본 동의 범위는 openid뿐이다. `CommonOAuth2Provider.GOOGLE`이 넣는
                     * profile·email을 이 한 줄이 대체한다(`scope`는 더하지 않고 바꾼다). 네이버
                     * discovery는 `openid`·`profile`을 광고하지만 `profile`을 요청하지 않는다 —
                     * 필요한 값은 ID Token의 `iss`·`sub` 둘뿐이다.
                     */
                    .scope(OidcScopes.OPENID)
                    // UserInfo를 부르지 않는다. Spring Security 7의 `shouldRetrieveUserInfo`는
                    // scope가 아니라 이 URI의 존재로 판단하므로, 비우지 않으면 openid만
                    // 요청했는데도 제공자의 UserInfo 왕복이 일어난다.
                    .userInfoUri(null)
                    .clientSettings(
                            ClientRegistration.ClientSettings.builder()
                                    .requireProofKey(true)
                                    .build());

            // 엔드포인트 재정의. 값이 없으면 위 builder가 들고 온 제공자 정본 주소가 남는다.
            if (hasText(provider.issuerUri())) {
                builder.issuerUri(provider.issuerUri());
            }
            if (hasText(provider.authorizationUri())) {
                builder.authorizationUri(provider.authorizationUri());
            }
            if (hasText(provider.tokenUri())) {
                builder.tokenUri(provider.tokenUri());
            }
            if (hasText(provider.jwkSetUri())) {
                builder.jwkSetUri(provider.jwkSetUri());
            }
            registrations.put(registrationId, builder.build());
        }

        private static ClientRegistration.Builder googleBuilder() {
            return CommonOAuth2Provider.GOOGLE.getBuilder(GOOGLE_REGISTRATION_ID);
        }

        /**
         * 네이버 등록의 뼈대.
         *
         * <p>{@code CommonOAuth2Provider}에 NAVER가 없다(상수는 {@code GOOGLE}·{@code GITHUB}·
         * {@code FACEBOOK}·{@code OKTA} 넷뿐이다). 그래서 Google과 달리 엔드포인트 네 개와
         * {@code userNameAttributeName}·{@code authorizationGrantType}·
         * {@code clientAuthenticationMethod}가 모두 필수 입력이고, 이 메서드가 그 값을 든다.
         *
         * <p>{@code client_secret_post}인 것은 네이버가 그렇게 광고하기 때문이다. discovery의
         * {@code token_endpoint_auth_methods_supported}가 {@code [client_secret_post, none]}이라
         * Spring Security의 기본값({@code client_secret_basic})으로는 토큰 교환이 성립하지 않는다.
         */
        private static ClientRegistration.Builder naverBuilder() {
            return ClientRegistration.withRegistrationId(NAVER_REGISTRATION_ID)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .issuerUri(NAVER_ISSUER_URI)
                    .authorizationUri(NAVER_AUTHORIZATION_URI)
                    .tokenUri(NAVER_TOKEN_URI)
                    .jwkSetUri(NAVER_JWK_SET_URI)
                    // ID Token의 주체 claim. `CommonOAuth2Provider`가 없으므로 기본값도 없다.
                    .userNameAttributeName(IdTokenClaimNames.SUB);
        }

        @Override
        public @Nullable ClientRegistration findByRegistrationId(String registrationId) {
            return this.registrations.get(registrationId);
        }

        /**
         * 쓸 수 있는 제공자 등록이 하나라도 있는가.
         *
         * <p>없으면 인증 필터를 배선하지 않는다. 필터를 두고 등록만 비우면 인증 시작이 필터 안의
         * 예외로 끝나고, 그 예외는 스택과 함께 로그에 실린다. 그것은 "내부 경로·스택을 로그에도
         * 남기지 않는다"와 정면으로 부딪힌다.
         *
         * <p>"하나 이상"이다. 제공자 하나만 구성된 환경에서 그 하나로 로그인할 수 있어야 하고,
         * 전부를 요구하면 개발 환경의 부분 구성이 인증 표면을 통째로 죽인다.
         */
        boolean isConfigured() {
            return !this.registrations.isEmpty();
        }

        /**
         * 이 애플리케이션이 이름을 아는 제공자인가.
         *
         * <p>구성 여부를 보지 않는다. 이름조차 모르는 제공자는 그 경로가 존재하지 않는 것이고(404),
         * 아는 이름인데 구성되지 않은 것은 요청을 처리할 수 없는 것이다(500). 두 답이 갈리는 자리는
         * {@code AuthSessionController} 하나이며 판정은 여기 있다.
         */
        static boolean isSupportedProvider(String registrationId) {
            return SUPPORTED_REGISTRATION_IDS.contains(registrationId);
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
    ProviderClientRegistrations providerClientRegistrations(AuthProperties properties) {
        return ProviderClientRegistrations.from(properties);
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
            ProviderClientRegistrations registrations) {
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
            ProviderClientRegistrations registrations,
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
                 * 로그아웃은 `POST /api/auth/logout` 하나다.
                 *
                 * 매처로 묶지 않고 그냥 켜면 기본 배선이 `/logout` HTTP 표면을 하나 새로 만든다.
                 * 그 경로는 `/api/auth/` 밖이라 CSRF·Origin 보호도 받지 않고, Nginx의 정적
                 * location이 받아 405를 돌려주므로 계약이 두 벌이 된다.
                 *
                 * 리다이렉트가 아니라 204다. 목적지는 서버가 정하지 않는다 — 로그아웃 뒤 화면은
                 * 클라이언트가 표지와 함께 여는 진입 화면이고, 여기서 302를 돌려주면 `fetch`가
                 * 그것을 따라가 본문 없는 HTML을 받는다.
                 *
                 * 세션 폐기 본체는 `AuthFlowHandlers`가 소유한다 — "행 삭제·컨텍스트 정리·쿠키
                 * 만료" 셋을 함께 만족시키는 자리는 그 메서드 하나다. 다만 기본
                 * `SecurityContextLogoutHandler`를 피한 것은 아니다: `LogoutConfigurer`는 등록된
                 * 사용자 핸들러 뒤에 그 핸들러를 **항상** 덧붙이므로 매 로그아웃마다 함께 실행된다.
                 * 우리 핸들러가 이미 세션을 무효화한 뒤이므로 그때 `getSession(false)`는 null이고,
                 * 남는 일은 이미 비어 있는 `SecurityContext`를 한 번 더 정리하는 것뿐이다. 순서가
                 * 뒤집히면(그 핸들러가 먼저 돌면) 우리 핸들러가 볼 세션이 사라져 행이 남으므로,
                 * 이 배선에서 중요한 것은 "피했다"가 아니라 "앞에 있다"다.
                 */
                .logout(
                        logout ->
                                logout
                                        .logoutRequestMatcher(logoutRequests())
                                        .addLogoutHandler(handlers.logoutHandler())
                                        .logoutSuccessHandler(
                                                (request, response, authentication) ->
                                                        response.setStatus(HttpStatus.NO_CONTENT.value())))
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
                 * CSRF 토큰은 세션에 귀속한다. 쿠키 저장소를 쓰지 않는 것은 "브라우저에는 세션
                 * 쿠키 하나뿐"이라는 계약 때문이다. 클라이언트가 토큰을 얻는 경로는
                 * `GET /api/auth/session`의 응답 본문 하나이며(`AuthSessionController`), 그래서
                 * 토큰이 쿠키·`localStorage`·`sessionStorage`·URL에 놓일 자리가 없다.
                 *
                 * 보호 대상을 `/api/auth/` 아래의 상태 변경 요청으로 좁힌다. 전역으로 두면
                 * CsrfFilter가 서블릿보다 먼저 답해 `POST /api/status`가 405 대신 403이 되고
                 * Story 1.2·1.3이 고정한 탐침 응답이 함께 뒤집힌다.
                 */
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(new SessionBoundCsrfTokenRepository())
                                        .requireCsrfProtectionMatcher(protectedRequests()))
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
                                ProviderClientRegistrations.normalizedBaseUrl(
                                        properties.publicBaseUrl())),
                        CsrfFilter.class)
                // 이미 인증된 세션의 로그인 시작 거절과 복귀 목적지 기록.
                // authorization request를 만드는 필터보다 앞이어야 한다.
                .addFilterBefore(handlers.authorizationStartFilter(), CsrfFilter.class)
                /*
                 * 세션 수명·의심 세션 판정.
                 *
                 * `SecurityContextHolderFilter` **뒤**여야 한다. 그 필터가 세션에 저장된
                 * `SecurityContext`를 thread-local에 얹으므로, 앞에 두면 여기서 비운 컨텍스트가
                 * 곧바로 덮어써진다. 뒤에 두면 이 필터가 마지막 말을 하고, 그 정리는 thread-local
                 * 에만 닿아 세션 행에 저장되지 않는다(재인증 요구가 행을 남긴다는 계약).
                 *
                 * `CsrfFilter`·`LogoutFilter`보다는 앞이다. 수명이 끝난 세션이 상태 변경 요청을
                 * 통과하는 창을 남기지 않는다.
                 */
                .addFilterAfter(handlers.sessionGuardFilter(), SecurityContextHolderFilter.class);

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

    /**
     * 로그아웃으로 인정하는 요청. {@code POST /api/auth/logout} 정확히 하나다.
     *
     * <p>람다로 쓴다. 경로 매처 구현을 인용하면 그 타입의 폐기 예고가 곧 {@code -Werror} 아래의
     * 빌드 실패가 되고, 여기서 하는 판정은 위 {@link #isProtectedRequest}와 같은 모양의 문자열
     * 비교 하나다. 접두사 비교가 아니라 동등 비교여서 {@code /api/auth/logout/x}는 받지 않는다.
     */
    private static RequestMatcher logoutRequests() {
        return AuthSecurityConfig::isLogoutRequest;
    }

    /**
     * 로그아웃 요청인가.
     *
     * <p>package-private이다. {@code AuthFlowHandlers}의 세션 guard가 재인증을 요구받은 세션의
     * 상태 변경을 막으면서 <b>로그아웃만은 통과시켜야</b> 하고(로그아웃은 상태를 없애기만 하므로
     * 표시된 세션도 스스로를 끝낼 수 있어야 한다), 그 판정이 두 곳에 있으면 한쪽만 고쳐지는 날이
     * 온다. {@link #isProtectedRequest}와 같은 이유의 같은 배치다.
     */
    static boolean isLogoutRequest(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && LOGOUT_PATH.equals(request.getRequestURI());
    }

    /**
     * 세션 귀속 CSRF 토큰과 동일 출처 {@code Origin}을 요구하는 요청인가.
     *
     * <p>package-private이다. {@code AuthFlowHandlers}의 세션 guard가 재인증을 요구받은 세션의
     * 상태 변경을 같은 경계에서 막으므로, 이 판정이 두 곳에 있으면 한쪽만 고쳐지는 날이 온다.
     */
    static boolean isProtectedRequest(HttpServletRequest request) {
        // 컨텍스트 경로가 비어 있는 배치다(`server.servlet.context-path`를 두지 않는다).
        // 그래서 요청 URI가 그대로 애플리케이션 경로다.
        return !SAFE_METHODS.contains(request.getMethod())
                && request.getRequestURI().startsWith(PROTECTED_PATH_PREFIX);
    }

    /**
     * 세션 귀속 CSRF 토큰 저장소.
     *
     * <p>{@link HttpSessionCsrfTokenRepository}에 한 가지만 더한다 — <b>토큰을 저장하려고 세션을
     * 만들지 않는다.</b>
     *
     * <p>기본 구현의 {@code saveToken}은 {@code request.getSession()}을 부르고, 그 호출이 세션
     * 행을 하나 만든다. 그래서 세션이 없는 상태 변경 요청 하나마다(로그아웃한 브라우저의 재요청,
     * 익명 호출자의 탐침) 거절과 함께 행이 늘어난다. "실패는 어떤 상태도 바꾸지 않는다"와
     * "반복 로그아웃은 새 세션 행을 만들지 않는다"가 그 지점에서 깨진다.
     *
     * <p>저장을 건너뛰어도 검사는 그대로 성립한다. 저장되지 않은 새 토큰과 요청이 들고 온 값은
     * 어차피 다르므로 결과는 무변경 403 하나다. 이 배치에서 토큰이 만들어지는 정당한 순간은
     * 인증된 세션이 {@code GET /api/auth/session}을 부르는 때뿐이고, 그때는 세션이 이미 있다.
     */
    private static final class SessionBoundCsrfTokenRepository implements CsrfTokenRepository {

        private final HttpSessionCsrfTokenRepository delegate = new HttpSessionCsrfTokenRepository();

        private SessionBoundCsrfTokenRepository() {
            // 헤더 이름을 명시한다. 기본값과 같은 값이지만 프런트엔드가 같은 리터럴을 들고 있다.
            this.delegate.setHeaderName(CSRF_HEADER_NAME);
        }

        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            return this.delegate.generateToken(request);
        }

        @Override
        public void saveToken(
                @Nullable CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
            if (token != null && request.getSession(false) == null) {
                return;
            }
            this.delegate.saveToken(token, request, response);
        }

        @Override
        public @Nullable CsrfToken loadToken(HttpServletRequest request) {
            return this.delegate.loadToken(request);
        }
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
