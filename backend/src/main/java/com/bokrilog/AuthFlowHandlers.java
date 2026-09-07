package com.bokrilog;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 인증 흐름의 시작·성공·실패·종료 경계.
 *
 * <p>복귀 목적지는 서버가 가진 닫힌 허용 목록뿐이다. 클라이언트가 보낸 값은 그 목록에 있는지만
 * 확인하고, 없으면 버린다. 외부 주소는 목록에 오를 수 없으므로 리다이렉트 대상이 될 수 없다.
 *
 * <p>세션 폐기의 본체가 하나다({@link #destroySession}). 로그아웃·절대 수명 초과·실패한 흐름의
 * 정리가 모두 그 하나를 지나므로 "행 삭제·{@code SecurityContext} 정리·쿠키 만료 지시" 셋 중
 * 하나가 빠진 경로가 생기지 않는다. 세 경로의 차이는 <b>무엇을 폐기 대상으로 보는가</b>뿐이고,
 * 그 판정은 각 진입점에 있다.
 *
 * <p>성공한 흐름은 세션에 내부 {@code user_id}만 남긴다. Spring Security가 만든 인증 결과에는
 * ID Token 원문과 claim이 들어 있고, 세션은 Spring Session JDBC를 통해 PostgreSQL로 간다. 그것을
 * 그대로 두면 "ID Token은 검증 즉시 폐기한다"가 구현으로 성립하지 않는다. 그래서 성공 직후
 * 인증 주체를 내부 식별자 하나로 바꿔 다시 저장한다.
 *
 * <p>실패는 인증 화면으로 수렴한다. 표지는 닫힌 두 값({@code cancelled}·{@code failed})뿐이고
 * 계정 존재 여부·삭제 여부·검증 실패의 종류를 드러내지 않는다. 원인을 응답에도 로그에도 남기지
 * 않는다.
 */
@Component
@ConditionalOnWebApplication(type = Type.SERVLET)
class AuthFlowHandlers {

    /** 인증 화면. 정적 SPA가 origin 루트에서 제공한다. */
    static final String ENTRY_PATH = "/";

    /** 신규 사용자의 목적지. */
    static final String ONBOARDING_PATH = "/onboarding";

    /** 기존 사용자의 기본 목적지. */
    static final String DASHBOARD_PATH = "/dashboard";

    /**
     * 복귀 목적지 허용 목록.
     *
     * <p>닫힌 집합이다. 목록에 없는 값은 검사할 필요조차 없이 버려지므로, 경로 정규화·스킴 검사·
     * 호스트 비교 같은 판정이 애초에 존재하지 않는다. 라우트가 늘 때 사람이 한 줄을 더하는 비용을
     * 지불하는 대신, 판정 논리에 구멍이 생길 자리를 없앤다.
     */
    private static final Set<String> ALLOWED_DESTINATIONS = Set.of(ONBOARDING_PATH, DASHBOARD_PATH);

    /** 인증 시작이 기록한 요청 목적지. 성공하면 지운다. */
    static final String REQUESTED_DESTINATION_ATTRIBUTE = "bokrilog.auth.requested-destination";

    /** 확정된 목적지. {@code GET /api/auth/session}이 종류만 보고한다. */
    static final String DESTINATION_ATTRIBUTE = "bokrilog.auth.destination";

    /** 인증 화면이 읽는 실패 표지의 쿼리 이름. */
    static final String FAILURE_MARKER_PARAMETER = "auth";

    /** 사용자가 취소했거나 동의를 거부했다. 인증 화면이 재시도 경로를 보여 준다. */
    static final String FAILURE_MARKER_CANCELLED = "cancelled";

    /**
     * 그 밖의 모든 실패.
     *
     * <p>제공자 오류, 토큰 엔드포인트 장애, {@code state} 불일치·재사용·만료, ID Token 검증 실패,
     * 삭제가 시작된 계정의 세션 거부가 모두 이 하나로 접힌다. 값을 나누는 순간 그 표지가 계정의
     * 존재나 상태를 알려 주는 신호가 된다.
     */
    static final String FAILURE_MARKER_FAILED = "failed";

    /**
     * 인증 전 흐름 세션의 유휴 수명(초).
     *
     * <p>미인증 {@code GET /api/auth/authorize/google} 한 번마다 세션 행이 하나 생긴다. Spring
     * Security가 authorization request를 HTTP 세션에 담기 때문이며, 그 저장소를 바꾸면 브라우저에
     * 두는 값이 세션 쿠키 하나라는 계약이 깨진다. 그래서 저장소는 그대로 두고 수명으로 경계를
     * 만든다 — 인가 왕복은 초 단위로 끝나므로 5분이면 정상 흐름에 넉넉하고, 익명 호출자가
     * 세션 테이블을 무제한 키우는 경로는 닫힌다. 만료된 행은 Spring Session JDBC의 정리 작업이
     * 지운다.
     *
     * <p>로그인이 성공하면 {@link #onSuccess}가 구성된 세션 수명으로 되돌린다.
     */
    static final int FLOW_SESSION_TIMEOUT_SECONDS = 300;

    private final AuthSessionService authSessionService;

    /**
     * 세션 저장소.
     *
     * <p>실패한 흐름의 세션 행을 실제로 지우기 위해 필요하다. {@code HttpSession#invalidate()}만으로는
     * 부족하다 — Spring Session은 무효화를 "지금 세션 ID로 삭제"로 옮기는데, 성공 핸들러에 오기 전에
     * 세션 고정 방어가 ID를 이미 회전시켰고 그 회전은 아직 저장소에 반영되지 않았다. 그래서 삭제가
     * 아무 행에도 맞지 않고 회전 전 ID를 가진 행이 그대로 남는다.
     */
    private final SessionRepository<? extends Session> sessions;

    private final RedirectStrategy redirects = new DefaultRedirectStrategy();

    /**
     * 성공 직후 인증 주체를 바꿔 쓰기 위한 저장소.
     *
     * <p>{@code AbstractAuthenticationProcessingFilter}는 성공 핸들러를 부르기 전에 자기 인증
     * 결과를 이미 저장한다. 그 결과에 ID Token이 들어 있으므로 같은 요청 안에서 덮어써야 한다.
     * Spring Session의 기본 flush 시점은 요청 끝이라, 덮어쓴 뒤에는 마지막 값 하나만 데이터베이스로
     * 간다 — ID Token은 저장소에 닿지 않는다.
     */
    private final SecurityContextRepository securityContexts = new HttpSessionSecurityContextRepository();

    /**
     * 세션 상태 판정의 유일한 소유자.
     *
     * <p>절대 수명과 재인증 요구 표시를 이 클래스가 다시 해석하지 않는다. 여기서 하는 일은 그
     * 판정을 요청 경로에 배선하고 결과에 따라 폐기 본체를 부르는 것뿐이다.
     */
    private final SessionGuard guard;

    AuthFlowHandlers(
            AuthSessionService authSessionService,
            SessionRepository<? extends Session> sessions,
            SessionGuard guard) {
        this.authSessionService = authSessionService;
        this.sessions = sessions;
        this.guard = guard;
    }

    /**
     * 구성된 세션 유휴 수명.
     *
     * <p>이 클래스가 인증 전 흐름 세션의 수명을 짧게 낮추므로({@link #FLOW_SESSION_TIMEOUT_SECONDS})
     * 로그인 성공 시 원래 값으로 되돌려야 한다. 그 값을 여기 다시 적지 않는다 — 저장소가 만드는
     * 새 세션이 구성된 기본값({@code spring.session.timeout})을 이미 들고 있으므로 그것을 읽으면
     * 정의가 설정 파일 한 곳으로 남는다. 이 호출은 세션 객체만 만들고 저장하지 않는다.
     */
    private Duration configuredSessionTimeout() {
        return this.sessions.createSession().getMaxInactiveInterval();
    }

    /**
     * 인증 시작 요청의 전처리.
     *
     * <p>두 가지를 한다. 이미 인증된 세션의 로그인 시작을 거절하고, 요청 목적지가 허용 목록에
     * 있으면 세션에 기록한다. 두 일이 같은 요청의 같은 지점에 있어 필터 하나로 둔다.
     */
    Filter authorizationStartFilter() {
        return new OncePerRequestFilter() {

            @Override
            protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                if (!isAuthorizationStart(request)) {
                    filterChain.doFilter(request, response);
                    return;
                }

                if (isAuthenticated(SecurityContextHolder.getContext().getAuthentication())) {
                    /*
                     * 이미 인증된 세션이다. 새 흐름을 열지 않는다.
                     *
                     * 열면 같은 브라우저에서 두 번째 신규 가입이 시작될 수 있고, 그 흐름의 콜백이
                     * 기존 세션을 다른 계정으로 갈아탄다. 대신 지금 세션의 목적지로 보낸다.
                     */
                    redirects.sendRedirect(request, response, currentDestination(request));
                    return;
                }

                String requested = allowedDestination(request.getParameter("next"));
                if (requested != null) {
                    request.getSession().setAttribute(REQUESTED_DESTINATION_ATTRIBUTE, requested);
                } else {
                    // 값이 없거나 목록에 없다. 버리고, 앞선 흐름이 남긴 기록도 지운다.
                    // 여기서 세션을 새로 만들지 않는다. 만들면 목적지 없는 요청마다 세션 행이 생긴다.
                    HttpSession session = request.getSession(false);
                    if (session != null) {
                        session.removeAttribute(REQUESTED_DESTINATION_ATTRIBUTE);
                    }
                }
                filterChain.doFilter(request, response);
                /*
                 * 흐름 세션의 수명을 낮춘다.
                 *
                 * 세션을 만드는 것은 이 필터가 아니라 뒤따르는 authorization request 필터이므로
                 * 체인이 돌아온 뒤여야 그 세션이 존재한다. Spring Session의 요청 wrapper는 요청
                 * 끝에 세션을 저장하고 그 저장이 이 필터보다 바깥에 있으므로, 여기서 바꾼 값이
                 * 세션 행에 그대로 반영된다.
                 */
                capFlowSessionLifetime(request);
            }
        };
    }

    /**
     * 인증 전 흐름 세션에만 짧은 수명을 준다.
     *
     * <p>이미 인증된 주체가 실린 세션은 건드리지 않는다. 이 필터는 인증된 세션의 로그인 시작을
     * 위에서 이미 거절하므로 여기 오는 세션은 인증 전이지만, 판정을 한 곳에 두어 조건이 갈라질
     * 자리를 없앤다.
     */
    private static void capFlowSessionLifetime(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && !carriesEstablishedPrincipal(session)) {
            session.setMaxInactiveInterval(FLOW_SESSION_TIMEOUT_SECONDS);
        }
    }

    /**
     * 세션 수명·의심 세션 판정을 요청 경로에 배선한다.
     *
     * <p>{@code SecurityContextHolderFilter} 뒤에 놓인다({@code AuthSecurityConfig}). 그 순서가
     * 두 상태의 차이를 만드는 지점이다.
     *
     * <ul>
     *   <li>절대 수명 초과 — 그 요청에서 세션을 폐기한다. 행이 사라지고 쿠키에 만료 지시가
     *       실린다. 그 뒤의 상태 변경은 세션과 함께 사라진 CSRF 토큰 때문에 403으로 수렴한다.
     *   <li>재인증 요구 — <b>행은 남기고</b> 이 요청의 thread-local 인증만 지운다.
     *       {@code SecurityContextHolderFilter}는 컨텍스트를 자동으로 저장하지 않으므로 이 정리가
     *       세션에 기록되지 않고, 1.8의 재인증이 회전시킬 대상이 그대로 보존된다. 이 세션의 상태
     *       변경은 여기서 직접 막는다 — 토큰은 세션에 그대로 남아 있으므로 CSRF 검사만으로는
     *       걸리지 않는다.
     * </ul>
     *
     * <p>재인증 요구에는 예외가 둘 있고 둘 다 이유가 다르다.
     *
     * <ul>
     *   <li><b>로그아웃은 통과한다.</b> 상태를 없애기만 하는 요청이므로 표시된 세션에도 허용되어야
     *       한다. 막으면 그 세션은 스스로를 끝낼 방법이 없고, 저장된 {@code SecurityContext}와 CSRF
     *       토큰을 실은 행이 유휴 만료까지 남는다.
     *   <li><b>인증 흐름 요청에서는 인증을 지우지 않는다.</b> 인증 시작과 콜백은 safe GET이라 위
     *       403에 걸리지 않는다. 여기서 thread-local을 비우면 {@link #authorizationStartFilter}의
     *       "이미 인증된 세션은 새 흐름을 열지 않는다" 판정이 성립하지 않고, 그 흐름의 콜백이
     *       {@link #onSuccess}에서 <b>같은 세션 행에</b> 새 주체를 심는다 — 다른 계정으로
     *       로그인하면 그 행의 {@code principal_name}이 갈아탄다. 인증을 남겨 두면 그 판정이
     *       거절로 답하고, 세션 확인은 여전히 미인증이다(그 경로는 인증 흐름이 아니므로 위 정리를
     *       그대로 지난다).
     * </ul>
     *
     * <p>두 경우의 외부 관측은 같다. 세션 확인은 어느 쪽에서도 미인증으로 답하고 보호 경로의 상태
     * 변경은 어느 쪽에서도 403 {@code FORBIDDEN} 하나다. 응답으로 둘을 구분할 수 없다 — 구분되면
     * 그 차이가 곧 세션의 이력을 알려 주는 신호가 된다.
     */
    Filter sessionGuardFilter() {
        return new OncePerRequestFilter() {

            @Override
            protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    /*
                     * 세션을 새로 만들지 않는다. 판정에 필요한 두 값은 요청이 이미 들고 온
                     * 세션에서 읽고, 세션이 없는 요청에는 판정할 대상 자체가 없다.
                     */
                    SessionGuard.Verdict verdict =
                            guard.evaluate(
                                    session.getCreationTime(),
                                    session.getAttribute(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE));
                    switch (verdict) {
                        case EXPIRED -> destroySession(request);
                        case REAUTH_REQUIRED -> {
                            if (AuthSecurityConfig.isProtectedRequest(request)
                                    && !AuthSecurityConfig.isLogoutRequest(request)) {
                                // 미인증 상태 변경과 같은 하나의 코드다. 사유를 남기지 않는다.
                                SecurityContextHolder.clearContext();
                                response.sendError(HttpStatus.FORBIDDEN.value());
                                return;
                            }
                            if (!isAuthenticationFlow(request)) {
                                SecurityContextHolder.clearContext();
                            }
                        }
                        case ACTIVE -> {
                            // 쓸 수 있는 세션이다. 아무것도 하지 않는다.
                        }
                    }
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    /**
     * 로그아웃이 부르는 세션 폐기.
     *
     * <p>{@code LogoutFilter}는 세션 귀속 CSRF 토큰과 동일 출처 {@code Origin} 검사를 통과한
     * 요청에만 도달한다({@code AuthSecurityConfig}의 필터 순서). 즉 여기 오는 요청은 자기 세션의
     * 토큰을 들고 있다는 뜻이고, 그래서 조건 없이 그 세션을 지운다 — 남의 세션이 여기 실릴 경로가
     * 없다. 실패 경로({@link #discardSession})가 조건을 두는 이유와 정확히 반대다: 그쪽은 미인증
     * 최상위 GET이라 남의 쿠키가 실릴 수 있다.
     *
     * <p>204를 돌려주기 전에 이 핸들러가 먼저 돈다. 폐기가 실패한 요청이 로그아웃 완료로 보고되는
     * 순서를 만들지 않는다.
     */
    LogoutHandler logoutHandler() {
        return (request, response, authentication) -> destroySession(request);
    }

    AuthenticationSuccessHandler successHandler() {
        return (request, response, authentication) -> onSuccess(request, response, authentication);
    }

    AuthenticationFailureHandler failureHandler() {
        return (request, response, exception) -> onFailure(request, response, exception);
    }

    private void onSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        Optional<AuthSessionService.AuthSession> session = establish(authentication);
        if (session.isEmpty()) {
            /*
             * 검증은 통과했지만 세션을 만들 수 없다. 삭제가 시작된·끝난 계정, 경합에서 진 흐름이
             * 여기로 온다. 어느 쪽인지 구분되지 않는 하나의 실패로 수렴한다.
             */
            converge(request, response, FAILURE_MARKER_FAILED);
            return;
        }

        AuthSessionService.AuthSession established = session.get();
        String destination =
                established.firstLogin()
                        ? ONBOARDING_PATH
                        : Optional.ofNullable(
                                        allowedDestination(
                                                attribute(request, REQUESTED_DESTINATION_ATTRIBUTE)))
                                .orElse(DASHBOARD_PATH);

        /*
         * 세션 ID는 이미 회전했다. `AbstractAuthenticationProcessingFilter`가 성공 핸들러를 부르기
         * 전에 세션 고정 방어 전략(`changeSessionId`)을 실행하며, Spring Session의 요청 wrapper가
         * 그 호출을 세션 행의 ID 갱신으로 옮긴다.
         */
        HttpSession httpSession = request.getSession();
        httpSession.removeAttribute(REQUESTED_DESTINATION_ATTRIBUTE);
        /*
         * 재인증 요구 표시를 지운다.
         *
         * 세션 고정 방어는 `changeSessionId`이고 그 회전은 속성을 그대로 보존한다. 표시가 남으면
         * 방금 성공한 인증으로 만든 세션이 영구히 미인증으로 답하고, 사용자는 로그인에 성공해도
         * 진입 화면으로 되돌려진다 — 되살릴 방법이 없는 상태다. 확정된 인증이 곧 그 표시가 요구한
         * 것이므로 성공 시점이 그것을 지우는 자리다.
         */
        httpSession.removeAttribute(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE);
        httpSession.setAttribute(DESTINATION_ATTRIBUTE, destination);
        /*
         * 인증 전 흐름 동안 낮춰 둔 수명을 되돌린다. 흐름 세션의 5분이 그대로 남으면 로그인한
         * 사용자가 5분 유휴로 로그아웃된다.
         */
        httpSession.setMaxInactiveInterval((int) configuredSessionTimeout().toSeconds());

        /*
         * 인증 주체를 내부 식별자 하나로 바꾼다. 여기서 버리는 것은 ID Token 원문과 그 claim,
         * 그리고 제공자가 준 표시 정보 전부다. 세션이 결정하는 값은 `user_id` 하나뿐이라는 계약이
         * 이 세 줄이다.
         */
        SecurityContext minimal = SecurityContextHolder.createEmptyContext();
        minimal.setAuthentication(
                new PreAuthenticatedAuthenticationToken(
                        established.userId().toString(),
                        // 자격정보를 들고 있지 않다. 이 세션은 서버가 개설한 사실 자체가 근거다.
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(minimal);
        this.securityContexts.saveContext(minimal, request, response);

        this.redirects.sendRedirect(request, response, destination);
    }

    private Optional<AuthSessionService.AuthSession> establish(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof OidcUser user)) {
            // OIDC 흐름이 아니면 세션을 만들지 않는다. 이 배선에는 다른 인증 경로가 없다.
            return Optional.empty();
        }
        URL issuer = user.getIssuer();
        String subject = user.getSubject();
        if (issuer == null || subject == null) {
            // 검증이 두 값을 필수로 요구하므로 여기 오지 않는다. 그래도 접는다.
            return Optional.empty();
        }
        return this.authSessionService.establish(issuer.toExternalForm(), subject);
    }

    private void onFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        converge(request, response, marker(exception));
    }

    /**
     * 실패를 인증 화면으로 수렴시킨다.
     *
     * <p>실패한 흐름의 세션은 남기지 않는다. 그 세션 행이 그대로 남으면 "실패는 어떤 상태도
     * 바꾸지 않는다"가 저장소에서 성립하지 않는다. 반대로 <b>이미 개설된 세션은 손대지 않는다</b> —
     * 어느 쪽인지는 {@link #discardSession}이 가른다.
     *
     * <p>{@code clearContext()}는 thread-local만 비운다. 이 배치의
     * {@code SecurityContextHolderFilter}는 컨텍스트를 자동으로 저장하지 않으므로, 남의 세션이
     * 실린 요청에서도 저장된 {@code SecurityContext}는 그대로다.
     */
    private void converge(HttpServletRequest request, HttpServletResponse response, String marker)
            throws IOException {
        SecurityContextHolder.clearContext();
        discardSession(request);
        this.redirects.sendRedirect(
                request, response, ENTRY_PATH + "?" + FAILURE_MARKER_PARAMETER + "=" + marker);
    }

    /**
     * 이 로그인 흐름이 만든 인증 전 세션만 정리한다.
     *
     * <p><b>이미 인증된 주체가 실린 세션은 건드리지 않는다.</b> 콜백은 미인증 최상위 GET이고 세션
     * 쿠키는 {@code SameSite=Lax}라 최상위 내비게이션에 실린다. 그래서 외부 사이트가
     * {@code top.location}으로 콜백 URL을 열면 피해자의 브라우저가 자기 세션 쿠키를 들고 이 실패
     * 경로에 도착한다. 그 세션을 조건 없이 지우면 그것이 곧 크로스 사이트 강제 로그아웃이며
     * "실패는 어떤 상태도 바꾸지 않는다"의 반대다. 인증 여부는 세션에 저장된
     * {@code SecurityContext}로 판정한다 — 실패 핸들러에 오기 전에
     * {@code AbstractAuthenticationProcessingFilter}가 thread-local을 이미 비웠으므로 그쪽을 보면
     * 언제나 미인증으로 보인다.
     */
    private void discardSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            // 이 흐름은 세션을 만들지 않았다. 쿠키가 가리키는 행이 없다는 뜻이므로 지울 것도 없다.
            return;
        }
        if (carriesEstablishedPrincipal(session)) {
            // 남의 로그인이다. 이 실패는 그 세션의 사실이 아니다.
            return;
        }
        destroySession(request);
    }

    /**
     * 세션 폐기의 유일한 본체.
     *
     * <p>세 가지를 함께 만족시킨다 — 서버 세션 행 삭제, {@code SecurityContext} 정리, 세션 쿠키
     * 만료 지시. 셋 중 둘만 만족하는 상태를 로그아웃·만료 완료로 보고하지 않는다는 계약이 이
     * 메서드 하나로 성립한다. 쿠키 만료 지시는 {@code invalidate()}가 만든다 — Spring Session의
     * 요청 wrapper가 그 호출을 {@code HttpSessionIdResolver#expireSession}으로 옮겨 빈 값과
     * {@code Max-Age=0}을 응답에 싣는다.
     *
     * <p>행은 두 ID로 지운다. {@code invalidate()} 하나로는 행이 남는 경로가 있기 때문이다. Spring
     * Security가 인가 요청을 HTTP 세션에 담아 흐름을 시작하기만 해도 세션 행이 하나 생기고, 검증을
     * 통과한 뒤 세션 개설이 거부되는 경로에서는 그 앞에서 세션 고정 방어가 ID를 회전시켜 둔다.
     * 회전은 요청 끝의 저장 시점에 행의 {@code SESSION_ID}를 바꾸는 UPDATE인데, 무효화가 그 저장을
     * 건너뛰게 만들어 삭제는 새 ID로, 행은 옛 ID로 남는다. 그래서 두 ID를 모두 지운다.
     *
     * <p>옛 ID는 요청이 들고 온 쿠키의 값이다. 그 값 자체가 이 요청의 사실이므로 별도로 기억해 둘
     * 것이 없다. 두 삭제 모두 없는 행에 대해서는 아무 일도 하지 않는다.
     *
     * <p>{@code clearContext()}는 thread-local만 비운다. 이 배치의 {@code SecurityContextHolderFilter}
     * 는 컨텍스트를 자동으로 저장하지 않으므로 다른 세션의 저장된 값에는 닿지 않는다.
     */
    private void destroySession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        String requestedId = request.getRequestedSessionId();
        String currentId = session.getId();
        session.invalidate();
        deleteStoredSession(requestedId);
        deleteStoredSession(currentId);
        SecurityContextHolder.clearContext();
    }

    /**
     * 이 세션이 개설된 로그인의 주체를 들고 있는가.
     *
     * <p>{@link #onSuccess}가 확정한 세션에만 {@link PreAuthenticatedAuthenticationToken}이 실린다.
     * 진행 중인 OIDC 흐름의 중간 결과는 {@code OAuth2AuthenticationToken}이라 여기서 걸러지고, 그
     * 흐름이 세션을 얻지 못하면 정리 대상으로 남는다.
     *
     * <p>세션 속성을 직접 읽는다. {@code SecurityContextHolder}는 실패 핸들러 진입 전에 비워지고,
     * 저장소 조회를 쓰면 그 구현이 세션을 새로 만들 수 있다. 확인하려는 것은 "이 세션 행에 무엇이
     * 저장돼 있는가"이므로 저장된 값을 그대로 본다.
     */
    private static boolean carriesEstablishedPrincipal(HttpSession session) {
        Object stored =
                session.getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return stored instanceof SecurityContext context
                && context.getAuthentication() instanceof PreAuthenticatedAuthenticationToken token
                && isAuthenticated(token);
    }

    private void deleteStoredSession(@Nullable String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            this.sessions.deleteById(sessionId);
        }
    }

    /**
     * 실패 표지. 닫힌 두 값뿐이다.
     *
     * <p>사용자가 스스로 취소한 것만 갈라낸다. 그 하나는 화면이 "다시 시도"를 안내할 근거이고,
     * 계정의 존재나 상태를 드러내지 않는다. 나머지는 모두 같은 값이다.
     */
    private static String marker(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2Exception
                && OAuth2ErrorCodes.ACCESS_DENIED.equals(oauth2Exception.getError().getErrorCode())) {
            return FAILURE_MARKER_CANCELLED;
        }
        return FAILURE_MARKER_FAILED;
    }

    /** 지금 세션이 가진 목적지. 없으면 기본 대시보드다. */
    private static String currentDestination(HttpServletRequest request) {
        return Optional.ofNullable(allowedDestination(attribute(request, DESTINATION_ATTRIBUTE)))
                .orElse(DASHBOARD_PATH);
    }

    private static @Nullable String attribute(HttpServletRequest request, String name) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(name);
        return value instanceof String text ? text : null;
    }

    /**
     * 허용 목록에 있는 목적지만 돌려준다.
     *
     * @return 목록에 있으면 그 경로, 아니면 {@code null}
     */
    static @Nullable String allowedDestination(@Nullable String candidate) {
        return candidate != null && ALLOWED_DESTINATIONS.contains(candidate) ? candidate : null;
    }

    /** 확정된 목적지의 종류. 경로가 아니라 종류만 브라우저로 나간다. */
    static String destinationKind(@Nullable String destination) {
        if (ONBOARDING_PATH.equals(destination)) {
            return "onboarding";
        }
        if (DASHBOARD_PATH.equals(destination)) {
            return "dashboard";
        }
        return "entry";
    }

    private static boolean isAuthorizationStart(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && request.getRequestURI().startsWith(AuthSecurityConfig.AUTHORIZATION_BASE_URI + "/");
    }

    /**
     * 새 인증을 확정할 수 있는 요청인가.
     *
     * <p>인증 시작과 콜백 둘이다. 이 두 경로에서만 세션 guard가 thread-local 인증을 남겨 두고,
     * 그래서 {@link #authorizationStartFilter}의 거절 판정과 {@link #discardSession}의 "남의
     * 로그인이다" 판정이 표시된 세션에서도 성립한다. 콜백을 함께 넣는 것은 그 경로도 safe GET이라
     * 403에 걸리지 않고, 판정 하나가 두 경로 중 한쪽만 덮으면 나머지가 곧 계정 갈아타기 경로가
     * 되기 때문이다.
     */
    private static boolean isAuthenticationFlow(HttpServletRequest request) {
        return isAuthorizationStart(request)
                || ("GET".equals(request.getMethod())
                        && request.getRequestURI()
                                .startsWith(AuthSecurityConfig.CALLBACK_BASE_URI + "/"));
    }

    private static boolean isAuthenticated(@Nullable Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
