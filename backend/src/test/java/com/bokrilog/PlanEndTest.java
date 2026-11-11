package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.plan.PlanEndApi;
import com.bokrilog.sync.SyncJobApi;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * Story 2.13: the explicit early end of an active plan, end to end on the real stack (Docker PostgreSQL, the worker,
 * the local OIDC and Bybit stubs). Each test signs in through the stub, publishes a first official version (Story 2.7)
 * and ends it over HTTP — recent reauthentication, the {@code plan-end} grant, the confirmation — then reads the
 * read-only record: the eligibility and its one order, the grant's five bindings and their misuse, the long-gap
 * choice without any provider call, the server's end time beside the unchanged last measurement, the atomic
 * transition and its rollback, idempotent replays, two tabs, the race with a sync's publication either way, the
 * {@code ENDED} dashboard with its evidence and history, the plan conditions closed by {@code PLAN_ENDED}, erasure and
 * the logs.
 */
// The plan lock is held by a resource the body never names: holding it is the point.
@SuppressWarnings("try")
class PlanEndTest extends PlanSyncTestBase {
    private static final String GOOGLE = AuthSecurityConfig.GOOGLE_REGISTRATION_ID;
    private static final String PLAN_END_PATH = AuthSecurityConfig.DASHBOARD_PATH + "/plan-end";
    private static final String CONFIRM_PATH = PLAN_END_PATH + "/confirm";
    private static final String CANCEL_PATH = PLAN_END_PATH + "/cancel";
    private static final String SYNC_PATH = AuthSecurityConfig.DASHBOARD_PATH + "/sync";
    private static final String VERIFICATION_PATH = AuthSecurityConfig.DASHBOARD_PATH + "/verification";

    // ------------------------------------------------------------------------------------------ EARLY_END

