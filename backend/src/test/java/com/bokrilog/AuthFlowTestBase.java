package com.bokrilog;

import com.bokrilog.DevOidcStub.TokenPlan;
import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.identity.AccountLifecycleApi;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증 흐름 시험의 공통 배선.
 *
 * <p>인증 시험 전부가 같은 Spring 컨텍스트와 같은 PostgreSQL 컨테이너를 공유한다. 공유의 조건은
 * 컨텍스트 customizer가 서로 같다는 것이고, 그래서 {@code @DynamicPropertySource} 메서드를 이 기반
 * 클래스에 둔다. 하위 클래스마다 같은 메서드를 다시 쓰면 customizer가 달라져 컨텍스트가 클래스마다
 * 하나씩 뜨고, {@code backend-quality} job의 wall-clock이 그만큼 늘어난다.
 *
 * <p>MockMvc가 아니라 실제 서블릿 컨테이너에 진짜 HTTP 요청을 보낸다. 이 스토리가 고정하려는 것은
 * 필터 체인을 지나 밖으로 나가는 바이트다 — 302의 {@code Location}, {@code Set-Cookie}의 속성,
 * 오류 본문. 셋 다 컨트롤러 바깥에서 결정되므로 그 경로를 통과시켜야 시험이 의미를 가진다.
 *
 * <p>클라이언트는 JDK 내장 {@link HttpClient}다. 기본 {@code followRedirects}가 {@code NEVER}라
 * 302를 직접 볼 수 있고, 쿠키 정책을 강제하지 않아 {@code Secure} 쿠키도 문자열 그대로 관측된다
 * ({@code StatusApiTest}와 같은 관용). 브라우저처럼 굴지 않는 것이 여기서는 장점이다.
 *
 * <p>단정을 하지 않는다. 여기 있는 것은 요청을 보내고 응답 조각을 꺼내고 저장소 행을 세는 일뿐이고,
 * 무엇이 참이어야 하는지는 각 시험이 자기 계약으로 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfig.class)
abstract class AuthFlowTestBase {

