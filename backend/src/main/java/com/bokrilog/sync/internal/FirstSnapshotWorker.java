package com.bokrilog.sync.internal;

import com.bokrilog.source.SourceConnectionApi;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable jobs. A virtual thread performs provider I/O without a database transaction. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FirstSnapshotWorker {
    private final SyncJobService service;
    private final SourceConnectionApi sources;
    private final Map<java.util.UUID,SyncJobRepository.Lease> inFlight=new ConcurrentHashMap<>();
    FirstSnapshotWorker(SyncJobService service,SourceConnectionApi sources) {
        this.service=service; this.sources=sources;
    }
    @Scheduled(fixedDelay=1000)
    void dispatch() {
        service.claim().ifPresent(lease -> {
            inFlight.put(lease.jobId(),lease);
            Thread.ofVirtual().name("first-snapshot-"+lease.jobId()).start(() -> run(lease));
        });
    }
    @Scheduled(fixedDelay=5000)
    void heartbeat() {
        for (var lease: inFlight.values()) {
            if (!service.heartbeat(lease)) inFlight.remove(lease.jobId(),lease);
        }
    }
    void run(SyncJobRepository.Lease lease) {
        try {
            if (!lease.equals(inFlight.get(lease.jobId()))) return;
            var outcome=sources.readEquity(lease.userId(),lease.connectionId());
            if (!lease.equals(inFlight.get(lease.jobId()))) return;
            if (outcome instanceof SourceConnectionApi.EquityResult.Rejected rejected) {
                service.fail(lease,rejected.reason().name());
            } else {
                var value=((SourceConnectionApi.EquityResult.Observed)outcome).value();
                service.publish(lease,value);
            }
        } catch (RuntimeException failure) {
            service.fail(lease,"UNAVAILABLE");
        } finally {
            inFlight.remove(lease.jobId(),lease);
        }
    }
}
