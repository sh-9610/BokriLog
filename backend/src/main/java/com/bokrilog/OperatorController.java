package com.bokrilog;

import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.OperatorAuditApi;
import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.OperatorPermission;
import com.bokrilog.sync.IdempotencyKey;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 보호된 운영자 표면의 HTTP(Story 3.1). 모든 경로가 {@code /api/operator} 아래이고 같은 술어 하나
 * ({@code AuthSecurityConfig.isOperatorPath})로 인증·세션 귀속 CSRF 토큰·동일 출처 {@code Origin}을 지나며, 그 뒤
 * {@link OperatorGate}가 운영자·권한을 판정한다. 비운영자·권한 부족·미존재 대상은 모두 없는 경로와 같은 404다.
 *
 * <ul>
 *   <li>{@code GET /api/operator/audit?from&to&command&scope&outcome&cursor&limit}(감사 권한): 최신순 allowlist 필드,
 *       {@code (recordedAt, sequence)} keyset cursor, 쪽당 최대 200행. 날짜는 KST 달력 날짜(양 끝 포함). 읽을 수 없는
 *       값은 400.
 *   <li>{@code GET /api/operator/operations/{operationId}}: 그 운영자 자신의 operation 행, 오래된 순. 남의·미존재는 404.
 *   <li>{@code POST /api/operator/commands/{type}}(준비, command 권한 + type별 권한): {@code {scope, target, reason}}과
 *       {@code Idempotency-Key}. 200 {@code {operationId, command, scope, target, reason, confirmationPhrase,
 *       policyVersion, requestedAt}}. 허가 참조는 서버 밖으로 나가지 않는다.
 *   <li>{@code POST /api/operator/commands/{type}/confirm}: {@code {operationId, confirmation}}과 같은 키. 200
 *       {@code {operationId, outcome: "COMPLETED", command, target, upperSequence, rowCount, truncated, rows}} — 같은
 *       키의 재전송도 같은 묶음이다(회전 없음).
 *   <li>{@code POST /api/operator/commands/{type}/cancel}: 무장과 그 허가를 버리고 {@code CANCELLED} 행을 남긴다. 무장이
 *       없어도 204.
 *   <li>Story 3.2의 제한 생성 command 넷({@code restrict-new-connection}·{@code restrict-sync}·
 *       {@code restrict-calculation-display}·{@code restrict-advertising})도 같은 세 경로다. 준비 응답은
 *       {@code impact {blockedStages, affectedPlans, affectedConnections, remainingFeatures}}(수만, 식별자 없음)를 더
 *       싣고, 확정 응답은 {@code {operationId, outcome, command, feature, scope, restrictionId, effectiveFrom}}이다.
 *   <li>Story 3.4의 해제 command {@code lift-restriction}도 같은 세 경로다. 준비 본문은 {@code {target: <제한 ID>, reason:
 *       <원인>, verification, evidenceRef, deployVersion, conditions[4]}}이고 응답 {@code impact}는 {@code {reopenedStages,
 *       remainingRestrictions[{feature, scope, origin}], linkedSignals, latestSignalAt}}(식별자 없음)다. 확정 응답은
 *       {@code {operationId, outcome, command, feature, scope, restrictionId, liftedAt, smoke {status, reopenedStages,
 *       stillBlockedStages}}}이다.
 *   <li>{@code GET /api/operator/restrictions}(command 권한): 효력 중인 제한, 구체 우선. 출처(운영자·자동 정지, Story
 *       3.3)·기능·scope·target(비식별된 것은 {@code targetErased})·시작 시각·사유·수행자 digest.
 * </ul>
 *
 * <p>거절은 공통 오류 모양이다: 400 본문 모양, 403 {@code REAUTHENTICATION_REQUIRED}(준비)·
 * {@code OPERATOR_COMMAND_NOT_CONFIRMED}(확정), 409 {@code CONFIRMATION_MISMATCH}·{@code IDEMPOTENCY_CONFLICT}, 422
 * {@code IDEMPOTENCY_KEY_REQUIRED}·{@code REASON_REQUIRED}·{@code REASON_INVALID}·{@code UNSUPPORTED_SCOPE}·
 * {@code TARGET_INVALID}·{@code ACTOR_NOT_ACCEPTED}, 409 {@code ALREADY_RESTRICTED}(Story 3.2), 409
 * {@code RESTRICTION_NOT_ACTIVE}·{@code NEW_SIGNAL_SINCE_PREPARE}과 422 {@code RECOVERY_CONDITION_MISSING}·
 * {@code RECOVERY_EVIDENCE_INVALID}(Story 3.4), 실패는 500. 404 밖의 응답은 {@code no-store}다.
 *
 * <p>한 요청에 운영 로그 한 줄 — 단계·결과 code·상태·지연뿐이다. 수행자·대상·사유·operation ID·키·허가를 남기지 않는다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class OperatorController {

    static final String OPERATIONS_PATH = AuthSecurityConfig.OPERATOR_API_PATH + "/operations";

    /** 효력 중인 제한의 목록(Story 3.2). command 권한이다. */
    static final String RESTRICTIONS_PATH = AuthSecurityConfig.OPERATOR_API_PATH + "/restrictions";

    static final String CONFIRM_SEGMENT = "confirm";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OperatorController.class);

    /** 요청 본문의 상한. 준비 본문은 사유 500자를 담아도 2KiB 안이다. */
    private static final int MAX_BODY_CHARS = 4096;

    /** 확인 문구의 상한. 서버 문구는 50자 안팎이고 허가 포트는 4KiB에서 자른다. */
    private static final int MAX_CONFIRMATION_CHARS = 256;

    /** 본문이 수행자를 주장하는 이름. 하나라도 있으면 거절한다 — 수행자는 세션이 정한다. */
    private static final Set<String> ACTOR_FIELDS =
            Set.of("actor", "actorId", "actorUserId", "actorDigest", "operator", "operatorId", "userId");

    private static final Pattern OPERATION_ID = Pattern.compile("op_[A-Za-z0-9_-]{1,60}");

    private static final Pattern COMMAND_FILTER = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    /** 감사 scope 필터. 제한 command의 원장 scope는 {@code <기능>:<scope>}다(Story 3.2). */
    private static final Pattern SCOPE_FILTER = Pattern.compile("[A-Z][A-Z_]{0,30}(:[A-Z][A-Z_]{0,30})?");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private static final Pattern LIMIT = Pattern.compile("\\d{1,4}");

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private record Refusal(HttpStatus status, String message) {}

    /** 감사 내보내기의 {@code TARGET_INVALID} 문장. 대상이 기간이다. */
    static final String EXPORT_TARGET_INVALID = "대상 기간이 올바르지 않습니다. 시작일과 종료일을 366일 이내로 지정해 주세요.";

    /** 거절 code → 상태와 안전한 문장. 닫힌 맵이며 machine 값은 code뿐이다. */
    private static final Map<String, Refusal> REFUSALS = Map.ofEntries(
            Map.entry(OperatorCommandService.REAUTHENTICATION_REQUIRED, new Refusal(HttpStatus.FORBIDDEN,
                    "운영 명령을 준비하려면 먼저 본인 확인이 필요합니다.")),
            Map.entry(OperatorCommandService.NOT_CONFIRMED, new Refusal(HttpStatus.FORBIDDEN,
                    "확인된 운영 명령이 아니어서 실행하지 않았습니다. 명령을 다시 준비해 주세요.")),
            Map.entry(OperatorCommandService.CONFIRMATION_MISMATCH, new Refusal(HttpStatus.CONFLICT,
                    "확인 문구가 일치하지 않아 실행하지 않았습니다. 화면의 문구를 그대로 입력해 주세요.")),
            Map.entry(OperatorCommandService.IDEMPOTENCY_CONFLICT, new Refusal(HttpStatus.CONFLICT,
                    "같은 요청 키가 다른 운영 명령에 이미 쓰였습니다. 명령을 새로 준비해 주세요.")),
            Map.entry(IdempotencyKey.REQUIRED_CODE, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED)),
            Map.entry(OperatorCommandApi.REASON_REQUIRED, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "사유를 입력해 주세요.")),
            Map.entry(OperatorCommandApi.REASON_INVALID, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "사유는 500자 이내의 한 줄 문장이어야 하고 이메일 주소를 담을 수 없습니다.")),
            Map.entry(OperatorCommandApi.UNSUPPORTED_SCOPE, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "이 운영 명령이 지원하지 않는 범위입니다.")),
            Map.entry(OperatorCommandApi.TARGET_INVALID, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "대상이 올바르지 않거나 지금 이 명령의 대상이 될 수 없습니다. 대상을 확인해 주세요.")),
            Map.entry(OperatorCommandApi.ALREADY_RESTRICTED, new Refusal(HttpStatus.CONFLICT,
                    "같은 기능·범위·대상의 제한이 이미 효력 중입니다. 활성 제한 목록을 확인해 주세요.")),
            Map.entry(OperatorCommandApi.ACTOR_NOT_ACCEPTED, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "요청에 수행자를 담을 수 없습니다. 수행자는 로그인 세션이 정합니다.")),
            Map.entry(OperatorCommandApi.RESTRICTION_NOT_ACTIVE, new Refusal(HttpStatus.CONFLICT,
                    "이 제한은 지금 효력 중이 아니어서 복구하지 않았습니다. 활성 제한 목록을 확인해 주세요.")),
            Map.entry(OperatorCommandApi.RECOVERY_CONDITION_MISSING, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "복구 조건 네 가지를 모두 확인해야 복구를 준비할 수 있습니다.")),
            Map.entry(OperatorCommandApi.RECOVERY_EVIDENCE_REQUIRED, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "수행한 검증·증거 참조·배포 버전을 모두 입력해 주세요.")),
            Map.entry(OperatorCommandApi.RECOVERY_EVIDENCE_INVALID, new Refusal(HttpStatus.UNPROCESSABLE_CONTENT,
                    "수행한 검증과 증거 참조는 500자 이내의 한 줄 문장이어야 하고 이메일 주소를 담을 수 없습니다. "
                            + "배포 버전은 영문·숫자와 . _ + - 만으로 64자 이내이고, 복구 조건은 아는 조건을 한 번씩만 "
                            + "보냅니다.")),
            Map.entry(OperatorCommandApi.NEW_SIGNAL_SINCE_PREPARE, new Refusal(HttpStatus.CONFLICT,
                    "준비 뒤 이 제한에 새 자동 정지 신호가 연결되어 복구하지 않았습니다. 다시 검증한 뒤 새로 준비해 주세요.")));

    /** 감사 행 하나의 응답 모양. allowlist 필드뿐이다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EntryView(long sequence, String operationId, String actorKind, String actorDigest, String command,
            String scope, String target, String reason, String outcome, @Nullable String code, String policyVersion,
            String requestedAt, @Nullable String decidedAt, String recordedAt, String correlationId) {

        static EntryView of(OperatorAuditApi.Entry entry) {
            return new EntryView(entry.sequence(), entry.operationId(), entry.actorKind(), entry.actorDigest(),
                    entry.command(), entry.scope(), entry.target(), entry.reason(), entry.outcome(), entry.code(),
                    entry.policyVersion(), entry.requestedAt().toString(),
                    entry.decidedAt() == null ? null : entry.decidedAt().toString(), entry.recordedAt().toString(),
                    entry.correlationId());
        }
    }

    record PageView(List<EntryView> items, @Nullable String nextCursor) {}

    record OperationView(String operationId, List<EntryView> items) {}

    /** 준비 응답의 미리보기: 제한 생성(Story 3.2)이거나 해제(Story 3.4)다. 식별자도 금융값도 없다. */
    sealed interface PreparedImpactView permits ImpactView, LiftImpactView {}

    /** 제한 생성의 영향 미리보기(Story 3.2). 수만 담는다 — 식별자도 금융값도 없다. */
    record ImpactView(List<String> blockedStages, long affectedPlans, long affectedConnections,
            List<String> remainingFeatures) implements PreparedImpactView {}

    /** 해제의 남는 제한 하나(Story 3.4). 식별자가 없다. */
    record RemainingView(String feature, String scope, String origin) {}

    /** 해제의 영향 미리보기(Story 3.4). 식별자도 금융값도 없다. 신호가 없으면 {@code latestSignalAt}은 {@code null}이다. */
    record LiftImpactView(List<String> reopenedStages, List<RemainingView> remainingRestrictions, int linkedSignals,
            @Nullable String latestSignalAt) implements PreparedImpactView {}

    /** 준비 응답. {@code impact}는 감사 내보내기에 없다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PreparedView(String operationId, String command, String scope, String target, String reason,
            String confirmationPhrase, String policyVersion, String requestedAt, @Nullable PreparedImpactView impact) {}

    record CompletedView(String operationId, String outcome, String command, String target, long upperSequence,
            int rowCount, boolean truncated, List<EntryView> rows) {}

    /** 제한 생성의 확정 결과(Story 3.2). target 원문은 싣지 않는다 — 같은 키의 재생은 원장에서 오고 원장에는 원문이 없다. */
    record RestrictionCompletedView(String operationId, String outcome, String command, String feature, String scope,
            UUID restrictionId, String effectiveFrom) {}

    /** 해제 커밋 뒤의 smoke(Story 3.4). */
    record SmokeView(String status, List<String> reopenedStages, List<String> stillBlockedStages) {}

    /** 해제의 확정 결과(Story 3.4). target 원문은 싣지 않는다 — 재생이 원장에서 온다. */
    record LiftCompletedView(String operationId, String outcome, String command, String feature, String scope,
            UUID restrictionId, String liftedAt, SmokeView smoke) {}

    /**
     * 효력 중인 제한 하나(Story 3.2). 비식별된 target은 싣지 않고 {@code targetErased}로 말한다. {@code origin}은 그 제한을
     * 만든 출처다(Story 3.3) — {@code OPERATOR}이거나 강한 신호의 자동 정지 {@code SIGNAL}이다. {@code linkedSignals}는 그
     * 제한을 만들었거나 그것에 연결된 강한 신호 수이고, 하나라도 있으면 출처와 무관하게 {@code requiresVerification}이 참이다
     * — 화면은 {@code 수동 검증 필요}로 보이고 복구 미리보기(Story 3.4)는 신호 계보를 보인다. {@code reinstated}는 해제
     * smoke가 어긋나 같은 범위로 되돌린 행이다(Story 3.4) — 그 행의 사유는 해제 원인이 아니라 표지
     * {@code SMOKE_REVERTED}이고, 신호 수는 되돌린 원 제한의 계보까지 센다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RestrictionView(UUID restrictionId, String feature, String scope, @Nullable String target,
            boolean targetErased, String effectiveFrom, @Nullable String reason, @Nullable String actorDigest,
            String origin, int linkedSignals, boolean requiresVerification, boolean reinstated) {

        static RestrictionView of(OperationalRestrictionApi.ActiveRestriction restriction) {
            return new RestrictionView(restriction.restrictionId(), restriction.feature().name(),
                    restriction.scope().name(), restriction.target(), restriction.targetErased(),
                    restriction.effectiveFrom().toString(), restriction.reason(), restriction.actorDigest(),
                    restriction.origin().name(), restriction.linkedSignals(), restriction.requiresVerification(),
                    restriction.reinstatedFrom() != null);
        }
    }

    record RestrictionListView(List<RestrictionView> items) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OperatorError(String code, String message, String correlationId) {}

    private final OperatorCommandService commands;

    private final OperatorAuditApi audit;

    private final OperationalRestrictionApi restrictions;

    /**
     * 핸들러 안의 이중 검사. 게이트({@link OperatorGate})가 먼저 판정하지만 경로 판정이 어긋나는 날(인코딩·매핑 변경)
     * 그 검사 하나에 기대지 않는다 — 여기서도 같은 권한을 보고 같은 404를 낸다.
     */
    private final OperatorAccess access;

    OperatorController(OperatorCommandService commands, OperatorAuditApi audit, OperationalRestrictionApi restrictions,
            OperatorAccess access) {
        this.commands = commands;
        this.audit = audit;
        this.restrictions = restrictions;
        this.access = access;
    }

    // ------------------------------------------------------------------ 감사 조회

    @GetMapping(path = OperatorGate.AUDIT_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> search(@RequestParam(required = false) @Nullable String from,
            @RequestParam(required = false) @Nullable String to,
            @RequestParam(required = false) @Nullable String command,
            @RequestParam(required = false) @Nullable String scope,
            @RequestParam(required = false) @Nullable String outcome,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable String limit, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = correlationIdOf(request);
        if (!this.access.permits(currentUserId(), Set.of(OperatorPermission.AUDIT))) {
            return notFound("audit", response, started);
        }
        OperatorAuditApi.Filter filter = filterOf(from, to, command, scope, outcome);
        Integer size = limitOf(limit);
        if (filter == null || size == null) {
            return answer("audit", badRequest(correlationId), "BAD_REQUEST", started);
        }
        var page = this.audit.search(filter, cursor, size);
        if (page.isEmpty()) {
            return answer("audit", badRequest(correlationId), "BAD_REQUEST", started);
        }
        return answer("audit", ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PageView(page.get().entries().stream().map(EntryView::of).toList(),
                        page.get().nextCursor())), "OK", started);
    }

    @GetMapping(path = OPERATIONS_PATH + "/{operationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> operation(@PathVariable String operationId, HttpServletResponse response)
            throws IOException {
        long started = System.nanoTime();
        UUID userId = currentUserId();
        if (!this.access.permits(userId, Set.of())) {
            return notFound("operation", response, started);
        }
        List<OperatorAuditApi.Entry> rows = userId == null || !OPERATION_ID.matcher(operationId).matches()
                ? List.of() : this.audit.operation(userId, operationId);
        if (rows.isEmpty()) {
            return notFound("operation", response, started);
        }
        return answer("operation", ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new OperationView(operationId, rows.stream().map(EntryView::of).toList())), "OK", started);
    }

    // ------------------------------------------------------------------ 활성 제한(Story 3.2)

    @GetMapping(path = RESTRICTIONS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> restrictions(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        long started = System.nanoTime();
        String correlationId = correlationIdOf(request);
        if (!this.access.permits(currentUserId(), Set.of(OperatorPermission.COMMAND))) {
            return notFound("restrictions", response, started);
        }
        List<RestrictionView> items;
        try {
            items = this.restrictions.active().stream().map(RestrictionView::of).toList();
        } catch (RuntimeException failure) {
            return answer("restrictions", internal(failure, correlationId), "ERROR", started);
        }
        return answer("restrictions", ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new RestrictionListView(items)), "OK", started);
    }

    // ------------------------------------------------------------------ 운영 command

    @PostMapping(path = OperatorGate.COMMANDS_PATH + "/{type}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> prepare(@PathVariable String type, @RequestBody(required = false) @Nullable String body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) @Nullable String key,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = correlationIdOf(request);
        UUID userId = currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null || session == null
                || !this.access.permits(userId, Set.of(OperatorPermission.COMMAND))) {
            return notFound("prepare", response, started);
        }
        OperatorCommandApi.Proposal proposal = proposalOf(type, body);
        if (proposal == null) {
            return answer("prepare", badRequest(correlationId), "BAD_REQUEST", started);
        }
        if (!IdempotencyKey.isValid(key)) {
            return answer("prepare", refused(IdempotencyKey.REQUIRED_CODE, correlationId), IdempotencyKey.REQUIRED_CODE,
                    started);
        }
        OperatorCommandService.Preparation preparation;
        try {
            preparation = this.commands.prepare(session, userId, proposal, key, correlationId);
        } catch (RuntimeException failure) {
            return answer("prepare", internal(failure, correlationId), "ERROR", started);
        }
        return switch (preparation) {
            case OperatorCommandService.Preparation.Refused refused ->
                    OperatorCommandService.NOT_FOUND.equals(refused.code()) ? notFound("prepare", response, started)
                            : answer("prepare", refused(refused.code(), type, correlationId), refused.code(), started);
            case OperatorCommandService.Preparation.Armed armed -> answer("prepare", ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new PreparedView(armed.flow().operationId(), armed.flow().command(), armed.flow().scope(),
                            armed.flow().target(), armed.flow().reason(), armed.flow().confirmation(),
                            armed.flow().policyVersion(), armed.flow().requestedAt().toString(),
                            armed.liftImpact() != null ? liftImpactView(armed.liftImpact())
                                    : impactView(armed.impact()))), "PREPARED", started);
        };
    }

    @PostMapping(path = OperatorGate.COMMANDS_PATH + "/{type}/" + CONFIRM_SEGMENT,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> confirm(@PathVariable String type, @RequestBody(required = false) @Nullable String body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) @Nullable String key,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = correlationIdOf(request);
        UUID userId = currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null || session == null) {
            return notFound("confirm", response, started);
        }
        if (!this.access.permits(userId, Set.of(OperatorPermission.COMMAND))) {
            this.commands.abandonRevoked(session, userId, correlationId);
            return notFound("confirm", response, started);
        }
        Confirmation parsed = confirmationOf(body);
        if (parsed == null) {
            return answer("confirm", badRequest(correlationId), "BAD_REQUEST", started);
        }
        if (!IdempotencyKey.isValid(key)) {
            return answer("confirm", refused(IdempotencyKey.REQUIRED_CODE, correlationId), IdempotencyKey.REQUIRED_CODE,
                    started);
        }
        OperatorCommandService.Outcome outcome;
        try {
            outcome = this.commands.confirm(request, session, userId, type, parsed.operationId(),
                    parsed.confirmation(), key, correlationId);
        } catch (RuntimeException failure) {
            return answer("confirm", internal(failure, correlationId), "ERROR", started);
        }
        return switch (outcome) {
            case OperatorCommandService.Outcome.Refused refused ->
                    OperatorCommandService.NOT_FOUND.equals(refused.code()) ? notFound("confirm", response, started)
                            : answer("confirm", refused(refused.code(), type, correlationId), refused.code(), started);
            case OperatorCommandService.Outcome.Completed completed -> answer("confirm", ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(completedBody(type, completed)), completed.ran() ? "COMPLETED" : "REPLAYED", started);
        };
    }

    private static Object completedBody(String type, OperatorCommandService.Outcome.Completed completed) {
        String outcome = OperatorCommandApi.AuditOutcome.COMPLETED.name();
        return switch (completed.result()) {
            case OperatorCommandApi.ExportResult export -> new CompletedView(export.operationId(), outcome, type,
                    export.target(), export.upperSequence(), export.rowCount(), export.truncated(),
                    completed.rows().stream().map(EntryView::of).toList());
            case OperatorCommandApi.RestrictionResult restriction -> new RestrictionCompletedView(
                    restriction.operationId(), outcome, type, restriction.feature().name(),
                    restriction.scope().name(), restriction.restrictionId(), restriction.effectiveFrom().toString());
            case OperatorCommandApi.LiftResult lift -> {
                OperatorCommandService.Smoke smoke = completed.smoke();
                if (smoke == null) {
                    // 해제의 결과에는 언제나 smoke가 있다. 없으면 깨진 계약이다 — 500으로 답한다.
                    throw new IllegalStateException("해제 결과에 smoke가 없다.");
                }
                yield new LiftCompletedView(lift.operationId(), outcome, type, lift.feature().name(),
                        lift.scope().name(), lift.restrictionId(), lift.liftedAt().toString(),
                        new SmokeView(smoke.status(), smoke.reopenedStages(), smoke.stillBlockedStages()));
            }
        };
    }

    private static LiftImpactView liftImpactView(RestrictionTargets.LiftImpact impact) {
        return new LiftImpactView(impact.reopenedStages(), impact.remainingRestrictions().stream()
                .map(other -> new RemainingView(other.feature(), other.scope(), other.origin())).toList(),
                impact.linkedSignals(), impact.latestSignalAt() == null ? null : impact.latestSignalAt().toString());
    }

    private static @Nullable ImpactView impactView(RestrictionTargets.@Nullable Impact impact) {
        return impact == null ? null : new ImpactView(impact.blockedStages(), impact.affectedPlans(),
                impact.affectedConnections(), impact.remainingFeatures());
    }

    @PostMapping(path = OperatorGate.COMMANDS_PATH + "/{type}/" + AuthSecurityConfig.CANCEL_SEGMENT)
    void cancel(@PathVariable String type, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        long started = System.nanoTime();
        String correlationId = correlationIdOf(request);
        UUID userId = currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null) {
            notFound("cancel", response, started);
            return;
        }
        if (!this.access.permits(userId, Set.of(OperatorPermission.COMMAND))) {
            if (session != null) {
                this.commands.abandonRevoked(session, userId, correlationId);
            }
            notFound("cancel", response, started);
            return;
        }
        if (!this.commands.supports(type)) {
            // 모르는 type은 경로가 없는 것과 같다. 무장을 건드리지 않는다.
            notFound("cancel", response, started);
            return;
        }
        String result = "CANCELLED";
        if (session != null) {
            try {
                if (!this.commands.cancel(session, userId, type, correlationId)) {
                    // 모르는 type은 경로가 없는 것과 같다. 무장을 건드리지 않았다.
                    notFound("cancel", response, started);
                    return;
                }
            } catch (RuntimeException failure) {
                // 무장은 이미 세션에서 빠졌다. 남은 허가는 5분 뒤 만료된다.
                if (LOG.isDebugEnabled()) {
                    LOG.debug("operator cancel failed: failure={}", failure.getClass().getName());
                }
                result = "ERROR";
            }
        }
        response.setStatus(HttpStatus.NO_CONTENT.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        log("cancel", result, HttpStatus.NO_CONTENT.value(), started);
    }

    // ------------------------------------------------------------------ 본문

    /** 확정 본문. */
    record Confirmation(String operationId, String confirmation) {}

    /**
     * 준비 본문의 제안. 본문이 JSON 객체가 아니거나 세 필드 중 문자열이 아닌 것이 있으면 {@code null}(400)이다. 필드가
     * 없는 것은 검증이 거절한다(422). 수행자 이름이 하나라도 있으면 제안에 그 사실을 싣는다.
     */
    static OperatorCommandApi.@Nullable Proposal proposalOf(String type, @Nullable String body) {
        tools.jackson.databind.JsonNode node = objectOf(body);
        if (node == null) {
            return null;
        }
        String scope;
        String target;
        String reason;
        try {
            scope = stringOrNull(node, "scope");
            target = stringOrNull(node, "target");
            reason = stringOrNull(node, "reason");
        } catch (IllegalArgumentException notText) {
            return null;
        }
        boolean actorClaimed = false;
        for (String name : ACTOR_FIELDS) {
            if (node.has(name)) {
                actorClaimed = true;
                break;
            }
        }
        if (!OperatorCommandApi.LIFT_RESTRICTION.equals(type)) {
            return new OperatorCommandApi.Proposal(type, scope, target, reason, actorClaimed);
        }
        /*
         * 해제(Story 3.4)는 scope를 받지 않는다 — 대상은 제한 ID 하나이고 범위는 그 제한의 것이다. 증거 필드가 문자열이
         * 아니거나 조건이 문자열 배열이 아니면 400이다. 비어 있는 것은 검증이 거절한다(422).
         */
        OperatorCommandApi.RecoveryInput recovery;
        try {
            recovery = new OperatorCommandApi.RecoveryInput(conditionsOf(node), stringOrNull(node, "verification"),
                    stringOrNull(node, "evidenceRef"), stringOrNull(node, "deployVersion"));
        } catch (IllegalArgumentException notText) {
            return null;
        }
        return new OperatorCommandApi.Proposal(type, null, target, reason, actorClaimed, recovery);
    }

    /** 복구 조건 배열. 없으면 {@code null}, 배열이 아니거나 문자열이 아닌 원소가 있으면 예외(400)다. */
    private static @Nullable List<String> conditionsOf(tools.jackson.databind.JsonNode node) {
        tools.jackson.databind.JsonNode value = node.get("conditions");
        if (value == null || value.isNull()) {
            return null;
        }
        // 조건은 넷이다. 그보다 긴 배열은 어떻게 읽어도 겹치거나 모르는 code를 담는다 — 본문 모양 오류다.
        if (!value.isArray() || value.size() > OperatorCommandApi.RECOVERY_CONDITIONS.size()) {
            throw new IllegalArgumentException("conditions");
        }
        List<String> conditions = new java.util.ArrayList<>();
        for (tools.jackson.databind.JsonNode item : value) {
            if (!item.isString()) {
                throw new IllegalArgumentException("conditions");
            }
            conditions.add(item.asString());
        }
        return List.copyOf(conditions);
    }

    static @Nullable Confirmation confirmationOf(@Nullable String body) {
        tools.jackson.databind.JsonNode node = objectOf(body);
        if (node == null) {
            return null;
        }
        tools.jackson.databind.JsonNode operation = node.get("operationId");
        tools.jackson.databind.JsonNode phrase = node.get("confirmation");
        if (operation == null || !operation.isString() || phrase == null || !phrase.isString()
                || phrase.asString().isBlank() || phrase.asString().length() > MAX_CONFIRMATION_CHARS
                || operation.asString().length() > 64) {
            return null;
        }
        return new Confirmation(operation.asString(), phrase.asString());
    }

    private static tools.jackson.databind.@Nullable JsonNode objectOf(@Nullable String body) {
        if (body == null || body.length() > MAX_BODY_CHARS) {
            return null;
        }
        tools.jackson.databind.JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (tools.jackson.core.JacksonException malformed) {
            return null;
        }
        return node != null && node.isObject() ? node : null;
    }

    private static @Nullable String stringOrNull(tools.jackson.databind.JsonNode node, String name) {
        tools.jackson.databind.JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(name);
        }
        return value.asString();
    }

    /** 조회 필터. 읽을 수 없는 값이 하나라도 있으면 {@code null}(400)이다. */
    static OperatorAuditApi.@Nullable Filter filterOf(@Nullable String from, @Nullable String to,
            @Nullable String command, @Nullable String scope, @Nullable String outcome) {
        LocalDate fromDate = dateOf(from);
        LocalDate toDate = dateOf(to);
        if ((from != null && fromDate == null) || (to != null && toDate == null)
                || (fromDate != null && toDate != null && toDate.isBefore(fromDate))
                || (command != null && !COMMAND_FILTER.matcher(command).matches())
                || (scope != null && !SCOPE_FILTER.matcher(scope).matches())) {
            return null;
        }
        OperatorCommandApi.AuditOutcome parsedOutcome = null;
        if (outcome != null) {
            try {
                parsedOutcome = OperatorCommandApi.AuditOutcome.valueOf(outcome);
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
        return new OperatorAuditApi.Filter(fromDate, toDate, command, scope, parsedOutcome);
    }

    private static @Nullable LocalDate dateOf(@Nullable String text) {
        if (text == null || !DATE.matcher(text).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException impossible) {
            return null;
        }
    }

    /** 쪽 크기. 없으면 기본값, 0이나 숫자가 아니면 {@code null}(400), 상한을 넘으면 상한이다. */
    static @Nullable Integer limitOf(@Nullable String limit) {
        if (limit == null) {
            return OperatorAuditApi.DEFAULT_PAGE_SIZE;
        }
        if (!LIMIT.matcher(limit).matches()) {
            return null;
        }
        int parsed = Integer.parseInt(limit);
        return parsed < 1 ? null : Math.min(parsed, OperatorAuditApi.MAX_PAGE_SIZE);
    }

    // ------------------------------------------------------------------ 응답

    /**
     * 없는 경로와 같은 404. 오류 디스패치를 지나 {@code ApiErrorHandler}가 본문을 만들므로 게이트의 404·없는 경로의 404와
     * 글자까지 같다.
     */
    private static @Nullable ResponseEntity<?> notFound(String step, HttpServletResponse response, long started)
            throws IOException {
        response.sendError(HttpStatus.NOT_FOUND.value());
        log(step, "NOT_FOUND", HttpStatus.NOT_FOUND.value(), started);
        return null;
    }

    /**
     * command별 문장이 있는 거절. {@code TARGET_INVALID}는 감사 내보내기의 기간 안내와 제한 command의 대상 안내가 다르다
     * (Story 3.2) — 같은 code의 다른 문장이며 machine 값은 code 하나다.
     */
    private static ResponseEntity<?> refused(String code, String command, String correlationId) {
        if (OperatorCommandApi.TARGET_INVALID.equals(code) && OperatorCommandApi.AUDIT_EXPORT.equals(command)) {
            return error(HttpStatus.UNPROCESSABLE_CONTENT, code, EXPORT_TARGET_INVALID, correlationId);
        }
        return refused(code, correlationId);
    }

    private static ResponseEntity<?> refused(String code, String correlationId) {
        Refusal refusal = REFUSALS.get(code);
        if (refusal == null) {
            // 이 표면이 모르는 거절은 호출자 잘못이 아니라 깨진 계약이다.
            return error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorHandler.CODE_INTERNAL_ERROR,
                    ApiErrorHandler.MESSAGE_INTERNAL_ERROR, correlationId);
        }
        return error(refusal.status(), code, refusal.message(), correlationId);
    }

    private static ResponseEntity<?> badRequest(String correlationId) {
        return error(HttpStatus.BAD_REQUEST, ApiErrorHandler.CODE_BAD_REQUEST, ApiErrorHandler.MESSAGE_BAD_REQUEST,
                correlationId);
    }

    private static ResponseEntity<?> internal(RuntimeException failure, String correlationId) {
        // 클래스 이름만 debug로 남긴다. 메시지·원인에는 SQL이나 값이 실릴 수 있다.
        if (LOG.isDebugEnabled()) {
            LOG.debug("operator request failed: failure={}", failure.getClass().getName());
        }
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorHandler.CODE_INTERNAL_ERROR,
                ApiErrorHandler.MESSAGE_INTERNAL_ERROR, correlationId);
    }

    private static ResponseEntity<OperatorError> error(HttpStatus status, String code, String message,
            String correlationId) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(new OperatorError(code, message, correlationId));
    }

    private static String correlationIdOf(HttpServletRequest request) {
        return ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER));
    }

    private static ResponseEntity<?> answer(String step, ResponseEntity<?> response, String result, long started) {
        String logged = response.getStatusCode().value() == 500 ? "ERROR" : result;
        log(step, logged, response.getStatusCode().value(), started);
        return response;
    }

    private static void log(String step, String result, int status, long started) {
        LOG.info("operator request: step={} result={} status={} latencyMs={}", step, result, status,
                (System.nanoTime() - started) / 1_000_000);
    }

    private static @Nullable UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
