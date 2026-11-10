package com.bokrilog;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * 인증 표면과 세션 정책.
 *
 * <p>루트 패키지가 소유한다. 허용 모듈은 여덟 개로 열거돼 있고 Structural Seed가 이 능력을
 * {@code identity}에 귀속하지만, {@code identity}는 HTTP 표면을 가질 수 없다
 * ({@code identity/package-info.java}, {@code ModuleBoundaryTest}). 두 제약을 함께 만족시키는
 * 배치는 하나뿐이다 — 판정·매핑·조회는 {@code identity} 공개 API가, HTTP 표면과 세션 정책은
 * 루트 패키지가 소유한다. 루트 패키지는 Modulith 모듈이 아니므로 이 배치는 모듈 간 의존
 * 간선을 새로 만들지 않는다. {@code SchemaValidationConfig}가 기동 정책을 루트에 둔 것과 같은
 * 이유다.
 *
 * <p>검증을 손으로 만들지 않는다. Authorization Code + PKCE(S256), 일회성 {@code state}·
 * {@code nonce}, ID Token의 JWS 서명·허용 알고리즘·JWKS와 {@code iss}·{@code aud}·{@code azp}·
 * {@code exp}·{@code nonce} 검증, 세션 고정 방어는 모두 Spring Security OAuth2 Client의 계약이다.
 * 이 클래스가 하는 일은 그 계약을 이 저장소의 경계에 맞추는 배선뿐이다.
 *
 * <p>보안 응답 헤더를 만들지 않는다. CSP를 비롯한 보안 헤더는 {@code deploy/nginx/bokrilog.conf}가
 * 유일한 정의이며, Spring Security의 기본 헤더 writer를 끄지 않으면 진입점과 애플리케이션이 같은
 * 헤더를 각각 실어 값이 두 번 나간다. 그때 헤더 값을 전문 비교하는
 * {@code verify-same-origin.sh}가 먼저 깨진다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다. 같은 이미지의 일회성 migration 역할은
 * {@code spring.main.web-application-type: none}으로 뜨고(AD-12), 그 프로세스에는 {@code HttpSecurity}
 * 자체가 없다. 조건 없이 두면 마이그레이션이 컨텍스트 생성 실패로 끝나 스키마를 바꾸는 유일한
 * 경로가 막힌다. 같은 조건이 {@code AuthFlowHandlers}·{@code AuthSessionService}·
 * {@code AuthSessionController}에도 붙어 인증 표면 전체가 API 프로세스의 것으로 남는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties({
    AuthSecurityConfig.AuthProperties.class,
    SessionGuard.SessionProperties.class
})
class AuthSecurityConfig {

    /** 인증 시작 경로의 접두사. 뒤에 제공자 등록 id가 붙는다({@code /api/auth/authorize/google}). */
    static final String AUTHORIZATION_BASE_URI = "/api/auth/authorize";

    /** 콜백 경로. {@code /api}만 프록시하는 Nginx 블록이 그대로 받는다. */
    static final String CALLBACK_BASE_URI = "/api/auth/callback";

    /** Google 제공자의 등록 id. */
    static final String GOOGLE_REGISTRATION_ID = "google";

    /** 네이버 제공자의 등록 id. */
    static final String NAVER_REGISTRATION_ID = "naver";

    /** 카카오 제공자의 등록 id. */
    static final String KAKAO_REGISTRATION_ID = "kakao";

    /** 세션 확인 경로. 로그인 상태 화면이 서버 세션으로 상태를 확인하는 유일한 경로다. */
    static final String SESSION_PATH = "/api/auth/session";

    /**
     * 로그아웃 경로. 이 하나뿐이다.
     *
     * <p>{@code /api/auth/} 아래이므로 아래 {@link #PROTECTED_PATH_PREFIX}의 보호(세션 귀속 CSRF
     * 토큰 + 동일 출처 {@code Origin})가 예외 없이 걸린다. 그것이 이 경로를 여기 둔 이유다.
     */
    static final String LOGOUT_PATH = "/api/auth/logout";

    /**
     * 재인증 시작 경로의 접두사. 뒤에 제공자 등록 id가 붙는다
     * ({@code /api/auth/reauthenticate/google}).
     *
     * <p>{@code POST}다. 이 요청이 하는 일은 세션에 일회성 흐름을 심는 것이고 그것은 상태 변경
     * 이므로 safe method가 될 수 없다. 그리고 {@code /api/auth/} 아래이므로 아래
     * {@link #PROTECTED_PATH_PREFIX}의 보호(세션 귀속 CSRF 토큰 + 동일 출처 {@code Origin})가
     * 예외 없이 걸린다 — 로그아웃을 여기 둔 것과 같은 이유다.
     *
     * <p>인가 시작({@link #AUTHORIZATION_BASE_URI})과 경로를 나눈 것이 요점이다. 인가 시작은
     * 제공자로 가는 최상위 내비게이션이라 {@code GET}이어야 하고, 그 경로에 무장을 얹으면 표지가
     * 붙은 링크 하나로 남의 세션에 재인증 흐름을 심을 수 있다.
     */
    static final String REAUTHENTICATION_BASE_URI = "/api/auth/reauthenticate";

    /**
     * 연결 표면의 경로. <b>이 계층에서 그 경로를 정의하는 상수는 이것 하나다.</b>
     *
     * <p>두 표면이 한 접두사를 나눠 쓰고 메서드가 그것을 가른다 — {@code GET /api/auth/link}는
     * 연결 상태 조회이고 {@code POST /api/auth/link/<제공자>}는 연결 시작이다. 이름을 둘로 두면
     * "이 경로가 어디 정의됐나"의 답이 둘이 된다.
     *
     * <p>조회는 인증된 소유자에게 <b>자기 계정의 사실</b>을 돌려준다. 응답에 실리는 것은 제공자
     * 이름뿐이며 {@code issuer}·{@code subject}·이메일·토큰·연결 시각은 담지 않는다
     * ({@code AuthSessionController}). 안전한 메서드라 CSRF·{@code Origin} 보호 대상이 아니고,
     * 주체가 없으면 컨트롤러가 공통 오류 계약의 403으로 접는다.
     *
     * <p>시작이 {@code POST}인 근거는 {@link #REAUTHENTICATION_BASE_URI}와 같다. 허가를 발급하고
     * 세션에 일회성 흐름을 심는 상태 변경이므로 safe method가 될 수 없고, {@code /api/auth/}
     * 아래이므로 아래 {@link #PROTECTED_PATH_PREFIX}의 보호(세션 귀속 CSRF 토큰 + 동일 출처
     * {@code Origin})가 예외 없이 걸린다.
     *
     * <p><b>세션 guard의 403 예외 목록에 들어가지 않는다.</b> 재인증 시작은 표시된 세션의 유일한
     * 회복 경로라 면제되지만({@link #isReauthenticationStart}) 연결은 그 반대다 — 재인증을
     * 요구받은 세션이 계정에 매핑을 더할 수 있으면 그 표시가 막으려던 것이 그대로 열린다. 그래서
     * 이 경로에는 대응하는 판정 메서드가 없고, 표시된 세션의 연결 시작은 다른 모든 상태 변경과
     * 같은 403으로 끝난다.
     */
    static final String LINK_BASE_URI = "/api/auth/link";

    /**
     * 해제 표면의 경로. <b>이 계층에서 그 경로를 정의하는 상수는 이것 하나다.</b>
     *
     * <p>세 표면이 한 접두사를 나눠 쓰고 <b>경로가</b> 그것을 가른다 —
     * {@code POST /api/auth/unlink/<제공자>}는 확인 대화상자를 열 준비(허가 발급·무장),
     * {@code POST /api/auth/unlink/<제공자>/confirm}은 그것을 소비하는 최종 확인,
     * {@code POST /api/auth/unlink/<제공자>/cancel}은 그 준비를 되돌리는 취소다. 연결이 메서드로
     * 두 표면을 가른 것과 다른 이유는 셋 다 상태 변경이기 때문이다 — 안전한 메서드로 놓을 수 있는
     * 것이 없다.
     *
     * <p>준비와 확인을 한 경로로 합치지 않는다. 합치면 "확인 대화상자를 여는 요청"과 "그 확인을
     * 실행하는 요청"이 구분되지 않아, 대화상자를 여는 순간 매핑이 사라진다. "최종 확인 전에는
     * 아무것도 바뀌지 않는다"는 계약이 그 둘로 성립한다.
     *
     * <p>취소가 <b>셋째</b>인 이유는 대칭이다. 준비가 서버 상태를 만드는 설계라면 되돌리는 경로가
     * 있어야 하고, 없으면 화면의 "바뀐 것은 없습니다"가 거짓이 되며 아무도 참조하지 않는 허가가
     * 수명을 산다. 연결에는 이 짝이 없다 — 그쪽의 취소는 제공자 콜백으로 돌아오므로 서버가 그
     * 사실을 알지만, 해제의 취소는 브라우저 안에서만 일어난다.
     *
     * <p>{@code POST}인 근거는 {@link #REAUTHENTICATION_BASE_URI}·{@link #LINK_BASE_URI}와 같다.
     * {@code /api/auth/} 아래이므로 아래 {@link #PROTECTED_PATH_PREFIX}의 보호(세션 귀속 CSRF 토큰 +
     * 동일 출처 {@code Origin})가 예외 없이 걸린다.
     *
     * <p><b>세션 guard의 403 예외 목록에 들어가지 않는다.</b> 연결과 같은 이유다 — 재인증을 요구받은
     * 세션이 계정에서 로그인 수단을 떼어 낼 수 있으면 그 표시가 막으려던 것이 그대로 열리며, 해제는
     * 연결보다 되돌리기 어렵다. 그래서 이 경로에도 대응하는 판정 메서드가 없고, 표시된 세션의 해제는
     * 다른 모든 상태 변경과 같은 403으로 끝난다.
     */
    static final String UNLINK_BASE_URI = "/api/auth/unlink";

    /**
     * 최종 확인 경로의 마지막 세그먼트.
     *
     * <p>{@link #UNLINK_BASE_URI} 뒤에 제공자 이름과 이 값이 붙는다. 상수로 두는 이유는 컨트롤러
     * 매핑과 시험·검증 스크립트가 같은 문자열을 봐야 하기 때문이며, 컴파일 시점 상수라 애노테이션
     * 안에서 이어 붙일 수 있다.
     */
    static final String UNLINK_CONFIRM_SEGMENT = "confirm";

    /**
     * 취소 경로의 마지막 세그먼트.
     *
     * <p>{@link #UNLINK_CONFIRM_SEGMENT}와 같은 근거의 같은 모양이다.
     */
    static final String UNLINK_CANCEL_SEGMENT = "cancel";

