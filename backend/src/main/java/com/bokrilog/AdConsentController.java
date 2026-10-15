package com.bokrilog;

import com.bokrilog.engagement.AdConsentApi;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.bokrilog.sync.IdempotencyKey;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 사용자의 문맥형 광고 동의(Story 4.1).
 *
 * <ul>
 *   <li>{@code GET /api/engagement/ad-consent}: 200 {@code {state, policyVersion, decidedAt}} — 상태는
 *       {@code UNDECIDED}·{@code GRANTED}·{@code DENIED}이고 미결정이면 {@code decidedAt}이 {@code null}이다.
 *   <li>{@code POST /api/engagement/ad-consent}: {@code {"choice":"GRANTED"|"DENIED","policyVersion":…}}과
 *       {@code Idempotency-Key}. 200 지금 상태(같은 묶음) — 새 결정·같은 선택·같은 키 재전송 모두 200이다. 거절은 공통 오류
 *       모양이다: 400 본문 모양, 422 {@code IDEMPOTENCY_KEY_REQUIRED}, 409 {@code AD_CONSENT_POLICY_CHANGED}(화면이 본 정책이
 *       지금과 다르다)·{@code IDEMPOTENCY_CONFLICT}, 실패 500.
 * </ul>
 *
 * <p>인증·CSRF·{@code Origin}은 {@code AuthSecurityConfig.isEngagementPath} 하나가 건다. 계정 접근은
 * {@code AccountAccessApi.decide(SENSITIVE)}로 보고, 쓰기는 모듈이 쓰기 트랜잭션 안에서 생명주기 잠금으로 다시 본다 — 삭제가
 * 시작된 계정은 어느 쪽이든 없는 경로와 같은 404다. 모든 응답이 {@code no-store}다.
 *
 * <p><b>재인증·감사 원장이 없다.</b> 동의는 ERD의 민감 command 목록에 없다(SPEC Ask First). 광고 자격 판정·운영 제한
 * {@code ADVERTISING} 판독도 여기서 하지 않는다(Story 4.2).
 *
 * <p><b>로그에 사용자·선택값·키가 없다.</b> 한 요청에 한 줄 — 단계·결과 code·상태·지연뿐이다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AdConsentController {

    static final String AD_CONSENT_PATH = AuthSecurityConfig.ENGAGEMENT_PATH + "/ad-consent";

    /** 화면이 본 정책 버전이 지금 버전과 다르다. 화면은 상태를 다시 읽는다. */
    static final String POLICY_CHANGED = "AD_CONSENT_POLICY_CHANGED";

    /** 같은 멱등 키가 다른 입력에 쓰였다. */
    static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AdConsentController.class);

    /** 요청 본문의 상한(문자). 두 필드의 이스케이프된 값과 키를 넉넉히 담는다. */
    private static final int MAX_BODY_CHARS = 1024;

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ConsentView(String state, String policyVersion, @Nullable String decidedAt) {}

    /** 본문의 입력. 선택은 닫힌 값이고 정책 버전은 비교만 한다. */
    record Request(AdConsentApi.Choice choice, String policyVersion) {}

    private final AdConsentApi consents;

    private final AccountAccessApi accounts;

    AdConsentController(AdConsentApi consents, AccountAccessApi accounts) {
        this.consents = consents;
        this.accounts = accounts;
    }

    @GetMapping(path = AD_CONSENT_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> current(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        UUID userId = allowedUser();
        if (userId == null) {
            return notFound("read", response, started);
        }
        AdConsentApi.View view;
        try {
            view = this.consents.current(userId);
        } catch (RuntimeException failure) {
            return internal("read", failure, request, started);
        }
        log("read", "OK", HttpStatus.OK.value(), started);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(view));
    }

    @PostMapping(path = AD_CONSENT_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> decide(@RequestBody(required = false) @Nullable String body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) @Nullable String key,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        String correlationId = correlationIdOf(request);
        UUID userId = allowedUser();
        if (userId == null) {
            return notFound("decide", response, started);
        }
        Request parsed = requestOf(body);
        if (parsed == null) {
            log("decide", ApiErrorHandler.CODE_BAD_REQUEST, HttpStatus.BAD_REQUEST.value(), started);
            return error(HttpStatus.BAD_REQUEST, new ApiErrorHandler.ApiError(ApiErrorHandler.CODE_BAD_REQUEST,
                    ApiErrorHandler.MESSAGE_BAD_REQUEST, correlationId));
        }
        if (!IdempotencyKey.isValid(key)) {
            log("decide", IdempotencyKey.REQUIRED_CODE, HttpStatus.UNPROCESSABLE_CONTENT.value(), started);
            return error(HttpStatus.UNPROCESSABLE_CONTENT, new ApiErrorHandler.ApiError(IdempotencyKey.REQUIRED_CODE,
                    ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED, correlationId));
        }
        AdConsentApi.Decision decision;
        try {
            decision = this.consents.decide(userId, key, parsed.choice(), parsed.policyVersion());
        } catch (RuntimeException failure) {
            return internal("decide", failure, request, started);
        }
        return switch (decision) {
            case AdConsentApi.Decision.Saved saved -> {
                log("decide", saved.replayed() ? "REPLAYED" : "SAVED", HttpStatus.OK.value(), started);
                yield ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(saved.view()));
            }
            case AdConsentApi.Decision.PolicyChanged changed -> {
                log("decide", POLICY_CHANGED, HttpStatus.CONFLICT.value(), started);
                yield error(HttpStatus.CONFLICT, new ApiErrorHandler.ApiError(POLICY_CHANGED,
                        "광고 동의 안내가 바뀌었습니다. 바뀐 안내를 확인한 뒤 다시 선택해 주세요.", correlationId));
            }
            case AdConsentApi.Decision.Conflict conflict -> {
                log("decide", IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT.value(), started);
                yield error(HttpStatus.CONFLICT, new ApiErrorHandler.ApiError(IDEMPOTENCY_CONFLICT,
                        "같은 요청 키가 다른 선택에 이미 쓰였습니다. 화면을 새로 열어 다시 선택해 주세요.", correlationId));
            }
            // 쓰기 잠금 아래에서 본 계정이 쓰기를 받지 않는다(삭제 시작). 앞의 접근 판정과 같은 404다.
            case AdConsentApi.Decision.Denied denied -> notFound("decide", response, started);
        };
    }

    /**
     * 본문의 입력. JSON 객체가 아니거나, {@code choice}가 {@code GRANTED}·{@code DENIED}가 아니거나, {@code policyVersion}이
     * 문자열이 아니면 {@code null}(400)이다. 정책 버전이 지금과 다른 것은 모듈이 409로 답한다.
     */
    static @Nullable Request requestOf(@Nullable String body) {
        if (body == null || body.length() > MAX_BODY_CHARS) {
            return null;
        }
        tools.jackson.databind.JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (tools.jackson.core.JacksonException malformed) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        tools.jackson.databind.JsonNode choice = node.get("choice");
        tools.jackson.databind.JsonNode policyVersion = node.get("policyVersion");
        if (choice == null || !choice.isString() || policyVersion == null || !policyVersion.isString()) {
            return null;
        }
        for (AdConsentApi.Choice known : AdConsentApi.Choice.values()) {
            if (known.name().equals(choice.asString())) {
                return new Request(known, policyVersion.asString());
            }
        }
        return null;
    }

    private static ConsentView view(AdConsentApi.View view) {
        return new ConsentView(view.state().name(), view.policyVersion(),
                view.decidedAt() == null ? null : view.decidedAt().toString());
    }

    /** 세션 사용자. 미인증은 인가 필터가 먼저 401로 끝낸다. 삭제가 시작된 계정·쓸 수 없는 계정은 {@code null}(404)이다. */
    private @Nullable UUID allowedUser() {
        UUID userId = currentUserId();
        if (userId == null
                || this.accounts.decide(userId, userId, AccountAccessKind.SENSITIVE) != AccessDecision.ALLOWED) {
            return null;
        }
        return userId;
    }

    private static @Nullable ResponseEntity<?> notFound(String step, HttpServletResponse response, long started)
            throws IOException {
        // 없는 경로와 같은 404. 오류 디스패치를 지나 공통 본문이 된다.
        response.sendError(HttpStatus.NOT_FOUND.value());
        log(step, "NOT_FOUND", HttpStatus.NOT_FOUND.value(), started);
        return null;
    }

    private static ResponseEntity<?> internal(String step, RuntimeException failure, HttpServletRequest request,
            long started) {
        // 클래스 이름만 debug로 남긴다. 메시지·원인에는 SQL이나 입력 값이 실릴 수 있다.
        if (LOG.isDebugEnabled()) {
            LOG.debug("ad consent request failed: failure={}", failure.getClass().getName());
        }
        log(step, "ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value(), started);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, new ApiErrorHandler.ApiError(
                ApiErrorHandler.CODE_INTERNAL_ERROR, ApiErrorHandler.MESSAGE_INTERNAL_ERROR, correlationIdOf(request)));
    }

    private static ResponseEntity<ApiErrorHandler.ApiError> error(HttpStatus status, ApiErrorHandler.ApiError body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }

    private static String correlationIdOf(HttpServletRequest request) {
        return ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER));
    }

    /** 단계·결과 code·상태·지연만 남긴다. 사용자·선택값·키는 싣지 않는다. */
    private static void log(String step, String result, int status, long started) {
        LOG.info("ad consent request: step={} result={} status={} latencyMs={}", step, result, status,
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
