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
        Thread.sleep(1200);
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
        Thread.sleep(1200);
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
            Thread.sleep(1500);
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
        for (int i=0;i<150;i++) {
            String status=jdbc().sql("SELECT status FROM sync_cashflow_conversion_job WHERE job_id=:j")
                    .param("j",job).query(String.class).single();
            if (!status.equals("QUEUED")&&!status.equals("RUNNING")) return status;
            Thread.sleep(100);
        }
        throw new AssertionError("cash-flow conversion did not finish");
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
    private String awaitClassification(UUID job) throws InterruptedException {
        for(int i=0;i<100;i++) {
            String status=jdbc().sql("SELECT status FROM sync_cashflow_classification_job WHERE job_id=:j")
                    .param("j",job).query(String.class).single();
            if(!status.equals("QUEUED")&&!status.equals("RUNNING")) return status;
            Thread.sleep(100);
        }
        throw new AssertionError("cash-flow classification did not finish");
    }
}