    /**
     * CSRF 토큰이 실리는 요청 헤더 이름.
     *
     * <p>Spring Security의 기본값과 같은 값을 명시한다. 이 문자열은 프런트엔드
     * ({@code frontend/src/content/auth.ts})에도 리터럴로 있다 — 두 계층이 한 계약을 나눠 갖는
     * 자리이며, 갈라지면 로그아웃이 403으로만 끝난다. 오류 code 문자열이 Nginx 구성과 중복되는
     * 것과 같은 종류의 중복이고, 같은 방식으로 시험이 붙잡는다.
     */
    static final String CSRF_HEADER_NAME = "X-CSRF-TOKEN";

    /** 인증이 필요 없는 서버 경계 확인 경로. Story 1.2의 계약이며 이 스토리가 바꾸지 않는다. */
    static final String STATUS_PATH = "/api/status";

    /**
     * 금융 source 연결 표면의 접두사. <b>인증으로 막는 첫 경로다</b>(Story 2.1).
     *
     * <p>이 접두사 아래에만 {@code authenticated()}가 걸린다. 전역으로 켜지 않는 이유는
     * {@link #authSecurityFilterChain}의 근거 주석에 있다 — Story 1.2가 고정한 404·405가 401·302로
     * 뒤집힌다.
     *
     * <p>{@code /api/auth/}와 함께 아래 {@link #PROTECTED_PATH_PREFIXES}에 든다. 상태 변경이 세션
     * 귀속 CSRF 토큰과 동일 출처 {@code Origin}을 지나야 하는 것은 두 접두사가 같고, <b>판정 함수는
     * 하나로 유지한다</b>({@link #isProtectedRequest}).
     *
     * <p>Nginx에 새 location이 필요 없다. {@code /api/}를 통째로 프록시하는 블록이 이미 이 경로를
     * 받는다({@code deploy/nginx/bokrilog.conf}).
     */
    static final String CONNECTIONS_BASE_URI = "/api/connections";

    static final String FIRST_SNAPSHOT_PATH = "/api/snapshots/first";
    static final String PLAN_CONDITIONS_PATH = "/api/plans/conditions";

    /**
     * 공식 대시보드(Story 2.8). 활성 계획의 공식 포인터가 가리키는 한 묶음과 세 신뢰 축, 또는
     * {@code NO_PLAN}·{@code PREPARING} 상태만 나간다.
     *
     * <p>Story 2.7의 {@code /api/plans/active}를 대체한다. 그 경로는 없어졌다 — 두 표면을 두면 삭제·캐시·
     * 버전 판정이 두 곳이 된다. 경로마다 판정 하나를 두고 인가와 CSRF·{@code Origin} 판정이 그 같은
     * 함수({@link #isDashboardPath})를 본다. 하위 경로도 같은 판정에 걸려 인증 없이는 401, 인증되면
     * 대상의 존재와 무관한 같은 404다.
     *
     * <p>하위 경로 가운데 일곱만 핸들러가 있다 — Story 2.9의 {@code GET /api/dashboard/evidence}
     * ({@code DashboardController.EVIDENCE_PATH}), Story 2.10의 {@code GET /api/dashboard/adjustments}
     * ({@code DashboardController.ADJUSTMENTS_PATH}), Story 2.11의 {@code POST /api/dashboard/sync}
     * ({@code DashboardController.SYNC_PATH}), Story 2.12의 {@code POST /api/dashboard/verification}
     * ({@code DashboardController.VERIFICATION_PATH}), 그리고 Story 2.13의 계획 중도 종료 셋 —
     * {@code POST /api/dashboard/plan-end}(준비)·{@code /confirm}(확정)·{@code /cancel}(취소)
     * ({@code PlanEndController})이다. 같은 술어가 이미 덮으므로 판정을 더하지 않는다.
     * 쿼리로 받은 세 참조가 그 사용자의 현재 공식 포인터가 아니면 — 조정 이력은 cursor가 그 사용자·참조·
     * 필터의 것이 아니어도 — 없는 경로와 본문이 글자까지 같은 404이고, 나머지 하위 경로는 그대로 같은
     * 404다. 앞의 둘은 읽기 전용 {@code GET}이라 CSRF 판정이 닿지 않는다. 동기화·검증 요청과 종료 셋은 이
     * 표면의 상태 변경이며, 같은 술어({@link #isProtectedRequest})가 세션 귀속 CSRF 토큰과 동일 출처
     * {@code Origin}을 요구한다 — 그 판정도 더하지 않는다.
     */
    static final String DASHBOARD_PATH = "/api/dashboard";

    /**
     * 보호된 운영자 표면(Story 3.1). 감사 조회·operation 조회·운영 command 준비/확정/취소가 이 접두사 아래에 있다.
     *
     * <p>인가와 CSRF·{@code Origin}이 같은 판정 함수({@link #isOperatorPath})를 본다. 그 위에 운영자 게이트
     * ({@link OperatorGate})가 인가 필터 뒤에서 한 번 더 판정한다 — 인증된 비운영자·권한 부족은 이 접두사의 <b>모든</b>
     * 경로·메서드에서 없는 경로와 같은 404다(405로 경로 존재가 새지 않는다). 미인증은 다른 보호 경로와 같은 401이다.
     */
    static final String OPERATOR_API_PATH = "/api/operator";

    /**
     * 인증 사용자의 서비스 공지(Story 3.5). 그 사용자에게 적용되는 효력 제한과 그것을 덮는 공지만 나간다.
     *
     * <p>인가와 CSRF·{@code Origin}이 같은 판정 함수({@link #isNoticesPath})를 본다. 읽기 전용 {@code GET}이라 CSRF 판정은
     * 닿지 않지만 술어는 하나로 둔다. 하위 경로도 같은 판정에 걸려 인증 없이는 401, 인증되면 같은 404다.
     */
    static final String NOTICES_PATH = "/api/notices";

    /**
     * 인증 사용자의 문의(Story 3.6). 접수({@code POST /api/support/inquiries})와 내 문의 목록({@code GET})이 이 접두사 아래에
     * 있다.
     *
     * <p>인가와 CSRF·{@code Origin}이 같은 판정 함수({@link #isSupportPath})를 본다. 접수는 상태 변경이라 세션 귀속 CSRF
     * 토큰과 동일 출처 {@code Origin}을 요구한다. 하위 경로도 같은 판정에 걸려 인증 없이는 401, 인증되면 없는 경로와 같은 404다.
     */
    static final String SUPPORT_PATH = "/api/support";

    /**
     * 광고 참여(Story 4.1~4.5). 광고 동의의 조회와 변경({@code GET}·{@code POST /api/engagement/ad-consent}), 광고 자격
     * 판정({@code POST /api/engagement/ad-eligibility}), transition 소비({@code POST /api/engagement/ad-transition}, Story 4.3),
     * 브라우저 계측 beacon({@code POST /api/engagement/telemetry}, Story 4.5)이 이 접두사 아래에 있다.
     *
     * <p>인가와 CSRF·{@code Origin}이 같은 판정 함수({@link #isEngagementPath})를 본다. 동의 변경·자격 판정·소비·계측은 상태 변경이라
     * 세션 귀속 CSRF 토큰과 동일 출처 {@code Origin}을 요구한다. 하위 경로도 같은 판정에 걸려 인증 없이는 401, 인증되면 없는 경로와 같은
     * 404다.
     */
    static final String ENGAGEMENT_PATH = "/api/engagement";

    /**
     * 사용자의 계정 삭제 요청(Story 5.1). 삭제 정책·요청 상태 조회({@code GET /api/account/deletion})와 준비·확정·취소
     * ({@code POST /api/account/deletion}·{@code /confirm}·{@code /cancel}), 삭제 진행 세션의 다시 시도
     * ({@code POST /api/account/deletion/retry}, Story 5.2)가 이 접두사 아래에 있다.
     *
     * <p>인가와 CSRF·{@code Origin}이 같은 판정 함수({@link #isAccountPath})를 본다. 준비·확정·취소·다시 시도는 상태 변경이라 세션 귀속 CSRF
     * 토큰과 동일 출처 {@code Origin}을 요구한다. 하위 경로도 같은 판정에 걸려 인증 없이는 401, 인증되면 없는 경로와 같은 404다.
     * 복수형 {@code /api/accounts}는 이 접두사가 아니다(identity 미노출 탐침이 그 404를 고정한다).
     */
    static final String ACCOUNT_PATH = "/api/account";

    /** Bybit 연결 표면의 경로. 이 계층에서 그 경로를 정의하는 상수는 이것 하나다. */
    static final String BYBIT_CONNECTION_PATH = CONNECTIONS_BASE_URI + "/bybit";

    /**
     * 최종 확인 경로의 마지막 세그먼트.
     *
     * <p>준비와 확인을 한 경로로 합치지 않는다. 합치면 폼을 여는 요청과 자격정보를 보내는 요청이
     * 구분되지 않아 "최종 확인 전에는 아무것도 바뀌지 않는다"가 성립하지 않는다
     * ({@link #UNLINK_CONFIRM_SEGMENT}와 같은 근거).
     */
    static final String CONNECT_SEGMENT = "connect";

    /** 취소 경로의 마지막 세그먼트. 준비가 만든 무장과 허가를 되돌린다. */
    static final String CANCEL_SEGMENT = "cancel";

    /**
     * 상태를 바꾸지 않는 메서드. RFC 9110의 safe method에 {@code OPTIONS}를 더한 집합이며
     * Spring Security의 기본 CSRF 제외 집합과 같다.
     */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** 디코딩·세미콜론 제거를 하는 경로 도구. 설정을 바꾸지 않으므로 공유해도 안전하다. */
    private static final UrlPathHelper PATHS = newPathHelper();

    private static UrlPathHelper newPathHelper() {
        UrlPathHelper helper = new UrlPathHelper();
        helper.setUrlDecode(true);
        helper.setRemoveSemicolonContent(true);
        return helper;
    }

    /**
     * CSRF·{@code Origin} 검증이 걸리는 경로 접두사.
     *
     * <p>Story 2.1이 집합으로 넓혔다. 값이 둘인 것이 요점이 아니라 <b>판정이 하나</b>인 것이
     * 요점이다 — {@link #isProtectedRequest}가 여전히 그 판정의 유일한 자리이고, 세션 guard와 CSRF
     * 매처와 {@code SameOriginFilter}가 그 함수 하나를 본다. 접두사마다 판정을 따로 두면 한쪽만
     * 고쳐지는 날 그 접두사의 상태 변경이 보호 없이 지나간다.
     *
     * <p>전역으로 넓히지 않는다. {@code /api/status}·{@code /api/nope}에 이 보호를 걸면 Story 1.2가
     * 고정한 405·404가 403으로 뒤집힌다.
     */
    private static final Set<String> PROTECTED_PATH_PREFIXES = Set.of("/api/auth/");

