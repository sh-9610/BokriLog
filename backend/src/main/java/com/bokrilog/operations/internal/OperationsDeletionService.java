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

    /** 강한 신호 기록(Story 3.3). 그 사용자 귀속({@code target_user_id})도 함께 비운다. */
    private final SignalRepository signals;

    private final ObjectProvider<ActorDigest> digest;

    /** 사용자 문의(Story 3.6). 삭제의 답은 행 삭제다. */
    private final SupportInquiryRepository inquiries;

    private final TransactionTemplate transactions;

    /** 삭제된 계정의 잔여 행 세기(Story 3.10). */
    private final DeletionResidueRepository residue;

    OperationsDeletionService(OperatorAuditRepository audit, OperationalRestrictionRepository restrictions,
            SignalRepository signals, ObjectProvider<ActorDigest> digest, SupportInquiryRepository inquiries,
            DeletionResidueRepository residue, PlatformTransactionManager transactionManager) {
        this.audit = audit;
        this.residue = residue;
        this.inquiries = inquiries;
        this.restrictions = restrictions;
        this.signals = signals;
        this.digest = digest;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 원장 잠금 아래에서 비식별한다(Epic 3 회고 B-2). 잠금이 없으면 원장 잠금을 쥔 다른 트랜잭션(운영 command·신호)이 아직
     * 커밋하지 않은 이 사용자의 행을 이 읽기가 보지 못하고 지나간다. 계정 삭제({@code AccountErasure})의 바깥 트랜잭션에
     * 참여하면 잠금은 그 커밋까지 간다.
     *
     * <p>덮는 것은 이 삭제보다 먼저 잠금을 쥔 쓰기뿐이다. 삭제가 커밋된 뒤 롤백·별도 트랜잭션으로 append되는 운영자
     * 거절·실패 행은 삭제된 수행자의 {@code actor_user_id}를 다시 실을 수 있다 — 열린 결정이다({@code deferred-work.md}).
     */
    @Override
    public int eraseActor(@Nullable UUID userId) {
        if (userId == null) {
            return 0;
        }
        Integer erased = this.transactions.execute(status -> {
            this.audit.lockAppends();
            return this.audit.eraseActor(userId);
        });
        return erased == null ? 0 : erased;
    }

    /** 원장 잠금 아래에서 비식별한다({@link #eraseActor}와 같은 근거). */
    @Override
    public int eraseRestrictionTargets(@Nullable UUID userId) {
        if (userId == null) {
            return 0;
        }
        Integer erased = this.transactions.execute(status -> {
            this.audit.lockAppends();
            return eraseAttributed(userId);
        });
        return erased == null ? 0 : erased;
    }

    /**
     * 그 사용자에게 귀속된 신호 행·제한 target을 비식별한다. 호출자가 {@link OperatorAuditRepository#lockAppends}를 쥔
     * 트랜잭션 안에서 부른다 — 계정 삭제 port와, 삭제가 이미 시작된 사용자에게 귀속을 쓴 강한 신호({@code
     * OperationalSignalService})가 이 본문 하나를 쓴다.
     *
     * @return 이번에 비식별한 행 수(제한 행과 신호 행의 합)
     * @throws IllegalStateException 비식별할 제한 행이 있는데 이 역할에 digest 키가 없다
     */
    int eraseAttributed(UUID userId) {
        // 신호 행의 귀속은 digest 키 없이 비운다 — target 원문이 없고 fingerprint는 처음부터 keyed digest의 hash다.
        int signalRows = this.signals.eraseTargetUser(userId);
        var rows = this.restrictions.attributedTo(userId);
        if (rows.isEmpty()) {
            return signalRows;
        }
        ActorDigest keyed = this.digest.getIfAvailable();
        if (keyed == null) {
            throw new IllegalStateException("제한 target을 비식별할 digest 키가 이 역할에 없다.");
        }
        int count = signalRows;
        for (var row : rows) {
            count += this.restrictions.eraseTarget(row.restrictionId(), userId,
                    keyed.ofTarget(row.scope(), row.target()));
        }
        return count;
    }

    @Override
    public int eraseInquiries(@Nullable UUID userId) {
        if (userId == null) {
            return 0;
        }
        // 접수와 같은 사용자 잠금 아래에서 지운다 — 도는 중인 접수 트랜잭션과 줄을 서고 그 중간을 지우지 않는다.
        Integer erased = this.transactions.execute(status -> {
            this.inquiries.lockUser(userId);
            return this.inquiries.eraseUser(userId);
        });
        return erased == null ? 0 : erased;
    }

    @Override
    public long remainingRowsOf(UUID userId) {
        return this.residue.remainingRowsOf(userId);
    }
}
