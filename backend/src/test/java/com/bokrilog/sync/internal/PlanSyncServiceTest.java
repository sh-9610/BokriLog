package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.sync.SyncJobApi.SyncTrigger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ERD-1.0.0 §2.2's rules as pure functions (Story 2.11): the extended mode's seven-day boundary, the
 * collection start with its overlap and its {@code t0} clamp, the windows of at most seven days, the closed
 * retry table and the worker's run limit — and, against mocks, two orders an integration test cannot pin: the
 * admission clears the plan's lost attempts before it judges the one in progress, and a lease held past the
 * run limit is no longer renewed.
 */
class PlanSyncServiceTest {
    private static final Instant TC = Instant.parse("2027-03-01T00:00:00Z");
    private static final Duration DAY = Duration.ofDays(1);
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INTENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID PORTFOLIO = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID CALCULATION = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID CONNECTION = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final Instant T0 = TC.minus(DAY.multipliedBy(3));
    private static final Instant END = T0.plus(DAY.multipliedBy(30));
    private static final Instant VERIFIED = TC.minus(Duration.ofHours(1));

    private final PlanSyncRepository syncs = mock(PlanSyncRepository.class);
    private final PlanPublicationRepository publications = mock(PlanPublicationRepository.class);
    private final SourceConnectionApi sources = mock(SourceConnectionApi.class);
    private final PortfolioMembershipApi portfolios = mock(PortfolioMembershipApi.class);
    private final AccountAccessApi accounts = mock(AccountAccessApi.class);
    private final PlanPublicationPort plans = mock(PlanPublicationPort.class);
    private final PlanPublicationService publicationService = mock(PlanPublicationService.class);
    private final PlanSyncService service = new PlanSyncService(syncs, publications,
            mock(InitialCollectionRepository.class), mock(CashflowClassificationRepository.class),
            mock(CashflowConversionRepository.class), sources, portfolios, accounts, plans, publicationService,
            new CalculationConformance(() -> true), mock(PlatformTransactionManager.class));

    /** An active plan whose official version is current, on the connection and membership still active. */
    private void admissible() {
        when(accounts.decide(eq(USER), eq(USER), any(AccountAccessKind.class))).thenReturn(AccessDecision.ALLOWED);
        when(publicationService.syncRestricted()).thenReturn(false);
        when(plans.activePlan(USER)).thenReturn(Optional.of(new PlanPublicationPort.ActivePlan(PLAN, INTENT, PORTFOLIO,
                "1000", T0, END, "DAILY", "0.1", T0)));
        when(publications.pointer(USER, PLAN)).thenReturn(Optional.of(new PlanPublicationRepository.Pointer(PORTFOLIO,
                CALCULATION, 1, VERIFIED)));
        when(publications.calculation(USER, CALCULATION)).thenReturn(Optional.of(
                new PlanPublicationRepository.CalculationView(PLAN, PORTFOLIO, UUID.randomUUID(), 2, UUID.randomUUID(),
                        "1000", T0, END, "DAILY", "0.1", VERIFIED, VERIFIED, VERIFIED, "CALC-1.0", "CALENDAR-SEOUL-1.0",
                        "DISPLAY-1.0", "BYBIT-PRICE-1.0", "impl", "a".repeat(64), "b".repeat(64), 0, 0, false,
                        "CONFIRMED")));
        when(publications.lineage(USER, CALCULATION)).thenReturn(Optional.of(new PlanPublicationRepository.Lineage(
                UUID.randomUUID(), "BYBIT", ACCOUNT, CONNECTION, MEMBERSHIP, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "BYBIT-CASHFLOW-1.0", "bybit-readonly-uta-1.0",
                "DECIMAL128-HALF_EVEN", "Asia/Seoul", VERIFIED, "INITIAL", T0, VERIFIED, null)));
        when(sources.snapshotConnection(USER)).thenReturn(Optional.of(new SourceConnectionApi.SnapshotConnection(
                CONNECTION, ACCOUNT, "bybit-readonly-uta-1.0")));
        when(portfolios.activeSource(USER)).thenReturn(Optional.of(new PortfolioMembershipApi.ActiveSource(PORTFOLIO,
                MEMBERSHIP, CONNECTION, ACCOUNT)));
        when(syncs.databaseNow()).thenReturn(TC);
        when(syncs.expireLost(USER, PLAN)).thenReturn(List.of());
        when(syncs.active(USER, PLAN)).thenReturn(Optional.empty());
        when(syncs.latest(USER, PLAN)).thenReturn(Optional.empty());
        when(syncs.insert(any(), eq(USER), any(), eq("MANUAL"))).thenReturn(1);
        when(syncs.byId(eq(USER), any())).thenReturn(Optional.of(new SyncJobApi.SyncJob(UUID.randomUUID(), "MANUAL",
                "QUEUED", "ADMITTED", null, null, false, TC, null, null)));
    }

