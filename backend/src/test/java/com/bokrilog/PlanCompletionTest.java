package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.plan.PlanEndApi;
import com.bokrilog.sync.SyncJobApi;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 2.14: the natural completion of a plan that reached its end {@code T}, end to end on the real stack (Docker
 * PostgreSQL, the worker, the local Bybit stub). Each test publishes a first official version (Story 2.7), moves the
 * plan's end into the past by SQL — the database clock is the authority, and no dev surface moves it — and asks for a
 * sync: the transition in the admission's transaction (AC-1), the first post-end observation fixed once (AC-3), the
 * needed ranges verified (AC-2), the comparison and the completion version published atomically (AC-4~AC-6), the
 * post-end syncs that only move the post-end value (AC-7), and deletion, races and the logs (AC-9).
 */
// The plan lock is held by a resource the body never names: holding it is the point.
@SuppressWarnings("try")
class PlanCompletionTest extends PlanSyncTestBase {
    private static final String SYNC_PATH = AuthSecurityConfig.DASHBOARD_PATH + "/sync";
    private static final String WALLET = DevBybitStub.WALLET_BALANCE_PATH;
    private static final String LOG = DevBybitStub.TRANSACTION_LOG_PATH;

    @Autowired PlanEndApi planEnd;

    // ------------------------------------------------------------------------------------------ BEFORE_T / AT_T