    /** {@code application.yml}이 정한 세션 쿠키 이름. 값이 갈라지면 여기서 먼저 드러난다. */
    static final String SESSION_COOKIE_NAME = "BOKRILOG_SESSION";

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        OidcStubConfiguration.registerProperties(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AccountLifecycleApi accountLifecycle;

    @Autowired
    private AccountDeletionApi accountDeletion;

    @Autowired
    private SessionGuard sessionGuard;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * 저장소를 비운다.
     *
     * <p>이 시험들은 "행이 몇 개인가"를 단정한다. 앞선 시험이 남긴 계정·세션이 있으면 그 단정이
     * 무엇을 세는지 알 수 없게 되므로, 새 UUID로 충돌만 피하는 대신 실제로 비운다. 컨테이너는 JVM
     * 하나에 하나뿐이고 시험은 순차 실행이라 이 초기화가 다른 시험과 겹치지 않는다.
     */
    @BeforeEach
    void resetStore() {
        this.jdbc.sql("TRUNCATE TABLE spring_session, spring_session_attributes").update();
        this.jdbc
                .sql(
                        "TRUNCATE TABLE identity_social_link, identity_account, "
                                + "identity_account_lifecycle CASCADE")
                .update();
    }

    // ----------------------------------------------------------------------
    // 흐름
    // ----------------------------------------------------------------------

    /**
     * 인증 시작이 만든 authorization request. 콜백을 흉내내는 데 필요한 값만 담는다.
     *
     * @param provider 이 흐름을 시작한 제공자 등록 id. {@link #issueCode(Authorization, TokenPlan)}가
     *     이 값으로 스텁 인스턴스를 고르므로 호출부가 제공자를 다시 적지 않는다 — 흐름과 code 발급이
     *     같은 제공자를 쓴다는 것이 여기서 구조로 성립한다
     */
    record Authorization(
            String provider,
            int status,
            String location,
            @Nullable String sessionCookie,
            Map<String, String> parameters) {

        /** 서버가 발급한 일회성 {@code state}. 세션에 귀속돼 있다. */
        String state() {
            return this.parameters.getOrDefault("state", "");
        }

        /** 제공자에게 보낸 {@code nonce}. 서버가 세션에 든 원본의 해시다. */
        String nonce() {
            return this.parameters.getOrDefault("nonce", "");
        }
    }

    Authorization startAuthorization() throws IOException, InterruptedException {
        return startAuthorization(null, null);
    }

    /**
     * Google 흐름의 인증 시작.
     *
     * <p>제공자를 받지 않는 형태를 남겨 둔다. Story 1.4·1.5의 시험 호출부가 전부 이 모양이고, 그
     * 시험들이 고정하는 것은 OIDC 코어 검증처럼 제공자에 종속되지 않는 계약이다. 축을 제공자로
     * 곱하면 같은 것을 두 번 보는 시험이 늘어난다.
     */
    Authorization startAuthorization(@Nullable String next, @Nullable String sessionCookie)
            throws IOException, InterruptedException {
        return startAuthorization(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, next, sessionCookie);
    }

    Authorization startAuthorization(
            String provider, @Nullable String next, @Nullable String sessionCookie)
            throws IOException, InterruptedException {
        String path =
                AuthSecurityConfig.AUTHORIZATION_BASE_URI
                        + "/"
                        + provider
                        + (next == null ? "" : "?next=" + next);
        HttpResponse<String> response = get(path, sessionCookie);
        String location = response.headers().firstValue("Location").orElse("");
        return new Authorization(
                provider,
                response.statusCode(),
                location,
                sessionCookieOf(response).orElse(sessionCookie),
                queryParameters(location));
    }

    /**
     * 이 인가 요청에 귀속된 authorization code.
     *
     * <p>스텁이 {@code code_challenge}·{@code redirect_uri}·{@code client_id}를 code와 함께 붙들어
     * 두고 토큰 교환에서 다시 확인한다({@code DevOidcStub}). 귀속 없이 code를 발급할 수 있게 두면
     * PKCE 결합이 깨져도 이 시험들이 전부 통과하므로, 발급 경로를 이 한 곳으로 좁힌다.
     *
     * <p>제공자는 인가 요청이 들고 온다. 인자로 다시 받으면 흐름과 다른 제공자의 스텁에 code를
     * 발급하는 호출이 가능해지고, 그것은 시험이 계약을 어긋나게 쓰는 자리가 된다.
     */
    static String issueCode(Authorization authorization, TokenPlan plan) {
        return OidcStubConfiguration.issueCode(
                authorization.provider(), plan, authorization.parameters());
    }

    /**
     * 검증된 콜백. 실제 제공자가 브라우저를 되돌려 보내는 것과 같은 요청이다.
     *
     * <p>제공자를 인가 요청에서 가져온다. 인자 없이 google로 접는 형태를 두면 네이버 흐름 뒤에
     * 그것을 부르는 호출이 <b>google 콜백 경로</b>로 가고, 그 요청은 흐름의 {@code state}·PKCE와
     * 어긋나므로 시험이 엉뚱한 이유로 통과한다. 실패를 단정하는 시험에서 특히 조용하다 — 기대한
     * 실패 축이 아니라 경로 불일치가 그 실패를 만든다. {@link #issueCode(Authorization, TokenPlan)}가
     * 같은 이유로 제공자를 인자로 받지 않는다.
     */
    HttpResponse<String> callback(Authorization authorization, String code)
            throws IOException, InterruptedException {
        return callback(authorization, authorization.sessionCookie(), code, authorization.state());
    }

    /**
     * 쿠키나 {@code state}를 흐름과 다르게 주는 콜백. 세션 귀속과 재생 방어를 보는 시험이 쓴다.
     *
     * <p>제공자만은 흐름이 정한다. 그 축까지 호출부가 정하면 위 결함이 다시 열린다.
     */
    HttpResponse<String> callback(
            Authorization authorization, @Nullable String sessionCookie, String code, String state)
            throws IOException, InterruptedException {
        return callback(authorization.provider(), sessionCookie, code, state);
    }

    /**
     * 제공자를 이름으로 지정하는 콜백.
     *
     * <p>인가 요청 없이 만드는 요청에만 쓴다 — 외부 사이트가 콜백 URL을 그냥 여는 모양이 그렇다.
     * 흐름이 있으면 위 두 형태를 쓴다.
     */
    HttpResponse<String> callback(
            String provider, @Nullable String sessionCookie, String code, String state)
            throws IOException, InterruptedException {
        return get(callbackPath(provider) + "?code=" + code + "&state=" + state, sessionCookie);
    }

    /** 사용자가 취소했거나 제공자가 오류로 되돌린 콜백. 제공자는 흐름이 들고 온다. */
    HttpResponse<String> callbackWithError(Authorization authorization, String error)
            throws IOException, InterruptedException {
        return callbackWithError(
                authorization.provider(),
                authorization.sessionCookie(),
                error,
                authorization.state());
    }

    HttpResponse<String> callbackWithError(
            String provider, @Nullable String sessionCookie, String error, String state)
            throws IOException, InterruptedException {
        return get(callbackPath(provider) + "?error=" + error + "&state=" + state, sessionCookie);
    }

    /** 인증 시작 → 검증된 콜백까지 한 번에. 정상 로그인 하나가 필요한 시험이 쓴다. */
    HttpResponse<String> signIn(String subject) throws IOException, InterruptedException {
        return signIn(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, subject);
    }

    HttpResponse<String> signIn(String provider, String subject)
            throws IOException, InterruptedException {
        Authorization authorization = startAuthorization(provider, null, null);
        String code =
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(subject));
        return callback(authorization, code);
    }

