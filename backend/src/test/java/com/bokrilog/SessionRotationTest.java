package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/**
 * 민감 command 실행 성공 뒤의 세션 처리 한 벌. 시각 제거가 회전보다 먼저이고, {@code completeSensitiveCommand}는
 * 회전 예외를 삼키되 그 전에 시각을 지우며, {@code rotateAfterSensitiveCommand}는 예외를 전파한다 — Spring 컨텍스트
 * 없이 고정한다.
 */
class SessionRotationTest {

    /** 회전이 실패하는 요청. 세션 하나를 미리 들고 있다. */
    private static final class FailingRotationRequest extends MockHttpServletRequest {

        private FailingRotationRequest() {
            setSession(new MockHttpSession());
        }

        @Override
        public String changeSessionId() {
            throw new IllegalStateException("회전 실패");
        }
    }

    @Test
    void 완료는_시각을_지우고_세션을_회전시킨다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        ReauthenticationFlow.recordAuthentication(session, Instant.now());
        String before = session.getId();

        SessionRotation.completeSensitiveCommand(request);

        assertThat(ReauthenticationFlow.lastAuthenticatedAt(session)).isNull();
        assertThat(request.getSession().getId()).isNotEqualTo(before);
        assertThat(request.getSession()).as("속성을 보존하는 회전이다").isSameAs(session);
    }

    @Test
    void 완료는_회전_예외를_삼키되_그_전에_시각을_지운다() {
        FailingRotationRequest request = new FailingRotationRequest();
        ReauthenticationFlow.recordAuthentication(request.getSession(), Instant.now());

        SessionRotation.completeSensitiveCommand(request);

        // 업무 변경은 이미 커밋됐다. 회전이 실패해도 연쇄 승인은 닫혀 있어야 한다.
        assertThat(ReauthenticationFlow.lastAuthenticatedAt(request.getSession())).isNull();
    }

    @Test
    void 본체는_예외를_전파하고_새_세션_id를_돌려준다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        ReauthenticationFlow.recordAuthentication(session, Instant.now());
        String before = session.getId();

        String rotated = SessionRotation.rotateAfterSensitiveCommand(request);

        assertThat(rotated).isNotEqualTo(before).isEqualTo(request.getSession().getId());
        assertThat(ReauthenticationFlow.lastAuthenticatedAt(session)).isNull();

        FailingRotationRequest failing = new FailingRotationRequest();
        ReauthenticationFlow.recordAuthentication(failing.getSession(), Instant.now());
        assertThatThrownBy(() -> SessionRotation.rotateAfterSensitiveCommand(failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ReauthenticationFlow.lastAuthenticatedAt(failing.getSession())).as("제거가 회전보다 먼저다")
                .isNull();
    }

    @Test
    void 세션이_없는_요청은_시각_제거_없이_회전만_시도한다() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        // `changeSessionId`는 세션이 없으면 예외다. 완료는 그것을 삼키고 세션을 만들지 않는다.
        SessionRotation.completeSensitiveCommand(request);

        assertThat(request.getSession(false)).isNull();
    }
}
