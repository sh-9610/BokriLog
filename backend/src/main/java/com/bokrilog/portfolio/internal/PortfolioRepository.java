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

    /**
     * 이 사용자의 portfolio 데이터를 지운다.
     *
     * <p>portfolio를 지우면 membership이 CASCADE로 함께 사라진다. 그 정리를 손으로 적으면 삭제
     * 경로가 자기 모듈의 내부 구조를 문장으로 다시 나열하는 형태가 된다
     * ({@code V4__source_connection.sql}).
     */
    int eraseOf(UUID userId) {
        return this.jdbc
                .sql("DELETE FROM portfolio WHERE user_id = :userId")
                .param("userId", userId)
                .update();
    }
}
