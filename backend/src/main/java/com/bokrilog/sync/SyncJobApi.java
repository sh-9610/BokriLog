package com.bokrilog.sync;

import com.bokrilog.portfolio.PortfolioMembershipApi.OfficialValue;
import java.time.Instant;
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
    /** Read-only source provenance validation for an opaque portfolio snapshot reference. */
    Optional<SourceSnapshot> sourceSnapshot(@Nullable UUID userId, UUID sourceSnapshotId);
    /** portfolio 삭제 뒤, source 삭제 앞에 호출한다. */
    void eraseSyncData(@Nullable UUID userId);
}
