package com.bokrilog.sync.internal;

import com.bokrilog.source.SourceConnectionApi.MarketInstrument;
import com.bokrilog.sync.SyncJobApi.ConversionJob;
import com.bokrilog.sync.SyncJobApi.ConversionPendingAsset;
import com.bokrilog.sync.SyncJobApi.ConversionPendingFlow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Story 2.6 attempts, immutable result bundles and the shared public market evidence.
 *
 * <p>Two lifetimes meet here. User rows (attempt, inputs, bundle, flows, legs, calculable cash
 * flows) are erased per user; market evidence rows carry no user identifier and outlive every
 * user. Evidence is written in its own short transaction between provider calls, results only in
 * the one publishing transaction.
 */
@Repository
class CashflowConversionRepository {
    private static final String PROVIDER = "BYBIT";
    private static final String CATEGORY = "spot";
    private static final int MAX_REVISION_RACES = 5;

    private final JdbcClient jdbc;
    private final CashflowClassificationRepository classifications;

    CashflowConversionRepository(JdbcClient jdbc, CashflowClassificationRepository classifications) {
        this.jdbc = jdbc;
        this.classifications = classifications;
    }

    record Lease(UUID jobId, UUID userId, UUID portfolioId, UUID intentId, UUID connectionId,
            UUID providerAccountId, UUID classificationId, String inputScopeHash, String policyVersion,
            @Nullable Instant referenceTime, long generation) {}

    /** The upstream COMPLETE classification an attempt converts. */
    record Classification(UUID classificationId, UUID candidateId, String scopeHash, String policyVersion,
            int flowCount) {}

    /** One immutable flow input with its source event and classification provenance. */
    record Input(UUID sourceEventId, String sourceId, int sourceRevision, String semanticHash,
            UUID provenanceConnectionId, String verdict, String ruleId, String classificationPolicy,
            String assetCode, String signedQuantity, Instant occurredAt) {
        PriceConversionPolicy.FlowInput flow() {
            return new PriceConversionPolicy.FlowInput(this.sourceEventId, this.sourceId, this.assetCode,
                    this.signedQuantity, this.occurredAt);
        }
    }

    /**
     * A closed candle ready to be recorded. {@code close} is canonical; {@code evidenceHash} is the
     * raw row hash; the request window is the kline {@code [start, end]} that returned it.
     */
    record CandleObservation(Instant startAt, String close, String evidenceHash, Instant requestStart,
            Instant requestEnd) {}

    static final class PublicationBlocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    // ---------------------------------------------------------------- upstream classification

    /**
     * The intent's COMPLETE classification of its latest still-consistent candidate, under the
     * current classification policy. Partial or review-required classifications never qualify.
     */
    Optional<Classification> eligibleClassification(UUID user, UUID intent, UUID portfolio, UUID connection,
            UUID account) {
        Optional<UUID> candidate = classifications.latestEligibleCandidate(user, intent, portfolio, connection, account);
        if (candidate.isEmpty()) return Optional.empty();
        return jdbc.sql("SELECT b.classification_id,b.candidate_id,b.input_scope_hash,b.policy_version,"
                + "b.deposit_count+b.withdrawal_count FROM sync_cashflow_classification_bundle b "
                + "JOIN sync_cashflow_classification_job j ON j.job_id=b.job_id AND j.status='COMPLETE' "
                + "WHERE b.user_id=:u AND b.intent_id=:i AND b.portfolio_id=:p AND b.connection_id=:c "
                + "AND b.provider_account_id=:a AND b.candidate_id=:candidate AND b.status='COMPLETE' "
                + "AND b.review_count=0 AND b.policy_version=:policy "
                + "ORDER BY b.processed_at DESC,b.classification_id DESC LIMIT 1")
            .param("u", user).param("i", intent).param("p", portfolio).param("c", connection).param("a", account)
            .param("candidate", candidate.get()).param("policy", CashflowDecisionTable.POLICY_VERSION)
            .query((rs, n) -> new Classification(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getString(3), rs.getString(4), rs.getInt(5))).optional();
    }

    /** The classification's external flows, joined back to their unchanged source events. */
    List<Input> classificationInputs(UUID user, UUID classificationId) {
        return sorted(jdbc.sql("SELECT ce.source_event_id,ce.source_id,ce.source_revision,ce.semantic_hash,"
                + "ce.provenance_connection_id,ce.verdict,ce.rule_id,ce.policy_version,fc.asset_code,"
                + "fc.signed_amount,fc.occurred_at FROM sync_cashflow_flow_candidate fc "
                + "JOIN sync_cashflow_classification_event ce ON ce.classification_id=fc.classification_id "
                + "AND ce.source_event_id=fc.source_event_id "
                + "JOIN sync_cashflow_classification_bundle b ON b.classification_id=fc.classification_id "
                + "JOIN sync_initial_source_event se ON se.event_id=fc.source_event_id "
                + "WHERE fc.classification_id=:c AND b.user_id=:u AND se.user_id=:u "
                + "AND se.provider_account_id=b.provider_account_id AND se.semantic_hash=ce.semantic_hash "
                + "AND ce.verdict IN ('EXTERNAL_IN','EXTERNAL_OUT') AND ce.signed_amount=fc.signed_amount "
                + "AND ce.asset_code=fc.asset_code AND ce.occurred_at=fc.occurred_at")
            .param("c", classificationId).param("u", user).query(CashflowConversionRepository::input).list());
    }

