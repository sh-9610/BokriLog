package com.bokrilog.sync.internal;

import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.internal.PlanPublicationRepository.CalculationView;
import com.bokrilog.sync.internal.PlanPublicationRepository.CreatingSync;
import com.bokrilog.sync.internal.PlanPublicationRepository.HistoryEntry;
import com.bokrilog.sync.internal.PlanPublicationRepository.Lineage;
import com.bokrilog.sync.internal.PlanPublicationRepository.Pointer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The head of the current official version's lineage (Story 2.11): the part the calculation evidence (2.9) and
 * the adjustment history (2.10) must judge alike, so that one version is either unavailable to both or to
 * neither. It runs inside the caller's read-only {@code REPEATABLE READ} transaction, after the caller found the
 * reference to be the current pointer and the version to belong to the plan.
 *
 * <ul>
 *   <li>the pointer's history records this version as the newest, still current, official since the pointer's
 *       own publication time;
 *   <li>for an incremental version: the attempt that created it published exactly this plan, calculation and
 *       pointer version, from the previous version as its basis, with the reference time, mode, collection start
 *       and latest lineage the version recorded — and that mode, start and extended verification are the ones the
 *       previous version's two verification times require (ERD §2.2);
 *   <li>the previous calculation is the previous official version in the history, and that version ended exactly
 *       when this one began.
 * </ul>
 *
 * <p>The first calculation ({@code INITIAL}) has no previous version; the evidence judges its creating
 * publication attempt on its own (Story 2.9), as it did before.
 */
@Component
class OfficialVersionHead {
    /** The earlier official versions the evidence lists beside the current one. */
    static final int OFFICIAL_VERSIONS_SHOWN = 10;

    private final PlanPublicationRepository repository;

    OfficialVersionHead(PlanPublicationRepository repository) {
        this.repository = repository;
    }

    /**
     * What the head read: the history (newest first, the current version and at most
     * {@link #OFFICIAL_VERSIONS_SHOWN} earlier ones), and for an incremental version its previous version.
     */
    record Head(List<HistoryEntry> history, @Nullable Previous previous) {}

    /** The previous official version of an incremental one, with the attempt that continued it. */
    record Previous(HistoryEntry entry, CalculationView calculation, Lineage lineage, CreatingSync sync) {}

    /** The head, or {@code null} when it does not hold. */
    @Nullable Head judge(UUID user, PlanPublicationPort.ActivePlan plan, Pointer pointer, CalculationView calculation,
            Lineage lineage) {
        if (lineage.initial() != (lineage.previousCalculationId() == null)) return null;
        List<HistoryEntry> history = repository.history(user, plan.planId(), OFFICIAL_VERSIONS_SHOWN + 1);
        if (history.isEmpty()) return null;
        HistoryEntry current = history.getFirst();
        if (current.pointerVersion() != pointer.version() || !current.calculationId().equals(pointer.calculationId())
                || current.supersededAt() != null || !current.publishedAt().equals(pointer.publishedAt()))
            return null;
        if (lineage.initial()) return new Head(history, null);

        UUID previousId = lineage.previousCalculationId();
        var sync = repository.creatingSync(user, lineage.createdByJobId()).orElse(null);
        var previous = previousId == null ? null : repository.calculation(user, previousId).orElse(null);
        var previousLineage = previousId == null ? null : repository.lineage(user, previousId).orElse(null);
        HistoryEntry previousEntry = history.size() > 1 ? history.get(1) : null;
        if (sync == null || previous == null || previousLineage == null || previousEntry == null
                || !previousEntry.calculationId().equals(previousId)
                || previousEntry.pointerVersion() != pointer.version() - 1
                || !current.publishedAt().equals(previousEntry.supersededAt())
                || !syncedFromRecorded(sync, plan, pointer.calculationId(), pointer.version(), calculation, lineage,
                        previous, previousLineage))
            return null;
        return new Head(history, new Previous(previousEntry, previous, previousLineage, sync));
    }

    /**
     * Whether an incremental version is exactly what its sync attempt recorded: the attempt published this version
     * of this plan as the next pointer version, from the previous version as its basis, with the version's reference
     * time, mode, collection start and latest lineage, for the plan's intent, portfolio, membership, connection and
     * provider account. The previous version is the same plan's, its portfolio snapshot is the one before, and its
     * two verification times give exactly this version's mode, collection start and extended verification.
     */
    static boolean syncedFromRecorded(CreatingSync sync, PlanPublicationPort.ActivePlan plan, UUID calculationId,
            long pointerVersion, CalculationView calculation, Lineage lineage, CalculationView previous,
            Lineage previousLineage) {
        if (!"PUBLISHED".equals(sync.status()) || !plan.planId().equals(sync.planId())
                || !calculationId.equals(sync.calculationId()) || sync.publishedPointerVersion() == null
                || sync.publishedPointerVersion() != pointerVersion || sync.basisPointerVersion() != pointerVersion - 1
                || !sync.basisCalculationId().equals(lineage.previousCalculationId())
                || !lineage.collectionMode().equals(sync.mode()) || !calculation.basisAt().equals(sync.tc())
                || !lineage.collectionRangeStart().equals(sync.rangeStart())
                || !lineage.candidateId().equals(sync.candidateId())
                || !lineage.classificationId().equals(sync.classificationId())
                || !lineage.conversionId().equals(sync.conversionId())
                || !plan.intentId().equals(sync.intentId()) || !calculation.portfolioId().equals(sync.portfolioId())
                || !lineage.membershipId().equals(sync.membershipId())
                || !lineage.connectionId().equals(sync.connectionId())
                || !lineage.providerAccountId().equals(sync.providerAccountId()))
            return false;
        if (!previous.planId().equals(plan.planId()) || !previous.portfolioId().equals(calculation.portfolioId())
                || previous.portfolioSequence() + 1 != calculation.portfolioSequence()
                || !previous.basisPortfolioSnapshotId().equals(calculation.basisPortfolioSnapshotId())
                || !previousLineage.basisSourceSnapshotId().equals(lineage.basisSourceSnapshotId())
                || !previousLineage.membershipId().equals(lineage.membershipId())
                || !previousLineage.connectionId().equals(lineage.connectionId())
                || !previousLineage.providerAccountId().equals(lineage.providerAccountId())
                || !previous.verifiedThrough().isBefore(calculation.basisAt()))
            return false;
        String mode = PlanSyncService.mode(calculation.basisAt(), previousLineage.lastExtendedAt());
        Instant lastExtended = mode.equals("EXTENDED") ? calculation.basisAt() : previousLineage.lastExtendedAt();
        return mode.equals(lineage.collectionMode()) && lastExtended.equals(lineage.lastExtendedAt())
                && PlanSyncService.rangeStart(calculation.t0(), previous.verifiedThrough(), mode)
                        .equals(lineage.collectionRangeStart());
    }
}
