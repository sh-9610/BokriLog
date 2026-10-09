package com.bokrilog.sync.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.PlanPublicationPort;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Whether an incremental sync may run (Story 2.11), judged the same way at admission, in the dashboard read, at
 * every stage and inside the publishing transaction. It reads only the public APIs of the other modules and this
 * module's own pointer; it opens no transaction — it joins the caller's.
 *
 * <p>The base is the plan's current official version: its plan, pointer, calculation and lineage. A sync runs on
 * the plan's own connection and membership — the ones its calculation recorded — and nothing else.
 */
@Component
class PlanSyncGuard {
    static final String RESTRICTED = "RESTRICTED";
    static final String CONNECTION_UNAVAILABLE = "CONNECTION_UNAVAILABLE";
    /**
     * The plan's own connection was revoked (Story 2.16): no new data reaches it any more. A cause of its own beside
     * {@link #CONNECTION_UNAVAILABLE}, so the dashboard can say why the values stop instead of a generic cause.
     */
    static final String CONNECTION_REVOKED = "CONNECTION_REVOKED";
    static final String PLAN_PERIOD_ENDED = "PLAN_PERIOD_ENDED";
    /** The closed codes a running attempt ends with when {@link #blocked} stops it. */
    static final String NOT_PERMITTED = "NOT_PERMITTED";
    static final String PLAN_CHANGED = "PLAN_CHANGED";
    static final String POINTER_CONFLICT = "POINTER_CONFLICT";
    static final String MEMBERSHIP_CHANGED = "MEMBERSHIP_CHANGED";

    private final PlanPublicationRepository publications;
    private final SourceConnectionApi sources;
    private final PortfolioMembershipApi portfolios;
    private final AccountAccessApi accounts;
    private final OperationalRestrictionApi restrictions;
    private final PlanPublicationPort plans;

    PlanSyncGuard(PlanPublicationRepository publications, SourceConnectionApi sources, PortfolioMembershipApi portfolios,
            AccountAccessApi accounts, OperationalRestrictionApi restrictions, PlanPublicationPort plans) {
        this.publications = publications;
        this.sources = sources;
        this.portfolios = portfolios;
        this.accounts = accounts;
        this.restrictions = restrictions;
        this.plans = plans;
    }

    /** The plan's current official version, read through its pointer. */
    record Base(PlanPublicationPort.ActivePlan plan, PlanPublicationRepository.Pointer pointer,
            PlanPublicationRepository.CalculationView calculation, PlanPublicationRepository.Lineage lineage) {}

    /** The active plan with its pointer and the version it cites, or empty when there is none to sync. */
    Optional<Base> base(UUID user) {
        var plan = plans.activePlan(user).orElse(null);
        if (plan == null) return Optional.empty();
        var pointer = publications.pointer(user, plan.planId()).orElse(null);
        if (pointer == null) return Optional.empty();
        var calculation = publications.calculation(user, pointer.calculationId()).orElse(null);
        if (calculation == null || !PlanPublicationService.belongs(calculation, plan, pointer)) return Optional.empty();
        return publications.lineage(user, pointer.calculationId()).map(lineage ->
                new Base(plan, pointer, calculation, lineage));
    }

    /**
     * A plan that reached its end and completed (Story 2.14): its base — the current pointer, the version it cites and
     * that version's lineage, as a sync reads them — and its completion record.
     */
    record CompletedBase(Base base, PlanPublicationPort.PlanCompletion completion) {}

    /**
     * The user's most recent plan when it completed, with its pointer, version, lineage and completion record; empty
     * when the most recent plan is not a completed one, or any of them is missing or does not belong to it.
     */
    Optional<CompletedBase> completedBase(UUID user) {
        var latest = plans.latestPlan(user).orElse(null);
        if (latest == null || !PlanPublicationService.COMPLETED.equals(latest.status()) || latest.completion() == null)
            return Optional.empty();
        var plan = latest.plan();
        var pointer = publications.pointer(user, plan.planId()).orElse(null);
        if (pointer == null) return Optional.empty();
        var calculation = publications.calculation(user, pointer.calculationId()).orElse(null);
        if (calculation == null || !PlanPublicationService.belongs(calculation, plan, pointer)) return Optional.empty();
        var completion = latest.completion();
        return publications.lineage(user, pointer.calculationId()).map(lineage ->
                new CompletedBase(new Base(plan, pointer, calculation, lineage), completion));
    }

