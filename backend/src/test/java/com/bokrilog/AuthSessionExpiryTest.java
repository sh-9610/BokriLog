package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.DevOidcStub.TokenPlan;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

/**
 * 세션 수명과 관리상 상태 전환.
 *
 * <p>수명은 두 축이다 — 유휴 30분({@code spring.session.timeout})과 절대 12시간
 * ({@code bokrilog.session.absolute-lifetime}). 앞의 것은 "쓰지 않으면 끊는다"이고 뒤의 것은
 * "계속 써도 끊는다"다. 유휴만 있으면 탈취된 쿠키가 주기적인 요청만으로 무한히 유지된다.
 *
 * <p><b>네 경우의 외부 관측이 하나로 수렴한다.</b> 유휴 만료, 절대 수명 초과, 관리상 폐기, 재인증
 * 요구가 모두 미인증 응답 하나로 답한다. 응답으로 갈라 주면 그 차이가 곧 "이 브라우저에 세션이
 * 있었는가"와 "무엇이 그 세션을 끊었는가"를 알려 주는 신호가 된다. 차이는 저장소 한 곳뿐이며
 * 그것을 이 시험이 고정한다 — 재인증 요구만 행을 남긴다.
 *
 * <p>시계를 조작하지 않고 저장소의 시각을 옮긴다. 시험용으로 짧은 수명을 따로 설정하면 검증하는
 * 값과 제품이 쓰는 값이 갈라지고, 그때 이 시험은 아무것도 확인하지 않는 상태가 된다.
 */
class AuthSessionExpiryTest extends AuthFlowTestBase {

    /** 미인증 세션 확인의 유일한 모양. 네 경우가 모두 이 문자열로 수렴한다. */
    private static final String UNAUTHENTICATED_BODY =
            "{\"authenticated\":false,\"destination\":\"entry\"}";

    @Test
    void 절대_수명_설정_키가_실제로_바인딩된다() {
        /*
         * 값을 비교하는 것만으로는 아무것도 확인하지 못한다. 코드 기본값이 12시간이므로
         * `absoluteLifetime()`을 12시간과 비교하는 단정은 `absolute-lifetime` 오타나 prefix 변경
         * 뒤에도 그대로 통과한다 — 설정이 조용히 무시되고 기본값이 그 자리를 대신하기 때문이다.
         *
         * 그래서 바인딩 자체를 본다. 지정한 컨텍스트와 지정하지 않은 컨텍스트의 값이 실제로
         * 달라지는지가 "이 키가 이 record에 닿는가"의 직접적인 답이고, `ApplicationContextRunner`
         * 면 그 배선만 정확히 볼 수 있다(`SchemaValidationConfigTest`와 같은 관용, Docker 불필요).
         */
        ApplicationContextRunner contexts =
                new ApplicationContextRunner().withUserConfiguration(SessionPropertiesConfig.class);

        // 지정하지 않으면 fail-closed 바닥이다.
        contexts.run(context -> assertThat(boundLifetime(context)).isEqualTo(Duration.ofHours(12)));
        // 지정하면 그 값이다. 이 단정이 위와 다른 값을 보는 것이 곧 키가 바인딩된다는 증거다.
        contexts.withPropertyValues("bokrilog.session.absolute-lifetime=90m")
                .run(context -> assertThat(boundLifetime(context)).isEqualTo(Duration.ofMinutes(90)));
        // 바닥보다 긴 값은 접힌다. 환경 변수가 상한을 조용히 늘리는 경로를 clamp가 닫는다.
        contexts.withPropertyValues("bokrilog.session.absolute-lifetime=48h")
                .run(context -> assertThat(boundLifetime(context)).isEqualTo(Duration.ofHours(12)));
        // 0과 음수도 상한 없음과 같으므로 접힌다.
        contexts.withPropertyValues("bokrilog.session.absolute-lifetime=0s")
                .run(context -> assertThat(boundLifetime(context)).isEqualTo(Duration.ofHours(12)));
    }

