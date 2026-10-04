package com.bokrilog.plan.internal;

import com.bokrilog.plan.PlanEndApi;
import com.bokrilog.sync.PlanPublicationPort;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The natural completion of an active plan (Story 2.14): the plan module's one state change and the completion record.
 *
 * <p>Sync's admission and its completion attempt call these through {@link PlanPublicationPort}; every command joins
 * the caller's transaction only, after the caller took the publishers' lock order (the plan's advisory lock, then the
 * account lifecycle row). The transition locks the plan row and judges {@code T} again against the database clock, so
 * an early end (Story 2.13) and a completion that race are told apart under the same locks and only one terminal
 * status is written ({@code WHERE status='ACTIVE'}).
 *
 * <p>The comparison is apart from the plan status. It starts {@code PENDING} (or {@code UNAVAILABLE} after a data gap)
 * and is judged to {@code CONFIRMED}, {@code UNAVAILABLE} or {@code HELD}; only {@code PENDING} and {@code HELD} are
 * judged again, so {@code CONFIRMED} and {@code UNAVAILABLE} are final. The first observation is fixed once and never
 * replaced. Every judgment is checked here against its pairing rules — the reason of its state, and a completion version
 * exactly for a judgment on complete inputs — before V14's checks would refuse it.
 */
@Service
class PlanCompletionService {
    /** What a judgment may write: anything but {@code PENDING}, the state before one. */
    private static final Set<PlanEndApi.Comparison> JUDGED = EnumSet.of(PlanEndApi.Comparison.CONFIRMED,
            PlanEndApi.Comparison.UNAVAILABLE, PlanEndApi.Comparison.HELD);
    /** The reasons that leave a comparison not computable after complete inputs: a completion version exists. */
    private static final Set<PlanEndApi.CompletionReason> WITH_VERSION = EnumSet.of(
            PlanEndApi.CompletionReason.OBSERVATION_DELAY_EXCEEDED, PlanEndApi.CompletionReason.POST_END_CASHFLOW);

    private final PlanCompletionRepository completions;

    PlanCompletionService(PlanCompletionRepository completions) {
        this.completions = completions;
    }

    Optional<PlanPublicationPort.PlanCompletion> completePlan(UUID userId, UUID planId, boolean dataGap) {
        requireCallerTransaction();
        if (!this.completions.lockActiveAtEnd(userId, planId)) return Optional.empty();
        if (this.completions.completePlan(userId, planId) != 1)
            throw new IllegalStateException("the plan changed under its row lock");
        if (this.completions.insert(userId, planId, dataGap) != 1)
            throw new IllegalStateException("the completion record was not written");
        return this.completions.find(userId, planId, false);
    }

    boolean recordObservation(UUID userId, UUID planId, UUID snapshotId, String value, Instant measuredAt) {
        requireCallerTransaction();
        return this.completions.recordObservation(userId, planId, snapshotId, value, measuredAt) == 1;
    }

    boolean recordComparison(UUID userId, UUID planId, String comparison, @Nullable String reason,
            @Nullable UUID calculationId, @Nullable Integer postEndCashflowCount) {
        requireCallerTransaction();
        PlanEndApi.Comparison judged = PlanEndApi.Comparison.valueOf(comparison);
        if (!JUDGED.contains(judged)) throw new IllegalArgumentException("a judgment does not write PENDING");
        PlanEndApi.CompletionReason why = reason == null ? null : PlanEndApi.CompletionReason.valueOf(reason);
        boolean paired = switch (judged) {
            case CONFIRMED -> why == null && calculationId != null;
            case HELD -> why == PlanEndApi.CompletionReason.INPUT_REVIEW_REQUIRED && calculationId == null;
            case UNAVAILABLE -> why != null && why != PlanEndApi.CompletionReason.INPUT_REVIEW_REQUIRED
                    && WITH_VERSION.contains(why) == (calculationId != null);
            case PENDING -> false;
        };
        if (!paired || (calculationId == null) != (postEndCashflowCount == null)
                || (postEndCashflowCount != null && postEndCashflowCount < 0))
            throw new IllegalArgumentException("the comparison, its reason and its version do not pair");
        return this.completions.recordComparison(userId, planId, comparison, reason, calculationId,
                postEndCashflowCount) == 1;
    }

    Optional<PlanPublicationPort.PlanCompletion> completion(UUID userId, UUID planId, boolean forUpdate) {
        if (forUpdate) requireCallerTransaction();
        return this.completions.find(userId, planId, forUpdate);
    }

    private static void requireCallerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("the plan completion joins the caller's transaction");
    }
}
