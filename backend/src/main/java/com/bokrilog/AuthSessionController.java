package com.bokrilog;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import org.springframework.security.web.csrf.CsrfToken;
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
     * <p>{@code null} 필드를 직렬화하지 않는다. 미인증 응답의 본문이 이 스토리 전후로 글자까지
     * 같아야 하기 때문이다 — 그 모양은 {@code AuthSessionContractTest}와
     * {@code verify-same-origin.sh}가 전문 동등성으로 고정한다. 미인증 응답에 빈 토큰 필드를
     * 실으면 그 필드의 존재 자체가 계약에 하나 더 늘고, 화면이 볼 이유도 없다.
     *
     * @param authenticated 서버 세션이 주체를 결정했는가
     * @param destination 목적지의 종류({@code entry}·{@code onboarding}·{@code dashboard}). 경로가
     *     아니라 종류다. 화면이 아는 경로는 자기 라우팅 표에 이미 있고, 서버가 경로 문자열을
     *     내보내면 그 값이 리다이렉트 입력으로 쓰일 자리가 생긴다.
     * @param csrfToken 세션 귀속 CSRF 토큰. 인증된 세션에만 실린다. 상태 변경 요청
     *     ({@code POST /api/auth/logout})이 이 값을
     *     {@code AuthSecurityConfig#CSRF_HEADER_NAME} 헤더로 되돌려 보낸다. 쿠키 저장소를 쓰지
     *     않는 구성에서 클라이언트가 토큰을 얻는 유일한 경로이며, 그래서 브라우저가 갖는 쿠키가
     *     세션 하나로 유지된다. 이 값은 세션에 귀속하므로 다른 세션에서는 통하지 않고, 응답의
     *     {@code no-store}와 진입점의 {@code Referrer-Policy: no-referrer}가 유출 경로를 닫는다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionState(boolean authenticated, String destination, @Nullable String csrfToken) {}

    @GetMapping(path = AuthSecurityConfig.SESSION_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionState> session(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated =
                authentication != null
                        && authentication.isAuthenticated()
                        && !(authentication instanceof AnonymousAuthenticationToken);

        /*
         * 폐기된 세션과 재인증을 요구받은 세션은 여기 오기 전에 미인증으로 수렴한다.
         * `AuthFlowHandlers`의 세션 guard 필터가 `SecurityContextHolderFilter` 뒤에서 판정하고,
         * 그 결과가 위 `authenticated`에 그대로 반영된다. 이 컨트롤러에 두 상태를 구분하는
         * 분기가 없는 것이 곧 "응답으로 구분되지 않는다"는 계약이다.
         */
        String destination =
                authenticated
                        ? AuthFlowHandlers.destinationKind(sessionAttribute(request))
                        : AuthFlowHandlers.destinationKind(null);

        return ResponseEntity.ok()
                /*
                 * 보안 응답 헤더는 Nginx가 소유하지만 캐시 지시는 이 응답 하나의 성질이다.
                 * 공유 캐시나 브라우저 뒤로 가기가 이 값을 되살리면 로그아웃·세션 만료 뒤에도
                 * 화면이 인증된 상태를 주장한다. 이제 이 응답에는 CSRF 토큰도 실리므로 저장하지
                 * 않는다는 지시가 한 겹 더 필요하다.
                 */
                .cacheControl(CacheControl.noStore())
                .body(new SessionState(authenticated, destination, csrfToken(request, authenticated)));
    }

    /**
     * 이 세션의 CSRF 토큰. 미인증이면 {@code null}이다.
     *
     * <p>미인증에서 부르지 않는 것이 중요하다. {@code CsrfFilter}가 요청 속성에 얹어 두는 값은
     * 지연 토큰이고, {@code getToken()}이 그 자리에서 토큰을 만들어 저장소에 쓴다. 세션 저장소
     * 구현은 세션이 없으면 저장을 건너뛰지만({@code AuthSecurityConfig}), 그렇더라도 미인증
     * 응답에 실을 값이 아니다 — 세션에 귀속되지 않은 토큰은 어떤 상태 변경도 통과시키지 못하고,
     * 매 요청 새 값이라 응답을 캐시 불가로 만드는 이유만 늘린다.
     *
     * <p>빈 값도 부재로 다룬다. {@code @JsonInclude(NON_NULL)}은 {@code NON_EMPTY}가 아니므로 빈
     * 문자열은 {@code "csrfToken":""}으로 실려 나가고, 클라이언트는 그 값으로 어떤 상태 변경도
     * 통과시킬 수 없다. 필드가 있으면 토큰을 받았다는 뜻이어야 하므로 이 접기가 컨트롤러의 일이다.
     */
    private static @Nullable String csrfToken(HttpServletRequest request, boolean authenticated) {
        if (!authenticated) {
            return null;
        }
        Object token = request.getAttribute(CsrfToken.class.getName());
        if (!(token instanceof CsrfToken csrf)) {
            return null;
        }
        String value = csrf.getToken();
        return value == null || value.isBlank() ? null : value;
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
