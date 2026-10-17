package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdTelemetry;
import com.bokrilog.engagement.ClientTelemetryApi;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 광고 자격의 계측(Story 4.2)과 transition 소비·브라우저 계측(Story 4.5). 이름·tag는 {@link AdTelemetry}의 어휘이고 루트
 * {@code TelemetryAllowlist}의 표와 같다. 정의는 {@link AdTelemetry}에 있다 — 판정 하나가 {@code trigger} 하나와 {@code reservation}
 * 또는 {@code skip{reason}} 하나를, 소비 하나가 {@code transition{outcome}} 하나를, beacon 이벤트 하나가 {@code client.event} 또는
 * {@code web.vitals} 하나를 올린다. 모두 호출한 트랜잭션이 커밋된 뒤에 센다(롤백된 판정은 세지 않는다). tag에 사용자·결과·
 * transition 값이 없다.
 */
@Component
class AdMetrics {

    private final MeterRegistry registry;

    AdMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 처음 증가할 때 시계열이 생기면 increase()가 그 증가를 놓친다. 0으로 미리 만든다.
        registry.counter(AdTelemetry.TRIGGER);
        registry.counter(AdTelemetry.RESERVATION);
        for (String reason : AdTelemetry.SKIP_REASONS) {
            registry.counter(AdTelemetry.SKIP, "reason", reason);
        }
        for (String outcome : AdTelemetry.TRANSITION_OUTCOMES) {
            registry.counter(AdTelemetry.TRANSITION, "outcome", outcome);
        }
        for (ClientTelemetryApi.AdEvent event : ClientTelemetryApi.AdEvent.values()) {
            if (event == ClientTelemetryApi.AdEvent.FAILED) {
                for (ClientTelemetryApi.AdFailure reason : ClientTelemetryApi.AdFailure.values()) {
                    registry.counter(AdTelemetry.CLIENT_EVENT, "event", event.name(), "reason", reason.name());
                }
            } else {
                registry.counter(AdTelemetry.CLIENT_EVENT, "event", event.name(), "reason", AdTelemetry.NONE);
            }
        }
        for (ClientTelemetryApi.VitalMetric metric : ClientTelemetryApi.VitalMetric.values()) {
            for (ClientTelemetryApi.Route route : ClientTelemetryApi.Route.values()) {
                for (String rating : AdTelemetry.VITAL_RATINGS) {
                    for (String ad : AdTelemetry.VITAL_ADS) {
                        registry.counter(AdTelemetry.WEB_VITALS, "metric", metric.name(), "route", route.path(),
                                "rating", rating, "ad", ad);
                    }
                }
            }
        }
    }

    /** 새 자격 하나(커밋되면). */
    void reserved() {
        afterCommit(() -> {
            this.registry.counter(AdTelemetry.TRIGGER).increment();
            this.registry.counter(AdTelemetry.RESERVATION).increment();
        });
    }

    /** 새 자격이 아닌 판정 하나(커밋되면). 사유는 {@link AdTelemetry#SKIP_REASONS}의 값이다. */
    void skipped(String reason) {
        if (!AdTelemetry.SKIP_REASONS.contains(reason)) {
            throw new IllegalArgumentException("닫힌 사유 밖의 값이다.");
        }
        afterCommit(() -> {
            this.registry.counter(AdTelemetry.TRIGGER).increment();
            this.registry.counter(AdTelemetry.SKIP, "reason", reason).increment();
        });
    }

    /** transition 소비의 결말 하나(커밋되면). 결말은 {@link AdTelemetry#TRANSITION_OUTCOMES}의 값이다. */
    void transition(String outcome) {
        if (!AdTelemetry.TRANSITION_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("닫힌 결말 밖의 값이다.");
        }
        afterCommit(() -> this.registry.counter(AdTelemetry.TRANSITION, "outcome", outcome).increment());
    }

    /** beacon 묶음 하나의 이벤트들(커밋되면). 값은 API의 enum이라 언제나 닫힌 어휘 안이다. */
    void clientEvents(List<ClientTelemetryApi.Event> events) {
        List<ClientTelemetryApi.Event> counted = List.copyOf(events);
        afterCommit(() -> {
            for (ClientTelemetryApi.Event event : counted) {
                switch (event) {
                    case ClientTelemetryApi.Event.Vital vital -> this.registry.counter(AdTelemetry.WEB_VITALS,
                            "metric", vital.metric().name(), "route", vital.route().path(), "rating",
                            vital.metric().poor(vital.value()) ? AdTelemetry.POOR : AdTelemetry.GOOD, "ad",
                            String.valueOf(vital.ad())).increment();
                    case ClientTelemetryApi.Event.Ad ad -> this.registry.counter(AdTelemetry.CLIENT_EVENT, "event",
                            ad.event().name(), "reason", ad.reason() == null ? AdTelemetry.NONE : ad.reason().name())
                            .increment();
                }
            }
        });
    }

    private static void afterCommit(Runnable count) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    count.run();
                }
            });
        } else {
            count.run();
        }
    }
}
