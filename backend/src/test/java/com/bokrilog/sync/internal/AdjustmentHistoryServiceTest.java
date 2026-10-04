package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.SyncJobApi.AdjustmentDirection;
import com.bokrilog.sync.SyncJobApi.AdjustmentHistory;
import com.bokrilog.sync.SyncJobApi.AdjustmentOutcome;
import com.bokrilog.sync.SyncJobApi.AdjustmentQuery;
import com.bokrilog.sync.SyncJobApi.AdjustmentRead;
import com.bokrilog.sync.SyncJobApi.BundleConsistency;
import com.bokrilog.sync.internal.AdjustmentHistoryRepository.ChainLink;
import com.bokrilog.sync.internal.AdjustmentHistoryRepository.ClassificationCounts;
import com.bokrilog.sync.internal.AdjustmentHistoryRepository.EarlierFlow;
import com.bokrilog.sync.internal.AdjustmentHistoryRepository.PageRow;
import com.bokrilog.sync.internal.AdjustmentHistoryRepository.Totals;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The adjustment history's decisions that an integration test cannot provoke on demand: conversion chains of
 * any shape, the change marker's rule, page boundaries of exactly twenty and twenty-one rows, and every
 * bundle-level disagreement one at a time. Collaborators are mocks; the transaction manager is a mock too,
 * so each {@code TransactionTemplate} callback simply runs.
 */
class AdjustmentHistoryServiceTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INTENT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID PORTFOLIO = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID CALCULATION = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID CONNECTION = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID CANDIDATE = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID CLASSIFICATION = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID CONVERSION = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID EARLIER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID EARLIEST = UUID.fromString("00000000-0000-0000-0000-00000000000d");
    private static final Instant T0 = Instant.parse("2027-01-04T00:00:00Z");
    private static final Instant TC = T0.plusSeconds(3600);
    private static final Instant END = T0.plusSeconds(30L * 86_400);
    private static final Instant PUBLISHED = TC.plusSeconds(5);

    private final PlanPublicationRepository publications = mock(PlanPublicationRepository.class);
    private final AdjustmentHistoryRepository history = mock(AdjustmentHistoryRepository.class);
    private final PortfolioMembershipApi portfolios = mock(PortfolioMembershipApi.class);
    private final AccountAccessApi accounts = mock(AccountAccessApi.class);
    private final PlanPublicationPort plans = mock(PlanPublicationPort.class);
    private final AdjustmentHistoryService service = new AdjustmentHistoryService(publications, history, portfolios,
            accounts, plans, new ShownPlanAgreement(publications, mock(PlanSyncRepository.class), portfolios),
            mock(PlatformTransactionManager.class));

    private static AdjustmentQuery query(AdjustmentDirection direction, String cursor) {
        return new AdjustmentQuery(PLAN, 1, CALCULATION, direction, cursor);
    }

    private static PlanPublicationRepository.CalculationView calculation(int flows) {
        return new PlanPublicationRepository.CalculationView(PLAN, PORTFOLIO, SNAPSHOT, 2, SNAPSHOT, "1000", T0, END,
                "DAILY", "0.1", TC, TC, TC, "CALC-1.0", "CALENDAR-SEOUL-1.0", "DISPLAY-1.0", "BYBIT-PRICE-1.0",
                "impl", "a".repeat(64), "b".repeat(64), flows, 0, false, "CONFIRMED");
    }

    private static PageRow row(int sequence, String usd) {
        return new PageRow(sequence, event(sequence), "adj-" + sequence, TC.minusSeconds(100 - sequence), usd,
                usd.startsWith("-") ? "TRANSFER_OUT" : "TRANSFER_IN", "USDT", usd, "EXTERNAL_TRANSFER_IN",
                "BYBIT-CASHFLOW-1.0", "BYBIT-PRICE-1.0", "DIRECT_NORMALIZATION", false, "1", "DIRECT",
                TC.minusSeconds(10), usd);
    }

    private static UUID event(int sequence) {
        return UUID.fromString(String.format("00000000-0000-0000-0001-%012d", sequence));
    }

    /**
     * One whole, consistent world of {@code flows} applied deposits of 1 USDT and a conversion without a
     * predecessor. A test re-stubs one collaborator afterwards to break exactly one thing.
     */
    private void world(int flows) {
        when(accounts.decide(eq(USER), eq(USER), any(AccountAccessKind.class))).thenReturn(AccessDecision.ALLOWED);
        when(plans.activePlan(USER)).thenReturn(Optional.of(new PlanPublicationPort.ActivePlan(PLAN, INTENT,
                PORTFOLIO, "1000", T0, END, "DAILY", "0.1", T0)));
        when(publications.pointer(USER, PLAN)).thenReturn(Optional.of(new PlanPublicationRepository.Pointer(PORTFOLIO,
                CALCULATION, 1, PUBLISHED)));
        when(publications.calculation(USER, CALCULATION)).thenReturn(Optional.of(calculation(flows)));
        when(portfolios.officialValue(USER)).thenReturn(Optional.of(new PortfolioMembershipApi.OfficialValue("1000",
                "USD", TC, TC, PUBLISHED, SNAPSHOT, 2)));
        when(publications.lineage(USER, CALCULATION)).thenReturn(Optional.of(new PlanPublicationRepository.Lineage(
                UUID.randomUUID(), "BYBIT", ACCOUNT, CONNECTION, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CANDIDATE, CLASSIFICATION, CONVERSION, "BYBIT-CASHFLOW-1.0", "bybit-readonly-uta-1.0",
                "DECIMAL128-HALF_EVEN", "Asia/Seoul", PUBLISHED, TC, null)));
        when(publications.classificationBundle(USER, CLASSIFICATION)).thenReturn(Optional.of(
                classificationBundle(CANDIDATE, PORTFOLIO, INTENT, CONNECTION, ACCOUNT, "COMPLETE", "BYBIT-CASHFLOW-1.0")));
        when(history.classificationCounts(USER, CLASSIFICATION))
                .thenReturn(Optional.of(new ClassificationCounts(flows, 0, 2, 0, 2)));
        when(publications.conversion(USER, CONVERSION)).thenReturn(Optional.of(conversionOf(CLASSIFICATION, INTENT,
                PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", flows, 0)));
        when(history.totals(eq(USER), eq(CALCULATION), any(AdjustmentDirection.class)))
                .thenReturn(new Totals(flows, 0, 0, 0, flows, flows == 0 ? 0 : 1, flows, flows, flows + ".000"));
        when(history.chain(USER, CLASSIFICATION, CONVERSION, AdjustmentHistoryService.MAX_CHAIN))
                .thenReturn(List.of(new ChainLink(CONVERSION, null, null, TC, flows)));
        when(history.earlierCoverage(eq(CALCULATION), anyList())).thenReturn(0);
        when(history.legs(eq(CONVERSION), anyList())).thenReturn(Map.of());
        when(history.earlierFlows(anyList(), anyList())).thenReturn(Map.of());
    }

    private static PlanPublicationRepository.ClassificationBundle classificationBundle(UUID candidate, UUID portfolio,
            UUID intent, UUID connection, UUID account, String status, String policy) {
        return new PlanPublicationRepository.ClassificationBundle(candidate, portfolio, intent, connection, account,
                status, policy, "c".repeat(64));
    }

    private static PlanPublicationRepository.Conversion conversionOf(UUID classification, UUID intent, UUID portfolio,
            UUID connection, UUID account, String policy, int flows, int estimated) {
        return new PlanPublicationRepository.Conversion(CONVERSION, classification, USER, intent, portfolio, connection,
                account, "CONVERTED", true, "d".repeat(64), policy, flows, estimated);
    }

    private static void totals(AdjustmentHistoryServiceTest t, Totals totals) {
        when(t.history.totals(eq(USER), eq(CALCULATION), any())).thenReturn(totals);
    }

    private static void counts(AdjustmentHistoryServiceTest t, ClassificationCounts counts) {
        when(t.history.classificationCounts(USER, CLASSIFICATION)).thenReturn(Optional.of(counts));
    }

    private static void portfolio(AdjustmentHistoryServiceTest t, UUID snapshot, long sequence) {
        when(t.portfolios.officialValue(USER)).thenReturn(Optional.of(new PortfolioMembershipApi.OfficialValue("1000",
                "USD", TC, TC, PUBLISHED, snapshot, sequence)));
    }

    private static void classification(AdjustmentHistoryServiceTest t,
            PlanPublicationRepository.ClassificationBundle bundle) {
        when(t.publications.classificationBundle(USER, CLASSIFICATION)).thenReturn(Optional.of(bundle));
    }

    private static void conversion(AdjustmentHistoryServiceTest t, PlanPublicationRepository.Conversion conversion) {
        when(t.publications.conversion(USER, CONVERSION)).thenReturn(Optional.of(conversion));
    }

    private static void chain(AdjustmentHistoryServiceTest t, ChainLink... links) {
        when(t.history.chain(USER, CLASSIFICATION, CONVERSION, AdjustmentHistoryService.MAX_CHAIN))
                .thenReturn(List.of(links));
    }

    private void pageOf(int fetched) {
        when(history.page(eq(USER), eq(CALCULATION), any(AdjustmentDirection.class), anyInt(), anyInt()))
                .thenReturn(IntStream.rangeClosed(1, fetched).mapToObj(sequence -> row(sequence, "1")).toList());
    }

    private AdjustmentHistory found(AdjustmentQuery query) {
        AdjustmentRead read = service.read(USER, query);
        assertThat(read.consistency()).isEqualTo(BundleConsistency.CONSISTENT);
        assertThat(read.outcome()).isInstanceOf(AdjustmentOutcome.Found.class);
        return ((AdjustmentOutcome.Found) read.outcome()).history();
    }

    private void assertUnavailable(String name) {
        assertThat(service.read(USER, query(AdjustmentDirection.ALL, null))).as(name)
                .isEqualTo(new AdjustmentRead(new AdjustmentOutcome.Unavailable(), BundleConsistency.INCONSISTENT));
    }

    @Test
    void aPageOfTwentyWithMoreBehindItCarriesTheCursorOfItsLastRowAndTheExactSummary() {
        world(25);
        pageOf(21);
        var page = found(query(AdjustmentDirection.ALL, null));
        assertThat(page.rows()).hasSize(20);
        assertThat(page.summary()).isEqualTo(new AdjustmentHistory.Summary(25, "25", 2));
        assertThat(page.appliedAt()).isEqualTo(PUBLISHED);
        assertThat(page.coverage()).isEqualTo(new AdjustmentHistory.Coverage(T0, TC));
        assertThat(page.nextCursor()).isNotNull();
        assertThat(AdjustmentCursor.decode(USER, query(AdjustmentDirection.ALL, null), page.nextCursor()))
                .isEqualTo(OptionalInt.of(20));
    }

    @Test
    void aPageOfExactlyTwentyIsTheLastOne() {
        world(20);
        pageOf(20);
        var page = found(query(AdjustmentDirection.ALL, null));
        assertThat(page.rows()).hasSize(20);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void aCursorOfAnotherScopeNamesNothingAndNothingIsRead() {
        when(accounts.decide(eq(USER), eq(USER), any(AccountAccessKind.class))).thenReturn(AccessDecision.ALLOWED);
        String other = AdjustmentCursor.encode(USER, query(AdjustmentDirection.IN, null), 20);
        assertThat(service.read(USER, query(AdjustmentDirection.ALL, other)))
                .isEqualTo(new AdjustmentRead(new AdjustmentOutcome.NotFound(), BundleConsistency.NOT_READ));
        assertThat(service.read(USER, null))
                .isEqualTo(new AdjustmentRead(new AdjustmentOutcome.NotFound(), BundleConsistency.NOT_READ));
        verifyNoInteractions(history, publications, plans, portfolios);
    }

    @Test
    void theAccountsOwnAccessIsDecidedBeforeAndAfterTheRead() {
        world(1);
        pageOf(1);
        when(accounts.decide(eq(USER), eq(USER), any(AccountAccessKind.class)))
                .thenReturn(AccessDecision.ALLOWED, AccessDecision.DENIED);
        assertThat(service.read(USER, query(AdjustmentDirection.ALL, null)))
                .isEqualTo(new AdjustmentRead(new AdjustmentOutcome.NotPermitted(), BundleConsistency.CONSISTENT));
        assertThat(service.read(null, query(AdjustmentDirection.ALL, null)))
                .isEqualTo(new AdjustmentRead(new AdjustmentOutcome.NotPermitted(), BundleConsistency.NOT_READ));
    }

    @Test
    void anyDisagreementOfTheVersionWithItsLineageIsUnavailable() {
        record Break(String name, Consumer<AdjustmentHistoryServiceTest> apply) {}
        UUID other = UUID.randomUUID();
        var breaks = new java.util.ArrayList<Break>(List.of(
                new Break("a broken row", t -> totals(t, new Totals(3, 1, 0, 0, 3, 1, 3, 3, "3"))),
                new Break("a row out of order", t -> totals(t, new Totals(3, 0, 1, 0, 3, 1, 3, 3, "3"))),
                new Break("another count", t -> totals(t, new Totals(2, 0, 0, 0, 2, 1, 2, 2, "2"))),
                new Break("a gap in the sequence", t -> totals(t, new Totals(3, 0, 0, 0, 3, 1, 4, 3, "3"))),
                new Break("a sequence that does not start at 1",
                        t -> totals(t, new Totals(3, 0, 0, 0, 3, 2, 3, 3, "3"))),
                new Break("another estimated count", t -> totals(t, new Totals(3, 0, 0, 1, 3, 1, 3, 3, "3"))),
                new Break("directions that are not the classification's",
                        t -> totals(t, new Totals(3, 0, 0, 0, 2, 1, 3, 3, "3"))),
                new Break("a total that is not a plain decimal",
                        t -> totals(t, new Totals(3, 0, 0, 0, 3, 1, 3, 3, "3e0"))),
                new Break("a review-required event", t -> counts(t, new ClassificationCounts(3, 0, 2, 1, 2))),
                new Break("another deposit and withdrawal count",
                        t -> counts(t, new ClassificationCounts(2, 0, 2, 0, 2))),
                new Break("an excluded count its events do not say",
                        t -> counts(t, new ClassificationCounts(3, 0, 2, 0, 1))),
                new Break("the version is missing", t -> when(t.publications.calculation(USER, CALCULATION))
                        .thenReturn(Optional.empty())),
                new Break("the version is not of the plan's terms", t -> when(t.plans.activePlan(USER)).thenReturn(
                        Optional.of(new PlanPublicationPort.ActivePlan(PLAN, INTENT, PORTFOLIO, "999", T0, END,
                                "DAILY", "0.1", T0)))),
                new Break("a portfolio pointer of another snapshot", t -> portfolio(t, UUID.randomUUID(), 2)),
                new Break("a portfolio pointer of another sequence", t -> portfolio(t, SNAPSHOT, 3)),
                new Break("the lineage is missing", t -> when(t.publications.lineage(USER, CALCULATION))
                        .thenReturn(Optional.empty())),
                new Break("the classification is missing", t -> when(t.publications.classificationBundle(USER,
                        CLASSIFICATION)).thenReturn(Optional.empty())),
                new Break("the classification counts are missing", t -> when(t.history.classificationCounts(USER,
                        CLASSIFICATION)).thenReturn(Optional.empty())),
                new Break("a classification of another candidate", t -> classification(t, classificationBundle(other,
                        PORTFOLIO, INTENT, CONNECTION, ACCOUNT, "COMPLETE", "BYBIT-CASHFLOW-1.0"))),
                new Break("a classification that is not complete", t -> classification(t, classificationBundle(
                        CANDIDATE, PORTFOLIO, INTENT, CONNECTION, ACCOUNT, "NEEDS_REVIEW", "BYBIT-CASHFLOW-1.0"))),
                new Break("a classification of another policy", t -> classification(t, classificationBundle(CANDIDATE,
                        PORTFOLIO, INTENT, CONNECTION, ACCOUNT, "COMPLETE", "BYBIT-CASHFLOW-9.9"))),
                new Break("a classification of another intent", t -> classification(t, classificationBundle(CANDIDATE,
                        PORTFOLIO, other, CONNECTION, ACCOUNT, "COMPLETE", "BYBIT-CASHFLOW-1.0"))),
                new Break("a classification of another portfolio", t -> classification(t, classificationBundle(
                        CANDIDATE, other, INTENT, CONNECTION, ACCOUNT, "COMPLETE", "BYBIT-CASHFLOW-1.0"))),
                new Break("a classification of another connection", t -> classification(t, classificationBundle(
                        CANDIDATE, PORTFOLIO, INTENT, other, ACCOUNT, "COMPLETE", "BYBIT-CASHFLOW-1.0"))),
                new Break("a classification of another provider account", t -> classification(t, classificationBundle(
                        CANDIDATE, PORTFOLIO, INTENT, CONNECTION, other, "COMPLETE", "BYBIT-CASHFLOW-1.0"))),
                new Break("the conversion is missing", t -> when(t.publications.conversion(USER, CONVERSION))
                        .thenReturn(Optional.empty())),
                new Break("a conversion that is not calculable", t -> conversion(t,
                        new PlanPublicationRepository.Conversion(CONVERSION, CLASSIFICATION, USER, INTENT, PORTFOLIO,
                                CONNECTION, ACCOUNT, "CONVERSION_REQUIRED", false, "d".repeat(64), "BYBIT-PRICE-1.0",
                                3, 0))),
                new Break("a calculable conversion of another status", t -> conversion(t,
                        new PlanPublicationRepository.Conversion(CONVERSION, CLASSIFICATION, USER, INTENT, PORTFOLIO,
                                CONNECTION, ACCOUNT, "REVIEW_REQUIRED", true, "d".repeat(64), "BYBIT-PRICE-1.0", 3, 0))),
                new Break("a conversion of another classification", t -> conversion(t, conversionOf(other, INTENT,
                        PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 3, 0))),
                new Break("a conversion of another price policy", t -> conversion(t, conversionOf(CLASSIFICATION,
                        INTENT, PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-9.9", 3, 0))),
                new Break("a conversion of another intent", t -> conversion(t, conversionOf(CLASSIFICATION, other,
                        PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 3, 0))),
                new Break("a conversion of another portfolio", t -> conversion(t, conversionOf(CLASSIFICATION, INTENT,
                        other, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 3, 0))),
                new Break("a conversion of another connection", t -> conversion(t, conversionOf(CLASSIFICATION, INTENT,
                        PORTFOLIO, other, ACCOUNT, "BYBIT-PRICE-1.0", 3, 0))),
                new Break("a conversion of another provider account", t -> conversion(t, conversionOf(CLASSIFICATION,
                        INTENT, PORTFOLIO, CONNECTION, other, "BYBIT-PRICE-1.0", 3, 0))),
                new Break("a conversion of another flow count", t -> conversion(t, conversionOf(CLASSIFICATION, INTENT,
                        PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 4, 0))),
                new Break("a conversion of another estimated count", t -> conversion(t, conversionOf(CLASSIFICATION,
                        INTENT, PORTFOLIO, CONNECTION, ACCOUNT, "BYBIT-PRICE-1.0", 3, 1))),
                new Break("a chain that leads nowhere", t -> chain(t, new ChainLink(CONVERSION, EARLIER,
                        "PRICE_REVISION", TC, 3))),
                new Break("a chain that starts elsewhere", t -> chain(t, new ChainLink(EARLIER, null, null, TC, 3))),
                new Break("a chain link that does not cite the one before", t -> chain(t,
                        new ChainLink(CONVERSION, EARLIER, "PRICE_REVISION", TC, 3),
                        new ChainLink(EARLIEST, null, null, T0, 3))),
                new Break("a chain link without its reason", t -> chain(t,
                        new ChainLink(CONVERSION, EARLIER, null, TC, 3), new ChainLink(EARLIER, null, null, T0, 3))),
                new Break("an earlier version of another flow count", t -> chain(t,
                        new ChainLink(CONVERSION, EARLIER, "PRICE_REVISION", TC, 3),
                        new ChainLink(EARLIER, null, null, T0, 2))),
                new Break("an earlier version that lacks an applied event", t -> {
                    chain(t, new ChainLink(CONVERSION, EARLIER, "PRICE_REVISION", TC, 3),
                            new ChainLink(EARLIER, null, null, T0, 3));
                    when(t.history.earlierCoverage(eq(CALCULATION), anyList())).thenReturn(2);
                })));
        // The walk returns at most MAX_CHAIN links: a chain that still has a predecessor there is not one lineage.
        var tooLong = new java.util.ArrayList<ChainLink>();
        for (int depth = 0; depth < AdjustmentHistoryService.MAX_CHAIN; depth++) {
            UUID id = depth == 0 ? CONVERSION : new UUID(7, depth);
            tooLong.add(new ChainLink(id, new UUID(7, depth + 1), "PRICE_REVISION", TC, 3));
        }
        breaks.add(new Break("a chain longer than the walk", t -> chain(t, tooLong.toArray(ChainLink[]::new))));
        for (Break each : breaks) {
            world(3);
            pageOf(3);
            each.apply().accept(this);
            assertUnavailable(each.name());
        }
    }

    @Test
    void aReferenceThatIsNotTheCurrentPointerIsNotFound() {
        world(3);
        pageOf(3);
        for (AdjustmentQuery stale : new AdjustmentQuery[] {
                new AdjustmentQuery(PLAN, 2, CALCULATION, AdjustmentDirection.ALL, null),
                new AdjustmentQuery(UUID.randomUUID(), 1, CALCULATION, AdjustmentDirection.ALL, null),
                new AdjustmentQuery(PLAN, 1, UUID.randomUUID(), AdjustmentDirection.ALL, null)}) {
            assertThat(service.read(USER, stale))
                    .isEqualTo(new AdjustmentRead(new AdjustmentOutcome.NotFound(), BundleConsistency.NOT_READ));
        }
    }

    @Test
    void aFlowIsMarkedChangedOnlyWhenAnEarlierVersionHadAnotherValueOrNone() {
        world(1);
        when(history.page(eq(USER), eq(CALCULATION), any(), anyInt(), anyInt())).thenReturn(List.of(row(1, "10")));
        Instant earlierAt = TC.minusSeconds(60), earliestAt = TC.minusSeconds(120);
        when(history.chain(USER, CLASSIFICATION, CONVERSION, AdjustmentHistoryService.MAX_CHAIN)).thenReturn(List.of(
                new ChainLink(CONVERSION, EARLIER, "PRICE_REVISION", TC, 1),
                new ChainLink(EARLIER, EARLIEST, "EVIDENCE_CHANGE", earlierAt, 1),
                new ChainLink(EARLIEST, null, null, earliestAt, 1)));
        when(history.earlierCoverage(eq(CALCULATION), anyList())).thenReturn(2);

        // The same value in every earlier version (in another notation): no marker, no list.
        when(history.earlierFlows(anyList(), anyList())).thenReturn(Map.of(
                EARLIER, Map.of(event(1), new EarlierFlow("CONVERTED", null, "10.0")),
                EARLIEST, Map.of(event(1), new EarlierFlow("CONVERTED", null, "10"))));
        var same = found(query(AdjustmentDirection.ALL, null)).rows().getFirst();
        assertThat(same.changed()).isFalse();
        assertThat(same.revisions()).isEmpty();

        // An earlier version without a value, one with another: marked, newest first, each as it was stored.
        when(history.earlierFlows(anyList(), anyList())).thenReturn(Map.of(
                EARLIER, Map.of(event(1), new EarlierFlow("CONVERTED", null, "9.990")),
                EARLIEST, Map.of(event(1), new EarlierFlow("CONVERSION_REQUIRED", "PRICE_CANDLE_NOT_CLOSED", null))));
        var changed = found(query(AdjustmentDirection.ALL, null)).rows().getFirst();
        assertThat(changed.changed()).isTrue();
        assertThat(changed.revisions()).containsExactly(
                new AdjustmentHistory.Revision(EARLIER, earlierAt, "CONVERTED", null, "9.99", "PRICE_REVISION", TC),
                new AdjustmentHistory.Revision(EARLIEST, earliestAt, "CONVERSION_REQUIRED", "PRICE_CANDLE_NOT_CLOSED",
                        null, "EVIDENCE_CHANGE", earlierAt));
    }
}
