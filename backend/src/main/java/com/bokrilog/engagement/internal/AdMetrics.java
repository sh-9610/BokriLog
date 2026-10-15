package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 광고 자격의 계측(Story 4.2). 이름·tag는 {@link AdTelemetry}의 어휘이고 루트 {@code TelemetryAllowlist}의 표와 같다. 정의는
 * {@link AdTelemetry}에 있다 — 판정 하나가 {@code trigger} 하나와 {@code reservation} 또는 {@code skip{reason}} 하나를 올린다.
 * 모두 호출한 트랜잭션이 커밋된 뒤에 센다(롤백된 판정은 세지 않는다). tag에 사용자·결과·transition 값이 없다.
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
