package com.bokrilog;

import com.bokrilog.ApiErrorHandler.ApiError;
import com.bokrilog.source.SourceConnectionApi;
import com.bokrilog.source.SourceCredentials;
import com.bokrilog.source.SourceRejectionReason;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 읽기 전용 Bybit 연결의 HTTP 경계.
 *
 * <p>네 가지만 답한다 — 지금 연결 상태를 조회하는 경로 하나, 자격정보 입력 폼을 열 준비를 하는
 * 경로 하나, 그 입력을 검증하고 저장하는 경로 하나, 그 준비를 되돌리는 경로 하나. 판정·검증·저장·
 * 세션 회전은 전부 {@link BybitConnectionService}가 소유하고 여기서 하는 일은 주체를 읽어 넘기고
 * 결과를 상태 코드와 본문으로 옮기는 것뿐이다({@code AuthSessionController}와 같은 분담).
 *
 * <p><b>이 컨트롤러가 인증으로 막는 첫 경로를 연다.</b> 접두사 {@code /api/connections/}에만
 * {@code authenticated()}가 걸리고 나머지는 {@code permitAll}을 유지한다
 * ({@code AuthSecurityConfig}) — 전역으로 켜면 Story 1.2가 고정한 404·405가 401·302로 뒤집힌다.
 * 그럼에도 아래 핸들러들이 주체를 다시 확인한다. 인가 규칙이 생겨도 핸들러 검사를 지우지 않는
 * 것이 이중 방어이며 {@code AuthSessionController.currentUserId}가 같은 관용이다.
 *
 * <p><b>Secret이 응답·주소·로그 어디에도 없다.</b> 자격정보는 {@code POST} 본문으로만 오고 성공
 * 응답에는 연결 사실과 시각·정책 버전뿐이다. 마스킹 값도 재표시하지 않는다 — 마스킹은 "일부를
 * 보여 준다"이고 이 폼의 계약은 "성공 뒤 다시 보여 주지 않는다"다.
 *
 * <p>루트 패키지 컨트롤러 관용을 따른다 — package-private 클래스, 중첩 record, {@code produces}
 * 명시({@code StatusController}·{@code AuthSessionController}).
 */
@RestController
@ConditionalOnWebApplication(type = Type.SERVLET)
class BybitConnectionController {

    /** 검증 시각을 문자열로 옮기는 형식. UTC instant 하나이며 locale에 좌우되지 않는다. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    /**
     * 거절 이유 → 안정 machine code.
     *
     * <p>닫힌 맵이다. 값이 하나 늘 때 대응을 빠뜨리면 아래 조회가 {@code null}을 돌려주고 그 요청은
     * 공통 {@code BAD_REQUEST}로 접히는데, 그러면 사용자가 다음 행동을 알 수 없게 된다. 그래서
     * enum 전체가 여기 있어야 하고 시험이 그 완전성을 단정한다.
     *
     * <p>{@code CONNECTION_} 접두사를 붙인다. 이 code들은 이 표면 하나의 것이며 Nginx가 만드는 어떤
     * 본문과도 겹치지 않는다 — 진입점은 422를 스스로 만들 수 없다.
     */
    private static final Map<SourceRejectionReason, String> CODES =
            Map.ofEntries(
                    Map.entry(SourceRejectionReason.NOT_PERMITTED, ApiErrorHandler.CODE_FORBIDDEN),
                    Map.entry(SourceRejectionReason.PROVIDER_ACCOUNT_TAKEN, "CONNECTION_ACCOUNT_TAKEN"),
                    Map.entry(SourceRejectionReason.INVALID_FORMAT, "CONNECTION_INVALID_FORMAT"),
                    Map.entry(
                            SourceRejectionReason.AUTHENTICATION_REJECTED,
                            "CONNECTION_AUTHENTICATION_REJECTED"),
                    Map.entry(SourceRejectionReason.WRITE_PERMISSION, "CONNECTION_WRITE_PERMISSION"),
                    Map.entry(
                            SourceRejectionReason.INSUFFICIENT_PERMISSION,
                            "CONNECTION_INSUFFICIENT_PERMISSION"),
                    Map.entry(SourceRejectionReason.SUB_ACCOUNT, "CONNECTION_SUB_ACCOUNT"),
                    Map.entry(SourceRejectionReason.IP_NOT_BOUND, "CONNECTION_IP_NOT_BOUND"),
                    Map.entry(
                            SourceRejectionReason.UNSUPPORTED_ACCOUNT_MODE,
                            "CONNECTION_UNSUPPORTED_ACCOUNT_MODE"),
                    Map.entry(SourceRejectionReason.WALLET_UNAVAILABLE, "CONNECTION_WALLET_UNAVAILABLE"),
                    Map.entry(SourceRejectionReason.TIMEOUT, "CONNECTION_TIMEOUT"),
                    Map.entry(SourceRejectionReason.RATE_LIMITED, "CONNECTION_RATE_LIMITED"),
                    Map.entry(
                            SourceRejectionReason.PROVIDER_UNAVAILABLE, "CONNECTION_PROVIDER_UNAVAILABLE"),
                    Map.entry(SourceRejectionReason.RESPONSE_FORMAT, "CONNECTION_RESPONSE_FORMAT"),
                    Map.entry(SourceRejectionReason.UNAVAILABLE, "CONNECTION_UNAVAILABLE"));

