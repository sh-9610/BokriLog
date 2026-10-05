package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.plan.PlanEndApi;
import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.sync.PlanPublicationPort;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Story 2.15: 실제 PostgreSQL과 worker로 후속 기준·독립 발행·종단 기록을 검증한다. */
class SuccessorPlanTest extends PlanSyncTestBase {
    @Autowired PlanEndApi ends;
    @Autowired PlanPublicationPort publicationPlans;
    @Autowired org.springframework.context.ApplicationContext context;

    // Neutral observation gate: reserve a RUNNING lease in the admission's transaction, so the real
    // scheduler never claims it. Reflection stays in tests; production exposes no worker command.
    private Object reserved(UUID user) {
        UUID id=new TransactionTemplate(manager).execute(status -> {
            UUID job=sync.admitNextBaseline(user,key("reserved")).orElseThrow().jobId();
            jdbc().sql("UPDATE sync_job SET status='RUNNING',fencing_generation=1,lease_until=now()+interval '30 seconds' WHERE job_id=:j")
                .param("j",job).update();
            return job;
        });
        var connection=source.snapshotConnection(user).orElseThrow();
        try {
            var type=Class.forName("com.bokrilog.sync.internal.SyncJobRepository$Lease");
            var constructor=type.getDeclaredConstructor(UUID.class,UUID.class,UUID.class,UUID.class,long.class);
            constructor.setAccessible(true);
            return constructor.newInstance(id,user,connection.connectionId(),connection.providerAccountId(),1L);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private boolean publishObservation(Object lease,String amount,Instant measured,Instant received) {
        try {
            var service=context.getBean("syncJobService");
            var method=service.getClass().getDeclaredMethod("publish",lease.getClass(),com.bokrilog.source.SourceConnectionApi.EquityObservation.class);
            method.setAccessible(true);
            return (boolean)method.invoke(service,lease,new com.bokrilog.source.SourceConnectionApi.EquityObservation(
                    "neutral-observation",amount,measured,received,"bybit-readonly-uta-1.0"));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static String key(String name) { return "successor-"+name+"-"+UUID.randomUUID(); }
    private SyncJobApi.DashboardResult early(UUID user) throws Exception {
        publishedPlan(user,key("first"),null);
        var result=ready(user);
        var request=new PlanEndApi.Request(PlanEndApi.Reason.USER_REQUEST,result.planId(),result.pointerVersion(),result.calculationId());
        new TransactionTemplate(manager).executeWithoutResult(status -> ends.end(user,request,key("end")));
        return result;
    }
    private UUID prepare(UUID user) throws Exception {
        UUID job=sync.admitNextBaseline(user,key("baseline")).orElseThrow().jobId();
        awaitTable("sync_job",job,"SUCCEEDED");
        return job;
    }
    private UUID successor(UUID user) throws Exception {
        var state=plans.state(user);
        assertThat(state.eligible()).isTrue();
        var baseline=state.snapshot();
        String end=LocalDateTime.ofInstant(baseline.measuredAt(),ZoneId.of("Asia/Seoul")).plusDays(30)
                .withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String session=key("session");
        var preview=plans.preview(user,session,new PlanConditionApi.PreviewInput(end,"DAILY","0.1")).orElseThrow();
        var intent=plans.confirm(user,session,preview.confirmationRef(),key("confirm")).intent().intentId();
        awaitTable("sync_initial_collection_job",sync.admitInitialInputs(user,intent,key("collect")).orElseThrow().jobId(),"INPUTS_COMPLETE");
        awaitTable("sync_cashflow_classification_job",sync.admitCashflowClassification(user,intent,key("classify")).orElseThrow().jobId(),"COMPLETE");
        awaitTable("sync_cashflow_conversion_job",sync.admitCashflowConversion(user,intent,key("convert")).orElseThrow().jobId(),"CONVERTED");
        var job=((SyncJobApi.PublicationAdmission.Accepted)sync.admitPlanPublication(user,intent,key("publish"))).job();
        awaitTable("sync_plan_publication_job",job.jobId(),"PUBLISHED");
        return ready(user).planId();
    }
    @Test
    void newWalletBaselinePublishesAnIndependentPlanAndBothHistoriesRemainReadable() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        var old=early(user);
        var oldRecord=sync.historicalDashboard(user,old.planId());
        int logs=BybitStubConfiguration.requestCount(DevBybitStub.TRANSACTION_LOG_PATH);
        UUID job=prepare(user);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.TRANSACTION_LOG_PATH)).isEqualTo(logs);
        var preparation=sync.currentNextBaseline(user).orElseThrow();
        assertThat(preparation.jobId()).isEqualTo(job);
        assertThat(preparation.requestedAt()).isAfter(preparation.terminalAt());
        var snapshot=portfolio.ownedSnapshot(user,preparation.snapshotId()).orElseThrow();
        assertThat(snapshot.measuredAt()).isAfter(preparation.terminalAt());
        assertThat(snapshot.snapshotId()).isNotEqualTo(old.basisPortfolioSnapshotId());
        UUID next=successor(user);
        assertThat(next).isNotEqualTo(old.planId());
        assertThat(ready(user).startAt()).isEqualTo(snapshot.measuredAt());
        assertThat(ready(user).principal()).isEqualTo(snapshot.decimal());
        assertThat(sync.historicalDashboard(user,old.planId())).isEqualTo(oldRecord);
        assertThat(sync.planHistory(user).orElseThrow()).hasSize(1);
        var reference=new SyncJobApi.EvidenceReference(old.planId(),old.pointerVersion(),old.calculationId());
        assertThat(sync.calculationEvidence(user,reference).outcome()).isInstanceOf(SyncJobApi.EvidenceOutcome.Found.class);
        assertThat(sync.adjustmentHistory(user,new SyncJobApi.AdjustmentQuery(old.planId(),old.pointerVersion(),old.calculationId(),SyncJobApi.AdjustmentDirection.ALL,null)).outcome()).isInstanceOf(SyncJobApi.AdjustmentOutcome.Found.class);
        assertThat(jdbc().sql("SELECT predecessor_plan_id FROM plan_definition WHERE plan_id=:p").param("p",next).query(UUID.class).single()).isEqualTo(old.planId());
        assertThat(jdbc().sql("SELECT count(*) FROM plan_definition WHERE user_id=:u AND status='ACTIVE'").param("u",user).query(Integer.class).single()).isEqualTo(1);
    }
    @Test
    void anActivePlanEvenPastItsScheduledEndCannotPrepareABaseline() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user,key("active"),null);
        assertThat(sync.admitNextBaseline(user,key("active-refusal"))).isEmpty();
        moveEnd(user,ready(user).basisAt().plusMillis(1));
        assertThat(sync.admitNextBaseline(user,key("scheduled-refusal"))).isEmpty();
    }
    @Test
    void anotherPreparationInvalidatesUnconsumedConfirmationAndOpenIntent() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        early(user); prepare(user);
        var baseline=plans.state(user).snapshot();
        String end=LocalDateTime.ofInstant(baseline.measuredAt(),ZoneId.of("Asia/Seoul")).plusDays(30).withSecond(0).withNano(0).toString();
        var input=new PlanConditionApi.PreviewInput(end,"DAILY","0.1");
        var old=plans.preview(user,"test-session",input).orElseThrow();
        var open=plans.confirm(user,"test-session",old.confirmationRef(),key("old-confirm"));
        var unconsumed=plans.preview(user,"test-session",input).orElseThrow();
        prepare(user);
        assertThat(plans.confirm(user,"test-session",unconsumed.confirmationRef(),key("stale")).accepted()).isFalse();
        assertThat(plans.collectionIntent(user,open.intent().intentId())).isEmpty();
        assertThat(plans.state(user).openIntent()).isNull();
    }
    @Test
    void twoTabsAndIdempotentReplaysAdmitAtMostOneWalletJob() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        early(user);
        String same=key("same");
        var first=sync.admitNextBaseline(user,same).orElseThrow();
        assertThat(sync.admitNextBaseline(user,same).orElseThrow().jobId()).isEqualTo(first.jobId());
        assertThat(sync.admitNextBaseline(user,key("second-tab"))).isEmpty();
        awaitTable("sync_job",first.jobId(),"SUCCEEDED");
        assertThat(sync.admitNextBaseline(user,same).orElseThrow().jobId()).isEqualTo(first.jobId());
        assertThat(jdbc().sql("SELECT count(*) FROM sync_job WHERE user_id=:u AND purpose='NEXT_PLAN_BASELINE'").param("u",user).query(Integer.class).single()).isEqualTo(1);
    }
    @Test
    void deletionBlocksAdmissionAndErasesOnlyTheOwnersPreparationAndLineage() throws Exception {
        UUID kept=connect(DevBybitStub.Scenario.UTA2); early(kept); prepare(kept);
        UUID erased=connect(DevBybitStub.Scenario.UTA2); early(erased); prepare(erased); successor(erased);
        accountDeletion().beginDeletion(erased);
        assertThat(sync.admitNextBaseline(erased,key("deleted"))).isEmpty();
        portfolio.erasePortfolioData(erased); sync.eraseSyncData(erased); plans.erasePlanData(erased); source.eraseSourceData(erased); accountDeletion().erasePayload(erased);
        assertThat(countOf("sync_next_plan_baseline",erased)).isZero();
        assertThat(countOf("plan_definition",erased)).isZero();
        assertThat(countOf("sync_next_plan_baseline",kept)).isEqualTo(1);
    }
    @Test
    void historicalReadsHideUnknownForeignAndActivePlans() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2); var ended=early(user);
        UUID other=connect(DevBybitStub.Scenario.UTA2); publishedPlan(other,key("other"),null);
        assertThat(sync.historicalDashboard(other,ended.planId()).dashboard()).isInstanceOf(SyncJobApi.Dashboard.NotFound.class);
        assertThat(sync.historicalDashboard(user,UUID.randomUUID()).dashboard()).isInstanceOf(SyncJobApi.Dashboard.NotFound.class);
        assertThat(sync.historicalDashboard(other,ready(other).planId()).dashboard()).isInstanceOf(SyncJobApi.Dashboard.NotFound.class);
    }
    @Test
    void pendingHeldAndUnavailableCompletionComparisonsDoNotRestrictSuccessorEligibility() throws Exception {
        for (String comparison : java.util.List.of("PENDING","HELD","UNAVAILABLE")) {
            UUID user=connect(DevBybitStub.Scenario.UTA2);
            publishedPlan(user,key("completion"),null);
            var result=ready(user);
            moveEnd(user,result.basisAt().plusMillis(1));
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                publicationPlans.lockPlanWrites(user);
                publicationPlans.completePlan(user,result.planId(),"UNAVAILABLE".equals(comparison)).orElseThrow();
                if ("HELD".equals(comparison)) publicationPlans.recordCompletionComparison(user,result.planId(),"HELD","INPUT_REVIEW_REQUIRED",null,null);
            });
            assertThat(sync.nextBaselineState(user).eligible()).isTrue();
            prepare(user);
            assertThat(plans.state(user).eligible()).isTrue();
            var historical=(SyncJobApi.Dashboard.Completed)sync.historicalDashboard(user,result.planId()).dashboard();
            assertThat(historical.sync()).isNull();
            assertThat(historical.postEnd()).isNull();
            assertThat(historical.completion().comparison()).isEqualTo(comparison);
        }
    }
    @Test
    void baselinePublicationFailureRollsBackThePortfolioAndPreservesLastNormalPreparation() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2); early(user); prepare(user);
        var baseline=sync.currentNextBaseline(user).orElseThrow();
        var pointer=portfolio.officialValue(user).orElseThrow();
        jdbc().sql("CREATE OR REPLACE FUNCTION reject_next_baseline() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.user_id='"+user+"'::uuid AND NEW.completed_at IS NOT NULL AND OLD.completed_at IS NULL THEN RAISE EXCEPTION 'test rejection'; END IF; RETURN NEW; END $$").update();
        jdbc().sql("CREATE OR REPLACE TRIGGER reject_next_baseline BEFORE UPDATE ON sync_next_plan_baseline FOR EACH ROW EXECUTE FUNCTION reject_next_baseline()").update();
        try {
            UUID job=sync.admitNextBaseline(user,key("rollback")).orElseThrow().jobId();
            awaitTable("sync_job",job,"FAILED");
            assertThat(sync.currentNextBaseline(user)).contains(baseline);
            assertThat(portfolio.officialValue(user)).contains(pointer);
            assertThat(countOf("plan_definition",user)).isEqualTo(1);
        } finally {
            jdbc().sql("DROP TRIGGER reject_next_baseline ON sync_next_plan_baseline").update();
            jdbc().sql("DROP FUNCTION reject_next_baseline()").update();
        }
    }
    @Test
    void nonPositiveNormalBaselinesRetainTheirOriginalValuesAndOnlyBlockCreation() throws Exception {
        for (String amount : java.util.List.of("0","-3.5")) {
            UUID user=connect(DevBybitStub.Scenario.UTA2); early(user);
            Object lease=reserved(user);
            Instant now=Instant.now();
            assertThat(publishObservation(lease,amount,now,now)).isTrue();
            assertThat(sync.nextBaselineState(user).snapshot().decimal()).isEqualTo(amount);
            assertThat(sync.nextBaselineState(user).reason()).isEqualTo("PRINCIPAL_NOT_POSITIVE");
            assertThat(plans.state(user).eligible()).isFalse();
            assertThat(countOf("plan_definition",user)).isEqualTo(1);
        }
    }
    @Test
    void observationMeasuredOrReceivedBeforeTheActualTerminalTimeCannotBecomeABaseline() throws Exception {
        for (boolean measured : java.util.List.of(true,false)) {
            UUID user=connect(DevBybitStub.Scenario.UTA2); var old=early(user);
            var before=sync.historicalDashboard(user,old.planId());
            var pointer=portfolio.officialValue(user).orElseThrow();
            Object lease=reserved(user);
            Instant now=Instant.now();
            assertThat(publishObservation(lease,"1250",measured ? old.measuredAt() : now,measured ? now : old.measuredAt())).isFalse();
            assertThat(sync.currentNextBaseline(user)).isEmpty();
            assertThat(portfolio.officialValue(user)).contains(pointer);
            assertThat(sync.historicalDashboard(user,old.planId())).isEqualTo(before);
        }
    }
    @Test
    void aRequestThatClaimsToPrecedeTheTerminalFactIsRejectedBeforePublication() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2); var old=early(user);
        Object lease=reserved(user);
        // Move the actual recorded terminal fact ahead of this request; the stored attribution no longer agrees.
        jdbc().sql("UPDATE plan_end SET ended_at=ended_at+interval '1 second' WHERE user_id=:u").param("u",user).update();
        Instant now=Instant.now();
        assertThat(publishObservation(lease,"1250",now,now)).isFalse();
        assertThat(sync.currentNextBaseline(user)).isEmpty();
        assertThat(countOf("plan_definition",user)).isEqualTo(1);
    }
    @Test
    void publicationRechecksDeletionRestrictionAndConnectionAvailability() throws Exception {
        for (String cause : java.util.List.of("deletion","restriction","connection")) {
            UUID user=connect(DevBybitStub.Scenario.UTA2); early(user);
            var pointer=portfolio.officialValue(user).orElseThrow();
            Object lease=reserved(user);
            if ("deletion".equals(cause)) accountDeletion().beginDeletion(user);
            if ("restriction".equals(cause)) restrictCommand("plan-condition-create");
            if ("connection".equals(cause)) jdbc().sql("UPDATE source_connection SET status='REVOKED' WHERE user_id=:u")
                    .param("u",user).update();
            try {
                Instant now=Instant.now();
                assertThat(publishObservation(lease,"1250",now,now)).as(cause).isFalse();
                assertThat(portfolio.officialValue(user)).contains(pointer);
                assertThat(sync.currentNextBaseline(user)).isEmpty();
            } finally {
                if ("restriction".equals(cause)) jdbc().sql("DELETE FROM operations_restriction WHERE command='plan-condition-create'").update();
            }
        }
    }
    @Test
    void aLateFencedWalletWorkerCannotOverwriteTheSuccessorBaselineOrNewPlansPointer() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2); early(user);
        Object lease=reserved(user);
        Instant now=Instant.now();
        assertThat(publishObservation(lease,"1250",now,now)).isTrue();
        var baseline=sync.currentNextBaseline(user).orElseThrow();
        var pointer=portfolio.officialValue(user).orElseThrow();
        assertThat(publishObservation(lease,"9999",Instant.now(),Instant.now())).isFalse();
        assertThat(sync.currentNextBaseline(user)).contains(baseline);
        assertThat(portfolio.officialValue(user)).contains(pointer);
        successor(user);
        var next=portfolio.officialValue(user).orElseThrow();
        assertThat(publishObservation(lease,"9999",Instant.now(),Instant.now())).isFalse();
        assertThat(portfolio.officialValue(user)).contains(next);
    }
    @Test
    void unconfirmedSourceInputNeverQualifiesAsAConditionBaseline() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2); early(user); prepare(user);
        var baseline=sync.currentNextBaseline(user).orElseThrow();
        // Existing input_status CHECK permits VERIFIED only: missing source provenance is equally fail-closed.
        jdbc().sql("UPDATE sync_next_plan_baseline SET source_snapshot_id=:fake WHERE user_id=:u AND current")
            .param("fake",UUID.randomUUID()).param("u",user).update();
        assertThat(plans.state(user).eligible()).isFalse();
    }
    @Test
    void nextBaselineHttpRequiresSessionOriginCsrfAndIdempotencyAndUsesNoStore() throws Exception {
        String subject=key("http");
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2); early(user);
        String path="/api/plans/next/baseline";
        assertThat(get(path,null).statusCode()).isEqualTo(401);
        assertThat(send("POST",path,cookie).statusCode()).isEqualTo(403);
        assertThat(send("POST",path,cookie,"Origin","https://other.invalid","X-CSRF-TOKEN",csrfTokenOf(cookie),"Idempotency-Key",key("foreign")).statusCode()).isEqualTo(403);
        assertThat(send("POST",path,cookie,"Origin",OidcStubConfiguration.PUBLIC_BASE_URL,"X-CSRF-TOKEN",csrfTokenOf(cookie)).statusCode()).isEqualTo(422);
        var admitted=send("POST",path,cookie,"Origin",OidcStubConfiguration.PUBLIC_BASE_URL,"X-CSRF-TOKEN",csrfTokenOf(cookie),"Idempotency-Key",key("http-admit"));
        assertThat(admitted.statusCode()).isEqualTo(202);
        assertThat(admitted.headers().firstValue("Cache-Control")).contains("no-store");
        UUID job=UUID.fromString(topLevelFieldsOf(admitted.body()).get("jobId").toString());
        awaitTable("sync_job",job,"SUCCEEDED");
        assertThat(get(path,cookie).statusCode()).isEqualTo(200);
        var unknown=send("GET","/api/dashboard?planId="+UUID.randomUUID(),cookie,"X-Request-Id","history-probe");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(topLevelFieldsOf(unknown.body())).containsEntry("code","NOT_FOUND");
        String deletionCsrf=csrfTokenOf(cookie);
        accountDeletion().beginDeletion(user);
        assertThat(get(path,cookie).statusCode()).isEqualTo(403);
        assertThat(get("/api/dashboard/history",cookie).statusCode()).isEqualTo(403);
        assertThat(send("POST",path,cookie,"Origin",OidcStubConfiguration.PUBLIC_BASE_URL,"X-CSRF-TOKEN",deletionCsrf,"Idempotency-Key",key("deleted-http")).statusCode()).isEqualTo(403);
    }

    @Test
    void previousFlowsAndAnEventExactlyAtTheNewStartAreNotAppliedToTheSuccessor() throws Exception {
        UUID user=accountLifecycle().createAccount();
        String uid=store(user,DevBybitStub.Scenario.INCREMENTAL);
        publishedPlan(user,key("old-flows"),null);
        var first=ready(user);
        Instant deposit=BybitStubConfiguration.nextIncrementalDeposit(uid,first.basisAt());
        Thread.sleep(Math.max(0,Duration.between(Instant.now(),deposit.plusMillis(100)).toMillis()));
        var accepted=(SyncJobApi.SyncAdmission.Accepted)sync.admitPlanSync(user,SyncJobApi.SyncTrigger.MANUAL,key("old-sync"));
        assertThat(awaitSync(accepted.job().jobId())).isEqualTo("SUCCEEDED");
        var old=ready(user);
        assertThat(old.flowCount()).isPositive();
        new TransactionTemplate(manager).executeWithoutResult(status -> ends.end(user,
                new PlanEndApi.Request(PlanEndApi.Reason.USER_REQUEST,old.planId(),old.pointerVersion(),old.calculationId()),key("flow-end")));
        var historical=sync.historicalDashboard(user,old.planId());
        // The next deterministic source event is exactly the new wallet measurement. The preparation is
        // requested after the actual end, and waits for that measurement before it is completed.
        Instant boundary=BybitStubConfiguration.nextIncrementalDeposit(uid,old.basisAt());
        Object lease=reserved(user);
        Thread.sleep(Math.max(0,Duration.between(Instant.now(),boundary.plusMillis(100)).toMillis()));
        assertThat(publishObservation(lease,"1020",boundary,Instant.now())).isTrue();
        successor(user);
        var next=ready(user);
        assertThat(next.startAt()).isEqualTo(boundary);
        assertThat(next.flowCount()).isZero();
        assertThat(sync.historicalDashboard(user,old.planId())).isEqualTo(historical);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow WHERE calculation_id=:c").param("c",old.calculationId()).query(Integer.class).single()).isEqualTo(old.flowCount());
    }

    private UUID successorIntent(UUID user) {
        var baseline=plans.state(user).snapshot();
        String end=LocalDateTime.ofInstant(baseline.measuredAt(),ZoneId.of("Asia/Seoul")).plusDays(30)
                .withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String session=key("review-session");
        var preview=plans.preview(user,session,new PlanConditionApi.PreviewInput(end,"DAILY","0.1")).orElseThrow();
        return plans.confirm(user,session,preview.confirmationRef(),key("review-confirm")).intent().intentId();
    }
    private SyncJobApi.DashboardResult completedForReview(UUID user, boolean observed) throws Exception {
        publishedPlan(user,key("review-completed"),null);
        var result=ready(user);
        moveEnd(user,result.basisAt().plusMillis(1));
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            publicationPlans.lockPlanWrites(user);
            publicationPlans.completePlan(user,result.planId(),false).orElseThrow();
        });
        if (observed) {
            var job=((SyncJobApi.SyncAdmission.Accepted)sync.admitPlanSync(user,SyncJobApi.SyncTrigger.MANUAL,key("review-observe"))).job();
            assertThat(awaitSync(job.jobId())).isEqualTo("SUCCEEDED");
            jdbc().sql("UPDATE sync_plan_sync_job SET admitted_at=admitted_at-interval '1 hour' WHERE user_id=:u")
                    .param("u",user).update();
        }
        return result;
    }
    @Test
    void historicalPendingIsReadOnlyAndHistorySummariesUseActualEarlyEndButScheduledCompletionEnd() throws Exception {
        String subject=key("review-http");
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        var old=early(user);
        var ended=publicationPlans.latestPlan(user).orElseThrow().end().endedAt();
        var history=get("/api/dashboard/history",cookie);
        assertThat(history.statusCode()).isEqualTo(200);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(Instant.parse(mapper.readTree(history.body()).get(0).get("endAt").asString())).isEqualTo(ended).isNotEqualTo(old.endAt());
        prepare(user); successor(user);
        var current=ready(user);
        moveEnd(user,current.basisAt().plusMillis(1));
        new TransactionTemplate(manager).executeWithoutResult(status -> publicationPlans.completePlan(user,current.planId(),false).orElseThrow());
        var pending=get("/api/dashboard?planId="+current.planId(),cookie);
        assertThat(pending.statusCode()).isEqualTo(200);
        var pendingJson=mapper.readTree(pending.body());
        assertThat(pendingJson.get("completion").get("comparison").asString()).isEqualTo("PENDING");
        assertThat(pendingJson.get("completion").get("retryable").asBoolean()).isFalse();
        assertThat(topLevelFieldsOf(pending.body()).keySet()).doesNotContain("sync","planEnd","postEnd","verification","end");
        var record=publicationPlans.latestPlan(user).orElseThrow().completion();
        assertThat(Instant.parse(mapper.readTree(get("/api/dashboard/history",cookie).body()).get(0).get("endAt").asString())).isEqualTo(record.completedAt());
    }
    @Test
    void concurrentTabsWithSameOrDifferentKeysAdmitOnePreparation() throws Exception {
        for (boolean same : new boolean[]{true,false}) {
            UUID user=connect(DevBybitStub.Scenario.UTA2); early(user);
            String firstKey=key("parallel-first"); String secondKey=same ? firstKey : key("parallel-second");
            var start=new java.util.concurrent.CountDownLatch(1);
            var accepted=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            try (var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first=executor.submit(() -> {
                    start.await();
                    return new TransactionTemplate(manager).execute(status -> {
                        var result=sync.admitNextBaseline(user,firstKey);
                        accepted.countDown();
                        try { if (!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("release missing"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                        return result;
                    });
                });
                start.countDown();
                assertThat(accepted.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second=executor.submit(() -> sync.admitNextBaseline(user,secondKey));
                pollUntil(Duration.ofSeconds(5), () -> jdbc().sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted").query(Integer.class).single(), n -> n>0);
                release.countDown();
                var a=first.get(10,java.util.concurrent.TimeUnit.SECONDS).orElseThrow();
                var b=second.get(10,java.util.concurrent.TimeUnit.SECONDS);
                if (same) assertThat(b.orElseThrow().jobId()).isEqualTo(a.jobId()); else assertThat(b).isEmpty();
                awaitTable("sync_job",a.jobId(),"SUCCEEDED");
                assertThat(countOf("sync_next_plan_baseline",user)).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }
    @Test
    void concurrentPreparationAndFirstPublicationHaveOnlyOneAdmissionWinner() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2); early(user); prepare(user);
        UUID intent=successorIntent(user);
        awaitTable("sync_initial_collection_job",sync.admitInitialInputs(user,intent,key("race-input")).orElseThrow().jobId(),"INPUTS_COMPLETE");
        awaitTable("sync_cashflow_classification_job",sync.admitCashflowClassification(user,intent,key("race-class")).orElseThrow().jobId(),"COMPLETE");
        awaitTable("sync_cashflow_conversion_job",sync.admitCashflowConversion(user,intent,key("race-convert")).orElseThrow().jobId(),"CONVERTED");
        int before=countOf("sync_next_plan_baseline",user)+countOf("sync_plan_publication_job",user);
        var start=new java.util.concurrent.CountDownLatch(1);
        try (var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var preparation=executor.submit(() -> { start.await(); return sync.admitNextBaseline(user,key("race-baseline")); });
            var publication=executor.submit(() -> { start.await(); return sync.admitPlanPublication(user,intent,key("race-publish")); });
            start.countDown();
            var a=preparation.get(10,java.util.concurrent.TimeUnit.SECONDS);
            var b=publication.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat((a.isPresent()?1:0)+(b instanceof SyncJobApi.PublicationAdmission.Accepted?1:0)).isEqualTo(1);
            assertThat(countOf("sync_next_plan_baseline",user)+countOf("sync_plan_publication_job",user)-before).isEqualTo(1);
            if (a.isPresent()) awaitTable("sync_job",a.orElseThrow().jobId(),"SUCCEEDED");
            if (b instanceof SyncJobApi.PublicationAdmission.Accepted accepted) awaitTable("sync_plan_publication_job",accepted.job().jobId(),"PUBLISHED");
            assertThat(jdbc().sql("SELECT count(*) FROM plan_definition WHERE user_id=:u AND status='ACTIVE'").param("u",user).query(Integer.class).single()).isLessThanOrEqualTo(1);
        }
    }
    private void blockedByStage(UUID user,String table, java.util.function.Supplier<UUID> admission) {
        int attempts=countOf("sync_plan_sync_job",user);
        var pointer=portfolio.officialValue(user).orElseThrow();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            UUID job=admission.get();
            for (String state : new String[]{"QUEUED","RUNNING"}) {
                if ("RUNNING".equals(state)) jdbc().sql("UPDATE "+table+" SET status='RUNNING',lease_until=now()+interval '10 minutes' WHERE job_id=:j").param("j",job).update();
                assertThat(sync.admitPlanSync(user,SyncJobApi.SyncTrigger.MANUAL,key("blocked-"+table+state)))
                        .isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
                assertThat(countOf("sync_plan_sync_job",user)).isEqualTo(attempts);
                assertThat(portfolio.officialValue(user)).contains(pointer);
            }
            status.setRollbackOnly();
        });
        assertThat(portfolio.officialValue(user)).contains(pointer);
    }
    @Test
    void queuedAndRunningPreparationAndEveryCreationStageRefuseOldCompletionOrPostEndAdmission() throws Exception {
        for (boolean observed : new boolean[]{false,true}) {
            UUID user=connect(DevBybitStub.Scenario.UTA2); completedForReview(user,observed);
            prepare(user);
            blockedByStage(user,"sync_job",() -> sync.admitNextBaseline(user,key("busy-baseline")).orElseThrow().jobId());
            UUID intent=successorIntent(user);
            blockedByStage(user,"sync_initial_collection_job",() -> sync.admitInitialInputs(user,intent,key("busy-input")).orElseThrow().jobId());
            awaitTable("sync_initial_collection_job",sync.admitInitialInputs(user,intent,key("progress-input")).orElseThrow().jobId(),"INPUTS_COMPLETE");
            blockedByStage(user,"sync_cashflow_classification_job",() -> sync.admitCashflowClassification(user,intent,key("busy-class")).orElseThrow().jobId());
            awaitTable("sync_cashflow_classification_job",sync.admitCashflowClassification(user,intent,key("progress-class")).orElseThrow().jobId(),"COMPLETE");
            blockedByStage(user,"sync_cashflow_conversion_job",() -> sync.admitCashflowConversion(user,intent,key("busy-convert")).orElseThrow().jobId());
            awaitTable("sync_cashflow_conversion_job",sync.admitCashflowConversion(user,intent,key("progress-convert")).orElseThrow().jobId(),"CONVERTED");
            blockedByStage(user,"sync_plan_publication_job",() -> ((SyncJobApi.PublicationAdmission.Accepted)sync.admitPlanPublication(user,intent,key("busy-publish"))).job().jobId());
        }
    }
    private Object oldCompletionLease(UUID user,boolean postEnd) {
        return new TransactionTemplate(manager).execute(status -> {
            var job=((SyncJobApi.SyncAdmission.Accepted)sync.admitPlanSync(user,SyncJobApi.SyncTrigger.MANUAL,key("late-completed"))).job();
            assertThat(job.purpose()).isEqualTo(postEnd ? "POST_END_VALUE" : "COMPLETION");
            var service=context.getBean("planSyncService");
            try {
                var claim=service.getClass().getDeclaredMethod("claim"); claim.setAccessible(true);
                Object lease=((java.util.Optional<?>)claim.invoke(service)).orElseThrow();
                var id=lease.getClass().getDeclaredMethod("jobId");id.setAccessible(true);
                assertThat(id.invoke(lease)).isEqualTo(job.jobId());
                jdbc().sql("UPDATE sync_plan_sync_job SET status='FAILED',failure_code='SYNC_INTERRUPTED',fencing_generation=fencing_generation+1,lease_until=NULL,finished_at=statement_timestamp() WHERE job_id=:j").param("j",job.jobId()).update();
                return lease;
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }
    private void lateCompletedPublication(Object lease,boolean postEnd) {
        try {
            var service=context.getBean("planSyncService");
            var field=service.getClass().getDeclaredField("publisher");field.setAccessible(true);var publisher=field.get(service);
            var observationType=Class.forName("com.bokrilog.sync.internal.PlanSyncPublisher$WalletObservation");
            var constructor=observationType.getDeclaredConstructor(String.class,Instant.class,Instant.class,String.class);constructor.setAccessible(true);
            var observation=constructor.newInstance("9999",Instant.now(),Instant.now(),"bybit-readonly-uta-1.0");
            var method=postEnd ? publisher.getClass().getDeclaredMethod("publishPostEnd",lease.getClass(),observationType)
                    : publisher.getClass().getDeclaredMethod("publishObservation",lease.getClass(),observationType,boolean.class);
            method.setAccessible(true);
            try {
                if(postEnd) method.invoke(publisher,lease,observation);else method.invoke(publisher,lease,observation,false);
                throw new AssertionError("late completed publication succeeded");
            } catch (java.lang.reflect.InvocationTargetException rejected) {
                assertThat(rejected.getCause().getClass().getSimpleName()).isEqualTo("LeaseLost");
            }
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @Test
    void lateCompletionAndPostEndPublishersCannotMoveThePreparingOrNewPlanPointers() throws Exception {
        for (boolean postEnd : new boolean[]{false,true}) {
            UUID user=connect(DevBybitStub.Scenario.UTA2); var old=completedForReview(user,postEnd);
            Object oldLease=oldCompletionLease(user,postEnd);
            var oldRecord=sync.historicalDashboard(user,old.planId());
            Object baselineLease=reserved(user);
            int sources=countOf("sync_source_snapshot",user);
            var start=new java.util.concurrent.CountDownLatch(1);
            try (var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var late=executor.submit(() -> { start.await(); lateCompletedPublication(oldLease,postEnd);return true; });
                var preparation=executor.submit(() -> { start.await();return publishObservation(baselineLease,"1400",Instant.now(),Instant.now()); });
                start.countDown();
                assertThat(late.get(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(preparation.get(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            assertThat(countOf("sync_source_snapshot",user)).isEqualTo(sources+1);
            var baseline=sync.currentNextBaseline(user).orElseThrow();
            assertThat(portfolio.officialValue(user).orElseThrow().snapshotId()).isEqualTo(baseline.snapshotId());
            successor(user);
            var next=ready(user);var pointer=portfolio.officialValue(user);sources=countOf("sync_source_snapshot",user);
            lateCompletedPublication(oldLease,postEnd);
            assertThat(portfolio.officialValue(user)).isEqualTo(pointer);
            assertThat(ready(user)).isEqualTo(next);
            assertThat(sync.currentNextBaseline(user)).contains(baseline);
            assertThat(sync.historicalDashboard(user,old.planId())).isEqualTo(oldRecord);
            assertThat(countOf("sync_source_snapshot",user)).isEqualTo(sources);
        }
    }

}
