package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.identity.AccessDecision;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 네 흐름이 서로를 버리되 게이트를 통과한 뒤에만 버린다(Story 2.1).
 *
 * <p>Story 1.11이 세 흐름에 대해 같은 절을 두었고({@code AuthUnlinkProviderTest}) 이 스토리가 네
 * 번째를 더했다. 설정 화면이 네 조작을 같은 페이지에 그리므로 무장이 겹칠 수 있고, 겹치면 콜백이나
 * 확인이 엉뚱한 흐름을 소비하거나 참조할 수 없게 된 허가가 수명을 산다.
 *
 * <p>축이 셋이다. Bybit 준비가 나머지 셋을 버리는가, 나머지 셋의 시작이 Bybit 무장을 버리는가,
 * 그리고 <b>거절되는</b> 시작이 남의 무장을 그대로 두는가. 마지막 것이 1.11의 리뷰가 잡은 결함의
 * 축이며 — 실패해야 할 뿐인 요청 하나가 다른 탭의 살아 있는 의도를 무음으로 파괴하는 모양이다.
 *
 * <p>무장과 허가를 <b>함께</b> 본다. 흐름만 버리면 그것이 가리키던 허가가 아무도 참조할 수 없는
 * 채로 수명을 살고, 그 상태는 응답으로 관측되지 않는다.
 */
class BybitConnectionFlowTest extends AuthFlowTestBase {

    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    private static final String NAVER = AuthSecurityConfig.NAVER_REGISTRATION_ID;

    private static final String KAKAO = AuthSecurityConfig.KAKAO_REGISTRATION_ID;

    // ----------------------------------------------------------------------
    // Bybit 준비가 나머지를 버린다
    // ----------------------------------------------------------------------