    /*
     * 네이버 OIDC 엔드포인트.
     *
     * 공식 discovery 문서(`https://nid.naver.com/.well-known/openid-configuration`, 2026-09-08 실측)가
     * 광고하는 값을 그대로 옮긴 정적 상수다. 기동 시점에 discovery 왕복을 하지 않는다는 계약 때문에
     * 값이 코드에 있고, `CommonOAuth2Provider`에 NAVER가 없으므로 이것을 들고 있는 자리가 여기다.
     *
     * OIDC 경로는 `/oauth2/*`이고 레거시 OAuth 2.0 API의 `/oauth2.0/*`과 다르다(공식 개발 가이드
     * §3.5). 두 계열을 섞으면 ID Token이 오지 않는다.
     *
     * 자격정보가 아니다. 공개된 제공자 주소이므로 이 리터럴은 gitleaks의 대상이 아니다.
     */
    private static final String NAVER_ISSUER_URI = "https://nid.naver.com";

    private static final String NAVER_AUTHORIZATION_URI = NAVER_ISSUER_URI + "/oauth2/authorize";

    private static final String NAVER_TOKEN_URI = NAVER_ISSUER_URI + "/oauth2/token";

    private static final String NAVER_JWK_SET_URI = NAVER_ISSUER_URI + "/oauth2/jwks";

    /*
     * 카카오 OIDC 엔드포인트.
     *
     * 공식 discovery 문서(`https://kauth.kakao.com/.well-known/openid-configuration`, 2026-09-09 실측)가
     * 광고하는 값을 그대로 옮긴 정적 상수다. 네이버와 같은 이유로 값이 코드에 있다 — 기동 시점에
     * discovery 왕복을 하지 않고 `CommonOAuth2Provider`에 KAKAO가 없다.
     *
     * JWKS 경로의 모양이 네이버와 다르다. 카카오는 issuer 바로 아래의 `/.well-known/jwks.json`이고
     * 네이버는 `/oauth2/jwks`다. 한 제공자의 모양을 다른 제공자에 옮겨 적으면 서명 검증이 통째로
     * 실패한다.
     *
     * 자격정보가 아니다. 공개된 제공자 주소이므로 이 리터럴은 gitleaks의 대상이 아니다.
     */
    private static final String KAKAO_ISSUER_URI = "https://kauth.kakao.com";

    private static final String KAKAO_AUTHORIZATION_URI = KAKAO_ISSUER_URI + "/oauth/authorize";

    private static final String KAKAO_TOKEN_URI = KAKAO_ISSUER_URI + "/oauth/token";

    private static final String KAKAO_JWK_SET_URI = KAKAO_ISSUER_URI + "/.well-known/jwks.json";

    /*
     * Google의 재인증 증거 요청.
     *
     * 공식 OpenID Connect 문서가 안내하는 `claims` 요청 파라미터의 값이다. Google discovery의
     * `claims_supported`에는 `auth_time`이 없지만 그 문서는 ID Token payload 목록에 이 claim을
     * 싣고 essential로 요청하는 방법을 함께 적는다(2026-09-21 실측).
     *
     * JSON 리터럴이다. 값이 세 겹 중첩된 객체 하나뿐이라 직렬화 라이브러리를 들이지 않는다 —
     * 이 문자열이 곧 그 문서가 보여 주는 값이고, 조립으로 만들면 무엇을 보내는지가 읽는 자리에서
     * 사라진다. 인코딩은 `OAuth2AuthorizationRequest`가 URI를 만들 때 한다.
     *
     * 자격정보가 아니다. 공개된 프로토콜 파라미터이므로 이 리터럴은 gitleaks의 대상이 아니다.
     */
    private static final String GOOGLE_ESSENTIAL_AUTH_TIME_CLAIMS =
            "{\"id_token\":{\"auth_time\":{\"essential\":true}}}";

    /**
     * 인증 설정. 값은 환경이 주고 이 record는 이름만 정한다.
     *
     * <p>{@code publicBaseUrl}은 브라우저가 보는 origin이다. redirect URI를 요청 헤더에서 유도하지
     * 않기 위해 절대 URI를 환경에서 받는다({@code application.yml}의 근거 참조).
     *
     * <p>제공자별 값이 하나씩이다. 세 제공자의 자격정보는 서로를 보지 않으며, 하나가 비어도 다른
     * 제공자의 등록과 로그인 경로는 그대로 살아 있다({@link ProviderClientRegistrations#from}).
     *
     * <p>엔드포인트 네 값은 제공자에 따라 의미가 다르다. Google은 비어 있는 것이 정상이며 그때
     * Spring Security의 {@code CommonOAuth2Provider.GOOGLE}이 정한 정본 주소를 쓴다. 네이버와 카카오는
     * {@code CommonOAuth2Provider}에 없으므로 이 클래스가 공식 discovery 문서의 정적 주소를 기본값으로
     * 들고 있고, 네 값은 그것을 덮는 재정의다. 어느 제공자에서도 기동 시점에 discovery 왕복을 하지
     * 않는다 — 값을 주는 것은 재정의가 필요한 환경(개발·CI의 local OIDC 스텁)뿐이다.
     */
    @ConfigurationProperties("bokrilog.auth")
    record AuthProperties(
            @Nullable String publicBaseUrl, Provider google, Provider naver, Provider kakao) {

        AuthProperties {
            /*
             * `bokrilog.auth.<제공자>`가 통째로 없는 구성에서도 배선이 성립해야 한다. 값이 없으면
             * 빈 값 여섯 개짜리 record로 바꾸고, 아래 `ProviderClientRegistrations`가 그 제공자만
             * "구성되지 않음"으로 접는다.
             *
             * 정규화가 여기 하나뿐인 것이 요점이다. 제공자가 셋으로 늘어도 그 성질은 유지된다 —
             * 그래서 `google()`·`naver()`·`kakao()`는 null을 돌려주지 않고, 소비하는 쪽에 null 검사가
             * 남지 않는다. 남으면 그 분기는 아무도 지나지 않는 죽은 코드가 된다.
             */
            if (google == null) {
                google = Provider.empty();
            }
            if (naver == null) {
                naver = Provider.empty();
            }
            if (kakao == null) {
                kakao = Provider.empty();
            }
        }

        /** 제공자 하나의 자격정보와 엔드포인트 재정의. 제공자 이름은 이 record에 없다. */
        record Provider(
                @Nullable String clientId,
                @Nullable String clientSecret,
                @Nullable String issuerUri,
                @Nullable String authorizationUri,
                @Nullable String tokenUri,
                @Nullable String jwkSetUri) {

            static Provider empty() {
                return new Provider(null, null, null, null, null, null);
            }
        }
    }

    /**
     * 이 애플리케이션이 아는 제공자 등록.
     *
     * <p>등록 id → {@link ClientRegistration} 맵이다. 단일 제공자를 필드로 들고 있던 모양을 맵으로
     * 넓힌 것이 Story 1.6의 핵심 변경이며, URL 표면은 이미 제공자 복수형이었다 —
     * {@code DefaultOAuth2AuthorizationRequestResolver}가 base URI 뒤에서 {@code {registrationId}}를
     * 뽑고 콜백은 {@code CALLBACK_BASE_URI + "/*"} 와일드카드다. 그래서 제공자를 늘리는 데 새 경로
     * 배선이 없다.
     *
     * <p>{@link ClientRegistrationRepository}를 직접 만드는 이유는 셋이다.
     *
     * <ul>
     *   <li>자격정보가 없는 구성에서 기동이 실패하지 않아야 한다. Boot의 property 매핑은
     *       {@code client-id}가 비면 {@code ClientRegistration.build()}에서 예외를 던지고, 그 예외는
     *       컨텍스트 생성 실패라 개발·CI 스택의 백엔드가 healthy가 되지 못한다. 여기서는 값이
     *       비면 그 제공자의 등록을 만들지 않고 "구성되지 않음" 상태로 남는다.
     *   <li>{@code issuer-uri}를 discovery 없이 실어야 한다. Boot의 {@code provider.<id>.issuer-uri}는
     *       기동 시점에 discovery 왕복을 하고, 그 왕복은 제공자에 대한 네트워크 의존을 기동 경로에
     *       만든다. Google은 {@code CommonOAuth2Provider.GOOGLE}이, 네이버·카카오는 위
     *       {@code NAVER_ISSUER_URI}·{@code KAKAO_ISSUER_URI} 이하 네 상수씩이 그 값을 정적으로
     *       들고 있어 {@code iss} 검증이 네트워크 없이 성립한다.
     *   <li>PKCE를 명시한다. Spring Security 7의 기본값이 이미 {@code requireProofKey = true}지만,
     *       기본값이 바뀌면 조용히 꺼지는 자리라 계약으로 적는다.
     * </ul>
     *
     * <p><b>fail-closed가 제공자 단위로 독립한다.</b> 판정을 한 번에 접으면 네이버 자격정보가 없는
     * 환경에서 Google 로그인까지 죽는다. 그래서 아래 {@link #from}은 제공자마다 따로 판정하고 맵에
     * 넣거나 넣지 않는다. {@code publicBaseUrl}만 모든 제공자가 공유하는 값이다 — 그것이 비면
     * redirect URI를 조립할 수 없으므로 어느 제공자도 만들어지지 않는 것이 맞다.
     */
    static final class ProviderClientRegistrations implements ClientRegistrationRepository {

        /**
         * 이 애플리케이션이 이름을 아는 제공자.
         *
         * <p>구성 여부와 무관한 닫힌 목록이다. {@code AuthSessionController}가 "모르는 제공자"(404)와
         * "아는 제공자인데 구성되지 않았다"(500)를 가르는 데 쓰며, 그 판정은 아래 맵으로 할 수 없다 —
         * 구성된 제공자의 인증 시작은 필터가 302로 끝내므로 컨트롤러에 도달하는 것은 언제나 맵에
         * 없는 이름이고, 맵을 술어로 쓰면 모든 요청이 404가 되어 fail-closed의 500이 사라진다.
         *
         * <p>집합이 아니라 <b>순서 있는 목록</b>이다. Story 1.10의 연결 상태 조회가 이 순서를 그대로
         * 응답에 싣는다 — 값이 실행마다 흔들리면 본문 전문을 고정하는 검증이 성립하지 않고, 화면이
         * 아는 고정 순서(Google → 네이버 → 카카오)와도 갈린다.
         */
        private static final List<String> SUPPORTED_REGISTRATION_IDS =
                rejectDuplicateRegistrationIds(
                        List.of(GOOGLE_REGISTRATION_ID, NAVER_REGISTRATION_ID, KAKAO_REGISTRATION_ID));

