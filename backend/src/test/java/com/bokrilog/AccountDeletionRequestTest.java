package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.identity.AccountDeletionApi.DeletionRequestOutcome;
import com.bokrilog.identity.AccountDeletionRunApi.DeletionRun;
import com.bokrilog.identity.AccountDeletionRunApi.RunStatus;
import com.bokrilog.identity.DeletionStep;
import com.bokrilog.operations.DeletionTombstoneApi.DestructionStatus;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Story 5.1: 삭제 범위와 보존 정책을 확인하고 계정 삭제를 요청한다 — 실제 스택(Docker PostgreSQL, local OIDC·Bybit 스텁)에서 HTTP로.
 * 정책 조회, 재인증·{@code delete-account} 허가·고정 문구 재입력, 요청 행·삭제 진행 전환·허가 소비의 원자 커밋, 다른 세션 폐기와 요청
 * 세션의 재인증 시각 제거(요청 세션의 ID는 회전하지 않는다), 같은 키 재전송(같은 본문은 같은 200과 멱등한 세션 정리, 다른 본문은
 * 충돌)·다른 키·삭제 중 준비, 정책 버전·문구 불일치·키 없음, production 차단, 취소, 삭제 중 다른 경로의 기존 거부, 동시 확정, 커밋
 * 뒤 정리 단계의 실패, 고지 hash 고정, 로그 누출 없음을 본다. 실제 모듈 삭제·tombstone은 일어나지 않는다(5.2·5.3).
 */
class AccountDeletionRequestTest extends PlanSyncTestBase {

    private static final String DELETION_PATH = AccountDeletionController.DELETION_PATH;

    private static final String CONFIRM_PATH = AccountDeletionController.CONFIRM_PATH;

    private static final String CANCEL_PATH = AccountDeletionController.CANCEL_PATH;

    private static final String PHRASE = "계정을 삭제합니다";

    private static final String VERSION = "ACCOUNT-DELETION-1.1";

    private static final Path SHARED_FIXTURE = Path.of("../frontend/src/content/accountDeletionPolicy.fixture.json");

    @Autowired AccountDeletionRequestService service;

    @Autowired com.bokrilog.identity.AccountDeletionRunApi runs;

    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    record Signed(UUID user, String subject, String cookie, String csrf) {}

    // ------------------------------------------------------------------------------------------ 정책과 요청

    /**
     * AC-1~AC-4: 정책 조회는 서버 상수를 그대로 싣고, 준비는 허가와 무장만 만들며, 확정은 요청 행·삭제 진행 전환·허가 소비를 한
     * 커밋으로 만든 뒤 다른 세션을 폐기하고 요청 세션의 재인증 시각만 지운다(세션 ID는 회전하지 않는다). 남은 세션은 진행 상태를 읽고
     * 같은 쿠키로 같은 요청을 재전송해 같은 본문을 받고 로그아웃할 수 있다. 모듈 데이터·tombstone은 그대로다. 로그에는 식별자·허가·문구·키가 없다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 정책을_확인하고_요청하면_원자로_삭제를_시작하고_다른_세션을_끊는다(CapturedOutput output) throws Exception {
        Signed signed = signedIn("delete-main");
        String other = sessionCookieOf(signIn(signed.subject())).orElseThrow();
        assertThat(sessionCountOf(signed.user())).isEqualTo(2);

        var state = get(DELETION_PATH, signed.cookie());
        assertThat(state.statusCode()).as(state.body()).isEqualTo(200);
        assertThat(state.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode view = json(state.body());
        assertThat(view.get("available").asBoolean()).isTrue();
        assertThat(view.get("request").isNull()).isTrue();
        assertThat(view.get("policy").get("version").asString()).isEqualTo(VERSION);
        assertThat(view.get("policy").get("backupDestroyDays").asInt()).isEqualTo(30);
        assertThat(view.get("policy").get("legalRetention").isEmpty()).isTrue();
        assertThat(strings(view.get("policy").get("targets"))).isEqualTo(AccountDeletionPolicy.TARGETS);

        var prepared = prepare(signed);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(prepared.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(prepared.body())).containsOnlyKeys("policyVersion", "confirmation")
                .containsEntry("policyVersion", VERSION).containsEntry("confirmation", PHRASE);
        String grant = grantReferenceOf(signed.user());
        assertThat(prepared.body()).doesNotContain(grant);
        assertThat(jdbc().sql("SELECT command||'|'||target FROM action_grant WHERE grant_id=:g").param("g", grant)
                .query(String.class).single()).isEqualTo("delete-account|" + signed.user() + "|" + VERSION);
        assertThat(lifecycleOf(signed.user())).isEqualTo("ACTIVE");
        assertThat(requestCount(signed.user())).isZero();

        Instant earliest = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        String key = key("delete-main");
        var confirmed = confirm(signed, VERSION, PHRASE, key);
        Instant latest = databaseNow();
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var result = topLevelFieldsOf(confirmed.body());
        // `progress`는 Story 5.2의 삭제 진행이다 — 막 만든 요청은 Worker가 집기 전이라 진행 중이다.
        assertThat(result).containsOnlyKeys("requestId", "requestedAt", "state", "policyVersion", "progress")
                .containsEntry("state", "DELETION_IN_PROGRESS").containsEntry("policyVersion", VERSION);
        String requestId = (String) result.get("requestId");
        assertThat(requestId).matches("[A-Za-z0-9_-]{1,64}");
        Instant requestedAt = Instant.parse((String) result.get("requestedAt"));
        assertThat(requestedAt).isBetween(earliest.minusMillis(1), latest);
        // 회전하지 않는다 — 새 세션 쿠키가 없고 같은 쿠키가 그대로 쓰인다. 재인증 시각만 지워졌다.
        assertThat(sessionCookieOf(confirmed)).isEmpty();
        String kept = signed.cookie();
        assertThat(sessionAttribute(sessionIdOf(kept), ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE)).isNull();
        assertThat(sessionAttribute(sessionIdOf(kept), AccountDeletionFlow.FLOW_ATTRIBUTE)).isNull();

        // 한 커밋: 요청 행, 삭제 진행 전환, 소비된 허가.
        assertThat(jdbc().sql("SELECT request_id||'|'||policy_version||'|'||notice_hash||'|'||idempotency_key_digest "
                + "FROM identity_deletion_request WHERE user_id=:u").param("u", signed.user()).query(String.class)
                .single()).isEqualTo(String.join("|", requestId, VERSION, AccountDeletionPolicy.noticeHash(),
                        sha256("account-deletion-key\n" + key)));
        assertThat(lifecycleOf(signed.user())).isEqualTo("DELETION_IN_PROGRESS");
        assertThat(actionGrantConsumed(grant)).isTrue();
        // 삭제를 시작만 했다 — 모듈 데이터·payload·tombstone은 그대로다(5.2·5.3).
        assertThat(jdbc().sql("SELECT count(*) FROM identity_account WHERE user_id=:u").param("u", signed.user())
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(countOf("source_connection", signed.user())).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM operations_deletion_tombstone").query(Integer.class).single())
                .isZero();

        // 다른 세션은 사라졌고 요청 세션 하나만 남았다.
        assertThat(sessionCountOf(signed.user())).isEqualTo(1);
        assertThat(get(DELETION_PATH, other).statusCode()).isEqualTo(401);
        assertThat(topLevelFieldsOf(sessionState(other).body())).containsEntry("authenticated", false)
                .doesNotContainKey("deletionInProgress");

        // 남은 세션은 삭제 진행을 안다.
        var session = topLevelFieldsOf(sessionState(kept).body());
        assertThat(session).containsEntry("authenticated", true).containsEntry("deletionInProgress", true)
                .containsKey("csrfToken");
        String csrf = csrfTokenOf(kept);
        Signed remaining = new Signed(signed.user(), signed.subject(), kept, csrf == null ? "" : csrf);
        JsonNode progress = json(get(DELETION_PATH, kept).body());
        assertThat(progress.get("request").get("requestId").asString()).isEqualTo(requestId);
        assertThat(progress.get("request").get("requestedAt").asString()).isEqualTo(requestedAt.toString());
        assertThat(progress.get("request").get("state").asString()).isEqualTo("DELETION_IN_PROGRESS");

        // 응답 유실 재전송: 같은 쿠키·같은 키는 같은 본문, 새 행·회전 없음.
        var replay = confirm(remaining, VERSION, PHRASE, key);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replay.body())).isEqualTo(result);
        assertThat(replay.body()).isEqualTo(confirmed.body());
        assertThat(sessionCookieOf(replay)).isEmpty();
        assertThat(requestCount(signed.user())).isEqualTo(1);

        // Story 5.2: Worker가 실행을 집고 한 단계가 실패해 재시도 필요가 돼도 확정의 재전송은 처음과 바이트까지 같다 — 확정 응답은 지금의
        // 진행을 싣지 않는다(지금의 진행은 조회만 싣는다).
        var lease = this.runs.claim(com.bokrilog.sync.WorkerLease.DURATION).orElseThrow();
        new org.springframework.transaction.support.TransactionTemplate(this.transactionManager)
                .executeWithoutResult(status -> {
                    assertThat(this.runs.hold(lease, com.bokrilog.sync.WorkerLease.DURATION)).isTrue();
                    this.runs.stepsNeedRetry(lease, com.bokrilog.identity.DeletionStep.CREDENTIALS,
                            Map.of(com.bokrilog.identity.DeletionStep.CREDENTIALS,
                                    com.bokrilog.identity.AccountDeletionRunApi.STEP_FAILED));
                });
        var afterWorker = confirm(remaining, VERSION, PHRASE, key);
        assertThat(afterWorker.statusCode()).as(afterWorker.body()).isEqualTo(200);
        assertThat(afterWorker.body()).isEqualTo(confirmed.body());
        assertThat(json(get(DELETION_PATH, kept).body()).get("request").get("progress").get("status").asString())
                .isEqualTo("RETRY_NEEDED");

        // 다른 키의 두 번째 요청과 삭제 중 준비는 같은 409다.
        var second = confirm(remaining, VERSION, PHRASE, key("delete-main-second"));
        assertRefused(second, 409, "ACCOUNT_DELETION_IN_PROGRESS");
        var again = prepare(remaining);
        assertRefused(again, 409, "ACCOUNT_DELETION_IN_PROGRESS");
        assertThat(actionGrantCountOf(signed.user())).isEqualTo(1);
        assertThat(requestCount(signed.user())).isEqualTo(1);

        // 로그아웃할 수 있다(삭제 중 계정은 다시 로그인할 수 없다 — 1.4 계약).
        assertThat(logout(kept, csrf).statusCode()).isEqualTo(204);

        assertThat(lines(output, "account deletion request: ")).anySatisfy(line -> assertThat(line)
                .contains("step=prepare result=PREPARED status=200 latencyMs="))
                .anySatisfy(line -> assertThat(line).contains("step=confirm result=REQUESTED status=200 latencyMs="))
                .anySatisfy(line -> assertThat(line).contains("step=confirm result=REPLAYED status=200 latencyMs="));
        assertThat(lines(output, "account deletion requested: ")).singleElement();
        for (String marker : List.of("account deletion request", "account deletion requested")) {
            for (String line : lines(output, marker)) {
                for (String forbidden : List.of(signed.user().toString(), key, grant, PHRASE, requestId,
                        AccountDeletionPolicy.noticeHash()))
                    assertThat(line).as(forbidden).doesNotContain(forbidden);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ 거절

    /** 재인증이 없거나 오래됐으면 허가·무장이 서지 않고, 무장 없는 확정은 같은 단일 거부다. */
    @Test
    void 재인증이_없으면_허가도_무장도_없다() throws Exception {
        String subject = "delete-no-reauth-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String csrf = csrfTokenOf(cookie);
        Signed bare = new Signed(user, subject, cookie, csrf == null ? "" : csrf);

        assertRefused(prepare(bare), 403, "REAUTHENTICATION_REQUIRED");
        assertThat(actionGrantCountOf(user)).isZero();
        assertRefused(confirm(bare, VERSION, PHRASE, key("delete-no-reauth")), 403, "ACCOUNT_DELETION_NOT_CONFIRMED");
        assertThat(lifecycleOf(user)).isEqualTo("ACTIVE");
        assertThat(requestCount(user)).isZero();
    }

