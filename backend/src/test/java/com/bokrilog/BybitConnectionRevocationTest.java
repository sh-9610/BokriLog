package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.sync.SyncJobApi;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Story 2.16: revoking the Bybit connection while every plan record stays read-only, end to end on the real stack
 * (Docker PostgreSQL, the workers, the local OIDC and Bybit stubs). Each test signs in through the stub, publishes a
 * first official version (Story 2.7) and revokes over HTTP — recent reauthentication, the
 * {@code bybit-connection-revoke} grant, the confirmation — then reads what is left: the credential gone, the
 * connection {@code REVOKED} with its time, the membership closed at that time, every attempt ended, no provider call,
 * the official result, evidence and history still 200 with {@code CONNECTION_REVOKED} where a sync would be, the one
 * refusal for another user's, an unknown and a revoked connection, idempotent replays and a conflict, the grant's
 * misuse, the atomic rollback, a late worker that writes nothing, the account erasure and the logs.
 * 해제 뒤 새 재인증 위에서는 재연결 준비가 다시 성립한다(Story 2.17 — 재연결 자체는 {@code BybitReconnectionTest}).
 */
// The plan lock is held by a resource the body never names: holding it is the point.
@SuppressWarnings("try")
class BybitConnectionRevocationTest extends PlanSyncTestBase {
    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;
    private static final String NAVER = AuthSecurityConfig.NAVER_REGISTRATION_ID;
    private static final String KAKAO = AuthSecurityConfig.KAKAO_REGISTRATION_ID;
    private static final String STATE_PATH = AuthSecurityConfig.BYBIT_CONNECTION_PATH;
    private static final String REVOCATION_PATH = AuthSecurityConfig.BYBIT_CONNECTION_PATH + "/revocation";
    private static final String CONFIRM_PATH = REVOCATION_PATH + "/confirm";
    private static final String CANCEL_PATH = REVOCATION_PATH + "/cancel";
    private static final String SYNC_PATH = AuthSecurityConfig.DASHBOARD_PATH + "/sync";
    private static final String PLAN_END_PATH = AuthSecurityConfig.DASHBOARD_PATH + "/plan-end";

    // ------------------------------------------------------------------------------------------ the revocation

    /**
     * AC-1~AC-7, AC-9: the state names the connection; the preparation answers the canonical phrase and changes nothing
     * but the grant and the arming; the confirmation deletes the credential, moves the connection to {@code REVOKED} with
     * the database time, closes the membership at that same time and rotates the session — without any provider call,
     * leaving the plan, its versions, pointers, snapshots and cash flows as they were. The dashboard keeps the official
     * result with {@code CONNECTION_REVOKED} for the sync, the verification and the freshness; the evidence and the
     * history stay 200; no sync is admitted; the state says revoked; each request writes one line without identifiers.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aRevocationDeletesTheCredentialAndKeepsTheRecordsReadOnly(CapturedOutput output) throws Exception {
        Signed signed = signedInWithPlan("revoke-main");
        var v1 = ready(signed.user());
        UUID connection = connectionOf(signed.user());
        var before = recordsOf(signed.user());
        BybitStubConfiguration.resetRequestCount();

        var state = topLevelFieldsOf(get(STATE_PATH, signed.cookie()).body());
        assertThat(state).containsEntry("connected", true).containsEntry("revoked", false)
                .containsEntry("connectionId", connection.toString()).doesNotContainKey("revokedAt");

        var prepared = prepare(signed, connection);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(prepared.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String confirmation = "bybit-connection-revoke|" + connection;
        assertThat(topLevelFieldsOf(prepared.body())).containsOnlyKeys("connectionId", "confirmation")
                .containsEntry("connectionId", connection.toString()).containsEntry("confirmation", confirmation);
        String grant = grantReferenceOf(signed.user());
        assertThat(prepared.body()).doesNotContain(grant);
        assertThat(jdbc().sql("SELECT command||'|'||target FROM action_grant WHERE grant_id=:g").param("g", grant)
                .query(String.class).single()).isEqualTo("bybit-connection-revoke|" + connection);
        assertThat(connectionStatus(connection)).isEqualTo("ACTIVE");
        assertThat(credentialCount(connection)).isEqualTo(1);

        Instant earliest = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        String key = key("revoke-main");
        var confirmed = confirm(signed, connection, confirmation, key);
        Instant latest = databaseNow();
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var result = topLevelFieldsOf(confirmed.body());
        assertThat(result).containsOnlyKeys("outcome", "connectionId", "revokedAt").containsEntry("outcome", "REVOKED")
                .containsEntry("connectionId", connection.toString());
        Instant revokedAt = Instant.parse((String) result.get("revokedAt"));
        assertThat(revokedAt).isBetween(earliest, latest).isEqualTo(revokedAt.truncatedTo(ChronoUnit.MILLIS));
        String rotated = sessionCookieOf(confirmed).orElseThrow();
        assertThat(rotated).isNotEqualTo(signed.cookie());
        assertThat(BybitStubConfiguration.requestCount()).as("no provider call to revoke").isZero();

        // One transaction: the credential, the state with its facts, the membership and the grant.
        assertThat(credentialCount(connection)).isZero();
        assertThat(jdbc().sql("SELECT status||'|'||revoked_at||'|'||revocation_idempotency_key||'|'||"
                + "revocation_request_hash FROM source_connection WHERE connection_id=:c").param("c", connection)
                .query(String.class).single()).isEqualTo(String.join("|", "REVOKED", pg(revokedAt), key,
                sha256("bybit-connection-revoke|" + connection)));
        assertThat(jdbc().sql("SELECT ended_at FROM portfolio_source_membership WHERE connection_id=:c")
                .param("c", connection).query(OffsetDateTime.class).single().toInstant()).isEqualTo(revokedAt);
        assertThat(actionGrantConsumed(grant)).isTrue();
        assertThat(actionGrantResult(grant)).contains("REVOKED|" + connection + "|" + revokedAt);
        // Nothing else moved: the plan, its versions and pointers, the snapshots, the flows, the provider account.
        assertThat(recordsOf(signed.user())).isEqualTo(before);
        assertThat(providerAccountIdOf(signed.user())).isPresent();

        // The record stays read-only, the sync says why it stopped.
        var dashboard = get(AuthSecurityConfig.DASHBOARD_PATH, rotated);
        assertThat(dashboard.statusCode()).as(dashboard.body()).isEqualTo(200);
        var fields = topLevelFieldsOf(dashboard.body());
        assertThat(fields).containsEntry("state", "READY");
        assertThat(nested(fields, "sync")).containsEntry("availability", "UNAVAILABLE")
                .containsEntry("reason", "CONNECTION_REVOKED");
        assertThat(nested(fields, "verification")).containsEntry("availability", "UNAVAILABLE")
                .containsEntry("reason", "CONNECTION_REVOKED");
        var resultView = nested(fields, "result");
        assertThat(resultView).containsEntry("calculationId", v1.calculationId().toString())
                .containsEntry("pointerVersion", 1).containsEntry("measuredAt", v1.measuredAt().toString());
        assertThat(nested(nested(resultView, "trust"), "freshness")).containsEntry("state", "STALE")
                .containsEntry("cause", "CONNECTION_REVOKED").containsEntry("nextAction", "NONE");
        String reference = "?planId=" + v1.planId() + "&pointerVersion=1&calculationId=" + v1.calculationId();
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence" + reference, rotated).statusCode())
                .isEqualTo(200);
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/adjustments" + reference, rotated).statusCode())
                .isEqualTo(200);
        var syncRequest = sendJson("POST", SYNC_PATH, "{\"trigger\":\"MANUAL\"}", rotated,
                protectedHeaders(csrfTokenOf(rotated), key("revoke-main-sync")));
        assertThat(syncRequest.statusCode()).as(syncRequest.body()).isEqualTo(409);
        assertThat(countOf("sync_plan_sync_job", signed.user())).isZero();
        assertThat(BybitStubConfiguration.requestCount()).as("no provider call at all").isZero();

        var revokedState = get(STATE_PATH, rotated);
        assertThat(revokedState.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(revokedState.body())).containsEntry("connected", false)
                .containsEntry("revoked", true).containsEntry("connectionId", connection.toString())
                .containsEntry("revokedAt", revokedAt.toString());

        assertThat(lines(output, "bybit revocation request: ")).anySatisfy(line -> assertThat(line)
                .contains("step=prepare result=PREPARED status=200 latencyMs="))
                .anySatisfy(line -> assertThat(line).contains("step=confirm result=REVOKED status=200 latencyMs="));
        assertThat(lines(output, "bybit connection revoked: ")).singleElement().satisfies(line -> assertThat(line)
                .contains("endedAttempts=0"));
        for (String marker : List.of("bybit revocation request: ", "bybit connection revoked: ")) {
            for (String line : lines(output, marker)) {
                for (String forbidden : List.of(signed.user().toString(), connection.toString(), key, grant,
                        "bybit-connection-revoke|"))
                    assertThat(line).as(forbidden).doesNotContain(forbidden);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ one refusal

    /**
     * AC-1: another user's connection, an unknown id and an already revoked connection are one answer — the same 404
     * {@code CONNECTION_NOT_FOUND}, nothing issued — so whether a connection exists is never said.
     */
    @Test
    void anotherUsersAnUnknownAndARevokedConnectionAreTheSameNotFound() throws Exception {
        Signed owner = signedInWithPlan("revoke-owner");
        UUID theirs = connectionOf(owner.user());
        Signed signed = signedInWithPlan("revoke-other");
        UUID mine = connectionOf(signed.user());

        var foreign = prepare(signed, theirs);
        var unknown = prepare(signed, UUID.randomUUID());
        for (var response : List.of(foreign, unknown)) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(withoutCorrelation(response.body())).isEqualTo(Map.of("code", "CONNECTION_NOT_FOUND",
                    "message", "해제할 활성 연결을 찾지 못했습니다. 설정 화면을 다시 불러와 주세요."));
        }
        assertThat(actionGrantCountOf(signed.user())).isZero();
        assertThat(connectionStatus(theirs)).isEqualTo("ACTIVE");

