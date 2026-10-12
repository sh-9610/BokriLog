package com.bokrilog;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 메트릭 노출의 배선(Story 3.7 AC-1·AC-3). Actuator에 닿는 설정은 여기 한 곳이다 — 모듈은 Micrometer {@code MeterRegistry}만
 * 쓴다.
 *
 * <p><b>노출.</b> Prometheus 엔드포인트 하나가 관리 포트({@code management.server.port}, 기본 18081)에서만 듣는다
 * ({@code application.yml}). compose는 그 포트를 게시하지 않고 Nginx는 {@code /api}만 넘기므로 공개 origin의
 * {@code /actuator/**}는 404다. 외부 liveness는 여전히 {@code GET /api/status}다({@link StatusController}).
 *
 * <p><b>거부.</b> {@link #telemetryMeterFilter}가 모든 meter에 공통 tag {@code env}를 붙이고, HTTP 서버 meter의 {@code uri}를
 * 경로 템플릿으로만 남기며(템플릿이 없으면 {@code UNKNOWN}), {@link TelemetryAllowlist}의 표 밖 meter를 등록 시점에 거부한다.
 */
@Configuration(proxyBeanMethods = false)
class TelemetryConfig {

    /**
     * 공통 tag {@code env}와 allowlist. 값이 셋({@code development·ci·production}) 밖이면 기동을 거부한다 — 잘못 쓴 환경 값으로
     * 개발 수치가 운영 실적에 섞이는 것을 조용히 두지 않는다.
     */
    @Bean
    TelemetryMeterFilter telemetryMeterFilter(@Value("${bokrilog.telemetry.env:development}") String env) {
        if (!TelemetryAllowlist.ENVIRONMENTS.contains(env)) {
            throw new IllegalStateException("bokrilog.telemetry.env는 development·ci·production 중 하나여야 한다.");
        }
        return new TelemetryMeterFilter(env);
    }

    /**
     * 이 애플리케이션의 경로 템플릿을 filter에 넘긴다. 모든 singleton이 만들어진 직후(웹 서버가 요청을 받기 전)에 확정한다 —
     * 그 전에 온 요청이 있으면 Micrometer가 그 meter id를 {@code UNKNOWN}으로 캐시해 템플릿이 영영 붙지 않는다. handler
     * mapping은 자기 초기화({@code afterPropertiesSet})에서 메서드를 등록하므로 이 시점에 다 있다.
     */
    @Bean
    SmartInitializingSingleton telemetryUriTemplates(TelemetryMeterFilter filter,
            ObjectProvider<RequestMappingHandlerMapping> mappings) {
        return () -> {
            Set<String> templates = new LinkedHashSet<>();
            mappings.orderedStream().forEach(mapping ->
                    mapping.getHandlerMethods().keySet().forEach(info -> templates.addAll(info.getPatternValues())));
            filter.useUriTemplates(templates);
        };
    }

    /** {@link TelemetryAllowlist}를 집행하는 filter. */
    static final class TelemetryMeterFilter implements MeterFilter {

        private final String env;
        private volatile Set<String> uriTemplates = Set.of();

        TelemetryMeterFilter(String env) {
            this.env = env;
        }

        /** 템플릿을 더한다. 관리 포트의 자식 컨텍스트도 같은 이벤트를 부모에 올리므로 덮어쓰지 않고 합친다. */
        synchronized void useUriTemplates(Set<String> templates) {
            Set<String> merged = new LinkedHashSet<>(this.uriTemplates);
            merged.addAll(templates);
            this.uriTemplates = Set.copyOf(merged);
        }

        Set<String> uriTemplates() {
            return this.uriTemplates;
        }

        @Override
        public Meter.Id map(Meter.Id id) {
            List<Tag> tags = new ArrayList<>();
            boolean http = TelemetryAllowlist.HTTP_SERVER_REQUESTS.equals(id.getName());
            for (Tag tag : id.getTagsAsIterable()) {
                if (TelemetryAllowlist.ENV_TAG.equals(tag.getKey())) {
                    continue;
                }
                if (http && TelemetryAllowlist.HTTP_DROPPED_TAGS.contains(tag.getKey())) {
                    continue;
                }
                if (http && "uri".equals(tag.getKey()) && !this.uriTemplates.contains(tag.getValue())) {
                    // 템플릿이 아닌 값(원시 경로·NOT_FOUND·REDIRECTION 등)은 하나로 접는다.
                    tags.add(Tag.of("uri", TelemetryAllowlist.UNKNOWN_URI));
                    continue;
                }
                tags.add(tag);
            }
            tags.add(Tag.of(TelemetryAllowlist.ENV_TAG, this.env));
            return id.replaceTags(tags);
        }

        @Override
        public MeterFilterReply accept(Meter.Id id) {
            Map<String, String> tags = new LinkedHashMap<>();
            for (Tag tag : id.getTagsAsIterable()) {
                tags.put(tag.getKey(), tag.getValue());
            }
            return TelemetryAllowlist.allows(id.getName(), tags, this.uriTemplates) ? MeterFilterReply.NEUTRAL
                    : MeterFilterReply.DENY;
        }
    }
}
