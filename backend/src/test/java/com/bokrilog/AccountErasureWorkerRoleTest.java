package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.identity.AccountDeletionRunApi.Lease;
import com.bokrilog.operations.DeletionTombstoneApi;
import com.bokrilog.sync.internal.WorkerDrainTestHook;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Story 5.2: 계정 삭제 Worker의 배선과 상한. 데이터베이스 없이 협력자를 대역으로 둔다.
 *
 * <ul>
 *   <li>{@code api} 역할에는 Worker bean이 없고, {@code worker} 역할의 실제 drain bean({@code ObjectProvider} 생성자)은 그 Worker를 함께
 *       묶는다 — 진행 중이 묶음에 보이고, 묶음의 claim 멈춤이 Worker의 집기를 닫는다.
 *   <li>생성자에서 받은 {@code production}이면 집지 않는다.
 *   <li>한 Worker의 동시 실행은 {@link AccountErasureWorker#MAX_IN_FLIGHT}까지다.
 * </ul>
 */
class AccountErasureWorkerRoleTest {

    private final AccountErasureRun runner = mock(AccountErasureRun.class);

    private final AccountDeletionRunApi runs = mock(AccountDeletionRunApi.class);

    private final DeletionTombstoneApi tombstones = mock(DeletionTombstoneApi.class);

    private WebApplicationContextRunner web() {
        when(this.tombstones.keyAvailable()).thenReturn(true);
        return new WebApplicationContextRunner()
                .withUserConfiguration(AccountErasureWorker.class, WorkerDrainTestHook.drainType())
                .withInitializer(WorkerDrainTestHook.idleTarget())
                // @Value Duration(drain-timeout)을 풀 변환기 — Boot 애플리케이션과 같은 것.
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withBean(AccountErasureRun.class, () -> this.runner)
                .withBean(AccountDeletionRunApi.class, () -> this.runs)
                .withBean(DeletionTombstoneApi.class, () -> this.tombstones)
                .withPropertyValues("bokrilog.sync.worker.drain-timeout=1s",
                        "bokrilog.account-deletion.worker.scheduled=false");
    }

    @Test
    void api_역할에는_삭제_Worker가_없다() {
        web().withPropertyValues("bokrilog.role=api").run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(AccountErasureWorker.class).doesNotHaveBean(WorkerDrainTestHook.drainType()));
    }

    @Test
    void worker_역할의_실제_drain은_삭제_Worker를_함께_묶는다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Lease lease = new Lease(UUID.randomUUID(), 1);
        when(this.runs.claim(any())).thenReturn(Optional.of(lease)).thenReturn(Optional.empty());
        when(this.runner.execute(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return AccountErasureRun.Result.OPERATIONAL_DATA_ERASED;
        });
        web().withPropertyValues("bokrilog.role=worker").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AccountErasureWorker.class)
                    .hasSingleBean(WorkerDrainTestHook.drainType());
            AccountErasureWorker worker = context.getBean(AccountErasureWorker.class);
            assertThat(worker.claimsAllowed()).isTrue();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<AccountErasureRun.Result> running = executor.submit(worker::runOnce);
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(WorkerDrainTestHook.combinedInFlight(context)).isEqualTo(1);

                WorkerDrainTestHook.stopAll(context);
                assertThat(worker.claimsAllowed()).isFalse();
                assertThat(worker.runOnce()).isNull();

                release.countDown();
                assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(AccountErasureRun.Result.OPERATIONAL_DATA_ERASED);
            }
            assertThat(WorkerDrainTestHook.combinedInFlight(context)).isZero();
        });
    }

    @Test
    void 생성자에서_production이면_집지_않는다() {
        when(this.tombstones.keyAvailable()).thenReturn(true);
        AccountErasureWorker production = new AccountErasureWorker(this.runner, this.runs, this.tombstones,
                BokrilogRoleConfig.PRODUCTION, false);
        assertThat(production.claimsAllowed()).isFalse();
        assertThat(production.runOnce()).isNull();
        verify(this.runs, times(0)).claim(any());
    }

    /**
     * tombstone 키가 없는 프로세스는 집지 않는다(Epic 5 회고 AI-3) — 개발·CI compose가 키를 넘기지 않으면 그 스택의 삭제는 진행 중으로
     * 남는다. 키를 공급하게 바꾼 뒤에도 키 없는 구성의 이 동작은 그대로여야 한다.
     */
    @Test
    void tombstone_키가_없으면_집지_않는다() {
        when(this.tombstones.keyAvailable()).thenReturn(false);
        AccountErasureWorker keyless = new AccountErasureWorker(this.runner, this.runs, this.tombstones, "development",
                false);
        assertThat(keyless.claimsAllowed()).isFalse();
        assertThat(keyless.runOnce()).isNull();
        verify(this.runs, times(0)).claim(any());
    }

    @Test
    void 동시_실행은_상한까지만_집는다() throws Exception {
        when(this.tombstones.keyAvailable()).thenReturn(true);
        when(this.runs.claim(any())).thenAnswer(invocation -> Optional.of(new Lease(UUID.randomUUID(), 1)));
        CountDownLatch entered = new CountDownLatch(AccountErasureWorker.MAX_IN_FLIGHT);
        CountDownLatch release = new CountDownLatch(1);
        when(this.runner.execute(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return AccountErasureRun.Result.OPERATIONAL_DATA_ERASED;
        });
        AccountErasureWorker worker = new AccountErasureWorker(this.runner, this.runs, this.tombstones, "development",
                false);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < AccountErasureWorker.MAX_IN_FLIGHT; index++) {
                executor.submit(worker::runOnce);
            }
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.inFlight()).isEqualTo(AccountErasureWorker.MAX_IN_FLIGHT);
            // 상한에 닿았다: 셋째 집기는 실행 상태 API를 부르지도 않는다.
            assertThat(worker.runOnce()).isNull();
            verify(this.runs, times(AccountErasureWorker.MAX_IN_FLIGHT)).claim(any());
            release.countDown();
        }
        assertThat(worker.inFlight()).isZero();
    }
}
