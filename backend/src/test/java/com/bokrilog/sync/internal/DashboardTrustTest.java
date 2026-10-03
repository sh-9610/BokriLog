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
 * action, and no axis borrows from another. A stale result's next action is a new sync (Story 2.11). The combinations are the pure function's inputs — some cannot
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
        "STALE,CONFIRMED,OFFICIAL,BOTH_OUTDATED,SYNC,INPUTS_VERIFIED,-,NO_ESTIMATED_INPUT,-,SYNC",
        "STALE,CONFIRMED,ESTIMATED,BOTH_OUTDATED,SYNC,INPUTS_VERIFIED,-,ESTIMATED_CASHFLOW_PRICE,"
            + "PLAN_AMOUNT PLAN_DIFFERENCE ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,SYNC",
        "STALE,CONFIRMED,NOT_COMPUTABLE,BOTH_OUTDATED,SYNC,INPUTS_VERIFIED,-,METRIC_UNDEFINED,REQUIRED_RATE,SYNC",
        "STALE,REVIEW_REQUIRED,OFFICIAL,BOTH_OUTDATED,SYNC,TARGET_NOT_POSITIVE,ADJUSTED_TARGET,NO_ESTIMATED_INPUT,-,SYNC",
        "STALE,REVIEW_REQUIRED,ESTIMATED,BOTH_OUTDATED,SYNC,TARGET_NOT_POSITIVE,ADJUSTED_TARGET,"
            + "ESTIMATED_CASHFLOW_PRICE,PLAN_AMOUNT PLAN_DIFFERENCE ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,SYNC",
        "STALE,REVIEW_REQUIRED,NOT_COMPUTABLE,BOTH_OUTDATED,SYNC,TARGET_NOT_POSITIVE,"
            + "ADJUSTED_TARGET REQUIRED_RATE GOAL_PROGRESS,METRIC_UNDEFINED,REQUIRED_RATE GOAL_PROGRESS,SYNC",
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

    /**
     * Story 2.11: the sync facts. No sync can run → stale, no action; a long gap → stale, a choice; the latest
     * attempt failed or was blocked → stale, sync again. Each wins over recent times; a running job wins over all of
     * them. A successful or no attempt leaves the times to decide.
     *
     * <p>Story 2.12: the latest sync needed review → stale, and a precision verification (not another sync) is the
     * next action of both axes, the input reliability review required for all six. A verification of the current base
     * that itself ended in a persistent review wins over the sync's review on both axes and over the long gap on the
     * freshness axis (C3), with no action at all — unusable when the history is out of the provider's reach.
     */
    @ParameterizedTest(name = "{0} / {1} / syncing={2} / verification={3}")
    @CsvSource({
        "UNAVAILABLE,,false,,STALE,SYNC_UNAVAILABLE,NONE,CONFIRMED,INPUTS_VERIFIED,NONE",
        "LONG_GAP,,false,,STALE,LONG_GAP,CHOOSE_CONTINUATION,CONFIRMED,INPUTS_VERIFIED,NONE",
        "LONG_GAP,BLOCKED,false,,STALE,LONG_GAP,CHOOSE_CONTINUATION,CONFIRMED,INPUTS_VERIFIED,NONE",
        "AVAILABLE,NEEDS_REVIEW,false,,STALE,LAST_SYNC_NEEDS_REVIEW,VERIFY,REVIEW_REQUIRED,SYNC_REVIEW_REQUIRED,VERIFY",
        "COOLDOWN,FAILED,false,,STALE,LAST_SYNC_UNSUCCESSFUL,SYNC,CONFIRMED,INPUTS_VERIFIED,NONE",
        "AVAILABLE,BLOCKED,false,,STALE,LAST_SYNC_UNSUCCESSFUL,SYNC,CONFIRMED,INPUTS_VERIFIED,NONE",
        "AVAILABLE,SUCCEEDED,false,,FRESH,BOTH_WITHIN_WINDOW,NONE,CONFIRMED,INPUTS_VERIFIED,NONE",
        "COOLDOWN,SUCCEEDED,false,,FRESH,BOTH_WITHIN_WINDOW,NONE,CONFIRMED,INPUTS_VERIFIED,NONE",
        "IN_PROGRESS,RUNNING,true,,SYNCING,JOB_IN_PROGRESS,WAIT_FOR_SYNC,CONFIRMED,INPUTS_VERIFIED,NONE",
        "IN_PROGRESS,NEEDS_REVIEW,true,,SYNCING,JOB_IN_PROGRESS,WAIT_FOR_SYNC,REVIEW_REQUIRED,SYNC_REVIEW_REQUIRED,VERIFY",
        "AVAILABLE,NEEDS_REVIEW,false,SOURCE_EVENT_MISSING,STALE,LAST_VERIFICATION_NEEDS_REVIEW,NONE,REVIEW_REQUIRED,"
            + "VERIFICATION_REVIEW_REQUIRED,NONE",
        "COOLDOWN,NEEDS_REVIEW,false,SOURCE_REQUIRES_REVIEW,STALE,LAST_VERIFICATION_NEEDS_REVIEW,NONE,REVIEW_REQUIRED,"
            + "VERIFICATION_REVIEW_REQUIRED,NONE",
        "AVAILABLE,NEEDS_REVIEW,false,HISTORY_OUT_OF_RANGE,STALE,LAST_VERIFICATION_NEEDS_REVIEW,NONE,UNUSABLE,"
            + "HISTORY_OUT_OF_RANGE,NONE",
        "IN_PROGRESS,RUNNING,true,SOURCE_EVENT_MISSING,SYNCING,JOB_IN_PROGRESS,WAIT_FOR_SYNC,REVIEW_REQUIRED,"
            + "VERIFICATION_REVIEW_REQUIRED,NONE",
        // C3: a persistent verification review wins over the long gap — the continuation is not offered.
        "LONG_GAP,NEEDS_REVIEW,false,SOURCE_EVENT_MISSING,STALE,LAST_VERIFICATION_NEEDS_REVIEW,NONE,REVIEW_REQUIRED,"
            + "VERIFICATION_REVIEW_REQUIRED,NONE",
        "LONG_GAP,NEEDS_REVIEW,false,HISTORY_OUT_OF_RANGE,STALE,LAST_VERIFICATION_NEEDS_REVIEW,NONE,UNUSABLE,"
            + "HISTORY_OUT_OF_RANGE,NONE",
        "LONG_GAP,NEEDS_REVIEW,false,,STALE,LONG_GAP,CHOOSE_CONTINUATION,REVIEW_REQUIRED,SYNC_REVIEW_REQUIRED,VERIFY",
    })
    void theSyncFactsDecideAStaleCauseAndAReviewOverRecentTimes(String availability, String latestStatus,
            boolean syncing, String verificationReview, String state, String cause, String action, String reliability,
            String reliabilityCause, String reliabilityAction) {
        var trust = DashboardTrust.judge(new DashboardTrust.Input(new DashboardTrust.Freshness(syncing, true, true),
                "CONFIRMED", false, 0, MEASURED, VERIFIED, BASIS, metrics("CONFIRMED", "OFFICIAL"),
                new DashboardTrust.SyncFacts(availability, latestStatus, verificationReview))).orElseThrow();
        assertThat(trust.freshness().state()).isEqualTo(state);
        assertThat(trust.freshness().cause()).isEqualTo(cause);
        assertThat(trust.freshness().nextAction()).isEqualTo(action);
        assertThat(DashboardTrust.nextAction(trust)).as("the screen's action is the freshness axis's").isEqualTo(action);
        assertThat(trust.freshness().affectedMetrics()).isEqualTo(state.equals("FRESH") ? List.of() : SIX);
        assertThat(trust.inputReliability().state()).isEqualTo(reliability);
        assertThat(trust.inputReliability().cause()).isEqualTo(reliabilityCause);
        assertThat(trust.inputReliability().lastNormalAt()).isEqualTo(VERIFIED);
        assertThat(trust.inputReliability().affectedMetrics())
                .isEqualTo(reliability.equals("CONFIRMED") ? List.of() : SIX);
        assertThat(trust.inputReliability().nextAction()).isEqualTo(reliabilityAction);
    }

    /**
     * R5: a sync's review asks for a precision verification only when one can run (now or after its cooldown); in a
     * long gap it asks for the continuation, otherwise for nothing — on both axes. R6: a sync held by a persistent
     * verification review is named by that review, not as "no sync can run"; the guard's own reasons still are.
     */
    @ParameterizedTest(name = "sync {0}/{1}, verification {2}, review {3}")
    @CsvSource({
        "AVAILABLE,,AVAILABLE,,LAST_SYNC_NEEDS_REVIEW,VERIFY,SYNC_REVIEW_REQUIRED,VERIFY",
        "COOLDOWN,,COOLDOWN,,LAST_SYNC_NEEDS_REVIEW,VERIFY,SYNC_REVIEW_REQUIRED,VERIFY",
        "AVAILABLE,,UNAVAILABLE,,LAST_SYNC_NEEDS_REVIEW,NONE,SYNC_REVIEW_REQUIRED,NONE",
        "COOLDOWN,,LONG_GAP,,LAST_SYNC_NEEDS_REVIEW,CHOOSE_CONTINUATION,SYNC_REVIEW_REQUIRED,CHOOSE_CONTINUATION",
        "UNAVAILABLE,RESTRICTED,UNAVAILABLE,,SYNC_UNAVAILABLE,NONE,SYNC_REVIEW_REQUIRED,NONE",
        "UNAVAILABLE,REVIEW_HELD,NOT_USEFUL,SOURCE_EVENT_MISSING,LAST_VERIFICATION_NEEDS_REVIEW,NONE,"
            + "VERIFICATION_REVIEW_REQUIRED,NONE",
        "UNAVAILABLE,RESTRICTED,UNAVAILABLE,SOURCE_EVENT_MISSING,SYNC_UNAVAILABLE,NONE,VERIFICATION_REVIEW_REQUIRED,NONE",
    })
    void aSyncReviewAsksOnlyForWhatCanRun(String availability, String reason, String verification, String review,
            String freshnessCause, String freshnessAction, String reliabilityCause, String reliabilityAction) {
        var trust = DashboardTrust.judge(new DashboardTrust.Input(new DashboardTrust.Freshness(false, true, true),
                "CONFIRMED", false, 0, MEASURED, VERIFIED, BASIS, metrics("CONFIRMED", "OFFICIAL"),
                new DashboardTrust.SyncFacts(availability, reason, "NEEDS_REVIEW", review, verification))).orElseThrow();
        assertThat(trust.freshness().cause()).isEqualTo(freshnessCause);
        assertThat(trust.freshness().nextAction()).isEqualTo(freshnessAction);
        assertThat(trust.inputReliability().cause()).isEqualTo(reliabilityCause);
        assertThat(trust.inputReliability().nextAction()).isEqualTo(reliabilityAction);
    }

    /**
     * Story 2.13: an ended plan's record folds freshness into {@code ENDED} — all six metrics, no next action — and
     * judges the input reliability and the nature from the version alone; no sync fact can reach them, so the record
     * says nothing about a sync that will never run. An unknown reliability is still not judged.
     */
    @ParameterizedTest(name = "{0} + {1}")
    @CsvSource({"CONFIRMED,OFFICIAL,CONFIRMED,INPUTS_VERIFIED,OFFICIAL", "CONFIRMED,ESTIMATED,CONFIRMED,INPUTS_VERIFIED,"
            + "ESTIMATED", "REVIEW_REQUIRED,NOT_COMPUTABLE,REVIEW_REQUIRED,TARGET_NOT_POSITIVE,NOT_COMPUTABLE"})
    void anEndedPlansRecordIsJudgedFromItsVersionAlone(String input, String nature, String reliabilityState,
            String reliabilityCause, String natureState) {
        var trust = DashboardTrust.judgeEnded(input, nature.equals("ESTIMATED"), nature.equals("ESTIMATED") ? 1 : 0,
                MEASURED, VERIFIED, BASIS, metrics(input, nature), null).orElseThrow();
        assertThat(trust.freshness()).isEqualTo(new com.bokrilog.sync.SyncJobApi.TrustAxis("ENDED", "PLAN_ENDED_EARLY",
                MEASURED, SIX, "NONE"));
        assertThat(trust.inputReliability().state()).isEqualTo(reliabilityState);
        assertThat(trust.inputReliability().cause()).isEqualTo(reliabilityCause);
        assertThat(trust.inputReliability().nextAction()).isEqualTo("NONE");
        assertThat(trust.inputReliability().lastNormalAt()).isEqualTo(VERIFIED);
        assertThat(trust.calculationNature().axis().state()).isEqualTo(natureState);
        assertThat(trust.calculationNature().axis().lastNormalAt()).isEqualTo(BASIS);
        assertThat(DashboardTrust.nextAction(trust)).isEqualTo("NONE");
        assertThat(DashboardTrust.judgeEnded("UNKNOWN", false, 0, MEASURED, VERIFIED, BASIS,
                metrics("CONFIRMED", "OFFICIAL"), null)).isEmpty();
    }

    /**
     * B1: the version's own persistent verification review (Story 2.12) still holds the ended record's inputs — the
     * same states and causes as on the ready dashboard, with no next action. Freshness stays ENDED.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"SOURCE_EVENT_MISSING,REVIEW_REQUIRED,VERIFICATION_REVIEW_REQUIRED",
        "CONVERSION_REQUIRES_REVIEW,REVIEW_REQUIRED,VERIFICATION_REVIEW_REQUIRED",
        "HISTORY_OUT_OF_RANGE,UNUSABLE,HISTORY_OUT_OF_RANGE"})
    void anEndedRecordKeepsItsVersionsPersistentReview(String review, String state, String cause) {
        var trust = DashboardTrust.judgeEnded("CONFIRMED", false, 0, MEASURED, VERIFIED, BASIS,
                metrics("CONFIRMED", "OFFICIAL"), review).orElseThrow();
        assertThat(trust.inputReliability()).isEqualTo(new com.bokrilog.sync.SyncJobApi.TrustAxis(state, cause, VERIFIED,
                SIX, "NONE"));
        assertThat(trust.freshness().state()).isEqualTo("ENDED");
        assertThat(DashboardTrust.nextAction(trust)).isEqualTo("NONE");
    }

    /** B3: the end time (the clock to the millisecond) before the measurement or the verification is too recent. */
    @Test
    void aResultAheadOfTheClockIsTooRecent() {
        Instant measured = Instant.parse("2027-01-04T01:00:00.500Z");
        assertThat(DashboardTrust.resultTooRecent(measured, measured, measured)).isFalse();
        assertThat(DashboardTrust.resultTooRecent(measured.plusNanos(999_999), measured, measured)).isFalse();
        assertThat(DashboardTrust.resultTooRecent(measured.minusNanos(1), measured, measured.minusSeconds(1))).isTrue();
        assertThat(DashboardTrust.resultTooRecent(measured, measured.minusSeconds(1), measured.plusMillis(1))).isTrue();
    }

    /**
     * Story 2.13: whether a ready plan can be ended now, in the end's own order — the restriction, then the database
     * clock at {@code T} (to the millisecond), then a result too recent to end on, then the long gap (the new-plan
     * choice only).
     */
    @Test
    void thePlanEndAvailabilityFollowsTheEndsOrder() {
        Instant end = Instant.parse("2027-02-01T00:00:00Z");
        Instant before = end.minusMillis(1);
        assertThat(DashboardTrust.planEnd(false, before, end, false, false))
                .isEqualTo(new com.bokrilog.sync.SyncJobApi.PlanEndAvailability("AVAILABLE", null));
        assertThat(DashboardTrust.planEnd(false, before, end, false, true))
                .isEqualTo(new com.bokrilog.sync.SyncJobApi.PlanEndAvailability("NEW_PLAN_CHOICE", null));
        assertThat(DashboardTrust.planEnd(false, before, end, true, true))
                .isEqualTo(new com.bokrilog.sync.SyncJobApi.PlanEndAvailability("UNAVAILABLE", "RESULT_TOO_RECENT"));
        assertThat(DashboardTrust.planEnd(false, end, end, true, true))
                .isEqualTo(new com.bokrilog.sync.SyncJobApi.PlanEndAvailability("UNAVAILABLE", "PLAN_PERIOD_ENDED"));
        assertThat(DashboardTrust.planEnd(true, end, end, true, true))
                .isEqualTo(new com.bokrilog.sync.SyncJobApi.PlanEndAvailability("UNAVAILABLE", "RESTRICTED"));
        assertThat(DashboardTrust.planEnd(true, before, end, false, false))
                .isEqualTo(new com.bokrilog.sync.SyncJobApi.PlanEndAvailability("UNAVAILABLE", "RESTRICTED"));
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
