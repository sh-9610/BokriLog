package com.bokrilog;

import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.identity.DeletionOutcome;
import com.bokrilog.identity.DeletionStep;
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
 * 계정 하나의 삭제 orchestrator(Story 3.10 AC-4, Story 5.2).
 *
 * <p><b>단계 목록은 하나다({@link DeletionStep}).</b> 각 단계가 무엇을 지우는지({@link #apply})와 그 단계의 잔여 행 수({@link #residueOf})가
 * 이 클래스 한 곳에 있고, 두 모드가 같은 목록을 돈다(삭제 참여자 나열이 실행과 잔여 검증으로 갈라지지 않게 — deferred 67 일부).
 * <ol>
 *   <li>{@link DeletionStep#CREDENTIALS} — 소셜 로그인 연결과 Bybit 자격정보.
 *   <li>{@link DeletionStep#PORTFOLIO} → {@link DeletionStep#SYNC} → {@link DeletionStep#PLAN} → {@link DeletionStep#SOURCE}(외래키가
 *       {@code ON DELETE RESTRICT}라 이 순서다).
 *   <li>{@link DeletionStep#OPERATIONS} — 감사 수행자·제한 target 비식별, 문의 삭제.
 *   <li>{@link DeletionStep#ENGAGEMENT} — 광고 동의 결정·광고 자격 결정 삭제.
 *   <li>{@link DeletionStep#IDENTITY} — tombstone 집합 닫기·보존 연장(단일 모드는 기록도), payload·인증 매핑·세션·요청 행 삭제와
 *       {@code DELETED} 전환. 마지막이다.
 * </ol>
 *
 * <p><b>단일 모드({@link #erase}·{@link #reapply}).</b> 삭제 전환({@code DELETION_IN_PROGRESS})부터 마지막 단계까지 한 트랜잭션이다 —
 * 하나라도 실패하면 아무것도 바뀌지 않는다. 검증용 삭제 표본 역할({@code deletion-sample})과 복원 적용({@link RestoreApplication})이
 * 부른다 — 복원 적용은 실패하면 전체를 되돌려야 안전하다.
 *
 * <p><b>단계 모드({@link AccountErasureRun}).</b> 사용자의 삭제 요청을 Worker가 집어 단계마다 따로 커밋하고 단계 상태를 identity에 남긴다.
 * 그 실행자는 이 클래스의 {@link #apply}·{@link #residueOf}·{@link #recordRequestedTombstone}·{@link #closeRequestedTombstone}만
 * 부른다. tombstone은 첫 단계 앞에서 기록되고(Story 5.3) 마지막 단계가 영향 generation 집합을 닫는다.
 *
 * <p><b>operations 단계는 감사 원장 추가 잠금을 쥔다(Epic 3 회고 B-2).</b> 수행자·제한 target 비식별이 그 잠금을 그 트랜잭션의 커밋까지
 * 쥐므로, 같은 잠금 아래에서 사용자 귀속 제한을 쓰는 경로(제한 생성 command·강한 신호·해제 smoke 되돌림)와 한 줄로 선다 — 먼저 쥔
 * 쓰기가 커밋한 행은 비식별이 보고, 뒤에 깨어난 신호는 이 삭제를 보고 자기 귀속을 비식별한다. 잠금을 sync 단계보다 앞당기지 않는다 —
 * 신호 {@code raise}는 sync 행 잠금을 쥔 감지 트랜잭션이 애플리케이션에서 기다리는 별도 트랜잭션이라, 단일 모드의 삭제가 sync erase
 * 전에 원장 잠금을 쥐면 "삭제 → sync 행 → 감지가 raise를 기다림 → raise가 원장 잠금"의 PostgreSQL이 모르는 교착이 된다. 단계 모드는
 * 단계마다 커밋하므로 operations 단계가 sync 행 잠금을 쥐고 있지 않다. 운영자가 자기 계정의 삭제와 동시에 command를 확정하면
 * PostgreSQL이 교착(40P01, 허가 행 {@code action_grant}과 원장 잠금)을 감지해 한쪽을 통째로 롤백할 수 있다 — 삭제는 멱등이라 다시 돌리면
 * 된다.
 *
 * <p>HTTP 표면이 없다.
 */
@Component
class AccountErasure {

    /** 단일 모드 삭제의 결과. */
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
     * 새 삭제 하나(단일 모드). tombstone의 요청 시각은 이 트랜잭션의 DB 시각이다. 멱등이다 — 같은 계정을 다시 지우면 모듈 삭제는 0행이고
     * tombstone은 이미 있는 행을 그대로 둔다.
     *
     * @throws IllegalArgumentException 요청 ID의 모양이 맞지 않는다
     * @throws IllegalStateException 이 역할에 tombstone 키가 없거나 어느 단계가 실패했다(전부 롤백)
     */
    Outcome erase(UUID userId, String requestId) {
        return run(userId, () -> {
            this.tombstones.record(userId, requestId);
            // 단일 모드는 이 트랜잭션이 곧 운영 데이터 삭제 완료다 — 영향 generation 집합을 닫고 보존을 늘린다(Story 5.3).
            this.tombstones.close(requestId);
        });
    }

    /**
     * 외부 tombstone이 가리키는 계정을 다시 지운다(복원 적용, 단일 모드). tombstone은 사본의 시각 그대로 이 DB의 outbox로 옮긴다 — 복원된
     * DB가 다음 내보내기에서 그 tombstone을 잃지 않게 한다.
     */
    Outcome reapply(UUID userId, DeletionTombstoneApi.Tombstone tombstone) {
        return run(userId, () -> {
            this.tombstones.carry(tombstone);
            // 이 DB에서는 지금 지웠다 — 열린 집합이면 지금 닫는다(이미 닫힌 사본은 그대로다).
            this.tombstones.close(tombstone.requestId());
        });
    }

    private Outcome run(UUID userId, Runnable recordTombstone) {
        Outcome outcome = this.transaction.execute(status -> {
            // 단계 모드 실행 행이 있으면 먼저 잠근다 — Worker 단계(실행 행 → 생명주기)와 같은 잠금 순서다(Story 5.2).
            this.accounts.lockRunIfPresent(userId);
            if (this.accounts.beginDeletion(userId) != DeletionOutcome.COMPLETED) {
                return Outcome.NOT_FOUND;
            }
            for (DeletionStep step : DeletionStep.values()) {
                apply(step, userId, recordTombstone);
            }
            // 사용자 요청의 단계 실행(Story 5.2)이 있었으면 이 커밋으로 마감한다 — QUEUED·RETRY_NEEDED로 남지 않는다.
            this.accounts.finishRunBySingleErasure(userId);
            return Outcome.ERASED;
        });
        if (outcome == null) {
            throw new IllegalStateException("계정 삭제가 결과 없이 끝났다.");
        }
        return outcome;
    }

    /**
     * 단계 하나의 삭제. <b>호출자 트랜잭션 안에서</b> 부른다(단일 모드는 한 트랜잭션, 단계 모드는 단계마다 한 트랜잭션). 모든 port가 그 사용자에
     * 멱등이라 성공한 단계를 다시 불러도 안전하다.
     *
     * @param recordTombstone {@link DeletionStep#IDENTITY}에서 payload 삭제 앞에 부를 tombstone 기록·닫기. 다른 단계는 부르지 않는다
     * @throws IllegalStateException 마지막 단계의 payload 삭제가 적용되지 않았다 — 부분 삭제를 완료로 보고하지 않는다
     */
    void apply(DeletionStep step, UUID userId, Runnable recordTombstone) {
        switch (step) {
            case CREDENTIALS -> {
                this.accounts.eraseSocialLinks(userId);
                this.sources.eraseCredentials(userId);
            }
            case PORTFOLIO -> this.portfolios.erasePortfolioData(userId);
            case SYNC -> this.syncs.eraseSyncData(userId);
            case PLAN -> this.plans.erasePlanData(userId);
            case SOURCE -> this.sources.eraseSourceData(userId);
            case OPERATIONS -> {
                this.operations.eraseActor(userId);
                this.operations.eraseRestrictionTargets(userId);
                this.operations.eraseInquiries(userId);
            }
            case ENGAGEMENT -> {
                this.engagement.eraseConsents(userId);
                this.engagement.eraseAdAttempts(userId);
            }
            case IDENTITY -> {
                recordTombstone.run();
                if (this.accounts.erasePayload(userId) != DeletionOutcome.COMPLETED) {
                    // 전환 뒤인데 payload 삭제가 적용되지 않았다. 부분 삭제를 완료로 보고하지 않는다 — 그 트랜잭션을 되돌린다.
                    throw new IllegalStateException("계정 payload 삭제가 적용되지 않았다.");
                }
            }
        }
    }

    /**
     * 그 단계가 지울 행 가운데 아직 그 사용자에게 남은 수(각 모듈의 {@code remainingRowsOf}). 단계 성공의 조건은 같은 트랜잭션에서 이 값이
     * 0인 것이고, 마지막 단계는 앞 단계 전부의 이 값을 다시 센다 — operations·engagement 표는 identity 외래키가 없어 잔여 판독만이 늦은
     * 쓰기를 잡는다.
     */
    long residueOf(DeletionStep step, UUID userId) {
        return switch (step) {
            case CREDENTIALS -> this.accounts.remainingSocialLinksOf(userId)
                    + this.sources.remainingCredentialsOf(userId);
            case PORTFOLIO -> this.portfolios.remainingRowsOf(userId);
            case SYNC -> this.syncs.remainingRowsOf(userId);
            case PLAN -> this.plans.remainingRowsOf(userId);
            case SOURCE -> this.sources.remainingRowsOf(userId);
            case OPERATIONS -> this.operations.remainingRowsOf(userId);
            case ENGAGEMENT -> this.engagement.remainingRowsOf(userId);
            case IDENTITY -> this.accounts.remainingRowsOf(userId);
        };
    }

    /**
     * 단계 모드의 tombstone 기록(Story 5.3): 그 사용자의 삭제 요청(Story 5.1)의 요청 ID와 요청 시각으로 남긴다. 실행자가 첫 단계 앞에서 그
     * 단계와 같은 fencing 아래 자기 트랜잭션으로 부른다 — 첫 claim에 tombstone이 생겨 운영 완료 전 복원도 그 계정을 다시 지운다(deferred
     * 171). 멱등이다(재시도·lease 만료 복구가 다시 불러도 한 행이다).
     *
     * @throws IllegalStateException 삭제 요청이 없다
     */
    void recordRequestedTombstone(UUID userId) {
        var request = this.accounts.deletionRequestOf(userId)
                .orElseThrow(() -> new IllegalStateException("삭제 요청이 없는 계정의 단계 삭제다."));
        this.tombstones.record(userId, request.requestId(), request.requestedAt());
    }

    /**
     * 단계 모드의 마지막 단계: 영향 generation 집합을 닫고 보존을 {@code max(요청, 지금) + 35일}로 늘린다(Story 5.3). 마지막 단계 트랜잭션
     * 안에서 payload 삭제(요청 행도 지운다) 앞에 부른다. 첫 단계의 기록이 없던 실행(이 스토리 전에 시작한 실행)도 있으므로 먼저 기록을 다시
     * 해 본다(멱등).
     *
     * @throws IllegalStateException 삭제 요청이 없다
     */
    void closeRequestedTombstone(UUID userId) {
        var request = this.accounts.deletionRequestOf(userId)
                .orElseThrow(() -> new IllegalStateException("삭제 요청이 없는 계정의 단계 삭제다."));
        this.tombstones.record(userId, request.requestId(), request.requestedAt());
        this.tombstones.close(request.requestId());
    }
}
