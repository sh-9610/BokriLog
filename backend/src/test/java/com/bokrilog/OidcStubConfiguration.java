package com.bokrilog;

import com.bokrilog.DevOidcStub.TokenPlan;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 백엔드 시험이 쓰는 local OIDC 제공자.
 *
 * <p>스텁 논리는 여기 없다. {@link DevOidcStub}이 키·JWKS·ID Token 서명·실패 주입을 모두 소유하고
 * 이 클래스는 그것을 루프백 HTTP 서버에 올리는 표면 하나다. 같은 논리가 개발·CI 스택에서는
 * {@code DevOidcStubConfig}를 통해 {@code /api/dev-oidc/**}에 올라간다 — 스텁을 두 번 만들지 않는다.
 *
 * <p>표면이 둘인 것은 포트 때문이다. 백엔드는 code 교환과 JWKS 조회를 자기 주소로 보내는데, 이
 * 시험들은 {@code RANDOM_PORT}로 뜨고 그 포트는 컨텍스트 refresh가 끝난 뒤에 정해진다. 제공자 등록은
 * 그보다 먼저 만들어지므로 시험에서는 애플리케이션 자신이 아니라 미리 뜬 이 서버를 가리켜야 한다.
 * {@code /api/dev-oidc/**} 표면은 compose 스택({@code verify-same-origin.sh})과 브라우저
 * 시험({@code e2e/auth-entry.spec.ts})이 검증한다.
 *
 * <p>Spring 빈을 등록하지 않는다. 시험이 필요한 것은 배선이 아니라 구성 값이므로
 * {@link #registerProperties(DynamicPropertyRegistry)}가 {@code bokrilog.auth.*} 일곱 개를 채운다.
 * 그래서 운영 코드의 {@code ClientRegistrationRepository} 빈이 하나로 유지되고, 시험이 검증하는
 * 배선이 곧 운영이 쓰는 배선이다.
 *
 * <p>{@code /authorize}를 올리지 않는다. 시험은 인가 요청의 쿼리를 직접 읽고
 * {@link DevOidcStub#issueCode(TokenPlan)}로 계획을 등록하므로 그 왕복을 브라우저처럼 지나지 않는다.
 * 동의 화면은 브라우저가 지나는 경로이며 위 두 검증이 담당한다.
 */
final class OidcStubConfiguration {

    /**
     * 시험이 쓰는 공개 base URL.
     *
     * <p>실제 서블릿 컨테이너의 임의 포트와 다른 값이다. 그것이 이 배치가 재현하려는 모양이다 —
     * 배포에서도 redirect URI는 브라우저가 보는 origin이고 컨테이너 주소가 아니다. 콜백 필터는
     * 경로로 매칭하므로 두 값이 달라도 흐름이 성립하며, redirect URI를 요청 헤더에서 유도하지
     * 않는다는 계약이 여기서 실제로 검증된다.
     */
    static final String PUBLIC_BASE_URL = "https://bokrilog.test";

    private static final Front FRONT = Front.start();

    /** 스텁이 실행 시점에 만든 client id. 소스에 자격정보 모양 리터럴을 두지 않는다. */
    static final String CLIENT_ID = FRONT.stub.clientId();

    static final String CLIENT_SECRET = FRONT.stub.clientSecret();

    private OidcStubConfiguration() {}

    /** 스텁을 가리키는 인증 구성. {@code AuthFlowTestBase}의 {@code @DynamicPropertySource}가 부른다. */
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("bokrilog.auth.public-base-url", () -> PUBLIC_BASE_URL);
        registry.add("bokrilog.auth.google.client-id", () -> CLIENT_ID);
        registry.add("bokrilog.auth.google.client-secret", () -> CLIENT_SECRET);
        registry.add("bokrilog.auth.google.issuer-uri", OidcStubConfiguration::issuer);
        registry.add("bokrilog.auth.google.authorization-uri", () -> issuer() + "/authorize");
        registry.add("bokrilog.auth.google.token-uri", () -> issuer() + "/token");
        registry.add("bokrilog.auth.google.jwk-set-uri", () -> issuer() + "/jwks");
    }

    /** 이 스텁이 주장하는 {@code iss}. 정상 흐름의 ID Token이 담는 값이다. */
    static String issuer() {
        return FRONT.stub.issuer();
    }

    /**
     * 계획대로 응답할 authorization code를 만들고 그 인가 요청에 귀속시킨다.
     *
     * @param authorizationRequest 서버가 만든 인가 요청의 쿼리 파라미터. 스텁이 여기서
     *     {@code code_challenge}·{@code redirect_uri}·{@code client_id}를 붙들어 두고 토큰 교환에서
     *     다시 확인한다. 귀속 없이 code를 발급할 수 있게 두면 PKCE 결합이 깨져도 시험이 통과한다
     */
    static String issueCode(TokenPlan plan, Map<String, String> authorizationRequest) {
        return FRONT.stub.issueCode(plan, authorizationRequest);
    }

    /** JDK 내장 HTTP 서버로 도는 표면. 시험 JVM에 하나만 뜬다. */
    private static final class Front {

        private final DevOidcStub stub;

        private Front(DevOidcStub stub) {
            this.stub = stub;
        }

        static Front start() {
            try {
                // 포트 0은 커널이 고른다. 고정 포트를 쓰면 병렬 실행이나 사용 중인 포트에서
                // 실패한다. 루프백에만 바인딩해 시험이 바깥으로 열리지 않게 한다.
                HttpServer server =
                        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                // `create`가 이미 포트를 바인딩했으므로 `start` 앞에서도 실제 포트를 알 수 있다.
                // 스텁의 `iss`는 그 주소여야 제공자 등록의 `issuer-uri`와 한 쌍이 된다.
                DevOidcStub stub =
                        new DevOidcStub("http://127.0.0.1:" + server.getAddress().getPort());
                Front front = new Front(stub);
                server.createContext("/jwks", front::handleJwks);
                server.createContext("/token", front::handleToken);
                server.setExecutor(null);
                server.start();
                return front;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private void handleJwks(HttpExchange exchange) throws IOException {
            respond(exchange, this.stub.jwks());
        }

        private void handleToken(HttpExchange exchange) throws IOException {
            String body =
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(
                    exchange,
                    this.stub.token(
                            DevOidcStub.parseForm(body),
                            // client 자격정보는 `client_secret_basic`으로 온다.
                            exchange.getRequestHeaders().getFirst("Authorization")));
        }

        private static void respond(HttpExchange exchange, DevOidcStub.Response response)
                throws IOException {
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
