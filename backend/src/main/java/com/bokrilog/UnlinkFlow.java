package com.bokrilog;

import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 세션에 심는 일회성 <b>해제</b> 무장.
 *
 * <p>{@link LinkFlow}와 같은 모양이되 필드 둘이 없다 — {@code returnPath}와 {@code boundSessionId}다.
 * 그 차이가 두 조작의 차이를 그대로 읽게 한다.
 *
 * <ul>
 *   <li><b>{@code returnPath}가 없다.</b> 해제에는 제공자 왕복이 없다. 준비도 확인도 같은 문서에서
 *       보내는 {@code POST}이고 응답은 204라, 브라우저를 어디로도 되돌리지 않는다.
 *   <li><b>{@code boundSessionId}가 없다.</b> 왕복이 없으므로 발급과 소비 사이에 세션 고정 방어가
 *       끼지 않는다. 즉 발급 시각의 세션 id와 소비 시각의 live id가 <b>같다</b>. 1.9 포트의 기본
 *       생성자가 그 경우를 위한 것이며, 제시된 id를 쓰는 예외는 왕복을 지나는 연결 하나뿐이다
 *       ({@code ActionGrantService.ConsumptionRequest#boundSessionId()}).
 * </ul>
 *
 * <p>무장이 여는 창은 "확인 대화상자가 열린 뒤 사용자가 최종 확인을 누르기까지"다. 연결에서 OIDC
 * 왕복이 차지하던 자리를 해제에서는 그 대화상자가 대신하며, 그래서 수명 상수의 근거도 같은 모양의
 * 사람 시간이다.
 *
 * <p><b>무장은 아무것도 바꾸지 않는다.</b> 이 record가 세션에 실렸다는 사실이 곧 "허가가 발급됐고
 * 아직 쓰이지 않았다"이며, 매핑·세션·연결 목록은 그대로다. "최종 확인 전에는 아무것도 바뀌지
 * 않는다"는 계약이 이 한 겹으로 성립한다.
 *
 * <p><b>최종 확인이 이 무장을 지우지 않는다.</b> {@link LinkFlow}에 있는 {@code consume}이 여기
 * 없는 이유가 그것이다 — 지우면 확인 도중의 예외가 허가를 고아로 남기고, 응답을 잃은 클라이언트가
 * 같은 {@code operation_id}를 다시 제시할 길도 사라져 1.9의 재전송 계약이 도달할 수 없는 코드가
 * 된다({@code UnlinkService.complete}). 연결에는 그 선택지가 없었다: 콜백은 한 번뿐이고 그 뒤의
 * 세션은 이미 회전해 있어 흐름을 남겨 둘 이유가 없다.
 *
 * <p>그래서 무장이 사라지는 길은 셋이다 — 같은 종류의 새 무장이 대신하거나, 다른 흐름이 버리거나
 * ({@code UnlinkService.abandon}), 수명이 다하거나. <b>일회성은 무장이 아니라 허가의 성질이며</b>
 * 그 권위는 {@code ActionGrantService}의 조건부 UPDATE 한 문장이다.
 *
 * <p>요청이 아니라 {@link HttpSession}을 받는다. 두 엔드포인트 모두 세션을 이미 손에 들고 있고,
 * 콜백 경계처럼 "세션이 없을 수도 있는" 자리를 지나지 않기 때문이다. {@code LinkFlow}가
 * {@code HttpServletRequest} 오버로드를 함께 가진 것은 그 경계 때문이며 여기에는 그 경계가 없다.
 *
 * <p>세션 속성 하나에 담는다. 새 컬럼도 새 마이그레이션도 만들지 않는 것이 1.8의 무장·1.10의 연결
 * 무장과 같은 이유다 — {@code spring_session}의 부가 상태는 Java 직렬화 {@code ATTRIBUTE_BYTES}에만
 * 살고, 그래서 아래 {@link Armed}가 {@link Serializable}이다.
 */
final class UnlinkFlow {

    /**
     * 무장된 해제 흐름이 사는 세션 속성.
     *
     * <p>재인증 무장({@code ReauthenticationFlow.FLOW_ATTRIBUTE})·연결 무장
     * ({@code LinkFlow.FLOW_ATTRIBUTE})과 <b>다른</b> 이름이다. 세 흐름이 한 화면에서 시작될 수
     * 있으므로 속성을 겸용하면 어느 무장이 살아 있는지가 값의 내용에 숨는다.
     */
    static final String FLOW_ATTRIBUTE = "bokrilog.auth.unlink";

    /**
     * 무장의 수명.
     *
     * <p>{@code LinkFlow.ARMED_LIFETIME}·{@code ReauthenticationFlow.ARMED_LIFETIME}과 같은 숫자지만
     * <b>다른 상수</b>다. 저쪽 둘은 OAuth 왕복의 시간이고 이쪽은 확인 대화상자가 열려 있는 사람
     * 시간이다. 한 값으로 합치면 왕복 정책을 바꾸는 날 확인 창이 조용히 따라 움직인다.
     *
     * <p>허가 자체의 수명은 또 다른 축이며({@code ActionGrant.LIFETIME}) 데이터베이스가 판정한다.
     * 두 값이 같은 5분이므로 정상 경로에서는 무장과 허가가 함께 죽지만, 그 동시성에 기대지 않는다 —
     * 무장이 살아 있어도 허가가 죽었으면 소비가 0행으로 끝나고 결과는 같은 거부 하나다.
     */
    static final Duration ARMED_LIFETIME = Duration.ofMinutes(5);

    /** 공통 무장 메커니즘. 키·수명·record는 이 클래스가 정하고 동작은 {@link SessionArming}이 한다. */
    private static final SessionArming<Armed> ARMING =
            new SessionArming<>(FLOW_ATTRIBUTE, Armed.class, ARMED_LIFETIME, Armed::armedAt);

    private UnlinkFlow() {}

    /**
     * 무장된 해제 흐름.
     *
     * @param targetProvider 해제하려는 제공자 이름. 최종 확인이 알려 준 이름과 같아야 한다
     * @param userId 이 세션의 사용자. 허가의 귀속이자 매핑이 사라질 계정이다
     * @param grantReference 준비 시각에 발급받은 불투명 허가 참조. <b>브라우저로 나가지 않는다</b> —
     *     서버가 세션에 들고 있다가 최종 확인에서 그대로 소비한다
     * @param operationId 이 실행의 멱등 키. 서버가 만든다. 같은 값의 재전송은 저장된 결과를 그대로
     *     받고 매핑을 다시 지우지 않는다({@code ActionGrantService})
     * @param armedAt 무장 시각. 위 {@link #ARMED_LIFETIME}의 기준이다
     */
    record Armed(
            String targetProvider,
            UUID userId,
            String grantReference,
            String operationId,
            Instant armedAt)
            implements Serializable {

        @Serial private static final long serialVersionUID = 1L;

        /** 이 무장이 아직 살아 있는가. */
        boolean isLive(Instant now) {
            return now.isBefore(this.armedAt.plus(ARMED_LIFETIME));
        }
    }

    /**
     * 이 세션에 해제 흐름을 심는다.
     *
     * <p>앞선 무장이 있으면 덮는다. 세션 하나가 동시에 두 해제를 진행할 이유가 없고, 남겨 두면 어느
     * 확인이 와도 통과하는 상태가 된다. 덮이는 무장의 허가를 함께 무효화하는 것은 이 메서드가
     * 아니라 {@code UnlinkService.abandon}의 일이다({@code LinkFlow.arm}과 같은 분담).
     */
    static void arm(HttpSession session, Armed armed) {
        ARMING.arm(session, armed);
    }

    /**
     * 무장된 흐름을 읽되 소비하지 않는다.
     *
     * <p>수명이 지난 무장은 없는 것과 같고, 그 자리에서 버린다 — 남겨 두면 다음 요청이 같은 판정을
     * 다시 하고 잊힌 값이 세션에 계속 실린다.
     *
     * @return 살아 있는 무장, 없으면 {@code null}
     */
    static @Nullable Armed peek(HttpSession session) {
        return ARMING.peek(session);
    }

    /**
     * 수명이 지난 무장까지 그대로 읽는다. 버리지 않는다.
     *
     * <p>버릴 무장의 허가를 함께 무효화하는 경로가 이것을 쓴다({@code UnlinkService.abandon}).
     * 수명이 지난 무장의 허가도 아직 살아 있을 수 있고, 살아 있다면 같은 이유로 지워야 한다.
     *
     * @return 저장된 무장, 없으면 {@code null}
     */
    static @Nullable Armed peekIncludingExpired(HttpSession session) {
        return ARMING.peekIncludingExpired(session);
    }

    /**
     * 무장만 버린다. 세션도 허가도 건드리지 않는다.
     *
     * <p><b>허가는 건드리지 않는다.</b> 버려지는 무장의 허가를 함께 무효화해야 하는 경로는
     * {@code UnlinkService.abandon} 하나이고, 그 짝을 여기 숨기면 "흐름만 버린다"는 이름이 거짓이
     * 된다({@code LinkFlow.discard}와 같은 분담).
     */
    static void discard(HttpSession session) {
        ARMING.discard(session);
    }
}
