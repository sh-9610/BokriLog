package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.sync.internal.VerificationPlanner.Interval;
import com.bokrilog.sync.internal.VerificationPlanner.Kind;
import com.bokrilog.sync.internal.VerificationPlanner.Range;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The range plan of Story 2.12 to the millisecond: the unconfirmed week, gaps in the lineage's evidence, the
 * anomalies' 24 hours clipped to {@code (t0, verified]}, explicit ranges, the new range and the continuation's final
 * sweep, the reused evidence left, the merge, the seven-day windows and the 730-day reach.
 */
class VerificationPlannerTest {
    private static final Instant T0 = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant VERIFIED = Instant.parse("2027-02-01T00:00:00Z");
    private static final Instant TC = VERIFIED.plus(Duration.ofHours(6));

    private static Interval interval(Instant start, Instant end) {
        return new Interval(start, end);
    }

    @Test
    void withoutAnomaliesThePlanIsTheUnconfirmedWeekAndEverythingBeforeItIsReused() {
        var plan = VerificationPlanner.precision(T0, VERIFIED, List.of(interval(T0, VERIFIED)), List.of(), List.of());
        assertThat(plan.targets()).containsExactly(new Range(Kind.UNCONFIRMED, VERIFIED.minus(Duration.ofDays(7)),
                VERIFIED));
        assertThat(plan.reused()).containsExactly(new Range(Kind.REUSED, T0, VERIFIED.minus(Duration.ofDays(7))));
        assertThat(VerificationPlanner.windowStart(plan.targets())).isEqualTo(VERIFIED.minus(Duration.ofDays(7)));
    }

    /** A plan younger than a week has nothing to reuse: the unconfirmed range starts at t0. */
    @ParameterizedTest(name = "t0 {0} ms after verified - 7 days")
    @CsvSource({"-1", "0", "1"})
    void theUnconfirmedRangeIsClampedToT0(long offset) {
        Instant t0 = VERIFIED.minus(Duration.ofDays(7)).plusMillis(offset);
        var plan = VerificationPlanner.precision(t0, VERIFIED, List.of(interval(t0, VERIFIED)), List.of(), List.of());
        Instant expected = offset > 0 ? t0 : VERIFIED.minus(Duration.ofDays(7));
        assertThat(plan.targets()).containsExactly(new Range(Kind.UNCONFIRMED, expected, VERIFIED));
        assertThat(plan.reused()).hasSize(offset < 0 ? 1 : 0);
    }

    /** The lineage's evidence is the union of its candidates' ranges; the part it does not cover is a gap. */
    @Test
    void aGapInTheEvidenceIsATarget() {
        Instant hole = T0.plus(Duration.ofDays(10));
        var evidence = List.of(interval(T0, hole), interval(hole.plus(Duration.ofDays(2)), VERIFIED));
        var plan = VerificationPlanner.precision(T0, VERIFIED, evidence, List.of(), List.of());
        assertThat(plan.targets()).contains(new Range(Kind.GAP, hole, hole.plus(Duration.ofDays(2))));
        // Touching and overlapping evidence is one range: no gap.
        var touching = List.of(interval(T0, hole), interval(hole, VERIFIED), interval(T0, T0.plusSeconds(1)));
        assertThat(VerificationPlanner.precision(T0, VERIFIED, touching, List.of(), List.of()).targets())
                .extracting(Range::kind).containsExactly(Kind.UNCONFIRMED);
    }

    /** 24 hours either side of each unexplained stored event, clipped to (t0, verified]; outside it, nothing. */
    @Test
    void anAnomalyIsTwentyFourHoursEitherSideClippedToT0AndTheVerification() {
        Instant early = T0.plus(Duration.ofHours(3));
        Instant middle = T0.plus(Duration.ofDays(10));
        Instant late = VERIFIED.minus(Duration.ofHours(2));
        var plan = VerificationPlanner.precision(T0, VERIFIED, List.of(interval(T0, VERIFIED)),
                List.of(middle, early, late, T0, VERIFIED.plusMillis(1), middle), List.of());
        assertThat(plan.targets()).containsExactly(
                new Range(Kind.ANOMALY, T0, early.plus(Duration.ofHours(24))),
                new Range(Kind.ANOMALY, middle.minus(Duration.ofHours(24)), middle.plus(Duration.ofHours(24))),
                new Range(Kind.UNCONFIRMED, VERIFIED.minus(Duration.ofDays(7)), VERIFIED),
                new Range(Kind.ANOMALY, late.minus(Duration.ofHours(24)), VERIFIED));
        // The reused evidence is what every target leaves.
        assertThat(plan.reused()).containsExactly(
                new Range(Kind.REUSED, early.plus(Duration.ofHours(24)), middle.minus(Duration.ofHours(24))),
                new Range(Kind.REUSED, middle.plus(Duration.ofHours(24)), VERIFIED.minus(Duration.ofDays(7))));
        assertThat(VerificationPlanner.windowStart(plan.targets())).isEqualTo(T0);
    }

