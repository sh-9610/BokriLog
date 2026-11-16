package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.DevOidcStub.TokenPlan;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * 재인증과 세션 회전.
 *
 * <p>AC-1~AC-6의 단일 소유자다. 재인증이 더하는 것은 판정 두 개뿐이므로
 * ({@code ReauthenticationFlow}) 여기서 보는 축도 그 둘과 그 둘을 감싼 경계다 — 무장·소비·목적지,
 * 식별자 귀속, {@code auth_time} 신선도, 그리고 시작 요청의 보호.
 *
 * <p>{@code AuthTokenValidationTest}의 18축을 복제하지 않는다. 재인증은 <b>같은</b>
 * {@code oauth2Login} 왕복을 지나므로 PKCE·{@code state}·{@code nonce}·JWS·{@code iss}·
 * {@code aud}·{@code azp}·{@code exp} 검증은 그 파일이 이미 고정한 것과 같은 검증기가 한다. 여기서
 * 다시 보면 같은 것을 두 번 세는 시험이 열여덟 개 늘어난다.
 *
 * <p>모든 실패 축이 계정·매핑 행 수를 함께 단정한다. 재인증의 가장 큰 위험은 성공 핸들러가
 * 로그인 분기를 타는 것이고, 그때 눈에 보이는 결과는 "실패했는데 계정이 하나 늘었다"이기
 * 때문이다.
 */
class AuthReauthenticationTest extends AuthFlowTestBase {

    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    private static final String NAVER = AuthSecurityConfig.NAVER_REGISTRATION_ID;

    private static final String KAKAO = AuthSecurityConfig.KAKAO_REGISTRATION_ID;

    /** 이 시험이 도는 제공자 전부. 축을 늘리는 대신 같은 축이 이 목록을 돈다. */
    private static final List<String> PROVIDERS = List.of(GOOGLE, NAVER, KAKAO);

    /** 배선된 성공 핸들러. HTTP 왕복으로 만들 수 없는 만료 경합을 직접 부르는 시험 하나가 쓴다. */
    @Autowired
    private AuthFlowHandlers handlers;

    /**
     * 제공자별로 <b>반드시 실려야 하는</b> 재인증 인가 파라미터.
     *
     * <p>공표된 수단만 보낸다는 계약이 이 표 하나다. Google은 {@code claims}의 essential
     * {@code auth_time}, 카카오는 {@code prompt=login}, 네이버는 공표한 추가 수단이 없어 공통
     * {@code max_age}뿐이다. 공통 {@code max_age}는 셋 모두가 받는다.
     */
    private static final Map<String, Map<String, String>> EXPECTED_REAUTH_PARAMETERS =
            Map.of(
                    GOOGLE,
                            Map.of(
                                    "max_age",
                                    "300",
                                    "claims",
                                    "{\"id_token\":{\"auth_time\":{\"essential\":true}}}"),
                    NAVER, Map.of("max_age", "300"),
                    KAKAO, Map.of("max_age", "300", "prompt", "login"));

    // ----------------------------------------------------------------------
    // 회복과 자발 재인증
    // ----------------------------------------------------------------------

    @Test
    void 표시된_세션만_재인증_요구와_토큰을_함께_받는다() throws Exception {
        String subject = "reauth-marked-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(cookie))).isTrue();

        HttpResponse<String> marked = sessionState(cookie);

        assertThat(marked.statusCode()).isEqualTo(200);
        /*
         * 표시는 미인증 응답에 필드 하나를 더한다. Story 1.5가 "표시와 폐기는 응답으로 구분되지
         * 않는다"로 고정한 자리를 이 스토리가 의도적으로 되짚는 지점이며, 구분하지 않으면 표시된
         * 세션이 토큰을 받지 못해 회복 경로가 없다.
         */
        assertThat(topLevelFieldsOf(marked.body()).keySet())
                .containsExactly("authenticated", "destination", "reauthRequired", "csrfToken");
        assertThat(marked.body()).contains("\"authenticated\":false");
        assertThat(marked.body()).contains("\"reauthRequired\":true");
        // 목적지는 여전히 종류 하나이고 표시된 세션에서는 인증 화면이다. 경로가 새지 않는다.
        assertThat(marked.body()).contains("\"destination\":\"entry\"");
        assertThat(csrfTokenIn(marked.body())).isNotBlank();
    }

    @Test
    void 폐기된_세션과_미인증_요청의_본문은_그대로다() throws Exception {
        String subject = "reauth-revoked-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        assertThat(sessionGuard().revokeSession(sessionIdOf(cookie))).isTrue();

        /*
         * 두 본문이 글자까지 같아야 한다. 폐기와 미인증에는 회복 경로가 없으므로 구분할 이유도
         * 없고, 여기에 필드가 하나 늘면 Story 1.4가 고정한 전문 동등성이 두 계층에서 함께
         * 깨진다.
         */
        assertThat(sessionState(cookie).body())
                .isEqualTo("{\"authenticated\":false,\"destination\":\"entry\"}");
        assertThat(sessionState(null).body())
                .isEqualTo("{\"authenticated\":false,\"destination\":\"entry\"}");
    }

