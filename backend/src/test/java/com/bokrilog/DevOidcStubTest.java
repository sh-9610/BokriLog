package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.DevOidcStub.TokenPlan;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * 스텁 제공자가 실제로 검사하는 것들.
 *
 * <p>이 시험이 없으면 {@code DevOidcStub}은 검사하는 척만 하는 장치가 될 수 있다. 그 상태에서
 * 우리 쪽의 PKCE가 꺼지거나 authorization code 재사용이 열려도 인증 통합 시험은 전부 통과한다 —
 * 제공자 자리에 선 것이 아무것도 거절하지 않으니 흐름은 언제나 성공한다. 그래서 스텁의 거절
 * 경로를 직접 단정한다.
 *
 * <p>Spring 컨텍스트도 Docker도 쓰지 않는다. {@code DevOidcStub}에는 HTTP도 Spring도 없고 확인하려는
 * 것은 순수한 판정이므로, 이 시험은 컨테이너 없이 돈다({@code SchemaValidationConfigTest}·
 * {@code DevOidcStubProfileTest}와 같은 자리).
 *
 * <p>스텁 인스턴스는 이 클래스에 하나다. RSA 키 두 개를 만드는 비용을 시험마다 다시 내지 않기
 * 위해서이고, 발급되는 code가 {@link java.util.UUID}로 유일하므로 시험끼리 같은 code를 만나지
 * 않는다.
 */
class DevOidcStubTest {

    private static final String ISSUER = "http://127.0.0.1:0/api/dev-oidc";

    private static final String REDIRECT_URI = "https://bokrilog.test/api/auth/callback/google";

    /** 인가 요청이 실제로 만드는 PKCE verifier의 자리. 값 자체는 아무 문자열이어도 된다. */
    private static final String VERIFIER = "dev-oidc-verifier-0123456789abcdefghijklmnopqrstuv";

    private static final DevOidcStub STUB = new DevOidcStub(ISSUER);

    // ----------------------------------------------------------------------
    // 인가
    // ----------------------------------------------------------------------

    @Test
    void PKCE_S256이_아닌_인가_요청에는_code를_발급하지_않는다() {
        Map<String, String> withoutPkce = authorizationRequest();
        withoutPkce.remove("code_challenge");
        withoutPkce.remove("code_challenge_method");

        // 브라우저 왕복은 여기서 멈춘다. 우리 쪽에서 PKCE가 꺼지면 동의 화면이 열리지 않는다.
        assertThat(STUB.consentRedirect(withoutPkce)).isNull();

        Map<String, String> plainMethod = authorizationRequest();
        plainMethod.put("code_challenge_method", "plain");
        assertThat(STUB.consentRedirect(plainMethod)).isNull();
    }

    @Test
    void 동의_링크는_이_인가_요청의_challenge에_귀속된_code를_준다() {
        String redirect = STUB.consentRedirect(authorizationRequest());

        assertThat(redirect).isNotNull();
        Map<String, String> callback = DevOidcStub.parseForm(query(redirect));
        // 이 code는 위 인가 요청의 challenge와 한 쌍이므로 그 verifier로만 교환된다.
        assertThat(exchange(callback.get("code"), VERIFIER).status()).isEqualTo(200);
    }

    @Test
    void 귀속되지_않은_인가_요청으로는_code를_만들_수_없다() {
        Map<String, String> withoutChallenge = authorizationRequest();
        withoutChallenge.remove("code_challenge");

        // 시험이 귀속을 건너뛰는 경로를 열어 두면 PKCE 결합이 깨져도 아무도 보지 못한다.
        assertThatThrownBy(() -> STUB.issueCode(TokenPlan.valid("n"), withoutChallenge))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------------
    // 토큰 교환
    // ----------------------------------------------------------------------

    @Test
    void 맞는_verifier로는_ID_Token이_나온다() {
        String code = STUB.issueCode(TokenPlan.valid("nonce-hash"), authorizationRequest());

        DevOidcStub.Response response = exchange(code, VERIFIER);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"id_token\":");
    }

    @Test
    void verifier가_challenge와_맞지_않으면_거부한다() {
        String code = STUB.issueCode(TokenPlan.valid("nonce-hash"), authorizationRequest());

        DevOidcStub.Response response = exchange(code, VERIFIER + "-tampered");

        // 존재만 확인하면 통과하는 자리다. S256을 다시 계산해 비교하지 않으면 이 단정이 깨진다.
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("invalid_grant");
        assertThat(response.body()).doesNotContain("id_token");
    }

