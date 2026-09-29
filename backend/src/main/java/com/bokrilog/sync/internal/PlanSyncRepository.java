package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncJobApi.SyncJob;
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
 * Story 2.11 incremental sync attempts (V11).
 *
 * <p>One attempt runs the collection, classification and conversion stages as rows of their own tables — the
 * "children" — and holds their lease: its heartbeat renews theirs, and its fenced ending ends every child it
 * left in progress, so no child outlives the attempt that owns it. Lease checks compare against
 * {@code statement_timestamp()}, like the publication attempt's: the publishing transaction can wait on the
 * plan lock, and {@code now()} would still be its start.
 */
@Repository
class PlanSyncRepository {
    private final JdbcClient jdbc;

    PlanSyncRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What an attempt holds: the plan and its lineage, the trigger, and the base — the official version it read
     * at admission (calculation, pointer version, candidate and both watermarks) — plus the plan's {@code t0}
     * and {@code T}.
     */
    record Lease(UUID jobId, UUID userId, UUID planId, UUID portfolioId, UUID intentId, UUID membershipId,
            UUID connectionId, UUID providerAccountId, String trigger, UUID baseCalculationId, long basePointerVersion,
            UUID baseCandidateId, Instant baseVerifiedThrough, Instant baseExtendedAt, Instant t0, Instant endAt,
            long generation) {}

    /** A new attempt, as the admission pinned it. */
    record Admission(UUID jobId, UUID userId, UUID planId, UUID portfolioId, UUID intentId, UUID membershipId,
            UUID connectionId, UUID providerAccountId, String trigger, String key, UUID baseCalculationId,
            long basePointerVersion, UUID baseCandidateId, Instant baseVerifiedThrough, Instant baseExtendedAt,
            Instant t0, Instant endAt) {}

    record KeyedJob(String trigger, SyncJob job) {}

    /** A claimed attempt. {@code interrupted}: its previous holder lost the lease while running it. */
    record Claimed(Lease lease, boolean interrupted) {}

    /** What the one ending line of an attempt says. No user, amount or time of an input. */
    record Ending(UUID jobId, String trigger, @Nullable String mode, String status, @Nullable String stage,
            @Nullable String failureCode, long latencyMs) {}

    // ---------------------------------------------------------------- admission

    /** Inserts the attempt. Zero when the key or the one-in-progress index was taken first. */
    int insert(Admission a) {
        return jdbc.sql("INSERT INTO sync_plan_sync_job(job_id,user_id,plan_id,portfolio_id,intent_id,membership_id,"
                + "connection_id,provider_account_id,trigger,idempotency_key,base_calculation_id,base_pointer_version,"
                + "base_candidate_id,base_verified_through,base_extended_verification_at,t0,end_at,status) VALUES "
                + "(:j,:u,:plan,:p,:i,:m,:c,:a,:trigger,:key,:calc,:version,:cand,:verified,:extended,:t0,:end,'QUEUED') "
                + "ON CONFLICT DO NOTHING")
            .param("j", a.jobId()).param("u", a.userId()).param("plan", a.planId()).param("p", a.portfolioId())
            .param("i", a.intentId()).param("m", a.membershipId()).param("c", a.connectionId())
            .param("a", a.providerAccountId()).param("trigger", a.trigger()).param("key", a.key())
            .param("calc", a.baseCalculationId()).param("version", a.basePointerVersion())
            .param("cand", a.baseCandidateId()).param("verified", utc(a.baseVerifiedThrough()))
            .param("extended", utc(a.baseExtendedAt())).param("t0", utc(a.t0())).param("end", utc(a.endAt()))
            .update();
    }

    private static final String VIEW = "SELECT job_id,trigger,status,stage,failure_code,admitted_at,finished_at "
            + "FROM sync_plan_sync_job";

    private static SyncJob view(ResultSet rs, int offset) throws SQLException {
        return new SyncJob(uuid(rs, offset + 1), rs.getString(offset + 2), rs.getString(offset + 3),
                rs.getString(offset + 4), rs.getString(offset + 5), instant(rs, offset + 6),
                instantOrNull(rs, offset + 7));
    }

    Optional<KeyedJob> byKey(UUID user, String key) {
        return jdbc.sql("SELECT trigger,job_id,trigger,status,stage,failure_code,admitted_at,finished_at "
                + "FROM sync_plan_sync_job WHERE user_id=:u AND idempotency_key=:k")
            .param("u", user).param("k", key)
            .query((rs, n) -> new KeyedJob(rs.getString(1), view(rs, 1))).optional();
    }

