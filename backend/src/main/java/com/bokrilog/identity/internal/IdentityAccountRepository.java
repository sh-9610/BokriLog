package com.bokrilog.identity.internal;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * identity 소유 테이블에 대한 유일한 writer.
 *
 * <p>SQL이 그대로 보이는 것이 이 클래스의 목적이다. 이 모듈이 하는 일은 상태 행을 잠그고, 읽고,
 * 술어가 붙은 UPDATE를 던지는 것뿐이라 ORM이 끼면 "커밋 직전 재검사"가 어느 시점에 실제로
 * 실행되는지가 흐려진다.
 *
 * <p>메서드는 트랜잭션을 열지 않는다. 잠금과 재검사가 의미를 가지려면 호출하는 쪽의 트랜잭션
 * 하나 안에서 이어져야 한다.
 */
@Repository
public class IdentityAccountRepository {

    private final JdbcClient jdbc;

    IdentityAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 생명주기 상태를 잠그고 읽는다.
     *
     * <p>{@code FOR UPDATE}가 이 트랜잭션이 끝날 때까지 그 행을 붙든다. 같은 계정을 건드리는 다른
     * 트랜잭션(삭제 전환 포함)은 여기서 직렬화된다.
     *
     * @return 행이 없으면 빈 값
     */
    public Optional<AccountState> lockLifecycleForUpdate(UUID userId) {
        return jdbc.sql(
                        "SELECT state FROM identity_account_lifecycle WHERE user_id = :userId FOR UPDATE")
                .param("userId", userId)
                .query(String.class)
                .optional()
                // 아는 값이 아니면 빈 값이다. 호출자에게는 행이 없는 것과 같고 모두 거부로 끝난다.
                .flatMap(AccountState::parse);
    }

