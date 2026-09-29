package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceProvider;
import com.bokrilog.source.SourceVerification;
import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.sync.SyncJobApi.SyncTrigger;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 2.11: the active plan's incremental sync at the user's moment, against the real stack (PostgreSQL,
 * the workers and the Bybit stub). The {@code ABSOLUTE} stub scenario places events at absolute times, so the
 * first collection and a later sync observe the same events in their overlap, and a test decides which events
 * exist, what the wallet holds and when the provider starts failing.
 *
 * <p>The workers here claim every 50 ms and renew every 500 ms (the defaults are one and five seconds): the
 * durable rows are the authority either way, and every chain in this class runs through six claims. Cases that
 * can share one published first version run in order on it — each restores what it changed — because every
 * test starts from an empty store.
 *
 * <p>The properties make this context the class's own, and a cached context keeps its scheduler running: its
 * 50 ms workers would go on claiming the jobs of every later class in the shared database. So the class
 * cancels its context's scheduled tasks when it ends. It does not close the context — that would close the
 * PostgreSQL container every class shares.
 */
@TestPropertySource(properties = {"bokrilog.sync.worker.dispatch-delay-ms=50",
    "bokrilog.sync.worker.heartbeat-delay-ms=500"})
class PlanSyncTest extends AuthFlowTestBase {
    @Autowired private SourceConnectionApi source;
    @Autowired private PortfolioMembershipApi portfolio;
    @Autowired private SyncJobApi sync;
    @Autowired private PlanConditionApi plans;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private DataSource dataSource;

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofDays(1);

    @AfterAll
    static void stopThisContextsWorkers(ApplicationContext context) {
        context.getBeansOfType(ScheduledTaskHolder.class).values()
                .forEach(holder -> holder.getScheduledTasks().forEach(ScheduledTask::cancel));
    }

    // ------------------------------------------------------------------------------------------ success

