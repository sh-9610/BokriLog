package com.bokrilog;

import com.bokrilog.identity.CompletionSessionAttributes;
import java.util.List;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;

/**
 * 완료 확인 전용 세션이 남기는 속성 이름({@link CompletionSessionAttributes}, Story 5.4)의 루트 구현(Epic 5 회고 A-2). 이름은 Spring
 * Security 저장소가 쓰는 기본 이름이고 그 저장소들의 상수는 공개되지 않아 같은 식(클래스 이름 + 접미사)으로 만든다(사본 리터럴을 두지
 * 않는다).
 *
 * <ul>
 *   <li>CSRF 토큰({@code HttpSessionCsrfTokenRepository}) — 인증 표면이 그 저장소를 기본 이름으로 쓴다
 *       ({@code AuthSecurityConfig.SessionBoundCsrfTokenRepository}). 남겨야 화면이 들고 있는 토큰으로 로그아웃할 수 있다
 *       ({@code DeletionCompletionTest}가 그 왕복을 확인한다).
 *   <li>OAuth2 인가 요청({@code HttpSessionOAuth2AuthorizationRequestRepository}) — 로그인 시작 중인 세션의 진행 흐름이다.
 * </ul>
 *
 * <p>웹 조건 없이 모든 역할에서 배선한다 — identity 서비스가 모든 역할(삭제 표본·복원 적용 포함)에 있다({@link DeletedIdentityDigestPort}와
 * 같은 관용).
 */
@Component
class CompletionSessionAttributesPort implements CompletionSessionAttributes {

    private static final List<String> KEPT = List.of(
            HttpSessionCsrfTokenRepository.class.getName() + ".CSRF_TOKEN",
            HttpSessionOAuth2AuthorizationRequestRepository.class.getName() + ".AUTHORIZATION_REQUEST");

    @Override
    public List<String> keptAttributeNames() {
        return KEPT;
    }
}
