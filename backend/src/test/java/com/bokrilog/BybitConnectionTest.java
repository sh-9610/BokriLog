package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.DevBybitStub.Scenario;
import com.bokrilog.source.SourceRejectionReason;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 읽기 전용 Bybit 연결의 HTTP 계약(Story 2.1).
 *
 * <p>실제 서블릿 컨테이너에 진짜 HTTP 요청을 보낸다. 이 스토리가 고정하려는 것 중 셋이 컨트롤러
 * 바깥에서 결정되기 때문이다 — 인가 규칙의 401, {@code /api/connections/} 상태 변경의 CSRF·
 * {@code Origin} 403, 그리고 성공 시 세션 회전의 {@code Set-Cookie}.
 *
 * <p>제공자는 local 스텁이다. 시나리오를 API Key가 고르므로 같은 키로 몇 번을 물어도 같은 답이
 * 나오고, 실패를 만들기 위해 스텁의 상태를 미리 바꿔 둘 필요가 없다({@code BybitStubConfiguration}).
 *
 * <p>Story 1.2·1.3이 고정한 오류 계약을 함께 본다. 이 스토리가 인가 규칙을 처음 켜므로 그 회귀가
 * 여기서 드러나야 한다 — {@code /api/nope}의 404와 {@code POST /api/status}의 405다.
 */
class BybitConnectionTest extends AuthFlowTestBase {

    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;

    @BeforeEach
    void resetStubCounter() {
        BybitStubConfiguration.resetRequestCount();
    }

    // ----------------------------------------------------------------------
    // 인가 규칙 — 이 스토리가 처음 연다
    // ----------------------------------------------------------------------

