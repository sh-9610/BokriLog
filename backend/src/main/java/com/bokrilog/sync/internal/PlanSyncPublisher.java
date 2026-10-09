package com.bokrilog.sync.internal;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.internal.PlanPublicationRepository.Candidate;
import com.bokrilog.sync.internal.PlanPublicationRepository.Conversion;
import com.bokrilog.sync.internal.PlanPublicationRepository.Flow;
import com.bokrilog.sync.internal.PlanPublicationRepository.Pinned;
import com.bokrilog.sync.internal.PlanPublicationRepository.PublicationBlocked;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The atomic publication of an incremental sync's next official version (Story 2.11, AD-23).
 *
 * <p>One outer {@code REQUIRED} transaction owned here, with no provider call inside it, in Story 2.7's order:
 * the plan lock, then the account's write lock, then the attempt's lease row; then every condition again —
 * restriction, deletion, the active plan, the base pointer (version and calculation), the portfolio pointer,
 * the membership, the connection, the plan's end and the attempt's own inputs (its own stages' results inside
 * the scope it fixed, else {@code INPUTS_CHANGED}); then the writes — the tc source
 * snapshot, the portfolio snapshot with its pointer CAS, the calculation version continuing the base one with
 * both watermarks, its six metrics and its cash flows, the flow differences from the version it replaced (Story
 * 2.12: added, removed and changed events, every incremental publication), and the plan pointer CAS with its history
 * row; and last a final check and the fenced ending. Anything that fails rolls everything back. The plan row and its
 * intent are never touched.
 */
@Component
class PlanSyncPublisher {
    private final PlanSyncRepository syncs;
    private final PlanPublicationRepository publications;
    private final PortfolioMembershipApi portfolios;
    private final AccountAccessApi accounts;
    private final PlanPublicationPort plans;
    private final PlanSyncGuard guard;
    private final TransactionTemplate transactions;

    PlanSyncPublisher(PlanSyncRepository syncs, PlanPublicationRepository publications, PortfolioMembershipApi portfolios,
            AccountAccessApi accounts, PlanPublicationPort plans, PlanSyncGuard guard, PlatformTransactionManager manager) {
        this.syncs = syncs;
        this.publications = publications;
        this.portfolios = portfolios;
        this.accounts = accounts;
        this.plans = plans;
        this.guard = guard;
        this.transactions = new TransactionTemplate(manager);
    }

    /**
     * The inputs one publication writes, read after the conversion and before the pure calculation.
     *
     * @param pinned the calculator's inputs in Story 2.7's shape: the plan's immutable terms and basis from the
     *     base version, the base version's portfolio pointer as the CAS expectation, and the attempt's own
     *     candidate, classification and conversion with E(tc) and {@code tc}
     * @param lastExtendedAt the version's second watermark: {@code tc} after an extended sync, the base's otherwise
     * @param inputHash {@link #inputHash} of the inputs as read; re-read and compared inside the transaction
     */
    record Prepared(Pinned pinned, Candidate candidate, Conversion conversion, List<Flow> flows,
            String classificationPolicy, Instant lastExtendedAt, String inputHash) {}

