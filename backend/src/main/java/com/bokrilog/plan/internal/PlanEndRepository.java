package com.bokrilog.plan.internal;

import com.bokrilog.plan.PlanEndApi;
import com.bokrilog.sync.PlanPublicationPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The early end's own rows (Story 2.13): the plan's status change and the end record ({@code plan_end}, V13). Every
 * statement is scoped to the user. Nothing here updates a plan's terms or deletes anything; erasure is
 * {@code PlanConditionRepository.erase}'s, which removes the end records before the plans.
 */
@Repository
class PlanEndRepository {
    private final JdbcClient jdbc;

    PlanEndRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether {@code planId} is the user's active plan. {@code forUpdate} locks the plan row until the caller's
     * transaction ends: a second end of the same plan waits here and then finds it ended.
     */
    boolean activePlan(UUID userId, UUID planId, boolean forUpdate) {
        return this.jdbc.sql("SELECT plan_id FROM plan_definition WHERE user_id=:u AND plan_id=:p AND status='ACTIVE'"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("u", userId).param("p", planId).query(UUID.class).optional().isPresent();
    }

    /** {@code ACTIVE → ENDED_EARLY}: the one statement that releases the active pointer. Exactly one row or none. */
    int endPlan(UUID userId, UUID planId) {
        return this.jdbc.sql("UPDATE plan_definition SET status='ENDED_EARLY' "
                        + "WHERE user_id=:u AND plan_id=:p AND status='ACTIVE'")
                .param("u", userId).param("p", planId).update();
    }

    /** The end record's values, as the end writes them. */
    record EndRow(UUID planId, UUID userId, PlanEndApi.Reason reason, Instant endedAt, UUID officialCalculationId,
            long officialPointerVersion, UUID officialPortfolioSnapshotId, Instant lastMeasuredAt,
            Instant cashflowVerifiedThrough, PlanEndApi.Completion completion, @Nullable Instant uncollectedFrom,
            @Nullable Instant uncollectedUntil, String idempotencyKey, String requestHash) {}

    int insert(EndRow row) {
        // The status column names the plan status the record pairs with (V13's composite foreign key): the plan row
        // must already be ENDED_EARLY, and it cannot go back while this record exists.
        return this.jdbc.sql("INSERT INTO plan_end (plan_id,user_id,status,reason,ended_at,official_calculation_id,"
                        + "official_pointer_version,official_portfolio_snapshot_id,last_measured_at,"
                        + "cashflow_verified_through,completion,uncollected_from,uncollected_until,idempotency_key,"
                        + "request_hash) VALUES (:p,:u,'ENDED_EARLY',:reason,:ended,:calc,:version,:snapshot,:measured,"
                        + ":verified,:completion,:from,:until,:key,:hash)")
                .param("p", row.planId()).param("u", row.userId()).param("reason", row.reason().name())
                .param("ended", utc(row.endedAt())).param("calc", row.officialCalculationId())
                .param("version", row.officialPointerVersion()).param("snapshot", row.officialPortfolioSnapshotId())
                .param("measured", utc(row.lastMeasuredAt())).param("verified", utc(row.cashflowVerifiedThrough()))
                .param("completion", row.completion().name())
                .param("from", row.uncollectedFrom() == null ? null : utc(row.uncollectedFrom()))
                .param("until", row.uncollectedUntil() == null ? null : utc(row.uncollectedUntil()))
                .param("key", row.idempotencyKey()).param("hash", row.requestHash()).update();
    }

    /** The user's end stored under the key: its plan, reason, time and request hash. */
    record Keyed(UUID planId, PlanEndApi.Reason reason, Instant endedAt, String requestHash) {}

    Optional<Keyed> byKey(UUID userId, String key) {
        return this.jdbc.sql("SELECT plan_id,reason,ended_at,request_hash FROM plan_end "
                        + "WHERE user_id=:u AND idempotency_key=:k")
                .param("u", userId).param("k", key)
                .query((rs, n) -> new Keyed(rs.getObject(1, UUID.class), PlanEndApi.Reason.valueOf(rs.getString(2)),
                        instant(rs, 3), rs.getString(4)))
                .optional();
    }

    /**
     * Whether the user has a plan in a terminal status: ended early (Story 2.13) or completed (Story 2.14). Either one
     * closes the plan-condition path until Story 2.15 opens the following plan.
     */
    boolean terminalPlanExists(UUID userId) {
        return this.jdbc.sql("SELECT EXISTS(SELECT 1 FROM plan_definition WHERE user_id=:u "
                        + "AND status IN ('ENDED_EARLY','COMPLETED'))")
                .param("u", userId).query(Boolean.class).single();
    }

    /**
     * The user's most recent plan with its status and, when it ended early, its end record, or when it completed, its
     * completion record (Story 2.14) — read together, so they cannot come from different moments. A terminal plan
     * without its record comes back without it.
     */
    Optional<PlanPublicationPort.LatestPlan> latestPlan(UUID userId) {
        return this.jdbc.sql("SELECT d.plan_id,d.intent_id,d.portfolio_id,d.p0_decimal,d.t0,d.end_at,d.period,"
                        + "d.target_rate,d.created_at,d.status,e.reason,e.ended_at,e.official_calculation_id,"
                        + "e.official_pointer_version,e.official_portfolio_snapshot_id,e.last_measured_at,"
                        + "e.cashflow_verified_through,e.completion,e.uncollected_from,e.uncollected_until,"
                        + PlanCompletionRepository.COLUMNS + " "
                        + "FROM plan_definition d LEFT JOIN plan_end e ON e.plan_id=d.plan_id AND e.user_id=d.user_id "
                        + "LEFT JOIN plan_completion c ON c.plan_id=d.plan_id AND c.user_id=d.user_id "
                        + "WHERE d.user_id=:u ORDER BY d.created_at DESC,d.plan_id DESC LIMIT 1")
                .param("u", userId)
                .query((rs, n) -> {
                    var plan = new PlanPublicationPort.ActivePlan(rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), instant(rs, 5),
                            instant(rs, 6), rs.getString(7), rs.getString(8), instant(rs, 9));
                    PlanPublicationPort.PlanEnd end = rs.getString(11) == null ? null
                            : new PlanPublicationPort.PlanEnd(rs.getString(11), instant(rs, 12),
                                    rs.getObject(13, UUID.class), rs.getLong(14), rs.getObject(15, UUID.class),
                                    instant(rs, 16), instant(rs, 17), rs.getString(18), nullableInstant(rs, 19),
                                    nullableInstant(rs, 20));
                    return new PlanPublicationPort.LatestPlan(plan, rs.getString(10), end,
                            PlanCompletionRepository.completion(rs, 20));
                })
                .optional();
    }

