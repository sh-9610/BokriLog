package com.bokrilog;

import com.bokrilog.ActionGrantService.Consumption;
import com.bokrilog.ActionGrantService.ConsumptionRequest;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.portfolio.PortfolioMembershipApi;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceProvider;
import com.bokrilog.source.SourceRejectionReason;
import com.bokrilog.source.SourceVerification;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 읽기 전용 Bybit 연결의 판정과 실행.
 *
 * <p><b>세 단계를 순서대로 강제한다.</b> 최근 재인증(1.8) → {@code bybit-connection-create}
 * 일회성 허가(1.9) → 제공자 세 엔드포인트 검증. 앞 둘이 성립하지 않으면 <b>외부 호출이 0건</b>이고,
 * 검증이 하나라도 확인 불가·모호하면 저장이 0건이다.
 *
 * <p><b>외부 호출은 트랜잭션 밖에서 끝난다.</b> {@link #complete}가 검증을 먼저 마치고, 결과를 받은
 * 뒤에야 허가 포트에 작업을 건넨다. 그 포트가 트랜잭션 경계를 소유하므로 순서를 어기면 배선
 * 실수가 예외로 드러난다({@code ActionGrantService}) — 즉 이 순서는 규율이 아니라 구조다.
 *
 * <p><b>최종 트랜잭션의 원자 단위는 여섯이다</b> — 허가 소비, 암호화된 자격정보, {@code BYBIT}
 * connection, provider account, {@code USD} 기본 portfolio 생성·재사용, 활성 source membership.
 * 저장이 거부되면 사설 예외로 그 트랜잭션을 되돌려 허가 소비까지 함께 사라진다
 * ({@code LinkService}·{@code UnlinkService}와 같은 근거) — 값으로 돌려주면 "허가만 소비되고
 * 연결은 없는" 반쪽 상태가 정확히 남는다.
 *
 * <p><b>거부가 두 종류다.</b> 하나는 {@code identity}와 같은 단일 값
 * ({@link SourceRejectionReason#NOT_PERMITTED}) 이고 거기에 허가 부재·이미 연결됨·운영 제한·삭제
 * 상태·타 사용자가 접힌다. 다른 하나는 <b>사용자가 제공자 콘솔에서 고칠 수 있는 사실</b>이며 그것은
 * 구분해 알려 준다 — 구분하지 않으면 읽기 전용 키를 다시 발급해야 하는지 IP를 등록해야 하는지 알
 * 방법이 없다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다({@code LinkService}와 같은 조건).
 */
@Service
@ConditionalOnWebApplication(type = Type.SERVLET)
class BybitConnectionService {

    /**
     * 민감 command의 이름.
     *
     * <p>{@code LinkService.COMMAND}·{@code UnlinkService.COMMAND}와 <b>다른 값</b>이어야 한다.
     * 허가는 이 문자열에 귀속하므로 값이 같으면 연결용으로 발급된 허가가 다른 작업에 쓰인다.
     *
     * <p>같은 문자열을 {@code operations_restriction.command}가 쓴다. 운영 제한이 막는 것은 command
     * 이므로 두 이름이 같아야 하고, 갈라지면 제한을 걸어도 아무것도 막히지 않는다.
     */
    static final String COMMAND = "bybit-connection-create";

    /** 허가의 target. 제공자 하나뿐이라 값이 고정이지만 축은 살아 있다. */
    static final String TARGET = "bybit";

    /**
     * 저장되는 실행 결과.
     *
     * <p>응답을 잃은 클라이언트의 재전송이 이 값을 그대로 다시 받는다. 연결 식별자·제공자 계정·
     * 시각을 담지 않는다 — 이 문자열은 허가 행에 남고 그 행은 계정 삭제까지 산다.
     */
    static final String RESULT_CONNECTED = "connected";

    /**
     * 확인 문구에 붙는 일회성 코드의 <b>난수 길이(바이트)</b>.
     *
     * <p>문자 수가 아니다. base64url은 3바이트를 4글자로 옮기므로 6바이트는 8글자가 된다 — 사용자가
     * 화면에서 읽고 대조할 만한 길이이고, 요청마다 다른 값을 만들기에 충분하다.
     */
    private static final int CONFIRMATION_CODE_BYTES = 6;

    /**
     * 확인 문구의 앞부분.
     *
     * <p>사용자가 화면에서 보는 문장이며 그 뒤에 일회성 코드가 붙는다. 서버가 만드는 이유는 그 값이
     * 곧 허가의 다섯 번째 귀속 축이기 때문이다 — 클라이언트가 만들면 축의 권위가 브라우저로 간다.
     */
    private static final String CONFIRMATION_PREFIX = "읽기 전용 Bybit 연결을 만듭니다. 확인 코드 ";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder CODE_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * 저장이 거부됐다.
     *
     * <p>소비 트랜잭션을 되돌리기 위한 사설 타입이다({@code LinkService.LinkRejected}와 같은 근거).
     * 스택도 메시지도 담지 않는다 — 이 예외는 이 파일 안에서 만들어져 이 파일 안에서 잡힌다.
     */
    private static final class StoreRejected extends RuntimeException {

        @Serial private static final long serialVersionUID = 1L;

        private final SourceRejectionReason reason;

        StoreRejected(SourceRejectionReason reason) {
            super(null, null, false, false);
            this.reason = reason;
        }

        SourceRejectionReason reason() {
            return this.reason;
        }
    }

    /**
     * 준비의 결과.
     *
     * <p>{@link Optional}이 아닌 이유는 거절 하나가 단일 거부와 <b>달라야</b> 하기 때문이다 —
     * 연결 기능이 구성되지 않은 스택은 사용자가 무엇을 해도 통과하지 못하고, 그 사실을 "권한이
     * 없습니다"로 접으면 사용자는 본인 확인만 반복한다. 나머지 거절은 그대로 하나로 수렴한다.
     */
    sealed interface Preparation {

        /** 허가가 발급됐고 세션에 무장이 심겼다. */
        record Armed(BybitConnectionFlow.Armed flow) implements Preparation {}

        /** 준비가 성립하지 않았다. */
        record Rejected(SourceRejectionReason reason) implements Preparation {}
    }

    /**
     * 연결 완료의 결과.
     *
     * <p>성공은 값 하나이고 실패는 이유를 든다. {@code LinkService.Outcome}이 둘뿐이던 것과 다른
     * 이유는 {@link SourceRejectionReason}의 javadoc에 있다.
     */
    sealed interface Outcome {

        /** 여섯이 한 트랜잭션으로 커밋됐다. 세션 재회전이 뒤따랐다. */
        record Connected(Instant verifiedAt, String policyVersion) implements Outcome {}

        /** 저장되지 않았다. */
        record Rejected(SourceRejectionReason reason) implements Outcome {}
    }

    private final SourceConnectionApi sources;

    private final PortfolioMembershipApi portfolios;

    private final OperationalRestrictionApi restrictions;

    private final ActionGrantService grants;

    BybitConnectionService(
            SourceConnectionApi sources,
            PortfolioMembershipApi portfolios,
            OperationalRestrictionApi restrictions,
            ActionGrantService grants) {
        this.sources = sources;
        this.portfolios = portfolios;
        this.restrictions = restrictions;
        this.grants = grants;
    }

    /** 그 사용자의 활성 연결. 화면이 "연결됨"과 검증 시각·정책 버전을 그리는 값이다. */
    Optional<SourceConnectionApi.ActiveConnection> activeConnection(@Nullable UUID userId) {
        return this.sources.findActiveConnection(userId);
    }

    /**
     * 자격정보 입력 폼을 열 준비를 한다. <b>아무것도 바꾸지 않는다.</b>
     *
     * <p>게이트가 셋이고 전부 외부 호출 0건이다 — 운영 제한이 없는가, 활성 연결이 아직 없는가,
     * 그리고 허가가 서는가(재인증 신선도·의심 세션·삭제 상태·주체 대조를 그 발급 하나가 함께 본다,
     * {@code ActionGrantRepository.issueIfReauthenticationFresh}).
     *
     * <p>순서가 계약이다. 허가를 먼저 받고 무장한다 — 무장을 먼저 하면 허가가 서지 않은 흐름이
     * 세션에 남고 그 흐름의 최종 확인은 소비할 것이 없다({@code UnlinkService.start}와 같다).
     *
     * <p>귀속 세션 id를 제시하지 않는다. 준비와 확인 사이에 세션이 회전하지 않으므로 1.9 포트의
     * 기본 경로가 그대로 성립한다.
     *
     * @return 무장된 흐름. 게이트 중 하나라도 닫히면 거절이며, 구성 부재를 뺀 나머지는 서로
     *     구분되지 않는다
     */
    Preparation start(HttpSession session, UUID userId) {
        if (!this.sources.isConnectable()) {
            /*
             * 저장까지 갈 수 없는 스택이다. **여기서 닫는 것이 요점이다** — 통과시키면 사용자의
             * 진짜 자격정보가 서명된 요청 세 번으로 거래소에 도착한 뒤에야 거절된다. 제공자
             * 등록의 fail-closed가 제공자에 닿기 전에 닫는 것과 같은 자리여야 한다.
             */
            return new Preparation.Rejected(SourceRejectionReason.UNAVAILABLE);
        }
        if (this.restrictions.isRestricted(COMMAND) || this.sources.hasActiveConnection(userId)) {
            /*
             * 운영 제한이 걸려 있거나 이미 활성 연결이 있다. 두 경우가 같은 빈 값으로 접힌다.
             *
             * 여기서 막는 것은 소비될 수 없는 허가다. 권위는 최종 트랜잭션의 재검사와 부분 유일
             * 색인이고, 그 사이에 상태가 바뀌어 이 게이트를 통과한 요청이 커밋에서 거부되는 것은
             * 정상이다 — 결과는 같은 거부 하나이며 저장소에는 아무것도 남지 않는다.
             */
            return new Preparation.Rejected(SourceRejectionReason.NOT_PERMITTED);
        }

        String confirmation = newConfirmation();
        Optional<String> reference = this.grants.issue(session, userId, COMMAND, TARGET, confirmation);
        if (reference.isEmpty()) {
            return new Preparation.Rejected(SourceRejectionReason.NOT_PERMITTED);
        }

        /*
         * 앞선 흐름은 무장이 확정된 **뒤에** 정리한다(Story 1.11의 리뷰가 잡은 규칙). 앞에 두면
         * 거절되는 준비 요청 하나가 진행 중이던 다른 흐름의 무장과 허가를 함께 없앤다.
         *
         * 재인증 무장을 버리는 이유는 `UnlinkService.start`와 같다 — 준비가 성립한다는 것은
         * 재인증이 이미 끝났다는 뜻이므로 남아 있는 재인증 무장은 완료되지 않은 옛 의도다.
         * 연결·해제 무장은 두 서비스를 함께 아는 컨트롤러가 버린다(빈 순환을 만들지 않는다).
         */
        supersedePreviousArming(session);
        ReauthenticationFlow.discard(session);

        BybitConnectionFlow.Armed armed =
                new BybitConnectionFlow.Armed(
                        userId,
                        reference.get(),
                        // 멱등 키는 서버가 만든다. 클라이언트가 고르면 추측 가능한 값이
                        // `(user_id, operation_id)` 슬롯을 선점하는 경로가 열린다.
                        UUID.randomUUID().toString(),
                        confirmation,
                        Instant.now());
        BybitConnectionFlow.arm(session, armed);
        return new Preparation.Armed(armed);
    }

    /**
     * 이 세션에 남은 연결 무장을 버리고 <b>그 허가도 함께</b> 무효화한다.
     *
     * <p>{@code UnlinkService.abandon}과 같은 근거의 같은 모양이다. 흐름만 버리면 그것이 가리키던
     * 허가가 아무도 참조할 수 없는 채 수명을 산다.
     */
    void abandon(HttpSession session) {
        BybitConnectionFlow.Armed armed = BybitConnectionFlow.peekIncludingExpired(session);
        BybitConnectionFlow.discard(session);
        if (armed != null) {
            this.grants.invalidate(armed.userId(), COMMAND, TARGET);
        }
    }

    /**
     * 새 무장이 앞선 무장을 대신한다.
     *
     * <p>{@link #abandon}과 갈라 두는 이유는 방금 {@code issue}가 같은 {@code (user, command,
     * target)}의 허가를 이미 지웠기 때문이다. 한 번 더 지우면 방금 발급한 것이 사라진다
     * ({@code UnlinkService.supersedePreviousArming}과 같은 근거이며, target이 고정이라 조건이
     * 아예 없다).
     */
    private static void supersedePreviousArming(HttpSession session) {
        BybitConnectionFlow.discard(session);
    }

    /**
     * 최종 확인. <b>검증이 먼저이고 저장이 나중이다.</b>
     *
     * <p>네 단계가 순서대로다.
     *
     * <ol>
     *   <li>무장을 읽는다. 없거나 사용자가 다르면 단일 거부다.
     *   <li>admission을 <b>외부 호출 없이</b> 다시 본다 — 운영 제한과 기존 활성 연결.
     *   <li>제공자 세 엔드포인트를 지난다. 이 동안 열린 트랜잭션도 계정 잠금도 없다.
     *   <li>허가 소비와 다섯 저장을 한 트랜잭션으로 커밋하고, 커밋 뒤에만 세션을 회전시킨다.
     * </ol>
     *
     * <p><b>무장을 여기서 버리지 않는다.</b> 검증 실패는 정상 경로이고, 버리면 잘못된 키를 한 번
     * 넣은 사용자가 본인 확인부터 다시 해야 한다. 재전송 계약도 같은 이유로 무장을 요구한다
     * ({@code UnlinkService.complete}와 같은 근거).
     *
     * @param request 지금 이 요청. 세션 회전이 여기서만 가능하다
     * @param confirmation <b>클라이언트가 되돌려 보낸</b> 확인 문구. 준비가 돌려준 값과 같아야
     *     하며, 그 비교가 허가의 다섯 번째 축을 실제로 살아 있게 한다
     */
    Outcome complete(
            HttpServletRequest request,
            HttpSession session,
            UUID userId,
            SourceCredentials credentials,
            String confirmation) {
        BybitConnectionFlow.Armed armed = BybitConnectionFlow.peek(session);
        if (armed == null || !armed.userId().equals(userId)) {
            return rejected(SourceRejectionReason.NOT_PERMITTED);
        }
        /*
         * 클라이언트가 보여 준 문구와 서버가 발급한 문구가 같은가.
         *
         * 이 비교가 없으면 다섯 번째 귀속 축은 클라이언트가 영향을 줄 수 없는 서버 값에 묶이고,
         * 요청마다 문구를 새로 만든 뜻이 사라진다 — 판별력이 상수 시절로 돌아간다. 정규화를
         * 지나는 이유는 아래 소비 술어가 같은 정규화를 지난 해시를 비교하기 때문이다.
         *
         * 최종 권위는 여전히 소비 UPDATE의 술어다. 이 검사는 그 앞에서 같은 사실을 값으로
         * 돌려주어 실패가 "귀속 불일치"라는 구분되지 않는 하나에 섞이지 않게 한다.
         */
        if (!ActionGrant.normalize(armed.confirmation())
                .equals(ActionGrant.normalize(confirmation))) {
            return rejected(SourceRejectionReason.NOT_PERMITTED);
        }
        if (!this.sources.isConnectable()) {
            // 구성이 빈 스택이다. 제공자에 닿기 전에 닫는다(`start`와 같은 게이트).
            return rejected(SourceRejectionReason.UNAVAILABLE);
        }
        if (this.restrictions.isRestricted(COMMAND) || this.sources.hasActiveConnection(userId)) {
            // 외부 호출 0건으로 끝난다. 거절될 자격정보로 제공자를 두드리지 않는다.
            return rejected(SourceRejectionReason.NOT_PERMITTED);
        }

        SourceVerification verification =
                this.sources.verify(SourceProvider.BYBIT, credentials);
        if (verification instanceof SourceVerification.Rejected rejection) {
            // 허가는 소비되지 않았다. 같은 무장으로 고친 키를 다시 시도할 수 있다.
            return rejected(rejection.reason());
        }
        SourceVerification.Verified verified = (SourceVerification.Verified) verification;

        /*
         * 업무 변경이 이번에 실제로 돌았는가.
         *
         * 허가 포트는 처음 소비와 저장된 결과의 재전송을 같은 타입으로 답한다. 밖에서 그 둘을
         * 가르는 재료는 하나뿐이다 — 건넨 작업이 불렸는가(`UnlinkService.complete`와 같은 상자).
         */
        AtomicBoolean stored = new AtomicBoolean(false);
        Outcome outcome = consume(session, armed, verified, credentials, confirmation, stored);
        if (outcome instanceof Outcome.Connected && stored.get()) {
            rotate(request);
        }
        return outcome;
    }

    /** 허가 소비와 다섯 저장 한 트랜잭션. 저장소에 닿는 자리는 이 메서드 하나다. */
    private Outcome consume(
            HttpSession session,
            BybitConnectionFlow.Armed armed,
            SourceVerification.Verified verified,
            SourceCredentials credentials,
            String confirmation,
            AtomicBoolean stored) {
        ConsumptionRequest consumption =
                new ConsumptionRequest(
                        session,
                        armed.userId(),
                        armed.grantReference(),
                        COMMAND,
                        TARGET,
                        /*
                         * **클라이언트가 되돌려 보낸 값**을 그대로 해시한다. 서버 기록을 쓰면 이
                         * 술어가 클라이언트 입력과 무관해져 다섯 번째 축이 다시 죽는다. 두 값이
                         * 같다는 것은 위에서 이미 확인했으므로 여기 실리는 것은 사용자가 화면에서
                         * 실제로 본 문자열이다.
                         */
                        confirmation,
                        armed.operationId());
        try {
            Consumption result =
                    this.grants.consume(
                            consumption,
                            () -> {
                                /*
                                 * 운영 제한을 **이 트랜잭션 안에서** 다시 본다.
                                 *
                                 * 제공자 왕복은 최대 15초이고 그 사이에 운영자가 제한을 켤 수
                                 * 있다. 진입 게이트의 판정만 믿으면 그 커밋이 그대로 통과하며
                                 * "최종 저장 트랜잭션에서 다시 검사한다"가 다섯 중 하나에 대해
                                 * 성립하지 않는다. 사설 예외라 소비 UPDATE까지 함께 롤백된다.
                                 */
                                if (this.restrictions.isRestricted(COMMAND)) {
                                    throw new StoreRejected(SourceRejectionReason.NOT_PERMITTED);
                                }
                                SourceConnectionApi.StoreOutcome outcome =
                                        this.sources.store(
                                                armed.userId(),
                                                SourceProvider.BYBIT,
                                                verified,
                                                credentials);
                                if (outcome instanceof SourceConnectionApi.StoreOutcome.Rejected denial) {
                                    // 트랜잭션을 통째로 되돌린다. 허가도 소비되지 않은 채 남는다.
                                    throw new StoreRejected(denial.reason());
                                }
                                SourceConnectionApi.StoreOutcome.Stored saved =
                                        (SourceConnectionApi.StoreOutcome.Stored) outcome;
                                this.portfolios.attachActiveSource(
                                        armed.userId(),
                                        saved.connectionId(),
                                        saved.providerAccountId(),
                                        verified.verifiedAt());
                                stored.set(true);
                                return RESULT_CONNECTED;
                            });
            return result instanceof Consumption.ConsumedRequiringSessionRotation
                    ? new Outcome.Connected(verified.verifiedAt(), verified.policyVersion())
                    : rejected(SourceRejectionReason.NOT_PERMITTED);
        } catch (StoreRejected rejection) {
            // 소비 UPDATE까지 함께 롤백됐다. 저장소에 남은 변화는 0건이다.
            return rejected(rejection.reason());
        } catch (DataIntegrityViolationException conflict) {
            /*
             * 동시 요청이 부분 유일 색인에서 갈렸다(사용자당 활성 연결 하나, portfolio당 활성
             * source 하나, 제공자 계정 전역 유일). 진 쪽은 트랜잭션이 통째로 롤백되어 자격정보도
             * portfolio도 membership도 남기지 않으며, 허가도 소비되지 않았다.
             *
             * 이 예외가 허가 포트 자신의 `(user_id, operation_id)` 충돌일 수는 없다 — 그것은
             * 포트가 안에서 잡아 재전송으로 처리한다. 여기 오는 것은 우리 작업의 충돌뿐이므로
             * 단일 거부로 접는다.
             */
            return rejected(SourceRejectionReason.NOT_PERMITTED);
        }
    }

    /**
     * 커밋 뒤 세션 회전. <b>연결이 실제로 저장된 요청에서만</b> 실행된다.
     *
     * <p>{@code SECURITY-ACTION-1.0}이 허가 소비 성공에 요구하는 것이고 1.9의 결과 타입 이름
     * ({@code ConsumedRequiringSessionRotation})이 이 자리로 넘긴 의무다. 재전송에서는 돌지
     * 않는다 — 업무 변경이 다시 실행되지 않았는데 회전시키면 "커밋 뒤에만"이 깨진다.
     *
     * <p>여기서 실패해도 연결은 성공이다. 연결은 이미 커밋됐고 이 회전은 그 뒤의 곁다리이므로,
     * 예외를 올려보내면 클라이언트가 "연결하지 못했다"를 보면서 실제로는 연결된 상태가 된다
     * ({@code UnlinkService.settleSessions}와 같은 판단).
     */
    private static void rotate(HttpServletRequest request) {
        try {
            request.changeSessionId();
        } catch (RuntimeException ignored) {
            // 다음 요청이 같은 세션 상태를 다시 본다. 연결 자체는 이미 커밋됐다.
        }
    }

    /**
     * 이 요청의 확인 문구.
     *
     * <p>요청마다 다르다. 그것이 이 스토리가 고치는 것이며, 상수를 그대로 쓰면 해시가
     * {@code (command, target)}의 결정적 함수가 되어 다섯 번째 축의 판별력이 0이 된다.
     */
    private static String newConfirmation() {
        byte[] bytes = new byte[CONFIRMATION_CODE_BYTES];
        RANDOM.nextBytes(bytes);
        return CONFIRMATION_PREFIX + CODE_ENCODER.encodeToString(bytes);
    }

    private static Outcome rejected(SourceRejectionReason reason) {
        return new Outcome.Rejected(reason);
    }
}
