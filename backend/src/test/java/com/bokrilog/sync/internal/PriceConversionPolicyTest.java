package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.sync.internal.PriceConversionPolicy.CandleFact;
import com.bokrilog.sync.internal.PriceConversionPolicy.CandleLookup;
import com.bokrilog.sync.internal.PriceConversionPolicy.Evaluation;
import com.bokrilog.sync.internal.PriceConversionPolicy.FlowInput;
import com.bokrilog.sync.internal.PriceConversionPolicy.FlowOutcome;
import com.bokrilog.sync.internal.PriceConversionPolicy.InstrumentFact;
import com.bokrilog.sync.internal.PriceConversionPolicy.InstrumentLookup;
import com.bokrilog.sync.internal.PriceConversionPolicy.MarketView;
import com.bokrilog.sync.internal.PriceConversionPolicy.Reason;
import com.bokrilog.sync.internal.PriceConversionPolicy.Reference;
import com.bokrilog.sync.internal.PriceConversionPolicy.Status;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@code BYBIT-PRICE-1.0} and {@code CALC-1.0} as a pure function: matrix, boundaries, precision, determinism. */
class PriceConversionPolicyTest {
    private static final Instant MINUTE = Instant.parse("2026-09-01T10:30:00Z");
    private static final Instant EVENT = MINUTE.plusSeconds(17);
    private static final Instant REFERENCE = MINUTE.plus(Duration.ofHours(1));

    // ------------------------------------------------------------------ direct normalization

    @ParameterizedTest
    @CsvSource({"USDT,100,100", "USDT,-12.5,-12.5", "USD,0.000001,0.000001", "USD,-7,-7"})
    void onlyUsdAndUsdtAreNormalizedDirectlyAndKeepTheirSign(String asset, String quantity, String usd) {
        FlowOutcome outcome = settle(flow(asset, quantity), new Market());
        assertThat(outcome.status()).isEqualTo(Status.CONVERTED);
        assertThat(outcome.evidenceType()).isEqualTo(PriceConversionPolicy.EvidenceType.DIRECT_NORMALIZATION);
        assertThat(outcome.usdValue()).isEqualTo(usd);
        assertThat(outcome.estimated()).isFalse();
        assertThat(outcome.legs()).isEmpty();
        assertThat(PriceConversionPolicy.needsMarket(flow(asset, quantity))).isFalse();
    }

    @Test
    void usdcAndEveryOtherAssetFollowTheCommonSpotRouteRuleWithoutAFixedAllowList() {
        assertThat(PriceConversionPolicy.isDirect("USDC")).isFalse();
        assertThat(PriceConversionPolicy.routes("USDC")).singleElement()
                .satisfies(route -> assertThat(route.legs()).extracting(PriceConversionPolicy.Leg::symbol)
                        .containsExactly("USDCUSDT"));
        for (String asset : List.of("BTC", "ETH", "NEWCOIN1", "Z9")) {
            var routes = PriceConversionPolicy.routes(asset);
            assertThat(routes).hasSize(2);
            assertThat(routes.get(0).legs()).extracting(PriceConversionPolicy.Leg::symbol).containsExactly(asset + "USDT");
            assertThat(routes.get(1).legs()).extracting(PriceConversionPolicy.Leg::symbol)
                    .containsExactly(asset + "USDC", "USDCUSDT");
            assertThat(routes.get(1).legs().get(0).baseAsset()).isEqualTo(asset);
            assertThat(routes.get(1).legs().get(0).quoteAsset()).isEqualTo("USDC");
        }
    }

