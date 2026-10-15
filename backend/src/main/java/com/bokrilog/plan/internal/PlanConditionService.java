package com.bokrilog.plan.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.portfolio.PortfolioMembershipApi.FirstOfficialSnapshot;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.sync.InitialInputsPlanPort;
import com.bokrilog.sync.PlanPublicationPort;
import com.bokrilog.sync.IdempotencyKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계획 조건 intent와 불변 활성 계획.
 *
 * <p>활성 계획은 이 모듈이 소유하지만 만드는 것은 sync의 발행 트랜잭션이다. 그래서
 * {@link PlanPublicationPort}의 변경 command는 호출자 트랜잭션에 참여만 하고, 조건 화면·미리보기·
 * 확인은 활성 계획이 있으면 새 조건을 받지 않는다({@link PlanConditionApi#ACTIVE_PLAN_EXISTS}).
 */
@Service
class PlanConditionService implements PlanConditionApi, InitialInputsPlanPort, PlanPublicationPort {
    static final String CALENDAR_POLICY="CALENDAR-SEOUL-1.0";
    static final String CALCULATION_POLICY="CALC-1.0";
    static final ZoneId ZONE=ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter LOCAL_TIME=DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final long CONFIRMATION_LIFETIME_SECONDS=600;

    private final PlanConditionRepository repository;
    /** The early end's rows (Story 2.13): whether a plan ended, and the latest plan the dashboard shows. */
    private final PlanEndRepository ends;
    /** The natural completion (Story 2.14): the transition, the first observation and the comparison. */
    private final PlanCompletionService completions;
    private final PortfolioMembershipApi portfolios;
    private final SyncJobApi sync;
    private final SourceConnectionApi sources;
    private final AccountAccessApi accounts;
    private final OperationalRestrictionApi restrictions;
    private final TransactionTemplate transactions;

    PlanConditionService(PlanConditionRepository repository, PlanEndRepository ends, PlanCompletionService completions,
            PortfolioMembershipApi portfolios, @Lazy SyncJobApi sync, SourceConnectionApi sources,
            AccountAccessApi accounts, OperationalRestrictionApi restrictions, PlatformTransactionManager manager) {
        this.repository=repository; this.ends=ends; this.completions=completions; this.portfolios=portfolios;
        this.sync=sync; this.sources=sources;
        this.accounts=accounts; this.restrictions=restrictions;
        this.transactions=new TransactionTemplate(manager);
    }

    @Override
    public State state(@Nullable UUID userId) {
        if (userId==null) return new State(false,"NOT_ELIGIBLE",null,null,null);
        return transactions.execute(status -> {
            repository.lockUser(userId);
            Instant now=Instant.now();
            repository.invalidateExpired(userId,now);
            if (!eligibleUser(userId)) {
                repository.invalidateOpen(userId);
                return new State(false,"NOT_ELIGIBLE",null,null,null);
            }
            var active=repository.activePlan(userId).orElse(null);
            if (active!=null) return new State(false,ACTIVE_PLAN_EXISTS,null,null,view(active));
            // A plan that ended early or completed takes no new conditions until Story 2.15 opens the following plan: the
            // first official snapshot is that plan's P0 and t0, and a plan made from it would continue the old baseline.
            if (ends.terminalPlanExists(userId) && sync.currentNextBaseline(userId).isEmpty())
                return new State(false,PLAN_ENDED,null,null,null);
            FirstOfficialSnapshot s=eligibleSnapshot(userId).orElse(null);
            if (s==null || !eligibleRestrictions(subjectOf(s.portfolioId(),s.connectionId()))) {
                repository.invalidateOpen(userId);
                return new State(false,"NOT_ELIGIBLE",null,null,null);
            }
            var source=sync.sourceSnapshot(userId,s.sourceSnapshotId()).orElseThrow();
            var snapshot=new Snapshot(s.decimal(),s.measuredAt(),s.publishedAt(),s.portfolioId(),s.snapshotId(),
                    s.membershipId(),s.connectionId(),s.providerAccountId(),s.sourceSnapshotId(),
                    source.inputStatus(),source.policyVersion(),s.portfolioPolicyVersion());
            repository.invalidateOpenUnlessCurrentSnapshot(userId,s,now);
            return new State(true,null,snapshot,repository.openIntent(userId).orElse(null),null);
        });
    }

    @Override
    public Optional<Preview> preview(@Nullable UUID userId, @Nullable String sessionId,
            @Nullable PreviewInput input) {
        if (userId==null || sessionId==null || sessionId.isBlank() || input==null
                || input.period()==null || input.targetRate()==null || input.endAtSeoul()==null
                || !eligibleUser(userId) || repository.activePlan(userId).isPresent()
                || (ends.terminalPlanExists(userId) && sync.currentNextBaseline(userId).isEmpty())) return Optional.empty();
        var s=eligibleSnapshot(userId).orElse(null);
        if (s==null) return Optional.empty();
        Period period;
        String rate;
        Instant end;
        try {
            period=Period.valueOf(input.period());
            rate=canonicalRate(input.targetRate());
            end=parseEnd(input.endAtSeoul(),s.measuredAt());
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
        var source=sync.sourceSnapshot(userId,s.sourceSnapshotId()).orElseThrow();
        Snapshot snapshot=new Snapshot(s.decimal(),s.measuredAt(),s.publishedAt(),s.portfolioId(),s.snapshotId(),
                s.membershipId(),s.connectionId(),s.providerAccountId(),s.sourceSnapshotId(),
                source.inputStatus(),source.policyVersion(),s.portfolioPolicyVersion());
        String ref=randomRef();
        Instant expires=Instant.now().plusSeconds(CONFIRMATION_LIFETIME_SECONDS)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String hash=sha256(canonical(userId,s,end,period,rate));
        try {
            Boolean stored=transactions.execute(status -> {
                repository.lockUser(userId);
                Instant now=Instant.now();
                repository.invalidateExpired(userId,now);
                if (!accounts.lockWriteAccess(userId) || !eligibleRestrictions(subjectOf(s.portfolioId(),s.connectionId()))
                        || !sameSnapshot(userId,s)) {
                    repository.invalidateOpen(userId);
                    return false;
                }
                // Checked again under the lock: a publication or an end that committed after the check above wins.
                if (repository.activePlan(userId).isPresent() || !sameSnapshot(userId,s)) return false;
                repository.insertConfirmation(sha256(ref),userId,sha256(sessionId),hash,s,end,period,rate,expires);
                sync.currentNextBaseline(userId).ifPresent(b -> repository.linkConfirmation(sha256(ref),b));
                return true;
            });
            if (!Boolean.TRUE.equals(stored)) return Optional.empty();
        } catch (DuplicateKeyException rejected) { return Optional.empty(); }
        return Optional.of(new Preview(ref,expires,snapshot,end,period,rate,CALENDAR_POLICY,CALCULATION_POLICY));
    }

    @Override
    public Confirmation confirm(@Nullable UUID userId, @Nullable String sessionId,
            @Nullable String confirmationRef, @Nullable String idempotencyKey) {
        if (userId==null || sessionId==null || sessionId.isBlank() || confirmationRef==null
                || !IdempotencyKey.isValid(idempotencyKey) || !eligibleUser(userId)) return new Confirmation(false,false,null,null);
        String confirmationHash=sha256(confirmationRef);
        try {
            return transactions.execute(status -> {
                repository.lockUser(userId);
                if (!accounts.lockWriteAccess(userId) || !eligibleRestrictions(RestrictionSubject.none()
                        .withCalculationPolicy(CALCULATION_POLICY)))
                    return new Confirmation(false,false,null,null);
                // An active plan takes no new conditions — whatever the reference is: unknown, from another
                // session, of another policy, or a replay. Checked before the reference is read, so every
                // one of those answers the actual state (409 ACTIVE_PLAN_EXISTS) instead of "stale" (422).
                // Read under the plan lock the publication also takes first, so a publication that
                // committed a moment ago is seen here.
                if (repository.activePlan(userId).isPresent())
                    return new Confirmation(false,false,null,ACTIVE_PLAN_EXISTS);
                // The same for an ended plan (Story 2.13): it answers its own state, before the reference is read.
                if (ends.terminalPlanExists(userId) && sync.currentNextBaseline(userId).isEmpty())
                    return new Confirmation(false,false,null,PLAN_ENDED);
                var c=repository.confirmation(confirmationHash,userId,sha256(sessionId)).orElse(null);
                if (c==null
                        || !c.calendarPolicy().equals(CALENDAR_POLICY)
                        || !c.calculationPolicy().equals(CALCULATION_POLICY)) return new Confirmation(false,false,null,null);
                // 확인은 자기 portfolio·연결을 가리킨다: 둘 중 하나의 제한도 확인을 막는다(Story 3.2).
                if (!eligibleRestrictions(subjectOf(c.portfolioId(),c.connectionId())))
                    return new Confirmation(false,false,null,null);
                var prior=repository.idempotency(userId,idempotencyKey).orElse(null);
                if (prior!=null) {
                    return new Confirmation(prior.canonicalHash().equals(c.canonicalHash()),
                            !prior.canonicalHash().equals(c.canonicalHash()),
                            repository.intent(userId,prior.intentId()).orElse(null),null);
                }
                if (!c.expiresAt().isAfter(Instant.now()) || c.consumedAt()!=null)
                    return new Confirmation(false,false,null,null);
                if (!eligibleUser(userId) || !confirmationStillValid(userId,c)) {
                    repository.invalidateOpen(userId);
                    return new Confirmation(false,false,null,null);
                }
                Instant now=Instant.now();
                if (!c.endAt().isAfter(now)) return new Confirmation(false,false,null,null);
                repository.invalidateExpired(userId,now);
                var current=repository.latestOpenForUpdate(userId).orElse(null);
                // A preview created before another tab's intent is a losing concurrent submission.
                if (current!=null && !current.createdAt().isBefore(c.issuedAt()))
                    return new Confirmation(false,true,current,null);
                UUID intentId=UUID.randomUUID();
                if (current!=null && repository.replace(userId,current.intentId(),intentId)!=1)
                    throw new StaleState();
                repository.saveIntent(intentId,userId,c);
                repository.linkIntent(intentId,confirmationHash);
                if (repository.idempotency(userId,idempotencyKey,c.canonicalHash(),intentId)!=1)
                    throw new DuplicateKeyException("idempotency conflict");
                repository.consume(confirmationHash);
                return new Confirmation(true,false,repository.intent(userId,intentId).orElse(null),null);
            });
        } catch (DuplicateKeyException | StaleState rejected) {
            var existing=repository.idempotency(userId,idempotencyKey).orElse(null);
            if (existing==null) return new Confirmation(false,false,null,null);
            return new Confirmation(existing.canonicalHash().equals(readCanonicalHash(confirmationHash,userId,sessionId)),
                    !existing.canonicalHash().equals(readCanonicalHash(confirmationHash,userId,sessionId)),
                    repository.intent(userId,existing.intentId()).orElse(null),null);
        }
    }

    @Override
    public Optional<CollectionIntent> collectionIntent(@Nullable UUID userId, UUID intentId) {
        return collectionIntent(userId,intentId,java.util.Set.of());
    }

    /** {@link #collectionIntent}에서 그 제한들은 보지 않는다(Story 3.3: 신호를 낸 수집 작업의 자기 제한). */
    private Optional<CollectionIntent> collectionIntent(@Nullable UUID userId, UUID intentId,
            java.util.Set<UUID> excluding) {
        if (userId == null) return Optional.empty();
        return transactions.execute(status -> {
            // 초기 수집의 intent 판정이다(Story 3.2): 대응표에서 초기 수집은 동기화 하나만 본다 — 계산 표시 제한은 막지 않는다.
            if (!writableUser(userId) || currentTerms(userId,intentId,false,SYNC_ONLY,excluding).isEmpty())
                return Optional.empty();
            var row = repository.collectionIntent(userId,intentId).orElse(null);
            if (row == null || !"OPEN".equals(row.status()) || !row.expiresAt().isAfter(Instant.now()))
                return Optional.empty();
            var connection = sources.snapshotConnection(userId).orElse(null);
            var active = portfolios.activeSource(userId).orElse(null);
            if (connection == null || active == null
                    || !row.connectionId().equals(connection.connectionId())
                    || !row.providerAccountId().equals(connection.providerAccountId())
                    || !row.connectionId().equals(active.connectionId())
                    || !row.providerAccountId().equals(active.providerAccountId())
                    || !row.portfolioId().equals(active.portfolioId())) return Optional.empty();
            return Optional.of(new CollectionIntent(row.intentId(),row.portfolioId(),row.connectionId(),
                    row.providerAccountId(),row.t0(),row.expiresAt()));
        });
    }

    @Override
    public Optional<InitialInputsPlanPort.Intent> eligibleIntent(@Nullable UUID userId, UUID intentId) {
        return eligibleIntent(userId,intentId,java.util.Set.of());
    }

    @Override
    public Optional<InitialInputsPlanPort.Intent> eligibleIntent(@Nullable UUID userId, UUID intentId,
            java.util.Set<UUID> excluding) {
        return collectionIntent(userId,intentId,excluding).map(i->new InitialInputsPlanPort.Intent(
                i.intentId(),i.portfolioId(),i.connectionId(),i.providerAccountId(),i.t0()));
    }

    @Override
    public Optional<PlanConditionApi.PlanTarget> planTarget(UUID planId) {
        return repository.planTarget(planId).map(this::withCurrentConnection);
    }

    @Override
    public Optional<UUID> activePlanProviderAccount(UUID userId) {
        return repository.activePlanProviderAccount(userId);
    }

    /**
     * 계획의 지금 연결(Story 2.17): 계획과 같은 Bybit 계정·portfolio의 지금 활성 연결과 그 membership이 있으면 그 연결이고,
     * 없으면 계획이 기록한 연결 그대로다. 동기화 가드({@code PlanSyncGuard.planSource})와 같은 판정이다 — 재연결 뒤
     * 운영 제한의 {@code PROVIDER_CONNECTION} 차원(운영자 대상 확인·영향 집계)이 새 연결을 본다. 그 차원의 판독은 연결 ID를
     * 같은 계정의 연결 전부로 넓히므로, 이전 연결에 건 효력 중인 {@code PROVIDER_CONNECTION} 제한도 같은 계정으로 다시 연결한
     * 계획의 그 기능을 막고 다른 계정의 연결에는 미치지 않는다({@code com.bokrilog.operations.RestrictionConnectionScope}).
     */
    private PlanConditionApi.PlanTarget withCurrentConnection(PlanConditionApi.PlanTarget recorded) {
        var connection=sources.snapshotConnection(recorded.userId()).orElse(null);
        var membership=portfolios.activeSource(recorded.userId()).orElse(null);
        if (connection==null || membership==null || !connection.providerAccountId().equals(recorded.providerAccountId())
                || !membership.portfolioId().equals(recorded.portfolioId())
                || !membership.connectionId().equals(connection.connectionId())
                || !membership.providerAccountId().equals(recorded.providerAccountId()))
            return recorded;
        return new PlanConditionApi.PlanTarget(recorded.planId(),recorded.userId(),recorded.portfolioId(),
                connection.connectionId(),recorded.providerAccountId(),recorded.status(),recorded.calculationPolicy());
    }

    @Override
    public long countPlans(boolean activeOnly, @Nullable UUID userId, @Nullable UUID portfolioId,
            @Nullable String calculationPolicy) {
        return repository.countPlans(activeOnly,userId,portfolioId,calculationPolicy);
    }

    @Override
    public long activePlanViolations() {
        return repository.activePlanViolations();
    }

    @Override
    public long remainingRowsOf(UUID userId) {
        return repository.remainingRowsOf(userId);
    }

    @Override
    public void erasePlanData(@Nullable UUID userId) {
        if (userId!=null) transactions.executeWithoutResult(status -> repository.erase(userId));
    }

    // ------------------------------------------------------------------ PlanPublicationPort (Story 2.7)

    @Override
    public Optional<Terms> publishableTerms(@Nullable UUID userId, UUID intentId) {
        if (userId==null || !eligibleUser(userId)) return Optional.empty();
        return transactions.execute(status -> currentTerms(userId,intentId,false,CONDITION_FEATURES));
    }

    @Override
    public Optional<PlanPublicationPort.ActivePlan> activePlan(@Nullable UUID userId) {
        if (userId==null) return Optional.empty();
        return repository.activePlan(userId).map(p -> new PlanPublicationPort.ActivePlan(p.planId(),p.intentId(),
                p.portfolioId(),p.p0(),p.t0(),p.endAt(),p.period().name(),p.rate(),p.createdAt()));
    }

    @Override
    public Optional<PlanPublicationPort.LatestPlan> latestPlan(@Nullable UUID userId) {
        if (userId==null) return Optional.empty();
        return ends.latestPlan(userId);
    }

    @Override
    public Optional<PlanPublicationPort.LatestPlan> historicalPlan(UUID userId, UUID planId) {
        return ends.historicalPlan(userId,planId);
    }
    @Override
    public java.util.List<PlanPublicationPort.LatestPlan> planHistory(UUID userId) { return ends.history(userId); }
    @Override
    public void invalidatePreparedConditions(UUID userId) {
        requireCallerTransaction();
        repository.invalidateOpen(userId);
        repository.invalidateConfirmations(userId);
    }

    @Override
    public Optional<PlanPublicationPort.PlanCompletion> completePlan(UUID userId, UUID planId, boolean dataGap) {
        return completions.completePlan(userId,planId,dataGap);
    }

    @Override
    public boolean recordCompletionObservation(UUID userId, UUID planId, UUID portfolioSnapshotId, String value,
            Instant measuredAt) {
        return completions.recordObservation(userId,planId,portfolioSnapshotId,value,measuredAt);
    }

    @Override
    public boolean recordCompletionComparison(UUID userId, UUID planId, String comparison, @Nullable String reason,
            @Nullable UUID completionCalculationId, @Nullable Integer postEndCashflowCount) {
        return completions.recordComparison(userId,planId,comparison,reason,completionCalculationId,
                postEndCashflowCount);
    }

    @Override
    public Optional<PlanPublicationPort.PlanCompletion> completion(UUID userId, UUID planId, boolean forUpdate) {
        return completions.completion(userId,planId,forUpdate);
    }

    @Override
    public void lockPlanWrites(UUID userId) {
        requireCallerTransaction();
        repository.lockUser(userId);
    }

    @Override
    public Optional<UUID> createActivePlan(UUID userId, Terms expected) {
        requireCallerTransaction();
        if (!eligibleUser(userId)) return Optional.empty();
        // The intent row is locked here: a racing confirmation's replacement waits for this commit.
        var current=currentTerms(userId,expected.intentId(),true,CONDITION_FEATURES).orElse(null);
        if (current==null || !current.equals(expected)) return Optional.empty();
        var row=repository.intentTerms(userId,expected.intentId(),false).orElseThrow();
        if (!row.endAt().isAfter(repository.databaseNow())) return Optional.empty();
        UUID planId=UUID.randomUUID();
        // A concurrent second insert for the same portfolio fails on the partial unique index and that
        // exception rolls the caller's whole publication back.
        repository.insertPlan(planId,userId,row);
        repository.linkPlan(planId,row.intentId());
        if (repository.consumeIntent(userId,expected.intentId(),planId)!=1)
            throw new IllegalStateException("intent changed under its row lock");
        return Optional.of(planId);
    }

    /**
     * The intent's terms while it is still publishable: OPEN, its snapshot provenance still the current
     * first official snapshot with the same membership, connection and policies, and no active plan on
     * its portfolio.
     *
     * <p>The end time is not judged here with the application clock. The publication judges it against
     * the database clock and answers {@code PLAN_PERIOD_ENDED} on its own; {@link #createActivePlan}
     * checks it again against the database clock under the intent's row lock.
     */
    private Optional<Terms> currentTerms(UUID userId, UUID intentId, boolean lock,
            java.util.Set<RestrictionFeature> features) {
        return currentTerms(userId,intentId,lock,features,java.util.Set.of());
    }

    private Optional<Terms> currentTerms(UUID userId, UUID intentId, boolean lock,
            java.util.Set<RestrictionFeature> features, java.util.Set<UUID> excluding) {
        var row=repository.intentTerms(userId,intentId,lock).orElse(null);
        if (row==null || !"OPEN".equals(row.status())
                || !CALENDAR_POLICY.equals(row.calendarPolicy()) || !CALCULATION_POLICY.equals(row.calculationPolicy())
                || repository.activePlanForPortfolio(row.portfolioId())) return Optional.empty();
        // intent 자신의 portfolio·연결(Story 3.2): 둘 중 하나의 제한도 그 발행을 막는다.
        if (restrictions.blocks(features,subjectOf(row.portfolioId(),row.connectionId()),excluding))
            return Optional.empty();
        var s=eligibleSnapshot(userId).orElse(null);
        if (s==null || !s.portfolioId().equals(row.portfolioId()) || !s.snapshotId().equals(row.snapshotId())
                || !s.membershipId().equals(row.membershipId()) || !s.connectionId().equals(row.connectionId())
                || !s.providerAccountId().equals(row.providerAccountId())
                || !s.sourceSnapshotId().equals(row.sourceSnapshotId()) || !s.decimal().equals(row.p0())
                || !s.measuredAt().equals(row.t0()) || !s.sourcePolicyVersion().equals(row.sourcePolicyVersion())
                || !s.portfolioPolicyVersion().equals(row.portfolioPolicyVersion())) return Optional.empty();
        return Optional.of(new Terms(row.intentId(),row.portfolioId(),row.snapshotId(),row.membershipId(),
                row.connectionId(),row.providerAccountId(),row.sourceSnapshotId(),row.p0(),row.t0(),row.endAt(),
                row.period().name(),row.rate(),row.sourcePolicyVersion(),row.portfolioPolicyVersion(),
                row.calendarPolicy(),row.calculationPolicy()));
    }

    private static void requireCallerTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("plan publication commands join the caller's transaction");
    }

    private static PlanConditionApi.ActivePlan view(PlanConditionRepository.ActivePlan p) {
        return new PlanConditionApi.ActivePlan(p.planId(),p.status(),p.p0(),p.t0(),p.endAt(),p.period(),p.rate(),
                p.basisSnapshotId(),p.intentId(),p.createdAt());
    }

    private @Nullable String readCanonicalHash(String hash, UUID userId, String sessionId) {
        return repository.confirmation(hash,userId,sha256(sessionId)).map(PlanConditionRepository.ConfirmationRow::canonicalHash)
                .orElse(null);
    }

    /** 계정 자신의 쓰기 접근만 본다. 제한은 호출자가 자기 기능으로 따로 본다. */
    private boolean writableUser(@Nullable UUID userId) {
        return userId!=null && accounts.decide(userId,userId,AccountAccessKind.WRITE)==AccessDecision.ALLOWED;
    }

    /** 계획 조건·첫 발행이 보는 기능(Story 3.2). */
    private static final java.util.Set<RestrictionFeature> CONDITION_FEATURES=java.util.Collections.unmodifiableSet(
            java.util.EnumSet.of(RestrictionFeature.SYNC,RestrictionFeature.CALCULATION_DISPLAY));

    /** 초기 수집이 보는 기능(Story 3.2). */
    private static final java.util.Set<RestrictionFeature> SYNC_ONLY=java.util.Collections.unmodifiableSet(
            java.util.EnumSet.of(RestrictionFeature.SYNC));

    private boolean eligibleUser(@Nullable UUID userId) {
        return userId!=null && accounts.decide(userId,userId,AccountAccessKind.WRITE)==AccessDecision.ALLOWED
                && eligibleRestrictions(RestrictionSubject.none().withCalculationPolicy(CALCULATION_POLICY));
    }

    /**
     * 계획 조건을 막는 기능(Story 3.2): 동기화 — 조건은 발행으로만 이어진다 — 와 계산 표시 — 미리보기는 정책
     * {@code CALC-1.0}의 계산을 보인다. 신규 연결 제한은 막지 않는다: 조건은 이미 있는 연결 위에서 돈다(NFR-17의 독립성).
     * 호출자가 가진 차원만 넘기고, 판독 실패는 예외다(fail-closed).
     */
    private boolean eligibleRestrictions(RestrictionSubject subject) {
        return !restrictions.blocks(CONDITION_FEATURES,subject);
    }

    private static RestrictionSubject subjectOf(UUID portfolioId, UUID connectionId) {
        return RestrictionSubject.none().withPortfolio(portfolioId).withConnection(connectionId)
                .withCalculationPolicy(CALCULATION_POLICY);
    }

    /**
     * 계획 조건의 기준 snapshot. 계획이 하나도 없으면 지금 활성 연결의 Bybit 계정의 기준 snapshot이고(Story 2.17 — 한 계정만
     * 쓴 portfolio에서는 첫 공식 snapshot), 종단 계획이 있으면 후속 기준(Story 2.15)이다.
     *
     * <p>기준 snapshot은 연결 ID가 아니라 provider account·portfolio로 받아들인다(Story 2.17). 같은 main UID로 다시 연결하면
     * 이전 연결로 받은 snapshot이 그대로 기준이고, 돌려주는 값의 membership·연결은 <b>지금 활성</b> membership·연결로 바꾼다
     * — 그 값이 확인·intent·{@code plan_definition}에 실려 새 계획은 발행 시점의 활성 연결로 수집하고 기록한다. 다른 계정의
     * snapshot은 기준이 되지 않는다(그 계정의 snapshot이 아직 없으면 빈 값). source snapshot의 provenance 대조는 snapshot 자신의
     * 연결로 한다.
     */
    private Optional<FirstOfficialSnapshot> eligibleSnapshot(UUID userId) {
        var latest=ends.latestPlan(userId).orElse(null);
        var connection=sources.snapshotConnection(userId);
        if (connection.isEmpty()) return Optional.empty();
        var first=latest==null ? portfolios.accountBaselineSnapshot(userId,connection.get().providerAccountId())
                : sync.currentNextBaseline(userId).filter(b -> latest.plan().planId().equals(b.predecessorPlanId())
                        && latest.plan().portfolioId().equals(b.portfolioId())
                        && (("ENDED_EARLY".equals(latest.status()) && latest.end()!=null && latest.end().endedAt().equals(b.terminalAt()))
                            || ("COMPLETED".equals(latest.status()) && latest.completion()!=null
                                && latest.completion().transitionedAt().equals(b.terminalAt()))))
                    .flatMap(b -> portfolios.ownedSnapshot(userId,b.snapshotId())
                            .filter(s -> s.sourceSnapshotId().equals(b.sourceSnapshotId())));
        var active=portfolios.activeSource(userId);
        if (first.isEmpty() || active.isEmpty()) return Optional.empty();
        var s=first.get(); var m=active.get(); var c=connection.get();
        var source=sync.sourceSnapshot(userId,s.sourceSnapshotId());
        // 같은 계정·portfolio의 지금 활성 membership·연결이어야 한다 — 연결 ID는 같지 않아도 된다(Story 2.17).
        if (!m.portfolioId().equals(s.portfolioId()) || !m.connectionId().equals(c.connectionId())
                || !m.providerAccountId().equals(s.providerAccountId())
                || !c.providerAccountId().equals(s.providerAccountId())
                || !c.policyVersion().equals(s.sourcePolicyVersion())
                || !s.sourcePolicyVersion().equals(s.portfolioPolicyVersion()) || source.isEmpty()
                || !source.get().userId().equals(userId) || !source.get().connectionId().equals(s.connectionId())
                || !source.get().providerAccountId().equals(s.providerAccountId())
                || !source.get().inputStatus().equals("VERIFIED")
                || !source.get().policyVersion().equals(s.sourcePolicyVersion())) return Optional.empty();
        try {
            BigDecimal amount=new BigDecimal(s.decimal());
            if (amount.signum()<=0 || amount.precision()>38 || Math.max(amount.scale(),0)>18) return Optional.empty();
        } catch (NumberFormatException invalid) { return Optional.empty(); }
        if (s.membershipId().equals(m.membershipId()) && s.connectionId().equals(c.connectionId())) return first;
        // 재연결 뒤(Story 2.17): snapshot·source·값·시각은 그대로, 수집·기록할 membership·연결만 지금 활성의 것이다.
        return Optional.of(new FirstOfficialSnapshot(s.portfolioId(),s.snapshotId(),s.decimal(),s.measuredAt(),
                s.composedAt(),s.publishedAt(),m.membershipId(),c.connectionId(),s.providerAccountId(),
                s.sourceSnapshotId(),s.sourcePolicyVersion(),s.portfolioPolicyVersion()));
    }

    private boolean sameSnapshot(UUID userId, FirstOfficialSnapshot expected) {
        return eligibleSnapshot(userId).map(s -> s.snapshotId().equals(expected.snapshotId())
                && s.sourceSnapshotId().equals(expected.sourceSnapshotId())
                && s.portfolioId().equals(expected.portfolioId())
                && s.membershipId().equals(expected.membershipId())
                && s.connectionId().equals(expected.connectionId())
                && s.providerAccountId().equals(expected.providerAccountId())
                && s.decimal().equals(expected.decimal())
                && s.measuredAt().equals(expected.measuredAt())
                && s.sourcePolicyVersion().equals(expected.sourcePolicyVersion())
                && s.portfolioPolicyVersion().equals(expected.portfolioPolicyVersion())).orElse(false);
    }

    private boolean confirmationStillValid(UUID userId, PlanConditionRepository.ConfirmationRow c) {
        var s=eligibleSnapshot(userId).orElse(null);
        if (s==null || !s.portfolioId().equals(c.portfolioId()) || !s.snapshotId().equals(c.snapshotId())
                || !s.membershipId().equals(c.membershipId()) || !s.connectionId().equals(c.connectionId())
                || !s.providerAccountId().equals(c.providerAccountId())
                || !s.sourceSnapshotId().equals(c.sourceSnapshotId()) || !s.decimal().equals(c.p0())
                || !s.measuredAt().equals(c.t0())
                || !s.sourcePolicyVersion().equals(c.sourcePolicyVersion())
                || !s.portfolioPolicyVersion().equals(c.portfolioPolicyVersion())) return false;
        try {
            LocalDateTime local=LocalDateTime.ofInstant(c.endAt(),ZONE);
            if (!parseEnd(local.format(LOCAL_TIME),c.t0()).equals(c.endAt())) return false;
            return canonicalRate(c.rate()).equals(c.rate());
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static Instant parseEnd(String raw, Instant t0) {
        if (raw==null || raw.length()>32) throw new IllegalArgumentException("invalid time");
        LocalDateTime local;
        try { local=LocalDateTime.parse(raw,LOCAL_TIME); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("invalid time",invalid); }
        if (local.getSecond()!=0 || local.getNano()!=0) throw new IllegalArgumentException("minute precision required");
        var offsets=ZONE.getRules().getValidOffsets(local);
        if (offsets.size()!=1) throw new IllegalArgumentException("ambiguous local time");
        LocalDateTime start=LocalDateTime.ofInstant(t0,ZONE);
        LocalDateTime firstDaily=start.plusDays(1);
        LocalDateTime maximum=start.plusYears(1);
        if (local.isBefore(firstDaily) || local.isAfter(maximum)) throw new IllegalArgumentException("time out of range");
        Instant end=local.toInstant(offsets.getFirst()).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (!end.isAfter(Instant.now())) throw new IllegalArgumentException("end time must be in the future");
        return end;
    }

    private static String canonicalRate(String raw) {
        if (raw==null || raw.length()>32 || !raw.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))
            throw new IllegalArgumentException("invalid decimal");
        BigDecimal value;
        try { value=new BigDecimal(raw); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("invalid decimal",invalid); }
        if (value.signum()<0 || value.compareTo(BigDecimal.TEN)>0 || Math.max(value.scale(),0)>12
                || value.precision()>18) throw new IllegalArgumentException("rate out of range");
        BigDecimal normalized=value.stripTrailingZeros();
        return normalized.signum()==0 ? "0" : normalized.toPlainString();
    }

    private String canonical(UUID userId, FirstOfficialSnapshot s, Instant end, Period period, String rate) {
        return s.portfolioId()+"|"+s.snapshotId()+"|"+s.membershipId()+"|"+s.connectionId()+"|"
                +s.providerAccountId()+"|"+s.sourceSnapshotId()+"|"+s.sourcePolicyVersion()+"|"
                +s.portfolioPolicyVersion()+"|"+s.decimal()+"|"
                +s.measuredAt().toEpochMilli()+"|"+end.toEpochMilli()+"|"+period.name()+"|"+rate+"|"
                +CALENDAR_POLICY+"|"+CALCULATION_POLICY+"|"+sync.currentNextBaseline(userId).map(b -> b.predecessorPlanId()+"|"+b.jobId()).orElse("FIRST");
    }
    private static String randomRef() {
        byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static final class StaleState extends RuntimeException {
        private static final long serialVersionUID=1L;
    }
}