    /** Overlapping anomaly ranges are one target: two events an hour apart read one 50-hour range (patch 16). */
    @Test
    void overlappingAnomaliesAreMergedIntoOneTarget() {
        Instant first = T0.plus(Duration.ofDays(5));
        Instant second = first.plus(Duration.ofHours(1));
        var plan = VerificationPlanner.precision(T0, VERIFIED, List.of(interval(T0, VERIFIED)), List.of(first, second),
                List.of());
        assertThat(plan.targets()).filteredOn(range -> range.kind() == Kind.ANOMALY).containsExactly(
                new Range(Kind.ANOMALY, first.minus(Duration.ofHours(24)), second.plus(Duration.ofHours(24))));
    }

    /**
     * Beyond fifty merged anomaly ranges, the shortest gaps between neighbours are bridged: at most fifty are stored,
     * every event still lies inside one, and only the bridged gaps are read besides (patch 16).
     */
    @Test
    void manyAnomaliesAreBridgedToFiftyRangesThatStillCoverEveryEvent() {
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        Instant verified = Instant.parse("2027-01-01T00:00:00Z");
        List<Instant> events = new java.util.ArrayList<>();
        // 60 events three days apart, and the 10 last of them a week apart: the shortest gaps are the three-day ones.
        for (int index = 0; index < 50; index++) events.add(t0.plus(Duration.ofDays(10 + 3L * index)));
        for (int index = 0; index < 10; index++) events.add(t0.plus(Duration.ofDays(400 + 7L * index)));
        var plan = VerificationPlanner.precision(t0, verified, List.of(interval(t0, verified)), events, List.of());
        var anomalies = plan.targets().stream().filter(range -> range.kind() == Kind.ANOMALY).toList();
        assertThat(anomalies).hasSize(VerificationPlanner.MAX_ANOMALY_RANGES);
        assertThat(events).allSatisfy(event -> assertThat(anomalies).anySatisfy(range ->
                assertThat(range.interval().contains(event)).isTrue()));
        // The ten weekly events keep their own ranges: only three-day gaps were bridged.
        assertThat(anomalies.subList(anomalies.size() - 10, anomalies.size())).allSatisfy(range ->
                assertThat(Duration.between(range.start(), range.end())).isEqualTo(Duration.ofHours(48)));
        assertThat(VerificationPlanner.bridged(List.of(interval(t0, t0.plusSeconds(1))), 50)).hasSize(1);
    }

    @Test
    void explicitRangesAreOneToTenInsideTheVerifiedRange() {
        var inside = interval(T0.plusSeconds(1), VERIFIED);
        assertThat(VerificationPlanner.explicitValid(T0, VERIFIED, List.of(inside))).isTrue();
        assertThat(VerificationPlanner.explicitValid(T0, VERIFIED, List.of(interval(T0, VERIFIED)))).isTrue();
        assertThat(VerificationPlanner.explicitValid(T0, VERIFIED, List.of())).isFalse();
        assertThat(VerificationPlanner.explicitValid(T0, VERIFIED, List.of(interval(T0.minusMillis(1), VERIFIED))))
                .isFalse();
        assertThat(VerificationPlanner.explicitValid(T0, VERIFIED, List.of(interval(T0, VERIFIED.plusMillis(1)))))
                .isFalse();
        assertThat(VerificationPlanner.explicitValid(T0, VERIFIED, java.util.Collections.nCopies(10, inside))).isTrue();
        assertThat(VerificationPlanner.explicitValid(T0, VERIFIED, java.util.Collections.nCopies(11, inside))).isFalse();
        var plan = VerificationPlanner.precision(T0, VERIFIED, List.of(interval(T0, VERIFIED)), List.of(),
                List.of(interval(T0.plus(Duration.ofDays(3)), T0.plus(Duration.ofDays(4)))));
        assertThat(plan.targets()).contains(new Range(Kind.EXPLICIT, T0.plus(Duration.ofDays(3)),
                T0.plus(Duration.ofDays(4))));
    }

