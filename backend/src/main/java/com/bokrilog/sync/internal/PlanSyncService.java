package com.bokrilog.sync.internal;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceRejectionReason;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.SyncJobApi.SyncAdmission;
import com.bokrilog.sync.SyncJobApi.SyncJob;
import com.bokrilog.sync.SyncJobApi.SyncState;
import com.bokrilog.sync.SyncJobApi.SyncTrigger;
import com.bokrilog.sync.internal.PlanPublicationRepository.Flow;
import com.bokrilog.sync.internal.PlanPublicationRepository.Pinned;
import com.bokrilog.sync.internal.PlanPublicationRepository.PublicationBlocked;
import com.bokrilog.sync.IdempotencyKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The user-triggered incremental sync of an active plan (Story 2.11, ERD-1.0.0 §2.2, SYNC-UX-1.0), and the engine
 * Story 2.12's precision verification and long-gap continuation run on too: one attempt table, one lease, one
 * collection core and one publication for every purpose.
 *
 * <p><b>Admission</b> is one short transaction without a provider call, judging in order: the account's write
 * access, the same key replayed, the active plan and its pointer, an attempt in progress (joined), a reason no
 * sync can run, the long gap (database clock), then the manual cooldown or the automatic interval.
 *
 * <p><b>The worker</b> runs one attempt through its stages on one lease: the Bybit time fixes {@code tc} (the
 * first provider call), the same judgment is made again with it, then the collection, classification and
 * conversion stages — rows of Stories 2.4~2.6 the attempt owns — the pure calculation, and the atomic
 * publication ({@link PlanSyncPublisher}). Every stage re-reads the lease, the fence and the permission first;
 * provider calls run between short transactions. A failure at any stage ends the attempt with a closed code and
 * leaves the last official version as it was. Nothing is retried automatically: a reclaimed attempt whose
 * holder vanished ends as interrupted, and the user syncs again.
 *
 * <p><b>A verification or continuation</b> (Story 2.12) plans its target ranges at admission
 * ({@link PlanVerificationService}); its worker adds {@code NEW} up to the {@code tc} it fixed, refuses a target
 * older than Bybit's history before any signed call, reads only the targets' windows, and reconciles them against the
 * official candidate. A continuation then fixes {@code tcFinal}, sweeps the last seven days again with the current
 * equity, and publishes once. A reclaimed verification still collecting goes on from its complete windows, twice at
 * most; everything later, and every reclaimed sync, ends as interrupted.
 *
 * <p><b>Observation</b>: every attempt that ends writes exactly one line — the job, purpose, reason, trigger, mode,
 * status, stage, code, the targets, windows, pages and page retries of its collection, its reconciliation outcomes
 * and late changes, the latency and the policy versions — and nothing else: no user, amount, credential, provider
 * text or Bybit id.
 */