    Optional<PlanPublicationPort.LatestPlan> historicalPlan(UUID userId, UUID planId) {
        return this.jdbc.sql("SELECT d.plan_id,d.intent_id,d.portfolio_id,d.p0_decimal,d.t0,d.end_at,d.period,"
                        + "d.target_rate,d.created_at,d.status,e.reason,e.ended_at,e.official_calculation_id,"
                        + "e.official_pointer_version,e.official_portfolio_snapshot_id,e.last_measured_at,"
                        + "e.cashflow_verified_through,e.completion,e.uncollected_from,e.uncollected_until,"
                        + PlanCompletionRepository.COLUMNS + " "
                        + "FROM plan_definition d LEFT JOIN plan_end e ON e.plan_id=d.plan_id AND e.user_id=d.user_id "
                        + "LEFT JOIN plan_completion c ON c.plan_id=d.plan_id AND c.user_id=d.user_id "
                        + "WHERE d.user_id=:u AND d.plan_id=:p AND d.status IN ('ENDED_EARLY','COMPLETED')")
                .param("u", userId).param("p",planId)
                .query((rs, n) -> {
                    var plan = new PlanPublicationPort.ActivePlan(rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), instant(rs, 5),
                            instant(rs, 6), rs.getString(7), rs.getString(8), instant(rs, 9));
                    PlanPublicationPort.PlanEnd end = rs.getString(11) == null ? null
                            : new PlanPublicationPort.PlanEnd(rs.getString(11), instant(rs, 12),
                                    rs.getObject(13, UUID.class), rs.getLong(14), rs.getObject(15, UUID.class),
                                    instant(rs, 16), instant(rs, 17), rs.getString(18), nullableInstant(rs, 19),
                                    nullableInstant(rs, 20));
                    return new PlanPublicationPort.LatestPlan(plan, rs.getString(10), end,
                            PlanCompletionRepository.completion(rs, 20));
                })
                .optional();
    }

    java.util.List<PlanPublicationPort.LatestPlan> history(UUID userId) {
        return this.jdbc.sql("SELECT d.plan_id,d.intent_id,d.portfolio_id,d.p0_decimal,d.t0,d.end_at,d.period,"
                        + "d.target_rate,d.created_at,d.status,e.reason,e.ended_at,e.official_calculation_id,"
                        + "e.official_pointer_version,e.official_portfolio_snapshot_id,e.last_measured_at,"
                        + "e.cashflow_verified_through,e.completion,e.uncollected_from,e.uncollected_until,"
                        + PlanCompletionRepository.COLUMNS + " "
                        + "FROM plan_definition d LEFT JOIN plan_end e ON e.plan_id=d.plan_id AND e.user_id=d.user_id "
                        + "LEFT JOIN plan_completion c ON c.plan_id=d.plan_id AND c.user_id=d.user_id "
                        + "WHERE d.user_id=:u AND d.status IN ('ENDED_EARLY','COMPLETED') ORDER BY d.created_at DESC,d.plan_id DESC")
                .param("u", userId)
                .query((rs, n) -> {
                    var plan = new PlanPublicationPort.ActivePlan(rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), instant(rs, 5),
                            instant(rs, 6), rs.getString(7), rs.getString(8), instant(rs, 9));
                    PlanPublicationPort.PlanEnd end = rs.getString(11) == null ? null
                            : new PlanPublicationPort.PlanEnd(rs.getString(11), instant(rs, 12),
                                    rs.getObject(13, UUID.class), rs.getLong(14), rs.getObject(15, UUID.class),
                                    instant(rs, 16), instant(rs, 17), rs.getString(18), nullableInstant(rs, 19),
                                    nullableInstant(rs, 20));
                    return new PlanPublicationPort.LatestPlan(plan, rs.getString(10), end,
                            PlanCompletionRepository.completion(rs, 20));
                })
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
