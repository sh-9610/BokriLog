package com.bokrilog.plan;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Public boundary for validating and storing an immutable plan condition intent. */
public interface PlanConditionApi {
    String COMMAND = "plan-condition-create";

    enum Period { DAILY, WEEKLY, MONTHLY }

    record Snapshot(String decimal, Instant measuredAt, Instant publishedAt,
            UUID portfolioId, UUID snapshotId, UUID membershipId, UUID connectionId,
            UUID providerAccountId, UUID sourceSnapshotId, String inputStatus,
            String sourcePolicyVersion, String portfolioPolicyVersion) {}
    record Intent(UUID intentId, String status, String decimal, Instant measuredAt,
            Instant endAt, Period period, String targetRate, UUID snapshotId, Instant createdAt) {}
    record State(boolean eligible, @Nullable String reason, @Nullable Snapshot snapshot,
            @Nullable Intent openIntent) {}
    record PreviewInput(@Nullable String endAtSeoul, @Nullable String period, @Nullable String targetRate) {}
    record Preview(String confirmationRef, Instant expiresAt, Snapshot snapshot,
            Instant endAt, Period period, String targetRate,
            String calendarPolicy, String calculationPolicy) {}
    record Confirmation(boolean accepted, boolean conflict, @Nullable Intent intent) {}

    State state(@Nullable UUID userId);
    Optional<Preview> preview(@Nullable UUID userId, @Nullable String sessionId, @Nullable PreviewInput input);
    Confirmation confirm(@Nullable UUID userId, @Nullable String sessionId,
            @Nullable String confirmationRef, @Nullable String idempotencyKey);
    void erasePlanData(@Nullable UUID userId);
}
