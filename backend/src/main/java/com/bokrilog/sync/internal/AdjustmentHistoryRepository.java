package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncJobApi.AdjustmentDirection;
import com.bokrilog.sync.SyncJobApi.AdjustmentHistory.PriceLeg;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The adjustment history reads (Story 2.10). They run in the dashboard's kind of read-only
 * {@code REPEATABLE READ} transaction, after the pointer and the calculation version were read there, and
 * they read this module's own tables only: the version's applied rows, the calculable rows they cite, the
 * conversion flows and legs, the classification events and the source events those came from.
 *
 * <p>Nothing here reads an excluded, held or review-required event, another attempt's rows, a raw provider
 * field that is not a fact of the event (no wallet address or transaction hash is stored at all), or a
 * credential. Every read starts from the calculation version by its id and is scoped to the user.
 */
@Repository
class AdjustmentHistoryRepository {
    private final JdbcClient jdbc;

    AdjustmentHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The classification's counts: what it excluded apart, what it held for review, and its two directions —
     * as the bundle recorded them — and how many of its events actually carry the {@code EXCLUDED} verdict,
     * so the excluded count shown is checked against the events it counts.
     */
    record ClassificationCounts(int deposits, int withdrawals, int excluded, int review, int excludedEvents) {}

    Optional<ClassificationCounts> classificationCounts(UUID user, UUID classificationId) {
        return jdbc.sql("SELECT b.deposit_count,b.withdrawal_count,b.excluded_count,b.review_count,"
                + "(SELECT count(*) FROM sync_cashflow_classification_event e WHERE e.classification_id=b.classification_id "
                + "AND e.verdict='EXCLUDED') FROM sync_cashflow_classification_bundle b "
                + "WHERE b.classification_id=:c AND b.user_id=:u")
            .param("c", classificationId).param("u", user)
            .query((rs, n) -> new ClassificationCounts(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                    rs.getInt(5)))
            .optional();
    }

    /**
     * What the version's whole applied history adds up to, judged over every row — never one page.
     *
     * @param total every applied row
     * @param broken rows whose lineage does not hold (see {@link #totals})
     * @param disordered rows whose sequence does not follow {@code (event time, source id)}
     * @param estimated rows applied with an estimated price
     * @param inbound rows that are deposits
     * @param firstSequence the smallest sequence (0 without rows)
     * @param lastSequence the largest sequence (0 without rows)
     * @param selected rows in the direction filter
     * @param selectedSum their signed USD values added exactly, as PostgreSQL's {@code numeric} writes it
     * @param own rows citing the version's own (latest) conversion — the rest were carried (Story 2.11)
     * @param ownInbound own rows that are deposits
     * @param ownEstimated own rows applied with an estimated price
     */
    record Totals(int total, int broken, int disordered, int estimated, int inbound, int firstSequence,
            int lastSequence, int selected, String selectedSum, int own, int ownInbound, int ownEstimated) {}

