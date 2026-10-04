package com.bokrilog.sync.internal;

import com.bokrilog.calculation.PlanCalculator;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The completion comparison of a plan that reached its end (Story 2.14, ERD-1.0.0 CV-11), as a pure function of
 * instants. The caller has already established that the inputs are complete (otherwise the comparison is held or not
 * computable for the input's own reason, and no completion version is calculated).
 *
 * <p>The order is the contract's: the observation delay first — more than 24 hours after {@code T} is not comparable,
 * exactly 24 hours is — then a confirmed external flow in {@code (T, measured]}, which moved the value the observation
 * saw; otherwise the comparison is confirmed. The post-end flows are counted whatever the outcome.
 */
final class CompletionComparison {

    /**
     * @param comparison {@code CONFIRMED} or {@code UNAVAILABLE}
     * @param reason {@code OBSERVATION_DELAY_EXCEEDED} or {@code POST_END_CASHFLOW} with {@code UNAVAILABLE}
     * @param postEndCashflowCount the confirmed external flows in {@code (T, measured]}
     */
    record Judgment(String comparison, @Nullable String reason, int postEndCashflowCount) {
        boolean comparable() {
            return PlanPublicationService.COMPARISON_CONFIRMED.equals(this.comparison);
        }
    }

    private CompletionComparison() {
    }

    static Judgment judge(Instant endAt, Instant measuredAt, List<Instant> flowTimes) {
        int postEnd = (int) flowTimes.stream().filter(at -> at.isAfter(endAt) && !at.isAfter(measuredAt)).count();
        if (!PlanCalculator.observationWithinLimit(endAt, measuredAt))
            return new Judgment(PlanPublicationService.COMPARISON_UNAVAILABLE,
                    PlanPublicationService.COMPLETION_DELAY_EXCEEDED, postEnd);
        if (postEnd > 0)
            return new Judgment(PlanPublicationService.COMPARISON_UNAVAILABLE,
                    PlanPublicationService.COMPLETION_POST_END_CASHFLOW, postEnd);
        return new Judgment(PlanPublicationService.COMPARISON_CONFIRMED, null, 0);
    }
}
