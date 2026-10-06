package com.bokrilog;

import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 세션에 심는 일회성 <b>계획 중도 종료</b> 무장(Story 2.13).
 *
 * <p>{@link BybitConnectionFlow}와 같은 모양이다. 준비와 확정 사이에 제공자 OIDC 왕복이 없으므로 세션이
 * 회전하지 않고, 확정은 허가를 <b>지금 그 요청의 세션</b>에 귀속시켜 소비한다(1.9 포트의 기본 경로). 그래도
 * 발급 시각의 세션 id를 함께 든다 — 확정이 그 값과 지금의 세션 id를 대조해, 그 사이 회전한 세션의 무장을
 * 허가 소비 전에 같은 거부 하나로 접는다.
 *
 * <p><b>확인 문구를 든다.</b> 문구는 서버가 만든 정본
 * ({@code plan-end|<reason>|<planId>|<pointerVersion>|<calculationId>}, {@code PlanEndApi.Request#confirmation})이고,
 * 사유와 사용자가 본 공식 결과의 참조를 함께 담는다. 그래서 허가의 다섯 번째 축(확인 hash)이 "그 사유로, 그 상태를
 * 보고" 한 확인만 판별한다. 클라이언트가 되보낸 값은 대조에만 쓴다(Story 2.1의 관용).
 *
 * <p><b>무장은 아무것도 바꾸지 않는다.</b> 이 record가 세션에 실렸다는 사실이 곧 "허가가 발급됐고 아직 쓰이지
 * 않았다"이다. 확정이 성공하면 버린다. 거절되면 버리지 않는다 — 상태 변화(409)를 본 사용자가 대시보드를 다시
 * 읽고 새 값으로 다시 준비할 때 그 준비가 이 무장을 대신한다.
 *
 * <p>세션 속성 하나에 담는다. 새 컬럼도 새 마이그레이션도 만들지 않는 것이 앞선 무장들과 같은 이유이며, 그래서
 * 아래 {@link Armed}가 {@link Serializable}이다.
 */
final class PlanEndFlow {

    /** 무장된 종료 흐름이 사는 세션 속성. 다른 무장과 <b>다른</b> 이름이다. */
    static final String FLOW_ATTRIBUTE = "bokrilog.plan.plan-end";

    /**
     * 무장의 수명. 확인 대화상자가 열려 있는 시간이며 허가의 수명({@code ActionGrant.LIFETIME})과 같은 숫자지만
     * 다른 상수다 — 대화상자 정책을 바꾸는 날 허가 정책이 조용히 따라 움직이지 않게 한다.
     */
    static final Duration ARMED_LIFETIME = Duration.ofMinutes(5);

    /** 공통 무장 메커니즘. 키·수명·record는 이 클래스가 정하고 동작은 {@link SessionArming}이 한다. */
    private static final SessionArming<Armed> ARMING =
            new SessionArming<>(FLOW_ATTRIBUTE, Armed.class, ARMED_LIFETIME, Armed::armedAt);

    private PlanEndFlow() {}

    /**
     * 무장된 종료 흐름.
     *
     * @param userId 이 세션의 사용자. 허가의 귀속이다
     * @param planId 종료할 계획. 허가의 target이다
     * @param reason {@code USER_REQUEST} 또는 {@code LONG_GAP_NEW_PLAN}
     * @param pointerVersion 사용자가 본 공식 포인터 버전
     * @param calculationId 사용자가 본 공식 계산 버전
     * @param grantReference 준비 시각에 발급받은 불투명 허가 참조. <b>브라우저로 나가지 않는다</b>
     * @param confirmation 서버가 만든 확인 문구 원문. 해시는 허가 포트가 만든다
     * @param sessionId 허가 발급 시각의 세션 id. 응답·로그 어디에도 나가지 않는다
     * @param armedAt 무장 시각. 위 {@link #ARMED_LIFETIME}의 기준이다
     */
    record Armed(
            UUID userId,
            UUID planId,
            String reason,
            long pointerVersion,
            UUID calculationId,
            String grantReference,
            String confirmation,
            String sessionId,
            Instant armedAt)
            implements Serializable {

        @Serial private static final long serialVersionUID = 1L;

        /** 이 무장이 아직 살아 있는가. */
        boolean isLive(Instant now) {
            return now.isBefore(this.armedAt.plus(ARMED_LIFETIME));
        }
    }

    /** 이 세션에 종료 흐름을 심는다. 앞선 무장은 덮인다(그 허가는 발급이 이미 무효화했다). */
    static void arm(HttpSession session, Armed armed) {
        ARMING.arm(session, armed);
    }

    /** 살아 있는 무장을 읽되 소비하지 않는다. 수명이 지났으면 버리고 {@code null}이다. */
    static @Nullable Armed peek(HttpSession session) {
        return ARMING.peek(session);
    }

    /** 수명이 지난 무장까지 그대로 읽는다. 버릴 무장의 허가를 함께 무효화하는 경로가 쓴다. */
    static @Nullable Armed peekIncludingExpired(HttpSession session) {
        return ARMING.peekIncludingExpired(session);
    }

    /** 무장만 버린다. 세션도 허가도 건드리지 않는다. */
    static void discard(HttpSession session) {
        ARMING.discard(session);
    }
}
