package com.bokrilog.plan.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.plan.PlanEndApi;
import com.bokrilog.plan.PlanEndApi.Reason;
import com.bokrilog.plan.PlanEndApi.Refusal;
import com.bokrilog.sync.SyncJobApi.PlanEndBasis;
import com.bokrilog.sync.SyncJobApi.PlanEndBasisRead;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The early end's eligibility as a pure function (Story 2.13 AC-1): the one order of its refusals, the millisecond
 * boundary of {@code T}, the reference and the long gap — and the canonical confirmation phrase and request hash the
 * grant and the idempotency key are bound to.
 */
class PlanEndRulesTest {
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CALCULATION = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final Instant MEASURED = Instant.parse("2027-01-10T00:00:00Z");
    private static final Instant END = Instant.parse("2027-02-01T00:00:00Z");

    private static PlanEndApi.Request request(Reason reason) {
        return new PlanEndApi.Request(reason, PLAN, 3, CALCULATION);
    }

    private static PlanEndBasis basis(Instant now, boolean longGap) {
        return new PlanEndBasis(PLAN, 3, CALCULATION, SNAPSHOT, MEASURED, MEASURED, END, now, longGap, false);
    }

    private static Optional<Refusal> judge(Reason reason, boolean writable, boolean active,
            @Nullable PlanEndBasisRead basis, boolean restricted) {
        return PlanEndRules.judge(request(reason), new PlanEndRules.Facts(writable, active, basis, restricted));
    }

    @Test
    void anEligibleRequestIsNotRefused() {
        assertThat(judge(Reason.USER_REQUEST, true, true, basis(MEASURED.plusSeconds(60), false), false)).isEmpty();
        assertThat(judge(Reason.LONG_GAP_NEW_PLAN, true, true, basis(MEASURED.plusSeconds(60), true), false)).isEmpty();
    }

    /** Write access, the plan, the reference, unavailability, then the reason: the first that fails answers. */
    @Test
    void refusalsComeInTheOneOrder() {
        PlanEndBasis everythingWrong = new PlanEndBasis(PLAN, 4, CALCULATION, SNAPSHOT, MEASURED, MEASURED, END,
                END.plusSeconds(1), true, true);
        assertThat(judge(Reason.LONG_GAP_NEW_PLAN, false, true, everythingWrong, true))
                .contains(new Refusal("NOT_PERMITTED", null));
        assertThat(judge(Reason.LONG_GAP_NEW_PLAN, true, false, null, true)).contains(new Refusal("PLAN_NOT_FOUND", null));
        assertThat(judge(Reason.USER_REQUEST, true, true, new PlanEndBasisRead.Unavailable(), true))
                .contains(new Refusal("PLAN_END_UNAVAILABLE", "RESULT_UNAVAILABLE"));
        assertThat(judge(Reason.USER_REQUEST, true, true, everythingWrong, true))
                .contains(new Refusal("REFERENCE_STALE", null));
        PlanEndBasis reached = new PlanEndBasis(PLAN, 3, CALCULATION, SNAPSHOT, MEASURED, MEASURED, END,
                END.plusSeconds(1), true, true);
        assertThat(judge(Reason.USER_REQUEST, true, true, reached, true))
                .contains(new Refusal("PLAN_END_UNAVAILABLE", "RESTRICTED"));
        assertThat(judge(Reason.USER_REQUEST, true, true, reached, false))
                .contains(new Refusal("PLAN_END_UNAVAILABLE", "PLAN_PERIOD_ENDED"));
        PlanEndBasis tooRecent = new PlanEndBasis(PLAN, 3, CALCULATION, SNAPSHOT, MEASURED, MEASURED, END,
                MEASURED.plusSeconds(1), true, true);
        assertThat(judge(Reason.USER_REQUEST, true, true, tooRecent, false))
                .contains(new Refusal("PLAN_END_UNAVAILABLE", "RESULT_TOO_RECENT"));
        assertThat(judge(Reason.USER_REQUEST, true, true, basis(MEASURED.plusSeconds(1), true), false))
                .contains(new Refusal("LONG_GAP", null));
        assertThat(judge(Reason.LONG_GAP_NEW_PLAN, true, true, basis(MEASURED.plusSeconds(1), false), false))
                .contains(new Refusal("NOT_LONG_GAP", null));
    }

    /** {@code serverNow < T} to the millisecond: one millisecond before T is eligible, T itself is not. */
    @Test
    void thePlanEndBoundaryIsExclusiveToTheMillisecond() {
        assertThat(judge(Reason.USER_REQUEST, true, true, basis(END.minusMillis(1), false), false)).isEmpty();
        assertThat(judge(Reason.USER_REQUEST, true, true, basis(END, false), false))
                .contains(new Refusal("PLAN_END_UNAVAILABLE", "PLAN_PERIOD_ENDED"));
        // A database clock with microseconds just before T is still before it.
        assertThat(judge(Reason.USER_REQUEST, true, true, basis(END.minusNanos(1_000), false), false)).isEmpty();
    }

