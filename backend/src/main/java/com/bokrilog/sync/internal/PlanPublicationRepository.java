package com.bokrilog.sync.internal;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.sync.SyncJobApi.CalculationEvidence.UnpublishedAttempt;
import com.bokrilog.sync.SyncJobApi.DashboardMetric;
import com.bokrilog.sync.SyncJobApi.PublicationJob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Story 2.7 publication attempts, calculation versions and the per-plan official pointer.
 *
 * <p>Everything here is user scoped and written only inside the publishing transaction the service
 * owns, except the attempt row itself (admission, claim, heartbeat, finish). Reads of the official
 * result start from the pointer and follow its calculation id; they never look for the newest row.
 */
@Repository
class PlanPublicationRepository {
    private final JdbcClient jdbc;

    PlanPublicationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Lease(UUID jobId, UUID userId, UUID intentId, UUID portfolioId, UUID connectionId,
            UUID providerAccountId, String inputScopeHash, long generation) {}

    /**
     * The complete candidate whose totalEquity is E(tc). An incremental one (Story 2.11) names the base candidate
     * whose events it carried and the start of its own windows; an initial one has neither.
     */
    record Candidate(UUID candidateId, UUID userId, UUID portfolioId, UUID intentId, UUID connectionId,
            UUID providerAccountId, Instant t0, Instant tc, String equity, Instant measuredAt, Instant receivedAt,
            String policyVersion, String completenessHash, @Nullable UUID baseCandidateId,
            @Nullable Instant windowStart) {}

    /** The classification's current conversion result: the bundle of its most recently finished attempt. */
    record Conversion(UUID conversionId, UUID classificationId, UUID userId, UUID intentId, UUID portfolioId,
            UUID connectionId, UUID providerAccountId, String status, boolean calculable, String fingerprint,
            String policyVersion, int flowCount, int estimatedCount) {}

    /** One calculable cash flow (V9), the only cash-flow input of the calculation. */
    record Flow(UUID sourceEventId, String sourceId, String semanticHash, Instant occurredAt, String usdValue,
            boolean estimated, String evidenceType) {}

    /** The inputs an attempt pinned at admission. */
    record Pinned(UUID jobId, UUID userId, UUID portfolioId, UUID intentId, UUID membershipId, UUID connectionId,
            UUID providerAccountId, UUID basisPortfolioSnapshotId, UUID basisSourceSnapshotId,
            UUID expectedPortfolioSnapshotId, long expectedPortfolioSequence, UUID candidateId, UUID classificationId,
            UUID conversionId, String principal, Instant t0, Instant endAt, String period, String targetRate,
            Instant tc, String currentValue, Instant measuredAt, int flowCount, String inputScopeHash,
            String calculationPolicy, String calendarPolicy) {}

    static final class PublicationBlocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        PublicationBlocked(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        String code() {
            return this.code;
        }
    }

    // ---------------------------------------------------------------- lineage inputs