    /** The lease was taken over; the late worker publishes nothing and finishes nothing. */
    static final class LeaseLost extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LeaseLost() {
            super(null, null, false, false);
        }
    }

    /**
     * Publishes the next version and ends the attempt as succeeded.
     *
     * @throws PublicationBlocked with the closed code the attempt ends with
     * @throws LeaseLost when the attempt is not this lease's any more
     */
    PlanSyncRepository.Ending publish(PlanSyncRepository.Lease lease, Prepared prepared,
            PlanCalculator.Result.Calculated result) {
        return Objects.requireNonNull(transactions.execute(status -> publishInTransaction(lease, prepared, result)));
    }

    private PlanSyncRepository.Ending publishInTransaction(PlanSyncRepository.Lease lease, Prepared prepared,
            PlanCalculator.Result.Calculated result) {
        UUID user = lease.userId();
        // Story 2.7's lock order: the plan lock, then the account lifecycle. No deadlock with a confirmation.
        plans.lockPlanWrites(user);
        if (!accounts.lockWriteAccess(user)) throw new PublicationBlocked("NOT_PERMITTED");
        if (!syncs.lockOwned(lease)) throw new LeaseLost();
        String blocked = guard.blocked(lease);
        if (blocked != null) throw new PublicationBlocked(blocked);
        // 새 공식 버전은 보이게 될 계산이다(Story 3.2): 그 표시 제한도 발행을 막는다.
        if (guard.publicationRestricted(lease)) throw new PublicationBlocked("NOT_PERMITTED");
        var base = publications.calculation(user, lease.baseCalculationId()).orElse(null);
        if (base == null) throw new PublicationBlocked("POINTER_CONFLICT");
        var portfolio = portfolios.officialValue(user).orElse(null);
        if (portfolio == null || !portfolio.snapshotId().equals(base.portfolioSnapshotId())
                || portfolio.publicationSequence() != base.portfolioSequence())
            throw new PublicationBlocked("POINTER_CONFLICT");
        // The attempt's own inputs, read again: the results of its own stages, inside the scope it fixed, and the
        // same candidate, conversion and flows it calculated from.
        Pinned pinned = prepared.pinned();
        var candidate = publications.candidate(user, pinned.candidateId()).orElse(null);
        var conversion = publications.conversion(user, pinned.conversionId()).orElse(null);
        List<Flow> flows = publications.flows(user, pinned.conversionId());
        if (candidate == null || conversion == null || !ownInputs(lease, pinned, candidate)
                || !inputHash(lease, candidate, pinned.classificationId(), conversion, flows).equals(prepared.inputHash()))
            throw new PublicationBlocked("INPUTS_CHANGED");

        UUID sourceSnapshotId = UUID.randomUUID();
        publications.insertCandidateSource(sourceSnapshotId, candidate);
        var next = portfolios.publishNext(new PortfolioMembershipApi.CandidateComponent(user, lease.portfolioId(),
                lease.membershipId(), lease.connectionId(), lease.providerAccountId(), sourceSnapshotId,
                candidate.equity(), candidate.measuredAt(), candidate.policyVersion()),
                base.portfolioSnapshotId(), base.portfolioSequence());
        var snapshot = switch (next) {
            case PortfolioMembershipApi.PublishedSnapshot published -> published;
            case PortfolioMembershipApi.NextPublication.MembershipChanged changed ->
                    throw new PublicationBlocked("MEMBERSHIP_CHANGED");
            case PortfolioMembershipApi.NextPublication.PointerMoved moved ->
                    throw new PublicationBlocked("POINTER_CONFLICT");
        };

        UUID calculationId = UUID.randomUUID();
        int estimatedFlows = (int) result.appliedCashflows().stream().filter(PlanCalculator.AppliedCashflow::estimated)
                .count();
        publications.insertCalculation(new PlanPublicationRepository.CalculationRow(calculationId, lease.jobId(),
                lease.planId(), pinned, snapshot.snapshotId(), snapshot.publicationSequence(), sourceSnapshotId,
                prepared.classificationPolicy(), conversion.policyVersion(), candidate.policyVersion(), result,
                result.appliedCashflows().size(), estimatedFlows, prepared.lastExtendedAt(), lease.baseCalculationId()));
        Map<String, Flow> bySource = new HashMap<>();
        flows.forEach(flow -> bySource.put(flow.sourceId(), flow));
        for (PlanCalculator.AppliedCashflow applied : result.appliedCashflows()) {
            Flow flow = bySource.get(applied.sourceId());
            if (flow == null) throw new IllegalStateException("applied cash flow without input");
            publications.insertFlow(calculationId, pinned.conversionId(), lease.providerAccountId(), flow, applied);
        }
        // What changed from the base version: every incremental version records it, whatever its purpose.
        publications.insertChanges(user, calculationId, lease.baseCalculationId());
        long version = lease.basePointerVersion() + 1;
        if (publications.pointNext(lease.planId(), user, lease.basePointerVersion(), lease.baseCalculationId(),
                calculationId) != 1)
            throw new PublicationBlocked("POINTER_CONFLICT");
        // The final check after every write: a restriction or deletion that raced in rolls it all back.
        if (guard.publicationRestricted(lease) || !guard.writable(user)) throw new PublicationBlocked("NOT_PERMITTED");
        return syncs.finishSucceeded(lease, calculationId, version).orElseThrow(LeaseLost::new);
    }

    // ---------------------------------------------------------------- Story 2.14

    /** A wallet observation a completion or post-end attempt read: Bybit's value, its times and the source policy. */
    record WalletObservation(String equity, Instant measuredAt, Instant receivedAt, String policyVersion) {}

    /**
     * Publishes the first post-end observation of a completed plan, once (Story 2.14): in Story 2.7's lock order, the
     * observation's source snapshot (its origin is this attempt), the next portfolio snapshot by the portfolio pointer's
     * CAS, the completion record's observation (only while none is fixed) and the attempt's own record of it. After a
     * data gap the attempt ends here, succeeded without a calculation. No calculation version and no plan pointer move.
     *
     * @return the ending when the attempt ended here, {@code null} when it goes on — also when another attempt fixed
     *     the observation first, in which case nothing was written
     * @throws PublicationBlocked with the closed code the attempt ends with
     * @throws LeaseLost when the attempt is not this lease's any more
     */
    PlanSyncRepository.@Nullable Ending publishObservation(PlanSyncRepository.Lease lease, WalletObservation observation,
            boolean endsHere) {
        return transactions.execute(status -> {
            UUID user = lease.userId();
            lockInOrder(lease);
            var completion = plans.completion(user, lease.planId(), true)
                    .orElseThrow(() -> new PublicationBlocked("PLAN_CHANGED"));
            if (completion.observed()) return null;
            var published = publishWallet(lease, observation);
            String value = PlanCalculator.plainDecimal(observation.equity());
            if (value == null || !plans.recordCompletionObservation(user, lease.planId(), published.snapshotId(),
                    value, observation.measuredAt()))
                throw new PublicationBlocked("INPUTS_CHANGED");
            finalCheck(lease);
            return endsHere ? syncs.finishWithoutResult(lease).orElseThrow(LeaseLost::new) : null;
        });
    }

    /**
     * Publishes a completed plan's post-end value (Story 2.14): the wallet observation as the next portfolio snapshot,
     * and nothing else — no history, no calculation, no plan pointer, no completion record. The attempt ends here.
     */
    PlanSyncRepository.Ending publishPostEnd(PlanSyncRepository.Lease lease, WalletObservation observation) {
        return Objects.requireNonNull(transactions.execute(status -> {
            UUID user = lease.userId();
            lockInOrder(lease);
            var completion = plans.completion(user, lease.planId(), false).orElse(null);
            if (completion == null || !completion.observed()) throw new PublicationBlocked("PLAN_CHANGED");
            publishWallet(lease, observation);
            finalCheck(lease);
            return syncs.finishWithoutResult(lease).orElseThrow(LeaseLost::new);
        }));
    }

    /**
     * Publishes the completion version and judges the comparison (Story 2.14, AC-6), in one outer transaction in Story
     * 2.7's lock order: every condition again — the plan completed and still this attempt's base, the comparison still
     * open ({@code PENDING} or {@code HELD}) on the very observation the calculation used, and the attempt's own
     * inputs; then the completion calculation version continuing the base one (its portfolio snapshot and source
     * snapshot are the observation's, its basis {@code T}, its flows verified through {@code U}), its metrics, its
     * applied flows, its change rows, the plan pointer CAS with its history row, and the comparison with its reason,
     * calculation and post-end flow count. Anything that fails rolls everything back: the record stays open, the
     * pointer and the versions as they were.
     */
    PlanSyncRepository.Ending publishCompletion(PlanSyncRepository.Lease lease, Prepared prepared,
            PlanCalculator.Result.Calculated result, CompletionComparison.Judgment judgment, UUID observedSnapshotId,
            Instant endAt) {
        return Objects.requireNonNull(transactions.execute(status -> {
            UUID user = lease.userId();
            lockInOrder(lease);
            var completion = plans.completion(user, lease.planId(), true).orElse(null);
            if (completion == null || !completion.observed()
                    || !observedSnapshotId.equals(completion.observedPortfolioSnapshotId())
                    || !(PlanPublicationService.COMPARISON_PENDING.equals(completion.comparison())
                            || PlanPublicationService.COMPARISON_HELD.equals(completion.comparison())))
                throw new PublicationBlocked("INPUTS_CHANGED");
            var observation = syncs.observation(user, observedSnapshotId).orElse(null);
            if (observation == null || !PlanSyncRepository.COMPLETION.equals(observation.purpose()))
                throw new PublicationBlocked("INPUTS_CHANGED");
            Pinned pinned = prepared.pinned();
            var candidate = publications.candidate(user, pinned.candidateId()).orElse(null);
            var conversion = publications.conversion(user, pinned.conversionId()).orElse(null);
            List<Flow> flows = publications.flows(user, pinned.conversionId());
            if (candidate == null || conversion == null || !ownInputs(lease, pinned, candidate)
                    || !candidate.measuredAt().equals(completion.observedMeasuredAt())
                    || !inputHash(lease, candidate, pinned.classificationId(), conversion, flows)
                            .equals(prepared.inputHash()))
                throw new PublicationBlocked("INPUTS_CHANGED");

            UUID calculationId = UUID.randomUUID();
            int estimatedFlows = (int) result.appliedCashflows().stream()
                    .filter(PlanCalculator.AppliedCashflow::estimated).count();
            publications.insertCalculation(new PlanPublicationRepository.CalculationRow(calculationId, lease.jobId(),
                    lease.planId(), pinned, observation.portfolioSnapshotId(), observation.sequence(),
                    observation.sourceSnapshotId(), prepared.classificationPolicy(), conversion.policyVersion(),
                    candidate.policyVersion(), result, result.appliedCashflows().size(), estimatedFlows,
                    candidate.tc(), lease.baseCalculationId(), PlanPublicationRepository.COMPLETION, endAt,
                    candidate.tc()));
            Map<String, Flow> bySource = new HashMap<>();
            flows.forEach(flow -> bySource.put(flow.sourceId(), flow));
            for (PlanCalculator.AppliedCashflow applied : result.appliedCashflows()) {
                Flow flow = bySource.get(applied.sourceId());
                if (flow == null) throw new IllegalStateException("applied cash flow without input");
                publications.insertFlow(calculationId, pinned.conversionId(), lease.providerAccountId(), flow, applied);
            }
            publications.insertChanges(user, calculationId, lease.baseCalculationId());
            if (publications.pointNext(lease.planId(), user, lease.basePointerVersion(), lease.baseCalculationId(),
                    calculationId) != 1)
                throw new PublicationBlocked("POINTER_CONFLICT");
            if (!plans.recordCompletionComparison(user, lease.planId(), judgment.comparison(), judgment.reason(),
                    calculationId, judgment.postEndCashflowCount()))
                throw new PublicationBlocked("INPUTS_CHANGED");
            finalCheck(lease);
            return syncs.finishSucceeded(lease, calculationId, lease.basePointerVersion() + 1)
                    .orElseThrow(LeaseLost::new);
        }));
    }

    /** Story 2.7's lock order — the plan lock, the account lifecycle, the attempt's lease row — then the guard. */
    private void lockInOrder(PlanSyncRepository.Lease lease) {
        UUID user = lease.userId();
        plans.lockPlanWrites(user);
        if (!accounts.lockWriteAccess(user)) throw new PublicationBlocked("NOT_PERMITTED");
        if (!syncs.lockOwned(lease)) throw new LeaseLost();
        String blocked = guard.blocked(lease);
        if (blocked != null) throw new PublicationBlocked(blocked);
        if (guard.publicationRestricted(lease)) throw new PublicationBlocked("NOT_PERMITTED");
    }

    /** The final check after every write: a restriction or deletion that raced in rolls it all back. */
    private void finalCheck(PlanSyncRepository.Lease lease) {
        if (guard.publicationRestricted(lease) || !guard.writable(lease.userId()))
            throw new PublicationBlocked("NOT_PERMITTED");
    }

    /**
     * The wallet observation as the portfolio's next snapshot, by the CAS on the portfolio's current pointer, and the
     * attempt's own record of it. The observation's source snapshot names this attempt as its origin.
     */
    private PortfolioMembershipApi.PublishedSnapshot publishWallet(PlanSyncRepository.Lease lease,
            WalletObservation observation) {
        UUID user = lease.userId();
        var current = portfolios.officialValue(user).orElseThrow(() -> new PublicationBlocked("POINTER_CONFLICT"));
        UUID sourceSnapshotId = UUID.randomUUID();
        publications.insertObservationSource(sourceSnapshotId, user, lease.connectionId(), lease.providerAccountId(),
                lease.jobId(), observation.equity(), observation.measuredAt(), observation.receivedAt(),
                observation.policyVersion());
        var next = portfolios.publishNext(new PortfolioMembershipApi.CandidateComponent(user, lease.portfolioId(),
                lease.membershipId(), lease.connectionId(), lease.providerAccountId(), sourceSnapshotId,
                observation.equity(), observation.measuredAt(), observation.policyVersion()),
                current.snapshotId(), current.publicationSequence());
        var published = switch (next) {
            case PortfolioMembershipApi.PublishedSnapshot snapshot -> snapshot;
            case PortfolioMembershipApi.NextPublication.MembershipChanged changed ->
                    throw new PublicationBlocked("MEMBERSHIP_CHANGED");
            case PortfolioMembershipApi.NextPublication.PointerMoved moved ->
                    throw new PublicationBlocked("POINTER_CONFLICT");
        };
        if (!syncs.recordObservation(lease, sourceSnapshotId, published.snapshotId(), published.publicationSequence()))
            throw new LeaseLost();
        return published;
    }

    /**
     * Whether the inputs are exactly this attempt's: the candidate of its collection child, the bundle of its
     * classification child and of its conversion child, and a candidate whose window start and {@code tc} are the
     * scope the attempt fixed. A verification's window start is its earliest target's; a continuation's {@code tc}
     * is the {@code tcFinal} its final sweep fixed (Story 2.12) — the attempt row carries both, as a sync's does.
     */
    private boolean ownInputs(PlanSyncRepository.Lease lease, Pinned pinned, Candidate candidate) {
        var scope = syncs.creatingSync(lease.userId(), lease.jobId()).orElse(null);
        var children = syncs.childResults(lease.userId(), lease.jobId()).orElse(null);
        return scope != null && children != null && scope.tc() != null && scope.windowStart() != null
                && pinned.candidateId().equals(children.candidateId())
                && pinned.classificationId().equals(children.classificationId())
                && pinned.conversionId().equals(children.conversionId())
                && scope.tc().equals(candidate.tc()) && scope.tc().equals(pinned.tc())
                && scope.windowStart().equals(candidate.windowStart())
                && lease.baseCandidateId().equals(candidate.baseCandidateId());
    }

    /**
     * The attempt's input hash: the plan and base, the candidate with E(tc) and its completeness, the
     * classification and the calculable conversion, and every flow — named, length-prefixed fields.
     */
    static String inputHash(PlanSyncRepository.Lease lease, Candidate candidate, UUID classificationId,
            Conversion conversion, List<Flow> flows) {
        PlanPublicationService.Fields fields = new PlanPublicationService.Fields("plan-sync-input-1")
                .add("job", lease.jobId()).add("plan", lease.planId()).add("baseCalculation", lease.baseCalculationId())
                .add("baseVersion", lease.basePointerVersion()).add("candidate", candidate.candidateId())
                .add("candidateUser", candidate.userId()).add("candidateIntent", candidate.intentId())
                .add("candidateConnection", candidate.connectionId())
                .add("candidateAccount", candidate.providerAccountId()).add("completeness", candidate.completenessHash())
                .add("base", candidate.baseCandidateId()).add("window", candidate.windowStart())
                .add("t0", candidate.t0().toEpochMilli()).add("tc", candidate.tc().toEpochMilli())
                .add("equity", PlanPublicationService.notation(candidate.equity()))
                .add("measured", candidate.measuredAt().toEpochMilli()).add("candidatePolicy", candidate.policyVersion())
                .add("classification", classificationId).add("conversion", conversion.conversionId())
                .add("conversionClassification", conversion.classificationId())
                .add("conversionFingerprint", conversion.fingerprint()).add("conversionPolicy", conversion.policyVersion())
                .add("conversionStatus", conversion.status()).add("calculable", conversion.calculable())
                .add("flows", flows.size());
        for (Flow flow : flows) {
            fields.add("flowEvent", flow.sourceEventId()).add("flowSource", flow.sourceId())
                    .add("flowSemantic", flow.semanticHash()).add("flowAt", flow.occurredAt().toEpochMilli())
                    .add("flowUsd", PlanPublicationService.notation(flow.usdValue())).add("flowEstimated", flow.estimated())
                    .add("flowEvidence", flow.evidenceType());
        }
        return PlanPublicationService.sha256(fields.toString());
    }
}
