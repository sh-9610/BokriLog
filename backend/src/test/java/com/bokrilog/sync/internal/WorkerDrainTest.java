package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.WorkerExit;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Story 3.8: Worker drain.
 *
 * <p>종료 신호 뒤 새 claim을 멈추고, heartbeat를 계속 밀며, 진행 중이 0이 되면 종료 코드 0이다. drain-timeout이 지나면 {@code
 * worker.drain.incomplete}와 0이 아닌 종료 코드다. 데이터베이스 없이 작업자의 협력자를 대역으로 둔다.
 */
@ExtendWith(OutputCaptureExtension.class)
class WorkerDrainTest {

    private final SyncJobService service = mock(SyncJobService.class);

    private final SourceConnectionApi sources = mock(SourceConnectionApi.class);

    private final PlanPublicationService publications = mock(PlanPublicationService.class);

    private final PlanSyncService planSyncs = mock(PlanSyncService.class);

    private FirstSnapshotWorker worker() {
        return new FirstSnapshotWorker(this.service, this.sources, this.publications, this.planSyncs,
                Duration.ofMillis(100));
    }

    @Test
    void claim을_멈추면_여섯_dispatch가_모두_새_lease를_잡지_않는다() {
        FirstSnapshotWorker worker = worker();
        dispatchAll(worker);
        verify(this.service, times(1)).claim();
        verify(this.service, times(1)).claimInitial();
        verify(this.service, times(1)).claimClassification();
        verify(this.service, times(1)).claimConversion();
        verify(this.publications, times(1)).claim();
        verify(this.planSyncs, times(1)).claim();

        worker.stopClaims();
        dispatchAll(worker);
        dispatchAll(worker);
        verify(this.service, times(1)).claim();
        verify(this.service, times(1)).claimInitial();
        verify(this.service, times(1)).claimClassification();
        verify(this.service, times(1)).claimConversion();
        verify(this.publications, times(1)).claim();
        verify(this.planSyncs, times(1)).claim();
        assertThat(worker.inFlight()).isZero();
    }

    /** 작업자가 dispatch하는 여섯 종류. 종류마다 claim·실행·heartbeat 자리가 다르다. */
    enum JobKind {
        FIRST_SNAPSHOT, INITIAL_COLLECTION, CLASSIFICATION, CONVERSION, PUBLICATION, PLAN_SYNC
    }

