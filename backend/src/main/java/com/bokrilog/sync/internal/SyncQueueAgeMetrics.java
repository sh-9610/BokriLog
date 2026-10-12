package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncTelemetry;
import com.bokrilog.sync.SyncTelemetry.Kind;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 동기화 대기 나이의 주기 측정(Story 3.7 AC-3). 정의는 {@link SyncTelemetry#QUEUE_AGE}에 있다.
 *
 * <p>15초마다 DB 시각으로 종류별 가장 오래된 {@code QUEUED} 작업의 나이를 읽어 gauge 값으로 둔다. scrape가 DB를 두드리지 않는다.
 * 읽지 못하면 NaN(미측정)으로 둔다 — 0으로 접으면 대기 없음과 구분되지 않는다(경보 {@code SyncQueueAgeUnmeasured}). 측정은 공유
 * 스케줄러 스레드가 아니라 가상 스레드에서 돌고(작업자 dispatch·heartbeat를 막지 않는다), 읽기 트랜잭션에 {@code
 * statement_timeout} 2초를 건다. 앞 측정이 끝나지 않았으면 이번 tick은 건너뛴다.
 *
 * <p><b>스케줄링이 도는 역할에만 있다</b>(Story 3.8, 루트 {@code BokrilogRole.Schedules}와 같은 판정 — {@code worker}·{@code all}). API 역할에는
 * 측정이 없으므로 gauge도 없다 — 측정하지 않는 프로세스가 NaN gauge를 영원히 내보내면 그 프로세스마다 미측정 경보가 울린다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
// 루트 `BokrilogRole.Schedules`와 같은 판정이다(api가 아니면 참 — 읽을 수 없는 값도 참이고 그 값은 기동이 거부된다). 모듈이 루트 타입에
// 기대면 루트↔모듈 순환이 생겨 같은 규칙을 식으로 둔다. 셋의 일치는 역할 시험들이 고정한다.
@ConditionalOnExpression("'${bokrilog.role:all}' != 'api'")
class SyncQueueAgeMetrics {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SyncQueueAgeMetrics.class);

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

    private final JdbcClient jdbc;
    private final TransactionTemplate reads;
    private final AtomicBoolean measuring = new AtomicBoolean();
    /** 종류별 대기 나이(초)의 double 비트. NaN이 미측정이다. */
    private final Map<Kind, AtomicLong> ages = new EnumMap<>(Kind.class);

    SyncQueueAgeMetrics(MeterRegistry registry, JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
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
}
