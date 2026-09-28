package com.bokrilog.sync;

import com.bokrilog.portfolio.PortfolioMembershipApi.OfficialValue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** 첫 공식 snapshot 작업과 화면 조회의 소유 경계. */
public interface SyncJobApi {
    record Job(UUID jobId, String status, @Nullable String failureReason) {}
    record State(@Nullable Job job, @Nullable OfficialValue officialValue,
            boolean connected, boolean restricted) {}
    record SourceSnapshot(UUID sourceSnapshotId, UUID userId, UUID connectionId,
            UUID providerAccountId, String inputStatus, String policyVersion) {}
    record CollectionJob(UUID jobId, String status, @Nullable String failureCode,
            @Nullable Instant lastCheckedAt) {}
    record InitialCollectionState(boolean permitted, @Nullable CollectionJob job) {}
    record ClassificationJob(UUID jobId, String status, @Nullable String failureCode,
            @Nullable Instant processedAt, int depositCount, int withdrawalCount,
            int excludedCount, int reviewCount, @Nullable String reasonCode) {}
    record ClassificationState(boolean permitted, @Nullable ClassificationJob job) {}

    /**
     * One flow that was not converted automatically (Story 2.6 AC-10).
     *
     * <p>Only held flows are itemized — conversion-required and review-required alike — and only with
     * what the user needs to recognize them: the flow status, the direction, the unrounded source
     * quantity (absolute), the event time and a safe reason. No USD value is carried here or anywhere
     * else in this view.
     */
    record ConversionPendingFlow(String direction, String quantity, Instant occurredAt, String status,
            String reasonCode, boolean retryable) {}
    record ConversionPendingAsset(String assetCode, int count, List<ConversionPendingFlow> flows) {}

    /**
     * The latest conversion attempt of the current COMPLETE classification.
     *
     * <p>{@code flowCount} is the attempt's fixed input count. The four outcome counts are those of the
     * result bundle the attempt published or reused, and are {@code null} when the attempt has no
     * bundle (queued, running, cancelled or interrupted) — they are never reported as zeros that do
     * not add up to {@code flowCount}.
     */
    record ConversionJob(UUID jobId, String status, @Nullable String reasonCode, boolean retryable,
            @Nullable Instant processedAt, int flowCount, @Nullable Integer convertedCount,
            @Nullable Integer estimatedCount, @Nullable Integer conversionRequiredCount,
            @Nullable Integer reviewCount, boolean calculable, String pricePolicyVersion,
            String calculationPolicyVersion, List<ConversionPendingAsset> pendingAssets) {}
    record ConversionState(boolean permitted, @Nullable ConversionJob job) {}

    /** 같은 연결의 진행 작업과 같은 멱등 키는 기존 작업을 반환한다. */
    Optional<Job> admit(@Nullable UUID userId, String idempotencyKey);
    Optional<Job> job(@Nullable UUID userId, UUID jobId);
    Optional<State> state(@Nullable UUID userId);
    Optional<CollectionJob> admitInitialInputs(@Nullable UUID userId, UUID intentId, String idempotencyKey);
    Optional<CollectionJob> initialCollectionJob(@Nullable UUID userId, UUID jobId);
    InitialCollectionState initialCollectionState(@Nullable UUID userId, UUID intentId);
    Optional<ClassificationJob> admitCashflowClassification(@Nullable UUID userId, UUID intentId,
            String idempotencyKey);
    ClassificationState cashflowClassificationState(@Nullable UUID userId, UUID intentId);
    /**
     * Accepts an event-time USD conversion attempt for the intent's COMPLETE classification. The
     * same key and an in-progress attempt are returned as they are. Empty when the classification
     * is missing, partial or under review, or the user, intent, connection or restriction state
     * does not permit it. Never calls the provider.
     */
    Optional<ConversionJob> admitCashflowConversion(@Nullable UUID userId, UUID intentId, String idempotencyKey);
    ConversionState cashflowConversionState(@Nullable UUID userId, UUID intentId);
    /** Read-only source provenance validation for an opaque portfolio snapshot reference. */
    Optional<SourceSnapshot> sourceSnapshot(@Nullable UUID userId, UUID sourceSnapshotId);

    // -------------------------------------------------------------------- Story 2.7

