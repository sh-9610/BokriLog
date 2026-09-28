package com.bokrilog.sync.internal;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.SyncJobApi.BundleConsistency;
import com.bokrilog.sync.SyncJobApi.CalculationEvidence;
import com.bokrilog.sync.SyncJobApi.Dashboard;
import com.bokrilog.sync.SyncJobApi.DashboardMetric;
import com.bokrilog.sync.SyncJobApi.DashboardRead;
import com.bokrilog.sync.SyncJobApi.DashboardResult;
import com.bokrilog.sync.SyncJobApi.EvidenceOutcome;
import com.bokrilog.sync.SyncJobApi.EvidenceRead;
import com.bokrilog.sync.SyncJobApi.EvidenceReference;
import com.bokrilog.sync.SyncJobApi.PublicationAdmission;
import com.bokrilog.sync.SyncJobApi.PublicationJob;
import com.bokrilog.sync.SyncJobApi.PublicationState;
import com.bokrilog.sync.internal.PlanPublicationRepository.Candidate;
import com.bokrilog.sync.internal.PlanPublicationRepository.Conversion;
import com.bokrilog.sync.internal.PlanPublicationRepository.Flow;
import com.bokrilog.sync.internal.PlanPublicationRepository.Lease;
import com.bokrilog.sync.internal.PlanPublicationRepository.Pinned;
import com.bokrilog.sync.internal.PlanPublicationRepository.PublicationBlocked;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The first publication of an active plan and its official result (Story 2.7, AD-23).
 *
 * <p>An attempt runs in three steps. Its inputs are re-verified and read in a short transaction; the
 * pure calculation runs outside any transaction; and one outer {@code REQUIRED} transaction owned
 * here then takes the plan lock and the account lock, re-verifies everything against the pinned input
 * hash and the lease fence, and writes the tc source snapshot, the portfolio snapshot with its
 * pointer CAS, the immutable active plan with the intent consumption, the calculation version with its
 * metrics and cash-flow history, and the per-plan pointer CAS. The portfolio and plan commands join
 * that transaction; nothing here uses {@code REQUIRES_NEW}, asynchronous or after-commit publication.
 * Any failure rolls everything back and the attempt is finished in a separate transaction.
 */
