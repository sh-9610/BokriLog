package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 상태 확인 API와 오류 계약의 HTTP 시험.
 *
 * <p>MockMvc가 아니라 실제 서블릿 컨테이너에 진짜 HTTP 요청을 보낸다. 이 스토리가 고정하려는 것은
 * 컨트롤러의 반환값이 아니라 밖으로 나가는 바이트다. 405 처리, 알 수 없는 경로 처리, 프레임워크
 * 기본 오류 본문 차단은 모두 컨트롤러 바깥의 디스패치 경로에서 결정되므로 그 경로를 통과시켜야
 * 시험이 의미를 가진다.
 *
 * <p>클라이언트는 JDK 내장 {@link HttpClient}다. 시험 전용 HTTP 클라이언트를 하나 더 들이지 않는다.
 *
 * <p>데이터베이스·제공자 자격정보를 요구하지 않는다. 이 시험이 통과하는 것 자체가 그 계약의 증거다.
 *
 * <p>{@link OutputCaptureExtension}은 로그 누출 시험에만 쓴다. 오류 계약은 응답 본문만이 아니라
 * 로그에도 걸리며, 로그는 응답보다 오래 남고 더 많은 곳으로 복제된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class StatusApiTest {

    /**
     * 응답 본문과 로그 어디에도 나와서는 안 되는 조각들. 프레임워크 기본 오류 본문·스택·내부 경로가
     * 새는 경로는 대부분 이 중 하나를 먼저 드러낸다.
     */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "org.springframework",
            "jakarta.servlet",
            "java.lang.",
            "Whitelabel",
            "\"trace\"",
            "\"path\"",
            "\"timestamp\"",
            "\"exception\"",
            "Exception");

    /**
     * 분류하지 못한 예외의 메시지. 실제 예외 메시지가 어떤 경로로든 밖으로 나오는지 보려면 그 값이
     * 다른 어떤 것과도 겹치지 않아야 한다. 자격정보 모양을 쓰지 않는다(gitleaks).
     */
    private static final String UNCLASSIFIED_EXCEPTION_MESSAGE = "누출-감지용-표식-4f1c8a";

    /** 시험 전용 경로. 운영 코드가 아니라 이 시험 클래스의 중첩 설정만 등록한다. */
    private static final String BOOM_PATH = "/api/__test__/boom";

    private static final String CONTAINER_ERROR_PATH = "/api/__test__/container-error";

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpResponse<String> send(String method, String path, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        for (int index = 0; index + 1 < headers.length; index += 2) {
            builder.header(headers[index], headers[index + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertNoLeak(String body) {
        for (String fragment : FORBIDDEN_FRAGMENTS) {
            assertThat(body).doesNotContain(fragment);
        }
    }

    /** 오류 본문의 correlation ID를 꺼낸다. 형식이 어긋나면 빈 문자열이 나와 단정이 실패한다. */
    private static String correlationIdOf(String body) {
        int marker = body.indexOf("\"correlationId\":\"");
        if (marker < 0) {
            return "";
        }
        int start = marker + "\"correlationId\":\"".length();
        int end = body.indexOf('"', start);
        return end < 0 ? "" : body.substring(start, end);
    }

    @Test
    void 상태_확인은_고정된_최소_필드만_돌려준다() throws Exception {
        HttpResponse<String> response = send("GET", "/api/status");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        // 시각·버전·호스트명·빌드 정보가 붙지 않았음을 문자열 동등성으로 고정한다.
        assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void 알_수_없는_API_경로는_공통_오류_계약을_돌려준다() throws Exception {
        HttpResponse<String> response = send("GET", "/api/nope");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
        assertThat(response.body()).contains("\"message\":\"요청한 경로를 찾을 수 없습니다.\"");
        assertThat(correlationIdOf(response.body())).isNotEmpty();
        // 요청 경로를 그대로 되돌려주면 그 자체가 반사 경로가 된다.
        assertThat(response.body()).doesNotContain("/api/nope");
        assertNoLeak(response.body());
    }

    @Test
    void 알_수_없는_비_API_경로도_같은_오류_계약을_돌려준다() throws Exception {
        HttpResponse<String> response = send("GET", "/nope");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
        assertNoLeak(response.body());
    }

    @Test
    void 잘못된_메서드는_405와_같은_오류_계약을_돌려준다() throws Exception {
        HttpResponse<String> response = send("POST", "/api/status");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(response.body()).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
        assertThat(response.body()).contains("\"message\":\"요청한 방식으로는 처리할 수 없습니다.\"");
        assertThat(correlationIdOf(response.body())).isNotEmpty();
        assertNoLeak(response.body());
    }

    @Test
    void 잘못된_메서드_응답은_허용_메서드를_알려준다() throws Exception {
        // RFC 9110은 405에 `Allow`를 필수로 정한다. 오류 본문을 우리 계약으로 바꾸면서
        // 프레임워크가 채워 넣은 프로토콜 헤더까지 버리면 클라이언트는 무엇이 되는지 알 수 없다.
        HttpResponse<String> response = send("POST", "/api/status");

        assertThat(response.headers().firstValue("Allow").orElse("")).contains("GET");
    }

    @Test
    void 받아들일_수_없는_표현_요청은_클라이언트_오류_code를_돌려준다() throws Exception {
        // 컨테이너·프레임워크가 분류한 4xx(400·406·415)가 서버 오류로 뒤집히면 클라이언트는
        // 재시도 여부를 판단할 근거를 잃는다. 상태는 4xx인데 code가 INTERNAL_ERROR인 응답을 막는다.
        HttpResponse<String> response = send("GET", "/api/status", "Accept", "text/plain");

        assertThat(response.statusCode()).isEqualTo(406);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(response.body()).contains("\"code\":\"BAD_REQUEST\"");
        assertThat(response.body()).contains("\"message\":\"요청이 올바르지 않습니다. 입력을 확인해 주세요.\"");
        assertThat(correlationIdOf(response.body())).isNotEmpty();
        assertNoLeak(response.body());
    }

    @Test
    void 분류하지_못한_예외는_500과_같은_오류_계약을_돌려주고_아무것도_흘리지_않는다(CapturedOutput output)
            throws Exception {
        HttpResponse<String> response =
                send("GET", BOOM_PATH, ApiErrorHandler.REQUEST_ID_HEADER, "boomTrace123");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(response.body())
                .isEqualTo("{\"code\":\"INTERNAL_ERROR\","
                        + "\"message\":\"요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.\","
                        + "\"correlationId\":\"boomTrace123\"}");
        assertThat(response.body()).doesNotContain(UNCLASSIFIED_EXCEPTION_MESSAGE);
        assertThat(response.body()).doesNotContain(BOOM_PATH);
        assertNoLeak(response.body());

        // 로그에는 correlation ID만 남는다. 예외 메시지도 스택 프레임도 남기지 않는다.
        assertThat(output.getAll()).contains("boomTrace123");
        assertThat(output.getAll()).doesNotContain(UNCLASSIFIED_EXCEPTION_MESSAGE);
        assertThat(output.getAll()).doesNotContain("IllegalStateException");
        assertThat(output.getAll()).doesNotContain("at com.bokrilog.");
        assertThat(output.getAll()).doesNotContain("at org.springframework.");
    }

    @Test
    void 진입점이_준_요청_식별자를_correlation_ID로_그대로_쓴다() throws Exception {
        HttpResponse<String> response =
                send("GET", "/api/nope", ApiErrorHandler.REQUEST_ID_HEADER, "abc123DEF456");

        assertThat(correlationIdOf(response.body())).isEqualTo("abc123DEF456");
    }

    @Test
    void 조작된_요청_식별자는_그대로_되돌려주지_않는다() throws Exception {
        HttpResponse<String> response = send(
                "GET", "/api/nope", ApiErrorHandler.REQUEST_ID_HEADER, "../../etc/passwd");

        String correlationId = correlationIdOf(response.body());
        assertThat(correlationId).isNotEmpty();
        assertThat(correlationId).doesNotContain("..");
        assertThat(correlationId).doesNotContain("/");
    }

    @Test
    void 컨테이너가_디스패치한_오류는_그_상태와_code를_그대로_유지한다() throws Exception {
        // 진짜 ERROR 디스패치를 지나게 한다. `/error`를 직접 부르면 컨테이너가 넣는
        // `ERROR_STATUS_CODE` 속성이 없어, 그 속성을 읽는 분기가 한 번도 실행되지 않는다.
        // 그 분기가 죽으면 404가 500으로 바뀌어도 아무 시험이 실패하지 않는다.
        HttpResponse<String> response = send("GET", CONTAINER_ERROR_PATH);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
        assertThat(response.body()).contains("\"message\":\"요청한 경로를 찾을 수 없습니다.\"");
        assertThat(correlationIdOf(response.body())).isNotEmpty();
        assertThat(response.body()).doesNotContain(CONTAINER_ERROR_PATH);
        assertNoLeak(response.body());
    }

    @Test
    void 프레임워크_오류_경로를_직접_불러도_같은_오류_계약을_돌려준다() throws Exception {
        // 상태 정보가 없는 요청이다. 알 수 없는 상태를 성공이나 빈 데이터로 위장하지 않고
        // 500으로 답한다.
        HttpResponse<String> response = send("GET", "/error");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(response.body()).contains("\"code\":\"INTERNAL_ERROR\"");
        assertThat(correlationIdOf(response.body())).isNotEmpty();
        assertNoLeak(response.body());
    }

    /**
     * 시험 전용 엔드포인트.
     *
     * <p>운영 코드에 이 경로를 두지 않는다. 이 클래스는 시험 소스에만 있고, 시험 클래스 안에 중첩되어
     * 있어 {@code @SpringBootApplication}의 컴포넌트 스캔 대상에서도 제외된다(Spring Boot의
     * {@code TestTypeExcludeFilter}가 시험 클래스와 그 중첩 클래스를 걸러낸다). 그래서 이 두 경로는
     * 이 시험 클래스의 컨텍스트에서만 존재한다.
     *
     * <p>여기 없으면 검증할 수 없는 것이 두 가지다. 하나는 분류하지 못한 예외의 500 경로이고,
     * 다른 하나는 서블릿 컨테이너가 만든 오류의 ERROR 디스패치 경로다. 둘 다 실제 HTTP로 지나가야
     * 의미가 있다.
     */
    @TestConfiguration
    static class TestOnlyEndpointConfiguration {

        @Bean
        TestOnlyEndpoints testOnlyEndpoints() {
            return new TestOnlyEndpoints();
        }
    }

    @RestController
    static class TestOnlyEndpoints {

        /** {@code @ExceptionHandler(Exception.class)}가 잡는 경로. */
        @GetMapping(BOOM_PATH)
        String boom() {
            throw new IllegalStateException(UNCLASSIFIED_EXCEPTION_MESSAGE);
        }

        /**
         * 컨테이너가 오류 디스패치를 만들게 한다. {@code sendError}는 서블릿 컨테이너가 등록된
         * 오류 페이지({@code /error})로 다시 디스패치하게 만들며, 그때 원래 상태 코드가
         * {@code RequestDispatcher.ERROR_STATUS_CODE} 속성으로 실린다.
         */
        @GetMapping(CONTAINER_ERROR_PATH)
        void containerError(HttpServletResponse response) throws IOException {
            response.sendError(HttpStatus.NOT_FOUND.value());
        }
    }
}
