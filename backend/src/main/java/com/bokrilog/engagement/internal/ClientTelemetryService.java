package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.ClientTelemetryApi;
import com.bokrilog.identity.AccountAccessApi;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ClientTelemetryApi}의 구현(Story 4.5). {@code internal}의 package-private 클래스다.
 *
 * <p>잠금 순서는 자격 판정·소비와 같다 — 사용자 advisory 잠금(같은 이름) → 계정 생명주기 잠금 → 보고할 SHOW 행 {@code FOR UPDATE}.
 * 같은 사용자의 소비와 beacon이 한 줄로 서므로 같은 attempt의 동시 묶음 가운데 하나만 표본을 남긴다.
 *
 * <p><b>표본은 SHOW 소비 1건당 metric마다 하나다.</b> 묶음 안의 광고 문서 Vitals({@code /ad-transition}·{@code ad=true})를
 * metric마다 첫 값 하나로 모으고, 소비 뒤 10분 안인 SHOW 행이 있으면 그 행에서 아직 보고되지 않은 metric만 표본으로 쓰고 그
 * metric을 표시한다 — 한 문서의 Vitals는 여러 묶음으로 나뉘어 온다(결과 보기 flush의 LCP, 문서 hidden의 CLS·INP). SHOW가
 * 아니거나 10분이 지났거나 이미 보고된 metric이거나 남의 계정이면 카운터만 센다. 표본에는 사용자·attempt가 없다.
 *
 * <p>로그를 남기지 않는다 — 결과 code는 호출자가 남긴다. 카운터는 커밋 뒤에 센다({@link AdMetrics}).
 */
@Service
class ClientTelemetryService implements ClientTelemetryApi {

    private static final Duration SAMPLE_WINDOW = Duration.ofMinutes(SAMPLE_WINDOW_MINUTES);

    private final AdAttemptRepository attempts;

    private final AdVitalSampleRepository samples;

    private final AccountAccessApi accounts;

    private final AdMetrics metrics;

    private final TransactionTemplate transactions;

    ClientTelemetryService(AdAttemptRepository attempts, AdVitalSampleRepository samples, AccountAccessApi accounts,
            AdMetrics metrics, PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.samples = samples;
        this.accounts = accounts;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Result record(UUID userId, List<Event> events) {
        if (events.isEmpty() || events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException("묶음은 1~" + MAX_EVENTS + "건이다.");
        }
        Map<VitalMetric, Double> adVitals = new EnumMap<>(VitalMetric.class);
        for (Event event : events) {
            if (event instanceof Event.Vital vital && vital.ad() && vital.route() == Route.AD_TRANSITION) {
                adVitals.putIfAbsent(vital.metric(), vital.value());
            }
        }
        Result result = this.transactions.execute(status -> {
            this.attempts.lockUser(userId);
            if (!this.accounts.lockWriteAccess(userId)) {
                return Result.DENIED;
            }
            if (!adVitals.isEmpty()) {
                var attempt = this.attempts.reportableShownForUpdate(userId, SAMPLE_WINDOW).orElse(null);
                if (attempt != null) {
                    Set<String> fresh = new TreeSet<>();
                    adVitals.forEach((metric, value) -> {
                        if (!attempt.reported().contains(metric.name())) {
                            this.samples.insert(metric, value);
                            fresh.add(metric.name());
                        }
                    });
                    if (!fresh.isEmpty()) {
                        this.attempts.markVitalsReported(attempt.id(), fresh);
                    }
                }
            }
            this.metrics.clientEvents(events);
            return Result.RECORDED;
        });
        if (result == null) {
            throw new IllegalStateException("계측 기록의 결과가 없다.");
        }
        return result;
    }
}
