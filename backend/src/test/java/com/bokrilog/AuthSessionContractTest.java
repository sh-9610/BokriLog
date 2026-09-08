package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.DevOidcStub.TokenPlan;
import com.bokrilog.identity.AccessDecision;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 세션 계약.
 *
 * <p>브라우저에 나가는 것은 불투명한 세션 쿠키 하나뿐이고, 그 쿠키의 속성 세 가지와 로그인 시점의
 * ID 회전은 응답 문자열로 단정한다. JDK {@code HttpClient}는 쿠키 정책을 강제하지 않으므로
 * {@code Secure} 쿠키도 평문 HTTP에서 그대로 관측된다 — 그래서 개발 편의로 {@code Secure}를
 * 프로필별로 끄지 않아도 이 계약을 시험할 수 있다.
 *
 * <p>세션 개설 판정은 {@code identity} 공개 API가 유일한 권위다. 삭제가 시작된 계정으로는 어떤
 * 검증된 콜백도 세션을 얻지 못하고, 그 사실이 표지로도 드러나지 않는다.
 */
class AuthSessionContractTest extends AuthFlowTestBase {

    @Test
    void 세션_쿠키는_HttpOnly와_Secure와_SameSite_Lax다() throws Exception {
        HttpResponse<String> response =
                get(
                        AuthSecurityConfig.AUTHORIZATION_BASE_URI
                                + "/"
                                + AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                        null);

        String setCookie = setCookieOf(response).orElseThrow();
        assertThat(setCookie).startsWith(SESSION_COOKIE_NAME + "=");
        assertThat(setCookie).contains("Path=/");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
        assertThat(setCookie).contains("SameSite=Lax");
        // 값에 사용자 식별자·이메일이 실리지 않는다. Spring Session이 base64로 실어 보내는
        // 불투명한 세션 식별자뿐이다.
        assertThat(setCookie).doesNotContain("@");
    }

    @Test
    void 로그인_성공_시_세션_ID가_회전하고_세션_행이_PostgreSQL에_남는다() throws Exception {
        Authorization authorization = startAuthorization();
        String beforeLogin = authorization.sessionCookie();
        assertThat(beforeLogin).isNotNull();

        String code = issueCode(authorization, TokenPlan.valid(authorization.nonce()));
        HttpResponse<String> response =
                callback(authorization, beforeLogin, code, authorization.state());

        String afterLogin = sessionCookieOf(response).orElseThrow();
        // 세션 고정 공격은 로그인 전에 심어 둔 식별자가 로그인 후에도 유효할 때 성립한다.
        assertThat(afterLogin).isNotEqualTo(beforeLogin);
        assertThat(sessionCount()).isEqualTo(1);

        // 회전 전 쿠키로는 더 이상 인증된 상태가 아니다.
        HttpResponse<String> stale = sessionState(beforeLogin);
        assertThat(stale.body()).contains("\"authenticated\":false");
    }

    @Test
    void 세션_저장소에_제공자_토큰과_subject가_남지_않는다() throws Exception {
        String subject = "stored-attributes-" + UUID.randomUUID();
        HttpResponse<String> login = signIn(subject);
        assertThat(login.statusCode()).isEqualTo(302);
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        /*
         * 속성 본문을 그대로 읽는다.
         *
         * 행 개수만 세면 "무엇이 저장됐는가"는 아무도 보지 않는다. Spring Security가 만든 인증
         * 결과에는 ID Token 원문과 claim이 들어 있고 세션은 PostgreSQL로 가므로, 성공 직후 주체를
         * 내부 식별자 하나로 바꿔 쓰는 세 줄(`AuthFlowHandlers`)이 사라지면 그 값들이 여기 남는다.
         * `attribute_bytes`는 Java 직렬화 바이트이고 그 안의 문자열은 그대로 보인다 — LATIN1은
         * 모든 바이트 값을 문자로 받으므로 어떤 바이트 열도 안전하게 텍스트로 옮겨진다.
         */
        String stored = storedSessionAttributes();

        // 검사가 아무것도 보지 않는 상태를 먼저 막는다.
        assertThat(stored).isNotEmpty();
        assertThat(stored).doesNotContain(subject);
        assertThat(stored).doesNotContain(OidcStubConfiguration.issuer());
        assertThat(stored).doesNotContain(OidcStubConfiguration.CLIENT_SECRET);
        assertThat(stored).doesNotContain("id_token");
        assertThat(stored).doesNotContain("access_token");
        assertThat(stored).doesNotContain("refresh_token");
        assertThat(stored).doesNotContain("OidcUser");
        assertThat(stored).doesNotContain("OAuth2AuthenticationToken");
        // 세션이 결정하는 값은 내부 user_id 하나다. 그것은 남아야 한다.
        assertThat(stored).contains(userId.toString());
    }

