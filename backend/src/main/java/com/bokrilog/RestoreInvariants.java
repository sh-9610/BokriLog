package com.bokrilog;

import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.SyncJobApi;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 복원된 DB의 불변식(Story 3.10 AC-2·AC-4). 서비스를 열기 전에 모두 0이어야 한다. 판정은 각 모듈의 공개 API가 자기 표로 한다 — 이
 * 클래스는 그 수를 모을 뿐 다른 모듈의 표에 닿지 않는다(루트가 소유한 {@code action_grant}만 직접 센다).
 *
 * <ul>
 *   <li>활성 계획 유일 — {@link PlanConditionApi#activePlanViolations}
 *   <li>공식 포인터 → 계산 버전 → portfolio snapshot 귀속 — {@link SyncJobApi#officialBundleViolations}(대시보드 묶음과 같은 술어)
 *   <li>portfolio 현재 포인터 — {@link PortfolioMembershipApi#currentPointerViolations}
 *   <li>요청 없이 끝나지 않은 삭제 없음 — {@link AccountDeletionApi#deletionInProgressAccounts} 가운데 사용자의 삭제 요청
 *       ({@link AccountDeletionApi#deletionRequestOf}, Story 5.1)이 없는 것. 요청이 있는 {@code DELETION_IN_PROGRESS}는 모듈 삭제를
 *       기다리는 정상 대기 상태다
 *   <li>요청했는데 막히지 않은 계정 없음 — 삭제 요청 행이 있는데 생명주기가 {@code ACTIVE}인 계정(위와 같은 수에 센다)
 *   <li>삭제가 끝난 계정의 잔여 행 0 — 모듈마다 {@code remainingRowsOf}
 * </ul>
 */
@Component
class RestoreInvariants {

    /**
     * 불변식 위반의 수. 각 값이 0이어야 통과다.
     *
     * @param activePlans 활성 계획이 둘 이상인 portfolio·사용자
     * @param officialBundles 공식 포인터 귀속을 어긴 사용자
     * @param portfolioPointers 현재 포인터를 어긴 portfolio
     * @param unfinishedDeletions 외부 tombstone이 다시 지우지 않았고 사용자의 삭제 요청(Story 5.1)도 없는 {@code DELETION_IN_PROGRESS}
     *     계정(요청 없는 삭제는 한 트랜잭션이라 정상이면 없다), 그리고 삭제 요청 행이 있는데 {@code ACTIVE}인 계정
     * @param residualRows 삭제가 끝난 계정에 남은 행
     */
    record Violations(long activePlans, long officialBundles, long portfolioPointers, long unfinishedDeletions,
            long residualRows) {

        long total() {
            return this.activePlans + this.officialBundles + this.portfolioPointers + this.unfinishedDeletions
                    + this.residualRows;
        }
    }

    private final AccountDeletionApi accounts;

    private final PlanConditionApi plans;

    private final SyncJobApi syncs;

    private final PortfolioMembershipApi portfolios;

    private final SourceConnectionApi sources;

    private final OperationsDeletionApi operations;

    private final EngagementDeletionApi engagement;

    private final JdbcClient jdbc;

    RestoreInvariants(AccountDeletionApi accounts, PlanConditionApi plans, SyncJobApi syncs,
            PortfolioMembershipApi portfolios, SourceConnectionApi sources, OperationsDeletionApi operations,
            EngagementDeletionApi engagement, JdbcClient jdbc) {
        this.accounts = accounts;
        this.plans = plans;
        this.syncs = syncs;
        this.portfolios = portfolios;
        this.sources = sources;
        this.operations = operations;
        this.engagement = engagement;
        this.jdbc = jdbc;
    }

    /** 지금 DB의 불변식을 센다. 읽기만 한다. */
    Violations check() {
        List<UUID> all = this.accounts.accounts();
        long residual = 0;
        for (UUID deleted : this.accounts.deletedAccounts()) {
            residual += this.accounts.remainingRowsOf(deleted) + this.portfolios.remainingRowsOf(deleted)
                    + this.syncs.remainingRowsOf(deleted) + this.plans.remainingRowsOf(deleted)
                    + this.sources.remainingRowsOf(deleted) + this.operations.remainingRowsOf(deleted)
                    + this.engagement.remainingRowsOf(deleted) + actionGrantsOf(deleted);
        }
        return new Violations(this.plans.activePlanViolations(), this.syncs.officialBundleViolations(all),
                this.portfolios.currentPointerViolations(), unrequestedDeletions(), residual);
    }

    /**
     * 끝나지 않은 삭제의 위반 수 — 둘을 함께 센다.
     *
     * <ul>
     *   <li>요청 행 없는 삭제 진행 계정. 5.1부터 요청 행이 있는 {@code DELETION_IN_PROGRESS}는 모듈 삭제(5.2)를 기다리는 정상 대기
     *       상태이므로 위반이 아니다.
     *   <li>생명주기가 {@code ACTIVE}인데 삭제 요청 행이 있는 계정. 요청과 삭제 진행 전환은 한 트랜잭션이라 정상이면 없다 — 있으면 그
     *       계정은 요청했는데 접근이 막히지 않은 상태다.
     * </ul>
     */
    private long unrequestedDeletions() {
        List<UUID> inProgress = this.accounts.deletionInProgressAccounts();
        long withoutRequest = inProgress.stream()
                .filter(user -> this.accounts.deletionRequestOf(user).isEmpty())
                .count();
        java.util.Set<UUID> notActive = new java.util.HashSet<>(inProgress);
        notActive.addAll(this.accounts.deletedAccounts());
        long activeWithRequest = this.accounts.accounts().stream()
                .filter(user -> !notActive.contains(user))
                .filter(user -> this.accounts.deletionRequestOf(user).isPresent())
                .count();
        return withoutRequest + activeWithRequest;
    }

    /** 루트가 소유한 일회성 허가 표의 행({@code identity_account}를 cascade로 따라 지워진다). */
    private long actionGrantsOf(UUID userId) {
        Long count = this.jdbc.sql("SELECT count(*) FROM action_grant WHERE user_id = :user")
                .param("user", userId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }
}
