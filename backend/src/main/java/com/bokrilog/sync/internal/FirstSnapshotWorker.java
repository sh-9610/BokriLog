package com.bokrilog.sync.internal;

import com.bokrilog.source.SourceConnectionApi;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final Map<java.util.UUID,InitialCollectionRepository.Lease> initialInFlight=new ConcurrentHashMap<>();
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
        for (var lease: initialInFlight.values()) {
            if (!service.heartbeatInitial(lease)) initialInFlight.remove(lease.jobId(),lease);
        }
    }
    @Scheduled(fixedDelay=1000)
    void dispatchInitialInputs() {
        service.claimInitial().ifPresent(lease->{
            initialInFlight.put(lease.jobId(),lease);
            Thread.ofVirtual().name("initial-inputs-"+lease.jobId()).start(()->runInitial(lease));
        });
    }
    @Scheduled(fixedDelay=1000)
    void dispatchCashflowClassification() {
        service.claimClassification().ifPresent(lease ->
                Thread.ofVirtual().name("cashflow-classification-"+lease.jobId())
                        .start(() -> service.processClassification(lease)));
    }
    void runInitial(InitialCollectionRepository.Lease lease) {
        AtomicBoolean needsReview=new AtomicBoolean(false);
        AtomicReference<String> stage=new AtomicReference<>("PROVIDER_CALL_FAILED");
        try {
            if (!lease.equals(initialInFlight.get(lease.jobId()))) return;
            var result=sources.collectInitialInputs(lease.userId(),lease.connectionId(),lease.t0(),lease.tc(),
                    tc->{stage.set("SCOPE_PERSIST_FAILED");
                        if(!service.fixCollectionScope(lease,tc)) throw new IllegalStateException("collection lease lost");
                        stage.set("PROVIDER_CALL_FAILED");},
                    page->{
                        if (!lease.equals(initialInFlight.get(lease.jobId()))) throw new IllegalStateException("collection lease lost");
                        stage.set("PAGE_PERSIST_FAILED");
                        needsReview.compareAndSet(false,service.storeCollectionPage(lease,page));
                        stage.set("PROVIDER_CALL_FAILED");
                    });
            if (!lease.equals(initialInFlight.get(lease.jobId()))) return;
            if (result instanceof SourceConnectionApi.CollectionResult.Rejected rejected) {
                if (rejected.reason()==com.bokrilog.source.SourceRejectionReason.RESPONSE_FORMAT)
                    service.reviewCollection(lease,"MALFORMED_PROVIDER_RESPONSE");
                else service.failCollection(lease,"PROVIDER_UNAVAILABLE");
            } else {
                var value=((SourceConnectionApi.CollectionResult.Collected)result).value();
                needsReview.compareAndSet(false,value.reviewRequired());
                stage.set("PUBLISH_FAILED");
                service.finishCollection(lease,value,needsReview.get());
            }
        } catch (RuntimeException failure) {
            service.failCollection(lease,stage.get());
        } finally {
            initialInFlight.remove(lease.jobId(),lease);
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
