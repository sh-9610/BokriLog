package com.bokrilog.operations.internal;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 문의 첫 답변 목표의 계측(Story 3.7 AC-3). 이름·tag는 루트 {@code TelemetryAllowlist}의 표와 같다.
 *
 * <ul>
 *   <li>{@link #FIRST_ANSWER} {@code bokrilog_support_first_answer_total{met}} — 첫 답변({@code ANSWERED} 전이) 하나가 커밋될
 *       때마다 1. {@code met}은 답변 시각(DB)이 목표 시각 이하면 {@code true}, 넘었으면 {@code false}, 목표가 아직 미정(다음
 *       연도 공휴일 seed 없음)이면 {@code pending}이다. 달성률의 분모는 {@code true + false}다. 목표는 SLA가 아니다.
 *   <li>{@link #PENDING} {@code bokrilog_support_pending_target} — 지금 첫 답변을 기다리는 문의 수. 주기 측정이라 스케줄링이 도는
 *       역할에만 있다({@link SupportPendingMetrics}, Story 3.8).
 * </ul>
 * 기간은 프로세스 수명(카운터)과 측정 시점(gauge)이고, 환경은 공통 tag {@code env}가 가른다. 문의 reference·제목·이메일은 어디에도
 * 없다. 첫 답변 카운터는 운영 command를 받는 API 프로세스(와 {@code all})에서 센다.
 */
@Component
class SupportMetrics {

    static final String FIRST_ANSWER = "bokrilog.support.first.answer";

    static final String PENDING = "bokrilog.support.pending.target";

    private final MeterRegistry registry;

    SupportMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String met : new String[] {"true", "false", "pending"}) {
            // 처음 증가할 때 시계열이 생기면 increase()가 그 증가를 놓친다. 0으로 미리 만든다.
            registry.counter(FIRST_ANSWER, "met", met);
        }
    }

    /** 첫 답변 하나(호출한 트랜잭션이 커밋되면). */
    void firstAnswered(Instant answeredAt, @Nullable Instant targetAt) {
        String met = targetAt == null ? "pending" : Boolean.toString(!answeredAt.isAfter(targetAt));
        Runnable count = () -> this.registry.counter(FIRST_ANSWER, "met", met).increment();
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
