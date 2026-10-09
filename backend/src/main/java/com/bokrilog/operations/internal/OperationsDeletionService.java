package com.bokrilog.operations.internal;

import com.bokrilog.operations.OperationsDeletionApi;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * operations의 deletion port(Story 3.1 AC-8, Story 3.2 AC-6). 분류와 근거는 {@link OperationsDeletionApi}가 소유한다.
 *
 * <p>모든 역할에서 배선한다 — 계정 삭제 orchestration(Epic 5)이 어느 프로세스에서 돌든 이 port가 있어야 한다. actor 비식별은
 * digest 키가 필요 없다(이미 있는 digest를 남기고 귀속만 비운다). 제한 target 비식별은 target을 digest로 바꾸므로 키가
 * 필요하다 — 키가 있는 역할(API)에서만 실제로 일하고, 키가 없는 역할에서 비식별할 행이 있으면 실패한다(조용히 건너뛰면
 * 삭제된 사용자의 원식별자가 남는다).
 */
@Service
class OperationsDeletionService implements OperationsDeletionApi {

    private final OperatorAuditRepository audit;

    private final OperationalRestrictionRepository restrictions;

    private final ObjectProvider<ActorDigest> digest;

    private final TransactionTemplate transactions;

    OperationsDeletionService(OperatorAuditRepository audit, OperationalRestrictionRepository restrictions,
            ObjectProvider<ActorDigest> digest, PlatformTransactionManager transactionManager) {
        this.audit = audit;
        this.restrictions = restrictions;
        this.digest = digest;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public int eraseActor(@Nullable UUID userId) {
        return userId == null ? 0 : this.audit.eraseActor(userId);
    }

    @Override
    public int eraseRestrictionTargets(@Nullable UUID userId) {
        if (userId == null) {
            return 0;
        }
        Integer erased = this.transactions.execute(status -> {
            var rows = this.restrictions.attributedTo(userId);
            if (rows.isEmpty()) {
                return 0;
            }
            ActorDigest keyed = this.digest.getIfAvailable();
            if (keyed == null) {
                throw new IllegalStateException("제한 target을 비식별할 digest 키가 이 역할에 없다.");
            }
            int count = 0;
            for (var row : rows) {
                count += this.restrictions.eraseTarget(row.restrictionId(), userId,
                        keyed.ofTarget(row.scope(), row.target()));
            }
            return count;
        });
        return erased == null ? 0 : erased;
    }
}
