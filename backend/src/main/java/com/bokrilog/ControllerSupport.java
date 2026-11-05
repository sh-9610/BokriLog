package com.bokrilog;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * 루트 패키지의 HTTP 표면(컨트롤러·{@code OperatorGate} 필터·{@code AuthFlowHandlers} 인증 처리기)이 함께 쓰는 판독·응답
 * 빌더 <b>한 벌</b>(Epic 4 회고 AI-8 ①).
 *
 * <p>각자 들고 있던 세션 사용자 판독({@code currentUserId}), SENSITIVE 접근 판정({@code allowedUser}), correlation id와
 * JSON·{@code no-store} 오류 응답 빌더의 사본이 이것 하나다. 새 컨트롤러는 사본을 만들지 않고 이것을 부른다 —
 * {@code ControllerSupportTest}가 운영 소스를 훑어 사본이 다시 생기지 않게 막는다.
 *
 * <p><b>판정은 고르지 않는다.</b> 미인증을 404로 낼지 403으로 낼지, SENSITIVE를 볼지 WRITE를 볼지, 본문 타입이 공통
 * {@link ApiErrorHandler.ApiError}인지 전용 record인지는 각 호출자의 계약이다. 여기 있는 것은 모든 호출자가 같은 값을
 * 내는 판독과 헤더 규칙뿐이고, {@link #allowedUser}는 없는 경로와 같은 404로 답하는 SENSITIVE 경로만 쓴다.
 *
 * <p>{@code ApiErrorHandler}의 자체 응답은 여기를 쓰지 않는다 — 그쪽에는 {@code no-store}가 없고
 * {@code GET /api/status}의 빈 {@code Cache-Control}을 시험이 고정했다.
 */
final class ControllerSupport {

    private ControllerSupport() {}

    /**
     * 지금 요청 주체의 내부 {@code user_id}. {@code AuthFlowHandlers}가 로그인 성공 시 심은 인증의 이름이고(세션 행의
     * {@code principal_name}), 클라이언트가 보낸 값이 아니다. 인증이 없거나 익명이거나 이름이 UUID가 아니면 {@code null}이다
     * — 예외를 밖으로 내보내지 않는다. 그 {@code null}을 404로 낼지 403으로 낼지는 호출자가 정한다.
     *
     * <p><b>이중 방어.</b> 인증이 필요한 접두사는 인가 필터({@code AuthSecurityConfig})가 이미 막으므로 이 값이
     * {@code null}인 요청은 지금 배선에서는 대개 핸들러에 도달하지 않는다 — 미인증 요청의 실제 답은 인가 필터의 401이다.
     * 그럼에도 핸들러가 주체를 다시 읽고 {@code null}을 거절하는 검사를 지우지 않는다. 인가 규칙이 실수로 넓어지거나 좁아지는
     * 날 이 검사가 마지막 말을 한다.
     */
    static @Nullable UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * 세션에 저장된 인증 주체의 내부 {@code user_id}. {@link #currentUserId()}와 달리 요청의 thread-local 인증이 아니라 세션 속성을 읽는다 —
     * 재인증을 요구받은(표시된) 세션은 guard 필터가 thread-local 인증을 지우므로 그 세션의 주체를 알 길이 이것뿐이다. 세션 확인 응답의
     * 삭제 진행 표지(Story 5.1)만 쓴다 — 이 값으로 접근을 허용하지 않는다. 없거나 읽을 수 없거나 UUID가 아니면 {@code null}이다.
     */
    static @Nullable UUID storedSessionUserId(@Nullable HttpSession session) {
        if (session == null) {
            return null;
        }
        Object stored;
        try {
            stored = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        } catch (IllegalStateException invalidated) {
            return null;
        }
        if (!(stored instanceof SecurityContext context) || context.getAuthentication() == null) {
            return null;
        }
        try {
            return UUID.fromString(context.getAuthentication().getName());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * 자기 계정에 SENSITIVE 접근이 허용된 세션 사용자. 미인증·삭제가 시작된 계정·쓸 수 없는 계정은 {@code null}이고 호출자는
     * 없는 경로와 같은 404로 답한다. 판정은 {@code identity} 공개 API 하나다.
     */
    static @Nullable UUID allowedUser(AccountAccessApi accounts) {
        UUID userId = currentUserId();
        if (userId == null
                || accounts.decide(userId, userId, AccountAccessKind.SENSITIVE) != AccessDecision.ALLOWED) {
            return null;
        }
        return userId;
    }

    /** 진입점이 준 요청 식별자를 {@link ApiErrorHandler#correlationIdOf(String)}의 규칙으로 거른 값. */
    static String correlationIdOf(HttpServletRequest request) {
        return ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER));
    }

    /** 공통 오류 본문을 JSON·{@code no-store}로 낸다. */
    static ResponseEntity<ApiErrorHandler.ApiError> error(HttpStatus status, ApiErrorHandler.ApiError body) {
        return noStoreJson(status, body);
    }

    /** 공통 오류 본문을 이 요청의 correlation id로 만들어 JSON·{@code no-store}로 낸다. */
    static ResponseEntity<ApiErrorHandler.ApiError> error(HttpStatus status, String code, String message,
            HttpServletRequest request) {
        return error(status, new ApiErrorHandler.ApiError(code, message, correlationIdOf(request)));
    }

    /** 전용 오류 record도 같은 헤더 규칙(JSON·{@code no-store})만 빌린다. 본문 모양은 호출자의 계약이다. */
    static <T> ResponseEntity<T> noStoreJson(HttpStatus status, T body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }
}
