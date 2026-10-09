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

    /**
     * {@link #eligibleIntent}에서 그 운영 제한들만 빼고 본다(Story 3.3). 강한 신호를 낸 초기 수집이 자기 신호의 제한에 막혀
     * 검토 필요로 맺지 못하는 일을 막는 데만 쓴다 — 나머지 판정(계정·intent 상태·연결·portfolio)은 같다.
     */
    Optional<Intent> eligibleIntent(@Nullable UUID userId, UUID intentId, java.util.Set<UUID> excludedRestrictions);
}
