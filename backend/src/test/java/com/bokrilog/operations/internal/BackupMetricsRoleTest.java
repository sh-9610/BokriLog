package com.bokrilog.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bokrilog.operations.BackupRecordApi.Store;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Story 3.9: 백업 gauge는 측정하는 역할({@code worker}·{@code all})에만 있고, 기록이 없으면 등록하지 않으며(NaN 없음), 읽은 값을
 * 그대로 낸다. 읽기가 실패하면 이미 낸 값을 유지한다. Prometheus 이름은 경보 규칙({@code alerts.yml})이 읽는 그 이름이다.
 */
class BackupMetricsRoleTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withUserConfiguration(BackupMetrics.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(BackupEventRepository.class, () -> mock(BackupEventRepository.class))
            .withBean(PlatformTransactionManager.class, BackupMetricsRoleTest::transactions);

    @Test
    void api_역할에는_백업_측정이_없다() {
        this.web.withPropertyValues("bokrilog.role=api")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BackupMetrics.class));
    }

    @Test
    void worker와_all_역할에는_백업_측정이_있다() {
        for (String role : new String[] {"worker", "all"}) {
            this.web.withPropertyValues("bokrilog.role=" + role)
                    .run(context -> assertThat(context).hasNotFailed().hasSingleBean(BackupMetrics.class));
        }
    }

    @Test
    void 성공_기록이_없으면_gauge를_등록하지_않는다() {
        MeterRegistry registry = new SimpleMeterRegistry();
        BackupEventRepository events = mock(BackupEventRepository.class);
        when(events.lastSuccessEpochSeconds()).thenReturn(Optional.empty());
        when(events.latestMeasured()).thenReturn(Map.of());
        new BackupMetrics(registry, events, transactions()).measureNow();
        assertThat(registry.find(BackupMetrics.LAST_SUCCESS).gauges()).isEmpty();
        assertThat(registry.find(BackupMetrics.STORE_USAGE).gauges()).isEmpty();
        assertThat(registry.find(BackupMetrics.PURGE_OVERDUE).gauges()).isEmpty();
        assertThat(registry.find(BackupMetrics.MEASURED_AT).gauges()).isEmpty();
    }

    @Test
    void 읽지_못하면_등록하지_않고_이미_낸_값은_유지한다() {
        MeterRegistry registry = new SimpleMeterRegistry();
        BackupEventRepository events = mock(BackupEventRepository.class);
        doThrow(new QueryTimeoutException("timeout")).when(events).limitStatementTime(anyString());
        BackupMetrics metrics = new BackupMetrics(registry, events, transactions());
        metrics.measureNow();
        assertThat(registry.find(BackupMetrics.LAST_SUCCESS).gauges()).as("읽지 못한 첫 측정은 등록하지 않는다").isEmpty();

        doNothing().when(events).limitStatementTime(anyString());
        when(events.lastSuccessEpochSeconds()).thenReturn(Optional.of(1_760_000_000.5));
        when(events.latestMeasured()).thenReturn(measured(Store.OCI, 7_000, 10_000, false));
        metrics.measureNow();
        assertThat(registry.get(BackupMetrics.LAST_SUCCESS).gauge().value()).isEqualTo(1_760_000_000.5);

        doThrow(new QueryTimeoutException("timeout")).when(events).limitStatementTime(anyString());
        metrics.measureNow();
        assertThat(registry.get(BackupMetrics.LAST_SUCCESS).gauge().value()).as("NaN으로 접지 않는다")
                .isEqualTo(1_760_000_000.5);
        assertThat(registry.get(BackupMetrics.STORE_USAGE).tag("store", "oci").gauge().value()).isEqualTo(0.7);
    }

    @Test
    void 성공_시각과_저장소별_사용률과_기한_초과를_낸다() {
        MeterRegistry registry = new SimpleMeterRegistry();
        BackupEventRepository events = mock(BackupEventRepository.class);
        when(events.lastSuccessEpochSeconds()).thenReturn(Optional.of(1_760_000_000.0));
        Map<Store, BackupEventRepository.Measured> both = new EnumMap<>(Store.class);
        both.putAll(measured(Store.OCI, 17_000, 20_000, false));
        both.putAll(measured(Store.OFFSITE, 7_000, 10_000, true));
        when(events.latestMeasured()).thenReturn(both);
        BackupMetrics metrics = new BackupMetrics(registry, events, transactions());
        metrics.measureNow();
        assertThat(registry.get(BackupMetrics.LAST_SUCCESS).gauge().value()).isEqualTo(1_760_000_000.0);
        assertThat(registry.get(BackupMetrics.STORE_USAGE).tag("store", "oci").gauge().value()).isEqualTo(0.85);
        assertThat(registry.get(BackupMetrics.STORE_USAGE).tag("store", "offsite").gauge().value()).isEqualTo(0.7);
        assertThat(registry.get(BackupMetrics.PURGE_OVERDUE).tag("store", "oci").gauge().value()).isZero();
        assertThat(registry.get(BackupMetrics.PURGE_OVERDUE).tag("store", "offsite").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(BackupMetrics.MEASURED_AT).tag("store", "oci").gauge().value()).isEqualTo(1_760_000_100.0);

        // 새 성공·측정이 오면 같은 gauge의 값이 바뀐다(등록은 한 번).
        when(events.lastSuccessEpochSeconds()).thenReturn(Optional.of(1_760_086_400.0));
        when(events.latestMeasured()).thenReturn(measured(Store.OFFSITE, 1_000, 10_000, false));
        metrics.measureNow();
        assertThat(registry.find(BackupMetrics.LAST_SUCCESS).gauges()).hasSize(1);
        assertThat(registry.get(BackupMetrics.LAST_SUCCESS).gauge().value()).isEqualTo(1_760_086_400.0);
        assertThat(registry.get(BackupMetrics.PURGE_OVERDUE).tag("store", "offsite").gauge().value()).isZero();
    }

    @Test
    void Prometheus_이름은_경보_규칙이_읽는_이름이다() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        BackupEventRepository events = mock(BackupEventRepository.class);
        when(events.lastSuccessEpochSeconds()).thenReturn(Optional.of(1_760_000_000.0));
        when(events.latestMeasured()).thenReturn(measured(Store.OCI, 1, 4, true));
        new BackupMetrics(registry, events, transactions()).measureNow();
        String body = registry.scrape();
        assertThat(body).containsPattern("(?m)^bokrilog_backup_last_success_timestamp_seconds 1\\.76E9$")
                .containsPattern("(?m)^bokrilog_backup_store_usage_ratio\\{store=\"oci\"} 0\\.25$")
                .containsPattern("(?m)^bokrilog_backup_purge_overdue\\{store=\"oci\"} 1(\\.0)?$")
                .containsPattern("(?m)^bokrilog_backup_store_measured_timestamp_seconds\\{store=\"oci\"} 1\\.7600001E9$");
    }

    // ------------------------------------------------------------------ helpers

    private static Map<Store, BackupEventRepository.Measured> measured(Store store, long bytes, long quota,
            boolean overdue) {
        Map<Store, BackupEventRepository.Measured> measured = new EnumMap<>(Store.class);
        measured.put(store, new BackupEventRepository.Measured(bytes, quota, overdue, 1_760_000_100.0));
        return measured;
    }

    /** 콜백을 그대로 돌리는 트랜잭션 관리자. */
    private static PlatformTransactionManager transactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }
}
