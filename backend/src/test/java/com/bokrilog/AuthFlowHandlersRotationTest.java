package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/**
 * 허가 소비 성공이 요구하는 세션 재회전.
 *
 * <p><b>HTTP 왕복에서는 이 회전이 관측되지 않는다.</b> 세션 고정 방어가 검증 직후 이미 한 번
 * 회전시키므로 "쿠키가 바뀌었다"는 두 번째 회전 없이도 참이고, Spring Session은 요청 끝에 최종
 * id 하나만 쓴다. 그래서 `request.changeSessionId()`를 지워도 왕복 단정은 전부 통과한다(변이로
 * 확인했다). 그 사실이 이 파일이 있는 이유다 — {@code SECURITY-ACTION-1.0}이 요구하는 재회전을
 * 어느 층도 보지 않는 상태를 남기지 않는다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다. 보는 것이 배선이 아니라 판정 하나이기 때문이다 — 소비에
 * 성공했을 때만 회전하고 거부에서는 하지 않는다. {@code MockHttpServletRequest}를 감싸 그 호출을
 * 센다.
 */
class AuthFlowHandlersRotationTest {

    @Test
    void 연결에_성공하면_세션_ID를_다시_회전시킨다() {
        RotationRecordingRequest request = new RotationRecordingRequest();
        String before = request.getSession().getId();
        ReauthenticationFlow.recordAuthentication(request.getSession(), Instant.now());

        String rotated = AuthFlowHandlers.rotateIfLinked(request, LinkService.Outcome.LINKED);

        assertThat(request.rotations()).isEqualTo(1);
        assertThat(rotated).isNotNull().isNotEqualTo(before);
        assertThat(request.getSession().getId()).isEqualTo(rotated);
        // 연결은 민감 command 하나다. 실행 성공이 재인증 시각을 지운다 — 본인 확인 1회는 command 1건.
        assertThat(ReauthenticationFlow.lastAuthenticatedAt(request.getSession())).isNull();
    }

    @Test
    void 연결이_거부되면_회전시키지_않는다() {
        RotationRecordingRequest request = new RotationRecordingRequest();
        String before = request.getSession().getId();

        /*
         * 거부에서 회전하면 사용자는 아무 일도 일어나지 않은 채 세션 식별자만 잃는다. 회전이
         * 소비 성공에 묶여 있다는 것이 `SECURITY-ACTION-1.0`의 계약이고, 그 묶임을 보는 자리가
         * 여기다.
         */
        Instant reauthenticatedAt = Instant.now();
        ReauthenticationFlow.recordAuthentication(request.getSession(), reauthenticatedAt);
        assertThat(AuthFlowHandlers.rotateIfLinked(request, LinkService.Outcome.DENIED)).isNull();

        assertThat(request.rotations()).isZero();
        assertThat(request.getSession().getId()).isEqualTo(before);
        // 거부는 아무것도 실행하지 않았다. 같은 재인증으로 다시 시작할 수 있어야 하므로 시각이 남는다.
        assertThat(ReauthenticationFlow.lastAuthenticatedAt(request.getSession())).isEqualTo(reauthenticatedAt);
    }

    /** {@code changeSessionId()} 호출을 세는 요청. 세션 하나를 미리 들고 있다. */
    private static final class RotationRecordingRequest extends MockHttpServletRequest {

        private final AtomicInteger rotations = new AtomicInteger();

        private RotationRecordingRequest() {
            setSession(new MockHttpSession());
        }

        @Override
        public String changeSessionId() {
            this.rotations.incrementAndGet();
            return super.changeSessionId();
        }

        int rotations() {
            return this.rotations.get();
        }
    }
}
