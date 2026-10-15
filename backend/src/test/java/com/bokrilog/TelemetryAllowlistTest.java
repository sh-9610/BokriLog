package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.engagement.AdTelemetry;
import com.bokrilog.sync.SyncTelemetry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;

/**
 * 관리 포트의 메트릭 노출과 allowlist(Story 3.7 AC-1·AC-3·AC-6).
 *
 * <p>Prometheus 출력은 관리 포트에서 실제 HTTP로 읽는다 — 노출 범위(엔드포인트 하나)와 공개 포트의 404를 같은 경로로 본다.
 * 출력 스캔은 시계열의 <b>정체</b>(이름·label)와 설명 줄을 본다. 표본 값(숫자)은 보지 않는다 — 메모리 바이트 수 같은 값은
 * 당연히 여러 자리 숫자이고 사용자 귀속 값이 아니다.
 */
class TelemetryAllowlistTest extends OperatorTestBase {

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern EMAIL = Pattern.compile("[^\\s@\"]+@[^\\s@\"]+\\.[^\\s@\"]+");

    private static final Pattern LONG_DIGITS = Pattern.compile("[0-9]{6,}");

    /** 시계열 줄: 이름, 선택 label 묶음(따옴표 안의 `{}`는 경로 템플릿이다), 값(과 선택 timestamp). */
    private static final Pattern SERIES = Pattern.compile(
            "([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{(?:[^\"}]|\"(?:[^\"\\\\]|\\\\.)*\")*\\})?\\s+\\S+(\\s+\\S+)?");

    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MeterRegistry registry;

    @Test
    void 관리_포트의_출력은_allowlist_메트릭만이고_모든_시계열에_env가_있으며_식별자가_없다() throws Exception {
        // 경로 템플릿이 있는 요청(쿼리에 식별자), 템플릿이 없는 요청, 공개 status.
        Signed user = signedIn("telemetry-uri");
        UUID planId = UUID.randomUUID();
        var evidence = get(AuthSecurityConfig.DASHBOARD_PATH + "/evidence?planId=" + planId
                + "&pointerVersion=1&calculationId=" + UUID.randomUUID(), user.cookie());
        assertThat(evidence.statusCode()).as(evidence.body()).isIn(404, 409, 503);
        assertThat(get("/api/telemetry-no-such-path/" + planId, null).statusCode()).isEqualTo(404);
        assertThat(get(AuthSecurityConfig.STATUS_PATH, null).statusCode()).isEqualTo(200);

        String body = scrape();
        List<Series> series = series(body);
        assertThat(series).isNotEmpty();
        Set<String> allowedNames = prometheusNames();
        for (Series each : series) {
            assertThat(allowedNames).as(each.name()).contains(each.name());
            assertThat(each.labels()).as(each.name()).containsEntry("env", "development");
            // 공통 tag role(Story 3.8). 시험 컨텍스트는 기본 역할 all이다.
            assertThat(each.labels()).as(each.name()).containsEntry("role", "all");
        }
        // 정체(이름·label)와 설명 줄에 UUID·이메일·숫자 6자리 이상이 없다.
        for (String identity : identities(body)) {
            assertThat(UUID_TEXT.matcher(identity).find()).as(identity).isFalse();
            assertThat(EMAIL.matcher(identity).find()).as(identity).isFalse();
            assertThat(LONG_DIGITS.matcher(identity).find()).as(identity).isFalse();
        }
        assertThat(body).doesNotContain(planId.toString()).doesNotContain(user.user().toString())
                .doesNotContain("planId").doesNotContain("?");

        // uri는 경로 템플릿이고 쿼리가 없다. 템플릿이 없는 요청은 UNKNOWN이다.
        List<Map<String, String>> requests = series.stream()
                .filter(each -> each.name().equals("http_server_requests_seconds_count")).map(Series::labels).toList();
        assertThat(requests).anySatisfy(labels -> assertThat(labels)
                .containsEntry("uri", AuthSecurityConfig.DASHBOARD_PATH + "/evidence").containsEntry("method", "GET"));
        assertThat(requests).anySatisfy(labels -> assertThat(labels).containsEntry("uri", "UNKNOWN"));
        assertThat(requests).allSatisfy(labels -> assertThat(labels)
                .containsOnlyKeys("env", "role", "method", "uri", "status", "outcome"));

        // 동기화 대기 나이는 여섯 종류 모두 있다(주기 측정). JVM 메모리와 DB 연결도 있다.
        Set<String> kinds = new LinkedHashSet<>();
        series.stream().filter(each -> each.name().equals("bokrilog_sync_queue_age_seconds"))
                .forEach(each -> kinds.add(each.labels().get("kind")));
        assertThat(kinds).containsExactlyInAnyOrder("plan_sync", "first_snapshot", "initial_collection",
                "classification", "conversion", "first_publication");
        assertThat(series).anySatisfy(each -> assertThat(each.name()).isEqualTo("jvm_memory_used_bytes"));
        assertThat(series).anySatisfy(each -> assertThat(each.name()).isEqualTo("hikaricp_connections_active"));
        assertThat(series).anySatisfy(each -> assertThat(each.name()).isEqualTo("bokrilog_support_pending_target"));

        // 광고 자격(Story 4.2)의 카운터 셋은 판정 전에도 0으로 있고, skip은 닫힌 사유 일곱만 label로 갖는다.
        assertThat(series).anySatisfy(each -> assertThat(each.name()).isEqualTo("bokrilog_ad_trigger_total"));
        assertThat(series).anySatisfy(each -> assertThat(each.name()).isEqualTo("bokrilog_ad_reservation_total"));
        Set<String> reasons = new LinkedHashSet<>();
        series.stream().filter(each -> each.name().equals("bokrilog_ad_skip_total"))
                .forEach(each -> reasons.add(each.labels().get("reason")));
        assertThat(reasons).containsExactlyInAnyOrderElementsOf(AdTelemetry.SKIP_REASONS);
        assertThat(series.stream().filter(each -> each.name().startsWith("bokrilog_ad_")).map(Series::labels))
                .allSatisfy(labels -> assertThat(labels.keySet()).isSubsetOf("env", "role", "reason"));
    }

