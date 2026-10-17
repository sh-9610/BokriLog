package com.bokrilog;

import com.bokrilog.engagement.AdTelemetry;
import com.bokrilog.operations.BackupRecordApi;
import com.bokrilog.sync.SyncTelemetry;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * 등록할 수 있는 메트릭의 닫힌 표(Story 3.7 AC-1·AC-3). <b>이 표가 유일한 정의다.</b>
 *
 * <p>행 하나가 meter 이름 하나이고, 그 이름이 가질 수 있는 tag 키와 키마다 허용되는 값의 집합을 담는다. {@link TelemetryConfig}의
 * {@code MeterFilter}가 이 표 밖의 이름, 표 밖의 tag 키, 집합 밖의 값을 가진 meter를 등록 시점에 거부한다 — 기동 실패가 아니라
 * 출력에 없을 뿐이다. 그래서 새 계측을 더할 때는 이 표에 먼저 행을 더해야 출력에 나타난다.
 *
 * <p><b>tag 값은 닫힌 enum 문자열뿐이다.</b> 사용자·계획·요청·계정·연결 ID, 이메일, 금액, Bybit 원문, 원시 URL은 어느 값 집합에도
 * 들어갈 수 없다. 값 집합이 실행 중에 정해지는 것은 둘뿐이고 둘 다 사용자 입력이 아니라 코드·런타임이 정한다 — HTTP {@code uri}는
 * 이 애플리케이션이 등록한 경로 템플릿(그 밖은 {@code UNKNOWN}으로 접는다), JVM 메모리 {@code id}는 이 JVM의 메모리 풀 이름이다.
 *
 * <p>모든 행은 공통 tag {@link #ENV_TAG}를 가진다. 값은 {@link #ENVIRONMENTS} 넷 중 하나라 개발·CI·격리 복원(Story 3.10, `restore`) 수치가 운영 실적과 섞이지 않는다 — 운영 경보의
 * selector는 `env="production"`이다.
 * 공통 tag {@link #ROLE_TAG}(Story 3.8)도 모든 행에 붙는다. 값은 {@link #ROLES} 셋 중 하나라 API·Worker 프로세스의 수치가 갈린다
 * — 예를 들어 동기화 대기 나이는 Worker만 측정한다.
 */
public final class TelemetryAllowlist {

    /** 공통 tag 이름. */
    public static final String ENV_TAG = "env";

    /** {@code BOKRILOG_ENV}가 받을 수 있는 값. */
    public static final Set<String> ENVIRONMENTS = Set.of("development", "ci", "production", "restore");

    /** 공통 tag 이름(Story 3.8). 값은 프로세스 역할({@code bokrilog.role})이다. */
    public static final String ROLE_TAG = "role";

    /** {@code BOKRILOG_ROLE}이 받을 수 있는 값({@link BokrilogRole}). */
    public static final Set<String> ROLES = Set.of("api", "worker", "all");

    /** 경로 템플릿이 없는 요청의 {@code uri}. */
    public static final String UNKNOWN_URI = "UNKNOWN";

    /** Hikari 연결 풀 이름({@code spring.datasource.hikari.pool-name}과 같다). */
    public static final String POOL_NAME = "bokrilog";

    /** HTTP 서버 meter 이름. */
    public static final String HTTP_SERVER_REQUESTS = "http.server.requests";

    /** HTTP 서버 meter에서 등록 전에 걷어 내는 tag. 예외 클래스 이름은 닫힌 enum이 아니다. */
    public static final Set<String> HTTP_DROPPED_TAGS = Set.of("exception", "error");

    /**
     * 허용 값 집합 하나. 고정 집합에 더해, {@code uriTemplates}이면 이 애플리케이션의 경로 템플릿({@link TelemetryConfig}가
     * handler mapping에서 읽어 넘긴다)도 받는다.
     */
    record Values(Set<String> fixed, boolean uriTemplates) {

        static Values of(Set<String> fixed) {
            return new Values(Set.copyOf(fixed), false);
        }

        boolean allows(String value, Set<String> templates) {
            return this.fixed.contains(value) || (this.uriTemplates && templates.contains(value));
        }
    }

    private static final Set<String> MEMORY_POOLS = ManagementFactory.getMemoryPoolMXBeans().stream()
            .map(MemoryPoolMXBean::getName)
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> HTTP_METHODS =
            Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "UNKNOWN");

    private static final Set<String> HTTP_STATUSES = IntStream.rangeClosed(100, 599)
            .mapToObj(Integer::toString)
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> HTTP_OUTCOMES =
            Set.of("INFORMATIONAL", "SUCCESS", "REDIRECTION", "CLIENT_ERROR", "SERVER_ERROR", "UNKNOWN");

    private static final Set<String> SYNC_KINDS = Arrays.stream(SyncTelemetry.Kind.values())
            .map(SyncTelemetry.Kind::tag)
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> BACKUP_STORES = Arrays.stream(BackupRecordApi.Store.values())
            .map(BackupRecordApi.Store::tag)
            .collect(Collectors.toUnmodifiableSet());

    private static final Map<String, Map<String, Values>> METERS = build();

    private TelemetryAllowlist() {}

    private static Map<String, Map<String, Values>> build() {
        Map<String, Map<String, Values>> meters = new LinkedHashMap<>();
        // 동기화(Story 3.7 AC-3). 정의는 SyncTelemetry에 있다.
        meters.put(SyncTelemetry.ATTEMPTS, Map.of(
                "kind", Values.of(SYNC_KINDS),
                "trigger", Values.of(SyncTelemetry.TRIGGERS)));
        meters.put(SyncTelemetry.OUTCOMES, Map.of(
                "kind", Values.of(SYNC_KINDS),
                "outcome", Values.of(SyncTelemetry.OUTCOME_VALUES),
                "reason", Values.of(SyncTelemetry.REASONS)));
        meters.put(SyncTelemetry.QUEUE_AGE, Map.of("kind", Values.of(SYNC_KINDS)));
        // 문의 첫 답변 목표(operations.internal.SupportMetrics).
        meters.put("bokrilog.support.first.answer", Map.of("met", Values.of(Set.of("true", "false", "pending"))));
        meters.put("bokrilog.support.pending.target", Map.of());
        // 광고 자격(Story 4.2). 정의는 AdTelemetry에 있다. 사유는 닫힌 일곱 값이고 사용자·결과·transition 값은 없다.
        meters.put(AdTelemetry.TRIGGER, Map.of());
        meters.put(AdTelemetry.SKIP, Map.of("reason", Values.of(AdTelemetry.SKIP_REASONS)));
        meters.put(AdTelemetry.RESERVATION, Map.of());
        // 광고 소비·브라우저 계측과 kill switch 표본(Story 4.5). 값은 모두 AdTelemetry의 닫힌 enum 문자열이다 — route는 화면
        // 경로 넷이고 사용자·결과·transition 값은 없다.
        meters.put(AdTelemetry.TRANSITION, Map.of("outcome", Values.of(AdTelemetry.TRANSITION_OUTCOMES)));
        meters.put(AdTelemetry.CLIENT_EVENT, Map.of(
                "event", Values.of(AdTelemetry.CLIENT_EVENTS),
                "reason", Values.of(AdTelemetry.CLIENT_REASONS)));
        meters.put(AdTelemetry.WEB_VITALS, Map.of(
                "metric", Values.of(AdTelemetry.VITAL_METRICS),
                "route", Values.of(AdTelemetry.VITAL_ROUTES),
                "rating", Values.of(AdTelemetry.VITAL_RATINGS),
                "ad", Values.of(AdTelemetry.VITAL_ADS)));
        meters.put(AdTelemetry.VITAL_SAMPLES, Map.of("metric", Values.of(AdTelemetry.VITAL_METRICS)));
        // 백업(Story 3.9, operations.internal.BackupMetrics). 저장소는 OCI와 OCI 밖 둘이다. 기록이 없으면 등록되지 않는다(NaN 없음).
        meters.put("bokrilog.backup.last.success.timestamp", Map.of());
        meters.put("bokrilog.backup.store.usage.ratio", Map.of("store", Values.of(BACKUP_STORES)));
        meters.put("bokrilog.backup.purge.overdue", Map.of("store", Values.of(BACKUP_STORES)));
        meters.put("bokrilog.backup.store.measured.timestamp", Map.of("store", Values.of(BACKUP_STORES)));
        // DB 연결(Hikari). 풀은 하나다.
        for (String name : new String[] {"hikaricp.connections", "hikaricp.connections.active",
                "hikaricp.connections.idle", "hikaricp.connections.pending", "hikaricp.connections.max",
                "hikaricp.connections.min", "hikaricp.connections.timeout"}) {
            meters.put(name, Map.of("pool", Values.of(Set.of(POOL_NAME))));
        }
        // JVM 메모리.
        for (String name : new String[] {"jvm.memory.used", "jvm.memory.committed", "jvm.memory.max"}) {
            meters.put(name, Map.of(
                    "area", Values.of(Set.of("heap", "nonheap")),
                    "id", Values.of(MEMORY_POOLS)));
        }
        // HTTP 서버 요청. uri는 경로 템플릿뿐이다(그 밖은 UNKNOWN).
        meters.put(HTTP_SERVER_REQUESTS, Map.of(
                "method", Values.of(HTTP_METHODS),
                "uri", new Values(Set.of(UNKNOWN_URI), true),
                "status", Values.of(HTTP_STATUSES),
                "outcome", Values.of(HTTP_OUTCOMES)));
        return Map.copyOf(meters);
    }

    /** 허용 표의 meter 이름들. */
    public static Set<String> names() {
        return METERS.keySet();
    }

    /** 그 이름의 허용 tag 키(공통 {@code env}·{@code role} 제외). 표 밖 이름이면 {@code null}. */
    public static @Nullable Set<String> tagKeys(String name) {
        Map<String, Values> tags = METERS.get(name);
        return tags == null ? null : tags.keySet();
    }

    /**
     * 그 meter를 등록해도 되는가: 이름이 표에 있고, 모든 tag 키가 그 행(또는 공통 {@code env}·{@code role})에 있고, 값이 그 키의
     * 집합에 있다.
     *
     * @param tags tag 키 → 값
     * @param uriTemplates 이 애플리케이션의 경로 템플릿
     */
    static boolean allows(String name, Map<String, String> tags, Set<String> uriTemplates) {
        Map<String, Values> allowed = METERS.get(name);
        if (allowed == null) {
            return false;
        }
        for (var tag : tags.entrySet()) {
            if (ENV_TAG.equals(tag.getKey())) {
                if (!ENVIRONMENTS.contains(tag.getValue())) {
                    return false;
                }
                continue;
            }
            if (ROLE_TAG.equals(tag.getKey())) {
                if (!ROLES.contains(tag.getValue())) {
                    return false;
                }
                continue;
            }
            Values values = allowed.get(tag.getKey());
            if (values == null || tag.getValue() == null || !values.allows(tag.getValue(), uriTemplates)) {
                return false;
            }
        }
        return true;
    }
}
