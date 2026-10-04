package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceProvider;
import com.bokrilog.source.SourceVerification;
import com.bokrilog.sync.SyncJobApi;
import java.time.Duration;
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
    @org.springframework.boot.test.web.server.LocalServerPort private int serverPort;

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
    void cashflowClassificationPublishesImmutableSignedCandidatesOnceForCompleteInput() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=initialIntent(user,"cashflow-classification");
        UUID collectionJob=sync.admitInitialInputs(user,intent,"cashflow-classification-inputs-001")
                .orElseThrow().jobId();
        assertThat(awaitInitial(user,collectionJob)).isEqualTo("INPUTS_COMPLETE");
        var bundle=jdbc().sql("SELECT candidate_id,provider_account_id,connection_id,t0,tc FROM "
                + "sync_initial_candidate_bundle WHERE job_id=:j")
                .param("j",collectionJob).query((rs,n)->new Object[] {
                    rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                    rs.getObject(4,java.time.OffsetDateTime.class).toInstant(),
                    rs.getObject(5,java.time.OffsetDateTime.class).toInstant()}).single();
        UUID candidate=(UUID)bundle[0]; UUID account=(UUID)bundle[1]; UUID connection=(UUID)bundle[2];
        Instant occurredAt=((Instant)bundle[4]).minusMillis(1);
        assertThat(occurredAt).isAfter((Instant)bundle[3]);
        UUID deposit=insertCandidateEvent(user,account,connection,collectionJob,candidate,"cashflow-in-001",
                "TRANSFER_IN","BTC","0.0003",occurredAt,"a".repeat(64));
        UUID withdrawal=insertCandidateEvent(user,account,connection,collectionJob,candidate,"cashflow-out-001",
                "TRANSFER_OUT","USDT","-12.50",occurredAt,"b".repeat(64));
        refreshCandidateHash(candidate);

        var accepted=sync.admitCashflowClassification(user,intent,"cashflow-classification-command-001")
                .orElseThrow();
        var replay=sync.admitCashflowClassification(user,intent,"cashflow-classification-command-002")
                .orElseThrow();
        assertThat(replay.jobId()).isEqualTo(accepted.jobId());
        assertThat(awaitClassification(accepted.jobId())).isEqualTo("COMPLETE");
        var state=sync.cashflowClassificationState(user,intent);
        assertThat(state.permitted()).isTrue();
        assertThat(state.job()).isNotNull();
        assertThat(state.job().depositCount()).isEqualTo(1);
        assertThat(state.job().withdrawalCount()).isEqualTo(1);
        assertThat(state.job().excludedCount()).isZero();
        assertThat(state.job().reviewCount()).isZero();
        assertThat(jdbc().sql("SELECT signed_amount FROM sync_cashflow_flow_candidate "
                + "WHERE source_event_id=:event").param("event",deposit).query(String.class).single())
                .isEqualTo("0.0003");
        assertThat(jdbc().sql("SELECT signed_amount FROM sync_cashflow_flow_candidate "
                + "WHERE source_event_id=:event").param("event",withdrawal).query(String.class).single())
                .isEqualTo("-12.5");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_classification_event "
                + "WHERE classification_id=(SELECT classification_id FROM sync_cashflow_classification_bundle "
                + "WHERE job_id=:j) AND policy_version='BYBIT-CASHFLOW-1.0' AND source_revision=1")
                .param("j",accepted.jobId()).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT to_regclass('active_plan') IS NULL").query(Boolean.class).single()).isTrue();

        UUID other=connect(DevBybitStub.Scenario.UTA2);
        UUID otherIntent=initialIntent(other,"cashflow-classification-other");
        UUID otherCollection=sync.admitInitialInputs(other,otherIntent,"cashflow-classification-other-inputs-001")
                .orElseThrow().jobId();
        assertThat(awaitInitial(other,otherCollection)).isEqualTo("INPUTS_COMPLETE");
        var otherBundle=jdbc().sql("SELECT candidate_id,provider_account_id,connection_id,t0,tc FROM "
                + "sync_initial_candidate_bundle WHERE job_id=:j").param("j",otherCollection)
                .query((rs,n)->new Object[] {rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getObject(3,UUID.class),rs.getObject(4,java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject(5,java.time.OffsetDateTime.class).toInstant()}).single();
        Instant malformedAt=((Instant)otherBundle[4]).minusMillis(1);
        UUID malformed=insertCandidateEvent(other,(UUID)otherBundle[1],(UUID)otherBundle[2],otherCollection,
                (UUID)otherBundle[0],"cashflow-malformed-001","TRANSFER_IN","BTC","not-a-number",malformedAt,
                "c".repeat(64));
        refreshCandidateHash((UUID)otherBundle[0]);
        var otherClassification=sync.admitCashflowClassification(other,otherIntent,
                "cashflow-classification-other-command-001").orElseThrow();
        jdbc().sql("UPDATE sync_cashflow_classification_job SET status='CANCELLED',finished_at=now() WHERE job_id=:j")
                .param("j",otherClassification.jobId()).update();
        var retried=sync.admitCashflowClassification(other,otherIntent,"cashflow-classification-other-command-002")
                .orElseThrow();
        assertThat(retried.jobId()).isEqualTo(otherClassification.jobId());
        assertThat(retried.status()).isEqualTo("QUEUED");
        assertThat(awaitClassification(retried.jobId())).isEqualTo("NEEDS_REVIEW");
        assertThat(sync.cashflowClassificationState(other,otherIntent).job().reviewCount()).isEqualTo(1);
        UUID malformedClass=jdbc().sql("SELECT classification_id FROM sync_cashflow_classification_bundle WHERE job_id=:j")
                .param("j",retried.jobId()).query(UUID.class).single();
        assertThat(jdbc().sql("SELECT source_quantity FROM sync_cashflow_classification_event "
                + "WHERE classification_id=:classification AND source_event_id=:event")
                .param("classification",malformedClass).param("event",malformed).query(String.class).list())
                .containsExactly((String)null);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_flow_candidate WHERE classification_id=:classification")
                .param("classification",malformedClass).query(Integer.class).single()).isZero();
        assertThat(sync.cashflowClassificationState(other,intent).permitted()).isFalse();

        portfolio.erasePortfolioData(user);
        sync.eraseSyncData(user);
        source.eraseSourceData(user);
        for(String table:new String[] {"sync_cashflow_classification_event","sync_cashflow_flow_candidate",
                "sync_cashflow_exclusion_summary"}) {
            assertThat(jdbc().sql("SELECT count(*) FROM "+table+" x JOIN sync_cashflow_classification_bundle b "
                    + "ON b.classification_id=x.classification_id WHERE b.user_id=:u")
                    .param("u",user).query(Integer.class).single()).as(table).isZero();
        }
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_classification_bundle WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_classification_job WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_classification_bundle WHERE job_id=:j")
                .param("j",otherClassification.jobId()).query(Integer.class).single()).isEqualTo(1);
    }

    private UUID insertCandidateEvent(UUID user,UUID account,UUID connection,UUID collectionJob,UUID candidate,
            String sourceId,String type,String currency,String cashFlow,Instant occurredAt,String hash) {
        UUID event=UUID.randomUUID();
        jdbc().sql("INSERT INTO sync_initial_source_event(event_id,user_id,provider_account_id,source_id,event_type,"
                + "currency,cash_flow,transaction_time,account_type,semantic_hash,observed_connection_id) "
                + "VALUES (:event,:user,:account,:source,:type,:currency,:flow,:time,'UNIFIED',:hash,:connection)")
            .param("event",event).param("user",user).param("account",account).param("source",sourceId)
            .param("type",type).param("currency",currency).param("flow",cashFlow)
            .param("time",java.time.OffsetDateTime.ofInstant(occurredAt,java.time.ZoneOffset.UTC))
            .param("hash",hash).param("connection",connection).update();
        jdbc().sql("INSERT INTO sync_initial_event_provenance(job_id,event_id,connection_id,semantic_hash) "
                + "VALUES (:job,:event,:connection,:hash)")
            .param("job",collectionJob).param("event",event).param("connection",connection).param("hash",hash).update();
        jdbc().sql("INSERT INTO sync_initial_candidate_event(candidate_id,event_id) VALUES (:candidate,:event)")
            .param("candidate",candidate).param("event",event).update();
        return event;
    }

    private void refreshCandidateHash(UUID candidate) {
        var scope=jdbc().sql("SELECT job_id,portfolio_id,intent_id,connection_id,provider_account_id,t0,tc,"
                + "equity_decimal,measured_at,policy_version FROM sync_initial_candidate_bundle WHERE candidate_id=:candidate")
            .param("candidate",candidate).query((rs,n)->new Object[] {rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),
                rs.getObject(6,java.time.OffsetDateTime.class).toInstant(),rs.getObject(7,java.time.OffsetDateTime.class).toInstant(),
                rs.getString(8),rs.getObject(9,java.time.OffsetDateTime.class).toInstant(),rs.getString(10)}).single();
        String pages=jdbc().sql("SELECT string_agg(event_type||'|'||range_start::text||'|'||range_end::text||'|'||"
                + "cursor_hash||'|'||item_count||'|'||COALESCE(first_event_at::text,'')||'|'||"
                + "COALESCE(last_event_at::text,'')||'|'||has_next_page::text,';' ORDER BY event_type,range_start,cursor_hash) "
                + "FROM sync_initial_page_evidence WHERE job_id=:job").param("job",scope[0]).query(String.class).single();
        String events=jdbc().sql("SELECT COALESCE(string_agg(source_id||'|'||semantic_hash,';' ORDER BY source_id),'') "
                + "FROM sync_initial_source_event WHERE user_id=(SELECT user_id FROM sync_initial_candidate_bundle "
                + "WHERE candidate_id=:candidate) AND provider_account_id=:account AND transaction_time>:t0 AND transaction_time<=:tc")
            .param("candidate",candidate).param("account",scope[4])
            .param("t0",java.time.OffsetDateTime.ofInstant((Instant)scope[5],java.time.ZoneOffset.UTC))
            .param("tc",java.time.OffsetDateTime.ofInstant((Instant)scope[6],java.time.ZoneOffset.UTC))
            .query(String.class).single();
        String payload=scope[0]+"|"+scope[1]+"|"+scope[2]+"|"+scope[3]+"|"+scope[4]+"|"+scope[5]+"|"+
                scope[6]+"|"+scope[7]+"|"+scope[8]+"|"+scope[9]+"|"+pages+"|"+events;
        try {
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            jdbc().sql("UPDATE sync_initial_candidate_bundle SET completeness_hash=:hash WHERE candidate_id=:candidate")
                    .param("hash",hash).param("candidate",candidate).update();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    // ------------------------------------------------------------------------------------------
    // Story 2.6 event-time USD conversion
    // ------------------------------------------------------------------------------------------

    /** One external flow injected into a complete candidate. Positive is a deposit. */
    private record Flow(String sourceId, String currency, String cashFlow, Instant at) {}

    @Test
    void cashflowConversionPublishesCalculableFlowsOnceAndSharesPublicEvidence() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=classifiedIntent(user,"conversion-main",new Flow("conv-usdt-in","USDT","100",base.plusSeconds(5)),
                new Flow("conv-btc-out","BTC","-0.5",base.plusSeconds(20)),
                new Flow("conv-eth-in","ETH","2",base.plusSeconds(40)));
        BybitStubConfiguration.resetRequestCount();

        var accepted=sync.admitCashflowConversion(user,intent,"conversion-command-001").orElseThrow();
        var sameKey=sync.admitCashflowConversion(user,intent,"conversion-command-001").orElseThrow();
        var whileActive=sync.admitCashflowConversion(user,intent,"conversion-command-002").orElseThrow();
        assertThat(sameKey.jobId()).isEqualTo(accepted.jobId());
        assertThat(whileActive.jobId()).isEqualTo(accepted.jobId());
        assertThat(BybitStubConfiguration.requestCount()).as("admission never calls the provider").isZero();
        assertThat(awaitConversion(accepted.jobId())).isEqualTo("CONVERTED_ESTIMATED");

        var job=sync.cashflowConversionState(user,intent).job();
        assertThat(job).isNotNull();
        assertThat(job.flowCount()).isEqualTo(3);
        assertThat(job.convertedCount()).isEqualTo(1);
        assertThat(job.estimatedCount()).isEqualTo(2);
        assertThat(job.conversionRequiredCount()).isZero();
        assertThat(job.reviewCount()).isZero();
        assertThat(job.calculable()).isTrue();
        assertThat(job.retryable()).isFalse();
        assertThat(job.reasonCode()).isNull();
        assertThat(job.pendingAssets()).isEmpty();
        assertThat(job.pricePolicyVersion()).isEqualTo("BYBIT-PRICE-1.0");
        assertThat(job.calculationPolicyVersion()).isEqualTo("CALC-1.0");
        UUID conversion=conversionIdOf(accepted.jobId());
        assertThat(calculableUsd(conversion,"conv-usdt-in")).isEqualTo("100");
        assertThat(calculableUsd(conversion,"conv-btc-out")).isEqualTo("-32500.125");
        assertThat(calculableUsd(conversion,"conv-eth-in")).isEqualTo("5998.99996");
        assertThat(jdbc().sql("SELECT evidence_type||'|'||estimated||'|'||price_policy_version||'|'||calculation_policy_version "
                + "FROM sync_calculable_cashflow WHERE conversion_id=:c ORDER BY source_id").param("c",conversion)
                .query(String.class).list()).containsExactly("SPOT_KLINE_CLOSE|true|BYBIT-PRICE-1.0|CALC-1.0",
                        "SPOT_KLINE_CLOSE|true|BYBIT-PRICE-1.0|CALC-1.0","DIRECT_NORMALIZATION|false|BYBIT-PRICE-1.0|CALC-1.0");
        assertThat(jdbc().sql("SELECT symbol||'|'||selection_rule||'|'||lag_minutes||'|'||close_price FROM "
                + "sync_cashflow_conversion_leg l JOIN sync_cashflow_conversion_flow f ON f.conversion_id=l.conversion_id "
                + "AND f.source_event_id=l.source_event_id WHERE l.conversion_id=:c ORDER BY f.source_id,l.leg_index")
                .param("c",conversion).query(String.class).list()).containsExactly("BTCUSDT|EVENT_MINUTE|0|65000.25",
                        "ETHUSDC|EVENT_MINUTE|0|3000.1","USDCUSDT|EVENT_MINUTE|0|0.9998");
        // AC-3 provenance: the kline request window that returned the cited candle.
        assertThat(jdbc().sql("SELECT c.request_start_at FROM sync_market_candle c JOIN sync_cashflow_conversion_leg l "
                + "ON l.candle_id=c.candle_id WHERE l.conversion_id=:c AND l.symbol='BTCUSDT'").param("c",conversion)
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(base.minusSeconds(300));
        assertThat(jdbc().sql("SELECT c.request_end_at FROM sync_market_candle c JOIN sync_cashflow_conversion_leg l "
                + "ON l.candle_id=c.candle_id WHERE l.conversion_id=:c AND l.symbol='BTCUSDT'").param("c",conversion)
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(base);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.SERVER_TIME_PATH)).isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.INSTRUMENTS_INFO_PATH)).isEqualTo(4);
        for (String symbol : new String[] {"BTCUSDT","ETHUSDT","ETHUSDC","USDCUSDT"})
            assertThat(BybitStubConfiguration.requestCount(DevBybitStub.INSTRUMENTS_INFO_PATH,symbol)).as(symbol).isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH)).isEqualTo(3);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH,"ETHUSDT")).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_current_snapshot").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT to_regclass('active_plan') IS NULL").query(Boolean.class).single()).isTrue();

        // A repeated attempt with the same inputs and evidence reuses the bundle; the event-minute
        // candles come from the shared evidence, so no kline is requested.
        BybitStubConfiguration.resetRequestCount();
        var replay=sync.admitCashflowConversion(user,intent,"conversion-command-003").orElseThrow();
        assertThat(replay.jobId()).isNotEqualTo(accepted.jobId());
        assertThat(awaitConversion(replay.jobId())).isEqualTo("CONVERTED_ESTIMATED");
        assertThat(conversionIdOf(replay.jobId())).isEqualTo(conversion);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH)).isZero();
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.SERVER_TIME_PATH)).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_bundle WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_calculable_cashflow WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isEqualTo(3);

        // Another user's flow on the same symbol and minute reads the shared candle as well.
        UUID other=connect(DevBybitStub.Scenario.UTA2);
        UUID otherIntent=classifiedIntent(other,"conversion-other",new Flow("other-btc-in","BTC","0.1",base.plusSeconds(30)));
        BybitStubConfiguration.resetRequestCount();
        UUID otherJob=sync.admitCashflowConversion(other,otherIntent,"conversion-other-command-001").orElseThrow().jobId();
        assertThat(awaitConversion(otherJob)).isEqualTo("CONVERTED_ESTIMATED");
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH)).isZero();
        assertThat(calculableUsd(conversionIdOf(otherJob),"other-btc-in")).isEqualTo("6500.025");

        // A new revision of the cited candle produces a new bundle that points at the old one.
        jdbc().sql("INSERT INTO sync_market_candle(candle_id,provider,category,symbol,interval_code,start_at,end_at,"
                + "revision,close_price,evidence_hash,policy_version,observed_at,request_start_at,request_end_at) SELECT :id,"
                + "provider,category,symbol,interval_code,start_at,end_at,2,'65010',:hash,policy_version,observed_at,"
                + "request_start_at,request_end_at FROM sync_market_candle "
                + "WHERE symbol='BTCUSDT' AND start_at=:start AND revision=1")
            .param("id",UUID.randomUUID()).param("hash","d".repeat(64))
            .param("start",java.time.OffsetDateTime.ofInstant(base,java.time.ZoneOffset.UTC)).update();
        UUID revisedJob=sync.admitCashflowConversion(user,intent,"conversion-command-004").orElseThrow().jobId();
        assertThat(awaitConversion(revisedJob)).isEqualTo("CONVERTED_ESTIMATED");
        UUID revised=conversionIdOf(revisedJob);
        assertThat(revised).isNotEqualTo(conversion);
        assertThat(jdbc().sql("SELECT previous_conversion_id::text||'|'||change_reason FROM sync_cashflow_conversion_bundle "
                + "WHERE conversion_id=:c").param("c",revised).query(String.class).single())
                .isEqualTo(conversion+"|PRICE_REVISION");
        assertThat(calculableUsd(revised,"conv-btc-out")).isEqualTo("-32505");
        assertThat(calculableUsd(conversion,"conv-btc-out")).as("the earlier bundle is preserved").isEqualTo("-32500.125");

        portfolio.erasePortfolioData(user);
        sync.eraseSyncData(user);
        source.eraseSourceData(user);
        for (String table : new String[] {"sync_cashflow_conversion_job","sync_cashflow_conversion_bundle",
                "sync_calculable_cashflow"}) {
            assertThat(jdbc().sql("SELECT count(*) FROM "+table+" WHERE user_id=:u").param("u",user)
                    .query(Integer.class).single()).as(table).isZero();
        }
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_flow WHERE conversion_id IN (:a,:b)")
                .param("a",conversion).param("b",revised).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_bundle WHERE user_id=:u").param("u",other)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_calculable_cashflow WHERE user_id=:u").param("u",other)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_market_candle WHERE symbol='BTCUSDT' AND start_at=:start")
                .param("start",java.time.OffsetDateTime.ofInstant(base,java.time.ZoneOffset.UTC))
                .query(Integer.class).single()).as("shared public evidence survives user erasure").isEqualTo(2);
        assertThat(sync.admitCashflowConversion(user,intent,"conversion-command-005")).isEmpty();
    }

    @Test
    void cashflowConversionHoldsFlowsWithoutAcceptablePriceEvidence() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=classifiedIntent(user,"conversion-missing",
                new Flow("gap-lag-3","GAP","1.5",base.plusSeconds(190)),
                new Flow("gap-lag-6","GAP","-2.000000000000000001",base.plusSeconds(365)),
                new Flow("new-no-history","NEW","3",base.plusSeconds(20)),
                new Flow("halt-not-trading","HALT","4",base.plusSeconds(25)),
                new Flow("zzz-no-route","ZZZ","5",base.plusSeconds(30)),
                new Flow("zero-close","ZERO","1",base.plusSeconds(35)));
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitCashflowConversion(user,intent,"conversion-missing-command-001").orElseThrow().jobId();
        assertThat(awaitConversion(job)).isEqualTo("CONVERSION_REQUIRED");

        var state=sync.cashflowConversionState(user,intent).job();
        assertThat(state).isNotNull();
        assertThat(state.retryable()).isFalse();
        assertThat(state.calculable()).isFalse();
        assertThat(state.reasonCode()).isEqualTo("PRICE_ROUTE_UNAVAILABLE");
        assertThat(state.estimatedCount()).isEqualTo(1);
        assertThat(state.conversionRequiredCount()).isEqualTo(5);
        assertThat(state.pendingAssets()).extracting(SyncJobApi.ConversionPendingAsset::assetCode)
                .containsExactly("GAP","HALT","NEW","ZERO","ZZZ");
        assertThat(state.pendingAssets().getFirst().flows()).singleElement().satisfies(flow -> {
            assertThat(flow.direction()).isEqualTo("WITHDRAWAL");
            assertThat(flow.quantity()).isEqualTo("2.000000000000000001");
            assertThat(flow.occurredAt()).isEqualTo(base.plusSeconds(365));
            assertThat(flow.reasonCode()).isEqualTo("PRICE_CANDLE_MISSING");
            assertThat(flow.retryable()).isFalse();
        });
        UUID conversion=conversionIdOf(job);
        assertThat(jdbc().sql("SELECT source_id||'|'||status||'|'||COALESCE(reason_code,'-')||'|'||signed_quantity "
                + "FROM sync_cashflow_conversion_flow WHERE conversion_id=:c ORDER BY source_id").param("c",conversion)
                .query(String.class).list()).containsExactly(
                        "gap-lag-3|CONVERTED_ESTIMATED|-|1.5",
                        "gap-lag-6|CONVERSION_REQUIRED|PRICE_CANDLE_MISSING|-2.000000000000000001",
                        "halt-not-trading|CONVERSION_REQUIRED|PRICE_ROUTE_UNAVAILABLE|4",
                        "new-no-history|CONVERSION_REQUIRED|PRICE_CANDLE_MISSING|3",
                        "zero-close|CONVERSION_REQUIRED|PRICE_NON_POSITIVE|1",
                        "zzz-no-route|CONVERSION_REQUIRED|PRICE_ROUTE_UNAVAILABLE|5");
        assertThat(jdbc().sql("SELECT selection_rule||'|'||lag_minutes FROM sync_cashflow_conversion_leg WHERE conversion_id=:c")
                .param("c",conversion).query(String.class).single()).isEqualTo("PRIOR_CLOSED_MINUTE|3");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_calculable_cashflow WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).as("a held bundle is never calculable").isZero();
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH,"GAPUSDT"))
                .as("both GAP windows merge into one request").isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH,"HALTUSDT")).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_current_snapshot").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void providerFailureIsRetryableWhileContradictoryOrOutOfRangePricesNeedReview() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID outage=connect(DevBybitStub.Scenario.UTA2);
        UUID outageIntent=classifiedIntent(outage,"conversion-outage",new Flow("down-in","DOWN","1",base.plusSeconds(10)));
        BybitStubConfiguration.resetRequestCount();
        UUID first=sync.admitCashflowConversion(outage,outageIntent,"conversion-outage-command-001").orElseThrow().jobId();
        assertThat(awaitConversion(first)).isEqualTo("CONVERSION_REQUIRED");
        var failed=sync.cashflowConversionState(outage,outageIntent).job();
        assertThat(failed.retryable()).isTrue();
        assertThat(failed.reasonCode()).isEqualTo("PRICE_PROVIDER_UNAVAILABLE");
        Thread.sleep(QUIET_WINDOW);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH,"DOWNUSDT"))
                .as("no automatic retry").isEqualTo(1);
        UUID retried=sync.admitCashflowConversion(outage,outageIntent,"conversion-outage-command-002").orElseThrow().jobId();
        assertThat(retried).isNotEqualTo(first);
        assertThat(awaitConversion(retried)).isEqualTo("CONVERSION_REQUIRED");
        assertThat(conversionIdOf(retried)).as("the same failure is the same result").isEqualTo(conversionIdOf(first));

        UUID review=connect(DevBybitStub.Scenario.UTA2);
        UUID reviewIntent=classifiedIntent(review,"conversion-review",new Flow("dup-in","DUP","1",base.plusSeconds(10)),
                new Flow("bad-in","BAD","1",base.plusSeconds(15)),new Flow("tiny-in","TINY","0.5",base.plusSeconds(20)));
        UUID reviewJob=sync.admitCashflowConversion(review,reviewIntent,"conversion-review-command-001").orElseThrow().jobId();
        assertThat(awaitConversion(reviewJob)).isEqualTo("REVIEW_REQUIRED");
        var reviewed=sync.cashflowConversionState(review,reviewIntent).job();
        assertThat(reviewed.retryable()).isFalse();
        assertThat(reviewed.calculable()).isFalse();
        assertThat(reviewed.reviewCount()).isEqualTo(3);
        assertThat(jdbc().sql("SELECT source_id||'|'||reason_code FROM sync_cashflow_conversion_flow WHERE conversion_id=:c "
                + "ORDER BY source_id").param("c",conversionIdOf(reviewJob)).query(String.class).list())
                .containsExactly("bad-in|PRICE_RESPONSE_MALFORMED","dup-in|PRICE_CANDLE_CONFLICT",
                        "tiny-in|CALCULATION_RANGE_EXCEEDED");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_market_candle WHERE symbol IN ('DUPUSDT','BADUSDT')")
                .query(Integer.class).single()).as("contradicted or malformed rows are not evidence").isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_calculable_cashflow WHERE user_id IN (:a,:b)")
                .param("a",outage).param("b",review).query(Integer.class).single()).isZero();
    }

    @Test
    void anUnclosedEventMinuteIsRetryableAndIsNotReplacedByAnEarlierCandle() throws Exception {
        clearMarketEvidence();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        Instant future=Instant.now().plusSeconds(180);
        UUID intent=classifiedIntent(user,"conversion-open",new Flow("open-btc-in","BTC","1",future));
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitCashflowConversion(user,intent,"conversion-open-command-001").orElseThrow().jobId();
        assertThat(awaitConversion(job)).isEqualTo("CONVERSION_REQUIRED");
        var state=sync.cashflowConversionState(user,intent).job();
        assertThat(state.reasonCode()).isEqualTo("PRICE_CANDLE_NOT_CLOSED");
        assertThat(state.retryable()).isTrue();
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH)).isZero();
        assertThat(jdbc().sql("SELECT reference_time FROM sync_cashflow_conversion_job WHERE job_id=:j").param("j",job)
                .query(java.time.OffsetDateTime.class).single()).isNotNull();
    }

    @Test
    void conversionAdmissionRequiresTheOwnersCompleteClassificationAndNeverCallsTheProvider() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=initialIntent(user,"conversion-unclassified");
        UUID collection=sync.admitInitialInputs(user,intent,"conversion-unclassified-inputs-001").orElseThrow().jobId();
        assertThat(awaitInitial(user,collection)).isEqualTo("INPUTS_COMPLETE");
        UUID other=connect(DevBybitStub.Scenario.UTA2);
        UUID otherIntent=classifiedIntent(other,"conversion-foreign",new Flow("foreign-usdt","USDT","1",
                closedTenMinuteBoundary().plusSeconds(1)));
        UUID review=connect(DevBybitStub.Scenario.UTA2);
        UUID reviewIntent=reviewedIntent(review,"conversion-reviewed");
        BybitStubConfiguration.resetRequestCount();

        assertThat(sync.admitCashflowConversion(user,intent,"conversion-none-command-001")).as("no classification").isEmpty();
        assertThat(sync.cashflowConversionState(user,intent)).isEqualTo(new SyncJobApi.ConversionState(true,null));
        assertThat(sync.admitCashflowConversion(user,otherIntent,"conversion-foreign-command-001")).as("foreign").isEmpty();
        assertThat(sync.cashflowConversionState(user,otherIntent).permitted()).isFalse();
        assertThat(sync.admitCashflowConversion(review,reviewIntent,"conversion-review-command-001"))
                .as("classification needs review").isEmpty();
        assertThat(sync.admitCashflowConversion(null,otherIntent,"conversion-null-command-001")).isEmpty();
        assertThat(sync.admitCashflowConversion(other,otherIntent,"short")).as("invalid key").isEmpty();
        jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) VALUES (:id,'initial-source-event-collection')")
                .param("id",UUID.randomUUID()).update();
        assertThat(sync.admitCashflowConversion(other,otherIntent,"conversion-restricted-command-001"))
                .as("restricted").isEmpty();
        assertThat(sync.cashflowConversionState(other,otherIntent).permitted()).isFalse();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_job").query(Integer.class).single()).isZero();
        assertThat(BybitStubConfiguration.requestCount()).isZero();
    }

    @Test
    void erasureAndRestrictionWinOverALateConversionWorker() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID kept=connect(DevBybitStub.Scenario.UTA2);
        UUID keptIntent=classifiedIntent(kept,"conversion-kept",new Flow("kept-usdt","USDT","7",base.plusSeconds(3)));
        UUID keptJob=sync.admitCashflowConversion(kept,keptIntent,"conversion-kept-command-001").orElseThrow().jobId();
        assertThat(awaitConversion(keptJob)).isEqualTo("CONVERTED");

        UUID erased=connect(DevBybitStub.Scenario.UTA2);
        UUID erasedIntent=classifiedIntent(erased,"conversion-erased",new Flow("slow-in","SLOW","1",base.plusSeconds(4)));
        BybitStubConfiguration.resetRequestCount();
        UUID erasedJob=sync.admitCashflowConversion(erased,erasedIntent,"conversion-erased-command-001").orElseThrow().jobId();
        awaitKlineCall("SLOWUSDT",1);
        portfolio.erasePortfolioData(erased);
        sync.eraseSyncData(erased);
        source.eraseSourceData(erased);
        Thread.sleep(2500);
        for (String table : new String[] {"sync_cashflow_conversion_job","sync_cashflow_conversion_bundle",
                "sync_calculable_cashflow"}) {
            assertThat(jdbc().sql("SELECT count(*) FROM "+table+" WHERE user_id=:u").param("u",erased)
                    .query(Integer.class).single()).as(table).isZero();
        }
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_input WHERE job_id=:j").param("j",erasedJob)
                .query(Integer.class).single()).isZero();
        assertThat(conversionIdOf(keptJob)).isNotNull();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_calculable_cashflow WHERE user_id=:u").param("u",kept)
                .query(Integer.class).single()).isEqualTo(1);

        // Another minute: the erased user's late worker left the base-minute candle in shared
        // evidence (public facts, no user data), and a cached event minute needs no kline call.
        UUID restricted=connect(DevBybitStub.Scenario.UTA2);
        UUID restrictedIntent=classifiedIntent(restricted,"conversion-restricted",
                new Flow("slow-restricted","SLOW","2",base.plusSeconds(125)));
        BybitStubConfiguration.resetRequestCount();
        UUID restrictedJob=sync.admitCashflowConversion(restricted,restrictedIntent,"conversion-restricted-command-001")
                .orElseThrow().jobId();
        awaitKlineCall("SLOWUSDT",1);
        jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) VALUES (:id,'initial-source-event-collection')")
                .param("id",UUID.randomUUID()).update();
        assertThat(awaitConversion(restrictedJob)).isEqualTo("CANCELLED");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_bundle WHERE user_id=:u").param("u",restricted)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_calculable_cashflow WHERE user_id=:u").param("u",restricted)
                .query(Integer.class).single()).isZero();
        assertThat(sync.admitCashflowConversion(restricted,restrictedIntent,"conversion-restricted-command-002")).isEmpty();
    }

    @Test
    void staleConversionFenceCannotPublishOrFinishTheAttempt() throws Exception {
        clearMarketEvidence();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=classifiedIntent(user,"conversion-stale-fence",
                new Flow("stale-slow-in","SLOW","3",closedTenMinuteBoundary().plusSeconds(245)));
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitCashflowConversion(user,intent,"conversion-stale-fence-command-001").orElseThrow().jobId();
        awaitKlineCall("SLOWUSDT",1);
        long claimed=jdbc().sql("SELECT fencing_generation FROM sync_cashflow_conversion_job WHERE job_id=:j")
                .param("j",job).query(Long.class).single();
        // Another holder takes the lease while the first worker is still inside the provider call.
        jdbc().sql("UPDATE sync_cashflow_conversion_job SET fencing_generation=fencing_generation+1 WHERE job_id=:j")
                .param("j",job).update();
        Thread.sleep(3000);

        var row=jdbc().sql("SELECT status||'|'||fencing_generation||'|'||(finished_at IS NULL)||'|'||(conversion_id IS NULL) "
                + "FROM sync_cashflow_conversion_job WHERE job_id=:j").param("j",job).query(String.class).single();
        assertThat(row).as("the late worker neither finishes nor points the attempt at a result")
                .isEqualTo("RUNNING|"+(claimed+1)+"|true|true");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_bundle WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isZero();
        assertNoConversionRowsFor(job);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_calculable_cashflow WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"INTENT_REPLACED", "CLASSIFICATION_NOT_COMPLETE"})
    void upstreamChangeBeforePublicationCancelsTheAttemptWithoutResults(String change) throws Exception {
        clearMarketEvidence();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        String prefix="conversion-upstream-"+change.toLowerCase(java.util.Locale.ROOT).replace('_','-');
        UUID intent=classifiedIntent(user,prefix,new Flow(prefix+"-usdt","USDT","9",closedTenMinuteBoundary().plusSeconds(6)),
                new Flow(prefix+"-slow","SLOW","4",closedTenMinuteBoundary().plusSeconds(308)));
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitCashflowConversion(user,intent,prefix+"-command-001").orElseThrow().jobId();
        awaitKlineCall("SLOWUSDT",1);
        if (change.equals("INTENT_REPLACED")) {
            jdbc().sql("UPDATE plan_condition_intent SET status='REPLACED' WHERE intent_id=:i AND status='OPEN'")
                    .param("i",intent).update();
        } else {
            // The classification attempt that produced the pinned bundle is no longer COMPLETE.
            assertThat(jdbc().sql("UPDATE sync_cashflow_classification_job SET status='NEEDS_REVIEW' WHERE job_id="
                    + "(SELECT b.job_id FROM sync_cashflow_conversion_job c JOIN sync_cashflow_classification_bundle b "
                    + "ON b.classification_id=c.classification_id WHERE c.job_id=:j)")
                    .param("j",job).update()).isEqualTo(1);
        }

        assertThat(awaitConversion(job)).isEqualTo("CANCELLED");
        assertThat(jdbc().sql("SELECT failure_code||'|'||(conversion_id IS NULL) FROM sync_cashflow_conversion_job "
                + "WHERE job_id=:j").param("j",job).query(String.class).single()).isEqualTo("NOT_PERMITTED|true");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_bundle WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isZero();
        assertNoConversionRowsFor(job);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_calculable_cashflow WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isZero();
        assertThat(sync.admitCashflowConversion(user,intent,prefix+"-command-002"))
                .as("the changed upstream cannot be converted again").isEmpty();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_job WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aUsdcFlowAndACrossRouteFlowShareOneMergedUsdcUsdtRequest() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        // Two minutes apart: the USDC flow needs USDCUSDT in round two, the ETH cross route in round
        // three. The first request must already cover both minutes.
        UUID intent=classifiedIntent(user,"conversion-usdc-merge",new Flow("merge-usdc-in","USDC","100",base.plusSeconds(10)),
                new Flow("merge-eth-in","ETH","2",base.plusSeconds(130)));
        BybitStubConfiguration.resetRequestCount();
        UUID job=sync.admitCashflowConversion(user,intent,"conversion-usdc-merge-command-001").orElseThrow().jobId();
        assertThat(awaitConversion(job)).isEqualTo("CONVERTED_ESTIMATED");

        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH,"USDCUSDT"))
                .as("one merged request per symbol per attempt").isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH,"ETHUSDC")).isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.INSTRUMENTS_INFO_PATH,"USDCUSDT")).isEqualTo(1);
        UUID conversion=conversionIdOf(job);
        assertThat(calculableUsd(conversion,"merge-usdc-in")).isEqualTo("99.98");
        assertThat(calculableUsd(conversion,"merge-eth-in")).isEqualTo("5998.99996");
        assertThat(jdbc().sql("SELECT request_start_at,request_end_at FROM sync_market_candle WHERE symbol='USDCUSDT'")
                .query((rs,n)->rs.getObject(1,java.time.OffsetDateTime.class).toInstant()+"|"
                        +rs.getObject(2,java.time.OffsetDateTime.class).toInstant()).list())
                .as("every USDCUSDT candle came from one window covering both minutes")
                .hasSize(8).containsOnly(base.minusSeconds(300)+"|"+base.plusSeconds(120));
    }

    @Test
    void aVolumeOnlyDifferenceIsNeitherAConflictNorANewRevision() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        // An earlier observation of the GAP fallback candle with the same close but other raw fields.
        UUID earlier=UUID.randomUUID();
        jdbc().sql("INSERT INTO sync_market_candle(candle_id,provider,category,symbol,interval_code,start_at,end_at,revision,"
                + "close_price,evidence_hash,policy_version,observed_at,request_start_at,request_end_at) VALUES (:id,'BYBIT',"
                + "'spot','GAPUSDT','1',:start,:end,1,'2.5',:hash,'BYBIT-PRICE-1.0',now(),:start,:end)")
            .param("id",earlier).param("start",java.time.OffsetDateTime.ofInstant(base,java.time.ZoneOffset.UTC))
            .param("end",java.time.OffsetDateTime.ofInstant(base.plusSeconds(60),java.time.ZoneOffset.UTC))
            .param("hash","e".repeat(64)).update();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=classifiedIntent(user,"conversion-volume",new Flow("volume-in","VOL","3",base.plusSeconds(10)),
                new Flow("gap-revision-in","GAP","2",base.plusSeconds(190)));
        UUID job=sync.admitCashflowConversion(user,intent,"conversion-volume-command-001").orElseThrow().jobId();

        assertThat(awaitConversion(job)).as("rows agreeing on the close are not a contradiction")
                .isEqualTo("CONVERTED_ESTIMATED");
        UUID conversion=conversionIdOf(job);
        assertThat(calculableUsd(conversion,"volume-in")).isEqualTo("21");
        assertThat(calculableUsd(conversion,"gap-revision-in")).isEqualTo("5");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_market_candle WHERE symbol='GAPUSDT' AND start_at=:start")
                .param("start",java.time.OffsetDateTime.ofInstant(base,java.time.ZoneOffset.UTC))
                .query(Integer.class).single()).as("the same close is not a new revision").isEqualTo(1);
        assertThat(jdbc().sql("SELECT l.candle_id FROM sync_cashflow_conversion_leg l JOIN sync_cashflow_conversion_flow f "
                + "ON f.conversion_id=l.conversion_id AND f.source_event_id=l.source_event_id "
                + "WHERE l.conversion_id=:c AND f.source_id='gap-revision-in'").param("c",conversion)
                .query(UUID.class).single()).isEqualTo(earlier);
        assertThat(jdbc().sql("SELECT evidence_hash FROM sync_market_candle WHERE candle_id=:id").param("id",earlier)
                .query(String.class).single()).as("the first raw hash stays as provenance").isEqualTo("e".repeat(64));
        assertThat(jdbc().sql("SELECT count(*) FROM sync_market_candle c WHERE c.symbol='VOLUSDT'")
                .query(Integer.class).single()).as("one revision per start despite duplicated rows").isEqualTo(6);
    }

    @ParameterizedTest(name = "server time {0}")
    @CsvSource({"TIMEOUT,CONVERSION_REQUIRED,PRICE_PROVIDER_UNAVAILABLE,true",
        "OUTAGE,CONVERSION_REQUIRED,PRICE_PROVIDER_UNAVAILABLE,true",
        "MALFORMED,REVIEW_REQUIRED,PRICE_RESPONSE_MALFORMED,false"})
    void aServerTimeFailureHoldsEveryPricedFlowWithoutFurtherCalls(DevBybitStub.ServerTimeMode mode, String status,
            String reason, boolean retryable) throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        String prefix="conversion-time-"+mode.name().toLowerCase(java.util.Locale.ROOT);
        UUID intent=classifiedIntent(user,prefix,new Flow(prefix+"-btc","BTC","1",closedTenMinuteBoundary().plusSeconds(8)),
                new Flow(prefix+"-usdt","USDT","2",closedTenMinuteBoundary().plusSeconds(9)));
        BybitStubConfiguration.resetRequestCount();
        BybitStubConfiguration.serverTimeMode(mode);
        try {
            UUID job=sync.admitCashflowConversion(user,intent,prefix+"-command-001").orElseThrow().jobId();
            assertThat(awaitConversion(job)).isEqualTo(status);
            var state=sync.cashflowConversionState(user,intent).job();
            assertThat(state.reasonCode()).isEqualTo(reason);
            assertThat(state.retryable()).isEqualTo(retryable);
            assertThat(state.convertedCount()).as("the direct flow still converts").isEqualTo(1);
            assertThat(jdbc().sql("SELECT reason_code FROM sync_cashflow_conversion_flow WHERE conversion_id=:c "
                    + "AND source_id=:s").param("c",conversionIdOf(job)).param("s",prefix+"-btc")
                    .query(String.class).single()).isEqualTo(reason);
            assertThat(BybitStubConfiguration.requestCount(DevBybitStub.SERVER_TIME_PATH)).isEqualTo(1);
            assertThat(BybitStubConfiguration.requestCount(DevBybitStub.INSTRUMENTS_INFO_PATH)).isZero();
            assertThat(BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH)).isZero();
            assertThat(jdbc().sql("SELECT reference_time IS NULL FROM sync_cashflow_conversion_job WHERE job_id=:j")
                    .param("j",job).query(Boolean.class).single()).isTrue();
        } finally {
            BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.NORMAL);
        }
    }

    @Test
    void anInstrumentOutageIsRetryableWhileDuplicateOrForeignListsNeedReview() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=classifiedIntent(user,"conversion-instruments",new Flow("inst-down","IDOWN","1",base.plusSeconds(11)),
                new Flow("inst-dup","IDUP","1",base.plusSeconds(12)),new Flow("inst-foreign","IFOR","1",base.plusSeconds(13)));
        UUID job=sync.admitCashflowConversion(user,intent,"conversion-instruments-command-001").orElseThrow().jobId();
        assertThat(awaitConversion(job)).isEqualTo("REVIEW_REQUIRED");

        assertThat(jdbc().sql("SELECT source_id||'|'||status||'|'||reason_code||'|'||retryable FROM sync_cashflow_conversion_flow "
                + "WHERE conversion_id=:c ORDER BY source_id").param("c",conversionIdOf(job)).query(String.class).list())
                .containsExactly("inst-down|CONVERSION_REQUIRED|PRICE_PROVIDER_UNAVAILABLE|true",
                        "inst-dup|REVIEW_REQUIRED|PRICE_RESPONSE_MALFORMED|false",
                        "inst-foreign|REVIEW_REQUIRED|PRICE_RESPONSE_MALFORMED|false");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_market_instrument_observation WHERE symbol IN "
                + "('IDOWNUSDT','IDUPUSDT','IFORUSDT','IFORUSDC')").query(Integer.class).single())
                .as("an unrecognized answer is not recorded as 'not listed'").isZero();
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.INSTRUMENTS_INFO_PATH,"IFORUSDC"))
                .as("no fall-through to the cross route").isZero();
        var state=sync.cashflowConversionState(user,intent).job();
        assertThat(state.retryable()).isFalse();
        assertThat(state.pendingAssets()).extracting(SyncJobApi.ConversionPendingAsset::assetCode)
                .containsExactly("IDOWN","IDUP","IFOR");
        assertThat(state.pendingAssets()).flatExtracting(SyncJobApi.ConversionPendingAsset::flows)
                .extracting(SyncJobApi.ConversionPendingFlow::status)
                .containsExactly("CONVERSION_REQUIRED","REVIEW_REQUIRED","REVIEW_REQUIRED");
    }

    @Test
    void aHeldAttemptThenASuccessChainsEvidenceChangesToTheResultLastFinishedWith() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=classifiedIntent(user,"conversion-chain",new Flow("chain-btc","BTC","2",base.plusSeconds(14)));
        try {
            BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.OUTAGE);
            UUID heldJob=sync.admitCashflowConversion(user,intent,"conversion-chain-command-001").orElseThrow().jobId();
            assertThat(awaitConversion(heldJob)).isEqualTo("CONVERSION_REQUIRED");
            UUID held=conversionIdOf(heldJob);

            BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.NORMAL);
            UUID successJob=sync.admitCashflowConversion(user,intent,"conversion-chain-command-002").orElseThrow().jobId();
            assertThat(awaitConversion(successJob)).isEqualTo("CONVERTED_ESTIMATED");
            UUID success=conversionIdOf(successJob);
            assertThat(chainOf(success)).isEqualTo(held+"|EVIDENCE_CHANGE");
            assertThat(calculableRows(held)).as("a held bundle never owns calculable rows").isZero();
            assertThat(calculableRows(success)).isEqualTo(1);

            // A → B → A again: the same failure reuses the held bundle, which becomes the current result.
            BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.OUTAGE);
            UUID againJob=sync.admitCashflowConversion(user,intent,"conversion-chain-command-003").orElseThrow().jobId();
            assertThat(awaitConversion(againJob)).isEqualTo("CONVERSION_REQUIRED");
            assertThat(conversionIdOf(againJob)).isEqualTo(held);

            // The next new version chains to that current result, not to the latest-created bundle.
            BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.NORMAL);
            jdbc().sql("INSERT INTO sync_market_candle(candle_id,provider,category,symbol,interval_code,start_at,end_at,"
                    + "revision,close_price,evidence_hash,policy_version,observed_at,request_start_at,request_end_at) SELECT :id,"
                    + "provider,category,symbol,interval_code,start_at,end_at,2,'65100',:hash,policy_version,observed_at,"
                    + "request_start_at,request_end_at FROM sync_market_candle WHERE symbol='BTCUSDT' AND start_at=:start "
                    + "AND revision=1")
                .param("id",UUID.randomUUID()).param("hash","f".repeat(64))
                .param("start",java.time.OffsetDateTime.ofInstant(base,java.time.ZoneOffset.UTC)).update();
            UUID revisedJob=sync.admitCashflowConversion(user,intent,"conversion-chain-command-004").orElseThrow().jobId();
            assertThat(awaitConversion(revisedJob)).isEqualTo("CONVERTED_ESTIMATED");
            UUID revised=conversionIdOf(revisedJob);
            assertThat(revised).isNotIn(held,success);
            assertThat(chainOf(revised)).isEqualTo(held+"|EVIDENCE_CHANGE");
            assertThat(calculableUsd(revised,"chain-btc")).isEqualTo("130200");
            assertThat(calculableUsd(success,"chain-btc")).isEqualTo("130000.5");
            assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_bundle WHERE user_id=:u").param("u",user)
                    .query(Integer.class).single()).isEqualTo(3);
        } finally {
            BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.NORMAL);
        }
    }

    @Test
    void aRestrictionCommittedWithTheAdmissionCancelsBeforeAnyProviderCall() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=classifiedIntent(user,"conversion-restrict-early",
                new Flow("early-btc","BTC","1",closedTenMinuteBoundary().plusSeconds(15)));
        BybitStubConfiguration.resetRequestCount();
        // One transaction: the worker can never see the queued attempt without the restriction.
        UUID job=new TransactionTemplate(manager).execute(status -> {
            UUID admitted=sync.admitCashflowConversion(user,intent,"conversion-restrict-early-command-001").orElseThrow().jobId();
            jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) VALUES (:id,'initial-source-event-collection')")
                    .param("id",UUID.randomUUID()).update();
            return admitted;
        });
        assertThat(awaitConversion(job)).isEqualTo("CANCELLED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_cashflow_conversion_job WHERE job_id=:j").param("j",job)
                .query(String.class).single()).isEqualTo("NOT_PERMITTED");
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_bundle WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void admissionRefusesADeletingAccountOrAnInactiveOrReplacedConnectionWithoutProviderCalls() throws Exception {
        Instant at=closedTenMinuteBoundary().plusSeconds(16);
        UUID deleting=connect(DevBybitStub.Scenario.UTA2);
        UUID deletingIntent=classifiedIntent(deleting,"conversion-deleting",new Flow("deleting-usdt","USDT","1",at));
        UUID revoked=connect(DevBybitStub.Scenario.UTA2);
        UUID revokedIntent=classifiedIntent(revoked,"conversion-revoked",new Flow("revoked-usdt","USDT","1",at));
        UUID replaced=connect(DevBybitStub.Scenario.UTA2);
        UUID replacedIntent=classifiedIntent(replaced,"conversion-replaced",new Flow("replaced-usdt","USDT","1",at));
        BybitStubConfiguration.resetRequestCount();

        accountDeletion().beginDeletion(deleting);
        jdbc().sql("UPDATE source_connection SET status='REVOKED' WHERE user_id=:u").param("u",revoked).update();
        jdbc().sql("UPDATE source_connection SET status='REVOKED' WHERE user_id=:u").param("u",replaced).update();
        String uid="replacement-"+UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(status -> source.store(replaced,SourceProvider.BYBIT,
                new SourceVerification.Verified(uid,Instant.now(),"bybit-readonly-uta-1.0"),
                new SourceCredentials(BybitStubConfiguration.apiKey(DevBybitStub.Scenario.UTA2,uid),
                        BybitStubConfiguration.newSecret())));

        assertThat(sync.admitCashflowConversion(deleting,deletingIntent,"conversion-deleting-command-001")).isEmpty();
        assertThat(sync.cashflowConversionState(deleting,deletingIntent).permitted()).isFalse();
        assertThat(sync.admitCashflowConversion(revoked,revokedIntent,"conversion-revoked-command-001")).isEmpty();
        assertThat(sync.admitCashflowConversion(replaced,replacedIntent,"conversion-replaced-command-001")).isEmpty();
        assertThat(sync.cashflowConversionState(replaced,replacedIntent).permitted()).isFalse();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_job").query(Integer.class).single()).isZero();
        Thread.sleep(QUIET_WINDOW);
        assertThat(BybitStubConfiguration.requestCount()).isZero();
    }

    @Test
    void aPublicationBlockedInsideThePublishingTransactionLeavesNoResultRows() throws Exception {
        clearMarketEvidence();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=classifiedIntent(user,"conversion-blocked",
                new Flow("blocked-btc","BTC","1",closedTenMinuteBoundary().plusSeconds(17)),
                new Flow("blocked-usdt","USDT","1",closedTenMinuteBoundary().plusSeconds(18)));
        // The restriction appears inside the publishing transaction, after the bundle row: the final
        // permission check sees it and the whole publication rolls back.
        jdbc().sql("CREATE FUNCTION restrict_on_conversion_bundle() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "INSERT INTO operations_restriction(restriction_id,command) VALUES (gen_random_uuid(),"
                + "'initial-source-event-collection'); RETURN NEW; END $$").update();
        jdbc().sql("CREATE TRIGGER restrict_on_conversion_bundle AFTER INSERT ON sync_cashflow_conversion_bundle "
                + "FOR EACH ROW EXECUTE FUNCTION restrict_on_conversion_bundle()").update();
        try {
            UUID job=sync.admitCashflowConversion(user,intent,"conversion-blocked-command-001").orElseThrow().jobId();
            assertThat(awaitConversion(job)).isEqualTo("CANCELLED");
            assertThat(jdbc().sql("SELECT failure_code||'|'||(conversion_id IS NULL) FROM sync_cashflow_conversion_job "
                    + "WHERE job_id=:j").param("j",job).query(String.class).single()).isEqualTo("NOT_PERMITTED|true");
            assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_bundle WHERE user_id=:u").param("u",user)
                    .query(Integer.class).single()).isZero();
            assertNoConversionRowsFor(job);
            assertThat(jdbc().sql("SELECT count(*) FROM operations_restriction").query(Integer.class).single())
                    .as("the restriction written inside the rolled-back transaction is gone too").isZero();
        } finally {
            jdbc().sql("DROP TRIGGER restrict_on_conversion_bundle ON sync_cashflow_conversion_bundle").update();
            jdbc().sql("DROP FUNCTION restrict_on_conversion_bundle()").update();
        }
    }

    @Test
    void anExpiredLeaseWithAFixedReferenceTimeIsReclaimedWithoutAnotherServerTimeCall() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=classifiedIntent(user,"conversion-reclaim",new Flow("reclaim-btc","BTC","1",base.plusSeconds(19)));
        Instant fixed=Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        BybitStubConfiguration.resetRequestCount();
        // A worker claimed the attempt, fixed its reference time and died: the lease has expired.
        UUID job=new TransactionTemplate(manager).execute(status -> {
            UUID admitted=sync.admitCashflowConversion(user,intent,"conversion-reclaim-command-001").orElseThrow().jobId();
            jdbc().sql("UPDATE sync_cashflow_conversion_job SET status='RUNNING',fencing_generation=1,"
                    + "lease_until=now()-interval '1 minute',heartbeat_at=now()-interval '2 minutes',reference_time=:t "
                    + "WHERE job_id=:j").param("t",java.time.OffsetDateTime.ofInstant(fixed,java.time.ZoneOffset.UTC))
                    .param("j",admitted).update();
            return admitted;
        });
        assertThat(awaitConversion(job)).isEqualTo("CONVERTED_ESTIMATED");
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.SERVER_TIME_PATH)).isZero();
        assertThat(jdbc().sql("SELECT fencing_generation FROM sync_cashflow_conversion_job WHERE job_id=:j").param("j",job)
                .query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc().sql("SELECT reference_time FROM sync_cashflow_conversion_bundle WHERE conversion_id=:c")
                .param("c",conversionIdOf(job)).query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(fixed);
        assertThat(calculableUsd(conversionIdOf(job),"reclaim-btc")).isEqualTo("65000.25");
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void anInjectedDatabaseFailureInterruptsTheAttemptWithoutResultsOrRetries(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        clearMarketEvidence();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=classifiedIntent(user,"conversion-interrupted",
                new Flow("interrupted-btc","BTC","1",closedTenMinuteBoundary().plusSeconds(21)));
        jdbc().sql("CREATE FUNCTION fail_conversion_flow() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'injected conversion failure detail'; END $$").update();
        jdbc().sql("CREATE TRIGGER fail_conversion_flow BEFORE INSERT ON sync_cashflow_conversion_flow "
                + "FOR EACH ROW EXECUTE FUNCTION fail_conversion_flow()").update();
        try {
            BybitStubConfiguration.resetRequestCount();
            UUID job=sync.admitCashflowConversion(user,intent,"conversion-interrupted-command-001").orElseThrow().jobId();
            assertThat(awaitConversion(job)).isEqualTo("CONVERSION_REQUIRED");
            assertThat(jdbc().sql("SELECT failure_code||'|'||retryable||'|'||(conversion_id IS NULL) FROM "
                    + "sync_cashflow_conversion_job WHERE job_id=:j").param("j",job).query(String.class).single())
                    .isEqualTo("CONVERSION_INTERRUPTED|true|true");
            var state=sync.cashflowConversionState(user,intent).job();
            assertThat(state.convertedCount()).as("no outcome counts without a result").isNull();
            assertThat(state.pendingAssets()).isEmpty();
            assertThat(jdbc().sql("SELECT count(*) FROM sync_cashflow_conversion_bundle WHERE user_id=:u").param("u",user)
                    .query(Integer.class).single()).isZero();
            assertNoConversionRowsFor(job);
            int calls=BybitStubConfiguration.requestCount();
            Thread.sleep(QUIET_WINDOW);
            assertThat(BybitStubConfiguration.requestCount()).as("no automatic retry").isEqualTo(calls);
            assertThat(output.getAll()).contains("cash-flow conversion interrupted: job="+job)
                    .doesNotContain("injected conversion failure detail");
        } finally {
            jdbc().sql("DROP TRIGGER fail_conversion_flow ON sync_cashflow_conversion_flow").update();
            jdbc().sql("DROP FUNCTION fail_conversion_flow()").update();
        }
    }

    private String chainOf(UUID conversion) {
        return jdbc().sql("SELECT previous_conversion_id::text||'|'||change_reason FROM sync_cashflow_conversion_bundle "
                + "WHERE conversion_id=:c").param("c",conversion).query(String.class).single();
    }

    private int calculableRows(UUID conversion) {
        return jdbc().sql("SELECT count(*) FROM sync_calculable_cashflow WHERE conversion_id=:c").param("c",conversion)
                .query(Integer.class).single();
    }

    /** No per-flow, leg or calculable row cites the attempt's inputs. */
    private void assertNoConversionRowsFor(UUID job) {
        for (String table : new String[] {"sync_cashflow_conversion_flow","sync_cashflow_conversion_leg",
                "sync_calculable_cashflow"}) {
            assertThat(jdbc().sql("SELECT count(*) FROM "+table+" WHERE source_event_id IN "
                    + "(SELECT source_event_id FROM sync_cashflow_conversion_input WHERE job_id=:j)")
                    .param("j",job).query(Integer.class).single()).as(table).isZero();
        }
    }

    @Test
    void conversionHttpUsesAcceptedConflictAndNoStoreWithoutUsdAmounts() throws Exception {
        String subject="conversion-http-"+UUID.randomUUID();
        var login=signIn(subject);
        String cookie=sessionCookieOf(login).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        Instant heldAt=closedTenMinuteBoundary().plusSeconds(4);
        UUID intent=classifiedIntent(user,"conversion-http",new Flow("http-usdt-out","USDT","-3.2571",
                closedTenMinuteBoundary().plusSeconds(2)),new Flow("http-halt-out","HALT","-4.125",heldAt));
        String path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/cashflow-conversion";
        String csrf=csrfTokenOf(cookie);
        String body="{\"intentId\":\""+intent+"\"}";

        var empty=get(path+"?intentId="+intent,cookie);
        assertThat(empty.statusCode()).isEqualTo(204);
        assertThat(empty.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(sendJson("POST",path,body,cookie,"Idempotency-Key","http-conversion-001").statusCode()).isEqualTo(403);
        var started=sendJson("POST",path,body,cookie,"Origin",OidcStubConfiguration.PUBLIC_BASE_URL,
                AuthSecurityConfig.CSRF_HEADER_NAME,csrf,"Idempotency-Key","http-conversion-001");
        assertThat(started.statusCode()).isEqualTo(202);
        assertThat(started.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        // An attempt without a result bundle reports its input count and no outcome counts.
        var accepted=topLevelFieldsOf(started.body());
        assertThat(accepted.get("flowCount")).isEqualTo(2);
        assertThat(accepted).doesNotContainKeys("convertedCount","estimatedCount","conversionRequiredCount","reviewCount");
        var repeated=sendJson("POST",path,body,cookie,"Origin",OidcStubConfiguration.PUBLIC_BASE_URL,
                AuthSecurityConfig.CSRF_HEADER_NAME,csrf,"Idempotency-Key","http-conversion-001");
        assertThat(topLevelFieldsOf(repeated.body()).get("jobId")).isEqualTo(accepted.get("jobId"));
        assertThat(awaitConversion(UUID.fromString((String)accepted.get("jobId")))).isEqualTo("CONVERSION_REQUIRED");
        var done=get(path+"?intentId="+intent,cookie);
        assertThat(done.statusCode()).isEqualTo(200);
        assertThat(done.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var fields=topLevelFieldsOf(done.body());
        assertThat(fields.get("status")).isEqualTo("CONVERSION_REQUIRED");
        assertThat(fields.get("reasonCode")).isEqualTo("PRICE_ROUTE_UNAVAILABLE");
        assertThat(fields.get("retryable")).isEqualTo(false);
        assertThat(fields.get("calculable")).isEqualTo(false);
        assertThat(fields.get("pricePolicyVersion")).isEqualTo("BYBIT-PRICE-1.0");
        assertThat(fields.get("convertedCount")).isEqualTo(1);
        assertThat(fields.get("conversionRequiredCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        var assets=(java.util.List<java.util.Map<String,Object>>)fields.get("pendingAssets");
        assertThat(assets).hasSize(1);
        assertThat(assets.getFirst()).containsEntry("assetCode","HALT").containsEntry("count",1);
        @SuppressWarnings("unchecked")
        var flows=(java.util.List<java.util.Map<String,Object>>)assets.getFirst().get("flows");
        assertThat(flows).singleElement().satisfies(flow -> {
            assertThat(flow).containsEntry("direction","WITHDRAWAL").containsEntry("quantity","4.125")
                    .containsEntry("status","CONVERSION_REQUIRED").containsEntry("reasonCode","PRICE_ROUTE_UNAVAILABLE")
                    .containsEntry("retryable",false);
            assertThat(flow.get("occurredAt")).isInstanceOf(String.class).isEqualTo(heldAt.toString());
        });
        // The converted USDT flow is not itemized and no USD value is sent at all.
        assertThat(done.body()).doesNotContain("usd").doesNotContain("3.2571");
        var unknown=sendJson("POST",path,"{\"intentId\":\""+UUID.randomUUID()+"\"}",cookie,
                "Origin",OidcStubConfiguration.PUBLIC_BASE_URL,AuthSecurityConfig.CSRF_HEADER_NAME,csrf,
                "Idempotency-Key","http-conversion-002");
        assertThat(unknown.statusCode()).isEqualTo(409);
        assertThat(get(path+"?intentId="+UUID.randomUUID(),cookie).statusCode()).isEqualTo(403);
    }

    /** Completes collection and classification with the given flows placed on chosen, closed minutes. */
    private UUID classifiedIntent(UUID user,String prefix,Flow... flows) throws Exception {
        UUID intent=initialIntent(user,prefix);
        UUID collection=sync.admitInitialInputs(user,intent,prefix+"-inputs-001").orElseThrow().jobId();
        assertThat(awaitInitial(user,collection)).isEqualTo("INPUTS_COMPLETE");
        var bundle=jdbc().sql("SELECT candidate_id,provider_account_id,connection_id,tc FROM sync_initial_candidate_bundle "
                + "WHERE job_id=:j").param("j",collection).query((rs,n)->new Object[] {rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,java.time.OffsetDateTime.class).toInstant()}).single();
        UUID candidate=(UUID)bundle[0];
        Instant earliest=java.util.Arrays.stream(flows).map(Flow::at).min(Instant::compareTo).orElse(Instant.now());
        Instant latest=java.util.Arrays.stream(flows).map(Flow::at).max(Instant::compareTo).orElse(Instant.now());
        Instant tc=((Instant)bundle[3]).isBefore(latest) ? latest.plusSeconds(1) : (Instant)bundle[3];
        // The candidate's scope moves so the flows fall inside (t0, tc]; the hash is recomputed below.
        jdbc().sql("UPDATE sync_initial_candidate_bundle SET t0=:t0,tc=:tc WHERE candidate_id=:c")
            .param("t0",java.time.OffsetDateTime.ofInstant(earliest.minusSeconds(3600),java.time.ZoneOffset.UTC))
            .param("tc",java.time.OffsetDateTime.ofInstant(tc,java.time.ZoneOffset.UTC)).param("c",candidate).update();
        for (Flow flow : flows) {
            insertCandidateEvent(user,(UUID)bundle[1],(UUID)bundle[2],collection,candidate,flow.sourceId(),
                    flow.cashFlow().startsWith("-") ? "TRANSFER_OUT" : "TRANSFER_IN",flow.currency(),flow.cashFlow(),
                    flow.at(),sha256Hex(user+"|"+flow.sourceId()));
        }
        refreshCandidateHash(candidate);
        UUID classification=sync.admitCashflowClassification(user,intent,prefix+"-classification-001").orElseThrow().jobId();
        assertThat(awaitClassification(classification)).isEqualTo("COMPLETE");
        return intent;
    }

    /** An intent whose classification finished with a review-required event. */
    private UUID reviewedIntent(UUID user,String prefix) throws Exception {
        UUID intent=initialIntent(user,prefix);
        UUID collection=sync.admitInitialInputs(user,intent,prefix+"-inputs-001").orElseThrow().jobId();
        assertThat(awaitInitial(user,collection)).isEqualTo("INPUTS_COMPLETE");
        var bundle=jdbc().sql("SELECT candidate_id,provider_account_id,connection_id,tc FROM sync_initial_candidate_bundle "
                + "WHERE job_id=:j").param("j",collection).query((rs,n)->new Object[] {rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,java.time.OffsetDateTime.class).toInstant()}).single();
        insertCandidateEvent(user,(UUID)bundle[1],(UUID)bundle[2],collection,(UUID)bundle[0],prefix+"-zero",
                "TRANSFER_IN","USDT","0",((Instant)bundle[3]).minusMillis(1),sha256Hex(prefix));
        refreshCandidateHash((UUID)bundle[0]);
        UUID classification=sync.admitCashflowClassification(user,intent,prefix+"-classification-001").orElseThrow().jobId();
        assertThat(awaitClassification(classification)).isEqualTo("NEEDS_REVIEW");
        return intent;
    }

    /** A ten-minute boundary at least an hour in the past: every minute near it is long closed. */
    private static Instant closedTenMinuteBoundary() {
        long tenMinutes=10*60_000L;
        return Instant.ofEpochMilli(Math.floorDiv(Instant.now().minusSeconds(3600).toEpochMilli(),tenMinutes)*tenMinutes);
    }

    /** Shared evidence outlives users; request-count assertions need a known starting point. */
    private void clearMarketEvidence() {
        jdbc().sql("TRUNCATE TABLE sync_market_candle, sync_market_instrument_observation CASCADE").update();
    }

    private String calculableUsd(UUID conversion,String sourceId) {
        return jdbc().sql("SELECT c.usd_value FROM sync_calculable_cashflow c WHERE c.conversion_id=:c AND c.source_id=:s")
            .param("c",conversion).param("s",sourceId).query(String.class).single();
    }

    private UUID conversionIdOf(UUID job) {
        return jdbc().sql("SELECT conversion_id FROM sync_cashflow_conversion_job WHERE job_id=:j").param("j",job)
            .query(UUID.class).single();
    }

    private String awaitConversion(UUID job) throws InterruptedException {
        String status=pollUntil(Duration.ofSeconds(15),()->jdbc().sql("SELECT status FROM sync_cashflow_conversion_job "
                + "WHERE job_id=:j").param("j",job).query(String.class).single(),FirstSnapshotTest::finished);
        if (!finished(status)) throw new AssertionError("cash-flow conversion did not finish");
        return status;
    }

    private void awaitKlineCall(String symbol,int calls) throws InterruptedException {
        for (int i=0;i<150;i++) {
            if (BybitStubConfiguration.requestCount(DevBybitStub.KLINE_PATH,symbol)>=calls) return;
            Thread.sleep(20);
        }
        throw new AssertionError("conversion did not reach the kline call");
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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
        return initialIntent(user,prefix,"0.1");
    }

    private UUID initialIntent(UUID user,String prefix,String rate) throws Exception {
        UUID firstJob=sync.admit(user,prefix+"-seed").orElseThrow().jobId();
        assertThat(await(user,firstJob)).isEqualTo("SUCCEEDED");
        return confirmIntent(user,prefix,rate);
    }

    /** Previews and confirms daily conditions ending 30 days after the first official snapshot. */
    private UUID confirmIntent(UUID user,String prefix,String rate) {
        var snapshot=plans.state(user).snapshot();
        String end=LocalDateTime.ofInstant(snapshot.measuredAt(),ZoneId.of("Asia/Seoul"))
                .plusDays(30).withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var preview=plans.preview(user,prefix+"-session",new PlanConditionApi.PreviewInput(end,"DAILY",rate))
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
        Thread.sleep(QUIET_WINDOW);
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
        // The next claim ends the attempt as BLOCKED (or, had a claim come first, its publication does).
        String ended=pollUntil(Duration.ofSeconds(10),()->jdbc().sql("SELECT status FROM sync_job WHERE job_id=:j")
                .param("j",job).query(String.class).single(),FirstSnapshotTest::finished);
        if (!finished(ended)) throw new AssertionError("the restricted attempt was never claimed");
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
        Thread.sleep(QUIET_WINDOW);
        assertThat(sync.admit(user,"after-erase")).isEmpty();
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_job WHERE user_id=:u")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(source.snapshotConnection(other)).isPresent();
        assertThat(sync.job(user,job)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Story 2.7 first plan calculation and atomic publication
    // ------------------------------------------------------------------------------------------

    @Test
    void theFirstPublicationCommitsPlanSnapshotCalculationAndBothPointersTogether() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-official","0.1",null);
        // A preview reviewed before the publication; confirming it afterwards must be refused.
        String end=LocalDateTime.ofInstant(plans.state(user).snapshot().measuredAt(),ZoneId.of("Asia/Seoul"))
                .plusDays(20).withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var lateReview=plans.preview(user,"publish-official-session",
                new PlanConditionApi.PreviewInput(end,"WEEKLY","0.2")).orElseThrow();
        UUID firstSnapshot=sync.state(user).orElseThrow().officialValue().snapshotId();

        var accepted=sync.admitPlanPublication(user,intent,"publish-official-command-001");
        assertThat(accepted).isInstanceOf(SyncJobApi.PublicationAdmission.Accepted.class);
        UUID job=((SyncJobApi.PublicationAdmission.Accepted)accepted).job().jobId();
        var replay=sync.admitPlanPublication(user,intent,"publish-official-command-001");
        assertThat(((SyncJobApi.PublicationAdmission.Accepted)replay).job().jobId()).as("same key, same intent")
                .isEqualTo(job);
        assertThat(awaitPublication(job)).isEqualTo("PUBLISHED");

        UUID plan=jdbc().sql("SELECT plan_id FROM plan_definition WHERE user_id=:u AND status='ACTIVE'")
                .param("u",user).query(UUID.class).single();
        assertThat(jdbc().sql("SELECT status||'|'||consumed_by_plan_id::text||'|'||(consumed_at IS NOT NULL) "
                + "FROM plan_condition_intent WHERE intent_id=:i").param("i",intent).query(String.class).single())
                .isEqualTo("CONSUMED|"+plan+"|true");
        var candidate=jdbc().sql("SELECT candidate_id,tc,measured_at,equity_decimal FROM sync_initial_candidate_bundle "
                + "WHERE user_id=:u").param("u",user).query((rs,n)->new Object[] {rs.getObject(1,UUID.class),
                rs.getObject(2,java.time.OffsetDateTime.class).toInstant(),
                rs.getObject(3,java.time.OffsetDateTime.class).toInstant(),rs.getString(4)}).single();
        UUID source=jdbc().sql("SELECT source_snapshot_id FROM sync_source_snapshot WHERE initial_candidate_id=:c "
                + "AND job_id IS NULL").param("c",candidate[0]).query(UUID.class).single();
        // The portfolio pointer advanced by exactly one to the new tc snapshot built from that source.
        assertThat(jdbc().sql("SELECT c.publication_sequence||'|'||s.amount_decimal||'|'||comp.source_snapshot_id::text "
                + "FROM portfolio_current_snapshot c JOIN portfolio_snapshot s ON s.portfolio_snapshot_id=c.portfolio_snapshot_id "
                + "JOIN portfolio_snapshot_component comp ON comp.portfolio_snapshot_id=s.portfolio_snapshot_id "
                + "WHERE s.user_id=:u").param("u",user).query(String.class).single())
                .isEqualTo("2|"+candidate[3]+"|"+source);
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isEqualTo(2);
        var official=sync.state(user).orElseThrow().officialValue();
        assertThat(official.publicationSequence()).as("GET /api/snapshots/first follows the pointer").isEqualTo(2);
        assertThat(official.measuredAt()).isEqualTo(candidate[2]);

        var calc=jdbc().sql("SELECT calculation_id,plan_id,basis_at,cashflow_verified_through,current_value_measured_at,"
                + "basis_portfolio_snapshot_id,portfolio_snapshot_id,source_snapshot_id,estimated,input_reliability,"
                + "calculation_policy||'|'||calendar_policy||'|'||display_policy||'|'||precision_policy||'|'||time_zone,"
                + "implementation_version,flow_count FROM sync_plan_calculation WHERE user_id=:u").param("u",user)
                .query((rs,n)->new Object[] {rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getObject(3,java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject(4,java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject(5,java.time.OffsetDateTime.class).toInstant(),rs.getObject(6,UUID.class),
                        rs.getObject(7,UUID.class),rs.getObject(8,UUID.class),rs.getBoolean(9),rs.getString(10),
                        rs.getString(11),rs.getString(12),rs.getInt(13)}).single();
        assertThat(calc[1]).isEqualTo(plan);
        assertThat(calc[2]).as("tc").isEqualTo(candidate[1]);
        assertThat(calc[3]).as("cashflow_verified_through = tc").isEqualTo(candidate[1]);
        assertThat(calc[4]).as("E(tc) measured_at kept separately").isEqualTo(candidate[2]);
        assertThat(calc[5]).as("basis snapshot is the first official snapshot").isEqualTo(firstSnapshot);
        assertThat(calc[6]).isEqualTo(official.snapshotId());
        assertThat(calc[7]).isEqualTo(source);
        assertThat(calc[8]).isEqualTo(false);
        assertThat(calc[9]).isEqualTo("CONFIRMED");
        assertThat(calc[10]).isEqualTo("CALC-1.0|CALENDAR-SEOUL-1.0|DISPLAY-1.0|DECIMAL128-HALF_EVEN|Asia/Seoul");
        assertThat(calc[11]).isEqualTo(PlanCalculator.IMPLEMENTATION_VERSION);
        assertThat(calc[12]).isEqualTo(0);
        assertThat(jdbc().sql("SELECT metric||'|'||nature||'|'||(value_decimal IS NOT NULL) FROM sync_plan_calculation_metric "
                + "WHERE calculation_id=:c ORDER BY metric").param("c",calc[0]).query(String.class).list())
                .containsExactly("ADJUSTED_TARGET|OFFICIAL|true","CURRENT_VALUE|OFFICIAL|true","GOAL_PROGRESS|OFFICIAL|true",
                        "PLAN_AMOUNT|OFFICIAL|true","PLAN_DIFFERENCE|OFFICIAL|true","REQUIRED_RATE|OFFICIAL|true");
        assertThat(jdbc().sql("SELECT pointer_version||'|'||calculation_id::text FROM sync_official_plan_result "
                + "WHERE plan_id=:p").param("p",plan).query(String.class).single()).isEqualTo("1|"+calc[0]);
        assertReproducible((UUID)calc[0]);

        // The official result through the pointer, and the active plan everywhere else.
        var found=ready(user);
        assertThat(found.planId()).isEqualTo(plan);
        assertThat(found.calculationId()).isEqualTo(calc[0]);
        assertThat(found.trust().freshness().state()).isEqualTo("FRESH");
        assertThat(found.metrics()).extracting(SyncJobApi.DashboardMetric::metric).containsExactly("CURRENT_VALUE",
                "PLAN_AMOUNT","PLAN_DIFFERENCE","ADJUSTED_TARGET","REQUIRED_RATE","GOAL_PROGRESS");
        assertThat(found.metrics().getFirst().value()).isEqualTo(candidate[3]);
        var state=plans.state(user);
        assertThat(state.eligible()).isFalse();
        assertThat(state.reason()).isEqualTo(PlanConditionApi.ACTIVE_PLAN_EXISTS);
        assertThat(state.activePlan().planId()).isEqualTo(plan);
        assertThat(plans.preview(user,"publish-official-session",new PlanConditionApi.PreviewInput(end,"DAILY","0.1")))
                .isEmpty();
        var confirmed=plans.confirm(user,"publish-official-session",lateReview.confirmationRef(),
                "publish-official-confirm-late");
        assertThat(confirmed.accepted()).isFalse();
        assertThat(confirmed.reason()).isEqualTo(PlanConditionApi.ACTIVE_PLAN_EXISTS);
        assertThat(sync.admitPlanPublication(user,intent,"publish-official-command-002"))
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("ACTIVE_PLAN_EXISTS"));
        assertThat(sync.admitPlanPublication(user,UUID.randomUUID(),"publish-official-command-001"))
                .as("same key, another intent").isEqualTo(new SyncJobApi.PublicationAdmission.Refused("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc().sql("SELECT count(*) FROM plan_definition WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT count(*) FROM plan_condition_intent WHERE user_id=:u AND status='OPEN'")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT to_regclass('active_plan') IS NULL").query(Boolean.class).single()).isTrue();
    }

    @Test
    void anEstimatedFlowMakesEveryDependentMetricEstimatedAndLinksTheAppliedHistory() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-estimated","0.1",base.minusSeconds(3600),
                new Flow("est-usdt-in","USDT","100",base.plusSeconds(5)),new Flow("est-btc-in","BTC","0.01",base.plusSeconds(9)));
        UUID job=publish(user,intent,"publish-estimated-command-001");
        assertThat(awaitPublication(job)).isEqualTo("PUBLISHED");

        UUID calc=jdbc().sql("SELECT calculation_id FROM sync_plan_calculation WHERE user_id=:u").param("u",user)
                .query(UUID.class).single();
        assertThat(jdbc().sql("SELECT metric||'|'||nature FROM sync_plan_calculation_metric WHERE calculation_id=:c "
                + "ORDER BY metric").param("c",calc).query(String.class).list()).containsExactly("ADJUSTED_TARGET|ESTIMATED",
                        "CURRENT_VALUE|OFFICIAL","GOAL_PROGRESS|ESTIMATED","PLAN_AMOUNT|ESTIMATED",
                        "PLAN_DIFFERENCE|ESTIMATED","REQUIRED_RATE|ESTIMATED");
        assertThat(jdbc().sql("SELECT estimated||'|'||flow_count||'|'||estimated_flow_count||'|'||price_policy_version "
                + "FROM sync_plan_calculation WHERE calculation_id=:c").param("c",calc).query(String.class).single())
                .isEqualTo("true|2|1|BYBIT-PRICE-1.0");
        assertThat(jdbc().sql("SELECT f.sequence||'|'||f.source_id||'|'||f.usd_value||'|'||f.estimated||'|'||f.evidence_type "
                + "FROM sync_plan_calculation_flow f JOIN sync_calculable_cashflow c ON c.conversion_id=f.conversion_id "
                + "AND c.source_event_id=f.source_event_id WHERE f.calculation_id=:c ORDER BY f.sequence")
                .param("c",calc).query(String.class).list())
                .containsExactly("1|est-usdt-in|100|false|DIRECT_NORMALIZATION","2|est-btc-in|650.0025|true|SPOT_KLINE_CLOSE");
        assertReproducible(calc);
        var found=ready(user);
        assertThat(found.trust().calculationNature().character()).isEqualTo("ESTIMATED");
        assertThat(found.trust().calculationNature().axis().state()).isEqualTo("ESTIMATED");
        assertThat(found.trust().calculationNature().axis().cause()).isEqualTo("ESTIMATED_CASHFLOW_PRICE");
        assertThat(found.trust().calculationNature().axis().affectedMetrics()).as("every derived metric")
                .containsExactly("PLAN_AMOUNT","PLAN_DIFFERENCE","ADJUSTED_TARGET","REQUIRED_RATE","GOAL_PROGRESS");
        assertThat(found.trust().calculationNature().estimatedFlowCount()).isEqualTo(1);
        assertThat(found.estimatedFlowCount()).isEqualTo(1);

        // Story 2.9 ESTIMATED: the cause is derived from the applied flows — the current value is Bybit's own
        // measurement and nothing propagates to it; every derived metric names the estimated price evidence
        // kind and the count, never an individual event (that is Story 2.10's).
        var evidence=evidenceOf(user);
        assertThat(evidence.cashflows()).isEqualTo(new SyncJobApi.CalculationEvidence.Cashflows(2,1));
        assertThat(evidence.conversion().pricePolicy()).isEqualTo("BYBIT-PRICE-1.0");
        assertThat(evidence.metrics().getFirst())
                .isEqualTo(new SyncJobApi.CalculationEvidence.MetricImpact("CURRENT_VALUE","OFFICIAL",null,null));
        assertThat(evidence.metrics().subList(1,6)).extracting(SyncJobApi.CalculationEvidence.MetricImpact::metric)
                .containsExactly("PLAN_AMOUNT","PLAN_DIFFERENCE","ADJUSTED_TARGET","REQUIRED_RATE","GOAL_PROGRESS");
        assertThat(evidence.metrics().subList(1,6)).allSatisfy(metric -> {
            assertThat(metric.nature()).isEqualTo("ESTIMATED");
            assertThat(metric.unavailableReason()).isNull();
            assertThat(metric.estimatedCause())
                    .isEqualTo(new SyncJobApi.CalculationEvidence.EstimatedCause("SPOT_KLINE_CLOSE",1));
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"CURRENT_VALUE_NOT_POSITIVE,REQUIRED_RATE,CONFIRMED", "TARGET_NOT_POSITIVE,REQUIRED_RATE,REVIEW_REQUIRED",
        "GOAL_PROFIT_NOT_POSITIVE,GOAL_PROGRESS,CONFIRMED"})
    void undefinedRatiosArePublishedWithTheirReasonAndNeverAsZero(String reason, String metric, String reliability)
            throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        String subject="publication-undefined-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        String prefix="publish-"+reason.toLowerCase(java.util.Locale.ROOT).replace('_','-');
        UUID intent=switch (reason) {
            case "CURRENT_VALUE_NOT_POSITIVE" -> convertedIntent(user,prefix,"0.1",null,"0");
            case "TARGET_NOT_POSITIVE" -> convertedIntent(user,prefix,"0.1",base.minusSeconds(3600),
                    new Flow(prefix+"-out","USDT","-5000",base.plusSeconds(7)));
            default -> convertedIntent(user,prefix,"0",null);
        };
        UUID job=publish(user,intent,prefix+"-command-001");
        assertThat(awaitPublication(job)).isEqualTo("PUBLISHED");
        UUID calc=jdbc().sql("SELECT calculation_id FROM sync_plan_calculation WHERE user_id=:u").param("u",user)
                .query(UUID.class).single();
        assertThat(jdbc().sql("SELECT (value_decimal IS NULL)||'|'||unavailable_reason FROM sync_plan_calculation_metric "
                + "WHERE calculation_id=:c AND metric=:m").param("c",calc).param("m",metric).query(String.class).single())
                .isEqualTo("true|"+reason);
        if (reason.equals("TARGET_NOT_POSITIVE")) {
            assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_metric WHERE calculation_id=:c "
                    + "AND unavailable_reason='TARGET_NOT_POSITIVE'").param("c",calc).query(Integer.class).single())
                    .as("both target-based ratios").isEqualTo(2);
        }
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_metric WHERE calculation_id=:c "
                + "AND metric IN ('CURRENT_VALUE','PLAN_AMOUNT','PLAN_DIFFERENCE','ADJUSTED_TARGET') AND value_decimal IS NOT NULL")
                .param("c",calc).query(Integer.class).single()).as("amounts are kept").isEqualTo(4);
        assertThat(jdbc().sql("SELECT input_reliability FROM sync_plan_calculation WHERE calculation_id=:c").param("c",calc)
                .query(String.class).single()).isEqualTo(reliability);

        // Through HTTP: "not computable" is its own state and never overwrites the character; an undefined
        // metric carries no value key at all (never a zero or a null to be mistaken for one) and its reason.
        var fields=resultOf(get(AuthSecurityConfig.DASHBOARD_PATH,cookie).body());
        @SuppressWarnings("unchecked")
        var trust=(java.util.Map<String,java.util.Map<String,Object>>)fields.get("trust");
        var expectedUnavailable=reason.equals("TARGET_NOT_POSITIVE") ? java.util.List.of("REQUIRED_RATE","GOAL_PROGRESS")
                : java.util.List.of(metric);
        assertThat(trust.get("calculationNature")).containsEntry("state","NOT_COMPUTABLE")
                .containsEntry("character","OFFICIAL").containsEntry("unavailableMetrics",expectedUnavailable);
        assertThat(trust.get("inputReliability")).containsEntry("state",reliability);
        // Story 2.8 AC-5: a review reaches the target and the ratios it left undefined; the nature axis
        // reaches the undefined metrics. Each axis names its own cause.
        if (reason.equals("TARGET_NOT_POSITIVE")) {
            assertThat(trust.get("inputReliability")).containsEntry("cause","TARGET_NOT_POSITIVE")
                    .containsEntry("affectedMetrics",java.util.List.of("ADJUSTED_TARGET","REQUIRED_RATE","GOAL_PROGRESS"))
                    .containsEntry("nextAction","NONE");
        } else {
            assertThat(trust.get("inputReliability")).containsEntry("cause","INPUTS_VERIFIED")
                    .containsEntry("affectedMetrics",java.util.List.of());
        }
        assertThat(trust.get("calculationNature")).containsEntry("cause","METRIC_UNDEFINED")
                .containsEntry("affectedMetrics",expectedUnavailable);
        @SuppressWarnings("unchecked")
        var metrics=(java.util.List<java.util.Map<String,Object>>)fields.get("metrics");
        for (var row : metrics) {
            if (expectedUnavailable.contains(row.get("metric"))) {
                assertThat(row).as((String)row.get("metric")).doesNotContainKey("value")
                        .containsEntry("unavailableReason",reason.equals("TARGET_NOT_POSITIVE") ? "TARGET_NOT_POSITIVE"
                                : reason).containsEntry("nature","OFFICIAL");
            } else {
                assertThat(row).as((String)row.get("metric")).containsKey("value").doesNotContainKey("unavailableReason");
            }
        }

        // Story 2.9 NOT_COMPUTABLE: the evidence links each undefined metric to its reason code, keeps its
        // character, carries no value at all and invents no estimated cause (no flow here was estimated).
        var evidence=send("GET",DashboardController.EVIDENCE_PATH+query(fields),cookie);
        assertThat(evidence.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var impacts=(java.util.List<java.util.Map<String,Object>>)topLevelFieldsOf(evidence.body()).get("metrics");
        assertThat(impacts).extracting(m -> m.get("metric")).containsExactly("CURRENT_VALUE","PLAN_AMOUNT",
                "PLAN_DIFFERENCE","ADJUSTED_TARGET","REQUIRED_RATE","GOAL_PROGRESS");
        for (var row : impacts) {
            assertThat(row).as((String)row.get("metric")).doesNotContainKeys("value","estimatedCause")
                    .containsEntry("nature","OFFICIAL");
            if (expectedUnavailable.contains(row.get("metric"))) {
                assertThat(row).as((String)row.get("metric")).containsEntry("unavailableReason",reason);
            } else {
                assertThat(row).as((String)row.get("metric")).doesNotContainKey("unavailableReason");
            }
        }
    }

    @Test
    void anOutOfRangeCalculationPublishesNothing() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-range","0.1",null,"1000000000000000000000000");
        UUID job=publish(user,intent,"publish-range-command-001");
        assertThat(awaitPublication(job)).isEqualTo("FAILED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_publication_job WHERE job_id=:j").param("j",job)
                .query(String.class).single()).isEqualTo("CALCULATION_RANGE_EXCEEDED");
        assertNothingPublished(user,intent);
        // The pure calculation fails the same way on the same inputs: no new attempt is offered or taken.
        var state=sync.planPublicationState(user,intent);
        assertThat(state.startable()).isFalse();
        assertThat(state.reason()).isEqualTo("CALCULATION_RANGE_EXCEEDED");
        assertThat(state.job().jobId()).isEqualTo(job);
        assertThat(sync.admitPlanPublication(user,intent,"publish-range-command-002"))
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("CALCULATION_RANGE_EXCEEDED"));
        // Story 2.8: a finished attempt is not "being prepared" — the dashboard has no plan to show.
        assertThat(dashboardOf(user)).isEqualTo(new SyncJobApi.Dashboard.NoPlan("CREATE_PLAN"));
    }

    @Test
    void ineligibleLineagesAndIntentsAreRefusedWithoutAnAttempt() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID unconverted=connect(DevBybitStub.Scenario.UTA2);
        UUID unconvertedIntent=initialIntent(unconverted,"refuse-unconverted");
        UUID collection=sync.admitInitialInputs(unconverted,unconvertedIntent,"refuse-unconverted-inputs-001")
                .orElseThrow().jobId();
        assertThat(awaitInitial(unconverted,collection)).isEqualTo("INPUTS_COMPLETE");
        assertThat(sync.admitPlanPublication(unconverted,unconvertedIntent,"refuse-unconverted-command-001"))
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("INPUTS_NOT_READY"));
        UUID reviewed=connect(DevBybitStub.Scenario.UTA2);
        UUID reviewedIntent=reviewedIntent(reviewed,"refuse-reviewed");
        assertThat(sync.admitPlanPublication(reviewed,reviewedIntent,"refuse-reviewed-command-001"))
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("INPUTS_NOT_READY"));
        UUID held=connect(DevBybitStub.Scenario.UTA2);
        UUID heldIntent=initialIntent(held,"refuse-held");
        shiftStart(held,heldIntent,base.minusSeconds(3600));
        UUID heldCollection=sync.admitInitialInputs(held,heldIntent,"refuse-held-inputs-001").orElseThrow().jobId();
        assertThat(awaitInitial(held,heldCollection)).isEqualTo("INPUTS_COMPLETE");
        injectFlows(held,heldCollection,new Flow("refuse-held-halt","HALT","1",base.plusSeconds(3)));
        UUID heldClassification=sync.admitCashflowClassification(held,heldIntent,"refuse-held-classification-001")
                .orElseThrow().jobId();
        assertThat(awaitClassification(heldClassification)).isEqualTo("COMPLETE");
        UUID heldConversion=sync.admitCashflowConversion(held,heldIntent,"refuse-held-conversion-001").orElseThrow().jobId();
        assertThat(awaitConversion(heldConversion)).isEqualTo("CONVERSION_REQUIRED");
        assertThat(sync.admitPlanPublication(held,heldIntent,"refuse-held-command-001"))
                .as("a conversion-required bundle is not calculable")
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("INPUTS_NOT_READY"));

        UUID owner=connect(DevBybitStub.Scenario.UTA2);
        UUID ownerIntent=convertedIntent(owner,"refuse-owner","0.1",null);
        assertThat(sync.admitPlanPublication(held,ownerIntent,"refuse-foreign-command-001")).as("another user's intent")
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("PLAN_CONDITIONS_REQUIRED"));
        // Another user's intent is readable as "not startable" — never that user's attempt, never a 403.
        assertThat(sync.planPublicationState(held,ownerIntent))
                .isEqualTo(new SyncJobApi.PublicationState(true,null,false,"PLAN_CONDITIONS_REQUIRED"));
        assertThat(sync.planPublicationState(owner,ownerIntent))
                .isEqualTo(new SyncJobApi.PublicationState(true,null,true,null));
        assertThat(sync.admitPlanPublication(owner,ownerIntent,"short")).isEqualTo(
                new SyncJobApi.PublicationAdmission.Refused("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(sync.admitPlanPublication(owner,ownerIntent,null)).as("no key is invented for the caller")
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("IDEMPOTENCY_KEY_REQUIRED"));
        jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) VALUES (:id,'plan-publication')")
                .param("id",UUID.randomUUID()).update();
        assertThat(sync.admitPlanPublication(owner,ownerIntent,"refuse-restricted-command-001"))
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("NOT_PERMITTED"));
        assertThat(sync.planPublicationState(owner,ownerIntent))
                .isEqualTo(new SyncJobApi.PublicationState(true,null,false,"NOT_PERMITTED"));
        jdbc().sql("TRUNCATE TABLE operations_restriction").update();
        // The plan period ended (T <= the database clock): its own code, not "conditions required".
        jdbc().sql("UPDATE plan_condition_intent SET end_at=now()-interval '1 second' WHERE intent_id=:i")
                .param("i",ownerIntent).update();
        assertThat(sync.admitPlanPublication(owner,ownerIntent,"refuse-ended-command-001"))
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("PLAN_PERIOD_ENDED"));
        assertThat(sync.planPublicationState(owner,ownerIntent))
                .isEqualTo(new SyncJobApi.PublicationState(true,null,false,"PLAN_PERIOD_ENDED"));
        // t0 >= tc: a candidate whose cut-off does not come after the plan start is not an input.
        UUID ordered=connect(DevBybitStub.Scenario.UTA2);
        UUID orderedIntent=convertedIntent(ordered,"refuse-ordered","0.1",null);
        UUID orderedCandidate=jdbc().sql("SELECT candidate_id FROM sync_initial_candidate_bundle WHERE user_id=:u")
                .param("u",ordered).query(UUID.class).single();
        jdbc().sql("UPDATE sync_initial_candidate_bundle SET tc=t0 WHERE candidate_id=:c").param("c",orderedCandidate)
                .update();
        refreshCandidateHash(orderedCandidate);
        assertThat(sync.admitPlanPublication(ordered,orderedIntent,"refuse-ordered-command-001"))
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("INPUTS_NOT_READY"));
        UUID deleting=connect(DevBybitStub.Scenario.UTA2);
        UUID deletingIntent=convertedIntent(deleting,"refuse-deleting","0.1",null);
        accountDeletion().beginDeletion(deleting);
        assertThat(sync.admitPlanPublication(deleting,deletingIntent,"refuse-deleting-command-001"))
                .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("NOT_PERMITTED"));
        assertThat(sync.planPublicationState(deleting,deletingIntent).permitted()).as("deletion in progress").isFalse();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_publication_job").query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM plan_definition").query(Integer.class).single()).isZero();
    }

    @Test
    void twoTabsConvergeOnOneAttemptAndTheIndexKeepsOneActivePlanPerPortfolio() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-race","0.1",null);
        var pool=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        UUID job;
        AutoCloseable lock=holdPlanLock(user);
        try {
            var first=pool.submit(()->{ ready.countDown(); start.await();
                return sync.admitPlanPublication(user,intent,"publish-race-tab-first-001"); });
            var second=pool.submit(()->{ ready.countDown(); start.await();
                return sync.admitPlanPublication(user,intent,"publish-race-tab-second-001"); });
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var a=(SyncJobApi.PublicationAdmission.Accepted)first.get(10,TimeUnit.SECONDS);
            var b=(SyncJobApi.PublicationAdmission.Accepted)second.get(10,TimeUnit.SECONDS);
            assertThat(b.job().jobId()).isEqualTo(a.job().jobId());
            job=a.job().jobId();
        } finally {
            lock.close();
            pool.shutdownNow();
        }
        assertThat(awaitPublication(job)).isEqualTo("PUBLISHED");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_publication_job WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isEqualTo(1);
        // The conditional unique index, not the application's check, is the last word.
        var plan=jdbc().sql("SELECT portfolio_id,intent_id FROM plan_definition WHERE user_id=:u").param("u",user)
                .query((rs,n)->new UUID[] {rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)}).single();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc().sql("INSERT INTO plan_definition(plan_id,"
                + "user_id,portfolio_id,intent_id,basis_portfolio_snapshot_id,membership_id,connection_id,provider_account_id,"
                + "basis_source_snapshot_id,p0_decimal,t0,end_at,period,target_rate,source_policy_version,"
                + "portfolio_policy_version,calendar_policy,calculation_policy,status) SELECT gen_random_uuid(),user_id,"
                + "portfolio_id,:other,basis_portfolio_snapshot_id,membership_id,connection_id,provider_account_id,"
                + "basis_source_snapshot_id,p0_decimal,t0,end_at,period,target_rate,source_policy_version,"
                + "portfolio_policy_version,calendar_policy,calculation_policy,'ACTIVE' FROM plan_definition "
                + "WHERE portfolio_id=:p").param("other",plan[1]).param("p",plan[0]).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc().sql("SELECT count(*) FROM plan_definition WHERE portfolio_id=:p AND status='ACTIVE'")
                .param("p",plan[0]).query(Integer.class).single()).isEqualTo(1);
    }

    /** A failure injected at any write of the publishing transaction leaves no new row and moves no pointer. */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"plan_definition,INSERT","plan_condition_intent,UPDATE","sync_source_snapshot,INSERT",
        "portfolio_snapshot,INSERT","portfolio_snapshot_component,INSERT","portfolio_current_snapshot,UPDATE",
        "sync_plan_calculation,INSERT","sync_plan_calculation_metric,INSERT","sync_plan_calculation_flow,INSERT",
        "sync_official_plan_result,INSERT"})
    void aFailureAtAnyPublicationStepRollsEverythingBack(String table, String event) throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        String prefix="publish-fail-"+table.replace('_','-');
        UUID intent=convertedIntent(user,prefix,"0.1",base.minusSeconds(3600),
                new Flow(prefix+"-in","USDT","100",base.plusSeconds(11)));
        // The trigger fires only for this test user's rows: the tables are shared with every other test and
        // with the workers of other cached contexts, whose writes must not fail because of this test.
        jdbc().sql("CREATE FUNCTION fail_publication_step() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF "+ownedBy(table,user)+" THEN RAISE EXCEPTION 'injected publication failure detail'; END IF; "
                + "RETURN NEW; END $$").update();
        jdbc().sql("CREATE TRIGGER fail_publication_step BEFORE "+event+" ON "+table
                + " FOR EACH ROW EXECUTE FUNCTION fail_publication_step()").update();
        UUID job;
        try {
            job=publish(user,intent,prefix+"-command-001");
            assertThat(awaitPublication(job)).isEqualTo("FAILED");
        } finally {
            jdbc().sql("DROP TRIGGER fail_publication_step ON "+table).update();
            jdbc().sql("DROP FUNCTION fail_publication_step()").update();
        }
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_publication_job WHERE job_id=:j").param("j",job)
                .query(String.class).single()).isEqualTo("PUBLICATION_INTERRUPTED");
        var interrupted=sync.planPublicationState(user,intent);
        assertThat(interrupted.job().retryable()).isTrue();
        assertThat(interrupted.startable()).as("an interruption is offered a new attempt").isTrue();
        assertNothingPublished(user,intent);
        // Nothing was consumed, so a new attempt publishes once the failure is gone.
        UUID retry=publish(user,intent,prefix+"-command-002");
        assertThat(awaitPublication(retry)).isEqualTo("PUBLISHED");
    }

    /** The trigger condition that selects this user's row of {@code table} (a UUID literal, never user input). */
    private static String ownedBy(String table,UUID user) {
        String id="'"+user+"'::uuid";
        return switch (table) {
            case "portfolio_current_snapshot" ->
                    "NEW.portfolio_id IN (SELECT portfolio_id FROM portfolio WHERE user_id="+id+")";
            case "sync_plan_calculation_metric","sync_plan_calculation_flow" ->
                    "EXISTS(SELECT 1 FROM sync_plan_calculation c WHERE c.calculation_id=NEW.calculation_id "
                            + "AND c.user_id="+id+")";
            default -> "NEW.user_id="+id;
        };
    }

    /** A worker that lost its fence while waiting to publish writes nothing, not even an ending. */
    @Test
    void aStaleFencedWorkerCannotPublish() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-stale","0.1",null);
        UUID job;
        long claimed;
        AutoCloseable lock=holdPlanLock(user);
        try {
            job=publish(user,intent,"publish-stale-command-001");
            awaitBlockedOnPlanLock();
            claimed=jdbc().sql("SELECT fencing_generation FROM sync_plan_publication_job WHERE job_id=:j").param("j",job)
                    .query(Long.class).single();
            jdbc().sql("UPDATE sync_plan_publication_job SET fencing_generation=fencing_generation+1 WHERE job_id=:j")
                    .param("j",job).update();
        } finally {
            lock.close();
        }
        awaitPlanLockReleased();
        Thread.sleep(QUIET_WINDOW);
        assertThat(jdbc().sql("SELECT status||'|'||fencing_generation||'|'||(finished_at IS NULL) FROM "
                + "sync_plan_publication_job WHERE job_id=:j").param("j",job).query(String.class).single())
                .isEqualTo("RUNNING|"+(claimed+1)+"|true");
        assertNothingPublished(user,intent);
    }

    /**
     * Deletion, restriction or a membership change while the worker waits wins over the late publication,
     * and each ends with its own closed reason.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"DELETION,,", "RESTRICTION,BLOCKED,NOT_PERMITTED", "MEMBERSHIP_ENDED,BLOCKED,MEMBERSHIP_CHANGED",
        "INTENT_REPLACED,BLOCKED,PLAN_CONDITIONS_CHANGED"})
    void aChangeDuringThePublicationRollsItBack(String change, String status, String code) throws Exception {
        UUID kept=connect(DevBybitStub.Scenario.UTA2);
        UUID keptIntent=convertedIntent(kept,"publish-kept-"+change.toLowerCase(java.util.Locale.ROOT),"0.1",null);
        assertThat(awaitPublication(publish(kept,keptIntent,"publish-kept-command-"+change.toLowerCase(java.util.Locale.ROOT))))
                .isEqualTo("PUBLISHED");
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        String prefix="publish-change-"+change.toLowerCase(java.util.Locale.ROOT).replace('_','-');
        UUID intent=convertedIntent(user,prefix,"0.1",null);
        UUID job;
        AutoCloseable lock=holdPlanLock(user);
        try {
            job=publish(user,intent,prefix+"-command-001");
            awaitBlockedOnPlanLock();
            switch (change) {
                case "DELETION" -> {
                    accountDeletion().beginDeletion(user);
                    portfolio.erasePortfolioData(user);
                    sync.eraseSyncData(user);
                    plans.erasePlanData(user);
                    source.eraseSourceData(user);
                }
                case "RESTRICTION" -> jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) "
                        + "VALUES (:id,'plan-publication')").param("id",UUID.randomUUID()).update();
                case "MEMBERSHIP_ENDED" -> jdbc().sql("UPDATE portfolio_source_membership SET ended_at=now() "
                        + "WHERE portfolio_id IN (SELECT portfolio_id FROM portfolio WHERE user_id=:u)")
                        .param("u",user).update();
                default -> jdbc().sql("UPDATE plan_condition_intent SET status='REPLACED' WHERE intent_id=:i")
                        .param("i",intent).update();
            }
        } finally {
            lock.close();
        }
        if (change.equals("DELETION")) {
            awaitPlanLockReleased();
            Thread.sleep(QUIET_WINDOW);
            for (String table : new String[] {"plan_definition","plan_condition_intent","portfolio_snapshot",
                    "sync_plan_calculation","sync_official_plan_result","sync_plan_publication_job","sync_source_snapshot",
                    "sync_calculable_cashflow","sync_initial_candidate_bundle"}) {
                assertThat(jdbc().sql("SELECT count(*) FROM "+table+" WHERE user_id=:u").param("u",user)
                        .query(Integer.class).single()).as(table).isZero();
            }
            assertThat(sync.admitPlanPublication(user,intent,prefix+"-command-002"))
                    .isEqualTo(new SyncJobApi.PublicationAdmission.Refused("NOT_PERMITTED"));
        } else {
            assertThat(awaitPublication(job)).isEqualTo(status);
            assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_publication_job WHERE job_id=:j").param("j",job)
                    .query(String.class).single()).as("closed reason").isEqualTo(code);
            assertNothingPublished(user,intent);
        }
        // Another user's publication is untouched, and still readable: a command restriction stops new
        // publications, it does not hide an existing result.
        assertThat(jdbc().sql("SELECT count(*) FROM sync_official_plan_result WHERE user_id=:u").param("u",kept)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(dashboardOf(kept)).isInstanceOf(SyncJobApi.Dashboard.Ready.class);
    }

    /**
     * The portfolio pointer moves between the publication's read and its CAS: the publication waits on
     * the portfolio row lock, and the holder advances the pointer before letting it go. The attempt ends
     * with {@code POINTER_CONFLICT} — not {@code MEMBERSHIP_CHANGED}, which is a different cause.
     */
    @Test
    void aPointerThatMovesBeforeItsCasEndsThePublicationWithPointerConflict() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-pointer","0.1",null);
        var current=jdbc().sql("SELECT c.portfolio_id,c.portfolio_snapshot_id FROM portfolio_current_snapshot c "
                + "JOIN portfolio p ON p.portfolio_id=c.portfolio_id WHERE p.user_id=:u").param("u",user)
                .query((rs,n)->new UUID[] {rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)}).single();
        UUID moved=UUID.randomUUID();
        UUID job;
        AutoCloseable portfolioLock=holdRowLock("SELECT portfolio_id FROM portfolio WHERE portfolio_id=:id FOR UPDATE",
                current[0],() -> {
                    jdbc().sql("INSERT INTO portfolio_snapshot(portfolio_snapshot_id,portfolio_id,user_id,amount_decimal,"
                            + "base_currency,measured_at,publication_sequence) SELECT :m,portfolio_id,user_id,amount_decimal,"
                            + "'USD',measured_at,2 FROM portfolio_snapshot WHERE portfolio_snapshot_id=:s")
                            .param("m",moved).param("s",current[1]).update();
                    jdbc().sql("UPDATE portfolio_current_snapshot SET portfolio_snapshot_id=:m,publication_sequence=2 "
                            + "WHERE portfolio_id=:p").param("m",moved).param("p",current[0]).update();
                });
        try {
            job=publish(user,intent,"publish-pointer-command-001");
            awaitBlockedOn("FOR UPDATE OF p,m");
        } finally {
            portfolioLock.close();
        }
        assertThat(awaitPublication(job)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_publication_job WHERE job_id=:j").param("j",job)
                .query(String.class).single()).isEqualTo("POINTER_CONFLICT");
        for (String table : new String[] {"plan_definition","sync_plan_calculation","sync_official_plan_result"}) {
            assertThat(jdbc().sql("SELECT count(*) FROM "+table+" WHERE user_id=:u").param("u",user)
                    .query(Integer.class).single()).as(table).isZero();
        }
        assertThat(jdbc().sql("SELECT portfolio_snapshot_id FROM portfolio_current_snapshot WHERE portfolio_id=:p")
                .param("p",current[0]).query(UUID.class).single()).as("only the holder moved it").isEqualTo(moved);
        assertThat(jdbc().sql("SELECT status FROM plan_condition_intent WHERE intent_id=:i").param("i",intent)
                .query(String.class).single()).isEqualTo("OPEN");
        // Story 2.8: a blocked attempt is finished, not "being prepared".
        assertThat(dashboardOf(user)).isEqualTo(new SyncJobApi.Dashboard.NoPlan("CREATE_PLAN"));
    }

    /** The plan's end arrives while the publication waits: judged by the database clock, with its own code. */
    @Test
    void aPlanPeriodThatEndsWhileThePublicationWaitsEndsItWithPlanPeriodEnded() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-period","0.1",null);
        UUID job;
        AutoCloseable lock=holdPlanLock(user);
        try {
            job=publish(user,intent,"publish-period-command-001");
            awaitBlockedOnPlanLock();
            jdbc().sql("UPDATE plan_condition_intent SET end_at=clock_timestamp()+interval '300 milliseconds' "
                    + "WHERE intent_id=:i").param("i",intent).update();
            awaitDatabaseClockPast(jdbc().sql("SELECT end_at FROM plan_condition_intent WHERE intent_id=:i")
                    .param("i",intent).query(java.time.OffsetDateTime.class).single());
        } finally {
            lock.close();
        }
        assertThat(awaitPublication(job)).isEqualTo("BLOCKED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_publication_job WHERE job_id=:j").param("j",job)
                .query(String.class).single()).isEqualTo("PLAN_PERIOD_ENDED");
        assertNothingPublished(user,intent);
    }

    /** An input beyond CALC-1.0's input limits (an E with 19 decimals) is refused by the calculator. */
    @Test
    void anInputBeyondTheCalculationLimitsFailsWithCalculationInputInvalid() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-invalid","0.1",null,"1250.0000000000000000001");
        UUID job=publish(user,intent,"publish-invalid-command-001");
        assertThat(awaitPublication(job)).isEqualTo("FAILED");
        assertThat(jdbc().sql("SELECT failure_code FROM sync_plan_publication_job WHERE job_id=:j").param("j",job)
                .query(String.class).single()).isEqualTo("CALCULATION_INPUT_INVALID");
        assertNothingPublished(user,intent);
    }

    /**
     * The publishing transaction holds its attempt row for its whole length, and the heartbeat runs on the
     * scheduler thread that dispatches every other job. A heartbeat that waited for that row would stall
     * all of them; it skips the locked row instead, and another user's first snapshot still completes.
     */
    @Test
    void aLockedAttemptRowNeverStallsTheSchedulerThatDispatchesEveryOtherJob() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-heartbeat","0.1",null);
        UUID other=connect(DevBybitStub.Scenario.UTA2);
        UUID job;
        AutoCloseable planLock=holdPlanLock(user);
        try {
            job=publish(user,intent,"publish-heartbeat-command-001");
            awaitBlockedOnPlanLock();
            AutoCloseable rowLock=holdRowLock("SELECT job_id FROM sync_plan_publication_job WHERE job_id=:id FOR UPDATE",
                    job,() -> {});
            try {
                // Long enough for at least one heartbeat (every five seconds) to meet the held row.
                Thread.sleep(6000);
                UUID otherJob=sync.admit(other,"publish-heartbeat-other-001").orElseThrow().jobId();
                assertThat(await(other,otherJob)).isEqualTo("SUCCEEDED");
            } finally {
                rowLock.close();
            }
        } finally {
            planLock.close();
        }
        assertThat(awaitPublication(job)).as("the skipped renewal kept the lease").isEqualTo("PUBLISHED");
    }

    /**
     * A lease that expires while the publishing transaction waits cannot publish: lease checks compare with
     * the statement's time, not the transaction's start. The expired attempt is reclaimed with a new fence,
     * and only that holder publishes.
     */
    @Test
    void aLeaseThatExpiresWhileThePublicationWaitsCannotPublish() throws Exception {
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-expired","0.1",null);
        UUID job;
        long claimed;
        AutoCloseable planLock=holdPlanLock(user);
        try {
            job=publish(user,intent,"publish-expired-command-001");
            awaitBlockedOnPlanLock();
            claimed=jdbc().sql("SELECT fencing_generation FROM sync_plan_publication_job WHERE job_id=:j")
                    .param("j",job).query(Long.class).single();
            // The held row keeps the heartbeat from renewing; the lease ends a second after it is taken.
            AutoCloseable rowLock=holdRowLock("SELECT job_id FROM sync_plan_publication_job WHERE job_id=:id FOR UPDATE",
                    job,() -> jdbc().sql("UPDATE sync_plan_publication_job SET lease_until=clock_timestamp()"
                            + "+interval '1 second' WHERE job_id=:j").param("j",job).update());
            try {
                // The holder's lease_until is invisible until it lets go, but it was set before this read:
                // a second after this read is past it.
                awaitDatabaseClockPast(jdbc().sql("SELECT clock_timestamp()+interval '1 second'")
                        .query(java.time.OffsetDateTime.class).single());
            } finally {
                rowLock.close();
            }
        } finally {
            planLock.close();
        }
        assertThat(awaitPublication(job)).isEqualTo("PUBLISHED");
        assertThat(jdbc().sql("SELECT fencing_generation FROM sync_plan_publication_job WHERE job_id=:j")
                .param("j",job).query(Long.class).single()).as("published by the reclaiming holder, not the expired one")
                .isEqualTo(claimed+1);
    }

    /**
     * SYNC-UX-1.0 against the database clock: each time alone can make the result stale; any job syncs it.
     * Story 2.8: the axis names which time is outside the window, reaches all six metrics unless fresh,
     * vouches for the measurement, and asks the screen to wait only while a job runs.
     */
    @Test
    void freshnessFollowsBothTimesAndAnyJobInProgress() throws Exception {
        Instant base=closedTenMinuteBoundary();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publish-freshness","0.1",base.minusSeconds(3600));
        assertThat(awaitPublication(publish(user,intent,"publish-freshness-command-001"))).isEqualTo("PUBLISHED");
        assertFreshness(user,"FRESH","BOTH_WITHIN_WINDOW","NONE");
        jdbc().sql("UPDATE sync_plan_calculation SET current_value_measured_at=now()-interval '16 minutes' "
                + "WHERE user_id=:u").param("u",user).update();
        // Story 2.11: a stale result's next action is a new sync.
        assertFreshness(user,"STALE","MEASUREMENT_OUTDATED","SYNC");
        jdbc().sql("UPDATE sync_plan_calculation SET current_value_measured_at=now(),basis_at=now()-interval '16 minutes',"
                + "cashflow_verified_through=now()-interval '16 minutes',last_extended_verification_at="
                + "now()-interval '16 minutes' WHERE user_id=:u").param("u",user).update();
        assertFreshness(user,"STALE","CASHFLOW_VERIFICATION_OUTDATED","SYNC");
        jdbc().sql("UPDATE sync_plan_calculation SET current_value_measured_at=now()-interval '16 minutes' "
                + "WHERE user_id=:u").param("u",user).update();
        assertFreshness(user,"STALE","BOTH_OUTDATED","SYNC");
        jdbc().sql("UPDATE sync_plan_calculation SET current_value_measured_at=now(),basis_at=now(),"
                + "cashflow_verified_through=now() WHERE user_id=:u").param("u",user).update();
        assertFreshness(user,"FRESH","BOTH_WITHIN_WINDOW","NONE");
        // A job in progress: RUNNING under a lease no worker will take over, so it stays in progress.
        UUID probe=UUID.randomUUID();
        jdbc().sql("INSERT INTO sync_job(job_id,user_id,connection_id,provider_account_id,idempotency_key,status,"
                + "fencing_generation,lease_until,heartbeat_at) SELECT :j,user_id,connection_id,provider_account_id,"
                + "'freshness-probe','RUNNING',1,now()+interval '1 hour',now() FROM source_connection WHERE user_id=:u")
                .param("j",probe).param("u",user).update();
        try {
            // Syncing wins over stale, and the official values stay.
            jdbc().sql("UPDATE sync_plan_calculation SET current_value_measured_at=now()-interval '16 minutes' "
                    + "WHERE user_id=:u").param("u",user).update();
            assertFreshness(user,"SYNCING","JOB_IN_PROGRESS","WAIT_FOR_SYNC");
        } finally {
            jdbc().sql("DELETE FROM sync_job WHERE job_id=:j").param("j",probe).update();
        }
        assertFreshness(user,"STALE","MEASUREMENT_OUTDATED","SYNC");
    }

    private void assertFreshness(UUID user,String state,String cause,String nextAction) {
        var dashboard=(SyncJobApi.Dashboard.Ready)dashboardOf(user);
        var freshness=dashboard.result().trust().freshness();
        assertThat(freshness.state()).as(cause).isEqualTo(state);
        assertThat(freshness.cause()).isEqualTo(cause);
        assertThat(freshness.lastNormalAt()).isEqualTo(dashboard.result().measuredAt());
        assertThat(freshness.affectedMetrics()).hasSize(state.equals("FRESH") ? 0 : 6);
        assertThat(freshness.nextAction()).isEqualTo(nextAction);
        assertThat(dashboard.nextAction()).as("the screen's next action").isEqualTo(nextAction);
        // Freshness is independent of the other two axes.
        assertThat(dashboard.result().trust().inputReliability().state()).isEqualTo("CONFIRMED");
    }

    /** The dashboard read's outcome for the user (the service port; it writes no log line). */
    private SyncJobApi.Dashboard dashboardOf(@org.jspecify.annotations.Nullable UUID user) {
        return sync.dashboard(user).dashboard();
    }

    /** The dashboard's official bundle; fails unless the read is {@code READY}. */
    private SyncJobApi.DashboardResult ready(UUID user) {
        var dashboard=dashboardOf(user);
        assertThat(dashboard).isInstanceOf(SyncJobApi.Dashboard.Ready.class);
        return ((SyncJobApi.Dashboard.Ready)dashboard).result();
    }

    /** The {@code result} object of a {@code READY} dashboard response body. */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> resultOf(String body) {
        var fields=topLevelFieldsOf(body);
        assertThat(fields).containsEntry("state","READY");
        return (java.util.Map<String,Object>)fields.get("result");
    }

    /**
     * A provider notation with trailing zeros (Bybit may send {@code "1250.00"}) is kept as provenance on the
     * first snapshot and the intent, but the attempt, the calculation version and every view carry the
     * lossless canonical notation — a client that accepts canonical decimals only must not lose the result.
     */
    @Test
    void aTrailingZeroNotationIsPublishedAndServedCanonically() throws Exception {
        String subject="publication-notation-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID seed=sync.admit(user,"publication-notation-seed").orElseThrow().jobId();
        assertThat(await(user,seed)).isEqualTo("SUCCEEDED");
        for (String sql : new String[] {"UPDATE portfolio_snapshot SET amount_decimal='1234.5600' WHERE user_id=:u",
                "UPDATE portfolio_snapshot_component SET amount_decimal='1234.5600' WHERE user_id=:u",
                "UPDATE sync_source_snapshot SET equity_decimal='1234.5600' WHERE user_id=:u"}) {
            jdbc().sql(sql).param("u",user).update();
        }
        UUID intent=confirmIntent(user,"publication-notation","0.1");
        assertThat(jdbc().sql("SELECT p0_decimal FROM plan_condition_intent WHERE intent_id=:i").param("i",intent)
                .query(String.class).single()).as("provenance keeps the provider notation").isEqualTo("1234.5600");
        convert(user,intent,"publication-notation",null,"1250.00");
        UUID job=publish(user,intent,"publication-notation-command-001");
        assertThat(awaitPublication(job)).isEqualTo("PUBLISHED");

        assertThat(jdbc().sql("SELECT p0_decimal||'|'||target_rate||'|'||current_value_decimal FROM "
                + "sync_plan_publication_job WHERE job_id=:j").param("j",job).query(String.class).single())
                .isEqualTo("1234.56|0.1|1250");
        assertThat(jdbc().sql("SELECT p0_decimal||'|'||target_rate FROM sync_plan_calculation WHERE user_id=:u")
                .param("u",user).query(String.class).single()).isEqualTo("1234.56|0.1");
        var result=resultOf(get(AuthSecurityConfig.DASHBOARD_PATH,cookie).body());
        @SuppressWarnings("unchecked")
        var conditions=(java.util.Map<String,Object>)result.get("conditions");
        assertThat(conditions).containsEntry("principal","1234.56").containsEntry("targetRate","0.1");
        @SuppressWarnings("unchecked")
        var metrics=(java.util.List<java.util.Map<String,Object>>)result.get("metrics");
        assertThat(metrics.getFirst()).containsEntry("metric","CURRENT_VALUE").containsEntry("value","1250");
        var state=topLevelFieldsOf(get(AuthSecurityConfig.PLAN_CONDITIONS_PATH,cookie).body());
        @SuppressWarnings("unchecked")
        var active=(java.util.Map<String,Object>)state.get("activePlan");
        assertThat(active).containsEntry("decimal","1234.56").containsEntry("targetRate","0.1")
                .containsKey("startAt").doesNotContainKey("measuredAt");
    }

    @Test
    void erasureRemovesTheUsersPublicationAndKeepsOtherUsers() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID kept=connect(DevBybitStub.Scenario.UTA2);
        // The kept user has an applied flow of its own, so the erasure's reach is visible in the shared table.
        UUID keptIntent=convertedIntent(kept,"erase-publication-kept","0.1",base.minusSeconds(3600),
                new Flow("erase-publication-kept-in","USDT","7",base.plusSeconds(11)));
        assertThat(awaitPublication(publish(kept,keptIntent,"erase-publication-kept-001"))).isEqualTo("PUBLISHED");
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"erase-publication","0.1",base.minusSeconds(3600),
                new Flow("erase-publication-in","USDT","5",base.plusSeconds(13)));
        assertThat(awaitPublication(publish(user,intent,"erase-publication-001"))).isEqualTo("PUBLISHED");
        // Story 2.9: the evidence has no table of its own, so the same erasure removes what it is read from.
        var erasedReference=referenceOf(ready(user));
        evidenceOf(user);
        // Story 2.10: neither has the adjustment history.
        var erasedResult=ready(user);
        UUID erasedCalculation=erasedResult.calculationId();
        assertThat(historyOf(user,SyncJobApi.AdjustmentDirection.ALL,null).rows()).hasSize(1);

        portfolio.erasePortfolioData(user);
        sync.eraseSyncData(user);
        plans.erasePlanData(user);
        source.eraseSourceData(user);
        for (String table : new String[] {"plan_definition","plan_condition_intent","plan_condition_confirmation",
                "portfolio_snapshot","sync_plan_calculation","sync_official_plan_result","sync_plan_publication_job",
                "sync_source_snapshot","sync_calculable_cashflow"}) {
            assertThat(jdbc().sql("SELECT count(*) FROM "+table+" WHERE user_id=:u").param("u",user)
                    .query(Integer.class).single()).as(table).isZero();
        }
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_metric m LEFT JOIN sync_plan_calculation c "
                + "ON c.calculation_id=m.calculation_id WHERE c.calculation_id IS NULL").query(Integer.class).single()).isZero();
        // The erased user's applied history went with its version; the kept user's stays.
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow WHERE calculation_id=:c")
                .param("c",erasedCalculation).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow f LEFT JOIN sync_plan_calculation c "
                + "ON c.calculation_id=f.calculation_id WHERE c.calculation_id IS NULL").query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow f JOIN sync_plan_calculation c "
                + "ON c.calculation_id=f.calculation_id WHERE c.user_id=:u").param("u",kept).query(Integer.class).single())
                .isEqualTo(1);
        assertThat(sync.adjustmentHistory(user,adjustmentQuery(erasedResult,SyncJobApi.AdjustmentDirection.ALL,null)))
                .isEqualTo(new SyncJobApi.AdjustmentRead(new SyncJobApi.AdjustmentOutcome.NotFound(),
                        SyncJobApi.BundleConsistency.NOT_READ));
        assertThat(historyOf(kept,SyncJobApi.AdjustmentDirection.ALL,null).rows()).singleElement()
                .satisfies(row -> assertThat(row.references().sourceId()).isEqualTo("erase-publication-kept-in"));
        assertThat(dashboardOf(kept)).isInstanceOf(SyncJobApi.Dashboard.Ready.class);
        assertThat(dashboardOf(user)).as("the erased user's read").isInstanceOf(SyncJobApi.Dashboard.NoPlan.class);
        assertThat(jdbc().sql("SELECT count(*) FROM plan_definition WHERE user_id=:u").param("u",kept)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(sync.calculationEvidence(user,erasedReference))
                .isEqualTo(new SyncJobApi.EvidenceRead(new SyncJobApi.EvidenceOutcome.NotFound(),
                        SyncJobApi.BundleConsistency.NOT_READ));
        assertThat(evidenceOf(kept).unpublishedAttempts()).as("another user's evidence stays").isEmpty();
        for (String table : new String[] {"sync_initial_candidate_bundle","sync_initial_page_evidence",
                "sync_cashflow_classification_bundle","sync_cashflow_conversion_bundle"}) {
            assertThat(jdbc().sql("SELECT count(*) FROM "+table+" WHERE user_id=:u").param("u",user)
                    .query(Integer.class).single()).as("evidence source "+table).isZero();
        }
    }

    @Test
    void publicationAndResultHttpUseAcceptedConflictAndNoStore() throws Exception {
        String subject="publication-http-"+UUID.randomUUID();
        var login=signIn(subject);
        String cookie=sessionCookieOf(login).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"publication-http","0.1",null);
        String path=AuthSecurityConfig.FIRST_SNAPSHOT_PATH+"/plan-publication";
        String csrf=csrfTokenOf(cookie);
        String body="{\"intentId\":\""+intent+"\"}";

        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH,null).statusCode()).as("unauthenticated").isEqualTo(401);
        // Story 2.8 replaced the 2.7 surface: the old path is gone, not a second way to the result.
        assertThat(get("/api/plans/active",cookie).statusCode()).isEqualTo(404);
        var none=get(AuthSecurityConfig.DASHBOARD_PATH,cookie);
        assertThat(none.statusCode()).isEqualTo(200);
        assertThat(none.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(none.body())).as("no plan, no financial field")
                .isEqualTo(java.util.Map.of("state","NO_PLAN","nextAction","CREATE_PLAN"));
        // No attempt yet: a readable state that says a start would be admitted.
        var empty=get(path+"?intentId="+intent,cookie);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat(empty.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(empty.body())).containsEntry("startable",true).doesNotContainKeys("job","reason");
        // Another (unknown) intent is not startable, with the admission's reason — not a 403.
        var foreign=get(path+"?intentId="+UUID.randomUUID(),cookie);
        assertThat(foreign.statusCode()).isEqualTo(200);
        assertThat(topLevelFieldsOf(foreign.body())).containsEntry("startable",false)
                .containsEntry("reason","PLAN_CONDITIONS_REQUIRED").doesNotContainKey("job");
        // A preview reviewed in this session before the publication; its confirmation comes afterwards.
        String end=LocalDateTime.ofInstant(plans.state(user).snapshot().measuredAt(),ZoneId.of("Asia/Seoul"))
                .plusDays(20).withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var reviewed=sendJson("POST",AuthSecurityConfig.PLAN_CONDITIONS_PATH+"/preview",
                "{\"endAtSeoul\":\""+end+"\",\"period\":\"WEEKLY\",\"targetRate\":\"0.2\"}",cookie,
                "Origin",OidcStubConfiguration.PUBLIC_BASE_URL,AuthSecurityConfig.CSRF_HEADER_NAME,csrf);
        assertThat(reviewed.statusCode()).isEqualTo(200);
        String reviewedRef=(String)topLevelFieldsOf(reviewed.body()).get("confirmationRef");

        assertThat(sendJson("POST",path,body,cookie,"Idempotency-Key","http-publication-001").statusCode())
                .as("no CSRF token or Origin").isEqualTo(403);
        // The command creates an immutable plan: no key is invented for the caller.
        var keyless=sendJson("POST",path,body,cookie,"Origin",OidcStubConfiguration.PUBLIC_BASE_URL,
                AuthSecurityConfig.CSRF_HEADER_NAME,csrf);
        assertThat(keyless.statusCode()).isEqualTo(422);
        assertThat(topLevelFieldsOf(keyless.body())).containsEntry("code","IDEMPOTENCY_KEY_REQUIRED")
                .containsKeys("message","correlationId");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_publication_job WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isZero();
        var started=sendJson("POST",path,body,cookie,"Origin",OidcStubConfiguration.PUBLIC_BASE_URL,
                AuthSecurityConfig.CSRF_HEADER_NAME,csrf,"Idempotency-Key","http-publication-001");
        assertThat(started.statusCode()).isEqualTo(202);
        assertThat(started.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        UUID job=UUID.fromString((String)topLevelFieldsOf(started.body()).get("jobId"));
        assertThat(awaitPublication(job)).isEqualTo("PUBLISHED");
        var done=get(path+"?intentId="+intent,cookie);
        assertThat(done.statusCode()).isEqualTo(200);
        var doneFields=topLevelFieldsOf(done.body());
        assertThat(doneFields).containsEntry("startable",false).containsEntry("reason","ACTIVE_PLAN_EXISTS");
        @SuppressWarnings("unchecked")
        var doneJob=(java.util.Map<String,Object>)doneFields.get("job");
        assertThat(doneJob).containsEntry("status","PUBLISHED").containsKey("planId");

        // Confirming the earlier preview, or any reference at all, now answers the actual state.
        for (String ref : new String[] {reviewedRef,"unknown-reference-"+UUID.randomUUID()}) {
            var late=sendJson("POST",AuthSecurityConfig.PLAN_CONDITIONS_PATH+"/confirm",
                    "{\"confirmationRef\":\""+ref+"\"}",cookie,"Origin",OidcStubConfiguration.PUBLIC_BASE_URL,
                    AuthSecurityConfig.CSRF_HEADER_NAME,csrf,"Idempotency-Key","http-late-confirm-"+UUID.randomUUID());
            assertThat(late.statusCode()).as(ref).isEqualTo(409);
            assertThat(topLevelFieldsOf(late.body())).containsEntry("code","ACTIVE_PLAN_EXISTS")
                    .containsKeys("message","correlationId");
        }

        var result=get(AuthSecurityConfig.DASHBOARD_PATH,cookie);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        // Story 2.11: a ready dashboard carries its sync state beside the result; Story 2.12 its verification state
        // and what the version changed (a first publication: nothing); Story 2.13 whether it can be ended now.
        assertThat(topLevelFieldsOf(result.body())).containsOnlyKeys("state","nextAction","result","sync",
                "verification","change","planEnd").containsEntry("nextAction","NONE");
        assertThat(nested(topLevelFieldsOf(result.body()),"planEnd")).containsOnlyKeys("availability","reason")
                .containsEntry("availability","AVAILABLE").containsEntry("reason",null);
        assertThat(nested(topLevelFieldsOf(result.body()),"change")).containsEntry("kind","NONE")
                .containsEntry("purpose",null).containsEntry("lateChangeCount",0);
        var fields=resultOf(result.body());
        assertThat(fields).containsKeys("planId","conditions","basisAt","measuredAt","cashflowVerifiedThrough",
                "trust","metrics","policies","pointerVersion");
        @SuppressWarnings("unchecked")
        var metrics=(java.util.List<java.util.Map<String,Object>>)fields.get("metrics");
        assertThat(metrics).extracting(m -> m.get("metric")).containsExactly("CURRENT_VALUE","PLAN_AMOUNT",
                "PLAN_DIFFERENCE","ADJUSTED_TARGET","REQUIRED_RATE","GOAL_PROGRESS");
        assertThat(metrics).allSatisfy(m -> assertThat(m.get("value")).isInstanceOf(String.class));
        @SuppressWarnings("unchecked")
        var trust=(java.util.Map<String,java.util.Map<String,Object>>)fields.get("trust");
        // Story 2.8 AC-5: each axis names its last normal time — the one time of the bundle it vouches for.
        assertThat(trust.get("freshness")).containsEntry("state","FRESH").containsEntry("cause","BOTH_WITHIN_WINDOW")
                .containsEntry("lastNormalAt",fields.get("measuredAt"))
                .containsEntry("measuredAt",fields.get("measuredAt"))
                .containsEntry("cashflowVerifiedThrough",fields.get("cashflowVerifiedThrough"))
                .containsEntry("affectedMetrics",java.util.List.of()).containsEntry("nextAction","NONE");
        assertThat(trust.get("inputReliability")).containsEntry("state","CONFIRMED")
                .containsEntry("cause","INPUTS_VERIFIED")
                .containsEntry("lastNormalAt",fields.get("cashflowVerifiedThrough"))
                .containsEntry("affectedMetrics",java.util.List.of()).containsEntry("nextAction","NONE");
        assertThat(trust.get("calculationNature")).containsEntry("state","OFFICIAL").containsEntry("character","OFFICIAL")
                .containsEntry("cause","NO_ESTIMATED_INPUT").containsEntry("lastNormalAt",fields.get("basisAt"))
                .containsEntry("affectedMetrics",java.util.List.of()).containsEntry("nextAction","NONE");
        assertThat(trust).as("three independent axes, no combined score")
                .containsOnlyKeys("freshness","inputReliability","calculationNature");

        var conflict=sendJson("POST",path,body,cookie,"Origin",OidcStubConfiguration.PUBLIC_BASE_URL,
                AuthSecurityConfig.CSRF_HEADER_NAME,csrf,"Idempotency-Key","http-publication-002");
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(conflict.body())).containsEntry("code","ACTIVE_PLAN_EXISTS").containsKeys("message",
                "correlationId");
        var conditions=topLevelFieldsOf(get(AuthSecurityConfig.PLAN_CONDITIONS_PATH,cookie).body());
        assertThat(conditions).containsEntry("reason","ACTIVE_PLAN_EXISTS");
        @SuppressWarnings("unchecked")
        var active=(java.util.Map<String,Object>)conditions.get("activePlan");
        assertThat(active).containsEntry("planId",doneJob.get("planId"));
        var preview=sendJson("POST",AuthSecurityConfig.PLAN_CONDITIONS_PATH+"/preview",
                "{\"endAtSeoul\":\"2099-01-01T00:00\",\"period\":\"DAILY\",\"targetRate\":\"0.1\"}",cookie,
                "Origin",OidcStubConfiguration.PUBLIC_BASE_URL,AuthSecurityConfig.CSRF_HEADER_NAME,csrf);
        assertThat(preview.statusCode()).isEqualTo(409);
        assertThat(topLevelFieldsOf(preview.body())).containsEntry("code","ACTIVE_PLAN_EXISTS");
        assertThat(topLevelFieldsOf(get(path+"?intentId="+UUID.randomUUID(),cookie).body()))
                .containsEntry("startable",false).containsEntry("reason","ACTIVE_PLAN_EXISTS");

        // Restrictions on write commands stop new work; they do not hide the published result.
        for (String command : new String[] {"plan-publication","first-snapshot","initial-source-event-collection"}) {
            jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) VALUES (:id,:c)")
                    .param("id",UUID.randomUUID()).param("c",command).update();
        }
        assertThat(get(AuthSecurityConfig.DASHBOARD_PATH,cookie).statusCode()).isEqualTo(200);
        // The account's own access does: while its deletion is in progress the result is refused.
        accountDeletion().beginDeletion(user);
        var refused=get(AuthSecurityConfig.DASHBOARD_PATH,cookie);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(topLevelFieldsOf(refused.body())).containsOnlyKeys("code","message","correlationId");
        assertThat(get(path+"?intentId="+intent,cookie).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------------------------------
    // Story 2.8 official dashboard: one consistent read, three states, tamper, consistency, deletion
    // ------------------------------------------------------------------------------------------

    /**
     * NO_PLAN, PREPARING and READY from the one surface. The publication is held on the plan lock after its
     * claim, so the dashboard reads "being prepared" while the attempt runs and no plan exists yet.
     */
    @Test
    void theDashboardAnswersNoPlanThenPreparingThenTheReadyBundle() throws Exception {
        String subject="dashboard-states-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"dashboard-states","0.1",null);
        assertThat(topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH,cookie).body()))
                .isEqualTo(java.util.Map.of("state","NO_PLAN","nextAction","CREATE_PLAN"));

        UUID job;
        AutoCloseable lock=holdPlanLock(user);
        try {
            job=publish(user,intent,"dashboard-states-command-001");
            awaitBlockedOnPlanLock();
            var preparing=get(AuthSecurityConfig.DASHBOARD_PATH,cookie);
            assertThat(preparing.statusCode()).isEqualTo(200);
            assertThat(preparing.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            assertThat(topLevelFieldsOf(preparing.body())).as("no financial field while it is prepared")
                    .isEqualTo(java.util.Map.of("state","PREPARING","nextAction","WAIT_FOR_RESULT"));
        } finally {
            lock.close();
        }
        assertThat(awaitPublication(job)).isEqualTo("PUBLISHED");
        var ready=get(AuthSecurityConfig.DASHBOARD_PATH,cookie);
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(ready.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var result=resultOf(ready.body());
        assertThat(result).containsEntry("pointerVersion",1)
                .containsEntry("calculationId",jdbc().sql("SELECT calculation_id::text FROM sync_official_plan_result "
                        + "WHERE user_id=:u").param("u",user).query(String.class).single());
    }

    /**
     * AC-1: the path takes no identifier. Another user's plan, snapshot or calculation id in the query
     * changes nothing — the answer is the bare request's answer to the letter — and every sub-path, with
     * this user's id, another user's or none that exists, is the same common 404.
     */
    @Test
    void aTamperedQueryOrSubPathRevealsNothingAboutAnyPlan() throws Exception {
        String subject="dashboard-tamper-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"dashboard-tamper","0.1",null);
        assertThat(awaitPublication(publish(user,intent,"dashboard-tamper-command-001"))).isEqualTo("PUBLISHED");
        UUID other=connect(DevBybitStub.Scenario.UTA2);
        UUID otherIntent=convertedIntent(other,"dashboard-tamper-other","0.2",null);
        assertThat(awaitPublication(publish(other,otherIntent,"dashboard-tamper-other-001"))).isEqualTo("PUBLISHED");
        var foreign=jdbc().sql("SELECT plan_id::text,calculation_id::text,portfolio_snapshot_id::text FROM "
                + "sync_plan_calculation WHERE user_id=:u").param("u",other)
                .query((rs,n)->new String[] {rs.getString(1),rs.getString(2),rs.getString(3)}).single();
        String ownPlan=jdbc().sql("SELECT plan_id::text FROM plan_definition WHERE user_id=:u").param("u",user)
                .query(String.class).single();

        String bare=get(AuthSecurityConfig.DASHBOARD_PATH,cookie).body();
        assertThat(resultOf(bare)).containsEntry("planId",ownPlan);
        for (String query : new String[] {"?planId="+foreign[0],"?calculationId="+foreign[1],"?snapshotId="+foreign[2],
                "?portfolioSnapshotId="+foreign[2],"?userId="+other,
                "?planId="+foreign[0]+"&calculationId="+foreign[1]+"&snapshotId="+foreign[2],"?planId="+ownPlan}) {
            var tampered=get(AuthSecurityConfig.DASHBOARD_PATH+query,cookie);
            assertThat(tampered.statusCode()).as(query).isEqualTo(200);
            assertThat(tampered.body()).as(query).isEqualTo(bare);
        }
        String notFound=null;
        for (String sub : new String[] {"/"+foreign[0],"/"+ownPlan,"/"+UUID.randomUUID(),"/"+foreign[1]+"/metrics","/"}) {
            var probe=send("GET",AuthSecurityConfig.DASHBOARD_PATH+sub,cookie,"X-Request-Id","dashboard-tamper-probe");
            assertThat(probe.statusCode()).as(sub).isEqualTo(404);
            assertThat(topLevelFieldsOf(probe.body())).as(sub).containsEntry("code","NOT_FOUND");
            if (notFound==null) notFound=probe.body();
            assertThat(probe.body()).as(sub+" is the same answer whether or not the target exists").isEqualTo(notFound);
            assertThat(get(AuthSecurityConfig.DASHBOARD_PATH+sub,null).statusCode()).as("unauthenticated "+sub)
                    .isEqualTo(401);
        }
        // The other user's result is untouched and still their own.
        assertThat(ready(other).planId().toString()).isEqualTo(foreign[0]);
    }

    /**
     * AC-2: a pointer whose bundle does not agree with itself answers 503 {@code DASHBOARD_UNAVAILABLE} and no
     * metric at all; the operational line says {@code consistent=false}. Each corruption is undone before
     * the next, and the restored bundle is ready again — the refusal is the bundle's, not the account's.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void anInconsistentBundleReturnsNoMetricAndLogsConsistentFalse(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String subject="dashboard-inconsistent-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"dashboard-inconsistent","0.1",null);
        assertThat(awaitPublication(publish(user,intent,"dashboard-inconsistent-command-001"))).isEqualTo("PUBLISHED");
        UUID kept=connect(DevBybitStub.Scenario.UTA2);
        UUID keptIntent=convertedIntent(kept,"dashboard-inconsistent-kept","0.1",null);
        assertThat(awaitPublication(publish(kept,keptIntent,"dashboard-inconsistent-kept-001"))).isEqualTo("PUBLISHED");
        var ids=jdbc().sql("SELECT c.calculation_id,c.plan_id,c.portfolio_id,c.basis_portfolio_snapshot_id,"
                + "c.portfolio_snapshot_id FROM sync_plan_calculation c WHERE c.user_id=:u").param("u",user)
                .query((rs,n)->new UUID[] {rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                        rs.getObject(4,UUID.class),rs.getObject(5,UUID.class)}).single();
        UUID calc=ids[0], plan=ids[1], portfolioId=ids[2], basis=ids[3], current=ids[4];
        String amount=jdbc().sql("SELECT value_decimal FROM sync_plan_calculation_metric WHERE calculation_id=:c "
                + "AND metric='CURRENT_VALUE'").param("c",calc).query(String.class).single();
        var goal=jdbc().sql("SELECT value_decimal,nature FROM sync_plan_calculation_metric WHERE calculation_id=:c "
                + "AND metric='GOAL_PROGRESS'").param("c",calc).query((rs,n)->new String[] {rs.getString(1),rs.getString(2)})
                .single();
        var pointer=jdbc().sql("SELECT pointer_version,published_at FROM sync_official_plan_result WHERE plan_id=:p")
                .param("p",plan).query((rs,n)->new Object[] {rs.getLong(1),rs.getObject(2,java.time.OffsetDateTime.class)})
                .single();
        var terms=jdbc().sql("SELECT period,p0_decimal FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c",calc).query((rs,n)->new String[] {rs.getString(1),rs.getString(2)}).single();
        record Corruption(String name,Runnable corrupt,Runnable restore) {}
        var corruptions=java.util.List.of(
                new Corruption("a metric is missing",
                        () -> jdbc().sql("DELETE FROM sync_plan_calculation_metric WHERE calculation_id=:c "
                                + "AND metric='GOAL_PROGRESS'").param("c",calc).update(),
                        () -> jdbc().sql("INSERT INTO sync_plan_calculation_metric(calculation_id,metric,value_decimal,nature) "
                                + "VALUES (:c,'GOAL_PROGRESS',:v,:n)").param("c",calc).param("v",goal[0]).param("n",goal[1])
                                .update()),
                new Corruption("the portfolio pointer cites another snapshot",
                        () -> jdbc().sql("UPDATE portfolio_current_snapshot SET portfolio_snapshot_id=:s,publication_sequence=1 "
                                + "WHERE portfolio_id=:p").param("s",basis).param("p",portfolioId).update(),
                        () -> jdbc().sql("UPDATE portfolio_current_snapshot SET portfolio_snapshot_id=:s,publication_sequence=2 "
                                + "WHERE portfolio_id=:p").param("s",current).param("p",portfolioId).update()),
                new Corruption("the version belongs to another portfolio",
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET portfolio_id=:p WHERE calculation_id=:c")
                                .param("p",UUID.randomUUID()).param("c",calc).update(),
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET portfolio_id=:p WHERE calculation_id=:c")
                                .param("p",portfolioId).param("c",calc).update()),
                new Corruption("the version was calculated at another target rate",
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET target_rate='0.2' WHERE calculation_id=:c")
                                .param("c",calc).update(),
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET target_rate='0.1' WHERE calculation_id=:c")
                                .param("c",calc).update()),
                new Corruption("the version was calculated from another start",
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET t0=t0-interval '1 minute' WHERE calculation_id=:c")
                                .param("c",calc).update(),
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET t0=t0+interval '1 minute' WHERE calculation_id=:c")
                                .param("c",calc).update()),
                new Corruption("the version was calculated to another end",
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET end_at=end_at+interval '1 day' WHERE calculation_id=:c")
                                .param("c",calc).update(),
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET end_at=end_at-interval '1 day' WHERE calculation_id=:c")
                                .param("c",calc).update()),
                new Corruption("the version was calculated with another period",
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET period='WEEKLY' WHERE calculation_id=:c")
                                .param("c",calc).update(),
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET period=:p WHERE calculation_id=:c")
                                .param("p",terms[0]).param("c",calc).update()),
                new Corruption("the version was calculated from another principal",
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET p0_decimal='1234.57' WHERE calculation_id=:c")
                                .param("c",calc).update(),
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET p0_decimal=:p WHERE calculation_id=:c")
                                .param("p",terms[1]).param("c",calc).update()),
                // The pointer's composite key pins the version's plan; a replica session skips it to break that.
                new Corruption("the version belongs to another plan",
                        () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                            jdbc().sql("SET LOCAL session_replication_role = replica").update();
                            jdbc().sql("UPDATE sync_plan_calculation SET plan_id=:x WHERE calculation_id=:c")
                                    .param("x",UUID.randomUUID()).param("c",calc).update();
                        }),
                        () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                            jdbc().sql("SET LOCAL session_replication_role = replica").update();
                            jdbc().sql("UPDATE sync_plan_calculation SET plan_id=:p WHERE calculation_id=:c")
                                    .param("p",plan).param("c",calc).update();
                        })),
                new Corruption("the active plan has no pointer",
                        () -> jdbc().sql("DELETE FROM sync_official_plan_result WHERE plan_id=:p").param("p",plan).update(),
                        () -> jdbc().sql("INSERT INTO sync_official_plan_result(plan_id,user_id,portfolio_id,calculation_id,"
                                + "pointer_version,published_at) VALUES (:p,:u,:portfolio,:c,:v,:at)").param("p",plan)
                                .param("u",user).param("portfolio",portfolioId).param("c",calc).param("v",pointer[0])
                                .param("at",pointer[1]).update()),
                // The foreign keys make a dangling pointer impossible; a replica session skips them to build one.
                new Corruption("the pointer's calculation does not exist",
                        () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                            jdbc().sql("SET LOCAL session_replication_role = replica").update();
                            jdbc().sql("UPDATE sync_official_plan_result SET calculation_id=:x WHERE plan_id=:p")
                                    .param("x",UUID.randomUUID()).param("p",plan).update();
                        }),
                        () -> jdbc().sql("UPDATE sync_official_plan_result SET calculation_id=:c WHERE plan_id=:p")
                                .param("c",calc).param("p",plan).update()));
        for (Corruption corruption : corruptions) {
            corruption.corrupt().run();
            var refused=send("GET",AuthSecurityConfig.DASHBOARD_PATH,cookie,"X-Request-Id","dashboard-inconsistent");
            assertThat(refused.statusCode()).as(corruption.name()).isEqualTo(503);
            assertThat(refused.headers().firstValue("Cache-Control").orElse("")).as(corruption.name()).contains("no-store");
            assertThat(topLevelFieldsOf(refused.body())).as(corruption.name())
                    .containsOnlyKeys("code","message","correlationId").containsEntry("code","DASHBOARD_UNAVAILABLE")
                    .containsEntry("correlationId","dashboard-inconsistent");
            assertThat(refused.body()).as(corruption.name()).doesNotContain(amount).doesNotContain(calc.toString())
                    .doesNotContain(plan.toString());
            assertThat(dashboardOf(user)).as(corruption.name()).isInstanceOf(SyncJobApi.Dashboard.Unavailable.class);
            corruption.restore().run();
            assertThat(dashboardOf(user)).as("restored: "+corruption.name())
                    .isInstanceOf(SyncJobApi.Dashboard.Ready.class);
        }
        // One line per HTTP read (the service port writes none), each inconsistent with the status it answered.
        var lines=dashboardLogLines(output);
        assertThat(lines).hasSize(corruptions.size())
                .allSatisfy(line -> assertThat(line).contains("state=INCONSISTENT status=503").endsWith("consistent=false"));
        assertThat(ready(kept).trust().freshness().state()).as("another user's result").isEqualTo("FRESH");
    }

    /**
     * AC-13: the account's own access is decided before and after the read. A deletion that begins while the
     * bundle is being read wins — the read waits on a table lock after its snapshot was taken, the deletion
     * commits meanwhile, and the bundle already read is dropped. In progress or finished, nothing financial
     * comes back, and another user's result stays.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aDeletionOnEitherSideOfTheReadRefusesTheDashboard(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        String subject="dashboard-deletion-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"dashboard-deletion","0.1",null);
        assertThat(awaitPublication(publish(user,intent,"dashboard-deletion-command-001"))).isEqualTo("PUBLISHED");
        UUID kept=connect(DevBybitStub.Scenario.UTA2);
        UUID keptIntent=convertedIntent(kept,"dashboard-deletion-kept","0.1",null);
        assertThat(awaitPublication(publish(kept,keptIntent,"dashboard-deletion-kept-001"))).isEqualTo("PUBLISHED");
        String amount=ready(user).metrics().getFirst().value();

        // After: the read is past its first check and holds its snapshot when the deletion commits.
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>> pending;
        AutoCloseable lock=holdTableLock("sync_official_plan_result");
        try {
            pending=java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return get(AuthSecurityConfig.DASHBOARD_PATH,cookie);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            awaitBlockedOn("FROM sync_official_plan_result");
            accountDeletion().beginDeletion(user);
        } finally {
            lock.close();
        }
        var late=pending.get(20,TimeUnit.SECONDS);
        assertThat(late.statusCode()).as("the deletion that began during the read wins").isEqualTo(403);
        assertThat(topLevelFieldsOf(late.body())).containsOnlyKeys("code","message","correlationId");
        assertThat(late.body()).doesNotContain(amount);
        assertThat(dashboardLogLines(output).getLast()).contains("state=DENIED status=403").contains("consistent=true");

        // Before: in progress. Nothing was read, so nothing is known about the bundle's consistency.
        var before=get(AuthSecurityConfig.DASHBOARD_PATH,cookie);
        assertThat(before.statusCode()).isEqualTo(403);
        assertThat(before.body()).doesNotContain(amount);
        assertThat(dashboardLogLines(output).getLast()).contains("state=DENIED status=403")
                .endsWith("consistent=unknown");
        assertThat(dashboardOf(user)).isInstanceOf(SyncJobApi.Dashboard.NotPermitted.class);

        // Finished: the erasure ran in its order and the account is DELETED; its session went with it.
        portfolio.erasePortfolioData(user);
        sync.eraseSyncData(user);
        plans.erasePlanData(user);
        source.eraseSourceData(user);
        assertThat(accountDeletion().erasePayload(user))
                .isEqualTo(com.bokrilog.identity.DeletionOutcome.COMPLETED);
        assertThat(dashboardOf(user)).as("DELETED").isInstanceOf(SyncJobApi.Dashboard.NotPermitted.class);
        // The erasure removed the account's session rows, so the cookie no longer authenticates: the
        // security filter answers before any read, with the common unauthenticated error.
        var after=get(AuthSecurityConfig.DASHBOARD_PATH,cookie);
        assertThat(after.statusCode()).isEqualTo(401);
        assertThat(topLevelFieldsOf(after.body())).containsOnlyKeys("code","message","correlationId")
                .containsEntry("code","UNAUTHENTICATED");

        assertThat(ready(kept).metrics()).hasSize(6);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_official_plan_result WHERE user_id=:u").param("u",kept)
                .query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * AC-12: one operational line per read, written where the answered status is known, and only the
     * outcome, that status, the latency and whether the bundle agreed with itself — never the user, a
     * correlation id, an amount, a time or a calculation input.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void eachDashboardReadLogsOneLineWithoutUserAmountsOrTimes(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        String subject="dashboard-log-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"dashboard-log","0.1",null);
        assertThat(awaitPublication(publish(user,intent,"dashboard-log-command-001"))).isEqualTo("PUBLISHED");
        String emptySubject="dashboard-log-empty-"+UUID.randomUUID();
        String emptyCookie=sessionCookieOf(signIn(emptySubject)).orElseThrow();
        UUID empty=userIdOf(OidcStubConfiguration.issuer(),emptySubject).orElseThrow();
        String deletingSubject="dashboard-log-deleting-"+UUID.randomUUID();
        String deletingCookie=sessionCookieOf(signIn(deletingSubject)).orElseThrow();
        UUID deleting=userIdOf(OidcStubConfiguration.issuer(),deletingSubject).orElseThrow();
        accountDeletion().beginDeletion(deleting);
        var result=ready(user);

        int before=dashboardLogLines(output).size();
        String[] ids={"dashboard-log-probe-1","dashboard-log-probe-2","dashboard-log-probe-3"};
        assertThat(send("GET",AuthSecurityConfig.DASHBOARD_PATH,emptyCookie,"X-Request-Id",ids[0]).statusCode())
                .isEqualTo(200);
        assertThat(send("GET",AuthSecurityConfig.DASHBOARD_PATH,cookie,"X-Request-Id",ids[1]).statusCode())
                .isEqualTo(200);
        assertThat(send("GET",AuthSecurityConfig.DASHBOARD_PATH,deletingCookie,"X-Request-Id",ids[2]).statusCode())
                .isEqualTo(403);
        var lines=dashboardLogLines(output);
        assertThat(lines).hasSize(before+3);
        var mine=lines.subList(before,lines.size());
        assertThat(mine.get(0)).contains("state=NO_PLAN status=200").endsWith("consistent=true");
        assertThat(mine.get(1)).contains("state=READY status=200").endsWith("consistent=true");
        assertThat(mine.get(2)).contains("state=DENIED status=403").endsWith("consistent=unknown");
        var forbidden=new java.util.ArrayList<String>(java.util.List.of(user.toString(),empty.toString(),
                deleting.toString(),result.planId().toString(),result.calculationId().toString(),
                result.portfolioSnapshotId().toString(),result.inputHash(),result.resultHash(),result.principal(),
                result.measuredAt().toString(),result.basisAt().toString(),ids[0],ids[1],ids[2]));
        // Short values such as "0" would match the status code itself; every amount of this plan is longer.
        result.metrics().forEach(metric -> {
            if (metric.value()!=null && metric.value().length()>=4) forbidden.add(metric.value());
        });
        for (String line : mine) {
            assertThat(line).matches(".*dashboard read: state=[A-Z_]+ status=\\d{3} latencyMs=\\d+ consistent=(true|false|unknown)$");
            for (String value : forbidden) assertThat(line).as(value).doesNotContain(value);
        }
    }

    /**
     * A read that throws answers the common 500 with no financial field, and the one line for it says
     * {@code ERROR} with the status actually answered and {@code consistent=unknown}. The pointer's table is
     * hidden for one request, so the repository read fails inside the consistent read.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aReadThatFailsAnswersTheCommonErrorAndLogsOneErrorLine(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        String subject="dashboard-error-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"dashboard-error","0.1",null);
        assertThat(awaitPublication(publish(user,intent,"dashboard-error-command-001"))).isEqualTo("PUBLISHED");
        String amount=ready(user).metrics().getFirst().value();

        int before=dashboardLogLines(output).size();
        java.net.http.HttpResponse<String> failed;
        jdbc().sql("ALTER TABLE sync_official_plan_result RENAME TO sync_official_plan_result_hidden").update();
        try {
            failed=send("GET",AuthSecurityConfig.DASHBOARD_PATH,cookie,"X-Request-Id","dashboard-error-probe");
        } finally {
            jdbc().sql("ALTER TABLE sync_official_plan_result_hidden RENAME TO sync_official_plan_result").update();
        }
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(failed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(failed.body())).containsOnlyKeys("code","message","correlationId")
                .containsEntry("code","INTERNAL_ERROR").containsEntry("correlationId","dashboard-error-probe");
        assertThat(failed.body()).doesNotContain(amount).doesNotContain("sync_official_plan_result");
        var lines=dashboardLogLines(output);
        assertThat(lines.subList(before,lines.size())).singleElement().asString()
                .contains("state=ERROR status=500").endsWith("consistent=unknown");
        assertThat(output.getAll()).as("no SQL or relation name reaches the log")
                .doesNotContain("sync_official_plan_result_hidden");
        // The next read is whole again.
        assertThat(resultOf(get(AuthSecurityConfig.DASHBOARD_PATH,cookie).body())).containsKey("metrics");
    }

    // ------------------------------------------------------------------------------------------
    // Story 2.9 calculation evidence: the shown pointer's lineage, tamper, broken lineage, deletion
    // ------------------------------------------------------------------------------------------

    /**
     * CURRENT: the three values of the shown pointer answer one read-only bundle, in the screen's order —
     * snapshots, times, input versions, the calculation version, metric impact and versions — each value the
     * one the stored rows hold. Nothing internal to the account (the user id, the provider account id, the
     * Bybit UID, credentials) is in the answer, and the one operational line carries none of it either.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void theShownResultsEvidenceLinksItsWholeLineageInOneAnswer(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        String subject="evidence-official-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"evidence-official","0.1",null);
        assertThat(awaitPublication(publish(user,intent,"evidence-official-command-001"))).isEqualTo("PUBLISHED");
        var shown=resultOf(get(AuthSecurityConfig.DASHBOARD_PATH,cookie).body());

        int before=evidenceLogLines(output).size();
        var answer=send("GET",DashboardController.EVIDENCE_PATH+query(shown),cookie,"X-Request-Id","evidence-probe");
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var fields=topLevelFieldsOf(answer.body());
        assertThat(fields.keySet()).as("the screen's order").containsExactly("planId","pointerVersion","calculationId",
                "snapshots","times","inputs","calculation","metrics","versions","change");
        // Story 2.12: a first publication changed nothing from a version before it.
        assertThat(nested(fields,"change")).containsEntry("kind","NONE").containsEntry("previous",null)
                .containsEntry("metrics",java.util.List.of()).containsEntry("events",java.util.List.of());
        assertThat(fields).containsEntry("planId",shown.get("planId")).containsEntry("pointerVersion",1)
                .containsEntry("calculationId",shown.get("calculationId"));

        var row=jdbc().sql("SELECT portfolio_snapshot_id::text,portfolio_publication_sequence,basis_portfolio_snapshot_id::text,"
                + "source_snapshot_id::text,t0,basis_at,current_value_measured_at,cashflow_verified_through,connection_id::text,"
                + "source_policy_version,candidate_id,classification_id::text,conversion_id::text,classification_policy_version,"
                + "price_policy_version,input_hash,result_hash,implementation_version,created_at,provider_account_id::text "
                + "FROM sync_plan_calculation WHERE user_id=:u").param("u",user)
                .query((rs,n)->new Object[] {rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),
                        rs.getObject(5,java.time.OffsetDateTime.class).toInstant().toString(),
                        rs.getObject(6,java.time.OffsetDateTime.class).toInstant().toString(),
                        rs.getObject(7,java.time.OffsetDateTime.class).toInstant().toString(),
                        rs.getObject(8,java.time.OffsetDateTime.class).toInstant().toString(),rs.getString(9),
                        rs.getString(10),rs.getObject(11,UUID.class),rs.getString(12),rs.getString(13),rs.getString(14),
                        rs.getString(15),rs.getString(16),rs.getString(17),rs.getString(18),
                        rs.getObject(19,java.time.OffsetDateTime.class).toInstant().toString(),rs.getString(20)})
                .single();
        // The basis source snapshot is the first snapshot's: the connection's policy is its record of it.
        var basisSource=jdbc().sql("SELECT s.source_snapshot_id::text,s.policy_version,c.policy_version,"
                + "(s.job_id IS NOT NULL) FROM sync_plan_calculation p JOIN sync_source_snapshot s "
                + "ON s.source_snapshot_id=p.basis_source_snapshot_id JOIN source_connection c "
                + "ON c.connection_id=s.connection_id WHERE p.user_id=:u").param("u",user)
                .query((rs,n)->new Object[] {rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)}).single();
        assertThat(basisSource[3]).as("written by the first-snapshot job").isEqualTo(true);
        assertThat(basisSource[1]).as("the connection's own policy, as the first snapshot recorded it")
                .isEqualTo(basisSource[2]);
        assertThat(nested(fields,"snapshots")).isEqualTo(java.util.Map.of("portfolioSnapshotId",row[0],
                "portfolioSequence",((Long)row[1]).intValue(),"basisPortfolioSnapshotId",row[2],
                "basisSourceSnapshotId",basisSource[0],"sourceSnapshotId",row[3]));
        // Four times under their own names; tc and the cash-flow verification are the same stored instant.
        assertThat(nested(fields,"times")).isEqualTo(java.util.Map.of("t0",row[4],"tc",row[5],"measuredAt",row[6],
                "cashflowVerifiedThrough",row[7]));
        var inputs=nested(fields,"inputs");
        assertThat(inputs.keySet()).containsExactly("connection","sourceRange","classification","conversion","cashflows",
                "plan");
        assertThat(nested(inputs,"connection")).isEqualTo(java.util.Map.of("provider","BYBIT","connectionId",row[8],
                "policyVersion",basisSource[1]));
        var candidate=jdbc().sql("SELECT b.policy_version,b.completeness_hash,min(e.range_start),max(e.range_end) "
                + "FROM sync_initial_candidate_bundle b JOIN sync_initial_page_evidence e ON e.job_id=b.job_id "
                + "WHERE b.candidate_id=:c GROUP BY b.policy_version,b.completeness_hash").param("c",row[10])
                .query((rs,n)->new String[] {rs.getString(1),rs.getString(2),
                        rs.getObject(3,java.time.OffsetDateTime.class).toInstant().toString(),
                        rs.getObject(4,java.time.OffsetDateTime.class).toInstant().toString()}).single();
        // A first publication's own windows started at t0 (Story 2.11 names that start separately).
        assertThat(nested(inputs,"sourceRange")).isEqualTo(java.util.Map.of("rangeStart",candidate[2],"rangeEnd",candidate[3],
                "windowStart",candidate[2],"collectionPolicy",candidate[0],"completenessHash",candidate[1]));
        // The collection covered exactly (t0, tc]: its pages start at t0 and end at tc.
        assertThat(candidate[2]).isEqualTo(row[4]);
        assertThat(candidate[3]).isEqualTo(row[5]);
        var classification=jdbc().sql("SELECT policy_version,input_scope_hash FROM sync_cashflow_classification_bundle "
                + "WHERE classification_id=:c::uuid").param("c",row[11])
                .query((rs,n)->new String[] {rs.getString(1),rs.getString(2)}).single();
        assertThat(classification[0]).isEqualTo(row[13]);
        assertThat(nested(inputs,"classification")).isEqualTo(java.util.Map.of("classificationId",row[11],
                "policyVersion",classification[0],"inputScopeHash",classification[1]));
        String fingerprint=jdbc().sql("SELECT outcome_fingerprint FROM sync_cashflow_conversion_bundle "
                + "WHERE conversion_id=:c::uuid").param("c",row[12]).query(String.class).single();
        assertThat(nested(inputs,"conversion")).isEqualTo(java.util.Map.of("conversionId",row[12],"pricePolicy",row[14],
                "resultFingerprint",fingerprint));
        assertThat(nested(inputs,"cashflows")).isEqualTo(java.util.Map.of("appliedCount",0,"estimatedCount",0));
        assertThat(nested(inputs,"plan")).isEqualTo(java.util.Map.of("planId",shown.get("planId"),
                "conditions",shown.get("conditions")));
        assertThat(nested(fields,"calculation")).isEqualTo(java.util.Map.of("calculationId",shown.get("calculationId"),
                "calculationPolicy","CALC-1.0","calendarPolicy","CALENDAR-SEOUL-1.0","displayPolicy","DISPLAY-1.0",
                "precisionPolicy","DECIMAL128-HALF_EVEN","timeZone","Asia/Seoul","implementationVersion",row[17],
                "inputHash",row[15],"resultHash",row[16],"createdAt",row[18]));
        @SuppressWarnings("unchecked")
        var metrics=(java.util.List<java.util.Map<String,Object>>)fields.get("metrics");
        assertThat(metrics).extracting(m -> m.get("metric")).containsExactly("CURRENT_VALUE","PLAN_AMOUNT",
                "PLAN_DIFFERENCE","ADJUSTED_TARGET","REQUIRED_RATE","GOAL_PROGRESS");
        assertThat(metrics).as("the character only, never the value").allSatisfy(m -> assertThat(m)
                .isEqualTo(java.util.Map.of("metric",m.get("metric"),"nature","OFFICIAL")));
        String publishedAt=jdbc().sql("SELECT published_at FROM sync_official_plan_result WHERE user_id=:u").param("u",user)
                .query(java.time.OffsetDateTime.class).single().toInstant().toString();
        // The one official version is current since its publication; it has no end (sent as null).
        var onlyVersion=new java.util.HashMap<String,Object>();
        onlyVersion.put("pointerVersion",1);
        onlyVersion.put("calculationId",shown.get("calculationId"));
        onlyVersion.put("publishedAt",publishedAt);
        onlyVersion.put("appliedUntil",null);
        onlyVersion.put("current",true);
        assertThat(nested(fields,"versions")).isEqualTo(java.util.Map.of(
                "official",java.util.List.of(onlyVersion),
                "unpublished",java.util.List.of()));

        // AC-8: nothing that names the account inside BokriLog or at the provider.
        String externalRef=jdbc().sql("SELECT provider_account_ref FROM source_provider_account WHERE user_id=:u")
                .param("u",user).query(String.class).single();
        assertThat(answer.body()).doesNotContain(user.toString()).doesNotContain((String)row[19])
                .doesNotContain(externalRef);
        assertThat(answer.body().toLowerCase(java.util.Locale.ROOT)).doesNotContain("userid").doesNotContain("provideraccount")
                .doesNotContain("secret").doesNotContain("apikey").doesNotContain("credential");

        var lines=evidenceLogLines(output);
        assertThat(lines.subList(before,lines.size())).singleElement().asString()
                .matches(".*calculation evidence read: state=FOUND status=200 latencyMs=\\d+ consistent=true$")
                .doesNotContain(user.toString()).doesNotContain((String)shown.get("calculationId"))
                .doesNotContain((String)shown.get("planId")).doesNotContain(row[15].toString()).doesNotContain("evidence-probe");
    }

    /**
     * UNPUBLISHED: the intent's attempts that ended without publishing — blocked by a restriction that came
     * while the worker waited, failed by an injected interruption — are listed apart from the one official
     * version, oldest first, with their status, reason and finish time only. Another intent's attempt is not.
     */
    @Test
    void attemptsThatNeverPublishedAreListedApartFromTheOfficialVersion() throws Exception {
        String subject="evidence-attempts-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"evidence-attempts","0.1",null);
        UUID blocked;
        AutoCloseable lock=holdPlanLock(user);
        try {
            blocked=publish(user,intent,"evidence-attempts-command-001");
            awaitBlockedOnPlanLock();
            jdbc().sql("INSERT INTO operations_restriction(restriction_id,command) VALUES (:id,'plan-publication')")
                    .param("id",UUID.randomUUID()).update();
        } finally {
            lock.close();
        }
        assertThat(awaitPublication(blocked)).isEqualTo("BLOCKED");
        jdbc().sql("TRUNCATE TABLE operations_restriction").update();
        jdbc().sql("CREATE FUNCTION fail_evidence_pointer() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.user_id='"+user+"'::uuid THEN RAISE EXCEPTION 'injected pointer failure'; END IF; "
                + "RETURN NEW; END $$").update();
        jdbc().sql("CREATE TRIGGER fail_evidence_pointer BEFORE INSERT ON sync_official_plan_result "
                + "FOR EACH ROW EXECUTE FUNCTION fail_evidence_pointer()").update();
        UUID failed;
        try {
            failed=publish(user,intent,"evidence-attempts-command-002");
            assertThat(awaitPublication(failed)).isEqualTo("FAILED");
        } finally {
            jdbc().sql("DROP TRIGGER fail_evidence_pointer ON sync_official_plan_result").update();
            jdbc().sql("DROP FUNCTION fail_evidence_pointer()").update();
        }
        assertThat(awaitPublication(publish(user,intent,"evidence-attempts-command-003"))).isEqualTo("PUBLISHED");
        // An attempt of another intent of the same user (a replaced one, say) belongs to another lineage.
        jdbc().sql("INSERT INTO sync_plan_publication_job(job_id,user_id,portfolio_id,intent_id,membership_id,connection_id,"
                + "provider_account_id,basis_portfolio_snapshot_id,basis_source_snapshot_id,expected_portfolio_snapshot_id,"
                + "expected_portfolio_sequence,candidate_id,classification_id,conversion_id,p0_decimal,t0,end_at,period,"
                + "target_rate,tc,current_value_decimal,current_value_measured_at,flow_count,input_scope_hash,"
                + "calculation_policy,calendar_policy,idempotency_key,status,failure_code,finished_at) SELECT :id,user_id,"
                + "portfolio_id,:other,membership_id,connection_id,provider_account_id,basis_portfolio_snapshot_id,"
                + "basis_source_snapshot_id,expected_portfolio_snapshot_id,expected_portfolio_sequence,candidate_id,"
                + "classification_id,conversion_id,p0_decimal,t0,end_at,period,target_rate,tc,current_value_decimal,"
                + "current_value_measured_at,flow_count,input_scope_hash,calculation_policy,calendar_policy,"
                + "'evidence-attempts-other-intent','FAILED','CALCULATION_INPUT_INVALID',now() "
                + "FROM sync_plan_publication_job WHERE job_id=:j").param("id",UUID.randomUUID())
                .param("other",UUID.randomUUID()).param("j",failed).update();

        var evidence=evidenceOf(user);
        var endings=jdbc().sql("SELECT finished_at FROM sync_plan_publication_job WHERE job_id IN (:b,:f) ORDER BY created_at")
                .param("b",blocked).param("f",failed)
                .query((rs,n)->rs.getObject(1,java.time.OffsetDateTime.class).toInstant()).list();
        assertThat(evidence.unpublishedAttempts()).containsExactly(
                new SyncJobApi.CalculationEvidence.UnpublishedAttempt("BLOCKED","NOT_PERMITTED",endings.get(0)),
                new SyncJobApi.CalculationEvidence.UnpublishedAttempt("FAILED","PUBLICATION_INTERRUPTED",endings.get(1)));
        assertThat(evidence.officialVersions()).singleElement().satisfies(version -> {
            assertThat(version.current()).isTrue();
            assertThat(version.pointerVersion()).isEqualTo(1);
            assertThat(version.calculationId()).isEqualTo(evidence.calculationId());
        });

        // Over HTTP: two separate lists, and an unpublished row names no attempt, plan, calculation or input.
        var shown=resultOf(get(AuthSecurityConfig.DASHBOARD_PATH,cookie).body());
        var body=topLevelFieldsOf(get(DashboardController.EVIDENCE_PATH+query(shown),cookie).body());
        var versions=nested(body,"versions");
        assertThat(versions.keySet()).containsExactly("official","unpublished");
        @SuppressWarnings("unchecked")
        var unpublished=(java.util.List<java.util.Map<String,Object>>)versions.get("unpublished");
        assertThat(unpublished).hasSize(2).allSatisfy(attempt ->
                assertThat(attempt.keySet()).containsExactly("status","reasonCode","finishedAt"));
        assertThat(unpublished).extracting(attempt -> attempt.get("status")).containsExactly("BLOCKED","FAILED");
    }

    /**
     * TAMPER (AC-1): only the three values of the user's current pointer answer. Another user's pointer, a
     * mix of the two, a version that is not current, unknown ids, malformed, repeated or missing values, and
     * a user with no plan at all answer one 404 — to the letter the body of a route that does not exist.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aTamperedEvidenceReferenceRevealsNothing(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        String subject="evidence-tamper-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"evidence-tamper","0.1",null);
        assertThat(awaitPublication(publish(user,intent,"evidence-tamper-command-001"))).isEqualTo("PUBLISHED");
        UUID other=connect(DevBybitStub.Scenario.UTA2);
        UUID otherIntent=convertedIntent(other,"evidence-tamper-other","0.2",null);
        assertThat(awaitPublication(publish(other,otherIntent,"evidence-tamper-other-001"))).isEqualTo("PUBLISHED");
        String emptySubject="evidence-tamper-empty-"+UUID.randomUUID();
        String emptyCookie=sessionCookieOf(signIn(emptySubject)).orElseThrow();
        var own=ready(user);
        var foreign=ready(other);
        String plan=own.planId().toString();
        String calc=own.calculationId().toString();
        String valid=query(own.planId(),own.pointerVersion(),own.calculationId());
        assertThat(get(DashboardController.EVIDENCE_PATH+valid,cookie).statusCode()).isEqualTo(200);
        assertThat(get(DashboardController.EVIDENCE_PATH+query(plan.toUpperCase(java.util.Locale.ROOT),"1",
                calc.toUpperCase(java.util.Locale.ROOT)),cookie).statusCode()).as("the same ids in capitals").isEqualTo(200);

        String notFound=send("GET",AuthSecurityConfig.DASHBOARD_PATH+"/no-such-route",cookie,"X-Request-Id",
                "evidence-tamper-probe").body();
        assertThat(topLevelFieldsOf(notFound)).containsEntry("code","NOT_FOUND");
        int before=evidenceLogLines(output).size();
        var tampered=java.util.List.of(
                query(foreign.planId(),foreign.pointerVersion(),foreign.calculationId()),
                query(own.planId(),1,foreign.calculationId()),
                query(foreign.planId(),1,own.calculationId()),
                query(own.planId(),2,own.calculationId()),
                query(UUID.randomUUID(),1,UUID.randomUUID()),
                query("not-a-uuid","1",calc),
                query(plan,"01",calc),
                query(plan,"0",calc),
                query(plan,"-1",calc),
                query(plan,"1.0",calc),
                query(plan,"1e0",calc),
                query(plan,"%201",calc),
                query(plan,"99999999999999999999",calc),
                query(plan,"1","1-1-1-1-1"),
                query(plan,"1",calc+"0"),
                query("","",""),
                "?pointerVersion=1&calculationId="+calc,
                "?planId="+plan+"&calculationId="+calc,
                "?planId="+plan+"&pointerVersion=1",
                "",
                valid+"&planId="+plan,
                valid+"&pointerVersion=1");
        for (String probe : tampered) {
            var answer=send("GET",DashboardController.EVIDENCE_PATH+probe,cookie,"X-Request-Id","evidence-tamper-probe");
            assertThat(answer.statusCode()).as(probe).isEqualTo(404);
            assertThat(answer.headers().firstValue("Cache-Control").orElse("")).as(probe).contains("no-store");
            assertThat(answer.body()).as(probe+" answers like a missing route").isEqualTo(notFound);
        }
        // A user without any plan, holding a real reference of someone else's current pointer.
        var empty=send("GET",DashboardController.EVIDENCE_PATH+valid,emptyCookie,"X-Request-Id","evidence-tamper-probe");
        assertThat(empty.statusCode()).isEqualTo(404);
        assertThat(empty.body()).isEqualTo(notFound);
        // A version that is no longer current: the pointer moved on, the old reference is stale. (Story 2.11's real
        // next version is IncrementalSyncTest's; here the pointer is only moved, so its history does not agree with
        // it any more and the moved pointer's own lineage is unavailable rather than found.)
        jdbc().sql("UPDATE sync_official_plan_result SET pointer_version=2 WHERE plan_id=:p").param("p",own.planId())
                .update();
        try {
            var stale=send("GET",DashboardController.EVIDENCE_PATH+valid,cookie,"X-Request-Id","evidence-tamper-probe");
            assertThat(stale.statusCode()).isEqualTo(404);
            assertThat(stale.body()).isEqualTo(notFound);
            assertThat(get(DashboardController.EVIDENCE_PATH+query(own.planId(),2,own.calculationId()),cookie).statusCode())
                    .as("a pointer its history does not know").isEqualTo(503);
        } finally {
            jdbc().sql("UPDATE sync_official_plan_result SET pointer_version=1 WHERE plan_id=:p").param("p",own.planId())
                    .update();
        }
        var lines=evidenceLogLines(output).subList(before,evidenceLogLines(output).size());
        assertThat(lines.stream().filter(line -> line.contains("status=404")).toList()).hasSize(tampered.size()+2)
                .allSatisfy(line -> assertThat(line).contains("state=NOT_FOUND status=404").endsWith("consistent=unknown"));
        assertThat(get(DashboardController.EVIDENCE_PATH+valid,null).statusCode()).as("unauthenticated").isEqualTo(401);
        // The other user's evidence is untouched and still their own.
        assertThat(evidenceOf(other).planId()).isEqualTo(foreign.planId());
    }

    /**
     * BROKEN (AC-6): when the current pointer's lineage disagrees with itself — a missing row, a policy or a
     * link that is not the version's — the answer is 503 {@code EVIDENCE_UNAVAILABLE} with no partial lineage,
     * and the line says {@code consistent=false}. A break that only the evidence reads does not refuse the
     * dashboard. Each corruption is undone before the next, and the restored lineage is whole again.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aBrokenLineageReturnsNoPartialEvidenceAndLogsConsistentFalse(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String subject="evidence-broken-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        // One directly normalized flow, so the applied history has a row to re-point.
        UUID intent=convertedIntent(user,"evidence-broken","0.1",base.minusSeconds(3600),
                new Flow("evidence-broken-in","USDT","100",base.plusSeconds(21)));
        UUID job=publish(user,intent,"evidence-broken-command-001");
        assertThat(awaitPublication(job)).isEqualTo("PUBLISHED");
        var own=ready(user);
        var reference=referenceOf(own);
        String valid=query(own.planId(),own.pointerVersion(),own.calculationId());
        var ids=jdbc().sql("SELECT classification_policy_version,price_policy_version,source_snapshot_id,candidate_id,"
                + "classification_id,conversion_id,basis_source_snapshot_id FROM sync_plan_calculation "
                + "WHERE calculation_id=:c")
                .param("c",own.calculationId()).query((rs,n)->new Object[] {rs.getString(1),rs.getString(2),
                        rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),
                        rs.getObject(6,UUID.class),rs.getObject(7,UUID.class)}).single();
        String classificationPolicy=(String)ids[0], pricePolicy=(String)ids[1];
        UUID source=(UUID)ids[2], candidate=(UUID)ids[3], classification=(UUID)ids[4], conversion=(UUID)ids[5];
        UUID basisSource=(UUID)ids[6];
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_flow WHERE calculation_id=:c")
                .param("c",own.calculationId()).query(Integer.class).single()).as("one applied flow").isEqualTo(1);
        // The collection's earliest pages (one per event type) — the ones whose start is t0.
        var firstPages=jdbc().sql("SELECT e.evidence_id FROM sync_initial_page_evidence e JOIN sync_initial_candidate_bundle b "
                + "ON b.job_id=e.job_id WHERE b.candidate_id=:c AND e.range_start=(SELECT min(x.range_start) FROM "
                + "sync_initial_page_evidence x WHERE x.job_id=b.job_id)").param("c",candidate).query(UUID.class).list();
        assertThat(firstPages).isNotEmpty();
        var goal=jdbc().sql("SELECT value_decimal,nature FROM sync_plan_calculation_metric WHERE calculation_id=:c "
                + "AND metric='GOAL_PROGRESS'").param("c",own.calculationId())
                .query((rs,n)->new String[] {rs.getString(1),rs.getString(2)}).single();
        record Corruption(String name,boolean dashboardToo,Runnable corrupt,Runnable restore) {}
        java.util.function.Consumer<String> replica=sql -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc().sql("SET LOCAL session_replication_role = replica").update();
            jdbc().sql(sql).update();
        });
        UUID stranger=UUID.randomUUID();
        var corruptions=java.util.List.of(
                new Corruption("the version's classification policy is not its classification's",false,
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET classification_policy_version='BYBIT-CASHFLOW-9.9' "
                                + "WHERE calculation_id=:c").param("c",own.calculationId()).update(),
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET classification_policy_version=:v "
                                + "WHERE calculation_id=:c").param("v",classificationPolicy).param("c",own.calculationId())
                                .update()),
                new Corruption("the version's price policy is not its conversion's",false,
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET price_policy_version='BYBIT-PRICE-9.9' "
                                + "WHERE calculation_id=:c").param("c",own.calculationId()).update(),
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET price_policy_version=:v WHERE calculation_id=:c")
                                .param("v",pricePolicy).param("c",own.calculationId()).update()),
                new Corruption("the applied cash-flow history does not add up",false,
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET flow_count=flow_count+1 WHERE calculation_id=:c")
                                .param("c",own.calculationId()).update(),
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET flow_count=flow_count-1 WHERE calculation_id=:c")
                                .param("c",own.calculationId()).update()),
                new Corruption("the tc source snapshot was measured at another time",false,
                        () -> jdbc().sql("UPDATE sync_source_snapshot SET measured_at=measured_at+interval '1 second' "
                                + "WHERE source_snapshot_id=:s").param("s",source).update(),
                        () -> jdbc().sql("UPDATE sync_source_snapshot SET measured_at=measured_at-interval '1 second' "
                                + "WHERE source_snapshot_id=:s").param("s",source).update()),
                new Corruption("the creating attempt was for another intent",false,
                        () -> jdbc().sql("UPDATE sync_plan_publication_job SET intent_id=:x WHERE job_id=:j")
                                .param("x",stranger).param("j",job).update(),
                        () -> jdbc().sql("UPDATE sync_plan_publication_job SET intent_id=:i WHERE job_id=:j")
                                .param("i",intent).param("j",job).update()),
                new Corruption("the candidate is another intent's",false,
                        () -> jdbc().sql("UPDATE sync_initial_candidate_bundle SET intent_id=:x WHERE candidate_id=:c")
                                .param("x",stranger).param("c",candidate).update(),
                        () -> jdbc().sql("UPDATE sync_initial_candidate_bundle SET intent_id=:i WHERE candidate_id=:c")
                                .param("i",intent).param("c",candidate).update()),
                new Corruption("the conversion is another intent's",false,
                        () -> jdbc().sql("UPDATE sync_cashflow_conversion_bundle SET intent_id=:x WHERE conversion_id=:c")
                                .param("x",stranger).param("c",conversion).update(),
                        () -> jdbc().sql("UPDATE sync_cashflow_conversion_bundle SET intent_id=:i WHERE conversion_id=:c")
                                .param("i",intent).param("c",conversion).update()),
                new Corruption("the basis source snapshot was measured after t0",false,
                        () -> jdbc().sql("UPDATE sync_source_snapshot SET measured_at=measured_at+interval '1 second' "
                                + "WHERE source_snapshot_id=:s").param("s",basisSource).update(),
                        () -> jdbc().sql("UPDATE sync_source_snapshot SET measured_at=measured_at-interval '1 second' "
                                + "WHERE source_snapshot_id=:s").param("s",basisSource).update()),
                new Corruption("the collection started after t0",false,
                        () -> jdbc().sql("UPDATE sync_initial_page_evidence SET range_start=range_start+interval '1 second' "
                                + "WHERE evidence_id IN (:e)").param("e",firstPages).update(),
                        () -> jdbc().sql("UPDATE sync_initial_page_evidence SET range_start=range_start-interval '1 second' "
                                + "WHERE evidence_id IN (:e)").param("e",firstPages).update()),
                new Corruption("the creating attempt expected another pointer sequence",false,
                        () -> jdbc().sql("UPDATE sync_plan_publication_job SET expected_portfolio_sequence="
                                + "expected_portfolio_sequence+1 WHERE job_id=:j").param("j",job).update(),
                        () -> jdbc().sql("UPDATE sync_plan_publication_job SET expected_portfolio_sequence="
                                + "expected_portfolio_sequence-1 WHERE job_id=:j").param("j",job).update()),
                // The foreign keys make these impossible; a replica session skips them to build one.
                new Corruption("the collection left no page evidence of this user",false,
                        () -> replica.accept("UPDATE sync_initial_page_evidence SET user_id='"+stranger+"' WHERE job_id="
                                + "(SELECT job_id FROM sync_initial_candidate_bundle WHERE candidate_id='"+candidate+"')"),
                        () -> replica.accept("UPDATE sync_initial_page_evidence SET user_id='"+user+"' WHERE job_id="
                                + "(SELECT job_id FROM sync_initial_candidate_bundle WHERE candidate_id='"+candidate+"')")),
                new Corruption("the classification is another candidate's",false,
                        () -> replica.accept("UPDATE sync_cashflow_classification_bundle SET candidate_id='"+stranger
                                + "' WHERE classification_id='"+classification+"'"),
                        () -> replica.accept("UPDATE sync_cashflow_classification_bundle SET candidate_id='"+candidate
                                + "' WHERE classification_id='"+classification+"'")),
                new Corruption("an applied flow cites another conversion",false,
                        () -> replica.accept("UPDATE sync_plan_calculation_flow SET conversion_id='"+stranger
                                + "' WHERE calculation_id='"+own.calculationId()+"'"),
                        () -> replica.accept("UPDATE sync_plan_calculation_flow SET conversion_id='"+conversion
                                + "' WHERE calculation_id='"+own.calculationId()+"'")),
                new Corruption("the version's conversion does not exist",false,
                        () -> replica.accept("UPDATE sync_plan_calculation SET conversion_id='"+stranger
                                + "' WHERE calculation_id='"+own.calculationId()+"'"),
                        () -> replica.accept("UPDATE sync_plan_calculation SET conversion_id='"+conversion
                                + "' WHERE calculation_id='"+own.calculationId()+"'")),
                new Corruption("a metric is missing",true,
                        () -> jdbc().sql("DELETE FROM sync_plan_calculation_metric WHERE calculation_id=:c "
                                + "AND metric='GOAL_PROGRESS'").param("c",own.calculationId()).update(),
                        () -> jdbc().sql("INSERT INTO sync_plan_calculation_metric(calculation_id,metric,value_decimal,nature) "
                                + "VALUES (:c,'GOAL_PROGRESS',:v,:n)").param("c",own.calculationId()).param("v",goal[0])
                                .param("n",goal[1]).update()));
        int before=evidenceLogLines(output).size();
        for (Corruption corruption : corruptions) {
            corruption.corrupt().run();
            var refused=send("GET",DashboardController.EVIDENCE_PATH+valid,cookie,"X-Request-Id","evidence-broken");
            assertThat(refused.statusCode()).as(corruption.name()).isEqualTo(503);
            assertThat(refused.headers().firstValue("Cache-Control").orElse("")).as(corruption.name()).contains("no-store");
            assertThat(topLevelFieldsOf(refused.body())).as(corruption.name())
                    .containsOnlyKeys("code","message","correlationId").containsEntry("code","EVIDENCE_UNAVAILABLE")
                    .containsEntry("correlationId","evidence-broken");
            assertThat(refused.body()).as(corruption.name()).doesNotContain(own.calculationId().toString())
                    .doesNotContain(own.planId().toString()).doesNotContain(own.inputHash());
            assertThat(sync.calculationEvidence(user,reference)).as(corruption.name()).isEqualTo(new SyncJobApi.EvidenceRead(
                    new SyncJobApi.EvidenceOutcome.Unavailable(),SyncJobApi.BundleConsistency.INCONSISTENT));
            if (corruption.dashboardToo()) {
                assertThat(dashboardOf(user)).as(corruption.name()).isInstanceOf(SyncJobApi.Dashboard.Unavailable.class);
            } else {
                assertThat(dashboardOf(user)).as("the dashboard itself: "+corruption.name())
                        .isInstanceOf(SyncJobApi.Dashboard.Ready.class);
            }
            corruption.restore().run();
            assertThat(sync.calculationEvidence(user,reference).outcome()).as("restored: "+corruption.name())
                    .isInstanceOf(SyncJobApi.EvidenceOutcome.Found.class);
        }
        var lines=evidenceLogLines(output);
        assertThat(lines.subList(before,lines.size())).hasSize(corruptions.size())
                .allSatisfy(line -> assertThat(line).contains("state=INCONSISTENT status=503").endsWith("consistent=false"));
    }

    /**
     * DELETING (AC-8): the account's own access is decided before and after the read. A deletion that begins
     * while the lineage is read wins; in progress or finished, nothing comes back, and another user's evidence
     * stays.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aDeletionOnEitherSideOfTheReadRefusesTheEvidence(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        String subject="evidence-deletion-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"evidence-deletion","0.1",null);
        assertThat(awaitPublication(publish(user,intent,"evidence-deletion-command-001"))).isEqualTo("PUBLISHED");
        UUID kept=connect(DevBybitStub.Scenario.UTA2);
        UUID keptIntent=convertedIntent(kept,"evidence-deletion-kept","0.1",null);
        assertThat(awaitPublication(publish(kept,keptIntent,"evidence-deletion-kept-001"))).isEqualTo("PUBLISHED");
        var own=ready(user);
        String valid=query(own.planId(),own.pointerVersion(),own.calculationId());

        // After: the read is past its first check and holds its snapshot when the deletion commits.
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>> pending;
        AutoCloseable lock=holdTableLock("sync_cashflow_classification_bundle");
        try {
            pending=java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return get(DashboardController.EVIDENCE_PATH+valid,cookie);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            // Only the evidence read selects these two columns together from that table.
            awaitBlockedOn("input_scope_hash FROM sync_cashflow_classification_bundle");
            accountDeletion().beginDeletion(user);
        } finally {
            lock.close();
        }
        var late=pending.get(20,TimeUnit.SECONDS);
        assertThat(late.statusCode()).as("the deletion that began during the read wins").isEqualTo(403);
        assertThat(late.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(late.body())).containsOnlyKeys("code","message","correlationId")
                .containsEntry("code","FORBIDDEN");
        assertThat(late.body()).doesNotContain(own.calculationId().toString()).doesNotContain(own.inputHash());
        assertThat(evidenceLogLines(output).getLast()).contains("state=DENIED status=403").endsWith("consistent=true");

        // Before: in progress. Nothing was read, so nothing is known about the lineage.
        var before=get(DashboardController.EVIDENCE_PATH+valid,cookie);
        assertThat(before.statusCode()).isEqualTo(403);
        assertThat(evidenceLogLines(output).getLast()).contains("state=DENIED status=403").endsWith("consistent=unknown");
        assertThat(get(DashboardController.EVIDENCE_PATH+"?planId=malformed",cookie).statusCode())
                .as("a malformed reference is not a way around the refusal").isEqualTo(403);
        assertThat(sync.calculationEvidence(user,referenceOf(own)).outcome())
                .isInstanceOf(SyncJobApi.EvidenceOutcome.NotPermitted.class);

        // Finished: the erasure ran in its order; the session went with it.
        portfolio.erasePortfolioData(user);
        sync.eraseSyncData(user);
        plans.erasePlanData(user);
        source.eraseSourceData(user);
        assertThat(accountDeletion().erasePayload(user)).isEqualTo(com.bokrilog.identity.DeletionOutcome.COMPLETED);
        assertThat(sync.calculationEvidence(user,referenceOf(own)).outcome()).as("DELETED")
                .isInstanceOf(SyncJobApi.EvidenceOutcome.NotPermitted.class);
        assertThat(get(DashboardController.EVIDENCE_PATH+valid,cookie).statusCode()).isEqualTo(401);

        assertThat(evidenceOf(kept).planId()).isEqualTo(ready(kept).planId());
    }

    /**
     * A read that throws answers the common 500 with nothing of the lineage, and the one line for it says
     * {@code ERROR} with the status answered and {@code consistent=unknown}. A table only the evidence reads
     * is hidden for one request, so the read fails inside the lineage — and the dashboard, which does not
     * read it, still answers.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void anEvidenceReadThatFailsAnswersTheCommonErrorAndLogsOneErrorLine(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String subject="evidence-error-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"evidence-error","0.1",null);
        assertThat(awaitPublication(publish(user,intent,"evidence-error-command-001"))).isEqualTo("PUBLISHED");
        var own=ready(user);
        String valid=query(own.planId(),own.pointerVersion(),own.calculationId());

        int before=evidenceLogLines(output).size();
        java.net.http.HttpResponse<String> failed;
        java.net.http.HttpResponse<String> dashboard;
        jdbc().sql("ALTER TABLE sync_cashflow_classification_bundle RENAME TO sync_cashflow_classification_bundle_hidden")
                .update();
        try {
            failed=send("GET",DashboardController.EVIDENCE_PATH+valid,cookie,"X-Request-Id","evidence-error-probe");
            dashboard=get(AuthSecurityConfig.DASHBOARD_PATH,cookie);
        } finally {
            jdbc().sql("ALTER TABLE sync_cashflow_classification_bundle_hidden RENAME TO sync_cashflow_classification_bundle")
                    .update();
        }
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(failed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(failed.body())).containsOnlyKeys("code","message","correlationId")
                .containsEntry("code","INTERNAL_ERROR").containsEntry("correlationId","evidence-error-probe");
        assertThat(failed.body()).doesNotContain(own.calculationId().toString()).doesNotContain(own.inputHash())
                .doesNotContain("sync_cashflow_classification_bundle");
        assertThat(dashboard.statusCode()).as("the dashboard does not read that table").isEqualTo(200);
        var lines=evidenceLogLines(output);
        assertThat(lines.subList(before,lines.size())).singleElement().asString()
                .matches(".*calculation evidence read: state=ERROR status=500 latencyMs=\\d+ consistent=unknown$");
        assertThat(output.getAll()).as("no SQL or relation name reaches the log")
                .doesNotContain("sync_cashflow_classification_bundle_hidden");
        // The next read is whole again.
        assertThat(get(DashboardController.EVIDENCE_PATH+valid,cookie).statusCode()).isEqualTo(200);
    }

    /**
     * A query the container cannot parse — an invalid percent escape, or more parameters than it accepts —
     * is a malformed reference like any other: the same 404 as a missing route, after the account's own
     * access, so a deleting account still hears 403. Never a 500. Sent over a raw socket: the HTTP client
     * refuses to build such a URI at all.
     */
    @Test
    void anUnparsableQueryIsTheSame404AndNeverA500() throws Exception {
        String subject="evidence-unparsable-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"evidence-unparsable","0.1",null);
        assertThat(awaitPublication(publish(user,intent,"evidence-unparsable-command-001"))).isEqualTo("PUBLISHED");
        var own=ready(user);
        String valid=query(own.planId(),own.pointerVersion(),own.calculationId());
        String notFound=send("GET",AuthSecurityConfig.DASHBOARD_PATH+"/no-such-route",cookie,"X-Request-Id",
                "evidence-unparsable-probe").body();
        var tooMany=new StringBuilder(valid);
        for (int i=0;i<1100;i++) tooMany.append("&x=").append(i);
        var probes=java.util.List.of(
                "?planId=%zz&pointerVersion=1&calculationId="+own.calculationId(),
                valid+"&junk=%",
                valid+"&junk=%G1",
                tooMany.toString());
        for (String probe : probes) {
            String[] answer=rawGet(DashboardController.EVIDENCE_PATH+probe,cookie,"evidence-unparsable-probe");
            assertThat(answer[0]).as(shortened(probe)).isEqualTo("404");
            assertThat(answer[1]).as(shortened(probe)).isEqualTo(notFound);
        }
        accountDeletion().beginDeletion(user);
        for (String probe : probes) {
            String[] answer=rawGet(DashboardController.EVIDENCE_PATH+probe,cookie,"evidence-unparsable-probe");
            assertThat(answer[0]).as("deleting: "+shortened(probe)).isEqualTo("403");
            assertThat(topLevelFieldsOf(answer[1])).containsEntry("code","FORBIDDEN");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Story 2.10 adjustment history
    // ------------------------------------------------------------------------------------------

    /**
     * CURRENT / PAGED / SAME_TIME / FILTER / EXCLUDED: the version's applied flows come in its own order, twenty
     * a page, and the next page continues exactly where the first stopped — two flows of one instant sit on the
     * page boundary and keep their source-id order. Each row carries its event, classification, price (legs
     * included) and references as stored; the summary covers the whole filtered range, summed exactly; the
     * excluded events are counted apart and never listed. Nothing internal to the account is in the answer, and
     * each read writes one operational line with none of it either.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void theAdjustmentHistoryPagesTheAppliedFlowsInTheVersionsOrderWithTheirLineage(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String subject="adjustments-current-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        var flows=new java.util.ArrayList<Flow>();
        var usd=new java.util.LinkedHashMap<String,java.math.BigDecimal>();
        for (int i=1;i<=25;i++) {
            String id=String.format(java.util.Locale.ROOT,"adj-current-%02d",i);
            // Flows 20 and 21 share one instant, and the first page ends between them.
            Instant at=base.plusSeconds(i==21 ? 20 : i);
            Flow flow=i==5 ? new Flow(id,"BTC","0.01",at) : i%4==0 ? new Flow(id,"USDT","-1.25",at)
                    : new Flow(id,"USDT",i<=3 ? "0.005" : "10.25",at);
            flows.add(flow);
            usd.put(id,i==5 ? new java.math.BigDecimal("650.0025") : new java.math.BigDecimal(flow.cashFlow()));
        }
        UUID intent=convertedIntentWithExcluded(user,"adjustments-current",base.minusSeconds(3600),
                java.util.List.of(new Excluded("adj-current-fee","FEE","USDT","-0.1",base.plusSeconds(30)),
                        new Excluded("adj-current-trade","TRADE","USDT","5",base.plusSeconds(31))),
                flows.toArray(Flow[]::new));
        assertThat(awaitPublication(publish(user,intent,"adjustments-current-command-001"))).isEqualTo("PUBLISHED");
        var shown=ready(user);
        var version=jdbc().sql("SELECT classification_id,conversion_id,t0,cashflow_verified_through "
                + "FROM sync_plan_calculation WHERE calculation_id=:c").param("c",shown.calculationId())
                .query((rs,n)->new Object[] {rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getObject(3,java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject(4,java.time.OffsetDateTime.class).toInstant()}).single();
        UUID classification=(UUID)version[0], conversion=(UUID)version[1];
        Instant publishedAt=jdbc().sql("SELECT published_at FROM sync_official_plan_result WHERE plan_id=:p")
                .param("p",shown.planId()).query(java.time.OffsetDateTime.class).single().toInstant();

        var first=historyOf(user,SyncJobApi.AdjustmentDirection.ALL,null);
        assertThat(first.planId()).isEqualTo(shown.planId());
        assertThat(first.pointerVersion()).isEqualTo(shown.pointerVersion());
        assertThat(first.calculationId()).isEqualTo(shown.calculationId());
        assertThat(first.direction()).isEqualTo(SyncJobApi.AdjustmentDirection.ALL);
        assertThat(first.appliedAt()).as("when the version became official").isEqualTo(publishedAt);
        assertThat(first.coverage()).isEqualTo(new SyncJobApi.AdjustmentHistory.Coverage((Instant)version[2],
                (Instant)version[3]));
        // The whole range, exactly; the two excluded events apart from it.
        assertThat(first.summary()).isEqualTo(new SyncJobApi.AdjustmentHistory.Summary(25,exactSum(usd.values()),2));
        assertThat(first.rows()).extracting(SyncJobApi.AdjustmentHistory.Adjustment::sequence)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,20).boxed().toList());
        assertThat(first.nextCursor()).isNotNull();

        var second=historyOf(user,SyncJobApi.AdjustmentDirection.ALL,first.nextCursor());
        assertThat(second.rows()).extracting(SyncJobApi.AdjustmentHistory.Adjustment::sequence)
                .containsExactly(21,22,23,24,25);
        assertThat(second.nextCursor()).as("the last page").isNull();
        assertThat(second.summary()).as("every page carries the whole range's summary").isEqualTo(first.summary());
        // SAME_TIME across the page boundary: one instant, source-id order.
        assertThat(second.rows().getFirst().occurredAt()).isEqualTo(first.rows().getLast().occurredAt());
        assertThat(first.rows().getLast().references().sourceId()).isEqualTo("adj-current-20");
        assertThat(second.rows().getFirst().references().sourceId()).isEqualTo("adj-current-21");
        // No duplicate, no gap, and no excluded event, over both pages.
        var listed=java.util.stream.Stream.concat(first.rows().stream(),second.rows().stream())
                .map(row -> row.references().sourceId()).toList();
        assertThat(listed).containsExactlyElementsOf(usd.keySet());

        // One estimated deposit, with its leg, and one directly normalized withdrawal — each as stored.
        var btc=first.rows().get(4);
        assertThat(btc).isEqualTo(new SyncJobApi.AdjustmentHistory.Adjustment(5,base.plusSeconds(5),"IN","BTC","0.01",
                "650.0025",new SyncJobApi.AdjustmentHistory.Classification("TRANSFER_IN","EXTERNAL_TRANSFER_IN",
                        "BYBIT-CASHFLOW-1.0"),
                new SyncJobApi.AdjustmentHistory.Price("BYBIT-PRICE-1.0","SPOT_KLINE_CLOSE",true,"65000.25",
                        "SPOT_ASSET_USDT",java.util.List.of(new SyncJobApi.AdjustmentHistory.PriceLeg("BTCUSDT",base,
                                base.plusSeconds(60),"65000.25","EVENT_MINUTE",0))),
                firstObservedAt(user,"adj-current-05"),new SyncJobApi.AdjustmentHistory.References(
                        eventIdOf(user,"adj-current-05"),"adj-current-05",classification,conversion,shown.calculationId()),
                false,java.util.List.of(),null));
        var withdrawal=first.rows().get(3);
        assertThat(withdrawal).isEqualTo(new SyncJobApi.AdjustmentHistory.Adjustment(4,base.plusSeconds(4),"OUT","USDT",
                "-1.25","-1.25",new SyncJobApi.AdjustmentHistory.Classification("TRANSFER_OUT","EXTERNAL_TRANSFER_OUT",
                        "BYBIT-CASHFLOW-1.0"),
                new SyncJobApi.AdjustmentHistory.Price("BYBIT-PRICE-1.0","DIRECT_NORMALIZATION",false,"1","DIRECT",
                        java.util.List.of()),
                firstObservedAt(user,"adj-current-04"),new SyncJobApi.AdjustmentHistory.References(
                        eventIdOf(user,"adj-current-04"),"adj-current-04",classification,conversion,shown.calculationId()),
                false,java.util.List.of(),null));
        assertThat(first.rows().getFirst().usdValue()).as("unrounded").isEqualTo("0.005");

        // FILTER: the direction's rows and the direction's own exact summary.
        var inbound=historyOf(user,SyncJobApi.AdjustmentDirection.IN,null);
        assertThat(inbound.summary()).isEqualTo(new SyncJobApi.AdjustmentHistory.Summary(19,exactSum(usd.values()
                .stream().filter(value -> value.signum()>0).toList()),2));
        assertThat(inbound.rows()).hasSize(19).allSatisfy(row -> assertThat(row.direction()).isEqualTo("IN"));
        assertThat(inbound.nextCursor()).isNull();
        var outbound=historyOf(user,SyncJobApi.AdjustmentDirection.OUT,null);
        assertThat(outbound.summary()).isEqualTo(new SyncJobApi.AdjustmentHistory.Summary(6,"-7.5",2));
        assertThat(outbound.rows()).extracting(SyncJobApi.AdjustmentHistory.Adjustment::sequence)
                .containsExactly(4,8,12,16,20,24);
        // A cursor made for one filter names nothing under another.
        assertThat(sync.adjustmentHistory(user,adjustmentQuery(shown,SyncJobApi.AdjustmentDirection.IN,
                first.nextCursor()))).isEqualTo(new SyncJobApi.AdjustmentRead(new SyncJobApi.AdjustmentOutcome.NotFound(),
                        SyncJobApi.BundleConsistency.NOT_READ));

        // Over HTTP: the same page, the documented order, no-store, and nothing internal to the account.
        int before=adjustmentLogLines(output).size();
        var answer=send("GET",adjustmentsPath(shown,""),cookie,"X-Request-Id","adjustments-probe");
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var fields=topLevelFieldsOf(answer.body());
        assertThat(fields.keySet()).containsExactly("planId","pointerVersion","calculationId","direction","appliedAt",
                "coverage","summary","rows","nextCursor");
        assertThat(fields).containsEntry("direction","ALL").containsEntry("nextCursor",first.nextCursor())
                .containsEntry("appliedAt",publishedAt.toString());
        assertThat(nested(fields,"summary")).isEqualTo(java.util.Map.of("count",25,"netUsdValue",
                exactSum(usd.values()),"excludedCount",2));
        @SuppressWarnings("unchecked")
        var rows=(java.util.List<java.util.Map<String,Object>>)fields.get("rows");
        assertThat(rows).hasSize(20);
        assertThat(rows.get(4).keySet()).as("the reading order").containsExactly("sequence","occurredAt","direction",
                "asset","signedQuantity","usdValue","classification","price","discoveredAt","refs","changed","revisions",
                "versionChange");
        assertThat(rows.get(4)).as("a first publication marks no row").containsEntry("versionChange",null);
        assertThat(rows.get(4)).containsEntry("signedQuantity","0.01").containsEntry("usdValue","650.0025")
                .containsEntry("changed",false).containsEntry("revisions",java.util.List.of());
        // The nested contract the screen validates (`isLeg`): exactly these keys, in this order.
        var price=nested(rows.get(4),"price");
        assertThat(price.keySet()).containsExactly("policyVersion","evidenceType","estimated","unitPrice","routeId","legs");
        @SuppressWarnings("unchecked")
        var legs=(java.util.List<java.util.Map<String,Object>>)price.get("legs");
        assertThat(legs).singleElement().satisfies(leg -> {
            assertThat(leg.keySet()).containsExactly("symbol","candleStartAt","candleEndAt","closePrice","selectionRule",
                    "lagMinutes");
            assertThat(leg).isEqualTo(java.util.Map.of("symbol","BTCUSDT","candleStartAt",base.toString(),
                    "candleEndAt",base.plusSeconds(60).toString(),"closePrice","65000.25","selectionRule","EVENT_MINUTE",
                    "lagMinutes",0));
        });
        assertThat(nested(rows.get(4),"classification").keySet()).containsExactly("transactionType","ruleId",
                "policyVersion");
        assertThat(nested(rows.get(4),"refs").keySet()).containsExactly("sourceEventId","sourceId","classificationId",
                "conversionId","calculationId");
        var last=topLevelFieldsOf(get(adjustmentsPath(shown,"&cursor="+first.nextCursor()),cookie).body());
        assertThat(last).containsEntry("nextCursor",null);
        assertThat((java.util.List<?>)last.get("rows")).hasSize(5);
        var filtered=topLevelFieldsOf(get(adjustmentsPath(shown,"&direction=OUT"),cookie).body());
        assertThat(filtered).containsEntry("direction","OUT");
        assertThat(nested(filtered,"summary")).containsEntry("count",6).containsEntry("netUsdValue","-7.5");
        String externalRef=jdbc().sql("SELECT provider_account_ref FROM source_provider_account WHERE user_id=:u")
                .param("u",user).query(String.class).single();
        String providerAccount=jdbc().sql("SELECT provider_account_id::text FROM sync_plan_calculation WHERE user_id=:u")
                .param("u",user).query(String.class).single();
        for (String body : new String[] {answer.body(),topLevelFieldsOf(answer.body()).toString()}) {
            assertThat(body).doesNotContain(user.toString()).doesNotContain(providerAccount).doesNotContain(externalRef)
                    .doesNotContain("adj-current-fee").doesNotContain("adj-current-trade");
            assertThat(body.toLowerCase(java.util.Locale.ROOT)).doesNotContain("userid").doesNotContain("provideraccount")
                    .doesNotContain("secret").doesNotContain("apikey").doesNotContain("credential")
                    .doesNotContain("semantic").doesNotContain("cursorhash").doesNotContain("address")
                    .doesNotContain("txhash");
        }
        var lines=adjustmentLogLines(output);
        assertThat(lines.subList(before,lines.size())).hasSize(3).allSatisfy(line -> assertThat(line)
                .matches(".*adjustment history read: state=FOUND status=200 latencyMs=\\d+ consistent=true$")
                .doesNotContain(user.toString()).doesNotContain(shown.calculationId().toString())
                .doesNotContain(shown.planId().toString()).doesNotContain(first.nextCursor())
                .doesNotContain("adjustments-probe"));
    }

    /**
     * AC-4 exact totals and EMPTY: three deposits of 0.005 add up to 0.015 exactly — the sum of stored values,
     * never of display-rounded ones (each would show as 0.01). A version with no applied flow lists nothing,
     * with a zero summary and its verified range, under every filter.
     */
    @Test
    void anAdjustmentTotalIsTheExactSumOfTheStoredValuesAndAnEmptyHistorySaysSo() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID user=connect(DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"adjustments-exact","0.1",base.minusSeconds(3600),
                new Flow("exact-1","USDT","0.005",base.plusSeconds(3)),new Flow("exact-2","USDT","0.005",base.plusSeconds(4)),
                new Flow("exact-3","USDT","0.005",base.plusSeconds(5)));
        assertThat(awaitPublication(publish(user,intent,"adjustments-exact-command-001"))).isEqualTo("PUBLISHED");
        var history=historyOf(user,SyncJobApi.AdjustmentDirection.ALL,null);
        assertThat(history.summary()).isEqualTo(new SyncJobApi.AdjustmentHistory.Summary(3,"0.015",0));
        assertThat(history.rows()).extracting(SyncJobApi.AdjustmentHistory.Adjustment::usdValue)
                .containsExactly("0.005","0.005","0.005");
        assertThat(historyOf(user,SyncJobApi.AdjustmentDirection.OUT,null).summary())
                .isEqualTo(new SyncJobApi.AdjustmentHistory.Summary(0,"0",0));

        UUID empty=connect(DevBybitStub.Scenario.UTA2);
        UUID emptyIntent=convertedIntent(empty,"adjustments-empty","0.1",null);
        assertThat(awaitPublication(publish(empty,emptyIntent,"adjustments-empty-command-001"))).isEqualTo("PUBLISHED");
        var times=jdbc().sql("SELECT t0,cashflow_verified_through FROM sync_plan_calculation WHERE user_id=:u")
                .param("u",empty).query((rs,n)->new Instant[] {rs.getObject(1,java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject(2,java.time.OffsetDateTime.class).toInstant()}).single();
        for (var direction : SyncJobApi.AdjustmentDirection.values()) {
            var none=historyOf(empty,direction,null);
            assertThat(none.rows()).as(direction.name()).isEmpty();
            assertThat(none.nextCursor()).isNull();
            assertThat(none.summary()).isEqualTo(new SyncJobApi.AdjustmentHistory.Summary(0,"0",0));
            assertThat(none.coverage()).isEqualTo(new SyncJobApi.AdjustmentHistory.Coverage(times[0],times[1]));
        }
    }

    /**
     * REVISED (AC-6): the current conversion continues an earlier one after a price revision. The changed flow
     * is marked and lists the earlier version — its own processing time and status, the value it had, why and
     * when it was replaced — and that earlier version is kept exactly as it was stored. A flow whose value did
     * not change carries no marker and no list.
     */
    @Test
    void aConversionThatContinuesAnEarlierOneMarksTheChangedFlowAndListsTheEarlierVersion() throws Exception {
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        String subject="adjustments-revised-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        UUID intent=convertedIntent(user,"adjustments-revised","0.1",base.minusSeconds(3600),
                new Flow("revised-btc-in","BTC","0.01",base.plusSeconds(7)),
                new Flow("revised-usdt-in","USDT","40",base.plusSeconds(8)));
        UUID earlier=jdbc().sql("SELECT conversion_id FROM sync_cashflow_conversion_job WHERE user_id=:u")
                .param("u",user).query(UUID.class).single();
        jdbc().sql("INSERT INTO sync_market_candle(candle_id,provider,category,symbol,interval_code,start_at,end_at,"
                + "revision,close_price,evidence_hash,policy_version,observed_at,request_start_at,request_end_at) SELECT :id,"
                + "provider,category,symbol,interval_code,start_at,end_at,2,'65010',:hash,policy_version,observed_at,"
                + "request_start_at,request_end_at FROM sync_market_candle "
                + "WHERE symbol='BTCUSDT' AND start_at=:start AND revision=1")
            .param("id",UUID.randomUUID()).param("hash","e".repeat(64))
            .param("start",java.time.OffsetDateTime.ofInstant(base,java.time.ZoneOffset.UTC)).update();
        UUID revisedJob=sync.admitCashflowConversion(user,intent,"adjustments-revised-conversion-002").orElseThrow().jobId();
        assertThat(awaitConversion(revisedJob)).isEqualTo("CONVERTED_ESTIMATED");
        UUID current=conversionIdOf(revisedJob);
        assertThat(jdbc().sql("SELECT previous_conversion_id::text||'|'||change_reason FROM sync_cashflow_conversion_bundle "
                + "WHERE conversion_id=:c").param("c",current).query(String.class).single())
                .isEqualTo(earlier+"|PRICE_REVISION");
        assertThat(awaitPublication(publish(user,intent,"adjustments-revised-command-001"))).isEqualTo("PUBLISHED");
        Instant earlierProcessed=processedAtOf(earlier), currentProcessed=processedAtOf(current);

        var history=historyOf(user,SyncJobApi.AdjustmentDirection.ALL,null);
        assertThat(history.rows()).hasSize(2);
        var btc=history.rows().getFirst();
        assertThat(btc.references().conversionId()).as("the version's own conversion").isEqualTo(current);
        assertThat(btc.usdValue()).isEqualTo("650.1");
        assertThat(btc.price().legs()).singleElement().satisfies(leg -> assertThat(leg.closePrice()).isEqualTo("65010"));
        assertThat(btc.changed()).isTrue();
        assertThat(btc.revisions()).containsExactly(new SyncJobApi.AdjustmentHistory.Revision(earlier,earlierProcessed,
                "CONVERTED_ESTIMATED",null,"650.0025","PRICE_REVISION",currentProcessed));
        var usdt=history.rows().getLast();
        assertThat(usdt.changed()).as("the same value in both versions").isFalse();
        assertThat(usdt.revisions()).isEmpty();
        // Over HTTP: the earlier version with exactly the keys the screen validates (`isRevision`), a missing
        // reason as null.
        @SuppressWarnings("unchecked")
        var rows=(java.util.List<java.util.Map<String,Object>>)topLevelFieldsOf(get(adjustmentsPath(ready(user),""),
                cookie).body()).get("rows");
        @SuppressWarnings("unchecked")
        var revisions=(java.util.List<java.util.Map<String,Object>>)rows.getFirst().get("revisions");
        assertThat(revisions).singleElement().satisfies(revision -> {
            assertThat(revision.keySet()).containsExactly("conversionId","processedAt","status","reasonCode","usdValue",
                    "changeReason","discoveredAt");
            assertThat(revision).containsEntry("conversionId",earlier.toString()).containsEntry("reasonCode",null)
                    .containsEntry("usdValue","650.0025").containsEntry("changeReason","PRICE_REVISION")
                    .containsEntry("processedAt",earlierProcessed.toString())
                    .containsEntry("discoveredAt",currentProcessed.toString());
        });
        assertThat(rows.getFirst()).containsEntry("changed",true);
        assertThat(rows.getLast()).containsEntry("changed",false).containsEntry("revisions",java.util.List.of());
        // Append-only: the earlier version's rows are untouched.
        assertThat(calculableUsd(earlier,"revised-btc-in")).isEqualTo("650.0025");
        assertThat(jdbc().sql("SELECT close_price FROM sync_cashflow_conversion_leg l JOIN sync_cashflow_conversion_flow f "
                + "ON f.conversion_id=l.conversion_id AND f.source_event_id=l.source_event_id WHERE l.conversion_id=:c "
                + "AND f.source_id='revised-btc-in'").param("c",earlier).query(String.class).single()).isEqualTo("65000.25");
        evidenceOf(user);
    }

    /**
     * TAMPER (AC-1·AC-4): only the user's current pointer, with a cursor made for this user, reference and
     * filter, answers. Another user's pointer or cursor, a stale version, a cursor for another filter or
     * version, an edited, empty, extended or repeated cursor, a repeated or unknown direction, and a query the
     * container cannot parse all answer one 404 — to the letter the body of a route that does not exist.
     * Unknown parameters are ignored and change nothing.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aTamperedAdjustmentReferenceOrCursorRevealsNothing(org.springframework.boot.test.system.CapturedOutput output)
            throws Exception {
        String subject="adjustments-tamper-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        Instant base=closedTenMinuteBoundary();
        UUID intent=convertedIntent(user,"adjustments-tamper","0.1",base.minusSeconds(3600),usdtFlows("tamper",21,base));
        assertThat(awaitPublication(publish(user,intent,"adjustments-tamper-command-001"))).isEqualTo("PUBLISHED");
        UUID other=connect(DevBybitStub.Scenario.UTA2);
        UUID otherIntent=convertedIntent(other,"adjustments-tamper-other","0.2",base.minusSeconds(3600),
                usdtFlows("tamper-other",21,base));
        assertThat(awaitPublication(publish(other,otherIntent,"adjustments-tamper-other-001"))).isEqualTo("PUBLISHED");
        String emptyCookie=sessionCookieOf(signIn("adjustments-tamper-empty-"+UUID.randomUUID())).orElseThrow();
        var own=ready(user);
        var foreign=ready(other);
        String plan=own.planId().toString();
        String calc=own.calculationId().toString();
        String valid=query(own.planId(),own.pointerVersion(),own.calculationId());
        String cursor=historyOf(user,SyncJobApi.AdjustmentDirection.ALL,null).nextCursor();
        String foreignCursor=historyOf(other,SyncJobApi.AdjustmentDirection.ALL,null).nextCursor();
        assertThat(cursor).isNotNull();
        assertThat(foreignCursor).isNotNull();
        String edited=cursor.substring(0,10)+(cursor.charAt(10)=='A' ? 'B' : 'A')+cursor.substring(11);
        String path=DashboardController.ADJUSTMENTS_PATH;
        for (String accepted : new String[] {valid,valid+"&cursor="+cursor,valid+"&direction=ALL",valid+"&direction=IN",
                valid+"&direction=OUT",query(plan.toUpperCase(java.util.Locale.ROOT),"1",calc.toUpperCase(java.util.Locale.ROOT))}) {
            assertThat(get(path+accepted,cookie).statusCode()).as(accepted).isEqualTo(200);
        }
        // Unknown parameters — an event, classification or conversion id among them — change nothing.
        assertThat(get(path+valid+"&sourceEventId="+UUID.randomUUID()+"&conversionId="+UUID.randomUUID()
                + "&classificationId="+UUID.randomUUID()+"&page=9",cookie).body()).isEqualTo(get(path+valid,cookie).body());

        String notFound=send("GET",AuthSecurityConfig.DASHBOARD_PATH+"/no-such-route",cookie,"X-Request-Id",
                "adjustments-tamper-probe").body();
        int before=adjustmentLogLines(output).size();
        var tampered=java.util.List.of(
                query(foreign.planId(),foreign.pointerVersion(),foreign.calculationId()),
                query(own.planId(),1,foreign.calculationId()),
                query(foreign.planId(),1,own.calculationId()),
                query(own.planId(),2,own.calculationId()),
                query(UUID.randomUUID(),1,UUID.randomUUID()),
                query("not-a-uuid","1",calc),
                query(plan,"01",calc),
                "?planId="+plan+"&pointerVersion=1",
                "",
                valid+"&cursor="+foreignCursor,
                valid+"&direction=IN&cursor="+cursor,
                valid+"&direction=OUT&cursor="+cursor,
                valid+"&cursor="+edited,
                valid+"&cursor=",
                valid+"&cursor=garbage",
                valid+"&cursor="+cursor+"A",
                valid+"&cursor="+cursor+"&cursor="+cursor,
                valid+"&direction=ALL&direction=ALL",
                valid+"&direction=in",
                valid+"&direction=BOTH",
                valid+"&direction=",
                valid+"&planId="+plan);
        for (String probe : tampered) {
            var answer=send("GET",path+probe,cookie,"X-Request-Id","adjustments-tamper-probe");
            assertThat(answer.statusCode()).as(probe).isEqualTo(404);
            assertThat(answer.headers().firstValue("Cache-Control").orElse("")).as(probe).contains("no-store");
            assertThat(answer.body()).as(probe+" answers like a missing route").isEqualTo(notFound);
        }
        // A user without any plan, holding someone else's real reference.
        var empty=send("GET",path+valid,emptyCookie,"X-Request-Id","adjustments-tamper-probe");
        assertThat(empty.statusCode()).isEqualTo(404);
        assertThat(empty.body()).isEqualTo(notFound);
        // A version that is no longer current, and a cursor made for the version before.
        jdbc().sql("UPDATE sync_official_plan_result SET pointer_version=2 WHERE plan_id=:p").param("p",own.planId())
                .update();
        try {
            for (String stale : new String[] {valid,query(own.planId(),2,own.calculationId())+"&cursor="+cursor}) {
                var answer=send("GET",path+stale,cookie,"X-Request-Id","adjustments-tamper-probe");
                assertThat(answer.statusCode()).as(stale).isEqualTo(404);
                assertThat(answer.body()).isEqualTo(notFound);
            }
            assertThat(get(path+query(own.planId(),2,own.calculationId()),cookie).statusCode()).as("the current version")
                    .isEqualTo(200);
        } finally {
            jdbc().sql("UPDATE sync_official_plan_result SET pointer_version=1 WHERE plan_id=:p").param("p",own.planId())
                    .update();
        }
        // A query the container cannot parse, sent byte for byte.
        var unparsable=java.util.List.of("?planId=%zz&pointerVersion=1&calculationId="+calc,valid+"&cursor=%",
                valid+"&direction=%G1");
        for (String probe : unparsable) {
            String[] answer=rawGet(path+probe,cookie,"adjustments-tamper-probe");
            assertThat(answer[0]).as(probe).isEqualTo("404");
            assertThat(answer[1]).as(probe).isEqualTo(notFound);
        }
        var lines=adjustmentLogLines(output).subList(before,adjustmentLogLines(output).size());
        assertThat(lines.stream().filter(line -> line.contains("status=404")).toList())
                .hasSize(tampered.size()+1+2+unparsable.size())
                .allSatisfy(line -> assertThat(line).contains("state=NOT_FOUND status=404").endsWith("consistent=unknown"));
        assertThat(get(path+valid,null).statusCode()).as("unauthenticated").isEqualTo(401);
        // The other user's history is untouched and still their own.
        assertThat(historyOf(other,SyncJobApi.AdjustmentDirection.ALL,null).planId()).isEqualTo(foreign.planId());
    }

    /**
     * BROKEN (AC-2): when any applied row disagrees with its lineage — a missing link, another source id, time,
     * asset, quantity or USD value, a classification that is not an external flow or not of the direction, a
     * price whose route and legs disagree, a row out of order or out of count, a conversion chain that leads
     * nowhere — the answer is 503 {@code ADJUSTMENT_HISTORY_UNAVAILABLE} with no partial list, whichever page
     * is asked for, and the line says {@code consistent=false}. The dashboard does not read these and answers.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aBrokenAdjustmentLineageReturnsNoPartialListAndLogsConsistentFalse(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String subject="adjustments-broken-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        clearMarketEvidence();
        Instant base=closedTenMinuteBoundary();
        UUID intent=convertedIntent(user,"adjustments-broken","0.1",base.minusSeconds(3600),
                new Flow("broken-usdt-in","USDT","100",base.plusSeconds(21)),
                new Flow("broken-btc-in","BTC","0.01",base.plusSeconds(23)),
                new Flow("broken-usdt-out","USDT","-3",base.plusSeconds(25)),
                // The stub's ETH has no USDT market: its price takes the two-leg ETHUSDC × USDCUSDT route.
                new Flow("broken-eth-in","ETH","2",base.plusSeconds(27)));
        assertThat(awaitPublication(publish(user,intent,"adjustments-broken-command-001"))).isEqualTo("PUBLISHED");
        var own=ready(user);
        var reference=adjustmentQuery(own,SyncJobApi.AdjustmentDirection.ALL,null);
        var ids=jdbc().sql("SELECT candidate_id,classification_id,conversion_id,provider_account_id FROM sync_plan_calculation "
                + "WHERE calculation_id=:c").param("c",own.calculationId())
                .query((rs,n)->new UUID[] {rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                        rs.getObject(4,UUID.class)})
                .single();
        UUID candidate=ids[0], classification=ids[1], conversion=ids[2], account=ids[3];
        UUID deposit=eventIdOf(user,"broken-usdt-in"), estimated=eventIdOf(user,"broken-btc-in");
        UUID crossed=eventIdOf(user,"broken-eth-in");
        UUID calc=own.calculationId();
        // Whole before any corruption: the two-leg route holds with its two legs, numbered from zero.
        var whole=historyOf(user,SyncJobApi.AdjustmentDirection.ALL,null);
        assertThat(whole.rows()).hasSize(4);
        assertThat(whole.rows().getLast().price().routeId()).isEqualTo("SPOT_ASSET_USDC_USDCUSDT");
        assertThat(whole.rows().getLast().price().legs()).extracting(SyncJobApi.AdjustmentHistory.PriceLeg::symbol)
                .containsExactly("ETHUSDC","USDCUSDT");
        String semantic=jdbc().sql("SELECT semantic_hash FROM sync_cashflow_conversion_flow WHERE conversion_id=:c "
                + "AND source_event_id=:e").param("c",conversion).param("e",deposit).query(String.class).single();
        java.util.function.BiFunction<UUID,String,String> moveEvent=(target,shift) ->
                "UPDATE sync_plan_calculation_flow SET occurred_at=occurred_at"+shift+" WHERE calculation_id='"+calc
                        + "' AND source_event_id='"+target+"'; UPDATE sync_calculable_cashflow SET occurred_at=occurred_at"
                        + shift+" WHERE conversion_id='"+conversion+"' AND source_event_id='"+target
                        + "'; UPDATE sync_cashflow_conversion_flow SET occurred_at=occurred_at"+shift+" WHERE conversion_id='"
                        + conversion+"' AND source_event_id='"+target+"'; UPDATE sync_cashflow_classification_event "
                        + "SET occurred_at=occurred_at"+shift+" WHERE classification_id='"+classification
                        + "' AND source_event_id='"+target+"'; UPDATE sync_initial_source_event SET transaction_time="
                        + "transaction_time"+shift+" WHERE event_id='"+target+"'";
        java.util.function.Consumer<String> replica=sql -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc().sql("SET LOCAL session_replication_role = replica").update();
            jdbc().sql(sql).update();
        });
        // One statement at a time: a corruption may be a few statements in a row.
        java.util.function.Consumer<String> plain=sql -> {
            for (String statement : sql.split("; ")) jdbc().sql(statement).update();
        };
        UUID stranger=UUID.randomUUID();
        record Corruption(String name,String corrupt,String restore,boolean withoutForeignKeys) {}
        String event="event_id='"+deposit+"'";
        var corruptions=java.util.List.of(
                new Corruption("the source event carries another cash flow",
                        "UPDATE sync_initial_source_event SET cash_flow='999' WHERE "+event,
                        "UPDATE sync_initial_source_event SET cash_flow='100' WHERE "+event,false),
                new Corruption("the source event is of another asset",
                        "UPDATE sync_initial_source_event SET currency='USDC' WHERE "+event,
                        "UPDATE sync_initial_source_event SET currency='USDT' WHERE "+event,false),
                new Corruption("the source event happened at another time",
                        "UPDATE sync_initial_source_event SET transaction_time=transaction_time+interval '1 millisecond' "
                                + "WHERE "+event,
                        "UPDATE sync_initial_source_event SET transaction_time=transaction_time-interval '1 millisecond' "
                                + "WHERE "+event,false),
                new Corruption("the source event is another Bybit record",
                        "UPDATE sync_initial_source_event SET source_id='broken-usdt-in-x' WHERE "+event,
                        "UPDATE sync_initial_source_event SET source_id='broken-usdt-in' WHERE "+event,false),
                new Corruption("the source event is of the other Bybit type",
                        "UPDATE sync_initial_source_event SET event_type='TRANSFER_OUT' WHERE "+event,
                        "UPDATE sync_initial_source_event SET event_type='TRANSFER_IN' WHERE "+event,false),
                new Corruption("the classification excluded the event",
                        "UPDATE sync_cashflow_classification_event SET verdict='EXCLUDED',signed_amount=NULL "
                                + "WHERE classification_id='"+classification+"' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_cashflow_classification_event SET verdict='EXTERNAL_IN',signed_amount='100' "
                                + "WHERE classification_id='"+classification+"' AND source_event_id='"+deposit+"'",false),
                new Corruption("the calculable row carries another USD value",
                        "UPDATE sync_calculable_cashflow SET usd_value='100.5' WHERE conversion_id='"+conversion
                                + "' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_calculable_cashflow SET usd_value='100' WHERE conversion_id='"+conversion
                                + "' AND source_event_id='"+deposit+"'",false),
                new Corruption("the conversion flow carries another USD value",
                        "UPDATE sync_cashflow_conversion_flow SET usd_value='99' WHERE conversion_id='"+conversion
                                + "' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_cashflow_conversion_flow SET usd_value='100' WHERE conversion_id='"+conversion
                                + "' AND source_event_id='"+deposit+"'",false),
                new Corruption("a directly normalized flow names a spot route",
                        "UPDATE sync_cashflow_conversion_flow SET route_id='SPOT_ASSET_USDT' WHERE conversion_id='"
                                + conversion+"' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_cashflow_conversion_flow SET route_id='DIRECT' WHERE conversion_id='"+conversion
                                + "' AND source_event_id='"+deposit+"'",false),
                new Corruption("an estimated flow's legs are not numbered from zero",
                        "UPDATE sync_cashflow_conversion_leg SET leg_index=1 WHERE conversion_id='"+conversion
                                + "' AND source_event_id='"+estimated+"'",
                        "UPDATE sync_cashflow_conversion_leg SET leg_index=0 WHERE conversion_id='"+conversion
                                + "' AND source_event_id='"+estimated+"'",false),
                new Corruption("the version counts another flow",
                        "UPDATE sync_plan_calculation SET flow_count=flow_count+1 WHERE calculation_id='"+calc+"'",
                        "UPDATE sync_plan_calculation SET flow_count=flow_count-1 WHERE calculation_id='"+calc+"'",false),
                new Corruption("two applied rows are out of the version's order",
                        "UPDATE sync_plan_calculation_flow SET sequence=CASE sequence WHEN 1 THEN 1000 ELSE sequence END "
                                + "WHERE calculation_id='"+calc+"'; UPDATE sync_plan_calculation_flow SET sequence=1 "
                                + "WHERE calculation_id='"+calc+"' AND sequence=2; UPDATE sync_plan_calculation_flow "
                                + "SET sequence=2 WHERE calculation_id='"+calc+"' AND sequence=1000",
                        "UPDATE sync_plan_calculation_flow SET sequence=1000 WHERE calculation_id='"+calc+"' AND sequence=1; "
                                + "UPDATE sync_plan_calculation_flow SET sequence=1 WHERE calculation_id='"+calc
                                + "' AND sequence=2; UPDATE sync_plan_calculation_flow SET sequence=2 WHERE calculation_id='"
                                + calc+"' AND sequence=1000",false),
                new Corruption("the applied rows skip a sequence",
                        "UPDATE sync_plan_calculation_flow SET sequence=9 WHERE calculation_id='"+calc+"' AND sequence=3",
                        "UPDATE sync_plan_calculation_flow SET sequence=3 WHERE calculation_id='"+calc+"' AND sequence=9",false),
                new Corruption("the classification held an event for review",
                        "UPDATE sync_cashflow_classification_bundle SET review_count=1 WHERE classification_id='"
                                + classification+"'",
                        "UPDATE sync_cashflow_classification_bundle SET review_count=0 WHERE classification_id='"
                                + classification+"'",false),
                new Corruption("an event is not the candidate's",
                        "DELETE FROM sync_initial_candidate_event WHERE candidate_id='"+candidate+"' AND "+event,
                        "INSERT INTO sync_initial_candidate_event(candidate_id,event_id) VALUES ('"+candidate+"','"
                                + deposit+"')",false),
                new Corruption("an applied row cites another conversion",
                        "UPDATE sync_plan_calculation_flow SET conversion_id='"+stranger+"' WHERE calculation_id='"+calc
                                + "' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_plan_calculation_flow SET conversion_id='"+conversion+"' WHERE calculation_id='"
                                + calc+"' AND source_event_id='"+deposit+"'",true),
                new Corruption("the conversion continues a version that does not exist",
                        "UPDATE sync_cashflow_conversion_bundle SET previous_conversion_id='"+stranger
                                + "',change_reason='EVIDENCE_CHANGE' WHERE conversion_id='"+conversion+"'",
                        "UPDATE sync_cashflow_conversion_bundle SET previous_conversion_id=NULL,change_reason=NULL "
                                + "WHERE conversion_id='"+conversion+"'",true),
                // Every link moved together, so only the (t0, cashflowVerifiedThrough] bound decides.
                new Corruption("an applied event lies after the verified range",
                        moveEvent.apply(crossed,"+interval '1 day'"),moveEvent.apply(crossed,"-interval '1 day'"),false),
                new Corruption("an applied event lies at or before t0",
                        moveEvent.apply(deposit,"-interval '2 hours'"),moveEvent.apply(deposit,"+interval '2 hours'"),false),
                new Corruption("the conversion flow carries another semantic hash",
                        "UPDATE sync_cashflow_conversion_flow SET semantic_hash='"+"f".repeat(64)+"' WHERE conversion_id='"
                                + conversion+"' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_cashflow_conversion_flow SET semantic_hash='"+semantic+"' WHERE conversion_id='"
                                + conversion+"' AND source_event_id='"+deposit+"'",false),
                // Every table ties the estimated flag to the evidence kind by a CHECK, so both move together here.
                new Corruption("an applied row is estimated where its conversion is not",
                        "UPDATE sync_plan_calculation_flow SET estimated=true,evidence_type='SPOT_KLINE_CLOSE' "
                                + "WHERE calculation_id='"+calc+"' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_plan_calculation_flow SET estimated=false,evidence_type='DIRECT_NORMALIZATION' "
                                + "WHERE calculation_id='"+calc+"' AND source_event_id='"+deposit+"'",false),
                new Corruption("the conversion flow cites another classification rule",
                        "UPDATE sync_cashflow_conversion_flow SET classification_rule_id='SOME_OTHER_RULE' "
                                + "WHERE conversion_id='"+conversion+"' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_cashflow_conversion_flow SET classification_rule_id='EXTERNAL_TRANSFER_IN' "
                                + "WHERE conversion_id='"+conversion+"' AND source_event_id='"+deposit+"'",false),
                new Corruption("the conversion flow cites another classification policy",
                        "UPDATE sync_cashflow_conversion_flow SET classification_policy_version='BYBIT-CASHFLOW-9.9' "
                                + "WHERE conversion_id='"+conversion+"' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_cashflow_conversion_flow SET classification_policy_version='BYBIT-CASHFLOW-1.0' "
                                + "WHERE conversion_id='"+conversion+"' AND source_event_id='"+deposit+"'",false),
                new Corruption("the calculable row cites another price policy",
                        "UPDATE sync_calculable_cashflow SET price_policy_version='BYBIT-PRICE-9.9' WHERE conversion_id='"
                                + conversion+"' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_calculable_cashflow SET price_policy_version='BYBIT-PRICE-1.0' WHERE conversion_id='"
                                + conversion+"' AND source_event_id='"+deposit+"'",false),
                new Corruption("an applied row names another provider account",
                        "UPDATE sync_plan_calculation_flow SET provider_account_id='"+stranger+"' WHERE calculation_id='"
                                + calc+"' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_plan_calculation_flow SET provider_account_id='"+account+"' WHERE calculation_id='"
                                + calc+"' AND source_event_id='"+deposit+"'",false),
                new Corruption("the source event is of another provider account",
                        "UPDATE sync_initial_source_event SET provider_account_id='"+stranger+"' WHERE "+event,
                        "UPDATE sync_initial_source_event SET provider_account_id='"+account+"' WHERE "+event,false),
                new Corruption("the calculable row carries another quantity than its conversion flow",
                        "UPDATE sync_calculable_cashflow SET signed_quantity='101' WHERE conversion_id='"+conversion
                                + "' AND source_event_id='"+deposit+"'",
                        "UPDATE sync_calculable_cashflow SET signed_quantity='100' WHERE conversion_id='"+conversion
                                + "' AND source_event_id='"+deposit+"'",false),
                new Corruption("a two-leg price names the one-leg route",
                        "UPDATE sync_cashflow_conversion_flow SET route_id='SPOT_ASSET_USDT' WHERE conversion_id='"
                                + conversion+"' AND source_event_id='"+crossed+"'",
                        "UPDATE sync_cashflow_conversion_flow SET route_id='SPOT_ASSET_USDC_USDCUSDT' WHERE conversion_id='"
                                + conversion+"' AND source_event_id='"+crossed+"'",false),
                new Corruption("the classification's excluded count is not its events'",
                        "UPDATE sync_cashflow_classification_bundle SET excluded_count=excluded_count+1 "
                                + "WHERE classification_id='"+classification+"'",
                        "UPDATE sync_cashflow_classification_bundle SET excluded_count=excluded_count-1 "
                                + "WHERE classification_id='"+classification+"'",false));
        int before=adjustmentLogLines(output).size();
        int requests=0;
        for (Corruption corruption : corruptions) {
            (corruption.withoutForeignKeys() ? replica : plain).accept(corruption.corrupt());
            for (String extra : new String[] {"","&direction=OUT"}) {
                var refused=send("GET",adjustmentsPath(own,extra),cookie,"X-Request-Id","adjustments-broken");
                requests++;
                assertThat(refused.statusCode()).as(corruption.name()).isEqualTo(503);
                assertThat(refused.headers().firstValue("Cache-Control").orElse("")).as(corruption.name())
                        .contains("no-store");
                assertThat(topLevelFieldsOf(refused.body())).as(corruption.name())
                        .containsOnlyKeys("code","message","correlationId")
                        .containsEntry("code","ADJUSTMENT_HISTORY_UNAVAILABLE").containsEntry("correlationId","adjustments-broken");
                assertThat(refused.body()).as(corruption.name()).doesNotContain(own.calculationId().toString())
                        .doesNotContain("broken-usdt-in").doesNotContain("broken-btc-in");
            }
            assertThat(sync.adjustmentHistory(user,reference)).as(corruption.name()).isEqualTo(new SyncJobApi.AdjustmentRead(
                    new SyncJobApi.AdjustmentOutcome.Unavailable(),SyncJobApi.BundleConsistency.INCONSISTENT));
            assertThat(dashboardOf(user)).as("the dashboard itself: "+corruption.name())
                    .isInstanceOf(SyncJobApi.Dashboard.Ready.class);
            (corruption.withoutForeignKeys() ? replica : plain).accept(corruption.restore());
            assertThat(sync.adjustmentHistory(user,reference).outcome()).as("restored: "+corruption.name())
                    .isInstanceOf(SyncJobApi.AdjustmentOutcome.Found.class);
        }
        var lines=adjustmentLogLines(output);
        assertThat(lines.subList(before,lines.size())).hasSize(requests)
                .allSatisfy(line -> assertThat(line).contains("state=INCONSISTENT status=503").endsWith("consistent=false"));
    }

    /**
     * DELETING (AC-9) and a failing read. A read that throws answers the common 500 and one {@code ERROR} line,
     * and the dashboard, which does not read that table, still answers. The account's own access is decided
     * before and after the read: a deletion that begins while the history is read wins; in progress or
     * finished, nothing comes back; another user's history stays.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aDeletionOnEitherSideOfTheAdjustmentReadRefusesItAndAFailingReadAnswersTheCommonError(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String subject="adjustments-deletion-"+UUID.randomUUID();
        String cookie=sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user=userIdOf(OidcStubConfiguration.issuer(),subject).orElseThrow();
        store(user,DevBybitStub.Scenario.UTA2);
        Instant base=closedTenMinuteBoundary();
        UUID intent=convertedIntent(user,"adjustments-deletion","0.1",base.minusSeconds(3600),
                new Flow("deletion-in","USDT","12",base.plusSeconds(14)));
        assertThat(awaitPublication(publish(user,intent,"adjustments-deletion-command-001"))).isEqualTo("PUBLISHED");
        UUID kept=connect(DevBybitStub.Scenario.UTA2);
        UUID keptIntent=convertedIntent(kept,"adjustments-deletion-kept","0.1",base.minusSeconds(3600),
                new Flow("deletion-kept-in","USDT","9",base.plusSeconds(15)));
        assertThat(awaitPublication(publish(kept,keptIntent,"adjustments-deletion-kept-001"))).isEqualTo("PUBLISHED");
        var own=ready(user);
        String path=adjustmentsPath(own,"");

        // A read that throws: a table only the history reads is hidden for one request. (The candidate's event
        // table no longer is: since Story 2.12 the dashboard's verification preview reads it too.)
        int before=adjustmentLogLines(output).size();
        java.net.http.HttpResponse<String> failed;
        java.net.http.HttpResponse<String> dashboard;
        jdbc().sql("ALTER TABLE sync_cashflow_conversion_leg RENAME TO sync_cashflow_conversion_leg_hidden").update();
        try {
            failed=send("GET",path,cookie,"X-Request-Id","adjustments-error-probe");
            dashboard=get(AuthSecurityConfig.DASHBOARD_PATH,cookie);
        } finally {
            jdbc().sql("ALTER TABLE sync_cashflow_conversion_leg_hidden RENAME TO sync_cashflow_conversion_leg").update();
        }
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(failed.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(failed.body())).containsOnlyKeys("code","message","correlationId")
                .containsEntry("code","INTERNAL_ERROR").containsEntry("correlationId","adjustments-error-probe");
        assertThat(failed.body()).doesNotContain("deletion-in").doesNotContain("sync_cashflow_conversion_leg");
        assertThat(dashboard.statusCode()).as("the dashboard does not read that table").isEqualTo(200);
        assertThat(adjustmentLogLines(output).subList(before,adjustmentLogLines(output).size())).singleElement()
                .asString().matches(".*adjustment history read: state=ERROR status=500 latencyMs=\\d+ consistent=unknown$");
        assertThat(output.getAll()).doesNotContain("sync_cashflow_conversion_leg_hidden");
        assertThat(get(path,cookie).statusCode()).as("whole again").isEqualTo(200);

        // After: the read is past its first check and holds its snapshot when the deletion commits.
        java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>> pending;
        AutoCloseable lock=holdTableLock("sync_initial_candidate_event");
        try {
            pending=java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return get(path,cookie);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            // The history's aggregate is the one statement that begins so (pg_stat_activity keeps only the
            // first kilobyte of a statement, so the fragment must come early).
            awaitBlockedOn("count(*) FILTER (WHERE NOT r.ok)");
            accountDeletion().beginDeletion(user);
        } finally {
            lock.close();
        }
        var late=pending.get(20,TimeUnit.SECONDS);
        assertThat(late.statusCode()).as("the deletion that began during the read wins").isEqualTo(403);
        assertThat(late.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(late.body())).containsOnlyKeys("code","message","correlationId")
                .containsEntry("code","FORBIDDEN");
        assertThat(late.body()).doesNotContain("deletion-in").doesNotContain(own.calculationId().toString());
        assertThat(adjustmentLogLines(output).getLast()).contains("state=DENIED status=403").endsWith("consistent=true");

        // Before: in progress. Nothing was read.
        assertThat(get(path,cookie).statusCode()).isEqualTo(403);
        assertThat(adjustmentLogLines(output).getLast()).contains("state=DENIED status=403").endsWith("consistent=unknown");
        assertThat(get(DashboardController.ADJUSTMENTS_PATH+"?planId=malformed",cookie).statusCode())
                .as("a malformed reference is not a way around the refusal").isEqualTo(403);
        assertThat(get(path+"&cursor=garbage",cookie).statusCode()).as("nor is a malformed cursor").isEqualTo(403);

        // Finished: the erasure ran in its order; the session went with it.
        portfolio.erasePortfolioData(user);
        sync.eraseSyncData(user);
        plans.erasePlanData(user);
        source.eraseSourceData(user);
        assertThat(accountDeletion().erasePayload(user)).isEqualTo(com.bokrilog.identity.DeletionOutcome.COMPLETED);
        assertThat(sync.adjustmentHistory(user,adjustmentQuery(own,SyncJobApi.AdjustmentDirection.ALL,null)).outcome())
                .as("DELETED").isInstanceOf(SyncJobApi.AdjustmentOutcome.NotPermitted.class);
        assertThat(get(path,cookie).statusCode()).isEqualTo(401);
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_source_event WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).as("the history's source events").isZero();

        var keptHistory=historyOf(kept,SyncJobApi.AdjustmentDirection.ALL,null);
        assertThat(keptHistory.rows()).singleElement()
                .satisfies(row -> assertThat(row.references().sourceId()).isEqualTo("deletion-kept-in"));
    }

    /** The page query of a shown result through the service port. */
    private static SyncJobApi.AdjustmentQuery adjustmentQuery(SyncJobApi.DashboardResult shown,
            SyncJobApi.AdjustmentDirection direction,@org.jspecify.annotations.Nullable String cursor) {
        return new SyncJobApi.AdjustmentQuery(shown.planId(),shown.pointerVersion(),shown.calculationId(),direction,cursor);
    }

    /** One page of the user's current history through the service port; fails unless it is found whole. */
    private SyncJobApi.AdjustmentHistory historyOf(UUID user,SyncJobApi.AdjustmentDirection direction,
            @org.jspecify.annotations.Nullable String cursor) {
        var read=sync.adjustmentHistory(user,adjustmentQuery(ready(user),direction,cursor));
        assertThat(read.consistency()).isEqualTo(SyncJobApi.BundleConsistency.CONSISTENT);
        assertThat(read.outcome()).isInstanceOf(SyncJobApi.AdjustmentOutcome.Found.class);
        return ((SyncJobApi.AdjustmentOutcome.Found)read.outcome()).history();
    }

    /** The history path of a shown result, with {@code extra} parameters after the three reference values. */
    private static String adjustmentsPath(SyncJobApi.DashboardResult shown,String extra) {
        return DashboardController.ADJUSTMENTS_PATH+query(shown.planId(),shown.pointerVersion(),shown.calculationId())
                +extra;
    }

    /** The history's operational lines captured so far, in order. */
    private static java.util.List<String> adjustmentLogLines(org.springframework.boot.test.system.CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains("adjustment history read:")).toList();
    }

    /** The exact sum in canonical notation. */
    private static String exactSum(java.util.Collection<java.math.BigDecimal> values) {
        java.math.BigDecimal sum=values.stream().reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
        return sum.signum()==0 ? "0" : sum.stripTrailingZeros().toPlainString();
    }

    /** {@code count} directly normalized deposits of {@code 1 USDT}, a second apart. */
    private static Flow[] usdtFlows(String prefix,int count,Instant base) {
        return java.util.stream.IntStream.rangeClosed(1,count).mapToObj(i -> new Flow(
                String.format(java.util.Locale.ROOT,"%s-%02d",prefix,i),"USDT","1",base.plusSeconds(i))).toArray(Flow[]::new);
    }

    private UUID eventIdOf(UUID user,String sourceId) {
        return jdbc().sql("SELECT event_id FROM sync_initial_source_event WHERE user_id=:u AND source_id=:s")
                .param("u",user).param("s",sourceId).query(UUID.class).single();
    }

    private Instant firstObservedAt(UUID user,String sourceId) {
        return jdbc().sql("SELECT first_observed_at FROM sync_initial_source_event WHERE user_id=:u AND source_id=:s")
                .param("u",user).param("s",sourceId).query(java.time.OffsetDateTime.class).single().toInstant();
    }

    private Instant processedAtOf(UUID conversion) {
        return jdbc().sql("SELECT processed_at FROM sync_cashflow_conversion_bundle WHERE conversion_id=:c")
                .param("c",conversion).query(java.time.OffsetDateTime.class).single().toInstant();
    }

    /** A source event the classification excludes, placed on the candidate beside the external flows. */
    private record Excluded(String sourceId,String type,String currency,String cashFlow,Instant at) {}

    /** Collects, places the flows and the excluded events on the candidate, classifies and converts. */
    private UUID convertedIntentWithExcluded(UUID user,String prefix,Instant start,java.util.List<Excluded> excluded,
            Flow... flows) throws Exception {
        UUID intent=initialIntent(user,prefix,"0.1");
        shiftStart(user,intent,start);
        UUID collection=sync.admitInitialInputs(user,intent,prefix+"-inputs-001").orElseThrow().jobId();
        assertThat(awaitInitial(user,collection)).isEqualTo("INPUTS_COMPLETE");
        UUID candidate=injectFlows(user,collection,flows);
        var bundle=jdbc().sql("SELECT provider_account_id,connection_id FROM sync_initial_candidate_bundle "
                + "WHERE candidate_id=:c").param("c",candidate)
                .query((rs,n)->new UUID[] {rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)}).single();
        for (Excluded event : excluded) {
            insertCandidateEvent(user,bundle[0],bundle[1],collection,candidate,event.sourceId(),event.type(),
                    event.currency(),event.cashFlow(),event.at(),sha256Hex(user+"|"+event.sourceId()));
        }
        refreshCandidateHash(candidate);
        UUID classification=sync.admitCashflowClassification(user,intent,prefix+"-classification-001").orElseThrow().jobId();
        assertThat(awaitClassification(classification)).isEqualTo("COMPLETE");
        UUID conversion=sync.admitCashflowConversion(user,intent,prefix+"-conversion-001").orElseThrow().jobId();
        assertThat(awaitConversion(conversion)).isIn("CONVERTED","CONVERTED_ESTIMATED");
        return intent;
    }

    private static String shortened(String probe) {
        return probe.length()>80 ? probe.substring(0,80)+"…" : probe;
    }

    /**
     * One GET over a raw socket, the request target sent byte for byte: {@code [status, body]}. HTTP/1.0,
     * so the answer is never chunked and the connection closes after it.
     */
    private String[] rawGet(String target,String cookie,String requestId) throws java.io.IOException {
        try (var socket=new java.net.Socket("127.0.0.1",serverPort)) {
            socket.setSoTimeout(20_000);
            String request="GET "+target+" HTTP/1.0\r\nHost: 127.0.0.1:"+serverPort+"\r\nCookie: "+SESSION_COOKIE_NAME+"="
                    +cookie+"\r\nX-Request-Id: "+requestId+"\r\nAccept: application/json\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String raw=new String(socket.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            int split=raw.indexOf("\r\n\r\n");
            return new String[] {raw.substring(0,split).split(" ")[1],raw.substring(split+4)};
        }
    }

    /** The shown pointer of a dashboard result, as the three values the evidence path takes. */
    private static SyncJobApi.EvidenceReference referenceOf(SyncJobApi.DashboardResult result) {
        return new SyncJobApi.EvidenceReference(result.planId(),result.pointerVersion(),result.calculationId());
    }

    /** The evidence of the user's current result through the service port; fails unless it is found whole. */
    private SyncJobApi.CalculationEvidence evidenceOf(UUID user) {
        var read=sync.calculationEvidence(user,referenceOf(ready(user)));
        assertThat(read.consistency()).isEqualTo(SyncJobApi.BundleConsistency.CONSISTENT);
        assertThat(read.outcome()).isInstanceOf(SyncJobApi.EvidenceOutcome.Found.class);
        return ((SyncJobApi.EvidenceOutcome.Found)read.outcome()).evidence();
    }

    /** The evidence query of a dashboard response's {@code result} object. */
    private static String query(java.util.Map<String,Object> result) {
        return query(String.valueOf(result.get("planId")),String.valueOf(result.get("pointerVersion")),
                String.valueOf(result.get("calculationId")));
    }

    private static String query(UUID planId,long pointerVersion,UUID calculationId) {
        return query(planId.toString(),Long.toString(pointerVersion),calculationId.toString());
    }

    /** The values as they are, unencoded: the probes above send malformed values on purpose. */
    private static String query(String planId,String pointerVersion,String calculationId) {
        return "?planId="+planId+"&pointerVersion="+pointerVersion+"&calculationId="+calculationId;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> nested(java.util.Map<String,Object> fields,String name) {
        return (java.util.Map<String,Object>)fields.get(name);
    }

    /** The evidence's operational lines captured so far, in order. */
    private static java.util.List<String> evidenceLogLines(org.springframework.boot.test.system.CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains("calculation evidence read:")).toList();
    }

    /** The dashboard's operational lines captured so far, in order. */
    private static java.util.List<String> dashboardLogLines(org.springframework.boot.test.system.CapturedOutput output) {
        return output.getAll().lines().filter(line -> line.contains("dashboard read:")).toList();
    }

    /** Holds an {@code ACCESS EXCLUSIVE} lock on {@code table} in a transaction of its own until closed. */
    private AutoCloseable holdTableLock(String table) throws InterruptedException {
        var acquired=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        Thread holder=Thread.ofVirtual().start(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc().sql("LOCK TABLE "+table+" IN ACCESS EXCLUSIVE MODE").update();
            acquired.countDown();
            try {
                if (!release.await(20,TimeUnit.SECONDS)) throw new IllegalStateException("table lock never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(acquired.await(5,TimeUnit.SECONDS)).isTrue();
        return () -> {
            release.countDown();
            holder.join(10_000);
        };
    }

    /** Collects, classifies and converts; {@code start} moves t0 back so the flows fall inside (t0, tc]. */
    private UUID convertedIntent(UUID user,String prefix,String rate,@org.jspecify.annotations.Nullable Instant start,
            Flow... flows) throws Exception {
        return convertedIntent(user,prefix,rate,start,null,flows);
    }

    private UUID convertedIntent(UUID user,String prefix,String rate,@org.jspecify.annotations.Nullable Instant start,
            @org.jspecify.annotations.Nullable String equity,Flow... flows) throws Exception {
        return convert(user,initialIntent(user,prefix,rate),prefix,start,equity,flows);
    }

    /** Collects, classifies and converts the inputs of an intent that already exists. */
    private UUID convert(UUID user,UUID intent,String prefix,@org.jspecify.annotations.Nullable Instant start,
            @org.jspecify.annotations.Nullable String equity,Flow... flows) throws Exception {
        if (start!=null) shiftStart(user,intent,start);
        UUID collection=sync.admitInitialInputs(user,intent,prefix+"-inputs-001").orElseThrow().jobId();
        assertThat(awaitInitial(user,collection)).isEqualTo("INPUTS_COMPLETE");
        if (flows.length>0 || equity!=null) {
            UUID candidate=injectFlows(user,collection,flows);
            if (equity!=null) {
                jdbc().sql("UPDATE sync_initial_candidate_bundle SET equity_decimal=:e WHERE candidate_id=:c")
                        .param("e",equity).param("c",candidate).update();
                refreshCandidateHash(candidate);
            }
        }
        UUID classification=sync.admitCashflowClassification(user,intent,prefix+"-classification-001").orElseThrow().jobId();
        assertThat(awaitClassification(classification)).isEqualTo("COMPLETE");
        UUID conversion=sync.admitCashflowConversion(user,intent,prefix+"-conversion-001").orElseThrow().jobId();
        assertThat(awaitConversion(conversion)).isIn("CONVERTED","CONVERTED_ESTIMATED");
        return intent;
    }

    private UUID convertedIntent(UUID user,String prefix,String rate,@org.jspecify.annotations.Nullable Instant start,
            String equity) throws Exception {
        return convertedIntent(user,prefix,rate,start,equity,new Flow[0]);
    }

    /** Places the flows on the collected candidate and recomputes its completeness hash. */
    private UUID injectFlows(UUID user,UUID collection,Flow... flows) {
        var bundle=jdbc().sql("SELECT candidate_id,provider_account_id,connection_id FROM sync_initial_candidate_bundle "
                + "WHERE job_id=:j").param("j",collection).query((rs,n)->new UUID[] {rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class)}).single();
        for (Flow flow : flows) {
            insertCandidateEvent(user,bundle[1],bundle[2],collection,bundle[0],flow.sourceId(),
                    flow.cashFlow().startsWith("-") ? "TRANSFER_OUT" : "TRANSFER_IN",flow.currency(),flow.cashFlow(),
                    flow.at(),sha256Hex(user+"|"+flow.sourceId()));
        }
        refreshCandidateHash(bundle[0]);
        return bundle[0];
    }

    /**
     * Moves the plan start (the first official snapshot's measured time and the intent's t0) back, before
     * collection starts, so injected flows on long-closed minutes fall inside (t0, tc].
     */
    private void shiftStart(UUID user,UUID intent,Instant start) {
        var t0=java.time.OffsetDateTime.ofInstant(start,java.time.ZoneOffset.UTC);
        jdbc().sql("UPDATE portfolio_snapshot SET measured_at=:t0 WHERE user_id=:u AND publication_sequence=1")
                .param("t0",t0).param("u",user).update();
        // The first snapshot's source moves with it: a published first snapshot and its source share one time.
        jdbc().sql("UPDATE sync_source_snapshot SET measured_at=:t0 WHERE user_id=:u AND job_id IS NOT NULL")
                .param("t0",t0).param("u",user).update();
        jdbc().sql("UPDATE plan_condition_intent SET t0=:t0 WHERE intent_id=:i").param("t0",t0).param("i",intent).update();
        jdbc().sql("UPDATE portfolio_source_membership SET started_at=:before WHERE portfolio_id IN "
                + "(SELECT portfolio_id FROM portfolio WHERE user_id=:u)")
                .param("before",t0.minusMinutes(1)).param("u",user).update();
    }

    private UUID publish(UUID user,UUID intent,String key) {
        var admission=sync.admitPlanPublication(user,intent,key);
        assertThat(admission).as("admission").isInstanceOf(SyncJobApi.PublicationAdmission.Accepted.class);
        return ((SyncJobApi.PublicationAdmission.Accepted)admission).job().jobId();
    }

    private String awaitPublication(UUID job) throws InterruptedException {
        String status=pollUntil(Duration.ofSeconds(15),()->jdbc().sql("SELECT status FROM sync_plan_publication_job "
                + "WHERE job_id=:j").param("j",job).query(String.class).single(),FirstSnapshotTest::finished);
        if (!finished(status)) throw new AssertionError("plan publication did not finish");
        return status;
    }

    /** No plan, calculation, pointer or tc snapshot; the portfolio pointer is still the first; the intent is open. */
    private void assertNothingPublished(UUID user,UUID intent) {
        for (String table : new String[] {"plan_definition","sync_plan_calculation","sync_official_plan_result"}) {
            assertThat(jdbc().sql("SELECT count(*) FROM "+table+" WHERE user_id=:u").param("u",user)
                    .query(Integer.class).single()).as(table).isZero();
        }
        assertThat(jdbc().sql("SELECT count(*) FROM sync_source_snapshot WHERE user_id=:u AND initial_candidate_id IS NOT NULL")
                .param("u",user).query(Integer.class).single()).isZero();
        assertThat(jdbc().sql("SELECT count(*) FROM portfolio_snapshot WHERE user_id=:u").param("u",user)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT c.publication_sequence FROM portfolio_current_snapshot c JOIN portfolio p "
                + "ON p.portfolio_id=c.portfolio_id WHERE p.user_id=:u").param("u",user).query(Long.class).single())
                .isEqualTo(1L);
        assertThat(jdbc().sql("SELECT status||'|'||(consumed_at IS NULL)||'|'||(consumed_by_plan_id IS NULL) "
                + "FROM plan_condition_intent WHERE intent_id=:i").param("i",intent).query(String.class).single())
                .isIn("OPEN|true|true","REPLACED|true|true");
    }

    /** The stored version is what the pure calculator produces again from the stored inputs. */
    private void assertReproducible(UUID calculation) {
        var row=jdbc().sql("SELECT p0_decimal,t0,end_at,period,target_rate,basis_at,conversion_id,provider_account_id,"
                + "input_hash,result_hash,created_by_job_id FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c",calculation).query((rs,n)->new Object[] {rs.getString(1),
                        rs.getObject(2,java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject(3,java.time.OffsetDateTime.class).toInstant(),rs.getString(4),rs.getString(5),
                        rs.getObject(6,java.time.OffsetDateTime.class).toInstant(),rs.getObject(7,UUID.class),
                        rs.getObject(8,UUID.class),rs.getString(9),rs.getString(10),rs.getObject(11,UUID.class)}).single();
        String current=jdbc().sql("SELECT current_value_decimal FROM sync_plan_publication_job WHERE job_id=:j")
                .param("j",row[10]).query(String.class).single();
        var flows=jdbc().sql("SELECT occurred_at,source_id,usd_value,estimated FROM sync_calculable_cashflow "
                + "WHERE conversion_id=:c").param("c",row[6]).query((rs,n)->new PlanCalculator.Cashflow(
                        rs.getObject(1,java.time.OffsetDateTime.class).toInstant(),row[7].toString(),rs.getString(2),
                        rs.getString(3),rs.getBoolean(4))).list();
        var again=PlanCalculator.calculate(new PlanCalculator.Input((String)row[0],(Instant)row[1],(Instant)row[2],
                PlanCalculator.Period.valueOf((String)row[3]),(String)row[4],current,(Instant)row[5],
                flows.reversed(),PlanCalculator.CALCULATION_POLICY,PlanCalculator.CALENDAR_POLICY));
        assertThat(again).isInstanceOf(PlanCalculator.Result.Calculated.class);
        assertThat(again.inputHash()).isEqualTo(row[8]);
        assertThat(((PlanCalculator.Result.Calculated)again).resultHash()).isEqualTo(row[9]);
        for (var metric : ((PlanCalculator.Result.Calculated)again).metrics()) {
            assertThat(jdbc().sql("SELECT COALESCE(value_decimal,'-')||'|'||nature||'|'||COALESCE(unavailable_reason,'-') "
                    + "FROM sync_plan_calculation_metric WHERE calculation_id=:c AND metric=:m").param("c",calculation)
                    .param("m",metric.metric().name()).query(String.class).single())
                    .isEqualTo((metric.value()==null?"-":metric.value())+"|"+metric.nature()+"|"
                            +(metric.unavailableReason()==null?"-":metric.unavailableReason()));
        }
    }

    /**
     * Holds the user's plan write lock (the first lock the publishing transaction takes) until closed, so
     * a test can change the world while the worker waits between its calculation and its commit.
     */
    private AutoCloseable holdPlanLock(UUID user) throws InterruptedException {
        var acquired=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        Thread holder=Thread.ofVirtual().start(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc().sql("WITH l AS MATERIALIZED (SELECT pg_advisory_xact_lock(hashtextextended(:u,0))) SELECT 1 FROM l")
                    .param("u",user.toString()).query(Integer.class).single();
            acquired.countDown();
            try {
                if (!release.await(20,TimeUnit.SECONDS)) throw new IllegalStateException("plan lock never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(acquired.await(5,TimeUnit.SECONDS)).isTrue();
        return () -> {
            release.countDown();
            holder.join(10_000);
        };
    }

    /**
     * Holds a row lock ({@code lockSql} binds {@code :id}) in a transaction of its own until closed, and runs
     * {@code whileHeld} in that transaction right after taking the lock. What {@code whileHeld} writes
     * becomes visible to everyone else only when the lock is released.
     */
    private AutoCloseable holdRowLock(String lockSql,UUID id,Runnable whileHeld) throws InterruptedException {
        var acquired=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread holder=Thread.ofVirtual().start(() -> {
            try {
                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    jdbc().sql(lockSql).param("id",id).query(UUID.class).list();
                    whileHeld.run();
                    acquired.countDown();
                    try {
                        if (!release.await(30,TimeUnit.SECONDS)) throw new IllegalStateException("row lock never released");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (RuntimeException | Error unexpected) {
                failure.set(unexpected);
                acquired.countDown();
            }
        });
        assertThat(acquired.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).as("row lock holder").isNull();
        return () -> {
            release.countDown();
            holder.join(10_000);
            assertThat(failure.get()).as("row lock holder").isNull();
        };
    }

    /** Waits until some backend waits for a lock while running a statement that contains {@code fragment}. */
    private void awaitBlockedOn(String fragment) throws InterruptedException {
        for (int i=0;i<200;i++) {
            if (jdbc().sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE :q")
                    .param("q","%"+fragment+"%").query(Integer.class).single()>0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("no statement ever waited on a lock: "+fragment);
    }

    private void awaitBlockedOnPlanLock() throws InterruptedException {
        for (int i=0;i<150;i++) {
            if (jdbc().sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")
                    .query(Integer.class).single()>0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the publication never reached the plan lock");
    }

    /**
     * Waits until no advisory lock is held or awaited. Called after the holder let go: the late worker took the
     * plan lock inside its publishing transaction, so an empty {@code pg_locks} means that transaction ended.
     *
     * <p>Preconditions: the caller saw the late worker waiting on the plan lock ({@link #awaitBlockedOnPlanLock})
     * and the holder has been joined ({@link #holdPlanLock}'s close). These tests hold no other advisory lock,
     * so counting every advisory lock rather than the plan lock's key can only make the wait longer, never
     * shorter; a key filter could pass vacuously if its arithmetic were off.
     */
    private void awaitPlanLockReleased() throws InterruptedException {
        int left=pollUntil(Duration.ofSeconds(10),()->jdbc().sql("SELECT count(*) FROM pg_locks "
                + "WHERE locktype='advisory'").query(Integer.class).single(),count->count==0);
        if (left>0) throw new AssertionError("the late worker never let go of the plan lock");
    }

    /** Waits until the database clock, the one the publication judges by, is past {@code deadline}. */
    private void awaitDatabaseClockPast(java.time.OffsetDateTime deadline) throws InterruptedException {
        boolean passed=pollUntil(Duration.ofSeconds(10),()->jdbc().sql("SELECT clock_timestamp()>:d")
                .param("d",deadline).query(Boolean.class).single(),Boolean::booleanValue);
        if (!passed) throw new AssertionError("the database clock never passed "+deadline);
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
        String status=pollUntil(Duration.ofSeconds(10),()->sync.job(user,job).orElseThrow().status(),
                FirstSnapshotTest::finished);
        if (!finished(status)) throw new AssertionError("job did not finish");
        return status;
    }
    private String awaitInitial(UUID user,UUID job) throws InterruptedException {
        String status=pollUntil(Duration.ofSeconds(10),()->sync.initialCollectionJob(user,job).orElseThrow().status(),
                FirstSnapshotTest::finished);
        if (!finished(status)) throw new AssertionError("initial input collection did not finish");
        return status;
    }
    private String awaitClassification(UUID job) throws InterruptedException {
        String status=pollUntil(Duration.ofSeconds(10),()->jdbc().sql("SELECT status FROM "
                + "sync_cashflow_classification_job WHERE job_id=:j").param("j",job).query(String.class).single(),
                FirstSnapshotTest::finished);
        if (!finished(status)) throw new AssertionError("cash-flow classification did not finish");
        return status;
    }
    /** A job status that no worker will move any more: neither waiting for a claim nor claimed. */
    private static boolean finished(String status) {
        return !status.equals("QUEUED")&&!status.equals("RUNNING");
    }
}