    @Test
    void 실행_중인_애플리케이션의_수명_두_값이_설정_파일의_값과_같다() throws Exception {
        /*
         * 위 시험이 키를, 이 시험이 실제로 뜬 프로세스를 본다. 유휴 수명은 세션 행의
         * `max_inactive_interval`로 관측한다 — 리터럴 1800과 비교하는 것이 "30m이 실제로
         * 적용됐는가"의 답이다.
         */
        assertThat(sessionGuard().absoluteLifetime()).isEqualTo(Duration.ofHours(12));
        assertThat(SessionGuard.DEFAULT_ABSOLUTE_LIFETIME).isEqualTo(Duration.ofHours(12));

        sessionCookieOf(signIn("expiry-lifetimes-" + UUID.randomUUID())).orElseThrow();

        assertThat(
                        jdbc().sql("SELECT max(max_inactive_interval) FROM spring_session")
                                .query(Integer.class)
                                .single())
                .isEqualTo(1800);
    }

    /** 바인딩된 절대 수명. */
    private static Duration boundLifetime(ApplicationContext context) {
        return context.getBean(SessionGuard.SessionProperties.class).absoluteLifetime();
    }

    /**
     * 설정 record 하나만 켜는 구성.
     *
     * <p>{@code AuthSecurityConfig}가 애플리케이션에서 이 record를 켜지만 그 클래스를 여기 들이면
     * 필터 체인 전부가 따라온다. 확인하려는 것은 키와 record의 결합 하나다.
     */
    @EnableConfigurationProperties(SessionGuard.SessionProperties.class)
    static class SessionPropertiesConfig {}

    @Test
    void 유휴_만료_후_접근은_미인증으로_수렴하고_행을_남기지_않는다() throws Exception {
        String subject = "expiry-idle-" + UUID.randomUUID();
        HttpResponse<String> login = signIn(subject);
        String cookie = sessionCookieOf(login).orElseThrow();
        assertThat(sessionCount()).isEqualTo(1);

        // `spring.session.timeout: 30m`을 넘긴다. 마지막 접근을 과거로 옮기는 것이 곧 유휴다.
        ageLastAccess(sessionIdOf(cookie), Duration.ofMinutes(31));

        HttpResponse<String> response = sessionState(cookie);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(UNAUTHENTICATED_BODY);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        // 만료 행 정리는 Spring Session이 소유한다. 접근이 그 정리를 실행한다.
        assertThat(sessionCount()).isZero();
        // 이전 세션으로는 어떤 사용자 데이터도 조회되지 않는다. 응답에 식별자가 실릴 자리가 없다.
        assertThat(response.body()).doesNotContain(subject);
        assertThat(response.body()).doesNotContain("csrfToken");
    }

