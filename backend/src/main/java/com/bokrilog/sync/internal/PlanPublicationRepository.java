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

    /** The complete initial candidate whose totalEquity is E(tc). */
    record Candidate(UUID candidateId, UUID userId, UUID portfolioId, UUID intentId, UUID connectionId,
            UUID providerAccountId, Instant t0, Instant tc, String equity, Instant measuredAt, Instant receivedAt,
            String policyVersion, String completenessHash) {}

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
                + "equity_decimal,measured_at,received_at,policy_version,completeness_hash "
                + "FROM sync_initial_candidate_bundle WHERE candidate_id=:c AND user_id=:u")
            .param("c", candidateId).param("u", user)
            .query((rs, n) -> new Candidate(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5),
                    uuid(rs, 6), instant(rs, 7), instant(rs, 8), rs.getString(9), instant(rs, 10), instant(rs, 11),
                    rs.getString(12), rs.getString(13))).optional();
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
     * How a version's cash flows were verified (Story 2.11): {@code INITIAL} collected {@code (t0, tc]} whole;
     * {@code NORMAL} and {@code EXTENDED} collected {@code (rangeStart, tc]} and carried the previous version's
     * flows at or before {@code rangeStart}. {@code lastExtendedAt} is the plan's last extended verification as
     * of this version.
     */
    record Collection(String mode, Instant rangeStart, Instant lastExtendedAt, @Nullable UUID previousCalculationId) {
        /** The first calculation: it verified from {@code t0}, so its extended verification is its {@code tc}. */
        static Collection initial(Instant t0, Instant tc) {
            return new Collection("INITIAL", t0, tc, null);
        }
    }

    record CalculationRow(UUID calculationId, UUID jobId, UUID planId, Pinned pinned, UUID portfolioSnapshotId,
            long portfolioSequence, UUID sourceSnapshotId, String classificationPolicy, String pricePolicy,
            String sourcePolicy, PlanCalculator.Result.Calculated result, int flowCount, int estimatedFlowCount,
            Collection collection) {}

    void insertCalculation(CalculationRow row) {
        Pinned p = row.pinned();
        PlanCalculator.Result.Calculated r = row.result();
        Collection collection = row.collection();
        jdbc.sql("INSERT INTO sync_plan_calculation(calculation_id,created_by_job_id,user_id,plan_id,portfolio_id,provider,"
                + "provider_account_id,connection_id,membership_id,basis_portfolio_snapshot_id,basis_source_snapshot_id,"
                + "portfolio_snapshot_id,portfolio_publication_sequence,source_snapshot_id,candidate_id,classification_id,"
                + "conversion_id,classification_policy_version,price_policy_version,source_policy_version,calculation_policy,"
                + "calendar_policy,display_policy,precision_policy,time_zone,implementation_version,p0_decimal,t0,end_at,"
                + "period,target_rate,basis_at,current_value_measured_at,cashflow_verified_through,adjusted_principal,"
                + "periods_elapsed,periods_total,periods_remaining,flow_count,estimated_flow_count,estimated,"
                + "input_reliability,input_hash,result_hash,collection_mode,collection_range_start,"
                + "last_extended_verification_at,previous_calculation_id) VALUES (:id,:job,:u,:plan,:p,'BYBIT',:a,:c,:m,"
                + ":bs,:bss,:ps,:pseq,:ss,:cand,:cls,:conv,:clsPolicy,:pricePolicy,:sourcePolicy,:calc,:calendar,:display,"
                + ":precision,:zone,:impl,:p0,:t0,:end,:period,:rate,:tc,:measured,:verified,:ap,:elapsed,:total,"
                + ":remaining,:flows,:estimatedFlows,:estimated,:reliability,:inputHash,:resultHash,:mode,:rangeStart,"
                + ":lastExtended,:previous)")
            .param("mode", collection.mode()).param("rangeStart", utc(collection.rangeStart()))
            .param("lastExtended", utc(collection.lastExtendedAt())).param("previous", collection.previousCalculationId())
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
            .param("rate", p.targetRate()).param("tc", utc(p.tc())).param("measured", utc(p.measuredAt()))
            .param("verified", utc(p.tc())).param("ap", r.adjustedPrincipal()).param("elapsed", r.periodsElapsed())
            .param("total", r.periodsTotal()).param("remaining", r.periodsRemaining()).param("flows", row.flowCount())
            .param("estimatedFlows", row.estimatedFlowCount()).param("estimated", r.estimated())
            .param("reliability", r.inputReliability().name()).param("inputHash", r.inputHash())
            .param("resultHash", r.resultHash()).update();
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
     * The plan's first pointer: an expected-version CAS against "no pointer yet" (version 0). A second
     * writer for the same plan inserts nothing and the caller rolls its publication back. The first official
     * version is recorded in the pointer's history in the same transaction (Story 2.11), with the pointer's
     * own publication time.
     */
    int pointFirst(UUID planId, UUID user, UUID portfolioId, UUID calculationId) {
        Optional<OffsetDateTime> published = jdbc.sql("INSERT INTO sync_official_plan_result(plan_id,user_id,portfolio_id,"
                + "calculation_id,pointer_version) VALUES (:plan,:u,:p,:calc,1) ON CONFLICT (plan_id) DO NOTHING "
                + "RETURNING published_at")
            .param("plan", planId).param("u", user).param("p", portfolioId).param("calc", calculationId)
            .query(OffsetDateTime.class).optional();
        if (published.isEmpty()) return 0;
        return jdbc.sql("INSERT INTO sync_official_plan_result_history(plan_id,pointer_version,user_id,calculation_id,"
                + "published_at) VALUES (:plan,1,:u,:calc,:at)")
            .param("plan", planId).param("u", user).param("calc", calculationId).param("at", published.get()).update();
    }

    /**
     * The source events the classification excluded, recorded as this version's exclusions (Story 2.11).
     * Each cites the classification event it came from. Returns how many were written.
     */
    int insertExclusions(UUID calculationId, UUID classificationId) {
        return jdbc.sql("INSERT INTO sync_plan_calculation_exclusion(calculation_id,classification_id,source_event_id,"
                + "occurred_at) SELECT :calc,classification_id,source_event_id,occurred_at "
                + "FROM sync_cashflow_classification_event WHERE classification_id=:cls AND verdict='EXCLUDED'")
            .param("calc", calculationId).param("cls", classificationId).update();
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
            String inputReliability) {}

    Optional<CalculationView> calculation(UUID user, UUID calculationId) {
        return jdbc.sql("SELECT plan_id,portfolio_id,portfolio_snapshot_id,portfolio_publication_sequence,"
                + "basis_portfolio_snapshot_id,p0_decimal,t0,end_at,period,target_rate,basis_at,current_value_measured_at,"
                + "cashflow_verified_through,calculation_policy,calendar_policy,display_policy,price_policy_version,"
                + "implementation_version,input_hash,result_hash,flow_count,estimated_flow_count,estimated,"
                + "input_reliability FROM sync_plan_calculation WHERE calculation_id=:c AND user_id=:u")
            .param("c", calculationId).param("u", user)
            .query((rs, n) -> new CalculationView(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), rs.getLong(4), uuid(rs, 5),
                    rs.getString(6), instant(rs, 7), instant(rs, 8), rs.getString(9), rs.getString(10), instant(rs, 11),
                    instant(rs, 12), instant(rs, 13), rs.getString(14), rs.getString(15), rs.getString(16),
                    rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20), rs.getInt(21),
                    rs.getInt(22), rs.getBoolean(23), rs.getString(24))).optional();
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
     * SYNC-UX-1.0 facts against the database clock: whether any of the user's jobs is in progress — a plan
     * sync included (Story 2.11) — whether each of the two times is at most 15 minutes old, whether the plan
     * period ended, whether more than thirty days went unverified, and the last sync attempt of the current
     * version: the most recently finished one among those admitted since the pointer became official. The
     * judgment itself is {@link DashboardTrust}'s; the clock is the database's alone, never the application's
     * or the client's. {@code restricted} comes from the operations module and is passed through.
     */
    DashboardTrust.Freshness freshness(UUID user, UUID planId, Instant measuredAt, Instant verifiedThrough,
            Instant endAt, Instant pointerPublishedAt, boolean restricted) {
        var facts = jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) "
                + "OR EXISTS(SELECT 1 FROM sync_initial_collection_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) "
                + "OR EXISTS(SELECT 1 FROM sync_cashflow_classification_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) "
                + "OR EXISTS(SELECT 1 FROM sync_cashflow_conversion_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) "
                + "OR EXISTS(SELECT 1 FROM sync_plan_publication_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) "
                + "OR EXISTS(SELECT 1 FROM sync_plan_sync_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')),"
                + "statement_timestamp()-:measured<=interval '15 minutes',"
                + "statement_timestamp()-:verified<=interval '15 minutes',"
                + "statement_timestamp()>=:end,statement_timestamp()-:verified>interval '30 days'")
            .param("u", user).param("measured", utc(measuredAt)).param("verified", utc(verifiedThrough))
            .param("end", utc(endAt))
            .query((rs, n) -> new boolean[] {rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4),
                    rs.getBoolean(5)})
            .single();
        DashboardTrust.LastAttempt last = jdbc.sql("SELECT status,failure_code,retryable FROM sync_plan_sync_job "
                + "WHERE user_id=:u AND plan_id=:p AND created_at>=:published AND finished_at IS NOT NULL "
                + "AND status<>'PUBLISHED' ORDER BY finished_at DESC,job_id DESC LIMIT 1")
            .param("u", user).param("p", planId).param("published", utc(pointerPublishedAt))
            .query((rs, n) -> new DashboardTrust.LastAttempt(rs.getString(1), rs.getString(2), rs.getBoolean(3)))
            .optional().orElse(null);
        return new DashboardTrust.Freshness(facts[0], facts[1], facts[2], restricted, facts[3], facts[4], last);
    }

    // ---------------------------------------------------------------- calculation evidence (Story 2.9)

    /*
     * The evidence reads below run in the same kind of read-only REPEATABLE READ transaction as the
     * dashboard's, after the pointer and the version were read there. Each is scoped to the user, and each
     * reads by the id the calculation version recorded — never "the latest" row of a kind. They read this
     * module's own tables only, and never a candidate's events, raw provider fields or credentials.
     */

    /**
     * The lineage columns of a calculation version that the dashboard does not read. The candidate, the
     * classification and the conversion are the version's <em>latest</em> lineage: for an incremental version
     * (Story 2.11) they cover {@code (collectionRangeStart, tc]}, and the flows at or before that start are
     * carried from {@code previousCalculationId} with their own lineage.
     */
    record Lineage(UUID createdByJobId, String provider, UUID providerAccountId, UUID connectionId,
            UUID membershipId, UUID basisSourceSnapshotId, UUID sourceSnapshotId, UUID candidateId,
            UUID classificationId, UUID conversionId, String classificationPolicy, String sourcePolicy,
            String precisionPolicy, String timeZone, Instant createdAt, String collectionMode,
            Instant collectionRangeStart, Instant lastExtendedAt, @Nullable UUID previousCalculationId) {
        boolean initial() {
            return "INITIAL".equals(this.collectionMode);
        }
    }

    Optional<Lineage> lineage(UUID user, UUID calculationId) {
        return jdbc.sql("SELECT created_by_job_id,provider,provider_account_id,connection_id,membership_id,"
                + "basis_source_snapshot_id,source_snapshot_id,candidate_id,classification_id,conversion_id,"
                + "classification_policy_version,source_policy_version,precision_policy,time_zone,created_at,"
                + "collection_mode,collection_range_start,last_extended_verification_at,previous_calculation_id "
                + "FROM sync_plan_calculation WHERE calculation_id=:c AND user_id=:u")
            .param("c", calculationId).param("u", user)
            .query((rs, n) -> new Lineage(uuid(rs, 1), rs.getString(2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5),
                    uuid(rs, 6), uuid(rs, 7), uuid(rs, 8), uuid(rs, 9), uuid(rs, 10), rs.getString(11),
                    rs.getString(12), rs.getString(13), rs.getString(14), instant(rs, 15), rs.getString(16),
                    instant(rs, 17), instant(rs, 18), rs.getObject(19, UUID.class))).optional();
    }

    /**
     * The sync attempt that created an incremental version (Story 2.11): its ending, what it published and the
     * basis, range and lineage it recorded. The version must be exactly what it recorded.
     */
    record CreatingSync(String status, UUID planId, @Nullable UUID calculationId, @Nullable Long publishedPointerVersion,
            UUID basisCalculationId, long basisPointerVersion, @Nullable String mode, @Nullable Instant tc,
            @Nullable Instant rangeStart, @Nullable UUID candidateId, @Nullable UUID classificationId,
            @Nullable UUID conversionId, UUID membershipId, UUID connectionId, UUID providerAccountId,
            UUID intentId, UUID portfolioId) {}

    Optional<CreatingSync> creatingSync(UUID user, UUID jobId) {
        return jdbc.sql("SELECT status,plan_id,calculation_id,published_pointer_version,basis_calculation_id,"
                + "basis_pointer_version,mode,tc,range_start,candidate_id,classification_id,conversion_id,membership_id,"
                + "connection_id,provider_account_id,intent_id,portfolio_id FROM sync_plan_sync_job "
                + "WHERE job_id=:j AND user_id=:u")
            .param("j", jobId).param("u", user)
            .query((rs, n) -> new CreatingSync(rs.getString(1), uuid(rs, 2), rs.getObject(3, UUID.class),
                    rs.getObject(4, Long.class), uuid(rs, 5), rs.getLong(6), rs.getString(7), instantOrNull(rs, 8),
                    instantOrNull(rs, 9), rs.getObject(10, UUID.class), rs.getObject(11, UUID.class),
                    rs.getObject(12, UUID.class), uuid(rs, 13), uuid(rs, 14), uuid(rs, 15), uuid(rs, 16),
                    uuid(rs, 17))).optional();
    }

    /**
     * One official version of a plan as the pointer's history recorded it (Story 2.11).
     *
     * @param supersededAt when the next version replaced it; {@code null} for the current one
     */
    record HistoryEntry(long pointerVersion, UUID calculationId, Instant publishedAt, @Nullable Instant supersededAt) {}

    /** The plan's official versions, newest first, at most {@code limit} of them. */
    List<HistoryEntry> history(UUID user, UUID planId, int limit) {
        return jdbc.sql("SELECT pointer_version,calculation_id,published_at,superseded_at FROM "
                + "sync_official_plan_result_history WHERE plan_id=:p AND user_id=:u ORDER BY pointer_version DESC LIMIT :l")
            .param("p", planId).param("u", user).param("l", limit)
            .query((rs, n) -> new HistoryEntry(rs.getLong(1), uuid(rs, 2), instant(rs, 3), instantOrNull(rs, 4))).list();
    }

    /** How many official versions the plan has had. */
    int historyCount(UUID user, UUID planId) {
        return jdbc.sql("SELECT count(*) FROM sync_official_plan_result_history WHERE plan_id=:p AND user_id=:u")
            .param("p", planId).param("u", user).query(Integer.class).single();
    }

    /**
     * What an incremental version carried (Story 2.11): the applied rows citing a conversion other than its
     * own, how many of them are not at or before the collection start, how many have no identical row in the
     * previous version, and how many rows the previous version had at or before that start. The carried rows
     * are whole exactly when the first three agree with the fourth and the middle two are zero.
     */
    record Carried(int rows, int beyondStart, int unmatched, int previousAtOrBeforeStart) {}

    Carried carried(UUID calculationId, UUID conversionId, UUID previousCalculationId, Instant rangeStart) {
        return jdbc.sql("SELECT count(*) FILTER (WHERE pf.conversion_id<>:conv),"
                + "count(*) FILTER (WHERE pf.conversion_id<>:conv AND pf.occurred_at>:start),"
                + "count(*) FILTER (WHERE pf.conversion_id<>:conv AND NOT EXISTS (SELECT 1 FROM sync_plan_calculation_flow pp "
                + "WHERE pp.calculation_id=:prev AND pp.source_event_id=pf.source_event_id AND pp.conversion_id=pf.conversion_id "
                + "AND pp.source_id=pf.source_id AND pp.occurred_at=pf.occurred_at AND pp.usd_value::numeric=pf.usd_value::numeric "
                + "AND pp.estimated=pf.estimated AND pp.evidence_type=pf.evidence_type AND pp.provider_account_id=pf.provider_account_id)),"
                + "(SELECT count(*) FROM sync_plan_calculation_flow pp WHERE pp.calculation_id=:prev AND pp.occurred_at<=:start) "
                + "FROM sync_plan_calculation_flow pf WHERE pf.calculation_id=:c")
            .param("c", calculationId).param("conv", conversionId).param("prev", previousCalculationId)
            .param("start", utc(rangeStart))
            .query((rs, n) -> new Carried(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))).single();
    }

    /**
     * A version's exclusions (Story 2.11): all of them, those citing its own classification, those that do not
     * but sit after its collection start, those that do not and have no identical exclusion in the previous
     * version, and the previous version's exclusions at or before that start.
     */
    record Exclusions(int total, int own, int carriedBeyondStart, int carriedUnmatched, int previousAtOrBeforeStart) {}

    Exclusions exclusions(UUID calculationId, UUID classificationId, @Nullable UUID previousCalculationId,
            Instant rangeStart) {
        return jdbc.sql("SELECT count(*),count(*) FILTER (WHERE x.classification_id=:cls),"
                + "count(*) FILTER (WHERE x.classification_id<>:cls AND x.occurred_at>:start),"
                + "count(*) FILTER (WHERE x.classification_id<>:cls AND NOT EXISTS (SELECT 1 FROM "
                + "sync_plan_calculation_exclusion px WHERE px.calculation_id=:prev AND px.source_event_id=x.source_event_id "
                + "AND px.classification_id=x.classification_id AND px.occurred_at=x.occurred_at)),"
                + "(SELECT count(*) FROM sync_plan_calculation_exclusion px WHERE px.calculation_id=:prev "
                + "AND px.occurred_at<=:start) FROM sync_plan_calculation_exclusion x WHERE x.calculation_id=:c")
            .param("c", calculationId).param("cls", classificationId).param("prev", previousCalculationId)
            .param("start", utc(rangeStart))
            .query((rs, n) -> new Exclusions(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)))
            .single();
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
     * A source snapshot's origin: the complete candidate it was built from ({@code null} for one a
     * first-snapshot job wrote), its provenance and the source policy version it was written under.
     */
    record SourceOrigin(@Nullable UUID candidateId, UUID connectionId, UUID providerAccountId, Instant measuredAt,
            String policyVersion) {}

    Optional<SourceOrigin> sourceOrigin(UUID user, UUID sourceSnapshotId) {
        return jdbc.sql("SELECT initial_candidate_id,connection_id,provider_account_id,measured_at,policy_version "
                + "FROM sync_source_snapshot WHERE source_snapshot_id=:s AND user_id=:u")
            .param("s", sourceSnapshotId).param("u", user)
            .query((rs, n) -> new SourceOrigin(rs.getObject(1, UUID.class), uuid(rs, 2), uuid(rs, 3), instant(rs, 4),
                    rs.getString(5)))
            .optional();
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
     * Erases this user's pointers and their history, attempts, calculation versions (with their metrics,
     * cash-flow history and exclusions) and tc source snapshots — the incremental versions' too. Runs before
     * the rest of the sync erasure: these rows cite the candidate, classification and conversion evidence with
     * ON DELETE RESTRICT.
     */
    void erase(UUID user) {
        jdbc.sql("DELETE FROM sync_official_plan_result WHERE user_id=:u").param("u", user).update();
        // The pointer's history (Story 2.11) cites the versions with ON DELETE RESTRICT: it goes before them.
        jdbc.sql("DELETE FROM sync_official_plan_result_history WHERE user_id=:u").param("u", user).update();
        jdbc.sql("DELETE FROM sync_plan_publication_job WHERE user_id=:u").param("u", user).update();
        // One statement for every version: the previous-version reference is NO ACTION, checked at its end.
        // The exclusions and flows go with their versions (ON DELETE CASCADE).
        jdbc.sql("DELETE FROM sync_plan_calculation WHERE user_id=:u").param("u", user).update();
        jdbc.sql("DELETE FROM sync_source_snapshot WHERE user_id=:u AND initial_candidate_id IS NOT NULL")
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
