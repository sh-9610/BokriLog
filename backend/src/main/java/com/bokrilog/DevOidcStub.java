package com.bokrilog;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * 결정적 local OIDC 제공자.
 *
 * <p>자기 RSA 키로 JWKS를 제공하고 ID Token을 서명한다. 외부 네트워크도, 커밋된 자격정보도 필요
 * 없다. client id·secret은 실행 시점에 {@link UUID}로 만들어 리터럴이 저장소에 남지 않는다. 실제
 * Google 왕복은 Epic 1의 <b>실연동 미검증</b> 게이트가 소유하며 이 스텁이 대신하지 않는다.
 *
 * <p>시험 소스가 아니라 {@code main}에 있다. Authorization Code 흐름의 인가 요청은 <b>브라우저</b>가
 * 보내고 code 교환과 JWKS 조회는 <b>백엔드</b>가 보내므로, 성공 경로를 브라우저 층에서 검증하려면
 * 한 스텁이 양쪽에서 도달 가능해야 한다. Playwright의 {@code page.route()}로는 백엔드가 나가는
 * 요청을 대신할 수 없다.
 *
 * <p>이 클래스에는 HTTP도 Spring도 없다. 두 개의 표면이 같은 논리를 쓴다 — 개발·CI 스택에서는
 * {@code DevOidcStubConfig}가 {@code /api/dev-oidc/**}에 올리고, 백엔드 시험에서는
 * {@code OidcStubConfiguration}이 루프백 HTTP 서버에 올린다. 스텁을 두 번 만들지 않기 위해 논리는
 * 여기 한 곳에만 둔다.
 *
 * <p>기본 프로필에서는 이 스텁의 빈이 하나도 만들어지지 않고 {@code /api/dev-oidc/**}가 노출되지
 * 않는다. 그 사실과 "{@code dev-oidc}가 실제 제공자 등록과 함께 활성이면 기동 거부"를
 * {@code DevOidcStubProfileTest}가 단정한다.
 *
 * <p>발급할 토큰의 모양은 호출자가 정한다. {@link #issueCode(TokenPlan, Map)}가 authorization code
 * 하나를 만들고 그 code로 오는 토큰 교환에 해당 계획을 적용한다. 실패 주입(서명 키·알고리즘·
 * {@code iss}·{@code aud}·{@code azp}·{@code exp}·{@code nonce}·엔드포인트 장애)이 모두 이 한 자리로
 * 모인다. 브라우저 왕복({@link #consentRedirect(Map)})은 언제나 정상 계획만 발급한다.
 *
 * <p>제공자 자리에 서는 검사는 실제로 한다. 발급한 code는 일회성이고, 토큰 교환은 PKCE
 * {@code code_verifier}의 S256을 인가 시점의 {@code code_challenge}와 재계산해 비교하며
 * {@code redirect_uri}와 client 자격정보도 확인한다. 스텁이 이것들을 흘려보내면 우리 쪽에서 PKCE가
 * 꺼지거나 code 재사용이 열려도 모든 시험이 통과한다 — 그때 이 스텁은 계약을 확인하는 장치가
 * 아니라 계약이 깨진 것을 숨기는 장치가 된다.
 */
final class DevOidcStub {

    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

    /**
     * 브라우저 왕복이 스텁의 {@code subject}를 고정하는 인가 요청 파라미터.
     *
     * <p>이름에 {@code dev_}가 붙는다. 실제 OIDC 파라미터와 섞이지 않아야 하고, 이 값을 보내는
     * 코드가 개발·CI 층의 것임이 호출 자리에서 읽혀야 한다. 값이 없으면 {@code subject}는 요청마다
     * 새로 만들어지며 그것이 기본이다(아래 {@link #consentRedirect}).
     */
    static final String SUBJECT_OVERRIDE_PARAMETER = "dev_subject";

    private final String issuer;

    private final String clientId;

    private final String clientSecret;

    private final RSAKey signingKey;

    private final RSAKey foreignKey;

    private final byte[] macSecret;

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    /**
     * 마지막 인가 요청이 실어 온 재인증 파라미터.
     *
     * <p>기록만 한다. 값에 따라 행동을 바꾸지 않는 것이 요점이다 — 이 스텁의 존재 이유는 정상
     * 흐름의 결정성이고, {@code prompt}나 {@code max_age}에 반응하기 시작하면 그 결정성이
     * 사라진다.
     *
     * <p>기록하는 이유는 시험이 <b>우리가 실제로 보냈는가</b>를 볼 자리가 필요하기 때문이다.
     * 인가 요청의 {@code Location}만 보면 서버가 만든 URL을 확인하는 것이고, 여기 남는 값은
     * 제공자 자리에 실제로 도착한 것이다. 세 이름만 담는다 — {@code state}·{@code nonce}·
     * {@code code_challenge}는 흐름의 비밀이라 기록할 값이 아니다.
     *
     * <p><b>인스턴스당 "마지막 요청" 하나다.</b> 같은 제공자로 인가 요청이 동시에 둘 들어오면
     * 나중에 도착한 쪽이 앞의 값을 덮으므로 이 맵을 읽는 단정은 순서에 의존한다. 그래서
     * {@link #grants}와 달리 code에 귀속되지 않으며, 시험은 인가 요청 하나를 보낸 직후에 읽어야
     * 한다. 흐름을 여럿 띄운 뒤 몰아서 읽으면 그 단정은 아무것도 고정하지 않는다.
     */
    private final Map<String, String> reauthenticationParameters = new ConcurrentHashMap<>();

    /** 재인증 파라미터로 기록하는 이름. 이 셋뿐이다. */
    private static final List<String> REAUTHENTICATION_PARAMETER_NAMES =
            List.of("max_age", "prompt", "claims");

    /**
     * @param issuer 이 스텁이 주장하는 {@code iss}. ID Token의 같은 claim이 이 값이며 제공자 등록의
     *     {@code issuer-uri}와 한 쌍이어야 검증을 통과한다.
     */
    DevOidcStub(String issuer) {
        this.issuer = issuer;
        // 값을 소스에 적으면 그것이 곧 자격정보 모양 리터럴이고, 가짜여도 gitleaks가 잡는다.
        // 탐지를 규칙 완화로 넘기지 않으려면 리터럴을 두지 않는 편이 유일한 답이다.
        this.clientId = "dev-oidc-client-" + UUID.randomUUID();
        this.clientSecret = UUID.randomUUID().toString();
        try {
            this.signingKey = new RSAKeyGenerator(2048).keyID("dev-oidc-signing").generate();
            // JWKS에 실리지 않는 키다. 이 키로 서명한 토큰은 서명 검증에 실패해야 한다.
            this.foreignKey = new RSAKeyGenerator(2048).keyID("dev-oidc-foreign").generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException("스텁 서명 키를 만들지 못했다.", ex);
        }
        this.macSecret = new byte[64];
        new SecureRandom().nextBytes(this.macSecret);
    }

    /** 스텁 표면이 그대로 내보낼 응답. */
    record Response(int status, String contentType, String body) {}

    /**
     * 발급된 code에 귀속된 인가 요청.
     *
     * <p>code 교환이 이 값들과 일치해야 토큰이 나간다. PKCE의 요점이 "인가를 시작한 쪽만 교환할 수
     * 있다"이므로 challenge를 code와 함께 붙들지 않으면 verifier를 비교할 대상이 없다.
     *
     * @param codeChallenge 인가 요청의 {@code code_challenge}(S256)
     * @param redirectUri 인가 요청의 {@code redirect_uri}. 교환 요청의 같은 값과 일치해야 한다
     * @param clientId 인가 요청의 {@code client_id}. 교환에서 인증된 client와 일치해야 한다
     */
    private record Grant(
            TokenPlan plan, String codeChallenge, String redirectUri, String clientId) {}

    String issuer() {
        return this.issuer;
    }

    String clientId() {
        return this.clientId;
    }

    String clientSecret() {
        return this.clientSecret;
    }

    /**
     * 공개 키 집합.
     *
     * <p>{@code signingKey}의 공개 부분만 내보낸다. {@code foreignKey}는 여기 없으므로 그 키로
     * 서명한 토큰은 검증에 실패한다.
     */
    Response jwks() {
        return json(200, new JWKSet(this.signingKey.toPublicJWK()).toString());
    }

    /**
     * discovery 문서.
     *
     * <p>이 애플리케이션은 어떤 프로필에서도 discovery 왕복을 하지 않는다. 엔드포인트는 구성으로
     * 받으므로({@code AuthSecurityConfig}) 이 문서는 사람이 스텁을 다른 클라이언트에 가리킬 때
     * 쓰는 것이며, 그 존재는 {@code verify-same-origin.sh}가 단정한다.
     */
    Response discovery(String authorizationUri, String tokenUri, String jwkSetUri) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("issuer", this.issuer);
        document.put("authorization_endpoint", authorizationUri);
        document.put("token_endpoint", tokenUri);
        document.put("jwks_uri", jwkSetUri);
        document.put("response_types_supported", List.of("code"));
        document.put("subject_types_supported", List.of("public"));
        document.put("id_token_signing_alg_values_supported", List.of(JWSAlgorithm.RS256.getName()));
        document.put("scopes_supported", List.of("openid"));
        document.put("code_challenge_methods_supported", List.of("S256"));
        return json(200, toJson(document));
    }

    /**
     * 계획대로 응답할 authorization code를 만들고 그 인가 요청에 귀속시킨다.
     *
     * @param authorizationRequest 인가 요청의 이름 → 값. {@code code_challenge}·{@code redirect_uri}·
     *     {@code client_id}가 있어야 하며 {@code code_challenge_method}는 {@code S256}이어야 한다.
     *     이 값들이 code에 붙어 토큰 교환에서 다시 확인된다.
     * @throws IllegalArgumentException 인가 요청이 PKCE S256의 모양이 아닐 때. 시험이 계약을 어긋나게
     *     쓴 것이므로 조용히 통과시키지 않는다
     */
    String issueCode(TokenPlan plan, Map<String, String> authorizationRequest) {
        String challenge = authorizationRequest.get("code_challenge");
        String redirectUri = authorizationRequest.get("redirect_uri");
        String clientId = authorizationRequest.get("client_id");
        if (isBlank(challenge)
                || isBlank(redirectUri)
                || isBlank(clientId)
                || !"S256".equals(authorizationRequest.get("code_challenge_method"))) {
            throw new IllegalArgumentException(
                    "PKCE S256 인가 요청에만 code를 발급한다. code_challenge·redirect_uri·client_id가"
                            + " 모두 있어야 한다.");
        }
        String code = "dev-oidc-code-" + UUID.randomUUID();
        this.grants.put(code, new Grant(plan, challenge, redirectUri, clientId));
        return code;
    }

    /**
     * 인가 요청을 콜백 URI로 옮긴다.
     *
     * <p>동의 화면이 열 링크의 목적지다. 실제 제공자가 브라우저를 되돌려 보내는 것과 같은 모양이며,
     * 여기서 만드는 계획은 언제나 정상 계획이다 — 실패 주입은 시험이 {@link #issueCode(TokenPlan)}로
     * 직접 한다.
     *
     * <p>{@code subject}는 기본적으로 요청마다 새로 만든다. 그래서 개발 스택의 왕복은 늘 신규
     * 가입이고 목적지는 온보딩이다. 고정된 {@code subject}를 소스에 두면 그 값이 곧 저장소에 남는
     * 식별자 리터럴이 된다.
     *
     * <p>{@link #SUBJECT_OVERRIDE_PARAMETER}가 그 예외다. 값을 <b>호출자가</b> 주므로 리터럴은
     * 저장소에 남지 않고, 같은 사람이 다시 돌아오는 흐름을 브라우저 층에서 재현할 수 있다 —
     * 재인증이 정확히 그 흐름이다(검증된 식별자가 현재 사용자의 것이어야 한다). 이 파라미터가
     * 없으면 개발·CI 스택에서 재인증 성공 경로가 존재할 수 없고, 그 계층은 실패만 볼 수 있다.
     *
     * <p>이 예외가 검사를 무르지 않는다. PKCE 재계산, code 일회성, client 인증,
     * {@code redirect_uri} 일치는 그대로다 — 바뀌는 것은 스텁이 주장하는 신원 하나이고, 실제
     * 제공자도 그 값을 스스로 정한다. 그리고 이 표면은 {@code dev-oidc} 프로필에만 존재하며 그
     * 프로필은 실제 제공자 등록과 함께 활성일 수 없다({@code DevOidcStubConfig}).
     *
     * <p>PKCE 없는 인가 요청은 열지 않는다. {@code code_challenge}가 없거나 method가 {@code S256}이
     * 아니면 code를 만들지 않는다 — 우리 쪽에서 PKCE가 꺼지면 브라우저 왕복이 여기서 멈춘다.
     *
     * @return 콜백 URI, 요청이 authorization request의 모양이 아니면 {@code null}
     */
    @Nullable String consentRedirect(Map<String, String> query) {
        recordReauthenticationParameters(query);
        String redirectUri = query.get("redirect_uri");
        String state = query.get("state");
        String nonce = query.get("nonce");
        if (isBlank(redirectUri) || isBlank(state) || isBlank(nonce)) {
            // 세 값 중 하나라도 없으면 이 흐름은 검증을 통과할 수 없다. 열어 주지 않는다.
            return null;
        }
        TokenPlan plan = TokenPlan.valid(nonce);
        String subjectOverride = query.get(SUBJECT_OVERRIDE_PARAMETER);
        if (!isBlank(subjectOverride)) {
            plan.subject(subjectOverride);
        }
        String code;
        try {
            code = issueCode(plan, query);
        } catch (IllegalArgumentException ex) {
            // PKCE S256이 아니다. 제공자가 인가를 거절하는 자리이며 이유를 본문에 적지 않는다.
            return null;
        }
        return redirectUri
                + (redirectUri.indexOf('?') < 0 ? '?' : '&')
                + "code="
                + encode(code)
                + "&state="
                + encode(state);
    }

    private void recordReauthenticationParameters(Map<String, String> query) {
        for (String name : REAUTHENTICATION_PARAMETER_NAMES) {
            String value = query.get(name);
            if (value != null) {
                this.reauthenticationParameters.put(name, value);
            } else {
                /*
                 * 없으면 지운다. 남겨 두면 재인증 왕복 뒤의 일반 로그인이 앞 왕복의 값을 그대로
                 * 보여 주고, "일반 로그인에는 얹지 않는다"를 보려는 시험이 통과하지 못한다.
                 */
                this.reauthenticationParameters.remove(name);
            }
        }
    }

    /**
     * 마지막 인가 요청에 실려 온 재인증 파라미터. 없으면 빈 맵이다.
     *
     * <p>이 스텁 인스턴스 하나의 마지막 요청이다. 제공자마다 인스턴스가 다르므로
     * ({@code DevOidcStubConfig}) 값이 제공자별로 갈린다.
     */
    Map<String, String> lastReauthenticationParameters() {
        return Map.copyOf(this.reauthenticationParameters);
    }

    /**
     * 토큰 교환.
     *
     * <p>code는 일회성이다. 조회가 아니라 제거로 꺼내므로 같은 code로 두 번 오면 두 번째는
     * {@code invalid_grant}다. 이것이 "authorization code는 한 번만 교환된다"가 스텁에서 성립하는
     * 지점이다.
     *
     * <p>PKCE는 재계산해 비교한다. {@code code_verifier}의 SHA-256을 base64url로 인코딩한 값이 인가
     * 시점에 붙들어 둔 {@code code_challenge}와 같아야 한다. 존재만 확인하면 우리 쪽이 challenge와
     * 무관한 verifier를 보내도 통과하고, 그러면 PKCE 결합이 깨진 것을 아무 시험도 보지 못한다.
     *
     * <p>client 인증과 {@code redirect_uri}도 확인한다. 실제 제공자가 하는 검사이며, 하지 않으면
     * 우리 쪽 등록이 잘못된 자격정보나 잘못된 redirect URI를 보내도 로그인이 성공한다.
     *
     * @param authorization 요청의 {@code Authorization} 헤더. Spring Security의 기본 client 인증
     *     방법이 {@code client_secret_basic}이라 자격정보가 본문이 아니라 이 헤더로 온다
     */
    Response token(Map<String, String> form, @Nullable String authorization) {
        String code = form.get("code");
        Grant grant = code == null ? null : this.grants.remove(code);
        if (grant == null) {
            // 모르는 code이거나 이미 교환된 code다. 실제 제공자와 같은 오류 모양으로 답하며
            // 둘을 구분하지 않는다.
            return json(400, "{\"error\":\"invalid_grant\"}");
        }
        if (grant.plan().tokenEndpointFailure) {
            return json(503, "{\"error\":\"server_error\"}");
        }
        if (!grant.clientId().equals(authenticatedClient(form, authorization))) {
            // 자격정보가 없거나 이 code를 받은 client가 아니다.
            return json(401, "{\"error\":\"invalid_client\"}");
        }
        if (!grant.redirectUri().equals(form.get("redirect_uri"))) {
            return json(400, "{\"error\":\"invalid_grant\"}");
        }
        String verifier = form.get("code_verifier");
        if (verifier == null || !grant.codeChallenge().equals(s256(verifier))) {
            return json(400, "{\"error\":\"invalid_grant\"}");
        }

        TokenPlan plan = grant.plan();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", "dev-oidc-access-" + UUID.randomUUID());
        response.put("token_type", "Bearer");
        response.put("expires_in", 300);
        response.put("scope", "openid");
        if (!plan.omitIdToken) {
            try {
                response.put("id_token", idToken(plan));
            } catch (JOSEException ex) {
                throw new IllegalStateException("ID Token을 서명하지 못했다.", ex);
            }
        }
        return json(200, toJson(response));
    }

    /**
     * 토큰 응답 계획.
     *
     * <p>기본값이 정상 흐름이다. 어긋나게 하려는 항목 하나만 바꿔 "그 하나 때문에 거부됐다"를
     * 단정한다. 가변 클래스인 것은 스텁 전용이기 때문이며, 이 애플리케이션의 다른 코드에는 이
     * 모양이 없다.
     */
    static final class TokenPlan {

        private String subject = "dev-oidc-subject-" + UUID.randomUUID();

        private @Nullable String issuer;

        private @Nullable List<String> audience;

        private @Nullable String authorizedParty;

        private @Nullable String nonce;

        private @Nullable Instant expiresAt;

        private @Nullable Instant issuedAt;

        /**
         * ID Token의 {@code auth_time}.
         *
         * <p>기본값이 없다는 것이 {@link #authTime}의 뜻이 아니다 — 값이 {@code null}이면 아래
         * {@link #idToken}이 발급 시각을 쓴다. "방금 인증했다"가 정상 흐름이므로 그것이 기본이고,
         * 이 필드는 그 기본을 어긋나게 하려는 시험만 채운다.
         */
        private @Nullable Instant authTime;

        /** claim 자체를 싣지 않는다. {@code auth_time}을 공표하지 않은 제공자를 재현한다. */
        private boolean omitAuthTime;

        private boolean foreignKey;

        private boolean macAlgorithm;

        private boolean omitIdToken;

        private boolean tokenEndpointFailure;

        /**
         * 정상 계획.
         *
         * @param nonceHash 인증 시작이 제공자에게 보낸 {@code nonce} 값. ID Token의 같은 이름 claim이
         *     이 값이어야 검증을 통과한다. 서버가 세션에 든 원본의 SHA-256 해시이며, 스텁은 그
         *     원본을 알 수도 없다.
         */
        static TokenPlan valid(String nonceHash) {
            TokenPlan plan = new TokenPlan();
            plan.nonce = nonceHash;
            return plan;
        }

        /** 같은 사람이 다시 로그인하는 흐름. {@code (issuer, subject)}가 같아야 같은 계정이다. */
        TokenPlan subject(String value) {
            this.subject = value;
            return this;
        }

        String subject() {
            return this.subject;
        }

        /** {@code iss}를 어긋나게 한다. */
        TokenPlan issuer(String value) {
            this.issuer = value;
            return this;
        }

        /** {@code aud}를 바꾼다. 값이 둘 이상이면 {@code azp}가 필수가 된다. */
        TokenPlan audience(List<String> values) {
            this.audience = List.copyOf(values);
            return this;
        }

        TokenPlan authorizedParty(String value) {
            this.authorizedParty = value;
            return this;
        }

        /** {@code nonce}를 어긋나게 한다. */
        TokenPlan nonce(@Nullable String value) {
            this.nonce = value;
            return this;
        }

        /** 이미 만료된 토큰. Spring Security의 시계 오차 허용(60초)보다 멀리 둔다. */
        TokenPlan expired() {
            Instant now = Instant.now();
            this.issuedAt = now.minus(Duration.ofHours(2));
            this.expiresAt = now.minus(Duration.ofHours(1));
            return this;
        }

        /** JWKS에 없는 키로 서명한다. 서명 검증이 실패해야 한다. */
        TokenPlan foreignKey() {
            this.foreignKey = true;
            return this;
        }

        /** RS256이 아닌 알고리즘으로 서명한다. 허용 알고리즘 검사가 막아야 한다. */
        TokenPlan macAlgorithm() {
            this.macAlgorithm = true;
            return this;
        }

        /**
         * {@code auth_time}을 특정 시각으로 둔다.
         *
         * <p>재인증의 신선도 판정을 어긋나게 하는 유일한 축이다. 오래된 인증과 미래의 인증을
         * 여기서 주입한다.
         */
        TokenPlan authTime(Instant value) {
            this.authTime = value;
            return this;
        }

        /**
         * {@code auth_time} claim을 아예 싣지 않는다.
         *
         * <p>증거 부재다. 네이버처럼 그 claim을 공표하지 않은 제공자가 실연동에서 만들 수 있는
         * 응답이며, 이 축이 없으면 "증거가 없으면 완화하지 않고 실패한다"를 아무 시험도 보지
         * 않는다.
         */
        TokenPlan omitAuthTime() {
            this.omitAuthTime = true;
            return this;
        }

        /** ID Token 없는 토큰 응답. OIDC 흐름이 성립하지 않는다. */
        TokenPlan omitIdToken() {
            this.omitIdToken = true;
            return this;
        }

        /** 토큰 엔드포인트 장애. 제공자 5xx·응답 유실을 재현한다. */
        TokenPlan tokenEndpointFailure() {
            this.tokenEndpointFailure = true;
            return this;
        }
    }

    /**
     * PKCE S256 변환. RFC 7636의 {@code BASE64URL(SHA256(ASCII(code_verifier)))}이다.
     *
     * <p>인가 시점의 {@code code_challenge}를 그대로 믿지 않고 여기서 다시 계산한다. 그래야 verifier
     * 하나가 challenge와 실제로 한 쌍인지 확인된다.
     */
    static String s256(String verifier) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다.", ex);
        }
    }

    /**
     * 요청이 증명한 client id.
     *
     * <p>{@code client_secret_basic}이 기본이므로 자격정보는 {@code Authorization: Basic} 헤더로
     * 온다. Spring Security가 두 값을 URL 인코딩해 싣기 때문에 디코딩한 뒤 비교한다.
     * {@code client_secret_post}로 온 요청도 받아 두어 client 인증 방법을 바꿔도 이 검사가 그대로
     * 성립한다.
     *
     * @return 자격정보가 이 스텁의 것과 일치하면 그 client id, 아니면 {@code null}
     */
    private @Nullable String authenticatedClient(
            Map<String, String> form, @Nullable String authorization) {
        String id = form.get("client_id");
        String secret = form.get("client_secret");
        if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded =
                        new String(
                                Base64.getDecoder().decode(authorization.substring(6).trim()),
                                StandardCharsets.UTF_8);
                int separator = decoded.indexOf(':');
                if (separator < 0) {
                    return null;
                }
                id = URLDecoder.decode(decoded.substring(0, separator), StandardCharsets.UTF_8);
                secret = URLDecoder.decode(decoded.substring(separator + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                // base64가 아니다. 자격정보를 증명하지 못한 것과 같다.
                return null;
            }
        }
        return this.clientId.equals(id) && this.clientSecret.equals(secret) ? id : null;
    }

    /** {@code application/x-www-form-urlencoded} 본문·쿼리를 이름 → 값으로 푼다. */
    static Map<String, String> parseForm(String body) {
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            values.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private String idToken(TokenPlan plan) throws JOSEException {
        Instant issuedAt = plan.issuedAt != null ? plan.issuedAt : Instant.now();
        Instant expiresAt = plan.expiresAt != null ? plan.expiresAt : issuedAt.plus(DEFAULT_LIFETIME);
        List<String> audience = plan.audience != null ? plan.audience : List.of(this.clientId);

        JWTClaimsSet.Builder claims =
                new JWTClaimsSet.Builder()
                        .issuer(plan.issuer != null ? plan.issuer : this.issuer)
                        .subject(plan.subject)
                        .audience(audience)
                        .issueTime(Date.from(issuedAt))
                        .expirationTime(Date.from(expiresAt));
        if (plan.nonce != null) {
            claims.claim("nonce", plan.nonce);
        }
        if (plan.authorizedParty != null) {
            claims.claim("azp", plan.authorizedParty);
        }
        if (!plan.omitAuthTime) {
            /*
             * 최근 인증 증거.
             *
             * 기본은 발급 시각이다 — 정상 흐름은 "방금 인증했다"이고, 그 기본이 없으면 재인증의
             * 성공 경로를 시험이 지날 수 없다. 일반 로그인 왕복에도 같은 claim이 실리며, 그것은
             * OIDC Core가 `max_age`와 함께 요구하는 모양 그대로다.
             *
             * 초 단위 정수로 싣는다. OIDC Core의 NumberDate이며 실제 제공자가 보내는 모양이다.
             * `Instant`를 그대로 담으면 Nimbus가 다른 표현으로 직렬화해 검증기가 읽지 못한다.
             */
            Instant authTime = plan.authTime != null ? plan.authTime : issuedAt;
            claims.claim("auth_time", authTime.getEpochSecond());
        }

        if (plan.macAlgorithm) {
            SignedJWT jwt =
                    new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims.build());
            jwt.sign(new MACSigner(this.macSecret));
            return jwt.serialize();
        }
        RSAKey key = plan.foreignKey ? this.foreignKey : this.signingKey;
        JWSSigner signer = new RSASSASigner(key);
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims.build());
        jwt.sign(signer);
        return jwt.serialize();
    }

    private static Response json(int status, String body) {
        return new Response(status, "application/json", body);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /** 스텁 응답용 최소 JSON 직렬화. 값은 문자열·정수·문자열 목록뿐이라 라이브러리를 들이지 않는다. */
    private static String toJson(Map<String, Object> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":").append(jsonValue(entry.getValue()));
        }
        return json.append('}').toString();
    }

    private static String jsonValue(Object value) {
        if (value instanceof Integer number) {
            return number.toString();
        }
        if (value instanceof List<?> items) {
            StringBuilder array = new StringBuilder("[");
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    array.append(',');
                }
                first = false;
                array.append('"').append(item).append('"');
            }
            return array.append(']').toString();
        }
        return "\"" + value + "\"";
    }
}
