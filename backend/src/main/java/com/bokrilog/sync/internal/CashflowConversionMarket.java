package com.bokrilog.sync.internal;

import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceRejectionReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gathers exactly the public market facts {@link PriceConversionPolicy} asks for, outside any
 * database transaction, and records them as shared evidence in short transactions.
 *
 * <p>The loop is: evaluate every flow, collect what the pending ones need, fetch it, evaluate again.
 * Within one attempt each symbol's instrument is asked once, and each symbol's candles are resolved
 * once: the first time any flow needs a symbol's candle, the request set covers the event minute of
 * <em>every</em> flow whose routes include that symbol, merged and chunked only by the
 * 1000-candle cap. So a symbol shared by routes ({@code USDCUSDT} is route one of {@code USDC} and
 * leg two of every cross route) is never requested again in a later round. An event minute whose
 * candle is already in the shared evidence is served from it without a kline call — that is how
 * flows of other users and earlier attempts share one observation. Nothing is retried
 * automatically.
 */
final class CashflowConversionMarket {
    /**
     * The longest path — the {@code USDT} route listed but without a candle, then the cross route —
     * settles in six rounds (instrument, candles, two cross instruments, cross candles, final
     * evaluation). The bound is a guard against a policy that never settles.
     */
    private static final int MAX_ROUNDS = 8;

    private final SourceConnectionApi sources;
    private final CashflowConversionRepository conversions;
    private final TransactionTemplate transactions;

    CashflowConversionMarket(SourceConnectionApi sources, CashflowConversionRepository conversions,
            TransactionTemplate transactions) {
        this.sources = sources;
        this.conversions = conversions;
        this.transactions = transactions;
    }

    /**
     * Converts the flows.
     *
     * @param reference the attempt's fixed reference time; {@code null} only when no flow needs a price
     * @param active checked before every provider call; when it turns false the attempt stops
     * @return outcomes in input order, or {@code null} when the attempt stopped
     */
    @Nullable List<PriceConversionPolicy.FlowOutcome> convert(List<PriceConversionPolicy.FlowInput> flows,
            PriceConversionPolicy.@Nullable Reference reference, BooleanSupplier active) {
        return convert(flows, reference, active, Set.of());
    }

