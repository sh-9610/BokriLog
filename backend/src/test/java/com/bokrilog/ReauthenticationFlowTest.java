package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.ReauthenticationFlow.Armed;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 재인증의 두 시간 경계.
 *
 * <p>무장 수명과 {@code auth_time} 신선도는 값 판정 둘뿐이고 저장소도 서블릿도 필요하지 않다.
 * 그래서 Spring 컨텍스트도 Docker도 쓰지 않는다({@code DevOidcStubTest}·
 * {@code AuthSessionStateJsonTest}와 같은 자리).
 *
 * <p>왕복 시험이 이 경계를 볼 수 없다는 것이 이 파일의 존재 이유다. HTTP 왕복은 초 단위로
 * 끝나므로 무장은 언제나 살아 있고 {@code auth_time}은 언제나 방금이다 — 그 축에서는
 * {@link ReauthenticationFlow#ARMED_LIFETIME}을 무한대로, {@link ReauthenticationFlow#CLOCK_SKEW}를
 * 0으로 바꿔도 아무 시험이 실패하지 않는다. 경계의 양쪽을 직접 고정하는 자리가 필요하다.
 *
 * <p><b>경계를 리터럴로 적는다.</b> 상수에서 유도하면 시험이 상수에 대해 항진명제가 된다 —
 * {@code CLOCK_SKEW}를 {@code Duration.ZERO}로 바꿔도 "창 + skew 안"의 좌표가 함께 움직여 그대로
 * 통과한다(이 파일의 첫 초안이 정확히 그랬다). 값을 손으로 적으면 정책이 바뀌는 날 이 시험이
 * 소리 내어 실패하고, 그것이 옳다 — SPEC은 신선도 창 5분과 clock skew 60초의 변경을
 * <b>Ask First</b>로 두었으므로 조용히 바뀌어서는 안 되는 값이다.
 *
 * <p>그래서 아래 {@link #정책_값은_SPEC이_소유한다}가 상수 셋을 직접 고정하고, 경계 축들은 그
 * 값에서 나오는 좌표를 리터럴로 쓴다.
 */
class ReauthenticationFlowTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    // ----------------------------------------------------------------------
    // 정책 값
    // ----------------------------------------------------------------------

    @Test
    void 정책_값은_SPEC이_소유한다() {
        /*
         * 세 값의 변경은 SPEC의 Ask First다. 여기서 직접 고정하지 않으면 아래 경계 축들이
         * 상수에서 좌표를 유도하게 되고, 그때 이 파일은 상수에 대한 항진명제가 되어 값이 조용히
         * 바뀌어도 통과한다.
         */
        assertThat(ReauthenticationFlow.ARMED_LIFETIME).isEqualTo(Duration.ofMinutes(5));
        assertThat(ReauthenticationFlow.FRESHNESS_WINDOW).isEqualTo(Duration.ofMinutes(5));
        assertThat(ReauthenticationFlow.CLOCK_SKEW).isEqualTo(Duration.ofSeconds(60));
        /*
         * 창과 보내는 `max_age`가 같은 값이다. 갈리면 제공자가 통과시킨 인증을 우리가 거절하거나
         * 그 반대가 된다.
         */
        assertThat(ReauthenticationFlow.MAX_AGE_SECONDS).isEqualTo(300L);
    }

    // ----------------------------------------------------------------------
    // 무장 수명
    // ----------------------------------------------------------------------

    @Test
    void 무장은_수명_안에서만_살아_있다() {
        // 막 무장했다.
        assertThat(armedAt(NOW).isLive(NOW)).isTrue();
        // 수명(5분) 안. 정상 왕복 전부가 여기 있다.
        assertThat(armedAt(NOW.minusSeconds(299)).isLive(NOW)).isTrue();
        /*
         * 정확히 수명이 지난 순간부터 죽는다. 경계를 포함하지 않는 쪽으로 정한 것이 fail-closed
         * 방향이며, `isLive`가 언제나 참을 돌려주게 바뀌면 이 두 단정이 먼저 깨진다.
         */
        assertThat(armedAt(NOW.minusSeconds(300)).isLive(NOW)).isFalse();
        assertThat(armedAt(NOW.minusSeconds(301)).isLive(NOW)).isFalse();
    }

    @Test
    void 수명이_지난_무장도_값을_그대로_들고_있다() {
        /*
         * 만료된 무장을 접는 것은 `AuthFlowHandlers`의 인증 흐름 경계이고, 실제 왕복은
         * `AuthReauthenticationTest.무장이_만료된_뒤_도착한_콜백은_이유와_함께_수렴한다`가 본다.
         * 그 경계가 이유를 말하려면 복귀 목적지를 잃지 않아야 한다 — 만료를 읽는 순간 값을
         * 비우면 사용자를 어디로 되돌릴지 알 수 없고, 그때 남는 선택은 조용히 튕기는 것뿐이다.
         */
        Armed expired = armedAt(NOW.minusSeconds(360));

        assertThat(expired.isLive(NOW)).isFalse();
        assertThat(expired.returnPath()).isEqualTo(AuthFlowHandlers.DASHBOARD_PATH);
        assertThat(expired.provider()).isEqualTo(AuthSecurityConfig.GOOGLE_REGISTRATION_ID);
    }

    // ----------------------------------------------------------------------
    // `auth_time` 신선도
    // ----------------------------------------------------------------------

    @Test
    void 증거가_없으면_완화하지_않는다() {
        // 제공자가 무엇을 공표했든 판정은 claim 하나다. 네이버가 여기로 수렴할 수 있다.
        assertThat(ReauthenticationFlow.isFreshAuthentication(null, NOW)).isFalse();
    }

    @Test
    void 과거_쪽_경계는_창과_skew의_합인_6분이다() {
        // 창(5분) 안. 정상 재인증이 여기 있다.
        assertThat(fresh(NOW.minusSeconds(240))).isTrue();
        // 창의 끝.
        assertThat(fresh(NOW.minusSeconds(300))).isTrue();
        /*
         * 창은 넘겼지만 skew(60초) 안이다. 이 한 축이 `CLOCK_SKEW`를 `Duration.ZERO`로 바꾸는
         * 변경을 붙잡는다 — 그렇게 바뀌면 여기가 거짓이 되고, 제공자가 `max_age`로 통과시킨
         * 인증을 우리가 초 단위 시계 차이로 거절하기 시작한다.
         */
        assertThat(fresh(NOW.minusSeconds(330))).isTrue();
        assertThat(fresh(NOW.minusSeconds(360))).isTrue();
        // 창 + skew 밖. 오래된 인증이다.
        assertThat(fresh(NOW.minusSeconds(361))).isFalse();
        assertThat(fresh(NOW.minusSeconds(600))).isFalse();
    }

    @Test
    void 미래_쪽_경계는_skew_하나인_60초다() {
        // skew 안의 미래. 두 시계가 정확히 같지 않으므로 이만큼은 받아들인다.
        assertThat(fresh(NOW.plusSeconds(30))).isTrue();
        assertThat(fresh(NOW.plusSeconds(60))).isTrue();
        /*
         * skew 밖의 미래. 시계 차이로 설명되지 않는 값이므로 신뢰하지 않는다 — 이 축이 없으면
         * 제공자가 임의의 미래를 주장해도 언제나 "신선함"이 된다.
         *
         * 과거 쪽(6분)과 미래 쪽(60초)이 서로 다른 값이라는 것이 여기서 함께 고정된다. 한 값으로
         * 합치면 미래 쪽이 6분까지 열린다.
         */
        assertThat(fresh(NOW.plusSeconds(61))).isFalse();
        assertThat(fresh(NOW.plusSeconds(330))).isFalse();
    }

    private static boolean fresh(Instant authenticatedAt) {
        return ReauthenticationFlow.isFreshAuthentication(authenticatedAt, NOW);
    }

    private static Armed armedAt(Instant at) {
        return new Armed(
                AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                UUID.randomUUID(),
                AuthFlowHandlers.DASHBOARD_PATH,
                at);
    }
}