    @Test
    void verifier가_없으면_거부한다() {
        String code = STUB.issueCode(TokenPlan.valid("nonce-hash"), authorizationRequest());

        Map<String, String> form = tokenRequest(code, null);

        assertThat(STUB.token(form, basicAuth()).status()).isEqualTo(400);
    }

    @Test
    void 한_번_교환한_code는_다시_교환되지_않는다() {
        String code = STUB.issueCode(TokenPlan.valid("nonce-hash"), authorizationRequest());
        assertThat(exchange(code, VERIFIER).status()).isEqualTo(200);

        DevOidcStub.Response replay = exchange(code, VERIFIER);

        // 조회로 꺼내면 같은 code가 몇 번이든 토큰을 낸다. 그때 우리 쪽의 code 재사용 방어가
        // 사라져도 시험은 통과한다.
        assertThat(replay.status()).isEqualTo(400);
        assertThat(replay.body()).contains("invalid_grant");
        assertThat(replay.body()).doesNotContain("id_token");
    }

    @Test
    void 모르는_code는_거부한다() {
        assertThat(exchange("dev-oidc-code-does-not-exist", VERIFIER).status()).isEqualTo(400);
        assertThat(STUB.token(Map.of(), basicAuth()).status()).isEqualTo(400);
    }

    @Test
    void 인가_때와_다른_redirect_URI로는_교환하지_않는다() {
        String code = STUB.issueCode(TokenPlan.valid("nonce-hash"), authorizationRequest());

        Map<String, String> form = tokenRequest(code, VERIFIER);
        form.put("redirect_uri", "https://evil.example/api/auth/callback/google");

        assertThat(STUB.token(form, basicAuth()).status()).isEqualTo(400);
    }

    @Test
    void client_자격정보가_없거나_틀리면_거부한다() {
        String code = STUB.issueCode(TokenPlan.valid("nonce-hash"), authorizationRequest());
        Map<String, String> form = tokenRequest(code, VERIFIER);

        assertThat(STUB.token(form, null).status()).isEqualTo(401);

        String wrongSecret =
                "Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        (encode(STUB.clientId()) + ":" + encode("not-the-secret"))
                                                .getBytes(StandardCharsets.UTF_8));
        // 앞선 호출이 code를 소비했으므로 새 code로 다시 본다.
        String second = STUB.issueCode(TokenPlan.valid("nonce-hash"), authorizationRequest());
        assertThat(STUB.token(tokenRequest(second, VERIFIER), wrongSecret).status())
                .isEqualTo(401);
    }

    @Test
    void 엔드포인트_장애_계획은_토큰_대신_5xx다() {
        String code =
                STUB.issueCode(
                        TokenPlan.valid("nonce-hash").tokenEndpointFailure(), authorizationRequest());

        DevOidcStub.Response response = exchange(code, VERIFIER);

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body()).doesNotContain("id_token");
    }

    // ----------------------------------------------------------------------
    // 요청 조립
    // ----------------------------------------------------------------------

    /** 서버가 만드는 인가 요청과 같은 모양. PKCE challenge는 위 verifier의 S256이다. */
    private static Map<String, String> authorizationRequest() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", STUB.clientId());
        query.put("scope", "openid");
        query.put("state", "state-value");
        query.put("nonce", "nonce-hash");
        query.put("redirect_uri", REDIRECT_URI);
        query.put("code_challenge", DevOidcStub.s256(VERIFIER));
        query.put("code_challenge_method", "S256");
        return query;
    }

    private static Map<String, String> tokenRequest(String code, @Nullable String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", REDIRECT_URI);
        if (verifier != null) {
            form.put("code_verifier", verifier);
        }
        return form;
    }

    private static DevOidcStub.Response exchange(String code, String verifier) {
        return STUB.token(tokenRequest(code, verifier), basicAuth());
    }

    /** {@code client_secret_basic}. Spring Security가 두 값을 URL 인코딩해 싣는 것과 같은 모양이다. */
    private static String basicAuth() {
        String credentials = encode(STUB.clientId()) + ":" + encode(STUB.clientSecret());
        return "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** URL의 쿼리 문자열. 콜백 목적지에서 {@code code}를 꺼내는 데 쓴다. */
    private static String query(String url) {
        int start = url.indexOf('?');
        return start < 0 ? "" : url.substring(start + 1);
    }
}
