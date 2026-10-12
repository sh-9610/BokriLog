package com.bokrilog.operations.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 첫 답변을 기다리는 문의 수의 주기 측정(Story 3.7 AC-3, {@link SupportMetrics#PENDING}).
 *
 * <p>60초 주기로 DB에서 읽는다(접수·처리 중). 읽지 못했으면 NaN(미측정)이다. 측정은 읽기뿐이다 — 해결 뒤 90일 삭제는 여전히 조회
 * 트랜잭션이 한다(백그라운드 삭제 없음, Story 3.6). 측정은 공유 스케줄러가 아니라 가상 스레드에서 돌고, 읽기 트랜잭션에 {@code
 * statement_timeout} 2초를 건다.
 *
 * <p><b>스케줄링이 도는 역할에만 있다</b>(Story 3.8, 루트 {@code BokrilogRole.Schedules}와 같은 판정 — {@code worker}·{@code all}). API 역할에는
 * 측정이 없으므로 gauge도 없다 — NaN gauge를 영원히 내보내지 않는다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
// 루트 `BokrilogRole.Schedules`와 같은 판정이다(api가 아니면 참 — 읽을 수 없는 값도 참이고 그 값은 기동이 거부된다). 모듈이 루트 타입에
// 기대면 루트↔모듈 순환이 생겨 같은 규칙을 식으로 둔다. 셋의 일치는 역할 시험들이 고정한다.
@ConditionalOnExpression("'${bokrilog.role:all}' != 'api'")
class SupportPendingMetrics {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SupportPendingMetrics.class);

    private final SupportInquiryRepository inquiries;
    private final TransactionTemplate reads;
    private final AtomicBoolean measuring = new AtomicBoolean();
    private final AtomicLong pending = new AtomicLong(Double.doubleToLongBits(Double.NaN));

    SupportPendingMetrics(MeterRegistry registry, SupportInquiryRepository inquiries,
            PlatformTransactionManager transactionManager) {
        this.inquiries = inquiries;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        Gauge.builder(SupportMetrics.PENDING, this.pending, held -> Double.longBitsToDouble(held.get()))
                .description("첫 답변을 기다리는 문의 수(60초 주기 측정)")
                .register(registry);
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
