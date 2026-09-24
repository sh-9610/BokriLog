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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
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
 *
 * <p>재인증은 같은 왕복을 지나되 갈라진다(Story 1.8). 세션에 무장된 일회성 흐름
 * ({@link ReauthenticationFlow})이 성공·실패 핸들러를 로그인과 갈라 놓는다 — 계정을 만들지도
 * 바꾸지도 않고, 검증된 {@code (issuer, subject)}가 현재 사용자의 것인지와 제공자가 서명한
 * {@code auth_time}이 신선한지만 확인하며, 결과는 인증 화면이 아니라 무장 시각에 붙들어 둔
 * 목적지로 돌아간다. 재인증 표지도 닫힌 두 값({@code reauthCancelled}·{@code reauthFailed})이다.
 *
 * <p>연결은 <b>세 번째 흐름</b>이다(Story 1.10). 같은 왕복을 지나되 {@link LinkFlow}가 성공·실패
 * 핸들러를 다시 갈라 놓고, 그 분기는 계정을 만들지 않으면서 <b>매핑 하나를 더한다</b> — 허가 소비와
 * 같은 트랜잭션에서다({@code LinkService}). 재인증 무장에 섞지 않는 이유가 그것이며, 연결 표지도
 * 닫힌 두 값({@code linkCancelled}·{@code linkFailed})이다.
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
     * 로그인 수단 설정 화면.
     *
     * <p>연결 조작이 사는 유일한 자리이고, 연결 흐름의 복귀 경로도 이 상수 하나다 — 클라이언트가
     * 목적지를 보내지 않는다는 계약이 그 조작에서도 그대로 성립한다({@code LinkService}).
     *
     * <p>이 경로를 더할 때 <b>다섯 곳을 함께</b> 고친다 — 아래 {@link #ALLOWED_DESTINATIONS},
     * {@code deploy/nginx/bokrilog.conf}의 허용 목록 정규식, {@code frontend/src/App.tsx}의 경로 맵,
     * {@code frontend/src/session/useSessionState.ts}의 목적지 집합, 그리고
     * {@code frontend/src/content/auth.ts}의 {@code RETURN_DESTINATIONS}다. 한 곳만 고치면 증상이
     * 제각각이다: Nginx가 빠지면 404, {@code App.tsx}가 빠지면 진입 화면, 여기가 빠지면 복귀가
     * 대시보드로 접히고, 목적지 집합이 빠지면 타입이 막으며, {@code RETURN_DESTINATIONS}가 빠지면
     * 미인증 진입에서 {@code next}가 실리지 않는다. 다섯의 등가를 보는 검사는 아직 없다.
     */
    static final String SETTINGS_PATH = "/settings";

    /**
     * 복귀 목적지 허용 목록.
     *
     * <p>닫힌 집합이다. 목록에 없는 값은 검사할 필요조차 없이 버려지므로, 경로 정규화·스킴 검사·
     * 호스트 비교 같은 판정이 애초에 존재하지 않는다. 라우트가 늘 때 사람이 한 줄을 더하는 비용을
     * 지불하는 대신, 판정 논리에 구멍이 생길 자리를 없앤다.
     */
    private static final Set<String> ALLOWED_DESTINATIONS =
            Set.of(ONBOARDING_PATH, DASHBOARD_PATH, SETTINGS_PATH);

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
     * 사용자가 재인증을 취소했거나 동의를 거부했다.
     *
     * <p>로그인의 {@link #FAILURE_MARKER_CANCELLED}와 값을 나누는 이유는 목적지가 다르기
     * 때문이다. 재인증 실패는 인증 화면이 아니라 <b>붙들어 둔 목적지</b>로 돌아가므로 그 화면이
     * 읽을 표지가 따로 있어야 하고, 같은 값을 쓰면 인증 화면의 "다시 로그인해 주세요" 문장이
     * 이미 로그인한 사용자에게 렌더된다.
     */
    static final String FAILURE_MARKER_REAUTH_CANCELLED = "reauthCancelled";

    /**
     * 그 밖의 모든 재인증 실패.
     *
     * <p>타 사용자 식별자, 미연결 식별자, {@code auth_time} 부재·초과·미래, 제공자 바꿔치기,
     * 삭제가 시작된 계정, 제공자 오류가 모두 이 하나로 접힌다. 로그인 실패 표지를 닫힌 두 값으로
     * 둔 것과 같은 이유다 — 값을 나누는 순간 그 표지가 계정의 존재나 연결 상태를 알려 주는
     * 신호가 된다. 연결되지 않은 제공자를 고른 것도 다른 실패와 구분되지 않는다.
     */
    static final String FAILURE_MARKER_REAUTH_FAILED = "reauthFailed";

    /**
     * 사용자가 연결을 취소했거나 동의를 거부했다.
     *
     * <p>재인증 표지와 값을 나누는 이유는 문장이다. 두 조작은 같은 화면으로 돌아오지만 사용자가
     * 한 일이 다르고, 같은 값을 쓰면 "본인 확인을 끝내지 않았습니다"가 연결을 취소한 사람에게
     * 렌더된다.
     */
    static final String FAILURE_MARKER_LINK_CANCELLED = "linkCancelled";

    /**
     * 그 밖의 모든 연결 실패.
     *
     * <p>이미 내 계정에 연결된 제공자, 다른 계정에 연결된 식별자, 같은 제공자의 다른
     * {@code subject}, 제공자 바꿔치기, 허가 만료·이미 사용됨, 세션 불일치, 삭제가 시작된 계정,
     * 제공자 오류가 모두 이 하나로 접힌다. 로그인·재인증 표지를 닫힌 두 값으로 둔 것과 같은
     * 이유다 — 값을 나누는 순간 그 표지가 <b>다른 계정의 존재와 소유자</b>를 알려 주는 신호가 된다.
     */
    static final String FAILURE_MARKER_LINK_FAILED = "linkFailed";

    /**
     * 연결이 끝났다.
     *
     * <p>이 파일의 다른 표지와 달리 실패가 아니다. 성공에 표지를 다는 이유는 비대칭 때문이다 —
     * 화면은 시작을 {@code role="status"}로 알리는데 결과를 알릴 재료가 없으면 스크린리더
     * 사용자에게 그 조작은 끝나지 않은 것과 같다. 왕복 뒤의 문서는 새로 열리므로 "방금 연결했다"를
     * 아는 값은 주소의 표지 하나뿐이다.
     *
     * <p>무엇을 연결했는지는 말하지 않는다. 대상 제공자를 표지에 실으면 그 값이 주소·`Referer`·
     * 히스토리에 남고, 화면은 어차피 연결 상태를 다시 조회해 목록으로 보여 준다.
     */
    static final String NOTICE_MARKER_LINK_SUCCEEDED = "linkSucceeded";

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

    /**
     * 연결의 판정과 실행.
     *
     * <p>이 클래스는 흐름 경계만 소유한다. "재인증이 신선한가"·"허가가 살아 있는가"·"매핑을 더해도
     * 되는가"는 전부 그쪽이 답하며, 여기서 하는 일은 무장을 꺼내 그 호출에 넘기고 결과를
     * 리다이렉트로 옮기는 것뿐이다.
     */
    private final LinkService linkService;

    AuthFlowHandlers(
            AuthSessionService authSessionService,
            SessionRepository<? extends Session> sessions,
            SessionGuard guard,
            LinkService linkService) {
        this.authSessionService = authSessionService;
        this.sessions = sessions;
        this.guard = guard;
        this.linkService = linkService;
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
     * 인증 흐름 경계.
     *
     * <p>세 가지를 한다. 이미 인증된 세션의 로그인 시작과 <b>로그인 콜백</b>을 거절하고, 무장된
     * 재인증 흐름의 시작만 통과시키고, 요청 목적지가 허용 목록에 있으면 세션에 기록한다. 셋 다
     * 같은 요청의 같은 지점에서 갈리므로 필터 하나로 둔다. {@code CsrfFilter} 앞에 놓여
     * Spring Security의 인가 요청 필터와 로그인 필터보다 먼저 답한다.
     *
     * <p>콜백까지 보는 것이 Story 1.8의 변화다. 그전에는 인증된 세션이 진행 중인 authorization
     * request를 가질 수 없었다 — 시작이 여기서 거절됐으므로 콜백에는 대조할 {@code state}가 없고,
     * 그래서 콜백을 보지 않아도 계정 갈아타기 경로가 없었다. 재인증이 그 전제를 깨므로
     * ({@code POST /api/auth/reauthenticate/…} 뒤의 인가 시작은 인증된 세션에서 성립한다) 무장이
     * 없는 콜백은 이 자리에서 접는다. 접지 않으면 흐름이 만료된 뒤 도착한 콜백이
     * {@link #onSuccess}의 <b>로그인 분기</b>로 들어가 같은 세션 행에 다른 계정을 심을 수 있다.
     */
    Filter authenticationFlowFilter() {
        return new OncePerRequestFilter() {

            @Override
            protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                if (isAuthorizationCallback(request)) {
                    if (isAuthenticated(SecurityContextHolder.getContext().getAuthentication())) {
                        LinkFlow.Armed link = LinkFlow.peekIncludingExpired(request);
                        ReauthenticationFlow.Armed armed =
                                ReauthenticationFlow.peekIncludingExpired(request);
                        link =
                                callbackLinkFlow(
                                        link, armed, AuthSecurityConfig.callbackProvider(request));
                        if (link != null) {
                            if (!link.isLive(Instant.now())) {
                                /*
                                 * 연결 무장이 있었지만 늦었다. 재인증 무장의 같은 가지와 근거가
                                 * 같다 — 아무 설명 없이 튕기지 않도록 흐름만 버리고 붙들어 둔
                                 * 목적지로 표지와 함께 보낸다. 허가는 건드리지 않는다: 만료된
                                 * 허가는 데이터베이스가 판정하고, 살아 있는 허가는 다음 발급이
                                 * 무효화한다.
                                 */
                                LinkFlow.discard(request);
                                redirects.sendRedirect(
                                        request,
                                        response,
                                        link.returnPath()
                                                + "?"
                                                + FAILURE_MARKER_PARAMETER
                                                + "="
                                                + FAILURE_MARKER_LINK_FAILED);
                                return;
                            }
                            filterChain.doFilter(request, response);
                            return;
                        }
                        if (armed == null) {
                            /*
                             * 인증된 세션에 도착한, 무장 없는 콜백이다. 이 흐름은 이 세션의
                             * 사실이 아니므로 검증에 넘기지 않고 지금 목적지로 되돌린다. 세션은
                             * 건드리지 않는다 — 남의 사이트가 연 콜백일 수도 있고, 그때 세션을
                             * 바꾸는 것이 곧 크로스 사이트 계정 갈아타기다.
                             */
                            redirects.sendRedirect(request, response, currentDestination(request));
                            return;
                        }
                        if (!armed.isLive(Instant.now())) {
                            /*
                             * 무장이 있었지만 늦었다. 사용자가 제공자 화면에서 무장 수명을
                             * 넘긴 경우이고, 그 사실을 말하지 않으면 같은 패널로 아무 설명 없이
                             * 튕긴다. 흐름만 버리고 붙들어 둔 목적지로 표지와 함께 보낸다 —
                             * 세션은 건드리지 않으므로 사용자는 그 자리에서 다시 시작할 수 있다.
                             */
                            ReauthenticationFlow.discard(request);
                            redirects.sendRedirect(
                                    request,
                                    response,
                                    armed.returnPath()
                                            + "?"
                                            + FAILURE_MARKER_PARAMETER
                                            + "="
                                            + FAILURE_MARKER_REAUTH_FAILED);
                            return;
                        }
                    }
                    filterChain.doFilter(request, response);
                    return;
                }
                if (!isAuthorizationStart(request)) {
                    filterChain.doFilter(request, response);
                    return;
                }

                boolean armedFlow = false;
                if (isAuthenticated(SecurityContextHolder.getContext().getAuthentication())) {
                    /*
                     * 이미 인증된 세션이다. 무장된 흐름이 있고 그 흐름의 제공자로 시작하는
                     * 경우에만 통과시킨다. 흐름은 둘이다 — 재인증(Story 1.8)과 연결(Story 1.10).
                     *
                     * 조건 없이 열면 같은 브라우저에서 두 번째 신규 가입이 시작될 수 있고, 그
                     * 흐름의 콜백이 기존 세션을 다른 계정으로 갈아탄다. 반대로 조건 없이 막으면
                     * 재인증도 연결도 성립하지 않는다 — 표시된 세션은 CSRF 토큰도 받지 못하고
                     * 로그인 버튼은 여기서 거절당해 목적지로 되돌려지며, 그 목적지는 다시
                     * 미인증으로 답한다. 되살릴 방법이 없는 무한 왕복이 그 상태다.
                     *
                     * 무장을 소비하지 않는다. 판정은 콜백에서 하고, 여기서 지우면 그 판정이
                     * 볼 것이 사라진다.
                     */
                    String provider = AuthSecurityConfig.authorizationStartProvider(request);
                    LinkFlow.Armed link = LinkFlow.peek(request);
                    ReauthenticationFlow.Armed armed = ReauthenticationFlow.peek(request);
                    armedFlow =
                            (link != null && link.targetProvider().equals(provider))
                                    || (armed != null && armed.provider().equals(provider));
                    if (!armedFlow) {
                        redirects.sendRedirect(request, response, currentDestination(request));
                        return;
                    }
                }

                if (!armedFlow) {
                    String requested = allowedDestination(request.getParameter("next"));
                    if (requested != null) {
                        request.getSession()
                                .setAttribute(REQUESTED_DESTINATION_ATTRIBUTE, requested);
                    } else {
                        // 값이 없거나 목록에 없다. 버리고, 앞선 흐름이 남긴 기록도 지운다.
                        // 여기서 세션을 새로 만들지 않는다. 만들면 목적지 없는 요청마다 세션
                        // 행이 생긴다.
                        HttpSession session = request.getSession(false);
                        if (session != null) {
                            session.removeAttribute(REQUESTED_DESTINATION_ATTRIBUTE);
                        }
                    }
                }
                /*
                 * 무장된 흐름의 시작에서는 `next`를 보지 않는다.
                 *
                 * 목적지는 무장 시각에 이미 정해졌다. 재인증은 세션이 가진 확정 목적지 하나에서,
                 * 연결은 그 조작이 사는 화면 상수 하나에서 온다(`LinkService`). 여기서 쿼리를
                 * 읽으면 클라이언트가 보낸 값이 복귀 목적지가 되는 두 번째 경로가 열리고, 그것은
                 * "콜백이 들고 온 어떤 값도 목적지가 되지 않는다"를 무르는 것과 같은 크기의
                 * 구멍이다.
                 */
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
     * <p>재인증 요구에는 예외가 셋 있고 셋 다 이유가 다르다.
     *
     * <ul>
     *   <li><b>로그아웃은 통과한다.</b> 상태를 없애기만 하는 요청이므로 표시된 세션에도 허용되어야
     *       한다. 막으면 그 세션은 스스로를 끝낼 방법이 없고, 저장된 {@code SecurityContext}와 CSRF
     *       토큰을 실은 행이 유휴 만료까지 남는다.
     *   <li><b>재인증 시작도 통과한다(Story 1.8).</b> 표시가 요구하는 것이 정확히 그 요청이므로
     *       막으면 표시된 세션에 회복 경로가 없다. 이 요청이 만드는 상태는 세션 안의 일회성 흐름
     *       하나이고, 그 흐름은 같은 사용자의 검증된 재인증 콜백에서만 소비된다.
     *   <li><b>인증 흐름 요청에서는 인증을 지우지 않는다.</b> 인증 시작과 콜백은 safe GET이라 위
     *       403에 걸리지 않는다. 여기서 thread-local을 비우면 {@link #authenticationFlowFilter}의
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
                                    && !AuthSecurityConfig.isLogoutRequest(request)
                                    && !AuthSecurityConfig.isReauthenticationStart(request)) {
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
        /*
         * 무장된 흐름이 있으면 로그인 분기를 타지 않는다.
         *
         * `establish`는 미연결 식별자를 신규 가입으로 처리하는 경로다. 재인증이 그것을 지나면
         * "재인증 실패"여야 할 입력이 **새 계정**을 만들고 그 계정으로 세션이 갈아타며, 연결이
         * 그것을 지나면 연결하려던 식별자로 **두 번째 계정**이 생기고 사용자는 자기 계정을
         * 떠난다. 세 분기가 갈리는 자리는 여기 하나다.
         *
         * 어느 흐름이 이 콜백을 소비하는지는 `callbackBelongsToLink`가 가른다. 순서로 정하면
         * 살아 있는 연결 무장이 재인증 콜백을 삼킨다 — 두 흐름이 함께 무장된 상태는 평범한
         * 경로이기 때문이다(그 근거는 그 메서드의 javadoc이 소유한다).
         *
         * 소비는 어느 쪽 결과에서도 일어난다. 흐름은 콜백 하나로 끝나며 그것이 일회성이다.
         */
        VerifiedIdentity identity = verifiedIdentityOf(authentication);
        LinkFlow.Armed link =
                callbackLinkFlow(
                        LinkFlow.peek(request),
                        ReauthenticationFlow.peek(request),
                        identity == null ? null : identity.provider());
        if (link != null) {
            LinkFlow.discard(request);
            onLinkSuccess(request, response, identity, link);
            return;
        }

        ReauthenticationFlow.Armed armed = ReauthenticationFlow.consume(request);
        if (armed != null) {
            onReauthenticationSuccess(request, response, identity, armed);
            return;
        }

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

        writeEstablishedPrincipal(request, response, established.userId());

        this.redirects.sendRedirect(request, response, destination);
    }

    /**
     * 재인증 콜백의 판정.
     *
     * <p>더하는 판정은 둘뿐이다 — "이 식별자가 현재 사용자의 것인가"와 "제공자가 증명한 인증
     * 시각이 신선한가". 공통 OIDC 검증(PKCE, {@code state}·{@code nonce}, JWS·{@code iss}·
     * {@code aud}·{@code azp}·{@code exp})은 이 요청이 여기 오기 전에 Spring Security OAuth2
     * Client가 이미 했다.
     *
     * <p>제공자 일치를 먼저 본다. 무장이 google인데 kakao 콜백이 도착하면 판정할 것이 없다 —
     * 그 흐름의 사실이 아니다.
     *
     * <p>세션 ID는 이미 회전했다. 로그인과 같은 배선({@code sessionFixation().changeSessionId()})
     * 이 성공 핸들러 앞에서 돌기 때문이고, 그래서 재인증이 회전을 따로 만들지 않는다.
     *
     * <p>이 성공이 실행하는 파괴적 작업은 <b>하나도 없다</b>. 남기는 것은 세션의 재인증 시각
     * 하나이며 HTTP로 나가지 않는다. 그 값을 5분·일회성 허가로 바꾸는 것은 Story 1.9이고,
     * 파괴적 작업의 최종 확인은 그 작업을 만드는 스토리가 함께 만든다.
     */
    private void onReauthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            @Nullable VerifiedIdentity identity,
            ReauthenticationFlow.Armed armed)
            throws IOException {
        if (identity == null
                || !identity.provider().equals(armed.provider())
                || !ReauthenticationFlow.isFreshAuthentication(
                        identity.authenticatedAt(), Instant.now())
                || !this.authSessionService.confirmReauthentication(
                        armed.userId(), identity.issuer(), identity.subject())) {
            /*
             * 네 갈래가 하나로 접힌다. 제공자 바꿔치기, 증거 부재·오래됨·미래, 타 사용자·미연결
             * 식별자, 삭제가 시작된 계정이다. 어느 쪽인지 표지로 구분되면 그 차이가 곧 "그
             * 식별자가 이 계정의 것인가"를 알려 주는 신호가 된다.
             */
            convergeReauthentication(request, response, armed, FAILURE_MARKER_REAUTH_FAILED);
            return;
        }

        HttpSession httpSession = request.getSession();
        /*
         * 표시를 지운다. 확정된 재인증이 곧 그 표시가 요구한 것이므로 이 시점이 지우는 자리다.
         * 남기면 방금 본인을 확인한 세션이 계속 미인증으로 답한다.
         */
        httpSession.removeAttribute(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE);
        ReauthenticationFlow.recordAuthentication(httpSession, Instant.now());
        writeEstablishedPrincipal(request, response, armed.userId());

        // 목적지는 무장 시각에 붙들어 둔 값 하나다. 콜백이 들고 온 어떤 값도 여기 오지 않는다.
        this.redirects.sendRedirect(request, response, armed.returnPath());
    }

    /**
     * 연결 콜백의 판정과 커밋.
     *
     * <p>이 경로는 로그인도 가입도 아니다. {@code establish}를 부르지 않으므로 미연결 식별자로
     * 새 계정이 생기지 않고, 세션의 사용자는 무장이 붙들어 둔 값 그대로다.
     *
     * <p><b>주체를 먼저 되돌린다.</b> {@code AbstractAuthenticationProcessingFilter}는 성공 핸들러를
     * 부르기 전에 {@code OAuth2AuthenticationToken}을 세션에 이미 저장했고, 그 토큰은 ID Token과
     * claim을 들고 있다. 그대로 두면 검증 직후 버려야 할 ID Token이 PostgreSQL 세션 행에 남고,
     * 세션 행의 {@code principal_name}이 내부 {@code user_id}에서 제공자 {@code sub}으로 갈아타며,
     * 무엇보다 <b>허가 포트가 "세션이 결정한 사용자"를 읽지 못해</b> 정상 연결이 거부된다
     * ({@code ActionGrantService}의 주체 대조). 그래서 판정보다 앞이다.
     *
     * <p>제공자 일치를 본다. 무장은 naver인데 kakao 콜백이 도착하면 판정할 것이 없다 — 그 흐름의
     * 사실이 아니다. 나머지 판정(허가·삭제 상태·유일성)은 {@code LinkService}가 한 트랜잭션에서
     * 한다.
     *
     * <p>성공하면 세션 ID를 <b>다시</b> 회전한다. 한 번은 검증 직후 세션 고정 방어가 이미 했고,
     * 이 두 번째 회전은 {@code SECURITY-ACTION-1.0}이 허가 소비 성공에 요구하는 것이며 1.9의
     * 결과 타입 이름({@code ConsumedRequiringSessionRotation})이 이 자리로 넘긴 의무다.
     */
    private void onLinkSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            @Nullable VerifiedIdentity identity,
            LinkFlow.Armed armed)
            throws IOException {
        writeEstablishedPrincipal(request, response, armed.userId());

        LinkService.Outcome outcome =
                identity == null || !identity.provider().equals(armed.targetProvider())
                        ? LinkService.Outcome.DENIED
                        : this.linkService.complete(
                                request.getSession(), armed, identity.issuer(), identity.subject());

        if (rotateIfLinked(request, outcome) == null) {
            /*
             * 여러 갈래가 하나로 접힌다. 제공자 바꿔치기, 이미 내 계정에 있는 제공자, 다른 계정에
             * 연결된 식별자, 허가 만료·이미 사용됨, 세션 불일치, 삭제가 시작된 계정이다. 어느
             * 쪽인지 표지로 구분되면 그 차이가 곧 "그 식별자가 누구의 것인가"를 알려 준다.
             */
            this.redirects.sendRedirect(
                    request,
                    response,
                    armed.returnPath()
                            + "?"
                            + FAILURE_MARKER_PARAMETER
                            + "="
                            + FAILURE_MARKER_LINK_FAILED);
            return;
        }

        // 목적지는 무장 시각에 붙들어 둔 값 하나다. 결과는 표지 하나로 말한다.
        this.redirects.sendRedirect(
                request,
                response,
                armed.returnPath()
                        + "?"
                        + FAILURE_MARKER_PARAMETER
                        + "="
                        + NOTICE_MARKER_LINK_SUCCEEDED);
    }

    /**
     * 허가 소비 성공이 요구하는 세션 재회전.
     *
     * <p>{@code SECURITY-ACTION-1.0}의 의무이고 1.9의 결과 타입 이름
     * ({@code ConsumedRequiringSessionRotation})이 이 자리로 넘긴 것이다. 소비와 매핑은 이미
     * 커밋됐고 여기서 바꾸는 것은 세션 식별자 하나뿐이므로, 이 호출이 실패하더라도 연결이 부분
     * 상태로 남지 않는다.
     *
     * <p><b>메서드로 뽑은 이유는 관측이다.</b> HTTP 왕복에서는 세션 고정 방어가 검증 직후 이미 한 번
     * 회전시키므로 "쿠키가 바뀌었다"가 이 두 번째 회전 없이도 참이고, 그래서 이 호출이 사라져도
     * 어떤 왕복 단정도 실패하지 않는다(변이로 확인했다). 판정과 호출을 한 자리에 모아 두면 그
     * 사실을 HTTP 밖에서 직접 볼 수 있다 — 소비에 성공했을 때만 회전하고 거부에서는 하지 않는다는
     * 것이 여기서 고정된다({@code AuthFlowHandlersRotationTest}).
     *
     * @return 회전한 새 세션 id, 연결이 거부됐으면 {@code null}
     */
    static @Nullable String rotateIfLinked(HttpServletRequest request, LinkService.Outcome outcome) {
        if (outcome != LinkService.Outcome.LINKED) {
            return null;
        }
        return request.changeSessionId();
    }

    /**
     * 이 콜백을 소비하는 연결 무장. 없으면 {@code null}이고 그때는 재인증·로그인 분기가 받는다.
     *
     * <p><b>두 흐름이 함께 무장된 상태는 평범한 경로다.</b> 설정 화면이 연결 버튼 셋과 본인 확인
     * 버튼 셋을 같은 페이지에 그리므로 "네이버 연결 무장 → 제공자 화면에서 이탈 → Google 본인
     * 확인"이 그대로 성립한다. 그때 콜백의 등록 id를 보지 않고 연결을 먼저 집으면 Google 재인증이
     * 연결 분기에 삼켜져 제공자 불일치로 끝나고, {@code recordAuthentication}이 불리지 않아
     * <b>재인증이 성립하지 않으며</b> {@code reauth_required} 표시도 풀리지 않는다.
     *
     * <p>그래서 <b>일치하는 쪽이 이긴다</b>. 무장 시각에 상대 흐름을 버리는 것이 1차 방어이고
     * ({@code LinkService.start}·{@code AuthSessionController.startReauthentication}) 이 판정은 그
     * 뒤에 남는 상태를 위한 2차 방어다.
     *
     * <p>어느 쪽도 일치하지 않으면 연결이 먼저다. 그 경우는 어느 흐름에서도 실패이고 순서가 정하는
     * 것은 표지 하나뿐이며, 재인증만 무장된 상태의 제공자 바꿔치기는 Story 1.8이 고정한 대로
     * 재인증 실패로 수렴한다.
     *
     * @param provider 이 콜백이 알려 준 등록 id. 성공 핸들러는 검증된 인증 결과에서, 흐름 경계와
     *     실패 핸들러는 경로에서 읽는다
     */
    private static LinkFlow.@Nullable Armed callbackLinkFlow(
            LinkFlow.@Nullable Armed link,
            ReauthenticationFlow.@Nullable Armed armed,
            @Nullable String provider) {
        if (link == null) {
            return null;
        }
        if (armed == null || link.targetProvider().equals(provider)) {
            return link;
        }
        return armed.provider().equals(provider) ? null : link;
    }

    /**
     * 재인증 실패를 그 흐름의 목적지로 수렴시킨다.
     *
     * <p>로그인 실패의 {@link #converge}와 두 가지가 다르다. 목적지가 인증 화면이 아니라 붙들어
     * 둔 복귀 경로이고, <b>세션을 정리하지 않는다</b> — 이미 개설된 세션은 이 실패의 사실이
     * 아니며 "취소·실패는 이미 개설된 세션을 건드리지 않는다"가 그 계약이다.
     *
     * <p>주체를 다시 쓰는 것이 여기서 필수다. 이 경로는 {@code onSuccess} 안에 있고,
     * {@code AbstractAuthenticationProcessingFilter}는 성공 핸들러를 부르기 <b>전에</b> 자기 인증
     * 결과를 세션에 이미 저장했다. 그 결과는 ID Token과 claim을 들고 있는
     * {@code OAuth2AuthenticationToken}이므로, 그대로 두면 검증에 실패한 흐름이 그 토큰을
     * PostgreSQL에 남기고 세션 행의 {@code principal_name}까지 제공자 {@code sub}으로 갈아탄다 —
     * 그 순간 {@code SessionGuard}의 사용자 단위 조작이 이 세션을 찾지 못한다. 같은 사용자를
     * 다시 쓰는 것이므로 세션의 사용자는 바뀌지 않는다.
     */
    private void convergeReauthentication(
            HttpServletRequest request,
            HttpServletResponse response,
            ReauthenticationFlow.Armed armed,
            String marker)
            throws IOException {
        writeEstablishedPrincipal(request, response, armed.userId());
        this.redirects.sendRedirect(
                request,
                response,
                armed.returnPath() + "?" + FAILURE_MARKER_PARAMETER + "=" + marker);
    }

    /**
     * 세션의 인증 주체를 내부 식별자 하나로 만든다.
     *
     * <p>여기서 버리는 것은 ID Token 원문과 그 claim, 그리고 제공자가 준 표시 정보 전부다. 세션이
     * 결정하는 값은 {@code user_id} 하나뿐이라는 계약이 이 네 줄이며, 로그인과 재인증이 같은
     * 본체를 지난다.
     *
     * <p>Spring Session의 기본 flush 시점은 요청 끝이라, 덮어쓴 뒤에는 마지막 값 하나만
     * 데이터베이스로 간다 — ID Token은 저장소에 닿지 않는다. 이 값의 이름이 곧 세션 행의
     * {@code principal_name}이고 {@code SessionGuard}가 그 색인으로 사용자 단위 조작을 한다.
     */
    private void writeEstablishedPrincipal(
            HttpServletRequest request, HttpServletResponse response, UUID userId) {
        SecurityContext minimal = SecurityContextHolder.createEmptyContext();
        minimal.setAuthentication(
                new PreAuthenticatedAuthenticationToken(
                        userId.toString(),
                        // 자격정보를 들고 있지 않다. 이 세션은 서버가 개설한 사실 자체가 근거다.
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(minimal);
        this.securityContexts.saveContext(minimal, request, response);
    }

    /**
     * 검증된 인증 결과를 계정·세션으로 옮긴다.
     *
     * <p>제공자 이름을 짐작하지 않는다. {@code OAuth2AuthenticationToken}은 이 흐름을 실제로 처리한
     * 등록 id를 들고 있고({@code getAuthorizedClientRegistrationId()}), 그 값이 그대로
     * {@code identity_social_link.provider}에 저장된다. 여기서 상수를 쓰면 어느 제공자로
     * 로그인해도 같은 이름이 남는다 — 그것이 Story 1.6 전의 결함이었다.
     *
     * <p>토큰이 그 타입이 아니면 세션을 만들지 않는다. 이 배선에서 성공 핸들러를 부르는 것은
     * {@code OAuth2LoginAuthenticationFilter} 하나이므로 다른 타입이 여기 올 경로가 없고, 왔다면
     * 제공자를 알 수 없다는 뜻이라 저장할 값이 없다.
     */
    private Optional<AuthSessionService.AuthSession> establish(Authentication authentication) {
        VerifiedIdentity identity = verifiedIdentityOf(authentication);
        if (identity == null) {
            return Optional.empty();
        }
        return this.authSessionService.establish(
                identity.provider(), identity.issuer(), identity.subject());
    }

    /**
     * 검증을 통과한 흐름이 알려 준 값.
     *
     * @param provider 이 흐름을 <b>실제로</b> 처리한 제공자 등록 id
     * @param issuer 제공자 namespace
     * @param subject 제공자가 준 외부 식별자
     * @param authenticatedAt 제공자가 서명한 인증 시각({@code auth_time}). 재인증의 신선도 판정이
     *     쓰는 유일한 증거이며, claim이 없으면 {@code null}이다. 로그인 경로는 이 값을 보지 않는다
     */
    private record VerifiedIdentity(
            String provider, String issuer, String subject, @Nullable Instant authenticatedAt) {}

    /**
     * 인증 결과에서 흐름의 사실을 꺼낸다.
     *
     * <p>제공자 이름을 짐작하지 않는다. {@code OAuth2AuthenticationToken}은 이 흐름을 실제로
     * 처리한 등록 id를 들고 있고({@code getAuthorizedClientRegistrationId()}), 그 값이 그대로
     * {@code identity_social_link.provider}에 저장된다. 여기서 상수를 쓰면 어느 제공자로
     * 로그인해도 같은 이름이 남는다 — 그것이 Story 1.6 전의 결함이었다.
     *
     * <p>토큰이 그 타입이 아니면 빈 값이다. 이 배선에서 성공 핸들러를 부르는 것은
     * {@code OAuth2LoginAuthenticationFilter} 하나이므로 다른 타입이 여기 올 경로가 없고, 왔다면
     * 제공자를 알 수 없다는 뜻이라 저장할 값도 판정할 값도 없다.
     *
     * <p>로그인과 재인증이 같은 추출을 지난다. 두 벌로 두면 한쪽만 제공자를 짐작하기 시작하는
     * 날이 온다.
     */
    private static @Nullable VerifiedIdentity verifiedIdentityOf(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            return null;
        }
        if (!(authentication.getPrincipal() instanceof OidcUser user)) {
            // OIDC 흐름이 아니면 세션을 만들지 않는다. 이 배선에는 다른 인증 경로가 없다.
            return null;
        }
        String provider = token.getAuthorizedClientRegistrationId();
        URL issuer = user.getIssuer();
        String subject = user.getSubject();
        if (provider == null || provider.isBlank() || issuer == null || subject == null) {
            // 검증이 세 값을 필수로 요구하므로 여기 오지 않는다. 그래도 접는다.
            return null;
        }
        return new VerifiedIdentity(
                provider, issuer.toExternalForm(), subject, user.getAuthenticatedAt());
    }

    private void onFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        /*
         * 무장된 재인증 흐름이 있으면 그 흐름의 목적지로 돌아간다.
         *
         * 여기 오는 것은 사용자 취소와 제공자 오류, 그리고 `state` 불일치·재사용처럼 인증이
         * 확정되기 전에 끝난 실패다. 그때 세션에는 아직 개설된 주체가 그대로 있으므로 주체를
         * 다시 쓸 일이 없고(`onSuccess` 안의 실패와 다른 점이다), `discardSession`의 "인증된
         * 세션은 건드리지 않는다"도 그대로 성립한다. 그래도 이 경로를 지나지 않는 이유는
         * 목적지다 — 이미 로그인한 사용자를 인증 화면으로 보낼 이유가 없다.
         */
        /*
         * 어느 흐름이 이 실패를 소비하는지도 콜백의 등록 id가 가른다(`callbackLinkFlow`). 성공
         * 핸들러와 달리 여기에는 검증된 인증 결과가 없으므로 그 값을 경로에서 읽는다.
         */
        LinkFlow.Armed link =
                callbackLinkFlow(
                        LinkFlow.peek(request),
                        ReauthenticationFlow.peek(request),
                        AuthSecurityConfig.callbackProvider(request));
        if (link != null) {
            /*
             * 연결의 취소·제공자 오류다. 허가는 소비되지 않았고 매핑도 만들어지지 않았으므로
             * 저장소의 변화는 0건이다. 남아 있는 허가는 다음 발급이 무효화한다.
             */
            LinkFlow.discard(request);
            this.redirects.sendRedirect(
                    request,
                    response,
                    link.returnPath()
                            + "?"
                            + FAILURE_MARKER_PARAMETER
                            + "="
                            + linkMarker(exception));
            return;
        }

        ReauthenticationFlow.Armed armed = ReauthenticationFlow.consume(request);
        if (armed != null) {
            this.redirects.sendRedirect(
                    request,
                    response,
                    armed.returnPath()
                            + "?"
                            + FAILURE_MARKER_PARAMETER
                            + "="
                            + reauthenticationMarker(exception));
            return;
        }
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
        if (isUserCancellation(exception)) {
            return FAILURE_MARKER_CANCELLED;
        }
        return FAILURE_MARKER_FAILED;
    }

    /** 재인증 실패 표지. 로그인과 같은 규칙으로 갈리고 값만 다르다. */
    private static String reauthenticationMarker(AuthenticationException exception) {
        return isUserCancellation(exception)
                ? FAILURE_MARKER_REAUTH_CANCELLED
                : FAILURE_MARKER_REAUTH_FAILED;
    }

    /** 연결 실패 표지. 같은 규칙으로 갈리고 값만 다르다. */
    private static String linkMarker(AuthenticationException exception) {
        return isUserCancellation(exception)
                ? FAILURE_MARKER_LINK_CANCELLED
                : FAILURE_MARKER_LINK_FAILED;
    }

    /** 사용자가 스스로 취소했거나 동의를 거부했는가. 자동 재시도를 만들지 않을 유일한 근거다. */
    private static boolean isUserCancellation(AuthenticationException exception) {
        return exception instanceof OAuth2AuthenticationException oauth2Exception
                && OAuth2ErrorCodes.ACCESS_DENIED.equals(oauth2Exception.getError().getErrorCode());
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
        if (SETTINGS_PATH.equals(destination)) {
            return "settings";
        }
        return "entry";
    }

    private static boolean isAuthorizationStart(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && request.getRequestURI().startsWith(AuthSecurityConfig.AUTHORIZATION_BASE_URI + "/");
    }

    /** 제공자가 브라우저를 되돌려 보내는 경로인가. */
    private static boolean isAuthorizationCallback(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && request.getRequestURI().startsWith(AuthSecurityConfig.CALLBACK_BASE_URI + "/");
    }

    /**
     * 세션 guard가 thread-local 인증을 남겨 두어야 하는 요청인가.
     *
     * <p>인증 시작·콜백·재인증 시작 셋이다. 이 경로들에서만 인증이 남고, 그래서
     * {@link #authenticationFlowFilter}의 거절 판정과 {@link #discardSession}의 "남의 로그인이다"
     * 판정이 표시된 세션에서도 성립하며 재인증 시작이 자기 주체를 읽을 수 있다. 판정 하나가
     * 시작과 콜백 중 한쪽만 덮으면 나머지가 곧 계정 갈아타기 경로가 된다.
     */
    private static boolean isAuthenticationFlow(HttpServletRequest request) {
        return isAuthorizationStart(request)
                /*
                 * 재인증 시작도 여기 든다(Story 1.8). 그 요청은 세션의 주체를 읽어 무장에 담아야
                 * 하므로 컨트롤러에 인증이 도달해야 한다. 빠지면 표시된 세션의 재인증 시작이
                 * 언제나 403으로 끝나고, 그 세션에는 회복 경로가 없다.
                 *
                 * 이 요청은 새 인증을 확정하지 않는다. `POST`라 위 403 예외가 따로 필요했고,
                 * 확정은 그 뒤에 오는 콜백이 무장을 소비하며 한다.
                 */
                || AuthSecurityConfig.isReauthenticationStart(request)
                || isAuthorizationCallback(request);
    }

    private static boolean isAuthenticated(@Nullable Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
