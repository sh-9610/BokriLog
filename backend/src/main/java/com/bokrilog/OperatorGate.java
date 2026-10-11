package com.bokrilog;

import com.bokrilog.operations.OperatorPermission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 운영자 표면의 게이트(Story 3.1 AC-1).
 *
 * <p>{@code /api/operator/**}의 <b>모든</b> 요청이 컨트롤러 매핑보다 먼저 여기를 지난다. 운영자가 아니거나 그 경로의
 * 권한이 없으면 없는 경로와 같은 404다 — {@code sendError}가 오류 디스패치를 지나 {@code ApiErrorHandler}의 본문을
 * 만들므로 {@code /api/operator/nope}의 404와 글자까지 같다. 게이트가 없으면 매핑된 경로의 잘못된 메서드가 405로 답해
 * 경로의 존재를 비운영자에게 알려 준다.
 *
 * <p>경로별 권한: {@code /api/operator/commands/**}·{@code /api/operator/restrictions}(Story 3.2)·
 * {@code /api/operator/notices}(Story 3.5)·{@code /api/operator/inquiries}(Story 3.6)는 {@code command},
 * {@code /api/operator/audit}는 {@code audit}, 그 밖
 * (operation 조회)은 운영자이기만 하면 된다. command마다 더 요구하는 권한(감사 내보내기의 {@code audit})은 서비스가 다시
 * 본다. 판정은 요청마다 allowlist를 다시 푼다({@link OperatorAccess}) — 회수가 다음 요청에 바로 반영된다.
 *
 * <p>인가 필터 <b>뒤</b>에 놓인다. 미인증은 거기서 401로 끝나고, CSRF·{@code Origin} 실패는 그 앞에서 403으로 끝난다 —
 * 두 응답은 어느 보호 경로에서나 같아 운영자 표면의 존재를 알려 주지 않는다.
 */
final class OperatorGate extends OncePerRequestFilter {

    static final String COMMANDS_PATH = AuthSecurityConfig.OPERATOR_API_PATH + "/commands";

    static final String AUDIT_PATH = AuthSecurityConfig.OPERATOR_API_PATH + "/audit";

    private final OperatorAccess access;

    /** 권한을 잃은 운영자의 남은 무장을 정리하는 자리. 게이트는 부르기만 한다. */
    private final OperatorCommandService commands;

    OperatorGate(OperatorAccess access, OperatorCommandService commands) {
        this.access = access;
        this.commands = commands;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AuthSecurityConfig.isOperatorPath(AuthSecurityConfig.applicationPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID userId = currentUserId();
        if (!this.access.permits(userId, requiredFor(AuthSecurityConfig.applicationPath(request)))) {
            abandonRevokedArming(request, userId);
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 확정·취소가 권한 없음으로 끊길 때 그 사용자의 운영 무장을 정리한다. 응답은 그대로 같은 404다 — 정리의 유무가
     * 응답에 드러나지 않는다. 정리가 실패해도 404는 같다(남은 허가는 5분 뒤 만료된다).
     */
    private void abandonRevokedArming(HttpServletRequest request, @Nullable UUID userId) {
        String uri = AuthSecurityConfig.applicationPath(request);
        if (userId == null || !"POST".equals(request.getMethod()) || !uri.startsWith(COMMANDS_PATH + "/")
                || !(uri.endsWith("/" + OperatorController.CONFIRM_SEGMENT)
                        || uri.endsWith("/" + AuthSecurityConfig.CANCEL_SEGMENT))) {
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        try {
            this.commands.abandonRevoked(session, userId,
                    ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER)));
        } catch (RuntimeException ignored) {
            // 응답은 같은 404다. 남은 무장은 다음 준비가 덮고, 허가는 5분 뒤 만료된다.
        }
    }

    /** 경로의 권한. 접두사 비교에 {@code /}를 붙여 {@code /api/operator/auditx} 같은 값이 걸리지 않게 한다. */
    static Set<OperatorPermission> requiredFor(String uri) {
        if (uri.equals(COMMANDS_PATH) || uri.startsWith(COMMANDS_PATH + "/")) {
            return Set.of(OperatorPermission.COMMAND);
        }
        if (uri.equals(OperatorController.RESTRICTIONS_PATH)
                || uri.startsWith(OperatorController.RESTRICTIONS_PATH + "/")) {
            return Set.of(OperatorPermission.COMMAND);
        }
        if (uri.equals(OperatorController.NOTICES_PATH) || uri.startsWith(OperatorController.NOTICES_PATH + "/")) {
            return Set.of(OperatorPermission.COMMAND);
        }
        if (uri.equals(OperatorController.INQUIRIES_PATH)
                || uri.startsWith(OperatorController.INQUIRIES_PATH + "/")) {
            return Set.of(OperatorPermission.COMMAND);
        }
        if (uri.equals(AUDIT_PATH) || uri.startsWith(AUDIT_PATH + "/")) {
            return Set.of(OperatorPermission.AUDIT);
        }
        return Set.of();
    }

    private static @Nullable UUID currentUserId() {
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
}
