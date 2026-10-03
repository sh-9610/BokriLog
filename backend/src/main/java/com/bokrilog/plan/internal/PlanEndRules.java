package com.bokrilog.plan.internal;

import com.bokrilog.plan.PlanEndApi;
import com.bokrilog.plan.PlanEndApi.Reason;
import com.bokrilog.plan.PlanEndApi.Refusal;
import com.bokrilog.sync.SyncJobApi.PlanEndBasis;
import com.bokrilog.sync.SyncJobApi.PlanEndBasisRead;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The early end's eligibility (Story 2.13 AC-1), as one pure function of facts the caller read: no database, no clock,
 * no Spring. The preparation judges it without locks; the end judges it again under its locks, with the same order:
 *
 * <ol>
 *   <li>the account's write access — {@code NOT_PERMITTED};
 *   <li>the user's own active plan — {@code PLAN_NOT_FOUND} (another user's, an unknown and an ended plan alike, and a
 *       plan an end committed between the preparation's two reads);
 *   <li>an official result that agrees with itself — else {@code PLAN_END_UNAVAILABLE}/{@code RESULT_UNAVAILABLE}:
 *       without it there is no pointer to compare the reference with, and an end recorded on it could never be shown;
 *   <li>the reference the screen shows still being the official pointer — {@code REFERENCE_STALE};
 *   <li>unavailability: the {@code plan-end} restriction ({@code RESTRICTED}), the database clock at {@code T}
 *       ({@code PLAN_PERIOD_ENDED}), or a result too recent to end on now ({@code RESULT_TOO_RECENT}) —
 *       {@code PLAN_END_UNAVAILABLE} with the reason;
 *   <li>the reason against the long gap — {@code LONG_GAP} for an early end in a long gap, {@code NOT_LONG_GAP} for the
 *       new-plan choice without one.
 * </ol>
 *
 * <p>The too-recent judgment is sync's ({@link PlanEndBasis#resultTooRecent}), the one the dashboard's plan-end part
 * shows: the end time is the database clock to the millisecond ({@link #endedAt}), and a result whose Bybit measurement
 * or {@code tc} lies ahead of it cannot be recorded as "last normal before the end". It is never moved to the end time.
 */
final class PlanEndRules {

    private PlanEndRules() {
    }

    /**
     * What the judgment reads.
     *
     * @param activePlan whether {@code request.planId()} is the user's active plan
     * @param basis sync's read of that plan's official result; {@code null} when the plan was not found active
     * @param restricted whether the {@code plan-end} command is restricted
     */
    record Facts(boolean writable, boolean activePlan, @Nullable PlanEndBasisRead basis, boolean restricted) {}

    /** The refusal, or empty when the request is eligible. Total over its facts: it never throws. */
    static Optional<Refusal> judge(PlanEndApi.Request request, Facts facts) {
        if (!facts.writable()) return refuse(PlanEndApi.NOT_PERMITTED, null);
        PlanEndBasisRead read = facts.basis();
        if (!facts.activePlan() || read == null || read instanceof PlanEndBasisRead.NotActive)
            return refuse(PlanEndApi.PLAN_NOT_FOUND, null);
        if (!(read instanceof PlanEndBasis basis))
            return refuse(PlanEndApi.PLAN_END_UNAVAILABLE, PlanEndApi.RESULT_UNAVAILABLE);
        if (basis.pointerVersion() != request.pointerVersion() || !basis.calculationId().equals(request.calculationId())
                || !basis.planId().equals(request.planId()))
            return refuse(PlanEndApi.REFERENCE_STALE, null);
        if (facts.restricted()) return refuse(PlanEndApi.PLAN_END_UNAVAILABLE, PlanEndApi.RESTRICTED);
        if (!basis.now().isBefore(basis.endAt()))
            return refuse(PlanEndApi.PLAN_END_UNAVAILABLE, PlanEndApi.PLAN_PERIOD_ENDED);
        if (basis.resultTooRecent()) return refuse(PlanEndApi.PLAN_END_UNAVAILABLE, PlanEndApi.RESULT_TOO_RECENT);
        if (request.reason() == Reason.USER_REQUEST && basis.longGap()) return refuse(PlanEndApi.LONG_GAP, null);
        if (request.reason() == Reason.LONG_GAP_NEW_PLAN && !basis.longGap())
            return refuse(PlanEndApi.NOT_LONG_GAP, null);
        return Optional.empty();
    }

    /**
     * The end time: the database clock, truncated to the millisecond (every stored instant is to the millisecond) — the
     * same truncation sync's too-recent judgment uses.
     */
    static Instant endedAt(Instant databaseNow) {
        return databaseNow.truncatedTo(ChronoUnit.MILLIS);
    }

    /** The completion comparison an end leaves: a data gap exactly for the new-plan choice after a long gap. */
    static PlanEndApi.Completion completion(Reason reason) {
        return reason == Reason.LONG_GAP_NEW_PLAN ? PlanEndApi.Completion.UNAVAILABLE_DATA_GAP
                : PlanEndApi.Completion.NOT_APPLICABLE;
    }

    private static Optional<Refusal> refuse(String code, @Nullable String reason) {
        return Optional.of(new Refusal(code, reason));
    }
}