    /**
     * 생명주기 상태를 잠그지 않고 읽는다. 판정처럼 아무것도 바꾸지 않는 경로에만 쓴다.
     *
     * @return 행이 없으면 빈 값
     */
    public Optional<AccountState> findLifecycle(UUID userId) {
        return jdbc.sql("SELECT state FROM identity_account_lifecycle WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional()
                // 아는 값이 아니면 빈 값이다. 호출자에게는 행이 없는 것과 같고 모두 거부로 끝난다.
                .flatMap(AccountState::parse);
    }

    /** 생명주기 guard 행을 {@code ACTIVE}로 만든다. */
    public void insertLifecycle(UUID userId) {
        jdbc.sql(
                        "INSERT INTO identity_account_lifecycle (user_id, state) "
                                + "VALUES (:userId, 'ACTIVE')")
                .param("userId", userId)
                .update();
    }

    /** 계정 payload 행을 만든다. */
    public void insertAccountPayload(UUID userId) {
        jdbc.sql("INSERT INTO identity_account (user_id) VALUES (:userId)")
                .param("userId", userId)
                .update();
    }

    /**
     * 계정이 {@code ACTIVE}일 때만 인증 매핑을 만든다. 이미 있는 식별자면 아무것도 하지 않는다.
     *
     * <p>이 한 문장이 판정과 쓰기를 함께 담는다. 그래서 거부된 호출은 아무것도 쓰지 않을 뿐 아니라
     * 데이터베이스 오류도 일으키지 않는다. 그것이 이 모양을 고른 이유다.
     *
     * <ul>
     *   <li>{@code SELECT … WHERE state = 'ACTIVE'} -- 상태 술어가 INSERT 안에 있다. 삭제가 시작된
     *       계정에는 원자적으로 0행이 되며, 호출자가 값을 읽고 판단하는 창이 존재하지 않는다.
     *   <li>{@code ON CONFLICT DO NOTHING} -- {@code (issuer, subject)}·{@code (user_id, provider)}
     *       유일 인덱스 위반을 예외가 아니라 0행으로 만든다. 예외로 두면 PostgreSQL이 그 트랜잭션을
     *       통째로 abort하고, 그 트랜잭션이 호출자의 것이면 호출자의 작업까지 사라진다.
     * </ul>
     *
     * <p>유일 인덱스는 여전히 최종 방어다. 여기서 바뀐 것은 위반을 알리는 방법뿐이다.
     *
     * @return 1이면 매핑이 생겼고, 0이면 상태가 {@code ACTIVE}가 아니거나 이미 그 식별자가 있다.
     *     어느 쪽인지는 호출자에게 구분되지 않으며 그것이 거부 계약이다.
     */
    public int insertSocialLinkIfActive(UUID userId, String provider, String issuer, String subject) {
        return jdbc.sql(
                        "INSERT INTO identity_social_link (link_id, user_id, provider, issuer, subject) "
                                + "SELECT :linkId, :userId, :provider, :issuer, :subject "
                                + "FROM identity_account_lifecycle "
                                + "WHERE user_id = :userId AND state = 'ACTIVE' "
                                + "ON CONFLICT DO NOTHING")
                .param("linkId", UUID.randomUUID())
                .param("userId", userId)
                .param("provider", provider)
                .param("issuer", issuer)
                .param("subject", subject)
                .update();
    }

    /**
     * 계정이 {@code ACTIVE}이고 <b>남은 수단이 둘 이상일 때만</b> 그 인증 매핑을 지운다.
     *
     * <p>{@link #insertSocialLinkIfActive}와 같은 모양이다. 판정과 쓰기가 한 문장에 있으므로 거부된
     * 호출은 아무것도 지우지 않고 데이터베이스 오류도 일으키지 않는다.
     *
     * <ul>
     *   <li>{@code EXISTS (… state = 'ACTIVE')} -- 상태 술어가 DELETE 안에 있다. 삭제가 시작된
     *       계정에는 원자적으로 0행이 된다.
     *   <li>{@code (SELECT count(*) …) > 1} -- <b>마지막 수단 보호</b>다. 이 술어가 문장 안에 있어야
     *       하는 이유는 밖에 두면 "세기"와 "지우기" 사이에 창이 생기기 때문이다. 같은 계정의 동시
     *       해제 둘이 그 창에서 만나면 둘 다 "지금은 2개니까 괜찮다"를 보고 통과해 계정이 잠긴다.
     *       호출자가 잡는 생명주기 행 잠금이 그 동시 요청을 직렬화하고, 이 술어가 두 번째 요청을
     *       0행으로 끝낸다. <b>둘 다 필요하다</b> — 잠금만으로는 서로 다른 트랜잭션의 스냅숏이
     *       갈리고, 술어만으로는 잠금 없는 동시 실행에서 둘 다 통과한다.
     *   <li>{@code provider} 일치 -- 연결되지 않은 제공자는 대상 행이 없어 0행이다. "마지막
     *       수단이라 거부됐다"와 "그 제공자가 애초에 없었다"가 호출자에게 같은 값으로 보인다.
     * </ul>
     *
     * <p>세는 시점이 문장의 스냅숏이다. PostgreSQL의 {@code DELETE}는 자기 서브쿼리를 삭제 이전
     * 스냅숏에서 읽으므로 {@code > 1}은 "지우기 전에 둘 이상"이고, 그것이 곧 "지운 뒤 하나 이상"이다.
     *
     * @return 1이면 매핑이 사라졌고, 0이면 상태가 {@code ACTIVE}가 아니거나 그 제공자가 연결돼 있지
     *     않거나 마지막 수단이었다. 어느 쪽인지는 호출자에게 구분되지 않으며 그것이 거부 계약이다.
     */
    public int deleteSocialLinkIfNotLast(UUID userId, String provider) {
        return jdbc.sql(
                        "DELETE FROM identity_social_link AS target "
                                + "WHERE target.user_id = :userId AND target.provider = :provider "
                                + "AND EXISTS ("
                                + "SELECT 1 FROM identity_account_lifecycle AS lifecycle "
                                + "WHERE lifecycle.user_id = :userId AND lifecycle.state = 'ACTIVE') "
                                + "AND (SELECT count(*) FROM identity_social_link AS remaining "
                                + "WHERE remaining.user_id = :userId) > 1")
                .param("userId", userId)
                .param("provider", provider)
                .update();
    }

    /**
     * 활성 계정에 연결된 인증 매핑을 찾는다.
     *
     * <p>상태 술어가 질의 안에 있다. 삭제가 시작된 계정은 매핑 행이 아직 남아 있어도 여기서
     * 0행이 되고, 삭제가 끝난 계정은 매핑 자체가 없어 역시 0행이다. 두 경우와 "그 식별자로
     * 만들어진 계정이 없다"가 호출자에게 같은 빈 값으로 보이는 것이 이 계약이다.
     *
     * <p>잠그지 않는다. 아무것도 바꾸지 않는 조회 경로이고, 뒤이어 세션을 만드는 쪽이 자기
     * 트랜잭션에서 다시 판정한다.
     *
     * @return 행이 없으면 빈 값
     */
    public Optional<UUID> findActiveUserIdBySocialIdentity(String issuer, String subject) {
        return jdbc.sql(
                        "SELECT link.user_id FROM identity_social_link AS link "
                                + "JOIN identity_account_lifecycle AS lifecycle "
                                + "ON lifecycle.user_id = link.user_id "
                                + "WHERE link.issuer = :issuer AND link.subject = :subject "
                                + "AND lifecycle.state = 'ACTIVE'")
                .param("issuer", issuer)
                .param("subject", subject)
                .query(UUID.class)
                .optional();
    }

    /**
     * 활성 계정에 연결된 제공자 이름.
     *
     * <p>상태 술어가 질의 안에 있다({@link #findActiveUserIdBySocialIdentity}와 같은 근거). 삭제가
     * 시작된 계정은 매핑 행이 아직 남아 있어도 0행이 되고, 삭제가 끝난 계정은 매핑 자체가 없어
     * 역시 0행이다 — 두 경우와 "그런 계정이 없다"가 호출자에게 같은 빈 목록으로 보인다.
     *
     * <p>{@code provider} 하나만 고른다. {@code issuer}·{@code subject}·{@code linked_at}을 함께
     * 고르면 그 값들이 공개 API의 반환에 실릴 자리가 생긴다. 고르지 않으므로 실을 수도 없다.
     *
     * <p>잠그지 않는다. 아무것도 바꾸지 않는 조회 경로이고, 실제로 매핑을 더하는 쪽이 자기
     * 트랜잭션에서 유일 인덱스로 다시 판정한다({@link #insertSocialLinkIfActive}).
     *
     * @return 연결된 제공자 이름. 중복은 스키마가 막는다({@code (user_id, provider)} 유일)
     */
    public List<String> findLinkedProvidersOfActive(UUID userId) {
        return jdbc.sql(
                        "SELECT link.provider FROM identity_social_link AS link "
                                + "JOIN identity_account_lifecycle AS lifecycle "
                                + "ON lifecycle.user_id = link.user_id "
                                + "WHERE link.user_id = :userId AND lifecycle.state = 'ACTIVE'")
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    /**
     * 삭제 진행 상태로 전진시킨다.
     *
     * <p>술어 {@code AND state = 'ACTIVE'}가 단조 전진을 강제한다. 0행이면 전이가 일어나지 않은
     * 것이고, 이미 목표 상태거나 그 너머라면 그것이 곧 멱등이다.
     *
     * @return 바뀐 행 수
     */
    public int markDeletionInProgress(UUID userId) {
        return jdbc.sql(
                        "UPDATE identity_account_lifecycle "
                                + "SET state = 'DELETION_IN_PROGRESS', state_changed_at = now() "
                                + "WHERE user_id = :userId AND state = 'ACTIVE'")
                .param("userId", userId)
                .update();
    }

    /**
     * 삭제 완료 상태로 전진시킨다. 술어가 {@code DELETION_IN_PROGRESS}를 요구하므로 전환을 건너뛴
     * 삭제는 일어나지 않는다.
     *
     * @return 바뀐 행 수
     */
    public int markDeleted(UUID userId) {
        return jdbc.sql(
                        "UPDATE identity_account_lifecycle "
                                + "SET state = 'DELETED', state_changed_at = now() "
                                + "WHERE user_id = :userId AND state = 'DELETION_IN_PROGRESS'")
                .param("userId", userId)
                .update();
    }

    /**
     * 계정의 인증 매핑을 모두 지운다.
     *
     * @return 지운 행 수. 0행은 실패가 아니다.
     */
    public int deleteSocialLinks(UUID userId) {
        return jdbc.sql("DELETE FROM identity_social_link WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    /**
     * 계정의 인증 세션을 모두 지운다.
     *
     * <p>세션 저장소는 Spring Session JDBC의 {@code spring_session}이다. 그 테이블에 행을 만들고
     * 갱신하는 것은 Spring Session뿐이고, 이 메서드는 삭제 경로만 담당한다. 두 역할이 갈려 있어
     * 세션의 수명 관리를 이 모듈이 가져오지 않는다.
     *
     * <p>{@code principal_name}이 내부 {@code user_id} 문자열이라는 것은 인증 표면의 결정이다
     * ({@code AuthFlowHandlers}가 인증 주체를 그 값 하나로 바꿔 저장한다). 두 곳이 갈라지면 이
     * DELETE가 조용히 0행이 되므로, 실제 로그인이 만든 세션을 지우는지 {@code AuthDeletionPathTest}가
     * 확인한다. 그 시험이 이 한 쌍을 붙잡는 유일한 장치다.
     *
     * <p>속성 행은 {@code ON DELETE CASCADE}로 함께 사라진다({@code V2__spring_session.sql}).
     *
     * <p>지운 행 수를 돌려주지 않는다. 0행은 실패가 아니고(로그인하지 않은 계정에는 세션이 없다)
     * 호출자가 그 수로 판단할 것도 없으므로, 값을 돌려주면 아무도 읽지 않는 반환값이 하나 생긴다.
     * 이 DELETE가 실제로 로그인 세션에 맞는지는 {@code AuthDeletionPathTest}가 확인한다.
     */
    public void deleteSessionsOf(UUID userId) {
        jdbc.sql("DELETE FROM spring_session WHERE principal_name = :principalName")
                .param("principalName", userId.toString())
                .update();
    }

    /**
     * 계정 payload 행을 지운다. 생명주기 guard 행은 건드리지 않는다.
     *
     * @return 지운 행 수. 0행은 실패가 아니다.
     */
    public int deleteAccountPayload(UUID userId) {
        return jdbc.sql("DELETE FROM identity_account WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    // ------------------------------------------------------------------ Story 5.1 삭제 요청

    /** 삭제 요청 행 하나(SQL 결과). */
    public record StoredDeletionRequest(String requestId, OffsetDateTime requestedAt, String policyVersion,
            String noticeHash, String idempotencyKeyDigest) {}

    /**
     * 그 계정의 삭제 요청. 잠그지 않는다 — 판정이 필요한 쓰기 경로는 먼저 생명주기 행을 잠근 뒤 같은 트랜잭션에서 부른다.
     *
     * @return 없으면 빈 값
     */
    public Optional<StoredDeletionRequest> findDeletionRequest(UUID userId) {
        return jdbc.sql("SELECT request_id, requested_at, policy_version, notice_hash, idempotency_key_digest "
                        + "FROM identity_deletion_request WHERE user_id = :userId")
                .param("userId", userId)
                .query((row, index) -> new StoredDeletionRequest(row.getString("request_id"),
                        row.getObject("requested_at", OffsetDateTime.class), row.getString("policy_version"),
                        row.getString("notice_hash"), row.getString("idempotency_key_digest")))
                .optional();
    }

    /**
     * 삭제 요청 행을 만든다. 시각은 DB가 정한다(문장 시각). 사용자당 유일 제약이 동시 요청의 최종 방어다 — 호출자는 생명주기 행을
     * 잠근 뒤 {@link #findDeletionRequest}로 먼저 본다.
     *
     * @return 만든 행
     */
    public StoredDeletionRequest insertDeletionRequest(String requestId, UUID userId, String policyVersion,
            String noticeHash, String idempotencyKeyDigest) {
        return jdbc.sql("INSERT INTO identity_deletion_request "
                        + "(request_id, user_id, policy_version, notice_hash, idempotency_key_digest) "
                        + "VALUES (:requestId, :userId, :policyVersion, :noticeHash, :keyDigest) "
                        + "RETURNING request_id, requested_at, policy_version, notice_hash, idempotency_key_digest")
                .param("requestId", requestId)
                .param("userId", userId)
                .param("policyVersion", policyVersion)
                .param("noticeHash", noticeHash)
                .param("keyDigest", idempotencyKeyDigest)
                .query((row, index) -> new StoredDeletionRequest(row.getString("request_id"),
                        row.getObject("requested_at", OffsetDateTime.class), row.getString("policy_version"),
                        row.getString("notice_hash"), row.getString("idempotency_key_digest")))
                .single();
    }

    /**
     * 그 계정의 삭제 요청 행을 지운다. 삭제 완료(payload 삭제)만 부른다 — 요청 행은 payload와 같은 운명이다.
     *
     * @return 지운 행 수. 0행은 실패가 아니다.
     */
    public int deleteDeletionRequest(UUID userId) {
        return jdbc.sql("DELETE FROM identity_deletion_request WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    /** 생명주기 행이 있는 모든 계정(Story 3.10 복원 적용). */
    public List<UUID> findAllAccounts() {
        return jdbc.sql("SELECT user_id FROM identity_account_lifecycle ORDER BY user_id").query(UUID.class).list();
    }

    /** 그 상태의 계정(Story 3.10 복원 검증). */
    public List<UUID> findAccountsIn(AccountState state) {
        return jdbc.sql("SELECT user_id FROM identity_account_lifecycle WHERE state = :state ORDER BY user_id")
                .param("state", state.name())
                .query(UUID.class)
                .list();
    }

    /**
     * 인증 세션을 모두 지운다(Story 3.10 복원 적용 전용). 속성 행은 {@code ON DELETE CASCADE}로 함께 사라진다. 세션 저장소 빈이 없는
     * 일회성 역할에서 부르므로 SQL이 여기 있다({@link #deleteSessionsOf}와 같은 근거).
     *
     * @return 지운 세션 수
     */
    public int deleteAllSessions() {
        return jdbc.sql("DELETE FROM spring_session").update();
    }

    /** 그 계정의 소셜 로그인 연결 행 수(Story 5.2 {@code CREDENTIALS} 단계의 잔여 검증). */
    public long countSocialLinksOf(UUID userId) {
        Long count = jdbc.sql("SELECT count(*) FROM identity_social_link WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    /**
     * 그 계정에 남은 payload·인증 매핑·세션·삭제 요청 행 수(Story 3.10 복원 검증, 삭제 요청은 Story 5.1). 생명주기 guard 행과 삭제
     * 실행·단계 상태(Story 5.2 — 삭제 기록이다)는 세지 않는다.
     */
    public long countRemainingRowsOf(UUID userId) {
        Long count = jdbc.sql("SELECT (SELECT count(*) FROM identity_account WHERE user_id = :userId) "
                        + "+ (SELECT count(*) FROM identity_social_link WHERE user_id = :userId) "
                        + "+ (SELECT count(*) FROM identity_deletion_request WHERE user_id = :userId) "
                        + "+ (SELECT count(*) FROM spring_session WHERE principal_name = :principalName)")
                .param("userId", userId)
                .param("principalName", userId.toString())
                .query(Long.class)
                .single();
        return count == null ? 0L : count;
    }

    // ------------------------------------------------------------------ Story 5.2 삭제 실행·단계 상태

    /** 실행 행 하나(SQL 결과). */
    public record StoredRun(String status, @org.jspecify.annotations.Nullable OffsetDateTime operationalErasedAt,
            long generation, @org.jspecify.annotations.Nullable String retryKeyDigest) {}

    /** 단계 행 하나(SQL 결과). */
    public record StoredStep(String step, String status, @org.jspecify.annotations.Nullable Long erasedRows,
            @org.jspecify.annotations.Nullable String failureCode, int attempts) {}

    /** 집은 실행(SQL 결과). */
    public record ClaimedRun(UUID userId, long generation) {}

    /**
     * 기존 실행 하나를 집는다 — {@code QUEUED}이거나 lease가 끝난 {@code RUNNING}이고 계정이 {@code DELETION_IN_PROGRESS}이며 삭제 요청
     * 행이 있는 것(요청 없는 실행은 집지 않는다). 다른
     * 트랜잭션이 잡은 행은 건너뛴다. 세대를 올리고 lease를 새로 건다(문장 시각 + {@code leaseMillis}). 요청 ID가 비었으면(Story 5.3
     * 전에 만든 실행) 요청 행에서 채운다.
     */
    public Optional<ClaimedRun> claimExistingRun(long leaseMillis) {
        return jdbc.sql("UPDATE identity_deletion_run SET status = 'RUNNING', "
                        + "fencing_generation = fencing_generation + 1, "
                        + "request_id = COALESCE(request_id, (SELECT request.request_id FROM identity_deletion_request "
                        + "AS request WHERE request.user_id = identity_deletion_run.user_id)), "
                        + "lease_until = statement_timestamp() + CAST(:leaseMillis AS double precision) * interval '1 millisecond', "
                        + "heartbeat_at = statement_timestamp() "
                        + "WHERE user_id = (SELECT run.user_id FROM identity_deletion_run AS run "
                        + "JOIN identity_account_lifecycle AS lifecycle ON lifecycle.user_id = run.user_id "
                        + "WHERE lifecycle.state = 'DELETION_IN_PROGRESS' AND EXISTS (SELECT 1 FROM identity_deletion_request "
                        + "AS request WHERE request.user_id = run.user_id) AND (run.status = 'QUEUED' "
                        + "OR (run.status = 'RUNNING' AND run.lease_until <= statement_timestamp())) "
                        + "ORDER BY run.created_at, run.user_id LIMIT 1 FOR UPDATE OF run SKIP LOCKED) "
                        + "RETURNING user_id, fencing_generation")
                .param("leaseMillis", leaseMillis)
                .query((row, index) -> new ClaimedRun(row.getObject("user_id", UUID.class),
                        row.getLong("fencing_generation")))
                .optional();
    }

    /**
     * 실행이 없는 삭제 요청 계정 하나의 실행 행을 만든다(첫 claim, 세대 1). 요청 시각 순이다. 같은 계정을 다른 트랜잭션이 먼저 만들면 0행이다.
     * 실행 행은 요청 ID를 든다(Story 5.3 — 요청 행은 운영 완료 때 지워지므로 백업 파기 판정이 요청 ID ↔ 운영 완료 시각을 이 행으로 잇는다).
     */
    public Optional<ClaimedRun> claimNewRun(long leaseMillis) {
        return jdbc.sql("INSERT INTO identity_deletion_run (user_id, status, fencing_generation, lease_until, heartbeat_at, "
                        + "request_id) "
                        + "SELECT candidate.user_id, 'RUNNING', 1, "
                        + "statement_timestamp() + CAST(:leaseMillis AS double precision) * interval '1 millisecond', statement_timestamp(), "
                        + "candidate.request_id "
                        + "FROM (SELECT request.user_id, request.request_id FROM identity_deletion_request AS request "
                        + "JOIN identity_account_lifecycle AS lifecycle ON lifecycle.user_id = request.user_id "
                        + "WHERE lifecycle.state = 'DELETION_IN_PROGRESS' AND NOT EXISTS ("
                        + "SELECT 1 FROM identity_deletion_run AS run WHERE run.user_id = request.user_id) "
                        + "ORDER BY request.requested_at, request.user_id LIMIT 1 "
                        + "FOR UPDATE OF request SKIP LOCKED) AS candidate "
                        + "ON CONFLICT (user_id) DO NOTHING RETURNING user_id, fencing_generation")
                .param("leaseMillis", leaseMillis)
                .query((row, index) -> new ClaimedRun(row.getObject("user_id", UUID.class),
                        row.getLong("fencing_generation")))
                .optional();
    }

    /** 새 실행의 단계 행 하나({@code PENDING}). */
    public void insertStep(UUID userId, String step, int position) {
        jdbc.sql("INSERT INTO identity_deletion_step (user_id, step, position) VALUES (:userId, :step, :position)")
                .param("userId", userId)
                .param("step", step)
                .param("position", position)
                .update();
    }

    /**
     * 이 트랜잭션의 잠금 대기 상한을 건다({@code SET LOCAL} — 트랜잭션이 끝나면 풀린다). heartbeat가 단계 트랜잭션이 쥔 실행 행에서 오래
     * 기다리지 않게 한다 — 넘기면 그 갱신은 실패하고 다음 tick이 다시 민다.
     */
    public void limitLockWait(long millis) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", millis + "ms")
                .query(String.class)
                .single();
    }

    /** lease를 민다. 그 세대의 실행 중 행이 아니면 0행이다. */
    public int renewRun(UUID userId, long generation, long leaseMillis) {
        return jdbc.sql("UPDATE identity_deletion_run "
                        + "SET lease_until = statement_timestamp() + CAST(:leaseMillis AS double precision) * interval '1 millisecond', "
                        + "heartbeat_at = statement_timestamp() "
                        + "WHERE user_id = :userId AND fencing_generation = :generation AND status = 'RUNNING'")
                .param("leaseMillis", leaseMillis)
                .param("userId", userId)
                .param("generation", generation)
                .update();
    }

    /**
     * 실행 행을 그 세대·{@code RUNNING}일 때만 잠그고 lease를 새로 민다(fencing + 갱신, 한 문장). UPDATE가 행 잠금을 호출자 트랜잭션이
     * 끝날 때까지 붙든다. lease 만료 여부는 보지 않는다 — 긴 단계 동안 heartbeat가 이 잠금에 막혀 lease가 지났더라도, 그 사이 다른 claim이
     * 세대를 올리지 않았다면 이 Worker가 여전히 주인이다. 정확성은 세대가 지킨다.
     *
     * @return 이 세대가 아직 실행을 쥐고 있는가
     */
    public boolean lockHeldRun(UUID userId, long generation, long leaseMillis) {
        return jdbc.sql("UPDATE identity_deletion_run "
                        + "SET lease_until = statement_timestamp() + CAST(:leaseMillis AS double precision) * interval '1 millisecond', "
                        + "heartbeat_at = statement_timestamp() WHERE user_id = :userId "
                        + "AND fencing_generation = :generation AND status = 'RUNNING' RETURNING 1")
                .param("leaseMillis", leaseMillis)
                .param("userId", userId)
                .param("generation", generation)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** 실행 행이 있으면 잠근다(단일 모드 삭제가 Worker 단계와 같은 순서 — 실행 행 → 생명주기 — 로 잠그게). */
    public void lockRunIfPresent(UUID userId) {
        jdbc.sql("SELECT 1 FROM identity_deletion_run WHERE user_id = :userId FOR UPDATE")
                .param("userId", userId)
                .query(Integer.class)
                .optional();
    }

    /** 단계의 시도 횟수만 하나 올린다(상태는 그대로 — 마지막 단계가 앞 단계의 잔여를 보고 멈춘 경우). */
    public int countStepAttempt(UUID userId, String step) {
        return jdbc.sql("UPDATE identity_deletion_step SET attempts = attempts + 1, finished_at = statement_timestamp() "
                        + "WHERE user_id = :userId AND step = :step")
                .param("userId", userId)
                .param("step", step)
                .update();
    }

    /** 실행 행을 잠그고 읽는다(다시 시도 접수). */
    public Optional<StoredRun> lockRun(UUID userId) {
        return jdbc.sql("SELECT status, operational_erased_at, fencing_generation, retry_key_digest "
                        + "FROM identity_deletion_run WHERE user_id = :userId FOR UPDATE")
                .param("userId", userId)
                .query(IdentityAccountRepository::storedRun)
                .optional();
    }

    /** 실행 행을 잠그지 않고 읽는다. */
    public Optional<StoredRun> findRun(UUID userId) {
        return jdbc.sql("SELECT status, operational_erased_at, fencing_generation, retry_key_digest "
                        + "FROM identity_deletion_run WHERE user_id = :userId")
                .param("userId", userId)
                .query(IdentityAccountRepository::storedRun)
                .optional();
    }

    /** 그 실행의 단계 행(순서대로). */
    public List<StoredStep> findSteps(UUID userId) {
        return jdbc.sql("SELECT step, status, erased_rows, failure_code, attempts FROM identity_deletion_step "
                        + "WHERE user_id = :userId ORDER BY position")
                .param("userId", userId)
                .query((row, index) -> new StoredStep(row.getString("step"), row.getString("status"),
                        row.getObject("erased_rows", Long.class), row.getString("failure_code"), row.getInt("attempts")))
                .list();
    }

    /** 단계를 성공으로 기록한다. */
    public int markStepSucceeded(UUID userId, String step, long erasedRows) {
        return jdbc.sql("UPDATE identity_deletion_step SET status = 'SUCCEEDED', erased_rows = :erasedRows, "
                        + "failure_code = NULL, attempts = attempts + 1, finished_at = statement_timestamp() "
                        + "WHERE user_id = :userId AND step = :step")
                .param("erasedRows", erasedRows)
                .param("userId", userId)
                .param("step", step)
                .update();
    }

    /**
     * 단계를 재시도 필요로 기록한다. {@code countAttempt}가 참이면 이번 실행에서 돈 단계다(마지막 단계의 재검사가 되돌린 앞 단계는
     * 이번에 돌지 않았으므로 세지 않는다).
     */
    public int markStepRetryNeeded(UUID userId, String step, String failureCode, boolean countAttempt) {
        return jdbc.sql("UPDATE identity_deletion_step SET status = 'RETRY_NEEDED', erased_rows = NULL, "
                        + "failure_code = :failureCode, attempts = attempts + :increment, "
                        + "finished_at = statement_timestamp() WHERE user_id = :userId AND step = :step")
                .param("failureCode", failureCode)
                .param("increment", countAttempt ? 1 : 0)
                .param("userId", userId)
                .param("step", step)
                .update();
    }

    /** 실행을 재시도 필요로 멈춘다(lease를 놓는다). */
    public int markRunRetryNeeded(UUID userId, long generation) {
        return jdbc.sql("UPDATE identity_deletion_run SET status = 'RETRY_NEEDED', lease_until = NULL "
                        + "WHERE user_id = :userId AND fencing_generation = :generation AND status = 'RUNNING'")
                .param("userId", userId)
                .param("generation", generation)
                .update();
    }

    /** 실행을 운영 데이터 삭제 완료로 끝낸다(문장 시각, lease를 놓는다). */
    public int markRunErased(UUID userId, long generation) {
        return jdbc.sql("UPDATE identity_deletion_run SET status = 'OPERATIONAL_DATA_ERASED', lease_until = NULL, "
                        + "operational_erased_at = statement_timestamp() "
                        + "WHERE user_id = :userId AND fencing_generation = :generation AND status = 'RUNNING'")
                .param("userId", userId)
                .param("generation", generation)
                .update();
    }

    /** lease를 놓고 {@code QUEUED}로 되돌린다(Worker 종료 drain). */
    public int releaseRun(UUID userId, long generation) {
        return jdbc.sql("UPDATE identity_deletion_run SET status = 'QUEUED', lease_until = NULL "
                        + "WHERE user_id = :userId AND fencing_generation = :generation AND status = 'RUNNING'")
                .param("userId", userId)
                .param("generation", generation)
                .update();
    }

    /**
     * 단일 모드 삭제(리허설 표본·복원 재삭제)가 그 계정을 이미 끝까지 지웠을 때 남은 단계 모드 실행을 마감한다 — 끝나지 않은 단계를 성공
     * (건수 0: 이 실행이 지운 것이 아니라 단일 모드가 지웠다)으로, 실행을 운영 데이터 삭제 완료로 바꾸고 세대를 올려 늦은 Worker의 단계
     * 커밋을 막는다. 실행 행이 없거나 이미 완료면 0행이다.
     *
     * @return 마감한 실행 수(0 또는 1)
     */
    public int finishRunBySingleErasure(UUID userId) {
        jdbc.sql("UPDATE identity_deletion_step SET status = 'SUCCEEDED', erased_rows = 0, failure_code = NULL, "
                        + "finished_at = statement_timestamp() WHERE user_id = :userId AND status <> 'SUCCEEDED' "
                        + "AND EXISTS (SELECT 1 FROM identity_deletion_run AS run WHERE run.user_id = :userId "
                        + "AND run.status <> 'OPERATIONAL_DATA_ERASED')")
                .param("userId", userId)
                .update();
        return jdbc.sql("UPDATE identity_deletion_run SET status = 'OPERATIONAL_DATA_ERASED', lease_until = NULL, "
                        + "operational_erased_at = statement_timestamp(), fencing_generation = fencing_generation + 1 "
                        + "WHERE user_id = :userId AND status <> 'OPERATIONAL_DATA_ERASED'")
                .param("userId", userId)
                .update();
    }

    /** 재시도 필요 실행을 {@code QUEUED}로 되돌리고 그 키를 남긴다. */
    public int requeueRun(UUID userId, String retryKeyDigest) {
        return jdbc.sql("UPDATE identity_deletion_run SET status = 'QUEUED', retry_key_digest = :digest, "
                        + "retry_requested_at = statement_timestamp() "
                        + "WHERE user_id = :userId AND status = 'RETRY_NEEDED'")
                .param("digest", retryKeyDigest)
                .param("userId", userId)
                .update();
    }

    /**
     * 그 요청 ID들 가운데 운영 데이터 삭제가 끝난 실행의 완료 시각(Story 5.3 백업 파기 판정). 끝나지 않았거나 요청 ID가 없는 실행은 빠진다.
     */
    public Map<String, OffsetDateTime> operationalCompletions(Collection<String> requestIds) {
        Map<String, OffsetDateTime> completions = new LinkedHashMap<>();
        if (requestIds.isEmpty()) {
            return completions;
        }
        jdbc.sql("SELECT request_id, operational_erased_at FROM identity_deletion_run "
                        + "WHERE request_id IN (:requestIds) AND status = 'OPERATIONAL_DATA_ERASED'")
                .param("requestIds", List.copyOf(requestIds))
                .query((ResultSet row) -> {
                    completions.put(row.getString("request_id"),
                            row.getObject("operational_erased_at", OffsetDateTime.class));
                });
        return completions;
    }

    private static StoredRun storedRun(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new StoredRun(row.getString("status"), row.getObject("operational_erased_at", OffsetDateTime.class),
                row.getLong("fencing_generation"), row.getString("retry_key_digest"));
    }
}
