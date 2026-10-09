package com.bokrilog.plan.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.plan.PlanEndApi;
import com.bokrilog.sync.SyncJobApi;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The early end of an active plan (Story 2.13): the plan module's rule and its one state change.
 *
 * <p>The judgment is {@link PlanEndRules}; the official basis — the pointer, the calculation it cites checked as the
 * ready dashboard checks it, the long gap by the sync admission's own function and whether the result is too recent —
 * is read from sync's public API in the caller's transaction (plan already depends on sync, so this is the existing
 * direction). The end itself writes two rows in the caller's transaction: the plan's {@code ACTIVE → ENDED_EARLY} and
 * the end record.
 */
@Service
class PlanEndService implements PlanEndApi {
    private final PlanConditionRepository plans;
    private final PlanEndRepository ends;
    private final AccountAccessApi accounts;
    private final OperationalRestrictionApi restrictions;
    private final SyncJobApi sync;
    /**
     * The preparation's read: read-only and {@code REPEATABLE READ}, without locks, so the plan row and the official
     * basis come from one snapshot — an end committed between them cannot turn the answer into a failure.
     */
    private final TransactionTemplate reads;

    PlanEndService(PlanConditionRepository plans, PlanEndRepository ends, AccountAccessApi accounts,
            OperationalRestrictionApi restrictions, @Lazy SyncJobApi sync, PlatformTransactionManager manager) {
        this.plans = plans;
        this.ends = ends;
        this.accounts = accounts;
        this.restrictions = restrictions;
        this.sync = sync;
        this.reads = new TransactionTemplate(manager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Optional<Refusal> check(@Nullable UUID userId, Request request) {
        if (userId == null) return Optional.of(new Refusal(NOT_PERMITTED, null));
        return Objects.requireNonNull(this.reads.execute(status -> {
            boolean writable = this.accounts.decide(userId, userId, AccountAccessKind.WRITE) == AccessDecision.ALLOWED;
            boolean active = writable && this.ends.activePlan(userId, request.planId(), false);
            var basis = active ? this.sync.planEndBasis(userId, request.planId()) : null;
            return PlanEndRules.judge(request, new PlanEndRules.Facts(writable, active, basis,
                    restricted(request.planId())));
        }));
    }

    @Override
    public Ended end(UUID userId, Request request, String idempotencyKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("the plan end joins the caller's transaction");
        // The publishers' lock order: the plan's advisory lock, then the account lifecycle row, then the plan row.
        this.plans.lockUser(userId);
        boolean writable = this.accounts.lockWriteAccess(userId);
        boolean active = writable && this.ends.activePlan(userId, request.planId(), true);
        SyncJobApi.PlanEndBasisRead basis = active ? this.sync.planEndBasis(userId, request.planId()) : null;
        var refusal = PlanEndRules.judge(request, new PlanEndRules.Facts(writable, active, basis,
                restricted(request.planId()))).orElse(null);
        if (refusal != null) throw new Refused(refusal.code(), refusal.reason());
        // Eligible means the read was a consistent basis (PlanEndRules refuses every other read).
        SyncJobApi.PlanEndBasis official = (SyncJobApi.PlanEndBasis) Objects.requireNonNull(basis);
        Instant endedAt = PlanEndRules.endedAt(official.now());
        if (this.ends.endPlan(userId, request.planId()) != 1)
            throw new IllegalStateException("the plan changed under its row lock");
        Completion completion = PlanEndRules.completion(request.reason());
        boolean gap = completion == Completion.UNAVAILABLE_DATA_GAP;
        this.ends.insert(new PlanEndRepository.EndRow(request.planId(), userId, request.reason(), endedAt,
                official.calculationId(), official.pointerVersion(), official.portfolioSnapshotId(),
                official.measuredAt(), official.cashflowVerifiedThrough(), completion,
                gap ? official.cashflowVerifiedThrough() : null, gap ? endedAt : null, idempotencyKey,
                request.requestHash()));
        return new Ended(request.planId(), request.reason(), endedAt);
    }

    /**
     * 이 계획의 종료를 제한이 막는가(Story 3.2) — 동기화 또는 계산 표시가 그 계획, 계획의 portfolio·연결, 계산 정책에
     * 걸렸는가. 대시보드의 계획 종료 가용성과 같은 기능·차원을 본다. 판독 실패는 예외다(fail-closed).
     */
    private boolean restricted(java.util.UUID planId) {
        RestrictionSubject subject = RestrictionSubject.none().withPlan(planId);
        var target = this.plans.planTarget(planId).orElse(null);
        if (target != null)
            subject = subject.withPortfolio(target.portfolioId()).withConnection(target.connectionId())
                    .withCalculationPolicy(target.calculationPolicy());
        return this.restrictions.blocks(java.util.EnumSet.of(RestrictionFeature.SYNC,
                RestrictionFeature.CALCULATION_DISPLAY), subject);
    }

    @Override
    public Replay replay(@Nullable UUID userId, String idempotencyKey, String requestHash) {
        if (userId == null) return new Replay.None();
        var keyed = this.ends.byKey(userId, idempotencyKey).orElse(null);
        if (keyed == null) return new Replay.None();
        if (!keyed.requestHash().equals(requestHash)) return new Replay.Conflict();
        return new Replay.Stored(new Ended(keyed.planId(), keyed.reason(), keyed.endedAt()));
    }
}
