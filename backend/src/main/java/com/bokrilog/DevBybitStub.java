package com.bokrilog;

import java.util.Locale;
import java.util.Map;
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

        /** 저장 자격정보의 Worker 재조회에서 요청 시간초과를 재현한다. */
        TIMEOUT("timeout"),

        /** 정상 0 현재가치. */
        ZERO_EQUITY("zeroequity"),

        /** 정상 음수 현재가치. */
        NEGATIVE_EQUITY("negativeequity"),

        /** 측정 시각이 없다. */
        BAD_MEASURED_TIME("badtime"),

        /** 제공자가 자격정보를 거절한다. */
        AUTH_FAILURE("authfail");

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

        if ((scenario == Scenario.SLOW_WALLET || scenario == Scenario.TIMEOUT)
                && WALLET_BALANCE_PATH.equals(path)) {
            try {
                Thread.sleep(scenario == Scenario.TIMEOUT ? 3500 : 1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return error(10016);
            }
        }
        return switch (path) {
            case QUERY_API_PATH -> queryApi(scenario, uid);
            case ACCOUNT_INFO_PATH -> accountInfo(scenario);
            case WALLET_BALANCE_PATH -> walletBalance(scenario, query);
            default -> new Response(404, JSON, "{\"retCode\":10001,\"retMsg\":\"\",\"result\":{}}");
        };
    }

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

    private static Response walletBalance(Scenario scenario, Map<String, String> query) {
        if (!"UNIFIED".equals(query.get("accountType"))) {
            // 어댑터는 이 값 하나만 묻는다. 다른 값이 오면 흐름이 계약을 벗어났다는 뜻이다.
            return error(10001);
        }
        if (scenario == Scenario.WALLET_FAILURE) {
            return error(10016);
        }
        return scenario == Scenario.BAD_MEASURED_TIME
                ? new Response(200, JSON, "{\"retCode\":0,\"result\":{\"list\":" + walletList(scenario) + "},\"time\":0}")
                : ok("{\"list\":" + walletList(scenario) + "}");
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
        return new Response(
                200, JSON, "{\"retCode\":0,\"retMsg\":\"OK\",\"result\":" + result + ",\"time\":" + System.currentTimeMillis() + "}");
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
