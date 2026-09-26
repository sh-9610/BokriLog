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
}
