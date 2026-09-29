package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bokrilog.source.SourceConnectionApi;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The plan sync dispatcher (Story 2.11) against mocks: never more than {@link PlanSyncService#MAX_CONCURRENT}
 * attempts in flight, the lost attempts ended on every tick — also one where the cap stops the claim — and a
 * slot freed as soon as a renewal says the lease is no longer this worker's (the run limit included).
 */
class FirstSnapshotWorkerTest {
    private final PlanSyncService syncs = mock(PlanSyncService.class);
    private final FirstSnapshotWorker worker = new FirstSnapshotWorker(mock(SyncJobService.class),
            mock(SourceConnectionApi.class), mock(PlanPublicationService.class), syncs);

    private static PlanSyncRepository.Lease lease() {
        return new PlanSyncRepository.Lease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now());
    }

    @Test
    void theDispatcherRunsAtMostTwoSyncsAndStillEndsLostOnesAtTheCap() throws Exception {
        var release = new CountDownLatch(1);
        var running = new AtomicInteger();
        when(syncs.claim()).thenAnswer(invocation -> Optional.of(lease()));
        doAnswer(invocation -> {
            running.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
            running.decrementAndGet();
            return null;
        }).when(syncs).run(any(), any());
        try {
            for (int tick = 0; tick < 5; tick++) worker.dispatchPlanSync();
            verify(syncs, times(5)).expireLost();
            verify(syncs, times(PlanSyncService.MAX_CONCURRENT)).claim();
            for (int i = 0; i < 200 && running.get() < PlanSyncService.MAX_CONCURRENT; i++) Thread.sleep(10);
            assertThat(running.get()).isEqualTo(PlanSyncService.MAX_CONCURRENT);

            // A renewal that fails (a lease lost, or held past the run limit) frees the slot at once.
            when(syncs.heartbeat(any())).thenReturn(false);
            worker.heartbeat();
            worker.dispatchPlanSync();
            verify(syncs, times(PlanSyncService.MAX_CONCURRENT + 1)).claim();
        } finally {
            release.countDown();
        }
    }
}
