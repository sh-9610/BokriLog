package com.bokrilog;

import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 세션에 심는 일회성 <b>Bybit 연결</b> 무장.
 *
 * <p>{@link UnlinkFlow}와 같은 모양이다 — {@code returnPath}도 {@code boundSessionId}도 없다.
 * 연결 검증에는 제공자 OIDC 왕복이 없으므로 준비와 확인 사이에 세션이 회전하지 않고, 1.9 포트의
 * 기본 생성자가 그대로 성립한다. 브라우저를 어디로도 되돌리지 않는 것도 같다 — 준비도 확인도 같은
 * 문서에서 보내는 {@code POST}다.
 *
 * <p><b>확인 문구를 든다.</b> {@code UnlinkFlow}에 없는 성분이 이것 하나이며, 이 스토리가 허가의
 * 다섯 번째 축을 실제로 살아 있게 만드는 자리다. Story 1.10·1.11의 두 command는 확인 문구가 서버
 * 상수라 해시가 {@code (command, target)}의 결정적 함수였고, 그러면 {@code ERD-1.0.0} §5가 축을
 * 다섯으로 둔 뜻이 사라진다. 여기서는 준비가 <b>요청마다 다른 문구</b>를 만들어 응답으로 돌려주고
 * 화면이 그것을 그대로 렌더하며, 최종 확인이 같은 문자열을 되보낸다. 그래서 해시는 사용자가 실제로
 * 본 문장의 함수다.
 *
 * <p>확인 문구를 세션에 함께 두는 이유는 두 가지다. 하나는 재전송이다 — 응답을 잃은 클라이언트가
 * 같은 {@code operation_id}로 다시 보낼 때 서버가 같은 문구를 다시 해시할 수 있어야 한다. 다른
 * 하나는 감사다: 소비 술어가 비교하는 값의 출처가 서버 측 기록 하나로 남는다.
 *
 * <p><b>무장은 아무것도 바꾸지 않는다.</b> 이 record가 세션에 실렸다는 사실이 곧 "허가가 발급됐고
 * 아직 쓰이지 않았다"이며, 연결·자격정보·portfolio는 그대로다.
 *
 * <p><b>최종 확인이 이 무장을 지우지 않는다.</b> 근거는 {@link UnlinkFlow}와 같다 — 지우면 확인
 * 도중의 예외가 허가를 고아로 남기고, 재전송 계약이 도달할 수 없는 코드가 된다. 게다가 여기서는
 * <b>검증 실패</b>가 정상 경로다: 잘못된 키로 한 번 거절된 사용자가 같은 무장으로 다시 시도할 수
 * 있어야 하고, 무장을 소비해 버리면 매번 본인 확인부터 다시 해야 한다.
 *
 * <p>세션 속성 하나에 담는다. 새 컬럼도 새 마이그레이션도 만들지 않는 것이 1.8·1.10·1.11의 무장과
 * 같은 이유이며, 그래서 아래 {@link Armed}가 {@link Serializable}이다.
 */
final class BybitConnectionFlow {

    /**
     * 무장된 연결 흐름이 사는 세션 속성.
     *
     * <p>재인증·연결·해제 무장과 <b>다른</b> 이름이다. 설정 화면이 네 조작을 같은 페이지에 그리므로
     * 속성을 겸용하면 어느 무장이 살아 있는지가 값의 내용에 숨는다.
     */
    static final String FLOW_ATTRIBUTE = "bokrilog.source.bybit-connection";

    /**
     * 무장의 수명.
     *
     * <p>{@code UnlinkFlow.ARMED_LIFETIME}과 같은 숫자지만 <b>다른 상수</b>다. 저쪽은 확인
     * 대화상자가 열려 있는 시간이고 이쪽은 사용자가 제공자 콘솔에서 키를 만들어 붙여 넣는
     * 시간이다. 한 값으로 합치면 대화상자 정책을 바꾸는 날 이 폼이 조용히 따라 움직인다.
     */
    static final Duration ARMED_LIFETIME = Duration.ofMinutes(5);

    /** 공통 무장 메커니즘. 키·수명·record는 이 클래스가 정하고 동작은 {@link SessionArming}이 한다. */
    private static final SessionArming<Armed> ARMING =
            new SessionArming<>(FLOW_ATTRIBUTE, Armed.class, ARMED_LIFETIME, Armed::armedAt);

    private BybitConnectionFlow() {}

    /**
     * 무장된 연결 흐름.
     *
     * @param userId 이 세션의 사용자. 허가의 귀속이자 연결이 붙을 계정이다
     * @param grantReference 준비 시각에 발급받은 불투명 허가 참조. <b>브라우저로 나가지 않는다</b>
     * @param operationId 이 실행의 멱등 키. 서버가 만든다
     * @param confirmation 사용자가 화면에서 보게 될 확인 문구 <b>원문</b>. 해시는 허가 포트가 만든다
     * @param armedAt 무장 시각. 위 {@link #ARMED_LIFETIME}의 기준이다
     */
    record Armed(
            UUID userId,
            String grantReference,
            String operationId,
            String confirmation,
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
     * <p>앞선 무장이 있으면 덮는다. 덮이는 무장의 허가를 함께 무효화하는 것은 이 메서드가 아니라
     * {@code BybitConnectionService}의 일이다({@code UnlinkFlow.arm}과 같은 분담).
     */
    static void arm(HttpSession session, Armed armed) {
        ARMING.arm(session, armed);
    }

    /**
     * 무장된 흐름을 읽되 소비하지 않는다.
     *
     * @return 살아 있는 무장, 없으면 {@code null}
     */
    static @Nullable Armed peek(HttpSession session) {
        return ARMING.peek(session);
    }

    /** 수명이 지난 무장까지 그대로 읽는다. 버릴 무장의 허가를 함께 무효화하는 경로가 쓴다. */
    static @Nullable Armed peekIncludingExpired(HttpSession session) {
        return ARMING.peekIncludingExpired(session);
    }

    /** 무장만 버린다. 세션도 허가도 건드리지 않는다({@code UnlinkFlow.discard}와 같은 분담). */
    static void discard(HttpSession session) {
        ARMING.discard(session);
    }
}
