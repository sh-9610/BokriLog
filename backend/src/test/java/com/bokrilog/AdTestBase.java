package com.bokrilog;

import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.engagement.internal.AdProviderTestHook;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 대시보드 광고 설정과 계측 시험의 공용 준비.
 *
 * <p>{@link OperatorTestBase}의 컨텍스트를 그대로 쓴다 — {@code @DynamicPropertySource}·{@code @MockitoBean}·
 * {@code @TestPropertySource}·설정 클래스를 두지 않으므로 Spring 시험 컨텍스트가 하나 더 생기지 않는다({@link AuthFlowTestBase}).
 * 공급자는
 * 시험 컨텍스트에서 켜져 있고(시험 속성) 꺼짐을 보는 시험이 {@link AdProviderTestHook}으로 잠시 끄므로, 시험마다 켜짐으로 되돌린다.
 *
 * <p>시험의 과거 광고 참조는 실행 시점에 만든다. 참조는
 * {@link SecureRandom}의 {@link AdProviderTestHook#TRANSITION_BYTES} 바이트다(토큰 모양 리터럴은 gitleaks가 잡는다). 이 helper들의
 * 사본은 {@code AdTestBaseTest}가 막는다.
 */
abstract class AdTestBase extends OperatorTestBase {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    EngagementDeletionApi deletion;

    @Autowired
    ApplicationContext context;

    @AfterEach
    void providerBackOn() {
        AdProviderTestHook.providerEnabled(this.context, true);
    }

    /** 발급 모양(운영 바이트 수의 URL-safe Base64, 패딩 없음)의 새 transition 참조. */
    static String newTransition() {
        byte[] bytes = new byte[AdProviderTestHook.TRANSITION_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
