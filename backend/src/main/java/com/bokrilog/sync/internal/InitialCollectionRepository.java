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
            UUID providerAccountId, Instant t0, @Nullable Instant tc, long generation) {}
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
    /**
     * Claims the oldest queued or lease-expired attempt of Story 2.4. A plan sync's stage row (Story 2.11)
     * is never claimed here: it belongs to its sync job, whose own lease and fence it follows.
     */
    Optional<Lease> claim() {
        return jdbc.sql("UPDATE sync_initial_collection_job SET status='RUNNING',fencing_generation=fencing_generation+1,"
                + "lease_until=now()+interval '30 seconds',heartbeat_at=now(),last_checked_at=now() "
                + "WHERE job_id=(SELECT job_id FROM sync_initial_collection_job WHERE plan_sync_job_id IS NULL AND "
                + "(status='QUEUED' OR (status='RUNNING' AND lease_until<now())) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) "
                + "RETURNING job_id,user_id,portfolio_id,intent_id,connection_id,provider_account_id,t0,tc,fencing_generation")
            .query((rs,n)->new Lease(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),
                rs.getObject(6,UUID.class),rs.getObject(7,OffsetDateTime.class).toInstant(),
                rs.getObject(8,OffsetDateTime.class)==null?null:rs.getObject(8,OffsetDateTime.class).toInstant(),
                rs.getLong(9))).optional();
    }

    /**
     * A plan sync's collection stage row (Story 2.11), inserted already {@code RUNNING} under fence 1 with
     * its sync job's lease, and with the range fixed: {@code t0} is the collection's start and {@code tc} the
     * job's reference time. The other methods of this class then run on it unchanged. The caller holds the
     * sync job's row lock; the job's heartbeat renews this lease.
     */
    Lease insertForSync(UUID id, UUID syncJobId, UUID user, UUID portfolio, UUID intent, UUID connection,
            UUID account, Instant start, Instant tc) {
        jdbc.sql("INSERT INTO sync_initial_collection_job(job_id,user_id,portfolio_id,intent_id,connection_id,"
                + "provider_account_id,idempotency_key,status,t0,tc,fencing_generation,lease_until,heartbeat_at,"
                + "last_checked_at,plan_sync_job_id) SELECT :j,:u,:p,:i,:c,:a,:k,'RUNNING',:t0,:tc,1,s.lease_until,now(),"
                + "now(),s.job_id FROM sync_plan_sync_job s WHERE s.job_id=:s AND s.status='RUNNING'")
            .param("j",id).param("u",user).param("p",portfolio).param("i",intent).param("c",connection)
            .param("a",account).param("k","plan-sync:"+syncJobId+":collection").param("t0",utc(start))
            .param("tc",utc(tc)).param("s",syncJobId).update();
        return new Lease(id,user,portfolio,intent,connection,account,start,tc,1);
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
        return review;
    }

    private record EventIdentity(String sourceId,UUID eventId,String semanticHash) {}

    StoredInput complete(Lease l, CollectionObservation input, boolean needsReview) {
        int updated=jdbc.sql("UPDATE sync_initial_collection_job SET tc=:tc,equity_decimal=:equity,measured_at=:measured,"
                + "received_at=:received,policy_version=:policy WHERE job_id=:j AND fencing_generation=:g "
                + "AND status='RUNNING' AND lease_until>now() AND (tc IS NULL OR tc=:tc)")
            .param("tc",utc(input.tc())).param("equity",input.equityDecimal()).param("measured",utc(input.measuredAt()))
            .param("received",utc(input.receivedAt())).param("policy",input.policyVersion())
            .param("j",l.jobId()).param("g",l.generation()).update();
        boolean complete=updated==1&&input.pageCount()==pageCount(l.jobId())
                &&validCoverage(l.jobId(),l.t0(),input.tc());
        if (!complete) needsReview=true;
        String status=needsReview?"NEEDS_REVIEW":"INPUTS_COMPLETE";
        String failure=needsReview?(input.failureCode()==null?"SOURCE_REQUIRES_REVIEW":input.failureCode()):null;
        UUID candidate=!needsReview?storeCandidate(l,input):null;
        return new StoredInput(candidate,status,failure);
    }

    private boolean validCoverage(UUID jobId, Instant t0, Instant tc) {
        List<RangePair> rows=jdbc.sql("SELECT range_start,range_end,count(DISTINCT event_type),count(*) FILTER (WHERE NOT has_next_page) "
                + "FROM sync_initial_page_evidence WHERE job_id=:j GROUP BY range_start,range_end ORDER BY range_start")
            .param("j",jobId)
            .query((rs,n)->new RangePair(instant(rs,1),instant(rs,2),rs.getInt(3),rs.getInt(4))).list();
        if (rows.isEmpty() || !rows.getFirst().start().equals(t0)) return false;
        Instant next=t0;
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
    private UUID storeCandidate(Lease l, CollectionObservation input) {
        String pageEvidence=jdbc.sql("SELECT string_agg(event_type||'|'||range_start::text||'|'||range_end::text||'|'||"
                + "cursor_hash||'|'||item_count||'|'||COALESCE(first_event_at::text,'')||'|'||"
                + "COALESCE(last_event_at::text,'')||'|'||has_next_page::text,';' ORDER BY event_type,range_start,cursor_hash) "
                + "FROM sync_initial_page_evidence WHERE job_id=:j").param("j",l.jobId())
            .query(String.class).single();
        String eventEvidence=jdbc.sql("SELECT COALESCE(string_agg(source_id||'|'||semantic_hash,';' ORDER BY source_id),'') "
                + "FROM sync_initial_source_event WHERE user_id=:u AND provider_account_id=:a "
                + "AND transaction_time>:t0 AND transaction_time<=:tc")
            .param("u",l.userId()).param("a",l.providerAccountId()).param("t0",utc(l.t0()))
            .param("tc",utc(input.tc())).query(String.class).single();
        String digest=sha256(l.jobId()+"|"+l.portfolioId()+"|"+l.intentId()+"|"+l.connectionId()+"|"
                +l.providerAccountId()+"|"+l.t0()+"|"+input.tc()+"|"+input.equityDecimal()+"|"
                +input.measuredAt()+"|"+input.policyVersion()+"|"+pageEvidence+"|"+eventEvidence);
        UUID candidate=UUID.randomUUID();
        jdbc.sql("INSERT INTO sync_initial_candidate_bundle(candidate_id,job_id,user_id,portfolio_id,intent_id,"
                + "connection_id,provider_account_id,t0,tc,equity_decimal,measured_at,received_at,policy_version,completeness_hash) "
                + "VALUES (:id,:j,:u,:p,:i,:c,:a,:t0,:tc,:equity,:measured,:received,:policy,:hash)")
            .param("id",candidate).param("j",l.jobId()).param("u",l.userId()).param("p",l.portfolioId())
            .param("i",l.intentId()).param("c",l.connectionId()).param("a",l.providerAccountId())
            .param("t0",utc(l.t0())).param("tc",utc(input.tc())).param("equity",input.equityDecimal())
            .param("measured",utc(input.measuredAt())).param("received",utc(input.receivedAt()))
            .param("policy",input.policyVersion()).param("hash",digest).update();
        jdbc.sql("INSERT INTO sync_initial_candidate_event(candidate_id,event_id) "
                + "SELECT :candidate,event_id FROM sync_initial_source_event WHERE user_id=:u "
                + "AND provider_account_id=:a AND transaction_time>:t0 AND transaction_time<=:tc")
            .param("candidate",candidate).param("u",l.userId()).param("a",l.providerAccountId())
            .param("t0",utc(l.t0())).param("tc",utc(input.tc())).update();
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
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Instant instant(java.sql.ResultSet rs,int col) throws java.sql.SQLException {
        return rs.getObject(col,OffsetDateTime.class).toInstant();
    }
    int erase(UUID userId) {
        jdbc.sql("DELETE FROM sync_initial_candidate_bundle WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_initial_event_observation WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_initial_event_provenance WHERE job_id IN "
                + "(SELECT job_id FROM sync_initial_collection_job WHERE user_id=:u)").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_initial_source_event WHERE user_id=:u").param("u",userId).update();
        jdbc.sql("DELETE FROM sync_initial_collection_job WHERE user_id=:u").param("u",userId).update();
        return 0;
    }
    private static OffsetDateTime utc(Instant i) { return OffsetDateTime.ofInstant(i,ZoneOffset.UTC); }
}
