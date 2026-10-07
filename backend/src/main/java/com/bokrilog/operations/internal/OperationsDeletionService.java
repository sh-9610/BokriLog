package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperationsDeletionApi;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * operations의 deletion port(Story 3.1 AC-8). 분류와 근거는 {@link OperationsDeletionApi}가 소유한다.
 *
 * <p>모든 역할에서 배선한다 — 계정 삭제 orchestration(Epic 5)이 어느 프로세스에서 돌든 이 port가 있어야 한다. digest
 * 키가 필요 없다: 비식별은 이미 있는 digest를 남기고 귀속만 비운다.
 */
@Service
class OperationsDeletionService implements OperationsDeletionApi {

    private final OperatorAuditRepository repository;

    OperationsDeletionService(OperatorAuditRepository repository) {
        this.repository = repository;
    }

    @Override
    public int eraseActor(@Nullable UUID userId) {
        return userId == null ? 0 : this.repository.eraseActor(userId);
    }
}