    /** The inputs fixed on the attempt at admission. */
    List<Input> storedInputs(Lease lease) {
        return sorted(jdbc.sql("SELECT i.source_event_id,i.source_id,i.source_revision,i.semantic_hash,"
                + "i.provenance_connection_id,i.classification_verdict,i.classification_rule_id,"
                + "i.classification_policy_version,i.asset_code,i.signed_quantity,i.occurred_at "
                + "FROM sync_cashflow_conversion_input i JOIN sync_cashflow_conversion_job j ON j.job_id=i.job_id "
                + "WHERE i.job_id=:j AND j.user_id=:u")
            .param("j", lease.jobId()).param("u", lease.userId()).query(CashflowConversionRepository::input).list());
    }

    /**
     * Whether the attempt's input scope still stands: the classification is still the intent's
     * current COMPLETE one, and both its flows and the attempt's fixed copy hash to the scope hash.
     */
    boolean inputsStillValid(Lease lease) {
        Optional<Classification> current = eligibleClassification(lease.userId(), lease.intentId(),
                lease.portfolioId(), lease.connectionId(), lease.providerAccountId());
        if (current.isEmpty() || !current.get().classificationId().equals(lease.classificationId())) return false;
        List<Input> source = classificationInputs(lease.userId(), lease.classificationId());
        if (source.size() != current.get().flowCount()) return false;
        if (!inputHash(current.get(), source).equals(lease.inputScopeHash())) return false;
        return inputHash(current.get(), storedInputs(lease)).equals(lease.inputScopeHash());
    }

    static String inputHash(Classification classification, List<Input> inputs) {
        StringBuilder payload = new StringBuilder().append(classification.classificationId()).append('|')
                .append(classification.candidateId()).append('|').append(classification.scopeHash()).append('|')
                .append(classification.policyVersion()).append('|').append(inputs.size());
        for (Input in : sorted(inputs)) {
            payload.append(';').append(in.sourceEventId()).append('|').append(in.sourceId()).append('|')
                    .append(in.sourceRevision()).append('|').append(in.semanticHash()).append('|')
                    .append(in.provenanceConnectionId()).append('|').append(in.verdict()).append('|')
                    .append(in.ruleId()).append('|').append(in.classificationPolicy()).append('|')
                    .append(in.assetCode()).append('|').append(in.signedQuantity()).append('|')
                    .append(in.occurredAt().toEpochMilli());
        }
        return sha256(payload.toString());
    }

    // ---------------------------------------------------------------- admission and lease

    /**
     * Admits an attempt. The same key returns its attempt; an in-progress attempt for the same
     * classification is returned instead of a second one (the partial unique index is the
     * authority). The inputs are copied with the attempt in the caller's transaction.
     */
    Optional<UUID> admit(UUID user, UUID intent, UUID portfolio, UUID connection, UUID account, String key) {
        Optional<Classification> eligible = eligibleClassification(user, intent, portfolio, connection, account);
        if (eligible.isEmpty()) return Optional.empty();
        Classification classification = eligible.get();
        Optional<UUID> same = jobIdByKey(user, intent, key);
        if (same.isPresent()) return same;
        Optional<UUID> active = activeJobId(user, classification.classificationId());
        if (active.isPresent()) return active;
        List<Input> inputs = classificationInputs(user, classification.classificationId());
        if (inputs.size() != classification.flowCount()) return Optional.empty();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("INSERT INTO sync_cashflow_conversion_job(job_id,user_id,portfolio_id,intent_id,"
                + "connection_id,provider_account_id,classification_id,input_scope_hash,flow_count,policy_version,"
                + "calculation_policy_version,idempotency_key,status) VALUES (:j,:u,:p,:i,:c,:a,:classification,"
                + ":hash,:count,:policy,:calc,:key,'QUEUED') ON CONFLICT DO NOTHING")
            .param("j", id).param("u", user).param("p", portfolio).param("i", intent).param("c", connection)
            .param("a", account).param("classification", classification.classificationId())
            .param("hash", inputHash(classification, inputs)).param("count", inputs.size())
            .param("policy", PriceConversionPolicy.POLICY_VERSION)
            .param("calc", PriceConversionPolicy.CALCULATION_POLICY_VERSION).param("key", key).update();
        if (inserted == 1) {
            for (Input in : inputs) {
                jdbc.sql("INSERT INTO sync_cashflow_conversion_input(job_id,source_event_id,source_id,source_revision,"
                        + "semantic_hash,provenance_connection_id,classification_verdict,classification_rule_id,"
                        + "classification_policy_version,asset_code,signed_quantity,occurred_at) VALUES (:j,:event,"
                        + ":source,:revision,:hash,:connection,:verdict,:rule,:policy,:asset,:quantity,:occurred)")
                    .param("j", id).param("event", in.sourceEventId()).param("source", in.sourceId())
                    .param("revision", in.sourceRevision()).param("hash", in.semanticHash())
                    .param("connection", in.provenanceConnectionId()).param("verdict", in.verdict())
                    .param("rule", in.ruleId()).param("policy", in.classificationPolicy())
                    .param("asset", in.assetCode()).param("quantity", in.signedQuantity())
                    .param("occurred", utc(in.occurredAt())).update();
            }
        }
        return jobIdByKey(user, intent, key).or(() -> activeJobId(user, classification.classificationId()));
    }