@Service
class PlanSyncService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PlanSyncService.class);

    /** The policy versions an attempt applies, for its ending line. */
    static final String POLICIES = CashflowDecisionTable.POLICY_VERSION + "," + PriceConversionPolicy.POLICY_VERSION
            + "," + PlanCalculator.CALCULATION_POLICY + "," + PlanCalculator.CALENDAR_POLICY;

    private final PlanSyncRepository syncs;
    private final PlanPublicationRepository publications;
    private final InitialCollectionRepository collections;
    private final CashflowClassificationRepository classifications;
    private final CashflowConversionRepository conversions;
    private final SourceConnectionApi sources;
    private final CalculationConformance conformance;
    private final PlanSyncGuard guard;
    private final PlanSyncPublisher publisher;
    private final TransactionTemplate transactions;
    private final CashflowConversionMarket market;
    /** The plan module's port (Story 2.14): the natural completion's transition and its completion record. */
    private final PlanPublicationPort plans;
    private final AccountAccessApi accounts;
    /**
     * What each running holder's collection did, for its attempt's one ending line (windows, pages, page retries). Keyed
     * by the holder's lease — the job and its fencing generation — so an earlier holder in this process and the one
     * that resumed its attempt never share or remove each other's counters.
     */
    private final java.util.Map<PlanSyncRepository.Lease, RunStats> runs = new java.util.concurrent.ConcurrentHashMap<>();

    /** An attempt's collection counters. Written by its one worker thread, read when it ends. */
    static final class RunStats {
        private final java.util.concurrent.atomic.AtomicInteger windows = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger pages = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger();

        void add(int windowCount, int pageCount, int retryCount) {
            this.windows.addAndGet(windowCount);
            this.pages.addAndGet(pageCount);
            this.retries.addAndGet(retryCount);
        }
    }

    PlanSyncService(PlanSyncRepository syncs, PlanPublicationRepository publications,
            InitialCollectionRepository collections, CashflowClassificationRepository classifications,
            CashflowConversionRepository conversions, SourceConnectionApi sources, CalculationConformance conformance,
            PlanSyncGuard guard, PlanSyncPublisher publisher, PlanPublicationPort plans, AccountAccessApi accounts,
            PlatformTransactionManager manager) {
        this.plans = plans;
        this.accounts = accounts;
        this.syncs = syncs;
        this.publications = publications;
        this.collections = collections;
        this.classifications = classifications;
        this.conversions = conversions;
        this.sources = sources;
        this.conformance = conformance;
        this.guard = guard;
        this.publisher = publisher;
        this.transactions = new TransactionTemplate(manager);
        this.market = new CashflowConversionMarket(sources, conversions, this.transactions);
    }

    // ---------------------------------------------------------------- admission

    SyncAdmission admit(@Nullable UUID userId, @Nullable SyncTrigger trigger, @Nullable String key) {
        if (userId == null) return new SyncAdmission.NotPermitted();
        if (trigger == null) return new SyncAdmission.Refused("BAD_REQUEST");
        // 계획 command의 키 규칙은 하나다(IdempotencyKey).
        if (!IdempotencyKey.isValid(key))
            return new SyncAdmission.Refused(IdempotencyKey.REQUIRED_CODE);
        try {
            return Objects.requireNonNull(transactions.execute(status -> admitInTransaction(userId, trigger, key)));
        } catch (DuplicateKeyException racing) {
            // Another tab won the key or the one-in-progress index between our read and insert: judge again.
            return Objects.requireNonNull(transactions.execute(status -> admitInTransaction(userId, trigger, key)));
        }
    }

    private SyncAdmission admitInTransaction(UUID user, SyncTrigger trigger, String key) {
        if (!guard.writable(user)) return new SyncAdmission.NotPermitted();
        var keyed = syncs.byKey(user, key).orElse(null);
        if (keyed != null) return sameSync(keyed, trigger) ? new SyncAdmission.Joined(keyed)
                : new SyncAdmission.Refused("IDEMPOTENCY_CONFLICT");
        var base = guard.base(user).orElse(null);
        if (base == null) {
            // A plan that reached its end and completed (Story 2.14): its post-end syncs.
            var completed = guard.completedBase(user).orElse(null);
            if (completed == null) return new SyncAdmission.Refused("NO_ACTIVE_PLAN");
            return admitCompleted(user, trigger, key, completed);
        }
        UUID planId = base.plan().planId();
        // Asymmetric on purpose (Story 2.12): a sync joins a verification in progress — it publishes with a new tc,
        // which is what the sync wanted — but a verification never joins a sync. An attempt in progress at T is joined
        // too, without the transition: it ends PLAN_PERIOD_ENDED by its guard, and the next request completes the plan.
        var active = syncs.active(user, planId).orElse(null);
        if (active != null) return new SyncAdmission.Joined(active);
        Instant now = publications.databaseNow();
        if (!now.isBefore(base.calculation().endAt())) return complete(user, trigger, key, base, now);
        if (unavailableReason(user, base, now) != null) return new SyncAdmission.Refused("SYNC_UNAVAILABLE");
        // A persistent verification review of this base holds every sync (R6): it could only end as that review, so
        // nothing is made — no attempt row, no provider call — and the dashboard says why (REVIEW_HELD).
        if (syncs.persistentlyReviewed(user, planId, base.pointer().calculationId()))
            return new SyncAdmission.Refused("SYNC_UNAVAILABLE");
        SyncJob latest = syncs.latest(user, planId).orElse(null);
        if (longGap(user, base, now)) return new SyncAdmission.Deferred("LONG_GAP", latest, null);
        Duration wait = SyncWindow.wait(SyncWindow.Trigger.valueOf(trigger.name()), now,
                syncs.lastAdmission(user, planId).orElse(null), base.pointer().publishedAt());
        if (wait != null) return new SyncAdmission.Deferred(trigger == SyncTrigger.MANUAL ? "COOLDOWN" : "NOT_DUE",
                latest, SyncWindow.seconds(wait));
        UUID jobId = UUID.randomUUID();
        var calculation = base.calculation();
        var lineage = base.lineage();
        int inserted = syncs.insert(new PlanSyncRepository.Admission(jobId, user, planId, calculation.portfolioId(),
                base.plan().intentId(), lineage.membershipId(), lineage.connectionId(), lineage.providerAccountId(),
                "SYNC", null, trigger.name(), key, base.pointer().calculationId(), base.pointer().version(),
                lineage.candidateId(), calculation.verifiedThrough(), lineage.lastExtendedAt(), calculation.t0(),
                calculation.endAt()));
        if (inserted != 1) {
            // The key or the in-progress index was taken in between: the same key, or the attempt it let stand.
            var raced = syncs.byKey(user, key).orElse(null);
            if (raced != null) return sameSync(raced, trigger) ? new SyncAdmission.Joined(raced)
                    : new SyncAdmission.Refused("IDEMPOTENCY_CONFLICT");
            return syncs.active(user, planId).<SyncAdmission>map(SyncAdmission.Joined::new)
                    .orElse(new SyncAdmission.Deferred(trigger == SyncTrigger.MANUAL ? "COOLDOWN" : "NOT_DUE",
                            syncs.latest(user, planId).orElse(null), 1L));
        }
        return new SyncAdmission.Accepted(syncs.byId(user, jobId).orElseThrow());
    }

    /**
     * A key replayed for a sync names the same request only if it made an attempt of a sync request — a sync, or a
     * completed plan's completion or post-end value (Story 2.14) — with the same trigger.
     */
    private static boolean sameSync(SyncJob keyed, SyncTrigger trigger) {
        return SYNC_REQUEST_PURPOSES.contains(keyed.purpose()) && keyed.trigger().equals(trigger.name());
    }

    /** The purposes a sync request admits. */
    private static final Set<String> SYNC_REQUEST_PURPOSES = Set.of("SYNC", PlanSyncRepository.COMPLETION,
            PlanSyncRepository.POST_END_VALUE);

    /**
     * The natural completion (Story 2.14, AC-1): the database clock reached the active plan's end {@code T} and nothing
     * is in progress. In this same transaction and in the publishers' lock order — the plan lock, then the account
     * lifecycle — the plan module locks the plan row, judges {@code T} again and writes {@code ACTIVE → COMPLETED} with
     * its completion record: not judged yet, or after a long gap (the sync admission's own judgment) not computable
     * for the data gap. Then the completed plan's own admission follows. An early end or another tab's transition that
     * won the locks leaves this one nothing to write: the plan is then answered as it now is.
     */
    private SyncAdmission complete(UUID user, SyncTrigger trigger, String key, PlanSyncGuard.Base base, Instant now) {
        plans.lockPlanWrites(user);
        if (!accounts.lockWriteAccess(user)) return new SyncAdmission.NotPermitted();
        plans.completePlan(user, base.plan().planId(), longGap(user, base, now));
        var completed = guard.completedBase(user).orElse(null);
        if (completed == null || !completed.base().plan().planId().equals(base.plan().planId()))
            return new SyncAdmission.Refused("NO_ACTIVE_PLAN");
        return admitCompleted(user, trigger, key, completed);
    }

    /**
     * A completed plan's sync request (Story 2.14, AC-7): an attempt in progress is joined; a restriction or a
     * connection or membership that is not the plan's own refuses it (the transition, if this request made it, still
     * commits); the manual cooldown and the automatic interval apply after the plan's last admission. The attempt is a
     * completion while the first observation is missing or the comparison is pending or held, else a post-end value.
     */
    private SyncAdmission admitCompleted(UUID user, SyncTrigger trigger, String key,
            PlanSyncGuard.CompletedBase completed) {
        plans.lockPlanWrites(user);
        if (!accounts.lockWriteAccess(user)) return new SyncAdmission.NotPermitted();
        if (publications.successorWorkInProgress(user)) return new SyncAdmission.Refused("SYNC_UNAVAILABLE");
        var latest=guard.completedBase(user).orElse(null);
        if (latest==null || !latest.base().plan().planId().equals(completed.base().plan().planId()))
            return new SyncAdmission.Refused("NO_ACTIVE_PLAN");
        var base = completed.base();
        UUID planId = base.plan().planId();
        var active = syncs.active(user, planId).orElse(null);
        if (active != null) return new SyncAdmission.Joined(active);
        if (completedUnavailableReason(user, base) != null) return new SyncAdmission.Refused("SYNC_UNAVAILABLE");
        Instant now = publications.databaseNow();
        Duration wait = completedWait(SyncWindow.Trigger.valueOf(trigger.name()), now,
                syncs.lastAdmission(user, planId).orElse(null), completed.completion().transitionedAt());
        if (wait != null) return new SyncAdmission.Deferred(trigger == SyncTrigger.MANUAL ? "COOLDOWN" : "NOT_DUE",
                syncs.latest(user, planId).orElse(null), SyncWindow.seconds(wait));
        String purpose = completionRetryable(user, completed) ? PlanSyncRepository.COMPLETION
                : PlanSyncRepository.POST_END_VALUE;
        UUID jobId = UUID.randomUUID();
        var calculation = base.calculation();
        var lineage = base.lineage();
        int inserted = syncs.insert(new PlanSyncRepository.Admission(jobId, user, planId, calculation.portfolioId(),
                base.plan().intentId(), lineage.membershipId(), lineage.connectionId(), lineage.providerAccountId(),
                purpose, null, trigger.name(), key, base.pointer().calculationId(), base.pointer().version(),
                lineage.candidateId(), calculation.verifiedThrough(), lineage.lastExtendedAt(), calculation.t0(),
                calculation.endAt()));
        if (inserted != 1) {
            var raced = syncs.byKey(user, key).orElse(null);
            if (raced != null) return sameSync(raced, trigger) ? new SyncAdmission.Joined(raced)
                    : new SyncAdmission.Refused("IDEMPOTENCY_CONFLICT");
            return syncs.active(user, planId).<SyncAdmission>map(SyncAdmission.Joined::new)
                    .orElse(new SyncAdmission.Deferred(trigger == SyncTrigger.MANUAL ? "COOLDOWN" : "NOT_DUE",
                            syncs.latest(user, planId).orElse(null), 1L));
        }
        return new SyncAdmission.Accepted(syncs.byId(user, jobId).orElseThrow());
    }

    /**
     * Whether a completed plan's completion would run again (Story 2.14): no first observation yet, a comparison
     * pending, or one held by a review that is not persistent. A persistent review of the same base
     * ({@code completionHeldForGood}) holds the comparison for as long as that base is official — Story 2.12's rule:
     * asking again would only end the same way, so nothing re-collects it, and a sync only refreshes the post-end value.
     */
    static boolean completionOpen(PlanPublicationPort.PlanCompletion completion, boolean completionHeldForGood) {
        if (!completion.observed() || PlanPublicationService.COMPARISON_PENDING.equals(completion.comparison()))
            return true;
        return PlanPublicationService.COMPARISON_HELD.equals(completion.comparison()) && !completionHeldForGood;
    }

    /** {@link #completionOpen} of a completed plan, with the persistent review of its base read here. */
    boolean completionRetryable(UUID user, PlanSyncGuard.CompletedBase completed) {
        var completion = completed.completion();
        boolean held = PlanPublicationService.COMPARISON_HELD.equals(completion.comparison())
                && syncs.completionPersistentlyReviewed(user, completed.base().plan().planId(),
                        completed.base().pointer().calculationId());
        return completionOpen(completion, held);
    }

    /**
     * The wait of a completed plan's request (Story 2.14): 2.11's rules after the plan's last admission since its
     * completion only. Neither the official publication (its result lies before {@code T}) nor an admission before the
     * transition (a progress sync's) holds back the first post-end observation.
     */
    static @Nullable Duration completedWait(SyncWindow.Trigger trigger, Instant now, @Nullable Instant lastAdmission,
            Instant transitionedAt) {
        Instant since = lastAdmission != null && !lastAdmission.isBefore(transitionedAt) ? lastAdmission : null;
        return SyncWindow.wait(trigger, now, since, Instant.EPOCH);
    }

    @Nullable String completedUnavailableReason(UUID user, PlanSyncGuard.Base base) {
        var lineage = base.lineage();
        return guard.completionUnavailableReason(user, lineage.membershipId(), base.calculation().portfolioId(),
                lineage.connectionId(), lineage.providerAccountId());
    }

    /**
     * The sync part of a completed plan's dashboard (Story 2.14), by the completed plan's own admission: an attempt in
     * progress, a reason none can run, then the manual cooldown. There is no long gap here: a completed plan's request
     * never waits for a continuation.
     */
    SyncState completedState(UUID user, PlanSyncGuard.Base base, @Nullable Instant transitionedAt) {
        UUID planId = base.plan().planId();
        Instant now = publications.databaseNow();
        boolean inProgress = syncs.active(user, planId).isPresent();
        String reason = completedUnavailableReason(user, base);
        // Before the transition (an active plan whose T the clock reached) nothing was admitted since it.
        Duration manualWait = transitionedAt == null ? null : completedWait(SyncWindow.Trigger.MANUAL, now,
                syncs.lastAdmission(user, planId).orElse(null), transitionedAt);
        SyncWindow.Availability availability = SyncWindow.availability(inProgress, reason, false, manualWait);
        return new SyncState(availability.name(), availability == SyncWindow.Availability.UNAVAILABLE ? reason : null,
                availability == SyncWindow.Availability.COOLDOWN && manualWait != null ? SyncWindow.seconds(manualWait)
                        : null,
                syncs.latest(user, planId).orElse(null));
    }

    @Nullable String unavailableReason(UUID user, PlanSyncGuard.Base base, Instant now) {
        var lineage = base.lineage();
        return guard.unavailableReason(user, lineage.membershipId(), base.calculation().portfolioId(),
                lineage.connectionId(), lineage.providerAccountId(), base.calculation().endAt(), now);
    }

    /** More than 30 days since the version's verification, by the database clock or by a worker's Bybit time. */
    boolean longGap(UUID user, PlanSyncGuard.Base base, Instant now) {
        return SyncWindow.longGap(now, base.calculation().verifiedThrough())
                || syncs.longGapObserved(user, base.plan().planId(), base.pointer().calculationId());
    }

    /**
     * What the sync and the verification parts of the dashboard both judge from (Story 2.12), read once against the
     * database clock in the caller's read transaction: whether an attempt is in progress, why nothing could run, the
     * long gap, the manual cooldown after the plan's last admission of any purpose, and the plan's latest attempt.
     */
    record Facts(Instant now, boolean inProgress, @Nullable String unavailableReason, boolean longGap,
            @Nullable Duration manualWait, @Nullable SyncJob latest, PlanSyncRepository.@Nullable Review review) {}

    /** Why a sync cannot run while a persistent verification review holds its base (R6). */
    static final String REVIEW_HELD = "REVIEW_HELD";

    Facts facts(UUID user, PlanSyncGuard.Base base) {
        UUID planId = base.plan().planId();
        Instant now = publications.databaseNow();
        return new Facts(now, syncs.active(user, planId).isPresent(), unavailableReason(user, base, now),
                longGap(user, base, now), SyncWindow.wait(SyncWindow.Trigger.MANUAL, now,
                        syncs.lastAdmission(user, planId).orElse(null), base.pointer().publishedAt()),
                syncs.latest(user, planId).orElse(null),
                syncs.verificationReview(user, planId, base.pointer().calculationId()).orElse(null));
    }

    /**
     * The sync part of the dashboard, inside the caller's read transaction: the availability the admission would
     * judge now and the plan's latest attempt.
     */
    SyncState state(UUID user, PlanSyncGuard.Base base) {
        return state(facts(user, base));
    }

    SyncState state(Facts facts) {
        Duration manualWait = facts.manualWait();
        // The guard's own reasons first; then a persistent verification review holds the sync (R6).
        String reason = facts.unavailableReason() != null ? facts.unavailableReason()
                : facts.review() != null ? REVIEW_HELD : null;
        SyncWindow.Availability availability = SyncWindow.availability(facts.inProgress(), reason, facts.longGap(),
                manualWait);
        return new SyncState(availability.name(), availability == SyncWindow.Availability.UNAVAILABLE ? reason : null,
                availability == SyncWindow.Availability.COOLDOWN && manualWait != null ? SyncWindow.seconds(manualWait)
                        : null,
                facts.latest());
    }

    // ---------------------------------------------------------------- the worker

    /**
     * Claims the oldest queued attempt. An attempt whose holder lost its lease while running it is not run again: it
     * ends as interrupted, its children with it, and the user can sync again — unless it is a verification or
     * continuation still collecting (Story 2.12), which goes on from its checkpoints, twice at most.
     */
    Optional<PlanSyncRepository.Lease> claim() {
        AtomicReference<PlanSyncRepository.Ending> interrupted = new AtomicReference<>();
        PlanSyncRepository.Lease lease = transactions.execute(status -> {
            var claimed = syncs.claim().orElse(null);
            if (claimed == null) return null;
            if (claimed.interrupted()) {
                syncs.finish(claimed.lease(), "FAILED", "SYNC_INTERRUPTED").ifPresent(interrupted::set);
                return null;
            }
            return claimed.lease();
        });
        if (interrupted.get() != null) logEnding(interrupted.get());
        return Optional.ofNullable(lease);
    }

    boolean heartbeat(PlanSyncRepository.Lease lease) {
        return Boolean.TRUE.equals(transactions.execute(status -> syncs.heartbeat(lease)));
    }

    /**
     * Runs one attempt. {@code alive} is the worker's local lease guard; losing it (or the lease row) stops the
     * attempt before the next provider call and nothing more is written.
     */
    void run(PlanSyncRepository.Lease lease, BooleanSupplier alive) {
        RunStats stats = new RunStats();
        runs.put(lease, stats);
        try {
            if (!alive.getAsBoolean()) return;
            if (lease.completing()) {
                runCompletion(lease, alive, stats);
                return;
            }
            if (lease.postEnd()) {
                runPostEnd(lease, alive);
                return;
            }
            if (lease.verifying()) {
                runVerification(lease, alive, stats);
                return;
            }
            /*
             * A persistent verification review of this very base (interpretation C1) holds the sync before any
             * provider call, the time included: its 24-hour window would not reach a missing event, carry it silently
             * and publish a version that erased the review. It ends as that review, and the pointer stays.
             */
            String held = transactions.execute(status -> syncs.verificationReview(lease.userId(), lease.planId(),
                    lease.baseCalculationId()).map(PlanSyncRepository.Review::code).orElse(null));
            if (held != null) {
                end(lease, "NEEDS_REVIEW", held);
                return;
            }
            Instant tc = fixTime(lease, alive);
            if (tc == null) return;
            var scope = SyncWindow.scope(tc, lease.t0(), lease.endAt(), lease.baseVerifiedThrough(),
                    lease.baseExtendedAt());
            if (!(scope instanceof SyncWindow.Scope.Collect collect)) {
                switch (scope) {
                    case SyncWindow.Scope.LongGap gap -> end(lease, "BLOCKED", "LONG_GAP");
                    case SyncWindow.Scope.PeriodEnded ended -> end(lease, "BLOCKED", "PLAN_PERIOD_ENDED");
                    // A time that did not move past the last verification is not one this attempt can trust.
                    default -> end(lease, "FAILED", "PROVIDER_TIME_UNTRUSTED");
                }
                return;
            }
            if (!inTransaction(lease, () -> syncs.fixScope(lease, collect.mode().name(), collect.windowStart())))
                return;
            // A sync's one target: [window start, tc]. The completion rule is the verification's (Story 2.12).
            List<VerificationPlanner.Range> targets = List.of(
                    new VerificationPlanner.Range(VerificationPlanner.Kind.NEW, collect.windowStart(), tc));
            InitialCollectionRepository.Lease child = newChild(lease, tc, collect.windowStart());
            if (child == null) return;
            UUID candidateId = collectAndComplete(lease, alive, stats, child, tc, targets, true, false, true);
            if (candidateId == null || !alive.getAsBoolean()) return;
            Instant lastExtended = collect.mode() == SyncWindow.Mode.EXTENDED ? tc : lease.baseExtendedAt();
            classifyConvertPublish(lease, alive, candidateId, tc, lastExtended, Set.of());
        } catch (PlanSyncPublisher.LeaseLost | Ended stopped) {
            // Another holder owns the attempt now, or a stage already ended it: nothing more is written.
        } catch (PublicationBlocked blocked) {
            end(lease, "BLOCKED", blocked.code());
        } catch (RuntimeException failure) {
            // The job id and the class name only: a message or cause can carry SQL, provider text or values.
            LOG.warn("plan sync interrupted: job={} failure={}", lease.jobId(), failure.getClass().getName());
            end(lease, "FAILED", "SYNC_FAILED");
        } finally {
            // The ending line took the counters if this holder ended the attempt; a holder that did not (its lease
            // was lost, it stopped) leaves nothing behind either — a resumed holder counts afresh.
            runs.remove(lease, stats);
        }
    }

    /**
     * A precision verification or a long-gap continuation (Story 2.12), from wherever an earlier holder of the same
     * attempt stopped: its tc, its planned ranges and its complete windows are checkpoints.
     *
     * <ol>
     *   <li>{@code tc} is fixed once from Bybit's time (60 seconds from the database clock, as a sync's).
     *   <li>The same judgment as a sync's, but a continuation is not held back by the long gap it continues.
     *   <li>{@code NEW} {@code (verified, tc]} joins the planned targets; the window start is the earliest target's.
     *   <li>A target older than {@code tc - 730 days} ends it before any signed call ({@code HISTORY_OUT_OF_RANGE}).
     *   <li>The targets' windows are read — the ones already complete skipped — then a verification completes; a
     *       continuation fixes {@code tcFinal}, sweeps {@code [max(t0, min(tc, tcFinal - 7 days)), tcFinal]} again
     *       with the current equity and completes over all of it.
     *   <li>Classification, conversion — the candles of the flows inside the targets asked again — calculation and
     *       publication as a sync's, with the extended watermark at the new tc.
     * </ol>
     */
    private void runVerification(PlanSyncRepository.Lease lease, BooleanSupplier alive, RunStats stats) {
        var progress = Objects.requireNonNull(transactions.execute(status -> syncs.progress(lease)));
        Instant tc = progress.tc();
        if (tc == null) {
            tc = fixTime(lease, alive);
            if (tc == null) return;
        }
        Instant phaseTc = progress.initialTc() != null ? progress.initialTc() : tc;
        boolean catchUp = "CATCH_UP".equals(lease.purpose());
        Instant verified = lease.baseVerifiedThrough();
        if (!phaseTc.isAfter(verified)) {
            end(lease, "FAILED", "PROVIDER_TIME_UNTRUSTED");
            return;
        }
        if (!catchUp && SyncWindow.longGap(phaseTc, verified)) {
            end(lease, "BLOCKED", "LONG_GAP");
            return;
        }
        if (!phaseTc.isBefore(lease.endAt())) {
            end(lease, "BLOCKED", "PLAN_PERIOD_ENDED");
            return;
        }
        Instant fixed = phaseTc;
        List<VerificationPlanner.Range> planned = Objects.requireNonNull(transactions.execute(status -> {
            var ranges = syncs.ranges(lease.userId(), lease.jobId());
            if (ranges.stream().noneMatch(range -> range.kind() == VerificationPlanner.Kind.NEW)
                    && syncs.lockOwned(lease))
                syncs.insertRanges(lease.userId(), lease.jobId(), List.of(VerificationPlanner.newRange(verified, fixed)));
            return syncs.ranges(lease.userId(), lease.jobId());
        }));
        List<VerificationPlanner.Range> targets = planned.stream()
                .filter(range -> range.kind() != VerificationPlanner.Kind.REUSED).toList();
        if (targets.stream().noneMatch(range -> range.kind() == VerificationPlanner.Kind.NEW)) return;
        // Out of Bybit's history: nothing is promised there, and nothing signed is sent.
        if (VerificationPlanner.outOfRange(targets, phaseTc)) {
            end(lease, "NEEDS_REVIEW", "HISTORY_OUT_OF_RANGE");
            return;
        }
        Instant windowStart = VerificationPlanner.windowStart(targets);
        if (progress.mode() == null
                && !inTransaction(lease, () -> syncs.fixScope(lease, lease.purpose(), windowStart)))
            return;
        InitialCollectionRepository.Lease child;
        if (progress.collectionJobId() == null) {
            child = newChild(lease, phaseTc, windowStart);
        } else {
            // The permission is checked before the takeover, as a new child's is: a resumed holder may have no
            // window left to read, but it still signs the key, account and wallet calls.
            AtomicReference<InitialCollectionRepository.Lease> taken = new AtomicReference<>();
            if (!enterWith(lease, () -> taken.set(syncs.takeOverCollection(lease, progress.collectionJobId())
                    .orElse(null)))) return;
            child = taken.get();
            if (child == null) {
                // The collection the attempt started is gone or finished: it cannot go on from it.
                end(lease, "FAILED", "SYNC_INTERRUPTED");
                return;
            }
        }
        if (child == null) return;
        UUID candidateId;
        List<VerificationPlanner.Range> all = targets;
        Instant finalTc = tc;
        if (!catchUp) {
            candidateId = collectAndComplete(lease, alive, stats, child, phaseTc, targets, true, true, false);
        } else {
            List<VerificationPlanner.Range> firstPhase = targets.stream()
                    .filter(range -> range.kind() != VerificationPlanner.Kind.FINAL_SWEEP).toList();
            if (progress.initialTc() == null) {
                // The first phase: every window of [max(t0, verified - 7 days), tc], without the wallet.
                if (!collectOnly(lease, alive, stats, child, phaseTc, firstPhase)) return;
                Instant swept = fixFinalTime(lease, alive, child, phaseTc);
                if (swept == null) return;
                finalTc = swept;
            } else {
                if (!enter(lease, "FINAL_SWEEP")) return;
                finalTc = tc;
            }
            VerificationPlanner.Range sweep = VerificationPlanner.finalSweep(lease.t0(), phaseTc, finalTc);
            List<VerificationPlanner.Range> withSweep = new ArrayList<>(firstPhase);
            if (targets.stream().noneMatch(range -> range.kind() == VerificationPlanner.Kind.FINAL_SWEEP)) {
                if (!inTransaction(lease, () -> {
                    syncs.insertRanges(lease.userId(), lease.jobId(), List.of(sweep));
                    return true;
                })) return;
            }
            withSweep.add(sweep);
            all = List.copyOf(withSweep);
            InitialCollectionRepository.Lease extended = new InitialCollectionRepository.Lease(child.jobId(),
                    child.userId(), child.portfolioId(), child.intentId(), child.connectionId(),
                    child.providerAccountId(), child.t0(), finalTc, child.generation(), child.planSyncJobId());
            candidateId = collectAndComplete(lease, alive, stats, extended, finalTc, all, true, true, false);
        }
        if (candidateId == null || !alive.getAsBoolean()) return;
        List<VerificationPlanner.Range> scope = all;
        classifyConvertPublish(lease, alive, candidateId, finalTc, finalTc,
                java.util.Set.copyOf(scope.stream().map(VerificationPlanner.Range::interval).toList()));
    }

    // ---------------------------------------------------------------- the completion (Story 2.14)

    /**
     * A completed plan's completion attempt (Story 2.14, AC-2·AC-3·AC-6):
     *
     * <ol>
     *   <li>Without a first observation yet: Bybit's time must have reached {@code T} (trusted as a sync's {@code tc});
     *       the wallet is read once, a value measured at or after {@code T} is published as the next portfolio snapshot
     *       and fixed on the completion record — the earliest user-owned post-end snapshot, since no other path makes
     *       one. A fixed observation is never read again, by a retry or after a held comparison. After a data gap the
     *       attempt ends here: no history is collected.
     *   <li>{@code U} is fixed from Bybit's time again, at or after the observation's measurement, so that
     *       {@code T < ti <= measured} is judged on a complete history.
     *   <li>Story 2.12's plan of ranges up to the base verification — the unconfirmed week, any gap, the unexplained
     *       stored events — with {@code NEW} {@code (verified, U]}; the evidence the lineage already proved is reused.
     *       A target out of Bybit's history ends it, the comparison not computable.
     *   <li>The targets' windows, classification, conversion, the comparison and the completion calculation at
     *       {@code u = T}, and the atomic publication ({@link PlanSyncPublisher#publishCompletion}).
     * </ol>
     */
    private void runCompletion(PlanSyncRepository.Lease lease, BooleanSupplier alive, RunStats stats) {
        var record = completionOf(lease);
        if (record == null) {
            end(lease, "BLOCKED", "PLAN_CHANGED");
            return;
        }
        boolean dataGap = PlanPublicationService.COMPLETION_REASON_DATA_GAP.equals(record.reason());
        if (!record.observed()) {
            if (!timeReachedEnd(lease, alive)) return;
            if (!enter(lease, "OBSERVATION") || !alive.getAsBoolean()) return;
            var observation = readWallet(lease);
            if (observation == null || !alive.getAsBoolean()) return;
            var ending = publisher.publishObservation(lease, observation, dataGap);
            if (ending != null) {
                logEnding(lease, ending);
                return;
            }
            record = completionOf(lease);
            if (record == null || !record.observed()) {
                end(lease, "BLOCKED", "PLAN_CHANGED");
                return;
            }
        }
        if (dataGap) {
            // Only the observation is owed after a data gap; another attempt fixed it.
            var ended = transactions.execute(status -> syncs.lockOwned(lease) ? syncs.finishWithoutResult(lease)
                    : Optional.<PlanSyncRepository.Ending>empty());
            if (ended != null) ended.ifPresent(ending -> logEnding(lease, ending));
            return;
        }
        var fixed = fixedObservation(lease, record);
        if (fixed == null) {
            end(lease, "FAILED", "SYNC_FAILED");
            return;
        }
        Instant cutoff = fixCompletionTime(lease, alive, Objects.requireNonNull(record.observedMeasuredAt()));
        if (cutoff == null) return;
        Instant verified = lease.baseVerifiedThrough();
        List<VerificationPlanner.Range> planned = Objects.requireNonNull(transactions.execute(status -> {
            if (!syncs.lockOwned(lease)) throw new PlanSyncPublisher.LeaseLost();
            var plan = VerificationPlanner.precision(lease.t0(), verified,
                    collections.lineageEvidence(lease.userId(), lease.baseCandidateId()),
                    collections.unexplainedEvents(lease.userId(), lease.providerAccountId(), lease.baseCandidateId(),
                            lease.t0(), verified), List.of());
            List<VerificationPlanner.Range> ranges = new ArrayList<>(plan.targets());
            ranges.add(VerificationPlanner.newRange(verified, cutoff));
            ranges.addAll(plan.reused());
            syncs.insertRanges(lease.userId(), lease.jobId(), ranges);
            return syncs.ranges(lease.userId(), lease.jobId());
        }));
        List<VerificationPlanner.Range> targets = planned.stream()
                .filter(range -> range.kind() != VerificationPlanner.Kind.REUSED).toList();
        if (VerificationPlanner.outOfRange(targets, cutoff)) {
            end(lease, "NEEDS_REVIEW", PlanPublicationService.COMPLETION_HISTORY_OUT_OF_RANGE);
            return;
        }
        Instant windowStart = VerificationPlanner.windowStart(targets);
        if (!inTransaction(lease, () -> syncs.fixScope(lease, PlanSyncRepository.COMPLETION, windowStart))) return;
        InitialCollectionRepository.Lease child = newChild(lease, cutoff, windowStart);
        if (child == null) return;
        UUID candidateId = collectAndComplete(lease, alive, stats, child, cutoff, targets, false, true, false, fixed);
        if (candidateId == null || !alive.getAsBoolean()) return;
        UUID classificationId = classify(lease, candidateId);
        if (classificationId == null || !alive.getAsBoolean()) return;
        UUID conversionId = convert(lease, alive, classificationId, cutoff,
                Set.copyOf(targets.stream().map(VerificationPlanner.Range::interval).toList()));
        if (conversionId == null || !alive.getAsBoolean()) return;
        calculateAndPublishCompletion(lease, alive, candidateId, classificationId, conversionId, cutoff, record);
    }

    /**
     * A completion's Bybit time or wallet measurement has not reached {@code T} yet (Story 2.14): the ordinary clock
     * difference right after {@code T}, trusted but early. Retryable; not an untrusted clock.
     */
    static final String PROVIDER_END_NOT_REACHED = "PROVIDER_END_NOT_REACHED";

    /** The completion record of the attempt's plan, read in a transaction of its own. */
    private PlanPublicationPort.@Nullable PlanCompletion completionOf(PlanSyncRepository.Lease lease) {
        return transactions.execute(status -> plans.completion(lease.userId(), lease.planId(), false).orElse(null));
    }

    /**
     * The first observation as the collection carries it: the value, times and policy of the source snapshot the
     * completion published it from. {@code null} when that snapshot is not the record's.
     */
    private PlanSyncPublisher.@Nullable WalletObservation fixedObservation(PlanSyncRepository.Lease lease,
            PlanPublicationPort.PlanCompletion record) {
        return transactions.execute(status -> {
            var observation = syncs.observation(lease.userId(),
                    Objects.requireNonNull(record.observedPortfolioSnapshotId())).orElse(null);
            if (observation == null) return null;
            var source = publications.sourceOrigin(lease.userId(), observation.sourceSnapshotId()).orElse(null);
            if (source == null || !source.measuredAt().equals(record.observedMeasuredAt())) return null;
            return new PlanSyncPublisher.WalletObservation(source.equity(), source.measuredAt(), source.receivedAt(),
                    source.policyVersion());
        });
    }

    /**
     * Stage TIME before the first observation: Bybit's time, trusted as a sync's {@code tc} (60 seconds from the
     * database clock), must have reached {@code T} — otherwise no wallet is read and the attempt ends retryable. The
     * time is not kept: the cutoff {@code U} is fixed after the observation.
     */
    private boolean timeReachedEnd(PlanSyncRepository.Lease lease, BooleanSupplier alive) {
        if (!enter(lease, "TIME") || !alive.getAsBoolean()) return false;
        var time = sources.marketTime();
        if (time instanceof SourceConnectionApi.MarketTimeResult.Rejected) {
            end(lease, "FAILED", "PROVIDER_UNAVAILABLE");
            return false;
        }
        Instant now = ((SourceConnectionApi.MarketTimeResult.Observed) time).serverTime();
        try {
            finishWith(lease, () -> SyncWindow.untrustedTime(now, publications.databaseNow())
                    ? new String[] {"FAILED", "PROVIDER_TIME_UNTRUSTED"}
                    : now.isBefore(lease.endAt()) ? new String[] {"FAILED", PROVIDER_END_NOT_REACHED} : null);
        } catch (Ended ended) {
            return false;
        }
        return alive.getAsBoolean();
    }

    /**
     * Stage TIME after the first observation: the cutoff {@code U}, fixed on the attempt once and judged in the same
     * transaction — trusted, and at or after the observation's measurement.
     */
    private @Nullable Instant fixCompletionTime(PlanSyncRepository.Lease lease, BooleanSupplier alive,
            Instant measuredAt) {
        if (!enter(lease, "TIME") || !alive.getAsBoolean()) return null;
        var time = sources.marketTime();
        if (time instanceof SourceConnectionApi.MarketTimeResult.Rejected) {
            end(lease, "FAILED", "PROVIDER_UNAVAILABLE");
            return null;
        }
        Instant cutoff = ((SourceConnectionApi.MarketTimeResult.Observed) time).serverTime();
        try {
            finishWith(lease, () -> {
                if (!syncs.fixTime(lease, cutoff)) throw new PlanSyncPublisher.LeaseLost();
                return SyncWindow.untrustedTime(cutoff, publications.databaseNow()) || cutoff.isBefore(measuredAt)
                        ? new String[] {"FAILED", "PROVIDER_TIME_UNTRUSTED"} : null;
            });
        } catch (Ended ended) {
            return null;
        }
        return cutoff;
    }

    /**
     * Stage OBSERVATION: the wallet, read once outside any transaction, as a value measured at or after {@code T} on a
     * time trusted like {@code tc}. A provider refusal ends the attempt with its closed code; a measurement before
     * {@code T} or out of trust ends it retryable. {@code null} when the attempt ended.
     */
    private PlanSyncPublisher.@Nullable WalletObservation readWallet(PlanSyncRepository.Lease lease) {
        var result = sources.readEquity(lease.userId(), lease.connectionId());
        if (result instanceof SourceConnectionApi.EquityResult.Rejected rejected) {
            String[] ending = rejection(rejected.reason());
            end(lease, ending[0], ending[1]);
            return null;
        }
        var value = ((SourceConnectionApi.EquityResult.Observed) result).value();
        // To the millisecond before anything is stored or judged: the delay is then one exact number in SQL, in Java
        // and in the record's CHECK (the database keeps microseconds; Bybit's time is in milliseconds anyway).
        Instant measuredAt = value.measuredAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant receivedAt = value.receivedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        try {
            finishWith(lease, () -> SyncWindow.untrustedTime(measuredAt, publications.databaseNow())
                    ? new String[] {"FAILED", "PROVIDER_TIME_UNTRUSTED"}
                    : measuredAt.isBefore(lease.endAt()) ? new String[] {"FAILED", PROVIDER_END_NOT_REACHED} : null);
        } catch (Ended ended) {
            return null;
        }
        return new PlanSyncPublisher.WalletObservation(value.decimal(), measuredAt, receivedAt, value.policyVersion());
    }

    /**
     * Stages CALCULATION and PUBLICATION of a completion: the comparison (CV-11's order), the completion calculation at
     * {@code u = T} with the first observation as {@code E}, then the atomic publication.
     */
    private void calculateAndPublishCompletion(PlanSyncRepository.Lease lease, BooleanSupplier alive,
            UUID candidateId, UUID classificationId, UUID conversionId, Instant cutoff,
            PlanPublicationPort.PlanCompletion record) {
        AtomicReference<PlanSyncPublisher.Prepared> holder = new AtomicReference<>();
        if (!enterWith(lease, "CALCULATION", () -> holder.set(prepare(lease, candidateId, classificationId,
                conversionId, cutoff)))) return;
        PlanSyncPublisher.Prepared prepared = holder.get();
        if (!conformance.verified()) {
            end(lease, "FAILED", "CALCULATION_POLICY_UNVERIFIED");
            return;
        }
        var judgment = CompletionComparison.judge(lease.endAt(), Objects.requireNonNull(record.observedMeasuredAt()),
                prepared.flows().stream().map(Flow::occurredAt).toList());
        PlanCalculator.Result result = PlanCalculator.complete(PlanPublicationService.completionInput(
                prepared.pinned(), prepared.flows(), judgment.postEndCashflowCount() > 0));
        if (result instanceof PlanCalculator.Result.Rejected rejected) {
            end(lease, "FAILED", rejected.reason() == PlanCalculator.RejectionReason.CALCULATION_RANGE_EXCEEDED
                    ? "CALCULATION_RANGE_EXCEEDED" : "CALCULATION_INPUT_INVALID");
            return;
        }
        var calculated = (PlanCalculator.Result.Calculated) result;
        // The calculator judged the comparability from the same facts; a different answer is not one result.
        boolean comparable = calculated.metric(PlanCalculator.Metric.PLAN_DIFFERENCE).unavailableReason()
                != PlanCalculator.UnavailableReason.COMPLETION_NOT_COMPARABLE;
        if (comparable != judgment.comparable()) {
            end(lease, "FAILED", "CALCULATION_INPUT_INVALID");
            return;
        }
        if (!alive.getAsBoolean()) return;
        if (!enter(lease, "PUBLICATION")) return;
        try {
            logEnding(lease, publisher.publishCompletion(lease, prepared, calculated, judgment,
                    Objects.requireNonNull(record.observedPortfolioSnapshotId()), lease.endAt()));
        } catch (PublicationBlocked blocked) {
            end(lease, "BLOCKED", blocked.code());
        } catch (DuplicateKeyException racing) {
            end(lease, "BLOCKED", "POINTER_CONFLICT");
        } catch (org.springframework.dao.DataIntegrityViolationException refused) {
            // A schema rule refused the completion (V14's checks): it all rolled back; the attempt ends retryable now
            // rather than with its lease. The class name only: a message can carry SQL or values.
            LOG.warn("plan completion refused: job={} failure={}", lease.jobId(), refused.getClass().getName());
            end(lease, "FAILED", "SYNC_FAILED");
        }
    }

    /**
     * A completed plan's post-end value (Story 2.14, AC-7): Bybit's time ({@code tc}, trusted) and the wallet only,
     * published as the next portfolio snapshot. No history, calculation or plan pointer; the completion record and
     * the completion version stay as they are.
     */
    private void runPostEnd(PlanSyncRepository.Lease lease, BooleanSupplier alive) {
        if (fixTime(lease, alive) == null) return;
        if (!enter(lease, "OBSERVATION") || !alive.getAsBoolean()) return;
        var observation = readWallet(lease);
        if (observation == null || !alive.getAsBoolean()) return;
        try {
            logEnding(lease, publisher.publishPostEnd(lease, observation));
        } catch (PublicationBlocked blocked) {
            end(lease, "BLOCKED", blocked.code());
        }
    }

    /** Inserts and attaches the attempt's collection child. {@code null} when the attempt ended. */
    private InitialCollectionRepository.@Nullable Lease newChild(PlanSyncRepository.Lease lease, Instant tc,
            Instant windowStart) {
        UUID childId = UUID.randomUUID();
        AtomicReference<InitialCollectionRepository.Lease> holder = new AtomicReference<>();
        if (!enterWith(lease, () -> {
            holder.set(collections.insertIncremental(childId, lease, tc, windowStart));
            if (!syncs.attachCollection(lease, childId)) throw new PlanSyncPublisher.LeaseLost();
        })) return null;
        return holder.get();
    }

    /**
     * Stage TIME: the first provider call, the public server time, fixed on the attempt once. It is judged against
     * the database clock in the transaction that stores it: more than 60 seconds apart (either way), neither can be
     * the reference, and the attempt ends in that same transaction before any signed call — the value stays on the
     * attempt as evidence, but no holder ever finds a stored {@code tc} that was not judged (a resumed one reuses it).
     */
    private @Nullable Instant fixTime(PlanSyncRepository.Lease lease, BooleanSupplier alive) {
        if (!enter(lease, "TIME")) return null;
        if (!alive.getAsBoolean()) return null;
        var time = sources.marketTime();
        if (time instanceof SourceConnectionApi.MarketTimeResult.Rejected) {
            end(lease, "FAILED", "PROVIDER_UNAVAILABLE");
            return null;
        }
        Instant tc = ((SourceConnectionApi.MarketTimeResult.Observed) time).serverTime();
        finishWith(lease, () -> {
            if (!syncs.fixTime(lease, tc)) throw new PlanSyncPublisher.LeaseLost();
            return SyncWindow.untrustedTime(tc, publications.databaseNow())
                    ? new String[] {"FAILED", "PROVIDER_TIME_UNTRUSTED"} : null;
        });
        return tc;
    }

    /**
     * Stage FINAL_SWEEP of a continuation: the server time again, trusted as {@code tc} was (60 seconds from the
     * database clock) and after the first phase's cutoff, before the plan's end; the attempt and its collection move
     * their cutoff to it in one fenced transaction.
     *
     * @return {@code tcFinal}, or {@code null} when the attempt ended or lost its lease
     */
    private @Nullable Instant fixFinalTime(PlanSyncRepository.Lease lease, BooleanSupplier alive,
            InitialCollectionRepository.Lease child, Instant tc) {
        if (!enter(lease, "FINAL_SWEEP")) return null;
        if (!alive.getAsBoolean()) return null;
        var time = sources.marketTime();
        if (time instanceof SourceConnectionApi.MarketTimeResult.Rejected) {
            end(lease, "FAILED", "PROVIDER_UNAVAILABLE");
            return null;
        }
        Instant tcFinal = ((SourceConnectionApi.MarketTimeResult.Observed) time).serverTime();
        // Judged, fixed on the attempt and moved onto its collection in one transaction: either all of it holds or
        // none of it does (a failed step throws and rolls the others back), so a resumed holder never finds a
        // tcFinal that was not judged, or an attempt and a collection with different cutoffs.
        finishWith(lease, () -> {
            if (SyncWindow.untrustedTime(tcFinal, publications.databaseNow()) || !tcFinal.isAfter(tc))
                return new String[] {"FAILED", "PROVIDER_TIME_UNTRUSTED"};
            if (!tcFinal.isBefore(lease.endAt())) return new String[] {"BLOCKED", "PLAN_PERIOD_ENDED"};
            if (!collections.lockOwned(child) || !syncs.fixFinalTime(lease, tcFinal)
                    || !collections.extendCutoff(child, tcFinal))
                throw new PlanSyncPublisher.LeaseLost();
            return null;
        });
        return tcFinal;
    }

    /**
     * Stage COLLECTION: the given targets' windows not yet complete, pages stored as they come under the lease and
     * the permission, then — when {@code complete} — the candidate of the whole {@code (t0, tc]} reconciled and made.
     *
     * @param includeWallet whether the current equity is read (every completing collection)
     * @param record whether the reconciliation outcomes are kept (a verification's, never a sync's)
     * @param countPages whether every page must be this run's own (a sync; never resumed)
     * @return the new candidate, or {@code null} when the attempt ended or lost its lease
     */
    private @Nullable UUID collectAndComplete(PlanSyncRepository.Lease lease, BooleanSupplier alive, RunStats stats,
            InitialCollectionRepository.Lease child, Instant tc, List<VerificationPlanner.Range> targets,
            boolean includeWallet, boolean record, boolean countPages) {
        return collectAndComplete(lease, alive, stats, child, tc, targets, includeWallet, record, countPages, null);
    }

    /**
     * @param fixed a completion's first post-end observation (Story 2.14): the candidate carries it as its equity
     *     instead of a new wallet read — the observation is fixed once and never read again — with the collection's
     *     own cutoff {@code U}
     */
    private @Nullable UUID collectAndComplete(PlanSyncRepository.Lease lease, BooleanSupplier alive, RunStats stats,
            InitialCollectionRepository.Lease child, Instant tc, List<VerificationPlanner.Range> targets,
            boolean includeWallet, boolean record, boolean countPages,
            PlanSyncPublisher.@Nullable WalletObservation fixed) {
        var result = collectWindows(lease, alive, stats, child, tc, targets, includeWallet);
        if (result == null) return null;
        var value = fixed == null ? result.observation()
                : new SourceConnectionApi.CollectionObservation(result.providerAccountRef(), fixed.equity(),
                        fixed.measuredAt(), fixed.receivedAt(), result.tc(), result.pageCount(), result.reviewRequired(),
                        result.failureCode(), result.policyVersion());
        AtomicReference<UUID> candidate = new AtomicReference<>();
        finishWith(lease, () -> {
            if (!collections.lockOwned(child)) throw new PlanSyncPublisher.LeaseLost();
            var connection = sources.snapshotConnection(lease.userId()).orElse(null);
            if (connection == null || !connection.policyVersion().equals(value.policyVersion())
                    || !tc.equals(value.tc()))
                return new String[] {"BLOCKED", "NOT_PERMITTED"};
            // Every page that asked for review is on the collection (whichever holder or phase stored it), and so is
            // every event diagnostic: this run's own flag is only the part of it this holder saw.
            boolean needsReview = value.reviewRequired() || collections.pageReviewed(child.jobId())
                    || collections.hasDiagnostics(child.jobId());
            var stored = collections.completeTargets(child, value, needsReview, targets, lease.baseCandidateId(),
                    lease.baseVerifiedThrough(), record ? lease.jobId() : null, countPages);
            collections.finish(child, stored.status(), stored.failureCode());
            // The child keeps the collection's own code; the attempt speaks its closed vocabulary.
            if (stored.candidateId() == null)
                return new String[] {"NEEDS_REVIEW", "SOURCE_EVENT_MISSING".equals(stored.failureCode())
                        ? "SOURCE_EVENT_MISSING" : "SOURCE_REQUIRES_REVIEW"};
            candidate.set(stored.candidateId());
            return null;
        });
        return candidate.get();
    }

    /** A continuation's first phase: the windows only, no completion yet. False when the attempt ended. */
    private boolean collectOnly(PlanSyncRepository.Lease lease, BooleanSupplier alive, RunStats stats,
            InitialCollectionRepository.Lease child, Instant tc, List<VerificationPlanner.Range> targets) {
        List<SourceConnectionApi.CollectionWindow> pending = pendingWindows(child, targets);
        if (pending.isEmpty()) return true;
        // Its pages' review flags stay on the collection (storePage) for the completion after the final sweep.
        return collectWindows(lease, alive, stats, child, tc, targets, false) != null;
    }

    /** The targets' windows this collection has not read to the end yet, in order. */
    private List<SourceConnectionApi.CollectionWindow> pendingWindows(InitialCollectionRepository.Lease child,
            List<VerificationPlanner.Range> targets) {
        var complete = new java.util.HashSet<>(Objects.requireNonNull(
                transactions.execute(status -> collections.completeWindows(child.jobId()))));
        return VerificationPlanner.windows(targets).stream().filter(window -> !complete.contains(window))
                .map(window -> new SourceConnectionApi.CollectionWindow(window.start(), window.end())).toList();
    }

    /**
     * Reads the pending windows through the shared collection core. The evidence a stopped holder left inside a
     * pending window is discarded first (a resumed collection reads those windows again from their first page). A
     * provider rejection ends the attempt (and its collection child) with the closed code of {@link #rejection}; a
     * collection that stopped because the lease no longer holds ends nothing, as a lost lease never does.
     *
     * @return the collection, or {@code null} when the attempt ended, stopped or lost its lease
     */
    private SourceConnectionApi.@Nullable WindowCollection collectWindows(PlanSyncRepository.Lease lease,
            BooleanSupplier alive, RunStats stats, InitialCollectionRepository.Lease child, Instant tc,
            List<VerificationPlanner.Range> targets, boolean includeWallet) {
        List<SourceConnectionApi.CollectionWindow> pending = pendingWindows(child, targets);
        if (!inTransaction(lease, () -> {
            if (!collections.lockOwned(child)) return false;
            collections.discardPartialWindows(child, pending.stream()
                    .map(window -> new VerificationPlanner.Interval(window.start(), window.end())).toList());
            return true;
        })) return null;
        BooleanSupplier held = heldLease(lease, alive, child);
        var result = sources.collectWindows(lease.userId(), lease.connectionId(), lease.t0(), tc, pending,
                includeWallet, page -> {
                    if (!alive.getAsBoolean()) throw new PlanSyncPublisher.LeaseLost();
                    transactions.executeWithoutResult(status -> {
                        if (!syncs.lockOwned(lease) || !collections.lockOwned(child))
                            throw new PlanSyncPublisher.LeaseLost();
                        String blocked = guard.blocked(lease);
                        if (blocked != null) throw new PublicationBlocked(blocked);
                        collections.storePage(child, page.evidence(), page.events(), page.reviewRequired());
                    });
                }, held);
        if (!alive.getAsBoolean()) return null;
        switch (result) {
            case SourceConnectionApi.WindowCollectionResult.Stopped stopped -> {
                // The lease stopped holding during a wait: another holder owns the attempt, or none may. Nothing is
                // written — not a provider code, which would name a failure that did not happen.
                stats.add(pending.size(), stopped.pageCount(), stopped.retryCount());
                return null;
            }
            case SourceConnectionApi.WindowCollectionResult.Rejected rejected -> {
                stats.add(pending.size(), rejected.pageCount(), rejected.retryCount());
                String[] ending = rejection(rejected.reason());
                finishWith(lease, () -> {
                    collections.finish(child, "NEEDS_REVIEW".equals(ending[0]) ? "NEEDS_REVIEW" : "RETRY_REQUIRED",
                            ending[1]);
                    return new String[] {ending[0], ending[1]};
                });
                return null;
            }
            case SourceConnectionApi.WindowCollectionResult.Collected collected -> {
                stats.add(pending.size(), collected.value().pageCount(), collected.value().retryCount());
                return collected.value();
            }
        }
    }

    /** How long a database answer to "does this lease still hold" is reused by the waits that poll it. */
    private static final long LEASE_CHECK_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);

    /**
     * The lease guard of a collection's waits: the worker's in-memory guard at every poll (every 100 ms), and the
     * database's answer — the attempt and its collection still owned — at most once a second, reused in between. A
     * wait may therefore run up to a second past a lease lost in the database; every page is still stored under the
     * row locks, so nothing is written for it.
     */
    private BooleanSupplier heldLease(PlanSyncRepository.Lease lease, BooleanSupplier alive,
            InitialCollectionRepository.Lease child) {
        long[] checkedAt = {System.nanoTime() - LEASE_CHECK_NANOS};
        boolean[] owned = {true};
        return () -> {
            if (!alive.getAsBoolean()) return false;
            long now = System.nanoTime();
            if (now - checkedAt[0] >= LEASE_CHECK_NANOS) {
                owned[0] = Boolean.TRUE.equals(transactions.execute(status -> syncs.owns(lease)
                        && collections.owns(child)));
                checkedAt[0] = now;
            }
            return owned[0];
        };
    }

    /**
     * The attempt's ending for a provider rejection: a malformed answer needs review (Story 2.4: a format error
     * is a review); an authentication, permission, account or key refusal is the source's rejection; a
     * connection or account that is gone blocks; the rest (time-out, rate limit, 5xx, outage) is a provider
     * failure the user may retry by syncing again.
     */
    static String[] rejection(SourceRejectionReason reason) {
        return switch (reason) {
            case RESPONSE_FORMAT -> new String[] {"NEEDS_REVIEW", "SOURCE_REQUIRES_REVIEW"};
            case AUTHENTICATION_REJECTED, WRITE_PERMISSION, INSUFFICIENT_PERMISSION, SUB_ACCOUNT, IP_NOT_BOUND,
                    UNSUPPORTED_ACCOUNT_MODE, INVALID_FORMAT, PROVIDER_ACCOUNT_TAKEN ->
                    new String[] {"FAILED", "SOURCE_REJECTED"};
            case NOT_PERMITTED -> new String[] {"BLOCKED", "NOT_PERMITTED"};
            case TIMEOUT, RATE_LIMITED, PROVIDER_UNAVAILABLE, WALLET_UNAVAILABLE, UNAVAILABLE ->
                    new String[] {"FAILED", "PROVIDER_UNAVAILABLE"};
        };
    }

    /**
     * Stage CLASSIFICATION, inside one transaction: the new candidate classified by {@code BYBIT-CASHFLOW-1.0}
     * as a whole.
     *
     * @return the COMPLETE classification, or {@code null} when the attempt ended
     */
    private @Nullable UUID classify(PlanSyncRepository.Lease lease, UUID candidateId) {
        AtomicReference<UUID> classified = new AtomicReference<>();
        try {
            finishWith(lease, () -> {
                String blocked = guard.blocked(lease);
                if (blocked != null) return new String[] {"BLOCKED", blocked};
                UUID childId = UUID.randomUUID();
                var child = classifications.insertChild(childId, lease, candidateId);
                if (!syncs.attachClassification(lease, childId)) throw new PlanSyncPublisher.LeaseLost();
                var inputHash = classifications.inputHash(child);
                if (inputHash.isEmpty()) {
                    classifications.finish(child, "NEEDS_REVIEW", "INPUT_COMPLETENESS_INVALID");
                    return new String[] {"NEEDS_REVIEW", "CLASSIFICATION_REQUIRES_REVIEW"};
                }
                var inputs = classifications.inputs(child);
                var decisions = inputs.stream().map(input -> CashflowDecisionTable.decide(input.accountType(),
                        input.eventType(), input.currency(), input.cashFlow())).toList();
                classifications.publish(child, inputHash.get(), inputs, decisions,
                        () -> guard.blocked(lease) == null);
                if (!"COMPLETE".equals(classifications.bundleStatus(lease.userId(), childId).orElse(null)))
                    return new String[] {"NEEDS_REVIEW", "CLASSIFICATION_REQUIRES_REVIEW"};
                classified.set(classifications.bundleId(lease.userId(), childId).orElseThrow());
                return null;
            });
        } catch (CashflowClassificationRepository.PublicationBlocked blocked) {
            endBlocked(lease);
            return null;
        }
        return classified.get();
    }

    /**
     * Stage CONVERSION: the classification's flows converted at their event times by {@code BYBIT-PRICE-1.0},
     * with {@code tc} as the fixed reference time. Prices come from the shared cache first and from the public
     * market outside any transaction; the result is published under the lease.
     *
     * @return the calculable conversion, or {@code null} when the attempt ended or lost its lease
     */
    private @Nullable UUID convert(PlanSyncRepository.Lease lease, BooleanSupplier alive, UUID classificationId,
            Instant tc, Set<VerificationPlanner.Interval> recheck) {
        UUID childId = UUID.randomUUID();
        AtomicReference<CashflowConversionRepository.Lease> holder = new AtomicReference<>();
        AtomicBoolean admitted = new AtomicBoolean(false);
        finishWith(lease, () -> {
            String blocked = guard.blocked(lease);
            if (blocked != null) return new String[] {"BLOCKED", blocked};
            var child = conversions.admitChild(childId, lease, classificationId, tc).orElse(null);
            if (child == null) return new String[] {"FAILED", "SYNC_FAILED"};
            if (!syncs.attachConversion(lease, childId)) throw new PlanSyncPublisher.LeaseLost();
            holder.set(child);
            admitted.set(true);
            return null;
        });
        if (!admitted.get()) return null;
        CashflowConversionRepository.Lease child = holder.get();
        List<CashflowConversionRepository.Input> inputs = Objects.requireNonNull(
                transactions.execute(status -> conversions.storedInputs(child)));
        List<PriceConversionPolicy.FlowInput> flows = inputs.stream().map(CashflowConversionRepository.Input::flow)
                .toList();
        PriceConversionPolicy.Reference reference = flows.stream().anyMatch(PriceConversionPolicy::needsMarket)
                ? new PriceConversionPolicy.Reference.Fixed(tc) : null;
        BooleanSupplier active = () -> alive.getAsBoolean()
                && Boolean.TRUE.equals(transactions.execute(status -> syncs.owns(lease) && conversions.owns(child)));
        // A verification does not trust the cached candles of the flows inside its targets (Story 2.12): a close
        // the provider corrected becomes a new revision, and the change record shows the value it moved.
        Set<Instant> forced = new java.util.TreeSet<>();
        for (PriceConversionPolicy.FlowInput flow : flows) {
            if (recheck.stream().anyMatch(range -> range.contains(flow.occurredAt())))
                forced.add(PriceConversionPolicy.eventMinute(flow.occurredAt()));
        }
        List<PriceConversionPolicy.FlowOutcome> outcomes = market.convert(flows, reference, active, forced);
        if (outcomes == null || !alive.getAsBoolean()) return null;
        AtomicReference<UUID> converted = new AtomicReference<>();
        try {
            finishWith(lease, () -> {
                if (!conversions.lockOwned(child)) throw new PlanSyncPublisher.LeaseLost();
                String blocked = guard.blocked(lease);
                if (blocked != null) return new String[] {"BLOCKED", blocked};
                UUID conversionId = conversions.publish(child, inputs, outcomes, reference == null ? null : tc,
                        () -> guard.blocked(lease) == null && conversions.inputsStillValid(child));
                String status = conversions.publishedBundle(lease.userId(), childId).map(row -> row[1]).orElse("");
                return switch (status) {
                    case "CONVERTED", "CONVERTED_ESTIMATED" -> {
                        converted.set(conversionId);
                        yield null;
                    }
                    case "REVIEW_REQUIRED" -> new String[] {"NEEDS_REVIEW", "CONVERSION_REQUIRES_REVIEW"};
                    // A verification or continuation whose price is held for good (no route, no candle within five
                    // minutes, a price that is not positive) needs review — a persistent one: asking again reads the
                    // same market. Only a hold that time resolves (the event minute's candle not closed yet, the
                    // market unreachable) is a failure the user retries. A sync keeps Story 2.11's ending.
                    default -> lease.verifying() && permanentlyHeld(outcomes)
                            ? new String[] {"NEEDS_REVIEW", "CONVERSION_REQUIRES_REVIEW"}
                            : new String[] {"FAILED", "CONVERSION_REQUIRED"};
                };
            });
        } catch (CashflowConversionRepository.PublicationBlocked blocked) {
            endBlocked(lease);
            return null;
        }
        return converted.get();
    }

    /** Whether a held flow's reason is one time does not resolve ({@link PriceConversionPolicy.Reason#retryable}). */
    static boolean permanentlyHeld(List<PriceConversionPolicy.FlowOutcome> outcomes) {
        return outcomes.stream().anyMatch(outcome -> outcome.status() == PriceConversionPolicy.Status.CONVERSION_REQUIRED
                && outcome.reason() != null && !outcome.reason().retryable());
    }

    /**
     * The stages after the collection, the same for every purpose: classification, conversion (a verification asks
     * again for the candles of the flows inside {@code recheck}), the pure calculation and the atomic publication.
     */
    private void classifyConvertPublish(PlanSyncRepository.Lease lease, BooleanSupplier alive, UUID candidateId,
            Instant tc, Instant lastExtended, Set<VerificationPlanner.Interval> recheck) {
        UUID classificationId = classify(lease, candidateId);
        if (classificationId == null || !alive.getAsBoolean()) return;
        UUID conversionId = convert(lease, alive, classificationId, tc, recheck);
        if (conversionId == null || !alive.getAsBoolean()) return;
        calculateAndPublish(lease, alive, candidateId, classificationId, conversionId, lastExtended);
    }

    /** Stages CALCULATION and PUBLICATION: the pure calculation outside any transaction, then the atomic publication. */
    private void calculateAndPublish(PlanSyncRepository.Lease lease, BooleanSupplier alive, UUID candidateId,
            UUID classificationId, UUID conversionId, Instant lastExtended) {
        AtomicReference<PlanSyncPublisher.Prepared> holder = new AtomicReference<>();
        if (!enterWith(lease, "CALCULATION", () -> holder.set(prepare(lease, candidateId, classificationId,
                conversionId, lastExtended)))) return;
        PlanSyncPublisher.Prepared prepared = holder.get();
        if (!conformance.verified()) {
            end(lease, "FAILED", "CALCULATION_POLICY_UNVERIFIED");
            return;
        }
        PlanCalculator.Result result = PlanCalculator.calculate(
                PlanPublicationService.calculationInput(prepared.pinned(), prepared.flows()));
        if (result instanceof PlanCalculator.Result.Rejected rejected) {
            end(lease, "FAILED", rejected.reason() == PlanCalculator.RejectionReason.CALCULATION_RANGE_EXCEEDED
                    ? "CALCULATION_RANGE_EXCEEDED" : "CALCULATION_INPUT_INVALID");
            return;
        }
        if (!alive.getAsBoolean()) return;
        if (!enter(lease, "PUBLICATION")) return;
        try {
            logEnding(lease, publisher.publish(lease, prepared, (PlanCalculator.Result.Calculated) result));
        } catch (PublicationBlocked blocked) {
            end(lease, "BLOCKED", blocked.code());
        } catch (DuplicateKeyException racing) {
            // A unique index took a racing write (the portfolio or plan pointer): the pointer moved.
            end(lease, "BLOCKED", "POINTER_CONFLICT");
        }
    }

    /**
     * The calculator's inputs in Story 2.7's shape: the plan's immutable terms and basis from the base version,
     * the base version's portfolio pointer as the CAS expectation, and the attempt's own candidate (E(tc) and
     * {@code tc}), classification and calculable conversion with its flows.
     */
    private PlanSyncPublisher.Prepared prepare(PlanSyncRepository.Lease lease, UUID candidateId, UUID classificationId,
            UUID conversionId, Instant lastExtended) {
        UUID user = lease.userId();
        var base = publications.calculation(user, lease.baseCalculationId()).orElseThrow();
        var baseLineage = publications.lineage(user, lease.baseCalculationId()).orElseThrow();
        var candidate = publications.candidate(user, candidateId).orElseThrow();
        var conversion = publications.conversion(user, conversionId).orElseThrow();
        List<Flow> flows = publications.flows(user, conversionId);
        if (!conversion.calculable() || flows.size() != conversion.flowCount()
                || !conversion.classificationId().equals(classificationId))
            throw new IllegalStateException("the sync's conversion is not one calculable lineage");
        String inputHash = PlanSyncPublisher.inputHash(lease, candidate, classificationId, conversion, flows);
        Pinned pinned = new Pinned(lease.jobId(), user, lease.portfolioId(), lease.intentId(), lease.membershipId(),
                lease.connectionId(), lease.providerAccountId(), base.basisPortfolioSnapshotId(),
                baseLineage.basisSourceSnapshotId(), base.portfolioSnapshotId(), base.portfolioSequence(), candidateId,
                classificationId, conversionId, PlanPublicationService.notation(base.principal()), base.t0(),
                base.endAt(), base.period(), PlanPublicationService.notation(base.targetRate()), candidate.tc(),
                PlanPublicationService.notation(candidate.equity()), candidate.measuredAt(), flows.size(), inputHash,
                base.calculationPolicy(), base.calendarPolicy());
        return new PlanSyncPublisher.Prepared(pinned, candidate, conversion, flows,
                publications.classificationPolicy(classificationId), lastExtended, inputHash);
    }

    // ---------------------------------------------------------------- transactions and endings

    /** A stage transaction's body: {@code null} to go on, or {@code {status, code}} to end the attempt with. */
    @FunctionalInterface
    private interface StageBody {
        String @Nullable [] run();
    }

    /**
     * Runs a stage body in one transaction under the lease: the attempt row is locked first (serializing with
     * erasure and the heartbeat), the body runs, and an ending it returns is written in the same transaction.
     * The ending line is written once that transaction committed.
     *
     * @throws PlanSyncPublisher.LeaseLost when the lease no longer owns the attempt
     */
    private void finishWith(PlanSyncRepository.Lease lease, StageBody body) {
        AtomicReference<PlanSyncRepository.Ending> ended = new AtomicReference<>();
        transactions.executeWithoutResult(status -> {
            if (!syncs.lockOwned(lease)) throw new PlanSyncPublisher.LeaseLost();
            String[] ending = body.run();
            if (ending != null) ended.set(finishAttempt(lease, ending[0], ending[1])
                    .orElseThrow(PlanSyncPublisher.LeaseLost::new));
        });
        if (ended.get() != null) {
            logEnding(lease, ended.get());
            throw new Ended();
        }
    }

    /** The attempt ended inside a stage (its line is written); the run stops without writing more. */
    private static final class Ended extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Ended() {
            super(null, null, false, false);
        }
    }

    /** Enters a stage: lease and permission first, then the stage recorded. False when the attempt ended. */
    private boolean enter(PlanSyncRepository.Lease lease, String stage) {
        return enterWith(lease, stage, () -> { });
    }

    private boolean enterWith(PlanSyncRepository.Lease lease, Runnable body) {
        return enterWith(lease, null, body);
    }

    private boolean enterWith(PlanSyncRepository.Lease lease, @Nullable String stage, Runnable body) {
        try {
            finishWith(lease, () -> {
                String blocked = guard.blocked(lease);
                if (blocked != null) return new String[] {"BLOCKED", blocked};
                if (stage != null && !syncs.stage(lease, stage)) throw new PlanSyncPublisher.LeaseLost();
                body.run();
                return null;
            });
            return true;
        } catch (Ended ended) {
            return false;
        }
    }

    /** Runs a fenced write under the lease. False when the lease no longer owns the attempt. */
    private boolean inTransaction(PlanSyncRepository.Lease lease, BooleanSupplier write) {
        return Boolean.TRUE.equals(transactions.execute(status -> syncs.lockOwned(lease) && write.getAsBoolean()));
    }

    /** Ends the attempt in a transaction of its own, fenced, and writes its one line. */
    private void end(PlanSyncRepository.Lease lease, String status, String code) {
        var ended = transactions.execute(state -> syncs.lockOwned(lease) ? finishAttempt(lease, status, code)
                : Optional.<PlanSyncRepository.Ending>empty());
        if (ended != null) ended.ifPresent(ending -> logEnding(lease, ending));
    }

    /**
     * Ends the attempt with a closed code, in the caller's transaction under the lease. A completion that ends in review
     * (Story 2.14, AC-2) writes its comparison in the same transaction: out of the provider's history it is not
     * computable ({@code HISTORY_OUT_OF_RANGE}), any other review holds it ({@code INPUT_REVIEW_REQUIRED}) — the plan
     * stays completed and its last official result as it was. Every other ending leaves the comparison as it is.
     */
    private Optional<PlanSyncRepository.Ending> finishAttempt(PlanSyncRepository.Lease lease, String status,
            String code) {
        var ended = syncs.finish(lease, status, code);
        if (ended.isPresent() && lease.completing() && "NEEDS_REVIEW".equals(status)) {
            boolean outOfRange = PlanPublicationService.COMPLETION_HISTORY_OUT_OF_RANGE.equals(code);
            plans.recordCompletionComparison(lease.userId(), lease.planId(),
                    outOfRange ? PlanPublicationService.COMPARISON_UNAVAILABLE : PlanPublicationService.COMPARISON_HELD,
                    outOfRange ? PlanPublicationService.COMPLETION_HISTORY_OUT_OF_RANGE
                            : PlanPublicationService.COMPLETION_INPUT_REVIEW,
                    null, null);
        }
        return ended;
    }

    /** A stage's own permission check refused a publication: the guard says why, or the inputs moved. */
    private void endBlocked(PlanSyncRepository.Lease lease) {
        String code = transactions.execute(status -> guard.blocked(lease));
        if (code != null) end(lease, "BLOCKED", code);
        else end(lease, "FAILED", "SYNC_FAILED");
    }

    /**
     * The attempt's one ending line: what it was, how it ended, how much its collection read (targets, windows,
     * pages, page retries), how many reconciliation outcomes it recorded and late changes it published, the latency
     * and the policies. No user, amount, credential, provider text or Bybit id.
     */
    void logEnding(PlanSyncRepository.Ending e) {
        logEnding(null, e);
    }

    /**
     * The ending line of an attempt a holder ended, with that holder's own collection counters (a resumed holder counts
     * what it read itself; the holder before it in this process keeps and drops its own).
     */
    private void logEnding(PlanSyncRepository.@Nullable Lease holder, PlanSyncRepository.Ending e) {
        RunStats stats = holder == null ? null : runs.remove(holder);
        int[] facts;
        try {
            facts = Objects.requireNonNull(transactions.execute(status -> syncs.endingFacts(e.jobId())));
        } catch (RuntimeException unreadable) {
            facts = new int[] {0, 0, 0};
        }
        LOG.info("plan sync finished: job={} purpose={} reason={} trigger={} mode={} status={} stage={} code={} "
                + "targets={} windows={} pages={} retries={} reconciled={} lateChanges={} latencyMs={} policies={}",
                e.jobId(), e.purpose(), e.reason() == null ? "-" : e.reason(), e.trigger(),
                e.mode() == null ? "-" : e.mode(), e.status(), e.stage() == null ? "-" : e.stage(),
                e.failureCode() == null ? "-" : e.failureCode(), facts[0], stats == null ? 0 : stats.windows.get(),
                stats == null ? 0 : stats.pages.get(), stats == null ? 0 : stats.retries.get(), facts[1], facts[2],
                e.latencyMs(), POLICIES);
    }

}
