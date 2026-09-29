package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceProvider;
import com.bokrilog.source.SourceVerification;
import com.bokrilog.sync.SyncJobApi;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 2.11: the user-triggered incremental sync of an active plan, end to end on the real stack (Docker
 * PostgreSQL, the worker, the local Bybit stub). Each test publishes a first official version (Story 2.7) and
 * then syncs it: the admission (SYNC-UX-1.0), the windows (ERD-1.0.0 §2.2), the atomic publication of the next
 * version (AD-23), the last official version kept through every failure, the races with deletion, restriction
 * and pointer moves (AD-16), and the lineage the evidence and history keep reading (Stories 2.9, 2.10).
 */
// The plan lock is held by a resource the body never names: holding it is the point.
@SuppressWarnings("try")
class IncrementalSyncTest extends AuthFlowTestBase {
    private static final String SYNC_PATH = AuthSecurityConfig.DASHBOARD_PATH + "/sync";

    @Autowired private SourceConnectionApi source;
    @Autowired private PortfolioMembershipApi portfolio;
    @Autowired private SyncJobApi sync;
    @Autowired private PlanConditionApi plans;
    @Autowired private PlatformTransactionManager manager;

    // ------------------------------------------------------------------------------------------ MANUAL

    /**
     * AC-1: a manual sync after new deposits publishes pointer v2 in one step — the new current value, metrics,
     * axes and cash flows — continuing v1 on the plan's unchanged terms; repeated requests join the one attempt;
     * the evidence and the adjustment history answer 200 for v2; and the attempt ends with exactly one line.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aManualSyncPublishesTheNextVersionAtomicallyAndKeepsItsLineageReadable(CapturedOutput output)
            throws Exception {
        String subject = "incremental-manual-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        store(user, DevBybitStub.Scenario.INCREMENTAL);
        UUID intent = publishedPlan(user, "incremental-manual", null);
        var v1 = ready(user);
        assertThat(v1.pointerVersion()).isEqualTo(1);
        String planRow = planRow(user);
        // New deposits arrive every 10 seconds: wait for at least one past the first version's tc.
        Thread.sleep(Math.max(0, Duration.between(Instant.now(), v1.basisAt().plusSeconds(11)).toMillis()));
        String csrf = csrfTokenOf(cookie);
        // Not reset: the scenario's deposits are counted from this UID's first wallet read.
        int queriesBefore = BybitStubConfiguration.transactionLogQueries().size();
        UUID job;
        try (AutoCloseable held = holdPlanLock(user)) {
            var accepted = postSync(cookie, csrf, "MANUAL", "incremental-manual-key-0001");
            assertThat(accepted.statusCode()).isEqualTo(202);
            assertThat(accepted.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            var fields = topLevelFieldsOf(accepted.body());
            assertThat(fields).containsOnlyKeys("outcome", "job", "retryAfterSeconds")
                    .containsEntry("outcome", "ACCEPTED").containsEntry("retryAfterSeconds", null);
            var jobView = nested(fields, "job");
            assertThat(jobView).containsOnlyKeys("jobId", "trigger", "status", "stage", "failureCode", "admittedAt",
                    "finishedAt").containsEntry("trigger", "MANUAL").containsEntry("status", "QUEUED");
            job = UUID.fromString((String) jobView.get("jobId"));
            // The attempt runs up to the publication and waits there for the plan lock.
            awaitBlockedOnPlanLock();
            // Another tab, a repeated click and a replay join the one attempt; nothing new is made.
            var other = postSync(cookie, csrf, "MANUAL", "incremental-manual-key-0002");
            assertThat(other.statusCode()).isEqualTo(200);
            assertThat(topLevelFieldsOf(other.body())).containsEntry("outcome", "JOINED");
            assertThat(nested(topLevelFieldsOf(other.body()), "job")).containsEntry("jobId", job.toString())
                    .containsEntry("status", "RUNNING").containsEntry("stage", "PUBLICATION");
            var entry = postSync(cookie, csrf, "ENTRY", "incremental-manual-key-0003");
            assertThat(topLevelFieldsOf(entry.body())).containsEntry("outcome", "JOINED");
            var replay = postSync(cookie, csrf, "MANUAL", "incremental-manual-key-0001");
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(topLevelFieldsOf(replay.body())).containsEntry("outcome", "JOINED");
            var conflict = postSync(cookie, csrf, "VISIBILITY", "incremental-manual-key-0001");
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(topLevelFieldsOf(conflict.body())).containsEntry("code", "IDEMPOTENCY_CONFLICT");
            assertThat(countOf("sync_plan_sync_job", user)).isEqualTo(1);
            // While it runs the last official version stays, and the dashboard says a sync is in progress.
            var during = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
            assertThat(during.result().pointerVersion()).isEqualTo(1);
            assertThat(during.result().trust().freshness().state()).isEqualTo("SYNCING");
            assertThat(during.nextAction()).isEqualTo("WAIT_FOR_SYNC");
            assertThat(during.sync().availability()).isEqualTo("IN_PROGRESS");
        }
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");

        var v2 = ready(user);
        assertThat(v2.pointerVersion()).isEqualTo(2);
        assertThat(v2.calculationId()).isNotEqualTo(v1.calculationId());
        assertThat(v2.flowCount()).as("the new deposits are applied").isGreaterThan(v1.flowCount());
        assertThat(new java.math.BigDecimal(v2.metrics().getFirst().value()))
                .isGreaterThan(new java.math.BigDecimal(v1.metrics().getFirst().value()));
        assertThat(v2.basisAt()).isAfter(v1.basisAt());
        assertThat(v2.cashflowVerifiedThrough()).isEqualTo(v2.basisAt());
        // The plan is the same immutable row with the same terms; its intent is not touched.
        assertThat(planRow(user)).isEqualTo(planRow);
        assertThat(v2.planId()).isEqualTo(v1.planId());
        assertThat(v2.principal()).isEqualTo(v1.principal());
        assertThat(v2.startAt()).isEqualTo(v1.startAt());
        assertThat(v2.endAt()).isEqualTo(v1.endAt());
        assertThat(v2.targetRate()).isEqualTo(v1.targetRate());
        // One new source snapshot, one new portfolio snapshot at the next sequence, both pointers moved together.
        assertThat(jdbc().sql("SELECT c.publication_sequence FROM portfolio_current_snapshot c JOIN portfolio p "
                + "ON p.portfolio_id=c.portfolio_id WHERE p.user_id=:u").param("u", user).query(Long.class).single())
                .isEqualTo(3L);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_source_snapshot WHERE user_id=:u AND initial_candidate_id "
                + "IS NOT NULL").param("u", user).query(Integer.class).single()).isEqualTo(2);
        // The version continues v1, with both watermarks: a normal sync keeps the extended one.
        var watermarks = jdbc().sql("SELECT previous_calculation_id,cashflow_verified_through,"
                + "last_extended_verification_at FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c", v2.calculationId()).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class),
                        rs.getObject(2, OffsetDateTime.class).toInstant(),
                        rs.getObject(3, OffsetDateTime.class).toInstant()}).single();
        assertThat(watermarks[0]).isEqualTo(v1.calculationId());
        assertThat(watermarks[1]).isEqualTo(v2.basisAt());
        assertThat(watermarks[2]).as("NORMAL keeps the first extended verification").isEqualTo(v1.basisAt());
        assertThat(jdbc().sql("SELECT mode||'|'||stage FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("NORMAL|PUBLICATION");
        // A plan younger than a day starts its windows at t0: every type's window started there, up to tc.
        var all = BybitStubConfiguration.transactionLogQueries();
        var queries = all.subList(queriesBefore, all.size());
        assertThat(queries).extracting(BybitStubConfiguration.TransactionQuery::type)
                .containsExactlyInAnyOrder("TRANSFER_IN", "TRANSFER_OUT");
        assertThat(queries).allSatisfy(q -> {
            assertThat(q.startTime()).isEqualTo(v1.startAt().toEpochMilli());
            assertThat(q.endTime()).isEqualTo(v2.basisAt().toEpochMilli());
        });
        // Its children are the stage rows of Stories 2.4~2.6, all finished.
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_collection_job WHERE plan_sync_job_id=:j "
                + "AND status='INPUTS_COMPLETE'").param("j", job).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_classification_job WHERE plan_sync_job_id=:j "
                + "AND status='COMPLETE'").param("j", job).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_job WHERE plan_sync_job_id=:j "
                + "AND status='CONVERTED'").param("j", job).query(Integer.class).single()).isEqualTo(1);

        // The ready dashboard carries the sync state beside the result, and a manual sync now cools down.
        var dashboard = get(AuthSecurityConfig.DASHBOARD_PATH, cookie);
        assertThat(topLevelFieldsOf(dashboard.body())).containsOnlyKeys("state", "nextAction", "result", "sync");
        var syncView = nested(topLevelFieldsOf(dashboard.body()), "sync");
        assertThat(syncView).containsOnlyKeys("availability", "reason", "retryAfterSeconds", "latest")
                .containsEntry("availability", "COOLDOWN").containsEntry("reason", null);
        assertThat(((Number) syncView.get("retryAfterSeconds")).intValue()).isBetween(1, 30);
        assertThat(nested(syncView, "latest")).containsEntry("jobId", job.toString())
                .containsEntry("status", "SUCCEEDED").containsEntry("failureCode", null);
        int calls = BybitStubConfiguration.requestCount();
        var cooldown = postSync(cookie, csrf, "MANUAL", "incremental-manual-key-0004");
        assertThat(topLevelFieldsOf(cooldown.body())).containsEntry("outcome", "COOLDOWN");
        assertThat(BybitStubConfiguration.requestCount()).as("no provider call").isEqualTo(calls);

        // Story 2.9: the v2 evidence is one lineage; the versions are v1 (with its end) and the current v2.
        var evidence = get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence?planId=" + v2.planId() + "&pointerVersion=2"
                + "&calculationId=" + v2.calculationId(), cookie);
        assertThat(evidence.statusCode()).as(evidence.body()).isEqualTo(200);
        var evidenceFields = topLevelFieldsOf(evidence.body());
        var range = nested(nested(evidenceFields, "inputs"), "sourceRange");
        assertThat(range).containsEntry("rangeStart", v2.startAt().toString())
                .containsEntry("rangeEnd", v2.basisAt().toString())
                .containsEntry("windowStart", v2.startAt().toString());
        @SuppressWarnings("unchecked")
        var official = (List<Map<String, Object>>) nested(evidenceFields, "versions").get("official");
        assertThat(official).hasSize(2);
        assertThat(official.get(0)).containsEntry("pointerVersion", 1)
                .containsEntry("calculationId", v1.calculationId().toString())
                .containsEntry("publishedAt", v1.publishedAt().toString())
                .containsEntry("appliedUntil", v2.publishedAt().toString()).containsEntry("current", false);
        assertThat(official.get(1)).containsEntry("pointerVersion", 2)
                .containsEntry("calculationId", v2.calculationId().toString())
                .containsEntry("appliedUntil", null).containsEntry("current", true);
        // Story 2.10: the history lists every applied flow of (t0, tc2], numbered 1..n.
        var history = get(AuthSecurityConfig.DASHBOARD_PATH + "/adjustments?planId=" + v2.planId()
                + "&pointerVersion=2&calculationId=" + v2.calculationId(), cookie);
        assertThat(history.statusCode()).as(history.body()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) topLevelFieldsOf(history.body()).get("rows");
        assertThat(rows).extracting(row -> ((Number) row.get("sequence")).intValue())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, v2.flowCount()).boxed().toList());
        assertThat(nested(topLevelFieldsOf(history.body()), "coverage"))
                .containsEntry("from", v2.startAt().toString()).containsEntry("through", v2.basisAt().toString());
        // The version the screen held is no longer current: its references are the same common 404.
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence?planId=" + v1.planId() + "&pointerVersion=1"
                + "&calculationId=" + v1.calculationId(), cookie).statusCode()).isEqualTo(404);

        // Observation: one admission line per request, exactly one ending line for the attempt.
        var admissions = lines(output, "plan sync admission: ");
        assertThat(admissions).anySatisfy(line -> assertThat(line).endsWith("")
                .contains("trigger=MANUAL result=ACCEPTED status=202 latencyMs="));
        assertThat(admissions).anySatisfy(line -> assertThat(line).contains("trigger=VISIBILITY result=IDEMPOTENCY_CONFLICT "
                + "status=409"));
        assertThat(admissions).anySatisfy(line -> assertThat(line).contains("trigger=MANUAL result=COOLDOWN status=200"));
        var endings = lines(output, "plan sync finished: job=" + job + " ");
        assertThat(endings).singleElement().satisfies(line -> assertThat(line).contains("trigger=MANUAL mode=NORMAL "
                + "status=SUCCEEDED stage=PUBLICATION code=- latencyMs=").endsWith(
                "policies=BYBIT-CASHFLOW-1.0,BYBIT-PRICE-1.0,CALC-1.0,CALENDAR-SEOUL-1.0"));
        for (String line : lines(output, "plan sync ")) {
            for (String forbidden : List.of(user.toString(), intent.toString(), v2.planId().toString(),
                    v2.metrics().getFirst().value(), "incremental-manual-key")) {
                assertThat(line).as(forbidden).doesNotContain(forbidden);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ COOLDOWN / NOT_DUE

    /**
     * SYNC-UX-1.0: automatic triggers wait 5 minutes after the later of the last admission and the publication,
     * a manual one 30 seconds after the last admission — whatever that attempt's outcome — and none of them calls
     * the provider or makes an attempt while it waits.
     */
    @Test
    void automaticTriggersWaitForTheIntervalAndAManualOneForTheCooldownWithoutProviderCalls() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "incremental-due", null);
        BybitStubConfiguration.resetRequestCount();
        for (SyncJobApi.SyncTrigger trigger : List.of(SyncJobApi.SyncTrigger.ENTRY, SyncJobApi.SyncTrigger.VISIBILITY)) {
            var deferred = sync.admitPlanSync(user, trigger, "incremental-due-" + trigger + "-01");
            assertThat(deferred).isInstanceOf(SyncJobApi.SyncAdmission.Deferred.class);
            var notDue = (SyncJobApi.SyncAdmission.Deferred) deferred;
            assertThat(notDue.outcome()).isEqualTo("NOT_DUE");
            assertThat(notDue.latest()).isNull();
            assertThat(notDue.retryAfterSeconds()).isBetween(250L, 300L);
        }
        assertThat(countOf("sync_plan_sync_job", user)).isZero();
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        // Five minutes after the publication an entry is due.
        jdbc().sql("UPDATE sync_official_plan_result SET published_at=published_at-interval '6 minutes' WHERE user_id=:u")
                .param("u", user).update();
        var entry = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.ENTRY, "incremental-due-entry-02");
        assertThat(entry).isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        UUID job = ((SyncJobApi.SyncAdmission.Accepted) entry).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        assertThat(jdbc().sql("SELECT trigger FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("ENTRY");
        // Right after: a manual sync cools down (30 s after that admission), an automatic one is not due.
        int calls = BybitStubConfiguration.requestCount();
        var manual = (SyncJobApi.SyncAdmission.Deferred) sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL,
                "incremental-due-manual-03");
        assertThat(manual.outcome()).isEqualTo("COOLDOWN");
        assertThat(manual.latest().jobId()).isEqualTo(job);
        assertThat(manual.retryAfterSeconds()).isBetween(1L, 30L);
        var visibility = (SyncJobApi.SyncAdmission.Deferred) sync.admitPlanSync(user,
                SyncJobApi.SyncTrigger.VISIBILITY, "incremental-due-visible-04");
        assertThat(visibility.outcome()).isEqualTo("NOT_DUE");
        var state = ((SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard()).sync();
        assertThat(state.availability()).isEqualTo("COOLDOWN");
        assertThat(state.retryAfterSeconds()).isBetween(1L, 30L);
        assertThat(BybitStubConfiguration.requestCount()).isEqualTo(calls);
        assertThat(countOf("sync_plan_sync_job", user)).isEqualTo(1);
        // 30 seconds after the admission a manual sync is admitted again.
        jdbc().sql("UPDATE sync_plan_sync_job SET admitted_at=admitted_at-interval '31 seconds' WHERE job_id=:j")
                .param("j", job).update();
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "incremental-due-manual-05"))
                .isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
    }

    // ------------------------------------------------------------------------------------------ LONG_GAP / WINDOWS

    /**
     * ERD-1.0.0 §2.2: more than 30 days since the verification creates no attempt and calls nothing — the result
     * stays readable and the choice is explained. Up to 30 days, the whole gap is collected in contiguous windows
     * of at most seven days from the extended overlap's start to the closed {@code tc}.
     */
    @Test
    void aLongGapCallsNothingAndAShorterGapIsCollectedWholeInSevenDayWindows() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "incremental-gap", Instant.now().minus(Duration.ofDays(45)));
        var v1 = ready(user);
        moveVerification(user, v1.calculationId(), "31 days");
        BybitStubConfiguration.resetRequestCount();
        var gap = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "incremental-gap-manual-01");
        assertThat(gap).isEqualTo(new SyncJobApi.SyncAdmission.Deferred("LONG_GAP", null, null));
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.ENTRY, "incremental-gap-entry-02"))
                .isEqualTo(new SyncJobApi.SyncAdmission.Deferred("LONG_GAP", null, null));
        assertThat(countOf("sync_plan_sync_job", user)).isZero();
        assertThat(BybitStubConfiguration.requestCount()).as("no provider call, not even the time").isZero();
        var dashboard = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
        assertThat(dashboard.result().pointerVersion()).as("the last official result stays readable").isEqualTo(1);
        assertThat(dashboard.sync().availability()).isEqualTo("LONG_GAP");
        assertThat(dashboard.result().trust().freshness().cause()).isEqualTo("LONG_GAP");
        assertThat(dashboard.nextAction()).isEqualTo("CHOOSE_CONTINUATION");

        // 29 days: collected whole, from the seven-day overlap (the last extended verification is as old).
        moveVerification(user, v1.calculationId(), "29 days");
        Instant verified = jdbc().sql("SELECT cashflow_verified_through FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c", v1.calculationId()).query(OffsetDateTime.class).single().toInstant();
        var accepted = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "incremental-gap-manual-03");
        UUID job = ((SyncJobApi.SyncAdmission.Accepted) accepted).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        var v2 = ready(user);
        Instant tc = v2.basisAt();
        assertThat(jdbc().sql("SELECT mode FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("EXTENDED");
        assertWindows(BybitStubConfiguration.transactionLogQueries(), verified.minus(Duration.ofDays(7)), tc);
        assertThat(jdbc().sql("SELECT last_extended_verification_at FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c", v2.calculationId()).query(OffsetDateTime.class).single().toInstant())
                .as("an extended sync moves the extended watermark to tc").isEqualTo(tc);

        // A worker already found the gap longer than 30 days with Bybit's time, from this very base: the next
        // admission and the dashboard treat it as a long gap too, whatever the database clock says.
        jdbc().sql("INSERT INTO sync_plan_sync_job(job_id,user_id,plan_id,portfolio_id,intent_id,membership_id,"
                + "connection_id,provider_account_id,trigger,idempotency_key,base_calculation_id,base_pointer_version,"
                + "base_candidate_id,base_verified_through,base_extended_verification_at,t0,end_at,tc,stage,status,"
                + "failure_code,admitted_at,finished_at) SELECT gen_random_uuid(),j.user_id,j.plan_id,j.portfolio_id,"
                + "j.intent_id,j.membership_id,j.connection_id,j.provider_account_id,'ENTRY','incremental-gap-worker-01',"
                + "c.calculation_id,2,c.candidate_id,c.cashflow_verified_through,c.last_extended_verification_at,j.t0,"
                + "j.end_at,c.cashflow_verified_through+interval '31 days','TIME','BLOCKED','LONG_GAP',"
                + "now()-interval '1 hour',now()-interval '1 hour' FROM sync_plan_sync_job j JOIN sync_plan_calculation c "
                + "ON c.calculation_id=:calc WHERE j.job_id=:j").param("calc", v2.calculationId()).param("j", job).update();
        assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "incremental-gap-manual-04"))
                .isInstanceOf(SyncJobApi.SyncAdmission.Deferred.class)
                .extracting(admission -> ((SyncJobApi.SyncAdmission.Deferred) admission).outcome()).isEqualTo("LONG_GAP");
        var afterWorker = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
        assertThat(afterWorker.sync().availability()).isEqualTo("LONG_GAP");
        assertThat(afterWorker.nextAction()).isEqualTo("CHOOSE_CONTINUATION");
    }

    /**
     * AC-3: a normal sync re-reads the 24 hours before the verification; once seven days have passed since the
     * last extended verification, the next sync reads seven days back and moves that watermark.
     */
    @Test
    void aNormalSyncOverlapsADayAndTheSyncSevenDaysAfterTheExtendedOneOverlapsAWeek() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "incremental-overlap", Instant.now().minus(Duration.ofDays(10)));
        var v1 = ready(user);
        BybitStubConfiguration.resetRequestCount();
        UUID normal = ((SyncJobApi.SyncAdmission.Accepted) sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL,
                "incremental-overlap-01")).job().jobId();
        assertThat(awaitSync(normal)).isEqualTo("SUCCEEDED");
        var v2 = ready(user);
        assertWindows(BybitStubConfiguration.transactionLogQueries(), v1.basisAt().minus(Duration.ofHours(24)),
                v2.basisAt());
        assertThat(jdbc().sql("SELECT last_extended_verification_at FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c", v2.calculationId()).query(OffsetDateTime.class).single().toInstant())
                .isEqualTo(v1.basisAt());
        // Stories 2.9 and 2.10 read v2, whose own windows started a day before v1's verification, after t0.
        var evidence2 = evidenceOf(user, v2);
        assertThat(evidence2.sourceRange().rangeStart()).isEqualTo(v2.startAt());
        assertThat(evidence2.sourceRange().rangeEnd()).isEqualTo(v2.basisAt());
        assertThat(evidence2.sourceRange().windowStart()).isEqualTo(v1.basisAt().minus(Duration.ofHours(24)))
                .isAfter(v2.startAt());
        assertThat(evidence2.officialVersions()).extracting(SyncJobApi.CalculationEvidence.OfficialVersion::appliedUntil)
                .containsExactly(v2.publishedAt(), null);
        assertThat(historyOf(user, v2)).isInstanceOf(SyncJobApi.AdjustmentOutcome.Found.class);
        // The version is tied to the sync's own children: another child breaks the lineage (503, never in part).
        jdbc().sql("UPDATE sync_plan_sync_job SET classification_job_id=:other WHERE job_id=:j")
                .param("other", UUID.randomUUID()).param("j", normal).update();
        assertThat(sync.calculationEvidence(user, new SyncJobApi.EvidenceReference(v2.planId(), 2, v2.calculationId()))
                .outcome()).isInstanceOf(SyncJobApi.EvidenceOutcome.Unavailable.class);
        jdbc().sql("UPDATE sync_plan_sync_job SET classification_job_id=(SELECT job_id FROM "
                + "sync_cashflow_classification_job WHERE plan_sync_job_id=:j) WHERE job_id=:j").param("j", normal).update();
        assertThat(evidenceOf(user, v2).calculationId()).isEqualTo(v2.calculationId());

        // Seven days since the last extended verification: the next sync is the extended one.
        jdbc().sql("UPDATE sync_plan_calculation SET last_extended_verification_at=cashflow_verified_through"
                + "-interval '7 days' WHERE calculation_id=:c").param("c", v2.calculationId()).update();
        jdbc().sql("UPDATE sync_plan_sync_job SET admitted_at=admitted_at-interval '1 minute' WHERE job_id=:j")
                .param("j", normal).update();
        BybitStubConfiguration.resetRequestCount();
        UUID extended = ((SyncJobApi.SyncAdmission.Accepted) sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL,
                "incremental-overlap-02")).job().jobId();
        assertThat(awaitSync(extended)).isEqualTo("SUCCEEDED");
        var v3 = ready(user);
        assertThat(v3.pointerVersion()).isEqualTo(3);
        assertWindows(BybitStubConfiguration.transactionLogQueries(), v2.basisAt().minus(Duration.ofDays(7)),
                v3.basisAt());
        assertThat(jdbc().sql("SELECT mode FROM sync_plan_sync_job WHERE job_id=:j").param("j", extended)
                .query(String.class).single()).isEqualTo("EXTENDED");
        assertThat(jdbc().sql("SELECT last_extended_verification_at FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c", v3.calculationId()).query(OffsetDateTime.class).single().toInstant())
                .isEqualTo(v3.basisAt());
        // v3's lineage: three contiguous official versions, (t0, tc3] collected, its windows from a week back.
        var evidence3 = evidenceOf(user, v3);
        assertThat(evidence3.sourceRange().rangeStart()).isEqualTo(v3.startAt());
        assertThat(evidence3.sourceRange().windowStart()).isEqualTo(v2.basisAt().minus(Duration.ofDays(7)));
        assertThat(evidence3.officialVersions()).extracting(SyncJobApi.CalculationEvidence.OfficialVersion::pointerVersion)
                .containsExactly(1L, 2L, 3L);
        assertThat(evidence3.officialVersions()).extracting(SyncJobApi.CalculationEvidence.OfficialVersion::appliedUntil)
                .containsExactly(v2.publishedAt(), v3.publishedAt(), null);
        assertThat(evidence3.officialVersions()).extracting(SyncJobApi.CalculationEvidence.OfficialVersion::current)
                .containsExactly(false, false, true);
        assertThat(historyOf(user, v3)).isInstanceOf(SyncJobApi.AdjustmentOutcome.Found.class);
    }

    private SyncJobApi.CalculationEvidence evidenceOf(UUID user, SyncJobApi.DashboardResult shown) {
        var read = sync.calculationEvidence(user, new SyncJobApi.EvidenceReference(shown.planId(), shown.pointerVersion(),
                shown.calculationId()));
        assertThat(read.outcome()).isInstanceOf(SyncJobApi.EvidenceOutcome.Found.class);
        return ((SyncJobApi.EvidenceOutcome.Found) read.outcome()).evidence();
    }

    private SyncJobApi.AdjustmentOutcome historyOf(UUID user, SyncJobApi.DashboardResult shown) {
        return sync.adjustmentHistory(user, new SyncJobApi.AdjustmentQuery(shown.planId(), shown.pointerVersion(),
                shown.calculationId(), SyncJobApi.AdjustmentDirection.ALL, null)).outcome();
    }

    /**
     * AD-16: the attempt's heartbeat renews its own lease and the leases of its running children, and a child
     * whose lease expired is left to its owner — the independent Story 2.4~2.6 claims never take it. The heartbeat
     * is driven directly (the service is package-private to its module, so it is reached through the context).
     */
    @Test
    void theHeartbeatRenewsTheAttemptAndItsChildrenAndAnExpiredChildIsLeftToItsOwner() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "incremental-heartbeat", null);
        var v1 = ready(user);
        var base = jdbc().sql("SELECT c.candidate_id,c.classification_id,c.membership_id,c.connection_id,"
                + "c.provider_account_id,d.intent_id FROM sync_plan_calculation c JOIN plan_definition d "
                + "ON d.plan_id=c.plan_id WHERE c.calculation_id=:c").param("c", v1.calculationId())
                .query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                        rs.getObject(6, UUID.class)}).single();
        UUID portfolioId = jdbc().sql("SELECT portfolio_id FROM portfolio WHERE user_id=:u").param("u", user)
                .query(UUID.class).single();
        UUID job = UUID.randomUUID();
        // A running attempt no worker holds, its lease two seconds from expiry, with two running children.
        jdbc().sql("INSERT INTO sync_plan_sync_job(job_id,user_id,plan_id,portfolio_id,intent_id,membership_id,"
                + "connection_id,provider_account_id,trigger,idempotency_key,base_calculation_id,base_pointer_version,"
                + "base_candidate_id,base_verified_through,base_extended_verification_at,t0,end_at,tc,stage,status,"
                + "fencing_generation,lease_until,heartbeat_at) VALUES (:j,:u,:plan,:p,:i,:m,:c,:a,'MANUAL',"
                + "'incremental-heartbeat-key-01',:calc,1,:cand,:verified,:verified,:t0,:end,now(),'CONVERSION',"
                + "'RUNNING',1,now()+interval '2 seconds',now())")
                .param("j", job).param("u", user).param("plan", v1.planId()).param("p", portfolioId)
                .param("i", base[5]).param("m", base[2]).param("c", base[3]).param("a", base[4])
                .param("calc", v1.calculationId()).param("cand", base[0])
                .param("verified", OffsetDateTime.ofInstant(v1.cashflowVerifiedThrough(), ZoneOffset.UTC))
                .param("t0", OffsetDateTime.ofInstant(v1.startAt(), ZoneOffset.UTC))
                .param("end", OffsetDateTime.ofInstant(v1.endAt(), ZoneOffset.UTC)).update();
        UUID collection = UUID.randomUUID();
        jdbc().sql("INSERT INTO sync_initial_collection_job(job_id,user_id,portfolio_id,intent_id,connection_id,"
                + "provider_account_id,idempotency_key,status,t0,tc,fencing_generation,lease_until,heartbeat_at,"
                + "plan_sync_job_id,window_start,base_candidate_id) VALUES (:id,:u,:p,:i,:c,:a,'plan-sync-heartbeat-01',"
                + "'RUNNING',:t0,now(),1,now()+interval '2 seconds',now(),:j,:t0,:cand)")
                .param("id", collection).param("u", user).param("p", portfolioId).param("i", base[5])
                .param("c", base[3]).param("a", base[4]).param("t0", OffsetDateTime.ofInstant(v1.startAt(), ZoneOffset.UTC))
                .param("j", job).param("cand", base[0]).update();
        UUID conversion = UUID.randomUUID();
        jdbc().sql("INSERT INTO sync_cashflow_conversion_job(job_id,user_id,portfolio_id,intent_id,connection_id,"
                + "provider_account_id,classification_id,input_scope_hash,flow_count,policy_version,"
                + "calculation_policy_version,idempotency_key,status,fencing_generation,lease_until,heartbeat_at,"
                + "plan_sync_job_id) VALUES (:id,:u,:p,:i,:c,:a,:cls,:hash,0,'BYBIT-PRICE-1.0','CALC-1.0',"
                + "'plan-sync-heartbeat-02','RUNNING',1,now()+interval '2 seconds',now(),:j)")
                .param("id", conversion).param("u", user).param("p", portfolioId).param("i", base[5])
                .param("c", base[3]).param("a", base[4]).param("cls", base[1]).param("hash", "e".repeat(64))
                .param("j", job).update();

        assertThat(heartbeat(job, user, v1, base, portfolioId)).as("the lease is still held").isTrue();
        for (String table : List.of("sync_plan_sync_job", "sync_initial_collection_job", "sync_cashflow_conversion_job")) {
            UUID id = table.equals("sync_plan_sync_job") ? job
                    : table.equals("sync_initial_collection_job") ? collection : conversion;
            assertThat(jdbc().sql("SELECT lease_until>now()+interval '20 seconds' FROM " + table + " WHERE job_id=:j")
                    .param("j", id).query(Boolean.class).single()).as(table + " renewed").isTrue();
        }
        // The children's leases expire while their owner still holds its own: the independent claims skip them.
        jdbc().sql("UPDATE sync_initial_collection_job SET lease_until=now()-interval '1 second' WHERE job_id=:j")
                .param("j", collection).update();
        jdbc().sql("UPDATE sync_cashflow_conversion_job SET lease_until=now()-interval '1 second' WHERE job_id=:j")
                .param("j", conversion).update();
        Thread.sleep(2_500);
        for (var child : List.of(new Object[] {"sync_initial_collection_job", collection},
                new Object[] {"sync_cashflow_conversion_job", conversion})) {
            assertThat(jdbc().sql("SELECT status||'|'||fencing_generation FROM " + child[0] + " WHERE job_id=:j")
                    .param("j", child[1]).query(String.class).single()).as(child[0] + " untouched").isEqualTo("RUNNING|1");
        }
        // Done: the owner ends, and its children with it.
        jdbc().sql("UPDATE sync_initial_collection_job SET status='CANCELLED',failure_code='SYNC_INTERRUPTED',"
                + "lease_until=NULL,finished_at=now() WHERE job_id=:j").param("j", collection).update();
        jdbc().sql("UPDATE sync_cashflow_conversion_job SET status='CONVERSION_REQUIRED',failure_code="
                + "'CONVERSION_INTERRUPTED',lease_until=NULL,finished_at=now() WHERE job_id=:j").param("j", conversion)
                .update();
        jdbc().sql("UPDATE sync_plan_sync_job SET status='FAILED',failure_code='SYNC_INTERRUPTED',lease_until=NULL,"
                + "finished_at=now() WHERE job_id=:j").param("j", job).update();
    }

    @Autowired private org.springframework.context.ApplicationContext context;

    /** {@code PlanSyncService#heartbeat} on a lease for the attempt, reached reflectively (package-private). */
    private boolean heartbeat(UUID job, UUID user, SyncJobApi.DashboardResult v1, UUID[] base, UUID portfolioId)
            throws ReflectiveOperationException {
        Class<?> service = Class.forName("com.bokrilog.sync.internal.PlanSyncService");
        Class<?> leaseType = Class.forName("com.bokrilog.sync.internal.PlanSyncRepository$Lease");
        var constructor = leaseType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object lease = constructor.newInstance(job, user, v1.planId(), portfolioId, base[5], base[2], base[3], base[4],
                "MANUAL", v1.calculationId(), 1L, base[0], v1.cashflowVerifiedThrough(), v1.cashflowVerifiedThrough(),
                v1.startAt(), v1.endAt(), 1L);
        var method = service.getDeclaredMethod("heartbeat", leaseType);
        method.setAccessible(true);
        return (Boolean) method.invoke(context.getBean(service), lease);
    }

    // ------------------------------------------------------------------------------------------ REVIEW / FAIL

    /**
     * AC-5: a stored event of (t0, tc] that neither the previous official candidate nor this sync's windows
     * explain holds the publication: no new version, both watermarks unchanged, the input reliability "review
     * required" for all six metrics and the freshness "not fresh" with a new sync as the next action.
     */
    @Test
    void anUnexplainedStoredEventHoldsThePublicationForReview() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "incremental-review", Instant.now().minus(Duration.ofDays(3)));
        var v1 = ready(user);
        // An event before the window start (verified - 24 h) that the first candidate did not have.
        insertSourceEvent(user, "unexplained-late", "TRANSFER_IN", "USDT", "5", v1.startAt().plus(Duration.ofHours(1)));
        UUID job = manual(user, "incremental-review-01");
        assertThat(awaitSync(job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT failure_code||'|'||stage FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("SOURCE_REQUIRES_REVIEW|COLLECTION");
        assertThat(jdbc().sql("SELECT status FROM sync_initial_collection_job WHERE plan_sync_job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle b JOIN sync_initial_collection_job j "
                + "ON j.job_id=b.job_id WHERE j.plan_sync_job_id=:j").param("j", job).query(Integer.class).single())
                .as("no candidate").isZero();
        assertUnchanged(user, v1);
        var dashboard = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
        var reliability = dashboard.result().trust().inputReliability();
        assertThat(reliability.state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(reliability.cause()).isEqualTo("SYNC_REVIEW_REQUIRED");
        assertThat(reliability.affectedMetrics()).hasSize(6);
        assertThat(dashboard.result().trust().freshness().cause()).isEqualTo("LAST_SYNC_NEEDS_REVIEW");
        assertThat(dashboard.nextAction()).isEqualTo("SYNC");
        assertThat(dashboard.sync().latest().status()).isEqualTo("NEEDS_REVIEW");
    }

    /**
     * AC-7: a failure at the time, conversion or publication stage ends the attempt with its closed code, keeps
     * the last official version and its values, and is not retried.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"TIME,FAILED,PROVIDER_UNAVAILABLE", "CONVERSION,FAILED,CONVERSION_REQUIRED",
        "PUBLICATION,FAILED,SYNC_FAILED"})
    void aFailureAtAnyStageKeepsTheLastOfficialVersionAndIsNotRetried(String stage, String status, String code)
            throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "incremental-fail-" + stage.toLowerCase(java.util.Locale.ROOT), null);
        var v1 = ready(user);
        if (stage.equals("CONVERSION")) {
            // A carried withdrawal of an asset without a trading route: its conversion is held.
            UUID candidate = jdbc().sql("SELECT candidate_id FROM sync_plan_calculation WHERE calculation_id=:c")
                    .param("c", v1.calculationId()).query(UUID.class).single();
            UUID event = insertSourceEvent(user, "halt-carried", "TRANSFER_OUT", "HALT", "-2.5",
                    v1.basisAt().minusMillis(500));
            jdbc().sql("INSERT INTO sync_initial_candidate_event(candidate_id,event_id) VALUES (:c,:e)")
                    .param("c", candidate).param("e", event).update();
        }
        UUID job;
        if (stage.equals("TIME")) BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.OUTAGE);
        if (stage.equals("PUBLICATION")) {
            jdbc().sql("CREATE FUNCTION fail_sync_publication() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF NEW.user_id='" + user + "'::uuid THEN RAISE EXCEPTION 'injected sync failure detail'; END IF; "
                    + "RETURN NEW; END $$").update();
            jdbc().sql("CREATE TRIGGER fail_sync_publication BEFORE INSERT ON sync_plan_calculation "
                    + "FOR EACH ROW EXECUTE FUNCTION fail_sync_publication()").update();
        }
        try {
            job = manual(user, "incremental-fail-01");
            assertThat(awaitSync(job)).isEqualTo(status);
        } finally {
            BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.NORMAL);
            if (stage.equals("PUBLICATION")) {
                jdbc().sql("DROP TRIGGER fail_sync_publication ON sync_plan_calculation").update();
                jdbc().sql("DROP FUNCTION fail_sync_publication()").update();
            }
        }
        assertThat(jdbc().sql("SELECT failure_code||'|'||stage FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo(code + "|" + stage);
        assertUnchanged(user, v1);
        assertNoChildInProgress(job);
        var dashboard = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
        assertThat(dashboard.result().metrics()).isEqualTo(v1.metrics());
        assertThat(dashboard.result().trust().freshness().state()).isEqualTo("STALE");
        assertThat(dashboard.result().trust().freshness().cause()).isEqualTo("LAST_SYNC_UNSUCCESSFUL");
        assertThat(dashboard.nextAction()).isEqualTo("SYNC");
        // No automatic retry.
        int calls = BybitStubConfiguration.requestCount();
        Thread.sleep(1_500);
        assertThat(countOf("sync_plan_sync_job", user)).isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount()).isEqualTo(calls);
    }

    // ------------------------------------------------------------------------------------------ RACE

    /**
     * AC-8: a deletion, restriction, connection removal, ended membership or moved pointer that lands while the
     * attempt waits to publish wins: nothing is published, no watermark moves, and the attempt is blocked with
     * the closed code.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"DELETION,NOT_PERMITTED", "RESTRICTION,NOT_PERMITTED", "CONNECTION,NOT_PERMITTED",
        "MEMBERSHIP,MEMBERSHIP_CHANGED", "POINTER,POINTER_CONFLICT"})
    void aChangeBeforeThePublicationWinsOverTheLateWorker(String change, String code) throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "incremental-race-" + change.toLowerCase(java.util.Locale.ROOT), null);
        var v1 = ready(user);
        UUID job;
        try (AutoCloseable held = holdPlanLock(user)) {
            job = manual(user, "incremental-race-01");
            awaitBlockedOnPlanLock();
            switch (change) {
                case "DELETION" -> accountDeletion().beginDeletion(user);
                case "RESTRICTION" -> jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) "
                        + "VALUES (gen_random_uuid(),'plan-sync')").update();
                case "CONNECTION" -> jdbc().sql("UPDATE source_connection SET status='REVOKED' WHERE user_id=:u")
                        .param("u", user).update();
                case "MEMBERSHIP" -> jdbc().sql("UPDATE portfolio_source_membership SET ended_at=now() WHERE portfolio_id "
                        + "IN (SELECT portfolio_id FROM portfolio WHERE user_id=:u)").param("u", user).update();
                default -> jdbc().sql("UPDATE sync_official_plan_result SET pointer_version=pointer_version+10 "
                        + "WHERE user_id=:u").param("u", user).update();
            }
        }
        assertThat(awaitSync(job)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single()).isEqualTo(code);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation WHERE user_id=:u").param("u", user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_official_plan_result_history WHERE user_id=:u").param("u", user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u").param("u", user)
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_source_snapshot WHERE user_id=:u AND initial_candidate_id "
                + "IS NOT NULL").param("u", user).query(Integer.class).single()).isEqualTo(1);
        assertNoChildInProgress(job);
        if (change.equals("DELETION")) {
            assertThat(sync.dashboard(user).dashboard()).isInstanceOf(SyncJobApi.Dashboard.NotPermitted.class);
            assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "incremental-race-02"))
                    .isInstanceOf(SyncJobApi.SyncAdmission.NotPermitted.class);
        } else if (!change.equals("POINTER")) {
            var dashboard = (SyncJobApi.Dashboard.Ready) sync.dashboard(user).dashboard();
            assertThat(dashboard.result().calculationId()).isEqualTo(v1.calculationId());
            assertThat(dashboard.sync().availability()).isEqualTo("UNAVAILABLE");
            assertThat(dashboard.result().trust().freshness().cause()).isEqualTo("SYNC_UNAVAILABLE");
            assertThat(dashboard.nextAction()).isEqualTo("NONE");
            assertThat(sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "incremental-race-02"))
                    .isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        }
    }

    /**
     * AD-16: a holder that lost its lease while waiting to publish is not run again — the reclaim ends the attempt
     * as interrupted with exactly one line — and the late worker writes nothing when it wakes up.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aLostLeaseEndsAsInterruptedAndTheLateWorkerWritesNothing(CapturedOutput output) throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "incremental-lease", null);
        var v1 = ready(user);
        UUID job;
        try (AutoCloseable held = holdPlanLock(user)) {
            job = manual(user, "incremental-lease-01");
            awaitBlockedOnPlanLock();
            jdbc().sql("UPDATE sync_plan_sync_job SET lease_until=statement_timestamp()-interval '1 second' "
                    + "WHERE job_id=:j").param("j", job).update();
            assertThat(awaitSync(job)).isEqualTo("FAILED");
        }
        Thread.sleep(1_000);
        assertThat(jdbc().sql("SELECT status||'|'||failure_code FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", job).query(String.class).single()).isEqualTo("FAILED|SYNC_INTERRUPTED");
        assertUnchanged(user, v1);
        assertNoChildInProgress(job);
        assertThat(lines(output, "plan sync finished: job=" + job + " ")).singleElement()
                .satisfies(line -> assertThat(line).contains("status=FAILED").contains("code=SYNC_INTERRUPTED"));
    }

    // ------------------------------------------------------------------------------------------ HTTP / UNAVAILABLE

    /**
     * The HTTP surface: 400 for a body that names none of the three triggers, 422 without a key, 403 without the
     * CSRF token and Origin or for a deleting account, 409 without an active plan or while unavailable, 405 for a
     * read of the command path — every answer {@code no-store} — and a READY dashboard with its sync state.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theHttpSurfaceAnswersEveryAdmissionShape(CapturedOutput output) throws Exception {
        String subject = "incremental-http-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String csrf = csrfTokenOf(cookie);
        store(user, DevBybitStub.Scenario.UTA2);
        // No active plan yet.
        var none = postSync(cookie, csrf, "MANUAL", "incremental-http-key-0001");
        assertThat(none.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(none.body())).containsEntry("code", "NO_ACTIVE_PLAN")
                .containsKeys("message", "correlationId");
        publishedPlan(user, "incremental-http", null);
        BybitStubConfiguration.resetRequestCount();
        for (String body : new String[] {"{}", "{\"trigger\":\"manual\"}", "{\"trigger\":\"NOW\"}",
                "{\"trigger\":1}", "not json"}) {
            var bad = sendJson("POST", SYNC_PATH, body, cookie, "Origin", OidcStubConfiguration.PUBLIC_BASE_URL,
                    AuthSecurityConfig.CSRF_HEADER_NAME, csrf, "Idempotency-Key", "incremental-http-key-0002");
            assertThat(bad.statusCode()).as(body).isEqualTo(400);
            assertThat(bad.headers().firstValue("Cache-Control").orElse("")).as(body).contains("no-store");
            assertThat(topLevelFieldsOf(bad.body())).as(body).containsEntry("code", "BAD_REQUEST");
        }
        var keyless = sendJson("POST", SYNC_PATH, "{\"trigger\":\"MANUAL\"}", cookie, "Origin",
                OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME, csrf);
        assertThat(keyless.statusCode()).isEqualTo(422);
        assertThat(topLevelFieldsOf(keyless.body())).containsEntry("code", "IDEMPOTENCY_KEY_REQUIRED");
        assertThat(sendJson("POST", SYNC_PATH, "{\"trigger\":\"MANUAL\"}", cookie, "Idempotency-Key",
                "incremental-http-key-0003").statusCode()).as("no CSRF token or Origin").isEqualTo(403);
        assertThat(sendJson("POST", SYNC_PATH, "{\"trigger\":\"MANUAL\"}", null, "Idempotency-Key",
                "incremental-http-key-0003").statusCode()).as("no session").isIn(401, 403);
        assertThat(get(SYNC_PATH, cookie).statusCode()).as("the command path is not a read").isEqualTo(405);
        // Unavailable while a restriction stands: 409, and the dashboard says why; no provider call.
        UUID restriction = UUID.randomUUID();
        jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) VALUES (:id,'plan-sync')")
                .param("id", restriction).update();
        var unavailable = postSync(cookie, csrf, "MANUAL", "incremental-http-key-0004");
        assertThat(unavailable.statusCode()).isEqualTo(409);
        assertThat(unavailable.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(unavailable.body())).containsEntry("code", "SYNC_UNAVAILABLE");
        var dashboard = get(AuthSecurityConfig.DASHBOARD_PATH, cookie);
        var syncView = nested(topLevelFieldsOf(dashboard.body()), "sync");
        assertThat(syncView).containsEntry("availability", "UNAVAILABLE").containsEntry("reason", "RESTRICTED")
                .containsEntry("retryAfterSeconds", null).containsEntry("latest", null);
        assertThat(topLevelFieldsOf(dashboard.body())).containsEntry("nextAction", "NONE");
        jdbc().sql("DELETE FROM operations_restriction WHERE restriction_id=:id").param("id", restriction).update();
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(countOf("sync_plan_sync_job", user)).isZero();
        // A deleting account is refused before anything else.
        accountDeletion().beginDeletion(user);
        var deleting = postSync(cookie, csrf, "MANUAL", "incremental-http-key-0005");
        assertThat(deleting.statusCode()).isEqualTo(403);
        var lines = lines(output, "plan sync admission: ");
        assertThat(lines).isNotEmpty().allSatisfy(line -> {
            assertThat(line).matches(".*plan sync admission: trigger=(ENTRY|VISIBILITY|MANUAL|-) result=[A-Z_]+ "
                    + "status=\\d{3} latencyMs=\\d+$");
            assertThat(line).doesNotContain(user.toString()).doesNotContain("incremental-http-key");
        });
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("trigger=MANUAL result=DENIED status=403"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("trigger=- result=BAD_REQUEST status=400"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains(
                "trigger=MANUAL result=IDEMPOTENCY_KEY_REQUIRED status=422"));
    }

    // ------------------------------------------------------------------------------------------ ERASURE

    /**
     * AC-10: the erasure removes this user's sync attempts with their stage rows, candidates, versions and
     * history — an attempt still in progress too, whose late worker recreates nothing — and keeps other users'.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void erasureRemovesTheUsersSyncRowsAndKeepsOtherUsers(CapturedOutput output) throws Exception {
        UUID kept = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(kept, "incremental-kept", null);
        UUID keptJob = manual(kept, "incremental-kept-01");
        assertThat(awaitSync(keptJob)).isEqualTo("SUCCEEDED");
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "incremental-erased", null);
        UUID done = manual(user, "incremental-erased-01");
        assertThat(awaitSync(done)).isEqualTo("SUCCEEDED");
        jdbc().sql("UPDATE sync_plan_sync_job SET admitted_at=admitted_at-interval '1 minute' WHERE job_id=:j")
                .param("j", done).update();
        UUID late;
        try (AutoCloseable held = holdPlanLock(user)) {
            late = manual(user, "incremental-erased-02");
            awaitBlockedOnPlanLock();
            accountDeletion().beginDeletion(user);
            portfolio.erasePortfolioData(user);
            sync.eraseSyncData(user);
        }
        Thread.sleep(1_500);
        for (String table : List.of("sync_plan_sync_job", "sync_initial_collection_job", "sync_cashflow_classification_job",
                "sync_cashflow_conversion_job", "sync_initial_candidate_bundle", "sync_plan_calculation",
                "sync_official_plan_result", "sync_official_plan_result_history")) {
            assertThat(countOf(table, user)).as(table).isZero();
            assertThat(countOf(table, kept)).as("kept " + table).isPositive();
        }
        assertThat(countOf("sync_initial_source_event", user)).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_event_provenance p JOIN sync_initial_collection_job j "
                + "ON j.job_id=p.job_id WHERE j.user_id=:u").param("u", user).query(Integer.class).single()).isZero();
        assertThat(ready(kept).pointerVersion()).isEqualTo(2);
        assertThat(lines(output, "plan sync finished: job=" + late + " ")).singleElement()
                .satisfies(line -> assertThat(line).contains("status=ERASED"));
    }

    // ------------------------------------------------------------------------------------------ helpers

    private HttpResponseView postSync(String cookie, @Nullable String csrf, String trigger, String key)
            throws Exception {
        var response = sendJson("POST", SYNC_PATH, "{\"trigger\":\"" + trigger + "\"}", cookie, "Origin",
                OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME, csrf,
                "Idempotency-Key", key);
        return new HttpResponseView(response.statusCode(), response.body(), response.headers());
    }

    private record HttpResponseView(int statusCode, String body, java.net.http.HttpHeaders headers) {}

    private UUID manual(UUID user, String key) {
        var admission = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, key);
        assertThat(admission).as("admission").isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        return ((SyncJobApi.SyncAdmission.Accepted) admission).job().jobId();
    }

    private String awaitSync(UUID job) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            String status = jdbc().sql("SELECT status FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                    .query(String.class).optional().orElse("GONE");
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return status;
            Thread.sleep(100);
        }
        throw new AssertionError("plan sync did not finish");
    }

    /** The windows cover [start, tc] contiguously, each at most seven days, both types in each window. */
    private static void assertWindows(List<BybitStubConfiguration.TransactionQuery> queries, Instant start, Instant tc) {
        assertThat(queries).isNotEmpty();
        var byWindow = new java.util.TreeMap<Long, java.util.Set<String>>();
        var ends = new java.util.HashMap<Long, Long>();
        for (var q : queries) {
            byWindow.computeIfAbsent(q.startTime(), ignored -> new java.util.HashSet<>()).add(q.type());
            ends.put(q.startTime(), q.endTime());
        }
        long next = start.toEpochMilli();
        for (var window : byWindow.entrySet()) {
            assertThat(window.getKey()).as("contiguous").isEqualTo(next);
            assertThat(window.getValue()).containsExactlyInAnyOrder("TRANSFER_IN", "TRANSFER_OUT");
            long end = ends.get(window.getKey());
            assertThat(end - window.getKey()).isPositive().isLessThanOrEqualTo(Duration.ofDays(7).toMillis());
            next = end;
        }
        assertThat(next).as("the closed tc").isEqualTo(tc.toEpochMilli());
    }

    /** The last official version is exactly v1: pointer, calculation, portfolio pointer and watermarks. */
    private void assertUnchanged(UUID user, SyncJobApi.DashboardResult v1) {
        var now = ready(user);
        assertThat(now.pointerVersion()).isEqualTo(1);
        assertThat(now.calculationId()).isEqualTo(v1.calculationId());
        assertThat(now.cashflowVerifiedThrough()).isEqualTo(v1.cashflowVerifiedThrough());
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation WHERE user_id=:u").param("u", user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_official_plan_result_history WHERE user_id=:u").param("u", user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT c.publication_sequence FROM portfolio_current_snapshot c JOIN portfolio p "
                + "ON p.portfolio_id=c.portfolio_id WHERE p.user_id=:u").param("u", user).query(Long.class).single())
                .isEqualTo(2L);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_source_snapshot WHERE user_id=:u AND initial_candidate_id "
                + "IS NOT NULL").param("u", user).query(Integer.class).single()).isEqualTo(1);
    }

    private void assertNoChildInProgress(UUID job) {
        for (String table : List.of("sync_initial_collection_job", "sync_cashflow_classification_job",
                "sync_cashflow_conversion_job")) {
            assertThat(jdbc().sql("SELECT count(*) FROM " + table + " WHERE plan_sync_job_id=:j AND status IN "
                    + "('QUEUED','RUNNING')").param("j", job).query(Integer.class).single()).as(table).isZero();
        }
    }

    /** Moves the version's verification (and its extended one) back, keeping it before t0 < basis. */
    private void moveVerification(UUID user, UUID calculation, String interval) {
        jdbc().sql("UPDATE sync_plan_calculation SET basis_at=now()-interval '" + interval + "',cashflow_verified_through="
                + "now()-interval '" + interval + "',last_extended_verification_at=now()-interval '" + interval
                + "' WHERE calculation_id=:c AND user_id=:u").param("c", calculation).param("u", user).update();
    }

    private UUID insertSourceEvent(UUID user, String sourceId, String type, String currency, String cashFlow,
            Instant at) {
        var connection = jdbc().sql("SELECT connection_id,provider_account_id FROM source_connection WHERE user_id=:u "
                + "AND status='ACTIVE'").param("u", user)
                .query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).single();
        UUID event = UUID.randomUUID();
        jdbc().sql("INSERT INTO sync_initial_source_event(event_id,user_id,provider_account_id,source_id,event_type,"
                + "currency,cash_flow,transaction_time,account_type,semantic_hash,observed_connection_id) VALUES "
                + "(:e,:u,:a,:s,:t,:c,:f,:at,'UNIFIED',:h,:conn)")
            .param("e", event).param("u", user).param("a", connection[1]).param("s", sourceId).param("t", type)
            .param("c", currency).param("f", cashFlow).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .param("h", sha256(type + "|" + currency + "|" + cashFlow + "|" + at.toEpochMilli() + "|UNIFIED"))
            .param("conn", connection[0]).update();
        return event;
    }

    private String planRow(UUID user) {
        return jdbc().sql("SELECT plan_id||'|'||p0_decimal||'|'||t0||'|'||end_at||'|'||period||'|'||target_rate||'|'||"
                + "status||'|'||created_at||'|'||intent_id FROM plan_definition WHERE user_id=:u").param("u", user)
                .query(String.class).single();
    }

    private int countOf(String table, UUID user) {
        return jdbc().sql("SELECT count(*) FROM " + table + " WHERE user_id=:u").param("u", user)
                .query(Integer.class).single();
    }

    private SyncJobApi.DashboardResult ready(UUID user) {
        var dashboard = sync.dashboard(user).dashboard();
        assertThat(dashboard).isInstanceOf(SyncJobApi.Dashboard.Ready.class);
        return ((SyncJobApi.Dashboard.Ready) dashboard).result();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> fields, String name) {
        return (Map<String, Object>) fields.get(name);
    }

    private static List<String> lines(CapturedOutput output, String marker) {
        List<String> found = new ArrayList<>();
        for (String line : output.getAll().split("\\R")) if (line.contains(marker)) found.add(line);
        return found;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Publishes the first official version (Story 2.7) of a new plan: the first snapshot, daily conditions
     * ending 30 days later, and the collection, classification and conversion of its inputs. {@code start}
     * moves t0 back before the collection starts.
     */
    private UUID publishedPlan(UUID user, String prefix, @Nullable Instant start) throws Exception {
        UUID firstJob = sync.admit(user, prefix + "-seed").orElseThrow().jobId();
        for (int i = 0; i < 100 && !"SUCCEEDED".equals(sync.job(user, firstJob).orElseThrow().status()); i++)
            Thread.sleep(100);
        assertThat(sync.job(user, firstJob).orElseThrow().status()).isEqualTo("SUCCEEDED");
        var snapshot = plans.state(user).snapshot();
        String end = LocalDateTime.ofInstant(snapshot.measuredAt(), ZoneId.of("Asia/Seoul")).plusDays(30)
                .withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var preview = plans.preview(user, prefix + "-session", new PlanConditionApi.PreviewInput(end, "DAILY", "0.1"))
                .orElseThrow();
        UUID intent = plans.confirm(user, prefix + "-session", preview.confirmationRef(), prefix + "-confirm-001")
                .intent().intentId();
        if (start != null) shiftStart(user, intent, start);
        UUID collection = sync.admitInitialInputs(user, intent, prefix + "-inputs-001").orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", collection, "INPUTS_COMPLETE");
        UUID classification = sync.admitCashflowClassification(user, intent, prefix + "-classification-001")
                .orElseThrow().jobId();
        awaitTable("sync_cashflow_classification_job", classification, "COMPLETE");
        UUID conversion = sync.admitCashflowConversion(user, intent, prefix + "-conversion-001").orElseThrow().jobId();
        awaitTable("sync_cashflow_conversion_job", conversion, "CONVERTED");
        var admission = sync.admitPlanPublication(user, intent, prefix + "-publication-001");
        UUID publication = ((SyncJobApi.PublicationAdmission.Accepted) admission).job().jobId();
        awaitTable("sync_plan_publication_job", publication, "PUBLISHED");
        return intent;
    }

    private void awaitTable(String table, UUID job, String expected) throws InterruptedException {
        String status = null;
        for (int i = 0; i < 150; i++) {
            status = jdbc().sql("SELECT status FROM " + table + " WHERE job_id=:j").param("j", job)
                    .query(String.class).single();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) break;
            Thread.sleep(100);
        }
        assertThat(status).as(table).isEqualTo(expected);
    }

    /** Moves the plan start (the first snapshot's time and the intent's t0) back, before the collection starts. */
    private void shiftStart(UUID user, UUID intent, Instant start) {
        var t0 = OffsetDateTime.ofInstant(start, ZoneOffset.UTC);
        jdbc().sql("UPDATE portfolio_snapshot SET measured_at=:t0 WHERE user_id=:u AND publication_sequence=1")
                .param("t0", t0).param("u", user).update();
        jdbc().sql("UPDATE sync_source_snapshot SET measured_at=:t0 WHERE user_id=:u AND job_id IS NOT NULL")
                .param("t0", t0).param("u", user).update();
        jdbc().sql("UPDATE plan_condition_intent SET t0=:t0 WHERE intent_id=:i").param("t0", t0).param("i", intent)
                .update();
        jdbc().sql("UPDATE portfolio_source_membership SET started_at=:before WHERE portfolio_id IN "
                + "(SELECT portfolio_id FROM portfolio WHERE user_id=:u)")
                .param("before", t0.minusMinutes(1)).param("u", user).update();
    }

    private UUID connect(DevBybitStub.Scenario scenario) {
        UUID user = accountLifecycle().createAccount();
        store(user, scenario);
        return user;
    }

    private void store(UUID user, DevBybitStub.Scenario scenario) {
        Instant start = Instant.now().minusSeconds(2);
        String uid = "incremental-" + UUID.randomUUID();
        var credentials = new SourceCredentials(BybitStubConfiguration.apiKey(scenario, uid),
                BybitStubConfiguration.newSecret());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var stored = (SourceConnectionApi.StoreOutcome.Stored) source.store(user, SourceProvider.BYBIT,
                    new SourceVerification.Verified(uid, start, "bybit-readonly-uta-1.0"), credentials);
            portfolio.attachActiveSource(user, stored.connectionId(), stored.providerAccountId(), start);
        });
    }

    /** Holds the user's plan write lock (the first lock of the publishing transaction) until closed. */
    private AutoCloseable holdPlanLock(UUID user) throws InterruptedException {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc().sql("WITH l AS MATERIALIZED (SELECT pg_advisory_xact_lock(hashtextextended(:u,0))) SELECT 1 FROM l")
                    .param("u", user.toString()).query(Integer.class).single();
            acquired.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("plan lock never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
        return () -> {
            release.countDown();
            holder.join(10_000);
        };
    }

    private void awaitBlockedOnPlanLock() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (jdbc().sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")
                    .query(Integer.class).single() > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the sync never reached the plan lock");
    }
}