    /** Any of the three reference values that is not the pointer's is stale. */
    @Test
    void aMovedPointerIsStale() {
        Instant now = MEASURED.plusSeconds(60);
        for (PlanEndBasis moved : Set.of(
                new PlanEndBasis(PLAN, 2, CALCULATION, SNAPSHOT, MEASURED, MEASURED, END, now, false, false),
                new PlanEndBasis(PLAN, 3, UUID.randomUUID(), SNAPSHOT, MEASURED, MEASURED, END, now, false, false),
                new PlanEndBasis(UUID.randomUUID(), 3, CALCULATION, SNAPSHOT, MEASURED, MEASURED, END, now, false,
                        false)))
            assertThat(judge(Reason.USER_REQUEST, true, true, moved, false)).as(moved.toString())
                    .contains(new Refusal("REFERENCE_STALE", null));
    }

    /**
     * A result too recent to end on (its measurement or verification ahead of the database clock — sync's judgment) is
     * its own unavailability, not a stale reference: the dashboard says the same, and a moment later it is eligible.
     */
    @Test
    void aResultAheadOfTheClockIsTooRecentNotStale() {
        Instant now = MEASURED.plusSeconds(60);
        assertThat(judge(Reason.USER_REQUEST, true, true, new PlanEndBasis(PLAN, 3, CALCULATION, SNAPSHOT, MEASURED,
                MEASURED, END, now, false, true), false)).contains(new Refusal("PLAN_END_UNAVAILABLE", "RESULT_TOO_RECENT"));
        assertThat(judge(Reason.USER_REQUEST, true, true, new PlanEndBasis(PLAN, 3, CALCULATION, SNAPSHOT, MEASURED,
                MEASURED, END, now, false, false), false)).isEmpty();
    }

    /**
     * B4: the plan found active by one read and not active by the basis read (an end committed between them, or any
     * read that names no plan) is the plan's own 404, never a failure — the judgment is total over its facts.
     */
    @Test
    void aPlanEndedBetweenTheTwoReadsIsNotFoundNeverAFailure() {
        assertThat(judge(Reason.USER_REQUEST, true, true, new PlanEndBasisRead.NotActive(), false))
                .contains(new Refusal("PLAN_NOT_FOUND", null));
        assertThat(judge(Reason.USER_REQUEST, true, true, null, false)).contains(new Refusal("PLAN_NOT_FOUND", null));
    }

    @Test
    void theEndTimeIsTheDatabaseClockToTheMillisecondAndTheCompletionFollowsTheReason() {
        assertThat(PlanEndRules.endedAt(Instant.parse("2027-01-10T00:00:00.123456Z")))
                .isEqualTo(Instant.parse("2027-01-10T00:00:00.123Z"));
        assertThat(PlanEndRules.completion(Reason.USER_REQUEST)).isEqualTo(PlanEndApi.Completion.NOT_APPLICABLE);
        assertThat(PlanEndRules.completion(Reason.LONG_GAP_NEW_PLAN))
                .isEqualTo(PlanEndApi.Completion.UNAVAILABLE_DATA_GAP);
    }

    /** The server's canonical phrase carries the reason and the three values; the request hash tells each apart. */
    @Test
    void theConfirmationPhraseAndTheRequestHashAreCanonical() {
        assertThat(request(Reason.USER_REQUEST).confirmation())
                .isEqualTo("plan-end|USER_REQUEST|" + PLAN + "|3|" + CALCULATION);
        assertThat(request(Reason.LONG_GAP_NEW_PLAN).confirmation())
                .isEqualTo("plan-end|LONG_GAP_NEW_PLAN|" + PLAN + "|3|" + CALCULATION);
        String hash = request(Reason.USER_REQUEST).requestHash();
        assertThat(hash).matches("[0-9a-f]{64}").isEqualTo(request(Reason.USER_REQUEST).requestHash());
        Set<String> hashes = new HashSet<>(Set.of(hash, request(Reason.LONG_GAP_NEW_PLAN).requestHash(),
                new PlanEndApi.Request(Reason.USER_REQUEST, UUID.randomUUID(), 3, CALCULATION).requestHash(),
                new PlanEndApi.Request(Reason.USER_REQUEST, PLAN, 4, CALCULATION).requestHash(),
                new PlanEndApi.Request(Reason.USER_REQUEST, PLAN, 3, UUID.randomUUID()).requestHash()));
        assertThat(hashes).hasSize(5);
    }
}