    /**
     * AC-1: before {@code T} nothing changes (2.11's admission). Once the database clock reached {@code T}, the GET shows
     * the plan completed with a comparison not judged yet — writing nothing — and the entry sync completes the plan in its
     * own transaction and admits one completion attempt.
     */
    @Test
    void theEntrySyncCompletesAPlanWhoseEndWasReachedAndTheReadWritesNothing() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-entry", null);
        var v1 = ready(user);
        // Before T: an ordinary ready dashboard and an ordinary sync request (deferred by the automatic interval).
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.ENTRY, key("entry", 1)))
                .isInstanceOf(SyncJobApi.SyncAdmission.Deferred.class);
        assertThat(planStatus(user)).isEqualTo("ACTIVE");

        Instant end = reachEnd(user, v1);
        var pending = completed(user);
        assertThat(pending.result().planStatus()).isEqualTo("COMPLETED");
        assertThat(pending.result().pointerVersion()).isEqualTo(1);
        assertThat(pending.result().trust().freshness().state()).isEqualTo("COMPLETED");
        assertThat(pending.result().trust().freshness().cause()).isEqualTo("PLAN_COMPLETED");
        assertThat(pending.result().trust().freshness().nextAction()).isEqualTo("NONE");
        assertThat(pending.nextAction()).isEqualTo("SYNC_FOR_OBSERVATION");
        assertThat(pending.completion()).isEqualTo(new SyncJobApi.PlanCompletionRecord(end, "PENDING", null, null, null,
                null, null, null, true));
        assertThat(pending.result().calculationKind()).isEqualTo("PROGRESS");
        assertThat(pending.postEnd()).isNull();
        assertThat(pending.sync().availability()).isEqualTo("AVAILABLE");
        // A read writes nothing: the plan is still ACTIVE in the database.
        assertThat(planStatus(user)).isEqualTo("ACTIVE");
        assertThat(countOf("plan_completion", user)).isZero();

        var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.ENTRY, key("entry", 2));
        assertThat(admission).isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        var job = ((SyncJobApi.SyncAdmission.Accepted) admission).job();
        assertThat(job.purpose()).isEqualTo("COMPLETION");
        assertThat(job.trigger()).isEqualTo("ENTRY");
        assertThat(planStatus(user)).isEqualTo("COMPLETED");
        assertThat(completionRow(user)).startsWith("PENDING|-|");
        assertThat(awaitSync(job.jobId())).isEqualTo("SUCCEEDED");
        // The same key replays the same attempt; another trigger under it conflicts.
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.ENTRY, key("entry", 2)))
                .isInstanceOf(SyncJobApi.SyncAdmission.Joined.class);
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key("entry", 2)))
                .isEqualTo(new SyncJobApi.SyncAdmission.Refused("IDEMPOTENCY_CONFLICT"));
    }

    // ------------------------------------------------------------------------------------------ CONFIRMED

    /**
     * AC-3·AC-4·AC-6·AC-8: the completion attempt fixes the first post-end observation once (published as the next
     * portfolio snapshot), verifies the needed ranges and publishes the completion version with the comparison in one
     * transaction: v2 at {@code u = T}, the observation as its current value, the target as its plan amount, no required
     * rate, and the record confirmed. The HTTP answer carries the completion facts; the evidence and the adjustment
     * history read v2 with its flows in {@code (t0, T]}; the attempt writes one line without a user or an amount.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aCompletionFixesTheFirstObservationAndPublishesTheConfirmedResult(CapturedOutput output) throws Exception {
        String subject = "completion-confirmed-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-confirmed", null);
        var v1 = ready(user);
        Instant end = reachEnd(user, v1);
        int wallets = BybitStubConfiguration.requestCount(WALLET);

        var accepted = postSync(cookie, csrfTokenOf(cookie), "ENTRY", key("confirmed", 1));
        assertThat(accepted.statusCode()).isEqualTo(202);
        var jobView = nested(topLevelFieldsOf(accepted.body()), "job");
        assertThat(jobView).containsEntry("purpose", "COMPLETION").containsEntry("reason", null);
        UUID job = UUID.fromString((String) jobView.get("jobId"));
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        assertThat(BybitStubConfiguration.requestCount(WALLET) - wallets).as("the wallet is read once").isEqualTo(1);

        var done = completed(user);
        var record = done.completion();
        assertThat(record.comparison()).isEqualTo("CONFIRMED");
        assertThat(record.reason()).isNull();
        assertThat(record.scheduledEndAt()).isEqualTo(end);
        assertThat(record.observedMeasuredAt()).isAfterOrEqualTo(end);
        assertThat(record.observationDelayMillis())
                .isEqualTo(Duration.between(end, record.observedMeasuredAt()).toMillis());
        assertThat(record.postEndCashflowCount()).isZero();
        assertThat(done.nextAction()).isEqualTo("PREPARE_NEXT_PLAN");
        assertThat(done.postEnd()).as("nothing after the first observation yet").isNull();
        var v2 = done.result();
        assertThat(v2.pointerVersion()).isEqualTo(2);
        assertThat(v2.planStatus()).isEqualTo("COMPLETED");
        assertThat(v2.basisAt()).isEqualTo(end);
        assertThat(v2.measuredAt()).isEqualTo(record.observedMeasuredAt());
        assertThat(v2.cashflowVerifiedThrough()).isAfterOrEqualTo(v2.measuredAt());
        Map<String, SyncJobApi.DashboardMetric> metrics = byName(v2.metrics());
        assertThat(new BigDecimal(metrics.get("CURRENT_VALUE").value()))
                .isEqualByComparingTo(new BigDecimal(record.observedValue()));
        assertThat(metrics.get("PLAN_AMOUNT").value()).isEqualTo(metrics.get("ADJUSTED_TARGET").value())
                .isEqualTo(record.completionTarget());
        assertThat(metrics.get("REQUIRED_RATE").unavailableReason()).isEqualTo("REMAINING_PERIOD_NOT_POSITIVE");
        assertThat(metrics.get("PLAN_DIFFERENCE").value()).isNotNull();
        assertThat(metrics.get("GOAL_PROGRESS").unavailableReason()).isNotEqualTo("COMPLETION_NOT_COMPARABLE");
        // The observation is the next portfolio snapshot, and v2 cites it; the plan terms never moved.
        assertThat(jdbc().sql("SELECT calculation_kind||'|'||(portfolio_snapshot_id=:s)::text FROM sync_plan_calculation "
                + "WHERE calculation_id=:c").param("s", v2.portfolioSnapshotId()).param("c", v2.calculationId())
                .query(String.class).single()).isEqualTo("COMPLETION|true");
        assertThat(jdbc().sql("SELECT observed_portfolio_snapshot_id FROM plan_completion WHERE user_id=:u")
                .param("u", user).query(UUID.class).single()).isEqualTo(v2.portfolioSnapshotId());

        // The HTTP answer.
        var answer = send("GET", AuthSecurityConfig.DASHBOARD_PATH, cookie);
        assertThat(answer.statusCode()).isEqualTo(200);
        var fields = topLevelFieldsOf(answer.body());
        assertThat(fields).containsOnlyKeys("state", "nextAction", "result", "sync", "change", "completion")
                .containsEntry("state", "COMPLETED").containsEntry("nextAction", "PREPARE_NEXT_PLAN");
        assertThat(nested(fields, "completion")).containsOnlyKeys("scheduledEndAt", "comparison", "reason",
                "observedValue", "observedMeasuredAt", "observationDelayMillis", "completionTarget",
                "postEndCashflowCount", "retryable").containsEntry("comparison", "CONFIRMED").containsEntry("reason", null)
                .containsEntry("retryable", false);
        assertThat(nested(fields, "result")).containsEntry("calculationKind", "COMPLETION");
        assertThat(nested(nested(fields, "result"), "conditions")).containsEntry("endAt", end.toString());

        // The evidence and the adjustment history read the completion version, its flows in (t0, T].
        var evidence = sync.calculationEvidence(user, new SyncJobApi.EvidenceReference(v2.planId(), 2,
                v2.calculationId()));
        assertThat(evidence.outcome()).isInstanceOf(SyncJobApi.EvidenceOutcome.Found.class);
        var lineage = ((SyncJobApi.EvidenceOutcome.Found) evidence.outcome()).evidence();
        assertThat(lineage.times().tc()).isEqualTo(end);
        assertThat(lineage.change().purpose()).isEqualTo("COMPLETION");
        assertThat(lineage.officialVersions()).hasSize(2);
        assertThat(lineage.officialVersions().getLast().appliedUntil()).isNull();
        var history = sync.adjustmentHistory(user, new SyncJobApi.AdjustmentQuery(v2.planId(), 2, v2.calculationId(),
                SyncJobApi.AdjustmentDirection.ALL, null));
        assertThat(history.outcome()).isInstanceOf(SyncJobApi.AdjustmentOutcome.Found.class);
        var coverage = ((SyncJobApi.AdjustmentOutcome.Found) history.outcome()).history().coverage();
        assertThat(coverage.through()).isEqualTo(end);

        // One ending line for the attempt: its purpose and code, no user, amount or Bybit id.
        // A post-end value, and its HTTP shape.
        passCooldown(user);
        var postEnd = postSync(cookie, csrfTokenOf(cookie), "MANUAL", key("confirmed", 2));
        assertThat(postEnd.statusCode()).isEqualTo(202);
        var postEndJob = nested(topLevelFieldsOf(postEnd.body()), "job");
        assertThat(postEndJob).containsEntry("purpose", "POST_END_VALUE");
        assertThat(awaitSync(UUID.fromString((String) postEndJob.get("jobId")))).isEqualTo("SUCCEEDED");
        var after = topLevelFieldsOf(send("GET", AuthSecurityConfig.DASHBOARD_PATH, cookie).body());
        assertThat(nested(after, "postEnd")).containsOnlyKeys("value", "measuredAt");
        assertThat((String) nested(after, "postEnd").get("value")).matches("-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?");
        assertThat(Instant.parse((String) nested(after, "postEnd").get("measuredAt")))
                .isAfter(record.observedMeasuredAt());
        assertThat(nested(after, "completion")).isEqualTo(nested(fields, "completion"));

        var lines = lines(output, "plan sync finished: job=" + job);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains("purpose=COMPLETION").contains("status=SUCCEEDED")
                .doesNotContain(user.toString()).doesNotContain(uidOf(user)).doesNotContain("equity")
                .doesNotContain("value");
    }

    // ------------------------------------------------------------------------------------------ AFTER_COMPLETION

    /**
     * AC-7·AC-9: after the comparison is judged, syncs are post-end values — Bybit's time and the wallet only, never the
     * Transaction Log — that move only the post-end value; the completion record, its first observation and the
     * completion version stay exactly as they were.
     */
    @Test
    void laterSyncsOnlyMoveThePostEndValue() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-after", null);
        reachEnd(user, ready(user));
        assertThat(awaitSync(admitted(user, SyncJobApi.SyncTrigger.ENTRY, key("after", 1))))
                .isEqualTo("SUCCEEDED");
        var confirmed = completed(user);
        String recordBefore = completionRow(user);
        int calculations = countOf("sync_plan_calculation", user);

        for (int round = 1; round <= 2; round++) {
            passCooldown(user);
            int logs = BybitStubConfiguration.requestCount(LOG);
            int wallets = BybitStubConfiguration.requestCount(WALLET);
            var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key("after", round + 1));
            var job = ((SyncJobApi.SyncAdmission.Accepted) admission).job();
            assertThat(job.purpose()).isEqualTo("POST_END_VALUE");
            assertThat(awaitSync(job.jobId())).isEqualTo("SUCCEEDED");
            assertThat(BybitStubConfiguration.requestCount(LOG) - logs).as("no history").isZero();
            assertThat(BybitStubConfiguration.requestCount(WALLET) - wallets).isEqualTo(1);
            var after = completed(user);
            assertThat(after.completion()).isEqualTo(confirmed.completion());
            assertThat(after.result().calculationId()).isEqualTo(confirmed.result().calculationId());
            assertThat(after.result().pointerVersion()).isEqualTo(2);
            assertThat(after.postEnd()).isNotNull();
            assertThat(after.postEnd().measuredAt()).isAfter(confirmed.completion().observedMeasuredAt());
        }
        assertThat(completionRow(user)).isEqualTo(recordBefore);
        assertThat(countOf("sync_plan_calculation", user)).isEqualTo(calculations);
    }

    // ------------------------------------------------------------------------------------------ DELAY / POST_END_FLOW

    /**
     * AC-4 (CV-11): a first observation more than 24 hours after {@code T} is not comparable. The observation is kept,
     * the completion version is published with its target, and the plan difference and goal progress have no value.
     */
    @Test
    void anObservationMoreThanADayAfterTheEndIsNotComparable() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-delay", Instant.now().minus(Duration.ofDays(10)));
        var v1 = ready(user);
        moveVerification(user, v1.calculationId(), "2 days");
        Instant end = databaseNow().minus(Duration.ofHours(25)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        moveEnd(user, end);
        assertThat(awaitSync(admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("delay", 1))))
                .isEqualTo("SUCCEEDED");
        var done = completed(user);
        assertThat(done.completion().comparison()).isEqualTo("UNAVAILABLE");
        assertThat(done.completion().reason()).isEqualTo("OBSERVATION_DELAY_EXCEEDED");
        assertThat(done.completion().observationDelayMillis()).isGreaterThan(Duration.ofHours(24).toMillis());
        assertThat(done.completion().observedValue()).isNotNull();
        assertThat(done.completion().completionTarget()).isNotNull();
        var metrics = byName(done.result().metrics());
        assertThat(metrics.get("PLAN_DIFFERENCE").value()).isNull();
        assertThat(metrics.get("PLAN_DIFFERENCE").unavailableReason()).isEqualTo("COMPLETION_NOT_COMPARABLE");
        assertThat(metrics.get("GOAL_PROGRESS").unavailableReason()).isEqualTo("COMPLETION_NOT_COMPARABLE");
        assertThat(metrics.get("CURRENT_VALUE").value()).isNotNull();
        assertThat(done.result().basisAt()).isEqualTo(end);
        assertThat(done.nextAction()).isEqualTo("PREPARE_NEXT_PLAN");
    }

    /**
     * AC-4·AC-5: a confirmed deposit in {@code (T, measured]} makes the comparison not computable; the completion
     * version applies only the flows of {@code (t0, T]} — the one after {@code T} is verified and counted, not applied.
     */
    @Test
    void aDepositBetweenTheEndAndTheObservationIsNotComparable() throws Exception {
        UUID user = accountLifecycle().createAccount();
        String uid = store(user, DevBybitStub.Scenario.INCREMENTAL);
        publishedPlan(user, "completion-flow", null);
        var v1 = ready(user);
        // T just before the next deposit after the first version's basis, then wait until it lies in the past.
        Instant deposit = BybitStubConfiguration.nextIncrementalDeposit(uid, v1.basisAt());
        Instant end = deposit.minusMillis(1);
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), deposit.plusSeconds(1)).toMillis()));
        moveEnd(user, end);
        assertThat(awaitSync(admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("flow", 1))))
                .isEqualTo("SUCCEEDED");
        var done = completed(user);
        assertThat(done.completion().comparison()).isEqualTo("UNAVAILABLE");
        assertThat(done.completion().reason()).isEqualTo("POST_END_CASHFLOW");
        assertThat(done.completion().postEndCashflowCount()).isPositive();
        var v2 = done.result();
        assertThat(v2.basisAt()).isEqualTo(end);
        // Every applied flow lies in (t0, T]; the deposit after T is not one of them.
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow WHERE calculation_id=:c "
                + "AND occurred_at>:e").param("c", v2.calculationId())
                .param("e", OffsetDateTime.ofInstant(end, java.time.ZoneOffset.UTC)).query(Integer.class).single())
                .isZero();
        assertThat(byName(v2.metrics()).get("PLAN_DIFFERENCE").unavailableReason())
                .isEqualTo("COMPLETION_NOT_COMPARABLE");
        var history = sync.adjustmentHistory(user, new SyncJobApi.AdjustmentQuery(v2.planId(), v2.pointerVersion(),
                v2.calculationId(), SyncJobApi.AdjustmentDirection.ALL, null));
        assertThat(history.outcome()).isInstanceOf(SyncJobApi.AdjustmentOutcome.Found.class);
        assertThat(((SyncJobApi.AdjustmentOutcome.Found) history.outcome()).history().rows())
                .allMatch(row -> !row.occurredAt().isAfter(end));
    }

    // ------------------------------------------------------------------------------------------ LONG_GAP_AT_T

    /**
     * AC-1·AC-2: a long gap at the transition records the comparison as not computable for the data gap; the attempt
     * only fixes the first observation — no Transaction Log page is read — and publishes no completion version.
     */
    @Test
    void aLongGapAtTheEndOnlyFixesTheObservation() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-gap", Instant.now().minus(Duration.ofDays(60)));
        var v1 = ready(user);
        moveVerification(user, v1.calculationId(), "40 days");
        moveEnd(user, databaseNow().minus(Duration.ofHours(1)));
        int logs = BybitStubConfiguration.requestCount(LOG);
        UUID job = admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("gap", 1));
        assertThat(completionRow(user)).startsWith("UNAVAILABLE|DATA_GAP|");
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        assertThat(BybitStubConfiguration.requestCount(LOG) - logs).as("no history in a data gap").isZero();
        var done = completed(user);
        assertThat(done.completion().comparison()).isEqualTo("UNAVAILABLE");
        assertThat(done.completion().reason()).isEqualTo("DATA_GAP");
        assertThat(done.completion().observedValue()).isNotNull();
        assertThat(done.completion().completionTarget()).isNull();
        assertThat(done.result().pointerVersion()).as("no completion version").isEqualTo(1);
        assertThat(done.nextAction()).isEqualTo("PREPARE_NEXT_PLAN");
        // The next sync is a post-end value.
        passCooldown(user);
        var next = (SyncJobApi.SyncAdmission.Accepted) sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL,
                key("gap", 2));
        assertThat(next.job().purpose()).isEqualTo("POST_END_VALUE");
        assertThat(awaitSync(next.job().jobId())).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------------------------------ TRANSIENT / RETRY

    /**
     * AC-1·AC-9: a provider failure before the observation leaves the comparison pending and the plan completed, with
     * the failure on the dashboard; the next request runs the completion again and confirms it.
     */
    @Test
    void aTransientFailureKeepsThePlanCompletedAndTheComparisonPending() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-transient", null);
        reachEnd(user, ready(user));
        BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.OUTAGE);
        try {
            assertThat(awaitSync(admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("transient", 1))))
                    .isEqualTo("FAILED");
        } finally {
            BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.NORMAL);
        }
        assertThat(planStatus(user)).isEqualTo("COMPLETED");
        assertThat(completionRow(user)).startsWith("PENDING|-|-|");
        var pending = completed(user);
        assertThat(pending.sync().latest().failureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(pending.nextAction()).isEqualTo("SYNC_FOR_OBSERVATION");
        assertThat(pending.result().pointerVersion()).isEqualTo(1);

        passCooldown(user);
        var again = (SyncJobApi.SyncAdmission.Accepted) sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL,
                key("transient", 2));
        assertThat(again.job().purpose()).isEqualTo("COMPLETION");
        assertThat(awaitSync(again.job().jobId())).isEqualTo("SUCCEEDED");
        assertThat(completed(user).completion().comparison()).isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------------------------------ HELD

    /**
     * AC-2 and Story 2.12's rule: an official event the verification no longer observes holds the comparison for input
     * review — a persistent review of that base. The plan stays completed and its last official result stays; the
     * input reliability says it; and asking again does not collect the history again: the request is a post-end value
     * (the comparison stays held, the next action is the following plan).
     */
    @Test
    void aPersistentReviewHoldsTheComparisonAndLaterSyncsOnlyMoveThePostEndValue() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.VANISHING);
        publishedPlan(user, "completion-held", null);
        var v1 = ready(user);
        reachEnd(user, v1);
        assertThat(awaitSync(admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("held", 1))))
                .isEqualTo("NEEDS_REVIEW");
        var held = completed(user);
        assertThat(planStatus(user)).isEqualTo("COMPLETED");
        assertThat(held.completion().comparison()).isEqualTo("HELD");
        assertThat(held.completion().reason()).isEqualTo("INPUT_REVIEW_REQUIRED");
        assertThat(held.completion().observedValue()).isNotNull();
        assertThat(held.completion().retryable()).isFalse();
        assertThat(held.result().calculationId()).isEqualTo(v1.calculationId());
        assertThat(held.nextAction()).isEqualTo("PREPARE_NEXT_PLAN");
        assertThat(held.result().trust().inputReliability().state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(held.result().trust().inputReliability().cause()).isEqualTo("VERIFICATION_REVIEW_REQUIRED");
        assertThat(held.result().trust().inputReliability().nextAction()).isEqualTo("NONE");

        passCooldown(user);
        int logs = BybitStubConfiguration.requestCount(LOG);
        var again = (SyncJobApi.SyncAdmission.Accepted) sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL,
                key("held", 2));
        assertThat(again.job().purpose()).isEqualTo("POST_END_VALUE");
        assertThat(awaitSync(again.job().jobId())).isEqualTo("SUCCEEDED");
        assertThat(BybitStubConfiguration.requestCount(LOG) - logs).as("no history again").isZero();
        var after = completed(user);
        assertThat(after.completion()).isEqualTo(held.completion());
        assertThat(after.postEnd()).isNotNull();
    }

    /**
     * AC-2·AC-3: a comparison held by a review that is not persistent (an attempt that ended in review without any
     * diagnostic of its collection — the shape a malformed provider answer leaves; set here by SQL on an attempt that
     * fixed the observation) is asked again; once the cause is gone the comparison is confirmed with the first
     * observation — its value, time and delay as first fixed.
     */
    @Test
    void aTransientlyHeldComparisonIsConfirmedWithTheFirstObservation() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-transient-held", null);
        reachEnd(user, ready(user));
        failCompletionPublications(user);
        UUID first;
        try {
            first = admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("transient-held", 1));
            assertThat(awaitSync(first)).isEqualTo("FAILED");
        } finally {
            stopFailingCompletionPublications();
        }
        jdbc().sql("UPDATE sync_plan_sync_job SET status='NEEDS_REVIEW',failure_code='SOURCE_REQUIRES_REVIEW' "
                + "WHERE job_id=:j").param("j", first).update();
        jdbc().sql("UPDATE plan_completion SET comparison='HELD',reason='INPUT_REVIEW_REQUIRED' WHERE user_id=:u")
                .param("u", user).update();
        var held = completed(user);
        assertThat(held.completion().retryable()).isTrue();
        assertThat(held.nextAction()).isEqualTo("SYNC_FOR_OBSERVATION");

        passCooldown(user);
        int wallets = BybitStubConfiguration.requestCount(WALLET);
        var again = (SyncJobApi.SyncAdmission.Accepted) sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL,
                "completion-transient-held-2");
        assertThat(again.job().purpose()).isEqualTo("COMPLETION");
        assertThat(awaitSync(again.job().jobId())).isEqualTo("SUCCEEDED");
        assertThat(BybitStubConfiguration.requestCount(WALLET) - wallets).as("the observation is reused").isZero();
        var done = completed(user);
        assertThat(done.completion().comparison()).isEqualTo("CONFIRMED");
        assertThat(done.completion().observedValue()).isEqualTo(held.completion().observedValue());
        assertThat(done.completion().observedMeasuredAt()).isEqualTo(held.completion().observedMeasuredAt());
        assertThat(done.completion().observationDelayMillis()).isEqualTo(held.completion().observationDelayMillis());
        assertThat(done.result().calculationKind()).isEqualTo("COMPLETION");
    }

    /**
     * AC-2: a target out of Bybit's history (here: the plan start moved back beyond two years by SQL, so the planned
     * gap lies out of reach) leaves the comparison not computable — no Transaction Log page is read, no completion
     * version, the pointer stays v1.
     */
    @Test
    void aHistoryOutOfTheProvidersReachIsNotComputable() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-range", null);
        var v1 = ready(user);
        var start = OffsetDateTime.ofInstant(databaseNow().minus(Duration.ofDays(800)), java.time.ZoneOffset.UTC);
        jdbc().sql("UPDATE plan_definition SET t0=:t WHERE user_id=:u").param("t", start).param("u", user).update();
        jdbc().sql("UPDATE sync_plan_calculation SET t0=:t WHERE user_id=:u").param("t", start).param("u", user).update();
        reachEnd(user, v1);
        int logs = BybitStubConfiguration.requestCount(LOG);
        assertThat(awaitSync(admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("range", 1))))
                .isEqualTo("NEEDS_REVIEW");
        assertThat(BybitStubConfiguration.requestCount(LOG) - logs).isZero();
        var done = completed(user);
        assertThat(done.completion().comparison()).isEqualTo("UNAVAILABLE");
        assertThat(done.completion().reason()).isEqualTo("HISTORY_OUT_OF_RANGE");
        assertThat(done.completion().completionTarget()).isNull();
        assertThat(done.completion().retryable()).isFalse();
        assertThat(done.result().pointerVersion()).isEqualTo(1);
        assertThat(done.result().calculationId()).isEqualTo(v1.calculationId());
        assertThat(done.nextAction()).isEqualTo("PREPARE_NEXT_PLAN");
    }

    /**
     * AC-1: after T, a restriction or a connection that ended still lets the request complete the plan (pending), but
     * no attempt is made; the dashboard says why.
     */
    @Test
    void anUnavailableSyncAfterTheEndCompletesThePlanWithoutAnAttempt() throws Exception {
        UUID restricted = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(restricted, "completion-restricted", null);
        reachEnd(restricted, ready(restricted));
        UUID disconnected = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(disconnected, "completion-disconnected", null);
        reachEnd(disconnected, ready(disconnected));
        restrictCommand("plan-sync");
        try {
            assertThat(sync.admitPlanSync(restricted, SyncJobApi.SyncTrigger.ENTRY, key("restricted", 1)))
                    .isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
            assertThat(planStatus(restricted)).isEqualTo("COMPLETED");
            assertThat(completionRow(restricted)).startsWith("PENDING|-|-|");
            assertThat(countOf("sync_plan_sync_job", restricted)).isZero();
            var dashboard = completed(restricted);
            assertThat(dashboard.sync().availability()).isEqualTo("UNAVAILABLE");
            assertThat(dashboard.sync().reason()).isEqualTo("RESTRICTED");
            assertThat(dashboard.nextAction()).isEqualTo("SYNC_FOR_OBSERVATION");
        } finally {
            jdbc().sql("DELETE FROM operations_restriction WHERE command='plan-sync'").update();
        }
        jdbc().sql("UPDATE source_connection SET status='REVOKED' WHERE user_id=:u").param("u", disconnected).update();
        assertThat(sync.admitPlanSync(disconnected, SyncJobApi.SyncTrigger.ENTRY, "completion-disconnected-1"))
                .isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        assertThat(planStatus(disconnected)).isEqualTo("COMPLETED");
        assertThat(countOf("sync_plan_sync_job", disconnected)).isZero();
        var dashboard = completed(disconnected);
        assertThat(dashboard.sync().availability()).isEqualTo("UNAVAILABLE");
        assertThat(dashboard.sync().reason()).isEqualTo("CONNECTION_UNAVAILABLE");
    }

    /**
     * A completed plan closes the plan conditions like an ended one: the state, the preview and the confirmation all
     * answer {@code PLAN_ENDED}, so its P0 and t0 never start a new plan (Story 2.15 opens the following one).
     */
    @Test
    void aCompletedPlanClosesThePlanConditions() throws Exception {
        String subject = "completion-conditions-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-conditions", null);
        reachEnd(user, ready(user));
        assertThat(awaitSync(admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("conditions", 1))))
                .isEqualTo("SUCCEEDED");
        var state = plans.state(user);
        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo(com.bokrilog.plan.PlanConditionApi.PLAN_ENDED);
        assertThat(state.activePlan()).isNull();
        assertThat(plans.preview(user, "completion-conditions-session",
                new com.bokrilog.plan.PlanConditionApi.PreviewInput("2099-01-01T00:00:00", "DAILY", "0.1"))).isEmpty();
        assertThat(plans.confirm(user, "completion-conditions-session", "unknown-ref", "completion-conditions-c1")
                .reason()).isEqualTo(com.bokrilog.plan.PlanConditionApi.PLAN_ENDED);
        var preview = sendJson("POST", AuthSecurityConfig.PLAN_CONDITIONS_PATH + "/preview",
                "{\"endAtSeoul\":\"2099-01-01T00:00:00\",\"period\":\"DAILY\",\"targetRate\":\"0.1\"}", cookie,
                "Origin", OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME,
                csrfTokenOf(cookie));
        assertThat(preview.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(preview.body())).containsEntry("code", "PLAN_ENDED");
        assertThat(countOf("plan_condition_intent", user)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ RACES

    /**
     * AC-6: an early end and a completion are told apart under the same locks. A completed plan cannot be ended early
     * (404), and a plan that ended early is not completed by a later sync (no active plan). A sync in progress when
     * {@code T} arrives ends {@code PLAN_PERIOD_ENDED} without writing, and the next request completes the plan.
     */
    @Test
    void anEarlyEndAndACompletionLeaveOneTerminalStatus() throws Exception {
        UUID completedUser = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(completedUser, "completion-race-done", null);
        var c1 = ready(completedUser);
        reachEnd(completedUser, c1);
        assertThat(awaitSync(admitted(completedUser, SyncJobApi.SyncTrigger.MANUAL, key("race", 1))))
                .isEqualTo("SUCCEEDED");
        UUID completionVersion = completed(completedUser).result().calculationId();
        var refused = new TransactionTemplate(manager).execute(status -> {
            try {
                planEnd.end(completedUser, new PlanEndApi.Request(PlanEndApi.Reason.USER_REQUEST, c1.planId(), 2,
                        completionVersion), "completion-race-end-0001");
                return "ENDED";
            } catch (PlanEndApi.Refused refusal) {
                status.setRollbackOnly();
                return refusal.code();
            }
        });
        assertThat(refused).isEqualTo(PlanEndApi.PLAN_NOT_FOUND);
        assertThat(planStatus(completedUser)).isEqualTo("COMPLETED");

        UUID endedUser = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(endedUser, "completion-race-ended", null);
        var e1 = ready(endedUser);
        new TransactionTemplate(manager).executeWithoutResult(status -> planEnd.end(endedUser,
                new PlanEndApi.Request(PlanEndApi.Reason.USER_REQUEST, e1.planId(), 1, e1.calculationId()),
                "completion-race-end-0002"));
        moveEnd(endedUser, e1.basisAt().plusMillis(1));
        assertThat(sync.admitPlanSync(endedUser, SyncJobApi.SyncTrigger.MANUAL, key("race", 2)))
                .isEqualTo(new SyncJobApi.SyncAdmission.Refused("NO_ACTIVE_PLAN"));
        assertThat(planStatus(endedUser)).isEqualTo("ENDED_EARLY");
        assertThat(countOf("plan_completion", endedUser)).isZero();
    }

    @Test
    void aSyncInProgressWhenTheEndArrivesEndsWithoutWritingAndTheNextRequestCompletes() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-late", null);
        var v1 = ready(user);
        Instant end = databaseNow().plusSeconds(6).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        moveEnd(user, end);
        UUID late;
        try (AutoCloseable held = holdPlanLock(user)) {
            late = admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("late", 1));
            pollUntil(Duration.ofSeconds(15), () -> databaseNow(), now -> now.isAfter(end.plusMillis(200)));
            // An entry request while it runs joins it: nothing transitions.
            assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.ENTRY, key("late", 2)))
                    .isInstanceOf(SyncJobApi.SyncAdmission.Joined.class);
            assertThat(planStatus(user)).isEqualTo("ACTIVE");
        }
        assertThat(awaitSync(late)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", late)
                .query(String.class).single()).isEqualTo("PLAN_PERIOD_ENDED");
        assertThat(completed(user).result().calculationId()).isEqualTo(v1.calculationId());
        assertThat(countOf("sync_plan_calculation", user)).isEqualTo(1);
        // B2: the progress sync admitted seconds ago, before the transition, does not hold the first observation back.
        UUID next = admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("late", 3));
        assertThat(planStatus(user)).isEqualTo("COMPLETED");
        assertThat(awaitSync(next)).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------------------------------ ROLLBACK

    /**
     * AC-6: a failure inside the completion's publication rolls it all back — no completion version, the pointer and
     * the record as they were (pending, the observation kept) — and the attempt fails retryable.
     */
    @Test
    void aFailingCompletionPublicationRollsEverythingBack() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-rollback", null);
        var v1 = ready(user);
        reachEnd(user, v1);
        failCompletionPublications(user);
        try {
            assertThat(awaitSync(admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("rollback", 1))))
                    .isEqualTo("FAILED");
        } finally {
            stopFailingCompletionPublications();
        }
        var pending = completed(user);
        assertThat(pending.completion().comparison()).isEqualTo("PENDING");
        assertThat(pending.completion().observedValue()).as("the observation committed before").isNotNull();
        assertThat(pending.result().calculationId()).isEqualTo(v1.calculationId());
        assertThat(countOf("sync_plan_calculation", user)).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_official_plan_result_history WHERE user_id=:u")
                .param("u", user).query(Integer.class).single()).isEqualTo(1);

        // AC-3·AC-9: the retry reuses the first observation — no wallet is read again — and confirms with it.
        passCooldown(user);
        int wallets = BybitStubConfiguration.requestCount(WALLET);
        assertThat(awaitSync(admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("rollback", 2))))
                .isEqualTo("SUCCEEDED");
        assertThat(BybitStubConfiguration.requestCount(WALLET) - wallets).as("the observation is reused").isZero();
        var done = completed(user).completion();
        assertThat(done.comparison()).isEqualTo("CONFIRMED");
        assertThat(done.observedMeasuredAt()).isEqualTo(pending.completion().observedMeasuredAt());
        assertThat(done.observedValue()).isEqualTo(pending.completion().observedValue());
    }

    // ------------------------------------------------------------------------------------------ DELETION

    /**
     * AC-9: a deletion that began wins over the transition (no write); the erasure removes the completion record, the
     * completion attempts and the completion version, and keeps another user's.
     */
    @Test
    void erasureRemovesTheCompletionAndADeletionWinsOverTheTransition() throws Exception {
        UUID kept = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(kept, "completion-kept", null);
        reachEnd(kept, ready(kept));
        assertThat(awaitSync(admitted(kept, SyncJobApi.SyncTrigger.MANUAL, key("kept", 1))))
                .isEqualTo("SUCCEEDED");

        UUID deleting = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(deleting, "completion-deleting", null);
        reachEnd(deleting, ready(deleting));
        accountDeletion().beginDeletion(deleting);
        assertThat(sync.admitPlanSync(deleting, SyncJobApi.SyncTrigger.MANUAL, key("deleting", 1)))
                .isInstanceOf(SyncJobApi.SyncAdmission.NotPermitted.class);
        assertThat(planStatus(deleting)).isEqualTo("ACTIVE");
        assertThat(countOf("plan_completion", deleting)).isZero();

        UUID erased = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(erased, "completion-erased", null);
        reachEnd(erased, ready(erased));
        assertThat(awaitSync(admitted(erased, SyncJobApi.SyncTrigger.MANUAL, key("erased", 1))))
                .isEqualTo("SUCCEEDED");
        accountDeletion().beginDeletion(erased);
        portfolio.erasePortfolioData(erased);
        sync.eraseSyncData(erased);
        plans.erasePlanData(erased);
        source.eraseSourceData(erased);
        accountDeletion().erasePayload(erased);
        for (String table : List.of("plan_completion", "plan_definition", "sync_plan_calculation",
                "sync_plan_sync_job", "sync_source_snapshot", "sync_official_plan_result"))
            assertThat(countOf(table, erased)).as(table).isZero();
        assertThat(countOf("plan_completion", kept)).isEqualTo(1);
        assertThat(completed(kept).completion().comparison()).isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------------------------------ SCHEMA

    /**
     * V14 replaces only the checks whose vocabulary widens (the plan status, the calculation's basis rules split by kind,
     * the metric reasons, the attempt's purpose, mode, stage, shape and success, the source snapshot's origin) and keeps
     * every other check of those tables exactly as V13 left it. The completion record pairs with its completed plan.
     */
    @Test
    void v14KeepsEveryCheckItDoesNotWidenAndPairsTheRecordWithItsPlan() throws Exception {
        String probe = "v13_probe_" + UUID.randomUUID().toString().replace("-", "");
        jdbc().sql("CREATE DATABASE " + probe).update();
        Map<String, String> v13 = new java.util.TreeMap<>();
        try {
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + probe;
            org.flywaydb.core.Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("13").load().migrate();
            try (var connection = java.sql.DriverManager.getConnection(url, postgres.getUsername(),
                    postgres.getPassword()); var statement = connection.createStatement();
                    var rows = statement.executeQuery(CHECKS)) {
                while (rows.next()) v13.put(rows.getString(1), rows.getString(2));
            }
        } finally {
            jdbc().sql("DROP DATABASE IF EXISTS " + probe + " WITH (FORCE)").update();
        }
        Map<String, String> v14 = new java.util.TreeMap<>();
        jdbc().sql(CHECKS).query((rs, n) -> v14.put(rs.getString(1), rs.getString(2))).list();
        List<String> replaced = List.of("plan_definition.plan_definition_status_check",
                "sync_plan_calculation.sync_plan_calculation_check", "sync_plan_calculation.sync_plan_calculation_check1",
                "sync_plan_calculation_metric.sync_plan_calculation_metric_unavailable_reason_check",
                "sync_plan_calculation_metric.sync_plan_calculation_metric_check2",
                "sync_plan_sync_job.sync_plan_sync_job_purpose_check", "sync_plan_sync_job.sync_plan_sync_job_mode_check",
                "sync_plan_sync_job.sync_plan_sync_job_stage_check",
                "sync_plan_sync_job.sync_plan_sync_job_purpose_shape_check", "sync_plan_sync_job.sync_plan_sync_job_check9",
                "sync_plan_sync_job.sync_plan_sync_job_failure_code_check",
                "sync_plan_sync_job.sync_plan_sync_job_check11",
                "sync_source_snapshot.sync_source_snapshot_one_origin");
        assertThat(v13).containsKeys(replaced.toArray(String[]::new));
        // The replaced checks are the ones V14 means to replace, by their V13 definitions, not only by their names.
        assertThat(v13.get("sync_plan_calculation.sync_plan_calculation_check"))
                .isEqualTo("CHECK ((cashflow_verified_through = basis_at))");
        assertThat(v13.get("sync_plan_calculation.sync_plan_calculation_check1"))
                .isEqualTo("CHECK (((t0 < basis_at) AND (basis_at < end_at)))");
        assertThat(v13.get("sync_plan_calculation_metric.sync_plan_calculation_metric_check2"))
                .isEqualTo("CHECK (((unavailable_reason IS NULL) OR (metric = ANY (ARRAY['REQUIRED_RATE'::text, "
                        + "'GOAL_PROGRESS'::text]))))");
        assertThat(v13.get("sync_plan_calculation_metric.sync_plan_calculation_metric_unavailable_reason_check"))
                .contains("'REMAINING_PERIOD_NOT_POSITIVE'").doesNotContain("COMPLETION_NOT_COMPARABLE");
        assertThat(v13.get("sync_plan_sync_job.sync_plan_sync_job_check9")).startsWith("CHECK (((status <> 'SUCCEEDED'")
                .contains("(calculation_id IS NOT NULL)").contains("(published_pointer_version = (base_pointer_version + 1))");
        assertThat(v13.get("sync_plan_sync_job.sync_plan_sync_job_check11")).startsWith("CHECK (((status <> 'FAILED'")
                .contains("'SYNC_FAILED'").doesNotContain("PROVIDER_END_NOT_REACHED");
        assertThat(v13.get("sync_plan_sync_job.sync_plan_sync_job_failure_code_check"))
                .contains("'PLAN_PERIOD_ENDED'").doesNotContain("PROVIDER_END_NOT_REACHED");
        assertThat(v13.get("sync_plan_sync_job.sync_plan_sync_job_purpose_check"))
                .isEqualTo("CHECK ((purpose = ANY (ARRAY['SYNC'::text, 'VERIFY'::text, 'CATCH_UP'::text])))");
        assertThat(v13.get("sync_plan_sync_job.sync_plan_sync_job_mode_check")).doesNotContain("COMPLETION");
        assertThat(v13.get("sync_plan_sync_job.sync_plan_sync_job_stage_check")).doesNotContain("OBSERVATION");
        assertThat(v13.get("sync_plan_sync_job.sync_plan_sync_job_purpose_shape_check")).doesNotContain("COMPLETION");
        assertThat(v13.get("sync_source_snapshot.sync_source_snapshot_one_origin"))
                .isEqualTo("CHECK (((job_id IS NULL) <> (initial_candidate_id IS NULL)))");
        assertThat(v13.get("plan_definition.plan_definition_status_check"))
                .isEqualTo("CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'ENDED_EARLY'::text])))");
        for (var check : v13.entrySet())
            if (!replaced.contains(check.getKey()))
                assertThat(v14).as(check.getKey()).containsEntry(check.getKey(), check.getValue());
        assertThat(v14).doesNotContainKeys("sync_plan_calculation.sync_plan_calculation_check",
                "sync_plan_calculation.sync_plan_calculation_check1", "sync_plan_sync_job.sync_plan_sync_job_check9",
                "sync_plan_sync_job.sync_plan_sync_job_check11");
        assertThat(v14.get("sync_plan_sync_job.sync_plan_sync_job_failed_code_check"))
                .contains("PROVIDER_END_NOT_REACHED");
        assertThat(v14.get("plan_definition.plan_definition_status_check")).contains("'COMPLETED'")
                .contains("'ENDED_EARLY'");
        assertThat(v14.get("sync_plan_calculation_metric.sync_plan_calculation_metric_check2"))
                .contains("COMPLETION_NOT_COMPARABLE");

        // The record pairs with its completed plan: it cannot cite an active plan, and the plan cannot go back.
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "completion-schema", null);
        var v1 = ready(user);
        assertThatSqlFails(() -> jdbc().sql("INSERT INTO plan_completion(plan_id,user_id,completed_at,transitioned_at,"
                + "comparison) VALUES (:p,:u,now(),now(),'PENDING')").param("p", v1.planId()).param("u", user).update());
        reachEnd(user, v1);
        assertThat(awaitSync(admitted(user, SyncJobApi.SyncTrigger.MANUAL, key("schema", 1))))
                .isEqualTo("SUCCEEDED");
        assertThatSqlFails(() -> jdbc().sql("UPDATE plan_definition SET status='ACTIVE' WHERE user_id=:u")
                .param("u", user).update());
        assertThatSqlFails(() -> jdbc().sql("UPDATE plan_completion SET comparison='HELD',reason=NULL WHERE user_id=:u")
                .param("u", user).update());
        assertThat(planStatus(user)).isEqualTo("COMPLETED");
    }

    @Autowired org.testcontainers.postgresql.PostgreSQLContainer postgres;

    private static final String CHECKS = "SELECT conrelid::regclass::text||'.'||conname,pg_get_constraintdef(oid) "
            + "FROM pg_constraint WHERE conrelid IN ('plan_definition'::regclass,'sync_plan_calculation'::regclass,"
            + "'sync_plan_calculation_metric'::regclass,'sync_plan_sync_job'::regclass,'sync_source_snapshot'::regclass) "
            + "AND contype='c' ORDER BY 1";

    private static void assertThatSqlFails(Runnable statement) {
        org.assertj.core.api.Assertions.assertThatThrownBy(statement::run)
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------------------------------ helpers

    /**
     * Moves the plan's end {@code T} just into the past, after the first version's basis (so that version stays a
     * progress version of the plan), and returns it.
     */
    private Instant reachEnd(UUID user, SyncJobApi.DashboardResult v1) throws InterruptedException {
        Instant end = databaseNow().minusMillis(100).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (!end.isAfter(v1.basisAt())) {
            Thread.sleep(Duration.between(end, v1.basisAt()).toMillis() + 300);
            end = databaseNow().minusMillis(100).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        }
        moveEnd(user, end);
        return end;
    }

    private String uidOf(UUID user) {
        return jdbc().sql("SELECT a.provider_account_ref FROM source_provider_account a JOIN source_connection c "
                + "ON c.provider_account_id=a.provider_account_id WHERE c.user_id=:u AND c.status='ACTIVE'")
                .param("u", user).query(String.class).single();
    }

    /** A test-only trigger: the comparison write of the user's completion publication fails, rolling it all back. */
    private void failCompletionPublications(UUID user) {
        jdbc().sql("CREATE OR REPLACE FUNCTION fail_plan_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.user_id='" + user + "'::uuid AND NEW.comparison<>'PENDING' THEN RAISE EXCEPTION 'injected'; "
                + "END IF; RETURN NEW; END $$").update();
        jdbc().sql("CREATE OR REPLACE TRIGGER fail_plan_completion BEFORE UPDATE ON plan_completion "
                + "FOR EACH ROW EXECUTE FUNCTION fail_plan_completion()").update();
    }

    private void stopFailingCompletionPublications() {
        jdbc().sql("DROP TRIGGER IF EXISTS fail_plan_completion ON plan_completion").update();
        jdbc().sql("DROP FUNCTION IF EXISTS fail_plan_completion()").update();
    }

    private SyncJobApi.Dashboard.Completed completed(UUID user) {
        var dashboard = sync.dashboard(user).dashboard();
        assertThat(dashboard).isInstanceOf(SyncJobApi.Dashboard.Completed.class);
        return (SyncJobApi.Dashboard.Completed) dashboard;
    }

    /**
     * An idempotency key built at run time ({@code completion-<name>-key-0001}). Written out as a literal, a key of
     * this shape trips gitleaks' {@code generic-api-key} rule; the rule stays as it is and the literal goes.
     */
    private static String key(String name, int number) {
        return "completion-" + name + "-key-" + String.format(Locale.ROOT, "%04d", number);
    }

    private UUID admitted(UUID user, SyncJobApi.SyncTrigger trigger, String key) {
        var admission = sync.admitPlanSync(user, trigger, key);
        assertThat(admission).as("admission").isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        return ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
    }

    /**
     * Moves the completed plan's earlier admissions a minute back: the next manual request is past the 30-second
     * cooldown that the completion's own last admission rightly imposes (2.11's rule, after the transition).
     */
    private void passCooldown(UUID user) {
        jdbc().sql("UPDATE sync_plan_sync_job SET admitted_at=admitted_at-interval '1 minute' WHERE user_id=:u")
                .param("u", user).update();
    }

    private String planStatus(UUID user) {
        return jdbc().sql("SELECT status FROM plan_definition WHERE user_id=:u").param("u", user)
                .query(String.class).single();
    }

    /** The completion record as one line: comparison, reason and observed value, nullable columns as {@code -}. */
    private String completionRow(UUID user) {
        return jdbc().sql("SELECT comparison||'|'||COALESCE(reason,'-')||'|'||COALESCE(observed_value,'-')||'|'||"
                + "COALESCE(observed_measured_at::text,'-')||'|'||COALESCE(completion_calculation_id::text,'-') "
                + "FROM plan_completion WHERE user_id=:u").param("u", user).query(String.class).single();
    }

    private static Map<String, SyncJobApi.DashboardMetric> byName(List<SyncJobApi.DashboardMetric> metrics) {
        Map<String, SyncJobApi.DashboardMetric> named = new java.util.HashMap<>();
        metrics.forEach(metric -> named.put(metric.metric(), metric));
        return named;
    }

    private HttpResponseView postSync(String cookie, @Nullable String csrf, String trigger, String key)
            throws Exception {
        var response = sendJson("POST", SYNC_PATH, "{\"trigger\":\"" + trigger + "\"}", cookie, "Origin",
                OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME, csrf,
                "Idempotency-Key", key);
        return new HttpResponseView(response.statusCode(), response.body(), response.headers());
    }
}
