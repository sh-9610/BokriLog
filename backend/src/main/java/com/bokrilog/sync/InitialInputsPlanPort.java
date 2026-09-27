package com.bokrilog.sync;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Sync-owned request to the plan module's eligible open-intent adapter. */
public interface InitialInputsPlanPort {
    record Intent(UUID intentId, UUID portfolioId, UUID connectionId,
            UUID providerAccountId, Instant t0) {}
    Optional<Intent> eligibleIntent(@Nullable UUID userId, UUID intentId);
}
