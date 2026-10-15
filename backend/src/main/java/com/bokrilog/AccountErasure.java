package com.bokrilog;

import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.identity.DeletionOutcome;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.SyncJobApi;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정 하나의 삭제를 한 트랜잭션으로 묶는 orchestrator(Story 3.10 AC-4).
 *
 * <p>순서는 다음과 같고 전부 한 커밋이다 — 하나라도 실패하면 아무것도 바뀌지 않는다(상태 전진·tombstone·모듈 삭제 모두 롤백).
 * <ol>
 *   <li>{@code identity} 삭제 전환({@code DELETION_IN_PROGRESS}) — 그 순간부터 새 세션·쓰기가 막힌다.
 *   <li>tombstone outbox 행({@link DeletionTombstoneApi}) — 기록 없는 탈퇴가 없다.
 *   <li>모듈 erase port: portfolio → sync → plan → source(외래키가 {@code ON DELETE RESTRICT}라 이 순서다) → operations
 *       (감사 수행자·제한 target 비식별, 문의 삭제) → engagement(광고 동의 결정·광고 자격 결정 삭제).
 *   <li>{@code identity} payload·인증 매핑·세션 삭제와 {@code DELETED} 전환 — 마지막이다.
 * </ol>
 *
 * <p><b>사용자 경로가 없다.</b> 실제 계정 삭제 요청(재인증·고지·법정 보존)은 Epic 5가 소유한다. 지금 이 클래스를 부르는 것은 검증용
 * 일회성 역할({@code deletion-sample})과 복원 적용({@link RestoreApplication})뿐이다. HTTP 표면이 없다.
 */
@Component
class AccountErasure {

    /** 삭제의 결과. */
    enum Outcome {
        /** 그 계정을 지웠다(이미 지워진 계정의 재시도도 같다). */
        ERASED,
        /** 그 계정이 없다. 아무것도 쓰지 않았다. */
        NOT_FOUND
    }

    private final AccountDeletionApi accounts;

    private final DeletionTombstoneApi tombstones;

    private final PortfolioMembershipApi portfolios;

    private final SyncJobApi syncs;

    private final PlanConditionApi plans;

    private final SourceConnectionApi sources;

    private final OperationsDeletionApi operations;

    private final EngagementDeletionApi engagement;

    private final TransactionTemplate transaction;

    AccountErasure(AccountDeletionApi accounts, DeletionTombstoneApi tombstones, PortfolioMembershipApi portfolios,
            SyncJobApi syncs, PlanConditionApi plans, SourceConnectionApi sources, OperationsDeletionApi operations,
            EngagementDeletionApi engagement, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.tombstones = tombstones;
        this.portfolios = portfolios;
        this.syncs = syncs;
        this.plans = plans;
        this.sources = sources;
        this.operations = operations;
        this.engagement = engagement;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 새 삭제 요청 하나. tombstone의 요청 시각은 이 트랜잭션의 DB 시각이다. 멱등이다 — 같은 계정을 다시 지우면 모듈 삭제는 0행이고
     * tombstone은 이미 있는 행을 그대로 둔다.
     *
     * @throws IllegalArgumentException 요청 ID의 모양이 맞지 않는다
     * @throws IllegalStateException 이 역할에 tombstone 키가 없거나 어느 단계가 실패했다(전부 롤백)
     */
    Outcome erase(UUID userId, String requestId) {
        return run(userId, () -> this.tombstones.record(userId, requestId));
    }

    /**
     * 외부 tombstone이 가리키는 계정을 다시 지운다(복원 적용). tombstone은 사본의 시각 그대로 이 DB의 outbox로 옮긴다 — 복원된 DB가 다음
     * 내보내기에서 그 tombstone을 잃지 않게 한다.
     */
    Outcome reapply(UUID userId, DeletionTombstoneApi.Tombstone tombstone) {
        return run(userId, () -> this.tombstones.carry(tombstone));
    }

    private Outcome run(UUID userId, Runnable recordTombstone) {
        Outcome outcome = this.transaction.execute(status -> {
            if (this.accounts.beginDeletion(userId) != DeletionOutcome.COMPLETED) {
                return Outcome.NOT_FOUND;
            }
            recordTombstone.run();
            this.portfolios.erasePortfolioData(userId);
            this.syncs.eraseSyncData(userId);
            this.plans.erasePlanData(userId);
            this.sources.eraseSourceData(userId);
            this.operations.eraseActor(userId);
            this.operations.eraseRestrictionTargets(userId);
            this.operations.eraseInquiries(userId);
            this.engagement.eraseConsents(userId);
            this.engagement.eraseAdAttempts(userId);
            if (this.accounts.erasePayload(userId) != DeletionOutcome.COMPLETED) {
                // 전환 뒤인데 payload 삭제가 적용되지 않았다. 부분 삭제를 완료로 보고하지 않는다 — 전부 되돌린다.
                throw new IllegalStateException("계정 payload 삭제가 적용되지 않았다.");
            }
            return Outcome.ERASED;
        });
        if (outcome == null) {
            throw new IllegalStateException("계정 삭제가 결과 없이 끝났다.");
        }
        return outcome;
    }
}