    Optional<SyncJob> byId(UUID user, UUID jobId) {
        return jdbc.sql(VIEW + " WHERE user_id=:u AND job_id=:j").param("u", user).param("j", jobId)
            .query((rs, n) -> view(rs, 0)).optional();
    }

    Optional<SyncJob> active(UUID user, UUID planId) {
        return jdbc.sql(VIEW + " WHERE user_id=:u AND plan_id=:plan AND status IN ('QUEUED','RUNNING') "
                + "ORDER BY admitted_at LIMIT 1")
            .param("u", user).param("plan", planId).query((rs, n) -> view(rs, 0)).optional();
    }

    /** The plan's most recent attempt, whatever its trigger or ending. */
    Optional<SyncJob> latest(UUID user, UUID planId) {
        return jdbc.sql(VIEW + " WHERE user_id=:u AND plan_id=:plan ORDER BY admitted_at DESC,job_id DESC LIMIT 1")
            .param("u", user).param("plan", planId).query((rs, n) -> view(rs, 0)).optional();
    }

    /** The time of the plan's last admission, of any trigger and any ending. */
    Optional<Instant> lastAdmission(UUID user, UUID planId) {
        return jdbc.sql("SELECT admitted_at FROM sync_plan_sync_job WHERE user_id=:u AND plan_id=:plan "
                + "ORDER BY admitted_at DESC LIMIT 1")
            .param("u", user).param("plan", planId)
            .query((rs, n) -> instant(rs, 1)).optional();
    }

