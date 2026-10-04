package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.SyncJobApi.BundleConsistency;
import com.bokrilog.sync.SyncJobApi.CalculationEvidence;
import com.bokrilog.sync.SyncJobApi.Dashboard;
import com.bokrilog.sync.SyncJobApi.DashboardMetric;
import com.bokrilog.sync.SyncJobApi.DashboardRead;
import com.bokrilog.sync.SyncJobApi.EvidenceOutcome;
import com.bokrilog.sync.SyncJobApi.EvidenceRead;
import com.bokrilog.sync.SyncJobApi.EvidenceReference;
import com.bokrilog.sync.SyncJobApi.PublicationAdmission;
import com.bokrilog.sync.SyncJobApi.PublicationJob;
import com.bokrilog.sync.SyncJobApi.PublicationState;
import com.bokrilog.sync.internal.PlanPublicationRepository.Candidate;
import com.bokrilog.sync.internal.PlanPublicationRepository.Conversion;
import com.bokrilog.sync.internal.PlanPublicationRepository.Flow;
import com.bokrilog.sync.internal.PlanPublicationRepository.Lease;
import com.bokrilog.sync.internal.PlanPublicationRepository.Pinned;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * The publication service's decisions that an integration test cannot provoke on demand: a
 * non-conformant build, a racing tab that loses to an already finished winner, a verification that
 * throws for one attempt's data, and the input hash's field boundaries. Collaborators are mocks; the
 * transaction manager is a mock too, so each {@code TransactionTemplate} callback simply runs.
 */
class PlanPublicationServiceTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INTENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PORTFOLIO = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID CONNECTION = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID BASIS_SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID BASIS_SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID CANDIDATE = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID CLASSIFICATION = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID CONVERSION = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final String KEY = "publication-unit-key-001";
    private static final Instant T0 = Instant.parse("2027-01-04T00:00:00Z");
    private static final Instant TC = T0.plusSeconds(3600);
    private static final Instant END = T0.plusSeconds(30L * 86_400);

    private final PlanPublicationRepository repository = mock(PlanPublicationRepository.class);
    private final CashflowClassificationRepository classifications = mock(CashflowClassificationRepository.class);
    private final CashflowConversionRepository conversions = mock(CashflowConversionRepository.class);
    private final SourceConnectionApi sources = mock(SourceConnectionApi.class);
    private final PortfolioMembershipApi portfolios = mock(PortfolioMembershipApi.class);
    private final AccountAccessApi accounts = mock(AccountAccessApi.class);
    private final OperationalRestrictionApi restrictions = mock(OperationalRestrictionApi.class);
    private final PlanPublicationPort plans = mock(PlanPublicationPort.class);
    private final PlanSyncService planSyncs = mock(PlanSyncService.class);
    private final PlanSyncRepository planSyncAttempts = mock(PlanSyncRepository.class);
    private final PlanVerificationService planVerifications = mock(PlanVerificationService.class);

    private final PlanPublicationPort.Terms terms = new PlanPublicationPort.Terms(INTENT, PORTFOLIO, BASIS_SNAPSHOT,
            MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SOURCE, "1000", T0, END, "DAILY", "0.1", "bybit-readonly-uta-1.0",
            "bybit-readonly-uta-1.0", "CALENDAR-SEOUL-1.0", "CALC-1.0");
    private final Candidate candidate = new Candidate(CANDIDATE, USER, PORTFOLIO, INTENT, CONNECTION, ACCOUNT, T0, TC,
            "1250", TC, TC, "bybit-readonly-uta-1.0", "a".repeat(64), null, null);
    private final CashflowConversionRepository.Classification classification =
            new CashflowConversionRepository.Classification(CLASSIFICATION, CANDIDATE, "b".repeat(64), "CASHFLOW-1.0", 0);
    private final Conversion conversion = new Conversion(CONVERSION, CLASSIFICATION, USER, INTENT, PORTFOLIO,
            CONNECTION, ACCOUNT, "CONVERTED", true, "c".repeat(64), "BYBIT-PRICE-1.0", 0, 0);
    private final PortfolioMembershipApi.OfficialValue pointer = new PortfolioMembershipApi.OfficialValue("1000",
            "USD", T0, T0, T0, BASIS_SNAPSHOT, 1);
    private final String hash = PlanPublicationService.inputHash(terms, candidate, classification, conversion,
            List.of(), BASIS_SNAPSHOT, 1);
    private final Lease lease = new Lease(JOB, USER, INTENT, PORTFOLIO, CONNECTION, ACCOUNT, hash, 1);

    private PlanPublicationService service(boolean conformant) {
        return new PlanPublicationService(repository, classifications, conversions, sources, portfolios, accounts,
                restrictions, plans, new CalculationConformance(() -> conformant),
                mock(PlatformTransactionManager.class), planSyncs, planSyncAttempts, planVerifications);
    }

    /** One eligible lineage: the account may write, nothing is restricted, and every input lines up. */
    private void ready() {
        when(accounts.decide(eq(USER), eq(USER), any(AccountAccessKind.class))).thenReturn(AccessDecision.ALLOWED);
        when(plans.activePlan(USER)).thenReturn(Optional.empty());
        when(sources.snapshotConnection(USER)).thenReturn(Optional.of(
                new SourceConnectionApi.SnapshotConnection(CONNECTION, ACCOUNT, "bybit-readonly-uta-1.0")));
        when(portfolios.activeSource(USER)).thenReturn(Optional.of(
                new PortfolioMembershipApi.ActiveSource(PORTFOLIO, MEMBERSHIP, CONNECTION, ACCOUNT)));
        when(plans.publishableTerms(USER, INTENT)).thenReturn(Optional.of(terms));
        when(repository.databaseNow()).thenReturn(TC.plusSeconds(60));
        when(classifications.latestEligibleCandidate(USER, INTENT, PORTFOLIO, CONNECTION, ACCOUNT))
                .thenReturn(Optional.of(CANDIDATE));
        when(repository.candidate(USER, CANDIDATE)).thenReturn(Optional.of(candidate));
        when(conversions.eligibleClassification(USER, INTENT, PORTFOLIO, CONNECTION, ACCOUNT))
                .thenReturn(Optional.of(classification));
        when(repository.conversionInProgress(USER, CLASSIFICATION)).thenReturn(false);
        when(repository.currentConversion(USER, CLASSIFICATION)).thenReturn(Optional.of(conversion));
        when(repository.flows(USER, CONVERSION)).thenReturn(List.of());
        when(portfolios.officialValue(USER)).thenReturn(Optional.of(pointer));
    }

    @Test
    void aNonConformantBuildRefusesAdmissionAndSaysSoInTheState() {
        ready();
        PlanPublicationService service = service(false);

        assertThat(service.admit(USER, INTENT, KEY))
                .isEqualTo(new PublicationAdmission.Refused("CALCULATION_POLICY_UNVERIFIED"));
        verify(repository, never()).insert(any(), any(), anyString());
        assertThat(service.state(USER, INTENT))
                .isEqualTo(new PublicationState(true, null, false, "CALCULATION_POLICY_UNVERIFIED"));
        // The conformant build admits the same inputs: the refusal above is the conformance, not the data.
        assertThat(service(true).state(USER, INTENT)).isEqualTo(new PublicationState(true, null, true, null));
    }

    @Test
    void aRunningAttemptOfANonConformantBuildFailsWithItsClosedCode() {
        ready();
        when(repository.lockOwned(lease)).thenReturn(true);
        when(repository.pinned(lease)).thenReturn(new Pinned(JOB, USER, PORTFOLIO, INTENT, MEMBERSHIP, CONNECTION,
                ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE, BASIS_SNAPSHOT, 1, CANDIDATE, CLASSIFICATION, CONVERSION, "1000",
                T0, END, "DAILY", "0.1", TC, "1250", TC, 0, hash, "CALC-1.0", "CALENDAR-SEOUL-1.0"));

        service(false).run(lease, () -> true);

        verify(repository).finish(lease, "FAILED", "CALCULATION_POLICY_UNVERIFIED");
        verify(plans, never()).lockPlanWrites(any());
    }

    /**
     * Two tabs race; this one's insert meets the one-attempt-in-progress index, and by the time it reads
     * again the winner has already finished. It is shown the winner's attempt, not "inputs not ready".
     */
    @Test
    void aTabThatLosesToAnAlreadyFinishedWinnerIsShownTheWinnersAttempt() {
        ready();
        PublicationJob winner = new PublicationJob(UUID.randomUUID(), "PUBLISHED", null, false, UUID.randomUUID(),
                TC, TC.plusSeconds(1));
        when(repository.byKey(USER, KEY)).thenReturn(Optional.empty());
        when(repository.active(USER, INTENT)).thenReturn(Optional.empty());
        when(repository.insert(any(), any(), eq(KEY))).thenReturn(0);
        when(repository.latest(USER, INTENT)).thenReturn(Optional.of(winner));

        assertThat(service(true).admit(USER, INTENT, KEY)).isEqualTo(new PublicationAdmission.Accepted(winner));
    }

    /**
     * A verification that throws for this attempt's data must not roll the claim back to QUEUED — it would
     * be the oldest attempt again on the next tick and block every later one. It is finished as BLOCKED.
     */
    @Test
    void aClaimWhoseVerificationThrowsIsFinishedInsteadOfBlockingTheQueue() {
        when(repository.claim()).thenReturn(Optional.of(lease));
        when(repository.lockOwned(lease)).thenReturn(true);
        when(accounts.decide(eq(USER), eq(USER), any(AccountAccessKind.class)))
                .thenThrow(new IllegalStateException("deterministic failure for this attempt"));

        assertThat(service(true).claim()).isEmpty();
        verify(repository).finish(lease, "BLOCKED", "INPUTS_CHANGED");
    }

    @Test
    void anAdmissionWithoutTheCallersKeyIsRefusedBeforeAnythingIsRead() {
        PlanPublicationService service = service(true);
        assertThat(service.admit(USER, INTENT, null))
                .isEqualTo(new PublicationAdmission.Refused("IDEMPOTENCY_KEY_REQUIRED"));
        assertThat(service.admit(USER, INTENT, "short"))
                .isEqualTo(new PublicationAdmission.Refused("IDEMPOTENCY_KEY_REQUIRED"));
        verify(repository, never()).byKey(any(), anyString());
    }

    /** Field boundaries are length-prefixed: a value containing the next field's name cannot shift them. */
    @Test
    void theInputHashCannotBeCollidedBySeparatorsInsideValues() {
        Flow shifted = new Flow(CANDIDATE, "x;flowSemantic=y", "z", TC, "100", false, "DIRECT_NORMALIZATION");
        Flow original = new Flow(CANDIDATE, "x", "y;flowSemantic=z", TC, "100", false, "DIRECT_NORMALIZATION");
        String a = PlanPublicationService.inputHash(terms, candidate, classification, conversion, List.of(shifted),
                BASIS_SNAPSHOT, 1);
        String b = PlanPublicationService.inputHash(terms, candidate, classification, conversion, List.of(original),
                BASIS_SNAPSHOT, 1);
        assertThat(a).isNotEqualTo(b);
        // The same value in another notation is the same input.
        Flow notation = new Flow(CANDIDATE, "x", "y;flowSemantic=z", TC, "100.00", false, "DIRECT_NORMALIZATION");
        assertThat(PlanPublicationService.inputHash(terms, candidate, classification, conversion, List.of(notation),
                BASIS_SNAPSHOT, 1)).isEqualTo(b);
    }

    // ---------------------------------------------------------------- Story 2.8 dashboard

    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID CALCULATION = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final UUID CURRENT_SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-00000000000f");

    private final PlanPublicationPort.ActivePlan plan = new PlanPublicationPort.ActivePlan(PLAN, INTENT, PORTFOLIO,
            "1000", T0, END, "DAILY", "0.1", TC);
    private final PlanPublicationRepository.CalculationView calculation = new PlanPublicationRepository.CalculationView(
            PLAN, PORTFOLIO, CURRENT_SNAPSHOT, 2, BASIS_SNAPSHOT, "1000", T0, END, "DAILY", "0.1", TC, TC, TC,
            "CALC-1.0", "CALENDAR-SEOUL-1.0", "DISPLAY-1.0", "BYBIT-PRICE-1.0", "impl", "a".repeat(64), "b".repeat(64),
            0, 0, false, "CONFIRMED");
    private final List<DashboardMetric> metrics = List.of(new DashboardMetric("CURRENT_VALUE", "1250", "OFFICIAL", null),
            new DashboardMetric("PLAN_AMOUNT", "1210", "OFFICIAL", null),
            new DashboardMetric("PLAN_DIFFERENCE", "40", "OFFICIAL", null),
            new DashboardMetric("ADJUSTED_TARGET", "1331", "OFFICIAL", null),
            new DashboardMetric("REQUIRED_RATE", "0.0648", "OFFICIAL", null),
            new DashboardMetric("GOAL_PROGRESS", "0.75", "OFFICIAL", null));

    /** A consistent bundle behind the pointer. */
    private void bundle() {
        when(plans.activePlan(USER)).thenReturn(Optional.of(plan));
        when(repository.pointer(USER, PLAN)).thenReturn(Optional.of(
                new PlanPublicationRepository.Pointer(PORTFOLIO, CALCULATION, 1, TC)));
        when(repository.calculation(USER, CALCULATION)).thenReturn(Optional.of(calculation));
        when(portfolios.officialValue(USER)).thenReturn(Optional.of(new PortfolioMembershipApi.OfficialValue("1250",
                "USD", TC, TC, TC, CURRENT_SNAPSHOT, 2)));
        when(repository.metrics(CALCULATION)).thenReturn(metrics);
        when(repository.freshness(USER, TC, TC)).thenReturn(new DashboardTrust.Freshness(false, true, true));
        when(repository.lineage(USER, CALCULATION)).thenReturn(Optional.of(lineageRow("BYBIT")));
        when(repository.history(USER, PLAN)).thenReturn(List.of(
                new PlanPublicationRepository.HistoryRow(1, CALCULATION, PORTFOLIO, TC)));
        var facts = new PlanSyncService.Facts(TC, false, null, false, null, null, null);
        when(planSyncs.facts(eq(USER), any())).thenReturn(facts);
        when(planSyncs.state(facts)).thenReturn(
                new com.bokrilog.sync.SyncJobApi.SyncState("AVAILABLE", null, null, null));
        when(planVerifications.state(eq(USER), any(), eq(facts))).thenReturn(
                new com.bokrilog.sync.SyncJobApi.VerificationState("AVAILABLE", null, null, "USER_REQUEST", null, null,
                        null));
        when(planVerifications.change(eq(USER), any())).thenReturn(new com.bokrilog.sync.SyncJobApi.VersionChange(
                "NONE", null, null, 0, 0, 0, 0, null, null, List.of(), List.of()));
    }

    @Test
    void theDashboardIsReadInOneReadOnlyRepeatableReadTransaction() {
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED);
        bundle();
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        PlanPublicationService service = new PlanPublicationService(repository, classifications, conversions, sources,
                portfolios, accounts, restrictions, plans, new CalculationConformance(() -> true), manager, planSyncs,
                planSyncAttempts, planVerifications);

        assertThat(service.dashboard(USER).dashboard()).isInstanceOf(Dashboard.Ready.class);
        assertThat(service.dashboard(USER).consistency()).isEqualTo(BundleConsistency.CONSISTENT);
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager, org.mockito.Mockito.times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).hasSize(2).allSatisfy(definition -> {
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        });
    }

    @Test
    void aDeletionThatBeginsDuringTheReadDropsTheBundle() {
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED,
                AccessDecision.DENIED);
        bundle();

        // The bundle was read (and was consistent); the second decision refused it.
        assertThat(service(true).dashboard(USER))
                .isEqualTo(new DashboardRead(new Dashboard.NotPermitted(), BundleConsistency.CONSISTENT));
        verify(repository).metrics(CALCULATION);
        verify(accounts, org.mockito.Mockito.times(2)).decide(USER, USER, AccountAccessKind.SENSITIVE);
    }

    @Test
    void aDeletionBeforeTheReadReadsNothing() {
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.DENIED);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        PlanPublicationService service = new PlanPublicationService(repository, classifications, conversions, sources,
                portfolios, accounts, restrictions, plans, new CalculationConformance(() -> true), manager, planSyncs,
                planSyncAttempts, planVerifications);

        // No bundle was read, so nothing is known about its consistency.
        assertThat(service.dashboard(USER))
                .isEqualTo(new DashboardRead(new Dashboard.NotPermitted(), BundleConsistency.NOT_READ));
        assertThat(service.dashboard(null))
                .isEqualTo(new DashboardRead(new Dashboard.NotPermitted(), BundleConsistency.NOT_READ));
        verify(manager, never()).getTransaction(any());
        verify(plans, never()).activePlan(any());
    }

    @Test
    void noPlanIsPreparingOnlyWhileAPublicationRuns() {
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED);
        when(plans.activePlan(USER)).thenReturn(Optional.empty());
        when(repository.publicationInProgress(USER)).thenReturn(true, false);

        assertThat(service(true).dashboard(USER).dashboard()).isEqualTo(new Dashboard.Preparing("WAIT_FOR_RESULT"));
        assertThat(service(true).dashboard(USER).dashboard()).isEqualTo(new Dashboard.NoPlan("CREATE_PLAN"));
    }

    /**
     * A write restriction does not hide the result: only the account's own access gates the read. The registry is
     * asked one thing only — whether the plan end is restricted (Story 2.13) — and that only says the plan cannot be
     * ended now; the bundle is the same.
     */
    @Test
    void aCommandRestrictionDoesNotHideTheDashboard() {
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED);
        when(restrictions.isRestricted(anyString())).thenReturn(true);
        bundle();

        var dashboard = service(true).dashboard(USER).dashboard();
        assertThat(dashboard).isInstanceOf(Dashboard.Ready.class);
        assertThat(((Dashboard.Ready) dashboard).planEnd())
                .isEqualTo(new com.bokrilog.sync.SyncJobApi.PlanEndAvailability("UNAVAILABLE", "RESTRICTED"));
        verify(restrictions).isRestricted("plan-end");
        verify(restrictions, never()).isRestricted(org.mockito.ArgumentMatchers.argThat(command ->
                !"plan-end".equals(command)));
    }

    // ---------------------------------------------------------------- Story 2.13 the ended plan's record

    private static final Instant ENDED_AT = TC.plusSeconds(120);

    private static PlanPublicationPort.PlanEnd end(long version, UUID calculation, UUID snapshot, Instant measured,
            Instant verified, Instant endedAt) {
        return new PlanPublicationPort.PlanEnd("USER_REQUEST", endedAt, calculation, version, snapshot, measured,
                verified, "NOT_APPLICABLE", null, null);
    }

    private final PlanPublicationPort.PlanEnd consistentEnd = end(1, CALCULATION, CURRENT_SNAPSHOT, TC, TC, ENDED_AT);

    /** An ended plan's bundle behind its pointer; the portfolio's current pointer has moved on (a following plan). */
    private void endedBundle(PlanPublicationPort.@org.jspecify.annotations.Nullable PlanEnd end, String status) {
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED);
        when(plans.activePlan(USER)).thenReturn(Optional.empty());
        when(repository.publicationInProgress(USER)).thenReturn(false);
        when(plans.latestPlan(USER)).thenReturn(Optional.of(new PlanPublicationPort.LatestPlan(plan, status, end)));
        when(repository.pointer(USER, PLAN)).thenReturn(Optional.of(
                new PlanPublicationRepository.Pointer(PORTFOLIO, CALCULATION, 1, TC)));
        when(repository.calculation(USER, CALCULATION)).thenReturn(Optional.of(calculation));
        when(portfolios.officialValue(USER)).thenReturn(Optional.of(new PortfolioMembershipApi.OfficialValue("9999",
                "USD", ENDED_AT, ENDED_AT, ENDED_AT, UUID.randomUUID(), 7)));
        when(repository.metrics(CALCULATION)).thenReturn(metrics);
        when(repository.lineage(USER, CALCULATION)).thenReturn(Optional.of(lineageRow("BYBIT")));
    }

    /**
     * Without an active plan or a publication in progress, the most recent plan that ended early is shown as its
     * record: its last official bundle, the plan status ENDED_EARLY, freshness ENDED and the end facts — and no sync,
     * verification or change part is read for it, nor the portfolio's current pointer compared.
     */
    @Test
    void anEndedPlanIsShownAsItsReadOnlyRecord() {
        endedBundle(consistentEnd, "ENDED_EARLY");

        var dashboard = service(true).dashboard(USER);
        assertThat(dashboard.consistency()).isEqualTo(BundleConsistency.CONSISTENT);
        assertThat(dashboard.dashboard()).isInstanceOf(Dashboard.Ended.class);
        var ended = (Dashboard.Ended) dashboard.dashboard();
        assertThat(ended.nextAction()).isEqualTo("PREPARE_NEXT_PLAN");
        assertThat(ended.result().planStatus()).isEqualTo("ENDED_EARLY");
        assertThat(ended.result().calculationId()).isEqualTo(CALCULATION);
        assertThat(ended.result().measuredAt()).isEqualTo(TC);
        assertThat(ended.result().trust().freshness().state()).isEqualTo("ENDED");
        assertThat(ended.result().trust().freshness().cause()).isEqualTo("PLAN_ENDED_EARLY");
        assertThat(ended.end()).isEqualTo(new com.bokrilog.sync.SyncJobApi.PlanEndRecord("USER_REQUEST", ENDED_AT, END,
                TC, 120_000, "NOT_APPLICABLE", null, null));
        verify(planSyncs, never()).facts(any(), any());
        verify(planVerifications, never()).state(any(), any(), any());
        verify(repository, never()).freshness(any(), any(), any());
        verify(restrictions, never()).isRestricted(anyString());
    }

    /**
     * B1: a persistent verification review of the ended version itself (Story 2.12) still holds the record's input
     * reliability — the state and cause the ready dashboard showed, with no next action; nothing else of sync reaches it.
     */
    @Test
    void anEndedRecordKeepsItsVersionsPersistentReview() {
        endedBundle(consistentEnd, "ENDED_EARLY");
        when(planSyncAttempts.verificationReview(USER, PLAN, CALCULATION)).thenReturn(Optional.of(
                new PlanSyncRepository.Review(UUID.randomUUID(), "SOURCE_EVENT_MISSING")));

        var reliability = ((Dashboard.Ended) service(true).dashboard(USER).dashboard()).result().trust()
                .inputReliability();
        assertThat(reliability.state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(reliability.cause()).isEqualTo("VERIFICATION_REVIEW_REQUIRED");
        assertThat(reliability.nextAction()).isEqualTo("NONE");
        verify(planSyncs, never()).facts(any(), any());

        when(planSyncAttempts.verificationReview(USER, PLAN, CALCULATION)).thenReturn(Optional.of(
                new PlanSyncRepository.Review(UUID.randomUUID(), "HISTORY_OUT_OF_RANGE")));
        var unusable = ((Dashboard.Ended) service(true).dashboard(USER).dashboard()).result().trust()
                .inputReliability();
        assertThat(unusable.state()).isEqualTo("UNUSABLE");
        assertThat(unusable.cause()).isEqualTo("HISTORY_OUT_OF_RANGE");
    }

    /** A long-gap end carries its uncollected range (verified, end]. */
    @Test
    void aLongGapEndCarriesItsUncollectedRange() {
        endedBundle(new PlanPublicationPort.PlanEnd("LONG_GAP_NEW_PLAN", ENDED_AT, CALCULATION, 1, CURRENT_SNAPSHOT, TC,
                TC, "UNAVAILABLE_DATA_GAP", TC, ENDED_AT), "ENDED_EARLY");

        var ended = (Dashboard.Ended) service(true).dashboard(USER).dashboard();
        assertThat(ended.end()).isEqualTo(new com.bokrilog.sync.SyncJobApi.PlanEndRecord("LONG_GAP_NEW_PLAN", ENDED_AT,
                END, TC, 120_000, "UNAVAILABLE_DATA_GAP", TC, ENDED_AT));
    }

    /**
     * The record is shown only when the end agrees with the pointer and the version: the same version and calculation,
     * the version's snapshot, both times unchanged, an end after them and before T, and a shape that goes with the
     * reason. Anything else — and an ended plan without its record, or a status this story does not know — is the
     * same "unavailable", never a record in part.
     */
    @Test
    void anEndThatDisagreesWithItsVersionIsUnavailable() {
        for (var disagreeing : List.of(
                end(2, CALCULATION, CURRENT_SNAPSHOT, TC, TC, ENDED_AT),
                end(1, UUID.randomUUID(), CURRENT_SNAPSHOT, TC, TC, ENDED_AT),
                end(1, CALCULATION, UUID.randomUUID(), TC, TC, ENDED_AT),
                end(1, CALCULATION, CURRENT_SNAPSHOT, TC.minusMillis(1), TC, ENDED_AT),
                end(1, CALCULATION, CURRENT_SNAPSHOT, TC, TC.minusMillis(1), ENDED_AT),
                end(1, CALCULATION, CURRENT_SNAPSHOT, TC, TC, END),
                new PlanPublicationPort.PlanEnd("USER_REQUEST", ENDED_AT, CALCULATION, 1, CURRENT_SNAPSHOT, TC, TC,
                        "UNAVAILABLE_DATA_GAP", null, null),
                new PlanPublicationPort.PlanEnd("LONG_GAP_NEW_PLAN", ENDED_AT, CALCULATION, 1, CURRENT_SNAPSHOT, TC, TC,
                        "UNAVAILABLE_DATA_GAP", TC.minusMillis(1), ENDED_AT),
                new PlanPublicationPort.PlanEnd("COMPLETED", ENDED_AT, CALCULATION, 1, CURRENT_SNAPSHOT, TC, TC,
                        "NOT_APPLICABLE", null, null),
                // A long-gap end whose range does not end at the end, or whose verification is the end itself.
                new PlanPublicationPort.PlanEnd("LONG_GAP_NEW_PLAN", ENDED_AT, CALCULATION, 1, CURRENT_SNAPSHOT, TC, TC,
                        "UNAVAILABLE_DATA_GAP", TC, ENDED_AT.minusMillis(1)),
                new PlanPublicationPort.PlanEnd("LONG_GAP_NEW_PLAN", TC, CALCULATION, 1, CURRENT_SNAPSHOT, TC, TC,
                        "UNAVAILABLE_DATA_GAP", TC, TC),
                // An early end with an uncollected range.
                new PlanPublicationPort.PlanEnd("USER_REQUEST", ENDED_AT, CALCULATION, 1, CURRENT_SNAPSHOT, TC, TC,
                        "NOT_APPLICABLE", TC, ENDED_AT))) {
            endedBundle(disagreeing, "ENDED_EARLY");
            var read = service(true).dashboard(USER);
            assertThat(read.dashboard()).as(disagreeing.toString()).isEqualTo(new Dashboard.Unavailable());
            assertThat(read.consistency()).isEqualTo(BundleConsistency.INCONSISTENT);
        }
        // An end before the pointer's publication: the pointer was published after the end, so it is not the one ended on.
        endedBundle(consistentEnd, "ENDED_EARLY");
        when(repository.pointer(USER, PLAN)).thenReturn(Optional.of(
                new PlanPublicationRepository.Pointer(PORTFOLIO, CALCULATION, 1, ENDED_AT.plusMillis(1))));
        assertThat(service(true).dashboard(USER).dashboard()).isEqualTo(new Dashboard.Unavailable());
        endedBundle(null, "ENDED_EARLY");
        assertThat(service(true).dashboard(USER).dashboard()).isEqualTo(new Dashboard.Unavailable());
        endedBundle(consistentEnd, "ACTIVE");
        assertThat(service(true).dashboard(USER).dashboard()).isEqualTo(new Dashboard.Unavailable());
    }

    /** Story 2.13: a ready plan's end availability, by the restriction, T and the long gap of the same read. */
    @Test
    void aReadyPlanSaysWhetherItCanBeEndedNow() {
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED);
        bundle();
        var longGap = new PlanSyncService.Facts(TC, false, null, true, null, null, null);
        when(planSyncs.facts(eq(USER), any())).thenReturn(longGap);
        when(planSyncs.state(longGap)).thenReturn(
                new com.bokrilog.sync.SyncJobApi.SyncState("LONG_GAP", null, null, null));
        when(planVerifications.state(eq(USER), any(), eq(longGap))).thenReturn(
                new com.bokrilog.sync.SyncJobApi.VerificationState("LONG_GAP", null, null, null, null, TC, null));

        var ready = (Dashboard.Ready) service(true).dashboard(USER).dashboard();
        assertThat(ready.planEnd()).isEqualTo(new com.bokrilog.sync.SyncJobApi.PlanEndAvailability("NEW_PLAN_CHOICE",
                null));
        assertThat(ready.result().planStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void aVersionWithAnUnsupportedReliabilityOrCharacterIsNotReturnedInPart() {
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED);
        bundle();
        when(repository.calculation(USER, CALCULATION)).thenReturn(Optional.of(new PlanPublicationRepository.CalculationView(
                PLAN, PORTFOLIO, CURRENT_SNAPSHOT, 2, BASIS_SNAPSHOT, "1000", T0, END, "DAILY", "0.1", TC, TC, TC,
                "CALC-1.0", "CALENDAR-SEOUL-1.0", "DISPLAY-1.0", "BYBIT-PRICE-1.0", "impl", "a".repeat(64),
                "b".repeat(64), 0, 0, false, "UNUSABLE")));
        assertThat(service(true).dashboard(USER))
                .isEqualTo(new DashboardRead(new Dashboard.Unavailable(), BundleConsistency.INCONSISTENT));

        // An estimated version whose derived metrics say official does not agree with itself either.
        when(repository.calculation(USER, CALCULATION)).thenReturn(Optional.of(new PlanPublicationRepository.CalculationView(
                PLAN, PORTFOLIO, CURRENT_SNAPSHOT, 2, BASIS_SNAPSHOT, "1000", T0, END, "DAILY", "0.1", TC, TC, TC,
                "CALC-1.0", "CALENDAR-SEOUL-1.0", "DISPLAY-1.0", "BYBIT-PRICE-1.0", "impl", "a".repeat(64),
                "b".repeat(64), 1, 1, true, "CONFIRMED")));
        assertThat(service(true).dashboard(USER).dashboard()).isEqualTo(new Dashboard.Unavailable());
        // A notation difference in the plan terms is not a disagreement.
        when(plans.activePlan(USER)).thenReturn(Optional.of(new PlanPublicationPort.ActivePlan(PLAN, INTENT, PORTFOLIO,
                "1000.00", T0, END, "DAILY", "0.10", TC)));
        when(repository.calculation(USER, CALCULATION)).thenReturn(Optional.of(calculation));
        assertThat(service(true).dashboard(USER).dashboard()).isInstanceOf(Dashboard.Ready.class);
    }

    /** A read that fails is not turned into an answer here: the HTTP surface answers and logs it. */
    @Test
    void aReadThatThrowsPropagatesInsteadOfAnsweringInPart() {
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED);
        bundle();
        when(repository.pointer(USER, PLAN)).thenThrow(new IllegalStateException("injected pointer failure"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(true).dashboard(USER))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).metrics(any());
    }

    // ---------------------------------------------------------------- Story 2.9 calculation evidence

    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final EvidenceReference REFERENCE = new EvidenceReference(PLAN, 1, CALCULATION);
    /** The connection's policy as the first snapshot recorded it: distinct here, to prove where it comes from. */
    private static final String CONNECTION_POLICY = "bybit-connection-policy-0.9";
    private static final String SOURCE_POLICY = "bybit-readonly-uta-1.0";

    private static PlanPublicationRepository.Lineage lineageRow(String provider) {
        return new PlanPublicationRepository.Lineage(JOB, provider, ACCOUNT, CONNECTION, MEMBERSHIP, BASIS_SOURCE, SOURCE,
                CANDIDATE, CLASSIFICATION, CONVERSION, "CASHFLOW-1.0", SOURCE_POLICY, "DECIMAL128-HALF_EVEN",
                "Asia/Seoul", TC, TC, null);
    }

    /** The inputs the creating attempt pinned: exactly the version's, advancing the pointer from the basis. */
    private static Pinned pinnedRow(int flowCount) {
        return new Pinned(JOB, USER, PORTFOLIO, INTENT, MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE,
                BASIS_SNAPSHOT, 1, CANDIDATE, CLASSIFICATION, CONVERSION, "1000", T0, END, "DAILY", "0.1", TC, "1250", TC,
                flowCount, "f".repeat(64), "CALC-1.0", "CALENDAR-SEOUL-1.0");
    }

    private static PlanPublicationRepository.CreatingAttempt attempt(Pinned pinned) {
        return new PlanPublicationRepository.CreatingAttempt("PUBLISHED", PLAN, CALCULATION, pinned);
    }

    private static PlanPublicationRepository.SourceOrigin tcSource(UUID candidateId, UUID connection, UUID account,
            Instant measuredAt, String policy) {
        return new PlanPublicationRepository.SourceOrigin(candidateId, connection, account, measuredAt, policy);
    }

    private static Candidate candidateRow(UUID portfolio, UUID connection, UUID account, Instant t0, Instant tc,
            Instant measuredAt, String policy) {
        return new Candidate(CANDIDATE, USER, portfolio, INTENT, connection, account, t0, tc, "1250", measuredAt, TC,
                policy, "a".repeat(64), null, null);
    }

    private static PlanPublicationRepository.ClassificationBundle classificationRow(UUID intent, UUID portfolio,
            UUID connection) {
        return new PlanPublicationRepository.ClassificationBundle(CANDIDATE, portfolio, intent, connection, ACCOUNT,
                "COMPLETE", "CASHFLOW-1.0", "b".repeat(64));
    }

    private static Conversion conversionRow(String status, UUID intent, UUID portfolio, UUID connection, UUID account,
            String policy, int flowCount) {
        return new Conversion(CONVERSION, CLASSIFICATION, USER, intent, portfolio, connection, account, status, true,
                "c".repeat(64), policy, flowCount, 0);
    }

    /** The current pointer's whole lineage: every row the version recorded exists and agrees with it. */
    private void lineage() {
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED);
        bundle();
        when(repository.lineage(USER, CALCULATION)).thenReturn(Optional.of(lineageRow("BYBIT")));
        when(repository.creatingAttempt(USER, JOB)).thenReturn(Optional.of(attempt(pinnedRow(0))));
        when(repository.sourceOrigin(USER, SOURCE)).thenReturn(Optional.of(
                tcSource(CANDIDATE, CONNECTION, ACCOUNT, TC, SOURCE_POLICY)));
        when(repository.sourceOrigin(USER, BASIS_SOURCE)).thenReturn(Optional.of(
                tcSource(null, CONNECTION, ACCOUNT, T0, CONNECTION_POLICY)));
        when(repository.candidate(USER, CANDIDATE)).thenReturn(Optional.of(
                candidateRow(PORTFOLIO, CONNECTION, ACCOUNT, T0, TC, TC, SOURCE_POLICY)));
        when(repository.collectionRange(USER, CANDIDATE)).thenReturn(Optional.of(
                new PlanPublicationRepository.CollectionRange(T0, TC)));
        when(repository.classificationBundle(USER, CLASSIFICATION)).thenReturn(Optional.of(
                classificationRow(INTENT, PORTFOLIO, CONNECTION)));
        when(repository.conversion(USER, CONVERSION)).thenReturn(Optional.of(
                conversionRow("CONVERTED", INTENT, PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 0)));
        when(repository.appliedFlowCounts(CALCULATION, CONVERSION)).thenReturn(
                new PlanPublicationRepository.AppliedFlowCounts(0, 0, 0, List.of()));
        when(repository.unpublished(USER, INTENT)).thenReturn(List.of(
                new CalculationEvidence.UnpublishedAttempt("FAILED", "PUBLICATION_INTERRUPTED", TC)));
    }

    /** An estimated version (two applied flows, one estimated) with an undefined required rate. */
    private void estimatedLineage(List<String> estimatedEvidence) {
        lineage();
        when(repository.calculation(USER, CALCULATION)).thenReturn(Optional.of(new PlanPublicationRepository.CalculationView(
                PLAN, PORTFOLIO, CURRENT_SNAPSHOT, 2, BASIS_SNAPSHOT, "1000", T0, END, "DAILY", "0.1", TC, TC, TC,
                "CALC-1.0", "CALENDAR-SEOUL-1.0", "DISPLAY-1.0", "BYBIT-PRICE-1.0", "impl", "a".repeat(64),
                "b".repeat(64), 2, 1, true, "CONFIRMED")));
        when(repository.metrics(CALCULATION)).thenReturn(List.of(new DashboardMetric("CURRENT_VALUE", "1250", "OFFICIAL", null),
                new DashboardMetric("PLAN_AMOUNT", "1210", "ESTIMATED", null),
                new DashboardMetric("PLAN_DIFFERENCE", "40", "ESTIMATED", null),
                new DashboardMetric("ADJUSTED_TARGET", "1331", "ESTIMATED", null),
                new DashboardMetric("REQUIRED_RATE", null, "ESTIMATED", "CURRENT_VALUE_NOT_POSITIVE"),
                new DashboardMetric("GOAL_PROGRESS", "0.75", "ESTIMATED", null)));
        when(repository.creatingAttempt(USER, JOB)).thenReturn(Optional.of(attempt(pinnedRow(2))));
        when(repository.conversion(USER, CONVERSION)).thenReturn(Optional.of(
                conversionRow("CONVERTED_ESTIMATED", INTENT, PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 2)));
        when(repository.appliedFlowCounts(CALCULATION, CONVERSION)).thenReturn(
                new PlanPublicationRepository.AppliedFlowCounts(2, 1, 0, estimatedEvidence));
    }

    private static CalculationEvidence found(EvidenceRead read) {
        assertThat(read.consistency()).isEqualTo(BundleConsistency.CONSISTENT);
        assertThat(read.outcome()).isInstanceOf(EvidenceOutcome.Found.class);
        return ((EvidenceOutcome.Found) read.outcome()).evidence();
    }

    @Test
    void theEvidenceIsReadInOneReadOnlyRepeatableReadTransaction() {
        lineage();
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        PlanPublicationService service = new PlanPublicationService(repository, classifications, conversions, sources,
                portfolios, accounts, restrictions, plans, new CalculationConformance(() -> true), manager, planSyncs,
                planSyncAttempts, planVerifications);

        var evidence = found(service.evidence(USER, REFERENCE));
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definitions.capture());
        assertThat(definitions.getValue().isReadOnly()).isTrue();
        assertThat(definitions.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // The whole lineage, each part from the row the version recorded.
        assertThat(evidence.snapshots()).isEqualTo(new CalculationEvidence.Snapshots(CURRENT_SNAPSHOT, 2, BASIS_SNAPSHOT,
                BASIS_SOURCE, SOURCE));
        assertThat(evidence.times()).isEqualTo(new CalculationEvidence.Times(T0, TC, TC, TC));
        // The connection's policy is the first snapshot's record of it, not the collection policy.
        assertThat(evidence.connection()).isEqualTo(new CalculationEvidence.Connection("BYBIT", CONNECTION,
                CONNECTION_POLICY));
        assertThat(evidence.sourceRange()).isEqualTo(new CalculationEvidence.SourceRange(T0, TC, T0, SOURCE_POLICY,
                "a".repeat(64)));
        assertThat(evidence.classification()).isEqualTo(new CalculationEvidence.Classification(CLASSIFICATION,
                "CASHFLOW-1.0", "b".repeat(64)));
        assertThat(evidence.conversion()).isEqualTo(new CalculationEvidence.Conversion(CONVERSION, "BYBIT-PRICE-1.0",
                "c".repeat(64)));
        assertThat(evidence.plan()).isEqualTo(new CalculationEvidence.Plan(PLAN, "1000", T0, END, "DAILY", "0.1"));
        assertThat(evidence.calculation()).isEqualTo(new CalculationEvidence.Calculation(CALCULATION, "CALC-1.0",
                "CALENDAR-SEOUL-1.0", "DISPLAY-1.0", "DECIMAL128-HALF_EVEN", "Asia/Seoul", "impl", "a".repeat(64),
                "b".repeat(64), TC));
        assertThat(evidence.officialVersions()).containsExactly(new CalculationEvidence.OfficialVersion(1, CALCULATION,
                TC, null, true));
        assertThat(evidence.unpublishedAttempts()).containsExactly(
                new CalculationEvidence.UnpublishedAttempt("FAILED", "PUBLICATION_INTERRUPTED", TC));
        assertThat(evidence.metrics()).allSatisfy(metric -> assertThat(metric.estimatedCause()).isNull());
    }

    /** Only the current pointer to the letter is found; nothing is looked up by a reference that is not it. */
    @Test
    void aReferenceThatIsNotTheCurrentPointerIsNotFoundAndReadsNoLineage() {
        lineage();
        var notFound = new EvidenceRead(new EvidenceOutcome.NotFound(), BundleConsistency.NOT_READ);
        UUID other = UUID.randomUUID();
        for (EvidenceReference reference : List.of(new EvidenceReference(other, 1, CALCULATION),
                new EvidenceReference(PLAN, 2, CALCULATION), new EvidenceReference(PLAN, 1, other))) {
            assertThat(service(true).evidence(USER, reference)).as(reference.toString()).isEqualTo(notFound);
        }
        assertThat(service(true).evidence(USER, null)).as("missing or malformed").isEqualTo(notFound);
        when(repository.pointer(USER, PLAN)).thenReturn(Optional.empty());
        assertThat(service(true).evidence(USER, REFERENCE)).as("an active plan without a pointer").isEqualTo(notFound);
        when(plans.activePlan(USER)).thenReturn(Optional.empty());
        assertThat(service(true).evidence(USER, REFERENCE)).as("no active plan").isEqualTo(notFound);
        verify(repository, never()).calculation(any(), any());
        verify(repository, never()).lineage(any(), any());
        verify(repository, never()).unpublished(any(), any());
    }

    @Test
    void aDeletionOnEitherSideOfTheEvidenceReadRefusesIt() {
        lineage();
        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.ALLOWED,
                AccessDecision.DENIED);
        assertThat(service(true).evidence(USER, REFERENCE))
                .isEqualTo(new EvidenceRead(new EvidenceOutcome.NotPermitted(), BundleConsistency.CONSISTENT));

        when(accounts.decide(USER, USER, AccountAccessKind.SENSITIVE)).thenReturn(AccessDecision.DENIED);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        PlanPublicationService service = new PlanPublicationService(repository, classifications, conversions, sources,
                portfolios, accounts, restrictions, plans, new CalculationConformance(() -> true), manager, planSyncs,
                planSyncAttempts, planVerifications);
        var refused = new EvidenceRead(new EvidenceOutcome.NotPermitted(), BundleConsistency.NOT_READ);
        assertThat(service.evidence(USER, REFERENCE)).isEqualTo(refused);
        // The refusal comes first: a malformed reference does not tell a deleting account "not found".
        assertThat(service.evidence(USER, null)).isEqualTo(refused);
        assertThat(service.evidence(null, REFERENCE)).isEqualTo(refused);
        verify(manager, never()).getTransaction(any());
    }

    private record Break(String name, Runnable apply) {}

    /**
     * Each break changes exactly one condition of an otherwise whole lineage, and each alone makes the whole
     * lineage unavailable — nothing is returned in part. Before every break the whole lineage is found.
     */
    @Test
    void eachLineageConditionAloneBreaksTheWholeLineage() {
        UUID stranger = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        var unavailable = new EvidenceRead(new EvidenceOutcome.Unavailable(), BundleConsistency.INCONSISTENT);
        Pinned good = pinnedRow(0);
        List<Break> breaks = List.of(
                // The dashboard's own checks, on the evidence path.
                new Break("calculation missing", () -> when(repository.calculation(USER, CALCULATION))
                        .thenReturn(Optional.empty())),
                new Break("calculation of another rate", () -> when(repository.calculation(USER, CALCULATION))
                        .thenReturn(Optional.of(new PlanPublicationRepository.CalculationView(PLAN, PORTFOLIO,
                                CURRENT_SNAPSHOT, 2, BASIS_SNAPSHOT, "1000", T0, END, "DAILY", "0.2", TC, TC, TC,
                                "CALC-1.0", "CALENDAR-SEOUL-1.0", "DISPLAY-1.0", "BYBIT-PRICE-1.0", "impl",
                                "a".repeat(64), "b".repeat(64), 0, 0, false, "CONFIRMED")))),
                new Break("portfolio pointer on another snapshot", () -> when(portfolios.officialValue(USER))
                        .thenReturn(Optional.of(new PortfolioMembershipApi.OfficialValue("1250", "USD", TC, TC, TC,
                                stranger, 2)))),
                new Break("portfolio pointer at another sequence", () -> when(portfolios.officialValue(USER))
                        .thenReturn(Optional.of(new PortfolioMembershipApi.OfficialValue("1250", "USD", TC, TC, TC,
                                CURRENT_SNAPSHOT, 3)))),
                new Break("five metrics", () -> when(repository.metrics(CALCULATION)).thenReturn(metrics.subList(0, 5))),
                new Break("lineage missing", () -> when(repository.lineage(USER, CALCULATION))
                        .thenReturn(Optional.empty())),
                new Break("another provider", () -> when(repository.lineage(USER, CALCULATION))
                        .thenReturn(Optional.of(lineageRow("OTHER")))),
                // The creating attempt.
                new Break("attempt not published", () -> when(repository.creatingAttempt(USER, JOB)).thenReturn(
                        Optional.of(new PlanPublicationRepository.CreatingAttempt("FAILED", PLAN, CALCULATION, good)))),
                new Break("attempt published another plan", () -> when(repository.creatingAttempt(USER, JOB))
                        .thenReturn(Optional.of(new PlanPublicationRepository.CreatingAttempt("PUBLISHED", stranger,
                                CALCULATION, good)))),
                new Break("attempt published another calculation", () -> when(repository.creatingAttempt(USER, JOB))
                        .thenReturn(Optional.of(new PlanPublicationRepository.CreatingAttempt("PUBLISHED", PLAN,
                                stranger, good)))),
                new Break("attempt pinned another intent", () -> pinned(new Pinned(JOB, USER, PORTFOLIO, stranger,
                        MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE, BASIS_SNAPSHOT, 1, CANDIDATE,
                        CLASSIFICATION, CONVERSION, "1000", T0, END, "DAILY", "0.1", TC, "1250", TC, 0, "f".repeat(64),
                        "CALC-1.0", "CALENDAR-SEOUL-1.0"))),
                new Break("attempt pinned another membership", () -> pinned(new Pinned(JOB, USER, PORTFOLIO, INTENT,
                        stranger, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE, BASIS_SNAPSHOT, 1, CANDIDATE,
                        CLASSIFICATION, CONVERSION, "1000", T0, END, "DAILY", "0.1", TC, "1250", TC, 0, "f".repeat(64),
                        "CALC-1.0", "CALENDAR-SEOUL-1.0"))),
                new Break("attempt pinned another basis source", () -> pinned(new Pinned(JOB, USER, PORTFOLIO, INTENT,
                        MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, stranger, BASIS_SNAPSHOT, 1, CANDIDATE,
                        CLASSIFICATION, CONVERSION, "1000", T0, END, "DAILY", "0.1", TC, "1250", TC, 0, "f".repeat(64),
                        "CALC-1.0", "CALENDAR-SEOUL-1.0"))),
                new Break("attempt expected another pointer", () -> pinned(new Pinned(JOB, USER, PORTFOLIO, INTENT,
                        MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE, stranger, 1, CANDIDATE,
                        CLASSIFICATION, CONVERSION, "1000", T0, END, "DAILY", "0.1", TC, "1250", TC, 0, "f".repeat(64),
                        "CALC-1.0", "CALENDAR-SEOUL-1.0"))),
                new Break("attempt expected another sequence", () -> pinned(new Pinned(JOB, USER, PORTFOLIO, INTENT,
                        MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE, BASIS_SNAPSHOT, 2, CANDIDATE,
                        CLASSIFICATION, CONVERSION, "1000", T0, END, "DAILY", "0.1", TC, "1250", TC, 0, "f".repeat(64),
                        "CALC-1.0", "CALENDAR-SEOUL-1.0"))),
                new Break("attempt pinned another candidate", () -> pinned(new Pinned(JOB, USER, PORTFOLIO, INTENT,
                        MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE, BASIS_SNAPSHOT, 1, stranger,
                        CLASSIFICATION, CONVERSION, "1000", T0, END, "DAILY", "0.1", TC, "1250", TC, 0, "f".repeat(64),
                        "CALC-1.0", "CALENDAR-SEOUL-1.0"))),
                new Break("attempt pinned another classification", () -> pinned(new Pinned(JOB, USER, PORTFOLIO,
                        INTENT, MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE, BASIS_SNAPSHOT, 1,
                        CANDIDATE, stranger, CONVERSION, "1000", T0, END, "DAILY", "0.1", TC, "1250", TC, 0,
                        "f".repeat(64), "CALC-1.0", "CALENDAR-SEOUL-1.0"))),
                new Break("attempt pinned another conversion", () -> pinned(new Pinned(JOB, USER, PORTFOLIO, INTENT,
                        MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE, BASIS_SNAPSHOT, 1, CANDIDATE,
                        CLASSIFICATION, stranger, "1000", T0, END, "DAILY", "0.1", TC, "1250", TC, 0, "f".repeat(64),
                        "CALC-1.0", "CALENDAR-SEOUL-1.0"))),
                new Break("attempt pinned another current value", () -> pinned(new Pinned(JOB, USER, PORTFOLIO, INTENT,
                        MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE, BASIS_SNAPSHOT, 1, CANDIDATE,
                        CLASSIFICATION, CONVERSION, "1000", T0, END, "DAILY", "0.1", TC, "1251", TC, 0, "f".repeat(64),
                        "CALC-1.0", "CALENDAR-SEOUL-1.0"))),
                new Break("attempt pinned another tc", () -> pinned(new Pinned(JOB, USER, PORTFOLIO, INTENT,
                        MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SNAPSHOT, BASIS_SOURCE, BASIS_SNAPSHOT, 1, CANDIDATE,
                        CLASSIFICATION, CONVERSION, "1000", T0, END, "DAILY", "0.1", TC.plusSeconds(1), "1250", TC, 0,
                        "f".repeat(64), "CALC-1.0", "CALENDAR-SEOUL-1.0"))),
                new Break("attempt pinned another flow count", () -> pinned(pinnedRow(1))),
                // The tc source snapshot, the basis source snapshot and the collected range.
                new Break("tc source of another connection", () -> when(repository.sourceOrigin(USER, SOURCE))
                        .thenReturn(Optional.of(tcSource(CANDIDATE, stranger, ACCOUNT, TC, SOURCE_POLICY)))),
                new Break("tc source of another provider account", () -> when(repository.sourceOrigin(USER, SOURCE))
                        .thenReturn(Optional.of(tcSource(CANDIDATE, CONNECTION, stranger, TC, SOURCE_POLICY)))),
                new Break("tc source of another candidate", () -> when(repository.sourceOrigin(USER, SOURCE))
                        .thenReturn(Optional.of(tcSource(null, CONNECTION, ACCOUNT, TC, SOURCE_POLICY)))),
                new Break("tc source under another policy", () -> when(repository.sourceOrigin(USER, SOURCE))
                        .thenReturn(Optional.of(tcSource(CANDIDATE, CONNECTION, ACCOUNT, TC, "other-policy")))),
                new Break("basis source missing", () -> when(repository.sourceOrigin(USER, BASIS_SOURCE))
                        .thenReturn(Optional.empty())),
                new Break("basis source built from a candidate", () -> when(repository.sourceOrigin(USER, BASIS_SOURCE))
                        .thenReturn(Optional.of(tcSource(CANDIDATE, CONNECTION, ACCOUNT, T0, CONNECTION_POLICY)))),
                new Break("basis source of another connection", () -> when(repository.sourceOrigin(USER, BASIS_SOURCE))
                        .thenReturn(Optional.of(tcSource(null, stranger, ACCOUNT, T0, CONNECTION_POLICY)))),
                new Break("basis source of another provider account", () -> when(repository.sourceOrigin(USER,
                        BASIS_SOURCE)).thenReturn(Optional.of(tcSource(null, CONNECTION, stranger, T0, CONNECTION_POLICY)))),
                new Break("basis source measured after t0", () -> when(repository.sourceOrigin(USER, BASIS_SOURCE))
                        .thenReturn(Optional.of(tcSource(null, CONNECTION, ACCOUNT, T0.plusSeconds(1), CONNECTION_POLICY)))),
                new Break("no page evidence", () -> when(repository.collectionRange(USER, CANDIDATE))
                        .thenReturn(Optional.empty())),
                new Break("range starting after t0", () -> when(repository.collectionRange(USER, CANDIDATE))
                        .thenReturn(Optional.of(new PlanPublicationRepository.CollectionRange(T0.plusSeconds(1), TC)))),
                new Break("range ending before tc", () -> when(repository.collectionRange(USER, CANDIDATE))
                        .thenReturn(Optional.of(new PlanPublicationRepository.CollectionRange(T0, TC.minusSeconds(1))))),
                // The candidate.
                new Break("candidate missing", () -> when(repository.candidate(USER, CANDIDATE)).thenReturn(Optional.empty())),
                new Break("candidate of another portfolio", () -> candidate(candidateRow(stranger, CONNECTION, ACCOUNT, T0,
                        TC, TC, SOURCE_POLICY))),
                new Break("candidate of another connection", () -> candidate(candidateRow(PORTFOLIO, stranger, ACCOUNT, T0,
                        TC, TC, SOURCE_POLICY))),
                new Break("candidate of another provider account", () -> candidate(candidateRow(PORTFOLIO, CONNECTION,
                        stranger, T0, TC, TC, SOURCE_POLICY))),
                new Break("candidate from another t0", () -> candidate(candidateRow(PORTFOLIO, CONNECTION, ACCOUNT,
                        T0.minusSeconds(1), TC, TC, SOURCE_POLICY))),
                new Break("candidate to another tc", () -> candidate(candidateRow(PORTFOLIO, CONNECTION, ACCOUNT, T0,
                        TC.plusSeconds(1), TC, SOURCE_POLICY))),
                new Break("candidate measured at another time", () -> candidate(candidateRow(PORTFOLIO, CONNECTION,
                        ACCOUNT, T0, TC, TC.minusSeconds(1), SOURCE_POLICY))),
                new Break("candidate under another policy", () -> candidate(candidateRow(PORTFOLIO, CONNECTION, ACCOUNT,
                        T0, TC, TC, "other-policy"))),
                // The classification.
                new Break("classification under review", () -> when(repository.classificationBundle(USER, CLASSIFICATION))
                        .thenReturn(Optional.of(new PlanPublicationRepository.ClassificationBundle(CANDIDATE, PORTFOLIO,
                                INTENT, CONNECTION, ACCOUNT, "NEEDS_REVIEW", "CASHFLOW-1.0", "b".repeat(64))))),
                new Break("classification of another provider account", () -> when(repository.classificationBundle(USER,
                        CLASSIFICATION)).thenReturn(Optional.of(new PlanPublicationRepository.ClassificationBundle(
                                CANDIDATE, PORTFOLIO, INTENT, CONNECTION, stranger, "COMPLETE", "CASHFLOW-1.0",
                                "b".repeat(64))))),
                new Break("classification of another intent", () -> classification(classificationRow(stranger,
                        PORTFOLIO, CONNECTION))),
                new Break("classification of another portfolio", () -> classification(classificationRow(INTENT,
                        stranger, CONNECTION))),
                new Break("classification of another connection", () -> classification(classificationRow(INTENT,
                        PORTFOLIO, stranger))),
                // The conversion.
                new Break("conversion of another classification", () -> when(repository.conversion(USER, CONVERSION))
                        .thenReturn(Optional.of(new Conversion(CONVERSION, stranger, USER, INTENT, PORTFOLIO, CONNECTION,
                                ACCOUNT, "CONVERTED", true, "c".repeat(64), "BYBIT-PRICE-1.0", 0, 0)))),
                new Break("conversion not calculable", () -> when(repository.conversion(USER, CONVERSION))
                        .thenReturn(Optional.of(new Conversion(CONVERSION, CLASSIFICATION, USER, INTENT, PORTFOLIO,
                                CONNECTION, ACCOUNT, "CONVERTED", false, "c".repeat(64), "BYBIT-PRICE-1.0", 0, 0)))),
                new Break("conversion in another status", () -> conversion(conversionRow("REVIEW_REQUIRED", INTENT,
                        PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 0))),
                new Break("conversion of another intent", () -> conversion(conversionRow("CONVERTED", stranger,
                        PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 0))),
                new Break("conversion of another portfolio", () -> conversion(conversionRow("CONVERTED", INTENT,
                        stranger, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 0))),
                new Break("conversion of another connection", () -> conversion(conversionRow("CONVERTED", INTENT,
                        PORTFOLIO, stranger, ACCOUNT, "BYBIT-PRICE-1.0", 0))),
                new Break("conversion of another provider account", () -> conversion(conversionRow("CONVERTED", INTENT,
                        PORTFOLIO, CONNECTION, stranger, "BYBIT-PRICE-1.0", 0))),
                new Break("conversion under another price policy", () -> conversion(conversionRow("CONVERTED", INTENT,
                        PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-9.9", 0))),
                new Break("conversion of another flow count", () -> conversion(conversionRow("CONVERTED", INTENT,
                        PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 1))),
                // The applied cash-flow history.
                new Break("a flow cites another conversion", () -> flows(new PlanPublicationRepository.AppliedFlowCounts(
                        0, 0, 1, List.of()))),
                new Break("another applied count", () -> flows(new PlanPublicationRepository.AppliedFlowCounts(
                        1, 0, 0, List.of()))),
                new Break("another estimated count", () -> flows(new PlanPublicationRepository.AppliedFlowCounts(
                        0, 1, 0, List.of()))),
                new Break("estimated evidence on an official version", () -> flows(
                        new PlanPublicationRepository.AppliedFlowCounts(0, 0, 0, List.of("SPOT_KLINE_CLOSE")))));
        for (Break broken : breaks) {
            org.mockito.Mockito.reset(repository, plans, portfolios, accounts);
            lineage();
            assertThat(service(true).evidence(USER, REFERENCE).outcome()).as("whole before: " + broken.name())
                    .isInstanceOf(EvidenceOutcome.Found.class);
            broken.apply().run();
            assertThat(service(true).evidence(USER, REFERENCE)).as(broken.name()).isEqualTo(unavailable);
        }
    }

    /** An estimated version needs exactly one kind of price evidence among its estimated flows. */
    @Test
    void anEstimatedVersionWithoutExactlyOnePriceEvidenceKindIsNotReturnedInPart() {
        var unavailable = new EvidenceRead(new EvidenceOutcome.Unavailable(), BundleConsistency.INCONSISTENT);
        estimatedLineage(List.of("SPOT_KLINE_CLOSE"));
        assertThat(service(true).evidence(USER, REFERENCE).outcome()).isInstanceOf(EvidenceOutcome.Found.class);
        for (List<String> kinds : List.of(List.<String>of(), List.of("DIRECT_NORMALIZATION", "SPOT_KLINE_CLOSE"))) {
            org.mockito.Mockito.reset(repository, plans, portfolios, accounts);
            estimatedLineage(kinds);
            assertThat(service(true).evidence(USER, REFERENCE)).as(kinds.toString()).isEqualTo(unavailable);
        }
    }

    private void pinned(Pinned pinned) {
        when(repository.creatingAttempt(USER, JOB)).thenReturn(Optional.of(attempt(pinned)));
    }

    private void candidate(Candidate row) {
        when(repository.candidate(USER, CANDIDATE)).thenReturn(Optional.of(row));
    }

    private void classification(PlanPublicationRepository.ClassificationBundle row) {
        when(repository.classificationBundle(USER, CLASSIFICATION)).thenReturn(Optional.of(row));
    }

    private void conversion(Conversion row) {
        when(repository.conversion(USER, CONVERSION)).thenReturn(Optional.of(row));
    }

    private void flows(PlanPublicationRepository.AppliedFlowCounts counts) {
        when(repository.appliedFlowCounts(CALCULATION, CONVERSION)).thenReturn(counts);
    }

    /**
     * The estimated cause is derived: every defined derived metric of an estimated version names the one
     * price evidence kind and the estimated flow count; the current value — Bybit's own measurement — has
     * none, and neither has an undefined metric, whose reason is what explains it.
     */
    @Test
    void anEstimatedVersionNamesItsEstimatedCauseOnEveryDefinedDerivedMetricOnly() {
        estimatedLineage(List.of("SPOT_KLINE_CLOSE"));

        var evidence = found(service(true).evidence(USER, REFERENCE));
        var cause = new CalculationEvidence.EstimatedCause("SPOT_KLINE_CLOSE", 1);
        assertThat(evidence.cashflows()).isEqualTo(new CalculationEvidence.Cashflows(2, 1));
        assertThat(evidence.metrics()).containsExactly(
                new CalculationEvidence.MetricImpact("CURRENT_VALUE", "OFFICIAL", null, null),
                new CalculationEvidence.MetricImpact("PLAN_AMOUNT", "ESTIMATED", null, cause),
                new CalculationEvidence.MetricImpact("PLAN_DIFFERENCE", "ESTIMATED", null, cause),
                new CalculationEvidence.MetricImpact("ADJUSTED_TARGET", "ESTIMATED", null, cause),
                new CalculationEvidence.MetricImpact("REQUIRED_RATE", "ESTIMATED", "CURRENT_VALUE_NOT_POSITIVE", null),
                new CalculationEvidence.MetricImpact("GOAL_PROGRESS", "ESTIMATED", null, cause));
    }

    @Test
    void anEvidenceReadThatThrowsPropagatesInsteadOfAnsweringInPart() {
        lineage();
        when(repository.classificationBundle(USER, CLASSIFICATION))
                .thenThrow(new IllegalStateException("injected classification failure"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(true).evidence(USER, REFERENCE))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).unpublished(any(), any());
    }
}