    HttpResponse<String> sessionState(@Nullable String sessionCookie)
            throws IOException, InterruptedException {
        return get(AuthSecurityConfig.SESSION_PATH, sessionCookie);
    }

    /**
     * 이 세션에 귀속된 CSRF 토큰.
     *
     * <p>제품 코드가 쓰는 것과 같은 경로로 얻는다 — {@code GET /api/auth/session}의 응답 본문
     * 하나다. 쿠키 저장소를 쓰지 않는 구성에서 그것이 유일한 통로이고, 시험이 저장소를 뒤져
     * 토큰을 꺼내면 화면이 실제로 지나는 경로를 아무 시험도 보지 않게 된다.
     *
     * @return 응답에 실린 토큰, 미인증 응답이면 {@code null}
     */
    @Nullable String csrfTokenOf(@Nullable String sessionCookie)
            throws IOException, InterruptedException {
        return csrfTokenIn(sessionState(sessionCookie).body());
    }

    /**
     * 세션 확인 응답 본문에서 CSRF 토큰만 꺼낸다. 필드가 없으면 {@code null}이다.
     *
     * <p>본문을 파싱한다. 정규식으로 {@code "csrfToken":}을 찾으면 그 이름이 <b>어디에</b> 있든
     * 잡히므로, 값이 중첩 객체 안으로 옮겨져도 이 헬퍼는 계속 값을 돌려주고 아래
     * {@link #topLevelFieldsOf}가 고정하려는 "최상위 필드" 계약이 조용히 약해진다.
     */
    static @Nullable String csrfTokenIn(String body) {
        Object value = topLevelFieldsOf(body).get("csrfToken");
        return value instanceof String text ? text : null;
    }

