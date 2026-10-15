package com.bokrilog;

import com.bokrilog.operations.RestrictionConnectionScope;
import com.bokrilog.source.SourceConnectionApi;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 연결 단위 제한이 미치는 연결 집합({@link RestrictionConnectionScope})의 루트 구현(Story 2.17).
 *
 * <p>같은 사용자·같은 provider account의 연결 전부다 — source 공개 API가 답한다. 이전 연결 ID에 건 열린 제한이 같은 계정으로
 * 다시 연결한 뒤의 연결에도 적용되고, 다른 계정의 연결에는 적용되지 않는다. 판독은 모든 역할에서 돌므로 웹 조건 없이
 * 배선한다.
 */
@Component
class ConnectionRestrictionScope implements RestrictionConnectionScope {

    private final SourceConnectionApi sources;

    ConnectionRestrictionScope(SourceConnectionApi sources) {
        this.sources = sources;
    }

    @Override
    public Set<UUID> sameAccountConnections(UUID connectionId) {
        Set<UUID> connections = new HashSet<>(this.sources.sameAccountConnections(connectionId));
        // 모르는 연결이어도 그 연결 자신의 제한은 그대로 대조한다(이전과 같은 판정).
        connections.add(connectionId);
        return Set.copyOf(connections);
    }
}
