package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.DevOidcStub.TokenPlan;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;

/**
 * 새 로그인 수단을 현재 계정에 연결하기.
 *
 * <p>AC-2~AC-7의 단일 소유자다. 이 스토리가 1.8의 재인증과 1.9의 허가를 <b>처음으로 실제
 * 소비</b>하므로, 여기서 보는 축은 그 두 계약이 HTTP 층에서 성립하는가와 연결 자체의 경계다 —
 * 세 단계의 순서, 흐름 귀속, 한 트랜잭션 커밋, 그리고 하나로 접히는 거부다.
 *
 * <p><b>{@code identity}가 이미 고정한 축을 복제하지 않는다.</b> {@code (issuer, subject)} 전역
 * 유일과 계정당 제공자 하나는 {@code AccountAccessDecisionTest}가, 같은 식별자의 동시 연결은
 * {@code AccountConcurrencyTest}가 이미 본다. 여기서는 그 답이 HTTP·허가 층을 지나 <b>거부 하나</b>로
 * 수렴하는지만 본다.
 *
 * <p>{@code AuthTokenValidationTest}의 18축도 복제하지 않는다. 연결은 <b>같은</b>
 * {@code oauth2Login} 왕복을 지나므로 PKCE·{@code state}·{@code nonce}·JWS 검증은 그 파일이 고정한
 * 것과 같은 검증기가 한다.
 *
 * <p>모든 실패 축이 매핑 행 수와 허가의 소비 여부를 함께 단정한다. 연결의 가장 큰 위험은 두
 * 가지이고 둘 다 저장소에서만 보인다 — 성공 핸들러가 로그인 분기로 새어 <b>새 계정</b>이 생기는
 * 것과, 허가만 소비되고 매핑이 없는 <b>반쪽 상태</b>가 남는 것이다.
 */
class AuthLinkProviderTest extends AuthFlowTestBase {

    /** 구성되지 않은 제공자 축이 포트를 직접 세우는 데 쓴다. */
    @Autowired private ActionGrantService actionGrants;

    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    private static final String NAVER = AuthSecurityConfig.NAVER_REGISTRATION_ID;

    private static final String KAKAO = AuthSecurityConfig.KAKAO_REGISTRATION_ID;

    // ----------------------------------------------------------------------
    // 연결 상태 조회
    // ----------------------------------------------------------------------

    @Test
    void 연결_상태는_제공자_이름만_돌려준다() throws Exception {
        Linked user = signInAndReauthenticate("link-status");

        HttpResponse<String> status = linkStatus(user.cookie());

        assertThat(status.statusCode()).isEqualTo(200);
        // 본문 전문을 고정한다. 필드가 하나 늘면 그것이 곧 외부 식별자가 새어 나갈 자리다.
        assertThat(status.body()).isEqualTo("{\"providers\":[\"google\"]}");
        assertThat(status.body()).doesNotContain(user.subject());
        assertThat(status.body()).doesNotContain(OidcStubConfiguration.issuer());
        assertThat(status.body()).doesNotContain(user.userId().toString());
        // 계정의 사실이므로 캐시에 남기지 않는다.
        assertThat(status.headers().allValues("Cache-Control")).contains("no-store");
    }

    @Test
    void 미인증_연결_상태_조회는_403이다() throws Exception {
        HttpResponse<String> status = linkStatus(null);

        assertThat(status.statusCode()).isEqualTo(403);
        assertThat(status.body()).contains("\"code\":\"FORBIDDEN\"");
        // 아는 제공자 이름도 개수도 실리지 않는다.
        assertThat(status.body()).doesNotContain(GOOGLE);
    }