    Optional<Candidate> candidate(UUID user, UUID candidateId) {
        return jdbc.sql("SELECT candidate_id,user_id,portfolio_id,intent_id,connection_id,provider_account_id,t0,tc,"
                + "equity_decimal,measured_at,received_at,policy_version,completeness_hash,base_candidate_id,window_start "
                + "FROM sync_initial_candidate_bundle WHERE candidate_id=:c AND user_id=:u")
            .param("c", candidateId).param("u", user)
            .query((rs, n) -> new Candidate(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5),
                    uuid(rs, 6), instant(rs, 7), instant(rs, 8), rs.getString(9), instant(rs, 10), instant(rs, 11),
                    rs.getString(12), rs.getString(13), rs.getObject(14, UUID.class), instantOrNull(rs, 15)))
            .optional();
    }

    Optional<Conversion> currentConversion(UUID user, UUID classificationId) {
        return jdbc.sql("SELECT b.conversion_id,b.classification_id,b.user_id,b.intent_id,b.portfolio_id,b.connection_id,"
                + "b.provider_account_id,b.status,b.calculable,b.outcome_fingerprint,b.policy_version,b.flow_count,"
                + "b.estimated_count FROM sync_cashflow_conversion_job j JOIN sync_cashflow_conversion_bundle b "
                + "ON b.conversion_id=j.conversion_id AND b.user_id=j.user_id WHERE j.user_id=:u "
                + "AND j.classification_id=:c AND j.finished_at IS NOT NULL ORDER BY j.finished_at DESC,j.job_id DESC LIMIT 1")
            .param("u", user).param("c", classificationId)
            .query((rs, n) -> new Conversion(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5),
                    uuid(rs, 6), uuid(rs, 7), rs.getString(8), rs.getBoolean(9), rs.getString(10), rs.getString(11),
                    rs.getInt(12), rs.getInt(13))).optional();
    }

    boolean conversionInProgress(UUID user, UUID classificationId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_cashflow_conversion_job WHERE user_id=:u "
                + "AND classification_id=:c AND status IN ('QUEUED','RUNNING'))")
            .param("u", user).param("c", classificationId).query(Boolean.class).single();
    }

    /** The calculable rows of a calculable bundle, in the calculation's stable order. */
    List<Flow> flows(UUID user, UUID conversionId) {
        return jdbc.sql("SELECT source_event_id,source_id,semantic_hash,occurred_at,usd_value,estimated,evidence_type "
                + "FROM sync_calculable_cashflow WHERE conversion_id=:c AND user_id=:u "
                + "ORDER BY occurred_at,source_id,source_event_id")
            .param("c", conversionId).param("u", user)
            .query((rs, n) -> new Flow(uuid(rs, 1), rs.getString(2), rs.getString(3), instant(rs, 4), rs.getString(5),
                    rs.getBoolean(6), rs.getString(7))).list();
    }

    String classificationPolicy(UUID classificationId) {
        return jdbc.sql("SELECT policy_version FROM sync_cashflow_classification_bundle WHERE classification_id=:c")
            .param("c", classificationId).query(String.class).single();
    }

    /** The database clock. Plan expiry and freshness are judged against it, not the application clock. */
    Instant databaseNow() {
        return jdbc.sql("SELECT statement_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    // ---------------------------------------------------------------- attempts

    int insert(UUID jobId, Pinned p, String key) {
        return jdbc.sql("INSERT INTO sync_plan_publication_job(job_id,user_id,portfolio_id,intent_id,membership_id,"
                + "connection_id,provider_account_id,basis_portfolio_snapshot_id,basis_source_snapshot_id,"
                + "expected_portfolio_snapshot_id,expected_portfolio_sequence,candidate_id,classification_id,conversion_id,"
                + "p0_decimal,t0,end_at,period,target_rate,tc,current_value_decimal,current_value_measured_at,flow_count,"
                + "input_scope_hash,calculation_policy,calendar_policy,idempotency_key,status) VALUES (:j,:u,:p,:i,:m,:c,:a,"
                + ":bs,:bss,:es,:eseq,:cand,:cls,:conv,:p0,:t0,:end,:period,:rate,:tc,:e,:measured,:flows,:hash,:calc,"
                + ":calendar,:key,'QUEUED') ON CONFLICT DO NOTHING")
            .param("j", jobId).param("u", p.userId()).param("p", p.portfolioId()).param("i", p.intentId())
            .param("m", p.membershipId()).param("c", p.connectionId()).param("a", p.providerAccountId())
            .param("bs", p.basisPortfolioSnapshotId()).param("bss", p.basisSourceSnapshotId())
            .param("es", p.expectedPortfolioSnapshotId()).param("eseq", p.expectedPortfolioSequence())
            .param("cand", p.candidateId()).param("cls", p.classificationId()).param("conv", p.conversionId())
            .param("p0", p.principal()).param("t0", utc(p.t0())).param("end", utc(p.endAt())).param("period", p.period())
            .param("rate", p.targetRate()).param("tc", utc(p.tc())).param("e", p.currentValue())
            .param("measured", utc(p.measuredAt())).param("flows", p.flowCount()).param("hash", p.inputScopeHash())
            .param("calc", p.calculationPolicy()).param("calendar", p.calendarPolicy()).param("key", key).update();
    }

    record KeyedJob(UUID intentId, PublicationJob job) {}

    Optional<KeyedJob> byKey(UUID user, String key) {
        return jdbc.sql(VIEW + " WHERE user_id=:u AND idempotency_key=:k").param("u", user).param("k", key)
            .query((rs, n) -> new KeyedJob(uuid(rs, 8), view(rs))).optional();
    }

    Optional<PublicationJob> active(UUID user, UUID intent) {
        return jdbc.sql(VIEW + " WHERE user_id=:u AND intent_id=:i AND status IN ('QUEUED','RUNNING') "
                + "ORDER BY created_at LIMIT 1").param("u", user).param("i", intent)
            .query((rs, n) -> view(rs)).optional();
    }

    Optional<PublicationJob> latest(UUID user, UUID intent) {
        return jdbc.sql(VIEW + " WHERE user_id=:u AND intent_id=:i ORDER BY created_at DESC,job_id DESC LIMIT 1")
            .param("u", user).param("i", intent).query((rs, n) -> view(rs)).optional();
    }

    /**
     * The closed code of an attempt that failed deterministically on exactly these inputs, if any. The
     * calculator is a pure function: the same pinned inputs fail the same way again, so a new attempt
     * would only repeat the failure.
     */
    Optional<String> deterministicFailure(UUID user, UUID intent, String inputScopeHash) {
        return jdbc.sql("SELECT failure_code FROM sync_plan_publication_job WHERE user_id=:u AND intent_id=:i "
                + "AND status='FAILED' AND failure_code IN ('CALCULATION_RANGE_EXCEEDED','CALCULATION_INPUT_INVALID') "
                + "AND input_scope_hash=:h ORDER BY created_at DESC,job_id DESC LIMIT 1")
            .param("u", user).param("i", intent).param("h", inputScopeHash).query(String.class).optional();
    }

    private static final String VIEW = "SELECT job_id,status,failure_code,plan_id,created_at,finished_at,"
            + "calculation_id,intent_id FROM sync_plan_publication_job";

    private static PublicationJob view(ResultSet rs) throws SQLException {
        String code = rs.getString(3);
        // A new attempt helps only after an interruption; every other ending needs new data or conditions.
        return new PublicationJob(uuid(rs, 1), rs.getString(2), code, "PUBLICATION_INTERRUPTED".equals(code),
                rs.getObject(4, UUID.class), instant(rs, 5), instantOrNull(rs, 6));
    }

    Optional<Lease> claim() {
        return jdbc.sql("UPDATE sync_plan_publication_job SET status='RUNNING',fencing_generation=fencing_generation+1,"
                + "lease_until=now()+interval '30 seconds',heartbeat_at=now(),last_checked_at=now() WHERE job_id=("
                + "SELECT job_id FROM sync_plan_publication_job WHERE status='QUEUED' OR (status='RUNNING' AND "
                + "lease_until<now()) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING job_id,user_id,"
                + "intent_id,portfolio_id,connection_id,provider_account_id,input_scope_hash,fencing_generation")
            .query((rs, n) -> new Lease(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5), uuid(rs, 6),
                    rs.getString(7), rs.getLong(8))).optional();
    }

    /*
     * Lease checks compare against statement_timestamp(), not now(). The publishing transaction can wait
     * on the plan lock for a long time, and now() is the transaction's start: a lease that expired while
     * it waited would still look alive to its later statements.
     */

    /** Locks the attempt row if this lease still owns it. Serializes with erasure. */
    boolean lockOwned(Lease lease) {
        return jdbc.sql("SELECT job_id FROM sync_plan_publication_job WHERE job_id=:j AND user_id=:u "
                + "AND connection_id=:c AND provider_account_id=:a AND status='RUNNING' AND fencing_generation=:g "
                + "AND lease_until>statement_timestamp() FOR UPDATE")
            .param("j", lease.jobId()).param("u", lease.userId()).param("c", lease.connectionId())
            .param("a", lease.providerAccountId()).param("g", lease.generation())
            .query(UUID.class).optional().isPresent();
    }

    /** Whether this lease still owns the attempt. A plain read: it never waits for a row lock. */
    boolean owns(Lease lease) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_plan_publication_job WHERE job_id=:j AND user_id=:u "
                + "AND status='RUNNING' AND fencing_generation=:g AND lease_until>statement_timestamp())")
            .param("j", lease.jobId()).param("u", lease.userId()).param("g", lease.generation())
            .query(Boolean.class).single();
    }

    /**
     * Renews the lease without ever waiting for the attempt row. The publishing transaction holds that row
     * {@code FOR UPDATE} for its whole length, and the heartbeat runs on the scheduler thread that also
     * dispatches every other job: waiting here would stall all of them. A row another transaction holds is
     * skipped; if the lease still owns it (a plain read), the attempt is alive — the lock holder is its own
     * publishing transaction, whose fenced finish decides at commit.
     *
     * @return whether the lease is still held (renewed, or skipped while its own transaction runs)
     */
    boolean heartbeat(Lease lease) {
        int renewed = jdbc.sql("UPDATE sync_plan_publication_job SET lease_until=now()+interval '30 seconds',"
                + "heartbeat_at=now(),last_checked_at=now() WHERE job_id=(SELECT job_id FROM sync_plan_publication_job "
                + "WHERE job_id=:j AND fencing_generation=:g AND status='RUNNING' AND lease_until>now() "
                + "FOR UPDATE SKIP LOCKED)")
            .param("j", lease.jobId()).param("g", lease.generation()).update();
        return renewed == 1 || owns(lease);
    }

    int finish(Lease lease, String status, String code) {
        return jdbc.sql("UPDATE sync_plan_publication_job SET status=:s,failure_code=:code,lease_until=NULL,"
                + "last_checked_at=statement_timestamp(),finished_at=statement_timestamp() WHERE job_id=:j "
                + "AND status='RUNNING' AND fencing_generation=:g AND lease_until>statement_timestamp()")
            .param("s", status).param("code", code).param("j", lease.jobId()).param("g", lease.generation()).update();
    }

    int finishPublished(Lease lease, UUID planId, UUID calculationId) {
        return jdbc.sql("UPDATE sync_plan_publication_job SET status='PUBLISHED',plan_id=:plan,calculation_id=:calc,"
                + "lease_until=NULL,last_checked_at=statement_timestamp(),finished_at=statement_timestamp() "
                + "WHERE job_id=:j AND status='RUNNING' AND fencing_generation=:g "
                + "AND lease_until>statement_timestamp()")
            .param("plan", planId).param("calc", calculationId).param("j", lease.jobId())
            .param("g", lease.generation()).update();
    }

    /** The pinned input columns of an attempt, in {@link Pinned}'s order. */
    private static final String PINNED_COLUMNS = "job_id,user_id,portfolio_id,intent_id,membership_id,connection_id,"
            + "provider_account_id,basis_portfolio_snapshot_id,basis_source_snapshot_id,expected_portfolio_snapshot_id,"
            + "expected_portfolio_sequence,candidate_id,classification_id,conversion_id,p0_decimal,t0,end_at,period,"
            + "target_rate,tc,current_value_decimal,current_value_measured_at,flow_count,input_scope_hash,"
            + "calculation_policy,calendar_policy";

    Pinned pinned(Lease lease) {
        return jdbc.sql("SELECT " + PINNED_COLUMNS + " FROM sync_plan_publication_job WHERE job_id=:j AND user_id=:u")
            .param("j", lease.jobId()).param("u", lease.userId())
            .query((rs, n) -> pinnedOf(rs, 0)).single();
    }

    /** The {@link #PINNED_COLUMNS} starting after {@code offset} columns of the row. */
    private static Pinned pinnedOf(ResultSet rs, int offset) throws SQLException {
        int o = offset;
        return new Pinned(uuid(rs, o + 1), uuid(rs, o + 2), uuid(rs, o + 3), uuid(rs, o + 4), uuid(rs, o + 5),
                uuid(rs, o + 6), uuid(rs, o + 7), uuid(rs, o + 8), uuid(rs, o + 9), uuid(rs, o + 10), rs.getLong(o + 11),
                uuid(rs, o + 12), uuid(rs, o + 13), uuid(rs, o + 14), rs.getString(o + 15), instant(rs, o + 16),
                instant(rs, o + 17), rs.getString(o + 18), rs.getString(o + 19), instant(rs, o + 20),
                rs.getString(o + 21), instant(rs, o + 22), rs.getInt(o + 23), rs.getString(o + 24),
                rs.getString(o + 25), rs.getString(o + 26));
    }

    // ---------------------------------------------------------------- publication (caller's transaction)

    /** The tc source snapshot, from the complete candidate. Its origin is the candidate, not a first-snapshot job. */
    int insertCandidateSource(UUID sourceSnapshotId, Candidate candidate) {
        return jdbc.sql("INSERT INTO sync_source_snapshot(source_snapshot_id,user_id,connection_id,provider_account_id,"
                + "job_id,initial_candidate_id,provider,equity_decimal,measured_at,received_at,input_status,policy_version) "
                + "VALUES (:id,:u,:c,:a,NULL,:cand,'BYBIT',:equity,:measured,:received,'VERIFIED',:policy)")
            .param("id", sourceSnapshotId).param("u", candidate.userId()).param("c", candidate.connectionId())
            .param("a", candidate.providerAccountId()).param("cand", candidate.candidateId())
            .param("equity", candidate.equity()).param("measured", utc(candidate.measuredAt()))
            .param("received", utc(candidate.receivedAt())).param("policy", candidate.policyVersion()).update();
    }

    /**
     * The source snapshot of a wallet observation a completion or post-end attempt read itself (Story 2.14): its origin
     * is that attempt, not a candidate or a first-snapshot job.
     */
    int insertObservationSource(UUID sourceSnapshotId, UUID user, UUID connectionId, UUID providerAccountId,
            UUID planSyncJobId, String equity, Instant measuredAt, Instant receivedAt, String policyVersion) {
        return jdbc.sql("INSERT INTO sync_source_snapshot(source_snapshot_id,user_id,connection_id,provider_account_id,"
                + "job_id,initial_candidate_id,plan_sync_job_id,provider,equity_decimal,measured_at,received_at,"
                + "input_status,policy_version) VALUES (:id,:u,:c,:a,NULL,NULL,:j,'BYBIT',:equity,:measured,:received,"
                + "'VERIFIED',:policy)")
            .param("id", sourceSnapshotId).param("u", user).param("c", connectionId).param("a", providerAccountId)
            .param("j", planSyncJobId).param("equity", equity).param("measured", utc(measuredAt))
            .param("received", utc(receivedAt)).param("policy", policyVersion).update();
    }

    /**
     * One calculation version to write. {@code lastExtendedVerificationAt} is its second watermark (a first
     * publication verified all of {@code (t0, tc]} and records {@code tc}); {@code previousCalculationId} is the
     * version it replaces, {@code null} for a first publication.
     */
    record CalculationRow(UUID calculationId, UUID jobId, UUID planId, Pinned pinned, UUID portfolioSnapshotId,
            long portfolioSequence, UUID sourceSnapshotId, String classificationPolicy, String pricePolicy,
            String sourcePolicy, PlanCalculator.Result.Calculated result, int flowCount, int estimatedFlowCount,
            Instant lastExtendedVerificationAt, @Nullable UUID previousCalculationId, String kind, Instant basisAt,
            Instant verifiedThrough) {

        /**
         * A progress version: its basis and its cash-flow verification are both the pinned {@code tc} (V10's rule).
         */
        CalculationRow(UUID calculationId, UUID jobId, UUID planId, Pinned pinned, UUID portfolioSnapshotId,
                long portfolioSequence, UUID sourceSnapshotId, String classificationPolicy, String pricePolicy,
                String sourcePolicy, PlanCalculator.Result.Calculated result, int flowCount, int estimatedFlowCount,
                Instant lastExtendedVerificationAt, @Nullable UUID previousCalculationId) {
            this(calculationId, jobId, planId, pinned, portfolioSnapshotId, portfolioSequence, sourceSnapshotId,
                    classificationPolicy, pricePolicy, sourcePolicy, result, flowCount, estimatedFlowCount,
                    lastExtendedVerificationAt, previousCalculationId, PROGRESS, pinned.tc(), pinned.tc());
        }
    }

    /** The calculation kinds (V14): a progress version, or the completion version at {@code u = T} (Story 2.14). */
    static final String PROGRESS = "PROGRESS";
    static final String COMPLETION = "COMPLETION";

    void insertCalculation(CalculationRow row) {
        Pinned p = row.pinned();
        PlanCalculator.Result.Calculated r = row.result();
        jdbc.sql("INSERT INTO sync_plan_calculation(calculation_id,created_by_job_id,user_id,plan_id,portfolio_id,provider,"
                + "provider_account_id,connection_id,membership_id,basis_portfolio_snapshot_id,basis_source_snapshot_id,"
                + "portfolio_snapshot_id,portfolio_publication_sequence,source_snapshot_id,candidate_id,classification_id,"
                + "conversion_id,classification_policy_version,price_policy_version,source_policy_version,calculation_policy,"
                + "calendar_policy,display_policy,precision_policy,time_zone,implementation_version,p0_decimal,t0,end_at,"
                + "period,target_rate,basis_at,current_value_measured_at,cashflow_verified_through,adjusted_principal,"
                + "periods_elapsed,periods_total,periods_remaining,flow_count,estimated_flow_count,estimated,"
                + "input_reliability,input_hash,result_hash,last_extended_verification_at,previous_calculation_id,"
                + "calculation_kind) "
                + "VALUES (:id,:job,:u,:plan,:p,'BYBIT',:a,:c,:m,:bs,:bss,:ps,"
                + ":pseq,:ss,:cand,:cls,:conv,:clsPolicy,:pricePolicy,:sourcePolicy,:calc,:calendar,:display,:precision,"
                + ":zone,:impl,:p0,:t0,:end,:period,:rate,:tc,:measured,:verified,:ap,:elapsed,:total,:remaining,:flows,"
                + ":estimatedFlows,:estimated,:reliability,:inputHash,:resultHash,:extended,:previous,:kind)")
            .param("id", row.calculationId()).param("job", row.jobId()).param("u", p.userId()).param("plan", row.planId())
            .param("p", p.portfolioId()).param("a", p.providerAccountId()).param("c", p.connectionId())
            .param("m", p.membershipId()).param("bs", p.basisPortfolioSnapshotId())
            .param("bss", p.basisSourceSnapshotId()).param("ps", row.portfolioSnapshotId())
            .param("pseq", row.portfolioSequence()).param("ss", row.sourceSnapshotId()).param("cand", p.candidateId())
            .param("cls", p.classificationId()).param("conv", p.conversionId())
            .param("clsPolicy", row.classificationPolicy()).param("pricePolicy", row.pricePolicy())
            .param("sourcePolicy", row.sourcePolicy()).param("calc", r.calculationPolicy())
            .param("calendar", r.calendarPolicy()).param("display", r.displayPolicy())
            .param("precision", r.precisionPolicy()).param("zone", r.timeZone()).param("impl", r.implementationVersion())
            .param("p0", p.principal()).param("t0", utc(p.t0())).param("end", utc(p.endAt())).param("period", p.period())
            .param("rate", p.targetRate()).param("tc", utc(row.basisAt())).param("measured", utc(p.measuredAt()))
            .param("verified", utc(row.verifiedThrough())).param("ap", r.adjustedPrincipal())
            .param("elapsed", r.periodsElapsed())
            .param("total", r.periodsTotal()).param("remaining", r.periodsRemaining()).param("flows", row.flowCount())
            .param("estimatedFlows", row.estimatedFlowCount()).param("estimated", r.estimated())
            .param("reliability", r.inputReliability().name()).param("inputHash", r.inputHash())
            .param("resultHash", r.resultHash()).param("extended", utc(row.lastExtendedVerificationAt()))
            .param("previous", row.previousCalculationId()).param("kind", row.kind()).update();
        for (PlanCalculator.MetricResult metric : r.metrics()) {
            jdbc.sql("INSERT INTO sync_plan_calculation_metric(calculation_id,metric,value_decimal,nature,unavailable_reason) "
                    + "VALUES (:id,:metric,:value,:nature,:reason)")
                .param("id", row.calculationId()).param("metric", metric.metric().name()).param("value", metric.value())
                .param("nature", metric.nature().name())
                .param("reason", metric.unavailableReason() == null ? null : metric.unavailableReason().name()).update();
        }
    }

    void insertFlow(UUID calculationId, UUID conversionId, UUID providerAccountId, Flow flow,
            PlanCalculator.AppliedCashflow applied) {
        jdbc.sql("INSERT INTO sync_plan_calculation_flow(calculation_id,sequence,conversion_id,source_event_id,"
                + "provider_account_id,source_id,occurred_at,usd_value,estimated,evidence_type,periods_to_basis,"
                + "periods_to_end) VALUES (:id,:seq,:conv,:event,:a,:source,:at,:usd,:estimated,:evidence,:toBasis,:toEnd)")
            .param("id", calculationId).param("seq", applied.sequence()).param("conv", conversionId)
            .param("event", flow.sourceEventId()).param("a", providerAccountId).param("source", flow.sourceId())
            .param("at", utc(flow.occurredAt())).param("usd", applied.usdValue()).param("estimated", flow.estimated())
            .param("evidence", flow.evidenceType()).param("toBasis", applied.periodsToBasis())
            .param("toEnd", applied.periodsToEnd()).update();
    }

    /**
     * The flow difference of a version ({@code :c}) from the one it replaced ({@code :p}) for user {@code :u}, one
     * row per source event (Story 2.12): {@code ADDED} (late when not after the previous version's verification),
     * {@code REMOVED} and {@code VALUE_CHANGED} (the USD value or the estimated flag moved), with both versions'
     * values, the price evidence kind and when the event was first observed. One text for the publication's insert
     * and the evidence's consistency check. V12's backfill carries a copy of it with the two ids as columns; a test
     * runs that copy against rows this one wrote and requires the same rows.
     */
    static final String CHANGE_DIFF = "SELECT COALESCE(n.source_event_id,o.source_event_id) AS source_event_id,"
            + "CASE WHEN o.source_event_id IS NULL THEN 'ADDED' WHEN n.source_event_id IS NULL THEN 'REMOVED' "
            + "ELSE 'VALUE_CHANGED' END AS change_kind,"
            + "COALESCE(n.occurred_at,o.occurred_at)<=prev.cashflow_verified_through AS late,"
            + "COALESCE(n.occurred_at,o.occurred_at) AS occurred_at,o.usd_value AS previous_usd_value,"
            + "o.estimated AS previous_estimated,n.usd_value AS new_usd_value,n.estimated AS new_estimated,"
            + "COALESCE(n.evidence_type,o.evidence_type) AS evidence_type,se.first_observed_at AS discovered_at "
            + "FROM (SELECT f.source_event_id FROM sync_plan_calculation_flow f WHERE f.calculation_id IN (:c,:p) "
            + "GROUP BY f.source_event_id) pairs "
            + "JOIN sync_plan_calculation prev ON prev.calculation_id=:p AND prev.user_id=:u "
            + "LEFT JOIN sync_plan_calculation_flow n ON n.calculation_id=:c AND n.source_event_id=pairs.source_event_id "
            + "LEFT JOIN sync_plan_calculation_flow o ON o.calculation_id=:p AND o.source_event_id=pairs.source_event_id "
            + "JOIN sync_initial_source_event se ON se.event_id=pairs.source_event_id AND se.user_id=:u "
            + "WHERE o.source_event_id IS NULL OR n.source_event_id IS NULL "
            + "OR o.usd_value::numeric IS DISTINCT FROM n.usd_value::numeric OR o.estimated IS DISTINCT FROM n.estimated";

    /** Writes a new version's change rows ({@link #CHANGE_DIFF}) in its publishing transaction. */
    void insertChanges(UUID user, UUID calculationId, UUID previousCalculationId) {
        jdbc.sql("INSERT INTO sync_plan_calculation_change(calculation_id,source_event_id,user_id,previous_calculation_id,"
                + "change_kind,late,occurred_at,previous_usd_value,previous_estimated,new_usd_value,new_estimated,"
                + "evidence_type,discovered_at) SELECT :c,d.source_event_id,:u,:p,d.change_kind,d.late,d.occurred_at,"
                + "d.previous_usd_value,d.previous_estimated,d.new_usd_value,d.new_estimated,d.evidence_type,"
                + "d.discovered_at FROM (" + CHANGE_DIFF + ") d")
            .param("c", calculationId).param("u", user).param("p", previousCalculationId).update();
    }

    /** One change row of a version, with the event's Bybit id and type. */
    record ChangeRow(UUID sourceEventId, String sourceId, String transactionType, Instant occurredAt, String kind,
            boolean late, @Nullable String previousUsd, @Nullable Boolean previousEstimated, @Nullable String newUsd,
            @Nullable Boolean newEstimated, String evidenceType, Instant discoveredAt, UUID previousCalculationId) {}

    /** A version's change rows, in event order. Empty for a first publication. */
    List<ChangeRow> changes(UUID user, UUID calculationId) {
        return jdbc.sql("SELECT ch.source_event_id,se.source_id,se.event_type,ch.occurred_at,ch.change_kind,ch.late,"
                + "ch.previous_usd_value,ch.previous_estimated,ch.new_usd_value,ch.new_estimated,ch.evidence_type,"
                + "ch.discovered_at,ch.previous_calculation_id FROM sync_plan_calculation_change ch "
                + "JOIN sync_initial_source_event se ON se.event_id=ch.source_event_id AND se.user_id=ch.user_id "
                + "WHERE ch.calculation_id=:c AND ch.user_id=:u ORDER BY ch.occurred_at,se.source_id")
            .param("c", calculationId).param("u", user)
            .query((rs, n) -> new ChangeRow(uuid(rs, 1), rs.getString(2), rs.getString(3), instant(rs, 4),
                    rs.getString(5), rs.getBoolean(6), rs.getString(7), (Boolean) rs.getObject(8), rs.getString(9),
                    (Boolean) rs.getObject(10), rs.getString(11), instant(rs, 12), uuid(rs, 13)))
            .list();
    }

    /**
     * Whether a version's change rows are exactly its {@link #CHANGE_DIFF}: as many rows as the difference has, each
     * one equal to it in every stored field — the predecessor, the kind, the late flag, the event time, both USD
     * values and estimated flags, the price evidence kind and the discovery time. The evidence, the history and the
     * dashboard's change marker rely on it.
     */
    boolean changesConsistent(UUID user, UUID calculationId, UUID previousCalculationId) {
        return jdbc.sql("WITH diff AS (" + CHANGE_DIFF + ") "
                + "SELECT (SELECT count(*) FROM diff)=(SELECT count(*) FROM sync_plan_calculation_change "
                + "WHERE calculation_id=:c AND user_id=:u) AND NOT EXISTS (SELECT 1 FROM sync_plan_calculation_change ch "
                + "LEFT JOIN diff d ON d.source_event_id=ch.source_event_id WHERE ch.calculation_id=:c AND ch.user_id=:u "
                + "AND (d.source_event_id IS NULL OR ch.previous_calculation_id IS DISTINCT FROM :p "
                + "OR ch.change_kind IS DISTINCT FROM d.change_kind OR ch.late IS DISTINCT FROM d.late "
                + "OR ch.occurred_at IS DISTINCT FROM d.occurred_at "
                + "OR ch.previous_usd_value::numeric IS DISTINCT FROM d.previous_usd_value::numeric "
                + "OR ch.previous_estimated IS DISTINCT FROM d.previous_estimated "
                + "OR ch.new_usd_value::numeric IS DISTINCT FROM d.new_usd_value::numeric "
                + "OR ch.new_estimated IS DISTINCT FROM d.new_estimated "
                + "OR ch.evidence_type IS DISTINCT FROM d.evidence_type "
                + "OR ch.discovered_at IS DISTINCT FROM d.discovered_at))")
            .param("c", calculationId).param("u", user).param("p", previousCalculationId).query(Boolean.class).single();
    }

    /**
     * The plan's first pointer: an expected-version CAS against "no pointer yet" (version 0). A second
     * writer for the same plan inserts nothing and the caller rolls its publication back.
     */
    int pointFirst(UUID planId, UUID user, UUID portfolioId, UUID calculationId) {
        int inserted = jdbc.sql("INSERT INTO sync_official_plan_result(plan_id,user_id,portfolio_id,calculation_id,"
                + "pointer_version) VALUES (:plan,:u,:p,:calc,1) ON CONFLICT (plan_id) DO NOTHING")
            .param("plan", planId).param("u", user).param("p", portfolioId).param("calc", calculationId).update();
        if (inserted == 1) recordHistory(planId);
        return inserted;
    }

    /**
     * The next pointer (Story 2.11): an expected-version CAS. It moves only while the pointer still names the
     * version and calculation the sync read at admission, to the next version and the new calculation, and the
     * move is recorded in the append-only history in the same transaction. Zero when the pointer moved.
     *
     * <p>The publication time is the statement's, not the transaction's: the publishing transaction may have
     * waited on the plan lock, and {@code now()} would date the version before the one it replaces committed.
     */
    int pointNext(UUID planId, UUID user, long expectedVersion, UUID expectedCalculationId, UUID calculationId) {
        int moved = jdbc.sql("UPDATE sync_official_plan_result SET calculation_id=:calc,pointer_version=pointer_version+1,"
                + "published_at=date_trunc('milliseconds',statement_timestamp()) WHERE plan_id=:plan AND user_id=:u "
                + "AND pointer_version=:v AND calculation_id=:expected")
            .param("calc", calculationId).param("plan", planId).param("u", user).param("v", expectedVersion)
            .param("expected", expectedCalculationId).update();
        if (moved == 1) recordHistory(planId);
        return moved;
    }

    /** Appends the pointer's current version to its history. Never updates an earlier row. */
    private void recordHistory(UUID planId) {
        jdbc.sql("INSERT INTO sync_official_plan_result_history(plan_id,pointer_version,user_id,portfolio_id,"
                + "calculation_id,published_at) SELECT plan_id,pointer_version,user_id,portfolio_id,calculation_id,"
                + "published_at FROM sync_official_plan_result WHERE plan_id=:plan")
            .param("plan", planId).update();
    }

    /** One row of the official pointer's history. */
    record HistoryRow(long pointerVersion, UUID calculationId, UUID portfolioId, Instant publishedAt) {}

    /** The plan's official versions, oldest first. */
    List<HistoryRow> history(UUID user, UUID planId) {
        return jdbc.sql("SELECT pointer_version,calculation_id,portfolio_id,published_at "
                + "FROM sync_official_plan_result_history WHERE plan_id=:plan AND user_id=:u ORDER BY pointer_version")
            .param("plan", planId).param("u", user)
            .query((rs, n) -> new HistoryRow(rs.getLong(1), uuid(rs, 2), uuid(rs, 3), instant(rs, 4))).list();
    }

    // ---------------------------------------------------------------- official result (through the pointer)

    /*
     * The dashboard reads below run in one read-only REPEATABLE READ transaction the service owns: they
     * see one snapshot of the database, so the plan, its pointer, the version it cites and that version's
     * metrics cannot come from two different moments. Nothing here reads candidate, held or review rows.
     */

    /**
     * Whether any of the user's publication attempts is queued or running — whatever its intent. With no
     * active plan this is the dashboard's "result being prepared"; a finished attempt (published, blocked
     * or failed) does not count.
     */
    boolean successorWorkInProgress(UUID user) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_job WHERE user_id=:u AND purpose='NEXT_PLAN_BASELINE' AND status IN ('QUEUED','RUNNING')) OR EXISTS(SELECT 1 FROM sync_plan_publication_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) OR EXISTS(SELECT 1 FROM sync_initial_collection_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) OR EXISTS(SELECT 1 FROM sync_cashflow_classification_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) OR EXISTS(SELECT 1 FROM sync_cashflow_conversion_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING'))")
            .param("u",user).query(Boolean.class).single();
    }
    boolean publicationInProgress(UUID user) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_plan_publication_job WHERE user_id=:u "
                + "AND status IN ('QUEUED','RUNNING'))")
            .param("u", user).query(Boolean.class).single();
    }

    record Pointer(UUID portfolioId, UUID calculationId, long version, Instant publishedAt) {}

    Optional<Pointer> pointer(UUID user, UUID planId) {
        return jdbc.sql("SELECT portfolio_id,calculation_id,pointer_version,published_at FROM sync_official_plan_result "
                + "WHERE plan_id=:plan AND user_id=:u")
            .param("plan", planId).param("u", user)
            .query((rs, n) -> new Pointer(uuid(rs, 1), uuid(rs, 2), rs.getLong(3), instant(rs, 4))).optional();
    }

    record CalculationView(UUID planId, UUID portfolioId, UUID portfolioSnapshotId, long portfolioSequence,
            UUID basisPortfolioSnapshotId, String principal, Instant t0, Instant endAt, String period,
            String targetRate, Instant basisAt, Instant measuredAt, Instant verifiedThrough, String calculationPolicy,
            String calendarPolicy, String displayPolicy, String pricePolicy, String implementationVersion,
            String inputHash, String resultHash, int flowCount, int estimatedFlowCount, boolean estimated,
            String inputReliability, String kind) {

        /** A progress version (every version before Story 2.14). */
        CalculationView(UUID planId, UUID portfolioId, UUID portfolioSnapshotId, long portfolioSequence,
                UUID basisPortfolioSnapshotId, String principal, Instant t0, Instant endAt, String period,
                String targetRate, Instant basisAt, Instant measuredAt, Instant verifiedThrough,
                String calculationPolicy, String calendarPolicy, String displayPolicy, String pricePolicy,
                String implementationVersion, String inputHash, String resultHash, int flowCount,
                int estimatedFlowCount, boolean estimated, String inputReliability) {
            this(planId, portfolioId, portfolioSnapshotId, portfolioSequence, basisPortfolioSnapshotId, principal, t0,
                    endAt, period, targetRate, basisAt, measuredAt, verifiedThrough, calculationPolicy, calendarPolicy,
                    displayPolicy, pricePolicy, implementationVersion, inputHash, resultHash, flowCount,
                    estimatedFlowCount, estimated, inputReliability, PROGRESS);
        }

        /** The completion version of a plan that reached its end (Story 2.14): computed at {@code u = T}. */
        boolean completion() {
            return COMPLETION.equals(this.kind);
        }

        /**
         * Where the version's applied flows end: its basis — {@code tc} for a progress version, whose flows are verified
         * through it, and {@code T} for a completion version, whose flows after {@code T} are verified but not applied.
         */
        Instant appliedThrough() {
            return this.basisAt;
        }
    }

    Optional<CalculationView> calculation(UUID user, UUID calculationId) {
        return jdbc.sql("SELECT plan_id,portfolio_id,portfolio_snapshot_id,portfolio_publication_sequence,"
                + "basis_portfolio_snapshot_id,p0_decimal,t0,end_at,period,target_rate,basis_at,current_value_measured_at,"
                + "cashflow_verified_through,calculation_policy,calendar_policy,display_policy,price_policy_version,"
                + "implementation_version,input_hash,result_hash,flow_count,estimated_flow_count,estimated,"
                + "input_reliability,calculation_kind FROM sync_plan_calculation WHERE calculation_id=:c AND user_id=:u")
            .param("c", calculationId).param("u", user)
            .query((rs, n) -> new CalculationView(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), rs.getLong(4), uuid(rs, 5),
                    rs.getString(6), instant(rs, 7), instant(rs, 8), rs.getString(9), rs.getString(10), instant(rs, 11),
                    instant(rs, 12), instant(rs, 13), rs.getString(14), rs.getString(15), rs.getString(16),
                    rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20), rs.getInt(21),
                    rs.getInt(22), rs.getBoolean(23), rs.getString(24), rs.getString(25))).optional();
    }

    /** The version's metrics in the screen's order. The caller checks that exactly the six came back. */
    List<DashboardMetric> metrics(UUID calculationId) {
        return jdbc.sql("SELECT metric,value_decimal,nature,unavailable_reason FROM sync_plan_calculation_metric "
                + "WHERE calculation_id=:c ORDER BY CASE metric WHEN 'CURRENT_VALUE' THEN 1 WHEN 'PLAN_AMOUNT' THEN 2 "
                + "WHEN 'PLAN_DIFFERENCE' THEN 3 WHEN 'ADJUSTED_TARGET' THEN 4 WHEN 'REQUIRED_RATE' THEN 5 "
                + "WHEN 'GOAL_PROGRESS' THEN 6 ELSE 7 END,metric")
            .param("c", calculationId)
            .query((rs, n) -> new DashboardMetric(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
            .list();
    }

    /**
     * SYNC-UX-1.0 facts against the database clock: whether any of the user's jobs is in progress, and
     * whether each of the two times is at most 15 minutes old. The judgment itself is
     * {@link DashboardTrust}'s; the clock is the database's alone, never the application's or the client's.
     */
    DashboardTrust.Freshness freshness(UUID user, Instant measuredAt, Instant verifiedThrough) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) "
                + "OR EXISTS(SELECT 1 FROM sync_initial_collection_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) "
                + "OR EXISTS(SELECT 1 FROM sync_cashflow_classification_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) "
                + "OR EXISTS(SELECT 1 FROM sync_cashflow_conversion_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) "
                + "OR EXISTS(SELECT 1 FROM sync_plan_publication_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) "
                + "OR EXISTS(SELECT 1 FROM sync_plan_sync_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')),"
                + "statement_timestamp()-:measured<=interval '15 minutes',"
                + "statement_timestamp()-:verified<=interval '15 minutes'")
            .param("u", user).param("measured", utc(measuredAt)).param("verified", utc(verifiedThrough))
            .query((rs, n) -> new DashboardTrust.Freshness(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)))
            .single();
    }

    // ---------------------------------------------------------------- calculation evidence (Story 2.9)

    /*
     * The evidence reads below run in the same kind of read-only REPEATABLE READ transaction as the
     * dashboard's, after the pointer and the version were read there. Each is scoped to the user, and each
     * reads by the id the calculation version recorded — never "the latest" row of a kind. They read this
     * module's own tables only, and never a candidate's events, raw provider fields or credentials.
     */

    /**
     * The lineage columns of a calculation version that the dashboard does not read.
     *
     * @param lastExtendedAt the version's second watermark: the last extended cash-flow verification
     * @param previousCalculationId the version it replaced (Story 2.11), {@code null} for a first publication
     */
    record Lineage(UUID createdByJobId, String provider, UUID providerAccountId, UUID connectionId,
            UUID membershipId, UUID basisSourceSnapshotId, UUID sourceSnapshotId, UUID candidateId,
            UUID classificationId, UUID conversionId, String classificationPolicy, String sourcePolicy,
            String precisionPolicy, String timeZone, Instant createdAt, Instant lastExtendedAt,
            @Nullable UUID previousCalculationId) {}

    Optional<Lineage> lineage(UUID user, UUID calculationId) {
        return jdbc.sql("SELECT created_by_job_id,provider,provider_account_id,connection_id,membership_id,"
                + "basis_source_snapshot_id,source_snapshot_id,candidate_id,classification_id,conversion_id,"
                + "classification_policy_version,source_policy_version,precision_policy,time_zone,created_at,"
                + "last_extended_verification_at,previous_calculation_id "
                + "FROM sync_plan_calculation WHERE calculation_id=:c AND user_id=:u")
            .param("c", calculationId).param("u", user)
            .query((rs, n) -> new Lineage(uuid(rs, 1), rs.getString(2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5),
                    uuid(rs, 6), uuid(rs, 7), uuid(rs, 8), uuid(rs, 9), uuid(rs, 10), rs.getString(11),
                    rs.getString(12), rs.getString(13), rs.getString(14), instant(rs, 15), instant(rs, 16),
                    rs.getObject(17, UUID.class))).optional();
    }

    /**
     * The attempt that created a version: its ending, what it published, and the inputs it pinned at
     * admission — the version must have been calculated from exactly those.
     */
    record CreatingAttempt(String status, @Nullable UUID planId, @Nullable UUID calculationId, Pinned pinned) {}

    Optional<CreatingAttempt> creatingAttempt(UUID user, UUID jobId) {
        return jdbc.sql("SELECT status,plan_id,calculation_id," + PINNED_COLUMNS + " FROM sync_plan_publication_job "
                + "WHERE job_id=:j AND user_id=:u")
            .param("j", jobId).param("u", user)
            .query((rs, n) -> new CreatingAttempt(rs.getString(1), rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class), pinnedOf(rs, 3))).optional();
    }

    /**
     * A source snapshot's origin and what it holds: the complete candidate it was built from, or — for a wallet
     * observation of a completion or post-end attempt (Story 2.14) — that attempt ({@code null} both for one a
     * first-snapshot job wrote), its provenance, the source policy version it was written under, and its stored value
     * and times (all three are required columns).
     */
    record SourceOrigin(@Nullable UUID candidateId, UUID connectionId, UUID providerAccountId, Instant measuredAt,
            String policyVersion, @Nullable UUID planSyncJobId, String equity, Instant receivedAt) {}

    /**
     * A source snapshot's origin: the candidate, or — for a wallet observation of a completion or post-end attempt
     * (Story 2.14) — that attempt; neither for a first-snapshot job's.
     */
    Optional<SourceOrigin> sourceOrigin(UUID user, UUID sourceSnapshotId) {
        return jdbc.sql("SELECT initial_candidate_id,connection_id,provider_account_id,measured_at,policy_version,"
                + "plan_sync_job_id,equity_decimal,received_at "
                + "FROM sync_source_snapshot WHERE source_snapshot_id=:s AND user_id=:u")
            .param("s", sourceSnapshotId).param("u", user)
            .query((rs, n) -> new SourceOrigin(rs.getObject(1, UUID.class), uuid(rs, 2), uuid(rs, 3), instant(rs, 4),
                    rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), instant(rs, 8)))
            .optional();
    }

    /**
     * How many of a calculable conversion's flows lie in {@code (from, through]}, how many of them are estimated and
     * how many are deposits: the flows a completion version (Story 2.14) applies, out of a conversion of
     * {@code (t0, U]}.
     */
    record WithinCounts(int count, int estimated, int inbound) {}

    WithinCounts calculableWithin(UUID user, UUID conversionId, Instant from, Instant through) {
        // Inbound is the adjustment history's own predicate (AdjustmentHistoryRepository#totals): not signed negative.
        return jdbc.sql("SELECT count(*),count(*) FILTER (WHERE estimated),count(*) FILTER (WHERE usd_value !~ '^-') "
                + "FROM sync_calculable_cashflow WHERE conversion_id=:c AND user_id=:u AND occurred_at>:f "
                + "AND occurred_at<=:t")
            .param("c", conversionId).param("u", user).param("f", utc(from)).param("t", utc(through))
            .query((rs, n) -> new WithinCounts(rs.getInt(1), rs.getInt(2), rs.getInt(3))).single();
    }

    /** The collected range of a candidate: its collection's earliest page start and latest page end. */
    record CollectionRange(Instant start, Instant end) {}

    /** Empty when the candidate is not the user's or its collection left no page evidence. */
    Optional<CollectionRange> collectionRange(UUID user, UUID candidateId) {
        return jdbc.sql("SELECT min(e.range_start),max(e.range_end) FROM sync_initial_candidate_bundle b "
                + "JOIN sync_initial_page_evidence e ON e.job_id=b.job_id AND e.user_id=b.user_id "
                + "WHERE b.candidate_id=:c AND b.user_id=:u HAVING count(*)>0")
            .param("c", candidateId).param("u", user)
            .query((rs, n) -> new CollectionRange(instant(rs, 1), instant(rs, 2))).optional();
    }

    /** A classification bundle by its id: its lineage, ending, policy and input range hash. */
    record ClassificationBundle(UUID candidateId, UUID portfolioId, UUID intentId, UUID connectionId,
            UUID providerAccountId, String status, String policyVersion, String inputScopeHash) {}

    Optional<ClassificationBundle> classificationBundle(UUID user, UUID classificationId) {
        return jdbc.sql("SELECT candidate_id,portfolio_id,intent_id,connection_id,provider_account_id,status,"
                + "policy_version,input_scope_hash FROM sync_cashflow_classification_bundle "
                + "WHERE classification_id=:c AND user_id=:u")
            .param("c", classificationId).param("u", user)
            .query((rs, n) -> new ClassificationBundle(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4),
                    uuid(rs, 5), rs.getString(6), rs.getString(7), rs.getString(8))).optional();
    }

    /** A classification's external flows: its deposits and withdrawals. Empty when it is not this user's. */
    Optional<Integer> externalFlowCount(UUID user, UUID classificationId) {
        return jdbc.sql("SELECT deposit_count+withdrawal_count FROM sync_cashflow_classification_bundle "
                + "WHERE classification_id=:c AND user_id=:u")
            .param("c", classificationId).param("u", user).query(Integer.class).optional();
    }

    /** A conversion bundle by its id — the one a version recorded, not the classification's latest. */
    Optional<Conversion> conversion(UUID user, UUID conversionId) {
        return jdbc.sql("SELECT conversion_id,classification_id,user_id,intent_id,portfolio_id,connection_id,"
                + "provider_account_id,status,calculable,outcome_fingerprint,policy_version,flow_count,estimated_count "
                + "FROM sync_cashflow_conversion_bundle WHERE conversion_id=:c AND user_id=:u")
            .param("c", conversionId).param("u", user)
            .query((rs, n) -> new Conversion(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5),
                    uuid(rs, 6), uuid(rs, 7), rs.getString(8), rs.getBoolean(9), rs.getString(10), rs.getString(11),
                    rs.getInt(12), rs.getInt(13))).optional();
    }

    /**
     * What a version's applied cash-flow history adds up to: its rows, the estimated ones, the rows that
     * cite a conversion other than {@code conversionId}, and the price evidence kinds of the estimated
     * ones. Counts only — no event, amount or time leaves here.
     */
    record AppliedFlowCounts(int count, int estimated, int foreign, List<String> estimatedEvidence) {}

    AppliedFlowCounts appliedFlowCounts(UUID calculationId, UUID conversionId) {
        int[] counts = jdbc.sql("SELECT count(*),count(*) FILTER (WHERE estimated),"
                + "count(*) FILTER (WHERE conversion_id<>:conv) FROM sync_plan_calculation_flow WHERE calculation_id=:c")
            .param("c", calculationId).param("conv", conversionId)
            .query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)}).single();
        List<String> evidence = jdbc.sql("SELECT DISTINCT evidence_type FROM sync_plan_calculation_flow "
                + "WHERE calculation_id=:c AND estimated ORDER BY evidence_type")
            .param("c", calculationId).query(String.class).list();
        return new AppliedFlowCounts(counts[0], counts[1], counts[2], evidence);
    }

    /**
     * The intent's attempts that ended without publishing ({@code FAILED} or {@code BLOCKED}), oldest
     * first: their status, closed reason and finish time. Neither an id nor any pinned input is read.
     */
    List<UnpublishedAttempt> unpublished(UUID user, UUID intentId) {
        return jdbc.sql("SELECT status,failure_code,finished_at FROM sync_plan_publication_job WHERE user_id=:u "
                + "AND intent_id=:i AND status IN ('FAILED','BLOCKED') ORDER BY created_at,job_id")
            .param("u", user).param("i", intentId)
            .query((rs, n) -> new UnpublishedAttempt(rs.getString(1),
                    rs.getString(2), instant(rs, 3))).list();
    }

    // ---------------------------------------------------------------- erasure

    /**
     * Erases this user's pointers, attempts, calculation versions (with their metrics and cash-flow
     * history) and tc source snapshots. Runs before the rest of the sync erasure: these rows cite the
     * candidate, classification and conversion evidence with ON DELETE RESTRICT.
     */
    void erase(UUID user) {
        jdbc.sql("DELETE FROM sync_official_plan_result WHERE user_id=:u").param("u", user).update();
        jdbc.sql("DELETE FROM sync_official_plan_result_history WHERE user_id=:u").param("u", user).update();
        jdbc.sql("DELETE FROM sync_plan_publication_job WHERE user_id=:u").param("u", user).update();
        jdbc.sql("DELETE FROM sync_plan_calculation WHERE user_id=:u").param("u", user).update();
        jdbc.sql("DELETE FROM sync_source_snapshot WHERE user_id=:u AND initial_candidate_id IS NOT NULL")
            .param("u", user).update();
        // A completion's or post-end value's wallet observation (Story 2.14): before the attempts it names.
        jdbc.sql("DELETE FROM sync_source_snapshot WHERE user_id=:u AND plan_sync_job_id IS NOT NULL")
            .param("u", user).update();
    }

    // ---------------------------------------------------------------- mapping

    private static UUID uuid(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant instantOrNull(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
