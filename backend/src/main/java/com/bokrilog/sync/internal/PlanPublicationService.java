package com.bokrilog.sync.internal;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.portfolio.PortfolioPointerInvariantViolation;
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
import com.bokrilog.sync.SyncJobApi.PlanEndAvailability;
import com.bokrilog.sync.SyncJobApi.PlanEndBasis;
import com.bokrilog.sync.SyncJobApi.PlanEndBasisRead;
import com.bokrilog.sync.SyncJobApi.PlanEndRecord;
import com.bokrilog.sync.SyncJobApi.PublicationAdmission;
import com.bokrilog.sync.SyncJobApi.PublicationJob;
import com.bokrilog.sync.SyncJobApi.PublicationState;
import com.bokrilog.sync.internal.PlanPublicationRepository.Candidate;
import com.bokrilog.sync.internal.PlanPublicationRepository.Conversion;
import com.bokrilog.sync.internal.PlanPublicationRepository.Flow;
import com.bokrilog.sync.internal.PlanPublicationRepository.Lease;
import com.bokrilog.sync.internal.PlanPublicationRepository.Pinned;
import com.bokrilog.sync.internal.PlanPublicationRepository.PublicationBlocked;
import com.bokrilog.sync.IdempotencyKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    /** The incremental sync (Story 2.11): the dashboard's sync state and the lineage of the versions it made. */
    private final PlanSyncService syncs;
    private final PlanSyncRepository syncAttempts;
    /** The precision verification and continuation (Story 2.12): the dashboard's verification part and change. */
    private final PlanVerificationService verifications;
    /** The shown plan's record against its pointer and version (Story 2.14): the dashboard's, evidence's and history's. */
    private final ShownPlanAgreement agreement;
    /** 강한 신호(Story 3.3): 첫 발행의 result hash 불일치·portfolio CAS 위반. */
    private final StrongSignals strongSignals;

    PlanPublicationService(PlanPublicationRepository repository, CashflowClassificationRepository classifications,
            CashflowConversionRepository conversions, SourceConnectionApi sources, PortfolioMembershipApi portfolios,
            AccountAccessApi accounts, OperationalRestrictionApi restrictions, PlanPublicationPort plans,
            CalculationConformance conformance, PlatformTransactionManager manager, PlanSyncService syncs,
            PlanSyncRepository syncAttempts, PlanVerificationService verifications, ShownPlanAgreement agreement,
            StrongSignals strongSignals) {
        this.agreement = agreement;
        this.strongSignals = strongSignals;
        this.syncs = syncs;
        this.syncAttempts = syncAttempts;
        this.verifications = verifications;
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
        if (!IdempotencyKey.isValid(key)) return new PublicationAdmission.Refused(IdempotencyKey.REQUIRED_CODE);
        try {
            return transactions.execute(status -> admitInTransaction(userId, intentId, key));
        } catch (DuplicateKeyException racing) {
            // Another tab won the key or the per-intent active index between our read and insert.
            return transactions.execute(status -> settled(userId, intentId, key));
        }
    }

    private PublicationAdmission admitInTransaction(UUID userId, UUID intentId, String key) {
        if (plans.latestPlan(userId).isPresent()) {
            plans.lockPlanWrites(userId);
            if (!accounts.lockWriteAccess(userId)) return new PublicationAdmission.Refused("NOT_PERMITTED");
        }
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
        } catch (StrongSignals.Unraised unraised) {
            // 강한 신호를 기록하지 못했다(Story 3.3): 작업을 끝내지 않는다 — lease 규칙(재claim·재검증)에 맡긴다. 상한
            // 세대면 실패로 맺는다. 이 표의 실패 code는 닫힌 목록이라 기존 code(중단)를 쓴다.
            if (StrongSignals.exhausted(lease.generation())) {
                finishFailed(lease, "PUBLICATION_INTERRUPTED");
                StrongSignals.logUnrecordedEnding("plan-publication");
            }
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
        } catch (PortfolioPointerInvariantViolation | PlanPublicationRepository.ResultHashMismatch strong) {
            // 강한 신호(Story 3.3): 발행은 이미 롤백됐다. 그 뒤 신호를 커밋하고, 작업은 기존대로 실패로 끝난다(run).
            strongSignals.raiseFor(strong, lease.userId(), lease.jobId());
            throw strong;
        }
    }

    /** The outer {@code REQUIRED} publication transaction. Throwing anywhere rolls every write back. */
    private void publishInTransaction(Lease lease, Loaded loaded, PlanCalculator.Result.Calculated result) {
        UUID user = lease.userId();
        // Lock order matches plan confirmation (plan lock, then account lifecycle): no deadlock, and a
        // confirmation that started first is either committed and seen here, or waits for this commit.
        plans.lockPlanWrites(user);
        if (!accounts.lockWriteAccess(user)) throw new PublicationBlocked("NOT_PERMITTED");
        Pinned pinned = loaded.pinned();
        if (restricted(pinned.portfolioId(), pinned.connectionId())) throw new PublicationBlocked("NOT_PERMITTED");
        if (!repository.lockOwned(lease)) throw new LeaseLost();
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
                inputs.candidate().policyVersion(), result, result.appliedCashflows().size(), estimatedFlows,
                // The initial collection verified all of (t0, tc]: the first extended verification is tc itself.
                pinned.tc(), null));
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
        if (restricted(pinned.portfolioId(), pinned.connectionId())
                || accounts.decide(user, user, AccountAccessKind.WRITE) != AccessDecision.ALLOWED)
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

    /**
     * The completion calculator's input (Story 2.14): the plan's terms, the first post-end observation as {@code E} with
     * its measurement time (the pinned current value and measurement — the completion's candidate carries them),
     * whether a confirmed post-end flow exists, and every calculable flow of {@code (t0, U]}; the calculator applies only
     * {@code t0 < ti <= T} and judges the comparability itself.
     */
    static PlanCalculator.CompletionInput completionInput(Pinned pinned, List<Flow> flows, boolean postEndCashflow) {
        String namespace = pinned.providerAccountId().toString();
        return new PlanCalculator.CompletionInput(notation(pinned.principal()), pinned.t0(), pinned.endAt(),
                PlanCalculator.Period.valueOf(pinned.period()), notation(pinned.targetRate()),
                notation(pinned.currentValue()), pinned.measuredAt(), postEndCashflow,
                flows.stream().map(flow -> new PlanCalculator.Cashflow(flow.occurredAt(), namespace, flow.sourceId(),
                        notation(flow.usdValue()), flow.estimated())).toList(),
                pinned.calculationPolicy(), pinned.calendarPolicy());
    }

    /** The lossless canonical notation, or the raw text when it is not a plain decimal (the calculator refuses it). */
    static String notation(String raw) {
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

    /**
     * {@code name=length:value;} per field. A length read first makes every character of a value inert. The
     * incremental sync's input hash (Story 2.11) uses the same serialization.
     */
    static final class Fields {
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
     * access gates this read.
     *
     * <p>Story 3.2: 계산 표시 제한도 이 읽기를 막지 않는다. 묶음은 그대로 읽어 온전한지 판정하고, 그 결과가 제한에
     * 걸렸는지({@code displayRestricted})를 같은 트랜잭션에서 함께 보고한다 — 결과 값을 빼는 것은 HTTP 표면이다. 공식
     * 기록은 아무것도 바뀌지 않는다.
     *
     * <p>No log line here: the HTTP surface writes the one operational line per read with the status it
     * actually answers. This read reports what it learned about the bundle — consistent, inconsistent, or
     * not read at all — and lets a failure propagate.
     */
    DashboardRead dashboard(@Nullable UUID userId) {
        if (userId == null || !readable(userId))
            return new DashboardRead(new Dashboard.NotPermitted(), BundleConsistency.NOT_READ);
        HeldBundle held = Objects.requireNonNull(dashboardReads.execute(status -> {
            var shown = new AtomicReference<PlanPublicationPort.@Nullable ActivePlan>();
            Dashboard read = bundle(userId, shown);
            return new HeldBundle(read, displayRestricted(read, shown.get()));
        }));
        Dashboard bundle = held.dashboard();
        BundleConsistency consistency = bundle instanceof Dashboard.Unavailable ? BundleConsistency.INCONSISTENT
                : BundleConsistency.CONSISTENT;
        // The same decision again, after the read: the bundle is dropped if the deletion won the race.
        if (!readable(userId)) return new DashboardRead(new Dashboard.NotPermitted(), consistency);
        return new DashboardRead(bundle, consistency, held.displayRestricted());
    }

    /** 묶음과, 계산 표시 제한이 그 결과를 막는지. 같은 트랜잭션에서 읽는다(Story 3.2). */
    private record HeldBundle(Dashboard dashboard, boolean displayRestricted) {}

    /**
     * 계산 표시 제한이 묶음의 결과를 막는가(Story 3.2) — 그 계획, 계획의 portfolio, 버전의 계산 정책, 또는 전역. 결과가
     * 있는 묶음만 묻는다. 계획과 정책은 방금 읽은 묶음의 값이라 다시 조회하지 않는다. 판독이 실패하면 예외다
     * (fail-closed: 결과를 보이지 않는다).
     */
    private boolean displayRestricted(Dashboard dashboard, PlanPublicationPort.@Nullable ActivePlan plan) {
        DashboardResult result = switch (dashboard) {
            case Dashboard.Ready ready -> ready.result();
            case Dashboard.Ended ended -> ended.result();
            case Dashboard.Completed completed -> completed.result();
            default -> null;
        };
        if (result == null) return false;
        if (plan == null || !plan.planId().equals(result.planId()))
            throw new IllegalStateException("결과의 계획을 묶음이 고르지 않았다.");
        return displayRestricted(plan.planId(), plan.portfolioId(), result.calculationPolicy());
    }

    /** 한 계획 결과의 계산 표시 제한. 대시보드·근거·조정 이력이 같은 것을 묻는다. */
    boolean displayRestricted(UUID planId, @Nullable UUID portfolioId, String calculationPolicy) {
        return restrictions.blocks(java.util.EnumSet.of(RestrictionFeature.CALCULATION_DISPLAY), RestrictionSubject
                .none().withPlan(planId).withPortfolio(portfolioId).withCalculationPolicy(calculationPolicy));
    }

    /**
     * 활성 계획의 종료를 제한이 막는가(Story 3.2) — plan 모듈의 판정과 같다: 동기화 또는 계산 표시가 그 계획, 계획의
     * portfolio, 계산이 기록한 연결, 계산 정책에 걸렸는가.
     */
    private boolean planEndRestricted(PlanPublicationPort.ActivePlan plan, UUID connectionId, String calculationPolicy) {
        return restrictions.blocks(java.util.EnumSet.of(RestrictionFeature.SYNC,
                RestrictionFeature.CALCULATION_DISPLAY), RestrictionSubject.none().withPlan(plan.planId())
                .withPortfolio(plan.portfolioId()).withConnection(connectionId)
                .withCalculationPolicy(calculationPolicy));
    }

    DashboardRead historicalDashboard(@Nullable UUID user, UUID planId) {
        if (user==null || !readable(user)) return new DashboardRead(new Dashboard.NotPermitted(),BundleConsistency.NOT_READ);
        HeldBundle held=Objects.requireNonNull(dashboardReads.execute(status -> {
            var shown=new AtomicReference<PlanPublicationPort.@Nullable ActivePlan>();
            Dashboard read=historicalBundle(user,planId,shown);
            return new HeldBundle(read,displayRestricted(read,shown.get()));
        }));
        Dashboard bundle=held.dashboard();
        var consistency=bundle instanceof Dashboard.Unavailable ? BundleConsistency.INCONSISTENT : BundleConsistency.CONSISTENT;
        if (!readable(user)) return new DashboardRead(new Dashboard.NotPermitted(),consistency);
        return new DashboardRead(bundle,consistency,held.displayRestricted());
    }

    /** {@code shown}에는 고른 계획을 남긴다 — 표시 제한 판정이 다시 조회하지 않게(Story 3.2). */
    private Dashboard historicalBundle(UUID user, UUID planId,
            AtomicReference<PlanPublicationPort.@Nullable ActivePlan> shown) {
        var selected=plans.historicalPlan(user,planId).orElse(null);
        if (selected==null) return new Dashboard.NotFound();
        shown.set(selected.plan());
        if (ENDED_EARLY.equals(selected.status())) return endedBundle(user,selected);
        if (selected.completion()==null) return new Dashboard.Unavailable();
        var value=completedBundle(user,selected.plan(),selected.completion());
        if (value instanceof Dashboard.Completed c) return new Dashboard.Completed(c.result(),
                DashboardTrust.PREPARE_NEXT_PLAN,new com.bokrilog.sync.SyncJobApi.PlanCompletionRecord(
                    c.completion().scheduledEndAt(),c.completion().comparison(),c.completion().reason(),
                    c.completion().observedValue(),c.completion().observedMeasuredAt(),c.completion().observationDelayMillis(),
                    c.completion().completionTarget(),c.completion().postEndCashflowCount(),false),null,null,c.change());
        return value;
    }

    /**
     * The bundle, inside the one consistent read. The plan shown is one choice (Story 2.13): the active plan; without
     * one, a publication in progress is "being prepared"; otherwise the most recent plan, when it ended early, is its
     * read-only record ({@link #endedBundle}); and anything else is "no plan". With an active plan, everything is read
     * by the pointer's calculation id, and the bundle must agree with itself — the version belongs to the plan, its
     * portfolio and its terms, the portfolio pointer cites the version's snapshot, and its metrics are a whole six —
     * or nothing of it is returned.
     */
    private Dashboard bundle(UUID user, AtomicReference<PlanPublicationPort.@Nullable ActivePlan> shown) {
        var plan = plans.activePlan(user).orElse(null);
        if (plan == null) {
            if (repository.publicationInProgress(user)) return new Dashboard.Preparing(DashboardTrust.WAIT_FOR_RESULT);
            var latest = plans.latestPlan(user).orElse(null);
            if (latest == null) return new Dashboard.NoPlan(DashboardTrust.CREATE_PLAN);
            // 보이는 계획(Story 3.2): 표시 제한 판정이 이 값을 쓴다.
            shown.set(latest.plan());
            if (COMPLETED.equals(latest.status()))
                return latest.completion() == null ? new Dashboard.Unavailable()
                        : completedBundle(user, latest.plan(), latest.completion());
            // Any other status without an active plan is not one this story knows: shown as nothing, never in part.
            return ENDED_EARLY.equals(latest.status()) ? endedBundle(user, latest) : new Dashboard.Unavailable();
        }
        shown.set(plan);
        // The database clock reached T (Story 2.14): the plan is not shown as an active plan that keeps accruing, even
        // before a sync request completes it — a GET writes nothing, so it only shows the completion not judged yet.
        if (!repository.databaseNow().isBefore(plan.endAt())) return completedBundle(user, plan, null);
        // Active plans and their pointers are published atomically: a plan without one is inconsistent.
        var pointer = repository.pointer(user, plan.planId()).orElse(null);
        if (pointer == null) return new Dashboard.Unavailable();
        var calculation = repository.calculation(user, pointer.calculationId()).orElse(null);
        if (calculation == null || !belongs(calculation, plan, pointer)) return new Dashboard.Unavailable();
        /*
         * The portfolio pointer must cite the version's own snapshot. This relies on AD-23: the portfolio
         * pointer and the plan's official pointer advance together, in one publication transaction, so a
         * difference means the two pointers came from different moments. Story 2.11's incremental sync keeps
         * that: it advances both in its own one publication transaction (PlanSyncPublisher), never one alone.
         */
        var portfolio = portfolios.officialValue(user).orElse(null);
        if (portfolio == null || !portfolio.snapshotId().equals(calculation.portfolioSnapshotId())
                || portfolio.publicationSequence() != calculation.portfolioSequence())
            return new Dashboard.Unavailable();
        List<DashboardMetric> metrics = repository.metrics(pointer.calculationId());
        if (!DashboardTrust.wellFormed(metrics, calculation.estimated())) return new Dashboard.Unavailable();
        var lineage = repository.lineage(user, pointer.calculationId()).orElse(null);
        if (lineage == null) return new Dashboard.Unavailable();
        // The sync state (Story 2.11) and the verification state and change (Story 2.12), in the same consistent
        // read and against the same database clock.
        // The availability facts and the persistent review are read once and shared by both parts and the axes.
        var base = new PlanSyncGuard.Base(plan, pointer, calculation, lineage);
        var facts = syncs.facts(user, base);
        var sync = syncs.state(facts);
        var latest = sync.latest();
        var review = facts.review();
        var verification = verifications.state(user, base, facts);
        var change = verifications.change(user, base);
        // The axes fail closed: a reliability this story does not know is not shown as anything.
        var trust = DashboardTrust.judge(new DashboardTrust.Input(
                repository.freshness(user, calculation.measuredAt(), calculation.verifiedThrough()),
                calculation.inputReliability(), calculation.estimated(), calculation.estimatedFlowCount(),
                calculation.measuredAt(), calculation.verifiedThrough(), calculation.basisAt(), metrics,
                new DashboardTrust.SyncFacts(sync.availability(), sync.reason(), latest == null ? null : latest.status(),
                        review == null ? null : review.code(), verification.availability())))
                .orElse(null);
        if (trust == null) return new Dashboard.Unavailable();
        // Whether the plan can be ended now (Story 2.13), by the end's own order, against the same clock and long gap.
        PlanEndAvailability planEnd = DashboardTrust.planEnd(planEndRestricted(plan, lineage.connectionId(), calculation.calculationPolicy()),
                facts.now(), calculation.endAt(), DashboardTrust.resultTooRecent(facts.now(), calculation.measuredAt(),
                        calculation.verifiedThrough()), facts.longGap());
        return new Dashboard.Ready(new DashboardResult(plan.planId(), ACTIVE, notation(calculation.principal()),
                calculation.t0(), calculation.endAt(), calculation.period(), notation(calculation.targetRate()),
                pointer.version(), pointer.publishedAt(), pointer.calculationId(), calculation.portfolioSnapshotId(),
                calculation.basisPortfolioSnapshotId(), calculation.basisAt(), calculation.measuredAt(),
                calculation.verifiedThrough(), calculation.calculationPolicy(), calculation.calendarPolicy(),
                calculation.displayPolicy(), calculation.pricePolicy(), calculation.implementationVersion(),
                calculation.inputHash(), calculation.resultHash(), calculation.flowCount(),
                calculation.estimatedFlowCount(), trust, metrics, calculation.kind()), DashboardTrust.nextAction(trust),
                sync,
                verification, change, planEnd);
    }

    /**
     * The plan statuses, end reasons and completion values this module reads. The plan module owns them; these are
     * sync's copies, because sync must not depend on plan — {@code SyncCommandNamesTest} pins each to the plan
     * module's constant, so a rename fails a test instead of making every record unavailable.
     */
    static final String ACTIVE = "ACTIVE";
    static final String ENDED_EARLY = "ENDED_EARLY";
    /** The natural completion (Story 2.14) and its comparison states and reasons, sync's copies. */
    static final String COMPLETED = "COMPLETED";
    static final String COMPARISON_PENDING = "PENDING";
    static final String COMPARISON_CONFIRMED = "CONFIRMED";
    static final String COMPARISON_UNAVAILABLE = "UNAVAILABLE";
    static final String COMPARISON_HELD = "HELD";
    static final String COMPLETION_DELAY_EXCEEDED = "OBSERVATION_DELAY_EXCEEDED";
    static final String COMPLETION_POST_END_CASHFLOW = "POST_END_CASHFLOW";
    static final String COMPLETION_HISTORY_OUT_OF_RANGE = "HISTORY_OUT_OF_RANGE";
    static final String COMPLETION_REASON_DATA_GAP = "DATA_GAP";
    static final String COMPLETION_INPUT_REVIEW = "INPUT_REVIEW_REQUIRED";
    static final String END_USER_REQUEST = "USER_REQUEST";
    static final String END_LONG_GAP_NEW_PLAN = "LONG_GAP_NEW_PLAN";
    static final String COMPLETION_NOT_APPLICABLE = "NOT_APPLICABLE";
    static final String COMPLETION_DATA_GAP = "UNAVAILABLE_DATA_GAP";

    /**
     * The read-only record of a plan that ended early (Story 2.13), inside the same consistent read: its last official
     * bundle — read through its pointer like a ready one's — and the end facts. The end record must agree with that
     * bundle ({@link #endAgrees}) — the same calculation and pointer version, the same portfolio snapshot and both times
     * copied unchanged — or nothing is returned. The portfolio's current pointer is not compared: a following plan
     * (Story 2.15) may move it. No sync, verification or change part; freshness is {@code ENDED}, and the input
     * reliability and the nature are judged from the version and its own persistent verification review (Story 2.12),
     * if one holds it — the review the ready dashboard showed for that same version.
     */
    private Dashboard endedBundle(UUID user, PlanPublicationPort.LatestPlan latest) {
        var plan = latest.plan();
        var end = latest.end();
        if (end == null) return new Dashboard.Unavailable();
        var pointer = repository.pointer(user, plan.planId()).orElse(null);
        if (pointer == null) return new Dashboard.Unavailable();
        var calculation = repository.calculation(user, pointer.calculationId()).orElse(null);
        if (calculation == null || !belongs(calculation, plan, pointer) || !endAgrees(end, plan, pointer, calculation))
            return new Dashboard.Unavailable();
        List<DashboardMetric> metrics = repository.metrics(pointer.calculationId());
        if (!DashboardTrust.wellFormed(metrics, calculation.estimated())) return new Dashboard.Unavailable();
        if (repository.lineage(user, pointer.calculationId()).isEmpty()) return new Dashboard.Unavailable();
        var review = syncAttempts.verificationReview(user, plan.planId(), pointer.calculationId()).orElse(null);
        var trust = DashboardTrust.judgeEnded(calculation.inputReliability(), calculation.estimated(),
                calculation.estimatedFlowCount(), calculation.measuredAt(), calculation.verifiedThrough(),
                calculation.basisAt(), metrics, review == null ? null : review.code()).orElse(null);
        if (trust == null) return new Dashboard.Unavailable();
        var record = new PlanEndRecord(end.reason(), end.endedAt(), plan.endAt(), end.lastMeasuredAt(),
                java.time.Duration.between(end.lastMeasuredAt(), end.endedAt()).toMillis(), end.completion(),
                end.uncollectedFrom(), end.uncollectedUntil());
        return new Dashboard.Ended(new DashboardResult(plan.planId(), ENDED_EARLY, notation(calculation.principal()),
                calculation.t0(), calculation.endAt(), calculation.period(), notation(calculation.targetRate()),
                pointer.version(), pointer.publishedAt(), pointer.calculationId(), calculation.portfolioSnapshotId(),
                calculation.basisPortfolioSnapshotId(), calculation.basisAt(), calculation.measuredAt(),
                calculation.verifiedThrough(), calculation.calculationPolicy(), calculation.calendarPolicy(),
                calculation.displayPolicy(), calculation.pricePolicy(), calculation.implementationVersion(),
                calculation.inputHash(), calculation.resultHash(), calculation.flowCount(),
                calculation.estimatedFlowCount(), trust, metrics, calculation.kind()), DashboardTrust.PREPARE_NEXT_PLAN,
                record);
    }

    /**
     * A completed plan's bundle (Story 2.14), inside the same consistent read: its current official version through its
     * pointer — the completion version once the comparison was judged on complete inputs, else the last progress
     * version — the completion record, the post-end value and the post-end sync part. {@code completion} is
     * {@code null} for an active plan whose {@code T} the database clock reached before any sync request completed it:
     * it is shown with a comparison not judged yet and nothing observed.
     *
     * <p>The record must agree with the version and with the observation the completion attempt published
     * ({@link #completionAgrees}, and the observation's own source snapshot) or nothing is returned. The portfolio's
     * current pointer is not compared with the version: the first observation and every post-end value move it.
     */
    private Dashboard completedBundle(UUID user, PlanPublicationPort.ActivePlan plan,
            PlanPublicationPort.@Nullable PlanCompletion completion) {
        var pointer = repository.pointer(user, plan.planId()).orElse(null);
        if (pointer == null) return new Dashboard.Unavailable();
        var calculation = repository.calculation(user, pointer.calculationId()).orElse(null);
        if (calculation == null || !belongs(calculation, plan, pointer)) return new Dashboard.Unavailable();
        if (completion == null ? calculation.completion() : !completionAgrees(completion, plan, pointer, calculation))
            return new Dashboard.Unavailable();
        List<DashboardMetric> metrics = repository.metrics(pointer.calculationId());
        if (!DashboardTrust.wellFormed(metrics, calculation.estimated())) return new Dashboard.Unavailable();
        var lineage = repository.lineage(user, pointer.calculationId()).orElse(null);
        if (lineage == null) return new Dashboard.Unavailable();
        String completionTarget = null;
        if (calculation.completion()) {
            // The completion version's current value is the observation, and its target is defined.
            String current = metrics.getFirst().value();
            completionTarget = metrics.get(DashboardTrust.METRICS.indexOf("ADJUSTED_TARGET")).value();
            if (current == null || completion == null || completion.observedValue() == null
                    || !sameDecimal(current, completion.observedValue()) || completionTarget == null)
                return new Dashboard.Unavailable();
        }
        if (completion != null && completion.observed() && !agreement.observationAgrees(user, completion))
            return new Dashboard.Unavailable();
        var review = syncAttempts.verificationReview(user, plan.planId(), pointer.calculationId()).orElse(null);
        boolean held = completion != null && COMPARISON_HELD.equals(completion.comparison());
        // A held comparison holds the inputs of the version shown: the input reliability says so, as a verification's
        // persistent review would (no next action on the axis — the screen-level action says whether to sync again).
        String heldBy = review != null ? review.code() : held ? COMPLETION_INPUT_REVIEW : null;
        var trust = DashboardTrust.judgeCompleted(calculation.inputReliability(), calculation.estimated(),
                calculation.estimatedFlowCount(), calculation.measuredAt(), calculation.verifiedThrough(),
                calculation.basisAt(), metrics, heldBy).orElse(null);
        if (trust == null) return new Dashboard.Unavailable();
        var base = new PlanSyncGuard.Base(plan, pointer, calculation, lineage);
        var sync = syncs.completedState(user, base, completion == null ? null : completion.transitionedAt());
        // What the version changed from the one it replaced (Story 2.12's marker): a completion version is an
        // incremental publication too.
        var change = verifications.change(user, base);
        com.bokrilog.sync.SyncJobApi.PostEndValue postEnd = null;
        if (completion != null && completion.observed()) {
            var portfolio = portfolios.officialValue(user).orElse(null);
            if (portfolio != null && !portfolio.snapshotId().equals(completion.observedPortfolioSnapshotId())
                    && portfolio.measuredAt().isAfter(Objects.requireNonNull(completion.observedMeasuredAt())))
                postEnd = new com.bokrilog.sync.SyncJobApi.PostEndValue(notation(portfolio.decimal()), portfolio.measuredAt());
        }
        boolean open = completion == null || PlanSyncService.completionOpen(completion, held
                && syncAttempts.completionPersistentlyReviewed(user, plan.planId(), pointer.calculationId()));
        var record = completion == null
                ? new com.bokrilog.sync.SyncJobApi.PlanCompletionRecord(plan.endAt(), COMPARISON_PENDING, null, null,
                        null, null, null, null, true)
                : new com.bokrilog.sync.SyncJobApi.PlanCompletionRecord(plan.endAt(), completion.comparison(),
                        completion.reason(), completion.observedValue(), completion.observedMeasuredAt(),
                        completion.observationDelayMillis(), completionTarget, completion.postEndCashflowCount(), open);
        return new Dashboard.Completed(new DashboardResult(plan.planId(), COMPLETED, notation(calculation.principal()),
                calculation.t0(), calculation.endAt(), calculation.period(), notation(calculation.targetRate()),
                pointer.version(), pointer.publishedAt(), pointer.calculationId(), calculation.portfolioSnapshotId(),
                calculation.basisPortfolioSnapshotId(), calculation.basisAt(), calculation.measuredAt(),
                calculation.verifiedThrough(), calculation.calculationPolicy(), calculation.calendarPolicy(),
                calculation.displayPolicy(), calculation.pricePolicy(), calculation.implementationVersion(),
                calculation.inputHash(), calculation.resultHash(), calculation.flowCount(),
                calculation.estimatedFlowCount(), trust, metrics, calculation.kind()),
                open ? DashboardTrust.SYNC_FOR_OBSERVATION : DashboardTrust.PREPARE_NEXT_PLAN, record, postEnd, sync,
                change);
    }

    /**
     * Whether a completion record agrees with the plan's pointer and the version it cites (Story 2.14): completed at the
     * plan's {@code T}; a fixed observation measured at or after {@code T} with its delay exactly the difference; and
     * a completion version exactly when the record names one — the pointer's own, of the completion kind, at
     * {@code u = T} on that observation (its measurement and its portfolio snapshot). A record without one has the plan
     * pointer still on a progress version.
     */
    static boolean completionAgrees(PlanPublicationPort.PlanCompletion completion, PlanPublicationPort.ActivePlan plan,
            PlanPublicationRepository.Pointer pointer, PlanPublicationRepository.CalculationView calculation) {
        if (!completion.completedAt().equals(plan.endAt())) return false;
        if (completion.observed()) {
            Instant measured = completion.observedMeasuredAt();
            Long delay = completion.observationDelayMillis();
            if (measured == null || delay == null || completion.observedValue() == null || measured.isBefore(plan.endAt())
                    || java.time.Duration.between(plan.endAt(), measured).toMillis() != delay)
                return false;
        }
        UUID completionCalculation = completion.completionCalculationId();
        if (completionCalculation == null) return !calculation.completion();
        return completion.observed() && pointer.calculationId().equals(completionCalculation)
                && calculation.completion() && calculation.basisAt().equals(plan.endAt())
                && calculation.measuredAt().equals(completion.observedMeasuredAt())
                && calculation.portfolioSnapshotId().equals(completion.observedPortfolioSnapshotId());
    }

    /**
     * Whether an end record agrees with the plan's pointer and the version it cites (Story 2.13): the same calculation
     * and pointer version — the pointer has not moved since the end — the version's portfolio snapshot, its measurement
     * and cash-flow verification copied unchanged, an end after both of them, after the pointer's publication and
     * before {@code T}, and a reason, completion and uncollected range that go together (a long-gap end's range is
     * {@code (verified, end]}, an early end has none and no completion comparison).
     */
    static boolean endAgrees(PlanPublicationPort.PlanEnd end, PlanPublicationPort.ActivePlan plan,
            PlanPublicationRepository.Pointer pointer, PlanPublicationRepository.CalculationView calculation) {
        boolean shape = switch (end.reason()) {
            case END_USER_REQUEST -> COMPLETION_NOT_APPLICABLE.equals(end.completion()) && end.uncollectedFrom() == null
                    && end.uncollectedUntil() == null;
            case END_LONG_GAP_NEW_PLAN -> COMPLETION_DATA_GAP.equals(end.completion())
                    && end.cashflowVerifiedThrough().equals(end.uncollectedFrom())
                    && end.endedAt().equals(end.uncollectedUntil())
                    && end.cashflowVerifiedThrough().isBefore(end.endedAt());
            default -> false;
        };
        return shape && end.officialCalculationId().equals(pointer.calculationId())
                && end.officialPointerVersion() == pointer.version()
                && end.officialPortfolioSnapshotId().equals(calculation.portfolioSnapshotId())
                && end.lastMeasuredAt().equals(calculation.measuredAt())
                && end.cashflowVerifiedThrough().equals(calculation.verifiedThrough())
                && !end.endedAt().isBefore(calculation.measuredAt())
                && !end.endedAt().isBefore(calculation.verifiedThrough())
                && !end.endedAt().isBefore(pointer.publishedAt()) && end.endedAt().isBefore(plan.endAt());
    }

    /**
     * The plan the evidence and the adjustment history judge their reference against (Story 2.13): the active plan, or
     * — without one — the most recent plan when it ended early. {@code end} is that plan's end record; an ended plan
     * whose record is missing comes back with {@code broken}, so the caller answers "unavailable" once the reference
     * named it, never "not found".
     */
    record Shown(PlanPublicationPort.ActivePlan plan, PlanPublicationPort.@Nullable PlanEnd end,
            PlanPublicationPort.@Nullable PlanCompletion completion, boolean broken) {

        /**
         * Whether the plan is terminal (ended early or completed): its record, not the portfolio's current pointer,
         * names the snapshot of its version — a post-end value or a following plan moves that pointer on.
         */
        boolean terminal() {
            return this.end != null || this.completion != null || this.broken;
        }
    }

    static @Nullable Shown shown(PlanPublicationPort plans, UUID user) {
        var active = plans.activePlan(user).orElse(null);
        if (active != null) return new Shown(active, null, null, false);
        var latest = plans.latestPlan(user).orElse(null);
        if (latest == null) return null;
        if (ENDED_EARLY.equals(latest.status())) return new Shown(latest.plan(), latest.end(), null, latest.end() == null);
        if (COMPLETED.equals(latest.status()))
            return new Shown(latest.plan(), null, latest.completion(), latest.completion() == null);
        return null;
    }

    static @Nullable Shown shown(PlanPublicationPort plans, UUID user, UUID planId) {
        var current=shown(plans,user);
        if (current!=null && current.plan().planId().equals(planId)) return current;
        var selected=plans.historicalPlan(user,planId).orElse(null);
        if (selected==null) return null;
        if (ENDED_EARLY.equals(selected.status())) return new Shown(selected.plan(),selected.end(),null,selected.end()==null);
        return new Shown(selected.plan(),null,selected.completion(),selected.completion()==null);
    }

    // ---------------------------------------------------------------- the plan end's basis (Story 2.13)

    /**
     * The official basis of the user's active plan {@code planId}, inside the caller's transaction: its pointer and the
     * calculation it cites, checked exactly as the ready dashboard checks its bundle ({@link #bundle}) — the version
     * belongs to the plan and its terms, the portfolio pointer cites its snapshot (AD-23), its six metrics are whole and
     * its input reliability is one this module knows, and its lineage exists — then the database clock, the long gap by
     * the sync admission's own function ({@link PlanSyncService#longGap}) and whether the result is too recent to end on
     * ({@link DashboardTrust#resultTooRecent}). Not the user's active plan: {@code NotActive}. A result that fails any
     * check: {@code Unavailable} — an end recorded on it would be a record that is never shown.
     */
    PlanEndBasisRead planEndBasis(@Nullable UUID userId, UUID planId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("the plan end basis joins the caller's transaction");
        if (userId == null) return new PlanEndBasisRead.NotActive();
        var plan = plans.activePlan(userId).orElse(null);
        if (plan == null || !plan.planId().equals(planId)) return new PlanEndBasisRead.NotActive();
        var pointer = repository.pointer(userId, planId).orElse(null);
        if (pointer == null) return new PlanEndBasisRead.Unavailable();
        var calculation = repository.calculation(userId, pointer.calculationId()).orElse(null);
        if (calculation == null || !belongs(calculation, plan, pointer)) return new PlanEndBasisRead.Unavailable();
        var portfolio = portfolios.officialValue(userId).orElse(null);
        if (portfolio == null || !portfolio.snapshotId().equals(calculation.portfolioSnapshotId())
                || portfolio.publicationSequence() != calculation.portfolioSequence())
            return new PlanEndBasisRead.Unavailable();
        if (!DashboardTrust.wellFormed(repository.metrics(pointer.calculationId()), calculation.estimated())
                || !DashboardTrust.RELIABILITIES.contains(calculation.inputReliability()))
            return new PlanEndBasisRead.Unavailable();
        var lineage = repository.lineage(userId, pointer.calculationId()).orElse(null);
        if (lineage == null) return new PlanEndBasisRead.Unavailable();
        Instant now = repository.databaseNow();
        boolean longGap = syncs.longGap(userId, new PlanSyncGuard.Base(plan, pointer, calculation, lineage), now);
        return new PlanEndBasis(planId, pointer.version(), pointer.calculationId(), calculation.portfolioSnapshotId(),
                calculation.measuredAt(), calculation.verifiedThrough(), calculation.endAt(), now, longGap,
                DashboardTrust.resultTooRecent(now, calculation.measuredAt(), calculation.verifiedThrough()));
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
            case EvidenceOutcome.Restricted restricted -> BundleConsistency.NOT_READ;
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
     *   <li>its creating attempt published exactly this plan and version, for the plan's intent: a first
     *       publication from the inputs it pinned at admission ({@link #calculatedFromPinned}), or an incremental
     *       sync (Story 2.11), precision verification or continuation (Story 2.12) that continued the version it read
     *       — the previous calculation of the same plan and terms, the next portfolio sequence, a candidate based on
     *       the previous one's, a window starting inside {@code [t0, previous verification]} (a verification's
     *       earliest target) and both watermarks as its mode leaves them ({@link #continuedFrom}) — with change rows
     *       that are exactly the flow difference from that version;
     *   <li>its tc source snapshot was built from its candidate under the candidate's policy, and its basis
     *       source snapshot is the first snapshot of the same connection, measured at {@code t0};
     *   <li>the candidate, the classification and the conversion are one lineage — the same candidate, the
     *       same classification, the policies the version recorded, the same portfolio, intent, connection
     *       and provider account — the collection's own windows covered exactly {@code [window start, tc]}
     *       (the window start is {@code t0} for a first publication), and the conversion is the calculable one
     *       the version recorded;
     *   <li>the applied cash-flow history has the version's counts and cites only its conversion;
     *   <li>the pointer's history is one contiguous line of versions ending in the current pointer.
     * </ul>
     */
    private EvidenceOutcome lineage(UUID user, EvidenceReference reference) {
        // The plan shown (Story 2.13): the active plan, or the most recent plan that ended early.
        var shown = shown(plans, user, reference.planId());
        if (shown == null || !shown.plan().planId().equals(reference.planId())) return new EvidenceOutcome.NotFound();
        var plan = shown.plan();
        var pointer = repository.pointer(user, plan.planId()).orElse(null);
        if (pointer == null || pointer.version() != reference.pointerVersion()
                || !pointer.calculationId().equals(reference.calculationId()))
            return new EvidenceOutcome.NotFound();
        // The reference is the current official pointer: from here on, a disagreement is a broken lineage.
        if (shown.broken()) return new EvidenceOutcome.Unavailable();
        UUID calculationId = pointer.calculationId();
        var calculation = repository.calculation(user, calculationId).orElse(null);
        if (calculation == null || !belongs(calculation, plan, pointer)) return new EvidenceOutcome.Unavailable();
        // 참조가 현재 포인터다: 계산 표시 제한이 지금 그 결과를 막는다(Story 3.2).
        if (displayRestricted(plan.planId(), plan.portfolioId(), calculation.calculationPolicy()))
            return new EvidenceOutcome.Restricted();
        var end = shown.end();
        // The same AD-23 reliance as the dashboard for an active plan: both pointers advance in one publication
        // transaction. A terminal plan's record names its snapshot instead; the portfolio's pointer may move on (a
        // post-end value, Story 2.14; a following plan, Story 2.15).
        if (!agreement.agrees(user, shown, pointer, calculation)) return new EvidenceOutcome.Unavailable();
        List<DashboardMetric> metrics = repository.metrics(calculationId);
        if (!DashboardTrust.wellFormed(metrics, calculation.estimated())) return new EvidenceOutcome.Unavailable();
        var lineage = repository.lineage(user, calculationId).orElse(null);
        if (lineage == null || !"BYBIT".equals(lineage.provider())) return new EvidenceOutcome.Unavailable();

        var candidate = repository.candidate(user, lineage.candidateId()).orElse(null);
        if (candidate == null || !candidate.intentId().equals(plan.intentId())
                || !candidate.portfolioId().equals(calculation.portfolioId())
                || !candidate.connectionId().equals(lineage.connectionId())
                || !candidate.providerAccountId().equals(lineage.providerAccountId())
                || !candidate.t0().equals(calculation.t0())
                // The collection's cutoff is the version's cash-flow verification: its basis for a progress version,
                // the completion's U (after T) for a completion version (Story 2.14).
                || !candidate.tc().equals(calculation.verifiedThrough())
                || !candidate.measuredAt().equals(calculation.measuredAt())
                || !candidate.policyVersion().equals(lineage.sourcePolicy()))
            return new EvidenceOutcome.Unavailable();
        // The creating attempt: a first publication, or an incremental sync (Story 2.11).
        var attempt = repository.creatingAttempt(user, lineage.createdByJobId()).orElse(null);
        Instant windowStart;
        int expectedConversionFlows;
        PlanSyncRepository.CreatingSync creatingSync = null;
        if (attempt != null) {
            if (!"PUBLISHED".equals(attempt.status()) || !plan.planId().equals(attempt.planId())
                    || !calculationId.equals(attempt.calculationId()) || lineage.previousCalculationId() != null
                    || candidate.baseCandidateId() != null || candidate.windowStart() != null
                    || !lineage.lastExtendedAt().equals(calculation.verifiedThrough())
                    || !calculatedFromPinned(attempt.pinned(), plan.intentId(), calculation, lineage, metrics))
                return new EvidenceOutcome.Unavailable();
            windowStart = calculation.t0();
            expectedConversionFlows = attempt.pinned().flowCount();
        } else {
            var sync = syncAttempts.creatingSync(user, lineage.createdByJobId()).orElse(null);
            var children = syncAttempts.childResults(user, lineage.createdByJobId()).orElse(null);
            var externalFlows = repository.externalFlowCount(user, lineage.classificationId()).orElse(null);
            if (sync == null || children == null || externalFlows == null
                    || !continuedFrom(user, sync, children, plan, pointer, calculationId, calculation, lineage,
                            candidate))
                return new EvidenceOutcome.Unavailable();
            // The change rows are exactly the flow difference from the version it continued (Story 2.12).
            if (lineage.previousCalculationId() == null
                    || !repository.changesConsistent(user, calculationId, lineage.previousCalculationId()))
                return new EvidenceOutcome.Unavailable();
            windowStart = Objects.requireNonNull(sync.windowStart());
            // The conversion converted every external flow its classification found.
            expectedConversionFlows = externalFlows;
            creatingSync = sync;
        }
        var source = repository.sourceOrigin(user, lineage.sourceSnapshotId()).orElse(null);
        // A progress version's source snapshot was built from its candidate; a completion version's is the first
        // post-end observation its completion attempt read and published as the version's portfolio snapshot.
        if (source == null || !(calculation.completion() ? completionSource(user, calculation, lineage, source)
                        : lineage.candidateId().equals(source.candidateId()))
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
        // The collection's own windows covered exactly [window start, tc]; together with the carried events of
        // an incremental candidate that is the whole (t0, tc].
        var range = repository.collectionRange(user, lineage.candidateId()).orElse(null);
        if (range == null || !range.start().equals(windowStart) || !range.end().equals(calculation.verifiedThrough()))
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
                || conversion.flowCount() != expectedConversionFlows)
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
        // A completed plan's current version stays current (its next action is a following plan, not a version).
        List<CalculationEvidence.OfficialVersion> versions = officialVersions(user, plan.planId(), pointer,
                calculation.portfolioId(), end == null ? null : end.endedAt());
        if (versions == null) return new EvidenceOutcome.Unavailable();
        var change = evidenceChange(user, calculationId, lineage, pointer, creatingSync, metrics, versions);
        if (change == null) return new EvidenceOutcome.Unavailable();

        var evidence = new CalculationEvidence(plan.planId(), pointer.version(), calculationId,
                new CalculationEvidence.Snapshots(calculation.portfolioSnapshotId(), calculation.portfolioSequence(),
                        calculation.basisPortfolioSnapshotId(), lineage.basisSourceSnapshotId(),
                        lineage.sourceSnapshotId()),
                new CalculationEvidence.Times(calculation.t0(), calculation.basisAt(), calculation.measuredAt(),
                        calculation.verifiedThrough()),
                new CalculationEvidence.Connection(lineage.provider(), lineage.connectionId(), basis.policyVersion()),
                new CalculationEvidence.SourceRange(calculation.t0(), range.end(), windowStart,
                        candidate.policyVersion(), candidate.completenessHash()),
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
                        calculation.resultHash(), lineage.createdAt(), calculation.kind()),
                impacts(metrics, calculation.estimatedFlowCount(), evidenceType), versions,
                repository.unpublished(user, plan.intentId()), change);
        return new EvidenceOutcome.Found(evidence);
    }

    /**
     * The version's change part (Story 2.12 AC-7): why its attempt ran and what it read and reused, the version it
     * replaced — as the pointer's history records it (the version just before this one, which must be that
     * predecessor) — with that version's basis and six metrics beside this one's (both immutable rows), when the
     * impact starts — the earliest late change ({@link VersionChanges}), the same instant the dashboard's marker shows
     * — and every changed event with its values, evidence and times. A first publication has none of it.
     *
     * @return the part, or {@code null} when the lineage does not hold: the predecessor or its six metrics are missing,
     *     or the history's previous version is not it (the caller answers that the evidence is unavailable)
     */
    private CalculationEvidence.@Nullable EvidenceChange evidenceChange(UUID user, UUID calculationId,
            PlanPublicationRepository.Lineage lineage, PlanPublicationRepository.Pointer pointer,
            PlanSyncRepository.@Nullable CreatingSync sync, List<DashboardMetric> metrics,
            List<CalculationEvidence.OfficialVersion> versions) {
        UUID previousId = lineage.previousCalculationId();
        if (sync == null || previousId == null)
            return new CalculationEvidence.EvidenceChange(VersionChanges.NONE, null, null, 0, 0, 0, List.of(),
                    List.of(), null, null, List.of(), List.of());
        var previous = repository.calculation(user, previousId).orElse(null);
        List<DashboardMetric> before = repository.metrics(previousId);
        if (previous == null || before.size() != DashboardTrust.METRICS.size() || versions.size() < 2) return null;
        var previousVersion = versions.get(versions.size() - 2);
        if (previousVersion.pointerVersion() + 1 != pointer.version()
                || !previousVersion.calculationId().equals(previousId))
            return null;
        List<CalculationEvidence.MetricDelta> deltas = new java.util.ArrayList<>();
        for (int index = 0; index < metrics.size(); index++) {
            DashboardMetric metric = metrics.get(index);
            DashboardMetric old = before.get(index);
            if (!old.metric().equals(metric.metric())) return null;
            deltas.add(new CalculationEvidence.MetricDelta(metric.metric(), old.value(), old.unavailableReason(),
                    metric.value(), metric.unavailableReason()));
        }
        var changes = repository.changes(user, calculationId);
        var late = VersionChanges.late(changes);
        var counts = VersionChanges.counts(changes);
        boolean verifying = !"SYNC".equals(sync.purpose());
        List<VerificationPlanner.Range> ranges = verifying ? syncAttempts.ranges(user, lineage.createdByJobId())
                : List.of();
        List<CalculationEvidence.ChangedEvent> events = changes.stream().map(change ->
                new CalculationEvidence.ChangedEvent(change.sourceId(), change.occurredAt(), change.kind(), change.late(),
                        change.transactionType(), change.previousUsd() == null ? null : notation(change.previousUsd()),
                        change.previousEstimated(), change.newUsd() == null ? null : notation(change.newUsd()),
                        change.newEstimated(), change.evidenceType(), change.discoveredAt(), pointer.publishedAt()))
                .toList();
        return new CalculationEvidence.EvidenceChange(VersionChanges.kind(late.size(), sync.purpose()), sync.purpose(),
                sync.reason(), counts.lateAdded(), counts.valueChanged(), counts.removed(),
                PlanVerificationService.targets(ranges), PlanVerificationService.reused(ranges),
                new CalculationEvidence.PreviousVersion(previousVersion.pointerVersion(), previousId,
                        previous.basisAt()),
                late.stream().map(PlanPublicationRepository.ChangeRow::occurredAt).min(java.util.Comparator.naturalOrder())
                        .orElse(null),
                List.copyOf(deltas), events);
    }

    /**
     * The plan's official versions from the pointer's append-only history (Story 2.11), oldest first: each
     * version is official from its publication until the next one's, and the current one has no end — unless the plan
     * ended early (Story 2.13): its current version was applied until the end. {@code null} when the history is not
     * one line ending in the current pointer — a gap, a version of another portfolio, a publication earlier than the
     * one before, or a last row that is not the pointer itself. Two versions may share a millisecond (the times are
     * stored to the millisecond), so an equal time is still one line.
     *
     * @param endedAt the end time of an ended plan, {@code null} for an active one
     */
    private @Nullable List<CalculationEvidence.OfficialVersion> officialVersions(UUID user, UUID planId,
            PlanPublicationRepository.Pointer pointer, UUID portfolioId, @Nullable Instant endedAt) {
        List<PlanPublicationRepository.HistoryRow> rows = repository.history(user, planId);
        if (rows.isEmpty()) return null;
        List<CalculationEvidence.OfficialVersion> versions = new java.util.ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var next = index + 1 < rows.size() ? rows.get(index + 1) : null;
            if (row.pointerVersion() != index + 1 || !row.portfolioId().equals(portfolioId)
                    || (next != null && next.publishedAt().isBefore(row.publishedAt())))
                return null;
            versions.add(new CalculationEvidence.OfficialVersion(row.pointerVersion(), row.calculationId(),
                    row.publishedAt(), next == null ? endedAt : next.publishedAt(), next == null));
        }
        var last = rows.getLast();
        if (last.pointerVersion() != pointer.version() || !last.calculationId().equals(pointer.calculationId())
                || !last.publishedAt().equals(pointer.publishedAt()))
            return null;
        return List.copyOf(versions);
    }

    /**
     * Whether an incremental version (Story 2.11) continued the version its sync read at admission: the sync
     * published exactly this plan, version and calculation from the plan's own portfolio, intent, membership,
     * connection and provider account; the version's predecessor is the sync's base — a calculation of the same
     * plan and terms, one portfolio sequence before, whose verification and extended verification the sync read,
     * with the same basis — and its candidate is based on that one's; the window starts inside
     * {@code [t0, previous verification]}; {@code tc} moved past the previous verification (a continuation's is its
     * {@code tcFinal}); and the extended watermark is the previous one after a normal sync and {@code tc} after an
     * extended sync, a verification or a continuation. The version's candidate,
     * classification and conversion are exactly what the sync's own stages produced, and the candidate's window
     * and {@code tc} are the scope the sync fixed.
     */
    private boolean continuedFrom(UUID user, PlanSyncRepository.CreatingSync sync,
            PlanSyncRepository.ChildResults children, PlanPublicationPort.ActivePlan plan,
            PlanPublicationRepository.Pointer pointer, UUID calculationId,
            PlanPublicationRepository.CalculationView calculation, PlanPublicationRepository.Lineage lineage,
            Candidate candidate) {
        if (!lineage.candidateId().equals(children.candidateId())
                || !lineage.classificationId().equals(children.classificationId())
                || !lineage.conversionId().equals(children.conversionId())
                || sync.tc() == null || !sync.tc().equals(candidate.tc()))
            return false;
        if (!"SUCCEEDED".equals(sync.status()) || !sync.planId().equals(plan.planId())
                || !calculationId.equals(sync.calculationId()) || sync.publishedPointerVersion() == null
                || sync.publishedPointerVersion() != pointer.version()
                || sync.basePointerVersion() + 1 != pointer.version()
                || !sync.portfolioId().equals(calculation.portfolioId()) || !sync.intentId().equals(plan.intentId())
                || !sync.membershipId().equals(lineage.membershipId())
                || !sync.connectionId().equals(lineage.connectionId())
                || !sync.providerAccountId().equals(lineage.providerAccountId())
                || !sync.baseCalculationId().equals(lineage.previousCalculationId())
                || sync.tc() == null || !sync.tc().equals(calculation.verifiedThrough()) || sync.windowStart() == null
                || sync.mode() == null)
            return false;
        // A normal sync keeps the extended watermark; an extended sync, a precision verification and a continuation
        // each move it to tc. Any other mode is not one this lineage can have.
        Instant expectedExtended = switch (sync.mode()) {
            case "NORMAL" -> sync.baseExtendedAt();
            case "EXTENDED", "VERIFY", "CATCH_UP", "COMPLETION" -> sync.tc();
            default -> null;
        };
        if (expectedExtended == null || !lineage.lastExtendedAt().equals(expectedExtended)) return false;
        var previous = repository.calculation(user, sync.baseCalculationId()).orElse(null);
        var previousLineage = repository.lineage(user, sync.baseCalculationId()).orElse(null);
        if (previous == null || previousLineage == null || !previous.planId().equals(plan.planId())
                || !previous.portfolioId().equals(calculation.portfolioId())
                || !previous.t0().equals(calculation.t0()) || !previous.endAt().equals(calculation.endAt())
                || !previous.period().equals(calculation.period())
                || !sameDecimal(previous.principal(), calculation.principal())
                || !sameDecimal(previous.targetRate(), calculation.targetRate())
                || previous.portfolioSequence() + 1 != calculation.portfolioSequence()
                || !previous.basisPortfolioSnapshotId().equals(calculation.basisPortfolioSnapshotId())
                || !previousLineage.basisSourceSnapshotId().equals(lineage.basisSourceSnapshotId())
                || !previous.verifiedThrough().equals(sync.baseVerifiedThrough())
                || !previousLineage.lastExtendedAt().equals(sync.baseExtendedAt())
                || !previousLineage.candidateId().equals(sync.baseCandidateId()))
            return false;
        Instant windowStart = sync.windowStart();
        return previousLineage.candidateId().equals(candidate.baseCandidateId())
                && windowStart.equals(candidate.windowStart()) && !windowStart.isBefore(calculation.t0())
                && !windowStart.isAfter(previous.verifiedThrough())
                && calculation.basisAt().isAfter(previous.verifiedThrough());
    }

    /**
     * The source snapshot of a completion version (Story 2.14): the observation its own completion attempt — or an
     * earlier completion attempt of the same plan, whose observation was fixed once and reused — published as the
     * version's portfolio snapshot, measured when the version says and in the source policy of its candidate.
     */
    private boolean completionSource(UUID user, PlanPublicationRepository.CalculationView calculation,
            PlanPublicationRepository.Lineage lineage, PlanPublicationRepository.SourceOrigin source) {
        var observation = syncAttempts.observation(user, calculation.portfolioSnapshotId()).orElse(null);
        return observation != null && PlanSyncRepository.COMPLETION.equals(observation.purpose())
                && observation.sourceSnapshotId().equals(lineage.sourceSnapshotId())
                && observation.sequence() == calculation.portfolioSequence()
                && observation.jobId().equals(source.planSyncJobId()) && source.candidateId() == null;
    }

    /**
     * Whether the version was calculated from exactly the inputs its attempt pinned at admission: the
     * intent, portfolio, membership, connection and provider account; the basis snapshots; the candidate,
     * classification and conversion; the terms, {@code tc}, E(tc) and its measurement time; the policies.
     *
     * <p>The portfolio pointer the publication advanced from is the plan's basis snapshot, and the version's
     * snapshot is the next sequence. That holds for a first publication; an incremental sync's version is judged
     * by {@link #continuedFrom} instead, and Story 2.15 (a following plan) must revisit it.
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

    /**
     * The version was calculated for this plan, in its portfolio, on its terms. The adjustment history
     * (Story 2.10) judges the version it lists by the same rule.
     */
    static boolean belongs(PlanPublicationRepository.CalculationView calculation,
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
        if (accounts.decide(user, user, AccountAccessKind.WRITE) != AccessDecision.ALLOWED) return false;
        // 사용자의 지금 portfolio와 연결: 첫 발행이 그 위에서 돈다.
        var membership = portfolios.activeSource(user).orElse(null);
        var connection = sources.snapshotConnection(user).orElse(null);
        return !restricted(membership == null ? null : membership.portfolioId(),
                connection == null ? null : connection.connectionId());
    }

    /**
     * 첫 발행을 제한이 막는가(Story 3.2) — 그것이 끝맺는 파이프라인의 동기화, 또는 발행할 계산({@code CALC-1.0})의 표시가
     * 그 portfolio·연결이나 전역에 걸렸는가. 아직 계획이 없으므로 계획 제한은 적용되지 않는다. 판독 실패는 예외다.
     */
    private boolean restricted(@Nullable UUID portfolioId, @Nullable UUID connectionId) {
        return restrictions.blocks(java.util.EnumSet.of(RestrictionFeature.SYNC, RestrictionFeature.CALCULATION_DISPLAY),
                RestrictionSubject.none().withPortfolio(portfolioId).withConnection(connectionId)
                        .withCalculationPolicy(PlanCalculator.CALCULATION_POLICY));
    }

    /** {@code P0 > 0}. The plan's start value is a plain decimal string. */
    private static boolean positive(String decimal) {
        try {
            return new java.math.BigDecimal(decimal).signum() > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