    /**
     * One first-publication attempt. {@code planId} is present only when it published.
     *
     * @param retryable whether a new attempt with a new key can succeed without new conditions or data
     */
    record PublicationJob(UUID jobId, String status, @Nullable String failureCode, boolean retryable,
            @Nullable UUID planId, Instant createdAt, @Nullable Instant finishedAt) {}

    /** Admission of a first-publication attempt. */
    sealed interface PublicationAdmission permits PublicationAdmission.Accepted, PublicationAdmission.Refused {
        record Accepted(PublicationJob job) implements PublicationAdmission {}
        /**
         * @param code {@code NOT_PERMITTED} (403), {@code IDEMPOTENCY_KEY_REQUIRED} (422), or one of
         *     {@code IDEMPOTENCY_CONFLICT}, {@code ACTIVE_PLAN_EXISTS}, {@code PLAN_CONDITIONS_REQUIRED},
         *     {@code PLAN_PERIOD_ENDED}, {@code INPUTS_NOT_READY}, {@code CALCULATION_POLICY_UNVERIFIED}, or —
         *     when an attempt already failed deterministically on exactly these inputs —
         *     {@code CALCULATION_RANGE_EXCEEDED} or {@code CALCULATION_INPUT_INVALID} (409)
         */
        record Refused(String code) implements PublicationAdmission {}
    }

    /**
     * The intent's publication state for the user's screen.
     *
     * @param permitted false only when the account itself is not readable here (no user, or its
     *     deletion is in progress); another user's or an unknown intent is permitted and not startable
     * @param job the latest attempt for this user and intent, if any
     * @param startable whether a new attempt would be admitted now, judged by the same readiness the
     *     admission uses; false while an attempt is in progress
     * @param reason when not startable and no attempt is in progress, the admission's refusal code
     */
    record PublicationState(boolean permitted, @Nullable PublicationJob job, boolean startable,
            @Nullable String reason) {}

    // -------------------------------------------------------------------- Story 2.8

    /** One of the six metrics. {@code value} is a canonical decimal string or absent with a reason. */
    record DashboardMetric(String metric, @Nullable String value, String nature,
            @Nullable String unavailableReason) {}

    /**
     * One trust axis as the server judged it (Story 2.8 AC-5). Every field is a code or a time; the
     * screen maps the codes to copy through closed maps and never judges time itself.
     *
     * @param lastNormalAt the time of the official bundle this axis vouches for: the measurement
     *     (freshness), the cash-flow verification (input reliability) or the calculation basis (nature)
     * @param affectedMetrics the metrics this axis's state reaches, in the screen's order
     * @param nextAction what the axis leaves the user to do: {@code WAIT_FOR_SYNC} (freshness, while a
     *     job runs) or {@code NONE}. An axis never produces the screen-level {@code CREATE_PLAN} or
     *     {@code WAIT_FOR_RESULT} — those belong to {@link Dashboard.NoPlan} and {@link Dashboard.Preparing}
     */
    record TrustAxis(String state, String cause, Instant lastNormalAt, List<String> affectedMetrics,
            String nextAction) {}

    /**
     * The calculation nature axis and the character it never overwrites: "not computable" is its own
     * state, and an undefined ratio of an estimated plan is still estimated.
     */
    record NatureAxis(TrustAxis axis, String character, List<String> unavailableMetrics,
            int estimatedFlowCount) {}

    /**
     * The three independent axes. There is no combined score. Freshness is judged by SYNC-UX-1.0 against
     * the database clock; its two times travel under their own names in the result.
     */
    record TrustAxes(TrustAxis freshness, TrustAxis inputReliability, NatureAxis calculationNature) {}

    /**
     * The active plan's official bundle, read through its pointer in one consistent read: the plan terms,
     * the pointer, the calculation version it cites, that version's six metrics and the three axes.
     * Nothing is assembled from another version or from candidate, held or review data.
     */
    record DashboardResult(UUID planId, String planStatus, String principal, Instant startAt, Instant endAt,
            String period, String targetRate, long pointerVersion, Instant publishedAt, UUID calculationId,
            UUID portfolioSnapshotId, UUID basisPortfolioSnapshotId, Instant basisAt, Instant measuredAt,
            Instant cashflowVerifiedThrough, String calculationPolicy, String calendarPolicy,
            String displayPolicy, String pricePolicy, String implementationVersion, String inputHash,
            String resultHash, int flowCount, int estimatedFlowCount, TrustAxes trust,
            List<DashboardMetric> metrics) {}