    @Test
    void 표시된_세션이_재인증으로_되살아난다() throws Exception {
        String subject = "reauth-recover-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(cookie))).isTrue();
        String token = csrfTokenOf(cookie);

        HttpResponse<String> callback = reauthenticate(GOOGLE, cookie, token, sameSubject(subject));

        assertThat(callback.statusCode()).isEqualTo(302);
        // 붙들어 둔 목적지로 돌아간다. 표지는 붙지 않는다 — 성공이므로 할 말이 없다.
        assertThat(locationOf(callback)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);

        // 세션 ID가 회전했다. 로그인과 같은 배선이 콜백에서 이미 했다.
        String rotated = sessionCookieOf(callback).orElseThrow();
        assertThat(rotated).isNotEqualTo(cookie);
        // 회전 전 쿠키로는 아무것도 아니다. 행은 하나뿐이므로 두 세션이 남지 않았다.
        assertThat(sessionState(cookie).body())
                .isEqualTo("{\"authenticated\":false,\"destination\":\"entry\"}");
        assertThat(sessionCountOf(userId)).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);

        // 표시가 사라졌으므로 이 세션은 다시 인증 상태로 답한다.
        HttpResponse<String> after = sessionState(rotated);
        assertThat(after.body()).contains("\"authenticated\":true");
        assertThat(after.body()).doesNotContain("reauthRequired");
        assertThat(after.body()).contains("\"destination\":\"onboarding\"");

