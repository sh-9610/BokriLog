package com.bokrilog.operations.internal;

import com.bokrilog.operations.BackupRecordApi.Row;
import com.bokrilog.operations.BackupRecordApi.Store;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 백업 기록 {@code operations_backup_event}의 <b>유일한</b> SQL 자리(Story 3.9).
 *
 * <p><b>추가 전용이다.</b> 이 파일의 문장은 INSERT와 SELECT뿐이다. 원 행을 고치거나 지우는 문장이 없고, {@code
 * BackupEventRepositoryTest}가 운영 소스 전체를 훑어 그 사실과 "이 표를 문자열로 아는 클래스가 이것 하나"를 강제한다 — 그래서 이
 * 파일의 주석에도 그 두 SQL 낱말을 대문자로 적지 않는다.
 *
 * <p>기록은 advisory 잠금 하나로 직렬화한다({@link #lockAppends}). 백업은 하루 한 번이라 비용이 없고, 한 실행의 행이 {@code
 * sequence}에서 이어진다.
 */
@Repository
class BackupEventRepository {

    /** 기록 잠금의 키. 원장 잠금({@code bokrilog.operations_audit_event.append})과 다른 이름이다. */
    private static final String APPEND_LOCK_NAME = "bokrilog.operations_backup_event.append";

    private final JdbcClient jdbc;

    BackupEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 호출자 트랜잭션 끝까지 기록을 직렬화한다. */
    void lockAppends() {
        this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
                .param("name", APPEND_LOCK_NAME)
                .query((rs, row) -> 1)
                .single();
    }

    /** 행 하나를 추가한다. 시각은 이 문장의 DB 시각이다. */
    void append(Row row) {
        this.jdbc.sql("INSERT INTO operations_backup_event (generation_id, event, store, code, bytes, count, "
                        + "quota_bytes, sha256, key_id) VALUES (:generation, :event, :store, :code, :bytes, :count, "
                        + ":quota, :sha256, :keyId)")
                .param("generation", row.generationId())
                .param("event", row.event().name())
                .param("store", row.store() == null ? null : row.store().tag())
                .param("code", row.code())
                .param("bytes", row.bytes())
                .param("count", row.count())
                .param("quota", row.quotaBytes())
                .param("sha256", row.sha256())
                .param("keyId", row.keyId())
                .update();
    }

    /** 이 읽기 트랜잭션의 문장 상한. 주기 측정이 DB를 오래 붙들지 않게. */
    void limitStatementTime(String timeout) {
        this.jdbc.sql("SET LOCAL statement_timeout = '" + timeout + "'").update();
    }

    /** 마지막 성공 기록의 DB 시각(epoch 초, 소수 포함). 성공이 없으면 비어 있다. */
    Optional<Double> lastSuccessEpochSeconds() {
        List<@Nullable Double> values = this.jdbc.sql("SELECT CAST(extract(epoch FROM max(recorded_at)) AS double precision) "
                        + "FROM operations_backup_event WHERE event = 'SUCCEEDED'")
                .query((rs, row) -> {
                    double value = rs.getDouble(1);
                    return rs.wasNull() ? null : value;
                })
                .list();
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }

    /**
     * 저장소 하나의 최신 측정.
     *
     * @param overdue 그 측정이 기한 초과 generation이 남았다고 적었는가
     * @param measuredAtEpochSeconds 그 측정 기록의 DB 시각(epoch 초) — 저장소를 읽지 못해 측정이 멈추면 이 값이 늙는다
     */
    record Measured(long bytes, long quotaBytes, boolean overdue, double measuredAtEpochSeconds) {}

    /** 저장소별 최신 측정. 측정이 없는 저장소는 빠진다. */
    Map<Store, Measured> latestMeasured() {
        Map<Store, Measured> measured = new EnumMap<>(Store.class);
        this.jdbc.sql("SELECT DISTINCT ON (store) store, bytes, quota_bytes, code, "
                        + "CAST(extract(epoch FROM recorded_at) AS double precision) AS measured_at "
                        + "FROM operations_backup_event WHERE event = 'MEASURED' ORDER BY store, sequence DESC")
                .query((java.sql.ResultSet rs) -> {
                    measured.put(Store.valueOf(rs.getString("store").toUpperCase(Locale.ROOT)),
                            new Measured(rs.getLong("bytes"), rs.getLong("quota_bytes"), rs.getString("code") != null,
                                    rs.getDouble("measured_at")));
                });
        return measured;
    }

    /** 그 generation에 성공 기록이 있는가. */
    boolean succeeded(String generationId) {
        Boolean exists = this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM operations_backup_event "
                        + "WHERE event = 'SUCCEEDED' AND generation_id = :generation)")
                .param("generation", generationId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }
}
