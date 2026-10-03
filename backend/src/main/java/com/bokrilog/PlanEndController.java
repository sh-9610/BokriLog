package com.bokrilog;

import com.bokrilog.plan.PlanEndApi;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The early end of the active plan (Story 2.13): three writes under {@code /api/dashboard/plan-end}, all behind the
 * dashboard path's one predicate — authentication, the session-bound CSRF token and the same-origin {@code Origin}
 * ({@code AuthSecurityConfig.isDashboardPath}). Nothing here calls the provider.
 *
 * <ul>
 *   <li>{@code POST /api/dashboard/plan-end} (prepare): {@code {reason, planId, pointerVersion, calculationId}} — the
 *       reason and the three values of the official result the screen shows. 200
 *       {@code {reason, planId, pointerVersion, calculationId, confirmation}} with the server's canonical confirmation
 *       phrase; the grant reference never leaves the server.
 *   <li>{@code POST /api/dashboard/plan-end/confirm}: the prepare body plus {@code confirmation}, with an
 *       {@code Idempotency-Key}. 200 {@code {outcome: "ENDED", planId, reason, endedAt}} — for the same key and request
 *       again too.
 *   <li>{@code POST /api/dashboard/plan-end/cancel}: drops the arming and its grant. Always 204.
 * </ul>
 *
 * <p>Refusals use the common error shape: 400 for a body that is not that shape; 403 {@code FORBIDDEN} (the account's
 * write access), {@code REAUTHENTICATION_REQUIRED} (prepare) or {@code PLAN_END_NOT_CONFIRMED} (confirm); 404
 * {@code PLAN_NOT_FOUND} (another user's, an unknown and an ended plan alike); 409 {@code REFERENCE_STALE},
 * {@code PLAN_END_UNAVAILABLE} (with a {@code reason}: {@code RESTRICTED}, {@code PLAN_PERIOD_ENDED},
 * {@code RESULT_TOO_RECENT} or {@code RESULT_UNAVAILABLE}),
 * {@code LONG_GAP}, {@code NOT_LONG_GAP} or {@code IDEMPOTENCY_CONFLICT}; 422 {@code IDEMPOTENCY_KEY_REQUIRED}; a code
 * this surface does not know, or a failure, is a 500. Every answer is {@code no-store}.
 *
 * <p>One request, one operational line: the step, the result code, the status answered and the latency. No user,
 * plan, reference, grant, confirmation phrase, key or amount.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class PlanEndController {
    static final String PLAN_END_PATH = AuthSecurityConfig.DASHBOARD_PATH + "/plan-end";
    static final String CONFIRM_PATH = PLAN_END_PATH + "/confirm";
    static final String CANCEL_PATH = PLAN_END_PATH + "/" + AuthSecurityConfig.CANCEL_SEGMENT;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PlanEndController.class);

    /** Status and safe message per refusal code. A closed map; only the stable code is the machine value. */
    private static final Map<String, Refusal> REFUSALS = Map.ofEntries(
            Map.entry(PlanEndApi.NOT_PERMITTED, new Refusal(HttpStatus.FORBIDDEN, ApiErrorHandler.CODE_FORBIDDEN,
                    ApiErrorHandler.MESSAGE_FORBIDDEN)),
            Map.entry(PlanEndCommandService.REAUTHENTICATION_REQUIRED, new Refusal(HttpStatus.FORBIDDEN,
                    PlanEndCommandService.REAUTHENTICATION_REQUIRED, "계획을 종료하려면 먼저 본인 확인이 필요합니다.")),
            Map.entry(PlanEndCommandService.PLAN_END_NOT_CONFIRMED, new Refusal(HttpStatus.FORBIDDEN,
                    PlanEndCommandService.PLAN_END_NOT_CONFIRMED,
                    "확인된 종료 요청이 아니어서 계획을 종료하지 않았습니다. 대시보드를 다시 불러와 주세요.")),
            Map.entry(PlanEndApi.PLAN_NOT_FOUND, new Refusal(HttpStatus.NOT_FOUND, PlanEndApi.PLAN_NOT_FOUND,
                    "종료할 활성 계획을 찾지 못했습니다. 대시보드를 다시 불러와 주세요.")),
            Map.entry(PlanEndApi.REFERENCE_STALE, new Refusal(HttpStatus.CONFLICT, PlanEndApi.REFERENCE_STALE,
                    "화면의 결과가 최신 공식 버전이 아닙니다. 대시보드를 다시 불러와 주세요.")),
            Map.entry(PlanEndApi.PLAN_END_UNAVAILABLE, new Refusal(HttpStatus.CONFLICT,
                    PlanEndApi.PLAN_END_UNAVAILABLE, "지금은 계획을 종료할 수 없습니다. 대시보드에서 이유를 확인해 주세요.")),
            Map.entry(PlanEndApi.LONG_GAP, new Refusal(HttpStatus.CONFLICT, PlanEndApi.LONG_GAP,
                    "오래 동기화하지 않아 장기 미동기화 선택지의 새 계획 시작으로만 종료할 수 있습니다.")),
            Map.entry(PlanEndApi.NOT_LONG_GAP, new Refusal(HttpStatus.CONFLICT, PlanEndApi.NOT_LONG_GAP,
                    "장기 미동기화 상태가 아니어서 새 계획 시작으로 종료하지 않았습니다.")),
            Map.entry(PlanEndCommandService.IDEMPOTENCY_CONFLICT, new Refusal(HttpStatus.CONFLICT,
                    PlanEndCommandService.IDEMPOTENCY_CONFLICT, "같은 요청 키가 다른 종료 요청에 이미 쓰였습니다. 새로 요청해 주세요.")));

    static final String MESSAGE_KEY_REQUIRED =
            "요청 키가 없거나 형식이 맞지 않아 종료 요청을 받지 않았습니다. 화면을 새로 열어 다시 시도해 주세요.";

    /** A request body is a few hundred bytes; anything much longer is not one. */
    private static final int MAX_BODY_CHARS = 1024;

    /** The confirmation phrase is about a hundred characters; the grant port caps it at 4 KiB. */
    private static final int MAX_CONFIRMATION_CHARS = 512;

    /** A canonical UUID (either case). {@link UUID#fromString} alone also accepts shortened groups. */
    private static final java.util.regex.Pattern UUID_TEXT = java.util.regex.Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Reads the body here rather than binding it, for the same {@code no-store} reason as the dashboard's writes. */
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private record Refusal(HttpStatus status, String code, String message) {}

    /** The prepare answer. The grant reference is not here. */
    record PreparedView(String reason, UUID planId, long pointerVersion, UUID calculationId, String confirmation) {}

    /** The confirm answer. */
    record EndedView(String outcome, UUID planId, String reason, String endedAt) {}

    /** The common error shape, with the reason of {@code PLAN_END_UNAVAILABLE} when there is one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PlanEndError(String code, String message, String correlationId, @Nullable String reason) {}

    /** A parsed body: the request, and the confirmation phrase of a confirm. */
    record Body(PlanEndApi.Request request, @Nullable String confirmation) {}

    private final PlanEndCommandService ends;

    PlanEndController(PlanEndCommandService ends) {
        this.ends = ends;
    }

    @PostMapping(path = PLAN_END_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> prepare(@RequestBody(required = false) @Nullable String body, HttpServletRequest request) {
        long started = System.nanoTime();
        Body parsed = bodyOf(body, false);
        if (parsed == null) return answer("prepare", badRequest(request), "BAD_REQUEST", started);
        UUID userId = currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null || session == null)
            return answer("prepare", refused(PlanEndApi.NOT_PERMITTED, null, request), PlanEndApi.NOT_PERMITTED,
                    started);
        PlanEndCommandService.Preparation preparation;
        try {
            preparation = this.ends.prepare(session, userId, parsed.request());
        } catch (RuntimeException failure) {
            return answer("prepare", internal(failure, request), "ERROR", started);
        }
        return switch (preparation) {
            case PlanEndCommandService.Preparation.Refused refused ->
                    answer("prepare", refused(refused.code(), refused.reason(), request), refused.code(), started);
            case PlanEndCommandService.Preparation.Armed armed -> answer("prepare", ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new PreparedView(armed.flow().reason(), armed.flow().planId(), armed.flow().pointerVersion(),
                            armed.flow().calculationId(), armed.flow().confirmation())), "PREPARED", started);
        };
    }

    @PostMapping(path = CONFIRM_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> confirm(@RequestBody(required = false) @Nullable String body,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String key,
            HttpServletRequest request) {
        long started = System.nanoTime();
        Body parsed = bodyOf(body, true);
        if (parsed == null || parsed.confirmation() == null)
            return answer("confirm", badRequest(request), "BAD_REQUEST", started);
        if (key == null || !validKey(key))
            return answer("confirm", error(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REQUIRED",
                    MESSAGE_KEY_REQUIRED, null, request), "IDEMPOTENCY_KEY_REQUIRED", started);
        UUID userId = currentUserId();
        HttpSession session = request.getSession(false);
        if (userId == null || session == null)
            return answer("confirm", refused(PlanEndApi.NOT_PERMITTED, null, request), PlanEndApi.NOT_PERMITTED,
                    started);
        PlanEndCommandService.Outcome outcome;
        try {
            outcome = this.ends.confirm(request, session, userId, parsed.request(), parsed.confirmation(), key);
        } catch (RuntimeException failure) {
            return answer("confirm", internal(failure, request), "ERROR", started);
        }
        return switch (outcome) {
            case PlanEndCommandService.Outcome.Refused refused ->
                    answer("confirm", refused(refused.code(), refused.reason(), request), refused.code(), started);
            case PlanEndCommandService.Outcome.Ended ended -> answer("confirm", ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new EndedView(PlanEndApi.OUTCOME_ENDED, ended.ended().planId(),
                            ended.ended().reason().name(), ended.ended().endedAt().toString())),
                    PlanEndApi.OUTCOME_ENDED, started);
        };
    }

    @PostMapping(path = CANCEL_PATH)
    void cancel(HttpServletRequest request, HttpServletResponse response) {
        long started = System.nanoTime();
        HttpSession session = request.getSession(false);
        String result = "CANCELLED";
        if (session != null) {
            try {
                this.ends.cancel(session);
            } catch (RuntimeException failure) {
                // The arming is gone from the session either way; a grant left behind expires in five minutes.
                if (LOG.isDebugEnabled()) LOG.debug("plan end cancel failed: failure={}", failure.getClass().getName());
                result = "ERROR";
            }
        }
        response.setStatus(HttpStatus.NO_CONTENT.value());
        response.setHeader(org.springframework.http.HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        log("cancel", result, HttpStatus.NO_CONTENT.value(), started);
    }

    /**
     * The request of a body, or {@code null} when there is no body, it is not a JSON object, the {@code reason} is not
     * exactly one of the two names, the two ids are not canonical UUID strings, the version is not a positive JSON
     * integer, or — for a confirm — the {@code confirmation} is not a string of a sane length. Other members are
     * ignored.
     */
    static @Nullable Body bodyOf(@Nullable String body, boolean confirming) {
        if (body == null || body.length() > MAX_BODY_CHARS) return null;
        tools.jackson.databind.JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (tools.jackson.core.JacksonException malformed) {
            return null;
        }
        if (node == null || !node.isObject()) return null;
        tools.jackson.databind.JsonNode reason = node.get("reason");
        tools.jackson.databind.JsonNode plan = node.get("planId");
        tools.jackson.databind.JsonNode version = node.get("pointerVersion");
        tools.jackson.databind.JsonNode calculation = node.get("calculationId");
        if (reason == null || !reason.isString() || plan == null || !plan.isString() || calculation == null
                || !calculation.isString() || version == null || !version.isIntegralNumber()
                || !version.canConvertToLong())
            return null;
        PlanEndApi.Reason parsedReason = switch (reason.asString()) {
            case "USER_REQUEST" -> PlanEndApi.Reason.USER_REQUEST;
            case "LONG_GAP_NEW_PLAN" -> PlanEndApi.Reason.LONG_GAP_NEW_PLAN;
            default -> null;
        };
        long pointerVersion = version.asLong();
        if (parsedReason == null || pointerVersion < 1 || !UUID_TEXT.matcher(plan.asString()).matches()
                || !UUID_TEXT.matcher(calculation.asString()).matches())
            return null;
        String confirmation = null;
        if (confirming) {
            tools.jackson.databind.JsonNode phrase = node.get("confirmation");
            if (phrase == null || !phrase.isString() || phrase.asString().isBlank()
                    || phrase.asString().length() > MAX_CONFIRMATION_CHARS)
                return null;
            confirmation = phrase.asString();
        }
        return new Body(new PlanEndApi.Request(parsedReason, UUID.fromString(plan.asString()), pointerVersion,
                UUID.fromString(calculation.asString())), confirmation);
    }

    /** The plan's key rule: the caller's own key, 16~128 characters of a safe alphabet (the dashboard's). */
    static boolean validKey(String key) {
        return key.length() >= 16 && key.length() <= 128 && key.matches("[A-Za-z0-9._:-]+") && !key.contains("..")
                && !key.startsWith("-");
    }

    private static ResponseEntity<?> refused(String code, @Nullable String reason, HttpServletRequest request) {
        Refusal refusal = REFUSALS.get(code);
        if (refusal == null) {
            // A refusal this surface does not know is a broken contract, not the caller's fault.
            return error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorHandler.CODE_INTERNAL_ERROR,
                    ApiErrorHandler.MESSAGE_INTERNAL_ERROR, null, request);
        }
        return error(refusal.status(), refusal.code(), refusal.message(),
                PlanEndApi.PLAN_END_UNAVAILABLE.equals(code) ? reason : null, request);
    }

    private static ResponseEntity<?> badRequest(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, ApiErrorHandler.CODE_BAD_REQUEST, ApiErrorHandler.MESSAGE_BAD_REQUEST, null,
                request);
    }

    private static ResponseEntity<?> internal(RuntimeException failure, HttpServletRequest request) {
        // The class name at debug only: a message or cause can carry SQL or values.
        if (LOG.isDebugEnabled()) LOG.debug("plan end request failed: failure={}", failure.getClass().getName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorHandler.CODE_INTERNAL_ERROR,
                ApiErrorHandler.MESSAGE_INTERNAL_ERROR, null, request);
    }

    private static ResponseEntity<PlanEndError> error(HttpStatus status, String code, String message,
            @Nullable String reason, HttpServletRequest request) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(new PlanEndError(code, message, ApiErrorHandler.correlationIdOf(
                        request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER)), reason));
    }

    private static ResponseEntity<?> answer(String step, ResponseEntity<?> response, String result, long started) {
        String logged = response.getStatusCode().value() == 500 ? "ERROR" : result;
        log(step, logged, response.getStatusCode().value(), started);
        return response;
    }

    private static void log(String step, String result, int status, long started) {
        LOG.info("plan end request: step={} result={} status={} latencyMs={}", step, result, status,
                (System.nanoTime() - started) / 1_000_000);
    }

    private static @Nullable UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) return null;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
