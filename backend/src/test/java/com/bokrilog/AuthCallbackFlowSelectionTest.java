package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * 검증을 통과한 콜백의 흐름 선택({@code AuthFlowHandlers.selectCallbackFlow}, deferred 191).
 *
 * <p><b>HTTP 왕복으로는 만들 수 없는 경합이다.</b> 흐름 경계가 재인증 무장을 살아 있다고 판정한 뒤 성공 핸들러 전에 그 무장이
 * 만료되는 창은 요청 하나 안의 몇 밀리초다. 그래서 판정을 순수 함수로 뽑아 시각을 직접 준다 — 만료는 "무장 시각 + 수명"을
 * 지난 {@code now}로 만든다. 이 판정이 성공 핸들러에서 실제로 실행되는 배선은
 * {@code AuthReauthenticationTest.만료된_재인증_무장의_콜백은_성공_핸들러에서_재인증_실패로_접힌다}가 본다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다({@code AuthFlowHandlersRotationTest}와 같은 이유 — 보는 것이 배선이 아니라 판정 하나다).
 * 사용자·경로·허가 참조 같은 값은 판정에 쓰이지 않지만 실행 시점에 만든다(시험의 토큰 모양 리터럴 금지).
 */
class AuthCallbackFlowSelectionTest {

    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    private static final String NAVER = AuthSecurityConfig.NAVER_REGISTRATION_ID;

    private static final String KAKAO = AuthSecurityConfig.KAKAO_REGISTRATION_ID;

    private static final Instant NOW = Instant.now();

    /** 판정 시각에 살아 있는 무장의 무장 시각. */
    private static final Instant LIVE_ARMED_AT = NOW.minus(Duration.ofMinutes(1));

    /** 판정 시각에 연결 무장의 수명이 막 끝나는 무장 시각. 수명 경계({@code now == armedAt + lifetime})는 만료다. */
    private static final Instant LINK_EXPIRED_ARMED_AT = NOW.minus(LinkFlow.ARMED_LIFETIME);

    /** 판정 시각에 재인증 무장의 수명이 막 끝나는 무장 시각. */
    private static final Instant REAUTHENTICATION_EXPIRED_ARMED_AT =
            NOW.minus(ReauthenticationFlow.ARMED_LIFETIME);

    @Test
    void 재인증_무장이_성공_핸들러_전에_만료되면_재인증_실패로_접는다() {
        ReauthenticationFlow.Armed expired = reauthentication(GOOGLE, REAUTHENTICATION_EXPIRED_ARMED_AT);
        assertThat(expired.isLive(NOW)).isFalse();

        AuthFlowHandlers.CallbackFlow flow = AuthFlowHandlers.selectCallbackFlow(null, expired, GOOGLE, NOW);

        // 로그인 분기가 아니다. 그 분기는 인증된 세션에 검증된 다른 계정을 싣는다.
        assertThat(flow).isEqualTo(new AuthFlowHandlers.CallbackFlow.ExpiredReauthentication(expired));
    }

    @Test
    void 살아_있는_재인증_무장은_재인증_분기다() {
        ReauthenticationFlow.Armed live = reauthentication(GOOGLE, LIVE_ARMED_AT);
        assertThat(live.isLive(NOW)).isTrue();

        assertThat(AuthFlowHandlers.selectCallbackFlow(null, live, GOOGLE, NOW))
                .isEqualTo(new AuthFlowHandlers.CallbackFlow.Reauthentication(live));
        // 제공자 바꿔치기도 재인증 분기가 받아 실패로 접는다(Story 1.8) — 로그인 분기로 가지 않는다.
        assertThat(AuthFlowHandlers.selectCallbackFlow(null, live, KAKAO, NOW))
                .isEqualTo(new AuthFlowHandlers.CallbackFlow.Reauthentication(live));
    }

    @Test
    void 무장이_하나도_없으면_로그인_분기다() {
        for (@Nullable String provider : callbackProviders()) {
            assertThat(AuthFlowHandlers.selectCallbackFlow(null, null, provider, NOW))
                    .as("callback=" + provider)
                    .isEqualTo(new AuthFlowHandlers.CallbackFlow.Login());
        }
    }