    /**
     * 입력 문구가 다르면 허가를 소비하지 않고 무장을 남긴다 — 같은 대화상자에서 다시 입력하면(NFC·양끝 공백은 같은 문구) 성립한다.
     * 정책 버전이 다르면 409이고 저장하지 않는다. 키가 없으면 422다.
     */
    @Test
    void 문구_불일치는_무장을_남기고_정책_변경은_409이며_키가_없으면_422다() throws Exception {
        Signed signed = signedIn("delete-mismatch");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());

        assertRefused(confirm(signed, VERSION, "계정을 삭제", key("delete-mismatch-1")), 403, "CONFIRMATION_MISMATCH");
        assertRefused(confirm(signed, "ACCOUNT-DELETION-0.9", PHRASE, key("delete-mismatch-2")), 409,
                "ACCOUNT_DELETION_POLICY_CHANGED");
        // 1.0을 본 화면(G-5 전 결정 D-3 이전 고지)의 확정도 저장하지 않는다 — 고지 문장이 바뀌어 정책 버전이 올랐다.
        assertRefused(confirm(signed, "ACCOUNT-DELETION-1.0", PHRASE, key("delete-mismatch-old")), 409,
                "ACCOUNT_DELETION_POLICY_CHANGED");
        var keyless = sendJson("POST", CONFIRM_PATH, body(VERSION, PHRASE), signed.cookie(),
                headers(signed.csrf(), null));
        assertRefused(keyless, 422, "IDEMPOTENCY_KEY_REQUIRED");
        var malformed = sendJson("POST", CONFIRM_PATH, "{\"policyVersion\":3}", signed.cookie(),
                headers(signed.csrf(), key("delete-mismatch-3")));
        assertRefused(malformed, 400, "BAD_REQUEST");
        assertThat(actionGrantConsumed(grant)).isFalse();
        assertThat(lifecycleOf(signed.user())).isEqualTo("ACTIVE");
        assertThat(requestCount(signed.user())).isZero();

