package com.bokrilog.sync.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.PlanPublicationPort;
import java.time.Instant;
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
    /** The command name operators restrict to stop incremental syncs. */
    static final String PLAN_SYNC_COMMAND = "plan-sync";

    static final String RESTRICTED = "RESTRICTED";
    static final String CONNECTION_UNAVAILABLE = "CONNECTION_UNAVAILABLE";
    static final String PLAN_PERIOD_ENDED = "PLAN_PERIOD_ENDED";

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

    /** The account's own write access. */
    boolean writable(UUID user) {
        return accounts.decide(user, user, AccountAccessKind.WRITE) == AccessDecision.ALLOWED;
    }

    /**
     * The commands whose restriction stops a sync: its own, and the stages and publication it reuses (the
     * Bybit read, the collection and the publication). The plan-condition command is not one of them — a sync
     * changes no condition.
     */
    boolean restricted() {
        return restrictions.isRestricted(PLAN_SYNC_COMMAND)
                || restrictions.isRestricted(SyncJobService.SYNC_COMMAND)
                || restrictions.isRestricted(SyncJobService.INITIAL_INPUTS_COMMAND)
                || restrictions.isRestricted(PlanPublicationService.PUBLICATION_COMMAND);
    }

    /**
     * Why no sync can run now, or {@code null}: a restriction, a connection or membership that is not the plan's
     * own any more (gone, replaced or ended), or the plan's end reached on the database clock.
     */
    @Nullable String unavailableReason(UUID user, UUID membershipId, UUID portfolioId, UUID connectionId,
            UUID providerAccountId, Instant endAt, Instant now) {
        if (restricted()) return RESTRICTED;
        if (!sameConnection(user, connectionId, providerAccountId)
                || !sameMembership(user, membershipId, portfolioId, connectionId, providerAccountId))
            return CONNECTION_UNAVAILABLE;
        if (!now.isBefore(endAt)) return PLAN_PERIOD_ENDED;
        return null;
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
        if (!writable(user) || restricted()) return "NOT_PERMITTED";
        var plan = plans.activePlan(user).orElse(null);
        if (plan == null || !plan.planId().equals(lease.planId())) return "PLAN_CHANGED";
        var pointer = publications.pointer(user, lease.planId()).orElse(null);
        if (pointer == null || pointer.version() != lease.basePointerVersion()
                || !pointer.calculationId().equals(lease.baseCalculationId()))
            return "POINTER_CONFLICT";
        if (!sameConnection(user, lease.connectionId(), lease.providerAccountId())) return "NOT_PERMITTED";
        if (!sameMembership(user, lease.membershipId(), lease.portfolioId(), lease.connectionId(),
                lease.providerAccountId()))
            return "MEMBERSHIP_CHANGED";
        if (!publications.databaseNow().isBefore(lease.endAt())) return "PLAN_PERIOD_ENDED";
        return null;
    }
}
