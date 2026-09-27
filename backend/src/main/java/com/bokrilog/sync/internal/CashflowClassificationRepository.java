package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncJobApi.ClassificationJob;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CashflowClassificationRepository {
    private final JdbcClient jdbc;
    CashflowClassificationRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    record Lease(UUID jobId, UUID userId, UUID portfolioId, UUID intentId, UUID connectionId,
            UUID providerAccountId, UUID candidateId, String policyVersion, long generation) {}
    record Input(UUID eventId, String sourceId, String semanticHash, String eventType,
            String currency, String cashFlow, Instant occurredAt, String accountType,
            UUID provenanceConnectionId) {}

    Optional<ClassificationJob> admit(UUID user, UUID intent, UUID portfolio, UUID connection,
            UUID account, String key) {
        Optional<UUID> candidate=latestEligibleCandidate(user,intent,portfolio,connection,account);
        if (candidate.isEmpty()) return Optional.empty();
        Optional<ClassificationJob> same=byCandidate(user,candidate.get(),CashflowDecisionTable.POLICY_VERSION);
        if (same.isPresent()) {
            ClassificationJob existing=same.get();
            if (!"CANCELLED".equals(existing.status())) return same;
            jdbc.sql("UPDATE sync_cashflow_classification_job SET status='QUEUED',failure_code=NULL,"
                    + "lease_until=NULL,finished_at=NULL,last_checked_at=now() WHERE job_id=:j AND status='CANCELLED'")
                .param("j",existing.jobId()).update();
            return byCandidate(user,candidate.get(),CashflowDecisionTable.POLICY_VERSION);
        }
        UUID id=UUID.randomUUID();
        jdbc.sql("INSERT INTO sync_cashflow_classification_job(job_id,user_id,portfolio_id,intent_id,connection_id,"
                + "provider_account_id,candidate_id,policy_version,idempotency_key,status) "
                + "VALUES (:j,:u,:p,:i,:c,:a,:candidate,:policy,:key,'QUEUED') "
                + "ON CONFLICT DO NOTHING")
            .param("j",id).param("u",user).param("p",portfolio).param("i",intent)
            .param("c",connection).param("a",account).param("candidate",candidate.get())
            .param("policy",CashflowDecisionTable.POLICY_VERSION).param("key",key).update();
        return byCandidate(user,candidate.get(),CashflowDecisionTable.POLICY_VERSION);
    }

    private Optional<UUID> latestEligibleCandidate(UUID user,UUID intent,UUID portfolio,UUID connection,UUID account) {
        return jdbc.sql("SELECT b.candidate_id FROM sync_initial_candidate_bundle b "
                + "JOIN sync_initial_collection_job j ON j.job_id=b.job_id AND j.status='INPUTS_COMPLETE' "
                + "WHERE b.user_id=:u AND b.intent_id=:i AND b.portfolio_id=:p AND b.connection_id=:c "
                + "AND b.provider_account_id=:a AND b.completeness_hash ~ '^[0-9a-f]{64}$' "
                + "AND (SELECT count(DISTINCT e.event_type) FROM sync_initial_page_evidence e WHERE e.job_id=j.job_id)=2 "
                + "AND NOT EXISTS (SELECT 1 FROM sync_initial_candidate_event ce "
                + "JOIN sync_initial_source_event se ON se.event_id=ce.event_id "
                + "LEFT JOIN sync_initial_event_provenance ep ON ep.job_id=j.job_id AND ep.event_id=se.event_id "
                + "WHERE ce.candidate_id=b.candidate_id AND (ep.event_id IS NULL OR ep.semantic_hash<>se.semantic_hash "
                + "OR se.user_id<>b.user_id OR se.provider_account_id<>b.provider_account_id "
                + "OR ep.connection_id<>b.connection_id OR se.account_type<>'UNIFIED' "
                + "OR se.transaction_time<=b.t0 OR se.transaction_time>b.tc)) "
                + "AND (SELECT count(*) FROM sync_initial_candidate_event ce WHERE ce.candidate_id=b.candidate_id)="
                + "(SELECT count(*) FROM sync_initial_source_event se WHERE se.user_id=b.user_id "
                + "AND se.provider_account_id=b.provider_account_id AND se.transaction_time>b.t0 AND se.transaction_time<=b.tc) "
                + "ORDER BY b.completed_at DESC,b.candidate_id DESC LIMIT 1")
            .param("u",user).param("i",intent).param("p",portfolio).param("c",connection).param("a",account)
            .query(UUID.class).optional();
    }
    Optional<ClassificationJob> byCandidate(UUID user,UUID candidate,String policy) {
        return jdbc.sql("SELECT j.job_id,j.status,j.failure_code,b.processed_at,b.deposit_count,b.withdrawal_count,"
                + "b.excluded_count,b.review_count,(SELECT reason_code FROM sync_cashflow_classification_event e "
                + "WHERE e.classification_id=b.classification_id AND e.verdict='REVIEW_REQUIRED' "
                + "ORDER BY e.reason_code LIMIT 1) FROM sync_cashflow_classification_job j "
                + "LEFT JOIN sync_cashflow_classification_bundle b ON b.job_id=j.job_id "
                + "WHERE j.user_id=:u AND j.candidate_id=:candidate AND j.policy_version=:policy")
            .param("u",user).param("candidate",candidate).param("policy",policy)
            .query((rs,n)->new ClassificationJob(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                instantOrNull(rs,4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getString(9))).optional();
    }
    Optional<ClassificationJob> latest(UUID user, UUID intent) {
        return jdbc.sql("SELECT j.job_id,j.status,j.failure_code,b.processed_at,b.deposit_count,b.withdrawal_count,"
                + "b.excluded_count,b.review_count,(SELECT reason_code FROM sync_cashflow_classification_event e "
                + "WHERE e.classification_id=b.classification_id AND e.verdict='REVIEW_REQUIRED' "
                + "ORDER BY e.reason_code LIMIT 1) FROM sync_cashflow_classification_job j "
                + "LEFT JOIN sync_cashflow_classification_bundle b ON b.job_id=j.job_id "
                + "WHERE j.user_id=:u AND j.intent_id=:i AND j.candidate_id=(SELECT b.candidate_id "
                + "FROM sync_initial_candidate_bundle b JOIN sync_initial_collection_job c ON c.job_id=b.job_id "
                + "AND c.status='INPUTS_COMPLETE' WHERE b.user_id=:u AND b.intent_id=:i "
                + "ORDER BY b.completed_at DESC,b.candidate_id DESC LIMIT 1) "
                + "ORDER BY j.created_at DESC,j.job_id DESC LIMIT 1")
            .param("u",user).param("i",intent)
            .query((rs,n)->new ClassificationJob(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                instantOrNull(rs,4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getString(9))).optional();
    }
    Optional<Lease> claim() {
        return jdbc.sql("UPDATE sync_cashflow_classification_job SET status='RUNNING',fencing_generation=fencing_generation+1,"
                + "lease_until=now()+interval '30 seconds',last_checked_at=now() WHERE job_id=(SELECT job_id "
                + "FROM sync_cashflow_classification_job WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<now()) "
                + "ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING job_id,user_id,portfolio_id,intent_id,"
                + "connection_id,provider_account_id,candidate_id,policy_version,fencing_generation")
            .query((rs,n)->new Lease(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),rs.getObject(6,UUID.class),rs.getObject(7,UUID.class),
                rs.getString(8),rs.getLong(9))).optional();
    }
    boolean lockOwned(Lease l) {
        return jdbc.sql("SELECT job_id FROM sync_cashflow_classification_job WHERE job_id=:j AND user_id=:u "
                + "AND status='RUNNING' AND fencing_generation=:g AND lease_until>now() FOR UPDATE")
            .param("j",l.jobId()).param("u",l.userId()).param("g",l.generation())
            .query(UUID.class).optional().isPresent();
    }
    Optional<String> inputHash(Lease l) {
        Optional<CandidateEvidence> evidence=jdbc.sql("SELECT b.completeness_hash,b.job_id,b.portfolio_id,b.intent_id,"
                + "b.connection_id,b.provider_account_id,b.t0,b.tc,b.equity_decimal,b.measured_at,b.policy_version "
                + "FROM sync_initial_candidate_bundle b "
                + "JOIN sync_initial_collection_job j ON j.job_id=b.job_id AND j.status='INPUTS_COMPLETE' "
                + "WHERE b.candidate_id=:candidate AND b.user_id=:u AND b.portfolio_id=:p AND b.intent_id=:i "
                + "AND b.connection_id=:c AND b.provider_account_id=:a AND b.completeness_hash ~ '^[0-9a-f]{64}$' "
                + "AND (SELECT count(DISTINCT pe.event_type) FROM sync_initial_page_evidence pe WHERE pe.job_id=j.job_id)=2 "
                + "AND NOT EXISTS (SELECT 1 FROM sync_initial_candidate_event ce "
                + "JOIN sync_initial_source_event se ON se.event_id=ce.event_id "
                + "LEFT JOIN sync_initial_event_provenance ep ON ep.job_id=j.job_id AND ep.event_id=se.event_id "
                + "WHERE ce.candidate_id=b.candidate_id AND (ep.event_id IS NULL OR ep.semantic_hash<>se.semantic_hash "
                + "OR se.user_id<>b.user_id OR se.provider_account_id<>b.provider_account_id "
                + "OR ep.connection_id<>b.connection_id OR se.account_type<>'UNIFIED' "
                + "OR se.transaction_time<=b.t0 OR se.transaction_time>b.tc)) "
                + "AND (SELECT count(*) FROM sync_initial_candidate_event ce WHERE ce.candidate_id=b.candidate_id)="
                + "(SELECT count(*) FROM sync_initial_source_event se WHERE se.user_id=b.user_id "
                + "AND se.provider_account_id=b.provider_account_id AND se.transaction_time>b.t0 AND se.transaction_time<=b.tc)")
            .param("candidate",l.candidateId()).param("u",l.userId()).param("p",l.portfolioId())
            .param("i",l.intentId()).param("c",l.connectionId()).param("a",l.providerAccountId())
            .query((rs,n)->new CandidateEvidence(rs.getString(1),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),rs.getObject(6,UUID.class),
                rs.getObject(7,OffsetDateTime.class).toInstant(),rs.getObject(8,OffsetDateTime.class).toInstant(),
                rs.getString(9),rs.getObject(10,OffsetDateTime.class).toInstant(),rs.getString(11))).optional();
        return evidence.filter(e->evidenceHash(l,e).equals(e.completenessHash())).map(CandidateEvidence::completenessHash);
    }
    private record CandidateEvidence(String completenessHash,UUID jobId,UUID portfolioId,UUID intentId,
            UUID connectionId,UUID accountId,Instant t0,Instant tc,String equity,Instant measuredAt,String policy) {}
    private String evidenceHash(Lease lease,CandidateEvidence e) {
        String pages=jdbc.sql("SELECT string_agg(event_type||'|'||range_start::text||'|'||range_end::text||'|'||"
                + "cursor_hash||'|'||item_count||'|'||COALESCE(first_event_at::text,'')||'|'||"
                + "COALESCE(last_event_at::text,'')||'|'||has_next_page::text,';' ORDER BY event_type,range_start,cursor_hash) "
                + "FROM sync_initial_page_evidence WHERE job_id=:j").param("j",e.jobId()).query(String.class).single();
        String events=jdbc.sql("SELECT COALESCE(string_agg(source_id||'|'||semantic_hash,';' ORDER BY source_id),'') "
                + "FROM sync_initial_candidate_event ce JOIN sync_initial_source_event se ON se.event_id=ce.event_id "
                + "WHERE ce.candidate_id=:candidate").param("candidate",lease.candidateId()).query(String.class).single();
        String value=e.jobId()+"|"+e.portfolioId()+"|"+e.intentId()+"|"+e.connectionId()+"|"+e.accountId()+"|"
                +e.t0()+"|"+e.tc()+"|"+e.equity()+"|"+e.measuredAt()+"|"+e.policy()+"|"+pages+"|"+events;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    List<Input> inputs(Lease l) {
        return jdbc.sql("SELECT se.event_id,se.source_id,se.semantic_hash,se.event_type,se.currency,se.cash_flow,"
                + "se.transaction_time,se.account_type,ep.connection_id FROM sync_initial_candidate_event ce "
                + "JOIN sync_initial_source_event se ON se.event_id=ce.event_id "
                + "JOIN sync_initial_candidate_bundle b ON b.candidate_id=ce.candidate_id "
                + "JOIN sync_initial_event_provenance ep ON ep.job_id=b.job_id AND ep.event_id=se.event_id "
                + "WHERE ce.candidate_id=:candidate AND se.user_id=:u AND se.provider_account_id=:a "
                + "AND ep.semantic_hash=se.semantic_hash ORDER BY se.source_id,se.event_id")
            .param("candidate",l.candidateId()).param("u",l.userId()).param("a",l.providerAccountId())
            .query((rs,n)->new Input(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getObject(7,OffsetDateTime.class).toInstant(),rs.getString(8),
                rs.getObject(9,UUID.class))).list();
    }
    boolean publish(Lease l,String inputHash,List<Input> inputs,List<CashflowDecisionTable.Decision> decisions,
            BooleanSupplier stillPermitted) {
        if (inputs.size()!=decisions.size()) throw new IllegalArgumentException("classification result count mismatch");
        int deposits=0,withdrawals=0,excluded=0,reviews=0;
        for(var d:decisions) switch(d.verdict()) {
            case EXTERNAL_IN -> deposits++;
            case EXTERNAL_OUT -> withdrawals++;
            case EXCLUDED -> excluded++;
            case REVIEW_REQUIRED -> reviews++;
        }
        String status=reviews==0?"COMPLETE":"NEEDS_REVIEW";
        UUID classificationId=UUID.randomUUID();
        jdbc.sql("INSERT INTO sync_cashflow_classification_bundle(classification_id,job_id,user_id,portfolio_id,intent_id,"
                + "connection_id,provider_account_id,candidate_id,input_scope_hash,policy_version,status,deposit_count,"
                + "withdrawal_count,excluded_count,review_count) VALUES (:id,:j,:u,:p,:i,:c,:a,:candidate,:hash,:policy,"
                + ":status,:deposits,:withdrawals,:excluded,:reviews)")
            .param("id",classificationId).param("j",l.jobId()).param("u",l.userId()).param("p",l.portfolioId())
            .param("i",l.intentId()).param("c",l.connectionId()).param("a",l.providerAccountId())
            .param("candidate",l.candidateId()).param("hash",inputHash).param("policy",l.policyVersion())
            .param("status",status).param("deposits",deposits).param("withdrawals",withdrawals)
            .param("excluded",excluded).param("reviews",reviews).update();
        Instant processedAt=jdbc.sql("SELECT date_trunc('milliseconds',now())").query(OffsetDateTime.class).single().toInstant();
        List<String> summaryKeys=new ArrayList<>();
        for(int i=0;i<inputs.size();i++) {
            Input in=inputs.get(i); CashflowDecisionTable.Decision d=decisions.get(i);
            jdbc.sql("INSERT INTO sync_cashflow_classification_event(classification_id,source_event_id,source_id,source_revision,"
                    + "semantic_hash,provenance_connection_id,verdict,reason_code,rule_id,policy_version,signed_amount,asset_code,"
                    + "source_quantity,occurred_at,processed_at) VALUES (:classification,:event,:source,1,:hash,:connection,"
                    + ":verdict,:reason,:rule,:policy,:amount,:asset,:quantity,:occurred,:processed)")
                .param("classification",classificationId).param("event",in.eventId()).param("source",in.sourceId())
                .param("hash",in.semanticHash()).param("connection",in.provenanceConnectionId())
                .param("verdict",d.verdict().name()).param("reason",d.reasonCode()).param("rule",d.ruleId())
                .param("policy",l.policyVersion()).param("amount",d.signedAmount()).param("asset",in.currency())
                .param("quantity",canonicalSourceQuantity(in.cashFlow()))
                .param("occurred",utc(in.occurredAt())).param("processed",utc(processedAt)).update();
            if (d.verdict()==CashflowDecisionTable.Verdict.EXTERNAL_IN
                    ||d.verdict()==CashflowDecisionTable.Verdict.EXTERNAL_OUT) {
                if (reviews==0) jdbc.sql("INSERT INTO sync_cashflow_flow_candidate(classification_id,source_event_id,"
                        + "signed_amount,asset_code,source_quantity,occurred_at) VALUES (:classification,:event,:amount,:asset,:quantity,:occurred)")
                    .param("classification",classificationId).param("event",in.eventId()).param("amount",d.signedAmount())
                    .param("asset",in.currency()).param("quantity",
                            CashflowDecisionTable.canonicalDecimal(new BigDecimal(in.cashFlow()).abs()))
                    .param("occurred",utc(in.occurredAt())).update();
            } else if (d.verdict()==CashflowDecisionTable.Verdict.EXCLUDED) {
                String key=d.reasonCode()+"|"+d.ruleId();
                if (!summaryKeys.contains(key)) {
                    summaryKeys.add(key);
                    jdbc.sql("INSERT INTO sync_cashflow_exclusion_summary(classification_id,reason_code,rule_id,event_count) "
                            + "VALUES (:classification,:reason,:rule,1) ON CONFLICT(classification_id,reason_code,rule_id) "
                            + "DO UPDATE SET event_count=sync_cashflow_exclusion_summary.event_count+1")
                        .param("classification",classificationId).param("reason",d.reasonCode()).param("rule",d.ruleId()).update();
                } else jdbc.sql("UPDATE sync_cashflow_exclusion_summary SET event_count=event_count+1 "
                        + "WHERE classification_id=:classification AND reason_code=:reason AND rule_id=:rule")
                    .param("classification",classificationId).param("reason",d.reasonCode()).param("rule",d.ruleId()).update();
            }
        }
        if (!stillPermitted.getAsBoolean()) throw new PublicationBlocked();
        int updated=jdbc.sql("UPDATE sync_cashflow_classification_job SET status=:status,failure_code=:failure,"
                + "lease_until=NULL,last_checked_at=now(),finished_at=now() WHERE job_id=:j AND status='RUNNING' "
                + "AND fencing_generation=:g AND lease_until>now()")
            .param("status",status).param("failure",reviews==0?null:"CLASSIFICATION_REQUIRES_REVIEW")
            .param("j",l.jobId()).param("g",l.generation()).update();
        if(updated!=1) throw new IllegalStateException("classification lease lost");
        return true;
    }
    static final class PublicationBlocked extends RuntimeException {
        private static final long serialVersionUID=1L;
    }
    int finish(Lease l,String status,String code) {
        return jdbc.sql("UPDATE sync_cashflow_classification_job SET status=:s,failure_code=:c,lease_until=NULL,"
                + "last_checked_at=now(),finished_at=now() WHERE job_id=:j AND status='RUNNING' AND fencing_generation=:g "
                + "AND lease_until>now()")
            .param("s",status).param("c",code).param("j",l.jobId()).param("g",l.generation()).update();
    }
    int erase(UUID user) {
        jdbc.sql("DELETE FROM sync_cashflow_classification_bundle WHERE user_id=:u").param("u",user).update();
        return jdbc.sql("DELETE FROM sync_cashflow_classification_job WHERE user_id=:u").param("u",user).update();
    }
    private static @Nullable Instant instantOrNull(java.sql.ResultSet rs,int col) throws java.sql.SQLException {
        OffsetDateTime value=rs.getObject(col,OffsetDateTime.class); return value==null?null:value.toInstant();
    }
    private static @Nullable String canonicalSourceQuantity(String value) {
        try { return CashflowDecisionTable.canonicalDecimal(new BigDecimal(value).abs()); }
        catch (NumberFormatException invalid) { return null; }
    }
    private static OffsetDateTime utc(Instant value) { return OffsetDateTime.ofInstant(value,ZoneOffset.UTC); }
}
