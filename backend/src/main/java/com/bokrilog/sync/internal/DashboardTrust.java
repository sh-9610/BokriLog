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
 * <p>The next actions are the closed set of this story: {@link #CREATE_PLAN}, {@link #WAIT_FOR_RESULT},
 * {@link #WAIT_FOR_SYNC} and {@link #NONE}. A sync control arrives with Story 2.11; until then "not
 * fresh" has no action here, and the screen says so without promising one.
 */
final class DashboardTrust {
    static final String CREATE_PLAN = "CREATE_PLAN";
    static final String WAIT_FOR_RESULT = "WAIT_FOR_RESULT";
    static final String WAIT_FOR_SYNC = "WAIT_FOR_SYNC";
    static final String NONE = "NONE";

    /** The screen's order of the six metrics. The repository reads them in this order. */
    static final List<String> METRICS = List.of("CURRENT_VALUE", "PLAN_AMOUNT", "PLAN_DIFFERENCE", "ADJUSTED_TARGET",
            "REQUIRED_RATE", "GOAL_PROGRESS");

    /**
     * The input reliability states a calculation version may carry in this story. {@code UNUSABLE} has no
     * producer yet; any other value is not judged at all.
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

    /** The screen's next action for a ready dashboard: wait while a sync runs, otherwise none. */
    static String nextAction(TrustAxes trust) {
        return WAIT_FOR_SYNC.equals(trust.freshness().nextAction()) ? WAIT_FOR_SYNC : NONE;
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
     * combination. A stale cause names which of the two times is outside the window.
     */
    private static TrustAxis freshness(Input input) {
        Freshness facts = input.freshness();
        if (facts.syncing())
            return new TrustAxis("SYNCING", "JOB_IN_PROGRESS", input.measuredAt(), METRICS, WAIT_FOR_SYNC);
        if (facts.measurementRecent() && facts.verificationRecent())
            return new TrustAxis("FRESH", "BOTH_WITHIN_WINDOW", input.measuredAt(), List.of(), NONE);
        String cause = !facts.measurementRecent() && !facts.verificationRecent() ? "BOTH_OUTDATED"
                : !facts.measurementRecent() ? "MEASUREMENT_OUTDATED" : "CASHFLOW_VERIFICATION_OUTDATED";
        return new TrustAxis("STALE", cause, input.measuredAt(), METRICS, NONE);
    }

    /**
     * A review under CALC-1.0 has one cause: the adjusted target is not positive. It reaches the target
     * itself and every ratio that reason left undefined.
     */
    private static TrustAxis reliability(Input input) {
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