    /**
     * Converts the flows, asking the provider again for the candles of the given event minutes even when the shared
     * evidence already holds them (Story 2.12: a verification re-reads the flows inside its targets). A close that
     * changed becomes the candle's next revision (Story 2.6's rule); the earlier revision stays.
     *
     * @param forcedMinutes event minutes whose cached candles are not trusted in this attempt
     */
    @Nullable List<PriceConversionPolicy.FlowOutcome> convert(List<PriceConversionPolicy.FlowInput> flows,
            PriceConversionPolicy.@Nullable Reference reference, BooleanSupplier active, Set<Instant> forcedMinutes) {
        Map<String, PriceConversionPolicy.InstrumentLookup> instruments = new HashMap<>();
        Map<String, Map<Instant, PriceConversionPolicy.CandleLookup>> candles = new HashMap<>();
        Map<String, Set<Instant>> potentialMinutes = reference instanceof PriceConversionPolicy.Reference.Fixed fixed
                ? potentialMinutes(flows, fixed.at()) : Map.of();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            PriceConversionPolicy.MarketView view = new PriceConversionPolicy.MarketView(reference,
                    Collections.unmodifiableMap(instruments), Collections.unmodifiableMap(candles));
            List<PriceConversionPolicy.Evaluation> evaluations = new ArrayList<>();
            for (PriceConversionPolicy.FlowInput flow : flows) evaluations.add(PriceConversionPolicy.evaluate(flow, view));
            Set<String> instrumentNeeds = new TreeSet<>();
            Map<String, Set<Instant>> candleNeeds = new TreeMap<>();
            for (PriceConversionPolicy.Evaluation evaluation : evaluations) {
                if (evaluation instanceof PriceConversionPolicy.Evaluation.Pending pending) {
                    instrumentNeeds.addAll(pending.instruments());
                    pending.candles().forEach((symbol, minutes) ->
                            candleNeeds.computeIfAbsent(symbol, ignored -> new TreeSet<>()).addAll(minutes));
                }
            }
            if (instrumentNeeds.isEmpty() && candleNeeds.isEmpty()) {
                return evaluations.stream()
                        .map(evaluation -> ((PriceConversionPolicy.Evaluation.Final) evaluation).outcome()).toList();
            }
            if (!(reference instanceof PriceConversionPolicy.Reference.Fixed fixed))
                throw new IllegalStateException("market facts requested without a fixed reference time");
            for (String symbol : instrumentNeeds) {
                if (!active.getAsBoolean()) return null;
                instruments.put(symbol, instrument(symbol, fixed.at()));
            }
            for (Map.Entry<String, Set<Instant>> need : candleNeeds.entrySet()) {
                String symbol = need.getKey();
                Map<Instant, PriceConversionPolicy.CandleLookup> bySymbol =
                        candles.computeIfAbsent(symbol, ignored -> new HashMap<>());
                Set<Instant> minutes = new TreeSet<>(need.getValue());
                minutes.addAll(potentialMinutes.getOrDefault(symbol, Set.of()));
                minutes.removeAll(bySymbol.keySet());
                if (!resolveCandles(symbol, minutes, fixed.at(), bySymbol, active, forcedMinutes)) return null;
            }
        }
        throw new IllegalStateException("conversion evaluation did not settle");
    }

    /**
     * Per symbol, the closed event minutes of every flow whose routes include it. Unclosed minutes
     * are left out: the policy never asks a candle for them.
     */
    private static Map<String, Set<Instant>> potentialMinutes(List<PriceConversionPolicy.FlowInput> flows,
            Instant reference) {
        Map<String, Set<Instant>> minutes = new HashMap<>();
        for (PriceConversionPolicy.FlowInput flow : flows) {
            Instant minute = PriceConversionPolicy.eventMinute(flow.occurredAt());
            if (!PriceConversionPolicy.isClosed(minute, reference)) continue;
            for (String symbol : PriceConversionPolicy.candidateSymbols(flow))
                minutes.computeIfAbsent(symbol, ignored -> new TreeSet<>()).add(minute);
        }
        return minutes;
    }

    /**
     * One instrument answer. An empty list means not listed. A non-empty list without an exact
     * symbol match, or with the symbol listed twice with different facts, is an answer we do not
     * recognize: it is a schema failure (review), never "not listed".
     */
    private PriceConversionPolicy.InstrumentLookup instrument(String symbol, Instant reference) {
        SourceConnectionApi.InstrumentResult result = sources.spotInstrument(symbol);
        if (result instanceof SourceConnectionApi.InstrumentResult.Rejected rejected)
            return new PriceConversionPolicy.InstrumentLookup.Failed(isSchemaFailure(rejected.reason()));
        List<SourceConnectionApi.MarketInstrument> listed = ((SourceConnectionApi.InstrumentResult.Listed) result).instruments();
        List<SourceConnectionApi.MarketInstrument> matching =
                listed.stream().filter(entry -> symbol.equals(entry.symbol())).toList();
        if (!listed.isEmpty() && matching.isEmpty()) return new PriceConversionPolicy.InstrumentLookup.Failed(true);
        Set<String> distinct = new HashSet<>();
        matching.forEach(entry -> distinct.add(entry.evidenceHash()));
        if (distinct.size() > 1) return new PriceConversionPolicy.InstrumentLookup.Failed(true);
        SourceConnectionApi.MarketInstrument entry = matching.isEmpty() ? null : matching.getFirst();
        PriceConversionPolicy.InstrumentFact fact =
                transactions.execute(status -> conversions.storeInstrument(symbol, entry, reference));
        return new PriceConversionPolicy.InstrumentLookup.Observed(fact);
    }

    /**
     * Resolves the given event minutes of one symbol: shared evidence first, then merged kline
     * windows for the misses.
     *
     * <p>A contradiction is decided on the price value: the same start listed with two different
     * canonical closes. Rows that differ only in volume or turnover agree on the price and are not
     * a conflict; the first row's raw hash is kept as provenance.
     *
     * @return {@code false} when the attempt stopped
     */
    private boolean resolveCandles(String symbol, Set<Instant> minutes, Instant reference,
            Map<Instant, PriceConversionPolicy.CandleLookup> target, BooleanSupplier active, Set<Instant> forcedMinutes) {
        if (minutes.isEmpty()) return true;
        Map<Instant, PriceConversionPolicy.CandleFact> cached = conversions.latestCandles(symbol, minutes);
        List<Instant> misses = new ArrayList<>();
        for (Instant minute : minutes) {
            PriceConversionPolicy.CandleFact hit = forcedMinutes.contains(minute) ? null : cached.get(minute);
            if (hit != null) target.put(minute, new PriceConversionPolicy.CandleLookup.Observed(Map.of(minute, hit), Set.of()));
            else misses.add(minute);
        }
        for (PriceConversionPolicy.RequestWindow window : PriceConversionPolicy.requestWindows(misses)) {
            if (!active.getAsBoolean()) return false;
            SourceConnectionApi.CandleResult result = sources.spotMinuteCandles(symbol, window.start(), window.end());
            if (result instanceof SourceConnectionApi.CandleResult.Rejected rejected) {
                boolean malformed = isSchemaFailure(rejected.reason());
                window.eventMinutes().forEach(minute ->
                        target.put(minute, new PriceConversionPolicy.CandleLookup.Failed(malformed)));
                continue;
            }
            Set<Instant> needed = new HashSet<>();
            window.eventMinutes().forEach(minute -> needed.addAll(PriceConversionPolicy.windowStarts(minute)));
            Map<Instant, Set<String>> closes = new HashMap<>();
            Map<Instant, SourceConnectionApi.MarketCandle> first = new TreeMap<>();
            for (SourceConnectionApi.MarketCandle row : ((SourceConnectionApi.CandleResult.Candles) result).candles()) {
                // Unclosed candles are neither used nor recorded: their close can still change.
                if (!needed.contains(row.startAt()) || !PriceConversionPolicy.isClosed(row.startAt(), reference)) continue;
                closes.computeIfAbsent(row.startAt(), ignored -> new HashSet<>())
                        .add(PriceConversionPolicy.canonical(new BigDecimal(row.close())));
                first.putIfAbsent(row.startAt(), row);
            }
            Set<Instant> conflicts = new HashSet<>();
            List<CashflowConversionRepository.CandleObservation> observations = new ArrayList<>();
            first.forEach((start, row) -> {
                Set<String> values = closes.get(start);
                if (values.size() > 1) conflicts.add(start);
                else observations.add(new CashflowConversionRepository.CandleObservation(start, values.iterator().next(),
                        row.evidenceHash(), window.start(), window.end()));
            });
            Map<Instant, PriceConversionPolicy.CandleFact> stored = observations.isEmpty() ? Map.of()
                    : transactions.execute(status -> conversions.storeCandles(symbol, observations, reference));
            for (Instant minute : window.eventMinutes()) {
                Map<Instant, PriceConversionPolicy.CandleFact> candidates = new HashMap<>();
                Set<Instant> contradicted = new HashSet<>();
                for (Instant start : PriceConversionPolicy.windowStarts(minute)) {
                    PriceConversionPolicy.CandleFact fact = stored.get(start);
                    if (fact != null) candidates.put(start, fact);
                    if (conflicts.contains(start)) contradicted.add(start);
                }
                target.put(minute, new PriceConversionPolicy.CandleLookup.Observed(Map.copyOf(candidates),
                        Set.copyOf(contradicted)));
            }
        }
        return true;
    }

    private static boolean isSchemaFailure(SourceRejectionReason reason) {
        return reason == SourceRejectionReason.RESPONSE_FORMAT || reason == SourceRejectionReason.INVALID_FORMAT;
    }
}
