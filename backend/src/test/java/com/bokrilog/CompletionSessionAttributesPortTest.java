package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * 완료 확인 전용 세션이 남기는 속성 이름의 루트 구현(Epic 5 회고 A-2). Spring 컨텍스트 없이 본다.
 *
 * <p>이름은 인증 표면이 쓰는 두 저장소의 기본 세션 속성 이름이어야 한다 — 우리 쪽 식을 바꾸거나 Spring이 기본 이름을 바꾸면 완료 세션이
 * CSRF 토큰을 잃어(로그아웃 403) 이 시험이 먼저 깨진다. 그래서 같은 식으로 계산한 값과, 실제 저장소가 세션에 쓴 속성 이름 둘 다와 대조한다.
 * 인증 표면({@code AuthSecurityConfig})은 CSRF 저장소를 기본 속성 이름으로 감싸 쓰고 OAuth2 인가 요청 저장소는 기본값을 쓴다.
 */
class CompletionSessionAttributesPortTest {

    private final CompletionSessionAttributesPort port = new CompletionSessionAttributesPort();

    @Test
    void 비어_있지_않고_바꿀_수_없다() {
        List<String> names = this.port.keptAttributeNames();
        assertThat(names).isNotEmpty().doesNotContainNull();
        assertThatThrownBy(() -> names.add("other")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(names::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 루트와_같은_식으로_만든_두_기본_이름이다() {
        assertThat(this.port.keptAttributeNames()).containsExactly(
                HttpSessionCsrfTokenRepository.class.getName() + ".CSRF_TOKEN",
                HttpSessionOAuth2AuthorizationRequestRepository.class.getName() + ".AUTHORIZATION_REQUEST");
    }

    @Test
    void 두_저장소가_실제로_세션에_쓰는_속성_이름과_같다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpSessionCsrfTokenRepository csrf = new HttpSessionCsrfTokenRepository();
        csrf.saveToken(csrf.generateToken(request), request, response);
        OAuth2AuthorizationRequest authorization = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://provider.test/authorize")
                .clientId("completion-session-test")
                .redirectUri("https://bokrilog.test/api/auth/callback/google")
                .state("completion-session-state")
                .build();
        new HttpSessionOAuth2AuthorizationRequestRepository().saveAuthorizationRequest(authorization, request, response);

        List<String> written = Collections.list(request.getSession().getAttributeNames());
        assertThat(this.port.keptAttributeNames()).containsExactlyInAnyOrderElementsOf(written);
    }
}
