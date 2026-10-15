package com.bokrilog.sync.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.sync.InitialInputsPlanPort;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.portfolio.PortfolioPointerInvariantViolation;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.source.SourceConnectionApi.CollectionObservation;
import com.bokrilog.sync.IdempotencyKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** sync owns each short transaction; no transaction spans a provider call. */
@Service
class SyncJobService implements SyncJobApi {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SyncJobService.class);
    private final PlanPublicationPort publicationPlans;
    private final SyncJobRepository jobs;
    private final InitialCollectionRepository initialJobs;
    private final CashflowClassificationRepository classifications;
    private final CashflowConversionRepository conversions;
    private final SourceConnectionApi sources;
    private final PortfolioMembershipApi portfolios;
    private final AccountAccessApi accounts;
    private final OperationalRestrictionApi restrictions;
    private final InitialInputsPlanPort plans;
    private final TransactionTemplate transactions;
    private final CashflowConversionMarket market;
    private final PlanPublicationService publications;
    private final AdjustmentHistoryService adjustments;
    private final PlanSyncService planSyncs;
    private final PlanSyncRepository planSyncAttempts;
    private final PlanVerificationService planVerifications;
    private final ConnectionRevocationRepository revocations;
    /** 강한 신호(Story 3.3): 초기 수집의 의미 충돌·미인식 enum, 첫 스냅샷 준비의 portfolio CAS 위반. */
    private final StrongSignals strongSignals;
    /** 동기화 계측(Story 3.7): 첫 스냅샷 admission의 제한 제외. 시도·결말은 작업 행 문장이 센다({@link SyncJobRepository}). */
    private final SyncMetrics metrics;

    SyncJobService(SyncJobRepository jobs, InitialCollectionRepository initialJobs,
            CashflowClassificationRepository classifications, CashflowConversionRepository conversions,
            SourceConnectionApi sources, PortfolioMembershipApi portfolios, AccountAccessApi accounts,
            OperationalRestrictionApi restrictions, InitialInputsPlanPort plans, PlatformTransactionManager manager,
            PlanPublicationService publications, AdjustmentHistoryService adjustments, PlanSyncService planSyncs,
            PlanSyncRepository planSyncAttempts, PlanVerificationService planVerifications, PlanPublicationPort publicationPlans,
            ConnectionRevocationRepository revocations, StrongSignals strongSignals, SyncMetrics metrics) {
        this.strongSignals=strongSignals;
        this.metrics=metrics;
        this.publicationPlans=publicationPlans;
        this.revocations=revocations;
        this.planSyncs=planSyncs;
        this.planVerifications=planVerifications;
        this.planSyncAttempts=planSyncAttempts;
        this.jobs=jobs; this.initialJobs=initialJobs; this.classifications=classifications;
        this.conversions=conversions;
        this.sources=sources; this.portfolios=portfolios;
        this.accounts=accounts; this.restrictions=restrictions; this.plans=plans;
        this.transactions=new TransactionTemplate(manager);
        this.market=new CashflowConversionMarket(sources,conversions,this.transactions);
        this.publications=publications;
        this.adjustments=adjustments;
    }
    boolean eligible(UUID userId) {
        return ineligibility(userId)==null;
    }
    /** 첫 스냅샷을 열 수 없는 이유: 계정 접근 거절({@code DENIED}) 또는 동기화 제한({@code RESTRICTED}). 열 수 있으면 null. */
    private @Nullable String ineligibility(UUID userId) {
        if (accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)!=AccessDecision.ALLOWED) return "DENIED";
        return syncRestricted(userId) ? PlanSyncGuard.RESTRICTED : null;
    }
    /**
     * 동기화 제한이 이 사용자의 첫 스냅샷·초기 입력을 막는가(Story 3.2) — {@code SYNC}가 사용자의 지금 portfolio·연결,
     * 또는 전역에 걸렸는가. 아직 계획이 없으므로 계획 제한은 적용되지 않고, 신규 연결 제한도 적용되지 않는다 — 이
     * 파이프라인은 이미 있는 연결 위에서 돈다(NFR-17의 독립성). 판독 실패는 예외다(fail-closed).
     */
    private boolean syncRestricted(UUID userId) {
        return syncRestricted(userId,java.util.Set.of());
    }
    private boolean syncRestricted(UUID userId,java.util.Set<UUID> excluding) {
        var membership=portfolios.activeSource(userId).orElse(null);
        var connection=sources.snapshotConnection(userId).orElse(null);
        return restrictions.blocks(java.util.EnumSet.of(RestrictionFeature.SYNC),RestrictionSubject.none()
                .withPortfolio(membership==null ? null : membership.portfolioId())
                .withConnection(connection==null ? null : connection.connectionId()),excluding);
    }
    @Override
    public Optional<Job> admit(@Nullable UUID userId, String key) {
        if (userId==null || key==null || key.isBlank() || key.length()>128) return Optional.empty();
        try {
            return transactions.execute(status -> {
                String refused=ineligibility(userId);
                if (refused!=null) {
                    // 제한이 막은 요청만 제외로 센다(Story 3.7, 요청 단위). 계정 접근 거절은 정책 제외가 아니다.
                    if (PlanSyncGuard.RESTRICTED.equals(refused))
                        metrics.outcome(com.bokrilog.sync.SyncTelemetry.Kind.FIRST_SNAPSHOT,
                                com.bokrilog.sync.SyncTelemetry.EXCLUDED,PlanSyncGuard.RESTRICTED);
                    return Optional.empty();
                }
                var source=sources.snapshotConnection(userId);
                if (source.isEmpty()) return Optional.empty();
                var connection=source.get();
                Optional<Job> same=jobs.byKey(userId,connection.connectionId(),key);
                if (same.isPresent()) return same;
                Optional<Job> active=jobs.active(userId,connection.connectionId());
                if (active.isPresent()) return active;
                // 공식값이 이미 있으면 첫 확인은 끝났다 — 계획 없이 다른 Bybit 계정으로 다시 연결한 경우만 그 계정의 새 첫
                // 확인을 받는다(Story 2.17, accountRebaseAllowed).
                if (portfolios.officialValue(userId).isPresent()
                        && !accountRebaseAllowed(userId,connection.providerAccountId())) return Optional.empty();
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
    /**
     * 계획 없이 다른 Bybit 계정으로 다시 연결한 사용자의 새 첫 확인을 받는가(Story 2.17, {@code RECONNECT-1.0}).
     *
     * <p>계획이 하나도 없고, 공식값은 있는데 그 계정의 기준 snapshot이 없을 때뿐이다 — 공식값이 이전 계정의 것이다. 그 첫
     * 확인은 portfolio 포인터를 새 계정의 snapshot으로 한 칸 옮기고({@code publishNext}), 그 snapshot이 이 계정의 계획
     * 기준이 된다({@code PortfolioMembershipApi.accountBaselineSnapshot}). 이전 snapshot·사건 기록은 지우거나 고치지 않는다.
     * 같은 계정의 재연결은 이전 연결로 받은 snapshot이 그대로 기준이라 여기 오지 않는다. 종단 계획이 있으면 후속 기준
     * (Story 2.15)이 이 일을 한다.
     */
    private boolean accountRebaseAllowed(UUID userId, UUID providerAccountId) {
        return publicationPlans.latestPlan(userId).isEmpty() && portfolios.officialValue(userId).isPresent()
                && portfolios.accountBaselineSnapshot(userId,providerAccountId).isEmpty();
    }
    private Optional<PlanPublicationPort.LatestPlan> successorPredecessor(UUID userId) {
        if (!initialEligible(userId) || publicationPlans.activePlan(userId).isPresent()) return Optional.empty();
        var latest=publicationPlans.latestPlan(userId).orElse(null);
        var membership=portfolios.activeSource(userId).orElse(null);
        var connection=sources.snapshotConnection(userId).orElse(null);
        if (latest==null || membership==null || connection==null
                || !latest.plan().portfolioId().equals(membership.portfolioId())
                || !membership.connectionId().equals(connection.connectionId())
                || !membership.providerAccountId().equals(connection.providerAccountId()) || terminal(latest)==null)
            return Optional.empty();
        return Optional.of(latest);
    }
    private static @Nullable Instant terminal(PlanPublicationPort.LatestPlan p) {
        if ("ENDED_EARLY".equals(p.status()) && p.end()!=null) return p.end().endedAt();
        if ("COMPLETED".equals(p.status()) && p.completion()!=null) return p.completion().transitionedAt();
        return null;
    }
    @Override
    public Optional<Job> admitNextBaseline(@Nullable UUID userId, @Nullable String key) {
        if (userId==null || !IdempotencyKey.isValid(key)) return Optional.empty();
        return transactions.execute(status -> {
            publicationPlans.lockPlanWrites(userId);
            if (!accounts.lockWriteAccess(userId)) return Optional.empty();
            var predecessor=successorPredecessor(userId).orElse(null);
            if (predecessor==null) return Optional.empty();
            var connection=sources.snapshotConnection(userId).orElseThrow();
            var same=jobs.byKey(userId,connection.connectionId(),key).orElse(null);
            if (same!=null) {
                var preparation=jobs.preparation(userId,same.jobId()).orElse(null);
                return preparation!=null && predecessor.plan().planId().equals(preparation.predecessorPlanId())
                        ? Optional.of(same) : Optional.empty();
            }
            if (jobs.busy(userId)) return Optional.empty();
            UUID id=UUID.randomUUID();
            if (jobs.insert(id,userId,connection.connectionId(),connection.providerAccountId(),key)!=1)
                return Optional.empty();
            jobs.insertPreparation(id,userId,predecessor.plan().planId(),predecessor.plan().portfolioId(),
                    java.util.Objects.requireNonNull(terminal(predecessor)));
            return jobs.byId(userId,id);
        });
    }
    @Override
    public Optional<NextBaseline> currentNextBaseline(UUID userId) {
        return jobs.nextInProgress(userId) ? Optional.empty() : jobs.baseline(userId,new UUID(0,0),true);
    }
    @Override
    public NextBaselineState nextBaselineState(@Nullable UUID userId) {
        if (userId==null || accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)!=AccessDecision.ALLOWED)
            return new NextBaselineState(false,"NOT_PERMITTED",null,null,null);
        var result=transactions.execute(status -> {
            var predecessor=successorPredecessor(userId).orElse(null);
            var baseline=jobs.baseline(userId,new UUID(0,0),true).filter(b -> predecessor!=null
                    && b.predecessorPlanId().equals(predecessor.plan().planId())).orElse(null);
            var snapshot=baseline==null ? null : portfolios.ownedSnapshot(userId,baseline.snapshotId())
                    .filter(s -> s.sourceSnapshotId().equals(baseline.sourceSnapshotId())
                            && jobs.sourceSnapshot(userId,s.sourceSnapshotId()).map(source -> "VERIFIED".equals(source.inputStatus())).orElse(false))
                    .orElse(null);
            // A revoked connection (Story 2.16) reads no new value: its own reason, before every other one.
            boolean revoked=sources.snapshotConnection(userId).isEmpty() && sources.hasRevokedConnection(userId);
            String reason=revoked ? "CONNECTION_REVOKED" : predecessor==null ? "NOT_ELIGIBLE"
                    : jobs.busy(userId) ? "JOB_IN_PROGRESS"
                    : baseline!=null && snapshot==null ? "INPUT_UNCONFIRMED"
                    : snapshot!=null && new java.math.BigDecimal(snapshot.decimal()).signum()<=0 ? "PRINCIPAL_NOT_POSITIVE" : "AVAILABLE";
            return new NextBaselineState(!revoked && predecessor!=null && !jobs.busy(userId),reason,
                    jobs.latestNext(userId).orElse(null),baseline,snapshot);
        });
        return accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)==AccessDecision.ALLOWED
                ? result : new NextBaselineState(false,"NOT_PERMITTED",null,null,null);
    }
    private boolean preparationValid(SyncJobRepository.Lease lease) {
        var preparation=jobs.preparation(lease.userId(),lease.jobId()).orElse(null);
        var predecessor=successorPredecessor(lease.userId()).orElse(null);
        return preparation!=null && predecessor!=null
                && predecessor.plan().planId().equals(preparation.predecessorPlanId())
                && predecessor.plan().portfolioId().equals(preparation.portfolioId())
                && preparation.terminalAt().equals(terminal(predecessor))
                && preparation.requestedAt().isAfter(preparation.terminalAt());
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
        // 연결은 한 번만 읽는다 — 두 번 읽으면 그 사이의 해제가 빈 값을 낳는다(Story 2.17 리뷰).
        var active=sources.snapshotConnection(userId).orElse(null);
        if (active==null) {
            /*
             * Story 2.16: a revoked connection keeps the first official value read-only — the job and the value stay
             * visible, flagged revoked, and no new read is offered. A revocation wins over a restriction.
             */
            if (sources.hasRevokedConnection(userId))
                return Optional.of(new State(jobs.latest(userId).orElse(null),
                        portfolios.officialValue(userId).orElse(null),false,false,true,false));
            return Optional.of(new State(null,null,false,false,false,false));
        }
        if (syncRestricted(userId))
            return Optional.of(new State(null,null,true,true,false,false));
        var connection=active;
        if (accountRebaseAllowed(userId,connection.providerAccountId()))
            /*
             * 다른 Bybit 계정으로 다시 연결했고 계획이 없다(Story 2.17): 공식값은 이전 계정의 것이라 이 계정의 첫 값으로 보이지
             * 않는다. 이 연결의 작업만 보이고 새 첫 확인을 받는다. 이전 값은 지우지 않는다.
             */
            return Optional.of(new State(jobs.latestOf(userId,connection.connectionId()).orElse(null),null,true,false,
                    false,true));
        /*
         * 작업은 지금 연결의 것, 없으면 지금 공식값을 만든 연결의 것이다(Story 2.17 리뷰). 사용자의 가장 최근 작업을 그대로 쓰면
         * A→B(새 첫 확인 실패)→A로 돌아온 사용자에게 B의 실패가 보인다.
         */
        var official=portfolios.officialValue(userId).orElse(null);
        var job=jobs.latestOf(userId,connection.connectionId()).or(() -> official==null ? Optional.empty()
                : portfolios.ownedSnapshot(userId,official.snapshotId())
                        .flatMap(s -> jobs.latestOf(userId,s.connectionId()))).orElse(null);
        return Optional.of(new State(job,official,true,false,false,false));
    }
    @Override
    public Optional<CollectionJob> admitInitialInputs(@Nullable UUID userId, UUID intentId, String key) {
        if (userId==null || !IdempotencyKey.isValid(key)) return Optional.empty();
        try {
            return transactions.execute(status -> {
                if (publicationPlans.latestPlan(userId).isPresent()) {
                    publicationPlans.lockPlanWrites(userId);
                    if (!accounts.lockWriteAccess(userId)) return Optional.empty();
                }
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
        if (userId==null||!IdempotencyKey.isValid(key)) return Optional.empty();
        return transactions.execute(status->{
            if (publicationPlans.latestPlan(userId).isPresent()) {
                publicationPlans.lockPlanWrites(userId);
                if (!accounts.lockWriteAccess(userId)) return Optional.empty();
            }
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
    public Optional<ConversionJob> admitCashflowConversion(@Nullable UUID userId, UUID intentId, String key) {
        if (userId==null||!IdempotencyKey.isValid(key)) return Optional.empty();
        return transactions.execute(status->{
            if (publicationPlans.latestPlan(userId).isPresent()) {
                publicationPlans.lockPlanWrites(userId);
                if (!accounts.lockWriteAccess(userId)) return Optional.empty();
            }
            var intent=plans.eligibleIntent(userId,intentId).orElse(null);
            if(intent==null||!initialEligible(userId)) return Optional.empty();
            var connection=sources.snapshotConnection(userId).orElse(null);
            if(connection==null||!connection.connectionId().equals(intent.connectionId())
                    ||!connection.providerAccountId().equals(intent.providerAccountId())) return Optional.empty();
            return conversions.admit(userId,intentId,intent.portfolioId(),intent.connectionId(),
                    intent.providerAccountId(),key).flatMap(jobId->conversions.view(userId,jobId));
        });
    }
    @Override
    public ConversionState cashflowConversionState(@Nullable UUID userId, UUID intentId) {
        if(userId==null||accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)!=AccessDecision.ALLOWED
                ||!initialEligible(userId)) return new ConversionState(false,null);
        var intent=plans.eligibleIntent(userId,intentId).orElse(null);
        var connection=sources.snapshotConnection(userId).orElse(null);
        if(intent==null||connection==null||!connection.connectionId().equals(intent.connectionId())
                ||!connection.providerAccountId().equals(intent.providerAccountId())) return new ConversionState(false,null);
        var classification=conversions.eligibleClassification(userId,intentId,intent.portfolioId(),
                intent.connectionId(),intent.providerAccountId());
        if(classification.isEmpty()) return new ConversionState(true,null);
        return new ConversionState(true,conversions.latestView(userId,intentId,
                classification.get().classificationId()).orElse(null));
    }
    @Override
    public PublicationAdmission admitPlanPublication(@Nullable UUID userId, UUID intentId, @Nullable String key) {
        return publications.admit(userId,intentId,key);
    }
    @Override
    public PublicationState planPublicationState(@Nullable UUID userId, UUID intentId) {
        return publications.state(userId,intentId);
    }
    @Override
    public DashboardRead dashboard(@Nullable UUID userId) {
        return publications.dashboard(userId);
    }
    @Override
    public DashboardRead historicalDashboard(@Nullable UUID userId, UUID planId) {
        return publications.historicalDashboard(userId,planId);
    }
    @Override
    public Optional<java.util.List<PlanPublicationPort.LatestPlan>> planHistory(@Nullable UUID userId) {
        if (userId==null || accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)!=AccessDecision.ALLOWED) return Optional.empty();
        var rows=transactions.execute(status -> publicationPlans.planHistory(userId));
        return accounts.decide(userId,userId,AccountAccessKind.SENSITIVE)==AccessDecision.ALLOWED ? Optional.ofNullable(rows) : Optional.empty();
    }
    @Override
    public EvidenceRead calculationEvidence(@Nullable UUID userId, @Nullable EvidenceReference reference) {
        return publications.evidence(userId,reference);
    }
    @Override
    public AdjustmentRead adjustmentHistory(@Nullable UUID userId, @Nullable AdjustmentQuery query) {
        return adjustments.read(userId,query);
    }
    @Override
    public SyncAdmission admitPlanSync(@Nullable UUID userId, @Nullable SyncTrigger trigger,
            @Nullable String idempotencyKey) {
        return planSyncs.admit(userId,trigger,idempotencyKey);
    }
    @Override
    public boolean publishedManualSync(UUID userId, UUID jobId) {
        return planSyncAttempts.publishedManualSync(userId,jobId);
    }
    @Override
    public VerificationAdmission admitPlanVerification(@Nullable UUID userId, @Nullable VerificationKind kind,
            @Nullable EvidenceReference reference, @Nullable String idempotencyKey) {
        return planVerifications.admit(userId,kind,reference,idempotencyKey);
    }
    @Override
    public PlanEndBasisRead planEndBasis(@Nullable UUID userId, UUID planId) {
        return publications.planEndBasis(userId,planId);
    }
    @Override
    public VerificationAdmission admitSystemVerification(@Nullable UUID userId, VerificationReason reason,
            List<InvestigationRange> ranges, @Nullable String idempotencyKey) {
        return planVerifications.admitSystem(userId,reason,ranges,idempotencyKey);
    }
    /**
     * Erases this user's sync rows. The publication rows go first: the official pointer and calculation
     * version cite the candidate, classification and conversion with ON DELETE RESTRICT. Runs after the
     * portfolio erasure, whose snapshot components cite the source snapshots.
     *
     * <p>The incremental sync attempts (Story 2.11) are locked first — in a stage's own lock order, attempt
     * before child — and erased after their stage rows, which reference them. An attempt still in progress ends
     * here, with the user's data; its one ending line is written once the erasure committed. Story 2.12's rows —
     * the verifications' ranges and reconciliation outcomes, the versions' change rows and the candidates'
     * exclusions — go first of all: they cite everything below.
     */
    @Override
    public void eraseSyncData(@Nullable UUID userId) {
        if (userId==null) return;
        List<PlanSyncRepository.Ending> ended=transactions.execute(status -> {
            planSyncAttempts.lockForErase(userId);
            // Story 2.12's rows first: they cite the versions, candidates, events and attempts erased below.
            planSyncAttempts.eraseVerification(userId);
            publications.erase(userId);
            conversions.erase(userId);
            classifications.erase(userId);
            initialJobs.erase(userId);
            List<PlanSyncRepository.Ending> erased=planSyncAttempts.erase(userId);
            jobs.erase(userId);
            return erased;
        });
        if (ended!=null) ended.forEach(ending -> planSyncs.logEnding(new PlanSyncRepository.Ending(ending.jobId(),
                ending.purpose(),ending.reason(),ending.trigger(),ending.mode(),"ERASED",ending.stage(),"-",
                ending.latencyMs())));
    }
    @Override
    public long officialBundleViolations(java.util.Collection<UUID> userIds) {
        return publications.officialBundleViolations(userIds);
    }
    @Override
    public long remainingRowsOf(UUID userId) {
        return jobs.remainingRowsOf(userId);
    }
    @Override
    public void lockConnectionRevocation(UUID userId) {
        requireCallerTransaction();
        publicationPlans.lockPlanWrites(userId);
    }
    @Override
    public void lockConnectionReconnection(UUID userId) {
        // 해제와 같은 첫 잠금이다(Story 2.17). 이름이 다른 것은 호출자의 뜻을 읽히게 하려는 것뿐이다.
        requireCallerTransaction();
        publicationPlans.lockPlanWrites(userId);
    }
    @Override
    public boolean completeEndedPlanForReconnection(UUID userId) {
        requireCallerTransaction();
        return planSyncs.completeIfEnded(userId);
    }
    @Override
    public EndedConnectionWork endConnectionWork(UUID userId, UUID connectionId, Instant revokedAt) {
        requireCallerTransaction();
        return revocations.endWork(userId,connectionId,revokedAt);
    }
    private static void requireCallerTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("the connection revocation joins the caller's transaction");
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
    /*
     * Story 2.6 conversion attempt. Eligibility is re-read at claim, before the first provider
     * call and inside the publishing transaction; provider calls run between short transactions.
     */
    Optional<CashflowConversionRepository.Lease> claimConversion() {
        return transactions.execute(status->conversions.claim().flatMap(lease->{
            if(!conversionStillPermitted(lease)) {
                conversions.finish(lease,"CANCELLED","NOT_PERMITTED",false);
                return Optional.empty();
            }
            return Optional.of(lease);
        }));
    }
    boolean heartbeatConversion(CashflowConversionRepository.Lease lease) {
        return transactions.execute(status->conversions.heartbeat(lease)==1);
    }
    /**
     * Runs one attempt. {@code alive} is the worker's local lease guard; losing it (or the lease
     * row) stops the attempt before the next provider call and nothing is published.
     */
    void runConversion(CashflowConversionRepository.Lease lease, BooleanSupplier alive) {
        try {
            if(!alive.getAsBoolean()) return;
            List<CashflowConversionRepository.Input> inputs=transactions.execute(status->{
                if(!conversions.lockOwned(lease)) return null;
                if(!conversionStillPermitted(lease)) {
                    conversions.finish(lease,"CANCELLED","NOT_PERMITTED",false);
                    return null;
                }
                return conversions.storedInputs(lease);
            });
            if(inputs==null) return;
            List<PriceConversionPolicy.FlowInput> flows=inputs.stream().map(CashflowConversionRepository.Input::flow).toList();
            BooleanSupplier active=()->alive.getAsBoolean()
                    &&Boolean.TRUE.equals(transactions.execute(status->conversions.owns(lease)));
            PriceConversionPolicy.Reference reference=null;
            if(flows.stream().anyMatch(PriceConversionPolicy::needsMarket)) {
                reference=lease.referenceTime()!=null ? new PriceConversionPolicy.Reference.Fixed(lease.referenceTime())
                        : fixReference(lease,active);
                if(reference==null) return;
            }
            List<PriceConversionPolicy.FlowOutcome> outcomes=market.convert(flows,reference,active);
            if(outcomes==null) return;
            publishConversion(lease,inputs,outcomes,
                    reference instanceof PriceConversionPolicy.Reference.Fixed fixed ? fixed.at() : null);
        } catch (RuntimeException failure) {
            /*
             * 예외 클래스의 단순 이름 하나만 남긴다(구조 인자 exception, Story 3.7). 메시지·원인에는 SQL·제공자 문구·값이
             * 실릴 수 있다. 작업 ID도 남기지 않는다 — 로그에 식별자를 두지 않으며, 그 작업은 행의 상태
             * (CONVERSION_REQUIRED·CONVERSION_INTERRUPTED)와 대시보드로 찾는다.
             */
            LOG.atWarn().addKeyValue("exception",failure.getClass().getSimpleName()).log("cash-flow conversion interrupted");
            transactions.executeWithoutResult(status->{
                if(conversions.lockOwned(lease))
                    conversions.finish(lease,"CONVERSION_REQUIRED","CONVERSION_INTERRUPTED",true);
            });
        }
    }
    /** Fixes the attempt's Bybit reference time once; a failed read becomes a per-flow outcome. */
    private PriceConversionPolicy.@Nullable Reference fixReference(CashflowConversionRepository.Lease lease,
            BooleanSupplier active) {
        if(!active.getAsBoolean()) return null;
        var time=sources.marketTime();
        if(time instanceof SourceConnectionApi.MarketTimeResult.Rejected rejected)
            return new PriceConversionPolicy.Reference.Failed(
                    rejected.reason()==com.bokrilog.source.SourceRejectionReason.RESPONSE_FORMAT);
        Instant at=((SourceConnectionApi.MarketTimeResult.Observed)time).serverTime();
        boolean fixed=Boolean.TRUE.equals(transactions.execute(status->
                conversions.lockOwned(lease)&&conversions.fixReference(lease,at)));
        return fixed ? new PriceConversionPolicy.Reference.Fixed(at) : null;
    }
    private void publishConversion(CashflowConversionRepository.Lease lease,List<CashflowConversionRepository.Input> inputs,
            List<PriceConversionPolicy.FlowOutcome> outcomes,@Nullable Instant reference) {
        try {
            transactions.executeWithoutResult(status->{
                if(!conversions.lockOwned(lease)) return;
                if(!conversionStillPermitted(lease)) {
                    conversions.finish(lease,"CANCELLED","NOT_PERMITTED",false);
                    return;
                }
                conversions.publish(lease,inputs,outcomes,reference,()->conversionStillPermitted(lease));
            });
        } catch (CashflowConversionRepository.PublicationBlocked blocked) {
            transactions.executeWithoutResult(status->{
                if(conversions.lockOwned(lease)) conversions.finish(lease,"CANCELLED","NOT_PERMITTED",false);
            });
        }
    }
    private boolean conversionStillPermitted(CashflowConversionRepository.Lease lease) {
        var intent=plans.eligibleIntent(lease.userId(),lease.intentId()).orElse(null);
        var connection=sources.snapshotConnection(lease.userId()).orElse(null);
        return initialEligible(lease.userId())&&intent!=null&&connection!=null
                &&intent.portfolioId().equals(lease.portfolioId())
                &&intent.connectionId().equals(lease.connectionId())
                &&intent.providerAccountId().equals(lease.providerAccountId())
                &&connection.connectionId().equals(lease.connectionId())
                &&connection.providerAccountId().equals(lease.providerAccountId())
                &&conversions.inputsStillValid(lease);
    }
    Optional<InitialCollectionRepository.Lease> claimInitial() {
        return transactions.execute(status -> initialJobs.claim().flatMap(lease -> {
            // 다시 잡힌 작업이 자기 강한 신호를 이미 냈으면 그 제한만은 빼고 본다(Story 3.3) — 처음 잡힌 것과 같은 결말이다.
            var own=strongSignals.ownRestrictions(lease.jobId());
            var intent=plans.eligibleIntent(lease.userId(),lease.intentId(),own).orElse(null);
            var connection=sources.snapshotConnection(lease.userId()).orElse(null);
            if (!collectionEligible(lease,own)||intent==null||connection==null
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
        List<InitialCollectionRepository.StrongObservation> strong=new ArrayList<>();
        Boolean review=transactions.execute(status->{
            if (!initialJobs.lockOwned(lease)||!collectionEligible(lease,strongSignals.ownRestrictions(lease.jobId())))
                throw new IllegalStateException("collection lease lost");
            return initialJobs.storePage(lease,page.evidence(),page.events(),page.reviewRequired(),strong);
        });
        /*
         * 강한 신호(Story 3.3 신호 6·7): 관측이 커밋된 뒤 종류마다 한 번 올린다 — 감지 지점의 별도 트랜잭션이다. 계획이 없는
         * 수집이라 판정표가 연결을 고른다. 수집의 결말은 기존 규칙이 맺는다(검토 필요). 기록하지 못하면 수집은 끝나지 않는다
         * (lease 규칙, 상한은 {@link #signalUnrecorded}).
         */
        if (!strong.isEmpty())
            strongSignals.observations(strong,lease.userId(),lease.jobId(),lease.connectionId(),null,null);
        return Boolean.TRUE.equals(review);
    }
    void finishCollection(InitialCollectionRepository.Lease lease,CollectionObservation input,boolean needsReview) {
        transactions.executeWithoutResult(status->{
            var own=strongSignals.ownRestrictions(lease.jobId());
            if (!initialJobs.lockOwned(lease)||!collectionEligible(lease,own)) return;
            var intent=plans.eligibleIntent(lease.userId(),lease.intentId(),own).orElse(null);
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
            // 강한 관측이 있는 수집은 무엇을 봤든 검토 필요다 — 앞선 holder가 본 것도 여기서 센다.
            var stored=initialJobs.complete(lease,input,needsReview||initialJobs.hasStrongDiagnostics(lease.jobId()));
            initialJobs.finish(lease,stored.status(),stored.failureCode());
        });
    }
    /**
     * 강한 신호를 기록하지 못한 수집(Story 3.3). 상한 세대 전에는 아무것도 하지 않는다 — lease가 만료되면 다시 잡혀 같은
     * 관측을 다시 올린다. 상한 세대면 후보 없이 검토 필요({@code SIGNAL_UNRECORDED})로 맺는다.
     */
    void signalUnrecorded(InitialCollectionRepository.Lease lease) {
        if (!StrongSignals.exhausted(lease.generation())) return;
        boolean ended=Boolean.TRUE.equals(transactions.execute(status->initialJobs.lockOwned(lease)
                &&initialJobs.finish(lease,"NEEDS_REVIEW",StrongSignals.SIGNAL_UNRECORDED)==1));
        if (ended) StrongSignals.logUnrecordedEnding("initial-collection");
    }
    /** 첫 스냅샷·후속 기준 작업의 {@link #signalUnrecorded}. 상한 세대면 실패({@code SIGNAL_UNRECORDED})로 맺는다. */
    void signalUnrecorded(SyncJobRepository.Lease lease) {
        if (!StrongSignals.exhausted(lease.generation())) return;
        boolean ended=Boolean.TRUE.equals(transactions.execute(status->jobs.lockOwned(lease)
                &&jobs.finish(lease,"FAILED",StrongSignals.SIGNAL_UNRECORDED)==1));
        if (ended) StrongSignals.logUnrecordedEnding("snapshot");
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
            if (lease.isPresent() && (!eligible(lease.get().userId())
                    || (jobs.next(lease.get().jobId()) && !preparationValid(lease.get())))) {
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
                boolean next=jobs.next(lease.jobId());
                // 공식값이 이미 있는 첫 확인은 다른 계정으로 다시 연결한 뒤의 새 첫 확인뿐이다(Story 2.17). 계획 쓰기와 직렬화하려고
                // 후속 기준과 같은 잠금 순서(계획 → 계정 생명주기)를 쓰고, 잠금 아래에서 자격을 다시 본다.
                boolean rebase=!next && portfolios.officialValue(lease.userId()).isPresent();
                if (next || rebase) publicationPlans.lockPlanWrites(lease.userId());
                if (!accounts.lockWriteAccess(lease.userId()) || !jobs.lockOwned(lease) || !eligible(lease.userId())
                        || (next && !preparationValid(lease))
                        || (rebase && !accountRebaseAllowed(lease.userId(),lease.providerAccountId())))
                    throw new PublicationBlocked();
                if (next) {
                    var preparation=jobs.preparation(lease.userId(),lease.jobId()).orElseThrow();
                    if (!value.measuredAt().isAfter(preparation.terminalAt())
                            || !value.receivedAt().isAfter(preparation.terminalAt())) throw new PublicationBlocked();
                }
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
                if (next || rebase) {
                    // 포인터를 이 계정의 새 snapshot으로 한 칸 옮긴다. 이전 snapshot은 그대로 남는다.
                    var current=portfolios.officialValue(lease.userId()).orElseThrow(PublicationBlocked::new);
                    var result=portfolios.publishNext(new PortfolioMembershipApi.CandidateComponent(component.userId(),
                            component.portfolioId(),component.membershipId(),component.connectionId(),component.providerAccountId(),
                            component.sourceSnapshotId(),component.decimal(),component.measuredAt(),component.policyVersion()),
                            current.snapshotId(),current.publicationSequence());
                    if (!(result instanceof PortfolioMembershipApi.PublishedSnapshot published)) throw new PublicationBlocked();
                    if (next) jobs.completePreparation(lease.userId(),lease.jobId(),published.snapshotId(),sourceId);
                    // 이전 계정의 snapshot에 걸린 확인·intent는 이제 기준이 아니다.
                    publicationPlans.invalidatePreparedConditions(lease.userId());
                } else if (!portfolios.publishFirst(component)) throw new PublicationBlocked();
                if (!jobs.owns(lease) || !eligible(lease.userId())
                        || jobs.finish(lease,"SUCCEEDED",null)!=1) throw new PublicationBlocked();
                return true;
            });
        } catch (PublicationBlocked blocked) {
            block(lease);
            return false;
        } catch (PortfolioPointerInvariantViolation violation) {
            // 강한 신호(Story 3.3 신호 5): 발행은 이미 롤백됐다. 그 뒤 신호를 커밋하고, 작업은 기존대로 실패로 끝난다.
            strongSignals.raiseFor(violation,lease.userId(),lease.jobId());
            throw violation;
        }
    }
    /**
     * 수집 작업이 이어 쓸 수 있는가: {@link #initialEligible}과 같되, 그 작업이 이미 낸 강한 신호의 제한({@code own})만은
     * 빼고 본다(Story 3.3) — 그 수집은 검토 필요로만 맺고 후보를 만들지 않는다. 운영자 제한·다른 신호의 제한과 계정 쓰기
     * 권한은 그대로 막는다.
     */
    private boolean collectionEligible(InitialCollectionRepository.Lease lease,java.util.Set<UUID> own) {
        return accounts.decide(lease.userId(),lease.userId(),AccountAccessKind.WRITE)==AccessDecision.ALLOWED
                && !syncRestricted(lease.userId(),own);
    }
    private boolean initialEligible(UUID userId) {
        return accounts.decide(userId,userId,AccountAccessKind.WRITE)==AccessDecision.ALLOWED
                && !syncRestricted(userId);
    }
    private static final class PublicationBlocked extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
