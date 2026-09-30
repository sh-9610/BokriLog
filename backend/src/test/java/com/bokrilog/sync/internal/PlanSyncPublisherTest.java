package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.calculation.PlanCalculator;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.internal.PlanPublicationRepository.Candidate;
import com.bokrilog.sync.internal.PlanPublicationRepository.Conversion;
import com.bokrilog.sync.internal.PlanPublicationRepository.Pinned;
import com.bokrilog.sync.internal.PlanPublicationRepository.PublicationBlocked;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The incremental sync's publication writes only the attempt's own inputs (Story 2.11): the candidate of its
 * collection child, the bundles of its classification and conversion children, and a candidate inside the scope
 * the attempt fixed ({@code tc}, window start, base candidate). Anything else is refused before the first write
 * with {@code INPUTS_CHANGED}. Collaborators are mocks; the transaction manager is a mock too.
 */
class PlanSyncPublisherTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INTENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID PORTFOLIO = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID CONNECTION = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID BASE_CALCULATION = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID BASE_CANDIDATE = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID CANDIDATE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CLASSIFICATION = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID CONVERSION = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final UUID SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final Instant T0 = Instant.parse("2027-01-04T00:00:00Z");
    private static final Instant VERIFIED = Instant.parse("2027-01-20T00:00:00Z");
    private static final Instant TC = VERIFIED.plus(Duration.ofHours(1));
    private static final Instant WINDOW = VERIFIED.minus(Duration.ofHours(24));
    private static final Instant END = Instant.parse("2027-06-04T00:00:00Z");

    private final PlanSyncRepository syncs = mock(PlanSyncRepository.class);
    private final PlanPublicationRepository publications = mock(PlanPublicationRepository.class);
    private final PortfolioMembershipApi portfolios = mock(PortfolioMembershipApi.class);
    private final AccountAccessApi accounts = mock(AccountAccessApi.class);
    private final PlanPublicationPort plans = mock(PlanPublicationPort.class);
    private final PlanSyncGuard guard = mock(PlanSyncGuard.class);

    private final PlanSyncRepository.Lease lease = new PlanSyncRepository.Lease(JOB, USER, PLAN, PORTFOLIO, INTENT,
            MEMBERSHIP, CONNECTION, ACCOUNT, "SYNC", null, "MANUAL", BASE_CALCULATION, 1, BASE_CANDIDATE, VERIFIED,
            VERIFIED, T0, END, 1);
    private final Conversion conversion = new Conversion(CONVERSION, CLASSIFICATION, USER, INTENT, PORTFOLIO,
            CONNECTION, ACCOUNT, "CONVERTED", true, "c".repeat(64), "BYBIT-PRICE-1.0", 0, 0);
    private final Pinned pinned = new Pinned(JOB, USER, PORTFOLIO, INTENT, MEMBERSHIP, CONNECTION, ACCOUNT,
            UUID.randomUUID(), UUID.randomUUID(), SNAPSHOT, 2, CANDIDATE, CLASSIFICATION, CONVERSION, "1000", T0, END,
            "DAILY", "0.1", TC, "1250", TC, 0, "f".repeat(64), "CALC-1.0", "CALENDAR-SEOUL-1.0");

    private static Candidate candidate(Instant tc, Instant window, UUID base) {
        return new Candidate(CANDIDATE, USER, PORTFOLIO, INTENT, CONNECTION, ACCOUNT, T0, tc, "1250", tc, tc,
                "bybit-readonly-uta-1.0", "a".repeat(64), base, window);
    }

    private static PlanSyncRepository.CreatingSync scope(Instant tc, Instant window) {
        return new PlanSyncRepository.CreatingSync("RUNNING", PLAN, PORTFOLIO, INTENT, MEMBERSHIP, CONNECTION, ACCOUNT,
                null, null, BASE_CALCULATION, 1, BASE_CANDIDATE, VERIFIED, VERIFIED, tc, "NORMAL", window, "SYNC", null,
                null, null);
    }

    private PlanSyncPublisher.Prepared prepared(Candidate candidate) {
        return new PlanSyncPublisher.Prepared(pinned, candidate, conversion, List.of(), "BYBIT-CASHFLOW-1.0", VERIFIED,
                PlanSyncPublisher.inputHash(lease, candidate, CLASSIFICATION, conversion, List.of()));
    }

    /**
     * A publication that passes every earlier check, with the attempt's own children and scope. The portfolio
     * pointer then moves under it, so a publication that got past the inputs check ends at its first write's CAS.
     */
    private void ownAttempt(Candidate candidate) {
        when(accounts.lockWriteAccess(USER)).thenReturn(true);
        when(syncs.lockOwned(lease)).thenReturn(true);
        when(guard.blocked(lease)).thenReturn(null);
        when(publications.calculation(USER, BASE_CALCULATION)).thenReturn(Optional.of(
                new PlanPublicationRepository.CalculationView(PLAN, PORTFOLIO, SNAPSHOT, 2, UUID.randomUUID(), "1000", T0,
                        END, "DAILY", "0.1", VERIFIED, VERIFIED, VERIFIED, "CALC-1.0", "CALENDAR-SEOUL-1.0",
                        "DISPLAY-1.0", "BYBIT-PRICE-1.0", "impl", "a".repeat(64), "b".repeat(64), 0, 0, false,
                        "CONFIRMED")));
        when(portfolios.officialValue(USER)).thenReturn(Optional.of(new PortfolioMembershipApi.OfficialValue("1000",
                "USD", VERIFIED, VERIFIED, VERIFIED, SNAPSHOT, 2)));
        when(publications.candidate(USER, CANDIDATE)).thenReturn(Optional.of(candidate));
        when(publications.conversion(USER, CONVERSION)).thenReturn(Optional.of(conversion));
        when(publications.flows(USER, CONVERSION)).thenReturn(List.of());
        when(syncs.creatingSync(USER, JOB)).thenReturn(Optional.of(scope(TC, WINDOW)));
        when(syncs.childResults(USER, JOB)).thenReturn(Optional.of(
                new PlanSyncRepository.ChildResults(CANDIDATE, CLASSIFICATION, CONVERSION)));
        when(portfolios.publishNext(any(), any(), anyLong()))
                .thenReturn(new PortfolioMembershipApi.NextPublication.PointerMoved());
    }

    private PlanSyncPublisher publisher() {
        return new PlanSyncPublisher(syncs, publications, portfolios, accounts, plans, guard,
                mock(PlatformTransactionManager.class));
    }

    private static PlanCalculator.Result.Calculated result() {
        return mock(PlanCalculator.Result.Calculated.class);
    }

    @Test
    void theAttemptsOwnInputsGoOnToTheWrites() {
        Candidate own = candidate(TC, WINDOW, BASE_CANDIDATE);
        ownAttempt(own);
        assertThatThrownBy(() -> publisher().publish(lease, prepared(own), result()))
                .isInstanceOf(PublicationBlocked.class).extracting(e -> ((PublicationBlocked) e).code())
                .isEqualTo("POINTER_CONFLICT");
        verify(publications).insertCandidateSource(any(), any());
    }

    @Test
    void inputsThatAreNotTheAttemptsOwnAreRefusedBeforeAnyWrite() {
        List<Consumer<PlanSyncPublisherTest>> breaks = List.of(
                test -> when(test.syncs.childResults(USER, JOB)).thenReturn(Optional.of(
                        new PlanSyncRepository.ChildResults(UUID.randomUUID(), CLASSIFICATION, CONVERSION))),
                test -> when(test.syncs.childResults(USER, JOB)).thenReturn(Optional.of(
                        new PlanSyncRepository.ChildResults(CANDIDATE, UUID.randomUUID(), CONVERSION))),
                test -> when(test.syncs.childResults(USER, JOB)).thenReturn(Optional.of(
                        new PlanSyncRepository.ChildResults(CANDIDATE, CLASSIFICATION, null))),
                test -> when(test.syncs.childResults(USER, JOB)).thenReturn(Optional.empty()),
                test -> when(test.syncs.creatingSync(USER, JOB)).thenReturn(Optional.of(scope(TC.plusMillis(1), WINDOW))),
                test -> when(test.syncs.creatingSync(USER, JOB)).thenReturn(Optional.of(scope(TC, WINDOW.plusMillis(1)))),
                test -> when(test.syncs.creatingSync(USER, JOB)).thenReturn(Optional.of(scope(TC, null))),
                test -> when(test.publications.candidate(USER, CANDIDATE)).thenReturn(Optional.of(
                        candidate(TC, WINDOW, UUID.randomUUID()))));
        for (int index = 0; index < breaks.size(); index++) {
            PlanSyncPublisherTest test = new PlanSyncPublisherTest();
            Candidate own = candidate(TC, WINDOW, BASE_CANDIDATE);
            test.ownAttempt(own);
            breaks.get(index).accept(test);
            assertThatThrownBy(() -> test.publisher().publish(test.lease, test.prepared(own), result())).as("break " + index)
                    .isInstanceOf(PublicationBlocked.class).extracting(e -> ((PublicationBlocked) e).code())
                    .isEqualTo("INPUTS_CHANGED");
            verify(test.publications, never()).insertCandidateSource(any(), any());
            verify(test.portfolios, never()).publishNext(any(), any(), anyLong());
        }
        // Inputs that changed after they were read (another hash) are refused the same way.
        Candidate own = candidate(TC, WINDOW, BASE_CANDIDATE);
        ownAttempt(own);
        var stale = new PlanSyncPublisher.Prepared(pinned, own, conversion, List.of(), "BYBIT-CASHFLOW-1.0", VERIFIED,
                "0".repeat(64));
        assertThatThrownBy(() -> publisher().publish(lease, stale, result())).isInstanceOf(PublicationBlocked.class)
                .extracting(e -> ((PublicationBlocked) e).code()).isEqualTo("INPUTS_CHANGED");
        verify(publications, never()).insertCandidateSource(any(), any());
    }
}