    /**
     * The dashboard read model (Story 2.8). One of five outcomes; only {@link Dashboard.Ready} carries
     * financial fields. The screen-level next action is a closed set per outcome: {@code CREATE_PLAN}
     * ({@link Dashboard.NoPlan}), {@code WAIT_FOR_RESULT} ({@link Dashboard.Preparing}), and
     * {@code WAIT_FOR_SYNC} or {@code NONE} ({@link Dashboard.Ready}).
     */
    sealed interface Dashboard permits Dashboard.NotPermitted, Dashboard.Unavailable, Dashboard.NoPlan,
            Dashboard.Preparing, Dashboard.Ready {
        /** The account's own access refused it before or after the read (its deletion began or ended). */
        record NotPermitted() implements Dashboard {}
        /** The pointer's bundle did not agree with itself; nothing is returned in part. */
        record Unavailable() implements Dashboard {}
        /** No active plan and no publication in progress. */
        record NoPlan(String nextAction) implements Dashboard {}
        /** No active plan yet, and one of the user's publication attempts is queued or running. */
        record Preparing(String nextAction) implements Dashboard {}
        /** The active plan's official bundle. */
        record Ready(DashboardResult result, String nextAction) implements Dashboard {}
    }

    /**
     * Accepts the first publication of the intent's active plan and official result. Its inputs are
     * pinned now: the intent's terms and one lineage of complete candidate, COMPLETE classification and
     * calculable conversion. The same key with the same intent returns the same attempt; an attempt in
     * progress for the intent is returned instead of a second one. The key is required: this command
     * creates an immutable plan, so a missing key is refused rather than replaced by a random one.
     */
    PublicationAdmission admitPlanPublication(@Nullable UUID userId, UUID intentId,
            @Nullable String idempotencyKey);

    /** The intent's latest publication attempt and whether a new one could start now. */
    PublicationState planPublicationState(@Nullable UUID userId, UUID intentId);

    /** Whether a dashboard read found its bundle agreeing with itself, or read no bundle at all. */
    enum BundleConsistency { CONSISTENT, INCONSISTENT, NOT_READ }

    /**
     * One dashboard read and what it learned about the bundle. {@code consistency} is
     * {@link BundleConsistency#NOT_READ} when the account was refused before the read; a bundle read and
     * then dropped by the second deletion check keeps the consistency it was read with.
     */
    record DashboardRead(Dashboard dashboard, BundleConsistency consistency) {}

    /**
     * The user's dashboard (Story 2.8): the account's own access is checked, the plan, pointer,
     * calculation version, metrics and freshness are read in one read-only {@code REPEATABLE READ}
     * transaction, and the access is checked again. A deletion that began or finished on either side
     * wins. A restriction on a write command does not hide an existing result. A failing read throws;
     * nothing is returned in part. The HTTP surface writes the one operational line per read, because it
     * alone knows the status it answered with.
     */
    DashboardRead dashboard(@Nullable UUID userId);

    // -------------------------------------------------------------------- Story 2.9

    /**
     * The dashboard result a screen shows, named by the three values of its official pointer. The
     * evidence read only judges whether they are the user's current pointer; nothing is looked up by them.
     */
    record EvidenceReference(UUID planId, long pointerVersion, UUID calculationId) {}

