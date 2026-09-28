package com.bokrilog.sync.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Lease renewals are isolated: one renewal that throws never skips the others. */
class FirstSnapshotWorkerHeartbeatTest {
    private record Lease(UUID jobId, String behaviour) {}

    @Test
    void aThrowingRenewalNeitherSkipsTheOthersNorDropsItsOwnLease() {
        Map<UUID, Lease> leases = new ConcurrentHashMap<>();
        Lease failing = new Lease(UUID.randomUUID(), "throw");
        Lease lost = new Lease(UUID.randomUUID(), "lost");
        Lease healthy = new Lease(UUID.randomUUID(), "renewed");
        leases.put(failing.jobId(), failing);
        leases.put(lost.jobId(), lost);
        leases.put(healthy.jobId(), healthy);
        AtomicInteger attempts = new AtomicInteger();

        FirstSnapshotWorker.renew(leases, lease -> {
            attempts.incrementAndGet();
            if (lease.behaviour().equals("throw")) throw new IllegalStateException("database unavailable");
            return lease.behaviour().equals("renewed");
        }, Lease::jobId);

        assertThat(attempts).hasValue(3);
        assertThat(leases).containsKeys(failing.jobId(), healthy.jobId()).doesNotContainKey(lost.jobId());
    }
}
