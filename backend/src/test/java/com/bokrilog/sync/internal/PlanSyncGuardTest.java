package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.PlanPublicationPort;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 재연결(Story 2.17)에서 가드가 가르는 것: 계획의 지금 연결({@link PlanSyncGuard#planSource})은 계획의 provider account·
 * portfolio로 찾고, 실행 중인 attempt는 접수한 연결에 묶인다({@link PlanSyncGuard#blocked}) — 같은 계정이라도 지금 활성 연결이
 * 그 attempt의 연결이 아니면 막는다. 계획의 것이 아닌 활성 연결(다른 계정)의 원인은 해제가 아니라 연결 불가다.
 */
class PlanSyncGuardTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INTENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID PORTFOLIO = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID CONNECTION = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID CALCULATION = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID NEW_MEMBERSHIP = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID NEW_CONNECTION = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    private static final Instant T0 = Instant.parse("2027-01-04T00:00:00Z");
    private static final Instant VERIFIED = Instant.parse("2027-01-20T00:00:00Z");
    private static final Instant END = Instant.parse("2027-06-04T00:00:00Z");

    private final PlanPublicationRepository publications = mock(PlanPublicationRepository.class);
    private final SourceConnectionApi sources = mock(SourceConnectionApi.class);
    private final PortfolioMembershipApi portfolios = mock(PortfolioMembershipApi.class);
    private final AccountAccessApi accounts = mock(AccountAccessApi.class);
    private final OperationalRestrictionApi restrictions = mock(OperationalRestrictionApi.class);
    private final PlanPublicationPort plans = mock(PlanPublicationPort.class);

    private final PlanSyncGuard guard = new PlanSyncGuard(publications, sources, portfolios, accounts, restrictions,
            plans, mock(StrongSignals.class));

    /** 해제 전 연결로 접수된 attempt. */
    private final PlanSyncRepository.Lease oldLease = new PlanSyncRepository.Lease(UUID.randomUUID(), USER, PLAN,
            PORTFOLIO, INTENT, MEMBERSHIP, CONNECTION, ACCOUNT, "SYNC", null, "MANUAL", CALCULATION, 1,
            UUID.randomUUID(), VERIFIED, VERIFIED, T0, END, 1);

    @BeforeEach
    void anActivePlanOnItsPointer() {
        when(accounts.decide(USER, USER, AccountAccessKind.WRITE)).thenReturn(AccessDecision.ALLOWED);
        when(plans.activePlan(USER)).thenReturn(Optional.of(new PlanPublicationPort.ActivePlan(PLAN, INTENT,
                PORTFOLIO, "1000", T0, END, "DAILY", "0.1", VERIFIED)));
        when(publications.pointer(USER, PLAN)).thenReturn(Optional.of(new PlanPublicationRepository.Pointer(PORTFOLIO,
                CALCULATION, 1, VERIFIED)));
        when(publications.databaseNow()).thenReturn(VERIFIED.plusSeconds(60));
    }

    private void activeConnection(UUID connection, UUID membership, UUID account) {
        when(sources.snapshotConnection(USER)).thenReturn(Optional.of(new SourceConnectionApi.SnapshotConnection(
                connection, account, "bybit-readonly-uta-1.0")));
        when(portfolios.activeSource(USER)).thenReturn(Optional.of(new PortfolioMembershipApi.ActiveSource(PORTFOLIO,
                membership, connection, account)));
    }

    @Test
    void aLateAttemptOfTheOldConnectionIsBlockedEvenWhenTheNewOneIsTheSameAccount() {
        activeConnection(NEW_CONNECTION, NEW_MEMBERSHIP, ACCOUNT);
        assertThat(guard.blocked(oldLease)).isEqualTo(PlanSyncGuard.NOT_PERMITTED);
        // 대조 상대: 그 attempt의 연결이 지금 활성 연결이면 막지 않는다.
        activeConnection(CONNECTION, MEMBERSHIP, ACCOUNT);
        assertThat(guard.blocked(oldLease)).isNull();
    }

    @Test
    void thePlansConnectionIsTheActiveOneOfItsAccountAndPortfolio() {
        activeConnection(NEW_CONNECTION, NEW_MEMBERSHIP, ACCOUNT);
        assertThat(guard.planSource(USER, PORTFOLIO, ACCOUNT))
                .contains(new PlanSyncGuard.PlanSource(NEW_MEMBERSHIP, NEW_CONNECTION, ACCOUNT));
        assertThat(guard.unavailableReason(USER, PLAN, PORTFOLIO, CONNECTION, ACCOUNT, END, VERIFIED)).isNull();
        // 다른 계정의 활성 연결은 계획의 것이 아니다 — 해제가 아니라 연결 불가다.
        activeConnection(NEW_CONNECTION, NEW_MEMBERSHIP, OTHER_ACCOUNT);
        when(sources.isRevoked(any(), any())).thenReturn(true);
        assertThat(guard.planSource(USER, PORTFOLIO, ACCOUNT)).isEmpty();
        assertThat(guard.unavailableReason(USER, PLAN, PORTFOLIO, CONNECTION, ACCOUNT, END, VERIFIED))
                .isEqualTo(PlanSyncGuard.CONNECTION_UNAVAILABLE);
        assertThat(guard.completionUnavailableReason(USER, PLAN, PORTFOLIO, CONNECTION, ACCOUNT))
                .isEqualTo(PlanSyncGuard.CONNECTION_UNAVAILABLE);
        // 활성 연결이 없고 계획이 마지막으로 돈 연결이 해제됐으면 해제다.
        when(sources.snapshotConnection(USER)).thenReturn(Optional.empty());
        when(portfolios.activeSource(USER)).thenReturn(Optional.empty());
        assertThat(guard.unavailableReason(USER, PLAN, PORTFOLIO, CONNECTION, ACCOUNT, END, VERIFIED))
                .isEqualTo(PlanSyncGuard.CONNECTION_REVOKED);
    }
}
