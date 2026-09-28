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
import org.springframework.transaction.PlatformTransactionManager;

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

    private final PlanPublicationPort.Terms terms = new PlanPublicationPort.Terms(INTENT, PORTFOLIO, BASIS_SNAPSHOT,
            MEMBERSHIP, CONNECTION, ACCOUNT, BASIS_SOURCE, "1000", T0, END, "DAILY", "0.1", "bybit-readonly-uta-1.0",
            "bybit-readonly-uta-1.0", "CALENDAR-SEOUL-1.0", "CALC-1.0");
    private final Candidate candidate = new Candidate(CANDIDATE, USER, PORTFOLIO, INTENT, CONNECTION, ACCOUNT, T0, TC,
            "1250", TC, TC, "bybit-readonly-uta-1.0", "a".repeat(64));
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
                mock(PlatformTransactionManager.class));
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
}
