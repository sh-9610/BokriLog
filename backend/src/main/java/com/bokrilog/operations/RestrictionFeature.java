package com.bokrilog.operations;

import java.util.EnumSet;
import java.util.Set;

/**
 * 운영 제한이 막는 기능(Story 3.2, NFR-17). 넷은 서로 독립이다 — 한 기능의 제한이 다른 기능을 막지 않는다.
 *
 * <p>각 기능이 받는 scope는 닫힌 조합이다({@link #scopes()}). 신규 연결과 광고는 전역뿐이고(연결 하나·계획 하나에 귀속되지
 * 않는다), 동기화는 계획·portfolio·연결 단위로, 계산 표시는 계획·portfolio·계산 정책 버전 단위로 좁힐 수 있다. 조합 밖의
 * 요청은 {@code UNSUPPORTED_SCOPE}다.
 */
public enum RestrictionFeature {
    /** 새 Bybit 연결의 시작·저장. 이미 있는 연결의 동기화·대시보드는 막지 않는다. */
    NEW_CONNECTION(EnumSet.of(RestrictionScope.GLOBAL)),
    /** 첫 스냅샷·초기 수집·계획 조건·발행·증분 동기화·계획 종료. */
    SYNC(EnumSet.of(RestrictionScope.GLOBAL, RestrictionScope.PLAN, RestrictionScope.PORTFOLIO,
            RestrictionScope.PROVIDER_CONNECTION)),
    /** 공식 계산 결과의 표시(대시보드 결과·근거·조정 이력)와 새 계산의 발행. 기록·포인터는 바꾸지 않는다. */
    CALCULATION_DISPLAY(EnumSet.of(RestrictionScope.GLOBAL, RestrictionScope.PLAN, RestrictionScope.PORTFOLIO,
            RestrictionScope.CALCULATION_POLICY_VERSION)),
    /** 광고. 소비자는 Epic 4이며 지금은 제한 행과 감사만 생긴다. */
    ADVERTISING(EnumSet.of(RestrictionScope.GLOBAL));

    private final Set<RestrictionScope> scopes;

    RestrictionFeature(Set<RestrictionScope> scopes) {
        this.scopes = Set.copyOf(scopes);
    }

    /** 이 기능이 받는 scope. */
    public Set<RestrictionScope> scopes() {
        return this.scopes;
    }

    /** 이 기능을 이 scope로 제한할 수 있는가. */
    public boolean admits(RestrictionScope scope) {
        return this.scopes.contains(scope);
    }
}
