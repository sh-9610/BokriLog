package com.bokrilog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 개발·CI 스택의 local Bybit 표면.
 *
 * <p>{@link DevOidcStubConfig}와 같은 골격이다 — {@code @Profile} + <b>단일 {@link RouterFunction}
 * 빈</b> + 생성자 guard + 내부 base URL 분리. 기본 프로필에서는 이 클래스의 빈이 하나도 만들어지지
 * 않고 {@code /api/dev-bybit/**}가 노출되지 않는다. 라우팅이 빈 하나에 실려 있으므로 그 빈의 부재가
 * 곧 경로의 부재다.
 *
 * <p>스텁 논리는 여기 없다. {@link DevBybitStub}이 시나리오 선택과 응답을 모두 소유하고, 백엔드
 * 시험은 같은 클래스를 루프백 HTTP 서버에 올린다. 스텁을 두 벌 구현하지 않는다.
 *
 * <p>{@code /api} 아래에 둔다. Nginx가 이미 프록시하는 경로라 새 서비스·새 게시 포트·새 compose
 * 파일·CSP 확장이 하나도 필요 없다({@code DevOidcStubConfig}와 같은 근거).
 *
 * <p><b>이 경로를 브라우저가 부르지 않는다.</b> 제공자 API 호출은 백엔드가 서버 대 서버로 보내며,
 * 그래서 어댑터가 보는 주소는 공개 origin이 아니라 자기 서블릿 포트다. 그 값은 compose가
 * {@code bokrilog.source.bybit.base-url}로 준다.
 */
@Configuration(proxyBeanMethods = false)
@Profile(DevBybitStubConfig.PROFILE)
class DevBybitStubConfig {

    /** 이 스텁을 켜는 프로필. 개발·CI 스택만 활성화한다. */
    static final String PROFILE = "dev-bybit";

    /** 스텁 표면의 접두사. {@code /api}만 프록시하는 Nginx 블록이 그대로 받는다. */
    static final String BASE_PATH = "/api/dev-bybit";

    /**
     * 어댑터가 보는 제공자 base URL의 프로퍼티 이름.
     *
     * <p>같은 이름이 {@code SourceProperties}에도 있다. 두 계층이 한 계약을 나눠 갖는 자리이며
     * ({@code CSRF_HEADER_NAME}과 같은 종류의 중복) 아래 guard가 그 값을 읽어 "스텁 프로필인데
     * 스텁을 가리키지 않는다"를 기동에서 막는다. {@code source.internal}의 타입을 여기서 참조하면
     * 루트 → 모듈 내부 의존이 생기므로 이름만 공유한다.
     */
    private static final String BASE_URL_PROPERTY = "bokrilog.source.bybit.base-url";

    /** 스텁이 키에 묶어 응답할 운영 reserved IP. 어댑터가 요구하는 값과 같아야 한다. */
    private static final String RESERVED_IP_PROPERTY = "bokrilog.source.bybit.reserved-ip";

    private final String reservedIp;

    /**
     * 기동 guard.
     *
     * <p>이 프로필이 켜졌는데 어댑터가 스텁을 가리키지 않으면 기동을 거부한다. 통과시키면 스텁은
     * 떠 있는데 실제 제공자로 자격정보가 나가는 프로세스가 되고, 어느 쪽이 검증했는지 응답만으로
     * 알 수 없다({@code DevOidcStubConfig}의 guard와 같은 근거).
     *
     * <p>어느 값이 문제인지 메시지에 적지 않는다. 환경 변수 이름은 응답에도 로그에도 남기지 않는
     * 것이 이 저장소의 계약이고 기동 실패 로그도 로그다.
     */
    DevBybitStubConfig(Environment environment) {
        String baseUrl = environment.getProperty(BASE_URL_PROPERTY, "");
        if (!trimTrailingSlash(baseUrl).endsWith(BASE_PATH)) {
            throw new IllegalStateException(
                    "개발용 Bybit 스텁 프로필은 실제 제공자 주소와 함께 활성화할 수 없다. "
                            + "스텁과 실제 제공자 중 하나만 구성한다.");
        }
        String ip = environment.getProperty(RESERVED_IP_PROPERTY, "");
        if (ip.isBlank()) {
            throw new IllegalStateException(
                    "개발용 Bybit 스텁은 운영 reserved IP 없이 뜰 수 없다. "
                            + "IP binding을 검증하는 경로가 그 값 하나에 걸려 있다.");
        }
        this.reservedIp = ip.trim();
    }

    /**
     * 스텁의 HTTP 표면.
     *
     * <p>{@code @RestController}가 아니라 함수형 라우팅이다. 모든 경로가 빈 <b>하나</b>에 실려
     * 있어야 "기본 프로필에서 {@code /api/dev-bybit/**}가 노출되지 않는다"를 빈의 부재로 단정할 수
     * 있다({@code DevOidcStubConfig}와 같은 근거).
     */
    @Bean
    RouterFunction<ServerResponse> devBybitStubRoutes() {
        DevBybitStub stub = new DevBybitStub(this.reservedIp);
        RouterFunctions.Builder routes = RouterFunctions.route();
        for (String path :
                List.of(
                        DevBybitStub.QUERY_API_PATH,
                        DevBybitStub.ACCOUNT_INFO_PATH,
                        DevBybitStub.WALLET_BALANCE_PATH,
                        DevBybitStub.SERVER_TIME_PATH,
                        DevBybitStub.TRANSACTION_LOG_PATH,
                        DevBybitStub.INSTRUMENTS_INFO_PATH,
                        DevBybitStub.KLINE_PATH)) {
            routes.GET(BASE_PATH + path, request -> respond(stub, path, request));
        }
        return routes.build();
    }

    private static ServerResponse respond(DevBybitStub stub, String path, ServerRequest request) {
        DevBybitStub.Response response = stub.handle(path, headers(request), parameters(request));
        return ServerResponse.status(response.status())
                .contentType(MediaType.parseMediaType(response.contentType()))
                .body(response.body());
    }

    private static Map<String, String> headers(ServerRequest request) {
        Map<String, String> values = new LinkedHashMap<>();
        request
                .headers()
                .asHttpHeaders()
                .forEach(
                        (name, list) -> {
                            if (!list.isEmpty()) {
                                values.put(name, list.get(0));
                            }
                        });
        return values;
    }

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

    private static String trimTrailingSlash(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
