package com.bokrilog.sync.internal;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceRejectionReason;
import com.bokrilog.sync.SyncJobApi.SyncAdmission;
import com.bokrilog.sync.SyncJobApi.SyncJob;
import com.bokrilog.sync.SyncJobApi.SyncState;
import com.bokrilog.sync.SyncJobApi.SyncTrigger;
import com.bokrilog.sync.internal.PlanPublicationRepository.Flow;
import com.bokrilog.sync.internal.PlanPublicationRepository.Pinned;
import com.bokrilog.sync.internal.PlanPublicationRepository.PublicationBlocked;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * The user-triggered incremental sync of an active plan (Story 2.11, ERD-1.0.0 §2.2, SYNC-UX-1.0).
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
 * <p><b>Observation</b>: every attempt that ends writes exactly one line — the job, trigger, mode, status, stage,
 * code, latency and policy versions — and nothing else: no user, amount, credential or provider text.
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

    PlanSyncService(PlanSyncRepository syncs, PlanPublicationRepository publications,
            InitialCollectionRepository collections, CashflowClassificationRepository classifications,
            CashflowConversionRepository conversions, SourceConnectionApi sources, CalculationConformance conformance,
            PlanSyncGuard guard, PlanSyncPublisher publisher, PlatformTransactionManager manager) {
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
        // One key rule for the plan's commands: the publication's.
        if (key == null || !PlanPublicationService.validKey(key))
            return new SyncAdmission.Refused("IDEMPOTENCY_KEY_REQUIRED");
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
        if (keyed != null) return keyed.trigger().equals(trigger.name()) ? new SyncAdmission.Joined(keyed.job())
                : new SyncAdmission.Refused("IDEMPOTENCY_CONFLICT");
        var base = guard.base(user).orElse(null);
        if (base == null) return new SyncAdmission.Refused("NO_ACTIVE_PLAN");
        UUID planId = base.plan().planId();
        var active = syncs.active(user, planId).orElse(null);
        if (active != null) return new SyncAdmission.Joined(active);
        Instant now = publications.databaseNow();
        if (unavailableReason(user, base, now) != null) return new SyncAdmission.Refused("SYNC_UNAVAILABLE");
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
                trigger.name(), key, base.pointer().calculationId(), base.pointer().version(), lineage.candidateId(),
                calculation.verifiedThrough(), lineage.lastExtendedAt(), calculation.t0(), calculation.endAt()));
        if (inserted != 1) {
            // The key or the in-progress index was taken in between: the same key, or the attempt it let stand.
            var raced = syncs.byKey(user, key).orElse(null);
            if (raced != null) return raced.trigger().equals(trigger.name()) ? new SyncAdmission.Joined(raced.job())
                    : new SyncAdmission.Refused("IDEMPOTENCY_CONFLICT");
            return syncs.active(user, planId).<SyncAdmission>map(SyncAdmission.Joined::new)
                    .orElse(new SyncAdmission.Deferred(trigger == SyncTrigger.MANUAL ? "COOLDOWN" : "NOT_DUE",
                            syncs.latest(user, planId).orElse(null), 1L));
        }
        return new SyncAdmission.Accepted(syncs.byId(user, jobId).orElseThrow());
    }

    private @Nullable String unavailableReason(UUID user, PlanSyncGuard.Base base, Instant now) {
        var lineage = base.lineage();
        return guard.unavailableReason(user, lineage.membershipId(), base.calculation().portfolioId(),
                lineage.connectionId(), lineage.providerAccountId(), base.calculation().endAt(), now);
    }

    /** More than 30 days since the version's verification, by the database clock or by a worker's Bybit time. */
    private boolean longGap(UUID user, PlanSyncGuard.Base base, Instant now) {
        return SyncWindow.longGap(now, base.calculation().verifiedThrough())
                || syncs.longGapObserved(user, base.plan().planId(), base.pointer().calculationId());
    }

    /**
     * The sync part of the dashboard, inside the caller's read transaction: the availability the admission would
     * judge now and the plan's latest attempt.
     */
    SyncState state(UUID user, PlanSyncGuard.Base base) {
        UUID planId = base.plan().planId();
        boolean inProgress = syncs.active(user, planId).isPresent();
        SyncJob latest = syncs.latest(user, planId).orElse(null);
        Instant now = publications.databaseNow();
        String reason = unavailableReason(user, base, now);
        Duration manualWait = SyncWindow.wait(SyncWindow.Trigger.MANUAL, now,
                syncs.lastAdmission(user, planId).orElse(null), base.pointer().publishedAt());
        SyncWindow.Availability availability = SyncWindow.availability(inProgress, reason, longGap(user, base, now),
                manualWait);
        return new SyncState(availability.name(), availability == SyncWindow.Availability.UNAVAILABLE ? reason : null,
                availability == SyncWindow.Availability.COOLDOWN && manualWait != null ? SyncWindow.seconds(manualWait)
                        : null,
                latest);
    }

    // ---------------------------------------------------------------- the worker

    /**
     * Claims the oldest queued attempt. An attempt whose holder lost its lease while running is not run again: it
     * ends as interrupted, its children with it, and the user can sync again.
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
        try {
            if (!alive.getAsBoolean()) return;
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
            UUID candidateId = collect(lease, alive, tc, collect.windowStart());
            if (candidateId == null || !alive.getAsBoolean()) return;
            UUID classificationId = classify(lease, candidateId);
            if (classificationId == null || !alive.getAsBoolean()) return;
            UUID conversionId = convert(lease, alive, classificationId, tc);
            if (conversionId == null || !alive.getAsBoolean()) return;
            Instant lastExtended = collect.mode() == SyncWindow.Mode.EXTENDED ? tc : lease.baseExtendedAt();
            calculateAndPublish(lease, alive, candidateId, classificationId, conversionId, lastExtended);
        } catch (PlanSyncPublisher.LeaseLost | Ended stopped) {
            // Another holder owns the attempt now, or a stage already ended it: nothing more is written.
        } catch (PublicationBlocked blocked) {
            end(lease, "BLOCKED", blocked.code());
        } catch (RuntimeException failure) {
            // The job id and the class name only: a message or cause can carry SQL, provider text or values.
            LOG.warn("plan sync interrupted: job={} failure={}", lease.jobId(), failure.getClass().getName());
            end(lease, "FAILED", "SYNC_FAILED");
        }
    }

    /**
     * Stage TIME: the first provider call, the public server time, fixed on the attempt once. Before anything else
     * uses it, it is compared with the database clock: more than 60 seconds apart (either way), neither can be the
     * reference, and the attempt ends before any signed call. The fixed value stays on the attempt as evidence.
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
        AtomicReference<Instant> databaseNow = new AtomicReference<>();
        if (!inTransaction(lease, () -> {
            databaseNow.set(publications.databaseNow());
            return syncs.fixTime(lease, tc);
        })) return null;
        if (SyncWindow.untrustedTime(tc, Objects.requireNonNull(databaseNow.get()))) {
            end(lease, "FAILED", "PROVIDER_TIME_UNTRUSTED");
            return null;
        }
        return tc;
    }

    /**
     * Stage COLLECTION: every required window from the window start to {@code tc}, pages stored as they come
     * under the lease and the permission, and the candidate of the whole {@code (t0, tc]} completed.
     *
     * @return the new candidate, or {@code null} when the attempt ended or lost its lease
     */
    private @Nullable UUID collect(PlanSyncRepository.Lease lease, BooleanSupplier alive, Instant tc,
            Instant windowStart) {
        UUID childId = UUID.randomUUID();
        AtomicReference<InitialCollectionRepository.Lease> holder = new AtomicReference<>();
        if (!enterWith(lease, () -> {
            holder.set(collections.insertIncremental(childId, lease, tc, windowStart));
            if (!syncs.attachCollection(lease, childId)) throw new PlanSyncPublisher.LeaseLost();
        })) return null;
        InitialCollectionRepository.Lease child = holder.get();
        AtomicBoolean review = new AtomicBoolean(false);
        var result = sources.collectIncrementalInputs(lease.userId(), lease.connectionId(), lease.t0(), windowStart, tc,
                page -> {
                    if (!alive.getAsBoolean()) throw new PlanSyncPublisher.LeaseLost();
                    Boolean pageReview = transactions.execute(status -> {
                        if (!syncs.lockOwned(lease) || !collections.lockOwned(child))
                            throw new PlanSyncPublisher.LeaseLost();
                        String blocked = guard.blocked(lease);
                        if (blocked != null) throw new PublicationBlocked(blocked);
                        return collections.storePage(child, page.evidence(), page.events(), page.reviewRequired());
                    });
                    if (Boolean.TRUE.equals(pageReview)) review.set(true);
                });
        if (!alive.getAsBoolean()) return null;
        if (result instanceof SourceConnectionApi.CollectionResult.Rejected rejected) {
            String[] ending = rejection(rejected.reason());
            finishWith(lease, () -> {
                collections.finish(child, "NEEDS_REVIEW".equals(ending[0]) ? "NEEDS_REVIEW" : "RETRY_REQUIRED",
                        ending[1]);
                return new String[] {ending[0], ending[1]};
            });
            return null;
        }
        var value = ((SourceConnectionApi.CollectionResult.Collected) result).value();
        AtomicReference<UUID> candidate = new AtomicReference<>();
        finishWith(lease, () -> {
            if (!collections.lockOwned(child)) throw new PlanSyncPublisher.LeaseLost();
            var connection = sources.snapshotConnection(lease.userId()).orElse(null);
            if (connection == null || !connection.policyVersion().equals(value.policyVersion())
                    || !tc.equals(value.tc()))
                return new String[] {"BLOCKED", "NOT_PERMITTED"};
            var stored = collections.completeIncremental(child, value, review.get() || value.reviewRequired(),
                    windowStart, lease.baseCandidateId());
            collections.finish(child, stored.status(), stored.failureCode());
            if (stored.candidateId() == null) return new String[] {"NEEDS_REVIEW", "SOURCE_REQUIRES_REVIEW"};
            candidate.set(stored.candidateId());
            return null;
        });
        return candidate.get();
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
            Instant tc) {
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
        List<PriceConversionPolicy.FlowOutcome> outcomes = market.convert(flows, reference, active);
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
                    default -> new String[] {"FAILED", "CONVERSION_REQUIRED"};
                };
            });
        } catch (CashflowConversionRepository.PublicationBlocked blocked) {
            endBlocked(lease);
            return null;
        }
        return converted.get();
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
            logEnding(publisher.publish(lease, prepared, (PlanCalculator.Result.Calculated) result));
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
            if (ending != null) ended.set(syncs.finish(lease, ending[0], ending[1])
                    .orElseThrow(PlanSyncPublisher.LeaseLost::new));
        });
        if (ended.get() != null) {
            logEnding(ended.get());
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
        var ended = transactions.execute(state -> syncs.lockOwned(lease) ? syncs.finish(lease, status, code)
                : Optional.<PlanSyncRepository.Ending>empty());
        if (ended != null) ended.ifPresent(this::logEnding);
    }

    /** A stage's own permission check refused a publication: the guard says why, or the inputs moved. */
    private void endBlocked(PlanSyncRepository.Lease lease) {
        String code = transactions.execute(status -> guard.blocked(lease));
        if (code != null) end(lease, "BLOCKED", code);
        else end(lease, "FAILED", "SYNC_FAILED");
    }

    void logEnding(PlanSyncRepository.Ending e) {
        LOG.info("plan sync finished: job={} trigger={} mode={} status={} stage={} code={} latencyMs={} policies={}",
                e.jobId(), e.trigger(), e.mode() == null ? "-" : e.mode(), e.status(),
                e.stage() == null ? "-" : e.stage(), e.failureCode() == null ? "-" : e.failureCode(), e.latencyMs(),
                POLICIES);
    }

}