    @Test
    void 표시된_세션은_연결_상태를_보지_못한다() throws Exception {
        Linked user = signInAndReauthenticate("link-status-marked");
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(user.cookie()))).isTrue();

        assertThat(linkStatus(user.cookie()).statusCode()).isEqualTo(403);
    }

    // ----------------------------------------------------------------------
    // 정상 연결
    // ----------------------------------------------------------------------

    @Test
    void 재인증이_신선하면_연결이_한_트랜잭션으로_끝난다() throws Exception {
        Linked user = signInAndReauthenticate("link-happy");
        String naverSubject = "link-happy-naver-" + UUID.randomUUID();

        HttpResponse<String> callback =
                link(NAVER, user.cookie(), csrfTokenOf(user.cookie()), naverSubject);

        assertThat(callback.statusCode()).isEqualTo(302);
        /*
         * 붙들어 둔 목적지로 돌아가며 결과를 표지 하나로 말한다. 왕복 뒤의 문서는 새로 열리므로
         * "방금 연결했다"를 아는 값은 주소의 표지뿐이고, 그것이 없으면 화면은 시작만 알리고
         * 결과는 알리지 않는다.
         */
        assertThat(locationOf(callback))
                .endsWith(
                        AuthFlowHandlers.SETTINGS_PATH
                                + "?auth="
                                + AuthFlowHandlers.NOTICE_MARKER_LINK_SUCCEEDED);
        // 무엇을 연결했는지는 말하지 않는다.
        assertThat(locationOf(callback)).doesNotContain(NAVER);

        // 매핑이 둘이고 같은 계정이며 제공자가 다르다. 계정은 늘지 않았다.
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(linkedProvidersOf(user.userId())).containsExactlyInAnyOrder(GOOGLE, NAVER);
        assertThat(userIdOf(OidcStubConfiguration.issuer(NAVER), naverSubject))
                .contains(user.userId());

        // 허가는 정확히 한 번 소비됐다.
        assertThat(actionGrantCountOf(user.userId())).isEqualTo(1);
        assertThat(consumedGrantCount()).isEqualTo(1);

        /*
         * 세션이 살아 있고 새 쿠키로 인증 상태다.
         *
         * <b>두 번째 회전 자체는 이 층에서 관측되지 않는다.</b> 세션 고정 방어가 성공 핸들러
         * 직전에 한 번 회전시키고 허가 소비 성공이 한 번 더 요구하는데(`SECURITY-ACTION-1.0`),
         * Spring Session은 요청 끝에 최종 id 하나만 쓰고 `Set-Cookie`도 한 번만 나간다 — 밖에서
         * 보이는 것은 "쿠키가 바뀌었다" 하나이며 회전 횟수는 그 값에 남지 않는다(변이로
         * 확인했다: 두 번째 회전을 지워도 이 시험들이 전부 통과한다).
         *
         * 그래도 이 단정이 지키는 것이 있다 — 두 번째 회전이 세션을 깨뜨리지 않는다는 사실이다.
         * 그 호출이 던지거나 세션을 무효화하면 아래 세 줄이 곧바로 실패한다.
         */
        String rotated = sessionCookieOf(callback).orElseThrow();
        assertThat(rotated).isNotEqualTo(user.cookie());
        assertThat(sessionState(user.cookie()).body()).contains("\"authenticated\":false");
        assertThat(sessionState(rotated).body()).contains("\"authenticated\":true");
        assertThat(sessionCountOf(user.userId())).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);

        // 흐름은 소비됐다. 남겨 두면 다음 콜백이 같은 무장을 다시 쓴다.
        String rotatedId = sessionIdOf(rotated);
        assertThat(sessionAttribute(rotatedId, LinkFlow.FLOW_ATTRIBUTE)).isNull();
        /*
         * 나머지 세션 속성은 회전을 건너 살아남는다. 이 왕복에서 세션 ID가 두 번 바뀌므로
         * (고정 방어 + 허가 소비의 재회전) 그 둘 중 하나라도 속성을 잃으면 사용자는 목적지와
         * 재인증 기록을 잃은 채 로그인 상태로 남는다 — 다음 연결이 이유 없이 거부되는 모양이다.
         */
        assertThat(sessionAttribute(rotatedId, AuthFlowHandlers.DESTINATION_ATTRIBUTE))
                .isEqualTo(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(sessionAttribute(rotatedId, ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                .isInstanceOf(java.time.Instant.class);

        // 연결 상태 조회가 두 제공자를 답한다. 순서는 화면이 아는 고정 순서다.
        assertThat(linkStatus(rotated).body()).isEqualTo("{\"providers\":[\"google\",\"naver\"]}");
    }

    @Test
    void 연결한_새_수단으로_로그인하면_같은_계정이다() throws Exception {
        Linked user = signInAndReauthenticate("link-then-sign-in");
        String naverSubject = "link-then-sign-in-naver-" + UUID.randomUUID();
        assertThat(link(NAVER, user.cookie(), csrfTokenOf(user.cookie()), naverSubject).statusCode())
                .isEqualTo(302);

        HttpResponse<String> second = signIn(NAVER, naverSubject);

        assertThat(second.statusCode()).isEqualTo(302);
        // 기존 사용자이므로 온보딩이 아니라 기본 목적지다. 계정도 매핑도 늘지 않았다.
        assertThat(locationOf(second)).endsWith(AuthFlowHandlers.DASHBOARD_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(userIdOf(OidcStubConfiguration.issuer(NAVER), naverSubject))
                .contains(user.userId());
    }

    @Test
    void 계정_삭제가_두_매핑을_함께_지운다() throws Exception {
        Linked mine = signInAndReauthenticate("link-erase-mine");
        assertThat(
                        link(
                                        NAVER,
                                        mine.cookie(),
                                        csrfTokenOf(mine.cookie()),
                                        "link-erase-naver-" + UUID.randomUUID())
                                .statusCode())
                .isEqualTo(302);
        Linked other = signInAndReauthenticate("link-erase-other");

        accountDeletion().beginDeletion(mine.userId());
        accountDeletion().erasePayload(mine.userId());

        assertThat(linkedProvidersOf(mine.userId())).isEmpty();
        // 다른 사용자의 매핑은 그대로다.
        assertThat(linkedProvidersOf(other.userId())).containsExactly(GOOGLE);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------
    // 시작의 게이트
    // ----------------------------------------------------------------------

    @Test
    void 재인증이_없으면_무장하지_않는다() throws Exception {
        String subject = "link-no-reauth-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();

        HttpResponse<String> start = startLink(NAVER, cookie, csrfTokenOf(cookie));

        assertLinkStartDenied(start);
        // 허가도 흐름도 만들어지지 않았다.
        assertThat(actionGrantCount()).isZero();
        assertThat(sessionAttribute(sessionIdOf(cookie), LinkFlow.FLOW_ATTRIBUTE)).isNull();
    }

    @Test
    void 재인증이_창을_넘기면_무장하지_않는다() throws Exception {
        Linked user = signInAndReauthenticate("link-stale-reauth");
        /*
         * 발급 게이트의 과거 쪽 경계는 `ActionGrant.REAUTHENTICATION_WINDOW`이고 판정은
         * 데이터베이스가 한다. 창 너머로 옮기면 그 술어가 0행을 만든다.
         */
        ageReauthentication(
                sessionIdOf(user.cookie()),
                ActionGrant.REAUTHENTICATION_WINDOW.plusMinutes(1));

        HttpResponse<String> start = startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));

        assertLinkStartDenied(start);
        assertThat(actionGrantCount()).isZero();
        assertThat(sessionAttribute(sessionIdOf(user.cookie()), LinkFlow.FLOW_ATTRIBUTE)).isNull();
    }

    @Test
    void 이미_연결된_제공자는_무장하지_않는다() throws Exception {
        Linked user = signInAndReauthenticate("link-already");

        HttpResponse<String> start = startLink(GOOGLE, user.cookie(), csrfTokenOf(user.cookie()));

        // 재인증은 신선한데도 거부다. 게이트가 둘이라는 사실이 여기서 관측된다.
        assertLinkStartDenied(start);
        assertThat(actionGrantCount()).isZero();
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 표시된_세션의_연결_시작은_403이다() throws Exception {
        Linked user = signInAndReauthenticate("link-marked");
        String token = csrfTokenOf(user.cookie());
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(user.cookie()))).isTrue();

        HttpResponse<String> start = startLink(NAVER, user.cookie(), token);

        /*
         * 재인증 시작은 표시된 세션의 유일한 회복 경로라 면제되지만 연결은 그 반대다 — 표시된
         * 세션이 계정에 매핑을 더할 수 있으면 그 표시가 막으려던 것이 그대로 열린다.
         */
        assertLinkStartDenied(start);
        assertThat(actionGrantCount()).isZero();
    }

    @Test
    void 미인증_연결_시작은_403이다() throws Exception {
        HttpResponse<String> start = startLink(NAVER, null, "linkanonymousmarker");

        assertLinkStartDenied(start);
        assertThat(sessionCount()).isZero();
        assertThat(actionGrantCount()).isZero();
    }

    @Test
    void 토큰_없는_연결_시작은_403이고_세션이_살아_있다() throws Exception {
        Linked user = signInAndReauthenticate("link-no-token");

        HttpResponse<String> start =
                startLink(NAVER, user.cookie(), null, OidcStubConfiguration.PUBLIC_BASE_URL);

        assertLinkStartDenied(start);
        assertThat(actionGrantCount()).isZero();
        assertThat(sessionState(user.cookie()).body()).contains("\"authenticated\":true");
    }

    @Test
    void 교차_출처_연결_시작은_403이다() throws Exception {
        Linked user = signInAndReauthenticate("link-cross-origin");

        HttpResponse<String> start =
                startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie()), "https://evil.example");

        assertLinkStartDenied(start);
        assertThat(actionGrantCount()).isZero();
    }

    @Test
    void 미지_제공자의_연결_시작은_404이고_이름을_되비추지_않는다() throws Exception {
        Linked user = signInAndReauthenticate("link-unknown-provider");

        HttpResponse<String> start =
                startLink("nosuchprovider", user.cookie(), csrfTokenOf(user.cookie()));

        assertThat(start.statusCode()).isEqualTo(404);
        assertThat(start.body()).contains("\"code\":\"NOT_FOUND\"");
        assertThat(start.body()).doesNotContain("nosuchprovider");
        // 아는 제공자 목록도 개수도 실리지 않는다.
        assertThat(start.body()).doesNotContain(GOOGLE);
        assertThat(start.body()).doesNotContain(NAVER);
        assertThat(start.body()).doesNotContain(KAKAO);
        assertThat(actionGrantCount()).isZero();
    }

    // ----------------------------------------------------------------------
    // 콜백의 판정
    // ----------------------------------------------------------------------

    @Test
    void 다른_계정에_연결된_식별자는_같은_거부다() throws Exception {
        Linked mine = signInAndReauthenticate("link-theirs-mine");
        Linked theirs = signInWith(NAVER, "link-theirs-naver");

        HttpResponse<String> callback =
                link(NAVER, mine.cookie(), csrfTokenOf(mine.cookie()), theirs.subject());

        assertLinkFailed(callback);
        // 양쪽 다 무변경이다. 상대 계정의 존재도 소유자도 드러나지 않는다.
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(linkedProvidersOf(mine.userId())).containsExactly(GOOGLE);
        assertThat(linkedProvidersOf(theirs.userId())).containsExactly(NAVER);
        // 매핑이 서지 않았으므로 허가도 소비되지 않았다. 반쪽 상태가 남지 않는다.
        assertThat(consumedGrantCount()).isZero();
        assertRotatedSessionSurvived(callback, mine.cookie(), mine.userId());
    }

    @Test
    void 무장과_다른_제공자의_콜백은_실패한다() throws Exception {
        Linked user = signInAndReauthenticate("link-provider-swap");
        // 네이버로 무장하고 네이버 흐름을 연 뒤, 콜백이 오기 전에 카카오로 다시 무장한다.
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        Authorization naver = startAuthorization(NAVER, null, user.cookie());
        assertThat(startLink(KAKAO, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        String code =
                issueCode(
                        naver,
                        TokenPlan.valid(naver.nonce())
                                .subject("link-provider-swap-naver-" + UUID.randomUUID()));

        HttpResponse<String> callback = callback(naver, user.cookie(), code, naver.state());

        assertLinkFailed(callback);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(consumedGrantCount()).isZero();
        assertRotatedSessionSurvived(callback, user.cookie(), user.userId());
    }

    @Test
    void 무장과_다른_제공자의_인증_시작은_거절된다() throws Exception {
        Linked user = signInAndReauthenticate("link-start-swap");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);

        Authorization kakao = startAuthorization(KAKAO, null, user.cookie());

        assertThat(kakao.status()).isEqualTo(302);
        // 제공자가 아니라 자기 목적지로 되돌린다. `state`도 발급되지 않았다.
        assertThat(kakao.location()).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(kakao.state()).isEmpty();
    }

    @Test
    void 만료된_허가로는_매핑이_생기지_않는다() throws Exception {
        Linked user = signInAndReauthenticate("link-expired-grant");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        Authorization naver = startAuthorization(NAVER, null, user.cookie());
        // 만료 판정은 소비 UPDATE의 술어가 한다. 행의 시각을 옮기는 것이 그 seam이다.
        moveActionGrantExpiry(singleGrantReference(), Duration.ofSeconds(-1));
        String code =
                issueCode(
                        naver,
                        TokenPlan.valid(naver.nonce())
                                .subject("link-expired-naver-" + UUID.randomUUID()));

        HttpResponse<String> callback = callback(naver, user.cookie(), code, naver.state());

        assertLinkFailed(callback);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(consumedGrantCount()).isZero();
    }

    @Test
    void 무장이_만료된_뒤_도착한_콜백은_이유와_함께_수렴한다() throws Exception {
        Linked user = signInAndReauthenticate("link-expired-flow");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        Authorization naver = startAuthorization(NAVER, null, user.cookie());
        String code =
                issueCode(
                        naver,
                        TokenPlan.valid(naver.nonce())
                                .subject("link-expired-flow-naver-" + UUID.randomUUID()));
        ageLinkFlow(sessionIdOf(user.cookie()), LinkFlow.ARMED_LIFETIME.plusMinutes(1));

        HttpResponse<String> callback = callback(naver, user.cookie(), code, naver.state());

        /*
         * 검증에 넘기기 전에 접힌다. 넘기면 무장 없는 콜백이 성공 핸들러의 **로그인 분기**로 들어가
         * 같은 브라우저에 두 번째 계정이 생긴다.
         */
        assertLinkFailed(callback);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(consumedGrantCount()).isZero();
        // 인증이 확정되기 전에 끝났으므로 회전하지 않는다.
        assertThat(sessionCookieOf(callback)).isEmpty();
        assertThat(sessionState(user.cookie()).body()).contains("\"authenticated\":true");
    }

    @Test
    void 삭제가_시작된_계정은_연결에_실패한다() throws Exception {
        Linked user = signInAndReauthenticate("link-deleting");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        Authorization naver = startAuthorization(NAVER, null, user.cookie());
        String code =
                issueCode(
                        naver,
                        TokenPlan.valid(naver.nonce())
                                .subject("link-deleting-naver-" + UUID.randomUUID()));
        accountDeletion().beginDeletion(user.userId());

        HttpResponse<String> callback = callback(naver, user.cookie(), code, naver.state());

        assertLinkFailed(callback);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(consumedGrantCount()).isZero();
    }

    @Test
    void 사용자가_취소하면_흐름만_버린다() throws Exception {
        Linked user = signInAndReauthenticate("link-cancelled");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        Authorization naver = startAuthorization(NAVER, null, user.cookie());

        HttpResponse<String> callback =
                callbackWithError(NAVER, user.cookie(), "access_denied", naver.state());

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback))
                .endsWith(
                        AuthFlowHandlers.SETTINGS_PATH
                                + "?auth="
                                + AuthFlowHandlers.FAILURE_MARKER_LINK_CANCELLED);
        // 허가·세션·매핑 무변경이다. 인증이 확정되기 전에 끝났으므로 회전도 없다.
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(consumedGrantCount()).isZero();
        assertThat(sessionCookieOf(callback)).isEmpty();
        assertThat(sessionState(user.cookie()).body()).contains("\"authenticated\":true");
        assertThat(sessionAttribute(sessionIdOf(user.cookie()), LinkFlow.FLOW_ATTRIBUTE)).isNull();
    }

    @Test
    void 취소가_아닌_제공자_오류는_실패_표지로_수렴한다() throws Exception {
        Linked user = signInAndReauthenticate("link-provider-error");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        Authorization naver = startAuthorization(NAVER, null, user.cookie());

        HttpResponse<String> callback =
                callbackWithError(NAVER, user.cookie(), "server_error", naver.state());

        assertThat(locationOf(callback))
                .endsWith(
                        AuthFlowHandlers.SETTINGS_PATH
                                + "?auth="
                                + AuthFlowHandlers.FAILURE_MARKER_LINK_FAILED);
        assertThat(locationOf(callback))
                .doesNotContain(AuthFlowHandlers.FAILURE_MARKER_LINK_CANCELLED);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 흐름_도중_로그아웃하면_콜백이_아무것도_바꾸지_않는다() throws Exception {
        Linked user = signInAndReauthenticate("link-logout");
        String token = csrfTokenOf(user.cookie());
        assertThat(startLink(NAVER, user.cookie(), token).statusCode()).isEqualTo(204);
        Authorization naver = startAuthorization(NAVER, null, user.cookie());
        String code =
                issueCode(
                        naver,
                        TokenPlan.valid(naver.nonce())
                                .subject("link-logout-naver-" + UUID.randomUUID()));

        // 세션 행이 사라지면 흐름도 함께 사라진다. 무장은 그 행의 속성 하나였다.
        assertThat(logout(user.cookie(), token).statusCode()).isEqualTo(204);
        assertThat(sessionCount()).isZero();

        HttpResponse<String> callback = callback(naver, user.cookie(), code, naver.state());

        // 기존 실패 경로로 수렴한다. `state`를 대조할 세션이 없다.
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback))
                .endsWith("/?auth=" + AuthFlowHandlers.FAILURE_MARKER_FAILED);
        assertThat(sessionCount()).isZero();
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(consumedGrantCount()).isZero();
    }

    @Test
    void 흐름은_일회성이라_두_번째_콜백이_아무것도_바꾸지_않는다() throws Exception {
        Linked user = signInAndReauthenticate("link-replay-callback");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        Authorization naver = startAuthorization(NAVER, null, user.cookie());
        String code =
                issueCode(
                        naver,
                        TokenPlan.valid(naver.nonce())
                                .subject("link-replay-naver-" + UUID.randomUUID()));

        HttpResponse<String> first = callback(naver, user.cookie(), code, naver.state());
        assertThat(first.statusCode()).isEqualTo(302);
        String rotated = sessionCookieOf(first).orElseThrow();
        assertThat(socialLinkCount()).isEqualTo(2);

        HttpResponse<String> second = callback(naver, rotated, code, naver.state());

        /*
         * 무장이 소비됐으므로 이 콜백은 이 세션의 사실이 아니다. 인증 흐름 경계가 검증에 넘기지
         * 않고 지금 목적지로 되돌린다.
         */
        assertThat(second.statusCode()).isEqualTo(302);
        assertThat(locationOf(second)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(accountCount()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------
    // 두 흐름이 한 세션에 있을 때
    // ----------------------------------------------------------------------

    @Test
    void 연결_무장_도중_재인증을_시작하면_그_흐름이_대신_성립한다() throws Exception {
        /*
         * 설정 화면은 연결 버튼 셋과 본인 확인 버튼 셋을 같은 페이지에 그린다. "네이버 연결 무장 →
         * 제공자 화면에서 이탈 → Google 본인 확인"은 그래서 평범한 경로이고, 연결 무장이 남아
         * 있으면 그 Google 콜백이 연결 분기에 삼켜져 재인증이 성립하지 않는다.
         */
        Linked user = signInAndReauthenticate("link-then-reauth");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        assertThat(actionGrantCount()).isEqualTo(1);

        assertThat(
                        startReauthentication(GOOGLE, user.cookie(), csrfTokenOf(user.cookie()))
                                .statusCode())
                .isEqualTo(204);

        // 무장 시각에 상대 흐름을 버린다. 그 무장이 가리키던 허가도 함께 사라진다.
        String sessionId = sessionIdOf(user.cookie());
        assertThat(sessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(actionGrantCount()).isZero();

        Authorization google = startAuthorization(GOOGLE, null, user.cookie());
        String code =
                issueCode(google, TokenPlan.valid(google.nonce()).subject(user.subject()));
        HttpResponse<String> callback = callback(google, user.cookie(), code, google.state());

        // 재인증이 성립한다 — 붙들어 둔 목적지로 표지 없이 돌아가고 재인증 시각이 기록된다.
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(locationOf(callback)).doesNotContain("auth=");
        String rotated = sessionCookieOf(callback).orElseThrow();
        assertThat(
                        sessionAttribute(
                                sessionIdOf(rotated), ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                .isInstanceOf(java.time.Instant.class);
        // 연결은 일어나지 않았다.
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 두_흐름이_함께_무장돼도_콜백의_제공자가_가른다() throws Exception {
        /*
         * 무장 시각의 정리가 1차 방어이고 이것은 그 뒤에 남는 상태를 위한 2차 방어다. 세션 속성을
         * 직접 심어 두 흐름이 공존하는 상태를 만든다 — 그 상태를 HTTP로 만들 수 없게 된 것이
         * 1차 방어의 결과다.
         */
        Linked user = signInAndReauthenticate("link-both-armed");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        String sessionId = sessionIdOf(user.cookie());
        Object armedLink = sessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE);
        assertThat(armedLink).isInstanceOf(LinkFlow.Armed.class);

        assertThat(
                        startReauthentication(GOOGLE, user.cookie(), csrfTokenOf(user.cookie()))
                                .statusCode())
                .isEqualTo(204);
        setSessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE, armedLink);

        Authorization google = startAuthorization(GOOGLE, null, user.cookie());
        String code =
                issueCode(google, TokenPlan.valid(google.nonce()).subject(user.subject()));
        HttpResponse<String> callback = callback(google, user.cookie(), code, google.state());

        // Google 콜백은 Google 무장의 것이다. 연결 무장이 살아 있어도 삼키지 않는다.
        assertThat(locationOf(callback)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(locationOf(callback)).doesNotContain("auth=");
        assertThat(
                        sessionAttribute(
                                sessionIdOf(sessionCookieOf(callback).orElseThrow()),
                                ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                .isInstanceOf(java.time.Instant.class);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 두_번째_연결_시작이_앞의_허가를_무효화한다() throws Exception {
        Linked user = signInAndReauthenticate("link-rearm");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        String first = singleGrantReference();

        assertThat(startLink(KAKAO, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);

        /*
         * `issue`의 무효화 술어는 `(user, command, target)`이고 target이 제공자 이름이라, 이
         * 무효화를 무장 쪽에서 하지 않으면 네이버 허가만 살아남고 그것을 가리키던 흐름 기록은
         * 사라진다 — 아무도 참조할 수 없는 허가가 수명을 사는 상태다.
         */
        assertThat(actionGrantExists(first)).isFalse();
        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(singleGrantReference()).isNotEqualTo(first);
    }

    @Test
    void 거절되는_연결_시작은_앞선_무장과_허가를_그대로_둔다() throws Exception {
        /*
         * 무장을 버리는 일이 게이트보다 앞에 있으면 403 하나가 진행 중이던 흐름을 파괴한다 — 이미
         * 연결된 제공자를 눌러 본 사용자가 방금 연 네이버 흐름을 잃는다. 그 순서는 Story 1.11이
         * 고친 것이고 이 단정이 그것을 붙잡는다.
         */
        Linked user = signInAndReauthenticate("link-rejected-keeps");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        String armed = singleGrantReference();
        String sessionId = sessionIdOf(user.cookie());

        // Google은 이미 이 계정에 연결돼 있다. 시작 게이트가 그것을 거절한다.
        assertLinkStartDenied(startLink(GOOGLE, user.cookie(), csrfTokenOf(user.cookie())));

        assertThat(sessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(LinkFlow.Armed.class);
        assertThat(actionGrantExists(armed)).isTrue();
        assertThat(actionGrantCount()).isEqualTo(1);

        // 그리고 그 무장은 여전히 쓸 수 있다.
        Authorization naver = startAuthorization(NAVER, null, user.cookie());
        String code =
                issueCode(
                        naver,
                        TokenPlan.valid(naver.nonce())
                                .subject("link-rejected-keeps-naver-" + UUID.randomUUID()));
        assertThat(callback(naver, user.cookie(), code, naver.state()).statusCode()).isEqualTo(302);
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 구성되지_않은_제공자의_연결_시작은_거부된다() {
        /*
         * 아는 이름이지만 등록이 만들어지지 않은 구간이다. 통과시키면 허가와 무장이 남은 채
         * 뒤따르는 인가 시작이 fail-closed로 끝나고, 사용자는 원본 오류 JSON에 닿으며 참조할 수
         * 없게 된 허가가 그대로 수명을 산다.
         *
         * 제공자 하나만 구성된 상태는 컨텍스트로 만들지 않는다 — 새 `@DynamicPropertySource`는
         * 컨텍스트를 가른다. 대신 그 등록으로 포트를 직접 세운다(`DevOidcStubProfileTest`와 같은
         * 관용).
         */
        UUID userId = accountLifecycle().createAccount();
        accountLifecycle()
                .linkSocialIdentity(
                        userId, GOOGLE, OidcStubConfiguration.issuer(GOOGLE), "unconfigured-google");
        MockHttpSession session = grantSession(UUID.randomUUID().toString(), userId);
        LinkService restricted =
                new LinkService(accountLifecycle(), this.actionGrants, googleOnlyRegistrations());

        assertThat(restricted.start(session, userId, NAVER)).isEmpty();

        // 거부는 상태를 만들지 않는다. 허가도 무장도 없다.
        assertThat(actionGrantCount()).isZero();
        assertThat(session.getAttribute(LinkFlow.FLOW_ATTRIBUTE)).isNull();
        // 이름은 여전히 아는 값이다. 두 질문이 다르다는 사실이 이 단정 한 쌍이다.
        assertThat(AuthSecurityConfig.ProviderClientRegistrations.isSupportedProvider(NAVER)).isTrue();
        assertThat(googleOnlyRegistrations().isConfiguredProvider(NAVER)).isFalse();
        assertThat(googleOnlyRegistrations().isConfiguredProvider(GOOGLE)).isTrue();
    }

    @Test
    void 같은_제공자의_다른_subject는_같은_거부다() throws Exception {
        Linked user = signInAndReauthenticate("link-same-provider");
        assertThat(startLink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        Authorization naver = startAuthorization(NAVER, null, user.cookie());
        String code =
                issueCode(
                        naver,
                        TokenPlan.valid(naver.nonce())
                                .subject("link-same-provider-late-" + UUID.randomUUID()));
        /*
         * 왕복 도중 같은 제공자가 이 계정에 붙었다. 시작 게이트는 이미 지났으므로 판정은
         * `(user_id, provider)` 유일 인덱스가 하고, 그 답이 HTTP 층에서 같은 거부 하나로 접히는지가
         * 이 축이다.
         */
        assertThat(
                        accountLifecycle()
                                .linkSocialIdentity(
                                        user.userId(),
                                        NAVER,
                                        OidcStubConfiguration.issuer(NAVER),
                                        "link-same-provider-early-" + UUID.randomUUID()))
                .isEqualTo(com.bokrilog.identity.AccessDecision.ALLOWED);

        HttpResponse<String> callback = callback(naver, user.cookie(), code, naver.state());

        assertLinkFailed(callback);
        // 먼저 붙은 매핑 하나만 남는다. 허가도 소비되지 않았다.
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(linkedProvidersOf(user.userId())).containsExactlyInAnyOrder(GOOGLE, NAVER);
        assertThat(consumedGrantCount()).isZero();
    }

    // ----------------------------------------------------------------------
    // 멱등
    // ----------------------------------------------------------------------

    @Test
    void 같은_operation_id_재전송은_저장된_결과를_돌려주고_매핑을_다시_만들지_않는다() {
        /*
         * HTTP를 지나지 않는 유일한 축이다. 연결 흐름은 콜백 하나로 소비되므로 같은 무장으로 두
         * 번째 요청을 보낼 길이 없고, 그래서 멱등 경로를 관측하려면 포트를 직접 두 번 불러야
         * 한다. 멱등의 권위는 1.9의 `(user_id, operation_id)` 유일 인덱스 그대로다.
         */
        UUID userId = accountLifecycle().createAccount();
        accountLifecycle()
                .linkSocialIdentity(userId, GOOGLE, OidcStubConfiguration.issuer(GOOGLE), "idem-google");
        MockHttpSession session = grantSession(UUID.randomUUID().toString(), userId);
        LinkFlow.Armed armed = linkService().start(session, userId, NAVER).orElseThrow();
        String naverSubject = "idem-naver-" + UUID.randomUUID();

        assertThat(
                        linkService()
                                .complete(
                                        session,
                                        armed,
                                        OidcStubConfiguration.issuer(NAVER),
                                        naverSubject))
                .isEqualTo(LinkService.Outcome.LINKED);
        assertThat(socialLinkCount()).isEqualTo(2);

        // 같은 요청의 재전송이다. 저장된 결과를 그대로 받고 업무 변경은 다시 실행되지 않는다.
        assertThat(
                        linkService()
                                .complete(
                                        session,
                                        armed,
                                        OidcStubConfiguration.issuer(NAVER),
                                        naverSubject))
                .isEqualTo(LinkService.Outcome.LINKED);
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(consumedGrantCount()).isEqualTo(1);
    }

    @Test
    void 발급_시각의_세션_id를_제시하지_않으면_소비가_성립하지_않는다() {
        /*
         * 이 스토리가 1.9의 포트를 넓힌 이유를 고정한다. 콜백에서 세션 고정 방어가 ID를 반드시
         * 회전시키므로 발급 시각의 세션 id와 소비 시각의 live id는 언제나 다르고, 흐름 기록이 그
         * 값을 나르지 않으면 정상 연결이 구조적으로 거부된다.
         */
        UUID userId = accountLifecycle().createAccount();
        accountLifecycle()
                .linkSocialIdentity(
                        userId, GOOGLE, OidcStubConfiguration.issuer(GOOGLE), "bound-google");
        MockHttpSession issuing = grantSession(UUID.randomUUID().toString(), userId);
        LinkFlow.Armed armed = linkService().start(issuing, userId, NAVER).orElseThrow();
        assertThat(armed.boundSessionId()).isEqualTo(issuing.getId());

        // 회전한 세션이다. 같은 사용자·같은 재인증 기록이지만 id가 다르다.
        MockHttpSession rotated = grantSession(UUID.randomUUID().toString(), userId);
        LinkFlow.Armed withLiveId =
                new LinkFlow.Armed(
                        armed.targetProvider(),
                        armed.userId(),
                        armed.returnPath(),
                        armed.grantReference(),
                        armed.operationId(),
                        // 흐름 기록이 live id를 들고 있는 상태. 귀속이 어긋난다.
                        rotated.getId(),
                        armed.armedAt());

        assertThat(
                        linkService()
                                .complete(
                                        rotated,
                                        withLiveId,
                                        OidcStubConfiguration.issuer(NAVER),
                                        "bound-naver"))
                .isEqualTo(LinkService.Outcome.DENIED);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(consumedGrantCount()).isZero();

        // 같은 회전한 세션이라도 발급 시각의 id를 제시하면 소비된다.
        assertThat(
                        linkService()
                                .complete(
                                        rotated,
                                        armed,
                                        OidcStubConfiguration.issuer(NAVER),
                                        "bound-naver"))
                .isEqualTo(LinkService.Outcome.LINKED);
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    // ----------------------------------------------------------------------
    // 단정 도구
    // ----------------------------------------------------------------------

    /**
     * 로그인과 재인증을 마친 사용자 하나.
     *
     * @param subject 그 사용자가 Google 스텁에서 받은 외부 식별자
     * @param cookie 재인증으로 회전한 뒤의 세션 쿠키
     */
    private record Linked(UUID userId, String subject, String cookie) {}

    /**
     * Google로 가입하고 곧바로 재인증까지 마친 사용자.
     *
     * <p>연결의 전제가 정확히 이 상태다 — 세션에 재인증 시각이 기록돼 있어야 허가가 발급된다.
     * 일반 로그인은 그 값을 남기지 않으므로(Story 1.8) 이 두 단계를 한 곳에 둔다.
     */
    private Linked signInAndReauthenticate(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        HttpResponse<String> reauthenticated =
                reauthenticate(GOOGLE, cookie, csrfTokenOf(cookie), plan -> plan.subject(subject));
        String rotated = sessionCookieOf(reauthenticated).orElseThrow();
        return new Linked(userId, subject, rotated);
    }

    /** 그 제공자로만 가입한 다른 사용자. 남의 식별자를 만드는 데 쓴다. */
    private Linked signInWith(String provider, String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(provider, subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(provider), subject).orElseThrow();
        return new Linked(userId, subject, cookie);
    }

    /** 연결 시작이 거부됐는가. 모든 거부가 사유를 구분하지 않는 같은 하나다. */
    private static void assertLinkStartDenied(HttpResponse<String> start) {
        assertThat(start.statusCode()).isEqualTo(403);
        assertThat(start.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(start.body()).contains("\"correlationId\"");
    }

    /**
     * 연결이 실패로 수렴했는가.
     *
     * <p>모든 실패가 같은 모양이다 — 설정 화면으로 표지 하나를 달고 돌아가며, 그 표지는 어느
     * 갈래에서 실패했는지 말하지 않는다.
     */
    private static void assertLinkFailed(HttpResponse<String> callback) {
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(locationOf(callback))
                .endsWith(
                        AuthFlowHandlers.SETTINGS_PATH
                                + "?auth="
                                + AuthFlowHandlers.FAILURE_MARKER_LINK_FAILED);
    }

    /**
     * 검증을 통과한 뒤 거절된 연결이 살아남았는가.
     *
     * <p>{@code AuthReauthenticationTest}의 같은 이름 헬퍼와 근거가 같다. 세션 고정 방어가 성공
     * 핸들러보다 앞에서 돌므로 이 경로의 세션 ID는 이미 바뀌었고, 같은 302 응답이 새 쿠키를 함께
     * 실어 보내지 않으면 사용자는 그 자리에서 로그아웃된다.
     */
    private void assertRotatedSessionSurvived(
            HttpResponse<String> callback, String cookie, UUID userId) throws Exception {
        String rotated = sessionCookieOf(callback).orElseThrow();
        assertThat(rotated).isNotEqualTo(cookie);
        assertThat(sessionState(cookie).body()).contains("\"authenticated\":false");
        assertThat(sessionState(rotated).body()).contains("\"authenticated\":true");
        assertThat(sessionCountOf(userId)).isEqualTo(1);
    }

    /**
     * Google만 구성된 등록.
     *
     * <p>새 컨텍스트를 만들지 않고 "아는 이름인데 구성되지 않음" 구간을 세우는 방법이다. 값은
     * 스텁이 실행 시점에 만든 것이라 소스에 자격정보 모양 리터럴이 남지 않는다.
     */
    private static AuthSecurityConfig.ProviderClientRegistrations googleOnlyRegistrations() {
        AuthSecurityConfig.AuthProperties.Provider google =
                new AuthSecurityConfig.AuthProperties.Provider(
                        OidcStubConfiguration.clientId(GOOGLE),
                        OidcStubConfiguration.clientSecret(GOOGLE),
                        OidcStubConfiguration.issuer(GOOGLE),
                        OidcStubConfiguration.issuer(GOOGLE) + "/authorize",
                        OidcStubConfiguration.issuer(GOOGLE) + "/token",
                        OidcStubConfiguration.issuer(GOOGLE) + "/jwks");
        return AuthSecurityConfig.ProviderClientRegistrations.from(
                new AuthSecurityConfig.AuthProperties(
                        OidcStubConfiguration.PUBLIC_BASE_URL, google, null, null));
    }

    /** 그 계정에 연결된 제공자. {@code identity} 공개 API가 돌려주는 값 그대로다. */
    private List<String> linkedProvidersOf(UUID userId) {
        return List.copyOf(accountLifecycle().findLinkedProviders(userId));
    }

    /** 소비된 허가 행 수. "허가만 소비되고 매핑이 없는" 반쪽 상태를 관측하는 창이다. */
    private long consumedGrantCount() {
        return jdbc()
                .sql("SELECT count(*) FROM action_grant WHERE consumed_at IS NOT NULL")
                .query(Long.class)
                .single();
    }

    /** 지금 저장소에 있는 유일한 허가 참조. 행이 하나가 아니면 소리 내어 실패한다. */
    private String singleGrantReference() {
        return jdbc().sql("SELECT grant_id FROM action_grant").query(String.class).single();
    }

    /**
     * 무장된 연결 흐름의 무장 시각을 과거로 옮긴다.
     *
     * <p>{@code AuthFlowTestBase.ageReauthenticationFlow}와 같은 근거의 같은 모양이다 — 시험이
     * 5분을 기다릴 수 없고, 수명을 줄이는 설정을 따로 두면 그 설정이 검증하는 값과 제품이 쓰는
     * 값이 갈린다.
     */
    private void ageLinkFlow(String sessionId, Duration elapsed) {
        Object stored = sessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE);
        if (!(stored instanceof LinkFlow.Armed armed)) {
            throw new IllegalStateException("무장되지 않은 세션이다: " + sessionId);
        }
        setSessionAttribute(
                sessionId,
                LinkFlow.FLOW_ATTRIBUTE,
                new LinkFlow.Armed(
                        armed.targetProvider(),
                        armed.userId(),
                        armed.returnPath(),
                        armed.grantReference(),
                        armed.operationId(),
                        armed.boundSessionId(),
                        armed.armedAt().minus(elapsed)));
    }
}
