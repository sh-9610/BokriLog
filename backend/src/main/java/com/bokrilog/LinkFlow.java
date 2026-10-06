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
 * 세션에 심는 일회성 <b>연결</b> 흐름.
 *
 * <p>{@link ReauthenticationFlow}와 모양은 같고 목적은 정반대다. 재인증은 "계정을 만들지 않고
 * 매핑을 만들지 않고 사용자를 바꾸지 않는다"가 계약이고, 연결은 <b>정확히 매핑을 만드는 일</b>이다.
 * 그래서 한 record에 목적 필드를 더해 겸용하지 않는다 — 겸용하면 그 계약이 조건부가 되고,
 * {@code AuthFlowHandlers.onSuccess}의 분기점이 "무장이 있으면 재인증"에서 "무장이 있고 목적이
 * X면"으로 바뀌어 읽는 사람이 매번 목적을 확인해야 한다. 별도 클래스가 더 싸다.
 *
 * <p>무장은 시작에, 판정은 콜백에 있다. 시작 시각에 대상 제공자·사용자·복귀 경로·허가 참조·
 * 멱등 키·<b>발급 시각의 세션 id</b>를 세션에 붙들어 두므로 콜백이 들고 온 어떤 값도 이 다섯 중
 * 무엇도 될 수 없다.
 *
 * <p><b>발급 시각의 세션 id를 함께 든다.</b> Spring Security의 세션 고정 방어가 성공 핸들러
 * <b>직전에</b> 세션 ID를 회전시키므로({@code AuthSecurityConfig}) 허가 발급 시각의 세션 id와 소비
 * 시각의 live id는 언제나 다르다. 허가 포트가 live id로만 귀속을 판정하면 연결은 구조적으로
 * 성립할 수 없고, 그래서 이 record가 그 값을 나른다. 이것이 귀속을 무르지 않는 이유는 이 record가
 * <b>세션 속성</b>이기 때문이다 — {@code changeSessionId}는 속성을 보존하므로 그 값을 읽을 수
 * 있다는 사실 자체가 "회전 전 세션의 연속"을 증명하고, 다른 세션은 그 속성을 갖지 못한다.
 *
 * <p>일회성은 소비 시 제거로 성립한다({@link #consume}). 같은 무장으로 두 번째 콜백이 오면 그
 * 콜백에는 흐름이 없고, {@code state}도 이미 소비돼 기존 실패 경로로 수렴한다. 흐름이 사라진
 * 뒤에도 허가 자체가 두 번 쓰이지 않는 것은 {@code ActionGrantService}의 조건부 UPDATE가 따로
 * 보장한다.
 *
 * <p>세션 속성 하나에 담는다. 새 컬럼도 새 마이그레이션도 만들지 않는 것이 1.8의 무장·
 * {@code SessionGuard}의 표시와 같은 이유다 — {@code spring_session}의 부가 상태는 Java 직렬화
 * {@code ATTRIBUTE_BYTES}에만 살고, 그래서 아래 {@link Armed}가 {@link Serializable}이다.
 */
final class LinkFlow {

    /**
     * 무장된 연결 흐름이 사는 세션 속성.
     *
     * <p>재인증 무장({@code ReauthenticationFlow.FLOW_ATTRIBUTE})과 <b>다른</b> 이름이다. 접두사
     * 관용은 {@code AuthFlowHandlers}의 두 속성·{@code SessionGuard}의 표시와 같다.
     */
    static final String FLOW_ATTRIBUTE = "bokrilog.auth.link";

    /**
     * 무장의 수명.
     *
     * <p>{@code ReauthenticationFlow.ARMED_LIFETIME}·{@code AuthFlowHandlers.FLOW_SESSION_TIMEOUT_SECONDS}
     * 와 같은 근거다 — 인가 왕복은 초 단위로 끝나므로 5분이면 정상 흐름에 넉넉하고, 잊힌 무장이
     * 세션에 무한히 남는 경로는 닫힌다. <b>같은 숫자지만 다른 상수다.</b> 저쪽은 재인증 왕복의
     * 시간이고 이쪽은 연결 왕복의 시간이며, 허가 자체의 수명은 또 다른 축이
     * ({@code ActionGrant.LIFETIME}) 데이터베이스에서 판정한다.
     */
    static final Duration ARMED_LIFETIME = Duration.ofMinutes(5);

    /** 공통 무장 메커니즘. 키·수명·record는 이 클래스가 정하고 동작은 {@link SessionArming}이 한다. */
    private static final SessionArming<Armed> ARMING =
            new SessionArming<>(FLOW_ATTRIBUTE, Armed.class, ARMED_LIFETIME, Armed::armedAt);

    private LinkFlow() {}

    /**
     * 무장된 연결 흐름.
     *
     * @param targetProvider 연결하려는 제공자 등록 id. 콜백이 알려 준 등록 id와 같아야 한다
     * @param userId 이 세션의 사용자. 허가의 귀속이자 매핑이 붙을 계정이다
     * @param returnPath 복귀 목적지. 연결 조작이 사는 화면 하나이며 클라이언트가 보내지 않는다
     * @param grantReference 시작 시각에 발급받은 불투명 허가 참조. 브라우저로 나가지 않는다 —
     *     서버가 세션에 들고 있다가 콜백에서 그대로 소비한다
     * @param operationId 이 실행의 멱등 키. 서버가 만든다. 같은 값의 재전송은 저장된 결과를
     *     그대로 받고 매핑을 다시 만들지 않는다({@code ActionGrantService})
     * @param boundSessionId 허가 발급 시각의 세션 id. 콜백의 live id와 언제나 다르므로 소비가
     *     이 값을 제시한다(클래스 javadoc 참조)
     * @param armedAt 무장 시각. 위 {@link #ARMED_LIFETIME}의 기준이다
     */
    record Armed(
            String targetProvider,
            UUID userId,
            String returnPath,
            String grantReference,
            String operationId,
            String boundSessionId,
            Instant armedAt)
            implements Serializable {

        @Serial private static final long serialVersionUID = 1L;

        /** 이 무장이 아직 살아 있는가. */
        boolean isLive(Instant now) {
            return now.isBefore(this.armedAt.plus(ARMED_LIFETIME));
        }
    }

    /**
     * 이 세션에 연결 흐름을 심는다.
     *
     * <p>앞선 무장이 있으면 덮는다. 세션 하나가 동시에 두 연결을 진행할 이유가 없고, 남겨 두면
     * 어느 쪽 콜백이 와도 통과하는 상태가 된다. 덮인 무장의 허가는 발급 경로가 이미 무효화했다
     * ({@code ActionGrantService.issue}가 같은 {@code (command, target)}의 살아 있는 허가를 먼저
     * 지운다).
     */
    static void arm(HttpSession session, Armed armed) {
        ARMING.arm(session, armed);
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
        return ARMING.peek(request);
    }

    /**
     * 수명이 지난 무장까지 그대로 읽는다. 버리지 않는다.
     *
     * <p>콜백 경계 하나가 이것을 쓴다. 거기서는 "무장이 없었다"와 "무장이 있었는데 늦었다"를
     * 갈라야 하기 때문이다({@code ReauthenticationFlow.peekIncludingExpired}와 같은 근거) — 앞의
     * 것은 이 세션의 사실이 아닌 콜백이라 조용히 목적지로 되돌리고, 뒤의 것은 사용자가 제공자
     * 화면에서 5분을 넘긴 경우라 이유를 말해야 한다.
     *
     * @return 저장된 무장, 없으면 {@code null}
     */
    static @Nullable Armed peekIncludingExpired(HttpServletRequest request) {
        return ARMING.peekIncludingExpired(request);
    }

    /**
     * 세션이 이미 있는 자리에서 무장을 읽는다. 수명이 지난 것도 그대로 돌려준다.
     *
     * <p>버릴 무장의 허가를 함께 무효화하는 경로가 이것을 쓴다({@code LinkService.abandon}).
     * 거기서는 요청이 아니라 세션이 이미 손에 있고, 수명이 지난 무장의 허가도 무효화 대상이다.
     */
    static @Nullable Armed peekIncludingExpired(HttpSession session) {
        return ARMING.peekIncludingExpired(session);
    }

    /**
     * 무장된 흐름을 읽고 지운다.
     *
     * <p>일회성이 여기서 성립한다. 콜백이 성공하든 실패하든 흐름은 그 콜백 하나로 끝난다.
     *
     * @return 살아 있던 무장, 없으면 {@code null}
     */
    static @Nullable Armed consume(HttpServletRequest request) {
        return ARMING.consume(request);
    }

    /** 무장만 버린다. 세션도 허가도 건드리지 않는다. */
    static void discard(HttpServletRequest request) {
        ARMING.discard(request);
    }

    /**
     * 세션이 이미 있는 자리에서 무장만 버린다.
     *
     * <p><b>허가는 건드리지 않는다.</b> 버려지는 무장의 허가를 함께 무효화해야 하는 경로는
     * {@code LinkService.abandon} 하나이고, 그 짝을 여기 숨기면 "흐름만 버린다"는 이름이 거짓이
     * 된다 — 콜백 경계처럼 허가를 남겨 두어야 하는 자리도 이 메서드를 쓴다.
     */
    static void discard(HttpSession session) {
        ARMING.discard(session);
    }
}
