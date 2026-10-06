package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceProvider;
import com.bokrilog.source.SourceVerification;
import com.bokrilog.sync.SyncJobApi;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the plan sync tests share (Stories 2.11 and 2.12): publishing a first official version on the real stack, the
 * stub connection, waiting for an attempt, holding the plan lock, moving times and inserting stored events by SQL, and
 * reading the dashboard. No {@code @DynamicPropertySource} of its own: it runs in {@link AuthFlowTestBase}'s context.
 */
abstract class PlanSyncTestBase extends AuthFlowTestBase {
    @Autowired SourceConnectionApi source;
    @Autowired PortfolioMembershipApi portfolio;
    @Autowired SyncJobApi sync;
    @Autowired PlanConditionApi plans;
    @Autowired PlatformTransactionManager manager;

    record HttpResponseView(int statusCode, String body, java.net.http.HttpHeaders headers) {}

    String awaitSync(UUID job) throws InterruptedException {
        String status = pollUntil(Duration.ofSeconds(30), () -> jdbc().sql("SELECT status FROM sync_plan_sync_job "
                + "WHERE job_id=:j").param("j", job).query(String.class).optional().orElse("GONE"),
                PlanSyncTestBase::finished);
        if (!finished(status)) throw new AssertionError("plan sync did not finish");
        return status;
    }

    /** A job status that no worker will move any more: neither waiting for a claim nor claimed. */
    static boolean finished(String status) {
        return !status.equals("QUEUED") && !status.equals("RUNNING");
    }

    /** The windows cover [start, tc] contiguously, each at most seven days, both types in each window. */
    static void assertWindows(List<BybitStubConfiguration.TransactionQuery> queries, Instant start, Instant tc) {
        assertThat(queries).isNotEmpty();
        var byWindow = new java.util.TreeMap<Long, java.util.Set<String>>();
        var ends = new java.util.HashMap<Long, Long>();
        for (var q : queries) {
            byWindow.computeIfAbsent(q.startTime(), ignored -> new java.util.HashSet<>()).add(q.type());
            ends.put(q.startTime(), q.endTime());
        }
        long next = start.toEpochMilli();
        for (var window : byWindow.entrySet()) {
            assertThat(window.getKey()).as("contiguous").isEqualTo(next);
            assertThat(window.getValue()).containsExactlyInAnyOrder("TRANSFER_IN", "TRANSFER_OUT");
            long end = ends.get(window.getKey());
            assertThat(end - window.getKey()).isPositive().isLessThanOrEqualTo(Duration.ofDays(7).toMillis());
            next = end;
        }
        assertThat(next).as("the closed tc").isEqualTo(tc.toEpochMilli());
    }

    /** The last official version is exactly v1: pointer, calculation, portfolio pointer and watermarks. */
    void assertNoChildInProgress(UUID job) {
        for (String table : List.of("sync_initial_collection_job", "sync_cashflow_classification_job",
                "sync_cashflow_conversion_job")) {
            assertThat(jdbc().sql("SELECT count(*) FROM " + table + " WHERE plan_sync_job_id=:j AND status IN "
                    + "('QUEUED','RUNNING')").param("j", job).query(Integer.class).single()).as(table).isZero();
        }
    }

    /** Moves the version's verification (and its extended one) back, keeping it before t0 < basis. */
    void moveVerification(UUID user, UUID calculation, String interval) {
        jdbc().sql("UPDATE sync_plan_calculation SET basis_at=now()-interval '" + interval + "',cashflow_verified_through="
                + "now()-interval '" + interval + "',last_extended_verification_at=now()-interval '" + interval
                + "' WHERE calculation_id=:c AND user_id=:u").param("c", calculation).param("u", user).update();
    }