@Service
class PlanPublicationService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PlanPublicationService.class);

    static final String PUBLICATION_COMMAND = "plan-publication";

    private final PlanPublicationRepository repository;
    private final CashflowClassificationRepository classifications;
    private final CashflowConversionRepository conversions;
    private final SourceConnectionApi sources;
    private final PortfolioMembershipApi portfolios;
    private final AccountAccessApi accounts;
    private final OperationalRestrictionApi restrictions;
    private final PlanPublicationPort plans;
    private final CalculationConformance conformance;
    private final TransactionTemplate transactions;
    /**
     * The dashboard's one consistent read (Story 2.8): read-only, {@code REPEATABLE READ}. The calculation
     * evidence (Story 2.9) reads its lineage in a transaction of the same kind.
     */
    private final TransactionTemplate dashboardReads;

    PlanPublicationService(PlanPublicationRepository repository, CashflowClassificationRepository classifications,
            CashflowConversionRepository conversions, SourceConnectionApi sources, PortfolioMembershipApi portfolios,
            AccountAccessApi accounts, OperationalRestrictionApi restrictions, PlanPublicationPort plans,
            CalculationConformance conformance, PlatformTransactionManager manager) {
        this.repository = repository;
        this.classifications = classifications;
        this.conversions = conversions;
        this.sources = sources;
        this.portfolios = portfolios;
        this.accounts = accounts;
        this.restrictions = restrictions;
        this.plans = plans;
        this.conformance = conformance;
        this.transactions = new TransactionTemplate(manager);
        this.dashboardReads = new TransactionTemplate(manager);
        this.dashboardReads.setReadOnly(true);
        this.dashboardReads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** The inputs of one publication, pinned or freshly re-read. The hash decides whether they changed. */
    record Inputs(PlanPublicationPort.Terms terms, Candidate candidate, UUID classificationId, Conversion conversion,
            List<Flow> flows, UUID expectedSnapshotId, long expectedSequence, String hash) {}

    /** Either the inputs are ready, or the closed reason they are not. */
    private record Readiness(@Nullable Inputs inputs, @Nullable String code) {
        static Readiness ready(Inputs inputs) {
            return new Readiness(inputs, null);
        }

        static Readiness blocked(String code) {
            return new Readiness(null, code);
        }
    }

    // ---------------------------------------------------------------- admission and state

    PublicationAdmission admit(@Nullable UUID userId, UUID intentId, @Nullable String key) {
        if (userId == null) return new PublicationAdmission.Refused("NOT_PERMITTED");
        // This command creates an immutable plan: without the caller's own key there is no safe replay.
        if (key == null || !validKey(key)) return new PublicationAdmission.Refused("IDEMPOTENCY_KEY_REQUIRED");
        try {
            return transactions.execute(status -> admitInTransaction(userId, intentId, key));
        } catch (DuplicateKeyException racing) {
            // Another tab won the key or the per-intent active index between our read and insert.
            return transactions.execute(status -> settled(userId, intentId, key));
        }
    }

    private PublicationAdmission admitInTransaction(UUID userId, UUID intentId, String key) {
        if (!writable(userId)) return new PublicationAdmission.Refused("NOT_PERMITTED");
        var keyed = repository.byKey(userId, key).orElse(null);
        if (keyed != null) return keyed(keyed, intentId);
        var active = repository.active(userId, intentId).orElse(null);
        if (active != null) return new PublicationAdmission.Accepted(active);
        if (!conformance.verified()) return new PublicationAdmission.Refused("CALCULATION_POLICY_UNVERIFIED");
        Readiness readiness = readiness(userId, intentId);
        Inputs inputs = readiness.inputs();
        if (inputs == null) return new PublicationAdmission.Refused(admissionCode(readiness.code()));
        var repeated = repository.deterministicFailure(userId, intentId, inputs.hash()).orElse(null);
        if (repeated != null) return new PublicationAdmission.Refused(repeated);
        UUID jobId = UUID.randomUUID();
        if (repository.insert(jobId, pin(jobId, userId, inputs), key) != 1) return settled(userId, intentId, key);
        return new PublicationAdmission.Accepted(repository.byKey(userId, key).orElseThrow().job());
    }

    /**
     * The answer after losing a race on the key or on the one-attempt-in-progress index: the keyed
     * attempt, the attempt in progress, or — when the winner already finished before this read — the
     * intent's latest attempt. The losing tab is shown what actually happened, never a false refusal.
     */
    private PublicationAdmission settled(UUID userId, UUID intentId, String key) {
        var keyed = repository.byKey(userId, key).orElse(null);
        if (keyed != null) return keyed(keyed, intentId);
        return repository.active(userId, intentId).or(() -> repository.latest(userId, intentId))
                .<PublicationAdmission>map(PublicationAdmission.Accepted::new)
                .orElse(new PublicationAdmission.Refused("INPUTS_NOT_READY"));
    }

    private static PublicationAdmission keyed(PlanPublicationRepository.KeyedJob keyed, UUID intentId) {
        return keyed.intentId().equals(intentId) ? new PublicationAdmission.Accepted(keyed.job())
                : new PublicationAdmission.Refused("IDEMPOTENCY_CONFLICT");
    }

    /** Admission speaks the HTTP vocabulary; attempt endings keep the stored closed codes. */
    private static String admissionCode(@Nullable String code) {
        if (code == null) return "INPUTS_NOT_READY";
        return switch (code) {
            case "NOT_PERMITTED" -> "NOT_PERMITTED";
            case "ACTIVE_PLAN_EXISTS" -> "ACTIVE_PLAN_EXISTS";
            case "PLAN_CONDITIONS_CHANGED", "MEMBERSHIP_CHANGED" -> "PLAN_CONDITIONS_REQUIRED";
            case "PLAN_PERIOD_ENDED" -> "PLAN_PERIOD_ENDED";
            default -> "INPUTS_NOT_READY";
        };
    }

    /**
     * The intent's latest attempt and whether a new one would be admitted now. Only the account's own
     * access decides whether the state is shown; everything else — a restriction, another user's or an
     * unknown intent, inputs not ready — is a readable "not startable" with the admission's reason, so
     * the screen never offers a start the admission would refuse, nor hides one it would accept.
     */
    PublicationState state(@Nullable UUID userId, UUID intentId) {
        if (userId == null || !readable(userId)) return new PublicationState(false, null, false, null);
        return transactions.execute(status -> {
            var latest = repository.latest(userId, intentId).orElse(null);
            if (latest != null && inProgress(latest)) return new PublicationState(true, latest, false, null);
            String reason = startRefusal(userId, intentId);
            return new PublicationState(true, latest, reason == null, reason);
        });
    }

    /**
     * The code the admission would refuse a new attempt with now, or {@code null} if it would accept. A
     * calculation that already failed deterministically on these same inputs is not offered again — the
     * pure calculator would fail the same way; new conditions or new inputs change the hash.
     */
    private @Nullable String startRefusal(UUID userId, UUID intentId) {
        if (!writable(userId)) return "NOT_PERMITTED";
        if (!conformance.verified()) return "CALCULATION_POLICY_UNVERIFIED";
        Readiness readiness = readiness(userId, intentId);
        Inputs inputs = readiness.inputs();
        if (inputs == null) return admissionCode(readiness.code());
        return repository.deterministicFailure(userId, intentId, inputs.hash()).orElse(null);
    }

    private static boolean inProgress(PublicationJob job) {
        return "QUEUED".equals(job.status()) || "RUNNING".equals(job.status());
    }

    // ---------------------------------------------------------------- the worker's attempt

    /**
     * Claims the oldest attempt and verifies it. The claim commits on its own; the verification runs in
     * a second short transaction. A verification that throws — deterministically, for this attempt's
     * data — must not roll the claim back to {@code QUEUED}: the attempt would be picked first again on
     * every tick and block every later publication. It is finished instead, fenced, as {@code BLOCKED}.
     */
    Optional<Lease> claim() {
        Lease lease = transactions.execute(status -> repository.claim().orElse(null));
        if (lease == null) return Optional.empty();
        try {
            String blocked = transactions.execute(status -> {
                if (!repository.lockOwned(lease)) return "LEASE_LOST";
                Readiness verified = verified(lease);
                return verified.inputs() == null ? Objects.requireNonNull(verified.code()) : null;
            });
            if (blocked == null) return Optional.of(lease);
            if (!"LEASE_LOST".equals(blocked)) finishBlocked(lease, blocked);
            return Optional.empty();
        } catch (RuntimeException failure) {
            // The class name only: a message or cause can carry SQL or values.
            LOG.warn("plan publication verification failed: job={} failure={}", lease.jobId(),
                    failure.getClass().getName());
            finishBlocked(lease, "INPUTS_CHANGED");
            return Optional.empty();
        }
    }

    boolean heartbeat(Lease lease) {
        return Boolean.TRUE.equals(transactions.execute(status -> repository.heartbeat(lease)));
    }

    /**
     * Runs one attempt. {@code alive} is the worker's local lease guard; losing it (or the lease row)
     * stops the attempt and nothing is published.
     */
    void run(Lease lease, BooleanSupplier alive) {
        try {
            if (!alive.getAsBoolean()) return;
            Loaded loaded = transactions.execute(status -> {
                if (!repository.lockOwned(lease)) return null;
                Readiness verified = verified(lease);
                if (verified.inputs() == null) {
                    repository.finish(lease, "BLOCKED", Objects.requireNonNull(verified.code()));
                    return null;
                }
                Pinned pinned = repository.pinned(lease);
                return new Loaded(pinned, repository.flows(lease.userId(), pinned.conversionId()));
            });
            if (loaded == null) return;
            if (!conformance.verified()) {
                finishFailed(lease, "CALCULATION_POLICY_UNVERIFIED");
                return;
            }
            // The pure calculation runs outside any transaction.
            PlanCalculator.Result result = PlanCalculator.calculate(calculationInput(loaded.pinned(), loaded.flows()));
            if (result instanceof PlanCalculator.Result.Rejected rejected) {
                finishFailed(lease, rejected.reason() == PlanCalculator.RejectionReason.CALCULATION_RANGE_EXCEEDED
                        ? "CALCULATION_RANGE_EXCEEDED" : "CALCULATION_INPUT_INVALID");
                return;
            }
            if (!alive.getAsBoolean()) return;
            publish(lease, loaded, (PlanCalculator.Result.Calculated) result);
        } catch (RuntimeException failure) {
            // The job id and the class name only: a message or cause can carry SQL or values.
            LOG.warn("plan publication interrupted: job={} failure={}", lease.jobId(), failure.getClass().getName());
            finishFailed(lease, "PUBLICATION_INTERRUPTED");
        }
    }

    private record Loaded(Pinned pinned, List<Flow> flows) {}

    private void publish(Lease lease, Loaded loaded, PlanCalculator.Result.Calculated result) {
        try {
            transactions.executeWithoutResult(status -> publishInTransaction(lease, loaded, result));
        } catch (PublicationBlocked blocked) {
            finishBlocked(lease, blocked.code());
        } catch (DuplicateKeyException racing) {
            // The conditional unique index is the authority for one active plan per portfolio.
            finishBlocked(lease, "ACTIVE_PLAN_EXISTS");
        } catch (LeaseLost lost) {
            // Another holder owns the attempt now. The late worker writes nothing, not even an ending.
        }
    }

    /** The outer {@code REQUIRED} publication transaction. Throwing anywhere rolls every write back. */
    private void publishInTransaction(Lease lease, Loaded loaded, PlanCalculator.Result.Calculated result) {
        UUID user = lease.userId();
        // Lock order matches plan confirmation (plan lock, then account lifecycle): no deadlock, and a
        // confirmation that started first is either committed and seen here, or waits for this commit.
        plans.lockPlanWrites(user);
        if (!accounts.lockWriteAccess(user)) throw new PublicationBlocked("NOT_PERMITTED");
        if (restricted()) throw new PublicationBlocked("NOT_PERMITTED");
        if (!repository.lockOwned(lease)) throw new LeaseLost();
        Pinned pinned = loaded.pinned();
        if (!repository.databaseNow().isBefore(pinned.endAt())) throw new PublicationBlocked("PLAN_PERIOD_ENDED");
        Readiness verified = verified(lease);
        Inputs inputs = verified.inputs();
        if (inputs == null) throw new PublicationBlocked(Objects.requireNonNull(verified.code()));

        UUID sourceSnapshotId = UUID.randomUUID();
        repository.insertCandidateSource(sourceSnapshotId, inputs.candidate());
        var current = portfolios.officialValue(user).orElse(null);
        if (current == null || !current.snapshotId().equals(pinned.expectedPortfolioSnapshotId())
                || current.publicationSequence() != pinned.expectedPortfolioSequence())
            throw new PublicationBlocked("POINTER_CONFLICT");
        var next = portfolios.publishNext(new PortfolioMembershipApi.CandidateComponent(user, pinned.portfolioId(),
                pinned.membershipId(), pinned.connectionId(), pinned.providerAccountId(), sourceSnapshotId,
                inputs.candidate().equity(), inputs.candidate().measuredAt(), inputs.candidate().policyVersion()),
                pinned.expectedPortfolioSnapshotId(), pinned.expectedPortfolioSequence());
        var snapshot = switch (next) {
            case PortfolioMembershipApi.PublishedSnapshot published -> published;
            case PortfolioMembershipApi.NextPublication.MembershipChanged changed ->
                    throw new PublicationBlocked("MEMBERSHIP_CHANGED");
            case PortfolioMembershipApi.NextPublication.PointerMoved moved ->
                    throw new PublicationBlocked("POINTER_CONFLICT");
        };
        UUID planId = plans.createActivePlan(user, inputs.terms()).orElseThrow(() -> new PublicationBlocked(
                plans.activePlan(user).isPresent() ? "ACTIVE_PLAN_EXISTS" : "PLAN_CONDITIONS_CHANGED"));

        UUID calculationId = UUID.randomUUID();
        int estimatedFlows = (int) result.appliedCashflows().stream().filter(PlanCalculator.AppliedCashflow::estimated)
                .count();
        repository.insertCalculation(new PlanPublicationRepository.CalculationRow(calculationId, lease.jobId(), planId,
                pinned, snapshot.snapshotId(), snapshot.publicationSequence(), sourceSnapshotId,
                repository.classificationPolicy(pinned.classificationId()), inputs.conversion().policyVersion(),
                inputs.candidate().policyVersion(), result, result.appliedCashflows().size(), estimatedFlows));
        Map<String, Flow> bySource = new HashMap<>();
        loaded.flows().forEach(flow -> bySource.put(flow.sourceId(), flow));
        for (PlanCalculator.AppliedCashflow applied : result.appliedCashflows()) {
            Flow flow = bySource.get(applied.sourceId());
            if (flow == null) throw new IllegalStateException("applied cash flow without input");
            repository.insertFlow(calculationId, pinned.conversionId(), pinned.providerAccountId(), flow, applied);
        }
        if (repository.pointFirst(planId, user, pinned.portfolioId(), calculationId) != 1)
            throw new PublicationBlocked("POINTER_CONFLICT");
        // The final check after every write: a restriction or deletion that raced in rolls it all back.
        if (restricted() || accounts.decide(user, user, AccountAccessKind.WRITE) != AccessDecision.ALLOWED)
            throw new PublicationBlocked("NOT_PERMITTED");
        if (repository.finishPublished(lease, planId, calculationId) != 1) throw new LeaseLost();
    }

    private void finishBlocked(Lease lease, String code) {
        transactions.executeWithoutResult(status -> {
            if (repository.lockOwned(lease)) repository.finish(lease, "BLOCKED", code);
        });
    }

    private void finishFailed(Lease lease, String code) {
        transactions.executeWithoutResult(status -> {
            if (repository.lockOwned(lease)) repository.finish(lease, "FAILED", code);
        });
    }

    /** The lease was taken over; the late worker publishes nothing and finishes nothing. */
    private static final class LeaseLost extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LeaseLost() {
            super(null, null, false, false);
        }
    }

    /**
     * The lease's inputs re-read now, or the closed reason it may not continue. Deletion and restriction
     * come first, then the connection, an existing active plan, the membership, the intent's terms and
     * the lineage; the re-read inputs must hash to what the attempt pinned at admission.
     */
    private Readiness verified(Lease lease) {
        UUID user = lease.userId();
        if (!writable(user)) return Readiness.blocked("NOT_PERMITTED");
        var connection = sources.snapshotConnection(user).orElse(null);
        if (connection == null || !connection.connectionId().equals(lease.connectionId())
                || !connection.providerAccountId().equals(lease.providerAccountId()))
            return Readiness.blocked("NOT_PERMITTED");
        Readiness readiness = readiness(user, lease.intentId());
        if (readiness.inputs() == null) return readiness;
        if (!readiness.inputs().hash().equals(lease.inputScopeHash())) return Readiness.blocked("INPUTS_CHANGED");
        return readiness;
    }

    // ---------------------------------------------------------------- eligibility and lineage

    /**
     * The intent's current inputs: its terms, and one lineage of the latest complete candidate, its
     * COMPLETE classification and that classification's current, calculable conversion — all of the
     * same user, portfolio, active membership, connection and provider account. Partial, review-required
     * or conversion-required results, a conversion still in progress, and another lineage never qualify.
     *
     * <p>The membership is judged before the terms: a membership that ended or was replaced makes the
     * intent unpublishable too, and reporting that as "conditions changed" would hide the cause. The
     * plan period is judged against the database clock right after the terms, before the lineage, so an
     * elapsed period says so instead of "inputs not ready".
     */
    private Readiness readiness(UUID user, UUID intentId) {
        if (plans.activePlan(user).isPresent()) return Readiness.blocked("ACTIVE_PLAN_EXISTS");
        var connection = sources.snapshotConnection(user).orElse(null);
        if (connection == null) return Readiness.blocked("NOT_PERMITTED");
        var membership = portfolios.activeSource(user).orElse(null);
        if (membership == null) return Readiness.blocked("MEMBERSHIP_CHANGED");
        var terms = plans.publishableTerms(user, intentId).orElse(null);
        if (terms == null) return Readiness.blocked("PLAN_CONDITIONS_CHANGED");
        if (!PlanCalculator.CALCULATION_POLICY.equals(terms.calculationPolicy())
                || !PlanCalculator.CALENDAR_POLICY.equals(terms.calendarPolicy()) || !positive(terms.principal()))
            return Readiness.blocked("PLAN_CONDITIONS_CHANGED");
        if (!repository.databaseNow().isBefore(terms.endAt())) return Readiness.blocked("PLAN_PERIOD_ENDED");
        if (!membership.membershipId().equals(terms.membershipId())
                || !membership.portfolioId().equals(terms.portfolioId())
                || !membership.connectionId().equals(terms.connectionId())
                || !membership.providerAccountId().equals(terms.providerAccountId()))
            return Readiness.blocked("MEMBERSHIP_CHANGED");
        if (!connection.connectionId().equals(terms.connectionId())
                || !connection.providerAccountId().equals(terms.providerAccountId()))
            return Readiness.blocked("NOT_PERMITTED");
        var candidateId = classifications.latestEligibleCandidate(user, intentId, terms.portfolioId(),
                terms.connectionId(), terms.providerAccountId()).orElse(null);
        if (candidateId == null) return Readiness.blocked("INPUTS_CHANGED");
        var candidate = repository.candidate(user, candidateId).orElse(null);
        if (candidate == null || !candidate.t0().equals(terms.startAt()) || !candidate.intentId().equals(intentId))
            return Readiness.blocked("INPUTS_CHANGED");
        // t0 < tc < T.
        if (!terms.startAt().isBefore(candidate.tc())) return Readiness.blocked("INPUTS_CHANGED");
        if (!candidate.tc().isBefore(terms.endAt())) return Readiness.blocked("PLAN_PERIOD_ENDED");
        var classification = conversions.eligibleClassification(user, intentId, terms.portfolioId(),
                terms.connectionId(), terms.providerAccountId()).orElse(null);
        if (classification == null || !classification.candidateId().equals(candidateId))
            return Readiness.blocked("INPUTS_CHANGED");
        if (repository.conversionInProgress(user, classification.classificationId()))
            return Readiness.blocked("INPUTS_CHANGED");
        var conversion = repository.currentConversion(user, classification.classificationId()).orElse(null);
        if (conversion == null || !conversion.calculable() || !conversion.userId().equals(user)
                || !conversion.intentId().equals(intentId) || !conversion.portfolioId().equals(terms.portfolioId())
                || !conversion.connectionId().equals(terms.connectionId())
                || !conversion.providerAccountId().equals(terms.providerAccountId())
                || !("CONVERTED".equals(conversion.status()) || "CONVERTED_ESTIMATED".equals(conversion.status())))
            return Readiness.blocked("INPUTS_CHANGED");
        List<Flow> flows = repository.flows(user, conversion.conversionId());
        if (flows.size() != conversion.flowCount() || flows.size() != classification.flowCount())
            return Readiness.blocked("INPUTS_CHANGED");
        var pointer = portfolios.officialValue(user).orElse(null);
        if (pointer == null) return Readiness.blocked("INPUTS_CHANGED");
        String hash = inputHash(terms, candidate, classification, conversion, flows, pointer.snapshotId(),
                pointer.publicationSequence());
        return Readiness.ready(new Inputs(terms, candidate, classification.classificationId(), conversion, flows,
                pointer.snapshotId(), pointer.publicationSequence(), hash));
    }

    /**
     * The attempt's pinned inputs. Decimal strings are pinned in their lossless canonical notation (no
     * leading or trailing zeros): the calculation version and every view read these, and a raw provider
     * notation such as {@code "1250.00"} would otherwise travel to clients that accept canonical values only.
     */
    private static Pinned pin(UUID jobId, UUID user, Inputs inputs) {
        var t = inputs.terms();
        var c = inputs.candidate();
        return new Pinned(jobId, user, t.portfolioId(), t.intentId(), t.membershipId(), t.connectionId(),
                t.providerAccountId(), t.basisSnapshotId(), t.basisSourceSnapshotId(), inputs.expectedSnapshotId(),
                inputs.expectedSequence(), c.candidateId(), inputs.classificationId(),
                inputs.conversion().conversionId(), notation(t.principal()), t.startAt(), t.endAt(), t.period(),
                notation(t.targetRate()), c.tc(), notation(c.equity()), c.measuredAt(), inputs.flows().size(),
                inputs.hash(), t.calculationPolicy(), t.calendarPolicy());
    }

    /** The calculator's provider-neutral input, in canonical notation. The namespace is the provider account. */
    static PlanCalculator.Input calculationInput(Pinned pinned, List<Flow> flows) {
        String namespace = pinned.providerAccountId().toString();
        return new PlanCalculator.Input(notation(pinned.principal()), pinned.t0(), pinned.endAt(),
                PlanCalculator.Period.valueOf(pinned.period()), notation(pinned.targetRate()),
                notation(pinned.currentValue()), pinned.tc(),
                flows.stream().map(flow -> new PlanCalculator.Cashflow(flow.occurredAt(), namespace, flow.sourceId(),
                        notation(flow.usdValue()), flow.estimated())).toList(),
                pinned.calculationPolicy(), pinned.calendarPolicy());
    }

    /** The lossless canonical notation, or the raw text when it is not a plain decimal (the calculator refuses it). */
    private static String notation(String raw) {
        String canonical = PlanCalculator.plainDecimal(raw);
        return canonical == null ? raw : canonical;
    }

    /**
     * The attempt's input scope hash: an explicit serialization of named fields, each prefixed with its
     * length, never a record's {@code toString()} (its format is not a contract) and never bare separators
     * (an identifier containing the separator would collide with another input). Decimal values are
     * hashed in their canonical notation, so the same value in another notation is the same input.
     */
    static String inputHash(PlanPublicationPort.Terms t, Candidate c, CashflowConversionRepository.Classification cls,
            Conversion conversion, List<Flow> flows, UUID pointerSnapshotId, long pointerSequence) {
        Fields fields = new Fields("publication-input-1")
                .add("intent", t.intentId()).add("portfolio", t.portfolioId()).add("basisSnapshot", t.basisSnapshotId())
                .add("membership", t.membershipId()).add("connection", t.connectionId())
                .add("providerAccount", t.providerAccountId()).add("basisSource", t.basisSourceSnapshotId())
                .add("principal", notation(t.principal())).add("start", t.startAt().toEpochMilli())
                .add("end", t.endAt().toEpochMilli()).add("period", t.period())
                .add("targetRate", notation(t.targetRate())).add("sourcePolicy", t.sourcePolicyVersion())
                .add("portfolioPolicy", t.portfolioPolicyVersion()).add("calendarPolicy", t.calendarPolicy())
                .add("calculationPolicy", t.calculationPolicy())
                .add("candidate", c.candidateId()).add("completeness", c.completenessHash())
                .add("t0", c.t0().toEpochMilli()).add("tc", c.tc().toEpochMilli()).add("equity", notation(c.equity()))
                .add("measured", c.measuredAt().toEpochMilli()).add("candidatePolicy", c.policyVersion())
                .add("classification", cls.classificationId()).add("classificationScope", cls.scopeHash())
                .add("classificationPolicy", cls.policyVersion()).add("conversion", conversion.conversionId())
                .add("conversionFingerprint", conversion.fingerprint())
                .add("conversionPolicy", conversion.policyVersion()).add("conversionStatus", conversion.status())
                .add("pointerSnapshot", pointerSnapshotId).add("pointerSequence", pointerSequence)
                .add("flows", flows.size());
        for (Flow flow : flows) {
            fields.add("flowEvent", flow.sourceEventId()).add("flowSource", flow.sourceId())
                    .add("flowSemantic", flow.semanticHash()).add("flowAt", flow.occurredAt().toEpochMilli())
                    .add("flowUsd", notation(flow.usdValue())).add("flowEstimated", flow.estimated())
                    .add("flowEvidence", flow.evidenceType());
        }
        return sha256(fields.toString());
    }

    /** {@code name=length:value;} per field. A length read first makes every character of a value inert. */
    private static final class Fields {
        private final StringBuilder payload = new StringBuilder();

        Fields(String kind) {
            add("kind", kind);
        }

        Fields add(String name, @Nullable Object value) {
            String text = String.valueOf(value);
            this.payload.append(name).append('=').append(text.length()).append(':').append(text).append(';');
            return this;
        }

        @Override
        public String toString() {
            return this.payload.toString();
        }
    }

    // ---------------------------------------------------------------- the dashboard (Story 2.8)

    /**
     * The dashboard read. The account's own access is checked, the bundle is read in one read-only
     * {@code REPEATABLE READ} transaction, and the access is checked again: a deletion that began or
     * finished while the bundle was read wins, and nothing of it is returned. Only the account's own
     * access gates this read — a restriction on a write command stops new publications, it does not hide
     * an existing result.
     *
     * <p>No log line here: the HTTP surface writes the one operational line per read with the status it
     * actually answers. This read reports what it learned about the bundle — consistent, inconsistent, or
     * not read at all — and lets a failure propagate.
     */
    DashboardRead dashboard(@Nullable UUID userId) {
        if (userId == null || !readable(userId))
            return new DashboardRead(new Dashboard.NotPermitted(), BundleConsistency.NOT_READ);
        Dashboard bundle = Objects.requireNonNull(dashboardReads.execute(status -> bundle(userId)));
        BundleConsistency consistency = bundle instanceof Dashboard.Unavailable ? BundleConsistency.INCONSISTENT
                : BundleConsistency.CONSISTENT;
        // The same decision again, after the read: the bundle is dropped if the deletion won the race.
        if (!readable(userId)) return new DashboardRead(new Dashboard.NotPermitted(), consistency);
        return new DashboardRead(bundle, consistency);
    }

    /**
     * The bundle, inside the one consistent read. With no active plan, a publication in progress is
     * "being prepared" and anything else is "no plan". With one, everything is read by the pointer's
     * calculation id, and the bundle must agree with itself — the version belongs to the plan, its
     * portfolio and its terms, the portfolio pointer cites the version's snapshot, and its metrics are a
     * whole six — or nothing of it is returned.
     */
    private Dashboard bundle(UUID user) {
        var plan = plans.activePlan(user).orElse(null);
        if (plan == null) {
            return repository.publicationInProgress(user) ? new Dashboard.Preparing(DashboardTrust.WAIT_FOR_RESULT)
                    : new Dashboard.NoPlan(DashboardTrust.CREATE_PLAN);
        }
        // Active plans and their pointers are published atomically: a plan without one is inconsistent.
        var pointer = repository.pointer(user, plan.planId()).orElse(null);
        if (pointer == null) return new Dashboard.Unavailable();
        var calculation = repository.calculation(user, pointer.calculationId()).orElse(null);
        if (calculation == null || !belongs(calculation, plan, pointer)) return new Dashboard.Unavailable();
        /*
         * The portfolio pointer must cite the version's own snapshot. This relies on AD-23: the portfolio
         * pointer and the plan's official pointer advance together, in one publication transaction, so a
         * difference means the two pointers came from different moments. A later story that advances one
         * without the other (Story 2.11's incremental sync, for instance) must revisit this check.
         */
        var portfolio = portfolios.officialValue(user).orElse(null);
        if (portfolio == null || !portfolio.snapshotId().equals(calculation.portfolioSnapshotId())
                || portfolio.publicationSequence() != calculation.portfolioSequence())
            return new Dashboard.Unavailable();
        List<DashboardMetric> metrics = repository.metrics(pointer.calculationId());
        if (!DashboardTrust.wellFormed(metrics, calculation.estimated())) return new Dashboard.Unavailable();
        // The axes fail closed: a reliability this story does not know is not shown as anything.
        var trust = DashboardTrust.judge(new DashboardTrust.Input(
                repository.freshness(user, calculation.measuredAt(), calculation.verifiedThrough()),
                calculation.inputReliability(), calculation.estimated(), calculation.estimatedFlowCount(),
                calculation.measuredAt(), calculation.verifiedThrough(), calculation.basisAt(), metrics))
                .orElse(null);
        if (trust == null) return new Dashboard.Unavailable();
        return new Dashboard.Ready(new DashboardResult(plan.planId(), "ACTIVE", notation(calculation.principal()),
                calculation.t0(), calculation.endAt(), calculation.period(), notation(calculation.targetRate()),
                pointer.version(), pointer.publishedAt(), pointer.calculationId(), calculation.portfolioSnapshotId(),
                calculation.basisPortfolioSnapshotId(), calculation.basisAt(), calculation.measuredAt(),
                calculation.verifiedThrough(), calculation.calculationPolicy(), calculation.calendarPolicy(),
                calculation.displayPolicy(), calculation.pricePolicy(), calculation.implementationVersion(),
                calculation.inputHash(), calculation.resultHash(), calculation.flowCount(),
                calculation.estimatedFlowCount(), trust, metrics), DashboardTrust.nextAction(trust));
    }

    // ---------------------------------------------------------------- calculation evidence (Story 2.9)

    /**
     * The calculation evidence read. Like the dashboard: the account's own access is checked, the lineage
     * is read in one read-only {@code REPEATABLE READ} transaction, and the access is checked again — a
     * deletion that began or finished while the lineage was read wins, and nothing of it is returned.
     *
     * <p>A {@code null} reference (missing or malformed) names nothing, so nothing is read: it is not found,
     * exactly like a reference that names another user's plan, an earlier version or no plan at all. The
     * read reports what it learned about the lineage — consistent, inconsistent, or not read (a refusal or
     * a reference that is not the current pointer) — and lets a failure propagate.
     */
    EvidenceRead evidence(@Nullable UUID userId, @Nullable EvidenceReference reference) {
        if (userId == null || !readable(userId))
            return new EvidenceRead(new EvidenceOutcome.NotPermitted(), BundleConsistency.NOT_READ);
        if (reference == null) return new EvidenceRead(new EvidenceOutcome.NotFound(), BundleConsistency.NOT_READ);
        EvidenceOutcome outcome = Objects.requireNonNull(dashboardReads.execute(status -> lineage(userId, reference)));
        BundleConsistency consistency = switch (outcome) {
            case EvidenceOutcome.Found found -> BundleConsistency.CONSISTENT;
            case EvidenceOutcome.Unavailable unavailable -> BundleConsistency.INCONSISTENT;
            case EvidenceOutcome.NotFound notFound -> BundleConsistency.NOT_READ;
            case EvidenceOutcome.NotPermitted denied -> BundleConsistency.NOT_READ;
        };
        // The same decision again, after the read: the lineage is dropped if the deletion won the race.
        if (!readable(userId)) return new EvidenceRead(new EvidenceOutcome.NotPermitted(), consistency);
        return new EvidenceRead(outcome, consistency);
    }

    /**
     * The lineage, inside the one consistent read. The reference must be the active plan's pointer to the
     * letter — its plan, its version and its calculation — or it is not found, and nothing else is looked
     * up by it. From there every row is read by the id the version recorded, and the whole chain must agree
     * with itself, or nothing of it is returned:
     *
     * <ul>
     *   <li>the version belongs to the plan, its portfolio and its terms, the portfolio pointer cites its
     *       snapshot, and its metrics are a whole six (the dashboard's own checks);
     *   <li>its creating attempt published exactly this plan and version, for the plan's intent, from the
     *       inputs it pinned at admission ({@link #calculatedFromPinned});
     *   <li>its tc source snapshot was built from its candidate under the candidate's policy, and its basis
     *       source snapshot is the first snapshot of the same connection, measured at {@code t0};
     *   <li>the candidate, the classification and the conversion are one lineage — the same candidate, the
     *       same classification, the policies the version recorded, the same portfolio, intent, connection
     *       and provider account — the collection covered exactly {@code (t0, tc]}, and the conversion is
     *       the calculable one the version recorded;
     *   <li>the applied cash-flow history has the version's counts and cites only its conversion.
     * </ul>
     */
    private EvidenceOutcome lineage(UUID user, EvidenceReference reference) {
        var plan = plans.activePlan(user).orElse(null);
        if (plan == null || !plan.planId().equals(reference.planId())) return new EvidenceOutcome.NotFound();
        var pointer = repository.pointer(user, plan.planId()).orElse(null);
        if (pointer == null || pointer.version() != reference.pointerVersion()
                || !pointer.calculationId().equals(reference.calculationId()))
            return new EvidenceOutcome.NotFound();
        // The reference is the current official pointer: from here on, a disagreement is a broken lineage.
        UUID calculationId = pointer.calculationId();
        var calculation = repository.calculation(user, calculationId).orElse(null);
        if (calculation == null || !belongs(calculation, plan, pointer)) return new EvidenceOutcome.Unavailable();
        // The same AD-23 reliance as the dashboard: both pointers advance in one publication transaction.
        var portfolio = portfolios.officialValue(user).orElse(null);
        if (portfolio == null || !portfolio.snapshotId().equals(calculation.portfolioSnapshotId())
                || portfolio.publicationSequence() != calculation.portfolioSequence())
            return new EvidenceOutcome.Unavailable();
        List<DashboardMetric> metrics = repository.metrics(calculationId);
        if (!DashboardTrust.wellFormed(metrics, calculation.estimated())) return new EvidenceOutcome.Unavailable();
        var lineage = repository.lineage(user, calculationId).orElse(null);
        if (lineage == null || !"BYBIT".equals(lineage.provider())) return new EvidenceOutcome.Unavailable();

        var attempt = repository.creatingAttempt(user, lineage.createdByJobId()).orElse(null);
        if (attempt == null || !"PUBLISHED".equals(attempt.status()) || !plan.planId().equals(attempt.planId())
                || !calculationId.equals(attempt.calculationId())
                || !calculatedFromPinned(attempt.pinned(), plan.intentId(), calculation, lineage, metrics))
            return new EvidenceOutcome.Unavailable();
        var candidate = repository.candidate(user, lineage.candidateId()).orElse(null);
        if (candidate == null || !candidate.intentId().equals(plan.intentId())
                || !candidate.portfolioId().equals(calculation.portfolioId())
                || !candidate.connectionId().equals(lineage.connectionId())
                || !candidate.providerAccountId().equals(lineage.providerAccountId())
                || !candidate.t0().equals(calculation.t0()) || !candidate.tc().equals(calculation.basisAt())
                || !candidate.measuredAt().equals(calculation.measuredAt())
                || !candidate.policyVersion().equals(lineage.sourcePolicy()))
            return new EvidenceOutcome.Unavailable();
        var source = repository.sourceOrigin(user, lineage.sourceSnapshotId()).orElse(null);
        if (source == null || !lineage.candidateId().equals(source.candidateId())
                || !source.connectionId().equals(lineage.connectionId())
                || !source.providerAccountId().equals(lineage.providerAccountId())
                || !source.measuredAt().equals(calculation.measuredAt())
                || !source.policyVersion().equals(candidate.policyVersion()))
            return new EvidenceOutcome.Unavailable();
        /*
         * The basis source snapshot is the first snapshot's (a first-snapshot job wrote it, not a candidate):
         * the same connection and provider account, measured at the plan start. It is also the one sync-owned
         * record of the connection's policy version — the first-snapshot publication stores it only after
         * checking it against the connection's own (SyncJobService#publish), so no other module is read here.
         */
        var basis = repository.sourceOrigin(user, lineage.basisSourceSnapshotId()).orElse(null);
        if (basis == null || basis.candidateId() != null || !basis.connectionId().equals(lineage.connectionId())
                || !basis.providerAccountId().equals(lineage.providerAccountId())
                || !basis.measuredAt().equals(calculation.t0()))
            return new EvidenceOutcome.Unavailable();
        // The collection covered exactly (t0, tc]: its pages start at the plan start and end at the basis.
        var range = repository.collectionRange(user, lineage.candidateId()).orElse(null);
        if (range == null || !range.start().equals(calculation.t0()) || !range.end().equals(calculation.basisAt()))
            return new EvidenceOutcome.Unavailable();
        var classification = repository.classificationBundle(user, lineage.classificationId()).orElse(null);
        if (classification == null || !"COMPLETE".equals(classification.status())
                || !classification.candidateId().equals(lineage.candidateId())
                || !classification.policyVersion().equals(lineage.classificationPolicy())
                || !classification.intentId().equals(plan.intentId())
                || !classification.portfolioId().equals(calculation.portfolioId())
                || !classification.connectionId().equals(lineage.connectionId())
                || !classification.providerAccountId().equals(lineage.providerAccountId()))
            return new EvidenceOutcome.Unavailable();
        var conversion = repository.conversion(user, lineage.conversionId()).orElse(null);
        if (conversion == null || !conversion.calculable()
                || !("CONVERTED".equals(conversion.status()) || "CONVERTED_ESTIMATED".equals(conversion.status()))
                || !conversion.classificationId().equals(lineage.classificationId())
                || !conversion.policyVersion().equals(calculation.pricePolicy())
                || !conversion.intentId().equals(plan.intentId())
                || !conversion.portfolioId().equals(calculation.portfolioId())
                || !conversion.connectionId().equals(lineage.connectionId())
                || !conversion.providerAccountId().equals(lineage.providerAccountId())
                || conversion.flowCount() != attempt.pinned().flowCount())
            return new EvidenceOutcome.Unavailable();
        var flows = repository.appliedFlowCounts(calculationId, lineage.conversionId());
        if (flows.count() != calculation.flowCount() || flows.estimated() != calculation.estimatedFlowCount()
                || flows.foreign() != 0)
            return new EvidenceOutcome.Unavailable();
        // An estimated flow has one kind of price evidence (V10's CHECK); anything else is not one lineage.
        String evidenceType = null;
        if (calculation.estimatedFlowCount() > 0) {
            if (flows.estimatedEvidence().size() != 1) return new EvidenceOutcome.Unavailable();
            evidenceType = flows.estimatedEvidence().getFirst();
        } else if (!flows.estimatedEvidence().isEmpty()) {
            return new EvidenceOutcome.Unavailable();
        }

        var evidence = new CalculationEvidence(plan.planId(), pointer.version(), calculationId,
                new CalculationEvidence.Snapshots(calculation.portfolioSnapshotId(), calculation.portfolioSequence(),
                        calculation.basisPortfolioSnapshotId(), lineage.basisSourceSnapshotId(),
                        lineage.sourceSnapshotId()),
                new CalculationEvidence.Times(calculation.t0(), calculation.basisAt(), calculation.measuredAt(),
                        calculation.verifiedThrough()),
                new CalculationEvidence.Connection(lineage.provider(), lineage.connectionId(), basis.policyVersion()),
                new CalculationEvidence.SourceRange(range.start(), range.end(), candidate.policyVersion(),
                        candidate.completenessHash()),
                new CalculationEvidence.Classification(lineage.classificationId(), classification.policyVersion(),
                        classification.inputScopeHash()),
                new CalculationEvidence.Conversion(lineage.conversionId(), conversion.policyVersion(),
                        conversion.fingerprint()),
                new CalculationEvidence.Cashflows(calculation.flowCount(), calculation.estimatedFlowCount()),
                new CalculationEvidence.Plan(plan.planId(), notation(calculation.principal()), calculation.t0(),
                        calculation.endAt(), calculation.period(), notation(calculation.targetRate())),
                new CalculationEvidence.Calculation(calculationId, calculation.calculationPolicy(),
                        calculation.calendarPolicy(), calculation.displayPolicy(), lineage.precisionPolicy(),
                        lineage.timeZone(), calculation.implementationVersion(), calculation.inputHash(),
                        calculation.resultHash(), lineage.createdAt()),
                impacts(metrics, calculation.estimatedFlowCount(), evidenceType),
                // The pointer keeps no history (V10): the one official version is the current one, official
                // since the pointer was published. Earlier versions arrive with Story 2.11's history.
                List.of(new CalculationEvidence.OfficialVersion(pointer.version(), calculationId, pointer.publishedAt(),
                        true)),
                repository.unpublished(user, plan.intentId()));
        return new EvidenceOutcome.Found(evidence);
    }

    /**
     * Whether the version was calculated from exactly the inputs its attempt pinned at admission: the
     * intent, portfolio, membership, connection and provider account; the basis snapshots; the candidate,
     * classification and conversion; the terms, {@code tc}, E(tc) and its measurement time; the policies.
     *
     * <p>The portfolio pointer the publication advanced from is the plan's basis snapshot, and the version's
     * snapshot is the next sequence. That holds for the first publication — the only kind before Story 2.11
     * (incremental sync) and 2.15 (a following plan), which must revisit it.
     *
     * <p>The pinned input scope hash is not recomputed here: it covers the intent's source and portfolio
     * policy versions, which only the plan module holds and no port exposes.
     */
    private static boolean calculatedFromPinned(Pinned pinned, UUID intentId,
            PlanPublicationRepository.CalculationView calculation, PlanPublicationRepository.Lineage lineage,
            List<DashboardMetric> metrics) {
        String currentValue = metrics.getFirst().value();
        return pinned.intentId().equals(intentId) && pinned.portfolioId().equals(calculation.portfolioId())
                && pinned.membershipId().equals(lineage.membershipId())
                && pinned.connectionId().equals(lineage.connectionId())
                && pinned.providerAccountId().equals(lineage.providerAccountId())
                && pinned.basisPortfolioSnapshotId().equals(calculation.basisPortfolioSnapshotId())
                && pinned.basisSourceSnapshotId().equals(lineage.basisSourceSnapshotId())
                && pinned.expectedPortfolioSnapshotId().equals(calculation.basisPortfolioSnapshotId())
                && pinned.expectedPortfolioSequence() + 1 == calculation.portfolioSequence()
                && pinned.candidateId().equals(lineage.candidateId())
                && pinned.classificationId().equals(lineage.classificationId())
                && pinned.conversionId().equals(lineage.conversionId())
                && sameDecimal(pinned.principal(), calculation.principal()) && pinned.t0().equals(calculation.t0())
                && pinned.endAt().equals(calculation.endAt()) && pinned.period().equals(calculation.period())
                && sameDecimal(pinned.targetRate(), calculation.targetRate()) && pinned.tc().equals(calculation.basisAt())
                && currentValue != null && sameDecimal(pinned.currentValue(), currentValue)
                && pinned.measuredAt().equals(calculation.measuredAt())
                && pinned.calculationPolicy().equals(calculation.calculationPolicy())
                && pinned.calendarPolicy().equals(calculation.calendarPolicy());
    }

    /**
     * Each metric's character and why. The estimated cause is derived, not stored: a derived metric of a
     * version with estimated flows is estimated because of those flows and their one price evidence kind.
     * The current value is Bybit's own measurement, so nothing propagates to it, and an undefined metric has
     * no value for a flow to have reached — its reason is what explains it. The metrics are already
     * {@link DashboardTrust#wellFormed}: a derived metric is estimated exactly when the version is.
     */
    static List<CalculationEvidence.MetricImpact> impacts(List<DashboardMetric> metrics, int estimatedFlowCount,
            @Nullable String evidenceType) {
        return metrics.stream().map(metric -> new CalculationEvidence.MetricImpact(metric.metric(), metric.nature(),
                metric.unavailableReason(),
                evidenceType != null && !"CURRENT_VALUE".equals(metric.metric()) && "ESTIMATED".equals(metric.nature())
                        && metric.unavailableReason() == null
                        ? new CalculationEvidence.EstimatedCause(evidenceType, estimatedFlowCount) : null))
                .toList();
    }

    /** The version was calculated for this plan, in its portfolio, on its terms. */
    private static boolean belongs(PlanPublicationRepository.CalculationView calculation,
            PlanPublicationPort.ActivePlan plan, PlanPublicationRepository.Pointer pointer) {
        return calculation.planId().equals(plan.planId()) && calculation.portfolioId().equals(pointer.portfolioId())
                && calculation.portfolioId().equals(plan.portfolioId()) && calculation.t0().equals(plan.startAt())
                && calculation.endAt().equals(plan.endAt()) && calculation.period().equals(plan.period())
                && sameDecimal(calculation.principal(), plan.principal())
                && sameDecimal(calculation.targetRate(), plan.targetRate());
    }

    /** The same number in any plain notation ({@code "1250"} and {@code "1250.00"}). */
    private static boolean sameDecimal(String left, String right) {
        try {
            return new java.math.BigDecimal(left).compareTo(new java.math.BigDecimal(right)) == 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    void erase(UUID userId) {
        repository.erase(userId);
    }

    // ---------------------------------------------------------------- helpers

    /** The account's own access: refused while its deletion is in progress. No command restriction. */
    private boolean readable(UUID user) {
        return accounts.decide(user, user, AccountAccessKind.SENSITIVE) == AccessDecision.ALLOWED;
    }

    private boolean writable(UUID user) {
        return accounts.decide(user, user, AccountAccessKind.WRITE) == AccessDecision.ALLOWED && !restricted();
    }

    /**
     * The commands whose restriction also stops a publication. sync keeps its own copy of the plan
     * condition command name, because sync must not depend on plan (plan depends on sync);
     * {@code SyncCommandNamesTest} pins the copy to the plan module's constant.
     */
    private boolean restricted() {
        return restrictions.isRestricted(PUBLICATION_COMMAND)
                || restrictions.isRestricted(SyncJobService.INITIAL_INPUTS_COMMAND)
                || restrictions.isRestricted(SyncJobService.PLAN_CONDITION_COMMAND)
                || restrictions.isRestricted(SyncJobService.SYNC_COMMAND)
                || restrictions.isRestricted(SyncJobService.CONNECTION_COMMAND);
    }

    /** {@code P0 > 0}. The plan's start value is a plain decimal string. */
    private static boolean positive(String decimal) {
        try {
            return new java.math.BigDecimal(decimal).signum() > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean validKey(String key) {
        return key.length() >= 16 && key.length() <= 128 && key.matches("[A-Za-z0-9._:-]+")
                && !key.contains("..") && !key.startsWith("-");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
