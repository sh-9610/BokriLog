package com.bokrilog.plan;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Public boundary for validating and storing an immutable plan condition intent. */
public interface PlanConditionApi {
    String COMMAND = "plan-condition-create";

    /** 활성 계획이 있어 새 조건을 받지 않는다(Story 2.7). 조건 화면·미리보기·확인이 같은 값을 쓴다. */
    String ACTIVE_PLAN_EXISTS = "ACTIVE_PLAN_EXISTS";

    /**
     * 계획이 중도 종료돼 새 조건을 받지 않는다(Story 2.13). 종료된 계획의 첫 정상 snapshot(옛 P0·t0)으로 새
     * 계획을 만들 수 없게 막는 자리이며, 후속 계획은 Story 2.15가 연다. 조건 화면·미리보기·확인이 같은 값을 쓴다.
     */
    String PLAN_ENDED = "PLAN_ENDED";

    enum Period { DAILY, WEEKLY, MONTHLY }

    record Snapshot(String decimal, Instant measuredAt, Instant publishedAt,
            UUID portfolioId, UUID snapshotId, UUID membershipId, UUID connectionId,
            UUID providerAccountId, UUID sourceSnapshotId, String inputStatus,
            String sourcePolicyVersion, String portfolioPolicyVersion) {}
    record Intent(UUID intentId, String status, String decimal, Instant measuredAt,
            Instant endAt, Period period, String targetRate, UUID snapshotId, Instant createdAt) {}
    /** Validated open intent and its current portfolio/source provenance for sync admission and publish. */
    record CollectionIntent(UUID intentId, UUID portfolioId, UUID connectionId,
            UUID providerAccountId, Instant t0, Instant expiresAt) {}
    /** The immutable active plan a consumed intent became (Story 2.7). */
    record ActivePlan(UUID planId, String status, String decimal, Instant t0, Instant endAt,
            Period period, String targetRate, UUID snapshotId, UUID intentId, Instant createdAt) {}
    /**
     * @param activePlan 활성 계획이 있으면 그 요약이다. 그때 {@code eligible}은 거짓이고 이유는
     *     {@link #ACTIVE_PLAN_EXISTS}다 — 새 조건을 받지 않는다. 활성 계획 없이 종료된 계획이 있으면 이유는
     *     {@link #PLAN_ENDED}이고 요약은 없다
     */
    record State(boolean eligible, @Nullable String reason, @Nullable Snapshot snapshot,
            @Nullable Intent openIntent, @Nullable ActivePlan activePlan) {}
    record PreviewInput(@Nullable String endAtSeoul, @Nullable String period, @Nullable String targetRate) {}
    record Preview(String confirmationRef, Instant expiresAt, Snapshot snapshot,
            Instant endAt, Period period, String targetRate,
            String calendarPolicy, String calculationPolicy) {}
    /**
     * @param reason 확인하지 못한 이유. 활성 계획이 있으면 {@link #ACTIVE_PLAN_EXISTS}, 종료된 계획이 있으면
     *     {@link #PLAN_ENDED}다
     */
    record Confirmation(boolean accepted, boolean conflict, @Nullable Intent intent, @Nullable String reason) {}

    State state(@Nullable UUID userId);
    Optional<Preview> preview(@Nullable UUID userId, @Nullable String sessionId, @Nullable PreviewInput input);
    Confirmation confirm(@Nullable UUID userId, @Nullable String sessionId,
            @Nullable String confirmationRef, @Nullable String idempotencyKey);
    Optional<CollectionIntent> collectionIntent(@Nullable UUID userId, UUID intentId);
    /** 계획·종료 기록·intent·확인·멱등 데이터를 지운다. 다른 사용자 것은 남는다. 멱등하다. */
    void erasePlanData(@Nullable UUID userId);

    // ------------------------------------------------------------------ Story 3.2

    /**
     * 운영 제한의 대상 확인에 쓰는 계획 하나(Story 3.2). 사용자 없이 id로 찾는다 — 운영자 표면이 부른다.
     *
     * @param userId 소유자. 제한 행의 귀속이다
     * @param connectionId 계획의 <b>지금</b> 연결(Story 2.17) — 계획과 같은 Bybit 계정·portfolio의 지금 활성 연결이고,
     *     그런 연결이 없을 때만 계획이 처음 기록한 연결이다. 재연결 뒤에는 이전 연결에 건 제한이 이 계획을 막지 않는다
     * @param providerAccountId 계획이 고정한 Bybit 계정(불변)
     * @param status 계획 상태 — {@code ACTIVE}·{@code ENDED_EARLY}·{@code COMPLETED}
     */
    record PlanTarget(UUID planId, UUID userId, UUID portfolioId, UUID connectionId, UUID providerAccountId,
            String status, String calculationPolicy) {}

    /** 그 계획. 없으면 빈 값이다. */
    Optional<PlanTarget> planTarget(UUID planId);

    /**
     * 그 사용자의 활성 계획이 고정한 Bybit 계정(Story 2.17). 활성 계획이 없으면 빈 값이다. 재연결 확정이 계획 쓰기 잠금 아래에서
     * 새 연결의 계정과 대조하는 데 쓴다 — 계정 열 하나만 읽고, 호출자 트랜잭션에서 읽는다.
     */
    Optional<UUID> activePlanProviderAccount(UUID userId);

    /**
     * 조건에 맞는 계획의 수. 운영 제한의 영향 미리보기가 쓴다. {@code null}인 조건은 거르지 않는다.
     *
     * @param activeOnly 참이면 활성 계획만 센다
     */
    long countPlans(boolean activeOnly, @Nullable UUID userId, @Nullable UUID portfolioId,
            @Nullable String calculationPolicy);

    // ------------------------------------------------------------------ Story 3.10 복원 검증

    /**
     * 활성 계획 유일성을 어긴 수 — 활성 계획이 둘 이상인 portfolio 수와 사용자 수의 합. 부분 유일 색인이 portfolio마다 막지만 복원된
     * DB에서 서비스를 열기 전에 다시 본다(0이어야 한다).
     */
    long activePlanViolations();

    /**
     * 그 사용자에게 아직 귀속된 이 모듈의 행 수. {@code plan_*} 표의 {@code user_id} 열을 카탈로그로 찾아 센다. 삭제가 끝난 계정이면
     * 0이어야 한다.
     */
    long remainingRowsOf(UUID userId);
}
