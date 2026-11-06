package com.bokrilog;

import com.bokrilog.engagement.EngagementDeletionApi;
import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.operations.OperationsDeletionApi;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.SyncJobApi;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li>삭제가 끝난 계정의 잔여 행 0 — 모듈마다 {@code remainingRowsOf}. 보고는 합과 함께 모듈별 내역({@link #RESIDUAL_KEYS})을 싣는다
 *       (Story 5.4)
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
     * @param residuals 삭제가 끝난 계정에 남은 행의 모듈별 내역(Story 5.4) — 키 순서가 보고 줄의 순서다({@link #RESIDUAL_KEYS}). 합은
     *     {@link #residualRows}가 이 내역에서 낸다(합과 내역이 따로 들어와 어긋날 수 없다)
     */
    record Violations(long activePlans, long officialBundles, long portfolioPointers, long unfinishedDeletions,
            Map<String, Long> residuals) {

        Violations {
            residuals = Collections.unmodifiableMap(new LinkedHashMap<>(residuals));
        }

        /** 삭제가 끝난 계정에 남은 행의 합(모듈별 내역의 합). */
        long residualRows() {
            return this.residuals.values().stream().mapToLong(Long::longValue).sum();
        }

        long total() {
            return this.activePlans + this.officialBundles + this.portfolioPointers + this.unfinishedDeletions
                    + residualRows();
        }
    }

    /**
     * 모듈별 잔여의 키(Story 5.4). 보고 줄에 {@code residual_<키>=<수>}로 이 순서대로 실린다 — 복원 리허설이 각 값을 0으로 단정한다.
     * <ul>
     *   <li>{@code identity} — identity 모듈의 잔여({@code remainingRowsOf} — payload·매핑·세션·삭제 요청 행)에서 매핑·세션 칸을 뺀 것
     *       (payload·삭제 요청 행). 모듈 집계가 권위라 identity가 새 표를 세게 되면 이 칸에 나타난다. 뺄셈이 음수면 두 집계가 어긋난
     *       것이라 그 절댓값을 위반으로 센다({@code source}와 같은 규칙)
     *   <li>{@code sign_in} — 소셜 로그인 매핑(직접 센다)
     *   <li>{@code sessions} — 인증 세션(직접 센다)
     *   <li>{@code credentials} — Bybit 자격정보(직접 센다)
     *   <li>{@code source} — source 모듈에서 자격정보를 뺀 것(연결·provider account·원 사건). 뺄셈이 음수면 두 집계가 어긋난 것이라 그 절댓값을
     *       위반으로 센다 — 합에서 상쇄되지 않게
     *   <li>{@code portfolio} — portfolio·snapshot·구성 내역
     *   <li>{@code sync} — 수집·동기화 작업과 계산 결과
     *   <li>{@code plan} — 계획과 그 조건·종료·완료 기록
     *   <li>{@code operations} — 문의와 운영 기록(감사·제한)의 사용자 귀속
     *   <li>{@code engagement} — 광고 동의·광고 자격 결정
     *   <li>{@code grants} — 일회성 허가(루트 {@code action_grant})
     * </ul>
     */
    static final List<String> RESIDUAL_KEYS = List.of("identity", "sign_in", "sessions", "credentials", "source", "portfolio",
            "sync", "plan", "operations", "engagement", "grants");

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
        Map<String, Long> residuals = new LinkedHashMap<>();
        RESIDUAL_KEYS.forEach(key -> residuals.put(key, 0L));
        for (UUID deleted : this.accounts.deletedAccounts()) {
            long signIn = this.accounts.remainingSocialLinksOf(deleted);
            long sessions = this.accounts.remainingSessionsOf(deleted);
            long credentials = this.sources.remainingCredentialsOf(deleted);
            // identity 모듈은 매핑·세션을 함께 센다. 그것을 뺀 칸이 음수면 두 집계가 어긋난 것 — 절댓값을 위반으로 센다.
            residuals.merge("identity", Math.abs(this.accounts.remainingRowsOf(deleted) - signIn - sessions), Long::sum);
            residuals.merge("sign_in", signIn, Long::sum);
            residuals.merge("sessions", sessions, Long::sum);
            residuals.merge("credentials", credentials, Long::sum);
            // source 모듈은 자격정보를 함께 센다(연결을 거쳐). 그것을 뺀 칸이 음수면 두 집계가 어긋난 것 — 절댓값을 위반으로 센다.
            residuals.merge("source", Math.abs(this.sources.remainingRowsOf(deleted) - credentials), Long::sum);
            residuals.merge("portfolio", this.portfolios.remainingRowsOf(deleted), Long::sum);
            residuals.merge("sync", this.syncs.remainingRowsOf(deleted), Long::sum);
            residuals.merge("plan", this.plans.remainingRowsOf(deleted), Long::sum);
            residuals.merge("operations", this.operations.remainingRowsOf(deleted), Long::sum);
            residuals.merge("engagement", this.engagement.remainingRowsOf(deleted), Long::sum);
            residuals.merge("grants", actionGrantsOf(deleted), Long::sum);
        }
        return new Violations(this.plans.activePlanViolations(), this.syncs.officialBundleViolations(all),
                this.portfolios.currentPointerViolations(), unrequestedDeletions(), residuals);
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
        Set<UUID> notActive = new HashSet<>(inProgress);
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
