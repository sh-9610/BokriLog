package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 로그아웃 계약.
 *
 * <p>로그아웃은 {@code POST /api/auth/logout} 하나이며 Story 1.4가 고정한 {@code /api/auth/} 상태
 * 변경 계약(세션 귀속 CSRF 토큰 + 동일 출처 {@code Origin})을 예외 없이 지난다. 실패는 인증 여부를
 * 드러내지 않는 403 {@code FORBIDDEN} 하나로 수렴한다.
 *
 * <p>세션 폐기는 세 가지를 함께 만족해야 한다 — 서버 세션 행 삭제, {@code SecurityContext} 정리,
 * 세션 쿠키 만료 지시. 셋 중 둘만 만족하는 상태를 로그아웃 완료로 보고하지 않는다. 그래서 이
 * 시험은 상태 코드만 보지 않고 저장소 행과 {@code Set-Cookie} 전문을 함께 단정한다.
 *
 * <p>반복 로그아웃이 403인 것은 결함이 아니라 계약의 결과다. {@code SameOriginFilter}와
 * {@code CsrfFilter}가 라우팅보다 앞에 있고 토큰 저장소가 세션 귀속이므로, 세션이 사라진 뒤의
 * 재요청은 라우팅에 닿기 전에 403으로 수렴한다. AC가 요구하는 "안전하게 처리"는 멱등한 상태
 * 코드가 아니라 <b>무변경</b>이며 403이 그것을 정확히 만족한다.
 */
class AuthLogoutTest extends AuthFlowTestBase {

    @Test
    void 정상_로그아웃은_204와_함께_세션과_쿠키를_모두_없앤다() throws Exception {
        HttpResponse<String> login = signIn("logout-happy-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();
        String token = csrfTokenOf(cookie);
        assertThat(token).isNotNull();
        assertThat(sessionCount()).isEqualTo(1);

        HttpResponse<String> response = logout(cookie, token);

        // 204다. 리다이렉트가 아니라서 `fetch`가 본문 없는 HTML을 따라가는 일이 없다.
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();

        // 1. 서버 세션 행 삭제. 속성 행도 함께 사라진다(`ON DELETE CASCADE`).
        assertThat(sessionCount()).isZero();
        assertThat(sessionAttributeCount()).isZero();

        // 2. 세션 쿠키 만료 지시. 값이 비고 즉시 만료가 실린다.
        String setCookie = setCookieOf(response).orElseThrow();
        assertThat(setCookie).startsWith(SESSION_COOKIE_NAME + "=;");
        assertThat(setCookie).contains("Max-Age=0");
        assertThat(setCookie).contains("Expires=");
        assertThat(setCookie).contains("Path=/");
        /*
         * 지우는 쿠키도 속성 세 개를 그대로 들고 있어야 한다. 브라우저는 속성이 어긋난
         * `Set-Cookie`를 원래 쿠키와 다른 것으로 볼 수 있고, 그러면 삭제가 성립하지 않아 쿠키가
         * 살아남는다. 로그인 시점의 같은 단정은 `AuthSessionContractTest`가 갖는다.
         */
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("SameSite=Lax");
        // 회전이 아니라 폐기다. 새 세션 값을 주지 않는다.
        assertThat(sessionCookieOf(response)).isEmpty();

        // 3. 그 쿠키로 이어지는 세션 확인이 더 이상 인증 상태가 아니다.
        HttpResponse<String> after = sessionState(cookie);
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.body()).isEqualTo("{\"authenticated\":false,\"destination\":\"entry\"}");
    }

