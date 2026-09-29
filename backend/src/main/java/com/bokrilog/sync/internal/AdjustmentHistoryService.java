package com.bokrilog.sync.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.SyncJobApi.AdjustmentHistory;
import com.bokrilog.sync.SyncJobApi.AdjustmentOutcome;
import com.bokrilog.sync.SyncJobApi.AdjustmentQuery;
import com.bokrilog.sync.SyncJobApi.AdjustmentRead;
import com.bokrilog.sync.SyncJobApi.BundleConsistency;
import com.bokrilog.sync.internal.AdjustmentHistoryRepository.ChainLink;
import com.bokrilog.sync.internal.AdjustmentHistoryRepository.EarlierFlow;
import com.bokrilog.sync.internal.AdjustmentHistoryRepository.PageRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The adjustment history of the current official result (Story 2.10): the external cash flows the current
 * calculation version applied, one page at a time, each with its source event, classification, event-time
 * conversion and the calculation that applied it.
 *
 * <p>The read has the calculation evidence's shape (Story 2.9). The account's own access is checked; the
 * cursor is judged against this user, the reference and the filter before anything is read; the pointer, the
 * version, the version's whole applied history and the page are read in one read-only
 * {@code REPEATABLE READ} transaction; and the access is checked again — a deletion that began or finished
 * meanwhile wins. The reference must be the user's current pointer to the letter, or it is not found and
 * nothing is looked up by it. From there on, a disagreement anywhere in the lineage of any row — not only
 * the rows of this page — returns no list at all.
 *
 * <p>Nothing is recalculated, reclassified or reconverted, and there is no table of its own: the existing
 * sync erasure removes everything this reads.
 */
@Service
class AdjustmentHistoryService {
    /** Rows per page. One more is read to know whether a next page exists. */
    static final int PAGE_SIZE = 20;
    /**
     * The most conversion versions walked, the current one included: at most this many links are read, and a
     * chain that does not end within them is not one lineage.
     */
    static final int MAX_CHAIN = 64;

    private final PlanPublicationRepository publications;
    private final AdjustmentHistoryRepository history;
    private final PortfolioMembershipApi portfolios;
    private final AccountAccessApi accounts;
    private final PlanPublicationPort plans;
    private final OfficialVersionHead heads;
    private final TransactionTemplate reads;