    Optional<UUID> jobIdByKey(UUID user, UUID intent, String key) {
        return jdbc.sql("SELECT job_id FROM sync_cashflow_conversion_job WHERE user_id=:u AND intent_id=:i "
                + "AND idempotency_key=:k").param("u", user).param("i", intent).param("k", key)
            .query(UUID.class).optional();
    }

    private Optional<UUID> activeJobId(UUID user, UUID classificationId) {
        return jdbc.sql("SELECT job_id FROM sync_cashflow_conversion_job WHERE user_id=:u AND classification_id=:c "
                + "AND status IN ('QUEUED','RUNNING') ORDER BY created_at LIMIT 1")
            .param("u", user).param("c", classificationId).query(UUID.class).optional();
    }

    // ---------------------------------------------------------------- a plan sync's stage (Story 2.11)

    /**
     * The COMPLETE classification by its id — the one a plan sync's own classification stage produced, not
     * the intent's latest. Partial or review-required bundles never qualify. The sync job pins this one
     * classification; {@link #eligibleClassification} would follow whichever came last.
     */
    Optional<Classification> completeClassification(UUID user, UUID classificationId) {
        return jdbc.sql("SELECT b.classification_id,b.candidate_id,b.input_scope_hash,b.policy_version,"
                + "b.deposit_count+b.withdrawal_count FROM sync_cashflow_classification_bundle b "
                + "JOIN sync_cashflow_classification_job j ON j.job_id=b.job_id AND j.status='COMPLETE' "
                + "WHERE b.classification_id=:c AND b.user_id=:u AND b.status='COMPLETE' AND b.review_count=0 "
                + "AND b.policy_version=:policy")
            .param("c", classificationId).param("u", user).param("policy", CashflowDecisionTable.POLICY_VERSION)
            .query((rs, n) -> new Classification(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getString(3), rs.getString(4), rs.getInt(5))).optional();
    }

    /**
     * A plan sync's conversion stage row, inserted already {@code RUNNING} under fence 1 with its sync job's
     * lease, for exactly {@code classification}. Its inputs are copied from that classification in the
     * caller's transaction, as {@link #admit} copies them. The stage dispatcher never claims it.
     *
     * @return the stage lease, or empty when the classification's flows do not add up to its own count
     */
    Optional<Lease> insertForSync(UUID id, UUID syncJobId, UUID user, UUID portfolio, UUID intent, UUID connection,
            UUID account, Classification classification) {
        List<Input> inputs = classificationInputs(user, classification.classificationId());
        if (inputs.size() != classification.flowCount()) return Optional.empty();
        String scope = inputHash(classification, inputs);
        int inserted = jdbc.sql("INSERT INTO sync_cashflow_conversion_job(job_id,user_id,portfolio_id,intent_id,"
                + "connection_id,provider_account_id,classification_id,input_scope_hash,flow_count,policy_version,"
                + "calculation_policy_version,idempotency_key,status,fencing_generation,lease_until,heartbeat_at,"
                + "last_checked_at,plan_sync_job_id) SELECT :j,:u,:p,:i,:c,:a,:classification,:hash,:count,:policy,"
                + ":calc,:key,'RUNNING',1,s.lease_until,now(),now(),s.job_id FROM sync_plan_sync_job s "
                + "WHERE s.job_id=:s AND s.status='RUNNING'")
            .param("j", id).param("u", user).param("p", portfolio).param("i", intent).param("c", connection)
            .param("a", account).param("classification", classification.classificationId()).param("hash", scope)
            .param("count", inputs.size()).param("policy", PriceConversionPolicy.POLICY_VERSION)
            .param("calc", PriceConversionPolicy.CALCULATION_POLICY_VERSION)
            .param("key", "plan-sync:" + syncJobId + ":conversion").param("s", syncJobId).update();
        if (inserted != 1) return Optional.empty();
        for (Input in : inputs) {
            jdbc.sql("INSERT INTO sync_cashflow_conversion_input(job_id,source_event_id,source_id,source_revision,"
                    + "semantic_hash,provenance_connection_id,classification_verdict,classification_rule_id,"
                    + "classification_policy_version,asset_code,signed_quantity,occurred_at) VALUES (:j,:event,"
                    + ":source,:revision,:hash,:connection,:verdict,:rule,:policy,:asset,:quantity,:occurred)")
                .param("j", id).param("event", in.sourceEventId()).param("source", in.sourceId())
                .param("revision", in.sourceRevision()).param("hash", in.semanticHash())
                .param("connection", in.provenanceConnectionId()).param("verdict", in.verdict())
                .param("rule", in.ruleId()).param("policy", in.classificationPolicy())
                .param("asset", in.assetCode()).param("quantity", in.signedQuantity())
                .param("occurred", utc(in.occurredAt())).update();
        }
        return Optional.of(new Lease(id, user, portfolio, intent, connection, account,
                classification.classificationId(), scope, PriceConversionPolicy.POLICY_VERSION, null, 1));
    }

    /**
     * {@link #inputsStillValid} for a pinned classification: the stage row's classification is still COMPLETE,
     * and both its flows and the stage's fixed copy hash to the scope hash. Whether it is the intent's latest
     * does not matter here — the sync job, not the intent, decides which classification it converts.
     */
    boolean pinnedInputsStillValid(Lease lease) {
        Optional<Classification> pinned = completeClassification(lease.userId(), lease.classificationId());
        if (pinned.isEmpty()) return false;
        List<Input> source = classificationInputs(lease.userId(), lease.classificationId());
        if (source.size() != pinned.get().flowCount()) return false;
        if (!inputHash(pinned.get(), source).equals(lease.inputScopeHash())) return false;
        return inputHash(pinned.get(), storedInputs(lease)).equals(lease.inputScopeHash());
    }