        /**
         * 같은 등록 id가 두 번 적힌 목록을 거부한다.
         *
         * <p>이 목록이 {@link Set}이던 동안에는 중복이 조용히 하나로 접혔다. 순서가 필요해
         * {@link List}로 바꾸면서 그 암묵적 guard가 사라졌으므로 명시적으로 둔다 — 중복이 남으면
         * 연결 상태 조회가 같은 제공자를 두 번 답하고, 그보다 나쁘게는 "아는 제공자 집합"이
         * 실제로 몇 개인지 읽는 사람이 세어야 한다.
         *
         * <p>{@code rejectSharedIssuer}와 같은 규율이다 — 잘못 적힌 구성은 조용히 동작하는 대신
         * 기동에서 멈춘다. 메시지에 이름을 적지 않는다.
         */
        static List<String> rejectDuplicateRegistrationIds(List<String> registrationIds) {
            if (Set.copyOf(registrationIds).size() != registrationIds.size()) {
                throw new IllegalStateException("제공자 등록 id가 중복된다. 이름은 서로 달라야 한다.");
            }
            return registrationIds;
        }

        private final Map<String, ClientRegistration> registrations;

        private ProviderClientRegistrations(Map<String, ClientRegistration> registrations) {
            this.registrations = Map.copyOf(registrations);
        }

        static ProviderClientRegistrations from(AuthProperties properties) {
            String baseUrl = normalizedBaseUrl(properties.publicBaseUrl());
            Map<String, ClientRegistration> registrations = new LinkedHashMap<>();
            put(registrations, GOOGLE_REGISTRATION_ID, googleBuilder(), properties.google(), baseUrl);
            put(registrations, NAVER_REGISTRATION_ID, naverBuilder(), properties.naver(), baseUrl);
            put(registrations, KAKAO_REGISTRATION_ID, kakaoBuilder(), properties.kakao(), baseUrl);
            rejectSharedIssuer(registrations);
            return new ProviderClientRegistrations(registrations);
        }

        /**
         * 서로 다른 두 등록이 같은 issuer를 가리키는 구성을 기동에서 거부한다.
         *
         * <p>계정 조회의 키가 {@code (issuer, subject)}이고 그 조합이 전역 유일이기 때문이다
         * ({@code AccountLifecycleApi#findUserIdBySocialIdentity}, {@code V1__identity_account.sql}).
         * 그래서 {@code bokrilog.auth.naver.issuer-uri}에 Google의 issuer가 잘못 들어오면 네이버로
         * 로그인한 사용자의 {@code sub}이 Google 쪽 매핑과 같은 namespace에서 조회되고, 그 매핑이
         * 있으면 <b>그 계정의 세션이 개설된다</b>. 두 제공자 식별자의 병합·전환은 이 스토리가 Never로
         * 금지한 것이며 그것이 구성 오류 하나로 열리는 경로가 여기다.
         *
         * <p>조회 키에 제공자를 더하는 방향을 택하지 않는다. 그것은 {@code identity} 공개 API의
         * 변경이고 이 스토리의 Ask First다. 이 검사는 잘못 구성된 프로세스가 아예 뜨지 못하게
         * 하는 것이라 어느 계약도 넓히지 않는다.
         *
         * <p>메시지에 자격정보도 {@code BOKRILOG_} 환경 변수 이름도 어느 제공자인지도 적지 않는다
         * ({@code DevOidcStubConfig}의 기동 guard와 같은 규율이다). 기동 실패 로그도 로그이며 답은
         * 어느 쪽이든 "제공자마다 다른 issuer를 구성한다" 하나다.
         */
        private static void rejectSharedIssuer(Map<String, ClientRegistration> registrations) {
            Set<String> seen = new HashSet<>();
            for (ClientRegistration registration : registrations.values()) {
                String issuer = registration.getProviderDetails().getIssuerUri();
                if (issuer == null) {
                    // 이 배선은 issuer 없는 등록을 만들지 않는다. 그래도 조용히 지나간다 —
                    // 비교할 값이 없는 것은 겹침이 아니다.
                    continue;
                }
                if (!seen.add(issuer)) {
                    // 문구가 제공자 수에 중립이다. "두 제공자"라고 적으면 셋째 등록이 충돌하는
                    // 날 이 메시지가 거짓말을 한다.
                    throw new IllegalStateException(
                            "서로 다른 제공자 등록이 같은 issuer를 가리킨다. "
                                    + "제공자마다 다른 issuer를 구성한다.");
                }
            }
        }

        /**
         * 제공자 하나를 판정해 맵에 넣는다.
         *
         * <p>거부는 그 제공자만 빠지는 것이다. 인증 시작은 {@code AuthSessionController}가 공통 오류
         * 계약으로 답하고, 다른 제공자의 등록은 아무 영향을 받지 않는다.
         */
        private static void put(
                Map<String, ClientRegistration> registrations,
                String registrationId,
                ClientRegistration.Builder builder,
                AuthProperties.Provider provider,
                @Nullable String baseUrl) {
            if (!hasText(provider.clientId())
                    || !hasText(provider.clientSecret())
                    || baseUrl == null) {
                // fail-closed.
                return;
            }

            builder
                    .clientId(provider.clientId())
                    .clientSecret(provider.clientSecret())
                    // 절대 URI다. 요청 Host에서 조립하지 않으므로 Nginx가 포트를 잃는 것과
                    // 무관하고, "사전 등록된 정확한 redirect URI"와 같은 모양이다.
                    .redirectUri(baseUrl + CALLBACK_BASE_URI + "/" + registrationId)
                    /*
                     * 기본 동의 범위는 openid뿐이다. `CommonOAuth2Provider.GOOGLE`이 넣는
                     * profile·email을 이 한 줄이 대체한다(`scope`는 더하지 않고 바꾼다). 네이버
                     * discovery는 `openid`·`profile`을 광고하지만 `profile`을 요청하지 않는다 —
                     * 필요한 값은 ID Token의 `iss`·`sub` 둘뿐이다.
                     */
                    .scope(OidcScopes.OPENID)
                    // UserInfo를 부르지 않는다. Spring Security 7의 `shouldRetrieveUserInfo`는
                    // scope가 아니라 이 URI의 존재로 판단하므로, 비우지 않으면 openid만
                    // 요청했는데도 제공자의 UserInfo 왕복이 일어난다.
                    .userInfoUri(null)
                    .clientSettings(
                            ClientRegistration.ClientSettings.builder()
                                    .requireProofKey(true)
                                    .build());

            // 엔드포인트 재정의. 값이 없으면 위 builder가 들고 온 제공자 정본 주소가 남는다.
            if (hasText(provider.issuerUri())) {
                builder.issuerUri(provider.issuerUri());
            }
            if (hasText(provider.authorizationUri())) {
                builder.authorizationUri(provider.authorizationUri());
            }
            if (hasText(provider.tokenUri())) {
                builder.tokenUri(provider.tokenUri());
            }
            if (hasText(provider.jwkSetUri())) {
                builder.jwkSetUri(provider.jwkSetUri());
            }
            registrations.put(registrationId, builder.build());
        }

        private static ClientRegistration.Builder googleBuilder() {
            return CommonOAuth2Provider.GOOGLE.getBuilder(GOOGLE_REGISTRATION_ID);
        }

        /**
         * 네이버 등록의 뼈대.
         *
         * <p>{@code CommonOAuth2Provider}에 NAVER가 없다(상수는 {@code GOOGLE}·{@code GITHUB}·
         * {@code FACEBOOK}·{@code OKTA} 넷뿐이다). 그래서 Google과 달리 엔드포인트 네 개와
         * {@code userNameAttributeName}·{@code authorizationGrantType}·
         * {@code clientAuthenticationMethod}가 모두 필수 입력이고, 이 메서드가 그 값을 든다.
         *
         * <p>{@code client_secret_post}인 것은 네이버가 그렇게 광고하기 때문이다. discovery의
         * {@code token_endpoint_auth_methods_supported}가 {@code [client_secret_post, none]}이라
         * Spring Security의 기본값({@code client_secret_basic})으로는 토큰 교환이 성립하지 않는다.
         */
        private static ClientRegistration.Builder naverBuilder() {
            return ClientRegistration.withRegistrationId(NAVER_REGISTRATION_ID)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .issuerUri(NAVER_ISSUER_URI)
                    .authorizationUri(NAVER_AUTHORIZATION_URI)
                    .tokenUri(NAVER_TOKEN_URI)
                    .jwkSetUri(NAVER_JWK_SET_URI)
                    // ID Token의 주체 claim. `CommonOAuth2Provider`가 없으므로 기본값도 없다.
                    .userNameAttributeName(IdTokenClaimNames.SUB);
        }

        /**
         * 카카오 등록의 뼈대.
         *
         * <p>{@link #naverBuilder()}와 같은 형태다. {@code CommonOAuth2Provider}에 KAKAO도 없으므로
         * 엔드포인트 네 개와 {@code userNameAttributeName}·{@code authorizationGrantType}·
         * {@code clientAuthenticationMethod}가 모두 필수 입력이다.
         *
         * <p>{@code client_secret_post}인 것은 카카오가 그것 <b>하나만</b> 광고하기 때문이다.
         * discovery의 {@code token_endpoint_auth_methods_supported}가 {@code [client_secret_post]}라
         * Spring Security의 기본값({@code client_secret_basic})으로는 토큰 교환이 성립하지 않는다.
         * 네이버는 {@code none}도 함께 광고하지만 카카오에는 그 선택지조차 없다.
         *
         * <p>Client Secret 필수 게이트를 무르지 않는다. 카카오 콘솔에서 Client Secret은 선택 기능이라
         * 운영자가 켜야 위 {@link #put}의 fail-closed를 통과한다. 게이트를 제공자마다 다르게 두면
         * "자격정보 셋이 다 있어야 등록이 생긴다"는 모양이 갈라지고, 그 차이가 구성 실수를 조용한
         * 성공으로 바꾼다.
         *
         * <p><b>카카오의 {@code sub}은 pairwise다.</b> discovery의 {@code subject_types_supported}가
         * {@code [pairwise]}이므로 그 값은 사용자만이 아니라 <b>client 등록에도</b> 묶인다. 조회 키가
         * {@code (issuer, subject)} 하나인 이 시스템에서 그것은 제공자별 단서가 된다 — 카카오 앱을 다시
         * 만들거나 REST API 키를 교체하면 기존 {@code (kauth.kakao.com, sub)} 매핑 전부가 고아가 되고,
         * 돌아온 사용자마다 <b>조용히 새 계정</b>이 생긴다. 오류도 경고도 없이 신규 가입 경로를 지나므로
         * 그 사건은 응답으로 관측되지 않는다. Google·네이버에는 없는 운영 제약이며, 키 교체는 계정
         * 마이그레이션을 동반해야 하는 작업이다({@code DEVELOPMENT.md}가 같은 사실을 적는다).
         */
        private static ClientRegistration.Builder kakaoBuilder() {
            return ClientRegistration.withRegistrationId(KAKAO_REGISTRATION_ID)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .issuerUri(KAKAO_ISSUER_URI)
                    .authorizationUri(KAKAO_AUTHORIZATION_URI)
                    .tokenUri(KAKAO_TOKEN_URI)
                    .jwkSetUri(KAKAO_JWK_SET_URI)
                    .userNameAttributeName(IdTokenClaimNames.SUB);
        }

