package com.bokrilog;

import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 세션에 심는 일회성 <b>Bybit 연결 해제</b> 무장(Story 2.16).
 *
 * <p>{@link PlanEndFlow}와 같은 모양이다. 준비와 확정 사이에 OIDC 왕복이 없으므로 세션이 회전하지 않고, 확정은
 * 허가를 지금 그 요청의 세션에 귀속시켜 소비한다. 발급 시각의 세션 id를 함께 들어 그 사이 회전한 세션의 무장을
 * 허가 소비 전에 같은 거부 하나로 접는다.
 *
 * <p><b>확인 문구를 든다.</b> 서버가 만든 정본({@code bybit-connection-revoke|<connectionId>})이며 클라이언트가
 * 되보낸 값은 대조에만 쓴다. 무장은 아무것도 바꾸지 않는다 — 실린 사실이 곧 "허가가 발급됐고 아직 쓰이지
 * 않았다"이다. 확정이 해제를 실제로 한 요청에서만 버린다.
 */
final class BybitRevocationFlow {

    /** 무장된 해제 흐름이 사는 세션 속성. 다른 무장과 <b>다른</b> 이름이다. */
    static final String FLOW_ATTRIBUTE = "bokrilog.source.bybit-revocation";

    /** 무장의 수명. 허가의 수명과 같은 숫자지만 다른 상수다({@link PlanEndFlow#ARMED_LIFETIME}과 같은 근거). */
    static final Duration ARMED_LIFETIME = Duration.ofMinutes(5);

    private BybitRevocationFlow() {}

    /**
     * 무장된 해제 흐름.
     *
     * @param userId 이 세션의 사용자. 허가의 귀속이다
     * @param connectionId 해제할 연결. 허가의 target이다
     * @param grantReference 준비 시각에 발급받은 불투명 허가 참조. <b>브라우저로 나가지 않는다</b>
     * @param confirmation 서버가 만든 확인 문구 원문
     * @param sessionId 허가 발급 시각의 세션 id. 응답·로그 어디에도 나가지 않는다
     * @param armedAt 무장 시각
     */
    record Armed(UUID userId, UUID connectionId, String grantReference, String confirmation, String sessionId,
            Instant armedAt) implements Serializable {

        @Serial private static final long serialVersionUID = 1L;

        boolean isLive(Instant now) {
            return now.isBefore(this.armedAt.plus(ARMED_LIFETIME));
        }
    }

    static void arm(HttpSession session, Armed armed) {
        session.setAttribute(FLOW_ATTRIBUTE, armed);
    }

    /** 살아 있는 무장을 읽되 소비하지 않는다. 수명이 지났으면 버리고 {@code null}이다. */
    static @Nullable Armed peek(HttpSession session) {
        Armed armed = peekIncludingExpired(session);
        if (armed == null) {
            return null;
        }
        if (!armed.isLive(Instant.now())) {
            discard(session);
            return null;
        }
        return armed;
    }

    /** 수명이 지난 무장까지 그대로 읽는다. 버릴 무장의 허가를 함께 무효화하는 경로가 쓴다. */
    static @Nullable Armed peekIncludingExpired(HttpSession session) {
        return session.getAttribute(FLOW_ATTRIBUTE) instanceof Armed armed ? armed : null;
    }

    /** 무장만 버린다. 세션도 허가도 건드리지 않는다. */
    static void discard(HttpSession session) {
        session.removeAttribute(FLOW_ATTRIBUTE);
    }
}
