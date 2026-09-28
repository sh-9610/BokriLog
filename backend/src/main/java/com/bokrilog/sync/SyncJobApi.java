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

    /** One of the six metrics. {@code value} is a canonical decimal string or absent with a reason. */
    record PlanResultMetric(String metric, @Nullable String value, String nature,
            @Nullable String unavailableReason) {}

    /**
     * The active plan's official result, read through its pointer only: the plan terms, the pointer,
     * the calculation version it cites and that version's six metrics. Nothing is assembled from
     * another version or from candidates.
     *
     * @param freshness {@code FRESH}, {@code SYNCING} or {@code STALE} against the database clock
     *     (SYNC-UX-1.0: both {@code measuredAt} and {@code cashflowVerifiedThrough} within 15 minutes)
     */
    record PlanResult(UUID planId, String planStatus, String principal, Instant startAt, Instant endAt,
            String period, String targetRate, long pointerVersion, Instant publishedAt, UUID calculationId,
            UUID portfolioSnapshotId, UUID basisPortfolioSnapshotId, Instant basisAt, Instant measuredAt,
            Instant cashflowVerifiedThrough, String calculationPolicy, String calendarPolicy,
            String displayPolicy, String pricePolicy, String implementationVersion, String inputHash,
            String resultHash, int flowCount, int estimatedFlowCount, boolean estimated,
            String inputReliability, String freshness, List<PlanResultMetric> metrics) {}

    sealed interface ActivePlanResult permits ActivePlanResult.NotPermitted, ActivePlanResult.None,
            ActivePlanResult.Found {
        record NotPermitted() implements ActivePlanResult {}
        record None() implements ActivePlanResult {}
        record Found(PlanResult result) implements ActivePlanResult {}
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

    /**
     * The official result of the user's active plan through its pointer. Only the account's own access
     * gates this read (it is refused while the account's deletion is in progress); a restriction on a
     * write command does not hide an existing result.
     */
    ActivePlanResult activePlanResult(@Nullable UUID userId);

    /** portfolio 삭제 뒤, source 삭제 앞에 호출한다. 발행 결과·시도를 먼저 지운다. */
    void eraseSyncData(@Nullable UUID userId);
}
