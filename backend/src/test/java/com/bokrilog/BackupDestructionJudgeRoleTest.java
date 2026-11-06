package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.operations.BackupDestructionApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.dao.QueryTimeoutException;

/**
 * Story 5.3: 백업 파기 판정자의 배선과 gauge. 데이터베이스 없이 협력자를 대역으로 둔다.
 *
 * <ul>
 *   <li>{@code api} 역할에는 판정자가 없고 {@code worker}·{@code all}에는 있다(production에서도 — 판정은 삭제가 아니다).
 *   <li>열린 집합의 요청 ID만 identity에 묻고, 받은 운영 완료 시각을 그대로 판정에 넘긴다(루트가 잇는다).
 *   <li>gauge는 첫 읽기 성공 뒤에 등록하고, 읽지 못하면 이미 낸 값을 유지한다(NaN 없음). 내보내기 성공 시각은 기록이 있어야 등록한다.
 * </ul>
 */
class BackupDestructionJudgeRoleTest {

    private final BackupDestructionApi destruction = mock(BackupDestructionApi.class);

    private final AccountDeletionRunApi runs = mock(AccountDeletionRunApi.class);

    private final AccountDeletionApi accounts = mock(AccountDeletionApi.class);

    private BackupDestructionJudge judge(BackupDestructionApi destruction, AccountDeletionRunApi runs, MeterRegistry registry) {
        return new BackupDestructionJudge(destruction, runs, this.accounts, registry, false);
    }

