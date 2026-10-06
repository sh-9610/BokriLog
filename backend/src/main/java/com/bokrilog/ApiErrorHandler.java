package com.bokrilog;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 오류 응답 계약.
 *
 * <p>이 애플리케이션이 내는 모든 오류는 아래 한 가지 모양이다. Nginx가 만드는 오류(502, 정적 404)도
 * 같은 모양을 돌려주므로 클라이언트는 오류가 어느 계층에서 났는지 몰라도 같은 방식으로 읽는다.
 *
 * <pre>{@code {"code":"NOT_FOUND","message":"요청한 경로를 찾을 수 없습니다.","correlationId":"..."}}</pre>
 *
 * <p>여기서 막는 것은 Spring의 기본 오류 본문이다. 기본값을 그대로 두면 whitelabel HTML,
 * ProblemDetail의 {@code detail}, {@code trace}, {@code path}가 응답에 실려 내부 경로와 스택이
 * 밖으로 나간다. {@code application.yml}의 {@code server.error.*}만으로는 {@code path}가 남으므로
 * 프레임워크의 오류 경로 자체를 아래 {@code ApiErrorPageController}로 대체한다.
 *
 * <p>오류는 성공이나 빈 데이터로 위장하지 않는다. 상태 코드는 실제 상태를 그대로 반영한다.
 */
