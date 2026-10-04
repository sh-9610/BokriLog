package com.bokrilog.plan.internal;

import com.bokrilog.sync.PlanPublicationPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The natural completion's own rows (Story 2.14): the plan's {@code ACTIVE → COMPLETED} and the completion record
 * ({@code plan_completion}, V14). Every statement is scoped to the user and guarded by the state it moves from, so a
 * racing writer changes nothing: the transition only from {@code ACTIVE} at or after {@code T}, the observation only
 * while none is fixed, and the comparison only from {@code PENDING} or {@code HELD}. Erasure is
 * {@code PlanConditionRepository.erase}'s, which removes the completion records before the plans.
 */
@Repository
class PlanCompletionRepository {
    private final JdbcClient jdbc;

    PlanCompletionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the user's plan row and answers whether it is active and its end {@code T} has been reached by the
     * database clock. A racing early end or completion waits here and then finds the plan terminal.
     */
    boolean lockActiveAtEnd(UUID userId, UUID planId) {
        return this.jdbc.sql("SELECT plan_id FROM plan_definition WHERE user_id=:u AND plan_id=:p AND status='ACTIVE' "
                        + "AND end_at<=statement_timestamp() FOR UPDATE")
                .param("u", userId).param("p", planId).query(UUID.class).optional().isPresent();
    }

    /** {@code ACTIVE → COMPLETED}, only at or after {@code T}. Exactly one row or none. */
    int completePlan(UUID userId, UUID planId) {
        return this.jdbc.sql("UPDATE plan_definition SET status='COMPLETED' WHERE user_id=:u AND plan_id=:p "
                        + "AND status='ACTIVE' AND end_at<=statement_timestamp()")
                .param("u", userId).param("p", planId).update();
    }

    /**
     * The completion record of the transition: completed at {@code T}, transitioned at the database clock to the
     * millisecond, and a comparison not judged yet — or, after a long gap, not computable for the data gap.
     */
    int insert(UUID userId, UUID planId, boolean dataGap) {
        return this.jdbc.sql("INSERT INTO plan_completion(plan_id,user_id,status,completed_at,transitioned_at,comparison,"
                        + "reason) SELECT d.plan_id,d.user_id,'COMPLETED',d.end_at,"
                        + "date_trunc('milliseconds',statement_timestamp()),:comparison,:reason "
                        + "FROM plan_definition d WHERE d.user_id=:u AND d.plan_id=:p AND d.status='COMPLETED'")
                .param("u", userId).param("p", planId)
                .param("comparison", dataGap ? "UNAVAILABLE" : "PENDING").param("reason", dataGap ? "DATA_GAP" : null)
                .update();
    }

    /** Fixes the first post-end observation, once: only while none is fixed. The delay is {@code measured − T}. */
    int recordObservation(UUID userId, UUID planId, UUID snapshotId, String value, Instant measuredAt) {
        return this.jdbc.sql("UPDATE plan_completion SET observed_portfolio_snapshot_id=:s,observed_value=:v,"
                        + "observed_measured_at=:m,"
                        + "observation_delay_millis=(extract(epoch FROM (CAST(:m AS timestamptz)-completed_at))*1000)::bigint,"
                        + "observed_at=date_trunc('milliseconds',statement_timestamp()) "
                        + "WHERE user_id=:u AND plan_id=:p AND observed_portfolio_snapshot_id IS NULL "
                        + "AND completed_at<=CAST(:m AS timestamptz)")
                .param("s", snapshotId).param("v", value).param("m", utc(measuredAt)).param("u", userId)
                .param("p", planId).update();
    }

    /** Writes the judged comparison: only from {@code PENDING} or {@code HELD}; the two others are terminal. */
    int recordComparison(UUID userId, UUID planId, String comparison, @Nullable String reason,
            @Nullable UUID calculationId, @Nullable Integer postEndCashflowCount) {
        return this.jdbc.sql("UPDATE plan_completion SET comparison=:c,reason=:r,completion_calculation_id=:calc,"
                        + "post_end_cashflow_count=:n,judged_at=CASE WHEN CAST(:calc AS uuid) IS NULL THEN NULL "
                        + "ELSE date_trunc('milliseconds',statement_timestamp()) END "
                        + "WHERE user_id=:u AND plan_id=:p AND comparison IN ('PENDING','HELD')")
                .param("c", comparison).param("r", reason).param("calc", calculationId).param("n", postEndCashflowCount)
                .param("u", userId).param("p", planId).update();
    }

    static final String COLUMNS = "c.completed_at,c.transitioned_at,c.comparison,c.reason,"
            + "c.observed_portfolio_snapshot_id,c.observed_value,c.observed_measured_at,c.observation_delay_millis,"
            + "c.completion_calculation_id,c.post_end_cashflow_count";

    /** The {@link #COLUMNS} starting after {@code offset} columns, or {@code null} when the record is absent. */
    static PlanPublicationPort.@Nullable PlanCompletion completion(ResultSet rs, int offset) throws SQLException {
        int o = offset;
        OffsetDateTime completedAt = rs.getObject(o + 1, OffsetDateTime.class);
        if (completedAt == null) return null;
        OffsetDateTime measured = rs.getObject(o + 7, OffsetDateTime.class);
        Object delay = rs.getObject(o + 8);
        Object count = rs.getObject(o + 10);
        return new PlanPublicationPort.PlanCompletion(completedAt.toInstant(),
                rs.getObject(o + 2, OffsetDateTime.class).toInstant(), rs.getString(o + 3), rs.getString(o + 4),
                rs.getObject(o + 5, UUID.class), rs.getString(o + 6), measured == null ? null : measured.toInstant(),
                delay == null ? null : rs.getLong(o + 8), rs.getObject(o + 9, UUID.class),
                count == null ? null : rs.getInt(o + 10));
    }

    Optional<PlanPublicationPort.PlanCompletion> find(UUID userId, UUID planId, boolean forUpdate) {
        return this.jdbc.sql("SELECT " + COLUMNS + " FROM plan_completion c WHERE c.user_id=:u AND c.plan_id=:p"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("u", userId).param("p", planId)
                .query((rs, n) -> completion(rs, 0)).optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
