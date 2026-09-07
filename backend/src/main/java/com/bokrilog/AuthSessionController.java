package com.bokrilog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 표면의 HTTP 경계.
 *
 * <p>두 가지만 답한다. 로그인 상태 화면이 서버 세션으로 상태를 확인하는 경로 하나와, 인증 시작이
 * 성립하지 않을 때의 공통 오류 계약 하나다. 나머지(authorization request 발급, 콜백 검증)는
 * Spring Security의 필터가 서블릿보다 앞에서 처리하므로 이 컨트롤러에 오지 않는다.
 *
 * <p>{@code user_id}·{@code subject}·이메일·토큰을 응답에 담지 않는다. 세션이 유일한 {@code user_id}
 * 권위라는 계약은 그 값을 브라우저에 알려 주지 않는다는 뜻이기도 하다. 화면이 알아야 하는 것은
 * "인증됐는가"와 "어디로 가야 하는가" 둘뿐이다(AD-14, AD-20).
 *
 * <p>루트 패키지 컨트롤러 관용을 따른다 — package-private 클래스, 중첩 record, {@code produces} 명시
 * ({@code StatusController}).
 */
@RestController
@ConditionalOnWebApplication(type = Type.SERVLET)
class AuthSessionController {

    /**
     * 세션 상태.
     *
     * @param authenticated 서버 세션이 주체를 결정했는가
     * @param destination 목적지의 종류({@code entry}·{@code onboarding}·{@code dashboard}). 경로가
     *     아니라 종류다. 화면이 아는 경로는 자기 라우팅 표에 이미 있고, 서버가 경로 문자열을
     *     내보내면 그 값이 리다이렉트 입력으로 쓰일 자리가 생긴다.
     */
    record SessionState(boolean authenticated, String destination) {}

    @GetMapping(path = AuthSecurityConfig.SESSION_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionState> session(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated =
                authentication != null
                        && authentication.isAuthenticated()
                        && !(authentication instanceof AnonymousAuthenticationToken);

        String destination =
                authenticated
                        ? AuthFlowHandlers.destinationKind(sessionAttribute(request))
                        : AuthFlowHandlers.destinationKind(null);

        return ResponseEntity.ok()
                /*
                 * 보안 응답 헤더는 Nginx가 소유하지만 캐시 지시는 이 응답 하나의 성질이다.
                 * 공유 캐시나 브라우저 뒤로 가기가 이 값을 되살리면 로그아웃·세션 만료 뒤에도
                 * 화면이 인증된 상태를 주장한다.
                 */
                .cacheControl(CacheControl.noStore())
                .body(new SessionState(authenticated, destination));
    }

    /**
     * 인증 시작이 성립하지 않을 때의 답.
     *
     * <p>제공자가 구성돼 있으면 Spring Security의 authorization request 필터가 이 요청을 먼저 받아
     * 302로 끝낸다. 그래서 이 메서드에 도달하는 것은 두 경우뿐이다.
     *
     * <ul>
     *   <li>모르는 제공자 — 그 경로는 존재하지 않는다. 404다.
     *   <li>아는 제공자인데 자격정보가 구성되지 않았다 — 요청을 처리할 수 없다. 500이다.
     * </ul>
     *
     * <p>둘 다 {@code ApiErrorHandler}의 공통 오류 계약으로 나간다. {@code sendError}가 컨테이너의
     * ERROR 디스패치를 지나므로 본문의 정의는 여전히 한 곳뿐이고, 같은 상태 코드에 대해 Nginx가
     * 만드는 본문과도 글자까지 같다. 성공이나 빈 데이터로 위장하지 않으며, 어떤 값이 비어 있는지도
     * 알려 주지 않는다.
     *
     * <p>제공자 등록을 여기서 조회하지 않는다. 등록이 있으면 이 메서드에 도달하지 않으므로
     * 조회 결과는 언제나 "없음"이고, 그 값을 보는 분기는 아무도 지나지 않는다. 남는 판정은
     * "아는 제공자 이름인가" 하나다.
     */
    @GetMapping(AuthSecurityConfig.AUTHORIZATION_BASE_URI + "/{provider}")
    void authorizationUnavailable(
            @PathVariable String provider, HttpServletResponse response) throws IOException {
        if (!AuthSecurityConfig.GOOGLE_REGISTRATION_ID.equals(provider)) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    private static @Nullable String sessionAttribute(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(AuthFlowHandlers.DESTINATION_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