    /**
     * Why no completion or post-end attempt can run now (Story 2.14), or {@code null}: the guard's own reasons without
     * the plan's end — a restriction, or a connection or membership that is not the plan's own any more.
     */
    @Nullable String completionUnavailableReason(UUID user, UUID planId, UUID membershipId, UUID portfolioId,
            UUID connectionId, UUID providerAccountId) {
        if (syncRestricted(planId, portfolioId, connectionId)) return RESTRICTED;
        if (!sameConnection(user, connectionId, providerAccountId)
                || !sameMembership(user, membershipId, portfolioId, connectionId, providerAccountId))
            return connectionCause(user, connectionId);
        return null;
    }

    /** The account's own write access. */
    boolean writable(UUID user) {
        return accounts.decide(user, user, AccountAccessKind.WRITE) == AccessDecision.ALLOWED;
    }

    /**
     * 동기화 제한이 이 계획의 동기화를 막는가(Story 3.2) — {@code SYNC}가 그 계획, portfolio, 연결, 또는 전역에 걸렸는가.
     * 접수·lease·모든 단계·발행이 읽는다. 판독 실패는 예외다(fail-closed).
     */
    boolean syncRestricted(UUID planId, UUID portfolioId, UUID connectionId) {
        return restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC), subject(planId, portfolioId, connectionId));
    }

    /** 작업의 {@link #syncRestricted}: 접수된 계획·portfolio·연결로 본다. */
    boolean syncRestricted(PlanSyncRepository.Lease lease) {
        return syncRestricted(lease.planId(), lease.portfolioId(), lease.connectionId());
    }

    /**
     * 증분 발행을 제한이 막는가(Story 3.2) — 동기화, 또는 발행할 계산의 표시가 그 계획·portfolio·연결·계산 정책
     * ({@code CALC-1.0})에 걸렸는가. 어느 쪽이든 걸려 있으면 공식 포인터를 옮기지 않는다.
     */
    boolean publicationRestricted(PlanSyncRepository.Lease lease) {
        return restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC, RestrictionFeature.CALCULATION_DISPLAY),
                subject(lease.planId(), lease.portfolioId(), lease.connectionId())
                        .withCalculationPolicy(PlanCalculator.CALCULATION_POLICY));
    }

    private static RestrictionSubject subject(UUID planId, UUID portfolioId, UUID connectionId) {
        return RestrictionSubject.none().withPlan(planId).withPortfolio(portfolioId).withConnection(connectionId);
    }

    /**
     * Why no sync can run now, or {@code null}: a restriction, a connection or membership that is not the plan's
     * own any more (gone, replaced or ended), or the plan's end reached on the database clock.
     */
    @Nullable String unavailableReason(UUID user, UUID planId, UUID membershipId, UUID portfolioId, UUID connectionId,
            UUID providerAccountId, Instant endAt, Instant now) {
        if (syncRestricted(planId, portfolioId, connectionId)) return RESTRICTED;
        if (!sameConnection(user, connectionId, providerAccountId)
                || !sameMembership(user, membershipId, portfolioId, connectionId, providerAccountId))
            return connectionCause(user, connectionId);
        if (!now.isBefore(endAt)) return PLAN_PERIOD_ENDED;
        return null;
    }

    /** Why the plan's connection is not usable: revoked by the user (Story 2.16), or otherwise gone or replaced. */
    private String connectionCause(UUID user, UUID connectionId) {
        return sources.isRevoked(user, connectionId) ? CONNECTION_REVOKED : CONNECTION_UNAVAILABLE;
    }

    boolean sameConnection(UUID user, UUID connectionId, UUID providerAccountId) {
        var connection = sources.snapshotConnection(user).orElse(null);
        return connection != null && connection.connectionId().equals(connectionId)
                && connection.providerAccountId().equals(providerAccountId);
    }

    boolean sameMembership(UUID user, UUID membershipId, UUID portfolioId, UUID connectionId, UUID providerAccountId) {
        var membership = portfolios.activeSource(user).orElse(null);
        return membership != null && membership.membershipId().equals(membershipId)
                && membership.portfolioId().equals(portfolioId) && membership.connectionId().equals(connectionId)
                && membership.providerAccountId().equals(providerAccountId);
    }

    /**
     * Whether a running attempt may go on, or the closed code it ends with: the account's write access or a
     * restriction ({@code NOT_PERMITTED}), a plan that is not the attempt's any more ({@code PLAN_CHANGED}), a
     * pointer that moved from the attempt's base ({@code POINTER_CONFLICT}), a connection that was removed or
     * replaced ({@code NOT_PERMITTED}), a membership that ended ({@code MEMBERSHIP_CHANGED}), or the plan's end
     * reached ({@code PLAN_PERIOD_ENDED}). Deletion and restriction come first: they win over a late answer.
     */
    @Nullable String blocked(PlanSyncRepository.Lease lease) {
        UUID user = lease.userId();
        if (!writable(user) || syncRestricted(lease)) return NOT_PERMITTED;
        if (lease.completing() || lease.postEnd()) return completedBlocked(lease);
        var plan = plans.activePlan(user).orElse(null);
        if (plan == null) {
            // The plan completed under this attempt (Story 2.14): its period ended, which is what stops it.
            var latest = plans.latestPlan(user).orElse(null);
            return latest != null && latest.plan().planId().equals(lease.planId())
                    && PlanPublicationService.COMPLETED.equals(latest.status()) ? PLAN_PERIOD_ENDED : PLAN_CHANGED;
        }
        if (!plan.planId().equals(lease.planId())) return PLAN_CHANGED;
        var pointer = publications.pointer(user, lease.planId()).orElse(null);
        if (pointer == null || pointer.version() != lease.basePointerVersion()
                || !pointer.calculationId().equals(lease.baseCalculationId()))
            return POINTER_CONFLICT;
        if (!sameConnection(user, lease.connectionId(), lease.providerAccountId())) return NOT_PERMITTED;
        if (!sameMembership(user, lease.membershipId(), lease.portfolioId(), lease.connectionId(),
                lease.providerAccountId()))
            return MEMBERSHIP_CHANGED;
        if (!publications.databaseNow().isBefore(lease.endAt())) return PLAN_PERIOD_ENDED;
        return null;
    }

    /**
     * {@link #blocked} for a completion or post-end attempt (Story 2.14): the plan must be the user's most recent plan
     * and completed, its pointer still the attempt's base, and its connection and membership still the plan's own. The
     * plan's end is what these attempts are for, so it never stops them.
     */
    private @Nullable String completedBlocked(PlanSyncRepository.Lease lease) {
        UUID user = lease.userId();
        var latest = plans.latestPlan(user).orElse(null);
        if (latest == null || !latest.plan().planId().equals(lease.planId())
                || !PlanPublicationService.COMPLETED.equals(latest.status()) || latest.completion() == null)
            return PLAN_CHANGED;
        var pointer = publications.pointer(user, lease.planId()).orElse(null);
        if (pointer == null || pointer.version() != lease.basePointerVersion()
                || !pointer.calculationId().equals(lease.baseCalculationId()))
            return POINTER_CONFLICT;
        if (!sameConnection(user, lease.connectionId(), lease.providerAccountId())) return NOT_PERMITTED;
        if (!sameMembership(user, lease.membershipId(), lease.portfolioId(), lease.connectionId(),
                lease.providerAccountId()))
            return MEMBERSHIP_CHANGED;
        return null;
    }
}
