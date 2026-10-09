package com.bokrilog;

import com.bokrilog.operations.OperatorCommandApi;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 세션에 심는 일회성 <b>운영 command</b> 무장(Story 3.1).
 *
 * <p>{@link BybitRevocationFlow}·{@link PlanEndFlow}와 같은 모양이고 동작은 {@link SessionArming}이 한다. 준비와 확정
 * 사이에 OIDC 왕복이 없으므로 세션이 회전하지 않고, 확정은 허가를 지금 그 요청의 세션에 귀속시켜 소비한다. 발급 시각의
 * 세션 id를 함께 들어 그 사이 회전한 세션의 무장을 허가 소비 전에 같은 거부 하나로 접는다.
 *
 * <p><b>envelope를 서버가 든다.</b> 사유·scope·target·정책 버전·요청 시각·멱등 키 digest는 준비가 검증한 값 그대로
 * 여기 남고, 확정은 클라이언트가 되보낸 operation ID·확인 문구·멱등 키를 이 값과 대조할 뿐이다 — 확정 본문이 사유나
 * 대상을 바꿔 실을 자리가 없다. 허가 참조와 세션 id는 응답·로그 어디에도 나가지 않는다.
 */
final class OperatorCommandFlow {

    /** 무장이 사는 세션 속성. 다른 무장과 <b>다른</b> 이름이다. */
    static final String FLOW_ATTRIBUTE = "bokrilog.operations.operator-command";

    /** 무장의 수명. 허가의 수명과 같은 숫자지만 다른 상수다({@link PlanEndFlow#ARMED_LIFETIME}과 같은 근거). */
    static final Duration ARMED_LIFETIME = Duration.ofMinutes(5);

    private static final SessionArming<Armed> ARMING =
            new SessionArming<>(FLOW_ATTRIBUTE, Armed.class, ARMED_LIFETIME, Armed::armedAt);

    private OperatorCommandFlow() {}

    /**
     * 무장된 운영 command.
     *
     * @param userId 수행자. 허가의 귀속이다
     * @param operationId 서버가 만든 불투명 ID
     * @param command command type. 허가의 command는 이 값에 접두사를 붙인 것이다
     * @param scope 검증을 지난 범위. 원장의 scope다
     * @param target 정규화한 원 대상. 화면에 되돌려 주고 실행에 쓴다
     * @param ledgerTarget 원장·허가의 target. 사용자에게 귀속된 제한 target은 keyed digest다(Story 3.2)
     * @param reason 검증을 지난 사유. 확정 본문은 이 값을 바꿀 수 없다
     * @param policyVersion 준비가 적용한 envelope 정책 버전
     * @param confirmationHash 허가와 같은 정규 hash. 원장에 남는 값이다
     * @param requestDigest {@code command|scope|target|reason}의 SHA-256. 같은 키의 다른 입력을 가른다
     * @param requestedAt 원장의 요청 시각(DB)
     * @param keyDigest 준비에 쓴 멱등 키의 digest. 확정의 키가 같아야 한다
     * @param grantReference 준비 시각에 발급받은 불투명 허가 참조. <b>브라우저로 나가지 않는다</b>
     * @param confirmation 서버가 만든 확인 문구 원문
     * @param sessionId 허가 발급 시각의 세션 id
     * @param armedAt 무장 시각
     * @param restriction 제한 생성 command의 요청(Story 3.2). 그 밖은 {@code null}
     * @param lift 해제 command의 요청과 준비 시각의 제한 사실(Story 3.4). 그 밖은 {@code null}
     */
    record Armed(UUID userId, String operationId, String command, String scope, String target, String ledgerTarget,
            String reason, String policyVersion, String confirmationHash, String requestDigest, Instant requestedAt,
            String keyDigest, String grantReference, String confirmation, String sessionId, Instant armedAt,
            OperatorCommandApi.@Nullable Restriction restriction, OperatorCommandApi.@Nullable Lift lift)
            implements Serializable {

        @Serial private static final long serialVersionUID = 3L;

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
