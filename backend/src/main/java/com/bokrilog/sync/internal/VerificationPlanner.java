package com.bokrilog.sync.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * The range plan of a precision verification and of a long-gap continuation (Story 2.12, ERD-1.0.0 §2.2), as pure
 * functions of instants.
 *
 * <p>No database, no clock, no Spring. Every range is a closed product range {@code [start, end]} with
 * {@code start < end}, compared to the millisecond; two ranges that touch or overlap are one when merged.
 *
 * <ul>
 *   <li>The <b>evidence</b> is what the official lineage already proved: the union of
 *       {@code [COALESCE(window_start, t0), tc]} over the current candidate and every base candidate behind it.
 *   <li>The <b>targets</b>: {@code UNCONFIRMED} {@code [max(t0, verified - 7 days), verified]} always;
 *       {@code GAP} — the part of {@code (t0, verified]} the evidence does not cover (a guard: the lineage covers it
 *       by construction); {@code ANOMALY} — 24 hours either side of each unexplained stored event, clipped to
 *       {@code (t0, verified]}, merged and bridged to at most {@link #MAX_ANOMALY_RANGES}; {@code EXPLICIT} — an
 *       operator's investigation ranges; {@code NEW}
 *       {@code (verified, tc]} once the worker fixed {@code tc}; and for a continuation only {@code UNCONFIRMED},
 *       {@code NEW} and its {@code FINAL_SWEEP} {@code [max(t0, min(tc, tcFinal - 7 days)), tcFinal]}.
 *   <li>The <b>reused</b> ranges are the evidence minus the targets: read never again, not even for a plan's end.
 *   <li>The <b>windows</b> split each merged target into contiguous windows of at most seven days — the same split
 *       as a sync's, so a verification reads the provider exactly as a sync would read the same range. The final
 *       sweep is split on its own: it re-reads what the first phase read, on purpose.
 *   <li>A target that begins before {@code tc - 730 days} is out of Bybit's history: nothing is promised there.
 * </ul>
 */
final class VerificationPlanner {
    /** The last unconfirmed stretch before a verification: the extended overlap of ERD-1.0.0 §2.2. */
    static final Duration UNCONFIRMED_SPAN = Duration.ofDays(7);
    /** Either side of an unexplained stored event: the smallest span a single window still reads whole. */
    static final Duration ANOMALY_SPAN = Duration.ofHours(24);
    /** The Transaction Log's documented reach ("at most two years"), taken conservatively. */
    static final Duration HISTORY_LIMIT = Duration.ofDays(730);
    /** The most explicit investigation ranges one attempt takes. */
    static final int MAX_EXPLICIT = 10;
    /**
     * The most anomaly ranges one attempt stores. The ranges are merged first; beyond this many, the closest ones are
     * bridged (the smallest gaps between them are read too), so every unexplained event still lies inside a target.
     */
    static final int MAX_ANOMALY_RANGES = 50;
    /** The provider's window ceiling. */
    static final Duration MAX_WINDOW = Duration.ofDays(7);

    enum Kind { UNCONFIRMED, GAP, ANOMALY, EXPLICIT, NEW, FINAL_SWEEP, REUSED }

    /** A closed range with {@code start < end}. */
    record Interval(Instant start, Instant end) {
        Interval {
            if (!end.isAfter(start)) throw new IllegalArgumentException("an empty range");
        }

        boolean contains(Instant at) {
            return !at.isBefore(this.start) && !at.isAfter(this.end);
        }
    }

    /** A planned range and why it is there. */
    record Range(Kind kind, Instant start, Instant end) {
        Range {
            if (!end.isAfter(start)) throw new IllegalArgumentException("an empty range");
        }

        Interval interval() {
            return new Interval(this.start, this.end);
        }
    }

    /** The targets up to the base verification and the evidence they leave to reuse. */
    record Plan(List<Range> targets, List<Range> reused) {}

    private VerificationPlanner() {
    }

    /**
     * A precision verification's plan up to {@code verified}: the unconfirmed range, any gap in the evidence, the
     * anomalies and the explicit ranges; the reused evidence is what they leave.
     *
     * @param anomalies the times of the unexplained stored events; those outside {@code (t0, verified]} are covered
     *     by the {@code NEW} range or not at all
     * @param explicit the investigation ranges, already validated ({@link #explicitValid})
     */
    static Plan precision(Instant t0, Instant verified, List<Interval> evidence, List<Instant> anomalies,
            List<Interval> explicit) {
        List<Range> targets = new ArrayList<>();
        targets.add(unconfirmed(t0, verified));
        List<Interval> covered = merge(clip(evidence, t0, verified));
        for (Interval gap : subtract(List.of(new Interval(t0, verified)), covered))
            targets.add(new Range(Kind.GAP, gap.start(), gap.end()));
        List<Interval> around = new ArrayList<>();
        for (Instant at : new TreeSet<>(anomalies)) {
            if (!at.isAfter(t0) || at.isAfter(verified)) continue;
            Instant start = max(t0, at.minus(ANOMALY_SPAN));
            Instant end = min(verified, at.plus(ANOMALY_SPAN));
            if (end.isAfter(start)) around.add(new Interval(start, end));
        }
        for (Interval range : bridged(merge(around), MAX_ANOMALY_RANGES))
            targets.add(new Range(Kind.ANOMALY, range.start(), range.end()));
        for (Interval range : explicit) targets.add(new Range(Kind.EXPLICIT, range.start(), range.end()));
        return new Plan(sorted(targets), reused(covered, targets));
    }

    /** A continuation's plan up to {@code verified}: the unconfirmed range only ({@code NEW} follows it at tc). */
    static Plan catchUp(Instant t0, Instant verified, List<Interval> evidence) {
        List<Range> targets = List.of(unconfirmed(t0, verified));
        return new Plan(targets, reused(merge(clip(evidence, t0, verified)), targets));
    }

    /** {@code [max(t0, verified - 7 days), verified]}. */
    static Range unconfirmed(Instant t0, Instant verified) {
        return new Range(Kind.UNCONFIRMED, max(t0, verified.minus(UNCONFIRMED_SPAN)), verified);
    }

    /** {@code (verified, tc]}, closed for the provider's query like every window. */
    static Range newRange(Instant verified, Instant tc) {
        return new Range(Kind.NEW, verified, tc);
    }

    /** {@code [max(t0, min(tc, tcFinal - 7 days)), tcFinal]}: the continuation's last recent sweep. */
    static Range finalSweep(Instant t0, Instant tc, Instant tcFinal) {
        return new Range(Kind.FINAL_SWEEP, max(t0, min(tc, tcFinal.minus(UNCONFIRMED_SPAN))), tcFinal);
    }

    /** 1 to 10 ranges, each inside {@code [t0, verified]}. */
    static boolean explicitValid(Instant t0, Instant verified, List<Interval> explicit) {
        if (explicit.isEmpty() || explicit.size() > MAX_EXPLICIT) return false;
        for (Interval range : explicit)
            if (range.start().isBefore(t0) || range.end().isAfter(verified)) return false;
        return true;
    }

    /** Whether any target begins before {@code tc - 730 days}. */
    static boolean outOfRange(List<Range> targets, Instant tc) {
        Instant reach = tc.minus(HISTORY_LIMIT);
        return targets.stream().filter(range -> range.kind() != Kind.REUSED)
                .anyMatch(range -> range.start().isBefore(reach));
    }

    /** The earliest target start: the attempt's window start. */
    static Instant windowStart(List<Range> targets) {
        return targets.stream().filter(range -> range.kind() != Kind.REUSED).map(Range::start)
                .min(Comparator.naturalOrder()).orElseThrow();
    }

    /** The targets merged into disjoint ranges (the final sweep with them). */
    static List<Interval> merged(List<Range> targets) {
        return merge(targets.stream().filter(range -> range.kind() != Kind.REUSED).map(Range::interval).toList());
    }

    /** Whether {@code at} lies inside a target. */
    static boolean covers(List<Range> targets, Instant at) {
        return merged(targets).stream().anyMatch(range -> range.contains(at));
    }

    /**
     * The windows the collection reads, in order: every merged target other than the final sweep, split into
     * contiguous windows of at most seven days, then the final sweep split the same way.
     */
    static List<Interval> windows(List<Range> targets) {
        List<Interval> windows = new ArrayList<>();
        for (Interval range : merge(targets.stream()
                .filter(target -> target.kind() != Kind.REUSED && target.kind() != Kind.FINAL_SWEEP)
                .map(Range::interval).toList()))
            windows.addAll(split(range));
        for (Interval range : merge(targets.stream().filter(target -> target.kind() == Kind.FINAL_SWEEP)
                .map(Range::interval).toList()))
            windows.addAll(split(range));
        return windows;
    }

    /** {@code [start, end]} as contiguous windows of at most seven days. */
    static List<Interval> split(Interval range) {
        List<Interval> windows = new ArrayList<>();
        Instant start = range.start();
        while (start.isBefore(range.end())) {
            Instant end = min(start.plus(MAX_WINDOW), range.end());
            windows.add(new Interval(start, end));
            start = end;
        }
        return windows;
    }

    /** Closed ranges merged where they touch or overlap, in order. */
    static List<Interval> merge(List<Interval> ranges) {
        List<Interval> ordered = new ArrayList<>(ranges);
        ordered.sort(Comparator.comparing(Interval::start).thenComparing(Interval::end));
        List<Interval> merged = new ArrayList<>();
        for (Interval range : ordered) {
            if (!merged.isEmpty() && !range.start().isAfter(merged.getLast().end())) {
                Interval last = merged.removeLast();
                merged.add(new Interval(last.start(), max(last.end(), range.end())));
            } else {
                merged.add(range);
            }
        }
        return merged;
    }

    /**
     * Disjoint ordered ranges reduced to at most {@code limit} by bridging the smallest gaps between neighbours: the
     * result covers everything the input did, and only the bridged gaps besides.
     */
    static List<Interval> bridged(List<Interval> ordered, int limit) {
        if (ordered.size() <= limit) return ordered;
        // Rank the gaps by length (then by position, for one answer), and bridge the shortest size - limit of them.
        List<Integer> gaps = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) gaps.add(index);
        gaps.sort(Comparator.<Integer, Duration>comparing(index -> Duration.between(ordered.get(index - 1).end(),
                ordered.get(index).start())).thenComparing(Comparator.naturalOrder()));
        java.util.Set<Integer> bridge = new java.util.HashSet<>(gaps.subList(0, ordered.size() - limit));
        List<Interval> result = new ArrayList<>();
        Interval current = ordered.getFirst();
        for (int index = 1; index < ordered.size(); index++) {
            Interval next = ordered.get(index);
            if (bridge.contains(index)) {
                current = new Interval(current.start(), next.end());
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return result;
    }

    /** {@code from} minus {@code minus}, both closed; the pieces keep the shared end points. */
    static List<Interval> subtract(List<Interval> from, List<Interval> minus) {
        List<Interval> left = merge(from);
        for (Interval cut : merge(minus)) {
            List<Interval> next = new ArrayList<>();
            for (Interval range : left) {
                if (!cut.end().isAfter(range.start()) || !cut.start().isBefore(range.end())) {
                    next.add(range);
                    continue;
                }
                if (range.start().isBefore(cut.start())) next.add(new Interval(range.start(), cut.start()));
                if (cut.end().isBefore(range.end())) next.add(new Interval(cut.end(), range.end()));
            }
            left = next;
        }
        return left;
    }

    /** The parts of the ranges inside {@code [from, to]}. */
    static List<Interval> clip(List<Interval> ranges, Instant from, Instant to) {
        List<Interval> clipped = new ArrayList<>();
        for (Interval range : ranges) {
            Instant start = max(from, range.start());
            Instant end = min(to, range.end());
            if (end.isAfter(start)) clipped.add(new Interval(start, end));
        }
        return clipped;
    }

    private static List<Range> reused(List<Interval> covered, List<Range> targets) {
        return subtract(covered, targets.stream().map(Range::interval).toList()).stream()
                .map(range -> new Range(Kind.REUSED, range.start(), range.end())).toList();
    }

    private static List<Range> sorted(List<Range> ranges) {
        List<Range> ordered = new ArrayList<>(ranges);
        ordered.sort(Comparator.comparing(Range::start).thenComparing(Range::end).thenComparing(Range::kind));
        return List.copyOf(ordered);
    }

    private static Instant max(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }
}