    /**
     * One aggregate over the version's applied rows. A row holds when every link of <em>its own</em> lineage is
     * there and agrees with the others and with the version (Story 2.11: a carried row keeps the conversion,
     * classification and candidate that first applied it):
     *
     * <ul>
     *   <li>the calculable row, its conversion bundle (calculable, of the row's classification), the conversion
     *       flow, the classification bundle (COMPLETE) and event, the candidate's membership and the source event
     *       it came from all exist, for this user and the version's provider account;
     *   <li>a row citing the version's own conversion sits after the collection start and comes from the
     *       version's classification and candidate under the version's policies; a row citing another conversion
     *       is carried: the version is incremental, the row sits at or before the collection start, and the
     *       previous official version holds the same row to the letter;
     *   <li>the source id, the semantic hash, the event time, the asset, the signed quantity and the USD value
     *       are the same in every link that carries them (decimals compared as numbers), and the event time is
     *       inside {@code (t0, cashflowVerifiedThrough]} (the coverage the answer shows);
     *   <li>the classification is an external flow whose Bybit type and sign agree with the direction, under
     *       the policy its own classification recorded;
     *   <li>the price evidence kind, the estimated flag, the route and the number of legs agree:
     *       direct normalization has no leg, the spot routes one or two, numbered from zero, under the
     *       policy its own conversion recorded.
     * </ul>
     *
     * <p>The route ids are the price policy's own constants, passed as parameters.
     */
    Totals totals(UUID user, UUID calculationId, AdjustmentDirection direction) {
        return jdbc.sql("SELECT count(*),count(*) FILTER (WHERE NOT r.ok),"
                + "count(*) FILTER (WHERE r.previous_at IS NOT NULL AND (r.previous_at>r.occurred_at OR "
                + "(r.previous_at=r.occurred_at AND r.previous_source>=r.source_id))),"
                + "count(*) FILTER (WHERE r.estimated),count(*) FILTER (WHERE r.inbound),"
                + "COALESCE(min(r.sequence),0),COALESCE(max(r.sequence),0),count(*) FILTER (WHERE r.selected),"
                + "COALESCE(sum(r.usd) FILTER (WHERE r.selected),0)::text,count(*) FILTER (WHERE r.own),"
                + "count(*) FILTER (WHERE r.own AND r.inbound),count(*) FILTER (WHERE r.own AND r.estimated) FROM ("
                + "SELECT pf.sequence,pf.estimated,pf.occurred_at,pf.source_id COLLATE \"C\" AS source_id,"
                + "lag(pf.occurred_at) OVER w AS previous_at,lag(pf.source_id COLLATE \"C\") OVER w AS previous_source,"
                + "pf.usd_value::numeric AS usd,(pf.usd_value !~ '^-') AS inbound,"
                + "(pf.conversion_id=c.conversion_id) AS own,"
                + "(:direction='ALL' OR (:direction='IN')=(pf.usd_value !~ '^-')) AS selected,"
                + "COALESCE((" + ROW_HOLDS + "),false) AS ok "
                + "FROM sync_plan_calculation_flow pf "
                + "JOIN sync_plan_calculation c ON c.calculation_id=pf.calculation_id AND c.user_id=:u "
                + ROW_JOINS
                + "WHERE pf.calculation_id=:c WINDOW w AS (ORDER BY pf.sequence)) r")
            .param("u", user).param("c", calculationId).param("direction", direction.name())
            .param("direct", PriceConversionPolicy.DIRECT_ROUTE).param("usdt", PriceConversionPolicy.USDT_ROUTE)
            .param("cross", PriceConversionPolicy.USDC_CROSS_ROUTE)
            .query((rs, n) -> new Totals(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                    rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getString(9), rs.getInt(10), rs.getInt(11),
                    rs.getInt(12)))
            .single();
    }

    /** Each applied row's own lineage, joined by the row's conversion and that conversion's classification. */
    private static final String ROW_JOINS = "LEFT JOIN sync_calculable_cashflow cc ON cc.conversion_id=pf.conversion_id "
            + "AND cc.source_event_id=pf.source_event_id "
            + "LEFT JOIN sync_cashflow_conversion_bundle cvb ON cvb.conversion_id=pf.conversion_id AND cvb.user_id=:u "
            + "LEFT JOIN sync_cashflow_conversion_flow cf ON cf.conversion_id=pf.conversion_id "
            + "AND cf.source_event_id=pf.source_event_id "
            + "LEFT JOIN sync_cashflow_classification_bundle cb ON cb.classification_id=cc.classification_id "
            + "AND cb.user_id=:u "
            + "LEFT JOIN sync_cashflow_classification_event ce ON ce.classification_id=cc.classification_id "
            + "AND ce.source_event_id=pf.source_event_id "
            + "LEFT JOIN sync_initial_candidate_event ic ON ic.candidate_id=cb.candidate_id "
            + "AND ic.event_id=pf.source_event_id "
            + "LEFT JOIN sync_initial_source_event se ON se.event_id=pf.source_event_id "
            + "LEFT JOIN (SELECT l.conversion_id,l.source_event_id,count(*) AS legs,max(l.leg_index) AS last_leg "
            + "FROM sync_cashflow_conversion_leg l JOIN sync_plan_calculation_flow lf ON lf.conversion_id=l.conversion_id "
            + "AND lf.source_event_id=l.source_event_id WHERE lf.calculation_id=:c "
            + "GROUP BY l.conversion_id,l.source_event_id) lg "
            + "ON lg.conversion_id=pf.conversion_id AND lg.source_event_id=pf.source_event_id ";

