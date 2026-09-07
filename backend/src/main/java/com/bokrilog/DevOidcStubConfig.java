package com.bokrilog;

import com.bokrilog.AuthSecurityConfig.AuthProperties;
import com.bokrilog.AuthSecurityConfig.GoogleClientRegistrations;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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
 * 프로세스에서 함께 구성되면 어느 쪽이 로그인을 처리했는지 응답만으로 알 수 없다.
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

    static final String DISCOVERY_PATH = BASE_PATH + "/.well-known/openid-configuration";

    static final String JWKS_PATH = BASE_PATH + "/jwks";

    static final String AUTHORIZE_PATH = BASE_PATH + "/authorize";

    static final String TOKEN_PATH = BASE_PATH + "/token";

    /** 동의 화면의 유일한 조작. 브라우저 시험이 이 이름으로 링크를 찾는다. */
    static final String CONSENT_LABEL = "동의하고 계속하기";

    /** 백엔드가 자기 스텁에 닿는 base URL. 값이 없으면 서블릿 포트로 조립한다. */
    private static final String INTERNAL_BASE_URL_PROPERTY = "bokrilog.auth.dev-oidc.internal-base-url";

    /**
     * 기동 guard.
     *
     * <p>실제 제공자 자격정보가 구성된 프로세스에서는 이 프로필이 켜질 수 없다. 어느 값이 문제인지
     * 메시지에 적지 않는다 — 환경 변수 이름은 응답에도 로그에도 남기지 않는 것이 이 저장소의
     * 계약이고, 기동 실패 로그도 로그다.
     */
    DevOidcStubConfig(AuthProperties properties) {
        AuthProperties.Google google = properties.google();
        if (hasText(google.clientId()) || hasText(google.clientSecret())) {
            throw new IllegalStateException(
                    "개발용 OIDC 스텁 프로필은 실제 제공자 등록과 함께 활성화할 수 없다. "
                            + "스텁과 실제 제공자 중 하나만 구성한다.");
        }
    }

    @Bean
    DevOidcStub devOidcStub(AuthProperties properties, Environment environment) {
        return new DevOidcStub(browserBaseUrl(properties, environment) + BASE_PATH);
    }

    /**
     * 스텁을 가리키는 제공자 등록.
     *
     * <p>{@code @Primary}로 운영 빈을 가린다. {@code PostgresTestcontainersConfig}가 Flyway 전략에
     * 쓰는 것과 같은 관용이며, 가려지는 쪽은 자격정보가 없어 "구성되지 않음"으로 접힌 등록이다.
     * client id·secret은 스텁이 실행 시점에 만든 값이라 compose에도 설정 파일에도 리터럴이 없다.
     */
    @Bean
    @Primary
    GoogleClientRegistrations devOidcClientRegistrations(
            DevOidcStub stub, AuthProperties properties, Environment environment) {
        String browserBaseUrl = browserBaseUrl(properties, environment);
        String internalBaseUrl = internalBaseUrl(environment);
        return GoogleClientRegistrations.from(
                new AuthProperties(
                        browserBaseUrl,
                        new AuthProperties.Google(
                                stub.clientId(),
                                stub.clientSecret(),
                                stub.issuer(),
                                browserBaseUrl + AUTHORIZE_PATH,
                                internalBaseUrl + TOKEN_PATH,
                                internalBaseUrl + JWKS_PATH)));
    }

    /**
     * 스텁의 HTTP 표면.
     *
     * <p>{@code @RestController}가 아니라 함수형 라우팅이다. 경로 네 개가 빈 하나에 실려 있어야
     * "기본 프로필에서 {@code /api/dev-oidc/**}가 노출되지 않는다"를 빈의 부재로 단정할 수 있다.
     */
    @Bean
    RouterFunction<ServerResponse> devOidcStubRoutes(
            DevOidcStub stub, AuthProperties properties, Environment environment) {
        String browserBaseUrl = browserBaseUrl(properties, environment);
        String internalBaseUrl = internalBaseUrl(environment);
        return RouterFunctions.route()
                .GET(
                        DISCOVERY_PATH,
                        request ->
                                respond(
                                        stub.discovery(
                                                browserBaseUrl + AUTHORIZE_PATH,
                                                internalBaseUrl + TOKEN_PATH,
                                                internalBaseUrl + JWKS_PATH)))
                .GET(JWKS_PATH, request -> respond(stub.jwks()))
                .GET(AUTHORIZE_PATH, request -> consent(stub, request))
                .POST(
                        TOKEN_PATH,
                        request ->
                                respond(
                                        stub.token(
                                                parameters(request),
                                                // client 자격정보는 `client_secret_basic`으로 온다.
                                                request.headers().firstHeader("Authorization"))))
                .build();
    }

    /**
     * 동의 화면.
     *
     * <p>실제 제공자의 동의 화면이 있는 자리다. 사용자가 링크를 누르면 최상위 내비게이션으로 콜백에
     * 되돌아온다. 자동으로 리다이렉트하지 않는 이유는 브라우저 시험이 "사용자가 동의했다"는 단계를
     * 실제로 지나야 성공 경로를 검증한 것이 되기 때문이다.
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
        String publicBaseUrl = GoogleClientRegistrations.normalizedBaseUrl(properties.publicBaseUrl());
        return publicBaseUrl != null ? publicBaseUrl : internalBaseUrl(environment);
    }

    private static String internalBaseUrl(Environment environment) {
        String configured =
                GoogleClientRegistrations.normalizedBaseUrl(
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

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
