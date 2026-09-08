package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.DevOidcStub.TokenPlan;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 복귀 목적지와 실패 표지.
 *
 * <p>목적지는 서버가 가진 닫힌 허용 목록뿐이다. 클라이언트가 보낸 값은 그 목록에 있는지만 확인하고,
 * 없으면 버린 뒤 기본 목적지로 보낸다. 외부 주소가 목록에 오를 수 없으므로 open redirect가 성립할
 * 자리가 없다.
 *
 * <p>실패 표지도 닫힌 집합이다. 값 두 개뿐이며, 어느 것도 계정의 존재·상태·검증 실패의 종류를
 * 알려 주지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthRedirectDestinationTest extends AuthFlowTestBase {

    @Test
    void 신규_사용자는_요청_목적지가_있어도_온보딩으로_간다() throws Exception {
        Authorization authorization =
                startAuthorization(AuthFlowHandlers.DASHBOARD_PATH, null);
        String code = issueCode(authorization, TokenPlan.valid(authorization.nonce()));

        HttpResponse<String> response = callback(authorization, code);

        // 온보딩을 건너뛰게 하지 않는다. 신규 사용자에게는 그 화면이 첫 목적지다.
        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
    }

    @Test
    void 기존_사용자는_허용_목록에_있는_원래_목적지로_돌아간다() throws Exception {
        String subject = "destination-user-" + UUID.randomUUID();
        signIn(subject);

        HttpResponse<String> response =
                signInWithDestination(subject, AuthFlowHandlers.DASHBOARD_PATH);

        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.DASHBOARD_PATH);
    }

    @Test
    void 허용_목록의_다른_경로도_그대로_보존한다() throws Exception {
        String subject = "onboarding-again-" + UUID.randomUUID();
        signIn(subject);

        HttpResponse<String> response =
                signInWithDestination(subject, AuthFlowHandlers.ONBOARDING_PATH);

        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
    }

    @Test
    void 외부_주소_목적지는_버리고_기본_대시보드로_보낸다() throws Exception {
        String subject = "external-destination-" + UUID.randomUUID();
        signIn(subject);

        HttpResponse<String> response = signInWithDestination(subject, "https://evil.example/x");

        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.DASHBOARD_PATH);
        assertThat(locationOf(response)).doesNotContain("evil.example");
    }

    @Test
    void 알_수_없는_내부_경로_목적지도_버린다() throws Exception {
        String subject = "unknown-destination-" + UUID.randomUUID();
        signIn(subject);

        HttpResponse<String> response = signInWithDestination(subject, "/unknown");

        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.DASHBOARD_PATH);
        assertThat(locationOf(response)).doesNotContain("/unknown");
    }

    @Test
    void 목적지를_주지_않은_기존_사용자는_기본_대시보드로_간다() throws Exception {
        String subject = "default-destination-" + UUID.randomUUID();
        signIn(subject);

        HttpResponse<String> response = signIn(subject);

        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.DASHBOARD_PATH);
    }

    @Test
    void 사용자_취소는_재시도_경로를_가진_표지로_수렴한다() throws Exception {
        Authorization authorization = startAuthorization();

        HttpResponse<String> response =
                callbackWithError(authorization, "access_denied");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response))
                .endsWith(
                        AuthTokenValidationTest.failureLocation(AuthFlowHandlers.FAILURE_MARKER_CANCELLED));
        // 상태 변경 0건. 자동 재시도도 다른 제공자 자동 선택도 하지 않는다.
        assertThat(accountCount()).isZero();
        assertThat(socialLinkCount()).isZero();
        assertThat(sessionCount()).isZero();
    }

    @Test
    void 실패_표지는_닫힌_두_값뿐이다() throws Exception {
        Authorization cancelled = startAuthorization();
        HttpResponse<String> cancelledResponse =
                callbackWithError(cancelled, "access_denied");

        Authorization providerError = startAuthorization();
        HttpResponse<String> providerErrorResponse =
                callbackWithError(providerError, "server_error");

        Authorization tokenFailure = startAuthorization();
        String code =
                issueCode(
                        tokenFailure, TokenPlan.valid(tokenFailure.nonce()).tokenEndpointFailure());
        HttpResponse<String> tokenFailureResponse =
                callback(tokenFailure, code);

        List<String> markers =
                List.of(
                        markerOf(cancelledResponse),
                        markerOf(providerErrorResponse),
                        markerOf(tokenFailureResponse));

        assertThat(markers)
                .containsExactly(
                        AuthFlowHandlers.FAILURE_MARKER_CANCELLED,
                        AuthFlowHandlers.FAILURE_MARKER_FAILED,
                        AuthFlowHandlers.FAILURE_MARKER_FAILED);
        // 제공자 오류 코드가 그대로 새어 나가지 않는다.
        assertThat(locationOf(providerErrorResponse)).doesNotContain("server_error");
    }

    @Test
    void 성공한_흐름도_code와_subject와_user_id를_남기지_않는다(CapturedOutput output) throws Exception {
        String subject = "redaction-user-" + UUID.randomUUID();
        Authorization authorization = startAuthorization();
        String code =
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(subject));

        HttpResponse<String> response = callback(authorization, code);
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String cookie = sessionCookieOf(response).orElseThrow();
        HttpResponse<String> sessionState = sessionState(cookie);

        for (String forbidden :
                List.of(code, subject, userId.toString(), OidcStubConfiguration.CLIENT_SECRET)) {
            assertThat(locationOf(response)).doesNotContain(forbidden);
            assertThat(response.body()).doesNotContain(forbidden);
            assertThat(sessionState.body()).doesNotContain(forbidden);
            assertThat(output.getAll()).doesNotContain(forbidden);
        }
        // PKCE verifier는 서버가 세션에만 들고 있다. 어떤 응답에도 이름조차 나오지 않는다.
        assertThat(locationOf(response)).doesNotContain("code_verifier");
        assertThat(response.body()).doesNotContain("code_verifier");
        // 쿠키 값에도 사용자 식별자가 실리지 않는다.
        assertThat(cookie).doesNotContain(userId.toString());
    }

    private HttpResponse<String> signInWithDestination(String subject, String next) throws Exception {
        Authorization authorization = startAuthorization(next, null);
        String code =
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(subject));
        return callback(authorization, code);
    }

    private static String markerOf(HttpResponse<String> response) {
        return queryParameters(locationOf(response))
                .getOrDefault(AuthFlowHandlers.FAILURE_MARKER_PARAMETER, "");
    }
}
