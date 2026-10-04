package com.bokrilog;

import com.bokrilog.ActionGrantService.Consumption;
import com.bokrilog.ActionGrantService.ConsumptionRequest;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.plan.PlanEndApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.stereotype.Service;

/**
 * 계획 중도 종료의 흐름과 허가(Story 2.13, {@code SECURITY-ACTION-1.0}).
 *
 * <p><b>세 단계를 순서대로 강제한다.</b> 최근 재인증(1.8) → {@code plan-end} 일회성 허가(1.9) → 확인 대화상자의
 * 확정. 허가 서비스가 루트 package-private이라 흐름과 세션 회전은 여기 있고, 누가 언제 무엇을 끝낼 수 있는지와
 * 상태 전환은 plan 모듈({@link PlanEndApi})이 소유한다.
 *
 * <p><b>준비는 아무것도 바꾸지 않는다.</b> 자격을 잠금 없이 판정하고({@link PlanEndApi#check}), 서버가 정본 확인
 * 문구를 만들어 그 문구에 귀속한 허가를 발급한 뒤에만 세션에 무장을 심는다. 앞선 게이트를 모두 지난 뒤의 실패는
 * 재인증 하나뿐이다 — 발급이 재인증 신선도·의심 세션·삭제 상태·주체 대조를 함께 본다.
 *
 * <p><b>확정은 허가 소비와 같은 트랜잭션 하나다.</b> 계정 쓰기 접근 → 같은 키의 재전송(종료 기록의
 * {@code (user_id, idempotency_key)}) → 무장과 본문의 일치(확인 문구는 NFC로 대조) → 허가 소비와 그 안의
 * {@link PlanEndApi#end}. 종료가 거절되면 코드가 붙은 예외가 소비까지 함께 되돌리고 허가는 미소비로 남는다.
 * 실제로 끝낸 요청에서만 무장을 버리고 세션을 회전시킨다.
 *
 * <p><b>멱등은 두 층이다.</b> 첫째 층은 종료 기록이다 — 무장이 사라진 뒤에도 같은 키·같은 요청은 같은 결과를,
 * 다른 요청은 충돌을 받는다. 둘째 층은 허가의 {@code (user_id, operation_id)}이고 operation id가 곧 그 키다 —
 * 첫째 층 조회를 동시에 지난 두 요청 가운데 하나만 소비하고, 진 쪽은 이긴 쪽이 커밋한 기록을 재생한다. 다른
 * 키로 같은 허가를 쓰는 두 번째 탭은 재생할 결과가 없어 단일 거부다.
 *
 * <p>외부(Bybit) 호출은 없다. servlet 웹 애플리케이션에서만 배선한다.
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class PlanEndCommandService {

    /** 재인증이 신선하지 않아 허가가 서지 않았다(준비). */
    static final String REAUTHENTICATION_REQUIRED = "REAUTHENTICATION_REQUIRED";

    /** 무장·확인 문구·허가 중 하나가 이 확정과 맞지 않는다(만료·재사용·다른 계획·다른 command·흐름 없음). */
    static final String PLAN_END_NOT_CONFIRMED = "PLAN_END_NOT_CONFIRMED";

    /** 같은 키가 다른 종료 요청에 이미 쓰였다. */
    static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    /** 준비의 결과. */
    sealed interface Preparation {

        /** 허가가 발급됐고 세션에 무장이 심겼다. */
        record Armed(PlanEndFlow.Armed flow) implements Preparation {}

        /** 준비가 성립하지 않았다. */
        record Refused(String code, @Nullable String reason) implements Preparation {}
    }

    /** 확정의 결과. */
    sealed interface Outcome {

        /** 계획이 끝났다 — 이번 요청이 끝냈거나, 같은 키의 앞선 요청이 끝낸 결과의 재생이다. */
        record Ended(PlanEndApi.Ended ended) implements Outcome {}

        /** 끝내지 않았다. 계획도 허가도 그대로다. */
        record Refused(String code, @Nullable String reason) implements Outcome {}
    }

    private final PlanEndApi planEnds;

    private final ActionGrantService grants;

    private final AccountAccessApi accounts;

    PlanEndCommandService(PlanEndApi planEnds, ActionGrantService grants, AccountAccessApi accounts) {
        this.planEnds = planEnds;
        this.grants = grants;
        this.accounts = accounts;
    }

    /**
     * 확인 대화상자를 열 준비. 자격 → 정본 확인 문구 → 허가 발급 → 무장 순서다. 앞선 무장은 이 준비에서 덮이지만,
     * 새 허가가 <b>발급된 뒤에만</b> 덮이고 그때 그 무장이 낳은 허가 하나만 함께 무효화된다(거절되는 준비는 아무것도
     * 버리지 않는다 — Story 1.11의 규칙).
     */
    Preparation prepare(HttpSession session, UUID userId, PlanEndApi.Request request) {
        var refusal = this.planEnds.check(userId, request).orElse(null);
        if (refusal != null) {
            return new Preparation.Refused(refusal.code(), refusal.reason());
        }
        String confirmation = request.confirmation();
        String target = request.planId().toString();
        var reference = this.grants.issue(session, userId, PlanEndApi.COMMAND, target, confirmation);
        if (reference.isEmpty()) {
            return new Preparation.Refused(REAUTHENTICATION_REQUIRED, null);
        }
        // The arming this one overwrites goes with its own grant — that grant only, by its reference: an issue for the
        // same plan already replaced it, and nothing another session holds is touched.
        PlanEndFlow.Armed previous = PlanEndFlow.peekIncludingExpired(session);
        if (previous != null) {
            this.grants.invalidateReference(previous.userId(), previous.grantReference());
        }
        // A reauthentication arming left over is an older intent: the grant just stood on a fresh reauthentication.
        ReauthenticationFlow.discard(session);
        PlanEndFlow.Armed armed =
                new PlanEndFlow.Armed(
                        userId,
                        request.planId(),
                        request.reason().name(),
                        request.pointerVersion(),
                        request.calculationId(),
                        reference.get(),
                        confirmation,
                        session.getId(),
                        Instant.now());
        PlanEndFlow.arm(session, armed);
        return new Preparation.Armed(armed);
    }

    /**
     * 확정.
     *
     * @param httpRequest 지금 이 요청. 세션 회전이 여기서만 가능하다
     * @param confirmation <b>클라이언트가 되돌려 보낸</b> 확인 문구. 무장의 문구와 NFC로 같아야 하고, 허가 소비의
     *     확인 hash도 이 값으로 만든다
     * @param idempotencyKey 이 확정의 멱등 키이자 허가의 operation id
     */
    Outcome confirm(
            HttpServletRequest httpRequest,
            HttpSession session,
            UUID userId,
            PlanEndApi.Request request,
            String confirmation,
            String idempotencyKey) {
        if (this.accounts.decide(userId, userId, AccountAccessKind.WRITE) != AccessDecision.ALLOWED) {
            return new Outcome.Refused(PlanEndApi.NOT_PERMITTED, null);
        }
        String requestHash = request.requestHash();
        Outcome replayed = replayed(userId, idempotencyKey, requestHash);
        if (replayed != null) {
            return replayed;
        }
        PlanEndFlow.Armed armed = PlanEndFlow.peek(session);
        if (armed == null
                || !armed.userId().equals(userId)
                || !armed.planId().equals(request.planId())
                || !armed.reason().equals(request.reason().name())
                || armed.pointerVersion() != request.pointerVersion()
                || !armed.calculationId().equals(request.calculationId())
                || !armed.sessionId().equals(session.getId())
                || !ActionGrant.normalize(armed.confirmation()).equals(ActionGrant.normalize(confirmation))) {
            return notConfirmed();
        }

        AtomicReference<PlanEndApi.@Nullable Ended> ran = new AtomicReference<>();
        Consumption consumption;
        try {
            consumption =
                    this.grants.consume(
                            new ConsumptionRequest(
                                    session,
                                    userId,
                                    armed.grantReference(),
                                    PlanEndApi.COMMAND,
                                    request.planId().toString(),
                                    // The value the client sent back: the fifth binding stays the screen's own phrase.
                                    confirmation,
                                    idempotencyKey),
                            () -> {
                                PlanEndApi.Ended ended = this.planEnds.end(userId, request, idempotencyKey);
                                ran.set(ended);
                                return PlanEndApi.OUTCOME_ENDED + "|" + ended.reason().name() + "|" + ended.endedAt();
                            });
        } catch (PlanEndApi.Refused refused) {
            // The consumption rolled back with the refusal: the plan is active and the grant unconsumed.
            return new Outcome.Refused(refused.code(), refused.reason());
        }
        if (consumption instanceof Consumption.Denied) {
            // A same-key, same-request end may have committed after the replay read above (its grant is not this
            // arming's to replay): its record is the answer. Anything else is the one refusal.
            Outcome committed = replayed(userId, idempotencyKey, requestHash);
            return committed == null ? notConfirmed() : committed;
        }
        PlanEndApi.Ended ended = ran.get();
        if (ended != null) {
            // This request ended the plan: the arming is spent and the session rotates (SECURITY-ACTION-1.0).
            PlanEndFlow.discard(session);
            rotate(httpRequest);
            return new Outcome.Ended(ended);
        }
        // The grant answered with the stored result of the same key's earlier execution: replay its end record.
        Outcome settled = replayed(userId, idempotencyKey, requestHash);
        return settled == null ? notConfirmed() : settled;
    }

    /**
     * 이 세션의 종료 무장을 버리고 <b>그 무장이 낳은 허가 하나만</b> 무효화한다. 무장이 없어도 같은 결과다.
     *
     * <p>참조로 좁힌다. {@code (command, target)}으로 지우면 오래된 탭의 취소 하나가 같은 계획을 위해 다른 세션이 방금
     * 받은 살아 있는 허가를 지운다.
     */
    void cancel(HttpSession session) {
        PlanEndFlow.Armed armed = PlanEndFlow.peekIncludingExpired(session);
        PlanEndFlow.discard(session);
        if (armed != null) {
            this.grants.invalidateReference(armed.userId(), armed.grantReference());
        }
    }

    /** 같은 키의 앞선 종료: 저장된 결과, 다른 요청과의 충돌, 또는 아직 없음({@code null}). */
    private @Nullable Outcome replayed(UUID userId, String key, String requestHash) {
        return switch (this.planEnds.replay(userId, key, requestHash)) {
            case PlanEndApi.Replay.Stored stored -> new Outcome.Ended(stored.ended());
            case PlanEndApi.Replay.Conflict conflict -> new Outcome.Refused(IDEMPOTENCY_CONFLICT, null);
            case PlanEndApi.Replay.None none -> null;
        };
    }

    private static Outcome notConfirmed() {
        return new Outcome.Refused(PLAN_END_NOT_CONFIRMED, null);
    }

    /**
     * 커밋 뒤 세션 회전. <b>계획을 실제로 끝낸 요청에서만</b> 돈다. 여기서 실패해도 종료는 이미 커밋됐다
     * ({@code BybitConnectionService.rotate}와 같은 판단).
     */
    private static void rotate(HttpServletRequest request) {
        try {
            request.changeSessionId();
        } catch (RuntimeException ignored) {
            // 다음 요청이 같은 세션 상태를 다시 본다. 종료 자체는 이미 커밋됐다.
        }
    }
}
