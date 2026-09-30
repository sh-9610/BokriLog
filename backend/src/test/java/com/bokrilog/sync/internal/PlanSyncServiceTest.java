package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceRejectionReason;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.SyncJobApi.SyncAdmission;
import com.bokrilog.sync.SyncJobApi.SyncJob;
import com.bokrilog.sync.SyncJobApi.SyncState;
import com.bokrilog.sync.SyncJobApi.SyncTrigger;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The incremental sync's decisions that an integration test cannot provoke on demand or to the millisecond: the
 * admission's order (SYNC-UX-1.0), the worker's second judgment with the Bybit {@code tc}, a reclaimed attempt
 * that is not run again, and the mapping of provider rejections to closed codes. Collaborators are mocks; the
 * transaction manager is a mock too, so each {@code TransactionTemplate} callback simply runs.
 */
class PlanSyncServiceTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INTENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID PORTFOLIO = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID CONNECTION = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID CALCULATION = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID CANDIDATE = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String KEY = "sync-unit-key-000001";
    private static final Instant T0 = Instant.parse("2027-01-04T00:00:00Z");
    private static final Instant VERIFIED = Instant.parse("2027-01-20T00:00:00Z");
    private static final Instant PUBLISHED = VERIFIED.plusSeconds(2);
    private static final Instant END = Instant.parse("2027-06-04T00:00:00Z");

    private final PlanSyncRepository syncs = mock(PlanSyncRepository.class);
    private final PlanPublicationRepository publications = mock(PlanPublicationRepository.class);
    private final InitialCollectionRepository collections = mock(InitialCollectionRepository.class);
    private final CashflowClassificationRepository classifications = mock(CashflowClassificationRepository.class);
    private final CashflowConversionRepository conversions = mock(CashflowConversionRepository.class);
    private final SourceConnectionApi sources = mock(SourceConnectionApi.class);
    private final PlanSyncGuard guard = mock(PlanSyncGuard.class);
    private final PlanSyncPublisher publisher = mock(PlanSyncPublisher.class);

    private final PlanPublicationPort.ActivePlan plan = new PlanPublicationPort.ActivePlan(PLAN, INTENT, PORTFOLIO,
            "1000", T0, END, "DAILY", "0.1", PUBLISHED);
    private final PlanPublicationRepository.Pointer pointer = new PlanPublicationRepository.Pointer(PORTFOLIO,
            CALCULATION, 1, PUBLISHED);
    private final PlanPublicationRepository.CalculationView calculation = new PlanPublicationRepository.CalculationView(
            PLAN, PORTFOLIO, UUID.randomUUID(), 2, UUID.randomUUID(), "1000", T0, END, "DAILY", "0.1", VERIFIED,
            VERIFIED, VERIFIED, "CALC-1.0", "CALENDAR-SEOUL-1.0", "DISPLAY-1.0", "BYBIT-PRICE-1.0", "impl",
            "a".repeat(64), "b".repeat(64), 0, 0, false, "CONFIRMED");
    private final PlanPublicationRepository.Lineage lineage = new PlanPublicationRepository.Lineage(UUID.randomUUID(),
            "BYBIT", ACCOUNT, CONNECTION, MEMBERSHIP, UUID.randomUUID(), UUID.randomUUID(), CANDIDATE, UUID.randomUUID(),
            UUID.randomUUID(), "BYBIT-CASHFLOW-1.0", "bybit-readonly-uta-1.0", "DECIMAL128-HALF_EVEN", "Asia/Seoul",
            VERIFIED, VERIFIED, null);
    private final PlanSyncGuard.Base base = new PlanSyncGuard.Base(plan, pointer, calculation, lineage);
    private final SyncJob queued = new SyncJob(JOB, "SYNC", null, "MANUAL", "QUEUED", null, null, PUBLISHED, null);
    private final PlanSyncRepository.Lease lease = new PlanSyncRepository.Lease(JOB, USER, PLAN, PORTFOLIO, INTENT,
            MEMBERSHIP, CONNECTION, ACCOUNT, "SYNC", null, "MANUAL", CALCULATION, 1, CANDIDATE, VERIFIED, VERIFIED, T0,
            END, 1);

    private PlanSyncService service() {
        return new PlanSyncService(syncs, publications, collections, classifications, conversions, sources,
                new CalculationConformance(() -> true), guard, publisher, mock(PlatformTransactionManager.class));
    }

    /** An eligible plan at {@code now}: writable, a current version, nothing in progress, never synced. */
    private void eligible(Instant now) {
        when(guard.writable(USER)).thenReturn(true);
        when(syncs.byKey(USER, KEY)).thenReturn(Optional.empty());
        when(guard.base(USER)).thenReturn(Optional.of(base));
        when(syncs.active(USER, PLAN)).thenReturn(Optional.empty());
        when(publications.databaseNow()).thenReturn(now);
        when(guard.unavailableReason(eq(USER), any(), any(), any(), any(), any(), any())).thenReturn(null);
        when(syncs.latest(USER, PLAN)).thenReturn(Optional.empty());
        when(syncs.lastAdmission(USER, PLAN)).thenReturn(Optional.empty());
        when(syncs.longGapObserved(USER, PLAN, CALCULATION)).thenReturn(false);
        when(syncs.insert(any())).thenReturn(1);
        when(syncs.byId(eq(USER), any())).thenReturn(Optional.of(queued));
    }

    @Test
    void anEligibleManualRequestIsQueuedOnTheVersionItRead() {
        eligible(PUBLISHED.plusSeconds(1));
        assertThat(service().admit(USER, SyncTrigger.MANUAL, KEY)).isEqualTo(new SyncAdmission.Accepted(queued));
        ArgumentCaptor<PlanSyncRepository.Admission> admission =
                ArgumentCaptor.forClass(PlanSyncRepository.Admission.class);
        verify(syncs).insert(admission.capture());
        var pinned = admission.getValue();
        assertThat(pinned.trigger()).isEqualTo("MANUAL");
        assertThat(pinned.key()).isEqualTo(KEY);
        assertThat(pinned.planId()).isEqualTo(PLAN);
        assertThat(pinned.baseCalculationId()).isEqualTo(CALCULATION);
        assertThat(pinned.basePointerVersion()).isEqualTo(1);
        assertThat(pinned.baseCandidateId()).isEqualTo(CANDIDATE);
        assertThat(pinned.baseVerifiedThrough()).isEqualTo(VERIFIED);
        assertThat(pinned.baseExtendedAt()).isEqualTo(VERIFIED);
        assertThat(pinned.membershipId()).isEqualTo(MEMBERSHIP);
        assertThat(pinned.connectionId()).isEqualTo(CONNECTION);
        // The admission never calls the provider.
        verifyNoInteractions(sources);
    }

    @Test
    void theAccountAndTheRequestShapeAreJudgedFirst() {
        eligible(PUBLISHED.plusSeconds(60));
        when(guard.writable(USER)).thenReturn(false);
        assertThat(service().admit(USER, SyncTrigger.MANUAL, KEY)).isEqualTo(new SyncAdmission.NotPermitted());
        verify(syncs, never()).byKey(any(), any());
        assertThat(service().admit(null, SyncTrigger.MANUAL, KEY)).isEqualTo(new SyncAdmission.NotPermitted());
        assertThat(service().admit(USER, null, KEY)).isEqualTo(new SyncAdmission.Refused("BAD_REQUEST"));
        for (String key : new String[] {null, "short", "has space inside-key", "-leading-dash-key-01", "a..b-key-000001",
                "x".repeat(129)}) {
            assertThat(service().admit(USER, SyncTrigger.MANUAL, key)).as(String.valueOf(key))
                    .isEqualTo(new SyncAdmission.Refused("IDEMPOTENCY_KEY_REQUIRED"));
        }
        verify(syncs, never()).insert(any());
    }

    @Test
    void theSameKeyReturnsItsAttemptOrConflictsBeforeAnythingElse() {
        eligible(PUBLISHED.plusSeconds(60));
        SyncJob done = new SyncJob(JOB, "SYNC", null, "MANUAL", "SUCCEEDED", "PUBLICATION", null, PUBLISHED, PUBLISHED);
        when(syncs.byKey(USER, KEY)).thenReturn(Optional.of(done));
        // Even with no plan left, a replay answers what the key did.
        when(guard.base(USER)).thenReturn(Optional.empty());
        assertThat(service().admit(USER, SyncTrigger.MANUAL, KEY)).isEqualTo(new SyncAdmission.Joined(done));
        assertThat(service().admit(USER, SyncTrigger.ENTRY, KEY))
                .isEqualTo(new SyncAdmission.Refused("IDEMPOTENCY_CONFLICT"));
        verify(syncs, never()).insert(any());
    }

    @Test
    void withoutAnActivePlanAndPointerThereIsNothingToSync() {
        eligible(PUBLISHED.plusSeconds(60));
        when(guard.base(USER)).thenReturn(Optional.empty());
        assertThat(service().admit(USER, SyncTrigger.MANUAL, KEY)).isEqualTo(new SyncAdmission.Refused("NO_ACTIVE_PLAN"));
    }

    @Test
    void anAttemptInProgressIsJoinedEvenWhenNothingElseWouldBeAdmitted() {
        eligible(VERIFIED.plus(Duration.ofDays(40)));
        SyncJob running = new SyncJob(JOB, "SYNC", null, "ENTRY", "RUNNING", "COLLECTION", null, PUBLISHED, null);
        when(syncs.active(USER, PLAN)).thenReturn(Optional.of(running));
        when(guard.unavailableReason(eq(USER), any(), any(), any(), any(), any(), any())).thenReturn("RESTRICTED");
        when(syncs.lastAdmission(USER, PLAN)).thenReturn(Optional.of(VERIFIED.plus(Duration.ofDays(40))));
        for (SyncTrigger trigger : SyncTrigger.values())
            assertThat(service().admit(USER, trigger, KEY)).isEqualTo(new SyncAdmission.Joined(running));
        verify(syncs, never()).insert(any());
    }

    @Test
    void anUnavailableSyncIsRefusedBeforeTheLongGapAndTheCooldown() {
        eligible(VERIFIED.plus(Duration.ofDays(40)));
        when(guard.unavailableReason(eq(USER), any(), any(), any(), any(), any(), any())).thenReturn("PLAN_PERIOD_ENDED");
        assertThat(service().admit(USER, SyncTrigger.MANUAL, KEY)).isEqualTo(new SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        verify(syncs, never()).insert(any());
    }

    /** The long gap is judged on the database clock at admission: exactly 30 days is still admitted. */
    @ParameterizedTest(name = "{0} ms beyond 30 days → {1}")
    @CsvSource({"0,ACCEPTED", "1,LONG_GAP"})
    void theLongGapIsJudgedAtAdmissionToTheMillisecond(long beyond, String outcome) {
        eligible(VERIFIED.plus(Duration.ofDays(30)).plusMillis(beyond));
        var admission = service().admit(USER, SyncTrigger.ENTRY, KEY);
        if (outcome.equals("ACCEPTED")) {
            assertThat(admission).isInstanceOf(SyncAdmission.Accepted.class);
        } else {
            assertThat(admission).isEqualTo(new SyncAdmission.Deferred("LONG_GAP", null, null));
            verify(syncs, never()).insert(any());
        }
        verifyNoInteractions(sources);
    }

    @Test
    void aWorkerThatAlreadySawTheLongGapWithBybitsTimeMakesItALongGapForTheSameBase() {
        eligible(PUBLISHED.plusSeconds(3600));
        when(syncs.longGapObserved(USER, PLAN, CALCULATION)).thenReturn(true);
        assertThat(service().admit(USER, SyncTrigger.MANUAL, KEY)).isEqualTo(new SyncAdmission.Deferred("LONG_GAP", null,
                null));
    }

    @Test
    void aManualRequestWithinThirtySecondsOfTheLastAdmissionIsACooldown() {
        Instant admitted = PUBLISHED.plusSeconds(600);
        eligible(admitted.plusMillis(29_001));
        SyncJob failed = new SyncJob(JOB, "SYNC", null, "MANUAL", "FAILED", "TIME", "PROVIDER_UNAVAILABLE", admitted, admitted);
        when(syncs.lastAdmission(USER, PLAN)).thenReturn(Optional.of(admitted));
        when(syncs.latest(USER, PLAN)).thenReturn(Optional.of(failed));
        // Whatever the last attempt's outcome: 999 ms left is one second to wait.
        assertThat(service().admit(USER, SyncTrigger.MANUAL, KEY)).isEqualTo(new SyncAdmission.Deferred("COOLDOWN",
                failed, 1L));
        when(publications.databaseNow()).thenReturn(admitted.plusSeconds(30));
        assertThat(service().admit(USER, SyncTrigger.MANUAL, KEY)).isInstanceOf(SyncAdmission.Accepted.class);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SyncTrigger.class, names = {"ENTRY", "VISIBILITY"})
    void anAutomaticRequestWithinFiveMinutesOfThePublicationIsNotDue(SyncTrigger trigger) {
        eligible(PUBLISHED.plus(Duration.ofMinutes(5)).minusMillis(1));
        assertThat(service().admit(USER, trigger, KEY)).isEqualTo(new SyncAdmission.Deferred("NOT_DUE", null, 1L));
        verify(syncs, never()).insert(any());
        when(publications.databaseNow()).thenReturn(PUBLISHED.plus(Duration.ofMinutes(5)));
        assertThat(service().admit(USER, trigger, KEY)).isInstanceOf(SyncAdmission.Accepted.class);
    }

    @Test
    void aLostInsertRaceJoinsTheAttemptThatStood() {
        eligible(PUBLISHED.plusSeconds(3600));
        SyncJob winner = new SyncJob(UUID.randomUUID(), "SYNC", null, "ENTRY", "QUEUED", null, null, PUBLISHED, null);
        when(syncs.insert(any())).thenReturn(0);
        Optional<SyncJob> none = Optional.empty();
        when(syncs.active(USER, PLAN)).thenReturn(none).thenReturn(Optional.of(winner));
        assertThat(service().admit(USER, SyncTrigger.MANUAL, KEY)).isEqualTo(new SyncAdmission.Joined(winner));
    }

    @Test
    void theDashboardStateJudgesTheSameRulesAsTheAdmission() {
        Instant admitted = PUBLISHED.plusSeconds(600);
        eligible(admitted.plusSeconds(10));
        when(syncs.lastAdmission(USER, PLAN)).thenReturn(Optional.of(admitted));
        assertThat(service().state(USER, base)).isEqualTo(new SyncState("COOLDOWN", null, 20L, null));
        when(syncs.active(USER, PLAN)).thenReturn(Optional.of(queued));
        assertThat(service().state(USER, base).availability()).isEqualTo("IN_PROGRESS");
        when(syncs.active(USER, PLAN)).thenReturn(Optional.empty());
        when(guard.unavailableReason(eq(USER), any(), any(), any(), any(), any(), any())).thenReturn("RESTRICTED");
        assertThat(service().state(USER, base)).isEqualTo(new SyncState("UNAVAILABLE", "RESTRICTED", null, null));
        when(guard.unavailableReason(eq(USER), any(), any(), any(), any(), any(), any())).thenReturn(null);
        when(publications.databaseNow()).thenReturn(VERIFIED.plus(Duration.ofDays(31)));
        assertThat(service().state(USER, base)).isEqualTo(new SyncState("LONG_GAP", null, null, null));
        when(publications.databaseNow()).thenReturn(admitted.plusSeconds(30));
        assertThat(service().state(USER, base)).isEqualTo(new SyncState("AVAILABLE", null, null, null));
    }

    // ---------------------------------------------------------------- the worker

    @Test
    void aReclaimedRunningAttemptIsEndedAsInterruptedAndNotRunAgain() {
        when(syncs.claim()).thenReturn(Optional.of(new PlanSyncRepository.Claimed(lease, true, false)));
        when(syncs.finish(lease, "FAILED", "SYNC_INTERRUPTED")).thenReturn(Optional.of(new PlanSyncRepository.Ending(
                JOB, "SYNC", null, "MANUAL", "NORMAL", "FAILED", "COLLECTION", "SYNC_INTERRUPTED", 31_000)));
        assertThat(service().claim()).isEmpty();
        verify(syncs).finish(lease, "FAILED", "SYNC_INTERRUPTED");
        verifyNoInteractions(sources);

        when(syncs.claim()).thenReturn(Optional.of(new PlanSyncRepository.Claimed(lease, false, false)));
        assertThat(service().claim()).contains(lease);
    }

    /** A running attempt the worker may drive: the lease holds and nothing blocks it. */
    private void running() {
        when(syncs.lockOwned(lease)).thenReturn(true);
        when(syncs.stage(eq(lease), anyString())).thenReturn(true);
        when(syncs.fixTime(eq(lease), any())).thenReturn(true);
        when(syncs.fixScope(eq(lease), anyString(), any())).thenReturn(true);
        when(guard.blocked(lease)).thenReturn(null);
        when(syncs.finish(eq(lease), anyString(), anyString())).thenAnswer(call -> Optional.of(
                new PlanSyncRepository.Ending(JOB, "SYNC", null, "MANUAL", null, call.getArgument(1), "TIME",
                        call.getArgument(2), 1)));
    }

    @Test
    void aDeletionOrRestrictionBeforeTheFirstCallEndsTheAttemptWithoutAnyProviderCall() {
        running();
        when(guard.blocked(lease)).thenReturn("NOT_PERMITTED");
        service().run(lease, () -> true);
        verify(syncs).finish(lease, "BLOCKED", "NOT_PERMITTED");
        verifyNoInteractions(sources);
    }

    @Test
    void aServerTimeFailureEndsTheAttemptWithoutASignedCall() {
        running();
        when(sources.marketTime()).thenReturn(new SourceConnectionApi.MarketTimeResult.Rejected(
                SourceRejectionReason.TIMEOUT));
        service().run(lease, () -> true);
        verify(syncs).finish(lease, "FAILED", "PROVIDER_UNAVAILABLE");
        verify(sources, never()).collectWindows(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    /**
     * The same judgment again with Bybit's {@code tc}, before any signed call: more than 30 days is a long gap,
     * {@code tc >= T} ends the plan period, and a time that did not move past the verification is not trusted.
     * Each fixes {@code tc} on the attempt first. (The database clock agrees with {@code tc} here.)
     */
    @ParameterizedTest(name = "tc = {0} → {1} {2}")
    @CsvSource({"2027-02-19T00:00:00.001Z,BLOCKED,LONG_GAP", "2027-01-20T00:00:00Z,FAILED,PROVIDER_TIME_UNTRUSTED",
        "2027-01-19T23:59:59Z,FAILED,PROVIDER_TIME_UNTRUSTED"})
    void theWorkerJudgesTheFixedTcBeforeAnySignedCall(String tc, String status, String code) {
        running();
        when(publications.databaseNow()).thenReturn(Instant.parse(tc));
        when(sources.marketTime()).thenReturn(new SourceConnectionApi.MarketTimeResult.Observed(Instant.parse(tc)));
        service().run(lease, () -> true);
        verify(syncs).fixTime(lease, Instant.parse(tc));
        verify(syncs).finish(lease, status, code);
        verify(syncs, never()).fixScope(any(), any(), any());
        verify(sources, never()).collectWindows(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    @Test
    void aTcAtThePlansEndEndsThePlanPeriodWithoutASignedCall() {
        var ending = new PlanSyncRepository.Lease(JOB, USER, PLAN, PORTFOLIO, INTENT, MEMBERSHIP, CONNECTION, ACCOUNT,
                "SYNC", null, "MANUAL", CALCULATION, 1, CANDIDATE, VERIFIED, VERIFIED, T0,
                VERIFIED.plus(Duration.ofDays(1)), 1);
        when(syncs.lockOwned(ending)).thenReturn(true);
        when(syncs.stage(eq(ending), anyString())).thenReturn(true);
        when(syncs.fixTime(eq(ending), any())).thenReturn(true);
        when(guard.blocked(ending)).thenReturn(null);
        when(syncs.finish(eq(ending), anyString(), anyString())).thenReturn(Optional.empty());
        when(sources.marketTime()).thenReturn(new SourceConnectionApi.MarketTimeResult.Observed(
                VERIFIED.plus(Duration.ofDays(1))));
        when(publications.databaseNow()).thenReturn(VERIFIED.plus(Duration.ofDays(1)));
        service().run(ending, () -> true);
        verify(syncs).finish(ending, "BLOCKED", "PLAN_PERIOD_ENDED");
        verify(sources, never()).collectWindows(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    /**
     * Bybit's time is trusted as {@code tc} only within 60 seconds of the database clock, either way: exactly 60
     * seconds goes on (here to the long gap its value implies), 60.001 seconds ends the attempt as untrusted — both
     * before any signed call, with {@code tc} kept on the attempt.
     */
    @ParameterizedTest(name = "database clock {0} ms from tc → {1} {2}")
    @CsvSource({"60000,BLOCKED,LONG_GAP", "-60000,BLOCKED,LONG_GAP", "60001,FAILED,PROVIDER_TIME_UNTRUSTED",
        "-60001,FAILED,PROVIDER_TIME_UNTRUSTED", "86400000,FAILED,PROVIDER_TIME_UNTRUSTED"})
    void bybitsTimeIsTrustedOnlyWithinSixtySecondsOfTheDatabaseClock(long offset, String status, String code) {
        running();
        Instant tc = VERIFIED.plus(Duration.ofDays(40));
        when(publications.databaseNow()).thenReturn(tc.plusMillis(offset));
        when(sources.marketTime()).thenReturn(new SourceConnectionApi.MarketTimeResult.Observed(tc));
        service().run(lease, () -> true);
        verify(syncs).fixTime(lease, tc);
        verify(syncs).finish(lease, status, code);
        verify(syncs, never()).fixScope(any(), any(), any());
        verify(sources, never()).collectWindows(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    // ---------------------------------------------------------------- the collection stage

    private static final Instant TC = VERIFIED.plus(Duration.ofHours(1));
    private final InitialCollectionRepository.Lease child = new InitialCollectionRepository.Lease(UUID.randomUUID(),
            USER, PORTFOLIO, INTENT, CONNECTION, ACCOUNT, T0, TC, 1);

    /** A running attempt that reaches its collection: a trusted {@code tc}, a normal window, its child row. */
    private void collecting() {
        running();
        when(publications.databaseNow()).thenReturn(TC);
        when(sources.marketTime()).thenReturn(new SourceConnectionApi.MarketTimeResult.Observed(TC));
        when(collections.insertIncremental(any(), eq(lease), eq(TC), eq(VERIFIED.minus(Duration.ofHours(24)))))
                .thenReturn(child);
        when(syncs.attachCollection(eq(lease), any())).thenReturn(true);
        when(collections.lockOwned(child)).thenReturn(true);
    }

    private static SourceConnectionApi.CollectionPage page(int index) {
        return new SourceConnectionApi.CollectionPage(new SourceConnectionApi.PageEvidence("TRANSFER_IN",
                VERIFIED.minus(Duration.ofHours(24)), TC, "c".repeat(63) + index, TC, TC, 0, null, null, true),
                java.util.List.of(), false);
    }

    /**
     * A collection rejection ends the attempt at COLLECTION with the closed code of the spec's table, and its
     * child with the stage table's own ending — a review for a malformed answer, a retry otherwise. Nothing is
     * classified.
     */
    @ParameterizedTest(name = "{0} → {1} {2}, child {3}")
    @CsvSource({"RESPONSE_FORMAT,NEEDS_REVIEW,SOURCE_REQUIRES_REVIEW,NEEDS_REVIEW",
        "AUTHENTICATION_REJECTED,FAILED,SOURCE_REJECTED,RETRY_REQUIRED",
        "IP_NOT_BOUND,FAILED,SOURCE_REJECTED,RETRY_REQUIRED", "SUB_ACCOUNT,FAILED,SOURCE_REJECTED,RETRY_REQUIRED",
        "NOT_PERMITTED,BLOCKED,NOT_PERMITTED,RETRY_REQUIRED", "TIMEOUT,FAILED,PROVIDER_UNAVAILABLE,RETRY_REQUIRED",
        "RATE_LIMITED,FAILED,PROVIDER_UNAVAILABLE,RETRY_REQUIRED",
        "PROVIDER_UNAVAILABLE,FAILED,PROVIDER_UNAVAILABLE,RETRY_REQUIRED"})
    void aCollectionRejectionEndsAtTheCollectionStageWithItsClosedCode(SourceRejectionReason reason, String status,
            String code, String childStatus) {
        collecting();
        when(sources.collectWindows(eq(USER), eq(CONNECTION), eq(T0), eq(TC),
                eq(java.util.List.of(new SourceConnectionApi.CollectionWindow(VERIFIED.minus(Duration.ofHours(24)), TC))),
                eq(true), any(), any())).thenReturn(new SourceConnectionApi.WindowCollectionResult.Rejected(reason, 0, 0));
        service().run(lease, () -> true);
        verify(syncs).fixScope(lease, "NORMAL", VERIFIED.minus(Duration.ofHours(24)));
        verify(syncs).attachCollection(eq(lease), any());
        verify(collections).finish(child, childStatus, code);
        verify(syncs).finish(lease, status, code);
        verify(classifications, never()).insertChild(any(), any(), any());
    }

    /**
     * A deletion or restriction that lands between two pages wins: the first page is stored, the second is not,
     * no further page is asked for, and the attempt ends blocked at its collection.
     */
    @Test
    void aDeletionOrRestrictionBetweenTwoPagesEndsTheCollectionBlocked() {
        collecting();
        // TIME, the collection's entry, page one — then page two finds the account refused.
        when(guard.blocked(lease)).thenReturn(null, null, null, "NOT_PERMITTED");
        java.util.concurrent.atomic.AtomicInteger offered = new java.util.concurrent.atomic.AtomicInteger();
        when(sources.collectWindows(eq(USER), eq(CONNECTION), eq(T0), eq(TC), any(), eq(true), any(), any()))
                .thenAnswer(call -> {
                    java.util.function.Consumer<SourceConnectionApi.CollectionPage> pages = call.getArgument(6);
                    for (int index = 1; index <= 3; index++) {
                        offered.incrementAndGet();
                        pages.accept(page(index));
                    }
                    return new SourceConnectionApi.WindowCollectionResult.Rejected(
                            SourceRejectionReason.PROVIDER_UNAVAILABLE, 1, 0);
                });
        service().run(lease, () -> true);
        verify(collections, org.mockito.Mockito.times(1)).storePage(eq(child), any(), any(), eq(false));
        assertThat(offered.get()).as("no page after the refused one is asked for").isEqualTo(2);
        verify(syncs).finish(lease, "BLOCKED", "NOT_PERMITTED");
        verify(classifications, never()).insertChild(any(), any(), any());
    }

    /**
     * Interpretation C1: a persistent verification review of this very base holds a sync before any provider call —
     * the time included — and ends it as that review; nothing is collected or published.
     */
    @Test
    void aPersistentVerificationReviewHoldsTheSyncBeforeAnyProviderCall() {
        running();
        when(syncs.verificationReview(USER, PLAN, CALCULATION)).thenReturn(Optional.of(
                new PlanSyncRepository.Review(UUID.randomUUID(), "SOURCE_EVENT_MISSING")));
        service().run(lease, () -> true);
        verify(syncs).finish(lease, "NEEDS_REVIEW", "SOURCE_EVENT_MISSING");
        verify(syncs, never()).fixTime(any(), any());
        verifyNoInteractions(sources);
    }

    private SourceConnectionApi.WindowCollection clean() {
        return new SourceConnectionApi.WindowCollection("1", "1000", TC, TC, TC, 1, 0, false, null, "policy-1");
    }

    /**
     * A page that asked for review is kept on the collection, so the completion reads it even when this holder's own
     * pages were clean — an earlier holder's, or a continuation's first phase (patch 1).
     */
    @Test
    void aStoredPageReviewMakesTheCompletionAReviewEvenWhenThisRunsPagesWereClean() {
        collecting();
        when(sources.collectWindows(eq(USER), eq(CONNECTION), eq(T0), eq(TC), any(), eq(true), any(), any()))
                .thenReturn(new SourceConnectionApi.WindowCollectionResult.Collected(clean()));
        when(sources.snapshotConnection(USER)).thenReturn(Optional.of(new SourceConnectionApi.SnapshotConnection(
                CONNECTION, ACCOUNT, "policy-1")));
        when(collections.pageReviewed(child.jobId())).thenReturn(true);
        when(collections.completeTargets(eq(child), any(), eq(true), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new InitialCollectionRepository.StoredInput(null, "NEEDS_REVIEW", "SOURCE_REQUIRES_REVIEW"));
        service().run(lease, () -> true);
        verify(collections).completeTargets(eq(child), any(), eq(true), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(syncs).finish(lease, "NEEDS_REVIEW", "SOURCE_REQUIRES_REVIEW");
    }

    /**
     * A collection that stopped because its lease stopped holding ends nothing: no provider code is written for a
     * failure that did not happen, on the attempt or its child (patch 8).
     */
    @Test
    void aStoppedCollectionEndsNothing() {
        collecting();
        when(sources.collectWindows(eq(USER), eq(CONNECTION), eq(T0), eq(TC), any(), eq(true), any(), any()))
                .thenReturn(new SourceConnectionApi.WindowCollectionResult.Stopped(1, 2));
        service().run(lease, () -> true);
        verify(syncs, never()).finish(any(), any(), any());
        verify(collections, never()).finish(any(), any(), any());
        verify(collections, never()).completeTargets(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(),
                any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aWorkerThatLostItsLeaseWritesNothingMore() {
        running();
        when(syncs.lockOwned(lease)).thenReturn(false);
        service().run(lease, () -> true);
        verify(syncs, never()).finish(any(), any(), any());
        verifyNoInteractions(sources);
        // The local guard stops it before the first call too.
        running();
        service().run(lease, () -> false);
        verifyNoInteractions(sources);
    }

    // ---------------------------------------------------------------- verification and continuation (Story 2.12)

    private PlanSyncRepository.Lease verifying(String purpose, String reason) {
        return new PlanSyncRepository.Lease(JOB, USER, PLAN, PORTFOLIO, INTENT, MEMBERSHIP, CONNECTION, ACCOUNT, purpose,
                reason, "MANUAL", CALCULATION, 1, CANDIDATE, VERIFIED, VERIFIED, T0, END, 1);
    }

    /** A verification or continuation that reaches its plan with a trusted {@code tc}. */
    private void verifyingRun(PlanSyncRepository.Lease attempt, Instant tc, java.util.List<VerificationPlanner.Range> planned) {
        when(syncs.progress(attempt)).thenReturn(new PlanSyncRepository.Progress(null, null, null, null, null, null, 0));
        when(syncs.lockOwned(attempt)).thenReturn(true);
        when(syncs.owns(attempt)).thenReturn(true);
        when(syncs.stage(eq(attempt), anyString())).thenReturn(true);
        when(syncs.fixTime(eq(attempt), any())).thenReturn(true);
        when(syncs.fixScope(eq(attempt), anyString(), any())).thenReturn(true);
        when(guard.blocked(attempt)).thenReturn(null);
        when(publications.databaseNow()).thenReturn(tc);
        when(sources.marketTime()).thenReturn(new SourceConnectionApi.MarketTimeResult.Observed(tc));
        var withNew = new java.util.ArrayList<>(planned);
        withNew.add(VerificationPlanner.newRange(VERIFIED, tc));
        when(syncs.ranges(USER, JOB)).thenReturn(planned).thenReturn(withNew);
        when(syncs.finish(eq(attempt), anyString(), anyString())).thenAnswer(call -> Optional.of(
                new PlanSyncRepository.Ending(JOB, attempt.purpose(), attempt.reason(), "MANUAL", null,
                        call.getArgument(1), "TIME", call.getArgument(2), 1)));
    }

    /**
     * With the same {@code tc} more than 30 days past the verification, a precision verification is held back as a
     * long gap before any signed call, while the continuation — which exists for exactly that gap — goes on to its
     * first phase: the windows without the wallet.
     */
    @Test
    void theLongGapHoldsAVerificationBackButNotTheContinuation() {
        Instant tc = VERIFIED.plus(Duration.ofDays(31));
        var unconfirmed = java.util.List.of(VerificationPlanner.unconfirmed(T0, VERIFIED));
        var verify = verifying("VERIFY", "USER_REQUEST");
        verifyingRun(verify, tc, unconfirmed);
        service().run(verify, () -> true);
        verify(syncs).finish(verify, "BLOCKED", "LONG_GAP");
        verify(sources, never()).collectWindows(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any());

        var catchUp = verifying("CATCH_UP", "LONG_GAP_CONTINUATION");
        verifyingRun(catchUp, tc, unconfirmed);
        when(collections.insertIncremental(any(), eq(catchUp), eq(tc), any())).thenReturn(child);
        when(syncs.attachCollection(eq(catchUp), any())).thenReturn(true);
        when(collections.lockOwned(child)).thenReturn(true);
        when(sources.collectWindows(eq(USER), eq(CONNECTION), eq(T0), eq(tc), any(), eq(false), any(), any()))
                .thenReturn(new SourceConnectionApi.WindowCollectionResult.Rejected(SourceRejectionReason.TIMEOUT, 0, 3));
        service().run(catchUp, () -> true);
        verify(syncs).insertRanges(USER, JOB, java.util.List.of(VerificationPlanner.newRange(VERIFIED, tc)));
        verify(syncs).fixScope(catchUp, "CATCH_UP", VERIFIED.minus(Duration.ofDays(7)));
        verify(sources).collectWindows(eq(USER), eq(CONNECTION), eq(T0), eq(tc), any(), eq(false), any(), any());
        verify(syncs).finish(catchUp, "FAILED", "PROVIDER_UNAVAILABLE");
    }

    /** A target older than {@code tc - 730 days} ends the attempt in review before any signed call or scope. */
    @Test
    void aTargetOutOfTheHistoryEndsBeforeAnySignedCall() {
        Instant tc = VERIFIED.plus(Duration.ofHours(1));
        var attempt = verifying("VERIFY", "OPERATOR_INVESTIGATION");
        verifyingRun(attempt, tc, java.util.List.of(VerificationPlanner.unconfirmed(T0, VERIFIED),
                new VerificationPlanner.Range(VerificationPlanner.Kind.EXPLICIT, tc.minus(Duration.ofDays(731)),
                        tc.minus(Duration.ofDays(730)))));
        service().run(attempt, () -> true);
        verify(syncs).finish(attempt, "NEEDS_REVIEW", "HISTORY_OUT_OF_RANGE");
        verify(syncs, never()).fixScope(any(), any(), any());
        verify(sources, never()).collectWindows(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                any(), any());
    }

    /**
     * Provider rejections map to closed endings (the spec's table): a malformed answer is a review, a refusal of
     * the key or account is the source's rejection, a connection that is gone blocks, and the rest is a provider
     * failure the user may retry by syncing again.
     */
    @ParameterizedTest(name = "{0} → {1} {2}")
    @CsvSource({"RESPONSE_FORMAT,NEEDS_REVIEW,SOURCE_REQUIRES_REVIEW",
        "AUTHENTICATION_REJECTED,FAILED,SOURCE_REJECTED", "WRITE_PERMISSION,FAILED,SOURCE_REJECTED",
        "INSUFFICIENT_PERMISSION,FAILED,SOURCE_REJECTED", "SUB_ACCOUNT,FAILED,SOURCE_REJECTED",
        "IP_NOT_BOUND,FAILED,SOURCE_REJECTED", "UNSUPPORTED_ACCOUNT_MODE,FAILED,SOURCE_REJECTED",
        "INVALID_FORMAT,FAILED,SOURCE_REJECTED", "NOT_PERMITTED,BLOCKED,NOT_PERMITTED",
        "TIMEOUT,FAILED,PROVIDER_UNAVAILABLE", "RATE_LIMITED,FAILED,PROVIDER_UNAVAILABLE",
        "PROVIDER_UNAVAILABLE,FAILED,PROVIDER_UNAVAILABLE", "WALLET_UNAVAILABLE,FAILED,PROVIDER_UNAVAILABLE",
        "UNAVAILABLE,FAILED,PROVIDER_UNAVAILABLE"})
    void aProviderRejectionEndsWithAClosedCode(SourceRejectionReason reason, String status, String code) {
        assertThat(PlanSyncService.rejection(reason)).containsExactly(status, code);
    }
}
