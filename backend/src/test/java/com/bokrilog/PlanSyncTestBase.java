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
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
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
     *
     * <p>종료 줄은 작업 ID를 싣지 않는다(Story 3.7 — 로그에 식별자를 남기지 않는다). 그래서 {@code part}(목적·상태 같은 내용)를
     * 담은 줄만 고른다 — 한 시험에 작업이 여럿이면 그것으로 가른다.
     */
    static List<String> endingLines(CapturedOutput output, String part) throws InterruptedException {
        return pollUntil(Duration.ofSeconds(10), () -> lines(output, "plan sync finished: ").stream()
                .filter(line -> line.contains(part)).toList(), found -> !found.isEmpty());
    }

    /**
     * 본문에 {@code marker}를 담은 로그 줄들의 본문({@code event}). 로그는 JSON 한 줄이다({@link LogLines}). 이 시험의 시작
     * 표지({@link #TEST_START}) 뒤의 줄만 본다 — 앞 시험의 늦은 작업자가 남긴 줄과 섞이지 않게.
     */
    static List<String> lines(CapturedOutput output, String marker) {
        List<String> events = LogLines.events(output.getAll());
        int start = events.lastIndexOf(TEST_START);
        List<String> found = new ArrayList<>();
        for (String event : events.subList(start + 1, events.size())) {
            if (event.contains(marker)) found.add(event);
        }
        return found;
    }

    /** 동기화 결말·제외 카운터({@code bokrilog_sync_outcomes_total})의 지금 값(Story 3.7). 없으면 0. */
    double syncOutcomes(String kind, String outcome, String reason) {
        var counter = meters.find(com.bokrilog.sync.SyncTelemetry.OUTCOMES)
                .tags("kind", kind, "outcome", outcome, "reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** 시험마다 처음에 남기는 로그 표지. 숫자·식별자가 없다. */
    static final String TEST_START = "plan sync test start";

    @org.junit.jupiter.api.BeforeEach
    void markTestStart() {
        org.slf4j.LoggerFactory.getLogger(PlanSyncTestBase.class).info(TEST_START);
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
        return publishedPlanFromBaseline(user, prefix, start, conversionStatus);
    }

    /**
     * 첫 스냅샷을 빼고 계획 조건의 지금 기준부터 발행까지 하는 {@link #publishedPlan(UUID, String, Instant, String)}(Story 2.17 —
     * 계획 없는 재연결은 기준을 그대로 쓰거나 먼저 바꾼다).
     */
    UUID publishedPlanFromBaseline(UUID user, String prefix, @Nullable Instant start, String conversionStatus)
            throws Exception {
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
        return store(user, scenario, "incremental-" + UUID.randomUUID());
    }

    /**
     * main UID를 받는 {@link #store(UUID, DevBybitStub.Scenario)}(Story 2.17): 같은 UID면 provider account를 다시 쓰고 다른 UID면
     * 새로 만든다. HTTP 흐름 없이 바로 저장한다 — 활성 계획의 계정 대조는 그 흐름 안에 있으므로 그것을 보는 시험은 HTTP로
     * 다시 연결한다.
     */
    String store(UUID user, DevBybitStub.Scenario scenario, String uid) {
        Instant start = Instant.now().minusSeconds(2);
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

    /**
     * Waits until some backend waits on a bigint advisory key (the plan lock's kind). 페이지 저장 문의 두 정수 키
     * ({@code objsubid=2})는 세지 않는다 — 문 앞 대기를 계획 잠금 대기로 읽지 않게.
     */
    void awaitBlockedOnPlanLock() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (jdbc().sql("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND objsubid<>2 AND NOT granted")
                    .query(Integer.class).single() > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("the sync never reached the plan lock");
    }

    /**
     * Waits until no advisory lock is held or awaited. Called after the holder let go: the late worker took the
     * plan lock inside its publishing transaction, so an empty {@code pg_locks} means that transaction ended.
     *
     * <p>전제: 호출자는 늦은 작업자가 계획 잠금에서 기다리는 것을 보았고({@link #awaitBlockedOnPlanLock}) holder를 join했다
     * ({@link #holdPlanLock}의 close). 이 시험들이 쥐는 advisory 잠금은 계획 잠금(bigint 키, {@code objsubid=1})과 페이지
     * 저장 문(두 정수 키, {@code objsubid=2})뿐이다. 문 키는 빼고 나머지 advisory 잠금을 모두 센다 — 계획 잠금의 키만 고르는
     * 필터는 산술이 틀리면 헛되이 통과할 수 있지만, 모두 세면 대기가 길어질 수는 있어도 짧아지지는 않는다.
     */
    void awaitPlanLockReleased() throws InterruptedException {
        int left = pollUntil(Duration.ofSeconds(10), () -> jdbc().sql("SELECT count(*) FROM pg_locks "
                + "WHERE locktype='advisory' AND objsubid<>2").query(Integer.class).single(), count -> count == 0);
        if (left > 0) throw new AssertionError("the late worker never let go of the plan lock");
    }

    /** 작업 행의 {@code status|stage|failure_code|resume_count} — 결말이 기대와 다를 때 실패 메시지에 싣는다. */
    String jobState(UUID job) {
        return jdbc().sql("SELECT status||'|'||COALESCE(stage,'-')||'|'||COALESCE(failure_code,'-')||'|'||resume_count "
                + "FROM sync_plan_sync_job WHERE job_id=:j").param("j", job).query(String.class).optional().orElse("GONE");
    }

    // ------------------------------------------------------------------------------------------ 페이지 저장 문

    /*
     * 페이지 저장 문의 advisory 키 — 두 정수 키(pg_locks objsubid=2)다. 운영 코드의 advisory 잠금은 모두 bigint 하나
     * (hashtextextended, objsubid=1)이고 두 정수 키는 없으므로 문은 계획·원장·Worker 잠금과 키 공간이 겹치지 않는다.
     * classid는 모든 문이 같고 문마다 objid({@link PageDoorKey})를 나눈다. 값은 임의로 고른 양의 int4다(PR #146 마지막
     * 점검 문에서 온 값이고 뜻은 없다).
     */
    static final int PAGE_DOOR_CLASS = 20_261_106;
    /** 문 획득 대기 상한. 문 트랜잭션의 {@code lock_timeout}(4초)이 이 대기보다 먼저 끝나 실패를 남긴다. */
    private static final Duration PAGE_DOOR_SHUT_WAIT = Duration.ofSeconds(5);
    /** 문 앞 막힘 대기의 마감. */
    private static final Duration PAGE_DOOR_BLOCK_WAIT = Duration.ofSeconds(60);
    /** 문 holder의 안전 시한 — 문 생성부터 재므로 막힘 대기 마감({@link #PAGE_DOOR_BLOCK_WAIT})보다 길다. */
    private static final Duration PAGE_DOOR_SAFETY = Duration.ofSeconds(90);
    /**
     * 만료 없이 막는 문을 막힘 뒤 열어야 하는 기본 상한 — lease(30초)의 절반. 문 앞에서 기다리는 페이지 트랜잭션이 계획
     * 작업과 수집 작업 행을 쥐고 있어 heartbeat(5초)가 {@code SKIP LOCKED}로 건너뛰므로, 이보다 오래 막으면 lease가 시험
     * 밖에서 끝난다. 막힘의 기준 시각은 문 대기의 {@code pg_locks.waitstart}다.
     */
    static final Duration PAGE_DOOR_LEASE_BOUND = com.bokrilog.sync.WorkerLease.DURATION.dividedBy(2);
    /** 문 뒤 명령이 페이지 트랜잭션 뒤에 줄 서기(또는 끝나기)까지의 마감 — {@link #PAGE_DOOR_LEASE_BOUND}보다 짧다. */
    private static final Duration PAGE_DOOR_COMMAND_WAIT = Duration.ofSeconds(10);
    /** 문을 연 뒤 명령의 결과를 기다리는 상한. 실패 경로에서도 명령이 시험 밖에서 커밋하지 않도록 이만큼 기다린다. */
    private static final Duration PAGE_DOOR_COMMAND_DRAIN = Duration.ofSeconds(30);
    /** holder 스레드가 끝나기를 기다리는 상한(문 열기·닫기 실패). */
    private static final Duration PAGE_DOOR_HOLDER_JOIN = Duration.ofSeconds(10);

    /**
     * 페이지 저장 문의 objid. 문마다 하나다 — trigger·함수 이름({@code page_door_<objid>})에도 붙는다. {@code SELF_TEST_}로
     * 시작하는 셋은 문 자체를 보는 시험 전용이다. 값이 서로 다른 것은 {@code PageDoorSourceTest}가 단정한다.
     */
    enum PageDoorKey {
        FINAL_SWEEP(1),
        CONTINUATION_COLLECTION(2),
        THIRD_INTERRUPTION(3),
        VERIFICATION_COLLECTION(4),
        LOST_WINDOW(5),
        REVOCATION_SYNC(6),
        REVOCATION_VERIFICATION(7),
        RECONNECTION_SYNC(8),
        PLAN_END_SYNC(9),
        SELF_TEST_SHUT_CLEANUP(10),
        SELF_TEST_EXPIRING_CLEANUP(11),
        SELF_TEST_LEASE_BOUND(12);

        final int object;

        PageDoorKey(int object) {
            this.object = object;
        }

        String trigger() {
            return "page_door_" + object;
        }
    }

    /**
     * 문을 닫는다: holder가 문 키를 배타적으로 쥐고, 이 사용자의 페이지 저장 가운데 {@code predicate}에 맞는 것이 문(공유
     * 잠금)을 기다리게 하는 trigger를 건다. {@code predicate}는 SQL 조건이고 {@code j}(그 페이지의 수집을 붙인 RUNNING 계획
     * 작업 행)와 {@code NEW}(저장되는 페이지)를 쓴다 — 기다리기 전에 한 번 판정한다. {@code expire}면 문이 열린 뒤 같은
     * 트랜잭션에서 그 계획 작업의 lease를 과거로 만든다(만료형, {@link #expireAtPage}). 아니면 페이지를 늦출 뿐이고(지연형)
     * 막힌 뒤 {@link #PAGE_DOOR_LEASE_BOUND} 안에 열어야 한다.
     */
    PageDoor shutPageDoor(UUID user, PageDoorKey key, String predicate, boolean expire) throws InterruptedException {
        return shutPageDoor(user, key, predicate, expire, PAGE_DOOR_LEASE_BOUND);
    }

    /** 만료 없는 문의 막힘 뒤 상한({@code leaseBound})을 정해 닫는 {@link #shutPageDoor(UUID, PageDoorKey, String, boolean)}. */
    PageDoor shutPageDoor(UUID user, PageDoorKey key, String predicate, boolean expire, Duration leaseBound)
            throws InterruptedException {
        var door = new PageDoor(key, expire, true, leaseBound);
        door.install(user, predicate);
        return door;
    }

    /**
     * 만료형 trigger만 건다(holder 없음 — 문은 처음부터 열려 있다): 이 사용자의 페이지 저장 가운데 {@code predicate}에 맞는
     * 것이 같은 트랜잭션에서 그 계획 작업의 lease를 과거로 만든다. 페이지 저장 트랜잭션은 이미 계획 작업 행을
     * {@code FOR UPDATE}로 쥐고 fencing을 통과했고 INSERT 뒤에는 그 lease를 다시 보지 않으므로({@code storePage}는 자식
     * 수집 lease만 다시 본다), 만료는 다른 세션과 경합 없이 페이지와 함께 커밋되고 holder의 다음 fenced 문부터
     * {@code LeaseLost}다. 자식 수집 작업의 lease는 건드리지 않는다 — 같은 트랜잭션의 자식 lease 재확인이 롤백시킨다.
     * {@code predicate}가 첫 holder({@code j.resume_count=0})를 고르지 않으면 재획득된 holder도 만료된다.
     */
    PageDoor expireAtPage(UUID user, PageDoorKey key, String predicate) throws InterruptedException {
        var door = new PageDoor(key, true, false, PAGE_DOOR_LEASE_BOUND);
        door.install(user, predicate);
        return door;
    }

    /**
     * 작업이 {@code RUNNING}·{@code stage}이고 문 키에 승인되지 않은 대기(문 앞에서 막힌 페이지 저장)가 있을 때까지 기다린다.
     * 문이 기대한 자리에서 막지 못하면 마감(60초) 뒤 마지막 상태(상태|단계|재개 수|대기 수)와 함께 실패한다 — 문 holder의
     * 안전 시한(90초, 문 생성부터)보다 먼저 끝나므로 문이 스스로 열린 뒤에 통과하는 일이 없다. 막힘의 기준 시각은 관측
     * 시각이 아니라 문 대기의 {@code pg_locks.waitstart}(가장 이른 것)이고, DB에서 잰 대기 길이만큼 앞당긴
     * {@code System.nanoTime()}으로 문에 남긴다(만료 없는 문의 lease 상한은 거기서 잰다). 작업 행이 없으면 상태는 {@code GONE}이다.
     */
    void awaitBlockedAtDoor(PageDoor door, UUID job, String stage) throws InterruptedException {
        if (!door.held()) throw new IllegalStateException("an open door blocks nothing");
        String state = null;
        long deadline = System.nanoTime() + PAGE_DOOR_BLOCK_WAIT.toNanos();
        do {
            long asked = System.nanoTime();
            var row = jdbc().sql("SELECT status||'|'||COALESCE(stage,'-')||'|'||resume_count||'|'||(" + door.waiters()
                    + "), (SELECT (extract(epoch FROM clock_timestamp()-min(waitstart))*1000000)::bigint FROM pg_locks "
                    + "WHERE " + door.keyCondition() + " AND NOT granted) FROM sync_plan_sync_job WHERE job_id=:j")
                    .param("j", job).query((rs, n) -> new Object[] {rs.getString(1), rs.getObject(2, Long.class)})
                    .optional().orElse(new Object[] {"GONE", null});
            state = (String) row[0];
            // waitstart는 대기가 막 시작한 짧은 동안 null일 수 있다 — 그때는 다시 본다.
            if (state.startsWith("RUNNING|" + stage + "|") && !state.endsWith("|0") && row[1] instanceof Long waited) {
                door.blockedSince(asked - TimeUnit.MICROSECONDS.toNanos(waited));
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() - deadline < 0);
        throw new AssertionError("the attempt never waited at the " + door.key + " door "
                + "(status|stage|resume_count|waiters): " + state);
    }

    /** 문 뒤 명령이 어느 갈래로 통과했는가({@link #runBehindDoor}). */
    enum CommandBranch {
        /** 명령의 backend가 문 앞 페이지 트랜잭션 뒤에 줄 섰다(행·tuple 잠금 대기). */
        QUEUED_BEHIND_PAGE,
        /** 명령이 줄 서지 않고 먼저 끝났다 — 페이지 트랜잭션이 쥔 것을 건드리지 않는 명령. */
        FINISHED_FIRST
    }

    /**
     * 문 앞 페이지 트랜잭션(문 대기 pid) 뒤에 줄 선 backend 수: {@code pg_blocking_pids}로 그 pid가 막는 backend와, 그
     * backend가 다시 막는 backend를 끝까지 따라간다 — 첫 대기자는 행의 트랜잭션 ID를, 두 번째 대기자부터는 첫 대기자가 쥔
     * tuple 잠금을 기다리므로 둘 다 잡힌다.
     */
    private int queuedBehindDoor(PageDoor door) {
        return jdbc().sql("WITH RECURSIVE page AS (SELECT pid FROM pg_locks WHERE " + door.keyCondition()
                + " AND NOT granted), behind(pid) AS (SELECT a.pid FROM pg_stat_activity a WHERE EXISTS "
                + "(SELECT 1 FROM page p WHERE p.pid=ANY(pg_blocking_pids(a.pid))) UNION SELECT a.pid FROM "
                + "pg_stat_activity a JOIN behind b ON b.pid=ANY(pg_blocking_pids(a.pid))) SELECT count(*) FROM behind")
                .query(Integer.class).single();
    }

    /**
     * 문 앞에서 막힌 페이지 트랜잭션 뒤에서 명령을 끝낸다(지연형): 명령을 보내기 전에 그 페이지 트랜잭션 뒤에 줄 선 backend
     * 수를 기준값으로 재고, 명령을 가상 스레드로 보낸 뒤 그 수가 기준값보다 커지거나({@link CommandBranch#QUEUED_BEHIND_PAGE})
     * 명령이 이미 끝난 것({@link CommandBranch#FINISHED_FIRST})을 본 뒤 문을 열고 명령의 결과를 돌려준다. 통과한 갈래는 문에
     * 남고({@link PageDoor#commandBranch()}) 로그 한 줄로도 남는다. 페이지 트랜잭션이 커밋하면 그 행을 기다리던 명령이 먼저
     * 행을 얻는다 — 첫 대기자가 tuple 잠금을 쥔 채 기다리므로 holder의 다음 문은 그 뒤에 선다.
     *
     * <p>명령 자신의 실패는 그대로 던진다. 그 밖의 실패(줄 서기를 보지 못함, 결과 시간 초과, 문 열기 실패)에서는 문을 열고
     * 명령의 결과를 {@link #PAGE_DOOR_COMMAND_DRAIN}만큼 기다린 뒤(결과는 버린다, 남은 스레드는 interrupt) 원래 예외를
     * 던진다 — 명령이 시험 밖에서 커밋하지 않게. 정리 중 예외는 원래 예외에 {@code addSuppressed}로 붙는다.
     */
    <T> T runBehindDoor(PageDoor door, java.util.concurrent.Callable<T> command) throws Exception {
        if (!door.held()) throw new IllegalStateException("an open door holds nothing back");
        int baseline = queuedBehindDoor(door);
        var result = new java.util.concurrent.CompletableFuture<T>();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                result.complete(command.call());
            } catch (Throwable failed) {
                result.completeExceptionally(failed);
            }
        });
        try {
            int seen = pollUntil(PAGE_DOOR_COMMAND_WAIT, () -> result.isDone() ? -1 : queuedBehindDoor(door),
                    found -> found < 0 || found > baseline);
            if (seen >= 0 && seen <= baseline)
                throw new AssertionError("the command never queued behind the " + door.key + " door's page "
                        + "(queued " + seen + ", baseline " + baseline + ")");
            CommandBranch branch = seen < 0 ? CommandBranch.FINISHED_FIRST : CommandBranch.QUEUED_BEHIND_PAGE;
            door.commandBranch = branch;
            org.slf4j.LoggerFactory.getLogger(PlanSyncTestBase.class).info("page door {} command {}", door.key, branch);
            door.open();
            return result.get(PAGE_DOOR_COMMAND_DRAIN.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException commandFailed) {
            // 명령이 끝났다 — 그 실패를 그대로 던진다.
            if (commandFailed.getCause() instanceof Exception cause) throw cause;
            if (commandFailed.getCause() instanceof Error error) throw error;
            throw commandFailed;
        } catch (Exception failed) {
            abandon(door, result, runner, failed);
            throw failed;
        } catch (Error failed) {
            abandon(door, result, runner, failed);
            throw failed;
        }
    }

    /** {@link #runBehindDoor}의 실패 정리: 문을 열고 명령의 결과를 상한까지 기다린 뒤 남은 스레드를 interrupt한다. */
    private static void abandon(PageDoor door, java.util.concurrent.CompletableFuture<?> result, Thread runner,
            Throwable original) {
        try {
            door.open();
        } catch (RuntimeException | Error cleanup) {
            original.addSuppressed(cleanup);
        }
        try {
            result.get(PAGE_DOOR_COMMAND_DRAIN.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
            // 결과는 버린다 — 명령이 끝났는지(시험 밖 커밋이 없는지)만 기다린다.
        }
        if (runner.isAlive()) runner.interrupt();
    }

    /**
     * 페이지 저장 문: 전용 가상 스레드 트랜잭션이 문 키를 배타적으로 쥔다(안전 시한 90초, 만료 없는 문은 막힌 뒤 상한
     * {@code leaseBound}(기본 {@link #PAGE_DOOR_LEASE_BOUND})도 — 그 안에 열지 않으면 holder가 실패로 끝나고 문 열기가 시험을
     * 실패시킨다). 획득은 {@code lock_timeout} 4초로 묶고, 문을 닫지 못하면 holder를 풀어 끝날 때까지 기다린 뒤 생성자가
     * 실패한다 — holder 스레드·연결이 남지 않는다. holder 없는 문(만료형 trigger만)도 같은 정리를 쓴다. {@link #open()}은 한 번만 열고 holder가 끝났고
     * 예외 없이 끝났음을 단정한다. {@link #close()}는 아직 열지 않았으면 열고 trigger·함수를 지운다 — 시험이 실패해도
     * 일어나고, 정리 중 예외는 첫 예외에 {@code addSuppressed}로 붙는다.
     */
    final class PageDoor implements AutoCloseable {
        private final PageDoorKey key;
        private final boolean expires;
        /** 만료 없는 문의 막힘 뒤 상한. */
        private final Duration leaseBound;
        private final CountDownLatch release = new CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicReference<@Nullable Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final @Nullable Thread holder;
        /** 문 대기가 시작한 시각({@code pg_locks.waitstart}을 {@code System.nanoTime()}으로 옮긴 값). 0이면 아직 보지 못했다. */
        private volatile long blockedAt;
        /** {@link #runBehindDoor}가 통과한 갈래. 아직 없으면 {@code null}. */
        private volatile @Nullable CommandBranch commandBranch;
        private boolean opened;

        private PageDoor(PageDoorKey key, boolean expires, boolean shut, Duration leaseBound)
                throws InterruptedException {
            this.key = key;
            this.expires = expires;
            this.leaseBound = leaseBound;
            if (!shut) {
                holder = null;
                return;
            }
            var closed = new CountDownLatch(1);
            Thread started = Thread.ofVirtual().start(() -> {
                try {
                    new TransactionTemplate(manager).executeWithoutResult(status -> {
                        // 이 트랜잭션에만 걸리는 획득 상한: 생성자의 대기(5초)보다 먼저 끝나 실패를 failure에 남긴다.
                        jdbc().sql("SELECT set_config('lock_timeout','4s',true)").query(String.class).single();
                        jdbc().sql("WITH l AS MATERIALIZED (SELECT pg_advisory_xact_lock(" + PAGE_DOOR_CLASS + ","
                                + key.object + ")) SELECT 1 FROM l").query(Integer.class).single();
                        closed.countDown();
                        holdUntilOpened();
                    });
                } catch (Throwable failed) {
                    failure.set(failed);
                } finally {
                    closed.countDown();
                }
            });
            holder = started;
            boolean signalled = closed.await(PAGE_DOOR_SHUT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            Throwable failed = failure.get();
            if (!signalled || failed != null) {
                release.countDown();
                // holder가 끝날 때까지 기다린다 — 획득 중이면 lock_timeout(4초)이 끝낸다. 스레드·연결을 남기지 않는다.
                var error = new AssertionError("the " + key + " door was not shut", failed);
                if (!started.join(PAGE_DOOR_HOLDER_JOIN))
                    error.addSuppressed(new AssertionError("the " + key + " door holder did not end"));
                throw error;
            }
        }

        /** holder가 문을 쥔 채 열기를 기다린다. 시한을 넘기면 예외로 트랜잭션을 끝내 문을 푼다(시험 실패로 남는다). */
        private void holdUntilOpened() {
            long shutAt = System.nanoTime();
            try {
                while (!release.await(100, TimeUnit.MILLISECONDS)) {
                    long now = System.nanoTime();
                    if (now - shutAt > PAGE_DOOR_SAFETY.toNanos())
                        throw new IllegalStateException("the " + key + " door was never opened");
                    long blocked = blockedAt;
                    if (!expires && blocked != 0 && now - blocked > leaseBound.toNanos())
                        throw new IllegalStateException("the " + key + " door held a page past its lease bound "
                                + leaseBound);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("the " + key + " door holder was interrupted", interrupted);
            }
        }

        /** trigger·함수를 건다. 도중에 실패하면 문을 열고 지운 뒤 원래 예외를 던진다(정리 중 예외는 addSuppressed). */
        private void install(UUID user, String predicate) {
            try {
                // OR REPLACE: finally 전에 죽은 시험이 남긴 것이 있어도 다음 시험이 만들 수 있다.
                jdbc().sql("CREATE OR REPLACE FUNCTION " + key.trigger() + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                        + "DECLARE target uuid; BEGIN IF NEW.user_id='" + user + "'::uuid THEN "
                        + "SELECT j.job_id INTO target FROM sync_plan_sync_job j WHERE j.collection_job_id=NEW.job_id "
                        + "AND j.status='RUNNING' AND (" + predicate + "); "
                        + "IF target IS NOT NULL THEN "
                        + "PERFORM pg_advisory_xact_lock_shared(" + PAGE_DOOR_CLASS + "," + key.object + "); "
                        + (expires ? "UPDATE sync_plan_sync_job SET lease_until=statement_timestamp()-interval '1 second' "
                                + "WHERE job_id=target; " : "")
                        + "END IF; END IF; RETURN NEW; END $$").update();
                jdbc().sql("CREATE OR REPLACE TRIGGER " + key.trigger() + " BEFORE INSERT ON sync_initial_page_evidence "
                        + "FOR EACH ROW EXECUTE FUNCTION " + key.trigger() + "()").update();
            } catch (RuntimeException | Error failed) {
                try {
                    close();
                } catch (RuntimeException | Error cleanup) {
                    failed.addSuppressed(cleanup);
                }
                throw failed;
            }
        }

        boolean held() {
            return holder != null;
        }

        /** holder가 있었고 그 스레드가 끝났다(문을 쥔 트랜잭션이 끝났다). */
        boolean holderEnded() {
            return holder != null && !holder.isAlive();
        }

        void blockedSince(long nanoTime) {
            blockedAt = nanoTime;
        }

        /** {@link #runBehindDoor}가 통과한 갈래. 아직 부르지 않았으면 {@code null}. */
        @Nullable CommandBranch commandBranch() {
            return commandBranch;
        }

        /** {@code pg_locks}에서 이 문 키의 행을 고르는 조건. */
        String keyCondition() {
            return "locktype='advisory' AND classid=" + PAGE_DOOR_CLASS + " AND objid=" + key.object + " AND objsubid=2";
        }

        /** 문 앞 미승인 대기 수의 SQL. */
        String waiters() {
            return "SELECT count(*) FROM pg_locks WHERE " + keyCondition() + " AND NOT granted";
        }

        void open() {
            if (opened) return;
            opened = true;
            release.countDown();
            if (holder == null) return;
            boolean ended;
            try {
                ended = holder.join(PAGE_DOOR_HOLDER_JOIN);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while opening the " + key + " door", interrupted);
            }
            if (!ended) throw new AssertionError("the " + key + " door holder did not end");
            Throwable failed = failure.get();
            if (failed != null) throw new AssertionError("the " + key + " door holder failed", failed);
        }

        @Override
        public void close() {
            @Nullable Throwable first = null;
            try {
                open();
            } catch (RuntimeException | Error failed) {
                first = failed;
            }
            for (String drop : List.of("DROP TRIGGER IF EXISTS " + key.trigger() + " ON sync_initial_page_evidence",
                    "DROP FUNCTION IF EXISTS " + key.trigger() + "()")) {
                try {
                    jdbc().sql(drop).update();
                } catch (RuntimeException | Error failed) {
                    if (first == null) first = failed;
                    else first.addSuppressed(failed);
                }
            }
            if (first instanceof RuntimeException runtime) throw runtime;
            if (first instanceof Error error) throw error;
        }
    }
}