        var confirmed = confirm(signed, VERSION, "  " + PHRASE + " ", key("delete-mismatch-4"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(actionGrantConsumed(grant)).isTrue();
        assertThat(lifecycleOf(signed.user())).isEqualTo("DELETION_IN_PROGRESS");
    }

    /** production(G-5 승인 전)이면 준비·확정이 403이고 아무 행도 만들지 않으며 조회는 {@code available:false}다. */
    @Test
    void production에서는_삭제_요청을_열지_않는다() throws Exception {
        Signed signed = signedIn("delete-production");
        boolean before = this.service.available();
        this.service.reinterpret("production");
        try {
            JsonNode view = json(get(DELETION_PATH, signed.cookie()).body());
            assertThat(view.get("available").asBoolean()).isFalse();
            assertRefused(prepare(signed), 403, "ACCOUNT_DELETION_UNAVAILABLE");
            assertRefused(confirm(signed, VERSION, PHRASE, key("delete-production")), 403,
                    "ACCOUNT_DELETION_UNAVAILABLE");
            assertThat(actionGrantCountOf(signed.user())).isZero();
            assertThat(requestCount(signed.user())).isZero();
            assertThat(lifecycleOf(signed.user())).isEqualTo("ACTIVE");
        } finally {
            this.service.reinterpret(before ? "development" : "production");
        }
    }

    /** 취소는 무장이 있든 없든 204·no-store이고 그 허가를 무효화한다. 그 뒤의 확정은 단일 거부다. */
    @Test
    void 취소는_언제나_204이고_허가를_무효화한다() throws Exception {
        Signed signed = signedIn("delete-cancel");
        var idle = send("POST", CANCEL_PATH, signed.cookie(), headers(signed.csrf(), null));
        assertThat(idle.statusCode()).isEqualTo(204);
        assertThat(idle.headers().firstValue("Cache-Control").orElse("")).contains("no-store");

        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());
        var cancelled = send("POST", CANCEL_PATH, signed.cookie(), headers(signed.csrf(), null));
        assertThat(cancelled.statusCode()).isEqualTo(204);
        assertThat(cancelled.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(actionGrantExists(grant)).isFalse();
        assertRefused(confirm(signed, VERSION, PHRASE, key("delete-cancel")), 403, "ACCOUNT_DELETION_NOT_CONFIRMED");
        assertThat(lifecycleOf(signed.user())).isEqualTo("ACTIVE");
    }

    /** 인증 없이는 401, CSRF·{@code Origin}이 없으면 403이다 — 새 접두사가 같은 술어 아래 있다. */
    @Test
    void 새_접두사는_인증과_CSRF_Origin을_요구한다() throws Exception {
        assertThat(get(DELETION_PATH, null).statusCode()).isEqualTo(401);
        Signed signed = signedIn("delete-guard");
        assertThat(send("POST", DELETION_PATH, signed.cookie(), "Origin", OidcStubConfiguration.PUBLIC_BASE_URL)
                .statusCode()).isEqualTo(403);
        assertThat(send("POST", DELETION_PATH, signed.cookie(), AuthSecurityConfig.CSRF_HEADER_NAME, signed.csrf())
                .statusCode()).isEqualTo(403);
        assertThat(actionGrantCountOf(signed.user())).isZero();
    }

    // ------------------------------------------------------------------------------------------ 원자성과 경합

    /** 요청 행 INSERT 뒤 전환이 실패하면 허가 소비까지 함께 롤백된다. 같은 무장으로 다시 확정하면 성립한다. */
    @Test
    void 확정의_한_부분이_실패하면_소비까지_롤백된다() throws Exception {
        Signed signed = signedIn("delete-atomic");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());
        jdbc().sql("CREATE OR REPLACE FUNCTION fail_deletion_transition() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.state='DELETION_IN_PROGRESS' THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$")
                .update();
        jdbc().sql("CREATE OR REPLACE TRIGGER fail_deletion_transition BEFORE UPDATE ON identity_account_lifecycle "
                + "FOR EACH ROW EXECUTE FUNCTION fail_deletion_transition()").update();
        HttpResponse<String> failed;
        try {
            failed = confirm(signed, VERSION, PHRASE, key("delete-atomic-1"));
        } finally {
            jdbc().sql("DROP TRIGGER IF EXISTS fail_deletion_transition ON identity_account_lifecycle").update();
            jdbc().sql("DROP FUNCTION IF EXISTS fail_deletion_transition()").update();
        }
        assertRefused(failed, 500, "INTERNAL_ERROR");
        assertThat(failed.body()).doesNotContain("injected").doesNotContain("identity_");
        assertThat(requestCount(signed.user())).isZero();
        assertThat(lifecycleOf(signed.user())).isEqualTo("ACTIVE");
        assertThat(actionGrantConsumed(grant)).isFalse();
        assertThat(sessionCookieOf(failed)).isEmpty();

        var retried = confirm(signed, VERSION, PHRASE, key("delete-atomic-2"));
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        assertThat(requestCount(signed.user())).isEqualTo(1);
        assertThat(actionGrantConsumed(grant)).isTrue();
    }

    /** 같은 세션의 두 확정이 다른 키로 겹치면 요청은 하나만 생긴다. 진 쪽은 요청을 만들지 않은 거절이다. */
    @Test
    void 동시_확정_둘은_요청_하나만_만든다() throws Exception {
        Signed signed = signedIn("delete-race");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        String first = key("delete-race-1");
        String second = key("delete-race-2");
        var a = async(() -> confirm(signed, VERSION, PHRASE, first));
        var b = async(() -> confirm(signed, VERSION, PHRASE, second));
        List<HttpResponse<String>> responses = List.of(a.join(), b.join());

        assertThat(responses).filteredOn(response -> response.statusCode() == 200).hasSize(1);
        assertThat(responses).filteredOn(response -> response.statusCode() != 200)
                .singleElement().satisfies(response -> assertThat(response.statusCode()).isIn(401, 403, 409));
        assertThat(requestCount(signed.user())).isEqualTo(1);
        String winner = responses.get(0).statusCode() == 200 ? first : second;
        assertThat(jdbc().sql("SELECT idempotency_key_digest FROM identity_deletion_request WHERE user_id=:u")
                .param("u", signed.user()).query(String.class).single())
                .isEqualTo(sha256("account-deletion-key\n" + winner));
    }