    /** The lineage predicate of one applied row ({@link #totals}). Decimal columns cast here are CHECK-canonical. */
    private static final String ROW_HOLDS = "cc.source_event_id IS NOT NULL AND cvb.conversion_id IS NOT NULL "
            + "AND cf.source_event_id IS NOT NULL AND cb.classification_id IS NOT NULL "
            + "AND ce.source_event_id IS NOT NULL AND ic.event_id IS NOT NULL AND se.event_id IS NOT NULL "
            + "AND cc.user_id=:u AND se.user_id=:u AND cvb.calculable AND cvb.classification_id=cc.classification_id "
            + "AND cb.status='COMPLETE' AND cb.provider_account_id=c.provider_account_id "
            + "AND pf.provider_account_id=c.provider_account_id AND se.provider_account_id=c.provider_account_id "
            // own rows: the version's lineage, after its collection start; carried rows: the previous version's own
            + "AND ((pf.conversion_id=c.conversion_id AND cc.classification_id=c.classification_id "
            + "AND cb.candidate_id=c.candidate_id AND pf.occurred_at>c.collection_range_start "
            + "AND ce.policy_version=c.classification_policy_version AND cvb.policy_version=c.price_policy_version) "
            + "OR (pf.conversion_id<>c.conversion_id AND c.collection_mode<>'INITIAL' "
            + "AND pf.occurred_at<=c.collection_range_start AND EXISTS (SELECT 1 FROM sync_plan_calculation_flow pp "
            + "WHERE pp.calculation_id=c.previous_calculation_id AND pp.source_event_id=pf.source_event_id "
            + "AND pp.conversion_id=pf.conversion_id AND pp.source_id=pf.source_id AND pp.occurred_at=pf.occurred_at "
            + "AND pp.usd_value::numeric=pf.usd_value::numeric AND pp.estimated=pf.estimated "
            + "AND pp.evidence_type=pf.evidence_type AND pp.provider_account_id=pf.provider_account_id))) "
            // the source identity
            + "AND pf.source_id=cc.source_id AND cc.source_id=cf.source_id AND cf.source_id=ce.source_id "
            + "AND ce.source_id=se.source_id AND cc.semantic_hash=cf.semantic_hash "
            + "AND cf.semantic_hash=ce.semantic_hash AND ce.semantic_hash=se.semantic_hash "
            // the event time, inside (t0, cashflow_verified_through] — the range the answer shows as its coverage
            + "AND pf.occurred_at=cc.occurred_at AND cc.occurred_at=cf.occurred_at AND cf.occurred_at=ce.occurred_at "
            + "AND ce.occurred_at=se.transaction_time AND pf.occurred_at>c.t0 AND pf.occurred_at<=c.cashflow_verified_through "
            // the asset and the signed quantity (the source's raw cash flow is cast only when it is plain)
            + "AND cc.asset_code=cf.asset_code AND cf.asset_code=ce.asset_code AND ce.asset_code=se.currency "
            + "AND ce.signed_amount IS NOT NULL AND cc.signed_quantity::numeric=cf.signed_quantity::numeric "
            + "AND cf.signed_quantity::numeric=ce.signed_amount::numeric "
            + "AND (CASE WHEN se.cash_flow ~ '^-?[0-9]+([.][0-9]+)?$' THEN se.cash_flow::numeric END)"
            + "=ce.signed_amount::numeric "
            // the USD value
            + "AND cf.usd_value IS NOT NULL AND pf.usd_value::numeric=cc.usd_value::numeric "
            + "AND cc.usd_value::numeric=cf.usd_value::numeric "
            // the classification: an external flow whose type and sign agree with the direction
            + "AND ((ce.verdict='EXTERNAL_IN' AND se.event_type='TRANSFER_IN' AND ce.signed_amount !~ '^-' "
            + "AND pf.usd_value !~ '^-') OR (ce.verdict='EXTERNAL_OUT' AND se.event_type='TRANSFER_OUT' "
            + "AND ce.signed_amount ~ '^-' AND pf.usd_value ~ '^-')) "
            + "AND ce.rule_id=cf.classification_rule_id AND ce.policy_version=cf.classification_policy_version "
            + "AND ce.policy_version=cb.policy_version "
            // the price: evidence kind, estimated flag, route and legs
            + "AND cc.price_policy_version=cvb.policy_version AND pf.evidence_type=cc.evidence_type "
            + "AND cc.evidence_type=cf.evidence_type AND pf.estimated=cc.estimated AND cc.estimated=cf.estimated "
            + "AND cf.usd_unit_price IS NOT NULL AND cf.route_id IS NOT NULL "
            + "AND ((cf.status='CONVERTED' AND cf.evidence_type='DIRECT_NORMALIZATION' AND NOT cf.estimated "
            + "AND cf.route_id=:direct AND lg.legs IS NULL) OR (cf.status='CONVERTED_ESTIMATED' "
            + "AND cf.evidence_type='SPOT_KLINE_CLOSE' AND cf.estimated "
            + "AND ((cf.route_id=:usdt AND lg.legs=1) OR (cf.route_id=:cross AND lg.legs=2)) "
            + "AND lg.last_leg=lg.legs-1))";

