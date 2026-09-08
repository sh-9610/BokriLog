package com.bokrilog;

import com.bokrilog.AuthSecurityConfig.AuthProperties;
import com.bokrilog.AuthSecurityConfig.ProviderClientRegistrations;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 개발·CI 스택의 local OIDC 제공자 표면.
 *
 * <p>{@code dev-oidc} 프로필에서만 존재한다. 기본 프로필에서는 이 클래스의 빈이 하나도 만들어지지
 * 않고 {@code /api/dev-oidc/**}가 노출되지 않는다 — 라우팅이 빈 하나에 실려 있으므로 그 빈의 부재가
 * 곧 경로의 부재다. {@code DevOidcStubProfileTest}가 두 프로필을 각각 단정한다.
 *
 * <p>프로필이 운영에서 조용히 켜지는 경로를 두 겹으로 막는다. 첫째가 위의 빈 부재이고, 둘째가 이
 * 클래스의 생성자다 — 실제 제공자 등록과 함께 활성이면 기동을 거부한다. 스텁과 실제 제공자가 같은
 * 프로세스에서 함께 구성되면 어느 쪽이 로그인을 처리했는지 응답만으로 알 수 없다. 제공자가 둘로
 * 늘었으므로 그 검사도 <b>둘 다</b> 본다 — 한쪽만 보면 나머지 제공자의 실제 자격정보가 스텁과 함께
 * 조용히 살아 있다.
 *
 * <p>스텁 논리는 이 클래스에 없다. {@link DevOidcStub}이 키·JWKS·ID Token 서명·PKCE·code 소비를
 * 모두 소유하고, 제공자마다 그 클래스의 <b>인스턴스 하나</b>를 만든다 — issuer가 생성자 인자이고
 * client id·secret·서명 키가 인스턴스 단위이므로 두 번 만드는 것으로 두 제공자가 성립한다. 스텁을
 * 두 벌 구현하지 않는다.
 *
 * <p>{@code /api} 아래에 둔다. Nginx가 이미 프록시하는 경로라 새 서비스·새 게시 포트·새 compose
 * 파일·CSP 확장이 하나도 필요 없다. 동의 화면은 인라인 스크립트도 스타일시트도 쓰지 않는 링크 하나
 * 짜리 문서라 {@code default-src 'none'}·{@code form-action 'none'} 아래에서 그대로 동작한다.
 *
 * <p>브라우저가 보는 주소와 백엔드가 보는 주소가 다르다. 인가 요청은 브라우저가 공개 origin으로
 * 보내고, code 교환과 JWKS 조회는 백엔드가 자기 서블릿 포트로 보낸다. 그래서 authorization URI만
 * 공개 base URL에서 조립하고 token·JWKS URI는 내부 base URL에서 조립한다. 두 값을 하나로 합치면
 * compose 네트워크 안에서 공개 origin이 해석되지 않아 code 교환이 실패한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile(DevOidcStubConfig.PROFILE)
@EnableConfigurationProperties(AuthProperties.class)
class DevOidcStubConfig {

    /** 이 스텁을 켜는 프로필. 개발·CI 스택만 활성화한다. */
    static final String PROFILE = "dev-oidc";

    /** 스텁 표면의 접두사. {@code /api}만 프록시하는 Nginx 블록이 그대로 받는다. */
    static final String BASE_PATH = "/api/dev-oidc";

    /**
     * 스텁이 서는 제공자.
     *
     * <p>운영 등록 id를 그대로 쓴다. 스텁 전용 이름을 만들면 등록 id와 경로 세그먼트가 갈라지고,
     * 그때 redirect URI의 마지막 세그먼트가 콜백 필터의 등록 조회와 어긋난다.
     */
    static final List<String> PROVIDERS =
            List.of(
                    AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                    AuthSecurityConfig.NAVER_REGISTRATION_ID);

    /** 동의 화면의 유일한 조작. 브라우저 시험이 이 이름으로 링크를 찾는다. */
    static final String CONSENT_LABEL = "동의하고 계속하기";

    /** 백엔드가 자기 스텁에 닿는 base URL. 값이 없으면 서블릿 포트로 조립한다. */
    private static final String INTERNAL_BASE_URL_PROPERTY = "bokrilog.auth.dev-oidc.internal-base-url";

    /**
     * 그 제공자 스텁의 경로 접두사.
     *
     * <p>제공자마다 갈라지는 것이 요점이다. 한 경로에 두 스텁을 올리면 어느 issuer의 키로 서명된
     * ID Token인지 요청만으로 정해지지 않고, 두 제공자가 서로의 토큰을 검증하는 상태가 열린다.
     */
    static String basePath(String provider) {
        return BASE_PATH + "/" + provider;
    }

    static String discoveryPath(String provider) {
        return basePath(provider) + "/.well-known/openid-configuration";
    }

    static String jwksPath(String provider) {
        return basePath(provider) + "/jwks";
    }

    static String authorizePath(String provider) {
        return basePath(provider) + "/authorize";
    }

    static String tokenPath(String provider) {
        return basePath(provider) + "/token";
    }

    /**
     * 기동 guard.
     *
     * <p>실제 제공자 자격정보가 구성된 프로세스에서는 이 프로필이 켜질 수 없다. 어느 값이 문제인지
     * 메시지에 적지 않는다 — 환경 변수 이름은 응답에도 로그에도 남기지 않는 것이 이 저장소의
     * 계약이고, 기동 실패 로그도 로그다. 어느 <b>제공자</b>인지도 적지 않는다: 같은 이유이고, 답은
     * 어느 쪽이든 "스텁과 실제 제공자 중 하나만 구성한다" 하나다.
     */
    DevOidcStubConfig(AuthProperties properties) {
        if (hasCredentials(properties.google()) || hasCredentials(properties.naver())) {
            throw new IllegalStateException(
                    "개발용 OIDC 스텁 프로필은 실제 제공자 등록과 함께 활성화할 수 없다. "
                            + "스텁과 실제 제공자 중 하나만 구성한다.");
        }
    }

    /** Google 스텁 빈의 이름. 같은 타입 빈이 둘이라 주입 지점이 이 이름으로 고른다. */
    static final String GOOGLE_STUB_BEAN = "devOidcStubGoogle";

    static final String NAVER_STUB_BEAN = "devOidcStubNaver";

    /*
     * 제공자별 스텁 인스턴스.
     *
     * `DevOidcStub`은 issuer를 생성자 인자로 받고 client id·secret·RSA 서명 키를 인스턴스마다 새로
     * 만든다. 그래서 아래 두 빈은 서로 다른 issuer·client·키를 가지며, 한쪽 스텁이 서명한 ID Token은
     * 다른 쪽의 JWKS로 검증되지 않는다. 대가는 RSA 키 두 쌍의 추가 생성이다.
     *
     * 맵 하나가 아니라 빈 둘이다. `Map<String, DevOidcStub>` 하나를 빈으로 두면 그 타입을 주입받는
     * 자리에서 Spring이 "DevOidcStub 타입 빈 전부"를 모으려 하고, 그런 빈이 없으므로 주입이 실패한다.
     * 빈을 둘로 두면 그 수집이 실제로 성립하고, "제공자 수만큼 스텁이 있다"를 빈 개수로 단정할 수도
     * 있다(`DevOidcStubProfileTest`).
     */

    @Bean(GOOGLE_STUB_BEAN)
    DevOidcStub devOidcStubGoogle(AuthProperties properties, Environment environment) {
        return newStub(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, properties, environment);
    }

    @Bean(NAVER_STUB_BEAN)
    DevOidcStub devOidcStubNaver(AuthProperties properties, Environment environment) {
        return newStub(AuthSecurityConfig.NAVER_REGISTRATION_ID, properties, environment);
    }

    private static DevOidcStub newStub(
            String provider, AuthProperties properties, Environment environment) {
        return new DevOidcStub(browserBaseUrl(properties, environment) + basePath(provider));
    }

    /**
     * 스텁을 가리키는 제공자 등록.
     *
     * <p>{@code @Primary}로 운영 빈을 가린다. {@code PostgresTestcontainersConfig}가 Flyway 전략에
     * 쓰는 것과 같은 관용이며, 가려지는 쪽은 자격정보가 없어 두 제공자 모두 "구성되지 않음"으로
     * 접힌 등록이다. client id·secret은 스텁이 실행 시점에 만든 값이라 compose에도 설정 파일에도
     * 리터럴이 없다.
     *
     * <p>두 제공자를 한 번에 조립한다. 운영 코드와 같은 {@code from()}을 지나므로 scope·PKCE·
     * redirect URI·UserInfo 부재 같은 계약이 스텁 경로에서도 그대로 성립하고, 시험이 검증하는 배선이
     * 곧 운영이 쓰는 배선으로 남는다.
     */
    @Bean
    @Primary
    ProviderClientRegistrations devOidcClientRegistrations(
            @Qualifier(GOOGLE_STUB_BEAN) DevOidcStub google,
            @Qualifier(NAVER_STUB_BEAN) DevOidcStub naver,
            AuthProperties properties,
            Environment environment) {
        String browserBaseUrl = browserBaseUrl(properties, environment);
        String internalBaseUrl = internalBaseUrl(environment);
        return ProviderClientRegistrations.from(
                new AuthProperties(
                        browserBaseUrl,
                        stubProvider(
                                google,
                                AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                                browserBaseUrl,
                                internalBaseUrl),
                        stubProvider(
                                naver,
                                AuthSecurityConfig.NAVER_REGISTRATION_ID,
                                browserBaseUrl,
                                internalBaseUrl)));
    }

    private static AuthProperties.Provider stubProvider(
            DevOidcStub stub, String provider, String browserBaseUrl, String internalBaseUrl) {
        return new AuthProperties.Provider(
                stub.clientId(),
                stub.clientSecret(),
                stub.issuer(),
                browserBaseUrl + authorizePath(provider),
                internalBaseUrl + tokenPath(provider),
                internalBaseUrl + jwksPath(provider));
    }

    /**
     * 스텁의 HTTP 표면.
     *
     * <p>{@code @RestController}가 아니라 함수형 라우팅이다. 모든 경로가 빈 <b>하나</b>에 실려 있어야
     * "기본 프로필에서 {@code /api/dev-oidc/**}가 노출되지 않는다"를 빈의 부재로 단정할 수 있다.
     * 제공자가 늘어도 빈은 하나다 — 제공자마다 라우터를 만들면 그 단정이 "빈이 몇 개인가"를 세는
     * 형태로 바뀌고, 그때 하나가 남아 있는 상태가 통과한다.
     */
    @Bean
    RouterFunction<ServerResponse> devOidcStubRoutes(
            @Qualifier(GOOGLE_STUB_BEAN) DevOidcStub google,
            @Qualifier(NAVER_STUB_BEAN) DevOidcStub naver,
            AuthProperties properties,
            Environment environment) {
        String browserBaseUrl = browserBaseUrl(properties, environment);
        String internalBaseUrl = internalBaseUrl(environment);
        Map<String, DevOidcStub> stubs =
                Map.of(
                        AuthSecurityConfig.GOOGLE_REGISTRATION_ID, google,
                        AuthSecurityConfig.NAVER_REGISTRATION_ID, naver);
        RouterFunctions.Builder routes = RouterFunctions.route();
        for (String provider : PROVIDERS) {
            DevOidcStub stub = stub(stubs, provider);
            routes
                    .GET(
                            discoveryPath(provider),
                            request ->
                                    respond(
                                            stub.discovery(
                                                    browserBaseUrl + authorizePath(provider),
                                                    internalBaseUrl + tokenPath(provider),
                                                    internalBaseUrl + jwksPath(provider))))
                    .GET(jwksPath(provider), request -> respond(stub.jwks()))
                    .GET(authorizePath(provider), request -> consent(stub, request))
                    .POST(
                            tokenPath(provider),
                            request ->
                                    respond(
                                            stub.token(
                                                    parameters(request),
                                                    /*
                                                     * client 자격정보는 등록의 인증 방법에 따라 이
                                                     * 헤더(`client_secret_basic`)로 오거나 본문
                                                     * (`client_secret_post`)으로 온다. 스텁은 둘 다
                                                     * 받으므로 제공자마다 분기하지 않는다.
                                                     */
                                                    request.headers().firstHeader("Authorization"))));
        }
        return routes.build();
    }

    /**
     * 동의 화면.
     *
     * <p>실제 제공자의 동의 화면이 있는 자리다. 사용자가 링크를 누르면 최상위 내비게이션으로 콜백에
     * 되돌아온다. 자동으로 리다이렉트하지 않는 이유는 브라우저 시험이 "사용자가 동의했다"는 단계를
     * 실제로 지나야 성공 경로를 검증한 것이 되기 때문이다.
     *
     * <p>제공자 이름을 문서에 적지 않는다. 두 제공자의 동의 화면이 글자까지 같아야 브라우저 시험이
     * 한 헬퍼로 두 왕복을 지날 수 있고, 어느 스텁이 응답했는지는 목적지가 말한다.
     */
    private static ServerResponse consent(DevOidcStub stub, ServerRequest request) {
        String redirect = stub.consentRedirect(parameters(request));
        if (redirect == null) {
            // authorization request의 모양이 아니다. 이 흐름은 검증을 통과할 수 없으므로 열지 않는다.
            return ServerResponse.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"invalid_request\"}");
        }
        return ServerResponse.ok()
                // charset을 명시한다. `text/html`만 두면 `StringHttpMessageConverter`가 헤더에
                // charset을 붙이지 않아 한국어 본문의 해석이 브라우저 추측에 맡겨진다.
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(
                        "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                                + "<title>개발용 로그인 스텁</title></head><body>"
                                + "<h1>개발용 로그인 스텁</h1>"
                                + "<p>실제 제공자가 아닙니다. 개발·CI 스택에서만 동작합니다.</p>"
                                + "<a href=\""
                                + escapeHtml(redirect)
                                + "\">"
                                + CONSENT_LABEL
                                + "</a></body></html>");
    }

    private static ServerResponse respond(DevOidcStub.Response response) {
        return ServerResponse.status(response.status())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(response.body());
    }

    /**
     * 그 제공자의 스텁.
     *
     * <p>없으면 기동을 멈춘다. {@link #PROVIDERS}가 맵을 만들고 같은 목록이 이 조회를 하므로 여기
     * 오지 않지만, 조용히 null을 흘리면 라우팅이 절반만 존재하는 상태가 남는다.
     */
    private static DevOidcStub stub(Map<String, DevOidcStub> stubs, String provider) {
        DevOidcStub stub = stubs.get(provider);
        if (stub == null) {
            throw new IllegalStateException("개발용 OIDC 스텁이 제공자 하나를 갖지 못했다.");
        }
        return stub;
    }

    /**
     * 요청의 이름 → 값.
     *
     * <p>{@code GET}은 쿼리 문자열, {@code POST}는 {@code application/x-www-form-urlencoded} 본문이
     * 서블릿 컨테이너에서 같은 파라미터 맵으로 합쳐진다. 값이 여러 개인 이름은 첫 값만 쓴다 —
     * authorization request와 토큰 요청의 어떤 이름도 반복되지 않으므로 두 번째 값은 흐름의 사실이
     * 아니다.
     */
    private static Map<String, String> parameters(ServerRequest request) {
        Map<String, String> values = new LinkedHashMap<>();
        request
                .params()
                .forEach(
                        (name, list) -> {
                            if (!list.isEmpty()) {
                                values.put(name, list.get(0));
                            }
                        });
        return values;
    }

    /** 브라우저가 보는 base URL. 공개 base URL이 없는 구성에서는 내부 주소로 접는다. */
    private static String browserBaseUrl(AuthProperties properties, Environment environment) {
        String publicBaseUrl =
                ProviderClientRegistrations.normalizedBaseUrl(properties.publicBaseUrl());
        return publicBaseUrl != null ? publicBaseUrl : internalBaseUrl(environment);
    }

    private static String internalBaseUrl(Environment environment) {
        String configured =
                ProviderClientRegistrations.normalizedBaseUrl(
                        environment.getProperty(INTERNAL_BASE_URL_PROPERTY));
        if (configured != null) {
            return configured;
        }
        // 자기 서블릿 포트다. compose는 이 포트를 `SERVER_PORT`로 고정하고 바깥에 게시하지 않는다.
        return "http://127.0.0.1:" + environment.getProperty("server.port", "8080");
    }

    /**
     * 링크 속성에 들어갈 URL을 escape한다.
     *
     * <p>{@code &}를 그대로 두면 {@code &state=}가 문자 참조로 읽혀 목적지가 달라진다. 값은 서버가
     * 만든 URL이지만 {@code redirect_uri}는 요청이 들고 온 값이므로 escape 없이 문서에 넣지 않는다.
     */
    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static boolean hasCredentials(AuthProperties.Provider provider) {
        return hasText(provider.clientId()) || hasText(provider.clientSecret());
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
