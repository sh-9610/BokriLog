package com.bokrilog;

import com.bokrilog.engagement.AdTransitionApi;
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
 * 중립 전환 화면의 transition 소비(Story 4.3).
 *
 * <p>{@code POST /api/engagement/ad-transition} 본문 {@code {"transition":"<43자 base64url>"}} — Story 4.2 자격 판정이 발급한
 * 불투명 참조다. 200은 둘 중 하나다: {@code {"outcome":"SHOW","adUnitPath":"<ad unit 경로>"}}(이 소비로 광고를 시도한다) 또는
 * {@code {"outcome":"SKIP","reason":"<사유>"}}({@code CONSUMED}·{@code EXPIRED}·{@code CONSENT_REQUIRED}·{@code RESTRICTED}·
 * {@code PROVIDER_DISABLED}). 사용자·결과·금융 값은 싣지 않는다. 그 사용자의 참조가 아니면(남의 것·없는 것·삭제가 시작된 계정) 없는
 * 경로와 같은 404이고, 본문 모양 오류는 400, 실패(운영 제한·동의 판독 실패 포함 — fail-closed, 소비도 롤백된다)는 500이다. 모든
 * 응답이 {@code no-store}다.
 *
 * <p>인증·CSRF·{@code Origin}은 {@code AuthSecurityConfig.isEngagementPath} 하나가 건다. 참조 자체가 일회성이라 멱등 키 헤더를
 * 받지 않는다 — 같은 참조의 재전송은 {@code CONSUMED}다.
 *
 * <p><b>로그에 사용자·transition이 없다.</b> 한 요청에 한 줄 — 단계·결과 code·상태·지연뿐이다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AdTransitionController {

    static final String AD_TRANSITION_PATH = AuthSecurityConfig.ENGAGEMENT_PATH + "/ad-transition";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AdTransitionController.class);

    /** 요청 본문의 상한(문자). 필드 하나의 43자 참조를 넉넉히 담는다. */
    private static final int MAX_BODY_CHARS = 256;

    /**
     * transition 참조의 모양 — 256비트 base64url 무패딩 43자(V27 CHECK와 같다). 전체 일치로 본다. 화면의 {@code TRANSITION_PATTERN}·
     * V27 CHECK와의 대조는 {@code AdSharedValuesTest}가 하므로 package-private이다.
     */
    static final Pattern TRANSITION = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** 응답 본문. 없는 필드는 싣지 않는다 — SHOW는 {@code adUnitPath}만, SKIP은 {@code reason}만 있다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ClaimView(String outcome, @Nullable String reason, @Nullable String adUnitPath) {}

    private final AdTransitionApi transitions;

    private final AccountAccessApi accounts;

    AdTransitionController(AdTransitionApi transitions, AccountAccessApi accounts) {
        this.transitions = transitions;
        this.accounts = accounts;
    }

    @PostMapping(path = AD_TRANSITION_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Nullable ResponseEntity<?> claim(@RequestBody(required = false) @Nullable String body, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        UUID userId = allowedUser();
        if (userId == null) {
            return notFound(response, started);
        }
        String transition = transitionOf(body);
        if (transition == null) {
            log(ApiErrorHandler.CODE_BAD_REQUEST, HttpStatus.BAD_REQUEST.value(), started);
            return error(HttpStatus.BAD_REQUEST, new ApiErrorHandler.ApiError(ApiErrorHandler.CODE_BAD_REQUEST,
                    ApiErrorHandler.MESSAGE_BAD_REQUEST, correlationIdOf(request)));
        }
        AdTransitionApi.Claim claim;
        try {
            claim = this.transitions.claim(userId, transition);
        } catch (RuntimeException failure) {
            // 동의·운영 제한 판독 실패도 여기다. 광고 시도로 접지 않는다(fail-closed) — 화면은 광고 없이 결과로 간다.
            // 예외의 단순 이름 하나만 WARN으로 남긴다. 메시지·원인·스택에는 SQL이나 입력 값이 실릴 수 있다.
            LOG.atWarn().addKeyValue("exception", failure.getClass().getSimpleName())
                    .log("ad transition claim failed closed");
            log("ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value(), started);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, new ApiErrorHandler.ApiError(
                    ApiErrorHandler.CODE_INTERNAL_ERROR, ApiErrorHandler.MESSAGE_INTERNAL_ERROR,
                    correlationIdOf(request)));
        }
        return switch (claim) {
            case AdTransitionApi.Claim.Show show -> {
                log("SHOW", HttpStatus.OK.value(), started);
                yield ok(new ClaimView("SHOW", null, show.adUnitPath()));
            }
            case AdTransitionApi.Claim.Skip skip -> {
                log(skip.reason().name(), HttpStatus.OK.value(), started);
                yield ok(new ClaimView("SKIP", skip.reason().name(), null));
            }
            // 남의 참조·없는 참조와 쓰기 잠금 아래에서 거절된 계정(삭제 시작)은 앞의 접근 판정과 같은 404다 — 구분되지 않는다.
            case AdTransitionApi.Claim.NotFound notFound -> notFound(response, started);
        };
    }

    /**
     * 본문의 transition 참조. JSON 객체가 아니거나, {@code transition}이 문자열이 아니거나, 43자 base64url이 아니면
     * {@code null}(400)이다.
     */
    static @Nullable String transitionOf(@Nullable String body) {
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
        tools.jackson.databind.JsonNode transition = node.get("transition");
        if (transition == null || !transition.isString() || !TRANSITION.matcher(transition.asString()).matches()) {
            return null;
        }
        return transition.asString();
    }

    private static ResponseEntity<ClaimView> ok(ClaimView view) {
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

    private static @Nullable ResponseEntity<?> notFound(HttpServletResponse response, long started) throws IOException {
        // 없는 경로와 같은 404. 오류 디스패치를 지나 공통 본문(no-store)이 된다.
        response.sendError(HttpStatus.NOT_FOUND.value());
        log("NOT_FOUND", HttpStatus.NOT_FOUND.value(), started);
        return null;
    }

    private static ResponseEntity<ApiErrorHandler.ApiError> error(HttpStatus status, ApiErrorHandler.ApiError body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(body);
    }

    private static String correlationIdOf(HttpServletRequest request) {
        return ApiErrorHandler.correlationIdOf(request.getHeader(ApiErrorHandler.REQUEST_ID_HEADER));
    }

    /** 단계·결과 code·상태·지연만 남긴다. 사용자·transition은 싣지 않는다. */
    private static void log(String result, int status, long started) {
        LOG.info("ad transition request: step=claim result={} status={} latencyMs={}", result, status,
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
