package com.bokrilog.plan.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.plan.PlanConditionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.portfolio.PortfolioMembershipApi.FirstOfficialSnapshot;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.SyncJobApi;
import com.bokrilog.sync.InitialInputsPlanPort;
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
import org.springframework.transaction.support.TransactionTemplate;

@Service
class PlanConditionService implements PlanConditionApi, InitialInputsPlanPort {
    static final String CALENDAR_POLICY="CALENDAR-SEOUL-1.0";
    static final String CALCULATION_POLICY="CALC-1.0";
    static final ZoneId ZONE=ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter LOCAL_TIME=DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final long CONFIRMATION_LIFETIME_SECONDS=600;

    private final PlanConditionRepository repository;
    private final PortfolioMembershipApi portfolios;
    private final SyncJobApi sync;
    private final SourceConnectionApi sources;
    private final AccountAccessApi accounts;
    private final OperationalRestrictionApi restrictions;
    private final TransactionTemplate transactions;

    PlanConditionService(PlanConditionRepository repository, PortfolioMembershipApi portfolios,
            @Lazy SyncJobApi sync, SourceConnectionApi sources, AccountAccessApi accounts,
            OperationalRestrictionApi restrictions, PlatformTransactionManager manager) {
        this.repository=repository; this.portfolios=portfolios; this.sync=sync; this.sources=sources;
        this.accounts=accounts; this.restrictions=restrictions;
        this.transactions=new TransactionTemplate(manager);
    }

    @Override
    public State state(@Nullable UUID userId) {
        if (userId==null) return new State(false,"NOT_ELIGIBLE",null,null);
        return transactions.execute(status -> {
            repository.lockUser(userId);
            Instant now=Instant.now();
            repository.invalidateExpired(userId,now);
            if (!eligibleUser(userId)) {
                repository.invalidateOpen(userId);
                return new State(false,"NOT_ELIGIBLE",null,null);
            }
            FirstOfficialSnapshot s=eligibleSnapshot(userId).orElse(null);
            if (s==null) {
                repository.invalidateOpen(userId);
                return new State(false,"NOT_ELIGIBLE",null,null);
            }
            var source=sync.sourceSnapshot(userId,s.sourceSnapshotId()).orElseThrow();
            var snapshot=new Snapshot(s.decimal(),s.measuredAt(),s.publishedAt(),s.portfolioId(),s.snapshotId(),
                    s.membershipId(),s.connectionId(),s.providerAccountId(),s.sourceSnapshotId(),
                    source.inputStatus(),source.policyVersion(),s.portfolioPolicyVersion());
            repository.invalidateOpenUnlessCurrentSnapshot(userId,s,now);
            return new State(true,null,snapshot,repository.openIntent(userId).orElse(null));
        });
    }

