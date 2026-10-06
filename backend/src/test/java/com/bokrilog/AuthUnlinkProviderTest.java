package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.identity.AccessDecision;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/**
 * 로그인 수단을 안전하게 해제하기.
 *
 * <p>AC-2~AC-9의 HTTP 층 소유자다. 여기서 보는 축은 두 단계의 순서("최종 확인 전에는 아무것도 바뀌지
 * 않는다"), 흐름 귀속, 한 트랜잭션 커밋, 커밋 뒤의 세션 정리, 그리고 하나로 접히는 거부다.
 *
 * <p><b>{@code identity}가 이미 고정한 축을 복제하지 않는다.</b> 마지막 수단 보호가 삭제 문장 안에
 * 있다는 사실은 {@code identity.AccountUnlinkTest}가, 동시 해제에서 정확히 하나만 성공한다는 사실은
 * {@code identity.AccountConcurrencyTest}가 본다. 여기서는 그 답이 HTTP·허가 층을 지나 <b>거부
 * 하나</b>로 수렴하는지만 본다.
 *
 * <p>모든 실패 축이 매핑 행 수와 허가의 소비 여부를 함께 단정한다. 해제의 가장 큰 위험은 둘이고 둘 다
 * 저장소에서만 보인다 — 마지막 수단까지 사라져 <b>계정이 잠기는 것</b>과, 허가만 소비되고 매핑이
 * 그대로인 <b>반쪽 상태</b>가 남는 것이다.
 */
class AuthUnlinkProviderTest extends AuthFlowTestBase {

    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    private static final String NAVER = AuthSecurityConfig.NAVER_REGISTRATION_ID;

    private static final String KAKAO = AuthSecurityConfig.KAKAO_REGISTRATION_ID;

    // ----------------------------------------------------------------------
    // 정상 해제
    // ----------------------------------------------------------------------

    @Test
    void 준비와_확인을_지나면_매핑이_하나_사라진다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-happy");

