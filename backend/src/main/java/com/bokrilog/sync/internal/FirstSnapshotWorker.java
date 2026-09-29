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
    /**
     * The pause between two claims of one kind, in milliseconds: one second, unless configured. The durable
     * rows are the authority either way; a shorter pause only claims sooner (the plan sync tests use it).
     */
    static final String DISPATCH_DELAY="${bokrilog.sync.worker.dispatch-delay-ms:1000}";
    /** The pause between two lease renewals, in milliseconds: five seconds (a lease lasts thirty), unless configured. */
    static final String HEARTBEAT_DELAY="${bokrilog.sync.worker.heartbeat-delay-ms:5000}";
    private final SyncJobService service;
    private final SourceConnectionApi sources;
    private final PlanPublicationService publications;
    private final Map<java.util.UUID,SyncJobRepository.Lease> inFlight=new ConcurrentHashMap<>();
    private final Map<java.util.UUID,InitialCollectionRepository.Lease> initialInFlight=new ConcurrentHashMap<>();
    private final Map<java.util.UUID,CashflowConversionRepository.Lease> conversionInFlight=new ConcurrentHashMap<>();
    private final Map<java.util.UUID,PlanPublicationRepository.Lease> publicationInFlight=new ConcurrentHashMap<>();
    private final Map<java.util.UUID,PlanSyncRepository.Lease> syncInFlight=new ConcurrentHashMap<>();
    private final PlanSyncService syncs;
    FirstSnapshotWorker(SyncJobService service,SourceConnectionApi sources,PlanPublicationService publications,
            PlanSyncService syncs) {
        this.service=service; this.sources=sources; this.publications=publications; this.syncs=syncs;
    }
    @Scheduled(fixedDelayString=DISPATCH_DELAY)
    void dispatch() {
        service.claim().ifPresent(lease -> {
            inFlight.put(lease.jobId(),lease);
            Thread.ofVirtual().name("first-snapshot-"+lease.jobId()).start(() -> run(lease));
        });
    }
    /**
     * Renews every in-flight lease. Each renewal is isolated: one that throws (a database hiccup, a
     * row gone mid-renewal) must not skip the renewals after it, or healthy attempts of another kind
     * would lose their lease for a failure that was not theirs. A lease whose renewal threw is kept —
     * the next tick tries again, and the lease expiry stays the authority.
     */
    @Scheduled(fixedDelayString=HEARTBEAT_DELAY)
    void heartbeat() {
        renew(inFlight,service::heartbeat,SyncJobRepository.Lease::jobId);
        renew(initialInFlight,service::heartbeatInitial,InitialCollectionRepository.Lease::jobId);
        renew(conversionInFlight,service::heartbeatConversion,CashflowConversionRepository.Lease::jobId);
        renew(publicationInFlight,publications::heartbeat,PlanPublicationRepository.Lease::jobId);
        // A plan sync's renewal also renews its running stage rows (Story 2.11).
        renew(syncInFlight,syncs::heartbeat,PlanSyncRepository.Lease::jobId);
    }
    static <L> void renew(Map<java.util.UUID,L> leases,java.util.function.Predicate<L> renewal,
            java.util.function.Function<L,java.util.UUID> id) {
        for (L lease: leases.values()) {
            try {
                if (!renewal.test(lease)) leases.remove(id.apply(lease),lease);
            } catch (RuntimeException failure) {
                // Isolated on purpose; see heartbeat(). Nothing about the failure is logged here.
            }
        }
    }
    /** One conversion claim per tick. Provider calls run on the virtual thread without a transaction. */
    @Scheduled(fixedDelayString=DISPATCH_DELAY)
    void dispatchCashflowConversion() {
        service.claimConversion().ifPresent(lease->{
            conversionInFlight.put(lease.jobId(),lease);
            Thread.ofVirtual().name("cashflow-conversion-"+lease.jobId()).start(()->{
                try {
                    service.runConversion(lease,()->lease.equals(conversionInFlight.get(lease.jobId())));
                } finally {
                    conversionInFlight.remove(lease.jobId(),lease);
                }
            });
        });
    }
    /**
     * One first-publication claim per tick (Story 2.7). The pure calculation runs on the virtual thread
     * between the input read and the publishing transaction; no transaction spans it.
     */
    @Scheduled(fixedDelayString=DISPATCH_DELAY)
    void dispatchPlanPublication() {
        publications.claim().ifPresent(lease->{
            publicationInFlight.put(lease.jobId(),lease);
            Thread.ofVirtual().name("plan-publication-"+lease.jobId()).start(()->{
                try {
                    publications.run(lease,()->lease.equals(publicationInFlight.get(lease.jobId())));
                } finally {
                    publicationInFlight.remove(lease.jobId(),lease);
                }
            });
        });
    }
    /**
     * The incremental plan sync (Story 2.11). Expired leases end first, as interrupted: a lost job is never
     * continued. Then one claim per tick, and never more than {@link PlanSyncService#MAX_CONCURRENT} running
     * here at once. Provider calls and the pure calculation run on the virtual thread without a transaction.
     */
    @Scheduled(fixedDelayString=DISPATCH_DELAY)
    void dispatchPlanSync() {
        syncs.expireLost();
        if (syncInFlight.size()>=PlanSyncService.MAX_CONCURRENT) return;
        syncs.claim().ifPresent(lease->{
            syncInFlight.put(lease.jobId(),lease);
            Thread.ofVirtual().name("plan-sync-"+lease.jobId()).start(()->{
                try {
                    syncs.run(lease,()->lease.equals(syncInFlight.get(lease.jobId())));
                } finally {
                    syncInFlight.remove(lease.jobId(),lease);
                }
            });
        });
    }
    @Scheduled(fixedDelayString=DISPATCH_DELAY)
    void dispatchInitialInputs() {
        service.claimInitial().ifPresent(lease->{
            initialInFlight.put(lease.jobId(),lease);
            Thread.ofVirtual().name("initial-inputs-"+lease.jobId()).start(()->runInitial(lease));
        });
    }
    @Scheduled(fixedDelayString=DISPATCH_DELAY)
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
