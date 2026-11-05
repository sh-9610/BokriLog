package com.bokrilog.source;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * 금융 source 연결의 검증·저장·조회·삭제.
 *
 * <p><b>검증과 저장이 갈라져 있다.</b> {@link #verify}는 외부 호출만 하고 데이터베이스에 닿지
 * 않으며, {@link #store}는 데이터베이스만 쓰고 외부로 나가지 않는다. 한 메서드로 합치면 제공자
 * 왕복 동안 트랜잭션이나 계정 잠금이 열려 있게 되고, 그것이 AD-21과 이 스토리의 Always가 함께
 * 금지한 모양이다. 경계는 허가 포트가 소유하므로({@code ActionGrantService}) 호출자가 그 순서를
 * 지키는 것이 배선의 조건이다 — 먼저 열면 포트가 거절한다.
 *
 * <p>거부는 값이다. 예외로 알리지 않는 이유는 거절이 정상 경로이기 때문이며, 실패(구성 오류·
 * 저장소 장애)는 그대로 예외로 올라간다.
 */
public interface SourceConnectionApi {

    /** 활성 연결 하나의 요약. 자격정보도 식별자도 담지 않는다. */
    record ActiveConnection(UUID connectionId, Instant verifiedAt, String policyVersion) {}

    /** Worker가 받은 provider-neutral 관측값. 비밀과 원 응답은 포함하지 않는다. */
    record EquityObservation(String providerAccountRef, String decimal, Instant measuredAt, Instant receivedAt,
            String policyVersion) {}

    /** Provider-neutral inputs and completeness evidence for the initial calculation collection. */
    record CollectionEvent(@Nullable String sourceId, @Nullable String type, @Nullable String currency,
            @Nullable String cashFlow, @Nullable Instant transactionTime, @Nullable String accountType) {}
    record PageEvidence(String type, Instant startAt, Instant endAt, @Nullable String cursorHash,
            Instant requestedAt, Instant receivedAt, int count, @Nullable Instant firstEventAt,
            @Nullable Instant lastEventAt, boolean hasNextPage) {}
    record CollectionPage(PageEvidence evidence, List<CollectionEvent> events, boolean reviewRequired) {}
    record CollectionObservation(String providerAccountRef, String equityDecimal, Instant measuredAt,
            Instant receivedAt, Instant tc, int pageCount, boolean reviewRequired,
            @Nullable String failureCode, String policyVersion) {}
    sealed interface CollectionResult {
        record Collected(CollectionObservation value) implements CollectionResult {}
        record Rejected(SourceRejectionReason reason) implements CollectionResult {}
    }

    /** 활성 연결의 안정 식별자와 현재 revision. */
    record SnapshotConnection(UUID connectionId, UUID providerAccountId, String policyVersion) {}

    sealed interface EquityResult {
        record Observed(EquityObservation value) implements EquityResult {}
        record Rejected(SourceRejectionReason reason) implements EquityResult {}
    }

    Optional<SnapshotConnection> snapshotConnection(@Nullable UUID userId);

    /** 저장된 암호문을 복호한 뒤 한 번만 제공자에 묻는다. 호출자는 DB 트랜잭션 밖에서 호출한다. */
    EquityResult readEquity(UUID userId, UUID connectionId);

    /**
     * Reads current equity and every required Transaction Log page outside any database transaction. {@code alive} is
     * the caller's lease guard: the call budget's waits and page retries give up as soon as it turns false, and the
     * result is then a rejection the caller does not act on (it checks its own lease first).
     */
    CollectionResult collectInitialInputs(UUID userId, UUID connectionId, Instant t0,
            @Nullable Instant fixedTc, Consumer<Instant> scopeConsumer, Consumer<CollectionPage> pageConsumer,
            java.util.function.BooleanSupplier alive);

    /**
     * One Transaction Log window to read (Story 2.12): a closed product range {@code [start, end]} of at most seven
     * days inside {@code [t0, tc]}. Both types and every cursor of it are read.
     */
    record CollectionWindow(Instant start, Instant end) {}

    /**
     * What a collection of explicit windows observed (Story 2.12). The equity, its measurement and receipt times are
     * present exactly when the wallet was asked for. {@code pageCount} counts the pages handed on, {@code retryCount}
     * the page retries the call budget allowed.
     */
    record WindowCollection(String providerAccountRef, @Nullable String equityDecimal, @Nullable Instant measuredAt,
            @Nullable Instant receivedAt, Instant tc, int pageCount, int retryCount, boolean reviewRequired,
            @Nullable String failureCode, String policyVersion) {

        /** The observation in Story 2.4's shape. Only for a collection that asked for the wallet. */
        public CollectionObservation observation() {
            if (this.equityDecimal == null || this.measuredAt == null || this.receivedAt == null)
                throw new IllegalStateException("a collection without the wallet has no equity observation");
            return new CollectionObservation(this.providerAccountRef, this.equityDecimal, this.measuredAt,
                    this.receivedAt, this.tc, this.pageCount, this.reviewRequired, this.failureCode,
                    this.policyVersion);
        }
    }

    sealed interface WindowCollectionResult {
        record Collected(WindowCollection value) implements WindowCollectionResult {}
        /** Refused; the counts say how far it got (pages handed on and page retries), for the attempt's one line. */
        record Rejected(SourceRejectionReason reason, int pageCount, int retryCount) implements WindowCollectionResult {}
        /**
         * Stopped because {@code alive} turned false during a wait or before a call: the caller's lease no longer
         * holds. Not a provider answer — the caller ends nothing for it, as for a lost lease.
         */
        record Stopped(int pageCount, int retryCount) implements WindowCollectionResult {}
    }

    /**
     * The collection core every sync purpose shares (Story 2.12): the key facts, the account mode, the wallet's
     * totalEquity when {@code includeWallet}, and every required type and cursor of exactly the given windows, in
     * order, with {@code tc} as the fixed cutoff the caller already took from the server time. It never asks the time
     * API. A collection page that times out or answers 429, {@code 10006} or 5xx is asked again up to three times
     * (2, 10 and 30 seconds plus jitter, ERD-1.0.0 §2.5), only while {@code alive} holds; authentication, permission,
     * schema and semantic failures are not retried, and an IP ban (403) opens a ten-minute circuit over every Bybit
     * call. At most two collections run at once. The same access, connection and credential rules as
     * {@link #collectInitialInputs} apply; the credential is read once. Call it outside any database transaction.
     */
    WindowCollectionResult collectWindows(UUID userId, UUID connectionId, Instant t0, Instant tc,
            List<CollectionWindow> windows, boolean includeWallet, Consumer<CollectionPage> pageConsumer,
            java.util.function.BooleanSupplier alive);

    /*
     * Public market facts (Story 2.6).
     *
     * These three reads carry no user identifier, no credential and no signature. They are
     * provider-neutral: the caller sees a symbol, two asset codes, a status word, minute candles and
     * a content hash, never a provider payload or `retMsg`. Each call is sent once — no automatic
     * retry — and a transport or schema failure is a closed rejection reason. The caller performs
     * them outside any database transaction.
     */

    /** One spot instrument entry exactly as listed. {@code evidenceHash} covers the four fields. */
    record MarketInstrument(String symbol, String baseAsset, String quoteAsset, String status,
            String evidenceHash) {}

    /**
     * One minute candle. Prices are the provider's decimal strings (canonical-decimal shaped, not
     * yet normalized). {@code evidenceHash} covers the seven provider fields of the row.
     */
    record MarketCandle(Instant startAt, String open, String high, String low, String close,
            String volume, String turnover, String evidenceHash) {}

    sealed interface MarketTimeResult {
        record Observed(Instant serverTime) implements MarketTimeResult {}
        record Rejected(SourceRejectionReason reason) implements MarketTimeResult {}
    }

    sealed interface InstrumentResult {
        /** Every entry the provider listed for the query. Empty means the symbol is not listed. */
        record Listed(List<MarketInstrument> instruments) implements InstrumentResult {}
        record Rejected(SourceRejectionReason reason) implements InstrumentResult {}
    }

    sealed interface CandleResult {
        /** Rows as returned, duplicates included, in no promised order. */
        record Candles(List<MarketCandle> candles) implements CandleResult {}
        record Rejected(SourceRejectionReason reason) implements CandleResult {}
    }

    /** Provider server time, the attempt's reference time. */
    MarketTimeResult marketTime();

    /** Spot Instruments Info for one symbol. */
    InstrumentResult spotInstrument(String symbol);

    /**
     * Spot 1-minute klines for {@code [start, end]} (both minute aligned, at most 1000 candles).
     * An invalid window is rejected without calling the provider.
     */
    CandleResult spotMinuteCandles(String symbol, Instant start, Instant end);

    /** 저장의 결과. */
    sealed interface StoreOutcome {

        /**
         * 자격정보·연결·제공자 계정이 저장됐다.
         *
         * @param connectionId 이번에 만들어진 연결
         * @param providerAccountId 재사용됐거나 새로 만들어진 <b>안정 식별자</b>
         * @param sameAccountAsRevoked 가장 최근에 해제한 연결과 같은 제공자 계정인가(Story 2.17 — 같은 main UID의 재연결)
         */
        record Stored(UUID connectionId, UUID providerAccountId, boolean sameAccountAsRevoked)
                implements StoreOutcome {}

        /** 저장되지 않았다. 호출자는 이 값을 받으면 자기 트랜잭션을 되돌려야 한다. */
        record Rejected(SourceRejectionReason reason) implements StoreOutcome {}
    }

    /**
     * 저장까지 갈 수 있는 구성인가. <b>제공자에 닿기 전에 답한다.</b>
     *
     * <p>이 판정이 admission 게이트에 있어야 하는 이유는 순서다. 암호화 key ring이나 제공자
     * base URL·운영 reserved IP가 비면 {@link #store}가 {@link SourceRejectionReason#UNAVAILABLE}로
     * 끝나는데, 그 자리는 이미 제공자 왕복 <b>뒤</b>다 — 사용자의 진짜 자격정보가 서명된 요청
     * 세 번으로 거래소에 도착한 다음에야 "지금은 연결할 수 없다"를 알리는 셈이 된다. 제공자 등록의
     * fail-closed는 제공자에 닿기 전에 닫는다({@code AuthSecurityConfig})가 이 스토리가 따르기로
     * 한 관용이므로 같은 자리에서 닫는다.
     *
     * <p>어느 값이 비었는지는 이 값에서도 응답에서도 로그에서도 복원되지 않는다.
     */
    boolean isConnectable();

    /**
     * 그 사용자에게 활성 연결이 있는가.
     *
     * <p>admission의 사전 확인이며 <b>권위가 아니다</b>. 권위는 부분 유일 색인
     * ({@code source_connection_active_user_key})이고, 이 조회는 외부 호출 0건으로 끝낼 수 있는
     * 요청을 먼저 걸러 낸다.
     */
    boolean hasActiveConnection(@Nullable UUID userId);

    /** 그 사용자의 활성 연결. 없으면 빈 값이며 미존재·삭제 계정도 같은 값이다. */
    Optional<ActiveConnection> findActiveConnection(@Nullable UUID userId);

    /**
     * 자격정보를 제공자에게 검증한다. <b>데이터베이스에 닿지 않는다.</b>
     *
     * <p>자동 재시도하지 않는다. 시간초과·rate limit·5xx를 포함해 한 번 실패하면 그대로 거절이며,
     * 다시 시작하는 것은 사용자의 조작이다(AC-5·AD-21).
     */
    SourceVerification verify(SourceProvider provider, SourceCredentials credentials);

    /**
     * 검증 결과와 자격정보를 저장한다. <b>호출자의 트랜잭션에 참여한다.</b>
     *
     * <p>세 행을 함께 만든다 — 제공자 계정(재사용 가능), 연결, 암호화된 자격정보. 소유권·삭제
     * 상태를 여기서 <b>다시</b> 판정하며, 동시 요청은 부분 유일 색인이 갈라 낸다.
     *
     * <p>해제한 연결이 있어도 거부하지 않는다(Story 2.17). 판정 순서는 활성 연결 → 다른 사용자의 main UID → 그 사용자의
     * 기존 제공자 계정 재사용 → 새 제공자 계정이다. 활성 계획과의 대조는 계획 잠금을 쥔 호출자가 한다 — 이 모듈은
     * 계획을 모른다. 해제된 이전 연결 행은 바꾸지 않는다.
     *
     * @param credentials 평문. 이 호출 안에서 암호화되고 그 뒤 어디에도 남지 않는다
     */
    StoreOutcome store(
            @Nullable UUID userId,
            SourceProvider provider,
            SourceVerification.Verified verified,
            SourceCredentials credentials);

    // ------------------------------------------------------------------ Story 2.16

    /**
     * 그 사용자의 현재 연결 하나의 상태. 활성 연결이 있으면 그것이고, 없으면 가장 최근에 해제된 연결이다.
     * 자격정보도 제공자 계정 식별자도 담지 않는다 — {@code connectionId}는 불투명 참조다.
     *
     * @param revokedAt 해제된 연결에만 있다. 데이터베이스 시각을 ms로 자른 값이다
     */
    record ConnectionStatus(UUID connectionId, boolean revoked, Instant verifiedAt, String policyVersion,
            @Nullable Instant revokedAt) {}

    /** 그 사용자의 현재 연결 상태. 연결한 적이 없으면 빈 값이며 미존재·삭제 계정도 같은 값이다. */
    Optional<ConnectionStatus> currentConnection(@Nullable UUID userId);

    /**
     * 그 사용자에게 해제된 연결이 있는가. 첫 스냅샷 상태가 해제 사실을 말하는 데 쓴다. 새 연결을 막지 않는다 — 재연결
     * ({@code RECONNECT-1.0}, Story 2.17)은 저장({@link #store})이 main UID로 판정한다.
     */
    boolean hasRevokedConnection(@Nullable UUID userId);

    /** 그 사용자의 그 연결이 해제된 연결인가. 동기화 가용성의 원인({@code CONNECTION_REVOKED})이 이것을 본다. */
    boolean isRevoked(@Nullable UUID userId, @Nullable UUID connectionId);

    /**
     * 그 연결과 같은 사용자·같은 제공자 계정의 연결 전부(해제 포함, 자기 자신 포함)(Story 2.17). 연결 단위 운영 제한이 계정을
     * 따르는 판정이 쓴다. 사용자 없이 연결 ID로 찾는다 — 제한 판독은 사용자를 모른다.
     *
     * @return 모르는 연결이면 빈 집합
     */
    java.util.Set<UUID> sameAccountConnections(UUID connectionId);

    /** 해제의 결과. */
    sealed interface Revocation {
        /**
         * 이번 호출이 해제했다.
         *
         * @param revokedAt 데이터베이스 시각({@code statement_timestamp()})을 ms로 자른 값
         */
        record Revoked(UUID connectionId, UUID providerAccountId, Instant revokedAt) implements Revocation {}

        /** 그 사용자의 활성 연결이 아니다 — 타 사용자·미존재·이미 해제가 구분되지 않는다. 아무것도 쓰지 않았다. */
        record NotActive() implements Revocation {}
    }

    /**
     * 연결을 해제한다. <b>호출자 트랜잭션 전용이다</b>({@code MANDATORY}).
     *
     * <p>연결 행을 {@code FOR UPDATE}로 잠그고 {@code ACTIVE}를 다시 확인한 뒤 자격정보 행을 지우고
     * ({@code ACTIVE → REVOKED}, 해제 시각·멱등 키·요청 hash)를 쓴다. 연결 행·제공자 계정은 지우지 않는다.
     * 호출자는 이 앞에서 계획 쓰기 잠금과 계정 생명주기 잠금을 잡는다.
     *
     * <p><b>해제 시각은 단조다.</b> 데이터베이스 시각을 ms로 자른 값이되, 검증 시각과 {@code notBefore}(활성
     * membership의 시작) 중 늦은 쪽을 ms로 올린 값보다 앞서지 않는다 — 시계 차로 {@code ended_at > started_at}이
     * 깨져 해제 전체가 실패하지 않게 한다. 호출자는 같은 값을 membership 종료와 작업 종단에 쓴다.
     */
    Revocation revoke(UUID userId, UUID connectionId, String idempotencyKey, String requestHash,
            @Nullable Instant notBefore);

    /** 같은 키의 앞선 해제. */
    sealed interface RevocationReplay {
        record Stored(UUID connectionId, Instant revokedAt) implements RevocationReplay {}

        /** 같은 키가 다른 요청에 쓰였다. */
        record Conflict() implements RevocationReplay {}

        record None() implements RevocationReplay {}
    }

    /** 그 사용자가 그 키로 해제한 결과. 요청 hash가 다르면 충돌이다. */
    RevocationReplay revocationReplay(@Nullable UUID userId, String idempotencyKey, String requestHash);

    /**
     * 이 사용자의 source 데이터를 지운다. <b>다른 사용자 것은 남는다.</b>
     *
     * <p>{@code identity}의 payload 삭제 <b>앞에</b> 돌아야 한다. 소유 테이블이
     * {@code identity_account}를 {@code ON DELETE RESTRICT}로 가리키므로 순서가 뒤집히면 외래키
     * 위반으로 소리 나게 실패한다 — 부분 삭제를 완료로 보고하지 않는다는 계약이 그 선택의 근거다
     * ({@code V4__source_connection.sql}).
     *
     * <p>멱등하다. 지울 것이 없는 계정에 다시 요청해도 실패가 아니다.
     */
    void eraseSourceData(@Nullable UUID userId);

    /**
     * 이 사용자의 Bybit 자격정보(암호문·nonce·키 버전)만 지운다 — 계정 삭제의 첫 단계(Story 5.2 {@code CREDENTIALS}). 삭제 전환을 지난
     * 계정만 지우고 {@code ACTIVE} 계정이면 아무것도 하지 않는다(0). 연결·제공자 계정·
     * membership은 남기고 뒤 단계({@link #eraseSourceData})가 지운다. 자격정보가 사라지면 연결을 다시 읽는 모든 Worker 재검사
     * ({@code snapshotConnection})가 빈 값이라 서명 호출이 더 일어나지 않는다. 다른 사용자 것은 남는다. 호출자 트랜잭션에 참여한다.
     * 멱등이다.
     *
     * @return 이번에 지운 행 수. 0도 정상이다
     */
    int eraseCredentials(@Nullable UUID userId);

    /** 그 사용자의 연결에 남은 자격정보 행 수({@code CREDENTIALS} 단계의 잔여 검증). */
    long remainingCredentialsOf(UUID userId);

    // ------------------------------------------------------------------ Story 3.2

    /**
     * 운영 제한의 대상 확인에 쓰는 연결 하나(Story 3.2). 사용자 없이 id로 찾는다. 자격정보·제공자 계정 식별자를 담지 않는다.
     *
     * @param userId 소유자. 제한 행의 귀속이다
     */
    record ConnectionTarget(UUID connectionId, UUID userId, boolean revoked) {}

    /** 그 연결. 없으면 빈 값이다. */
    Optional<ConnectionTarget> connectionTarget(UUID connectionId);

    /** 지금 활성인 연결의 수. 운영 제한의 영향 미리보기가 쓴다. */
    long countActiveConnections();

    // ------------------------------------------------------------------ Story 3.10 복원 검증

    /**
     * 자격정보 표본 복호의 결과. <b>수만 담는다</b> — 평문은 이 port 밖으로 나가지 않고 제공자 호출도 없다.
     *
     * @param sampled 읽은 자격정보 행 수
     * @param opened 그 가운데 지금 key ring(과거 키 포함)으로 풀린 수
     * @param activeKey 새 암호화에 쓸 active key가 key ring에 있는가 — 없으면 복원된 서비스가 새 연결을 봉할 수 없다
     */
    record CredentialProbe(int sampled, int opened, boolean activeKey) {

        /** 통과: active key가 있고 표본이 모두 풀렸다. */
        public boolean passed() {
            return this.activeKey && this.opened == this.sampled;
        }
    }

    /**
     * 저장된 자격정보를 지금 key ring으로 풀어 본다(복원된 DB와 key ring이 짝인가). 표본은 key version마다 가장 최근 행 하나와 전체에서
     * 가장 최근 {@code limit}행의 합이다 — 오래된 version의 키가 빠진 것도 잡는다. 풀린 평문은 곧바로 버린다. 연결 상태와 무관하게
     * 자격정보 행이 있는 것을 본다.
     */
    CredentialProbe probeCredentials(int limit);

    /**
     * 그 사용자에게 아직 귀속된 이 모듈의 행 수. {@code source_*} 표의 {@code user_id} 열을 카탈로그로 찾아 센다(자격정보 행은 연결을
     * 거쳐 센다). 삭제가 끝난 계정이면 0이어야 한다.
     */
    long remainingRowsOf(UUID userId);
}
