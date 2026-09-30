package com.bokrilog.sync.internal;

import com.bokrilog.source.SourceConnectionApi.CollectionEvent;
import com.bokrilog.source.SourceConnectionApi.CollectionObservation;
import com.bokrilog.source.SourceConnectionApi.PageEvidence;
import com.bokrilog.sync.SyncJobApi.CollectionJob;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InitialCollectionRepository {
    private static final long MAX_WINDOW_MILLIS=7L*24*60*60*1000;
    private static final int MAX_SOURCE_ID_BYTES=512;
    private static final int MAX_CASH_FLOW_CHARS=128;
    private static final int MAX_CASH_FLOW_PRECISION=38;
    private static final int MAX_CASH_FLOW_SCALE=18;
    private final JdbcClient jdbc;
    InitialCollectionRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

    record Lease(UUID jobId, UUID userId, UUID portfolioId, UUID intentId, UUID connectionId,
            UUID providerAccountId, Instant t0, @Nullable Instant tc, long generation, @Nullable UUID planSyncJobId) {
        Lease(UUID jobId, UUID userId, UUID portfolioId, UUID intentId, UUID connectionId, UUID providerAccountId,
                Instant t0, @Nullable Instant tc, long generation) {
            this(jobId, userId, portfolioId, intentId, connectionId, providerAccountId, t0, tc, generation, null);
        }
    }
    record StoredInput(@Nullable UUID candidateId, String status, @Nullable String failureCode) {}

    Optional<CollectionJob> byId(UUID userId, UUID jobId) {
        return jdbc.sql("SELECT job_id,status,failure_code,last_checked_at FROM sync_initial_collection_job "
                + "WHERE user_id=:u AND job_id=:j").param("u",userId).param("j",jobId)
            .query((rs,n)->job(rs)).optional();
    }
    Optional<CollectionJob> latest(UUID userId) {
        return jdbc.sql("SELECT job_id,status,failure_code,last_checked_at FROM sync_initial_collection_job "
                + "WHERE user_id=:u ORDER BY created_at DESC,job_id DESC LIMIT 1").param("u",userId)
            .query((rs,n)->job(rs)).optional();
    }
    Optional<CollectionJob> latest(UUID userId,UUID intentId) {
        return jdbc.sql("SELECT job_id,status,failure_code,last_checked_at FROM sync_initial_collection_job "
                + "WHERE user_id=:u AND intent_id=:i ORDER BY created_at DESC,job_id DESC LIMIT 1")
            .param("u",userId).param("i",intentId).query((rs,n)->job(rs)).optional();
    }
    Optional<CollectionJob> byKey(UUID userId, UUID intentId, String key) {
        return jdbc.sql("SELECT job_id,status,failure_code,last_checked_at FROM sync_initial_collection_job "
                + "WHERE user_id=:u AND intent_id=:i AND idempotency_key=:k")
            .param("u",userId).param("i",intentId).param("k",key).query((rs,n)->job(rs)).optional();
    }
    Optional<CollectionJob> active(UUID userId, UUID intentId) {
        return jdbc.sql("SELECT job_id,status,failure_code,last_checked_at FROM sync_initial_collection_job "
                + "WHERE user_id=:u AND intent_id=:i AND status IN ('QUEUED','RUNNING') ORDER BY created_at LIMIT 1")
            .param("u",userId).param("i",intentId).query((rs,n)->job(rs)).optional();
    }
    private static CollectionJob job(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime checked=rs.getObject(4,OffsetDateTime.class);
        return new CollectionJob(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                checked==null?null:checked.toInstant());
    }
    int insert(UUID id, UUID user, UUID portfolio, UUID intent, UUID connection, UUID account,
            String key, Instant t0) {
        return jdbc.sql("INSERT INTO sync_initial_collection_job(job_id,user_id,portfolio_id,intent_id,"
                + "connection_id,provider_account_id,idempotency_key,status,t0) "
                + "VALUES (:j,:u,:p,:i,:c,:a,:k,'QUEUED',:t0) ON CONFLICT(user_id,intent_id,idempotency_key) DO NOTHING")
            .param("j",id).param("u",user).param("p",portfolio).param("i",intent).param("c",connection)
            .param("a",account).param("k",key).param("t0",utc(t0)).update();
    }
    Optional<Lease> claim() {
        return jdbc.sql("UPDATE sync_initial_collection_job SET status='RUNNING',fencing_generation=fencing_generation+1,"
                + "lease_until=now()+interval '30 seconds',heartbeat_at=now(),last_checked_at=now() "
                + "WHERE job_id=(SELECT job_id FROM sync_initial_collection_job WHERE plan_sync_job_id IS NULL AND "
                + "(status='QUEUED' OR (status='RUNNING' AND lease_until<now())) ORDER BY created_at "
                + "FOR UPDATE SKIP LOCKED LIMIT 1) "
                + "RETURNING job_id,user_id,portfolio_id,intent_id,connection_id,provider_account_id,t0,tc,fencing_generation")
            .query((rs,n)->new Lease(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),
                rs.getObject(6,UUID.class),rs.getObject(7,OffsetDateTime.class).toInstant(),
                rs.getObject(8,OffsetDateTime.class)==null?null:rs.getObject(8,OffsetDateTime.class).toInstant(),
                rs.getLong(9))).optional();
    }
    /**
     * The collection stage of an incremental sync (Story 2.11): a child row the sync attempt owns. It starts
     * running, with a lease of its own the attempt's heartbeat renews and the first fencing generation, and its
     * scope is fixed now — the plan's {@code t0}, the attempt's {@code tc}, its window start and the base
     * candidate whose events it carries. The independent claim never picks it.
     */
    Lease insertIncremental(UUID childId, PlanSyncRepository.Lease sync, Instant tc, Instant windowStart) {
        jdbc.sql("INSERT INTO sync_initial_collection_job(job_id,user_id,portfolio_id,intent_id,connection_id,"
                + "provider_account_id,idempotency_key,status,t0,tc,fencing_generation,lease_until,heartbeat_at,"
                + "last_checked_at,plan_sync_job_id,window_start,base_candidate_id) VALUES (:j,:u,:p,:i,:c,:a,:k,"
                + "'RUNNING',:t0,:tc,1,now()+interval '30 seconds',now(),now(),:sync,:w,:base)")
            .param("j",childId).param("u",sync.userId()).param("p",sync.portfolioId()).param("i",sync.intentId())
            .param("c",sync.connectionId()).param("a",sync.providerAccountId())
            .param("k","plan-sync:"+sync.jobId()+":collection").param("t0",utc(sync.t0())).param("tc",utc(tc))
            .param("sync",sync.jobId()).param("w",utc(windowStart)).param("base",sync.baseCandidateId()).update();
        return new Lease(childId,sync.userId(),sync.portfolioId(),sync.intentId(),sync.connectionId(),
                sync.providerAccountId(),sync.t0(),tc,1,sync.jobId());
    }
    boolean fixScope(Lease l, Instant tc) {
        int updated=jdbc.sql("UPDATE sync_initial_collection_job SET tc=:tc,last_checked_at=now() WHERE job_id=:j "
                + "AND status='RUNNING' AND fencing_generation=:g AND lease_until>now() AND tc IS NULL")
            .param("tc",utc(tc)).param("j",l.jobId()).param("g",l.generation()).update();
        if (updated==1) return true;
        return jdbc.sql("SELECT tc FROM sync_initial_collection_job WHERE job_id=:j AND status='RUNNING' "
                + "AND fencing_generation=:g AND lease_until>now()")
            .param("j",l.jobId()).param("g",l.generation()).query((rs,n)->rs.getObject(1,OffsetDateTime.class))
            .optional().map(existing->existing.toInstant().equals(tc)).orElse(false);
    }
    boolean lockOwned(Lease l) {
        return jdbc.sql("SELECT job_id FROM sync_initial_collection_job WHERE job_id=:j AND user_id=:u "
                + "AND connection_id=:c AND provider_account_id=:a AND status='RUNNING' "
                + "AND fencing_generation=:g AND lease_until>now() FOR UPDATE")
            .param("j",l.jobId()).param("u",l.userId()).param("c",l.connectionId())
            .param("a",l.providerAccountId()).param("g",l.generation()).query(UUID.class).optional().isPresent();
    }
    int heartbeat(Lease l) {
        return jdbc.sql("UPDATE sync_initial_collection_job SET lease_until=now()+interval '30 seconds',"
                + "heartbeat_at=now(),last_checked_at=now() WHERE job_id=:j AND fencing_generation=:g "
                + "AND status='RUNNING' AND lease_until>now()")
            .param("j",l.jobId()).param("g",l.generation()).update();
    }
    int finish(Lease l, String status, @Nullable String code) {
        return jdbc.sql("UPDATE sync_initial_collection_job SET status=:s,failure_code=:c,lease_until=NULL,"
                + "last_checked_at=now(),finished_at=now() WHERE job_id=:j AND fencing_generation=:g "
                + "AND status='RUNNING' AND lease_until>now()")
            .param("s",status).param("c",code).param("j",l.jobId()).param("g",l.generation()).update();
    }
    boolean owns(Lease l) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_initial_collection_job WHERE job_id=:j AND user_id=:u "
                + "AND status='RUNNING' AND fencing_generation=:g AND lease_until>now())")
            .param("j",l.jobId()).param("u",l.userId()).param("g",l.generation()).query(Boolean.class).single();
    }

    boolean storePage(Lease l, PageEvidence p, List<CollectionEvent> pageEvents, boolean pageReview) {
        UUID evidenceId=jdbc.sql("INSERT INTO sync_initial_page_evidence(evidence_id,job_id,user_id,event_type,range_start,range_end,"
                + "cursor_hash,requested_at,received_at,item_count,first_event_at,last_event_at,has_next_page) "
                + "VALUES (:id,:j,:u,:type,:start,:end,:cursor,:requested,:received,:count,:first,:last,:next) "
                + "ON CONFLICT(job_id,event_type,range_start,range_end,cursor_hash) DO UPDATE "
                + "SET evidence_id=sync_initial_page_evidence.evidence_id RETURNING evidence_id")
            .param("id",UUID.randomUUID()).param("j",l.jobId()).param("u",l.userId()).param("type",p.type())
            .param("start",utc(p.startAt())).param("end",utc(p.endAt())).param("cursor",p.cursorHash())
            .param("requested",utc(p.requestedAt())).param("received",utc(p.receivedAt())).param("count",p.count())
            .param("first",p.firstEventAt()==null?null:utc(p.firstEventAt()))
            .param("last",p.lastEventAt()==null?null:utc(p.lastEventAt())).param("next",p.hasNextPage())
            .query(UUID.class).single();
        int progress=jdbc.sql("UPDATE sync_initial_collection_job SET last_checked_at=now() WHERE job_id=:j "
                + "AND status='RUNNING' AND fencing_generation=:g AND lease_until>now()")
            .param("j",l.jobId()).param("g",l.generation()).update();
        if (progress!=1) throw new IllegalStateException("collection lease lost");

        Instant tc=jdbc.sql("SELECT tc FROM sync_initial_collection_job WHERE job_id=:j")
                .param("j",l.jobId()).query((rs,n)->rs.getObject(1,OffsetDateTime.class))
                .optional().map(OffsetDateTime::toInstant).orElseThrow(()->new IllegalStateException("collection scope missing"));
        Map<String,Parsed> valid=new LinkedHashMap<>();
        boolean review=pageReview;
        for (CollectionEvent event:pageEvents) {
            Parsed parsed=parse(event,l.t0(),tc);
            if (parsed.diagnostic()!=null) {
                observation(l,evidenceId,event,parsed.diagnostic(),parsed.hash());
                review=true;
            } else {
                Parsed prior=valid.putIfAbsent(event.sourceId(),parsed);
                if (prior!=null&&!prior.hash().equals(parsed.hash())) {
                    observation(l,evidenceId,event,"PROVIDER_ID_CONFLICT",parsed.hash());
                    review=true;
                }
            }
        }
        if (!valid.isEmpty()) {
            StringBuilder sql=new StringBuilder("INSERT INTO sync_initial_source_event(event_id,user_id,provider_account_id,source_id,"
                    + "event_type,currency,cash_flow,transaction_time,account_type,semantic_hash,observed_connection_id) VALUES ");
            int i=0;
            for (Map.Entry<String,Parsed> entry:valid.entrySet()) {
                if (i>0) sql.append(',');
                sql.append("(:id").append(i).append(",:u,:a,:source").append(i).append(",:type").append(i)
                    .append(",:currency").append(i).append(",:flow").append(i).append(",:time").append(i)
                    .append(",'UNIFIED',:hash").append(i).append(",:connection)");
                i++;
            }
            sql.append(" ON CONFLICT(provider_account_id,source_id) DO NOTHING");
            var statement=jdbc.sql(sql.toString()).param("u",l.userId()).param("a",l.providerAccountId())
                    .param("connection",l.connectionId());
            i=0;
            for (Map.Entry<String,Parsed> entry:valid.entrySet()) {
                Parsed x=entry.getValue();
                statement.param("id"+i,UUID.randomUUID()).param("source"+i,entry.getKey())
                    .param("type"+i,x.type()).param("currency"+i,x.currency()).param("flow"+i,x.flow())
                    .param("time"+i,utc(x.time())).param("hash"+i,x.hash());
                i++;
            }
            statement.update();
            String names=String.join(",",java.util.Collections.nCopies(valid.size(),"?"));
            String query="SELECT source_id,event_id,semantic_hash FROM sync_initial_source_event "
                    + "WHERE provider_account_id=? AND source_id IN ("+names+")";
            List<Object> args=new ArrayList<>(); args.add(l.providerAccountId()); args.addAll(valid.keySet());
            Map<String,EventIdentity> stored=jdbc.sql(query).params(args)
                    .query((rs,n)->new EventIdentity(rs.getString(1),rs.getObject(2,UUID.class),rs.getString(3)))
                    .list().stream().collect(java.util.stream.Collectors.toMap(EventIdentity::sourceId,x->x));
            List<EventIdentity> matching=new ArrayList<>();
            for (Map.Entry<String,Parsed> entry:valid.entrySet()) {
                EventIdentity existing=stored.get(entry.getKey());
                if (existing==null) { review=true; continue; }
                if (!existing.semanticHash().equals(entry.getValue().hash())) {
                    CollectionEvent event=pageEvents.stream().filter(x->entry.getKey().equals(x.sourceId())).findFirst().orElseThrow();
                    observation(l,evidenceId,event,"PROVIDER_ID_CONFLICT",entry.getValue().hash());
                    review=true;
                } else matching.add(existing);
            }
            if (!matching.isEmpty()) {
                StringBuilder provenanceSql=new StringBuilder("INSERT INTO sync_initial_event_provenance(job_id,event_id,connection_id,semantic_hash) VALUES ");
                for(int idx=0;idx<matching.size();idx++) {
                    if(idx>0) provenanceSql.append(',');
                    provenanceSql.append("(:job,:event").append(idx).append(",:connection,:hash").append(idx).append(')');
                }
                provenanceSql.append(" ON CONFLICT(job_id,event_id) DO NOTHING");
                var provenance=jdbc.sql(provenanceSql.toString()).param("job",l.jobId()).param("connection",l.connectionId());
                for(int idx=0;idx<matching.size();idx++) provenance.param("event"+idx,matching.get(idx).eventId())
                        .param("hash"+idx,matching.get(idx).semanticHash());
                provenance.update();
            }
        }
        if (review) {
            // Kept on the collection (Story 2.12): a resumed holder, or a continuation's second phase, still knows it.
            jdbc.sql("UPDATE sync_initial_collection_job SET page_review=true WHERE job_id=:j AND status='RUNNING' "
                    + "AND fencing_generation=:g AND lease_until>now()")
                .param("j",l.jobId()).param("g",l.generation()).update();
        }
        return review;
    }

    /** Whether a page of this collection asked for review ({@link #storePage}), whichever holder stored it. */
    boolean pageReviewed(UUID jobId) {
        return jdbc.sql("SELECT page_review FROM sync_initial_collection_job WHERE job_id=:j")
            .param("j",jobId).query(Boolean.class).optional().orElse(false);
    }

    /**
     * Discards the page evidence of windows a resumed collection reads again (Story 2.12): an earlier holder stopped
     * inside them, and its pages would otherwise stay beside the new ones — the page insert keeps the first row of a
     * cursor, so an old {@code has_next_page} could mark a window complete that was not. Their diagnostics go with
     * them (the observation rows cascade). Complete windows are never passed here. Fenced.
     */
    void discardPartialWindows(Lease l,List<VerificationPlanner.Interval> windows) {
        for (VerificationPlanner.Interval window:windows) {
            jdbc.sql("DELETE FROM sync_initial_page_evidence e USING sync_initial_collection_job c "
                    + "WHERE e.job_id=:j AND e.range_start=:start AND e.range_end=:end AND c.job_id=e.job_id "
                    + "AND c.status='RUNNING' AND c.fencing_generation=:g AND c.lease_until>now()")
                .param("j",l.jobId()).param("start",utc(window.start())).param("end",utc(window.end()))
                .param("g",l.generation()).update();
        }
    }

    private record EventIdentity(String sourceId,UUID eventId,String semanticHash) {}

    StoredInput complete(Lease l, CollectionObservation input, boolean needsReview) {
        boolean complete=recordObservation(l,input)&&input.pageCount()==pageCount(l.jobId())
                &&validCoverage(l.jobId(),l.t0(),input.tc());
        if (!complete) needsReview=true;
        String status=needsReview?"NEEDS_REVIEW":"INPUTS_COMPLETE";
        String failure=needsReview?(input.failureCode()==null?"SOURCE_REQUIRES_REVIEW":input.failureCode()):null;
        UUID candidate=!needsReview?storeCandidate(l,input,null,null):null;
        return new StoredInput(candidate,status,failure);
    }

    /**
     * Completes the collection of a sync, a precision verification or a continuation (Stories 2.11 and 2.12) — one
     * rule for all three.
     *
     * <p><b>Completeness.</b> The page evidence must be exactly the windows of the targets
     * ({@link VerificationPlanner#windows}) — no window missing and no other — each with both types reaching their
     * last page. A sync's one target is {@code [window start, tc]}; windows of two targets may overlap (a
     * continuation's final sweep re-reads its first phase) but a target's own windows never do.
     *
     * <p><b>Reconciliation.</b> Every stored event of {@code (t0, tc]} is judged by its Bybit id and semantic hash
     * against the base candidate (the official one this attempt continues): observed and official is {@code SAME};
     * observed and not official is {@code NEW} (late when not after the base verification); official, inside a
     * target and not observed is {@code MISSING} — the official result's own evidence moved, so the attempt needs
     * review ({@code SOURCE_EVENT_MISSING}); not official, inside a target and not observed is {@code SUPERSEDED} —
     * the new candidate excludes it, and every later one inherits the exclusion; the same id with another hash is
     * {@code CONFLICT} (review). Outside the targets an official event is carried with a provenance marker and an
     * excluded one stays excluded; any other stored event is unexplained (review). Source facts are never touched.
     * The outcomes are recorded for a verification or continuation ({@code recordFor}), not for a sync.
     *
     * <p><b>The candidate</b> then covers {@code (t0, tc]}: the observed and carried events, and nothing it excludes.
     *
     * @param countPages whether every page the collection reported must be stored now — a sync's single run; a
     *     resumed verification also stored pages of an earlier holder
     */
    StoredInput completeTargets(Lease l, CollectionObservation input, boolean needsReview,
            List<VerificationPlanner.Range> targets, UUID baseCandidateId, Instant baseVerifiedThrough,
            @Nullable UUID recordFor, boolean countPages) {
        boolean complete=recordObservation(l,input)&&(!countPages||input.pageCount()==pageCount(l.jobId()))
                &&targetCoverage(l.jobId(),targets);
        String failure=needsReview?(input.failureCode()==null?"SOURCE_REQUIRES_REVIEW":input.failureCode()):null;
        if (!complete) return new StoredInput(null,"NEEDS_REVIEW",failure==null?"SOURCE_REQUIRES_REVIEW":failure);
        Reconciliation r=reconcile(l,input.tc(),targets,baseCandidateId,baseVerifiedThrough);
        if (recordFor!=null) recordReconciliation(l.userId(),recordFor,r);
        // A conflict needs no rule of its own here: it is always a diagnostic row, so the caller already asked for
        // review (needsReview) and the failure is set above; its outcome is still recorded.
        if (failure==null&&r.unexplained()) failure="SOURCE_REQUIRES_REVIEW";
        if (failure==null&&!r.missing().isEmpty()) failure="SOURCE_EVENT_MISSING";
        if (failure!=null) return new StoredInput(null,"NEEDS_REVIEW",failure);
        if (!r.carried().isEmpty()) {
            // One array parameter, not one bind per event: a long lineage carries many events.
            jdbc.sql("INSERT INTO sync_initial_event_provenance(job_id,event_id,connection_id,semantic_hash,"
                    + "carried_from_candidate_id) SELECT :j,se.event_id,:c,se.semantic_hash,:base "
                    + "FROM sync_initial_source_event se WHERE se.event_id=ANY(CAST(:events AS uuid[])) AND se.user_id=:u "
                    + "AND se.provider_account_id=:a ON CONFLICT(job_id,event_id) DO NOTHING")
                .param("j",l.jobId()).param("c",l.connectionId()).param("base",baseCandidateId)
                .param("events",uuidArray(r.carried())).param("u",l.userId()).param("a",l.providerAccountId()).update();
        }
        UUID candidate=storeCandidate(l,input,baseCandidateId,VerificationPlanner.windowStart(targets),
                r.excluded().keySet());
        if (!r.excluded().isEmpty()) {
            jdbc.sql("INSERT INTO sync_initial_candidate_excluded_event(candidate_id,event_id,user_id,excluded_by_job_id,"
                    + "reason_code) SELECT :c,x.event_id,:u,x.job_id,'NOT_REOBSERVED' "
                    + "FROM unnest(CAST(:events AS uuid[]),CAST(:jobs AS uuid[])) AS x(event_id,job_id)")
                .param("c",candidate).param("u",l.userId())
                .param("events",uuidArray(r.excluded().keySet())).param("jobs",uuidArray(r.excluded().values()))
                .update();
        }
        return new StoredInput(candidate,"INPUTS_COMPLETE",null);
    }

    /** One stored event of the range. */
    private record StoredEvent(UUID eventId,String sourceId,String semanticHash,Instant occurredAt) {}

    /** One reconciliation outcome to record. */
    private record Outcome(String sourceId,@Nullable UUID eventId,String outcome,boolean late,@Nullable Instant occurredAt,
            @Nullable String storedHash,@Nullable String observedHash) {}

    /**
     * What the reconciliation found: the outcomes, the official events to carry, the events the new candidate
     * excludes (with the attempt that excluded each), the missing ones, the conflicts, and whether any stored event
     * stays unexplained.
     */
    private record Reconciliation(List<Outcome> outcomes,List<UUID> carried,Map<UUID,UUID> excluded,List<UUID> missing,
            java.util.Set<String> conflicts,boolean unexplained) {}

    private Reconciliation reconcile(Lease l,Instant tc,List<VerificationPlanner.Range> targets,UUID base,
            Instant baseVerifiedThrough) {
        List<StoredEvent> range=jdbc.sql("SELECT event_id,source_id,semantic_hash,transaction_time "
                + "FROM sync_initial_source_event WHERE user_id=:u AND provider_account_id=:a AND transaction_time>:t0 "
                + "AND transaction_time<=:tc ORDER BY transaction_time,source_id")
            .param("u",l.userId()).param("a",l.providerAccountId()).param("t0",utc(l.t0())).param("tc",utc(tc))
            .query((rs,n)->new StoredEvent(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),instant(rs,4)))
            .list();
        java.util.Set<UUID> official=new java.util.HashSet<>(jdbc.sql("SELECT event_id FROM sync_initial_candidate_event "
                + "WHERE candidate_id=:base").param("base",base).query(UUID.class).list());
        Map<UUID,UUID> excludedBefore=new LinkedHashMap<>();
        jdbc.sql("SELECT event_id,excluded_by_job_id FROM sync_initial_candidate_excluded_event WHERE candidate_id=:base "
                + "AND user_id=:u").param("base",base).param("u",l.userId())
            .query((rs,n)->Map.entry(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class))).list()
            .forEach(entry->excludedBefore.put(entry.getKey(),entry.getValue()));
        java.util.Set<UUID> observed=new java.util.HashSet<>(jdbc.sql("SELECT event_id FROM sync_initial_event_provenance "
                + "WHERE job_id=:j AND carried_from_candidate_id IS NULL").param("j",l.jobId()).query(UUID.class).list());
        Map<String,String> conflicting=new LinkedHashMap<>();
        jdbc.sql("SELECT DISTINCT ON (source_id) source_id,semantic_hash FROM sync_initial_event_observation "
                + "WHERE job_id=:j AND diagnostic_code='PROVIDER_ID_CONFLICT' AND source_id IS NOT NULL "
                + "AND semantic_hash IS NOT NULL ORDER BY source_id,observed_at")
            .param("j",l.jobId()).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).list()
            .forEach(entry->conflicting.put(entry.getKey(),entry.getValue()));
        Map<String,StoredEvent> bySource=new LinkedHashMap<>();
        range.forEach(event->bySource.put(event.sourceId(),event));

        List<Outcome> outcomes=new ArrayList<>();
        List<UUID> carried=new ArrayList<>();
        Map<UUID,UUID> excluded=new LinkedHashMap<>();
        List<UUID> missing=new ArrayList<>();
        java.util.Set<String> conflicts=new java.util.HashSet<>();
        boolean unexplained=false;
        for (Map.Entry<String,String> conflict:conflicting.entrySet()) {
            StoredEvent stored=bySource.get(conflict.getKey());
            String storedHash=stored==null?storedHash(l,conflict.getKey()):stored.semanticHash();
            if (storedHash==null||storedHash.equals(conflict.getValue())) continue;
            conflicts.add(conflict.getKey());
            outcomes.add(new Outcome(conflict.getKey(),stored==null?null:stored.eventId(),"CONFLICT",false,
                    stored==null?null:stored.occurredAt(),storedHash,conflict.getValue()));
        }
        for (StoredEvent event:range) {
            // Its CONFLICT outcome stands for it (one outcome per Bybit id); the attempt needs review anyway.
            if (conflicts.contains(event.sourceId())) continue;
            boolean isOfficial=official.contains(event.eventId());
            boolean inTarget=VerificationPlanner.covers(targets,event.occurredAt());
            if (observed.contains(event.eventId())) {
                if (isOfficial) outcomes.add(new Outcome(event.sourceId(),event.eventId(),"SAME",false,event.occurredAt(),
                        event.semanticHash(),event.semanticHash()));
                else outcomes.add(new Outcome(event.sourceId(),event.eventId(),"NEW",
                        !event.occurredAt().isAfter(baseVerifiedThrough),event.occurredAt(),null,event.semanticHash()));
            } else if (isOfficial) {
                if (inTarget) {
                    missing.add(event.eventId());
                    outcomes.add(new Outcome(event.sourceId(),event.eventId(),"MISSING",false,event.occurredAt(),
                            event.semanticHash(),null));
                } else {
                    carried.add(event.eventId());
                }
            } else if (excludedBefore.containsKey(event.eventId())) {
                // An exclusion inherited from the lineage: explained, and excluded again.
                excluded.put(event.eventId(),excludedBefore.get(event.eventId()));
            } else if (inTarget) {
                excluded.put(event.eventId(),Objects.requireNonNull(l.planSyncJobId()));
                outcomes.add(new Outcome(event.sourceId(),event.eventId(),"SUPERSEDED",false,event.occurredAt(),
                        event.semanticHash(),null));
            } else {
                unexplained=true;
            }
        }
        return new Reconciliation(outcomes,carried,excluded,missing,conflicts,unexplained);
    }

    private @Nullable String storedHash(Lease l,String sourceId) {
        return jdbc.sql("SELECT semantic_hash FROM sync_initial_source_event WHERE provider_account_id=:a AND source_id=:s "
                + "AND user_id=:u").param("a",l.providerAccountId()).param("s",sourceId).param("u",l.userId())
            .query(String.class).optional().orElse(null);
    }

    private void recordReconciliation(UUID user,UUID job,Reconciliation r) {
        for (Outcome o:r.outcomes()) {
            jdbc.sql("INSERT INTO sync_plan_verification_event(job_id,source_id,user_id,event_id,outcome,late,occurred_at,"
                    + "stored_semantic_hash,observed_semantic_hash) VALUES (:j,:s,:u,:e,:o,:late,:at,:stored,:observed) "
                    + "ON CONFLICT(job_id,source_id) DO NOTHING")
                .param("j",job).param("s",o.sourceId()).param("u",user).param("e",o.eventId()).param("o",o.outcome())
                .param("late",o.late()).param("at",o.occurredAt()==null?null:utc(o.occurredAt()))
                .param("stored",o.storedHash()).param("observed",o.observedHash()).update();
        }
    }

    /**
     * Whether the page evidence is exactly the targets' windows, each read to the last page of both types. A window
     * of the evidence that is none of them — a stray window, or a window a target does not ask for — fails it too.
     */
    private boolean targetCoverage(UUID jobId,List<VerificationPlanner.Range> targets) {
        List<RangePair> rows=jdbc.sql("SELECT range_start,range_end,count(DISTINCT event_type),count(*) FILTER (WHERE NOT has_next_page) "
                + "FROM sync_initial_page_evidence WHERE job_id=:j GROUP BY range_start,range_end ORDER BY range_start,range_end")
            .param("j",jobId)
            .query((rs,n)->new RangePair(instant(rs,1),instant(rs,2),rs.getInt(3),rs.getInt(4))).list();
        java.util.Set<VerificationPlanner.Interval> expected=new java.util.HashSet<>(VerificationPlanner.windows(targets));
        if (expected.isEmpty()||rows.size()!=expected.size()) return false;
        for (RangePair r:rows) {
            if (!expected.contains(new VerificationPlanner.Interval(r.start(),r.end()))||r.typeCount()!=2
                    ||r.finalPageCount()!=2)
                return false;
        }
        return true;
    }

    /** Whether the collection recorded any event diagnostic (a missing id, an unknown type, a conflict, ...). */
    boolean hasDiagnostics(UUID jobId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_initial_event_observation WHERE job_id=:j)")
            .param("j",jobId).query(Boolean.class).single();
    }

    /** The windows of this collection already read to the last page of both types: a resumed one skips them. */
    List<VerificationPlanner.Interval> completeWindows(UUID jobId) {
        return jdbc.sql("SELECT range_start,range_end FROM sync_initial_page_evidence WHERE job_id=:j "
                + "GROUP BY range_start,range_end HAVING count(DISTINCT event_type)=2 "
                + "AND count(*) FILTER (WHERE NOT has_next_page)=2 ORDER BY range_start,range_end")
            .param("j",jobId).query((rs,n)->new VerificationPlanner.Interval(instant(rs,1),instant(rs,2))).list();
    }

    /**
     * The evidence the official lineage already proved (Story 2.12): {@code [COALESCE(window_start, t0), tc]} of the
     * candidate and of every base candidate behind it. Each was complete when it was made, so their union covers
     * {@code (t0, verified]}.
     */
    List<VerificationPlanner.Interval> lineageEvidence(UUID user,UUID candidateId) {
        return jdbc.sql("WITH RECURSIVE lineage(candidate_id,base_candidate_id,start_at,tc,depth) AS ("
                + "SELECT candidate_id,base_candidate_id,COALESCE(window_start,t0),tc,0 FROM sync_initial_candidate_bundle "
                + "WHERE candidate_id=:c AND user_id=:u UNION ALL SELECT b.candidate_id,b.base_candidate_id,"
                + "COALESCE(b.window_start,b.t0),b.tc,l.depth+1 FROM lineage l JOIN sync_initial_candidate_bundle b "
                + "ON b.candidate_id=l.base_candidate_id AND b.user_id=:u WHERE l.depth<100000) "
                + "SELECT start_at,tc FROM lineage WHERE tc>start_at")
            .param("c",candidateId).param("u",user)
            .query((rs,n)->new VerificationPlanner.Interval(instant(rs,1),instant(rs,2))).list();
    }

    /**
     * The times of the stored events of {@code (t0, through]} the candidate neither holds nor excludes: events a
     * failed attempt stored, late or in a gap (Story 2.12). Nothing but the times leaves here.
     */
    List<Instant> unexplainedEvents(UUID user,UUID providerAccountId,UUID candidateId,Instant t0,Instant through) {
        return jdbc.sql("SELECT se.transaction_time FROM sync_initial_source_event se WHERE se.user_id=:u "
                + "AND se.provider_account_id=:a AND se.transaction_time>:t0 AND se.transaction_time<=:through "
                + "AND NOT EXISTS (SELECT 1 FROM sync_initial_candidate_event ce WHERE ce.candidate_id=:c "
                + "AND ce.event_id=se.event_id) AND NOT EXISTS (SELECT 1 FROM sync_initial_candidate_excluded_event x "
                + "WHERE x.candidate_id=:c AND x.event_id=se.event_id) ORDER BY se.transaction_time")
            .param("u",user).param("a",providerAccountId).param("t0",utc(t0)).param("through",utc(through))
            .param("c",candidateId).query((rs,n)->instant(rs,1)).list();
    }

    /** A collection child's cutoff moves to the continuation's final one, fenced, before its final sweep. */
    boolean extendCutoff(Lease l,Instant tcFinal) {
        // statement_timestamp(): the parent attempt's lease and claim clock, in the same transaction as its CAS.
        return jdbc.sql("UPDATE sync_initial_collection_job SET tc=:f,last_checked_at=statement_timestamp() "
                + "WHERE job_id=:j AND status='RUNNING' AND fencing_generation=:g "
                + "AND lease_until>statement_timestamp() AND tc<:f")
            .param("f",utc(tcFinal)).param("j",l.jobId()).param("g",l.generation()).update()==1;
    }

    /** Records the collection's observation of equity and time on the job, fenced. */
    private boolean recordObservation(Lease l, CollectionObservation input) {
        return jdbc.sql("UPDATE sync_initial_collection_job SET tc=:tc,equity_decimal=:equity,measured_at=:measured,"
                + "received_at=:received,policy_version=:policy WHERE job_id=:j AND fencing_generation=:g "
                + "AND status='RUNNING' AND lease_until>now() AND (tc IS NULL OR tc=:tc)")
            .param("tc",utc(input.tc())).param("equity",input.equityDecimal()).param("measured",utc(input.measuredAt()))
            .param("received",utc(input.receivedAt())).param("policy",input.policyVersion())
            .param("j",l.jobId()).param("g",l.generation()).update()==1;
    }

    /** The pages cover {@code [start, tc]} in contiguous windows of at most seven days, both types to their last page. */
    private boolean validCoverage(UUID jobId, Instant start, Instant tc) {
        List<RangePair> rows=jdbc.sql("SELECT range_start,range_end,count(DISTINCT event_type),count(*) FILTER (WHERE NOT has_next_page) "
                + "FROM sync_initial_page_evidence WHERE job_id=:j GROUP BY range_start,range_end ORDER BY range_start")
            .param("j",jobId)
            .query((rs,n)->new RangePair(instant(rs,1),instant(rs,2),rs.getInt(3),rs.getInt(4))).list();
        if (rows.isEmpty() || !rows.getFirst().start().equals(start)) return false;
        Instant next=start;
        for (RangePair r:rows) {
            if (!r.start().equals(next)||r.typeCount()!=2||r.finalPageCount()!=2
                    ||r.end().isAfter(tc)||r.end().isAfter(r.start().plus(MAX_WINDOW_MILLIS,java.time.temporal.ChronoUnit.MILLIS)))
                return false;
            next=r.end();
        }
        return next.equals(tc);
    }
    private int pageCount(UUID jobId) {
        return jdbc.sql("SELECT count(*) FROM sync_initial_page_evidence WHERE job_id=:j")
                .param("j",jobId).query(Integer.class).single();
    }
    private record RangePair(Instant start,Instant end,int typeCount,int finalPageCount) {}
    private void observation(Lease l,UUID evidenceId,CollectionEvent e,String code,@Nullable String hash) {
        String safeSource=e.sourceId()!=null&&e.sourceId().getBytes(StandardCharsets.UTF_8).length<=MAX_SOURCE_ID_BYTES
                ?e.sourceId():null;
        String safeFlow=e.cashFlow()!=null&&e.cashFlow().length()<=MAX_CASH_FLOW_CHARS?e.cashFlow():null;
        jdbc.sql("INSERT INTO sync_initial_event_observation(observation_id,job_id,page_evidence_id,user_id,provider_account_id,"
                + "observed_connection_id,source_id,event_type,currency,cash_flow,transaction_time,account_type,"
                + "semantic_hash,diagnostic_code) VALUES (:id,:j,:evidence,:u,:a,:c,:source,:type,:currency,:flow,:time,:account,:hash,:code)")
            .param("id",UUID.randomUUID()).param("j",l.jobId()).param("evidence",evidenceId)
            .param("u",l.userId()).param("a",l.providerAccountId()).param("c",l.connectionId())
            .param("source",safeSource).param("type",e.type()).param("currency",e.currency())
            .param("flow",safeFlow).param("time",e.transactionTime()==null?null:utc(e.transactionTime()))
            .param("account",e.accountType()).param("hash",hash).param("code",code).update();
    }
    /**
     * The completeness hash of a candidate. One formula with its classification's re-check
     * ({@code CashflowClassificationRepository#evidenceHash}): the page evidence and the event evidence are read
     * with the same SQL there. An incremental candidate appends its base and window start; an initial one's
     * text is what it always was.
     */
    static String completenessDigest(UUID jobId, UUID portfolioId, UUID intentId, UUID connectionId, UUID accountId,
            Instant t0, Instant tc, String equity, Instant measuredAt, String policy, String pageEvidence,
            String eventEvidence, @Nullable UUID baseCandidateId, @Nullable Instant windowStart) {
        String value=jobId+"|"+portfolioId+"|"+intentId+"|"+connectionId+"|"+accountId+"|"+t0+"|"+tc+"|"+equity+"|"
                +measuredAt+"|"+policy+"|"+pageEvidence+"|"+eventEvidence;
        if (baseCandidateId!=null||windowStart!=null) value=value+"|base="+baseCandidateId+"|window="+windowStart;
        return sha256(value);
    }
    private UUID storeCandidate(Lease l, CollectionObservation input, @Nullable UUID baseCandidateId,
            @Nullable Instant windowStart) {
        return storeCandidate(l,input,baseCandidateId,windowStart,java.util.Set.of());
    }

    /**
     * Stores the candidate of {@code (t0, tc]}: every stored event of the range but the excluded ones. The
     * completeness hash reads the same events, so an initial candidate (nothing excluded) hashes as it always did.
     */
    private UUID storeCandidate(Lease l, CollectionObservation input, @Nullable UUID baseCandidateId,
            @Nullable Instant windowStart, java.util.Set<UUID> excluded) {
        String exclusion=excluded.isEmpty()?"":" AND NOT (event_id=ANY(CAST(:excluded AS uuid[])))";
        String pageEvidence=jdbc.sql("SELECT string_agg(event_type||'|'||range_start::text||'|'||range_end::text||'|'||"
                + "cursor_hash||'|'||item_count||'|'||COALESCE(first_event_at::text,'')||'|'||"
                + "COALESCE(last_event_at::text,'')||'|'||has_next_page::text,';' ORDER BY event_type,range_start,cursor_hash) "
                + "FROM sync_initial_page_evidence WHERE job_id=:j").param("j",l.jobId())
            .query(String.class).single();
        var evidenceQuery=jdbc.sql("SELECT COALESCE(string_agg(source_id||'|'||semantic_hash,';' ORDER BY source_id),'') "
                + "FROM sync_initial_source_event WHERE user_id=:u AND provider_account_id=:a "
                + "AND transaction_time>:t0 AND transaction_time<=:tc"+exclusion)
            .param("u",l.userId()).param("a",l.providerAccountId()).param("t0",utc(l.t0()))
            .param("tc",utc(input.tc()));
        if (!excluded.isEmpty()) evidenceQuery=evidenceQuery.param("excluded",uuidArray(excluded));
        String eventEvidence=evidenceQuery.query(String.class).single();
        String digest=completenessDigest(l.jobId(),l.portfolioId(),l.intentId(),l.connectionId(),l.providerAccountId(),
                l.t0(),input.tc(),input.equityDecimal(),input.measuredAt(),input.policyVersion(),pageEvidence,
                eventEvidence,baseCandidateId,windowStart);
        UUID candidate=UUID.randomUUID();
        jdbc.sql("INSERT INTO sync_initial_candidate_bundle(candidate_id,job_id,user_id,portfolio_id,intent_id,"
                + "connection_id,provider_account_id,t0,tc,equity_decimal,measured_at,received_at,policy_version,completeness_hash,"
                + "base_candidate_id,window_start) "
                + "VALUES (:id,:j,:u,:p,:i,:c,:a,:t0,:tc,:equity,:measured,:received,:policy,:hash,:base,:window)")
            .param("id",candidate).param("j",l.jobId()).param("u",l.userId()).param("p",l.portfolioId())
            .param("i",l.intentId()).param("c",l.connectionId()).param("a",l.providerAccountId())
            .param("t0",utc(l.t0())).param("tc",utc(input.tc())).param("equity",input.equityDecimal())
            .param("measured",utc(input.measuredAt())).param("received",utc(input.receivedAt()))
            .param("policy",input.policyVersion()).param("hash",digest).param("base",baseCandidateId)
            .param("window",windowStart==null?null:utc(windowStart)).update();
        var events=jdbc.sql("INSERT INTO sync_initial_candidate_event(candidate_id,event_id) "
                + "SELECT :candidate,event_id FROM sync_initial_source_event WHERE user_id=:u "
                + "AND provider_account_id=:a AND transaction_time>:t0 AND transaction_time<=:tc"+exclusion)
            .param("candidate",candidate).param("u",l.userId()).param("a",l.providerAccountId())
            .param("t0",utc(l.t0())).param("tc",utc(input.tc()));
        if (!excluded.isEmpty()) events=events.param("excluded",uuidArray(excluded));
        events.update();
        return candidate;
    }

    private record Parsed(@Nullable String type,@Nullable String currency,@Nullable String flow,
            @Nullable Instant time,@Nullable String hash,@Nullable String diagnostic) {}
    private static Parsed parse(CollectionEvent e,Instant t0,Instant tc) {
        if (e.sourceId()==null||e.sourceId().isBlank()) return new Parsed(null,null,null,null,null,"MISSING_ID");
        if (e.sourceId().getBytes(StandardCharsets.UTF_8).length>MAX_SOURCE_ID_BYTES)
            return new Parsed(null,null,null,e.transactionTime(),sha256(e.sourceId()),"SOURCE_ID_TOO_LONG");
        if (e.type()==null||e.currency()==null||e.cashFlow()==null||e.transactionTime()==null
                ||!"UNIFIED".equals(e.accountType())) return new Parsed(null,null,null,e.transactionTime(),null,"MALFORMED_EVENT");
        if (!e.transactionTime().isAfter(t0)||e.transactionTime().isAfter(tc))
            return new Parsed(null,null,null,e.transactionTime(),null,"EVENT_OUT_OF_WINDOW");
        if (!"TRANSFER_IN".equals(e.type())&& !"TRANSFER_OUT".equals(e.type()))
            return new Parsed(null,null,null,e.transactionTime(),null,"UNKNOWN_EVENT");
        if (!e.currency().matches("[A-Z0-9]{1,32}")||e.cashFlow().length()>MAX_CASH_FLOW_CHARS
                ||!e.cashFlow().matches("-?[0-9]+(?:\\.[0-9]+)?"))
            return new Parsed(null,null,null,e.transactionTime(),null,"MALFORMED_EVENT");
        String magnitude=e.cashFlow().startsWith("-")?e.cashFlow().substring(1):e.cashFlow();
        int dot=magnitude.indexOf('.');
        String integerPart=dot<0?magnitude:magnitude.substring(0,dot);
        String fraction=dot<0?"":magnitude.substring(dot+1);
        int significantInteger=integerPart.replaceFirst("^0+","").length();
        if (significantInteger+fraction.length()>MAX_CASH_FLOW_PRECISION||fraction.length()>MAX_CASH_FLOW_SCALE)
            return new Parsed(null,null,null,e.transactionTime(),null,"MALFORMED_EVENT");
        String flow;
        java.math.BigDecimal decimal;
        try { decimal=new java.math.BigDecimal(e.cashFlow()); }
        catch (NumberFormatException invalid) { return new Parsed(null,null,null,e.transactionTime(),null,"MALFORMED_EVENT"); }
        if (decimal.precision()>MAX_CASH_FLOW_PRECISION||Math.max(decimal.scale(),0)>MAX_CASH_FLOW_SCALE)
            return new Parsed(null,null,null,e.transactionTime(),null,"MALFORMED_EVENT");
        flow=decimal.stripTrailingZeros().toPlainString();
        if (flow.equals("-0")) flow="0";
        String currency=e.currency().toUpperCase(java.util.Locale.ROOT);
        String hash=sha256(e.type()+"|"+currency+"|"+flow+"|"+e.transactionTime().toEpochMilli()+"|UNIFIED");
        return new Parsed(e.type(),currency,flow,e.transactionTime(),hash,null);
    }
    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Instant instant(java.sql.ResultSet rs,int col) throws java.sql.SQLException {
        return rs.getObject(col,OffsetDateTime.class).toInstant();
    }
    int erase(UUID userId) {
        jdbc.sql("DELETE FROM sync_initial_candidate_excluded_event WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_initial_candidate_bundle WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_initial_event_observation WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_initial_event_provenance WHERE job_id IN "
                + "(SELECT job_id FROM sync_initial_collection_job WHERE user_id=:u)").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_initial_source_event WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_initial_collection_job WHERE user_id=:u").param("u",userId).update();
        return 0;
    }
    private static OffsetDateTime utc(Instant i) { return OffsetDateTime.ofInstant(i,ZoneOffset.UTC); }

    /**
     * A PostgreSQL {@code uuid[]} literal for one bind parameter ({@code CAST(:x AS uuid[])}): the statement stays one
     * bind however many ids it carries (the driver's 65535-bind limit). A UUID's text has no character to escape.
     */
    static String uuidArray(java.util.Collection<UUID> ids) {
        StringBuilder literal=new StringBuilder("{");
        for (UUID id:ids) {
            if (literal.length()>1) literal.append(',');
            literal.append(id);
        }
        return literal.append('}').toString();
    }
}
