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