    /**
     * Moves the plan's end {@code T} (Story 2.14): the plan row and every calculation version of it, so the version still
     * belongs to its plan. Callers keep {@code T} after each progress version's basis (V10's check).
     */
    void moveEnd(UUID user, Instant end) {
        var at = OffsetDateTime.ofInstant(end.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), ZoneOffset.UTC);
        jdbc().sql("UPDATE plan_definition SET end_at=:e WHERE user_id=:u").param("e", at).param("u", user).update();
        jdbc().sql("UPDATE sync_plan_calculation SET end_at=:e WHERE user_id=:u").param("e", at).param("u", user)
                .update();
    }

    UUID insertSourceEvent(UUID user, String sourceId, String type, String currency, String cashFlow,
            Instant at) {
        var connection = jdbc().sql("SELECT connection_id,provider_account_id FROM source_connection WHERE user_id=:u "
                + "AND status='ACTIVE'").param("u", user)
                .query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).single();
        UUID event = UUID.randomUUID();
        jdbc().sql("INSERT INTO sync_initial_source_event(event_id,user_id,provider_account_id,source_id,event_type,"
                + "currency,cash_flow,transaction_time,account_type,semantic_hash,observed_connection_id) VALUES "
                + "(:e,:u,:a,:s,:t,:c,:f,:at,'UNIFIED',:h,:conn)")
            .param("e", event).param("u", user).param("a", connection[1]).param("s", sourceId).param("t", type)
            .param("c", currency).param("f", cashFlow).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .param("h", sha256(type + "|" + currency + "|" + cashFlow + "|" + at.toEpochMilli() + "|UNIFIED"))
            .param("conn", connection[0]).update();
        return event;
    }

    String planRow(UUID user) {
        return jdbc().sql("SELECT plan_id||'|'||p0_decimal||'|'||t0||'|'||end_at||'|'||period||'|'||target_rate||'|'||"
                + "status||'|'||created_at||'|'||intent_id FROM plan_definition WHERE user_id=:u").param("u", user)
                .query(String.class).single();
    }

    int countOf(String table, UUID user) {
        return jdbc().sql("SELECT count(*) FROM " + table + " WHERE user_id=:u").param("u", user)
                .query(Integer.class).single();
    }

    SyncJobApi.DashboardResult ready(UUID user) {
        var dashboard = sync.dashboard(user).dashboard();
        assertThat(dashboard).isInstanceOf(SyncJobApi.Dashboard.Ready.class);
        return ((SyncJobApi.Dashboard.Ready) dashboard).result();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> nested(Map<String, Object> fields, String name) {
        return (Map<String, Object>) fields.get(name);
    }

    /**
     * 작업 하나의 종료 줄 묶음. 종료 줄은 상태가 커밋되고 종료 사실을 한 번 더 읽은 뒤에야 찍히므로 {@link #awaitSync}가
     * 돌아온 직후에는 아직 없을 수 있다 — 느린 러너에서 그 틈이 실제로 드러났다(PR #71 첫 실행). 줄이 하나라도 보일
     * 때까지 잠시 기다린 뒤 그때의 묶음을 돌려준다. 몇 줄이어야 하는지의 단정은 호출자가 한다.
     */
    static List<String> endingLines(CapturedOutput output, UUID job) throws InterruptedException {
        String marker = "plan sync finished: job=" + job + " ";
        return pollUntil(Duration.ofSeconds(10), () -> lines(output, marker), found -> !found.isEmpty());
    }

    static List<String> lines(CapturedOutput output, String marker) {
        List<String> found = new ArrayList<>();
        for (String line : output.getAll().split("\\R")) if (line.contains(marker)) found.add(line);
        return found;
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Publishes the first official version (Story 2.7) of a new plan: the first snapshot, daily conditions
     * ending 30 days later, and the collection, classification and conversion of its inputs. {@code start}
     * moves t0 back before the collection starts.
     */
    UUID publishedPlan(UUID user, String prefix, @Nullable Instant start) throws Exception {
        return publishedPlan(user, prefix, start, "CONVERTED");
    }

    /** {@link #publishedPlan(UUID, String, Instant)} whose conversion ends with {@code conversionStatus}. */
    UUID publishedPlan(UUID user, String prefix, @Nullable Instant start, String conversionStatus) throws Exception {
        UUID firstJob = sync.admit(user, prefix + "-seed").orElseThrow().jobId();
        pollUntil(Duration.ofSeconds(10), () -> sync.job(user, firstJob).orElseThrow().status(),
                PlanSyncTestBase::finished);
        assertThat(sync.job(user, firstJob).orElseThrow().status()).isEqualTo("SUCCEEDED");
        var snapshot = plans.state(user).snapshot();
        String end = LocalDateTime.ofInstant(snapshot.measuredAt(), ZoneId.of("Asia/Seoul")).plusDays(30)
                .withSecond(0).withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        var preview = plans.preview(user, prefix + "-session", new PlanConditionApi.PreviewInput(end, "DAILY", "0.1"))
                .orElseThrow();
        UUID intent = plans.confirm(user, prefix + "-session", preview.confirmationRef(), prefix + "-confirm-001")
                .intent().intentId();
        if (start != null) shiftStart(user, intent, start);
        UUID collection = sync.admitInitialInputs(user, intent, prefix + "-inputs-001").orElseThrow().jobId();
        awaitTable("sync_initial_collection_job", collection, "INPUTS_COMPLETE");
        UUID classification = sync.admitCashflowClassification(user, intent, prefix + "-classification-001")
                .orElseThrow().jobId();
        awaitTable("sync_cashflow_classification_job", classification, "COMPLETE");
        UUID conversion = sync.admitCashflowConversion(user, intent, prefix + "-conversion-001").orElseThrow().jobId();
        awaitTable("sync_cashflow_conversion_job", conversion, conversionStatus);
        var admission = sync.admitPlanPublication(user, intent, prefix + "-publication-001");
        UUID publication = ((SyncJobApi.PublicationAdmission.Accepted) admission).job().jobId();
        awaitTable("sync_plan_publication_job", publication, "PUBLISHED");
        return intent;
    }

    void awaitTable(String table, UUID job, String expected) throws InterruptedException {
        String status = pollUntil(Duration.ofSeconds(15), () -> jdbc().sql("SELECT status FROM " + table
                + " WHERE job_id=:j").param("j", job).query(String.class).single(), PlanSyncTestBase::finished);
        assertThat(status).as(table).isEqualTo(expected);
    }

    /** Moves the plan start (the first snapshot's time and the intent's t0) back, before the collection starts. */
    void shiftStart(UUID user, UUID intent, Instant start) {
        var t0 = OffsetDateTime.ofInstant(start, ZoneOffset.UTC);
        jdbc().sql("UPDATE portfolio_snapshot SET measured_at=:t0 WHERE user_id=:u AND publication_sequence=1")
                .param("t0", t0).param("u", user).update();
        jdbc().sql("UPDATE sync_source_snapshot SET measured_at=:t0 WHERE user_id=:u AND job_id IS NOT NULL")
                .param("t0", t0).param("u", user).update();
        jdbc().sql("UPDATE plan_condition_intent SET t0=:t0 WHERE intent_id=:i").param("t0", t0).param("i", intent)
                .update();
        jdbc().sql("UPDATE portfolio_source_membership SET started_at=:before WHERE portfolio_id IN "
                + "(SELECT portfolio_id FROM portfolio WHERE user_id=:u)")
                .param("before", t0.minusMinutes(1)).param("u", user).update();
    }

    UUID connect(DevBybitStub.Scenario scenario) {
        UUID user = accountLifecycle().createAccount();
        store(user, scenario);
        return user;
    }

    String store(UUID user, DevBybitStub.Scenario scenario) {
        Instant start = Instant.now().minusSeconds(2);
        String uid = "incremental-" + UUID.randomUUID();
        var credentials = new SourceCredentials(BybitStubConfiguration.apiKey(scenario, uid),
                BybitStubConfiguration.newSecret());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var stored = (SourceConnectionApi.StoreOutcome.Stored) source.store(user, SourceProvider.BYBIT,
                    new SourceVerification.Verified(uid, start, "bybit-readonly-uta-1.0"), credentials);
            portfolio.attachActiveSource(user, stored.connectionId(), stored.providerAccountId(), start);
        });
        return uid;
    }

    /** Holds the user's plan write lock (the first lock of the publishing transaction) until closed. */
    AutoCloseable holdPlanLock(UUID user) throws InterruptedException {
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

    void awaitBlockedOnPlanLock() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (jdbc().sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")
                    .query(Integer.class).single() > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the sync never reached the plan lock");
    }

    /**
     * Waits until no advisory lock is held or awaited. Called after the holder let go: the late worker took the
     * plan lock inside its publishing transaction, so an empty {@code pg_locks} means that transaction ended.
     *
     * <p>Preconditions: the caller saw the late worker waiting on the plan lock ({@link #awaitBlockedOnPlanLock})
     * and the holder has been joined ({@link #holdPlanLock}'s close). These tests hold no other advisory lock,
     * so counting every advisory lock rather than the plan lock's key can only make the wait longer, never
     * shorter; a key filter could pass vacuously if its arithmetic were off.
     */
    void awaitPlanLockReleased() throws InterruptedException {
        int left = pollUntil(Duration.ofSeconds(10), () -> jdbc().sql("SELECT count(*) FROM pg_locks "
                + "WHERE locktype='advisory'").query(Integer.class).single(), count -> count == 0);
        if (left > 0) throw new AssertionError("the late worker never let go of the plan lock");
    }
}
