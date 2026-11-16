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
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
     * 보호된 운영자 화면(Story 3.1).
     *
     * <p>복귀 목적지 허용 목록에 들지만 <b>운영자에게만</b> 목적지가 된다. 로그인 성공({@link #onSuccess})과 재인증 시작
     * ({@code AuthSessionController})이 그 순간의 운영자 여부를 다시 보고, 아니면 {@link #DASHBOARD_PATH}로 접는다 — 둘 다
     * 평범한 이동이라 권한 유무가 목적지로 새지 않는다. 다섯 곳의 등가는 {@link #SETTINGS_PATH}의 설명과 같다.
     */
    static final String OPERATOR_PATH = "/operator";

    /**
     * 복귀 목적지 허용 목록.
     *
     * <p>닫힌 집합이다. 목록에 없는 값은 검사할 필요조차 없이 버려지므로, 경로 정규화·스킴 검사·
     * 호스트 비교 같은 판정이 애초에 존재하지 않는다. 라우트가 늘 때 사람이 한 줄을 더하는 비용을
     * 지불하는 대신, 판정 논리에 구멍이 생길 자리를 없앤다.
     */
    private static final Set<String> ALLOWED_DESTINATIONS =
            Set.of(ONBOARDING_PATH, DASHBOARD_PATH, SETTINGS_PATH, OPERATOR_PATH);

    /** 인증 시작이 기록한 요청 목적지. 성공하면 지운다. */
    static final String REQUESTED_DESTINATION_ATTRIBUTE = "bokrilog.auth.requested-destination";

    /** 확정된 목적지. {@code GET /api/auth/session}이 종류만 보고한다. */
    static final String DESTINATION_ATTRIBUTE = "bokrilog.auth.destination";

    /**
     * 세션의 인가 요청과 {@code state}가 맞지 않을 때 {@code OAuth2LoginAuthenticationFilter}가 내는 오류 code.
     *
     * <p>{@link #onFailure}의 <b>위조 콜백 가드</b>가 이 값으로 "이 세션의 인가 요청에 닿지 못한 실패"를 고른다 — 인증된
     * 세션에 외부 사이트가 모르는 {@code state}의 콜백을 열어도 살아 있는 연결·재인증 무장을 버리지 않게 하는 가드다
     * (Epic 1 회고 F2 — {@code epic-1-retro-2026-09-25.md}, 액션 {@code epic-1-retro-item-2}).
     *
     * <p>그 필터의 private 상수({@code AUTHORIZATION_REQUEST_NOT_FOUND_ERROR_CODE})라 참조할 수 없어 같은 값을 여기
     * 적는다. Spring Security가 이 값을 바꾸면 모르는 {@code state} 쪽 위조 콜백 가드가 조용히 꺼져 그런 콜백이 다시
     * 무장을 버린다 — {@code AuthLinkProviderTest}·{@code AuthReauthenticationTest}의 위조 콜백 시험이 그때 실패한다.
     */
    static final String AUTHORIZATION_REQUEST_NOT_FOUND_ERROR_CODE = "authorization_request_not_found";

    /** 인증 화면이 읽는 실패 표지의 쿼리 이름. */
    static final String FAILURE_MARKER_PARAMETER = "auth";

    /** 사용자가 취소했거나 동의를 거부했다. 인증 화면이 재시도 경로를 보여 준다. */
    static final String FAILURE_MARKER_CANCELLED = "cancelled";

    /**
     * 그 밖의 모든 실패.
     *
     * <p>제공자 오류, 토큰 엔드포인트 장애, {@code state} 불일치·재사용·만료, ID Token 검증 실패,
     * 삭제가 시작된 계정의 세션 거부가 모두 이 하나로 접힌다. 값을 나누는 순간 그 표지가 계정의
     * 존재나 상태를 알려 주는 신호가 된다. 삭제가 <b>끝난</b> 계정의 식별자 하나만 {@link #FAILURE_MARKER_DELETED}로 갈린다.
     */
    static final String FAILURE_MARKER_FAILED = "failed";

    /**
     * 삭제가 <b>끝난</b>({@code DELETED}) 계정의 로그인 수단이다(Story 5.4 재가입 차단).
     *
     * <p>이 표지만 계정의 상태를 말한다 — 의도한 예외다. 보는 사람은 제공자 검증을 방금 통과한 그 identity의 본인뿐이고(콜백 직후의 같은
     * 브라우저), 재가입이 막힌 이유를 숨기면 사용자는 같은 수단으로 계속 시도한다. 다른 사용자·대상의 접근 거부는 여전히 하나의 값이다.
     * 계정·세션은 만들지 않는다. 삭제가 시작됐지만 끝나지 않은 계정(로그인 매핑을 지운 뒤 뒤 단계가 재시도 필요로 멈춘 계정 포함)은 그대로
     * {@link #FAILURE_MARKER_FAILED}이고 그때도 계정을 만들지 않는다.
     */
    static final String FAILURE_MARKER_DELETED = "deleted";

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

    /** 운영자 판정(Story 3.1). 복귀 목적지 {@link #OPERATOR_PATH}를 운영자에게만 허용하는 데 쓴다. */
    private final OperatorAccess operators;

    /** 완료 확인 세션의 연결(Story 5.4). 인증 성공이 그 세션의 연결을 지운다. */
    private final DeletionCompletionSessions completions;

    AuthFlowHandlers(
            AuthSessionService authSessionService,
            SessionRepository<? extends Session> sessions,
            SessionGuard guard,
            LinkService linkService,
            OperatorAccess operators,
            DeletionCompletionSessions completions) {
        this.completions = completions;
        this.authSessionService = authSessionService;
        this.sessions = sessions;
        this.guard = guard;
        this.linkService = linkService;
        this.operators = operators;
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
     * 없는 콜백 — 연결 무장의 대상과 제공자가 다른 콜백을 포함한다({@link #callbackLinkFlow}) — 은
     * 이 자리에서 접는다.
     *
     * <p>성공 핸들러도 스스로 막는다 — 무장이 하나라도 남아 있던 콜백은 만료됐더라도 그 흐름의 실패로 접고 로그인 분기로
     * 보내지 않는다({@link #selectCallbackFlow}, deferred 191). 그래도 이 경계가 필요한 것은 둘이다. 인증된 세션에 <b>무장이
     * 아예 없는</b> 콜백은 성공 핸들러에서 로그인 분기이므로 검증에 넘기는 순간 같은 세션 행에 다른 계정을 심는다 — 그것을 막는
     * 것은 이 자리뿐이다. 그리고 여기서 접은 콜백은 검증에 닿지 않으므로 이 세션의 authorization request도 무장도 그대로다.
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
                             * 인증된 세션에 도착한, 이 세션의 무장이 소비하지 않는 콜백이다. 무장이
                             * 아예 없는 콜백과, 연결 무장은 있지만 그 대상과 제공자가 다른 콜백
                             * (`callbackLinkFlow`가 `null`을 낸 경우 — Epic 1 회고 F3,
                             * `epic-1-retro-2026-09-25.md`, 액션 `epic-1-retro-item-4`)이 함께 온다.
                             * 이 흐름은 이 세션의 사실이 아니므로 검증에 넘기지 않고 지금 목적지로
                             * 되돌린다. 세션도 무장도 인가 요청도 건드리지 않는다 — 남의 사이트가 연
                             * 콜백일 수도 있고, 그때 세션을 바꾸는 것이 곧 크로스 사이트 계정
                             * 갈아타기이며, 연결 무장을 버리면 이어지는 정상 콜백이 연결을 마치지
                             * 못한다.
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
                                    UnreadableSessionValue.read(session, SessionGuard.REAUTH_REQUIRED_ATTRIBUTE));
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
         * 어느 흐름이 이 콜백을 소비하는지는 `selectCallbackFlow`가 가르고, 그 안의 연결·재인증
         * 선택은 `callbackLinkFlow`다. 순서로 정하면 살아 있는 연결 무장이 재인증 콜백을 삼킨다 —
         * 두 흐름이 함께 무장된 상태는 평범한 경로이기 때문이다(그 근거는 그 메서드의 javadoc이
         * 소유한다).
         *
         * 소비는 어느 쪽 결과에서도 일어난다. 흐름은 콜백 하나로 끝나며 그것이 일회성이다.
         */
        VerifiedIdentity identity = verifiedIdentityOf(authentication);
        /*
         * 이 요청의 수명 판정 시각은 이것 하나다. 선택과 하위 판정(`onLinkSuccess`의 만료 거부, 재인증의 신선도·기록)이
         * 같은 값을 쓰므로, 선택에서 살아 있던 무장이 같은 요청 안에서 다시 만료 판정을 받지 않는다.
         */
        Instant now = Instant.now();
        // 수명이 지난 것까지 읽는다. `peek`은 만료된 무장을 버리고 `null`을 내므로 "무장이 없었다"와 "늦었다"를 가를 수 없다.
        LinkFlow.Armed storedLink = LinkFlow.peekIncludingExpired(request);
        ReauthenticationFlow.Armed storedReauthentication =
                ReauthenticationFlow.peekIncludingExpired(request);
        CallbackFlow flow =
                selectCallbackFlow(
                        storedLink,
                        storedReauthentication,
                        identity == null ? null : identity.provider(),
                        now);

        /*
         * 무장의 정리 규칙은 하나다. 고른 무장은 그 case가 소비한다. 고르지 않은 무장은 수명이 지났을 때만 버리고(`peek`이
         * 하던 정리다) 살아 있으면 그 흐름의 것이므로 남긴다(Epic 1 회고 F3).
         */
        switch (flow) {
            case CallbackFlow.Link(LinkFlow.Armed link) -> {
                LinkFlow.discard(request);
                discardIfExpired(request, storedReauthentication, now);
                onLinkSuccess(request, response, identity, link, now);
            }
            case CallbackFlow.Reauthentication(ReauthenticationFlow.Armed armed) -> {
                ReauthenticationFlow.discard(request);
                discardIfExpired(request, storedLink, now);
                onReauthenticationSuccess(request, response, identity, armed, now);
            }
            case CallbackFlow.ExpiredReauthentication(ReauthenticationFlow.Armed armed) -> {
                ReauthenticationFlow.discard(request);
                discardIfExpired(request, storedLink, now);
                /*
                 * 흐름 경계가 살아 있다고 판정한 재인증 무장이 이 자리 전에 만료됐다(deferred 191). 경계의 만료
                 * 가지와 같은 결과로 접는다 — 재인증 실패. 재인증 시각을 기록하지 않고 `reauth_required` 표시도
                 * 풀지 않는다. 주체는 무장의 사용자로 다시 쓴다(`convergeReauthentication`).
                 */
                convergeReauthentication(request, response, armed, FAILURE_MARKER_REAUTH_FAILED);
            }
            case CallbackFlow.Login() -> {
                /*
                 * 읽을 수 있는 무장이 하나도 없었다. 두 속성을 그래도 지운다 — 타입이 어긋난 값은 `peekIncludingExpired`가
                 * 없는 것으로 읽되 버리지 않는다. 재인증 쪽은 예전 `consume`이 이 자리에서 그렇게 치웠고, 연결 쪽도 같은
                 * 규칙으로 맞춘다.
                 */
                LinkFlow.discard(request);
                ReauthenticationFlow.discard(request);
                onLogin(request, response, authentication, identity);
            }
        }
    }

    /** 고르지 않은 연결 무장이 수명이 지났으면 버린다. 살아 있으면 남긴다. */
    private static void discardIfExpired(
            HttpServletRequest request, LinkFlow.@Nullable Armed link, Instant now) {
        if (link != null && !link.isLive(now)) {
            LinkFlow.discard(request);
        }
    }

    /** 고르지 않은 재인증 무장이 수명이 지났으면 버린다. 살아 있으면 남긴다. */
    private static void discardIfExpired(
            HttpServletRequest request, ReauthenticationFlow.@Nullable Armed armed, Instant now) {
        if (armed != null && !armed.isLive(now)) {
            ReauthenticationFlow.discard(request);
        }
    }

    /**
     * 로그인 분기. 무장이 하나도 없는 콜백만 온다({@link #selectCallbackFlow}).
     *
     * <p>{@code establish}는 미연결 식별자를 신규 가입으로 처리한다. 그래서 무장이 있었던 콜백은 어떤 경우에도 여기 오지
     * 않는다 — 온다면 인증된 세션에 검증된 다른 계정이 실린다.
     */
    private void onLogin(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication,
            @Nullable VerifiedIdentity identity)
            throws IOException {
        Optional<AuthSessionService.AuthSession> session = establish(authentication);
        if (session.isEmpty()) {
            /*
             * 검증은 통과했지만 세션을 만들 수 없다. 삭제가 시작된 계정, 경합에서 진 흐름이 여기로 온다. 어느 쪽인지 구분되지 않는 하나의
             * 실패로 수렴한다. 삭제가 끝난 계정의 식별자(재가입 차단, Story 5.4)만 그 사실을 말하는 표지로 갈린다 — 계정은 만들지 않았다.
             */
            converge(request, response, failureMarkerOf(identity));
            return;
        }

        AuthSessionService.AuthSession established = session.get();
        String destination =
                established.firstLogin()
                        ? ONBOARDING_PATH
                        : this.operators.foldDestination(
                                established.userId(),
                                Optional.ofNullable(
                                                allowedDestination(
                                                        attribute(request, REQUESTED_DESTINATION_ATTRIBUTE)))
                                        .orElse(DASHBOARD_PATH));

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
            ReauthenticationFlow.Armed armed,
            Instant now)
            throws IOException {
        if (identity == null
                || !identity.provider().equals(armed.provider())
                || !ReauthenticationFlow.isFreshAuthentication(identity.authenticatedAt(), now)
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
        ReauthenticationFlow.recordAuthentication(httpSession, now);
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
            LinkFlow.Armed armed,
            Instant now)
            throws IOException {
        writeEstablishedPrincipal(request, response, armed.userId());

        /*
         * 수명이 지난 무장은 허가를 소비하러 가지 않고 거부로 접는다. 흐름 경계가 늦은 콜백을 이미
         * 접지만 그 판정과 이 자리 사이에 만료될 수 있고(`onSuccess`의 방어선이 그 무장을 여기로
         * 넘긴다), 허가의 만료는 데이터베이스 시계라 JVM 시계의 무장 수명과 같은 순간에 끝난다는
         * 보장이 없다.
         */
        LinkService.Outcome outcome =
                identity == null
                                || !identity.provider().equals(armed.targetProvider())
                                || !armed.isLive(now)
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
     * <p>회전과 함께 재인증 시각을 지운다({@code SessionRotation}). 연결은 민감 command 하나이고 본인
     * 확인 1회는 command 1건이다 — 지우지 않으면 방금의 재인증이 5분 안에서 해제·연결 같은 다음
     * command를 연쇄 승인한다. 거부에서는 아무것도 지우지 않으므로 사용자는 같은 재인증으로 다시
     * 시작할 수 있다.
     *
     * @return 회전한 새 세션 id, 연결이 거부됐으면 {@code null}
     */
    static @Nullable String rotateIfLinked(HttpServletRequest request, LinkService.Outcome outcome) {
        if (outcome != LinkService.Outcome.LINKED) {
            return null;
        }
        return SessionRotation.rotateAfterSensitiveCommand(request);
    }

    /** 검증을 통과한 콜백을 받는 흐름. {@link #selectCallbackFlow}의 결과다. */
    sealed interface CallbackFlow {

        /** 연결 분기. 무장이 만료됐거나 제공자가 다르면 {@code onLinkSuccess}가 거부로 접는다. */
        record Link(LinkFlow.Armed armed) implements CallbackFlow {}

        /** 살아 있는 재인증 무장의 분기. */
        record Reauthentication(ReauthenticationFlow.Armed armed) implements CallbackFlow {}

        /** 재인증 무장이 있었지만 수명이 지났다. 재인증 실패로 접는다 — 로그인 분기로 가지 않는다. */
        record ExpiredReauthentication(ReauthenticationFlow.Armed armed) implements CallbackFlow {}

        /** 무장이 하나도 없다. 로그인 분기다. */
        record Login() implements CallbackFlow {}
    }

    /**
     * 검증을 통과한 콜백을 어느 흐름이 받는가(deferred 191).
     *
     * <p><b>저장된 무장은 수명이 지난 것까지 받는다.</b> 흐름 경계({@link #authenticationFlowFilter})가 무장을 살아 있다고
     * 판정한 뒤 이 자리 전에 만료될 수 있다. 살아 있는 것만 보면 그 무장은 "없던 것"이 되어 콜백이 로그인 분기로 떨어지고,
     * 인증된 세션에 검증된 <b>다른 계정</b>이 실린다. 그래서 무장이 하나라도 있었던 입력은 어느 것도 {@link CallbackFlow.Login}을
     * 내지 않는다 — 만료된 무장도 그 흐름의 실패로 접는다.
     *
     * <p>연결·재인증 중 무엇이 이 콜백을 받는지는 흐름 경계와 같은 판정이다 — 두 무장을 만료 포함으로 {@link #callbackLinkFlow}에
     * 넘긴다. 그래서 <b>제공자가 일치하는 무장이 수명과 무관하게 이기고</b>, 이 판정은 경계가 내린 선택을 그대로 잇는다 — 경계
     * 뒤에 만료가 끼어도 선택이 다른 흐름으로 옮겨 가지 않는다(만료된 재인증 무장이 콜백과 맞으면 제공자가 다른 살아 있는 연결
     * 무장을 소비하지 않는다, F3). 무장이 모두 살아 있으면 살아 있는 것만 보던 이전 판정과 같은 값을 낸다.
     *
     * <ul>
     *   <li>연결이 이 콜백을 받으면 {@link CallbackFlow.Link}다. 만료된 연결 무장도 여기로 오고 {@code onLinkSuccess}가 거부로
     *       접는다(허가를 소비하러 가지 않는다).
     *   <li>재인증 무장이 없는데 연결 무장이 받지 않는 콜백(제공자 불일치)도 연결 분기가 받아 거부로 접는다. 여기 오는 길은
     *       둘이다. 제공자가 다른 콜백은 흐름 경계가 경로의 제공자로 이미 접으므로(F3) 검증까지 오지 못한다 — 경로와 인가 요청의
     *       {@code redirect_uri}가 어긋나면 검증이 먼저 실패한다. 그리고 경계 판정 뒤 무장이 만료됐어도 경계의 선택은 이미
     *       끝났다. 어느 쪽이든 {@code null}로 두면 로그인 분기가 인증된 세션에 다른 계정을 심으므로 연결 분기가 받는다.
     *   <li>연결이 받지 않고 재인증 무장이 있으면 살아 있는지에 따라 {@link CallbackFlow.Reauthentication}·
     *       {@link CallbackFlow.ExpiredReauthentication}이다.
     *   <li>두 무장이 모두 없을 때만 {@link CallbackFlow.Login}이다.
     * </ul>
     *
     * @param provider 검증된 인증 결과의 등록 id. 없으면 {@code null}이고 어느 무장과도 일치하지 않는다
     * @param now 수명 판정 시각
     */
    static CallbackFlow selectCallbackFlow(
            LinkFlow.@Nullable Armed storedLink,
            ReauthenticationFlow.@Nullable Armed storedReauthentication,
            @Nullable String provider,
            Instant now) {
        LinkFlow.Armed link = callbackLinkFlow(storedLink, storedReauthentication, provider);
        if (link == null && storedReauthentication == null) {
            // 재인증 무장 없이 연결 무장만 남아 있고 이 콜백이 그것과 맞지 않는다. 로그인으로 보내지 않고 연결 분기가 거부한다.
            link = storedLink;
        }
        if (link != null) {
            return new CallbackFlow.Link(link);
        }
        if (storedReauthentication != null) {
            return storedReauthentication.isLive(now)
                    ? new CallbackFlow.Reauthentication(storedReauthentication)
                    : new CallbackFlow.ExpiredReauthentication(storedReauthentication);
        }
        return new CallbackFlow.Login();
    }

    /**
     * 이 콜백을 소비하는 연결 무장. 연결이 이 콜백을 받지 않으면 {@code null}이다.
     *
     * <p>{@code null}이 곧 "재인증이나 로그인이 받는다"는 뜻은 아니다. 그 뒤는 호출자마다 다르다 — 흐름 경계는 재인증 무장이
     * 없으면 검증 앞에서 되돌리고, 성공 핸들러({@link #selectCallbackFlow})는 재인증 무장이 없으면 남은 연결 무장이 거부로
     * 받으며 무장이 하나도 없을 때만 로그인으로 보내고, 실패 핸들러는 재인증 무장을 소비한다.
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
     * <p><b>연결 무장만 있을 때도 일치해야 이긴다</b>(Epic 1 회고 F3). 연결 무장은 그 무장의 대상
     * 제공자 콜백만 소비한다 — 그렇지 않으면 다른 제공자의 콜백(같은 화면에서 앞서 연 다른 제공자
     * 흐름의 늦은 도착, 남의 사이트가 연 콜백)이 살아 있는 연결 무장을 집어 실패로 끝내고, 사용자는
     * 이어지는 정상 콜백으로도 연결을 마치지 못한다. 그때 이 판정은 {@code null}이고, 재인증 무장도
     * 없으므로 흐름 경계({@link #authenticationFlowFilter})가 "무장 없는 콜백"과 같이 지금
     * 목적지로 되돌린다 — 검증에 넘기지 않으므로 이 세션의 authorization request도 무장도 그대로다.
     *
     * <p>두 무장이 함께 있고 어느 쪽도 일치하지 않으면 연결이 먼저다. 그 경우는 어느 흐름에서도
     * 실패이고 순서가 정하는 것은 표지 하나뿐이며, 재인증만 무장된 상태의 제공자 바꿔치기는
     * Story 1.8이 고정한 대로 재인증 실패로 수렴한다.
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
        if (link.targetProvider().equals(provider)) {
            return link;
        }
        if (armed == null) {
            return null;
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
     *
     * <p>개설된 세션을 남기는 인증 성공 경로(로그인·재인증과 그 실패 수렴·연결)가 모두 여기를 지나므로 CSRF 토큰도 여기서 미리
     * 저장한다({@link #storeCsrfToken}).
     */
    private void writeEstablishedPrincipal(
            HttpServletRequest request, HttpServletResponse response, UUID userId) {
        // 완료 확인 세션(Story 5.4)에서 로그인했으면 그 완료 연결을 지운다 — 회전은 세션 행 키를 보존한다. 판독은 미인증 세션만 하므로
        // 지우지 못해도 새 인증 세션이 완료 조회를 받지는 않는다(최선 노력).
        this.completions.forget(request);
        SecurityContext minimal = SecurityContextHolder.createEmptyContext();
        minimal.setAuthentication(
                new PreAuthenticatedAuthenticationToken(
                        userId.toString(),
                        // 자격정보를 들고 있지 않다. 이 세션은 서버가 개설한 사실 자체가 근거다.
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(minimal);
        this.securityContexts.saveContext(minimal, request, response);
        storeCsrfToken(request);
    }

    /**
     * 이 세션의 CSRF 토큰을 인증 성공 요청 안에서 만들어 저장한다.
     *
     * <p>그렇지 않으면 토큰은 로그인 뒤 첫 세션 확인({@code GET /api/auth/session})에서 처음 만들어진다. 그 요청들이 겹치면
     * 요청마다 다른 토큰을 만들어 응답에 싣고 세션에는 마지막에 저장한 하나만 남으므로(last-write-wins), 다른 응답의 토큰을 든
     * 화면은 다음 상태 변경이 403이 된다. 재인증·연결 콜백도 같다 — 인증 성공 시 Spring Security의
     * {@code CsrfAuthenticationStrategy}가 기존 토큰을 지우고 지연 토큰으로 바꿔 두기 때문이다. 여기서 만들어 두면 뒤따르는 세션
     * 확인은 저장된 같은 토큰을 읽기만 한다.
     *
     * <p>방법은 {@code CsrfFilter}가 요청 속성에 얹어 둔 지연 토큰의 {@code getToken()} 하나다 — 지연 토큰이 저장소에서 읽고 없으면
     * 만들어 저장한다. 저장소 구현·속성 이름은 그대로이고({@code AuthSecurityConfig.SessionBoundCsrfTokenRepository}), 응답에
     * 토큰을 싣는 자격(인증·표시된 세션)도 그대로다({@code AuthSessionController}). 이 경로는 세션이 있을 때만 오므로 저장소의
     * "세션을 만들지 않는다"와 부딪히지 않는다.
     */
    private static void storeCsrfToken(HttpServletRequest request) {
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken deferred) {
            deferred.getToken();
        }
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
        HttpSession session = request.getSession(false);
        if (session != null
                && carriesEstablishedPrincipal(session)
                && missedAuthorizationRequest(request, exception)) {
            /*
             * 이 세션의 authorization request에 닿지 못한 실패다(Epic 1 회고 F2). 무장을 건드리지
             * 않고 "무장 없는 콜백"과 같이 지금 목적지로 되돌린다.
             *
             * 위협은 `discardSession`의 것과 같다. 콜백은 미인증 최상위 GET이고 세션 쿠키는
             * `SameSite=Lax`라 최상위 내비게이션에 실리므로, 외부 사이트가 `top.location`으로
             * `state` 없는(또는 모르는 `state`의) 콜백을 열면 피해자의 브라우저가 자기 세션을 들고
             * 여기 도착한다. 흐름 경계는 살아 있는 무장이 있으면 콜백을 검증에 넘기고, 그 검증은
             * 이 세션의 인가 요청을 찾지 못해 여기로 온다. 그때 아래처럼 무장을 버리면 그것을
             * 반복하는 것만으로 남이 이 사용자의 연결·재인증을 계속 거부할 수 있다.
             *
             * 이 실패는 아무것도 소비하지 않았다 — `OAuth2LoginAuthenticationFilter`는 `state`가
             * 맞을 때만 저장된 인가 요청을 지우므로 진행 중인 흐름의 인가 요청도 그대로이고, 이어지는
             * 정상 콜백이 그 흐름을 마친다. 세션 행·주체·CSRF 토큰도 바뀌지 않는다. 사용자 취소·
             * 제공자 오류·검증 실패처럼 인가 요청을 소비한 실패는 이 가드에 걸리지 않고 아래에서
             * 지금처럼 무장을 소비한다.
             *
             * 목적지의 사용자는 저장된 주체에서 읽는다. 실패 핸들러에 오기 전에
             * `AbstractAuthenticationProcessingFilter`가 thread-local을 비웠으므로 그쪽을 보면 운영자의
             * 목적지가 흐름 경계의 되돌림과 다르게 접힌다.
             */
            this.redirects.sendRedirect(
                    request,
                    response,
                    currentDestination(request, ControllerSupport.storedSessionUserId(session)));
            return;
        }
        /*
         * 무장된 재인증 흐름이 있으면 그 흐름의 목적지로 돌아간다.
         *
         * 여기 오는 것은 사용자 취소와 제공자 오류, 그리고 토큰 검증 실패처럼 인증이 확정되기
         * 전에 끝난 실패다(인가 요청에 닿지 못한 `state` 없음·불일치·재사용은 인증된 세션이면 위
         * 가드가 먼저 받는다). 그때 세션에는 아직 개설된 주체가 그대로 있으므로 주체를
         * 다시 쓸 일이 없고(`onSuccess` 안의 실패와 다른 점이다), `discardSession`의 "인증된
         * 세션은 건드리지 않는다"도 그대로 성립한다. 그래도 이 경로를 지나지 않는 이유는
         * 목적지다 — 이미 로그인한 사용자를 인증 화면으로 보낼 이유가 없다.
         */
        /*
         * 어느 흐름이 이 실패를 소비하는지도 콜백의 등록 id가 가른다(`callbackLinkFlow`). 성공
         * 핸들러와 달리 여기에는 검증된 인증 결과가 없으므로 그 값을 경로에서 읽는다.
         *
         * 성공 핸들러(`selectCallbackFlow`)와 달리 여기는 **살아 있는 무장만** 본다(`peek`). 그래서 흐름
         * 경계의 판정 뒤 이 자리 전에 무장이 만료되면 그 무장은 없는 것이 되고, 선택이 다른 흐름의 표지나 아래
         * "지금 목적지"로 옮겨 갈 수 있다. 실패라 계정·매핑·주체는 바뀌지 않고 달라지는 것은 표지와 어느 무장이
         * 소비되는지뿐이다. 성공 핸들러처럼 만료 포함으로 고르는 일은 아직 하지 않았다(defer).
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
        if (session != null && carriesEstablishedPrincipal(session)) {
            /*
             * 인증된 세션인데 어느 흐름도 이 실패를 소비하지 않았다. 흐름 경계가 무장 없는 콜백을
             * 먼저 접으므로 여기 오는 경로는 없다(그 판정과 이 자리 사이에 무장이 만료된 경합 정도다).
             * 그래도 이미 로그인한 사용자를 인증 화면으로 보낼 이유가 없으므로 위 가드와 같은 302로
             * 지금 목적지에 되돌린다. `converge`로 가도 `discardSession`이 세션을 지키지만 목적지가
             * 인증 화면이 된다.
             */
            this.redirects.sendRedirect(
                    request,
                    response,
                    currentDestination(request, ControllerSupport.storedSessionUserId(session)));
            return;
        }
        if (missedAuthorizationRequest(request, exception)) {
            /*
             * 로그인 전 세션의 실패인데 이 세션의 authorization request에 닿지 못했다(deferred 190). 응답은 아래
             * 수렴과 같고(진입 화면 + 실패 표지) 세션만 지우지 않는다.
             *
             * 위협은 위 F2 가드와 같다. 외부 사이트가 `top.location`으로 `state` 없는(또는 모르는 `state`의)
             * 콜백을 열면 `SameSite=Lax` 쿠키가 실려 피해자의 진행 중인 인증 전 세션이 여기 도착한다. 그 세션을
             * 지우면 그 안의 인가 요청도 함께 사라지므로, 그것을 반복하는 것만으로 남이 이 사용자의 로그인을 계속
             * 취소시킬 수 있다. 같은 브라우저에서 로그인을 두 번 시작한 사용자의 앞 탭 콜백(덮인 `state`)도
             * 뒤 탭의 흐름을 지운다.
             *
             * 이 실패는 아무것도 소비하지 않았으므로 이어지는 정상 콜백이 로그인을 마친다. 세션에는 개설된
             * 주체가 없으므로(그랬다면 위 가드가 받았다) 남겨도 주체를 결정하지 않는다. 인가 요청을 소비한 실패
             * (사용자 취소·제공자 오류·토큰 검증 실패)는 여기 걸리지 않고 아래에서 지금처럼 그 흐름의 세션을
             * 지운다. 세션 개설 거부는 성공 핸들러의 수렴이 같은 일을 한다.
             *
             * 같은 이유로 완료 확인 세션(Story 5.4 — 삭제가 끝난 계정의 미인증 세션, `DeletionCompletionSessions`)도
             * 위조 콜백에 지워지지 않는다. 그전에는 위조 콜백 하나가 그 세션 행과 함께 완료 연결(FK cascade)까지
             * 지워 사용자가 완료 안내를 다시 볼 수 없었다.
             *
             * 남기는 대가는 하나다. 위조 콜백도 이 세션에 닿는 요청이므로 흐름 세션의 유휴 수명
             * (`FLOW_SESSION_TIMEOUT_SECONDS`)을 연장할 수 있다. 그래도 받아들이는 근거는 둘이다 — 생성 시각 기준
             * 절대 수명 12시간(`SessionGuard`)이 어느 세션에나 상한이고, 이 세션에는 개설된 주체가 없어 무엇도
             * 결정하지 않는다.
             */
            SecurityContextHolder.clearContext();
            this.redirects.sendRedirect(request, response, entryFailureLocation(marker(exception)));
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
        this.redirects.sendRedirect(request, response, entryFailureLocation(marker));
    }

    /** 인증 화면과 실패 표지. 로그인 실패의 목적지는 이것 하나다. */
    private static String entryFailureLocation(String marker) {
        return ENTRY_PATH + "?" + FAILURE_MARKER_PARAMETER + "=" + marker;
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
     * 세션을 개설하지 못한 로그인의 표지(Story 5.4). 삭제가 <b>끝난</b> 계정의 식별자만 {@link #FAILURE_MARKER_DELETED}이고 그 밖은
     * {@link #FAILURE_MARKER_FAILED}다. 판정이 예외로 끝나도 {@code failed}로 접어 실패 수렴(세션 정리·이동)에 닿게 한다.
     */
    private String failureMarkerOf(@Nullable VerifiedIdentity identity) {
        if (identity == null) {
            return FAILURE_MARKER_FAILED;
        }
        try {
            return this.authSessionService.isDeletedIdentity(identity.issuer(), identity.subject())
                    ? FAILURE_MARKER_DELETED : FAILURE_MARKER_FAILED;
        } catch (RuntimeException unreadable) {
            return FAILURE_MARKER_FAILED;
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

    /**
     * 이 실패가 이 세션의 authorization request에 닿지 못했는가(Epic 1 회고 F2).
     *
     * <p>{@code OAuth2LoginAuthenticationFilter}가 인가 요청을 지우기 <b>전에</b> 던지는 두 code만
     * 해당한다.
     * <ul>
     *   <li>{@code invalid_request} — 요청이 인가 응답 모양이 아니다({@code state}가 없거나,
     *       {@code code}·{@code error}가 모두 없다). 제공자가 유효한 {@code state}와 함께 되돌린
     *       {@code error=invalid_request}도 같은 code로 오지만 그것은 인가 요청을 소비한 제공자
     *       오류이므로, 요청 모양을 함께 보아 필터가 스스로 던진 것만 고른다.
     *   <li>{@code authorization_request_not_found} — 세션의 인가 요청과 {@code state}가 맞지 않는다.
     *       저장된 요청은 {@code state}가 맞을 때만 지워지므로 이 실패는 아무것도 소비하지 않았다.
     * </ul>
     */
    private static boolean missedAuthorizationRequest(
            HttpServletRequest request, AuthenticationException exception) {
        if (!(exception instanceof OAuth2AuthenticationException oauth2Exception)) {
            return false;
        }
        String code = oauth2Exception.getError().getErrorCode();
        if (AUTHORIZATION_REQUEST_NOT_FOUND_ERROR_CODE.equals(code)) {
            return true;
        }
        return OAuth2ErrorCodes.INVALID_REQUEST.equals(code) && !isAuthorizationResponse(request);
    }

    /**
     * {@code OAuth2LoginAuthenticationFilter}가 인가 응답으로 받아들이는 모양인가. 그 필터의 판정
     * ({@code OAuth2AuthorizationResponseUtils.isAuthorizationResponse}, package-private)과 같은 식이다 —
     * {@code state}가 있고 {@code code}나 {@code error}가 있다.
     */
    private static boolean isAuthorizationResponse(HttpServletRequest request) {
        return StringUtils.hasText(request.getParameter(OAuth2ParameterNames.STATE))
                && (StringUtils.hasText(request.getParameter(OAuth2ParameterNames.CODE))
                        || StringUtils.hasText(request.getParameter(OAuth2ParameterNames.ERROR)));
    }

    /**
     * 지금 세션이 가진 목적지. 없으면 기본 대시보드다. 운영자 화면은 지금 운영자일 때만 남는다
     * ({@link OperatorAccess#foldDestination}).
     */
    private String currentDestination(HttpServletRequest request) {
        return currentDestination(request, ControllerSupport.currentUserId());
    }

    /**
     * {@link #currentDestination(HttpServletRequest)}의 사용자를 호출자가 주는 형태. thread-local 인증이
     * 이미 비워진 실패 핸들러가 저장된 주체로 같은 목적지를 낸다.
     */
    private String currentDestination(HttpServletRequest request, @Nullable UUID userId) {
        String destination =
                Optional.ofNullable(allowedDestination(attribute(request, DESTINATION_ATTRIBUTE)))
                        .orElse(DASHBOARD_PATH);
        return this.operators.foldDestination(userId, destination);
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
        if (OPERATOR_PATH.equals(destination)) {
            return "operator";
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
