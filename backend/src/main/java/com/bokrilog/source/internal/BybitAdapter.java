package com.bokrilog.source.internal;

import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceRejectionReason;
import com.bokrilog.source.SourceVerification;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bybit UTA 연결 admission. <b>세 엔드포인트를 순서대로 지나고 전부 통과해야 승인한다.</b>
 *
 * <p>순서가 계약이다 — {@code GET /v5/user/query-api} → {@code GET /v5/account/info} →
 * {@code GET /v5/account/wallet-balance?accountType=UNIFIED}. 앞 호출이 거절하면 뒤 호출을 보내지
 * 않는다. 키의 성질을 먼저 확인하기 전에 잔고를 묻는 것은 거절될 자격정보로 제공자를 한 번 더
 * 두드리는 일이다.
 *
 * <p><b>자동 재시도가 없다.</b> 시간초과·429·5xx를 포함해 한 번 실패하면 그대로 거절이며, 다시
 * 시작하는 것은 사용자의 조작이다. {@code ERD-1.0.0} §2.5의 3회 backoff는 sync page 수집의 계약이고
 * admission에는 적용하지 않는다(AC-5·AD-21, SPEC의 Design Notes가 그 범위를 못 박았다).
 *
 * <p><b>모르는 값과 새 필드는 거부한다.</b> 허용 목록을 이 클래스가 소유하고 목록에 없으면 닫는다.
 * 낙관적 해석을 하지 않으므로 "아마 읽기 전용일 것"이라는 경로가 없다.
 *
 * <p><b>응답 원문이 이 클래스를 떠나지 않는다.</b> 돌려주는 것은 provider-neutral DTO 하나이고
 * ({@link SourceVerification}) 지갑 값·permission 배열·제공자 메시지는 화면에도 로그에도 실리지
 * 않는다. 실패도 마찬가지다 — 이유는 닫힌 enum 하나이며 제공자가 쓴 문장을 나르지 않는다.
 *
 * <p>구성이 비면 이 어댑터는 {@link SourceRejectionReason#UNAVAILABLE}만 돌려준다. 기동은
 * 성공한다 — 제공자 등록과 같은 관용이며 어느 값이 비었는지는 응답에도 로그에도 남기지 않는다.
 */
@Component
class BybitAdapter {

    /**
     * 이 라운드가 적용하는 연결 정책의 버전.
     *
     * <p>저장되는 값이다. 정책이 바뀌어도 이미 저장된 연결이 어느 규칙을 지났는지 남아야 하고,
     * 화면이 "적용 정책 버전"으로 그 값을 그대로 보여 준다.
     */
    static final String POLICY_VERSION = "bybit-readonly-uta-1.0";

    /** 첫 호출. 키 자체의 성질을 묻는다. */
    static final String QUERY_API_PATH = "/v5/user/query-api";

    /** 둘째 호출. 계정 모드를 묻는다. */
    static final String ACCOUNT_INFO_PATH = "/v5/account/info";

    /** 셋째 호출. 실제 지갑 조회가 성립하는지 본다. */
    static final String WALLET_BALANCE_PATH = "/v5/account/wallet-balance";

    /** 셋째 호출의 쿼리. 이 계정 유형 하나만 묻는다. */
    static final String WALLET_BALANCE_QUERY = "accountType=UNIFIED";

    /** 지갑 응답에서 우리가 받아들이는 유일한 계정 유형. */
    private static final String UNIFIED_ACCOUNT_TYPE = "UNIFIED";

    /**
     * 지원하는 계정 모드.
     *
     * <p>UTA 2.0({@code 5})과 UTA Pro({@code 6})뿐이다. Classic({@code 1})·UTA 1.0({@code 3}·
     * {@code 4})과 알 수 없는 값은 목록에 없으므로 전부 거절이다 — 이 스토리의 Never가 그 셋을
     * 지원 목록에 넣는 것을 금지한다.
     */
    private static final Set<Integer> SUPPORTED_ACCOUNT_MODES = Set.of(5, 6);

    /**
     * 서브 계정이 아님을 뜻하는 {@code parentUid}.
     *
     * <p>문자열로 비교한다. 제공자가 숫자로 보내든 문자열로 보내든 {@code JsonNode.asString()}이
     * 같은 표현을 주고, 숫자로 파싱하면 그 과정에서 모르는 모양이 조용히 0이 될 자리가 생긴다.
     */
    private static final String MAIN_ACCOUNT_PARENT_UID = "0";

    /**
     * 키에 묶인 IP를 "전부 허용"으로 여는 값.
     *
     * <p>이 값이 있으면 reserved IP가 목록에 있어도 실제로는 아무 IP나 통과한다. 그래서 허용이
     * 아니라 <b>거절</b>의 근거다 — IP binding을 요구하는 계약(AR-21)이 그것으로 무르면 안 된다.
     */
    private static final String ANY_IP = "*";

    /**
     * 읽기 전용 키에서 <b>비어 있지 않아도 되는</b> permission 그룹.
     *
     * <p><b>지금은 비어 있다.</b> 어느 계획 문서도 Bybit permission 문자열을 열거하지 않으며, 그
     * 공백은 API 스파이크가 채운다(SPEC의 Ask First). 목록을 지어내는 대신 구조와 fail-closed
     * 판정을 먼저 두었고, 비어 있는 동안의 뜻은 정확히 "모든 permission 배열이 비어 있어야 한다"
     * 이다 — 읽기 전용 키의 모양이며 이 스토리가 요구하는 것과 같다.
     *
     * <p>스파이크가 값을 채우면 그 그룹만 비어 있지 않아도 되고 나머지는 그대로 닫힌다.
     */
    private static final Set<String> READ_ONLY_PERMISSION_GROUPS = Set.of();

    /**
     * 조회에 <b>반드시 있어야 하는</b> permission 그룹.
     *
     * <p>같은 이유로 지금은 비어 있다. 값이 채워지면 그 그룹이 없거나 비어 있을 때
     * {@link SourceRejectionReason#INSUFFICIENT_PERMISSION}으로 거절한다. 판정 구조를 먼저 두는
     * 이유는 목록이 채워지는 날 그것을 쓰는 코드까지 함께 만들지 않기 위해서다.
     */
    private static final Set<String> REQUIRED_PERMISSION_GROUPS = Set.of();

    /**
     * 제공자 오류 코드 → 거절 이유.
     *
     * <p><b>지금은 비어 있다.</b> 위 두 목록과 같은 근거다 — 어느 계획 문서도 Bybit의
     * {@code retCode} 값을 열거하지 않으므로 지어내지 않는다. 비어 있는 동안 {@code retCode != 0}은
     * 호출 자리가 정한 기본값으로 접히며, 그 기본값은 "제공자가 이 자격정보를 거절했다"와 "지갑
     * 조회가 성립하지 않았다" 둘뿐이다.
     */
    private static final Map<Integer, SourceRejectionReason> RET_CODE_REASONS = Map.of();

    /** 자격정보 문자열의 길이 상한(바이트). 질의에 실어도 되는 크기인가만 본다. */
    private static final int MAX_CREDENTIAL_BYTES = 256;

    /** 제공자가 요구하는 요청 유효 창(밀리초). 서명에 그대로 든다. */
    private static final String RECV_WINDOW = "5000";

    private static final String HEADER_API_KEY = "X-BAPI-API-KEY";

    private static final String HEADER_TIMESTAMP = "X-BAPI-TIMESTAMP";

    private static final String HEADER_RECV_WINDOW = "X-BAPI-RECV-WINDOW";

    private static final String HEADER_SIGN = "X-BAPI-SIGN";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 응답을 읽는 데만 쓰는 매퍼. 이 클래스 밖으로 나가는 값은 만들지 않는다. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final @Nullable String baseUrl;

    private final @Nullable String reservedIp;

    private final Duration requestTimeout;

    private final HttpClient http;

    private final Clock clock;

    /**
     * 생성자가 하나다.
     *
     * <p>시계를 주입받는 오버로드를 두지 않는다. 둘이면 Spring이 어느 것으로 만들지 정하지
     * 못하고, 시각의 권위는 어차피 이 클래스가 아니다 — 저장되는 검증 시각은 여기서 만들지만
     * 만료·유효 구간 판정은 전부 데이터베이스의 {@code now()}가 한다({@code V3}의 근거).
     */
    BybitAdapter(SourceProperties properties) {
        this.clock = Clock.systemUTC();
        this.baseUrl = normalizedBaseUrl(properties.bybit().baseUrl());
        String ip = properties.bybit().reservedIp();
        this.reservedIp = ip == null || ip.isBlank() ? null : ip.trim();
        this.requestTimeout = properties.bybit().requestTimeoutOrDefault();
        this.http =
                HttpClient.newBuilder()
                        // 리다이렉트를 따라가지 않는다. 서명된 요청이 다른 호스트로 재전송되면
                        // 자격정보가 우리가 고르지 않은 origin에 도달한다.
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(this.requestTimeout)
                        .build();
    }

    /**
     * 세 검증을 순서대로 지난다.
     *
     * <p>데이터베이스에 닿지 않는다. 이 메서드가 도는 동안 열린 트랜잭션도 계정 잠금도 없다는 것이
     * 호출자와의 계약이며, 그래서 저장은 결과를 받은 뒤 별도 트랜잭션이 한다.
     */
    SourceVerification verify(SourceCredentials credentials) {
        if (this.baseUrl == null || this.reservedIp == null) {
            // 구성이 비었다. 어느 값인지 알리지 않는다.
            return rejected(SourceRejectionReason.UNAVAILABLE);
        }
        if (!isHeaderSafe(credentials.apiKey()) || !isAcceptable(credentials.apiSecret())) {
            return rejected(SourceRejectionReason.INVALID_FORMAT);
        }

        Call queryApi = call(credentials, QUERY_API_PATH, "");
        if (queryApi.rejection() != null) {
            return rejected(queryApi.rejection());
        }
        JsonNode keyFacts = resultOf(queryApi);
        if (keyFacts == null) {
            return rejected(reasonOf(queryApi, SourceRejectionReason.AUTHENTICATION_REJECTED));
        }
        SourceRejectionReason keyVerdict = judgeKey(keyFacts);
        if (keyVerdict != null) {
            return rejected(keyVerdict);
        }
        String providerAccountRef = text(keyFacts, "userID");
        if (providerAccountRef == null || providerAccountRef.isBlank()) {
            return rejected(SourceRejectionReason.RESPONSE_FORMAT);
        }

        Call accountInfo = call(credentials, ACCOUNT_INFO_PATH, "");
        if (accountInfo.rejection() != null) {
            return rejected(accountInfo.rejection());
        }
        JsonNode account = resultOf(accountInfo);
        if (account == null) {
            return rejected(reasonOf(accountInfo, SourceRejectionReason.AUTHENTICATION_REJECTED));
        }
        if (!isSupportedAccountMode(account)) {
            return rejected(SourceRejectionReason.UNSUPPORTED_ACCOUNT_MODE);
        }

        Call wallet = call(credentials, WALLET_BALANCE_PATH, WALLET_BALANCE_QUERY);
        if (wallet.rejection() != null) {
            return rejected(wallet.rejection());
        }
        JsonNode balance = resultOf(wallet);
        if (balance == null) {
            return rejected(reasonOf(wallet, SourceRejectionReason.WALLET_UNAVAILABLE));
        }
        if (!hasUsableUnifiedEquity(balance)) {
            /*
             * 실패나 응답 누락을 `0 USDT`로 표시하지 않는다. 여기서 값을 들고 나가지도 않는다 —
             * 이 조회는 공식 현재가치 스냅샷이 아니며 그 발행은 Story 2.2가 소유한다.
             */
            return rejected(SourceRejectionReason.WALLET_UNAVAILABLE);
        }

        return new SourceVerification.Verified(
                providerAccountRef, Instant.now(this.clock), POLICY_VERSION);
    }

    /** 연결 때 검증한 지갑을 Worker가 새로 조회한다. 제공자 시각은 응답의 time(tc)이다. */
    SourceConnectionApi.EquityResult readEquity(SourceCredentials credentials) {
        if (!isConfigured())
            return new SourceConnectionApi.EquityResult.Rejected(SourceRejectionReason.UNAVAILABLE);
        if (!isHeaderSafe(credentials.apiKey()) || !isAcceptable(credentials.apiSecret()))
            return new SourceConnectionApi.EquityResult.Rejected(SourceRejectionReason.INVALID_FORMAT);
        Call keyCall = call(credentials, QUERY_API_PATH, "");
        if (keyCall.rejection() != null)
            return new SourceConnectionApi.EquityResult.Rejected(keyCall.rejection());
        JsonNode keyFacts = resultOf(keyCall);
        if (keyFacts == null)
            return new SourceConnectionApi.EquityResult.Rejected(
                    reasonOf(keyCall, SourceRejectionReason.AUTHENTICATION_REJECTED));
        SourceRejectionReason keyVerdict = judgeKey(keyFacts);
        if (keyVerdict != null)
            return new SourceConnectionApi.EquityResult.Rejected(keyVerdict);
        String accountRef = text(keyFacts, "userID");
        if (accountRef == null || accountRef.isBlank())
            return new SourceConnectionApi.EquityResult.Rejected(SourceRejectionReason.RESPONSE_FORMAT);
        Call accountCall = call(credentials, ACCOUNT_INFO_PATH, "");
        if (accountCall.rejection() != null)
            return new SourceConnectionApi.EquityResult.Rejected(accountCall.rejection());
        JsonNode account = resultOf(accountCall);
        if (account == null)
            return new SourceConnectionApi.EquityResult.Rejected(
                    reasonOf(accountCall, SourceRejectionReason.AUTHENTICATION_REJECTED));
        if (!isSupportedAccountMode(account))
            return new SourceConnectionApi.EquityResult.Rejected(
                    SourceRejectionReason.UNSUPPORTED_ACCOUNT_MODE);
        Call call = call(credentials, WALLET_BALANCE_PATH, WALLET_BALANCE_QUERY);
        if (call.rejection() != null)
            return new SourceConnectionApi.EquityResult.Rejected(call.rejection());
        JsonNode result = resultOf(call);
        if (result == null)
            return new SourceConnectionApi.EquityResult.Rejected(
                    reasonOf(call, SourceRejectionReason.WALLET_UNAVAILABLE));
        if (!hasUsableUnifiedEquity(result))
            return new SourceConnectionApi.EquityResult.Rejected(SourceRejectionReason.RESPONSE_FORMAT);
        JsonNode tc = call.body().get("time");
        if (tc == null || !tc.isIntegralNumber() || tc.asLong() <= 0)
            return new SourceConnectionApi.EquityResult.Rejected(SourceRejectionReason.RESPONSE_FORMAT);
        Instant measured;
        try {
            measured = Instant.ofEpochMilli(tc.asLong());
        } catch (RuntimeException invalid) {
            return new SourceConnectionApi.EquityResult.Rejected(SourceRejectionReason.RESPONSE_FORMAT);
        }
        JsonNode equity = result.get("list").get(0).get("totalEquity");
        return new SourceConnectionApi.EquityResult.Observed(
                new SourceConnectionApi.EquityObservation(accountRef, equity.asString(), measured,
                        Instant.now(this.clock), POLICY_VERSION));
    }

    /**
     * 키 자체의 성질을 판정한다.
     *
     * <p>순서가 뜻을 만든다 — 쓰기 권한이 먼저이고 그 다음이 메인 UID, 그 다음이 IP다. 사용자가
     * 제공자 콘솔에서 고쳐야 할 것 중 가장 위험한 것을 먼저 말한다.
     *
     * @return 거절 이유, 통과하면 {@code null}
     */
    private @Nullable SourceRejectionReason judgeKey(JsonNode keyFacts) {
        if (!isReadOnlyFlag(keyFacts)) {
            return SourceRejectionReason.WRITE_PERMISSION;
        }
        SourceRejectionReason permissions =
                permissionVerdict(
                        keyFacts.get("permissions"),
                        READ_ONLY_PERMISSION_GROUPS,
                        REQUIRED_PERMISSION_GROUPS);
        if (permissions != null) {
            return permissions;
        }
        if (!isMainAccount(keyFacts)) {
            return SourceRejectionReason.SUB_ACCOUNT;
        }
        if (!isBoundToReservedIp(keyFacts)) {
            return SourceRejectionReason.IP_NOT_BOUND;
        }
        return null;
    }

    /**
     * {@code readOnly}가 정확히 {@code 1}인가.
     *
     * <p>없는 필드와 모르는 값을 참으로 읽지 않는다. 그 하나가 낙관적 해석이며, 여기서 열리면
     * 쓰기 권한이 붙은 키가 통과한다.
     */
    private static boolean isReadOnlyFlag(JsonNode keyFacts) {
        JsonNode value = keyFacts.get("readOnly");
        return value != null && value.isNumber() && value.asInt() == 1;
    }

    /**
     * permission 배열의 판정. <b>허용 목록 밖은 전부 닫는다.</b>
     *
     * <p>목록 둘을 인자로 받는다. 상수를 직접 읽으면 목록이 비어 있는 동안 두 분기 중 하나가 어떤
     * 시험으로도 도달할 수 없는 코드가 되고, 스파이크가 값을 채우는 날 처음 실행된다. 인자로 두면
     * 구조 자체를 지금 고정할 수 있다.
     *
     * @param permissions 제공자가 준 그룹 → 값 목록. 객체가 아니면 형식 오류다
     * @return 거절 이유, 통과하면 {@code null}
     */
    static @Nullable SourceRejectionReason permissionVerdict(
            @Nullable JsonNode permissions, Set<String> readOnlyGroups, Set<String> requiredGroups) {
        if (permissions == null || !permissions.isObject()) {
            return SourceRejectionReason.RESPONSE_FORMAT;
        }
        for (Map.Entry<String, JsonNode> group : permissions.properties()) {
            JsonNode values = group.getValue();
            if (!values.isArray()) {
                return SourceRejectionReason.RESPONSE_FORMAT;
            }
            if (!values.isEmpty() && !readOnlyGroups.contains(group.getKey())) {
                // 허용 목록에 없는 그룹이 비어 있지 않다. 쓰기 성격으로 읽고 닫는다.
                return SourceRejectionReason.WRITE_PERMISSION;
            }
        }
        for (String required : requiredGroups) {
            JsonNode values = permissions.get(required);
            if (values == null || !values.isArray() || values.isEmpty()) {
                return SourceRejectionReason.INSUFFICIENT_PERMISSION;
            }
        }
        return null;
    }

    /** 메인 UID인가. 두 필드가 <b>둘 다</b> 그것을 말해야 한다. */
    private static boolean isMainAccount(JsonNode keyFacts) {
        JsonNode isMaster = keyFacts.get("isMaster");
        String parentUid = text(keyFacts, "parentUid");
        return isMaster != null
                && isMaster.isBoolean()
                && isMaster.asBoolean()
                && MAIN_ACCOUNT_PARENT_UID.equals(parentUid);
    }

    /**
     * 운영 reserved IP가 그 키에 묶여 있는가.
     *
     * <p>{@code "*"}가 목록에 있으면 거짓이다. 그 값은 "아무 IP나 허용"이라 binding이 성립하지
     * 않으며, 목록에 reserved IP가 함께 있어도 마찬가지다.
     */
    private boolean isBoundToReservedIp(JsonNode keyFacts) {
        JsonNode ips = keyFacts.get("ips");
        if (ips == null || !ips.isArray() || ips.isEmpty()) {
            return false;
        }
        boolean bound = false;
        for (JsonNode entry : ips) {
            String value = entry.isString() ? entry.asString().trim() : null;
            if (value == null) {
                return false;
            }
            if (ANY_IP.equals(value)) {
                return false;
            }
            if (value.equals(this.reservedIp)) {
                bound = true;
            }
        }
        return bound;
    }

    /** 지원하는 계정 모드인가. 모르는 값은 목록에 없으므로 거짓이다. */
    private static boolean isSupportedAccountMode(JsonNode account) {
        JsonNode status = account.get("unifiedMarginStatus");
        return status != null && status.isNumber() && SUPPORTED_ACCOUNT_MODES.contains(status.asInt());
    }

    /**
     * 지갑 응답이 쓸 수 있는 모양인가.
     *
     * <p>세 가지를 함께 본다 — 결과가 <b>단일</b> {@code UNIFIED}이고, {@code totalEquity}가
     * 있으며, 그 값이 canonical decimal string이다. 값 자체는 돌려주지 않는다.
     */
    private static boolean hasUsableUnifiedEquity(JsonNode balance) {
        JsonNode list = balance.get("list");
        if (list == null || !list.isArray() || list.size() != 1) {
            return false;
        }
        JsonNode entry = list.get(0);
        if (!UNIFIED_ACCOUNT_TYPE.equals(text(entry, "accountType"))) {
            return false;
        }
        JsonNode equity = entry.get("totalEquity");
        return equity != null && equity.isString() && isCanonicalDecimal(equity.asString());
    }

    /**
     * canonical decimal string인가.
     *
     * <p>JSON number·지수 표기·{@code NaN}·locale separator를 거부한다(epic-2-context의 십진수
     * 계약). {@code BigDecimal}이 받아들이는 모양보다 좁다 — 그 생성자는 {@code 1E+3}도
     * {@code NaN}이 아닌 값으로 읽는다.
     */
    private static boolean isCanonicalDecimal(String value) {
        return value.matches("-?\\d+(\\.\\d+)?");
    }

    /** 한 번의 호출과 그 결과. 거절이 정해졌으면 본문은 없다. */
    private record Call(@Nullable JsonNode body, @Nullable SourceRejectionReason rejection) {}

    /**
     * 서명된 GET 하나. <b>재시도하지 않는다.</b>
     *
     * <p>전송 계층의 실패가 여기서 거절로 접힌다 — 시간초과·429·5xx·JSON 아님이 각각 다른 값이다.
     * 제공자가 쓴 문장은 어느 경우에도 나르지 않는다.
     */
    private Call call(SourceCredentials credentials, String path, String query) {
        String timestamp = Long.toString(this.clock.millis());
        String signature = sign(credentials.apiSecret(), timestamp + credentials.apiKey() + RECV_WINDOW + query);
        if (signature == null) {
            return new Call(null, SourceRejectionReason.INVALID_FORMAT);
        }
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(this.baseUrl + path + (query.isEmpty() ? "" : "?" + query)))
                        .timeout(this.requestTimeout)
                        .header(HEADER_API_KEY, credentials.apiKey())
                        .header(HEADER_TIMESTAMP, timestamp)
                        .header(HEADER_RECV_WINDOW, RECV_WINDOW)
                        .header(HEADER_SIGN, signature)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        HttpResponse<String> response;
        try {
            response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException timeout) {
            return new Call(null, SourceRejectionReason.TIMEOUT);
        } catch (IOException failure) {
            return new Call(null, SourceRejectionReason.PROVIDER_UNAVAILABLE);
        } catch (InterruptedException interrupted) {
            // 인터럽트 상태를 되살린다. 삼키면 상위 스레드의 취소가 사라진다.
            Thread.currentThread().interrupt();
            return new Call(null, SourceRejectionReason.PROVIDER_UNAVAILABLE);
        }
        if (response.statusCode() == 429) {
            return new Call(null, SourceRejectionReason.RATE_LIMITED);
        }
        if (response.statusCode() >= 500) {
            return new Call(null, SourceRejectionReason.PROVIDER_UNAVAILABLE);
        }
        if (response.statusCode() != 200) {
            /*
             * 200도 429도 5xx도 아니다. 제공자가 자격정보를 거절하는 모양 중 하나이며, 본문을
             * 읽어 사유를 복원하지 않는다 — 그 문장은 우리 오류 계약의 값이 아니다.
             */
            return new Call(null, SourceRejectionReason.AUTHENTICATION_REJECTED);
        }
        try {
            JsonNode body = JSON.readTree(response.body());
            return body.isObject()
                    ? new Call(body, null)
                    : new Call(null, SourceRejectionReason.RESPONSE_FORMAT);
        } catch (JacksonException malformed) {
            // 원문을 로그에도 응답에도 싣지 않는다. 형식이 아니라는 사실 하나만 남는다.
            return new Call(null, SourceRejectionReason.RESPONSE_FORMAT);
        }
    }

    /**
     * 성공 응답의 {@code result}.
     *
     * @return 객체 {@code result}, 그 밖이면 {@code null}. 사유는 {@link #reasonOf}가 정한다
     */
    private static @Nullable JsonNode resultOf(Call call) {
        JsonNode body = call.body();
        if (body == null) {
            return null;
        }
        JsonNode code = body.get("retCode");
        if (code == null || !code.isNumber() || code.asInt() != 0) {
            return null;
        }
        JsonNode result = body.get("result");
        return result != null && result.isObject() ? result : null;
    }

    /**
     * 실패한 호출의 거절 이유.
     *
     * <p>{@link #RET_CODE_REASONS}가 비어 있는 동안 답은 언제나 호출 자리가 정한 기본값이다.
     * 스파이크가 그 표를 채우면 같은 자리에서 더 잘게 갈린다.
     */
    private static SourceRejectionReason reasonOf(Call call, SourceRejectionReason fallback) {
        JsonNode body = call.body();
        if (body == null) {
            return fallback;
        }
        JsonNode code = body.get("retCode");
        if (code == null || !code.isNumber()) {
            return SourceRejectionReason.RESPONSE_FORMAT;
        }
        if (code.asInt() == 0) {
            // `retCode`는 성공인데 `result`가 우리가 아는 모양이 아니다.
            return SourceRejectionReason.RESPONSE_FORMAT;
        }
        return RET_CODE_REASONS.getOrDefault(code.asInt(), fallback);
    }

    /** HMAC-SHA256 서명. 실패하면 {@code null}이며 그 값은 평문을 담지 않는다. */
    private static @Nullable String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of()
                    .formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException failure) {
            // 빈 secret은 `InvalidKeyException`이다. 값을 예외에 담지 않고 형식 오류로 접는다.
            return null;
        }
    }

    private static @Nullable String text(@Nullable JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asString();
    }

    private static boolean isAcceptable(@Nullable String value) {
        return value != null
                && !value.isBlank()
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_CREDENTIAL_BYTES;
    }

    /**
     * 요청 헤더에 실어도 되는 값인가.
     *
     * <p>API Key는 {@code X-BAPI-API-KEY} 헤더로 나간다. CR·LF·제어문자·비-ASCII가 그대로 닿으면
     * {@code HttpRequest.Builder.header}가 {@link IllegalArgumentException}을 던지고, 그 예외는
     * 이 클래스의 거절 값이 아니라 일반 500으로 새어 나간다 — 사용자는 "형식이 올바르지 않다"
     * 대신 "서버가 고장났다"를 본다. 그리고 CR·LF를 통과시키는 것은 그 자체로 헤더 주입이다.
     *
     * <p>보이는 ASCII만 받는다. 제공자가 발급하는 키는 그 범위 안이고, 범위를 넓히는 것은 그것을
     * 요구하는 사실이 관측된 뒤의 일이다(fail-closed).
     */
    private static boolean isHeaderSafe(@Nullable String value) {
        if (!isAcceptable(value)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static SourceVerification rejected(SourceRejectionReason reason) {
        return new SourceVerification.Rejected(reason);
    }

    /**
     * origin 하나만 담은 절대 base URL로 정규화한다.
     *
     * <p>{@code AuthSecurityConfig.ProviderClientRegistrations.normalizedBaseUrl}과 같은 규율이되
     * 여기서는 경로가 붙은 값도 받는다 — 개발 스텁이 {@code /api/dev-bybit} 아래에 서기 때문이다.
     * 끝의 {@code /}만 떼고 스킴을 확인한다.
     */
    private static @Nullable String normalizedBaseUrl(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return null;
        }
        try {
            /*
             * 실제로 URI로 읽히는지 본다. 스킴 접두사만 보면 공백이 섞인 값이 통과하고, 그때
             * `URI.create`가 호출 자리에서 `IllegalArgumentException`을 던진다 — 구성 오류가
             * fail-closed가 아니라 요청마다의 500이 된다. 여기서 `null`로 떨어뜨리면 그 스택은
             * 다른 구성 부재와 같은 하나로 수렴한다.
             */
            URI.create(trimmed);
        } catch (IllegalArgumentException notAUri) {
            return null;
        }
        return trimmed;
    }

    /** 이 어댑터가 쓸 수 있는 구성을 갖췄는가. 없으면 연결 기능이 fail-closed다. */
    boolean isConfigured() {
        return this.baseUrl != null && this.reservedIp != null;
    }

    /** 이 어댑터가 요구하는 운영 reserved IP. 개발 스텁이 같은 값을 응답에 싣는다. */
    @Nullable String reservedIp() {
        return this.reservedIp;
    }

    /** 이 어댑터가 아는 permission 그룹 허용 목록. 시험이 구조를 확인하는 창이다. */
    static List<Set<String>> permissionAllowLists() {
        return List.of(READ_ONLY_PERMISSION_GROUPS, REQUIRED_PERMISSION_GROUPS);
    }
}