        /**
         * 그 제공자의 재인증 인가 요청에 얹을 파라미터.
         *
         * <p><b>제공자마다 다른 것은 이것 하나다.</b> 검증도 판정도 오류도 셋이 공유하고, 차이는
         * "재인증을 어떻게 요청하는가"에만 있다. 그래서 이 맵이 제공자 차이가 사는 유일한 자리이며
         * 등록 조립 바로 옆에 있다.
         *
         * <p>공통은 {@code max_age}다. OIDC Core가 이 파라미터를 쓰면 OP는 필요 시 재인증해야 하고
         * ID Token은 {@code auth_time}을 <b>반드시</b> 실어야 한다고 정한다. 모르는 파라미터는
         * 무시되므로 공표하지 않은 제공자에게 보내도 흐름이 깨지지 않는다. 값은
         * {@link ReauthenticationFlow#MAX_AGE_SECONDS} 하나이며 콜백에서 재는 창과 같은 상수다.
         *
         * <p>그 위에 각 제공자가 <b>공표한</b> 수단만 더한다(2026-09-21 실측).
         *
         * <ul>
         *   <li>Google — 공식 OpenID Connect 문서가 {@code claims} 요청 파라미터로
         *       {@code {"id_token":{"auth_time":{"essential":true}}}}를 안내한다. 그 문서가
         *       공표하는 {@code prompt} 값은 {@code none}·{@code consent}·{@code select_account}
         *       뿐이라 {@code login}을 보내지 않는다 — 증거는 요청할 수 있고 강제 수단은 미공표다.
         *   <li>네이버 — 공식 답변(naveridlogin-API 이슈 #92)이 "ID Token의 claim 구성과 nonce를
         *       별도로 설명한 공식 문서는 현재 제공되고 있지 않습니다"이다. 공표된 증거도 요청
         *       파라미터도 없으므로 공통 {@code max_age}만 보내고, 증거가 없으면 fail-closed다.
         *   <li>카카오 — 공식 REST 문서가 {@code prompt=login}("이미 인증되어 있어도 재인증을
         *       요청할 때")과 ID Token claim {@code auth_time}을 함께 공표한다. {@code max_age}는
         *       공표하지 않지만 모르는 파라미터는 무시된다.
         * </ul>
         *
         * <p>이 파라미터를 상시로 얹지 않는다. 얹으면 일반 로그인이 매번 재인증을 요구하고,
         * 카카오의 {@code prompt=login}은 이미 로그인한 사용자에게 매번 자격정보를 다시 묻는다.
         * 얹는 판정은 무장된 흐름 하나이며 {@code authorizationRequestResolver}가 소유한다.
         *
         * @return 얹을 파라미터, 아는 제공자가 아니면 빈 맵
         */
        static Map<String, Object> reauthenticationParameters(String registrationId) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("max_age", Long.toString(ReauthenticationFlow.MAX_AGE_SECONDS));
            if (GOOGLE_REGISTRATION_ID.equals(registrationId)) {
                parameters.put("claims", GOOGLE_ESSENTIAL_AUTH_TIME_CLAIMS);
            } else if (KAKAO_REGISTRATION_ID.equals(registrationId)) {
                parameters.put("prompt", "login");
            } else if (!NAVER_REGISTRATION_ID.equals(registrationId)) {
                /*
                 * 모르는 제공자다. 여기 오지 않는다 — 무장은 아는 이름에만 붙고
                 * (`AuthSessionController`) 등록이 없으면 인가 시작이 resolver에 닿지 않는다.
                 * 그래도 공통 파라미터를 얹지 않는다: 이름을 모르는 제공자에게 보낼 값이 없다.
                 */
                return Map.of();
            }
            return parameters;
        }

        @Override
        public @Nullable ClientRegistration findByRegistrationId(String registrationId) {
            return this.registrations.get(registrationId);
        }

        /**
         * 쓸 수 있는 제공자 등록이 하나라도 있는가.
         *
         * <p>없으면 인증 필터를 배선하지 않는다. 필터를 두고 등록만 비우면 인증 시작이 필터 안의
         * 예외로 끝나고, 그 예외는 스택과 함께 로그에 실린다. 그것은 "내부 경로·스택을 로그에도
         * 남기지 않는다"와 정면으로 부딪힌다.
         *
         * <p>"하나 이상"이다. 제공자 하나만 구성된 환경에서 그 하나로 로그인할 수 있어야 하고,
         * 전부를 요구하면 개발 환경의 부분 구성이 인증 표면을 통째로 죽인다.
         */
        boolean isConfigured() {
            return !this.registrations.isEmpty();
        }

        /**
         * 그 제공자의 등록이 <b>실제로 만들어졌는가</b>.
         *
         * <p>{@link #isSupportedProvider}와 다른 질문이다. 그쪽은 이름을 아는가이고 이것은 자격정보가
         * 갖춰졌는가이며, 둘이 갈리는 구간(아는 이름인데 구성되지 않음)이 연결 시작에서 문제가 된다 —
         * 그 요청을 통과시키면 허가와 무장이 만들어진 뒤 뒤따르는 인가 시작이 fail-closed로 끝나고,
         * 참조할 수 없게 된 허가가 그대로 5분을 산다. Story 1.9의 "등록 구성 여부는 조회하지 않는다"는
         * <b>재인증 시작</b>의 계약이었고 그 경로는 남는 상태가 없었다.
         *
         * <p>열거 위험이 다르다는 것이 이 조회를 여는 근거다. 연결 시작은 인증된 소유자에게만 답하는
         * 경로이고 거부는 다른 모든 거부와 구분되지 않는 403 하나이므로, 이 판정이 제공자 목록을
         * 밖으로 흘리지 않는다.
         */
        boolean isConfiguredProvider(String registrationId) {
            return this.registrations.containsKey(registrationId);
        }

        /**
         * 이 애플리케이션이 이름을 아는 제공자인가.
         *
         * <p>구성 여부를 보지 않는다. 이름조차 모르는 제공자는 그 경로가 존재하지 않는 것이고(404),
         * 아는 이름인데 구성되지 않은 것은 요청을 처리할 수 없는 것이다(500). 두 답이 갈리는 자리는
         * {@code AuthSessionController} 하나이며 판정은 여기 있다.
         */
        static boolean isSupportedProvider(String registrationId) {
            return SUPPORTED_REGISTRATION_IDS.contains(registrationId);
        }

        /**
         * 이름을 아는 제공자 전부. 고정 순서다.
         *
         * <p>연결 상태 조회가 이 목록을 걸러 응답을 만든다. 구성 여부를 보지 않는 것은
         * {@link #isSupportedProvider}와 같은 이유이며, 그래서 이 목록이 응답에 <b>그대로</b>
         * 실리지 않는다 — 실리는 것은 이 목록과 연결 사실의 교집합이고, 어느 제공자가 구성됐는지는
         * 그 응답에서 복원되지 않는다.
         */
        static List<String> supportedProviders() {
            return SUPPORTED_REGISTRATION_IDS;
        }