    @Test
    void anAdmissionEndsThePlansLostAttemptsBeforeItJudgesTheOneInProgress() {
        admissible();
        var admission = service.admit(USER, SyncTrigger.MANUAL);
        assertThat(admission).isInstanceOf(SyncJobApi.SyncAdmission.Answered.class);
        assertThat(((SyncJobApi.SyncAdmission.Answered) admission).answer().outcome()).isEqualTo("ADMITTED");
        var order = inOrder(syncs);
        order.verify(syncs).expireLost(USER, PLAN);
        order.verify(syncs).active(USER, PLAN);
        order.verify(syncs).insert(any(), eq(USER), any(), eq("MANUAL"));
    }

    @Test
    void anAccountThatMayNotWriteClearsNothingAndIsRefused() {
        admissible();
        when(accounts.decide(eq(USER), eq(USER), any(AccountAccessKind.class))).thenReturn(AccessDecision.DENIED);
        assertThat(service.admit(USER, SyncTrigger.MANUAL)).isInstanceOf(SyncJobApi.SyncAdmission.NotPermitted.class);
        verify(syncs, never()).expireLost(any(), any());
        verify(syncs, never()).insert(any(), any(), any(), any());
    }

    @Test
    void aConnectionThatIsNotTheOfficialVersionsIsAChangedSource() {
        admissible();
        when(sources.snapshotConnection(USER)).thenReturn(Optional.of(new SourceConnectionApi.SnapshotConnection(
                UUID.randomUUID(), ACCOUNT, "bybit-readonly-uta-1.0")));
        var answer = ((SyncJobApi.SyncAdmission.Answered) service.admit(USER, SyncTrigger.MANUAL)).answer();
        assertThat(answer.outcome()).isEqualTo("NOT_AVAILABLE");
        assertThat(answer.reason()).isEqualTo("SOURCE_CHANGED");
        assertThat(answer.manual()).isEqualTo(new SyncJobApi.ManualSync(false, "SOURCE_CHANGED", null));
        verify(syncs, never()).insert(any(), any(), any(), any());
    }

    @Test
    void aLeaseIsRenewedUntilTheRunLimitAndNeverAfter() {
        Instant claimed = TC;
        var lease = new PlanSyncRepository.Lease(UUID.randomUUID(), USER, PLAN, 1, claimed);
        when(syncs.heartbeat(lease)).thenReturn(true);
        when(syncs.databaseNow()).thenReturn(claimed.plus(PlanSyncService.MAX_RUN).minusMillis(1));
        assertThat(service.heartbeat(lease)).as("1 ms before the limit").isTrue();
        when(syncs.databaseNow()).thenReturn(claimed.plus(PlanSyncService.MAX_RUN));
        assertThat(service.heartbeat(lease)).as("at the limit: the lease runs out").isFalse();
        verify(syncs, times(1)).heartbeat(lease);
    }

