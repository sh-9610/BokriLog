package com.bokrilog.portfolio.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code portfolio}·{@code portfolio_source_membership}에 닿는 유일한 자리.
 *
 * <p>{@code JdbcClient} + 원시 SQL이다({@code SourceConnectionRepository}·
 * {@code IdentityAccountRepository}와 같은 관용).
 */
@Repository
class PortfolioRepository {

    private final JdbcClient jdbc;

    PortfolioRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UUID> findDefaultPortfolio(UUID userId, String baseCurrency) {
        return this.jdbc
                .sql(
                        "SELECT portfolio_id FROM portfolio "
                                + "WHERE user_id = :userId AND base_currency = :currency AND is_default")
                .param("userId", userId)
                .param("currency", baseCurrency)
                .query(UUID.class)
                .optional();
    }

    int insertDefaultPortfolio(UUID portfolioId, UUID userId, String baseCurrency) {
        return this.jdbc
                .sql(
                        "INSERT INTO portfolio (portfolio_id, user_id, base_currency, is_default) "
                                + "VALUES (:id, :userId, :currency, true)")
                .param("id", portfolioId)
                .param("userId", userId)
                .param("currency", baseCurrency)
                .update();
    }

    int insertActiveMembership(
            UUID membershipId,
            UUID portfolioId,
            UUID connectionId,
            UUID providerAccountId,
            Instant startedAt) {
        return this.jdbc
                .sql(
                        "INSERT INTO portfolio_source_membership "
                                + "(membership_id, portfolio_id, connection_id, provider_account_id, "
                                + "started_at) "
                                + "VALUES (:id, :portfolioId, :connectionId, :accountId, :startedAt)")
                .param("id", membershipId)
                .param("portfolioId", portfolioId)
                .param("connectionId", connectionId)
                .param("accountId", providerAccountId)
                .param("startedAt", OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC))
                .update();
    }

    record MembershipRow(UUID portfolioId, UUID membershipId) {}

    Optional<MembershipRow> eligible(UUID userId, UUID connectionId, UUID accountId,
            Instant measuredAt) {
        return this.jdbc.sql("SELECT p.portfolio_id, m.membership_id FROM portfolio p "
                + "JOIN portfolio_source_membership m ON m.portfolio_id=p.portfolio_id "
                + "WHERE p.user_id=:userId AND p.base_currency='USD' AND p.is_default "
                + "AND m.connection_id=:connectionId AND m.provider_account_id=:accountId "
                + "AND m.started_at<=:measured AND (m.ended_at IS NULL OR :measured<m.ended_at) "
                + "AND m.ended_at IS NULL")
            .param("userId",userId).param("connectionId",connectionId).param("accountId",accountId)
            .param("measured",OffsetDateTime.ofInstant(measuredAt,ZoneOffset.UTC))
            .query((rs,n)->new MembershipRow(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)))
            .optional();
    }

    boolean lockEligible(UUID userId, UUID portfolioId, UUID membershipId,
            UUID connectionId, UUID accountId, Instant measuredAt) {
        return this.jdbc.sql("SELECT m.membership_id FROM portfolio p "
                + "JOIN portfolio_source_membership m ON m.portfolio_id=p.portfolio_id "
                + "WHERE p.user_id=:userId AND p.portfolio_id=:portfolioId "
                + "AND p.base_currency='USD' AND p.is_default AND m.membership_id=:membershipId "
                + "AND m.connection_id=:connectionId AND m.provider_account_id=:accountId "
                + "AND m.started_at<=:measured AND m.ended_at IS NULL FOR UPDATE OF p,m")
            .param("userId",userId).param("portfolioId",portfolioId).param("membershipId",membershipId)
            .param("connectionId",connectionId).param("accountId",accountId)
            .param("measured",OffsetDateTime.ofInstant(measuredAt,ZoneOffset.UTC))
            .query(UUID.class).optional().isPresent();
    }

    boolean hasOfficial(UUID portfolioId) {
        return this.jdbc.sql("SELECT EXISTS(SELECT 1 FROM portfolio_current_snapshot WHERE portfolio_id=:id)")
            .param("id",portfolioId).query(Boolean.class).single();
    }

    int insertSnapshot(UUID snapshotId, UUID portfolioId, UUID userId, String decimal,
            Instant measuredAt, long sequence) {
        return this.jdbc.sql("INSERT INTO portfolio_snapshot "
                + "(portfolio_snapshot_id,portfolio_id,user_id,amount_decimal,base_currency,measured_at,publication_sequence) "
                + "VALUES (:id,:portfolioId,:userId,:decimal,'USD',:measured,:sequence)")
            .param("id",snapshotId).param("portfolioId",portfolioId).param("userId",userId)
            .param("decimal",decimal).param("measured",OffsetDateTime.ofInstant(measuredAt,ZoneOffset.UTC))
            .param("sequence",sequence).update();
    }

    record PointerRow(UUID snapshotId, long sequence) {}

    /** 이 portfolio의 현재 포인터를 잠근다. 소유자가 다르면 빈 값이다. */
    Optional<PointerRow> lockPointer(UUID portfolioId, UUID userId) {
        return this.jdbc.sql("SELECT c.portfolio_snapshot_id,c.publication_sequence FROM portfolio_current_snapshot c "
                + "JOIN portfolio p ON p.portfolio_id=c.portfolio_id WHERE c.portfolio_id=:portfolioId "
                + "AND p.user_id=:userId FOR UPDATE OF c")
            .param("portfolioId",portfolioId).param("userId",userId)
            .query((rs,n)->new PointerRow(rs.getObject(1,UUID.class),rs.getLong(2))).optional();
    }

    /** 기대한 snapshot·순번일 때만 한 칸 전진한다. 현재값을 {@code max(created_at)}으로 추론하지 않는다. */
    int advancePointer(UUID portfolioId, UUID expectedSnapshotId, long expectedSequence, UUID snapshotId,
            long sequence) {
        return this.jdbc.sql("UPDATE portfolio_current_snapshot SET portfolio_snapshot_id=:snapshotId,"
                + "publication_sequence=:sequence,published_at=now() WHERE portfolio_id=:portfolioId "
                + "AND portfolio_snapshot_id=:expectedSnapshotId AND publication_sequence=:expectedSequence")
            .param("snapshotId",snapshotId).param("sequence",sequence).param("portfolioId",portfolioId)
            .param("expectedSnapshotId",expectedSnapshotId).param("expectedSequence",expectedSequence).update();
    }

    int insertComponent(UUID snapshotId, UUID membershipId, UUID sourceId, UUID portfolioId,
            UUID userId, UUID connectionId, UUID accountId, String decimal, String policyVersion) {
        return this.jdbc.sql("INSERT INTO portfolio_snapshot_component "
                + "(portfolio_snapshot_id,membership_id,source_snapshot_id,portfolio_id,user_id,"
                + "connection_id,provider_account_id,amount_decimal,policy_version) "
                + "VALUES (:snapshotId,:membershipId,:sourceId,:portfolioId,:userId,:connectionId,"
                + ":accountId,:decimal,:policy)")
            .param("snapshotId",snapshotId).param("membershipId",membershipId)
            .param("sourceId",sourceId).param("portfolioId",portfolioId).param("userId",userId)
            .param("connectionId",connectionId).param("accountId",accountId)
            .param("decimal",decimal).param("policy",policyVersion).update();
    }

    int pointFirst(UUID portfolioId, UUID snapshotId) {
        return this.jdbc.sql("INSERT INTO portfolio_current_snapshot "
                + "(portfolio_id,portfolio_snapshot_id,publication_sequence) "
                + "VALUES (:portfolioId,:snapshotId,1) ON CONFLICT (portfolio_id) DO NOTHING")
            .param("portfolioId",portfolioId).param("snapshotId",snapshotId).update();
    }

    Optional<com.bokrilog.portfolio.PortfolioMembershipApi.OfficialValue> official(UUID userId) {
        return this.jdbc.sql("SELECT s.amount_decimal,s.base_currency,s.measured_at,s.composed_at,"
                + "c.published_at,s.portfolio_snapshot_id,c.publication_sequence FROM portfolio p "
                + "JOIN portfolio_current_snapshot c ON c.portfolio_id=p.portfolio_id "
                + "JOIN portfolio_snapshot s ON s.portfolio_snapshot_id=c.portfolio_snapshot_id "
                + "WHERE p.user_id=:userId AND s.user_id=:userId AND p.is_default")
            .param("userId",userId)
            .query((rs,n)->new com.bokrilog.portfolio.PortfolioMembershipApi.OfficialValue(
                rs.getString(1),rs.getString(2),rs.getObject(3,OffsetDateTime.class).toInstant(),
                rs.getObject(4,OffsetDateTime.class).toInstant(),
                rs.getObject(5,OffsetDateTime.class).toInstant(),rs.getObject(6,UUID.class),rs.getLong(7)))
            .optional();
    }

    Optional<com.bokrilog.portfolio.PortfolioMembershipApi.FirstOfficialSnapshot> firstOfficial(UUID userId) {
        return this.jdbc.sql("SELECT s.portfolio_id,s.portfolio_snapshot_id,s.amount_decimal,s.measured_at,"
                + "s.composed_at,s.published_at,c.membership_id,c.connection_id,c.provider_account_id,"
                + "c.source_snapshot_id,c.policy_version "
                + "FROM portfolio p JOIN portfolio_snapshot s ON s.portfolio_id=p.portfolio_id "
                + "JOIN portfolio_snapshot_component c ON c.portfolio_snapshot_id=s.portfolio_snapshot_id "
                + "WHERE p.user_id=:userId AND s.user_id=:userId AND p.is_default "
                + "AND s.base_currency='USD' ORDER BY s.publication_sequence,s.portfolio_snapshot_id LIMIT 1")
            .param("userId",userId)
            .query((rs,n)->new com.bokrilog.portfolio.PortfolioMembershipApi.FirstOfficialSnapshot(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getObject(4,OffsetDateTime.class).toInstant(),rs.getObject(5,OffsetDateTime.class).toInstant(),
                rs.getObject(6,OffsetDateTime.class).toInstant(),rs.getObject(7,UUID.class),
                rs.getObject(8,UUID.class),rs.getObject(9,UUID.class),rs.getObject(10,UUID.class),
                rs.getString(11),rs.getString(11))).optional();
    }

    Optional<com.bokrilog.portfolio.PortfolioMembershipApi.FirstOfficialSnapshot> ownedSnapshot(UUID userId, UUID snapshotId) {
        return this.jdbc.sql("SELECT s.portfolio_id,s.portfolio_snapshot_id,s.amount_decimal,s.measured_at,"
                + "s.composed_at,s.published_at,c.membership_id,c.connection_id,c.provider_account_id,"
                + "c.source_snapshot_id,c.policy_version "
                + "FROM portfolio p JOIN portfolio_snapshot s ON s.portfolio_id=p.portfolio_id "
                + "JOIN portfolio_snapshot_component c ON c.portfolio_snapshot_id=s.portfolio_snapshot_id "
                + "WHERE p.user_id=:userId AND s.user_id=:userId AND p.is_default "
                + "AND s.base_currency='USD' AND s.portfolio_snapshot_id=:snapshotId")
            .param("userId",userId).param("snapshotId",snapshotId)
            .query((rs,n)->new com.bokrilog.portfolio.PortfolioMembershipApi.FirstOfficialSnapshot(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getObject(4,OffsetDateTime.class).toInstant(),rs.getObject(5,OffsetDateTime.class).toInstant(),
                rs.getObject(6,OffsetDateTime.class).toInstant(),rs.getObject(7,UUID.class),
                rs.getObject(8,UUID.class),rs.getObject(9,UUID.class),rs.getObject(10,UUID.class),
                rs.getString(11),rs.getString(11))).optional();
    }

    Optional<com.bokrilog.portfolio.PortfolioMembershipApi.ActiveSource> activeSource(UUID userId) {
        return this.jdbc.sql("SELECT p.portfolio_id,m.membership_id,m.connection_id,m.provider_account_id "
                + "FROM portfolio p JOIN portfolio_source_membership m ON m.portfolio_id=p.portfolio_id "
                + "WHERE p.user_id=:userId AND p.base_currency='USD' AND p.is_default "
                + "AND m.ended_at IS NULL ORDER BY m.started_at DESC LIMIT 1")
            .param("userId",userId)
            .query((rs,n)->new com.bokrilog.portfolio.PortfolioMembershipApi.ActiveSource(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class))).optional();
    }

    /** The latest start of the connection's open intervals in the user's portfolios. */
    Optional<Instant> activeMembershipStart(UUID userId, UUID connectionId) {
        return this.jdbc.sql("SELECT max(m.started_at) FROM portfolio_source_membership m JOIN portfolio p "
                + "ON p.portfolio_id=m.portfolio_id WHERE p.user_id=:userId AND m.connection_id=:connectionId "
                + "AND m.ended_at IS NULL")
            .param("userId", userId).param("connectionId", connectionId)
            .query((rs, n) -> {
                OffsetDateTime at = rs.getObject(1, OffsetDateTime.class);
                return at == null ? null : at.toInstant();
            })
            .optional();
    }

    /** Closes the connection's open interval in the user's portfolios at {@code endedAt}. */
    int endMembership(UUID userId, UUID connectionId, Instant endedAt) {
        return this.jdbc.sql("UPDATE portfolio_source_membership SET ended_at=:endedAt WHERE connection_id=:connectionId "
                + "AND ended_at IS NULL AND portfolio_id IN (SELECT portfolio_id FROM portfolio WHERE user_id=:userId)")
            .param("endedAt", OffsetDateTime.ofInstant(endedAt, ZoneOffset.UTC))
            .param("connectionId", connectionId).param("userId", userId).update();
    }

    /**
     * 이 사용자의 portfolio 데이터를 지운다.
     *
     * <p>portfolio를 지우면 membership이 CASCADE로 함께 사라진다. 그 정리를 손으로 적으면 삭제
     * 경로가 자기 모듈의 내부 구조를 문장으로 다시 나열하는 형태가 된다
     * ({@code V4__source_connection.sql}).
     */
    int eraseOf(UUID userId) {
        this.jdbc.sql("DELETE FROM portfolio_current_snapshot WHERE portfolio_id IN "
                + "(SELECT portfolio_id FROM portfolio WHERE user_id=:userId)")
                .param("userId",userId).update();
        this.jdbc.sql("DELETE FROM portfolio_snapshot WHERE user_id=:userId")
                .param("userId",userId).update();
        return this.jdbc
                .sql("DELETE FROM portfolio WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }

    /** 운영 제한의 대상 확인(Story 3.2): id로 찾은 portfolio와 그 소유자, 활성 source의 유무. */
    Optional<com.bokrilog.portfolio.PortfolioMembershipApi.PortfolioTarget> portfolioTarget(UUID portfolioId) {
        return this.jdbc.sql("SELECT p.portfolio_id,p.user_id,EXISTS(SELECT 1 FROM portfolio_source_membership m "
                + "WHERE m.portfolio_id=p.portfolio_id AND m.ended_at IS NULL) FROM portfolio p WHERE p.portfolio_id=:p")
            .param("p",portfolioId)
            .query((rs,n)->new com.bokrilog.portfolio.PortfolioMembershipApi.PortfolioTarget(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBoolean(3))).optional();
    }

    /**
     * 현재 포인터 불변식을 어긴 portfolio 수(Story 3.10 복원 검증). 포인터가 그 portfolio·소유자의 snapshot을 가리키지 않거나, 그 순번이
     * portfolio의 가장 큰 snapshot 순번이 아니거나, snapshot이 있는데 포인터가 없다.
     */
    long currentPointerViolations() {
        Long count = this.jdbc.sql("SELECT count(*) FROM portfolio p "
                + "LEFT JOIN portfolio_current_snapshot c ON c.portfolio_id=p.portfolio_id "
                + "LEFT JOIN portfolio_snapshot s ON s.portfolio_snapshot_id=c.portfolio_snapshot_id "
                + "AND s.portfolio_id=p.portfolio_id AND s.user_id=p.user_id "
                + "AND s.publication_sequence=c.publication_sequence "
                + "WHERE (c.portfolio_id IS NULL AND EXISTS (SELECT 1 FROM portfolio_snapshot x WHERE x.portfolio_id=p.portfolio_id)) "
                + "OR (c.portfolio_id IS NOT NULL AND (s.portfolio_snapshot_id IS NULL OR c.publication_sequence<>"
                + "(SELECT max(x.publication_sequence) FROM portfolio_snapshot x WHERE x.portfolio_id=p.portfolio_id)))")
            .query(Long.class).single();
        return count==null ? 0L : count;
    }

    /**
     * 그 사용자에게 남은 이 모듈의 행 수(Story 3.10 복원 검증). {@code portfolio}·{@code portfolio_*} 표의 {@code user_id} 열을
     * 카탈로그로 찾아 센다 — 표를 이름으로 나열하지 않으므로 새 표가 생겨도 빠지지 않는다.
     */
    long remainingRowsOf(UUID userId) {
        Long count = this.jdbc.sql("SELECT CAST(COALESCE(sum(CAST(CAST((xpath('/row/n/text()', query_to_xml(format("
                + "'SELECT count(*) AS n FROM %I WHERE %I = %L', c.table_name, c.column_name, CAST(:user AS text)), "
                + "false, true, '')))[1] AS text) AS bigint)), 0) AS bigint) FROM information_schema.columns c "
                + "WHERE c.table_schema = current_schema() AND c.column_name = 'user_id' "
                + "AND (c.table_name = 'portfolio' OR c.table_name LIKE 'portfolio\\_%')")
            .param("user",userId).query(Long.class).single();
        return count==null ? 0L : count;
    }
}
