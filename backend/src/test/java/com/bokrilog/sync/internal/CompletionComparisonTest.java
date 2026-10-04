package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The completion comparison's order (Story 2.14 AC-4, CV-11): the delay first — exactly 24 hours is comparable — then a
 * confirmed external flow in {@code (T, measured]}; otherwise confirmed. Flows at {@code T} or after the observation are
 * not post-end flows of this observation.
 */
class CompletionComparisonTest {
    private static final Instant END = Instant.parse("2027-02-03T00:00:00Z");
    private static final long DAY = Duration.ofDays(1).toMillis();

    @Test
    void exactlyADayIsComparableAndOneMillisecondMoreIsNot() {
        assertThat(CompletionComparison.judge(END, END.plusMillis(DAY), List.of()))
                .isEqualTo(new CompletionComparison.Judgment("CONFIRMED", null, 0));
        assertThat(CompletionComparison.judge(END, END.plusMillis(DAY + 1), List.of()))
                .isEqualTo(new CompletionComparison.Judgment("UNAVAILABLE", "OBSERVATION_DELAY_EXCEEDED", 0));
        assertThat(CompletionComparison.judge(END, END, List.of()).comparable()).isTrue();
    }

    @Test
    void aFlowBetweenTheEndAndTheObservationIsNotComparable() {
        Instant measured = END.plusSeconds(3600);
        assertThat(CompletionComparison.judge(END, measured, List.of(END, END.plusMillis(1), measured,
                measured.plusMillis(1)))).isEqualTo(new CompletionComparison.Judgment("UNAVAILABLE",
                        "POST_END_CASHFLOW", 2));
        // A flow at T is the plan's own; a flow after the observation did not move it.
        assertThat(CompletionComparison.judge(END, measured, List.of(END, measured.plusMillis(1))).comparable()).isTrue();
    }

    @Test
    void theDelayIsJudgedBeforeThePostEndFlows() {
        Instant measured = END.plusMillis(DAY + 1);
        assertThat(CompletionComparison.judge(END, measured, List.of(END.plusSeconds(1))))
                .isEqualTo(new CompletionComparison.Judgment("UNAVAILABLE", "OBSERVATION_DELAY_EXCEEDED", 1));
    }
}