    @Override
    public Optional<Preview> preview(@Nullable UUID userId, @Nullable String sessionId,
            @Nullable PreviewInput input) {
        if (userId==null || sessionId==null || sessionId.isBlank() || input==null
                || input.period()==null || input.targetRate()==null || input.endAtSeoul()==null
                || !eligibleUser(userId)) return Optional.empty();
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
        String hash=sha256(canonical(s,end,period,rate));
        try {
            Boolean stored=transactions.execute(status -> {
                repository.lockUser(userId);
                Instant now=Instant.now();
                repository.invalidateExpired(userId,now);
                if (!accounts.lockWriteAccess(userId) || !eligibleRestrictions() || !sameSnapshot(userId,s)) {
                    repository.invalidateOpen(userId);
                    return false;
                }
                repository.insertConfirmation(sha256(ref),userId,sha256(sessionId),hash,s,end,period,rate,expires);
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
                || !validKey(idempotencyKey) || !eligibleUser(userId)) return new Confirmation(false,false,null);
        String confirmationHash=sha256(confirmationRef);
        try {
            return transactions.execute(status -> {
                repository.lockUser(userId);
                if (!accounts.lockWriteAccess(userId) || !eligibleRestrictions())
                    return new Confirmation(false,false,null);
                var c=repository.confirmation(confirmationHash,userId,sha256(sessionId)).orElse(null);
                if (c==null
                        || !c.calendarPolicy().equals(CALENDAR_POLICY)
                        || !c.calculationPolicy().equals(CALCULATION_POLICY)) return new Confirmation(false,false,null);
                var prior=repository.idempotency(userId,idempotencyKey).orElse(null);
                if (prior!=null) {
                    return new Confirmation(prior.canonicalHash().equals(c.canonicalHash()),
                            !prior.canonicalHash().equals(c.canonicalHash()),
                            repository.intent(userId,prior.intentId()).orElse(null));
                }
                if (!c.expiresAt().isAfter(Instant.now()) || c.consumedAt()!=null)
                    return new Confirmation(false,false,null);
                if (!eligibleUser(userId) || !confirmationStillValid(userId,c)) {
                    repository.invalidateOpen(userId);
                    return new Confirmation(false,false,null);
                }
                Instant now=Instant.now();
                if (!c.endAt().isAfter(now)) return new Confirmation(false,false,null);
                repository.invalidateExpired(userId,now);
                var current=repository.latestOpenForUpdate(userId).orElse(null);
                // A preview created before another tab's intent is a losing concurrent submission.
                if (current!=null && !current.createdAt().isBefore(c.issuedAt()))
                    return new Confirmation(false,true,current);
                UUID intentId=UUID.randomUUID();
                if (current!=null && repository.replace(userId,current.intentId(),intentId)!=1)
                    throw new StaleState();
                repository.saveIntent(intentId,userId,c);
                if (repository.idempotency(userId,idempotencyKey,c.canonicalHash(),intentId)!=1)
                    throw new DuplicateKeyException("idempotency conflict");
                repository.consume(confirmationHash);
                return new Confirmation(true,false,repository.intent(userId,intentId).orElse(null));
            });
        } catch (DuplicateKeyException | StaleState rejected) {
            var existing=repository.idempotency(userId,idempotencyKey).orElse(null);
            if (existing==null) return new Confirmation(false,false,null);
            return new Confirmation(existing.canonicalHash().equals(readCanonicalHash(confirmationHash,userId,sessionId)),
                    !existing.canonicalHash().equals(readCanonicalHash(confirmationHash,userId,sessionId)),
                    repository.intent(userId,existing.intentId()).orElse(null));
        }
    }

    @Override
    public Optional<CollectionIntent> collectionIntent(@Nullable UUID userId, UUID intentId) {
        if (userId == null) return Optional.empty();
        return transactions.execute(status -> {
            if (!eligibleUser(userId)) return Optional.empty();
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
        return collectionIntent(userId,intentId).map(i->new InitialInputsPlanPort.Intent(
                i.intentId(),i.portfolioId(),i.connectionId(),i.providerAccountId(),i.t0()));
    }

    @Override
    public void erasePlanData(@Nullable UUID userId) {
        if (userId!=null) transactions.executeWithoutResult(status -> repository.erase(userId));
    }

    private @Nullable String readCanonicalHash(String hash, UUID userId, String sessionId) {
        return repository.confirmation(hash,userId,sha256(sessionId)).map(PlanConditionRepository.ConfirmationRow::canonicalHash)
                .orElse(null);
    }

    private boolean eligibleUser(@Nullable UUID userId) {
        return userId!=null && accounts.decide(userId,userId,AccountAccessKind.WRITE)==AccessDecision.ALLOWED
                && eligibleRestrictions();
    }

    private boolean eligibleRestrictions() {
        return !restrictions.isRestricted(COMMAND)
                && !restrictions.isRestricted("first-snapshot")
                && !restrictions.isRestricted("bybit-connection-create");
    }

    private Optional<FirstOfficialSnapshot> eligibleSnapshot(UUID userId) {
        var first=portfolios.firstOfficialSnapshot(userId);
        var active=portfolios.activeSource(userId);
        var connection=sources.snapshotConnection(userId);
        if (first.isEmpty() || active.isEmpty() || connection.isEmpty()) return Optional.empty();
        var s=first.get(); var m=active.get(); var c=connection.get();
        var source=sync.sourceSnapshot(userId,s.sourceSnapshotId());
        if (!m.portfolioId().equals(s.portfolioId()) || !m.membershipId().equals(s.membershipId())
                || !m.connectionId().equals(s.connectionId()) || !m.providerAccountId().equals(s.providerAccountId())
                || !c.connectionId().equals(s.connectionId()) || !c.providerAccountId().equals(s.providerAccountId())
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
        return first;
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

    private static String canonical(FirstOfficialSnapshot s, Instant end, Period period, String rate) {
        return s.portfolioId()+"|"+s.snapshotId()+"|"+s.membershipId()+"|"+s.connectionId()+"|"
                +s.providerAccountId()+"|"+s.sourceSnapshotId()+"|"+s.sourcePolicyVersion()+"|"
                +s.portfolioPolicyVersion()+"|"+s.decimal()+"|"
                +s.measuredAt().toEpochMilli()+"|"+end.toEpochMilli()+"|"+period.name()+"|"+rate+"|"
                +CALENDAR_POLICY+"|"+CALCULATION_POLICY;
    }
    private static boolean validKey(@Nullable String key) {
        return key!=null && key.length()>=16 && key.length()<=128 && key.matches("[A-Za-z0-9._:-]+")
                && !key.contains("..") && !key.startsWith("-");
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
