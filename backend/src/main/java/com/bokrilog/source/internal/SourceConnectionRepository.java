package com.bokrilog.source.internal;

import com.bokrilog.source.SourceConnectionApi.ActiveConnection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code source_*} 테이블에 닿는 유일한 자리.
 *
 * <p>{@code JdbcClient} + 원시 SQL이다. JPA를 쓰지 않는 근거는 {@code IdentityAccountRepository}와
 * 같다 — 여기서 하는 일은 술어가 붙은 INSERT와 조건부 조회뿐이라 SQL이 그대로 보이는 편이 낫고,
 * dirty checking과 flush 시점은 "커밋 직전 재검사"라는 계약을 흐린다.
 *
 * <p><b>한 개 규칙을 이 클래스가 세지 않는다.</b> 사용자당 활성 연결 하나는 부분 유일 색인이
 * 강제하고({@code V4__source_connection.sql}) 여기서 하는 조회는 외부 호출 0건으로 끝낼 수 있는
 * 요청을 먼저 거르는 사전 확인이다. 충돌은 {@link org.springframework.dao.DuplicateKeyException}
 * 으로 올라가 호출자의 트랜잭션을 통째로 되돌린다.
 */
@Repository
class SourceConnectionRepository {

    /** 활성 연결의 상태 값. 스키마 CHECK와 한 쌍이다. */
    static final String STATUS_ACTIVE = "ACTIVE";

    private final JdbcClient jdbc;

    SourceConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 제공자 계정 한 행. 소유자를 함께 읽어야 "남의 UID인가"를 판정할 수 있다. */
    record ProviderAccountRow(UUID providerAccountId, UUID userId) {}

    Optional<ActiveConnection> findActiveConnection(UUID userId, String provider) {
        return this.jdbc
                .sql(
                        "SELECT connection_id, verified_at, policy_version FROM source_connection "
                                + "WHERE user_id = :userId AND provider = :provider AND status = :status")
                .param("userId", userId)
                .param("provider", provider)
                .param("status", STATUS_ACTIVE)
                .query(
                        (rs, rowNum) ->
                                new ActiveConnection(
                                        rs.getObject("connection_id", UUID.class),
                                        rs.getObject("verified_at", OffsetDateTime.class).toInstant(),
                                        rs.getString("policy_version")))
                .optional();
    }

    record SnapshotRow(UUID connectionId, UUID providerAccountId, String providerAccountRef, String policyVersion,
            UUID credentialId, String keyVersion, String algorithmVersion,
            byte[] nonce, byte[] ciphertext) {}

    Optional<SnapshotRow> snapshotConnection(UUID userId, UUID connectionId) {
        return this.jdbc.sql("SELECT c.connection_id, c.provider_account_id, a.provider_account_ref, c.policy_version, "
                + "k.credential_id, k.key_version, k.algorithm_version, k.nonce, k.ciphertext "
                + "FROM source_connection c JOIN source_provider_account a ON a.provider_account_id=c.provider_account_id JOIN source_credential k ON k.connection_id=c.connection_id "
                + "WHERE c.user_id=:userId AND c.connection_id=:connectionId AND c.status='ACTIVE'")
            .param("userId", userId).param("connectionId", connectionId)
            .query((rs,n) -> new SnapshotRow(rs.getObject(1,UUID.class), rs.getObject(2,UUID.class),rs.getString(3),
                rs.getString(4), rs.getObject(5,UUID.class),rs.getString(6),rs.getString(7),
                rs.getBytes(8),rs.getBytes(9))).optional();
    }

    Optional<SnapshotRow> activeSnapshotConnection(UUID userId) {
        return this.jdbc.sql("SELECT c.connection_id, c.provider_account_id, a.provider_account_ref, c.policy_version, "
                + "k.credential_id, k.key_version, k.algorithm_version, k.nonce, k.ciphertext "
                + "FROM source_connection c JOIN source_provider_account a ON a.provider_account_id=c.provider_account_id JOIN source_credential k ON k.connection_id=c.connection_id "
                + "WHERE c.user_id=:userId AND c.status='ACTIVE'")
            .param("userId", userId)
            .query((rs,n) -> new SnapshotRow(rs.getObject(1,UUID.class), rs.getObject(2,UUID.class),rs.getString(3),
                rs.getString(4), rs.getObject(5,UUID.class),rs.getString(6),rs.getString(7),
                rs.getBytes(8),rs.getBytes(9))).optional();
    }