    @Test
    void 인증_전_흐름_세션은_짧은_수명만_갖고_로그인이_그것을_되돌린다() throws Exception {
        /*
         * 미인증 인증 시작 한 번마다 세션 행이 하나 생긴다. Spring Security가 authorization
         * request를 HTTP 세션에 담기 때문이며, 상한이 없으면 익명 호출자가 세션 테이블을 무제한
         * 키울 수 있다. 수명이 그 경계다.
         */
        Authorization authorization = startAuthorization();

        assertThat(sessionCount()).isEqualTo(1);
        assertThat(maxInactiveInterval()).isEqualTo(AuthFlowHandlers.FLOW_SESSION_TIMEOUT_SECONDS);

        String code = issueCode(authorization, TokenPlan.valid(authorization.nonce()));
        HttpResponse<String> response =
                callback(authorization, code);

        assertThat(response.statusCode()).isEqualTo(302);
        // 로그인한 세션은 구성된 수명을 갖는다. 흐름의 짧은 수명이 그대로 남으면 로그인한
        // 사용자가 5분 유휴로 로그아웃된다. 1800초는 `application.yml`의
        // `spring.session.timeout: 30m`이며 그 파일이 그 값의 유일한 정의다.
        assertThat(maxInactiveInterval()).isEqualTo(1800);
    }

