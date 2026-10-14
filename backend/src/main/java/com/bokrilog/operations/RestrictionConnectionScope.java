package com.bokrilog.operations;

import java.util.Set;
import java.util.UUID;

/**
 * 연결 단위 제한({@code PROVIDER_CONNECTION})이 미치는 연결 집합(Story 2.17). <b>operations가 소유하고 루트가 구현한다.</b>
 *
 * <p>연결 단위 제한은 연결 ID 하나가 아니라 그 연결의 Bybit 계정(provider account)을 따른다 — 해제 → 같은 계정 재연결로
 * 운영자 해제 없이 제한을 풀 수 없게 하려는 것이다. 판독 술어({@code OperationalRestrictionApi#evaluate}·{@code matching}과
 * 공지의 복구 대상 판정)는 넘겨받은 연결 ID를 이 집합으로 넓혀 대조한다. 다른 계정의 연결은 집합에 들지 않는다.
 *
 * <p>operations는 연결 표를 모른다(모듈 경계). 그래서 그 사실은 source 공개 API를 아는 루트가 답한다.
 */
public interface RestrictionConnectionScope {

    /**
     * 그 연결과 같은 사용자·같은 provider account의 연결 전부(해제된 연결 포함, 자기 자신 포함).
     *
     * @return 모르는 연결이면 그 연결 하나만 든 집합
     */
    Set<UUID> sameAccountConnections(UUID connectionId);
}
