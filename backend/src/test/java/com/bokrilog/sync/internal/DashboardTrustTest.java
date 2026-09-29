package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.sync.SyncJobApi.DashboardMetric;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The three axes are judged independently (Story 2.8 AC-5): every combination of freshness (3), input
 * reliability (2) and calculation nature (3) gives each axis its own cause, affected metrics and next
 * action, and no axis borrows from another. The combinations are the pure function's inputs — some cannot
 * occur under CALC-1.0 (a review always leaves a ratio undefined), and the function is total over them.
 */
class DashboardTrustTest {
    private static final Instant MEASURED = Instant.parse("2027-01-04T00:59:59Z");
    private static final Instant VERIFIED = Instant.parse("2027-01-04T01:00:00Z");
    private static final Instant BASIS = Instant.parse("2027-01-04T01:00:00.500Z");
    private static final List<String> SIX = List.of("CURRENT_VALUE", "PLAN_AMOUNT", "PLAN_DIFFERENCE",
            "ADJUSTED_TARGET", "REQUIRED_RATE", "GOAL_PROGRESS");

    /**
     * Columns: freshness · input · nature → freshness cause · freshness action · input cause · input affected
     * · nature cause · nature affected · screen action. Metric lists are space separated; {@code -} is none.
     */
    @ParameterizedTest(name = "{0} + {1} + {2}")
    @CsvSource({
        "FRESH,CONFIRMED,OFFICIAL,BOTH_WITHIN_WINDOW,NONE,INPUTS_VERIFIED,-,NO_ESTIMATED_INPUT,-,NONE",
        "FRESH,CONFIRMED,ESTIMATED,BOTH_WITHIN_WINDOW,NONE,INPUTS_VERIFIED,-,ESTIMATED_CASHFLOW_PRICE,"
            + "PLAN_AMOUNT PLAN_DIFFERENCE ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,NONE",
        "FRESH,CONFIRMED,NOT_COMPUTABLE,BOTH_WITHIN_WINDOW,NONE,INPUTS_VERIFIED,-,METRIC_UNDEFINED,REQUIRED_RATE,NONE",
        "FRESH,REVIEW_REQUIRED,OFFICIAL,BOTH_WITHIN_WINDOW,NONE,TARGET_NOT_POSITIVE,ADJUSTED_TARGET,NO_ESTIMATED_INPUT,-,NONE",
        "FRESH,REVIEW_REQUIRED,ESTIMATED,BOTH_WITHIN_WINDOW,NONE,TARGET_NOT_POSITIVE,ADJUSTED_TARGET,"
            + "ESTIMATED_CASHFLOW_PRICE,PLAN_AMOUNT PLAN_DIFFERENCE ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,NONE",
        "FRESH,REVIEW_REQUIRED,NOT_COMPUTABLE,BOTH_WITHIN_WINDOW,NONE,TARGET_NOT_POSITIVE,"
            + "ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,METRIC_UNDEFINED,REQUIRED_RATE GOAL_PROGRESS,NONE",
        "SYNCING,CONFIRMED,OFFICIAL,JOB_IN_PROGRESS,WAIT_FOR_SYNC,INPUTS_VERIFIED,-,NO_ESTIMATED_INPUT,-,WAIT_FOR_SYNC",
        "SYNCING,CONFIRMED,ESTIMATED,JOB_IN_PROGRESS,WAIT_FOR_SYNC,INPUTS_VERIFIED,-,ESTIMATED_CASHFLOW_PRICE,"
            + "PLAN_AMOUNT PLAN_DIFFERENCE ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,WAIT_FOR_SYNC",
        "SYNCING,CONFIRMED,NOT_COMPUTABLE,JOB_IN_PROGRESS,WAIT_FOR_SYNC,INPUTS_VERIFIED,-,METRIC_UNDEFINED,"
            + "REQUIRED_RATE,WAIT_FOR_SYNC",
        "SYNCING,REVIEW_REQUIRED,OFFICIAL,JOB_IN_PROGRESS,WAIT_FOR_SYNC,TARGET_NOT_POSITIVE,ADJUSTED_TARGET,"
            + "NO_ESTIMATED_INPUT,-,WAIT_FOR_SYNC",
        "SYNCING,REVIEW_REQUIRED,ESTIMATED,JOB_IN_PROGRESS,WAIT_FOR_SYNC,TARGET_NOT_POSITIVE,ADJUSTED_TARGET,"
            + "ESTIMATED_CASHFLOW_PRICE,PLAN_AMOUNT PLAN_DIFFERENCE ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,WAIT_FOR_SYNC",
        "SYNCING,REVIEW_REQUIRED,NOT_COMPUTABLE,JOB_IN_PROGRESS,WAIT_FOR_SYNC,TARGET_NOT_POSITIVE,"
            + "ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,METRIC_UNDEFINED,REQUIRED_RATE GOAL_PROGRESS,WAIT_FOR_SYNC",
        "STALE,CONFIRMED,OFFICIAL,BOTH_OUTDATED,SYNC_NOW,INPUTS_VERIFIED,-,NO_ESTIMATED_INPUT,-,SYNC_NOW",
        "STALE,CONFIRMED,ESTIMATED,BOTH_OUTDATED,SYNC_NOW,INPUTS_VERIFIED,-,ESTIMATED_CASHFLOW_PRICE,"
            + "PLAN_AMOUNT PLAN_DIFFERENCE ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,SYNC_NOW",
        "STALE,CONFIRMED,NOT_COMPUTABLE,BOTH_OUTDATED,SYNC_NOW,INPUTS_VERIFIED,-,METRIC_UNDEFINED,REQUIRED_RATE,SYNC_NOW",
        "STALE,REVIEW_REQUIRED,OFFICIAL,BOTH_OUTDATED,SYNC_NOW,TARGET_NOT_POSITIVE,ADJUSTED_TARGET,NO_ESTIMATED_INPUT,-,SYNC_NOW",
        "STALE,REVIEW_REQUIRED,ESTIMATED,BOTH_OUTDATED,SYNC_NOW,TARGET_NOT_POSITIVE,ADJUSTED_TARGET,"
            + "ESTIMATED_CASHFLOW_PRICE,PLAN_AMOUNT PLAN_DIFFERENCE ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,SYNC_NOW",
        "STALE,REVIEW_REQUIRED,NOT_COMPUTABLE,BOTH_OUTDATED,SYNC_NOW,TARGET_NOT_POSITIVE,"
            + "ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,METRIC_UNDEFINED,REQUIRED_RATE GOAL_PROGRESS,SYNC_NOW",
    })
    void eachAxisIsJudgedOnItsOwn(String freshness, String input, String nature, String freshnessCause,
            String freshnessAction, String inputCause, String inputAffected, String natureCause, String natureAffected,
            String screenAction) {
        var trust = DashboardTrust.judge(input(freshness, input, nature)).orElseThrow();

        var fresh = trust.freshness();
        assertThat(fresh.state()).isEqualTo(freshness);
        assertThat(fresh.cause()).isEqualTo(freshnessCause);
        assertThat(fresh.lastNormalAt()).as("freshness vouches for the measurement").isEqualTo(MEASURED);
        assertThat(fresh.affectedMetrics()).isEqualTo(freshness.equals("FRESH") ? List.of() : SIX);
        assertThat(fresh.nextAction()).isEqualTo(freshnessAction);

        var reliability = trust.inputReliability();
        assertThat(reliability.state()).isEqualTo(input);
        assertThat(reliability.cause()).isEqualTo(inputCause);
        assertThat(reliability.lastNormalAt()).as("input vouches for the cash-flow verification").isEqualTo(VERIFIED);
        assertThat(reliability.affectedMetrics()).isEqualTo(list(inputAffected));
        assertThat(reliability.nextAction()).as("no review action exists in this story").isEqualTo("NONE");

        var character = trust.calculationNature();
        assertThat(character.axis().state()).isEqualTo(nature);
        assertThat(character.character()).as("the character is kept beside not computable")
                .isEqualTo(nature.equals("ESTIMATED") ? "ESTIMATED" : "OFFICIAL");
        assertThat(character.axis().cause()).isEqualTo(natureCause);
        assertThat(character.axis().lastNormalAt()).as("nature vouches for the basis").isEqualTo(BASIS);
        assertThat(character.axis().affectedMetrics()).isEqualTo(list(natureAffected));
        assertThat(character.unavailableMetrics()).isEqualTo(nature.equals("NOT_COMPUTABLE")
                ? list(natureAffected) : List.of());
        assertThat(character.axis().nextAction()).isEqualTo("NONE");

        assertThat(DashboardTrust.nextAction(trust)).isEqualTo(screenAction);
    }

