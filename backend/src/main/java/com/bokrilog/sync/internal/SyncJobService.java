package com.bokrilog.sync.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.sync.InitialInputsPlanPort;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.source.SourceConnectionApi.CollectionObservation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** sync owns each short transaction; no transaction spans a provider call. */
@Service
class SyncJobService implements SyncJobApi {
    static final String SYNC_COMMAND="first-snapshot";
    static final String CONNECTION_COMMAND="bybit-connection-create";
    static final String INITIAL_INPUTS_COMMAND="initial-source-event-collection";
    private final SyncJobRepository jobs;
    private final InitialCollectionRepository initialJobs;
    private final CashflowClassificationRepository classifications;
    private final SourceConnectionApi sources;
    private final PortfolioMembershipApi portfolios;
    private final AccountAccessApi accounts;
    private final OperationalRestrictionApi restrictions;
    private final InitialInputsPlanPort plans;
    private final TransactionTemplate transactions;

    SyncJobService(SyncJobRepository jobs, InitialCollectionRepository initialJobs,
            CashflowClassificationRepository classifications, SourceConnectionApi sources,
            PortfolioMembershipApi portfolios, AccountAccessApi accounts,
            OperationalRestrictionApi restrictions, InitialInputsPlanPort plans, PlatformTransactionManager manager) {
        this.jobs=jobs; this.initialJobs=initialJobs; this.classifications=classifications;
        this.sources=sources; this.portfolios=portfolios;
        this.accounts=accounts; this.restrictions=restrictions; this.plans=plans;
        this.transactions=new TransactionTemplate(manager);
    }
    boolean eligible(UUID userId) {
        return accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)==AccessDecision.ALLOWED
            && !restrictions.isRestricted(SYNC_COMMAND)
            && !restrictions.isRestricted(CONNECTION_COMMAND);
    }
    @Override
    public Optional<Job> admit(@Nullable UUID userId, String key) {
        if (userId==null || key==null || key.isBlank() || key.length()>128) return Optional.empty();
        try {
            return transactions.execute(status -> {
                if (!eligible(userId)) return Optional.empty();
                var source=sources.snapshotConnection(userId);
                if (source.isEmpty()) return Optional.empty();
                var connection=source.get();
                Optional<Job> same=jobs.byKey(userId,connection.connectionId(),key);
                if (same.isPresent()) return same;
                Optional<Job> active=jobs.active(userId,connection.connectionId());
                if (active.isPresent()) return active;
                if (portfolios.officialValue(userId).isPresent()) return Optional.empty();
                UUID id=UUID.randomUUID();
                jobs.insert(id,userId,connection.connectionId(),connection.providerAccountId(),key);
                return jobs.byId(userId,id);
            });
        } catch (DuplicateKeyException racing) {
            var source=sources.snapshotConnection(userId);
            return source.flatMap(c -> jobs.byKey(userId,c.connectionId(),key)
                    .or(() -> jobs.active(userId,c.connectionId())));
        }
    }
    @Override
    public Optional<Job> job(@Nullable UUID userId, UUID jobId) {
        if (userId==null || !eligible(userId) || sources.snapshotConnection(userId).isEmpty())
            return Optional.empty();
        return jobs.byId(userId,jobId);
    }
    @Override
    public Optional<State> state(@Nullable UUID userId) {
        if (userId==null || accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)
                !=AccessDecision.ALLOWED) return Optional.empty();
        if (sources.snapshotConnection(userId).isEmpty())
            return Optional.of(new State(null,null,false,false));
        if (restrictions.isRestricted(SYNC_COMMAND)
                || restrictions.isRestricted(CONNECTION_COMMAND))
            return Optional.of(new State(null,null,true,true));
        return Optional.of(new State(jobs.latest(userId).orElse(null),
                portfolios.officialValue(userId).orElse(null),true,false));
    }
    @Override
    public Optional<CollectionJob> admitInitialInputs(@Nullable UUID userId, UUID intentId, String key) {
        if (userId==null || !validCollectionKey(key)) return Optional.empty();
        try {
            return transactions.execute(status -> {
                var intent=plans.eligibleIntent(userId,intentId).orElse(null);
                if (intent==null || !initialEligible(userId)) return Optional.empty();
                var connection=sources.snapshotConnection(userId).orElse(null);
                if (connection==null || !connection.connectionId().equals(intent.connectionId())
                        || !connection.providerAccountId().equals(intent.providerAccountId())) return Optional.empty();
                Optional<CollectionJob> same=initialJobs.byKey(userId,intentId,key);
                if (same.isPresent()) return same;
                Optional<CollectionJob> active=initialJobs.active(userId,intentId);
                if (active.isPresent()) return active;
                UUID id=UUID.randomUUID();
                if (initialJobs.insert(id,userId,intent.portfolioId(),intent.intentId(),intent.connectionId(),
                        intent.providerAccountId(),key,intent.t0())!=1)
                    return initialJobs.byKey(userId,intentId,key).or(()->initialJobs.active(userId,intentId));
                return initialJobs.byId(userId,id);
            });
        } catch (DuplicateKeyException racing) {
            return plans.eligibleIntent(userId,intentId).flatMap(i->initialJobs.byKey(userId,intentId,key)
                    .or(()->initialJobs.active(userId,intentId)));
        }
    }
    @Override
    public Optional<CollectionJob> initialCollectionJob(@Nullable UUID userId, UUID jobId) {
        if (userId==null || accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)!=AccessDecision.ALLOWED
                || !initialEligible(userId)) return Optional.empty();
        return initialJobs.byId(userId,jobId);
    }
    @Override
    public InitialCollectionState initialCollectionState(@Nullable UUID userId,UUID intentId) {
        if (userId==null || accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)!=AccessDecision.ALLOWED
                || !initialEligible(userId)) return new InitialCollectionState(false,null);
        var intent=plans.eligibleIntent(userId,intentId).orElse(null);
        var connection=sources.snapshotConnection(userId).orElse(null);
        if (intent==null || connection==null || !connection.connectionId().equals(intent.connectionId())
                || !connection.providerAccountId().equals(intent.providerAccountId()))
            return new InitialCollectionState(false,null);
        return new InitialCollectionState(true,initialJobs.latest(userId,intentId).orElse(null));
    }
    @Override
    public Optional<ClassificationJob> admitCashflowClassification(@Nullable UUID userId,UUID intentId,String key) {
        if (userId==null||!validCollectionKey(key)) return Optional.empty();
        return transactions.execute(status->{
            var intent=plans.eligibleIntent(userId,intentId).orElse(null);
            if(intent==null||!initialEligible(userId)) return Optional.empty();
            var connection=sources.snapshotConnection(userId).orElse(null);
            if(connection==null||!connection.connectionId().equals(intent.connectionId())
                    ||!connection.providerAccountId().equals(intent.providerAccountId())) return Optional.empty();
            return classifications.admit(userId,intentId,intent.portfolioId(),intent.connectionId(),
                    intent.providerAccountId(),key);
        });
    }
    @Override
    public ClassificationState cashflowClassificationState(@Nullable UUID userId,UUID intentId) {
        if(userId==null||accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)!=AccessDecision.ALLOWED
                ||!initialEligible(userId)) return new ClassificationState(false,null);
        var intent=plans.eligibleIntent(userId,intentId).orElse(null);
        var connection=sources.snapshotConnection(userId).orElse(null);
        if(intent==null||connection==null||!connection.connectionId().equals(intent.connectionId())
                ||!connection.providerAccountId().equals(intent.providerAccountId())) return new ClassificationState(false,null);
        return new ClassificationState(true,classifications.latest(userId,intentId).orElse(null));
    }
    @Override
    public Optional<SourceSnapshot> sourceSnapshot(@Nullable UUID userId, UUID sourceSnapshotId) {
        return userId==null ? Optional.empty() : jobs.sourceSnapshot(userId,sourceSnapshotId);
    }
    @Override
    public void eraseSyncData(@Nullable UUID userId) {
        if (userId!=null) transactions.executeWithoutResult(status -> {
            classifications.erase(userId);
            initialJobs.erase(userId);
            jobs.erase(userId);
        });
    }
    Optional<CashflowClassificationRepository.Lease> claimClassification() {
        return transactions.execute(status->classifications.claim().flatMap(lease->{
            var intent=plans.eligibleIntent(lease.userId(),lease.intentId()).orElse(null);
            var connection=sources.snapshotConnection(lease.userId()).orElse(null);
            if(!initialEligible(lease.userId())||intent==null||connection==null
                    ||!intent.portfolioId().equals(lease.portfolioId())
                    ||!intent.connectionId().equals(lease.connectionId())
                    ||!intent.providerAccountId().equals(lease.providerAccountId())
                    ||!connection.connectionId().equals(lease.connectionId())
                    ||!connection.providerAccountId().equals(lease.providerAccountId())) {
                classifications.finish(lease,"CANCELLED","NOT_PERMITTED");
                return Optional.empty();
            }
            return Optional.of(lease);
        }));
    }
    void processClassification(CashflowClassificationRepository.Lease lease) {
        try {
            transactions.executeWithoutResult(status->{
                if(!classifications.lockOwned(lease)) return;
                var intent=plans.eligibleIntent(lease.userId(),lease.intentId()).orElse(null);
                var connection=sources.snapshotConnection(lease.userId()).orElse(null);
                if(!initialEligible(lease.userId())||intent==null||connection==null
                        ||!intent.portfolioId().equals(lease.portfolioId())
                        ||!intent.connectionId().equals(lease.connectionId())
                        ||!intent.providerAccountId().equals(lease.providerAccountId())
                        ||!connection.connectionId().equals(lease.connectionId())
                        ||!connection.providerAccountId().equals(lease.providerAccountId())) {
                    classifications.finish(lease,"CANCELLED","NOT_PERMITTED"); return;
                }
                var inputHash=classifications.inputHash(lease);
                if(inputHash.isEmpty()) { classifications.finish(lease,"NEEDS_REVIEW","INPUT_COMPLETENESS_INVALID"); return; }
                var inputs=classifications.inputs(lease);
                var decisions=inputs.stream().map(input->CashflowDecisionTable.decide(input.accountType(),input.eventType(),
                        input.currency(),input.cashFlow())).toList();
                classifications.publish(lease,inputHash.get(),inputs,decisions,()->classificationStillPermitted(lease));
            });
        } catch (CashflowClassificationRepository.PublicationBlocked blocked) {
            transactions.executeWithoutResult(status -> classifications.finish(lease,"CANCELLED","NOT_PERMITTED"));
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status ->
                    classifications.finish(lease,"NEEDS_REVIEW","CLASSIFICATION_FAILED"));
        }
    }
    private boolean classificationStillPermitted(CashflowClassificationRepository.Lease lease) {
        var intent=plans.eligibleIntent(lease.userId(),lease.intentId()).orElse(null);
        var connection=sources.snapshotConnection(lease.userId()).orElse(null);
        return initialEligible(lease.userId())&&intent!=null&&connection!=null
                &&intent.portfolioId().equals(lease.portfolioId())
                &&intent.connectionId().equals(lease.connectionId())
                &&intent.providerAccountId().equals(lease.providerAccountId())
                &&connection.connectionId().equals(lease.connectionId())
                &&connection.providerAccountId().equals(lease.providerAccountId());
    }
    Optional<InitialCollectionRepository.Lease> claimInitial() {
        return transactions.execute(status -> initialJobs.claim().flatMap(lease -> {
            var intent=plans.eligibleIntent(lease.userId(),lease.intentId()).orElse(null);
            var connection=sources.snapshotConnection(lease.userId()).orElse(null);
            if (!initialEligible(lease.userId())||intent==null||connection==null
                    ||!intent.portfolioId().equals(lease.portfolioId())
                    ||!intent.connectionId().equals(lease.connectionId())
                    ||!intent.providerAccountId().equals(lease.providerAccountId())
                    ||!intent.t0().equals(lease.t0())
                    ||!connection.connectionId().equals(lease.connectionId())
                    ||!connection.providerAccountId().equals(lease.providerAccountId())) {
                initialJobs.finish(lease,"CANCELLED","NOT_PERMITTED");
                return Optional.empty();
            }
            return Optional.of(lease);
        }));
    }
    boolean heartbeatInitial(InitialCollectionRepository.Lease lease) {
        return transactions.execute(status->initialJobs.heartbeat(lease)==1);
    }
    boolean fixCollectionScope(InitialCollectionRepository.Lease lease,Instant tc) {
        return transactions.execute(status->initialJobs.lockOwned(lease)&&initialJobs.fixScope(lease,tc));
    }
    boolean storeCollectionPage(InitialCollectionRepository.Lease lease,
            com.bokrilog.source.SourceConnectionApi.CollectionPage page) {
        return transactions.execute(status->{
            if (!initialJobs.lockOwned(lease)||!initialEligible(lease.userId()))
                throw new IllegalStateException("collection lease lost");
            return initialJobs.storePage(lease,page.evidence(),page.events(),page.reviewRequired());
        });
    }
    void finishCollection(InitialCollectionRepository.Lease lease,CollectionObservation input,boolean needsReview) {
        transactions.executeWithoutResult(status->{
            if (!initialJobs.lockOwned(lease)||!initialEligible(lease.userId())) return;
            var intent=plans.eligibleIntent(lease.userId(),lease.intentId()).orElse(null);
            var connection=sources.snapshotConnection(lease.userId()).orElse(null);
            if (intent==null||connection==null||!intent.portfolioId().equals(lease.portfolioId())
                    ||!intent.connectionId().equals(lease.connectionId())
                    ||!intent.providerAccountId().equals(lease.providerAccountId())
                    ||!intent.t0().equals(lease.t0())||!connection.connectionId().equals(lease.connectionId())
                    ||!connection.providerAccountId().equals(lease.providerAccountId())
                    ||(lease.tc()!=null&&!lease.tc().equals(input.tc()))
                    ||!connection.policyVersion().equals(input.policyVersion())) {
                initialJobs.finish(lease,"CANCELLED","NOT_PERMITTED");
                return;
            }
            var stored=initialJobs.complete(lease,input,needsReview);
            initialJobs.finish(lease,stored.status(),stored.failureCode());
        });
    }
    void failCollection(InitialCollectionRepository.Lease lease,String safeCode) {
        transactions.executeWithoutResult(status->{
            if (initialJobs.lockOwned(lease)) initialJobs.finish(lease,"RETRY_REQUIRED",safeCode);
        });
    }
    void reviewCollection(InitialCollectionRepository.Lease lease,String safeCode) {
        transactions.executeWithoutResult(status->{
            if (initialJobs.lockOwned(lease)) initialJobs.finish(lease,"NEEDS_REVIEW",safeCode);
        });
    }
    Optional<SyncJobRepository.Lease> claim() {
        return transactions.execute(status -> {
            Optional<SyncJobRepository.Lease> lease=jobs.claim();
            if (lease.isPresent() && !eligible(lease.get().userId())) {
                jobs.finish(lease.get(),"BLOCKED","NOT_PERMITTED");
                return Optional.empty();
            }
            if (lease.isPresent()) {
                var connection=sources.snapshotConnection(lease.get().userId());
                if (connection.isEmpty() || !connection.get().connectionId().equals(lease.get().connectionId())
                        || !connection.get().providerAccountId().equals(lease.get().providerAccountId())) {
                    jobs.finish(lease.get(),"BLOCKED","NOT_PERMITTED");
                    return Optional.empty();
                }
            }
            return lease;
        });
    }
    boolean heartbeat(SyncJobRepository.Lease lease) {
        return transactions.execute(status -> jobs.heartbeat(lease)==1);
    }
    void fail(SyncJobRepository.Lease lease,String reason) {
        transactions.executeWithoutResult(status -> {
            if (jobs.lockOwned(lease)) jobs.finish(lease,"FAILED",reason);
        });
    }
    void block(SyncJobRepository.Lease lease) {
        transactions.executeWithoutResult(status -> {
            if (jobs.lockOwned(lease)) jobs.finish(lease,"BLOCKED","NOT_PERMITTED");
        });
    }
    boolean publish(SyncJobRepository.Lease lease, SourceConnectionApi.EquityObservation value) {
        try {
            return transactions.execute(status -> {
                if (!jobs.lockOwned(lease) || !eligible(lease.userId())) throw new PublicationBlocked();
                var source=sources.snapshotConnection(lease.userId());
                if (source.isEmpty() || !source.get().connectionId().equals(lease.connectionId())
                        || !source.get().providerAccountId().equals(lease.providerAccountId())
                        || !source.get().policyVersion().equals(value.policyVersion()))
                    throw new PublicationBlocked();
                var membership=portfolios.eligibleMembership(lease.userId(),lease.connectionId(),
                        lease.providerAccountId(),value.measuredAt());
                if (membership.isEmpty()) throw new PublicationBlocked();
                UUID sourceId=UUID.randomUUID();
                jobs.insertSource(sourceId,lease,value.decimal(),value.measuredAt(),
                        value.receivedAt(),value.policyVersion());
                var component=new PortfolioMembershipApi.FirstComponent(lease.userId(),
                        membership.get().portfolioId(),membership.get().membershipId(),
                        lease.connectionId(),lease.providerAccountId(),sourceId,value.decimal(),
                        value.measuredAt(),value.policyVersion());
                if (!portfolios.publishFirst(component)) throw new PublicationBlocked();
                if (!jobs.owns(lease) || !eligible(lease.userId())
                        || jobs.finish(lease,"SUCCEEDED",null)!=1) throw new PublicationBlocked();
                return true;
            });
        } catch (PublicationBlocked blocked) {
            block(lease);
            return false;
        }
    }
    private boolean initialEligible(UUID userId) {
        return accounts.decide(userId,userId,AccountAccessKind.WRITE)==AccessDecision.ALLOWED
                && !restrictions.isRestricted(INITIAL_INPUTS_COMMAND)
                && !restrictions.isRestricted("plan-condition-create")
                && !restrictions.isRestricted(SYNC_COMMAND)
                && !restrictions.isRestricted(CONNECTION_COMMAND);
    }
    private static boolean validCollectionKey(@Nullable String key) {
        return key!=null&&key.length()>=16&&key.length()<=128&&key.matches("[A-Za-z0-9._:-]+")
                &&!key.contains("..")&&!key.startsWith("-");
    }
    private static final class PublicationBlocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