    @Test
    void 만료된_세션의_쿠키로는_상태_변경도_성립하지_않는다() throws Exception {
        HttpResponse<String> login = signIn("expiry-state-change-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();
        String token = csrfTokenOf(cookie);
        assertThat(token).isNotNull();

        ageLastAccess(sessionIdOf(cookie), Duration.ofMinutes(31));

        // 만료 전에 받아 둔 토큰이다. 세션이 사라졌으므로 그 토큰이 귀속될 자리도 없다.
        HttpResponse<String> response = logout(cookie, token);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(sessionCount()).isZero();
    }

    @Test
    void 절대_수명_안의_세션은_유휴가_아니면_그대로_인증_상태다() throws Exception {
        HttpResponse<String> login = signIn("expiry-within-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();

        // 절대 수명에 아직 닿지 않았다. 이 단정이 없으면 아래 시험은 "오래된 세션이면 무조건
        // 끊는다"와 구분되지 않는다.
        ageCreation(sessionIdOf(cookie), sessionGuard().absoluteLifetime().minusMinutes(5));

        HttpResponse<String> response = sessionState(cookie);

        assertThat(response.body()).contains("\"authenticated\":true");
        assertThat(sessionCount()).isEqualTo(1);
    }

    @Test
    void 절대_수명을_넘긴_세션은_계속_쓰이고_있어도_그_요청에서_폐기된다() throws Exception {
        HttpResponse<String> login = signIn("expiry-absolute-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();
        assertThat(sessionState(cookie).body()).contains("\"authenticated\":true");

        /*
         * 생성 시각만 옮긴다. 마지막 접근은 방금이므로 이 세션은 유휴 만료가 아니다 — "계속
         * 쓰이고 있지만 만들어진 지 오래된" 상태이고, 그것이 절대 수명이 잡아야 하는 경우다.
         * 재요청·세션 ID 회전으로 이 상한이 늘어나지 않는다는 계약이 여기서 성립한다.
         */
        ageCreation(sessionIdOf(cookie), sessionGuard().absoluteLifetime().plusMinutes(1));

        HttpResponse<String> response = sessionState(cookie);

        assertThat(response.statusCode()).isEqualTo(200);
        // 유휴 만료와 같은 외부 모양이다.
        assertThat(response.body()).isEqualTo(UNAUTHENTICATED_BODY);
        // 그 요청에서 폐기된다 — 행이 사라지고 쿠키에 만료 지시가 실린다.
        assertThat(sessionCount()).isZero();
        String setCookie = setCookieOf(response).orElseThrow();
        assertThat(setCookie).startsWith(SESSION_COOKIE_NAME + "=;");
        assertThat(setCookie).contains("Max-Age=0");
    }

    @Test
    void 관리상_폐기된_세션의_쿠키는_미인증으로_수렴한다() throws Exception {
        String subject = "expiry-revoked-" + UUID.randomUUID();
        HttpResponse<String> login = signIn(subject);
        String cookie = sessionCookieOf(login).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        // HTTP가 아니라 내부 bean으로 상태를 만든다. 이 기능에 도달하는 HTTP 경로는 없다.
        assertThat(sessionGuard().revokeSessionsOf(userId)).isEqualTo(1);

        HttpResponse<String> response = sessionState(cookie);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(UNAUTHENTICATED_BODY);
        assertThat(sessionCountOf(userId)).isZero();
        assertThat(sessionCount()).isZero();
        // 계정은 그대로다. 세션 폐기는 계정 상태를 바꾸지 않는다.
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 재인증을_요구받은_세션은_차단되지만_행은_남는다() throws Exception {
        String subject = "expiry-reauth-" + UUID.randomUUID();
        HttpResponse<String> login = signIn(subject);
        String cookie = sessionCookieOf(login).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        // 표시 전에 받아 둔다. 표시 뒤에는 세션 확인이 미인증으로 답해 토큰이 실리지 않으므로,
        // 여기서 받지 않으면 아래 단정이 "토큰이 없어서 403"과 구분되지 않는다.
        String token = csrfTokenOf(cookie);
        assertThat(token).isNotNull();

        assertThat(sessionGuard().requireReauthenticationOf(userId)).isEqualTo(1);

        HttpResponse<String> response = sessionState(cookie);

        // 폐기와 응답으로 구분되지 않는다. 위 `관리상_폐기된_세션`과 같은 문자열이다.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(UNAUTHENTICATED_BODY);
        /*
         * 차이는 여기 하나다. 행이 남아 1.8의 재인증이 회전시킬 대상이 보존된다. 이 단정이 없으면
         * 두 상태가 같은 것으로 구현돼도 아무 시험이 실패하지 않고, 1.8이 상태 기계를 다시 만든다.
         */
        assertThat(sessionCountOf(userId)).isEqualTo(1);

        /*
         * 표시된 세션으로는 보호 경로의 상태 변경이 차단된다. 토큰이 맞아도 성립하지 않는다.
         *
         * 로그아웃이 아닌 상태 변경으로 확인한다 — 로그아웃은 상태를 없애기만 하므로 아래 시험이
         * 고정하는 대로 통과해야 한다. `POST /api/auth/session`은 건강한 세션이라면 라우팅까지
         * 닿아 405가 되는 경로이고, 여기서 403이 나오는 것은 guard가 답했다는 뜻이다. 미인증
         * 요청도 같은 403이므로 응답으로 두 상태가 구분되지 않는다.
         *
         * 본문 모양까지 고정한다. 이 403은 설정된 `accessDeniedHandler`가 아니라 필터의
         * `sendError`에서 나오므로, 상태 코드만 보면 그 경로가 공통 오류 계약을 지나는지 아무
         * 시험도 확인하지 않는다.
         */
        HttpResponse<String> refused =
                send(
                        "POST",
                        AuthSecurityConfig.SESSION_PATH,
                        cookie,
                        "Origin",
                        OidcStubConfiguration.PUBLIC_BASE_URL,
                        AuthSecurityConfig.CSRF_HEADER_NAME,
                        token);

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(refused.body()).contains("\"correlationId\"");
        // 사유를 남기지 않는다. 표시의 이름도 토큰 값도 응답에 되비치지 않는다.
        assertThat(refused.body()).doesNotContain("reauth");
        assertThat(refused.body()).doesNotContain(token);
        assertThat(sessionCountOf(userId)).isEqualTo(1);
    }

    @Test
    void 표시된_세션은_새_인증_흐름을_시작할_수_없다() throws Exception {
        String subject = "expiry-reauth-start-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(sessionGuard().requireReauthenticationOf(userId)).isEqualTo(1);

        /*
         * 인증 시작은 safe GET이라 위 403에 걸리지 않는다. 그래서 guard가 이 경로에서 thread-local
         * 인증을 남겨 두고, `authorizationStartFilter`의 "이미 인증된 세션은 새 흐름을 열지
         * 않는다" 판정이 그것을 보고 거절한다. 지우면 그 판정이 성립하지 않아 이 세션이 새 흐름을
         * 열고, 그 흐름의 콜백이 같은 세션 행에 다른 주체를 심는다 — 계정 갈아타기다.
         */
        Authorization start = startAuthorization(null, cookie);

        assertThat(start.status()).isEqualTo(302);
        // 제공자로 보내지 않는다. 지금 세션의 목적지로 되돌린다.
        assertThat(start.location()).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(start.location()).doesNotContain("dev-oidc");
        assertThat(start.location()).doesNotContain("client_id");
        // 흐름이 시작되지 않았으므로 state도 발급되지 않았다.
        assertThat(start.state()).isEmpty();
        // 행은 그대로다. 새 세션도 생기지 않았다.
        assertThat(sessionCountOf(userId)).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);
        // 세션 확인은 여전히 미인증이다. 인증 흐름 밖에서는 표시가 그대로 차단한다.
        assertThat(sessionState(cookie).body()).isEqualTo(UNAUTHENTICATED_BODY);
    }

    @Test
    void 표시된_세션도_스스로_로그아웃할_수_있다() throws Exception {
        String subject = "expiry-reauth-logout-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        // 표시 전에 받아 둔다. 표시 뒤에는 세션 확인이 미인증으로 답해 토큰이 실리지 않는다.
        String token = csrfTokenOf(cookie);
        assertThat(sessionGuard().requireReauthenticationOf(userId)).isEqualTo(1);

        /*
         * 로그아웃은 상태를 없애기만 한다. 막으면 표시된 세션은 스스로를 끝낼 방법이 없고,
         * 저장된 `SecurityContext`와 CSRF 토큰을 실은 행이 유휴 만료까지 남는다.
         */
        HttpResponse<String> response = logout(cookie, token);

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(sessionCountOf(userId)).isZero();
        assertThat(sessionCount()).isZero();
        // 쿠키 만료 지시도 정상 로그아웃과 같다.
        String setCookie = setCookieOf(response).orElseThrow();
        assertThat(setCookie).startsWith(SESSION_COOKIE_NAME + "=;");
        assertThat(setCookie).contains("Max-Age=0");
        // 계정은 그대로다. 로그아웃은 계정 상태를 바꾸지 않는다.
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void 성공적인_재인증은_표시를_지운다() throws Exception {
        /*
         * `changeSessionId`는 세션 속성을 보존한다. 표시가 남으면 방금 성공한 인증으로 만든 세션이
         * 영구히 미인증으로 답하고, 사용자는 로그인에 성공해도 진입 화면으로 되돌려진다.
         *
         * 흐름 세션을 표시해 그 경로를 만든다. 인증된 세션은 위 시험이 고정한 대로 새 흐름을 시작
         * 할 수 없으므로, 표시를 들고 `onSuccess`에 도달하는 길은 이 하나다(1.8의 재인증도 같은
         * 지점을 지난다).
         */
        Authorization start = startAuthorization();
        String flowCookie = start.sessionCookie();
        assertThat(flowCookie).isNotNull();
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(flowCookie))).isTrue();

        String subject = "expiry-reauth-success-" + UUID.randomUUID();
        String code = issueCode(start, TokenPlan.valid(start.nonce()).subject(subject));
        HttpResponse<String> callback = callback(start, flowCookie, code, start.state());

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        String cookie = sessionCookieOf(callback).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        // 표시가 사라졌으므로 이 세션은 인증 상태로 답한다.
        assertThat(sessionState(cookie).body()).contains("\"authenticated\":true");
        assertThat(sessionCountOf(userId)).isEqualTo(1);
        // 저장소에도 남지 않는다. 속성 하나가 그 표시의 유일한 흔적이었다.
        assertThat(storedAttributeNames()).doesNotContain(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE);
        // 상태 변경도 다시 성립한다. 차단이 풀린 것을 보호 경로에서 확인한다.
        assertThat(logout(cookie, csrfTokenOf(cookie)).statusCode()).isEqualTo(204);
    }

    /** 저장된 세션 속성 이름 전부. 표시가 저장소에 남았는지 본다. */
    private String storedAttributeNames() {
        return String.join(
                " ",
                jdbc().sql("SELECT attribute_name FROM spring_session_attributes")
                        .query(String.class)
                        .list());
    }

    @Test
    void 한_사용자의_세션_전환이_다른_사용자를_건드리지_않는다() throws Exception {
        String subjectA = "expiry-user-a-" + UUID.randomUUID();
        String subjectB = "expiry-user-b-" + UUID.randomUUID();
        String cookieA = sessionCookieOf(signIn(subjectA)).orElseThrow();
        String cookieB = sessionCookieOf(signIn(subjectB)).orElseThrow();
        UUID userA = userIdOf(OidcStubConfiguration.issuer(), subjectA).orElseThrow();
        UUID userB = userIdOf(OidcStubConfiguration.issuer(), subjectB).orElseThrow();

        assertThat(sessionGuard().revokeSessionsOf(userA)).isEqualTo(1);

        assertThat(sessionState(cookieA).body()).isEqualTo(UNAUTHENTICATED_BODY);
        assertThat(sessionState(cookieB).body()).contains("\"authenticated\":true");
        assertThat(sessionCountOf(userA)).isZero();
        assertThat(sessionCountOf(userB)).isEqualTo(1);

        // 재인증 요구도 같다. B는 표시되지 않는다.
        assertThat(sessionGuard().requireReauthenticationOf(userB)).isEqualTo(1);
        assertThat(sessionState(cookieB).body()).isEqualTo(UNAUTHENTICATED_BODY);
        assertThat(sessionCountOf(userB)).isEqualTo(1);
    }

    @Test
    void 존재하지_않는_사용자의_세션_전환은_아무것도_바꾸지_않는다() throws Exception {
        String subject = "expiry-untouched-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID stranger = UUID.randomUUID();

        // 존재 여부를 값으로 알려 주지 않는다. 대상이 0건인 것과 실패는 다른 일이다.
        assertThat(sessionGuard().revokeSessionsOf(stranger)).isZero();
        assertThat(sessionGuard().requireReauthenticationOf(stranger)).isZero();

        assertThat(sessionState(cookie).body()).contains("\"authenticated\":true");
        assertThat(sessionCount()).isEqualTo(1);
    }
}