    /**
     * JSON 객체의 <b>최상위</b> 필드를 나온 순서대로.
     *
     * <p>본문 전문 비교를 대체하는 도구다. 토큰 값이 실행마다 달라 전문을 고정할 수 없게 됐지만,
     * 고정하려던 것은 값이 아니라 무엇이 나가는가였다. 이름 목록이 그 계약이며, 열지 않은 필드가
     * 하나라도 늘면 목록이 달라진다. 파싱이므로 중첩 객체의 이름은 여기 나타나지 않는다 — 값이
     * 한 겹 안으로 들어가는 변경도 목록의 변화로 드러난다.
     *
     * <p>{@link LinkedHashMap}이라 순서가 보존된다. 애플리케이션이 쓰는 매퍼가 아니라 읽기 전용
     * 매퍼인 것이 의도다: 여기서 확인하는 것은 나간 바이트이고, 직렬화 규칙 자체는
     * {@code AuthSessionStateJsonTest}가 애플리케이션의 매퍼로 본다.
     */
    static Map<String, Object> topLevelFieldsOf(String body) {
        return JSON.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /** 응답 본문을 읽는 데만 쓰는 매퍼. */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    /**
     * 로그아웃 요청.
     *
     * <p>Story 1.4가 고정한 {@code /api/auth/} 상태 변경 계약을 그대로 지난다 — 세션 귀속 CSRF
     * 토큰과 동일 출처 {@code Origin}이다. 둘 중 하나를 비우거나 틀린 값으로 주는 것이 곧 이
     * 스토리의 거절 시험이므로 인자로 받는다.
     *
     * @param csrfToken 헤더에 실을 값. {@code null}이면 헤더를 붙이지 않는다
     * @param origin {@code Origin} 헤더 값. {@code null}이면 헤더를 붙이지 않는다
     */
    HttpResponse<String> logout(
            @Nullable String sessionCookie, @Nullable String csrfToken, @Nullable String origin)
            throws IOException, InterruptedException {
        List<String> headers = new ArrayList<>();
        if (origin != null) {
            headers.add("Origin");
            headers.add(origin);
        }
        if (csrfToken != null) {
            headers.add(AuthSecurityConfig.CSRF_HEADER_NAME);
            headers.add(csrfToken);
        }
        return send(
                "POST",
                AuthSecurityConfig.LOGOUT_PATH,
                sessionCookie,
                headers.toArray(new String[0]));
    }

    /** 계약을 모두 만족시키는 로그아웃. 정상 경로가 필요한 시험이 쓴다. */
    HttpResponse<String> logout(@Nullable String sessionCookie, @Nullable String csrfToken)
            throws IOException, InterruptedException {
        return logout(sessionCookie, csrfToken, OidcStubConfiguration.PUBLIC_BASE_URL);
    }

    HttpResponse<String> get(String path, @Nullable String sessionCookie)
            throws IOException, InterruptedException {
        return send("GET", path, sessionCookie);
    }

    HttpResponse<String> send(
            String method, String path, @Nullable String sessionCookie, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + this.port + path))
                        .timeout(Duration.ofSeconds(20))
                        .method(method, HttpRequest.BodyPublishers.noBody());
        if (sessionCookie != null) {
            builder.header("Cookie", SESSION_COOKIE_NAME + "=" + sessionCookie);
        }
        for (int index = 0; index + 1 < headers.length; index += 2) {
            builder.header(headers[index], headers[index + 1]);
        }
        return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String callbackPath() {
        return callbackPath(AuthSecurityConfig.GOOGLE_REGISTRATION_ID);
    }

    /**
     * 그 제공자의 콜백 경로.
     *
     * <p>URL 표면은 이미 제공자 복수형이다 — 콜백 필터가 {@code CALLBACK_BASE_URI + "/*"}
     * 와일드카드로 받고 마지막 세그먼트가 등록 id다. 그래서 이 경로를 만드는 데 새 배선이 없다.
     */
    static String callbackPath(String provider) {
        return AuthSecurityConfig.CALLBACK_BASE_URI + "/" + provider;
    }

    /**
     * 그 인증 매핑의 {@code provider} 컬럼 값.
     *
     * <p>인증에 실제로 쓰인 제공자가 저장됐는지 보는 유일한 창이다. {@code (issuer, subject)}가
     * 전역 유일이므로 이 술어로 행 하나가 정해진다.
     *
     * @return 저장된 값, 그 매핑이 없으면 빈 값
     */
    Optional<String> linkedProviderOf(String issuer, String subject) {
        return this.jdbc
                .sql(
                        "SELECT provider FROM identity_social_link "
                                + "WHERE issuer = :issuer AND subject = :subject")
                .param("issuer", issuer)
                .param("subject", subject)
                .query(String.class)
                .optional();
    }