        String rotatedId = sessionIdOf(rotated);
        assertThat(sessionAttribute(rotatedId, SessionGuard.REAUTH_REQUIRED_ATTRIBUTE)).isNull();
        // 흐름은 소비됐다. 남겨 두면 다음 콜백이 같은 무장을 다시 쓴다.
        assertThat(sessionAttribute(rotatedId, ReauthenticationFlow.FLOW_ATTRIBUTE)).isNull();
        // 이 스토리가 남기는 유일한 결과다. HTTP로 나가지 않으며 1.9가 소비한다.
        assertThat(sessionAttribute(rotatedId, ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                .isInstanceOf(Instant.class);

        // 계정도 매핑도 늘지 않았다. 재인증은 계정을 만들지도 바꾸지도 않는다.
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(userIdOf(OidcStubConfiguration.issuer(), subject)).contains(userId);
    }

    @Test
    void 표시되지_않은_세션의_자발_재인증도_같은_결과다() throws Exception {
        String subject = "reauth-voluntary-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        HttpResponse<String> callback =
                reauthenticate(GOOGLE, cookie, csrfTokenOf(cookie), sameSubject(subject));

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        String rotated = sessionCookieOf(callback).orElseThrow();
        // 표시가 없었을 뿐 회전과 기록은 같다.
        assertThat(rotated).isNotEqualTo(cookie);
        assertThat(sessionAttribute(
                        sessionIdOf(rotated), ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                .isInstanceOf(Instant.class);
        assertThat(sessionCountOf(userId)).isEqualTo(1);
        assertThat(accountCount()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------
    // 식별자 귀속
    // ----------------------------------------------------------------------

    @Test
    void 타_사용자_식별자는_재인증에_실패한다() throws Exception {
        String mine = "reauth-mine-" + UUID.randomUUID();
        String theirs = "reauth-theirs-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(mine)).orElseThrow();
        UUID myUserId = userIdOf(OidcStubConfiguration.issuer(), mine).orElseThrow();
        sessionCookieOf(signIn(theirs)).orElseThrow();
        UUID theirUserId = userIdOf(OidcStubConfiguration.issuer(), theirs).orElseThrow();

        HttpResponse<String> callback =
                reauthenticate(GOOGLE, cookie, csrfTokenOf(cookie), sameSubject(theirs));

        assertReauthenticationFailed(callback);
        // 계정·매핑·세션이 모두 그대로다. 두 사용자의 어느 쪽도 갈아타지 않았다.
        assertThat(accountCount()).isEqualTo(2);
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(sessionCountOf(theirUserId)).isEqualTo(1);
        // 이 세션은 여전히 내 것이다. 실패한 재인증이 주체를 바꾸지 않았다.
        assertRotatedSessionSurvived(callback, cookie, myUserId);
    }

    @Test
    void 미연결_식별자는_계정을_만들지_않고_같은_실패다() throws Exception {
        String subject = "reauth-linked-" + UUID.randomUUID();
        String stranger = "reauth-stranger-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        HttpResponse<String> callback =
                reauthenticate(GOOGLE, cookie, csrfTokenOf(cookie), sameSubject(stranger));

        assertReauthenticationFailed(callback);
        /*
         * 여기가 이 스토리에서 가장 조용한 결함의 자리다. 성공 핸들러가 로그인 분기를 타면 이
         * 식별자는 "미연결"이므로 **신규 가입**이 되고, 계정이 하나 늘면서 이 세션이 그 계정으로
         * 갈아탄다. 행 수 두 줄이 그것을 붙잡는다.
         */
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(userIdOf(OidcStubConfiguration.issuer(), stranger)).isEmpty();
        assertRotatedSessionSurvived(callback, cookie, userId);
    }

    @Test
    void 삭제가_시작된_계정은_재인증에_실패한다() throws Exception {
        String subject = "reauth-deleting-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String token = csrfTokenOf(cookie);

        // 흐름 도중에 전환한다. 무장은 삭제 전에, 판정은 삭제 뒤에 일어난다.
        assertThat(startReauthentication(GOOGLE, cookie, token).statusCode()).isEqualTo(204);
        Authorization authorization = startAuthorization(GOOGLE, null, cookie);
        accountDeletion().beginDeletion(userId);
        String code =
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(subject));

        HttpResponse<String> callback = callback(authorization, cookie, code, authorization.state());

        // `AccessDecision.DENIED` 하나이며 다른 실패와 구분되지 않는다.
        assertReauthenticationFailed(callback);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertRotatedSessionSurvived(callback, cookie, userId);
    }

    // ----------------------------------------------------------------------
    // 최근 인증 증거
    // ----------------------------------------------------------------------

    @Test
    void auth_time이_없으면_완화하지_않고_실패한다() throws Exception {
        assertFreshnessAxisFails(TokenPlan::omitAuthTime);
    }

    @Test
    void 신선도_창을_넘긴_auth_time은_실패한다() throws Exception {
        // 창 5분 + skew 60초 밖이다. 값 하나만 어긋나게 하고 나머지는 정상 계획이다.
        assertFreshnessAxisFails(plan -> plan.authTime(Instant.now().minus(Duration.ofMinutes(10))));
    }

    @Test
    void 허용_skew_밖의_미래_auth_time은_실패한다() throws Exception {
        assertFreshnessAxisFails(plan -> plan.authTime(Instant.now().plus(Duration.ofMinutes(10))));
    }

    @Test
    void 창_안의_auth_time은_통과한다() throws Exception {
        String subject = "reauth-fresh-edge-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();

        /*
         * 창의 안쪽 가장자리다. 이 축이 없으면 "전부 실패"로도 위 세 시험이 통과하고, 그때 재인증
         * 성공 경로는 아무도 보지 않는 코드가 된다.
         */
        HttpResponse<String> callback =
                reauthenticate(
                        GOOGLE,
                        cookie,
                        csrfTokenOf(cookie),
                        plan ->
                                plan.subject(subject)
                                        .authTime(Instant.now().minus(Duration.ofMinutes(4))));

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
    }

    // ----------------------------------------------------------------------
    // 흐름의 경계
    // ----------------------------------------------------------------------

    @Test
    void 사용자가_취소하면_흐름만_버린다() throws Exception {
        String subject = "reauth-cancel-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(startReauthentication(GOOGLE, cookie, csrfTokenOf(cookie)).statusCode())
                .isEqualTo(204);
        Authorization authorization = startAuthorization(GOOGLE, null, cookie);

        HttpResponse<String> callback =
                callbackWithError(GOOGLE, cookie, "access_denied", authorization.state());

        assertThat(callback.statusCode()).isEqualTo(302);
        // 취소 표지를 달고 붙들어 둔 목적지로 돌아간다. 인증 화면이 아니다 — 이 사용자는
        // 로그인한 상태 그대로다.
        assertThat(locationOf(callback))
                .endsWith(
                        AuthFlowHandlers.ONBOARDING_PATH
                                + "?auth="
                                + AuthFlowHandlers.FAILURE_MARKER_REAUTH_CANCELLED);
        // 세션도 표시도 무변경이다. 회전조차 일어나지 않았다.
        assertThat(sessionCookieOf(callback)).isEmpty();
        assertSessionSurvived(callback, cookie, userId);
        // 흐름만 사라졌다. 자동 재시도가 없으므로 다음 재인증은 사용자가 다시 시작해야 한다.
        assertThat(sessionAttribute(sessionIdOf(cookie), ReauthenticationFlow.FLOW_ATTRIBUTE))
                .isNull();
    }

    @Test
    void 취소가_아닌_제공자_오류는_실패_표지로_수렴한다() throws Exception {
        String subject = "reauth-provider-error-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(startReauthentication(GOOGLE, cookie, csrfTokenOf(cookie)).statusCode())
                .isEqualTo(204);
        Authorization authorization = startAuthorization(GOOGLE, null, cookie);

        HttpResponse<String> callback =
                callbackWithError(GOOGLE, cookie, "server_error", authorization.state());

        /*
         * 취소만 갈라진다. 사용자가 스스로 그만둔 것과 제공자가 오류로 되돌린 것은 다음 행동이
         * 같지만 무엇이 일어났는지는 다르고, 그 둘이 한 값으로 접히면 화면이 "다시 시도할 수
         * 있습니다"를 제공자 장애에도 말한다. 이 축이 없으면 표지 판정이 언제나 취소를 돌려주게
         * 바뀌어도 아무 시험이 실패하지 않는다.
         */
        assertReauthenticationFailed(callback);
        assertThat(locationOf(callback))
                .doesNotContain(AuthFlowHandlers.FAILURE_MARKER_REAUTH_CANCELLED);
        // 인증이 확정되기 전에 끝난 실패다. 회전도 상태 변경도 없다.
        assertThat(sessionCookieOf(callback)).isEmpty();
        assertSessionSurvived(callback, cookie, userId);
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void 제공자가_보낸_invalid_request는_위조_콜백_가드에_걸리지_않고_무장을_소비한다() throws Exception {
        /*
         * 위조 콜백 가드(Epic 1 회고 F2)는 `invalid_request`를 요청이 인가 응답 모양이 아닐 때만 받는다. 제공자가 유효한
         * `state`와 함께 되돌린 `error=invalid_request`는 이 세션의 인가 요청을 소비한 제공자 오류이므로 지금처럼 무장을
         * 소비하고 실패 표지로 수렴해야 한다 — code만 보는 가드였다면 무장이 남고 표지 없이 되돌아간다.
         */
        String subject = "reauth-provider-invalid-request-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(startReauthentication(GOOGLE, cookie, csrfTokenOf(cookie)).statusCode())
                .isEqualTo(204);
        Authorization authorization = startAuthorization(GOOGLE, null, cookie);

        HttpResponse<String> callback =
                callbackWithError(GOOGLE, cookie, "invalid_request", authorization.state());

        assertReauthenticationFailed(callback);
        assertThat(sessionCookieOf(callback)).isEmpty();
        assertSessionSurvived(callback, cookie, userId);
        assertThat(sessionAttribute(sessionIdOf(cookie), ReauthenticationFlow.FLOW_ATTRIBUTE))
                .isNull();
    }

    @Test
    void 무장이_만료된_뒤_도착한_콜백은_이유와_함께_수렴한다() throws Exception {
        String subject = "reauth-expired-arming-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(startReauthentication(GOOGLE, cookie, csrfTokenOf(cookie)).statusCode())
                .isEqualTo(204);
        Authorization authorization = startAuthorization(GOOGLE, null, cookie);
        String code =
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(subject));

        /*
         * 사용자가 제공자 화면에서 무장 수명을 넘겼다. 시계 대신 저장소의 무장 시각을 옮긴다 —
         * 5분을 기다릴 수 없고, 수명을 줄이는 설정을 따로 두면 그 값이 제품의 값과 갈린다.
         */
        ageReauthenticationFlow(
                sessionIdOf(cookie), ReauthenticationFlow.ARMED_LIFETIME.plusMinutes(1));

        HttpResponse<String> callback = callback(authorization, cookie, code, authorization.state());

        /*
         * "무장이 없었다"와 "무장이 있었는데 늦었다"가 갈린다. 앞의 것은 이 세션의 사실이 아닌
         * 콜백이라 조용히 목적지로 되돌리고, 뒤의 것은 이유를 말해야 한다 — 말하지 않으면 늦게
         * 돌아온 사용자가 아무 설명 없이 같은 패널로 튕긴다.
         */
        assertReauthenticationFailed(callback);
        // 검증에 넘기지 않았으므로 회전도 없다. 세션은 그대로다.
        assertThat(sessionCookieOf(callback)).isEmpty();
        assertSessionSurvived(callback, cookie, userId);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        // 만료된 무장은 그 자리에서 버려진다. 다음 콜백이 같은 판정을 다시 하지 않는다.
        assertThat(sessionAttribute(sessionIdOf(cookie), ReauthenticationFlow.FLOW_ATTRIBUTE))
                .isNull();
    }

    @Test
    void 무장과_다른_제공자의_인증_시작은_거절된다() throws Exception {
        String subject = "reauth-start-swap-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        assertThat(startReauthentication(KAKAO, cookie, csrfTokenOf(cookie)).statusCode())
                .isEqualTo(204);

        Authorization google = startAuthorization(GOOGLE, null, cookie);

        assertThat(google.status()).isEqualTo(302);
        // 제공자가 아니라 자기 목적지로 되돌린다. `state`도 발급되지 않았다.
        assertThat(google.location()).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(google.state()).isEmpty();
    }

    @Test
    void 무장과_다른_제공자의_콜백은_실패한다() throws Exception {
        String subject = "reauth-callback-swap-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        // 카카오로 무장하고 카카오 흐름을 연 뒤, 콜백이 오기 전에 google로 다시 무장한다.
        assertThat(startReauthentication(KAKAO, cookie, csrfTokenOf(cookie)).statusCode())
                .isEqualTo(204);
        Authorization kakao = startAuthorization(KAKAO, null, cookie);
        assertThat(startReauthentication(GOOGLE, cookie, csrfTokenOf(cookie)).statusCode())
                .isEqualTo(204);
        String code = issueCode(kakao, TokenPlan.valid(kakao.nonce()).subject(subject));

        HttpResponse<String> callback = callback(kakao, cookie, code, kakao.state());

        // 무장이 google인데 kakao 콜백이다. 판정할 것도 없이 실패다.
        assertReauthenticationFailed(callback);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertRotatedSessionSurvived(callback, cookie, userId);
    }

    @Test
    void 흐름은_일회성이라_두_번째_콜백이_아무것도_바꾸지_않는다() throws Exception {
        String subject = "reauth-replay-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(startReauthentication(GOOGLE, cookie, csrfTokenOf(cookie)).statusCode())
                .isEqualTo(204);
        Authorization authorization = startAuthorization(GOOGLE, null, cookie);
        String code =
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(subject));

        HttpResponse<String> first = callback(authorization, cookie, code, authorization.state());
        assertThat(first.statusCode()).isEqualTo(302);
        String rotated = sessionCookieOf(first).orElseThrow();

        HttpResponse<String> second =
                callback(authorization, rotated, code, authorization.state());

        /*
         * 무장이 소비됐으므로 이 콜백은 이 세션의 사실이 아니다. 인증 흐름 경계가 검증에 넘기지
         * 않고 지금 목적지로 되돌린다 — 넘기면 성공 핸들러의 **로그인 분기**가 같은 세션 행에
         * 주체를 다시 심는다.
         */
        assertThat(second.statusCode()).isEqualTo(302);
        assertThat(locationOf(second)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(sessionCookieOf(second)).isEmpty();
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertSessionSurvived(second, rotated, userId);
    }

    @Test
    void 무장_없는_콜백은_인증된_세션을_건드리지_않는다() throws Exception {
        String subject = "reauth-bare-callback-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        HttpResponse<String> callback =
                callback(GOOGLE, cookie, "bare-code", "bare-state");

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertSessionSurvived(callback, cookie, userId);
    }

    @Test
    void 위조_콜백은_살아_있는_재인증_무장을_소비하지_않는다() throws Exception {
        /*
         * I/O 표 "F2 위조 콜백(재인증)"(Epic 1 회고 F2). 살아 있는 무장이 있으면 흐름 경계가 콜백을 검증에
         * 넘기고, 이 세션의 인가 요청에 닿지 못한 검증은 실패 핸들러로 온다. 그 실패가 무장을 소비하면 외부
         * 사이트가 이것을 반복하는 것만으로 이 사용자의 재인증을 계속 거부할 수 있다.
         */
        String subject = "reauth-forged-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(startReauthentication(GOOGLE, cookie, csrfTokenOf(cookie)).statusCode())
                .isEqualTo(204);
        Authorization authorization = startAuthorization(GOOGLE, null, cookie);
        String sessionId = sessionIdOf(cookie);
        String token = storedCsrfToken(sessionId).getToken();
        Object armedBefore = sessionAttribute(sessionId, ReauthenticationFlow.FLOW_ATTRIBUTE);
        assertThat(armedBefore)
                .isInstanceOfSatisfying(
                        ReauthenticationFlow.Armed.class,
                        armed -> {
                            assertThat(armed.provider()).isEqualTo(GOOGLE);
                            assertThat(armed.userId()).isEqualTo(userId);
                            assertThat(armed.returnPath()).isEqualTo(AuthFlowHandlers.ONBOARDING_PATH);
                        });

        for (HttpResponse<String> forged : forgedCallbacks(GOOGLE, cookie)) {
            // "무장 없는 콜백"과 같은 302(지금 목적지)다. 표지·회전이 없다.
            assertThat(forged.statusCode()).isEqualTo(302);
            assertThat(locationOf(forged)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
            assertThat(locationOf(forged)).doesNotContain("auth=");
            assertThat(sessionCookieOf(forged)).isEmpty();
            assertSessionSurvived(forged, cookie, userId);
            assertThat(storedCsrfToken(sessionId).getToken()).isEqualTo(token);
            // 같은 무장이다 — 제공자·사용자·복귀 경로·무장 시각까지 그대로다(record 동등).
            assertThat(sessionAttribute(sessionId, ReauthenticationFlow.FLOW_ATTRIBUTE))
                    .isEqualTo(armedBefore);
            assertThat(sessionAttribute(sessionId, ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                    .isNull();
        }

        // 이어지는 정상 콜백이 그 무장과 인가 요청으로 재인증을 마친다.
        String code =
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(subject));
        HttpResponse<String> callback = callback(authorization, cookie, code, authorization.state());

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(locationOf(callback)).doesNotContain("auth=");
        String rotated = sessionCookieOf(callback).orElseThrow();
        assertThat(sessionAttribute(
                        sessionIdOf(rotated), ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                .isInstanceOf(Instant.class);
        assertThat(sessionCountOf(userId)).isEqualTo(1);
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void 흐름_없는_미인증_콜백의_판정은_그대로다() throws Exception {
        // Story 1.4·1.5의 회귀다. 인증된 세션의 콜백만 위에서 갈라졌고 나머지는 변하지 않았다.
        HttpResponse<String> callback = callback(GOOGLE, null, "no-flow-code", "no-flow-state");

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback))
                .endsWith("/?auth=" + AuthFlowHandlers.FAILURE_MARKER_FAILED);
        assertThat(sessionCount()).isZero();
        assertThat(accountCount()).isZero();
    }

    @Test
    void 흐름_도중_로그아웃하면_콜백이_아무것도_바꾸지_않는다() throws Exception {
        String subject = "reauth-logout-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        String token = csrfTokenOf(cookie);
        assertThat(startReauthentication(GOOGLE, cookie, token).statusCode()).isEqualTo(204);
        Authorization authorization = startAuthorization(GOOGLE, null, cookie);
        String code =
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(subject));

        // 세션 행이 사라지면 흐름도 함께 사라진다. 무장은 그 행의 속성 하나였다.
        assertThat(logout(cookie, token).statusCode()).isEqualTo(204);
        assertThat(sessionCount()).isZero();

        HttpResponse<String> callback = callback(authorization, cookie, code, authorization.state());

        // 기존 실패 경로로 수렴한다. `state`를 대조할 세션이 없다.
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback))
                .endsWith("/?auth=" + AuthFlowHandlers.FAILURE_MARKER_FAILED);
        assertThat(sessionCount()).isZero();
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 만료된_재인증_무장의_콜백은_성공_핸들러에서_재인증_실패로_접힌다() throws Exception {
        /*
         * 흐름 경계가 재인증 무장을 살아 있다고 보고 검증에 넘긴 뒤, 성공 핸들러 전에 그 무장이 만료된 상태다(deferred 191).
         * HTTP 왕복으로는 그 창을 만들 수 없으므로 배선된 성공 핸들러를 그 상태의 세션으로 직접 부른다 — 판정 함수만 보는
         * `AuthCallbackFlowSelectionTest`와 달리 여기서는 분기가 실제로 실행된다.
         *
         * 검증된 식별자는 어느 계정에도 연결되지 않은 것이다. 로그인 분기로 떨어지면 그 식별자로 새 계정이 생기고 세션이 그
         * 계정으로 갈아타므로, "계정 0건"과 "주체가 무장의 사용자"가 그 결함을 직접 본다.
         */
        UUID userId = UUID.randomUUID();
        ReauthenticationFlow.Armed expired =
                new ReauthenticationFlow.Armed(
                        GOOGLE,
                        userId,
                        AuthFlowHandlers.SETTINGS_PATH,
                        Instant.now().minus(ReauthenticationFlow.ARMED_LIFETIME).minusSeconds(1));
        assertThat(expired.isLive(Instant.now())).isFalse();

        OAuth2AuthenticationToken verified = verifiedGoogleToken();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ReauthenticationFlow.FLOW_ATTRIBUTE, expired);
        session.setAttribute(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE, Boolean.TRUE);
        // 검증 필터는 성공 핸들러를 부르기 전에 자기 인증 결과를 세션에 저장한다. 그 상태를 그대로 만든다.
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(verified));
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", AuthSecurityConfig.CALLBACK_BASE_URI + "/" + GOOGLE);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            this.handlers.successHandler().onAuthenticationSuccess(request, response, verified);
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(response.getRedirectedUrl())
                .isEqualTo(
                        AuthFlowHandlers.SETTINGS_PATH
                                + "?"
                                + AuthFlowHandlers.FAILURE_MARKER_PARAMETER
                                + "="
                                + AuthFlowHandlers.FAILURE_MARKER_REAUTH_FAILED);
        // 재인증이 성립하지 않았다. 시각이 없고 표시도 그대로다.
        assertThat(ReauthenticationFlow.lastAuthenticatedAt(session)).isNull();
        assertThat(SessionGuard.isMarked(session.getAttribute(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE))).isTrue();
        // 저장된 주체는 검증 결과(ID Token)가 아니라 무장의 사용자 하나다.
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isInstanceOfSatisfying(
                        SecurityContext.class,
                        stored ->
                                assertThat(stored.getAuthentication())
                                        .isInstanceOfSatisfying(
                                                PreAuthenticatedAuthenticationToken.class,
                                                principal ->
                                                        assertThat(principal.getName())
                                                                .isEqualTo(userId.toString())));
        // 만료 무장은 소비됐다. 같은 무장으로 다시 판정되지 않는다.
        assertThat(session.getAttribute(ReauthenticationFlow.FLOW_ATTRIBUTE)).isNull();
        // 로그인 분기로 가지 않았다. 계정도 매핑도 생기지 않았다.
        assertThat(accountCount()).isZero();
        assertThat(socialLinkCount()).isZero();
    }

    /** 검증 필터가 성공 핸들러에 넘기는 모양의 인증 결과. 어느 계정에도 연결되지 않은 Google 식별자이고 방금 인증했다. */
    private static OAuth2AuthenticationToken verifiedGoogleToken() {
        Instant now = Instant.now();
        OidcIdToken idToken =
                new OidcIdToken(
                        UUID.randomUUID().toString(),
                        now,
                        now.plus(Duration.ofMinutes(5)),
                        Map.of(
                                IdTokenClaimNames.ISS, OidcStubConfiguration.issuer(),
                                IdTokenClaimNames.SUB, "reauth-expired-race-" + UUID.randomUUID(),
                                IdTokenClaimNames.AUTH_TIME, now));
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, GOOGLE);
    }

    // ----------------------------------------------------------------------
    // 시작 요청의 보호
    // ----------------------------------------------------------------------

    @Test
    void 미인증_재인증_시작은_403이다() throws Exception {
        HttpResponse<String> response =
                startReauthentication(GOOGLE, null, "anonymous-marker-token");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        // 세션 귀속 토큰이 없으므로 CSRF 단계에서 수렴한다. 세션 행이 생기지 않는다.
        assertThat(sessionCount()).isZero();
    }

    @Test
    void 토큰_없는_재인증_시작은_403이고_세션이_살아_있다() throws Exception {
        String subject = "reauth-no-token-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        HttpResponse<String> response = startReauthentication(GOOGLE, cookie, null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertSessionSurvived(response, cookie, userId);
        assertThat(sessionAttribute(sessionIdOf(cookie), ReauthenticationFlow.FLOW_ATTRIBUTE))
                .isNull();
    }

    @Test
    void 교차_출처_재인증_시작은_403이다() throws Exception {
        String subject = "reauth-cross-origin-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();

        HttpResponse<String> response =
                startReauthentication(GOOGLE, cookie, csrfTokenOf(cookie), "https://evil.example");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(sessionAttribute(sessionIdOf(cookie), ReauthenticationFlow.FLOW_ATTRIBUTE))
                .isNull();
    }

    @Test
    void 미지_제공자의_재인증_시작은_404이고_이름을_되비추지_않는다() throws Exception {
        String subject = "reauth-unknown-provider-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();

        HttpResponse<String> response =
                startReauthentication("nosuchprovider", cookie, csrfTokenOf(cookie));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
        // 아는 제공자 이름도 개수도 응답에 실리지 않는다.
        assertThat(response.body()).doesNotContain("nosuchprovider");
        for (String provider : PROVIDERS) {
            assertThat(response.body()).as("%s 이름 노출", provider).doesNotContain(provider);
        }
        assertThat(sessionAttribute(sessionIdOf(cookie), ReauthenticationFlow.FLOW_ATTRIBUTE))
                .isNull();
    }

    @Test
    void 표시된_세션은_재인증_시작만_통과시키고_다른_상태_변경은_403이다() throws Exception {
        String subject = "reauth-marked-guard-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(cookie))).isTrue();
        String token = csrfTokenOf(cookie);
        assertThat(token).isNotNull();

        // 표시된 세션에도 열리는 상태 변경은 로그아웃과 재인증 시작 둘뿐이다.
        assertThat(startReauthentication(GOOGLE, cookie, token).statusCode()).isEqualTo(204);
        HttpResponse<String> other =
                send(
                        "POST",
                        AuthSecurityConfig.SESSION_PATH,
                        cookie,
                        "Origin",
                        OidcStubConfiguration.PUBLIC_BASE_URL,
                        AuthSecurityConfig.CSRF_HEADER_NAME,
                        token);
        assertThat(other.statusCode()).isEqualTo(403);
        assertThat(other.body()).contains("\"code\":\"FORBIDDEN\"");
    }

    // ----------------------------------------------------------------------
    // 제공자별 인가 파라미터
    // ----------------------------------------------------------------------

    @Test
    void 제공자마다_공표한_재인증_파라미터를_보낸다() throws Exception {
        for (String provider : PROVIDERS) {
            String subject = "reauth-params-" + provider + "-" + UUID.randomUUID();
            String cookie = sessionCookieOf(signIn(provider, subject)).orElseThrow();
            assertThat(startReauthentication(provider, cookie, csrfTokenOf(cookie)).statusCode())
                    .as("%s 무장", provider)
                    .isEqualTo(204);

            Authorization authorization = startAuthorization(provider, null, cookie);

            assertThat(authorization.status()).as("%s 인가 시작 상태", provider).isEqualTo(302);
            /*
             * 제공자 자리에 실제로 도착한 값을 본다.
             *
             * `Location`을 파싱하면 우리가 만든 URL을 다시 읽는 것이고, 그것은 조립 단계까지만
             * 확인한다. 인가 요청을 스텁의 동의 경로에 그대로 넘기면 그 인스턴스가 받은 값이
             * 기록되고, 이 단정이 "실제로 보냈는가"를 본다. 인스턴스당 마지막 요청 하나이므로
             * 인가 요청 직후에 읽는다(`DevOidcStub`).
             */
            assertThat(OidcStubConfiguration.consent(provider, authorization.parameters()))
                    .as("%s 동의 경로", provider)
                    .isNotNull();
            Map<String, String> received =
                    OidcStubConfiguration.reauthenticationParametersOf(provider);

            Map<String, String> expected = EXPECTED_REAUTH_PARAMETERS.get(provider);
            assertThat(received).as("%s 재인증 인가 파라미터", provider).isEqualTo(expected);
            /*
             * 전문 비교다. 공표하지 않은 수단은 보내지 않는다 — Google 문서의 `prompt` 목록에
             * `login`이 없고 네이버는 어떤 추가 수단도 공표하지 않았다. 없는 값을 보내는 것은
             * 제공자가 정의하지 않은 동작에 기대는 것이고, 포함 검사로 두면 그 추가가 통과한다.
             */
            // 기존 검증 재료가 그대로 실린다. 파라미터를 덮어썼다면 여기서 먼저 걸린다.
            assertThat(authorization.nonce()).as("%s nonce", provider).isNotBlank();
            assertThat(authorization.state()).as("%s state", provider).isNotBlank();
            assertThat(authorization.location())
                    .as("%s PKCE", provider)
                    .contains("code_challenge_method=S256");
        }
    }

    @Test
    void 일반_로그인에는_재인증_파라미터를_얹지_않는다() throws Exception {
        for (String provider : PROVIDERS) {
            Authorization authorization = startAuthorization(provider, null, null);

            assertThat(OidcStubConfiguration.consent(provider, authorization.parameters()))
                    .as("%s 동의 경로", provider)
                    .isNotNull();

            /*
             * 상시로 얹으면 일반 로그인이 매번 재인증을 요구하고, 카카오의 `prompt=login`은 이미
             * 로그인한 사용자에게 매번 자격정보를 다시 묻는다. 얹는 판정은 무장된 흐름 하나다.
             *
             * 제공자 자리의 기록이 비어 있다는 것이 그 계약이다. 위 재인증 축과 같은 창을 보므로
             * 한쪽만 통과하는 상태가 생기지 않는다.
             */
            assertThat(OidcStubConfiguration.reauthenticationParametersOf(provider))
                    .as("%s 일반 로그인 인가 파라미터", provider)
                    .isEmpty();
        }
    }

    // ----------------------------------------------------------------------
    // 단정 도구
    // ----------------------------------------------------------------------

    /** 정상 계획에 이 사용자의 {@code subject}만 실어 준다. */
    private static UnaryOperator<TokenPlan> sameSubject(String subject) {
        return plan -> plan.subject(subject);
    }

    /**
     * 신선도 축 하나가 실패하는지 본다.
     *
     * <p>세 축({@code auth_time} 부재·초과·미래)이 같은 형태라 본체를 한 곳에 둔다. 다른 것은
     * 계획 하나뿐이고, 기대는 셋 다 같은 실패다.
     */
    private void assertFreshnessAxisFails(UnaryOperator<TokenPlan> axis) throws Exception {
        String subject = "reauth-freshness-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();

        HttpResponse<String> callback =
                reauthenticate(
                        GOOGLE, cookie, csrfTokenOf(cookie), plan -> axis.apply(plan.subject(subject)));

        assertReauthenticationFailed(callback);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertRotatedSessionSurvived(callback, cookie, userId);
    }

    /**
     * 재인증이 실패로 수렴했는가.
     *
     * <p>모든 실패가 같은 모양이다 — 붙들어 둔 목적지로 표지 하나를 달고 돌아가며, 그 표지는
     * 어느 갈래에서 실패했는지 말하지 않는다.
     */
    private static void assertReauthenticationFailed(HttpResponse<String> callback) {
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback))
                .endsWith(
                        AuthFlowHandlers.ONBOARDING_PATH
                                + "?auth="
                                + AuthFlowHandlers.FAILURE_MARKER_REAUTH_FAILED);
    }

