package com.bokrilog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 백엔드 시험이 쓰는 local Bybit 제공자.
 *
 * <p>스텁 논리는 여기 없다. {@link DevBybitStub}이 시나리오 선택과 응답을 모두 소유하고 이 클래스는
 * 그것을 루프백 HTTP 서버에 올리는 표면 하나다. 같은 논리가 개발·CI 스택에서는
 * {@code DevBybitStubConfig}를 통해 {@code /api/dev-bybit/**}에 올라간다 — 스텁을 두 번 만들지
 * 않는다({@code OidcStubConfiguration}과 같은 배치).
 *
 * <p>표면이 둘인 것은 포트 때문이다. 어댑터는 제공자 호출을 구성된 base URL로 보내는데, 이 시험들은
 * {@code RANDOM_PORT}로 뜨고 그 포트는 컨텍스트 refresh가 끝난 뒤에 정해진다. 어댑터는 그보다 먼저
 * 만들어지므로 시험에서는 애플리케이션 자신이 아니라 미리 뜬 이 서버를 가리켜야 한다.
 *
 * <p><b>자격정보 모양 리터럴이 없다.</b> 암호화 key ring은 시험 JVM이 실행 시점에 만들고 API
 * Secret도 각 시험이 만든다. API Key는 시나리오 선택자이지 비밀이 아니다.
 */
final class BybitStubConfiguration {

    /**
     * 이 스텁이 키에 묶어 응답할 운영 reserved IP.
     *
     * <p>문서용 주소(RFC 5737 TEST-NET-3)다. 시험 JVM이 실제로 쓰는 주소(루프백)와 다른 값인 것이
     * 의도다 — IP binding이 "호출자의 주소"가 아니라 "키에 등록된 주소"를 본다는 계약이 그래야
     * 검증된다.
     */
    static final String RESERVED_IP = "203.0.113.10";

    /** 시험이 쓰는 key ring의 버전 이름. 값이 아니라 이름이므로 리터럴로 둬도 된다. */
    static final String KEY_VERSION = "test";

    /** 회전을 관측하는 시험이 쓰는 두 번째(과거) 버전. */
    static final String PREVIOUS_KEY_VERSION = "test-previous";

    private static final Front FRONT = Front.start();

    /** 시험 JVM이 실행 시점에 만든 key ring. 소스에도 설정 파일에도 리터럴이 없다. */
    private static final String KEY_RING =
            KEY_VERSION + ":" + newKey() + "," + PREVIOUS_KEY_VERSION + ":" + newKey();

    private BybitStubConfiguration() {}

    /**
     * 스텁을 가리키는 source 구성. {@code AuthFlowTestBase}의 {@code @DynamicPropertySource}가 부른다.
     *
     * <p>새 {@code @DynamicPropertySource}를 만들지 않는다. 하위 클래스마다 같은 메서드를 다시 쓰면
     * 컨텍스트 customizer가 달라져 컨텍스트가 클래스마다 하나씩 뜨고 {@code backend-quality}의
     * wall-clock이 그만큼 늘어난다({@code OidcStubConfiguration}과 같은 근거).
     */
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("bokrilog.source.bybit.base-url", FRONT::origin);
        registry.add("bokrilog.source.bybit.reserved-ip", () -> RESERVED_IP);
        // 시험이 몇 초를 기다리지 않는다. 시간초과 축은 어댑터 단위 시험이 자기 서버로 본다.
        registry.add("bokrilog.source.bybit.request-timeout", () -> "3s");
        registry.add("bokrilog.source.credential.keys", () -> KEY_RING);
        registry.add("bokrilog.source.credential.active-key-version", () -> KEY_VERSION);
    }

    /** 그 시나리오의 API Key. 시험과 검증 스크립트가 같은 모양을 만든다. */
    static String apiKey(DevBybitStub.Scenario scenario, String uid) {
        return DevBybitStub.apiKey(scenario, uid);
    }

    /** 새 API Secret. 스텁은 값을 보지 않지만 서명이 계산돼야 하므로 비어 있으면 안 된다. */
    static String newSecret() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(24));
    }

    /**
     * 지금까지 스텁이 받은 요청 수.
     *
     * <p>"외부 호출 0건"을 관측하는 유일한 창이다. 저장 행 수만 세면 "거절돼서 저장되지 않았다"와
     * "제공자에 아예 가지 않았다"가 구분되지 않는데, admission의 계약은 뒤쪽이다 — 거절될 자격정보로
     * 제공자를 두드리지 않는다.
     *
     * <p>계수기가 시험 표면에만 있다. 운영 스텁 논리({@link DevBybitStub})에 두면 개발 스택이
     * 쓰지 않는 상태가 거기 남는다.
     */
    static int requestCount() {
        return Front.REQUESTS.get();
    }

    /** 계수기를 0으로 되돌린다. 시험 하나가 세는 구간을 자기가 정한다. */
    static void resetRequestCount() {
        Front.REQUESTS.set(0);
    }

    private static String newKey() {
        return Base64.getEncoder().encodeToString(randomBytes(32));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** JDK 내장 HTTP 서버로 도는 표면. 시험 JVM에 하나만 뜬다. */
    private static final class Front {

        /** 스텁이 받은 요청 수. 시험이 "외부 호출 0건"을 관측하는 값이다. */
        static final AtomicInteger REQUESTS = new AtomicInteger();

        private final String origin;

        private Front(String origin) {
            this.origin = origin;
        }

        String origin() {
            return this.origin;
        }

        static Front start() {
            try {
                // 포트 0은 커널이 고른다. 루프백에만 바인딩해 시험이 바깥으로 열리지 않게 한다.
                HttpServer server =
                        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                DevBybitStub stub = new DevBybitStub(RESERVED_IP);
                for (String path :
                        List.of(
                                DevBybitStub.QUERY_API_PATH,
                                DevBybitStub.ACCOUNT_INFO_PATH,
                                DevBybitStub.WALLET_BALANCE_PATH)) {
                    server.createContext(path, exchange -> handle(stub, path, exchange));
                }
                server.setExecutor(null);
                server.start();
                return new Front("http://127.0.0.1:" + server.getAddress().getPort());
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        private static void handle(DevBybitStub stub, String path, HttpExchange exchange)
                throws IOException {
            REQUESTS.incrementAndGet();
            Map<String, String> headers = new LinkedHashMap<>();
            exchange
                    .getRequestHeaders()
                    .forEach(
                            (name, values) -> {
                                if (!values.isEmpty()) {
                                    headers.put(name, values.get(0));
                                }
                            });
            DevBybitStub.Response response =
                    stub.handle(path, headers, query(exchange.getRequestURI().getRawQuery()));
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private static Map<String, String> query(String raw) {
            Map<String, String> values = new LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) {
                return values;
            }
            for (String pair : raw.split("&")) {
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                values.put(
                        URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
            return values;
        }
    }
}
