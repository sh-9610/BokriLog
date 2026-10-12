package com.bokrilog.operations.internal;

import com.bokrilog.operations.BackupRecordApi.Store;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 백업 기록의 주기 측정(Story 3.9). 이름·tag는 루트 {@code TelemetryAllowlist}의 표와 같다.
 *
 * <ul>
 *   <li>{@link #LAST_SUCCESS} {@code bokrilog_backup_last_success_timestamp_seconds} — 마지막 {@code SUCCEEDED} 기록의 DB 시각
 *       (epoch 초). 경보 {@code BackupAgeExceeded}(26시간)·{@code BackupAgeUnmeasured}가 읽는다.
 *   <li>{@link #STORE_USAGE} {@code bokrilog_backup_store_usage_ratio{store}} — 저장소별 최신 {@code MEASURED}의 bytes / quota.
 *       경보 70% warning·85% critical.
 *   <li>{@link #PURGE_OVERDUE} {@code bokrilog_backup_purge_overdue{store}} — 그 최신 측정이 생성 후 30일을 넘긴 generation이 남았다고
 *       적었으면 1, 아니면 0. 경보 {@code BackupPurgeOverdue}.
 *   <li>{@link #MEASURED_AT} {@code bokrilog_backup_store_measured_timestamp_seconds{store}} — 그 최신 측정 기록의 DB 시각(epoch
 *       초). 저장소를 읽지 못해 측정이 멈추면 위 두 값은 옛 값 그대로 남는다 — 이 시각이 늙는 것으로 그것을 알린다(경보
 *       {@code BackupStoreMeasurementStale}, 26시간).
 * </ul>
 *
 * <p><b>기록이 없으면 gauge를 등록하지 않는다 — NaN을 내지 않는다.</b> {@code time() - max(NaN)}은 NaN이라 나이 경보가 거짓이 되고,
 * series가 있으니 {@code absent()}도 거짓이 되어 둘 다 침묵한다. 등록하지 않으면 {@code BackupAgeUnmeasured}가 울린다. 한 번 읽은
 * 값은 다음 읽기가 실패해도 그대로 둔다 — 성공 시각은 늘 "적어도 그때 성공했다"는 참인 값이고, 그대로 두면 나이는 계속 자라 경보가
 * 제때 울린다.
 *
 * <p>60초 주기로 가상 스레드에서 읽고 읽기 트랜잭션에 {@code statement_timeout} 2초를 건다. <b>스케줄링이 도는 역할에만 있다</b>(Story
 * 3.8 — {@code worker}·{@code all}). API 슬롯이 같은 gauge를 내면 blue/green 동안 시계열이 겹친다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
// 루트 `BokrilogRole.Schedules`와 같은 판정이다(api가 아니면 참). 모듈이 루트 타입에 기대면 루트↔모듈 순환이 생겨 같은 규칙을 식으로
// 둔다(SupportPendingMetrics와 같다).
@ConditionalOnExpression("'${bokrilog.role:all}' != 'api'")
class BackupMetrics {

    static final String LAST_SUCCESS = "bokrilog.backup.last.success.timestamp";

    static final String STORE_USAGE = "bokrilog.backup.store.usage.ratio";

    static final String PURGE_OVERDUE = "bokrilog.backup.purge.overdue";

    static final String MEASURED_AT = "bokrilog.backup.store.measured.timestamp";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BackupMetrics.class);

    private final MeterRegistry registry;

    private final BackupEventRepository events;

    private final TransactionTemplate reads;

    private final AtomicBoolean measuring = new AtomicBoolean();

    /** 마지막 성공 시각의 double 비트. 등록 전에는 쓰이지 않는다. */
    private final AtomicLong lastSuccess = new AtomicLong();

    private final AtomicBoolean lastSuccessRegistered = new AtomicBoolean();

    private final Map<Store, AtomicLong> usage = new EnumMap<>(Store.class);

    private final Map<Store, AtomicLong> overdue = new EnumMap<>(Store.class);

    private final Map<Store, AtomicLong> measuredAt = new EnumMap<>(Store.class);

    BackupMetrics(MeterRegistry registry, BackupEventRepository events, PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.events = events;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
    }

    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    void measure() {
        if (!this.measuring.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("backup-metrics").start(() -> {
            try {
                measureNow();
            } finally {
                this.measuring.set(false);
            }
        });
    }

    /** 지금 다시 읽는다. 읽지 못하면 이미 낸 값을 그대로 둔다(등록도 하지 않는다). */
    synchronized void measureNow() {
        Reading reading;
        try {
            reading = this.reads.execute(status -> {
                this.events.limitStatementTime("2s");
                return new Reading(this.events.lastSuccessEpochSeconds(), this.events.latestMeasured());
            });
        } catch (RuntimeException unreadable) {
            LOG.atDebug().addKeyValue("exception", unreadable.getClass().getSimpleName())
                    .log("backup record unreadable");
            return;
        }
        if (reading == null) {
            return;
        }
        reading.lastSuccess().ifPresent(seconds -> {
            this.lastSuccess.set(Double.doubleToLongBits(seconds));
            if (this.lastSuccessRegistered.compareAndSet(false, true)) {
                Gauge.builder(LAST_SUCCESS, this.lastSuccess, held -> Double.longBitsToDouble(held.get()))
                        .baseUnit("seconds")
                        .description("마지막 성공 백업 기록의 DB 시각(epoch 초, 두 저장소 모두 성공한 generation)")
                        .register(this.registry);
            }
        });
        for (var entry : reading.measured().entrySet()) {
            BackupEventRepository.Measured measured = entry.getValue();
            double ratio = (double) measured.bytes() / (double) measured.quotaBytes();
            publish(this.usage, STORE_USAGE, "저장소 사용량 / quota(최신 측정)", entry.getKey(), ratio, null);
            publish(this.overdue, PURGE_OVERDUE, "생성 후 30일을 넘긴 generation이 남았으면 1(최신 측정)", entry.getKey(),
                    measured.overdue() ? 1.0 : 0.0, null);
            publish(this.measuredAt, MEASURED_AT, "최신 측정 기록의 DB 시각(epoch 초)", entry.getKey(),
                    measured.measuredAtEpochSeconds(), "seconds");
        }
    }

    /** 그 저장소의 값을 둔다. 처음이면 값을 먼저 둔 뒤 gauge를 등록한다(등록 직후의 scrape가 빈 값을 읽지 않게). */
    private void publish(Map<Store, AtomicLong> values, String name, String description, Store store, double value,
            @Nullable String baseUnit) {
        AtomicLong held = values.get(store);
        if (held != null) {
            held.set(Double.doubleToLongBits(value));
            return;
        }
        AtomicLong created = new AtomicLong(Double.doubleToLongBits(value));
        values.put(store, created);
        Gauge.builder(name, created, bits -> Double.longBitsToDouble(bits.get()))
                .description(description)
                .baseUnit(baseUnit)
                .tag("store", store.tag())
                .register(this.registry);
    }

    private record Reading(Optional<Double> lastSuccess, Map<Store, BackupEventRepository.Measured> measured) {}
}
