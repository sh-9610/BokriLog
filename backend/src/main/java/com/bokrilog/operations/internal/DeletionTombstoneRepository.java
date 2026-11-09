package com.bokrilog.operations.internal;

import com.bokrilog.operations.DeletionTombstoneApi.AffectedGeneration;
import com.bokrilog.operations.DeletionTombstoneApi.DestructionStatus;
import com.bokrilog.operations.DeletionTombstoneApi.GenerationSet;
import com.bokrilog.operations.DeletionTombstoneApi.Tombstone;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 삭제 tombstone outbox {@code operations_deletion_tombstone}과 그 영향 generation·내보내기 기록(V32)의 <b>유일한</b> SQL 자리(Story 3.10,
 * Story 5.3).
 *
 * <p>tombstone 행을 바꾸는 문장은 정해진 여섯뿐이다 — 집합 닫기·보존 연장 둘({@link #close}·{@link #closeAt}), 초과 전환
 * ({@link #markOverdue}), 파기 완료 전환({@link #markDestroyed}), 복원의 외부 상태 병합({@link #mergeCarried}), 그리고 내보내기가 DESTROYED
 * 행에 처음 실린 번호를 붙이는 것({@link #markDestroyedExport}). 여섯 다 단조다(닫힌 집합을 다시 열지 않고, 보존을 줄이지 않고, 상태를
 * PENDING &lt; OVERDUE &lt; DESTROYED 밖으로 되돌리지 않고, 붙인 번호를 바꾸지 않는다). 지우는 문장은 백업 파기가 완료됐고 그 상태로 성공한
 * 내보내기에 실렸고 보존 기한이 지난 tombstone({@link #purgeExpired} — 영향 generation 행은 외래키로 함께 지워진다)과 35일 지난 내보내기
 * 기록({@link #purgeExports}) 둘이다. {@code DeletionTombstoneTest}가 운영 소스 전체를 훑어 그 사실과 "이 표를 SQL로 아는 클래스가 이것
 * 하나"를 강제한다. 시각의 권위는 DB다.
 */
@Repository
class DeletionTombstoneRepository {

    /**
     * 백업 파기 기한(요청 + 30일)의 식. 열로 저장하지 않는다 — 요청 시각에서 늘 정해진다({@code DeletionTombstoneApi.BACKUP_DEADLINE}).
     * 시간대와 무관하게 시간으로 적는다.
     */
    private static final String BACKUP_DEADLINE = "(requested_at + interval '720 hours')";

    /** tombstone 한 행과 그 영향 generation(쉼표 목록, {@code gen_YYYYMMDD:<oci><offsite>})을 읽는 SELECT 머리. */
    private static final String TOMBSTONE_COLUMNS = "SELECT t.user_digest, t.request_id, t.requested_at, t.destroy_after, "
            + "t.status, t.closed_at IS NOT NULL AS closed, COALESCE((SELECT string_agg(g.generation_id || ':' || "
            + "CASE WHEN g.oci_purged_at IS NULL THEN '-' ELSE 'P' END || CASE WHEN g.offsite_purged_at IS NULL THEN '-' "
            + "ELSE 'P' END, ',' ORDER BY g.generation_id) FROM operations_deletion_backup_generation g "
            + "WHERE g.request_id = t.request_id), '') AS generations FROM operations_deletion_tombstone t ";

    /**
     * DESTROYED 행이 그 상태로 성공한 내보내기에 실렸는가: 처음 실린 번호 이상의 내보내기가 성공으로 기록됐다. 이 전에는 그 행을 지우지도
     * 내보내기에서 빼지도 않는다 — 앞선 사본에 미완료로 실린 행이 바로 빠지면 줄어듦 검사가 영원히 실패한다.
     */
    private static final String EXPORTED_AS_DESTROYED = "(t.destroyed_export IS NOT NULL AND EXISTS (SELECT 1 FROM "
            + "operations_tombstone_export e WHERE e.exported_at IS NOT NULL AND e.sequence >= t.destroyed_export))";

    /** 남아 있는(지우지 않을) 행의 술어: 백업 파기 완료가 아니거나, 아직 그 상태로 내보내지지 않았거나, 보존 기한 전이다. */
    private static final String KEPT = "(t.status <> 'DESTROYED' OR NOT " + EXPORTED_AS_DESTROYED
            + " OR t.destroy_after > statement_timestamp())";

    private final JdbcClient jdbc;

    DeletionTombstoneRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 새 tombstone 하나. 요청 시각은 이 문장의 DB 시각(ms)이다. 같은 요청·같은 계정의 행이 있으면 0행이다. */
    int insert(String userDigest, String requestId) {
        return this.jdbc.sql("INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, "
                        + "origin) SELECT :request, :digest, t.at, t.at + interval '840 hours', 'DELETION' "
                        + "FROM (SELECT date_trunc('milliseconds', statement_timestamp()) AS at) t "
                        + "ON CONFLICT DO NOTHING")
                .param("request", requestId)
                .param("digest", userDigest)
                .update();
    }

    /** 사용자 삭제 요청의 시각(ms로 자른다)으로 새 tombstone 하나. 같은 요청·같은 계정의 행이 있으면 0행이다. */
    int insertRequested(String userDigest, String requestId, Instant requestedAt) {
        return this.jdbc.sql("INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, "
                        + "origin) SELECT :request, :digest, t.at, t.at + interval '840 hours', 'DELETION' "
                        + "FROM (SELECT date_trunc('milliseconds', CAST(:requested AS timestamptz)) AS at) t "
                        + "ON CONFLICT DO NOTHING")
                .param("request", requestId)
                .param("digest", userDigest)
                .param("requested", utc(requestedAt))
                .update();
    }

    /**
     * 외부 사본의 tombstone 하나를 시각·상태 그대로 옮긴다. 닫힌 집합의 닫힌 시각은 보존 기한 − 35일(요청 시각보다 앞서지 않게)이고, 초과·
     * 완료 시각은 사본에 없으므로 파기 기한·이 문장의 시각이다. 같은 요청·같은 계정의 행이 있으면 0행이다.
     */
    int insertCarried(Tombstone tombstone) {
        return this.jdbc.sql("INSERT INTO operations_deletion_tombstone (request_id, user_digest, requested_at, destroy_after, "
                        + "origin, status, closed_at, overdue_at, destroyed_at) "
                        + "SELECT :request, :digest, c.requested, c.destroy, 'RESTORE', "
                        + ":status, CASE WHEN :closed THEN greatest(c.requested, c.destroy - interval '840 hours') END, "
                        + "CASE WHEN :status = 'OVERDUE' THEN c.requested + interval '720 hours' END, "
                        + "CASE WHEN :status = 'DESTROYED' THEN statement_timestamp() END "
                        + "FROM (SELECT CAST(:requested AS timestamptz) AS requested, CAST(:destroy AS timestamptz) AS destroy) c "
                        + "ON CONFLICT DO NOTHING")
                .param("request", tombstone.requestId())
                .param("digest", tombstone.userDigest())
                .param("requested", utc(tombstone.requestedAt()))
                .param("destroy", utc(tombstone.destroyAfter()))
                .param("status", tombstone.status().name())
                .param("closed", tombstone.set() == GenerationSet.CLOSED)
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

    /**
     * 열린 집합을 이 문장의 DB 시각(ms)으로 닫고 보존 기한을 {@code max(요청, 닫힌 시각) + 840시간}으로 늘린다(줄이지 않는다). 이미 닫혔거나
     * 없으면 0행이다.
     */
    int close(String requestId) {
        return this.jdbc.sql("UPDATE operations_deletion_tombstone SET closed_at = greatest(requested_at, t.at), "
                        + "destroy_after = greatest(destroy_after, greatest(requested_at, t.at) + interval '840 hours') "
                        + "FROM (SELECT date_trunc('milliseconds', statement_timestamp()) AS at) t "
                        + "WHERE request_id = :request AND closed_at IS NULL")
                .param("request", requestId)
                .update();
    }

    /** {@link #close}와 같지만 닫힌 시각을 준다(삭제 실행 기록의 운영 완료 시각, ms로 자른다). */
    int closeAt(String requestId, Instant closedAt) {
        return this.jdbc.sql("UPDATE operations_deletion_tombstone SET closed_at = greatest(requested_at, t.at), "
                        + "destroy_after = greatest(destroy_after, greatest(requested_at, t.at) + interval '840 hours') "
                        + "FROM (SELECT date_trunc('milliseconds', CAST(:closed AS timestamptz)) AS at) t "
                        + "WHERE request_id = :request AND closed_at IS NULL")
                .param("closed", utc(closedAt))
                .param("request", requestId)
                .update();
    }

    /**
     * 백업 파기가 완료됐고, 그 상태로 성공한 내보내기에 실렸고, 보존 기한이 이 문장의 DB 시각 이전인 행을 지운다(영향 generation 행은 외래키로
     * 함께 지워진다). 완료가 아닌 행(열린 집합·파기 미완료·초과)은 기한이 지나도 지우지 않는다 — 그 계정이 든 백업이 남아 있을 수 있다.
     */
    int purgeExpired() {
        return this.jdbc.sql("DELETE FROM operations_deletion_tombstone t WHERE t.status = 'DESTROYED' "
                        + "AND t.destroy_after <= statement_timestamp() AND " + EXPORTED_AS_DESTROYED)
                .update();
    }

    /**
     * 외부 사본에 실을 행(요청 시각·요청 ID 순): 백업 파기가 완료가 아닌 행 전부(기한과 무관), 아직 그 상태로 성공한 내보내기에 실리지 않은
     * 완료 행 전부, 그리고 보존 기한이 이 문장의 DB 시각 + 24시간(`DeletionTombstoneApi.EXPORT_MARGIN`)보다 뒤인 완료 행. 사본 object가
     * 24시간 남으므로 확인된 완료 행은 보존 기한을 넘겨 외부에 남지 않는다. DB에 남아 있는 미완료 행이 사본에서 빠지는 일은 없다.
     */
    List<Tombstone> exportable() {
        return rows("(t.status <> 'DESTROYED' OR NOT " + EXPORTED_AS_DESTROYED
                + " OR t.destroy_after > statement_timestamp() + interval '24 hours')");
    }

    /** 남아 있는 행 전체(복원 적용의 대조): 완료가 아니거나 보존 기한이 지나지 않았다. */
    List<Tombstone> live() {
        return rows(KEPT);
    }

    /** 그 가운데 이 DB 계보의 삭제가 쓴 행(origin `DELETION`) — 복원의 계정 대조가 본다. */
    List<Tombstone> liveRecorded() {
        return rows(KEPT + " AND t.origin = 'DELETION'");
    }

    /**
     * 복원(Story 5.3): 같은 요청 ID·같은 digest의 행에 외부 사본의 더 진전된 상태를 병합한다(단조). 상태는 큰 쪽(PENDING &lt; OVERDUE &lt;
     * DESTROYED), 닫힌 시각·초과 시각·완료 시각은 없을 때만 채우고, 보존 기한은 큰 쪽이다. 사본의 닫힌 시각은 보존 기한 − 35일(요청 시각보다
     * 앞서지 않게)로 되짚는다.
     *
     * @return 바뀐 행 수(0 또는 1)
     */
    int mergeCarried(Tombstone tombstone) {
        return this.jdbc.sql("UPDATE operations_deletion_tombstone x SET "
                        + "status = CASE WHEN x.status = 'DESTROYED' OR c.status = 'DESTROYED' THEN 'DESTROYED' "
                        + "WHEN x.status = 'OVERDUE' OR c.status = 'OVERDUE' THEN 'OVERDUE' ELSE 'PENDING' END, "
                        + "closed_at = COALESCE(x.closed_at, c.closed), "
                        + "overdue_at = COALESCE(x.overdue_at, CASE WHEN c.status = 'OVERDUE' "
                        + "THEN x.requested_at + interval '720 hours' END), "
                        + "destroyed_at = COALESCE(x.destroyed_at, CASE WHEN c.status = 'DESTROYED' "
                        + "THEN statement_timestamp() END), "
                        + "destroy_after = greatest(x.destroy_after, c.destroy) "
                        + "FROM (SELECT CAST(:status AS text) AS status, CAST(:destroy AS timestamptz) AS destroy, "
                        + "CASE WHEN :closed THEN greatest(CAST(:requested AS timestamptz), "
                        + "CAST(:destroy AS timestamptz) - interval '840 hours') END AS closed) c "
                        + "WHERE x.request_id = :request AND x.user_digest = :digest AND (x.status <> c.status "
                        + "OR x.destroy_after < c.destroy OR (x.closed_at IS NULL AND c.closed IS NOT NULL))")
                .param("status", tombstone.status().name())
                .param("destroy", utc(tombstone.destroyAfter()))
                .param("closed", tombstone.set() == GenerationSet.CLOSED)
                .param("requested", utc(tombstone.requestedAt()))
                .param("request", tombstone.requestId())
                .param("digest", tombstone.userDigest())
                .update();
    }

    /** 그 요청 ID의 행의 digest. 없으면 빈 값이다. */
    Optional<String> digestOf(String requestId) {
        return this.jdbc.sql("SELECT user_digest FROM operations_deletion_tombstone WHERE request_id = :request")
                .param("request", requestId)
                .query(String.class)
                .optional();
    }

    // ------------------------------------------------------------------ Story 5.3 백업 파기 판정

    /** 이 문장의 DB 시각. 판정 한 번이 같은 "지금"을 쓴다. */
    Instant now() {
        OffsetDateTime now = this.jdbc.sql("SELECT statement_timestamp()").query(OffsetDateTime.class).single();
        return now.toInstant();
    }

    /** 이 읽기 트랜잭션의 문장 상한(2초, {@code set_config}의 트랜잭션 범위). 주기 측정이 DB를 오래 붙들지 않게. */
    void limitStatementTime() {
        this.jdbc.sql("SELECT set_config('statement_timeout', :timeout, true)")
                .param("timeout", "2s")
                .query(String.class)
                .single();
    }

    /**
     * 판정 트랜잭션 하나의 상한: 잠금 대기 2초·문장 5초({@code set_config}의 트랜잭션 범위). 판정이 계정 삭제 단계({@code close})를 오래
     * 기다리게 하지도, 그것을 오래 기다리지도 않게 한다 — 넘기면 그 tombstone은 다음 주기가 다시 판정한다.
     */
    void limitJudgeTime() {
        this.jdbc.sql("SELECT set_config('lock_timeout', :lock, true), set_config('statement_timeout', :statement, true)")
                .param("lock", "2s")
                .param("statement", "5s")
                .query((rs, row) -> 1)
                .single();
    }

    /** 집합이 열린 tombstone의 요청 ID(요청 시각 순). */
    List<String> openRequestIds() {
        return this.jdbc.sql("SELECT request_id FROM operations_deletion_tombstone WHERE closed_at IS NULL "
                        + "ORDER BY requested_at, request_id")
                .query(String.class)
                .list();
    }

    /**
     * 판정할 tombstone 하나.
     *
     * @param closedAt 집합이 닫힌 시각. 열려 있으면 {@code null}
     */
    record Candidate(String requestId, Instant requestedAt, @Nullable Instant closedAt, Instant backupDeadline,
            DestructionStatus status) {}

    /** 파기 완료가 아닌 tombstone의 요청 ID(요청 시각 순). 잠그지 않는다 — 판정은 하나씩 {@link #lockCandidate}로 잠근다. */
    List<String> undestroyed() {
        return this.jdbc.sql("SELECT request_id FROM operations_deletion_tombstone WHERE status <> 'DESTROYED' "
                        + "ORDER BY requested_at, request_id")
                .query(String.class)
                .list();
    }

    /**
     * 판정할 tombstone 하나를 잠그고 읽는다({@code FOR UPDATE SKIP LOCKED} — 계정 삭제 단계가 쥔 행은 건너뛰고 다음 주기가 본다). 이미
     * 파기 완료이거나 없거나 잠겨 있으면 비어 있다.
     */
    Optional<Candidate> lockCandidate(String requestId) {
        return this.jdbc.sql("SELECT request_id, requested_at, closed_at, " + BACKUP_DEADLINE + " AS backup_deadline, status "
                        + "FROM operations_deletion_tombstone WHERE request_id = :request AND status <> 'DESTROYED' "
                        + "FOR UPDATE SKIP LOCKED")
                .param("request", requestId)
                .query((rs, row) -> new Candidate(rs.getString("request_id"), instant(rs, "requested_at"),
                        nullableInstant(rs, "closed_at"), instant(rs, "backup_deadline"),
                        DestructionStatus.valueOf(rs.getString("status"))))
                .optional();
    }

    /** 요청 ID로 읽은 tombstone의 파기 상태(완료 조회, Story 5.4). */
    record DestructionRow(Instant requestedAt, Instant backupDeadline, DestructionStatus status,
            @Nullable Instant destroyedAt) {}

    /** 그 요청 ID의 tombstone의 파기 상태. 없으면 비어 있다. 읽기만 한다. */
    Optional<DestructionRow> destructionOf(String requestId) {
        return this.jdbc.sql("SELECT requested_at, " + BACKUP_DEADLINE + " AS backup_deadline, status, destroyed_at "
                        + "FROM operations_deletion_tombstone WHERE request_id = :request")
                .param("request", requestId)
                .query((rs, row) -> new DestructionRow(instant(rs, "requested_at"), instant(rs, "backup_deadline"),
                        DestructionStatus.valueOf(rs.getString("status")), nullableInstant(rs, "destroyed_at")))
                .optional();
    }

    /** 그 요청 ID들 가운데 백업 파기가 완료(DESTROYED)된 것. 읽기만 한다. */
    List<String> destroyedAmong(Collection<String> requestIds) {
        if (requestIds.isEmpty()) {
            return List.of();
        }
        return this.jdbc.sql("SELECT request_id FROM operations_deletion_tombstone WHERE status = 'DESTROYED' "
                        + "AND request_id IN (:requests) ORDER BY request_id")
                .param("requests", List.copyOf(requestIds))
                .query(String.class)
                .list();
    }

    /** 그 요청 ID의 행의 상태. 없으면 비어 있다. */
    Optional<DestructionStatus> statusOf(String requestId) {
        return this.jdbc.sql("SELECT status FROM operations_deletion_tombstone WHERE request_id = :request")
                .param("request", requestId)
                .query(String.class)
                .optional()
                .map(DestructionStatus::valueOf);
    }

    /**
     * 그 tombstone의 영향 generation 가운데 파기 확인이 빈 저장소를 이 문장의 DB 시각으로 채운다 — 백업 파기 완료(DESTROYED)인 행은 모든
     * generation이 두 저장소에서 파기됐다는 뜻이다(복원이 그 상태를 옮기거나 병합했을 때 그 사실을 행에 맞춘다).
     */
    int markGenerationsPurged(String requestId) {
        return this.jdbc.sql("UPDATE operations_deletion_backup_generation SET "
                        + "oci_purged_at = COALESCE(oci_purged_at, statement_timestamp()), "
                        + "offsite_purged_at = COALESCE(offsite_purged_at, statement_timestamp()) "
                        + "WHERE request_id = :request AND (oci_purged_at IS NULL OR offsite_purged_at IS NULL)")
                .param("request", requestId)
                .update();
    }

    /**
     * 그 tombstone의 영향 generation 하나를 더하거나 파기 확인 시각을 채운다(이미 채워진 시각은 바꾸지 않는다). 행을 빼지 않는다. 채울 것이
     * 없으면 행을 다시 쓰지 않는다(주기 판정이 같은 값으로 tuple을 쌓지 않게).
     */
    void addGeneration(String requestId, String generationId, @Nullable Instant ociPurgedAt,
            @Nullable Instant offsitePurgedAt) {
        this.jdbc.sql("INSERT INTO operations_deletion_backup_generation (request_id, generation_id, oci_purged_at, "
                        + "offsite_purged_at) VALUES (:request, :generation, :oci, :offsite) "
                        + "ON CONFLICT (request_id, generation_id) DO UPDATE SET "
                        + "oci_purged_at = COALESCE(operations_deletion_backup_generation.oci_purged_at, EXCLUDED.oci_purged_at), "
                        + "offsite_purged_at = COALESCE(operations_deletion_backup_generation.offsite_purged_at, "
                        + "EXCLUDED.offsite_purged_at) WHERE (operations_deletion_backup_generation.oci_purged_at IS NULL "
                        + "AND EXCLUDED.oci_purged_at IS NOT NULL) OR (operations_deletion_backup_generation.offsite_purged_at "
                        + "IS NULL AND EXCLUDED.offsite_purged_at IS NOT NULL)")
                .param("request", requestId)
                .param("generation", generationId)
                .param("oci", ociPurgedAt == null ? null : utc(ociPurgedAt))
                .param("offsite", offsitePurgedAt == null ? null : utc(offsitePurgedAt))
                .update();
    }

    /**
     * 외부 사본에서 옮긴 영향 generation 하나. 사본이 파기를 확인한 저장소는 이 문장의 DB 시각을 확인 표시로 넣는다(실제 파기 시각은 사본에
     * 없다 — 복원 DB의 백업 기록에는 그 뒤의 파기가 없으므로 이 표시가 없으면 영원히 완료가 되지 못한다). 이미 있으면 빈 확인만 채운다.
     */
    void carryGeneration(String requestId, String generationId, boolean ociPurged, boolean offsitePurged) {
        addGeneration(requestId, generationId, ociPurged ? now() : null, offsitePurged ? now() : null);
    }

    /** 그 tombstone의 영향 generation 가운데 두 저장소 파기가 다 확인되지 않은 수. */
    int unpurgedGenerations(String requestId) {
        Integer count = this.jdbc.sql("SELECT count(*) FROM operations_deletion_backup_generation "
                        + "WHERE request_id = :request AND (oci_purged_at IS NULL OR offsite_purged_at IS NULL)")
                .param("request", requestId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    /** 닫힌 집합의 tombstone을 백업 파기 완료로 바꾼다(완료 시각은 이 문장의 DB 시각). 이미 완료이거나 열린 집합이면 0행이다. */
    int markDestroyed(String requestId) {
        return this.jdbc.sql("UPDATE operations_deletion_tombstone SET status = 'DESTROYED', "
                        + "destroyed_at = statement_timestamp() "
                        + "WHERE request_id = :request AND status <> 'DESTROYED' AND closed_at IS NOT NULL")
                .param("request", requestId)
                .update();
    }

    /** 파기 기한이 지난 미완료 tombstone을 초과로 바꾼다(한 번 — PENDING에서만). 아니면 0행이다. */
    int markOverdue(String requestId) {
        return this.jdbc.sql("UPDATE operations_deletion_tombstone SET status = 'OVERDUE', "
                        + "overdue_at = statement_timestamp() "
                        + "WHERE request_id = :request AND status = 'PENDING' AND " + BACKUP_DEADLINE + " <= statement_timestamp()")
                .param("request", requestId)
                .update();
    }

    /** 파기 완료가 아니고 파기 기한까지 {@code dueSoonHours}시간 안이며 아직 기한 전인 tombstone 수. */
    long dueSoon(long dueSoonHours) {
        Long count = this.jdbc.sql("SELECT count(*) FROM operations_deletion_tombstone WHERE status <> 'DESTROYED' "
                        + "AND " + BACKUP_DEADLINE + " > statement_timestamp() "
                        + "AND " + BACKUP_DEADLINE + " <= statement_timestamp() "
                        + "+ CAST(:hours AS double precision) * interval '1 hour'")
                .param("hours", dueSoonHours)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    /** 파기 완료가 아니고 파기 기한이 지난 tombstone 수(초과로 바뀌기 전의 행 포함). */
    long overdue() {
        Long count = this.jdbc.sql("SELECT count(*) FROM operations_deletion_tombstone WHERE status <> 'DESTROYED' "
                        + "AND " + BACKUP_DEADLINE + " <= statement_timestamp()")
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    // ------------------------------------------------------------------ Story 5.3 내보내기 기록

    /** 내보내기 하나를 시작한다(시각은 이 문장의 DB 시각). 그 번호. */
    long prepareExport() {
        Long id = this.jdbc.sql("INSERT INTO operations_tombstone_export DEFAULT VALUES RETURNING sequence")
                .query(Long.class)
                .single();
        if (id == null) {
            throw new IllegalStateException("내보내기 번호를 받지 못했다.");
        }
        return id;
    }

    /** 지금 DESTROYED인데 아직 번호가 없는 tombstone에 이 내보내기 번호를 붙인다(처음 DESTROYED로 실리는 내보내기). */
    int markDestroyedExport(long exportId) {
        return this.jdbc.sql("UPDATE operations_deletion_tombstone SET destroyed_export = :export "
                        + "WHERE status = 'DESTROYED' AND destroyed_export IS NULL")
                .param("export", exportId)
                .update();
    }

    /** 그 내보내기를 성공으로 채운다(시각은 이 문장의 DB 시각). 아직 성공이 아니었으면 1행이다. */
    int confirmExport(long exportId, int tombstones) {
        return this.jdbc.sql("UPDATE operations_tombstone_export SET exported_at = statement_timestamp(), tombstones = :count "
                        + "WHERE sequence = :export AND exported_at IS NULL")
                .param("count", tombstones)
                .param("export", exportId)
                .update();
    }

    /** 그 내보내기가 이미 성공으로 채워졌는가(같은 기록의 재전송). */
    boolean exportConfirmed(long exportId) {
        Boolean confirmed = this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM operations_tombstone_export WHERE sequence = :export "
                        + "AND exported_at IS NOT NULL)")
                .param("export", exportId)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(confirmed);
    }

    /** 35일보다 오래전에 시작한 내보내기 기록을 지운다. */
    int purgeExports() {
        return this.jdbc.sql("DELETE FROM operations_tombstone_export "
                        + "WHERE prepared_at < statement_timestamp() - interval '840 hours'")
                .update();
    }

    /** 마지막 성공 기록의 DB 시각(epoch 초, 소수 포함). 없으면 비어 있다. */
    Optional<Double> lastExportEpochSeconds() {
        List<@Nullable Double> values = this.jdbc.sql(
                        "SELECT CAST(extract(epoch FROM max(exported_at)) AS double precision) FROM operations_tombstone_export")
                .query((rs, row) -> {
                    double value = rs.getDouble(1);
                    return rs.wasNull() ? null : value;
                })
                .list();
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }

    private List<Tombstone> rows(String predicate) {
        return this.jdbc.sql(TOMBSTONE_COLUMNS + "WHERE " + predicate + " ORDER BY t.requested_at, t.request_id")
                .query((rs, row) -> {
                    String generations = rs.getString("generations");
                    List<AffectedGeneration> affected = generations == null || generations.isEmpty() ? List.of()
                            : Arrays.stream(generations.split(",")).map(AffectedGeneration::parse).toList();
                    return new Tombstone(rs.getString("user_digest"), rs.getString("request_id"),
                            instant(rs, "requested_at"), instant(rs, "destroy_after"),
                            DestructionStatus.valueOf(rs.getString("status")),
                            rs.getBoolean("closed") ? GenerationSet.CLOSED : GenerationSet.OPEN, affected);
                })
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
