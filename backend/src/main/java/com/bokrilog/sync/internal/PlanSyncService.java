package com.bokrilog.sync.internal;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceRejectionReason;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.SyncJobApi.ManualSync;
import com.bokrilog.sync.SyncJobApi.SyncAdmission;
import com.bokrilog.sync.SyncJobApi.SyncAnswer;
import com.bokrilog.sync.SyncJobApi.SyncJob;
import com.bokrilog.sync.SyncJobApi.SyncState;
import com.bokrilog.sync.SyncJobApi.SyncTrigger;
import com.bokrilog.sync.internal.PlanPublicationRepository.CalculationView;
import com.bokrilog.sync.internal.PlanPublicationRepository.Candidate;
import com.bokrilog.sync.internal.PlanPublicationRepository.Flow;
import com.bokrilog.sync.internal.PlanPublicationRepository.Lineage;
import com.bokrilog.sync.internal.PlanPublicationRepository.Pinned;
import com.bokrilog.sync.internal.PlanSyncRepository.Ending;
import com.bokrilog.sync.internal.PlanSyncRepository.EventFacts;
import com.bokrilog.sync.internal.PlanSyncRepository.Job;
import com.bokrilog.sync.internal.PlanSyncRepository.Lease;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The active plan's incremental sync at the user's moment (Story 2.11, ERD-1.0.0 §2.2, SYNC-UX-1.0, AD-23).
 *
 * <p><b>Admission</b> is judged against the database clock and never calls the provider: the account, the
 * restrictions, the active plan and its pointer, the official version's connection and membership, the plan
 * period, an attempt in progress, the thirty-day gap and the trigger's timing rule — in that order. The plan has
 * at most one attempt in progress; the partial unique index decides a race, and the loser answers with the
 * winner's reference.
 *
 * <p><b>The worker</b> holds one lease. It fixes the Bybit reference time {@code tc} once, judges the gap, the
 * plan period and the mode against it, and then runs the Story 2.4 collection, the 2.5 classification and the
 * 2.6 conversion through their repositories on stage rows it owns, and the 2.7 pure calculation. No provider
 * call runs inside a transaction; every write runs in a short transaction that locks the job row first and
 * re-checks the fence, the deletion and restriction state, the plan and its pointer, the connection and the
 * membership. A lost lease is never continued.
 *
 * <p><b>The publication</b> is one outer {@code REQUIRED} transaction owned here, in the first publication's
 * lock order: the tc source snapshot, the portfolio pointer CAS, the calculation version with its metrics,
 * flows and exclusions, the plan pointer CAS with its history, and the job's ending. Anything failing rolls it
 * all back; nothing here uses {@code REQUIRES_NEW}, asynchronous or after-commit publication.
 */
