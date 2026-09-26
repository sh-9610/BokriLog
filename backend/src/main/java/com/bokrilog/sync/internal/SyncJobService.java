package com.bokrilog.sync.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.SyncJobApi;
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
    private final SyncJobRepository jobs;
    private final SourceConnectionApi sources;
    private final PortfolioMembershipApi portfolios;
    private final AccountAccessApi accounts;
    private final OperationalRestrictionApi restrictions;
    private final TransactionTemplate transactions;

    SyncJobService(SyncJobRepository jobs, SourceConnectionApi sources,
            PortfolioMembershipApi portfolios, AccountAccessApi accounts,
            OperationalRestrictionApi restrictions, PlatformTransactionManager manager) {
        this.jobs=jobs; this.sources=sources; this.portfolios=portfolios;
        this.accounts=accounts; this.restrictions=restrictions;
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
    public Optional<SourceSnapshot> sourceSnapshot(@Nullable UUID userId, UUID sourceSnapshotId) {
        return userId==null ? Optional.empty() : jobs.sourceSnapshot(userId,sourceSnapshotId);
    }
    @Override
    public void eraseSyncData(@Nullable UUID userId) {
        if (userId!=null) transactions.executeWithoutResult(status -> jobs.erase(userId));
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
    private static final class PublicationBlocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