    /** identity의 요청 하나가 생명주기 잠금으로 직렬화된다 — 동시 두 요청 가운데 하나만 만들고 다른 키는 진행 중을 본다. */
    @Test
    void identity_요청은_잠금_아래에서_하나만_만든다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<DeletionRequestOutcome>> calls = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            String digest = sha256("delete-identity-" + index + "-" + UUID.randomUUID());
            CompletableFuture<DeletionRequestOutcome> call = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    call.complete(accountDeletion().requestDeletion(user, VERSION, AccountDeletionPolicy.noticeHash(),
                            digest));
                } catch (Exception failure) {
                    call.completeExceptionally(failure);
                }
            });
            calls.add(call);
        }
        start.countDown();
        List<DeletionRequestOutcome> outcomes = calls.stream().map(CompletableFuture::join).toList();
        assertThat(outcomes).filteredOn(DeletionRequestOutcome.Created.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(DeletionRequestOutcome.InProgress.class::isInstance).hasSize(1);
        assertThat(requestCount(user)).isEqualTo(1);
        assertThat(lifecycleOf(user)).isEqualTo("DELETION_IN_PROGRESS");

        // 받아들일 수 없는 값과 요청 없이 시작된 삭제는 행을 만들지 않는다.
        UUID other = connect(DevBybitStub.Scenario.UTA2);
        assertThat(accountDeletion().requestDeletion(other, "v1", AccountDeletionPolicy.noticeHash(),
                sha256("x"))).isInstanceOf(DeletionRequestOutcome.Denied.class);
        accountDeletion().beginDeletion(other);
        assertThat(accountDeletion().requestDeletion(other, VERSION, AccountDeletionPolicy.noticeHash(),
                sha256("y"))).isInstanceOf(DeletionRequestOutcome.Denied.class);
        assertThat(requestCount(other)).isZero();
    }

    /** 삭제 완료(payload 삭제)는 요청 행도 지우고, 잔여 행 수는 요청 행을 센다(Epic 2 회고 item-24의 새 표). */
    @Test
    void 삭제_완료는_요청_행도_지우고_잔여_행이_그것을_센다() throws Exception {
        UUID user = accountLifecycle().createAccount();
        accountDeletion().requestDeletion(user, VERSION, AccountDeletionPolicy.noticeHash(), sha256("erase"));
        long before = accountDeletion().remainingRowsOf(user);
        assertThat(requestCount(user)).isEqualTo(1);

        assertThat(accountDeletion().erasePayload(user)).isEqualTo(com.bokrilog.identity.DeletionOutcome.COMPLETED);
        assertThat(requestCount(user)).isZero();
        assertThat(accountDeletion().deletionRequestOf(user)).isEmpty();
        // payload·요청 행이 빠진 만큼 줄었다(소셜 매핑·세션은 이 계정에 없다).
        assertThat(before).isEqualTo(2);
        assertThat(accountDeletion().remainingRowsOf(user)).isZero();
    }

    // ------------------------------------------------------------------------------------------ 삭제 중 다른 경로

    /** 남은 요청 세션으로 다른 표면에 가면 지금과 같은 거부다 — 새 차단 장치 없이 기존 삭제 상태 판정이 막고 아무것도 쓰지 않는다. */
    @Test
    void 삭제_중에는_다른_경로가_기존대로_거부하고_쓰지_않는다() throws Exception {
        /*
         * Bybit 연결이 없는 사용자다 — 연결 준비가 "이미 활성 연결이 있다"로 거절되지 않게(그 거절도 같은 403으로 접혀 원인이
         * 가려진다). 아래 본문은 모두 각 표면이 활성 계정에서 받아들이는 모양이라, 거부의 원인은 삭제 진행 상태다.
         */
        Signed signed = signedInWithoutConnection("delete-elsewhere");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        var confirmed = confirm(signed, VERSION, PHRASE, key("delete-elsewhere"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(sessionCookieOf(confirmed)).isEmpty();
        String kept = signed.cookie();
        String csrf = csrfTokenOf(kept);
        int grantsBefore = (int) actionGrantCountOf(signed.user());

        Map<String, HttpResponse<String>> answers = new java.util.LinkedHashMap<>();
        answers.put("dashboard", get(AuthSecurityConfig.DASHBOARD_PATH, kept));
        answers.put("sync", sendJson("POST", AuthSecurityConfig.DASHBOARD_PATH + "/sync", "{\"trigger\":\"MANUAL\"}",
                kept, headers(csrf, key("delete-elsewhere-sync"))));
        answers.put("connection", send("POST", AuthSecurityConfig.BYBIT_CONNECTION_PATH, kept,
                headers(csrf, null)));
        answers.put("adConsent", sendJson("POST", AdConsentController.AD_CONSENT_PATH,
                "{\"choice\":\"GRANTED\",\"policyVersion\":\"" + com.bokrilog.engagement.AdConsentApi.POLICY_VERSION
                        + "\"}", kept, headers(csrf, key("delete-elsewhere-ad"))));
        answers.put("inquiry", sendJson("POST", AuthSecurityConfig.SUPPORT_PATH + "/inquiries",
                "{\"category\":\"ACCOUNT\",\"title\":\"로그인 문의\",\"description\":\"로그인이 안 됩니다.\","
                        + "\"contact\":\"IN_APP\"}", kept, headers(csrf, key("delete-elsewhere-inquiry"))));
        answers.put("inquiries", get(AuthSecurityConfig.SUPPORT_PATH + "/inquiries", kept));

        Map<String, String> observed = new java.util.LinkedHashMap<>();
        for (var answer : answers.entrySet()) {
            var response = answer.getValue();
            Object code = topLevelFieldsOf(response.body()).get("code");
            observed.put(answer.getKey(), response.statusCode() + " " + code);
            // 공통 오류 모양 하나다.
            assertThat(topLevelFieldsOf(response.body())).as(answer.getKey() + " " + response.body())
                    .containsOnlyKeys("code", "message", "correlationId");
        }
        assertThat(observed).isEqualTo(Map.of(
                "dashboard", "403 FORBIDDEN",
                "sync", "403 FORBIDDEN",
                "connection", "403 FORBIDDEN",
                "adConsent", "404 NOT_FOUND",
                "inquiry", "404 NOT_FOUND",
                "inquiries", "404 NOT_FOUND"));
        assertThat(countOf("sync_plan_sync_job", signed.user())).isZero();
        assertThat(countOf("engagement_ad_consent_decision", signed.user())).isZero();
        assertThat(countOf("support_inquiry", signed.user())).isZero();
        assertThat(countOf("source_connection", signed.user())).isZero();
        assertThat(actionGrantCountOf(signed.user())).isEqualTo(grantsBefore);
        assertThat(lifecycleOf(signed.user())).isEqualTo("DELETION_IN_PROGRESS");
    }

    // ------------------------------------------------------------------------------------------ 공유 표

    /** 정책 code·버전·기한·법정 보존과 거절 code 집합이 화면과 같은 공유 표를 본다. */
    @Test
    void 정책과_거절_code가_화면의_공유_표와_같다() throws Exception {
        JsonNode table = JsonMapper.builder().build().readTree(Files.readString(SHARED_FIXTURE, StandardCharsets.UTF_8));
        assertThat(table.get("path").asString()).isEqualTo(DELETION_PATH);
        assertThat(table.get("version").asString()).isEqualTo(AccountDeletionPolicy.VERSION);
        assertThat(table.get("confirmation").asString()).isEqualTo(AccountDeletionPolicy.CONFIRMATION);
        assertThat(table.get("backupDestroyDays").asInt()).isEqualTo(AccountDeletionPolicy.BACKUP_DESTROY_DAYS);
        assertThat(strings(table.get("targets"))).isEqualTo(AccountDeletionPolicy.TARGETS);
        // 법정 분리 보존 항목은 G-5 전이라 두 벌 모두 비어 있다(항목이 생기면 네 필드를 함께 대조한다).
        assertThat(table.get("legalRetention").isEmpty()).isTrue();
        assertThat(AccountDeletionPolicy.LEGAL_RETENTION).isEmpty();
        assertThat(strings(table.get("refusalCodes"))).containsExactlyInAnyOrderElementsOf(
                AccountDeletionController.emittedCodes());
        // 삭제 단계 code(Story 5.2) — 화면의 실패 범위 문장 맵과 같은 집합·순서다.
        assertThat(strings(table.get("erasureSteps"))).containsExactlyElementsOf(
                Arrays.stream(DeletionStep.values()).map(Enum::name).toList());
        assertThat(table.get("retryPath").asString()).isEqualTo(AccountDeletionController.RETRY_PATH);
        // 진행 상태 값 — 화면의 닫힌 집합과 같다.
        assertThat(strings(table.get("progressStatuses"))).containsExactly(AccountDeletionController.PROGRESS_IN_PROGRESS,
                AccountDeletionController.PROGRESS_RETRY_NEEDED, AccountDeletionController.PROGRESS_ERASED,
                AccountDeletionController.PROGRESS_COMPLETED);
        // 완료 조회의 백업 파기 상태(Story 5.4) — tombstone 상태 그대로다.
        assertThat(strings(table.get("backupDestructionStatuses"))).containsExactlyElementsOf(
                Arrays.stream(DestructionStatus.values()).map(Enum::name).toList());
        assertThat(Arrays.stream(RunStatus.values())
                .map(status -> AccountDeletionController.progressViewOf(new DeletionRun(status, null, List.of())).status())
                .distinct().toList())
                .containsExactlyInAnyOrderElementsOf(strings(table.get("progressStatuses")));
    }

    // ------------------------------------------------------------------------------------------ 리뷰 보강

    /** 준비 본문의 정책 버전(화면이 본 값)이 지금 정책과 다르면 409이고 허가·무장이 없다. 본문이 없으면 400이다. */
    @Test
    void 준비의_정책_버전이_다르면_409이고_아무것도_무장하지_않는다() throws Exception {
        Signed signed = signedIn("delete-prepare-version");
        assertRefused(prepare(signed, "ACCOUNT-DELETION-0.9"), 409, "ACCOUNT_DELETION_POLICY_CHANGED");
        assertRefused(send("POST", DELETION_PATH, signed.cookie(), headers(signed.csrf(), null)), 400, "BAD_REQUEST");
        assertThat(actionGrantCountOf(signed.user())).isZero();
        assertThat(sessionAttribute(sessionIdOf(signed.cookie()), AccountDeletionFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(lifecycleOf(signed.user())).isEqualTo("ACTIVE");
    }

    /** 응답 유실 뒤 같은 쿠키·같은 키의 재전송은 같은 200 본문이고 새 행·새 쿠키가 없다(회전하지 않으므로 옛 쿠키가 살아 있다). */
    @Test
    void 응답_유실_뒤_같은_쿠키의_같은_키_재전송은_같은_200이다() throws Exception {
        Signed signed = signedIn("delete-resend");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        String key = key("delete-resend");
        var first = confirm(signed, VERSION, PHRASE, key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(sessionCookieOf(first)).isEmpty();

        var resent = confirm(signed, VERSION, PHRASE, key);
        assertThat(resent.statusCode()).as(resent.body()).isEqualTo(200);
        assertThat(resent.body()).isEqualTo(first.body());
        assertThat(sessionCookieOf(resent)).isEmpty();
        assertThat(requestCount(signed.user())).isEqualTo(1);
        assertThat(sessionCountOf(signed.user())).isEqualTo(1);
    }

    /**
     * 커밋 뒤 세션 정리 단계가 실패해도(세션이 그 사이 무효화됐다) 커밋된 요청은 그대로 성공이다 — 단계 code 한 줄만 남고 오류로
     * 바뀌지 않는다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 커밋_뒤_정리가_실패해도_커밋된_요청은_성공으로_답한다(CapturedOutput output) throws Exception {
        UUID user = accountLifecycle().createAccount();
        FailingSession session = new FailingSession(UUID.randomUUID().toString());
        MockHttpSession granted = grantSession(user);
        for (String name : java.util.Collections.list(granted.getAttributeNames())) {
            session.setAttribute(name, granted.getAttribute(name));
        }
        assertThat(this.service.prepare(session, user, VERSION))
                .isInstanceOf(AccountDeletionRequestService.Preparation.Armed.class);

        session.failing = true;
        var outcome = this.service.confirm(session, user, VERSION, PHRASE, key("delete-settle-failure"));

        assertThat(outcome).isInstanceOfSatisfying(AccountDeletionRequestService.Outcome.Done.class,
                done -> assertThat(done.created()).isTrue());
        assertThat(requestCount(user)).isEqualTo(1);
        assertThat(lifecycleOf(user)).isEqualTo("DELETION_IN_PROGRESS");
        assertThat(output.getAll()).contains("SETTLE_DISCARD_ARMING_FAILED")
                .contains("SETTLE_CLEAR_REAUTHENTICATION_FAILED");
        assertThat(lines(output, "account deletion settle failed")).allSatisfy(line -> assertThat(line)
                .doesNotContain(user.toString()).doesNotContain(PHRASE));
        // 줄마다 단계 code와 예외의 단순 클래스 이름 하나다 — 예외 메시지·스택·식별자는 없다.
        assertThat(settleFailures(output)).extracting(record -> record.get("code") + "|" + record.get("exception"))
                .contains("SETTLE_DISCARD_ARMING_FAILED|IllegalStateException",
                        "SETTLE_CLEAR_REAUTHENTICATION_FAILED|IllegalStateException");
        assertThat(output.getAll()).doesNotContain("무효화된 세션");
    }

    /**
     * 첫 정리 단계(다른 세션 폐기)가 실패해도 커밋된 요청은 성공으로 답하고 뒤 두 단계(무장 폐기·재인증 시각 제거)는 그대로 돈다. 실패는
     * 단계 code와 예외 단순 이름 한 줄로만 남는다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 다른_세션_폐기가_실패해도_요청은_성공이고_뒤_단계는_돈다(CapturedOutput output) throws Exception {
        Signed signed = signedIn("delete-revoke-failure");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        String other = sessionCookieOf(signIn(signed.subject())).orElseThrow();
        assertThat(sessionCountOf(signed.user())).isEqualTo(2);
        String kept = sessionIdOf(signed.cookie());
        assertThat(sessionAttribute(kept, ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE)).isNotNull();

        // 이 사용자의 세션 행 DELETE만 실패시킨다(다른 시험·만료 정리의 세션은 건드리지 않는다).
        jdbc().sql("CREATE OR REPLACE FUNCTION fail_session_revoke() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF OLD.principal_name = TG_ARGV[0] THEN RAISE EXCEPTION 'injected'; END IF; RETURN OLD; END $$")
                .update();
        jdbc().sql("CREATE OR REPLACE TRIGGER fail_session_revoke BEFORE DELETE ON spring_session FOR EACH ROW "
                + "EXECUTE FUNCTION fail_session_revoke('" + signed.user() + "')").update();
        HttpResponse<String> confirmed;
        try {
            confirmed = confirm(signed, VERSION, PHRASE, key("delete-revoke-failure"));
        } finally {
            jdbc().sql("DROP TRIGGER IF EXISTS fail_session_revoke ON spring_session").update();
            jdbc().sql("DROP FUNCTION IF EXISTS fail_session_revoke()").update();
        }

        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(confirmed.body())).containsEntry("state", "DELETION_IN_PROGRESS");
        assertThat(requestCount(signed.user())).isEqualTo(1);
        assertThat(lifecycleOf(signed.user())).isEqualTo("DELETION_IN_PROGRESS");
        // 첫 단계는 실패했다 — 다른 세션이 남아 있다.
        assertThat(sessionCountOf(signed.user())).isEqualTo(2);
        assertThat(sessionIdOf(other)).isNotEqualTo(kept);
        // 뒤 두 단계는 돌았다.
        assertThat(sessionAttribute(kept, AccountDeletionFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(sessionAttribute(kept, ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE)).isNull();
        assertThat(settleFailures(output)).singleElement().satisfies(record -> {
            assertThat(record).containsEntry("code", "SETTLE_REVOKE_OTHER_SESSIONS_FAILED");
            assertThat(record.get("exception")).isNotNull().asString().doesNotContain(".").isNotBlank();
        });
        assertThat(lines(output, "account deletion settle failed")).allSatisfy(line -> assertThat(line)
                .doesNotContain(signed.user().toString()).doesNotContain("injected"));
        assertThat(output.getAll()).doesNotContain("injected");
    }

    /**
     * 같은 키·다른 본문은 같은 요청이 아니다 — 커밋된 요청이 있어도 정책 버전이나 입력 문구가 다르면 409
     * {@link com.bokrilog.sync.IdempotencyKey#CONFLICT_CODE}이고 새 행이 없다. 같은 본문은 여전히 같은 200이다.
     */
    @Test
    void 같은_키_다른_본문의_재전송은_충돌이다() throws Exception {
        Signed signed = signedIn("delete-key-conflict");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        String key = key("delete-key-conflict");
        var first = confirm(signed, VERSION, PHRASE, key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);

        assertRefused(confirm(signed, "ACCOUNT-DELETION-0.9", PHRASE, key), 409,
                com.bokrilog.sync.IdempotencyKey.CONFLICT_CODE);
        assertRefused(confirm(signed, VERSION, "다른 문구입니다", key), 409,
                com.bokrilog.sync.IdempotencyKey.CONFLICT_CODE);
        assertThat(requestCount(signed.user())).isEqualTo(1);
        assertThat(lifecycleOf(signed.user())).isEqualTo("DELETION_IN_PROGRESS");

        // 정규화 뒤 같은 문구(양끝 공백·NFD)는 같은 본문이다.
        String nfd = java.text.Normalizer.normalize(PHRASE, java.text.Normalizer.Form.NFD);
        var same = confirm(signed, VERSION, " " + nfd + " ", key);
        assertThat(same.statusCode()).as(same.body()).isEqualTo(200);
        assertThat(same.body()).isEqualTo(first.body());
        assertThat(requestCount(signed.user())).isEqualTo(1);
    }

    /**
     * 앞선 확정이 커밋한 뒤 세션 정리 전에 끝났다(요청 행만 있고 다른 세션·무장·재인증 시각이 남았다). 같은 키·같은 본문의 재전송은 같은
     * 200으로 답하면서 그 정리를 마친다 — 다른 세션을 폐기하고 무장과 재인증 시각을 지우며, 이 세션의 ID는 그대로다.
     */
    @Test
    void 같은_키_재전송은_끝나지_않은_세션_정리를_마친다() throws Exception {
        Signed signed = signedIn("delete-replay-settle");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        String other = sessionCookieOf(signIn(signed.subject())).orElseThrow();
        String kept = sessionIdOf(signed.cookie());
        String key = key("delete-replay-settle");
        // 커밋만 하고 정리 전에 끝난 확정을 재현한다 — identity가 요청 행과 삭제 진행 전환을 쓰고 세션은 손대지 않는다.
        assertThat(accountDeletion().requestDeletion(signed.user(), VERSION, AccountDeletionPolicy.noticeHash(),
                AccountDeletionRequestService.keyDigestOf(key))).isInstanceOf(DeletionRequestOutcome.Created.class);
        assertThat(sessionCountOf(signed.user())).isEqualTo(2);
        assertThat(sessionAttribute(kept, AccountDeletionFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(AccountDeletionFlow.Armed.class);
        assertThat(sessionAttribute(kept, ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE)).isNotNull();

        var replay = confirm(signed, VERSION, PHRASE, key);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replay.body())).containsEntry("state", "DELETION_IN_PROGRESS")
                .containsEntry("policyVersion", VERSION);
        assertThat(sessionCookieOf(replay)).isEmpty();
        assertThat(sessionCountOf(signed.user())).isEqualTo(1);
        assertThat(get(DELETION_PATH, other).statusCode()).isEqualTo(401);
        assertThat(sessionIdOf(signed.cookie())).isEqualTo(kept);
        assertThat(sessionAttribute(kept, AccountDeletionFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(sessionAttribute(kept, ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE)).isNull();
        assertThat(requestCount(signed.user())).isEqualTo(1);

        // 정리는 멱등이다 — 한 번 더 보내도 같은 200이고 남은 이 세션은 그대로다.
        var again = confirm(signed, VERSION, PHRASE, key);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(replay.body());
        assertThat(sessionCountOf(signed.user())).isEqualTo(1);
    }

    /**
     * 고지 hash를 고정한다 — 정책 상수의 정규 직렬화를 여기 손으로 적고 그 SHA-256을 실행 중에 계산해 대조한다. {@code TARGETS}·
     * {@code CONFIRMATION}·{@code BACKUP_DESTROY_DAYS}·{@code LEGAL_RETENTION}·{@code NOTICE_DIGEST} 가운데 무엇이든 바꾸면 이 시험이
     * 깨진다 — 그때는 {@code VERSION}을 올리고(이미 본 화면의 확정이 409로 다시 읽게 된다) 아래 직렬화를 새 정책으로 고친다. 마지막 줄의
     * 화면 고지 digest는 리터럴로 적지 않는다 — 공유 표와의 대조는 {@link #고지_문장_digest는_공유_표의_notice에서_다시_계산한_값이다}가 한다.
     */
    @Test
    void 고지_hash는_ACCOUNT_DELETION_1_1의_정규_직렬화다() throws Exception {
        String canonical = String.join("\n",
                "version=ACCOUNT-DELETION-1.1",
                "targets=ACCOUNT_AND_SIGN_IN,SESSIONS,BYBIT_CREDENTIALS,BYBIT_CONNECTIONS,SOURCE_EVENTS,CASHFLOWS,"
                        + "SNAPSHOTS,PLANS,CALCULATIONS,SYNC_JOBS,SUPPORT_INQUIRIES,AD_RECORDS,OPERATIONS_RECORDS",
                "backupDestroyDays=30",
                "legalRetention=",
                "confirmation=계정을 삭제합니다",
                "notice=" + noticeDigestOfSharedTable());
        assertThat(AccountDeletionPolicy.VERSION).isEqualTo("ACCOUNT-DELETION-1.1");
        assertThat(AccountDeletionPolicy.noticeHash()).isEqualTo(sha256(canonical)).matches("[0-9a-f]{64}");
    }

    /**
     * 화면 고지 문장의 원본은 공유 표의 {@code notice} 하나다(D-3). 표에서 정규 직렬화를 다시 계산한 SHA-256이 서버 상수
     * {@code NOTICE_DIGEST}와 같고, 표의 버전별 이력({@code noticeDigestByVersion})에서 지금 {@code VERSION}의 행과도 같아야 한다. 이력의
     * digest는 서로 모두 달라야 한다 — 그래서 문장을 바꾸면 새 버전 행을 더하고 {@code VERSION}·상수를 올려야 하고, 버전을 그대로 두려면 지금
     * 행을 고쳐 써야 한다(리뷰에 이력 수정으로 보인다). 삭제 대상 문장의 code는 정책 {@code TARGETS}와 같은 집합·순서이고, 백업 문장 틀은 일
     * 수 자리 하나를 가지며, 정규 직렬화가 줄 단위이므로 어느 문장에도 줄바꿈이 없다.
     */
    @Test
    void 고지_문장_digest는_공유_표의_notice에서_다시_계산한_값이다() throws Exception {
        JsonNode table = sharedTable();
        JsonNode notice = table.get("notice");
        assertThat(notice).isNotNull();
        assertThat(notice.get("targets").propertyNames()).containsExactlyElementsOf(AccountDeletionPolicy.TARGETS);
        assertThat(strings(notice.get("kept"))).isNotEmpty();
        assertThat(notice.get("backup").asString().split("\\{days}", -1)).hasSize(2);
        assertThat(notice.get("legalNone").asString()).isNotBlank();

        // 정규 직렬화의 경계 — 줄바꿈이 든 문장은 다른 줄로 읽혀 직렬화가 모호해진다.
        List<String> sentences = new ArrayList<>();
        AccountDeletionPolicy.TARGETS.forEach(code -> sentences.add(notice.get("targets").get(code).asString()));
        sentences.addAll(strings(notice.get("kept")));
        sentences.add(notice.get("backup").asString());
        sentences.add(notice.get("legalNone").asString());
        assertThat(sentences).allSatisfy(sentence -> assertThat(sentence).doesNotContain("\n").doesNotContain("\r"));

        String recomputed = noticeDigestOfSharedTable();
        assertThat(AccountDeletionPolicy.NOTICE_DIGEST).matches("[0-9a-f]{64}").isEqualTo(recomputed);

        // 버전별 고지 digest 이력 — 지금 버전의 행이 상수와 같고, 행의 digest는 서로 모두 다르다.
        JsonNode history = table.get("noticeDigestByVersion");
        assertThat(history).isNotNull();
        assertThat(history.get(AccountDeletionPolicy.VERSION)).as("지금 버전의 이력 행").isNotNull();
        assertThat(history.get(AccountDeletionPolicy.VERSION).asString()).isEqualTo(AccountDeletionPolicy.NOTICE_DIGEST);
        List<String> digests = new ArrayList<>();
        for (String version : history.propertyNames()) {
            assertThat(version).matches("ACCOUNT-DELETION-[0-9]+\\.[0-9]+");
            digests.add(history.get(version).asString());
        }
        assertThat(digests).allSatisfy(digest -> assertThat(digest).matches("[0-9a-f]{64}")).doesNotHaveDuplicates();
    }

    /** 공유 표 {@code notice}의 정규 직렬화(정책 설계 노트 그대로)의 SHA-256. */
    private static String noticeDigestOfSharedTable() throws Exception {
        JsonNode notice = sharedTable().get("notice");
        List<String> lines = new ArrayList<>();
        for (String code : AccountDeletionPolicy.TARGETS) {
            JsonNode sentence = notice.get("targets").get(code);
            assertThat(sentence).as(code).isNotNull();
            lines.add("target." + code + "=" + sentence.asString());
        }
        for (String kept : strings(notice.get("kept"))) {
            lines.add("kept=" + kept);
        }
        lines.add("backup=" + notice.get("backup").asString());
        lines.add("legalNone=" + notice.get("legalNone").asString());
        return sha256(String.join("\n", lines));
    }

    private static JsonNode sharedTable() throws Exception {
        return JsonMapper.builder().build().readTree(Files.readString(SHARED_FIXTURE, StandardCharsets.UTF_8));
    }

    /** 재인증을 요구받은 삭제 진행 세션의 세션 응답도 삭제 진행을 말한다 — 화면이 회복 패널 대신 진행 화면을 그린다. */
    @Test
    void 재인증_표시된_삭제_진행_세션도_삭제_진행을_말한다() throws Exception {
        Signed signed = signedIn("delete-marked");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        assertThat(confirm(signed, VERSION, PHRASE, key("delete-marked")).statusCode()).isEqualTo(200);
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(signed.cookie()))).isTrue();

        assertThat(topLevelFieldsOf(sessionState(signed.cookie()).body())).containsEntry("authenticated", false)
                .containsEntry("reauthRequired", true).containsEntry("deletionInProgress", true)
                .containsKey("csrfToken");
        // 요청이 없는 표시된 세션의 본문은 그대로다(필드가 없다).
        Signed other = signedIn("delete-marked-none");
        assertThat(sessionGuard().requireReauthentication(sessionIdOf(other.cookie()))).isTrue();
        assertThat(topLevelFieldsOf(sessionState(other.cookie()).body())).containsEntry("reauthRequired", true)
                .doesNotContainKey("deletionInProgress");
    }

    /** 연결·수단 해제·재인증·Bybit 해제 준비가 성립하면 삭제 무장과 그 허가를 버린다. 거절되는 Bybit 연결 준비는 버리지 않는다. */
    @Test
    void 다른_흐름은_게이트를_지난_뒤에만_삭제_무장을_버린다() throws Exception {
        for (String other : List.of("link", "unlink", "reauth", "revocation")) {
            Signed signed = signedInWithTwoMethods("delete-dropped-by-" + other);
            assertThat(prepare(signed).statusCode()).isEqualTo(200);
            String grant = grantReferenceOf(signed.user());
            int status = switch (other) {
                case "link" -> startLink(AuthSecurityConfig.KAKAO_REGISTRATION_ID, signed.cookie(), signed.csrf())
                        .statusCode();
                case "unlink" -> prepareUnlink(AuthSecurityConfig.NAVER_REGISTRATION_ID, signed.cookie(),
                        signed.csrf()).statusCode();
                case "revocation" -> revocationPrepare(signed).statusCode();
                default -> startReauthentication(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, signed.cookie(),
                        signed.csrf()).statusCode();
            };
            assertThat(status).as(other).isIn(200, 204);
            assertThat(sessionAttribute(sessionIdOf(signed.cookie()), AccountDeletionFlow.FLOW_ATTRIBUTE)).as(other)
                    .isNull();
            assertThat(actionGrantExists(grant)).as(other).isFalse();
        }
        // 성립하는 Bybit 연결 준비(활성 연결이 없는 사용자)도 삭제 무장과 그 허가를 버린다.
        Signed connecting = signedInWithoutConnection("delete-dropped-by-connect");
        assertThat(prepare(connecting).statusCode()).isEqualTo(200);
        String connectingGrant = grantReferenceOf(connecting.user());
        var connectPrepared = prepareBybitConnection(connecting.cookie(), connecting.csrf());
        assertThat(connectPrepared.statusCode()).as(connectPrepared.body()).isEqualTo(200);
        assertThat(sessionAttribute(sessionIdOf(connecting.cookie()), AccountDeletionFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(actionGrantExists(connectingGrant)).isFalse();

        Signed kept = signedInWithTwoMethods("delete-kept-by-connect");
        assertThat(prepare(kept).statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(kept.user());
        assertThat(prepareBybitConnection(kept.cookie(), kept.csrf()).statusCode()).as("an active connection")
                .isEqualTo(403);
        assertThat(sessionAttribute(sessionIdOf(kept.cookie()), AccountDeletionFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(AccountDeletionFlow.Armed.class);
        assertThat(actionGrantExists(grant)).isTrue();
    }

    /** 성립한 삭제 준비는 다른 흐름의 무장을 버리고, 거절되는 준비(재인증 없음·삭제 진행 중)는 아무것도 버리지 않는다. */
    @Test
    void 삭제_준비는_게이트를_지난_뒤에만_다른_흐름을_버린다() throws Exception {
        Signed stale = signedInWithTwoMethods("delete-refused-stale");
        assertThat(prepareUnlink(AuthSecurityConfig.NAVER_REGISTRATION_ID, stale.cookie(), stale.csrf()).statusCode())
                .isEqualTo(204);
        String staleSession = sessionIdOf(stale.cookie());
        String unlinkGrant = onlyGrantOf(stale.user());
        ageReauthentication(staleSession, java.time.Duration.ofMinutes(6));
        assertRefused(prepare(stale), 403, "REAUTHENTICATION_REQUIRED");
        assertThat(sessionAttribute(staleSession, UnlinkFlow.FLOW_ATTRIBUTE)).isInstanceOf(UnlinkFlow.Armed.class);
        assertThat(actionGrantExists(unlinkGrant)).isTrue();

        Signed deleting = signedInWithTwoMethods("delete-refused-in-progress");
        assertThat(prepareUnlink(AuthSecurityConfig.NAVER_REGISTRATION_ID, deleting.cookie(), deleting.csrf())
                .statusCode()).isEqualTo(204);
        String deletingGrant = onlyGrantOf(deleting.user());
        assertThat(accountDeletion().requestDeletion(deleting.user(), VERSION, AccountDeletionPolicy.noticeHash(),
                sha256("delete-refused-in-progress"))).isInstanceOf(DeletionRequestOutcome.Created.class);
        assertRefused(prepare(deleting), 409, "ACCOUNT_DELETION_IN_PROGRESS");
        assertThat(sessionAttribute(sessionIdOf(deleting.cookie()), UnlinkFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(UnlinkFlow.Armed.class);
        assertThat(actionGrantExists(deletingGrant)).isTrue();

        Signed fresh = signedInWithTwoMethods("delete-drops-unlink");
        assertThat(prepareUnlink(AuthSecurityConfig.NAVER_REGISTRATION_ID, fresh.cookie(), fresh.csrf()).statusCode())
                .isEqualTo(204);
        String freshUnlink = onlyGrantOf(fresh.user());
        assertThat(prepare(fresh).statusCode()).isEqualTo(200);
        assertThat(sessionAttribute(sessionIdOf(fresh.cookie()), UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(actionGrantExists(freshUnlink)).isFalse();

        Signed revoking = signedInWithTwoMethods("delete-drops-revocation");
        assertThat(revocationPrepare(revoking).statusCode()).isEqualTo(200);
        String revocationGrant = onlyGrantOf(revoking.user());
        assertThat(prepare(revoking).statusCode()).isEqualTo(200);
        assertThat(sessionAttribute(sessionIdOf(revoking.cookie()), BybitRevocationFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(actionGrantExists(revocationGrant)).isFalse();
    }

    /** 준비한 세션이 아닌 다른 세션의 확정은 무장이 없어 단일 거부이고 허가는 소비되지 않는다. */
    @Test
    void 다른_세션의_확정은_확인되지_않은_요청이다() throws Exception {
        Signed signed = signedIn("delete-other-session");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());
        String otherCookie = sessionCookieOf(signIn(signed.subject())).orElseThrow();
        String otherCsrf = csrfTokenOf(otherCookie);
        Signed other = new Signed(signed.user(), signed.subject(), otherCookie, otherCsrf == null ? "" : otherCsrf);

        assertRefused(confirm(other, VERSION, PHRASE, key("delete-other-session")), 403,
                "ACCOUNT_DELETION_NOT_CONFIRMED");
        assertThat(actionGrantConsumed(grant)).isFalse();
        assertThat(lifecycleOf(signed.user())).isEqualTo("ACTIVE");
        assertThat(requestCount(signed.user())).isZero();
    }

    /** NFD로 입력한 문구도 NFC로 같은 문구다. */
    @Test
    void NFD로_입력한_문구도_받는다() throws Exception {
        Signed signed = signedIn("delete-nfd");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        String nfd = java.text.Normalizer.normalize(PHRASE, java.text.Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo(PHRASE);
        var confirmed = confirm(signed, VERSION, nfd, key("delete-nfd"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(lifecycleOf(signed.user())).isEqualTo("DELETION_IN_PROGRESS");
    }

    /**
     * 삭제가 요청된 계정은 같은 OIDC identity로 다시 로그인해도 세션이 서지 않고 새 계정도 생기지 않는다(1.4 계약) — 콜백은 302
     * {@code /?auth=failed}이고 세션 쿠키가 없으며 남은 세션은 요청 세션 하나뿐이다.
     */
    @Test
    void 삭제_중_계정의_OIDC_로그인은_거부된다() throws Exception {
        Signed signed = signedIn("delete-sign-in");
        assertThat(prepare(signed).statusCode()).isEqualTo(200);
        assertThat(confirm(signed, VERSION, PHRASE, key("delete-sign-in")).statusCode()).isEqualTo(200);
        long accounts = accountCount();

        var again = signIn(signed.subject());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(302);
        // 계약: 콜백은 단일 실패 표지로 인증 화면에 돌려보내고(계정 상태를 구분하지 않는다) 세션 쿠키를 내지 않는다.
        assertThat(again.headers().firstValue("Location")).hasValue(
                "/?" + AuthFlowHandlers.FAILURE_MARKER_PARAMETER + "=" + AuthFlowHandlers.FAILURE_MARKER_FAILED);
        assertThat(sessionCookieOf(again)).isEmpty();
        assertThat(sessionCountOf(signed.user())).isEqualTo(1);
        assertThat(accountCount()).isEqualTo(accounts);
        assertThat(userIdOf(OidcStubConfiguration.issuer(), signed.subject())).isEmpty();
    }

    /** 커밋 뒤 정리에서 removeAttribute가 실패하는 세션(그 사이 무효화된 세션). 준비 동안에는 정상이다. */
    private static final class FailingSession extends MockHttpSession {

        volatile boolean failing;

        FailingSession(String id) {
            super(null, id);
        }

        @Override
        public void removeAttribute(String name) {
            if (this.failing) throw new IllegalStateException("무효화된 세션");
            super.removeAttribute(name);
        }
    }

    // ------------------------------------------------------------------------------------------ 도구

    /** Google·네이버 두 수단, Bybit 연결, 신선한 재인증 — 설정 화면의 모든 흐름이 시작될 수 있다. */
    private Signed signedInWithTwoMethods(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(accountLifecycle().linkSocialIdentity(user, AuthSecurityConfig.NAVER_REGISTRATION_ID,
                OidcStubConfiguration.issuer(AuthSecurityConfig.NAVER_REGISTRATION_ID),
                prefix + "-naver-" + UUID.randomUUID())).isEqualTo(com.bokrilog.identity.AccessDecision.ALLOWED);
        store(user, DevBybitStub.Scenario.UTA2);
        var reauthenticated = reauthenticate(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, cookie, csrfTokenOf(cookie),
                plan -> plan.subject(subject));
        String rotated = sessionCookieOf(reauthenticated).orElseThrow();
        String csrf = csrfTokenOf(rotated);
        return new Signed(user, subject, rotated, csrf == null ? "" : csrf);
    }

    private HttpResponse<String> revocationPrepare(Signed signed) throws Exception {
        UUID connection = jdbc().sql("SELECT connection_id FROM source_connection WHERE user_id=:u AND status='ACTIVE'")
                .param("u", signed.user()).query(UUID.class).single();
        return sendJson("POST", AuthSecurityConfig.BYBIT_CONNECTION_PATH + "/revocation",
                "{\"connectionId\":\"" + connection + "\"}", signed.cookie(), headers(signed.csrf(), null));
    }

    /** 그 사용자의 미소비 허가 하나(다른 흐름이 발급한 것). */
    private String onlyGrantOf(UUID user) {
        return jdbc().sql("SELECT grant_id FROM action_grant WHERE user_id=:u AND consumed_at IS NULL")
                .param("u", user).query(String.class).single();
    }

    /** Bybit 연결 없이 신선한 재인증만 있는 사용자 — 연결 준비가 성립할 수 있다. */
    private Signed signedInWithoutConnection(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        var reauthenticated = reauthenticate(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, cookie, csrfTokenOf(cookie),
                plan -> plan.subject(subject));
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String rotated = sessionCookieOf(reauthenticated).orElseThrow();
        String csrf = csrfTokenOf(rotated);
        return new Signed(user, subject, rotated, csrf == null ? "" : csrf);
    }

    /** 이 시험이 남긴 커밋 뒤 정리 실패 줄들(필드 이름 → 값). 시험 시작 표지 뒤의 줄만 본다. */
    private static List<Map<String, Object>> settleFailures(CapturedOutput output) {
        List<Map<String, Object>> records = LogLines.records(output.getAll());
        int start = -1;
        for (int index = 0; index < records.size(); index++) {
            if (TEST_START.equals(String.valueOf(records.get(index).get("event")))) start = index;
        }
        return records.subList(start + 1, records.size()).stream()
                .filter(record -> "account deletion settle failed".equals(String.valueOf(record.get("event"))))
                .toList();
    }

    private Signed signedIn(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        var reauthenticated = reauthenticate(AuthSecurityConfig.GOOGLE_REGISTRATION_ID, cookie, csrfTokenOf(cookie),
                plan -> plan.subject(subject));
        String rotated = sessionCookieOf(reauthenticated).orElseThrow();
        String csrf = csrfTokenOf(rotated);
        return new Signed(user, subject, rotated, csrf == null ? "" : csrf);
    }

    private HttpResponse<String> prepare(Signed signed) throws Exception {
        return prepare(signed, VERSION);
    }

    /** 준비. 본문은 화면이 본 정책 버전 하나다. */
    private HttpResponse<String> prepare(Signed signed, String version) throws Exception {
        return sendJson("POST", DELETION_PATH, "{\"policyVersion\":\"" + version + "\"}", signed.cookie(),
                headers(signed.csrf(), null));
    }

    private HttpResponse<String> confirm(Signed signed, String version, String phrase, String key) throws Exception {
        return sendJson("POST", CONFIRM_PATH, body(version, phrase), signed.cookie(), headers(signed.csrf(), key));
    }

    private static String body(String version, String phrase) {
        return "{\"policyVersion\":\"" + version + "\",\"confirmation\":\"" + phrase + "\"}";
    }

    private static String[] headers(@Nullable String csrf, @Nullable String key) {
        List<String> headers = new ArrayList<>(List.of("Origin", OidcStubConfiguration.PUBLIC_BASE_URL));
        if (csrf != null) {
            headers.add(AuthSecurityConfig.CSRF_HEADER_NAME);
            headers.add(csrf);
        }
        if (key != null) {
            headers.add("Idempotency-Key");
            headers.add(key);
        }
        return headers.toArray(String[]::new);
    }

    private static void assertRefused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(topLevelFieldsOf(response.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", code);
    }

    private String grantReferenceOf(UUID user) {
        return jdbc().sql("SELECT grant_id FROM action_grant WHERE user_id=:u AND command='delete-account' "
                + "AND consumed_at IS NULL").param("u", user).query(String.class).single();
    }

    private String lifecycleOf(UUID user) {
        return jdbc().sql("SELECT state FROM identity_account_lifecycle WHERE user_id=:u").param("u", user)
                .query(String.class).single();
    }

    private int requestCount(UUID user) {
        return countOf("identity_deletion_request", user);
    }

    /** 호출마다 실행 시점에 만드는 고유 키다 — 소스에 키 모양 리터럴을 두지 않는다. */
    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static JsonNode json(String body) {
        return JsonMapper.builder().build().readTree(body);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            values.add(value.asString());
        }
        return values;
    }

    private interface Call {
        HttpResponse<String> send() throws Exception;
    }

    private static CompletableFuture<HttpResponse<String>> async(Call call) {
        CompletableFuture<HttpResponse<String>> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                future.complete(call.send());
            } catch (Exception failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }
}
