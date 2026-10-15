package com.bokrilog;

import com.bokrilog.engagement.AdEligibilityApi;
import com.bokrilog.identity.AccessDecision;
import com.bokrilog.identity.AccountAccessApi;
import com.bokrilog.identity.AccountAccessKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 수동 동기화 성공 결과의 광고 자격 판정(Story 4.2).
 *
 * <p>{@code POST /api/engagement/ad-eligibility} 본문 {@code {"syncJobId":"<uuid>"}} — 수동 동기화 접수 응답의
 * {@code job.jobId}다. 200 {@code {outcome, reason, transition, replayed}}: {@code outcome}은 {@code ELIGIBLE}·{@code SKIPPED},
 * {@code reason}은 {@code SKIPPED}에서만 있고({@code CONSENT_REQUIRED}·{@code RESTRICTED}·{@code PROVIDER_DISABLED}·
 * {@code INTERVAL}·{@code DAILY_CAP}), {@code transition}은 {@code ELIGIBLE}에서만 있는 불투명 참조다. 없는 값은 {@code null}이다.
 * 사용자·결과·금융 값은 싣지 않는다. 그 사용자의 후보 결과가 아니면(남의 것·없는 것·수동 동기화 성공이 아닌 것) 없는 경로와 같은
 * 404이고, 본문 모양 오류는 400, 실패(운영 제한·동의 판독 실패 포함 — fail-closed)는 500이다.
 *
 * <p>인증·CSRF·{@code Origin}은 {@code AuthSecurityConfig.isEngagementPath} 하나가 건다. 자연 키가 {@code (사용자, jobId)}라 멱등
 * 키 헤더를 받지 않는다 — 같은 결과의 재전송은 모듈이 앞선 결정으로 답한다({@code replayed}). 계정 접근은
 * {@code AccountAccessApi.decide(SENSITIVE)}로 보고, 모듈이 쓰기 트랜잭션 안에서 생명주기 잠금으로 다시 본다 — 삭제가 시작된 계정은
 * 어느 쪽이든 404다. 모든 응답이 {@code no-store}다.
 *
 * <p><b>판정은 결과 접근을 막지 않는다.</b> 대시보드·동기화 경로는 이 판정을 부르지 않는다. 이 경로를 부르는 화면은 Story 4.3이다.
 *
 * <p><b>로그에 사용자·결과·transition이 없다.</b> 한 요청에 한 줄 — 단계·결과 code·재전송 여부·상태·지연뿐이다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AdEligibilityController {

    static final String AD_ELIGIBILITY_PATH = AuthSecurityConfig.ENGAGEMENT_PATH + "/ad-eligibility";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AdEligibilityController.class);

    /** 요청 본문의 상한(문자). 필드 하나의 UUID를 넉넉히 담는다. */
    private static final int MAX_BODY_CHARS = 256;

    /** 정규 UUID 표기. {@link UUID#fromString}이 받는 축약형("1-1-1-1-1")은 받지 않는다. */
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record EligibilityView(String outcome, @Nullable String reason, @Nullable String transition, boolean replayed) {}

    private final AdEligibilityApi eligibility;

    private final AccountAccessApi accounts;

    AdEligibilityController(AdEligibilityApi eligibility, AccountAccessApi accounts) {
        this.eligibility = eligibility;
        this.accounts = accounts;
    }

    @PostMapping(path = AD_ELIGIBILITY_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> judge(@RequestBody(required = false) @Nullable String body, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        UUID userId = allowedUser();
        if (userId == null) {
            return notFound("NOT_FOUND", response, started);
        }
        UUID syncJobId = syncJobIdOf(body);
        if (syncJobId == null) {
            log(ApiErrorHandler.CODE_BAD_REQUEST, false, HttpStatus.BAD_REQUEST.value(), started);
            return error(HttpStatus.BAD_REQUEST, new ApiErrorHandler.ApiError(ApiErrorHandler.CODE_BAD_REQUEST,
                    ApiErrorHandler.MESSAGE_BAD_REQUEST, correlationIdOf(request)));
        }
        AdEligibilityApi.Decision decision;
        try {
            decision = this.eligibility.decide(userId, syncJobId);
        } catch (RuntimeException failure) {
            // 동의·운영 제한 판독 실패도 여기다. 자격으로 접지 않는다(fail-closed) — 화면은 광고 없이 결과로 간다(Story 4.3).
            // 운영에서 보이도록 WARN으로 예외의 단순 이름 하나만 남긴다. 메시지·원인·스택에는 SQL이나 입력 값이 실릴 수 있다.
            LOG.atWarn().addKeyValue("exception", failure.getClass().getSimpleName())
                    .log("ad eligibility judgment failed closed");
            log("ERROR", false, HttpStatus.INTERNAL_SERVER_ERROR.value(), started);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, new ApiErrorHandler.ApiError(
                    ApiErrorHandler.CODE_INTERNAL_ERROR, ApiErrorHandler.MESSAGE_INTERNAL_ERROR,
                    correlationIdOf(request)));
        }
        return switch (decision) {
            case AdEligibilityApi.Decision.Eligible eligible -> {
                log("ELIGIBLE", eligible.replayed(), HttpStatus.OK.value(), started);
                yield ok(new EligibilityView("ELIGIBLE", null, eligible.transition(), eligible.replayed()));
            }
            case AdEligibilityApi.Decision.Skipped skipped -> {
                log(skipped.reason().name(), skipped.replayed(), HttpStatus.OK.value(), started);
                yield ok(new EligibilityView("SKIPPED", skipped.reason().name(), null, skipped.replayed()));
            }
            // 후보가 아닌 결과와 쓰기 잠금 아래에서 거절된 계정(삭제 시작)은 앞의 접근 판정과 같은 404다 — 구분되지 않는다.
            case AdEligibilityApi.Decision.NotCandidate notCandidate -> notFound("NOT_CANDIDATE", response, started);
            case AdEligibilityApi.Decision.Denied denied -> notFound("NOT_FOUND", response, started);
        };
    }

    /**
     * 본문의 결과 참조. JSON 객체가 아니거나, {@code syncJobId}가 문자열이 아니거나, 정규 UUID 표기가 아니면 {@code null}(400)이다.
     */
    static @Nullable UUID syncJobIdOf(@Nullable String body) {
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
        tools.jackson.databind.JsonNode syncJobId = node.get("syncJobId");
        if (syncJobId == null || !syncJobId.isString() || !CANONICAL_UUID.matcher(syncJobId.asString()).matches()) {
            return null;
        }
        return UUID.fromString(syncJobId.asString());
    }

    private static ResponseEntity<EligibilityView> ok(EligibilityView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
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

    private static @Nullable ResponseEntity<?> notFound(String result, HttpServletResponse response, long started)
            throws IOException {
        // 없는 경로와 같은 404. 오류 디스패치를 지나 공통 본문이 된다.
        response.sendError(HttpStatus.NOT_FOUND.value());
        log(result, false, HttpStatus.NOT_FOUND.value(), started);
        return null;
    }

    private static ResponseEntity<ApiErrorHandler.ApiError> error(HttpStatus status, ApiErrorHandler.ApiError body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }

    private static String correlationIdOf(HttpServletRequest request) {
        return ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER));
    }

    /** 단계·결과 code·재전송 여부·상태·지연만 남긴다. 사용자·결과·transition은 싣지 않는다. */
    private static void log(String result, boolean replayed, int status, long started) {
        LOG.info("ad eligibility request: step=judge result={} replayed={} status={} latencyMs={}", result, replayed,
                status, (System.nanoTime() - started) / 1_000_000);
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