    // ----------------------------------------------------------------------
    // 응답 조각
    // ----------------------------------------------------------------------

    /**
     * 리다이렉트 목적지.
     *
     * <p>절대 URI로 비교하지 않는다. 서블릿 컨테이너의 {@code useRelativeRedirects} 설정에 따라
     * {@code Location}이 상대 경로일 수도 절대 URI일 수도 있고, 이 시험이 고정하려는 것은 그
     * 표현이 아니라 어느 경로로 보냈는가다.
     */
    static String locationOf(HttpResponse<String> response) {
        return response.headers().firstValue("Location").orElse("");
    }

    /** 응답이 새로 준 세션 쿠키 값. 회전을 관측하는 데 쓴다. */
    static Optional<String> sessionCookieOf(HttpResponse<String> response) {
        return setCookieOf(response).flatMap(AuthFlowTestBase::cookieValue);
    }

    /** {@code Set-Cookie} 헤더 전문. 속성(HttpOnly·Secure·SameSite)을 문자열로 단정한다. */
    static Optional<String> setCookieOf(HttpResponse<String> response) {
        List<String> headers = response.headers().allValues("Set-Cookie");
        return headers.stream()
                .filter(header -> header.startsWith(SESSION_COOKIE_NAME + "="))
                .findFirst();
    }

    /**
     * 세션 쿠키 값이 가리키는 세션 id.
     *
     * <p>쿠키 값은 세션 id 그 자체가 아니다. Spring Session의 {@code DefaultCookieSerializer}가
     * base64(URL 안전, 패딩 없음)로 실어 보낸다({@code application.yml}의 쿠키 주석). 세션 단위
     * 조작을 시험하려면 그 인코딩을 되돌려야 하고, 인코딩 가정이 틀리면 {@code SessionGuard}가
     * 없는 세션을 대상으로 삼아 {@code false}를 돌려주므로 시험이 소리 내어 실패한다.
     */
    static String sessionIdOf(String cookieValue) {
        return new String(Base64.getUrlDecoder().decode(cookieValue), StandardCharsets.UTF_8);
    }

