package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.AuthSecurityConfig.AuthProperties;
import com.bokrilog.AuthSecurityConfig.ProviderClientRegistrations;
import com.bokrilog.DevOidcStub.TokenPlan;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 카카오 왕복.
 *
 * <p>Story 1.4가 세운 공통 OIDC·세션 계약을 그대로 재사용한다. 그래서 이 파일이 보는 것은 "그 계약이
 * 세 번째 제공자에도 예외 없이 적용되는가"이며, 검증 실패의 축 열여덟 개는 되풀지 않는다 —
 * {@code AuthTokenValidationTest}가 이미 제공자에 종속되지 않는 형태로 덮고 있고, 축을 제공자로
 * 곱하면 같은 것을 두 번 보는 시험만 늘어난다. 같은 이유로 issuer 충돌 기동 거부도 여기 없다
 * ({@code AuthNaverSignInTest}가 그 계약의 단일 소유자이고 판정은 제공자에 종속되지 않는다).
 *
 * <p>제공자별 fail-closed 독립도 여기서 본다. 그 판정은 컨텍스트가 아니라
 * {@link ProviderClientRegistrations#from} 하나가 소유하므로 새 {@code @DynamicPropertySource}를
 * 만들지 않고 그 메서드를 직접 부른다 — 컨텍스트를 갈라 놓으면 {@code backend-quality}의 wall-clock이
 * 그만큼 늘어난다.
 */
class AuthKakaoSignInTest extends AuthFlowTestBase {

    private static final String KAKAO = AuthSecurityConfig.KAKAO_REGISTRATION_ID;

    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    private static final String NAVER = AuthSecurityConfig.NAVER_REGISTRATION_ID;

    @Test
    void 카카오_인가_시작은_공통_불변식을_그대로_지킨다() throws Exception {
        Authorization authorization = startAuthorization(KAKAO, null, null);

        assertThat(authorization.status()).isEqualTo(302);
        // 제공자(=그 제공자의 스텁)의 인가 엔드포인트로 보낸다.
        assertThat(authorization.location())
                .startsWith(OidcStubConfiguration.issuer(KAKAO) + "/authorize");

        Map<String, String> parameters = authorization.parameters();
        assertThat(parameters).containsEntry("response_type", "code");
        assertThat(parameters).containsEntry("client_id", OidcStubConfiguration.clientId(KAKAO));
        // 동의 범위는 `openid` 하나다. 정확히 비교한다 — 부분 문자열로 보면 이메일·프로필 동의가
        // 붙어도 통과한다. 카카오 콘솔은 그 동의 항목을 앱 단위로 열 수 있으므로 요청 파라미터가
        // 그것을 요구하지 않는다는 사실이 여기서 고정된다.
        assertThat(parameters).containsEntry("scope", "openid");
        assertThat(parameters).containsEntry("code_challenge_method", "S256");
        assertThat(parameters)
                .containsEntry(
                        "redirect_uri", OidcStubConfiguration.PUBLIC_BASE_URL + callbackPath(KAKAO));
        assertThat(parameters.get("state")).isNotBlank();
        // 카카오 discovery는 `claims_supported`에 `nonce`를 명시한다. 네이버가 남긴 실연동 위험이
        // 여기에는 없고, 그래서 이 계약을 그대로 두는 데 아무 대가도 치르지 않는다.
        assertThat(parameters.get("nonce")).isNotBlank();
        assertThat(parameters.get("code_challenge")).isNotBlank();
        // verifier는 서버가 세션에만 들고 있다. 제공자에게 가는 것은 challenge뿐이다.
        assertThat(parameters).doesNotContainKey("code_verifier");
        assertThat(authorization.location()).doesNotContain("code_verifier");
    }

    @Test
    void 신규_카카오_식별자는_계정과_매핑을_하나씩_만들고_온보딩으로_보낸다() throws Exception {
        String subject = "kakao-new-" + UUID.randomUUID();

        HttpResponse<String> response = signIn(KAKAO, subject);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(userIdOf(OidcStubConfiguration.issuer(KAKAO), subject)).isPresent();
        // 저장된 이름이 인증에 실제로 쓰인 registration id다.
        assertThat(linkedProviderOf(OidcStubConfiguration.issuer(KAKAO), subject)).contains(KAKAO);
        // 세션 ID가 회전했다. 로그인 성공이 그 회전의 유일한 시점이다.
        assertThat(sessionCookieOf(response)).isPresent();
        assertThat(sessionCount()).isEqualTo(1);
    }

    @Test
    void 기존_카카오_식별자는_같은_user_id로_대시보드로_간다() throws Exception {
        String subject = "kakao-returning-" + UUID.randomUUID();

        signIn(KAKAO, subject);
        UUID first = userIdOf(OidcStubConfiguration.issuer(KAKAO), subject).orElseThrow();

        HttpResponse<String> second = signIn(KAKAO, subject);

        assertThat(second.statusCode()).isEqualTo(302);
        assertThat(locationOf(second)).endsWith(AuthFlowHandlers.DASHBOARD_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(userIdOf(OidcStubConfiguration.issuer(KAKAO), subject)).contains(first);
    }

    @Test
    void 카카오_취소는_상태를_바꾸지_않고_cancelled로_수렴한다() throws Exception {
        Authorization authorization = startAuthorization(KAKAO, null, null);

        HttpResponse<String> response = callbackWithError(authorization, "access_denied");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response))
                .endsWith(
                        AuthFlowHandlers.ENTRY_PATH
                                + "?"
                                + AuthFlowHandlers.FAILURE_MARKER_PARAMETER
                                + "="
                                + AuthFlowHandlers.FAILURE_MARKER_CANCELLED);
        assertThat(accountCount()).isZero();
        assertThat(socialLinkCount()).isZero();
        assertThat(sessionCount()).isZero();
        // 제공자 이름도 제공자 오류 문구도 목적지에 실리지 않는다.
        assertThat(locationOf(response)).doesNotContain(KAKAO);
        assertThat(locationOf(response)).doesNotContain("access_denied");
    }

    @Test
    void 카카오_제공자_오류는_failed_하나로_수렴한다() throws Exception {
        Authorization authorization = startAuthorization(KAKAO, null, null);

        HttpResponse<String> response = callbackWithError(authorization, "server_error");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response))
                .endsWith(
                        AuthFlowHandlers.ENTRY_PATH
                                + "?"
                                + AuthFlowHandlers.FAILURE_MARKER_PARAMETER
                                + "="
                                + AuthFlowHandlers.FAILURE_MARKER_FAILED);
        assertThat(accountCount()).isZero();
        assertThat(socialLinkCount()).isZero();
        assertThat(sessionCount()).isZero();
        assertThat(locationOf(response)).doesNotContain("server_error");
    }

    @Test
    void 카카오_토큰_엔드포인트_장애도_failed로_수렴하고_상태를_남기지_않는다() throws Exception {
        Authorization authorization = startAuthorization(KAKAO, null, null);
        String code =
                issueCode(
                        authorization,
                        TokenPlan.valid(authorization.nonce()).tokenEndpointFailure());

        HttpResponse<String> response = callback(authorization, code);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response))
                .endsWith(
                        AuthFlowHandlers.ENTRY_PATH
                                + "?"
                                + AuthFlowHandlers.FAILURE_MARKER_PARAMETER
                                + "="
                                + AuthFlowHandlers.FAILURE_MARKER_FAILED);
        assertThat(accountCount()).isZero();
        assertThat(socialLinkCount()).isZero();
        assertThat(sessionCount()).isZero();
    }

    @Test
    void 이미_인증된_세션은_카카오_가입을_시작하지_않는다() throws Exception {
        String subject = "kakao-authenticated-" + UUID.randomUUID();
        HttpResponse<String> login = signIn(KAKAO, subject);
        String cookie = sessionCookieOf(login).orElseThrow();

        Authorization second = startAuthorization(KAKAO, null, cookie);

        assertThat(second.status()).isEqualTo(302);
        // 제공자가 아니라 지금 세션의 목적지로 보낸다. 두 번째 신규 가입을 열지 않는다.
        assertThat(second.location()).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(second.location()).doesNotContain(OidcStubConfiguration.issuer(KAKAO));
        assertThat(second.parameters()).doesNotContainKey("state");
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);
    }

    @Test
    void 세_제공자가_함께_구성된_스택에서_셋_다_인가를_시작한다() throws Exception {
        /*
         * 이 컨텍스트의 등록은 셋이다. 한 제공자를 더하면서 다른 제공자의 인가 시작이 죽는 회귀는
         * 여기서 드러난다 — 등록 저장소가 한 제공자만 들고 있으면 나머지가 공통 오류 계약으로
         * 떨어진다.
         */
        Authorization google = startAuthorization(GOOGLE, null, null);
        Authorization naver = startAuthorization(NAVER, null, null);
        Authorization kakao = startAuthorization(KAKAO, null, null);

        assertThat(google.status()).isEqualTo(302);
        assertThat(naver.status()).isEqualTo(302);
        assertThat(kakao.status()).isEqualTo(302);
        assertThat(google.location()).startsWith(OidcStubConfiguration.issuer(GOOGLE) + "/authorize");
        assertThat(naver.location()).startsWith(OidcStubConfiguration.issuer(NAVER) + "/authorize");
        assertThat(kakao.location()).startsWith(OidcStubConfiguration.issuer(KAKAO) + "/authorize");
        // 세 흐름의 issuer·client가 서로 다르다. 같으면 한 스텁이 다른 제공자의 토큰을 검증한다.
        assertThat(
                        List.of(
                                OidcStubConfiguration.issuer(GOOGLE),
                                OidcStubConfiguration.issuer(NAVER),
                                OidcStubConfiguration.issuer(KAKAO)))
                .doesNotHaveDuplicates();
        assertThat(
                        List.of(
                                String.valueOf(google.parameters().get("client_id")),
                                String.valueOf(naver.parameters().get("client_id")),
                                String.valueOf(kakao.parameters().get("client_id"))))
                .doesNotHaveDuplicates();
    }

    @Test
    void 카카오가_구성되지_않아도_나머지_두_제공자의_등록은_살아_있다() {
        /*
         * I/O 매트릭스의 "카카오 미구성" 행이다. 판정은 제공자 단위로 독립해야 한다 — 한 번에
         * 접으면 카카오 자격정보가 없는 개발 환경에서 Google·네이버 로그인까지 죽는다.
         *
         * 컨텍스트를 새로 띄우지 않는다. 이 판정의 소유자는 `from()` 하나이고, 그것을 직접 부르는
         * 편이 무엇이 판정하는지도 분명하다. 구성되지 않은 제공자의 인가 시작이 공통 오류 계약으로
         * 답하는 것은 `StatusApiTest`가 실제 HTTP로 확인한다.
         */
        ProviderClientRegistrations withoutKakao =
                ProviderClientRegistrations.from(
                        new AuthProperties(
                                OidcStubConfiguration.PUBLIC_BASE_URL,
                                credentials(),
                                credentials(),
                                null));

        assertThat(withoutKakao.isConfigured()).isTrue();
        assertThat(withoutKakao.findByRegistrationId(GOOGLE)).isNotNull();
        assertThat(withoutKakao.findByRegistrationId(NAVER)).isNotNull();
        assertThat(withoutKakao.findByRegistrationId(KAKAO)).isNull();

        ProviderClientRegistrations kakaoOnly =
                ProviderClientRegistrations.from(
                        new AuthProperties(
                                OidcStubConfiguration.PUBLIC_BASE_URL, null, null, credentials()));

        assertThat(kakaoOnly.isConfigured()).isTrue();
        assertThat(kakaoOnly.findByRegistrationId(KAKAO)).isNotNull();
        assertThat(kakaoOnly.findByRegistrationId(GOOGLE)).isNull();
        assertThat(kakaoOnly.findByRegistrationId(NAVER)).isNull();
    }

    @Test
    void 카카오는_client_secret이_없으면_등록되지_않는다() {
        /*
         * 카카오 콘솔에서 Client Secret은 선택 기능이다. 게이트를 무르면 "자격정보 셋이 다 있어야
         * 등록이 생긴다"는 fail-closed의 모양이 제공자마다 달라지고, 그 차이는 구성 실수를 조용한
         * 성공으로 바꾼다. 이 스토리는 무르지 않기로 했고 그 결정이 여기 고정된다.
         */
        ProviderClientRegistrations secretless =
                ProviderClientRegistrations.from(
                        new AuthProperties(
                                OidcStubConfiguration.PUBLIC_BASE_URL,
                                null,
                                null,
                                new AuthProperties.Provider(
                                        "client-" + UUID.randomUUID(), null, null, null, null, null)));

        assertThat(secretless.findByRegistrationId(KAKAO)).isNull();
        assertThat(secretless.isConfigured()).isFalse();
    }

    /** 값은 실행 시점에 만든다. 자격정보 모양 리터럴을 시험 fixture에도 두지 않는다. */
    private static AuthProperties.Provider credentials() {
        return new AuthProperties.Provider(
                "client-" + UUID.randomUUID(), UUID.randomUUID().toString(), null, null, null, null);
    }
}
