package com.bokrilog;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * 개발·CI와 백엔드 시험이 함께 쓰는 Bybit 스텁의 <b>논리</b>.
 *
 * <p>{@link DevOidcStub}과 같은 배치다 — 논리는 {@code main}에 하나뿐이고 표면만 둘이다. 개발·CI
 * 스택은 {@link DevBybitStubConfig}가 {@code /api/dev-bybit/**}에 올리고 백엔드 시험은
 * {@code BybitStubConfiguration}이 루프백 HTTP 서버에 올린다. 스텁을 두 번 만들지 않는다.
 *
 * <p><b>시나리오는 API Key가 고른다.</b> {@code <시나리오>.<UID>} 모양이며 그 앞부분이 아래 닫힌
 * 목록에 없으면 인증 거절이다. 값을 키에 담는 이유는 결정성이다 — 같은 키로 몇 번을 물어도 같은
 * 답이 나오고, 시험이 실패를 만들기 위해 스텁의 상태를 미리 바꿔 둘 필요가 없다.
 *
 * <p><b>자격정보 모양 리터럴이 없다.</b> 시나리오 토큰은 선택자이지 비밀이 아니고, Secret은 값이
 * 무엇이든 받는다(시험과 검증 스크립트가 실행 시점에 만든다). 서명은 <b>존재만</b> 확인한다 —
 * 스텁이 Secret을 알지 못하므로 HMAC을 다시 계산할 수 없고, 서명 계산 자체의 계약은 자바 시험이
 * 고정한다. 여기서 보는 것은 "서명된 요청의 모양으로 왔는가" 하나다.
 *
 * <p>응답은 문자열로 만든다. 값이 고정된 몇 줄짜리 JSON이라 직렬화 라이브러리를 들이지 않는다 —
 * 무엇을 돌려주는지가 읽는 자리에서 그대로 보인다({@code DevOidcStubConfig}의 동의 화면과 같은
 * 판단).
 */
final class DevBybitStub {

    /** 키 자체의 성질을 묻는 경로. 어댑터의 첫 호출이다. */
    static final String QUERY_API_PATH = "/v5/user/query-api";

    /** 계정 모드를 묻는 경로. 둘째 호출이다. */
    static final String ACCOUNT_INFO_PATH = "/v5/account/info";

    /** 지갑을 묻는 경로. 셋째 호출이다. */
    static final String WALLET_BALANCE_PATH = "/v5/account/wallet-balance";

    static final String SERVER_TIME_PATH = "/v5/market/time";

    static final String TRANSACTION_LOG_PATH = "/v5/account/transaction-log";

    /** 공개 spot 상품 정보(Story 2.6). 서명 없이 답한다. */
    static final String INSTRUMENTS_INFO_PATH = "/v5/market/instruments-info";

    /** 공개 spot 1분봉(Story 2.6). 서명 없이 답한다. */
    static final String KLINE_PATH = "/v5/market/kline";

    /**
     * 공개 시세 fixture. <b>symbol이 고른다.</b>
     *
     * <p>API Key가 시나리오를 고르는 것과 같은 결정성이다 — 공개 호출에는 키가 없으므로 선택자가
     * symbol이다. 목록에 없는 symbol은 상품 정보가 빈 목록이고 kline은 제공자 오류다. 모든 candle의
     * 시가·고가·저가·종가는 같은 값이며 거래량은 {@code 1}이다. 진행 중인 현재 분 candle도 답에
     * 포함한다 — 실제 제공자처럼 미종료 candle이 섞여 오고, 그것을 버리는 것은 호출자의 몫이다.
     */
    enum MarketFixture {

        /** 직접 경로 {@code ASSETUSDT}. 모든 분에 candle이 있다. */
        DIRECT("BTCUSDT", "BTC", "USDT", "Trading", "65000.25"),

        /** 교차 경로의 첫 leg. {@code ETHUSDT}는 목록에 없으므로 {@code ETHUSDC × USDCUSDT}로 간다. */
        CROSS_BASE("ETHUSDC", "ETH", "USDC", "Trading", "3000.1"),

        /** 교차 경로의 둘째 leg이자 {@code USDC} 자산의 경로. 1 USD로 고정하지 않는다. */
        CROSS_QUOTE("USDCUSDT", "USDC", "USDT", "Trading", "0.9998"),

        /** candle 공백. epoch 분이 10의 배수인 candle만 있다. */
        GAP("GAPUSDT", "GAP", "USDT", "Trading", "2.5"),

        /** 거래 중인 상품이지만 이력이 없다. 거래 가능 심볼만으로 과거 가격을 가정하지 않는다. */
        NO_HISTORY("NEWUSDT", "NEW", "USDT", "Trading", null),

        /** 상장돼 있지만 {@code Trading}이 아니다. */
        NOT_TRADING("HALTUSDT", "HALT", "USDT", "Closed", "1"),

        /** 같은 candle이 서로 다른 종가로 두 번 온다. */
        CONFLICT("DUPUSDT", "DUP", "USDT", "Trading", "4"),

        /** 첫 행이 여섯 칸이다. 모양을 알 수 없는 응답이다. */
        MALFORMED("BADUSDT", "BAD", "USDT", "Trading", "5"),

        /** kline이 503이다. */
        OUTAGE("DOWNUSDT", "DOWN", "USDT", "Trading", "6"),

        /** 종가가 0이다. */
        NON_POSITIVE("ZEROUSDT", "ZERO", "USDT", "Trading", "0"),

        /** kline이 2초 뒤에 답한다. 삭제·제한 경쟁을 재현한다. */
        SLOW("SLOWUSDT", "SLOW", "USDT", "Trading", "1.5"),

        /** 종가가 {@code 1E-24}다. 작은 수량과 곱하면 표현 범위 아래로 내려간다. */
        TINY("TINYUSDT", "TINY", "USDT", "Trading", "0.000000000000000000000001"),

        /**
         * 같은 candle이 두 번 오되 거래량만 다르다. 가격(종가)이 같으므로 모순도 새 revision도 아니다.
         * 모든 분에 candle이 있다.
         */
        VOLUME_ONLY("VOLUSDT", "VOL", "USDT", "Trading", "7"),

        /** 상품 정보가 503이다. 재시도 가능한 제공자 장애다. */
        INSTRUMENT_OUTAGE("IDOWNUSDT", "IDOWN", "USDT", "Trading", "1"),

        /** 같은 symbol이 서로 다른 상태로 두 번 나열된다. 모순된 답이다. */
        INSTRUMENT_DUPLICATE("IDUPUSDT", "IDUP", "USDT", "Trading", "1"),

        /** 목록이 비어 있지 않은데 요청한 symbol이 없다. 알 수 없는 답이지 "미상장"이 아니다. */
        INSTRUMENT_FOREIGN("IFORUSDT", "IFOR", "USDT", "Trading", "1");

        private final String symbol;
        private final String base;
        private final String quote;
        private final String status;
        private final @Nullable String close;

        MarketFixture(String symbol, String base, String quote, String status, @Nullable String close) {
            this.symbol = symbol;
            this.base = base;
            this.quote = quote;
            this.status = status;
            this.close = close;
        }

        String symbol() {
            return this.symbol;
        }

        static @Nullable MarketFixture of(@Nullable String symbol) {
            for (MarketFixture fixture : values()) {
                if (fixture.symbol.equals(symbol)) {
                    return fixture;
                }
            }
            return null;
        }
    }

    private static final long MINUTE_MILLIS = 60_000L;

    /** 시나리오와 UID를 가르는 문자. */
    private static final char KEY_SEPARATOR = '.';

    /**
     * 스텁이 내는 시나리오. <b>성공 둘, 거절 열.</b>
     *
     * <p>토큰이 곧 API Key의 앞부분이다. 목록에 없는 값은 인증 거절로 수렴하므로 "아무 키나
     * 통과한다"가 성립하지 않는다.
     */
    enum Scenario {

        /** UTA 2.0 메인 UID 읽기 전용 키. 정상 연결. */
        UTA2("uta2"),

        /** UTA Pro. 정상 연결과 동일하다. */
        UTA_PRO("utapro"),

        /** {@code readOnly} 플래그가 서 있지 않다. */
        WRITE_PERMISSION("write"),

        /**
         * 플래그는 읽기 전용인데 permission 배열이 비어 있지 않다.
         *
         * <p>{@link #WRITE_PERMISSION}과 갈라 두는 것이 요점이다. 그쪽은 {@code readOnly} 하나로
         * 걸리므로 허용 목록 판정에 도달하지 않고, 그래서 그 시나리오만으로는
         * {@code BybitAdapter.permissionVerdict}의 블록을 통째로 지워도 모든 시험이 통과한다.
         */
        PERMISSION_GRANTED("granted"),

        /** {@code permissions} 필드가 아예 없다. 모르는 모양은 낙관적으로 해석하지 않는다. */
        NO_PERMISSIONS("nopermissions"),

        /** 서브 UID 키다. */
        SUB_ACCOUNT("sub"),

        /** 저장된 메인 UID와 Worker 재조회 UID가 다르다. */
        UID_CHANGED("uidchanged"),

        /** UTA 1.0이다. 지원 목록에 없다. */
        LEGACY_UTA("uta1"),

        /** 알 수 없는 계정 모드다. 낙관적으로 해석하지 않는다. */
        UNKNOWN_MODE("unknownmode"),

        /** 운영 reserved IP가 묶여 있지 않다. */
        NO_IP("noip"),

        /** {@code ips}가 "전부 허용"이다. binding이 성립하지 않는다. */
        ANY_IP("anyip"),

        /** 지갑 조회가 실패한다. */
        WALLET_FAILURE("wallet"),

        /**
         * 지갑 결과가 둘이다. 단일 {@code UNIFIED}가 아니면 값을 쓸 수 없다.
         *
         * <p>아래 셋과 함께 {@code hasUsableUnifiedEquity}의 네 조건을 하나씩 깬다. fixture가
         * 없으면 {@code list.size() != 1}을 {@code isEmpty()}로 바꿔도 전부 통과한다.
         */
        WALLET_TWO_ENTRIES("wallettwo"),

        /** 지갑 결과의 계정 유형이 {@code UNIFIED}가 아니다. */
        WALLET_OTHER_TYPE("walletother"),

        /** {@code totalEquity}가 없다. 실패를 {@code 0 USDT}로 대체하지 않는다. */
        WALLET_NO_EQUITY("walletnoequity"),

        /** {@code totalEquity}가 canonical decimal string이 아니다(지수 표기). */
        WALLET_EXPONENT("walletexponent"),

        /** 제공자가 호출 빈도를 제한한다. */
        RATE_LIMIT("ratelimit"),

        /** 제공자가 응답하지 못한다. */
        OUTAGE("outage"),

        /** 응답이 JSON이 아니다. */
        MALFORMED("malformed"),

        /** lease 경쟁을 재현하는 느린 지갑 응답. */
        SLOW_WALLET("slowwallet"),

        /** 초기 사건 수집 도중 삭제·fencing 경쟁을 재현하는 느린 transaction-log 응답. */
        SLOW_COLLECTION("slowcollection"),

        /** 페이지네이션이 반복 cursor를 돌려주는 수집 오류. */
        REPEATED_CURSOR("repeatedcursor"),

        /** 두 번째 필수 type 조회에서 제공자 오류를 반환한다. */
        LOG_FAILURE("logfailure"),

        /** 필수 list 스키마 대신 원문 유사 fixture 값을 반환한다. */
        MALFORMED_LOG("malformedlog"),

        /** 지원하지 않는 사건 유형을 반환한다. */
        UNKNOWN_LOG_EVENT("unknownlogevent"),

        /** 같은 Bybit ID를 두 번의 수집에서 서로 다른 핵심 값으로 반환한다. */
        ID_CONFLICT("idconflict"),

        OVERSIZED_EVENT_ID("oversizedeventid"),
        OVERSIZED_CASH_FLOW("oversizedcashflow"),
        EXCESSIVE_CASH_FLOW_SCALE("excessivescale"),
        OUT_OF_WINDOW_EVENT("outofwindowevent"),
        OVER_LIMIT_PAGE("overlimitpage"),

        /** 저장 자격정보의 Worker 재조회에서 요청 시간초과를 재현한다. */
        TIMEOUT("timeout"),

        /** 정상 0 현재가치. */
        ZERO_EQUITY("zeroequity"),

        /** 정상 음수 현재가치. */
        NEGATIVE_EQUITY("negativeequity"),

        /** 측정 시각이 없다. */
        BAD_MEASURED_TIME("badtime"),

        /** 제공자가 자격정보를 거절한다. */
        AUTH_FAILURE("authfail"),

        /**
         * 비-USDT 외부 자금흐름을 내는 수집(Story 2.6 e2e). {@code BTC} 입금은 추정 환산되고
         * {@code HALT} 출금은 상장돼 있지만 거래 중이 아니어서 환산 보류로 남는다.
         */
        MARKET_FLOWS("marketflows"),

        /**
         * 계산 가능한 추정 환산 하나를 내는 수집(Story 2.7 e2e). {@code BTC} 입금 하나뿐이라 사건 분이
         * 닫히면 환산 묶음이 {@code CONVERTED_ESTIMATED}로 계산 가능해지고, 최초 발행의 다섯 지표가
         * {@code 추정}이 된다. 현재가치는 그대로 공식이다.
         */
        BTC_DEPOSIT("btcdeposit"),

        /**
         * 발행 가능한 USDT 입출금 스물셋을 내는 수집(Story 2.10 e2e). 입금 열여덟(`10.005 USDT`)과 출금
         * 다섯(`-2.5 USDT`, 네 번째마다)이며 앞의 둘은 같은 밀리초라 `source_id` 순서가 그 둘을 가른다.
         * 전부 직접 정규화라 시세 호출 없이 환산되고, 한 페이지(20행)를 넘어 `더 보기`가 생긴다.
         */
        ADJUSTMENTS("adjustments"),

        /**
         * 시간이 흐르며 입금이 쌓이는 수집(Story 2.11 e2e). UID마다 첫 지갑 조회 시각이 기준이고, 기준 뒤 10초마다
         * `+10 USDT` 입금이 하나씩(`incremental-01`…`incremental-48`, 8분 동안) 생긴다. 느린 준비에도 최초 발행 뒤에
         * 입금이 남는다. 한 type의 한 페이지(50행)에 모두 들어간다. 요청 창 안이면서 이미 지난
         * 사건만 내고, 지갑 잔액은 `1000 + 10 × 지난 사건 수`다. 그래서 최초 발행 뒤 잠시 기다려 동기화하면 새 입금과
         * 새 현재가치가 함께 들어온다. 전부 직접 정규화라 시세 호출이 없다.
         */
        INCREMENTAL("incremental");

        private final String token;

        Scenario(String token) {
            this.token = token;
        }

        /** API Key 앞부분에 쓰는 값. 검증 스크립트와 시험이 같은 문자열을 만든다. */
        String token() {
            return this.token;
        }

        static @Nullable Scenario of(String token) {
            for (Scenario scenario : values()) {
                if (scenario.token.equals(token)) {
                    return scenario;
                }
            }
            return null;
        }
    }

    /** 스텁이 내는 응답 하나. */
    record Response(int status, String contentType, String body) {}

    private static final String JSON = "application/json";

    private static final String TEXT = "text/plain;charset=UTF-8";

    /** 이 스텁이 {@code ips}에 싣는 운영 reserved IP. 어댑터가 요구하는 값과 같아야 한다. */
    private final String reservedIp;
    private final AtomicInteger conflictTransferInReads = new AtomicInteger();
    /** {@link Scenario#INCREMENTAL}의 UID별 기준 시각(첫 지갑 조회). */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> incrementalBase =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong lastWalletEnvelopeTime = new AtomicLong();

    /**
     * 서버 시간 응답의 모양. 서버 시간에는 키도 symbol도 없어 선택자가 없으므로 시험 표면이 이 값을
     * 바꾼다({@code BybitStubConfiguration}). 개발 스택은 바꾸지 않으며 기본값은 정상 응답이다.
     */
    enum ServerTimeMode { NORMAL, TIMEOUT, OUTAGE, MALFORMED }

    private final java.util.concurrent.atomic.AtomicReference<ServerTimeMode> serverTimeMode =
            new java.util.concurrent.atomic.AtomicReference<>(ServerTimeMode.NORMAL);

    void serverTimeMode(ServerTimeMode mode) {
        this.serverTimeMode.set(mode);
    }

    DevBybitStub(String reservedIp) {
        this.reservedIp = reservedIp;
    }

    /** 그 시나리오의 API Key. 시험과 검증 스크립트가 같은 모양을 만든다. */
    static String apiKey(Scenario scenario, String uid) {
        return scenario.token() + KEY_SEPARATOR + uid;
    }

    /**
     * 요청 하나에 답한다.
     *
     * @param path 위 세 상수 중 하나
     * @param headers 요청 헤더. 이름 비교는 대소문자를 접는다
     * @param query 쿼리 파라미터. 지갑 조회의 {@code accountType}을 본다
     */
    Response handle(String path, Map<String, String> headers, Map<String, String> query) {
        if (SERVER_TIME_PATH.equals(path)) {
            switch (this.serverTimeMode.get()) {
                case TIMEOUT -> {
                    try {
                        Thread.sleep(3500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                case OUTAGE -> {
                    return new Response(503, JSON, "{\"retCode\":10016,\"retMsg\":\"\"}");
                }
                case MALFORMED -> {
                    return ok("{\"timeSecond\":\"soon\",\"timeNano\":\"not-a-time\"}");
                }
                case NORMAL -> { }
            }
            long millis=System.currentTimeMillis();
            return ok("{\"timeSecond\":\""+(millis/1000)+"\",\"timeNano\":\""+(millis*1_000_000L)+"\"}");
        }
        // 공개 시세는 키 검사 앞에서 답한다. 실제 제공자도 이 두 경로에 서명을 요구하지 않는다.
        if (INSTRUMENTS_INFO_PATH.equals(path)) {
            return instruments(query);
        }
        if (KLINE_PATH.equals(path)) {
            return kline(query);
        }
        String apiKey = header(headers, "X-BAPI-API-KEY");
        if (apiKey == null
                || header(headers, "X-BAPI-SIGN") == null
                || header(headers, "X-BAPI-TIMESTAMP") == null
                || header(headers, "X-BAPI-RECV-WINDOW") == null) {
            // 서명된 요청의 모양이 아니다. 제공자가 자격정보를 거절하는 것과 같은 답으로 접는다.
            return error(10003);
        }
        int separator = apiKey.indexOf(KEY_SEPARATOR);
        if (separator <= 0 || separator == apiKey.length() - 1) {
            return error(10003);
        }
        Scenario scenario = Scenario.of(apiKey.substring(0, separator).toLowerCase(Locale.ROOT));
        String uid = apiKey.substring(separator + 1);
        if (scenario == null) {
            return error(10003);
        }

        if (((scenario == Scenario.SLOW_WALLET || scenario == Scenario.TIMEOUT)
                && WALLET_BALANCE_PATH.equals(path))
                || (scenario == Scenario.SLOW_COLLECTION && TRANSACTION_LOG_PATH.equals(path))) {
            try {
                Thread.sleep(scenario == Scenario.TIMEOUT ? 3500 : 2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return error(10016);
            }
        }
        return switch (path) {
            case QUERY_API_PATH -> queryApi(scenario, uid);
            case ACCOUNT_INFO_PATH -> accountInfo(scenario);
            case WALLET_BALANCE_PATH -> walletBalance(scenario, uid, query);
            case TRANSACTION_LOG_PATH -> transactionLog(scenario, uid, query);
            default -> new Response(404, JSON, "{\"retCode\":10001,\"retMsg\":\"\",\"result\":{}}");
        };
    }

    private Response transactionLog(Scenario scenario,String uid,Map<String,String> query) {
        String type=query.get("type");
        if (!"UNIFIED".equals(query.get("accountType"))
                ||!("TRANSFER_IN".equals(type)||"TRANSFER_OUT".equals(type))) return error(10001);
        if (scenario==Scenario.LOG_FAILURE&&"TRANSFER_OUT".equals(type)) return error(10016);
        if (scenario==Scenario.REPEATED_CURSOR&&"TRANSFER_IN".equals(type))
            return ok("{\"list\":[],\"nextPageCursor\":\"loop\"}");
        if (scenario==Scenario.MALFORMED_LOG&&"TRANSFER_IN".equals(type))
            return ok("{\"list\":\"fixture-payload-must-not-escape\",\"nextPageCursor\":\"\"}");
        if (scenario==Scenario.UNKNOWN_LOG_EVENT&&"TRANSFER_IN".equals(type)) {
            long start=Long.parseLong(query.get("startTime"));
            return ok("{\"list\":[{\"id\":\"unknown-event-id\",\"type\":\"INTERNAL_SETTLEMENT\","
                    +"\"currency\":\"USDT\",\"cashFlow\":\"1.000\",\"transactionTime\":\""
                    +(start+1)+"\"}],\"nextPageCursor\":\"\"}");
        }
        if (scenario==Scenario.OVERSIZED_EVENT_ID&&"TRANSFER_IN".equals(type))
            return eventPage("삼".repeat(171),"TRANSFER_IN","1.00",query,"start+1");
        if (scenario==Scenario.OVERSIZED_CASH_FLOW&&"TRANSFER_IN".equals(type))
            return eventPage("large-cash-flow","TRANSFER_IN","9".repeat(129),query,"start+1");
        if (scenario==Scenario.EXCESSIVE_CASH_FLOW_SCALE&&"TRANSFER_IN".equals(type))
            return eventPage("wide-scale","TRANSFER_IN","0."+"1".repeat(19),query,"start+1");
        if (scenario==Scenario.OUT_OF_WINDOW_EVENT&&"TRANSFER_IN".equals(type))
            return eventPage("out-of-window","TRANSFER_IN","1.00",query,"start");
        if (scenario==Scenario.OVER_LIMIT_PAGE&&"TRANSFER_IN".equals(type)) {
            long eventAt=Long.parseLong(query.get("startTime"))+1;
            StringBuilder items=new StringBuilder("[");
            for(int i=0;i<51;i++) {
                if(i>0) items.append(',');
                items.append("{\"id\":\"limit-").append(i).append("\",\"type\":\"TRANSFER_IN\","
                        +"\"currency\":\"USDT\",\"cashFlow\":\"1\",\"transactionTime\":\"")
                        .append(eventAt).append("\"}");
            }
            return ok("{\"list\":"+items+"],\"nextPageCursor\":\"\"}");
        }
        if (scenario==Scenario.MARKET_FLOWS) {
            long start=Long.parseLong(query.get("startTime"));
            return "TRANSFER_IN".equals(type)
                    ? ok("{\"list\":[{\"id\":\"market-btc-in\",\"type\":\"TRANSFER_IN\",\"currency\":\"BTC\","
                            +"\"cashFlow\":\"0.01\",\"transactionTime\":\""+(start+1)+"\"}],\"nextPageCursor\":\"\"}")
                    : ok("{\"list\":[{\"id\":\"market-halt-out\",\"type\":\"TRANSFER_OUT\",\"currency\":\"HALT\","
                            +"\"cashFlow\":\"-2.5\",\"transactionTime\":\""+(start+2)+"\"}],\"nextPageCursor\":\"\"}");
        }
        if (scenario==Scenario.BTC_DEPOSIT&&"TRANSFER_IN".equals(type)) {
            long start=Long.parseLong(query.get("startTime"));
            return ok("{\"list\":[{\"id\":\"btc-deposit-in\",\"type\":\"TRANSFER_IN\",\"currency\":\"BTC\","
                    +"\"cashFlow\":\"0.01\",\"transactionTime\":\""+(start+1)+"\"}],\"nextPageCursor\":\"\"}");
        }
        if (scenario==Scenario.ADJUSTMENTS) {
            String end=query.get("endTime");
            return adjustmentsPage(type,Long.parseLong(query.get("startTime")),
                    end==null?Long.MAX_VALUE:Long.parseLong(end));
        }
        if (scenario==Scenario.INCREMENTAL) {
            String end=query.get("endTime");
            return incrementalPage(uid,type,Long.parseLong(query.get("startTime")),
                    end==null?Long.MAX_VALUE:Long.parseLong(end));
        }
        if (scenario==Scenario.ID_CONFLICT&&"TRANSFER_IN".equals(type)) {
            int read=conflictTransferInReads.incrementAndGet();
            String flow=read==1?"1.000":"2.000";
            long start=Long.parseLong(query.get("startTime"));
            return ok("{\"list\":[{\"id\":\"stable-event-id\",\"type\":\"TRANSFER_IN\","
                    +"\"currency\":\"USDT\",\"cashFlow\":\""+flow+"\",\"transactionTime\":\""
                    +(start+1)+"\"}],\"nextPageCursor\":\"\"}");
        }
        return ok("{\"list\":[],\"nextPageCursor\":\"\"}");
    }

    /**
     * How many flows {@link Scenario#ADJUSTMENTS} emits. The e2e does not read this constant: it hard-codes the
     * counts and totals that follow from it (23 rows, 18 deposits, 5 withdrawals), so a change here changes both.
     */
    static final int ADJUSTMENT_FLOWS = 23;

    /**
     * {@link Scenario#ADJUSTMENTS}: {@code adjustment-01}…{@code adjustment-23}, every fourth a withdrawal of
     * {@code -2.5 USDT} and the rest deposits of {@code 10.005 USDT}. The first two share one millisecond
     * ({@code start+1}); flow {@code i} otherwise sits at {@code start+i-1}, so the calculation's order is
     * the numbering. One page per type: both lists are well under the 50-row limit.
     *
     * <p>The rows are emitted only when all of them fit inside the request's {@code [startTime, endTime]}
     * window; a window too short for them gets none, so the same ids never come back at other times in a window
     * they do not fit (that would be an id conflict). Each fitting window places them relative to its own start:
     * the scenario models one collection window, as the e2e's range of a few seconds gives.
     */
    private static Response adjustmentsPage(String type,long start,long end) {
        StringBuilder items=new StringBuilder("[");
        if (start+Math.max(1,ADJUSTMENT_FLOWS-1)>end) return ok("{\"list\":[],\"nextPageCursor\":\"\"}");
        for (int i=1;i<=ADJUSTMENT_FLOWS;i++) {
            boolean withdrawal=i%4==0;
            if (withdrawal!="TRANSFER_OUT".equals(type)) continue;
            if (items.length()>1) items.append(',');
            items.append("{\"id\":\"adjustment-").append(String.format(Locale.ROOT,"%02d",i))
                    .append("\",\"type\":\"").append(type).append("\",\"currency\":\"USDT\",\"cashFlow\":\"")
                    .append(withdrawal?"-2.5":"10.005").append("\",\"transactionTime\":\"")
                    .append(start+Math.max(1,i-1)).append("\"}");
        }
        return ok("{\"list\":"+items+"],\"nextPageCursor\":\"\"}");
    }

    /** How many deposits {@link Scenario#INCREMENTAL} emits, and how far apart. */
    static final int INCREMENTAL_FLOWS = 48;
    static final long INCREMENTAL_SPACING_MILLIS = 10_000L;

    /**
     * {@link Scenario#INCREMENTAL}: the deposits of this UID inside {@code [startTime, endTime]} that are already in
     * the past. Nothing before the UID's first wallet read.
     */
    private Response incrementalPage(String uid,String type,long start,long end) {
        Long base=incrementalBase.get(uid);
        if (base==null||!"TRANSFER_IN".equals(type)) return ok("{\"list\":[],\"nextPageCursor\":\"\"}");
        long now=System.currentTimeMillis();
        StringBuilder items=new StringBuilder("[");
        for (int k=1;k<=INCREMENTAL_FLOWS;k++) {
            long at=base+INCREMENTAL_SPACING_MILLIS*k;
            if (at<start||at>end||at>now) continue;
            if (items.length()>1) items.append(',');
            items.append("{\"id\":\"incremental-").append(String.format(Locale.ROOT,"%02d",k))
                    .append("\",\"type\":\"TRANSFER_IN\",\"currency\":\"USDT\",\"cashFlow\":\"10\",")
                    .append("\"transactionTime\":\"").append(at).append("\"}");
        }
        return ok("{\"list\":"+items+"],\"nextPageCursor\":\"\"}");
    }

    /** {@link Scenario#INCREMENTAL}'s balance: 1000 USDT plus 10 for every deposit already in the past. */
    private String incrementalEquity(String uid) {
        long base=incrementalBase.computeIfAbsent(uid,ignored->System.currentTimeMillis());
        long passed=Math.min(INCREMENTAL_FLOWS,Math.max(0,(System.currentTimeMillis()-base)/INCREMENTAL_SPACING_MILLIS));
        return String.valueOf(1000+10*passed);
    }

    private static Response eventPage(String id,String type,String flow,Map<String,String> query,String timeKind) {
        long start=Long.parseLong(query.get("startTime"));
        long eventAt=timeKind.equals("start")?start:start+1;
        return ok("{\"list\":[{\"id\":\""+id+"\",\"type\":\""+type+"\","
                +"\"currency\":\"USDT\",\"cashFlow\":\""+flow+"\",\"transactionTime\":\""
                +eventAt+"\"}],\"nextPageCursor\":\"\"}");
    }

    /** spot 상품 정보. symbol을 응답에 되돌려 싣지 않는다 — fixture의 값만 나간다. */
    private static Response instruments(Map<String, String> query) {
        if (!"spot".equals(query.get("category"))) {
            return error(10001);
        }
        MarketFixture fixture = MarketFixture.of(query.get("symbol"));
        if (fixture == MarketFixture.INSTRUMENT_OUTAGE) {
            return new Response(503, JSON, "{\"retCode\":10016,\"retMsg\":\"\"}");
        }
        String list;
        if (fixture == null) {
            list = "[]";
        } else if (fixture == MarketFixture.INSTRUMENT_DUPLICATE) {
            list = "[" + instrument(fixture.symbol, fixture, "Trading") + ","
                    + instrument(fixture.symbol, fixture, "Closed") + "]";
        } else if (fixture == MarketFixture.INSTRUMENT_FOREIGN) {
            list = "[" + instrument(fixture.base + "USDC", fixture, "Trading") + "]";
        } else {
            list = "[" + instrument(fixture.symbol, fixture, fixture.status) + "]";
        }
        return ok("{\"category\":\"spot\",\"list\":" + list + "}");
    }

    private static String instrument(String symbol, MarketFixture fixture, String status) {
        return "{\"symbol\":\"" + symbol + "\",\"baseCoin\":\"" + fixture.base + "\",\"quoteCoin\":\""
                + fixture.quote + "\",\"innovation\":\"0\",\"status\":\"" + status + "\"}";
    }

    /** 한 번의 kline 요청이 받을 수 있는 최대 candle 수. 실제 제공자의 상한과 같다. */
    private static final int MAX_KLINE_LIMIT = 1000;

    /**
     * spot 1분봉. 최신 candle이 앞에 온다(실제 제공자의 정렬).
     *
     * <p>현재 시각보다 뒤의 분은 없고, 진행 중인 현재 분은 있다. 개발·CI 스택에서 이 경로는
     * 인증 없이 열려 있으므로 요청 크기를 닫는다 — {@code limit}은 1~1000, {@code start <= end},
     * 그리고 창 전체가 {@code limit} 안에 들어야 한다. 밖이면 오류 envelope이다. 중복 행을 싣는
     * fixture도 그 상한을 넘지 않는다.
     */
    private static Response kline(Map<String, String> query) {
        MarketFixture fixture = MarketFixture.of(query.get("symbol"));
        if (!"spot".equals(query.get("category")) || !"1".equals(query.get("interval")) || fixture == null) {
            return error(10001);
        }
        long start;
        long end;
        int limit;
        try {
            start = Long.parseLong(query.get("start"));
            end = Long.parseLong(query.get("end"));
            limit = Integer.parseInt(query.getOrDefault("limit", "200"));
        } catch (RuntimeException invalid) {
            return error(10001);
        }
        if (limit < 1 || limit > MAX_KLINE_LIMIT || start < 0 || start > end
                || Math.floorDiv(end, MINUTE_MILLIS) - Math.floorDiv(start + MINUTE_MILLIS - 1, MINUTE_MILLIS) + 1 > limit) {
            return error(10001);
        }
        if (fixture == MarketFixture.OUTAGE) {
            return new Response(503, JSON, "{\"retCode\":10016,\"retMsg\":\"\"}");
        }
        if (fixture == MarketFixture.SLOW) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return error(10016);
            }
        }
        long current = Math.floorDiv(System.currentTimeMillis(), MINUTE_MILLIS) * MINUTE_MILLIS;
        long first = Math.floorDiv(start + MINUTE_MILLIS - 1, MINUTE_MILLIS) * MINUTE_MILLIS;
        StringBuilder rows = new StringBuilder("[");
        int count = 0;
        for (long at = Math.min(Math.floorDiv(end, MINUTE_MILLIS) * MINUTE_MILLIS, current);
                at >= first && count < limit && fixture.close != null;
                at -= MINUTE_MILLIS) {
            if (fixture == MarketFixture.GAP && Math.floorDiv(at, MINUTE_MILLIS) % 10 != 0) {
                continue;
            }
            if (count > 0) {
                rows.append(',');
            }
            if (fixture == MarketFixture.MALFORMED && count == 0) {
                rows.append("[\"").append(at).append("\",\"5\",\"5\",\"5\",\"5\",\"1\"]");
            } else {
                rows.append(candle(at, fixture.close, "1"));
            }
            count++;
            // The duplicate row counts toward the cap like any other row.
            if ((fixture == MarketFixture.CONFLICT || fixture == MarketFixture.VOLUME_ONLY) && count < limit) {
                rows.append(',').append(fixture == MarketFixture.CONFLICT
                        ? candle(at, "4.1", "1") : candle(at, fixture.close, "2"));
                count++;
            }
        }
        rows.append(']');
        return ok("{\"symbol\":\"" + fixture.symbol + "\",\"category\":\"spot\",\"list\":" + rows + "}");
    }

    private static String candle(long startMillis, String close, String volume) {
        return "[\"" + startMillis + "\",\"" + close + "\",\"" + close + "\",\"" + close + "\",\"" + close
                + "\",\"" + volume + "\",\"" + close + "\"]";
    }

    void resetScenarioState() {
        conflictTransferInReads.set(0);
        incrementalBase.clear();
        serverTimeMode.set(ServerTimeMode.NORMAL);
    }
    long lastWalletEnvelopeTime() { return lastWalletEnvelopeTime.get(); }

    private Response queryApi(Scenario scenario, String uid) {
        return switch (scenario) {
            case AUTH_FAILURE -> error(10003);
            case RATE_LIMIT -> new Response(429, JSON, "{\"retCode\":10006,\"retMsg\":\"\"}");
            case OUTAGE -> new Response(503, JSON, "{\"retCode\":10016,\"retMsg\":\"\"}");
            case MALFORMED -> new Response(200, TEXT, "not json");
            default ->
                    ok(
                            "{\"readOnly\":"
                                    + (scenario == Scenario.WRITE_PERMISSION ? 0 : 1)
                                    + permissionsField(scenario)
                                    + ",\"ips\":"
                                    + ips(scenario)
                                    + ",\"isMaster\":"
                                    + (scenario == Scenario.SUB_ACCOUNT ? "false" : "true")
                                    + ",\"parentUid\":\""
                                    + (scenario == Scenario.SUB_ACCOUNT ? "7" : "0")
                                    + "\",\"userID\":\""
                                    + (scenario == Scenario.UID_CHANGED ? uid + "-changed" : uid)
                                    + "\"}");
        };
    }

    private static Response accountInfo(Scenario scenario) {
        int mode =
                switch (scenario) {
                    case UTA_PRO -> 6;
                    case LEGACY_UTA -> 3;
                    case UNKNOWN_MODE -> 99;
                    default -> 5;
                };
        return ok("{\"unifiedMarginStatus\":" + mode + ",\"marginMode\":\"REGULAR_MARGIN\"}");
    }

    private Response walletBalance(Scenario scenario, String uid, Map<String, String> query) {
        if (!"UNIFIED".equals(query.get("accountType"))) {
            // 어댑터는 이 값 하나만 묻는다. 다른 값이 오면 흐름이 계약을 벗어났다는 뜻이다.
            return error(10001);
        }
        if (scenario == Scenario.WALLET_FAILURE) {
            return error(10016);
        }
        if (scenario == Scenario.INCREMENTAL) {
            String equity = incrementalEquity(uid);
            long envelopeTime=System.currentTimeMillis();
            lastWalletEnvelopeTime.set(envelopeTime);
            return ok("{\"list\":[{\"accountType\":\"UNIFIED\",\"totalEquity\":\"" + equity
                    + "\",\"accountIMRate\":\"0\"}]}", envelopeTime);
        }
        long envelopeTime=System.currentTimeMillis();
        lastWalletEnvelopeTime.set(scenario==Scenario.BAD_MEASURED_TIME?0:envelopeTime);
        return scenario == Scenario.BAD_MEASURED_TIME
                ? new Response(200, JSON, "{\"retCode\":0,\"result\":{\"list\":" + walletList(scenario) + "},\"time\":0}")
                : ok("{\"list\":" + walletList(scenario) + "}",envelopeTime);
    }

    /**
     * 그 시나리오의 {@code permissions} 필드. <b>없는 시나리오에서는 필드 자체가 없다.</b>
     *
     * <p>정상 키는 모든 배열이 비어 있다. 그것이 읽기 전용 키의 모양이고, 어댑터의 허용 목록이
     * 비어 있는 동안 통과하는 유일한 모양이다({@code BybitAdapter.READ_ONLY_PERMISSION_GROUPS}).
     */
    private static String permissionsField(Scenario scenario) {
        if (scenario == Scenario.NO_PERMISSIONS) {
            return "";
        }
        String spot = scenario == Scenario.PERMISSION_GRANTED ? "[\"SpotTrade\"]" : "[]";
        return ",\"permissions\":{\"ContractTrade\":[],\"Spot\":" + spot
                + ",\"Wallet\":[],\"Options\":[],\"Derivatives\":[],\"Exchange\":[],\"NFT\":[]}";
    }

    /**
     * 그 시나리오의 지갑 목록.
     *
     * <p>네 갈래가 {@code hasUsableUnifiedEquity}의 네 조건을 하나씩 깬다 — 항목 수, 계정 유형,
     * {@code totalEquity}의 존재, 그리고 그 값이 canonical decimal string인가.
     */
    private static String walletList(Scenario scenario) {
        return switch (scenario) {
            case WALLET_TWO_ENTRIES ->
                    "[{\"accountType\":\"UNIFIED\",\"totalEquity\":\"1234.56\"},"
                            + "{\"accountType\":\"UNIFIED\",\"totalEquity\":\"1.00\"}]";
            case WALLET_OTHER_TYPE -> "[{\"accountType\":\"CONTRACT\",\"totalEquity\":\"1234.56\"}]";
            case WALLET_NO_EQUITY -> "[{\"accountType\":\"UNIFIED\",\"accountIMRate\":\"0\"}]";
            // 지수 표기다. `BigDecimal`은 받아들이지만 우리 경계는 받아들이지 않는다.
            case WALLET_EXPONENT -> "[{\"accountType\":\"UNIFIED\",\"totalEquity\":\"1E+3\"}]";
            case ZERO_EQUITY -> "[{\"accountType\":\"UNIFIED\",\"totalEquity\":\"0\"}]";
            case NEGATIVE_EQUITY -> "[{\"accountType\":\"UNIFIED\",\"totalEquity\":\"-12.345678901234567890\"}]";
            default ->
                    "[{\"accountType\":\"UNIFIED\",\"totalEquity\":\"1234.56\","
                            + "\"accountIMRate\":\"0\"}]";
        };
    }

    private String ips(Scenario scenario) {
        return switch (scenario) {
            case NO_IP -> "[\"198.51.100.7\"]";
            case ANY_IP -> "[\"*\"]";
            default -> "[\"" + this.reservedIp + "\"]";
        };
    }

    private static Response ok(String result) {
        return ok(result,System.currentTimeMillis());
    }

    private static Response ok(String result,long envelopeTime) {
        return new Response(
                200, JSON, "{\"retCode\":0,\"retMsg\":\"OK\",\"result\":" + result + ",\"time\":" + envelopeTime + "}");
    }

    /** 제공자 오류 응답. 메시지를 비운다 — 어댑터가 그 문장을 나르지 않는다는 계약의 짝이다. */
    private static Response error(int retCode) {
        return new Response(
                200, JSON, "{\"retCode\":" + retCode + ",\"retMsg\":\"\",\"result\":{},\"time\":0}");
    }

    private static @Nullable String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                String value = entry.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }
}
