package com.bokrilog;

import com.bokrilog.ActionGrantService.Consumption;
import com.bokrilog.ActionGrantService.ConsumptionRequest;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.sync.SyncJobApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Bybit 연결 해제의 흐름과 허가(Story 2.16, {@code SECURITY-ACTION-1.0}).
 *
 * <p><b>세 단계를 순서대로 강제한다.</b> 최근 재인증(1.8) → {@code bybit-connection-revoke} 일회성 허가(1.9) → 확인
 * 대화상자의 확정. 계획 중도 종료({@code PlanEndCommandService})와 같은 모양이다 — 허가 서비스가 루트
 * package-private이라 흐름과 세션 회전은 여기 있고, 자격정보 삭제와 상태 전환은 {@code source}, 구간 종료는
 * {@code portfolio}, 작업 종단은 {@code sync}가 소유한다.
 *
 * <p><b>준비는 아무것도 바꾸지 않는다.</b> 계정 쓰기 접근과 대상(그 사용자의 활성 연결)을 잠금 없이 판정하고,
 * 정본 확인 문구({@code bybit-connection-revoke|<connectionId>})에 귀속한 허가를 발급한 뒤에만 무장을 심는다.
 * 타 사용자·미존재·이미 해제된 연결은 같은 단일 거부다.
 *
 * <p><b>확정은 허가 소비와 같은 트랜잭션 하나다.</b> 잠금 순서는 발행자들과 같다 — 계획 advisory → 계정 생명주기
 * (WRITE 판정) → 연결 행 → 작업 행. 그 안에서 자격정보 행 삭제, {@code ACTIVE → REVOKED}(해제 시각·멱등 키·요청
 * hash), 그 연결의 활성 membership 종료, 여섯 작업 표의 진행 중 작업 종단이 함께 커밋된다. 하나라도 실패하면
 * 소비까지 전부 롤백되고 연결은 {@code ACTIVE} 그대로다. 실제로 해제한 요청에서만 무장을 버리고 세션을 회전한다.
 *
 * <p><b>멱등은 두 층이다.</b> 첫째는 연결 행의 {@code (user_id, revocation_idempotency_key)}다 — 무장이 사라진
 * 뒤에도 같은 키·같은 요청은 저장된 결과를, 다른 요청은 충돌을 받는다. 둘째는 허가의 {@code (user_id, operation_id)}
 * 이고 operation id가 곧 그 키다 — 동시 확정 둘 가운데 하나만 소비하고 진 쪽은 이긴 쪽의 결과를 재생한다.
 *
 * <p><b>운영 제한으로 막지 않는다.</b> 해제는 사용자가 BokriLog의 접근을 줄이는 보호 방향 행동이다 — 자격정보를
 * 지우고 작업을 멈출 뿐 새 데이터를 만들지 않는다. 제한은 위험을 늘리는 쓰기(연결·동기화·발행)를 멈추려고 있으므로
 * 그 제한이 걸린 동안 사용자가 자기 자격정보를 지울 수 없게 하면 제한의 뜻이 뒤집힌다. 그래서 준비·확정 어디에도
 * 운영 제한 판독({@code OperationalRestrictionApi})이 없다. 계정 접근(삭제 진행 포함)과 재인증·허가는 그대로 본다.
 *
 * <p>Bybit 호출은 없다. BokriLog는 Bybit의 API Key를 원격 폐기하지 않는다. servlet 웹 애플리케이션에서만 배선한다.
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class BybitRevocationCommandService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BybitRevocationCommandService.class);

    /**
     * 민감 command의 이름. 다른 command와 <b>다른 값</b>이어야 한다 — 허가는 이 문자열에 귀속한다.
     */
    static final String COMMAND = "bybit-connection-revoke";

    /** 계정 쓰기 접근이 없다(삭제 진행 포함). */
    static final String NOT_PERMITTED = "NOT_PERMITTED";

    /** 그 사용자의 활성 연결이 아니다 — 타 사용자·미존재·이미 해제가 같은 값이다. */
    static final String CONNECTION_NOT_FOUND = "CONNECTION_NOT_FOUND";

    /** 재인증이 신선하지 않아 허가가 서지 않았다(준비). */
    static final String REAUTHENTICATION_REQUIRED = "REAUTHENTICATION_REQUIRED";

    /** 무장·확인 문구·허가 중 하나가 이 확정과 맞지 않는다(만료·재사용·다른 연결·다른 command·흐름 없음). */
    static final String REVOCATION_NOT_CONFIRMED = "REVOCATION_NOT_CONFIRMED";

    /** 같은 키가 다른 해제 요청에 이미 쓰였다. */
    static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    /** 해제의 결과 code. */
    static final String OUTCOME_REVOKED = "REVOKED";

    /** 거절. 소비 트랜잭션을 되돌리기 위한 사설 타입이며 스택도 메시지도 담지 않는다. */
    private static final class Refused extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        private final String code;

        Refused(String code) {
            super(null, null, false, false);
            this.code = code;
        }

        String code() {
            return this.code;
        }
    }

    /** 해제된 연결. */
    record Revoked(UUID connectionId, Instant revokedAt) {}

    sealed interface Preparation {
        record Armed(BybitRevocationFlow.Armed flow) implements Preparation {}

        record Refused(String code) implements Preparation {}
    }

    sealed interface Outcome {
        /** 해제됐다 — 이번 요청이 했거나, 같은 키의 앞선 요청이 한 결과의 재생이다. */
        record Done(Revoked revoked) implements Outcome {}

        /** 해제하지 않았다. 연결도 허가도 그대로다. */
        record Refused(String code) implements Outcome {}
    }

    private final SourceConnectionApi sources;

    private final PortfolioMembershipApi portfolios;

    private final SyncJobApi sync;

    private final ActionGrantService grants;

    private final AccountAccessApi accounts;

    BybitRevocationCommandService(SourceConnectionApi sources, PortfolioMembershipApi portfolios, SyncJobApi sync,
            ActionGrantService grants, AccountAccessApi accounts) {
        this.sources = sources;
        this.portfolios = portfolios;
        this.sync = sync;
        this.grants = grants;
        this.accounts = accounts;
    }

    /** 서버가 만드는 정본 확인 문구. 사용자가 본 대상이 문구에 들어가므로 hash가 그 연결을 판별한다. */
    static String confirmationOf(UUID connectionId) {
        return COMMAND + "|" + connectionId;
    }

    /** 요청 hash. 같은 키가 다른 연결의 해제에 다시 쓰이면 충돌로 가른다. */
    static String requestHashOf(UUID connectionId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(confirmationOf(connectionId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * 확인 대화상자를 열 준비. 자격 → 정본 확인 문구 → 허가 발급 → 무장 순서다. 앞선 해제 무장은 새 허가가 발급된
     * 뒤에만 덮이고, 그때 그 무장이 낳은 허가 하나만 무효화된다(거절되는 준비는 아무것도 버리지 않는다).
     */
    Preparation prepare(HttpSession session, UUID userId, UUID connectionId) {
        if (this.accounts.decide(userId, userId, AccountAccessKind.WRITE) != AccessDecision.ALLOWED) {
            return new Preparation.Refused(NOT_PERMITTED);
        }
        var current = this.sources.currentConnection(userId).orElse(null);
        if (current == null || current.revoked() || !current.connectionId().equals(connectionId)) {
            return new Preparation.Refused(CONNECTION_NOT_FOUND);
        }
        String confirmation = confirmationOf(connectionId);
        var reference = this.grants.issue(session, userId, COMMAND, connectionId.toString(), confirmation);
        if (reference.isEmpty()) {
            return new Preparation.Refused(REAUTHENTICATION_REQUIRED);
        }
        BybitRevocationFlow.Armed previous = BybitRevocationFlow.peekIncludingExpired(session);
        if (previous != null) {
            this.grants.invalidateReference(previous.userId(), previous.grantReference());
        }
        // 남아 있는 재인증 무장은 옛 의도다. 방금 허가가 신선한 재인증 위에 섰다.
        ReauthenticationFlow.discard(session);
        BybitRevocationFlow.Armed armed = new BybitRevocationFlow.Armed(userId, connectionId, reference.get(),
                confirmation, session.getId(), Instant.now());
        BybitRevocationFlow.arm(session, armed);
        return new Preparation.Armed(armed);
    }

    /**
     * 확정.
     *
     * @param confirmation <b>클라이언트가 되돌려 보낸</b> 확인 문구. 무장의 문구와 NFC로 같아야 하고, 허가 소비의
     *     확인 hash도 이 값으로 만든다
     * @param idempotencyKey 이 확정의 멱등 키이자 허가의 operation id
     */
    Outcome confirm(HttpServletRequest httpRequest, HttpSession session, UUID userId, UUID connectionId,
            String confirmation, String idempotencyKey) {
        if (this.accounts.decide(userId, userId, AccountAccessKind.WRITE) != AccessDecision.ALLOWED) {
            return new Outcome.Refused(NOT_PERMITTED);
        }
        String requestHash = requestHashOf(connectionId);
        Outcome replayed = replayed(userId, idempotencyKey, requestHash);
        if (replayed != null) {
            return replayed;
        }
        BybitRevocationFlow.Armed armed = BybitRevocationFlow.peek(session);
        if (armed == null
                || !armed.userId().equals(userId)
                || !armed.connectionId().equals(connectionId)
                || !armed.sessionId().equals(session.getId())
                || !ActionGrant.normalize(armed.confirmation()).equals(ActionGrant.normalize(confirmation))) {
            return notConfirmed();
        }

        AtomicReference<@Nullable Revoked> ran = new AtomicReference<>();
        AtomicReference<SyncJobApi.@Nullable EndedConnectionWork> ended = new AtomicReference<>();
        Consumption consumption;
        try {
            consumption = this.grants.consume(
                    new ConsumptionRequest(session, userId, armed.grantReference(), COMMAND, connectionId.toString(),
                            // The value the client sent back: the fifth binding stays the screen's own phrase.
                            confirmation, idempotencyKey),
                    () -> {
                        // The publishers' lock order: the plan's advisory lock, the account lifecycle, then the rows.
                        this.sync.lockConnectionRevocation(userId);
                        if (!this.accounts.lockWriteAccess(userId)) throw new Refused(NOT_PERMITTED);
                        Instant notBefore =
                                this.portfolios.activeMembershipStart(userId, connectionId).orElse(null);
                        var revocation =
                                this.sources.revoke(userId, connectionId, idempotencyKey, requestHash, notBefore);
                        if (!(revocation instanceof SourceConnectionApi.Revocation.Revoked revoked))
                            throw new Refused(CONNECTION_NOT_FOUND);
                        this.portfolios.endSourceMembership(userId, connectionId, revoked.revokedAt());
                        ended.set(this.sync.endConnectionWork(userId, connectionId, revoked.revokedAt()));
                        ran.set(new Revoked(connectionId, revoked.revokedAt()));
                        return OUTCOME_REVOKED + "|" + connectionId + "|" + revoked.revokedAt();
                    });
        } catch (Refused refused) {
            // The consumption rolled back with the refusal: the connection is active and the grant unconsumed.
            return new Outcome.Refused(refused.code());
        } catch (DuplicateKeyException racing) {
            // The same key's revocation committed in between (the partial unique index): its row is the answer.
            Outcome committed = replayed(userId, idempotencyKey, requestHash);
            return committed == null ? notConfirmed() : committed;
        }
        if (consumption instanceof Consumption.Denied) {
            Outcome committed = replayed(userId, idempotencyKey, requestHash);
            return committed == null ? notConfirmed() : committed;
        }
        Revoked revoked = ran.get();
        if (revoked != null) {
            // 이 요청이 연결을 해제했다: 무장은 다 썼고 세션은 회전하며 재인증 시각은 지워진다(SECURITY-ACTION-1.0).
            BybitRevocationFlow.discard(session);
            SessionRotation.completeSensitiveCommand(httpRequest);
            var work = ended.get();
            LOG.info("bybit connection revoked: endedAttempts={}", work == null ? 0 : work.total());
            return new Outcome.Done(revoked);
        }
        // The grant answered with the stored result of the same key's earlier execution: replay its row.
        Outcome settled = replayed(userId, idempotencyKey, requestHash);
        return settled == null ? notConfirmed() : settled;
    }

    /** 이 세션의 해제 무장을 버리고 <b>그 무장이 낳은 허가 하나만</b> 무효화한다. 무장이 없어도 같은 결과다. */
    void abandon(HttpSession session) {
        BybitRevocationFlow.Armed armed = BybitRevocationFlow.peekIncludingExpired(session);
        BybitRevocationFlow.discard(session);
        if (armed != null) {
            this.grants.invalidateReference(armed.userId(), armed.grantReference());
        }
    }

    private @Nullable Outcome replayed(UUID userId, String key, String requestHash) {
        return switch (this.sources.revocationReplay(userId, key, requestHash)) {
            case SourceConnectionApi.RevocationReplay.Stored stored ->
                    new Outcome.Done(new Revoked(stored.connectionId(), stored.revokedAt()));
            case SourceConnectionApi.RevocationReplay.Conflict conflict -> new Outcome.Refused(IDEMPOTENCY_CONFLICT);
            case SourceConnectionApi.RevocationReplay.None none -> null;
        };
    }

    private static Outcome notConfirmed() {
        return new Outcome.Refused(REVOCATION_NOT_CONFIRMED);
    }
}
