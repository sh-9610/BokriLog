package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.AuthSecurityConfig.AuthProperties;
import com.bokrilog.AuthSecurityConfig.ProviderClientRegistrations;
import com.bokrilog.DevOidcStub.TokenPlan;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * 네이버 왕복.
 *
 * <p>Story 1.4가 세운 공통 OIDC·세션 계약을 그대로 재사용한다. 그래서 이 파일이 보는 것은 "그 계약이
 * 두 번째 제공자에도 예외 없이 적용되는가"이며, 검증 실패의 축 열여덟 개는 되풀지 않는다 —
 * {@code AuthTokenValidationTest}가 이미 제공자에 종속되지 않는 형태로 덮고 있고, 축을 제공자로
 * 곱하면 같은 것을 두 번 보는 시험만 늘어난다.
 *
 * <p>제공자별 fail-closed 독립도 여기서 본다. 그 판정은 컨텍스트가 아니라
 * {@link ProviderClientRegistrations#from} 하나가 소유하므로 새 {@code @DynamicPropertySource}를
 * 만들지 않고 그 메서드를 직접 부른다 — 컨텍스트를 갈라 놓으면 {@code backend-quality}의 wall-clock이
 * 그만큼 늘어난다.
 */
class AuthNaverSignInTest extends AuthFlowTestBase {

    private static final String NAVER = AuthSecurityConfig.NAVER_REGISTRATION_ID;

    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    private static final String KAKAO = AuthSecurityConfig.KAKAO_REGISTRATION_ID;

    @Test
    void 네이버_인가_시작은_공통_불변식을_그대로_지킨다() throws Exception {
        Authorization authorization = startAuthorization(NAVER, null, null);

        assertThat(authorization.status()).isEqualTo(302);
        // 제공자(=그 제공자의 스텁)의 인가 엔드포인트로 보낸다.
        assertThat(authorization.location())
                .startsWith(OidcStubConfiguration.issuer(NAVER) + "/authorize");

        Map<String, String> parameters = authorization.parameters();
        assertThat(parameters).containsEntry("response_type", "code");
        assertThat(parameters).containsEntry("client_id", OidcStubConfiguration.clientId(NAVER));
        // 동의 범위는 `openid` 하나다. 정확히 비교한다 — 부분 문자열로 보면 `openid profile`이
        // 그대로 통과해 "profile을 요청하지 않는다"가 확인되지 않는다.
        assertThat(parameters).containsEntry("scope", "openid");
        assertThat(parameters).containsEntry("code_challenge_method", "S256");
        assertThat(parameters).containsEntry(
                "redirect_uri", OidcStubConfiguration.PUBLIC_BASE_URL + callbackPath(NAVER));
        assertThat(parameters.get("state")).isNotBlank();
        // `nonce`를 그대로 보낸다. 네이버 공식 가이드가 이 파라미터를 언급하지 않지만 확인되지
        // 않은 이유로 보안 계약을 먼저 헐겁게 만들지 않는다(SPEC `## Design Notes`).
        assertThat(parameters.get("nonce")).isNotBlank();
        assertThat(parameters.get("code_challenge")).isNotBlank();
        // verifier는 서버가 세션에만 들고 있다. 제공자에게 가는 것은 challenge뿐이다.
        assertThat(parameters).doesNotContainKey("code_verifier");
        assertThat(authorization.location()).doesNotContain("code_verifier");
    }

    @Test
    void 신규_네이버_식별자는_계정과_매핑을_하나씩_만들고_온보딩으로_보낸다() throws Exception {
        String subject = "naver-new-" + UUID.randomUUID();

        HttpResponse<String> response = signIn(NAVER, subject);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(userIdOf(OidcStubConfiguration.issuer(NAVER), subject)).isPresent();
        // 세션 ID가 회전했다. 로그인 성공이 그 회전의 유일한 시점이다.
        assertThat(sessionCookieOf(response)).isPresent();
        assertThat(sessionCount()).isEqualTo(1);
    }

    @Test
    void 기존_네이버_식별자는_같은_user_id로_대시보드로_간다() throws Exception {
        String subject = "naver-returning-" + UUID.randomUUID();

        signIn(NAVER, subject);
        UUID first = userIdOf(OidcStubConfiguration.issuer(NAVER), subject).orElseThrow();

        HttpResponse<String> second = signIn(NAVER, subject);

        assertThat(second.statusCode()).isEqualTo(302);
        assertThat(locationOf(second)).endsWith(AuthFlowHandlers.DASHBOARD_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(userIdOf(OidcStubConfiguration.issuer(NAVER), subject)).contains(first);
    }

    @Test
    void 네이버_취소는_상태를_바꾸지_않고_cancelled로_수렴한다() throws Exception {
        Authorization authorization = startAuthorization(NAVER, null, null);

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
        assertThat(locationOf(response)).doesNotContain(NAVER);
        assertThat(locationOf(response)).doesNotContain("access_denied");
    }

    @Test
    void 네이버_제공자_오류는_failed_하나로_수렴한다() throws Exception {
        Authorization authorization = startAuthorization(NAVER, null, null);

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
    void 네이버_토큰_엔드포인트_장애도_failed로_수렴하고_상태를_남기지_않는다() throws Exception {
        Authorization authorization = startAuthorization(NAVER, null, null);
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
    void 이미_인증된_세션은_네이버_가입을_시작하지_않는다() throws Exception {
        String subject = "naver-authenticated-" + UUID.randomUUID();
        HttpResponse<String> login = signIn(NAVER, subject);
        String cookie = sessionCookieOf(login).orElseThrow();

        Authorization second = startAuthorization(NAVER, null, cookie);

        assertThat(second.status()).isEqualTo(302);
        // 제공자가 아니라 지금 세션의 목적지로 보낸다. 두 번째 신규 가입을 열지 않는다.
        assertThat(second.location()).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(second.location()).doesNotContain(OidcStubConfiguration.issuer(NAVER));
        assertThat(second.parameters()).doesNotContainKey("state");
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);
    }

    /*
     * "함께 구성된 스택에서 모든 제공자가 인가를 시작한다"는 축이 여기 없다. Story 1.6에는
     * `두_제공자가_함께_구성된_스택에서_둘_다_인가를_시작한다`가 있었지만, Story 1.7이
     * `AuthKakaoSignInTest.세_제공자가_함께_구성된_스택에서_셋_다_인가를_시작한다`를 들이면서 그것의
     * 진부분집합이 됐다 — 같은 컨텍스트에서 같은 것을 더 좁게 보는 시험이라 남겨 두면 회귀를 하나도
     * 더 잡지 못하면서 다음 제공자마다 고쳐야 하는 자리만 늘어난다.
     */

    @Test
    void 한_제공자가_구성되지_않아도_다른_제공자의_등록은_살아_있다() {
        /*
         * I/O 매트릭스의 "네이버 미구성" 행이다. 판정은 제공자 단위로 독립해야 한다 — Story 1.6
         * 전의 `from()`은 세 값을 한 번에 AND로 접었고, 그 모양을 제공자 둘에 그대로 두면 네이버
         * 자격정보가 없는 개발 환경에서 Google 로그인까지 죽는다.
         *
         * 컨텍스트를 새로 띄우지 않는다. 이 판정의 소유자는 `from()` 하나이고, 그것을 직접 부르는
         * 편이 무엇이 판정하는지도 분명하다. 구성되지 않은 제공자의 인가 시작이 공통 오류 계약으로
         * 답하는 것은 `StatusApiTest`가 실제 HTTP로 확인한다.
         */
        AuthProperties.Provider credentials =
                new AuthProperties.Provider(
                        "client-" + UUID.randomUUID(),
                        UUID.randomUUID().toString(),
                        null,
                        null,
                        null,
                        null);

        ProviderClientRegistrations googleOnly =
                ProviderClientRegistrations.from(
                        new AuthProperties(
                                OidcStubConfiguration.PUBLIC_BASE_URL, credentials, null, null));

        assertThat(googleOnly.isConfigured()).isTrue();
        assertThat(googleOnly.findByRegistrationId(GOOGLE)).isNotNull();
        assertThat(googleOnly.findByRegistrationId(NAVER)).isNull();

        ProviderClientRegistrations naverOnly =
                ProviderClientRegistrations.from(
                        new AuthProperties(
                                OidcStubConfiguration.PUBLIC_BASE_URL, null, credentials, null));

        assertThat(naverOnly.isConfigured()).isTrue();
        assertThat(naverOnly.findByRegistrationId(NAVER)).isNotNull();
        assertThat(naverOnly.findByRegistrationId(GOOGLE)).isNull();
    }

    @Test
    void 어느_두_등록이_같은_issuer를_가리켜도_기동을_거부한다() {
        /*
         * 구성 오류 하나로 제공자 병합이 열리는 경로다.
         *
         * 계정 조회의 키는 `(issuer, subject)`이고 그 조합은 전역 유일이다
         * (`AccountLifecycleApi.findUserIdBySocialIdentity`). 그래서 `bokrilog.auth.naver.issuer-uri`에
         * Google의 issuer가 잘못 들어오면 네이버로 로그인한 `sub`이 Google 쪽 매핑과 같은 namespace에서
         * 조회되고, 그 매핑이 있으면 그 계정의 세션이 개설된다 — SPEC이 Never로 금지한 "두 제공자
         * 식별자의 병합·전환"이다.
         *
         * 조회 키에 제공자를 더하는 방향은 택하지 않았다. `identity` 공개 API 변경이고 이 스토리의
         * Ask First다. 대신 잘못 구성된 프로세스가 뜨지 못하게 한다.
         *
         * 조합을 셋 본다. 한 쌍만 보면 판정이 "앞 두 등록만" 비교하는 모양이어도 통과하고, 그때
         * 셋째 등록의 issuer 충돌은 조용히 기동에 성공한다 — 제공자가 늘어날수록 커지는 사각지대라
         * 세 번째가 들어오는 지금 닫는다. 마지막 조합은 Google 없이 세운다. 겹침 판정이 첫 등록과의
         * 비교로 좁혀지면 그 조합만 붉게 나온다.
         *
         * Google issuer를 리터럴로 적지 않는다. 그 값의 소유자는 `CommonOAuth2Provider.GOOGLE`이고,
         * 여기서 다시 적으면 두 곳이 갈라질 자리가 생긴다.
         */
        AuthProperties.Provider google = credentials();
        ClientRegistration googleRegistration =
                Objects.requireNonNull(
                        ProviderClientRegistrations.from(
                                        new AuthProperties(
                                                OidcStubConfiguration.PUBLIC_BASE_URL,
                                                google,
                                                null,
                                                null))
                                .findByRegistrationId(GOOGLE));
        String googleIssuer =
                Objects.requireNonNull(googleRegistration.getProviderDetails().getIssuerUri());

        // 1) 네이버가 Google issuer를 가리킨다.
        String naverClientId = "client-" + UUID.randomUUID();
        String naverClientSecret = UUID.randomUUID().toString();
        assertRefusesSharedIssuer(
                new AuthProperties(
                        OidcStubConfiguration.PUBLIC_BASE_URL,
                        google,
                        collidingWith(naverClientId, naverClientSecret, googleIssuer),
                        null),
                googleIssuer,
                naverClientId,
                naverClientSecret);

        // 2) 카카오가 Google issuer를 가리킨다. 셋째 등록도 같은 검사를 받는다.
        String kakaoClientId = "client-" + UUID.randomUUID();
        String kakaoClientSecret = UUID.randomUUID().toString();
        assertRefusesSharedIssuer(
                new AuthProperties(
                        OidcStubConfiguration.PUBLIC_BASE_URL,
                        google,
                        null,
                        collidingWith(kakaoClientId, kakaoClientSecret, googleIssuer)),
                googleIssuer,
                kakaoClientId,
                kakaoClientSecret);

        // 3) 손으로 조립한 두 등록끼리 겹친다. Google이 아예 없는 구성이다.
        String sharedIssuer = "https://shared-issuer.test";
        String pairClientId = "client-" + UUID.randomUUID();
        String pairClientSecret = UUID.randomUUID().toString();
        assertRefusesSharedIssuer(
                new AuthProperties(
                        OidcStubConfiguration.PUBLIC_BASE_URL,
                        null,
                        collidingWith(pairClientId, pairClientSecret, sharedIssuer),
                        collidingWith(pairClientId, pairClientSecret, sharedIssuer)),
                sharedIssuer,
                pairClientId,
                pairClientSecret);
    }

    /**
     * 그 구성이 기동에서 거부되고, 거부 메시지가 아무것도 흘리지 않는다.
     *
     * <p>메시지가 무엇을 말하지 <b>않는지</b>도 계약이다. 자격정보·환경 변수 이름·어느 제공자인지가
     * 기동 실패 로그에도 남지 않는다({@code DevOidcStubProfileTest}가 스텁 guard에 세운 것과 같은
     * 규율이다). 제공자 이름은 셋을 모두 본다 — 둘만 보면 셋째 이름이 메시지에 실리기 시작해도
     * 통과한다.
     */
    private static void assertRefusesSharedIssuer(
            AuthProperties collided, String issuer, String clientId, String clientSecret) {
        assertThatThrownBy(() -> ProviderClientRegistrations.from(collided))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(
                        thrown -> {
                            String message = String.valueOf(thrown.getMessage());
                            assertThat(message).doesNotContain("BOKRILOG_");
                            assertThat(message).doesNotContain("client-id");
                            assertThat(message).doesNotContain(clientId);
                            assertThat(message).doesNotContain(clientSecret);
                            assertThat(message).doesNotContain(issuer);
                            assertThat(message).doesNotContain(GOOGLE);
                            assertThat(message).doesNotContain(NAVER);
                            assertThat(message).doesNotContain(KAKAO);
                        });
    }

    /** 그 issuer를 가리키도록 재정의된 제공자 구성. */
    private static AuthProperties.Provider collidingWith(
            String clientId, String clientSecret, String issuerUri) {
        return new AuthProperties.Provider(clientId, clientSecret, issuerUri, null, null, null);
    }

    /** 값은 실행 시점에 만든다. 자격정보 모양 리터럴을 시험 fixture에도 두지 않는다. */
    private static AuthProperties.Provider credentials() {
        return new AuthProperties.Provider(
                "client-" + UUID.randomUUID(), UUID.randomUUID().toString(), null, null, null, null);
    }
}
