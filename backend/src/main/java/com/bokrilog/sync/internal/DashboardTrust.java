package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncJobApi.DashboardMetric;
import com.bokrilog.sync.SyncJobApi.NatureAxis;
import com.bokrilog.sync.SyncJobApi.PlanEndAvailability;
import com.bokrilog.sync.SyncJobApi.TrustAxes;
import com.bokrilog.sync.SyncJobApi.TrustAxis;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The three trust axes of the dashboard, judged on the server (Story 2.8 AC-5, SYNC-UX-1.0).
 *
 * <p>A pure function: no database, no clock, no Spring. The caller reads the official bundle and the
 * freshness facts (judged against the database clock) in one transaction and hands them here. The
 * axes stay independent — there is no combined score — and each carries its state, a short cause, the
 * last normal time of the bundle it vouches for, the metrics its state reaches and the next action.
 *
 * <p>The last normal time needs no new storage: the bundle the pointer cites is the last normal bundle.
 * Freshness vouches for the measurement ({@code measuredAt}), input reliability for the cash-flow
 * verification ({@code cashflowVerifiedThrough}) and the calculation nature for the basis
 * ({@code basisAt}).
 *
 * <p>The next actions are a closed set: {@link #CREATE_PLAN}, {@link #WAIT_FOR_RESULT}, {@link #WAIT_FOR_SYNC},
 * {@link #SYNC}, {@link #VERIFY}, {@link #CHOOSE_CONTINUATION} and {@link #NONE}. Story 2.11 added the sync
 * control: a stale result is refreshed by a new sync ({@code SYNC}), unless the gap is long
 * ({@code CHOOSE_CONTINUATION}) or no sync can run ({@code NONE}). The latest sync attempt is one of the facts: a
 * failed or review-required ending keeps the result "not fresh" whatever its times, and a review-required ending
 * makes the input reliability "review required" for all six metrics.
 *
 * <p>Story 2.12: a sync that ended in review is not asked to sync again — syncing cannot resolve it — but to run a
 * precision verification ({@code VERIFY}) when one can run, to choose the continuation in a long gap, and otherwise
 * nothing. A verification or continuation of the current base that itself ended in a
 * persistent review ({@link PlanSyncRepository#verificationReview}) wins over the sync's review on both axes, and over
 * the long gap on the freshness axis, for as long as that base is official: the input reliability is
 * {@code VERIFICATION_REVIEW_REQUIRED} (or {@code UNUSABLE} / {@code HISTORY_OUT_OF_RANGE} when the history is out of
 * the provider's reach), with no next action — asking again, for either a verification or a continuation, would not
 * change it.
 *
 * <p>Story 2.13: a plan that ended early keeps its last official bundle as a read-only record. Freshness means nothing
 * there — the plan is not synced any more — so the axis folds into one state, {@code ENDED} ({@code PLAN_ENDED_EARLY},
 * all six metrics, no next action), and the end facts take its place on the screen. The input reliability and the
 * calculation nature are judged from that calculation version ({@link #judgeEnded}) and one fact about it: a persistent
 * verification review of that very version (Story 2.12) still holds its inputs, so the record says so as the ready
 * dashboard did — with no next action. A sync's own review concerns later inputs, not the version, and stays out. The
 * screen-level next action of the record is {@link #PREPARE_NEXT_PLAN}. Whether a ready plan can be ended now is
 * {@link #planEnd}, judged by the end's own order.
 */
final class DashboardTrust {
    static final String CREATE_PLAN = "CREATE_PLAN";
    static final String WAIT_FOR_RESULT = "WAIT_FOR_RESULT";
    static final String WAIT_FOR_SYNC = "WAIT_FOR_SYNC";
    static final String SYNC = "SYNC";
    static final String VERIFY = "VERIFY";
    static final String CHOOSE_CONTINUATION = "CHOOSE_CONTINUATION";
    static final String NONE = "NONE";
    /** The ended plan's screen-level next action (Story 2.13): the following plan is prepared apart (Story 2.15). */
    static final String PREPARE_NEXT_PLAN = "PREPARE_NEXT_PLAN";
    /** The freshness state and cause of an ended plan's record. */
    static final String ENDED = "ENDED";
    static final String PLAN_ENDED_EARLY = "PLAN_ENDED_EARLY";
    /** The freshness state and cause of a completed plan's bundle (Story 2.14). */
    static final String COMPLETED = "COMPLETED";
    static final String PLAN_COMPLETED = "PLAN_COMPLETED";
    /**
     * A completed plan's screen-level next action while its first observation is missing or its comparison is pending
     * or held (Story 2.14): a sync fetches the observation or retries the comparison. Otherwise
     * {@link #PREPARE_NEXT_PLAN}.
     */
    static final String SYNC_FOR_OBSERVATION = "SYNC_FOR_OBSERVATION";

    /** The plan-end availabilities of a ready dashboard (Story 2.13). */
    static final String END_AVAILABLE = "AVAILABLE";
    static final String END_NEW_PLAN_CHOICE = "NEW_PLAN_CHOICE";
    static final String END_UNAVAILABLE = "UNAVAILABLE";
    /**
     * The plan end's own unavailability reasons (Story 2.13), sync's copies of the plan module's names
     * ({@code SyncCommandNamesTest} pins them): the {@code plan-end} restriction, {@code T} reached, and a result too
     * recent to end on now.
     */
    static final String END_RESTRICTED = "RESTRICTED";
    static final String END_PERIOD_ENDED = "PLAN_PERIOD_ENDED";
    static final String END_RESULT_TOO_RECENT = "RESULT_TOO_RECENT";

    /** The screen's order of the six metrics. The repository reads them in this order. */
    static final List<String> METRICS = List.of("CURRENT_VALUE", "PLAN_AMOUNT", "PLAN_DIFFERENCE", "ADJUSTED_TARGET",
            "REQUIRED_RATE", "GOAL_PROGRESS");

    /**
     * The input reliability states a calculation version may carry. Any other stored value is not judged at all.
     * {@code UNUSABLE} is never stored: it is the axis state a verification review out of the provider's history
     * produces (Story 2.12), judged here from the sync facts.
     */
    static final Set<String> RELIABILITIES = Set.of("CONFIRMED", "REVIEW_REQUIRED");

    private static final String TARGET_NOT_POSITIVE = "TARGET_NOT_POSITIVE";

    private DashboardTrust() {
    }

    /**
     * SYNC-UX-1.0 facts judged against the database clock.
     *
     * @param syncing any of the user's jobs is queued or running
     * @param measurementRecent {@code serverNow - measuredAt <= 15 minutes}
     * @param verificationRecent {@code serverNow - cashflowVerifiedThrough <= 15 minutes}
     */
    record Freshness(boolean syncing, boolean measurementRecent, boolean verificationRecent) {}

    /**
     * The sync facts of the plan (Story 2.11): the availability the admission would judge now, and the status of
     * the plan's latest attempt ({@code null} without one). Its closed code is the screen's to show; the axes
     * need only how it ended — a worker's long gap already reaches them through the availability.
     *
     * @param unavailableReason with {@code UNAVAILABLE}: why no sync can run — {@code REVIEW_HELD} when a persistent
     *     verification review holds it (R6), which the verification review's own cause then names
     * @param verificationReview the closed code of a verification or continuation of the current base that ended in a
     *     persistent review (Story 2.12), {@code null} without one
     * @param verificationAvailability what a precision verification can do now (the dashboard's verification part)
     */
    record SyncFacts(String availability, @Nullable String unavailableReason, @Nullable String latestStatus,
            @Nullable String verificationReview, String verificationAvailability) {
        SyncFacts(String availability, @Nullable String latestStatus, @Nullable String verificationReview) {
            this(availability, null, latestStatus, verificationReview, "AVAILABLE");
        }

        SyncFacts(String availability, @Nullable String latestStatus) {
            this(availability, latestStatus, null);
        }

        /** No attempt yet and nothing in the way. */
        static SyncFacts none() {
            return new SyncFacts(SyncWindow.Availability.AVAILABLE.name(), null, null);
        }
    }

    /**
     * What a sync's review asks of the user (R5), by what a precision verification can do now: run it ({@code VERIFY},
     * now or after its cooldown), or — in a long gap, where only the continuation runs — choose the continuation;
     * otherwise nothing the user can press would resolve it.
     */
    private static String reviewAction(String verificationAvailability) {
        return switch (verificationAvailability) {
            case "AVAILABLE", "COOLDOWN" -> VERIFY;
            case "LONG_GAP" -> CHOOSE_CONTINUATION;
            default -> NONE;
        };
    }

    /** The code of a verification review whose input is out of the provider's history: unusable, not reviewable. */
    static final String HISTORY_OUT_OF_RANGE = "HISTORY_OUT_OF_RANGE";

    /** What the axes are judged from: the freshness and sync facts and one calculation version with its metrics. */
    record Input(Freshness freshness, String inputReliability, boolean estimated, int estimatedFlowCount,
            Instant measuredAt, Instant verifiedThrough, Instant basisAt, List<DashboardMetric> metrics,
            SyncFacts sync) {
        /** Without sync facts: no attempt yet and nothing in the way. */
        Input(Freshness freshness, String inputReliability, boolean estimated, int estimatedFlowCount,
                Instant measuredAt, Instant verifiedThrough, Instant basisAt, List<DashboardMetric> metrics) {
            this(freshness, inputReliability, estimated, estimatedFlowCount, measuredAt, verifiedThrough, basisAt,
                    metrics, SyncFacts.none());
        }
    }

    /**
     * The three axes, or empty when the version's input reliability is not one this story knows. The
     * judgment fails closed: an unknown value is never read as "confirmed", and the caller answers that
     * the bundle cannot be shown.
     */
    static Optional<TrustAxes> judge(Input input) {
        if (!RELIABILITIES.contains(input.inputReliability())) return Optional.empty();
        return Optional.of(new TrustAxes(freshness(input), reliability(input), nature(input)));
    }

    /**
     * The three axes of an ended plan's record (Story 2.13), or empty when the version's input reliability is not one
     * this story knows. Freshness is {@code ENDED}; the input reliability and the nature are judged from the version
     * alone, without any sync fact — no attempt runs on an ended plan, and none of its endings reaches the record.
     */
    static Optional<TrustAxes> judgeEnded(String inputReliability, boolean estimated, int estimatedFlowCount,
            Instant measuredAt, Instant verifiedThrough, Instant basisAt, List<DashboardMetric> metrics,
            @Nullable String verificationReview) {
        return judgeRecord(ENDED, PLAN_ENDED_EARLY, inputReliability, estimated, estimatedFlowCount, measuredAt,
                verifiedThrough, basisAt, metrics, verificationReview);
    }

    /**
     * The three axes of a completed plan's bundle (Story 2.14), judged as an ended plan's record is: freshness folds
     * into {@code COMPLETED} ({@code PLAN_COMPLETED}, all six metrics, no next action) — a completed plan no longer
     * accrues, and its post-end syncs only refresh the post-end value — and the input reliability and the nature come
     * from the version and its own persistent verification review.
     */
    static Optional<TrustAxes> judgeCompleted(String inputReliability, boolean estimated, int estimatedFlowCount,
            Instant measuredAt, Instant verifiedThrough, Instant basisAt, List<DashboardMetric> metrics,
            @Nullable String verificationReview) {
        return judgeRecord(COMPLETED, PLAN_COMPLETED, inputReliability, estimated, estimatedFlowCount, measuredAt,
                verifiedThrough, basisAt, metrics, verificationReview);
    }

    private static Optional<TrustAxes> judgeRecord(String freshnessState, String freshnessCause,
            String inputReliability, boolean estimated, int estimatedFlowCount, Instant measuredAt,
            Instant verifiedThrough, Instant basisAt, List<DashboardMetric> metrics,
            @Nullable String verificationReview) {
        if (!RELIABILITIES.contains(inputReliability)) return Optional.empty();
        // Only the version's own persistent review; no attempt status, availability or long gap reaches the record.
        SyncFacts recordedFacts = new SyncFacts(SyncWindow.Availability.AVAILABLE.name(), null, null, verificationReview,
                SyncWindow.Availability.AVAILABLE.name());
        Input recorded = new Input(new Freshness(false, false, false), inputReliability, estimated, estimatedFlowCount,
                measuredAt, verifiedThrough, basisAt, metrics, recordedFacts);
        return Optional.of(new TrustAxes(new TrustAxis(freshnessState, freshnessCause, measuredAt, METRICS, NONE),
                reliability(recorded), nature(recorded)));
    }

    /**
     * Whether an official result is too recent to end on now (Story 2.13): the end time — the database clock to the
     * millisecond — would precede its measurement or its cash-flow verification. Bybit's time is trusted up to 60
     * seconds from the database clock, so for a moment after a publication this can hold; the last normal result is
     * never moved to the end time, so the end waits. The plan end's own judgment reads this same value.
     */
    static boolean resultTooRecent(Instant now, Instant measuredAt, Instant verifiedThrough) {
        Instant endedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return endedAt.isBefore(measuredAt) || endedAt.isBefore(verifiedThrough);
    }

    /**
     * Whether a ready plan can be ended now (Story 2.13), in the end's own order after the plan and its reference:
     * unavailable first — the {@code plan-end} restriction ({@code RESTRICTED}), then the database clock at {@code T}
     * ({@code PLAN_PERIOD_ENDED}), then a result too recent to end on ({@code RESULT_TOO_RECENT},
     * {@link #resultTooRecent}) — then the long gap, judged by the sync admission's own function, which leaves only the
     * long-gap choice's "start a new plan" ({@code NEW_PLAN_CHOICE}).
     */
    static PlanEndAvailability planEnd(boolean restricted, Instant now, Instant endAt, boolean tooRecent,
            boolean longGap) {
        if (restricted) return new PlanEndAvailability(END_UNAVAILABLE, END_RESTRICTED);
        if (!now.isBefore(endAt)) return new PlanEndAvailability(END_UNAVAILABLE, END_PERIOD_ENDED);
        if (tooRecent) return new PlanEndAvailability(END_UNAVAILABLE, END_RESULT_TOO_RECENT);
        if (longGap) return new PlanEndAvailability(END_NEW_PLAN_CHOICE, null);
        return new PlanEndAvailability(END_AVAILABLE, null);
    }

    /** The screen's next action for a ready dashboard: the freshness axis's own. */
    static String nextAction(TrustAxes trust) {
        return trust.freshness().nextAction();
    }

    /**
     * Whether the version's metrics are a whole bundle: exactly the six in order, the current value
     * defined and official, a value or a reason on each, and the character agreeing with the version —
     * every derived metric estimated exactly when the version used an estimated flow.
     */
    static boolean wellFormed(List<DashboardMetric> metrics, boolean estimated) {
        if (metrics.size() != METRICS.size()) return false;
        for (int index = 0; index < METRICS.size(); index++) {
            DashboardMetric metric = metrics.get(index);
            if (!METRICS.get(index).equals(metric.metric())) return false;
            if ((metric.value() == null) == (metric.unavailableReason() == null)) return false;
            boolean current = index == 0;
            String expected = current || !estimated ? "OFFICIAL" : "ESTIMATED";
            if (!expected.equals(metric.nature())) return false;
            if (current && metric.value() == null) return false;
        }
        return true;
    }

    /**
     * {@code SYNCING} wins over {@code STALE} (as in Story 2.7): the official values stay while a job runs.
     * Freshness is independent of the input review: "fresh, review required, not computable" is a valid
     * combination. A stale cause names why (SYNC-UX-1.0): no sync can run, the gap is long, the latest attempt
     * needed review or did not publish, or which of the two times is outside the window.
     */
    private static TrustAxis freshness(Input input) {
        Freshness facts = input.freshness();
        SyncFacts sync = input.sync();
        if (facts.syncing())
            return new TrustAxis("SYNCING", "JOB_IN_PROGRESS", input.measuredAt(), METRICS, WAIT_FOR_SYNC);
        // The guard's own reasons (restriction, connection, plan end) say nothing can run; a sync held by a persistent
        // verification review (R6) is named by that review instead.
        if (SyncWindow.Availability.UNAVAILABLE.name().equals(sync.availability())
                && !PlanSyncService.REVIEW_HELD.equals(sync.unavailableReason()))
            return new TrustAxis("STALE", "SYNC_UNAVAILABLE", input.measuredAt(), METRICS, NONE);
        // A persistent verification review of this base wins over the long gap (interpretation C3): the
        // continuation would end the same way, so it is not offered.
        if (sync.verificationReview() != null)
            return new TrustAxis("STALE", "LAST_VERIFICATION_NEEDS_REVIEW", input.measuredAt(), METRICS, NONE);
        if (SyncWindow.Availability.LONG_GAP.name().equals(sync.availability()))
            return new TrustAxis("STALE", "LONG_GAP", input.measuredAt(), METRICS, CHOOSE_CONTINUATION);
        // Syncing again cannot resolve a sync's review: the precision verification can (Story 2.12).
        if ("NEEDS_REVIEW".equals(sync.latestStatus()))
            return new TrustAxis("STALE", "LAST_SYNC_NEEDS_REVIEW", input.measuredAt(), METRICS,
                    reviewAction(sync.verificationAvailability()));
        if ("FAILED".equals(sync.latestStatus()) || "BLOCKED".equals(sync.latestStatus()))
            return new TrustAxis("STALE", "LAST_SYNC_UNSUCCESSFUL", input.measuredAt(), METRICS, SYNC);
        if (facts.measurementRecent() && facts.verificationRecent())
            return new TrustAxis("FRESH", "BOTH_WITHIN_WINDOW", input.measuredAt(), List.of(), NONE);
        String cause = !facts.measurementRecent() && !facts.verificationRecent() ? "BOTH_OUTDATED"
                : !facts.measurementRecent() ? "MEASUREMENT_OUTDATED" : "CASHFLOW_VERIFICATION_OUTDATED";
        return new TrustAxis("STALE", cause, input.measuredAt(), METRICS, SYNC);
    }

    /**
     * A review has three causes. A verification of the current base ended needing review (Story 2.12): all six
     * metrics are held and nothing the user can ask changes it. The latest sync ended needing review (Story 2.11):
     * its inputs are held, so every metric's next version is — all six — and a precision verification is what can
     * resolve it. Otherwise, under CALC-1.0, the adjusted target is not positive: it reaches the target itself and
     * every ratio that reason left undefined.
     */
    private static TrustAxis reliability(Input input) {
        // A verification of this base ended in review: it wins over any sync review, and asking again changes nothing.
        String verification = input.sync().verificationReview();
        if (HISTORY_OUT_OF_RANGE.equals(verification))
            return new TrustAxis("UNUSABLE", HISTORY_OUT_OF_RANGE, input.verifiedThrough(), METRICS, NONE);
        if (verification != null)
            return new TrustAxis("REVIEW_REQUIRED", "VERIFICATION_REVIEW_REQUIRED", input.verifiedThrough(), METRICS,
                    NONE);
        if ("NEEDS_REVIEW".equals(input.sync().latestStatus()))
            return new TrustAxis("REVIEW_REQUIRED", "SYNC_REVIEW_REQUIRED", input.verifiedThrough(), METRICS,
                    reviewAction(input.sync().verificationAvailability()));
        // Only the two known values reach here (judge refuses the rest).
        if ("REVIEW_REQUIRED".equals(input.inputReliability())) {
            List<String> affected = inOrder(input.metrics(), metric -> metric.metric().equals("ADJUSTED_TARGET")
                    || TARGET_NOT_POSITIVE.equals(metric.unavailableReason()));
            return new TrustAxis("REVIEW_REQUIRED", TARGET_NOT_POSITIVE, input.verifiedThrough(), affected, NONE);
        }
        return new TrustAxis("CONFIRMED", "INPUTS_VERIFIED", input.verifiedThrough(), List.of(), NONE);
    }

    /**
     * {@code NOT_COMPUTABLE} when any metric is undefined, otherwise the character. The character is kept
     * as its own field. The axis reaches the estimated metrics and the undefined ones.
     */
    private static NatureAxis nature(Input input) {
        List<String> unavailable = inOrder(input.metrics(), metric -> metric.value() == null);
        List<String> affected = inOrder(input.metrics(),
                metric -> metric.value() == null || "ESTIMATED".equals(metric.nature()));
        String character = input.estimated() ? "ESTIMATED" : "OFFICIAL";
        String state = !unavailable.isEmpty() ? "NOT_COMPUTABLE" : character;
        String cause = switch (state) {
            case "NOT_COMPUTABLE" -> "METRIC_UNDEFINED";
            case "ESTIMATED" -> "ESTIMATED_CASHFLOW_PRICE";
            default -> "NO_ESTIMATED_INPUT";
        };
        return new NatureAxis(new TrustAxis(state, cause, input.basisAt(), affected, NONE), character, unavailable,
                input.estimatedFlowCount());
    }

    private static List<String> inOrder(List<DashboardMetric> metrics, Predicate<DashboardMetric> reached) {
        return metrics.stream().filter(reached).map(DashboardMetric::metric).toList();
    }
}
