package com.bokrilog.sync.internal;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.SyncJobApi.ActivePlanResult;
import com.bokrilog.sync.SyncJobApi.PlanResult;
import com.bokrilog.sync.SyncJobApi.PlanResultMetric;
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

    // ---------------------------------------------------------------- official result

    /**
     * The official result through the pointer. Only the account's own access gates this read: a
     * restriction on a write command stops new publications, it does not hide an existing result.
     */
    ActivePlanResult activeResult(@Nullable UUID userId) {
        if (userId == null || !readable(userId)) return new ActivePlanResult.NotPermitted();
        return transactions.execute(status -> {
            var plan = plans.activePlan(userId).orElse(null);
            if (plan == null) return new ActivePlanResult.None();
            var pointer = repository.pointer(userId, plan.planId()).orElse(null);
            if (pointer == null) return new ActivePlanResult.None();
            // Everything below is read by the pointer's calculation id; those rows are immutable.
            var calculation = repository.calculation(userId, pointer.calculationId()).orElse(null);
            if (calculation == null || !calculation.planId().equals(plan.planId())) return new ActivePlanResult.None();
            List<PlanResultMetric> metrics = repository.metrics(pointer.calculationId());
            return new ActivePlanResult.Found(new PlanResult(plan.planId(), "ACTIVE", notation(calculation.principal()),
                    calculation.t0(), calculation.endAt(), calculation.period(), notation(calculation.targetRate()),
                    pointer.version(), pointer.publishedAt(), pointer.calculationId(), calculation.portfolioSnapshotId(),
                    calculation.basisPortfolioSnapshotId(), calculation.basisAt(), calculation.measuredAt(),
                    calculation.verifiedThrough(), calculation.calculationPolicy(), calculation.calendarPolicy(),
                    calculation.displayPolicy(), calculation.pricePolicy(), calculation.implementationVersion(),
                    calculation.inputHash(), calculation.resultHash(), calculation.flowCount(),
                    calculation.estimatedFlowCount(), calculation.estimated(), calculation.inputReliability(),
                    repository.freshness(userId, calculation.measuredAt(), calculation.verifiedThrough()), metrics));
        });
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