    /**
     * 여섯 종류 모두: 진행 중인 작업 하나가 latch에 막혀 있는 동안 진행 중은 1이고 drain은 기다린다. heartbeat가 있는 종류는 그동안
     * 그 lease를 민다(판정 작업은 heartbeat가 없다). 막힘이 풀리면 drain이 참으로 끝나고 진행 중은 0이다.
     */
    @ParameterizedTest
    @EnumSource(JobKind.class)
    void 진행_중_작업이_끝날_때까지_heartbeat를_밀며_기다린다(JobKind kind) throws Exception {
        FirstSnapshotWorker worker = worker();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Answer<Object> block = invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        };
        UUID jobId = UUID.randomUUID();
        Runnable dispatch;
        Runnable heartbeatRenewed;
        Runnable claimedOnce;
        switch (kind) {
            case FIRST_SNAPSHOT -> {
                SyncJobRepository.Lease lease = new SyncJobRepository.Lease(jobId, UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), 1);
                when(this.service.claim()).thenReturn(Optional.of(lease)).thenReturn(Optional.empty());
                when(this.service.heartbeat(any(SyncJobRepository.Lease.class))).thenReturn(true);
                when(this.sources.readEquity(any(), any())).thenAnswer(block);
                dispatch = worker::dispatch;
                heartbeatRenewed = () -> verify(this.service, atLeastOnce()).heartbeat(lease);
                claimedOnce = () -> verify(this.service, times(1)).claim();
            }
            case INITIAL_COLLECTION -> {
                InitialCollectionRepository.Lease lease = mock(InitialCollectionRepository.Lease.class);
                when(lease.jobId()).thenReturn(jobId);
                when(this.service.claimInitial()).thenReturn(Optional.of(lease)).thenReturn(Optional.empty());
                when(this.service.heartbeatInitial(any())).thenReturn(true);
                when(this.sources.collectInitialInputs(any(), any(), any(), any(), any(), any(), any()))
                        .thenAnswer(block);
                dispatch = worker::dispatchInitialInputs;
                heartbeatRenewed = () -> verify(this.service, atLeastOnce()).heartbeatInitial(lease);
                claimedOnce = () -> verify(this.service, times(1)).claimInitial();
            }
            case CLASSIFICATION -> {
                CashflowClassificationRepository.Lease lease = mock(CashflowClassificationRepository.Lease.class);
                when(lease.jobId()).thenReturn(jobId);
                when(this.service.claimClassification()).thenReturn(Optional.of(lease)).thenReturn(Optional.empty());
                doAnswer(block).when(this.service).processClassification(lease);
                dispatch = worker::dispatchCashflowClassification;
                heartbeatRenewed = () -> {
                    // 판정 작업에는 heartbeat가 없다. 진행 중으로 세는 것만 본다.
                };
                claimedOnce = () -> verify(this.service, times(1)).claimClassification();
            }
            case CONVERSION -> {
                CashflowConversionRepository.Lease lease = mock(CashflowConversionRepository.Lease.class);
                when(lease.jobId()).thenReturn(jobId);
                when(this.service.claimConversion()).thenReturn(Optional.of(lease)).thenReturn(Optional.empty());
                when(this.service.heartbeatConversion(any())).thenReturn(true);
                doAnswer(block).when(this.service).runConversion(eq(lease), any());
                dispatch = worker::dispatchCashflowConversion;
                heartbeatRenewed = () -> verify(this.service, atLeastOnce()).heartbeatConversion(lease);
                claimedOnce = () -> verify(this.service, times(1)).claimConversion();
            }
            case PUBLICATION -> {
                PlanPublicationRepository.Lease lease = mock(PlanPublicationRepository.Lease.class);
                when(lease.jobId()).thenReturn(jobId);
                when(this.publications.claim()).thenReturn(Optional.of(lease)).thenReturn(Optional.empty());
                when(this.publications.heartbeat(any())).thenReturn(true);
                doAnswer(block).when(this.publications).run(eq(lease), any());
                dispatch = worker::dispatchPlanPublication;
                heartbeatRenewed = () -> verify(this.publications, atLeastOnce()).heartbeat(lease);
                claimedOnce = () -> verify(this.publications, times(1)).claim();
            }
            case PLAN_SYNC -> {
                PlanSyncRepository.Lease lease = mock(PlanSyncRepository.Lease.class);
                when(lease.jobId()).thenReturn(jobId);
                when(this.planSyncs.claim()).thenReturn(Optional.of(lease)).thenReturn(Optional.empty());
                when(this.planSyncs.heartbeat(any())).thenReturn(true);
                doAnswer(block).when(this.planSyncs).run(eq(lease), any());
                dispatch = worker::dispatchPlanSync;
                heartbeatRenewed = () -> verify(this.planSyncs, atLeastOnce()).heartbeat(lease);
                claimedOnce = () -> verify(this.planSyncs, times(1)).claim();
            }
            default -> throw new IllegalStateException(kind.name());
        }
        dispatch.run();
        assertThat(entered.await(5, TimeUnit.SECONDS)).as(kind.name()).isTrue();
        assertThat(worker.inFlight()).as(kind.name()).isEqualTo(1);

        AtomicBoolean drained = new AtomicBoolean();
        Thread draining = Thread.ofVirtual().start(() ->
                drained.set(WorkerDrain.drain(worker, Duration.ofSeconds(10), Duration.ofMillis(50),
                        Duration.ofMillis(10))));
        Thread.sleep(300);
        // 아직 기다리는 중이고, 그동안 진행 중 lease를 민다. 새 claim은 없다.
        assertThat(draining.isAlive()).as(kind.name()).isTrue();
        assertThat(worker.inFlight()).as(kind.name()).isEqualTo(1);
        heartbeatRenewed.run();
        dispatch.run();
        claimedOnce.run();

        release.countDown();
        draining.join(5_000);
        assertThat(drained).as(kind.name()).isTrue();
        assertThat(worker.inFlight()).as(kind.name()).isZero();
    }

    @Test
    void 시간_안에_비지_않으면_incomplete를_남기고_거짓이다(CapturedOutput output) {
        FakeTarget target = new FakeTarget(2);
        long started = System.nanoTime();
        boolean drained = WorkerDrain.drain(target, Duration.ofMillis(300), Duration.ofMillis(50), Duration.ofMillis(10));
        assertThat(drained).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        assertThat(target.stopped).isTrue();
        assertThat(target.heartbeats.get()).isPositive();
        assertThat(output.getAll()).contains(WorkerDrain.INCOMPLETE_EVENT);
    }

    @Test
    void 진행_중이_없으면_바로_끝나고_heartbeat가_필요_없다() {
        FakeTarget target = new FakeTarget(0);
        assertThat(WorkerDrain.drain(target, Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofMillis(10)))
                .isTrue();
        assertThat(target.stopped).isTrue();
        assertThat(target.heartbeats.get()).isZero();
    }

    @Test
    void 종료_사건이_drain을_돌리고_결과가_종료_코드다() {
        try (GenericApplicationContext context = new GenericApplicationContext();
                GenericApplicationContext other = new GenericApplicationContext()) {
            FakeTarget empty = new FakeTarget(0);
            WorkerDrain drain = new WorkerDrain(empty, Duration.ofSeconds(1), context);
            // 다른 컨텍스트(관리 포트의 자식)의 닫기는 drain을 돌리지 않는다.
            drain.onApplicationEvent(new ContextClosedEvent(other));
            assertThat(empty.stopped).isFalse();
            drain.onApplicationEvent(new ContextClosedEvent(context));
            assertThat(empty.stopped).isTrue();
            assertThat(drain.exitCode()).isEqualTo(WorkerExit.DRAINED).isZero();

            FakeTarget stuck = new FakeTarget(1);
            WorkerDrain incomplete = new WorkerDrain(stuck, Duration.ofMillis(200), context);
            incomplete.onApplicationEvent(new ContextClosedEvent(context));
            assertThat(incomplete.exitCode()).isEqualTo(WorkerExit.DRAIN_INCOMPLETE).isNotZero();
            // 두 번째 사건은 다시 돌지 않는다.
            int heartbeats = stuck.heartbeats.get();
            incomplete.onApplicationEvent(new ContextClosedEvent(context));
            assertThat(stuck.heartbeats.get()).isEqualTo(heartbeats);
        }
    }

    @Test
    void 잠금을_잃으면_claim을_멈추고_한_번만_끝낸다(CapturedOutput output) {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            FakeTarget target = new FakeTarget(0);
            WorkerDrain drain = new WorkerDrain(target, Duration.ofSeconds(1), context);
            AtomicInteger exits = new AtomicInteger();
            AtomicInteger code = new AtomicInteger(-1);
            drain.terminator = value -> {
                exits.incrementAndGet();
                code.set(value);
            };
            drain.lockLost();
            drain.lockLost();
            assertThat(target.stopped).isTrue();
            assertThat(exits).hasValue(1);
            assertThat(code).hasValue(WorkerExit.LOCK_LOST);
            assertThat(drain.exitCode()).isEqualTo(WorkerExit.LOCK_LOST);
            assertThat(output.getAll()).contains(WorkerDrain.LOCK_LOST_EVENT);

            // 닫는 중(drain 시작 뒤)의 연결 닫힘은 잃은 것이 아니다.
            WorkerDrain closing = new WorkerDrain(new FakeTarget(0), Duration.ofSeconds(1), context);
            closing.terminator = value -> exits.incrementAndGet();
            closing.onApplicationEvent(new ContextClosedEvent(context));
            closing.lockLost();
            assertThat(exits).hasValue(1);
            assertThat(closing.exitCode()).isEqualTo(WorkerExit.DRAINED);
        }
    }

    @Test
    void drain_timeout은_양수여야_한다() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            for (Duration timeout : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> new WorkerDrain(new FakeTarget(0), timeout,
                        context)).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    private static void dispatchAll(FirstSnapshotWorker worker) {
        worker.dispatch();
        worker.dispatchInitialInputs();
        worker.dispatchCashflowClassification();
        worker.dispatchCashflowConversion();
        worker.dispatchPlanPublication();
        worker.dispatchPlanSync();
    }

    /** 진행 중 수가 고정된 대역. */
    private static final class FakeTarget implements WorkerDrain.Target {

        private final int inFlight;

        private volatile boolean stopped;

        private final AtomicInteger heartbeats = new AtomicInteger();

        FakeTarget(int inFlight) {
            this.inFlight = inFlight;
        }

        @Override
        public void stopClaims() {
            this.stopped = true;
        }

        @Override
        public int inFlight() {
            return this.inFlight;
        }

        @Override
        public void heartbeat() {
            this.heartbeats.incrementAndGet();
        }
    }
}
