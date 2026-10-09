package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.sync.SyncJobApi;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Story 2.12: the precision verification and the long-gap continuation of an active plan, end to end on the real stack
 * (Docker PostgreSQL, the worker, the local Bybit stub). Each test publishes a first official version (Story 2.7) and
 * then verifies or continues it: the admission and its reasons, the planned and reused ranges (asserted from the
 * provider's own query log and the stored plan), the reconciliation's five outcomes with the source facts untouched,
 * late events and price revisions republished as the next version with their change record, what stays held when an
 * anomaly is not resolved, the continuation's final sweep, a resumed collection, the call budget, the races and the
 * erasure.
 */
// The plan lock is held by a resource the body never names: holding it is the point.
@SuppressWarnings("try")
class PlanVerificationTest extends PlanSyncTestBase {
    private static final String VERIFICATION_PATH = AuthSecurityConfig.DASHBOARD_PATH + "/verification";
    private static final String SYNC_PATH = AuthSecurityConfig.DASHBOARD_PATH + "/sync";

    // ------------------------------------------------------------------------------------------ PRECISION_NO_CHANGE

    /**
     * A precision verification without anomalies reads only the unconfirmed week before the verification and the new
     * range up to its tc — never the reused evidence before it — and publishes v+1 with "verified, no change", its
     * ranges, the reused range and the time; the evidence and the history answer 200; a second request cools down;
     * the admission and the attempt each write their one line.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aPrecisionVerificationReadsOnlyTheUnconfirmedAndNewRangesAndPublishesTheNextVersion(CapturedOutput output)
            throws Exception {
        String subject = "verify-precision-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-precision", Instant.now().minus(Duration.ofDays(20)));
        var v1 = ready(user);
        Instant verified = v1.cashflowVerifiedThrough();
        String csrf = csrfTokenOf(cookie);
        BybitStubConfiguration.resetRequestCount();

        var accepted = postVerification(cookie, csrf, "PRECISION", v1, "verify-precision-key-0001");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        assertThat(accepted.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var fields = topLevelFieldsOf(accepted.body());
        assertThat(fields).containsOnlyKeys("outcome", "job", "retryAfterSeconds").containsEntry("outcome", "ACCEPTED");
        var jobView = nested(fields, "job");
        assertThat(jobView).containsOnlyKeys("jobId", "purpose", "reason", "trigger", "status", "stage", "failureCode",
                "admittedAt", "finishedAt").containsEntry("purpose", "VERIFY").containsEntry("reason", "USER_REQUEST")
                .containsEntry("trigger", "MANUAL").containsEntry("status", "QUEUED");
        UUID job = UUID.fromString((String) jobView.get("jobId"));
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");

        var v2 = ready(user);
        Instant tc = v2.basisAt();
        assertThat(v2.pointerVersion()).isEqualTo(2);
        assertThat(v2.cashflowVerifiedThrough()).isEqualTo(tc);
        // The plan: the unconfirmed week and the new range are the targets; the evidence before them is reused.
        assertThat(ranges(job)).containsExactly(
                "UNCONFIRMED|" + verified.minus(Duration.ofDays(7)) + "|" + verified,
                "REUSED|" + v1.startAt() + "|" + verified.minus(Duration.ofDays(7)),
                "NEW|" + verified + "|" + tc);
        // Only the targets were read: [verified - 7 days, tc] in windows of at most seven days, both types each.
        assertWindows(BybitStubConfiguration.transactionLogQueries(), verified.minus(Duration.ofDays(7)), tc);
        var scope = jdbc().sql("SELECT mode,window_start,resume_count FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", job).query((rs, n) -> new Object[] {rs.getString(1),
                        rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getInt(3)}).single();
        assertThat(scope).containsExactly("VERIFY", verified.minus(Duration.ofDays(7)), 0);
        assertThat(extendedWatermark(v2.calculationId())).as("a verification moves the extended watermark to tc")
                .isEqualTo(tc);

        // The dashboard: "verified, no change" with the ranges and the time; the verification cools down.
        var dashboard = topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, cookie).body());
        assertThat(dashboard).containsOnlyKeys("state", "nextAction", "result", "sync", "verification", "change",
                "planEnd");
        var change = nested(dashboard, "change");
        assertThat(change).containsEntry("kind", "VERIFIED_NO_CHANGE").containsEntry("purpose", "VERIFY")
                .containsEntry("reason", "USER_REQUEST").containsEntry("lateChangeCount", 0)
                .containsEntry("firstAffectedAt", null);
        // verifiedAt is the finish time of the attempt that made the version, to the millisecond.
        assertThat(change.get("verifiedAt")).isEqualTo(jdbc().sql("SELECT finished_at FROM sync_plan_sync_job "
                + "WHERE job_id=:j").param("j", job).query(OffsetDateTime.class).single().toInstant().toString());
        assertThat(listOf(change, "targets")).extracting(range -> range.get("kind")).containsExactly("UNCONFIRMED", "NEW");
        assertThat(listOf(change, "reused")).singleElement().satisfies(range -> assertThat(range)
                .containsEntry("start", v1.startAt().toString())
                .containsEntry("end", verified.minus(Duration.ofDays(7)).toString()));
        var verification = nested(dashboard, "verification");
        assertThat(verification).containsOnlyKeys("availability", "reason", "retryAfterSeconds", "suggestedReason",
                "preview", "catchUpFrom", "latest").containsEntry("availability", "COOLDOWN")
                .containsEntry("suggestedReason", "USER_REQUEST").containsEntry("catchUpFrom", null);
        var latest = nested(verification, "latest");
        assertThat(latest).containsEntry("jobId", job.toString()).containsEntry("status", "SUCCEEDED")
                .containsEntry("holdsResult", false);
        assertThat(nested(latest, "counts")).containsOnlyKeys("new", "same", "missing", "superseded", "conflict")
                .containsEntry("new", 0).containsEntry("missing", 0);

        // Stories 2.9 and 2.10 read v2; the evidence carries the change part with the version it replaced.
        var evidence = get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence?planId=" + v2.planId() + "&pointerVersion=2"
                + "&calculationId=" + v2.calculationId(), cookie);
        assertThat(evidence.statusCode()).as(evidence.body()).isEqualTo(200);
        var evidenceChange = nested(topLevelFieldsOf(evidence.body()), "change");
        assertThat(evidenceChange).containsEntry("kind", "VERIFIED_NO_CHANGE").containsEntry("firstAffectedAt", null);
        assertThat(nested(evidenceChange, "previous")).containsEntry("pointerVersion", 1)
                .containsEntry("calculationId", v1.calculationId().toString())
                .containsEntry("basisAt", verified.toString());
        assertThat(listOf(evidenceChange, "metrics")).hasSize(6);
        assertThat(listOf(evidenceChange, "events")).isEmpty();
        assertThat(nested(nested(topLevelFieldsOf(evidence.body()), "inputs"), "sourceRange"))
                .containsEntry("windowStart", verified.minus(Duration.ofDays(7)).toString());
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/adjustments?planId=" + v2.planId() + "&pointerVersion=2"
                + "&calculationId=" + v2.calculationId(), cookie).statusCode()).isEqualTo(200);

        // Asked again at once: the cooldown, with no provider call and no attempt.
        int calls = BybitStubConfiguration.requestCount();
        var cooldown = postVerification(cookie, csrf, "PRECISION", v2, "verify-precision-key-0002");
        assertThat(cooldown.statusCode()).isEqualTo(200);
        assertThat(topLevelFieldsOf(cooldown.body())).containsEntry("outcome", "COOLDOWN");
        assertThat(((Number) topLevelFieldsOf(cooldown.body()).get("retryAfterSeconds")).intValue()).isBetween(1, 30);
        assertThat(BybitStubConfiguration.requestCount()).isEqualTo(calls);

        assertThat(lines(output, "plan verification admission: ")).anySatisfy(line -> assertThat(line)
                .contains("kind=PRECISION result=ACCEPTED status=202 latencyMs="))
                .anySatisfy(line -> assertThat(line).contains("kind=PRECISION result=COOLDOWN status=200"));
        assertThat(endingLines(output, job)).singleElement().satisfies(line ->
                assertThat(line).contains("purpose=VERIFY reason=USER_REQUEST trigger=MANUAL mode=VERIFY "
                        + "status=SUCCEEDED stage=PUBLICATION code=- targets=2 windows=2 pages=4 retries=0 "
                        + "reconciled=0 lateChanges=0 latencyMs="));
        for (String line : lines(output, "plan ")) {
            for (String forbidden : List.of(user.toString(), v2.planId().toString(), "verify-precision-key"))
                assertThat(line).as(forbidden).doesNotContain(forbidden);
        }
    }

    // ------------------------------------------------------------------------------------------ LATE_EVENT

    /**
     * A deposit a failed attempt stored before the unconfirmed week holds the sync for review, and a precision
     * verification — not another sync — is the next action. The verification, admitted as {@code GAP_SUSPECTED},
     * reads 24 hours around it, finds it at Bybit, and publishes v+1 with a late addition: the dashboard, the evidence
     * and the history point at the same event and the same impact start, the metrics show both versions' values, and
     * the event is applied exactly once.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aLateEventIsFoundByTheVerificationAndPublishedAsALateAddition(CapturedOutput output) throws Exception {
        UUID user = connect(DevBybitStub.Scenario.LATE_EVENT);
        publishedPlan(user, "verify-late", Instant.now().minus(Duration.ofDays(20)));
        var v1 = ready(user);
        Instant lateAt = v1.startAt().plusMillis(1000);
        // A failed attempt stored it: the stub hides it from the first read and shows it from the second on.
        insertSourceEvent(user, "late-event-01", "TRANSFER_IN", "USDT", "25", lateAt);
        UUID syncJob = manualSync(user, "verify-late-sync-01");
        assertThat(awaitSync(syncJob)).isEqualTo("NEEDS_REVIEW");
        var held = readyDashboard(user);
        assertThat(held.nextAction()).isEqualTo("VERIFY");
        assertThat(held.result().trust().inputReliability().cause()).isEqualTo("SYNC_REVIEW_REQUIRED");
        assertThat(held.result().trust().inputReliability().nextAction()).isEqualTo("VERIFY");
        assertThat(held.verification().suggestedReason()).isEqualTo("GAP_SUSPECTED");
        assertThat(held.verification().preview().anomalyCount()).isEqualTo(1);
        assertThat(held.verification().preview().rangeStart()).isEqualTo(v1.startAt());
        cooldownPassed(user);

        UUID job = precision(user, v1, "verify-late-verify-01", "GAP_SUSPECTED");
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        var v2 = ready(user);
        assertThat(v2.pointerVersion()).isEqualTo(2);
        assertThat(v2.flowCount()).isEqualTo(v1.flowCount() + 1);
        assertThat(ranges(job)).contains("ANOMALY|" + v1.startAt() + "|" + lateAt.plus(Duration.ofHours(24)));
        assertThat(BybitStubConfiguration.transactionLogQueries()).anySatisfy(query -> {
            assertThat(query.startTime()).isEqualTo(v1.startAt().toEpochMilli());
            assertThat(query.endTime()).isEqualTo(lateAt.plus(Duration.ofHours(24)).toEpochMilli());
        });
        assertThat(outcomes(job)).containsExactly("late-event-01|NEW|true");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow WHERE calculation_id=:c "
                + "AND source_id='late-event-01'").param("c", v2.calculationId()).query(Integer.class).single())
                .as("applied once").isEqualTo(1);

        var dashboard = readyDashboard(user);
        assertThat(dashboard.change().kind()).isEqualTo("CORRECTED");
        assertThat(dashboard.change().reason()).isEqualTo("GAP_SUSPECTED");
        assertThat(dashboard.change().lateChangeCount()).isEqualTo(1);
        assertThat(dashboard.change().firstAffectedAt()).isEqualTo(lateAt);
        assertThat(dashboard.result().trust().inputReliability().state()).isEqualTo("CONFIRMED");

        var evidence = sync.calculationEvidence(user, reference(v2));
        var change = ((SyncJobApi.EvidenceOutcome.Found) evidence.outcome()).evidence().change();
        assertThat(change.kind()).isEqualTo("CORRECTED");
        assertThat(change.firstAffectedAt()).isEqualTo(lateAt);
        assertThat(change.previous()).isEqualTo(new SyncJobApi.CalculationEvidence.PreviousVersion(1,
                v1.calculationId(), v1.basisAt()));
        assertThat(change.events()).singleElement().satisfies(event -> {
            assertThat(event.sourceId()).isEqualTo("late-event-01");
            assertThat(event.changeKind()).isEqualTo("ADDED");
            assertThat(event.late()).isTrue();
            assertThat(event.occurredAt()).isEqualTo(lateAt);
            assertThat(event.previousUsdValue()).isNull();
            assertThat(event.usdValue()).isEqualTo("25");
            assertThat(event.evidenceType()).isEqualTo("DIRECT_NORMALIZATION");
            assertThat(event.appliedAt()).isEqualTo(v2.publishedAt());
        });
        var planAmount = change.metrics().stream().filter(metric -> metric.metric().equals("PLAN_AMOUNT")).findFirst()
                .orElseThrow();
        assertThat(planAmount.previousValue()).isEqualTo(v1.metrics().get(1).value());
        assertThat(planAmount.value()).isEqualTo(v2.metrics().get(1).value()).isNotEqualTo(planAmount.previousValue());

        var history = (SyncJobApi.AdjustmentOutcome.Found) sync.adjustmentHistory(user, new SyncJobApi.AdjustmentQuery(
                v2.planId(), 2, v2.calculationId(), SyncJobApi.AdjustmentDirection.ALL, null)).outcome();
        assertThat(history.history().rows()).filteredOn(row -> row.references().sourceId().equals("late-event-01"))
                .singleElement().satisfies(row -> {
                    assertThat(row.versionChange()).isNotNull();
                    assertThat(row.versionChange().change()).isEqualTo("ADDED");
                    assertThat(row.versionChange().late()).isTrue();
                    assertThat(row.versionChange().previousUsdValue()).isNull();
                    assertThat(row.versionChange().appliedAt()).isEqualTo(v2.publishedAt());
                });
        // No Bybit id and no amount in the attempt's line. Its lateChanges is counted in SQL (VersionChanges.LATE_SQL)
        // and the dashboard's lateChangeCount in Java (VersionChanges.late): the same rows, 1 and 1.
        assertThat(endingLines(output, job)).singleElement().satisfies(line -> {
            assertThat(line).contains("purpose=VERIFY reason=GAP_SUSPECTED").contains("lateChanges=1")
                    .contains("reconciled=1");
            assertThat(line).doesNotContain("late-event-01").doesNotContain(user.toString());
        });
    }

    /** A late withdrawal is found the same way, with its negative value and its own impact start. */
    @Test
    void aLateWithdrawalIsPublishedAsALateAddition() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.LATE_WITHDRAWAL);
        publishedPlan(user, "verify-withdrawal", Instant.now().minus(Duration.ofDays(3)));
        var v1 = ready(user);
        assertThat(v1.flowCount()).isZero();
        // Bybit's transaction time is whole milliseconds; the stub places the withdrawal a second after the first
        // window's start.
        Instant lateAt = v1.startAt().plusMillis(1000).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        UUID job = precision(user, v1, "verify-withdrawal-verify-01", "USER_REQUEST");
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        assertThat(outcomes(job)).containsExactly("late-withdrawal-01|NEW|true");
        var v2 = ready(user);
        assertThat(flowUsd(v2.calculationId(), "late-withdrawal-01")).isEqualTo("-5");
        var change = readyDashboard(user).change();
        assertThat(change.kind()).isEqualTo("CORRECTED");
        assertThat(change.firstAffectedAt()).isEqualTo(lateAt);
        var events = ((SyncJobApi.EvidenceOutcome.Found) sync.calculationEvidence(user, reference(v2)).outcome())
                .evidence().change().events();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.transactionType()).isEqualTo("TRANSFER_OUT");
            assertThat(event.changeKind()).isEqualTo("ADDED");
            assertThat(event.usdValue()).isEqualTo("-5");
        });
    }

    /**
     * Story 2.12 changed one rule of the Story 2.11 sync: an official event inside the sync's own window that the
     * complete collection does not see again is no longer carried silently — the sync needs review
     * ({@code SOURCE_EVENT_MISSING}), and the next action is the precision verification.
     */
    @Test
    void aSyncThatNoLongerSeesAnOfficialEventInItsWindowNeedsReview() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.VANISHING);
        // Ten minutes old: the sync's window starts at t0 and the official event is inside it.
        publishedPlan(user, "verify-sync-missing", Instant.now().minus(Duration.ofMinutes(10)));
        var v1 = ready(user);
        assertThat(v1.flowCount()).isEqualTo(1);
        UUID job = manualSync(user, "verify-sync-missing-01");
        assertThat(awaitSync(job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT failure_code||'|'||stage FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("SOURCE_EVENT_MISSING|COLLECTION");
        assertThat(outcomes(job)).as("a sync keeps no reconciliation record").isEmpty();
        assertStillV1(user, v1);
        var dashboard = readyDashboard(user);
        assertThat(dashboard.result().trust().freshness().cause()).isEqualTo("LAST_SYNC_NEEDS_REVIEW");
        assertThat(dashboard.nextAction()).isEqualTo("VERIFY");
        assertThat(dashboard.result().trust().inputReliability().cause()).isEqualTo("SYNC_REVIEW_REQUIRED");
    }

    // ------------------------------------------------------------------------------------------ SUPERSEDED

    /**
     * A stored event a complete verification does not see again, which no official version had, is excluded — not
     * deleted — and the verification publishes; every later candidate inherits the exclusion, so the next sync counts
     * it as explained and publishes too.
     */
    @Test
    void anUnseenStoredEventIsExcludedAndLaterSyncsTreatItAsExplained() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-superseded", Instant.now().minus(Duration.ofDays(20)));
        var v1 = ready(user);
        UUID ghost = insertSourceEvent(user, "ghost-01", "TRANSFER_IN", "USDT", "3",
                v1.startAt().plus(Duration.ofDays(2)));
        String ghostRow = sourceRow(ghost);
        assertThat(awaitSync(manualSync(user, "verify-superseded-sync-01"))).isEqualTo("NEEDS_REVIEW");
        cooldownPassed(user);

        UUID job = precision(user, v1, "verify-superseded-verify-01", "GAP_SUSPECTED");
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        var v2 = ready(user);
        assertThat(outcomes(job)).containsExactly("ghost-01|SUPERSEDED|false");
        assertThat(readyDashboard(user).change().kind()).isEqualTo("VERIFIED_NO_CHANGE");
        assertThat(exclusions(v2.calculationId())).containsExactly(ghost + "|" + job + "|NOT_REOBSERVED");
        assertThat(sourceRow(ghost)).as("the source fact is untouched").isEqualTo(ghostRow);

        cooldownPassed(user);
        UUID next = manualSync(user, "verify-superseded-sync-02");
        assertThat(awaitSync(next)).isEqualTo("SUCCEEDED");
        var v3 = ready(user);
        assertThat(v3.pointerVersion()).isEqualTo(3);
        assertThat(exclusions(v3.calculationId())).as("inherited with the attempt that excluded it")
                .containsExactly(ghost + "|" + job + "|NOT_REOBSERVED");
        assertThat(sync.calculationEvidence(user, reference(v3)).outcome())
                .isInstanceOf(SyncJobApi.EvidenceOutcome.Found.class);
    }

    // ------------------------------------------------------------------------------------------ MISSING / CONFLICT

    /**
     * An official event the verification does not see again holds everything: no version, no watermark, the input
     * reliability {@code VERIFICATION_REVIEW_REQUIRED} for all six with no action, and asking again is refused as not
     * useful. A sync that ends in review afterwards does not replace it. The system's verification still runs: an
     * operator must be able to verify the same base again.
     */
    @Test
    void aMissingOfficialEventHoldsTheVersionAndAskingAgainIsNotUseful() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.VANISHING);
        publishedPlan(user, "verify-missing", Instant.now().minus(Duration.ofDays(3)));
        var v1 = ready(user);
        assertThat(v1.flowCount()).isEqualTo(1);
        UUID vanished = jdbc().sql("SELECT event_id FROM sync_initial_source_event WHERE user_id=:u "
                + "AND source_id='vanishing-01'").param("u", user).query(UUID.class).single();
        String vanishedRow = sourceRow(vanished);

        UUID job = precision(user, v1, "verify-missing-01-key", "USER_REQUEST");
        assertThat(awaitSync(job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT failure_code||'|'||stage FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("SOURCE_EVENT_MISSING|COLLECTION");
        assertThat(outcomes(job)).containsExactly("vanishing-01|MISSING|false");
        assertThat(sourceRow(vanished)).isEqualTo(vanishedRow);
        assertStillV1(user, v1);
        assertVerificationHeld(user, "REVIEW_REQUIRED", "VERIFICATION_REVIEW_REQUIRED");

        cooldownPassed(user);
        var reviewed = readyDashboard(user).verification();
        assertThat(reviewed.availability()).isEqualTo("NOT_USEFUL");
        assertThat(reviewed.suggestedReason()).as("nothing to suggest when asking changes nothing").isNull();
        assertThat(reviewed.latest().holdsResult()).as("this attempt holds the result").isTrue();
        assertThat(sync.admitPlanVerification(user, SyncJobApi.VerificationKind.PRECISION, reference(v1),
                "verify-missing-02-key")).isEqualTo(new SyncJobApi.VerificationAdmission.Refused("VERIFICATION_NOT_USEFUL"));

        // Interpretation C1 and R6: the review holds every sync of the same base at admission — a manual and an
        // automatic one are refused as unavailable (REVIEW_HELD) with no attempt row and no Bybit call, so the missing
        // event is never carried silently and the version stays. (The worker's own C1 check stays as a defence.)
        // Counted, not reset: a reset would also forget which reads of this UID the stub already answered.
        int calls = BybitStubConfiguration.requestCount();
        int attempts = countOf("sync_plan_sync_job", user);
        assertThat(readyDashboard(user).sync()).isEqualTo(new SyncJobApi.SyncState("UNAVAILABLE", "REVIEW_HELD", null,
                readyDashboard(user).sync().latest()));
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "verify-missing-sync-01"))
                .isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        automaticDue(user);
        for (var trigger : List.of(SyncJobApi.SyncTrigger.ENTRY, SyncJobApi.SyncTrigger.VISIBILITY))
            assertThat(sync.admitPlanSync(user, trigger, "verify-missing-auto-" + trigger))
                    .isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        assertThat(countOf("sync_plan_sync_job", user)).as("no attempt row").isEqualTo(attempts);
        assertThat(BybitStubConfiguration.requestCount()).as("no Bybit call, not even the time").isEqualTo(calls);
        assertStillV1(user, v1);
        assertVerificationHeld(user, "REVIEW_REQUIRED", "VERIFICATION_REVIEW_REQUIRED");

        // The system's verification skips the usefulness and the cooldown.
        var system = sync.admitSystemVerification(user, SyncJobApi.VerificationReason.PLAN_END_PREPARATION, List.of(),
                "verify-missing-system-01");
        assertThat(system).isInstanceOf(SyncJobApi.VerificationAdmission.Accepted.class);
        var systemJob = ((SyncJobApi.VerificationAdmission.Accepted) system).job();
        assertThat(systemJob.trigger()).isEqualTo("SYSTEM");
        assertThat(systemJob.reason()).isEqualTo("PLAN_END_PREPARATION");
        assertThat(awaitSync(systemJob.jobId())).isEqualTo("NEEDS_REVIEW");
    }

    /**
     * The same Bybit id with another semantic hash is a conflict: review, the stored fact untouched, both hashes kept
     * in the verification's own record, and no missing outcome for it.
     */
    @Test
    void theSameIdWithAnotherHashIsAConflictThatNeedsReview() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.ID_CONFLICT);
        BybitStubConfiguration.resetRequestCount();
        publishedPlan(user, "verify-conflict", null);
        var v1 = ready(user);
        UUID event = jdbc().sql("SELECT event_id FROM sync_initial_source_event WHERE user_id=:u "
                + "AND source_id='stable-event-id'").param("u", user).query(UUID.class).single();
        String row = sourceRow(event);
        UUID job = precision(user, v1, "verify-conflict-01-key", "USER_REQUEST");
        assertThat(awaitSync(job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("SOURCE_REQUIRES_REVIEW");
        assertThat(jdbc().sql("SELECT outcome||'|'||(stored_semantic_hash<>observed_semantic_hash) "
                + "FROM sync_plan_verification_event WHERE job_id=:j").param("j", job).query(String.class).list())
                .containsExactly("CONFLICT|true");
        assertThat(sourceRow(event)).isEqualTo(row);
        assertStillV1(user, v1);
        assertVerificationHeld(user, "REVIEW_REQUIRED", "VERIFICATION_REVIEW_REQUIRED");
    }

    // ------------------------------------------------------------------------------------------ PRICE_REVISION

    /**
     * A verification asks again for the candles of the flows inside its targets even though they are cached: the
     * provider's corrected close becomes the candle's next revision (the earlier one stays), and the version records a
     * changed value from the previous USD to the new one, marked in the history.
     */
    @Test
    void aCorrectedCloseBecomesANewRevisionAndAChangedValue() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.REVISED_PRICE);
        BybitStubConfiguration.resetRequestCount();
        publishedPlan(user, "verify-price", Instant.now().minus(Duration.ofDays(3)), "CONVERTED_ESTIMATED");
        var v1 = ready(user);
        assertThat(flowUsd(v1.calculationId(), "revised-01")).isEqualTo("2");

        UUID job = precision(user, v1, "verify-price-01-key", "USER_REQUEST");
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        assertThat(outcomes(job)).as("observed again and official").containsExactly("revised-01|SAME|false");
        var v2 = ready(user);
        assertThat(flowUsd(v2.calculationId(), "revised-01")).isEqualTo("2.5");
        Instant minute = v1.startAt().plusMillis(1000).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        assertThat(jdbc().sql("SELECT revision||'|'||close_price FROM sync_market_candle WHERE symbol='REVUSDT' "
                + "AND start_at=:m ORDER BY revision").param("m", OffsetDateTime.ofInstant(minute, ZoneOffset.UTC))
                .query(String.class).list()).containsExactly("1|2", "2|2.5");
        var change = readyDashboard(user).change();
        assertThat(change.kind()).isEqualTo("CORRECTED");
        assertThat(change.lateChangeCount()).isEqualTo(1);
        var events = ((SyncJobApi.EvidenceOutcome.Found) sync.calculationEvidence(user, reference(v2)).outcome())
                .evidence().change().events();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.changeKind()).isEqualTo("VALUE_CHANGED");
            assertThat(event.previousUsdValue()).isEqualTo("2");
            assertThat(event.usdValue()).isEqualTo("2.5");
            assertThat(event.previousEstimated()).isTrue();
            assertThat(event.estimated()).isTrue();
            assertThat(event.evidenceType()).isEqualTo("SPOT_KLINE_CLOSE");
        });
        var history = (SyncJobApi.AdjustmentOutcome.Found) sync.adjustmentHistory(user, new SyncJobApi.AdjustmentQuery(
                v2.planId(), 2, v2.calculationId(), SyncJobApi.AdjustmentDirection.ALL, null)).outcome();
        assertThat(history.history().rows()).singleElement().satisfies(row -> {
            assertThat(row.versionChange().change()).isEqualTo("VALUE_CHANGED");
            assertThat(row.versionChange().previousUsdValue()).isEqualTo("2");
        });
    }

    // ------------------------------------------------------------------------------------------ CATCH_UP

    /**
     * After a long gap nothing calls Bybit until the user chooses; a stale choice token and a precision verification
     * are refused. {@code 기존 계획 계속} then collects from {@code verified - 7 days} to its tc in windows of at most
     * seven days, fixes {@code tcFinal}, sweeps the last seven days again with the current equity, and publishes once
     * with both watermarks at {@code tcFinal}; the plan is no longer in a long gap.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aContinuationCollectsFromTheUnconfirmedWeekThroughAFinalSweepAndPublishesOnce(CapturedOutput output)
            throws Exception {
        String subject = "verify-catchup-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-catchup", Instant.now().minus(Duration.ofDays(45)));
        var shown = ready(user);
        moveVerification(user, shown.calculationId(), "31 days");
        var v1 = ready(user);
        Instant verified = v1.cashflowVerifiedThrough();
        String csrf = csrfTokenOf(cookie);
        BybitStubConfiguration.resetRequestCount();

        var dashboard = topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, cookie).body());
        assertThat(nested(dashboard, "verification")).containsEntry("availability", "LONG_GAP")
                .containsEntry("catchUpFrom", verified.minus(Duration.ofDays(7)).toString()).containsEntry("preview", null);
        assertThat(postVerification(cookie, csrf, "PRECISION", v1, "verify-catchup-key-0001").body())
                .contains("\"code\":\"LONG_GAP\"");
        var stale = postVerification(cookie, csrf, "CATCH_UP", v1.planId(), 2, v1.calculationId(),
                "verify-catchup-key-0002");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(stale.body())).containsEntry("code", "REFERENCE_STALE");
        assertThat(BybitStubConfiguration.requestCount()).as("no provider call before the choice").isZero();

        var accepted = postVerification(cookie, csrf, "CATCH_UP", v1, "verify-catchup-key-0003");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        var jobView = nested(topLevelFieldsOf(accepted.body()), "job");
        assertThat(jobView).containsEntry("purpose", "CATCH_UP").containsEntry("reason", "LONG_GAP_CONTINUATION");
        UUID job = UUID.fromString((String) jobView.get("jobId"));
        assertThat(awaitSync(job, 90)).isEqualTo("SUCCEEDED");

        var times = jdbc().sql("SELECT initial_tc,tc FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query((rs, n) -> new Instant[] {rs.getObject(1, OffsetDateTime.class).toInstant(),
                        rs.getObject(2, OffsetDateTime.class).toInstant()}).single();
        Instant tc = times[0];
        Instant tcFinal = times[1];
        assertThat(tcFinal).isAfter(tc);
        var v2 = ready(user);
        assertThat(v2.pointerVersion()).isEqualTo(2);
        assertThat(v2.basisAt()).isEqualTo(tcFinal);
        assertThat(v2.cashflowVerifiedThrough()).isEqualTo(tcFinal);
        assertThat(extendedWatermark(v2.calculationId())).isEqualTo(tcFinal);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_official_plan_result_history WHERE user_id=:u")
                .param("u", user).query(Integer.class).single()).as("one publication").isEqualTo(2);
        // The first phase ran [verified - 7 days, tc] in contiguous seven-day windows; the final sweep re-read
        // [tcFinal - 7 days, tcFinal].
        var queries = BybitStubConfiguration.transactionLogQueries();
        assertWindows(queries.stream().filter(q -> q.endTime() <= tc.toEpochMilli()).toList(),
                verified.minus(Duration.ofDays(7)), tc);
        assertWindows(queries.stream().filter(q -> q.endTime() > tc.toEpochMilli()).toList(),
                tcFinal.minus(Duration.ofDays(7)), tcFinal);
        assertThat(ranges(job)).extracting(range -> range.substring(0, range.indexOf('|')))
                .containsExactly("UNCONFIRMED", "REUSED", "NEW", "FINAL_SWEEP");
        assertThat(ranges(job)).contains("FINAL_SWEEP|" + tcFinal.minus(Duration.ofDays(7)) + "|" + tcFinal);
        // The wallet was read once, in the final sweep: the current equity.
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.WALLET_BALANCE_PATH)).isEqualTo(1);

        var after = readyDashboard(user);
        assertThat(after.sync().availability()).isNotEqualTo("LONG_GAP");
        assertThat(after.verification().availability()).isNotEqualTo("LONG_GAP");
        assertThat(after.result().trust().freshness().cause()).isNotEqualTo("LONG_GAP");
        assertThat(after.change().kind()).isEqualTo("VERIFIED_NO_CHANGE");
        assertThat(after.change().purpose()).isEqualTo("CATCH_UP");
        assertThat(sync.calculationEvidence(user, reference(v2)).outcome())
                .isInstanceOf(SyncJobApi.EvidenceOutcome.Found.class);
        assertThat(endingLines(output, job)).singleElement().satisfies(line ->
                assertThat(line).contains("purpose=CATCH_UP reason=LONG_GAP_CONTINUATION trigger=MANUAL mode=CATCH_UP "
                        + "status=SUCCEEDED stage=PUBLICATION code=- targets=3"));
    }

    // ------------------------------------------------------------------------------------------ RESUME

    /**
     * A continuation whose holder loses its lease while collecting is reclaimed and goes on from its checkpoints: the
     * complete windows are not read again, the late holder writes nothing, and it publishes once.
     */
    @Test
    void aReclaimedContinuationGoesOnFromItsCompleteWindows() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-resume", Instant.now().minus(Duration.ofDays(45)));
        var shown = ready(user);
        moveVerification(user, shown.calculationId(), "31 days");
        var v1 = ready(user);
        BybitStubConfiguration.resetRequestCount();
        slowPageStores(user);
        UUID job;
        try {
            job = continuation(user, v1, "verify-resume-01-key");
            awaitPages(job, 3);
            expireLease(job);
            assertThat(awaitSync(job, 120)).isEqualTo("SUCCEEDED");
        } finally {
            dropSlowPageStores();
        }
        assertThat(jdbc().sql("SELECT resume_count FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(ready(user).pointerVersion()).isEqualTo(2);
        // The first window was complete before the loss: read once. None was read more than twice.
        var byWindow = new java.util.TreeMap<Long, Integer>();
        for (var q : BybitStubConfiguration.transactionLogQueries())
            if (q.type().equals("TRANSFER_IN")) byWindow.merge(q.startTime(), 1, Integer::sum);
        assertThat(byWindow.firstEntry().getValue()).isEqualTo(1);
        assertThat(byWindow.values()).allSatisfy(count -> assertThat(count).isBetween(1, 2));
        assertThat(byWindow.values()).contains(1);
    }

    /** The third loss of the same attempt is not resumed: it ends as interrupted and publishes nothing. */
    @Test
    void theThirdInterruptionEndsTheAttemptAsInterrupted() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-interrupted", Instant.now().minus(Duration.ofDays(60)));
        var v1 = ready(user);
        // Three anomalies far apart: more windows to read, so each loss lands while collecting.
        for (int day : new int[] {5, 20, 35})
            insertSourceEvent(user, "interrupted-" + day, "TRANSFER_IN", "USDT", "1", v1.startAt().plus(Duration.ofDays(day)));
        slowPageStores(user);
        UUID job;
        try {
            job = precision(user, v1, "verify-interrupted-01", "GAP_SUSPECTED");
            for (int loss = 0; loss < 3; loss++) {
                awaitResumeCount(job, loss);
                awaitPages(job, loss + 1);
                expireLease(job);
            }
            assertThat(awaitSync(job, 120)).isEqualTo("FAILED");
        } finally {
            dropSlowPageStores();
        }
        assertThat(jdbc().sql("SELECT failure_code||'|'||resume_count FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", job).query(String.class).single()).isEqualTo("SYNC_INTERRUPTED|2");
        assertStillV1(user, v1);
        assertNoChildInProgress(job);
    }

    // ------------------------------------------------------------------------------------------ OUT_OF_RANGE

    /**
     * A target older than {@code tc - 730 days} ends the attempt before any signed call, publishes nothing, and makes
     * the input reliability unusable for as long as the base is official. (An active plan cannot be that old, so the
     * guard is exercised with an attempt whose planned range reaches back that far.)
     */
    @Test
    void aTargetOutOfTheProvidersHistoryEndsBeforeAnySignedCall() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-range", Instant.now().minus(Duration.ofDays(20)));
        var v1 = ready(user);
        UUID job = UUID.randomUUID();
        var base = jdbc().sql("SELECT c.candidate_id,c.membership_id,c.connection_id,c.provider_account_id,d.intent_id,"
                + "c.portfolio_id FROM sync_plan_calculation c JOIN plan_definition d ON d.plan_id=c.plan_id "
                + "WHERE c.calculation_id=:c").param("c", v1.calculationId())
                .query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                        rs.getObject(6, UUID.class)}).single();
        BybitStubConfiguration.resetRequestCount();
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc().sql("INSERT INTO sync_plan_sync_job(job_id,user_id,plan_id,portfolio_id,intent_id,membership_id,"
                    + "connection_id,provider_account_id,purpose,reason_code,trigger,idempotency_key,base_calculation_id,"
                    + "base_pointer_version,base_candidate_id,base_verified_through,base_extended_verification_at,t0,"
                    + "end_at,status) VALUES (:j,:u,:plan,:p,:i,:m,:c,:a,'VERIFY','OPERATOR_INVESTIGATION','SYSTEM',"
                    + "'verify-range-key-0001',:calc,1,:cand,:verified,:verified,:t0,:end,'QUEUED')")
                    .param("j", job).param("u", user).param("plan", v1.planId()).param("p", base[5])
                    .param("i", base[4]).param("m", base[1]).param("c", base[2]).param("a", base[3])
                    .param("calc", v1.calculationId()).param("cand", base[0]).param("verified", utc(v1.basisAt()))
                    .param("t0", utc(v1.startAt())).param("end", utc(v1.endAt())).update();
            jdbc().sql("INSERT INTO sync_plan_verification_range(job_id,seq,user_id,kind,range_start,range_end) VALUES "
                    + "(:j,1,:u,'UNCONFIRMED',:u0,:u1),(:j,2,:u,'EXPLICIT',:e0,:e1)")
                    .param("j", job).param("u", user).param("u0", utc(v1.basisAt().minus(Duration.ofDays(7))))
                    .param("u1", utc(v1.basisAt())).param("e0", utc(Instant.now().minus(Duration.ofDays(800))))
                    .param("e1", utc(Instant.now().minus(Duration.ofDays(799)))).update();
        });
        assertThat(awaitSync(job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("HISTORY_OUT_OF_RANGE");
        for (String signed : List.of(DevBybitStub.QUERY_API_PATH, DevBybitStub.ACCOUNT_INFO_PATH,
                DevBybitStub.WALLET_BALANCE_PATH, DevBybitStub.TRANSACTION_LOG_PATH))
            assertThat(BybitStubConfiguration.requestCount(signed)).as(signed).isZero();
        assertStillV1(user, v1);
        assertVerificationHeld(user, "UNUSABLE", "HISTORY_OUT_OF_RANGE");
    }

    // ------------------------------------------------------------------------------------------ BUDGET

    /** A page that answers 503 twice is asked again, the same page, after the budget's delays, and the sync succeeds. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFlakyPageIsRetriedAndTheSyncSucceeds(CapturedOutput output) throws Exception {
        UUID user = connect(DevBybitStub.Scenario.LOG_FLAKY);
        publishedPlan(user, "verify-flaky", null);
        int before = BybitStubConfiguration.transactionLogQueries().size();
        UUID job = manualSync(user, "verify-flaky-01-key");
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        var reads = transferIn(before);
        assertThat(reads).hasSize(3).extracting(BybitStubConfiguration.TransactionQuery::startTime).containsOnly(
                reads.getFirst().startTime());
        assertThat(reads.get(1).receivedAtMillis() - reads.get(0).receivedAtMillis()).isGreaterThanOrEqualTo(45);
        assertThat(reads.get(2).receivedAtMillis() - reads.get(1).receivedAtMillis()).isGreaterThanOrEqualTo(95);
        assertThat(endingLines(output, job)).singleElement()
                .satisfies(line -> assertThat(line).contains("retries=2"));
    }

    /** A page that keeps answering 503 gets three retries and no more; the sync ends as a provider failure. */
    @Test
    void aPageThatStaysDownExhaustsTheRetries() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.LOG_DOWN);
        publishedPlan(user, "verify-down", null);
        var v1 = ready(user);
        int before = BybitStubConfiguration.transactionLogQueries().size();
        UUID job = manualSync(user, "verify-down-01-key");
        assertThat(awaitSync(job)).isEqualTo("FAILED");
        assertThat(jdbc().sql("SELECT failure_code||'|'||stage FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("PROVIDER_UNAVAILABLE|COLLECTION");
        assertThat(transferIn(before)).hasSize(4);
        assertStillV1(user, v1);
    }

    /** {@code retCode 10006} is retried no earlier than the reset time its headers gave. */
    @Test
    void aRateLimitedPageIsRetriedAfterItsResetTime() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.LOG_RATE_LIMITED);
        publishedPlan(user, "verify-limited", null);
        int before = BybitStubConfiguration.transactionLogQueries().size();
        assertThat(awaitSync(manualSync(user, "verify-limited-01-key"))).isEqualTo("SUCCEEDED");
        var reads = transferIn(before);
        assertThat(reads).hasSize(2);
        assertThat(reads.get(1).receivedAtMillis() - reads.get(0).receivedAtMillis()).isGreaterThanOrEqualTo(150);
    }

    /** When the headers say the remaining budget is low, the next page waits for the reset time. */
    @Test
    void aLowRemainingBudgetDelaysTheNextPageUntilItsReset() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.LOG_LOW_LIMIT);
        publishedPlan(user, "verify-lowlimit", null);
        int before = BybitStubConfiguration.transactionLogQueries().size();
        assertThat(awaitSync(manualSync(user, "verify-lowlimit-01-key"))).isEqualTo("SUCCEEDED");
        var all = BybitStubConfiguration.transactionLogQueries();
        var queries = all.subList(before, all.size());
        assertThat(queries).extracting(BybitStubConfiguration.TransactionQuery::type)
                .containsExactly("TRANSFER_IN", "TRANSFER_OUT");
        assertThat(queries.get(1).receivedAtMillis() - queries.get(0).receivedAtMillis()).isGreaterThanOrEqualTo(250);
    }

    /**
     * An HTTP 403 is an IP ban: the sync ends as a provider failure without a retry, and while the circuit is open no
     * Bybit call leaves the process — not even the public time. It closes after its duration.
     */
    @Test
    void anIpBanOpensTheCircuitOverEveryBybitCall() throws Exception {
        try {
            UUID user = connect(DevBybitStub.Scenario.LOG_IP_BAN);
            publishedPlan(user, "verify-ipban", null);
            int before = BybitStubConfiguration.transactionLogQueries().size();
            UUID job = manualSync(user, "verify-ipban-01-key");
            assertThat(awaitSync(job)).isEqualTo("FAILED");
            assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                    .query(String.class).single()).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(transferIn(before)).as("no retry after a ban").hasSize(1);
            int calls = BybitStubConfiguration.requestCount();
            cooldownPassed(user);
            UUID held = manualSync(user, "verify-ipban-02-key");
            assertThat(awaitSync(held)).isEqualTo("FAILED");
            assertThat(jdbc().sql("SELECT failure_code||'|'||stage FROM sync_plan_sync_job WHERE job_id=:j")
                    .param("j", held).query(String.class).single()).isEqualTo("PROVIDER_UNAVAILABLE|TIME");
            assertThat(source.marketTime())
                    .isInstanceOf(com.bokrilog.source.SourceConnectionApi.MarketTimeResult.Rejected.class);
            assertThat(BybitStubConfiguration.requestCount()).as("no HTTP while the circuit is open").isEqualTo(calls);
            // Asked until it closes: every refused ask sends nothing, and the first that goes out is answered.
            assertThat(awaitCircuitClosed()).isTrue();
            assertThat(BybitStubConfiguration.requestCount()).isEqualTo(calls + 1);
        } finally {
            // Whatever failed above, the next test does not start behind this one's circuit.
            awaitCircuitClosed();
        }
    }

    /** Asks the public time until the IP circuit lets it out; false when it stayed shut past its duration. */
    private boolean awaitCircuitClosed() throws InterruptedException {
        long deadline = System.nanoTime() + BybitStubConfiguration.CIRCUIT_OPEN.plusSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (source.marketTime() instanceof com.bokrilog.source.SourceConnectionApi.MarketTimeResult.Observed)
                return true;
            Thread.sleep(50);
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------ RACE / ERASURE

    /**
     * A deletion, restriction or pointer move that lands while the verification waits to publish wins: nothing is
     * published, no watermark moves, and it ends blocked with the closed code.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"DELETION,NOT_PERMITTED", "RESTRICTION,NOT_PERMITTED", "POINTER,POINTER_CONFLICT"})
    void aChangeBeforeThePublicationWinsOverTheVerification(String change, String code) throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-race-" + change.toLowerCase(java.util.Locale.ROOT), null);
        var v1 = ready(user);
        UUID job;
        try (AutoCloseable held = holdPlanLock(user)) {
            job = precision(user, v1, "verify-race-01-key", "USER_REQUEST");
            awaitBlockedOnPlanLock();
            switch (change) {
                case "DELETION" -> accountDeletion().beginDeletion(user);
                case "RESTRICTION" -> restrictGlobally(com.bokrilog.operations.RestrictionFeature.SYNC);
                default -> jdbc().sql("UPDATE sync_official_plan_result SET pointer_version=pointer_version+10 "
                        + "WHERE user_id=:u").param("u", user).update();
            }
        }
        assertThat(awaitSync(job)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo(code);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation WHERE user_id=:u").param("u", user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_change WHERE user_id=:u").param("u", user)
                .query(Integer.class).single()).isZero();
        assertNoChildInProgress(job);
    }

    /**
     * The erasure removes this user's attempts with their ranges and reconciliation outcomes, the versions' change
     * rows and the candidates' exclusions — a verification still in progress too, whose late worker recreates nothing
     * — and keeps the other user's.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void erasureRemovesTheUsersVerificationRowsAndKeepsOtherUsers(CapturedOutput output) throws Exception {
        UUID kept = verifiedWithEveryRow("verify-kept");
        UUID user = verifiedWithEveryRow("verify-erased");
        cooldownPassed(user);
        var shown = ready(user);
        UUID late;
        try (AutoCloseable held = holdPlanLock(user)) {
            late = precision(user, shown, "verify-erased-late", "USER_REQUEST");
            awaitBlockedOnPlanLock();
            accountDeletion().beginDeletion(user);
            portfolio.erasePortfolioData(user);
            sync.eraseSyncData(user);
        }
        // A negative assertion: nothing to wait for but time — the late worker must not recreate anything.
        Thread.sleep(1_500);
        for (String table : List.of("sync_plan_sync_job", "sync_plan_verification_range", "sync_plan_verification_event",
                "sync_initial_candidate_excluded_event", "sync_plan_calculation_change", "sync_plan_calculation")) {
            assertThat(countOf(table, user)).as(table).isZero();
            assertThat(countOf(table, kept)).as("kept " + table).isPositive();
        }
        assertThat(ready(kept).pointerVersion()).isEqualTo(2);
        assertThat(endingLines(output, late)).singleElement()
                .satisfies(line -> assertThat(line).contains("status=ERASED"));
    }

    /** A published verification with a late addition, an exclusion, ranges and outcomes. */
    private UUID verifiedWithEveryRow(String prefix) throws Exception {
        UUID user = connect(DevBybitStub.Scenario.LATE_EVENT);
        publishedPlan(user, prefix, Instant.now().minus(Duration.ofDays(3)));
        var v1 = ready(user);
        insertSourceEvent(user, prefix + "-ghost", "TRANSFER_IN", "USDT", "2", v1.startAt().plus(Duration.ofHours(5)));
        UUID job = precision(user, v1, prefix + "-verify-01", "GAP_SUSPECTED");
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        assertThat(outcomes(job)).contains("late-event-01|NEW|true", prefix + "-ghost|SUPERSEDED|false");
        return user;
    }

    // ------------------------------------------------------------------------------------------ HTTP / REFUSALS

    /**
     * The HTTP surface: 400 for any body that is not the shape, 422 without a key, 403 without the CSRF token and
     * Origin or for a deleting account, 405 for a read, and the 409 refusals — no plan, a stale reference, another
     * purpose in progress, unavailable, not a long gap, and a key reused for the other kind — with a sync joining a
     * verification in progress but never the other way round. Every answer is {@code no-store}; no provider call.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theHttpSurfaceAnswersEveryAdmissionShape(CapturedOutput output) throws Exception {
        String subject = "verify-http-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String csrf = csrfTokenOf(cookie);
        store(user, DevBybitStub.Scenario.UTA2);
        var none = postVerification(cookie, csrf, "PRECISION", UUID.randomUUID(), 1, UUID.randomUUID(),
                "verify-http-key-0001");
        assertThat(none.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(none.body())).containsEntry("code", "NO_ACTIVE_PLAN");
        publishedPlan(user, "verify-http", null);
        var v1 = ready(user);
        BybitStubConfiguration.resetRequestCount();
        String plan = v1.planId().toString();
        String calc = v1.calculationId().toString();
        for (String body : new String[] {"{}", "not json", "[]",
                "{\"kind\":\"precision\",\"planId\":\"" + plan + "\",\"pointerVersion\":1,\"calculationId\":\"" + calc + "\"}",
                "{\"kind\":\"PRECISION\",\"planId\":\"" + plan + "\",\"pointerVersion\":\"1\",\"calculationId\":\"" + calc + "\"}",
                "{\"kind\":\"PRECISION\",\"planId\":\"" + plan + "\",\"pointerVersion\":0,\"calculationId\":\"" + calc + "\"}",
                "{\"kind\":\"PRECISION\",\"planId\":\"" + plan + "\",\"pointerVersion\":1.5,\"calculationId\":\"" + calc + "\"}",
                "{\"kind\":\"PRECISION\",\"planId\":\"not-a-uuid\",\"pointerVersion\":1,\"calculationId\":\"" + calc + "\"}",
                "{\"kind\":\"PRECISION\",\"planId\":\"" + plan + "\",\"pointerVersion\":1}"}) {
            var bad = sendJson("POST", VERIFICATION_PATH, body, cookie, "Origin", OidcStubConfiguration.PUBLIC_BASE_URL,
                    AuthSecurityConfig.CSRF_HEADER_NAME, csrf, "Idempotency-Key", "verify-http-key-0002");
            assertThat(bad.statusCode()).as(body).isEqualTo(400);
            assertThat(bad.headers().firstValue("Cache-Control").orElse("")).as(body).contains("no-store");
            assertThat(topLevelFieldsOf(bad.body())).as(body).containsEntry("code", "BAD_REQUEST");
        }
        var keyless = sendJson("POST", VERIFICATION_PATH, bodyOf("PRECISION", v1.planId(), 1, v1.calculationId()),
                cookie, "Origin", OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME, csrf);
        assertThat(keyless.statusCode()).isEqualTo(422);
        // 모든 엄격 키 경로가 같은 code와 같은 메시지 한 문장으로 답한다(`ApiErrorHandler`).
        assertThat(topLevelFieldsOf(keyless.body())).containsEntry("code", "IDEMPOTENCY_KEY_REQUIRED")
                .containsEntry("message", ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED);
        assertThat(sendJson("POST", VERIFICATION_PATH, bodyOf("PRECISION", v1.planId(), 1, v1.calculationId()), cookie,
                "Idempotency-Key", "verify-http-key-0003").statusCode()).as("no CSRF token or Origin").isEqualTo(403);
        assertThat(get(VERIFICATION_PATH, cookie).statusCode()).as("the command path is not a read").isEqualTo(405);
        var notLong = postVerification(cookie, csrf, "CATCH_UP", v1, "verify-http-key-0004");
        assertThat(topLevelFieldsOf(notLong.body())).containsEntry("code", "NOT_LONG_GAP");
        UUID restriction = restrictGlobally(com.bokrilog.operations.RestrictionFeature.SYNC);
        var unavailable = postVerification(cookie, csrf, "PRECISION", v1, "verify-http-key-0005");
        assertThat(unavailable.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(unavailable.body())).containsEntry("code", "VERIFICATION_UNAVAILABLE");
        assertThat(nested(topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, cookie).body()), "verification"))
                .containsEntry("availability", "UNAVAILABLE").containsEntry("reason", "RESTRICTED");
        lift(restriction);
        assertThat(BybitStubConfiguration.requestCount()).as("no provider call for a refusal").isZero();

        // A sync in progress: a verification is refused, never joined to it.
        UUID syncJob;
        try (AutoCloseable held = holdPlanLock(user)) {
            syncJob = manualSync(user, "verify-http-sync-01");
            awaitBlockedOnPlanLock();
            var busy = postVerification(cookie, csrf, "PRECISION", v1, "verify-http-key-0006");
            assertThat(busy.statusCode()).isEqualTo(409);
            assertThat(topLevelFieldsOf(busy.body())).containsEntry("code", "JOB_IN_PROGRESS");
        }
        assertThat(awaitSync(syncJob)).isEqualTo("SUCCEEDED");
        var v2 = ready(user);
        cooldownPassed(user);
        // A verification in progress: a sync joins it; the same key and kind joins; the other kind conflicts.
        UUID verification;
        try (AutoCloseable held = holdPlanLock(user)) {
            var accepted = postVerification(cookie, csrf, "PRECISION", v2, "verify-http-key-0007");
            assertThat(accepted.statusCode()).isEqualTo(202);
            verification = UUID.fromString((String) nested(topLevelFieldsOf(accepted.body()), "job").get("jobId"));
            awaitBlockedOnPlanLock();
            var joinedSync = sendJson("POST", SYNC_PATH, "{\"trigger\":\"MANUAL\"}", cookie, "Origin",
                    OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME, csrf, "Idempotency-Key",
                    "verify-http-key-0008");
            assertThat(joinedSync.statusCode()).isEqualTo(200);
            assertThat(topLevelFieldsOf(joinedSync.body())).containsEntry("outcome", "JOINED");
            assertThat(nested(topLevelFieldsOf(joinedSync.body()), "job")).containsEntry("jobId", verification.toString())
                    .containsEntry("purpose", "VERIFY");
            var replay = postVerification(cookie, csrf, "PRECISION", v2, "verify-http-key-0007");
            assertThat(topLevelFieldsOf(replay.body())).containsEntry("outcome", "JOINED");
            var conflict = postVerification(cookie, csrf, "CATCH_UP", v2, "verify-http-key-0007");
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(topLevelFieldsOf(conflict.body())).containsEntry("code", "IDEMPOTENCY_CONFLICT");
        }
        assertThat(awaitSync(verification)).isEqualTo("SUCCEEDED");
        accountDeletion().beginDeletion(user);
        assertThat(postVerification(cookie, csrf, "PRECISION", v2, "verify-http-key-0009").statusCode())
                .isEqualTo(403);
        var lines = lines(output, "plan verification admission: ");
        assertThat(lines).isNotEmpty().allSatisfy(line -> {
            assertThat(line).matches(".*plan verification admission: kind=(PRECISION|CATCH_UP|-) result=[A-Z_]+ "
                    + "status=\\d{3} latencyMs=\\d+$");
            assertThat(line).doesNotContain(user.toString()).doesNotContain("verify-http-key");
        });
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("kind=PRECISION result=DENIED status=403"))
                .anySatisfy(line -> assertThat(line).contains("kind=- result=BAD_REQUEST status=400"))
                .anySatisfy(line -> assertThat(line).contains("kind=CATCH_UP result=IDEMPOTENCY_CONFLICT status=409"));
    }

    /**
     * The system's four reasons come through the public method only, on the current pointer, with trigger
     * {@code SYSTEM}; an operator's investigation carries its explicit ranges (1 to 10, inside {@code (t0, verified]})
     * and the other reasons none.
     */
    @Test
    void theSystemReasonsAreAdmittedThroughThePublicMethodOnly() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-system", Instant.now().minus(Duration.ofDays(20)));
        var shown = ready(user);
        Instant verified = shown.cashflowVerifiedThrough();
        var investigation = List.of(new SyncJobApi.InvestigationRange(shown.startAt().plus(Duration.ofDays(1)),
                shown.startAt().plus(Duration.ofDays(2))));
        assertThat(sync.admitSystemVerification(user, SyncJobApi.VerificationReason.USER_REQUEST, List.of(),
                "verify-system-key-000")).isEqualTo(new SyncJobApi.VerificationAdmission.Refused("BAD_REQUEST"));
        assertThat(sync.admitSystemVerification(user, SyncJobApi.VerificationReason.OPERATOR_INVESTIGATION, List.of(),
                "verify-system-key-001")).isEqualTo(new SyncJobApi.VerificationAdmission.Refused("INVALID_RANGE"));
        assertThat(sync.admitSystemVerification(user, SyncJobApi.VerificationReason.UNRECOGNIZED_EVENT, investigation,
                "verify-system-key-002")).isEqualTo(new SyncJobApi.VerificationAdmission.Refused("INVALID_RANGE"));
        assertThat(sync.admitSystemVerification(user, SyncJobApi.VerificationReason.OPERATOR_INVESTIGATION,
                List.of(new SyncJobApi.InvestigationRange(verified, verified.plusSeconds(60))), "verify-system-key-003"))
                .isEqualTo(new SyncJobApi.VerificationAdmission.Refused("INVALID_RANGE"));
        int version = 1;
        for (var reason : List.of(SyncJobApi.VerificationReason.PLAN_END_PREPARATION,
                SyncJobApi.VerificationReason.UNRECOGNIZED_EVENT, SyncJobApi.VerificationReason.DUPLICATE_SUSPECTED,
                SyncJobApi.VerificationReason.OPERATOR_INVESTIGATION)) {
            var admission = sync.admitSystemVerification(user, reason,
                    reason == SyncJobApi.VerificationReason.OPERATOR_INVESTIGATION ? investigation : List.of(),
                    "verify-system-" + reason.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
            assertThat(admission).as(reason.name()).isInstanceOf(SyncJobApi.VerificationAdmission.Accepted.class);
            var job = ((SyncJobApi.VerificationAdmission.Accepted) admission).job();
            assertThat(job.purpose()).isEqualTo("VERIFY");
            assertThat(job.trigger()).isEqualTo("SYSTEM");
            assertThat(job.reason()).isEqualTo(reason.name());
            if (reason == SyncJobApi.VerificationReason.OPERATOR_INVESTIGATION)
                assertThat(ranges(job.jobId())).contains("EXPLICIT|" + investigation.getFirst().start() + "|"
                        + investigation.getFirst().end());
            assertThat(awaitSync(job.jobId())).as(reason.name()).isEqualTo("SUCCEEDED");
            assertThat(ready(user).pointerVersion()).isEqualTo(++version);
        }
    }

    // ------------------------------------------------------------------------------------------ LINEAGE / INTEGRITY

    /**
     * A verification of a version a sync made (lineage depth one): the lineage's evidence is v1's {@code [t0, tc1]} and
     * the sync's {@code [tc1 - 24h, tc2]}, so nothing is a gap — the targets are the unconfirmed week and the new range,
     * everything before the week is reused, and only the targets are read.
     */
    @Test
    void aVerificationOfASyncedVersionReusesTheWholeLineageWithoutAGap() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-depth", Instant.now().minus(Duration.ofDays(20)));
        var v1 = ready(user);
        assertThat(awaitSync(manualSync(user, "verify-depth-sync-01"))).isEqualTo("SUCCEEDED");
        var v2 = ready(user);
        assertThat(v2.pointerVersion()).isEqualTo(2);
        Instant verified = v2.cashflowVerifiedThrough();
        cooldownPassed(user);
        int before = BybitStubConfiguration.transactionLogQueries().size();
        UUID job = precision(user, v2, "verify-depth-verify-01", "USER_REQUEST");
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        var v3 = ready(user);
        assertThat(v3.pointerVersion()).isEqualTo(3);
        assertThat(ranges(job)).containsExactly(
                "UNCONFIRMED|" + verified.minus(Duration.ofDays(7)) + "|" + verified,
                "REUSED|" + v1.startAt() + "|" + verified.minus(Duration.ofDays(7)),
                "NEW|" + verified + "|" + v3.basisAt());
        var all = BybitStubConfiguration.transactionLogQueries();
        assertWindows(all.subList(before, all.size()), verified.minus(Duration.ofDays(7)), v3.basisAt());
        assertThat(sync.calculationEvidence(user, reference(v3)).outcome())
                .isInstanceOf(SyncJobApi.EvidenceOutcome.Found.class);
    }

    /**
     * A version's change rows must be exactly its flow difference in every field (patch 19): a row deleted, a kind
     * flipped, a previous value, late flag or discovery time changed, or a row too many, and the evidence and the
     * history answer "unavailable, inconsistent" while the dashboard still answers — its change marker folds to
     * {@code NONE} (patch 23). Restored, all three read it again.
     */
    @Test
    void aChangeRowThatIsNotTheFlowDifferenceMakesTheEvidenceAndHistoryUnavailable() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.REVISED_PRICE);
        // A minute of its own: the REV candle cache and the stub's per-minute reads are shared by this JVM's tests.
        publishedPlan(user, "verify-tamper", Instant.now().minus(Duration.ofDays(3)).minus(Duration.ofHours(2)),
                "CONVERTED_ESTIMATED");
        var v1 = ready(user);
        UUID job = precision(user, v1, "verify-tamper-01-key", "USER_REQUEST");
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        var v2 = ready(user);
        UUID calculation = v2.calculationId();
        String where = " WHERE calculation_id='" + calculation + "'";
        UUID extra = insertSourceEvent(user, "tamper-extra", "TRANSFER_IN", "USDT", "4",
                v1.startAt().plus(Duration.ofHours(3)));
        var original = jdbc().sql("SELECT * FROM sync_plan_calculation_change" + where).query().singleRow();
        String[][] tampers = {
            {"UPDATE sync_plan_calculation_change SET late=NOT late" + where,
                "UPDATE sync_plan_calculation_change SET late=NOT late" + where},
            {"UPDATE sync_plan_calculation_change SET previous_usd_value='3'" + where,
                "UPDATE sync_plan_calculation_change SET previous_usd_value='2'" + where},
            {"UPDATE sync_plan_calculation_change SET discovered_at=discovered_at-interval '1 second'" + where,
                "UPDATE sync_plan_calculation_change SET discovered_at=discovered_at+interval '1 second'" + where},
            {"UPDATE sync_plan_calculation_change SET change_kind='ADDED',previous_usd_value=NULL,"
                + "previous_estimated=NULL" + where,
                "UPDATE sync_plan_calculation_change SET change_kind='VALUE_CHANGED',previous_usd_value='2',"
                + "previous_estimated=true" + where},
            {"DELETE FROM sync_plan_calculation_change" + where, null},
            {"INSERT INTO sync_plan_calculation_change(calculation_id,source_event_id,user_id,previous_calculation_id,"
                + "change_kind,late,occurred_at,new_usd_value,new_estimated,evidence_type,discovered_at) VALUES ('"
                + calculation + "','" + extra + "','" + user + "','" + v1.calculationId() + "','ADDED',true,"
                + "now()-interval '1 day','4',false,'DIRECT_NORMALIZATION',now())",
                "DELETE FROM sync_plan_calculation_change" + where + " AND source_event_id='" + extra + "'"},
        };
        for (String[] tamper : tampers) {
            jdbc().sql(tamper[0]).update();
            try {
                var evidence = sync.calculationEvidence(user, reference(v2));
                assertThat(evidence.outcome()).as(tamper[0]).isInstanceOf(SyncJobApi.EvidenceOutcome.Unavailable.class);
                assertThat(evidence.consistency()).as(tamper[0]).isEqualTo(SyncJobApi.BundleConsistency.INCONSISTENT);
                var history = sync.adjustmentHistory(user, new SyncJobApi.AdjustmentQuery(v2.planId(), 2, calculation,
                        SyncJobApi.AdjustmentDirection.ALL, null));
                assertThat(history.outcome()).as(tamper[0]).isInstanceOf(SyncJobApi.AdjustmentOutcome.Unavailable.class);
                assertThat(history.consistency()).as(tamper[0]).isEqualTo(SyncJobApi.BundleConsistency.INCONSISTENT);
                var dashboard = readyDashboard(user);
                assertThat(dashboard.change().kind()).as(tamper[0]).isEqualTo("NONE");
                assertThat(dashboard.change().lateChangeCount()).isZero();
            } finally {
                if (tamper[1] != null) {
                    jdbc().sql(tamper[1]).update();
                } else {
                    jdbc().sql("INSERT INTO sync_plan_calculation_change(calculation_id,source_event_id,user_id,"
                            + "previous_calculation_id,change_kind,late,occurred_at,previous_usd_value,previous_estimated,"
                            + "new_usd_value,new_estimated,evidence_type,discovered_at) VALUES (:c,:e,:u,:p,:k,:late,:at,"
                            + ":pu,:pe,:nu,:ne,:ev,:d)")
                            .param("c", original.get("calculation_id")).param("e", original.get("source_event_id"))
                            .param("u", original.get("user_id")).param("p", original.get("previous_calculation_id"))
                            .param("k", original.get("change_kind")).param("late", original.get("late"))
                            .param("at", original.get("occurred_at")).param("pu", original.get("previous_usd_value"))
                            .param("pe", original.get("previous_estimated")).param("nu", original.get("new_usd_value"))
                            .param("ne", original.get("new_estimated")).param("ev", original.get("evidence_type"))
                            .param("d", original.get("discovered_at")).update();
                }
            }
            assertThat(sync.calculationEvidence(user, reference(v2)).outcome()).as("restored: " + tamper[0])
                    .isInstanceOf(SyncJobApi.EvidenceOutcome.Found.class);
        }
        assertThat(readyDashboard(user).change().kind()).isEqualTo("CORRECTED");
    }

    /**
     * V12's backfill is the publication's difference with its two ids as columns (patch 19): run against every change
     * row the publications wrote (after deleting them, in a transaction rolled back), it writes the same rows.
     */
    @Test
    void theV12BackfillWritesExactlyTheRowsThePublicationWrote() throws Exception {
        verifiedWithEveryRow("verify-backfill");
        String migration = new String(java.util.Objects.requireNonNull(getClass().getResourceAsStream(
                "/db/migration/V12__plan_verification.sql")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int begin = migration.indexOf("-- backfill:begin");
        int end = migration.indexOf("-- backfill:end");
        assertThat(begin).isPositive();
        String backfill = migration.substring(migration.indexOf('\n', begin) + 1, end).trim();
        backfill = backfill.substring(0, backfill.lastIndexOf(';'));
        String statement = backfill;
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status -> {
            List<String> written = changeRows();
            assertThat(written).isNotEmpty();
            jdbc().sql("DELETE FROM sync_plan_calculation_change").update();
            jdbc().sql(statement).update();
            assertThat(changeRows()).containsExactlyElementsOf(written);
            status.setRollbackOnly();
        });
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.testcontainers.postgresql.PostgreSQLContainer postgres;

    /**
     * V12 replaces only the five V11 checks of {@code sync_plan_sync_job} whose vocabulary widens (patch 27): a schema
     * migrated to V11 alone and this one (V12) hold every other V11 check with the same definition under the same name.
     */
    @Test
    void v12KeepsEveryV11CheckItDoesNotWiden() throws Exception {
        String probe = "v11_probe_" + UUID.randomUUID().toString().replace("-", "");
        jdbc().sql("CREATE DATABASE " + probe).update();
        try {
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + probe;
            org.flywaydb.core.Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("11").load().migrate();
            Map<String, String> v11 = new java.util.TreeMap<>();
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(CHECKS)) {
                while (rows.next()) v11.put(rows.getString(1), rows.getString(2));
            }
            // V12 itself, not the current schema: Story 2.14 (V14) widens some of these checks again.
            org.flywaydb.core.Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("12").load().migrate();
            Map<String, String> v12 = new java.util.TreeMap<>();
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
                    var statement = connection.createStatement();
                    var rows = statement.executeQuery(CHECKS)) {
                while (rows.next()) v12.put(rows.getString(1), rows.getString(2));
            }
            var widened = java.util.Set.of("sync_plan_sync_job_trigger_check", "sync_plan_sync_job_mode_check",
                    "sync_plan_sync_job_stage_check", "sync_plan_sync_job_failure_code_check",
                    "sync_plan_sync_job_check12");
            assertThat(v11).containsKeys(widened.toArray(String[]::new)).hasSizeGreaterThan(widened.size());
            for (var check : v11.entrySet())
                if (!widened.contains(check.getKey()))
                    assertThat(v12).as(check.getKey()).containsEntry(check.getKey(), check.getValue());
            assertThat(v12).containsKeys("sync_plan_sync_job_trigger_check", "sync_plan_sync_job_mode_check",
                    "sync_plan_sync_job_stage_check", "sync_plan_sync_job_failure_code_check",
                    "sync_plan_sync_job_review_check").doesNotContainKey("sync_plan_sync_job_check12");
        } finally {
            jdbc().sql("DROP DATABASE IF EXISTS " + probe + " WITH (FORCE)").update();
        }
    }

    private static final String CHECKS = "SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint "
            + "WHERE conrelid='sync_plan_sync_job'::regclass AND contype='c' ORDER BY conname";

    /** Every change row as one comparable line, in one order. */
    private List<String> changeRows() {
        return jdbc().sql("SELECT concat_ws('|',calculation_id,source_event_id,user_id,previous_calculation_id,change_kind,"
                + "late,occurred_at,COALESCE(previous_usd_value,'-'),COALESCE(previous_estimated::text,'-'),"
                + "COALESCE(new_usd_value,'-'),COALESCE(new_estimated::text,'-'),evidence_type,discovered_at) "
                + "FROM sync_plan_calculation_change ORDER BY calculation_id,source_event_id").query(String.class).list();
    }

    /**
     * A continuation that loses its lease in its final sweep is reclaimed there and publishes once, with both
     * watermarks at the {@code tcFinal} the first holder fixed.
     */
    @Test
    void aContinuationReclaimedInItsFinalSweepPublishesOnceAtItsTcFinal() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-sweep", Instant.now().minus(Duration.ofDays(45)));
        moveVerification(user, ready(user).calculationId(), "31 days");
        var v1 = ready(user);
        UUID job;
        slowPageStores(user);
        try {
            job = continuation(user, v1, "verify-sweep-01-key");
            awaitStage(job, "FINAL_SWEEP");
            expireLease(job);
            assertThat(awaitSync(job, 120)).isEqualTo("SUCCEEDED");
        } finally {
            dropSlowPageStores();
        }
        var times = jdbc().sql("SELECT initial_tc,tc,resume_count FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", job).query((rs, n) -> new Object[] {rs.getObject(1, OffsetDateTime.class).toInstant(),
                        rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getInt(3)}).single();
        Instant tcFinal = (Instant) times[1];
        assertThat((Instant) times[0]).isBefore(tcFinal);
        assertThat(times[2]).isEqualTo(1);
        var v2 = ready(user);
        assertThat(v2.pointerVersion()).isEqualTo(2);
        assertThat(v2.cashflowVerifiedThrough()).isEqualTo(tcFinal);
        assertThat(extendedWatermark(v2.calculationId())).isEqualTo(tcFinal);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_official_plan_result_history WHERE user_id=:u")
                .param("u", user).query(Integer.class).single()).as("one publication").isEqualTo(2);
        assertNoChildInProgress(job);
    }

    /** A precision verification reclaimed while collecting goes on from its complete windows and publishes v+1. */
    @Test
    void aVerificationReclaimedWhileCollectingPublishesTheNextVersion() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-reclaimed", Instant.now().minus(Duration.ofDays(60)));
        var v1 = ready(user);
        for (int day : new int[] {5, 20, 35})
            insertSourceEvent(user, "reclaimed-" + day, "TRANSFER_IN", "USDT", "1", v1.startAt().plus(Duration.ofDays(day)));
        UUID job;
        slowPageStores(user);
        try {
            job = precision(user, v1, "verify-reclaimed-01", "GAP_SUSPECTED");
            awaitPages(job, 2);
            expireLease(job);
            assertThat(awaitSync(job, 120)).isEqualTo("SUCCEEDED");
        } finally {
            dropSlowPageStores();
        }
        assertThat(jdbc().sql("SELECT resume_count FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(Integer.class).single()).isEqualTo(1);
        var v2 = ready(user);
        assertThat(v2.pointerVersion()).isEqualTo(2);
        // The three stored events were not observed again: excluded, not deleted.
        assertThat(exclusions(v2.calculationId())).hasSize(3);
        assertNoChildInProgress(job);
    }

    /** A sync that replays a verification's key is a conflict, not a join (the key names one request). */
    @Test
    void aSyncReplayingAVerificationsKeyIsAConflict() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-sharedkey", null);
        var v1 = ready(user);
        UUID job = precision(user, v1, "verify-sharedkey-01", "USER_REQUEST");
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "verify-sharedkey-01"))
                .isEqualTo(new SyncJobApi.SyncAdmission.Refused("IDEMPOTENCY_CONFLICT"));
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
    }

    /**
     * A system verification while the user's precision verification is in progress is refused as "in progress": it
     * joins only the same system request (patch 15).
     */
    @Test
    void aSystemVerificationDoesNotJoinTheUsersVerification() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-system-busy", null);
        var v1 = ready(user);
        UUID job;
        try (AutoCloseable held = holdPlanLock(user)) {
            job = precision(user, v1, "verify-system-busy-01", "USER_REQUEST");
            awaitBlockedOnPlanLock();
            assertThat(sync.admitSystemVerification(user, SyncJobApi.VerificationReason.PLAN_END_PREPARATION, List.of(),
                    "verify-system-busy-02")).isEqualTo(new SyncJobApi.VerificationAdmission.Refused("JOB_IN_PROGRESS"));
        }
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------------------------------ SECOND REVIEW (R1~R8)

    /**
     * Starts a precision verification whose first holder stores the window's first page and then loses its lease
     * while the window is still incomplete (the stub answers that holder's second page slowly); the resumed holder
     * reads the window again. Returns the attempt once it ended.
     */
    private UUID verifiedAcrossALostWindow(UUID user, SyncJobApi.DashboardResult v1, String key) throws Exception {
        UUID job = precision(user, v1, key, "USER_REQUEST");
        awaitPages(job, 1);
        expireLease(job);
        awaitSync(job, 60);
        assertThat(jdbc().sql("SELECT resume_count FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(Integer.class).single()).as("resumed once").isEqualTo(1);
        return job;
    }

    /**
     * R1: an event the first holder's partial page saw, which the resumed holder's re-read of that window no longer
     * sees, is not observed — its observation went with the discarded page. Not official, it is superseded (excluded)
     * and the verification publishes without it.
     */
    @Test
    void anEventOnlyADiscardedPageSawIsSupersededNotNew() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.FLICKER_NEW);
        publishedPlan(user, "verify-flicker-new", Instant.now().minus(Duration.ofDays(3)));
        var v1 = ready(user);
        UUID job = verifiedAcrossALostWindow(user, v1, "verify-flicker-new-01");
        assertThat(jdbc().sql("SELECT status FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(outcomes(job)).containsExactly("flicker-01|SUPERSEDED|false");
        var v2 = ready(user);
        assertThat(v2.flowCount()).isEqualTo(v1.flowCount());
        assertThat(exclusions(v2.calculationId())).hasSize(1);
    }

    /** R1: an official event the re-read no longer sees is missing, even though a discarded page saw it. */
    @Test
    void anOfficialEventOnlyADiscardedPageSawIsMissing() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.FLICKER_OFFICIAL);
        publishedPlan(user, "verify-flicker-official", Instant.now().minus(Duration.ofDays(3)));
        var v1 = ready(user);
        assertThat(v1.flowCount()).isEqualTo(1);
        UUID job = verifiedAcrossALostWindow(user, v1, "verify-flicker-official-01");
        assertThat(jdbc().sql("SELECT status||'|'||failure_code FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", job).query(String.class).single()).isEqualTo("NEEDS_REVIEW|SOURCE_EVENT_MISSING");
        assertThat(outcomes(job)).containsExactly("flicker-01|MISSING|false");
        assertStillV1(user, v1);
    }

    /**
     * R2: a review mark lives on its page — a page the first holder stored with an item that is not an object, which
     * the resumed holder discarded and read again clean, leaves no review behind, persistent or not.
     */
    @Test
    void aReviewMarkOfADiscardedPageDoesNotOutliveIt() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.FLICKER_JUNK);
        publishedPlan(user, "verify-flicker-junk", Instant.now().minus(Duration.ofDays(3)));
        var v1 = ready(user);
        UUID job = verifiedAcrossALostWindow(user, v1, "verify-flicker-junk-01");
        assertThat(jdbc().sql("SELECT status FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_page_evidence e JOIN sync_plan_sync_job j "
                + "ON j.collection_job_id=e.job_id WHERE j.job_id=:j AND e.review_required").param("j", job)
                .query(Integer.class).single()).isZero();
        assertThat(ready(user).pointerVersion()).isEqualTo(2);
        assertThat(readyDashboard(user).verification().availability()).isNotEqualTo("NOT_USEFUL");
    }

    /**
     * R3: in a long gap the continuation reads the same anomaly ranges a precision verification would — an unexplained
     * stored event older than the unconfirmed week is read again. Seen again it is a late addition the continuation
     * publishes; the dashboard said the continuation starts there.
     */
    @Test
    void aContinuationReadsAnOldUnexplainedEventAndPublishesItWhenSeen() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.LATE_EVENT);
        publishedPlan(user, "verify-catchup-late", Instant.now().minus(Duration.ofDays(45)));
        moveVerification(user, ready(user).calculationId(), "31 days");
        var v1 = ready(user);
        Instant lateAt = v1.startAt().plusMillis(1000).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        insertSourceEvent(user, "late-event-01", "TRANSFER_IN", "USDT", "25", lateAt);
        assertThat(readyDashboard(user).verification().catchUpFrom()).isEqualTo(v1.startAt());
        UUID job = continuation(user, v1, "verify-catchup-late-01");
        assertThat(awaitSync(job, 90)).isEqualTo("SUCCEEDED");
        assertThat(ranges(job)).anySatisfy(range -> assertThat(range).startsWith("ANOMALY|" + v1.startAt() + "|"));
        assertThat(outcomes(job)).containsExactly("late-event-01|NEW|true");
        var v2 = ready(user);
        assertThat(v2.flowCount()).isEqualTo(v1.flowCount() + 1);
        assertThat(readyDashboard(user).change().lateAddedCount()).isEqualTo(1);
    }

    /** R3: not seen again, the old stored event is superseded and the continuation still publishes. */
    @Test
    void aContinuationSupersedesAnOldUnexplainedEventThatIsGone() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "verify-catchup-ghost", Instant.now().minus(Duration.ofDays(45)));
        moveVerification(user, ready(user).calculationId(), "31 days");
        var v1 = ready(user);
        insertSourceEvent(user, "catchup-ghost-01", "TRANSFER_IN", "USDT", "2", v1.startAt().plus(Duration.ofDays(2)));
        UUID job = continuation(user, v1, "verify-catchup-ghost-01");
        assertThat(awaitSync(job, 90)).isEqualTo("SUCCEEDED");
        assertThat(outcomes(job)).containsExactly("catchup-ghost-01|SUPERSEDED|false");
        var v2 = ready(user);
        assertThat(v2.pointerVersion()).isEqualTo(2);
        assertThat(exclusions(v2.calculationId())).hasSize(1);
    }

    /**
     * R4: a verification whose late event has no price route ends in a persistent review (CONVERSION_REQUIRES_REVIEW)
     * — asking again reads the same market — so the sync is held (REVIEW_HELD, 409) and asking again is not useful.
     */
    @Test
    void aPermanentPriceHoldEndsTheVerificationInAPersistentReview() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.LATE_UNPRICED);
        publishedPlan(user, "verify-unpriced", Instant.now().minus(Duration.ofDays(3)));
        var v1 = ready(user);
        UUID job = precision(user, v1, "verify-unpriced-01", "USER_REQUEST");
        assertThat(awaitSync(job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT failure_code||'|'||stage FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("CONVERSION_REQUIRES_REVIEW|CONVERSION");
        assertStillV1(user, v1);
        assertVerificationHeld(user, "REVIEW_REQUIRED", "VERIFICATION_REVIEW_REQUIRED");
        var dashboard = readyDashboard(user);
        assertThat(dashboard.verification().availability()).isEqualTo("NOT_USEFUL");
        assertThat(dashboard.sync().availability()).isEqualTo("UNAVAILABLE");
        assertThat(dashboard.sync().reason()).isEqualTo("REVIEW_HELD");
        cooldownPassed(user);
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "verify-unpriced-sync-01"))
                .isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        assertThat(sync.admitPlanVerification(user, SyncJobApi.VerificationKind.PRECISION, reference(v1),
                "verify-unpriced-02")).isEqualTo(new SyncJobApi.VerificationAdmission.Refused("VERIFICATION_NOT_USEFUL"));
    }

    /**
     * R8: another user's verification corrects a price both users' flows share; this user's ordinary sync then
     * converts at the corrected close. The version is CORRECTED by one changed value — not a late event.
     */
    @Test
    void aSharedPriceCorrectionUnderAnOrdinarySyncIsAChangedValueNotALateEvent() throws Exception {
        // Both plans start at the same instant, so both deposits fall in the same minute and share its candle. The
        // minute is one no other test of this JVM uses (the candle cache and the stub's per-minute reads are shared).
        Instant start = Instant.now().minus(Duration.ofDays(4)).minus(Duration.ofMinutes(37))
                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(10);
        UUID user = connect(DevBybitStub.Scenario.REVISED_PRICE);
        publishedPlan(user, "verify-shared-a", start, "CONVERTED_ESTIMATED");
        var v1 = ready(user);
        UUID other = connect(DevBybitStub.Scenario.REVISED_PRICE);
        publishedPlan(other, "verify-shared-b", start, "CONVERTED_ESTIMATED");
        UUID corrected = precision(other, ready(other), "verify-shared-b-verify", "USER_REQUEST");
        assertThat(awaitSync(corrected)).isEqualTo("SUCCEEDED");
        assertThat(flowUsd(ready(other).calculationId(), "revised-01")).isEqualTo("2.5");

        assertThat(awaitSync(manualSync(user, "verify-shared-a-sync"))).isEqualTo("SUCCEEDED");
        var v2 = ready(user);
        assertThat(flowUsd(v1.calculationId(), "revised-01")).isEqualTo("2");
        assertThat(flowUsd(v2.calculationId(), "revised-01")).isEqualTo("2.5");
        var change = readyDashboard(user).change();
        assertThat(change.kind()).isEqualTo("CORRECTED");
        assertThat(change.purpose()).isEqualTo("SYNC");
        assertThat(change.lateChangeCount()).isEqualTo(1);
        assertThat(change.valueChangedCount()).isEqualTo(1);
        assertThat(change.lateAddedCount()).isZero();
        assertThat(change.removedCount()).isZero();
        var evidence = ((SyncJobApi.EvidenceOutcome.Found) sync.calculationEvidence(user, reference(v2)).outcome())
                .evidence().change();
        assertThat(evidence.valueChangedCount()).isEqualTo(1);
        assertThat(evidence.lateAddedCount()).isZero();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private HttpResponseView postVerification(String cookie, @Nullable String csrf, String kind,
            SyncJobApi.DashboardResult shown, String key) throws Exception {
        return postVerification(cookie, csrf, kind, shown.planId(), shown.pointerVersion(), shown.calculationId(), key);
    }

    private HttpResponseView postVerification(String cookie, @Nullable String csrf, String kind, UUID planId,
            long version, UUID calculationId, String key) throws Exception {
        var response = sendJson("POST", VERIFICATION_PATH, bodyOf(kind, planId, version, calculationId), cookie,
                "Origin", OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME, csrf,
                "Idempotency-Key", key);
        return new HttpResponseView(response.statusCode(), response.body(), response.headers());
    }

    private static String bodyOf(String kind, UUID planId, long version, UUID calculationId) {
        return "{\"kind\":\"" + kind + "\",\"planId\":\"" + planId + "\",\"pointerVersion\":" + version
                + ",\"calculationId\":\"" + calculationId + "\"}";
    }

    private static SyncJobApi.EvidenceReference reference(SyncJobApi.DashboardResult shown) {
        return new SyncJobApi.EvidenceReference(shown.planId(), shown.pointerVersion(), shown.calculationId());
    }

    /** A precision verification through the public API, admitted with the expected derived reason. */
    private UUID precision(UUID user, SyncJobApi.DashboardResult shown, String key, String reason) {
        var admission = sync.admitPlanVerification(user, SyncJobApi.VerificationKind.PRECISION, reference(shown), key);
        assertThat(admission).as("admission").isInstanceOf(SyncJobApi.VerificationAdmission.Accepted.class);
        var job = ((SyncJobApi.VerificationAdmission.Accepted) admission).job();
        assertThat(job.reason()).isEqualTo(reason);
        return job.jobId();
    }

    private UUID continuation(UUID user, SyncJobApi.DashboardResult shown, String key) {
        var admission = sync.admitPlanVerification(user, SyncJobApi.VerificationKind.CATCH_UP, reference(shown), key);
        assertThat(admission).as("admission").isInstanceOf(SyncJobApi.VerificationAdmission.Accepted.class);
        return ((SyncJobApi.VerificationAdmission.Accepted) admission).job().jobId();
    }

    private UUID manualSync(UUID user, String key) {
        var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key);
        assertThat(admission).as("admission").isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        return ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
    }

    /**
     * Moves every admission of the user and the plan's publication back past the automatic triggers' five minutes.
     * (The pointer's history moves with it, so the evidence still reads one line of versions.)
     */
    private void automaticDue(UUID user) {
        jdbc().sql("UPDATE sync_plan_sync_job SET admitted_at=admitted_at-interval '6 minutes' WHERE user_id=:u")
                .param("u", user).update();
        jdbc().sql("UPDATE sync_official_plan_result SET published_at=published_at-interval '6 minutes' WHERE user_id=:u")
                .param("u", user).update();
        jdbc().sql("UPDATE sync_official_plan_result_history SET published_at=published_at-interval '6 minutes' "
                + "WHERE user_id=:u").param("u", user).update();
    }

    /** Moves every admission of the user back past the 30-second cooldown. */
    private void cooldownPassed(UUID user) {
        jdbc().sql("UPDATE sync_plan_sync_job SET admitted_at=admitted_at-interval '31 seconds' WHERE user_id=:u")
                .param("u", user).update();
    }

    private SyncJobApi.Dashboard.Ready readyDashboard(UUID user) {
        var dashboard = sync.dashboard(user).dashboard();
        assertThat(dashboard).isInstanceOf(SyncJobApi.Dashboard.Ready.class);
        return (SyncJobApi.Dashboard.Ready) dashboard;
    }

    /** The attempt's planned ranges as {@code KIND|start|end}, in the order they were written. */
    private List<String> ranges(UUID job) {
        return jdbc().sql("SELECT kind,range_start,range_end FROM sync_plan_verification_range WHERE job_id=:j "
                + "ORDER BY seq").param("j", job)
                .query((rs, n) -> rs.getString(1) + "|" + rs.getObject(2, OffsetDateTime.class).toInstant() + "|"
                        + rs.getObject(3, OffsetDateTime.class).toInstant())
                .list();
    }

    /** The attempt's reconciliation outcomes as {@code source|OUTCOME|late}. */
    private List<String> outcomes(UUID job) {
        return jdbc().sql("SELECT source_id||'|'||outcome||'|'||late FROM sync_plan_verification_event WHERE job_id=:j "
                + "ORDER BY source_id").param("j", job).query(String.class).list();
    }

    /** The exclusions of the version's candidate as {@code event|excluded by|reason}. */
    private List<String> exclusions(UUID calculationId) {
        return jdbc().sql("SELECT x.event_id||'|'||x.excluded_by_job_id||'|'||x.reason_code "
                + "FROM sync_initial_candidate_excluded_event x JOIN sync_plan_calculation c "
                + "ON c.candidate_id=x.candidate_id WHERE c.calculation_id=:c").param("c", calculationId)
                .query(String.class).list();
    }

    private String sourceRow(UUID event) {
        return jdbc().sql("SELECT source_id||'|'||event_type||'|'||currency||'|'||cash_flow||'|'||transaction_time||'|'||"
                + "semantic_hash||'|'||first_observed_at FROM sync_initial_source_event WHERE event_id=:e")
                .param("e", event).query(String.class).single();
    }

    private String flowUsd(UUID calculationId, String sourceId) {
        return jdbc().sql("SELECT usd_value FROM sync_plan_calculation_flow WHERE calculation_id=:c AND source_id=:s")
                .param("c", calculationId).param("s", sourceId).query(String.class).single();
    }

    private Instant extendedWatermark(UUID calculationId) {
        return jdbc().sql("SELECT last_extended_verification_at FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c", calculationId).query(OffsetDateTime.class).single().toInstant();
    }

    /** The TRANSFER_IN requests since {@code before}, in order. */
    private static List<BybitStubConfiguration.TransactionQuery> transferIn(int before) {
        var all = BybitStubConfiguration.transactionLogQueries();
        return new ArrayList<>(all.subList(before, all.size()).stream().filter(q -> q.type().equals("TRANSFER_IN"))
                .toList());
    }

    /** The last official version is still v1, with its watermark. */
    private void assertStillV1(UUID user, SyncJobApi.DashboardResult v1) {
        var now = ready(user);
        assertThat(now.pointerVersion()).isEqualTo(1);
        assertThat(now.calculationId()).isEqualTo(v1.calculationId());
        assertThat(now.cashflowVerifiedThrough()).isEqualTo(v1.cashflowVerifiedThrough());
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation WHERE user_id=:u").param("u", user)
                .query(Integer.class).single()).isEqualTo(1);
    }

    /** The verification's review stands on both axes: all six held, no action, and asking again changes nothing. */
    private void assertVerificationHeld(UUID user, String state, String cause) {
        var dashboard = readyDashboard(user);
        var reliability = dashboard.result().trust().inputReliability();
        assertThat(reliability.state()).isEqualTo(state);
        assertThat(reliability.cause()).isEqualTo(cause);
        assertThat(reliability.affectedMetrics()).hasSize(6);
        assertThat(reliability.nextAction()).isEqualTo("NONE");
        assertThat(dashboard.result().trust().freshness().cause()).isEqualTo("LAST_VERIFICATION_NEEDS_REVIEW");
        assertThat(dashboard.nextAction()).isEqualTo("NONE");
        assertThat(dashboard.verification().latest()).isNotNull();
    }

    private String awaitSync(UUID job, int seconds) throws InterruptedException {
        for (int i = 0; i < seconds * 10; i++) {
            String status = jdbc().sql("SELECT status FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                    .query(String.class).optional().orElse("GONE");
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return status;
            Thread.sleep(100);
        }
        throw new AssertionError("the attempt did not finish");
    }

    /** Every page store of the user sleeps 700 ms: the collection lasts long enough to lose its lease mid-way. */
    private void slowPageStores(UUID user) {
        // OR REPLACE: a test that failed before its finally cannot leave the next one unable to create them.
        jdbc().sql("CREATE OR REPLACE FUNCTION slow_verification_pages() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.user_id='" + user + "'::uuid THEN PERFORM pg_sleep(0.7); END IF; RETURN NEW; END $$").update();
        jdbc().sql("CREATE OR REPLACE TRIGGER slow_verification_pages BEFORE INSERT ON sync_initial_page_evidence "
                + "FOR EACH ROW EXECUTE FUNCTION slow_verification_pages()").update();
    }

    private void dropSlowPageStores() {
        jdbc().sql("DROP TRIGGER IF EXISTS slow_verification_pages ON sync_initial_page_evidence").update();
        jdbc().sql("DROP FUNCTION IF EXISTS slow_verification_pages()").update();
    }

    /** Waits until the attempt's collection stored at least {@code pages} pages. */
    private void awaitPages(UUID job, int pages) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            Integer stored = jdbc().sql("SELECT count(*) FROM sync_initial_page_evidence e JOIN sync_initial_collection_job c "
                    + "ON c.job_id=e.job_id WHERE c.plan_sync_job_id=:j").param("j", job).query(Integer.class).single();
            if (stored >= pages) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the collection stored too few pages");
    }

    private void awaitResumeCount(UUID job, int count) throws InterruptedException {
        String state = null;
        for (int i = 0; i < 600; i++) {
            state = jdbc().sql("SELECT status||'|'||resume_count||'|'||COALESCE(lease_until>statement_timestamp(),"
                    + "false)||'|'||COALESCE(stage,'-')||'|'||COALESCE(failure_code,'-') FROM sync_plan_sync_job "
                    + "WHERE job_id=:j").param("j", job).query(String.class).single();
            if (state.startsWith("RUNNING|" + count + "|true|")) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the attempt was not reclaimed: " + state);
    }

    /** Waits until the attempt is running in {@code stage} and has stored a page there. */
    private void awaitStage(UUID job, String stage) throws InterruptedException {
        String state = null;
        for (int i = 0; i < 1200; i++) {
            state = jdbc().sql("SELECT j.status||'|'||COALESCE(j.stage,'-')||'|'||(SELECT count(*) FROM "
                    + "sync_initial_page_evidence e WHERE e.job_id=j.collection_job_id AND e.range_end>j.initial_tc) "
                    + "FROM sync_plan_sync_job j WHERE j.job_id=:j").param("j", job).query(String.class).single();
            if (state.startsWith("RUNNING|" + stage + "|") && !state.endsWith("|0")) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the attempt never reached " + stage + ": " + state);
    }

    /** The holder loses its lease: it expires now, and the worker's heartbeat and claim take it from there. */
    private void expireLease(UUID job) {
        jdbc().sql("UPDATE sync_plan_sync_job SET lease_until=statement_timestamp()-interval '1 second' WHERE job_id=:j")
                .param("j", job).update();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> fields, String name) {
        return (List<Map<String, Object>>) fields.get(name);
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

}
