package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncJobApi.SyncJob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Story 2.11 incremental sync jobs, the pointer's CAS with its history, and the reads a sync needs from the
 * versions it continues.
 *
 * <p>The job row is the only fence of a sync: its stage rows (Story 2.4~2.6 tables, {@code plan_sync_job_id})
 * follow its lease, are renewed by its heartbeat and are cancelled when it ends. Every write the worker makes
 * runs in a short transaction that locks the job row first ({@link #lockOwned}); lease checks compare with
 * {@code statement_timestamp()}, as the publication's do, because a transaction can wait on a lock.
 */
@Repository
class PlanSyncRepository {
    private final JdbcClient jdbc;

    PlanSyncRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A claimed job: its fence, and when it was claimed (the run limit counts from there, on the database clock). */
    record Lease(UUID jobId, UUID userId, UUID planId, long generation, Instant claimedAt) {}

    /** The job as the worker reads it: its basis, what it fixed and the stage rows it made. */
    record Job(UUID jobId, UUID userId, UUID planId, UUID portfolioId, UUID intentId, UUID membershipId,
            UUID connectionId, UUID providerAccountId, String trigger, String status, String stage,
            UUID basisCalculationId, long basisPointerVersion, Instant basisVerifiedThrough, Instant basisExtendedAt,
            @Nullable String mode, @Nullable Instant tc, @Nullable Instant rangeStart, @Nullable UUID collectionJobId,
            @Nullable UUID candidateId, @Nullable UUID classificationJobId, @Nullable UUID classificationId,
            @Nullable UUID conversionJobId, @Nullable UUID conversionId, Instant createdAt,
            @Nullable String sourcePolicyVersion) {}

    /** What the admission fixes on a new job: the official version current at admission. */
    record Basis(UUID planId, UUID portfolioId, UUID intentId, UUID membershipId, UUID connectionId,
            UUID providerAccountId, UUID calculationId, long pointerVersion, Instant verifiedThrough,
            Instant extendedAt) {}

    /** The fields of one ending the operational line carries (no id, amount or time of an event). */
    record Ending(String trigger, @Nullable String mode, String status, String stage, @Nullable String failureCode,
            long latencyMillis, @Nullable Integer windows, @Nullable Integer pages, @Nullable Integer flows,
            String policies) {}

    /** The database clock. Admission and every timing rule are judged against it. */
    Instant databaseNow() {
        return jdbc.sql("SELECT statement_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    // ---------------------------------------------------------------- admission

    private static final String VIEW = "SELECT job_id,trigger,status,stage,mode,failure_code,retryable,created_at,"
            + "finished_at,published_pointer_version FROM sync_plan_sync_job";

    /** The plan's attempt in progress, if any. The partial unique index keeps it to one. */
    Optional<SyncJob> active(UUID user, UUID planId) {
        return jdbc.sql(VIEW + " WHERE user_id=:u AND plan_id=:p AND status IN ('QUEUED','RUNNING') LIMIT 1")
            .param("u", user).param("p", planId).query((rs, n) -> view(rs)).optional();
    }

    /** The plan's latest attempt, by admission. */
    Optional<SyncJob> latest(UUID user, UUID planId) {
        return jdbc.sql(VIEW + " WHERE user_id=:u AND plan_id=:p ORDER BY created_at DESC,job_id DESC LIMIT 1")
            .param("u", user).param("p", planId).query((rs, n) -> view(rs)).optional();
    }

    Optional<SyncJob> byId(UUID user, UUID jobId) {
        return jdbc.sql(VIEW + " WHERE user_id=:u AND job_id=:j").param("u", user).param("j", jobId)
            .query((rs, n) -> view(rs)).optional();
    }

    private static SyncJob view(ResultSet rs) throws SQLException {
        return new SyncJob(uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getBoolean(7), instant(rs, 8), instantOrNull(rs, 9), rs.getObject(10, Long.class));
    }

    /** Inserts a queued job. Zero when the plan already has one in progress (the index is the authority). */
    int insert(UUID jobId, UUID user, Basis b, String trigger) {
        return jdbc.sql("INSERT INTO sync_plan_sync_job(job_id,user_id,plan_id,portfolio_id,intent_id,membership_id,"
                + "connection_id,provider_account_id,trigger,status,stage,basis_calculation_id,basis_pointer_version,"
                + "basis_verified_through,basis_extended_at) VALUES (:j,:u,:plan,:p,:i,:m,:c,:a,:trigger,'QUEUED',"
                + "'ADMITTED',:calc,:version,:verified,:extended) "
                + "ON CONFLICT (plan_id) WHERE status IN ('QUEUED','RUNNING') DO NOTHING")
            .param("j", jobId).param("u", user).param("plan", b.planId()).param("p", b.portfolioId())
            .param("i", b.intentId()).param("m", b.membershipId()).param("c", b.connectionId())
            .param("a", b.providerAccountId()).param("trigger", trigger).param("calc", b.calculationId())
            .param("version", b.pointerVersion()).param("verified", utc(b.verifiedThrough()))
            .param("extended", utc(b.extendedAt())).update();
    }

    // ---------------------------------------------------------------- lease

    /** How long a job may wait in the queue; older ones end as interrupted and are never claimed. */
    private static final String QUEUED_LIMIT = "interval '5 minutes'";

    /**
     * Claims the oldest queued job that has not waited too long. A lease that expired is never taken over: it
     * ends as interrupted ({@link #expireLost}).
     */
    Optional<Lease> claim() {
        return jdbc.sql("UPDATE sync_plan_sync_job SET status='RUNNING',fencing_generation=fencing_generation+1,"
                + "lease_until=statement_timestamp()+interval '30 seconds',heartbeat_at=statement_timestamp(),"
                + "claimed_at=date_trunc('milliseconds',statement_timestamp()),last_checked_at=statement_timestamp() "
                + "WHERE job_id=(SELECT job_id FROM sync_plan_sync_job WHERE status='QUEUED' "
                + "AND created_at>=statement_timestamp()-" + QUEUED_LIMIT + " ORDER BY created_at "
                + "FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING job_id,user_id,plan_id,fencing_generation,claimed_at")
            .query((rs, n) -> new Lease(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), rs.getLong(4), instant(rs, 5)))
            .optional();
    }

    /** Locks the job row if this lease still owns it. Serializes with erasure and with every other write. */
    boolean lockOwned(Lease lease) {
        return jdbc.sql("SELECT job_id FROM sync_plan_sync_job WHERE job_id=:j AND user_id=:u AND status='RUNNING' "
                + "AND fencing_generation=:g AND lease_until>statement_timestamp() FOR UPDATE")
            .param("j", lease.jobId()).param("u", lease.userId()).param("g", lease.generation())
            .query(UUID.class).optional().isPresent();
    }

    /** Whether this lease still owns the job. A plain read: it never waits for a row lock. */
    boolean owns(Lease lease) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_plan_sync_job WHERE job_id=:j AND user_id=:u "
                + "AND status='RUNNING' AND fencing_generation=:g AND lease_until>statement_timestamp())")
            .param("j", lease.jobId()).param("u", lease.userId()).param("g", lease.generation())
            .query(Boolean.class).single();
    }

    /**
     * Renews the job's lease and its running stage rows' leases without ever waiting for a row lock: the
     * scheduler thread that runs it dispatches every other job too. A job row another transaction holds is
     * skipped, and the job is alive if the lease still owns it (its own write transaction holds the row).
     */
    boolean heartbeat(Lease lease) {
        int renewed = jdbc.sql("UPDATE sync_plan_sync_job SET lease_until=now()+interval '30 seconds',heartbeat_at=now(),"
                + "last_checked_at=now() WHERE job_id=(SELECT job_id FROM sync_plan_sync_job WHERE job_id=:j "
                + "AND fencing_generation=:g AND status='RUNNING' AND lease_until>now() FOR UPDATE SKIP LOCKED)")
            .param("j", lease.jobId()).param("g", lease.generation()).update();
        boolean alive = renewed == 1 || owns(lease);
        if (alive) {
            for (String table : STAGE_TABLES) {
                jdbc.sql("UPDATE " + table + " SET lease_until=now()+interval '30 seconds',last_checked_at=now() "
                        + "WHERE job_id IN (SELECT job_id FROM " + table + " WHERE plan_sync_job_id=:j AND status='RUNNING' "
                        + "AND lease_until>now() FOR UPDATE SKIP LOCKED)")
                    .param("j", lease.jobId()).update();
            }
        }
        return alive;
    }

    private static final List<String> STAGE_TABLES = List.of("sync_initial_collection_job",
            "sync_cashflow_classification_job", "sync_cashflow_conversion_job");

    /**
     * Ends every job that was lost as {@code SYNC_INTERRUPTED} — a running one whose lease expired, and a queued
     * one that waited longer than the queue limit — cancels its stage rows still in progress, and returns the
     * endings for the operational lines. A lost job is never continued (no automatic retry).
     */
    List<Ending> expireLost() {
        return expire("", Map.of());
    }

    /** {@link #expireLost} for one plan only: the admission clears it before judging the attempt in progress. */
    List<Ending> expireLost(UUID user, UUID planId) {
        return expire("user_id=:u AND plan_id=:p AND ", Map.of("u", user, "p", planId));
    }

    private List<Ending> expire(String scope, Map<String, ?> values) {
        var statement = jdbc.sql("UPDATE sync_plan_sync_job SET status='FAILED',failure_code='SYNC_INTERRUPTED',"
                + "retryable=true,lease_until=NULL,finished_at=statement_timestamp(),last_checked_at=statement_timestamp() "
                + "WHERE job_id IN (SELECT job_id FROM sync_plan_sync_job WHERE " + scope
                + "((status='RUNNING' AND lease_until<statement_timestamp()) OR (status='QUEUED' "
                + "AND created_at<statement_timestamp()-" + QUEUED_LIMIT + ")) FOR UPDATE SKIP LOCKED) "
                + "RETURNING job_id," + ENDING_COLUMNS);
        for (Map.Entry<String, ?> value : values.entrySet()) statement = statement.param(value.getKey(), value.getValue());
        List<Object[]> ended = statement.query((rs, n) -> new Object[] {uuid(rs, 1), ending(rs, 1)}).list();
        for (Object[] row : ended) cancelStages((UUID) row[0]);
        return ended.stream().map(row -> (Ending) row[1]).toList();
    }

    /** Cancels the job's stage rows that are still in progress. */
    void cancelStages(UUID jobId) {
        jdbc.sql("UPDATE sync_initial_collection_job SET status='CANCELLED',failure_code='NOT_PERMITTED',lease_until=NULL,"
                + "finished_at=now(),last_checked_at=now() WHERE plan_sync_job_id=:j AND status IN ('QUEUED','RUNNING')")
            .param("j", jobId).update();
        jdbc.sql("UPDATE sync_cashflow_classification_job SET status='CANCELLED',failure_code='NOT_PERMITTED',"
                + "lease_until=NULL,finished_at=now(),last_checked_at=now() WHERE plan_sync_job_id=:j "
                + "AND status IN ('QUEUED','RUNNING')")
            .param("j", jobId).update();
        jdbc.sql("UPDATE sync_cashflow_conversion_job SET status='CANCELLED',failure_code='NOT_PERMITTED',retryable=false,"
                + "lease_until=NULL,finished_at=now(),last_checked_at=now() WHERE plan_sync_job_id=:j "
                + "AND status IN ('QUEUED','RUNNING')")
            .param("j", jobId).update();
    }

    // ---------------------------------------------------------------- the worker's records

    Job job(Lease lease) {
        return jdbc.sql("SELECT job_id,user_id,plan_id,portfolio_id,intent_id,membership_id,connection_id,"
                + "provider_account_id,trigger,status,stage,basis_calculation_id,basis_pointer_version,"
                + "basis_verified_through,basis_extended_at,mode,tc,range_start,collection_job_id,candidate_id,"
                + "classification_job_id,classification_id,conversion_job_id,conversion_id,created_at,"
                + "source_policy_version FROM sync_plan_sync_job WHERE job_id=:j AND user_id=:u")
            .param("j", lease.jobId()).param("u", lease.userId())
            .query((rs, n) -> new Job(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5), uuid(rs, 6),
                    uuid(rs, 7), uuid(rs, 8), rs.getString(9), rs.getString(10), rs.getString(11), uuid(rs, 12),
                    rs.getLong(13), instant(rs, 14), instant(rs, 15), rs.getString(16), instantOrNull(rs, 17),
                    instantOrNull(rs, 18), rs.getObject(19, UUID.class), rs.getObject(20, UUID.class),
                    rs.getObject(21, UUID.class), rs.getObject(22, UUID.class), rs.getObject(23, UUID.class),
                    rs.getObject(24, UUID.class), instant(rs, 25), rs.getString(26)))
            .single();
    }

    /** Records the stage the job reached. The caller holds the job row. */
    void stage(Lease lease, String stage) {
        update(lease, "stage=:stage", Map.of("stage", stage));
    }

    /** Fixes the reference time, the mode and the collection range once, with the windows it needs. */
    void fixScope(Lease lease, Instant tc, String mode, Instant start, int windows) {
        update(lease, "tc=:tc,mode=:mode,range_start=:start,window_count=:windows,stage='COLLECTION'",
                Map.of("tc", utc(tc), "mode", mode, "start", utc(start), "windows", windows));
    }

    void recordCollectionJob(Lease lease, UUID collectionJobId) {
        update(lease, "collection_job_id=:v", Map.of("v", collectionJobId));
    }

    void recordCollection(Lease lease, int pages, String sourcePolicy, @Nullable UUID candidateId) {
        Map<String, Object> values = new HashMap<>();
        values.put("pages", pages);
        values.put("policy", sourcePolicy);
        values.put("candidate", candidateId);
        update(lease, "page_count=:pages,source_policy_version=:policy,candidate_id=:candidate", values);
    }

    void recordClassificationJob(Lease lease, UUID classificationJobId) {
        update(lease, "classification_job_id=:v,stage='CLASSIFICATION'", Map.of("v", classificationJobId));
    }

    void recordClassification(Lease lease, UUID classificationId, String policy) {
        update(lease, "classification_id=:v,classification_policy_version=:policy",
                Map.of("v", classificationId, "policy", policy));
    }

    void recordConversionJob(Lease lease, UUID conversionJobId) {
        update(lease, "conversion_job_id=:v,stage='CONVERSION'", Map.of("v", conversionJobId));
    }

    void recordConversion(Lease lease, UUID conversionId, String policy) {
        update(lease, "conversion_id=:v,price_policy_version=:policy", Map.of("v", conversionId, "policy", policy));
    }

    void recordFlows(Lease lease, int flows, String calculationPolicy) {
        update(lease, "flow_count=:flows,calculation_policy=:policy,stage='CALCULATION'",
                Map.of("flows", flows, "policy", calculationPolicy));
    }

    private void update(Lease lease, String assignments, Map<String, ?> values) {
        var statement = jdbc.sql("UPDATE sync_plan_sync_job SET " + assignments + ",last_checked_at=statement_timestamp() "
                + "WHERE job_id=:j AND status='RUNNING' AND fencing_generation=:g AND lease_until>statement_timestamp()")
            .param("j", lease.jobId()).param("g", lease.generation());
        for (Map.Entry<String, ?> value : values.entrySet()) statement = statement.param(value.getKey(), value.getValue());
        if (statement.update() != 1) throw new IllegalStateException("plan sync lease lost");
    }

    /** The stage row's classification bundle: its id and outcome. */
    Optional<String[]> classificationOutcome(UUID classificationJobId) {
        return jdbc.sql("SELECT classification_id::text,status FROM sync_cashflow_classification_bundle WHERE job_id=:j")
            .param("j", classificationJobId).query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional();
    }

    /** A conversion bundle's outcome: its status and whether a new attempt can settle it. */
    record ConversionOutcome(String status, boolean retryable, String policyVersion) {}

    Optional<ConversionOutcome> conversionOutcome(UUID user, UUID conversionId) {
        return jdbc.sql("SELECT status,retryable,policy_version FROM sync_cashflow_conversion_bundle "
                + "WHERE conversion_id=:c AND user_id=:u")
            .param("c", conversionId).param("u", user)
            .query((rs, n) -> new ConversionOutcome(rs.getString(1), rs.getBoolean(2), rs.getString(3))).optional();
    }

    /**
     * Ends the job, fenced, and cancels its stage rows still in progress, in the caller's transaction.
     *
     * @return the ending for the operational line, or empty when the lease no longer owns the job
     */
    Optional<Ending> finish(Lease lease, String status, @Nullable String failureCode, boolean retryable) {
        Optional<Ending> ending = jdbc.sql("UPDATE sync_plan_sync_job SET status=:s,failure_code=:code,retryable=:r,"
                + "lease_until=NULL,finished_at=statement_timestamp(),last_checked_at=statement_timestamp() "
                + "WHERE job_id=:j AND status='RUNNING' AND fencing_generation=:g AND lease_until>statement_timestamp() "
                + "RETURNING job_id," + ENDING_COLUMNS)
            .param("s", status).param("code", failureCode).param("r", retryable).param("j", lease.jobId())
            .param("g", lease.generation()).query((rs, n) -> ending(rs, 1)).optional();
        if (ending.isPresent()) cancelStages(lease.jobId());
        return ending;
    }

    Optional<Ending> finishPublished(Lease lease, UUID calculationId, long pointerVersion) {
        Optional<Ending> ending = jdbc.sql("UPDATE sync_plan_sync_job SET status='PUBLISHED',calculation_id=:calc,"
                + "published_pointer_version=:version,lease_until=NULL,finished_at=statement_timestamp(),"
                + "last_checked_at=statement_timestamp() WHERE job_id=:j AND status='RUNNING' AND fencing_generation=:g "
                + "AND lease_until>statement_timestamp() RETURNING job_id," + ENDING_COLUMNS)
            .param("calc", calculationId).param("version", pointerVersion).param("j", lease.jobId())
            .param("g", lease.generation()).query((rs, n) -> ending(rs, 1)).optional();
        if (ending.isPresent()) cancelStages(lease.jobId());
        return ending;
    }

    private static final String ENDING_COLUMNS = "trigger,mode,status,stage,failure_code,"
            + "(extract(epoch FROM finished_at-created_at)*1000)::bigint,window_count,page_count,flow_count,"
            + "COALESCE(source_policy_version,'-')||','||COALESCE(classification_policy_version,'-')||','||"
            + "COALESCE(price_policy_version,'-')||','||COALESCE(calculation_policy,'-')";

    private static Ending ending(ResultSet rs, int offset) throws SQLException {
        int o = offset;
        return new Ending(rs.getString(o + 1), rs.getString(o + 2), rs.getString(o + 3), rs.getString(o + 4),
                rs.getString(o + 5), rs.getLong(o + 6), rs.getObject(o + 7, Integer.class),
                rs.getObject(o + 8, Integer.class), rs.getObject(o + 9, Integer.class), rs.getString(o + 10));
    }

    // ---------------------------------------------------------------- the pointer (caller's transaction)

    /**
     * Moves the plan's official pointer from the expected version and calculation to the next version, an
     * expected-version CAS, and records it in the pointer's history: the replaced version ends when the next
     * one begins. Returns the new publication time, or empty when the pointer is not where it was expected.
     */
    Optional<Instant> advancePointer(UUID planId, UUID user, long expectedVersion, UUID expectedCalculationId,
            UUID calculationId) {
        Optional<Instant> published = jdbc.sql("UPDATE sync_official_plan_result SET calculation_id=:calc,"
                + "pointer_version=pointer_version+1,published_at=date_trunc('milliseconds',statement_timestamp()) "
                + "WHERE plan_id=:plan AND user_id=:u AND pointer_version=:expected AND calculation_id=:expectedCalc "
                + "RETURNING published_at")
            .param("calc", calculationId).param("plan", planId).param("u", user).param("expected", expectedVersion)
            .param("expectedCalc", expectedCalculationId)
            .query((rs, n) -> instant(rs, 1)).optional();
        if (published.isEmpty()) return published;
        int ended = jdbc.sql("UPDATE sync_official_plan_result_history SET superseded_at=:at WHERE plan_id=:plan "
                + "AND user_id=:u AND pointer_version=:expected AND calculation_id=:expectedCalc AND superseded_at IS NULL")
            .param("at", utc(published.get())).param("plan", planId).param("u", user).param("expected", expectedVersion)
            .param("expectedCalc", expectedCalculationId).update();
        if (ended != 1) return Optional.empty();
        jdbc.sql("INSERT INTO sync_official_plan_result_history(plan_id,pointer_version,user_id,calculation_id,"
                + "published_at) VALUES (:plan,:version,:u,:calc,:at)")
            .param("plan", planId).param("version", expectedVersion + 1).param("u", user).param("calc", calculationId)
            .param("at", utc(published.get())).update();
        return published;
    }

    // ---------------------------------------------------------------- the versions a sync continues

    /**
     * The previous version's applied flows at or before the collection start, each as the calculable row it
     * cites — the original conversion, carried as it was.
     */
    List<CarriedFlow> carriedFlows(UUID previousCalculationId, Instant start) {
        return jdbc.sql("SELECT pf.conversion_id,cc.source_event_id,cc.source_id,cc.semantic_hash,cc.occurred_at,"
                + "cc.usd_value,cc.estimated,cc.evidence_type FROM sync_plan_calculation_flow pf "
                + "JOIN sync_calculable_cashflow cc ON cc.conversion_id=pf.conversion_id AND cc.source_event_id=pf.source_event_id "
                + "WHERE pf.calculation_id=:c AND pf.occurred_at<=:start ORDER BY pf.sequence")
            .param("c", previousCalculationId).param("start", utc(start))
            .query((rs, n) -> new CarriedFlow(uuid(rs, 1), new PlanPublicationRepository.Flow(uuid(rs, 2),
                    rs.getString(3), rs.getString(4), instant(rs, 5), rs.getString(6), rs.getBoolean(7),
                    rs.getString(8)))).list();
    }

    record CarriedFlow(UUID conversionId, PlanPublicationRepository.Flow flow) {}

    /**
     * One event's facts as a version or a classification holds them, for the overlap comparison: the verdict,
     * the asset, the signed quantity, the time, the semantic hash, and — for an applied flow — the USD value,
     * the estimated flag and the price evidence kind.
     */
    record EventFacts(String verdict, String asset, @Nullable String signedQuantity, Instant occurredAt,
            String semanticHash, @Nullable String usdValue, boolean estimated, @Nullable String evidenceType) {}

    /** The previous version's applied flows after the collection start, by source event. */
    Map<UUID, EventFacts> previousFlowsAfter(UUID previousCalculationId, Instant start) {
        Map<UUID, EventFacts> facts = new HashMap<>();
        jdbc.sql("SELECT pf.source_event_id,CASE WHEN pf.usd_value ~ '^-' THEN 'EXTERNAL_OUT' ELSE 'EXTERNAL_IN' END,"
                + "cc.asset_code,cc.signed_quantity,cc.occurred_at,cc.semantic_hash,pf.usd_value,pf.estimated,"
                + "pf.evidence_type FROM sync_plan_calculation_flow pf JOIN sync_calculable_cashflow cc "
                + "ON cc.conversion_id=pf.conversion_id AND cc.source_event_id=pf.source_event_id "
                + "WHERE pf.calculation_id=:c AND pf.occurred_at>:start")
            .param("c", previousCalculationId).param("start", utc(start))
            .query((rs, n) -> Map.entry(uuid(rs, 1), new EventFacts(rs.getString(2), rs.getString(3), rs.getString(4),
                    instant(rs, 5), rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getString(9))))
            .list().forEach(entry -> facts.put(entry.getKey(), entry.getValue()));
        return facts;
    }

    /** The new conversion's calculable flows, by source event. */
    Map<UUID, EventFacts> calculableFlows(UUID conversionId) {
        Map<UUID, EventFacts> facts = new HashMap<>();
        jdbc.sql("SELECT source_event_id,CASE WHEN usd_value ~ '^-' THEN 'EXTERNAL_OUT' ELSE 'EXTERNAL_IN' END,"
                + "asset_code,signed_quantity,occurred_at,semantic_hash,usd_value,estimated,evidence_type "
                + "FROM sync_calculable_cashflow WHERE conversion_id=:c")
            .param("c", conversionId)
            .query((rs, n) -> Map.entry(uuid(rs, 1), new EventFacts(rs.getString(2), rs.getString(3), rs.getString(4),
                    instant(rs, 5), rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getString(9))))
            .list().forEach(entry -> facts.put(entry.getKey(), entry.getValue()));
        return facts;
    }

    /** The previous version's exclusions after the collection start, by source event, as their classification held them. */
    Map<UUID, EventFacts> previousExclusionsAfter(UUID previousCalculationId, Instant start) {
        return classificationFacts("SELECT e.source_event_id,e.verdict,e.asset_code,e.source_quantity,e.occurred_at,"
                + "e.semantic_hash FROM sync_plan_calculation_exclusion x JOIN sync_cashflow_classification_event e "
                + "ON e.classification_id=x.classification_id AND e.source_event_id=x.source_event_id "
                + "WHERE x.calculation_id=:c AND x.occurred_at>:start", previousCalculationId, start);
    }

    /** Every event of a classification, by source event. */
    Map<UUID, EventFacts> classificationEvents(UUID classificationId) {
        return classificationFacts("SELECT source_event_id,verdict,asset_code,source_quantity,occurred_at,semantic_hash "
                + "FROM sync_cashflow_classification_event WHERE classification_id=:c", classificationId, null);
    }

    private Map<UUID, EventFacts> classificationFacts(String sql, UUID id, @Nullable Instant start) {
        Map<UUID, EventFacts> facts = new HashMap<>();
        var statement = jdbc.sql(sql).param("c", id);
        if (start != null) statement = statement.param("start", utc(start));
        statement.query((rs, n) -> Map.entry(uuid(rs, 1), new EventFacts(rs.getString(2), rs.getString(3),
                        rs.getString(4), instant(rs, 5), rs.getString(6), null, false, null)))
            .list().forEach(entry -> facts.put(entry.getKey(), entry.getValue()));
        return facts;
    }

    /** Carries the previous version's exclusions at or before the collection start to the new version. */
    int carryExclusions(UUID calculationId, UUID previousCalculationId, Instant start) {
        return jdbc.sql("INSERT INTO sync_plan_calculation_exclusion(calculation_id,classification_id,source_event_id,"
                + "occurred_at) SELECT :calc,classification_id,source_event_id,occurred_at FROM "
                + "sync_plan_calculation_exclusion WHERE calculation_id=:prev AND occurred_at<=:start")
            .param("calc", calculationId).param("prev", previousCalculationId).param("start", utc(start)).update();
    }

    // ---------------------------------------------------------------- erasure

    /**
     * Erases this user's sync jobs. Their stage rows go with them (ON DELETE CASCADE). Runs after the
     * publication erasure: a version cites the stage evidence with ON DELETE RESTRICT.
     */
    void erase(UUID user) {
        jdbc.sql("DELETE FROM sync_plan_sync_job WHERE user_id=:u").param("u", user).update();
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
