package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncJobApi.Job;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class SyncJobRepository {
    private final JdbcClient jdbc;
    SyncJobRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    record Lease(UUID jobId, UUID userId, UUID connectionId, UUID providerAccountId, long generation) {}

    Optional<Job> byKey(UUID userId, UUID connectionId, String key) {
        return jdbc.sql("SELECT job_id,status,failure_reason FROM sync_job WHERE user_id=:u AND connection_id=:c AND idempotency_key=:k")
            .param("u",userId).param("c",connectionId).param("k",key).query((rs,n)->job(rs)).optional();
    }
    Optional<Job> active(UUID userId, UUID connectionId) {
        return jdbc.sql("SELECT job_id,status,failure_reason FROM sync_job WHERE user_id=:u "
                + "AND connection_id=:c AND status IN ('QUEUED','RUNNING') ORDER BY created_at LIMIT 1")
            .param("u",userId).param("c",connectionId).query((rs,n)->job(rs)).optional();
    }
    Optional<Job> byId(UUID userId, UUID jobId) {
        return jdbc.sql("SELECT job_id,status,failure_reason FROM sync_job WHERE user_id=:u AND job_id=:j")
            .param("u",userId).param("j",jobId).query((rs,n)->job(rs)).optional();
    }
    Optional<Job> latest(UUID userId) {
        return jdbc.sql("SELECT job_id,status,failure_reason FROM sync_job WHERE user_id=:u "
                + "ORDER BY created_at DESC,job_id DESC LIMIT 1")
            .param("u",userId).query((rs,n)->job(rs)).optional();
    }
    Optional<com.bokrilog.sync.SyncJobApi.SourceSnapshot> sourceSnapshot(UUID userId, UUID id) {
        return jdbc.sql("SELECT source_snapshot_id,user_id,connection_id,provider_account_id,input_status,policy_version "
                + "FROM sync_source_snapshot WHERE user_id=:u AND source_snapshot_id=:id")
            .param("u",userId).param("id",id)
            .query((rs,n)->new com.bokrilog.sync.SyncJobApi.SourceSnapshot(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6))).optional();
    }
    private static Job job(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Job(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3));
    }
    int insert(UUID jobId, UUID userId, UUID connectionId, UUID accountId, String key) {
        return jdbc.sql("INSERT INTO sync_job (job_id,user_id,connection_id,provider_account_id,idempotency_key,status) "
                + "VALUES (:j,:u,:c,:a,:k,'QUEUED') ON CONFLICT (connection_id,idempotency_key) DO NOTHING")
            .param("j",jobId).param("u",userId).param("c",connectionId).param("a",accountId)
            .param("k",key).update();
    }
    Optional<Lease> claim() {
        return jdbc.sql("UPDATE sync_job SET status='RUNNING',fencing_generation=fencing_generation+1,"
                + "lease_until=now()+interval '30 seconds',heartbeat_at=now() WHERE job_id=("
                + "SELECT job_id FROM sync_job WHERE status='QUEUED' OR "
                + "(status='RUNNING' AND lease_until<now()) ORDER BY created_at "
                + "FOR UPDATE SKIP LOCKED LIMIT 1) "
                + "RETURNING job_id,user_id,connection_id,provider_account_id,fencing_generation")
            .query((rs,n)->new Lease(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getLong(5))).optional();
    }
    boolean owns(Lease lease) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_job WHERE job_id=:j AND user_id=:u "
                + "AND connection_id=:c AND provider_account_id=:a AND status='RUNNING' "
                + "AND fencing_generation=:g AND lease_until>now())")
            .param("j",lease.jobId()).param("u",lease.userId()).param("c",lease.connectionId())
            .param("a",lease.providerAccountId()).param("g",lease.generation())
            .query(Boolean.class).single();
    }
    boolean lockOwned(Lease lease) {
        return jdbc.sql("SELECT job_id FROM sync_job WHERE job_id=:j AND user_id=:u "
                + "AND connection_id=:c AND provider_account_id=:a AND status='RUNNING' "
                + "AND fencing_generation=:g AND lease_until>now() FOR UPDATE")
            .param("j",lease.jobId()).param("u",lease.userId()).param("c",lease.connectionId())
            .param("a",lease.providerAccountId()).param("g",lease.generation())
            .query(UUID.class).optional().isPresent();
    }
    int heartbeat(Lease lease) {
        return jdbc.sql("UPDATE sync_job SET lease_until=now()+interval '30 seconds',heartbeat_at=now() "
                + "WHERE job_id=:j AND fencing_generation=:g AND status='RUNNING' AND lease_until>now()")
            .param("j",lease.jobId()).param("g",lease.generation()).update();
    }
    int finish(Lease lease,String status,String reason) {
        return jdbc.sql("UPDATE sync_job SET status=:s,failure_reason=:r,lease_until=NULL,finished_at=now() "
                + "WHERE job_id=:j AND fencing_generation=:g AND status='RUNNING' AND lease_until>now()")
            .param("s",status).param("r",reason).param("j",lease.jobId())
            .param("g",lease.generation()).update();
    }
    int insertSource(UUID sourceId, Lease lease, String decimal, Instant measured, Instant received,
            String policy) {
        return jdbc.sql("INSERT INTO sync_source_snapshot "
                + "(source_snapshot_id,user_id,connection_id,provider_account_id,job_id,provider,"
                + "equity_decimal,measured_at,received_at,input_status,policy_version) "
                + "VALUES (:id,:u,:c,:a,:j,'BYBIT',:decimal,:measured,:received,'VERIFIED',:policy)")
            .param("id",sourceId).param("u",lease.userId()).param("c",lease.connectionId())
            .param("a",lease.providerAccountId()).param("j",lease.jobId())
            .param("decimal",decimal)
            .param("measured",OffsetDateTime.ofInstant(measured,ZoneOffset.UTC))
            .param("received",OffsetDateTime.ofInstant(received,ZoneOffset.UTC))
            .param("policy",policy).update();
    }
    boolean nextInProgress(UUID userId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_job WHERE user_id=:u AND purpose='NEXT_PLAN_BASELINE' AND status IN ('QUEUED','RUNNING'))")
            .param("u",userId).query(Boolean.class).single();
    }
    boolean next(UUID jobId) {
        return jdbc.sql("SELECT purpose='NEXT_PLAN_BASELINE' FROM sync_job WHERE job_id=:j")
            .param("j",jobId).query(Boolean.class).optional().orElse(false);
    }
    Optional<com.bokrilog.sync.SyncJobApi.NextBaseline> baseline(UUID userId, UUID jobId, boolean currentOnly) {
        return jdbc.sql("SELECT job_id,predecessor_plan_id,portfolio_id,portfolio_snapshot_id,source_snapshot_id,terminal_at,requested_at,completed_at FROM sync_next_plan_baseline WHERE user_id=:u AND completed_at IS NOT NULL "
                + (currentOnly ? "AND current" : "AND job_id=:j"))
            .param("u",userId).param("j",jobId).query((rs,n) -> new com.bokrilog.sync.SyncJobApi.NextBaseline(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
                rs.getObject(5,UUID.class),rs.getObject(6,OffsetDateTime.class).toInstant(),
                rs.getObject(7,OffsetDateTime.class).toInstant(),rs.getObject(8,OffsetDateTime.class).toInstant())).optional();
    }
    record Preparation(UUID predecessorPlanId, UUID portfolioId, Instant terminalAt, Instant requestedAt) {}
    Optional<Preparation> preparation(UUID userId, UUID jobId) {
        return jdbc.sql("SELECT predecessor_plan_id,portfolio_id,terminal_at,requested_at FROM sync_next_plan_baseline WHERE user_id=:u AND job_id=:j")
            .param("u",userId).param("j",jobId).query((rs,n) -> new Preparation(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,OffsetDateTime.class).toInstant(),rs.getObject(4,OffsetDateTime.class).toInstant())).optional();
    }
    Optional<Job> latestNext(UUID userId) {
        return jdbc.sql("SELECT job_id,status,failure_reason FROM sync_job WHERE user_id=:u AND purpose='NEXT_PLAN_BASELINE' ORDER BY created_at DESC,job_id DESC LIMIT 1")
            .param("u",userId).query((rs,n)->job(rs)).optional();
    }
    boolean busy(UUID userId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) OR EXISTS(SELECT 1 FROM sync_plan_sync_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) OR EXISTS(SELECT 1 FROM sync_plan_publication_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) OR EXISTS(SELECT 1 FROM sync_initial_collection_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) OR EXISTS(SELECT 1 FROM sync_cashflow_classification_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING')) OR EXISTS(SELECT 1 FROM sync_cashflow_conversion_job WHERE user_id=:u AND status IN ('QUEUED','RUNNING'))")
            .param("u",userId).query(Boolean.class).single();
    }
    void insertPreparation(UUID jobId, UUID userId, UUID predecessor, UUID portfolio, Instant terminal) {
        jdbc.sql("UPDATE sync_job SET purpose='NEXT_PLAN_BASELINE' WHERE job_id=:j").param("j",jobId).update();
        jdbc.sql("INSERT INTO sync_next_plan_baseline(job_id,user_id,predecessor_plan_id,portfolio_id,terminal_at) VALUES (:j,:u,:p,:portfolio,:t)")
            .param("j",jobId).param("u",userId).param("p",predecessor).param("portfolio",portfolio)
            .param("t",OffsetDateTime.ofInstant(terminal,ZoneOffset.UTC)).update();
    }
    void completePreparation(UUID userId, UUID jobId, UUID portfolioSnapshotId, UUID sourceSnapshotId) {
        jdbc.sql("UPDATE sync_next_plan_baseline SET current=false WHERE user_id=:u AND current").param("u",userId).update();
        if (jdbc.sql("UPDATE sync_next_plan_baseline SET current=true,completed_at=statement_timestamp(),portfolio_snapshot_id=:p,source_snapshot_id=:s WHERE user_id=:u AND job_id=:j AND completed_at IS NULL AND terminal_at<statement_timestamp()")
                .param("u",userId).param("j",jobId).param("p",portfolioSnapshotId).param("s",sourceSnapshotId).update()!=1)
            throw new IllegalStateException("baseline preparation changed");
    }
    void erase(UUID userId) {
        jdbc.sql("DELETE FROM sync_next_plan_baseline WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_source_snapshot WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_job WHERE user_id=:u").param("u",userId).update();
    }
}