    /**
     * 그 세션이 여전히 그 사용자의 인증된 세션 하나인가.
     *
     * <p>실패한 재인증이 세션을 건드리지 않았다는 단정의 본체다. 인증 여부만 보면 주체가 다른
     * 계정으로 갈아탄 상태가 통과하므로 그 사용자의 세션 행 수까지 본다 —
     * {@code principal_name}이 곧 세션이 결정하는 {@code user_id}이며 {@code SessionGuard}의
     * 사용자 단위 조작이 쓰는 색인이다. 다른 계정으로 갈아타면 이 수가 0이 된다.
     *
     * <p>응답이 새 쿠키를 주면 그것을 쓴다. 검증을 통과한 뒤에 거절된 재인증은 세션 ID가 이미
     * 회전한 상태다 — 세션 고정 방어가 성공 핸들러보다 앞에서 돌기 때문이고, 그 회전은 우리가
     * 취소할 수 있는 것이 아니다. 같은 302 응답이 새 쿠키를 함께 실어 보내므로 사용자는 로그인
     * 상태로 남고, 사라지는 것은 회전 전 식별자 하나뿐이다.
     */
    private void assertSessionSurvived(
            HttpResponse<String> callback, String cookie, UUID userId) throws Exception {
        String current = sessionCookieOf(callback).orElse(cookie);
        assertThat(sessionState(current).body()).contains("\"authenticated\":true");
        assertThat(sessionCountOf(userId)).isEqualTo(1);
    }

