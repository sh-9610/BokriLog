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

    private final List<String> paths = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger transactionCalls = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicLong walletEnvelopeTime = new AtomicLong();
    private final List<String> decodedCursors = new ArrayList<>();
    private volatile boolean oversizedPage;

    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);

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
        String body =
                switch (path) {
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
