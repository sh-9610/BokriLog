package com.bokrilog;

import com.bokrilog.DevOidcStub.TokenPlan;
import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.identity.AccountLifecycleApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

    /**
     * 시험 컨텍스트의 워커 dispatch 간격. 운영은 {@code application.yml}의 1초다.
     *
     * <p>실제 파이프라인(첫 스냅샷 → 수집 → 판정 → 환산 → 발행 → 증분 동기화)을 모는 시험은 단계마다 최대
     * 한 tick을 기다린다. 1초 tick이면 그 대기가 시험 시간의 대부분이 된다. heartbeat(5초)는 줄이지 않는다
     * — 늦은 워커가 DB fence까지 가는 창이 그 간격에 묶여 있다({@code FirstSnapshotWorker}).
     */
    static final Duration WORKER_TICK = Duration.ofMillis(100);

    /**
     * "일어나지 않음"을 증명하는 창. tick 다섯 개 길이다. {@code fixedDelay}는 각 실행이 끝난 뒤부터 세므로
     * 이 창이 주는 dispatch 기회는 정확히 다섯 번이 아니라 네다섯 번이다. 원래 창(1.2~2.5초)이 1초 간격에서 준
     * 기회는 한 번에서 세 번이었으므로 창이 짧아져도 증명은 약해지지 않는다. 관측 가능한 조건을 기다리는 데
     * 쓰지 않는다.
     */
    static final Duration QUIET_WINDOW = WORKER_TICK.multipliedBy(5);

    /** {@link #pollUntil}의 조회 간격. */
    static final Duration POLL = Duration.ofMillis(20);

    /** 시험 컨텍스트의 GPT ad unit 경로(Story 4.3). 형식만 맞는 가짜 값이고 공급자에 닿지 않는다. */
    static final String AD_UNIT_PATH = "/1234567/bokrilog_test";

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        OidcStubConfiguration.registerProperties(registry);
        /*
         * Story 2.1의 source 구성도 같은 메서드에서 채운다. 새 `@DynamicPropertySource`를 만들면
         * 컨텍스트 customizer가 달라져 컨텍스트가 하나 더 뜨고, 그 비용은 `backend-quality`의
         * wall-clock이다.
         */
        BybitStubConfiguration.registerProperties(registry);
        // 워커 dispatch 간격도 같은 근거로 여기서 준다.
        registry.add("bokrilog.sync.worker.dispatch-interval", () -> WORKER_TICK.toMillis() + "ms");
        // Story 3.1의 운영자 설정도 같은 근거로 여기서 준다. digest 키는 API 기동의 필수값이다.
        OperatorTestProperties.registerProperties(registry);
        // Story 4.2의 광고 공급자 활성도 같은 근거로 여기서 준다. 운영 기본값은 꺼짐(false)이다. 꺼짐을 보는 시험은
        // `AdProviderTestHook`으로 잠시 끄고 되돌린다.
        registry.add("bokrilog.engagement.ad-provider-enabled", () -> "true");
        // Story 4.3: 공급자 활성은 플래그와 형식에 맞는 ad unit 경로가 함께일 때다. 운영 기본값은 빈 값이다.
        registry.add("bokrilog.engagement.ad-unit-path", () -> AD_UNIT_PATH);
    }

    /**
     * {@code done}이 참이 되거나 {@code timeout}이 지날 때까지 {@link #POLL}마다 {@code read}를 다시 읽고,
     * 마지막으로 읽은 값을 돌려준다.
     *
     * <p>단정하지 않는다. 시간이 다 돼도 예외 없이 그때의 값을 돌려주므로, 그 값이 무엇이어야 하는지는
     * 호출하는 시험이 자기 계약으로 쓴다.
     */
    static <T> T pollUntil(Duration timeout, Supplier<? extends T> read, Predicate<? super T> done)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        T value = read.get();
        while (!done.test(value) && System.nanoTime() - deadline < 0) {
            Thread.sleep(POLL);
            value = read.get();
        }
        return value;
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager storeResetTransactions;

    @Autowired
    private AccountLifecycleApi accountLifecycle;

    @Autowired
    private AccountDeletionApi accountDeletion;

    @Autowired
    private SessionGuard sessionGuard;

    /**
     * 금융 source 연결의 공개 API(Story 2.1).
     *
     * <p>HTTP를 지나지 않는 축 하나가 이 빈을 직접 부른다 — 삭제 경로다. 전체 계정 삭제
     * orchestration은 Epic 5의 것이라 지금은 HTTP 표면이 없고, 그래도 이 스토리가 만든 데이터의
     * 삭제 경로는 함께 완성돼야 한다.
     */
    @Autowired
    private SourceConnectionApi sources;

    @Autowired
    private com.bokrilog.sync.SyncJobApi syncJobs;

    /** portfolio membership의 공개 API. {@link #sources}와 같은 근거로 주입한다. */
    @Autowired
    private PortfolioMembershipApi portfolios;

    /**
     * 연결의 판정과 실행.
     *
     * <p>HTTP를 지나지 않는 축 하나가 이 빈을 직접 부른다 — 같은 {@code operation_id}의 재전송이다.
     * 연결 흐름은 콜백 하나로 소비되므로 HTTP 층에서는 같은 무장으로 두 번 들어갈 수 없고, 그래서
     * 멱등 경로를 관측하려면 포트를 직접 두 번 불러야 한다.
     */
    @Autowired
    private LinkService linkService;

    /**
     * 해제의 판정과 실행.
     *
     * <p>HTTP를 지나지 않는 축 하나가 이 빈을 직접 부른다 — 같은 {@code operation_id}의 재전송이다.
     * 해제 무장은 최종 확인 하나로 소비되므로 HTTP 층에서는 같은 무장으로 두 번 들어갈 수 없고,
     * 그래서 멱등 경로를 관측하려면 포트를 직접 두 번 불러야 한다({@link #linkService}와 같은 근거).
     */
    @Autowired
    private UnlinkService unlinkService;

    /**
     * 세션 저장소.
     *
     * <p>세션 속성을 애플리케이션과 같은 경로로 읽기 위해 필요하다. 새
     * {@code @DynamicPropertySource}를 만들지 않는 것과 같은 이유로 주입이다 — 시험이 자기 저장소
     * 인스턴스를 만들면 컨텍스트가 갈라지고 {@code backend-quality}의 wall-clock이 늘어난다.
     */
    @Autowired
    private SessionRepository<? extends Session> sessions;

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * 저장소를 비운다.
     *
     * <p>이 시험들은 "행이 몇 개인가"를 단정한다. 앞선 시험이 남긴 계정·세션이 있으면 그 단정이
     * 무엇을 세는지 알 수 없게 되므로, 새 UUID로 충돌만 피하는 대신 실제로 비운다. 컨테이너는 JVM
     * 하나에 하나뿐이다. 시험 메서드는 순차 실행이어도 scheduler·virtual worker는 살아 있으므로,
     * 초기화는 전체 테이블 잠금을 NOWAIT로 확보한 트랜잭션에서만 한다.
     */
    @BeforeEach
    void resetStore() {
        // 긴 빌드 도중 Docker VM 시계가 밀렸으면 이 시험의 엉뚱한 단정 대신 그 사실로 실패한다(Epic 4 회고 AI-6).
        PostgresTestcontainersConfig.verifyClock();
        PostgresStoreReset.run(this.jdbc, this.storeResetTransactions, this::truncateStore);
    }

    private void truncateStore() {
        // 완료 확인 전용 세션(Story 5.4)은 세션 행을 외래키로 가리키므로 함께 적는다.
        this.jdbc.sql("TRUNCATE TABLE spring_session, spring_session_attributes, identity_deletion_completion_session")
                .update();
        // `action_grant`를 이름으로 적는다. `identity_account`를 참조하므로 CASCADE가 어차피
        // 비우지만, 이름이 없으면 그 정리가 외래키 설정에 딸린 부수 효과로만 성립한다.
        //
        // Story 2.1의 다섯 테이블도 같은 이유로 이름으로 적는다. `source_*`·`portfolio*`는
        // `identity_account`를 `ON DELETE RESTRICT`로 가리키므로 이름이 없으면 TRUNCATE 자체가
        // 실패하고, 그 실패는 "시험이 서로를 오염시킨다"가 아니라 "초기화가 깨졌다"로 보인다.
        this.jdbc
                .sql(
                        "TRUNCATE TABLE portfolio_source_membership, portfolio, source_credential, "
                                + "source_connection, source_provider_account, action_grant, "
                                + "identity_social_link, identity_account, identity_account_lifecycle "
                                + "CASCADE")
                .update();
        // 운영 제한·강한 신호 기록·감사 원장은 어느 계정도 참조하지 않는다. 그래서 위 CASCADE가 비우지 못한다. 신호 행이
        // 제한을 외래키로 가리키므로 함께 적는다. 판정표(`operations_signal_policy`)는 migration seed라 비우지 않는다.
        // 공지(Story 3.5)도 어느 계정도 참조하지 않는다. 버전 행이 사건 행을 외래키로 가리키므로 함께 적는다.
        // 문의(Story 3.6)도 계정 표를 외래키로 가리키지 않는다. 영업일 달력(`support_business_calendar`·
        // `support_calendar_year`)은 migration seed라 비우지 않는다. 백업 기록(Story 3.9)은 사용자 식별자가 없는 순수 운영 데이터다.
        // 광고 동의 결정(Story 4.1)과 광고 자격 결정(Story 4.2)도 계정 표를 외래키로 가리키지 않는다. 광고 Vitals 표본(Story 4.5)은
        // 사용자 열이 없는 집계라 계정 정리로는 비지 않는다. tombstone의 영향 generation(Story 5.3)은 tombstone을 외래키로 가리키므로
        // 함께 적고, 내보내기 성공 기록은 어느 표도 가리키지 않는다.
        this.jdbc.sql("TRUNCATE TABLE operations_signal, operations_restriction, operations_audit_event, "
                + "operations_notice_version, operations_notice_incident, support_inquiry, operations_backup_event, "
                + "operations_deletion_backup_generation, operations_deletion_tombstone, operations_tombstone_export, "
                + "engagement_ad_consent_decision, engagement_ad_attempt, engagement_ad_vital_sample, "
                // 재가입 차단 digest(Story 5.4)도 어느 표도 가리키지 않는다(사용자 열이 없다).
                + "identity_deleted_identity")
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
        return send(
                "POST",
                AuthSecurityConfig.LOGOUT_PATH,
                sessionCookie,
                protectedHeaders(csrfToken, origin));
    }

    /** 계약을 모두 만족시키는 로그아웃. 정상 경로가 필요한 시험이 쓴다. */
    HttpResponse<String> logout(@Nullable String sessionCookie, @Nullable String csrfToken)
            throws IOException, InterruptedException {
        return logout(sessionCookie, csrfToken, OidcStubConfiguration.PUBLIC_BASE_URL);
    }

    /**
     * 재인증 시작 요청.
     *
     * <p>로그아웃과 같은 {@code /api/auth/} 상태 변경 계약을 지난다 — 세션 귀속 CSRF 토큰과 동일
     * 출처 {@code Origin}이다. 둘 중 하나를 비우거나 틀린 값으로 주는 것이 이 스토리의 거절
     * 시험이므로 인자로 받는다.
     *
     * @param csrfToken 헤더에 실을 값. {@code null}이면 헤더를 붙이지 않는다
     * @param origin {@code Origin} 헤더 값. {@code null}이면 헤더를 붙이지 않는다
     */
    HttpResponse<String> startReauthentication(
            String provider,
            @Nullable String sessionCookie,
            @Nullable String csrfToken,
            @Nullable String origin)
            throws IOException, InterruptedException {
        return send(
                "POST",
                AuthSecurityConfig.REAUTHENTICATION_BASE_URI + "/" + provider,
                sessionCookie,
                protectedHeaders(csrfToken, origin));
    }

    /** 계약을 모두 만족시키는 재인증 시작. 정상 경로가 필요한 시험이 쓴다. */
    HttpResponse<String> startReauthentication(
            String provider, @Nullable String sessionCookie, @Nullable String csrfToken)
            throws IOException, InterruptedException {
        return startReauthentication(
                provider, sessionCookie, csrfToken, OidcStubConfiguration.PUBLIC_BASE_URL);
    }

    /**
     * 재인증 왕복 하나. 무장 → 인가 시작 → 검증된 콜백까지 간다.
     *
     * <p>무장이 204가 아니면 그 자리에서 멈춘다. 조용히 이어 가면 뒤따르는 단정이 "재인증이
     * 실패했다"를 보면서 통과하는데, 실제 원인은 흐름이 시작조차 하지 않은 것이다.
     *
     * <p>토큰 계획을 시험이 정한다. 정상 계획에서 어긋나게 하려는 축 하나만 바꾸는 것이
     * {@code TokenPlan}의 관용이며, 재인증에서 그 축은 {@code subject}와 {@code auth_time}이다.
     *
     * @param plan 정상 계획을 받아 이 시험이 필요한 모양으로 바꾼다
     */
    HttpResponse<String> reauthenticate(
            String provider,
            String sessionCookie,
            @Nullable String csrfToken,
            UnaryOperator<TokenPlan> plan)
            throws IOException, InterruptedException {
        HttpResponse<String> armed = startReauthentication(provider, sessionCookie, csrfToken);
        if (armed.statusCode() != 204) {
            throw new IllegalStateException(
                    "재인증 무장이 204가 아니다: " + armed.statusCode() + " " + armed.body());
        }
        Authorization authorization = startAuthorization(provider, null, sessionCookie);
        String code = issueCode(authorization, plan.apply(TokenPlan.valid(authorization.nonce())));
        return callback(authorization, sessionCookie, code, authorization.state());
    }

    /**
     * 연결 상태 조회.
     *
     * <p>안전한 메서드라 CSRF·{@code Origin}을 요구하지 않는다. 주체가 없으면 공통 오류 계약의
     * 403으로 끝나며 그 판정은 세션 하나가 한다.
     */
    HttpResponse<String> linkStatus(@Nullable String sessionCookie)
            throws IOException, InterruptedException {
        return get(AuthSecurityConfig.LINK_BASE_URI, sessionCookie);
    }

    /**
     * 연결 시작 요청.
     *
     * <p>로그아웃·재인증 시작과 같은 {@code /api/auth/} 상태 변경 계약을 지난다 — 세션 귀속 CSRF
     * 토큰과 동일 출처 {@code Origin}이다. 둘 중 하나를 비우거나 틀린 값으로 주는 것이 이
     * 스토리의 거절 시험이므로 인자로 받는다.
     */
    HttpResponse<String> startLink(
            String provider,
            @Nullable String sessionCookie,
            @Nullable String csrfToken,
            @Nullable String origin)
            throws IOException, InterruptedException {
        return send(
                "POST",
                AuthSecurityConfig.LINK_BASE_URI + "/" + provider,
                sessionCookie,
                protectedHeaders(csrfToken, origin));
    }

    /** 계약을 모두 만족시키는 연결 시작. 정상 경로가 필요한 시험이 쓴다. */
    HttpResponse<String> startLink(
            String provider, @Nullable String sessionCookie, @Nullable String csrfToken)
            throws IOException, InterruptedException {
        return startLink(provider, sessionCookie, csrfToken, OidcStubConfiguration.PUBLIC_BASE_URL);
    }

    /**
     * 연결 왕복 하나. 무장 → 인가 시작 → 검증된 콜백까지 간다.
     *
     * <p>무장이 204가 아니면 그 자리에서 멈춘다({@link #reauthenticate}와 같은 근거). 조용히 이어
     * 가면 뒤따르는 단정이 "연결이 실패했다"를 보면서 통과하는데, 실제 원인은 흐름이 시작조차
     * 하지 않은 것이다.
     *
     * @param subject 대상 제공자가 줄 외부 식별자. 연결의 판정이 보는 값이다
     */
    HttpResponse<String> link(
            String provider,
            String sessionCookie,
            @Nullable String csrfToken,
            String subject)
            throws IOException, InterruptedException {
        HttpResponse<String> armed = startLink(provider, sessionCookie, csrfToken);
        if (armed.statusCode() != 204) {
            throw new IllegalStateException(
                    "연결 무장이 204가 아니다: " + armed.statusCode() + " " + armed.body());
        }
        Authorization authorization = startAuthorization(provider, null, sessionCookie);
        String code =
                issueCode(authorization, TokenPlan.valid(authorization.nonce()).subject(subject));
        return callback(authorization, sessionCookie, code, authorization.state());
    }

    /**
     * 해제 준비 요청. 확인 대화상자를 여는 자리이며 <b>아무것도 바꾸지 않는다.</b>
     *
     * <p>로그아웃·재인증 시작·연결 시작과 같은 {@code /api/auth/} 상태 변경 계약을 지난다 — 세션
     * 귀속 CSRF 토큰과 동일 출처 {@code Origin}이다. 둘 중 하나를 비우거나 틀린 값으로 주는 것이
     * 이 스토리의 거절 시험이므로 인자로 받는다.
     */
    HttpResponse<String> prepareUnlink(
            String provider,
            @Nullable String sessionCookie,
            @Nullable String csrfToken,
            @Nullable String origin)
            throws IOException, InterruptedException {
        return send(
                "POST",
                AuthSecurityConfig.UNLINK_BASE_URI + "/" + provider,
                sessionCookie,
                protectedHeaders(csrfToken, origin));
    }

    /** 계약을 모두 만족시키는 해제 준비. 정상 경로가 필요한 시험이 쓴다. */
    HttpResponse<String> prepareUnlink(
            String provider, @Nullable String sessionCookie, @Nullable String csrfToken)
            throws IOException, InterruptedException {
        return prepareUnlink(
                provider, sessionCookie, csrfToken, OidcStubConfiguration.PUBLIC_BASE_URL);
    }

    /** 해제 최종 확인 요청. <b>여기서만 상태가 바뀐다.</b> */
    HttpResponse<String> confirmUnlink(
            String provider,
            @Nullable String sessionCookie,
            @Nullable String csrfToken,
            @Nullable String origin)
            throws IOException, InterruptedException {
        return send(
                "POST",
                AuthSecurityConfig.UNLINK_BASE_URI
                        + "/"
                        + provider
                        + "/"
                        + AuthSecurityConfig.UNLINK_CONFIRM_SEGMENT,
                sessionCookie,
                protectedHeaders(csrfToken, origin));
    }

    /** 계약을 모두 만족시키는 해제 최종 확인. */
    HttpResponse<String> confirmUnlink(
            String provider, @Nullable String sessionCookie, @Nullable String csrfToken)
            throws IOException, InterruptedException {
        return confirmUnlink(
                provider, sessionCookie, csrfToken, OidcStubConfiguration.PUBLIC_BASE_URL);
    }

    /**
     * 해제 왕복 하나. 준비 → 최종 확인까지 간다.
     *
     * <p>준비가 204가 아니면 그 자리에서 멈춘다({@link #link}·{@link #reauthenticate}와 같은 근거).
     * 조용히 이어 가면 뒤따르는 단정이 "해제가 실패했다"를 보면서 통과하는데, 실제 원인은 흐름이
     * 시작조차 하지 않은 것이다.
     */
    HttpResponse<String> unlink(
            String provider, String sessionCookie, @Nullable String csrfToken)
            throws IOException, InterruptedException {
        HttpResponse<String> armed = prepareUnlink(provider, sessionCookie, csrfToken);
        if (armed.statusCode() != 204) {
            throw new IllegalStateException(
                    "해제 준비가 204가 아니다: " + armed.statusCode() + " " + armed.body());
        }
        return confirmUnlink(provider, sessionCookie, csrfToken);
    }

    /** 해제 취소 요청. 준비가 만든 무장과 허가를 되돌린다. */
    HttpResponse<String> cancelUnlink(
            String provider,
            @Nullable String sessionCookie,
            @Nullable String csrfToken,
            @Nullable String origin)
            throws IOException, InterruptedException {
        return send(
                "POST",
                AuthSecurityConfig.UNLINK_BASE_URI
                        + "/"
                        + provider
                        + "/"
                        + AuthSecurityConfig.UNLINK_CANCEL_SEGMENT,
                sessionCookie,
                protectedHeaders(csrfToken, origin));
    }

    /** 계약을 모두 만족시키는 해제 취소. */
    HttpResponse<String> cancelUnlink(
            String provider, @Nullable String sessionCookie, @Nullable String csrfToken)
            throws IOException, InterruptedException {
        return cancelUnlink(provider, sessionCookie, csrfToken, OidcStubConfiguration.PUBLIC_BASE_URL);
    }

    /**
     * {@code /api/auth/} 상태 변경이 요구하는 헤더 둘. {@code null}인 값은 붙이지 않는다.
     *
     * <p>이 파일의 상태 변경 헬퍼 다섯이 모두 이것을 지난다 — 로그아웃·재인증 시작·연결 시작·해제
     * 준비·해제 확인·해제 취소다. 조립을 각자 인라인으로 두면 "토큰을 비운다"·"다른 origin을
     * 보낸다"는 거절 축이 헬퍼마다 조금씩 다른 모양이 되고, 그 차이는 시험이 무엇을 보는지 읽는
     * 사람이 매번 확인해야 한다.
     */
    private static String[] protectedHeaders(
            @Nullable String csrfToken, @Nullable String origin) {
        List<String> headers = new ArrayList<>();
        if (origin != null) {
            headers.add("Origin");
            headers.add(origin);
        }
        if (csrfToken != null) {
            headers.add(AuthSecurityConfig.CSRF_HEADER_NAME);
            headers.add(csrfToken);
        }
        return headers.toArray(new String[0]);
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

    /**
     * 본문이 있는 요청 하나.
     *
     * <p>{@link #send}와 갈라 두는 이유는 자격정보다. Bybit 연결의 최종 확인만 본문을 가지며, 그
     * 본문에 Secret이 실린다 — 쿼리 문자열로 보내는 오버로드를 만들지 않는 것이 이 분리의 뜻이다.
     */
    HttpResponse<String> sendJson(
            String method, String path, String body, @Nullable String sessionCookie, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + this.port + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sessionCookie != null) {
            builder.header("Cookie", SESSION_COOKIE_NAME + "=" + sessionCookie);
        }
        for (int index = 0; index + 1 < headers.length; index += 2) {
            builder.header(headers[index], headers[index + 1]);
        }
        return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ----------------------------------------------------------------------
    // Bybit 연결(Story 2.1)
    // ----------------------------------------------------------------------

    /**
     * 그 사용자의 활성 연결을 실제 해제 종단 그대로 끝낸다(Story 2.16) — 자격정보 삭제, {@code REVOKED}와 단조 해제
     * 시각·멱등 키·요청 hash, membership 종료, 진행 중 작업 종단을 한 트랜잭션에서. 제품 코드의 세 모듈 API를 그대로
     * 부르되 HTTP·허가·계획 잠금·생명주기 잠금은 거치지 않는다: 계획 잠금을 쥔 채 "그 사이 연결이 해제됐다"를 만드는
     * 시험이 쓴다.
     */
    void revokeConnectionOf(UUID userId) {
        new TransactionTemplate(this.storeResetTransactions).executeWithoutResult(status -> {
            UUID connection = this.jdbc.sql("SELECT connection_id FROM source_connection WHERE user_id=:u "
                    + "AND status='ACTIVE'").param("u", userId).query(UUID.class).single();
            var revocation = this.sources.revoke(userId, connection, "test-revocation-" + UUID.randomUUID(),
                    BybitRevocationCommandService.requestHashOf(connection),
                    this.portfolios.activeMembershipStart(userId, connection).orElse(null));
            var revoked = (SourceConnectionApi.Revocation.Revoked) revocation;
            this.portfolios.endSourceMembership(userId, connection, revoked.revokedAt());
            this.syncJobs.endConnectionWork(userId, connection, revoked.revokedAt());
        });
    }


    /** 최종 확인 경로. 준비와 갈라 두는 것이 "확인 전에는 아무것도 바뀌지 않는다"의 짝이다. */
    static final String BYBIT_CONNECT_PATH =
            AuthSecurityConfig.BYBIT_CONNECTION_PATH + "/" + AuthSecurityConfig.CONNECT_SEGMENT;

    /** 취소 경로. 준비가 만든 무장과 허가를 되돌린다. */
    static final String BYBIT_CANCEL_PATH =
            AuthSecurityConfig.BYBIT_CONNECTION_PATH + "/" + AuthSecurityConfig.CANCEL_SEGMENT;

    /** 연결 상태 조회. 안전한 메서드라 CSRF·{@code Origin}을 요구하지 않는다. */
    HttpResponse<String> bybitConnectionState(@Nullable String sessionCookie)
            throws IOException, InterruptedException {
        return get(AuthSecurityConfig.BYBIT_CONNECTION_PATH, sessionCookie);
    }

    /**
     * 연결 준비 요청. 자격정보 입력 폼을 열 자리이며 <b>아무것도 바꾸지 않는다.</b>
     *
     * <p>{@code /api/connections/} 아래이므로 {@code /api/auth/}와 같은 상태 변경 계약을 지난다 —
     * 세션 귀속 CSRF 토큰과 동일 출처 {@code Origin}. 둘 중 하나를 비우거나 틀린 값으로 주는 것이
     * 이 스토리의 거절 축이므로 인자로 받는다.
     */
    HttpResponse<String> prepareBybitConnection(
            @Nullable String sessionCookie, @Nullable String csrfToken, @Nullable String origin)
            throws IOException, InterruptedException {
        return send(
                "POST",
                AuthSecurityConfig.BYBIT_CONNECTION_PATH,
                sessionCookie,
                protectedHeaders(csrfToken, origin));
    }

    /** 계약을 모두 만족시키는 연결 준비. 정상 경로가 필요한 시험이 쓴다. */
    HttpResponse<String> prepareBybitConnection(
            @Nullable String sessionCookie, @Nullable String csrfToken)
            throws IOException, InterruptedException {
        return prepareBybitConnection(sessionCookie, csrfToken, OidcStubConfiguration.PUBLIC_BASE_URL);
    }

    /** 준비 응답에서 확인 문구만 꺼낸다. 화면이 렌더하고 최종 확인이 되보낼 값이다. */
    static String confirmationIn(String body) {
        Object value = topLevelFieldsOf(body).get("confirmation");
        return value instanceof String text ? text : "";
    }

    /** 최종 확인 요청. <b>여기서만 제공자로 나가고 여기서만 상태가 바뀐다.</b> */
    HttpResponse<String> connectBybit(
            @Nullable String sessionCookie,
            @Nullable String csrfToken,
            @Nullable String origin,
            @Nullable String apiKey,
            @Nullable String apiSecret,
            @Nullable String confirmation)
            throws IOException, InterruptedException {
        return sendJson(
                "POST",
                BYBIT_CONNECT_PATH,
                connectBody(apiKey, apiSecret, confirmation),
                sessionCookie,
                protectedHeaders(csrfToken, origin));
    }

    /** 계약을 모두 만족시키는 최종 확인. */
    HttpResponse<String> connectBybit(
            @Nullable String sessionCookie,
            @Nullable String csrfToken,
            @Nullable String apiKey,
            @Nullable String apiSecret,
            @Nullable String confirmation)
            throws IOException, InterruptedException {
        return connectBybit(
                sessionCookie,
                csrfToken,
                OidcStubConfiguration.PUBLIC_BASE_URL,
                apiKey,
                apiSecret,
                confirmation);
    }

    /** 연결 취소 요청. 준비가 만든 무장과 허가를 되돌린다. */
    HttpResponse<String> cancelBybitConnection(
            @Nullable String sessionCookie, @Nullable String csrfToken)
            throws IOException, InterruptedException {
        return send(
                "POST",
                BYBIT_CANCEL_PATH,
                sessionCookie,
                protectedHeaders(csrfToken, OidcStubConfiguration.PUBLIC_BASE_URL));
    }

    /**
     * 준비 → 최종 확인 왕복 하나.
     *
     * <p>준비가 200이 아니면 그 자리에서 멈춘다({@code link}·{@code unlink}와 같은 근거). 조용히
     * 이어 가면 뒤따르는 단정이 "연결이 실패했다"를 보면서 통과하는데, 실제 원인은 흐름이 시작조차
     * 하지 않은 것이다.
     */
    HttpResponse<String> connectBybitRoundTrip(
            String sessionCookie, @Nullable String csrfToken, String apiKey, String apiSecret)
            throws IOException, InterruptedException {
        HttpResponse<String> prepared = prepareBybitConnection(sessionCookie, csrfToken);
        if (prepared.statusCode() != 200) {
            throw new IllegalStateException(
                    "연결 준비가 200이 아니다: " + prepared.statusCode() + " " + prepared.body());
        }
        return connectBybit(
                sessionCookie, csrfToken, apiKey, apiSecret, confirmationIn(prepared.body()));
    }

    /**
     * 최종 확인의 본문.
     *
     * <p>{@code null}인 필드는 아예 싣지 않는다. 그래야 "필드가 없다"와 "빈 값이다"를 시험이 갈라
     * 볼 수 있다.
     */
    private static String connectBody(
            @Nullable String apiKey, @Nullable String apiSecret, @Nullable String confirmation) {
        List<String> fields = new ArrayList<>();
        if (apiKey != null) {
            fields.add("\"apiKey\":\"" + escapeJson(apiKey) + "\"");
        }
        if (apiSecret != null) {
            fields.add("\"apiSecret\":\"" + escapeJson(apiSecret) + "\"");
        }
        if (confirmation != null) {
            fields.add("\"confirmation\":\"" + escapeJson(confirmation) + "\"");
        }
        return "{" + String.join(",", fields) + "}";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 운영 제한 하나를 건다(Story 3.2의 {@code 기능 × scope × target}). 시험이 직접 행을 넣는다 — 운영자 command의 준비·확정
     * 왕복은 {@code OperatorRestrictionTest}가 본다. 효력은 이 문장의 DB 시각부터다.
     *
     * @return 그 제한의 ID. {@link #lift}로 이 행만 지운다
     */
    UUID restrict(RestrictionFeature feature, RestrictionScope scope, String target) {
        UUID id = UUID.randomUUID();
        this.jdbc
                .sql("INSERT INTO operations_restriction (restriction_id, feature, scope, target, effective_from) "
                        + "VALUES (:id, :feature, :scope, :target, statement_timestamp())")
                .param("id", id)
                .param("feature", feature.name())
                .param("scope", scope.name())
                .param("target", target)
                .update();
        return id;
    }

    /** 그 기능 전체를 막는 전역 제한. */
    UUID restrictGlobally(RestrictionFeature feature) {
        return restrict(feature, RestrictionScope.GLOBAL, RestrictionScope.GLOBAL_TARGET);
    }

    /** 시험이 건 제한 하나를 지운다. 해제 경로(Story 3.4)가 아니라 시험 정리다. 다른 행은 건드리지 않는다. */
    void lift(UUID restrictionId) {
        this.jdbc.sql("DELETE FROM operations_restriction WHERE restriction_id = :id").param("id", restrictionId).update();
    }

    long sourceConnectionCount() {
        return count("SELECT count(*) FROM source_connection");
    }

    long sourceCredentialCount() {
        return count("SELECT count(*) FROM source_credential");
    }

    long providerAccountCount() {
        return count("SELECT count(*) FROM source_provider_account");
    }

    long portfolioCount() {
        return count("SELECT count(*) FROM portfolio");
    }

    long membershipCount() {
        return count("SELECT count(*) FROM portfolio_source_membership");
    }

    /**
     * 한 사용자의 연결 행 수.
     *
     * <p>전체 개수만 세면 "다른 사용자의 연결이 보존됐다"를 확인할 수 없다
     * ({@link #sessionCountOf}와 같은 근거).
     */
    long sourceConnectionCountOf(UUID userId) {
        return this.jdbc
                .sql("SELECT count(*) FROM source_connection WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    long portfolioCountOf(UUID userId) {
        return this.jdbc
                .sql("SELECT count(*) FROM portfolio WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /** 그 사용자의 안정 식별자. 자격정보를 교체해 다시 연결해도 같은 값이어야 한다. */
    Optional<UUID> providerAccountIdOf(UUID userId) {
        return this.jdbc
                .sql("SELECT provider_account_id FROM source_provider_account WHERE user_id = :userId")
                .param("userId", userId)
                .query(UUID.class)
                .optional();
    }

    /** 저장된 자격정보 행 하나. 평문 조각이 없는지, nonce가 행마다 다른지 보는 창이다. */
    record StoredCredential(String keyVersion, String algorithmVersion, byte[] nonce, byte[] ciphertext) {}

    List<StoredCredential> storedCredentials() {
        return this.jdbc
                .sql(
                        "SELECT key_version, algorithm_version, nonce, ciphertext FROM source_credential "
                                + "ORDER BY created_at")
                .query(
                        (rs, rowNum) ->
                                new StoredCredential(
                                        rs.getString("key_version"),
                                        rs.getString("algorithm_version"),
                                        rs.getBytes("nonce"),
                                        rs.getBytes("ciphertext")))
                .list();
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

    /** 연결의 판정과 실행. HTTP 없이 멱등 재전송을 관측하는 시험이 쓴다. */
    LinkService linkService() {
        return this.linkService;
    }

    /** 해제의 판정과 실행. HTTP 없이 멱등 재전송을 관측하는 시험이 쓴다. */
    UnlinkService unlinkService() {
        return this.unlinkService;
    }

    /**
     * 세션에 기록된 재인증 시각을 과거로 옮긴다.
     *
     * <p>시험이 5분을 기다릴 수는 없고, 창을 줄이는 설정을 따로 두면 그 설정이 검증하는 값과
     * 제품이 쓰는 값이 갈린다({@link #ageLastAccess}와 같은 근거). 신선도 판정은 데이터베이스가
     * 하지만 그 피연산자 하나는 세션에 사는 {@code Instant}이므로, 여기서는 그 속성을 더 이른
     * 시각으로 다시 쓴다.
     *
     * <p>기록이 없으면 소리 내어 실패한다. 조용히 지나가면 뒤따르는 단정이 "오래된 재인증"이
     * 아니라 "재인증이 아예 없는" 세션을 보며 통과한다 — 두 상태의 결과가 같아서 더 위험하다.
     *
     * @param sessionId {@link #sessionIdOf}로 되돌린 세션 id
     */
    void ageReauthentication(String sessionId, Duration elapsed) {
        ageReauthentication(this.sessions, sessionId, elapsed);
    }

    /** 와일드카드 저장소의 타입 매개변수를 한 번만 붙들어 둔다({@link #ageReauthenticationFlow}와 같다). */
    private static <S extends Session> void ageReauthentication(
            SessionRepository<S> repository, String sessionId, Duration elapsed) {
        S session = repository.findById(sessionId);
        if (session == null) {
            throw new IllegalStateException("세션이 없다: " + sessionId);
        }
        Object stored = session.getAttribute(ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE);
        if (!(stored instanceof Instant recorded)) {
            throw new IllegalStateException("재인증 기록이 없는 세션이다: " + sessionId);
        }
        session.setAttribute(
                ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE, recorded.minus(elapsed));
        repository.save(session);
    }

    /**
     * 세션 하나가 들고 있는 속성.
     *
     * <p>주입된 {@link SessionRepository}를 지난다. {@code ATTRIBUTE_BYTES}는 Java 직렬화라 SQL로
     * 읽을 수 없고, 저장소를 지나면 애플리케이션이 쓰는 것과 같은 변환을 그대로 쓴다. 그래서 이
     * 헬퍼가 보는 값은 제품 코드가 보는 값과 같다.
     *
     * @param sessionId {@link #sessionIdOf}로 되돌린 세션 id
     * @return 그 속성, 세션이나 속성이 없으면 {@code null}
     */
    @Nullable Object sessionAttribute(String sessionId, String name) {
        Session session = this.sessions.findById(sessionId);
        return session == null ? null : session.getAttribute(name);
    }

    /**
     * 세션 하나의 속성을 덮어쓴다.
     *
     * <p>시계 대신 저장소의 값을 옮기는 관용의 일반형이다({@link #ageLastAccess}와 같은 근거).
     * {@code ATTRIBUTE_BYTES}는 Java 직렬화라 SQL로 다룰 수 없으므로 저장소를 지나야 하고, 그래서
     * 여기서 쓰는 변환은 애플리케이션이 쓰는 것과 같다.
     *
     * @param sessionId {@link #sessionIdOf}로 되돌린 세션 id
     */
    void setSessionAttribute(String sessionId, String name, Object value) {
        setSessionAttribute(this.sessions, sessionId, name, value);
    }

    /** 와일드카드 저장소의 타입 매개변수를 한 번만 붙들어 둔다({@link #ageReauthenticationFlow}와 같다). */
    private static <S extends Session> void setSessionAttribute(
            SessionRepository<S> repository, String sessionId, String name, Object value) {
        S session = repository.findById(sessionId);
        if (session == null) {
            throw new IllegalStateException("세션이 없다: " + sessionId);
        }
        session.setAttribute(name, value);
        repository.save(session);
    }

    /**
     * 무장된 재인증 흐름의 무장 시각을 과거로 옮긴다.
     *
     * <p>시험이 5분을 기다릴 수는 없고, 수명을 줄이는 설정을 따로 두면 그 설정이 검증하는 값과
     * 제품이 쓰는 값이 갈린다({@link #ageLastAccess}와 같은 근거). 그래서 시계 대신 저장소의 값을
     * 옮긴다 — 무장은 세션 속성 하나이므로 그 record를 같은 값에 더 이른 시각으로 다시 쓴다.
     *
     * <p>무장이 없으면 소리 내어 실패한다. 조용히 지나가면 뒤따르는 단정이 "만료된 무장"이 아니라
     * "무장이 아예 없는" 세션을 보며 통과한다 — 두 상태의 결과가 다르다는 것이 이 시험의 요점이다.
     *
     * @param sessionId {@link #sessionIdOf}로 되돌린 세션 id
     */
    void ageReauthenticationFlow(String sessionId, Duration elapsed) {
        ageReauthenticationFlow(this.sessions, sessionId, elapsed);
    }

    /**
     * 와일드카드로 받은 저장소의 타입 매개변수를 한 번만 붙들어 둔다.
     *
     * <p>{@link SessionRepository#save}는 저장소가 만든 세션 타입을 요구한다. 주입 지점에서 그
     * 타입을 알 수 없어 {@code ? extends Session}으로 받으면 조회는 되지만 저장이 컴파일되지
     * 않는다. 여기서 capture 변환을 한 번 거치면 unchecked 캐스트가 남지 않는다
     * ({@code SessionGuard.SessionStore}와 같은 관용).
     */
    private static <S extends Session> void ageReauthenticationFlow(
            SessionRepository<S> repository, String sessionId, Duration elapsed) {
        S session = repository.findById(sessionId);
        if (session == null) {
            throw new IllegalStateException("세션이 없다: " + sessionId);
        }
        Object stored = session.getAttribute(ReauthenticationFlow.FLOW_ATTRIBUTE);
        if (!(stored instanceof ReauthenticationFlow.Armed armed)) {
            throw new IllegalStateException("무장되지 않은 세션이다: " + sessionId);
        }
        session.setAttribute(
                ReauthenticationFlow.FLOW_ATTRIBUTE,
                new ReauthenticationFlow.Armed(
                        armed.provider(),
                        armed.userId(),
                        armed.returnPath(),
                        armed.armedAt().minus(elapsed)));
        repository.save(session);
    }

    // ----------------------------------------------------------------------
    // 허가(action grant)
    // ----------------------------------------------------------------------

    /**
     * 허가 시험 셋이 공유하는 command·target·확인 문구.
     *
     * <p>세 파일에 같은 리터럴을 두면 한쪽만 바뀌는 날 "귀속이 어긋난 요청"과 "시험이 서로 다른
     * 값을 쓴다"가 같은 실패로 보인다. 값의 뜻은 이 포트가 해석하지 않으므로 무엇이어도 되지만,
     * 셋이 같아야 한다는 것은 계약이다.
     */
    static final String GRANT_COMMAND = "link-provider";

    /** 위와 한 쌍. */
    static final String GRANT_TARGET = "naver";

    /** 위와 한 쌍. */
    static final String GRANT_CONFIRMATION = "연결합니다";

    /** 방금 재인증을 마친, 이 사용자의 세션. */
    static MockHttpSession grantSession(UUID userId) {
        return grantSession(UUID.randomUUID().toString(), userId);
    }

    /** 그 세션 id를 가지고 방금 재인증을 마친, 이 사용자의 세션. */
    static MockHttpSession grantSession(String sessionId, UUID userId) {
        return grantSession(sessionId, userId, Instant.now(), Duration.ZERO);
    }

    /**
     * 허가 포트에 건네는 세션.
     *
     * <p>{@link MockHttpSession}인 이유는 그 포트에 HTTP 표면이 없어 요청 문맥이 없고, Spring
     * Session의 {@code HttpSession} 어댑터가 공개 타입이 아니기 때문이다. 세션 id를 실제 로그인이
     * 만든 값으로 주는 축에서는 그 값을 그대로 넣어 저장소의 사실과 맞춘다.
     *
     * <p>주체를 {@code AuthFlowHandlers}가 심는 것과 같은 모양으로 넣는다. 그 값이 없으면 포트가
     * "세션이 결정한 사용자"를 확인할 수 없어 거부하며, 그 자체가 하나의 시험 축이다.
     *
     * <p>생성 시각을 인자로 받는다. {@code MockHttpSession}의 기본 생성 시각은 언제나 "지금"이라
     * 세션 절대 수명 축이 한 번도 실행되지 않는다.
     *
     * @param userId 세션에 심을 주체. {@code null}이면 주체 없는 세션이다
     * @param reauthenticatedAt 세션에 기록할 재인증 시각. {@code null}이면 기록하지 않는다
     * @param createdAgo 생성 시각을 지금보다 이만큼 과거로 둔다
     */
    static MockHttpSession grantSession(
            String sessionId,
            @Nullable UUID userId,
            @Nullable Instant reauthenticatedAt,
            Duration createdAgo) {
        long created = System.currentTimeMillis() - createdAgo.toMillis();
        MockHttpSession session =
                new MockHttpSession(null, sessionId) {
                    @Override
                    public long getCreationTime() {
                        return created;
                    }
                };
        if (userId != null) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    new PreAuthenticatedAuthenticationToken(
                            userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }
        if (reauthenticatedAt != null) {
            session.setAttribute(ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE, reauthenticatedAt);
        }
        return session;
    }

    /**
     * 데이터베이스의 지금.
     *
     * <p>발급 창의 경계를 정확히 짚는 데 필요하다. 그 판정의 두 피연산자 중 하나는 {@code now()}
     * 이므로, 자바 시계로 좌표를 잡으면 JVM과 컨테이너의 시차만큼 경계가 흔들린다.
     */
    Instant databaseNow() {
        return this.jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
    }

    /** 허가 행 수. */
    long actionGrantCount() {
        return count("SELECT count(*) FROM action_grant");
    }

    /**
     * 한 사용자의 허가 행 수.
     *
     * <p>전체 개수만 세면 "다른 사용자의 허가가 보존됐다"를 확인할 수 없다
     * ({@link #sessionCountOf}와 같은 근거). 계정 payload 삭제가 그 계정의 허가만 지우는지 보는
     * 자리가 여기다.
     */
    long actionGrantCountOf(UUID userId) {
        return this.jdbc
                .sql("SELECT count(*) FROM action_grant WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /** 그 허가 행이 아직 있는가. */
    boolean actionGrantExists(String reference) {
        return this.jdbc
                .sql("SELECT count(*) FROM action_grant WHERE grant_id = :reference")
                .param("reference", reference)
                .query(Long.class)
                .single()
                == 1L;
    }

    /**
     * 그 허가가 소비됐는가.
     *
     * <p>허가를 <b>읽기만</b> 하는 경로다. 확인 화면 조회가 소비를 일으키지 않는다는 단정이 이
     * 헬퍼로 관측된다.
     *
     * @throws IllegalStateException 그 행이 없을 때. 조용히 {@code false}를 돌려주면 "지워진 허가"가
     *     "미소비 허가"로 보이고 뒤따르는 단정이 엉뚱한 이유로 통과한다
     */
    boolean actionGrantConsumed(String reference) {
        return this.jdbc
                .sql("SELECT consumed_at IS NOT NULL FROM action_grant WHERE grant_id = :reference")
                .param("reference", reference)
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("허가 행이 없다: " + reference));
    }

    /** 그 허가에 저장된 결과. 아직 없으면 빈 값이다. */
    Optional<String> actionGrantResult(String reference) {
        return this.jdbc
                .sql("SELECT result FROM action_grant WHERE grant_id = :reference AND result IS NOT NULL")
                .param("reference", reference)
                .query(String.class)
                .optional();
    }

    /**
     * 허가의 만료 시각을 지금 기준 상대 위치로 옮긴다.
     *
     * <p>시험이 5분을 기다릴 수는 없고, 수명을 줄이는 설정을 따로 두면 그 설정이 검증하는 값과
     * 제품이 쓰는 값이 갈린다({@link #ageLastAccess}와 같은 근거). 만료 판정이 데이터베이스에 있어
     * 그 seam이 공짜로 생긴 자리이며, 그래서 이 헬퍼도 자바 시각이 아니라 {@code now()}를 기준으로
     * 옮긴다 — JVM과 컨테이너의 시계가 어긋나도 경계 단정이 흔들리지 않는다.
     *
     * <p>필요하면 발급 시각도 함께 당긴다. 스키마가 {@code expires_at > issued_at}을 요구하므로
     * 만료를 과거로 옮기는 것만으로는 행이 제약을 깬다. 당기는 값은 새 만료보다 1초 앞이며, 발급
     * 시각 자체를 보는 시험은 아래 {@link #moveActionGrantIssuedAt}으로 다시 정한다.
     *
     * @param fromNow 음수면 과거, {@link Duration#ZERO}면 정확히 지금이다
     */
    void moveActionGrantExpiry(String reference, Duration fromNow) {
        requireSingleGrantRow(
                this.jdbc
                        .sql(
                                "UPDATE action_grant SET expires_at = "
                                        + "now() + make_interval(secs => CAST(:seconds AS double precision)), "
                                        + "issued_at = LEAST(issued_at, "
                                        + "now() + make_interval(secs => CAST(:seconds AS double precision)) "
                                        + "- interval '1 second') "
                                        + "WHERE grant_id = :reference")
                        .param("seconds", fromNow.toSeconds())
                        .param("reference", reference)
                        .update(),
                reference);
    }

    /**
     * 허가의 발급 시각을 지금 기준 상대 위치로 옮긴다. 만료 시각은 건드리지 않는다.
     *
     * <p>"아무도 {@code issued_at + 수명}을 다시 계산하지 않는다"를 관측하는 도구다. 서버 시각이
     * 뒤로 간 것처럼 발급 시각을 미래로 옮겨도 만료 판정은 바뀌지 않아야 한다.
     */
    void moveActionGrantIssuedAt(String reference, Duration fromNow) {
        requireSingleGrantRow(
                this.jdbc
                        .sql(
                                "UPDATE action_grant SET issued_at = "
                                        + "now() + make_interval(secs => CAST(:seconds AS double precision)) "
                                        + "WHERE grant_id = :reference")
                        .param("seconds", fromNow.toSeconds())
                        .param("reference", reference)
                        .update(),
                reference);
    }

    private static void requireSingleGrantRow(int updated, String reference) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "허가 하나를 옮기려 했는데 " + updated + "행이 바뀌었다: " + reference);
        }
    }

    /** 특정 인증 매핑에 연결된 계정. 재로그인이 같은 계정을 쓰는지 본다. */
    Optional<UUID> userIdOf(String issuer, String subject) {
        return this.accountLifecycle.findUserIdBySocialIdentity(issuer, subject);
    }

    AccountLifecycleApi accountLifecycle() {
        return this.accountLifecycle;
    }

    /** 금융 source 연결의 공개 API. 삭제 경로를 HTTP 없이 부르는 시험이 쓴다. */
    SourceConnectionApi sources() {
        return this.sources;
    }

    /** portfolio membership의 공개 API. 같은 근거로 삭제 경로를 부른다. */
    PortfolioMembershipApi portfolios() {
        return this.portfolios;
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