    /** Claims a Story 2.6 attempt. A plan sync's stage row (Story 2.11) belongs to its job and is never claimed. */
    Optional<Lease> claim() {
        return jdbc.sql("UPDATE sync_cashflow_conversion_job SET status='RUNNING',"
                + "fencing_generation=fencing_generation+1,lease_until=now()+interval '30 seconds',"
                + "heartbeat_at=now(),last_checked_at=now() WHERE job_id=(SELECT job_id FROM "
                + "sync_cashflow_conversion_job WHERE plan_sync_job_id IS NULL AND "
                + "(status='QUEUED' OR (status='RUNNING' AND lease_until<now())) "
                + "ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING job_id,user_id,portfolio_id,"
                + "intent_id,connection_id,provider_account_id,classification_id,input_scope_hash,policy_version,"
                + "reference_time,fencing_generation")
            .query((rs, n) -> new Lease(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                    rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
                    instantOrNull(rs, 10), rs.getLong(11))).optional();
    }

    /** Locks the attempt row if this lease still owns it. Serializes with erasure. */
    boolean lockOwned(Lease lease) {
        return jdbc.sql("SELECT job_id FROM sync_cashflow_conversion_job WHERE job_id=:j AND user_id=:u "
                + "AND connection_id=:c AND provider_account_id=:a AND status='RUNNING' AND fencing_generation=:g "
                + "AND lease_until>now() FOR UPDATE")
            .param("j", lease.jobId()).param("u", lease.userId()).param("c", lease.connectionId())
            .param("a", lease.providerAccountId()).param("g", lease.generation())
            .query(UUID.class).optional().isPresent();
    }

