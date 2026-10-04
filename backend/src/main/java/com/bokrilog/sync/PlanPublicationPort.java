package com.bokrilog.sync;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 최초 발행이 계획 모듈에 요청하는 것(Story 2.7). <b>sync가 소유하고 plan이 구현한다.</b>
 *
 * <p>{@link InitialInputsPlanPort}와 같은 역전 port다. plan은 이미 sync의 공개 API에 의존하므로
 * sync가 plan을 부르면 순환이 된다. 그래서 필요한 계약을 sync가 여기 적고 plan이 구현한다.
 *
 * <p>변경 command({@link #lockPlanWrites}, {@link #createActivePlan})는 <b>호출자 트랜잭션에
 * 참여만 한다</b>. 발행 트랜잭션의 주인은 sync publication service 하나다.
 */
public interface PlanPublicationPort {

    /**
     * 발행할 수 있는 intent의 불변 조건과 그 기준 snapshot provenance.
     *
     * @param period {@code DAILY}·{@code WEEKLY}·{@code MONTHLY}
     */
    record Terms(UUID intentId, UUID portfolioId, UUID basisSnapshotId, UUID membershipId,
            UUID connectionId, UUID providerAccountId, UUID basisSourceSnapshotId, String principal,
            Instant startAt, Instant endAt, String period, String targetRate, String sourcePolicyVersion,
            String portfolioPolicyVersion, String calendarPolicy, String calculationPolicy) {}

    /** 활성 계획의 요약. 계획 행은 불변이다. */
    record ActivePlan(UUID planId, UUID intentId, UUID portfolioId, String principal, Instant startAt,
            Instant endAt, String period, String targetRate, Instant createdAt) {}

    /**
     * 그 사용자의 OPEN intent가 현재 첫 공식 snapshot·활성 membership·연결·정책과 여전히 일치하고
     * 활성 계획이 없으면 그 조건을 돌려준다. 그 밖에는 빈 값이다(삭제·제한 포함).
     *
     * <p>종료 시각 {@code T}는 여기서 판정하지 않는다. 호출자가 데이터베이스 시각으로 판정하고
     * {@code PLAN_PERIOD_ENDED}로 따로 답한다 — 여기서 애플리케이션 시계로 걸러 내면 기간 종료가
     * "조건이 바뀌었다"로 뭉개지고, 두 시계가 다른 순간에 서로 다른 답을 낸다.
     */
    Optional<Terms> publishableTerms(@Nullable UUID userId, UUID intentId);

    /** 그 사용자의 활성 계획. 없으면 빈 값이다. */
    Optional<ActivePlan> activePlan(@Nullable UUID userId);

    /**
     * 중도 종료한 계획의 종료 기록(Story 2.13). plan 모듈이 저장한 값 그대로이며 sync는 대조만 한다.
     *
     * @param reason {@code USER_REQUEST} 또는 {@code LONG_GAP_NEW_PLAN}
     * @param officialCalculationId 종료 시점의 마지막 정상 공식 계산 버전. 그 계획의 공식 포인터가 가리키던 값이다
     * @param lastMeasuredAt 그 계산의 {@code measured_at}을 복사한 값. 종료 시각으로 바꿔 쓰지 않는다
     * @param completion {@code NOT_APPLICABLE} 또는 {@code UNAVAILABLE_DATA_GAP}
     * @param uncollectedFrom 장기 미동기화 종료의 미수집 구간 시작(그 밖에는 {@code null})
     */
    record PlanEnd(String reason, Instant endedAt, UUID officialCalculationId, long officialPointerVersion,
            UUID officialPortfolioSnapshotId, Instant lastMeasuredAt, Instant cashflowVerifiedThrough, String completion,
            @Nullable Instant uncollectedFrom, @Nullable Instant uncollectedUntil) {}

    /**
     * 자연 만료로 완료된 계획의 완료 기록(Story 2.14). plan 모듈이 저장한 값 그대로이며 sync는 대조만 한다.
     *
     * @param completedAt 계획 종료 {@code T}. 전환이 실제로 일어난 시각({@code transitionedAt})과 다르다
     * @param comparison {@code PENDING}·{@code CONFIRMED}·{@code UNAVAILABLE}·{@code HELD}
     * @param reason {@code UNAVAILABLE}·{@code HELD}의 이유. 그 밖에는 {@code null}
     * @param observedPortfolioSnapshotId 첫 종료 후 관측이 발행된 portfolio snapshot. 관측 전에는 {@code null}이고,
     *     관측 다섯 값(snapshot·값·측정 시각·지연·고정 시각)은 함께 있거나 함께 없다
     * @param completionCalculationId 완료 계산 버전. 입력이 완전했던 판정에만 있다
     */
    record PlanCompletion(Instant completedAt, Instant transitionedAt, String comparison, @Nullable String reason,
            @Nullable UUID observedPortfolioSnapshotId, @Nullable String observedValue,
            @Nullable Instant observedMeasuredAt, @Nullable Long observationDelayMillis,
            @Nullable UUID completionCalculationId, @Nullable Integer postEndCashflowCount) {

        /** 첫 종료 후 관측이 고정됐는가. */
        public boolean observed() {
            return this.observedPortfolioSnapshotId != null;
        }
    }

    /**
     * 그 사용자의 가장 최근 계획과 상태(Story 2.13). {@code ENDED_EARLY}이면 종료 기록이, {@code COMPLETED}이면 완료
     * 기록(Story 2.14)이 함께 온다 — 기록이 없으면 그 값은 {@code null}이고, 호출자는 그것을 어긋난 묶음으로 읽는다.
     *
     * @param plan 계획의 불변 요약. 활성 계획이 아니어도 같은 모양이다
     * @param status {@code ACTIVE}, {@code ENDED_EARLY} 또는 {@code COMPLETED}
     */
    record LatestPlan(ActivePlan plan, String status, @Nullable PlanEnd end, @Nullable PlanCompletion completion) {}

    /**
     * 그 사용자의 가장 최근 계획. 없으면 빈 값이다. 대시보드·계산 근거·조정 이력이 활성 계획이 없을 때
     * "표시할 계획"(종료된 계획의 읽기 전용 기록)을 고르는 읽기다.
     */
    Optional<LatestPlan> latestPlan(@Nullable UUID userId);

    /** 소유 종단 기록 선택과 기록 목록. 활성 선택은 기본 대시보드만 제공한다. */
    Optional<LatestPlan> historicalPlan(UUID userId, UUID planId);
    java.util.List<LatestPlan> planHistory(UUID userId);
    /** 새 기준 성공은 이전 확인과 OPEN intent를 무효화한다. 호출자 트랜잭션에 참여한다. */
    void invalidatePreparedConditions(UUID userId);

    // ------------------------------------------------------------------ Story 2.14

    /**
     * 활성 계획을 자연 만료로 완료한다. <b>호출자 트랜잭션 전용</b>이며 호출자가 {@link #lockPlanWrites}와 계정
     * 생명주기 잠금을 먼저 잡는다(발행자·중도 종료와 같은 잠금 순서). 계획 행을 {@code FOR UPDATE}로 잠근 뒤
     * 데이터베이스 시각이 {@code T} 이상인지 다시 판정하고, {@code ACTIVE → COMPLETED}(정확히 한 행)와 완료 기록
     * (비교 {@code PENDING}, 장기 미동기화면 {@code UNAVAILABLE}/{@code DATA_GAP})을 쓴다.
     *
     * @return 쓴 완료 기록. 그 계획이 그 사용자의 활성 계획이 아니거나 아직 {@code T} 전이면 빈 값이고 아무것도 쓰지 않는다
     */
    Optional<PlanCompletion> completePlan(UUID userId, UUID planId, boolean dataGap);

    /**
     * 첫 종료 후 관측을 한 번만 고정한다. <b>호출자 트랜잭션 전용</b>. 완료 기록이 있고 아직 관측이 없을 때만 쓰며
     * 지연은 {@code measuredAt − T}다. 이미 고정됐으면 쓰지 않는다 — 더 늦거나 유리한 값으로 바꾸지 않는다.
     *
     * @return 이번에 고정했는가
     */
    boolean recordCompletionObservation(UUID userId, UUID planId, UUID portfolioSnapshotId, String value,
            Instant measuredAt);

    /**
     * 완료 비교를 판정해 쓴다. <b>호출자 트랜잭션 전용</b>. {@code PENDING}·{@code HELD}에서만 바뀌며
     * {@code CONFIRMED}·{@code UNAVAILABLE}은 종단이다. 계산 버전은 입력이 완전했던 판정에만 함께 온다.
     *
     * @return 이번에 바꿨는가
     */
    boolean recordCompletionComparison(UUID userId, UUID planId, String comparison, @Nullable String reason,
            @Nullable UUID completionCalculationId, @Nullable Integer postEndCashflowCount);

    /**
     * 그 사용자의 완료된 계획 {@code planId}의 완료 기록. 없으면 빈 값이다. {@code forUpdate}면 호출자 트랜잭션이
     * 끝날 때까지 그 행을 잠근다.
     */
    Optional<PlanCompletion> completion(UUID userId, UUID planId, boolean forUpdate);

    /**
     * 그 사용자의 계획 조건 쓰기와 직렬화한다. 호출자 트랜잭션이 끝날 때까지 유지된다.
     *
     * <p>발행 트랜잭션이 <b>가장 먼저</b> 부른다. 계획 조건 확인과 같은 잠금 순서(계획 → 계정 생명주기)
     * 여야 교착이 생기지 않고, 발행이 커밋되기 전에 시작된 조건 확인이 활성 계획을 놓치지 않는다.
     */
    void lockPlanWrites(UUID userId);

    /**
     * 불변 활성 계획을 만들고 intent를 {@code CONSUMED}로 바꾼다. 호출자 트랜잭션 전용이다.
     *
     * <p>intent가 더 이상 OPEN이 아니거나, 조건이 {@code expected}와 다르거나, 데이터베이스 시각이
     * 이미 {@code T}에 닿았거나, 그 portfolio에 활성 계획이 이미 있으면 아무것도 쓰지 않고 빈 값이다.
     * 경합에서 늦은 INSERT는 조건부 유일 색인에서 예외로 끝나고 그 예외가 호출자의 트랜잭션을 되돌린다.
     */
    Optional<UUID> createActivePlan(UUID userId, Terms expected);
}