        String rotated = sessionCookieOf(revoke(signed, mine, key("revoke-other"))).orElseThrow();
        Signed again = new Signed(signed.user(), signed.subject(), rotated, csrfTokenOf(rotated));
        var revoked = prepare(again, mine);
        assertThat(revoked.statusCode()).isEqualTo(404);
        assertThat(withoutCorrelation(revoked.body())).isEqualTo(withoutCorrelation(foreign.body()));
    }

    /** AC-3: without a fresh reauthentication no grant is issued; an aged one is refused the same way. */
    @Test
    void withoutAFreshReauthenticationNoGrantIsIssued() throws Exception {
        String subject = "revoke-no-reauth-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        UUID connection = connectionOf(user);
        Signed bare = new Signed(user, subject, cookie, csrfTokenOf(cookie));

        var refused = prepare(bare, connection);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", "REAUTHENTICATION_REQUIRED");
        assertThat(actionGrantCountOf(user)).isZero();

        Signed reauthenticated = reauthenticate(bare);
        ageReauthentication(sessionIdOf(reauthenticated.cookie()), Duration.ofMinutes(6));
        var stale = prepare(reauthenticated, connection);
        assertThat(stale.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(stale.body())).containsEntry("code", "REAUTHENTICATION_REQUIRED");
        assertThat(actionGrantCountOf(user)).isZero();
        assertThat(connectionStatus(connection)).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------------------------------ idempotency

    /**
     * AC-1, AC-8: the same key and request after the revocation replays the stored result — the arming is gone, the
     * session rotated — and the same key under another request is {@code IDEMPOTENCY_CONFLICT}; nothing changes.
     */
    @Test
    void theSameKeyReplaysTheRevocationAndAnotherRequestUnderItConflicts() throws Exception {
        Signed signed = signedInWithPlan("revoke-replay");
        UUID connection = connectionOf(signed.user());
        String key = key("revoke-replay");
        var first = revoke(signed, connection, key);
        String rotated = sessionCookieOf(first).orElseThrow();
        Signed after = new Signed(signed.user(), signed.subject(), rotated, csrfTokenOf(rotated));

        var replayed = confirm(after, connection, "bybit-connection-revoke|" + connection, key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replayed.body())).isEqualTo(topLevelFieldsOf(first.body()));
        assertThat(sessionCookieOf(replayed)).as("a replay does not rotate").isEmpty();
        // 재전송 뒤에도 재인증 시각은 없다 — 첫 실행이 지웠고 재전송은 아무것도 다시 하지 않는다.
        assertThat(sessionAttribute(sessionIdOf(rotated), ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE)).isNull();

        var conflict = confirm(after, UUID.randomUUID(), "bybit-connection-revoke|x", key);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(conflict.body())).containsEntry("code", "IDEMPOTENCY_CONFLICT");
        assertThat(jdbc().sql("SELECT count(*) FROM source_connection WHERE user_id=:u AND status='REVOKED'")
                .param("u", signed.user()).query(Integer.class).single()).isEqualTo(1);
    }

    /** AC-8: two confirmations with the same key racing behind the plan lock answer the same revocation. */
    @Test
    void concurrentConfirmationsWithTheSameKeyAnswerTheSameRevocation() throws Exception {
        Signed signed = signedInWithPlan("revoke-concurrent");
        UUID connection = connectionOf(signed.user());
        var prepared = prepare(signed, connection);
        String confirmation = confirmationIn(prepared.body());
        String key = key("revoke-concurrent");
        CompletableFuture<HttpResponse<String>> first;
        CompletableFuture<HttpResponse<String>> second;
        try (AutoCloseable held = holdPlanLock(signed.user())) {
            first = async(() -> confirm(signed, connection, confirmation, key));
            awaitPlanLockWaiters(signed.user(), 1);
            second = async(() -> confirm(signed, connection, confirmation, key));
            awaitRowLockWaiter(signed.user());
        }
        var a = first.get();
        var b = second.get();
        assertThat(List.of(a.statusCode(), b.statusCode())).containsExactly(200, 200);
        assertThat(topLevelFieldsOf(b.body())).isEqualTo(topLevelFieldsOf(a.body()));
        assertThat(connectionStatus(connection)).isEqualTo("REVOKED");
        assertThat(credentialCount(connection)).isZero();
    }

    /** AC-8: one arming confirmed by two tabs with two keys revokes once; the other key has nothing to replay. */
    @Test
    void twoTabsConfirmingOneArmingWithTwoKeysRevokeOnce() throws Exception {
        Signed signed = signedInWithPlan("revoke-tabs");
        UUID connection = connectionOf(signed.user());
        String confirmation = confirmationIn(prepare(signed, connection).body());
        CompletableFuture<HttpResponse<String>> first;
        CompletableFuture<HttpResponse<String>> second;
        try (AutoCloseable held = holdPlanLock(signed.user())) {
            first = async(() -> confirm(signed, connection, confirmation, key("revoke-tab-one")));
            awaitPlanLockWaiters(signed.user(), 1);
            second = async(() -> confirm(signed, connection, confirmation, key("revoke-tab-two")));
            awaitRowLockWaiter(signed.user());
        }
        var a = first.get();
        var b = second.get();
        assertThat(a.statusCode()).as(a.body()).isEqualTo(200);
        assertThat(b.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(b.body())).containsEntry("code", "REVOCATION_NOT_CONFIRMED");
        assertThat(connectionStatus(connection)).isEqualTo("REVOKED");
    }

    // ------------------------------------------------------------------------------------------ grant misuse

    /**
     * AC-3: no arming, a phrase that is not the prepared one, another connection in the body, a grant of another
     * command, an expired grant and a second confirmation after the first are one refusal —
     * {@code REVOCATION_NOT_CONFIRMED} — and the connection and the grant stay as they were.
     */
    @Test
    void aMisusedGrantIsOneRefusalAndChangesNothing() throws Exception {
        Signed signed = signedInWithPlan("revoke-misuse");
        UUID connection = connectionOf(signed.user());
        String confirmation = "bybit-connection-revoke|" + connection;

        assertNotConfirmed(confirm(signed, connection, confirmation, key("revoke-misuse")), connection);
        assertThat(prepare(signed, connection).statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());
        assertNotConfirmed(confirm(signed, connection, confirmation + "x", key("revoke-misuse")), connection);
        assertNotConfirmed(confirm(signed, UUID.randomUUID(), confirmation, key("revoke-misuse")), connection);
        assertThat(actionGrantConsumed(grant)).isFalse();
        jdbc().sql("UPDATE action_grant SET command='plan-end' WHERE grant_id=:g").param("g", grant).update();
        assertNotConfirmed(confirm(signed, connection, confirmation, key("revoke-misuse")), connection);
        assertThat(prepare(signed, connection).statusCode()).isEqualTo(200);
        String expired = grantReferenceOf(signed.user());
        moveActionGrantExpiry(expired, Duration.ofSeconds(-1));
        assertNotConfirmed(confirm(signed, connection, confirmation, key("revoke-misuse")), connection);
        assertThat(actionGrantConsumed(expired)).isFalse();

        assertThat(prepare(signed, connection).statusCode()).isEqualTo(200);
        var first = confirm(signed, connection, confirmation, key("revoke-misuse"));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        String rotated = sessionCookieOf(first).orElseThrow();
        Signed after = new Signed(signed.user(), signed.subject(), rotated, csrfTokenOf(rotated));
        var reused = confirm(after, connection, confirmation, key("revoke-misuse"));
        assertThat(reused.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(reused.body())).containsEntry("code", "REVOCATION_NOT_CONFIRMED");
    }

    /** The cancel drops the arming and its grant; a confirmation after it is refused. Always 204. */
    @Test
    void theCancelDropsTheArmingAndItsGrant() throws Exception {
        Signed signed = signedInWithPlan("revoke-cancel");
        UUID connection = connectionOf(signed.user());
        var prepared = prepare(signed, connection);
        String grant = grantReferenceOf(signed.user());
        var cancelled = send("POST", CANCEL_PATH, signed.cookie(), protectedHeaders(signed.csrf(), null));
        assertThat(cancelled.statusCode()).isEqualTo(204);
        assertThat(cancelled.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(actionGrantExists(grant)).isFalse();
        assertNotConfirmed(confirm(signed, connection, confirmationIn(prepared.body()), key("revoke-cancel")),
                connection);
        assertThat(send("POST", CANCEL_PATH, signed.cookie(), protectedHeaders(signed.csrf(), null)).statusCode())
                .as("nothing armed: still 204").isEqualTo(204);
    }

    /**
     * The connection path's predicate guards all three: without the CSRF token or with another origin 403 — nothing
     * issued, nothing revoked; a body that is not the shape is 400 and a missing or malformed key 422.
     */
    @Test
    void theThreeWritesAreGuardedAndReadTheirBodiesStrictly() throws Exception {
        Signed signed = signedInWithPlan("revoke-guard");
        UUID connection = connectionOf(signed.user());
        String body = "{\"connectionId\":\"" + connection + "\"}";

        assertThat(sendJson("POST", REVOCATION_PATH, body, null, "Origin", OidcStubConfiguration.PUBLIC_BASE_URL)
                .statusCode()).as("no session").isIn(401, 403);
        assertThat(sendJson("POST", REVOCATION_PATH, body, signed.cookie(), "Origin",
                OidcStubConfiguration.PUBLIC_BASE_URL).statusCode()).isEqualTo(403);
        assertThat(sendJson("POST", REVOCATION_PATH, body, signed.cookie(), "Origin", "https://elsewhere.example",
                AuthSecurityConfig.CSRF_HEADER_NAME, signed.csrf()).statusCode()).isEqualTo(403);
        assertThat(send("POST", CANCEL_PATH, signed.cookie(), "Origin", OidcStubConfiguration.PUBLIC_BASE_URL)
                .statusCode()).isEqualTo(403);
        assertThat(actionGrantCountOf(signed.user())).isZero();

        for (String malformed : List.of("", "[]", "{}", "{\"connectionId\":\"1-1-1-1-1\"}",
                "{\"connectionId\":7}")) {
            var response = sendJson("POST", REVOCATION_PATH, malformed, signed.cookie(),
                    protectedHeaders(signed.csrf(), null));
            assertThat(response.statusCode()).as(malformed).isEqualTo(400);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
        assertThat(sendJson("POST", CONFIRM_PATH, body, signed.cookie(), protectedHeaders(signed.csrf(),
                key("revoke-guard"))).statusCode()).as("no confirmation").isEqualTo(400);
        String confirmBody = confirmBody(connection, "bybit-connection-revoke|" + connection);
        assertThat(sendJson("POST", CONFIRM_PATH, confirmBody, signed.cookie(), protectedHeaders(signed.csrf(), null))
                .statusCode()).isEqualTo(422);
        var shortKey = sendJson("POST", CONFIRM_PATH, confirmBody, signed.cookie(),
                protectedHeaders(signed.csrf(), "short"));
        assertThat(shortKey.statusCode()).isEqualTo(422);
        // 모든 엄격 키 경로가 같은 code와 같은 메시지 한 문장으로 답한다(`ApiErrorHandler`).
        assertThat(topLevelFieldsOf(shortKey.body())).containsEntry("code", "IDEMPOTENCY_KEY_REQUIRED")
                .containsEntry("message", ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED);
        assertThat(connectionStatus(connection)).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------------------------------ rollback

    /**
     * AC-4: a failure at any write of the transaction — the credential's deletion, the state, the membership, the
     * attempts, the grant's stored result — rolls everything back: the connection stays {@code ACTIVE} with its
     * credential, the membership open, the grant unconsumed, a 500 in the common shape. Once the cause is gone the same
     * key revokes.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CREDENTIAL", "STATUS", "MEMBERSHIP", "ATTEMPTS", "RESULT"})
    void aFailureAtAnyWriteRollsTheWholeRevocationBack(String step) throws Exception {
        Signed signed = signedInWithPlan("revoke-rollback-" + step.toLowerCase(java.util.Locale.ROOT));
        UUID connection = connectionOf(signed.user());
        var prepared = prepare(signed, connection);
        String grant = grantReferenceOf(signed.user());
        String key = key("revoke-rollback");
        String table = switch (step) {
            case "CREDENTIAL" -> "source_credential";
            case "STATUS" -> "source_connection";
            case "MEMBERSHIP" -> "portfolio_source_membership";
            case "ATTEMPTS" -> "sync_job";
            default -> "action_grant";
        };
        String when = step.equals("CREDENTIAL") ? "BEFORE DELETE" : "BEFORE UPDATE";
        String level = step.equals("ATTEMPTS") ? "FOR EACH STATEMENT" : "FOR EACH ROW";
        String condition = switch (step) {
            case "CREDENTIAL" -> "OLD.connection_id='" + connection + "'::uuid";
            case "STATUS" -> "NEW.connection_id='" + connection + "'::uuid AND NEW.status='REVOKED'";
            case "MEMBERSHIP" -> "NEW.connection_id='" + connection + "'::uuid";
            // Statement level: the revocation's own uncommitted state is what this transaction sees.
            case "ATTEMPTS" -> "EXISTS(SELECT 1 FROM source_connection WHERE connection_id='" + connection
                    + "'::uuid AND status='REVOKED')";
            default -> "NEW.result IS NOT NULL AND NEW.user_id='" + signed.user() + "'::uuid";
        };
        jdbc().sql("CREATE OR REPLACE FUNCTION fail_revocation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF " + condition + " THEN RAISE EXCEPTION 'injected'; END IF; RETURN COALESCE(NEW, OLD); END $$")
                .update();
        jdbc().sql("CREATE OR REPLACE TRIGGER fail_revocation " + when + " ON " + table + " " + level
                + " EXECUTE FUNCTION fail_revocation()").update();
        HttpResponse<String> failed;
        try {
            failed = confirm(signed, connection, confirmationIn(prepared.body()), key);
        } finally {
            jdbc().sql("DROP TRIGGER IF EXISTS fail_revocation ON " + table).update();
            jdbc().sql("DROP FUNCTION IF EXISTS fail_revocation()").update();
        }
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(topLevelFieldsOf(failed.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", "INTERNAL_ERROR");
        assertThat(failed.body()).doesNotContain("injected");
        assertThat(connectionStatus(connection)).isEqualTo("ACTIVE");
        assertThat(credentialCount(connection)).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_source_membership WHERE connection_id=:c "
                + "AND ended_at IS NULL").param("c", connection).query(Integer.class).single()).isEqualTo(1);
        assertThat(actionGrantConsumed(grant)).isFalse();

        var retried = confirm(signed, connection, confirmationIn(prepared.body()), key);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        assertThat(connectionStatus(connection)).isEqualTo("REVOKED");
    }

    // ------------------------------------------------------------------------------------------ late worker

    /**
     * AC-5: a sync already collecting when the revocation commits is ended by it ({@code BLOCKED}/{@code NOT_PERMITTED})
     * with its stage rows, and the late worker writes no version, pointer, snapshot or watermark; queued attempts of the
     * other tables end the same way.
     */
    @Test
    void aLateWorkerPublishesNothingAfterTheRevocation() throws Exception {
        Signed signed = signedInWithPlan("revoke-late-worker");
        UUID connection = connectionOf(signed.user());
        var v1 = ready(signed.user());
        int snapshots = countOf("portfolio_snapshot", signed.user());
        slowPageStores(signed.user());
        UUID late;
        try {
            late = manualSync(signed.user(), key("revoke-late-sync"));
            awaitStage(late, "COLLECTION");
            var revoked = revoke(signed, connection, key("revoke-late-worker"));
            assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        } finally {
            dropSlowPageStores();
        }
        assertThat(awaitSync(late)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", late)
                .query(String.class).single()).isEqualTo("NOT_PERMITTED");
        assertNoChildInProgress(late);
        assertThat(countOf("sync_plan_calculation", signed.user())).isEqualTo(1);
        assertThat(countOf("sync_official_plan_result_history", signed.user())).isEqualTo(1);
        assertThat(countOf("portfolio_snapshot", signed.user())).isEqualTo(snapshots);
        assertThat(jdbc().sql("SELECT pointer_version FROM sync_official_plan_result WHERE user_id=:u")
                .param("u", signed.user()).query(Long.class).single()).isEqualTo(1);
        assertThat(ready(signed.user()).calculationId()).isEqualTo(v1.calculationId());
        for (String table : List.of("sync_job", "sync_initial_collection_job", "sync_cashflow_classification_job",
                "sync_cashflow_conversion_job", "sync_plan_publication_job", "sync_plan_sync_job")) {
            assertThat(jdbc().sql("SELECT count(*) FROM " + table + " WHERE user_id=:u AND status IN "
                    + "('QUEUED','RUNNING')").param("u", signed.user()).query(Integer.class).single()).as(table)
                    .isZero();
        }
    }

    // ------------------------------------------------------------------------------------------ preservation

    /**
     * AC-6: a plan ended before the revocation keeps its record: the {@code ENDED} dashboard, the evidence and the
     * history stay 200 after it, and nothing of the plan is deleted.
     */
    @Test
    void anEndedPlansRecordStaysAfterTheRevocation() throws Exception {
        Signed signed = signedInWithPlan("revoke-ended");
        var v1 = ready(signed.user());
        UUID connection = connectionOf(signed.user());
        String body = "{\"reason\":\"USER_REQUEST\",\"planId\":\"" + v1.planId() + "\",\"pointerVersion\":1,"
                + "\"calculationId\":\"" + v1.calculationId() + "\"";
        var endPrepared = sendJson("POST", PLAN_END_PATH, body + "}", signed.cookie(),
                protectedHeaders(signed.csrf(), null));
        assertThat(endPrepared.statusCode()).as(endPrepared.body()).isEqualTo(200);
        var ended = sendJson("POST", PLAN_END_PATH + "/confirm", body + ",\"confirmation\":\""
                + confirmationIn(endPrepared.body()) + "\"}", signed.cookie(),
                protectedHeaders(signed.csrf(), key("revoke-ended-plan")));
        assertThat(ended.statusCode()).as(ended.body()).isEqualTo(200);
        String cookie = sessionCookieOf(ended).orElseThrow();
        // 종료가 재인증 시각을 지웠다(본인 확인 1회는 command 1건) — 같은 세션의 해제 준비는 거절되고 허가도 늘지 않는다.
        Signed stale = new Signed(signed.user(), signed.subject(), cookie, csrfTokenOf(cookie));
        long grants = actionGrantCountOf(signed.user());
        var chained = prepare(stale, connection);
        assertThat(chained.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(chained.body())).containsEntry("code", "REAUTHENTICATION_REQUIRED");
        assertThat(actionGrantCountOf(signed.user())).isEqualTo(grants);
        // 해제는 새 재인증 위에서 준비한다.
        Signed afterEnd = reauthenticate(stale);
        var before = recordsOf(signed.user());

        String rotated = sessionCookieOf(revoke(afterEnd, connection, key("revoke-ended"))).orElseThrow();
        assertThat(recordsOf(signed.user())).isEqualTo(before);
        var dashboard = get(AuthSecurityConfig.DASHBOARD_PATH, rotated);
        assertThat(dashboard.statusCode()).as(dashboard.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(dashboard.body())).containsEntry("state", "ENDED");
        String reference = "?planId=" + v1.planId() + "&pointerVersion=1&calculationId=" + v1.calculationId();
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence" + reference, rotated).statusCode())
                .isEqualTo(200);
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/adjustments" + reference, rotated).statusCode())
                .isEqualTo(200);
        var baseline = get("/api/plans/next/baseline", rotated);
        assertThat(baseline.statusCode()).as(baseline.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(baseline.body())).containsEntry("eligible", false)
                .containsEntry("reason", "CONNECTION_REVOKED");
    }

    // ------------------------------------------------------------------------------------------ reconnection

    /**
     * 의도 변경(Story 2.17, {@code RECONNECT-1.0}): 해제한 사용자의 새 연결 준비는 더 이상 단일 거부가 아니다. 해제가
     * 재인증 시각을 지웠으므로 같은 세션의 준비는 재인증 부재로 거절되고(허가 0건), 새 재인증 위에서는 Story 2.1의 같은
     * 준비가 성립한다. 준비는 아무것도 저장하지 않는다 — 연결은 여전히 해제된 하나다. 재연결 자체는
     * {@code BybitReconnectionTest}가 본다.
     */
    @Test
    void aRevokedUserCanPrepareAReconnectionOnAFreshReauthentication() throws Exception {
        Signed signed = signedInWithPlan("revoke-reconnect");
        UUID connection = connectionOf(signed.user());
        String rotated = sessionCookieOf(revoke(signed, connection, key("revoke-reconnect"))).orElseThrow();
        String csrf = csrfTokenOf(rotated);
        long grants = actionGrantCountOf(signed.user());
        var stale = prepareBybitConnection(rotated, csrf);
        assertThat(stale.statusCode()).isEqualTo(403);
        assertThat(actionGrantCountOf(signed.user())).isEqualTo(grants);

        Signed fresh = reauthenticate(new Signed(signed.user(), signed.subject(), rotated, csrf));
        var prepared = prepareBybitConnection(fresh.cookie(), fresh.csrf());
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(confirmationIn(prepared.body())).isNotBlank();
        assertThat(sourceConnectionCountOf(signed.user())).isEqualTo(1);
        assertThat(connectionStatus(connection)).isEqualTo("REVOKED");
    }

    // ------------------------------------------------------------------------------------------ deletion

    /**
     * AC-8: a deletion that began wins over a confirmation (refused, nothing changes); the erasure after a revocation
     * removes the revoked connection with its metadata, the provider account, the plan and its results, and keeps
     * another user's revoked connection.
     */
    @Test
    void erasureRemovesTheRevocationAndADeletionWinsOverTheConfirmation() throws Exception {
        Signed kept = signedInWithPlan("revoke-kept");
        UUID keptConnection = connectionOf(kept.user());
        revoke(kept, keptConnection, key("revoke-kept"));

        Signed deleting = signedInWithPlan("revoke-deleting");
        UUID deletingConnection = connectionOf(deleting.user());
        var prepared = prepare(deleting, deletingConnection);
        accountDeletion().beginDeletion(deleting.user());
        var refused = confirm(deleting, deletingConnection, confirmationIn(prepared.body()), key("revoke-deleting"));
        assertThat(refused.statusCode()).isIn(401, 403);
        assertThat(connectionStatus(deletingConnection)).isEqualTo("ACTIVE");
        assertThat(credentialCount(deletingConnection)).isEqualTo(1);

        Signed erased = signedInWithPlan("revoke-erased");
        revoke(erased, connectionOf(erased.user()), key("revoke-erased"));
        accountDeletion().beginDeletion(erased.user());
        portfolio.erasePortfolioData(erased.user());
        sync.eraseSyncData(erased.user());
        plans.erasePlanData(erased.user());
        source.eraseSourceData(erased.user());
        accountDeletion().erasePayload(erased.user());
        for (String table : List.of("source_connection", "source_provider_account", "plan_definition",
                "sync_plan_calculation", "sync_official_plan_result", "action_grant")) {
            assertThat(countOf(table, erased.user())).as(table).isZero();
        }
        assertThat(connectionStatus(keptConnection)).isEqualTo("REVOKED");
        assertThat(sync.dashboard(kept.user()).dashboard()).isInstanceOf(SyncJobApi.Dashboard.Ready.class);
    }

    /** V16's pair check: a revoked row has its three facts, an active row none of them. */
    @Test
    void theSchemaPairsTheRevocationStateWithItsFacts() throws Exception {
        Signed signed = signedInWithPlan("revoke-schema");
        UUID connection = connectionOf(signed.user());
        assertThatSqlFails(() -> jdbc().sql("UPDATE source_connection SET status='REVOKED' WHERE connection_id=:c")
                .param("c", connection).update());
        assertThatSqlFails(() -> jdbc().sql("UPDATE source_connection SET revoked_at=now() WHERE connection_id=:c")
                .param("c", connection).update());
        revoke(signed, connection, key("revoke-schema"));
        assertThatSqlFails(() -> jdbc().sql("UPDATE source_connection SET status='ACTIVE' WHERE connection_id=:c")
                .param("c", connection).update());
    }

    // ------------------------------------------------------------------------------------------ review additions

    /**
     * The revocation time is monotonic: a verification and a membership start ahead of the database clock (a provider
     * clock that ran ahead) still give a revocation after both, and the membership closes at that same time.
     */
    @Test
    void theRevocationTimeComesAfterTheVerificationAndTheMembershipStart() throws Exception {
        Signed signed = signedInWithPlan("revoke-monotonic");
        UUID connection = connectionOf(signed.user());
        jdbc().sql("UPDATE source_connection SET verified_at=now()+interval '1 hour' WHERE connection_id=:c")
                .param("c", connection).update();
        jdbc().sql("UPDATE portfolio_source_membership SET started_at=now()+interval '2 hours' WHERE connection_id=:c")
                .param("c", connection).update();
        Instant start = jdbc().sql("SELECT started_at FROM portfolio_source_membership WHERE connection_id=:c")
                .param("c", connection).query(OffsetDateTime.class).single().toInstant();

        var revoked = revoke(signed, connection, key("revoke-monotonic"));
        Instant revokedAt = Instant.parse((String) topLevelFieldsOf(revoked.body()).get("revokedAt"));
        assertThat(revokedAt).isAfter(start).isEqualTo(revokedAt.truncatedTo(ChronoUnit.MILLIS));
        assertThat(jdbc().sql("SELECT ended_at FROM portfolio_source_membership WHERE connection_id=:c")
                .param("c", connection).query(OffsetDateTime.class).single().toInstant()).isEqualTo(revokedAt);
    }

    /**
     * Before any plan, the first flow's attempts in progress — a first snapshot, a collection, a classification, a
     * conversion and a first publication, each held by a holder whose lease is still valid — are ended by the
     * revocation with each table's own ending, at the revocation's time; the holder's fenced statements then find
     * nothing to own, and nothing is published.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theFirstFlowsAttemptsInProgressEndWithTheRevocation(CapturedOutput output) throws Exception {
        Signed signed = signedInWithInputs("revoke-first-flow");
        UUID user = signed.user();
        UUID connection = connectionOf(user);
        var ids = jdbc().sql("SELECT i.intent_id, i.portfolio_id, j.provider_account_id, j.t0, b.candidate_id, "
                + "k.classification_id, v.conversion_id FROM sync_initial_collection_job j "
                + "JOIN plan_condition_intent i ON i.intent_id=j.intent_id "
                + "JOIN sync_initial_candidate_bundle b ON b.user_id=j.user_id "
                + "JOIN sync_cashflow_classification_bundle k ON k.candidate_id=b.candidate_id "
                + "JOIN sync_cashflow_conversion_bundle v ON v.classification_id=k.classification_id "
                + "WHERE j.user_id=:u LIMIT 1").param("u", user)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, OffsetDateTime.class), rs.getObject(5, UUID.class),
                        rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)}).single();
        UUID intent = (UUID) ids[0];
        UUID portfolioId = (UUID) ids[1];
        UUID account = (UUID) ids[2];
        OffsetDateTime t0 = (OffsetDateTime) ids[3];
        UUID candidate = (UUID) ids[4];
        UUID classification = (UUID) ids[5];
        UUID conversion = (UUID) ids[6];
        String lease = "lease_until=now()+interval '1 hour'";
        Map<String, UUID> held = new LinkedHashMap<>();
        held.put("sync_job", UUID.randomUUID());
        held.put("sync_initial_collection_job", UUID.randomUUID());
        held.put("sync_cashflow_classification_job", UUID.randomUUID());
        held.put("sync_cashflow_conversion_job", UUID.randomUUID());
        held.put("sync_plan_publication_job", UUID.randomUUID());
        jdbc().sql("INSERT INTO sync_job(job_id,user_id,connection_id,provider_account_id,idempotency_key,status,"
                + "fencing_generation,lease_until,heartbeat_at) VALUES (:j,:u,:c,:a,:k,'RUNNING',7,now()+interval "
                + "'1 hour',now())").param("j", held.get("sync_job")).param("u", user).param("c", connection)
                .param("a", account).param("k", key("held-first")).update();
        jdbc().sql("INSERT INTO sync_initial_collection_job(job_id,user_id,portfolio_id,intent_id,connection_id,"
                + "provider_account_id,idempotency_key,status,t0,fencing_generation,lease_until) VALUES "
                + "(:j,:u,:p,:i,:c,:a,:k,'RUNNING',:t0,7,now()+interval '1 hour')")
                .param("j", held.get("sync_initial_collection_job")).param("u", user).param("p", portfolioId)
                .param("i", intent).param("c", connection).param("a", account).param("k", key("held-collection"))
                .param("t0", t0).update();
        jdbc().sql("INSERT INTO sync_cashflow_classification_job(job_id,user_id,portfolio_id,intent_id,connection_id,"
                + "provider_account_id,candidate_id,policy_version,idempotency_key,status,fencing_generation,"
                + "lease_until) VALUES (:j,:u,:p,:i,:c,:a,:cand,'held-policy',:k,'RUNNING',7,now()+interval '1 hour')")
                .param("j", held.get("sync_cashflow_classification_job")).param("u", user).param("p", portfolioId)
                .param("i", intent).param("c", connection).param("a", account).param("cand", candidate)
                .param("k", key("held-classification")).update();
        jdbc().sql("INSERT INTO sync_cashflow_conversion_job(job_id,user_id,portfolio_id,intent_id,connection_id,"
                + "provider_account_id,classification_id,input_scope_hash,flow_count,policy_version,"
                + "calculation_policy_version,idempotency_key,status,fencing_generation,lease_until) VALUES "
                + "(:j,:u,:p,:i,:c,:a,:k2,repeat('a',64),0,'held','held',:k,'RUNNING',7,now()+interval '1 hour')")
                .param("j", held.get("sync_cashflow_conversion_job")).param("u", user).param("p", portfolioId)
                .param("i", intent).param("c", connection).param("a", account).param("k2", classification)
                .param("k", key("held-conversion")).update();
        jdbc().sql("INSERT INTO sync_plan_publication_job(job_id,user_id,portfolio_id,intent_id,membership_id,"
                + "connection_id,provider_account_id,basis_portfolio_snapshot_id,basis_source_snapshot_id,"
                + "expected_portfolio_snapshot_id,expected_portfolio_sequence,candidate_id,classification_id,"
                + "conversion_id,p0_decimal,t0,end_at,period,target_rate,tc,current_value_decimal,"
                + "current_value_measured_at,flow_count,input_scope_hash,calculation_policy,calendar_policy,"
                + "idempotency_key,status,fencing_generation,lease_until) VALUES (:j,:u,:p,:i,gen_random_uuid(),:c,:a,"
                + "gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),1,:cand,:cls,:conv,'1000',:t0,"
                + ":t0::timestamptz+interval '30 days',"
                + "'DAILY','0.1',:t0::timestamptz+interval '1 day','1000',now(),0,repeat('b',64),'held','held',:k,"
                + "'RUNNING',7,now()+interval '1 hour')")
                .param("j", held.get("sync_plan_publication_job")).param("u", user).param("p", portfolioId)
                .param("i", intent).param("c", connection).param("a", account).param("cand", candidate)
                .param("cls", classification).param("conv", conversion).param("t0", t0)
                .param("k", key("held-publication")).update();
        var before = recordsOf(user);

        var revoked = revoke(signed, connection, key("revoke-first-flow"));
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        Instant revokedAt = Instant.parse((String) topLevelFieldsOf(revoked.body()).get("revokedAt"));
        Map<String, String> endings = Map.of("sync_job", "BLOCKED|NOT_PERMITTED",
                "sync_initial_collection_job", "CANCELLED|NOT_PERMITTED",
                "sync_cashflow_classification_job", "CANCELLED|NOT_PERMITTED",
                "sync_cashflow_conversion_job", "CANCELLED|NOT_PERMITTED",
                "sync_plan_publication_job", "BLOCKED|NOT_PERMITTED");
        for (var entry : held.entrySet()) {
            String code = entry.getKey().equals("sync_job") ? "failure_reason" : "failure_code";
            assertThat(jdbc().sql("SELECT status||'|'||" + code + " FROM " + entry.getKey() + " WHERE job_id=:j")
                    .param("j", entry.getValue()).query(String.class).single()).as(entry.getKey())
                    .isEqualTo(endings.get(entry.getKey()));
            assertThat(jdbc().sql("SELECT finished_at FROM " + entry.getKey() + " WHERE job_id=:j")
                    .param("j", entry.getValue()).query(OffsetDateTime.class).single().toInstant())
                    .as(entry.getKey()).isEqualTo(revokedAt);
            // The holder's own fenced statement: its row is not running any more.
            assertThat(jdbc().sql("UPDATE " + entry.getKey() + " SET " + lease + " WHERE job_id=:j "
                    + "AND status='RUNNING' AND fencing_generation=7 AND lease_until>now()")
                    .param("j", entry.getValue()).update()).as(entry.getKey()).isZero();
        }
        assertThat(lines(output, "bybit connection revoked: ")).singleElement().satisfies(line -> assertThat(line)
                .contains("endedAttempts=5"));
        assertThat(recordsOf(user)).isEqualTo(before);
        assertThat(countOf("plan_definition", user)).isZero();
    }

    /** AC-5: a precision verification (Story 2.12) collecting when the revocation commits ends with it. */
    @Test
    void aPrecisionVerificationInProgressEndsWithTheRevocation() throws Exception {
        Signed signed = signedInWithPlan("revoke-verification");
        UUID connection = connectionOf(signed.user());
        var v1 = ready(signed.user());
        slowPageStores(signed.user());
        UUID verifying;
        try {
            var admission = sync.admitPlanVerification(signed.user(), SyncJobApi.VerificationKind.PRECISION,
                    new SyncJobApi.EvidenceReference(v1.planId(), v1.pointerVersion(), v1.calculationId()),
                    key("revoke-verification-job"));
            assertThat(admission).isInstanceOf(SyncJobApi.VerificationAdmission.Accepted.class);
            verifying = ((SyncJobApi.VerificationAdmission.Accepted) admission).job().jobId();
            awaitStage(verifying, "COLLECTION");
            assertThat(revoke(signed, connection, key("revoke-verification")).statusCode()).isEqualTo(200);
        } finally {
            dropSlowPageStores();
        }
        assertThat(awaitSync(verifying)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT purpose||'|'||failure_code FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", verifying).query(String.class).single()).isEqualTo("VERIFY|NOT_PERMITTED");
        assertNoChildInProgress(verifying);
        assertThat(ready(signed.user()).calculationId()).isEqualTo(v1.calculationId());
    }

    /** A revoked plan can still end: early (USER_REQUEST), and after a long gap through the new-plan choice. */
    @Test
    void aRevokedPlanCanStillEndEarlyOrThroughTheLongGapChoice() throws Exception {
        Signed early = signedInWithPlan("revoke-then-end");
        var e1 = ready(early.user());
        String cookie = sessionCookieOf(revoke(early, connectionOf(early.user()), key("revoke-then-end")))
                .orElseThrow();
        // 해제가 재인증 시각을 지웠다(본인 확인 1회는 command 1건) — 같은 세션의 종료 준비는 거절되고 허가도 늘지 않는다.
        Signed stale = new Signed(early.user(), early.subject(), cookie, csrfTokenOf(cookie));
        long grants = actionGrantCountOf(early.user());
        var chained = sendJson("POST", PLAN_END_PATH, "{\"reason\":\"USER_REQUEST\",\"planId\":\"" + e1.planId()
                + "\",\"pointerVersion\":" + e1.pointerVersion() + ",\"calculationId\":\"" + e1.calculationId() + "\"}",
                stale.cookie(), protectedHeaders(stale.csrf(), null));
        assertThat(chained.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(chained.body())).containsEntry("code", "REAUTHENTICATION_REQUIRED");
        assertThat(actionGrantCountOf(early.user())).isEqualTo(grants);
        // 종료는 새 재인증 위에서 준비한다.
        Signed afterEarly = reauthenticate(stale);
        assertThat(nested(topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, afterEarly.cookie()).body()),
                "planEnd")).containsEntry("availability", "AVAILABLE");
        String endedCookie = sessionCookieOf(endPlan(afterEarly, "USER_REQUEST", e1)).orElseThrow();
        assertThat(topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, endedCookie).body()))
                .containsEntry("state", "ENDED");

        Signed gap = signedInWithPlan("revoke-gap-end", Instant.now().minus(Duration.ofDays(45)));
        var g1 = ready(gap.user());
        moveVerification(gap.user(), g1.calculationId(), "31 days");
        var g1Moved = ready(gap.user());
        String gapCookie = sessionCookieOf(revoke(gap, connectionOf(gap.user()), key("revoke-gap-end")))
                .orElseThrow();
        Signed afterGap = reauthenticate(new Signed(gap.user(), gap.subject(), gapCookie, csrfTokenOf(gapCookie)));
        var dashboard = topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, afterGap.cookie()).body());
        assertThat(nested(dashboard, "planEnd")).containsEntry("availability", "NEW_PLAN_CHOICE");
        assertThat(nested(dashboard, "sync")).containsEntry("reason", "CONNECTION_REVOKED");
        assertThat(endPlan(afterGap, "LONG_GAP_NEW_PLAN", g1Moved).statusCode()).isEqualTo(200);
        assertThat(jdbc().sql("SELECT reason FROM plan_end WHERE user_id=:u").param("u", gap.user())
                .query(String.class).single()).isEqualTo("LONG_GAP_NEW_PLAN");
    }

    /** AC-7: the first official value read before any plan stays read-only after the revocation, flagged revoked. */
    @Test
    void theFirstSnapshotStateSaysRevokedWithAndWithoutItsValue() throws Exception {
        Signed bare = signedInWithConnection("revoke-snapshot-bare");
        String bareCookie = sessionCookieOf(revoke(bare, connectionOf(bare.user()), key("revoke-snapshot-bare")))
                .orElseThrow();
        var bareState = topLevelFieldsOf(get(AuthSecurityConfig.FIRST_SNAPSHOT_PATH, bareCookie).body());
        assertThat(bareState).containsEntry("connected", false).containsEntry("revoked", true)
                .doesNotContainKey("decimal");

        Signed valued = signedInWithConnection("revoke-snapshot-value");
        UUID job = sync.admit(valued.user(), key("revoke-snapshot-seed")).orElseThrow().jobId();
        pollUntil(Duration.ofSeconds(10), () -> sync.job(valued.user(), job).orElseThrow().status(),
                PlanSyncTestBase::finished);
        var official = portfolio.officialValue(valued.user()).orElseThrow();
        String valuedCookie = sessionCookieOf(revoke(valued, connectionOf(valued.user()),
                key("revoke-snapshot-value"))).orElseThrow();
        var state = topLevelFieldsOf(get(AuthSecurityConfig.FIRST_SNAPSHOT_PATH, valuedCookie).body());
        assertThat(state).containsEntry("connected", false).containsEntry("revoked", true)
                .containsEntry("decimal", official.decimal())
                .containsEntry("measuredAt", official.measuredAt().toString());
        // A never-connected account's answer keeps its old shape.
        String subject = "revoke-snapshot-none-" + UUID.randomUUID();
        String none = sessionCookieOf(signIn(subject)).orElseThrow();
        assertThat(topLevelFieldsOf(get(AuthSecurityConfig.FIRST_SNAPSHOT_PATH, none).body()))
                .doesNotContainKey("revoked");
    }

    /** The connect answer names the opaque connection the settings screen revokes without reading again. */
    @Test
    void theConnectAnswerNamesTheConnection() throws Exception {
        String subject = "revoke-connect-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        Signed signed = reauthenticate(new Signed(user, subject, cookie, csrfTokenOf(cookie)));
        var connected = connectBybitRoundTrip(signed.cookie(), signed.csrf(),
                BybitStubConfiguration.apiKey(DevBybitStub.Scenario.UTA2, "961" + (int) (Math.random() * 1000)),
                BybitStubConfiguration.newSecret());
        assertThat(connected.statusCode()).as(connected.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(connected.body())).containsEntry("connected", true)
                .containsEntry("revoked", false).containsEntry("connectionId", connectionOf(user).toString());
    }

    /** A revocation is a protective action: an operational restriction does not stop it. */
    @Test
    void anOperationalRestrictionDoesNotStopTheRevocation() throws Exception {
        Signed signed = signedInWithPlan("revoke-restricted");
        UUID connection = connectionOf(signed.user());
        // 모든 기능을 전역으로 막는다(Story 3.2): 해제는 그 어느 것도 읽지 않는다.
        for (var feature : com.bokrilog.operations.RestrictionFeature.values())
            restrictGlobally(feature);
        assertThat(revoke(signed, connection, key("revoke-restricted")).statusCode()).isEqualTo(200);
        assertThat(connectionStatus(connection)).isEqualTo("REVOKED");
    }

    // ------------------------------------------------------------------------------------------ flows

    /** Link, unlink, reauthentication starts drop an armed revocation and its grant; a refused connect keeps it. */
    @Test
    void theOtherFlowsDropAnArmedRevocationOnlyOnceTheirGatesPass() throws Exception {
        for (String other : List.of("link", "unlink", "reauth")) {
            Signed signed = signedInWithTwoMethods("revoke-dropped-by-" + other);
            UUID connection = connectionOf(signed.user());
            assertThat(prepare(signed, connection).statusCode()).isEqualTo(200);
            String grant = grantReferenceOf(signed.user());
            int status = switch (other) {
                case "link" -> startLink(KAKAO, signed.cookie(), signed.csrf()).statusCode();
                case "unlink" -> prepareUnlink(NAVER, signed.cookie(), signed.csrf()).statusCode();
                default -> startReauthentication(GOOGLE, signed.cookie(), signed.csrf()).statusCode();
            };
            assertThat(status).as(other).isEqualTo(204);
            assertThat(sessionAttribute(sessionIdOf(signed.cookie()), BybitRevocationFlow.FLOW_ATTRIBUTE)).as(other)
                    .isNull();
            assertThat(actionGrantExists(grant)).as(other).isFalse();
        }
        Signed kept = signedInWithTwoMethods("revoke-kept-by-connect");
        assertThat(prepare(kept, connectionOf(kept.user())).statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(kept.user());
        assertThat(prepareBybitConnection(kept.cookie(), kept.csrf()).statusCode()).as("an active connection")
                .isEqualTo(403);
        assertThat(sessionAttribute(sessionIdOf(kept.cookie()), BybitRevocationFlow.FLOW_ATTRIBUTE))
                .isInstanceOf(BybitRevocationFlow.Armed.class);
        assertThat(actionGrantExists(grant)).isTrue();
    }

    /** A revocation preparation that passes drops the link and unlink armings; one that is refused drops nothing. */
    @Test
    void theRevocationDropsTheOtherFlowsOnlyOnceItsGatesPass() throws Exception {
        Signed signed = signedInWithTwoMethods("revoke-drops-unlink");
        assertThat(prepareUnlink(NAVER, signed.cookie(), signed.csrf()).statusCode()).isEqualTo(204);
        String sessionId = sessionIdOf(signed.cookie());
        String unlinkGrant = jdbc().sql("SELECT grant_id FROM action_grant WHERE user_id=:u").param("u", signed.user())
                .query(String.class).single();

        assertThat(prepare(signed, UUID.randomUUID()).statusCode()).as("CONNECTION_NOT_FOUND").isEqualTo(404);
        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE)).isInstanceOf(UnlinkFlow.Armed.class);
        assertThat(actionGrantExists(unlinkGrant)).isTrue();

        ageReauthentication(sessionId, Duration.ofMinutes(6));
        var stale = prepare(signed, connectionOf(signed.user()));
        assertThat(topLevelFieldsOf(stale.body())).containsEntry("code", "REAUTHENTICATION_REQUIRED");
        assertThat(sessionAttribute(sessionId, UnlinkFlow.FLOW_ATTRIBUTE)).isInstanceOf(UnlinkFlow.Armed.class);
        assertThat(actionGrantExists(unlinkGrant)).isTrue();

        Signed fresh = signedInWithTwoMethods("revoke-drops-link");
        assertThat(startLink(KAKAO, fresh.cookie(), fresh.csrf()).statusCode()).isEqualTo(204);
        String linkGrant = jdbc().sql("SELECT grant_id FROM action_grant WHERE user_id=:u").param("u", fresh.user())
                .query(String.class).single();
        assertThat(prepare(fresh, connectionOf(fresh.user())).statusCode()).isEqualTo(200);
        assertThat(sessionAttribute(sessionIdOf(fresh.cookie()), LinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(actionGrantExists(linkGrant)).isFalse();
        assertThat(grantReferenceOf(fresh.user())).isNotNull();

        Signed unlinking = signedInWithTwoMethods("revoke-drops-unlink-2");
        assertThat(prepareUnlink(NAVER, unlinking.cookie(), unlinking.csrf()).statusCode()).isEqualTo(204);
        assertThat(prepare(unlinking, connectionOf(unlinking.user())).statusCode()).isEqualTo(200);
        assertThat(sessionAttribute(sessionIdOf(unlinking.cookie()), UnlinkFlow.FLOW_ATTRIBUTE)).isNull();
        assertThat(actionGrantCountOf(unlinking.user())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ helpers

    record Signed(UUID user, String subject, String cookie, String csrf) {}

    /** The connection's records that a revocation must leave as they were. */
    record Records(String plans, int calculations, int pointers, int snapshots, int components, int flows,
            int sourceSnapshots, int events, int memberships, int portfolios) {}

    private Records recordsOf(UUID user) {
        return new Records(
                jdbc().sql("SELECT string_agg(plan_id||'|'||status||'|'||p0_decimal||'|'||t0||'|'||end_at, ',' "
                        + "ORDER BY plan_id) FROM plan_definition WHERE user_id=:u").param("u", user)
                        .query(String.class).optional().orElse("-"),
                countOf("sync_plan_calculation", user), countOf("sync_official_plan_result", user),
                countOf("portfolio_snapshot", user), countOf("portfolio_snapshot_component", user),
                jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow f JOIN sync_plan_calculation c ON "
                        + "c.calculation_id=f.calculation_id WHERE c.user_id=:u").param("u", user).query(Integer.class)
                        .single(),
                countOf("sync_source_snapshot", user),
                countOf("sync_initial_source_event", user),
                jdbc().sql("SELECT count(*) FROM portfolio_source_membership m JOIN portfolio p ON "
                        + "p.portfolio_id=m.portfolio_id WHERE p.user_id=:u").param("u", user).query(Integer.class)
                        .single(),
                countOf("portfolio", user));
    }

    private Signed signedInWithConnection(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        return reauthenticate(new Signed(user, subject, cookie, csrfTokenOf(cookie)));
    }

    /** Google and Naver linked, a connection, a fresh reauthentication: every flow of the settings screen can start. */
    private Signed signedInWithTwoMethods(String prefix) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        assertThat(accountLifecycle().linkSocialIdentity(user, NAVER, OidcStubConfiguration.issuer(NAVER),
                prefix + "-naver-" + UUID.randomUUID())).isEqualTo(com.bokrilog.identity.AccessDecision.ALLOWED);
        store(user, DevBybitStub.Scenario.UTA2);
        return reauthenticate(new Signed(user, subject, cookie, csrfTokenOf(cookie)));
    }

    /** A connection with the first snapshot, conditions and the inputs collected, classified and converted: no plan. */
    private Signed signedInWithInputs(String prefix) throws Exception {
        Signed signed = signedInWithConnection(prefix);
        UUID user = signed.user();
        UUID firstJob = sync.admit(user, key(prefix + "-seed")).orElseThrow().jobId();
        pollUntil(Duration.ofSeconds(10), () -> sync.job(user, firstJob).orElseThrow().status(),
                PlanSyncTestBase::finished);
        var snapshot = plans.state(user).snapshot();
        String end = java.time.LocalDateTime.ofInstant(snapshot.measuredAt(), java.time.ZoneId.of("Asia/Seoul"))
                .plusDays(30).withSecond(0).withNano(0).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var preview = plans.preview(user, prefix + "-session",
                new com.bokrilog.plan.PlanConditionApi.PreviewInput(end, "DAILY", "0.1")).orElseThrow();
        UUID intent = plans.confirm(user, prefix + "-session", preview.confirmationRef(), key(prefix + "-confirm"))
                .intent().intentId();
        UUID collection = sync.admitInitialInputs(user, intent, key(prefix + "-inputs")).orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", collection, "INPUTS_COMPLETE");
        UUID classification = sync.admitCashflowClassification(user, intent, key(prefix + "-classification"))
                .orElseThrow().jobId();
        awaitTable("sync_cashflow_classification_job", classification, "COMPLETE");
        UUID conversion = sync.admitCashflowConversion(user, intent, key(prefix + "-conversion")).orElseThrow()
                .jobId();
        awaitTable("sync_cashflow_conversion_job", conversion, "CONVERTED");
        return signed;
    }

    private HttpResponse<String> endPlan(Signed signed, String reason, SyncJobApi.DashboardResult version)
            throws Exception {
        String body = "{\"reason\":\"" + reason + "\",\"planId\":\"" + version.planId() + "\",\"pointerVersion\":"
                + version.pointerVersion() + ",\"calculationId\":\"" + version.calculationId() + "\"";
        var prepared = sendJson("POST", PLAN_END_PATH, body + "}", signed.cookie(),
                protectedHeaders(signed.csrf(), null));
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        var ended = sendJson("POST", PLAN_END_PATH + "/confirm", body + ",\"confirmation\":\""
                + confirmationIn(prepared.body()) + "\"}", signed.cookie(),
                protectedHeaders(signed.csrf(), key("revoke-end")));
        assertThat(ended.statusCode()).as(ended.body()).isEqualTo(200);
        return ended;
    }

    private Signed signedInWithPlan(String prefix) throws Exception {
        return signedInWithPlan(prefix, null);
    }

    private Signed signedInWithPlan(String prefix, @Nullable Instant start) throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        publishedPlan(user, prefix, start);
        return reauthenticate(new Signed(user, subject, cookie, csrfTokenOf(cookie)));
    }

    private Signed reauthenticate(Signed signed) throws Exception {
        var reauthenticated = reauthenticate(GOOGLE, signed.cookie(), signed.csrf(),
                plan -> plan.subject(signed.subject()));
        String rotated = sessionCookieOf(reauthenticated).orElseThrow();
        return new Signed(signed.user(), signed.subject(), rotated, csrfTokenOf(rotated));
    }

    /** A key unique to the call, made at run time: no key-shaped literal sits in the source. */
    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private UUID connectionOf(UUID user) {
        return jdbc().sql("SELECT connection_id FROM source_connection WHERE user_id=:u AND status='ACTIVE'")
                .param("u", user).query(UUID.class).single();
    }

    private String connectionStatus(UUID connection) {
        return jdbc().sql("SELECT status FROM source_connection WHERE connection_id=:c").param("c", connection)
                .query(String.class).single();
    }

    private int credentialCount(UUID connection) {
        return jdbc().sql("SELECT count(*) FROM source_credential WHERE connection_id=:c").param("c", connection)
                .query(Integer.class).single();
    }

    /** The timestamptz text PostgreSQL concatenates, for a UTC session: {@code 2026-10-05 01:02:03.456+00}. */
    private String pg(Instant at) {
        return jdbc().sql("SELECT (:t)::timestamptz::text").param("t", OffsetDateTime.ofInstant(at,
                java.time.ZoneOffset.UTC)).query(String.class).single();
    }

    private HttpResponse<String> prepare(Signed signed, UUID connection) throws Exception {
        return sendJson("POST", REVOCATION_PATH, "{\"connectionId\":\"" + connection + "\"}", signed.cookie(),
                protectedHeaders(signed.csrf(), null));
    }

    private HttpResponse<String> confirm(Signed signed, UUID connection, String confirmation, String key)
            throws Exception {
        return sendJson("POST", CONFIRM_PATH, confirmBody(connection, confirmation), signed.cookie(),
                protectedHeaders(signed.csrf(), key));
    }

    /** Prepares and confirms; both must succeed. */
    private HttpResponse<String> revoke(Signed signed, UUID connection, String key) throws Exception {
        var prepared = prepare(signed, connection);
        if (prepared.statusCode() != 200)
            throw new IllegalStateException("prepare: " + prepared.statusCode() + " " + prepared.body());
        var confirmed = confirm(signed, connection, confirmationIn(prepared.body()), key);
        if (confirmed.statusCode() != 200)
            throw new IllegalStateException("confirm: " + confirmed.statusCode() + " " + confirmed.body());
        return confirmed;
    }

    private static String confirmBody(UUID connection, String confirmation) {
        return "{\"connectionId\":\"" + connection + "\",\"confirmation\":\"" + confirmation + "\"}";
    }

    private static String[] protectedHeaders(@Nullable String csrf, @Nullable String key) {
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

    private void assertNotConfirmed(HttpResponse<String> response, UUID connection) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(topLevelFieldsOf(response.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", "REVOCATION_NOT_CONFIRMED");
        assertThat(connectionStatus(connection)).isEqualTo("ACTIVE");
        assertThat(credentialCount(connection)).isEqualTo(1);
    }

    private static Map<String, Object> withoutCorrelation(String body) {
        Map<String, Object> fields = new LinkedHashMap<>(topLevelFieldsOf(body));
        fields.remove("correlationId");
        return fields;
    }

    private String grantReferenceOf(UUID user) {
        return jdbc().sql("SELECT grant_id FROM action_grant WHERE user_id=:u AND command='bybit-connection-revoke' "
                + "AND consumed_at IS NULL").param("u", user).query(String.class).single();
    }

    private static void assertThatSqlFails(Runnable statement) {
        try {
            statement.run();
        } catch (org.springframework.dao.DataAccessException expected) {
            return;
        }
        throw new AssertionError("the statement was accepted");
    }

    private UUID manualSync(UUID user, String key) {
        var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key);
        assertThat(admission).as("admission").isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        return ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
    }

    private void awaitStage(UUID job, String stage) throws InterruptedException {
        String state = pollUntil(Duration.ofSeconds(30), () -> jdbc().sql("SELECT status||'|'||COALESCE(stage,'-') "
                + "FROM sync_plan_sync_job WHERE job_id=:j").param("j", job).query(String.class).single(),
                found -> found.equals("RUNNING|" + stage));
        assertThat(state).isEqualTo("RUNNING|" + stage);
    }

    /** Page stores of this user's collection sleep: a sync stays in its collection long enough to be overtaken. */
    private void slowPageStores(UUID user) {
        jdbc().sql("CREATE OR REPLACE FUNCTION slow_revocation_pages() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.user_id='" + user + "'::uuid THEN PERFORM pg_sleep(1.5); END IF; RETURN NEW; END $$")
                .update();
        jdbc().sql("CREATE OR REPLACE TRIGGER slow_revocation_pages BEFORE INSERT ON sync_initial_page_evidence "
                + "FOR EACH ROW EXECUTE FUNCTION slow_revocation_pages()").update();
    }

    private void dropSlowPageStores() {
        jdbc().sql("DROP TRIGGER IF EXISTS slow_revocation_pages ON sync_initial_page_evidence").update();
        jdbc().sql("DROP FUNCTION IF EXISTS slow_revocation_pages()").update();
    }

    /** The not-granted locks on this user's plan lock ({@code PlanEndTest}'s query). */
    private static final String PLAN_LOCK_WAITERS = "SELECT pid FROM pg_locks WHERE locktype='advisory' AND NOT granted "
            + "AND objsubid=1 AND ((classid::bigint<<32)|objid::bigint)=hashtextextended(:u,0)";

    /** Waits until a transaction waits on the row of a transaction that itself waits on this user's plan lock. */
    private void awaitRowLockWaiter(UUID user) throws InterruptedException {
        int waiting = pollUntil(Duration.ofSeconds(10), () -> jdbc().sql("SELECT count(*) FROM pg_locks w "
                + "JOIN pg_locks h ON h.locktype='transactionid' AND h.granted AND h.transactionid=w.transactionid "
                + "WHERE w.locktype='transactionid' AND NOT w.granted AND h.pid IN (" + PLAN_LOCK_WAITERS + ")")
                .param("u", user.toString()).query(Integer.class).single(), count -> count > 0);
        if (waiting == 0) throw new AssertionError("the second confirmation never waited on the grant");
    }

    private void awaitPlanLockWaiters(UUID user, int count) throws InterruptedException {
        int waiting = pollUntil(Duration.ofSeconds(10), () -> jdbc().sql("SELECT count(*) FROM (" + PLAN_LOCK_WAITERS
                + ") w").param("u", user.toString()).query(Integer.class).single(), found -> found >= count);
        if (waiting < count) throw new AssertionError("only " + waiting + " waited on the plan lock");
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