    /**
     * Whether a worker already found, with Bybit's own time, that the gap from this base is longer than 30 days.
     * The gap only grows from the same base, so the admission and the dashboard treat it as a long gap too.
     */
    boolean longGapObserved(UUID user, UUID planId, UUID baseCalculationId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_plan_sync_job WHERE user_id=:u AND plan_id=:plan "
                + "AND base_calculation_id=:calc AND status='BLOCKED' AND failure_code='LONG_GAP')")
            .param("u", user).param("plan", planId).param("calc", baseCalculationId).query(Boolean.class).single();
    }

    // ---------------------------------------------------------------- the worker's lease

    private static final String LEASE_COLUMNS = "j.job_id,j.user_id,j.plan_id,j.portfolio_id,j.intent_id,"
            + "j.membership_id,j.connection_id,j.provider_account_id,j.trigger,j.base_calculation_id,"
            + "j.base_pointer_version,j.base_candidate_id,j.base_verified_through,j.base_extended_verification_at,"
            + "j.t0,j.end_at,j.fencing_generation";

    private static Lease lease(ResultSet rs) throws SQLException {
        return new Lease(uuid(rs, 1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5), uuid(rs, 6), uuid(rs, 7),
                uuid(rs, 8), rs.getString(9), uuid(rs, 10), rs.getLong(11), uuid(rs, 12), instant(rs, 13),
                instant(rs, 14), instant(rs, 15), instant(rs, 16), rs.getLong(17));
    }

    /**
     * Claims the oldest queued attempt, or a running one whose lease expired. A reclaimed running attempt is
     * reported as interrupted: the caller ends it instead of running it again (no automatic retry).
     */
    Optional<Claimed> claim() {
        return jdbc.sql("WITH picked AS (SELECT job_id,status AS previous FROM sync_plan_sync_job "
                + "WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<statement_timestamp()) "
                + "ORDER BY admitted_at FOR UPDATE SKIP LOCKED LIMIT 1) "
                + "UPDATE sync_plan_sync_job j SET status='RUNNING',fencing_generation=j.fencing_generation+1,"
                + "lease_until=statement_timestamp()+interval '30 seconds',heartbeat_at=statement_timestamp() "
                + "FROM picked WHERE j.job_id=picked.job_id RETURNING " + LEASE_COLUMNS + ",picked.previous")
            .query((rs, n) -> new Claimed(lease(rs), "RUNNING".equals(rs.getString(18)))).optional();
    }

    /** Locks the attempt row if this lease still owns it. Serializes with erasure and with the heartbeat. */
    boolean lockOwned(Lease lease) {
        return jdbc.sql("SELECT job_id FROM sync_plan_sync_job WHERE job_id=:j AND user_id=:u AND status='RUNNING' "
                + "AND fencing_generation=:g AND lease_until>statement_timestamp() FOR UPDATE")
            .param("j", lease.jobId()).param("u", lease.userId()).param("g", lease.generation())
            .query(UUID.class).optional().isPresent();
    }

    /** Whether this lease still owns the attempt. A plain read: it never waits for a row lock. */
    boolean owns(Lease lease) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_plan_sync_job WHERE job_id=:j AND user_id=:u "
                + "AND status='RUNNING' AND fencing_generation=:g AND lease_until>statement_timestamp())")
            .param("j", lease.jobId()).param("u", lease.userId()).param("g", lease.generation())
            .query(Boolean.class).single();
    }

    /**
     * Renews the attempt's lease and the leases of its children still running, never waiting for a row: a row
     * another transaction holds is skipped (the publication transaction holds the attempt for its whole length;
     * a stage transaction holds a child). A skipped attempt is alive while this lease still owns it.
     *
     * @return whether the lease is still held
     */
    boolean heartbeat(Lease lease) {
        int renewed = jdbc.sql("UPDATE sync_plan_sync_job SET lease_until=statement_timestamp()+interval '30 seconds',"
                + "heartbeat_at=statement_timestamp() WHERE job_id=(SELECT job_id FROM sync_plan_sync_job "
                + "WHERE job_id=:j AND fencing_generation=:g AND status='RUNNING' AND lease_until>statement_timestamp() "
                + "FOR UPDATE SKIP LOCKED)")
            .param("j", lease.jobId()).param("g", lease.generation()).update();
        if (renewed != 1 && !owns(lease)) return false;
        jdbc.sql("UPDATE sync_initial_collection_job SET lease_until=now()+interval '30 seconds',heartbeat_at=now(),"
                + "last_checked_at=now() WHERE job_id IN (SELECT job_id FROM sync_initial_collection_job "
                + "WHERE plan_sync_job_id=:j AND status='RUNNING' AND lease_until>now() FOR UPDATE SKIP LOCKED)")
            .param("j", lease.jobId()).update();
        jdbc.sql("UPDATE sync_cashflow_classification_job SET lease_until=now()+interval '30 seconds',"
                + "last_checked_at=now() WHERE job_id IN (SELECT job_id FROM sync_cashflow_classification_job "
                + "WHERE plan_sync_job_id=:j AND status='RUNNING' AND lease_until>now() FOR UPDATE SKIP LOCKED)")
            .param("j", lease.jobId()).update();
        jdbc.sql("UPDATE sync_cashflow_conversion_job SET lease_until=now()+interval '30 seconds',heartbeat_at=now(),"
                + "last_checked_at=now() WHERE job_id IN (SELECT job_id FROM sync_cashflow_conversion_job "
                + "WHERE plan_sync_job_id=:j AND status='RUNNING' AND lease_until>now() FOR UPDATE SKIP LOCKED)")
            .param("j", lease.jobId()).update();
        return true;
    }

    private static final String FENCE = " WHERE job_id=:j AND status='RUNNING' AND fencing_generation=:g "
            + "AND lease_until>statement_timestamp()";

    /** Records the stage the attempt enters. */
    boolean stage(Lease lease, String stage) {
        return jdbc.sql("UPDATE sync_plan_sync_job SET stage=:s" + FENCE)
            .param("s", stage).param("j", lease.jobId()).param("g", lease.generation()).update() == 1;
    }

    /** Fixes the attempt's Bybit reference time once (CAS on "not fixed yet"). */
    boolean fixTime(Lease lease, Instant tc) {
        return jdbc.sql("UPDATE sync_plan_sync_job SET tc=:tc" + FENCE + " AND tc IS NULL")
            .param("tc", utc(tc)).param("j", lease.jobId()).param("g", lease.generation()).update() == 1;
    }

    /** Fixes the mode and the window start once, after the time. */
    boolean fixScope(Lease lease, String mode, Instant windowStart) {
        return jdbc.sql("UPDATE sync_plan_sync_job SET mode=:m,window_start=:w" + FENCE
                + " AND tc IS NOT NULL AND mode IS NULL")
            .param("m", mode).param("w", utc(windowStart)).param("j", lease.jobId()).param("g", lease.generation())
            .update() == 1;
    }

    boolean attachCollection(Lease lease, UUID child) {
        return jdbc.sql("UPDATE sync_plan_sync_job SET collection_job_id=:c,stage='COLLECTION'" + FENCE
                + " AND collection_job_id IS NULL")
            .param("c", child).param("j", lease.jobId()).param("g", lease.generation()).update() == 1;
    }

    boolean attachClassification(Lease lease, UUID child) {
        return jdbc.sql("UPDATE sync_plan_sync_job SET classification_job_id=:c,stage='CLASSIFICATION'" + FENCE
                + " AND classification_job_id IS NULL")
            .param("c", child).param("j", lease.jobId()).param("g", lease.generation()).update() == 1;
    }

    boolean attachConversion(Lease lease, UUID child) {
        return jdbc.sql("UPDATE sync_plan_sync_job SET conversion_job_id=:c,stage='CONVERSION'" + FENCE
                + " AND conversion_job_id IS NULL")
            .param("c", child).param("j", lease.jobId()).param("g", lease.generation()).update() == 1;
    }

    private static final String ENDING = " RETURNING job_id,trigger,mode,status,stage,failure_code,"
            + "(extract(epoch FROM (finished_at-admitted_at))*1000)::bigint";

    private static Ending ending(ResultSet rs) throws SQLException {
        return new Ending(uuid(rs, 1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getLong(7));
    }

    /**
     * Ends the attempt with a closed code, fenced, and every child it left in progress with it. Empty when the
     * lease no longer owns it: the late worker writes nothing, not even an ending.
     */
    Optional<Ending> finish(Lease lease, String status, String code) {
        Optional<Ending> ended = jdbc.sql("UPDATE sync_plan_sync_job SET status=:s,failure_code=:code,lease_until=NULL,"
                + "finished_at=date_trunc('milliseconds',statement_timestamp())" + FENCE + ENDING)
            .param("s", status).param("code", code).param("j", lease.jobId()).param("g", lease.generation())
            .query((rs, n) -> ending(rs)).optional();
        ended.ifPresent(done -> endChildren(done.jobId(), code));
        return ended;
    }

    /** Ends the attempt as published, fenced. Its children already finished with their own endings. */
    Optional<Ending> finishSucceeded(Lease lease, UUID calculationId, long pointerVersion) {
        return jdbc.sql("UPDATE sync_plan_sync_job SET status='SUCCEEDED',calculation_id=:calc,"
                + "published_pointer_version=:v,lease_until=NULL,"
                + "finished_at=date_trunc('milliseconds',statement_timestamp())" + FENCE + ENDING)
            .param("calc", calculationId).param("v", pointerVersion).param("j", lease.jobId())
            .param("g", lease.generation()).query((rs, n) -> ending(rs)).optional();
    }

    /**
     * Ends the children still queued or running with the attempt's code. They carry the stage tables' own
     * vocabulary: a collection or classification is cancelled with the code, a conversion is interrupted (its
     * table allows no other result-less ending with an arbitrary code).
     */
    void endChildren(UUID jobId, String code) {
        jdbc.sql("UPDATE sync_initial_collection_job SET status='CANCELLED',failure_code=:code,lease_until=NULL,"
                + "last_checked_at=now(),finished_at=now() WHERE plan_sync_job_id=:j AND status IN ('QUEUED','RUNNING')")
            .param("code", code).param("j", jobId).update();
        jdbc.sql("UPDATE sync_cashflow_classification_job SET status='CANCELLED',failure_code=:code,lease_until=NULL,"
                + "last_checked_at=now(),finished_at=now() WHERE plan_sync_job_id=:j AND status IN ('QUEUED','RUNNING')")
            .param("code", code).param("j", jobId).update();
        jdbc.sql("UPDATE sync_cashflow_conversion_job SET status='CONVERSION_REQUIRED',"
                + "failure_code='CONVERSION_INTERRUPTED',retryable=false,lease_until=NULL,last_checked_at=now(),"
                + "finished_at=now() WHERE plan_sync_job_id=:j AND status IN ('QUEUED','RUNNING')")
            .param("j", jobId).update();
    }

    // ---------------------------------------------------------------- lineage (Story 2.9 evidence)

    /**
     * The attempt that created an incremental version: its ending, what it published, the base it read, the
     * scope it fixed and the lineage it carried.
     */
    record CreatingSync(String status, UUID planId, UUID portfolioId, UUID intentId, UUID membershipId,
            UUID connectionId, UUID providerAccountId, @Nullable UUID calculationId,
            @Nullable Long publishedPointerVersion, UUID baseCalculationId, long basePointerVersion,
            UUID baseCandidateId, Instant baseVerifiedThrough, Instant baseExtendedAt, @Nullable Instant tc,
            @Nullable String mode, @Nullable Instant windowStart) {}

    Optional<CreatingSync> creatingSync(UUID user, UUID jobId) {
        return jdbc.sql("SELECT status,plan_id,portfolio_id,intent_id,membership_id,connection_id,provider_account_id,"
                + "calculation_id,published_pointer_version,base_calculation_id,base_pointer_version,base_candidate_id,"
                + "base_verified_through,base_extended_verification_at,tc,mode,window_start FROM sync_plan_sync_job "
                + "WHERE job_id=:j AND user_id=:u")
            .param("j", jobId).param("u", user)
            .query((rs, n) -> new CreatingSync(rs.getString(1), uuid(rs, 2), uuid(rs, 3), uuid(rs, 4), uuid(rs, 5),
                    uuid(rs, 6), uuid(rs, 7), rs.getObject(8, UUID.class),
                    rs.getObject(9) == null ? null : rs.getLong(9), uuid(rs, 10), rs.getLong(11), uuid(rs, 12),
                    instant(rs, 13), instant(rs, 14), instantOrNull(rs, 15), rs.getString(16),
                    instantOrNull(rs, 17)))
            .optional();
    }

    /**
     * The results the attempt's own stages produced: the candidate of its collection child (completed), the
     * bundle of its classification child (COMPLETE) and the bundle of its conversion child (calculable). Each is
     * {@code null} when that child is missing, not this attempt's, or did not finish that way.
     */
    record ChildResults(@Nullable UUID candidateId, @Nullable UUID classificationId, @Nullable UUID conversionId) {}

    Optional<ChildResults> childResults(UUID user, UUID jobId) {
        return jdbc.sql("SELECT (SELECT b.candidate_id FROM sync_initial_collection_job c "
                + "JOIN sync_initial_candidate_bundle b ON b.job_id=c.job_id AND b.user_id=c.user_id "
                + "WHERE c.job_id=j.collection_job_id AND c.plan_sync_job_id=j.job_id AND c.user_id=j.user_id "
                + "AND c.status='INPUTS_COMPLETE'),"
                + "(SELECT b.classification_id FROM sync_cashflow_classification_job c "
                + "JOIN sync_cashflow_classification_bundle b ON b.job_id=c.job_id AND b.user_id=c.user_id "
                + "WHERE c.job_id=j.classification_job_id AND c.plan_sync_job_id=j.job_id AND c.user_id=j.user_id "
                + "AND c.status='COMPLETE' AND b.status='COMPLETE'),"
                + "(SELECT c.conversion_id FROM sync_cashflow_conversion_job c WHERE c.job_id=j.conversion_job_id "
                + "AND c.plan_sync_job_id=j.job_id AND c.user_id=j.user_id "
                + "AND c.status IN ('CONVERTED','CONVERTED_ESTIMATED')) "
                + "FROM sync_plan_sync_job j WHERE j.job_id=:j AND j.user_id=:u")
            .param("j", jobId).param("u", user)
            .query((rs, n) -> new ChildResults(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class))).optional();
    }

    // ---------------------------------------------------------------- erasure

    /**
     * Locks the user's attempts before their children are erased. A stage transaction locks the attempt first
     * and a child second; erasure then takes them in the same order and the two cannot deadlock.
     */
    void lockForErase(UUID user) {
        jdbc.sql("SELECT job_id FROM sync_plan_sync_job WHERE user_id=:u ORDER BY job_id FOR UPDATE")
            .param("u", user).query(UUID.class).list();
    }

    /**
     * Erases the user's attempts, after their children. Returns the attempts that were still in progress: they
     * end here, with the user's data, and the caller writes their one ending line once this commits.
     */
    List<Ending> erase(UUID user) {
        return jdbc.sql("DELETE FROM sync_plan_sync_job WHERE user_id=:u RETURNING job_id,trigger,mode,status,stage,"
                + "failure_code,(extract(epoch FROM (statement_timestamp()-admitted_at))*1000)::bigint")
            .param("u", user).query((rs, n) -> ending(rs)).list().stream()
            .filter(e -> "QUEUED".equals(e.status()) || "RUNNING".equals(e.status())).toList();
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
