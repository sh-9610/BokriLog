package com.bokrilog.sync.internal;

import com.bokrilog.source.SourceConnectionApi;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable jobs. A virtual thread performs provider I/O without a database transaction. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FirstSnapshotWorker {
    /**
     * The pause between two claims of each dispatch below. Its one default is {@code application.yml}'s
     * {@code 1s}; the Docker-backed tests shorten it so a pipeline test does not wait a second per stage. The
     * heartbeat stays fixed at five seconds: shortening it would also shorten the window in which a late worker
     * reaches the database fence instead of the in-memory guard.
     *
     * <p>The constructor refuses a value that is not positive. Spring rejects a negative delay but accepts zero,
     * and zero makes the six claims spin on the one scheduler thread the heartbeat also runs on; the environment
     * can still set the value through relaxed binding, so the guard is here rather than in the file.
     */
    private static final String DISPATCH_INTERVAL="${bokrilog.sync.worker.dispatch-interval}";
    private final SyncJobService service;
    private final SourceConnectionApi sources;
    private final PlanPublicationService publications;
    private final Map<java.util.UUID,SyncJobRepository.Lease> inFlight=new ConcurrentHashMap<>();
    private final Map<java.util.UUID,InitialCollectionRepository.Lease> initialInFlight=new ConcurrentHashMap<>();
    private final Map<java.util.UUID,CashflowConversionRepository.Lease> conversionInFlight=new ConcurrentHashMap<>();
    private final Map<java.util.UUID,PlanPublicationRepository.Lease> publicationInFlight=new ConcurrentHashMap<>();
    private final Map<java.util.UUID,PlanSyncRepository.Lease> planSyncInFlight=new ConcurrentHashMap<>();
    private final PlanSyncService planSyncs;
    FirstSnapshotWorker(SyncJobService service,SourceConnectionApi sources,PlanPublicationService publications,
            PlanSyncService planSyncs,@Value(DISPATCH_INTERVAL) java.time.Duration dispatchInterval) {
        if (dispatchInterval.isZero()||dispatchInterval.isNegative())
            throw new IllegalStateException("bokrilog.sync.worker.dispatch-interval must be positive");
        this.service=service; this.sources=sources; this.publications=publications; this.planSyncs=planSyncs;
    }
    @Scheduled(fixedDelayString=DISPATCH_INTERVAL)
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
    @Scheduled(fixedDelay=5000)
    void heartbeat() {
        renew(inFlight,service::heartbeat,SyncJobRepository.Lease::jobId);
        renew(initialInFlight,service::heartbeatInitial,InitialCollectionRepository.Lease::jobId);
        renew(conversionInFlight,service::heartbeatConversion,CashflowConversionRepository.Lease::jobId);
        renew(publicationInFlight,publications::heartbeat,PlanPublicationRepository.Lease::jobId);
        renew(planSyncInFlight,planSyncs::heartbeat,PlanSyncRepository.Lease::jobId);
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
    @Scheduled(fixedDelayString=DISPATCH_INTERVAL)
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
    @Scheduled(fixedDelayString=DISPATCH_INTERVAL)
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
     * One incremental sync claim per tick (Story 2.11). The attempt runs its stages on the virtual thread; provider
     * calls and the pure calculation run between short transactions, and its heartbeat also renews the leases of
     * the stage rows it owns.
     */
    @Scheduled(fixedDelayString=DISPATCH_INTERVAL)
    void dispatchPlanSync() {
        planSyncs.claim().ifPresent(lease->{
            planSyncInFlight.put(lease.jobId(),lease);
            Thread.ofVirtual().name("plan-sync-"+lease.jobId()).start(()->{
                try {
                    planSyncs.run(lease,()->lease.equals(planSyncInFlight.get(lease.jobId())));
                } finally {
                    planSyncInFlight.remove(lease.jobId(),lease);
                }
            });
        });
    }
    @Scheduled(fixedDelayString=DISPATCH_INTERVAL)
    void dispatchInitialInputs() {
        service.claimInitial().ifPresent(lease->{
            initialInFlight.put(lease.jobId(),lease);
            Thread.ofVirtual().name("initial-inputs-"+lease.jobId()).start(()->runInitial(lease));
        });
    }
    @Scheduled(fixedDelayString=DISPATCH_INTERVAL)
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
                    },
                    // The call budget's waits and page retries last only while this worker holds the job (Story 2.12).
                    ()->lease.equals(initialInFlight.get(lease.jobId())));
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
        } catch (StrongSignals.Unraised unraised) {
            // 강한 신호를 기록하지 못했다(Story 3.3): 수집을 끝내지 않는다 — lease가 만료되면 다시 claim된다. 상한 세대면
            // 검토 필요로 맺는다.
            service.signalUnrecorded(lease);
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
        } catch (StrongSignals.Unraised unraised) {
            // 강한 신호를 기록하지 못했다(Story 3.3): 작업을 끝내지 않는다 — lease 규칙에 맡기고, 상한 세대면 실패로 맺는다.
            service.signalUnrecorded(lease);
        } catch (RuntimeException failure) {
            service.fail(lease,"UNAVAILABLE");
        } finally {
            inFlight.remove(lease.jobId(),lease);
        }
    }
}
