package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.EngagementDeletionApi;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link EngagementDeletionApi}의 구현(Story 4.1·4.2). 동의 결정 표와 자격 결정 표의 삭제와 잔여 행 세기를 한 자리에 둔다.
 *
 * <p><b>삭제는 사용자 잠금을 쥐지 않는다.</b> 계정 삭제 orchestrator는 생명주기 행을 먼저 잠근 트랜잭션에서 부르므로, 여기서 동의·
 * 자격 판정의 사용자 잠금을 다시 잡으면 "사용자 잠금 → 생명주기"를 기다리는 결정 트랜잭션과 서로를 기다린다. 잠그지 않아도 행이 다시
 * 생기지 않는다 — 결정 트랜잭션은 생명주기 잠금을 얻은 뒤에야 쓰고, 그때는 삭제 전환이 커밋돼 쓰기가 막혀 있다. 반대로 결정이 먼저
 * 생명주기를 쥐었으면 삭제가 그 커밋을 기다린 뒤 그 행까지 지운다.
 */
@Service
class EngagementDeletionService implements EngagementDeletionApi {

    private final AdConsentRepository consents;

    private final AdAttemptRepository attempts;

    private final EngagementResidueRepository residue;

    private final TransactionTemplate transactions;

    EngagementDeletionService(AdConsentRepository consents, AdAttemptRepository attempts,
            EngagementResidueRepository residue, PlatformTransactionManager transactionManager) {
        this.consents = consents;
        this.attempts = attempts;
        this.residue = residue;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public int eraseConsents(@Nullable UUID userId) {
        if (userId == null) {
            return 0;
        }
        Integer erased = this.transactions.execute(status -> this.consents.eraseUser(userId));
        return erased == null ? 0 : erased;
    }

    @Override
    public int eraseAdAttempts(@Nullable UUID userId) {
        if (userId == null) {
            return 0;
        }
        Integer erased = this.transactions.execute(status -> this.attempts.eraseUser(userId));
        return erased == null ? 0 : erased;
    }

    @Override
    public long remainingRowsOf(UUID userId) {
        return this.residue.remainingRowsOf(userId);
    }
}
