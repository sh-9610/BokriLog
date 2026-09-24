package com.bokrilog;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 표면의 HTTP 경계.
 *
 * <p>다섯 가지만 답한다. 로그인 상태 화면이 서버 세션으로 상태를 확인하는 경로 하나, 재인증
 * 흐름을 세션에 심는 경로 하나, 연결 상태를 조회하는 경로 하나, 연결 흐름을 시작하는 경로 하나,
 * 그리고 인증 시작이 성립하지 않을 때의 공통 오류 계약 하나다.
 * 나머지(authorization request 발급, 콜백 검증)는 Spring Security의 필터가 서블릿보다 앞에서
 * 처리하므로 이 컨트롤러에 오지 않는다.
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
     * 연결의 판정과 실행.
     *
     * <p>이 컨트롤러는 HTTP 경계만 소유한다. 연결 여부·재인증 신선도·허가·매핑은 전부 그쪽이
     * 답하며, 여기서 하는 일은 주체를 읽어 넘기고 결과를 상태 코드로 옮기는 것뿐이다.
     */
    private final LinkService linkService;

    AuthSessionController(LinkService linkService) {
        this.linkService = linkService;
    }

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
     * @param reauthRequired 이 세션이 재인증을 요구받았는가. <b>표시된 세션에만</b> 실리고 그
     *     밖에서는 {@code null}이라 나가지 않는다. Story 1.5는 표시와 폐기를 응답으로 구분하지
     *     않았고 그 근거를 "이 스토리에는 재인증 화면이 없으므로"라고 {@code SessionGuard}
     *     javadoc에 적었다 — Story 1.8이 그 화면을 만들면서 그 예외를 되짚는다. 구분하지 않으면
     *     표시된 세션은 CSRF 토큰을 받지 못하고, 진입 화면의 로그인 버튼은 인증 시작 필터에
     *     거절당해 목적지로 되돌려지며, 그 목적지는 다시 미인증으로 답한다. 구분을 열어도
     *     공격자가 얻는 것은 "이 세션이 표시됐다"는 사실 하나이고 회복에 필요한 것은 제공자
     *     자격이므로 그 사실만으로는 아무 일도 할 수 없다. <b>폐기된 세션과 미인증 요청의 본문은
     *     그대로다</b> — 그 둘에는 회복 경로가 없으므로 구분할 이유도 없다
     * @param csrfToken 세션 귀속 CSRF 토큰. 인증된 세션과 <b>표시된 세션</b>에만 실린다. 상태
     *     변경 요청({@code POST /api/auth/logout}·{@code POST /api/auth/reauthenticate/…})이 이
     *     값을 {@code AuthSecurityConfig#CSRF_HEADER_NAME} 헤더로 되돌려 보낸다. 쿠키 저장소를
     *     쓰지 않는 구성에서 클라이언트가 토큰을 얻는 유일한 경로이며, 그래서 브라우저가 갖는
     *     쿠키가 세션 하나로 유지된다. 이 값은 세션에 귀속하므로 다른 세션에서는 통하지 않고,
     *     응답의 {@code no-store}와 진입점의 {@code Referrer-Policy: no-referrer}가 유출 경로를
     *     닫는다
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SessionState(
            boolean authenticated,
            String destination,
            @Nullable Boolean reauthRequired,
            @Nullable String csrfToken) {}

    @GetMapping(path = AuthSecurityConfig.SESSION_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionState> session(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated =
                authentication != null
                        && authentication.isAuthenticated()
                        && !(authentication instanceof AnonymousAuthenticationToken);

        /*
         * 폐기된 세션은 여기 오기 전에 미인증으로 수렴한다. `AuthFlowHandlers`의 세션 guard
         * 필터가 `SecurityContextHolderFilter` 뒤에서 판정하고 그 결과가 위 `authenticated`에
         * 그대로 반영된다.
         *
         * 재인증을 요구받은 세션만 아래 표시로 갈린다. 그 세션은 행이 남아 있으므로 속성을 읽을
         * 수 있고, 폐기된 세션은 행 자체가 없어 여기서도 아무 값이 나오지 않는다 — 두 상태를
         * 가르는 것이 저장소 한 곳이라는 Story 1.5의 계약이 그대로 쓰인다.
         */
        boolean reauthRequired = !authenticated && carriesReauthMarker(request);

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
                .body(
                        new SessionState(
                                authenticated,
                                destination,
                                reauthRequired ? Boolean.TRUE : null,
                                csrfToken(request, authenticated || reauthRequired)));
    }

    /**
     * 이 세션이 재인증 요구 표시를 들고 있는가.
     *
     * <p>세션 속성을 직접 읽는다. 표시된 세션의 thread-local 인증은 guard 필터가 이미 지웠으므로
     * {@code SecurityContextHolder}로는 표시와 미인증이 구분되지 않는다. 세션을 새로 만들지
     * 않는다 — 요청이 세션을 들고 오지 않았다면 표시할 대상 자체가 없다.
     */
    private static boolean carriesReauthMarker(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null
                && Boolean.TRUE.equals(session.getAttribute(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE));
    }

    /**
     * 이 세션의 CSRF 토큰. 토큰을 받을 자격이 없으면 {@code null}이다.
     *
     * <p>자격은 둘이다 — 인증된 세션과 재인증을 요구받은 세션. 두 번째가 Story 1.8이 넓힌
     * 자리이며, 표시된 세션이 토큰을 받지 못하면 그 세션은 재인증 시작을 보낼 수 없고 회복
     * 경로가 없다. 로그아웃도 같은 토큰을 쓰므로 표시된 세션은 이제 화면에서 스스로 나갈 수도
     * 있다.
     *
     * <p>그 밖에서 부르지 않는 것이 중요하다. {@code CsrfFilter}가 요청 속성에 얹어 두는 값은
     * 지연 토큰이고, {@code getToken()}이 그 자리에서 토큰을 만들어 저장소에 쓴다. 세션 저장소
     * 구현은 세션이 없으면 저장을 건너뛰지만({@code AuthSecurityConfig}), 그렇더라도 미인증
     * 응답에 실을 값이 아니다 — 세션에 귀속되지 않은 토큰은 어떤 상태 변경도 통과시키지 못하고,
     * 매 요청 새 값이라 응답을 캐시 불가로 만드는 이유만 늘린다.
     *
     * <p>빈 값도 부재로 다룬다. {@code @JsonInclude(NON_NULL)}은 {@code NON_EMPTY}가 아니므로 빈
     * 문자열은 {@code "csrfToken":""}으로 실려 나가고, 클라이언트는 그 값으로 어떤 상태 변경도
     * 통과시킬 수 없다. 필드가 있으면 토큰을 받았다는 뜻이어야 하므로 이 접기가 컨트롤러의 일이다.
     *
     * @param eligible 이 세션이 토큰을 받을 자격이 있는가
     */
    private static @Nullable String csrfToken(HttpServletRequest request, boolean eligible) {
        if (!eligible) {
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
     * <p>구성된 등록을 여기서 조회하지 않는다. 등록이 있으면 이 메서드에 도달하지 않으므로
     * {@code findByRegistrationId}의 답은 언제나 "없음"이고, 그것을 술어로 쓰면 두 경우가 모두 404가
     * 되어 fail-closed의 500이 사라진다. 남는 판정은 "아는 제공자 <b>이름</b>인가" 하나이며 그
     * 닫힌 집합은 {@code AuthSecurityConfig.ProviderClientRegistrations}가 소유한다.
     *
     * <p>아는 제공자 목록도 개수도 응답에 실리지 않는다. 두 답은 공통 오류 계약의 본문 두 개이며
     * {@code provider} 값을 되비추지 않는다 — 그러지 않으면 이 경로가 곧 "이 서비스가 어떤 제공자를
     * 아는가"를 열거하는 표면이 된다.
     */
    @GetMapping(AuthSecurityConfig.AUTHORIZATION_BASE_URI + "/{provider}")
    void authorizationUnavailable(
            @PathVariable String provider, HttpServletResponse response) throws IOException {
        if (!AuthSecurityConfig.ProviderClientRegistrations.isSupportedProvider(provider)) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    /**
     * 재인증 흐름을 시작한다.
     *
     * <p>이 요청이 하는 일은 세션에 일회성 흐름을 심는 것 하나다. 제공자로 나가지 않고 어떤
     * 상태도 확정하지 않으며, 그 다음 단계는 클라이언트가 여는
     * {@code GET /api/auth/authorize/<제공자>} 최상위 내비게이션이다. 두 단계를 나눈 이유는
     * 보호다 — 무장은 상태 변경이라 세션 귀속 CSRF 토큰과 동일 출처 {@code Origin}을 지나야 하고
     * ({@code /api/auth/} 아래이므로 자동이다), 인가 시작은 제공자로 가는 내비게이션이라
     * {@code GET}이어야 한다.
     *
     * <p>답은 셋뿐이다.
     *
     * <ul>
     *   <li>모르는 제공자 — 그 경로는 존재하지 않는다. 404다. 아는 제공자 <b>목록도 개수도</b>
     *       응답에 실리지 않는다.
     *   <li>주체 없음 — 무장할 세션이 없다. 403이다. 미인증 요청은 여기 오기 전에 CSRF 단계에서
     *       같은 403으로 수렴하므로 두 경우가 응답으로 구분되지 않는다.
     *   <li>그 밖 — 목적지를 붙들어 무장하고 204. 본문이 없다.
     * </ul>
     *
     * <p>등록 구성 여부를 조회하지 않는다. 그 판정은 뒤따르는 인가 시작이 이미 소유하고
     * ({@link #authorizationUnavailable}), 여기서 한 번 더 하면 같은 fail-closed가 두 곳에서
     * 갈린다. 연결된 제공자 목록도 보지 않는다 — 보면 이 경로가 곧 "이 계정이 어느 제공자에
     * 연결됐는가"를 알려 주는 표면이 되고, 연결되지 않은 제공자를 고른 사용자는 콜백에서 다른
     * 실패와 구분되지 않는 하나로 수렴한다.
     */
    @PostMapping(AuthSecurityConfig.REAUTHENTICATION_BASE_URI + "/{provider}")
    void startReauthentication(
            @PathVariable String provider, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!AuthSecurityConfig.ProviderClientRegistrations.isSupportedProvider(provider)) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        UUID userId = currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null || session == null) {
            // 무장할 대상이 없다. 미인증 상태 변경과 같은 하나의 코드이며 사유를 남기지 않는다.
            response.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }

        /*
         * 목적지를 여기서 붙든다.
         *
         * 출처는 세션이 이미 가진 확정 목적지 하나이고 클라이언트는 목적지를 보내지 않는다.
         * 요청 본문이나 쿼리에서 읽으면 콜백이 들고 온 값이 목적지가 되는 두 번째 경로가 열린다.
         * 세션에 값이 없거나 허용 목록 밖이면 기본 대시보드다.
         */
        String destination =
                Optional.ofNullable(AuthFlowHandlers.allowedDestination(sessionAttribute(request)))
                        .orElse(AuthFlowHandlers.DASHBOARD_PATH);
        /*
         * 남아 있는 연결 무장을 먼저 버리고 그 허가도 함께 무효화한다.
         *
         * 한 세션에 두 흐름이 함께 무장돼 있으면 콜백이 어느 쪽 것인지를 등록 id로 매번 가려야
         * 하고, 그 판정이 한 번 어긋나면 재인증 콜백이 연결 분기에 삼켜진다 — 그때 재인증 시각이
         * 기록되지 않아 본인 확인이 성립하지 않고 표시도 풀리지 않는다. 설정 화면이 연결 버튼과
         * 본인 확인 버튼을 같은 페이지에 그리므로 그 상태는 평범한 경로다.
         *
         * 흐름만 버리면 그 무장이 가리키던 허가가 아무도 참조할 수 없는 채 수명을 산다. 두 가지를
         * 함께 하는 자리는 `LinkService.abandon` 하나다.
         */
        this.linkService.abandon(session);
        ReauthenticationFlow.arm(session, provider, userId, destination);
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }

    /**
     * 연결 상태.
     *
     * <p>{@code providers} 하나뿐이고 값은 <b>제공자 이름</b>의 배열이다. {@code issuer}·
     * {@code subject}·이메일·토큰·연결 시각을 싣지 않는다 — {@code identity} 공개 API가 이름
     * 집합만 돌려주므로 여기서 실을 수 있는 다른 값이 애초에 없다.
     *
     * <p>순서가 고정이다. 화면이 아는 고정 순서와 같은 목록을 지나므로 응답이 실행마다 흔들리지
     * 않고, 그 안정성이 없으면 본문 전문을 고정하는 검증이 성립하지 않는다.
     *
     * @param providers 이 계정에 연결된 제공자 이름
     */
    record LinkState(List<String> providers) {}

    /**
     * 이 계정에 연결된 제공자.
     *
     * <p>인증된 소유자에게 <b>자기 계정의 사실</b>을 보여 주는 자리다. Story 1.9가
     * {@link #startReauthentication}에 "연결된 제공자 목록도 보지 않는다"를 적어 둔 것과 성질이
     * 다르다 — 그쪽은 <b>미인증·재인증 시작</b> 경로라 목록을 보면 열거 표면이 되지만, 여기는
     * 세션이 이미 결정한 사용자에게 그 사용자의 값을 돌려준다. 그래도 반환은 제공자 이름으로
     * 좁고 미존재·삭제 계정은 빈 집합으로 접히므로, 이 응답에서 계정의 존재나 외부 식별자를
     * 복원할 수 없다.
     *
     * <p>주체가 없으면 403이다. 미인증 요청·재인증을 요구받은 세션·폐기된 세션·수명을 넘긴 세션이
     * 모두 같은 하나로 수렴한다 — 세션 guard가 그 요청의 인증을 이미 지웠으므로 여기서는 주체
     * 하나만 보면 된다.
     *
     * <p>안전한 메서드라 CSRF·{@code Origin} 보호 대상이 아니다. 이 응답은 상태를 바꾸지 않고,
     * 동일 출처 정책이 다른 origin의 스크립트가 본문을 읽는 것을 막는다. 대신 캐시에는 남기지
     * 않는다 — 공유 캐시나 뒤로 가기가 이 값을 되살리면 로그아웃한 브라우저가 계정의 사실을
     * 다시 그린다.
     */
    @GetMapping(
            path = AuthSecurityConfig.LINK_BASE_URI,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<LinkState> links(HttpServletResponse response) throws IOException {
        UUID userId = currentUserId();
        if (userId == null) {
            response.sendError(HttpStatus.FORBIDDEN.value());
            return null;
        }
        Set<String> linked = this.linkService.linkedProviders(userId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new LinkState(
                                AuthSecurityConfig.ProviderClientRegistrations.supportedProviders()
                                        .stream()
                                        .filter(linked::contains)
                                        .toList()));
    }

    /**
     * 연결 흐름을 시작한다.
     *
     * <p>{@link #startReauthentication}과 같은 두 단계 배치다. 이 요청이 하는 일은 허가를 받고
     * 세션에 일회성 흐름을 심는 것이며, 그 다음 단계는 클라이언트가 여는
     * {@code GET /api/auth/authorize/<제공자>} 최상위 내비게이션이다. 나눈 이유도 같다 — 무장은
     * 상태 변경이라 세션 귀속 CSRF 토큰과 동일 출처 {@code Origin}을 지나야 하고, 인가 시작은
     * 제공자로 가는 내비게이션이라 {@code GET}이어야 한다.
     *
     * <p>답은 셋뿐이다.
     *
     * <ul>
     *   <li>모르는 제공자 — 그 경로는 존재하지 않는다. 404다. 아는 제공자 <b>목록도 개수도</b>
     *       응답에 실리지 않는다.
     *   <li>주체 없음, 또는 시작이 성립하지 않음 — 403이다. 이미 연결된 제공자·재인증 없음·
     *       오래된 재인증·의심 세션·삭제 상태가 모두 이 하나로 수렴하며 <b>응답으로 구분되지
     *       않는다</b>. 다른 계정의 존재나 소유자를 드러내지 않는다는 계약이 여기서 시작된다.
     *   <li>그 밖 — 허가를 발급하고 무장하고 204. 본문이 없다.
     * </ul>
     *
     * <p>복귀 목적지를 받지 않는다. 연결 조작이 사는 화면은 하나이므로 그 경로는 서버 상수이며
     * ({@code AuthFlowHandlers.SETTINGS_PATH}) 클라이언트가 보낼 값이 없다.
     */
    @PostMapping(AuthSecurityConfig.LINK_BASE_URI + "/{provider}")
    void startLink(
            @PathVariable String provider, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!AuthSecurityConfig.ProviderClientRegistrations.isSupportedProvider(provider)) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        UUID userId = currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null
                || session == null
                || this.linkService.start(session, userId, provider).isEmpty()) {
            // 거부는 하나의 값이다. 사유를 남기지 않는다.
            response.sendError(HttpStatus.FORBIDDEN.value());
            return;
        }
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }

    /**
     * 지금 요청 주체의 내부 {@code user_id}.
     *
     * <p>{@code AuthFlowHandlers}가 로그인 성공 시 심은 {@link PreAuthenticatedAuthenticationToken}
     * 의 이름이다. 그 값이 세션 행의 {@code principal_name}이자 이 애플리케이션이 아는 유일한
     * 사용자 식별자이며, 클라이언트가 보낸 값이 아니다.
     *
     * @return 인증된 주체의 식별자, 없거나 읽히지 않으면 {@code null}
     */
    private static @Nullable UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            // 이 배선이 심는 이름은 언제나 UUID 문자열이다. 아니라면 무장할 대상을 모른다는
            // 뜻이므로 거부한다 — 예외를 밖으로 내보내지 않는다(스택도 로그도 남지 않는다).
            return null;
        }
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