    /** A stale cause names which of the two times is outside the window. */
    @ParameterizedTest(name = "measurement recent={0}, verification recent={1}")
    @CsvSource({"true,true,FRESH,BOTH_WITHIN_WINDOW", "false,true,STALE,MEASUREMENT_OUTDATED",
        "true,false,STALE,CASHFLOW_VERIFICATION_OUTDATED", "false,false,STALE,BOTH_OUTDATED"})
    void aStaleCauseNamesTheOutdatedTime(boolean measurement, boolean verification, String state, String cause) {
        var axis = DashboardTrust.judge(new DashboardTrust.Input(new DashboardTrust.Freshness(false, measurement,
                verification), "CONFIRMED", false, 0, MEASURED, VERIFIED, BASIS, metrics("CONFIRMED", "OFFICIAL")))
                .orElseThrow().freshness();
        assertThat(axis.state()).isEqualTo(state);
        assertThat(axis.cause()).isEqualTo(cause);
    }

    /**
     * Story 2.11: a stale cause in its order — in progress, restriction, period end, long gap, the last attempt,
     * then the fifteen-minute rule — each with its next action, and a fresh measurement does not hide the last
     * attempt's failure. The official values' last normal time never moves.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "in progress beats everything,true,true,true,true,FAILED,SYNC_INTERRUPTED,true,SYNCING,JOB_IN_PROGRESS,WAIT_FOR_SYNC",
        "restriction,false,true,true,true,FAILED,SYNC_INTERRUPTED,true,STALE,SYNC_RESTRICTED,NONE",
        "period ended,false,false,true,true,FAILED,SYNC_INTERRUPTED,true,STALE,PLAN_PERIOD_ENDED,NONE",
        "long gap,false,false,false,true,FAILED,SYNC_INTERRUPTED,true,STALE,LONG_GAP_CHOICE_REQUIRED,REVIEW_LONG_GAP",
        "a retryable failure,false,false,false,false,FAILED,SOURCE_UNAVAILABLE,true,STALE,LAST_SYNC_FAILED,SYNC_NOW",
        "a failure no new attempt helps,false,false,false,false,FAILED,SOURCE_REJECTED,false,STALE,LAST_SYNC_FAILED,NONE",
        "a review,false,false,false,false,REVIEW_REQUIRED,APPLIED_FLOW_CHANGED,false,STALE,LAST_SYNC_REVIEW_REQUIRED,NONE",
        "a retryable block,false,false,false,false,BLOCKED,POINTER_CONFLICT,true,STALE,LAST_SYNC_BLOCKED,SYNC_NOW",
        "a block,false,false,false,false,BLOCKED,PLAN_CHANGED,false,STALE,LAST_SYNC_BLOCKED,NONE",
        "no attempt,false,false,false,false,,,false,FRESH,BOTH_WITHIN_WINDOW,NONE"})
    void aStaleCauseFollowsTheSyncOrder(String name, boolean syncing, boolean restricted, boolean periodEnded,
            boolean longGap, String status, String code, boolean retryable, String state, String cause, String action) {
        var last = status == null ? null : new DashboardTrust.LastAttempt(status, code, retryable);
        var trust = DashboardTrust.judge(new DashboardTrust.Input(new DashboardTrust.Freshness(syncing, true, true,
                restricted, periodEnded, longGap, last), "CONFIRMED", false, 0, MEASURED, VERIFIED, BASIS,
                metrics("CONFIRMED", "OFFICIAL"))).orElseThrow();
        assertThat(trust.freshness().state()).as(name).isEqualTo(state);
        assertThat(trust.freshness().cause()).as(name).isEqualTo(cause);
        assertThat(trust.freshness().nextAction()).as(name).isEqualTo(action);
        assertThat(trust.freshness().lastNormalAt()).isEqualTo(MEASURED);
        assertThat(DashboardTrust.nextAction(trust)).as("the screen's action is the freshness axis's").isEqualTo(action);
    }

    /** A last attempt that needed a review of its latest inputs makes the input reliability "review required". */
    @Test
    void theLastAttemptsReviewIsTheInputReliabilitysCause() {
        var trust = DashboardTrust.judge(new DashboardTrust.Input(new DashboardTrust.Freshness(false, true, true, false,
                false, false, new DashboardTrust.LastAttempt("REVIEW_REQUIRED", "COLLECTION_REVIEW_REQUIRED", false)),
                "CONFIRMED", false, 0, MEASURED, VERIFIED, BASIS, metrics("CONFIRMED", "OFFICIAL"))).orElseThrow();
        assertThat(trust.inputReliability().state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(trust.inputReliability().cause()).isEqualTo("LATEST_INPUTS_REVIEW_REQUIRED");
        assertThat(trust.inputReliability().affectedMetrics()).isEqualTo(SIX);
        assertThat(trust.inputReliability().lastNormalAt()).isEqualTo(VERIFIED);
        assertThat(trust.inputReliability().nextAction()).isEqualTo("NONE");
        // A failure is not a review: the reliability stays the version's own.
        var failed = DashboardTrust.judge(new DashboardTrust.Input(new DashboardTrust.Freshness(false, true, true, false,
                false, false, new DashboardTrust.LastAttempt("FAILED", "SOURCE_UNAVAILABLE", true)),
                "CONFIRMED", false, 0, MEASURED, VERIFIED, BASIS, metrics("CONFIRMED", "OFFICIAL"))).orElseThrow();
        assertThat(failed.inputReliability().state()).isEqualTo("CONFIRMED");
    }

    /** A running job wins over stale times: the official values stay while it runs. */
    @Test
    void syncingWinsOverStale() {
        var axis = DashboardTrust.judge(new DashboardTrust.Input(new DashboardTrust.Freshness(true, false, false),
                "CONFIRMED", false, 0, MEASURED, VERIFIED, BASIS, metrics("CONFIRMED", "OFFICIAL"))).orElseThrow()
                .freshness();
        assertThat(axis.state()).isEqualTo("SYNCING");
        assertThat(axis.nextAction()).isEqualTo("WAIT_FOR_SYNC");
    }

    /**
     * An estimated version with an undefined ratio: not computable, the character stays estimated, and the
     * axis reaches the estimated metrics together with the undefined ones.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"CONFIRMED,CURRENT_VALUE_NOT_POSITIVE,REQUIRED_RATE", "REVIEW_REQUIRED,TARGET_NOT_POSITIVE,REQUIRED_RATE GOAL_PROGRESS"})
    void anEstimatedVersionThatIsNotComputableKeepsItsCharacter(String input, String reason, String undefined) {
        var missing = list(undefined);
        var metrics = new ArrayList<DashboardMetric>();
        for (String metric : SIX) {
            boolean current = metric.equals("CURRENT_VALUE");
            String nature = current ? "OFFICIAL" : "ESTIMATED";
            metrics.add(missing.contains(metric) ? new DashboardMetric(metric, null, nature, reason)
                    : new DashboardMetric(metric, current ? "1250" : "10", nature, null));
        }
        assertThat(DashboardTrust.wellFormed(metrics, true)).isTrue();
        var nature = DashboardTrust.judge(new DashboardTrust.Input(new DashboardTrust.Freshness(false, true, true),
                input, true, 2, MEASURED, VERIFIED, BASIS, metrics)).orElseThrow().calculationNature();

        assertThat(nature.axis().state()).isEqualTo("NOT_COMPUTABLE");
        assertThat(nature.character()).isEqualTo("ESTIMATED");
        assertThat(nature.axis().cause()).isEqualTo("METRIC_UNDEFINED");
        assertThat(nature.unavailableMetrics()).isEqualTo(missing);
        // Estimated metrics ∪ undefined metrics, in the screen's order: every derived metric.
        assertThat(nature.axis().affectedMetrics()).isEqualTo(SIX.subList(1, 6));
        assertThat(nature.estimatedFlowCount()).isEqualTo(2);
    }

    /** An input reliability this story does not know is not judged at all — never read as confirmed. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"UNUSABLE", "SOMETHING_NEW", "confirmed", "''"})
    void anUnknownReliabilityFailsClosed(String reliability) {
        var judged = DashboardTrust.judge(new DashboardTrust.Input(new DashboardTrust.Freshness(false, true, true),
                reliability, false, 0, MEASURED, VERIFIED, BASIS, metrics("CONFIRMED", "OFFICIAL")));
        assertThat(judged).isEmpty();
    }

    @Test
    void aWholeBundleHasTheSixMetricsInOrderWithAValueOrAReasonAndOneCharacter() {
        assertThat(DashboardTrust.wellFormed(metrics("CONFIRMED", "OFFICIAL"), false)).isTrue();
        assertThat(DashboardTrust.wellFormed(metrics("CONFIRMED", "ESTIMATED"), true)).isTrue();
        assertThat(DashboardTrust.wellFormed(metrics("CONFIRMED", "NOT_COMPUTABLE"), false)).isTrue();
        assertThat(DashboardTrust.wellFormed(metrics("CONFIRMED", "OFFICIAL").subList(0, 5), false)).as("five").isFalse();
        var reordered = new ArrayList<>(metrics("CONFIRMED", "OFFICIAL"));
        reordered.add(0, reordered.remove(1));
        assertThat(DashboardTrust.wellFormed(reordered, false)).as("order").isFalse();
        assertThat(DashboardTrust.wellFormed(metrics("CONFIRMED", "ESTIMATED"), false)).as("character").isFalse();
        assertThat(DashboardTrust.wellFormed(metrics("CONFIRMED", "OFFICIAL"), true)).as("character").isFalse();
        var both = new ArrayList<>(metrics("CONFIRMED", "OFFICIAL"));
        both.set(4, new DashboardMetric("REQUIRED_RATE", "0", "OFFICIAL", "CURRENT_VALUE_NOT_POSITIVE"));
        assertThat(DashboardTrust.wellFormed(both, false)).as("a value and a reason").isFalse();
        var noCurrent = new ArrayList<>(metrics("CONFIRMED", "OFFICIAL"));
        noCurrent.set(0, new DashboardMetric("CURRENT_VALUE", null, "OFFICIAL", "CURRENT_VALUE_NOT_POSITIVE"));
        assertThat(DashboardTrust.wellFormed(noCurrent, false)).as("the current value is always defined").isFalse();
    }

    private static DashboardTrust.Input input(String freshness, String input, String nature) {
        var facts = switch (freshness) {
            case "SYNCING" -> new DashboardTrust.Freshness(true, true, true);
            case "STALE" -> new DashboardTrust.Freshness(false, false, false);
            default -> new DashboardTrust.Freshness(false, true, true);
        };
        return new DashboardTrust.Input(facts, input, nature.equals("ESTIMATED"), nature.equals("ESTIMATED") ? 1 : 0,
                MEASURED, VERIFIED, BASIS, metrics(input, nature));
    }

    /**
     * Six metrics for a combination. A not computable version leaves the required rate undefined (with the
     * current value not positive) or, under review, both target-based ratios undefined for the target.
     */
    private static List<DashboardMetric> metrics(String input, String nature) {
        String derived = nature.equals("ESTIMATED") ? "ESTIMATED" : "OFFICIAL";
        boolean undefined = nature.equals("NOT_COMPUTABLE");
        boolean review = input.equals("REVIEW_REQUIRED");
        return List.of(new DashboardMetric("CURRENT_VALUE", "1250", "OFFICIAL", null),
                new DashboardMetric("PLAN_AMOUNT", "1210", derived, null),
                new DashboardMetric("PLAN_DIFFERENCE", "40", derived, null),
                new DashboardMetric("ADJUSTED_TARGET", review ? "-5" : "1331", derived, null),
                undefined ? new DashboardMetric("REQUIRED_RATE", null, derived,
                        review ? "TARGET_NOT_POSITIVE" : "CURRENT_VALUE_NOT_POSITIVE")
                        : new DashboardMetric("REQUIRED_RATE", "0.0648", derived, null),
                undefined && review ? new DashboardMetric("GOAL_PROGRESS", null, derived, "TARGET_NOT_POSITIVE")
                        : new DashboardMetric("GOAL_PROGRESS", "0.75", derived, null));
    }

    private static List<String> list(String spaced) {
        return spaced.equals("-") ? List.of() : Arrays.asList(spaced.split(" "));
    }
}
