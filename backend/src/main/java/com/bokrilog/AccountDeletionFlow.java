package com.bokrilog;

import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 세션에 심는 일회성 <b>계정 삭제</b> 무장(Story 5.1).
 *
 * <p>{@link BybitRevocationFlow}와 같은 모양이다. 준비와 확정 사이에 OIDC 왕복이 없으므로 세션이 회전하지 않고, 확정은 허가를 지금
 * 그 요청의 세션에 귀속시켜 소비한다. 발급 시각의 세션 id를 함께 들어 그 사이 회전한 세션의 무장을 허가 소비 전에 같은 거부
 * 하나로 접는다.
 *
 * <p><b>확인 문구와 정책 버전을 든다.</b> 문구는 서버 상수({@link AccountDeletionPolicy#CONFIRMATION})이고 사용자가 입력한 값은
 * 대조에만 쓴다. 무장은 아무것도 바꾸지 않는다 — 실린 사실이 곧 "허가가 발급됐고 아직 쓰이지 않았다"이다. 문구가 어긋난 확정은
 * 무장을 남기고(같은 대화상자에서 다시 입력한다) 요청을 실제로 만든 확정에서만 버린다.
 */
final class AccountDeletionFlow {

    /** 무장된 삭제 흐름이 사는 세션 속성. 다른 무장과 <b>다른</b> 이름이다. */
    static final String FLOW_ATTRIBUTE = "bokrilog.identity.account-deletion";

    /** 무장의 수명. 허가의 수명과 같은 숫자지만 다른 상수다({@link BybitRevocationFlow#ARMED_LIFETIME}과 같은 근거). */
    static final Duration ARMED_LIFETIME = Duration.ofMinutes(5);

    /** 공통 무장 메커니즘. 키·수명·record는 이 클래스가 정하고 동작은 {@link SessionArming}이 한다. */
    private static final SessionArming<Armed> ARMING =
            new SessionArming<>(FLOW_ATTRIBUTE, Armed.class, ARMED_LIFETIME, Armed::armedAt);

    private AccountDeletionFlow() {}

    /**
     * 무장된 삭제 흐름.
     *
     * @param userId 이 세션의 사용자. 허가의 귀속이다
     * @param policyVersion 준비 시각의 정책 버전. 허가 target의 일부다
     * @param grantReference 준비 시각에 발급받은 불투명 허가 참조. <b>브라우저로 나가지 않는다</b>
     * @param confirmation 서버가 정한 확인 문구 원문
     * @param sessionId 허가 발급 시각의 세션 id. 응답·로그 어디에도 나가지 않는다
     * @param armedAt 무장 시각
     */
    record Armed(UUID userId, String policyVersion, String grantReference, String confirmation, String sessionId,
            Instant armedAt) implements Serializable {

        @Serial private static final long serialVersionUID = 1L;

        boolean isLive(Instant now) {
            return now.isBefore(this.armedAt.plus(ARMED_LIFETIME));
        }
    }

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
