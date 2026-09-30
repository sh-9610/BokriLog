package com.bokrilog.source.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceRejectionReason;
import com.bokrilog.source.SourceVerification;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.net.URLDecoder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bybit 어댑터의 전송 계층 계약과 허용 목록 구조(Story 2.1).
 *
 * <p>Spring 컨텍스트도 데이터베이스도 쓰지 않는다. 여기서 보는 축 셋은 공유 스텁으로 재현하기
 * 어렵거나 재현해서는 안 되는 것들이다 — 시간초과(스텁이 잠들면 CI가 그만큼 느려진다), 구성 부재,
 * 그리고 허용 목록이 비어 있는 동안에는 도달할 수 없는 판정 분기다.
 *
 * <p>HTTP 왕복의 성공 경로와 제공자 오류 매핑은 {@code BybitConnectionTest}가 공유 스텁으로 본다.
 * 같은 것을 두 층에서 보지 않는다.
 */
class BybitAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String RESERVED_IP = "203.0.113.10";
    private static final String SPECIAL_CURSOR = "page:/?&+%# value";

    private HttpServer server;

    private String origin;

    // Written by the server thread and read by the test thread, so every recorder is a synchronized list.
    private final List<String> paths = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    private final java.util.concurrent.atomic.AtomicInteger transactionCalls = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicLong walletEnvelopeTime = new AtomicLong();
    private final List<String> decodedCursors = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile boolean oversizedPage;
    /** Which malformed or failing market answer the handler gives (Story 2.6). Empty is the normal answer. */
    private volatile String marketMode = "";
    private final List<Boolean> signedRequests = java.util.Collections.synchronizedList(new ArrayList<>());

    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
    /** A signed path that answers with {@link #failure} instead of its normal answer (Story 2.11). */
    private volatile String failingPath = "";
    /**
     * {@code ratelimit} (429), {@code timeout} (slower than the client's timeout), {@code notjson}, and for the call
     * budget (Story 2.12) {@code server} (503), {@code unauthorized} (401), {@code forbidden} (403), {@code retcode10006}
     * (200 with a reset header 300 ms ahead) and {@code retcode10001}.
     */
    private volatile String failure = "";
    /** How many requests of the failing path fail; negative is every one. */
    private final java.util.concurrent.atomic.AtomicInteger failTimes = new java.util.concurrent.atomic.AtomicInteger(-1);
    /** Whether transaction-log answers carry a low remaining limit (10 %) with a reset 300 ms ahead. */
    private volatile boolean lowLimit;
    /** When each request arrived, in order, with its path. */
    private final List<long[]> arrivals = java.util.Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void startServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.origin = "http://127.0.0.1:" + this.server.getAddress().getPort();
        this.server.createContext("/", this::handle);
        this.server.setExecutor(null);
        this.server.start();
    }

    @AfterEach
    void stopServer() {
        this.server.stop(0);
    }

    @Test
    void firstEquityUsesProviderTimeAndExactDecimal() {
        BybitAdapter adapter=adapter(this.origin,RESERVED_IP,Duration.ofSeconds(1));
        var outcome=adapter.readEquity(credentials());
        assertThat(outcome).isInstanceOf(SourceConnectionApi.EquityResult.Observed.class);
        var value=((SourceConnectionApi.EquityResult.Observed)outcome).value();
        assertThat(value.decimal()).isEqualTo("10.00");
        assertThat(value.measuredAt()).isAfter(java.time.Instant.EPOCH);
        assertThat(value.measuredAt().toEpochMilli()).isEqualTo(walletEnvelopeTime.get());
        assertThat(this.paths).containsExactly(BybitAdapter.QUERY_API_PATH,
                BybitAdapter.ACCOUNT_INFO_PATH,BybitAdapter.WALLET_BALANCE_PATH);
    }

    @Test
    void 구성이_비면_외부_호출_없이_기능이_닫힌다() {
        BybitAdapter noBaseUrl = adapter(null, RESERVED_IP, Duration.ofSeconds(1));
        BybitAdapter noReservedIp = adapter(this.origin, null, Duration.ofSeconds(1));

        assertThat(noBaseUrl.isConfigured()).isFalse();
        assertThat(noReservedIp.isConfigured()).isFalse();
        assertThat(reasonOf(noBaseUrl.verify(credentials())))
                .isEqualTo(SourceRejectionReason.UNAVAILABLE);
        assertThat(reasonOf(noReservedIp.verify(credentials())))
                .isEqualTo(SourceRejectionReason.UNAVAILABLE);
        assertThat(this.paths).isEmpty();
    }

    @Test
    void 비어_있거나_지나치게_긴_자격정보는_외부_호출_없이_거절된다() {
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1));

        assertThat(reasonOf(adapter.verify(new SourceCredentials("  ", "secret"))))
                .isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(reasonOf(adapter.verify(new SourceCredentials("key", ""))))
                .isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(reasonOf(adapter.verify(new SourceCredentials("k".repeat(257), "secret"))))
                .isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(this.paths).isEmpty();
    }

    @Test
    void 헤더에_실을_수_없는_API_Key는_외부_호출_없이_거절된다() {
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1));

        /*
         * CR·LF·제어문자·비-ASCII가 그대로 헤더에 닿으면 `HttpRequest.Builder.header`가
         * `IllegalArgumentException`을 던지고, 그 예외는 이 클래스의 거절 값이 아니라 일반 500으로
         * 새어 나간다 — 사용자는 "형식이 올바르지 않다" 대신 "서버가 고장났다"를 본다. 그리고
         * CR·LF를 통과시키는 것은 그 자체로 헤더 주입이다.
         */
        for (String hostile : List.of("uta2.1\r\nX-Injected: 1", "uta2.1\n", "uta2.한글", "uta2.1\u0000")) {
            assertThat(reasonOf(adapter.verify(new SourceCredentials(hostile, "secret"))))
                    .withFailMessage("거절되지 않았다: %s", hostile.replace("\r", "\\r").replace("\n", "\\n"))
                    .isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        }
        assertThat(this.paths).isEmpty();
    }

    @Test
    void URI로_읽히지_않는_base_URL은_기능을_닫는다() {
        /*
         * 스킴 접두사만 보면 공백이 섞인 값이 통과하고, 그때 `URI.create`가 호출 자리에서 던진다 —
         * 구성 오류가 fail-closed가 아니라 요청마다의 500이 된다.
         */
        BybitAdapter adapter = adapter("http://127.0.0.1:1 /bybit", RESERVED_IP, Duration.ofSeconds(1));

        assertThat(adapter.isConfigured()).isFalse();
        assertThat(reasonOf(adapter.verify(credentials()))).isEqualTo(SourceRejectionReason.UNAVAILABLE);
        assertThat(this.paths).isEmpty();
    }

    @Test
    void 읽을_수_없는_상한은_기본값으로_접히고_기동을_죽이지_않는다() {
        /*
         * Boot의 `${이름:기본값}`은 property가 해석되지 않을 때만 기본값을 쓴다. 빈 문자열로 설정된
         * 환경 변수는 해석되므로 `Duration` 바인딩에 그대로 닿고, 그 실패는 컨텍스트 생성 실패다 —
         * "값이 없으면 기능만 닫히고 기동은 산다"와 정면으로 부딪힌다.
         */
        for (String unreadable : List.of("", "   ", "나중에")) {
            assertThat(new SourceProperties.Bybit(null, null, unreadable, null).requestTimeoutOrDefault())
                    .isEqualTo(SourceProperties.Bybit.DEFAULT_REQUEST_TIMEOUT);
        }
        // 0 이하도 같다. 상한이 없다는 뜻이 되어 무한정 기다리는 경로가 열린다.
        assertThat(new SourceProperties.Bybit(null, null, "0s", null).requestTimeoutOrDefault())
                .isEqualTo(SourceProperties.Bybit.DEFAULT_REQUEST_TIMEOUT);
        assertThat(new SourceProperties.Bybit(null, null, "-1s", null).requestTimeoutOrDefault())
                .isEqualTo(SourceProperties.Bybit.DEFAULT_REQUEST_TIMEOUT);
        // 읽히는 값은 그대로 쓴다.
        assertThat(new SourceProperties.Bybit(null, null, "250ms", null).requestTimeoutOrDefault())
                .isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void 시간초과는_구분된_거절이고_재시도하지_않는다() {
        this.delay.set(Duration.ofMillis(800));
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofMillis(150));

        assertThat(reasonOf(adapter.verify(credentials()))).isEqualTo(SourceRejectionReason.TIMEOUT);
        // 한 번만 보냈다. `ERD-1.0.0` §2.5의 3회 backoff는 sync 수집의 계약이며 admission에는
        // 적용하지 않는다(AC-5·AD-21).
        assertThat(this.paths).hasSize(1);
    }

    @Test
    void 앞_호출이_거절하면_뒤_호출을_보내지_않는다() {
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1));

        // 서브 UID다. 첫 호출에서 걸리므로 계정 모드·지갑은 묻지 않는다.
        assertThat(reasonOf(adapter.verify(new SourceCredentials("sub.1", "secret"))))
                .isEqualTo(SourceRejectionReason.SUB_ACCOUNT);
        assertThat(this.paths).containsExactly(BybitAdapter.QUERY_API_PATH);
    }

    @Test
    void 세_호출을_순서대로_한_번씩_보낸다() {
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1));

        SourceVerification verification = adapter.verify(credentials());

        assertThat(verification).isInstanceOf(SourceVerification.Verified.class);
        assertThat(this.paths)
                .containsExactly(
                        BybitAdapter.QUERY_API_PATH,
                        BybitAdapter.ACCOUNT_INFO_PATH,
                        BybitAdapter.WALLET_BALANCE_PATH);
        SourceVerification.Verified verified = (SourceVerification.Verified) verification;
        assertThat(verified.providerAccountRef()).isEqualTo("1");
        assertThat(verified.policyVersion()).isEqualTo(BybitAdapter.POLICY_VERSION);
    }

    @Test
    void initialCollectionFixesServerTimeAndReadsBothTypesAcrossSevenDayWindowsAndEveryCursor() {
        BybitAdapter adapter=adapter(this.origin,RESERVED_IP,Duration.ofSeconds(1));
        long tcMillis=System.currentTimeMillis();
        var t0=java.time.Instant.ofEpochMilli(tcMillis-8L*24*60*60*1000);
        List<SourceConnectionApi.CollectionPage> pages=new ArrayList<>();
        var outcome=adapter.collectInitialInputs(credentials(),"1",t0,null,ignored->{},pages::add,()->true);

        assertThat(outcome).isInstanceOf(SourceConnectionApi.CollectionResult.Collected.class);
        var value=((SourceConnectionApi.CollectionResult.Collected)outcome).value();
        assertThat(value.tc()).isAfter(t0);
        assertThat(value.equityDecimal()).isEqualTo("10.00");
        assertThat(value.measuredAt().toEpochMilli()).isEqualTo(walletEnvelopeTime.get());
        assertThat(value.pageCount()).isEqualTo(5);
        assertThat(pages).hasSize(5);
        assertThat(this.paths.getFirst()).isEqualTo(BybitAdapter.SERVER_TIME_PATH);
        assertThat(this.paths).filteredOn(BybitAdapter.TRANSACTION_LOG_PATH::equals).hasSize(5);
        assertThat(pages.stream().map(p->p.evidence().type()).toList())
                .contains("TRANSFER_IN","TRANSFER_OUT");
        assertThat(pages).allSatisfy(page->{
            assertThat(java.time.Duration.between(page.evidence().startAt(),page.evidence().endAt()).toDays())
                    .isLessThanOrEqualTo(7);
            assertThat(page.evidence().cursorHash()).hasSize(64);
        });
        assertThat(this.requests).anyMatch(url->url.contains("accountType=UNIFIED")
                &&url.contains("type=TRANSFER_IN")&&url.contains("limit=50"));
        assertThat(this.requests).anyMatch(url->url.contains("type=TRANSFER_OUT"));
        assertThat(this.decodedCursors).containsExactly(SPECIAL_CURSOR);
        assertThat(this.requests).anyMatch(url->url.contains("cursor=page%3A%2F%3F%26%2B%25%23+value"));
        assertThat(pages.getFirst().events()).singleElement().satisfies(event->{
            assertThat(event.sourceId()).isEqualTo("event-1");
            assertThat(event.accountType()).isEqualTo("UNIFIED");
        });
    }

    /**
     * The window collection (Stories 2.11 and 2.12) never asks the time — its {@code tc} was fixed by the caller — and
     * reads exactly the windows it is given: from {@code from}, contiguous and at most seven days, up to that
     * {@code tc}; events are still judged against {@code (t0, tc]}. A window that starts before {@code t0}, is empty or
     * ends past {@code tc} is refused before any call. (Story 2.12 removed the incremental wrapper that had no caller.)
     */
    @Test
    void windowCollectionReadsItsWindowsFromFromAndNeverAsksTheTime() {
        BybitAdapter adapter=adapter(this.origin,RESERVED_IP,Duration.ofSeconds(1));
        var tc=java.time.Instant.ofEpochMilli(System.currentTimeMillis());
        var t0=tc.minus(Duration.ofDays(20));
        var from=tc.minus(Duration.ofDays(8));
        List<SourceConnectionApi.CollectionPage> pages=new ArrayList<>();
        var outcome=adapter.collectWindows(credentials(),"1",t0,tc,BybitAdapter.contiguousWindows(from,tc),true,
                pages::add,()->true);

        assertThat(outcome).isInstanceOf(SourceConnectionApi.WindowCollectionResult.Collected.class);
        var value=((SourceConnectionApi.WindowCollectionResult.Collected)outcome).value();
        assertThat(value.tc()).isEqualTo(tc);
        assertThat(this.paths).doesNotContain(BybitAdapter.SERVER_TIME_PATH);
        assertThat(this.paths.getFirst()).isEqualTo(BybitAdapter.QUERY_API_PATH);
        assertThat(pages.getFirst().evidence().startAt()).isEqualTo(from);
        var starts=pages.stream().map(p->p.evidence().startAt()).distinct().sorted().toList();
        assertThat(starts).containsExactly(from,from.plus(Duration.ofDays(7)));
        assertThat(pages).allSatisfy(page->assertThat(Duration.between(page.evidence().startAt(),
                page.evidence().endAt())).isLessThanOrEqualTo(Duration.ofDays(7)));
        assertThat(pages.stream().map(p->p.evidence().endAt()).max(java.util.Comparator.naturalOrder()))
                .contains(tc);
        assertThat(this.requests).filteredOn(url->url.contains("transaction-log"))
                .allMatch(url->!url.contains("startTime="+t0.toEpochMilli()));

        this.paths.clear();
        assertThat(reasonOfWindows(adapter.collectWindows(credentials(),"1",t0,tc,
                List.of(new SourceConnectionApi.CollectionWindow(t0.minusMillis(1),tc.minus(Duration.ofDays(14)))),true,
                pages::add,()->true))).as("before t0").isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(reasonOfWindows(adapter.collectWindows(credentials(),"1",t0,tc,
                List.of(new SourceConnectionApi.CollectionWindow(tc.minusSeconds(1),tc.plusSeconds(1))),true,
                pages::add,()->true))).as("past tc").isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(reasonOfWindows(adapter.collectWindows(credentials(),"1",t0,tc,List.of(),false,pages::add,
                ()->true))).as("nothing to read").isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(this.paths).isEmpty();
    }

    /**
     * Both collections read a signed call's transport rejection first: a rate limit, a time-out and a body that is
     * not JSON are reported as themselves at the key check and at a transaction-log page — never folded into the
     * call site's fallback (a timed-out key check used to read as an authentication rejection).
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} at {1} ({2})")
    @org.junit.jupiter.params.provider.CsvSource({
        "ratelimit,/v5/user/query-api,initial,RATE_LIMITED", "ratelimit,/v5/user/query-api,windows,RATE_LIMITED",
        "timeout,/v5/user/query-api,initial,TIMEOUT", "timeout,/v5/user/query-api,windows,TIMEOUT",
        "notjson,/v5/user/query-api,initial,RESPONSE_FORMAT", "notjson,/v5/user/query-api,windows,RESPONSE_FORMAT",
        "ratelimit,/v5/account/transaction-log,initial,RATE_LIMITED",
        "ratelimit,/v5/account/transaction-log,windows,RATE_LIMITED",
        "timeout,/v5/account/transaction-log,initial,TIMEOUT",
        "timeout,/v5/account/transaction-log,windows,TIMEOUT",
        "notjson,/v5/account/transaction-log,initial,RESPONSE_FORMAT",
        "notjson,/v5/account/transaction-log,windows,RESPONSE_FORMAT"})
    void aSignedCallsTransportRejectionIsReportedAsItselfDuringEitherCollection(String kind, String path,
            String entry, SourceRejectionReason expected) {
        this.failingPath=path;
        this.failure=kind;
        BybitAdapter adapter=adapter(this.origin,RESERVED_IP,Duration.ofMillis(300));
        var tc=java.time.Instant.ofEpochMilli(System.currentTimeMillis());
        var t0=tc.minus(Duration.ofDays(1));
        List<SourceConnectionApi.CollectionPage> pages=new ArrayList<>();
        var reason=entry.equals("initial")
                ? reasonOfCollection(adapter.collectInitialInputs(credentials(),"1",t0,tc,ignored->{},pages::add,
                        ()->true))
                : reasonOfWindows(adapter.collectWindows(credentials(),"1",t0,tc,BybitAdapter.contiguousWindows(t0,tc),
                        true,pages::add,()->true));
        assertThat(reason).isEqualTo(expected);
        assertThat(pages).as("no page is handed on from a rejected call").isEmpty();
    }

    // ------------------------------------------------------------------ Story 2.12 call budget

    private SourceConnectionApi.WindowCollectionResult oneWindow(BybitAdapter adapter) {
        var tc = java.time.Instant.ofEpochMilli(System.currentTimeMillis());
        var t0 = tc.minus(Duration.ofDays(1));
        return adapter.collectWindows(credentials(), "1", t0, tc,
                List.of(new SourceConnectionApi.CollectionWindow(t0, tc)), true, page -> {}, () -> true);
    }

    private List<Long> transactionArrivals() {
        synchronized (this.arrivals) {
            return this.arrivals.stream().filter(arrival -> arrival[1] == 1).map(arrival -> arrival[0]).toList();
        }
    }

    /**
     * A collection page that times out, answers 429, 5xx or {@code 10006} is asked again, the same page, up to three
     * times; the fourth failure is the collection's answer (ERD-1.0.0 §2.5).
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource({"ratelimit,RATE_LIMITED", "server,PROVIDER_UNAVAILABLE",
        "timeout,TIMEOUT", "retcode10006,RATE_LIMITED"})
    void aRetryablePageFailureIsAskedAgainThreeTimesAndNoMore(String kind, SourceRejectionReason expected) {
        this.failingPath = BybitAdapter.TRANSACTION_LOG_PATH;
        this.failure = kind;
        var outcome = oneWindow(adapter(this.origin, RESERVED_IP, Duration.ofMillis(300)));
        assertThat(outcome).isInstanceOf(SourceConnectionApi.WindowCollectionResult.Rejected.class);
        var rejected = (SourceConnectionApi.WindowCollectionResult.Rejected) outcome;
        assertThat(rejected.reason()).isEqualTo(expected);
        assertThat(rejected.retryCount()).isEqualTo(3);
        assertThat(rejected.pageCount()).isZero();
        // The single-threaded server drains slow requests after the client gave up on them.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (transactionArrivals().size() < 4 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(transactionArrivals()).hasSize(4);
    }

    /** A page that recovers on its second retry is handed on once, and the collection reports both retries. */
    @Test
    void aPageThatRecoversIsHandedOnOnceWithItsRetries() {
        this.failingPath = BybitAdapter.TRANSACTION_LOG_PATH;
        this.failure = "server";
        this.failTimes.set(2);
        List<SourceConnectionApi.CollectionPage> pages = new ArrayList<>();
        var tc = java.time.Instant.ofEpochMilli(System.currentTimeMillis());
        var t0 = tc.minus(Duration.ofDays(1));
        var outcome = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1)).collectWindows(credentials(), "1", t0,
                tc, List.of(new SourceConnectionApi.CollectionWindow(t0, tc)), true, pages::add, () -> true);
        assertThat(outcome).isInstanceOf(SourceConnectionApi.WindowCollectionResult.Collected.class);
        var value = ((SourceConnectionApi.WindowCollectionResult.Collected) outcome).value();
        assertThat(value.retryCount()).isEqualTo(2);
        assertThat(value.pageCount()).isEqualTo(pages.size());
        assertThat(value.equityDecimal()).isEqualTo("10.00");
        var arrivals = transactionArrivals();
        assertThat(arrivals.get(1) - arrivals.get(0)).as("the first delay").isGreaterThanOrEqualTo(15);
        assertThat(arrivals.get(2) - arrivals.get(1)).as("the second delay").isGreaterThanOrEqualTo(35);
    }

    /** Authentication, schema and semantic failures of a page are not retried. */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource({"unauthorized,AUTHENTICATION_REJECTED", "notjson,RESPONSE_FORMAT",
        "retcode10001,PROVIDER_UNAVAILABLE"})
    void aNonRetryablePageFailureIsAskedOnce(String kind, SourceRejectionReason expected) {
        this.failingPath = BybitAdapter.TRANSACTION_LOG_PATH;
        this.failure = kind;
        var outcome = (SourceConnectionApi.WindowCollectionResult.Rejected) oneWindow(
                adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1)));
        assertThat(outcome.reason()).isEqualTo(expected);
        assertThat(outcome.retryCount()).isZero();
        assertThat(transactionArrivals()).hasSize(1);
    }

    /** {@code 10006} is asked again no earlier than the reset time its headers gave. */
    @Test
    void aRateLimitIsRetriedAfterItsResetTime() {
        this.failingPath = BybitAdapter.TRANSACTION_LOG_PATH;
        this.failure = "retcode10006";
        this.failTimes.set(1);
        var outcome = oneWindow(adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1)));
        assertThat(outcome).isInstanceOf(SourceConnectionApi.WindowCollectionResult.Collected.class);
        var arrivals = transactionArrivals();
        assertThat(arrivals.get(1) - arrivals.get(0)).isGreaterThanOrEqualTo(250);
    }

    /**
     * Interpretation C4: a {@code 10006} whose reset time is an 18-digit value is asked again after the longest
     * backoff (60 ms in this budget), not never, and nothing overflows.
     */
    @Test
    void aRateLimitWithAnAbsurdResetIsRetriedAfterTheLongestBackoff() {
        this.failingPath = BybitAdapter.TRANSACTION_LOG_PATH;
        this.failure = "retcode10006far";
        this.failTimes.set(1);
        var outcome = oneWindow(adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1)));
        assertThat(outcome).isInstanceOf(SourceConnectionApi.WindowCollectionResult.Collected.class);
        var arrivals = transactionArrivals();
        assertThat(arrivals.get(1) - arrivals.get(0)).isBetween(40L, 1_000L);
    }

    /**
     * A collection whose lease stops holding while it waits to retry a page stops: nothing more is sent, and the
     * answer is "stopped", not a provider rejection the caller would record (patch 8).
     */
    @Test
    void aCollectionWhoseLeaseStopsHoldingStopsWithoutAProviderAnswer() {
        this.failingPath = BybitAdapter.TRANSACTION_LOG_PATH;
        this.failure = "server";
        var tc = java.time.Instant.ofEpochMilli(System.currentTimeMillis());
        var t0 = tc.minus(Duration.ofDays(1));
        var outcome = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1)).collectWindows(credentials(), "1", t0,
                tc, List.of(new SourceConnectionApi.CollectionWindow(t0, tc)), true, page -> {},
                () -> transactionArrivals().isEmpty());
        assertThat(outcome).isEqualTo(new SourceConnectionApi.WindowCollectionResult.Stopped(0, 0));
        assertThat(transactionArrivals()).hasSize(1);
    }

    /** A page asked again carries the times of the try that answered, not of its first try (patch 9). */
    @Test
    void aRetriedPageCarriesTheTimesOfTheTryThatAnswered() {
        this.failingPath = BybitAdapter.TRANSACTION_LOG_PATH;
        this.failure = "server";
        this.failTimes.set(2);
        List<SourceConnectionApi.CollectionPage> pages = new ArrayList<>();
        var tc = java.time.Instant.ofEpochMilli(System.currentTimeMillis());
        var t0 = tc.minus(Duration.ofDays(1));
        var outcome = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1)).collectWindows(credentials(), "1", t0,
                tc, List.of(new SourceConnectionApi.CollectionWindow(t0, tc)), true, pages::add, () -> true);
        assertThat(outcome).isInstanceOf(SourceConnectionApi.WindowCollectionResult.Collected.class);
        var arrivals = transactionArrivals();
        var first = pages.getFirst().evidence();
        assertThat(first.requestedAt().toEpochMilli()).as("after the failed tries")
                .isGreaterThanOrEqualTo(arrivals.get(1));
        assertThat(first.receivedAt()).isAfterOrEqualTo(first.requestedAt());
    }

    /**
     * A ban that opens the circuit while another call waits for its token holds that call back too: the circuit is
     * looked at again after the token (patch 4), so the waiting call sends nothing.
     */
    @Test
    void aCircuitThatOpensWhileACallWaitsForItsTokenHoldsThatCallBack() throws Exception {
        this.failingPath = BybitAdapter.QUERY_API_PATH;
        this.failure = "forbidden";
        this.delay.set(Duration.ofMillis(300));
        // One signed token a second: the second call waits about a second for it, long after the ban came back.
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(2),
                new SourceProperties.Budget("1", null, null, "20ms,40ms,60ms", "0", "5s", null, "200ms"));
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> adapter.verify(credentials()));
        // The first request is at the server (which answers it 300 ms later); the circuit is still closed.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (sizeOfPaths() < 1 && System.nanoTime() < deadline) Thread.sleep(5);
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> adapter.verify(credentials()));
        assertThat(reasonOf(first.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                .isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        assertThat(reasonOf(second.get(5, java.util.concurrent.TimeUnit.SECONDS)))
                .isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        assertThat(sizeOfPaths()).as("the waiting call sent nothing").isEqualTo(1);
    }

    /** Headers saying 10 % is left hold the next page until the reset time, at most the configured cap. */
    @Test
    void aLowRemainingLimitHoldsTheNextPage() {
        this.lowLimit = true;
        var outcome = oneWindow(adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1)));
        assertThat(outcome).isInstanceOf(SourceConnectionApi.WindowCollectionResult.Collected.class);
        var arrivals = transactionArrivals();
        assertThat(arrivals).hasSizeGreaterThanOrEqualTo(2);
        // The reset is 300 ms ahead; the test budget caps the wait at 200 ms.
        assertThat(arrivals.get(1) - arrivals.get(0)).isBetween(180L, 290L);
    }

    /**
     * An HTTP 403 is an IP ban: the page is not retried, and every Bybit call — signed or public — is held back without
     * a request until the circuit closes.
     */
    @Test
    void aForbiddenAnswerOpensTheCircuitOverEveryCall() throws InterruptedException {
        this.failingPath = BybitAdapter.TRANSACTION_LOG_PATH;
        this.failure = "forbidden";
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1));
        var outcome = (SourceConnectionApi.WindowCollectionResult.Rejected) oneWindow(adapter);
        assertThat(outcome.reason()).isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        assertThat(outcome.retryCount()).isZero();
        int requests = sizeOfPaths();
        assertThat(((SourceConnectionApi.MarketTimeResult.Rejected) adapter.marketTime()).reason())
                .isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        assertThat(reasonOf(adapter.verify(credentials()))).isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        assertThat(sizeOfPaths()).as("no request while the circuit is open").isEqualTo(requests);
        Thread.sleep(700);
        assertThat(adapter.marketTime()).isInstanceOf(SourceConnectionApi.MarketTimeResult.Observed.class);
        assertThat(sizeOfPaths()).isEqualTo(requests + 1);
    }

    /**
     * A 403 at the connection admission's first call (query-api) is an IP ban too: the connection is refused as a
     * provider failure and the circuit holds every later Bybit call back without a request.
     */
    @Test
    void aForbiddenConnectionAdmissionOpensTheCircuit() {
        this.failingPath = BybitAdapter.QUERY_API_PATH;
        this.failure = "forbidden";
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1));
        assertThat(reasonOf(adapter.verify(credentials()))).isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        assertThat(this.paths).containsExactly(BybitAdapter.QUERY_API_PATH);
        assertThat(((SourceConnectionApi.MarketTimeResult.Rejected) adapter.marketTime()).reason())
                .isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        assertThat(reasonOf(adapter.verify(credentials()))).isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        assertThat(this.paths).as("nothing more while the circuit is open").hasSize(1);
    }

    /** The admission's calls are never retried, whatever the failure. */
    @Test
    void anAdmissionCallIsNeverRetried() {
        this.failingPath = BybitAdapter.QUERY_API_PATH;
        this.failure = "server";
        assertThat(reasonOf(adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1)).verify(credentials())))
                .isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        assertThat(this.paths).containsExactly(BybitAdapter.QUERY_API_PATH);
    }

    /** A window outside [t0, tc], longer than seven days or empty, and a wallet-less empty call are refused unsent. */
    @Test
    void anInvalidWindowIsRefusedWithoutACall() {
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1));
        var tc = java.time.Instant.ofEpochMilli(System.currentTimeMillis());
        var t0 = tc.minus(Duration.ofDays(20));
        for (var windows : List.of(List.of(new SourceConnectionApi.CollectionWindow(t0.minusMillis(1), t0.plusSeconds(1))),
                List.of(new SourceConnectionApi.CollectionWindow(tc.minusSeconds(1), tc.plusMillis(1))),
                List.of(new SourceConnectionApi.CollectionWindow(t0, t0.plus(Duration.ofDays(7)).plusMillis(1))),
                List.of(new SourceConnectionApi.CollectionWindow(t0, t0)))) {
            var outcome = adapter.collectWindows(credentials(), "1", t0, tc, windows, true, page -> {}, () -> true);
            assertThat(((SourceConnectionApi.WindowCollectionResult.Rejected) outcome).reason())
                    .isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        }
        assertThat(((SourceConnectionApi.WindowCollectionResult.Rejected) adapter.collectWindows(credentials(), "1",
                t0, tc, List.of(), false, page -> {}, () -> true)).reason()).isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(this.paths).isEmpty();
    }

    private static SourceRejectionReason reasonOfCollection(SourceConnectionApi.CollectionResult outcome) {
        assertThat(outcome).isInstanceOf(SourceConnectionApi.CollectionResult.Rejected.class);
        return ((SourceConnectionApi.CollectionResult.Rejected) outcome).reason();
    }

    private static SourceRejectionReason reasonOfWindows(SourceConnectionApi.WindowCollectionResult outcome) {
        assertThat(outcome).isInstanceOf(SourceConnectionApi.WindowCollectionResult.Rejected.class);
        return ((SourceConnectionApi.WindowCollectionResult.Rejected) outcome).reason();
    }

    @Test
    void rejectsTransactionLogPagesLargerThanRequestedLimit() {
        this.oversizedPage=true;
        BybitAdapter adapter=adapter(this.origin,RESERVED_IP,Duration.ofSeconds(1));
        List<SourceConnectionApi.CollectionPage> pages=new ArrayList<>();
        var t0=java.time.Instant.now().minusSeconds(60);

        var outcome=adapter.collectInitialInputs(credentials(),"1",t0,null,ignored->{},pages::add,()->true);

        assertThat(outcome).isInstanceOf(SourceConnectionApi.CollectionResult.Rejected.class);
        assertThat(((SourceConnectionApi.CollectionResult.Rejected)outcome).reason())
                .isEqualTo(SourceRejectionReason.RESPONSE_FORMAT);
        assertThat(pages).isEmpty();
    }

    @Test
    void 허용_목록_밖_permission은_닫히고_요구_목록_부재는_권한_부족이다() {
        JsonNode readOnly = JSON.readTree("{\"Spot\":[],\"Wallet\":[]}");
        JsonNode writeCapable = JSON.readTree("{\"Spot\":[\"SpotTrade\"],\"Wallet\":[]}");
        JsonNode notAnObject = JSON.readTree("[]");
        JsonNode notAnArray = JSON.readTree("{\"Spot\":\"SpotTrade\"}");

        // 허용 목록이 비어 있는 동안의 뜻: 모든 배열이 비어 있어야 한다.
        assertThat(BybitAdapter.permissionVerdict(readOnly, Set.of(), Set.of())).isNull();
        assertThat(BybitAdapter.permissionVerdict(writeCapable, Set.of(), Set.of()))
                .isEqualTo(SourceRejectionReason.WRITE_PERMISSION);
        // 목록이 채워지면 그 그룹만 비어 있지 않아도 된다.
        assertThat(BybitAdapter.permissionVerdict(writeCapable, Set.of("Spot"), Set.of())).isNull();
        // 요구 목록이 채워지면 없거나 빈 그룹이 권한 부족이다.
        assertThat(BybitAdapter.permissionVerdict(readOnly, Set.of(), Set.of("Spot")))
                .isEqualTo(SourceRejectionReason.INSUFFICIENT_PERMISSION);
        assertThat(BybitAdapter.permissionVerdict(readOnly, Set.of(), Set.of("Missing")))
                .isEqualTo(SourceRejectionReason.INSUFFICIENT_PERMISSION);
        // 모르는 모양은 형식 오류다. 낙관적으로 해석하지 않는다.
        assertThat(BybitAdapter.permissionVerdict(null, Set.of(), Set.of()))
                .isEqualTo(SourceRejectionReason.RESPONSE_FORMAT);
        assertThat(BybitAdapter.permissionVerdict(notAnObject, Set.of(), Set.of()))
                .isEqualTo(SourceRejectionReason.RESPONSE_FORMAT);
        assertThat(BybitAdapter.permissionVerdict(notAnArray, Set.of(), Set.of()))
                .isEqualTo(SourceRejectionReason.RESPONSE_FORMAT);
    }

    @Test
    void 허용_목록은_비어_있고_그_공백은_스파이크가_채운다() {
        // 목록을 지어내지 않았다는 사실 자체가 계약이다(SPEC의 Ask First). 값이 채워지는 날 이
        // 단정이 먼저 실패해 그 변경이 의도된 것임을 확인하게 만든다.
        assertThat(BybitAdapter.permissionAllowLists()).allSatisfy(list -> assertThat(list).isEmpty());
    }

    // ------------------------------------------------------------------ Story 2.6 public market reads

    private static final java.time.Instant WINDOW_START = java.time.Instant.parse("2026-09-01T10:25:00Z");
    private static final java.time.Instant WINDOW_END = java.time.Instant.parse("2026-09-01T10:30:00Z");

    @Test
    void publicMarketReadsAreUnsignedAndUseTheClosedSpotQueries() {
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1));

        var time = adapter.marketTime();
        var instrument = adapter.spotInstrument("BTCUSDT");
        var candles = adapter.spotMinuteCandles("BTCUSDT", WINDOW_START, WINDOW_END);

        assertThat(time).isInstanceOf(SourceConnectionApi.MarketTimeResult.Observed.class);
        assertThat(((SourceConnectionApi.MarketTimeResult.Observed) time).serverTime()).isAfter(java.time.Instant.EPOCH);
        assertThat(((SourceConnectionApi.InstrumentResult.Listed) instrument).instruments()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.symbol()).isEqualTo("BTCUSDT");
                    assertThat(entry.baseAsset()).isEqualTo("BTC");
                    assertThat(entry.quoteAsset()).isEqualTo("USDT");
                    assertThat(entry.status()).isEqualTo("Trading");
                    assertThat(entry.evidenceHash()).hasSize(64);
                });
        var rows = ((SourceConnectionApi.CandleResult.Candles) candles).candles();
        assertThat(rows).extracting(SourceConnectionApi.MarketCandle::startAt)
                .containsExactly(WINDOW_START.plusSeconds(60), WINDOW_START);
        assertThat(rows.get(1).close()).isEqualTo("65000.25");
        assertThat(rows.get(0).close()).isEqualTo("65000.50");
        assertThat(rows).allSatisfy(row -> assertThat(row.evidenceHash()).hasSize(64));
        assertThat(this.paths).containsExactly(BybitAdapter.SERVER_TIME_PATH, BybitAdapter.INSTRUMENTS_INFO_PATH,
                BybitAdapter.KLINE_PATH);
        assertThat(List.copyOf(this.signedRequests)).containsOnly(false);
        assertThat(this.requests.get(1)).endsWith("?category=spot&symbol=BTCUSDT");
        assertThat(this.requests.get(2)).endsWith("?category=spot&symbol=BTCUSDT&interval=1&start="
                + WINDOW_START.toEpochMilli() + "&end=" + WINDOW_END.toEpochMilli() + "&limit=1000");
    }

    @Test
    void invalidMarketQueriesAreRejectedWithoutACall() {
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1));
        java.time.Instant aligned = WINDOW_START;

        assertThat(candleReason(adapter.spotMinuteCandles("BTCUSDT", aligned.plusMillis(1), WINDOW_END)))
                .isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(candleReason(adapter.spotMinuteCandles("BTCUSDT", WINDOW_END, aligned)))
                .isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(candleReason(adapter.spotMinuteCandles("BTCUSDT", aligned, aligned.plusSeconds(60L * 1000))))
                .isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(candleReason(adapter.spotMinuteCandles("btc&x=1", aligned, WINDOW_END)))
                .isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(((SourceConnectionApi.InstrumentResult.Rejected) adapter.spotInstrument("BTC USDT")).reason())
                .isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        // 1000 candles exactly is the ceiling and is sent.
        assertThat(adapter.spotMinuteCandles("BTCUSDT", aligned, aligned.plusSeconds(60L * 999)))
                .isInstanceOf(SourceConnectionApi.CandleResult.Candles.class);
        assertThat(this.paths).containsExactly(BybitAdapter.KLINE_PATH);
        BybitAdapter unconfigured = adapter(null, RESERVED_IP, Duration.ofSeconds(1));
        assertThat(((SourceConnectionApi.MarketTimeResult.Rejected) unconfigured.marketTime()).reason())
                .isEqualTo(SourceRejectionReason.UNAVAILABLE);
        assertThat(this.paths).hasSize(1);
    }

    @Test
    void marketReadsReportTheTransportRejectionFirstAndNeverRetry() {
        this.delay.set(Duration.ofMillis(800));
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofMillis(150));

        assertThat(((SourceConnectionApi.MarketTimeResult.Rejected) adapter.marketTime()).reason())
                .isEqualTo(SourceRejectionReason.TIMEOUT);
        assertThat(((SourceConnectionApi.InstrumentResult.Rejected) adapter.spotInstrument("BTCUSDT")).reason())
                .isEqualTo(SourceRejectionReason.TIMEOUT);
        assertThat(candleReason(adapter.spotMinuteCandles("BTCUSDT", WINDOW_START, WINDOW_END)))
                .isEqualTo(SourceRejectionReason.TIMEOUT);
        // The single-threaded server drains the three slow requests; a retry would show up as a fourth.
        this.delay.set(Duration.ZERO);
        awaitPaths(3);

        this.marketMode = "ratelimit";
        assertThat(candleReason(adapter.spotMinuteCandles("BTCUSDT", WINDOW_START, WINDOW_END)))
                .isEqualTo(SourceRejectionReason.RATE_LIMITED);
        awaitPaths(4);
    }

    private void awaitPaths(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try {
            while (sizeOfPaths() < expected && System.nanoTime() < deadline) Thread.sleep(20);
            Thread.sleep(300);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        assertThat(sizeOfPaths()).isEqualTo(expected);
    }

    private int sizeOfPaths() {
        synchronized (this.paths) {
            return this.paths.size();
        }
    }

    @Test
    void failedOrUnknownMarketAnswersFoldIntoClosedReasonsWithoutTheProviderText() {
        BybitAdapter adapter = adapter(this.origin, RESERVED_IP, Duration.ofSeconds(1));
        this.marketMode = "retcode";
        var instrument = adapter.spotInstrument("BTCUSDT");
        assertThat(((SourceConnectionApi.InstrumentResult.Rejected) instrument).reason())
                .isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        assertThat(instrument.toString()).doesNotContain("raw provider text");
        assertThat(candleReason(adapter.spotMinuteCandles("BTCUSDT", WINDOW_START, WINDOW_END)))
                .isEqualTo(SourceRejectionReason.PROVIDER_UNAVAILABLE);
        for (String mode : List.of("missing-status", "not-array")) {
            this.marketMode = mode;
            assertThat(((SourceConnectionApi.InstrumentResult.Rejected) adapter.spotInstrument("BTCUSDT")).reason())
                    .as(mode).isEqualTo(SourceRejectionReason.RESPONSE_FORMAT);
        }
        for (String mode : List.of("short-row", "outside", "wrong-symbol", "number", "exponent")) {
            this.marketMode = mode;
            assertThat(candleReason(adapter.spotMinuteCandles("BTCUSDT", WINDOW_START, WINDOW_END)))
                    .as(mode).isEqualTo(SourceRejectionReason.RESPONSE_FORMAT);
        }
    }

    private static SourceRejectionReason candleReason(SourceConnectionApi.CandleResult result) {
        return ((SourceConnectionApi.CandleResult.Rejected) result).reason();
    }

    /**
     * 정상 응답을 내는 최소 제공자.
     *
     * <p>{@code DevBybitStub}을 쓰지 않는다. 이 시험이 보는 것은 시나리오가 아니라 전송 계층이고,
     * 지연을 주입할 수 있어야 하기 때문이다.
     */
    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        this.paths.add(path);
        this.requests.add(exchange.getRequestURI().toString());
        Duration wait = this.delay.get();
        if (!wait.isZero()) {
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        String apiKey = exchange.getRequestHeaders().getFirst("X-BAPI-API-KEY");
        this.signedRequests.add(apiKey != null || exchange.getRequestHeaders().getFirst("X-BAPI-SIGN") != null);
        this.arrivals.add(new long[] {System.currentTimeMillis(), path.equals(BybitAdapter.TRANSACTION_LOG_PATH) ? 1 : 0});
        if (path.equals(this.failingPath) && failsNow()) {
            switch (this.failure) {
                case "server", "unauthorized", "forbidden" -> {
                    byte[] refused = "{\"retCode\":10016,\"retMsg\":\"raw provider text\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(this.failure.equals("server") ? 503
                            : this.failure.equals("unauthorized") ? 401 : 403, refused.length);
                    exchange.getResponseBody().write(refused);
                    exchange.close();
                    return;
                }
                case "retcode10006", "retcode10001", "retcode10006far" -> {
                    byte[] refused = ("{\"retCode\":" + this.failure.substring(7, 12) + ",\"retMsg\":\"raw provider text\","
                            + "\"result\":{},\"time\":1}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("X-Bapi-Limit", "10");
                    exchange.getResponseHeaders().set("X-Bapi-Limit-Status", "0");
                    // "far": an 18-digit reset time no clock reaches (interpretation C4).
                    exchange.getResponseHeaders().set("X-Bapi-Limit-Reset-Timestamp", this.failure.endsWith("far")
                            ? "999999999999999999" : String.valueOf(System.currentTimeMillis() + 300));
                    exchange.sendResponseHeaders(200, refused.length);
                    exchange.getResponseBody().write(refused);
                    exchange.close();
                    return;
                }
                case "ratelimit" -> {
                    byte[] limited = "{\"retCode\":10006,\"retMsg\":\"raw provider text\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429, limited.length);
                    exchange.getResponseBody().write(limited);
                    exchange.close();
                    return;
                }
                case "timeout" -> {
                    try {
                        Thread.sleep(700);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                case "notjson" -> {
                    byte[] text = "not json".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(200, text.length);
                    exchange.getResponseBody().write(text);
                    exchange.close();
                    return;
                }
                default -> { }
            }
        }
        if ("ratelimit".equals(this.marketMode) && path.startsWith("/v5/market/")) {
            byte[] limited = "{\"retCode\":10006,\"retMsg\":\"raw provider text\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, limited.length);
            exchange.getResponseBody().write(limited);
            exchange.close();
            return;
        }
        String body =
                switch (path) {
                    case BybitAdapter.INSTRUMENTS_INFO_PATH -> instrumentResponse();
                    case BybitAdapter.KLINE_PATH -> klineResponse(exchange.getRequestURI().getRawQuery());
                    case BybitAdapter.SERVER_TIME_PATH -> {
                        long now=System.currentTimeMillis();
                        yield result("{\"timeSecond\":\""+(now/1000)+"\",\"timeNano\":\""+(now*1_000_000L)+"\"}");
                    }
                    case BybitAdapter.QUERY_API_PATH ->
                            result(
                                    "{\"readOnly\":1,\"permissions\":{\"Spot\":[]},\"ips\":[\""
                                            + RESERVED_IP
                                            + "\"],\"isMaster\":"
                                            + (apiKey != null && apiKey.startsWith("sub.")
                                                    ? "false"
                                                    : "true")
                                            + ",\"parentUid\":\"0\",\"userID\":\"1\"}");
                    case BybitAdapter.ACCOUNT_INFO_PATH -> result("{\"unifiedMarginStatus\":5}");
                    case BybitAdapter.WALLET_BALANCE_PATH ->
                            walletResponse();
                    case BybitAdapter.TRANSACTION_LOG_PATH -> transactionResponse(exchange.getRequestURI().getRawQuery());
                    default -> result("{}");
                };
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (this.lowLimit && path.equals(BybitAdapter.TRANSACTION_LOG_PATH)) {
            exchange.getResponseHeaders().set("X-Bapi-Limit", "10");
            exchange.getResponseHeaders().set("X-Bapi-Limit-Status", "1");
            exchange.getResponseHeaders().set("X-Bapi-Limit-Reset-Timestamp",
                    String.valueOf(System.currentTimeMillis() + 300));
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** Whether this request of the failing path fails: every one while the count is negative, else that many. */
    private boolean failsNow() {
        int left = this.failTimes.get();
        if (left < 0) return true;
        if (left == 0) return false;
        this.failTimes.decrementAndGet();
        return true;
    }

    private String transactionResponse(String query) {
        int n=this.transactionCalls.incrementAndGet();
        if(this.oversizedPage&&n==1) {
            StringBuilder rows=new StringBuilder("[");
            for(int index=0;index<51;index++) {
                if(index>0) rows.append(',');
                rows.append("{\"id\":\"over-limit-").append(index).append("\",\"type\":\"TRANSFER_IN\","
                        +"\"currency\":\"USDT\",\"cashFlow\":\"1\",\"transactionTime\":\"")
                        .append(System.currentTimeMillis()).append("\"}");
            }
            rows.append(']');
            return result("{\"list\":"+rows+",\"nextPageCursor\":\"\"}");
        }
        if(n==1) return result("{\"list\":[{\"id\":\"event-1\",\"type\":\"TRANSFER_IN\","
                +"\"currency\":\"USDT\",\"cashFlow\":\"1.000\",\"transactionTime\":\""
                +(System.currentTimeMillis()-7L*24*60*60*1000)+"\"}],\"nextPageCursor\":\""+SPECIAL_CURSOR+"\"}");
        String rawCursor=query.contains("cursor=")?query.substring(query.indexOf("cursor=")+7):null;
        String cursor=rawCursor==null?null:URLDecoder.decode(rawCursor,StandardCharsets.UTF_8);
        if(cursor!=null) this.decodedCursors.add(cursor);
        if(SPECIAL_CURSOR.equals(cursor)) return result("{\"list\":[],\"nextPageCursor\":\"\"}");
        return result("{\"list\":[],\"nextPageCursor\":\"\"}");
    }

    private String instrumentResponse() {
        return switch (this.marketMode) {
            case "retcode" -> "{\"retCode\":10001,\"retMsg\":\"raw provider text\",\"result\":{},\"time\":1}";
            case "missing-status" -> result("{\"category\":\"spot\",\"list\":[{\"symbol\":\"BTCUSDT\","
                    + "\"baseCoin\":\"BTC\",\"quoteCoin\":\"USDT\"}]}");
            case "not-array" -> result("{\"category\":\"spot\",\"list\":{}}");
            default -> result("{\"category\":\"spot\",\"list\":[{\"symbol\":\"BTCUSDT\",\"baseCoin\":\"BTC\","
                    + "\"quoteCoin\":\"USDT\",\"innovation\":\"0\",\"status\":\"Trading\"}]}");
        };
    }

    private String klineResponse(String query) {
        long start = Long.parseLong(parameter(query, "start"));
        long end = Long.parseLong(parameter(query, "end"));
        String symbol = "wrong-symbol".equals(this.marketMode) ? "ETHUSDT" : parameter(query, "symbol");
        String rows = switch (this.marketMode) {
            case "short-row" -> "[[\"" + start + "\",\"1\",\"1\",\"1\",\"1\",\"1\"]]";
            case "outside" -> "[[\"" + (end + 60_000) + "\",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\"]]";
            case "number" -> "[[" + start + ",\"1\",\"1\",\"1\",\"1\",\"1\",\"1\"]]";
            case "exponent" -> "[[\"" + start + "\",\"1\",\"1\",\"1\",\"1E3\",\"1\",\"1\"]]";
            default -> "[[\"" + (start + 60_000) + "\",\"65000.3\",\"65001\",\"64999\",\"65000.50\",\"2\",\"130001\"],"
                    + "[\"" + start + "\",\"65000\",\"65001\",\"64999\",\"65000.25\",\"1\",\"65000.25\"]]";
        };
        return "retcode".equals(this.marketMode)
                ? "{\"retCode\":10002,\"retMsg\":\"raw provider text\",\"result\":{},\"time\":1}"
                : result("{\"symbol\":\"" + symbol + "\",\"category\":\"spot\",\"list\":" + rows + "}");
    }

    private static String parameter(String query, String name) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(name + "=")) return URLDecoder.decode(pair.substring(name.length() + 1), StandardCharsets.UTF_8);
        }
        throw new AssertionError("missing query parameter " + name);
    }

    private String walletResponse() {
        long envelopeTime=System.currentTimeMillis();
        this.walletEnvelopeTime.set(envelopeTime);
        return result("{\"list\":[{\"accountType\":\"UNIFIED\",\"totalEquity\":\"10.00\"}]}",envelopeTime);
    }

    private static String result(String body) {
        return result(body,System.currentTimeMillis());
    }

    private static String result(String body,long envelopeTime) {
        return "{\"retCode\":0,\"retMsg\":\"OK\",\"result\":" + body
                + ",\"time\":" + envelopeTime + "}";
    }

    private static BybitAdapter adapter(String baseUrl, String reservedIp, Duration timeout,
            SourceProperties.Budget budget) {
        return new BybitAdapter(new SourceProperties(SourceProperties.Credential.empty(),
                new SourceProperties.Bybit(baseUrl, reservedIp, timeout.toMillis() + "ms", budget)));
    }

    private static BybitAdapter adapter(String baseUrl, String reservedIp, Duration timeout) {
        return new BybitAdapter(
                new SourceProperties(
                        SourceProperties.Credential.empty(),
                        // 상한은 문자열로 구성된다. 빈 값이 기동을 죽이지 않게 하려는 배치이며
                        // (`SourceProperties.Bybit.requestTimeoutOrDefault`) 여기서는 그 형식을
                        // 그대로 지난다 — 시험이 제품과 다른 경로로 값을 넣지 않는다.
                        new SourceProperties.Bybit(baseUrl, reservedIp, timeout.toMillis() + "ms",
                                // A call budget whose page retries take milliseconds (Story 2.12): the retry
                                // itself is asserted, not its production delays.
                                new SourceProperties.Budget(null, null, null, "20ms,40ms,60ms", "0", "600ms", null,
                                        "200ms"))));
    }

    private static SourceCredentials credentials() {
        return new SourceCredentials("uta2.1", "secret-" + UUID.randomUUID());
    }

    private static SourceRejectionReason reasonOf(SourceVerification verification) {
        return ((SourceVerification.Rejected) verification).reason();
    }
}