        HttpResponse<String> prepared =
                prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));

        assertThat(prepared.statusCode()).isEqualTo(204);
        assertThat(prepared.body()).isEmpty();
        // 준비는 무장과 허가만 만든다. 매핑도 세션도 그대로다.
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(actionGrantCountOf(user.userId())).isEqualTo(1);
        assertThat(consumedGrantCount()).isZero();
        assertThat(sessionAttribute(sessionIdOf(user.cookie()), UnlinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(UnlinkFlow.Armed.class);

        HttpResponse<String> confirmed =
                confirmUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));

        assertThat(confirmed.statusCode()).isEqualTo(204);
        assertThat(confirmed.body()).isEmpty();
        // 본문도 헤더도 대상 제공자를 말하지 않는다. 화면이 목록을 다시 조회해 지금 상태를 그린다.
        assertThat(confirmed.headers().map().toString()).doesNotContain(NAVER);

        // 매핑이 하나 사라졌고 계정은 그대로다.
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(linkedProvidersOf(user.userId())).containsExactly(GOOGLE);
        // 허가는 정확히 한 번 소비됐다.
        assertThat(consumedGrantCount()).isEqualTo(1);

        // 세션이 회전했고 새 쿠키로 인증 상태다. 옛 쿠키는 죽었다.
        String rotated = sessionCookieOf(confirmed).orElseThrow();
        assertThat(rotated).isNotEqualTo(user.cookie());
        assertThat(sessionState(user.cookie()).body()).contains("\"authenticated\":false");
        assertThat(sessionState(rotated).body()).contains("\"authenticated\":true");
        assertThat(sessionCountOf(user.userId())).isEqualTo(1);

        /*
         * 무장은 남는다. 확인이 그것을 지우면 두 가지가 깨진다 — 확인 도중의 예외가 허가를 고아로
         * 남기고, 응답을 잃은 클라이언트가 같은 `operation_id`를 다시 제시할 길이 사라진다. 일회성은
         * 무장이 아니라 허가의 성질이며 아래 재전송 축이 그것을 관측한다.
         */
        assertThat(sessionAttribute(sessionIdOf(rotated), UnlinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(UnlinkFlow.Armed.class);
    }

    @Test
    void 같은_확인의_재전송은_204이고_아무것도_다시_하지_않는다() throws Exception {
        /*
         * 1.9의 멱등 계약이 HTTP 표면에서 도달하는 자리다. 무장이 남아 있으므로 같은 확인을 다시
         * 보낼 수 있고, 허가의 저장된 결과가 그 요청에 답한다 — 매핑을 다시 지우지도, 세션을 다시
         * 정리하지도 않는다.
         */
        Linked user = signInLinkAndReauthenticate("unlink-resend");
        HttpResponse<String> first = unlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));
        assertThat(first.statusCode()).isEqualTo(204);
        String rotated = sessionCookieOf(first).orElseThrow();
        assertThat(socialLinkCount()).isEqualTo(1);

        HttpResponse<String> resent = confirmUnlink(NAVER, rotated, csrfTokenOf(rotated));

        assertThat(resent.statusCode()).isEqualTo(204);
        // 업무 변경이 다시 실행되지 않았다. 마지막 수단이 사라지지 않은 것이 그 증거다.
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(linkedProvidersOf(user.userId())).containsExactly(GOOGLE);
        assertThat(consumedGrantCount()).isEqualTo(1);
        /*
         * 세션 정리도 다시 돌지 않는다. 업무 변경이 일어나지 않았는데 세션을 또 폐기·회전시키면
         * "커밋 뒤에만 정리한다"가 깨지고, 그 회전은 `Set-Cookie`로 밖에서 보인다.
         */
        assertThat(sessionCookieOf(resent)).isEmpty();
        assertThat(sessionCountOf(user.userId())).isEqualTo(1);
        assertThat(sessionState(rotated).body()).contains("\"authenticated\":true");
    }

    @Test
    void 실행된_해제는_재인증_시각을_지워_다음_민감_command를_연쇄_승인하지_않는다() throws Exception {
        /*
         * I/O 표 "연쇄 승인 차단". 본인 확인 1회는 command 1건이다 — 해제가 실제로 실행된 요청이 세션 회전과
         * 함께 재인증 시각을 지우므로(`SessionRotation`), 5분 안의 다음 민감 command(여기서는 연결 시작)는
         * 허가를 받지 못하고 재인증을 요구한다. 새 재인증 뒤에는 다시 시작할 수 있다.
         */
        Linked user = signInLinkAndReauthenticate("unlink-chain");
        HttpResponse<String> confirmed = unlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));
        assertThat(confirmed.statusCode()).isEqualTo(204);
        String rotated = sessionCookieOf(confirmed).orElseThrow();
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionAttribute(sessionIdOf(rotated), ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                .isNull();

        // 같은 세션의 다음 민감 command는 재인증 요구로 거절된다. 허가는 발급되지 않고 무장도 심기지 않는다.
        HttpResponse<String> chained = startLink(KAKAO, rotated, csrfTokenOf(rotated));
        assertUnlinkDenied(chained);
        assertThat(actionGrantCountOf(user.userId())).isEqualTo(1);
        assertThat(consumedGrantCount()).isEqualTo(1);
        assertThat(sessionAttribute(sessionIdOf(rotated), LinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(socialLinkCount()).isEqualTo(1);

        // 새 본인 확인이 다시 연다.
        String reauthenticated =
                sessionCookieOf(
                                reauthenticate(
                                        GOOGLE, rotated, csrfTokenOf(rotated), plan -> plan.subject(user.subject())))
                        .orElseThrow();
        assertThat(startLink(KAKAO, reauthenticated, csrfTokenOf(reauthenticated)).statusCode()).isEqualTo(204);
        assertThat(actionGrantCountOf(user.userId())).isEqualTo(2);
    }

    @Test
    void 거부된_확인은_재인증_시각을_남기고_같은_무장으로_다시_확인할_수_있다() throws Exception {
        /*
         * I/O 표 "Denied". 거부는 업무 변경을 실행하지 않았으므로 회전도 시각 제거도 없다 — 그래서 같은 무장으로
         * 다시 시도할 수 있다. 지우는 조건이 "실행 플래그"인 것을 여기서 고정한다.
         */
        Linked user = signInLinkAndReauthenticate("unlink-denied-keeps");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode()).isEqualTo(204);
        String sessionId = sessionIdOf(user.cookie());

        // 대상이 다른 확인은 거부다.
        HttpResponse<String> denied = confirmUnlink(GOOGLE, user.cookie(), csrfTokenOf(user.cookie()));
        assertUnlinkDenied(denied);
        assertThat(sessionCookieOf(denied)).as("거부는 회전하지 않는다").isEmpty();
        assertThat(sessionAttribute(sessionId, ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                .isInstanceOf(java.time.Instant.class);
        assertThat(consumedGrantCount()).isZero();
        assertThat(socialLinkCount()).isEqualTo(2);

        // 같은 무장이 그대로 쓰인다. 이번에는 실행이고, 그 요청이 시각을 지운다.
        HttpResponse<String> confirmed = confirmUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));
        assertThat(confirmed.statusCode()).isEqualTo(204);
        assertThat(socialLinkCount()).isEqualTo(1);
        String rotated = sessionCookieOf(confirmed).orElseThrow();
        assertThat(sessionAttribute(sessionIdOf(rotated), ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                .isNull();
    }

    @Test
    void 해제한_제공자로_로그인하면_기존_계정에_닿지_않는다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-then-sign-in");
        assertThat(unlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);

        HttpResponse<String> reused = signIn(NAVER, user.naverSubject());

        /*
         * 그 식별자는 이제 어느 계정에도 연결돼 있지 않다. 자동 재연결이 없으므로 신규 가입 경로를
         * 지나 **새 계정**이 만들어지고, 그것이 정책이다(I/O 표의 "해제 뒤 그 제공자 로그인").
         */
        assertThat(reused.statusCode()).isEqualTo(302);
        assertThat(locationOf(reused)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(accountCount()).isEqualTo(2);
        assertThat(userIdOf(OidcStubConfiguration.issuer(NAVER), user.naverSubject()))
                .isNotEmpty()
                .isNotEqualTo(Optional.of(user.userId()));
        // 기존 계정의 매핑은 늘지 않았다.
        assertThat(linkedProvidersOf(user.userId())).containsExactly(GOOGLE);
    }

    @Test
    void 해제_뒤에도_남은_수단으로는_같은_계정에_로그인된다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-remaining");
        assertThat(unlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);

        HttpResponse<String> again = signIn(GOOGLE, user.subject());

        assertThat(again.statusCode()).isEqualTo(302);
        // 기존 사용자이므로 온보딩이 아니다. 계정도 매핑도 늘지 않았다.
        assertThat(locationOf(again)).endsWith(AuthFlowHandlers.DASHBOARD_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(userIdOf(OidcStubConfiguration.issuer(), user.subject())).contains(user.userId());
    }

    @Test
    void 해제가_현재_세션_외의_세션을_모두_폐기한다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-sessions");
        // 같은 사용자의 다른 기기 둘. 남은 수단으로 로그인하므로 같은 계정의 세션이다.
        String otherDevice = sessionCookieOf(signIn(GOOGLE, user.subject())).orElseThrow();
        String thirdDevice = sessionCookieOf(signIn(GOOGLE, user.subject())).orElseThrow();
        // 다른 사용자 하나. 이 세션은 어느 경우에도 대상이 아니다.
        Linked bystander = signInOnly("unlink-bystander");
        assertThat(sessionCountOf(user.userId())).isEqualTo(3);

        HttpResponse<String> confirmed =
                unlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));

        assertThat(confirmed.statusCode()).isEqualTo(204);
        /*
         * 세션은 어떤 제공자로 인증됐는지 기록하지 않는다. 그래서 "해제된 수단에서 유래한 세션"을
         * 고를 수 없고, AC-7이 정한 fail-safe 갈래가 유일한 길이다 — 현재 세션 외 전부 폐기다.
         */
        assertThat(sessionCountOf(user.userId())).isEqualTo(1);
        assertThat(sessionState(otherDevice).body()).contains("\"authenticated\":false");
        assertThat(sessionState(thirdDevice).body()).contains("\"authenticated\":false");
        // 남은 하나는 회전한 현재 세션이다.
        String rotated = sessionCookieOf(confirmed).orElseThrow();
        assertThat(sessionState(rotated).body()).contains("\"authenticated\":true");
        // 다른 사용자의 세션은 그대로다.
        assertThat(sessionCountOf(bystander.userId())).isEqualTo(1);
        assertThat(sessionState(bystander.cookie()).body()).contains("\"authenticated\":true");
    }

    @Test
    void 해제_뒤_계정_삭제가_남은_매핑을_지우고_다른_사용자는_보존한다() throws Exception {
        Linked mine = signInLinkAndReauthenticate("unlink-erase-mine");
        assertThat(unlink(NAVER, mine.cookie(), csrfTokenOf(mine.cookie())).statusCode())
                .isEqualTo(204);
        Linked other = signInOnly("unlink-erase-other");

        accountDeletion().beginDeletion(mine.userId());
        accountDeletion().erasePayload(mine.userId());

        assertThat(linkedProvidersOf(mine.userId())).isEmpty();
        assertThat(linkedProvidersOf(other.userId())).containsExactly(GOOGLE);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------
    // 준비의 게이트
    // ----------------------------------------------------------------------

    @Test
    void 마지막_수단은_준비부터_거부된다() throws Exception {
        // Google 하나만 연결된 계정이다. 재인증은 신선하다.
        Linked user = signInAndReauthenticate("unlink-last");

        HttpResponse<String> prepared =
                prepareUnlink(GOOGLE, user.cookie(), csrfTokenOf(user.cookie()));

        assertUnlinkDenied(prepared);
        // 허가도 무장도 만들어지지 않았다. 소비될 수 없는 허가가 5분을 사는 상태를 만들지 않는다.
        assertThat(actionGrantCount()).isZero();
        assertThat(sessionAttribute(sessionIdOf(user.cookie()), UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 마지막_수단을_API로_직접_확인해도_아무것도_바뀌지_않는다() throws Exception {
        /*
         * 화면을 우회한 직접 호출이다. 무장은 수단이 둘일 때 정상으로 얻고, 그 사이에 다른 수단이
         * 사라진 상태를 만들어 확인을 보낸다 — 화면이 유일한 게이트가 아니라는 사실이 이 축이다.
         */
        Linked user = signInLinkAndReauthenticate("unlink-last-direct");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        // Google을 먼저 떼어 낸다. 이제 네이버가 마지막 수단이다.
        assertThat(accountLifecycle().unlinkSocialIdentity(user.userId(), GOOGLE))
                .isEqualTo(AccessDecision.ALLOWED);
        assertThat(socialLinkCount()).isEqualTo(1);

        HttpResponse<String> confirmed =
                confirmUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));

        assertUnlinkDenied(confirmed);
        // 삭제 0행이고 허가도 소비되지 않았다. 반쪽 상태가 남지 않는다.
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(linkedProvidersOf(user.userId())).containsExactly(NAVER);
        assertThat(consumedGrantCount()).isZero();
        // 세션도 그대로다. 정리는 커밋 뒤에만 일어난다.
        assertThat(sessionCountOf(user.userId())).isEqualTo(1);
        assertThat(sessionState(user.cookie()).body()).contains("\"authenticated\":true");
    }

    @Test
    void 재인증이_없으면_무장하지_않는다() throws Exception {
        Linked user = signInWithTwoMethods("unlink-no-reauth");

        HttpResponse<String> prepared =
                prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));

        assertUnlinkDenied(prepared);
        assertThat(actionGrantCount()).isZero();
        assertThat(sessionAttribute(sessionIdOf(user.cookie()), UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 재인증이_창을_넘기면_무장하지_않는다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-stale-reauth");
        ageReauthentication(
                sessionIdOf(user.cookie()), ActionGrant.REAUTHENTICATION_WINDOW.plusMinutes(1));

        HttpResponse<String> prepared =
                prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));

        assertUnlinkDenied(prepared);
        assertThat(actionGrantCount()).isZero();
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 연결되지_않은_제공자의_해제는_같은_거부다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-not-linked");

        HttpResponse<String> prepared =
                prepareUnlink(KAKAO, user.cookie(), csrfTokenOf(user.cookie()));

        // 마지막 수단 거부와 응답이 구분되지 않는다.
        assertUnlinkDenied(prepared);
        assertThat(actionGrantCount()).isZero();
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 표시된_세션의_해제는_403이다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-marked");
        String token = csrfTokenOf(user.cookie());
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(user.cookie()))).isTrue();

        /*
         * 재인증 시작은 표시된 세션의 유일한 회복 경로라 면제되지만 해제는 그 반대다 — 표시된
         * 세션이 로그인 수단을 떼어 낼 수 있으면 그 표시가 막으려던 것이 그대로 열린다.
         */
        assertUnlinkDenied(prepareUnlink(NAVER, user.cookie(), token));
        assertUnlinkDenied(confirmUnlink(NAVER, user.cookie(), token));
        assertThat(actionGrantCount()).isZero();
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 미인증_해제는_403이다() throws Exception {
        assertUnlinkDenied(prepareUnlink(NAVER, null, "verifyanonymousunlinktoken"));
        assertUnlinkDenied(confirmUnlink(NAVER, null, "verifyanonymousunlinktoken"));
        assertThat(sessionCount()).isZero();
    }

    // ----------------------------------------------------------------------
    // 확인의 게이트
    // ----------------------------------------------------------------------

    @Test
    void 무장_없이_보낸_확인은_아무것도_바꾸지_않는다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-unarmed");

        HttpResponse<String> confirmed =
                confirmUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));

        assertUnlinkDenied(confirmed);
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(actionGrantCount()).isZero();
        // 세션도 회전하지 않았다.
        assertThat(sessionCookieOf(confirmed)).isEmpty();
    }

    @Test
    void 대상이_다른_확인은_무장을_없애지_않는다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-wrong-target");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);

        // Google을 겨눈 확인이다. 무장은 네이버의 것이므로 이 확인의 사실이 아니다.
        assertUnlinkDenied(confirmUnlink(GOOGLE, user.cookie(), csrfTokenOf(user.cookie())));

        /*
         * 엉뚱한 확인 하나가 살아 있는 의도를 없애지 않는다. 없애면 그 자체가 방해 경로이고,
         * 사용자는 대화상자에서 확인을 눌러도 이유 없이 실패한다.
         */
        assertThat(sessionAttribute(sessionIdOf(user.cookie()), UnlinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(UnlinkFlow.Armed.class);
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(consumedGrantCount()).isZero();

        // 맞는 대상으로는 그대로 성립한다.
        assertThat(confirmUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 무장이_만료되면_확인이_거부된다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-expired-flow");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        ageUnlinkFlow(sessionIdOf(user.cookie()), UnlinkFlow.ARMED_LIFETIME.plusMinutes(1));

        assertUnlinkDenied(confirmUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())));

        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(consumedGrantCount()).isZero();
        // 만료된 무장은 읽는 쪽이 버린다. 잊힌 값이 세션에 계속 실리지 않는다.
        assertThat(sessionAttribute(sessionIdOf(user.cookie()), UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
    }

    @Test
    void 허가가_만료되면_확인이_거부된다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-expired-grant");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        /*
         * 무장은 살아 있는데 허가가 죽었다. 두 수명은 서로 다른 상수이고 판정의 권위도 다르다 —
         * 무장은 애플리케이션이, 만료는 데이터베이스가 본다.
         */
        moveActionGrantExpiry(singleGrantReference(), Duration.ofSeconds(-1));

        assertUnlinkDenied(confirmUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())));

        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(consumedGrantCount()).isZero();
    }

    @Test
    void 흐름_도중_삭제가_시작되면_확인이_거부된다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-deleting");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        accountDeletion().beginDeletion(user.userId());

        assertUnlinkDenied(confirmUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())));

        // 매핑 행은 그대로다. 그 행을 지우는 것은 삭제 경로의 일이다.
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(consumedGrantCount()).isZero();
    }

    @Test
    void 확인_없이_이탈하면_아무것도_바뀌지_않는다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-abandoned");
        long sessionsBefore = sessionCountOf(user.userId());

        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);

        // 매핑·세션·연결 목록이 준비 전과 같다. 무장은 5분 뒤 만료된다.
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(linkedProvidersOf(user.userId())).containsExactlyInAnyOrder(GOOGLE, NAVER);
        assertThat(sessionCountOf(user.userId())).isEqualTo(sessionsBefore);
        assertThat(consumedGrantCount()).isZero();
        assertThat(linkStatus(user.cookie()).body())
                .isEqualTo("{\"providers\":[\"google\",\"naver\"]}");
    }

    @Test
    void 두_번째_준비가_앞의_허가를_무효화한다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-rearm");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        String first = singleGrantReference();

        assertThat(prepareUnlink(GOOGLE, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
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
    void 거절되는_준비는_앞선_무장과_허가를_그대로_둔다() throws Exception {
        /*
         * 무장을 버리는 일이 게이트보다 앞에 있으면, 마지막 수단의 해제를 눌러 본 사용자가 방금 연
         * 대화상자를 잃는다 — 403 하나가 진행 중이던 흐름을 파괴한다. 같은 파일이
         * `대상이_다른_확인은_무장을_없애지_않는다`로 정확히 그 반대를 계약으로 두었다.
         */
        Linked user = signInLinkAndReauthenticate("unlink-rejected-keeps");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        String armed = singleGrantReference();
        String sessionId = sessionIdOf(user.cookie());

        // 마지막 수단이 아니라 **연결되지 않은** 제공자다. 게이트가 닫히는 다른 축도 같아야 한다.
        assertUnlinkDenied(prepareUnlink(KAKAO, user.cookie(), csrfTokenOf(user.cookie())));

        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(UnlinkFlow.Armed.class);
        assertThat(actionGrantExists(armed)).isTrue();
        assertThat(actionGrantCount()).isEqualTo(1);

        // 그리고 그 무장은 여전히 쓸 수 있다.
        assertThat(confirmUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 거절되는_연결_시작은_해제_무장을_그대로_둔다() throws Exception {
        /*
         * 상대 흐름을 버리는 일도 게이트 뒤에 있어야 한다. 이미 연결된 제공자로 연결을 눌러 본
         * 사용자가 열어 둔 해제 대화상자를 잃으면 안 된다.
         */
        Linked user = signInLinkAndReauthenticate("unlink-kept-by-link");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        String armed = singleGrantReference();
        String sessionId = sessionIdOf(user.cookie());

        // Google은 이미 연결돼 있다. 연결 시작이 그 게이트에서 거절된다.
        assertUnlinkDenied(startLink(GOOGLE, user.cookie(), csrfTokenOf(user.cookie())));

        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(UnlinkFlow.Armed.class);
        assertThat(actionGrantExists(armed)).isTrue();
        assertThat(sessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE)).isNull();
    }

    @Test
    void 거절되는_해제_준비는_연결_무장을_그대로_둔다() throws Exception {
        /*
         * 위 둘의 거울이다. 상대 흐름을 버리는 일이 게이트 앞에 있으면, 마지막 수단의 해제를 눌러 본
         * 사용자가 방금 시작한 연결 흐름과 그 허가를 잃는다 — 그 다음 콜백은 무장이 없어 실패하고
         * 사용자는 이유를 알 수 없다.
         */
        Linked user = signInLinkAndReauthenticate("unlink-rejected-keeps-link");
        assertThat(startLink(KAKAO, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        String armed = singleGrantReference();
        String sessionId = sessionIdOf(user.cookie());

        // 카카오는 연결되지 않았으므로 해제 준비가 그 게이트에서 거절된다.
        assertUnlinkDenied(prepareUnlink(KAKAO, user.cookie(), csrfTokenOf(user.cookie())));

        assertThat(sessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(LinkFlow.Armed.class);
        assertThat(actionGrantExists(armed)).isTrue();
        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
    }

    // ----------------------------------------------------------------------
    // 취소
    // ----------------------------------------------------------------------

    @Test
    void 취소가_무장과_허가를_되돌린다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-cancel");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        String sessionId = sessionIdOf(user.cookie());

        HttpResponse<String> cancelled =
                cancelUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));

        assertThat(cancelled.statusCode()).isEqualTo(204);
        assertThat(cancelled.body()).isEmpty();
        /*
         * 준비가 만든 둘이 함께 사라진다. 흐름만 버리면 아무도 참조할 수 없는 허가가 수명을 살고,
         * 화면의 "바뀐 것은 없습니다"도 거짓이 된다.
         */
        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(actionGrantCount()).isZero();
        // 매핑·세션은 애초에 바뀐 적이 없다.
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(sessionCountOf(user.userId())).isEqualTo(1);

        // 되돌린 뒤의 확인은 소비할 것이 없다.
        assertUnlinkDenied(confirmUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())));
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 오래된_탭의_취소는_다른_세션의_허가를_지우지_않는다() throws Exception {
        /*
         * I/O 표 "오래된 탭 취소". 같은 사용자의 세션 B가 같은 제공자로 준비하면 그 발급이 세션 A의 허가를
         * `(user, command, target)` 단위로 대신한다("같은 작업의 살아 있는 허가는 하나"). 그 뒤 세션 A의 취소는
         * **자기 무장이 든 참조만** 지우므로 B의 허가는 살아 있고 B의 확정이 성공한다 — 옛 `(user, command,
         * target)` 단위 취소였다면 A의 취소가 B의 허가까지 지워 아래 확정이 거부됐다.
         */
        Linked first = signInLinkAndReauthenticate("unlink-two-sessions");
        String cookie = sessionCookieOf(signIn(first.subject())).orElseThrow();
        String second =
                sessionCookieOf(
                                reauthenticate(
                                        GOOGLE, cookie, csrfTokenOf(cookie), plan -> plan.subject(first.subject())))
                        .orElseThrow();
        assertThat(sessionCountOf(first.userId())).isEqualTo(2);

        assertThat(prepareUnlink(NAVER, first.cookie(), csrfTokenOf(first.cookie())).statusCode()).isEqualTo(204);
        assertThat(prepareUnlink(NAVER, second, csrfTokenOf(second)).statusCode()).isEqualTo(204);
        // B의 발급이 A의 허가를 대신했다. 살아 있는 허가는 B의 것 하나다.
        String live = singleGrantReference();

        assertThat(cancelUnlink(NAVER, first.cookie(), csrfTokenOf(first.cookie())).statusCode()).isEqualTo(204);
        assertThat(sessionAttribute(sessionIdOf(first.cookie()), UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(actionGrantExists(live)).as("다른 세션의 허가").isTrue();
        assertThat(sessionAttribute(sessionIdOf(second), UnlinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(UnlinkFlow.Armed.class);

        assertThat(confirmUnlink(NAVER, second, csrfTokenOf(second)).statusCode()).isEqualTo(204);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(actionGrantConsumed(live)).isTrue();
    }

    @Test
    void 취소는_무장이_없어도_204이고_아무것도_알려_주지_않는다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-cancel-empty");

        /*
         * 무장이 있었는지로 답을 나누지 않는다. 나누면 이 경로가 "저 세션이 지금 무엇을 준비했는가"를
         * 묻는 오라클이 된다. 그래서 멱등이고, 아무것도 없을 때 부르면 아무 일도 하지 않는다.
         */
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> cancelled =
                    cancelUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie()));
            assertThat(cancelled.statusCode()).isEqualTo(204);
            assertThat(cancelled.body()).isEmpty();
        }
        assertThat(socialLinkCount()).isEqualTo(2);
        assertThat(actionGrantCount()).isZero();
    }

    @Test
    void 취소도_토큰과_동일_출처를_요구하고_미지_제공자는_404다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-cancel-protected");
        String token = csrfTokenOf(user.cookie());
        assertThat(prepareUnlink(NAVER, user.cookie(), token).statusCode()).isEqualTo(204);

        assertUnlinkDenied(
                cancelUnlink(NAVER, user.cookie(), null, OidcStubConfiguration.PUBLIC_BASE_URL));
        assertUnlinkDenied(cancelUnlink(NAVER, user.cookie(), token, "https://evil.example"));

        HttpResponse<String> unknown = cancelUnlink("nosuchprovider", user.cookie(), token);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("\"code\":\"NOT_FOUND\"");

        // 거절된 취소는 무장을 건드리지 않는다.
        assertThat(sessionAttribute(sessionIdOf(user.cookie()), UnlinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(UnlinkFlow.Armed.class);
        assertThat(actionGrantCount()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------
    // 세 흐름이 서로를 버린다
    // ----------------------------------------------------------------------

    @Test
    void 해제_무장이_연결_시작에_버려진다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-then-link");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);
        assertThat(actionGrantCount()).isEqualTo(1);

        assertThat(startLink(KAKAO, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);

        String sessionId = sessionIdOf(user.cookie());
        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
        // 흐름도 허가도 함께 사라진다. 남은 허가 하나는 방금 연결 시작이 발급한 것이다.
        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(sessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(LinkFlow.Armed.class);
    }

    @Test
    void 해제_무장이_재인증_시작에_버려진다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-then-reauth");
        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);

        assertThat(
                        startReauthentication(GOOGLE, user.cookie(), csrfTokenOf(user.cookie()))
                                .statusCode())
                .isEqualTo(204);

        String sessionId = sessionIdOf(user.cookie());
        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(actionGrantCount()).isZero();
        assertThat(sessionAttribute(sessionId, ReauthenticationFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(ReauthenticationFlow.Armed.class);
    }

    @Test
    void 연결_무장이_해제_준비에_버려진다() throws Exception {
        Linked user = signInLinkAndReauthenticate("link-then-unlink");
        assertThat(startLink(KAKAO, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);

        assertThat(prepareUnlink(NAVER, user.cookie(), csrfTokenOf(user.cookie())).statusCode())
                .isEqualTo(204);

        String sessionId = sessionIdOf(user.cookie());
        assertThat(sessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE)).isNull();
        // 버려진 연결 무장의 허가도 함께 사라진다. 남은 하나는 해제 준비가 발급한 것이다.
        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(UnlinkFlow.Armed.class);
    }

    // ----------------------------------------------------------------------
    // 보호와 경로
    // ----------------------------------------------------------------------

    @Test
    void 토큰_없는_해제는_403이다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-no-token");

        assertUnlinkDenied(
                prepareUnlink(NAVER, user.cookie(), null, OidcStubConfiguration.PUBLIC_BASE_URL));
        assertUnlinkDenied(
                confirmUnlink(NAVER, user.cookie(), null, OidcStubConfiguration.PUBLIC_BASE_URL));
        assertThat(actionGrantCount()).isZero();
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 교차_출처_해제는_403이다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-cross-origin");
        String token = csrfTokenOf(user.cookie());

        assertUnlinkDenied(prepareUnlink(NAVER, user.cookie(), token, "https://evil.example"));
        assertUnlinkDenied(confirmUnlink(NAVER, user.cookie(), token, "https://evil.example"));
        assertThat(actionGrantCount()).isZero();
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 미지_제공자의_해제는_404이고_아는_이름을_드러내지_않는다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-unknown");
        String token = csrfTokenOf(user.cookie());

        for (HttpResponse<String> response :
                List.of(
                        prepareUnlink("nosuchprovider", user.cookie(), token),
                        confirmUnlink("nosuchprovider", user.cookie(), token))) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
            // 값을 되비추지도, 아는 제공자 목록·개수를 실지도 않는다.
            assertThat(response.body()).doesNotContain("nosuchprovider");
            assertThat(response.body()).doesNotContain(NAVER);
        }
        assertThat(actionGrantCount()).isZero();
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    @Test
    void 안전한_메서드로는_해제할_수_없다() throws Exception {
        Linked user = signInLinkAndReauthenticate("unlink-method");

        HttpResponse<String> unsafe =
                get(AuthSecurityConfig.UNLINK_BASE_URI + "/" + NAVER, user.cookie());

        // 그 경로는 있고 메서드가 다르다. 해제는 안전한 메서드가 될 수 없다는 사실이 이 405다.
        assertThat(unsafe.statusCode()).isEqualTo(405);
        assertThat(unsafe.body()).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    // ----------------------------------------------------------------------
    // 멱등
    // ----------------------------------------------------------------------

    @Test
    void 같은_operation_id_재전송은_저장된_결과를_돌려주고_매핑을_다시_지우지_않는다() {
        /*
         * HTTP를 지나지 않는 유일한 축이다. 해제 무장은 최종 확인 하나로 소비되므로 같은 무장으로
         * 두 번째 요청을 보낼 길이 없고, 그래서 멱등 경로를 관측하려면 포트를 직접 두 번 불러야
         * 한다. 멱등의 권위는 1.9의 `(user_id, operation_id)` 유일 인덱스 그대로다.
         */
        UUID userId = accountLifecycle().createAccount();
        for (String provider : List.of(GOOGLE, NAVER)) {
            accountLifecycle()
                    .linkSocialIdentity(
                            userId,
                            provider,
                            OidcStubConfiguration.issuer(provider),
                            "idem-unlink-" + provider + "-" + UUID.randomUUID());
        }
        MockHttpSession session = grantSession(UUID.randomUUID().toString(), userId);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        assertThat(unlinkService().start(session, userId, NAVER)).isPresent();
        UnlinkFlow.Armed armed =
                (UnlinkFlow.Armed) session.getAttribute(UnlinkFlow.FLOW_ATTRIBUTE);

        assertThat(unlinkService().complete(request, session, userId, NAVER))
                .isEqualTo(UnlinkService.Outcome.UNLINKED);
        assertThat(socialLinkCount()).isEqualTo(1);

        /*
         * 같은 요청의 재전송이다. 무장은 소비됐으므로 저장소의 사실을 그대로 다시 심어 그 요청을
         * 재현한다 — 응답을 잃은 클라이언트가 같은 값을 다시 보내는 모양이다.
         */
        UnlinkFlow.arm(session, armed);
        assertThat(unlinkService().complete(request, session, userId, NAVER))
                .isEqualTo(UnlinkService.Outcome.UNLINKED);
        // 업무 변경은 다시 실행되지 않았다. 마지막 수단이 사라지지 않은 것이 그 증거다.
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(consumedGrantCount()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------
    // 단정 도구
    // ----------------------------------------------------------------------

    /**
     * 로그인·연결·재인증을 마친 사용자 하나.
     *
     * @param subject Google 스텁에서 받은 외부 식별자
     * @param naverSubject 네이버 스텁에서 받은 외부 식별자. 연결되지 않은 축에서는 빈 문자열이다
     * @param cookie 마지막 조작으로 회전한 뒤의 세션 쿠키
     */
    private record Linked(UUID userId, String subject, String naverSubject, String cookie) {}

    /** Google로만 가입한 사용자. 다른 사용자의 세션을 만드는 데 쓴다. */
    private Linked signInOnly(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        return new Linked(userId, subject, "", cookie);
    }

    /** Google로 가입하고 곧바로 재인증까지 마친 사용자. 수단은 하나뿐이다. */
    private Linked signInAndReauthenticate(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String rotated =
                sessionCookieOf(
                                reauthenticate(
                                        GOOGLE, cookie, csrfTokenOf(cookie), plan -> plan.subject(subject)))
                        .orElseThrow();
        return new Linked(userId, subject, "", rotated);
    }

    /**
     * Google로 가입하고 네이버가 연결된 사용자. <b>재인증은 하지 않는다.</b>
     *
     * <p>네이버 매핑은 {@code identity} 공개 API로 직접 붙인다. 연결 왕복을 지나면 그 왕복이 요구하는
     * 재인증 때문에 세션에 신선한 기록이 남아, "재인증이 없는 상태"를 이 층에서 만들 수 없다. 연결
     * 경로 자체는 {@code AuthLinkProviderTest}가 이미 고정하므로 여기서 복제하지 않는다.
     */
    private Linked signInWithTwoMethods(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String naverSubject = prefix + "-naver-" + UUID.randomUUID();
        assertThat(
                        accountLifecycle()
                                .linkSocialIdentity(
                                        userId,
                                        NAVER,
                                        OidcStubConfiguration.issuer(NAVER),
                                        naverSubject))
                .isEqualTo(AccessDecision.ALLOWED);
        return new Linked(userId, subject, naverSubject, cookie);
    }

    /**
     * 위에 재인증을 더한 사용자. <b>해제의 전제가 정확히 이 상태다.</b>
     *
     * <p>세션에 재인증 시각이 기록돼 있어야 허가가 발급된다. 일반 로그인은 그 값을 남기지 않으므로
     * (Story 1.8) 두 단계를 한 곳에 둔다.
     */
    private Linked signInLinkAndReauthenticate(String prefix) throws Exception {
        Linked base = signInWithTwoMethods(prefix);
        String rotated =
                sessionCookieOf(
                                reauthenticate(
                                        GOOGLE,
                                        base.cookie(),
                                        csrfTokenOf(base.cookie()),
                                        plan -> plan.subject(base.subject())))
                        .orElseThrow();
        return new Linked(base.userId(), base.subject(), base.naverSubject(), rotated);
    }

    /** 해제가 거부됐는가. 모든 거부가 사유를 구분하지 않는 같은 하나다. */
    private static void assertUnlinkDenied(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(response.body()).contains("\"correlationId\"");
    }

    /** 그 계정에 연결된 제공자. {@code identity} 공개 API가 돌려주는 값 그대로다. */
    private List<String> linkedProvidersOf(UUID userId) {
        return List.copyOf(accountLifecycle().findLinkedProviders(userId));
    }

    /** 소비된 허가 행 수. "허가만 소비되고 매핑이 남은" 반쪽 상태를 관측하는 창이다. */
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
     * 무장된 해제 흐름의 무장 시각을 과거로 옮긴다.
     *
     * <p>{@code AuthFlowTestBase.ageReauthenticationFlow}와 같은 근거의 같은 모양이다 — 시험이 5분을
     * 기다릴 수 없고, 수명을 줄이는 설정을 따로 두면 그 설정이 검증하는 값과 제품이 쓰는 값이 갈린다.
     */
    private void ageUnlinkFlow(String sessionId, Duration elapsed) {
        Object stored = sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE);
        if (!(stored instanceof UnlinkFlow.Armed armed)) {
            throw new IllegalStateException("무장되지 않은 세션이다: " + sessionId);
        }
        setSessionAttribute(
                sessionId,
                UnlinkFlow.FLOW_ATTRIBUTE,
                new UnlinkFlow.Armed(
                        armed.targetProvider(),
                        armed.userId(),
                        armed.grantReference(),
                        armed.operationId(),
                        armed.armedAt().minus(elapsed)));
    }
}
