package com.bokrilog.source.internal;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.identity.AccountDeletionApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceProvider;
import com.bokrilog.source.SourceRejectionReason;
import com.bokrilog.source.SourceVerification;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link SourceConnectionApi} 구현. 구현은 {@code internal}의 package-private 클래스다
 * ({@code AccountLifecycleService}와 같은 배치).
 *
 * <p><b>검증과 저장이 갈라져 있다.</b> {@link #verify}는 {@link BybitAdapter}에 위임하고
 * 데이터베이스에 닿지 않으며, {@link #store}는 데이터베이스만 쓰고 외부로 나가지 않는다. 그 분리가
 * "외부 호출 동안 DB 트랜잭션도 계정 잠금도 열지 않는다"를 배선으로 성립시킨다.
 *
 * <p>{@code identity}에는 공개 API로만 닿는다. 삭제 상태·소유권 판정은
 * {@link AccountAccessApi#decide}가 답하고, 이 클래스는 {@code identity.internal}이나 소유 테이블에
 * 닿지 않는다.
 *
 * <p>트랜잭션을 <b>열지 않는다.</b> {@link #store}는 호출자의 경계에 참여하며, 그 경계의 주인은
 * 허가 포트다({@code ActionGrantService}). 여기서 {@code @Transactional}을 붙이면 허가 소비와
 * 저장이 같은 경계에서 커밋된다는 계약이 애노테이션 둘 사이로 숨는다.
 */
@Service
class SourceConnectionService implements SourceConnectionApi {

    private final SourceConnectionRepository repository;

    private final CredentialCipher cipher;

    private final BybitAdapter bybit;

    private final AccountAccessApi accountAccess;

    private final AccountDeletionApi accountDeletion;

    SourceConnectionService(
            SourceConnectionRepository repository,
            CredentialCipher cipher,
            BybitAdapter bybit,
            AccountAccessApi accountAccess,
            AccountDeletionApi accountDeletion) {
        this.accountDeletion = accountDeletion;
        this.repository = repository;
        this.cipher = cipher;
        this.bybit = bybit;
        this.accountAccess = accountAccess;
    }

    @Override
    public boolean isConnectable() {
        /*
         * 두 조건이 모두 있어야 한다 — 봉할 키와 물어볼 주소·기대할 IP. 하나만 보면 나머지가 빈
         * 스택에서 제공자 왕복이 먼저 일어난다.
         */
        return this.cipher.isAvailable() && this.bybit.isConfigured();
    }

    @Override
    public boolean hasActiveConnection(@Nullable UUID userId) {
        return findActiveConnection(userId).isPresent();
    }

    @Override
    public Optional<ActiveConnection> findActiveConnection(@Nullable UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return this.repository.findActiveConnection(userId, SourceProvider.BYBIT.name());
    }

    @Override
    public Optional<SnapshotConnection> snapshotConnection(@Nullable UUID userId) {
        if (userId == null) return Optional.empty();
        return this.repository.activeSnapshotConnection(userId)
            .map(row -> new SnapshotConnection(row.connectionId(), row.providerAccountId(),
                    row.policyVersion()));
    }

    @Override
    public EquityResult readEquity(UUID userId, UUID connectionId) {
        if (this.accountAccess.decide(userId, userId, AccountAccessKind.SENSITIVE)
                != AccessDecision.ALLOWED) {
            return new EquityResult.Rejected(SourceRejectionReason.NOT_PERMITTED);
        }
        Optional<SourceConnectionRepository.SnapshotRow> row =
                this.repository.snapshotConnection(userId, connectionId);
        if (row.isEmpty()) return new EquityResult.Rejected(SourceRejectionReason.NOT_PERMITTED);
        var r = row.get();
        var opened = this.cipher.open(new CredentialCipher.Aad(userId, connectionId,
                r.credentialId(), r.keyVersion(), r.algorithmVersion()), r.nonce(), r.ciphertext());
        if (opened.isEmpty()) return new EquityResult.Rejected(SourceRejectionReason.UNAVAILABLE);
        String[] pair = opened.get();
        var result = this.bybit.readEquity(new SourceCredentials(pair[0], pair[1]));
        if (result instanceof EquityResult.Observed observed
                && !r.providerAccountRef().equals(observed.value().providerAccountRef())) {
            return new EquityResult.Rejected(SourceRejectionReason.SUB_ACCOUNT);
        }
        return result;
    }

    @Override
    public CollectionResult collectInitialInputs(UUID userId, UUID connectionId, Instant t0,
            @Nullable Instant fixedTc, Consumer<Instant> scopeConsumer,
            Consumer<com.bokrilog.source.SourceConnectionApi.CollectionPage> pageConsumer,
            java.util.function.BooleanSupplier alive) {
        if (this.accountAccess.decide(userId, userId, AccountAccessKind.SENSITIVE)
                != AccessDecision.ALLOWED)
            return new CollectionResult.Rejected(SourceRejectionReason.NOT_PERMITTED);
        Optional<SourceConnectionRepository.SnapshotRow> row =
                this.repository.snapshotConnection(userId, connectionId);
        if (row.isEmpty()) return new CollectionResult.Rejected(SourceRejectionReason.NOT_PERMITTED);
        var r = row.get();
        var opened = this.cipher.open(new CredentialCipher.Aad(userId, connectionId,
                r.credentialId(), r.keyVersion(), r.algorithmVersion()), r.nonce(), r.ciphertext());
        if (opened.isEmpty()) return new CollectionResult.Rejected(SourceRejectionReason.UNAVAILABLE);
        String[] pair = opened.get();
        CollectionResult result = this.bybit.collectInitialInputs(
                new SourceCredentials(pair[0], pair[1]), r.providerAccountRef(), t0,
                fixedTc, scopeConsumer, pageConsumer, alive);
        if (result instanceof CollectionResult.Collected collected
                && !r.providerAccountRef().equals(collected.value().providerAccountRef()))
            return new CollectionResult.Rejected(SourceRejectionReason.SUB_ACCOUNT);
        return result;
    }

    @Override
    public WindowCollectionResult collectWindows(UUID userId, UUID connectionId, Instant t0, Instant tc,
            java.util.List<CollectionWindow> windows, boolean includeWallet,
            Consumer<com.bokrilog.source.SourceConnectionApi.CollectionPage> pageConsumer,
            java.util.function.BooleanSupplier alive) {
        if (this.accountAccess.decide(userId, userId, AccountAccessKind.SENSITIVE)
                != AccessDecision.ALLOWED)
            return new WindowCollectionResult.Rejected(SourceRejectionReason.NOT_PERMITTED, 0, 0);
        Optional<SourceConnectionRepository.SnapshotRow> row =
                this.repository.snapshotConnection(userId, connectionId);
        if (row.isEmpty()) return new WindowCollectionResult.Rejected(SourceRejectionReason.NOT_PERMITTED, 0, 0);
        var r = row.get();
        var opened = this.cipher.open(new CredentialCipher.Aad(userId, connectionId,
                r.credentialId(), r.keyVersion(), r.algorithmVersion()), r.nonce(), r.ciphertext());
        if (opened.isEmpty()) return new WindowCollectionResult.Rejected(SourceRejectionReason.UNAVAILABLE, 0, 0);
        String[] pair = opened.get();
        WindowCollectionResult result = this.bybit.collectWindows(new SourceCredentials(pair[0], pair[1]),
                r.providerAccountRef(), t0, tc, windows, includeWallet, pageConsumer, alive);
        if (result instanceof WindowCollectionResult.Collected collected
                && !r.providerAccountRef().equals(collected.value().providerAccountRef()))
            return new WindowCollectionResult.Rejected(SourceRejectionReason.SUB_ACCOUNT,
                    collected.value().pageCount(), collected.value().retryCount());
        return result;
    }

    @Override
    public MarketTimeResult marketTime() {
        return this.bybit.marketTime();
    }

    @Override
    public InstrumentResult spotInstrument(String symbol) {
        return this.bybit.spotInstrument(symbol);
    }

    @Override
    public CandleResult spotMinuteCandles(String symbol, Instant start, Instant end) {
        return this.bybit.spotMinuteCandles(symbol, start, end);
    }

    @Override
    public SourceVerification verify(SourceProvider provider, SourceCredentials credentials) {
        if (provider != SourceProvider.BYBIT) {
            // 닫힌 enum이라 여기 오지 않는다. 값이 늘어나는 날 조용히 Bybit로 흐르지 않게 한다.
            return new SourceVerification.Rejected(SourceRejectionReason.UNAVAILABLE);
        }
        return this.bybit.verify(credentials);
    }

    @Override
    public StoreOutcome store(
            @Nullable UUID userId,
            SourceProvider provider,
            SourceVerification.Verified verified,
            SourceCredentials credentials) {
        if (userId == null || provider != SourceProvider.BYBIT) {
            return rejected(SourceRejectionReason.NOT_PERMITTED);
        }
        /*
         * 삭제 상태를 여기서 다시 본다.
         *
         * 허가 포트도 소비 앞에서 같은 판정을 하지만(`ActionGrantService.attempt`) 그 사이에
         * 외부 호출이 있었고, 무엇보다 이 저장은 그 포트 없이도 호출될 수 있는 공개 API다.
         * 삭제가 저장과 경쟁하면 삭제가 이긴다는 계약은 판정이 저장과 같은 트랜잭션에 있을 때만
         * 성립한다.
         */
        if (this.accountAccess.decide(userId, userId, AccountAccessKind.SENSITIVE)
                != AccessDecision.ALLOWED) {
            return rejected(SourceRejectionReason.NOT_PERMITTED);
        }
        /*
         * 판정 순서(Story 2.17, RECONNECT-1.0): 활성 연결 → 다른 사용자의 main UID → 그 사용자의 기존 제공자 계정 재사용 →
         * 새 제공자 계정. 해제한 연결이 있다는 사실은 더 이상 거부 사유가 아니다 — 활성 계획과 main UID의 대조는 계획
         * 잠금을 쥔 호출자가 저장 뒤 같은 트랜잭션에서 하고, 어긋나면 그 트랜잭션을 통째로 되돌린다.
         *
         * 활성 연결 검사는 사전 확인이다. 권위는 부분 유일 색인(`source_connection_active_user_key`)이며, 두 탭이 함께
         * 여기를 지나면 진 쪽의 INSERT가 그 색인에서 예외로 끝나 호출자 트랜잭션 전체가 롤백된다.
         */
        if (this.repository.findActiveConnection(userId, provider.name()).isPresent()) {
            return rejected(SourceRejectionReason.NOT_PERMITTED);
        }

        String providerName = provider.name();
        Optional<SourceConnectionRepository.ProviderAccountRow> existing =
                this.repository.findProviderAccount(providerName, verified.providerAccountRef());
        if (existing.isPresent() && !existing.get().userId().equals(userId)) {
            /*
             * 그 메인 UID는 다른 BokriLog 계정의 것이다. 원인만 알리고 상대 계정은 드러내지
             * 않는다 — 값이 하나이므로 이 응답에서 소유자도 제공자 계정의 다른 사실도 복원되지
             * 않는다.
             */
            return rejected(SourceRejectionReason.PROVIDER_ACCOUNT_TAKEN);
        }

        UUID providerAccountId = existing.map(SourceConnectionRepository.ProviderAccountRow::providerAccountId)
                .orElseGet(UUID::randomUUID);
        if (existing.isEmpty()) {
            /*
             * 새 제공자 계정이다. 동시 요청 둘이 여기 함께 들어오면 유일 색인이 하나를 거절하고
             * 그 예외가 호출자의 트랜잭션을 통째로 되돌린다 — 값으로 접지 않는 것이 요점이다
             * (`V4__source_connection.sql`).
             */
            this.repository.insertProviderAccount(
                    providerAccountId, userId, providerName, verified.providerAccountRef());
        }

        // 직전 해제 연결과 같은 계정인가 — 새 연결 행을 넣기 전에 읽는다(A→B→A의 마지막은 B와 다른 계정이다).
        boolean sameAccountAsRevoked = this.repository.latestRevokedProviderAccount(userId)
                .map(providerAccountId::equals).orElse(false);
        UUID connectionId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        Optional<CredentialCipher.Sealed> sealed =
                this.cipher.seal(
                        userId, connectionId, credentialId, credentials.apiKey(), credentials.apiSecret());
        if (sealed.isEmpty()) {
            // key ring이 없다. 평문으로 저장하는 경로를 만들지 않는다.
            return rejected(SourceRejectionReason.UNAVAILABLE);
        }

        this.repository.insertConnection(
                connectionId,
                userId,
                providerAccountId,
                providerName,
                verified.verifiedAt(),
                verified.policyVersion());
        this.repository.insertCredential(credentialId, connectionId, sealed.get());
        return new StoreOutcome.Stored(connectionId, providerAccountId, sameAccountAsRevoked);
    }

    @Override
    public Optional<ConnectionStatus> currentConnection(@Nullable UUID userId) {
        return userId == null ? Optional.empty() : this.repository.currentConnection(userId);
    }

    @Override
    public boolean hasRevokedConnection(@Nullable UUID userId) {
        return userId != null && this.repository.hasRevoked(userId);
    }

    @Override
    public boolean isRevoked(@Nullable UUID userId, @Nullable UUID connectionId) {
        return userId != null && connectionId != null && this.repository.isRevoked(userId, connectionId);
    }

    @Override
    public java.util.Set<UUID> sameAccountConnections(UUID connectionId) {
        return this.repository.sameAccountConnections(connectionId);
    }

    @Override
    public Revocation revoke(UUID userId, UUID connectionId, String idempotencyKey, String requestHash,
            @Nullable Instant notBefore) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("the revocation joins the caller's transaction");
        // The row lock, then the state judged again under it: a revocation that won the race left nothing to do.
        var locked = this.repository.lockActive(userId, connectionId).orElse(null);
        if (locked == null) return new Revocation.NotActive();
        /*
         * The credential first: once it is gone no worker can decrypt anything, and every re-check that joins it
         * (`snapshotConnection`) is empty. Exactly one row — a connection has exactly one credential (V4).
         */
        if (this.repository.deleteCredential(connectionId) != 1)
            throw new IllegalStateException("the active connection had no credential");
        Instant revokedAt = this.repository.markRevoked(userId, connectionId, idempotencyKey, requestHash,
                        notBefore)
                .orElseThrow(() -> new IllegalStateException("the connection changed under its row lock"));
        return new Revocation.Revoked(connectionId, locked, revokedAt);
    }

    @Override
    public RevocationReplay revocationReplay(@Nullable UUID userId, String idempotencyKey, String requestHash) {
        if (userId == null) return new RevocationReplay.None();
        var stored = this.repository.revocationByKey(userId, idempotencyKey).orElse(null);
        if (stored == null) return new RevocationReplay.None();
        if (!stored.requestHash().equals(requestHash)) return new RevocationReplay.Conflict();
        return new RevocationReplay.Stored(stored.connectionId(), stored.revokedAt());
    }

    @Override
    public void eraseSourceData(@Nullable UUID userId) {
        if (userId == null) {
            return;
        }
        this.repository.eraseOf(userId);
    }

    @Override
    public int eraseCredentials(@Nullable UUID userId) {
        if (userId == null) {
            return 0;
        }
        // 삭제 전환을 지난 계정만 지운다 — 생명주기 상태를 잠금 아래에서 직접 본다(identity `eraseSocialLinks`와 같은 규칙).
        if (!this.accountDeletion.lockDeletionStarted(userId)) {
            return 0;
        }
        return this.repository.eraseCredentialsOf(userId);
    }

    @Override
    public long remainingCredentialsOf(UUID userId) {
        return this.repository.countCredentialsOf(userId);
    }

    private static StoreOutcome rejected(SourceRejectionReason reason) {
        return new StoreOutcome.Rejected(reason);
    }

    @Override
    public Optional<ConnectionTarget> connectionTarget(UUID connectionId) {
        return this.repository.connectionTarget(connectionId);
    }

    @Override
    public long countActiveConnections() {
        return this.repository.countActive();
    }

    @Override
    public CredentialProbe probeCredentials(int limit) {
        int sampled = 0;
        int opened = 0;
        for (var row : this.repository.credentialSample(Math.max(0, limit))) {
            sampled++;
            var pair = this.cipher.open(new CredentialCipher.Aad(row.userId(), row.connectionId(), row.credentialId(),
                    row.keyVersion(), row.algorithmVersion()), row.nonce(), row.ciphertext());
            if (pair.isPresent()) {
                opened++;
                // 평문은 세기만 하고 버린다. 문자열은 지울 수 없으므로 참조를 바로 놓는다.
                java.util.Arrays.fill(pair.get(), null);
            }
        }
        return new CredentialProbe(sampled, opened, this.cipher.isAvailable());
    }

    @Override
    public long remainingRowsOf(UUID userId) {
        return this.repository.remainingRowsOf(userId);
    }
}