    private static Optional<String> cookieValue(String setCookie) {
        String head = setCookie.split(";", 2)[0];
        int separator = head.indexOf('=');
        if (separator < 0) {
            return Optional.empty();
        }
        String value = head.substring(separator + 1);
        // 값이 비면 만료 지시다(세션 무효화). 회전과 구분해야 하므로 빈 값으로 남기지 않는다.
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** URL의 쿼리 문자열을 이름 → 값으로 푼다. */
    static Map<String, String> queryParameters(String url) {
        Map<String, String> parameters = new HashMap<>();
        int start = url.indexOf('?');
        if (start < 0) {
            return parameters;
        }
        for (String pair : url.substring(start + 1).split("&")) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            parameters.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return parameters;
    }

    // ----------------------------------------------------------------------
    // 저장소
    // ----------------------------------------------------------------------

    /** 생명주기 guard 행 수. "계정이 몇 개 만들어졌는가"의 답이다. */
    long accountCount() {
        return count("SELECT count(*) FROM identity_account_lifecycle");
    }

    /** 인증 매핑 행 수. */
    long socialLinkCount() {
        return count("SELECT count(*) FROM identity_social_link");
    }

    /** 세션 행 수. 로그인하지 않은 흐름이 세션을 남기지 않았는지 본다. */
    long sessionCount() {
        return count("SELECT count(*) FROM spring_session");
    }

    /**
     * 한 사용자의 세션 행 수.
     *
     * <p>전체 개수만 세면 "다른 사용자의 세션이 보존됐다"를 확인할 수 없다. 두 사용자가 각각
     * 하나를 가진 상태와 한 사용자가 둘을 가진 상태가 같은 숫자이기 때문이다. {@code PRINCIPAL_NAME}
     * 은 내부 {@code user_id} 문자열이며({@code AuthFlowHandlers}) 그 값이 색인 컬럼이다.
     */
    long sessionCountOf(UUID userId) {
        return this.jdbc
                .sql("SELECT count(*) FROM spring_session WHERE principal_name = :principal")
                .param("principal", userId.toString())
                .query(Long.class)
                .single();
    }

    /**
     * 세션 <b>하나</b>의 유휴 만료를 과거로 옮긴다.
     *
     * <p>시험이 30분을 기다릴 수는 없고, 수명을 줄이는 설정을 시험용으로 따로 두면 그 설정이
     * 검증하는 값과 제품이 쓰는 값이 갈라진다. 그래서 시계 대신 저장소의 시각을 옮긴다 —
     * Spring Session은 {@code LAST_ACCESS_TIME + MAX_INACTIVE_INTERVAL}로 만료를 판정하므로
     * 마지막 접근을 충분히 과거로 두면 다음 접근이 그 세션을 만료로 다룬다.
     *
     * <p>대상을 인자로 받는다({@link #sessionCountOf}와 같은 이유). {@code WHERE} 없이 두면 두
     * 세션·두 사용자 만료 시험이 뒤에 붙는 날 이 호출이 조용히 모든 행을 늙게 만들고, 그때 그
     * 시험은 "다른 세션은 보존된다"를 확인할 수 없는 상태가 된다.
     *
     * @param sessionId {@link #sessionIdOf}로 되돌린 세션 id
     */
    void ageLastAccess(String sessionId, Duration elapsed) {
        long shifted = System.currentTimeMillis() - elapsed.toMillis();
        requireSingleRow(
                this.jdbc
                        .sql(
                                "UPDATE spring_session SET last_access_time = :accessed, "
                                        + "expiry_time = :accessed + (max_inactive_interval * 1000) "
                                        + "WHERE session_id = :id")
                        .param("accessed", shifted)
                        .param("id", sessionId)
                        .update(),
                sessionId);
    }

    /**
     * 세션 <b>하나</b>의 생성 시각을 과거로 옮긴다.
     *
     * <p>유휴 만료와 절대 수명을 갈라서 보기 위한 도구다. 마지막 접근은 그대로 두므로 이 세션은
     * "계속 쓰이고 있지만 만들어진 지 오래된" 상태가 된다. 대상 지정의 근거는
     * {@link #ageLastAccess}와 같다.
     *
     * @param sessionId {@link #sessionIdOf}로 되돌린 세션 id
     */
    void ageCreation(String sessionId, Duration elapsed) {
        requireSingleRow(
                this.jdbc
                        .sql("UPDATE spring_session SET creation_time = :created WHERE session_id = :id")
                        .param("created", System.currentTimeMillis() - elapsed.toMillis())
                        .param("id", sessionId)
                        .update(),
                sessionId);
    }

    /**
     * 시각을 옮긴 행이 정확히 하나인지 본다.
     *
     * <p>0건이면 세션 id 가정이 틀렸다는 뜻이고, 그때 뒤따르는 단정은 아무 일도 일어나지 않은
     * 세션을 보며 조용히 통과한다. 소리 내어 실패시킨다.
     */
    private static void requireSingleRow(int updated, String sessionId) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "세션 하나를 옮기려 했는데 " + updated + "행이 바뀌었다: " + sessionId);
        }
    }

    /** 세션 상태 전환의 내부 표면. 개발·CI는 HTTP가 아니라 이 bean으로 상태를 만든다. */
    SessionGuard sessionGuard() {
        return this.sessionGuard;
    }

    /** 특정 인증 매핑에 연결된 계정. 재로그인이 같은 계정을 쓰는지 본다. */
    Optional<UUID> userIdOf(String issuer, String subject) {
        return this.accountLifecycle.findUserIdBySocialIdentity(issuer, subject);
    }

    AccountLifecycleApi accountLifecycle() {
        return this.accountLifecycle;
    }

    AccountDeletionApi accountDeletion() {
        return this.accountDeletion;
    }

    JdbcClient jdbc() {
        return this.jdbc;
    }

    private long count(String sql) {
        return this.jdbc.sql(sql).query(Long.class).single();
    }
}