@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {

    /**
     * 안정적인 machine code. 클라이언트가 분기 조건으로 쓰는 값이라 문자열을 바꾸지 않는다.
     * 새 code는 그것을 낼 엔드포인트가 생길 때 SPEC이 함께 추가한다.
     *
     * <p>같은 문자열이 {@code deploy/nginx/bokrilog.conf}의 named location에도 리터럴로 있다.
     * 두 계층이 한 계약을 나눠 갖기 때문이며, 두 본문이 실제로 같은지는
     * {@code deploy/compose/verify-same-origin.sh}가 응답을 서로 비교해 확인한다.
     */
    static final String CODE_BAD_REQUEST = "BAD_REQUEST";

    static final String CODE_NOT_FOUND = "NOT_FOUND";

    static final String CODE_METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

    /**
     * 인증되지 않은 요청. Story 1.4가 더했다.
     *
     * <p>{@code FORBIDDEN}과 갈라 두는 이유는 클라이언트가 할 일이 다르기 때문이다. 이 code는
     * "로그인하면 된다"이고 아래 {@code FORBIDDEN}은 "로그인해도 이 요청은 안 된다"다. 둘을
     * {@code BAD_REQUEST}로 뭉치면 화면이 로그인 안내와 거부 안내를 구분할 근거를 잃는다.
     *
     * <p>어느 쪽도 대상의 존재 여부를 드러내지 않는다. 타 사용자 대상과 미존재 대상은
     * {@code identity}의 판정에서 이미 같은 값이 되어 여기까지 같은 code로 온다.
     */
    static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";

    /** 인증됐지만 허용되지 않은 요청. CSRF 토큰·동일 출처 {@code Origin} 검증 실패도 이 code다. */
    static final String CODE_FORBIDDEN = "FORBIDDEN";

    static final String CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    /** 사용자에게 보이는 메시지. 원인을 특정하지 않고 다음 행동만 알려 준다. */
    static final String MESSAGE_BAD_REQUEST = "요청이 올바르지 않습니다. 입력을 확인해 주세요.";

    static final String MESSAGE_NOT_FOUND = "요청한 경로를 찾을 수 없습니다.";

    static final String MESSAGE_METHOD_NOT_ALLOWED = "요청한 방식으로는 처리할 수 없습니다.";

    /** 원인을 특정하지 않는다. 세션이 없는 것과 만료된 것을 구분해 알려 주지 않는다. */
    static final String MESSAGE_UNAUTHENTICATED = "로그인이 필요합니다. 다시 로그인해 주세요.";

    static final String MESSAGE_FORBIDDEN = "요청을 처리할 권한이 없습니다.";

    static final String MESSAGE_INTERNAL_ERROR = "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    /**
     * 멱등 키가 없거나 규칙에 맞지 않을 때의 메시지 <b>한 문장</b>(422 {@code IdempotencyKey.REQUIRED_CODE}).
     *
     * <p>규칙은 {@code com.bokrilog.sync.IdempotencyKey}가, 메시지는 여기가 소유한다. command마다 다른 문장을 두면
     * 같은 code에 메시지가 여섯이 되고 화면은 code로만 가르므로 그 차이는 아무것도 전하지 않는다.
     */
    static final String MESSAGE_IDEMPOTENCY_KEY_REQUIRED =
            "요청 키가 없거나 형식이 맞지 않아 요청을 받지 않았습니다. 화면을 새로 열어 다시 시도해 주세요.";

    /**
     * Nginx가 채워 넣는 요청 식별자. 있으면 그대로 쓴다. 진입점과 애플리케이션이 같은 값을 쓰지
     * 않으면 사용자가 화면에서 읽은 ID로 로그를 찾을 수 없다.
     */
    static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 응답 본문. 필드 순서가 곧 직렬화 순서이며 Nginx의 정적 JSON도 같은 순서를 쓴다. */
    record ApiError(String code, String message, String correlationId) {}

    /**
     * 프레임워크가 이미 분류한 예외(404·405·415·400 등)가 모두 이 지점을 지난다.
     * 본문 인자는 버린다. 그 값이 곧 Spring의 기본 오류 본문이다.
     *
     * <p>반면 헤더 인자는 그대로 실어 보낸다. 프레임워크가 이 자리에 넣는 것은 상태 코드가 요구하는
     * 프로토콜 헤더다. 405의 {@code Allow}가 대표적이며 RFC 9110이 필수로 정한 값이다. 이것을 버리면
     * 클라이언트는 어떤 메서드가 되는지 알 방법이 없다. 본문의 {@code Content-Type}만 아래에서
     * JSON으로 덮어써 오류 계약을 유지한다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorFor(statusCode, correlationId(request)));
    }

    /**
     * 프레임워크가 분류하지 못한 모든 예외. 예외 종류·메시지·스택을 응답에 싣지 않는다.
     *
     * <p>기본 로그에도 스택을 남기지 않는다. 스택 한 줄에 배포 경로·의존 라이브러리 구성·내부
     * 클래스 구조가 함께 실리고, 로그는 응답보다 오래 남으며 더 많은 곳으로 복제된다. 대신 응답과
     * 같은 correlation ID를 남겨 사용자가 신고한 ID로 요청을 특정할 수 있게 한다. 스택이 필요한
     * 조사에서는 {@code logging.level.com.bokrilog=debug}로 한 번만 열고 다시 닫는다.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnclassified(Exception ex, WebRequest request) {
        String correlationId = correlationId(request);
        logger.warn("처리하지 못한 요청 (correlationId=" + correlationId + ")");
        if (logger.isDebugEnabled()) {
            logger.debug("처리하지 못한 요청의 원인 (correlationId=" + correlationId + ")", ex);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorFor(HttpStatus.INTERNAL_SERVER_ERROR, correlationId));
    }

    /**
     * 상태 코드를 안정적인 machine code로 옮긴다.
     *
     * <p>4xx를 뭉뚱그려 {@code INTERNAL_ERROR}로 보내지 않는다. 컨테이너가 만든 400·406·415가
     * 그 경로로 들어오면 "요청이 잘못됐다"는 사실이 "서버가 고장났다"로 뒤집혀 클라이언트가 재시도
     * 여부를 판단할 근거를 잃는다. 404·405 밖의 클라이언트 오류는 {@code BAD_REQUEST}로 모은다.
     * 원인을 더 잘게 나누는 code는 그것을 낼 엔드포인트가 생길 때 SPEC이 함께 추가한다.
     *
     * <p>Story 1.4가 401·403을 그 규약대로 갈랐다. 인증 표면이 생기면서 두 상태를 실제로 내는
     * 경로가 만들어졌다({@code AuthSecurityConfig}의 entry point와 access denied handler).
     * 401·403은 Nginx가 이 구성에서 스스로 만들 수 없는 상태라 진입점의 {@code error_page} 목록에
     * 대응 항목이 없고, 두 계층이 같은 code를 나눠 갖지도 않는다.
     */
    private static ApiError errorFor(HttpStatusCode status, String correlationId) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return new ApiError(CODE_NOT_FOUND, MESSAGE_NOT_FOUND, correlationId);
        }
        if (status.value() == HttpStatus.METHOD_NOT_ALLOWED.value()) {
            return new ApiError(CODE_METHOD_NOT_ALLOWED, MESSAGE_METHOD_NOT_ALLOWED, correlationId);
        }
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            return new ApiError(CODE_UNAUTHENTICATED, MESSAGE_UNAUTHENTICATED, correlationId);
        }
        if (status.value() == HttpStatus.FORBIDDEN.value()) {
            return new ApiError(CODE_FORBIDDEN, MESSAGE_FORBIDDEN, correlationId);
        }
        if (status.is4xxClientError()) {
            return new ApiError(CODE_BAD_REQUEST, MESSAGE_BAD_REQUEST, correlationId);
        }
        return new ApiError(CODE_INTERNAL_ERROR, MESSAGE_INTERNAL_ERROR, correlationId);
    }

    private static String correlationId(WebRequest request) {
        return correlationIdOf(request.getHeader(REQUEST_ID_HEADER));
    }

    /**
     * 진입점이 준 값이 없으면 새로 만든다. 헤더는 클라이언트가 조작할 수 있으므로 길이를 제한하고
     * 안전한 문자만 남긴다. 조작된 값이 그대로 로그·응답에 들어가면 로그 위조 통로가 된다.
     *
     * <p>package-private이다. 자기 본문을 직접 만드는 컨트롤러가 하나 생겼고
     * ({@code BybitConnectionController}) 그 응답의 correlation ID도 이 규칙을 지나야 한다 — 두
     * 곳에서 만들면 진입점이 준 값을 한쪽만 존중하는 날이 온다.
     */
    static String correlationIdOf(@Nullable String headerValue) {
        if (headerValue == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = headerValue.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64 || !trimmed.matches("[A-Za-z0-9._-]+")) {
            return UUID.randomUUID().toString();
        }
        return trimmed;
    }

    /**
     * 프레임워크의 오류 경로({@code /error}) 대체.
     *
     * <p>{@code BasicErrorController}는 자기와 같은 타입의 bean이 없을 때만 등록된다. 여기서
     * {@code ErrorController}를 직접 구현해 그 자리를 차지한다. 필터·서블릿 컨테이너가 만든 오류나
     * {@code @ControllerAdvice}가 닿지 않는 오류도 이 경로를 지나므로, 이 대체가 없으면
     * {@code {"timestamp":...,"status":500,"error":...,"path":"/api/..."}}가 그대로 나간다.
     *
     * <p>같은 파일에 두는 이유는 이것이 위 advice와 하나의 계약이기 때문이다. 두 곳에서 오류 본문을
     * 만들면 한쪽만 바뀌어 모양이 갈라진다.
     */
    @Controller
    @RequestMapping("${server.error.path:/error}")
    static class ApiErrorPageController implements ErrorController {

        @RequestMapping
        @ResponseBody
        ResponseEntity<ApiError> handle(HttpServletRequest request) {
            HttpStatus status = statusOf(request);
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorFor(status, correlationIdOf(request.getHeader(REQUEST_ID_HEADER))));
        }

        private static HttpStatus statusOf(HttpServletRequest request) {
            Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            if (attribute instanceof Integer code) {
                HttpStatus resolved = HttpStatus.resolve(code);
                if (resolved != null) {
                    return resolved;
                }
            }
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }
}