    /**
     * 422 응답의 사용자 문장. <b>하나뿐이다.</b>
     *
     * <p>이유마다 다른 문장을 여기 두지 않는다. 화면 카피는 {@code frontend/src/content/}가
     * 소유하고 클라이언트는 {@code code}로 자기 사전을 고르므로, 이유별 문장을 이 계층에도 두면
     * 아무도 렌더하지 않는 두 번째 사본이 생긴다 — 실제로 두 벌이 이미 갈라졌다("직접 다시" 대
     * "아래 버튼으로 다시"). 구분은 {@code code}가 나르고 안내는 화면이 소유한다.
     *
     * <p>제공자가 쓴 문장을 나르지 않는다. 응답 원문·permission 배열·지갑 값은 어댑터를 떠나지
     * 않으므로 여기 실을 값이 애초에 없다.
     */
    private static final String MESSAGE_CONNECTION_REJECTED =
            "Bybit 연결을 끝내지 못했습니다. 아래 code가 원인을 가리킵니다.";

    /** 남은 두 이유의 문장. 단일 거부는 공통 계약의 것을 그대로 쓴다. */
    private final BybitConnectionService connections;

    /**
     * 연결·해제 흐름.
     *
     * <p>이 컨트롤러가 그 둘을 아는 이유는 빈 순환이다. 네 흐름(재인증·연결·해제·Bybit 연결)이 같은
     * 화면에서 시작될 수 있으므로 무장 시각에 나머지를 버려야 하는데, 서비스끼리 서로를 주입받으면
     * 생성자 순환이 된다. 그래서 각 서비스는 <b>자기 흐름과 재인증만</b> 버리고 상대 서비스의
     * 흐름은 두 서비스를 함께 아는 컨트롤러가 버린다({@code AuthSessionController.abandonArmedFlows}
     * 와 같은 배치이며, 그쪽은 반대 방향으로 이 서비스를 안다).
     */
    private final LinkService linkService;

    private final UnlinkService unlinkService;

    BybitConnectionController(
            BybitConnectionService connections, LinkService linkService, UnlinkService unlinkService) {
        this.connections = connections;
        this.linkService = linkService;
        this.unlinkService = unlinkService;
    }

