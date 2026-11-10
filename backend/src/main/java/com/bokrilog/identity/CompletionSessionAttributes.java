package com.bokrilog.identity;

import java.util.List;

/**
 * 완료 확인 전용 세션(Story 5.4)에 남기는 세션 속성의 이름. <b>identity가 소유하고 루트가 구현한다</b> — 이름은 인증 표면(Spring Security
 * 저장소)이 정하는데 identity는 그 타입에 의존하지 않는다(Epic 5 회고 A-2, {@code ModuleBoundaryTest}가 모듈의
 * {@code org.springframework.security} 의존을 막는다).
 *
 * <p>요청 세션을 완료 세션으로 바꿀 때와 완료 세션을 주기적으로 정리할 때 identity가 이 이름 말고 모든 속성을 지운다. 남기는 속성은 사용자를
 * 가리키지 않는다(CSRF 토큰·OAuth2 인가 요청).
 */
public interface CompletionSessionAttributes {

    /**
     * 남기는 속성 이름. 비어 있지 않다.
     *
     * @return 변하지 않는 목록
     */
    List<String> keptAttributeNames();
}
