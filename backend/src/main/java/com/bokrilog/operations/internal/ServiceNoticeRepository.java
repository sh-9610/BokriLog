package com.bokrilog.operations.internal;

import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.ServiceNoticeApi.NoticeStatus;
import com.bokrilog.operations.ServiceNoticeApi.NoticeVersion;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code operations_notice_incident}·{@code operations_notice_version}에 닿는 <b>유일한</b> 자리(Story 3.5 AC-3).
 *
 * <p><b>추가 전용이다.</b> 이 파일의 문장은 INSERT와 SELECT뿐이다. 버전 행을 고치거나 지우는 문장이 없고,
 * {@code ServiceNoticeTest}가 운영 소스를 훑어 그 사실을 강제한다 — 그래서 이 파일의 주석에도 그 두 SQL 낱말을 대문자로
 * 적지 않는다(시험이 세는 것은 문자열이다). 행 잠금도 쓰지 않는다 — 발행은 원장 추가 잠금({@link
 * OperatorAuditRepository#lockAppends}) 아래에서만 일어나므로 그 잠금이 같은 사건의 버전 판정을 직렬화하고, 같은 번호의
 * 최후 권위는 기본키다.
 *
 * <p>시각의 권위는 데이터베이스다. 시작·복구·작성 시각과 "지금"은 모두 문장의 {@code statement_timestamp()}다.
 */
@Repository
class ServiceNoticeRepository {

    /** 버전 행과 사건의 시작 시각. 조회가 모두 이 목록을 쓴다. */
    private static final String VERSION_COLUMNS = "v.incident_id, v.version, v.previous_version, v.status, "
            + "i.started_at, v.affected_features, v.continuing_features, v.message, v.next_update_at, v.recovered_at, "
            + "v.covers, v.operation_id, v.support_contact, v.created_at";

    private static final String FROM = " FROM operations_notice_version v "
            + "JOIN operations_notice_incident i ON i.incident_id = v.incident_id ";

    /** 그 사건의 최신 버전인가. */
    private static final String IS_LATEST = "v.version = (SELECT max(w.version) FROM operations_notice_version w "
            + "WHERE w.incident_id = v.incident_id)";

    private final JdbcClient jdbc;

    ServiceNoticeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 지금(이 문장의 DB 시각). */
    Instant now() {
        return this.jdbc.sql("SELECT statement_timestamp()").query((rs, row) -> instant(rs, 1)).single();
    }

    /** 버전 기본키의 이름. 같은 번호의 동시 발행을 가르는 데 쓴다(V21이 고정한 이름). */
    static final String VERSION_PRIMARY_KEY = "operations_notice_version_pkey";

    /** 그 시각이 지금보다 미래이고 지금부터 {@code maxSeconds} 안인가. 판정은 DB 시각이다. */
    boolean withinNextUpdateWindow(Instant at, long maxSeconds) {
        Boolean inside = this.jdbc.sql("SELECT CAST(:at AS timestamptz) > statement_timestamp() "
                        + "AND CAST(:at AS timestamptz) <= statement_timestamp() "
                        + "+ make_interval(secs => CAST(:max AS double precision))")
                .param("at", utc(at))
                .param("max", maxSeconds)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(inside);
    }

    /** 그 사건의 최신 버전. 없는 사건이면 빈 값이다. */
    Optional<NoticeVersion> latest(UUID incidentId) {
        return this.jdbc.sql("SELECT " + VERSION_COLUMNS + FROM + "WHERE v.incident_id = :id "
                        + "ORDER BY v.version DESC LIMIT 1")
                .param("id", incidentId)
                .query(ServiceNoticeRepository::version)
                .optional();
    }

    /**
     * 사용자 조회의 후보: 사건마다 최신 버전 가운데 (1) 진행 중이고 그 제한들 중 하나라도 덮는 것과 (2) 복구 시각부터
     * {@code recoveredWindowSeconds} 안의 복구 버전.
     */
    List<NoticeVersion> latestRelevant(Collection<UUID> effectiveRestrictionIds, long recoveredWindowSeconds) {
        return this.jdbc.sql("SELECT " + VERSION_COLUMNS + FROM + "WHERE " + IS_LATEST + " AND ("
                        + "(v.status <> 'RECOVERED' AND v.covers && CAST(:ids AS uuid[])) OR "
                        + "(v.status = 'RECOVERED' AND statement_timestamp() < v.recovered_at "
                        + "+ make_interval(secs => CAST(:window AS double precision)))) "
                        + "ORDER BY i.started_at, v.incident_id")
                .param("ids", uuidArray(effectiveRestrictionIds))
                .param("window", recoveredWindowSeconds)
                .query(ServiceNoticeRepository::version)
                .list();
    }

    /** 최근에 시작한 사건 {@code limit}개의 모든 버전. 사건은 최근 순, 버전은 오래된 순이다. */
    List<NoticeVersion> recentIncidentVersions(int limit) {
        return this.jdbc.sql("SELECT " + VERSION_COLUMNS + FROM + "WHERE v.incident_id IN (SELECT incident_id "
                        + "FROM operations_notice_incident ORDER BY started_at DESC, incident_id LIMIT :limit) "
                        + "ORDER BY i.started_at DESC, v.incident_id, v.version")
                .param("limit", limit)
                .query(ServiceNoticeRepository::version)
                .list();
    }

    /** 새 사건 한 행. 시작 시각은 이 문장의 DB 시각이다. 호출자의 트랜잭션에 참여한다. */
    Instant insertIncident(UUID incidentId, String operationId) {
        return this.jdbc.sql("INSERT INTO operations_notice_incident (incident_id, operation_id, started_at) "
                        + "VALUES (:id, :op, statement_timestamp()) RETURNING started_at")
                .param("id", incidentId)
                .param("op", operationId)
                .query((rs, row) -> instant(rs, 1))
                .single();
    }

    /** 새 버전이 쓴 시각들. */
    record Written(Instant createdAt, @Nullable Instant recoveredAt) {}

    /**
     * 버전 한 행. 복구 버전의 복구 시각은 이 문장의 DB 시각이다. 같은 번호가 이미 있으면 기본키가 거절한다
     * ({@code DuplicateKeyException}). 호출자의 트랜잭션에 참여한다.
     */
    Written insertVersion(UUID incidentId, int version, NoticeStatus status, String message,
            List<RestrictionFeature> affected, List<RestrictionFeature> continuing, @Nullable Instant nextUpdateAt,
            List<UUID> covers, String operationId, String supportContact) {
        return this.jdbc.sql("INSERT INTO operations_notice_version (incident_id, version, previous_version, status, "
                        + "message, affected_features, continuing_features, next_update_at, recovered_at, covers, "
                        + "operation_id, support_contact, created_at) VALUES (:id, :version, :previous, :status, "
                        + ":message, CAST(:affected AS text[]), CAST(:continuing AS text[]), "
                        + "CAST(:next AS timestamptz), CASE WHEN :status = 'RECOVERED' THEN statement_timestamp() END, "
                        + "CAST(:covers AS uuid[]), :op, :contact, statement_timestamp()) "
                        + "RETURNING created_at, recovered_at")
                .param("id", incidentId)
                .param("version", version)
                .param("previous", version == 1 ? null : version - 1)
                .param("status", status.name())
                .param("message", message)
                .param("affected", textArray(affected))
                .param("continuing", textArray(continuing))
                .param("next", nextUpdateAt == null ? null : utc(nextUpdateAt))
                .param("covers", uuidArray(covers))
                .param("op", operationId)
                .param("contact", supportContact)
                .query((rs, row) -> new Written(instant(rs, 1), nullableInstant(rs, 2)))
                .single();
    }

    private static NoticeVersion version(ResultSet rs, int index) throws SQLException {
        int previous = rs.getInt("previous_version");
        Integer previousVersion = rs.wasNull() ? null : previous;
        return new NoticeVersion(rs.getObject("incident_id", UUID.class), rs.getInt("version"), previousVersion,
                NoticeStatus.valueOf(rs.getString("status")), instant(rs, "started_at"),
                features(rs.getArray("affected_features")), features(rs.getArray("continuing_features")),
                rs.getString("message"), nullableInstant(rs, "next_update_at"), nullableInstant(rs, "recovered_at"),
                uuids(rs.getArray("covers")), rs.getString("operation_id"), rs.getString("support_contact"),
                instant(rs, "created_at"));
    }

    private static List<RestrictionFeature> features(@Nullable Array array) throws SQLException {
        List<RestrictionFeature> features = new ArrayList<>();
        if (array != null) {
            for (Object item : (Object[]) array.getArray()) {
                features.add(RestrictionFeature.valueOf(String.valueOf(item)));
            }
        }
        return List.copyOf(features);
    }

    private static List<UUID> uuids(@Nullable Array array) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        if (array != null) {
            for (Object item : (Object[]) array.getArray()) {
                ids.add(item instanceof UUID id ? id : UUID.fromString(String.valueOf(item)));
            }
        }
        return List.copyOf(ids);
    }

    /** PostgreSQL {@code uuid[]} 리터럴 하나. UUID 글자에는 이스케이프할 문자가 없다. */
    private static String uuidArray(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    /** PostgreSQL {@code text[]} 리터럴 하나. 기능 이름은 대문자·밑줄뿐이라 이스케이프할 문자가 없다. */
    private static String textArray(List<RestrictionFeature> features) {
        return features.stream().map(Enum::name).collect(Collectors.joining(",", "{", "}"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException("공지 행의 필수 시각이 비어 있다.");
        }
        return value.toInstant();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) {
            throw new SQLException("공지 행의 필수 시각이 비어 있다.");
        }
        return value.toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
