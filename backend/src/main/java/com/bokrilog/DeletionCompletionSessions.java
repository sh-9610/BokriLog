package com.bokrilog;

import com.bokrilog.identity.AccountDeletionApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 완료 확인 전용 세션의 판독(Story 5.4). 삭제를 확정한 요청 세션은 삭제가 끝나는 커밋에서 사용자 ID·인증 문맥 없이 삭제 요청 ID 하나로만
 * 이어진 세션이 된다(identity {@link AccountDeletionApi#erasePayload}). 그 세션은 <b>인증되지 않은</b> 세션이다 — 인증 주체가 없으므로 모든
 * 보호 경로가 미인증과 같고, 이 판독이 여는 것은 셋뿐이다: 세션 확인 응답의 완료 표지({@code AuthSessionController}), 완료 조회와 확정
 * 재전송의 거부({@code AccountDeletionController}·인가 규칙 {@code AuthSecurityConfig}). 로그아웃은 세션에 남긴 CSRF 토큰으로 기존 경로를
 * 그대로 지난다.
 *
 * <p>판독은 요청이 들고 온 세션 ID 하나로 identity에 묻는다 — 세션을 새로 만들지 않는다. 일반 세션·세션 없는 요청은 빈 값이다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다(세션은 API 프로세스에만 있다).
 */
@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
class DeletionCompletionSessions {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DeletionCompletionSessions.class);

    private final AccountDeletionApi accounts;

    DeletionCompletionSessions(AccountDeletionApi accounts) {
        this.accounts = accounts;
    }

    /**
     * 이 요청의 세션이 완료 확인 전용 세션이면 그 삭제 요청 ID. 아니면 빈 값. <b>미인증 세션만</b> 읽는다 — 완료 세션에서 다른 identity로
     * 로그인한 세션은 인증 성공이 연결을 지우고({@link #forget}), 그 정리가 실패했더라도 인증된 세션은 완료 조회를 받지 않는다.
     */
    Optional<String> requestIdOf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || isAuthenticated(SecurityContextHolder.getContext().getAuthentication())) {
            return Optional.empty();
        }
        String sessionId;
        try {
            sessionId = session.getId();
        } catch (IllegalStateException invalidated) {
            return Optional.empty();
        }
        return this.accounts.completionRequestOfSession(sessionId);
    }

    /**
     * 인증 성공(세션 ID 회전) 때 이 요청 세션의 완료 연결을 지운다 — 회전 전(요청이 들고 온 쿠키)·후 ID 둘로 찾는다(회전은 요청 끝에 저장소에
     * 반영된다). 최선 노력이다: 실패해도 로그인을 막지 않는다(판독은 미인증 세션만 한다). 실패는 예외의 단순 이름 한 줄만 남긴다.
     */
    void forget(HttpServletRequest request) {
        List<String> ids = new ArrayList<>();
        String requested = request.getRequestedSessionId();
        if (requested != null) {
            ids.add(requested);
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                ids.add(session.getId());
            } catch (IllegalStateException invalidated) {
                // 무효화된 세션이다 — 요청이 들고 온 ID만 본다.
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        try {
            this.accounts.forgetCompletionOfSessions(ids);
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("exception", failure.getClass().getSimpleName())
                    .log("account deletion completion link not cleared");
        }
    }

    private static boolean isAuthenticated(@Nullable Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