    /**
     * 연결 상태.
     *
     * <p>{@code null} 필드를 직렬화하지 않는다({@code AuthSessionController.SessionState}와 같은
     * 근거) — 연결되지 않은 계정의 응답에 빈 시각 필드를 실으면 그 필드의 존재가 계약에 하나 더
     * 는다.
     *
     * <p>자격정보도 제공자 계정 식별자도 담지 않는다. 화면이 알아야 하는 것은 "연결됐는가"와
     * "언제 검증됐는가"와 "어느 정책이 적용됐는가" 셋뿐이다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ConnectionState(
            boolean connected, @Nullable String verifiedAt, @Nullable String policyVersion) {}

    /**
     * 준비의 응답.
     *
     * <p>확인 문구 하나만 담는다. 화면이 그 문장을 <b>그대로</b> 렌더하고 최종 확인이 같은 값을
     * 되보내므로, 허가의 다섯 번째 축이 "사용자가 실제로 본 문구"의 함수가 된다
     * ({@code BybitConnectionFlow}의 javadoc).
     *
     * <p>허가 참조도 멱등 키도 담지 않는다. 그 둘은 서버가 세션에 들고 있으며 브라우저로 나가지
     * 않는다.
     */
    record ConnectionChallenge(String confirmation) {}

    /**
     * 최종 확인의 요청 본문.
     *
     * <p>{@code POST} 본문으로만 온다. 쿼리 문자열에 실으면 그 값이 주소·{@code Referer}·
     * 히스토리·access log에 남는다 — Nginx가 쿼리를 로그에 남기지 않도록 구성돼 있어도, 그 구성
     * 하나에 비밀의 수명을 걸지 않는다.
     *
     * @param confirmation 준비가 돌려준 문구 원문. 화면이 보여 준 그 문자열이다
     */
    record ConnectRequest(
            @Nullable String apiKey, @Nullable String apiSecret, @Nullable String confirmation) {}

