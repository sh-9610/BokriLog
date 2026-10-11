package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bokrilog.sync.SyncTelemetry;
import com.bokrilog.sync.SyncTelemetry.Kind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 작업 종단 상태 → 결말의 대응(Story 3.7 AC-3). 실패율의 분모가 이 대응에 걸려 있다 — Bybit 오류(실패)를 제외로 접거나 성공에
 * 섞으면 경보가 거짓이 된다.
 */
class SyncMetricsMappingTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final SyncMetrics metrics = new SyncMetrics(this.registry, mock(JdbcClient.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class));

    @Test
    void 성공_실패_검토_막힘_삭제는_각자의_결말이다() {
        this.metrics.ending(Kind.PLAN_SYNC, "SUCCEEDED", null);
        this.metrics.ending(Kind.PLAN_SYNC, "FAILED", "PROVIDER_UNAVAILABLE");
        this.metrics.ending(Kind.PLAN_SYNC, "NEEDS_REVIEW", "SOURCE_REQUIRES_REVIEW");
        this.metrics.ending(Kind.PLAN_SYNC, "BLOCKED", "NOT_PERMITTED");
        this.metrics.ending(Kind.PLAN_SYNC, "ERASED", null);
        this.metrics.ending(Kind.FIRST_SNAPSHOT, "FAILED", "SOMETHING_UNLISTED");

        assertThat(count("plan_sync", SyncTelemetry.SUCCESS, "NONE")).isEqualTo(1.0);
        assertThat(count("plan_sync", SyncTelemetry.FAILURE, "PROVIDER_UNAVAILABLE")).isEqualTo(1.0);
        assertThat(count("plan_sync", SyncTelemetry.FAILURE, "SOURCE_REQUIRES_REVIEW")).isEqualTo(1.0);
        assertThat(count("plan_sync", SyncTelemetry.EXCLUDED, "NOT_PERMITTED")).isEqualTo(1.0);
        assertThat(count("first_snapshot", SyncTelemetry.FAILURE, SyncTelemetry.OTHER)).isEqualTo(1.0);
        assertThat(this.registry.find(SyncTelemetry.OUTCOMES).counters().stream().mapToDouble(Counter::count).sum())
                .as("삭제가 끝낸 작업은 세지 않는다").isEqualTo(5.0);
    }

    @Test
    void 주요_조합은_0으로_미리_등록되고_여러_건은_한_번에_센다() {
        assertThat(this.registry.find(SyncTelemetry.ATTEMPTS).tags("kind", "plan_sync", "trigger", "ENTRY").counter()
                .count()).isZero();
        assertThat(count("plan_sync", SyncTelemetry.EXCLUDED, "RESTRICTED")).isZero();
        assertThat(count("first_snapshot", SyncTelemetry.SUCCESS, "NONE")).isZero();
        this.metrics.outcomes(Kind.PLAN_SYNC, SyncTelemetry.EXCLUDED, "NOT_PERMITTED", 3);
        this.metrics.outcomes(Kind.PLAN_SYNC, SyncTelemetry.EXCLUDED, "NOT_PERMITTED", 0);
        assertThat(count("plan_sync", SyncTelemetry.EXCLUDED, "NOT_PERMITTED")).isEqualTo(3.0);
    }

    @Test
    void 시도의_trigger는_닫힌_목록이고_대기_나이는_측정_전_NaN이다() {
        this.metrics.attempt(Kind.PLAN_SYNC, "MANUAL");
        this.metrics.attempt(Kind.PLAN_SYNC, "SOMETHING");
        assertThat(this.registry.find(SyncTelemetry.ATTEMPTS).tags("kind", "plan_sync", "trigger", "MANUAL").counter()
                .count()).isEqualTo(1.0);
        assertThat(this.registry.find(SyncTelemetry.ATTEMPTS).tags("trigger", "OTHER").counter().count())
                .isEqualTo(1.0);
        for (Kind kind : Kind.values()) {
            assertThat(this.registry.find(SyncTelemetry.QUEUE_AGE).tags("kind", kind.tag()).gauge().value())
                    .as(kind.tag()).isNaN();
        }
    }

    private double count(String kind, String outcome, String reason) {
        Counter counter = this.registry.find(SyncTelemetry.OUTCOMES)
                .tags("kind", kind, "outcome", outcome, "reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