        /**
         * origin 하나만 담은 절대 base URL로 정규화한다.
         *
         * <p>package-private이다. {@code DevOidcStubConfig}가 스텁 엔드포인트 URI를 같은 base에서
         * 조립하므로 정규화 규칙이 두 곳에 있으면 한쪽만 고쳐지는 날이 온다.
         *
         * <p>스킴 접두사만 보지 않는다. 이 값은 두 곳에서 쓰이는데, redirect URI 조립에서는 경로가
         * 붙은 값이 등록값과 다른 URI를 만들고, {@code SameOriginFilter}의 {@code Origin} 비교에서는
         * 브라우저가 보내는 origin(스킴 + 호스트 + 포트)과 영구히 어긋나 모든 상태 변경 요청이
         * 403이 된다. 두 번째가 조용하다 — 아무 오류도 나지 않고 그냥 전부 거절된다. 그래서
         * {@link URI}로 파싱해 host가 없거나 경로·쿼리·프래그먼트·userinfo가 붙은 값을 거부한다.
         *
         * @return origin만 담긴 절대 URL, 아니면 {@code null}
         */
        static @Nullable String normalizedBaseUrl(@Nullable String value) {
            if (!hasText(value)) {
                return null;
            }
            String trimmed = value.trim();
            // 뒤에 경로를 붙여 쓰므로 끝의 `/`를 남기지 않는다. `//api/...`는 등록값과 다른 URI다.
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            URI uri;
            try {
                uri = new URI(trimmed);
            } catch (URISyntaxException ex) {
                // URI로 읽히지 않는 값은 origin일 수 없다. 예외를 밖으로 내보내지 않는다 —
                // 구성이 잘못된 스택은 기동 실패가 아니라 fail-closed로 남는 편이 계약에 맞다.
                return null;
            }
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                // 상대 경로·스킴 없는 값·`javascript:` 같은 opaque URI가 여기서 걸린다.
                return null;
            }
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                // host가 없으면 비교할 origin이 없고, userinfo가 있으면 origin이 아니다.
                return null;
            }
            String path = uri.getRawPath();
            if ((path != null && !path.isEmpty()) || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            return trimmed;
        }

        private static boolean hasText(@Nullable String value) {
            return value != null && !value.isBlank();
        }
    }

    @Bean
    ProviderClientRegistrations providerClientRegistrations(AuthProperties properties) {
        return ProviderClientRegistrations.from(properties);
    }

    /**
     * 인증 시작 요청을 authorization request로 옮긴다.
     *
     * <p>기본 resolver는 모르는 등록 id에 {@code IllegalArgumentException}을 던지고, 그 예외를 받은
     * 필터가 스택과 함께 로그를 남긴 뒤 500을 보낸다. 여기서 그 경우를 빈 값으로 접어 요청이
     * 필터를 그대로 지나가게 하고, {@code AuthSessionController}가 공통 오류 계약으로 답한다.
     * 얻는 것은 두 가지다 — 로그에 스택이 남지 않고, 모르는 제공자와 구성되지 않은 제공자가 같은
     * 한 곳에서 같은 모양으로 답한다.
     *
     * <p>두 번째 일은 재인증 파라미터를 얹는 것이다. 무장된 흐름이 있고 그 흐름의 제공자가 지금
     * 시작하는 제공자와 같을 때만 얹는다 — 상시로 얹으면 일반 로그인이 매번 재인증을 요구하고,
     * 제공자를 보지 않고 얹으면 google로 무장한 세션의 kakao 로그인에 카카오의
     * {@code prompt=login}이 실린다.
     *
     * <p>{@code Consumer} 오버로드를 쓴다. {@code Map} 오버로드도 {@code putAll}이지만, 소비자
     * 형태가 "기존 값을 덮지 않고 더한다"를 호출 자리에서 말한다. 덮으면 같은 맵에 사는
     * {@code nonce}와 PKCE {@code code_challenge}가 사라지고 검증이 통째로 무너진다.
     */
    @Bean
    OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ProviderClientRegistrations registrations) {
        DefaultOAuth2AuthorizationRequestResolver delegate =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION_BASE_URI);
        return new OAuth2AuthorizationRequestResolver() {

            @Override
            public @Nullable OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                try {
                    return withReauthenticationParameters(
                            request,
                            delegate.resolve(request),
                            authorizationStartProvider(request));
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }

            @Override
            public @Nullable OAuth2AuthorizationRequest resolve(
                    HttpServletRequest request, String clientRegistrationId) {
                try {
                    return withReauthenticationParameters(
                            request,
                            delegate.resolve(request, clientRegistrationId),
                            clientRegistrationId);
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }
        };
    }

    /**
     * 무장된 재인증 흐름이 있으면 그 제공자의 파라미터를 얹는다.
     *
     * <p>흐름이 없거나 제공자가 다르면 인가 요청을 그대로 돌려준다. 그것이 일반 로그인이다.
     */
    private static @Nullable OAuth2AuthorizationRequest withReauthenticationParameters(
            HttpServletRequest request,
            @Nullable OAuth2AuthorizationRequest resolved,
            @Nullable String registrationId) {
        if (resolved == null || registrationId == null) {
            return resolved;
        }
        ReauthenticationFlow.Armed armed = ReauthenticationFlow.peek(request);
        if (armed == null || !armed.provider().equals(registrationId)) {
            return resolved;
        }
        Map<String, Object> extra =
                ProviderClientRegistrations.reauthenticationParameters(registrationId);
        if (extra.isEmpty()) {
            return resolved;
        }
        return OAuth2AuthorizationRequest.from(resolved)
                .additionalParameters(parameters -> parameters.putAll(extra))
                .build();
    }

    /**
     * 인증 시작 요청이 가리키는 제공자 등록 id.
     *
     * <p>package-private이다. 소비자가 둘이고 둘 다 같은 비교를 한다 — 이 클래스의 resolver가
     * "무장된 흐름의 제공자인가"를 보고 재인증 파라미터를 얹을지 정하고,
     * {@code AuthFlowHandlers}의 인증 흐름 경계가 같은 비교로 인증된 세션의 시작을 통과시킬지
     * 정한다. 판정이 두 곳에 있으면 한쪽만 고쳐지는 날이 오고, 그때 한쪽은 통과시키고 다른
     * 쪽은 파라미터를 얹지 않는 상태가 된다. {@link #isLogoutRequest}·
     * {@link #isReauthenticationStart}와 같은 이유의 같은 배치다.
     *
     * <p>요청 URI의 마지막 세그먼트를 읽는다. 프레임워크가 인가 요청 attribute에 같은 값을
     * 담지만 그 이름은 우리 계약이 아니라 라이브러리의 것이라, 바뀌면 조용히 {@code null}이
     * 되고 재인증 파라미터가 아무 오류 없이 사라진다. 경로는 이 클래스가 소유한 값이다.
     *
     * @return 제공자 등록 id, 인증 시작 경로가 아니거나 세그먼트가 갈리면 {@code null}
     */
    static @Nullable String authorizationStartProvider(HttpServletRequest request) {
        return lastSegment(request, AUTHORIZATION_BASE_URI);
    }

    /**
     * 콜백이 가리키는 제공자 등록 id.
     *
     * <p>{@link #authorizationStartProvider}와 같은 근거의 같은 추출이다. 소비자는
     * {@code AuthFlowHandlers}의 흐름 경계와 실패 핸들러이며, 둘 다 "이 콜백이 어느 무장의
     * 것인가"를 이 값으로 가른다 — 보지 않으면 재인증 콜백이 살아 있는 연결 무장에 삼켜진다.
     *
     * <p>성공 핸들러는 이 값을 쓰지 않는다. 그쪽에는 검증을 통과한 인증 결과가 있고, 흐름을
     * 실제로 처리한 등록 id는 경로가 아니라 그 결과가 들고 있다
     * ({@code OAuth2AuthenticationToken#getAuthorizedClientRegistrationId()}).
     *
     * @return 제공자 등록 id, 콜백 경로가 아니거나 세그먼트가 갈리면 {@code null}
     */
    static @Nullable String callbackProvider(HttpServletRequest request) {
        return lastSegment(request, CALLBACK_BASE_URI);
    }

    /**
     * 그 접두사 뒤의 세그먼트 하나.
     *
     * <p>프레임워크가 같은 값을 attribute에 담지만 그 이름은 우리 계약이 아니라 라이브러리의
     * 것이라, 바뀌면 조용히 {@code null}이 된다. 경로는 이 클래스가 소유한 값이다.
     */
    private static @Nullable String lastSegment(HttpServletRequest request, String baseUri) {
        String prefix = baseUri + "/";
        String uri = request.getRequestURI();
        if (!uri.startsWith(prefix)) {
            return null;
        }
        String segment = uri.substring(prefix.length());
        return segment.isEmpty() || segment.indexOf('/') >= 0 ? null : segment;
    }

    /**
     * 제공자 액세스·리프레시 토큰을 버린다.
     *
     * <p>{@code oauth2Login}은 성공한 흐름의 {@code OAuth2AuthorizedClient}를 저장소에 넣으려 한다.
     * 기본 구현은 그것을 세션에 넣고, 세션은 Spring Session JDBC를 통해 PostgreSQL로 간다. 그러면
     * 저장하지 않기로 한 액세스·리프레시 토큰이 데이터베이스에 남는다. 이 구현은 아무것도 쓰지
     * 않고 아무것도 돌려주지 않아 토큰이 검증 직후 사라진다.
     *
     * <p>지금 이 애플리케이션에는 제공자 API를 호출하는 경로가 없다. 토큰을 보관할 이유가 생기면
     * 그 스토리의 SPEC이 목적·필드·암호화·만료·폐기 시점을 명시한다.
     */
    @Bean
    OAuth2AuthorizedClientRepository discardingAuthorizedClientRepository() {
        return new OAuth2AuthorizedClientRepository() {

            @Override
            public <T extends OAuth2AuthorizedClient> @Nullable T loadAuthorizedClient(
                    String clientRegistrationId, Authentication principal, HttpServletRequest request) {
                return null;
            }

            @Override
            public void saveAuthorizedClient(
                    OAuth2AuthorizedClient authorizedClient,
                    Authentication principal,
                    HttpServletRequest request,
                    HttpServletResponse response) {
                // 버린다. 이 호출이 곧 "토큰을 저장하지 않는다"가 구현으로 성립하는 지점이다.
            }

            @Override
            public void removeAuthorizedClient(
                    String clientRegistrationId,
                    Authentication principal,
                    HttpServletRequest request,
                    HttpServletResponse response) {
                // 저장한 것이 없으므로 지울 것도 없다.
            }
        };
    }

    @Bean
    SecurityFilterChain authSecurityFilterChain(
            HttpSecurity http,
            ProviderClientRegistrations registrations,
            AuthProperties properties,
            AuthFlowHandlers handlers,
            OperatorAccess operators,
            OperatorCommandService operatorCommands,
            DeletionCompletionSessions completions)
            throws Exception {

        http.authorizeHttpRequests(
                        requests ->
                                /*
                                 * 인증으로 막는 경로가 하나 생겼다(Story 2.1).
                                 *
                                 * Story 1.4가 "보호는 서버가 소유한 자원이 생기는 스토리가 그 자원과
                                 * 함께 더한다"를 여기 적었고, 금융 source 연결이 그 첫 자원이다.
                                 * 막는 범위는 `/api/connections/` 접두사 하나이며
                                 * (`requiresAuthentication`) 나머지는 그대로 `permitAll`이다.
                                 *
                                 * 전역으로 켜지 않는 이유는 그대로다. `anyRequest().authenticated()`를
                                 * 두면 Story 1.2가 고정한 오류 계약이 뒤집힌다 — `/api/nope`의 404와
                                 * `POST /api/status`의 405가 전부 401·302가 되고, 그 회귀는
                                 * `StatusApiTest`와 `verify-same-origin.sh`의 탐침 열한 개에서
                                 * 동시에 드러난다. 진입 화면과 인증 표면도 미인증 요청을 받아야
                                 * 하므로 `permitAll`이 그 경로들의 계약이다.
                                 *
                                 * 핸들러의 주체 검사를 지우지 않는다. 인가 규칙이 실수로 넓어지거나
                                 * 좁아지는 날 그 검사가 마지막 말을 한다(이중 방어).
                                 */
                                requests
                                        /*
                                         * 완료 확인 전용 세션(Story 5.4)은 인증 주체가 없는 세션이다. 그 세션이 닿는 경로는 둘뿐이다 —
                                         * 완료 조회(`GET /api/account/deletion`)와 확정 재전송의 거부(`POST …/confirm`). 나머지
                                         * 보호 경로는 아래 규칙대로 미인증과 같다. 판정은 요청의 세션 ID 하나로 identity에 묻는다.
                                         */
                                        .requestMatchers(AuthSecurityConfig::isDeletionCompletionRequest)
                                        .access((authentication, context) -> new AuthorizationDecision(
                                                isAuthenticatedUser(authentication.get())
                                                        || completions.requestIdOf(context.getRequest()).isPresent()))
                                        .requestMatchers(AuthSecurityConfig::requiresAuthentication)
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                /*
                 * 보안 응답 헤더는 Nginx가 유일하게 정의한다. 여기서 켜 두면 같은 헤더가 두 번
                 * 나가고, `Cache-Control`도 Story 1.2가 관측한 응답에 새로 붙는다.
                 */
                .headers(headers -> headers.disable())
                /*
                 * 로그아웃은 `POST /api/auth/logout` 하나다.
                 *
                 * 매처로 묶지 않고 그냥 켜면 기본 배선이 `/logout` HTTP 표면을 하나 새로 만든다.
                 * 그 경로는 `/api/auth/` 밖이라 CSRF·Origin 보호도 받지 않고, Nginx의 정적
                 * location이 받아 405를 돌려주므로 계약이 두 벌이 된다.
                 *
                 * 리다이렉트가 아니라 204다. 목적지는 서버가 정하지 않는다 — 로그아웃 뒤 화면은
                 * 클라이언트가 표지와 함께 여는 진입 화면이고, 여기서 302를 돌려주면 `fetch`가
                 * 그것을 따라가 본문 없는 HTML을 받는다.
                 *
                 * 세션 폐기 본체는 `AuthFlowHandlers`가 소유한다 — "행 삭제·컨텍스트 정리·쿠키
                 * 만료" 셋을 함께 만족시키는 자리는 그 메서드 하나다. 다만 기본
                 * `SecurityContextLogoutHandler`를 피한 것은 아니다: `LogoutConfigurer`는 등록된
                 * 사용자 핸들러 뒤에 그 핸들러를 **항상** 덧붙이므로 매 로그아웃마다 함께 실행된다.
                 * 우리 핸들러가 이미 세션을 무효화한 뒤이므로 그때 `getSession(false)`는 null이고,
                 * 남는 일은 이미 비어 있는 `SecurityContext`를 한 번 더 정리하는 것뿐이다. 순서가
                 * 뒤집히면(그 핸들러가 먼저 돌면) 우리 핸들러가 볼 세션이 사라져 행이 남으므로,
                 * 이 배선에서 중요한 것은 "피했다"가 아니라 "앞에 있다"다.
                 */
                .logout(
                        logout ->
                                logout
                                        .logoutRequestMatcher(logoutRequests())
                                        .addLogoutHandler(handlers.logoutHandler())
                                        .logoutSuccessHandler(
                                                (request, response, authentication) ->
                                                        response.setStatus(HttpStatus.NO_CONTENT.value())))
                /*
                 * 저장된 요청으로 되돌리지 않는다. 복귀 목적지는 서버가 가진 닫힌 허용 목록이며
                 * (`AuthFlowHandlers`), 요청 캐시는 그 목록을 거치지 않는 두 번째 경로가 된다.
                 */
                .requestCache(cache -> cache.disable())
                /*
                 * 로그인 성공 시 세션 ID를 회전한다. Spring Session의 요청 wrapper가
                 * `changeSessionId()`를 지원하므로 회전이 PostgreSQL의 세션 행에도 반영된다.
                 */
                .sessionManagement(
                        session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                /*
                 * CSRF 토큰은 세션에 귀속한다. 쿠키 저장소를 쓰지 않는 것은 "브라우저에는 세션
                 * 쿠키 하나뿐"이라는 계약 때문이다. 클라이언트가 토큰을 얻는 경로는
                 * `GET /api/auth/session`의 응답 본문 하나이며(`AuthSessionController`), 그래서
                 * 토큰이 쿠키·`localStorage`·`sessionStorage`·URL에 놓일 자리가 없다.
                 *
                 * 보호 대상을 `/api/auth/` 아래의 상태 변경 요청으로 좁힌다. 전역으로 두면
                 * CsrfFilter가 서블릿보다 먼저 답해 `POST /api/status`가 405 대신 403이 되고
                 * Story 1.2·1.3이 고정한 탐침 응답이 함께 뒤집힌다.
                 */
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(new SessionBoundCsrfTokenRepository())
                                        .requireCsrfProtectionMatcher(protectedRequests()))
                /*
                 * 401·403을 공통 오류 계약으로 답한다. 기본 배선은 익명 요청의 접근 거부를
                 * 로그인 페이지 리다이렉트로 바꾸는데, 그러면 API 요청이 302 + HTML로 끝난다.
                 * `sendError`가 컨테이너의 ERROR 디스패치를 지나 `ApiErrorHandler`의 본문을 만든다.
                 */
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        response.sendError(HttpStatus.UNAUTHORIZED.value()))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        response.sendError(HttpStatus.FORBIDDEN.value())))
                // 동일 출처 `Origin` 검증. CSRF 토큰 검사보다 앞에 둔다.
                .addFilterBefore(
                        new SameOriginFilter(
                                ProviderClientRegistrations.normalizedBaseUrl(
                                        properties.publicBaseUrl())),
                        CsrfFilter.class)
                /*
                 * 인증 흐름 경계. 이미 인증된 세션의 로그인 시작·콜백 거절, 무장된 재인증
                 * 시작의 통과, 복귀 목적지 기록을 함께 한다.
                 *
                 * authorization request를 만드는 필터와 콜백을 처리하는 로그인 필터보다 앞이어야
                 * 한다. 뒤에 두면 거절 판정이 이미 검증이 끝난 흐름을 보게 되고, 그때는 세션에
                 * 새 주체가 이미 저장된 뒤다.
                 */
                .addFilterBefore(handlers.authenticationFlowFilter(), CsrfFilter.class)
                /*
                 * 세션 수명·의심 세션 판정.
                 *
                 * `SecurityContextHolderFilter` **뒤**여야 한다. 그 필터가 세션에 저장된
                 * `SecurityContext`를 thread-local에 얹으므로, 앞에 두면 여기서 비운 컨텍스트가
                 * 곧바로 덮어써진다. 뒤에 두면 이 필터가 마지막 말을 하고, 그 정리는 thread-local
                 * 에만 닿아 세션 행에 저장되지 않는다(재인증 요구가 행을 남긴다는 계약).
                 *
                 * `CsrfFilter`·`LogoutFilter`보다는 앞이다. 수명이 끝난 세션이 상태 변경 요청을
                 * 통과하는 창을 남기지 않는다.
                 */
                .addFilterAfter(handlers.sessionGuardFilter(), SecurityContextHolderFilter.class)
                /*
                 * 운영자 게이트(Story 3.1). 인가 필터 **뒤**다 — 미인증은 그 앞에서 401로 끝나고, 여기 오는 요청은
                 * 인증됐다. 빈으로 두지 않는다: 빈이면 서블릿 컨테이너에도 한 번 더 등록되어 체인 밖에서 돈다.
                 */
                .addFilterAfter(new OperatorGate(operators, operatorCommands), AuthorizationFilter.class);

        if (registrations.isConfigured()) {
            http.oauth2Login(
                    login ->
                            login
                                    /*
                                     * 생성되는 로그인 페이지를 쓰지 않는다. 인증 화면은 정적 SPA가
                                     * origin 루트에서 제공하며, 여기에 값을 주지 않으면 Spring이
                                     * `/login` HTML 표면을 하나 만든다.
                                     */
                                    .loginPage("/")
                                    .redirectionEndpoint(
                                            redirection -> redirection.baseUri(CALLBACK_BASE_URI + "/*"))
                                    .successHandler(handlers.successHandler())
                                    .failureHandler(handlers.failureHandler()));
            /*
             * authorization endpoint의 base URI는 위 `authorizationRequestResolver` 빈이 소유한다.
             * `authorizationEndpoint().baseUri(...)`는 resolver 빈이 있을 때 무시되므로, 두 곳에
             * 적어 한쪽만 바뀌는 자리를 만들지 않는다.
             */
        }

        return http.build();
    }

    /** CSRF 토큰과 {@code Origin}을 요구하는 요청. {@code /api/auth/} 아래의 상태 변경뿐이다. */
    private static RequestMatcher protectedRequests() {
        return AuthSecurityConfig::isProtectedRequest;
    }

    /**
     * 로그아웃으로 인정하는 요청. {@code POST /api/auth/logout} 정확히 하나다.
     *
     * <p>람다로 쓴다. 경로 매처 구현을 인용하면 그 타입의 폐기 예고가 곧 {@code -Werror} 아래의
     * 빌드 실패가 되고, 여기서 하는 판정은 위 {@link #isProtectedRequest}와 같은 모양의 문자열
     * 비교 하나다. 접두사 비교가 아니라 동등 비교여서 {@code /api/auth/logout/x}는 받지 않는다.
     */
    private static RequestMatcher logoutRequests() {
        return AuthSecurityConfig::isLogoutRequest;
    }

    /**
     * 로그아웃 요청인가.
     *
     * <p>package-private이다. {@code AuthFlowHandlers}의 세션 guard가 재인증을 요구받은 세션의
     * 상태 변경을 막으면서 <b>로그아웃만은 통과시켜야</b> 하고(로그아웃은 상태를 없애기만 하므로
     * 표시된 세션도 스스로를 끝낼 수 있어야 한다), 그 판정이 두 곳에 있으면 한쪽만 고쳐지는 날이
     * 온다. {@link #isProtectedRequest}와 같은 이유의 같은 배치다.
     */
    static boolean isLogoutRequest(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && LOGOUT_PATH.equals(request.getRequestURI());
    }

    /**
     * 재인증 시작 요청인가.
     *
     * <p>package-private이고 근거는 {@link #isLogoutRequest}와 같다. {@code AuthFlowHandlers}의
     * 세션 guard가 두 가지를 이 판정 하나로 한다 — 표시된 세션의 상태 변경 403에서 이 요청을
     * 면제하고, 이 요청에서 thread-local 인증을 지우지 않는다. 판정이 두 곳에 있으면 한쪽만
     * 고쳐지는 날이 온다.
     *
     * <p>접두사 비교다. 뒤에 제공자 등록 id가 붙으므로 동등 비교가 될 수 없다. 두 세그먼트짜리
     * 경로({@code /api/auth/reauthenticate/google/x})는 컨트롤러 매핑이 받지 않아 404로 끝나며,
     * 그 요청도 이 판정에는 걸려 CSRF·{@code Origin} 보호를 그대로 지난다.
     */
    static boolean isReauthenticationStart(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && request.getRequestURI().startsWith(REAUTHENTICATION_BASE_URI + "/");
    }

    /**
     * 세션 귀속 CSRF 토큰과 동일 출처 {@code Origin}을 요구하는 요청인가.
     *
     * <p>package-private이다. {@code AuthFlowHandlers}의 세션 guard가 재인증을 요구받은 세션의
     * 상태 변경을 같은 경계에서 막으므로, 이 판정이 두 곳에 있으면 한쪽만 고쳐지는 날이 온다.
     */
    static boolean isProtectedRequest(HttpServletRequest request) {
        // 컨텍스트 경로가 비어 있는 배치다(`server.servlet.context-path`를 두지 않는다).
        // 그래서 요청 URI가 그대로 애플리케이션 경로다.
        if (SAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        String uri = applicationPath(request);
        for (String prefix : PROTECTED_PATH_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        /*
         * 연결 표면도 같은 보호를 받는다. 접두사 목록에 문자열로 더하지 않고 아래 판정 하나를
         * 부르는 이유는 비대칭을 만들지 않기 위해서다 — `requiresAuthentication`은 맨
         * `/api/connections`도 포함하는데 접두사 문자열은 `/api/connections/`만 덮는다. 두 판정이
         * 갈린 상태 자체가 이 함수가 하나로 유지되는 이유와 부딪힌다.
         */
        return isConnectionsPath(uri) || isSnapshotPath(uri) || isPlanConditionsPath(uri)
                || isDashboardPath(uri) || isOperatorPath(uri) || isNoticesPath(uri) || isSupportPath(uri)
                || isEngagementPath(uri) || isAccountPath(uri);
    }

    /**
     * 완료 확인 전용 세션(Story 5.4)이 닿을 수 있는 계정 삭제 요청인가 — 완료 조회({@code GET /api/account/deletion})와 확정
     * ({@code POST /api/account/deletion/confirm}, 완료 뒤 재전송은 거부 code 하나로 답한다) 둘이다. 인증된 세션에는 아무 차이도 없다 —
     * 이 둘도 {@link #requiresAuthentication}에 들고, 완료 세션이 아니면 같은 401이다.
     */
    static boolean isDeletionCompletionRequest(HttpServletRequest request) {
        String uri = applicationPath(request);
        return "GET".equals(request.getMethod()) && uri.equals(AccountDeletionController.DELETION_PATH)
                || "POST".equals(request.getMethod()) && uri.equals(AccountDeletionController.CONFIRM_PATH);
    }

    /** 익명이 아닌 인증 주체인가({@code authenticated()}와 같은 판정). */
    private static boolean isAuthenticatedUser(@Nullable Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /**
     * 인증을 요구하는 요청인가. <b>이 판정도 하나뿐이다.</b>
     *
     * <p>Story 2.1이 처음 연다. 대상은 금융 source 연결 표면 하나이며 나머지 경로는 그대로
     * {@code permitAll}이다 — 전역으로 켜면 Story 1.2가 고정한 404·405가 401·302로 뒤집힌다.
     *
     * <p>접두사 비교에 {@code /}를 붙인다. {@code startsWith("/api/connections")}만 쓰면
     * {@code /api/connectionsfoo}처럼 우리 것이 아닌 경로가 함께 걸리고, 그 요청의 404가 401로
     * 바뀐다. 접두사 없는 정확한 {@code /api/connections}도 함께 든다 — 그 경로에 핸들러가 없어
     * 404지만, 인증 없이 404를 돌려주면 이 접두사의 존재가 밖에서 열거된다.
     */
    static boolean requiresAuthentication(HttpServletRequest request) {
        String uri = applicationPath(request);
        return isConnectionsPath(uri) || isSnapshotPath(uri) || isPlanConditionsPath(uri) || isDashboardPath(uri)
                || isOperatorPath(uri) || isNoticesPath(uri) || isSupportPath(uri) || isEngagementPath(uri)
                || isAccountPath(uri);
    }

    /**
     * 보호 술어가 보는 경로 — 디코딩·정규화한 애플리케이션 내 경로 하나(Story 3.1 리뷰).
     *
     * <p>원시 {@code getRequestURI()}를 보면 안 된다. 방화벽은 {@code %6F} 같은 비예약 문자 인코딩을 통과시키고 Nginx는
     * 원시 URI를 그대로 넘기는데, MVC는 디코딩한 경로로 핸들러를 고른다 — 그러면 {@code /api/%6Fperator/audit}가
     * 인증·운영자 게이트 없이 감사 원장에 닿고, 다른 접두사의 상태 변경이 CSRF·{@code Origin} 판정을 비켜 간다. 그래서
     * 인가·CSRF·게이트가 모두 MVC와 같은 디코딩 경로를 본다. 세미콜론 매개변수를 떼고 연속 {@code /}를 접는다.
     */
    static String applicationPath(HttpServletRequest request) {
        return PATHS.getPathWithinApplication(request);
    }

    /**
     * 금융 source 연결 표면의 경로인가.
     *
     * <p>인가 판정({@link #requiresAuthentication})과 CSRF·{@code Origin} 판정
     * ({@link #isProtectedRequest})이 <b>같은 함수</b>를 본다. 한쪽이 접두사 문자열을 따로 들면 그
     * 둘이 덮는 집합이 갈리고, 그때 어느 경로는 인증을 요구받으면서 CSRF는 지나지 않는다.
     */
    private static boolean isSnapshotPath(String uri) {
        return uri.equals(FIRST_SNAPSHOT_PATH) || uri.startsWith(FIRST_SNAPSHOT_PATH + "/");
    }

    private static boolean isPlanConditionsPath(String uri) {
        return uri.equals(PLAN_CONDITIONS_PATH) || uri.startsWith(PLAN_CONDITIONS_PATH + "/")
                || uri.equals("/api/plans/next/baseline");
    }

    /**
     * The dashboard and every sub-path — the evidence (Story 2.9), the adjustment history (Story 2.10), the sync
     * request (Story 2.11), the verification request (Story 2.12) and the plan end's three (Story 2.13) too.
     */
    private static boolean isDashboardPath(String uri) {
        return uri.equals(DASHBOARD_PATH) || uri.startsWith(DASHBOARD_PATH + "/");
    }

    /**
     * 보호된 운영자 표면과 그 모든 하위 경로(Story 3.1). 인가·CSRF·{@code Origin}·운영자 게이트가 이 함수 하나를 본다.
     */
    static boolean isOperatorPath(String uri) {
        return uri.equals(OPERATOR_API_PATH) || uri.startsWith(OPERATOR_API_PATH + "/");
    }

    /** 서비스 공지와 그 모든 하위 경로(Story 3.5). 인가·CSRF·{@code Origin}이 이 함수 하나를 본다. */
    private static boolean isNoticesPath(String uri) {
        return uri.equals(NOTICES_PATH) || uri.startsWith(NOTICES_PATH + "/");
    }

    /** 사용자 문의와 그 모든 하위 경로(Story 3.6). 인가·CSRF·{@code Origin}이 이 함수 하나를 본다. */
    private static boolean isSupportPath(String uri) {
        return uri.equals(SUPPORT_PATH) || uri.startsWith(SUPPORT_PATH + "/");
    }

    /** 광고 참여와 그 모든 하위 경로(Story 4.1). 인가·CSRF·{@code Origin}이 이 함수 하나를 본다. */
    private static boolean isEngagementPath(String uri) {
        return uri.equals(ENGAGEMENT_PATH) || uri.startsWith(ENGAGEMENT_PATH + "/");
    }

    /** 계정 삭제 요청과 그 모든 하위 경로(Story 5.1). 인가·CSRF·{@code Origin}이 이 함수 하나를 본다. */
    private static boolean isAccountPath(String uri) {
        return uri.equals(ACCOUNT_PATH) || uri.startsWith(ACCOUNT_PATH + "/");
    }

    private static boolean isConnectionsPath(String uri) {
        return uri.equals(CONNECTIONS_BASE_URI) || uri.startsWith(CONNECTIONS_BASE_URI + "/");
    }

    /**
     * 세션 귀속 CSRF 토큰 저장소.
     *
     * <p>{@link HttpSessionCsrfTokenRepository}에 한 가지만 더한다 — <b>토큰을 저장하려고 세션을
     * 만들지 않는다.</b>
     *
     * <p>기본 구현의 {@code saveToken}은 {@code request.getSession()}을 부르고, 그 호출이 세션
     * 행을 하나 만든다. 그래서 세션이 없는 상태 변경 요청 하나마다(로그아웃한 브라우저의 재요청,
     * 익명 호출자의 탐침) 거절과 함께 행이 늘어난다. "실패는 어떤 상태도 바꾸지 않는다"와
     * "반복 로그아웃은 새 세션 행을 만들지 않는다"가 그 지점에서 깨진다.
     *
     * <p>저장을 건너뛰어도 검사는 그대로 성립한다. 저장되지 않은 새 토큰과 요청이 들고 온 값은
     * 어차피 다르므로 결과는 무변경 403 하나다. 이 배치에서 토큰이 만들어지는 정당한 순간은
     * 인증된 세션이 {@code GET /api/auth/session}을 부르는 때뿐이고, 그때는 세션이 이미 있다.
     */
    private static final class SessionBoundCsrfTokenRepository implements CsrfTokenRepository {

        private final HttpSessionCsrfTokenRepository delegate = new HttpSessionCsrfTokenRepository();

        private SessionBoundCsrfTokenRepository() {
            // 헤더 이름을 명시한다. 기본값과 같은 값이지만 프런트엔드가 같은 리터럴을 들고 있다.
            this.delegate.setHeaderName(CSRF_HEADER_NAME);
        }

        @Override
        public CsrfToken generateToken(HttpServletRequest request) {
            return this.delegate.generateToken(request);
        }

        @Override
        public void saveToken(
                @Nullable CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
            if (token != null && request.getSession(false) == null) {
                return;
            }
            this.delegate.saveToken(token, request, response);
        }

        @Override
        public @Nullable CsrfToken loadToken(HttpServletRequest request) {
            return this.delegate.loadToken(request);
        }
    }

    /**
     * 상태 변경 요청의 {@code Origin}이 이 서비스의 origin인지 확인한다.
     *
     * <p>CSRF 토큰만으로는 토큰이 새는 경로가 하나 생기면 방어가 통째로 사라진다. 서버가 아는
     * origin과 요청이 주장하는 origin을 함께 보면 그 경로 하나로는 통과하지 못한다.
     *
     * <p>{@code Origin}이 없는 요청도 거절한다. 브라우저는 상태 변경 요청에 이 헤더를 싣고,
     * 싣지 않는 것은 브라우저가 아니다. 공개 base URL이 구성되지 않은 스택에서는 비교할 origin이
     * 없으므로 모두 거절한다 — 그것이 fail-closed다.
     */
    private static final class SameOriginFilter extends OncePerRequestFilter {

        private final @Nullable String expectedOrigin;

        private SameOriginFilter(@Nullable String expectedOrigin) {
            this.expectedOrigin = expectedOrigin;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            if (isProtectedRequest(request)) {
                String origin = request.getHeader("Origin");
                if (this.expectedOrigin == null || !this.expectedOrigin.equals(origin)) {
                    // 어느 쪽이 어긋났는지 응답에 남기지 않는다. 공통 오류 계약의 403 하나다.
                    response.sendError(HttpStatus.FORBIDDEN.value());
                    return;
                }
            }
            filterChain.doFilter(request, response);
        }
    }
}