    @Test
    void 수명_경계_직전의_무장은_살아_있는_쪽_흐름이다() {
        // 경계 1ns 전은 살아 있고 경계 자체는 만료다. 두 Flow의 수명 식(`now < armedAt + lifetime`)이 그 선을 긋는다.
        ReauthenticationFlow.Armed justLive =
                reauthentication(GOOGLE, REAUTHENTICATION_EXPIRED_ARMED_AT.plusNanos(1));
        ReauthenticationFlow.Armed atBoundary = reauthentication(GOOGLE, REAUTHENTICATION_EXPIRED_ARMED_AT);
        assertThat(justLive.isLive(NOW)).isTrue();
        assertThat(atBoundary.isLive(NOW)).isFalse();

        assertThat(AuthFlowHandlers.selectCallbackFlow(null, justLive, GOOGLE, NOW))
                .isEqualTo(new AuthFlowHandlers.CallbackFlow.Reauthentication(justLive));
        assertThat(AuthFlowHandlers.selectCallbackFlow(null, atBoundary, GOOGLE, NOW))
                .isEqualTo(new AuthFlowHandlers.CallbackFlow.ExpiredReauthentication(atBoundary));

        // 연결 쪽도 같은 선이다. 선택은 수명과 무관하게 연결이고, 살아 있음은 `onLinkSuccess`가 같은 `now`로 다시 본다.
        LinkFlow.Armed linkJustLive = link(GOOGLE, LINK_EXPIRED_ARMED_AT.plusNanos(1));
        LinkFlow.Armed linkAtBoundary = link(GOOGLE, LINK_EXPIRED_ARMED_AT);
        assertThat(linkJustLive.isLive(NOW)).isTrue();
        assertThat(linkAtBoundary.isLive(NOW)).isFalse();
        assertThat(AuthFlowHandlers.selectCallbackFlow(linkJustLive, null, GOOGLE, NOW))
                .isEqualTo(new AuthFlowHandlers.CallbackFlow.Link(linkJustLive));
    }

    @Test
    void 무장의_모든_조합은_계산한_기대_흐름과_같고_무장이_있으면_로그인이_아니다() {
        List<LinkFlow.@Nullable Armed> links = linkOptions();
        List<ReauthenticationFlow.@Nullable Armed> reauthentications = reauthenticationOptions();
        List<@Nullable String> providers = callbackProviders();
        int armedInputs = 0;
        for (LinkFlow.@Nullable Armed link : links) {
            for (ReauthenticationFlow.@Nullable Armed reauthentication : reauthentications) {
                for (@Nullable String provider : providers) {
                    String combination =
                            "link=" + describe(link)
                                    + " reauth=" + describe(reauthentication)
                                    + " callback=" + provider;
                    AuthFlowHandlers.CallbackFlow flow =
                            AuthFlowHandlers.selectCallbackFlow(link, reauthentication, provider, NOW);

                    assertThat(flow).as(combination).isEqualTo(expected(link, reauthentication, provider));
                    if (link != null || reauthentication != null) {
                        assertThat(flow).as(combination).isNotInstanceOf(AuthFlowHandlers.CallbackFlow.Login.class);
                        armedInputs++;
                    }
                }
            }
        }
        /*
         * 서로 다른 입력만 센다. 무장 선택지는 각각 없음 + (살아 있음·만료) × (google·naver) = 5개이고, 둘 다 없는 1개를 빼면
         * 무장이 있는 조합이 5 × 5 − 1 = 24개다. 콜백 제공자는 google·naver·kakao·없음 넷이다.
         */
        assertThat(armedInputs).isEqualTo(24 * 4);
    }