    /**
     * 그 제공자 계정이 이미 있는가.
     *
     * <p>사용자로 좁히지 <b>않는다</b>. 좁히면 "남의 계정에 이미 붙은 UID"가 조회되지 않아 새
     * 행을 만들려다 유일 색인에 부딪히고, 그 실패는 구분되지 않는 예외가 된다. 소유자를 함께 읽어
     * 값으로 판정하는 것이 이 조회가 사용자 축을 갖지 않는 이유다.
     */
    Optional<ProviderAccountRow> findProviderAccount(String provider, String providerAccountRef) {
        return this.jdbc
                .sql(
                        "SELECT provider_account_id, user_id FROM source_provider_account "
                                + "WHERE provider = :provider AND provider_account_ref = :ref")
                .param("provider", provider)
                .param("ref", providerAccountRef)
                .query(
                        (rs, rowNum) ->
                                new ProviderAccountRow(
                                        rs.getObject("provider_account_id", UUID.class),
                                        rs.getObject("user_id", UUID.class)))
                .optional();
    }

    int insertProviderAccount(
            UUID providerAccountId, UUID userId, String provider, String providerAccountRef) {
        return this.jdbc
                .sql(
                        "INSERT INTO source_provider_account "
                                + "(provider_account_id, user_id, provider, provider_account_ref) "
                                + "VALUES (:id, :userId, :provider, :ref)")
                .param("id", providerAccountId)
                .param("userId", userId)
                .param("provider", provider)
                .param("ref", providerAccountRef)
                .update();
    }

    int insertConnection(
            UUID connectionId,
            UUID userId,
            UUID providerAccountId,
            String provider,
            Instant verifiedAt,
            String policyVersion) {
        return this.jdbc
                .sql(
                        "INSERT INTO source_connection "
                                + "(connection_id, user_id, provider_account_id, provider, status, "
                                + "verified_at, policy_version) "
                                + "VALUES (:id, :userId, :accountId, :provider, :status, :verifiedAt, "
                                + ":policyVersion)")
                .param("id", connectionId)
                .param("userId", userId)
                .param("accountId", providerAccountId)
                .param("provider", provider)
                .param("status", STATUS_ACTIVE)
                .param("verifiedAt", OffsetDateTime.ofInstant(verifiedAt, ZoneOffset.UTC))
                .param("policyVersion", policyVersion)
                .update();
    }

    int insertCredential(UUID credentialId, UUID connectionId, CredentialCipher.Sealed sealed) {
        return this.jdbc
                .sql(
                        "INSERT INTO source_credential "
                                + "(credential_id, connection_id, key_version, algorithm_version, nonce, "
                                + "ciphertext) "
                                + "VALUES (:id, :connectionId, :keyVersion, :algorithmVersion, :nonce, "
                                + ":ciphertext)")
                .param("id", credentialId)
                .param("connectionId", connectionId)
                .param("keyVersion", sealed.keyVersion())
                .param("algorithmVersion", sealed.algorithmVersion())
                .param("nonce", sealed.nonce())
                .param("ciphertext", sealed.ciphertext())
                .update();
    }

    /**
     * 이 사용자의 source 데이터를 지운다.
     *
     * <p>순서가 있다 — 연결을 먼저 지우면 자격정보와 membership이 CASCADE로 함께 사라지고, 그 뒤에
     * 제공자 계정이 남는다. 반대로 하면 제공자 계정의 RESTRICT가 막는다.
     *
     * @return 지워진 연결 행 수. 0도 정상이며 멱등하다
     */
    int eraseOf(UUID userId) {
        int connections =
                this.jdbc
                        .sql("DELETE FROM source_connection WHERE user_id = :userId")
                        .param("userId", userId)
                        .update();
        this.jdbc
                .sql("DELETE FROM source_provider_account WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        return connections;
    }
}