    /**
     * 검증을 통과한 뒤 거절된 재인증이 살아남았는가.
     *
     * <p>{@link #assertSessionSurvived}와 다른 점은 <b>회전을 요구한다</b>는 것이다. 세션 고정
     * 방어가 성공 핸들러보다 앞에서 돌므로 이 경로의 세션 ID는 이미 바뀌었고, 같은 302 응답이
     * 새 쿠키를 함께 실어 보내지 않으면 사용자는 그 자리에서 로그아웃된다 — 응답에 쿠키가 없는
     * 상태가 정확히 그 결함이다.
     *
     * <p>둘을 한 헬퍼로 두면 회전 여부 어느 쪽이든 받아들여 그 구분이 사라진다. 인증이 확정되기
     * 전에 끝난 실패(취소·{@code state} 불일치·무장 만료)는 회전하지 않으며 그 축은 위
     * {@link #assertSessionSurvived}와 {@code sessionCookieOf(...).isEmpty()}가 함께 고정한다.
     */
    private void assertRotatedSessionSurvived(
            HttpResponse<String> callback, String cookie, UUID userId) throws Exception {
        String rotated = sessionCookieOf(callback).orElseThrow();
        assertThat(rotated).isNotEqualTo(cookie);
        // 회전 전 쿠키는 죽었다. 행이 둘로 늘지 않았다는 뜻이기도 하다.
        assertThat(sessionState(cookie).body()).contains("\"authenticated\":false");
        assertThat(sessionState(rotated).body()).contains("\"authenticated\":true");
        assertThat(sessionCountOf(userId)).isEqualTo(1);
    }
}
