package com.bokrilog.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.PostgresTestcontainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 재가입 차단의 fail-closed(Story 5.4) — 감사 digest 키가 없는 프로세스(웹 없는 컨텍스트, 키를 주지 않는다)에서는 차단 기록을 대조하거나 남길
 * 수 없다. 그때 새 매핑(가입·연결)은 거부되고, 지울 매핑이 있는 삭제는 아무것도 지우지 않고 예외로 끝난다(차단 기록 없는 삭제를 하지 않는다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestcontainersConfig.class)
class DeletedIdentityKeylessTest {

    @Autowired private AccountLifecycleApi lifecycle;

    @Autowired private AccountDeletionApi deletion;

    @Autowired private JdbcClient jdbc;

    @Test
    void 키가_없으면_새_매핑을_만들지_않고_매핑이_있는_삭제는_실패로_닫는다() {
        String issuer = "https://accounts.example/" + UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        UUID userId = this.lifecycle.createAccount();
        assertThat(this.lifecycle.linkSocialIdentity(userId, "google", issuer, subject)).isEqualTo(AccessDecision.DENIED);
        assertThat(this.lifecycle.deletedIdentityStateOf(issuer, subject))
                .isEqualTo(AccountLifecycleApi.DeletedIdentityState.UNVERIFIABLE);
        assertThat(this.lifecycle.isDeletedIdentity(issuer, subject)).isFalse();
        assertThat(links(userId)).isZero();

        // 키가 있던 시절의 매핑이 남은 계정(시험은 SQL로 만든다)을 지우려 하면 아무것도 지우지 않고 실패한다.
        this.jdbc.sql("INSERT INTO identity_social_link (link_id, user_id, provider, issuer, subject) "
                        + "VALUES (:id, :u, 'google', :i, :s)")
                .param("id", UUID.randomUUID()).param("u", userId).param("i", issuer).param("s", subject).update();
        assertThat(this.deletion.beginDeletion(userId)).isEqualTo(DeletionOutcome.COMPLETED);
        long digests = digests();
        assertThatThrownBy(() -> this.deletion.eraseSocialLinks(userId)).isInstanceOf(IllegalStateException.class);
        assertThat(links(userId)).isEqualTo(1);
        assertThat(digests()).isEqualTo(digests);
        assertThatThrownBy(() -> this.deletion.erasePayload(userId)).isInstanceOf(IllegalStateException.class);
        assertThat(links(userId)).isEqualTo(1);
        assertThat(this.jdbc.sql("SELECT state FROM identity_account_lifecycle WHERE user_id = :u").param("u", userId)
                .query(String.class).single()).isEqualTo("DELETION_IN_PROGRESS");
    }

    private long links(UUID userId) {
        return this.jdbc.sql("SELECT count(*) FROM identity_social_link WHERE user_id = :u").param("u", userId)
                .query(Long.class).single();
    }

    private long digests() {
        return this.jdbc.sql("SELECT count(*) FROM identity_deleted_identity").query(Long.class).single();
    }
}
