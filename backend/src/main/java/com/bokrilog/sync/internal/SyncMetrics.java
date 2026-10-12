package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncTelemetry;
import com.bokrilog.sync.SyncTelemetry.Kind;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 동기화 계측(Story 3.7 AC-3). 이름과 tag 값은 {@link SyncTelemetry}의 닫힌 어휘뿐이고, 등록은 Micrometer {@link MeterRegistry}
 * 하나로 한다 — 이 모듈은 Actuator를 모른다. 정의(분모·기간·환경)는 {@link SyncTelemetry}에 있다.
 *
 * <p><b>커밋된 사실만 센다.</b> 시도·결말은 그것을 쓴 트랜잭션이 커밋된 뒤에 센다({@link #afterCommit}). 롤백된 admission이나
 * 경합으로 다시 판정한 admission이 두 번 세지지 않는다.
 *
 * <p><b>대기 나이는 주기 측정이다.</b> 15초마다 DB 시각으로 종류별 가장 오래된 {@code QUEUED} 작업의 나이를 읽어 gauge 값으로
 * 둔다. scrape가 DB를 두드리지 않는다. 읽지 못하면 NaN(미측정)으로 둔다 — 0으로 접으면 대기 없음과 구분되지 않는다(경보
 * {@code SyncQueueAgeUnmeasured}). 스케줄은 API 프로세스에만 있다({@code FirstSnapshotSchedulingConfig}). 측정은 공유
 * 스케줄러 스레드가 아니라 가상 스레드에서 돌고(작업자 dispatch·heartbeat를 막지 않는다), 읽기 트랜잭션에 {@code
 * statement_timeout} 2초를 건다. 앞 측정이 끝나지 않았으면 이번 tick은 건너뛴다.
 *
 * <p><b>카운터는 미리 0으로 등록한다.</b> 처음 증가할 때 시계열이 생기면 {@code increase()}가 그 첫 증가를 놓친다. 주요 종류·결말·
 * 사유 조합({@link #PRE_REGISTERED})을 기동 때 0으로 만든다. 표 밖 조합은 처음 셀 때 생긴다.
 */
@Component
class SyncMetrics {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SyncMetrics.class);

    /** 종류별 대기 나이 표. 작업 표마다 대기 시각 열이 다르다(계획 동기화만 {@code admitted_at}). */
    private static final String QUEUE_AGE_SQL = """
            SELECT 'FIRST_SNAPSHOT', extract(epoch FROM statement_timestamp() - min(created_at))
              FROM sync_job WHERE status = 'QUEUED'
            UNION ALL SELECT 'INITIAL_COLLECTION', extract(epoch FROM statement_timestamp() - min(created_at))
              FROM sync_initial_collection_job WHERE status = 'QUEUED'
            UNION ALL SELECT 'CLASSIFICATION', extract(epoch FROM statement_timestamp() - min(created_at))
              FROM sync_cashflow_classification_job WHERE status = 'QUEUED'
            UNION ALL SELECT 'CONVERSION', extract(epoch FROM statement_timestamp() - min(created_at))
              FROM sync_cashflow_conversion_job WHERE status = 'QUEUED'
            UNION ALL SELECT 'FIRST_PUBLICATION', extract(epoch FROM statement_timestamp() - min(created_at))
              FROM sync_plan_publication_job WHERE status = 'QUEUED'
            UNION ALL SELECT 'PLAN_SYNC', extract(epoch FROM statement_timestamp() - min(admitted_at))
              FROM sync_plan_sync_job WHERE status = 'QUEUED'
            """;

    /** 측정 읽기의 상한. 넘으면 그 tick은 미측정이다. */
    static final String STATEMENT_TIMEOUT = "2s";

    /** 기동 때 0으로 등록하는 결말 조합: 종류 → {결말, 사유}. */
    static final Map<Kind, java.util.List<String[]>> PRE_REGISTERED = Map.of(
            Kind.PLAN_SYNC, java.util.List.of(
                    new String[] {SyncTelemetry.SUCCESS, SyncTelemetry.NONE},
                    new String[] {SyncTelemetry.EXCLUDED, "RESTRICTED"},
                    new String[] {SyncTelemetry.EXCLUDED, "REVIEW_HELD"},
                    new String[] {SyncTelemetry.EXCLUDED, "LONG_GAP"},
                    new String[] {SyncTelemetry.EXCLUDED, "NOT_PERMITTED"},
                    new String[] {SyncTelemetry.FAILURE, "PROVIDER_UNAVAILABLE"},
                    new String[] {SyncTelemetry.FAILURE, "SYNC_FAILED"},
                    new String[] {SyncTelemetry.FAILURE, "SYNC_INTERRUPTED"},
                    new String[] {SyncTelemetry.FAILURE, SyncTelemetry.OTHER}),
            Kind.FIRST_SNAPSHOT, java.util.List.of(
                    new String[] {SyncTelemetry.SUCCESS, SyncTelemetry.NONE},
                    new String[] {SyncTelemetry.EXCLUDED, "RESTRICTED"},
                    new String[] {SyncTelemetry.EXCLUDED, "NOT_PERMITTED"},
                    new String[] {SyncTelemetry.FAILURE, "UNAVAILABLE"},
                    new String[] {SyncTelemetry.FAILURE, "PROVIDER_UNAVAILABLE"},
                    new String[] {SyncTelemetry.FAILURE, SyncTelemetry.OTHER}));

    /** 기동 때 0으로 등록하는 시도 조합. */
    static final Map<Kind, java.util.List<String>> PRE_REGISTERED_TRIGGERS = Map.of(
            Kind.PLAN_SYNC, java.util.List.of("ENTRY", "VISIBILITY", "MANUAL", "SYSTEM"),
            Kind.FIRST_SNAPSHOT, java.util.List.of("USER"));

    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final org.springframework.transaction.support.TransactionTemplate reads;
    private final java.util.concurrent.atomic.AtomicBoolean measuring = new java.util.concurrent.atomic.AtomicBoolean();
    /** 종류별 대기 나이(초)의 double 비트. NaN이 미측정이다. */
    private final Map<Kind, AtomicLong> ages = new EnumMap<>(Kind.class);

    SyncMetrics(MeterRegistry registry, JdbcClient jdbc,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.reads = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        PRE_REGISTERED_TRIGGERS.forEach((kind, triggers) -> triggers.forEach(trigger ->
                registry.counter(SyncTelemetry.ATTEMPTS, "kind", kind.tag(), "trigger", trigger)));
        PRE_REGISTERED.forEach((kind, pairs) -> pairs.forEach(pair ->
                registry.counter(SyncTelemetry.OUTCOMES, "kind", kind.tag(), "outcome", pair[0], "reason", pair[1])));
        for (Kind kind : Kind.values()) {
            AtomicLong bits = new AtomicLong(Double.doubleToLongBits(Double.NaN));
            this.ages.put(kind, bits);
            Gauge.builder(SyncTelemetry.QUEUE_AGE, bits, held -> Double.longBitsToDouble(held.get()))
                    .baseUnit("seconds")
                    .description("가장 오래 기다린 QUEUED 작업의 나이(DB 시각, 15초 주기 측정)")
                    .tag("kind", kind.tag())
                    .register(registry);
        }
    }

    /** 유효 시도 하나(작업 행이 커밋되면). */
    void attempt(Kind kind, @Nullable String trigger) {
        String value = SyncTelemetry.trigger(trigger);
        afterCommit(() -> this.registry.counter(SyncTelemetry.ATTEMPTS, "kind", kind.tag(), "trigger", value)
                .increment());
    }

    /** 결말 또는 제외 하나(그 사실이 커밋되면). */
    void outcome(Kind kind, String outcome, @Nullable String code) {
        outcomes(kind, outcome, code, 1);
    }

    /** 같은 결말 여럿(한 문장이 작업 여럿을 끝냈을 때). 0 이하면 아무것도 하지 않는다. */
    void outcomes(Kind kind, String outcome, @Nullable String code, int count) {
        if (count <= 0) return;
        String reason = SyncTelemetry.SUCCESS.equals(outcome) ? SyncTelemetry.NONE : SyncTelemetry.reason(code);
        afterCommit(() -> this.registry.counter(SyncTelemetry.OUTCOMES, "kind", kind.tag(), "outcome", outcome,
                "reason", reason).increment(count));
    }

    /**
     * 작업 행의 종단 상태를 결말로. 성공은 {@code SUCCEEDED}, 막힘은 {@code BLOCKED}(제한·권한·계획 변경 — 정책이 막은 것),
     * 그 밖({@code FAILED}·{@code NEEDS_REVIEW})은 실패다. 지운 계정의 종단({@code ERASED})은 세지 않는다.
     */
    void ending(Kind kind, String status, @Nullable String code) {
        switch (status) {
            case "SUCCEEDED" -> outcome(kind, SyncTelemetry.SUCCESS, null);
            case "BLOCKED" -> outcome(kind, SyncTelemetry.EXCLUDED, code);
            case "ERASED" -> {
                // 계정 삭제가 끝낸 작업은 운영 결말이 아니다.
            }
            default -> outcome(kind, SyncTelemetry.FAILURE, code);
        }
    }

    /** 대기 나이 측정을 가상 스레드에 넘긴다. 앞 측정이 아직 돌면 건너뛴다. */
    @Scheduled(initialDelay = 0, fixedDelay = 15_000)
    void measureQueueAge() {
        if (!this.measuring.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("sync-queue-age").start(() -> {
            try {
                measureQueueAgeNow();
            } finally {
                this.measuring.set(false);
            }
        });
    }

    /** 대기 나이를 지금 다시 읽는다. 실패하면 모든 종류를 미측정(NaN)으로 둔다. */
    void measureQueueAgeNow() {
        Map<Kind, Double> measured = new EnumMap<>(Kind.class);
        try {
            this.reads.executeWithoutResult(status -> {
                this.jdbc.sql("SET LOCAL statement_timeout = '" + STATEMENT_TIMEOUT + "'").update();
                this.jdbc.sql(QUEUE_AGE_SQL).query((rs, row) -> {
                    double seconds = rs.getDouble(2);
                    measured.put(Kind.valueOf(rs.getString(1)), rs.wasNull() ? 0.0 : Math.max(0.0, seconds));
                    return null;
                }).list();
            });
        } catch (RuntimeException unreadable) {
            measured.clear();
            LOG.atDebug().addKeyValue("exception", unreadable.getClass().getSimpleName()).log("sync queue age unreadable");
        }
        for (Kind kind : Kind.values()) {
            Double value = measured.get(kind);
            this.ages.get(kind).set(Double.doubleToLongBits(value == null ? Double.NaN : value));
        }
    }

    /** 트랜잭션 안이면 커밋 뒤에, 밖이면 바로. */
    private static void afterCommit(Runnable count) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    count.run();
                }
            });
        } else {
            count.run();
        }
    }
}
