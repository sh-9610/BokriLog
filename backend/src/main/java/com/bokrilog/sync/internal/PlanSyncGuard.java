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
 * the plan's own connection and membership and nothing else.
 *
 * <p>Story 2.17부터 그 연결은 계획의 provider account·portfolio에 붙은 지금 활성 연결이다({@link #planSource}) — 같은
 * 계정으로 다시 연결한 뒤에는 기준 버전이 기록한 연결이 아니라 새 연결이다. 실행 중인 attempt는 접수한 연결에 묶인다
 * ({@link #blocked}).
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
    private final StrongSignals strongSignals;

    PlanSyncGuard(PlanPublicationRepository publications, SourceConnectionApi sources, PortfolioMembershipApi portfolios,
            AccountAccessApi accounts, OperationalRestrictionApi restrictions, PlanPublicationPort plans,
            StrongSignals strongSignals) {
        this.strongSignals = strongSignals;
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
     * 계획의 지금 연결(Story 2.17): 계획이 고정한 Bybit 계정과 portfolio에 붙은 지금 활성 연결과 그 활성 membership.
     *
     * <p>계획은 연결 ID가 아니라 provider account에 귀속한다 — 사건 고유성과 source snapshot이 이미 그 계정 단위다(AR-12,
     * V7). 연결은 교체 가능한 수집 수단이라, 같은 main UID로 재연결하면 새 연결·membership이 그 계획의 연결이 된다. 계획
     * 계보에 남은 원래 연결 ID는 불변 기록일 뿐 판정에 쓰지 않는다. 새 동기화·정밀 검증 attempt와 그 발행 계보는 여기서 고른
     * <b>새</b> ID를 쓴다.
     *
     * @return 그런 연결이 없으면 빈 값 — 해제됐거나 다른 계정·portfolio의 연결이다
     */
    Optional<PlanSource> planSource(UUID user, UUID portfolioId, UUID providerAccountId) {
        var connection = sources.snapshotConnection(user).orElse(null);
        if (connection == null || !connection.providerAccountId().equals(providerAccountId)) return Optional.empty();
        var membership = portfolios.activeSource(user).orElse(null);
        if (membership == null || !membership.portfolioId().equals(portfolioId)
                || !membership.connectionId().equals(connection.connectionId())
                || !membership.providerAccountId().equals(providerAccountId))
            return Optional.empty();
        return Optional.of(new PlanSource(membership.membershipId(), connection.connectionId(), providerAccountId));
    }

    /** 계획의 지금 연결과 그 membership(Story 2.17). */
    record PlanSource(UUID membershipId, UUID connectionId, UUID providerAccountId) {}

    /**
     * 완료·종료 후 값 attempt를 지금 받지 못하는 이유(Story 2.14), 없으면 {@code null}: 계획의 종료를 뺀 가드 자신의 이유 —
     * 제한, 또는 계획 자신의 연결이 더는 없음({@link #planSource}).
     *
     * @param recordedConnectionId 계획 계보가 기록한 연결. 지금 연결이 없을 때 제한 차원과 해제 원인을 이것으로 본다
     */
    @Nullable String completionUnavailableReason(UUID user, UUID planId, UUID portfolioId, UUID recordedConnectionId,
            UUID providerAccountId) {
        var source = planSource(user, portfolioId, providerAccountId).orElse(null);
        if (syncRestricted(planId, portfolioId, source == null ? recordedConnectionId : source.connectionId()))
            return RESTRICTED;
        if (source == null) return connectionCause(user, recordedConnectionId);
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

    /**
     * 작업의 {@link #syncRestricted}: 접수된 계획·portfolio·연결로 본다. 그 시도가 낸 강한 신호의 제한만은 빼고 본다(Story
     * 3.3) — 그 시도는 검토 필요로만 맺고 발행하지 않으며, 자기 제한에 막히면 검증의 대조 기록이 반쪽으로 남는다. 운영자
     * 제한·다른 신호의 제한은 그대로 막는다. lease·모든 단계·인계(takeover)가 이 판정을 쓴다. 발행 판정
     * ({@link #publicationRestricted})은 아무것도 빼지 않는다.
     */
    boolean syncRestricted(PlanSyncRepository.Lease lease) {
        return restrictions.blocks(EnumSet.of(RestrictionFeature.SYNC),
                subject(lease.planId(), lease.portfolioId(), lease.connectionId()),
                strongSignals.ownRestrictions(lease.jobId()));
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
     * 지금 동기화를 받지 못하는 이유, 없으면 {@code null}: 제한, 계획 자신의 연결이 더는 없음({@link #planSource} — 없음·해제·
     * 다른 계정), 또는 데이터베이스 시각으로 계획 종료.
     *
     * @param recordedConnectionId 계획 계보가 기록한 연결(Story 2.17). 지금 연결이 있으면 제한 차원은 지금 연결이다 — 연결 단위
     *     제한은 같은 계정의 연결 전부에 미친다(판독이 넓힌다). 지금 연결이 없을 때만 제한 차원과 해제 원인을 이것으로 본다
     */
    @Nullable String unavailableReason(UUID user, UUID planId, UUID portfolioId, UUID recordedConnectionId,
            UUID providerAccountId, Instant endAt, Instant now) {
        var source = planSource(user, portfolioId, providerAccountId).orElse(null);
        if (syncRestricted(planId, portfolioId, source == null ? recordedConnectionId : source.connectionId()))
            return RESTRICTED;
        if (source == null) return connectionCause(user, recordedConnectionId);
        if (!now.isBefore(endAt)) return PLAN_PERIOD_ENDED;
        return null;
    }

    /**
     * 계획에 자기 연결이 없는 이유. 지금 활성 연결이 있는데 계획의 것이 아니면(다른 계정·portfolio, Story 2.17) 연결 불가이고,
     * 활성 연결이 없고 계획 버전이 마지막으로 돈 연결을 사용자가 해제했으면(Story 2.16) 해제다. 그 밖은 연결 불가다.
     */
    private String connectionCause(UUID user, UUID connectionId) {
        if (sources.snapshotConnection(user).isPresent()) return CONNECTION_UNAVAILABLE;
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
