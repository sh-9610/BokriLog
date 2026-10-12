package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.sync.SyncTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 동기화 계측의 분모와 제외 사유(Story 3.7 AC-3).
 *
 * <p>유효 시도는 admission을 통과해 작업 행이 커밋된 것뿐이다. 제한이 막은 요청은 시도가 아니라 {@code excluded{reason=RESTRICTED}}
 * 로 세고, 시도의 결말은 종료와 함께 센다. 카운터는 컨텍스트 수명 동안 쌓이므로 시험은 증분을 본다.
 */
class SyncMetricsTest extends PlanSyncTestBase {

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern EMAIL = Pattern.compile("[^\\s@\"]+@[^\\s@\"]+\\.[^\\s@\"]+");

    private static final Pattern LONG_DIGITS = Pattern.compile("\\p{Nd}{6,}");

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 수동_동기화_성공_하나와_제한_거절_하나는_시도_1_성공_1_제외_1이다(CapturedOutput output) throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "metrics-sync", null);
        double attempts = count(SyncTelemetry.ATTEMPTS, "kind", "plan_sync", "trigger", "MANUAL");
        double successes = count(SyncTelemetry.OUTCOMES, "kind", "plan_sync", "outcome", "success", "reason", "NONE");
        double restricted = count(SyncTelemetry.OUTCOMES, "kind", "plan_sync", "outcome", "excluded",
                "reason", "RESTRICTED");
        double failures = sum(SyncTelemetry.OUTCOMES, "kind", "plan_sync", "outcome", "failure");

        var accepted = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "metrics-sync-key-0001");
        assertThat(accepted).isInstanceOf(SyncJobApi.SyncAdmission.Accepted.class);
        UUID job = ((SyncJobApi.SyncAdmission.Accepted) accepted).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        // 결말은 종료 줄과 함께 센다 — 상태 커밋 뒤에 오므로 잠시 기다린다.
        pollUntil(Duration.ofSeconds(10), () -> count(SyncTelemetry.OUTCOMES, "kind", "plan_sync", "outcome",
                "success", "reason", "NONE"), value -> value > successes);

        UUID restriction = restrictGlobally(RestrictionFeature.SYNC);
        try {
            var refused = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "metrics-sync-key-0002");
            assertThat(refused).isEqualTo(new SyncJobApi.SyncAdmission.Refused("SYNC_UNAVAILABLE"));
        } finally {
            lift(restriction);
        }

        assertThat(count(SyncTelemetry.ATTEMPTS, "kind", "plan_sync", "trigger", "MANUAL")).as("통과분만 시도다")
                .isEqualTo(attempts + 1);
        assertThat(count(SyncTelemetry.OUTCOMES, "kind", "plan_sync", "outcome", "success", "reason", "NONE"))
                .isEqualTo(successes + 1);
        assertThat(count(SyncTelemetry.OUTCOMES, "kind", "plan_sync", "outcome", "excluded", "reason",
                "RESTRICTED")).isEqualTo(restricted + 1);
        assertThat(sum(SyncTelemetry.OUTCOMES, "kind", "plan_sync", "outcome", "failure")).isEqualTo(failures);

        // AC-6: 이 왕복이 남긴 로그 어디에도 UUID·이메일·숫자 6자리 이상이 없다. 예외는 계약으로 둘이다(DEVELOPMENT.md) —
        // 요청 correlation ID(요청마다 새로 만드는 값, 사용자 귀속이 아니다)와 구조 수치 durationMs·count(정수 그대로).
        // 계정 삭제 뒤 로그로 사용자를 다시 찾을 근거가 남지 않는다.
        var records = LogLines.records(output.getAll());
        assertThat(records).isNotEmpty();
        assertThat(LogLines.events(output.getAll(), "plan sync finished: ")).isNotEmpty();
        for (var record : records) {
            for (var field : record.entrySet()) {
                if (java.util.Set.of("correlationId", "durationMs", "count").contains(field.getKey())) {
                    continue;
                }
                String value = String.valueOf(field.getValue());
                assertThat(UUID_TEXT.matcher(value).find()).as(field.getKey() + "=" + value).isFalse();
                assertThat(EMAIL.matcher(value).find()).as(field.getKey() + "=" + value).isFalse();
                assertThat(LONG_DIGITS.matcher(value).find()).as(field.getKey() + "=" + value).isFalse();
            }
        }
        assertThat(output.getAll()).doesNotContain(user.toString()).doesNotContain(job.toString());
    }

    @Test
    void 첫_스냅샷도_시도와_결말을_세고_대기_나이는_여섯_종류를_주기로_잰다() throws Exception {
        double attempts = count(SyncTelemetry.ATTEMPTS, "kind", "first_snapshot", "trigger", "USER");
        double successes = count(SyncTelemetry.OUTCOMES, "kind", "first_snapshot", "outcome", "success",
                "reason", "NONE");
        double restricted = count(SyncTelemetry.OUTCOMES, "kind", "first_snapshot", "outcome", "excluded",
                "reason", "RESTRICTED");
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        // 제한 중의 요청은 시도가 아니라 제외다.
        UUID restriction = restrictGlobally(RestrictionFeature.SYNC);
        try {
            assertThat(sync.admit(user, "metrics-snapshot-restricted")).isEmpty();
        } finally {
            lift(restriction);
        }
        assertThat(count(SyncTelemetry.OUTCOMES, "kind", "first_snapshot", "outcome", "excluded", "reason",
                "RESTRICTED")).isEqualTo(restricted + 1);
        assertThat(count(SyncTelemetry.ATTEMPTS, "kind", "first_snapshot", "trigger", "USER")).isEqualTo(attempts);
        UUID job = sync.admit(user, "metrics-snapshot-seed").orElseThrow().jobId();
        assertThat(pollUntil(Duration.ofSeconds(10), () -> sync.job(user, job).orElseThrow().status(),
                PlanSyncTestBase::finished)).isEqualTo("SUCCEEDED");
        assertThat(count(SyncTelemetry.ATTEMPTS, "kind", "first_snapshot", "trigger", "USER"))
                .isEqualTo(attempts + 1);
        assertThat(pollUntil(Duration.ofSeconds(10), () -> count(SyncTelemetry.OUTCOMES, "kind", "first_snapshot",
                "outcome", "success", "reason", "NONE"), value -> value > successes)).isGreaterThan(successes);
        // 주기 측정(15초): 기동 직후 한 번 재고, 그 뒤 값은 NaN이 아니고 0 이상이다.
        for (SyncTelemetry.Kind kind : SyncTelemetry.Kind.values()) {
            double age = pollUntil(Duration.ofSeconds(20), () -> queueAge(kind), value -> !Double.isNaN(value));
            assertThat(age).as(kind.tag()).isNotNaN().isGreaterThanOrEqualTo(0.0);
        }
    }

    @Test
    void 대기_행이_있으면_그_종류의_대기_나이는_0보다_크다() throws Exception {
        UUID user = connect(DevBybitStub.Scenario.UTA2);
        publishedPlan(user, "metrics-queue", null);
        var accepted = sync.admitPlanSync(user, SyncJobApi.SyncTrigger.MANUAL, "metrics-queue-key-0001");
        UUID job = ((SyncJobApi.SyncAdmission.Accepted) accepted).job().jobId();
        assertThat(awaitSync(job)).isEqualTo("SUCCEEDED");
        // 계획 동기화가 소유한 판정 행은 작업자가 claim하지 않는다(plan_sync_job_id). 그 행을 10분 전에 대기한 것으로 되돌린다.
        assertThat(jdbc().sql("UPDATE sync_cashflow_classification_job SET status='QUEUED',lease_until=NULL,"
                + "finished_at=NULL,created_at=statement_timestamp()-interval '10 minutes' WHERE plan_sync_job_id=:j")
                .param("j", job).update()).isEqualTo(1);
        TelemetryProbe.run(this.context, "syncQueueAgeMetrics", "measureQueueAgeNow");
        assertThat(queueAge(SyncTelemetry.Kind.CLASSIFICATION)).isBetween(599.0, 3600.0);
        assertThat(queueAge(SyncTelemetry.Kind.PLAN_SYNC)).isGreaterThanOrEqualTo(0.0);
    }

    private double count(String name, String... tags) {
        Counter counter = this.registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double sum(String name, String... tags) {
        return this.registry.find(name).tags(tags).counters().stream().mapToDouble(Counter::count).sum();
    }

    private double queueAge(SyncTelemetry.Kind kind) {
        Gauge gauge = this.registry.find(SyncTelemetry.QUEUE_AGE).tags("kind", kind.tag()).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }
}