    /**
     * AC-1~AC-5, AC-7: the preparation answers the server's canonical confirmation phrase and changes nothing but the
     * grant and the arming; the confirmation ends the plan in one transaction — {@code ENDED_EARLY}, one end record with
     * the server's time and the last official references and times copied, the grant consumed with its result, the
     * session rotated — without any provider call, and leaves every input, cash flow, version and pointer as it was.
     * The dashboard then shows the {@code ENDED} record, the evidence and the history still answer 200 (the current
     * version applied until the end), no sync or verification is admitted, and each request writes one line without
     * the user, plan, key, grant or phrase.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anEarlyEndPreparesConfirmsAndLeavesAReadOnlyRecord(CapturedOutput output) throws Exception {
        Signed signed = signedInWithPlan("end-early", Instant.now().minus(Duration.ofDays(10)));
        var v1 = ready(signed.user());
        var before = snapshotOf(signed.user());
        BybitStubConfiguration.resetRequestCount();

        // READY says the plan can be ended now.
        var dashboard = topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, signed.cookie()).body());
        assertThat(nested(dashboard, "planEnd")).containsOnlyKeys("availability", "reason")
                .containsEntry("availability", "AVAILABLE").containsEntry("reason", null);

        var prepared = prepare(signed, "USER_REQUEST", v1);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(prepared.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String confirmation = "plan-end|USER_REQUEST|" + v1.planId() + "|1|" + v1.calculationId();
        assertThat(topLevelFieldsOf(prepared.body())).containsOnlyKeys("reason", "planId", "pointerVersion",
                "calculationId", "confirmation").containsEntry("reason", "USER_REQUEST")
                .containsEntry("planId", v1.planId().toString()).containsEntry("pointerVersion", 1)
                .containsEntry("calculationId", v1.calculationId().toString())
                .containsEntry("confirmation", confirmation);
        // The grant is issued, bound and unconsumed; its reference never leaves the server. Nothing else moved.
        String grant = grantReferenceOf(signed.user());
        assertThat(prepared.body()).doesNotContain(grant);
        assertThat(actionGrantConsumed(grant)).isFalse();
        assertThat(jdbc().sql("SELECT command||'|'||target FROM action_grant WHERE grant_id=:g").param("g", grant)
                .query(String.class).single()).isEqualTo("plan-end|" + v1.planId());
        assertThat(planStatus(signed.user())).isEqualTo("ACTIVE");
        assertThat(countOf("plan_end", signed.user())).isZero();

        Instant earliest = databaseNow().truncatedTo(ChronoUnit.MILLIS);
        String key = "end-early-key-0000001";
        var confirmed = confirm(signed, "USER_REQUEST", v1, confirmation, key);
        Instant latest = databaseNow();
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var ended = topLevelFieldsOf(confirmed.body());
        assertThat(ended).containsOnlyKeys("outcome", "planId", "reason", "endedAt").containsEntry("outcome", "ENDED")
                .containsEntry("planId", v1.planId().toString()).containsEntry("reason", "USER_REQUEST");
        Instant endedAt = Instant.parse((String) ended.get("endedAt"));
        assertThat(endedAt).isBetween(earliest, latest).isEqualTo(endedAt.truncatedTo(ChronoUnit.MILLIS));
        // The session rotated: a new cookie, and the old one names no session any more.
        String rotated = sessionCookieOf(confirmed).orElseThrow();
        assertThat(rotated).isNotEqualTo(signed.cookie());
        // 종료가 실행된 요청이 재인증 시각을 지웠다 — 본인 확인 1회는 command 1건이다(`SessionRotation`).
        assertThat(sessionAttribute(sessionIdOf(rotated), ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE)).isNull();
        assertThat(BybitStubConfiguration.requestCount()).as("no provider call to end").isZero();

        // One transaction: the status, the end record and the grant's consumption with its result.
        assertThat(planStatus(signed.user())).isEqualTo("ENDED_EARLY");
        assertThat(endRow(signed.user())).isEqualTo(String.join("|", v1.planId().toString(), "USER_REQUEST",
                endedAt.toString(), v1.calculationId().toString(), "1", v1.portfolioSnapshotId().toString(),
                v1.measuredAt().toString(), v1.cashflowVerifiedThrough().toString(), "NOT_APPLICABLE", "-", "-", key,
                new PlanEndApi.Request(PlanEndApi.Reason.USER_REQUEST, v1.planId(), 1, v1.calculationId())
                        .requestHash()));
        assertThat(actionGrantConsumed(grant)).isTrue();
        assertThat(actionGrantResult(grant)).contains("ENDED|USER_REQUEST|" + endedAt);
        // Nothing else was written: the terms, the inputs, the versions, the pointers and the cash flows stay.
        assertThat(snapshotOf(signed.user())).isEqualTo(before.withStatus("ENDED_EARLY"));

        // The record: the same bundle as the plan's last official version, freshness ENDED, and the end facts.
        var endedRead = sync.dashboard(signed.user()).dashboard();
        assertThat(endedRead).isInstanceOf(SyncJobApi.Dashboard.Ended.class);
        var record = (SyncJobApi.Dashboard.Ended) endedRead;
        assertThat(record.result().calculationId()).isEqualTo(v1.calculationId());
        assertThat(record.result().measuredAt()).as("the last measurement is not moved to the end")
                .isEqualTo(v1.measuredAt());
        assertThat(record.result().metrics()).isEqualTo(v1.metrics());
        var http = get(AuthSecurityConfig.DASHBOARD_PATH, rotated);
        assertThat(http.statusCode()).isEqualTo(200);
        assertThat(http.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var fields = topLevelFieldsOf(http.body());
        assertThat(fields).containsOnlyKeys("state", "nextAction", "result", "end").containsEntry("state", "ENDED")
                .containsEntry("nextAction", "PREPARE_NEXT_PLAN");
        var result = nested(fields, "result");
        assertThat(result).containsEntry("planStatus", "ENDED_EARLY").containsEntry("measuredAt",
                v1.measuredAt().toString()).containsEntry("pointerVersion", 1);
        var freshness = nested(nested(result, "trust"), "freshness");
        assertThat(freshness).containsEntry("state", "ENDED").containsEntry("cause", "PLAN_ENDED_EARLY")
                .containsEntry("lastNormalAt", v1.measuredAt().toString()).containsEntry("nextAction", "NONE")
                .containsEntry("affectedMetrics", List.of("CURRENT_VALUE", "PLAN_AMOUNT", "PLAN_DIFFERENCE",
                        "ADJUSTED_TARGET", "REQUIRED_RATE", "GOAL_PROGRESS"));
        assertThat(nested(nested(result, "trust"), "inputReliability")).containsEntry("state", "CONFIRMED");
        assertThat(nested(fields, "end")).containsOnlyKeys("reason", "endedAt", "scheduledEndAt",
                "lastNormalMeasuredAt", "lastNormalDelayMillis", "completion", "uncollectedFrom", "uncollectedUntil")
                .containsEntry("reason", "USER_REQUEST").containsEntry("endedAt", endedAt.toString())
                .containsEntry("scheduledEndAt", v1.endAt().toString())
                .containsEntry("lastNormalMeasuredAt", v1.measuredAt().toString())
                .containsEntry("completion", "NOT_APPLICABLE").containsEntry("uncollectedFrom", null)
                .containsEntry("uncollectedUntil", null);
        assertThat(((Number) nested(fields, "end").get("lastNormalDelayMillis")).longValue())
                .isEqualTo(Duration.between(v1.measuredAt(), endedAt).toMillis());

        // The evidence and the history read the same version, read-only; it was applied until the end.
        String reference = "?planId=" + v1.planId() + "&pointerVersion=1&calculationId=" + v1.calculationId();
        var evidence = get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence" + reference, rotated);
        assertThat(evidence.statusCode()).as(evidence.body()).isEqualTo(200);
        var versions = listOf(nested(topLevelFieldsOf(evidence.body()), "versions"), "official");
        assertThat(versions).singleElement().satisfies(version -> assertThat(version)
                .containsEntry("calculationId", v1.calculationId().toString()).containsEntry("current", true)
                .containsEntry("appliedUntil", endedAt.toString()));
        var history = get(AuthSecurityConfig.DASHBOARD_PATH + "/adjustments" + reference, rotated);
        assertThat(history.statusCode()).as(history.body()).isEqualTo(200);

        // No sync, verification or second end is admitted for an ended plan.
        String csrf = csrfTokenOf(rotated);
        var syncRequest = sendJson("POST", SYNC_PATH, "{\"trigger\":\"MANUAL\"}", rotated, protectedHeaders(csrf,
                "end-early-sync-key-01"));
        assertThat(syncRequest.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(syncRequest.body())).containsEntry("code", "NO_ACTIVE_PLAN");
        var verification = sendJson("POST", VERIFICATION_PATH, "{\"kind\":\"PRECISION\",\"planId\":\"" + v1.planId()
                + "\",\"pointerVersion\":1,\"calculationId\":\"" + v1.calculationId() + "\"}", rotated,
                protectedHeaders(csrf, "end-early-verify-key-1"));
        assertThat(verification.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(verification.body())).containsEntry("code", "NO_ACTIVE_PLAN");
        assertThat(countOf("sync_plan_sync_job", signed.user())).isZero();
        assertThat(BybitStubConfiguration.requestCount()).as("no provider call at all").isZero();

        // One line per request, without the user, plan, reference, key, grant or phrase.
        assertThat(lines(output, "plan end request: ")).anySatisfy(line -> assertThat(line)
                .contains("step=prepare result=PREPARED status=200 latencyMs="))
                .anySatisfy(line -> assertThat(line).contains("step=confirm result=ENDED status=200 latencyMs="));
        assertThat(lines(output, "dashboard read: ")).anySatisfy(line -> assertThat(line)
                .contains("state=ENDED status=200"));
        for (String line : lines(output, "plan end request: ")) {
            for (String forbidden : List.of(signed.user().toString(), v1.planId().toString(),
                    v1.calculationId().toString(), key, grant, "plan-end|"))
                assertThat(line).as(forbidden).doesNotContain(forbidden);
        }
    }

    // ------------------------------------------------------------------------------------------ LONG_GAP_NEW_PLAN

    /**
     * AC-2, AC-5: after more than 30 days without verification the dashboard offers only the long-gap choice; an early
     * end is refused ({@code LONG_GAP}), the new-plan choice ends the plan without any provider call and records its
     * reason, the completion comparison {@code UNAVAILABLE_DATA_GAP} and the uncollected range {@code (verified, end]}.
     * In a normal state the new-plan choice is refused ({@code NOT_LONG_GAP}).
     */
    @Test
    void theLongGapChoiceEndsWithoutAProviderCallAndKeepsTheUncollectedRange() throws Exception {
        Signed normal = signedInWithPlan("end-not-gap", Instant.now().minus(Duration.ofDays(10)));
        var n1 = ready(normal.user());
        var refused = prepare(normal, "LONG_GAP_NEW_PLAN", n1);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", "NOT_LONG_GAP").doesNotContainKey("reason");
        assertThat(actionGrantCountOf(normal.user())).isZero();

        Signed signed = signedInWithPlan("end-long-gap", Instant.now().minus(Duration.ofDays(45)));
        moveVerification(signed.user(), ready(signed.user()).calculationId(), "31 days");
        var v1 = ready(signed.user());
        BybitStubConfiguration.resetRequestCount();
        var ready = (SyncJobApi.Dashboard.Ready) sync.dashboard(signed.user()).dashboard();
        assertThat(ready.planEnd()).isEqualTo(new SyncJobApi.PlanEndAvailability("NEW_PLAN_CHOICE", null));
        assertThat(ready.sync().availability()).isEqualTo("LONG_GAP");
        assertThat(planEndOf(signed.cookie())).isEqualTo(planEndView("NEW_PLAN_CHOICE", null));

        var early = prepare(signed, "USER_REQUEST", v1);
        assertThat(early.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(early.body())).containsEntry("code", "LONG_GAP");
        var prepared = prepare(signed, "LONG_GAP_NEW_PLAN", v1);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        String confirmation = confirmationIn(prepared.body());
        assertThat(confirmation).isEqualTo("plan-end|LONG_GAP_NEW_PLAN|" + v1.planId() + "|1|" + v1.calculationId());
        var confirmed = confirm(signed, "LONG_GAP_NEW_PLAN", v1, confirmation, "end-long-gap-key-0001");
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        Instant endedAt = Instant.parse((String) topLevelFieldsOf(confirmed.body()).get("endedAt"));
        assertThat(BybitStubConfiguration.requestCount()).as("the gap is never collected").isZero();

        assertThat(endRow(signed.user())).startsWith(String.join("|", v1.planId().toString(), "LONG_GAP_NEW_PLAN",
                endedAt.toString(), v1.calculationId().toString(), "1", v1.portfolioSnapshotId().toString(),
                v1.measuredAt().toString(), v1.cashflowVerifiedThrough().toString(), "UNAVAILABLE_DATA_GAP",
                v1.cashflowVerifiedThrough().toString(), endedAt.toString()));
        var record = (SyncJobApi.Dashboard.Ended) sync.dashboard(signed.user()).dashboard();
        assertThat(record.end()).isEqualTo(new SyncJobApi.PlanEndRecord("LONG_GAP_NEW_PLAN", endedAt, v1.endAt(),
                v1.measuredAt(), Duration.between(v1.measuredAt(), endedAt).toMillis(), "UNAVAILABLE_DATA_GAP",
                v1.cashflowVerifiedThrough(), endedAt));
        assertThat(record.nextAction()).isEqualTo("PREPARE_NEXT_PLAN");
        assertThat(countOf("sync_plan_sync_job", signed.user())).isZero();
        // The HTTP record carries the same gap: no comparison, and the range (verified, end] never collected.
        var http = topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, sessionCookieOf(confirmed).orElseThrow())
                .body());
        assertThat(http).containsEntry("state", "ENDED");
        assertThat(nested(http, "end")).containsEntry("reason", "LONG_GAP_NEW_PLAN")
                .containsEntry("completion", "UNAVAILABLE_DATA_GAP")
                .containsEntry("uncollectedFrom", v1.cashflowVerifiedThrough().toString())
                .containsEntry("uncollectedUntil", endedAt.toString())
                .containsEntry("endedAt", endedAt.toString());
    }

    // ------------------------------------------------------------------------------------------ NO_REAUTH

    /** AC-3: without a reauthentication, or with one older than five minutes, no grant is issued and nothing moves. */
    @Test
    void withoutAFreshReauthenticationNoGrantIsIssued() throws Exception {
        String subject = "end-no-reauth-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "end-no-reauth", null);
        var v1 = ready(user);
        Signed bare = new Signed(user, subject, cookie, csrfTokenOf(cookie));

        var refused = prepare(bare, "USER_REQUEST", v1);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", "REAUTHENTICATION_REQUIRED");
        assertThat(refused.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(actionGrantCountOf(user)).isZero();

        Signed reauthenticated = reauthenticate(bare);
        ageReauthentication(sessionIdOf(reauthenticated.cookie()), Duration.ofMinutes(6));
        var stale = prepare(reauthenticated, "USER_REQUEST", v1);
        assertThat(stale.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(stale.body())).containsEntry("code", "REAUTHENTICATION_REQUIRED");
        assertThat(actionGrantCountOf(user)).isZero();
        assertThat(planStatus(user)).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------------------------------ NOT_ELIGIBLE

    /**
     * AC-1: another user's plan, an unknown id and an already ended plan are one answer — the same 404
     * {@code PLAN_NOT_FOUND}, nothing issued — so whether a plan exists is never said.
     */
    @Test
    void anotherUsersAnUnknownAndAnEndedPlanAreTheSameNotFound() throws Exception {
        Signed owner = signedInWithPlan("end-owner", null);
        var theirs = ready(owner.user());
        Signed signed = signedInWithPlan("end-other", null);
        var mine = ready(signed.user());

        var foreign = prepare(signed, "USER_REQUEST", theirs.planId(), mine.pointerVersion(), mine.calculationId());
        var unknown = prepare(signed, "USER_REQUEST", UUID.randomUUID(), mine.pointerVersion(), mine.calculationId());
        for (var response : List.of(foreign, unknown)) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(withoutCorrelation(response.body())).isEqualTo(Map.of("code", "PLAN_NOT_FOUND", "message",
                    "종료할 활성 계획을 찾지 못했습니다. 대시보드를 다시 불러와 주세요."));
        }
        assertThat(actionGrantCountOf(signed.user())).isZero();
        assertThat(planStatus(owner.user())).isEqualTo("ACTIVE");

        String rotated = sessionCookieOf(endPlan(signed, "USER_REQUEST", mine, "end-other-key-00001")).orElseThrow();
        // The rotation kept the reauthentication: the refusal is the plan's own, not the grant's.
        Signed again = new Signed(signed.user(), signed.subject(), rotated, csrfTokenOf(rotated));
        var ended = prepare(again, "USER_REQUEST", mine);
        assertThat(ended.statusCode()).isEqualTo(404);
        assertThat(withoutCorrelation(ended.body())).isEqualTo(withoutCorrelation(foreign.body()));
    }

    // ------------------------------------------------------------------------------------------ UNAVAILABLE

    /**
     * Story 3.2: 계산 정책 버전({@code CALC-1.0})의 표시 제한도 계획 종료를 막는다 — 준비와 확정이 같은 code로 거절되고
     * 허가는 소비되지 않으며 계획은 활성으로 남는다. 표시 제한 중의 대시보드도 계획 종료 가용성을 그대로 싣는다.
     */
    @Test
    void aCalculationPolicyDisplayRestrictionStopsThePrepareAndTheConfirmation() throws Exception {
        Signed signed = signedInWithPlan("end-display-restricted", null);
        var v1 = ready(signed.user());
        var prepared = prepare(signed, "USER_REQUEST", v1);
        assertThat(prepared.statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());

        UUID restriction = restrict(com.bokrilog.operations.RestrictionFeature.CALCULATION_DISPLAY,
                com.bokrilog.operations.RestrictionScope.CALCULATION_POLICY_VERSION, "CALC-1.0");
        try {
            assertThat(planEndOf(signed.cookie())).isEqualTo(planEndView("UNAVAILABLE", "RESTRICTED"));
            var confirmed = confirm(signed, "USER_REQUEST", v1, confirmationIn(prepared.body()),
                    "end-display-restricted-key-01");
            assertThat(confirmed.statusCode()).isEqualTo(409);
            assertThat(topLevelFieldsOf(confirmed.body())).containsEntry("code", "PLAN_END_UNAVAILABLE")
                    .containsEntry("reason", "RESTRICTED");
            assertThat(actionGrantConsumed(grant)).isFalse();
            assertThat(planStatus(signed.user())).isEqualTo("ACTIVE");
            var again = prepare(signed, "USER_REQUEST", v1);
            assertThat(again.statusCode()).isEqualTo(409);
            assertThat(topLevelFieldsOf(again.body())).containsEntry("code", "PLAN_END_UNAVAILABLE")
                    .containsEntry("reason", "RESTRICTED");
        } finally {
            lift(restriction);
        }
    }

    /**
     * AC-1: 제한과 {@code T}에 닿은 DB 시각은 계획 종료를 사유와 함께 막는다 — 준비에서, 그리고 확정 트랜잭션 안에서 다시.
     * 거기서의 거절은 허가 소비까지 함께 되돌린다. Story 3.2: 그 제한은 이 계획 하나의 동기화 제한이다.
     */
    @Test
    void aRestrictionOrAReachedEndMakesTheEndUnavailable() throws Exception {
        Signed signed = signedInWithPlan("end-restricted", null);
        var v1 = ready(signed.user());
        var prepared = prepare(signed, "USER_REQUEST", v1);
        assertThat(prepared.statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());

        // 이 계획 하나의 동기화 제한(Story 3.2): 계획 종료는 그 계획의 동기화 파이프라인에 속한다.
        UUID restriction = restrict(com.bokrilog.operations.RestrictionFeature.SYNC,
                com.bokrilog.operations.RestrictionScope.PLAN, v1.planId().toString());
        try {
            var ready = (SyncJobApi.Dashboard.Ready) sync.dashboard(signed.user()).dashboard();
            assertThat(ready.planEnd()).isEqualTo(new SyncJobApi.PlanEndAvailability("UNAVAILABLE", "RESTRICTED"));
            assertThat(planEndOf(signed.cookie())).isEqualTo(planEndView("UNAVAILABLE", "RESTRICTED"));
            // Confirmed after the restriction: refused inside the transaction, the grant stays unconsumed.
            var confirmed = confirm(signed, "USER_REQUEST", v1, confirmationIn(prepared.body()),
                    "end-restricted-key-01");
            assertThat(confirmed.statusCode()).isEqualTo(409);
            assertThat(topLevelFieldsOf(confirmed.body())).containsOnlyKeys("code", "message", "correlationId",
                    "reason").containsEntry("code", "PLAN_END_UNAVAILABLE").containsEntry("reason", "RESTRICTED");
            assertThat(actionGrantConsumed(grant)).isFalse();
            assertThat(planStatus(signed.user())).isEqualTo("ACTIVE");
            assertThat(countOf("plan_end", signed.user())).isZero();
            var again = prepare(signed, "USER_REQUEST", v1);
            assertThat(again.statusCode()).isEqualTo(409);
            assertThat(topLevelFieldsOf(again.body())).containsEntry("code", "PLAN_END_UNAVAILABLE")
                    .containsEntry("reason", "RESTRICTED");
        } finally {
            // Only this test's restriction: nothing else of the table is touched.
            lift(restriction);
        }

        // The plan's end T reached on the database clock.
        Signed late = signedInWithPlan("end-period", Instant.now().minus(Duration.ofDays(5)));
        var l1 = ready(late.user());
        moveVerification(late.user(), l1.calculationId(), "2 days");
        var end = OffsetDateTime.ofInstant(databaseNow().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MILLIS),
                java.time.ZoneOffset.UTC);
        jdbc().sql("UPDATE plan_definition SET end_at=:e WHERE user_id=:u").param("e", end).param("u", late.user())
                .update();
        jdbc().sql("UPDATE sync_plan_calculation SET end_at=:e WHERE user_id=:u").param("e", end)
                .param("u", late.user()).update();
        // Story 2.14: a plan whose T the clock reached is shown completed (not judged yet), not as a ready plan to end.
        var period = (SyncJobApi.Dashboard.Completed) sync.dashboard(late.user()).dashboard();
        assertThat(period.completion().comparison()).isEqualTo("PENDING");
        var reached = prepare(late, "USER_REQUEST", period.result());
        assertThat(reached.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(reached.body())).containsEntry("code", "PLAN_END_UNAVAILABLE")
                .containsEntry("reason", "PLAN_PERIOD_ENDED");
        assertThat(actionGrantCountOf(late.user())).isZero();
    }

    /**
     * B3: an official result whose measurement lies ahead of the database clock (Bybit's time is trusted up to 60
     * seconds from it) is too recent to end on — the dashboard says so with its own reason, and the preparation and a
     * confirmation prepared before refuse with it, the grant unconsumed. It is not a stale reference.
     */
    @Test
    void aResultAheadOfTheClockIsTooRecentToEndOn() throws Exception {
        Signed signed = signedInWithPlan("end-too-recent", null);
        var v1 = ready(signed.user());
        var prepared = prepare(signed, "USER_REQUEST", v1);
        assertThat(prepared.statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());
        jdbc().sql("UPDATE sync_plan_calculation SET current_value_measured_at=statement_timestamp()+interval "
                + "'30 seconds' WHERE calculation_id=:c").param("c", v1.calculationId()).update();

        assertThat(planEndOf(signed.cookie())).isEqualTo(planEndView("UNAVAILABLE", "RESULT_TOO_RECENT"));
        var confirmed = confirm(signed, "USER_REQUEST", v1, confirmationIn(prepared.body()), "end-too-recent-key-01");
        assertThat(confirmed.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(confirmed.body())).containsEntry("code", "PLAN_END_UNAVAILABLE")
                .containsEntry("reason", "RESULT_TOO_RECENT");
        assertThat(actionGrantConsumed(grant)).isFalse();
        var again = prepare(signed, "USER_REQUEST", v1);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(again.body())).containsEntry("code", "PLAN_END_UNAVAILABLE")
                .containsEntry("reason", "RESULT_TOO_RECENT");
        assertThat(planStatus(signed.user())).isEqualTo("ACTIVE");
        assertThat(countOf("plan_end", signed.user())).isZero();
    }

    /**
     * B5: an official result that does not agree with itself by the ready dashboard's own checks — here the portfolio
     * pointer no longer cites the version's snapshot (AD-23) — is not ended on: the preparation and a confirmation
     * prepared before refuse with {@code RESULT_UNAVAILABLE}, the grant unconsumed, so no record that could never be
     * shown is committed.
     */
    @Test
    void anInconsistentOfficialResultIsNotEndedOn() throws Exception {
        Signed signed = signedInWithPlan("end-inconsistent-basis", null);
        var v1 = ready(signed.user());
        var prepared = prepare(signed, "USER_REQUEST", v1);
        assertThat(prepared.statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());
        jdbc().sql("UPDATE sync_plan_calculation SET portfolio_publication_sequence=99 WHERE calculation_id=:c")
                .param("c", v1.calculationId()).update();
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH, signed.cookie()).statusCode()).isEqualTo(503);

        var confirmed = confirm(signed, "USER_REQUEST", v1, confirmationIn(prepared.body()), "end-inconsistent-key01");
        assertThat(confirmed.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(confirmed.body())).containsEntry("code", "PLAN_END_UNAVAILABLE")
                .containsEntry("reason", "RESULT_UNAVAILABLE");
        assertThat(actionGrantConsumed(grant)).isFalse();
        var again = prepare(signed, "USER_REQUEST", v1);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(again.body())).containsEntry("reason", "RESULT_UNAVAILABLE");
        assertThat(planStatus(signed.user())).isEqualTo("ACTIVE");
        assertThat(countOf("plan_end", signed.user())).isZero();
    }

    /**
     * B1: a version held by its own persistent verification review (Story 2.12 — here an official event the precision
     * verification did not see again) keeps that input reliability in its ended record, with no next action; the
     * record does not turn it into "confirmed".
     */
    @Test
    void anEndedRecordKeepsTheVersionsPersistentReview() throws Exception {
        Signed signed = signedInWithPlan("end-reviewed", Instant.now().minus(Duration.ofDays(3)),
                DevBybitStub.Scenario.VANISHING);
        var v1 = ready(signed.user());
        var admission = sync.admitPlanVerification(signed.user(), SyncJobApi.VerificationKind.PRECISION,
                new SyncJobApi.EvidenceReference(v1.planId(), v1.pointerVersion(), v1.calculationId()),
                "end-reviewed-verify-01");
        UUID job = ((SyncJobApi.VerificationAdmission.Accepted) admission).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("NEEDS_REVIEW");
        assertThat(ready(signed.user()).trust().inputReliability().cause()).isEqualTo("VERIFICATION_REVIEW_REQUIRED");

        var ended = endPlan(signed, "USER_REQUEST", v1, "end-reviewed-key-0001");
        var record = (SyncJobApi.Dashboard.Ended) sync.dashboard(signed.user()).dashboard();
        assertThat(record.result().trust().inputReliability().state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(record.result().trust().inputReliability().cause()).isEqualTo("VERIFICATION_REVIEW_REQUIRED");
        var reliability = nested(nested(nested(topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH,
                sessionCookieOf(ended).orElseThrow()).body()), "result"), "trust"), "inputReliability");
        assertThat(reliability).containsEntry("state", "REVIEW_REQUIRED")
                .containsEntry("cause", "VERIFICATION_REVIEW_REQUIRED").containsEntry("nextAction", "NONE");
    }

    /**
     * B2: a cancel from a stale tab invalidates only the grant its own arming holds. Another session that prepared the
     * same plan afterwards keeps its grant and ends the plan.
     */
    @Test
    void aStaleTabsCancelDoesNotKillAnotherSessionsGrant() throws Exception {
        Signed first = signedInWithPlan("end-two-sessions", null);
        var v1 = ready(first.user());
        String cookie = sessionCookieOf(signIn(first.subject())).orElseThrow();
        Signed second = reauthenticate(new Signed(first.user(), first.subject(), cookie, csrfTokenOf(cookie)));
        assertThat(userIdOf(OidcStubConfiguration.issuer(), first.subject())).contains(first.user());

        assertThat(prepare(first, "USER_REQUEST", v1).statusCode()).isEqualTo(200);
        var prepared = prepare(second, "USER_REQUEST", v1);
        assertThat(prepared.statusCode()).isEqualTo(200);
        String live = grantReferenceOf(first.user());
        assertThat(send("POST", CANCEL_PATH, first.cookie(), protectedHeaders(first.csrf(), null)).statusCode())
                .isEqualTo(204);
        assertThat(actionGrantExists(live)).as("the other session's grant").isTrue();
        var ended = confirm(second, "USER_REQUEST", v1, confirmationIn(prepared.body()), "end-two-sessions-key01");
        assertThat(ended.statusCode()).as(ended.body()).isEqualTo(200);
    }

    // ------------------------------------------------------------------------------------------ STALE

    /**
     * AC-3, AC-6: a reference that is not the official pointer is refused at the preparation; a sync that publishes
     * v+1 after the preparation makes the confirmation {@code REFERENCE_STALE} — the plan stays active and the grant
     * unconsumed — and a new preparation with v+1's values ends it.
     */
    @Test
    void aVersionPublishedAfterThePreparationMakesTheConfirmationStale() throws Exception {
        Signed signed = signedInWithPlan("end-stale", null);
        var v1 = ready(signed.user());
        var wrong = prepare(signed, "USER_REQUEST", v1.planId(), 2, v1.calculationId());
        assertThat(wrong.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(wrong.body())).containsEntry("code", "REFERENCE_STALE");

        var prepared = prepare(signed, "USER_REQUEST", v1);
        assertThat(prepared.statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());
        UUID job = manualSync(signed.user(), "end-stale-sync-001");
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        var v2 = ready(signed.user());
        assertThat(v2.pointerVersion()).isEqualTo(2);

        var stale = confirm(signed, "USER_REQUEST", v1, confirmationIn(prepared.body()), "end-stale-key-000001");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(stale.body())).containsEntry("code", "REFERENCE_STALE");
        assertThat(actionGrantConsumed(grant)).isFalse();
        assertThat(planStatus(signed.user())).isEqualTo("ACTIVE");
        assertThat(countOf("plan_end", signed.user())).isZero();

        var fresh = prepare(signed, "USER_REQUEST", v2);
        assertThat(fresh.statusCode()).isEqualTo(200);
        var ended = confirm(signed, "USER_REQUEST", v2, confirmationIn(fresh.body()), "end-stale-key-000002");
        assertThat(ended.statusCode()).as(ended.body()).isEqualTo(200);
        assertThat(endRow(signed.user())).contains("|" + v2.calculationId() + "|2|");
    }

    // ------------------------------------------------------------------------------------------ REPLAY

    /**
     * AC-6: the same key with the same request answers the same end again — after the arming is gone and the session
     * rotated — and the same key with another request is a conflict; there is one end record.
     */
    @Test
    void theSameKeyReplaysTheEndAndAnotherRequestUnderItConflicts() throws Exception {
        Signed signed = signedInWithPlan("end-replay", null);
        var v1 = ready(signed.user());
        String key = "end-replay-key-000001";
        var first = endPlan(signed, "USER_REQUEST", v1, key);
        String rotated = sessionCookieOf(first).orElseThrow();
        Signed after = new Signed(signed.user(), signed.subject(), rotated, csrfTokenOf(rotated));
        String confirmation = "plan-end|USER_REQUEST|" + v1.planId() + "|1|" + v1.calculationId();

        var replay = confirm(after, "USER_REQUEST", v1, confirmation, key);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replay.body())).isEqualTo(topLevelFieldsOf(first.body()));
        assertThat(sessionCookieOf(replay)).as("a replay changes nothing, the session included").isEmpty();

        var conflict = confirm(after, "LONG_GAP_NEW_PLAN", v1,
                "plan-end|LONG_GAP_NEW_PLAN|" + v1.planId() + "|1|" + v1.calculationId(), key);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(conflict.body())).containsEntry("code", "IDEMPOTENCY_CONFLICT");
        assertThat(countOf("plan_end", signed.user())).isEqualTo(1);
    }

    /**
     * AC-6: two confirmations with the same key that meet inside the transaction — the first waits on the plan lock
     * holding the grant, the second waits on the grant — answer the same end: the grant's {@code (user, operation)}
     * decides, and the loser replays the record the winner committed.
     */
    @Test
    void concurrentConfirmationsWithTheSameKeyAnswerTheSameEnd() throws Exception {
        Signed signed = signedInWithPlan("end-concurrent", null);
        var v1 = ready(signed.user());
        var prepared = prepare(signed, "USER_REQUEST", v1);
        String confirmation = confirmationIn(prepared.body());
        String key = "end-concurrent-key-01";
        CompletableFuture<HttpResponse<String>> first;
        CompletableFuture<HttpResponse<String>> second;
        try (AutoCloseable held = holdPlanLock(signed.user())) {
            first = async(() -> confirm(signed, "USER_REQUEST", v1, confirmation, key));
            awaitPlanLockWaiters(signed.user(), 1);
            second = async(() -> confirm(signed, "USER_REQUEST", v1, confirmation, key));
            awaitRowLockWaiter(signed.user());
        }
        var a = first.get();
        var b = second.get();
        assertThat(List.of(a.statusCode(), b.statusCode())).containsExactly(200, 200);
        assertThat(topLevelFieldsOf(b.body())).isEqualTo(topLevelFieldsOf(a.body()));
        assertThat(countOf("plan_end", signed.user())).isEqualTo(1);
        assertThat(planStatus(signed.user())).isEqualTo("ENDED_EARLY");
    }

    // ------------------------------------------------------------------------------------------ TWO_TABS

    /** AC-6: the same arming confirmed with two keys ends the plan once; the other key has nothing to replay (403). */
    @Test
    void twoTabsConfirmingOneArmingWithTwoKeysEndThePlanOnce() throws Exception {
        Signed signed = signedInWithPlan("end-tabs", null);
        var v1 = ready(signed.user());
        var prepared = prepare(signed, "USER_REQUEST", v1);
        String confirmation = confirmationIn(prepared.body());
        CompletableFuture<HttpResponse<String>> first;
        CompletableFuture<HttpResponse<String>> second;
        try (AutoCloseable held = holdPlanLock(signed.user())) {
            first = async(() -> confirm(signed, "USER_REQUEST", v1, confirmation, "end-tabs-key-00000001"));
            awaitPlanLockWaiters(signed.user(), 1);
            second = async(() -> confirm(signed, "USER_REQUEST", v1, confirmation, "end-tabs-key-00000002"));
            awaitRowLockWaiter(signed.user());
        }
        var a = first.get();
        var b = second.get();
        assertThat(a.statusCode()).as(a.body()).isEqualTo(200);
        assertThat(b.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(b.body())).containsEntry("code", "PLAN_END_NOT_CONFIRMED");
        assertThat(countOf("plan_end", signed.user())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ GRANT_MISUSE

    /**
     * AC-3: an expired grant, a grant of another command, a confirmation phrase that is not the one prepared, another
     * plan in the body, no arming at all and a second confirmation after the first are one refusal —
     * {@code PLAN_END_NOT_CONFIRMED} — and the plan and the grant stay as they were.
     */
    @Test
    void aMisusedGrantIsOneRefusalAndChangesNothing() throws Exception {
        Signed signed = signedInWithPlan("end-misuse", null);
        var v1 = ready(signed.user());
        String confirmation = "plan-end|USER_REQUEST|" + v1.planId() + "|1|" + v1.calculationId();

        // No arming at all.
        assertNotConfirmed(confirm(signed, "USER_REQUEST", v1, confirmation, "end-misuse-key-000001"), signed);
        // A phrase that is not the prepared one.
        assertThat(prepare(signed, "USER_REQUEST", v1).statusCode()).isEqualTo(200);
        String grant = grantReferenceOf(signed.user());
        assertNotConfirmed(confirm(signed, "USER_REQUEST", v1, confirmation.replace("|1|", "|2|"),
                "end-misuse-key-000002"), signed);
        // Another plan in the body.
        assertNotConfirmed(confirm(signed, "USER_REQUEST", UUID.randomUUID(), 1, v1.calculationId(), confirmation,
                "end-misuse-key-000003"), signed);
        assertThat(actionGrantConsumed(grant)).isFalse();
        // A grant of another command.
        jdbc().sql("UPDATE action_grant SET command='bybit-connection-create' WHERE grant_id=:g").param("g", grant)
                .update();
        assertNotConfirmed(confirm(signed, "USER_REQUEST", v1, confirmation, "end-misuse-key-000004"), signed);
        // An expired grant.
        assertThat(prepare(signed, "USER_REQUEST", v1).statusCode()).isEqualTo(200);
        String expired = grantReferenceOf(signed.user());
        moveActionGrantExpiry(expired, Duration.ofSeconds(-1));
        assertNotConfirmed(confirm(signed, "USER_REQUEST", v1, confirmation, "end-misuse-key-000005"), signed);
        assertThat(actionGrantConsumed(expired)).isFalse();
        // Reused: the first confirmation ends the plan, a second one with another key finds nothing.
        assertThat(prepare(signed, "USER_REQUEST", v1).statusCode()).isEqualTo(200);
        var first = confirm(signed, "USER_REQUEST", v1, confirmation, "end-misuse-key-000006");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        String rotated = sessionCookieOf(first).orElseThrow();
        Signed after = new Signed(signed.user(), signed.subject(), rotated, csrfTokenOf(rotated));
        var reused = confirm(after, "USER_REQUEST", v1, confirmation, "end-misuse-key-000007");
        assertThat(reused.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(reused.body())).containsEntry("code", "PLAN_END_NOT_CONFIRMED");
        assertThat(countOf("plan_end", signed.user())).isEqualTo(1);
    }

    /** The cancel drops the arming and its grant; a confirmation after it is refused. Always 204. */
    @Test
    void theCancelDropsTheArmingAndItsGrant() throws Exception {
        Signed signed = signedInWithPlan("end-cancel", null);
        var v1 = ready(signed.user());
        var prepared = prepare(signed, "USER_REQUEST", v1);
        String grant = grantReferenceOf(signed.user());
        var cancelled = send("POST", CANCEL_PATH, signed.cookie(), protectedHeaders(signed.csrf(), null));
        assertThat(cancelled.statusCode()).isEqualTo(204);
        assertThat(cancelled.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(actionGrantExists(grant)).isFalse();
        assertNotConfirmed(confirm(signed, "USER_REQUEST", v1, confirmationIn(prepared.body()),
                "end-cancel-key-0000001"), signed);
        assertThat(send("POST", CANCEL_PATH, signed.cookie(), protectedHeaders(signed.csrf(), null)).statusCode())
                .as("nothing armed: still 204").isEqualTo(204);
    }

    // ------------------------------------------------------------------------------------------ HTTP shape

    /**
     * The dashboard path's predicate guards all three: without a session 401, without the CSRF token or with another
     * origin 403 — nothing issued, nothing ended; a body that is not the shape is 400 and a missing or malformed key 422.
     */
    @Test
    void theThreeWritesAreGuardedAndReadTheirBodiesStrictly() throws Exception {
        Signed signed = signedInWithPlan("end-guard", null);
        var v1 = ready(signed.user());
        String body = prepareBody("USER_REQUEST", v1.planId(), 1, v1.calculationId());

        // Without a session there is no CSRF token either: the predicate refuses before anything is read.
        assertThat(sendJson("POST", PLAN_END_PATH, body, null, "Origin", OidcStubConfiguration.PUBLIC_BASE_URL)
                .statusCode()).as("no session").isIn(401, 403);
        assertThat(sendJson("POST", PLAN_END_PATH, body, signed.cookie(), "Origin",
                OidcStubConfiguration.PUBLIC_BASE_URL).statusCode()).isEqualTo(403);
        assertThat(sendJson("POST", PLAN_END_PATH, body, signed.cookie(), "Origin", "https://elsewhere.example",
                AuthSecurityConfig.CSRF_HEADER_NAME, signed.csrf()).statusCode()).isEqualTo(403);
        assertThat(send("POST", CANCEL_PATH, signed.cookie(), "Origin", OidcStubConfiguration.PUBLIC_BASE_URL)
                .statusCode()).isEqualTo(403);
        assertThat(actionGrantCountOf(signed.user())).isZero();

        for (String malformed : List.of("", "[]", "{\"reason\":\"USER_REQUEST\"}",
                prepareBody("COMPLETED", v1.planId(), 1, v1.calculationId()),
                "{\"reason\":\"USER_REQUEST\",\"planId\":\"" + v1.planId() + "\",\"pointerVersion\":0,\"calculationId\":\""
                        + v1.calculationId() + "\"}",
                "{\"reason\":\"USER_REQUEST\",\"planId\":\"1-1-1-1-1\",\"pointerVersion\":1,\"calculationId\":\""
                        + v1.calculationId() + "\"}")) {
            var response = sendJson("POST", PLAN_END_PATH, malformed, signed.cookie(), protectedHeaders(signed.csrf(),
                    null));
            assertThat(response.statusCode()).as(malformed).isEqualTo(400);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
        assertThat(sendJson("POST", CONFIRM_PATH, body, signed.cookie(), protectedHeaders(signed.csrf(),
                "end-guard-key-0000001")).statusCode()).as("no confirmation").isEqualTo(400);
        String confirmBody = confirmBody("USER_REQUEST", v1.planId(), 1, v1.calculationId(), "plan-end|x");
        assertThat(sendJson("POST", CONFIRM_PATH, confirmBody, signed.cookie(), protectedHeaders(signed.csrf(), null))
                .statusCode()).isEqualTo(422);
        var shortKey = sendJson("POST", CONFIRM_PATH, confirmBody, signed.cookie(), protectedHeaders(signed.csrf(),
                "short"));
        assertThat(shortKey.statusCode()).isEqualTo(422);
        // 모든 엄격 키 경로가 같은 code와 같은 메시지 한 문장으로 답한다(`ApiErrorHandler`).
        assertThat(topLevelFieldsOf(shortKey.body())).containsEntry("code", "IDEMPOTENCY_KEY_REQUIRED")
                .containsEntry("message", ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED);
        assertThat(planStatus(signed.user())).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------------------------------ LATE_WORKER

    /**
     * AC-6: the plan lock serializes a sync's publication and the end, both ways. A sync already collecting when the
     * end commits stops at its next step with {@code PLAN_CHANGED} and creates no pointer, version or snapshot; a sync
     * that publishes first moves the pointer, and the end that waited behind it is {@code REFERENCE_STALE}.
     */
    @Test
    void publicationAndEndAreSerializedByThePlanLockEitherWay() throws Exception {
        Signed signed = signedInWithPlan("end-late-worker", null);
        var v1 = ready(signed.user());
        slowPageStores(signed.user());
        UUID late;
        try {
            late = manualSync(signed.user(), "end-late-worker-sync-1");
            awaitStage(late, "COLLECTION");
            var ended = endPlan(signed, "USER_REQUEST", v1, "end-late-worker-key-01");
            assertThat(ended.statusCode()).as(ended.body()).isEqualTo(200);
        } finally {
            dropSlowPageStores();
        }
        assertThat(awaitSync(late)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", late)
                .query(String.class).single()).isEqualTo("PLAN_CHANGED");
        assertThat(countOf("sync_plan_calculation", signed.user())).isEqualTo(1);
        assertThat(countOf("sync_official_plan_result_history", signed.user())).isEqualTo(1);
        assertThat(countOf("portfolio_snapshot", signed.user())).isEqualTo(2);
        assertThat(jdbc().sql("SELECT pointer_version FROM sync_official_plan_result WHERE user_id=:u")
                .param("u", signed.user()).query(Long.class).single()).isEqualTo(1);
        assertNoChildInProgress(late);
        assertThat(sync.dashboard(signed.user()).dashboard()).isInstanceOf(SyncJobApi.Dashboard.Ended.class);

        // The other way: the sync waits to publish, the end waits behind it, the sync publishes first.
        Signed other = signedInWithPlan("end-publish-first", null);
        var o1 = ready(other.user());
        var prepared = prepare(other, "USER_REQUEST", o1);
        String grant = grantReferenceOf(other.user());
        UUID first;
        CompletableFuture<HttpResponse<String>> waiting;
        try (AutoCloseable held = holdPlanLock(other.user())) {
            first = manualSync(other.user(), "end-publish-first-sync");
            awaitPlanLockWaiters(other.user(), 1);
            waiting = async(() -> confirm(other, "USER_REQUEST", o1, confirmationIn(prepared.body()),
                    "end-publish-first-key1"));
            awaitPlanLockWaiters(other.user(), 2);
        }
        assertThat(awaitSync(first)).isEqualTo("SUCCEEDED");
        var stale = waiting.get();
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(stale.body())).containsEntry("code", "REFERENCE_STALE");
        assertThat(actionGrantConsumed(grant)).isFalse();
        assertThat(planStatus(other.user())).isEqualTo("ACTIVE");
        assertThat(ready(other.user()).pointerVersion()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------ ROLLBACK

    /**
     * AC-5: a failure at any write of the transaction — the status, the end record, the grant's stored result — rolls
     * everything back: the plan stays active, no record, the grant unconsumed, the inputs and versions unchanged, a 500
     * in the common shape. Once the cause is gone the same key ends the plan.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"STATUS", "RECORD", "RESULT"})
    void aFailureAtAnyWriteRollsTheWholeEndBack(String step) throws Exception {
        Signed signed = signedInWithPlan("end-rollback-" + step.toLowerCase(java.util.Locale.ROOT), null);
        var v1 = ready(signed.user());
        var before = snapshotOf(signed.user());
        var prepared = prepare(signed, "USER_REQUEST", v1);
        String grant = grantReferenceOf(signed.user());
        String key = "end-rollback-key-" + step.toLowerCase(java.util.Locale.ROOT) + "-01";
        String table = switch (step) {
            case "STATUS" -> "plan_definition";
            case "RECORD" -> "plan_end";
            default -> "action_grant";
        };
        String when = switch (step) {
            case "STATUS" -> "BEFORE UPDATE";
            case "RECORD" -> "BEFORE INSERT";
            default -> "BEFORE UPDATE";
        };
        String condition = step.equals("RESULT") ? "NEW.result IS NOT NULL" : "NEW.user_id='" + signed.user() + "'::uuid";
        jdbc().sql("CREATE OR REPLACE FUNCTION fail_plan_end() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF " + condition + " THEN RAISE EXCEPTION 'injected'; END IF; RETURN NEW; END $$").update();
        jdbc().sql("CREATE OR REPLACE TRIGGER fail_plan_end " + when + " ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION fail_plan_end()").update();
        HttpResponse<String> failed;
        try {
            failed = confirm(signed, "USER_REQUEST", v1, confirmationIn(prepared.body()), key);
        } finally {
            jdbc().sql("DROP TRIGGER IF EXISTS fail_plan_end ON " + table).update();
            jdbc().sql("DROP FUNCTION IF EXISTS fail_plan_end()").update();
        }
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(topLevelFieldsOf(failed.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", "INTERNAL_ERROR");
        assertThat(failed.body()).doesNotContain("injected");
        assertThat(planStatus(signed.user())).isEqualTo("ACTIVE");
        assertThat(countOf("plan_end", signed.user())).isZero();
        assertThat(actionGrantConsumed(grant)).isFalse();
        assertThat(snapshotOf(signed.user())).isEqualTo(before);

        var retried = confirm(signed, "USER_REQUEST", v1, confirmationIn(prepared.body()), key);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        assertThat(planStatus(signed.user())).isEqualTo("ENDED_EARLY");
    }

    // ------------------------------------------------------------------------------------------ ENDED consistency

    /**
     * AC-7: the record is shown only when the end record agrees with the plan's pointer and the version it cites — a
     * disagreement is 503 for the dashboard, the evidence and the history alike, never a partial record.
     */
    @Test
    void anEndRecordThatDisagreesWithItsVersionIsNotShownInPart() throws Exception {
        Signed signed = signedInWithPlan("end-inconsistent", null);
        var v1 = ready(signed.user());
        var ended = endPlan(signed, "USER_REQUEST", v1, "end-inconsistent-key1");
        String cookie = sessionCookieOf(ended).orElseThrow();
        String reference = "?planId=" + v1.planId() + "&pointerVersion=1&calculationId=" + v1.calculationId();
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH, cookie).statusCode()).isEqualTo(200);

        jdbc().sql("UPDATE plan_end SET last_measured_at=last_measured_at-interval '1 millisecond' WHERE user_id=:u")
                .param("u", signed.user()).update();
        var dashboard = get(AuthSecurityConfig.DASHBOARD_PATH, cookie);
        assertThat(dashboard.statusCode()).isEqualTo(503);
        assertThat(topLevelFieldsOf(dashboard.body())).containsEntry("code", "DASHBOARD_UNAVAILABLE");
        var evidence = get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence" + reference, cookie);
        assertThat(evidence.statusCode()).isEqualTo(503);
        assertThat(topLevelFieldsOf(evidence.body())).containsEntry("code", "EVIDENCE_UNAVAILABLE");
        var history = get(AuthSecurityConfig.DASHBOARD_PATH + "/adjustments" + reference, cookie);
        assertThat(history.statusCode()).isEqualTo(503);
        assertThat(topLevelFieldsOf(history.body())).containsEntry("code", "ADJUSTMENT_HISTORY_UNAVAILABLE");

        // An ended plan without its record is not a record either.
        jdbc().sql("DELETE FROM plan_end WHERE user_id=:u").param("u", signed.user()).update();
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH, cookie).statusCode()).isEqualTo(503);
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence" + reference, cookie).statusCode())
                .isEqualTo(503);
        var unrecordedHistory = get(AuthSecurityConfig.DASHBOARD_PATH + "/adjustments" + reference, cookie);
        assertThat(unrecordedHistory.statusCode()).isEqualTo(503);
        assertThat(topLevelFieldsOf(unrecordedHistory.body())).containsEntry("code", "ADJUSTMENT_HISTORY_UNAVAILABLE");
        // Another reference is still the same 404 as ever.
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence?planId=" + UUID.randomUUID()
                + "&pointerVersion=1&calculationId=" + v1.calculationId(), cookie).statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------------------------------ PLAN_ENDED

    /**
     * Until Story 2.15 opens the following plan, a user whose plan ended takes no new plan conditions: the state, the
     * preview and the confirmation all answer {@code PLAN_ENDED}, so the ended plan's P0 and t0 never start a new one.
     */
    @Test
    void anEndedPlanClosesThePlanConditions() throws Exception {
        Signed signed = signedInWithPlan("end-conditions", null);
        var v1 = ready(signed.user());
        var ended = endPlan(signed, "USER_REQUEST", v1, "end-conditions-key-001");
        String cookie = sessionCookieOf(ended).orElseThrow();

        var state = plans.state(signed.user());
        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo(PlanConditionApi.PLAN_ENDED);
        assertThat(state.activePlan()).isNull();
        var http = topLevelFieldsOf(get(AuthSecurityConfig.PLAN_CONDITIONS_PATH, cookie).body());
        assertThat(http).containsEntry("eligible", false).containsEntry("reason", "PLAN_ENDED");
        var preview = sendJson("POST", AuthSecurityConfig.PLAN_CONDITIONS_PATH + "/preview",
                "{\"endAtSeoul\":\"2099-01-01T00:00:00\",\"period\":\"DAILY\",\"targetRate\":\"0.1\"}", cookie,
                protectedHeaders(csrfTokenOf(cookie), null));
        assertThat(preview.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(preview.body())).containsEntry("code", "PLAN_ENDED");
        assertThat(plans.preview(signed.user(), "end-conditions-session", new PlanConditionApi.PreviewInput(
                "2099-01-01T00:00:00", "DAILY", "0.1"))).isEmpty();
        assertThat(plans.confirm(signed.user(), "end-conditions-session", "unknown-ref", "end-conditions-confirm-1")
                .reason()).isEqualTo(PlanConditionApi.PLAN_ENDED);
        String[] headers = protectedHeaders(csrfTokenOf(cookie), "end-conditions-confirm-2");
        var confirm = sendJson("POST", AuthSecurityConfig.PLAN_CONDITIONS_PATH + "/confirm",
                "{\"confirmationRef\":\"unknown-ref\"}", cookie, headers);
        assertThat(confirm.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(confirm.body())).containsEntry("code", "PLAN_ENDED");
        assertThat(countOf("plan_condition_intent", signed.user())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ DELETION

    /**
     * AC-8: a deletion that began wins over a confirmation (403, nothing changes); the erasure removes the ended plan,
     * its end record and its official result, the grants with the account, and keeps another user's.
     */
    @Test
    void erasureRemovesTheEndedPlanAndADeletionWinsOverTheConfirmation() throws Exception {
        Signed kept = signedInWithPlan("end-kept", null);
        endPlan(kept, "USER_REQUEST", ready(kept.user()), "end-kept-key-00000001");

        Signed deleting = signedInWithPlan("end-deleting", null);
        var d1 = ready(deleting.user());
        var prepared = prepare(deleting, "USER_REQUEST", d1);
        accountDeletion().beginDeletion(deleting.user());
        var refused = confirm(deleting, "USER_REQUEST", d1, confirmationIn(prepared.body()), "end-deleting-key-0001");
        assertThat(refused.statusCode()).isIn(401, 403);
        assertThat(planStatus(deleting.user())).isEqualTo("ACTIVE");
        assertThat(countOf("plan_end", deleting.user())).isZero();

        Signed erased = signedInWithPlan("end-erased", null);
        endPlan(erased, "USER_REQUEST", ready(erased.user()), "end-erased-key-000001");
        assertThat(actionGrantCountOf(erased.user())).isEqualTo(1);
        accountDeletion().beginDeletion(erased.user());
        portfolio.erasePortfolioData(erased.user());
        sync.eraseSyncData(erased.user());
        plans.erasePlanData(erased.user());
        source.eraseSourceData(erased.user());
        accountDeletion().erasePayload(erased.user());
        for (String table : List.of("plan_end", "plan_definition", "sync_plan_calculation", "sync_official_plan_result",
                "action_grant")) {
            assertThat(countOf(table, erased.user())).as(table).isZero();
        }
        assertThat(countOf("plan_end", kept.user())).isEqualTo(1);
        assertThat(planStatus(kept.user())).isEqualTo("ENDED_EARLY");
        assertThat(sync.dashboard(kept.user()).dashboard()).isInstanceOf(SyncJobApi.Dashboard.Ended.class);
    }

    // ------------------------------------------------------------------------------------------ V13

    /** V13 replaces only the plan status check (widened by ENDED_EARLY); every other plan check stays as V12 left it. */
    @Test
    void v13KeepsEveryPlanCheckItDoesNotWiden() throws Exception {
        String probe = "v12_probe_" + UUID.randomUUID().toString().replace("-", "");
        jdbc().sql("CREATE DATABASE " + probe).update();
        try {
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + probe;
            org.flywaydb.core.Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("12").load().migrate();
            Map<String, String> v12 = new java.util.TreeMap<>();
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(),
                    postgres.getPassword()); var statement = connection.createStatement();
                    var rows = statement.executeQuery(CHECKS)) {
                while (rows.next()) v12.put(rows.getString(1), rows.getString(2));
            }
            Map<String, String> v13 = new java.util.TreeMap<>();
            jdbc().sql(CHECKS).query((rs, n) -> v13.put(rs.getString(1), rs.getString(2))).list();
            assertThat(v12).containsKey("plan_definition_status_check").hasSizeGreaterThan(1);
            for (var check : v12.entrySet())
                if (!check.getKey().equals("plan_definition_status_check"))
                    assertThat(v13).as(check.getKey()).containsEntry(check.getKey(), check.getValue());
            var expectedChecks=new java.util.HashSet<>(v12.keySet());
            expectedChecks.add("plan_successor_pair"); // V15 expands lineage without widening V12 checks.
            assertThat(v13).containsOnlyKeys(expectedChecks);
            assertThat(v13.get("plan_definition_status_check")).contains("'ACTIVE'").contains("'ENDED_EARLY'");
            assertThat(v12.get("plan_definition_status_check")).doesNotContain("ENDED_EARLY");
        } finally {
            jdbc().sql("DROP DATABASE IF EXISTS " + probe + " WITH (FORCE)").update();
        }
    }

    /** 공유 컨테이너. bean이 아니므로 주입받지 않는다({@link PostgresTestcontainersConfig#shared()}). */
    private final org.testcontainers.postgresql.PostgreSQLContainer postgres = PostgresTestcontainersConfig.shared();

    /**
     * B6: the schema pairs an end record with its ended plan — a record cannot cite an active plan, and a plan cannot
     * go back to ACTIVE while its record exists.
     */
    @Test
    void theSchemaPairsAnEndRecordWithItsEndedPlan() throws Exception {
        Signed signed = signedInWithPlan("end-schema", null);
        var v1 = ready(signed.user());
        assertThatSqlFails(() -> jdbc().sql("INSERT INTO plan_end(plan_id,user_id,reason,ended_at,official_calculation_id,"
                + "official_pointer_version,official_portfolio_snapshot_id,last_measured_at,cashflow_verified_through,"
                + "completion,idempotency_key,request_hash) VALUES (:p,:u,'USER_REQUEST',date_trunc('milliseconds',"
                + "statement_timestamp()),:c,1,:s,:m,:v,'NOT_APPLICABLE','end-schema-key-000001',:h)")
                .param("p", v1.planId()).param("u", signed.user()).param("c", v1.calculationId())
                .param("s", v1.portfolioSnapshotId())
                .param("m", OffsetDateTime.ofInstant(v1.measuredAt(), java.time.ZoneOffset.UTC))
                .param("v", OffsetDateTime.ofInstant(v1.cashflowVerifiedThrough(), java.time.ZoneOffset.UTC))
                .param("h", "0".repeat(64)).update());
        endPlan(signed, "USER_REQUEST", v1, "end-schema-key-000002");
        assertThatSqlFails(() -> jdbc().sql("UPDATE plan_definition SET status='ACTIVE' WHERE user_id=:u")
                .param("u", signed.user()).update());
        assertThat(planStatus(signed.user())).isEqualTo("ENDED_EARLY");
        assertThat(jdbc().sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='plan_end_plan_fk'")
                .query(String.class).single())
                .isEqualTo("FOREIGN KEY (plan_id, user_id, status) REFERENCES plan_definition(plan_id, user_id, status) "
                        + "ON DELETE RESTRICT");
    }

    private static void assertThatSqlFails(Runnable statement) {
        org.assertj.core.api.Assertions.assertThatThrownBy(statement::run)
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private static final String CHECKS = "SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint "
            + "WHERE conrelid='plan_definition'::regclass AND contype='c' ORDER BY conname";

    // ------------------------------------------------------------------------------------------ helpers

    /** A signed-in user with a fresh reauthentication: the session cookie, its CSRF token and the user. */
    record Signed(UUID user, String subject, String cookie, String csrf) {}

    /** Signs in, connects, publishes a first version and reauthenticates — the end's precondition. */
    private Signed signedInWithPlan(String prefix, @Nullable Instant start) throws Exception {
        return signedInWithPlan(prefix, start, DevBybitStub.Scenario.UTA2);
    }

    private Signed signedInWithPlan(String prefix, @Nullable Instant start, DevBybitStub.Scenario scenario)
            throws Exception {
        String subject = prefix + "-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, scenario);
        publishedPlan(user, prefix, start);
        return reauthenticate(new Signed(user, subject, cookie, csrfTokenOf(cookie)));
    }

    private Signed reauthenticate(Signed signed) throws Exception {
        var reauthenticated = reauthenticate(GOOGLE, signed.cookie(), signed.csrf(),
                plan -> plan.subject(signed.subject()));
        String rotated = sessionCookieOf(reauthenticated).orElseThrow();
        return new Signed(signed.user(), signed.subject(), rotated, csrfTokenOf(rotated));
    }

    private HttpResponse<String> prepare(Signed signed, String reason, SyncJobApi.DashboardResult version)
            throws Exception {
        return prepare(signed, reason, version.planId(), version.pointerVersion(), version.calculationId());
    }

    private HttpResponse<String> prepare(Signed signed, String reason, UUID planId, long pointerVersion,
            UUID calculationId) throws Exception {
        return sendJson("POST", PLAN_END_PATH, prepareBody(reason, planId, pointerVersion, calculationId),
                signed.cookie(), protectedHeaders(signed.csrf(), null));
    }

    private HttpResponse<String> confirm(Signed signed, String reason, SyncJobApi.DashboardResult version,
            String confirmation, String key) throws Exception {
        return confirm(signed, reason, version.planId(), version.pointerVersion(), version.calculationId(),
                confirmation, key);
    }

    private HttpResponse<String> confirm(Signed signed, String reason, UUID planId, long pointerVersion,
            UUID calculationId, String confirmation, String key) throws Exception {
        return sendJson("POST", CONFIRM_PATH, confirmBody(reason, planId, pointerVersion, calculationId, confirmation),
                signed.cookie(), protectedHeaders(signed.csrf(), key));
    }

    /** Prepares and confirms; both must succeed. */
    private HttpResponse<String> endPlan(Signed signed, String reason, SyncJobApi.DashboardResult version, String key)
            throws Exception {
        var prepared = prepare(signed, reason, version);
        if (prepared.statusCode() != 200)
            throw new IllegalStateException("prepare: " + prepared.statusCode() + " " + prepared.body());
        var confirmed = confirm(signed, reason, version, confirmationIn(prepared.body()), key);
        if (confirmed.statusCode() != 200)
            throw new IllegalStateException("confirm: " + confirmed.statusCode() + " " + confirmed.body());
        return confirmed;
    }

    private static String prepareBody(String reason, UUID planId, long pointerVersion, UUID calculationId) {
        return "{\"reason\":\"" + reason + "\",\"planId\":\"" + planId + "\",\"pointerVersion\":" + pointerVersion
                + ",\"calculationId\":\"" + calculationId + "\"}";
    }

    private static String confirmBody(String reason, UUID planId, long pointerVersion, UUID calculationId,
            String confirmation) {
        return "{\"reason\":\"" + reason + "\",\"planId\":\"" + planId + "\",\"pointerVersion\":" + pointerVersion
                + ",\"calculationId\":\"" + calculationId + "\",\"confirmation\":\"" + confirmation + "\"}";
    }

    /** The same-origin headers with the CSRF token, and the idempotency key when given. */
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

    private void assertNotConfirmed(HttpResponse<String> response, Signed signed) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(topLevelFieldsOf(response.body())).containsOnlyKeys("code", "message", "correlationId")
                .containsEntry("code", "PLAN_END_NOT_CONFIRMED");
        assertThat(planStatus(signed.user())).isEqualTo("ACTIVE");
        assertThat(countOf("plan_end", signed.user())).isZero();
    }

    private static Map<String, Object> withoutCorrelation(String body) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>(topLevelFieldsOf(body));
        fields.remove("correlationId");
        return fields;
    }

    private String grantReferenceOf(UUID user) {
        return jdbc().sql("SELECT grant_id FROM action_grant WHERE user_id=:u AND command='plan-end' "
                + "AND consumed_at IS NULL")
                .param("u", user).query(String.class).single();
    }

    private String planStatus(UUID user) {
        return jdbc().sql("SELECT status FROM plan_definition WHERE user_id=:u").param("u", user).query(String.class)
                .single();
    }

    /** The end record as one line, nullable columns as {@code -}. */
    private String endRow(UUID user) {
        return jdbc().sql("SELECT concat_ws('|',plan_id,reason,to_char(ended_at AT TIME ZONE 'UTC',"
                + "'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'),official_calculation_id,official_pointer_version,"
                + "official_portfolio_snapshot_id,last_measured_at,cashflow_verified_through,completion,"
                + "COALESCE(uncollected_from::text,'-'),COALESCE(uncollected_until::text,'-'),idempotency_key,"
                + "request_hash) FROM plan_end WHERE user_id=:u")
                .param("u", user).query((rs, n) -> normalize(rs.getString(1))).single();
    }

    /** Rewrites the timestamps of a {@code concat_ws} line as {@link Instant#toString}, so lines compare with views. */
    private String normalize(String line) {
        List<String> parts = new ArrayList<>();
        for (String part : line.split("\\|", -1)) {
            if (part.matches("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}.*")) {
                parts.add(jdbc().sql("SELECT CAST(:t AS timestamptz)").param("t", part).query(OffsetDateTime.class)
                        .single().toInstant().toString());
            } else {
                parts.add(part);
            }
        }
        return String.join("|", parts);
    }

    /** Everything the end must leave alone: the plan's terms, the inputs, the versions, the pointers, the flows. */
    record Snapshot(String plan, int intents, int calculations, int metrics, int flows, int history, String pointer,
            int portfolioSnapshots, int sourceEvents, int candidates, int classifications, int conversions) {
        Snapshot withStatus(String status) {
            return new Snapshot(this.plan.replace("|ACTIVE|", "|" + status + "|"), this.intents, this.calculations,
                    this.metrics, this.flows, this.history, this.pointer, this.portfolioSnapshots, this.sourceEvents,
                    this.candidates, this.classifications, this.conversions);
        }
    }

    private Snapshot snapshotOf(UUID user) {
        return new Snapshot(planRow(user), countOf("plan_condition_intent", user),
                countOf("sync_plan_calculation", user),
                jdbc().sql("SELECT count(*) FROM sync_plan_calculation_metric m JOIN sync_plan_calculation c "
                        + "ON c.calculation_id=m.calculation_id WHERE c.user_id=:u").param("u", user)
                        .query(Integer.class).single(),
                jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow f JOIN sync_plan_calculation c "
                        + "ON c.calculation_id=f.calculation_id WHERE c.user_id=:u").param("u", user)
                        .query(Integer.class).single(),
                countOf("sync_official_plan_result_history", user),
                jdbc().sql("SELECT calculation_id||'|'||pointer_version||'|'||published_at FROM sync_official_plan_result "
                        + "WHERE user_id=:u").param("u", user).query(String.class).single(),
                countOf("portfolio_snapshot", user), countOf("sync_initial_source_event", user),
                countOf("sync_initial_candidate_bundle", user), countOf("sync_cashflow_classification_bundle", user),
                countOf("sync_cashflow_conversion_bundle", user));
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
        jdbc().sql("CREATE OR REPLACE FUNCTION slow_plan_end_pages() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.user_id='" + user + "'::uuid THEN PERFORM pg_sleep(1.5); END IF; RETURN NEW; END $$").update();
        jdbc().sql("CREATE OR REPLACE TRIGGER slow_plan_end_pages BEFORE INSERT ON sync_initial_page_evidence "
                + "FOR EACH ROW EXECUTE FUNCTION slow_plan_end_pages()").update();
    }

    private void dropSlowPageStores() {
        jdbc().sql("DROP TRIGGER IF EXISTS slow_plan_end_pages ON sync_initial_page_evidence").update();
        jdbc().sql("DROP FUNCTION IF EXISTS slow_plan_end_pages()").update();
    }

    /**
     * The not-granted locks on this user's plan lock: the advisory key {@code hashtextextended(user, 0)}, which
     * {@code pg_locks} shows as its high and low 32 bits ({@code classid}, {@code objid}, {@code objsubid = 1}). The
     * bits are put back together with a shift and an or (a bigint shift does not check overflow, so the sign comes
     * back too); {@code &} is not used because the named-parameter parser reads it as a parameter prefix.
     */
    private static final String PLAN_LOCK_WAITERS = "SELECT pid FROM pg_locks WHERE locktype='advisory' AND NOT granted "
            + "AND objsubid=1 AND ((classid::bigint<<32)|objid::bigint)=hashtextextended(:u,0)";

    /**
     * Waits until a transaction waits on the row of a transaction that itself waits on this user's plan lock: the second
     * confirmation, waiting on the grant the first one consumed before it reached the plan lock.
     */
    private void awaitRowLockWaiter(UUID user) throws InterruptedException {
        int waiting = pollUntil(Duration.ofSeconds(10), () -> jdbc().sql("SELECT count(*) FROM pg_locks w "
                + "JOIN pg_locks h ON h.locktype='transactionid' AND h.granted AND h.transactionid=w.transactionid "
                + "WHERE w.locktype='transactionid' AND NOT w.granted AND h.pid IN (" + PLAN_LOCK_WAITERS + ")")
                .param("u", user.toString()).query(Integer.class).single(), count -> count > 0);
        if (waiting == 0) throw new AssertionError("the second confirmation never waited on the grant");
    }

    /** Waits until at least {@code count} transactions wait on this user's plan lock. */
    private void awaitPlanLockWaiters(UUID user, int count) throws InterruptedException {
        int waiting = pollUntil(Duration.ofSeconds(10), () -> jdbc().sql("SELECT count(*) FROM (" + PLAN_LOCK_WAITERS
                + ") w").param("u", user.toString()).query(Integer.class).single(), found -> found >= count);
        if (waiting < count) throw new AssertionError("only " + waiting + " waited on the plan lock");
    }

    /** The {@code planEnd} block of the HTTP dashboard. */
    private Map<String, Object> planEndOf(String cookie) throws Exception {
        var response = get(AuthSecurityConfig.DASHBOARD_PATH, cookie);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return nested(topLevelFieldsOf(response.body()), "planEnd");
    }

    private static Map<String, Object> planEndView(String availability, @Nullable String reason) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("availability", availability);
        view.put("reason", reason);
        return view;
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> fields, String name) {
        return (List<Map<String, Object>>) fields.get(name);
    }
}
