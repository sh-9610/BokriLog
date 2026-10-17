package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.ClientTelemetryApi.VitalMetric;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 광고 Vitals 표본 표 {@code engagement_ad_vital_sample}의 유일한 SQL 자리(Story 4.5).
 *
 * <p>표본은 metric·값·기록 시각뿐이다 — 사용자·attempt·transition 열이 없다(식별 없는 집계). 문장은 INSERT 하나(beacon 기록),
 * 판정 창 읽기 하나, DELETE 둘(창 밖 표본·발동 또는 열린 광고 제한 시각까지의 표본)뿐이다. 시각의 권위는 DB 시각이다.
 */
@Repository
class AdVitalSampleRepository {

    private final JdbcClient jdbc;

    AdVitalSampleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 표본 하나를 쓴다. 기록 시각은 이 문장의 DB 시각이다. */
    void insert(VitalMetric metric, double value) {
        this.jdbc.sql("INSERT INTO engagement_ad_vital_sample (metric, value) VALUES (:m, :v)")
                .param("m", metric.name())
                .param("v", value)
                .update();
    }

    /** 호출자 트랜잭션의 문장 시한. */
    void limitStatementTime(String timeout) {
        if (!timeout.matches("[0-9]{1,5}(ms|s)")) {
            throw new IllegalArgumentException("statement_timeout 모양이 아니다.");
        }
        this.jdbc.sql("SET LOCAL statement_timeout = '" + timeout + "'").update();
    }

    /** 판정 창 밖(이 문장의 DB 시각에서 {@code window}보다 오래된) 표본을 지운다. */
    int purgeOutside(Duration window) {
        return this.jdbc.sql("DELETE FROM engagement_ad_vital_sample "
                        + "WHERE recorded_at < statement_timestamp() - interval '1 second' * :w")
                .param("w", window.toSeconds())
                .update();
    }

    /** 그 시각까지 기록된 표본을 모두 지운다(kill switch 발동 뒤). */
    int deleteUpTo(Instant at) {
        return this.jdbc.sql("DELETE FROM engagement_ad_vital_sample WHERE recorded_at <= :at")
                .param("at", OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
                .update();
    }

    /**
     * metric 하나의 창 통계.
     *
     * @param samples 창 표본 수
     * @param p75 창 표본의 {@code percentile_disc(0.75)} — 실제 표본 값 하나다. 표본이 없으면 {@code NaN}
     */
    record Stat(long samples, double p75) {}

    /**
     * 판정 창 하나.
     *
     * @param measuredAt 창을 읽은 문장의 DB 시각
     * @param stats metric별 통계. 표본이 없는 metric도 {@code 0}·{@code NaN}으로 담는다
     */
    record Window(Instant measuredAt, Map<VitalMetric, Stat> stats) {}

    /** 판정 창(이 문장의 DB 시각에서 {@code window} 안)의 metric별 통계. */
    Window window(Duration window) {
        Map<VitalMetric, Stat> stats = new EnumMap<>(VitalMetric.class);
        for (VitalMetric metric : VitalMetric.values()) {
            stats.put(metric, new Stat(0, Double.NaN));
        }
        this.jdbc.sql("SELECT metric, count(*) AS samples, percentile_disc(0.75) WITHIN GROUP (ORDER BY value) AS p75 "
                        + "FROM engagement_ad_vital_sample "
                        + "WHERE recorded_at >= statement_timestamp() - interval '1 second' * :w GROUP BY metric")
                .param("w", window.toSeconds())
                .query((java.sql.ResultSet rs) -> {
                    stats.put(VitalMetric.valueOf(rs.getString("metric")),
                            new Stat(rs.getLong("samples"), rs.getDouble("p75")));
                });
        OffsetDateTime now = this.jdbc.sql("SELECT statement_timestamp()").query(OffsetDateTime.class).single();
        if (now == null) {
            throw new IllegalStateException("DB 시각을 읽지 못했다.");
        }
        return new Window(now.toInstant(), stats);
    }
}