    @Test
    void 무장이_모두_살아_있으면_연결과_재인증_중_제공자가_일치하는_쪽이_이긴다() {
        LinkFlow.Armed link = link(NAVER, LIVE_ARMED_AT);
        ReauthenticationFlow.Armed reauthentication = reauthentication(GOOGLE, LIVE_ARMED_AT);

        assertThat(AuthFlowHandlers.selectCallbackFlow(link, reauthentication, NAVER, NOW))
                .isEqualTo(new AuthFlowHandlers.CallbackFlow.Link(link));
        assertThat(AuthFlowHandlers.selectCallbackFlow(link, reauthentication, GOOGLE, NOW))
                .isEqualTo(new AuthFlowHandlers.CallbackFlow.Reauthentication(reauthentication));
        // 어느 쪽도 일치하지 않으면 연결이 먼저다(어느 흐름에서도 실패이고 순서가 정하는 것은 표지 하나다).
        assertThat(AuthFlowHandlers.selectCallbackFlow(link, reauthentication, KAKAO, NOW))
                .isEqualTo(new AuthFlowHandlers.CallbackFlow.Link(link));
    }

    @Test
    void 연결_무장만_있으면_제공자가_달라도_연결_분기가_받아_거부한다() {
        // 흐름 경계가 이미 접는 입력이다. 그래도 여기서 로그인으로 떨어지지 않는다 — 살아 있든 만료됐든.
        LinkFlow.Armed live = link(NAVER, LIVE_ARMED_AT);
        LinkFlow.Armed expired = link(NAVER, LINK_EXPIRED_ARMED_AT);
        assertThat(live.isLive(NOW)).isTrue();
        assertThat(expired.isLive(NOW)).isFalse();
        for (LinkFlow.Armed link : List.of(live, expired)) {
            assertThat(AuthFlowHandlers.selectCallbackFlow(link, null, GOOGLE, NOW))
                    .isEqualTo(new AuthFlowHandlers.CallbackFlow.Link(link));
            assertThat(AuthFlowHandlers.selectCallbackFlow(link, null, NAVER, NOW))
                    .isEqualTo(new AuthFlowHandlers.CallbackFlow.Link(link));
        }
    }

    @Test
    void 만료된_재인증_무장이_콜백과_맞으면_제공자가_다른_연결_무장을_소비하지_않는다() {
        /*
         * 흐름 경계는 이 콜백을 재인증의 것으로 보고 검증에 넘겼다(제공자가 맞는 쪽이 이긴다). 그 뒤 재인증 무장이 만료됐다고
         * 해서 다른 제공자를 노리는 연결 무장이 이 콜백을 집으면 그 연결이 남의 콜백으로 실패한다(Epic 1 회고 F3).
         */
        LinkFlow.Armed link = link(NAVER, LIVE_ARMED_AT);
        ReauthenticationFlow.Armed expired = reauthentication(GOOGLE, REAUTHENTICATION_EXPIRED_ARMED_AT);
        assertThat(expired.isLive(NOW)).isFalse();

        assertThat(AuthFlowHandlers.selectCallbackFlow(link, expired, GOOGLE, NOW))
                .isEqualTo(new AuthFlowHandlers.CallbackFlow.ExpiredReauthentication(expired));
    }

    @Test
    void 만료된_연결_무장이_콜백과_맞으면_제공자가_다른_재인증_무장을_소비하지_않는다() {
        // 위 시험의 거울이다. 경계가 연결의 것으로 넘긴 콜백은 연결 무장이 만료됐어도 연결 분기가 받아 거부한다.
        LinkFlow.Armed expired = link(GOOGLE, LINK_EXPIRED_ARMED_AT);
        ReauthenticationFlow.Armed reauthentication = reauthentication(NAVER, LIVE_ARMED_AT);
        assertThat(expired.isLive(NOW)).isFalse();
        assertThat(reauthentication.isLive(NOW)).isTrue();

        assertThat(AuthFlowHandlers.selectCallbackFlow(expired, reauthentication, GOOGLE, NOW))
                .isEqualTo(new AuthFlowHandlers.CallbackFlow.Link(expired));
    }

