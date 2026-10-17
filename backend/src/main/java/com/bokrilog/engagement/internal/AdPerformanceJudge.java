package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdTelemetry;
import com.bokrilog.engagement.ClientTelemetryApi.VitalMetric;
import com.bokrilog.operations.AdvertisingStopApi;
import com.bokrilog.operations.OperationalRestrictionApi;
import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionSubject;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 광고 성능 kill switch의 판정(Story 4.5, 정책 {@code AD-PERF-1.0}).
 *
 * <p><b>5분 주기.</b> 한 번의 판정은 (1) 판정 창(24시간, DB 시각) 밖 표본을 지우고 metric별 표본 수와 {@code percentile_disc(0.75)}를
 * 읽는다 — 읽기 트랜잭션 하나, {@code statement_timeout} 2초. (2) 전역 {@code ADVERTISING} 제한이 이미 효력 중이면(운영자·앞선 발동
 * 무엇이든) 판정하지 않고 그 시각까지의 표본을 모두 지운다 — 광고가 꺼진 동안 남은 묵은 표본(발동 뒤 늦게 커밋된 것 포함)이 해제
 * 직후 다시 발동시키지 않게. (3) 아니면 표본이 {@value #MIN_SAMPLES}개 이상이고 p75가 NFR-14 임계를 <b>넘는</b> metric이 있을 때
 * operations의 {@link AdvertisingStopApi#stop}을 부르고, 새 제한이면 그 효력 시작까지, 그사이 다른 경로가 제한을 열었으면
 * ({@code AlreadyRestricted}) 창을 읽은 시각까지의 표본을 지운다. (4) 창 표본 수를 gauge {@code bokrilog_ad_vitals_samples{metric}}에
 * 쓴다(읽지 못했으면 NaN — 미측정).
 *
 * <p>"p75 ≤ 임계"는 "임계 초과 표본 비율 ≤ 25%"와 같다 — 표본 40개면 40개 중 11개 이상이 넘어야 발동한다
 * ({@code percentile_disc(0.75)}는 30번째 값이다). 표본은 SHOW 소비 1건당 metric마다 한 번이고 사용자 귀속이 없다(남용은 여러 계정이
 * 필요하고, 최악은 광고 수익 정지다).
 *
 * <p><b>스케줄링이 도는 역할에만 있다</b>({@code worker}·{@code all} — 루트 {@code BokrilogRole.Schedules}와 같은 판정을 식으로 둔다,
 * {@code operations.internal.SupportPendingMetrics}와 같은 이유). 판정은 공유 스케줄러가 아니라 가상 스레드에서 돌고 겹치지 않는다.
 * 자동 해제는 없다 — 제한은 운영자의 {@code lift-restriction}으로만 닫힌다. 판독·판정 실패는 WARN 한 줄(예외의 단순 이름만)이고 다음
 * 주기가 다시 판정한다.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnExpression("'${bokrilog.role:all}' != 'api'")
class AdPerformanceJudge {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AdPerformanceJudge.class);

    /** 판정 창. */
    static final Duration WINDOW = Duration.ofHours(24);

    /** 발동에 필요한 metric별 최소 표본 수. */
    static final int MIN_SAMPLES = 40;

    /** 판정 주기(ms). */
    static final long PERIOD_MS = 5 * 60 * 1000L;

    private final AdVitalSampleRepository samples;

    private final AdvertisingStopApi stops;

    private final OperationalRestrictionApi restrictions;

    private final TransactionTemplate transactions;

    private final AtomicBoolean judging = new AtomicBoolean();

    private final Map<VitalMetric, AtomicLong> windowSamples = new EnumMap<>(VitalMetric.class);

    AdPerformanceJudge(MeterRegistry registry, AdVitalSampleRepository samples, AdvertisingStopApi stops,
            OperationalRestrictionApi restrictions, PlatformTransactionManager transactionManager) {
        this.samples = samples;
        this.stops = stops;
        this.restrictions = restrictions;
        this.transactions = new TransactionTemplate(transactionManager);
        for (VitalMetric metric : VitalMetric.values()) {
            AtomicLong held = new AtomicLong(Double.doubleToLongBits(Double.NaN));
            this.windowSamples.put(metric, held);
            Gauge.builder(AdTelemetry.VITAL_SAMPLES, held, value -> Double.longBitsToDouble(value.get()))
                    .description("광고 Vitals 판정 창(24시간)의 표본 수(5분 주기 측정)")
                    .tag("metric", metric.name())
                    .register(registry);
        }
    }

    @Scheduled(initialDelay = 0, fixedDelay = PERIOD_MS)
    void judge() {
        if (!this.judging.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("ad-performance-judge").start(() -> {
            try {
                judgeNow();
            } catch (RuntimeException failed) {
                // 다음 주기가 다시 판정한다. 메시지·원인은 남기지 않는다 — SQL이 실린다. 종류만.
                LOG.atWarn().addKeyValue("exception", failed.getClass().getSimpleName())
                        .log("ad performance judge failed");
            } finally {
                this.judging.set(false);
            }
        });
    }

    /** 지금 한 번 판정한다. 창을 읽지 못하면 gauge만 NaN으로 두고 끝난다. 판정 중의 실패(stop 포함)는 예외로 올라간다. */
    void judgeNow() {
        AdVitalSampleRepository.Window window = measure();
        if (window == null) {
            return;
        }
        if (this.restrictions.blocks(EnumSet.of(RestrictionFeature.ADVERTISING), RestrictionSubject.none())) {
            // 광고가 이미 꺼져 있다. 판정하지 않고 묵은 표본을 비운다 — 해제 뒤에는 새 표본으로 다시 센다.
            purgeUpTo(window.measuredAt());
            return;
        }
        for (VitalMetric metric : VitalMetric.values()) {
            AdVitalSampleRepository.Stat stat = window.stats().get(metric);
            if (stat.samples() < MIN_SAMPLES || !metric.poor(stat.p75())) {
                continue;
            }
            AdvertisingStopApi.Stop stop = this.stops.stop(new AdvertisingStopApi.Breach(
                    AdvertisingStopApi.Metric.valueOf(metric.name()), stat.samples(), stat.p75()));
            purgeUpTo(switch (stop) {
                // 발동 시각까지의 표본을 지운다 — 운영자가 검증 뒤 해제하면 새 표본으로 다시 시작한다.
                case AdvertisingStopApi.Stop.Created created -> created.effectiveFrom();
                // 창을 읽은 뒤 다른 경로가 광고를 껐다. 창을 읽은 시각까지 비운다.
                case AdvertisingStopApi.Stop.AlreadyRestricted already -> window.measuredAt();
            });
            return;
        }
    }

    /** 그 시각까지의 표본을 지우고 gauge를 다시 잰다. */
    private void purgeUpTo(Instant at) {
        this.transactions.executeWithoutResult(status -> this.samples.deleteUpTo(at));
        measure();
    }

    /** 창 밖 표본을 지우고 창 통계를 읽어 gauge에 쓴다. 읽지 못했으면 gauge를 NaN으로 두고 {@code null}이다. */
    private AdVitalSampleRepository.@Nullable Window measure() {
        AdVitalSampleRepository.Window window;
        try {
            window = this.transactions.execute(status -> {
                this.samples.limitStatementTime("2s");
                this.samples.purgeOutside(WINDOW);
                return this.samples.window(WINDOW);
            });
        } catch (RuntimeException unreadable) {
            window = null;
            // 메시지·원인은 남기지 않는다 — SQL이 실린다. 종류만.
            LOG.atWarn().addKeyValue("exception", unreadable.getClass().getSimpleName())
                    .log("ad vitals window unreadable");
        }
        for (VitalMetric metric : VitalMetric.values()) {
            double value = window == null ? Double.NaN : window.stats().get(metric).samples();
            this.windowSamples.get(metric).set(Double.doubleToLongBits(value));
        }
        return window;
    }
}
