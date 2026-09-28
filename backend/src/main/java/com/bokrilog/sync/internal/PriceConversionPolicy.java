package com.bokrilog.sync.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Pure, versioned {@code BYBIT-PRICE-1.0} event-time USD conversion with {@code CALC-1.0} arithmetic.
 *
 * <p>No I/O, no clock, no database. Every decision is a function of the flow, the fixed reference
 * time of the attempt and the market facts in a {@link MarketView}. The caller (the conversion
 * worker) fetches whatever {@link Evaluation.Pending} asks for and evaluates again, so the policy
 * also decides <em>which</em> provider facts are needed — lazily, one route and one leg at a time.
 *
 * <p>The rules, in order:
 * <ol>
 *   <li>Inputs are an asset code {@code [A-Z0-9]{1,32}} and a non-zero canonical signed quantity
 *       within precision 50 / scale 24; otherwise {@link Reason#INPUT_INVALID}.
 *   <li>{@code USD} and {@code USDT} alone are {@link EvidenceType#DIRECT_NORMALIZATION}: one unit is
 *       one USD by product policy, not a market claim. Nothing else is assumed to be worth 1 USD —
 *       {@code USDC} included.
 *   <li>Every other asset uses the first spot route whose legs all hold: {@code ASSETUSDT}, then
 *       {@code ASSETUSDC × USDCUSDT} ({@code USDC} itself has only {@code USDCUSDT}). A leg holds
 *       when Instruments Info lists exactly that symbol with {@code baseCoin}, {@code quoteCoin}
 *       and {@code status=Trading} equal, and a candle is selected with a positive close.
 *   <li>The candle is the closed 1-minute candle containing the event ({@code start = floor(t)},
 *       closed when {@code start + 1m <= reference}); if that candle is not closed the flow is
 *       retryable with no fallback. If it is closed but absent, the most recent of
 *       {@code start-1m … start-5m} is used. Later candles are never used.
 *   <li>A route that fails for a transient reason (provider failure, unclosed candle) stops the
 *       search — falling through to the next route would make the answer depend on timing.
 *       Only definitive failures (not listed, not trading, no candle within five minutes, close
 *       &lt;= 0) move on to the next route.
 *   <li>The USD value is the exact product of the signed quantity and every leg close, rounded
 *       once to the canonical form (at most 34 significant digits, scale at most 24, HALF_EVEN).
 *       {@code |v| >= 1E24} or a non-zero {@code |v| < 1E-24} is
 *       {@link Reason#CALCULATION_RANGE_EXCEEDED}.
 * </ol>
 */
final class PriceConversionPolicy {
    static final String POLICY_VERSION = "BYBIT-PRICE-1.0";
    static final String CALCULATION_POLICY_VERSION = "CALC-1.0";

    static final String USD = "USD";
    static final String USDT = "USDT";
    static final String USDC = "USDC";
    static final String TRADING = "Trading";
    static final String DIRECT_ROUTE = "DIRECT";
    static final String USDT_ROUTE = "SPOT_ASSET_USDT";
    static final String USDC_CROSS_ROUTE = "SPOT_ASSET_USDC_USDCUSDT";

    static final Duration MINUTE = Duration.ofMinutes(1);
    static final int MAX_FALLBACK_MINUTES = 5;
    static final int MAX_CANDLES_PER_REQUEST = 1000;

    static final int MAX_SIGNIFICANT_DIGITS = 34;
    static final int MAX_SCALE = 24;
    static final int MAX_INPUT_PRECISION = 50;
    static final int MAX_INPUT_SCALE = 24;
    static final BigDecimal UPPER_BOUND = new BigDecimal("1E24");
    static final BigDecimal LOWER_BOUND = new BigDecimal("1E-24");

    private static final Pattern ASSET = Pattern.compile("[A-Z0-9]{1,32}");
    private static final Pattern CANONICAL = Pattern.compile("-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?");

    private PriceConversionPolicy() {}

    /** Flow status in ascending bundle priority (declaration order is the priority). */
    enum Status { CONVERTED, CONVERTED_ESTIMATED, CONVERSION_REQUIRED, REVIEW_REQUIRED }

    /** Safe reason codes. Declaration order breaks ties deterministically. */
    enum Reason {
        PRICE_PROVIDER_UNAVAILABLE(Status.CONVERSION_REQUIRED, true),
        PRICE_CANDLE_NOT_CLOSED(Status.CONVERSION_REQUIRED, true),
        PRICE_ROUTE_UNAVAILABLE(Status.CONVERSION_REQUIRED, false),
        PRICE_CANDLE_MISSING(Status.CONVERSION_REQUIRED, false),
        PRICE_NON_POSITIVE(Status.CONVERSION_REQUIRED, false),
        PRICE_RESPONSE_MALFORMED(Status.REVIEW_REQUIRED, false),
        PRICE_CANDLE_CONFLICT(Status.REVIEW_REQUIRED, false),
        CALCULATION_RANGE_EXCEEDED(Status.REVIEW_REQUIRED, false),
        INPUT_INVALID(Status.REVIEW_REQUIRED, false);

        private final Status status;
        private final boolean retryable;

        Reason(Status status, boolean retryable) {
            this.status = status;
            this.retryable = retryable;
        }

        Status status() { return this.status; }

        boolean retryable() { return this.retryable; }
    }

    enum EvidenceType { DIRECT_NORMALIZATION, SPOT_KLINE_CLOSE }

    enum SelectionRule { EVENT_MINUTE, PRIOR_CLOSED_MINUTE }

    record FlowInput(java.util.UUID sourceEventId, String sourceId, String assetCode,
            String signedQuantity, Instant occurredAt) {}

    record Leg(String symbol, String baseAsset, String quoteAsset) {}

    record Route(String routeId, List<Leg> legs) {}

    /** One stored instrument observation. {@code listed=false} means the query returned no entry. */
    record InstrumentFact(java.util.UUID observationId, boolean listed, @Nullable String symbol,
            @Nullable String baseAsset, @Nullable String quoteAsset, @Nullable String status,
            String evidenceHash) {}

    sealed interface InstrumentLookup {
        record Observed(InstrumentFact fact) implements InstrumentLookup {}
        /** The provider call failed; {@code malformed} separates schema failures from transient ones. */
        record Failed(boolean malformed) implements InstrumentLookup {}
    }

    /** One stored candle revision. {@code close} is canonical. */
    record CandleFact(java.util.UUID candleId, Instant startAt, int revision, String close,
            String evidenceHash) {}

    sealed interface CandleLookup {
        /**
         * Candles available for one event minute, keyed by start, and the starts the provider
         * contradicted itself on.
         */
        record Observed(Map<Instant, CandleFact> candles, Set<Instant> conflicts) implements CandleLookup {}
        record Failed(boolean malformed) implements CandleLookup {}
    }

    sealed interface Reference {
        record Fixed(Instant at) implements Reference {}
        record Failed(boolean malformed) implements Reference {}
    }

    /**
     * What the attempt knows. Candles are keyed by symbol and then by <em>event minute</em>: the
     * lookup for an event minute holds what was fetched (or cached) for that minute's window.
     */
    record MarketView(@Nullable Reference reference, Map<String, InstrumentLookup> instruments,
            Map<String, Map<Instant, CandleLookup>> candles) {}

    record LegOutcome(int legIndex, Leg leg, InstrumentFact instrument, CandleFact candle,
            SelectionRule rule, int lagMinutes) {}

    record FlowOutcome(FlowInput input, Status status, @Nullable Reason reason,
            @Nullable EvidenceType evidenceType, @Nullable String routeId, @Nullable String usdUnitPrice,
            @Nullable String usdValue, List<LegOutcome> legs) {
        boolean estimated() { return this.status == Status.CONVERTED_ESTIMATED; }

        boolean retryable() { return this.reason != null && this.reason.retryable(); }
    }

    sealed interface Evaluation {
        record Final(FlowOutcome outcome) implements Evaluation {}
        /** Facts to fetch before this flow can be decided: instrument symbols, candle event minutes. */
        record Pending(Set<String> instruments, Map<String, Set<Instant>> candles) implements Evaluation {}
    }

    record BundleOutcome(Status status, @Nullable Reason reason, boolean retryable, int converted,
            int estimated, int conversionRequired, int review) {
        boolean calculable() {
            return this.status == Status.CONVERTED || this.status == Status.CONVERTED_ESTIMATED;
        }

        int flowCount() { return this.converted + this.estimated + this.conversionRequired + this.review; }
    }

    /** One kline request: {@code [start, end]} covers the windows of {@code eventMinutes}. */
    record RequestWindow(Instant start, Instant end, List<Instant> eventMinutes) {
        long candleCount() { return Duration.between(this.start, this.end).toMinutes() + 1; }
    }

    static boolean isDirect(String asset) {
        return USD.equals(asset) || USDT.equals(asset);
    }

    /** Whether the flow needs provider facts at all (a valid, non-direct flow). */
    static boolean needsMarket(FlowInput flow) {
        return validInput(flow) && !isDirect(flow.assetCode());
    }

    /** The approved spot routes in priority order. Empty for directly normalized assets. */
    static List<Route> routes(String asset) {
        if (isDirect(asset)) return List.of();
        if (USDC.equals(asset)) return List.of(new Route(USDT_ROUTE, List.of(new Leg(USDC + USDT, USDC, USDT))));
        return List.of(
                new Route(USDT_ROUTE, List.of(new Leg(asset + USDT, asset, USDT))),
                new Route(USDC_CROSS_ROUTE, List.of(new Leg(asset + USDC, asset, USDC),
                        new Leg(USDC + USDT, USDC, USDT))));
    }

    /**
     * Every symbol any approved route of the flow could ask a candle of. The market resolves a
     * symbol's candles once per attempt for all flows that could need it, so a symbol shared by
     * routes ({@code USDCUSDT}) is not requested again in a later round.
     */
    static Set<String> candidateSymbols(FlowInput flow) {
        if (!needsMarket(flow)) return Set.of();
        Set<String> symbols = new TreeSet<>();
        for (Route route : routes(flow.assetCode())) for (Leg leg : route.legs()) symbols.add(leg.symbol());
        return symbols;
    }

    static Instant eventMinute(Instant occurredAt) {
        long minute = MINUTE.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(occurredAt.toEpochMilli(), minute) * minute);
    }

    /** A candle is closed once its end is not after the reference time. */
    static boolean isClosed(Instant candleStart, Instant reference) {
        return !candleStart.plus(MINUTE).isAfter(reference);
    }

    /** The starts a lookup for {@code eventMinute} may use, most recent first. */
    static List<Instant> windowStarts(Instant eventMinute) {
        List<Instant> starts = new ArrayList<>();
        for (int lag = 0; lag <= MAX_FALLBACK_MINUTES; lag++) starts.add(eventMinute.minus(MINUTE.multipliedBy(lag)));
        return starts;
    }

    static Evaluation evaluate(FlowInput flow, MarketView view) {
        if (!validInput(flow)) return fail(flow, Reason.INPUT_INVALID);
        if (isDirect(flow.assetCode())) {
            String usd = canonicalResult(new BigDecimal(flow.signedQuantity()));
            if (usd == null) return fail(flow, Reason.CALCULATION_RANGE_EXCEEDED);
            return new Evaluation.Final(new FlowOutcome(flow, Status.CONVERTED, null,
                    EvidenceType.DIRECT_NORMALIZATION, DIRECT_ROUTE, "1", usd, List.of()));
        }
        Reference reference = view.reference();
        if (reference == null) throw new IllegalStateException("a market flow needs a fixed reference time");
        if (reference instanceof Reference.Failed failed)
            return fail(flow, failed.malformed() ? Reason.PRICE_RESPONSE_MALFORMED : Reason.PRICE_PROVIDER_UNAVAILABLE);
        Instant at = ((Reference.Fixed) reference).at();
        Instant minute = eventMinute(flow.occurredAt());
        @Nullable Reason firstTradableFailure = null;
        for (Route route : routes(flow.assetCode())) {
            // Legs are judged in order and asked for one at a time: a leg that is not tradable
            // settles the route, so the next leg's instrument is never requested for nothing.
            boolean unavailable = false;
            List<InstrumentFact> facts = new ArrayList<>();
            for (Leg leg : route.legs()) {
                InstrumentLookup lookup = view.instruments().get(leg.symbol());
                if (lookup == null) return new Evaluation.Pending(Set.of(leg.symbol()), Map.of());
                if (lookup instanceof InstrumentLookup.Failed failed)
                    return fail(flow, failed.malformed() ? Reason.PRICE_RESPONSE_MALFORMED : Reason.PRICE_PROVIDER_UNAVAILABLE);
                InstrumentFact fact = ((InstrumentLookup.Observed) lookup).fact();
                if (!isTrading(leg, fact)) {
                    unavailable = true;
                    break;
                }
                facts.add(fact);
            }
            if (unavailable) continue;

            if (!isClosed(minute, at)) return fail(flow, Reason.PRICE_CANDLE_NOT_CLOSED);
            Map<String, Set<Instant>> missingCandles = new TreeMap<>();
            for (Leg leg : route.legs()) {
                Map<Instant, CandleLookup> bySymbol = view.candles().get(leg.symbol());
                if (bySymbol == null || !bySymbol.containsKey(minute)) missingCandles.put(leg.symbol(), Set.of(minute));
            }
            if (!missingCandles.isEmpty()) return new Evaluation.Pending(Set.of(), missingCandles);

            @Nullable Reason review = null;
            @Nullable Reason definitive = null;
            boolean retry = false;
            List<LegOutcome> legs = new ArrayList<>();
            for (int index = 0; index < route.legs().size(); index++) {
                Leg leg = route.legs().get(index);
                CandleLookup lookup = view.candles().get(leg.symbol()).get(minute);
                if (lookup instanceof CandleLookup.Failed failed) {
                    if (failed.malformed()) review = first(review, Reason.PRICE_RESPONSE_MALFORMED); else retry = true;
                    continue;
                }
                CandleLookup.Observed observed = (CandleLookup.Observed) lookup;
                @Nullable LegOutcome selected = null;
                for (int lag = 0; lag <= MAX_FALLBACK_MINUTES && selected == null; lag++) {
                    Instant start = minute.minus(MINUTE.multipliedBy(lag));
                    if (observed.conflicts().contains(start)) {
                        review = first(review, Reason.PRICE_CANDLE_CONFLICT);
                        break;
                    }
                    CandleFact candle = observed.candles().get(start);
                    if (candle != null) selected = new LegOutcome(index, leg, facts.get(index), candle,
                            lag == 0 ? SelectionRule.EVENT_MINUTE : SelectionRule.PRIOR_CLOSED_MINUTE, lag);
                }
                if (selected == null) {
                    if (review == null) definitive = first(definitive, Reason.PRICE_CANDLE_MISSING);
                    continue;
                }
                BigDecimal close = inputDecimal(selected.candle().close());
                if (close == null) { review = first(review, Reason.CALCULATION_RANGE_EXCEEDED); continue; }
                if (close.signum() <= 0) { definitive = first(definitive, Reason.PRICE_NON_POSITIVE); continue; }
                legs.add(selected);
            }
            if (review != null) return fail(flow, review);
            if (definitive != null) {
                // The reason reported when no route holds is the first tradable route's failure.
                if (firstTradableFailure == null) firstTradableFailure = definitive;
                continue;
            }
            if (retry) return fail(flow, Reason.PRICE_PROVIDER_UNAVAILABLE);

            BigDecimal unit = BigDecimal.ONE;
            for (LegOutcome leg : legs) unit = unit.multiply(new BigDecimal(leg.candle().close()));
            String unitPrice = canonicalResult(unit);
            String usd = canonicalResult(new BigDecimal(flow.signedQuantity()).multiply(unit));
            if (unitPrice == null || usd == null) return fail(flow, Reason.CALCULATION_RANGE_EXCEEDED);
            return new Evaluation.Final(new FlowOutcome(flow, Status.CONVERTED_ESTIMATED, null,
                    EvidenceType.SPOT_KLINE_CLOSE, route.routeId(), unitPrice, usd, List.copyOf(legs)));
        }
        return fail(flow, firstTradableFailure == null ? Reason.PRICE_ROUTE_UNAVAILABLE : firstTradableFailure);
    }

    /**
     * Rounds an exact value once into the CALC-1.0 canonical form.
     *
     * @return canonical decimal string, or {@code null} when {@code |v| >= 1E24} or {@code v} is
     *     non-zero with {@code |v| < 1E-24}
     */
    static @Nullable String canonicalResult(BigDecimal exact) {
        if (exact.signum() == 0) return "0";
        if (exact.abs().compareTo(LOWER_BOUND) < 0) return null;
        int target = exact.scale();
        if (exact.precision() > MAX_SIGNIFICANT_DIGITS) target = exact.scale() - (exact.precision() - MAX_SIGNIFICANT_DIGITS);
        if (target > MAX_SCALE) target = MAX_SCALE;
        BigDecimal rounded = target < exact.scale() ? exact.setScale(target, RoundingMode.HALF_EVEN) : exact;
        if (rounded.abs().compareTo(UPPER_BOUND) >= 0) return null;
        return canonical(rounded);
    }

    static String canonical(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }

    /** Bundle status by priority; the reason prefers a retryable one when the status allows retry. */
    static BundleOutcome summarize(List<FlowOutcome> outcomes) {
        int converted = 0, estimated = 0, required = 0, review = 0;
        Status status = Status.CONVERTED;
        for (FlowOutcome outcome : outcomes) {
            switch (outcome.status()) {
                case CONVERTED -> converted++;
                case CONVERTED_ESTIMATED -> estimated++;
                case CONVERSION_REQUIRED -> required++;
                case REVIEW_REQUIRED -> review++;
            }
            if (outcome.status().ordinal() > status.ordinal()) status = outcome.status();
        }
        final Status top = status;
        List<Reason> reasons = outcomes.stream().filter(o -> o.status() == top && o.reason() != null)
                .map(FlowOutcome::reason).sorted().toList();
        boolean retryable = top == Status.CONVERSION_REQUIRED && reasons.stream().anyMatch(Reason::retryable);
        @Nullable Reason reason = reasons.isEmpty() ? null
                : retryable ? reasons.stream().filter(Reason::retryable).findFirst().orElseThrow()
                : reasons.getFirst();
        return new BundleOutcome(top, reason, retryable, converted, estimated, required, review);
    }

    /**
     * The outcome identity. Same input scope, same policy, same evidence (candle revision, instrument
     * value) and same decisions give the same fingerprint regardless of flow order.
     */
    static String fingerprint(String inputScopeHash, List<FlowOutcome> outcomes) {
        StringBuilder payload = new StringBuilder(POLICY_VERSION).append('|').append(CALCULATION_POLICY_VERSION)
                .append('|').append(inputScopeHash);
        outcomes.stream().sorted(Comparator.comparing((FlowOutcome o) -> o.input().sourceId())
                        .thenComparing(o -> o.input().sourceEventId()))
                .forEach(o -> {
                    payload.append(";").append(o.input().sourceEventId()).append('|').append(o.status())
                            .append('|').append(o.reason()).append('|').append(o.evidenceType())
                            .append('|').append(o.routeId()).append('|').append(o.usdUnitPrice())
                            .append('|').append(o.usdValue());
                    for (LegOutcome leg : o.legs()) {
                        payload.append('#').append(leg.legIndex()).append('|').append(leg.leg().symbol())
                                .append('|').append(leg.instrument().evidenceHash()).append('|')
                                .append(leg.candle().startAt().toEpochMilli()).append('|')
                                .append(leg.candle().revision()).append('|').append(leg.candle().evidenceHash())
                                .append('|').append(leg.rule()).append('|').append(leg.lagMinutes());
                    }
                });
        return sha256(payload.toString());
    }

    /**
     * Packs event-minute windows ({@code [m-5m, m]}) into as few kline requests as possible, each
     * holding at most {@value #MAX_CANDLES_PER_REQUEST} candles. Greedy covering from the earliest
     * minute is optimal for fixed-length windows on a line.
     */
    static List<RequestWindow> requestWindows(Collection<Instant> eventMinutes) {
        List<Instant> minutes = new ArrayList<>(new TreeSet<>(eventMinutes));
        List<RequestWindow> windows = new ArrayList<>();
        int index = 0;
        while (index < minutes.size()) {
            Instant start = minutes.get(index).minus(MINUTE.multipliedBy(MAX_FALLBACK_MINUTES));
            Instant limit = start.plus(MINUTE.multipliedBy(MAX_CANDLES_PER_REQUEST - 1L));
            List<Instant> covered = new ArrayList<>();
            while (index < minutes.size() && !minutes.get(index).isAfter(limit)) covered.add(minutes.get(index++));
            windows.add(new RequestWindow(start, covered.getLast(), List.copyOf(covered)));
        }
        return windows;
    }

    private static boolean isTrading(Leg leg, InstrumentFact fact) {
        return fact.listed() && leg.symbol().equals(fact.symbol()) && leg.baseAsset().equals(fact.baseAsset())
                && leg.quoteAsset().equals(fact.quoteAsset()) && TRADING.equals(fact.status());
    }

    private static boolean validInput(FlowInput flow) {
        if (flow.assetCode() == null || !ASSET.matcher(flow.assetCode()).matches()) return false;
        BigDecimal quantity = inputDecimal(flow.signedQuantity());
        return quantity != null && quantity.signum() != 0 && flow.occurredAt() != null;
    }

    /** A canonical decimal within the CALC-1.0 input limit (precision 50, scale 24), or {@code null}. */
    static @Nullable BigDecimal inputDecimal(@Nullable String value) {
        if (value == null || value.length() > 128 || !CANONICAL.matcher(value).matches()) return null;
        BigDecimal decimal = new BigDecimal(value);
        if (decimal.precision() > MAX_INPUT_PRECISION || Math.max(decimal.scale(), 0) > MAX_INPUT_SCALE) return null;
        return decimal;
    }

    private static Reason first(@Nullable Reason current, Reason candidate) {
        return current == null || candidate.ordinal() < current.ordinal() ? candidate : current;
    }

    private static Evaluation fail(FlowInput flow, Reason reason) {
        return new Evaluation.Final(new FlowOutcome(flow, reason.status(), reason, null, null, null, null, List.of()));
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