    @Test
    void 미인증_요청은_연결_표면에서_401이다() throws Exception {
        HttpResponse<String> response = bybitConnectionState(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(response.body()).contains("\"correlationId\"");
    }

    @Test
    void 인가_규칙이_켜져도_Story_1_2의_오류_계약은_그대로다() throws Exception {
        HttpResponse<String> unknown = get("/api/nope", null);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("\"code\":\"NOT_FOUND\"");

        HttpResponse<String> wrongMethod = send("POST", AuthSecurityConfig.STATUS_PATH, null);
        assertThat(wrongMethod.statusCode()).isEqualTo(405);
        assertThat(wrongMethod.body()).contains("\"code\":\"METHOD_NOT_ALLOWED\"");

        HttpResponse<String> status = get(AuthSecurityConfig.STATUS_PATH, null);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void 연결_표면의_상태_변경은_토큰과_동일_출처를_요구한다() throws Exception {
        Session session = signInAndReauthenticate("csrf");

        assertThat(prepareBybitConnection(session.cookie(), null, OidcStubConfiguration.PUBLIC_BASE_URL)
                        .statusCode())
                .isEqualTo(403);
        assertThat(
                        prepareBybitConnection(
                                        session.cookie(), session.csrfToken(), "https://evil.example")
                                .statusCode())
                .isEqualTo(403);
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(actionGrantCount()).isZero();
    }

    // ----------------------------------------------------------------------
    // admission — 외부 호출 0건으로 끝나야 하는 거절
    // ----------------------------------------------------------------------

    @Test
    void 재인증이_없으면_준비가_거부되고_외부_호출이_0건이다() throws Exception {
        // 로그인만 한 세션이다. Story 1.8 이후 일반 로그인은 재인증 시각을 남기지 않는다.
        String subject = "no-reauth-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();

        HttpResponse<String> prepared = prepareBybitConnection(cookie, csrfTokenOf(cookie));

        assertThat(prepared.statusCode()).isEqualTo(403);
        assertThat(prepared.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(actionGrantCount()).isZero();
        assertNothingStored();
    }

    @Test
    void 화면을_우회한_직접_확인은_거부되고_외부_호출이_0건이다() throws Exception {
        Session session = signInAndReauthenticate("bypass");

        HttpResponse<String> response =
                connectBybit(
                        session.cookie(),
                        session.csrfToken(),
                        BybitStubConfiguration.apiKey(Scenario.UTA2, "900001"),
                        BybitStubConfiguration.newSecret(),
                        "아무 문구");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertNothingStored();
    }

    @Test
    void 운영_제한이_걸리면_외부_호출_없이_거부된다() throws Exception {
        Session session = signInAndReauthenticate("restricted");
        restrictCommand(BybitConnectionService.COMMAND);

        HttpResponse<String> prepared = prepareBybitConnection(session.cookie(), session.csrfToken());

        assertThat(prepared.statusCode()).isEqualTo(403);
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(actionGrantCount()).isZero();
        assertNothingStored();
    }

    @Test
    void 이미_연결된_사용자는_외부_호출_없이_거부된다() throws Exception {
        Session session = connect("already", Scenario.UTA2, "910001");
        assertThat(sourceConnectionCount()).isEqualTo(1);

        BybitStubConfiguration.resetRequestCount();
        Session again = reauthenticate(session);
        HttpResponse<String> prepared = prepareBybitConnection(again.cookie(), again.csrfToken());

        assertThat(prepared.statusCode()).isEqualTo(403);
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(sourceConnectionCount()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------
    // 정상 연결
    // ----------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({"UTA2, 920001", "UTA_PRO, 920002"})
    void 지원되는_읽기_전용_키는_여섯을_한_트랜잭션으로_저장한다(String scenario, String uid)
            throws Exception {
        Session session = signInAndReauthenticate("connect-" + uid);
        String apiKey = BybitStubConfiguration.apiKey(Scenario.valueOf(scenario), uid);
        String secret = BybitStubConfiguration.newSecret();

        HttpResponse<String> prepared =
                prepareBybitConnection(session.cookie(), session.csrfToken());
        assertThat(prepared.statusCode()).isEqualTo(200);
        String confirmation = confirmationIn(prepared.body());
        assertThat(confirmation).isNotBlank();
        // 이 시점까지 저장도 외부 호출도 없다. "확인 전에는 아무것도 바뀌지 않는다".
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertNothingStored();

        HttpResponse<String> connected =
                connectBybit(session.cookie(), session.csrfToken(), apiKey, secret, confirmation);

        assertThat(connected.statusCode()).isEqualTo(200);
        assertThat(connected.body()).contains("\"connected\":true");
        assertThat(connected.body()).contains("\"policyVersion\":\"");
        assertThat(connected.body()).contains("\"verifiedAt\":\"");
        // 응답 어디에도 자격정보가 없다.
        assertThat(connected.body()).doesNotContain(secret);
        assertThat(connected.body()).doesNotContain(apiKey);

        assertThat(sourceConnectionCount()).isEqualTo(1);
        assertThat(sourceCredentialCount()).isEqualTo(1);
        assertThat(providerAccountCount()).isEqualTo(1);
        assertThat(portfolioCount()).isEqualTo(1);
        assertThat(membershipCount()).isEqualTo(1);
        assertThat(actionGrantConsumed(grantReferenceOf(session.userId()))).isTrue();

        // 세션 ID가 회전했다. 허가 소비 성공이 요구하는 것이다(`SECURITY-ACTION-1.0`).
        String rotated = sessionCookieOf(connected).orElseThrow();
        assertThat(rotated).isNotEqualTo(session.cookie());
        assertThat(sessionState(rotated).body()).contains("\"authenticated\":true");
        assertThat(sessionCountOf(session.userId())).isEqualTo(1);

        // 세 엔드포인트를 정확히 한 번씩 지났다. 재시도가 없다는 사실이 여기서 관측된다.
        assertThat(BybitStubConfiguration.requestCount()).isEqualTo(3);

        HttpResponse<String> state = bybitConnectionState(rotated);
        assertThat(state.statusCode()).isEqualTo(200);
        assertThat(state.body()).contains("\"connected\":true");
        assertThat(state.body()).doesNotContain(secret);
    }

    @Test
    void 저장된_자격정보에_평문_조각이_없고_nonce가_행마다_다르다() throws Exception {
        Session first = signInAndReauthenticate("cipher-a");
        String firstSecret = BybitStubConfiguration.newSecret();
        String firstKey = BybitStubConfiguration.apiKey(Scenario.UTA2, "930001");
        assertThat(
                        connectBybitRoundTrip(
                                        first.cookie(), first.csrfToken(), firstKey, firstSecret)
                                .statusCode())
                .isEqualTo(200);

        Session second = signInAndReauthenticate("cipher-b");
        String secondSecret = BybitStubConfiguration.newSecret();
        String secondKey = BybitStubConfiguration.apiKey(Scenario.UTA2, "930002");
        assertThat(
                        connectBybitRoundTrip(
                                        second.cookie(), second.csrfToken(), secondKey, secondSecret)
                                .statusCode())
                .isEqualTo(200);

        var rows = storedCredentials();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).nonce()).hasSize(12);
        assertThat(rows.get(0).nonce()).isNotEqualTo(rows.get(1).nonce());
        assertThat(rows.get(0).keyVersion()).isEqualTo(BybitStubConfiguration.KEY_VERSION);
        assertThat(rows.get(0).algorithmVersion()).isEqualTo("AES256GCM-1");

        for (var row : rows) {
            String hex = HexFormat.of().formatHex(row.ciphertext());
            assertThat(hex).doesNotContain(HexFormat.of().formatHex(bytes(firstSecret)));
            assertThat(hex).doesNotContain(HexFormat.of().formatHex(bytes(secondSecret)));
            assertThat(hex).doesNotContain(HexFormat.of().formatHex(bytes(firstKey)));
            assertThat(hex).doesNotContain(HexFormat.of().formatHex(bytes(secondKey)));
        }
        // 같은 평문 두 벌이 아니다. nonce가 매번 새로우므로 암호문도 같을 수 없다.
        assertThat(Arrays.equals(rows.get(0).ciphertext(), rows.get(1).ciphertext())).isFalse();
    }

    @Test
    void 같은_메인_UID로_자격정보를_바꿔_다시_연결하면_안정_식별자가_재사용된다() throws Exception {
        Session session = connect("reuse", Scenario.UTA2, "940001");
        UUID providerAccountId = providerAccountIdOf(session.userId()).orElseThrow();

        /*
         * 여기서 보려는 것은 "자격정보·connection 교체에도 안정 식별자가 재사용되는가"다. Story 2.16부터 해제한
         * 사용자의 새 연결은 단일 거부다(RECONNECT-1.0 구현 전) — 그래서 교체의 전제를 해제가 아니라 연결 행 삭제로
         * 만든다. 행을 지우면 자격정보와 membership이 CASCADE로 함께 사라지고 제공자 계정·portfolio는 남는다.
         */
        jdbc().sql("DELETE FROM source_connection WHERE user_id=:u").param("u", session.userId()).update();

        Session again = reauthenticate(session);
        assertThat(
                        connectBybitRoundTrip(
                                        again.cookie(),
                                        again.csrfToken(),
                                        BybitStubConfiguration.apiKey(Scenario.UTA2, "940001"),
                                        BybitStubConfiguration.newSecret())
                                .statusCode())
                .isEqualTo(200);

        assertThat(providerAccountCount()).isEqualTo(1);
        assertThat(providerAccountIdOf(session.userId())).contains(providerAccountId);
        // portfolio도 재사용된다. 두 번째 연결이 두 번째 묶음을 만들지 않는다.
        assertThat(portfolioCount()).isEqualTo(1);
        assertThat(membershipCount()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------
    // 거절 — 사용자가 제공자 콘솔에서 고칠 수 있는 사실은 구분해 알려 준다
    // ----------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "WRITE_PERMISSION, WRITE_PERMISSION, 950001",
        // 플래그는 읽기 전용인데 permission 배열이 비어 있지 않다. 위 줄과 달리 이 갈래만
        // `permissionVerdict`의 허용 목록 판정에 도달한다 — 없으면 그 블록을 통째로 지워도 green이다.
        "PERMISSION_GRANTED, WRITE_PERMISSION, 950012",
        "NO_PERMISSIONS, RESPONSE_FORMAT, 950013",
        // 지갑 응답 모양 넷. `hasUsableUnifiedEquity`의 조건을 하나씩 깬다.
        "WALLET_TWO_ENTRIES, WALLET_UNAVAILABLE, 950014",
        "WALLET_OTHER_TYPE, WALLET_UNAVAILABLE, 950015",
        "WALLET_NO_EQUITY, WALLET_UNAVAILABLE, 950016",
        "WALLET_EXPONENT, WALLET_UNAVAILABLE, 950017",
        "SUB_ACCOUNT, SUB_ACCOUNT, 950002",
        "LEGACY_UTA, UNSUPPORTED_ACCOUNT_MODE, 950003",
        "UNKNOWN_MODE, UNSUPPORTED_ACCOUNT_MODE, 950004",
        "NO_IP, IP_NOT_BOUND, 950005",
        "ANY_IP, IP_NOT_BOUND, 950006",
        "WALLET_FAILURE, WALLET_UNAVAILABLE, 950007",
        "RATE_LIMIT, RATE_LIMITED, 950008",
        "OUTAGE, PROVIDER_UNAVAILABLE, 950009",
        "MALFORMED, RESPONSE_FORMAT, 950010",
        "AUTH_FAILURE, AUTHENTICATION_REJECTED, 950011"
    })
    void 거절은_구분되고_아무것도_저장하지_않는다(String scenario, String reason, String uid)
            throws Exception {
        Session session = signInAndReauthenticate("reject-" + uid);
        String apiKey = BybitStubConfiguration.apiKey(Scenario.valueOf(scenario), uid);
        String secret = BybitStubConfiguration.newSecret();

        HttpResponse<String> response =
                connectBybitRoundTrip(session.cookie(), session.csrfToken(), apiKey, secret);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body())
                .contains(
                        "\"code\":\""
                                + BybitConnectionController.codeOf(
                                        SourceRejectionReason.valueOf(reason))
                                + "\"");
        assertThat(response.body()).contains("\"correlationId\"");
        // 제공자가 쓴 문장도 원문도 나르지 않는다.
        assertThat(response.body()).doesNotContain(secret);
        assertThat(response.body()).doesNotContain(apiKey);
        assertThat(response.body()).doesNotContain("retCode");
        assertNothingStored();
        // 허가는 소비되지 않았다. 같은 무장으로 고친 키를 다시 시도할 수 있다.
        assertThat(actionGrantConsumed(grantReferenceOf(session.userId()))).isFalse();
    }

    @Test
    void 형식이_아닌_입력은_외부_호출_없이_거절된다() throws Exception {
        Session session = signInAndReauthenticate("format");
        HttpResponse<String> prepared = prepareBybitConnection(session.cookie(), session.csrfToken());
        String confirmation = confirmationIn(prepared.body());
        BybitStubConfiguration.resetRequestCount();

        HttpResponse<String> blank =
                connectBybit(session.cookie(), session.csrfToken(), "   ", "   ", confirmation);

        assertThat(blank.statusCode()).isEqualTo(422);
        assertThat(blank.body())
                .contains(
                        "\"code\":\""
                                + BybitConnectionController.codeOf(SourceRejectionReason.INVALID_FORMAT)
                                + "\"");
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertNothingStored();
    }

    @Test
    void 검증에_실패해도_같은_무장으로_고친_키를_다시_시도할_수_있다() throws Exception {
        Session session = signInAndReauthenticate("retry");
        HttpResponse<String> prepared = prepareBybitConnection(session.cookie(), session.csrfToken());
        String confirmation = confirmationIn(prepared.body());

        assertThat(
                        connectBybit(
                                        session.cookie(),
                                        session.csrfToken(),
                                        BybitStubConfiguration.apiKey(Scenario.WRITE_PERMISSION, "960001"),
                                        BybitStubConfiguration.newSecret(),
                                        confirmation)
                                .statusCode())
                .isEqualTo(422);

        HttpResponse<String> second =
                connectBybit(
                        session.cookie(),
                        session.csrfToken(),
                        BybitStubConfiguration.apiKey(Scenario.UTA2, "960001"),
                        BybitStubConfiguration.newSecret(),
                        confirmation);

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(sourceConnectionCount()).isEqualTo(1);
    }

    @Test
    void 다른_계정에_연결된_메인_UID는_상대_계정을_드러내지_않고_거절된다() throws Exception {
        Session owner = connect("owner", Scenario.UTA2, "970001");
        Session other = signInAndReauthenticate("intruder");

        HttpResponse<String> response =
                connectBybitRoundTrip(
                        other.cookie(),
                        other.csrfToken(),
                        BybitStubConfiguration.apiKey(Scenario.UTA2, "970001"),
                        BybitStubConfiguration.newSecret());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body())
                .contains(
                        "\"code\":\""
                                + BybitConnectionController.codeOf(
                                        SourceRejectionReason.PROVIDER_ACCOUNT_TAKEN)
                                + "\"");
        assertThat(response.body()).doesNotContain(owner.userId().toString());
        assertThat(response.body()).doesNotContain("970001");
        assertThat(sourceConnectionCountOf(other.userId())).isZero();
        assertThat(sourceConnectionCountOf(owner.userId())).isEqualTo(1);
        assertThat(portfolioCountOf(other.userId())).isZero();
    }

    // ----------------------------------------------------------------------
    // 삭제와의 경쟁, 그리고 삭제 경로
    // ----------------------------------------------------------------------

    @Test
    void 저장_직전에_삭제가_시작되면_삭제가_이긴다() throws Exception {
        Session session = signInAndReauthenticate("deleting");
        HttpResponse<String> prepared = prepareBybitConnection(session.cookie(), session.csrfToken());
        String confirmation = confirmationIn(prepared.body());
        String reference = grantReferenceOf(session.userId());

        accountDeletion().beginDeletion(session.userId());

        HttpResponse<String> response =
                connectBybit(
                        session.cookie(),
                        session.csrfToken(),
                        BybitStubConfiguration.apiKey(Scenario.UTA2, "980001"),
                        BybitStubConfiguration.newSecret(),
                        confirmation);

        assertThat(response.statusCode()).isEqualTo(403);
        assertNothingStored();
        // 허가 소비까지 롤백됐다. 재시도로 연결이 되살아나지 않는다.
        assertThat(actionGrantConsumed(reference)).isFalse();
        assertThat(
                        connectBybit(
                                        session.cookie(),
                                        session.csrfToken(),
                                        BybitStubConfiguration.apiKey(Scenario.UTA2, "980001"),
                                        BybitStubConfiguration.newSecret(),
                                        confirmation)
                                .statusCode())
                .isEqualTo(403);
        assertNothingStored();
    }

    @Test
    void 삭제_경로가_그_사용자의_source와_portfolio만_지운다() throws Exception {
        Session leaving = connect("leaving", Scenario.UTA2, "990001");
        Session staying = connect("staying", Scenario.UTA_PRO, "990002");
        assertThat(sourceConnectionCount()).isEqualTo(2);

        /*
         * 순서가 계약이다. `source`·`portfolio` 삭제 경로가 먼저 돌고 그 다음이 identity payload다 —
         * 두 모듈의 소유 테이블이 `identity_account`를 `ON DELETE RESTRICT`로 가리키므로 순서가
         * 뒤집히면 외래키 위반으로 소리 나게 실패한다(`V4__source_connection.sql`).
         *
         * 전체 계정 삭제 orchestration은 Epic 5가 가져간다. 여기서 보는 것은 이 스토리가 만든
         * 데이터의 삭제 경로가 실제로 존재하고 다른 사용자를 건드리지 않는다는 것이다.
         */
        portfolios().erasePortfolioData(leaving.userId());
        sources().eraseSourceData(leaving.userId());
        accountDeletion().beginDeletion(leaving.userId());
        accountDeletion().erasePayload(leaving.userId());

        assertThat(sourceConnectionCountOf(leaving.userId())).isZero();
        assertThat(portfolioCountOf(leaving.userId())).isZero();
        assertThat(sourceConnectionCountOf(staying.userId())).isEqualTo(1);
        assertThat(portfolioCountOf(staying.userId())).isEqualTo(1);
        assertThat(sourceCredentialCount()).isEqualTo(1);
        assertThat(providerAccountCount()).isEqualTo(1);
        assertThat(membershipCount()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------
    // 취소
    // ----------------------------------------------------------------------

    @Test
    void 취소는_무장과_허가를_되돌리고_언제나_204다() throws Exception {
        Session session = signInAndReauthenticate("cancel");
        HttpResponse<String> prepared = prepareBybitConnection(session.cookie(), session.csrfToken());
        String confirmation = confirmationIn(prepared.body());
        assertThat(actionGrantCount()).isEqualTo(1);

        assertThat(cancelBybitConnection(session.cookie(), session.csrfToken()).statusCode())
                .isEqualTo(204);
        assertThat(actionGrantCount()).isZero();

        // 무장이 없으므로 확인은 단일 거부다.
        assertThat(
                        connectBybit(
                                        session.cookie(),
                                        session.csrfToken(),
                                        BybitStubConfiguration.apiKey(Scenario.UTA2, "991001"),
                                        BybitStubConfiguration.newSecret(),
                                        confirmation)
                                .statusCode())
                .isEqualTo(403);
        assertNothingStored();

        // 멱등하다. 되돌릴 것이 없을 때 불러도 아무 일도 하지 않는다.
        assertThat(cancelBybitConnection(session.cookie(), session.csrfToken()).statusCode())
                .isEqualTo(204);
    }

    @Test
    void 거절_이유는_전부_안정_code를_갖는다() {
        for (SourceRejectionReason reason : SourceRejectionReason.values()) {
            assertThat(BybitConnectionController.covers(reason))
                    .withFailMessage("거절 이유 %s에 대응하는 code가 없다.", reason)
                    .isTrue();
        }
    }

    @Test
    void 이_표면이_내는_code_집합이_프런트엔드_사전과_같다() {
        /*
         * 화면 카피는 `frontend/src/content/connection.ts`가 소유하고 클라이언트는 `code`로 자기
         * 사전을 고른다. 두 계층이 한 계약을 나눠 갖는 자리이며(`CSRF_HEADER_NAME`과 같은 종류의
         * 중복) 각 층이 자기 쪽을 이 목록과 대조한다 — 갈라지면 화면이 모르는 code를 받아 일반
         * 문장으로 접고, 사용자는 고칠 수 있는 원인을 듣지 못한다.
         *
         * 여기 적힌 목록이 그 계약이다. 프런트엔드는 `CONNECTION_REJECTION_CODES`가 같은 집합임을
         * 자기 시험으로 단정한다.
         */
        assertThat(BybitConnectionController.emittedCodes())
                .containsExactlyInAnyOrder(
                        "CONNECTION_ACCOUNT_TAKEN",
                        "CONNECTION_AUTHENTICATION_REJECTED",
                        "CONNECTION_INSUFFICIENT_PERMISSION",
                        "CONNECTION_INVALID_FORMAT",
                        "CONNECTION_IP_NOT_BOUND",
                        "CONNECTION_PROVIDER_UNAVAILABLE",
                        "CONNECTION_RATE_LIMITED",
                        "CONNECTION_RESPONSE_FORMAT",
                        "CONNECTION_SUB_ACCOUNT",
                        "CONNECTION_TIMEOUT",
                        "CONNECTION_UNAVAILABLE",
                        "CONNECTION_UNSUPPORTED_ACCOUNT_MODE",
                        "CONNECTION_WALLET_UNAVAILABLE",
                        "CONNECTION_WRITE_PERMISSION",
                        ApiErrorHandler.CODE_FORBIDDEN);
    }

    @Test
    void 확인_문구가_다르면_거부되고_허가는_미소비로_남는다() throws Exception {
        Session session = signInAndReauthenticate("wrong-confirmation");
        HttpResponse<String> prepared = prepareBybitConnection(session.cookie(), session.csrfToken());
        String confirmation = confirmationIn(prepared.body());
        String reference = grantReferenceOf(session.userId());
        BybitStubConfiguration.resetRequestCount();

        HttpResponse<String> response =
                connectBybit(
                        session.cookie(),
                        session.csrfToken(),
                        BybitStubConfiguration.apiKey(Scenario.UTA2, "950020"),
                        BybitStubConfiguration.newSecret(),
                        confirmation + "-tampered");

        /*
         * 다섯 번째 귀속 축이 살아 있다는 증거다. 이 비교가 없으면 축은 클라이언트가 영향을 줄 수
         * 없는 서버 값에 묶이고, 요청마다 문구를 새로 만든 뜻이 사라진다.
         */
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertNothingStored();
        assertThat(actionGrantConsumed(reference)).isFalse();

        // 올바른 문구로는 같은 무장이 그대로 성립한다. 거부가 무장을 태우지 않았다.
        assertThat(
                        connectBybit(
                                        session.cookie(),
                                        session.csrfToken(),
                                        BybitStubConfiguration.apiKey(Scenario.UTA2, "950020"),
                                        BybitStubConfiguration.newSecret(),
                                        confirmation)
                                .statusCode())
                .isEqualTo(200);
        assertThat(actionGrantConsumed(reference)).isTrue();
    }

    @Test
    void 연결_접두사의_경계가_다른_경로를_삼키지_않는다() throws Exception {
        /*
         * `startsWith("/api/connections")`만 쓰면 `/api/connectionsfoo`처럼 우리 것이 아닌 경로가
         * 함께 걸리고 그 요청의 404가 401로 바뀐다. 접두사 하나가 인접 이름을 삼키지 않는다는
         * 사실을 미인증 요청으로 본다.
         */
        HttpResponse<String> neighbour = get("/api/connectionsfoo", null);
        assertThat(neighbour.statusCode()).isEqualTo(404);
        assertThat(neighbour.body()).contains("\"code\":\"NOT_FOUND\"");

        /*
         * 맨 접두사는 반대다. 핸들러가 없어 404지만 인증 없이 그 404를 돌려주면 이 접두사의
         * 존재가 밖에서 열거된다. 미인증은 401이고, 인증된 요청에만 404다.
         */
        HttpResponse<String> bareAnonymous = get(AuthSecurityConfig.CONNECTIONS_BASE_URI, null);
        assertThat(bareAnonymous.statusCode()).isEqualTo(401);

        Session session = signInAndReauthenticate("prefix");
        HttpResponse<String> bareAuthenticated =
                get(AuthSecurityConfig.CONNECTIONS_BASE_URI, session.cookie());
        assertThat(bareAuthenticated.statusCode()).isEqualTo(404);
        assertThat(bareAuthenticated.body()).contains("\"code\":\"NOT_FOUND\"");
    }

    // ----------------------------------------------------------------------
    // 단정 도구
    // ----------------------------------------------------------------------

    /**
     * 로그인과 재인증을 마친 사용자 하나.
     *
     * <p>연결의 전제가 정확히 이 상태다 — 세션에 재인증 시각이 기록돼 있어야 허가가 발급된다.
     * 일반 로그인은 그 값을 남기지 않으므로(Story 1.8) 두 단계를 한 곳에 둔다
     * ({@code AuthLinkProviderTest}의 같은 레시피).
     */
    private record Session(UUID userId, String subject, String cookie, String csrfToken) {}

    private Session signInAndReauthenticate(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        HttpResponse<String> reauthenticated =
                reauthenticate(GOOGLE, cookie, csrfTokenOf(cookie), plan -> plan.subject(subject));
        String rotated = sessionCookieOf(reauthenticated).orElseThrow();
        return new Session(userId, subject, rotated, csrfTokenOf(rotated));
    }

    /** 이미 있는 사용자의 본인 확인을 새로 한다. 두 번째 연결 시도가 필요한 시험이 쓴다. */
    private Session reauthenticate(Session session) throws Exception {
        HttpResponse<String> reauthenticated =
                reauthenticate(
                        GOOGLE,
                        session.cookie(),
                        csrfTokenOf(session.cookie()),
                        plan -> plan.subject(session.subject()));
        String rotated = sessionCookieOf(reauthenticated).orElseThrow();
        return new Session(session.userId(), session.subject(), rotated, csrfTokenOf(rotated));
    }

    /** 가입 → 본인 확인 → 연결까지 한 번에. 연결된 상태가 전제인 시험이 쓴다. */
    private Session connect(String prefix, Scenario scenario, String uid) throws Exception {
        Session session = signInAndReauthenticate(prefix);
        HttpResponse<String> response =
                connectBybitRoundTrip(
                        session.cookie(),
                        session.csrfToken(),
                        BybitStubConfiguration.apiKey(scenario, uid),
                        BybitStubConfiguration.newSecret());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "연결이 200이 아니다: " + response.statusCode() + " " + response.body());
        }
        return new Session(
                session.userId(),
                session.subject(),
                sessionCookieOf(response).orElseThrow(),
                session.csrfToken());
    }

    /**
     * 그 사용자의 허가 참조.
     *
     * <p>브라우저로 나가지 않는 값이라 응답에서 읽을 수 없다. 시험이 "소비됐는가"를 보려면
     * 저장소에서 직접 찾아야 하고, 사용자당 살아 있는 허가는 하나라 이 조회로 행이 정해진다.
     */
    private String grantReferenceOf(UUID userId) {
        return jdbc()
                .sql("SELECT grant_id FROM action_grant WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .single();
    }

    /** 이 스토리가 만드는 다섯 테이블이 전부 비어 있는가. */
    private void assertNothingStored() {
        assertThat(sourceConnectionCount()).isZero();
        assertThat(sourceCredentialCount()).isZero();
        assertThat(providerAccountCount()).isZero();
        assertThat(portfolioCount()).isZero();
        assertThat(membershipCount()).isZero();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
