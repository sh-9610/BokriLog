package com.bokrilog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * 세션 속성 하나에 심는 일회성 무장의 <b>메커니즘</b>.
 *
 * <p>Flow 여섯({@code ReauthenticationFlow}·{@code LinkFlow}·{@code UnlinkFlow}·{@code BybitConnectionFlow}·
 * {@code PlanEndFlow}·{@code BybitRevocationFlow} — 재인증은 민감 command가 아니라 그 전제다)이 각자 들고 있던
 * {@code arm/peek/peekIncludingExpired/consume/discard} 본문이 이것 하나다. 각 Flow는 자기 속성 키·수명·{@code Armed} record와 고유 메서드만 남기고 공통 동작을 여기에
 * 위임한다 — Epic 3이 운영자 command를 더할 때 일곱 번째 사본이 생기지 않게 하려는 것이다(Epic 2 회고 AI-8).
 *
 * <p><b>정책은 여기 없다.</b> 속성 키, 5분 수명, record의 필드, 성공 시 무장을 지우는지 남기는지(재전송 계약), 세션
 * id 귀속 방식은 전부 각 Flow와 그 서비스가 정한다. 이 클래스가 아는 것은 "무장을 저장하고, 살아 있는 것만
 * 읽고, 수명이 지난 것은 읽는 자리에서 버린다"는 동작뿐이다.
 *
 * <p>수명 판정은 {@code armedAt}을 꺼내는 함수 하나로 한다. 각 record의 {@code isLive(now)}와 같은 식
 * ({@code now < armedAt + lifetime})이며, record 쪽 메서드는 시험과 콜백 경계가 그대로 쓰므로 남겨 둔다.
 *
 * @param <T> 세션에 실리는 무장 record. {@code spring_session}의 부가 상태는 Java 직렬화 {@code ATTRIBUTE_BYTES}에만
 *     살므로 {@link Serializable}이어야 한다
 */
final class SessionArming<T extends Serializable> {

    private final String attribute;

    private final Class<T> type;

    private final Duration lifetime;

    private final Function<T, Instant> armedAt;

    /**
     * @param attribute 무장이 사는 세션 속성 이름. Flow마다 <b>다른</b> 값이어야 한다 — 겸용하면 어느 무장이 살아
     *     있는지가 값의 내용에 숨는다
     * @param type 무장 record의 타입. 타입이 어긋난 값은 없는 것과 같다
     * @param lifetime 무장의 수명. 허가의 수명({@code ActionGrant.LIFETIME})과 같은 숫자여도 다른 상수다
     * @param armedAt record에서 무장 시각을 꺼내는 함수
     */
    SessionArming(String attribute, Class<T> type, Duration lifetime, Function<T, Instant> armedAt) {
        this.attribute = attribute;
        this.type = type;
        this.lifetime = lifetime;
        this.armedAt = armedAt;
    }

    /** 이 무장이 아직 살아 있는가. 각 record의 {@code isLive}와 같은 식이다. */
    boolean isLive(T armed, Instant now) {
        return now.isBefore(this.armedAt.apply(armed).plus(this.lifetime));
    }

    /**
     * 이 세션에 무장을 심는다. 앞선 무장이 있으면 덮는다.
     *
     * <p>덮이는 무장의 허가를 함께 무효화하는 것은 이 메서드가 아니라 각 서비스의 일이다 — 흐름만 버리는 메서드에 그
     * 짝을 숨기면 "흐름만 버린다"는 이름이 거짓이 된다.
     */
    void arm(HttpSession session, T armed) {
        session.setAttribute(this.attribute, armed);
    }

    /**
     * 살아 있는 무장을 읽되 소비하지 않는다.
     *
     * <p>수명이 지난 무장은 없는 것과 같고, 그 자리에서 버린다 — 남겨 두면 다음 요청이 같은 판정을 다시 하고 잊힌
     * 값이 세션에 계속 실린다.
     *
     * @return 살아 있는 무장, 없으면 {@code null}
     */
    @Nullable T peek(HttpSession session) {
        T armed = peekIncludingExpired(session);
        if (armed == null) {
            return null;
        }
        if (!isLive(armed, Instant.now())) {
            discard(session);
            return null;
        }
        return armed;
    }

    /** {@link #peek(HttpSession)}의 요청 오버로드. 세션이 없는 콜백 경계가 쓴다. */
    @Nullable T peek(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : peek(session);
    }

    /**
     * 수명이 지난 무장까지 그대로 읽는다. 버리지 않는다.
     *
     * <p>두 종류의 경로가 쓴다 — 버릴 무장의 허가를 함께 무효화하는 경로(수명이 지난 무장의 허가도 아직 살아 있을 수
     * 있다)와, "무장이 없었다"와 "무장이 있었는데 늦었다"를 갈라 이유를 말해야 하는 콜백 경계다.
     *
     * <p>타입이 어긋난 값은 없는 것과 같되 <b>버리지 않는다</b>(만료 무장을 버리는 {@link #peek}과 다른 비대칭). record의
     * 직렬화 버전이 바뀐 뒤 옛 세션에 남은 값이 그 경우이고, 그것을 여기서 지우면 읽기 메서드가 세션을 바꾸는 셈이 된다 —
     * 다음 {@link #arm}이 덮고 수명이 지나면 세션과 함께 사라진다.
     *
     * @return 저장된 무장, 없으면 {@code null}
     */
    @Nullable T peekIncludingExpired(HttpSession session) {
        Object stored = session.getAttribute(this.attribute);
        return this.type.isInstance(stored) ? this.type.cast(stored) : null;
    }

    /** {@link #peekIncludingExpired(HttpSession)}의 요청 오버로드. */
    @Nullable T peekIncludingExpired(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : peekIncludingExpired(session);
    }

    /**
     * 살아 있는 무장을 읽고 지운다. 콜백 하나로 흐름이 끝나는 Flow(재인증·연결)가 쓴다.
     *
     * @return 살아 있던 무장, 없으면 {@code null}
     */
    @Nullable T consume(HttpServletRequest request) {
        T armed = peek(request);
        discard(request);
        return armed;
    }

    /** 무장만 버린다. 세션도 허가도 건드리지 않는다. */
    void discard(HttpSession session) {
        session.removeAttribute(this.attribute);
    }

    /** {@link #discard(HttpSession)}의 요청 오버로드. 세션이 없으면 아무것도 하지 않는다. */
    void discard(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            discard(session);
        }
    }
}