    /**
     * 지금 연결 상태.
     *
     * <p>안전한 메서드라 CSRF·{@code Origin} 보호 대상이 아니다. 대신 캐시에는 남기지 않는다 —
     * 공유 캐시나 뒤로 가기가 이 값을 되살리면 로그아웃한 브라우저가 계정의 사실을 다시 그린다
     * ({@code AuthSessionController.links}와 같은 판단).
     */
    @GetMapping(
            path = AuthSecurityConfig.BYBIT_CONNECTION_PATH,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<ConnectionState> state(HttpServletResponse response) throws IOException {
        UUID userId = currentUserId();
        if (userId == null) {
            response.sendError(HttpStatus.FORBIDDEN.value());
            return null;
        }
        Optional<SourceConnectionApi.ActiveConnection> active = this.connections.activeConnection(userId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        active.map(
                                        connection ->
                                                new ConnectionState(
                                                        true,
                                                        TIMESTAMP.format(connection.verifiedAt()),
                                                        connection.policyVersion()))
                                .orElseGet(() -> new ConnectionState(false, null, null)));
    }

    /**
     * 자격정보 입력 폼을 열 준비를 한다. <b>아무것도 바꾸지 않는다.</b>
     *
     * <p>허가를 발급받아 세션에 일회성 무장을 심고 확인 문구를 돌려준다. 실제 검증과 저장은 그
     * 무장을 소비하는 {@link #connect}가 한다 — "최종 확인 전에는 아무것도 바뀌지 않는다"는 계약이
     * 두 경로로 성립한다({@code AuthSessionController.prepareUnlink}와 같은 배치).
     *
     * <p>답은 둘뿐이다 — 준비가 성립하지 않으면 403이고 그 밖은 200에 확인 문구다. 403에는 재인증
     * 없음·오래된 재인증·의심 세션·삭제 상태·운영 제한·이미 있는 활성 연결이 모두 접히며 응답으로
     * 구분되지 않는다.
     */
    @PostMapping(
            path = AuthSecurityConfig.BYBIT_CONNECTION_PATH,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> prepare(HttpServletRequest request) {
        UUID userId = currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null || session == null) {
            return error(SourceRejectionReason.NOT_PERMITTED, request);
        }
        BybitConnectionService.Preparation preparation = this.connections.start(session, userId);
        if (preparation instanceof BybitConnectionService.Preparation.Rejected rejection) {
            /*
             * 거부는 둘로만 갈린다 — 구성되지 않은 기능(422)과 그 밖의 하나(403). 뒤의 것에는
             * 재인증 없음·오래된 재인증·의심 세션·삭제 상태·운영 제한·이미 있는 활성 연결이 모두
             * 접히며 응답으로 구분되지 않는다.
             */
            return error(rejection.reason(), request);
        }
        BybitConnectionFlow.Armed armed =
                ((BybitConnectionService.Preparation.Armed) preparation).flow();
        /*
         * 상대 흐름은 무장이 확정된 뒤에만 버린다(Story 1.11의 리뷰가 잡은 규칙). 앞에 두면
         * 거절되는 준비 요청 하나가 진행 중이던 연결·해제 대화상자의 무장과 허가를 함께 없앤다.
         */
        this.linkService.abandon(session);
        this.unlinkService.abandon(session);
        return ResponseEntity.ok()
                // 확인 문구는 이 요청에만 유효하다. 캐시가 되살리면 다음 요청이 남의 문구를 본다.
                .cacheControl(CacheControl.noStore())
                .body(new ConnectionChallenge(armed.confirmation()));
    }

    /**
     * 최종 확인. <b>여기서만 제공자로 나가고 여기서만 상태가 바뀐다.</b>
     *
     * <p>검증을 먼저 끝내고 결과를 받은 뒤 허가 소비와 다섯 저장을 한 트랜잭션으로 커밋한다. 성공
     * 응답에는 연결 사실과 검증 시각·정책 버전뿐이며 <b>Secret도 API Key도 없다</b>.
     *
     * <p>거절은 두 종류로 나간다 — 단일 거부는 403이고, 사용자가 제공자 콘솔에서 고칠 수 있는
     * 사실은 422에 자기 code와 다음 행동을 담는다. 어느 쪽도 응답 원문·permission 배열·지갑 값을
     * 싣지 않는다.
     */
    @PostMapping(
            path = AuthSecurityConfig.BYBIT_CONNECTION_PATH + "/" + AuthSecurityConfig.CONNECT_SEGMENT,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> connect(
            @RequestBody ConnectRequest body, HttpServletRequest request) {
        UUID userId = currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null || session == null) {
            return error(SourceRejectionReason.NOT_PERMITTED, request);
        }
        if (body.apiKey() == null || body.apiSecret() == null || body.confirmation() == null) {
            return error(SourceRejectionReason.INVALID_FORMAT, request);
        }

        BybitConnectionService.Outcome outcome =
                this.connections.complete(
                        request,
                        session,
                        userId,
                        new SourceCredentials(body.apiKey(), body.apiSecret()),
                        body.confirmation());
        if (outcome instanceof BybitConnectionService.Outcome.Rejected rejection) {
            return error(rejection.reason(), request);
        }
        BybitConnectionService.Outcome.Connected connected =
                (BybitConnectionService.Outcome.Connected) outcome;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new ConnectionState(
                                true,
                                TIMESTAMP.format(connected.verifiedAt()),
                                connected.policyVersion()));
    }

