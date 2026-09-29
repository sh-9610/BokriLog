package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncJobApi.DashboardMetric;
import com.bokrilog.sync.SyncJobApi.NatureAxis;
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
 * <p>The next actions are a closed set: {@link #CREATE_PLAN} and {@link #WAIT_FOR_RESULT} belong to the
 * screen without a result (Story 2.8); a ready result's freshness leaves {@link #WAIT_FOR_SYNC} (a job
 * runs), {@link #SYNC_NOW} (the result is older than the window, or the last attempt failed in a way a new
 * attempt can help — Story 2.11), {@link #REVIEW_LONG_GAP} (more than thirty days went unsynchronized and
 * only a choice the user has not made yet can continue) or {@link #NONE}.
 */
final class DashboardTrust {
    static final String CREATE_PLAN = "CREATE_PLAN";
    static final String WAIT_FOR_RESULT = "WAIT_FOR_RESULT";
    static final String WAIT_FOR_SYNC = "WAIT_FOR_SYNC";
    static final String SYNC_NOW = "SYNC_NOW";
    static final String REVIEW_LONG_GAP = "REVIEW_LONG_GAP";
    static final String NONE = "NONE";

    /** The screen's order of the six metrics. The repository reads them in this order. */
    static final List<String> METRICS = List.of("CURRENT_VALUE", "PLAN_AMOUNT", "PLAN_DIFFERENCE", "ADJUSTED_TARGET",
            "REQUIRED_RATE", "GOAL_PROGRESS");

    /**
     * The input reliability states a calculation version may carry. {@code UNUSABLE} has no producer yet (a
     * sync's review is reported through the version's last attempt, not stored on a version); any other value
     * is not judged at all.
     */
    static final Set<String> RELIABILITIES = Set.of("CONFIRMED", "REVIEW_REQUIRED");

    private static final String TARGET_NOT_POSITIVE = "TARGET_NOT_POSITIVE";

    private DashboardTrust() {
    }

    /**
     * The last sync attempt of the current official version (Story 2.11): the most recent one that finished
     * among those admitted after the pointer became official. A publication moves the pointer, so this is
     * never a publication; it is a failure, a review or a block, with the closed table's retry verdict.
     */
    record LastAttempt(String status, String failureCode, boolean retryable) {}

    /**
     * SYNC-UX-1.0 facts judged against the database clock.
     *
     * @param syncing any of the user's jobs is queued or running
     * @param measurementRecent {@code serverNow - measuredAt <= 15 minutes}
     * @param verificationRecent {@code serverNow - cashflowVerifiedThrough <= 15 minutes}
     * @param restricted a command the plan sync runs through is restricted (Story 2.11)
     * @param periodEnded {@code serverNow >= T}
     * @param longGap {@code serverNow - cashflowVerifiedThrough > 30 days}
     * @param lastAttempt the current version's last finished sync attempt, if any
     */
    record Freshness(boolean syncing, boolean measurementRecent, boolean verificationRecent, boolean restricted,
            boolean periodEnded, boolean longGap, @Nullable LastAttempt lastAttempt) {
        /** Story 2.8's three facts alone: no restriction, no ending, no gap and no attempt yet. */
        Freshness(boolean syncing, boolean measurementRecent, boolean verificationRecent) {
            this(syncing, measurementRecent, verificationRecent, false, false, false, null);
        }
    }

    /** What the axes are judged from: the freshness facts and one calculation version with its metrics. */
    record Input(Freshness freshness, String inputReliability, boolean estimated, int estimatedFlowCount,
            Instant measuredAt, Instant verifiedThrough, Instant basisAt, List<DashboardMetric> metrics) {}

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
     * The screen's next action for a ready dashboard: the freshness axis's own — wait while a sync runs,
     * sync now, review the long gap, or none. The other two axes never leave an action of their own.
     */
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
     * combination.
     *
     * <p>A stale result names its cause in this order (Story 2.11): a sync restriction, the plan period's
     * end, a gap of more than thirty days, the last attempt's failure, review or block since the version
     * became official, and only then which of the two times is outside the fifteen-minute window. The last
     * normal time is always the version's measurement — the official values never change here.
     */
    private static TrustAxis freshness(Input input) {
        Freshness facts = input.freshness();
        if (facts.syncing())
            return new TrustAxis("SYNCING", "JOB_IN_PROGRESS", input.measuredAt(), METRICS, WAIT_FOR_SYNC);
        if (facts.restricted()) return stale(input, "SYNC_RESTRICTED", NONE);
        if (facts.periodEnded()) return stale(input, "PLAN_PERIOD_ENDED", NONE);
        if (facts.longGap()) return stale(input, "LONG_GAP_CHOICE_REQUIRED", REVIEW_LONG_GAP);
        LastAttempt last = facts.lastAttempt();
        if (last != null) {
            String action = last.retryable() ? SYNC_NOW : NONE;
            switch (last.status()) {
                case "FAILED" -> {
                    return stale(input, "LAST_SYNC_FAILED", action);
                }
                case "REVIEW_REQUIRED" -> {
                    return stale(input, "LAST_SYNC_REVIEW_REQUIRED", NONE);
                }
                case "BLOCKED" -> {
                    return stale(input, "LAST_SYNC_BLOCKED", action);
                }
                default -> {
                    // A publication moves the pointer, so it is never the current version's last attempt.
                }
            }
        }
        if (facts.measurementRecent() && facts.verificationRecent())
            return new TrustAxis("FRESH", "BOTH_WITHIN_WINDOW", input.measuredAt(), List.of(), NONE);
        String cause = !facts.measurementRecent() && !facts.verificationRecent() ? "BOTH_OUTDATED"
                : !facts.measurementRecent() ? "MEASUREMENT_OUTDATED" : "CASHFLOW_VERIFICATION_OUTDATED";
        return stale(input, cause, SYNC_NOW);
    }

    private static TrustAxis stale(Input input, String cause, String nextAction) {
        return new TrustAxis("STALE", cause, input.measuredAt(), METRICS, nextAction);
    }

    /**
     * A review under CALC-1.0 has one cause: the adjusted target is not positive. It reaches the target
     * itself and every ratio that reason left undefined.
     *
     * <p>Story 2.11 adds a second: the last sync attempt of this version ended needing a review of its
     * latest inputs. Nothing of it was published, so every metric stays the last normal one and all six are
     * reached; the verified time is still the version's own.
     */
    private static TrustAxis reliability(Input input) {
        LastAttempt last = input.freshness().lastAttempt();
        if (last != null && "REVIEW_REQUIRED".equals(last.status()))
            return new TrustAxis("REVIEW_REQUIRED", "LATEST_INPUTS_REVIEW_REQUIRED", input.verifiedThrough(), METRICS,
                    NONE);
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