    /** The windows: merged targets split into contiguous windows of at most seven days, in order. */
    @Test
    void theWindowsSplitTheMergedTargetsIntoSevenDayWindows() {
        var targets = List.of(new Range(Kind.UNCONFIRMED, VERIFIED.minus(Duration.ofDays(7)), VERIFIED),
                VerificationPlanner.newRange(VERIFIED, TC),
                new Range(Kind.ANOMALY, T0, T0.plus(Duration.ofDays(2))),
                new Range(Kind.EXPLICIT, T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(9))));
        assertThat(VerificationPlanner.windows(targets)).containsExactly(
                interval(T0, T0.plus(Duration.ofDays(7))), interval(T0.plus(Duration.ofDays(7)), T0.plus(Duration.ofDays(9))),
                interval(VERIFIED.minus(Duration.ofDays(7)), VERIFIED), interval(VERIFIED, TC));
        // Exactly seven days is one window; a millisecond more is two.
        assertThat(VerificationPlanner.split(interval(T0, T0.plus(Duration.ofDays(7))))).hasSize(1);
        assertThat(VerificationPlanner.split(interval(T0, T0.plus(Duration.ofDays(7)).plusMillis(1))))
                .containsExactly(interval(T0, T0.plus(Duration.ofDays(7))),
                        interval(T0.plus(Duration.ofDays(7)), T0.plus(Duration.ofDays(7)).plusMillis(1)));
        assertThat(VerificationPlanner.covers(targets, VERIFIED)).isTrue();
        assertThat(VerificationPlanner.covers(targets, T0.plus(Duration.ofDays(9)).plusMillis(1))).isFalse();
        assertThat(VerificationPlanner.covers(targets, TC)).isTrue();
        assertThat(VerificationPlanner.covers(targets, TC.plusMillis(1))).isFalse();
    }

    /** The continuation: the unconfirmed week, then its final sweep of the last seven days to tcFinal, split apart. */
    @Test
    void theContinuationSweepsTheLastSevenDaysAgainOnItsOwn() {
        Instant verified = T0.plus(Duration.ofDays(40));
        Instant tc = verified.plus(Duration.ofDays(31));
        Instant tcFinal = tc.plus(Duration.ofSeconds(90));
        var plan = VerificationPlanner.catchUp(T0, verified, List.of(interval(T0, verified)));
        assertThat(plan.targets()).containsExactly(new Range(Kind.UNCONFIRMED, verified.minus(Duration.ofDays(7)),
                verified));
        assertThat(plan.reused()).containsExactly(new Range(Kind.REUSED, T0, verified.minus(Duration.ofDays(7))));
        var sweep = VerificationPlanner.finalSweep(T0, tc, tcFinal);
        assertThat(sweep).isEqualTo(new Range(Kind.FINAL_SWEEP, tcFinal.minus(Duration.ofDays(7)), tcFinal));
        var targets = List.of(plan.targets().getFirst(), VerificationPlanner.newRange(verified, tc), sweep);
        var windows = VerificationPlanner.windows(targets);
        assertThat(windows.getLast()).isEqualTo(interval(tcFinal.minus(Duration.ofDays(7)), tcFinal));
        assertThat(windows.get(windows.size() - 2).end()).isEqualTo(tc);
        // A short gap: the sweep never starts before the first phase's tc nor before t0.
        assertThat(VerificationPlanner.finalSweep(T0, T0.plusSeconds(10), T0.plusSeconds(20)))
                .isEqualTo(new Range(Kind.FINAL_SWEEP, T0, T0.plusSeconds(20)));
        assertThat(VerificationPlanner.finalSweep(T0, tcFinal.minus(Duration.ofDays(8)), tcFinal).start())
                .isEqualTo(tcFinal.minus(Duration.ofDays(8)));
    }

    /** A target beginning before tc - 730 days is out of the provider's reach; exactly 730 days is not. */
    @ParameterizedTest(name = "{0} ms before tc - 730 days → {1}")
    @CsvSource({"1,true", "0,false", "-1,false"})
    void theHistoryReachIsSevenHundredThirtyDaysToTheMillisecond(long before, boolean out) {
        Instant start = TC.minus(Duration.ofDays(730)).minusMillis(before);
        var targets = List.of(new Range(Kind.EXPLICIT, start, start.plusSeconds(1)),
                new Range(Kind.REUSED, TC.minus(Duration.ofDays(900)), TC.minus(Duration.ofDays(899))));
        assertThat(VerificationPlanner.outOfRange(targets, TC)).isEqualTo(out);
    }

    @Test
    void mergeAndSubtractKeepClosedEndPoints() {
        var merged = VerificationPlanner.merge(List.of(interval(T0.plusSeconds(10), T0.plusSeconds(20)),
                interval(T0, T0.plusSeconds(10)), interval(T0.plusSeconds(30), T0.plusSeconds(40))));
        assertThat(merged).containsExactly(interval(T0, T0.plusSeconds(20)), interval(T0.plusSeconds(30),
                T0.plusSeconds(40)));
        assertThat(VerificationPlanner.subtract(List.of(interval(T0, T0.plusSeconds(40))),
                List.of(interval(T0.plusSeconds(10), T0.plusSeconds(20)))))
                .containsExactly(interval(T0, T0.plusSeconds(10)), interval(T0.plusSeconds(20), T0.plusSeconds(40)));
        assertThat(VerificationPlanner.subtract(List.of(interval(T0, T0.plusSeconds(10))),
                List.of(interval(T0, T0.plusSeconds(10))))).isEmpty();
        assertThat(VerificationPlanner.clip(List.of(interval(T0.minusSeconds(5), T0.plusSeconds(5))), T0,
                T0.plusSeconds(3))).containsExactly(interval(T0, T0.plusSeconds(3)));
        assertThatThrownBy(() -> interval(T0, T0)).isInstanceOf(IllegalArgumentException.class);
    }
}