    /**
     * The read-only lineage of the current official result (Story 2.9): which snapshots, times, inputs,
     * policies and calculation version it came from, how each metric's character was reached, and the
     * attempts of the same intent that never published. Internal user ids, provider account ids, the
     * Bybit UID, credentials, raw provider responses and individual cash-flow events are not here.
     */
    record CalculationEvidence(UUID planId, long pointerVersion, UUID calculationId, Snapshots snapshots,
            Times times, Connection connection, SourceRange sourceRange, Classification classification,
            Conversion conversion, Cashflows cashflows, Plan plan, Calculation calculation,
            List<MetricImpact> metrics, List<OfficialVersion> officialVersions,
            List<UnpublishedAttempt> unpublishedAttempts) {

        /**
         * @param portfolioSnapshotId the official portfolio snapshot the version was calculated on
         * @param portfolioSequence that snapshot's publication sequence
         * @param basisPortfolioSnapshotId the plan's basis snapshot (its start value)
         * @param basisSourceSnapshotId the source snapshot the basis snapshot was composed from (measured at t0)
         * @param sourceSnapshotId the tc source snapshot the portfolio snapshot was composed from
         */
        public record Snapshots(UUID portfolioSnapshotId, long portfolioSequence, UUID basisPortfolioSnapshotId,
                UUID basisSourceSnapshotId, UUID sourceSnapshotId) {}

        /**
         * The plan start and the three times of the version, each under its own name. Bybit gives no token
         * that ties a balance and the cash-flow log to one moment, so the measurement and the cash-flow
         * verification are two facts, never one "latest" time.
         */
        public record Times(Instant t0, Instant tc, Instant measuredAt, Instant cashflowVerifiedThrough) {}

        /**
         * Where the inputs were collected: the provider, the connection (provenance only) and the connection's
         * policy version, as the first snapshot recorded it after checking it against the connection's own.
         */
        public record Connection(String provider, UUID connectionId, String policyVersion) {}

        /** The collected range: the earliest page start and the latest page end, with the collection policy. */
        public record SourceRange(Instant rangeStart, Instant rangeEnd, String collectionPolicy,
                String completenessHash) {}

        public record Classification(UUID classificationId, String policyVersion, String inputScopeHash) {}

        public record Conversion(UUID conversionId, String pricePolicy, String resultFingerprint) {}

        /** How many external cash flows the version applied, and how many of them were estimated. */
        public record Cashflows(int appliedCount, int estimatedCount) {}

        /** The plan version: its id and its immutable terms (canonical decimal strings). */
        public record Plan(UUID planId, String principal, Instant startAt, Instant endAt, String period,
                String targetRate) {}

        public record Calculation(UUID calculationId, String calculationPolicy, String calendarPolicy,
                String displayPolicy, String precisionPolicy, String timeZone, String implementationVersion,
                String inputHash, String resultHash, Instant createdAt) {}

        /**
         * One metric's character and why: an undefined metric's reason code, and — for a defined derived
         * metric of an estimated version — the estimated cause, derived from the applied flows (not a stored
         * column). An undefined metric carries its reason and no cause.
         */
        public record MetricImpact(String metric, String nature, @Nullable String unavailableReason,
                @Nullable EstimatedCause estimatedCause) {}

        /** The flows converted with an estimated price and the kind of that price evidence. */
        public record EstimatedCause(String evidenceType, int flowCount) {}

        /**
         * One official version: its publication order (the pointer version), the time it became official
         * and whether it is the current one.
         */
        public record OfficialVersion(long pointerVersion, UUID calculationId, Instant publishedAt,
                boolean current) {}

        /** An attempt of the same intent that published nothing. Read-only; never an official value. */
        public record UnpublishedAttempt(String status, String reasonCode, Instant finishedAt) {}
    }

    /** The outcome of one evidence read. Only {@link Found} carries lineage. */
    sealed interface EvidenceOutcome permits EvidenceOutcome.NotPermitted, EvidenceOutcome.NotFound,
            EvidenceOutcome.Unavailable, EvidenceOutcome.Found {
        /** The account's own access refused it before or after the read. */
        record NotPermitted() implements EvidenceOutcome {}
        /**
         * The reference is not the user's current official pointer — another user's, an earlier version,
         * an unknown or malformed id, a missing value, or no active plan at all. One answer for all of them.
         */
        record NotFound() implements EvidenceOutcome {}
        /** The current pointer's lineage did not agree with itself; nothing is returned in part. */
        record Unavailable() implements EvidenceOutcome {}
        record Found(CalculationEvidence evidence) implements EvidenceOutcome {}
    }

    /**
     * One evidence read and what it learned about the lineage: {@link BundleConsistency#NOT_READ} when no
     * lineage was read (refused first, or the reference names no current pointer).
     */
    record EvidenceRead(EvidenceOutcome outcome, BundleConsistency consistency) {}

    /**
     * The calculation evidence of the user's current official result (Story 2.9). The account's own access
     * is checked, the pointer, the calculation version, its metrics and cash flows, and the collection,
     * classification and conversion records are read in one read-only {@code REPEATABLE READ} transaction,
     * and the access is checked again. A {@code null} reference (missing or malformed) is not found. A
     * failing read throws; nothing is returned in part.
     */
    EvidenceRead calculationEvidence(@Nullable UUID userId, @Nullable EvidenceReference reference);

    /** portfolio 삭제 뒤, source 삭제 앞에 호출한다. 발행 결과·시도를 먼저 지운다. */
    void eraseSyncData(@Nullable UUID userId);
}