    AdjustmentHistoryService(PlanPublicationRepository publications, AdjustmentHistoryRepository history,
            PortfolioMembershipApi portfolios, AccountAccessApi accounts, PlanPublicationPort plans,
            OfficialVersionHead heads, PlatformTransactionManager manager) {
        this.publications = publications;
        this.heads = heads;
        this.history = history;
        this.portfolios = portfolios;
        this.accounts = accounts;
        this.plans = plans;
        this.reads = new TransactionTemplate(manager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * One page. A {@code null} query (missing, repeated or malformed values) and a cursor that names nothing
     * in this scope are not found, after the account's own access and before any read. The read reports what
     * it learned about the lineage — consistent, inconsistent, or not read (a refusal, or a reference that is
     * not the current pointer) — and lets a failure propagate.
     */
    AdjustmentRead read(@Nullable UUID userId, @Nullable AdjustmentQuery query) {
        if (userId == null || !readable(userId))
            return new AdjustmentRead(new AdjustmentOutcome.NotPermitted(), BundleConsistency.NOT_READ);
        if (query == null) return new AdjustmentRead(new AdjustmentOutcome.NotFound(), BundleConsistency.NOT_READ);
        int after = 0;
        if (query.cursor() != null) {
            OptionalInt named = AdjustmentCursor.decode(userId, query, query.cursor());
            if (named.isEmpty()) return new AdjustmentRead(new AdjustmentOutcome.NotFound(), BundleConsistency.NOT_READ);
            after = named.getAsInt();
        }
        int from = after;
        AdjustmentOutcome outcome = Objects.requireNonNull(reads.execute(status -> page(userId, query, from)));
        BundleConsistency consistency = switch (outcome) {
            case AdjustmentOutcome.Found found -> BundleConsistency.CONSISTENT;
            case AdjustmentOutcome.Unavailable unavailable -> BundleConsistency.INCONSISTENT;
            case AdjustmentOutcome.NotFound notFound -> BundleConsistency.NOT_READ;
            case AdjustmentOutcome.NotPermitted denied -> BundleConsistency.NOT_READ;
        };
        // The same decision again, after the read: the page is dropped if the deletion won the race.
        if (!readable(userId)) return new AdjustmentRead(new AdjustmentOutcome.NotPermitted(), consistency);
        return new AdjustmentRead(outcome, consistency);
    }

    /**
     * The page, inside the one consistent read. The reference must be the active plan's pointer — its plan,
     * version and calculation — or it is not found. Then the version must agree with itself and its lineage:
     *
     * <ul>
     *   <li>it belongs to the plan and its terms, and the portfolio pointer cites its snapshot (the
     *       dashboard's AD-23 reliance: both pointers advance in one publication, the incremental sync's too);
     *   <li>its head holds as the calculation evidence judges it ({@link OfficialVersionHead}): the pointer's
     *       history, and for an incremental version the sync attempt that published it with the mode, start and
     *       extended verification the rules require, and the previous official version — so one version is
     *       unavailable to both reads or to neither;
     *   <li>its latest classification is the COMPLETE one of its candidate under its policy, with nothing held
     *       for review and an excluded count its excluded events agree with, and its latest conversion is that
     *       classification's calculable one under its price policy, both of its intent, portfolio, connection
     *       and provider account, with the counts of the rows that cite them;
     *   <li>every applied row holds by its own lineage ({@link AdjustmentHistoryRepository#totals}), the rows
     *       are the version's count, numbered {@code 1..n} in {@code (event time, source id)} order, the rows
     *       citing the latest conversion have the latest classification's deposits and withdrawals, and — for an
     *       incremental version (Story 2.11) — the carried rows are exactly the previous official version's rows
     *       at or before the collection start;
     *   <li>the exclusions are the latest classification's excluded events and, for an incremental version, the
     *       previous version's exclusions at or before the start, to the letter;
     *   <li>the conversion chain behind each conversion the rows cite ends where it should, and every earlier
     *       version in it carries every event that cites it.
     * </ul>
     *
     * <p>The previous version's own lineage is not walked again: its bundles were judged whole when it was
     * published. Every row's lineage is still judged here.
     */
    private AdjustmentOutcome page(UUID user, AdjustmentQuery query, int after) {
        var plan = plans.activePlan(user).orElse(null);
        if (plan == null || !plan.planId().equals(query.planId())) return new AdjustmentOutcome.NotFound();
        var pointer = publications.pointer(user, plan.planId()).orElse(null);
        if (pointer == null || pointer.version() != query.pointerVersion()
                || !pointer.calculationId().equals(query.calculationId()))
            return new AdjustmentOutcome.NotFound();
        // The reference is the current official pointer: from here on, a disagreement is a broken lineage.
        UUID calculationId = pointer.calculationId();
        var calculation = publications.calculation(user, calculationId).orElse(null);
        if (calculation == null || !PlanPublicationService.belongs(calculation, plan, pointer))
            return new AdjustmentOutcome.Unavailable();
        var portfolio = portfolios.officialValue(user).orElse(null);
        if (portfolio == null || !portfolio.snapshotId().equals(calculation.portfolioSnapshotId())
                || portfolio.publicationSequence() != calculation.portfolioSequence())
            return new AdjustmentOutcome.Unavailable();
        var lineage = publications.lineage(user, calculationId).orElse(null);
        if (lineage == null || heads.judge(user, plan, pointer, calculation, lineage) == null)
            return new AdjustmentOutcome.Unavailable();
        var classification = publications.classificationBundle(user, lineage.classificationId()).orElse(null);
        var counts = history.classificationCounts(user, lineage.classificationId()).orElse(null);
        if (classification == null || counts == null || !"COMPLETE".equals(classification.status())
                || !classification.candidateId().equals(lineage.candidateId())
                || !classification.policyVersion().equals(lineage.classificationPolicy())
                || !classification.intentId().equals(plan.intentId())
                || !classification.portfolioId().equals(calculation.portfolioId())
                || !classification.connectionId().equals(lineage.connectionId())
                || !classification.providerAccountId().equals(lineage.providerAccountId())
                || counts.review() != 0
                // The excluded count shown is the classification's own record, and its events must say the same.
                || counts.excluded() != counts.excludedEvents())
            return new AdjustmentOutcome.Unavailable();
        var conversion = publications.conversion(user, lineage.conversionId()).orElse(null);
        if (conversion == null || !conversion.calculable()
                || !("CONVERTED".equals(conversion.status()) || "CONVERTED_ESTIMATED".equals(conversion.status()))
                || !conversion.classificationId().equals(lineage.classificationId())
                || !conversion.policyVersion().equals(calculation.pricePolicy())
                || !conversion.intentId().equals(plan.intentId())
                || !conversion.portfolioId().equals(calculation.portfolioId())
                || !conversion.connectionId().equals(lineage.connectionId())
                || !conversion.providerAccountId().equals(lineage.providerAccountId()))
            return new AdjustmentOutcome.Unavailable();

        var totals = history.totals(user, calculationId, query.direction());
        if (totals.total() != calculation.flowCount() || totals.broken() != 0 || totals.disordered() != 0
                || totals.estimated() != calculation.estimatedFlowCount()
                || (totals.total() > 0 && (totals.firstSequence() != 1 || totals.lastSequence() != totals.total()))
                // The latest lineage's counts reach only the rows that cite it (Story 2.11).
                || totals.own() != counts.deposits() + counts.withdrawals() || totals.own() != conversion.flowCount()
                || totals.ownEstimated() != conversion.estimatedCount()
                || totals.ownInbound() != counts.deposits() || totals.own() - totals.ownInbound() != counts.withdrawals())
            return new AdjustmentOutcome.Unavailable();
        String net = PlanPublicationService.notation(totals.selectedSum());
        if (!canonical(net)) return new AdjustmentOutcome.Unavailable();

        // What an incremental version carried is the previous version's rows and exclusions at or before its start.
        UUID previous = lineage.previousCalculationId();
        var exclusions = publications.exclusions(calculationId, lineage.classificationId(), previous,
                lineage.collectionRangeStart());
        if (exclusions.own() != counts.excludedEvents() || exclusions.carriedBeyondStart() != 0
                || exclusions.carriedUnmatched() != 0)
            return new AdjustmentOutcome.Unavailable();
        if (previous == null) {
            if (totals.own() != totals.total() || exclusions.own() != exclusions.total())
                return new AdjustmentOutcome.Unavailable();
        } else {
            var carried = publications.carried(calculationId, lineage.conversionId(), previous,
                    lineage.collectionRangeStart());
            if (carried.rows() != totals.total() - totals.own() || carried.beyondStart() != 0 || carried.unmatched() != 0
                    || carried.rows() != carried.previousAtOrBeforeStart()
                    || exclusions.total() - exclusions.own() != exclusions.previousAtOrBeforeStart())
                return new AdjustmentOutcome.Unavailable();
        }

        /*
         * The conversion chain of every conversion the rows cite: the latest one and each carried row's own. Their
         * links are read at once; only a conversion that continues an earlier one has a chain to walk (an
         * incremental sync's conversion never does), and the coverage of every chain is counted in one query.
         */
        List<AdjustmentHistoryRepository.RowConversion> cited = history.rowConversions(user, calculationId);
        Map<UUID, AdjustmentHistoryRepository.ConversionLink> links = history.conversionLinks(user,
                cited.stream().map(AdjustmentHistoryRepository.RowConversion::conversionId).toList());
        Map<UUID, List<EarlierVersion>> earlierByConversion = new HashMap<>();
        Map<UUID, List<UUID>> chains = new HashMap<>();
        List<UUID> earlierIds = new ArrayList<>();
        for (var row : cited) {
            var link = links.get(row.conversionId());
            if (link == null || !link.classificationId().equals(row.classificationId()))
                return new AdjustmentOutcome.Unavailable();
            List<EarlierVersion> earlier = link.previousId() == null ? List.of()
                    : earlierVersions(user, row.classificationId(), row.conversionId());
            if (earlier == null) return new AdjustmentOutcome.Unavailable();
            earlierByConversion.put(row.conversionId(), earlier);
            if (earlier.isEmpty()) continue;
            List<UUID> ids = earlier.stream().map(EarlierVersion::conversionId).toList();
            chains.put(row.conversionId(), ids);
            earlierIds.addAll(ids);
        }
        Map<UUID, Integer> coverage = history.earlierCoverage(calculationId, chains);
        for (var row : cited) {
            List<UUID> ids = chains.getOrDefault(row.conversionId(), List.of());
            if (coverage.getOrDefault(row.conversionId(), 0) != row.rows() * ids.size())
                return new AdjustmentOutcome.Unavailable();
        }

        List<PageRow> fetched = history.page(user, calculationId, query.direction(), after, PAGE_SIZE + 1);
        List<PageRow> rows = fetched.size() > PAGE_SIZE ? fetched.subList(0, PAGE_SIZE) : fetched;
        List<UUID> events = rows.stream().map(PageRow::sourceEventId).toList();
        var legs = history.legs(calculationId, events);
        var earlierFlows = history.earlierFlows(earlierIds, events);
        List<AdjustmentHistory.Adjustment> adjustments = new ArrayList<>();
        for (PageRow row : rows) {
            adjustments.add(adjustment(row, calculationId, legs.getOrDefault(row.sourceEventId(), List.of()),
                    earlierByConversion.getOrDefault(row.conversionId(), List.of()), earlierFlows));
        }
        String next = fetched.size() > PAGE_SIZE
                ? AdjustmentCursor.encode(user, query, rows.getLast().sequence()) : null;
        return new AdjustmentOutcome.Found(new AdjustmentHistory(plan.planId(), pointer.version(), calculationId,
                query.direction(), pointer.publishedAt(),
                new AdjustmentHistory.Coverage(calculation.t0(), calculation.verifiedThrough()),
                new AdjustmentHistory.Summary(totals.selected(), net, exclusions.total()), List.copyOf(adjustments),
                next));
    }

    /**
     * An earlier conversion version: its own processing time, and — from the version that replaced it — why it
     * stopped being current and when that was found (the replacing version's processing time).
     */
    private record EarlierVersion(UUID conversionId, java.time.Instant processedAt, String changeReason,
            java.time.Instant discoveredAt) {}

    /**
     * The earlier conversion versions behind {@code current}, newest first, or {@code null} when the chain is
     * not one lineage: a link that points outside this user and classification, a chain that does not end
     * within {@link #MAX_CHAIN} links (the current one included), a link without its reason, or a version with
     * another flow count than the current one.
     */
    private @Nullable List<EarlierVersion> earlierVersions(UUID user, UUID classificationId, UUID current) {
        List<ChainLink> chain = history.chain(user, classificationId, current, MAX_CHAIN);
        if (chain.isEmpty() || !chain.getFirst().conversionId().equals(current)) return null;
        int flowCount = chain.getFirst().flowCount();
        List<EarlierVersion> earlier = new ArrayList<>();
        for (int index = 1; index < chain.size(); index++) {
            ChainLink replaced = chain.get(index);
            ChainLink replacing = chain.get(index - 1);
            String reason = replacing.changeReason();
            if (!replaced.conversionId().equals(replacing.previousId()) || reason == null
                    || replaced.flowCount() != flowCount)
                return null;
            earlier.add(new EarlierVersion(replaced.conversionId(), replaced.processedAt(), reason,
                    replacing.processedAt()));
        }
        // The walk ended because the last version has no predecessor — not because a link led elsewhere.
        if (chain.getLast().previousId() != null) return null;
        return earlier;
    }

    /**
     * One row. The change marker stands when the row's own conversion continues earlier ones and in at least
     * one of them this event had another value (or none); only then are the earlier versions listed, newest
     * first, each as it was stored. The references are the row's own lineage: a carried row cites the
     * classification and conversion that first applied it.
     */
    private static AdjustmentHistory.Adjustment adjustment(PageRow row, UUID calculationId,
            List<AdjustmentHistory.PriceLeg> legs, List<EarlierVersion> earlier,
            Map<UUID, Map<UUID, EarlierFlow>> earlierFlows) {
        List<AdjustmentHistory.Revision> revisions = new ArrayList<>();
        boolean changed = false;
        for (EarlierVersion version : earlier) {
            EarlierFlow flow = earlierFlows.getOrDefault(version.conversionId(), Map.of()).get(row.sourceEventId());
            // The coverage check read in the same snapshot says every earlier version carries every event.
            if (flow == null) throw new IllegalStateException("an earlier version without the applied event");
            String stored = flow.usdValue();
            String value = stored == null ? null : PlanPublicationService.notation(stored);
            if (value == null || !sameDecimal(value, row.conversionUsdValue())) changed = true;
            revisions.add(new AdjustmentHistory.Revision(version.conversionId(), version.processedAt(), flow.status(),
                    flow.reasonCode(), value, version.changeReason(), version.discoveredAt()));
        }
        String direction = row.usdValue().startsWith("-") ? "OUT" : "IN";
        return new AdjustmentHistory.Adjustment(row.sequence(), row.occurredAt(), direction, row.asset(),
                PlanPublicationService.notation(row.signedQuantity()), PlanPublicationService.notation(row.usdValue()),
                new AdjustmentHistory.Classification(row.transactionType(), row.ruleId(), row.classificationPolicy()),
                new AdjustmentHistory.Price(row.pricePolicy(), row.evidenceType(), row.estimated(),
                        PlanPublicationService.notation(row.unitPrice()), row.routeId(), List.copyOf(legs)),
                row.discoveredAt(),
                new AdjustmentHistory.References(row.sourceEventId(), row.sourceId(), row.classificationId(),
                        row.conversionId(), calculationId),
                changed, changed ? List.copyOf(revisions) : List.of());
    }

    private static boolean sameDecimal(String left, String right) {
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean canonical(String decimal) {
        return decimal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]*[1-9])?");
    }

    /** The account's own access: refused while its deletion is in progress. No command restriction. */
    private boolean readable(UUID user) {
        return accounts.decide(user, user, AccountAccessKind.SENSITIVE) == AccessDecision.ALLOWED;
    }
}