    /** Story 4.2: 광고 카운터 셋. trigger·reservation은 tag가 없고 skip은 닫힌 사유 일곱만 받는다. */
    @Test
    void 광고_카운터는_닫힌_사유_tag만_받는다() {
        assertThat(TelemetryAllowlist.tagKeys(AdTelemetry.TRIGGER)).isEmpty();
        assertThat(TelemetryAllowlist.tagKeys(AdTelemetry.RESERVATION)).isEmpty();
        assertThat(TelemetryAllowlist.tagKeys(AdTelemetry.SKIP)).containsExactly("reason");
        assertThat(AdTelemetry.SKIP_REASONS).containsExactlyInAnyOrder("CONSENT_REQUIRED", "RESTRICTED",
                "PROVIDER_DISABLED", "INTERVAL", "DAILY_CAP", "NOT_CANDIDATE", "REPLAYED");
        // 건너뜀 사유 다섯은 API의 닫힌 사유와 같다.
        for (var reason : com.bokrilog.engagement.AdEligibilityApi.SkipReason.values()) {
            assertThat(AdTelemetry.SKIP_REASONS).contains(reason.name());
        }
        for (String reason : AdTelemetry.SKIP_REASONS) {
            assertThat(TelemetryAllowlist.allows(AdTelemetry.SKIP, Map.of("reason", reason, "env", "ci"), Set.of()))
                    .as(reason).isTrue();
        }
        String marker = UUID.randomUUID().toString();
        assertThat(TelemetryAllowlist.allows(AdTelemetry.SKIP, Map.of("reason", marker), Set.of())).isFalse();
        assertThat(TelemetryAllowlist.allows(AdTelemetry.SKIP, Map.of("reason", "INTERVAL", "jobId", marker),
                Set.of())).isFalse();
        assertThat(TelemetryAllowlist.allows(AdTelemetry.RESERVATION, Map.of("transition", marker), Set.of())).isFalse();
        assertThat(TelemetryAllowlist.allows(AdTelemetry.TRIGGER, Map.of("userId", marker), Set.of())).isFalse();
    }

    @Test
    void 표_밖의_이름_tag_값은_출력에_없고_기동을_막지_않는다() throws Exception {
        String marker = UUID.randomUUID().toString();
        this.registry.counter("bokrilog.telemetry.probe.forbidden").increment();
        this.registry.counter(SyncTelemetry.ATTEMPTS, "kind", "plan_sync", "trigger", "MANUAL", "userId", marker)
                .increment();
        this.registry.counter(SyncTelemetry.ATTEMPTS, "kind", "plan_sync", "trigger", marker).increment();
        this.registry.counter(SyncTelemetry.OUTCOMES, "kind", "plan_sync", "outcome", "success", "reason", marker)
                .increment();
        String body = scrape();
        assertThat(body).doesNotContain("telemetry_probe_forbidden").doesNotContain(marker)
                .doesNotContain("userId");
    }