    /**
     * 열어 둔 준비를 되돌린다. <b>준비가 만든 것을 지운다.</b>
     *
     * <p>준비가 서버 상태를 만들므로(무장과 허가) 되돌리는 경로가 있어야 한다. 화면의 취소 문장이
     * "바뀐 것은 없습니다"라고 말하는데 그 말이 참이려면 준비가 남긴 둘도 함께 사라져야 하고, 남겨
     * 두면 아무도 참조하지 않는 허가가 수명을 산다({@code AuthSessionController.cancelUnlink}와
     * 같은 근거).
     *
     * <p><b>인증된 요청에는 언제나 204다.</b> 무장이 있었는지로 답을 나누지 않는다 — 나누면 이
     * 경로가 "저 세션이 지금 무엇을 준비했는가"를 묻는 오라클이 된다. 그래서 멱등이고, 되돌릴 것이
     * 없을 때 불러도 아무 일도 하지 않는다.
     *
     * <p>미인증 요청은 여기 오지 않는다. 이 접두사에는 {@code authenticated()}가 걸려 있어 인가
     * 필터가 먼저 401로 끝내고({@code AuthSecurityConfig}), CSRF·{@code Origin}이 어긋난 요청은
     * 그보다 앞에서 403으로 끝난다.
     */
    @PostMapping(
            AuthSecurityConfig.BYBIT_CONNECTION_PATH + "/" + AuthSecurityConfig.CANCEL_SEGMENT)
    void cancel(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            this.connections.abandon(session);
        }
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }

    /**
     * 거절 하나를 공통 오류 계약의 모양으로 옮긴다.
     *
     * <p>상태 코드가 둘뿐이다. 단일 거부는 403이고 나머지는 전부 422다 — 요청의 모양은 맞는데 그
     * 내용으로는 처리할 수 없다는 뜻이며, Nginx가 스스로 만들 수 없는 상태라 두 계층이 같은 code를
     * 나눠 갖지 않는다.
     */
    private static ResponseEntity<ApiError> error(
            SourceRejectionReason reason, HttpServletRequest request) {
        HttpStatus status =
                reason == SourceRejectionReason.NOT_PERMITTED
                        ? HttpStatus.FORBIDDEN
                        : HttpStatus.UNPROCESSABLE_CONTENT;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(
                        new ApiError(
                                codeOf(reason),
                                reason == SourceRejectionReason.NOT_PERMITTED
                                        ? ApiErrorHandler.MESSAGE_FORBIDDEN
                                        : MESSAGE_CONNECTION_REJECTED,
                                ApiErrorHandler.correlationIdOf(
                                        request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER))));
    }

    /** 거절 이유가 전부 code를 갖는가. 시험이 닫힌 맵의 완전성을 보는 창이다. */
    static boolean covers(SourceRejectionReason reason) {
        return CODES.containsKey(reason);
    }

    /**
     * 이 표면이 낼 수 있는 안정 code 전부.
     *
     * <p>프런트엔드의 {@code REJECTIONS} 맵이 같은 집합을 덮어야 한다. 두 계층이 한 계약을 나눠
     * 갖는 자리이며({@code CSRF_HEADER_NAME}과 같은 종류의 중복) 각 층의 시험이 자기 쪽을 이
     * 목록과 대조한다 — 갈라지면 화면이 모르는 code를 받아 일반 문장으로 접는다.
     */
    static java.util.Set<String> emittedCodes() {
        return java.util.Set.copyOf(CODES.values());
    }

    /** 그 거절 이유의 안정 machine code. 시험과 검증 스크립트가 같은 값을 본다. */
    static String codeOf(SourceRejectionReason reason) {
        return CODES.getOrDefault(reason, ApiErrorHandler.CODE_BAD_REQUEST);
    }

    /**
     * 지금 요청 주체의 내부 {@code user_id}.
     *
     * <p>{@code AuthSessionController.currentUserId}와 같은 판정이다. 인가 규칙이 이 접두사를 이미
     * 막으므로 이 값이 {@code null}인 요청은 <b>지금 배선에서는 여기 도달하지 않는다</b> — 미인증
     * 요청의 실제 답은 인가 필터의 401이다. 그럼에도 핸들러 검사를 지우지 않는 것은 이중 방어이고,
     * 규칙이 실수로 넓어지거나 좁아지는 날 이 검사가 마지막 말을 한다.
     */
    private static @Nullable UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
