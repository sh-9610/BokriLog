package com.bokrilog;

import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.identity.AccountDeletionApi.DeletionRequest;
import com.bokrilog.identity.AccountDeletionRunApi;
import com.bokrilog.operations.BackupDestructionApi;
import com.bokrilog.sync.IdempotencyKey;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자의 계정 삭제 요청의 HTTP 경계(Story 5.1). {@code identity}에는 HTTP가 없으므로 루트가 맡는다.
 *
 * <ul>
 *   <li>{@code GET /api/account/deletion}: 200 {@code {available, policy, request}} — 삭제 정책(버전·대상 code·백업 파기 기한·법정 분리
 *       보존)과, 이미 요청했으면 그 요청(ID·시각·상태·정책 버전·진행 {@code progress} — 지금 값, Story 5.2)과 설정된 지원 연락처.
 *       production이면 {@code available:false}다. 삭제가 시작된 계정의 남은 세션도 읽는다(READ). 삭제가 끝난 뒤의 완료 확인
 *       전용 세션(Story 5.4)은 {@code request} 대신 {@code completion}(운영 삭제 시각·백업 파기 기한·상태·전체 완료)을 받는다 —
 *       인증 주체가 없는 그 세션이 닿는 경로는 이 조회와 확정 재전송의 거부(409 {@code ACCOUNT_DELETION_COMPLETED}) 둘뿐이다.
 *   <li>{@code POST /api/account/deletion}: 준비 — 본문 {@code {"policyVersion":<화면이 본 정책 버전>}}, 200 {@code {policyVersion, confirmation}}.
 *       허가를 발급하고 무장할 뿐 아무것도 바꾸지 않는다. 정책 버전이 지금과 다르면 409 {@code ACCOUNT_DELETION_POLICY_CHANGED}다.
 *   <li>{@code POST /api/account/deletion/confirm}: {@code {"policyVersion":…,"confirmation":<입력한 문구>}}과
 *       {@code Idempotency-Key}. 200 {@code {requestId, requestedAt, state, policyVersion, progress}} — 처음 요청과 같은 키의
 *       재전송이 같은 본문이다. {@code progress}는 지금 값이 아니라 고정 {@link #CONFIRMED_PROGRESS}다(Story 5.2).
 *   <li>{@code POST /api/account/deletion/cancel}: 204 — 무장과 그 허가를 버린다. 인증된 요청에는 언제나 204다.
 *   <li>{@code POST /api/account/deletion/retry}(Story 5.2): 본문 없음, CSRF 헤더·동일 출처 {@code Origin}·{@code Idempotency-Key}
 *       필수(재인증 없음). 202 {@code RequestView} — 재시도 필요로 멈춘 삭제 실행을 다시 대기열에 넣었다(같은 키의 재전송도 202). 키가
 *       없거나 모양이 틀리면 422 {@code IDEMPOTENCY_KEY_REQUIRED}, 삭제 요청이 없거나 계정을 읽을 수 없으면 없는 경로와 같은 404,
 *       실행이 재시도 필요가 아니면(아직 집기 전·실행 중·완료) 409 {@code ACCOUNT_DELETION_NOT_RETRYABLE}이다. CSRF·{@code Origin}이 맞지
 *       않으면 보안 필터가 403, 세션이 없으면 401이다.
 * </ul>
 *
 * <p>거절은 공통 오류 모양이고 code는 {@link #REFUSALS}의 닫힌 맵이다. 인증·CSRF·{@code Origin}은
 * {@code AuthSecurityConfig.isAccountPath} 하나가 건다. 모든 응답이 {@code no-store}다.
 *
 * <p><b>로그에 사용자·요청 ID·허가 참조·확인 문구·멱등 키·정책 원값이 없다.</b> 한 요청에 이 컨트롤러가 쓰는 줄은 하나다 — 단계·결과
 * code·상태·지연뿐이다. 요청을 실제로 만든 확정에서는 서비스가 한 줄({@code account deletion requested}, 상태만)을 더 쓰고, 커밋 뒤
 * 세션 정리 단계가 실패하면 그 단계 code와 예외의 단순 클래스 이름 한 줄({@code account deletion settle failed})이 더 남는다.
 */
@RestController
@ConditionalOnWebApplication(type = Type.SERVLET)
class AccountDeletionController {

    static final String DELETION_PATH = AuthSecurityConfig.ACCOUNT_PATH + "/deletion";

    static final String CONFIRM_PATH = DELETION_PATH + "/confirm";

    static final String CANCEL_PATH = DELETION_PATH + "/" + AuthSecurityConfig.CANCEL_SEGMENT;

    /** 삭제 실행의 다시 시도(Story 5.2). */
    static final String RETRY_PATH = DELETION_PATH + "/retry";

    /** 진행 상태: 삭제 단계가 대기·실행 중이다(실행 전 포함). */
    static final String PROGRESS_IN_PROGRESS = "IN_PROGRESS";

    /** 진행 상태: 한 단계가 실패해 멈췄다 — 다시 시도를 받는다. */
    static final String PROGRESS_RETRY_NEEDED = "RETRY_NEEDED";

    /**
     * 진행 상태: 운영 데이터 삭제 완료(백업 파기 완료가 아니다). 그 커밋에서 요청 세션은 완료 확인 전용 세션이 되므로(Story 5.4) 인증된
     * 화면이 이 값을 받을 일은 드물다 — 완료 뒤의 사실은 완료 조회({@link CompletionView})가 싣는다.
     */
    static final String PROGRESS_ERASED = "OPERATIONAL_DATA_ERASED";

    /** 진행 상태: 전체 삭제 완료(Story 5.4 — 운영 삭제 + 백업 파기 + 법정 분리 보존 처리). 인증된 화면이 이 값을 받을 일은 없다. */
    static final String PROGRESS_COMPLETED = "DELETION_COMPLETED";

    /** 삭제가 이미 끝났다 — 완료 확인 전용 세션의 확정 재전송(Story 5.4). 아무것도 만들지 않는다. */
    static final String COMPLETED = "ACCOUNT_DELETION_COMPLETED";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AccountDeletionController.class);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    /** 본문은 수십 바이트다. 훨씬 긴 것은 이 요청이 아니다. */
    private static final int MAX_BODY_CHARS = 512;

    /** 입력 문구는 열 자 안팎이다. 넉넉한 상한만 둔다. */
    private static final int MAX_CONFIRMATION_CHARS = 128;

    /** 정책 버전은 `ACCOUNT-DELETION-<major>.<minor>` 모양이라 스무 자 안팎이다. 넉넉한 상한만 둔다. */
    private static final int MAX_POLICY_VERSION_CHARS = 64;

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private record Refusal(HttpStatus status, String code, String message) {}

    /** 거절 code → 상태와 안전한 문장. 닫힌 맵이며 기계 값은 code 하나다. 화면의 맵과 공유 표가 같은 집합을 본다. */
    private static final Map<String, Refusal> REFUSALS = Map.ofEntries(
            Map.entry(AccountDeletionRequestService.NOT_PERMITTED, new Refusal(HttpStatus.FORBIDDEN,
                    ApiErrorHandler.CODE_FORBIDDEN, ApiErrorHandler.MESSAGE_FORBIDDEN)),
            Map.entry(AccountDeletionRequestService.REAUTHENTICATION_REQUIRED, new Refusal(HttpStatus.FORBIDDEN,
                    AccountDeletionRequestService.REAUTHENTICATION_REQUIRED,
                    "계정을 삭제하려면 먼저 본인 확인이 필요합니다.")),
            Map.entry(AccountDeletionRequestService.NOT_CONFIRMED, new Refusal(HttpStatus.FORBIDDEN,
                    AccountDeletionRequestService.NOT_CONFIRMED,
                    "확인된 삭제 요청이 아니어서 계정을 삭제하지 않았습니다. 설정 화면을 다시 불러와 주세요.")),
            Map.entry(AccountDeletionRequestService.CONFIRMATION_MISMATCH, new Refusal(HttpStatus.FORBIDDEN,
                    AccountDeletionRequestService.CONFIRMATION_MISMATCH,
                    "입력한 문구가 확인 문구와 다릅니다. 다시 입력해 주세요.")),
            Map.entry(AccountDeletionRequestService.POLICY_CHANGED, new Refusal(HttpStatus.CONFLICT,
                    AccountDeletionRequestService.POLICY_CHANGED,
                    "삭제 정책이 바뀌었습니다. 정책을 다시 확인해 주세요.")),
            Map.entry(AccountDeletionRequestService.IN_PROGRESS, new Refusal(HttpStatus.CONFLICT,
                    AccountDeletionRequestService.IN_PROGRESS, "이미 계정 삭제가 요청돼 진행 중입니다.")),
            Map.entry(AccountDeletionRequestService.UNAVAILABLE, new Refusal(HttpStatus.FORBIDDEN,
                    AccountDeletionRequestService.UNAVAILABLE, "지금은 계정 삭제를 요청할 수 없습니다.")),
            Map.entry(AccountDeletionRequestService.NOT_RETRYABLE, new Refusal(HttpStatus.CONFLICT,
                    AccountDeletionRequestService.NOT_RETRYABLE, "지금은 다시 시도할 삭제 단계가 없습니다. 진행 상태를 다시 확인해 주세요.")),
            Map.entry(COMPLETED, new Refusal(HttpStatus.CONFLICT, COMPLETED,
                    "계정 삭제가 이미 끝났습니다. 새로 요청할 것이 없습니다.")),
            Map.entry(IdempotencyKey.CONFLICT_CODE, new Refusal(HttpStatus.CONFLICT, IdempotencyKey.CONFLICT_CODE,
                    "같은 요청 키가 다른 삭제 요청에 이미 쓰였습니다. 화면을 새로 열어 상태를 확인해 주세요.")));

    /** 삭제 정책. 서버 상수 그대로다. */
    record PolicyView(String version, List<String> targets, int backupDestroyDays,
            List<AccountDeletionPolicy.LegalRetention> legalRetention) {}

    /**
     * 삭제 실행의 진행(Story 5.2). 금융 상세·건수·실패 원인 원문이 없다 — 상태와 실패한 단계 code(순서대로)뿐이다.
     *
     * @param status {@link #PROGRESS_IN_PROGRESS}·{@link #PROGRESS_RETRY_NEEDED}·{@link #PROGRESS_ERASED}·
     *     {@link #PROGRESS_COMPLETED}(Story 5.4)
     * @param failedSteps 재시도 필요인 단계 code
     */
    record ProgressView(String status, List<String> failedSteps) {}

    /**
     * 확정 응답의 진행 — 언제나 이 고정 값이다. 같은 키의 재전송은 처음과 바이트까지 같은 본문이어야 하므로(Story 5.1 재생 계약) Worker가
     * 이미 돌기 시작했어도 지금의 진행을 싣지 않는다. 지금의 진행은 조회와 다시 시도 응답만 싣는다.
     */
    static final ProgressView CONFIRMED_PROGRESS = new ProgressView(PROGRESS_IN_PROGRESS, List.of());

    /** 요청 하나. 사용자 식별자가 없다. */
    record RequestView(String requestId, String requestedAt, String state, String policyVersion,
            ProgressView progress) {}

    /**
     * 완료 조회(Story 5.4) — 완료 확인 전용 세션만 받는다. 사용자 식별자·금융 상세가 없다. 모든 값이 서버 값이다.
     *
     * @param requestId 삭제 요청 ID
     * @param requestedAt 요청 시각(tombstone의 요청 시각). tombstone을 읽지 못했으면 {@code null}
     * @param operationalErasedAt 운영 데이터 삭제 완료 시각
     * @param backupDeadline 백업 사본 파기 기한(요청 + 30일). tombstone을 읽지 못했으면 {@code null}
     * @param backupDestruction 백업 파기 상태 {@code PENDING}·{@code OVERDUE}·{@code DESTROYED}. tombstone을 읽지 못했으면 {@code null}
     *     — 완료로 말하지 않는다
     * @param backupDestroyedAt 백업 파기 완료 시각. 완료가 아니면 {@code null}
     * @param deletionCompleted 전체 삭제 완료(운영 삭제 + 백업 파기 + 법정 분리 보존 처리)인가
     * @param deletionCompletedAt 전체 삭제 완료 시각. 아니면 {@code null}
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record CompletionView(String requestId, @Nullable String requestedAt, String operationalErasedAt,
            @Nullable String backupDeadline, @Nullable String backupDestruction, @Nullable String backupDestroyedAt,
            boolean deletionCompleted, @Nullable String deletionCompletedAt) {}

    /**
     * 조회 응답. 요청이 없으면 {@code request}가 {@code null}로 실린다. 완료 확인 전용 세션이면 {@code request}는 {@code null}이고
     * {@code completion}이 실린다(Story 5.4 — 그 밖에서는 나가지 않는다). 지원 연락처는 요청이 있거나 완료 조회일 때 설정돼 있으면 실린다
     * (deferred 170 — 진행·완료 화면의 지원 경로).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    record DeletionView(boolean available, PolicyView policy, @Nullable RequestView request,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable CompletionView completion,
            @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String supportContact) {}

    /** 준비 응답. 허가 참조는 없다. */
    record Challenge(String policyVersion, String confirmation) {}

    /** 확정 본문. */
    record ConfirmBody(String policyVersion, String confirmation) {}

    private final AccountDeletionRequestService deletions;

    private final AccountAccessApi accounts;

    private final LinkService linkService;

    private final UnlinkService unlinkService;

    private final BybitConnectionService connections;

    private final BybitRevocationCommandService revocations;

    /** 완료 확인 전용 세션의 판독(Story 5.4). */
    private final DeletionCompletionSessions completions;

    /** 완료 조회의 실행 상태(identity, Story 5.4). */
    private final AccountDeletionRunApi runs;

    /** 완료 조회의 백업 파기 상태(operations, Story 5.4). */
    private final BackupDestructionApi destruction;

    /** 진행·완료 화면의 지원 연락처(deferred 170). 비면 싣지 않는다. */
    private final SupportContact supportContact;

    AccountDeletionController(AccountDeletionRequestService deletions, AccountAccessApi accounts,
            LinkService linkService, UnlinkService unlinkService, BybitConnectionService connections,
            BybitRevocationCommandService revocations, DeletionCompletionSessions completions,
            AccountDeletionRunApi runs, BackupDestructionApi destruction, SupportContact supportContact) {
        this.completions = completions;
        this.runs = runs;
        this.destruction = destruction;
        this.supportContact = supportContact;
        this.deletions = deletions;
        this.accounts = accounts;
        this.linkService = linkService;
        this.unlinkService = unlinkService;
        this.connections = connections;
        this.revocations = revocations;
    }

    /**
     * 삭제 정책과 요청 상태. 안전한 메서드라 CSRF·{@code Origin} 대상이 아니다. 삭제가 시작된 계정의 남은 요청 세션도 읽는다 —
     * 진행 화면이 요청 시각·정책 버전을 그린다.
     */
    @GetMapping(path = DELETION_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> state(HttpServletRequest request) {
        long started = System.nanoTime();
        UUID userId = ControllerSupport.currentUserId();
        if (userId == null) {
            // 인증 주체가 없다. 완료 확인 전용 세션(Story 5.4)만 인가 규칙을 지나 여기까지 온다 — 그 세션의 완료 조회다.
            return completion(request, started);
        }
        if (this.accounts.decide(userId, userId, AccountAccessKind.READ) != AccessDecision.ALLOWED) {
            return answer("state", ControllerSupport.error(HttpStatus.NOT_FOUND, ApiErrorHandler.CODE_NOT_FOUND,
                    ApiErrorHandler.MESSAGE_NOT_FOUND, request), ApiErrorHandler.CODE_NOT_FOUND, started);
        }
        RequestView existing;
        try {
            existing = this.deletions.requestOf(userId).map(found -> viewOf(found, progressOf(userId)))
                    .orElse(null);
        } catch (RuntimeException failure) {
            return answer("state", internal(failure, request), "ERROR", started);
        }
        return answer("state", ControllerSupport.noStoreJson(HttpStatus.OK, new DeletionView(this.deletions.available(),
                policyView(), existing, null, existing == null ? null : this.supportContact.value())),
                existing == null ? "NONE" : AccountDeletionRequestService.STATE_IN_PROGRESS, started);
    }

    private static PolicyView policyView() {
        return new PolicyView(AccountDeletionPolicy.VERSION, AccountDeletionPolicy.TARGETS,
                AccountDeletionPolicy.BACKUP_DESTROY_DAYS, AccountDeletionPolicy.LEGAL_RETENTION);
    }

    /**
     * 완료 확인 전용 세션의 완료 조회(Story 5.4). 세션이 든 삭제 요청 ID로 identity의 실행(운영 완료·전체 완료)과 operations의 tombstone
     * (백업 파기 기한·상태)을 읽는다 — 모두 서버 값이다. 완료 세션이 아니거나 그 요청의 끝난 실행이 없으면 없는 경로와 같은 404다. 기한
     * 초과·파기 미완료를 완료로 말하지 않는다 — tombstone을 읽지 못하면 그 칸은 비어 있다.
     */
    private ResponseEntity<?> completion(HttpServletRequest request, long started) {
        CompletionView view;
        try {
            String requestId = this.completions.requestIdOf(request).orElse(null);
            AccountDeletionRunApi.CompletedDeletion finished =
                    requestId == null ? null : this.runs.completedDeletionOf(requestId).orElse(null);
            if (requestId == null || finished == null) {
                return answer("completion", ControllerSupport.error(HttpStatus.NOT_FOUND, ApiErrorHandler.CODE_NOT_FOUND,
                        ApiErrorHandler.MESSAGE_NOT_FOUND, request), ApiErrorHandler.CODE_NOT_FOUND, started);
            }
            BackupDestructionApi.Destruction backup = this.destruction.destructionOf(requestId).orElse(null);
            java.time.Instant completedAt = finished.deletionCompletedAt();
            view = new CompletionView(requestId,
                    backup == null ? null : TIMESTAMP.format(backup.requestedAt()),
                    TIMESTAMP.format(finished.operationalErasedAt()),
                    backup == null ? null : TIMESTAMP.format(backup.backupDeadline()),
                    backup == null ? null : backup.status().name(),
                    backup == null || backup.destroyedAt() == null ? null : TIMESTAMP.format(backup.destroyedAt()),
                    completedAt != null,
                    completedAt == null ? null : TIMESTAMP.format(completedAt));
        } catch (RuntimeException failure) {
            return answer("completion", internal(failure, request), "ERROR", started);
        }
        return answer("completion", ControllerSupport.noStoreJson(HttpStatus.OK, new DeletionView(this.deletions.available(),
                policyView(), null, view, this.supportContact.value())),
                view.deletionCompleted() ? PROGRESS_COMPLETED : PROGRESS_ERASED, started);
    }

    /** 준비. 허가를 발급하고 무장할 뿐 아무것도 바꾸지 않는다. 본문은 화면이 본 정책 버전 하나다. */
    @PostMapping(path = DELETION_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> prepare(@RequestBody(required = false) @Nullable String body, HttpServletRequest request) {
        long started = System.nanoTime();
        String seenVersion = preparedVersionOf(body);
        if (seenVersion == null) {
            return answer("prepare", ControllerSupport.error(HttpStatus.BAD_REQUEST, ApiErrorHandler.CODE_BAD_REQUEST,
                    ApiErrorHandler.MESSAGE_BAD_REQUEST, request), ApiErrorHandler.CODE_BAD_REQUEST, started);
        }
        UUID userId = ControllerSupport.currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null || session == null) {
            return answer("prepare", refused(AccountDeletionRequestService.NOT_PERMITTED, request),
                    AccountDeletionRequestService.NOT_PERMITTED, started);
        }
        AccountDeletionRequestService.Preparation preparation;
        try {
            preparation = this.deletions.prepare(session, userId, seenVersion);
        } catch (RuntimeException failure) {
            return answer("prepare", internal(failure, request), "ERROR", started);
        }
        if (preparation instanceof AccountDeletionRequestService.Preparation.Refused refusal) {
            return answer("prepare", refused(refusal.code(), request), refusal.code(), started);
        }
        AccountDeletionFlow.Armed armed = ((AccountDeletionRequestService.Preparation.Armed) preparation).flow();
        /*
         * 같은 설정 화면의 다른 흐름은 무장이 확정된 뒤에만 버린다(Story 1.11의 규칙). 앞에 두면 거절되는 준비 하나가 진행 중이던
         * 연결·해제 대화상자의 무장과 허가를 함께 없앤다. 재인증 무장은 준비가 이미 버렸다.
         */
        this.linkService.abandon(session);
        this.unlinkService.abandon(session);
        this.connections.abandon(session);
        this.revocations.abandon(session);
        return answer("prepare", ControllerSupport.noStoreJson(HttpStatus.OK,
                new Challenge(armed.policyVersion(), armed.confirmation())), "PREPARED", started);
    }

    /**
     * 확정. 처음 요청이면 요청 행·삭제 진행 전환·허가 소비가 한 커밋이고, 그 뒤 다른 세션을 폐기하고 이 세션의 재인증 시각만 지운다 —
     * 이 세션의 ID는 회전하지 않는다(응답 유실 뒤 같은 쿠키의 재전송이 같은 200을 받아야 한다).
     */
    @PostMapping(path = CONFIRM_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> confirm(@RequestBody(required = false) @Nullable String body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) @Nullable String key,
            HttpServletRequest request) {
        long started = System.nanoTime();
        ConfirmBody parsed = bodyOf(body);
        if (parsed == null) {
            return answer("confirm", ControllerSupport.error(HttpStatus.BAD_REQUEST, ApiErrorHandler.CODE_BAD_REQUEST,
                    ApiErrorHandler.MESSAGE_BAD_REQUEST, request), ApiErrorHandler.CODE_BAD_REQUEST, started);
        }
        if (!IdempotencyKey.isValid(key)) {
            return answer("confirm", ControllerSupport.error(HttpStatus.UNPROCESSABLE_CONTENT,
                    IdempotencyKey.REQUIRED_CODE, ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED, request),
                    IdempotencyKey.REQUIRED_CODE, started);
        }
        UUID userId = ControllerSupport.currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null && this.completions.requestIdOf(request).isPresent()) {
            /*
             * 삭제가 끝난 뒤의 확정 재전송(Story 5.4) — 완료 확인 전용 세션만 인가 규칙을 지나 여기 온다. 계정·요청·세션 어느 것도 다시
             * 만들지 않고 거부 code 하나로 답한다(응답 유실 뒤의 재전송이 "이미 끝났다"를 알게 한다).
             */
            return answer("confirm", refused(COMPLETED, request), COMPLETED, started);
        }
        if (userId == null || session == null) {
            return answer("confirm", refused(AccountDeletionRequestService.NOT_PERMITTED, request),
                    AccountDeletionRequestService.NOT_PERMITTED, started);
        }
        AccountDeletionRequestService.Outcome outcome;
        try {
            outcome = this.deletions.confirm(session, userId, parsed.policyVersion(), parsed.confirmation(),
                    key);
        } catch (RuntimeException failure) {
            return answer("confirm", internal(failure, request), "ERROR", started);
        }
        return switch (outcome) {
            case AccountDeletionRequestService.Outcome.Refused refusal ->
                    answer("confirm", refused(refusal.code(), request), refusal.code(), started);
            case AccountDeletionRequestService.Outcome.Done done -> answer("confirm",
                    ControllerSupport.noStoreJson(HttpStatus.OK, viewOf(done.request(), CONFIRMED_PROGRESS)),
                    done.created() ? "REQUESTED" : "REPLAYED", started);
        };
    }

    /** 무장과 그 허가를 버린다. 인증된 요청에는 언제나 204다(준비를 묻는 오라클이 되지 않게). */
    @PostMapping(CANCEL_PATH)
    void cancel(HttpServletRequest request, HttpServletResponse response) {
        long started = System.nanoTime();
        HttpSession session = request.getSession(false);
        String result = "CANCELLED";
        if (session != null) {
            try {
                this.deletions.abandon(session);
            } catch (RuntimeException failure) {
                // 무장은 세션에서 이미 사라졌다. 남은 허가는 5분 안에 만료된다.
                if (LOG.isDebugEnabled()) {
                    LOG.debug("account deletion cancel failed: failure={}", failure.getClass().getSimpleName());
                }
                result = "ERROR";
            }
        }
        response.setStatus(HttpStatus.NO_CONTENT.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        log("cancel", result, HttpStatus.NO_CONTENT.value(), started);
    }

    /**
     * 삭제 실행의 다시 시도(Story 5.2). 요청 세션의 상태 전용 조작이다 — 재인증·허가가 없다(삭제 중 계정은 재인증할 수 없다). CSRF·
     * {@code Origin}·{@code Idempotency-Key}를 요구한다. 202 {@code RequestView} — 실패·미실행 단계만 다시 도는 실행을 대기열에 넣었다.
     * 같은 키의 재전송은 같은 202이고, 실행이 재시도 필요가 아니면 409 {@link AccountDeletionRequestService#NOT_RETRYABLE}이다. 요청이
     * 없거나 계정을 읽을 수 없으면 없는 경로와 같은 404다. 자동 재시도는 없다 — 사용자가 누를 때만 온다.
     */
    @PostMapping(path = RETRY_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> retry(@RequestHeader(name = IdempotencyKey.HEADER, required = false) @Nullable String key,
            HttpServletRequest request) {
        long started = System.nanoTime();
        if (!IdempotencyKey.isValid(key)) {
            return answer("retry", ControllerSupport.error(HttpStatus.UNPROCESSABLE_CONTENT,
                    IdempotencyKey.REQUIRED_CODE, ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED, request),
                    IdempotencyKey.REQUIRED_CODE, started);
        }
        UUID userId = ControllerSupport.currentUserId();
        if (userId == null || this.accounts.decide(userId, userId, AccountAccessKind.READ) != AccessDecision.ALLOWED) {
            return answer("retry", ControllerSupport.error(HttpStatus.NOT_FOUND, ApiErrorHandler.CODE_NOT_FOUND,
                    ApiErrorHandler.MESSAGE_NOT_FOUND, request), ApiErrorHandler.CODE_NOT_FOUND, started);
        }
        AccountDeletionRequestService.Retry outcome;
        try {
            outcome = this.deletions.retry(userId, key);
        } catch (RuntimeException failure) {
            return answer("retry", internal(failure, request), "ERROR", started);
        }
        return switch (outcome) {
            case AccountDeletionRequestService.Retry.Refused refusal
                    when AccountDeletionRequestService.NOT_PERMITTED.equals(refusal.code()) ->
                    answer("retry", ControllerSupport.error(HttpStatus.NOT_FOUND, ApiErrorHandler.CODE_NOT_FOUND,
                            ApiErrorHandler.MESSAGE_NOT_FOUND, request), ApiErrorHandler.CODE_NOT_FOUND, started);
            case AccountDeletionRequestService.Retry.Refused refusal ->
                    answer("retry", refused(refusal.code(), request), refusal.code(), started);
            case AccountDeletionRequestService.Retry.Accepted accepted -> answer("retry",
                    ControllerSupport.noStoreJson(HttpStatus.ACCEPTED,
                            viewOf(accepted.request(), settledProgressOf(userId))),
                    accepted.replayed() ? "REPLAYED" : "ACCEPTED", started);
        };
    }

    /** 이 표면이 낼 수 있는 거절 code 전부(공통 400·500 제외). 화면의 맵과 공유 표가 같은 집합을 덮는다. */
    static Set<String> emittedCodes() {
        var codes = new java.util.TreeSet<String>();
        REFUSALS.values().forEach(refusal -> codes.add(refusal.code()));
        codes.add(IdempotencyKey.REQUIRED_CODE);
        return Set.copyOf(codes);
    }

    /**
     * 확정 본문, 또는 본문이 없거나 JSON 객체가 아니거나 두 필드가 적당한 길이의 문자열이 아니면 {@code null}. 다른 필드는 무시한다.
     */
    static @Nullable ConfirmBody bodyOf(@Nullable String body) {
        tools.jackson.databind.JsonNode node = jsonObjectOf(body);
        if (node == null) return null;
        String version = textOf(node, "policyVersion", MAX_POLICY_VERSION_CHARS);
        String phrase = textOf(node, "confirmation", MAX_CONFIRMATION_CHARS);
        return version == null || phrase == null ? null : new ConfirmBody(version, phrase);
    }

    /** 준비 본문의 정책 버전(사용자가 화면에서 본 값), 또는 본문이 그 모양이 아니면 {@code null}. 다른 필드는 무시한다. */
    static @Nullable String preparedVersionOf(@Nullable String body) {
        tools.jackson.databind.JsonNode node = jsonObjectOf(body);
        return node == null ? null : textOf(node, "policyVersion", MAX_POLICY_VERSION_CHARS);
    }

    private static tools.jackson.databind.@Nullable JsonNode jsonObjectOf(@Nullable String body) {
        if (body == null || body.length() > MAX_BODY_CHARS) return null;
        tools.jackson.databind.JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (tools.jackson.core.JacksonException malformed) {
            return null;
        }
        return node == null || !node.isObject() ? null : node;
    }

    private static @Nullable String textOf(tools.jackson.databind.JsonNode node, String field, int maxChars) {
        tools.jackson.databind.JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank() || value.asString().length() > maxChars)
            return null;
        return value.asString();
    }

    private static RequestView viewOf(DeletionRequest request, ProgressView progress) {
        return new RequestView(request.requestId(), TIMESTAMP.format(request.requestedAt()),
                AccountDeletionRequestService.STATE_IN_PROGRESS, request.policyVersion(), progress);
    }

    /** 그 계정의 삭제 진행. 실행이 아직 없으면(Worker가 집기 전) 진행 중이다. */
    private ProgressView progressOf(UUID userId) {
        return this.deletions.runOf(userId).map(AccountDeletionController::progressViewOf)
                .orElse(new ProgressView(PROGRESS_IN_PROGRESS, List.of()));
    }

    /**
     * 다시 시도 응답의 진행(지금 값). 재대기열은 이미 커밋됐으므로 진행을 읽지 못해도 오류로 바꾸지 않는다(Story 5.1의 커밋 뒤
     * 규칙과 같다) — 그때는 진행 중이다. 확정 응답은 이것이 아니라 고정 {@link #CONFIRMED_PROGRESS}다.
     */
    private ProgressView settledProgressOf(UUID userId) {
        try {
            return progressOf(userId);
        } catch (RuntimeException failure) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("account deletion progress unread: failure={}", failure.getClass().getSimpleName());
            }
            return new ProgressView(PROGRESS_IN_PROGRESS, List.of());
        }
    }

    static ProgressView progressViewOf(AccountDeletionRunApi.DeletionRun run) {
        String status = switch (run.status()) {
            case QUEUED, RUNNING -> PROGRESS_IN_PROGRESS;
            case RETRY_NEEDED -> PROGRESS_RETRY_NEEDED;
            case OPERATIONAL_DATA_ERASED -> PROGRESS_ERASED;
            case DELETION_COMPLETED -> PROGRESS_COMPLETED;
        };
        // 실패 범위는 재시도 필요일 때만 싣는다 — 다시 시도를 받아 대기·실행 중이면 지난 실패는 지금의 사실이 아니다.
        return new ProgressView(status, run.status() == AccountDeletionRunApi.RunStatus.RETRY_NEEDED
                ? run.failedSteps().stream().map(Enum::name).toList() : List.of());
    }

    private static ResponseEntity<?> refused(String code, HttpServletRequest request) {
        Refusal refusal = REFUSALS.get(code);
        if (refusal == null) {
            // 이 표면이 모르는 거절은 호출자 탓이 아니라 깨진 계약이다.
            return ControllerSupport.error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorHandler.CODE_INTERNAL_ERROR,
                    ApiErrorHandler.MESSAGE_INTERNAL_ERROR, request);
        }
        return ControllerSupport.error(refusal.status(), refusal.code(), refusal.message(), request);
    }

    private static ResponseEntity<?> internal(RuntimeException failure, HttpServletRequest request) {
        // 클래스 이름만 debug로 남긴다. 메시지·원인에는 SQL이나 값이 실릴 수 있다.
        if (LOG.isDebugEnabled()) {
            LOG.debug("account deletion failed: failure={}", failure.getClass().getSimpleName());
        }
        return ControllerSupport.error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorHandler.CODE_INTERNAL_ERROR,
                ApiErrorHandler.MESSAGE_INTERNAL_ERROR, request);
    }

    private static ResponseEntity<?> answer(String step, ResponseEntity<?> response, String result, long started) {
        String logged = response.getStatusCode().value() == 500 ? "ERROR" : result;
        log(step, logged, response.getStatusCode().value(), started);
        return response;
    }

    /** 요청 하나, 운영 줄 하나. 사용자·요청 ID·허가 참조·확인 문구·멱등 키·정책 원값을 싣지 않는다. */
    private static void log(String step, String result, int status, long started) {
        LOG.info("account deletion request: step={} result={} status={} latencyMs={}", step, result, status,
                (System.nanoTime() - started) / 1_000_000);
    }
}