    boolean owns(Lease lease) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sync_cashflow_conversion_job WHERE job_id=:j AND user_id=:u "
                + "AND status='RUNNING' AND fencing_generation=:g AND lease_until>now())")
            .param("j", lease.jobId()).param("u", lease.userId()).param("g", lease.generation())
            .query(Boolean.class).single();
    }

    int heartbeat(Lease lease) {
        return jdbc.sql("UPDATE sync_cashflow_conversion_job SET lease_until=now()+interval '30 seconds',"
                + "heartbeat_at=now(),last_checked_at=now() WHERE job_id=:j AND fencing_generation=:g "
                + "AND status='RUNNING' AND lease_until>now()")
            .param("j", lease.jobId()).param("g", lease.generation()).update();
    }

    /** Fixes the attempt's reference time once. A retaken lease keeps the value already fixed. */
    boolean fixReference(Lease lease, Instant reference) {
        int updated = jdbc.sql("UPDATE sync_cashflow_conversion_job SET reference_time=:t,last_checked_at=now() "
                + "WHERE job_id=:j AND status='RUNNING' AND fencing_generation=:g AND lease_until>now() "
                + "AND reference_time IS NULL")
            .param("t", utc(reference)).param("j", lease.jobId()).param("g", lease.generation()).update();
        if (updated == 1) return true;
        return jdbc.sql("SELECT reference_time FROM sync_cashflow_conversion_job WHERE job_id=:j AND status='RUNNING' "
                + "AND fencing_generation=:g AND lease_until>now()")
            .param("j", lease.jobId()).param("g", lease.generation())
            .query((rs, n) -> instantOrNull(rs, 1)).optional()
            .map(existing -> reference.equals(existing)).orElse(false);
    }

    int finish(Lease lease, String status, @Nullable String code, boolean retryable) {
        return jdbc.sql("UPDATE sync_cashflow_conversion_job SET status=:s,failure_code=:code,retryable=:r,"
                + "lease_until=NULL,last_checked_at=now(),finished_at=now() WHERE job_id=:j AND status='RUNNING' "
                + "AND fencing_generation=:g AND lease_until>now()")
            .param("s", status).param("code", code).param("r", retryable).param("j", lease.jobId())
            .param("g", lease.generation()).update();
    }

    // ---------------------------------------------------------------- shared public evidence

    /** The latest revision of each requested candle start. Absent starts are absent. */
    Map<Instant, PriceConversionPolicy.CandleFact> latestCandles(String symbol, Collection<Instant> starts) {
        if (starts.isEmpty()) return Map.of();
        List<Instant> distinct = new ArrayList<>(new java.util.TreeSet<>(starts));
        String placeholders = String.join(",", java.util.Collections.nCopies(distinct.size(), "?"));
        List<Object> args = new ArrayList<>(List.of(PROVIDER, CATEGORY, symbol));
        distinct.forEach(start -> args.add(utc(start)));
        Map<Instant, PriceConversionPolicy.CandleFact> latest = new HashMap<>();
        jdbc.sql("SELECT DISTINCT ON (start_at) candle_id,start_at,revision,close_price,evidence_hash "
                + "FROM sync_market_candle WHERE provider=? AND category=? AND interval_code='1' AND symbol=? "
                + "AND start_at IN (" + placeholders + ") ORDER BY start_at,revision DESC")
            .params(args).query(CashflowConversionRepository::candle).list()
            .forEach(fact -> latest.put(fact.startAt(), fact));
        return latest;
    }

    /**
     * Records closed candles. The revision is decided on the price value: an unchanged canonical
     * close reuses the latest revision (a volume- or turnover-only difference is not a revision,
     * and the stored raw hash stays the first observation's provenance); a changed close appends the
     * next revision and never overwrites the earlier one.
     *
     * <p>Rows are inserted in ascending start order. Two attempts with overlapping windows then take
     * the unique-index locks in the same order and cannot deadlock on each other.
     */
    Map<Instant, PriceConversionPolicy.CandleFact> storeCandles(String symbol, Collection<CandleObservation> candles,
            Instant reference) {
        Map<Instant, PriceConversionPolicy.CandleFact> latest =
                latestCandles(symbol, candles.stream().map(CandleObservation::startAt).toList());
        Map<Instant, PriceConversionPolicy.CandleFact> stored = new HashMap<>();
        List<CandleObservation> ordered = candles.stream().sorted(Comparator.comparing(CandleObservation::startAt)).toList();
        for (CandleObservation candle : ordered) {
            PriceConversionPolicy.CandleFact existing = latest.get(candle.startAt());
            for (int attempt = 0; ; attempt++) {
                if (existing != null && existing.close().equals(candle.close())) {
                    stored.put(candle.startAt(), existing);
                    break;
                }
                if (attempt >= MAX_REVISION_RACES) throw new IllegalStateException("candle revision race");
                int revision = existing == null ? 1 : existing.revision() + 1;
                UUID id = UUID.randomUUID();
                Optional<UUID> inserted = jdbc.sql("INSERT INTO sync_market_candle(candle_id,provider,category,symbol,"
                        + "interval_code,start_at,end_at,revision,close_price,evidence_hash,policy_version,observed_at,"
                        + "request_start_at,request_end_at) VALUES (:id,:provider,:category,:symbol,'1',:start,:end,"
                        + ":revision,:close,:hash,:policy,:observed,:requestStart,:requestEnd) "
                        + "ON CONFLICT DO NOTHING RETURNING candle_id")
                    .param("id", id).param("provider", PROVIDER).param("category", CATEGORY).param("symbol", symbol)
                    .param("start", utc(candle.startAt()))
                    .param("end", utc(candle.startAt().plus(PriceConversionPolicy.MINUTE)))
                    .param("revision", revision).param("close", candle.close()).param("hash", candle.evidenceHash())
                    .param("policy", PriceConversionPolicy.POLICY_VERSION).param("observed", utc(reference))
                    .param("requestStart", utc(candle.requestStart())).param("requestEnd", utc(candle.requestEnd()))
                    .query(UUID.class).optional();
                if (inserted.isPresent()) {
                    stored.put(candle.startAt(), new PriceConversionPolicy.CandleFact(id, candle.startAt(), revision,
                            candle.close(), candle.evidenceHash()));
                    break;
                }
                existing = latestCandles(symbol, List.of(candle.startAt())).get(candle.startAt());
            }
        }
        return stored;
    }

    /**
     * Records the instrument observation of one symbol ({@code null} entry: not listed). Unchanged
     * values reuse their revision.
     */
    PriceConversionPolicy.InstrumentFact storeInstrument(String symbol, @Nullable MarketInstrument entry,
            Instant reference) {
        String hash = entry == null ? sha256(symbol + "|UNLISTED") : entry.evidenceHash();
        for (int attempt = 0; attempt <= MAX_REVISION_RACES; attempt++) {
            Optional<StoredInstrument> latest = jdbc.sql("SELECT observation_id,listed,symbol,"
                    + "base_asset,quote_asset,status,evidence_hash,revision FROM sync_market_instrument_observation "
                    + "WHERE provider=:provider AND category=:category AND symbol=:symbol ORDER BY revision DESC LIMIT 1")
                .param("provider", PROVIDER).param("category", CATEGORY).param("symbol", symbol)
                .query(CashflowConversionRepository::instrument).optional();
            if (latest.isPresent() && latest.get().fact().evidenceHash().equals(hash)) return latest.get().fact();
            int revision = latest.map(stored -> stored.revision() + 1).orElse(1);
            UUID id = UUID.randomUUID();
            int inserted = jdbc.sql("INSERT INTO sync_market_instrument_observation(observation_id,provider,category,"
                    + "symbol,revision,listed,base_asset,quote_asset,status,evidence_hash,policy_version,observed_at) "
                    + "VALUES (:id,:provider,:category,:symbol,:revision,:listed,:base,:quote,:status,:hash,:policy,"
                    + ":observed) ON CONFLICT DO NOTHING")
                .param("id", id).param("provider", PROVIDER).param("category", CATEGORY).param("symbol", symbol)
                .param("revision", revision).param("listed", entry != null)
                .param("base", entry == null ? null : entry.baseAsset())
                .param("quote", entry == null ? null : entry.quoteAsset())
                .param("status", entry == null ? null : entry.status()).param("hash", hash)
                .param("policy", PriceConversionPolicy.POLICY_VERSION).param("observed", utc(reference)).update();
            if (inserted == 1) {
                return new PriceConversionPolicy.InstrumentFact(id, entry != null, entry == null ? null : symbol,
                        entry == null ? null : entry.baseAsset(), entry == null ? null : entry.quoteAsset(),
                        entry == null ? null : entry.status(), hash);
            }
        }
        throw new IllegalStateException("instrument revision race");
    }

    private record StoredInstrument(PriceConversionPolicy.InstrumentFact fact, int revision) {}

    // ---------------------------------------------------------------- publication

    /**
     * Publishes the attempt's result inside the caller's transaction.
     *
     * <p>Same inputs, policy and evidence give the same fingerprint, and that bundle is reused.
     * Otherwise a new bundle is appended with its predecessor and the reason for the change; the
     * earlier bundle is never touched. Calculable rows are written only under a calculable bundle.
     * The permission check runs after every insert and before the fenced job update, so a lost
     * check rolls back everything.
     */
    UUID publish(Lease lease, List<Input> inputs, List<PriceConversionPolicy.FlowOutcome> outcomes,
            @Nullable Instant reference, BooleanSupplier stillPermitted) {
        if (inputs.size() != outcomes.size()) throw new IllegalArgumentException("conversion result count mismatch");
        PriceConversionPolicy.BundleOutcome summary = PriceConversionPolicy.summarize(outcomes);
        String fingerprint = PriceConversionPolicy.fingerprint(lease.inputScopeHash(), outcomes);
        UUID conversionId = jdbc.sql("SELECT conversion_id FROM sync_cashflow_conversion_bundle WHERE user_id=:u "
                + "AND classification_id=:c AND policy_version=:p AND outcome_fingerprint=:f")
            .param("u", lease.userId()).param("c", lease.classificationId()).param("p", lease.policyVersion())
            .param("f", fingerprint).query(UUID.class).optional()
            .orElseGet(() -> insertBundle(lease, inputs, outcomes, summary, fingerprint, reference));
        if (!stillPermitted.getAsBoolean()) throw new PublicationBlocked();
        int updated = jdbc.sql("UPDATE sync_cashflow_conversion_job SET status=:s,failure_code=:code,retryable=:r,"
                + "conversion_id=:conversion,lease_until=NULL,last_checked_at=now(),finished_at=now() "
                + "WHERE job_id=:j AND status='RUNNING' AND fencing_generation=:g AND lease_until>now()")
            .param("s", summary.status().name()).param("code", summary.reason() == null ? null : summary.reason().name())
            .param("r", summary.retryable()).param("conversion", conversionId).param("j", lease.jobId())
            .param("g", lease.generation()).update();
        if (updated != 1) throw new IllegalStateException("conversion lease lost");
        return conversionId;
    }

    private UUID insertBundle(Lease lease, List<Input> inputs, List<PriceConversionPolicy.FlowOutcome> outcomes,
            PriceConversionPolicy.BundleOutcome summary, String fingerprint, @Nullable Instant reference) {
        Map<UUID, Input> byEvent = new LinkedHashMap<>();
        inputs.forEach(in -> byEvent.put(in.sourceEventId(), in));
        /*
         * The predecessor is the result the classification most recently finished with, not the most
         * recently created bundle: after A -> B -> A again (fingerprint reuse), the current result is
         * A, and the next version must chain to A.
         */
        Optional<Object[]> previous = jdbc.sql("SELECT b.conversion_id,b.policy_version FROM sync_cashflow_conversion_job j "
                + "JOIN sync_cashflow_conversion_bundle b ON b.conversion_id=j.conversion_id AND b.user_id=j.user_id "
                + "WHERE j.user_id=:u AND j.classification_id=:c AND j.finished_at IS NOT NULL "
                + "ORDER BY j.finished_at DESC,j.job_id DESC LIMIT 1")
            .param("u", lease.userId()).param("c", lease.classificationId())
            .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}).optional();
        UUID previousId = previous.map(row -> (UUID) row[0]).orElse(null);
        String changeReason = previous.isEmpty() ? null
                : !lease.policyVersion().equals(previous.get()[1]) ? "POLICY_CHANGE"
                : priceRevised(previousId, outcomes) ? "PRICE_REVISION" : "EVIDENCE_CHANGE";
        UUID conversionId = UUID.randomUUID();
        jdbc.sql("INSERT INTO sync_cashflow_conversion_bundle(conversion_id,created_by_job_id,user_id,portfolio_id,"
                + "intent_id,connection_id,provider_account_id,classification_id,input_scope_hash,policy_version,"
                + "calculation_policy_version,outcome_fingerprint,status,reason_code,retryable,calculable,estimated,"
                + "flow_count,converted_count,estimated_count,conversion_required_count,review_count,reference_time,"
                + "previous_conversion_id,change_reason) VALUES (:id,:job,:u,:p,:i,:c,:a,:classification,:hash,:policy,"
                + ":calc,:fingerprint,:status,:reason,:retryable,:calculable,:estimated,:flows,:converted,:estimatedCount,"
                + ":required,:review,:reference,:previous,:change)")
            .param("id", conversionId).param("job", lease.jobId()).param("u", lease.userId())
            .param("p", lease.portfolioId()).param("i", lease.intentId()).param("c", lease.connectionId())
            .param("a", lease.providerAccountId()).param("classification", lease.classificationId())
            .param("hash", lease.inputScopeHash()).param("policy", lease.policyVersion())
            .param("calc", PriceConversionPolicy.CALCULATION_POLICY_VERSION).param("fingerprint", fingerprint)
            .param("status", summary.status().name())
            .param("reason", summary.reason() == null ? null : summary.reason().name())
            .param("retryable", summary.retryable()).param("calculable", summary.calculable())
            .param("estimated", summary.estimated() > 0).param("flows", summary.flowCount())
            .param("converted", summary.converted()).param("estimatedCount", summary.estimated())
            .param("required", summary.conversionRequired()).param("review", summary.review())
            .param("reference", reference == null ? null : utc(reference)).param("previous", previousId)
            .param("change", changeReason).update();
        List<PriceConversionPolicy.FlowOutcome> ordered = outcomes.stream()
                .sorted(Comparator.comparing((PriceConversionPolicy.FlowOutcome o) -> o.input().sourceId())
                        .thenComparing(o -> o.input().sourceEventId())).toList();
        for (PriceConversionPolicy.FlowOutcome outcome : ordered) {
            Input in = byEvent.get(outcome.input().sourceEventId());
            if (in == null) throw new IllegalArgumentException("conversion result without input");
            jdbc.sql("INSERT INTO sync_cashflow_conversion_flow(conversion_id,source_event_id,source_id,semantic_hash,"
                    + "classification_rule_id,classification_policy_version,asset_code,signed_quantity,occurred_at,"
                    + "status,reason_code,retryable,evidence_type,route_id,usd_unit_price,usd_value,estimated) VALUES "
                    + "(:id,:event,:source,:hash,:rule,:policy,:asset,:quantity,:occurred,:status,:reason,:retryable,"
                    + ":evidence,:route,:unit,:usd,:estimated)")
                .param("id", conversionId).param("event", in.sourceEventId()).param("source", in.sourceId())
                .param("hash", in.semanticHash()).param("rule", in.ruleId()).param("policy", in.classificationPolicy())
                .param("asset", in.assetCode()).param("quantity", in.signedQuantity())
                .param("occurred", utc(in.occurredAt())).param("status", outcome.status().name())
                .param("reason", outcome.reason() == null ? null : outcome.reason().name())
                .param("retryable", outcome.retryable())
                .param("evidence", outcome.evidenceType() == null ? null : outcome.evidenceType().name())
                .param("route", outcome.routeId()).param("unit", outcome.usdUnitPrice())
                .param("usd", outcome.usdValue()).param("estimated", outcome.estimated()).update();
            for (PriceConversionPolicy.LegOutcome leg : outcome.legs()) {
                jdbc.sql("INSERT INTO sync_cashflow_conversion_leg(conversion_id,source_event_id,leg_index,symbol,"
                        + "base_asset,quote_asset,instrument_observation_id,candle_id,candle_start_at,candle_end_at,"
                        + "close_price,selection_rule,lag_minutes) VALUES (:id,:event,:index,:symbol,:base,:quote,"
                        + ":instrument,:candle,:start,:end,:close,:rule,:lag)")
                    .param("id", conversionId).param("event", in.sourceEventId()).param("index", leg.legIndex())
                    .param("symbol", leg.leg().symbol()).param("base", leg.leg().baseAsset())
                    .param("quote", leg.leg().quoteAsset()).param("instrument", leg.instrument().observationId())
                    .param("candle", leg.candle().candleId()).param("start", utc(leg.candle().startAt()))
                    .param("end", utc(leg.candle().startAt().plus(PriceConversionPolicy.MINUTE)))
                    .param("close", leg.candle().close()).param("rule", leg.rule().name())
                    .param("lag", leg.lagMinutes()).update();
            }
            if (summary.calculable()) {
                jdbc.sql("INSERT INTO sync_calculable_cashflow(conversion_id,source_event_id,user_id,classification_id,"
                        + "source_id,semantic_hash,asset_code,signed_quantity,occurred_at,usd_value,estimated,"
                        + "evidence_type,price_policy_version,calculation_policy_version) VALUES (:id,:event,:u,"
                        + ":classification,:source,:hash,:asset,:quantity,:occurred,:usd,:estimated,:evidence,:policy,:calc)")
                    .param("id", conversionId).param("event", in.sourceEventId()).param("u", lease.userId())
                    .param("classification", lease.classificationId()).param("source", in.sourceId())
                    .param("hash", in.semanticHash()).param("asset", in.assetCode())
                    .param("quantity", in.signedQuantity()).param("occurred", utc(in.occurredAt()))
                    .param("usd", outcome.usdValue()).param("estimated", outcome.estimated())
                    .param("evidence", outcome.evidenceType() == null ? null : outcome.evidenceType().name())
                    .param("policy", lease.policyVersion())
                    .param("calc", PriceConversionPolicy.CALCULATION_POLICY_VERSION).update();
            }
        }
        return conversionId;
    }

    /** Whether any flow now cites a different revision of a candle the previous bundle cited. */
    private boolean priceRevised(UUID previousId, List<PriceConversionPolicy.FlowOutcome> outcomes) {
        Map<String, UUID> previous = new HashMap<>();
        jdbc.sql("SELECT source_event_id,symbol,candle_start_at,candle_id FROM sync_cashflow_conversion_leg "
                + "WHERE conversion_id=:c").param("c", previousId)
            .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getObject(4, UUID.class)}).list()
            .forEach(row -> previous.put(row[0] + "|" + row[1] + "|" + row[2], (UUID) row[3]));
        for (PriceConversionPolicy.FlowOutcome outcome : outcomes) {
            for (PriceConversionPolicy.LegOutcome leg : outcome.legs()) {
                UUID cited = previous.get(outcome.input().sourceEventId() + "|" + leg.leg().symbol() + "|"
                        + leg.candle().startAt());
                if (cited != null && !cited.equals(leg.candle().candleId())) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- views


    /** The latest attempt of the classification the intent currently converts. */
    Optional<ConversionJob> latestView(UUID user, UUID intent, UUID classificationId) {
        Optional<UUID> latest = jdbc.sql("SELECT job_id FROM sync_cashflow_conversion_job WHERE user_id=:u "
                + "AND intent_id=:i AND classification_id=:c ORDER BY created_at DESC,job_id DESC LIMIT 1")
            .param("u", user).param("i", intent).param("c", classificationId).query(UUID.class).optional();
        return latest.flatMap(jobId -> view(user, jobId));
    }

    /** The attempt's safe status view. It carries counts and held flows, never a USD value. */
    Optional<ConversionJob> view(UUID user, UUID jobId) {
        Optional<Object[]> row = jdbc.sql("SELECT j.job_id,j.status,j.failure_code,j.retryable,j.finished_at,j.flow_count,"
                + "j.policy_version,j.calculation_policy_version,j.conversion_id,b.converted_count,b.estimated_count,"
                + "b.conversion_required_count,b.review_count,b.calculable FROM sync_cashflow_conversion_job j "
                + "LEFT JOIN sync_cashflow_conversion_bundle b ON b.conversion_id=j.conversion_id AND b.user_id=j.user_id "
                + "WHERE j.user_id=:u AND j.job_id=:x")
            .param("u", user).param("x", jobId)
            .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getBoolean(4), instantOrNull(rs, 5), rs.getInt(6), rs.getString(7), rs.getString(8),
                    rs.getObject(9, UUID.class), rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getInt(13),
                    rs.getBoolean(14)}).optional();
        return row.map(r -> {
            UUID conversionId = (UUID) r[8];
            boolean result = conversionId != null;
            // Outcome counts exist only with a result bundle. An attempt without one (in progress,
            // cancelled, interrupted) reports its input count and no outcome counts.
            return new ConversionJob((UUID) r[0], (String) r[1], (String) r[2], (Boolean) r[3], (Instant) r[4],
                    (Integer) r[5], result ? (Integer) r[9] : null, result ? (Integer) r[10] : null,
                    result ? (Integer) r[11] : null, result ? (Integer) r[12] : null,
                    result && (Boolean) r[13], (String) r[6], (String) r[7],
                    result ? heldAssets(conversionId) : List.of());
        });
    }

    /** Held flows — both conversion-required and review-required — grouped by asset. */
    private List<ConversionPendingAsset> heldAssets(UUID conversionId) {
        Map<String, List<ConversionPendingFlow>> byAsset = new TreeMap<>();
        jdbc.sql("SELECT asset_code,signed_quantity,occurred_at,reason_code,retryable,status FROM sync_cashflow_conversion_flow "
                + "WHERE conversion_id=:c AND status IN ('CONVERSION_REQUIRED','REVIEW_REQUIRED') "
                + "ORDER BY asset_code,occurred_at,source_id,source_event_id")
            .param("c", conversionId)
            .query((rs, n) -> {
                String quantity = rs.getString(2);
                boolean withdrawal = quantity.startsWith("-");
                return Map.entry(rs.getString(1), new ConversionPendingFlow(withdrawal ? "WITHDRAWAL" : "DEPOSIT",
                        withdrawal ? quantity.substring(1) : quantity, rs.getObject(3, OffsetDateTime.class).toInstant(),
                        rs.getString(6), rs.getString(4), rs.getBoolean(5)));
            }).list()
            .forEach(entry -> byAsset.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue()));
        List<ConversionPendingAsset> assets = new ArrayList<>();
        byAsset.forEach((asset, flows) -> assets.add(new ConversionPendingAsset(asset, flows.size(), List.copyOf(flows))));
        return List.copyOf(assets);
    }

    // ---------------------------------------------------------------- erasure

    /**
     * Erases this user's attempts and results. Shared market evidence carries no user identifier
     * and stays; other users' rows are untouched. Attempts go first because they reference bundles.
     */
    void erase(UUID user) {
        jdbc.sql("DELETE FROM sync_calculable_cashflow WHERE user_id=:u").param("u", user).update();
        jdbc.sql("DELETE FROM sync_cashflow_conversion_job WHERE user_id=:u").param("u", user).update();
        jdbc.sql("DELETE FROM sync_cashflow_conversion_bundle WHERE user_id=:u").param("u", user).update();
    }

    // ---------------------------------------------------------------- mapping

    private static Input input(ResultSet rs, int row) throws SQLException {
        return new Input(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getObject(11, OffsetDateTime.class).toInstant());
    }

    private static PriceConversionPolicy.CandleFact candle(ResultSet rs, int row) throws SQLException {
        return new PriceConversionPolicy.CandleFact(rs.getObject(1, UUID.class),
                rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getInt(3), rs.getString(4), rs.getString(5));
    }

    private static StoredInstrument instrument(ResultSet rs, int row) throws SQLException {
        boolean listed = rs.getBoolean(2);
        return new StoredInstrument(new PriceConversionPolicy.InstrumentFact(rs.getObject(1, UUID.class), listed,
                listed ? rs.getString(3) : null, rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                rs.getInt(8));
    }

    private static List<Input> sorted(List<Input> inputs) {
        return inputs.stream().sorted(Comparator.comparing(Input::sourceId).thenComparing(Input::sourceEventId)).toList();
    }

    private static @Nullable Instant instantOrNull(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