    /**
     * One conversion the version's rows cite (Story 2.11: the own one and those of carried rows), with the
     * classification it converted and how many of the rows cite it.
     */
    record RowConversion(UUID conversionId, UUID classificationId, int rows) {}

    List<RowConversion> rowConversions(UUID user, UUID calculationId) {
        return jdbc.sql("SELECT pf.conversion_id,cc.classification_id,count(*) FROM sync_plan_calculation_flow pf "
                + "JOIN sync_calculable_cashflow cc ON cc.conversion_id=pf.conversion_id AND cc.source_event_id=pf.source_event_id "
                + "WHERE pf.calculation_id=:c AND cc.user_id=:u GROUP BY pf.conversion_id,cc.classification_id "
                + "ORDER BY pf.conversion_id")
            .param("c", calculationId).param("u", user)
            .query((rs, n) -> new RowConversion(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)))
            .list();
    }

    /** A cited conversion's own link: the classification it converted and the version it continued, if any. */
    record ConversionLink(UUID classificationId, @Nullable UUID previousId) {}

    /** The links of these conversions, this user's only, read at once; a conversion that is not there is absent. */
    Map<UUID, ConversionLink> conversionLinks(UUID user, List<UUID> conversions) {
        Map<UUID, ConversionLink> links = new HashMap<>();
        if (conversions.isEmpty()) return links;
        jdbc.sql("SELECT conversion_id,classification_id,previous_conversion_id FROM sync_cashflow_conversion_bundle "
                + "WHERE user_id=:u AND conversion_id IN (:c)")
            .param("u", user).param("c", conversions)
            .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class),
                    new ConversionLink(rs.getObject(2, UUID.class), rs.getObject(3, UUID.class))))
            .list().forEach(entry -> links.put(entry.getKey(), entry.getValue()));
        return links;
    }

    /** One link of the conversion chain: a version, the one before it, and why it replaced that one. */
    record ChainLink(UUID conversionId, @Nullable UUID previousId, @Nullable String changeReason,
            Instant processedAt, int flowCount) {}

    /**
     * The conversion chain from {@code conversionId} back through {@code previous_conversion_id}, newest first
     * (the current version first). The walk stays inside this user and classification and returns at most
     * {@code limit} links, the current one included; whether it ended where it should is the caller's judgment.
     */
    List<ChainLink> chain(UUID user, UUID classificationId, UUID conversionId, int limit) {
        return jdbc.sql("WITH RECURSIVE chain(conversion_id,previous_id,change_reason,processed_at,flow_count,depth) "
                + "AS (SELECT conversion_id,previous_conversion_id,change_reason,processed_at,flow_count,0 "
                + "FROM sync_cashflow_conversion_bundle WHERE conversion_id=:c AND user_id=:u AND classification_id=:cls "
                + "UNION ALL SELECT b.conversion_id,b.previous_conversion_id,b.change_reason,b.processed_at,b.flow_count,"
                + "chain.depth+1 FROM chain JOIN sync_cashflow_conversion_bundle b ON b.conversion_id=chain.previous_id "
                + "AND b.user_id=:u AND b.classification_id=:cls WHERE chain.depth+1<:limit) "
                + "SELECT conversion_id,previous_id,change_reason,processed_at,flow_count FROM chain ORDER BY depth")
            .param("c", conversionId).param("u", user).param("cls", classificationId).param("limit", limit)
            .query((rs, n) -> new ChainLink(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                    instant(rs, 4), rs.getInt(5)))
            .list();
    }

    /**
     * For each cited conversion, how many (applied row citing it, earlier version of its chain) pairs have a
     * conversion flow in that earlier version — every chain in one query. A conversion without earlier versions
     * is not in {@code chains} and not in the answer.
     */
    Map<UUID, Integer> earlierCoverage(UUID calculationId, Map<UUID, List<UUID>> chains) {
        Map<UUID, Integer> coverage = new HashMap<>();
        List<Object[]> pairs = new ArrayList<>();
        chains.forEach((cited, versions) -> versions.forEach(version -> pairs.add(new Object[] {cited, version})));
        if (pairs.isEmpty()) return coverage;
        StringBuilder values = new StringBuilder();
        for (int index = 0; index < pairs.size(); index++) {
            if (index > 0) values.append(',');
            values.append("(CAST(:c").append(index).append(" AS uuid),CAST(:e").append(index).append(" AS uuid))");
        }
        var statement = jdbc.sql("SELECT pf.conversion_id,count(*) FROM (VALUES " + values + ") AS p(cited,earlier) "
                + "JOIN sync_plan_calculation_flow pf ON pf.calculation_id=:calc AND pf.conversion_id=p.cited "
                + "JOIN sync_cashflow_conversion_flow f ON f.conversion_id=p.earlier AND f.source_event_id=pf.source_event_id "
                + "GROUP BY pf.conversion_id").param("calc", calculationId);
        for (int index = 0; index < pairs.size(); index++)
            statement = statement.param("c" + index, pairs.get(index)[0]).param("e" + index, pairs.get(index)[1]);
        statement.query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getInt(2)))
            .list().forEach(entry -> coverage.put(entry.getKey(), entry.getValue()));
        return coverage;
    }

    /** One applied row with the facts of its lineage the screen shows, and its own conversion and classification. */
    record PageRow(int sequence, UUID sourceEventId, String sourceId, Instant occurredAt, String usdValue,
            String transactionType, String asset, String signedQuantity, String ruleId, String classificationPolicy,
            String pricePolicy, String evidenceType, boolean estimated, String unitPrice, String routeId,
            Instant discoveredAt, String conversionUsdValue, UUID conversionId, UUID classificationId) {}

    /**
     * The applied rows after {@code after} in the direction filter, in the version's order, at most
     * {@code limit} of them. The lineage of every row was already judged whole ({@link #totals}).
     */
    List<PageRow> page(UUID user, UUID calculationId, AdjustmentDirection direction, int after, int limit) {
        return jdbc.sql("SELECT pf.sequence,pf.source_event_id,pf.source_id,pf.occurred_at,pf.usd_value,se.event_type,"
                + "cc.asset_code,cc.signed_quantity,ce.rule_id,ce.policy_version,cc.price_policy_version,cf.evidence_type,"
                + "cf.estimated,cf.usd_unit_price,cf.route_id,se.first_observed_at,cf.usd_value,pf.conversion_id,"
                + "cc.classification_id FROM sync_plan_calculation_flow pf "
                + "JOIN sync_plan_calculation c ON c.calculation_id=pf.calculation_id AND c.user_id=:u "
                + "JOIN sync_calculable_cashflow cc ON cc.conversion_id=pf.conversion_id "
                + "AND cc.source_event_id=pf.source_event_id "
                + "JOIN sync_cashflow_conversion_flow cf ON cf.conversion_id=pf.conversion_id "
                + "AND cf.source_event_id=pf.source_event_id "
                + "JOIN sync_cashflow_classification_event ce ON ce.classification_id=cc.classification_id "
                + "AND ce.source_event_id=pf.source_event_id "
                + "JOIN sync_initial_source_event se ON se.event_id=pf.source_event_id AND se.user_id=:u "
                + "WHERE pf.calculation_id=:c AND pf.sequence>:after "
                + "AND (:direction='ALL' OR (:direction='IN')=(pf.usd_value !~ '^-')) ORDER BY pf.sequence LIMIT :limit")
            .param("u", user).param("c", calculationId).param("after", after).param("direction", direction.name())
            .param("limit", limit)
            .query((rs, n) -> new PageRow(rs.getInt(1), rs.getObject(2, UUID.class), rs.getString(3), instant(rs, 4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                    rs.getString(10), rs.getString(11), rs.getString(12), rs.getBoolean(13), rs.getString(14),
                    rs.getString(15), instant(rs, 16), rs.getString(17), rs.getObject(18, UUID.class),
                    rs.getObject(19, UUID.class)))
            .list();
    }

    /** The price legs of the page's events, each from the conversion its row cites, in leg order. */
    Map<UUID, List<PriceLeg>> legs(UUID calculationId, List<UUID> events) {
        Map<UUID, List<PriceLeg>> legs = new HashMap<>();
        if (events.isEmpty()) return legs;
        jdbc.sql("SELECT l.source_event_id,l.symbol,l.candle_start_at,l.candle_end_at,l.close_price,l.selection_rule,"
                + "l.lag_minutes FROM sync_cashflow_conversion_leg l JOIN sync_plan_calculation_flow pf "
                + "ON pf.conversion_id=l.conversion_id AND pf.source_event_id=l.source_event_id "
                + "WHERE pf.calculation_id=:c AND l.source_event_id IN (:events) ORDER BY l.source_event_id,l.leg_index")
            .param("c", calculationId).param("events", events)
            .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), new PriceLeg(rs.getString(2), instant(rs, 3),
                    instant(rs, 4), rs.getString(5), rs.getString(6), rs.getInt(7))))
            .list()
            .forEach(entry -> legs.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue()));
        return legs;
    }

    /** What an earlier conversion version held for one event. */
    record EarlierFlow(String status, @Nullable String reasonCode, @Nullable String usdValue) {}

    /** The earlier versions' flows of the page's events, keyed by version then event. */
    Map<UUID, Map<UUID, EarlierFlow>> earlierFlows(List<UUID> versions, List<UUID> events) {
        Map<UUID, Map<UUID, EarlierFlow>> flows = new LinkedHashMap<>();
        if (versions.isEmpty() || events.isEmpty()) return flows;
        jdbc.sql("SELECT conversion_id,source_event_id,status,reason_code,usd_value FROM sync_cashflow_conversion_flow "
                + "WHERE conversion_id IN (:versions) AND source_event_id IN (:events)")
            .param("versions", versions).param("events", events)
            .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    new EarlierFlow(rs.getString(3), rs.getString(4), rs.getString(5))})
            .list()
            .forEach(row -> flows.computeIfAbsent((UUID) row[0], ignored -> new HashMap<>())
                    .put((UUID) row[1], (EarlierFlow) row[2]));
        return flows;
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