    /**
     * 기대 흐름을 선택 함수와 다른 모양으로 다시 적는다.
     *
     * <p>제공자가 일치하는 무장이 수명과 무관하게 이긴다 — 연결 일치가 먼저이고, 재인증 일치는 재인증(살아 있으면
     * {@code Reauthentication}, 만료면 {@code ExpiredReauthentication})이다. 둘 다 일치하지 않으면 지금 판정 그대로다 — 연결
     * 무장이 있으면 연결, 없고 재인증 무장이 있으면 재인증, 둘 다 없으면 로그인.
     */
    private static AuthFlowHandlers.CallbackFlow expected(
            LinkFlow.@Nullable Armed link,
            ReauthenticationFlow.@Nullable Armed reauthentication,
            @Nullable String provider) {
        boolean linkMatches = link != null && link.targetProvider().equals(provider);
        boolean reauthenticationMatches =
                reauthentication != null && reauthentication.provider().equals(provider);
        if (linkMatches) {
            return new AuthFlowHandlers.CallbackFlow.Link(link);
        }
        if (reauthenticationMatches) {
            return reauthenticationFlow(reauthentication);
        }
        if (link != null) {
            return new AuthFlowHandlers.CallbackFlow.Link(link);
        }
        if (reauthentication != null) {
            return reauthenticationFlow(reauthentication);
        }
        return new AuthFlowHandlers.CallbackFlow.Login();
    }

    private static AuthFlowHandlers.CallbackFlow reauthenticationFlow(ReauthenticationFlow.Armed armed) {
        return armed.isLive(NOW)
                ? new AuthFlowHandlers.CallbackFlow.Reauthentication(armed)
                : new AuthFlowHandlers.CallbackFlow.ExpiredReauthentication(armed);
    }

    /** 연결 무장 선택지 다섯 — 없음, (살아 있음·만료) × (google·naver). 만료 전제는 여기서 단정한다. */
    private static List<LinkFlow.@Nullable Armed> linkOptions() {
        List<LinkFlow.@Nullable Armed> options = new ArrayList<>();
        options.add(null);
        for (String provider : List.of(GOOGLE, NAVER)) {
            LinkFlow.Armed live = link(provider, LIVE_ARMED_AT);
            LinkFlow.Armed expired = link(provider, LINK_EXPIRED_ARMED_AT);
            assertThat(live.isLive(NOW)).isTrue();
            assertThat(expired.isLive(NOW)).isFalse();
            options.add(live);
            options.add(expired);
        }
        return options;
    }

    /** 재인증 무장 선택지 다섯 — 없음, (살아 있음·만료) × (google·naver). */
    private static List<ReauthenticationFlow.@Nullable Armed> reauthenticationOptions() {
        List<ReauthenticationFlow.@Nullable Armed> options = new ArrayList<>();
        options.add(null);
        for (String provider : List.of(GOOGLE, NAVER)) {
            ReauthenticationFlow.Armed live = reauthentication(provider, LIVE_ARMED_AT);
            ReauthenticationFlow.Armed expired = reauthentication(provider, REAUTHENTICATION_EXPIRED_ARMED_AT);
            assertThat(live.isLive(NOW)).isTrue();
            assertThat(expired.isLive(NOW)).isFalse();
            options.add(live);
            options.add(expired);
        }
        return options;
    }

    /**
     * 콜백 제공자 넷 — 무장 제공자로 쓰는 google·naver(어느 쪽 무장과 일치하는 콜백), 어느 무장과도 다른 kakao, 인증 결과에서
     * 읽지 못함({@code null}).
     */
    private static List<@Nullable String> callbackProviders() {
        List<@Nullable String> providers = new ArrayList<>();
        providers.add(GOOGLE);
        providers.add(NAVER);
        providers.add(KAKAO);
        providers.add(null);
        return providers;
    }

    private static String describe(LinkFlow.@Nullable Armed link) {
        return link == null ? "none" : link.targetProvider() + (link.isLive(NOW) ? "/live" : "/expired");
    }

    private static String describe(ReauthenticationFlow.@Nullable Armed armed) {
        return armed == null ? "none" : armed.provider() + (armed.isLive(NOW) ? "/live" : "/expired");
    }

    private static LinkFlow.Armed link(String provider, Instant armedAt) {
        return new LinkFlow.Armed(
                provider,
                UUID.randomUUID(),
                AuthFlowHandlers.SETTINGS_PATH,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                armedAt);
    }

    private static ReauthenticationFlow.Armed reauthentication(String provider, Instant armedAt) {
        return new ReauthenticationFlow.Armed(
                provider, UUID.randomUUID(), AuthFlowHandlers.SETTINGS_PATH, armedAt);
    }
}
