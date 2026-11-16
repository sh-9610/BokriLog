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
 * 콜백 검증.
 *
 * <p>정상 흐름 하나를 먼저 고정한 뒤, 검증 항목을 하나씩 어긋나게 해서 "그 하나 때문에 거부됐다"를
 * 단정한다. 정상 흐름이 없으면 모든 실패 시험이 다른 이유로도 통과할 수 있다.
 *
 * <p>모든 실패에서 계정·매핑·세션 행이 0개다. 상태 코드만 보면 "그 요청이 실패했다"까지만 알 수
 * 있고, 아무것도 만들지 않았다는 것은 저장소를 세야 알 수 있다.
 *
 * <p>실패 표지는 닫힌 값 하나({@code failed})다. 어느 검증이 어긋났는지 응답에도 로그에도 남기지
 * 않는다. 원인을 표지로 나누는 순간 그 값이 계정의 존재나 상태를 알려 주는 신호가 된다.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthTokenValidationTest extends AuthFlowTestBase {

    @Test
    void 인증_시작은_PKCE와_nonce가_붙은_authorization_request로_리다이렉트한다() throws Exception {
        Authorization authorization = startAuthorization();

        assertThat(authorization.status()).isEqualTo(302);
        assertThat(authorization.location()).startsWith(OidcStubConfiguration.issuer() + "/authorize");

        // AD-10이 요구하는 항목들. 하나라도 빠지면 흐름 전체의 성질이 달라진다.
        assertThat(authorization.parameters())
                .containsEntry("response_type", "code")
                .containsEntry("client_id", OidcStubConfiguration.CLIENT_ID)
                .containsEntry("scope", "openid")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry(
                        "redirect_uri",
                        OidcStubConfiguration.PUBLIC_BASE_URL + callbackPath());
        assertThat(authorization.parameters().get("code_challenge")).isNotBlank();
        assertThat(authorization.state()).isNotBlank();
        assertThat(authorization.nonce()).isNotBlank();

        // 기본 동의 범위는 openid뿐이다. profile·email을 함께 요청하지 않는다.
        assertThat(authorization.parameters().get("scope")).isEqualTo("openid");
    }

    @Test
    void 검증을_통과한_콜백만_계정과_세션을_만든다() throws Exception {
        Authorization authorization = startAuthorization();
        String code = issueCode(authorization, TokenPlan.valid(authorization.nonce()));

        HttpResponse<String> response = callback(authorization, code);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);
    }

    @Test
    void JWKS에_없는_키로_서명한_ID_Token은_거부한다() throws Exception {
        assertRejected(plan -> plan.foreignKey());
    }

    @Test
    void 허용되지_않은_알고리즘으로_서명한_ID_Token은_거부한다() throws Exception {
        // 기본 허용 알고리즘은 RS256 하나다. HS256을 받아 주면 client secret을 아는 쪽이
        // ID Token을 스스로 만들 수 있게 된다(알고리즘 혼동).
        assertRejected(plan -> plan.macAlgorithm());
    }

    @Test
    void iss가_다른_ID_Token은_거부한다() throws Exception {
        assertRejected(plan -> plan.issuer("https://accounts.example.invalid"));
    }

    @Test
    void aud가_이_client가_아닌_ID_Token은_거부한다() throws Exception {
        assertRejected(plan -> plan.audience(List.of("other-client")));
    }

    @Test
    void 복수_aud에_azp가_없는_ID_Token은_거부한다() throws Exception {
        assertRejected(
                plan -> plan.audience(List.of(OidcStubConfiguration.CLIENT_ID, "other-client")));
    }

    @Test
    void azp가_이_client가_아닌_ID_Token은_거부한다() throws Exception {
        assertRejected(
                plan ->
                        plan.audience(List.of(OidcStubConfiguration.CLIENT_ID, "other-client"))
                                .authorizedParty("other-client"));
    }

    @Test
    void 만료된_ID_Token은_거부한다() throws Exception {
        assertRejected(plan -> plan.expired());
    }

    @Test
    void nonce가_다른_ID_Token은_거부한다() throws Exception {
        assertRejected(plan -> plan.nonce("mismatched-nonce"));
    }

    @Test
    void nonce가_없는_ID_Token은_거부한다() throws Exception {
        assertRejected(plan -> plan.nonce(null));
    }

    @Test
    void ID_Token이_없는_토큰_응답은_거부한다() throws Exception {
        assertRejected(plan -> plan.omitIdToken());
    }

    @Test
    void 토큰_엔드포인트_장애는_어떤_상태도_남기지_않는다() throws Exception {
        assertRejected(plan -> plan.tokenEndpointFailure());
    }

    @Test
    void state가_어긋나면_유효한_code로도_로그인되지_않는다() throws Exception {
        Authorization authorization = startAuthorization();
        String code = issueCode(authorization, TokenPlan.valid(authorization.nonce()));

        // 이 code는 정상 토큰을 낸다. 교환이 시작됐다면 로그인이 성공했을 것이다.
        HttpResponse<String> response =
                callback(
                        authorization,
                        authorization.sessionCookie(),
                        code,
                        UUID.randomUUID().toString());

        assertConvergedWithoutAccount(response);
        /*
         * 이 실패는 세션의 인가 요청에 닿지 못했으므로 그 세션을 지우지 않는다(deferred 190). 세션 행은 남지만 주체를
         * 결정하지 않는다 — 지우면 남이 연 위조 콜백 하나로 진행 중인 로그인이 취소된다.
         */
        assertThat(sessionCount()).isEqualTo(1);
        assertThat(sessionState(authorization.sessionCookie()).body())
                .contains("\"authenticated\":false");
    }

    @Test
    void 로그인_전_세션의_위조_콜백은_진행_중인_로그인을_지우지_않는다() throws Exception {
        Authorization authorization = startAuthorization();
        assertThat(sessionCount()).isEqualTo(1);

        /*
         * 외부 사이트가 `top.location`으로 이 세션의 인가 요청에 닿지 못하는 콜백을 연다(deferred 190). 응답은 지금처럼
         * 진입 화면과 실패 표지이고, 세션 행과 그 안의 인가 요청은 그대로다.
         */
        for (HttpResponse<String> forged :
                forgedCallbacks(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, authorization.sessionCookie())) {
            assertThat(forged.statusCode()).isEqualTo(302);
            assertThat(locationOf(forged)).endsWith(failureLocation(AuthFlowHandlers.FAILURE_MARKER_FAILED));
            // 쿠키 만료 지시도 없다. 행만 남기고 브라우저의 쿠키를 지우면 결과는 같다 — 흐름이 끊긴다.
            assertThat(forged.headers().allValues("Set-Cookie")).noneMatch(value -> value.contains("Max-Age=0"));
        }
        assertThat(sessionCount()).isEqualTo(1);
        assertThat(accountCount()).isZero();
        assertThat(socialLinkCount()).isZero();
        assertThat(sessionState(authorization.sessionCookie()).body())
                .contains("\"authenticated\":false");

        // 이어지는 정상 콜백이 로그인을 마친다.
        String code = issueCode(authorization, TokenPlan.valid(authorization.nonce()));
        HttpResponse<String> response = callback(authorization, code);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(sessionCount()).isEqualTo(1);
        String established = sessionCookieOf(response).orElse(authorization.sessionCookie());
        assertThat(sessionState(established).body()).contains("\"authenticated\":true");
    }

    @Test
    void 같은_브라우저에서_두_번_시작한_로그인은_앞_흐름의_콜백이_뒤_흐름을_지우지_않는다() throws Exception {
        Authorization first = startAuthorization();
        // 같은 세션에서 다시 시작한다. 세션의 인가 요청은 하나라 뒤 흐름이 앞 흐름의 것을 덮는다.
        Authorization second = startAuthorization(null, first.sessionCookie());
        assertThat(second.state()).isNotEqualTo(first.state());
        assertThat(sessionCount()).isEqualTo(1);

        // 앞 탭의 콜백이 늦게 도착한다. 그 state는 이 세션의 인가 요청과 맞지 않으므로 실패다.
        String firstCode = issueCode(first, TokenPlan.valid(first.nonce()));
        HttpResponse<String> stale = callback(first, firstCode);

        assertConvergedWithoutAccount(stale);
        // 뒤 흐름의 세션과 인가 요청은 그대로다(deferred 190).
        assertThat(sessionCount()).isEqualTo(1);

        String secondCode = issueCode(second, TokenPlan.valid(second.nonce()));
        HttpResponse<String> response = callback(second, secondCode);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        String established = sessionCookieOf(response).orElse(second.sessionCookie());
        assertThat(sessionState(established).body()).contains("\"authenticated\":true");
    }

    @Test
    void 로그인_전_세션도_인가_요청을_소비한_실패는_그_세션을_지운다() throws Exception {
        // 유효한 state의 취소·제공자 오류는 이 흐름의 인가 요청을 소비한 실패다. 지금처럼 세션을 남기지 않는다.
        for (String error : List.of("access_denied", "server_error")) {
            Authorization authorization = startAuthorization();

            HttpResponse<String> response = callbackWithError(authorization, error);

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(locationOf(response))
                    .endsWith(
                            failureLocation(
                                    "access_denied".equals(error)
                                            ? AuthFlowHandlers.FAILURE_MARKER_CANCELLED
                                            : AuthFlowHandlers.FAILURE_MARKER_FAILED));
            assertThat(sessionCount()).isZero();
            // 위 위조 시험이 "만료 지시 없음"을 단정하는 근거다 — 세션을 지우는 실패는 쿠키 만료도 함께 싣는다.
            assertThat(response.headers().allValues("Set-Cookie")).anyMatch(value -> value.contains("Max-Age=0"));
        }
        assertThat(accountCount()).isZero();
    }

    @Test
    void 로그인_전_세션의_invalid_request는_state가_있을_때만_세션을_지운다() throws Exception {
        /*
         * 같은 code `invalid_request`가 두 모양으로 온다(`missedAuthorizationRequest`). `state` 없이 오면 요청이 인가 응답
         * 모양이 아니어서 필터가 스스로 던진 것이고 인가 요청에 닿지 못했다 — 세션을 남긴다. 유효한 `state`와 함께 오면
         * 제공자가 되돌린 오류이고 필터가 인가 요청을 소비했다 — 지금처럼 그 흐름의 세션을 지운다.
         */
        Authorization withoutState = startAuthorization();
        HttpResponse<String> filterThrown =
                get(callbackPath() + "?error=invalid_request", withoutState.sessionCookie());

        assertThat(filterThrown.statusCode()).isEqualTo(302);
        assertThat(locationOf(filterThrown)).endsWith(failureLocation(AuthFlowHandlers.FAILURE_MARKER_FAILED));
        assertThat(sessionCount()).isEqualTo(1);
        // 남은 흐름은 이어지는 정상 콜백으로 끝난다.
        String code = issueCode(withoutState, TokenPlan.valid(withoutState.nonce()));
        assertThat(locationOf(callback(withoutState, code))).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        assertThat(accountCount()).isEqualTo(1);

        Authorization withState = startAuthorization();
        assertThat(sessionCount()).isEqualTo(2);
        HttpResponse<String> providerError = callbackWithError(withState, "invalid_request");

        assertThat(providerError.statusCode()).isEqualTo(302);
        assertThat(locationOf(providerError)).endsWith(failureLocation(AuthFlowHandlers.FAILURE_MARKER_FAILED));
        // 로그인한 앞 세션 하나만 남는다. 제공자 오류를 받은 흐름의 세션은 지워졌다.
        assertThat(sessionCount()).isEqualTo(1);
        assertThat(sessionState(withState.sessionCookie()).body()).contains("\"authenticated\":false");
        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void 세션에_귀속되지_않은_state는_거부한다() throws Exception {
        Authorization authorization = startAuthorization();
        String code = issueCode(authorization, TokenPlan.valid(authorization.nonce()));

        // state는 맞지만 그 state를 발급한 세션이 아니다. 쿠키 없이 오는 콜백이 그 모양이다.
        HttpResponse<String> response = callback(authorization, null, code, authorization.state());

        assertConvergedWithoutAccount(response);
        // 인증되지 않은 흐름의 세션이 남아 있어도 그 세션은 주체를 결정하지 않는다.
        assertThat(sessionState(authorization.sessionCookie()).body())
                .contains("\"authenticated\":false");
    }

    @Test
    void 다른_흐름의_state로_바꿔치기한_콜백은_거부한다() throws Exception {
        Authorization first = startAuthorization();
        Authorization second = startAuthorization();
        String code = issueCode(first, TokenPlan.valid(first.nonce()));

        // 첫 흐름의 세션에 두 번째 흐름의 state를 들고 온다. 두 값이 한 쌍이 아니면 거부다.
        HttpResponse<String> response = callback(first, first.sessionCookie(), code, second.state());

        assertConvergedWithoutAccount(response);
        // 바꿔치기의 대상이 된 두 번째 흐름도 인증되지 않는다.
        assertThat(sessionState(second.sessionCookie()).body()).contains("\"authenticated\":false");
    }

    @Test
    void 한_번_쓴_state는_다시_쓸_수_없다() throws Exception {
        Authorization authorization = startAuthorization();
        TokenPlan plan = TokenPlan.valid(authorization.nonce());
        String code = issueCode(authorization, plan);
        HttpResponse<String> first = callback(authorization, code);
        assertThat(first.statusCode()).isEqualTo(302);
        String establishedCookie = sessionCookieOf(first).orElse(authorization.sessionCookie());

        // 같은 state를 다시 들고 온다. 서버가 세션에서 이미 지웠으므로 두 번째는 성립하지 않는다.
        String replayCode = issueCode(authorization, TokenPlan.valid(authorization.nonce()));
        HttpResponse<String> replay =
                callback(authorization, establishedCookie, replayCode, authorization.state());

        assertThat(replay.statusCode()).isEqualTo(302);
        /*
         * 인증 화면이 아니라 지금 목적지로 되돌린다(Story 1.8).
         *
         * 이 요청은 인증된 세션에 도착한 무장 없는 콜백이므로 인증 흐름 경계가 검증 앞에서
         * 접는다. 그전에는 같은 요청이 `state` 검사를 지나 실패 핸들러로 갔고 표지와 함께 인증
         * 화면으로 수렴했다 — 상태는 어느 쪽에서도 바뀌지 않지만, 이미 로그인한 사용자에게
         * "로그인을 끝내지 못했습니다"를 보여 줄 이유가 없다. 경계를 콜백까지 넓힌 이유는 따로
         * 있다: 재인증이 인증된 세션에 진행 중인 authorization request를 만들 수 있게 했고,
         * 그것을 접지 않으면 흐름이 만료된 뒤 도착한 콜백이 성공 핸들러의 로그인 분기로 들어가
         * 같은 세션 행에 다른 계정을 심는다.
         */
        assertThat(locationOf(replay)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        // 두 번째 시도가 계정이나 매핑을 하나 더 만들지 않았다.
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        /*
         * 이미 개설된 세션은 그대로다.
         *
         * 재사용 시도는 실패해야 하지만 그 실패가 앞서 성공한 로그인을 지워서는 안 된다. 실패
         * 경로가 요청이 들고 온 쿠키의 세션을 조건 없이 지우면 여기가 0이 되고, 그 동작이 곧
         * 크로스 사이트 강제 로그아웃이다 — 아래 시험이 그 모양을 직접 본다.
         */
        assertThat(sessionCount()).isEqualTo(1);
        assertThat(sessionState(establishedCookie).body()).contains("\"authenticated\":true");
    }

    @Test
    void 이미_교환된_code는_새_흐름에서도_다시_쓰이지_않는다() throws Exception {
        Authorization first = startAuthorization();
        String code = issueCode(first, TokenPlan.valid(first.nonce()));
        assertThat(callback(first, code).statusCode()).isEqualTo(302);
        assertThat(accountCount()).isEqualTo(1);

        /*
         * 새 흐름의 state는 그 흐름의 것이라 맞지만 code는 이미 교환된 값이다. 제공자가
         * authorization code를 일회성으로 다루면 여기서 거절되고, 다루지 않으면 같은 code가
         * 두 번째 로그인을 만든다.
         */
        Authorization second = startAuthorization();
        HttpResponse<String> replay = callback(second, code);

        assertThat(replay.statusCode()).isEqualTo(302);
        assertThat(locationOf(replay)).endsWith(failureLocation(AuthFlowHandlers.FAILURE_MARKER_FAILED));
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 개설된_세션은_실패하는_콜백으로_지워지지_않는다() throws Exception {
        HttpResponse<String> login = signIn("cross-site-victim-" + UUID.randomUUID());
        assertThat(login.statusCode()).isEqualTo(302);
        String established = sessionCookieOf(login).orElseThrow();
        assertThat(sessionCount()).isEqualTo(1);

        /*
         * 외부 사이트가 `top.location`으로 콜백 URL을 여는 모양이다.
         *
         * 콜백은 미인증 최상위 GET이고 세션 쿠키는 `SameSite=Lax`라 이 내비게이션에 실린다.
         * 그래서 공격자는 피해자의 브라우저가 자기 세션 쿠키를 들고 이 실패 경로에 도착하게
         * 만들 수 있다. 이 요청으로 세션이 사라지면 그것이 크로스 사이트 강제 로그아웃이고
         * "실패는 어떤 상태도 바꾸지 않는다"의 반대다.
         */
        HttpResponse<String> forced =
                callback(
                        AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                        established,
                        "forced-code",
                        "forced-state");

        assertThat(forced.statusCode()).isEqualTo(302);
        /*
         * Story 1.8부터 이 요청은 검증에 닿지도 않는다. 인증 흐름 경계가 "인증된 세션의 무장
         * 없는 콜백"으로 보고 지금 목적지로 되돌리며, 그 편이 이 공격에 대한 답으로도 낫다 —
         * 피해자는 자기 화면에 그대로 있고 공격자는 아무것도 관측하지 못한다. 실패 표지와 함께
         * 인증 화면을 보여 주던 이전 동작은 피해자에게 "로그인을 끝내지 못했습니다"라는 거짓
         * 설명을 주는 것이기도 했다.
         */
        assertThat(locationOf(forced)).endsWith(AuthFlowHandlers.ONBOARDING_PATH);
        // 세션 행도, 그 세션이 결정하는 주체도 그대로다.
        assertThat(sessionCount()).isEqualTo(1);
        assertThat(sessionState(established).body()).contains("\"authenticated\":true");
        // 실패가 계정이나 매핑을 건드리지도 않았다.
        assertThat(accountCount()).isEqualTo(1);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 실패는_원인을_응답에도_로그에도_남기지_않는다(CapturedOutput output) throws Exception {
        Authorization authorization = startAuthorization();
        TokenPlan plan = TokenPlan.valid(authorization.nonce()).issuer("https://accounts.example.invalid");
        String code = issueCode(authorization, plan);

        HttpResponse<String> response = callback(authorization, code);

        String location = locationOf(response);
        // 표지는 닫힌 값 하나뿐이다. 검증 실패의 종류가 URL에 실리지 않는다.
        assertThat(location).endsWith(failureLocation(AuthFlowHandlers.FAILURE_MARKER_FAILED));
        assertThat(location).doesNotContain(code);
        assertThat(location).doesNotContain(authorization.state());
        assertThat(location).doesNotContain(plan.subject());
        assertThat(response.body()).doesNotContain(code);
        assertThat(response.body()).doesNotContain(plan.subject());

        // 로그에도 남지 않는다. 로그는 응답보다 오래 남고 더 많은 곳으로 복제된다.
        assertThat(output.getAll()).doesNotContain(code);
        assertThat(output.getAll()).doesNotContain(plan.subject());
        assertThat(output.getAll()).doesNotContain(authorization.state());
        assertThat(output.getAll()).doesNotContain("invalid_id_token");
        assertThat(output.getAll()).doesNotContain(OidcStubConfiguration.CLIENT_SECRET);
        assertThat(output.getAll()).doesNotContain("at org.springframework.security");
    }

    /** 계획 하나를 어긋나게 한 콜백이 거부되고 아무 상태도 남지 않는지 본다. */
    private void assertRejected(java.util.function.UnaryOperator<TokenPlan> mutation) throws Exception {
        Authorization authorization = startAuthorization();
        TokenPlan plan = mutation.apply(TokenPlan.valid(authorization.nonce()));
        String code = issueCode(authorization, plan);

        HttpResponse<String> response = callback(authorization, code);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response)).endsWith(failureLocation(AuthFlowHandlers.FAILURE_MARKER_FAILED));
        assertThat(accountCount()).isZero();
        assertThat(socialLinkCount()).isZero();
        assertThat(sessionCount()).isZero();
    }

    /**
     * 계정·매핑이 늘지 않고 인증 화면으로 수렴했는지 본다.
     *
     * <p>세션 행 수를 세지 않는다. 인증 시작이 authorization request를 세션에 넣으므로 흐름을
     * 두 번 시작한 시험에는 그 흐름들의 세션 행이 남아 있고, 실패 핸들러는 콜백이 들고 온
     * 쿠키의 세션만 무효화한다. 여기서 확인해야 하는 것은 "행이 없다"가 아니라 "그 세션이
     * 주체를 결정하지 않는다"이므로 호출자가 세션 상태로 그것을 단정한다.
     */
    private void assertConvergedWithoutAccount(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(locationOf(response)).endsWith(failureLocation(AuthFlowHandlers.FAILURE_MARKER_FAILED));
        assertThat(accountCount()).isZero();
        assertThat(socialLinkCount()).isZero();
    }

    static String failureLocation(String marker) {
        return AuthFlowHandlers.ENTRY_PATH
                + "?"
                + AuthFlowHandlers.FAILURE_MARKER_PARAMETER
                + "="
                + marker;
    }
}
