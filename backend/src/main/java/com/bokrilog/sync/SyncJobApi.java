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
    /** portfolio 삭제 뒤, source 삭제 앞에 호출한다. */
    void eraseSyncData(@Nullable UUID userId);
}