    @Test
    void 이미_인증된_세션에서는_로그인_시작을_거절한다() throws Exception {
        HttpResponse<String> login = signIn("already-signed-in-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();

        Authorization retry = startAuthorization(null, cookie);

        assertThat(retry.status()).isEqualTo(302);
        // 제공자로 보내지 않는다. 새 흐름을 열면 같은 브라우저에서 다른 계정으로 갈아탈 수 있다.
        assertThat(retry.location()).doesNotContain(OidcStubConfiguration.issuer());
        assertThat(retry.location()).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        // 신규 가입도 열지 않는다.
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 삭제가_시작된_계정으로는_세션을_얻지_못한다() throws Exception {
        String subject = "deleting-user-" + UUID.randomUUID();
        UUID userId = accountLifecycle().createAccount();
        assertThat(
                        accountLifecycle()
                                .linkSocialIdentity(
                                        userId,
                                        AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                                        OidcStubConfiguration.issuer(),
                                        subject))
                .isEqualTo(AccessDecision.ALLOWED);
        accountDeletion().beginDeletion(userId);

        HttpResponse<String> response = signIn(subject);

        // 인증 화면으로 수렴한다. 표지는 다른 모든 실패와 같은 값이라 삭제 여부가 드러나지 않는다.
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response))
                .endsWith(AuthTokenValidationTest.failureLocation(AuthFlowHandlers.FAILURE_MARKER_FAILED));
        // 계정도 매핑도 늘지 않았다. 신규 가입으로 우회되지 않는다.
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionCount()).isZero();
    }

    @Test
    void 삭제_전환이_콜백보다_먼저_커밋되면_세션을_만들지_않는다() throws Exception {
        String subject = "racing-user-" + UUID.randomUUID();
        HttpResponse<String> first = signIn(subject);
        assertThat(first.statusCode()).isEqualTo(302);
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        // 흐름을 먼저 시작해 authorization request를 세션에 넣어 둔다.
        Authorization authorization = startAuthorization();
        String code =
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(subject));

        // 그 사이에 삭제 전환이 커밋된다. 콜백은 이 전환 뒤에 도착한다.
        accountDeletion().beginDeletion(userId);

        HttpResponse<String> response = callback(authorization, code);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response))
                .endsWith(AuthTokenValidationTest.failureLocation(AuthFlowHandlers.FAILURE_MARKER_FAILED));
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 삭제가_끝난_계정은_생명주기_guard가_세션_개설을_계속_거부한다() throws Exception {
        /*
         * 삭제 완료 계정에는 인증 매핑이 남지 않는다(`erasePayload`가 함께 지운다). 그래서 로그인
         * 흐름으로는 그 계정에 도달할 수 없고, 대신 guard 행이 판정을 계속 거부하는지를 본다.
         * 같은 `(issuer, subject)`로 새 계정을 만들 수 있는지는 Epic 5가 확정할 정책이며 이
         * 스토리가 답을 고정하지 않는다.
         */
        String subject = "erased-user-" + UUID.randomUUID();
        UUID userId = accountLifecycle().createAccount();
        accountLifecycle()
                .linkSocialIdentity(
                        userId,
                        AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                        OidcStubConfiguration.issuer(),
                        subject);
        accountDeletion().beginDeletion(userId);
        accountDeletion().erasePayload(userId);

        assertThat(accountLifecycle().decideSessionCreation(userId)).isEqualTo(AccessDecision.DENIED);
        assertThat(accountLifecycle().findUserIdBySocialIdentity(OidcStubConfiguration.issuer(), subject))
                .isEmpty();
    }

    @Test
    void 미인증_세션_확인은_인증되지_않았다고_답한다() throws Exception {
        HttpResponse<String> response = sessionState(null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
        assertThat(response.body()).isEqualTo("{\"authenticated\":false,\"destination\":\"entry\"}");
        // 세션 확인만으로 세션이 만들어지지 않는다.
        assertThat(sessionCount()).isZero();
    }

    @Test
    void 인증된_세션_확인은_상태와_목적지_종류와_CSRF_토큰만_돌려준다() throws Exception {
        String subject = "session-state-" + UUID.randomUUID();
        HttpResponse<String> login = signIn(subject);
        String cookie = sessionCookieOf(login).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        HttpResponse<String> response = sessionState(cookie);

        assertThat(response.statusCode()).isEqualTo(200);
        /*
         * 필드 세 개다. Story 1.5가 CSRF 토큰을 더했다 — 쿠키 저장소를 쓰지 않는 구성에서
         * 클라이언트가 토큰을 얻는 유일한 경로이며, 그래서 브라우저가 갖는 쿠키가 하나로 남는다.
         *
         * 토큰 값은 실행마다 다르므로 본문 전문을 비교할 수 없다. 대신 필드 집합을 정규화해
         * 비교한다 — 이 스토리가 열지 않은 필드가 하나라도 늘면 여기서 걸린다.
         */
        assertThat(fieldNamesOf(response.body()))
                .containsExactly("authenticated", "destination", "csrfToken");
        assertThat(response.body()).contains("\"authenticated\":true");
        assertThat(response.body()).contains("\"destination\":\"onboarding\"");
        String token = csrfTokenIn(response.body());
        assertThat(token).isNotNull().isNotEmpty();

        // user_id·subject·제공자 토큰이 응답에 없다(AD-14). 실린 토큰은 CSRF 토큰 하나다.
        assertThat(response.body()).doesNotContain(userId.toString());
        assertThat(response.body()).doesNotContain(subject);
        assertThat(response.body()).doesNotContain("id_token");
        assertThat(response.body()).doesNotContain("access_token");
        // 세션 식별자도 실리지 않는다. 토큰이 쿠키 값과 같으면 그것이 곧 세션 유출이다.
        assertThat(token).isNotEqualTo(cookie);
        assertThat(token).isNotEqualTo(sessionIdOf(cookie));
        assertThat(response.body()).doesNotContain(cookie);

        // 뒤로 가기나 공유 캐시가 인증된 상태를 되살리지 못한다. 이제 토큰도 함께 실리므로
        // 이 지시가 한 겹 더 필요하다.
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void CSRF_토큰은_세션에_귀속하고_다른_세션에서는_통하지_않는다() throws Exception {
        HttpResponse<String> firstLogin = signIn("csrf-binding-first-" + UUID.randomUUID());
        String firstCookie = sessionCookieOf(firstLogin).orElseThrow();
        String firstToken = csrfTokenOf(firstCookie);

        HttpResponse<String> secondLogin = signIn("csrf-binding-second-" + UUID.randomUUID());
        String secondCookie = sessionCookieOf(secondLogin).orElseThrow();
        String secondToken = csrfTokenOf(secondCookie);

        assertThat(firstToken).isNotNull();
        assertThat(secondToken).isNotNull();
        assertThat(firstToken).isNotEqualTo(secondToken);

        /*
         * 교차 세션 시도가 먼저다. 두 세션이 모두 살아 있는 동안이어야 이 403이 "토큰이 다른
         * 세션 것이다"로 설명된다 — 첫 토큰으로 먼저 로그아웃해 버리면 그 토큰은 세션과 함께
         * 사라지고, 뒤이은 403은 "그 토큰이 이제 존재하지 않는다"와 구분되지 않는다.
         */
        assertThat(logout(secondCookie, firstToken).statusCode()).isEqualTo(403);
        // 거절된 상태 변경은 어느 세션도 건드리지 않는다.
        assertThat(sessionState(secondCookie).body()).contains("\"authenticated\":true");
        assertThat(sessionState(firstCookie).body()).contains("\"authenticated\":true");

        // 자기 세션에서는 통한다. 이 단정이 없으면 위 거절이 "토큰은 원래 통하지 않는다"와
        // 구분되지 않는다.
        assertThat(logout(firstCookie, firstToken).statusCode()).isEqualTo(204);
        // 두 번째 토큰도 자기 세션에서 통한다. 첫 로그아웃이 남의 토큰을 무효화하지 않았다.
        assertThat(logout(secondCookie, secondToken).statusCode()).isEqualTo(204);
    }

    @Test
    void 미인증_세션_확인은_CSRF_토큰을_싣지_않는다() throws Exception {
        HttpResponse<String> response = sessionState(null);

        /*
         * 미인증 응답에 토큰 필드가 없다. 세션에 귀속되지 않은 토큰은 어떤 상태 변경도 통과시키지
         * 못하고, 실으면 그 필드를 채우려고 세션을 만드는 경로가 열린다. 아래 단정이 그 부작용의
         * 부재까지 함께 본다.
         */
        assertThat(fieldNamesOf(response.body())).containsExactly("authenticated", "destination");
        assertThat(csrfTokenIn(response.body())).isNull();
        assertThat(sessionCount()).isZero();
    }

    @Test
    void 상태_변경_요청은_동일_출처_Origin_없이_거절한다() throws Exception {
        HttpResponse<String> response = send("POST", AuthSecurityConfig.SESSION_PATH, null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(response.body()).contains("\"correlationId\"");
    }

    @Test
    void 상태_변경_요청은_다른_출처의_Origin을_거절한다() throws Exception {
        HttpResponse<String> response =
                send("POST", AuthSecurityConfig.SESSION_PATH, null, "Origin", "https://evil.example");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
    }

    @Test
    void 미인증_상태_변경_요청은_CSRF_토큰이_없으면_거절한다() throws Exception {
        HttpResponse<String> response =
                send(
                        "POST",
                        AuthSecurityConfig.SESSION_PATH,
                        null,
                        "Origin",
                        OidcStubConfiguration.PUBLIC_BASE_URL);

        /*
         * `Origin`은 통과했고 CSRF 토큰이 없어 막힌다. 두 검사가 한 쌍으로 걸려 있다는 증거다.
         *
         * 403 하나다. CSRF 실패는 인증 여부와 무관하게 같은 코드다. 미인증을 401로 갈라 두면 그
         * 차이가 곧 "이 브라우저에 세션이 있는가"를 알려 주는 신호가 되고, 화면이 할 일은 어느
         * 쪽이든 같다(인증 화면으로 되돌린다). 인증된 세션의 같은 실패는 아래 시험이 같은 값으로
         * 고정한다.
         */
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(response.body()).contains("\"correlationId\"");
    }

    @Test
    void 인증된_상태_변경_요청도_CSRF_토큰_없이는_거절한다() throws Exception {
        HttpResponse<String> login = signIn("csrf-user-" + UUID.randomUUID());
        String cookie = sessionCookieOf(login).orElseThrow();

        HttpResponse<String> response =
                send(
                        "POST",
                        AuthSecurityConfig.SESSION_PATH,
                        cookie,
                        "Origin",
                        OidcStubConfiguration.PUBLIC_BASE_URL);

        // 세션이 있어도 세션 귀속 CSRF 토큰이 없으면 상태 변경은 성립하지 않는다.
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
    }

    @Test
    void CSRF_검사는_인증_경로_밖의_오류_계약을_바꾸지_않는다() throws Exception {
        // Story 1.2가 고정한 응답이다. CSRF를 전역으로 켜면 이 405가 403으로 뒤집힌다.
        HttpResponse<String> response = send("POST", AuthSecurityConfig.STATUS_PATH, null);

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.body()).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
    }

    /**
     * 저장된 세션 속성 전부를 텍스트로.
     *
     * <p>바이트를 그대로 받아 와 Java에서 옮긴다. 데이터베이스에서 {@code convert_from(…, 'LATIN1')}
     * 으로 옮기려던 초안은 실패했다 — Java 직렬화 바이트에는 {@code 0x00}이 들어 있고 PostgreSQL의
     * {@code text}는 어떤 인코딩에서도 NUL을 담을 수 없어 {@code invalid byte sequence}로 죽는다.
     * "LATIN1은 256개 바이트 값을 모두 받는다"는 문자 집합의 성질이지만 NUL 금지는 그와 별개인
     * 저장 계층의 제약이다.
     *
     * <p>{@code ISO_8859_1}은 그 제약이 없고 바이트 하나를 코드 포인트 하나로 사상하므로 손실이
     * 없다. 그 안의 ASCII 조각(claim 이름·식별자)이 그대로 검색된다.
     */
    private String storedSessionAttributes() {
        List<byte[]> rows =
                jdbc().sql("SELECT attribute_bytes FROM spring_session_attributes")
                        .query(byte[].class)
                        .list();
        StringBuilder joined = new StringBuilder();
        for (byte[] row : rows) {
            joined.append(new String(row, StandardCharsets.ISO_8859_1)).append(' ');
        }
        return joined.toString();
    }

    /**
     * JSON 객체의 최상위 필드 이름을 나온 순서대로.
     *
     * <p>본문 전문 비교를 대체한다. 토큰 값이 실행마다 달라 전문을 고정할 수 없게 됐지만, 고정하려던
     * 것은 값이 아니라 <b>무엇이 나가는가</b>였다. 필드 이름 목록이 그 계약이며, 열지 않은 필드가
     * 하나라도 늘면 이 목록이 달라진다.
     *
     * <p>{@code AuthFlowTestBase}가 본문을 파싱한다. 정규식으로 {@code "이름":}을 찾던 초안은 본문
     * 어디에 있는 이름이든 잡았고, 그러면 값이 중첩 객체 안으로 옮겨지는 변경이 이 목록을 그대로
     * 두어 "필드가 하나라도 늘면 걸린다"는 계약이 조용히 약해진다.
     */
    private static List<String> fieldNamesOf(String json) {
        return new ArrayList<>(topLevelFieldsOf(json).keySet());
    }

    /** 세션 행의 유휴 수명(초). 이 시험들은 행이 하나뿐인 상태에서만 읽는다. */
    private int maxInactiveInterval() {
        return jdbc()
                .sql("SELECT max(max_inactive_interval) FROM spring_session")
                .query(Integer.class)
                .single();
    }
}
