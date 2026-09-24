package com.bokrilog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 세션에 심는 일회성 재인증 흐름.
 *
 * <p>재인증은 두 번째 인증 경로가 아니다. 같은 {@code oauth2Login} 왕복을 그대로 지나므로 PKCE
 * S256·일회성 {@code state}·{@code nonce}·JWS·{@code iss}·{@code aud}·{@code azp}·{@code exp}
 * 검증은 Spring Security OAuth2 Client가 하던 대로 하고, 이 클래스가 더하는 것은 <b>판정 두 개</b>
 * 뿐이다 — "이 식별자가 현재 사용자의 것인가"({@code AuthSessionService})와 "제공자가 증명한 인증
 * 시각이 신선한가"(아래 {@link #isFreshAuthentication}).
 *
 * <p>무장은 시작에, 판정은 콜백에 있다. 시작 시점에 제공자·사용자·복귀 목적지를 세션에 붙들어
 * 두므로 콜백이 들고 온 어떤 값도 목적지가 되지 않고, 셋 중 하나라도 어긋나면 판정할 것도 없이
 * 실패다. 목적지의 출처는 세션이 이미 가진 {@code DESTINATION_ATTRIBUTE} 하나이며 클라이언트는
 * 목적지를 보내지 않는다.
 *
 * <p>일회성은 소비 시 제거로 성립한다({@link #consume}). 같은 무장으로 두 번째 콜백이 오면 그
 * 콜백에는 흐름이 없고, {@code state}도 이미 소비돼 기존 실패 경로로 수렴한다.
 *
 * <p>세션 속성 하나에 담는다. 새 컬럼도 새 마이그레이션도 만들지 않는 것이 {@code SessionGuard}의
 * {@code reauth_required} 표시와 같은 이유다 — {@code spring_session}의 부가 상태는 Java 직렬화
 * {@code ATTRIBUTE_BYTES}에만 살고, 그래서 아래 {@link Armed}가 {@link Serializable}이다.
 *
 * <p>신선도 정책을 두 곳에 적지 않는다. 인가 요청이 보내는 {@code max_age}와 콜백에서 재는 창이
 * 같은 상수 하나에서 나온다({@link #MAX_AGE_SECONDS}와 {@link #FRESHNESS_WINDOW}). 두 값이
 * 갈리면 제공자가 통과시킨 인증을 우리가 거절하거나 그 반대가 된다.
 */
final class ReauthenticationFlow {

    /**
     * 무장된 흐름이 사는 세션 속성.
     *
     * <p>접두사 관용은 {@code AuthFlowHandlers}의 두 속성·{@code SessionGuard}의 표시와 같다.
     */
    static final String FLOW_ATTRIBUTE = "bokrilog.auth.reauthentication";

    /**
     * 마지막 재인증 시각이 사는 세션 속성.
     *
     * <p>이 스토리가 남기는 유일한 결과이며 HTTP로 나가지 않는다. 그 값을 5분·일회성 허가로
     * 바꾸는 것은 Story 1.9이고, 파괴적 작업의 최종 확인 화면은 1.10·1.11이 그 작업과 함께
     * 만든다. 여기에는 실행할 작업이 하나도 없다.
     */
    static final String AUTHENTICATED_AT_ATTRIBUTE = "bokrilog.auth.reauthenticated-at";

    /**
     * 무장의 수명.
     *
     * <p>{@code AuthFlowHandlers.FLOW_SESSION_TIMEOUT_SECONDS}와 같은 근거다 — 인가 왕복은 초
     * 단위로 끝나므로 5분이면 정상 흐름에 넉넉하고, 잊힌 무장이 세션에 무한히 남는 경로는 닫힌다.
     * 수명이 지난 무장은 읽는 쪽이 버린다.
     */
    static final Duration ARMED_LIFETIME = Duration.ofMinutes(5);

    /**
     * 최근 인증으로 인정하는 창.
     *
     * <p>제공자에게 보내는 {@code max_age}와 같은 값이다(아래 {@link #MAX_AGE_SECONDS}). 두 값이
     * 갈리면 제공자는 통과시킨 인증을 우리가 거절하거나, 우리가 요구한 것보다 오래된 인증을
     * 받아들인다.
     *
     * <p><b>실제 허용치는 이 값이 아니다.</b> 과거 쪽 경계는 {@code 창 + skew}(6분)이고 미래 쪽
     * 경계는 {@code skew}(60초)다 — 두 시계가 정확히 같지 않으므로 창의 끝에서 제공자가 통과시킨
     * 인증을 우리가 초 단위 차이로 거절하지 않게 한다. 판정의 정의는 아래
     * {@link #isFreshAuthentication}이 갖고, 네 경계는 {@code ReauthenticationFlowTest}가 고정한다.
     */
    static final Duration FRESHNESS_WINDOW = Duration.ofMinutes(5);

    /**
     * 허용 clock skew.
     *
     * <p>제공자의 시계와 우리 시계가 정확히 같지 않다. 이 값만큼의 미래는 받아들이고 그 밖의
     * 미래는 실패다 — 창 밖의 미래 {@code auth_time}은 시계 차이가 아니라 신뢰할 수 없는 값이다.
     * 과거 쪽에도 같은 여유를 준다.
     */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    /** 인가 요청의 {@code max_age}. 위 {@link #FRESHNESS_WINDOW}를 초로 옮긴 값 하나다. */
    static final long MAX_AGE_SECONDS = FRESHNESS_WINDOW.toSeconds();

    private ReauthenticationFlow() {}

    /**
     * 무장된 재인증 흐름.
     *
     * @param provider 이 흐름이 쓸 제공자 등록 id. 콜백이 알려 준 등록 id와 같아야 한다
     * @param userId 이 세션의 사용자. 콜백이 들고 온 식별자가 이 계정의 것인지만 확인한다
     * @param returnPath 복귀 목적지. 시작 시각에 붙들어 둔 값이며
     *     {@code AuthFlowHandlers.ALLOWED_DESTINATIONS} 안이다
     * @param armedAt 무장 시각. 위 {@link #ARMED_LIFETIME}의 기준이다
     */
    record Armed(String provider, UUID userId, String returnPath, Instant armedAt)
            implements Serializable {

        @Serial private static final long serialVersionUID = 1L;

        /** 이 무장이 아직 살아 있는가. */
        boolean isLive(Instant now) {
            return now.isBefore(this.armedAt.plus(ARMED_LIFETIME));
        }
    }

    /**
     * 이 세션에 재인증 흐름을 심는다.
     *
     * <p>앞선 무장이 있으면 덮는다. 세션 하나가 동시에 두 재인증을 진행할 이유가 없고, 남겨 두면
     * 어느 쪽 콜백이 와도 통과하는 상태가 된다.
     */
    static void arm(HttpSession session, String provider, UUID userId, String returnPath) {
        session.setAttribute(
                FLOW_ATTRIBUTE, new Armed(provider, userId, returnPath, Instant.now()));
    }

    /**
     * 무장된 흐름을 읽되 소비하지 않는다.
     *
     * <p>수명이 지난 무장은 없는 것과 같고, 그 자리에서 버린다 — 남겨 두면 다음 요청이 같은
     * 판정을 다시 하고 잊힌 값이 세션에 계속 실린다.
     *
     * @return 살아 있는 무장, 없으면 {@code null}
     */
    static @Nullable Armed peek(HttpServletRequest request) {
        Armed armed = stored(request);
        if (armed == null) {
            return null;
        }
        if (!armed.isLive(Instant.now())) {
            discard(request);
            return null;
        }
        return armed;
    }

    /**
     * 수명이 지난 무장까지 그대로 읽는다. 버리지 않는다.
     *
     * <p>콜백 경계 하나가 이것을 쓴다. 거기서는 "무장이 없었다"와 "무장이 있었는데 늦었다"를
     * 갈라야 하기 때문이다 — 앞의 것은 이 세션의 사실이 아닌 콜백이라 조용히 목적지로 되돌리고,
     * 뒤의 것은 사용자가 제공자 화면에서 5분을 넘긴 경우라 <b>이유를 말해야 한다</b>. 둘을
     * 구분하지 않으면 늦게 돌아온 사용자가 아무 설명 없이 같은 패널로 튕긴다.
     *
     * @return 저장된 무장, 없으면 {@code null}
     */
    static @Nullable Armed peekIncludingExpired(HttpServletRequest request) {
        return stored(request);
    }

    private static @Nullable Armed stored(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        return session.getAttribute(FLOW_ATTRIBUTE) instanceof Armed armed ? armed : null;
    }

    /**
     * 무장된 흐름을 읽고 지운다.
     *
     * <p>일회성이 여기서 성립한다. 콜백이 성공하든 실패하든 흐름은 그 콜백 하나로 끝난다.
     *
     * @return 살아 있던 무장, 없으면 {@code null}
     */
    static @Nullable Armed consume(HttpServletRequest request) {
        Armed armed = peek(request);
        discard(request);
        return armed;
    }

    /** 무장만 버린다. 세션은 건드리지 않는다. */
    static void discard(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(FLOW_ATTRIBUTE);
        }
    }

    /** 이 세션의 마지막 재인증 시각을 기록한다. */
    static void recordAuthentication(HttpSession session, Instant at) {
        session.setAttribute(AUTHENTICATED_AT_ATTRIBUTE, at);
    }

    /**
     * 제공자가 서명한 인증 시각이 신선한가.
     *
     * <p>최근 인증의 증거는 이 claim 하나다. 콜백 도착 시각도, 제공자 세션의 존재도, 계정 선택
     * 화면을 지났다는 사실도 증거가 아니다 — 셋 다 "방금 실제로 인증했다"를 말하지 않는다.
     *
     * <p><b>증거가 없으면 완화하지 않는다.</b> 제공자가 무엇을 공표했든 판정은 콜백의 claim
     * 하나로 한다. 네이버처럼 {@code auth_time}을 공표하지 않은 제공자가 실연동에서 fail-closed
     * 되는 것은 결함이 아니라 이 규칙이 요구한 결과다.
     *
     * @param authenticatedAt ID Token의 {@code auth_time}. 없으면 {@code null}
     * @param now 판정 시각
     * @return 부재·창 초과·skew 밖의 미래가 아니면 참
     */
    static boolean isFreshAuthentication(@Nullable Instant authenticatedAt, Instant now) {
        if (authenticatedAt == null) {
            return false;
        }
        if (authenticatedAt.isAfter(now.plus(CLOCK_SKEW))) {
            // 미래다. 시계 차이로 설명되지 않는 값이므로 신뢰하지 않는다.
            return false;
        }
        return !authenticatedAt.isBefore(now.minus(FRESHNESS_WINDOW).minus(CLOCK_SKEW));
    }
}
