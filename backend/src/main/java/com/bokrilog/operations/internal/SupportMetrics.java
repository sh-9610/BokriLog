package com.bokrilog.operations.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
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
 *   <li>{@link #PENDING} {@code bokrilog_support_pending_target} — 지금 첫 답변을 기다리는 문의 수(접수·처리 중). 60초 주기로
 *       DB에서 읽는다. 읽지 못했으면 NaN(미측정)이다.
 * </ul>
 * 기간은 프로세스 수명(카운터)과 측정 시점(gauge)이고, 환경은 공통 tag {@code env}가 가른다. 문의 reference·제목·이메일은 어디에도
 * 없다.
 *
 * <p>주기 측정은 읽기뿐이다. 해결 뒤 90일 삭제는 여전히 조회 트랜잭션이 한다(백그라운드 삭제 없음, Story 3.6). 스케줄은 API
 * 프로세스에만 있다. 측정은 공유 스케줄러가 아니라 가상 스레드에서 돌고, 읽기 트랜잭션에 {@code statement_timeout} 2초를 건다.
 */
@Component
class SupportMetrics {

    static final String FIRST_ANSWER = "bokrilog.support.first.answer";

    static final String PENDING = "bokrilog.support.pending.target";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SupportMetrics.class);

    private final MeterRegistry registry;
    private final SupportInquiryRepository inquiries;
    private final org.springframework.transaction.support.TransactionTemplate reads;
    private final java.util.concurrent.atomic.AtomicBoolean measuring = new java.util.concurrent.atomic.AtomicBoolean();
    private final AtomicLong pending = new AtomicLong(Double.doubleToLongBits(Double.NaN));

    SupportMetrics(MeterRegistry registry, SupportInquiryRepository inquiries,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.inquiries = inquiries;
        this.reads = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        for (String met : new String[] {"true", "false", "pending"}) {
            // 처음 증가할 때 시계열이 생기면 increase()가 그 증가를 놓친다. 0으로 미리 만든다.
            registry.counter(FIRST_ANSWER, "met", met);
        }
        Gauge.builder(PENDING, this.pending, held -> Double.longBitsToDouble(held.get()))
                .description("첫 답변을 기다리는 문의 수(60초 주기 측정)")
                .register(registry);
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

    @Scheduled(initialDelay = 0, fixedDelay = 60_000)
    void measurePending() {
        if (!this.measuring.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("support-pending").start(() -> {
            try {
                measurePendingNow();
            } finally {
                this.measuring.set(false);
            }
        });
    }

    /** 대기 문의 수를 지금 다시 읽는다. 실패하면 NaN(미측정)이다. */
    void measurePendingNow() {
        double value;
        try {
            Long count = this.reads.execute(status -> {
                this.inquiries.limitStatementTime("2s");
                return this.inquiries.awaitingFirstAnswer();
            });
            value = count == null ? Double.NaN : count;
        } catch (RuntimeException unreadable) {
            value = Double.NaN;
            LOG.atDebug().addKeyValue("exception", unreadable.getClass().getSimpleName())
                    .log("support pending count unreadable");
        }
        this.pending.set(Double.doubleToLongBits(value));
    }
}