    @Test
    void Bybit_준비가_해제_무장과_그_허가를_버린다() throws Exception {
        Ready user = ready("bybit-over-unlink");
        assertThat(prepareUnlink(NAVER, user.cookie(), user.csrfToken()).statusCode()).isEqualTo(204);
        assertThat(actionGrantCount()).isEqualTo(1);

        assertThat(prepareBybitConnection(user.cookie(), user.csrfToken()).statusCode()).isEqualTo(200);

        String sessionId = sessionIdOf(user.cookie());
        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
        // 남은 허가 하나는 방금 Bybit 준비가 발급한 것이다. 해제 허가는 함께 사라졌다.
        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(grantCountOf(BybitConnectionService.COMMAND)).isEqualTo(1);
        assertThat(sessionAttribute(sessionId, BybitConnectionFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(BybitConnectionFlow.Armed.class);
    }

    @Test
    void Bybit_준비가_연결_무장과_그_허가를_버린다() throws Exception {
        Ready user = ready("bybit-over-link");
        assertThat(startLink(KAKAO, user.cookie(), user.csrfToken()).statusCode()).isEqualTo(204);
        assertThat(actionGrantCount()).isEqualTo(1);

        assertThat(prepareBybitConnection(user.cookie(), user.csrfToken()).statusCode()).isEqualTo(200);

        String sessionId = sessionIdOf(user.cookie());
        assertThat(sessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(grantCountOf(BybitConnectionService.COMMAND)).isEqualTo(1);
    }

    @Test
    void Bybit_준비가_재인증_무장을_버린다() throws Exception {
        Ready user = ready("bybit-over-reauth");
        assertThat(startReauthentication(GOOGLE, user.cookie(), user.csrfToken()).statusCode())
                .isEqualTo(204);
        String sessionId = sessionIdOf(user.cookie());
        assertThat(sessionAttribute(sessionId, ReauthenticationFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(ReauthenticationFlow.Armed.class);

        assertThat(prepareBybitConnection(user.cookie(), user.csrfToken()).statusCode()).isEqualTo(200);

        /*
         * 준비가 성립한다는 것은 재인증이 이미 끝났다는 뜻이다(그 시각이 없으면 허가가 서지
         * 않는다). 남아 있는 재인증 무장은 완료되지 않은 옛 의도이므로 버린다.
         */
        assertThat(sessionAttribute(sessionId, ReauthenticationFlow.FLOW_ATTRIBUTE)).isNull();
    }

    // ----------------------------------------------------------------------
    // 나머지가 Bybit 무장을 버린다
    // ----------------------------------------------------------------------

    @Test
    void Bybit_무장이_연결_시작에_버려진다() throws Exception {
        Ready user = armedWithBybit("link-over-bybit");

        assertThat(startLink(KAKAO, user.cookie(), user.csrfToken()).statusCode()).isEqualTo(204);

        String sessionId = sessionIdOf(user.cookie());
        assertThat(sessionAttribute(sessionId, BybitConnectionFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(grantCountOf(BybitConnectionService.COMMAND)).isZero();
        assertThat(actionGrantCount()).isEqualTo(1);
    }

    @Test
    void Bybit_무장이_해제_준비에_버려진다() throws Exception {
        Ready user = armedWithBybit("unlink-over-bybit");

        assertThat(prepareUnlink(NAVER, user.cookie(), user.csrfToken()).statusCode()).isEqualTo(204);

        String sessionId = sessionIdOf(user.cookie());
        assertThat(sessionAttribute(sessionId, BybitConnectionFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(grantCountOf(BybitConnectionService.COMMAND)).isZero();
        assertThat(actionGrantCount()).isEqualTo(1);
    }

    @Test
    void Bybit_무장이_재인증_시작에_버려진다() throws Exception {
        Ready user = armedWithBybit("reauth-over-bybit");

        assertThat(startReauthentication(GOOGLE, user.cookie(), user.csrfToken()).statusCode())
                .isEqualTo(204);

        String sessionId = sessionIdOf(user.cookie());
        assertThat(sessionAttribute(sessionId, BybitConnectionFlow.FLOW_ATTRIBUTE)).isNull();
        // 재인증 무장에는 허가가 없다. Bybit 허가가 함께 사라져 남는 행이 0이다.
        assertThat(actionGrantCount()).isZero();
    }

    // ----------------------------------------------------------------------
    // 거절되는 시작은 남의 무장을 그대로 둔다
    // ----------------------------------------------------------------------

    @Test
    void 거절되는_Bybit_준비는_해제_무장을_그대로_둔다() throws Exception {
        /*
         * 1.11의 리뷰가 잡은 결함의 축이다. 실패해야 할 뿐인 요청 하나가 다른 탭의 살아 있는
         * 의도를 무음으로 파괴하면 안 된다.
         */
        Ready user = ready("rejected-bybit-keeps-unlink");
        assertThat(prepareUnlink(NAVER, user.cookie(), user.csrfToken()).statusCode()).isEqualTo(204);
        String armed = singleGrantReference();
        String sessionId = sessionIdOf(user.cookie());

        // 운영 제한을 켠다. Bybit 준비가 그 게이트에서 거절된다.
        restrictGlobally(com.bokrilog.operations.RestrictionFeature.NEW_CONNECTION);
        HttpResponse<String> rejected = prepareBybitConnection(user.cookie(), user.csrfToken());
        assertThat(rejected.statusCode()).isEqualTo(403);

        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(UnlinkFlow.Armed.class);
        assertThat(actionGrantExists(armed)).isTrue();
        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(sessionAttribute(sessionId, BybitConnectionFlow.FLOW_ATTRIBUTE)).isNull();

        // 그리고 그 무장은 여전히 쓸 수 있다.
        assertThat(confirmUnlink(NAVER, user.cookie(), user.csrfToken()).statusCode()).isEqualTo(204);
    }

    @Test
    void 거절되는_연결_시작은_Bybit_무장을_그대로_둔다() throws Exception {
        Ready user = armedWithBybit("rejected-link-keeps-bybit");
        String armed = singleGrantReference();
        String sessionId = sessionIdOf(user.cookie());

        // Google은 이미 연결돼 있다. 연결 시작이 그 게이트에서 거절된다.
        assertThat(startLink(GOOGLE, user.cookie(), user.csrfToken()).statusCode()).isEqualTo(403);

        assertThat(sessionAttribute(sessionId, BybitConnectionFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(BybitConnectionFlow.Armed.class);
        assertThat(actionGrantExists(armed)).isTrue();
        assertThat(sessionAttribute(sessionId, LinkFlow.FLOW_ATTRIBUTE)).isNull();
    }

    @Test
    void 거절되는_해제_준비는_Bybit_무장을_그대로_둔다() throws Exception {
        Ready user = armedWithBybit("rejected-unlink-keeps-bybit");
        String armed = singleGrantReference();
        String sessionId = sessionIdOf(user.cookie());

        // 카카오는 연결되지 않았으므로 해제 준비가 그 게이트에서 거절된다.
        assertThat(prepareUnlink(KAKAO, user.cookie(), user.csrfToken()).statusCode()).isEqualTo(403);

        assertThat(sessionAttribute(sessionId, BybitConnectionFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(BybitConnectionFlow.Armed.class);
        assertThat(actionGrantExists(armed)).isTrue();
        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
    }

    // ----------------------------------------------------------------------
    // 단정 도구
    // ----------------------------------------------------------------------

    /**
     * 로그인 수단 둘과 신선한 재인증을 가진 사용자.
     *
     * <p>네 흐름이 모두 성립하려면 이 상태여야 한다 — 해제 준비는 수단이 둘 이상이어야 하고, 연결
     * 시작은 아직 연결되지 않은 제공자가 있어야 하며, 셋 다 최근 본인 확인을 요구한다
     * ({@code AuthUnlinkProviderTest}의 같은 레시피).
     */
    private record Ready(UUID userId, String subject, String cookie, String csrfToken) {}

    private Ready ready(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(
                        accountLifecycle()
                                .linkSocialIdentity(
                                        userId,
                                        NAVER,
                                        OidcStubConfiguration.issuer(NAVER),
                                        prefix + "-naver-" + UUID.randomUUID()))
                .isEqualTo(AccessDecision.ALLOWED);
        String rotated =
                sessionCookieOf(
                                reauthenticate(
                                        GOOGLE, cookie, csrfTokenOf(cookie), plan -> plan.subject(subject)))
                        .orElseThrow();
        return new Ready(userId, subject, rotated, csrfTokenOf(rotated));
    }

    /** 위에 Bybit 준비를 더한 상태. 무장 하나와 허가 하나가 있다. */
    private Ready armedWithBybit(String prefix) throws Exception {
        Ready user = ready(prefix);
        assertThat(prepareBybitConnection(user.cookie(), user.csrfToken()).statusCode()).isEqualTo(200);
        return user;
    }

    /** 살아 있는 허가가 정확히 하나일 때 그 참조. 둘이면 이 조회가 소리 내어 실패한다. */
    private String singleGrantReference() {
        return jdbc().sql("SELECT grant_id FROM action_grant").query(String.class).single();
    }

    /** 그 command의 허가 행 수. 어느 흐름의 허가가 남았는지를 이름으로 가른다. */
    private long grantCountOf(String command) {
        return jdbc()
                .sql("SELECT count(*) FROM action_grant WHERE command = :command")
                .param("command", command)
                .query(Long.class)
                .single();
    }
}
