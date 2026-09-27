package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceProvider;
import com.bokrilog.source.SourceVerification;
import com.bokrilog.sync.SyncJobApi;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Docker-backed first publication and failure matrix. */
class FirstSnapshotTest extends AuthFlowTestBase {
    @Autowired private SourceConnectionApi source;
    @Autowired private PortfolioMembershipApi portfolio;
    @Autowired private SyncJobApi sync;
    @Autowired private PlanConditionApi plans;
    @Autowired private PlatformTransactionManager manager;

    @Test
    void repeatedAdmissionPublishesOneOfficialBundle() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        BybitStubConfiguration.resetRequestCount();
        var first=sync.admit(user,"same-operation").orElseThrow();
        var duplicate=sync.admit(user,"same-operation").orElseThrow();
        assertThat(duplicate.jobId()).isEqualTo(first.jobId());
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(await(user,first.jobId())).isEqualTo("SUCCEEDED");
        var value=sync.state(user).orElseThrow().officialValue();
        assertThat(value).isNotNull();
        assertThat(value.decimal()).isEqualTo("1234.56");
        assertThat(value.currency()).isEqualTo("USD");
        assertThat(value.measuredAt()).isAfter(Instant.EPOCH);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_source_snapshot WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot_component")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_current_snapshot")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount()).isEqualTo(3);
    }

    @Test
    void firstSnapshotCanBeReviewedConfirmedReplayedAndReplaced() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID job=sync.admit(user,"plan-seed").orElseThrow().jobId();
        assertThat(await(user,job)).isEqualTo("SUCCEEDED");
        var state=plans.state(user);
        assertThat(state.eligible()).isTrue();
        assertThat(state.snapshot()).isNotNull();
        var original=state.snapshot();
        assertThat(original.decimal()).isEqualTo("1234.56");
        assertThat(original.inputStatus()).isEqualTo("VERIFIED");
        assertThat(original.snapshotId()).isEqualTo(sync.state(user).orElseThrow().officialValue().snapshotId());

        String end=LocalDateTime.ofInstant(original.measuredAt(),ZoneId.of("Asia/Seoul"))
                .plusDays(30).withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var first=plans.preview(user,"bound-session",new PlanConditionApi.PreviewInput(end,"DAILY","0.1"))
                .orElseThrow();
        assertThat(first.snapshot().snapshotId()).isEqualTo(original.snapshotId());
        var saved=plans.confirm(user,"bound-session",first.confirmationRef(),"plan-idempotency-001");
        assertThat(saved.accepted()).isTrue();
        assertThat(saved.intent().status()).isEqualTo("OPEN");
        var replay=plans.confirm(user,"bound-session",first.confirmationRef(),"plan-idempotency-001");
        assertThat(replay.accepted()).isTrue();
        assertThat(replay.intent().intentId()).isEqualTo(saved.intent().intentId());

        var replacement=plans.preview(user,"bound-session",new PlanConditionApi.PreviewInput(end,"MONTHLY","0.2"))
                .orElseThrow();
        var replaced=plans.confirm(user,"bound-session",replacement.confirmationRef(),"plan-idempotency-002");
        assertThat(replaced.accepted()).isTrue();
        assertThat(jdbc().sql("SELECT status FROM plan_condition_intent WHERE intent_id=:id")
                .param("id",saved.intent().intentId()).query(String.class).single()).isEqualTo("REPLACED");
        assertThat(jdbc().sql("SELECT count(*) FROM plan_condition_intent WHERE user_id=:u AND status='OPEN'")
                .param("u",user).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT to_regclass('active_plan') IS NULL")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void initialInputsReuseAdmissionAndPublishOnlyACompleteCandidate() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID firstJob=sync.admit(user,"initial-inputs-seed").orElseThrow().jobId();
        assertThat(await(user,firstJob)).isEqualTo("SUCCEEDED");
        var snapshot=plans.state(user).snapshot();
        String end=LocalDateTime.ofInstant(snapshot.measuredAt(),ZoneId.of("Asia/Seoul"))
                .plusDays(30).withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var preview=plans.preview(user,"initial-inputs-session",
                new PlanConditionApi.PreviewInput(end,"DAILY","0.1")).orElseThrow();
        var intent=plans.confirm(user,"initial-inputs-session",preview.confirmationRef(),
                "initial-plan-confirm-001").intent();
        assertThat(intent).isNotNull();

        BybitStubConfiguration.resetRequestCount();
        var accepted=sync.admitInitialInputs(user,intent.intentId(),"initial-inputs-command-001").orElseThrow();
        var replay=sync.admitInitialInputs(user,intent.intentId(),"initial-inputs-command-001").orElseThrow();
        assertThat(replay.jobId()).isEqualTo(accepted.jobId());
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(awaitInitial(user,accepted.jobId())).as("failure="+sync.initialCollectionJob(user,accepted.jobId())
                .orElseThrow().failureCode()+", pages="+jdbc().sql("SELECT count(*) FROM sync_initial_page_evidence WHERE job_id=:j")
                .param("j",accepted.jobId()).query(Integer.class).single()+", events="+
                jdbc().sql("SELECT count(*) FROM sync_initial_source_event WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isEqualTo("INPUTS_COMPLETE");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT equity_decimal FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",accepted.jobId()).query(String.class).single()).isEqualTo("1234.56");
        assertThat(jdbc().sql("SELECT measured_at FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",accepted.jobId()).query(java.time.OffsetDateTime.class).single().toInstant().toEpochMilli())
                .isEqualTo(BybitStubConfiguration.lastWalletEnvelopeTime());
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_page_evidence WHERE job_id=:j")
                .param("j",accepted.jobId()).query(Integer.class).single()).isEqualTo(2);
        jdbc().sql("INSERT INTO sync_initial_page_evidence(evidence_id,job_id,user_id,event_type,range_start,range_end,"
                + "cursor_hash,requested_at,received_at,item_count,first_event_at,last_event_at,has_next_page) "
                + "SELECT :id,job_id,user_id,event_type,range_start,range_end,cursor_hash,requested_at,received_at,"
                + "item_count,first_event_at,last_event_at,has_next_page FROM sync_initial_page_evidence "
                + "WHERE job_id=:j AND event_type='TRANSFER_IN' ORDER BY range_start LIMIT 1 "
                + "ON CONFLICT(job_id,event_type,range_start,range_end,cursor_hash) DO NOTHING")
                .param("id",UUID.randomUUID()).param("j",accepted.jobId()).update();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_page_evidence WHERE job_id=:j")
                .param("j",accepted.jobId()).query(Integer.class).single())
                .as("replaying the first cursor page remains idempotent").isEqualTo(2);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_source_event WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE user_id=:u "
                + "AND completeness_hash ~ '^[0-9a-f]{64}$'").param("u",user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount()).isEqualTo(6);
    }

    @Test
    void staleInitialCollectionFenceCannotPublishCandidate() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.SLOW_COLLECTION);
        UUID intent=initialIntent(user,"stale-collection");
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitInitialInputs(user,intent,"stale-collection-command-001").orElseThrow().jobId();
        awaitCollectionProviderCall(user,job);
        jdbc().sql("UPDATE sync_initial_collection_job SET fencing_generation=fencing_generation+1 WHERE job_id=:j")
                .param("j",job).update();
        Thread.sleep(2500);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT status FROM sync_initial_collection_job WHERE job_id=:j")
                .param("j",job).query(String.class).single()).isEqualTo("RUNNING");
    }

    @Test
    void eraseDuringInitialCollectionCannotRecreateUserData() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.SLOW_COLLECTION);
        UUID other=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=initialIntent(user,"erase-collection");
        UUID otherIntent=initialIntent(other,"other-collection");
        UUID otherJob=sync.admitInitialInputs(other,otherIntent,"other-collection-command-001").orElseThrow().jobId();
        assertThat(awaitInitial(other,otherJob)).isEqualTo("INPUTS_COMPLETE");

        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitInitialInputs(user,intent,"erase-collection-command-001").orElseThrow().jobId();
        awaitCollectionProviderCall(user,job);
        portfolio.erasePortfolioData(user);
        sync.eraseSyncData(user);
        source.eraseSourceData(user);
        Thread.sleep(2500);
        for (String table : new String[] {"sync_initial_collection_job","sync_initial_source_event",
                "sync_initial_event_observation","sync_initial_page_evidence","sync_initial_candidate_bundle"}) {
            assertThat(jdbc().sql("SELECT count(*) FROM "+table+" WHERE user_id=:u")
                    .param("u",user).query(Integer.class).single()).as(table).isZero();
        }
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_event_provenance p JOIN sync_initial_collection_job j "
                + "ON j.job_id=p.job_id WHERE j.user_id=:u").param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_event e JOIN sync_initial_candidate_bundle b "
                + "ON b.candidate_id=e.candidate_id WHERE b.user_id=:u").param("u",user)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",otherJob).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentAdmissionCreatesOnlyOneActiveJobForAnIntent() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.SLOW_COLLECTION);
        UUID intent=initialIntent(user,"active-race");
        var pool=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{
                ready.countDown(); start.await();
                return sync.admitInitialInputs(user,intent,"active-race-command-first").orElseThrow();
            });
            var second=pool.submit(()->{
                ready.countDown(); start.await();
                return sync.admitInitialInputs(user,intent,"active-race-command-second").orElseThrow();
            });
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstJob=first.get(5,TimeUnit.SECONDS);
            var secondJob=second.get(5,TimeUnit.SECONDS);
            assertThat(secondJob.jobId()).isEqualTo(firstJob.jobId());
            assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_collection_job WHERE user_id=:u AND intent_id=:i")
                    .param("u",user).param("i",intent).query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_collection_job WHERE user_id=:u AND intent_id=:i "
                    + "AND status IN ('QUEUED','RUNNING')").param("u",user).param("i",intent)
                    .query(Integer.class).single()).isEqualTo(1);
            assertThat(awaitInitial(user,firstJob.jobId())).isEqualTo("INPUTS_COMPLETE");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void collectionStateIsScopedToSelectedIntentAndReturnsEmptyOnlyForEligibleIntent() throws Exception {
        String subject="initial-state-scope-"+UUID.randomUUID();
        var login=signIn(subject);
        String cookie=sessionCookieOf(login).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID oldIntent=initialIntent(user,"state-old");
        UUID oldJob=sync.admitInitialInputs(user,oldIntent,"state-old-command-001").orElseThrow().jobId();
        assertThat(awaitInitial(user,oldJob)).isEqualTo("INPUTS_COMPLETE");

        var snapshot=plans.state(user).snapshot();
        String end=LocalDateTime.ofInstant(snapshot.measuredAt(),ZoneId.of("Asia/Seoul"))
                .plusDays(31).withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var preview=plans.preview(user,"state-replace-session",new PlanConditionApi.PreviewInput(end,"WEEKLY","0.2"))
                .orElseThrow();
        UUID currentIntent=plans.confirm(user,"state-replace-session",preview.confirmationRef(),
                "state-replace-confirm-001").intent().intentId();

        var current=get(AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/inputs?intentId="+currentIntent,cookie);
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(topLevelFieldsOf(current.body()).get("job")).isNull();
        assertThat(get(AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/inputs?intentId="+oldIntent,cookie).statusCode())
                .isEqualTo(403);
        assertThat(sync.initialCollectionJob(user,oldJob).orElseThrow().status()).isEqualTo("INPUTS_COMPLETE");
    }

    @Test
    void collectionStateDeniesRestrictedAndDeletedAccounts() throws Exception {
        String restrictedSubject="initial-state-restricted-"+UUID.randomUUID();
        var restrictedLogin=signIn(restrictedSubject);
        String restrictedCookie=sessionCookieOf(restrictedLogin).orElseThrow();
        UUID restricted=userIdOf(OidcStubConfiguration.issuer(),restrictedSubject).orElseThrow();
        store(restricted,DevBybitStub.Scenario.UTA2);
        UUID restrictedIntent=initialIntent(restricted,"state-restricted");
        jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) VALUES (:id,:command)")
                .param("id",UUID.randomUUID()).param("command","initial-source-event-collection").update();
        assertThat(sync.initialCollectionState(restricted,restrictedIntent).permitted()).isFalse();
        assertThat(get(AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/inputs?intentId="+restrictedIntent,restrictedCookie)
                .statusCode()).isEqualTo(403);

        String deletedSubject="initial-state-deleted-"+UUID.randomUUID();
        var deletedLogin=signIn(deletedSubject);
        String deletedCookie=sessionCookieOf(deletedLogin).orElseThrow();
        UUID deleted=userIdOf(OidcStubConfiguration.issuer(),deletedSubject).orElseThrow();
        store(deleted,DevBybitStub.Scenario.UTA2);
        UUID deletedIntent=initialIntent(deleted,"state-deleted");
        accountDeletion().beginDeletion(deleted);
        assertThat(sync.initialCollectionState(deleted,deletedIntent).permitted()).isFalse();
        assertThat(get(AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/inputs?intentId="+deletedIntent,deletedCookie)
                .statusCode()).isEqualTo(403);
    }

    private UUID initialIntent(UUID user,String prefix) throws Exception {
        UUID firstJob=sync.admit(user,prefix+"-seed").orElseThrow().jobId();
        assertThat(await(user,firstJob)).isEqualTo("SUCCEEDED");
        var snapshot=plans.state(user).snapshot();
        String end=LocalDateTime.ofInstant(snapshot.measuredAt(),ZoneId.of("Asia/Seoul"))
                .plusDays(30).withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var preview=plans.preview(user,prefix+"-session",new PlanConditionApi.PreviewInput(end,"DAILY","0.1"))
                .orElseThrow();
        return plans.confirm(user,prefix+"-session",preview.confirmationRef(),prefix+"-confirm-001")
                .intent().intentId();
    }

    private void awaitCollectionProviderCall(UUID user,UUID job) throws Exception {
        for (int i=0;i<100;i++) {
            var status=sync.initialCollectionJob(user,job).orElseThrow().status();
            if (status.equals("RUNNING")&&BybitStubConfiguration.requestCount()>=4) return;
            Thread.sleep(50);
        }
        throw new AssertionError("initial collection did not reach the transaction-log provider call");
    }

    @Test
    void ineligibleCollectionIntentDoesNotCallProvider() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID other=connect(DevBybitStub.Scenario.UTA2);
        UUID ownIntent=initialIntent(user,"ineligible-own");
        UUID foreignIntent=initialIntent(other,"ineligible-foreign");

        BybitStubConfiguration.resetRequestCount();
        assertThat(sync.admitInitialInputs(user,foreignIntent,"ineligible-command-001")).isEmpty();
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_collection_job WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT status FROM plan_condition_intent WHERE intent_id=:i")
                .param("i",ownIntent).query(String.class).single()).isEqualTo("OPEN");
    }

    @Test
    void repeatedTransactionCursorLeavesPartialEvidenceAndNeedsReview() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.REPEATED_CURSOR);
        UUID intent=initialIntent(user,"repeated-cursor");
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitInitialInputs(user,intent,"repeated-cursor-command-001").orElseThrow().jobId();

        assertThat(awaitInitial(user,job)).isEqualTo("NEEDS_REVIEW");
        assertThat(sync.initialCollectionJob(user,job).orElseThrow().failureCode()).isEqualTo("MALFORMED_PROVIDER_RESPONSE");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_page_evidence WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isZero();
    }

    @Test
    void transactionLogPageFailureLeavesNoCandidateAndRequestsRetry() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.LOG_FAILURE);
        UUID intent=initialIntent(user,"log-page-failure");
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitInitialInputs(user,intent,"log-failure-command-001").orElseThrow().jobId();

        assertThat(awaitInitial(user,job)).isEqualTo("RETRY_REQUIRED");
        assertThat(sync.initialCollectionJob(user,job).orElseThrow().failureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_page_evidence WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isZero();
    }

    @Test
    void malformedTransactionLogResponseNeedsReviewWithoutExposingPayload() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.MALFORMED_LOG);
        UUID intent=initialIntent(user,"malformed-log");
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitInitialInputs(user,intent,"malformed-log-command-001").orElseThrow().jobId();

        assertThat(awaitInitial(user,job)).isEqualTo("NEEDS_REVIEW");
        var status=sync.initialCollectionJob(user,job).orElseThrow();
        assertThat(status.failureCode()).isEqualTo("MALFORMED_PROVIDER_RESPONSE");
        assertThat(status.failureCode()).doesNotContain("fixture-payload-must-not-escape");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isZero();
    }

    @Test
    void unknownTransactionEventSchemaIsQuarantinedWithoutCandidate() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UNKNOWN_LOG_EVENT);
        UUID intent=initialIntent(user,"unknown-log-event");
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitInitialInputs(user,intent,"unknown-log-command-001").orElseThrow().jobId();

        assertThat(awaitInitial(user,job)).isEqualTo("NEEDS_REVIEW");
        assertThat(sync.initialCollectionJob(user,job).orElseThrow().failureCode())
                .isEqualTo("UNKNOWN_OR_MALFORMED_EVENT");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_event_observation WHERE job_id=:j "
                + "AND diagnostic_code='UNKNOWN_EVENT'").param("j",job).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_event_observation o "
                + "JOIN sync_initial_page_evidence p ON p.evidence_id=o.page_evidence_id AND p.job_id=o.job_id "
                + "WHERE o.job_id=:j AND p.event_type='TRANSFER_IN'")
                .param("j",job).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_source_event WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isZero();
    }

    @Test
    void oversizedUtf8EventIdIsQuarantinedWithoutInsert() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.OVERSIZED_EVENT_ID);
        UUID intent=initialIntent(user,"oversized-id");
        UUID job=sync.admitInitialInputs(user,intent,"oversized-id-command-001").orElseThrow().jobId();

        assertThat(awaitInitial(user,job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_event_observation WHERE job_id=:j "
                + "AND diagnostic_code='SOURCE_ID_TOO_LONG' AND source_id IS NULL")
                .param("j",job).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_source_event WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"OVERSIZED_CASH_FLOW", "EXCESSIVE_CASH_FLOW_SCALE"})
    void excessiveCashFlowSizeOrScaleIsQuarantined(DevBybitStub.Scenario scenario) throws Exception {
        UUID user=connect(scenario);
        UUID intent=initialIntent(user,"cash-flow-limit-"+scenario.name().toLowerCase(java.util.Locale.ROOT));
        UUID job=sync.admitInitialInputs(user,intent,"cash-flow-limit-command-"+scenario.name()).orElseThrow().jobId();

        assertThat(awaitInitial(user,job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_event_observation WHERE job_id=:j "
                + "AND diagnostic_code='MALFORMED_EVENT'").param("j",job).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_source_event WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isZero();
    }

    @Test
    void outOfWindowTransactionTimeIsQuarantinedWithoutCandidate() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.OUT_OF_WINDOW_EVENT);
        UUID intent=initialIntent(user,"out-of-window");
        UUID job=sync.admitInitialInputs(user,intent,"out-of-window-command-001").orElseThrow().jobId();

        assertThat(awaitInitial(user,job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_event_observation WHERE job_id=:j "
                + "AND diagnostic_code='EVENT_OUT_OF_WINDOW'").param("j",job).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_source_event WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isZero();
    }

    @Test
    void overLimitProviderPageLeavesNoCandidate() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.OVER_LIMIT_PAGE);
        UUID intent=initialIntent(user,"over-limit-page");
        UUID job=sync.admitInitialInputs(user,intent,"over-limit-page-command-001").orElseThrow().jobId();

        assertThat(awaitInitial(user,job)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_page_evidence WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",job).query(Integer.class).single()).isZero();
    }

    @Test
    void duplicateEventIdWithDifferentSemanticHashPreservesOriginalAndRequiresReview() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.ID_CONFLICT);
        UUID intent=initialIntent(user,"event-conflict");
        BybitStubConfiguration.resetRequestCount();
        UUID first=sync.admitInitialInputs(user,intent,"event-conflict-command-001").orElseThrow().jobId();
        assertThat(awaitInitial(user,first)).isEqualTo("INPUTS_COMPLETE");
        UUID second=sync.admitInitialInputs(user,intent,"event-conflict-command-002").orElseThrow().jobId();

        assertThat(awaitInitial(user,second)).isEqualTo("NEEDS_REVIEW");
        assertThat(sync.initialCollectionJob(user,second).orElseThrow().failureCode()).isEqualTo("SOURCE_REQUIRES_REVIEW");
        assertThat(jdbc().sql("SELECT cash_flow FROM sync_initial_source_event WHERE user_id=:u AND source_id='stable-event-id'")
                .param("u",user).query(String.class).single()).isEqualTo("1");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_event_observation "
                + "WHERE job_id=:j AND diagnostic_code='PROVIDER_ID_CONFLICT'")
                .param("j",second).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",second).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_candidate_bundle WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void competingPreviewsCannotReplaceAnIntentSavedAfterTheirReview() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID job=sync.admit(user,"plan-race-seed").orElseThrow().jobId();
        assertThat(await(user,job)).isEqualTo("SUCCEEDED");
        var snapshot=plans.state(user).snapshot();
        String end=LocalDateTime.ofInstant(snapshot.measuredAt(),ZoneId.of("Asia/Seoul"))
                .plusDays(30).withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        var earlier=plans.preview(user,"race-session",new PlanConditionApi.PreviewInput(end,"DAILY","0.1"))
                .orElseThrow();
        var competing=plans.preview(user,"race-session",new PlanConditionApi.PreviewInput(end,"WEEKLY","0.2"))
                .orElseThrow();
        var winner=plans.confirm(user,"race-session",earlier.confirmationRef(),"plan-race-first-001");
        assertThat(winner.accepted()).isTrue();

        jdbc().sql("UPDATE plan_condition_confirmation SET issued_at=(SELECT created_at FROM plan_condition_intent "
                + "WHERE intent_id=:intent) WHERE user_id=:user AND period='WEEKLY'")
                .param("intent",winner.intent().intentId()).param("user",user).update();

        var loser=plans.confirm(user,"race-session",competing.confirmationRef(),"plan-race-second-001");
        assertThat(loser.accepted()).isFalse();
        assertThat(loser.conflict()).isTrue();
        assertThat(loser.intent().intentId()).isEqualTo(winner.intent().intentId());
        assertThat(jdbc().sql("SELECT status FROM plan_condition_intent WHERE intent_id=:id")
                .param("id",winner.intent().intentId()).query(String.class).single()).isEqualTo("OPEN");
    }

    @Test
    void planPreviewRejectsWrongSessionInvalidRateAndExpiredRangeWithoutSaving() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID job=sync.admit(user,"plan-validation-seed").orElseThrow().jobId();
        assertThat(await(user,job)).isEqualTo("SUCCEEDED");
        var snapshot=plans.state(user).snapshot();
        assertThat(plans.preview(user,"session-a",null)).isEmpty();
        String validEnd=LocalDateTime.ofInstant(snapshot.measuredAt(),ZoneId.of("Asia/Seoul"))
                .plusDays(4).withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertThat(plans.preview(user,"session-a",new PlanConditionApi.PreviewInput(validEnd,"DAILY","NaN")))
                .isEmpty();
        String tooLate=LocalDateTime.ofInstant(snapshot.measuredAt(),ZoneId.of("Asia/Seoul"))
                .plusYears(1).plusDays(1).withSecond(0).withNano(0)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        assertThat(plans.preview(user,"session-a",new PlanConditionApi.PreviewInput(tooLate,"DAILY","0")))
                .isEmpty();
        var preview=plans.preview(user,"session-a",new PlanConditionApi.PreviewInput(validEnd,"WEEKLY","0"))
                .orElseThrow();
        assertThat(plans.confirm(user,"session-b",preview.confirmationRef(),"plan-idempotency-003").accepted())
                .isFalse();
        assertThat(jdbc().sql("SELECT count(*) FROM plan_condition_intent WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
    }

    @Test
    void httpAdmissionUses202AndSameOriginProtection() throws Exception {
        String subject="snapshot-http-"+UUID.randomUUID();
        var signed=signIn(subject);
        String cookie=sessionCookieOf(signed).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        String csrf=csrfTokenOf(cookie);
        assertThat(send("POST",AuthSecurityConfig.FIRST_SNAPSHOT_PATH,cookie).statusCode())
                .isEqualTo(403);
        var started=send("POST",AuthSecurityConfig.FIRST_SNAPSHOT_PATH,cookie,
                "Origin",OidcStubConfiguration.PUBLIC_BASE_URL,
                AuthSecurityConfig.CSRF_HEADER_NAME,csrf,"Idempotency-Key","http-first");
        assertThat(started.statusCode()).isEqualTo(202);
        var repeated=send("POST",AuthSecurityConfig.FIRST_SNAPSHOT_PATH,cookie,
                "Origin",OidcStubConfiguration.PUBLIC_BASE_URL,
                AuthSecurityConfig.CSRF_HEADER_NAME,csrf,"Idempotency-Key","http-first");
        assertThat(repeated.statusCode()).isEqualTo(202);
        assertThat(topLevelFieldsOf(repeated.body()).get("jobId"))
                .isEqualTo(topLevelFieldsOf(started.body()).get("jobId"));
        assertThat(get(AuthSecurityConfig.FIRST_SNAPSHOT_PATH,cookie).statusCode()).isEqualTo(200);
    }

    @Test
    void zeroAndNegativeEquityRemainOfficial() throws Exception {
        for (var scenario : new DevBybitStub.Scenario[] {
                DevBybitStub.Scenario.ZERO_EQUITY, DevBybitStub.Scenario.NEGATIVE_EQUITY }) {
            UUID user=connect(scenario);
            UUID job=sync.admit(user,UUID.randomUUID().toString()).orElseThrow().jobId();
            assertThat(await(user,job)).isEqualTo("SUCCEEDED");
            assertThat(sync.state(user).orElseThrow().officialValue().decimal())
                    .isEqualTo(scenario==DevBybitStub.Scenario.ZERO_EQUITY
                            ? "0" : "-12.345678901234567890");
        }
    }

    @Test
    void invalidProviderTimeCannotCreatePartialRows() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.BAD_MEASURED_TIME);
        UUID job=sync.admit(user,"bad-time").orElseThrow().jobId();
        assertThat(await(user,job)).isEqualTo("FAILED");
        assertThat(sync.state(user).orElseThrow().officialValue()).isNull();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_source_snapshot WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
    }

    @ParameterizedTest(name = "Worker 재조회 {0} -> {1}")
    @CsvSource({
        "AUTH_FAILURE, AUTHENTICATION_REJECTED, 1",
        "WRITE_PERMISSION, WRITE_PERMISSION, 1",
        "PERMISSION_GRANTED, WRITE_PERMISSION, 1",
        "NO_PERMISSIONS, RESPONSE_FORMAT, 1",
        "SUB_ACCOUNT, SUB_ACCOUNT, 1",
        "UID_CHANGED, SUB_ACCOUNT, 3",
        "NO_IP, IP_NOT_BOUND, 1",
        "ANY_IP, IP_NOT_BOUND, 1",
        "LEGACY_UTA, UNSUPPORTED_ACCOUNT_MODE, 2",
        "RATE_LIMIT, RATE_LIMITED, 1",
        "OUTAGE, PROVIDER_UNAVAILABLE, 1",
        "MALFORMED, RESPONSE_FORMAT, 1",
        "WALLET_FAILURE, WALLET_UNAVAILABLE, 3",
        "WALLET_NO_EQUITY, RESPONSE_FORMAT, 3",
        "TIMEOUT, TIMEOUT, 3"
    })
    void providerFailureDoesNotPublishOrAutomaticallyRetry(
            DevBybitStub.Scenario scenario, String reason, int calls) throws Exception {
        UUID user=connect(scenario);
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admit(user,"failed-provider-"+scenario).orElseThrow().jobId();
        assertThat(await(user,job)).isEqualTo("FAILED");
        assertThat(sync.job(user,job).orElseThrow().failureReason()).isEqualTo(reason);
        assertThat(BybitStubConfiguration.requestCount()).isEqualTo(calls);
        assertThat(sync.state(user).orElseThrow().officialValue()).isNull();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_source_snapshot WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        Thread.sleep(1200);
        assertThat(sync.job(user,job).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(BybitStubConfiguration.requestCount()).isEqualTo(calls);
    }

    @Test
    void pointerFailureRollsBackEveryCandidate() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        jdbc().sql("CREATE FUNCTION reject_first_pointer() RETURNS trigger LANGUAGE plpgsql "
                + "AS $$ BEGIN RAISE EXCEPTION 'injected pointer failure'; END $$").update();
        jdbc().sql("CREATE TRIGGER reject_first_pointer BEFORE INSERT ON portfolio_current_snapshot "
                + "FOR EACH ROW EXECUTE FUNCTION reject_first_pointer()").update();
        try {
            UUID job=sync.admit(user,"pointer-fail").orElseThrow().jobId();
            assertThat(await(user,job)).isEqualTo("FAILED");
            assertThat(jdbc().sql("SELECT count(*) FROM sync_source_snapshot WHERE user_id=:u")
                    .param("u",user).query(Integer.class).single()).isZero();
            assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u")
                    .param("u",user).query(Integer.class).single()).isZero();
            assertThat(jdbc().sql("SELECT count(*) FROM portfolio_current_snapshot")
                    .query(Integer.class).single()).isZero();
        } finally {
            jdbc().sql("DROP TRIGGER reject_first_pointer ON portfolio_current_snapshot").update();
            jdbc().sql("DROP FUNCTION reject_first_pointer()").update();
        }
    }

    @Test
    void staleFenceCannotPublishAfterLeaseOwnershipChanges() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.SLOW_WALLET);
        UUID job=sync.admit(user,"stale-fence").orElseThrow().jobId();
        for (int i=0;i<50;i++) {
            if (sync.job(user,job).orElseThrow().status().equals("RUNNING")) break;
            Thread.sleep(50);
        }
        assertThat(sync.job(user,job).orElseThrow().status()).isEqualTo("RUNNING");
        jdbc().sql("UPDATE sync_job SET fencing_generation=fencing_generation+1 WHERE job_id=:j")
                .param("j",job).update();
        Thread.sleep(2000);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_source_snapshot WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_current_snapshot")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void restrictionAfterAdmissionBlocksDisplayAndPublication() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.SLOW_WALLET);
        UUID job=sync.admit(user,"restrict-later").orElseThrow().jobId();
        jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) "
                + "VALUES (:id,'first-snapshot')").param("id",UUID.randomUUID()).update();
        assertThat(sync.state(user).orElseThrow().restricted()).isTrue();
        assertThat(sync.state(user).orElseThrow().officialValue()).isNull();
        Thread.sleep(2200);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_source_snapshot WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT status FROM sync_job WHERE job_id=:j")
                .param("j",job).query(String.class).single()).isIn("QUEUED","BLOCKED");
    }

    @Test
    void deletionRemovesOwnPayloadAndPreventsRepublishing() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID other=connect(DevBybitStub.Scenario.UTA2);
        UUID job=sync.admit(user,"erase-me").orElseThrow().jobId();
        portfolio.erasePortfolioData(user);
        sync.eraseSyncData(user);
        source.eraseSourceData(user);
        Thread.sleep(1200);
        assertThat(sync.admit(user,"after-erase")).isEmpty();
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_job WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(source.snapshotConnection(other)).isPresent();
        assertThat(sync.job(user,job)).isEmpty();
    }

    private UUID connect(DevBybitStub.Scenario scenario) {
        UUID user=accountLifecycle().createAccount();
        store(user,scenario);
        return user;
    }

    private void store(UUID user,DevBybitStub.Scenario scenario) {
        Instant start=Instant.now().minusSeconds(2);
        String uid="first-"+UUID.randomUUID();
        var credentials=new SourceCredentials(BybitStubConfiguration.apiKey(scenario,uid),
                BybitStubConfiguration.newSecret());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var stored=(SourceConnectionApi.StoreOutcome.Stored)source.store(user,SourceProvider.BYBIT,
                    new SourceVerification.Verified(uid,start,"bybit-readonly-uta-1.0"),credentials);
            portfolio.attachActiveSource(user,stored.connectionId(),stored.providerAccountId(),start);
        });
    }

    private String await(UUID user,UUID job) throws InterruptedException {
        for (int i=0;i<100;i++) {
            String status=sync.job(user,job).orElseThrow().status();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return status;
            Thread.sleep(100);
        }
        throw new AssertionError("job did not finish");
    }
    private String awaitInitial(UUID user,UUID job) throws InterruptedException {
        for(int i=0;i<100;i++) {
            String status=sync.initialCollectionJob(user,job).orElseThrow().status();
            if(!status.equals("QUEUED")&&!status.equals("RUNNING")) return status;
            Thread.sleep(100);
        }
        throw new AssertionError("initial input collection did not finish");
    }
}
