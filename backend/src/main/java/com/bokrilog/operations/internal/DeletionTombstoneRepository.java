package com.bokrilog.operations.internal;

import com.bokrilog.operations.DeletionTombstoneApi.Tombstone;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 삭제 tombstone outbox {@code operations_deletion_tombstone}의 <b>유일한</b> SQL 자리(Story 3.10).
 *
 * <p>문장은 INSERT·SELECT와 기한 지난 행을 지우는 문장 하나({@link #purgeExpired})뿐이다. 행을 고치는 문장이 없고, {@code
 * DeletionTombstoneTest}가 운영 소스 전체를 훑어 그 사실과 "이 표를 SQL로 아는 클래스가 이것 하나"를 강제한다. 시각의 권위는 DB다.
 */
@Repository
class DeletionTombstoneRepository {

    private final JdbcClient jdbc;

    DeletionTombstoneRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 새 tombstone 하나. 요청 시각은 이 문장의 DB 시각(ms)이고 기한은 그 + 840시간이다. 같은 요청·같은 계정의 행이 있으면 0행이다. */
    int insert(String userDigest, String requestId) {
        return this.jdbc.sql("INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, origin) "
                        + "SELECT :request, :digest, t.at, t.at + interval '840 hours', 'DELETION' "
                        + "FROM (SELECT date_trunc('milliseconds', statement_timestamp()) AS at) t "
                        + "ON CONFLICT DO NOTHING")
                .param("request", requestId)
                .param("digest", userDigest)
                .update();
    }

    /** 외부 사본의 tombstone 하나를 시각 그대로 옮긴다. 같은 요청·같은 계정의 행이 있으면 0행이다. */
    int insertCarried(Tombstone tombstone) {
        return this.jdbc.sql("INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, origin) "
                        + "VALUES (:request, :digest, :requested, :destroy, 'RESTORE') ON CONFLICT DO NOTHING")
                .param("request", tombstone.requestId())
                .param("digest", tombstone.userDigest())
                .param("requested", OffsetDateTime.ofInstant(tombstone.requestedAt(), ZoneOffset.UTC))
                .param("destroy", OffsetDateTime.ofInstant(tombstone.destroyAfter(), ZoneOffset.UTC))
                .update();
    }

    /** 그 digest의 행이 있는가. */
    boolean exists(String userDigest) {
        Boolean exists = this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM operations_deletion_tombstone WHERE user_digest = :digest)")
                .param("digest", userDigest)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    /** 파기 기한이 이 문장의 DB 시각 이전인 행을 지운다. */
    int purgeExpired() {
        return this.jdbc.sql("DELETE FROM operations_deletion_tombstone WHERE destroy_after <= statement_timestamp()")
                .update();
    }

    /**
     * 외부 사본에 실을 행(요청 시각·요청 ID 순): 파기 기한이 이 문장의 DB 시각 + 24시간(`DeletionTombstoneApi.EXPORT_MARGIN`)보다 뒤다.
     * 사본 object가 24시간 남으므로 외부의 어느 사본도 파기 기한을 넘겨 남지 않는다.
     */
    List<Tombstone> exportable() {
        return rows("destroy_after > statement_timestamp() + interval '24 hours'");
    }

    /** 파기 기한이 지나지 않은 행 전체(복원 적용의 대조). */
    List<Tombstone> live() {
        return rows("destroy_after > statement_timestamp()");
    }

    /** 그 가운데 이 DB 계보의 삭제가 쓴 행(origin `DELETION`) — 복원의 계정 대조가 본다. */
    List<Tombstone> liveRecorded() {
        return rows("destroy_after > statement_timestamp() AND origin = 'DELETION'");
    }

    /** 그 요청 ID의 행의 digest. 없으면 빈 값이다. */
    java.util.Optional<String> digestOf(String requestId) {
        return this.jdbc.sql("SELECT user_digest FROM operations_deletion_tombstone WHERE request_id = :request")
                .param("request", requestId)
                .query(String.class)
                .optional();
    }

    private List<Tombstone> rows(String predicate) {
        return this.jdbc.sql("SELECT user_digest, request_id, requested_at, destroy_after FROM operations_deletion_tombstone "
                        + "WHERE " + predicate + " ORDER BY requested_at, request_id")
                .query((rs, row) -> new Tombstone(rs.getString(1), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant(), rs.getObject(4, OffsetDateTime.class).toInstant()))
                .list();
    }
}
