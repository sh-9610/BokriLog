package com.bokrilog;

import com.bokrilog.engagement.ClientTelemetryApi;
import com.bokrilog.identity.AccountAccessApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 브라우저 계측 beacon(Story 4.5).
 *
 * <p>{@code POST /api/engagement/telemetry} 본문 {@code {"events":[…]}} — 1~16건, 8 KiB 이하. 이벤트는 둘 중 하나다.
 * <ul>
 *   <li>{@code {"kind":"VITAL","metric":"LCP|INP|CLS","route":"/dashboard|/settings|/plan-conditions|/ad-transition",
 *       "value":<0 이상 유한수>,"ad":<bool>}}
 *   <li>{@code {"kind":"AD","event":"REQUESTED|READY|CLOSED|FAILED|RESULT","reason":"TIMEOUT|NO_FILL|BLOCKED|UNSUPPORTED|
 *       ERROR|WINDOW_EXPIRED"}} — {@code reason}은 {@code FAILED}에만 있고 거기서는 필수다.
 * </ul>
 * 필드가 더 있거나 빠졌거나 어휘 밖이면 — 하나라도 — 400이고 아무것도 기록하지 않는다. 성공은 본문 없는 204, 계정이 쓰기를 받지
 * 않으면(삭제가 시작된 계정) 없는 경로와 같은 404(기록 없음), 실패는 500이다. 모든 응답이 {@code no-store}다.
 *
 * <p>인증·CSRF·{@code Origin}은 {@code AuthSecurityConfig.isEngagementPath} 하나가 건다 — 그래서 브라우저는 {@code sendBeacon}이 아니라
 * CSRF 헤더를 실은 {@code fetch keepalive}로 보낸다. 멱등 키를 받지 않는다: 같은 묶음의 재전송은 카운터를 한 번 더 올릴 뿐이고
 * 표본은 SHOW 소비 1건당 metric마다 한 번이다. 브라우저는 재시도하지 않는다.
 *
 * <p><b>로그에 사용자·값이 없다.</b> 한 요청에 한 줄 — 결과 code·상태·이벤트 수·지연뿐이다.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ClientTelemetryController {

    static final String TELEMETRY_PATH = AuthSecurityConfig.ENGAGEMENT_PATH + "/telemetry";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClientTelemetryController.class);

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static final Set<String> VITAL_FIELDS = Set.of("kind", "metric", "route", "value", "ad");

    private static final Set<String> AD_FIELDS = Set.of("kind", "event");

    private static final Set<String> FAILED_FIELDS = Set.of("kind", "event", "reason");

    private final ClientTelemetryApi telemetry;

    private final AccountAccessApi accounts;

    ClientTelemetryController(ClientTelemetryApi telemetry, AccountAccessApi accounts) {
        this.telemetry = telemetry;
        this.accounts = accounts;
    }

    @PostMapping(path = TELEMETRY_PATH)
    @Nullable ResponseEntity<?> record(@RequestBody(required = false) @Nullable String body, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        long started = System.nanoTime();
        UUID userId = ControllerSupport.allowedUser(this.accounts);
        if (userId == null) {
            return notFound(response, started, 0);
        }
        List<ClientTelemetryApi.Event> events = eventsOf(body);
        if (events == null) {
            log(ApiErrorHandler.CODE_BAD_REQUEST, HttpStatus.BAD_REQUEST.value(), 0, started);
            return ControllerSupport.error(HttpStatus.BAD_REQUEST, ApiErrorHandler.CODE_BAD_REQUEST,
                    ApiErrorHandler.MESSAGE_BAD_REQUEST, request);
        }
        ClientTelemetryApi.Result result;
        try {
            result = this.telemetry.record(userId, events);
        } catch (RuntimeException failure) {
            // 예외의 단순 이름 하나만 WARN으로 남긴다. 메시지·원인·스택에는 SQL이나 입력 값이 실릴 수 있다.
            LOG.atWarn().addKeyValue("exception", failure.getClass().getSimpleName())
                    .log("client telemetry record failed");
            log("ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value(), events.size(), started);
            return ControllerSupport.error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorHandler.CODE_INTERNAL_ERROR,
                    ApiErrorHandler.MESSAGE_INTERNAL_ERROR, request);
        }
        if (result == ClientTelemetryApi.Result.DENIED) {
            // 쓰기 잠금 아래에서 거절된 계정(삭제 시작)은 앞의 접근 판정과 같은 404다.
            return notFound(response, started, events.size());
        }
        log("RECORDED", HttpStatus.NO_CONTENT.value(), events.size(), started);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * 본문의 이벤트들. 8 KiB를 넘거나, JSON 객체 {@code {"events":[…]}}가 아니거나, 1~16건이 아니거나, 이벤트 하나라도 어휘·모양
     * 밖이면 {@code null}(400)이다.
     */
    static @Nullable List<ClientTelemetryApi.Event> eventsOf(@Nullable String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > ClientTelemetryApi.MAX_BODY_BYTES) {
            return null;
        }
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (tools.jackson.core.JacksonException malformed) {
            return null;
        }
        if (node == null || !node.isObject() || node.size() != 1) {
            return null;
        }
        JsonNode items = node.get("events");
        if (items == null || !items.isArray() || items.isEmpty() || items.size() > ClientTelemetryApi.MAX_EVENTS) {
            return null;
        }
        List<ClientTelemetryApi.Event> events = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            ClientTelemetryApi.Event event = eventOf(item);
            if (event == null) {
                return null;
            }
            events.add(event);
        }
        return List.copyOf(events);
    }

    private static ClientTelemetryApi.@Nullable Event eventOf(JsonNode item) {
        if (!item.isObject()) {
            return null;
        }
        String kind = textOf(item, "kind");
        if ("VITAL".equals(kind)) {
            if (!fieldsAre(item, VITAL_FIELDS)) {
                return null;
            }
            ClientTelemetryApi.VitalMetric metric = enumOf(ClientTelemetryApi.VitalMetric.class, textOf(item, "metric"));
            String path = textOf(item, "route");
            ClientTelemetryApi.Route route = path == null ? null : ClientTelemetryApi.Route.ofPath(path);
            JsonNode value = item.get("value");
            JsonNode ad = item.get("ad");
            if (metric == null || route == null || value == null || !value.isNumber() || ad == null
                    || !ad.isBoolean()) {
                return null;
            }
            double number = value.asDouble();
            if (!Double.isFinite(number) || number < 0) {
                return null;
            }
            return new ClientTelemetryApi.Event.Vital(metric, route, number, ad.asBoolean());
        }
        if ("AD".equals(kind)) {
            ClientTelemetryApi.AdEvent event = enumOf(ClientTelemetryApi.AdEvent.class, textOf(item, "event"));
            if (event == null) {
                return null;
            }
            if (event == ClientTelemetryApi.AdEvent.FAILED) {
                ClientTelemetryApi.AdFailure reason = enumOf(ClientTelemetryApi.AdFailure.class, textOf(item, "reason"));
                return reason == null || !fieldsAre(item, FAILED_FIELDS) ? null
                        : new ClientTelemetryApi.Event.Ad(event, reason);
            }
            return fieldsAre(item, AD_FIELDS) ? new ClientTelemetryApi.Event.Ad(event, null) : null;
        }
        return null;
    }

    /** 객체의 필드 이름이 정확히 그 집합인가 — 더 있거나 빠지면 거짓이다. */
    private static boolean fieldsAre(JsonNode item, Set<String> names) {
        if (item.size() != names.size()) {
            return false;
        }
        for (String name : names) {
            if (!item.has(name)) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String textOf(JsonNode item, String field) {
        JsonNode value = item.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static <E extends Enum<E>> @Nullable E enumOf(Class<E> type, @Nullable String name) {
        if (name == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    private static @Nullable ResponseEntity<?> notFound(HttpServletResponse response, long started, int events)
            throws IOException {
        // 없는 경로와 같은 404. 오류 디스패치를 지나 공통 본문(no-store)이 된다.
        response.sendError(HttpStatus.NOT_FOUND.value());
        log("NOT_FOUND", HttpStatus.NOT_FOUND.value(), events, started);
        return null;
    }

    /** 결과 code·상태·이벤트 수·지연만 남긴다. 사용자·값은 싣지 않는다. */
    private static void log(String result, int status, int events, long started) {
        LOG.info("client telemetry request: result={} status={} events={} latencyMs={}", result, status, events,
                (System.nanoTime() - started) / 1_000_000);
    }
}