    @Test
    void 로그아웃_응답에_세션_식별자나_토큰이_남지_않는다() throws Exception {
        HttpResponse<String> login = signIn("logout-redaction-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();
        String token = csrfTokenOf(cookie);
        assertThat(token).isNotNull();

        HttpResponse<String> response = logout(cookie, token);

        assertThat(response.statusCode()).isEqualTo(204);
        // 본문이 비어 있으므로 그 안에 남을 것도 없다. 헤더도 함께 본다 — `Set-Cookie` 하나가
        // 세션 이름을 담고, 그 값은 비어 있어야 한다.
        String headers = response.headers().map().toString();
        assertThat(headers).doesNotContain(cookie);
        assertThat(headers).doesNotContain(token);
    }

    @Test
    void 반복_로그아웃은_403이고_아무_상태도_바꾸지_않는다() throws Exception {
        HttpResponse<String> login = signIn("logout-repeat-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();
        String token = csrfTokenOf(cookie);
        assertThat(logout(cookie, token).statusCode()).isEqualTo(204);
        long accountsAfterLogout = accountCount();

        // 같은 쿠키·같은 토큰으로 다시 보낸다. 토큰이 세션과 함께 사라졌으므로 CSRF 단계에서
        // 수렴한다.
        HttpResponse<String> repeated = logout(cookie, token);

        assertThat(repeated.statusCode()).isEqualTo(403);
        assertThat(repeated.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(repeated.body()).contains("\"correlationId\"");
        /*
         * 새 세션 행이 생기지 않는다.
         *
         * 이 단정이 `AuthSecurityConfig`의 세션 귀속 CSRF 저장소가 하는 일을 고정한다. 기본
         * 구현은 토큰을 저장하려고 `request.getSession()`을 부르고, 그 호출 하나가 거절된 요청마다
         * 세션 행을 만든다.
         */
        assertThat(sessionCount()).isZero();
        // 새 인증도, 제공자 로그아웃 시작도 없다. 계정·매핑은 그대로다.
        assertThat(accountCount()).isEqualTo(accountsAfterLogout);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void CSRF_토큰이_없으면_403이고_세션은_살아_있다() throws Exception {
        HttpResponse<String> login = signIn("logout-no-token-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();

        HttpResponse<String> response = logout(cookie, null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertSessionStillAuthenticated(cookie);
    }

    @Test
    void 잘못된_CSRF_토큰은_403이고_값을_되비추지_않는다() throws Exception {
        HttpResponse<String> login = signIn("logout-bad-token-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();
        String wrongToken = "verifywrongcsrftokenmarker";

        HttpResponse<String> response = logout(cookie, wrongToken);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        // 무엇이 틀렸는지 알려 주지 않는다. 보낸 값을 응답에 되비추지도 않는다.
        assertThat(response.body()).doesNotContain(wrongToken);
        assertThat(response.headers().map().toString()).doesNotContain(wrongToken);
        assertSessionStillAuthenticated(cookie);
    }

    @Test
    void 다른_세션의_CSRF_토큰은_통하지_않는다() throws Exception {
        HttpResponse<String> victimLogin = signIn("logout-victim-" + UUID.randomUUID());
        String victimCookie = sessionCookieOf(victimLogin).orElseThrow();

        HttpResponse<String> attackerLogin = signIn("logout-attacker-" + UUID.randomUUID());
        String attackerCookie = sessionCookieOf(attackerLogin).orElseThrow();
        String attackerToken = csrfTokenOf(attackerCookie);
        assertThat(attackerToken).isNotNull();

        // 자기 토큰을 남의 쿠키에 실어 보낸다. 토큰이 세션에 귀속돼 있으므로 성립하지 않는다.
        HttpResponse<String> response = logout(victimCookie, attackerToken);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertSessionStillAuthenticated(victimCookie);
        assertThat(sessionCount()).isEqualTo(2);
    }

    @Test
    void 교차_출처_로그아웃은_토큰이_맞아도_403이다() throws Exception {
        HttpResponse<String> login = signIn("logout-cross-origin-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();
        String token = csrfTokenOf(cookie);
        assertThat(token).isNotNull();

        HttpResponse<String> foreign = logout(cookie, token, "https://evil.example");

        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(foreign.body()).contains("\"code\":\"FORBIDDEN\"");
        assertSessionStillAuthenticated(cookie);

        // `Origin`이 아예 없는 요청도 같다. 브라우저는 상태 변경 요청에 이 헤더를 싣는다.
        HttpResponse<String> headerless = logout(cookie, token, null);

        assertThat(headerless.statusCode()).isEqualTo(403);
        assertThat(headerless.body()).contains("\"code\":\"FORBIDDEN\"");
        assertSessionStillAuthenticated(cookie);
    }

    @Test
    void 미인증_로그아웃은_403이고_세션을_만들지_않는다() throws Exception {
        HttpResponse<String> response = logout(null, "verifyanonymouscsrftokenmarker");

        // 인증 여부를 응답 코드·본문으로 구분하지 않는다. 위 실패들과 같은 하나다.
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(sessionCount()).isZero();
        assertThat(accountCount()).isZero();
    }

    @Test
    void 한_사용자의_로그아웃이_다른_사용자의_세션을_건드리지_않는다() throws Exception {
        String subjectA = "logout-user-a-" + UUID.randomUUID();
        String subjectB = "logout-user-b-" + UUID.randomUUID();
        HttpResponse<String> loginA = signIn(subjectA);
        HttpResponse<String> loginB = signIn(subjectB);
        String cookieA = sessionCookieOf(loginA).orElseThrow();
        String cookieB = sessionCookieOf(loginB).orElseThrow();
        UUID userA = userIdOf(OidcStubConfiguration.issuer(), subjectA).orElseThrow();
        UUID userB = userIdOf(OidcStubConfiguration.issuer(), subjectB).orElseThrow();
        assertThat(userA).isNotEqualTo(userB);
        assertThat(sessionCountOf(userA)).isEqualTo(1);
        assertThat(sessionCountOf(userB)).isEqualTo(1);

        HttpResponse<String> response = logout(cookieA, csrfTokenOf(cookieA));

        assertThat(response.statusCode()).isEqualTo(204);
        // A의 행만 사라진다. `principal_name` 색인이 대상을 고르므로 조건절을 우리가 쓰지 않는다.
        assertThat(sessionCountOf(userA)).isZero();
        assertThat(sessionCountOf(userB)).isEqualTo(1);
        /*
         * B의 인증 상태와 목적지도 그대로다.
         *
         * 본문 전문을 문자열로 비교할 수 없다. 이 스토리가 더한 CSRF 토큰이 인증된 응답마다 실리고
         * 그 값은 실행마다 다르다. 대신 필드 집합을 함께 단정한다 — 열지 않은 필드가 하나라도
         * 늘면 `containsOnlyKeys`가 걸린다.
         */
        Map<String, Object> preserved = topLevelFieldsOf(sessionState(cookieB).body());
        assertThat(preserved).containsOnlyKeys("authenticated", "destination", "csrfToken");
        assertThat(preserved.get("authenticated")).isEqualTo(true);
        assertThat(preserved.get("destination")).isEqualTo("onboarding");
        assertThat(preserved.get("csrfToken")).isInstanceOf(String.class);
        // 계정과 매핑은 어느 쪽도 사라지지 않는다. 로그아웃은 계정 상태를 바꾸지 않는다.
        assertThat(accountCount()).isEqualTo(2);
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 로그아웃은_이_경로_하나뿐이고_다른_표면을_만들지_않는다() throws Exception {
        HttpResponse<String> login = signIn("logout-surface-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();

        /*
         * `logout()`을 매처 없이 켜면 Spring Security가 `/logout` 필터를 등록해 이 스토리가 만들지
         * 않은 HTTP 표면이 하나 늘어난다. 그 경로는 `/api/auth/` 밖이라 CSRF·Origin 보호도 받지
         * 않는다. 여기서 그 표면의 부재를 단정한다.
         */
        assertThat(send("POST", "/logout", cookie, "Origin", OidcStubConfiguration.PUBLIC_BASE_URL)
                        .statusCode())
                .isEqualTo(404);
        assertThat(send("GET", "/logout", cookie).statusCode()).isEqualTo(404);
        // 안전한 메서드로는 로그아웃이 성립하지 않는다. 매핑이 없어 404다.
        assertThat(get(AuthSecurityConfig.LOGOUT_PATH, cookie).statusCode()).isEqualTo(404);
        // 접두사가 겹치는 경로도 로그아웃이 아니다.
        assertThat(
                        send(
                                        "POST",
                                        AuthSecurityConfig.LOGOUT_PATH + "/x",
                                        cookie,
                                        "Origin",
                                        OidcStubConfiguration.PUBLIC_BASE_URL)
                                .statusCode())
                .isEqualTo(403);

        // 위 어떤 요청도 세션을 지우지 않았다.
        assertSessionStillAuthenticated(cookie);
    }

    @Test
    void 로그아웃은_인증_경로_밖의_오류_계약을_바꾸지_않는다() throws Exception {
        // Story 1.2·1.4 회귀. `logout()` DSL이 켜지면서 라우팅이 달라지지 않았음을 본다.
        assertThat(send("POST", AuthSecurityConfig.STATUS_PATH, null).statusCode()).isEqualTo(405);
        assertThat(get("/api/nope", null).statusCode()).isEqualTo(404);
        assertThat(send("PUT", "/onboarding", null).statusCode()).isEqualTo(404);
    }

    /** 그 쿠키로 이어지는 세션이 여전히 인증 상태인가. 무변경 거절의 핵심 단정이다. */
    private void assertSessionStillAuthenticated(String sessionCookie) throws Exception {
        HttpResponse<String> state = sessionState(sessionCookie);
        assertThat(state.statusCode()).isEqualTo(200);
        assertThat(state.body()).contains("\"authenticated\":true");
    }

    /** 세션 속성 행 수. 세션 행이 사라질 때 함께 사라지는지 본다. */
    private long sessionAttributeCount() {
        return jdbc().sql("SELECT count(*) FROM spring_session_attributes").query(Long.class).single();
    }
}