@Service
class PlanSyncService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PlanSyncService.class);

    /** The plan sync's own command. Its restriction stops admissions and running attempts alike. */
    static final String PLAN_SYNC_COMMAND = "plan-sync";

    /** Syncs one process runs at once (ERD §2.5: two Bybit sync connections to start with). */
    static final int MAX_CONCURRENT = 2;

    /** The normal overlap before the last verification (ERD §2.2). */
    static final Duration NORMAL_OVERLAP = Duration.ofHours(24);
    /** The extended overlap, and how long after the last extended verification the next sync extends. */
    static final Duration EXTENDED_OVERLAP = Duration.ofDays(7);
    static final Duration EXTENDED_INTERVAL = Duration.ofDays(7);
    /** More than this unverified is a long gap: no automatic sync until the user chooses (Story 2.12/2.13). */
    static final Duration LONG_GAP = Duration.ofDays(30);
    /** Entry and reactivation admit at most once in this interval (SYNC-UX-1.0). */
    static final Duration AUTOMATIC_INTERVAL = Duration.ofMinutes(5);
    /** A button press admits at most once in this interval, whatever the trigger or the result. */
    static final Duration MANUAL_COOLDOWN = Duration.ofSeconds(30);
    /** The Transaction Log's request window (ERD §2.2). */
    static final Duration WINDOW = Duration.ofDays(7);
    /** A queued job older than this is never claimed; it ends as interrupted (PlanSyncRepository#expireLost). */
    static final Duration QUEUED_LIMIT = Duration.ofMinutes(5);
    /**
     * The longest a worker holds one lease. Past it, the heartbeat stops renewing: a stalled attempt loses its
     * lease within one lease period, ends as interrupted, and frees its place under {@link #MAX_CONCURRENT}.
     */
    static final Duration MAX_RUN = Duration.ofMinutes(10);

    private final PlanSyncRepository syncs;
    private final PlanPublicationRepository publications;
    private final InitialCollectionRepository collections;
    private final CashflowClassificationRepository classifications;
    private final CashflowConversionRepository conversions;
    private final SourceConnectionApi sources;
    private final PortfolioMembershipApi portfolios;
    private final AccountAccessApi accounts;
    private final PlanPublicationPort plans;
    private final PlanPublicationService publicationService;
    private final CalculationConformance conformance;
    private final TransactionTemplate transactions;
    private final CashflowConversionMarket market;

    PlanSyncService(PlanSyncRepository syncs, PlanPublicationRepository publications,
            InitialCollectionRepository collections, CashflowClassificationRepository classifications,
            CashflowConversionRepository conversions, SourceConnectionApi sources, PortfolioMembershipApi portfolios,
            AccountAccessApi accounts, PlanPublicationPort plans, PlanPublicationService publicationService,
            CalculationConformance conformance, PlatformTransactionManager manager) {
        this.syncs = syncs;
        this.publications = publications;
        this.collections = collections;
        this.classifications = classifications;
        this.conversions = conversions;
        this.sources = sources;
        this.portfolios = portfolios;
        this.accounts = accounts;
        this.plans = plans;
        this.publicationService = publicationService;
        this.conformance = conformance;
        this.transactions = new TransactionTemplate(manager);
        this.market = new CashflowConversionMarket(sources, conversions, this.transactions);
    }

    // ---------------------------------------------------------------- the rules of ERD §2.2

    /** {@code EXTENDED} once seven days passed since the last extended verification, else {@code NORMAL}. */
    static String mode(Instant tc, Instant lastExtendedVerificationAt) {
        return Duration.between(lastExtendedVerificationAt, tc).compareTo(EXTENDED_INTERVAL) >= 0 ? "EXTENDED" : "NORMAL";
    }

    /** {@code max(t0, cashflow_verified_through - overlap)}: 24 hours, or 7 days for an extended sync. */
    static Instant rangeStart(Instant t0, Instant verifiedThrough, String mode) {
        Instant overlapped = verifiedThrough.minus("EXTENDED".equals(mode) ? EXTENDED_OVERLAP : NORMAL_OVERLAP);
        return overlapped.isAfter(t0) ? overlapped : t0;
    }

    /** More than thirty days unverified. Exactly thirty days is still an ordinary sync (ERD §2.2). */
    static boolean longGap(Instant verifiedThrough, Instant now) {
        return Duration.between(verifiedThrough, now).compareTo(LONG_GAP) > 0;
    }

    /**
     * The whole seconds left of a button press's cooldown after the last admission, at least one while any of it
     * is left; zero once thirty seconds passed.
     */
    static int cooldownSeconds(Instant lastAdmission, Instant now) {
        Duration left = Duration.between(now, lastAdmission.plus(MANUAL_COOLDOWN));
        return left.isPositive() ? (int) Math.max(1, (left.toMillis() + 999) / 1000) : 0;
    }

    /** Entry and reactivation admit only once five minutes passed since {@code from}. */
    static boolean recent(Instant from, Instant now) {
        return Duration.between(from, now).compareTo(AUTOMATIC_INTERVAL) < 0;
    }

    /** Whether a lease claimed at {@code claimedAt} may still be renewed at {@code now}. */
    static boolean withinRunLimit(Instant claimedAt, Instant now) {
        return Duration.between(claimedAt, now).compareTo(MAX_RUN) < 0;
    }

    /** How many windows of at most seven days cover {@code (start, tc]}. */
    static int windows(Instant start, Instant tc) {
        long span = Duration.between(start, tc).toMillis();
        long window = WINDOW.toMillis();
        return (int) ((span + window - 1) / window);
    }

    // ---------------------------------------------------------------- admission

    /** What the admission rules decided, before anything is written. */
    private sealed interface Judgment {
        record NotPermitted() implements Judgment {}
        record NotAvailable(String reason) implements Judgment {}
        record InProgress(SyncJob job) implements Judgment {}
        record LongGap() implements Judgment {}
        record Recent() implements Judgment {}
        record Cooldown(int retryAfterSeconds) implements Judgment {}
        record Admit(PlanSyncRepository.Basis basis) implements Judgment {}
    }

    /** The rules in their order, against the database clock. Reads only. */
    private Judgment judge(UUID user, SyncTrigger trigger) {
        if (!writable(user)) return new Judgment.NotPermitted();
        if (publicationService.syncRestricted()) return new Judgment.NotAvailable("SYNC_RESTRICTED");
        var plan = plans.activePlan(user).orElse(null);
        if (plan == null) return new Judgment.NotAvailable("NO_ACTIVE_PLAN");
        var pointer = publications.pointer(user, plan.planId()).orElse(null);
        CalculationView calculation = pointer == null ? null
                : publications.calculation(user, pointer.calculationId()).orElse(null);
        Lineage lineage = pointer == null ? null : publications.lineage(user, pointer.calculationId()).orElse(null);
        if (pointer == null || calculation == null || lineage == null
                || !PlanPublicationService.belongs(calculation, plan, pointer))
            return new Judgment.NotAvailable("NO_ACTIVE_PLAN");
        if (!sameSource(user, calculation, lineage)) return new Judgment.NotAvailable("SOURCE_CHANGED");
        Instant now = syncs.databaseNow();
        if (!now.isBefore(calculation.endAt())) return new Judgment.NotAvailable("PLAN_PERIOD_ENDED");
        var active = syncs.active(user, plan.planId());
        if (active.isPresent()) return new Judgment.InProgress(active.get());
        if (longGap(calculation.verifiedThrough(), now)) return new Judgment.LongGap();
        Instant last = syncs.latest(user, plan.planId()).map(SyncJob::createdAt).orElse(null);
        if (trigger == SyncTrigger.MANUAL) {
            int left = last == null ? 0 : cooldownSeconds(last, now);
            if (left > 0) return new Judgment.Cooldown(left);
        } else if (recent(last != null ? last : pointer.publishedAt(), now)) {
            return new Judgment.Recent();
        }
        return new Judgment.Admit(new PlanSyncRepository.Basis(plan.planId(), calculation.portfolioId(), plan.intentId(),
                lineage.membershipId(), lineage.connectionId(), lineage.providerAccountId(), pointer.calculationId(),
                pointer.version(), calculation.verifiedThrough(), lineage.lastExtendedAt()));
    }

    /** The official version's connection and membership are still the active ones. */
    private boolean sameSource(UUID user, CalculationView calculation, Lineage lineage) {
        var connection = sources.snapshotConnection(user).orElse(null);
        var membership = portfolios.activeSource(user).orElse(null);
        return connection != null && membership != null && connection.connectionId().equals(lineage.connectionId())
                && connection.providerAccountId().equals(lineage.providerAccountId())
                && membership.membershipId().equals(lineage.membershipId())
                && membership.portfolioId().equals(calculation.portfolioId())
                && membership.connectionId().equals(lineage.connectionId())
                && membership.providerAccountId().equals(lineage.providerAccountId());
    }

    private static ManualSync manualOf(Judgment judgment) {
        return switch (judgment) {
            case Judgment.Admit admit -> new ManualSync(true, null, null);
            case Judgment.NotAvailable unavailable -> new ManualSync(false, unavailable.reason(), null);
            case Judgment.InProgress progress -> new ManualSync(false, "IN_PROGRESS", null);
            case Judgment.LongGap gap -> new ManualSync(false, "LONG_GAP", null);
            case Judgment.Cooldown cooldown -> new ManualSync(false, "COOLDOWN", cooldown.retryAfterSeconds());
            // A button press is never "recently synced"; the account refusal is answered before this.
            case Judgment.Recent recent -> new ManualSync(true, null, null);
            case Judgment.NotPermitted denied -> new ManualSync(false, "NO_ACTIVE_PLAN", null);
        };
    }

    /**
     * Admits a sync. One admission, one operational line with the trigger, the outcome and the reason — no
     * user, plan, job id, amount or time.
     */
    SyncAdmission admit(@Nullable UUID userId, SyncTrigger trigger) {
        if (userId == null) {
            logAdmission(trigger, NOT_PERMITTED_OUTCOME, null);
            return new SyncAdmission.NotPermitted();
        }
        SyncAdmission admission;
        List<Ending> expired = new ArrayList<>();
        try {
            admission = Objects.requireNonNull(transactions.execute(status -> admitInTransaction(userId, trigger, expired)));
        } catch (DuplicateKeyException racing) {
            admission = Objects.requireNonNull(transactions.execute(status -> settled(userId, trigger)));
        }
        expired.forEach(PlanSyncService::logEnding);
        if (admission instanceof SyncAdmission.Answered answered)
            logAdmission(trigger, answered.answer().outcome(), answered.answer().reason());
        else logAdmission(trigger, NOT_PERMITTED_OUTCOME, null);
        return admission;
    }

    /**
     * The admission line's outcome for a refusal of the account itself. Log only: the refusal answers 403 and
     * carries no outcome.
     */
    static final String NOT_PERMITTED_OUTCOME = "NOT_PERMITTED";

    private SyncAdmission admitInTransaction(UUID user, SyncTrigger trigger, List<Ending> expired) {
        // A lost attempt of this plan ends first, so it never answers as "in progress" (a queued job no worker
        // claimed in time, or a running one whose lease ran out).
        if (writable(user)) {
            plans.activePlan(user).ifPresent(plan -> expired.addAll(syncs.expireLost(user, plan.planId())));
        }
        Judgment judgment = judge(user, trigger);
        if (judgment instanceof Judgment.NotPermitted) return new SyncAdmission.NotPermitted();
        if (judgment instanceof Judgment.Admit admit) {
            UUID jobId = UUID.randomUUID();
            if (syncs.insert(jobId, user, admit.basis(), trigger.name()) != 1) return settled(user, trigger);
            SyncJob job = syncs.byId(user, jobId).orElseThrow();
            return answered("ADMITTED", null, job, new ManualSync(false, "IN_PROGRESS", null));
        }
        return answer(user, trigger, judgment);
    }

    /** After losing the race on the one-in-progress index: the winner's reference, or the rules again. */
    private SyncAdmission settled(UUID user, SyncTrigger trigger) {
        Judgment judgment = judge(user, trigger);
        if (judgment instanceof Judgment.NotPermitted) return new SyncAdmission.NotPermitted();
        // Nothing is inserted here: an attempt that still admits would only race again.
        if (judgment instanceof Judgment.Admit)
            return answered("IN_PROGRESS", null, latestJob(user), new ManualSync(false, "IN_PROGRESS", null));
        return answer(user, trigger, judgment);
    }

    private SyncAdmission answer(UUID user, SyncTrigger trigger, Judgment judgment) {
        ManualSync manual = trigger == SyncTrigger.MANUAL ? manualOf(judgment) : manualOf(judge(user, SyncTrigger.MANUAL));
        return switch (judgment) {
            case Judgment.NotAvailable unavailable -> answered("NOT_AVAILABLE", unavailable.reason(), latestJob(user), manual);
            case Judgment.InProgress progress -> answered("IN_PROGRESS", null, progress.job(), manual);
            case Judgment.LongGap gap -> answered("LONG_GAP", null, latestJob(user), manual);
            case Judgment.Recent recent -> answered("RECENTLY_SYNCED", null, latestJob(user), manual);
            case Judgment.Cooldown cooldown -> answered("COOLDOWN", null, latestJob(user), manual);
            case Judgment.Admit admit -> throw new IllegalStateException("an admission is answered by its insert");
            case Judgment.NotPermitted denied -> throw new IllegalStateException("a refusal is not an answer");
        };
    }

    private static SyncAdmission answered(String outcome, @Nullable String reason, @Nullable SyncJob job,
            ManualSync manual) {
        return new SyncAdmission.Answered(new SyncAnswer(outcome, reason, job, manual));
    }

    /** The active plan's attempt in progress, else its latest one; nothing without an active plan. */
    private @Nullable SyncJob latestJob(UUID user) {
        var plan = plans.activePlan(user).orElse(null);
        if (plan == null) return null;
        return syncs.active(user, plan.planId()).or(() -> syncs.latest(user, plan.planId())).orElse(null);
    }

    /**
     * The sync state for the screen: the active plan's attempt in progress or its latest one, and whether the
     * button would start one now. Empty when the account's own access refuses it (its deletion is in progress).
     */
    Optional<SyncState> state(@Nullable UUID userId) {
        if (userId == null || !readable(userId)) return Optional.empty();
        return Optional.ofNullable(transactions.execute(status -> {
            Judgment judgment = judge(userId, SyncTrigger.MANUAL);
            if (judgment instanceof Judgment.NotPermitted) return null;
            return new SyncState(latestJob(userId), manualOf(judgment));
        }));
    }

    private static void logAdmission(SyncTrigger trigger, String outcome, @Nullable String reason) {
        LOG.info("plan sync admission: trigger={} outcome={} reason={}", trigger.name(), outcome,
                reason == null ? "none" : reason);
    }

    // ---------------------------------------------------------------- the worker

    /** Ends every job whose lease expired (no automatic retry) and writes their operational lines. */
    void expireLost() {
        List<Ending> endings = transactions.execute(status -> syncs.expireLost());
        if (endings != null) endings.forEach(PlanSyncService::logEnding);
    }

    /** Claims the oldest queued job. The claim commits on its own. */
    Optional<Lease> claim() {
        return Optional.ofNullable(transactions.execute(status -> syncs.claim().orElse(null)));
    }

    /**
     * Renews the lease and its running stage rows — never past {@link #MAX_RUN} after the claim: then the worker
     * lets the lease run out, so a stalled attempt cannot hold its place forever.
     */
    boolean heartbeat(Lease lease) {
        return Boolean.TRUE.equals(transactions.execute(status ->
                withinRunLimit(lease.claimedAt(), syncs.databaseNow()) && syncs.heartbeat(lease)));
    }

    /** The lease was lost or taken over: the late worker writes nothing, not even an ending. */
    private static final class LeaseLost extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LeaseLost() {
            super(null, null, false, false);
        }
    }

    /** The job already ended inside a step; the worker only stops. */
    private static final class Halted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Halted() {
            super(null, null, false, false);
        }
    }

    /** The publication was refused with a closed reason; everything it wrote rolls back. */
    private static final class Blocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        Blocked(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    /**
     * Runs one job. {@code alive} is the worker's local lease guard; losing it (or the lease row) stops the
     * job before the next provider call and nothing more is written.
     */
    void run(Lease lease, BooleanSupplier alive) {
        AtomicReference<String> reached = new AtomicReference<>("ADMITTED");
        try {
            execute(lease, alive, reached);
        } catch (LeaseLost | Halted stopped) {
            // Nothing more to write: the lease is gone, or the job already ended in the step that stopped it.
        } catch (RuntimeException failure) {
            // The stage and the class name only: no id, and a message or cause can carry SQL, provider text or values.
            LOG.warn("plan sync interrupted: stage={} failure={}", reached.get(), failure.getClass().getName());
            end(lease, "FAILED", "SYNC_INTERRUPTED");
        }
    }

    private void execute(Lease lease, BooleanSupplier alive, AtomicReference<String> reached) {
        if (!alive.getAsBoolean()) return;
        // The reference time: fixed once, before any account call.
        reached.set("REFERENCE_TIME");
        Job job = step(lease, j -> {
            syncs.stage(lease, "REFERENCE_TIME");
            return j;
        });
        if (!alive.getAsBoolean()) return;
        var time = sources.marketTime();
        if (time instanceof SourceConnectionApi.MarketTimeResult.Rejected rejected) {
            end(lease, "FAILED", rejected.reason() == SourceRejectionReason.RESPONSE_FORMAT
                    ? "REFERENCE_TIME_INVALID" : "REFERENCE_TIME_UNAVAILABLE");
            return;
        }
        Instant tc = ((SourceConnectionApi.MarketTimeResult.Observed) time).serverTime();
        Scope scope = step(lease, j -> scope(lease, j, tc));
        reached.set("COLLECTION");

        // The collection: every window and cursor of (start, tc], outside any transaction.
        if (!alive.getAsBoolean()) return;
        AtomicBoolean review = new AtomicBoolean(false);
        Instant start = scope.start();
        var result = sources.collectInitialInputs(job.userId(), job.connectionId(), start, tc, ignored -> {
            // The reference time is fixed already: the adapter never asks for it again.
        }, page -> {
            if (!alive.getAsBoolean()) throw new LeaseLost();
            // Bybit's startTime is inclusive: an event exactly at the start belongs to the carried part.
            var events = page.events().stream().filter(event -> event.transactionTime() == null
                    || !event.transactionTime().equals(start)).toList();
            boolean pageReview = step(lease, j -> {
                if (!collections.lockOwned(scope.collection())) throw new LeaseLost();
                return collections.storePage(scope.collection(), page.evidence(), events, page.reviewRequired());
            });
            if (pageReview) review.set(true);
        });
        if (!alive.getAsBoolean()) return;
        if (result instanceof SourceConnectionApi.CollectionResult.Rejected rejected) {
            String code = collectionFailure(rejected.reason());
            step(lease, j -> {
                // The stage row records the job's own closed code: it belongs to the job, not to Story 2.4's screen.
                if (collections.lockOwned(scope.collection()))
                    collections.finish(scope.collection(), code.equals("COLLECTION_REVIEW_REQUIRED") ? "NEEDS_REVIEW"
                            : "RETRY_REQUIRED", code);
                endInStep(lease, statusOf(code), code);
                return Boolean.TRUE;
            });
            return;
        }
        var observed = ((SourceConnectionApi.CollectionResult.Collected) result).value();
        CashflowClassificationRepository.Lease classification = step(lease, j -> collected(lease, j, scope, observed,
                review.get() || observed.reviewRequired()));
        reached.set("CLASSIFICATION");

        // The classification: one short transaction, as Story 2.5's.
        CashflowConversionRepository.Lease conversion = step(lease, j -> classified(lease, j, classification));
        reached.set("CONVERSION");

        // The conversion: public market facts outside any transaction, the result in one short transaction.
        if (!alive.getAsBoolean()) return;
        List<CashflowConversionRepository.Input> inputs = step(lease, j -> {
            if (!conversions.lockOwned(conversion)) throw new LeaseLost();
            return conversions.storedInputs(conversion);
        });
        List<PriceConversionPolicy.FlowInput> flows = inputs.stream().map(CashflowConversionRepository.Input::flow).toList();
        BooleanSupplier active = () -> alive.getAsBoolean()
                && Boolean.TRUE.equals(transactions.execute(status -> syncs.owns(lease) && conversions.owns(conversion)));
        PriceConversionPolicy.Reference reference = null;
        if (flows.stream().anyMatch(PriceConversionPolicy::needsMarket)) {
            if (!active.getAsBoolean()) return;
            var marketTime = sources.marketTime();
            if (marketTime instanceof SourceConnectionApi.MarketTimeResult.Rejected rejected) {
                reference = new PriceConversionPolicy.Reference.Failed(rejected.reason() == SourceRejectionReason.RESPONSE_FORMAT);
            } else {
                Instant at = ((SourceConnectionApi.MarketTimeResult.Observed) marketTime).serverTime();
                boolean fixed = step(lease, j -> conversions.lockOwned(conversion) && conversions.fixReference(conversion, at));
                if (!fixed) return;
                reference = new PriceConversionPolicy.Reference.Fixed(at);
            }
        }
        List<PriceConversionPolicy.FlowOutcome> outcomes = market.convert(flows, reference, active);
        if (outcomes == null) return;
        Instant referenceAt = reference instanceof PriceConversionPolicy.Reference.Fixed fixed ? fixed.at() : null;
        UUID conversionId = step(lease, j -> converted(lease, j, conversion, inputs, outcomes, referenceAt));

        // The inputs of the new version: the carried flows and the new ones, with the overlap re-verified.
        Assembled assembled = step(lease, j -> assemble(lease, j, conversionId));
        reached.set("CALCULATION");

        // The pure calculation, outside any transaction.
        if (!alive.getAsBoolean()) return;
        if (!conformance.verified()) {
            end(lease, "FAILED", "CALCULATION_POLICY_UNVERIFIED");
            return;
        }
        PlanCalculator.Result calculated = PlanCalculator.calculate(assembled.input());
        if (calculated instanceof PlanCalculator.Result.Rejected rejected) {
            end(lease, "FAILED", rejected.reason() == PlanCalculator.RejectionReason.CALCULATION_RANGE_EXCEEDED
                    ? "CALCULATION_RANGE_EXCEEDED" : "CALCULATION_INPUT_INVALID");
            return;
        }
        if (!alive.getAsBoolean()) return;
        reached.set("PUBLICATION");
        publish(lease, assembled, (PlanCalculator.Result.Calculated) calculated);
    }

    /** The collection's fixed range and its stage row. */
    private record Scope(Instant tc, String mode, Instant start, InitialCollectionRepository.Lease collection) {}

    /** Judges {@code tc} against the basis version and fixes the range; the collection stage row starts. */
    private @Nullable Scope scope(Lease lease, Job job, Instant tc) {
        CalculationView basis = publications.calculation(job.userId(), job.basisCalculationId()).orElseThrow();
        if (!tc.isAfter(job.basisVerifiedThrough())) return endInStep(lease, "FAILED", "REFERENCE_TIME_INVALID");
        if (!tc.isBefore(basis.endAt())) return endInStep(lease, "BLOCKED", "PLAN_PERIOD_ENDED");
        // The gap judged again against the fixed tc, before any account call (ERD §2.2).
        if (longGap(job.basisVerifiedThrough(), tc)) return endInStep(lease, "BLOCKED", "LONG_GAP_CHOICE_REQUIRED");
        String mode = mode(tc, job.basisExtendedAt());
        Instant start = rangeStart(basis.t0(), job.basisVerifiedThrough(), mode);
        syncs.fixScope(lease, tc, mode, start, windows(start, tc));
        UUID collectionJobId = UUID.randomUUID();
        var collection = collections.insertForSync(collectionJobId, job.jobId(), job.userId(), job.portfolioId(),
                job.intentId(), job.connectionId(), job.providerAccountId(), start, tc);
        syncs.recordCollectionJob(lease, collectionJobId);
        return new Scope(tc, mode, start, collection);
    }

    private static String collectionFailure(SourceRejectionReason reason) {
        return switch (reason) {
            case RESPONSE_FORMAT -> "COLLECTION_REVIEW_REQUIRED";
            case TIMEOUT, RATE_LIMITED, PROVIDER_UNAVAILABLE, WALLET_UNAVAILABLE, UNAVAILABLE -> "SOURCE_UNAVAILABLE";
            default -> "SOURCE_REJECTED";
        };
    }

    /** Completes the collection stage; a complete candidate starts the classification stage row. */
    private CashflowClassificationRepository.@Nullable Lease collected(Lease lease, Job job, Scope scope,
            SourceConnectionApi.CollectionObservation observed, boolean needsReview) {
        var collection = scope.collection();
        if (!collections.lockOwned(collection)) throw new LeaseLost();
        var connection = sources.snapshotConnection(job.userId()).orElse(null);
        if (connection == null || !connection.policyVersion().equals(observed.policyVersion())) {
            // The connection or its policy changed while the collection ran: nothing it read is this job's.
            collections.finish(collection, "CANCELLED", "SOURCE_CHANGED");
            return endInStep(lease, "BLOCKED", "SOURCE_CHANGED");
        }
        if (!observed.tc().equals(scope.tc())) {
            // The adapter answered for another reference time than the one fixed: the run did not hold together.
            collections.finish(collection, "CANCELLED", "SYNC_INTERRUPTED");
            return endInStep(lease, "FAILED", "SYNC_INTERRUPTED");
        }
        var stored = collections.complete(collection, observed, needsReview);
        collections.finish(collection, stored.status(), stored.failureCode());
        syncs.recordCollection(lease, observed.pageCount(), observed.policyVersion(), stored.candidateId());
        if (stored.candidateId() == null) return endInStep(lease, "REVIEW_REQUIRED", "COLLECTION_REVIEW_REQUIRED");
        UUID classificationJobId = UUID.randomUUID();
        var classification = classifications.insertForSync(classificationJobId, job.jobId(), job.userId(),
                job.portfolioId(), job.intentId(), job.connectionId(), job.providerAccountId(), stored.candidateId());
        syncs.recordClassificationJob(lease, classificationJobId);
        return classification;
    }

    /** Classifies the candidate (BYBIT-CASHFLOW-1.0); a COMPLETE classification starts the conversion stage row. */
    private CashflowConversionRepository.@Nullable Lease classified(Lease lease, Job job,
            CashflowClassificationRepository.Lease classification) {
        if (!classifications.lockOwned(classification)) throw new LeaseLost();
        var inputHash = classifications.inputHash(classification);
        if (inputHash.isEmpty()) {
            // The candidate is not whole: an event it holds was not observed again (it vanished) or changed.
            classifications.finish(classification, "NEEDS_REVIEW", "INPUT_COMPLETENESS_INVALID");
            return endInStep(lease, "REVIEW_REQUIRED", "COLLECTION_REVIEW_REQUIRED");
        }
        var inputs = classifications.inputs(classification);
        var decisions = inputs.stream().map(input -> CashflowDecisionTable.decide(input.accountType(), input.eventType(),
                input.currency(), input.cashFlow())).toList();
        // The job's own checks ran in this transaction already (step); nothing else can change here.
        classifications.publish(classification, inputHash.get(), inputs, decisions, () -> true);
        String[] outcome = syncs.classificationOutcome(classification.jobId()).orElseThrow();
        UUID classificationId = UUID.fromString(outcome[0]);
        syncs.recordClassification(lease, classificationId, classification.policyVersion());
        if (!"COMPLETE".equals(outcome[1])) return endInStep(lease, "REVIEW_REQUIRED", "CLASSIFICATION_REVIEW_REQUIRED");
        var pinned = conversions.completeClassification(job.userId(), classificationId).orElse(null);
        UUID conversionJobId = UUID.randomUUID();
        var conversion = pinned == null ? null : conversions.insertForSync(conversionJobId, job.jobId(), job.userId(),
                job.portfolioId(), job.intentId(), job.connectionId(), job.providerAccountId(), pinned).orElse(null);
        if (conversion == null) return endInStep(lease, "REVIEW_REQUIRED", "CLASSIFICATION_REVIEW_REQUIRED");
        syncs.recordConversionJob(lease, conversionJobId);
        return conversion;
    }

    /** Publishes the conversion result (BYBIT-PRICE-1.0); only a calculable one continues. */
    private @Nullable UUID converted(Lease lease, Job job, CashflowConversionRepository.Lease conversion,
            List<CashflowConversionRepository.Input> inputs, List<PriceConversionPolicy.FlowOutcome> outcomes,
            @Nullable Instant reference) {
        if (!conversions.lockOwned(conversion)) throw new LeaseLost();
        if (!conversions.pinnedInputsStillValid(conversion)) {
            conversions.finish(conversion, "CANCELLED", "NOT_PERMITTED", false);
            return endInStep(lease, "FAILED", "SYNC_INTERRUPTED");
        }
        UUID conversionId = conversions.publish(conversion, inputs, outcomes, reference, () -> true);
        var outcome = syncs.conversionOutcome(job.userId(), conversionId).orElseThrow();
        syncs.recordConversion(lease, conversionId, outcome.policyVersion());
        return switch (outcome.status()) {
            case "CONVERTED", "CONVERTED_ESTIMATED" -> conversionId;
            case "CONVERSION_REQUIRED" -> outcome.retryable() ? endInStep(lease, "FAILED", "CONVERSION_PENDING")
                    : endInStep(lease, "REVIEW_REQUIRED", "CONVERSION_REQUIRED");
            default -> endInStep(lease, "REVIEW_REQUIRED", "CONVERSION_REVIEW_REQUIRED");
        };
    }

    /** One flow of the new version and the calculable row it cites. */
    private record Cited(UUID conversionId, Flow flow) {}

    /** Everything the calculation and the publication need, read in one step. */
    private record Assembled(PlanPublicationPort.ActivePlan plan, CalculationView previous, Lineage previousLineage,
            Candidate candidate, UUID classificationId, UUID conversionId, String mode, Instant start, Instant tc,
            List<Cited> flows, PlanCalculator.Input input) {}

    /**
     * The new version's inputs: the previous version's flows at or before the start, citing their original
     * conversions, and the new conversion's calculable flows after it. Every flow or exclusion the previous
     * version held after the start must come back with the same verdict, asset, quantity, time, USD value and
     * character; a change is a review for Story 2.12, never a silent correction.
     */
    private @Nullable Assembled assemble(Lease lease, Job job, UUID conversionId) {
        UUID user = job.userId();
        var plan = plans.activePlan(user).orElseThrow();
        CalculationView previous = publications.calculation(user, job.basisCalculationId()).orElseThrow();
        Lineage previousLineage = publications.lineage(user, job.basisCalculationId()).orElseThrow();
        Instant start = Objects.requireNonNull(job.rangeStart());
        Instant tc = Objects.requireNonNull(job.tc());
        UUID classificationId = Objects.requireNonNull(job.classificationId());
        Candidate candidate = publications.candidate(user, Objects.requireNonNull(job.candidateId())).orElseThrow();

        Map<UUID, EventFacts> fresh = syncs.calculableFlows(conversionId);
        for (var entry : syncs.previousFlowsAfter(job.basisCalculationId(), start).entrySet()) {
            EventFacts now = fresh.get(entry.getKey());
            if (now == null || !sameFlow(entry.getValue(), now))
                return endInStep(lease, "REVIEW_REQUIRED", "APPLIED_FLOW_CHANGED");
        }
        Map<UUID, EventFacts> events = syncs.classificationEvents(classificationId);
        for (var entry : syncs.previousExclusionsAfter(job.basisCalculationId(), start).entrySet()) {
            EventFacts now = events.get(entry.getKey());
            if (now == null || !"EXCLUDED".equals(now.verdict()) || !sameEvent(entry.getValue(), now))
                return endInStep(lease, "REVIEW_REQUIRED", "APPLIED_FLOW_CHANGED");
        }

        List<Cited> flows = new ArrayList<>();
        for (var carried : syncs.carriedFlows(job.basisCalculationId(), start))
            flows.add(new Cited(carried.conversionId(), carried.flow()));
        for (Flow flow : publications.flows(user, conversionId)) flows.add(new Cited(conversionId, flow));
        syncs.recordFlows(lease, flows.size(), previous.calculationPolicy());
        String namespace = job.providerAccountId().toString();
        var input = new PlanCalculator.Input(PlanPublicationService.notation(plan.principal()), plan.startAt(),
                plan.endAt(), PlanCalculator.Period.valueOf(plan.period()),
                PlanPublicationService.notation(plan.targetRate()), PlanPublicationService.notation(candidate.equity()), tc,
                flows.stream().map(cited -> new PlanCalculator.Cashflow(cited.flow().occurredAt(), namespace,
                        cited.flow().sourceId(), PlanPublicationService.notation(cited.flow().usdValue()),
                        cited.flow().estimated())).toList(),
                previous.calculationPolicy(), previous.calendarPolicy());
        return new Assembled(plan, previous, previousLineage, candidate, classificationId, conversionId,
                Objects.requireNonNull(job.mode()), start, tc, List.copyOf(flows), input);
    }

    private static boolean sameFlow(EventFacts before, EventFacts now) {
        return sameEvent(before, now) && before.estimated() == now.estimated()
                && Objects.equals(before.evidenceType(), now.evidenceType())
                && sameDecimal(before.usdValue(), now.usdValue());
    }

    private static boolean sameEvent(EventFacts before, EventFacts now) {
        return before.verdict().equals(now.verdict()) && before.asset().equals(now.asset())
                && before.occurredAt().equals(now.occurredAt()) && before.semanticHash().equals(now.semanticHash())
                && sameDecimal(before.signedQuantity(), now.signedQuantity());
    }

    private static boolean sameDecimal(@Nullable String left, @Nullable String right) {
        if (left == null || right == null) return left == right;
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    // ---------------------------------------------------------------- the publication

    private void publish(Lease lease, Assembled assembled, PlanCalculator.Result.Calculated result) {
        Ending ending;
        try {
            ending = transactions.execute(status -> publishInTransaction(lease, assembled, result));
        } catch (Blocked blocked) {
            end(lease, "BLOCKED", blocked.code);
            return;
        } catch (DuplicateKeyException racing) {
            // Another writer took the pointer's next version or the calculation's place first.
            end(lease, "BLOCKED", "POINTER_CONFLICT");
            return;
        }
        if (ending != null) logEnding(ending);
    }

    /** The outer {@code REQUIRED} publication transaction. Throwing anywhere rolls every write back. */
    private Ending publishInTransaction(Lease lease, Assembled a, PlanCalculator.Result.Calculated result) {
        UUID user = lease.userId();
        // The first publication's lock order: the plan lock, then the account lifecycle, then the job.
        plans.lockPlanWrites(user);
        if (!accounts.lockWriteAccess(user)) throw new Blocked("NOT_PERMITTED");
        if (publicationService.syncRestricted()) throw new Blocked("NOT_PERMITTED");
        if (!syncs.lockOwned(lease)) throw new LeaseLost();
        Job job = syncs.job(lease);
        if (!syncs.databaseNow().isBefore(a.plan().endAt())) throw new Blocked("PLAN_PERIOD_ENDED");
        String refused = refusal(job);
        if (refused != null) throw new Blocked(refused);
        syncs.stage(lease, "PUBLICATION");

        UUID sourceSnapshotId = UUID.randomUUID();
        publications.insertCandidateSource(sourceSnapshotId, a.candidate());
        var next = portfolios.publishNext(new PortfolioMembershipApi.CandidateComponent(user, job.portfolioId(),
                job.membershipId(), job.connectionId(), job.providerAccountId(), sourceSnapshotId, a.candidate().equity(),
                a.candidate().measuredAt(), a.candidate().policyVersion()),
                a.previous().portfolioSnapshotId(), a.previous().portfolioSequence());
        var snapshot = switch (next) {
            case PortfolioMembershipApi.PublishedSnapshot published -> published;
            case PortfolioMembershipApi.NextPublication.MembershipChanged changed -> throw new Blocked("MEMBERSHIP_CHANGED");
            case PortfolioMembershipApi.NextPublication.PointerMoved moved -> throw new Blocked("POINTER_CONFLICT");
        };

        UUID calculationId = UUID.randomUUID();
        int estimated = (int) result.appliedCashflows().stream().filter(PlanCalculator.AppliedCashflow::estimated).count();
        Instant lastExtended = "EXTENDED".equals(a.mode()) ? a.tc() : a.previousLineage().lastExtendedAt();
        var conversion = publications.conversion(user, a.conversionId()).orElseThrow();
        publications.insertCalculation(new PlanPublicationRepository.CalculationRow(calculationId, job.jobId(),
                job.planId(), pinned(job, a, result), snapshot.snapshotId(), snapshot.publicationSequence(), sourceSnapshotId,
                publications.classificationPolicy(a.classificationId()), conversion.policyVersion(),
                a.candidate().policyVersion(), result, result.appliedCashflows().size(), estimated,
                new PlanPublicationRepository.Collection(a.mode(), a.start(), lastExtended, job.basisCalculationId())));
        Map<String, Cited> bySource = new HashMap<>();
        a.flows().forEach(cited -> bySource.put(cited.flow().sourceId(), cited));
        for (PlanCalculator.AppliedCashflow applied : result.appliedCashflows()) {
            Cited cited = bySource.get(applied.sourceId());
            if (cited == null) throw new IllegalStateException("applied cash flow without input");
            publications.insertFlow(calculationId, cited.conversionId(), job.providerAccountId(), cited.flow(), applied);
        }
        // The new classification's exclusions after the start, and the previous version's at or before it.
        publications.insertExclusions(calculationId, a.classificationId());
        syncs.carryExclusions(calculationId, job.basisCalculationId(), a.start());
        Instant published = syncs.advancePointer(job.planId(), user, job.basisPointerVersion(), job.basisCalculationId(),
                calculationId).orElseThrow(() -> new Blocked("POINTER_CONFLICT"));
        // The final check after every write: a restriction or deletion that raced in rolls it all back.
        if (publicationService.syncRestricted()
                || accounts.decide(user, user, AccountAccessKind.WRITE) != AccessDecision.ALLOWED)
            throw new Blocked("NOT_PERMITTED");
        Objects.requireNonNull(published);
        return syncs.finishPublished(lease, calculationId, job.basisPointerVersion() + 1)
                .orElseThrow(LeaseLost::new);
    }

    /**
     * The version's inputs in the shape the calculation version records them. The expected portfolio pointer is
     * the previous version's snapshot and sequence; the basis snapshots are the plan's own.
     */
    private static Pinned pinned(Job job, Assembled a, PlanCalculator.Result.Calculated result) {
        var plan = a.plan();
        return new Pinned(job.jobId(), job.userId(), job.portfolioId(), job.intentId(), job.membershipId(),
                job.connectionId(), job.providerAccountId(), a.previous().basisPortfolioSnapshotId(),
                a.previousLineage().basisSourceSnapshotId(), a.previous().portfolioSnapshotId(),
                a.previous().portfolioSequence(), a.candidate().candidateId(), a.classificationId(), a.conversionId(),
                PlanPublicationService.notation(plan.principal()), plan.startAt(), plan.endAt(), plan.period(),
                PlanPublicationService.notation(plan.targetRate()), a.tc(),
                PlanPublicationService.notation(a.candidate().equity()), a.candidate().measuredAt(), a.flows().size(),
                result.inputHash(), a.input().calculationPolicy(), a.input().calendarPolicy());
    }

    // ---------------------------------------------------------------- steps and endings

    /**
     * One short write transaction: the job row locked under this lease, the job re-checked, then {@code body}.
     * A refusal ends the job here as {@code BLOCKED}. {@code body} may end the job itself and return
     * {@code null}; either way the worker stops ({@link Halted}).
     */
    private <T> T step(Lease lease, Function<Job, @Nullable T> body) {
        Object value = transactions.execute(status -> {
            if (!syncs.lockOwned(lease)) throw new LeaseLost();
            Job job = syncs.job(lease);
            String refused = refusal(job);
            if (refused != null) {
                endInStep(lease, "BLOCKED", refused);
                return HALT;
            }
            T result = body.apply(job);
            return result == null ? HALT : result;
        });
        if (value == HALT || value == null) throw new Halted();
        @SuppressWarnings("unchecked")
        T typed = (T) value;
        return typed;
    }

    private static final Object HALT = new Object();

    /**
     * Whether the job may still write, and the closed reason when not: the account's deletion or a
     * restriction, the plan, the pointer the job was admitted on, the connection (its id, its provider
     * account, and — once the collection recorded it — its policy version: {@code SOURCE_CHANGED}) and the
     * membership.
     */
    private @Nullable String refusal(Job job) {
        UUID user = job.userId();
        if (!writable(user) || publicationService.syncRestricted()) return "NOT_PERMITTED";
        var plan = plans.activePlan(user).orElse(null);
        if (plan == null || !plan.planId().equals(job.planId())) return "PLAN_CHANGED";
        var pointer = publications.pointer(user, job.planId()).orElse(null);
        if (pointer == null || pointer.version() != job.basisPointerVersion()
                || !pointer.calculationId().equals(job.basisCalculationId()))
            return "POINTER_CONFLICT";
        var connection = sources.snapshotConnection(user).orElse(null);
        if (connection == null || !connection.connectionId().equals(job.connectionId())
                || !connection.providerAccountId().equals(job.providerAccountId())
                || (job.sourcePolicyVersion() != null && !connection.policyVersion().equals(job.sourcePolicyVersion())))
            return "SOURCE_CHANGED";
        var membership = portfolios.activeSource(user).orElse(null);
        if (membership == null || !membership.membershipId().equals(job.membershipId())
                || !membership.portfolioId().equals(job.portfolioId())
                || !membership.connectionId().equals(job.connectionId())
                || !membership.providerAccountId().equals(job.providerAccountId()))
            return "MEMBERSHIP_CHANGED";
        return null;
    }

    /** Ends the job inside the current step's transaction; the step then stops the worker. */
    private <T> @Nullable T endInStep(Lease lease, String status, String code) {
        syncs.finish(lease, status, code, retryable(code)).ifPresent(PlanSyncService::logEnding);
        return null;
    }

    /** Ends the job in a transaction of its own, if the lease still owns it. */
    private void end(Lease lease, String status, String code) {
        Ending ending = transactions.execute(t -> syncs.lockOwned(lease)
                ? syncs.finish(lease, status, code, retryable(code)).orElse(null) : null);
        if (ending != null) logEnding(ending);
    }

    /** The closed table's retry verdict. */
    static boolean retryable(String code) {
        return switch (code) {
            case "POINTER_CONFLICT", "REFERENCE_TIME_UNAVAILABLE", "REFERENCE_TIME_INVALID", "SOURCE_UNAVAILABLE",
                    "CONVERSION_PENDING", "SYNC_INTERRUPTED" -> true;
            default -> false;
        };
    }

    private static String statusOf(String code) {
        return switch (code) {
            case "COLLECTION_REVIEW_REQUIRED" -> "REVIEW_REQUIRED";
            default -> "FAILED";
        };
    }

    /**
     * One ending, one operational line: the trigger, the mode, the status, the stage reached, the closed failure
     * code, the latency from admission, the windows, pages and flows, and the policy versions. No user, plan or
     * job id, amount, time, event or provider text.
     */
    private static void logEnding(Ending e) {
        LOG.info("plan sync finished: trigger={} mode={} status={} stage={} failure={} latencyMs={} windows={} pages={} "
                + "flows={} policy={}", e.trigger(), e.mode() == null ? "none" : e.mode(), e.status(), e.stage(),
                e.failureCode() == null ? "none" : e.failureCode(), e.latencyMillis(), count(e.windows()),
                count(e.pages()), count(e.flows()), e.policies());
    }

    private static String count(@Nullable Integer value) {
        return value == null ? "none" : value.toString();
    }

    // ---------------------------------------------------------------- erasure and access

    void erase(UUID userId) {
        syncs.erase(userId);
    }

    /** The account's own access: refused while its deletion is in progress. */
    private boolean readable(UUID user) {
        return accounts.decide(user, user, AccountAccessKind.SENSITIVE) == AccessDecision.ALLOWED;
    }

    private boolean writable(UUID user) {
        return accounts.decide(user, user, AccountAccessKind.WRITE) == AccessDecision.ALLOWED;
    }
}