    /**
     * ENTRY/MATRIX success (AC-1, AC-3~AC-6): a button press publishes the next version. Both pointers advance
     * in one transaction, the history records both versions, the plan terms stay, the watermark advances to the
     * new tc and the extended verification is carried (a normal sync). The version carries the earlier flow and
     * exclusion at or before its start with their original lineage, re-verifies the overlap and adds the events
     * that appeared late in it and after the last tc. The evidence and the history read the second version under
     * the 2.9 and 2.10 contracts, and a carried row that no longer matches the previous version is refused.
     */
    @Test
    void aManualSyncPublishesTheNextVersionAndAdvancesBothPointersTogether() throws Exception {
        var account = connect();
        Instant now = Instant.now();
        BybitStubConfiguration.absoluteEvents(account.uid(), List.of(
                event("overlap-in", "TRANSFER_IN", "USDT", "20", now.minus(Duration.ofHours(2)))));
        var v1 = publishFirst(account, now.minus(DAY.multipliedBy(3)),
                List.of(new Flow("carried-in", "USDT", "100", now.minus(DAY.multipliedBy(3)).plus(HOUR))),
                List.of(new Flow("carried-fee", "USDT", "-1", now.minus(DAY.multipliedBy(3)).plus(HOUR.multipliedBy(2)))));
        UUID user = account.user();
        var before = ready(user);
        assertThat(before.pointerVersion()).isEqualTo(1);
        assertThat(before.flowCount()).isEqualTo(2);
        Instant tc1 = before.basisAt();
        String planRow = planRow(user);

        // After the first version: an event that appears late inside the overlap, one after tc1, and a new balance.
        BybitStubConfiguration.absoluteEvents(account.uid(), List.of(
                event("overlap-in", "TRANSFER_IN", "USDT", "20", now.minus(Duration.ofHours(2))),
                event("late-in", "TRANSFER_IN", "USDT", "7.5", tc1.minus(HOUR)),
                event("new-out", "TRANSFER_OUT", "USDT", "-5", tc1.plusMillis(1))));
        BybitStubConfiguration.absoluteEquity(account.uid(), "1300.5");
        BybitStubConfiguration.resetRequestCount();

        var admitted = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL));
        assertThat(admitted.outcome()).isEqualTo("ADMITTED");
        assertThat(admitted.job().status()).isEqualTo("QUEUED");
        assertThat(admitted.job().stage()).isEqualTo("ADMITTED");
        assertThat(admitted.manual()).isEqualTo(new SyncJobApi.ManualSync(false, "IN_PROGRESS", null));
        assertThat(BybitStubConfiguration.requestCount()).as("the admission never calls the provider").isZero();
        UUID job = admitted.job().jobId();
        assertThat(awaitSync(job)).isEqualTo("PUBLISHED");

        var after = ready(user);
        assertThat(after.pointerVersion()).isEqualTo(2);
        assertThat(after.planId()).isEqualTo(v1.plan());
        assertThat(after.calculationId()).isNotEqualTo(v1.calculation());
        assertThat(after.metrics().getFirst().value()).as("the new current value").isEqualTo("1300.5");
        assertThat(after.basisAt()).isAfter(tc1);
        assertThat(after.cashflowVerifiedThrough()).as("the watermark is the new tc").isEqualTo(after.basisAt());
        assertThat(after.trust().freshness().state()).isEqualTo("FRESH");
        assertThat(after.flowCount()).isEqualTo(4);
        assertThat(planRow(user)).as("the plan terms are never modified").isEqualTo(planRow);

        var v2 = jdbc().sql("SELECT collection_mode,collection_range_start,last_extended_verification_at,"
                + "previous_calculation_id,portfolio_publication_sequence,created_by_job_id FROM sync_plan_calculation "
                + "WHERE calculation_id=:c").param("c", after.calculationId())
                .query((rs, n) -> new Object[] {rs.getString(1), instant(rs.getObject(2, OffsetDateTime.class)),
                        instant(rs.getObject(3, OffsetDateTime.class)), rs.getObject(4, UUID.class), rs.getLong(5),
                        rs.getObject(6, UUID.class)}).single();
        assertThat(v2[0]).isEqualTo("NORMAL");
        assertThat(v2[1]).as("max(t0, cashflow_verified_through - 24h)").isEqualTo(tc1.minus(Duration.ofHours(24)));
        assertThat(v2[2]).as("a normal sync carries the extended verification").isEqualTo(tc1);
        assertThat(v2[3]).isEqualTo(v1.calculation());
        assertThat(v2[4]).as("the portfolio pointer advanced by one").isEqualTo(3L);
        assertThat(v2[5]).isEqualTo(job);
        assertThat(jdbc().sql("SELECT c.publication_sequence FROM portfolio_current_snapshot c JOIN portfolio p "
                + "ON p.portfolio_id=c.portfolio_id WHERE p.user_id=:u").param("u", user).query(Long.class).single())
                .isEqualTo(3L);
        assertThat(jdbc().sql("SELECT pointer_version||'|'||(superseded_at IS NULL) FROM sync_official_plan_result_history "
                + "WHERE plan_id=:p ORDER BY pointer_version").param("p", v1.plan()).query(String.class).list())
                .containsExactly("1|false", "2|true");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_official_plan_result_history h JOIN sync_official_plan_result r "
                + "ON r.plan_id=h.plan_id WHERE h.plan_id=:p AND h.pointer_version=1 AND h.superseded_at=r.published_at")
                .param("p", v1.plan()).query(Integer.class).single()).as("v1 ends when v2 begins").isEqualTo(1);
        // Carried rows cite the first version's conversion; the others the new one.
        UUID v1Conversion = conversionOf(v1.calculation());
        assertThat(jdbc().sql("SELECT source_id||'|'||(conversion_id=:old) FROM sync_plan_calculation_flow "
                + "WHERE calculation_id=:c ORDER BY sequence").param("old", v1Conversion)
                .param("c", after.calculationId()).query(String.class).list())
                .containsExactly("carried-in|true", "overlap-in|false", "late-in|false", "new-out|false");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation_exclusion WHERE calculation_id=:c")
                .param("c", after.calculationId()).query(Integer.class).single()).as("the carried exclusion").isEqualTo(1);
        // The stage rows belong to the job and ended; nothing is left in progress.
        for (String table : new String[] {"sync_initial_collection_job", "sync_cashflow_classification_job",
                "sync_cashflow_conversion_job"}) {
            assertThat(jdbc().sql("SELECT status FROM " + table + " WHERE plan_sync_job_id=:j").param("j", job)
                    .query(String.class).list()).as(table).hasSize(1).doesNotContain("QUEUED", "RUNNING");
        }
        assertThat(jdbc().sql("SELECT t0 FROM sync_initial_collection_job WHERE plan_sync_job_id=:j").param("j", job)
                .query(OffsetDateTime.class).single().toInstant()).as("the collection starts at the overlap")
                .isEqualTo(v2[1]);
        // One reference time, the three account calls and one page per type: nothing more.
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.SERVER_TIME_PATH)).isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.QUERY_API_PATH)).isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.WALLET_BALANCE_PATH)).isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.TRANSACTION_LOG_PATH)).isEqualTo(2);
        assertThat(BybitStubConfiguration.requestCount()).isEqualTo(6);

        // The evidence of the second version: the 2.9 contract, with the collection and the version history.
        var evidence = evidenceOf(user);
        assertThat(evidence.pointerVersion()).isEqualTo(2);
        assertThat(evidence.collection().mode()).isEqualTo("NORMAL");
        assertThat(evidence.collection().rangeStart()).isEqualTo(v2[1]);
        assertThat(evidence.collection().inherited()).isEqualTo(new SyncJobApi.CalculationEvidence.Inherited(
                v1.calculation(), 1, 1, (Instant) v2[1]));
        assertThat(evidence.sourceRange().rangeStart()).isEqualTo(v2[1]);
        assertThat(evidence.sourceRange().rangeEnd()).isEqualTo(after.basisAt());
        assertThat(evidence.officialVersionCount()).isEqualTo(2);
        assertThat(evidence.officialVersions()).extracting(v -> v.pointerVersion() + "|" + v.current())
                .containsExactly("2|true", "1|false");
        assertThat(evidence.officialVersions().get(1).supersededAt()).isEqualTo(after.publishedAt());
        assertThat(evidence.cashflows()).isEqualTo(new SyncJobApi.CalculationEvidence.Cashflows(4, 0));

        // The history lists every applied flow from t0 on, in order, each with its own lineage.
        var history = historyOf(user, after);
        assertThat(history.rows()).extracting(SyncJobApi.AdjustmentHistory.Adjustment::sequence)
                .containsExactly(1, 2, 3, 4);
        assertThat(history.coverage()).isEqualTo(new SyncJobApi.AdjustmentHistory.Coverage(after.startAt(),
                after.cashflowVerifiedThrough()));
        assertThat(history.summary().excludedCount()).as("every exclusion of the verified range").isEqualTo(1);
        assertThat(history.rows().getFirst().references().conversionId()).isEqualTo(v1Conversion);

        // A carried row that is no longer the previous version's: neither read answers in part.
        UUID carried = jdbc().sql("SELECT source_event_id FROM sync_plan_calculation_flow WHERE calculation_id=:c "
                + "AND source_id='carried-in'").param("c", after.calculationId()).query(UUID.class).single();
        replica("UPDATE sync_plan_calculation_flow SET usd_value='101' WHERE calculation_id='" + after.calculationId()
                + "' AND source_event_id='" + carried + "'");
        try {
            assertThat(sync.adjustmentHistory(user, adjustmentQuery(after)).outcome())
                    .isInstanceOf(SyncJobApi.AdjustmentOutcome.Unavailable.class);
            assertThat(sync.calculationEvidence(user, reference(after)).outcome())
                    .isInstanceOf(SyncJobApi.EvidenceOutcome.Unavailable.class);
        } finally {
            replica("UPDATE sync_plan_calculation_flow SET usd_value='100' WHERE calculation_id='" + after.calculationId()
                    + "' AND source_event_id='" + carried + "'");
        }
        assertThat(sync.adjustmentHistory(user, adjustmentQuery(after)).outcome())
                .isInstanceOf(SyncJobApi.AdjustmentOutcome.Found.class);
        // The first version's references are stale now: the same 404 as any other.
        assertThat(sync.calculationEvidence(user, new SyncJobApi.EvidenceReference(v1.plan(), 1, v1.calculation()))
                .outcome()).isInstanceOf(SyncJobApi.EvidenceOutcome.NotFound.class);
    }

    /**
     * RANGE (AC-3): after 29 days without a sync and more than seven days since the last extended verification,
     * the next sync is extended: it starts seven days before the last verification and covers the whole gap in
     * contiguous windows of at most seven days — every type and page of each — with the event on a shared window
     * boundary counted once. The extended verification advances to the new tc; the sync after it is normal. The
     * versions of a plan are one line (V11): the first one's two times are its tc, no version continues itself,
     * and none is continued twice.
     */
    @Test
    void anExtendedSyncCoversTheWholeGapInWindowsOfAtMostSevenDays() throws Exception {
        var account = connect();
        var v1 = publishFirst(account, Instant.now().minus(DAY.multipliedBy(40)), List.of(), List.of());
        UUID user = account.user();
        ageVersion(user, DAY.multipliedBy(29));
        Instant verified = ready(user).cashflowVerifiedThrough();
        Instant start = verified.minus(DAY.multipliedBy(7));
        BybitStubConfiguration.absoluteEvents(account.uid(), List.of(
                event("boundary-in", "TRANSFER_IN", "USDT", "3", start.plus(DAY.multipliedBy(7))),
                event("gap-in", "TRANSFER_IN", "USDT", "4", start.plus(DAY.multipliedBy(20)))));

        UUID job = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("PUBLISHED");
        var after = ready(user);
        var row = jdbc().sql("SELECT collection_mode,collection_range_start,last_extended_verification_at FROM "
                + "sync_plan_calculation WHERE calculation_id=:c").param("c", after.calculationId())
                .query((rs, n) -> new Object[] {rs.getString(1), instant(rs.getObject(2, OffsetDateTime.class)),
                        instant(rs.getObject(3, OffsetDateTime.class))}).single();
        assertThat(row[0]).isEqualTo("EXTENDED");
        assertThat(row[1]).as("max(t0, cashflow_verified_through - 7 days)").isEqualTo(start);
        assertThat(row[2]).as("the extended verification advances to tc").isEqualTo(after.basisAt());
        UUID collection = jdbc().sql("SELECT job_id FROM sync_initial_collection_job WHERE plan_sync_job_id=:j")
                .param("j", job).query(UUID.class).single();
        var windows = jdbc().sql("SELECT range_start,range_end,count(DISTINCT event_type) FROM sync_initial_page_evidence "
                + "WHERE job_id=:j GROUP BY range_start,range_end ORDER BY range_start").param("j", collection)
                .query((rs, n) -> new Object[] {instant(rs.getObject(1, OffsetDateTime.class)),
                        instant(rs.getObject(2, OffsetDateTime.class)), rs.getInt(3)}).list();
        assertThat(windows).as("36 days in windows of at most seven").hasSize(6);
        Instant next = start;
        for (Object[] window : windows) {
            assertThat(window[0]).as("contiguous").isEqualTo(next);
            assertThat(Duration.between((Instant) window[0], (Instant) window[1])).isLessThanOrEqualTo(DAY.multipliedBy(7));
            assertThat(window[2]).as("both types").isEqualTo(2);
            next = (Instant) window[1];
        }
        assertThat(next).as("the closed upper bound is tc").isEqualTo(after.basisAt());
        assertThat(jdbc().sql("SELECT window_count||'|'||page_count FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", job).query(String.class).single()).isEqualTo("6|12");
        assertThat(jdbc().sql("SELECT source_id FROM sync_plan_calculation_flow WHERE calculation_id=:c ORDER BY sequence")
                .param("c", after.calculationId()).query(String.class).list())
                .as("the boundary event is seen by two windows and counted once").containsExactly("boundary-in", "gap-in");

        // The next sync, right after: the extended verification is recent, so it is normal again.
        clearCooldown(user);
        UUID second = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
        assertThat(awaitSync(second)).isEqualTo("PUBLISHED");
        var third = ready(user);
        assertThat(jdbc().sql("SELECT collection_mode||'|'||(last_extended_verification_at=:ext) FROM sync_plan_calculation "
                + "WHERE calculation_id=:c").param("ext", OffsetDateTime.ofInstant(after.basisAt(), ZoneOffset.UTC))
                .param("c", third.calculationId()).query(String.class).single()).isEqualTo("NORMAL|true");
        assertThat(third.pointerVersion()).isEqualTo(3);
        assertThat(evidenceOf(user).officialVersions()).extracting(SyncJobApi.CalculationEvidence.OfficialVersion::pointerVersion)
                .containsExactly(3L, 2L, 1L);

        // The line of versions is the schema's own rule, not only the writer's.
        assertThatThrownBy(() -> jdbc().sql("UPDATE sync_plan_calculation SET last_extended_verification_at="
                + "basis_at-interval '1 millisecond' WHERE calculation_id=:c").param("c", v1.calculation()).update())
                .as("the first version's extended verification is its tc").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().sql("UPDATE sync_plan_calculation SET collection_range_start="
                + "collection_range_start+interval '1 millisecond' WHERE calculation_id=:c").param("c", v1.calculation())
                .update()).as("the first version collected from t0").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().sql("UPDATE sync_plan_calculation SET previous_calculation_id=calculation_id "
                + "WHERE calculation_id=:c").param("c", third.calculationId()).update())
                .as("no version continues itself").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().sql("UPDATE sync_plan_calculation SET previous_calculation_id=:v1 "
                + "WHERE calculation_id=:c").param("v1", v1.calculation()).param("c", third.calculationId()).update())
                .as("no version is continued twice").isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * An event exactly at the collection start millisecond: Bybit's {@code startTime} is inclusive, so the
     * collection sees it again, but it belongs to the carried part. It is dropped from the new collection, the
     * new version carries it once with its first lineage, and the sync publishes.
     */
    @Test
    void anEventExactlyAtTheCollectionStartIsCarriedOnceAndTheSyncPublishes() throws Exception {
        var account = connect();
        Instant now = Instant.now();
        var v1 = publish(account, now.minus(DAY.multipliedBy(3)),
                tc -> List.of(new Flow("edge-in", "USDT", "5", tc.minus(Duration.ofHours(24)))), List.of());
        UUID user = account.user();
        Instant start = ready(user).cashflowVerifiedThrough().minus(Duration.ofHours(24));
        // The provider holds the same event at that millisecond; the sync's first window starts exactly there.
        BybitStubConfiguration.absoluteEvents(account.uid(), List.of(event("edge-in", "TRANSFER_IN", "USDT", "5", start)));

        UUID job = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("PUBLISHED");
        var after = ready(user);
        assertThat(jdbc().sql("SELECT collection_range_start FROM sync_plan_calculation WHERE calculation_id=:c")
                .param("c", after.calculationId()).query(OffsetDateTime.class).single().toInstant()).isEqualTo(start);
        assertThat(jdbc().sql("SELECT source_id||'|'||(conversion_id=:old) FROM sync_plan_calculation_flow "
                + "WHERE calculation_id=:c ORDER BY sequence").param("old", conversionOf(v1.calculation()))
                .param("c", after.calculationId()).query(String.class).list())
                .as("carried once, citing the first version's conversion").containsExactly("edge-in|true");
        assertThat(jdbc().sql("SELECT count(*) FROM sync_initial_event_provenance p JOIN sync_initial_collection_job j "
                + "ON j.job_id=p.job_id WHERE j.plan_sync_job_id=:j").param("j", job).query(Integer.class).single())
                .as("the new collection kept nothing at the start").isZero();
        assertThat(evidenceOf(user).collection().inherited().flowCount()).isEqualTo(1);
        assertThat(historyOf(user, after).rows()).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------ admission

    /**
     * RECENT, COOLDOWN, MERGE (AC-1, AC-2): entry and reactivation within five minutes admit nothing and call
     * no provider; a button press admits one attempt, and presses, tabs and retries in the same moment converge
     * on it; after it, a press within thirty seconds answers the cooldown with the seconds left.
     */
    @Test
    void admissionFollowsItsTimingRulesAndConvergesOnOneAttempt() throws Exception {
        var account = connect();
        publishFirst(account, null, List.of(), List.of());
        UUID user = account.user();
        BybitStubConfiguration.resetRequestCount();

        for (SyncTrigger automatic : new SyncTrigger[] {SyncTrigger.DASHBOARD_ENTRY, SyncTrigger.TAB_REACTIVATION}) {
            var recent = answer(sync.admitPlanSync(user, automatic));
            assertThat(recent.outcome()).as(automatic.name()).isEqualTo("RECENTLY_SYNCED");
            assertThat(recent.job()).isNull();
            assertThat(recent.manual()).as("the button is still available")
                    .isEqualTo(new SyncJobApi.ManualSync(true, null, null));
        }
        assertThat(jobCount(user)).isZero();
        assertThat(BybitStubConfiguration.requestCount()).isZero();

        // Many presses and tabs at once: one attempt, and every answer names it or the cooldown after it.
        var answers = new java.util.concurrent.ConcurrentLinkedQueue<SyncJobApi.SyncAnswer>();
        var go = new CountDownLatch(1);
        List<Thread> racers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            SyncTrigger trigger = i % 2 == 0 ? SyncTrigger.MANUAL : SyncTrigger.DASHBOARD_ENTRY;
            racers.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    answers.add(answer(sync.admitPlanSync(user, trigger)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        go.countDown();
        for (Thread racer : racers) racer.join(20_000);
        assertThat(jobCount(user)).as("one attempt in progress per plan").isEqualTo(1);
        UUID job = jdbc().sql("SELECT job_id FROM sync_plan_sync_job WHERE user_id=:u").param("u", user)
                .query(UUID.class).single();
        assertThat(answers).hasSize(6).filteredOn(a -> a.outcome().equals("ADMITTED")).hasSizeLessThanOrEqualTo(1);
        assertThat(answers).allSatisfy(a -> {
            assertThat(a.outcome()).isIn("ADMITTED", "IN_PROGRESS", "COOLDOWN", "RECENTLY_SYNCED");
            if (!a.outcome().equals("RECENTLY_SYNCED")) assertThat(a.job().jobId()).isEqualTo(job);
        });
        assertThat(awaitSync(job)).isEqualTo("PUBLISHED");

        var cooldown = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL));
        assertThat(cooldown.outcome()).isEqualTo("COOLDOWN");
        assertThat(cooldown.job().jobId()).isEqualTo(job);
        assertThat(cooldown.manual().reason()).isEqualTo("COOLDOWN");
        assertThat(cooldown.manual().retryAfterSeconds()).isBetween(1, 30);
        assertThat(sync.planSyncState(user).orElseThrow().manual().reason()).isEqualTo("COOLDOWN");
        assertThat(answer(sync.admitPlanSync(user, SyncTrigger.DASHBOARD_ENTRY)).outcome()).isEqualTo("RECENTLY_SYNCED");
        assertThat(jobCount(user)).isEqualTo(1);

        // Thirty seconds after the admission the button admits again; five minutes after it, entry does too.
        clearCooldown(user);
        assertThat(answer(sync.admitPlanSync(user, SyncTrigger.DASHBOARD_ENTRY)).outcome()).isEqualTo("RECENTLY_SYNCED");
        assertThat(sync.planSyncState(user).orElseThrow().manual()).isEqualTo(new SyncJobApi.ManualSync(true, null, null));
        jdbc().sql("UPDATE sync_plan_sync_job SET created_at=created_at-interval '5 minutes' WHERE job_id=:j")
                .param("j", job).update();
        var entry = answer(sync.admitPlanSync(user, SyncTrigger.DASHBOARD_ENTRY));
        assertThat(entry.outcome()).isEqualTo("ADMITTED");
        assertThat(entry.job().trigger()).isEqualTo("DASHBOARD_ENTRY");
        assertThat(awaitSync(entry.job().jobId())).isEqualTo("PUBLISHED");
    }

    /**
     * The admission's refusals, in its order, each without an attempt or a provider call. Each restriction the
     * sync runs through refuses on its own; a connection or membership that is not the official version's is a
     * changed source.
     */
    @Test
    void admissionRefusesInItsOrderWithoutAnAttemptOrAProviderCall() throws Exception {
        UUID empty = accountLifecycle().createAccount();
        assertThat(answer(sync.admitPlanSync(empty, SyncTrigger.MANUAL)))
                .isEqualTo(new SyncJobApi.SyncAnswer("NOT_AVAILABLE", "NO_ACTIVE_PLAN", null,
                        new SyncJobApi.ManualSync(false, "NO_ACTIVE_PLAN", null)));
        assertThat(sync.admitPlanSync(null, SyncTrigger.MANUAL)).isInstanceOf(SyncJobApi.SyncAdmission.NotPermitted.class);

        var account = connect();
        var v1 = publishFirst(account, null, List.of(), List.of());
        UUID user = account.user();
        BybitStubConfiguration.resetRequestCount();
        for (String command : new String[] {"plan-sync", "first-snapshot", "initial-source-event-collection",
                "plan-publication"}) {
            restrictCommand(command);
            try {
                var restricted = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL));
                assertThat(restricted.outcome()).as(command).isEqualTo("NOT_AVAILABLE");
                assertThat(restricted.reason()).as(command + " alone").isEqualTo("SYNC_RESTRICTED");
                assertThat(restricted.manual()).isEqualTo(new SyncJobApi.ManualSync(false, "SYNC_RESTRICTED", null));
            } finally {
                jdbc().sql("TRUNCATE TABLE operations_restriction").update();
            }
        }

        jdbc().sql("UPDATE portfolio_source_membership SET ended_at=now() WHERE portfolio_id IN "
                + "(SELECT portfolio_id FROM portfolio WHERE user_id=:u)").param("u", user).update();
        assertThat(answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).reason()).isEqualTo("SOURCE_CHANGED");
        jdbc().sql("UPDATE portfolio_source_membership SET ended_at=NULL WHERE portfolio_id IN "
                + "(SELECT portfolio_id FROM portfolio WHERE user_id=:u)").param("u", user).update();
        jdbc().sql("UPDATE source_connection SET status='REVOKED' WHERE user_id=:u").param("u", user).update();
        var revoked = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL));
        assertThat(revoked.reason()).isEqualTo("SOURCE_CHANGED");
        assertThat(revoked.manual()).isEqualTo(new SyncJobApi.ManualSync(false, "SOURCE_CHANGED", null));
        jdbc().sql("UPDATE source_connection SET status='ACTIVE' WHERE user_id=:u").param("u", user).update();

        var ends = OffsetDateTime.ofInstant(databaseNow(), ZoneOffset.UTC);
        jdbc().sql("UPDATE plan_definition SET end_at=:e WHERE plan_id=:p").param("e", ends).param("p", v1.plan()).update();
        jdbc().sql("UPDATE sync_plan_calculation SET end_at=:e WHERE plan_id=:p").param("e", ends).param("p", v1.plan())
                .update();
        var ended = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL));
        assertThat(ended.outcome()).isEqualTo("NOT_AVAILABLE");
        assertThat(ended.reason()).isEqualTo("PLAN_PERIOD_ENDED");
        assertThat(ready(user).trust().freshness().cause()).isEqualTo("PLAN_PERIOD_ENDED");

        accountDeletion().beginDeletion(user);
        assertThat(sync.admitPlanSync(user, SyncTrigger.MANUAL)).isInstanceOf(SyncJobApi.SyncAdmission.NotPermitted.class);
        assertThat(sync.planSyncState(user)).isEmpty();
        assertThat(jobCount(user)).isZero();
        assertThat(BybitStubConfiguration.requestCount()).isZero();
    }

    /**
     * A lost attempt never answers as in progress: a queued job that waited more than five minutes is never
     * claimed — the dispatcher ends it as interrupted on its next tick, and an admission clears its plan's lost
     * jobs before it judges the one in progress — so the next press is admitted.
     */
    @Test
    void aQueuedJobThatWaitedTooLongEndsAsInterruptedAndTheNextPressIsAdmitted() throws Exception {
        var account = connect();
        var v1 = publishFirst(account, null, List.of(), List.of());
        UUID user = account.user();
        UUID stale = UUID.randomUUID();
        insertJob(stale, user, v1, ready(user).cashflowVerifiedThrough(), Duration.ofMinutes(6));
        assertThat(awaitSync(stale)).isEqualTo("FAILED");
        assertThat(failureOf(stale)).isEqualTo("SYNC_INTERRUPTED");
        assertThat(jdbc().sql("SELECT (claimed_at IS NULL)||'|'||fencing_generation FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", stale).query(String.class).single()).as("never claimed").isEqualTo("true|0");

        UUID again = UUID.randomUUID();
        insertJob(again, user, v1, ready(user).cashflowVerifiedThrough(), Duration.ofMinutes(6));
        var admitted = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL));
        assertThat(admitted.outcome()).as("the lost job is not the one in progress").isEqualTo("ADMITTED");
        assertThat(admitted.job().jobId()).isNotEqualTo(again);
        assertThat(jdbc().sql("SELECT status||'|'||failure_code FROM sync_plan_sync_job WHERE job_id=:j")
                .param("j", again).query(String.class).single()).isEqualTo("FAILED|SYNC_INTERRUPTED");
        assertThat(awaitSync(admitted.job().jobId())).isEqualTo("PUBLISHED");
    }

    /**
     * GAP (AC-1): more than thirty days unverified admits nothing and calls no provider; the result stays and is
     * "long gap, choice required". Just under thirty days is an ordinary (extended) sync. A worker whose fixed tc
     * finds the gap too long ends before any account call.
     */
    @Test
    void aGapOfMoreThanThirtyDaysAdmitsNothingAndCallsNoProvider() throws Exception {
        var account = connect();
        var v1 = publishFirst(account, Instant.now().minus(DAY.multipliedBy(45)), List.of(), List.of());
        UUID user = account.user();
        ageVersion(user, DAY.multipliedBy(30).plus(Duration.ofMinutes(1)));
        BybitStubConfiguration.resetRequestCount();
        for (SyncTrigger trigger : SyncTrigger.values()) {
            var gap = answer(sync.admitPlanSync(user, trigger));
            assertThat(gap.outcome()).as(trigger.name()).isEqualTo("LONG_GAP");
            assertThat(gap.manual()).isEqualTo(new SyncJobApi.ManualSync(false, "LONG_GAP", null));
        }
        assertThat(jobCount(user)).isZero();
        assertThat(BybitStubConfiguration.requestCount()).isZero();
        var stale = ready(user);
        assertThat(stale.calculationId()).as("the last normal result stays").isEqualTo(v1.calculation());
        assertThat(stale.trust().freshness().cause()).isEqualTo("LONG_GAP_CHOICE_REQUIRED");
        assertThat(stale.trust().freshness().nextAction()).isEqualTo("REVIEW_LONG_GAP");

        // The worker judges the gap again against its own tc, before any account call.
        UUID late = UUID.randomUUID();
        insertJob(late, user, v1, Instant.now().minus(DAY.multipliedBy(31)), Duration.ZERO);
        assertThat(awaitSync(late)).isEqualTo("BLOCKED");
        assertThat(failureOf(late)).isEqualTo("LONG_GAP_CHOICE_REQUIRED");
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.SERVER_TIME_PATH)).isEqualTo(1);
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.QUERY_API_PATH)).isZero();
        assertThat(BybitStubConfiguration.requestCount(DevBybitStub.TRANSACTION_LOG_PATH)).isZero();

        ageVersion(user, DAY.multipliedBy(30).minus(Duration.ofMinutes(1)));
        clearCooldown(user);
        var admitted = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL));
        assertThat(admitted.outcome()).isEqualTo("ADMITTED");
        assertThat(awaitSync(admitted.job().jobId())).isEqualTo("PUBLISHED");
        assertThat(admitted.job().jobId()).isNotEqualTo(late);
        assertThat(jdbc().sql("SELECT mode FROM sync_plan_sync_job WHERE job_id=:j").param("j", admitted.job().jobId())
                .query(String.class).single()).isEqualTo("EXTENDED");
    }

    // ------------------------------------------------------------------------------------------ endings

    /** One ending the sync can reach on a shared first version, with what sets it up and what undoes it. */
    private record Ending(String name, String status, String code, boolean retryable, ThrowingRunnable setUp,
            ThrowingRunnable tearDown) {}

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * REVIEW (AC-5): an applied flow of the overlap that changed, a price that cannot be verified, an
     * unrecognized sign, a conflict, a malformed log, a vanished event — nothing is published, the watermark and
     * the pointer stay, and the input reliability says review required. The cases run in the pipeline's order
     * on one first version: an event a case adds stays at the provider, and each later case stops at an earlier
     * stage than the ones before it.
     */
    @Test
    void aReviewPublishesNothingAndKeepsTheWatermark() throws Exception {
        var account = connect();
        Instant now = Instant.now();
        var known = event("known-in", "TRANSFER_IN", "USDT", "10", now.minus(Duration.ofHours(2)));
        var halt = event("halt-in", "TRANSFER_IN", "HALT", "2.5", now.minus(Duration.ofMinutes(10)));
        var sign = event("sign-in", "TRANSFER_IN", "USDT", "-3", now.minus(Duration.ofMinutes(1)));
        BybitStubConfiguration.absoluteEvents(account.uid(), List.of(known));
        var v1 = publishFirst(account, now.minus(DAY.multipliedBy(3)), List.of(), List.of());
        UUID user = account.user();
        String uid = account.uid();
        String applied = jdbc().sql("SELECT usd_value FROM sync_plan_calculation_flow WHERE calculation_id=:c")
                .param("c", v1.calculation()).query(String.class).single();
        var cases = List.of(
                new Ending("APPLIED", "REVIEW_REQUIRED", "APPLIED_FLOW_CHANGED", false,
                        () -> replica("UPDATE sync_plan_calculation_flow SET usd_value='9' WHERE calculation_id='"
                                + v1.calculation() + "'"),
                        () -> replica("UPDATE sync_plan_calculation_flow SET usd_value='" + applied
                                + "' WHERE calculation_id='" + v1.calculation() + "'")),
                new Ending("PRICE", "REVIEW_REQUIRED", "CONVERSION_REQUIRED", false,
                        () -> BybitStubConfiguration.absoluteEvents(uid, List.of(known, halt)), () -> { }),
                new Ending("SIGN", "REVIEW_REQUIRED", "CLASSIFICATION_REVIEW_REQUIRED", false,
                        () -> BybitStubConfiguration.absoluteEvents(uid, List.of(known, halt, sign)), () -> { }),
                new Ending("CONFLICT", "REVIEW_REQUIRED", "COLLECTION_REVIEW_REQUIRED", false,
                        () -> BybitStubConfiguration.absoluteEvents(uid, List.of(
                                event("known-in", "TRANSFER_IN", "USDT", "11", now.minus(Duration.ofHours(2))), halt, sign)),
                        () -> { }),
                new Ending("MALFORMED_LOG", "REVIEW_REQUIRED", "COLLECTION_REVIEW_REQUIRED", false,
                        () -> BybitStubConfiguration.absoluteFailure(uid, DevBybitStub.AbsoluteFailure.MALFORMED_LOG),
                        () -> BybitStubConfiguration.absoluteFailure(uid, null)),
                new Ending("VANISHED", "REVIEW_REQUIRED", "COLLECTION_REVIEW_REQUIRED", false,
                        () -> BybitStubConfiguration.absoluteEvents(uid, List.of()), () -> { }));
        for (Ending ending : cases) {
            UUID job = endWith(user, ending);
            var dashboard = ready(user);
            assertThat(dashboard.trust().freshness().state()).as(ending.name()).isEqualTo("STALE");
            assertThat(dashboard.trust().freshness().cause()).as(ending.name()).isEqualTo("LAST_SYNC_REVIEW_REQUIRED");
            assertThat(dashboard.trust().inputReliability().state()).as(ending.name()).isEqualTo("REVIEW_REQUIRED");
            assertThat(dashboard.trust().inputReliability().cause()).isEqualTo("LATEST_INPUTS_REVIEW_REQUIRED");
            assertThat(jdbc().sql("SELECT status FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                    .query(String.class).single()).isEqualTo("REVIEW_REQUIRED");
        }
    }

    /**
     * FAIL (AC-7) and the worker's own refusals of tc: the reference time (unavailable, malformed, before the
     * last verification, past the plan's end), the provider (a refused key, a 429 on either account call, a
     * timeout, an outage, a log that is not JSON), a price not settled yet, the calculation's range — nothing is
     * published, the result says "not fresh" with the cause, and whether another attempt can help comes from the
     * closed table. All on one first version, each case undoing its own change.
     */
    @Test
    void aFailurePublishesNothingAndSaysWhetherAnotherAttemptCanHelp() throws Exception {
        var account = connect();
        publishFirst(account, Instant.now().minus(DAY.multipliedBy(3)), List.of(), List.of());
        UUID user = account.user();
        String uid = account.uid();
        ThrowingRunnable timeNormal = () -> BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.NORMAL);
        ThrowingRunnable providerNormal = () -> BybitStubConfiguration.absoluteFailure(uid, null);
        var cases = List.of(
                new Ending("TIME_OUTAGE", "FAILED", "REFERENCE_TIME_UNAVAILABLE", true,
                        () -> BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.OUTAGE), timeNormal),
                new Ending("TIME_MALFORMED", "FAILED", "REFERENCE_TIME_INVALID", true,
                        () -> BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.MALFORMED), timeNormal),
                new Ending("TIME_BEFORE_THE_LAST_VERIFICATION", "FAILED", "REFERENCE_TIME_INVALID", true,
                        () -> BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.PAST), timeNormal),
                new Ending("TIME_PAST_THE_PLAN_END", "BLOCKED", "PLAN_PERIOD_ENDED", false,
                        () -> BybitStubConfiguration.serverTimeMode(DevBybitStub.ServerTimeMode.FUTURE), timeNormal),
                new Ending("AUTH", "FAILED", "SOURCE_REJECTED", false,
                        () -> BybitStubConfiguration.absoluteFailure(uid, DevBybitStub.AbsoluteFailure.AUTH), providerNormal),
                new Ending("RATE_LIMIT", "FAILED", "SOURCE_UNAVAILABLE", true,
                        () -> BybitStubConfiguration.absoluteFailure(uid, DevBybitStub.AbsoluteFailure.RATE_LIMIT),
                        providerNormal),
                new Ending("ACCOUNT_RATE_LIMIT", "FAILED", "SOURCE_UNAVAILABLE", true,
                        () -> BybitStubConfiguration.absoluteFailure(uid, DevBybitStub.AbsoluteFailure.ACCOUNT_RATE_LIMIT),
                        providerNormal),
                new Ending("ACCOUNT_TIMEOUT", "FAILED", "SOURCE_UNAVAILABLE", true,
                        () -> BybitStubConfiguration.absoluteFailure(uid, DevBybitStub.AbsoluteFailure.ACCOUNT_TIMEOUT),
                        providerNormal),
                new Ending("LOG_OUTAGE", "FAILED", "SOURCE_UNAVAILABLE", true,
                        () -> BybitStubConfiguration.absoluteFailure(uid, DevBybitStub.AbsoluteFailure.LOG_OUTAGE),
                        providerNormal),
                new Ending("NOT_JSON_LOG", "FAILED", "SOURCE_UNAVAILABLE", true,
                        () -> BybitStubConfiguration.absoluteFailure(uid, DevBybitStub.AbsoluteFailure.NOT_JSON_LOG),
                        providerNormal),
                new Ending("RANGE", "FAILED", "CALCULATION_RANGE_EXCEEDED", false,
                        () -> BybitStubConfiguration.absoluteEquity(uid, "1" + "0".repeat(30)),
                        () -> BybitStubConfiguration.absoluteEquity(uid, "1234.56")),
                // Last: the event it adds stays with the provider, and a later case would find it again.
                new Ending("CONVERSION_PENDING", "FAILED", "CONVERSION_PENDING", true,
                        () -> BybitStubConfiguration.absoluteEvents(uid, List.of(event("down-in", "TRANSFER_IN", "DOWN",
                                "1", Instant.now().minus(Duration.ofMinutes(10))))),
                        () -> { }));
        for (Ending ending : cases) {
            BybitStubConfiguration.resetRequestCount();
            endWith(user, ending);
            if (ending.name().startsWith("TIME"))
                assertThat(BybitStubConfiguration.requestCount(DevBybitStub.QUERY_API_PATH)).as(ending.name())
                        .as("no account call without a reference time").isZero();
            var dashboard = ready(user);
            assertThat(dashboard.trust().freshness().cause()).as(ending.name())
                    .isEqualTo(ending.status().equals("BLOCKED") ? "LAST_SYNC_BLOCKED" : "LAST_SYNC_FAILED");
            assertThat(dashboard.trust().freshness().nextAction()).as(ending.name())
                    .isEqualTo(ending.retryable() ? "SYNC_NOW" : "NONE");
            assertThat(dashboard.trust().inputReliability().state()).as("a failure is not a review").isEqualTo("CONFIRMED");
            assertThat(dashboard.metrics().getFirst().value()).as("never a zero in its place").isEqualTo("1234.56");
        }
    }

    /**
     * An ending: the cooldown cleared, the case set up, one admission to its end with the expected status, code
     * and retry verdict, the pointer, watermark and value untouched, then the case undone.
     */
    private UUID endWith(UUID user, Ending ending) throws Exception {
        var before = snapshotOf(user);
        clearCooldown(user);
        UUID job;
        try {
            ending.setUp().run();
            job = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
            assertThat(awaitSync(job)).as(ending.name()).isEqualTo(ending.status());
        } finally {
            ending.tearDown().run();
        }
        assertThat(failureOf(job)).as(ending.name()).isEqualTo(ending.code());
        assertThat(retryableOf(job)).as(ending.name()).isEqualTo(ending.retryable());
        assertThat(snapshotOf(user)).as(ending.name() + ": pointer, watermark and value are the last normal ones")
                .isEqualTo(before);
        return job;
    }

    /**
     * After an attempt that ended in a review or a failure, a publication makes the result fresh and confirmed
     * again: the last attempt counts only when it was admitted at or after the current pointer's publication.
     */
    @Test
    void aPublicationAfterAReviewAndAFailureIsFreshAndConfirmedAgain() throws Exception {
        var account = connect();
        Instant now = Instant.now();
        BybitStubConfiguration.absoluteEvents(account.uid(), List.of(
                event("known-in", "TRANSFER_IN", "USDT", "10", now.minus(Duration.ofHours(2)))));
        var v1 = publishFirst(account, now.minus(DAY.multipliedBy(3)), List.of(), List.of());
        UUID user = account.user();
        String applied = jdbc().sql("SELECT usd_value FROM sync_plan_calculation_flow WHERE calculation_id=:c")
                .param("c", v1.calculation()).query(String.class).single();
        endWith(user, new Ending("APPLIED", "REVIEW_REQUIRED", "APPLIED_FLOW_CHANGED", false,
                () -> replica("UPDATE sync_plan_calculation_flow SET usd_value='9' WHERE calculation_id='"
                        + v1.calculation() + "'"),
                () -> replica("UPDATE sync_plan_calculation_flow SET usd_value='" + applied + "' WHERE calculation_id='"
                        + v1.calculation() + "'")));
        assertThat(ready(user).trust().inputReliability().state()).isEqualTo("REVIEW_REQUIRED");
        endWith(user, new Ending("LOG_OUTAGE", "FAILED", "SOURCE_UNAVAILABLE", true,
                () -> BybitStubConfiguration.absoluteFailure(account.uid(), DevBybitStub.AbsoluteFailure.LOG_OUTAGE),
                () -> BybitStubConfiguration.absoluteFailure(account.uid(), null)));
        assertThat(ready(user).trust().freshness().cause()).isEqualTo("LAST_SYNC_FAILED");

        clearCooldown(user);
        UUID published = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
        assertThat(awaitSync(published)).isEqualTo("PUBLISHED");
        var fresh = ready(user);
        assertThat(fresh.pointerVersion()).isEqualTo(2);
        assertThat(fresh.trust().freshness().state()).isEqualTo("FRESH");
        assertThat(fresh.trust().freshness().nextAction()).isEqualTo("NONE");
        assertThat(fresh.trust().inputReliability().state()).isEqualTo("CONFIRMED");

        // The boundary of "since the pointer became official": one millisecond before does not count, at it does.
        insertEnded(published, fresh.publishedAt().minusMillis(1));
        assertThat(ready(user).trust().freshness().state()).as("admitted before the publication").isEqualTo("FRESH");
        insertEnded(published, fresh.publishedAt());
        var counted = ready(user).trust().freshness();
        assertThat(counted.state()).as("admitted at the publication").isEqualTo("STALE");
        assertThat(counted.cause()).isEqualTo("LAST_SYNC_FAILED");
        assertThat(counted.nextAction()).isEqualTo("SYNC_NOW");
    }

    /** A finished failed attempt on the published one's basis, admitted at {@code createdAt}. */
    private void insertEnded(UUID like, Instant createdAt) {
        jdbc().sql("INSERT INTO sync_plan_sync_job(job_id,user_id,plan_id,portfolio_id,intent_id,membership_id,"
                + "connection_id,provider_account_id,trigger,status,stage,failure_code,retryable,basis_calculation_id,"
                + "basis_pointer_version,basis_verified_through,basis_extended_at,created_at,finished_at) SELECT :j,user_id,"
                + "plan_id,portfolio_id,intent_id,membership_id,connection_id,provider_account_id,'MANUAL','FAILED',"
                + "'COLLECTION','SOURCE_UNAVAILABLE',true,basis_calculation_id,basis_pointer_version,basis_verified_through,"
                + "basis_extended_at,:created,statement_timestamp() FROM sync_plan_sync_job WHERE job_id=:like")
                .param("j", UUID.randomUUID()).param("created", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .param("like", like).update();
    }

    /**
     * AC-3: a failure at any write of the publishing transaction leaves no new row and moves no pointer. Each
     * interruption writes one line with the stage it reached and the failure's class — no id, no message.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aFailureInjectedAtAnyPublicationWriteRollsTheWholeVersionBack(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var account = connect();
        Instant now = Instant.now();
        publishFirst(account, now.minus(DAY.multipliedBy(3)),
                List.of(new Flow("inject-in", "USDT", "50", now.minus(DAY.multipliedBy(3)).plus(HOUR))),
                List.of(new Flow("inject-fee", "USDT", "-1", now.minus(DAY.multipliedBy(3)).plus(HOUR.multipliedBy(2)))));
        UUID user = account.user();
        Map<String, Integer> counts = rowCounts(user);
        String[][] writes = {{"sync_source_snapshot", "INSERT"}, {"portfolio_snapshot", "INSERT"},
            {"portfolio_snapshot_component", "INSERT"}, {"portfolio_current_snapshot", "UPDATE"},
            {"sync_plan_calculation", "INSERT"}, {"sync_plan_calculation_metric", "INSERT"},
            {"sync_plan_calculation_flow", "INSERT"}, {"sync_plan_calculation_exclusion", "INSERT"},
            {"sync_official_plan_result", "UPDATE"}, {"sync_official_plan_result_history", "UPDATE"},
            {"sync_official_plan_result_history", "INSERT"}};
        for (String[] write : writes) {
            String table = write[0];
            String name = table + " " + write[1];
            endWith(user, new Ending(name, "FAILED", "SYNC_INTERRUPTED", true, () -> {
                jdbc().sql("CREATE FUNCTION fail_sync_step() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                        + "IF " + ownedBy(table, user) + " THEN RAISE EXCEPTION 'injected sync failure detail'; END IF; "
                        + "RETURN NEW; END $$").update();
                jdbc().sql("CREATE TRIGGER fail_sync_step BEFORE " + write[1] + " ON " + table
                        + " FOR EACH ROW EXECUTE FUNCTION fail_sync_step()").update();
            }, () -> {
                jdbc().sql("DROP TRIGGER fail_sync_step ON " + table).update();
                jdbc().sql("DROP FUNCTION fail_sync_step()").update();
            }));
            assertThat(rowCounts(user)).as(name + ": no new row anywhere").isEqualTo(counts);
        }
        var interrupted = output.getAll().lines().filter(line -> line.contains("plan sync interrupted:")).toList();
        List<UUID> jobs = jdbc().sql("SELECT job_id FROM sync_plan_sync_job WHERE user_id=:u").param("u", user)
                .query(UUID.class).list();
        assertThat(interrupted).hasSizeGreaterThanOrEqualTo(writes.length).allSatisfy(line -> {
            assertThat(line).containsPattern("plan sync interrupted: stage=PUBLICATION failure=[A-Za-z0-9_.$]+$")
                    .doesNotContain("injected sync failure detail").doesNotContain(user.toString());
            jobs.forEach(id -> assertThat(line).doesNotContain(id.toString()));
        });
        // The failures are gone: a new attempt publishes.
        clearCooldown(user);
        UUID retry = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
        assertThat(awaitSync(retry)).isEqualTo("PUBLISHED");
    }

    /**
     * LATE (AC-8): while the worker waits to publish, a stale fence, a deletion, a restriction, an ended
     * membership, a pointer that moved or a connection that changed — nothing is published, each with its own
     * ending, and a deletion wins over the late worker. A lease that expired is never continued: it ends as
     * interrupted. Each change breaks its own account; one other user's result stays untouched through all.
     */
    @Test
    void aLateWorkerPublishesNothing() throws Exception {
        var kept = connect();
        publishFirst(kept, null, List.of(), List.of());
        String[][] changes = {{"FENCE", null, null}, {"DELETION", null, null},
            {"RESTRICTION", "BLOCKED", "NOT_PERMITTED"}, {"MEMBERSHIP", "BLOCKED", "MEMBERSHIP_CHANGED"},
            {"POINTER", "BLOCKED", "POINTER_CONFLICT"}, {"CONNECTION", "BLOCKED", "SOURCE_CHANGED"}};
        for (String[] each : changes) {
            try {
                lateWorker(each[0], each[1], each[2]);
            } finally {
                jdbc().sql("TRUNCATE TABLE operations_restriction").update();
            }
            assertThat(ready(kept.user()).pointerVersion()).as(each[0] + ": another user's result is untouched")
                    .isEqualTo(1);
        }
    }

    private void lateWorker(String change, @Nullable String status, @Nullable String code) throws Exception {
        var account = connect();
        var v1 = publishFirst(account, null, List.of(), List.of());
        UUID user = account.user();
        var before = snapshotOf(user);
        UUID job;
        AutoCloseable lock = holdPlanLock(user);
        try {
            job = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
            awaitBlockedOnPlanLock();
            switch (change) {
                case "FENCE" -> jdbc().sql("UPDATE sync_plan_sync_job SET fencing_generation=fencing_generation+1 "
                        + "WHERE job_id=:j").param("j", job).update();
                case "DELETION" -> {
                    accountDeletion().beginDeletion(user);
                    portfolio.erasePortfolioData(user);
                    sync.eraseSyncData(user);
                    plans.erasePlanData(user);
                    source.eraseSourceData(user);
                }
                case "RESTRICTION" -> restrictCommand("plan-sync");
                case "MEMBERSHIP" -> jdbc().sql("UPDATE portfolio_source_membership SET ended_at=now() WHERE portfolio_id IN "
                        + "(SELECT portfolio_id FROM portfolio WHERE user_id=:u)").param("u", user).update();
                case "CONNECTION" -> jdbc().sql("UPDATE source_connection SET status='REVOKED' WHERE user_id=:u")
                        .param("u", user).update();
                default -> jdbc().sql("UPDATE sync_official_plan_result SET pointer_version=pointer_version+1 "
                        + "WHERE plan_id=:p").param("p", v1.plan()).update();
            }
        } finally {
            lock.close();
        }
        switch (change) {
            case "FENCE" -> {
                awaitPlanLockReleased(user);
                assertThat(jdbc().sql("SELECT status||'|'||(finished_at IS NULL) FROM sync_plan_sync_job WHERE job_id=:j")
                        .param("j", job).query(String.class).single()).as("the late worker writes nothing")
                        .isEqualTo("RUNNING|true");
                assertThat(snapshotOf(user)).isEqualTo(before);
                // Its lease runs out: the job ends as interrupted, never continued.
                jdbc().sql("UPDATE sync_plan_sync_job SET lease_until=now()-interval '1 second' WHERE job_id=:j")
                        .param("j", job).update();
                assertThat(awaitSync(job)).isEqualTo("FAILED");
                assertThat(failureOf(job)).isEqualTo("SYNC_INTERRUPTED");
            }
            case "DELETION" -> {
                awaitPlanLockReleased(user);
                for (String table : new String[] {"sync_plan_sync_job", "sync_plan_calculation", "sync_official_plan_result",
                        "sync_official_plan_result_history", "sync_initial_collection_job", "sync_initial_candidate_bundle",
                        "sync_cashflow_classification_bundle", "sync_cashflow_conversion_bundle", "sync_source_snapshot"}) {
                    assertThat(jdbc().sql("SELECT count(*) FROM " + table + " WHERE user_id=:u").param("u", user)
                            .query(Integer.class).single()).as(table).isZero();
                }
            }
            default -> {
                assertThat(awaitSync(job)).as(change).isEqualTo(status);
                assertThat(failureOf(job)).as(change).isEqualTo(code);
                assertThat(jdbc().sql("SELECT count(*) FROM sync_plan_calculation WHERE user_id=:u").param("u", user)
                        .query(Integer.class).single()).isEqualTo(1);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ the worker

    /**
     * The job's heartbeat renews its running stage rows with it, so a sync that runs longer than one lease still
     * publishes: while its collection waits on slow pages, both leases are cut to one second, and the heartbeat
     * carries them past it.
     */
    @Test
    void theHeartbeatRenewsTheStageRowsAndASyncLongerThanOneLeasePublishes() throws Exception {
        var account = connect();
        publishFirst(account, Instant.now().minus(DAY.multipliedBy(3)), List.of(), List.of());
        UUID user = account.user();
        BybitStubConfiguration.absoluteFailure(account.uid(), DevBybitStub.AbsoluteFailure.SLOW_LOG);
        try {
            UUID job = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
            UUID stage = awaitRunningStage(job);
            jdbc().sql("UPDATE sync_plan_sync_job SET lease_until=now()+interval '1 second' WHERE job_id=:j")
                    .param("j", job).update();
            jdbc().sql("UPDATE sync_initial_collection_job SET lease_until=now()+interval '1 second' WHERE job_id=:j")
                    .param("j", stage).update();
            boolean renewed = false;
            for (int i = 0; i < 150 && !renewed; i++) {
                renewed = jdbc().sql("SELECT (SELECT lease_until>now()+interval '20 seconds' FROM sync_plan_sync_job "
                        + "WHERE job_id=:j) AND (SELECT lease_until>now()+interval '20 seconds' OR status<>'RUNNING' "
                        + "FROM sync_initial_collection_job WHERE job_id=:s)").param("j", job).param("s", stage)
                        .query(Boolean.class).single();
                if (!renewed) Thread.sleep(20);
            }
            assertThat(renewed).as("both leases renewed past the one second they were cut to").isTrue();
            assertThat(awaitSync(job)).as("longer than the cut lease, and still published").isEqualTo("PUBLISHED");
        } finally {
            BybitStubConfiguration.absoluteFailure(account.uid(), null);
        }
    }

    private UUID awaitRunningStage(UUID job) throws InterruptedException {
        for (int i = 0; i < 3000; i++) {
            List<UUID> running = jdbc().sql("SELECT job_id FROM sync_initial_collection_job WHERE plan_sync_job_id=:j "
                    + "AND status='RUNNING'").param("j", job).query(UUID.class).list();
            if (!running.isEmpty()) return running.getFirst();
            Thread.sleep(20);
        }
        throw new AssertionError("the collection stage never ran");
    }

    /**
     * A sync's stage rows belong to it: even when one looks running under an expired lease, Story 2.4~2.6's
     * dispatchers never claim it. Another user's own collection, classification and conversion — newer rows, so
     * behind these in each claim order — are claimed and finish meanwhile.
     */
    @Test
    void theStageDispatchersNeverClaimASyncsStageRows() throws Exception {
        var account = connect();
        publishFirst(account, null, List.of(), List.of());
        UUID user = account.user();
        UUID job = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("PUBLISHED");
        String[] tables = {"sync_initial_collection_job", "sync_cashflow_classification_job", "sync_cashflow_conversion_job"};
        Map<String, List<Map<String, Object>>> saved = new TreeMap<>();
        for (String table : tables) {
            saved.put(table, jdbc().sql("SELECT * FROM " + table + " WHERE plan_sync_job_id=:j").param("j", job)
                    .query().listOfRows());
        }
        jdbc().sql("UPDATE sync_initial_collection_job SET status='RUNNING',failure_code=NULL,finished_at=NULL,"
                + "lease_until=now()-interval '1 second' WHERE plan_sync_job_id=:j").param("j", job).update();
        jdbc().sql("UPDATE sync_cashflow_classification_job SET status='RUNNING',failure_code=NULL,finished_at=NULL,"
                + "lease_until=now()-interval '1 second' WHERE plan_sync_job_id=:j").param("j", job).update();
        jdbc().sql("UPDATE sync_cashflow_conversion_job SET status='RUNNING',failure_code=NULL,finished_at=NULL,"
                + "retryable=false,conversion_id=NULL,lease_until=now()-interval '1 second' WHERE plan_sync_job_id=:j")
                .param("j", job).update();
        String fences = fencesOf(job);
        try {
            // Another user's whole chain runs through the three claims meanwhile.
            publishFirst(connect(), null, List.of(), List.of());
            assertThat(fencesOf(job)).as("never claimed: the same status, fence and expired lease").isEqualTo(fences);
            assertThat(fences).doesNotContain("QUEUED").contains("RUNNING|1|true");
        } finally {
            // Each row back as it ended, in one statement (a finished conversion row has its conversion id).
            for (String table : tables) {
                for (var row : saved.get(table)) {
                    boolean conversion = table.equals("sync_cashflow_conversion_job");
                    var restore = jdbc().sql("UPDATE " + table + " SET status=:s,failure_code=:f,finished_at=:at,"
                            + "lease_until=:l" + (conversion ? ",conversion_id=:c,retryable=:r" : "") + " WHERE job_id=:j")
                            .param("s", row.get("status")).param("f", row.get("failure_code"))
                            .param("at", row.get("finished_at")).param("l", row.get("lease_until"))
                            .param("j", row.get("job_id"));
                    if (conversion) restore = restore.param("c", row.get("conversion_id")).param("r", row.get("retryable"));
                    restore.update();
                }
            }
        }
    }

    /** Each stage row's status, fence and whether its lease is still the expired one. */
    private String fencesOf(UUID job) {
        return jdbc().sql("SELECT string_agg(t||':'||status||'|'||fencing_generation||'|'||(lease_until<now()),',' "
                + "ORDER BY t) FROM (SELECT 'collection' t,status,fencing_generation,lease_until FROM "
                + "sync_initial_collection_job WHERE plan_sync_job_id=:j UNION ALL SELECT 'classification',status,"
                + "fencing_generation,lease_until FROM sync_cashflow_classification_job WHERE plan_sync_job_id=:j "
                + "UNION ALL SELECT 'conversion',status,fencing_generation,lease_until FROM sync_cashflow_conversion_job "
                + "WHERE plan_sync_job_id=:j) s").param("j", job).query(String.class).single();
    }

    /**
     * Story 2.4 is unchanged by the adapter's reading of transport failures: a first collection whose Transaction
     * Log answers a body that is not JSON still ends as a retry, not as a review.
     */
    @Test
    void aFirstCollectionWhoseLogIsNotJsonStillAsksForARetry() throws Exception {
        var account = connect();
        UUID intent = intentOf(account, null);
        BybitStubConfiguration.absoluteFailure(account.uid(), DevBybitStub.AbsoluteFailure.NOT_JSON_LOG);
        try {
            UUID collection = sync.admitInitialInputs(account.user(), intent, "sync-inputs-" + UUID.randomUUID())
                    .orElseThrow().jobId();
            assertThat(awaitInitial(account.user(), collection)).isEqualTo("RETRY_REQUIRED");
            assertThat(sync.initialCollectionJob(account.user(), collection).orElseThrow().failureCode())
                    .isEqualTo("PROVIDER_UNAVAILABLE");
        } finally {
            BybitStubConfiguration.absoluteFailure(account.uid(), null);
        }
    }

    // ------------------------------------------------------------------------------------------ lineage

    /**
     * BROKEN (2.9 AC-6, 2.10): the second version's head — its creating attempt, its recorded mode, start and
     * extended verification, the rows it carried, the pointer's history — disagrees with itself in one way at a
     * time. The calculation evidence and the adjustment history judge it alike: both refuse (503), and once the
     * break is undone both answer again.
     */
    @Test
    void anIncrementalVersionWhoseHeadDisagreesIsUnavailableToBothReads() throws Exception {
        var account = connect();
        Instant now = Instant.now();
        var v1 = publishFirst(account, now.minus(DAY.multipliedBy(3)),
                List.of(new Flow("head-in", "USDT", "30", now.minus(DAY.multipliedBy(3)).plus(HOUR))),
                List.of(new Flow("head-fee", "USDT", "-1", now.minus(DAY.multipliedBy(3)).plus(HOUR.multipliedBy(2)))));
        UUID user = account.user();
        UUID job = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("PUBLISHED");
        var v2 = ready(user);
        String calc = v2.calculationId().toString();
        String plan = v1.plan().toString();
        UUID carriedEvent = jdbc().sql("SELECT source_event_id FROM sync_plan_calculation_flow WHERE calculation_id=:c "
                + "AND source_id='head-in'").param("c", v2.calculationId()).query(UUID.class).single();
        String v2Row = rowJson("sync_plan_calculation_flow", "calculation_id='" + calc + "' AND source_event_id='"
                + carriedEvent + "'");
        String v1Row = rowJson("sync_plan_calculation_flow", "calculation_id='" + v1.calculation()
                + "' AND source_event_id='" + carriedEvent + "'");
        String v1History = rowJson("sync_official_plan_result_history", "plan_id='" + plan + "' AND pointer_version=1");
        OffsetDateTime extended = jdbc().sql("SELECT last_extended_verification_at FROM sync_plan_calculation "
                + "WHERE calculation_id=:c").param("c", v2.calculationId()).query(OffsetDateTime.class).single();
        record Break(String name, ThrowingRunnable apply, ThrowingRunnable undo) {}
        var breaks = List.of(
                new Break("the creating attempt did not publish",
                        () -> jdbc().sql("UPDATE sync_plan_sync_job SET status='FAILED',failure_code='SYNC_INTERRUPTED',"
                                + "retryable=true,calculation_id=NULL,published_pointer_version=NULL WHERE job_id=:j")
                                .param("j", job).update(),
                        () -> jdbc().sql("UPDATE sync_plan_sync_job SET status='PUBLISHED',failure_code=NULL,retryable=false,"
                                + "calculation_id=CAST(:c AS uuid),published_pointer_version=2 WHERE job_id=:j")
                                .param("c", calc).param("j", job).update()),
                new Break("a mode the verification times do not give",
                        () -> {
                            jdbc().sql("UPDATE sync_plan_calculation SET collection_mode='EXTENDED',"
                                    + "last_extended_verification_at=basis_at WHERE calculation_id=CAST(:c AS uuid)")
                                    .param("c", calc).update();
                            jdbc().sql("UPDATE sync_plan_sync_job SET mode='EXTENDED' WHERE job_id=:j").param("j", job).update();
                        },
                        () -> {
                            jdbc().sql("UPDATE sync_plan_calculation SET collection_mode='NORMAL',"
                                    + "last_extended_verification_at=:e WHERE calculation_id=CAST(:c AS uuid)")
                                    .param("e", extended).param("c", calc)
                                    .update();
                            jdbc().sql("UPDATE sync_plan_sync_job SET mode='NORMAL' WHERE job_id=:j").param("j", job).update();
                        }),
                new Break("a collection start the rule does not give",
                        () -> {
                            jdbc().sql("UPDATE sync_plan_calculation SET collection_range_start=collection_range_start+"
                                    + "interval '1 millisecond' WHERE calculation_id=CAST(:c AS uuid)").param("c", calc).update();
                            jdbc().sql("UPDATE sync_plan_sync_job SET range_start=range_start+interval '1 millisecond' "
                                    + "WHERE job_id=:j").param("j", job).update();
                        },
                        () -> {
                            jdbc().sql("UPDATE sync_plan_calculation SET collection_range_start=collection_range_start-"
                                    + "interval '1 millisecond' WHERE calculation_id=CAST(:c AS uuid)").param("c", calc).update();
                            jdbc().sql("UPDATE sync_plan_sync_job SET range_start=range_start-interval '1 millisecond' "
                                    + "WHERE job_id=:j").param("j", job).update();
                        }),
                new Break("an extended verification a normal sync does not carry",
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET last_extended_verification_at="
                                + "last_extended_verification_at-interval '1 millisecond' WHERE calculation_id=CAST(:c AS uuid)")
                                .param("c", calc).update(),
                        () -> jdbc().sql("UPDATE sync_plan_calculation SET last_extended_verification_at="
                                + "last_extended_verification_at+interval '1 millisecond' WHERE calculation_id=CAST(:c AS uuid)")
                                .param("c", calc).update()),
                new Break("a carried row that is missing",
                        () -> replica("DELETE FROM sync_plan_calculation_flow WHERE calculation_id='" + calc
                                + "' AND source_event_id='" + carriedEvent + "'"),
                        () -> replicaInsert("sync_plan_calculation_flow", v2Row)),
                new Break("a carried row the previous version does not hold",
                        () -> replica("DELETE FROM sync_plan_calculation_flow WHERE calculation_id='" + v1.calculation()
                                + "' AND source_event_id='" + carriedEvent + "'"),
                        () -> replicaInsert("sync_plan_calculation_flow", v1Row)),
                new Break("the previous version did not end when this one began",
                        () -> jdbc().sql("UPDATE sync_official_plan_result_history SET superseded_at=superseded_at-"
                                + "interval '1 millisecond' WHERE plan_id=CAST(:p AS uuid) AND pointer_version=1")
                                .param("p", plan).update(),
                        () -> jdbc().sql("UPDATE sync_official_plan_result_history SET superseded_at=superseded_at+"
                                + "interval '1 millisecond' WHERE plan_id=CAST(:p AS uuid) AND pointer_version=1")
                                .param("p", plan).update()),
                new Break("the history's current version became official at another time",
                        () -> jdbc().sql("UPDATE sync_official_plan_result_history SET published_at=published_at-"
                                + "interval '1 millisecond' WHERE plan_id=CAST(:p AS uuid) AND pointer_version=2")
                                .param("p", plan).update(),
                        () -> jdbc().sql("UPDATE sync_official_plan_result_history SET published_at=published_at+"
                                + "interval '1 millisecond' WHERE plan_id=CAST(:p AS uuid) AND pointer_version=2")
                                .param("p", plan).update()),
                new Break("the previous version is missing from the history",
                        () -> jdbc().sql("DELETE FROM sync_official_plan_result_history WHERE plan_id=CAST(:p AS uuid) "
                                + "AND pointer_version=1").param("p", plan).update(),
                        () -> replicaInsert("sync_official_plan_result_history", v1History)));
        for (Break each : breaks) {
            each.apply().run();
            try {
                assertThat(sync.calculationEvidence(user, reference(v2)).outcome()).as(each.name() + ": the evidence")
                        .isInstanceOf(SyncJobApi.EvidenceOutcome.Unavailable.class);
                assertThat(sync.adjustmentHistory(user, adjustmentQuery(v2)).outcome()).as(each.name() + ": the history")
                        .isInstanceOf(SyncJobApi.AdjustmentOutcome.Unavailable.class);
            } finally {
                each.undo().run();
            }
            assertThat(sync.calculationEvidence(user, reference(v2)).outcome()).as(each.name() + " undone")
                    .isInstanceOf(SyncJobApi.EvidenceOutcome.Found.class);
            assertThat(sync.adjustmentHistory(user, adjustmentQuery(v2)).outcome()).as(each.name() + " undone")
                    .isInstanceOf(SyncJobApi.AdjustmentOutcome.Found.class);
        }
    }

    /**
     * V11 on a store that V10 left: a plan published before this story is copied, row by row, into a database of
     * its own migrated to V10, and V11 then backfills it. The backfilled calculation columns, exclusions and
     * history are exactly what this story writes for a first version, and — put in place of the native ones —
     * the evidence and the history read them, and the next sync publishes from them.
     */
    @Test
    void theV11BackfillOfAPlanPublishedBeforeItReadsAndSyncs() throws Exception {
        var account = connect();
        Instant now = Instant.now();
        var v1 = publishFirst(account, now.minus(DAY.multipliedBy(3)),
                List.of(new Flow("backfill-in", "USDT", "40", now.minus(DAY.multipliedBy(3)).plus(HOUR))),
                List.of(new Flow("backfill-fee", "USDT", "-1", now.minus(DAY.multipliedBy(3)).plus(HOUR.multipliedBy(2)))));
        UUID user = account.user();
        var hikari = (HikariDataSource) dataSource;
        String database = "v11_backfill_" + UUID.randomUUID().toString().replace("-", "");
        String url = hikari.getJdbcUrl().replaceFirst("(jdbc:postgresql://[^/]+/)([^?]+)", "$1" + database);
        jdbc().sql("CREATE DATABASE " + database).update();
        try {
            // The driver is a runtime dependency: DriverManager finds it, and each connection is a plain one.
            var side = new DriverManagerDataSource(url, hikari.getUsername(), hikari.getPassword());
            Flyway.configure().dataSource(side).locations("classpath:db/migration").cleanDisabled(true).target("10")
                    .load().migrate();
            copyUserRows(side, user);
            Flyway.configure().dataSource(side).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
            var other = JdbcClient.create(side);

            String calc = v1.calculation().toString();
            String columns = "SELECT collection_mode||'|'||collection_range_start::text||'|'||"
                    + "last_extended_verification_at::text||'|'||(previous_calculation_id IS NULL)||'|'||"
                    + "(collection_range_start=t0)||'|'||(last_extended_verification_at=basis_at) FROM sync_plan_calculation "
                    + "WHERE calculation_id='" + calc + "'";
            String exclusions = "SELECT COALESCE(string_agg(classification_id::text||'|'||source_event_id::text||'|'||"
                    + "occurred_at::text,',' ORDER BY source_event_id),'') FROM sync_plan_calculation_exclusion "
                    + "WHERE calculation_id='" + calc + "'";
            String history = "SELECT string_agg(plan_id::text||'|'||pointer_version||'|'||user_id::text||'|'||"
                    + "calculation_id::text||'|'||published_at::text||'|'||COALESCE(superseded_at::text,'-'),',') FROM "
                    + "sync_official_plan_result_history WHERE plan_id='" + v1.plan() + "'";
            String backfilledColumns = other.sql(columns).query(String.class).single();
            assertThat(backfilledColumns).as("INITIAL, from t0, extended at tc").startsWith("INITIAL|")
                    .endsWith("|true|true|true");
            assertThat(backfilledColumns).as("what this story writes for a first version")
                    .isEqualTo(jdbc().sql(columns).query(String.class).single());
            assertThat(other.sql(exclusions).query(String.class).single()).as("the excluded event")
                    .isNotEmpty().isEqualTo(jdbc().sql(exclusions).query(String.class).single());
            assertThat(other.sql(history).query(String.class).single()).as("the one official version, current")
                    .endsWith("|-").isEqualTo(jdbc().sql(history).query(String.class).single());
            for (String stage : new String[] {"sync_initial_collection_job", "sync_cashflow_classification_job",
                    "sync_cashflow_conversion_job"}) {
                assertThat(other.sql("SELECT count(*) FROM " + stage + " WHERE plan_sync_job_id IS NOT NULL")
                        .query(Integer.class).single()).as(stage).isZero();
            }

            // The backfilled rows in place of the native ones.
            String backfilledExclusions = other.sql("SELECT COALESCE(json_agg(row_to_json(x))::text,'[]') FROM "
                    + "sync_plan_calculation_exclusion x WHERE calculation_id='" + calc + "'").query(String.class).single();
            String backfilledHistory = other.sql("SELECT json_agg(row_to_json(h))::text FROM "
                    + "sync_official_plan_result_history h WHERE plan_id='" + v1.plan() + "'").query(String.class).single();
            var backfilled = other.sql("SELECT collection_mode,collection_range_start,last_extended_verification_at FROM "
                    + "sync_plan_calculation WHERE calculation_id='" + calc + "'").query().singleRow();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc().sql("DELETE FROM sync_plan_calculation_exclusion WHERE calculation_id=CAST(:c AS uuid)")
                        .param("c", calc).update();
                jdbc().sql("DELETE FROM sync_official_plan_result_history WHERE plan_id=:p").param("p", v1.plan()).update();
                jdbc().sql("UPDATE sync_plan_calculation SET collection_mode=:m,collection_range_start=:s,"
                        + "last_extended_verification_at=:e WHERE calculation_id=CAST(:c AS uuid)")
                        .param("m", backfilled.get("collection_mode")).param("s", backfilled.get("collection_range_start"))
                        .param("e", backfilled.get("last_extended_verification_at")).param("c", calc).update();
                jdbc().sql("INSERT INTO sync_plan_calculation_exclusion SELECT * FROM "
                        + "json_populate_recordset(NULL::sync_plan_calculation_exclusion, CAST(:rows AS json))")
                        .param("rows", backfilledExclusions).update();
                jdbc().sql("INSERT INTO sync_official_plan_result_history SELECT * FROM "
                        + "json_populate_recordset(NULL::sync_official_plan_result_history, CAST(:rows AS json))")
                        .param("rows", backfilledHistory).update();
            });
        } finally {
            jdbc().sql("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)").update();
        }

        var shown = ready(user);
        assertThat(evidenceOf(user).collection().mode()).isEqualTo("INITIAL");
        assertThat(historyOf(user, shown).summary().excludedCount()).isEqualTo(1);
        UUID job = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
        assertThat(awaitSync(job)).as("the next sync continues the backfilled version").isEqualTo("PUBLISHED");
        var next = ready(user);
        assertThat(next.pointerVersion()).isEqualTo(2);
        assertThat(evidenceOf(user).collection().inherited().previousCalculationId()).isEqualTo(v1.calculation());
        assertThat(historyOf(user, next).summary().excludedCount()).isEqualTo(1);
    }

    /**
     * Copies this user's rows of every table V10 has into the other database, as JSON rows (a column V11 added
     * is dropped by the record type). Foreign keys and triggers are off while it copies, so the order does not
     * matter; CHECKs still hold. A table without a user column is copied through the parent that names the user;
     * a table this list does not know fails the copy rather than being skipped in silence.
     */
    private void copyUserRows(DataSource side, UUID user) {
        String u = "'" + user + "'";
        // A child table names the user through the parent row it cites by the same column.
        Function<String[], String> through = pair -> pair[0] + " IN (SELECT " + pair[0] + " FROM " + pair[1]
                + " WHERE user_id=" + u + ")";
        Map<String, String> parents = Map.ofEntries(
                Map.entry("source_credential", through.apply(new String[] {"connection_id", "source_connection"})),
                Map.entry("portfolio_source_membership", through.apply(new String[] {"portfolio_id", "portfolio"})),
                Map.entry("portfolio_current_snapshot", through.apply(new String[] {"portfolio_id", "portfolio"})),
                Map.entry("sync_initial_event_provenance",
                        through.apply(new String[] {"job_id", "sync_initial_collection_job"})),
                Map.entry("sync_initial_candidate_event",
                        through.apply(new String[] {"candidate_id", "sync_initial_candidate_bundle"})),
                Map.entry("sync_cashflow_classification_event",
                        through.apply(new String[] {"classification_id", "sync_cashflow_classification_bundle"})),
                Map.entry("sync_cashflow_flow_candidate",
                        through.apply(new String[] {"classification_id", "sync_cashflow_classification_bundle"})),
                Map.entry("sync_cashflow_exclusion_summary",
                        through.apply(new String[] {"classification_id", "sync_cashflow_classification_bundle"})),
                Map.entry("sync_cashflow_conversion_input",
                        through.apply(new String[] {"job_id", "sync_cashflow_conversion_job"})),
                Map.entry("sync_cashflow_conversion_flow",
                        through.apply(new String[] {"conversion_id", "sync_cashflow_conversion_bundle"})),
                Map.entry("sync_cashflow_conversion_leg",
                        through.apply(new String[] {"conversion_id", "sync_cashflow_conversion_bundle"})),
                Map.entry("sync_plan_calculation_metric",
                        through.apply(new String[] {"calculation_id", "sync_plan_calculation"})),
                Map.entry("sync_plan_calculation_flow",
                        through.apply(new String[] {"calculation_id", "sync_plan_calculation"})));
        // Nothing of the user: sessions, operations, the shared public market evidence, Flyway's own table.
        Set<String> none = Set.of("spring_session", "spring_session_attributes", "operations_restriction",
                "sync_market_instrument_observation", "sync_market_candle", "flyway_schema_history");
        var other = JdbcClient.create(side);
        List<String> tables = other.sql("SELECT table_name FROM information_schema.tables WHERE table_schema='public' "
                + "AND table_type='BASE TABLE' ORDER BY table_name").query(String.class).list();
        new TransactionTemplate(new DataSourceTransactionManager(side)).executeWithoutResult(status -> {
            other.sql("SET LOCAL session_replication_role=replica").update();
            for (String table : tables) {
                if (none.contains(table)) continue;
                boolean owned = other.sql("SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public' "
                        + "AND table_name=:t AND column_name='user_id')").param("t", table).query(Boolean.class).single();
                String filter = owned ? "user_id=" + u : parents.get(table);
                if (filter == null) throw new AssertionError("a V10 table the copy does not know: " + table);
                for (String row : jdbc().sql("SELECT row_to_json(t)::text FROM " + table + " t WHERE " + filter)
                        .query(String.class).list()) {
                    other.sql("INSERT INTO " + table + " SELECT * FROM json_populate_record(NULL::" + table
                            + ", CAST(:row AS json))").param("row", row).update();
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------ erasure

    /** AC-10: the erasure removes the user's jobs, stage rows, candidates, versions and history, and nobody else's. */
    @Test
    void anErasureRemovesTheUsersSyncDataAndKeepsOtherUsers() throws Exception {
        var kept = connect();
        publishFirst(kept, null, List.of(), List.of());
        assertThat(awaitSync(answer(sync.admitPlanSync(kept.user(), SyncTrigger.MANUAL)).job().jobId()))
                .isEqualTo("PUBLISHED");
        var account = connect();
        Instant now = Instant.now();
        publishFirst(account, now.minus(DAY.multipliedBy(3)),
                List.of(new Flow("erase-in", "USDT", "10", now.minus(DAY.multipliedBy(3)).plus(HOUR))),
                List.of(new Flow("erase-fee", "USDT", "-1", now.minus(DAY.multipliedBy(3)).plus(HOUR.multipliedBy(2)))));
        UUID user = account.user();
        UUID published = answer(sync.admitPlanSync(user, SyncTrigger.MANUAL)).job().jobId();
        assertThat(awaitSync(published)).isEqualTo("PUBLISHED");
        endWith(user, new Ending("LOG_OUTAGE", "FAILED", "SOURCE_UNAVAILABLE", true,
                () -> BybitStubConfiguration.absoluteFailure(account.uid(), DevBybitStub.AbsoluteFailure.LOG_OUTAGE),
                () -> BybitStubConfiguration.absoluteFailure(account.uid(), null)));
        var keptBefore = rowCounts(kept.user());

        accountDeletion().beginDeletion(user);
        portfolio.erasePortfolioData(user);
        sync.eraseSyncData(user);
        plans.erasePlanData(user);
        source.eraseSourceData(user);
        for (String table : new String[] {"sync_plan_sync_job", "sync_plan_calculation", "sync_official_plan_result",
                "sync_official_plan_result_history", "sync_initial_collection_job", "sync_initial_candidate_bundle",
                "sync_initial_source_event", "sync_cashflow_classification_job", "sync_cashflow_classification_bundle",
                "sync_cashflow_conversion_job", "sync_cashflow_conversion_bundle", "sync_calculable_cashflow",
                "sync_source_snapshot", "sync_plan_publication_job"}) {
            assertThat(jdbc().sql("SELECT count(*) FROM " + table + " WHERE user_id=:u").param("u", user)
                    .query(Integer.class).single()).as(table).isZero();
        }
        assertThat(rowCounts(kept.user())).as("the other user is untouched").isEqualTo(keptBefore);
        assertThat(ready(kept.user()).pointerVersion()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------ HTTP

    /**
     * The HTTP surface: authentication, CSRF and Origin for the admission, the trigger's format, 202 and 200,
     * {@code no-store} everywhere, no financial field in the answers, and one operational line per admission and
     * per ending without a user, an id, an amount or a time.
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void theSyncHttpSurfaceAdmitsReportsAndLogsWithoutFinancialValues(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String subject = "plan-sync-http-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID user = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String uid = "sync-" + UUID.randomUUID();
        store(user, uid);
        publishFirst(new Account(user, uid), null, List.of(), List.of());
        String csrf = csrfTokenOf(cookie);
        String path = DashboardController.SYNC_PATH;

        assertThat(get(path, null).statusCode()).as("unauthenticated").isEqualTo(401);
        var state = get(path, cookie);
        assertThat(state.statusCode()).isEqualTo(200);
        assertThat(state.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(topLevelFieldsOf(state.body())).isEqualTo(Map.of("manual", Map.of("available", true)));

        assertThat(sendJson("POST", path, "{\"trigger\":\"MANUAL\"}", cookie).statusCode())
                .as("no CSRF token or Origin").isEqualTo(403);
        for (String body : new String[] {"{\"trigger\":\"manual\"}", "{\"trigger\":\"LATER\"}", "{}", "{\"trigger\":null}"}) {
            var refused = sendJson("POST", path, body, cookie, "Origin", OidcStubConfiguration.PUBLIC_BASE_URL,
                    AuthSecurityConfig.CSRF_HEADER_NAME, csrf);
            assertThat(refused.statusCode()).as(body).isEqualTo(400);
            assertThat(refused.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            assertThat(topLevelFieldsOf(refused.body())).containsEntry("code", "SYNC_TRIGGER_REQUIRED");
        }
        assertThat(jobCount(user)).isZero();

        var started = sendJson("POST", path, "{\"trigger\":\"MANUAL\"}", cookie, "Origin",
                OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME, csrf);
        assertThat(started.statusCode()).isEqualTo(202);
        assertThat(started.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var fields = topLevelFieldsOf(started.body());
        assertThat(fields.keySet()).containsExactly("outcome", "job", "manual");
        assertThat(fields).containsEntry("outcome", "ADMITTED");
        @SuppressWarnings("unchecked")
        var job = (Map<String, Object>) fields.get("job");
        assertThat(job.keySet()).as("codes and times only, and the attempt's own id").containsExactly("jobId", "trigger",
                "status", "stage", "mode", "failureCode", "retryable", "createdAt", "finishedAt", "pointerVersion");
        UUID jobId = UUID.fromString((String) job.get("jobId"));
        assertThat(awaitSync(jobId)).isEqualTo("PUBLISHED");

        var done = topLevelFieldsOf(get(path, cookie).body());
        @SuppressWarnings("unchecked")
        var doneJob = (Map<String, Object>) done.get("job");
        assertThat(doneJob).containsEntry("status", "PUBLISHED").containsEntry("pointerVersion", 2)
                .containsEntry("stage", "PUBLICATION").containsEntry("mode", "NORMAL");
        assertThat(get(path, cookie).body()).doesNotContain(user.toString(), uid, "1234.56", "totalEquity");
        var cooldown = sendJson("POST", path, "{\"trigger\":\"MANUAL\",\"ignored\":1}", cookie, "Origin",
                OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME, csrf);
        assertThat(cooldown.statusCode()).isEqualTo(200);
        assertThat(topLevelFieldsOf(cooldown.body())).containsEntry("outcome", "COOLDOWN");
        var entry = sendJson("POST", path, "{\"trigger\":\"DASHBOARD_ENTRY\"}", cookie, "Origin",
                OidcStubConfiguration.PUBLIC_BASE_URL, AuthSecurityConfig.CSRF_HEADER_NAME, csrf);
        var entryFields = topLevelFieldsOf(entry.body());
        assertThat(entryFields).containsEntry("outcome", "RECENTLY_SYNCED");
        @SuppressWarnings("unchecked")
        var entryManual = (Map<String, Object>) entryFields.get("manual");
        assertThat(entryManual).as("the button's cooldown, whatever the trigger").containsEntry("available", false)
                .containsEntry("reason", "COOLDOWN").containsKey("retryAfterSeconds");
        var dashboard = topLevelFieldsOf(get(AuthSecurityConfig.DASHBOARD_PATH, cookie).body());
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) dashboard.get("result");
        assertThat(result).containsEntry("pointerVersion", 2);

        var admissions = output.getAll().lines().filter(line -> line.contains("plan sync admission:")).toList();
        assertThat(admissions).isNotEmpty().allSatisfy(line -> assertThat(line)
                .containsPattern("trigger=[A-Z_]+ outcome=[A-Z_]+ reason=[A-Z_a-z]+$")
                .doesNotContain(user.toString(), uid, jobId.toString()));
        assertThat(admissions).anySatisfy(line -> assertThat(line).endsWith("trigger=MANUAL outcome=ADMITTED reason=none"));
        var endings = output.getAll().lines().filter(line -> line.contains("plan sync finished:")).toList();
        assertThat(endings).anySatisfy(line -> assertThat(line).containsPattern(
                "trigger=MANUAL mode=NORMAL status=PUBLISHED stage=PUBLICATION failure=none latencyMs=[0-9]+ windows=1 "
                        + "pages=2 flows=0 policy=bybit-readonly-uta-1.0,BYBIT-CASHFLOW-1.0,BYBIT-PRICE-1.0,CALC-1.0"));
        assertThat(endings).allSatisfy(line -> assertThat(line).doesNotContain(user.toString(), uid, jobId.toString()));

        accountDeletion().beginDeletion(user);
        assertThat(get(path, cookie).statusCode()).isIn(401, 403);
        assertThat(sync.admitPlanSync(user, SyncTrigger.MANUAL)).isInstanceOf(SyncJobApi.SyncAdmission.NotPermitted.class);
        assertThat(output.getAll().lines().filter(line -> line.contains("plan sync admission:")).toList())
                .as("a refusal of the account itself").anySatisfy(line -> assertThat(line)
                        .endsWith("trigger=MANUAL outcome=NOT_PERMITTED reason=none"))
                .noneSatisfy(line -> assertThat(line).contains("DENIED"));
    }

    // ------------------------------------------------------------------------------------------ fixtures

    private record Account(UUID user, String uid) {}

    private record Published(UUID plan, UUID calculation) {}

    /** One event the collection finds. Positive is a deposit; a negative amount on a deposit is a sign conflict. */
    private record Flow(String sourceId, String currency, String cashFlow, Instant at) {}

    private static DevBybitStub.AbsoluteEvent event(String id, String type, String currency, String cashFlow, Instant at) {
        return new DevBybitStub.AbsoluteEvent(id, type, currency, cashFlow, at.toEpochMilli());
    }

    private Account connect() {
        UUID user = accountLifecycle().createAccount();
        String uid = "sync-" + UUID.randomUUID();
        store(user, uid);
        return new Account(user, uid);
    }

    private void store(UUID user, String uid) {
        Instant start = Instant.now().minusSeconds(2);
        var credentials = new SourceCredentials(BybitStubConfiguration.apiKey(DevBybitStub.Scenario.ABSOLUTE, uid),
                BybitStubConfiguration.newSecret());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var stored = (SourceConnectionApi.StoreOutcome.Stored) source.store(user, SourceProvider.BYBIT,
                    new SourceVerification.Verified(uid, start, "bybit-readonly-uta-1.0"), credentials);
            portfolio.attachActiveSource(user, stored.connectionId(), stored.providerAccountId(), start);
        });
    }

    /**
     * The first snapshot and the plan conditions (ending 30 days after it), the start optionally moved back. The
     * returned intent is open for the first collection.
     */
    private UUID intentOf(Account account, @Nullable Instant start) throws Exception {
        UUID user = account.user();
        UUID first = sync.admit(user, "first-" + UUID.randomUUID()).orElseThrow().jobId();
        assertThat(awaitFirst(user, first)).isEqualTo("SUCCEEDED");
        var snapshot = plans.state(user).snapshot();
        String end = LocalDateTime.ofInstant(snapshot.measuredAt(), ZoneId.of("Asia/Seoul")).plusDays(30)
                .withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String session = "sync-session-" + UUID.randomUUID();
        var preview = plans.preview(user, session, new PlanConditionApi.PreviewInput(end, "DAILY", "0.1")).orElseThrow();
        UUID intent = plans.confirm(user, session, preview.confirmationRef(), "sync-confirm-" + UUID.randomUUID())
                .intent().intentId();
        if (start != null) shiftStart(user, intent, start);
        return intent;
    }

    private Published publishFirst(Account account, @Nullable Instant start, List<Flow> flows, List<Flow> excluded)
            throws Exception {
        return publish(account, start, tc -> flows, excluded);
    }

    /**
     * The first official version: first snapshot, conditions, the complete collection of what the stub holds,
     * the injected flows (computed from the collection's tc) and excluded events (placed on the candidate — the
     * stub does not know them, so they must sit before the next sync's start or at it), classification, conversion
     * and publication.
     */
    private Published publish(Account account, @Nullable Instant start, Function<Instant, List<Flow>> flowsAt,
            List<Flow> excluded) throws Exception {
        UUID user = account.user();
        UUID intent = intentOf(account, start);
        UUID collection = sync.admitInitialInputs(user, intent, "sync-inputs-" + UUID.randomUUID()).orElseThrow().jobId();
        assertThat(awaitInitial(user, collection)).isEqualTo("INPUTS_COMPLETE");
        var bundle = jdbc().sql("SELECT candidate_id,provider_account_id,connection_id,tc FROM sync_initial_candidate_bundle "
                + "WHERE job_id=:j").param("j", collection).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, OffsetDateTime.class).toInstant()})
                .single();
        List<Flow> flows = flowsAt.apply((Instant) bundle[3]);
        if (!flows.isEmpty() || !excluded.isEmpty()) {
            UUID candidate = (UUID) bundle[0];
            UUID providerAccount = (UUID) bundle[1];
            UUID connection = (UUID) bundle[2];
            for (Flow flow : flows) {
                insertCandidateEvent(user, providerAccount, connection, collection, candidate, flow.sourceId(),
                        flow.cashFlow().startsWith("-") ? "TRANSFER_OUT" : "TRANSFER_IN", flow.currency(), flow.cashFlow(),
                        flow.at(), sha256Hex(user + "|" + flow.sourceId()));
            }
            for (Flow fee : excluded) {
                insertCandidateEvent(user, providerAccount, connection, collection, candidate, fee.sourceId(), "FEE",
                        fee.currency(), fee.cashFlow(), fee.at(), sha256Hex(user + "|" + fee.sourceId()));
            }
            refreshCandidateHash(candidate);
        }
        UUID classification = sync.admitCashflowClassification(user, intent, "sync-class-" + UUID.randomUUID())
                .orElseThrow().jobId();
        assertThat(awaitTable("sync_cashflow_classification_job", classification)).isEqualTo("COMPLETE");
        UUID conversion = sync.admitCashflowConversion(user, intent, "sync-conv-" + UUID.randomUUID()).orElseThrow().jobId();
        assertThat(awaitTable("sync_cashflow_conversion_job", conversion)).isIn("CONVERTED", "CONVERTED_ESTIMATED");
        var admission = sync.admitPlanPublication(user, intent, "sync-publish-" + UUID.randomUUID());
        UUID publication = ((SyncJobApi.PublicationAdmission.Accepted) admission).job().jobId();
        assertThat(awaitTable("sync_plan_publication_job", publication)).isEqualTo("PUBLISHED");
        var result = ready(user);
        return new Published(result.planId(), result.calculationId());
    }

    /** Moves the plan start back before the collection, so injected flows fall inside (t0, tc]. */
    private void shiftStart(UUID user, UUID intent, Instant start) {
        var t0 = OffsetDateTime.ofInstant(start, ZoneOffset.UTC);
        jdbc().sql("UPDATE portfolio_snapshot SET measured_at=:t0 WHERE user_id=:u AND publication_sequence=1")
                .param("t0", t0).param("u", user).update();
        jdbc().sql("UPDATE sync_source_snapshot SET measured_at=:t0 WHERE user_id=:u AND job_id IS NOT NULL")
                .param("t0", t0).param("u", user).update();
        jdbc().sql("UPDATE plan_condition_intent SET t0=:t0 WHERE intent_id=:i").param("t0", t0).param("i", intent).update();
        jdbc().sql("UPDATE portfolio_source_membership SET started_at=:before WHERE portfolio_id IN "
                + "(SELECT portfolio_id FROM portfolio WHERE user_id=:u)")
                .param("before", t0.minusMinutes(1)).param("u", user).update();
    }

    /**
     * Ages the first version as if it had been published {@code ago} ago: its tc, its cash-flow verification and
     * its extended verification move together (V11: a first version's extended verification is its tc). The
     * plan start must already lie before them.
     */
    private void ageVersion(UUID user, Duration ago) {
        jdbc().sql("UPDATE sync_plan_calculation SET basis_at=now()-make_interval(secs=>:v),"
                + "cashflow_verified_through=now()-make_interval(secs=>:v),"
                + "last_extended_verification_at=now()-make_interval(secs=>:v) WHERE user_id=:u")
                .param("v", ago.toSeconds()).param("u", user).update();
    }

    /** Lets the next button press be admitted: every earlier admission moves 31 seconds back. */
    private void clearCooldown(UUID user) {
        jdbc().sql("UPDATE sync_plan_sync_job SET created_at=created_at-interval '31 seconds' WHERE user_id=:u")
                .param("u", user).update();
    }

    /** A queued job admitted {@code ago} ago on the first version, with its basis's verification at {@code verified}. */
    private void insertJob(UUID jobId, UUID user, Published v1, Instant verified, Duration ago) {
        jdbc().sql("INSERT INTO sync_plan_sync_job(job_id,user_id,plan_id,portfolio_id,intent_id,membership_id,"
                + "connection_id,provider_account_id,trigger,status,stage,basis_calculation_id,basis_pointer_version,"
                + "basis_verified_through,basis_extended_at,created_at) SELECT :j,c.user_id,c.plan_id,c.portfolio_id,"
                + "p.intent_id,c.membership_id,c.connection_id,c.provider_account_id,'MANUAL','QUEUED','ADMITTED',"
                + "c.calculation_id,1,:v,:v,date_trunc('milliseconds',statement_timestamp()-make_interval(secs=>:ago)) "
                + "FROM sync_plan_calculation c JOIN plan_definition p ON p.plan_id=c.plan_id WHERE c.calculation_id=:c")
                .param("j", jobId).param("v", OffsetDateTime.ofInstant(verified, ZoneOffset.UTC))
                .param("ago", ago.toSeconds()).param("c", v1.calculation()).update();
    }

    private UUID insertCandidateEvent(UUID user, UUID account, UUID connection, UUID collectionJob, UUID candidate,
            String sourceId, String type, String currency, String cashFlow, Instant occurredAt, String hash) {
        UUID event = UUID.randomUUID();
        jdbc().sql("INSERT INTO sync_initial_source_event(event_id,user_id,provider_account_id,source_id,event_type,"
                + "currency,cash_flow,transaction_time,account_type,semantic_hash,observed_connection_id) "
                + "VALUES (:event,:user,:account,:source,:type,:currency,:flow,:time,'UNIFIED',:hash,:connection)")
            .param("event", event).param("user", user).param("account", account).param("source", sourceId)
            .param("type", type).param("currency", currency).param("flow", cashFlow)
            .param("time", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
            .param("hash", hash).param("connection", connection).update();
        jdbc().sql("INSERT INTO sync_initial_event_provenance(job_id,event_id,connection_id,semantic_hash) "
                + "VALUES (:job,:event,:connection,:hash)")
            .param("job", collectionJob).param("event", event).param("connection", connection).param("hash", hash).update();
        jdbc().sql("INSERT INTO sync_initial_candidate_event(candidate_id,event_id) VALUES (:candidate,:event)")
            .param("candidate", candidate).param("event", event).update();
        return event;
    }

    /** Recomputes a candidate's completeness hash after events were placed on it (the collection's own recipe). */
    private void refreshCandidateHash(UUID candidate) {
        var scope = jdbc().sql("SELECT job_id,portfolio_id,intent_id,connection_id,provider_account_id,t0,tc,"
                + "equity_decimal,measured_at,policy_version FROM sync_initial_candidate_bundle WHERE candidate_id=:candidate")
            .param("candidate", candidate).query((rs, n) -> new Object[] {rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getObject(6, OffsetDateTime.class).toInstant(),
                rs.getObject(7, OffsetDateTime.class).toInstant(), rs.getString(8),
                rs.getObject(9, OffsetDateTime.class).toInstant(), rs.getString(10)}).single();
        String pages = jdbc().sql("SELECT string_agg(event_type||'|'||range_start::text||'|'||range_end::text||'|'||"
                + "cursor_hash||'|'||item_count||'|'||COALESCE(first_event_at::text,'')||'|'||"
                + "COALESCE(last_event_at::text,'')||'|'||has_next_page::text,';' ORDER BY event_type,range_start,cursor_hash) "
                + "FROM sync_initial_page_evidence WHERE job_id=:job").param("job", scope[0]).query(String.class).single();
        String events = jdbc().sql("SELECT COALESCE(string_agg(source_id||'|'||semantic_hash,';' ORDER BY source_id),'') "
                + "FROM sync_initial_source_event WHERE user_id=(SELECT user_id FROM sync_initial_candidate_bundle "
                + "WHERE candidate_id=:candidate) AND provider_account_id=:account AND transaction_time>:t0 AND transaction_time<=:tc")
            .param("candidate", candidate).param("account", scope[4])
            .param("t0", OffsetDateTime.ofInstant((Instant) scope[5], ZoneOffset.UTC))
            .param("tc", OffsetDateTime.ofInstant((Instant) scope[6], ZoneOffset.UTC)).query(String.class).single();
        String payload = scope[0] + "|" + scope[1] + "|" + scope[2] + "|" + scope[3] + "|" + scope[4] + "|" + scope[5]
                + "|" + scope[6] + "|" + scope[7] + "|" + scope[8] + "|" + scope[9] + "|" + pages + "|" + events;
        jdbc().sql("UPDATE sync_initial_candidate_bundle SET completeness_hash=:hash WHERE candidate_id=:candidate")
                .param("hash", sha256Hex(payload)).param("candidate", candidate).update();
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Runs a statement with foreign keys and triggers off, as a corruption no application path could make. */
    private void replica(String sql) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc().sql("SET LOCAL session_replication_role=replica").update();
            jdbc().sql(sql).update();
        });
    }

    /** One row as JSON, to put it back after a corruption removed it. */
    private String rowJson(String table, String where) {
        return jdbc().sql("SELECT row_to_json(t)::text FROM " + table + " t WHERE " + where).query(String.class).single();
    }

    private void replicaInsert(String table, String row) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc().sql("SET LOCAL session_replication_role=replica").update();
            jdbc().sql("INSERT INTO " + table + " SELECT * FROM json_populate_record(NULL::" + table
                    + ", CAST(:row AS json))").param("row", row).update();
        });
    }

    private static String ownedBy(String table, UUID user) {
        String id = "'" + user + "'::uuid";
        return switch (table) {
            case "portfolio_current_snapshot" -> "NEW.portfolio_id IN (SELECT portfolio_id FROM portfolio WHERE user_id=" + id + ")";
            case "sync_plan_calculation_metric", "sync_plan_calculation_flow", "sync_plan_calculation_exclusion" ->
                    "EXISTS(SELECT 1 FROM sync_plan_calculation c WHERE c.calculation_id=NEW.calculation_id AND c.user_id="
                            + id + ")";
            default -> "NEW.user_id=" + id;
        };
    }

    /** Row counts of everything a version's publication writes, for this user. */
    private Map<String, Integer> rowCounts(UUID user) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String table : new String[] {"sync_source_snapshot", "portfolio_snapshot", "sync_plan_calculation",
                "sync_official_plan_result_history"}) {
            counts.put(table, jdbc().sql("SELECT count(*) FROM " + table + " WHERE user_id=:u").param("u", user)
                    .query(Integer.class).single());
        }
        for (String table : new String[] {"sync_plan_calculation_metric", "sync_plan_calculation_flow",
                "sync_plan_calculation_exclusion"}) {
            counts.put(table, jdbc().sql("SELECT count(*) FROM " + table + " x JOIN sync_plan_calculation c "
                    + "ON c.calculation_id=x.calculation_id WHERE c.user_id=:u").param("u", user).query(Integer.class).single());
        }
        counts.put("portfolio_snapshot_component", jdbc().sql("SELECT count(*) FROM portfolio_snapshot_component "
                + "WHERE user_id=:u").param("u", user).query(Integer.class).single());
        return counts;
    }

    /** The official pointer, the watermark and the current value, as one comparable string. */
    private String snapshotOf(UUID user) {
        var result = ready(user);
        return result.pointerVersion() + "|" + result.calculationId() + "|" + result.cashflowVerifiedThrough() + "|"
                + result.metrics().getFirst().value() + "|" + result.portfolioSnapshotId() + "|"
                + jdbc().sql("SELECT c.publication_sequence FROM portfolio_current_snapshot c JOIN portfolio p "
                        + "ON p.portfolio_id=c.portfolio_id WHERE p.user_id=:u").param("u", user).query(Long.class).single();
    }

    private String planRow(UUID user) {
        return jdbc().sql("SELECT plan_id::text||'|'||p0_decimal||'|'||t0::text||'|'||end_at::text||'|'||period||'|'||"
                + "target_rate||'|'||status FROM plan_definition WHERE user_id=:u").param("u", user)
                .query(String.class).single();
    }

    private UUID conversionOf(UUID calculation) {
        return jdbc().sql("SELECT conversion_id FROM sync_plan_calculation WHERE calculation_id=:c").param("c", calculation)
                .query(UUID.class).single();
    }

    private int jobCount(UUID user) {
        return jdbc().sql("SELECT count(*) FROM sync_plan_sync_job WHERE user_id=:u").param("u", user)
                .query(Integer.class).single();
    }

    private @Nullable String failureOf(UUID job) {
        return jdbc().sql("SELECT failure_code FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(String.class).single();
    }

    private boolean retryableOf(UUID job) {
        return jdbc().sql("SELECT retryable FROM sync_plan_sync_job WHERE job_id=:j").param("j", job)
                .query(Boolean.class).single();
    }

    private static SyncJobApi.SyncAnswer answer(SyncJobApi.SyncAdmission admission) {
        assertThat(admission).isInstanceOf(SyncJobApi.SyncAdmission.Answered.class);
        return ((SyncJobApi.SyncAdmission.Answered) admission).answer();
    }

    private SyncJobApi.DashboardResult ready(UUID user) {
        var dashboard = sync.dashboard(user).dashboard();
        assertThat(dashboard).isInstanceOf(SyncJobApi.Dashboard.Ready.class);
        return ((SyncJobApi.Dashboard.Ready) dashboard).result();
    }

    private static SyncJobApi.EvidenceReference reference(SyncJobApi.DashboardResult result) {
        return new SyncJobApi.EvidenceReference(result.planId(), result.pointerVersion(), result.calculationId());
    }

    private SyncJobApi.CalculationEvidence evidenceOf(UUID user) {
        var read = sync.calculationEvidence(user, reference(ready(user)));
        assertThat(read.consistency()).isEqualTo(SyncJobApi.BundleConsistency.CONSISTENT);
        return ((SyncJobApi.EvidenceOutcome.Found) read.outcome()).evidence();
    }

    private static SyncJobApi.AdjustmentQuery adjustmentQuery(SyncJobApi.DashboardResult result) {
        return new SyncJobApi.AdjustmentQuery(result.planId(), result.pointerVersion(), result.calculationId(),
                SyncJobApi.AdjustmentDirection.ALL, null);
    }

    private SyncJobApi.AdjustmentHistory historyOf(UUID user, SyncJobApi.DashboardResult result) {
        var read = sync.adjustmentHistory(user, adjustmentQuery(result));
        assertThat(read.outcome()).isInstanceOf(SyncJobApi.AdjustmentOutcome.Found.class);
        return ((SyncJobApi.AdjustmentOutcome.Found) read.outcome()).history();
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    // ------------------------------------------------------------------------------------------ waiting

    /** Polls a condition every 10 ms for at most 30 seconds: never a fixed wait. */
    private String awaitSync(UUID job) throws InterruptedException {
        return awaitTable("sync_plan_sync_job", job);
    }

    private String awaitTable(String table, UUID job) throws InterruptedException {
        for (int i = 0; i < 3000; i++) {
            List<String> status = jdbc().sql("SELECT status FROM " + table + " WHERE job_id=:j").param("j", job)
                    .query(String.class).list();
            if (!status.isEmpty() && !status.getFirst().equals("QUEUED") && !status.getFirst().equals("RUNNING"))
                return status.getFirst();
            Thread.sleep(10);
        }
        throw new AssertionError(table + " did not finish");
    }

    private String awaitFirst(UUID user, UUID job) throws InterruptedException {
        for (int i = 0; i < 3000; i++) {
            String status = sync.job(user, job).orElseThrow().status();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return status;
            Thread.sleep(10);
        }
        throw new AssertionError("first snapshot did not finish");
    }

    private String awaitInitial(UUID user, UUID job) throws InterruptedException {
        for (int i = 0; i < 3000; i++) {
            String status = sync.initialCollectionJob(user, job).orElseThrow().status();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return status;
            Thread.sleep(10);
        }
        throw new AssertionError("initial input collection did not finish");
    }

    /** Holds the user's plan write lock (the publication's first lock) until closed. */
    private AutoCloseable holdPlanLock(UUID user) throws InterruptedException {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc().sql("WITH l AS MATERIALIZED (SELECT pg_advisory_xact_lock(hashtextextended(:u,0))) SELECT 1 FROM l")
                    .param("u", user.toString()).query(Integer.class).single();
            acquired.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("plan lock never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
        return () -> {
            release.countDown();
            holder.join(10_000);
        };
    }

    private void awaitBlockedOnPlanLock() throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            if (jdbc().sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")
                    .query(Integer.class).single() > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("the sync never reached the plan lock");
    }

    /**
     * Waits until nobody holds or awaits the user's plan lock: the late worker that waited for it took it and its
     * publishing transaction ended — whatever it decided is written (or not) by then.
     */
    private void awaitPlanLockReleased(UUID user) throws InterruptedException {
        long key = jdbc().sql("SELECT hashtextextended(:u,0)").param("u", user.toString()).query(Long.class).single();
        for (int i = 0; i < 3000; i++) {
            if (jdbc().sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objsubid=1 "
                    + "AND classid::bigint=:hi AND objid::bigint=:lo").param("hi", key >>> 32).param("lo", key & 0xffffffffL)
                    .query(Integer.class).single() == 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("the plan lock was never released");
    }
}