    @Test
    void theRunLimitIsTenMinutesFromTheClaim() {
        assertThat(PlanSyncService.MAX_RUN).isEqualTo(Duration.ofMinutes(10));
        assertThat(PlanSyncService.withinRunLimit(TC, TC.plus(Duration.ofMinutes(10)).minusMillis(1))).isTrue();
        assertThat(PlanSyncService.withinRunLimit(TC, TC.plus(Duration.ofMinutes(10)))).isFalse();
        assertThat(PlanSyncService.QUEUED_LIMIT).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void theNextSyncExtendsExactlyWhenSevenDaysPassedSinceTheLastExtendedVerification() {
        assertThat(PlanSyncService.mode(TC, TC.minus(Duration.ofDays(7)).plusMillis(1))).as("7 days - 1 ms")
                .isEqualTo("NORMAL");
        assertThat(PlanSyncService.mode(TC, TC.minus(Duration.ofDays(7)))).as("exactly 7 days").isEqualTo("EXTENDED");
        assertThat(PlanSyncService.mode(TC, TC.minus(Duration.ofDays(7)).minusMillis(1))).as("7 days + 1 ms")
                .isEqualTo("EXTENDED");
    }

    @Test
    void theStartOverlapsTheLastVerificationAndNeverPrecedesThePlanStart() {
        Instant verified = TC.minus(DAY.multipliedBy(2));
        Instant t0 = TC.minus(DAY.multipliedBy(60));
        assertThat(PlanSyncService.rangeStart(t0, verified, "NORMAL")).isEqualTo(verified.minus(Duration.ofHours(24)));
        assertThat(PlanSyncService.rangeStart(t0, verified, "EXTENDED")).isEqualTo(verified.minus(Duration.ofDays(7)));
        // A young plan: the overlap would reach before t0, so the start is t0 itself.
        Instant young = verified.minus(Duration.ofHours(3));
        assertThat(PlanSyncService.rangeStart(young, verified, "NORMAL")).isEqualTo(young);
        assertThat(PlanSyncService.rangeStart(young, verified, "EXTENDED")).isEqualTo(young);
    }

    @Test
    void theRangeIsCoveredByWindowsOfAtMostSevenDays() {
        assertThat(PlanSyncService.windows(TC.minusMillis(1), TC)).isEqualTo(1);
        assertThat(PlanSyncService.windows(TC.minus(Duration.ofDays(7)), TC)).as("exactly one window").isEqualTo(1);
        assertThat(PlanSyncService.windows(TC.minus(Duration.ofDays(7)).minusMillis(1), TC)).isEqualTo(2);
        // A 29-day gap extended by 7 days: the whole range, never only the overlap.
        assertThat(PlanSyncService.windows(TC.minus(Duration.ofDays(36)), TC)).isEqualTo(6);
    }

    @Test
    void aGapOfExactlyThirtyDaysIsStillAnOrdinarySync() {
        Duration thirty = Duration.ofDays(30);
        assertThat(PlanSyncService.longGap(TC.minus(thirty).plusMillis(1), TC)).as("30 days - 1 ms").isFalse();
        assertThat(PlanSyncService.longGap(TC.minus(thirty), TC)).as("exactly 30 days").isFalse();
        assertThat(PlanSyncService.longGap(TC.minus(thirty).minusMillis(1), TC)).as("30 days + 1 ms").isTrue();
    }

    @Test
    void entryAndReactivationWaitFiveMinutesAndTheButtonThirtySeconds() {
        Duration five = Duration.ofMinutes(5);
        assertThat(PlanSyncService.recent(TC.minus(five).plusMillis(1), TC)).as("5 min - 1 ms").isTrue();
        assertThat(PlanSyncService.recent(TC.minus(five), TC)).as("exactly 5 min").isFalse();
        Duration thirty = Duration.ofSeconds(30);
        assertThat(PlanSyncService.cooldownSeconds(TC, TC)).as("just admitted").isEqualTo(30);
        assertThat(PlanSyncService.cooldownSeconds(TC.minus(thirty).plusMillis(1), TC)).as("1 ms left").isEqualTo(1);
        assertThat(PlanSyncService.cooldownSeconds(TC.minus(thirty), TC)).as("exactly 30 s").isZero();
        assertThat(PlanSyncService.cooldownSeconds(TC.minusMillis(12_500), TC)).as("17.5 s left").isEqualTo(18);
    }

    @Test
    void whetherANewAttemptCanHelpComesFromTheClosedTable() {
        for (String retryable : new String[] {"POINTER_CONFLICT", "REFERENCE_TIME_UNAVAILABLE", "REFERENCE_TIME_INVALID",
                "SOURCE_UNAVAILABLE", "CONVERSION_PENDING", "SYNC_INTERRUPTED"})
            assertThat(PlanSyncService.retryable(retryable)).as(retryable).isTrue();
        for (String final_ : new String[] {"NOT_PERMITTED", "PLAN_CHANGED", "MEMBERSHIP_CHANGED", "SOURCE_CHANGED",
                "LONG_GAP_CHOICE_REQUIRED",
                "PLAN_PERIOD_ENDED", "COLLECTION_REVIEW_REQUIRED", "CLASSIFICATION_REVIEW_REQUIRED", "CONVERSION_REQUIRED",
                "CONVERSION_REVIEW_REQUIRED", "APPLIED_FLOW_CHANGED", "SOURCE_REJECTED", "CALCULATION_RANGE_EXCEEDED",
                "CALCULATION_INPUT_INVALID", "CALCULATION_POLICY_UNVERIFIED"})
            assertThat(PlanSyncService.retryable(final_)).as(final_).isFalse();
    }
}