    private WebApplicationContextRunner web() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(BackupDestructionJudge.class)
                .withBean(BackupDestructionApi.class, () -> this.destruction)
                .withBean(AccountDeletionRunApi.class, () -> this.runs)
                .withBean(AccountDeletionApi.class, () -> this.accounts)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("bokrilog.backup-destruction.judge.scheduled=false");
    }

    @Test
    void api_역할에는_판정자가_없고_worker와_all에는_있다() {
        web().withPropertyValues("bokrilog.role=api")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BackupDestructionJudge.class));
        for (String role : new String[] {"worker", "all"}) {
            web().withPropertyValues("bokrilog.role=" + role, "bokrilog.telemetry.env=production")
                    .run(context -> assertThat(context).hasNotFailed().hasSingleBean(BackupDestructionJudge.class));
        }
    }

    @Test
    void 열린_집합의_운영_완료_시각을_identity에서_받아_판정에_넘긴다() {
        Instant erased = Instant.parse("2026-11-05T01:00:00Z");
        when(this.destruction.openRequestIds()).thenReturn(List.of("req-a", "req-b"));
        when(this.runs.operationalCompletions(List.of("req-a", "req-b"))).thenReturn(Map.of("req-a", erased));
        when(this.destruction.judge(Map.of("req-a", erased))).thenReturn(new BackupDestructionApi.Judgement(1, 0, 0));
        when(this.destruction.measure()).thenReturn(new BackupDestructionApi.Measurement(0, 0, Optional.empty()));
        BackupDestructionJudge judge = judge(this.destruction, this.runs, new SimpleMeterRegistry());
        assertThat(judge.judgeNow()).isEqualTo(new BackupDestructionApi.Judgement(1, 0, 0));

        // 열린 집합이 없으면 identity에 묻지 않는다.
        BackupDestructionApi idle = mock(BackupDestructionApi.class);
        AccountDeletionRunApi untouched = mock(AccountDeletionRunApi.class);
        when(idle.openRequestIds()).thenReturn(List.of());
        when(idle.judge(Map.of())).thenReturn(new BackupDestructionApi.Judgement(0, 0, 0));
        when(idle.measure()).thenReturn(new BackupDestructionApi.Measurement(0, 0, Optional.empty()));
        judge(idle, untouched, new SimpleMeterRegistry()).judgeNow();
        verify(untouched, never()).operationalCompletions(any());
    }

    @Test
    void gauge는_첫_읽기_뒤에_등록하고_읽지_못하면_값을_유지한다() {
        MeterRegistry registry = new SimpleMeterRegistry();
        when(this.destruction.measure()).thenThrow(new QueryTimeoutException("timeout"));
        BackupDestructionJudge judge = judge(this.destruction, this.runs, registry);
        judge.measureNow();
        assertThat(registry.find(BackupDestructionJudge.OVERDUE).gauges()).as("읽지 못한 첫 측정은 등록하지 않는다").isEmpty();

        Mockito.reset(this.destruction);
        when(this.destruction.measure()).thenReturn(new BackupDestructionApi.Measurement(2, 1, Optional.empty()));
        judge.measureNow();
        assertThat(registry.get(BackupDestructionJudge.DUE_SOON).gauge().value()).isEqualTo(2.0);
        assertThat(registry.get(BackupDestructionJudge.OVERDUE).gauge().value()).isEqualTo(1.0);
        assertThat(registry.find(BackupDestructionJudge.EXPORT_LAST_SUCCESS).gauges()).as("기록이 없다").isEmpty();

        Mockito.reset(this.destruction);
        when(this.destruction.measure()).thenReturn(new BackupDestructionApi.Measurement(0, 3,
                Optional.of(1_762_000_000.5)));
        judge.measureNow();
        assertThat(registry.get(BackupDestructionJudge.OVERDUE).gauge().value()).isEqualTo(3.0);
        assertThat(registry.get(BackupDestructionJudge.EXPORT_LAST_SUCCESS).gauge().value()).isEqualTo(1_762_000_000.5);

        Mockito.reset(this.destruction);
        when(this.destruction.measure()).thenThrow(new QueryTimeoutException("timeout"));
        judge.measureNow();
        assertThat(registry.get(BackupDestructionJudge.OVERDUE).gauge().value()).as("NaN으로 접지 않는다").isEqualTo(3.0);
        assertThat(registry.get(BackupDestructionJudge.EXPORT_LAST_SUCCESS).gauge().value()).isEqualTo(1_762_000_000.5);
    }

    /** 새 gauge 셋의 Prometheus 노출 이름은 경보 규칙(alerts.yml)이 읽는 그 이름이다. */
    @Test
    void gauge의_노출_이름은_경보_규칙이_읽는_이름이다() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        when(this.destruction.measure())
                .thenReturn(new BackupDestructionApi.Measurement(1, 2, Optional.of(1_762_000_000.0)));
        judge(this.destruction, this.runs, registry).measureNow();
        Map<String, Double> series = new HashMap<>();
        for (String line : registry.scrape().split("\n")) {
            if (!line.startsWith("#") && !line.isBlank()) {
                String[] parts = line.trim().split(" ");
                series.put(parts[0], Double.parseDouble(parts[1]));
            }
        }
        assertThat(series).containsEntry("bokrilog_deletion_backup_destruction_due_soon", 1.0)
                .containsEntry("bokrilog_deletion_backup_destruction_overdue", 2.0)
                .containsEntry("bokrilog_tombstone_export_last_success_timestamp_seconds", 1_762_000_000.0);
    }

    @Test
    void 판정이_실패해도_gauge는_다시_읽는다() {
        MeterRegistry registry = new SimpleMeterRegistry();
        when(this.destruction.openRequestIds()).thenReturn(List.of());
        when(this.destruction.judge(any())).thenThrow(new QueryTimeoutException("timeout"));
        when(this.destruction.measure()).thenReturn(new BackupDestructionApi.Measurement(0, 1, Optional.empty()));
        BackupDestructionJudge judge = judge(this.destruction, this.runs, registry);
        assertThatThrownBy(judge::judgeNow).isInstanceOf(QueryTimeoutException.class);
        assertThat(registry.get(BackupDestructionJudge.OVERDUE).gauge().value()).isEqualTo(1.0);
    }

    /**
     * Story 5.4 2차 리뷰: 전체 삭제 완료 기록이 실패해도 완료 세션 정리와 gauge는 돈다. 파기 판정 자체가 실패해도 정리는 돈다(판정 예외는
     * 그대로 올라간다).
     */
    @Test
    void 전체_완료_기록이_실패해도_완료_세션_정리는_돈다() {
        when(this.destruction.openRequestIds()).thenReturn(List.of());
        when(this.destruction.judge(Map.of())).thenReturn(new BackupDestructionApi.Judgement(0, 0, 0));
        when(this.destruction.measure()).thenReturn(new BackupDestructionApi.Measurement(0, 0, Optional.empty()));
        when(this.runs.awaitingDeletionCompletion()).thenThrow(new QueryTimeoutException("timeout"));
        when(this.accounts.scrubCompletionSessions()).thenReturn(1);
        BackupDestructionJudge judge = judge(this.destruction, this.runs, new SimpleMeterRegistry());

        assertThat(judge.judgeNow()).as("완료 기록 실패는 판정을 실패로 바꾸지 않는다")
                .isEqualTo(new BackupDestructionApi.Judgement(0, 0, 0));
        verify(this.accounts).scrubCompletionSessions();
        verify(this.destruction).measure();

        Mockito.reset(this.destruction, this.accounts);
        when(this.destruction.openRequestIds()).thenReturn(List.of());
        when(this.destruction.judge(any())).thenThrow(new QueryTimeoutException("timeout"));
        when(this.destruction.measure()).thenReturn(new BackupDestructionApi.Measurement(0, 0, Optional.empty()));
        assertThatThrownBy(judge::judgeNow).isInstanceOf(QueryTimeoutException.class);
        verify(this.accounts).scrubCompletionSessions();
    }
}
