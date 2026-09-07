package com.bokrilog.identity.internal;

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
     * 계정 payload 행을 지운다. 생명주기 guard 행은 건드리지 않는다.
     *
     * @return 지운 행 수. 0행은 실패가 아니다.
     */
    public int deleteAccountPayload(UUID userId) {
        return jdbc.sql("DELETE FROM identity_account WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }
}