    @Test
    void candidateSymbolsCoverEveryRouteLegSoSharedSymbolsAreResolvedOnce() {
        assertThat(PriceConversionPolicy.candidateSymbols(flow("ETH", "1")))
                .containsExactlyInAnyOrder("ETHUSDT", "ETHUSDC", "USDCUSDT");
        assertThat(PriceConversionPolicy.candidateSymbols(flow("USDC", "1"))).containsExactly("USDCUSDT");
        assertThat(PriceConversionPolicy.candidateSymbols(flow("USDT", "1"))).isEmpty();
        assertThat(PriceConversionPolicy.candidateSymbols(flow("BTC", "0"))).as("invalid input asks nothing").isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"btc,1", "BTC-X,1", "BTC,0", "BTC,1.50", "BTC,01", "BTC,1E3", "BTC,NaN",
        "BTC,0.0000000000000000000000001"})
    void invalidAssetOrQuantityNeedsReviewWithoutProviderFacts(String asset, String quantity) {
        FlowOutcome outcome = settle(flow(asset, quantity), new Market());
        assertThat(outcome.status()).isEqualTo(Status.REVIEW_REQUIRED);
        assertThat(outcome.reason()).isEqualTo(Reason.INPUT_INVALID);
    }

    // ------------------------------------------------------------------ route and instruments

    @Test
    void theFirstRouteAsksOnlyForItsOwnInstrumentThenItsEventMinuteCandle() {
        Market market = new Market();
        Evaluation first = PriceConversionPolicy.evaluate(flow("BTC", "-0.5"), market.view());
        assertThat(first).isEqualTo(new Evaluation.Pending(Set.of("BTCUSDT"), Map.of()));
        market.trading("BTCUSDT", "BTC", "USDT");
        Evaluation second = PriceConversionPolicy.evaluate(flow("BTC", "-0.5"), market.view());
        assertThat(second).isEqualTo(new Evaluation.Pending(Set.of(), Map.of("BTCUSDT", Set.of(MINUTE))));
        market.candles("BTCUSDT", MINUTE, Map.of(MINUTE, "65000.25"));
        FlowOutcome outcome = settle(flow("BTC", "-0.5"), market);
        assertThat(outcome.status()).isEqualTo(Status.CONVERTED_ESTIMATED);
        assertThat(outcome.usdValue()).isEqualTo("-32500.125");
        assertThat(outcome.usdUnitPrice()).isEqualTo("65000.25");
        assertThat(outcome.routeId()).isEqualTo(PriceConversionPolicy.USDT_ROUTE);
        assertThat(outcome.legs()).singleElement().satisfies(leg -> {
            assertThat(leg.rule()).isEqualTo(PriceConversionPolicy.SelectionRule.EVENT_MINUTE);
            assertThat(leg.lagMinutes()).isZero();
            assertThat(leg.candle().startAt()).isEqualTo(MINUTE);
        });
    }

    @Test
    void crossRouteMultipliesBothClosesExactlyWhenTheUsdtRouteIsNotListed() {
        Market market = new Market().unlisted("ETHUSDT").trading("ETHUSDC", "ETH", "USDC")
                .trading("USDCUSDT", "USDC", "USDT")
                .candles("ETHUSDC", MINUTE, Map.of(MINUTE, "3000.1"))
                .candles("USDCUSDT", MINUTE, Map.of(MINUTE, "0.9998"));
        FlowOutcome outcome = settle(flow("ETH", "2"), market);
        assertThat(outcome.status()).isEqualTo(Status.CONVERTED_ESTIMATED);
        assertThat(outcome.routeId()).isEqualTo(PriceConversionPolicy.USDC_CROSS_ROUTE);
        assertThat(outcome.usdUnitPrice()).isEqualTo("2999.49998");
        assertThat(outcome.usdValue()).isEqualTo("5998.99996");
        assertThat(outcome.legs()).extracting(leg -> leg.leg().symbol()).containsExactly("ETHUSDC", "USDCUSDT");
    }

    @Test
    void usdcIsPricedThroughUsdcUsdtAndIsNotAssumedToBeOneDollar() {
        Market market = new Market().trading("USDCUSDT", "USDC", "USDT")
                .candles("USDCUSDT", MINUTE, Map.of(MINUTE, "0.9998"));
        FlowOutcome outcome = settle(flow("USDC", "100"), market);
        assertThat(outcome.status()).isEqualTo(Status.CONVERTED_ESTIMATED);
        assertThat(outcome.usdValue()).isEqualTo("99.98");
    }

    @Test
    void aNonTradingOrMismatchedInstrumentMovesToTheNextRouteAndNoRouteHoldsWithoutAReason() {
        assertThat(settle(flow("BTC", "1"), new Market().instrument("BTCUSDT", "BTC", "USDT", "Closed")
                .unlisted("BTCUSDC")).reason()).isEqualTo(Reason.PRICE_ROUTE_UNAVAILABLE);
        assertThat(settle(flow("BTC", "1"), new Market().instrument("BTCUSDT", "XBT", "USDT", "Trading")
                .unlisted("BTCUSDC")).reason()).isEqualTo(Reason.PRICE_ROUTE_UNAVAILABLE);
        assertThat(settle(flow("BTC", "1"), new Market().instrument("BTCUSDT", "BTC", "USDC", "Trading")
                .unlisted("BTCUSDC")).reason()).isEqualTo(Reason.PRICE_ROUTE_UNAVAILABLE);
        FlowOutcome none = settle(flow("ZZZ", "1"), new Market().unlisted("ZZZUSDT").unlisted("ZZZUSDC"));
        assertThat(none.status()).isEqualTo(Status.CONVERSION_REQUIRED);
        assertThat(none.retryable()).isFalse();
        assertThat(none.usdValue()).isNull();
    }

    @Test
    void aTradingSymbolWithoutHistoryIsRejectedAndTheCrossRouteIsTriedNext() {
        Market market = new Market().trading("NEWUSDT", "NEW", "USDT").candles("NEWUSDT", MINUTE, Map.of())
                .unlisted("NEWUSDC");
        FlowOutcome outcome = settle(flow("NEW", "3"), market);
        assertThat(outcome.status()).isEqualTo(Status.CONVERSION_REQUIRED);
        assertThat(outcome.reason()).isEqualTo(Reason.PRICE_CANDLE_MISSING);
        assertThat(outcome.retryable()).isFalse();

        Market fallback = new Market().trading("NEWUSDT", "NEW", "USDT").candles("NEWUSDT", MINUTE, Map.of())
                .trading("NEWUSDC", "NEW", "USDC").trading("USDCUSDT", "USDC", "USDT")
                .candles("NEWUSDC", MINUTE, Map.of(MINUTE, "2")).candles("USDCUSDT", MINUTE, Map.of(MINUTE, "1.0001"));
        FlowOutcome crossed = settle(flow("NEW", "3"), fallback);
        assertThat(crossed.routeId()).isEqualTo(PriceConversionPolicy.USDC_CROSS_ROUTE);
        assertThat(crossed.usdValue()).isEqualTo("6.0006");
    }

    @Test
    void transientProviderFailuresStopTheSearchInsteadOfFallingThrough() {
        // Final without the cross route's instruments: the policy did not ask for route two.
        FlowOutcome outcome = settle(flow("BTC", "1"), new Market().failedInstrument("BTCUSDT", false));
        assertThat(outcome.reason()).isEqualTo(Reason.PRICE_PROVIDER_UNAVAILABLE);
        assertThat(outcome.retryable()).isTrue();

        Market candles = new Market().trading("BTCUSDT", "BTC", "USDT").failedCandles("BTCUSDT", MINUTE, false);
        assertThat(settle(flow("BTC", "1"), candles).reason()).isEqualTo(Reason.PRICE_PROVIDER_UNAVAILABLE);

        Market time = new Market();
        time.reference = new Reference.Failed(false);
        assertThat(settle(flow("BTC", "1"), time).reason()).isEqualTo(Reason.PRICE_PROVIDER_UNAVAILABLE);
    }

    @Test
    void malformedAnswersNeedReview() {
        assertThat(settle(flow("BTC", "1"), new Market().failedInstrument("BTCUSDT", true)).reason())
                .isEqualTo(Reason.PRICE_RESPONSE_MALFORMED);
        assertThat(settle(flow("BTC", "1"), new Market().trading("BTCUSDT", "BTC", "USDT")
                .failedCandles("BTCUSDT", MINUTE, true)).status()).isEqualTo(Status.REVIEW_REQUIRED);
        Market time = new Market();
        time.reference = new Reference.Failed(true);
        assertThat(settle(flow("BTC", "1"), time).reason()).isEqualTo(Reason.PRICE_RESPONSE_MALFORMED);
    }

    // ------------------------------------------------------------------ candle boundaries

    @ParameterizedTest
    @CsvSource({"0,EVENT_MINUTE", "1,PRIOR_CLOSED_MINUTE", "3,PRIOR_CLOSED_MINUTE", "5,PRIOR_CLOSED_MINUTE"})
    void aMissingEventMinuteFallsBackToTheMostRecentOfTheFivePriorMinutes(int lag, String rule) {
        Map<Instant, String> closes = new HashMap<>();
        // An older candle inside the window shows that the most recent one wins.
        if (lag < 5) closes.put(MINUTE.minus(Duration.ofMinutes(5)), "11");
        closes.put(MINUTE.minus(Duration.ofMinutes(lag)), "10");
        FlowOutcome outcome = settle(flow("BTC", "1"), new Market().trading("BTCUSDT", "BTC", "USDT")
                .candles("BTCUSDT", MINUTE, closes));
        assertThat(outcome.legs().getFirst().lagMinutes()).isEqualTo(lag);
        assertThat(outcome.legs().getFirst().rule().name()).isEqualTo(rule);
        assertThat(outcome.usdValue()).isEqualTo("10");
    }

    @Test
    void aCandleSixMinutesBeforeOrAnyLaterCandleIsNeverUsed() {
        Map<Instant, String> closes = new HashMap<>();
        closes.put(MINUTE.minus(Duration.ofMinutes(6)), "9");
        closes.put(MINUTE.plus(Duration.ofMinutes(1)), "12");
        FlowOutcome outcome = settle(flow("BTC", "1"), new Market().trading("BTCUSDT", "BTC", "USDT")
                .candles("BTCUSDT", MINUTE, closes).unlisted("BTCUSDC"));
        assertThat(outcome.reason()).isEqualTo(Reason.PRICE_CANDLE_MISSING);
    }

    @Test
    void theEventMinuteMustBeClosedAgainstTheFixedReferenceWithoutFallback() {
        Market open = new Market().trading("BTCUSDT", "BTC", "USDT");
        open.reference = new Reference.Fixed(MINUTE.plusSeconds(59));
        FlowOutcome outcome = settle(flow("BTC", "1"), open);
        assertThat(outcome.reason()).isEqualTo(Reason.PRICE_CANDLE_NOT_CLOSED);
        assertThat(outcome.retryable()).isTrue();

        Market boundary = new Market().trading("BTCUSDT", "BTC", "USDT")
                .candles("BTCUSDT", MINUTE, Map.of(MINUTE, "10"));
        boundary.reference = new Reference.Fixed(MINUTE.plusSeconds(60));
        assertThat(settle(flow("BTC", "1"), boundary).status()).isEqualTo(Status.CONVERTED_ESTIMATED);
    }

    @Test
    void anEventExactlyOnAMinuteBoundaryBelongsToTheCandleStartingThere() {
        assertThat(PriceConversionPolicy.eventMinute(MINUTE)).isEqualTo(MINUTE);
        assertThat(PriceConversionPolicy.eventMinute(MINUTE.minusMillis(1))).isEqualTo(MINUTE.minus(Duration.ofMinutes(1)));
        assertThat(PriceConversionPolicy.eventMinute(MINUTE.plusSeconds(59).plusMillis(999))).isEqualTo(MINUTE);
    }

    @Test
    void contradictoryCandlesNeedReviewAndNonPositiveClosesAreNotPrices() {
        Market conflict = new Market().trading("BTCUSDT", "BTC", "USDT");
        conflict.candles.computeIfAbsent("BTCUSDT", ignored -> new HashMap<>()).put(MINUTE,
                new CandleLookup.Observed(Map.of(), Set.of(MINUTE)));
        FlowOutcome outcome = settle(flow("BTC", "1"), conflict);
        assertThat(outcome.status()).isEqualTo(Status.REVIEW_REQUIRED);
        assertThat(outcome.reason()).isEqualTo(Reason.PRICE_CANDLE_CONFLICT);

        for (String close : List.of("0", "-3")) {
            FlowOutcome zero = settle(flow("BTC", "1"), new Market().trading("BTCUSDT", "BTC", "USDT")
                    .candles("BTCUSDT", MINUTE, Map.of(MINUTE, close)).unlisted("BTCUSDC"));
            assertThat(zero.reason()).isEqualTo(Reason.PRICE_NON_POSITIVE);
            assertThat(zero.status()).isEqualTo(Status.CONVERSION_REQUIRED);
        }
        FlowOutcome precise = settle(flow("BTC", "1"), new Market().trading("BTCUSDT", "BTC", "USDT")
                .candles("BTCUSDT", MINUTE, Map.of(MINUTE, "1.0000000000000000000000001")));
        assertThat(precise.reason()).isEqualTo(Reason.CALCULATION_RANGE_EXCEEDED);
    }

    // ------------------------------------------------------------------ precision

    @ParameterizedTest(name = "{0} x {1} = {2}")
    @CsvSource({
        "-0.5,65000.25,-32500.125",
        "123456789012.345678,98765.4321098765432109876,12193263113702179.43360767503705234",
        "0.123456789012345678901233,1.5,0.18518518351851851835185",
        "0.123456789012345678901231,1.5,0.185185183518518518351846",
        "0.5,0.000000000000000000000005,0.000000000000000000000002",
        "0.5,0.000000000000000000000007,0.000000000000000000000004",
        "99999999999999999999.99,10000,999999999999999999999900",
        "-0.000123456789,65000.25,-8.02472214919725"
    })
    void usdValueIsTheExactProductRoundedOnceHalfEvenToCanonicalForm(String quantity, String close, String usd) {
        FlowOutcome outcome = settle(flow("BTC", quantity), new Market().trading("BTCUSDT", "BTC", "USDT")
                .candles("BTCUSDT", MINUTE, Map.of(MINUTE, close)));
        assertThat(outcome.status()).isEqualTo(Status.CONVERTED_ESTIMATED);
        assertThat(outcome.usdValue()).isEqualTo(usd);
        BigDecimal value = new BigDecimal(outcome.usdValue());
        assertThat(value.precision()).isLessThanOrEqualTo(34);
        assertThat(value.scale()).isLessThanOrEqualTo(24);
    }

    @ParameterizedTest
    @CsvSource({"100000000000000000000,10000", "0.5,0.000000000000000000000001", "-0.5,0.000000000000000000000001"})
    void valuesOutsideTheCanonicalRangeAreRejectedInsteadOfRoundedToZero(String quantity, String close) {
        FlowOutcome outcome = settle(flow("BTC", quantity), new Market().trading("BTCUSDT", "BTC", "USDT")
                .candles("BTCUSDT", MINUTE, Map.of(MINUTE, close)));
        assertThat(outcome.status()).isEqualTo(Status.REVIEW_REQUIRED);
        assertThat(outcome.reason()).isEqualTo(Reason.CALCULATION_RANGE_EXCEEDED);
        assertThat(outcome.usdValue()).isNull();
    }

    @Test
    void canonicalResultBoundaries() {
        assertThat(PriceConversionPolicy.canonicalResult(new BigDecimal("1E-24"))).isEqualTo("0.000000000000000000000001");
        assertThat(PriceConversionPolicy.canonicalResult(new BigDecimal("0.99E-24"))).isNull();
        assertThat(PriceConversionPolicy.canonicalResult(new BigDecimal("1E24"))).isNull();
        assertThat(PriceConversionPolicy.canonicalResult(new BigDecimal("-1E24"))).isNull();
        assertThat(PriceConversionPolicy.canonicalResult(new BigDecimal("999999999999999999999999.4"))).isEqualTo(
                "999999999999999999999999.4");
        assertThat(PriceConversionPolicy.canonicalResult(new BigDecimal("9.99999999999999999999999999999999995")))
                .isEqualTo("10");
        assertThat(PriceConversionPolicy.canonicalResult(new BigDecimal("12.500"))).isEqualTo("12.5");
    }

    // ------------------------------------------------------------------ bundle and determinism

    @Test
    void bundleStatusFollowsPriorityAndRetryIsOfferedOnlyForRetryableReasons() {
        FlowOutcome converted = settle(flow("USDT", "1"), new Market());
        FlowOutcome estimated = settle(flow("BTC", "1"), new Market().trading("BTCUSDT", "BTC", "USDT")
                .candles("BTCUSDT", MINUTE, Map.of(MINUTE, "2")));
        FlowOutcome missing = settle(flow("ZZZ", "1"), new Market().unlisted("ZZZUSDT").unlisted("ZZZUSDC"));
        FlowOutcome provider = settle(flow("BTC", "1"), new Market().failedInstrument("BTCUSDT", false));
        FlowOutcome review = settle(flow("BTC", "1"), new Market().failedInstrument("BTCUSDT", true));

        assertThat(PriceConversionPolicy.summarize(List.of())).satisfies(bundle -> {
            assertThat(bundle.status()).isEqualTo(Status.CONVERTED);
            assertThat(bundle.calculable()).isTrue();
        });
        assertThat(PriceConversionPolicy.summarize(List.of(converted)).status()).isEqualTo(Status.CONVERTED);
        var withEstimate = PriceConversionPolicy.summarize(List.of(converted, estimated));
        assertThat(withEstimate.status()).isEqualTo(Status.CONVERTED_ESTIMATED);
        assertThat(withEstimate.calculable()).isTrue();
        var held = PriceConversionPolicy.summarize(List.of(estimated, missing));
        assertThat(held.status()).isEqualTo(Status.CONVERSION_REQUIRED);
        assertThat(held.calculable()).isFalse();
        assertThat(held.retryable()).isFalse();
        assertThat(held.reason()).isEqualTo(Reason.PRICE_ROUTE_UNAVAILABLE);
        var retry = PriceConversionPolicy.summarize(List.of(missing, provider));
        assertThat(retry.retryable()).isTrue();
        assertThat(retry.reason()).isEqualTo(Reason.PRICE_PROVIDER_UNAVAILABLE);
        var reviewFirst = PriceConversionPolicy.summarize(List.of(converted, estimated, missing, provider, review));
        assertThat(reviewFirst.status()).isEqualTo(Status.REVIEW_REQUIRED);
        assertThat(reviewFirst.retryable()).isFalse();
        assertThat(reviewFirst.flowCount()).isEqualTo(5);
    }

    @Test
    void candidateOrderInputOrderAndRepetitionDoNotChangeTheSelectionOrTheFingerprint() {
        List<FlowInput> flows = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            flows.add(new FlowInput(new UUID(0, index), "source-" + index, index % 2 == 0 ? "BTC" : "USDT",
                    index % 3 == 0 ? "-0.25" : "1.5", EVENT.plusSeconds(index)));
        }
        Random random = new Random(26);
        String fingerprint = null;
        for (int round = 0; round < 20; round++) {
            Map<Instant, String> closes = new LinkedHashMap<>();
            List<Instant> starts = new ArrayList<>(PriceConversionPolicy.windowStarts(MINUTE));
            Collections.shuffle(starts, random);
            for (Instant start : starts) closes.put(start, start.equals(MINUTE) ? "65000.25" : "1");
            Market market = new Market().trading("BTCUSDT", "BTC", "USDT").candles("BTCUSDT", MINUTE, closes);
            List<FlowInput> order = new ArrayList<>(flows);
            Collections.shuffle(order, random);
            List<FlowOutcome> outcomes = order.stream().map(flow -> settle(flow, market)).toList();
            assertThat(outcomes).filteredOn(o -> o.input().assetCode().equals("BTC"))
                    .allSatisfy(o -> assertThat(o.legs().getFirst().candle().startAt()).isEqualTo(MINUTE));
            String current = PriceConversionPolicy.fingerprint("a".repeat(64), outcomes);
            if (fingerprint == null) fingerprint = current;
            assertThat(current).isEqualTo(fingerprint);
        }
        assertThat(PriceConversionPolicy.fingerprint("b".repeat(64), List.of())).isNotEqualTo(
                PriceConversionPolicy.fingerprint("a".repeat(64), List.of()));
    }

    @Test
    void aNewCandleRevisionChangesTheFingerprint() {
        Market first = new Market().trading("BTCUSDT", "BTC", "USDT").candles("BTCUSDT", MINUTE, Map.of(MINUTE, "10"));
        Market revised = new Market().trading("BTCUSDT", "BTC", "USDT");
        revised.candles.computeIfAbsent("BTCUSDT", ignored -> new HashMap<>()).put(MINUTE, new CandleLookup.Observed(
                Map.of(MINUTE, new CandleFact(UUID.randomUUID(), MINUTE, 2, "10", "f".repeat(64))), Set.of()));
        String before = PriceConversionPolicy.fingerprint("a".repeat(64), List.of(settle(flow("BTC", "1"), first)));
        String after = PriceConversionPolicy.fingerprint("a".repeat(64), List.of(settle(flow("BTC", "1"), revised)));
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void requestWindowsMergeNearbyMinutesAndNeverExceedAThousandCandles() {
        Instant base = MINUTE;
        var single = PriceConversionPolicy.requestWindows(List.of(base, base, base.plus(Duration.ofMinutes(2))));
        assertThat(single).singleElement().satisfies(window -> {
            assertThat(window.start()).isEqualTo(base.minus(Duration.ofMinutes(5)));
            assertThat(window.end()).isEqualTo(base.plus(Duration.ofMinutes(2)));
            assertThat(window.candleCount()).isEqualTo(8);
        });
        List<Instant> spread = new ArrayList<>();
        for (int minute = 0; minute < 3000; minute += 7) spread.add(base.plus(Duration.ofMinutes(minute)));
        var windows = PriceConversionPolicy.requestWindows(spread);
        assertThat(windows).allSatisfy(window -> assertThat(window.candleCount()).isBetween(1L, 1000L));
        // [-5m, 994m], [996m, 1995m], [1997m, 2996m]: three full windows.
        assertThat(windows).hasSize(3);
        assertThat(windows.stream().mapToInt(window -> window.eventMinutes().size()).sum()).isEqualTo(spread.size());
        var far = PriceConversionPolicy.requestWindows(List.of(base, base.plus(Duration.ofDays(3))));
        assertThat(far).hasSize(2);
    }

    // ------------------------------------------------------------------ fixture

    private static FlowInput flow(String asset, String quantity) {
        return new FlowInput(UUID.nameUUIDFromBytes((asset + quantity).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "source-" + asset, asset, quantity, EVENT);
    }

    /** Evaluates until final, failing the test if the policy asks for facts the fixture lacks. */
    private static FlowOutcome settle(FlowInput flow, Market market) {
        Evaluation evaluation = PriceConversionPolicy.evaluate(flow, market.view());
        assertThat(evaluation).as("the fixture must hold every fact the policy asks for")
                .isInstanceOf(Evaluation.Final.class);
        return ((Evaluation.Final) evaluation).outcome();
    }

    private static final class Market {
        Reference reference = new Reference.Fixed(REFERENCE);
        final Map<String, InstrumentLookup> instruments = new HashMap<>();
        final Map<String, Map<Instant, CandleLookup>> candles = new HashMap<>();

        MarketView view() {
            return new MarketView(reference, instruments, candles);
        }

        Market trading(String symbol, String base, String quote) {
            return instrument(symbol, base, quote, "Trading");
        }

        Market instrument(String symbol, String base, String quote, String status) {
            instruments.put(symbol, new InstrumentLookup.Observed(new InstrumentFact(UUID.randomUUID(), true, symbol,
                    base, quote, status, sha(symbol + base + quote + status))));
            return this;
        }

        Market unlisted(String symbol) {
            instruments.put(symbol, new InstrumentLookup.Observed(new InstrumentFact(UUID.randomUUID(), false, null,
                    null, null, null, sha(symbol))));
            return this;
        }

        Market failedInstrument(String symbol, boolean malformed) {
            instruments.put(symbol, new InstrumentLookup.Failed(malformed));
            return this;
        }

        Market candles(String symbol, Instant eventMinute, Map<Instant, String> closes) {
            Map<Instant, CandleFact> facts = new HashMap<>();
            closes.forEach((start, close) -> facts.put(start, new CandleFact(
                    UUID.nameUUIDFromBytes((symbol + start + close).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    start, 1, close, sha(symbol + start + close))));
            candles.computeIfAbsent(symbol, ignored -> new HashMap<>())
                    .put(eventMinute, new CandleLookup.Observed(facts, Set.of()));
            return this;
        }

        Market failedCandles(String symbol, Instant eventMinute, boolean malformed) {
            candles.computeIfAbsent(symbol, ignored -> new HashMap<>()).put(eventMinute, new CandleLookup.Failed(malformed));
            return this;
        }

        private static String sha(String value) {
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
