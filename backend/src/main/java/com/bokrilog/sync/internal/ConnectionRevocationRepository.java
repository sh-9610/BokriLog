package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncJobApi.EndedConnectionWork;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The attempts a connection revocation ends (Story 2.16): every row of the connection still queued or running, in the
 * six attempt tables, with each table's own refusal ending and code. No new failure code: {@code NOT_PERMITTED} is the
 * closed code every table already allows for "the connection is no longer the user's to read". A precision
 * verification or a continuation (Story 2.12) is a plan sync attempt of its own purpose, so it ends here too.
 *
 * <p>The order is a stage's own lock order: the plan syncs before their stage rows (attempt before child), then the
 * independent stages, the first publications and the first snapshots. A stage row of an ended plan sync ends with it
 * whatever connection it names. Each statement clears the lease, so a holder's next fenced statement
 * ({@code status='RUNNING' AND fencing_generation=:g}) finds nothing to own.
 *
 * <p>One time: the revocation's, never before the row's own admission ({@code GREATEST}), to the millisecond.
 */
@Repository
class ConnectionRevocationRepository {
    private static final String IN_PROGRESS = " WHERE user_id=:u AND status IN ('QUEUED','RUNNING')";
    private static final String OF_CONNECTION = " AND connection_id=:c";
    /** The connection's rows, and the stage rows of any plan sync of the connection. */
    private static final String OF_CONNECTION_OR_ATTEMPT = " AND (connection_id=:c OR plan_sync_job_id IN "
            + "(SELECT job_id FROM sync_plan_sync_job WHERE user_id=:u AND connection_id=:c))";

    private final JdbcClient jdbc;
    /**
     * 연결 해제가 끝낸 작업의 결말 계측(Story 3.7). 이 문장이 끝낸 작업은 다른 결말 자리(종료 줄·작업 행 종단)를 지나지 않으므로
     * 여기서 {@code excluded{reason=NOT_PERMITTED}}로 센다 — 시도와 결말의 합이 맞게. 커밋 뒤에 센다.
     */
    private final SyncMetrics metrics;

    ConnectionRevocationRepository(JdbcClient jdbc, SyncMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    EndedConnectionWork endWork(UUID user, UUID connection, Instant revokedAt) {
        int planSyncs = update("UPDATE sync_plan_sync_job SET status='BLOCKED',failure_code='NOT_PERMITTED',"
                + "lease_until=NULL,finished_at=GREATEST(:at,admitted_at)" + IN_PROGRESS + OF_CONNECTION, user,
                connection, revokedAt);
        int collections = update("UPDATE sync_initial_collection_job SET status='CANCELLED',failure_code='NOT_PERMITTED',"
                + "lease_until=NULL,last_checked_at=GREATEST(:at,created_at),finished_at=GREATEST(:at,created_at)"
                + IN_PROGRESS + OF_CONNECTION_OR_ATTEMPT, user, connection, revokedAt);
        int classifications = update("UPDATE sync_cashflow_classification_job SET status='CANCELLED',"
                + "failure_code='NOT_PERMITTED',lease_until=NULL,last_checked_at=GREATEST(:at,created_at),"
                + "finished_at=GREATEST(:at,created_at)" + IN_PROGRESS + OF_CONNECTION_OR_ATTEMPT, user, connection,
                revokedAt);
        int conversions = update("UPDATE sync_cashflow_conversion_job SET status='CANCELLED',failure_code='NOT_PERMITTED',"
                + "retryable=false,lease_until=NULL,last_checked_at=GREATEST(:at,created_at),"
                + "finished_at=GREATEST(:at,created_at)" + IN_PROGRESS + OF_CONNECTION_OR_ATTEMPT, user, connection,
                revokedAt);
        int publications = update("UPDATE sync_plan_publication_job SET status='BLOCKED',failure_code='NOT_PERMITTED',"
                + "lease_until=NULL,last_checked_at=GREATEST(:at,created_at),finished_at=GREATEST(:at,created_at)"
                + IN_PROGRESS + OF_CONNECTION, user, connection, revokedAt);
        int firstSnapshots = update("UPDATE sync_job SET status='BLOCKED',failure_reason='NOT_PERMITTED',"
                + "lease_until=NULL,finished_at=GREATEST(:at,created_at)" + IN_PROGRESS + OF_CONNECTION, user,
                connection, revokedAt);
        metrics.outcomes(com.bokrilog.sync.SyncTelemetry.Kind.PLAN_SYNC, com.bokrilog.sync.SyncTelemetry.EXCLUDED,
                "NOT_PERMITTED", planSyncs);
        metrics.outcomes(com.bokrilog.sync.SyncTelemetry.Kind.FIRST_SNAPSHOT, com.bokrilog.sync.SyncTelemetry.EXCLUDED,
                "NOT_PERMITTED", firstSnapshots);
        return new EndedConnectionWork(firstSnapshots, planSyncs, collections, classifications, conversions,
                publications);
    }

    private int update(String statement, UUID user, UUID connection, Instant at) {
        return jdbc.sql(statement).param("u", user).param("c", connection)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).update();
    }
}
