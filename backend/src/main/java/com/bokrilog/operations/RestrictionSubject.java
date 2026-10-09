package com.bokrilog.operations;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * 제한 판독 지점이 넘기는 대상 차원(Story 3.2).
 *
 * <p><b>그 지점이 가진 식별자만 넘긴다.</b> 넘기지 않은 차원의 구체 제한은 그 지점에 적용되지 않는다 — 예를 들어 첫 스냅샷에는
 * 계획이 없으므로 계획 제한이 적용되지 않는다. "모름"을 전부 차단으로 접으면 계획 하나의 제한이 모든 온보딩을 막는다.
 * 전역 제한은 어떤 대상에도 적용된다.
 *
 * @param planId 계획
 * @param portfolioId portfolio ID
 * @param connectionId 제공자 연결
 * @param calculationPolicy 계산 정책 버전(예: {@code CALC-1.0})
 */
public record RestrictionSubject(@Nullable UUID planId, @Nullable UUID portfolioId, @Nullable UUID connectionId,
        @Nullable String calculationPolicy) {

    private static final RestrictionSubject NONE = new RestrictionSubject(null, null, null, null);

    /** 대상 차원이 없다. 전역 제한만 적용된다. */
    public static RestrictionSubject none() {
        return NONE;
    }

    public RestrictionSubject withPlan(@Nullable UUID plan) {
        return new RestrictionSubject(plan, this.portfolioId, this.connectionId, this.calculationPolicy);
    }

    public RestrictionSubject withPortfolio(@Nullable UUID portfolio) {
        return new RestrictionSubject(this.planId, portfolio, this.connectionId, this.calculationPolicy);
    }

    public RestrictionSubject withConnection(@Nullable UUID connection) {
        return new RestrictionSubject(this.planId, this.portfolioId, connection, this.calculationPolicy);
    }

    public RestrictionSubject withCalculationPolicy(@Nullable String policy) {
        return new RestrictionSubject(this.planId, this.portfolioId, this.connectionId, policy);
    }
}
