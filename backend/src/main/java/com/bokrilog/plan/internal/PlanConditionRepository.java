package com.bokrilog.plan.internal;

import com.bokrilog.plan.PlanConditionApi.Intent;
import com.bokrilog.plan.PlanConditionApi.Period;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PlanConditionRepository {
    private final JdbcClient jdbc;
    PlanConditionRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    Optional<Intent> openIntent(UUID userId) {
        return jdbc.sql("SELECT intent_id,status,p0_decimal,t0,end_at,period,target_rate,portfolio_snapshot_id,created_at "
                + "FROM plan_condition_intent WHERE user_id=:u AND status='OPEN'")
            .param("u",userId).query((rs,n)->new Intent(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),instant(rs,4),instant(rs,5),Period.valueOf(rs.getString(6)),
                rs.getString(7),rs.getObject(8,UUID.class),instant(rs,9))).optional();
    }

    void invalidateOpenUnlessCurrentSnapshot(UUID userId,
            com.bokrilog.portfolio.PortfolioMembershipApi.FirstOfficialSnapshot snapshot, Instant now) {
        jdbc.sql("UPDATE plan_condition_intent SET status='INVALIDATED' WHERE user_id=:u AND status='OPEN' "
                + "AND (portfolio_id<>:p OR portfolio_snapshot_id<>:s OR membership_id<>:m "
                + "OR connection_id<>:c OR provider_account_id<>:a OR source_snapshot_id<>:ss "
                + "OR p0_decimal<>:p0 OR t0<>:t0 OR source_policy_version<>:sourcePolicy "
                + "OR portfolio_policy_version<>:portfolioPolicy OR end_at<=:now "
                + "OR calendar_policy<>'CALENDAR-SEOUL-1.0' OR calculation_policy<>'CALC-1.0')")
            .param("u",userId).param("p",snapshot.portfolioId()).param("s",snapshot.snapshotId())
            .param("m",snapshot.membershipId()).param("c",snapshot.connectionId())
            .param("a",snapshot.providerAccountId()).param("ss",snapshot.sourceSnapshotId())
            .param("p0",snapshot.decimal()).param("t0",utc(snapshot.measuredAt()))
            .param("sourcePolicy",snapshot.sourcePolicyVersion())
            .param("portfolioPolicy",snapshot.portfolioPolicyVersion()).param("now",utc(now)).update();
    }

    void lockUser(UUID userId) {
        jdbc.sql("WITH user_lock AS MATERIALIZED (SELECT pg_advisory_xact_lock(hashtextextended(:u,0))) "
                + "SELECT 1 FROM user_lock")
            .param("u",userId.toString()).query(Integer.class).single();
    }

    void insertConfirmation(String refHash, UUID userId, String sessionHash, String inputHash,
            com.bokrilog.portfolio.PortfolioMembershipApi.FirstOfficialSnapshot s,
            Instant endAt, Period period, String rate, Instant expiresAt) {
        jdbc.sql("INSERT INTO plan_condition_confirmation (confirmation_hash,user_id,session_hash,"
                + "issued_at,expires_at,canonical_hash,portfolio_id,portfolio_snapshot_id,membership_id,"
                + "connection_id,provider_account_id,source_snapshot_id,p0_decimal,t0,end_at,period,"
                + "target_rate,source_policy_version,portfolio_policy_version,calendar_policy,calculation_policy) "
                + "VALUES (:h,:u,:sh,now(),:expires,:ih,:p,:ps,:m,:c,:a,:ss,:p0,:t0,:end,:period,:rate,"
                + ":sourcePolicy,:portfolioPolicy,'CALENDAR-SEOUL-1.0','CALC-1.0')")
            .param("h",refHash).param("u",userId).param("sh",sessionHash).param("expires",utc(expiresAt))
            .param("ih",inputHash).param("p",s.portfolioId()).param("ps",s.snapshotId())
            .param("m",s.membershipId()).param("c",s.connectionId()).param("a",s.providerAccountId())
            .param("ss",s.sourceSnapshotId()).param("p0",s.decimal()).param("t0",utc(s.measuredAt()))
            .param("sourcePolicy",s.sourcePolicyVersion()).param("portfolioPolicy",s.portfolioPolicyVersion())
            .param("end",utc(endAt)).param("period",period.name()).param("rate",rate).update();
    }

    Optional<ConfirmationRow> confirmation(String hash, UUID userId, String sessionHash) {
        return jdbc.sql("SELECT canonical_hash,portfolio_id,portfolio_snapshot_id,membership_id,connection_id,"
                + "provider_account_id,source_snapshot_id,p0_decimal,t0,end_at,period,target_rate,"
                + "source_policy_version,portfolio_policy_version,calendar_policy,calculation_policy,"
                + "issued_at,expires_at,consumed_at "
                + "FROM plan_condition_confirmation WHERE confirmation_hash=:h AND user_id=:u "
                + "AND session_hash=:sh FOR UPDATE")
            .param("h",hash).param("u",userId).param("sh",sessionHash)
            .query((rs,n)->new ConfirmationRow(rs.getString(1),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),
                rs.getObject(6,UUID.class),rs.getObject(7,UUID.class),rs.getString(8),instant(rs,9),
                instant(rs,10),Period.valueOf(rs.getString(11)),rs.getString(12),rs.getString(13),
                rs.getString(14),rs.getString(15),rs.getString(16),instant(rs,17),instant(rs,18),
                rs.getObject(19,OffsetDateTime.class)==null ? null : instant(rs,19))).optional();
    }

    record ConfirmationRow(String canonicalHash, UUID portfolioId, UUID snapshotId, UUID membershipId,
            UUID connectionId, UUID providerAccountId, UUID sourceSnapshotId, String p0,
            Instant t0, Instant endAt, Period period, String rate, String sourcePolicyVersion,
            String portfolioPolicyVersion, String calendarPolicy, String calculationPolicy,
            Instant issuedAt, Instant expiresAt, Instant consumedAt) {}

    Optional<IdempotencyRow> idempotency(UUID userId, String key) {
        return jdbc.sql("SELECT canonical_hash,intent_id FROM plan_condition_idempotency "
                + "WHERE user_id=:u AND command='CONFIRM_PLAN_CONDITION' AND idempotency_key=:k")
            .param("u",userId).param("k",key)
            .query((rs,n)->new IdempotencyRow(rs.getString(1),rs.getObject(2,UUID.class))).optional();
    }
    record IdempotencyRow(String canonicalHash, UUID intentId) {}

    int saveIntent(UUID id, UUID userId, ConfirmationRow c) {
        return jdbc.sql("INSERT INTO plan_condition_intent (intent_id,user_id,portfolio_id,portfolio_snapshot_id,"
                + "membership_id,connection_id,provider_account_id,source_snapshot_id,p0_decimal,t0,end_at,period,"
                + "target_rate,source_policy_version,portfolio_policy_version,calendar_policy,calculation_policy,status) "
                + "VALUES (:id,:u,:p,:s,:m,:c,:a,:ss,:p0,:t0,:end,:period,:rate,:sourcePolicy,:portfolioPolicy,"
                + ":calendar,:calc,'OPEN')")
            .param("id",id).param("u",userId).param("p",c.portfolioId()).param("s",c.snapshotId())
            .param("m",c.membershipId()).param("c",c.connectionId()).param("a",c.providerAccountId())
            .param("ss",c.sourceSnapshotId()).param("p0",c.p0()).param("t0",utc(c.t0()))
            .param("end",utc(c.endAt())).param("period",c.period().name()).param("rate",c.rate())
            .param("sourcePolicy",c.sourcePolicyVersion()).param("portfolioPolicy",c.portfolioPolicyVersion())
            .param("calendar",c.calendarPolicy()).param("calc",c.calculationPolicy()).update();
    }

    Optional<Intent> intent(UUID userId, UUID id) {
        return jdbc.sql("SELECT intent_id,status,p0_decimal,t0,end_at,period,target_rate,portfolio_snapshot_id,created_at "
                + "FROM plan_condition_intent WHERE user_id=:u AND intent_id=:id")
            .param("u",userId).param("id",id).query((rs,n)->new Intent(rs.getObject(1,UUID.class),
                rs.getString(2),rs.getString(3),instant(rs,4),instant(rs,5),Period.valueOf(rs.getString(6)),
                rs.getString(7),rs.getObject(8,UUID.class),instant(rs,9))).optional();
    }

    Optional<CollectionIntentRow> collectionIntent(UUID userId, UUID id) {
        return jdbc.sql("SELECT intent_id,portfolio_id,connection_id,provider_account_id,t0,end_at,status "
                + "FROM plan_condition_intent WHERE user_id=:u AND intent_id=:id")
            .param("u",userId).param("id",id).query((rs,n)->new CollectionIntentRow(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),instant(rs,5),instant(rs,6),rs.getString(7))).optional();
    }
    record CollectionIntentRow(UUID intentId, UUID portfolioId, UUID connectionId,
            UUID providerAccountId, Instant t0, Instant expiresAt, String status) {}

    Optional<Intent> latestOpenForUpdate(UUID userId) {
        return jdbc.sql("SELECT intent_id,status,p0_decimal,t0,end_at,period,target_rate,portfolio_snapshot_id,created_at "
                + "FROM plan_condition_intent WHERE user_id=:u AND status='OPEN' FOR UPDATE")
            .param("u",userId).query((rs,n)->new Intent(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),instant(rs,4),instant(rs,5),Period.valueOf(rs.getString(6)),
                rs.getString(7),rs.getObject(8,UUID.class),instant(rs,9))).optional();
    }

    int replace(UUID userId, UUID oldId, UUID newId) {
        return jdbc.sql("UPDATE plan_condition_intent SET status='REPLACED',replaced_by=:new "
                + "WHERE user_id=:u AND intent_id=:old AND status='OPEN'")
            .param("new",newId).param("u",userId).param("old",oldId).update();
    }
    void invalidateOpen(UUID userId) {
        jdbc.sql("UPDATE plan_condition_intent SET status='INVALIDATED' WHERE user_id=:u AND status='OPEN'")
            .param("u",userId).update();
    }
    void invalidateExpired(UUID userId, Instant now) {
        jdbc.sql("UPDATE plan_condition_intent SET status='INVALIDATED' WHERE user_id=:u AND status='OPEN' "
                + "AND end_at<=:now")
            .param("u",userId).param("now",utc(now)).update();
    }
    int idempotency(UUID userId, String key, String hash, UUID intentId) {
        return jdbc.sql("INSERT INTO plan_condition_idempotency (user_id,command,idempotency_key,canonical_hash,intent_id) "
                + "VALUES (:u,'CONFIRM_PLAN_CONDITION',:k,:h,:id) ON CONFLICT DO NOTHING")
            .param("u",userId).param("k",key).param("h",hash).param("id",intentId).update();
    }
    void consume(String hash) {
        jdbc.sql("UPDATE plan_condition_confirmation SET consumed_at=now() WHERE confirmation_hash=:h")
            .param("h",hash).update();
    }
    /**
     * Erases this user's end and completion records, plans, intents, confirmations and idempotency rows. The end records go first:
     * they reference their plan (Story 2.13). Plans go next: they reference their intent immediately, while the
     * intent's reference back to its plan is deferred to the end of this transaction, by which time both are gone.
     */
    void erase(UUID userId) {
        jdbc.sql("DELETE FROM plan_end WHERE user_id=:u").param("u",userId).update();
        // The completion records too (Story 2.14): they reference their plan like the end records.
        jdbc.sql("DELETE FROM plan_completion WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM plan_definition WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM plan_condition_idempotency WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM plan_condition_confirmation WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM plan_condition_intent WHERE user_id=:u").param("u",userId).update();
    }

    void invalidateConfirmations(UUID userId) {
        jdbc.sql("UPDATE plan_condition_confirmation SET consumed_at=statement_timestamp() WHERE user_id=:u AND consumed_at IS NULL")
            .param("u",userId).update();
    }
    void linkConfirmation(String hash, com.bokrilog.sync.SyncJobApi.NextBaseline baseline) {
        jdbc.sql("UPDATE plan_condition_confirmation SET predecessor_plan_id=:p,baseline_job_id=:b WHERE confirmation_hash=:h")
            .param("p",baseline.predecessorPlanId()).param("b",baseline.jobId()).param("h",hash).update();
    }
    void linkIntent(UUID intentId, String hash) {
        jdbc.sql("UPDATE plan_condition_intent i SET predecessor_plan_id=c.predecessor_plan_id,baseline_job_id=c.baseline_job_id FROM plan_condition_confirmation c WHERE i.intent_id=:i AND c.confirmation_hash=:h AND i.user_id=c.user_id")
            .param("i",intentId).param("h",hash).update();
    }
    void linkPlan(UUID planId, UUID intentId) {
        jdbc.sql("UPDATE plan_definition d SET predecessor_plan_id=i.predecessor_plan_id,baseline_job_id=i.baseline_job_id FROM plan_condition_intent i WHERE d.plan_id=:p AND i.intent_id=:i AND d.user_id=i.user_id")
            .param("p",planId).param("i",intentId).update();
    }

    // ------------------------------------------------------------------ Story 2.7 active plan

    /** The user's active plan, if any. MVP has one portfolio per user and one active plan per portfolio. */
    Optional<ActivePlan> activePlan(UUID userId) {
        return jdbc.sql("SELECT plan_id,status,p0_decimal,t0,end_at,period,target_rate,basis_portfolio_snapshot_id,"
                + "intent_id,created_at,portfolio_id FROM plan_definition WHERE user_id=:u AND status='ACTIVE' "
                + "ORDER BY created_at,plan_id LIMIT 1")
            .param("u",userId).query((rs,n)->new ActivePlan(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),instant(rs,4),instant(rs,5),Period.valueOf(rs.getString(6)),rs.getString(7),
                rs.getObject(8,UUID.class),rs.getObject(9,UUID.class),instant(rs,10),rs.getObject(11,UUID.class)))
            .optional();
    }
    record ActivePlan(UUID planId, String status, String p0, Instant t0, Instant endAt, Period period,
            String rate, UUID basisSnapshotId, UUID intentId, Instant createdAt, UUID portfolioId) {}

    boolean activePlanForPortfolio(UUID portfolioId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM plan_definition WHERE portfolio_id=:p AND status='ACTIVE')")
            .param("p",portfolioId).query(Boolean.class).single();
    }

    /** The intent's immutable terms. {@code forUpdate} locks the row until the caller's transaction ends. */
    Optional<IntentTerms> intentTerms(UUID userId, UUID intentId, boolean forUpdate) {
        return jdbc.sql("SELECT intent_id,portfolio_id,portfolio_snapshot_id,membership_id,connection_id,"
                + "provider_account_id,source_snapshot_id,p0_decimal,t0,end_at,period,target_rate,"
                + "source_policy_version,portfolio_policy_version,calendar_policy,calculation_policy,status "
                + "FROM plan_condition_intent WHERE user_id=:u AND intent_id=:i" + (forUpdate ? " FOR UPDATE" : ""))
            .param("u",userId).param("i",intentId)
            .query((rs,n)->new IntentTerms(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),
                rs.getObject(6,UUID.class),rs.getObject(7,UUID.class),rs.getString(8),instant(rs,9),
                instant(rs,10),Period.valueOf(rs.getString(11)),rs.getString(12),rs.getString(13),
                rs.getString(14),rs.getString(15),rs.getString(16),rs.getString(17))).optional();
    }
    record IntentTerms(UUID intentId, UUID portfolioId, UUID snapshotId, UUID membershipId, UUID connectionId,
            UUID providerAccountId, UUID sourceSnapshotId, String p0, Instant t0, Instant endAt, Period period,
            String rate, String sourcePolicyVersion, String portfolioPolicyVersion, String calendarPolicy,
            String calculationPolicy, String status) {}

    /** The database's current time. Expiry inside the publishing transaction is judged against it. */
    Instant databaseNow() {
        return jdbc.sql("SELECT statement_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    int insertPlan(UUID planId, UUID userId, IntentTerms terms) {
        return jdbc.sql("INSERT INTO plan_definition(plan_id,user_id,portfolio_id,intent_id,basis_portfolio_snapshot_id,"
                + "membership_id,connection_id,provider_account_id,basis_source_snapshot_id,p0_decimal,t0,end_at,period,"
                + "target_rate,source_policy_version,portfolio_policy_version,calendar_policy,calculation_policy,status) "
                + "VALUES (:id,:u,:p,:i,:s,:m,:c,:a,:ss,:p0,:t0,:end,:period,:rate,:sourcePolicy,:portfolioPolicy,"
                + ":calendar,:calc,'ACTIVE')")
            .param("id",planId).param("u",userId).param("p",terms.portfolioId()).param("i",terms.intentId())
            .param("s",terms.snapshotId()).param("m",terms.membershipId()).param("c",terms.connectionId())
            .param("a",terms.providerAccountId()).param("ss",terms.sourceSnapshotId()).param("p0",terms.p0())
            .param("t0",utc(terms.t0())).param("end",utc(terms.endAt())).param("period",terms.period().name())
            .param("rate",terms.rate()).param("sourcePolicy",terms.sourcePolicyVersion())
            .param("portfolioPolicy",terms.portfolioPolicyVersion()).param("calendar",terms.calendarPolicy())
            .param("calc",terms.calculationPolicy()).update();
    }

    /** OPEN → CONSUMED, once, by the plan it became. */
    int consumeIntent(UUID userId, UUID intentId, UUID planId) {
        return jdbc.sql("UPDATE plan_condition_intent SET status='CONSUMED',consumed_at=now(),consumed_by_plan_id=:plan "
                + "WHERE user_id=:u AND intent_id=:i AND status='OPEN'")
            .param("plan",planId).param("u",userId).param("i",intentId).update();
    }

    private static OffsetDateTime utc(Instant instant) { return OffsetDateTime.ofInstant(instant,ZoneOffset.UTC); }
    private static Instant instant(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        return rs.getObject(column,OffsetDateTime.class).toInstant();
    }
}
