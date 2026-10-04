package com.bokrilog.sync.internal;

import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.sync.PlanPublicationPort;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Whether the shown plan's record agrees with its pointer and the version it cites — one judgment for the dashboard,
 * the calculation evidence and the adjustment history, so the three never disagree about the same plan.
 *
 * <ul>
 *   <li>An active plan: the portfolio's current pointer cites the version's snapshot (AD-23: both pointers advance in
 *       one publication transaction).
 *   <li>A plan that ended early (Story 2.13): its end record names the version, its snapshot and its times
 *       ({@link PlanPublicationService#endAgrees}).
 *   <li>A completed plan (Story 2.14): its completion record agrees with the version
 *       ({@link PlanPublicationService#completionAgrees}) and its first observation is the one a completion attempt
 *       published ({@link #observationAgrees}). Post-end values move the portfolio's pointer, so it is not compared.
 * </ul>
 *
 * <p>It reads in the caller's transaction and opens none.
 */
@Component
class ShownPlanAgreement {
    private final PlanPublicationRepository publications;
    private final PlanSyncRepository syncs;
    private final PortfolioMembershipApi portfolios;

    ShownPlanAgreement(PlanPublicationRepository publications, PlanSyncRepository syncs,
            PortfolioMembershipApi portfolios) {
        this.publications = publications;
        this.syncs = syncs;
        this.portfolios = portfolios;
    }

    boolean agrees(UUID user, PlanPublicationService.Shown shown, PlanPublicationRepository.Pointer pointer,
            PlanPublicationRepository.CalculationView calculation) {
        if (shown.end() != null) return PlanPublicationService.endAgrees(shown.end(), shown.plan(), pointer, calculation);
        if (shown.completion() != null)
            return PlanPublicationService.completionAgrees(shown.completion(), shown.plan(), pointer, calculation)
                    && (!shown.completion().observed() || observationAgrees(user, shown.completion()));
        var portfolio = portfolios.officialValue(user).orElse(null);
        return portfolio != null && portfolio.snapshotId().equals(calculation.portfolioSnapshotId())
                && portfolio.publicationSequence() == calculation.portfolioSequence();
    }

    /**
     * Whether the first observation is the one a completion attempt published: its portfolio snapshot was that
     * attempt's own observation, and the attempt's source snapshot holds the same value and measurement.
     */
    boolean observationAgrees(UUID user, PlanPublicationPort.PlanCompletion completion) {
        var observation = syncs.observation(user, Objects.requireNonNull(completion.observedPortfolioSnapshotId()))
                .orElse(null);
        if (observation == null || !PlanSyncRepository.COMPLETION.equals(observation.purpose())) return false;
        var source = publications.sourceOrigin(user, observation.sourceSnapshotId()).orElse(null);
        String value = completion.observedValue();
        return source != null && value != null && observation.jobId().equals(source.planSyncJobId())
                && source.measuredAt().equals(completion.observedMeasuredAt()) && new java.math.BigDecimal(source.equity()).compareTo(new java.math.BigDecimal(value)) == 0;
    }
}
