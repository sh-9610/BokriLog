package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/**
 * 세션 무장 메커니즘 한 벌. 여섯 Flow가 위임하는 동작을 Spring 컨텍스트 없이 고정한다 — 만료 무장은 읽는 자리에서
 * 버리고, 타입이 어긋난 값은 없는 것과 같되 버리지 않으며, 세션 없는 요청 오버로드는 null/no-op이고,
 * {@code consume}은 {@code peek} + {@code discard}다.
 */
class SessionArmingTest {

    private static final String ATTRIBUTE = "bokrilog.test.arming";

    private static final Duration LIFETIME = Duration.ofMinutes(5);

    /** 시험용 무장 record. 필드는 무장 시각 하나다. */
    private record Armed(Instant armedAt) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
    }

    private final SessionArming<Armed> arming = new SessionArming<>(ATTRIBUTE, Armed.class, LIFETIME, Armed::armedAt);

    @Test
    void 살아_있는_무장은_읽어도_남는다() {
        MockHttpSession session = new MockHttpSession();
        Armed armed = new Armed(Instant.now());
        arming.arm(session, armed);

        assertThat(arming.peek(session)).isEqualTo(armed);
        assertThat(arming.peek(session)).as("peek은 소비하지 않는다").isEqualTo(armed);
        assertThat(arming.peekIncludingExpired(session)).isEqualTo(armed);
        assertThat(arming.isLive(armed, Instant.now())).isTrue();
    }

    @Test
    void 수명이_지난_무장은_peek이_그_자리에서_버린다() {
        MockHttpSession session = new MockHttpSession();
        Armed expired = new Armed(Instant.now().minus(LIFETIME).minusSeconds(1));
        arming.arm(session, expired);

        assertThat(arming.isLive(expired, Instant.now())).isFalse();
        assertThat(arming.peekIncludingExpired(session)).as("만료 무장도 그대로 읽는다").isEqualTo(expired);
        assertThat(arming.peek(session)).isNull();
        assertThat(session.getAttribute(ATTRIBUTE)).as("peek이 버렸다").isNull();
        assertThat(arming.peekIncludingExpired(session)).isNull();
    }

    @Test
    void 타입이_어긋난_값은_없는_것과_같되_버리지_않는다() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ATTRIBUTE, "옛 직렬화 버전의 값");

        assertThat(arming.peek(session)).isNull();
        assertThat(arming.peekIncludingExpired(session)).isNull();
        // 읽기 메서드는 세션을 바꾸지 않는다. 다음 arm이 덮는다.
        assertThat(session.getAttribute(ATTRIBUTE)).isEqualTo("옛 직렬화 버전의 값");
        arming.arm(session, new Armed(Instant.now()));
        assertThat(arming.peek(session)).isNotNull();
    }

    /**
     * 세션 저장소가 풀 수 없던 값(배포 전 무장·깨진 바이트)은 표지 {@link UnreadableSessionValue}로 읽힌다
     * ({@code SessionAttributeConversion}). 없는 무장과 같고, 그 속성 하나만 버린다 — 다른 속성은 건드리지 않는다.
     */
    @Test
    void 읽을_수_없는_값은_없는_무장과_같고_그_속성만_버린다() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ATTRIBUTE, new UnreadableSessionValue("StreamCorruptedException"));
        session.setAttribute("other", "값");

        assertThat(arming.peekIncludingExpired(session)).isNull();
        assertThat(session.getAttribute(ATTRIBUTE)).as("그 속성만 버렸다").isNull();
        assertThat(session.getAttribute("other")).isEqualTo("값");

        // 없는 무장은 그냥 없는 무장이다 — 아무것도 지우지 않는다.
        assertThat(arming.peek(session)).isNull();
        assertThat(session.getAttribute("other")).isEqualTo("값");
    }

    @Test
    void 세션이_없는_요청_오버로드는_null이고_아무것도_하지_않는다() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(arming.peek(request)).isNull();
        assertThat(arming.peekIncludingExpired(request)).isNull();
        assertThat(arming.consume(request)).isNull();
        arming.discard(request);
        // 세션을 만들지 않았다.
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void consume은_peek과_discard다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        Armed armed = new Armed(Instant.now());
        arming.arm(session, armed);

        assertThat(arming.peek(request)).isEqualTo(armed);
        assertThat(arming.consume(request)).isEqualTo(armed);
        assertThat(arming.peek(request)).as("한 번뿐이다").isNull();
        assertThat(session.getAttribute(ATTRIBUTE)).isNull();

        // 만료 무장의 consume은 null이고 그래도 버린다.
        arming.arm(session, new Armed(Instant.now().minus(LIFETIME).minusSeconds(1)));
        assertThat(arming.consume(request)).isNull();
        assertThat(session.getAttribute(ATTRIBUTE)).isNull();
    }

    @Test
    void discard는_무장만_버리고_다른_속성은_건드리지_않는다() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("other", "값");
        arming.arm(session, new Armed(Instant.now()));

        arming.discard(session);

        assertThat(session.getAttribute(ATTRIBUTE)).isNull();
        assertThat(session.getAttribute("other")).isEqualTo("값");
        assertThat(session.isInvalid()).isFalse();
    }
}