    @Test
    void 공개_포트의_actuator는_404이고_관리_포트는_prometheus_하나만_연다() throws Exception {
        for (String path : List.of("/actuator", "/actuator/prometheus", "/actuator/health", "/actuator/info")) {
            var response = get(path, null);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(topLevelFieldsOf(response.body())).as(path).containsEntry("code", "NOT_FOUND");
        }
        for (String path : List.of("/actuator", "/actuator/health", "/actuator/info", "/actuator/env",
                "/actuator/beans", "/actuator/metrics", "/actuator/loggers")) {
            assertThat(management(path).statusCode()).as(path).isEqualTo(404);
        }
        assertThat(management("/actuator/prometheus").statusCode()).isEqualTo(200);
    }

    @Test
    void env는_네_값_밖이면_기동을_거부한다() {
        TelemetryConfig config = new TelemetryConfig();
        // `restore`는 격리 복원(Story 3.10)의 API·Worker다 — 운영 경보(env="production")에 섞이지 않는다.
        for (String env : List.of("development", "ci", "production", "restore")) {
            assertThat(config.telemetryMeterFilter(env, "api")).isNotNull();
        }
        for (String env : List.of("staging", "", "Production", "Restore")) {
            assertThatThrownBy(() -> config.telemetryMeterFilter(env, "api")).as(env)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void role은_세_값_밖이면_기동을_거부하고_모든_meter에_붙는다() {
        TelemetryConfig config = new TelemetryConfig();
        for (String role : List.of("api", "worker", "all")) {
            var filter = config.telemetryMeterFilter("ci", role);
            Meter.Id mapped = filter.map(new Meter.Id(SyncTelemetry.QUEUE_AGE, Tags.of("kind", "plan_sync"), null, null,
                    Meter.Type.GAUGE));
            assertThat(mapped.getTag("role")).isEqualTo(role);
            assertThat(filter.accept(mapped)).isEqualTo(MeterFilterReply.NEUTRAL);
        }
        // 빈 값은 기본 역할이다.
        assertThat(config.telemetryMeterFilter("ci", "").map(new Meter.Id(SyncTelemetry.QUEUE_AGE,
                Tags.of("kind", "plan_sync"), null, null, Meter.Type.GAUGE)).getTag("role")).isEqualTo("all");
        for (String role : List.of("API", "both", "scheduler")) {
            assertThatThrownBy(() -> config.telemetryMeterFilter("ci", role)).as(role)
                    .isInstanceOf(IllegalStateException.class);
        }
        // 이미 붙은 role tag를 다른 값으로 덮어 들여오지 못한다 — map이 이 프로세스의 값으로 바꾼다.
        var filter = config.telemetryMeterFilter("ci", "worker");
        assertThat(filter.map(new Meter.Id(SyncTelemetry.QUEUE_AGE, Tags.of("kind", "plan_sync", "role", "api"), null,
                null, Meter.Type.GAUGE)).getTag("role")).isEqualTo("worker");
        assertThat(TelemetryAllowlist.allows(SyncTelemetry.QUEUE_AGE, Map.of("kind", "plan_sync", "role", "other"),
                Set.of())).isFalse();
    }

    /** Story 3.9: 백업 gauge 넷. 성공 시각은 tag가 없고, 사용률·기한 초과·측정 시각은 저장소 두 값(oci·offsite)만 받는다. */
    @Test
    void 백업_gauge는_닫힌_저장소_tag만_받는다() {
        String lastSuccess = "bokrilog.backup.last.success.timestamp";
        assertThat(TelemetryAllowlist.tagKeys(lastSuccess)).isEmpty();
        assertThat(TelemetryAllowlist.allows(lastSuccess, Map.of("env", "ci", "role", "worker"), Set.of())).isTrue();
        assertThat(TelemetryAllowlist.allows(lastSuccess, Map.of("store", "oci"), Set.of())).isFalse();
        for (String name : new String[] {"bokrilog.backup.store.usage.ratio", "bokrilog.backup.purge.overdue",
                "bokrilog.backup.store.measured.timestamp"}) {
            assertThat(TelemetryAllowlist.tagKeys(name)).containsExactly("store");
            for (String store : new String[] {"oci", "offsite"}) {
                assertThat(TelemetryAllowlist.allows(name, Map.of("store", store, "role", "worker"), Set.of()))
                        .as(name + "/" + store).isTrue();
            }
            assertThat(TelemetryAllowlist.allows(name, Map.of("store", "gen_20261012"), Set.of())).isFalse();
            assertThat(TelemetryAllowlist.allows(name, Map.of("store", "oci", "generation", "gen_20261012"),
                    Set.of())).isFalse();
        }
    }

    @Test
    void filter는_uri를_템플릿으로만_남기고_예외_tag를_걷으며_env를_붙인다() {
        var filter = new TelemetryConfig().telemetryMeterFilter("ci", "all");
        filter.useUriTemplates(Set.of("/api/dashboard/evidence"));
        Meter.Id raw = new Meter.Id(TelemetryAllowlist.HTTP_SERVER_REQUESTS, Tags.of("method", "GET", "uri",
                "/api/plans/" + UUID.randomUUID(), "status", "200", "outcome", "SUCCESS", "exception", "none"),
                null, null, Meter.Type.TIMER);
        Meter.Id mapped = filter.map(raw);
        assertThat(mapped.getTag("uri")).isEqualTo("UNKNOWN");
        assertThat(mapped.getTag("exception")).isNull();
        assertThat(mapped.getTag("env")).isEqualTo("ci");
        assertThat(filter.accept(mapped)).isEqualTo(MeterFilterReply.NEUTRAL);
        Meter.Id templated = filter.map(raw.replaceTags(Tags.of("method", "GET", "uri", "/api/dashboard/evidence",
                "status", "404", "outcome", "CLIENT_ERROR")));
        assertThat(templated.getTag("uri")).isEqualTo("/api/dashboard/evidence");
        assertThat(filter.accept(templated)).isEqualTo(MeterFilterReply.NEUTRAL);
        // 표 밖 이름·키·값은 거부다.
        assertThat(filter.accept(filter.map(new Meter.Id("jvm.threads.live", Tags.empty(), null, null,
                Meter.Type.GAUGE)))).isEqualTo(MeterFilterReply.DENY);
        assertThat(filter.accept(filter.map(new Meter.Id(SyncTelemetry.ATTEMPTS, Tags.of("kind", "plan_sync",
                "trigger", "MANUAL", "planId", "x"), null, null, Meter.Type.COUNTER))))
                .isEqualTo(MeterFilterReply.DENY);
        assertThat(filter.accept(filter.map(new Meter.Id(SyncTelemetry.OUTCOMES, Tags.of("kind", "plan_sync",
                "outcome", "success", "reason", "SOMETHING_NEW"), null, null, Meter.Type.COUNTER))))
                .isEqualTo(MeterFilterReply.DENY);
        // 닫힌 목록 밖의 code는 계측이 OTHER로 접어 등록한다.
        assertThat(SyncTelemetry.reason("SOMETHING_NEW")).isEqualTo(SyncTelemetry.OTHER);
        assertThat(SyncTelemetry.trigger("CRON")).isEqualTo(SyncTelemetry.OTHER);
    }

    // ------------------------------------------------------------------ helpers

    record Series(String name, Map<String, String> labels) {}

    private String scrape() throws Exception {
        HttpResponse<String> response = management("/actuator/prometheus");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return response.body();
    }

    private HttpResponse<String> management(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.managementPort + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static List<Series> series(String body) {
        List<Series> series = new ArrayList<>();
        for (String line : body.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = SERIES.matcher(line.strip());
            assertThat(matcher.matches()).as(line).isTrue();
            Map<String, String> labels = new java.util.LinkedHashMap<>();
            if (matcher.group(2) != null) {
                Matcher label = LABEL.matcher(matcher.group(2));
                while (label.find()) {
                    labels.put(label.group(1), label.group(2));
                }
            }
            series.add(new Series(matcher.group(1), labels));
        }
        return series;
    }

    /** 표본 값을 뺀 줄들: 설명·형식 줄 전체와 시계열 줄의 이름·label 부분. */
    private static List<String> identities(String body) {
        List<String> identities = new ArrayList<>();
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("#")) {
                identities.add(line);
                continue;
            }
            Matcher matcher = SERIES.matcher(line.strip());
            if (matcher.matches()) {
                identities.add(matcher.group(1) + (matcher.group(2) == null ? "" : matcher.group(2)));
            }
        }
        return identities;
    }

    /** 허용 표의 이름이 Prometheus에서 가질 수 있는 시계열 이름들(단위·종류 접미사). */
    private static Set<String> prometheusNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String name : TelemetryAllowlist.names()) {
            String base = name.replace('.', '_');
            for (String unit : List.of("", "_bytes", "_seconds")) {
                for (String suffix : List.of("", "_total", "_count", "_sum", "_max")) {
                    names.add(base + unit + suffix);
                }
            }
        }
        return names;
    }
}
