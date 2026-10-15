package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.SyncJobApi.EvidenceReference;
import com.bokrilog.sync.SyncJobApi.InvestigationRange;
import com.bokrilog.sync.SyncJobApi.SyncJob;
import com.bokrilog.sync.SyncJobApi.VerificationAdmission;
import com.bokrilog.sync.SyncJobApi.VerificationKind;
import com.bokrilog.sync.SyncJobApi.VerificationReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The verification admission's order and derivations (Story 2.12) that an integration test cannot provoke one by
 * one: the account, the replayed key by kind, the plan and the reference, an attempt in progress by purpose,
 * unavailability, the kind's own condition, the cooldown, the derived reason and the planned ranges — and the
 * system's reasons skipping the reference, the usefulness and the cooldown. Collaborators are mocks.
 */
class PlanVerificationServiceTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INTENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID PORTFOLIO = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID CALCULATION = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID CANDIDATE = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final String KEY = "verify-unit-key-000001";
    private static final Instant T0 = Instant.parse("2027-01-04T00:00:00Z");
    private static final Instant VERIFIED = Instant.parse("2027-01-24T00:00:00Z");
    private static final Instant PUBLISHED = VERIFIED.plusSeconds(2);
    private static final Instant END = Instant.parse("2027-06-04T00:00:00Z");

    private final PlanSyncRepository syncs = mock(PlanSyncRepository.class);
    private final PlanPublicationRepository publications = mock(PlanPublicationRepository.class);
    private final InitialCollectionRepository collections = mock(InitialCollectionRepository.class);
    private final PlanSyncGuard guard = mock(PlanSyncGuard.class);
    private final PlanSyncService planSyncs = mock(PlanSyncService.class);

    private final PlanSyncGuard.Base base = new PlanSyncGuard.Base(
            new PlanPublicationPort.ActivePlan(PLAN, INTENT, PORTFOLIO, "1000", T0, END, "DAILY", "0.1", PUBLISHED),
            new PlanPublicationRepository.Pointer(PORTFOLIO, CALCULATION, 3, PUBLISHED),
            new PlanPublicationRepository.CalculationView(PLAN, PORTFOLIO, UUID.randomUUID(), 4, UUID.randomUUID(),
                    "1000", T0, END, "DAILY", "0.1", VERIFIED, VERIFIED, VERIFIED, "CALC-1.0", "CALENDAR-SEOUL-1.0",
                    "DISPLAY-1.0", "BYBIT-PRICE-1.0", "impl", "a".repeat(64), "b".repeat(64), 0, 0, false, "CONFIRMED"),
            new PlanPublicationRepository.Lineage(UUID.randomUUID(), "BYBIT", ACCOUNT, UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CANDIDATE, UUID.randomUUID(),
                    UUID.randomUUID(), "BYBIT-CASHFLOW-1.0", "bybit-readonly-uta-1.0", "DECIMAL128-HALF_EVEN",
                    "Asia/Seoul", VERIFIED, VERIFIED, null));
    private final EvidenceReference shown = new EvidenceReference(PLAN, 3, CALCULATION);
    private final SyncJob queued = job("VERIFY", "USER_REQUEST", "MANUAL", "QUEUED");

    private static SyncJob job(String purpose, String reason, String trigger, String status) {
        return new SyncJob(UUID.randomUUID(), purpose, reason, trigger, status, null, null, PUBLISHED, null);
    }

    private PlanVerificationService service() {
        return new PlanVerificationService(syncs, publications, collections, guard, planSyncs,
                mock(PlatformTransactionManager.class));
    }

    /** Everything admits at {@code now}: writable, the shown pointer current, nothing in progress or in the way. */
    private void eligible(Instant now) {
        when(guard.writable(USER)).thenReturn(true);
        when(syncs.byKey(USER, KEY)).thenReturn(Optional.empty());
        when(guard.base(USER)).thenReturn(Optional.of(base));
        when(syncs.active(USER, PLAN)).thenReturn(Optional.empty());
        when(publications.databaseNow()).thenReturn(now);
        when(planSyncs.unavailableReason(eq(USER), any(), any())).thenReturn(null);
        // 계획의 지금 연결(Story 2.17). 새 attempt는 이 ID로 접수한다.
        when(planSyncs.planSource(eq(USER), any())).thenReturn(Optional.of(new PlanSyncGuard.PlanSource(
                base.lineage().membershipId(), base.lineage().connectionId(), ACCOUNT)));
        when(planSyncs.longGap(eq(USER), any(), any())).thenReturn(false);
        when(syncs.persistentlyReviewed(USER, PLAN, CALCULATION)).thenReturn(false);
        when(syncs.lastAdmission(USER, PLAN)).thenReturn(Optional.empty());
        when(collections.lineageEvidence(USER, CANDIDATE)).thenReturn(List.of(new VerificationPlanner.Interval(T0,
                VERIFIED)));
        when(collections.unexplainedEvents(USER, ACCOUNT, CANDIDATE, T0, VERIFIED)).thenReturn(List.of());
        when(syncs.insert(any())).thenReturn(1);
        when(syncs.byId(eq(USER), any())).thenReturn(Optional.of(queued));
    }

    /**
     * 재연결 뒤(Story 2.17) 정밀 검증·기존 계획 계속은 기준 버전이 기록한 연결이 아니라 계획의 지금 연결로 접수된다 — stub이
     * 기준 버전과 다른 membership·연결을 돌려준다.
     */
    @Test
    void aVerificationAfterAReconnectionIsAdmittedOnTheNewConnectionAndMembership() {
        eligible(PUBLISHED.plusSeconds(60));
        UUID membership = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        UUID connection = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
        when(planSyncs.planSource(eq(USER), any()))
                .thenReturn(Optional.of(new PlanSyncGuard.PlanSource(membership, connection, ACCOUNT)));
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Accepted(queued));
        ArgumentCaptor<PlanSyncRepository.Admission> admission = ArgumentCaptor.forClass(PlanSyncRepository.Admission.class);
        verify(syncs).insert(admission.capture());
        assertThat(admission.getValue().membershipId()).isEqualTo(membership)
                .isNotEqualTo(base.lineage().membershipId());
        assertThat(admission.getValue().connectionId()).isEqualTo(connection)
                .isNotEqualTo(base.lineage().connectionId());
        // 지금 연결이 없으면(가용성 판정 뒤 바뀌었다) attempt를 만들지 않는다.
        when(planSyncs.planSource(eq(USER), any())).thenReturn(Optional.empty());
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("VERIFICATION_UNAVAILABLE"));
    }

    @Test
    void anEligiblePrecisionVerificationIsQueuedWithItsPlan() {
        eligible(PUBLISHED.plusSeconds(60));
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Accepted(queued));
        ArgumentCaptor<PlanSyncRepository.Admission> admission = ArgumentCaptor.forClass(PlanSyncRepository.Admission.class);
        verify(syncs).insert(admission.capture());
        assertThat(admission.getValue().purpose()).isEqualTo("VERIFY");
        assertThat(admission.getValue().reason()).isEqualTo("USER_REQUEST");
        assertThat(admission.getValue().trigger()).isEqualTo("MANUAL");
        assertThat(admission.getValue().basePointerVersion()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VerificationPlanner.Range>> ranges = ArgumentCaptor.forClass(List.class);
        verify(syncs).insertRanges(eq(USER), any(), ranges.capture());
        assertThat(ranges.getValue()).containsExactly(
                new VerificationPlanner.Range(VerificationPlanner.Kind.UNCONFIRMED, VERIFIED.minus(Duration.ofDays(7)),
                        VERIFIED),
                new VerificationPlanner.Range(VerificationPlanner.Kind.REUSED, T0, VERIFIED.minus(Duration.ofDays(7))));
    }

    @Test
    void unexplainedStoredEventsMakeItAGapSuspicionAroundThem() {
        eligible(PUBLISHED.plusSeconds(60));
        Instant late = T0.plus(Duration.ofDays(2));
        when(collections.unexplainedEvents(USER, ACCOUNT, CANDIDATE, T0, VERIFIED)).thenReturn(List.of(late));
        service().admit(USER, VerificationKind.PRECISION, shown, KEY);
        ArgumentCaptor<PlanSyncRepository.Admission> admission = ArgumentCaptor.forClass(PlanSyncRepository.Admission.class);
        verify(syncs).insert(admission.capture());
        assertThat(admission.getValue().reason()).isEqualTo("GAP_SUSPECTED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VerificationPlanner.Range>> ranges = ArgumentCaptor.forClass(List.class);
        verify(syncs).insertRanges(eq(USER), any(), ranges.capture());
        assertThat(ranges.getValue()).contains(new VerificationPlanner.Range(VerificationPlanner.Kind.ANOMALY,
                late.minus(Duration.ofHours(24)), late.plus(Duration.ofHours(24))));
    }

    @Test
    void theAccountTheShapeAndTheKeyComeFirst() {
        eligible(PUBLISHED.plusSeconds(60));
        when(guard.writable(USER)).thenReturn(false);
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.NotPermitted());
        assertThat(service().admit(null, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.NotPermitted());
        assertThat(service().admit(USER, null, shown, KEY)).isEqualTo(new VerificationAdmission.Refused("BAD_REQUEST"));
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, "short"))
                .isEqualTo(new VerificationAdmission.Refused("IDEMPOTENCY_KEY_REQUIRED"));
        when(guard.writable(USER)).thenReturn(true);
        SyncJob keyed = job("VERIFY", "USER_REQUEST", "MANUAL", "SUCCEEDED");
        when(syncs.byKey(USER, KEY)).thenReturn(Optional.of(keyed));
        // A replay answers what the key did, even with no plan left; the other kind conflicts.
        when(guard.base(USER)).thenReturn(Optional.empty());
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Joined(keyed));
        assertThat(service().admit(USER, VerificationKind.CATCH_UP, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("IDEMPOTENCY_CONFLICT"));
        // A sync's key is not a verification's.
        when(syncs.byKey(USER, KEY)).thenReturn(Optional.of(job("SYNC", null, "MANUAL", "SUCCEEDED")));
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("IDEMPOTENCY_CONFLICT"));
        verify(syncs, never()).insert(any());
    }

    @Test
    void thePlanTheReferenceAndAnAttemptInProgressAreJudgedInOrder() {
        eligible(PUBLISHED.plusSeconds(60));
        when(guard.base(USER)).thenReturn(Optional.empty());
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("NO_ACTIVE_PLAN"));
        when(guard.base(USER)).thenReturn(Optional.of(base));
        for (var stale : List.of(new EvidenceReference(PLAN, 2, CALCULATION), new EvidenceReference(PLAN, 3,
                UUID.randomUUID()), new EvidenceReference(UUID.randomUUID(), 3, CALCULATION)))
            assertThat(service().admit(USER, VerificationKind.PRECISION, stale, KEY))
                    .isEqualTo(new VerificationAdmission.Refused("REFERENCE_STALE"));
        SyncJob verifying = job("VERIFY", "GAP_SUSPECTED", "MANUAL", "RUNNING");
        when(syncs.active(USER, PLAN)).thenReturn(Optional.of(verifying));
        when(planSyncs.unavailableReason(eq(USER), any(), any())).thenReturn("RESTRICTED");
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Joined(verifying));
        assertThat(service().admit(USER, VerificationKind.CATCH_UP, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("JOB_IN_PROGRESS"));
        when(syncs.active(USER, PLAN)).thenReturn(Optional.of(job("SYNC", null, "ENTRY", "RUNNING")));
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .as("a verification never joins a sync").isEqualTo(new VerificationAdmission.Refused("JOB_IN_PROGRESS"));
        when(syncs.active(USER, PLAN)).thenReturn(Optional.empty());
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("VERIFICATION_UNAVAILABLE"));
        verify(syncs, never()).insert(any());
    }

    @Test
    void eachKindHasItsOwnConditionAndThenTheCooldown() {
        eligible(PUBLISHED.plusSeconds(60));
        assertThat(service().admit(USER, VerificationKind.CATCH_UP, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("NOT_LONG_GAP"));
        when(syncs.persistentlyReviewed(USER, PLAN, CALCULATION)).thenReturn(true);
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("VERIFICATION_NOT_USEFUL"));
        assertThat(service().admit(USER, VerificationKind.CATCH_UP, shown, KEY)).as("before the long-gap condition")
                .isEqualTo(new VerificationAdmission.Refused("VERIFICATION_NOT_USEFUL"));
        // R7: the persistent review is judged before the long gap, in the dashboard's order (C3), for both kinds.
        when(planSyncs.longGap(eq(USER), any(), any())).thenReturn(true);
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("VERIFICATION_NOT_USEFUL"));
        assertThat(service().admit(USER, VerificationKind.CATCH_UP, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("VERIFICATION_NOT_USEFUL"));
        when(syncs.persistentlyReviewed(USER, PLAN, CALCULATION)).thenReturn(false);
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("LONG_GAP"));
        // The cooldown holds whatever purpose the last admission had: 29.001 seconds is one second to wait.
        Instant admitted = PUBLISHED.plusSeconds(30);
        when(publications.databaseNow()).thenReturn(admitted.plusMillis(29_001));
        when(syncs.lastAdmission(USER, PLAN)).thenReturn(Optional.of(admitted));
        SyncJob last = job("SYNC", null, "ENTRY", "FAILED");
        when(syncs.latest(USER, PLAN)).thenReturn(Optional.of(last));
        assertThat(service().admit(USER, VerificationKind.CATCH_UP, shown, KEY))
                .isEqualTo(new VerificationAdmission.Cooldown(last, 1L));
        when(publications.databaseNow()).thenReturn(admitted.plusSeconds(30));
        assertThat(service().admit(USER, VerificationKind.CATCH_UP, shown, KEY)).isInstanceOf(
                VerificationAdmission.Accepted.class);
        ArgumentCaptor<PlanSyncRepository.Admission> admission = ArgumentCaptor.forClass(PlanSyncRepository.Admission.class);
        verify(syncs).insert(admission.capture());
        assertThat(admission.getValue().purpose()).isEqualTo("CATCH_UP");
        assertThat(admission.getValue().reason()).isEqualTo("LONG_GAP_CONTINUATION");
    }

    @Test
    void theSystemsReasonsSkipTheReferenceTheUsefulnessAndTheCooldown() {
        eligible(PUBLISHED.plusSeconds(60));
        when(syncs.persistentlyReviewed(USER, PLAN, CALCULATION)).thenReturn(true);
        when(syncs.lastAdmission(USER, PLAN)).thenReturn(Optional.of(PUBLISHED.plusSeconds(59)));
        when(syncs.byId(eq(USER), any())).thenReturn(Optional.of(job("VERIFY", "PLAN_END_PREPARATION", "SYSTEM",
                "QUEUED")));
        assertThat(service().admitSystem(USER, VerificationReason.PLAN_END_PREPARATION, List.of(), KEY))
                .isInstanceOf(VerificationAdmission.Accepted.class);
        ArgumentCaptor<PlanSyncRepository.Admission> admission = ArgumentCaptor.forClass(PlanSyncRepository.Admission.class);
        verify(syncs).insert(admission.capture());
        assertThat(admission.getValue().trigger()).isEqualTo("SYSTEM");
        assertThat(admission.getValue().reason()).isEqualTo("PLAN_END_PREPARATION");
        assertThat(service().admitSystem(USER, VerificationReason.USER_REQUEST, List.of(), KEY))
                .isEqualTo(new VerificationAdmission.Refused("BAD_REQUEST"));
        assertThat(service().admitSystem(USER, VerificationReason.GAP_SUSPECTED, List.of(), KEY))
                .isEqualTo(new VerificationAdmission.Refused("BAD_REQUEST"));
        assertThat(service().admitSystem(USER, VerificationReason.OPERATOR_INVESTIGATION, List.of(), KEY))
                .isEqualTo(new VerificationAdmission.Refused("INVALID_RANGE"));
        assertThat(service().admitSystem(USER, VerificationReason.OPERATOR_INVESTIGATION,
                List.of(new InvestigationRange(T0, T0)), KEY)).isEqualTo(new VerificationAdmission.Refused("INVALID_RANGE"));
        assertThat(service().admitSystem(USER, VerificationReason.OPERATOR_INVESTIGATION,
                java.util.Collections.nCopies(11, new InvestigationRange(T0, VERIFIED)), KEY))
                .isEqualTo(new VerificationAdmission.Refused("INVALID_RANGE"));
        when(planSyncs.longGap(eq(USER), any(), any())).thenReturn(true);
        assertThat(service().admitSystem(USER, VerificationReason.DUPLICATE_SUSPECTED, List.of(), KEY))
                .isEqualTo(new VerificationAdmission.Refused("LONG_GAP"));
        // Nothing null reaches the transaction: it is refused, not thrown.
        assertThat(service().admitSystem(USER, null, List.of(), KEY)).isEqualTo(new VerificationAdmission.Refused(
                "BAD_REQUEST"));
        assertThat(service().admitSystem(USER, VerificationReason.PLAN_END_PREPARATION, null, KEY))
                .isEqualTo(new VerificationAdmission.Refused("INVALID_RANGE"));
        assertThat(service().admitSystem(USER, VerificationReason.OPERATOR_INVESTIGATION,
                java.util.Arrays.asList(new InvestigationRange(T0, VERIFIED), null), KEY))
                .isEqualTo(new VerificationAdmission.Refused("INVALID_RANGE"));
    }

    // ------------------------------------------------------------------ the system's same-request rule (patch 15)

    private static final List<InvestigationRange> ONE_RANGE = List.of(new InvestigationRange(T0.plus(Duration.ofDays(2)),
            T0.plus(Duration.ofDays(3))));

    private void storedExplicit(SyncJob job, List<InvestigationRange> ranges) {
        when(syncs.ranges(USER, job.jobId())).thenReturn(ranges.stream().map(range -> new VerificationPlanner.Range(
                VerificationPlanner.Kind.EXPLICIT, range.start(), range.end())).toList());
    }

    @Test
    void aSystemRequestJoinsOnlyTheSameSystemRequestInProgress() {
        eligible(PUBLISHED.plusSeconds(60));
        SyncJob investigating = job("VERIFY", "OPERATOR_INVESTIGATION", "SYSTEM", "RUNNING");
        storedExplicit(investigating, ONE_RANGE);
        when(syncs.active(USER, PLAN)).thenReturn(Optional.of(investigating));
        assertThat(service().admitSystem(USER, VerificationReason.OPERATOR_INVESTIGATION, ONE_RANGE, KEY))
                .isEqualTo(new VerificationAdmission.Joined(investigating));
        assertThat(service().admitSystem(USER, VerificationReason.OPERATOR_INVESTIGATION,
                List.of(new InvestigationRange(T0.plus(Duration.ofDays(4)), T0.plus(Duration.ofDays(5)))), KEY))
                .as("other ranges").isEqualTo(new VerificationAdmission.Refused("JOB_IN_PROGRESS"));
        assertThat(service().admitSystem(USER, VerificationReason.UNRECOGNIZED_EVENT, List.of(), KEY))
                .as("another reason").isEqualTo(new VerificationAdmission.Refused("JOB_IN_PROGRESS"));
        // The user's verification in progress is not the system's request.
        SyncJob users = job("VERIFY", "USER_REQUEST", "MANUAL", "RUNNING");
        storedExplicit(users, List.of());
        when(syncs.active(USER, PLAN)).thenReturn(Optional.of(users));
        assertThat(service().admitSystem(USER, VerificationReason.PLAN_END_PREPARATION, List.of(), KEY))
                .isEqualTo(new VerificationAdmission.Refused("JOB_IN_PROGRESS"));
        verify(syncs, never()).insert(any());
    }

    @Test
    void aReplayedSystemKeyNamesTheSameRequestOnlyWithTheSameReasonAndRanges() {
        eligible(PUBLISHED.plusSeconds(60));
        SyncJob keyed = job("VERIFY", "OPERATOR_INVESTIGATION", "SYSTEM", "SUCCEEDED");
        storedExplicit(keyed, ONE_RANGE);
        when(syncs.byKey(USER, KEY)).thenReturn(Optional.of(keyed));
        assertThat(service().admitSystem(USER, VerificationReason.OPERATOR_INVESTIGATION, ONE_RANGE, KEY))
                .isEqualTo(new VerificationAdmission.Joined(keyed));
        assertThat(service().admitSystem(USER, VerificationReason.OPERATOR_INVESTIGATION,
                List.of(new InvestigationRange(T0.plus(Duration.ofDays(2)), T0.plus(Duration.ofDays(4)))), KEY))
                .isEqualTo(new VerificationAdmission.Refused("IDEMPOTENCY_CONFLICT"));
        assertThat(service().admitSystem(USER, VerificationReason.DUPLICATE_SUSPECTED, List.of(), KEY))
                .isEqualTo(new VerificationAdmission.Refused("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void aLostRaceWithNothingLeftToReadRefusesAsInProgressNeverAsACooldown() {
        eligible(PUBLISHED.plusSeconds(60));
        when(syncs.insert(any())).thenReturn(0);
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Refused("JOB_IN_PROGRESS"));
        assertThat(service().admitSystem(USER, VerificationReason.PLAN_END_PREPARATION, List.of(), KEY))
                .isEqualTo(new VerificationAdmission.Refused("JOB_IN_PROGRESS"));
        // A lost race to the same key reads the winner.
        SyncJob winner = job("VERIFY", "USER_REQUEST", "MANUAL", "QUEUED");
        when(syncs.byKey(USER, KEY)).thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        assertThat(service().admit(USER, VerificationKind.PRECISION, shown, KEY))
                .isEqualTo(new VerificationAdmission.Joined(winner));
    }

    // ------------------------------------------------------------------ the dashboard part (patches 24, 25, C3)

    private PlanSyncService.Facts facts(boolean inProgress, boolean longGap, Duration wait,
            PlanSyncRepository.Review review) {
        return new PlanSyncService.Facts(PUBLISHED.plusSeconds(60), inProgress, null, longGap, wait, null, review);
    }

    @Test
    void theStateSuggestsAReasonOnlyWhenAVerificationCouldBeAskedFor() {
        eligible(PUBLISHED.plusSeconds(60));
        when(syncs.latestVerification(USER, PLAN)).thenReturn(Optional.empty());
        var available = service().state(USER, base, facts(false, false, null, null));
        assertThat(available.availability()).isEqualTo("AVAILABLE");
        assertThat(available.suggestedReason()).isEqualTo("USER_REQUEST");
        assertThat(available.preview()).isNotNull();
        var cooling = service().state(USER, base, facts(false, false, Duration.ofSeconds(12), null));
        assertThat(cooling.availability()).isEqualTo("COOLDOWN");
        assertThat(cooling.suggestedReason()).isEqualTo("USER_REQUEST");
        assertThat(cooling.retryAfterSeconds()).isEqualTo(12L);
        var longGap = service().state(USER, base, facts(false, true, null, null));
        assertThat(longGap.availability()).isEqualTo("LONG_GAP");
        assertThat(longGap.suggestedReason()).as("a long gap asks for the continuation").isNull();
        assertThat(longGap.preview()).isNull();
        assertThat(longGap.catchUpFrom()).isEqualTo(VERIFIED.minus(Duration.ofDays(7)));
        var review = new PlanSyncRepository.Review(UUID.randomUUID(), "SOURCE_EVENT_MISSING");
        var notUseful = service().state(USER, base, facts(false, false, null, review));
        assertThat(notUseful.availability()).isEqualTo("NOT_USEFUL");
        assertThat(notUseful.suggestedReason()).isNull();
        // C3: the persistent review wins over the long gap; where the continuation would start is still said.
        var reviewedGap = service().state(USER, base, facts(false, true, null, review));
        assertThat(reviewedGap.availability()).isEqualTo("NOT_USEFUL");
        assertThat(reviewedGap.catchUpFrom()).isEqualTo(VERIFIED.minus(Duration.ofDays(7)));
    }

    /**
     * R3: a continuation plans the same anomaly ranges as a precision verification — an unexplained stored event older
     * than the unconfirmed week is read again — and the dashboard says it starts there.
     */
    @Test
    void aContinuationPlansTheAnomalyRangesAndStartsAtTheEarliest() {
        eligible(PUBLISHED.plusSeconds(60));
        when(planSyncs.longGap(eq(USER), any(), any())).thenReturn(true);
        Instant old = T0.plus(Duration.ofDays(3));
        when(collections.unexplainedEvents(USER, ACCOUNT, CANDIDATE, T0, VERIFIED)).thenReturn(List.of(old));
        assertThat(service().admit(USER, VerificationKind.CATCH_UP, shown, KEY))
                .isInstanceOf(VerificationAdmission.Accepted.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VerificationPlanner.Range>> ranges = ArgumentCaptor.forClass(List.class);
        verify(syncs).insertRanges(eq(USER), any(), ranges.capture());
        assertThat(ranges.getValue()).contains(new VerificationPlanner.Range(VerificationPlanner.Kind.ANOMALY,
                old.minus(Duration.ofHours(24)), old.plus(Duration.ofHours(24))));
        when(syncs.latestVerification(USER, PLAN)).thenReturn(Optional.empty());
        var gap = service().state(USER, base, facts(false, true, null, null));
        assertThat(gap.catchUpFrom()).isEqualTo(old.minus(Duration.ofHours(24)));
        // While an attempt runs nothing is scanned: the start is its unconfirmed week's.
        assertThat(service().state(USER, base, facts(true, true, null, null)).catchUpFrom())
                .isEqualTo(VERIFIED.minus(Duration.ofDays(7)));
    }

    @Test
    void nothingIsPreviewedWhileAnAttemptIsInProgress() {
        eligible(PUBLISHED.plusSeconds(60));
        when(syncs.latestVerification(USER, PLAN)).thenReturn(Optional.empty());
        var running = service().state(USER, base, facts(true, false, null, null));
        assertThat(running.availability()).isEqualTo("IN_PROGRESS");
        assertThat(running.preview()).isNull();
        assertThat(running.suggestedReason()).isNull();
        verify(collections, never()).lineageEvidence(any(), any());
        verify(collections, never()).unexplainedEvents(any(), any(), any(), any(), any());
    }

    @Test
    void theLatestVerificationHoldsTheResultOnlyWhenItIsTheBasesPersistentReview() {
        eligible(PUBLISHED.plusSeconds(60));
        SyncJob reviewed = job("VERIFY", "USER_REQUEST", "MANUAL", "NEEDS_REVIEW");
        when(syncs.latestVerification(USER, PLAN)).thenReturn(Optional.of(reviewed));
        when(syncs.ranges(USER, reviewed.jobId())).thenReturn(List.of());
        when(syncs.reconciliationCounts(USER, reviewed.jobId())).thenReturn(new int[] {0, 0, 1, 0, 0});
        var holding = service().state(USER, base, facts(false, false, null,
                new PlanSyncRepository.Review(reviewed.jobId(), "SOURCE_EVENT_MISSING")));
        assertThat(holding.latest()).isNotNull();
        assertThat(holding.latest().holdsResult()).isTrue();
        var other = service().state(USER, base, facts(false, false, null,
                new PlanSyncRepository.Review(UUID.randomUUID(), "SOURCE_EVENT_MISSING")));
        assertThat(other.latest().holdsResult()).as("a later attempt that did not hold it").isFalse();
        assertThat(service().state(USER, base, facts(false, false, null, null)).latest().holdsResult()).isFalse();
    }
}
