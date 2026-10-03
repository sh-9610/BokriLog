package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncJobApi.EvidenceReference;
import com.bokrilog.sync.SyncJobApi.InvestigationRange;
import com.bokrilog.sync.SyncJobApi.SyncJob;
import com.bokrilog.sync.SyncJobApi.VerificationAdmission;
import com.bokrilog.sync.SyncJobApi.VerificationCounts;
import com.bokrilog.sync.SyncJobApi.VerificationJob;
import com.bokrilog.sync.SyncJobApi.VerificationKind;
import com.bokrilog.sync.SyncJobApi.VerificationPreview;
import com.bokrilog.sync.SyncJobApi.VerificationRange;
import com.bokrilog.sync.SyncJobApi.VerificationReason;
import com.bokrilog.sync.SyncJobApi.VerificationState;
import com.bokrilog.sync.SyncJobApi.VersionChange;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The precision verification and the long-gap continuation of an active plan (Story 2.12, ERD-1.0.0 §2.2,
 * SYNC-UX-1.0): their admission, the verification part of the dashboard and what a version changed.
 *
 * <p><b>Admission</b> is one short transaction without a provider call. For the user's two kinds it judges, in order:
 * the account's write access; the same key replayed (the same kind joins, another is a conflict); the active plan and
 * whether the reference the screen sent is still its pointer (the user's choice token); an attempt in progress (the
 * same purpose joins, another refuses); a reason nothing can run; a persistent review of this very base
 * ({@link PlanSyncRepository#persistentlyReviewed}: either kind would only end the same way); the kind's own condition
 * — a precision verification needs no long gap, a continuation needs the long gap; the 30-second cooldown after the
 * plan's last admission of any purpose; and then the plan of ranges ({@link VerificationPlanner}) is fixed with the
 * attempt. The system's four reasons skip the reference, the usefulness and the cooldown: an operator who fixed the
 * cause must be able to verify the same base again. A system request joins an attempt in progress only when it is the
 * same request — a system verification with the same reason and the same explicit ranges — and a replayed key names
 * the same request only on the same terms.
 *
 * <p>The insert takes the key and the one-in-progress index with {@code ON CONFLICT DO NOTHING}: a request that lost
 * the race reads the winner (the key's attempt, or the one in progress) and, when that is gone too, refuses as "in
 * progress" — it never invents a cooldown.
 *
 * <p>Everything else is the one engine's ({@link PlanSyncService}): the same attempt table, lease, collection core and
 * publication.
 */
@Service
class PlanVerificationService {
    /** The reasons only the public method admits (Stories 2.13, 2.14 and Epic 3). */
    static final Set<VerificationReason> SYSTEM_REASONS = Set.of(VerificationReason.PLAN_END_PREPARATION,
            VerificationReason.UNRECOGNIZED_EVENT, VerificationReason.DUPLICATE_SUSPECTED,
            VerificationReason.OPERATOR_INVESTIGATION);

    private final PlanSyncRepository syncs;
    private final PlanPublicationRepository publications;
    private final InitialCollectionRepository collections;
    private final PlanSyncGuard guard;
    private final PlanSyncService planSyncs;
    private final TransactionTemplate transactions;

    PlanVerificationService(PlanSyncRepository syncs, PlanPublicationRepository publications,
            InitialCollectionRepository collections, PlanSyncGuard guard, PlanSyncService planSyncs,
            PlatformTransactionManager manager) {
        this.syncs = syncs;
        this.publications = publications;
        this.collections = collections;
        this.guard = guard;
        this.planSyncs = planSyncs;
        this.transactions = new TransactionTemplate(manager);
    }

    // ---------------------------------------------------------------- the user's admission

    VerificationAdmission admit(@Nullable UUID userId, @Nullable VerificationKind kind,
            @Nullable EvidenceReference reference, @Nullable String key) {
        if (userId == null) return new VerificationAdmission.NotPermitted();
        if (kind == null || reference == null) return new VerificationAdmission.Refused("BAD_REQUEST");
        if (key == null || !PlanPublicationService.validKey(key))
            return new VerificationAdmission.Refused("IDEMPOTENCY_KEY_REQUIRED");
        return Objects.requireNonNull(transactions.execute(status -> admitInTransaction(userId, kind, reference, key)));
    }

    private VerificationAdmission admitInTransaction(UUID user, VerificationKind kind, EvidenceReference reference,
            String key) {
        if (!guard.writable(user)) return new VerificationAdmission.NotPermitted();
        String purpose = purpose(kind);
        var keyed = syncs.byKey(user, key).orElse(null);
        if (keyed != null) return userReplay(keyed, purpose);
        var base = guard.base(user).orElse(null);
        if (base == null) return new VerificationAdmission.Refused("NO_ACTIVE_PLAN");
        if (!matches(base, reference)) return new VerificationAdmission.Refused("REFERENCE_STALE");
        UUID planId = base.plan().planId();
        var active = syncs.active(user, planId).orElse(null);
        if (active != null) return userJoin(active, purpose);
        Instant now = publications.databaseNow();
        if (planSyncs.unavailableReason(user, base, now) != null)
            return new VerificationAdmission.Refused("VERIFICATION_UNAVAILABLE");
        // Interpretations C2/C3: after a persistent review of this base, neither kind can change anything. It is judged
        // before the long gap, in the dashboard's order, so the answer and the screen never disagree.
        if (syncs.persistentlyReviewed(user, planId, base.pointer().calculationId()))
            return new VerificationAdmission.Refused("VERIFICATION_NOT_USEFUL");
        boolean longGap = planSyncs.longGap(user, base, now);
        if (kind == VerificationKind.PRECISION && longGap) return new VerificationAdmission.Refused("LONG_GAP");
        if (kind == VerificationKind.CATCH_UP && !longGap) return new VerificationAdmission.Refused("NOT_LONG_GAP");
        Duration wait = cooldown(user, base, now);
        if (wait != null)
            return new VerificationAdmission.Cooldown(syncs.latest(user, planId).orElse(null), SyncWindow.seconds(wait));
        VerificationPlanner.Plan plan;
        VerificationReason reason;
        if (kind == VerificationKind.PRECISION) {
            List<Instant> anomalies = anomalies(user, base);
            reason = anomalies.isEmpty() ? VerificationReason.USER_REQUEST : VerificationReason.GAP_SUSPECTED;
            plan = VerificationPlanner.precision(base.calculation().t0(), base.calculation().verifiedThrough(),
                    evidence(user, base), anomalies, List.of());
        } else {
            reason = VerificationReason.LONG_GAP_CONTINUATION;
            plan = VerificationPlanner.catchUp(base.calculation().t0(), base.calculation().verifiedThrough(),
                    evidence(user, base), anomalies(user, base));
        }
        return insert(user, base, purpose, reason, "MANUAL", key, plan, List.of());
    }

    /** A user's replayed key: the same kind (from the user, not the system) joins; anything else conflicts. */
    private static VerificationAdmission userReplay(SyncJob keyed, String purpose) {
        return purpose.equals(keyed.purpose()) && "MANUAL".equals(keyed.trigger())
                ? new VerificationAdmission.Joined(keyed) : new VerificationAdmission.Refused("IDEMPOTENCY_CONFLICT");
    }

    /** An attempt in progress for a user's request: the same purpose joins, another refuses. */
    private static VerificationAdmission userJoin(SyncJob active, String purpose) {
        return purpose.equals(active.purpose()) ? new VerificationAdmission.Joined(active)
                : new VerificationAdmission.Refused("JOB_IN_PROGRESS");
    }

    // ---------------------------------------------------------------- the system's admission

    VerificationAdmission admitSystem(@Nullable UUID userId, @Nullable VerificationReason reason,
            @Nullable List<InvestigationRange> ranges, @Nullable String key) {
        if (userId == null) return new VerificationAdmission.NotPermitted();
        if (reason == null || !SYSTEM_REASONS.contains(reason)) return new VerificationAdmission.Refused("BAD_REQUEST");
        if (key == null || !PlanPublicationService.validKey(key))
            return new VerificationAdmission.Refused("IDEMPOTENCY_KEY_REQUIRED");
        if (ranges == null || ranges.stream().anyMatch(Objects::isNull))
            return new VerificationAdmission.Refused("INVALID_RANGE");
        boolean investigation = reason == VerificationReason.OPERATOR_INVESTIGATION;
        if (investigation == ranges.isEmpty()) return new VerificationAdmission.Refused("INVALID_RANGE");
        List<VerificationPlanner.Interval> explicit;
        try {
            explicit = ranges.stream().map(range -> new VerificationPlanner.Interval(
                    Objects.requireNonNull(range.start()), Objects.requireNonNull(range.end()))).toList();
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return new VerificationAdmission.Refused("INVALID_RANGE");
        }
        return Objects.requireNonNull(transactions.execute(status -> admitSystemInTransaction(userId, reason,
                explicit, key)));
    }

    private VerificationAdmission admitSystemInTransaction(UUID user, VerificationReason reason,
            List<VerificationPlanner.Interval> explicit, String key) {
        if (!guard.writable(user)) return new VerificationAdmission.NotPermitted();
        var keyed = syncs.byKey(user, key).orElse(null);
        if (keyed != null) return sameSystemRequest(user, keyed, reason, explicit) ? new VerificationAdmission.Joined(keyed)
                : new VerificationAdmission.Refused("IDEMPOTENCY_CONFLICT");
        var base = guard.base(user).orElse(null);
        if (base == null) return new VerificationAdmission.Refused("NO_ACTIVE_PLAN");
        UUID planId = base.plan().planId();
        var active = syncs.active(user, planId).orElse(null);
        if (active != null) return sameSystemRequest(user, active, reason, explicit)
                ? new VerificationAdmission.Joined(active) : new VerificationAdmission.Refused("JOB_IN_PROGRESS");
        Instant now = publications.databaseNow();
        if (planSyncs.unavailableReason(user, base, now) != null)
            return new VerificationAdmission.Refused("VERIFICATION_UNAVAILABLE");
        if (planSyncs.longGap(user, base, now)) return new VerificationAdmission.Refused("LONG_GAP");
        Instant t0 = base.calculation().t0();
        Instant verified = base.calculation().verifiedThrough();
        if (!explicit.isEmpty() && !VerificationPlanner.explicitValid(t0, verified, explicit))
            return new VerificationAdmission.Refused("INVALID_RANGE");
        var plan = VerificationPlanner.precision(t0, verified, evidence(user, base), anomalies(user, base), explicit);
        return insert(user, base, "VERIFY", reason, "SYSTEM", key, plan, explicit);
    }

    /**
     * Whether an attempt is this system request: a system verification with the same reason and exactly the same
     * explicit ranges (none for the three reasons that take none).
     */
    private boolean sameSystemRequest(UUID user, SyncJob job, VerificationReason reason,
            List<VerificationPlanner.Interval> explicit) {
        if (!"VERIFY".equals(job.purpose()) || !"SYSTEM".equals(job.trigger()) || !reason.name().equals(job.reason()))
            return false;
        return explicitOf(syncs.ranges(user, job.jobId())).equals(sortedIntervals(explicit));
    }

    private static List<VerificationPlanner.Interval> explicitOf(List<VerificationPlanner.Range> ranges) {
        return sortedIntervals(ranges.stream().filter(range -> range.kind() == VerificationPlanner.Kind.EXPLICIT)
                .map(VerificationPlanner.Range::interval).toList());
    }

    private static List<VerificationPlanner.Interval> sortedIntervals(List<VerificationPlanner.Interval> ranges) {
        return ranges.stream().sorted(Comparator.comparing(VerificationPlanner.Interval::start)
                .thenComparing(VerificationPlanner.Interval::end)).toList();
    }

    /**
     * Inserts the attempt and its planned ranges. A lost race answers with the key's attempt or the one in progress,
     * judged as the admission judges them; when neither is there any more, it refuses as "in progress" (the attempt
     * that took the index has just finished) — never with an invented cooldown.
     */
    private VerificationAdmission insert(UUID user, PlanSyncGuard.Base base, String purpose, VerificationReason reason,
            String trigger, String key, VerificationPlanner.Plan plan, List<VerificationPlanner.Interval> explicit) {
        UUID jobId = UUID.randomUUID();
        var calculation = base.calculation();
        var lineage = base.lineage();
        UUID planId = base.plan().planId();
        int inserted = syncs.insert(new PlanSyncRepository.Admission(jobId, user, planId, calculation.portfolioId(),
                base.plan().intentId(), lineage.membershipId(), lineage.connectionId(), lineage.providerAccountId(),
                purpose, reason.name(), trigger, key, base.pointer().calculationId(), base.pointer().version(),
                lineage.candidateId(), calculation.verifiedThrough(), lineage.lastExtendedAt(), calculation.t0(),
                calculation.endAt()));
        if (inserted != 1) {
            boolean system = "SYSTEM".equals(trigger);
            var raced = syncs.byKey(user, key).orElse(null);
            if (raced != null) return system ? (sameSystemRequest(user, raced, reason, explicit)
                    ? new VerificationAdmission.Joined(raced) : new VerificationAdmission.Refused("IDEMPOTENCY_CONFLICT"))
                    : userReplay(raced, purpose);
            var active = syncs.active(user, planId).orElse(null);
            if (active != null) return system ? (sameSystemRequest(user, active, reason, explicit)
                    ? new VerificationAdmission.Joined(active) : new VerificationAdmission.Refused("JOB_IN_PROGRESS"))
                    : userJoin(active, purpose);
            return new VerificationAdmission.Refused("JOB_IN_PROGRESS");
        }
        List<VerificationPlanner.Range> ranges = new java.util.ArrayList<>(plan.targets());
        ranges.addAll(plan.reused());
        syncs.insertRanges(user, jobId, ranges);
        return new VerificationAdmission.Accepted(syncs.byId(user, jobId).orElseThrow());
    }

    private static String purpose(VerificationKind kind) {
        return kind == VerificationKind.PRECISION ? "VERIFY" : "CATCH_UP";
    }

    /** The reference is the plan's current pointer to the letter: its plan, version and calculation. */
    private static boolean matches(PlanSyncGuard.Base base, EvidenceReference reference) {
        return base.plan().planId().equals(reference.planId()) && base.pointer().version() == reference.pointerVersion()
                && base.pointer().calculationId().equals(reference.calculationId());
    }

    /** 30 seconds after the plan's last admission, of any purpose. */
    private @Nullable Duration cooldown(UUID user, PlanSyncGuard.Base base, Instant now) {
        return SyncWindow.wait(SyncWindow.Trigger.MANUAL, now,
                syncs.lastAdmission(user, base.plan().planId()).orElse(null), base.pointer().publishedAt());
    }

    private List<VerificationPlanner.Interval> evidence(UUID user, PlanSyncGuard.Base base) {
        return collections.lineageEvidence(user, base.lineage().candidateId());
    }

    private List<Instant> anomalies(UUID user, PlanSyncGuard.Base base) {
        return collections.unexplainedEvents(user, base.lineage().providerAccountId(), base.lineage().candidateId(),
                base.calculation().t0(), base.calculation().verifiedThrough());
    }

    // ---------------------------------------------------------------- the dashboard (caller's read transaction)

    /**
     * The verification part of the dashboard, judged by the admission's own rules against the facts the caller read
     * once ({@link PlanSyncService#facts}, the base's persistent review among them), in the caller's one consistent
     * read: the availability, what a precision verification would read now, where a continuation would start, and the
     * plan's latest verification with its plan and reconciliation counts. Nothing is scanned while an attempt is in
     * progress — no preview (the lineage's evidence and the unexplained events), and the continuation's start is then
     * its unconfirmed range's.
     */
    VerificationState state(UUID user, PlanSyncGuard.Base base, PlanSyncService.Facts facts) {
        UUID planId = base.plan().planId();
        var review = facts.review();
        Duration wait = facts.manualWait();
        String availability = facts.inProgress() ? "IN_PROGRESS" : facts.unavailableReason() != null ? "UNAVAILABLE"
                : review != null ? "NOT_USEFUL" : facts.longGap() ? "LONG_GAP" : wait != null ? "COOLDOWN" : "AVAILABLE";
        Long retryAfter = ("COOLDOWN".equals(availability) || "LONG_GAP".equals(availability)) && wait != null
                ? SyncWindow.seconds(wait) : null;
        Instant t0 = base.calculation().t0();
        Instant verified = base.calculation().verifiedThrough();
        VerificationPreview preview = null;
        String suggested = null;
        Instant catchUpFrom = null;
        if (facts.longGap()) {
            // Where the continuation's own plan starts: its unconfirmed week or an earlier anomaly range.
            catchUpFrom = facts.inProgress() ? VerificationPlanner.unconfirmed(t0, verified).start()
                    : VerificationPlanner.windowStart(VerificationPlanner.catchUp(t0, verified, List.of(),
                            anomalies(user, base)).targets());
        } else if (!facts.inProgress()) {
            List<Instant> anomalies = anomalies(user, base);
            var plan = VerificationPlanner.precision(t0, verified, evidence(user, base), anomalies, List.of());
            int anomalyCount = (int) anomalies.stream().filter(at -> at.isAfter(t0) && !at.isAfter(verified)).count();
            preview = new VerificationPreview(VerificationPlanner.windowStart(plan.targets()),
                    VerificationPlanner.unconfirmed(t0, verified).start(), anomalyCount,
                    plan.reused().stream().map(VerificationPlanner.Range::end).max(Comparator.naturalOrder())
                            .orElse(null));
            // Only a verification the user could ask for (now, or once the cooldown ends) has a reason to suggest.
            if ("AVAILABLE".equals(availability) || "COOLDOWN".equals(availability))
                suggested = (anomalies.isEmpty() ? VerificationReason.USER_REQUEST : VerificationReason.GAP_SUSPECTED)
                        .name();
        }
        VerificationJob latest = syncs.latestVerification(user, planId)
                .map(job -> job(user, job, review != null && review.jobId().equals(job.jobId()))).orElse(null);
        return new VerificationState(availability, "UNAVAILABLE".equals(availability) ? facts.unavailableReason() : null,
                retryAfter, suggested, preview, catchUpFrom, latest);
    }

    /** The verification part alone, reading its facts (for callers without the dashboard's shared read). */
    VerificationState state(UUID user, PlanSyncGuard.Base base) {
        return state(user, base, planSyncs.facts(user, base));
    }

    /** A verification attempt with its planned ranges and reconciliation counts. */
    VerificationJob job(UUID user, SyncJob job, boolean holdsResult) {
        List<VerificationPlanner.Range> ranges = syncs.ranges(user, job.jobId());
        int[] counts = syncs.reconciliationCounts(user, job.jobId());
        return new VerificationJob(job, targets(ranges), reused(ranges),
                new VerificationCounts(counts[0], counts[1], counts[2], counts[3], counts[4]), holdsResult);
    }

    /**
     * The persistent review of the current base that stands ({@link PlanSyncRepository#verificationReview}): the
     * attempt that ended so and its closed code, or {@code null}.
     */
    PlanSyncRepository.@Nullable Review review(UUID user, PlanSyncGuard.Base base) {
        return syncs.verificationReview(user, base.plan().planId(), base.pointer().calculationId()).orElse(null);
    }

    /**
     * What the current version changed (AC-7): the attempt that made it and its change rows, judged by
     * {@link VersionChanges}. A version whose change rows are not exactly its flow difference shows {@code NONE} —
     * the dashboard is not blocked for it, but no marker is drawn from rows the evidence would refuse.
     */
    VersionChange change(UUID user, PlanSyncGuard.Base base) {
        UUID calculationId = base.pointer().calculationId();
        var sync = syncs.creatingSync(user, base.lineage().createdByJobId()).orElse(null);
        if (sync != null && !publications.changesConsistent(user, calculationId, sync.baseCalculationId()))
            return new VersionChange(VersionChanges.NONE, null, null, 0, 0, 0, 0, null, null, List.of(), List.of());
        var changes = publications.changes(user, calculationId);
        var late = VersionChanges.late(changes);
        var counts = VersionChanges.counts(changes);
        Instant firstAffected = late.stream().map(PlanPublicationRepository.ChangeRow::occurredAt)
                .min(Comparator.naturalOrder()).orElse(null);
        String kind = VersionChanges.kind(late.size(), sync == null ? null : sync.purpose());
        boolean verifying = sync != null && !"SYNC".equals(sync.purpose());
        List<VerificationPlanner.Range> ranges = verifying ? syncs.ranges(user, base.lineage().createdByJobId())
                : List.of();
        return new VersionChange(kind, sync == null ? null : sync.purpose(), sync == null ? null : sync.reason(),
                counts.total(), counts.lateAdded(), counts.valueChanged(), counts.removed(), firstAffected, verifying ? sync.finishedAt() : null, targets(ranges), reused(ranges));
    }

    static List<VerificationRange> targets(List<VerificationPlanner.Range> ranges) {
        return ranges.stream().filter(range -> range.kind() != VerificationPlanner.Kind.REUSED)
                .map(PlanVerificationService::view).toList();
    }

    static List<VerificationRange> reused(List<VerificationPlanner.Range> ranges) {
        return ranges.stream().filter(range -> range.kind() == VerificationPlanner.Kind.REUSED)
                .map(PlanVerificationService::view).toList();
    }

    private static VerificationRange view(VerificationPlanner.Range range) {
        return new VerificationRange(range.kind().name(), range.start(), range.end());
    }
}
