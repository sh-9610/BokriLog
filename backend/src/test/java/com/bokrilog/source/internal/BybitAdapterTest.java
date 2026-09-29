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
    /** {@code ratelimit} (429), {@code timeout} (slower than the client's timeout) or {@code notjson}. */
    private volatile String failure = "";

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
            assertThat(new SourceProperties.Bybit(null, null, unreadable).requestTimeoutOrDefault())
                    .isEqualTo(SourceProperties.Bybit.DEFAULT_REQUEST_TIMEOUT);
        }
        // 0 이하도 같다. 상한이 없다는 뜻이 되어 무한정 기다리는 경로가 열린다.
        assertThat(new SourceProperties.Bybit(null, null, "0s").requestTimeoutOrDefault())
                .isEqualTo(SourceProperties.Bybit.DEFAULT_REQUEST_TIMEOUT);
        assertThat(new SourceProperties.Bybit(null, null, "-1s").requestTimeoutOrDefault())
                .isEqualTo(SourceProperties.Bybit.DEFAULT_REQUEST_TIMEOUT);
        // 읽히는 값은 그대로 쓴다.
        assertThat(new SourceProperties.Bybit(null, null, "250ms").requestTimeoutOrDefault())
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
        var outcome=adapter.collectInitialInputs(credentials(),"1",t0,null,ignored->{},pages::add);

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
     * Story 2.11: the incremental collection never asks the time — its {@code tc} was fixed by the caller — and
     * its windows start at {@code from}, contiguous and at most seven days, up to that {@code tc}; events are
     * still judged against {@code (t0, tc]}. A scope that starts before {@code t0} or does not end after
     * {@code from} is refused before any call.
     */
    @Test
    void incrementalCollectionStartsItsWindowsAtFromAndNeverAsksTheTime() {
        BybitAdapter adapter=adapter(this.origin,RESERVED_IP,Duration.ofSeconds(1));
        var tc=java.time.Instant.ofEpochMilli(System.currentTimeMillis());
        var t0=tc.minus(Duration.ofDays(20));
        var from=tc.minus(Duration.ofDays(8));
        List<SourceConnectionApi.CollectionPage> pages=new ArrayList<>();
        var outcome=adapter.collectIncrementalInputs(credentials(),"1",t0,from,tc,pages::add);

        assertThat(outcome).isInstanceOf(SourceConnectionApi.CollectionResult.Collected.class);
        var value=((SourceConnectionApi.CollectionResult.Collected)outcome).value();
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
        assertThat(reasonOfCollection(adapter.collectIncrementalInputs(credentials(),"1",t0,t0.minusMillis(1),tc,
                pages::add))).as("before t0").isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(reasonOfCollection(adapter.collectIncrementalInputs(credentials(),"1",t0,tc,tc,pages::add)))
                .as("an empty scope").isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(reasonOfCollection(adapter.collectIncrementalInputs(credentials(),"1",t0,from,
                from.minusMillis(1),pages::add))).as("tc before from").isEqualTo(SourceRejectionReason.INVALID_FORMAT);
        assertThat(this.paths).isEmpty();
    }

    /**
     * Both collections read a signed call's transport rejection first: a rate limit, a time-out and a body that is
     * not JSON are reported as themselves at the key check and at a transaction-log page — never folded into the
     * call site's fallback (a timed-out key check used to read as an authentication rejection).
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} at {1} ({2})")
    @org.junit.jupiter.params.provider.CsvSource({
        "ratelimit,/v5/user/query-api,initial,RATE_LIMITED", "ratelimit,/v5/user/query-api,incremental,RATE_LIMITED",
        "timeout,/v5/user/query-api,initial,TIMEOUT", "timeout,/v5/user/query-api,incremental,TIMEOUT",
        "notjson,/v5/user/query-api,initial,RESPONSE_FORMAT", "notjson,/v5/user/query-api,incremental,RESPONSE_FORMAT",
        "ratelimit,/v5/account/transaction-log,initial,RATE_LIMITED",
        "ratelimit,/v5/account/transaction-log,incremental,RATE_LIMITED",
        "timeout,/v5/account/transaction-log,initial,TIMEOUT",
        "timeout,/v5/account/transaction-log,incremental,TIMEOUT",
        "notjson,/v5/account/transaction-log,initial,RESPONSE_FORMAT",
        "notjson,/v5/account/transaction-log,incremental,RESPONSE_FORMAT"})
    void aSignedCallsTransportRejectionIsReportedAsItselfDuringEitherCollection(String kind, String path,
            String entry, SourceRejectionReason expected) {
        this.failingPath=path;
        this.failure=kind;
        BybitAdapter adapter=adapter(this.origin,RESERVED_IP,Duration.ofMillis(300));
        var tc=java.time.Instant.ofEpochMilli(System.currentTimeMillis());
        var t0=tc.minus(Duration.ofDays(1));
        List<SourceConnectionApi.CollectionPage> pages=new ArrayList<>();
        var outcome=entry.equals("initial")
                ? adapter.collectInitialInputs(credentials(),"1",t0,tc,ignored->{},pages::add)
                : adapter.collectIncrementalInputs(credentials(),"1",t0,t0,tc,pages::add);
        assertThat(reasonOfCollection(outcome)).isEqualTo(expected);
        assertThat(pages).as("no page is handed on from a rejected call").isEmpty();
    }

    private static SourceRejectionReason reasonOfCollection(SourceConnectionApi.CollectionResult outcome) {
        assertThat(outcome).isInstanceOf(SourceConnectionApi.CollectionResult.Rejected.class);
        return ((SourceConnectionApi.CollectionResult.Rejected) outcome).reason();
    }

    @Test
    void rejectsTransactionLogPagesLargerThanRequestedLimit() {
        this.oversizedPage=true;
        BybitAdapter adapter=adapter(this.origin,RESERVED_IP,Duration.ofSeconds(1));
        List<SourceConnectionApi.CollectionPage> pages=new ArrayList<>();
        var t0=java.time.Instant.now().minusSeconds(60);

        var outcome=adapter.collectInitialInputs(credentials(),"1",t0,null,ignored->{},pages::add);

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
        if (path.equals(this.failingPath)) {
            switch (this.failure) {
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
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
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

    private static BybitAdapter adapter(String baseUrl, String reservedIp, Duration timeout) {
        return new BybitAdapter(
                new SourceProperties(
                        SourceProperties.Credential.empty(),
                        // 상한은 문자열로 구성된다. 빈 값이 기동을 죽이지 않게 하려는 배치이며
                        // (`SourceProperties.Bybit.requestTimeoutOrDefault`) 여기서는 그 형식을
                        // 그대로 지난다 — 시험이 제품과 다른 경로로 값을 넣지 않는다.
                        new SourceProperties.Bybit(baseUrl, reservedIp, timeout.toMillis() + "ms")));
    }

    private static SourceCredentials credentials() {
        return new SourceCredentials("uta2.1", "secret-" + UUID.randomUUID());
    }

    private static SourceRejectionReason reasonOf(SourceVerification verification) {
        return ((SourceVerification.Rejected) verification).reason();
    }
}
