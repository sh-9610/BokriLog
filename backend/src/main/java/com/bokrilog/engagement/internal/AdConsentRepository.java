package com.bokrilog.engagement.internal;

import com.bokrilog.engagement.AdConsentApi.Choice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 광고 동의 결정 표 {@code engagement_ad_consent_decision}의 유일한 SQL 자리(Story 4.1).
 *
 * <p>문장은 INSERT·SELECT와 deletion port의 DELETE뿐이다 — 결정 행을 고치는 문장이 없다(추가 전용). 결정 시각은 INSERT
 * 문장의 DB 시각(열 기본값 {@code statement_timestamp()})이다.
 */
@Repository
class AdConsentRepository {

    /** 한 사용자의 결정을 직렬화하는 advisory 잠금 이름의 접두사. 다른 모듈의 잠금 이름과 겹치지 않는다. */
    private static final String USER_LOCK_PREFIX = "bokrilog.engagement.ad_consent.user:";

    private final JdbcClient jdbc;

    AdConsentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 호출자 트랜잭션 끝까지 그 사용자의 결정을 직렬화한다. 같은 키의 동시 결정·서로 다른 선택의 경합을 줄 세운다. */
    void lockUser(UUID userId) {
        this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:name, 0))")
                .param("name", USER_LOCK_PREFIX + userId)
                .query((rs, row) -> 1)
                .single();
    }

    /** 결정 행 하나. */
    record Row(String policyVersion, Choice choice, Instant decidedAt, String requestDigest) {}

    /**
     * 그 사용자의 최신 결정 — 순번(identity)이 가장 큰 행. 결정은 사용자 잠금 아래에서만 쓰므로 순번 순서가 커밋 순서다. 시각으로
     * 정렬하지 않는다(DB 시계가 뒤로 가면 뒤집힌다).
     */
    Optional<Row> latest(UUID userId) {
        return this.jdbc.sql("SELECT policy_version, choice, decided_at, request_digest "
                        + "FROM engagement_ad_consent_decision WHERE user_id = :u "
                        + "ORDER BY id DESC LIMIT 1")
                .param("u", userId)
                .query(AdConsentRepository::row)
                .optional();
    }

    /** 같은 사용자·같은 키의 앞선 결정. */
    Optional<Row> byKey(UUID userId, String keyDigest) {
        return this.jdbc.sql("SELECT policy_version, choice, decided_at, request_digest "
                        + "FROM engagement_ad_consent_decision WHERE user_id = :u AND idempotency_key_digest = :k")
                .param("u", userId)
                .param("k", keyDigest)
                .query(AdConsentRepository::row)
                .optional();
    }

    /** 결정 한 행을 쓰고 그 행을 돌려준다. 시각은 이 문장의 DB 시각이다. */
    Row insert(UUID userId, String policyVersion, Choice choice, String keyDigest, String requestDigest) {
        return this.jdbc.sql("INSERT INTO engagement_ad_consent_decision (user_id, policy_version, choice, "
                        + "idempotency_key_digest, request_digest) VALUES (:u, :p, :c, :k, :r) "
                        + "RETURNING policy_version, choice, decided_at, request_digest")
                .param("u", userId)
                .param("p", policyVersion)
                .param("c", choice.name())
                .param("k", keyDigest)
                .param("r", requestDigest)
                .query(AdConsentRepository::row)
                .single();
    }

    /** 그 사용자의 결정 행을 모두 지운다(deletion port). 다른 사용자의 행은 그대로다. */
    int eraseUser(UUID userId) {
        return this.jdbc.sql("DELETE FROM engagement_ad_consent_decision WHERE user_id = :u").param("u", userId)
                .update();
    }

    private static Row row(ResultSet rs, int row) throws SQLException {
        OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
        if (decidedAt == null) {
            throw new SQLException("결정 행의 시각이 비어 있다.");
        }
        return new Row(rs.getString("policy_version"), Choice.valueOf(rs.getString("choice")), decidedAt.toInstant(),
                rs.getString("request_digest"));
    }
}
