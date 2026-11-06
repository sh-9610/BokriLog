package com.bokrilog.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.bokrilog.identity.internal.IdentityAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 삭제 경로.
 *
 * <p>확인하는 것은 네 가지다. payload와 매핑이 실제로 사라지는가, 생명주기 guard 행이 남는가,
 * 다시 불러도 실패가 아닌가, 그리고 저장이 실패했을 때 완료로 보고하지 않는가.
 *
 * <p>저장 실패는 실제로 주입한다. 실패 경로를 시험하지 않으면 "부분 실패를 완료로 보고하지
 * 않는다"는 문장은 코드가 아니라 의도로만 남는다. 주입 지점은 저장소 한 곳이고, 그래서 이 시험
 * 클래스만 {@link MockitoSpyBean}으로 컨텍스트가 갈라진다. 컨테이너는 여전히 하나다.
 */
class AccountDeletionTest extends IdentityKeyedTestBase {

    @Autowired private AccountLifecycleApi lifecycle;

    @Autowired private AccountDeletionApi deletion;

    @Autowired private JdbcClient jdbc;

    @MockitoSpyBean private IdentityAccountRepository repository;

    private String storedState(UUID userId) {
        return jdbc
                .sql("SELECT state FROM identity_account_lifecycle WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse("<행 없음>");
    }

    private long countPayload(UUID userId) {
        return jdbc
                .sql("SELECT count(*) FROM identity_account WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private long countLinks(UUID userId) {
        return jdbc
                .sql("SELECT count(*) FROM identity_social_link WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /** 계정 하나를 만들고 매핑 하나를 붙인다. 삭제가 실제로 지울 것이 있어야 시험이 의미를 가진다. */
    private UUID accountWithLink() {
        UUID userId = lifecycle.createAccount();
        AccessDecision linked =
                lifecycle.linkSocialIdentity(
                        userId,
                        "google",
                        "https://accounts.example/" + UUID.randomUUID(),
                        UUID.randomUUID().toString());
        assertThat(linked).isEqualTo(AccessDecision.ALLOWED);
        return userId;
    }

    @Test
    void 전환을_거치지_않은_계정은_지우지_않는다() {
        UUID userId = accountWithLink();

        assertThat(deletion.erasePayload(userId)).isEqualTo(DeletionOutcome.NOT_APPLICABLE);
        assertThat(storedState(userId)).isEqualTo("ACTIVE");
        assertThat(countPayload(userId)).isEqualTo(1L);
        assertThat(countLinks(userId)).isEqualTo(1L);
    }

    @Test
    void 삭제는_payload와_매핑을_지우고_guard_행을_남긴다() {
        UUID userId = accountWithLink();

        assertThat(deletion.beginDeletion(userId)).isEqualTo(DeletionOutcome.COMPLETED);
        assertThat(deletion.erasePayload(userId)).isEqualTo(DeletionOutcome.COMPLETED);

        assertThat(countPayload(userId)).isZero();
        assertThat(countLinks(userId)).isZero();
        // guard 행은 payload와 분리돼 남는다. 이것이 삭제 상태 계약의 남는 절반이다.
        assertThat(storedState(userId)).isEqualTo("DELETED");
    }

    @Test
    void 남은_guard_행이_신규_세션과_매핑_연결을_계속_거부한다() {
        UUID userId = accountWithLink();
        deletion.beginDeletion(userId);
        deletion.erasePayload(userId);

        assertThat(lifecycle.decideSessionCreation(userId)).isEqualTo(AccessDecision.DENIED);
        assertThat(
                        lifecycle.linkSocialIdentity(
                                userId,
                                "naver",
                                "https://accounts.example/" + UUID.randomUUID(),
                                UUID.randomUUID().toString()))
                .isEqualTo(AccessDecision.DENIED);
        assertThat(countLinks(userId)).isZero();
        assertThat(storedState(userId)).isEqualTo("DELETED");
    }

    @Test
    void 삭제_재호출은_실패가_아니다() {
        UUID userId = accountWithLink();
        deletion.beginDeletion(userId);
        deletion.erasePayload(userId);

        // 지울 행이 하나도 없다. 없는 행 삭제를 실패로 보지 않는다.
        assertThat(deletion.erasePayload(userId)).isEqualTo(DeletionOutcome.COMPLETED);
        // 전환도 멱등이다. 이미 끝난 계정을 되살리지 않는다.
        assertThat(deletion.beginDeletion(userId)).isEqualTo(DeletionOutcome.COMPLETED);
        assertThat(storedState(userId)).isEqualTo("DELETED");
        assertThat(countPayload(userId)).isZero();
    }

    @Test
    void 없는_계정의_삭제는_예외가_아니라_해당_없음이다() {
        UUID missing = UUID.randomUUID();

        assertThat(deletion.beginDeletion(missing)).isEqualTo(DeletionOutcome.NOT_APPLICABLE);
        assertThat(deletion.erasePayload(missing)).isEqualTo(DeletionOutcome.NOT_APPLICABLE);
        assertThat(deletion.beginDeletion(null)).isEqualTo(DeletionOutcome.NOT_APPLICABLE);
        assertThat(deletion.erasePayload(null)).isEqualTo(DeletionOutcome.NOT_APPLICABLE);
    }

    @Test
    void 한_계정의_삭제는_다른_계정을_건드리지_않는다() {
        UUID target = accountWithLink();
        UUID bystander = accountWithLink();

        deletion.beginDeletion(target);
        deletion.erasePayload(target);

        assertThat(storedState(bystander)).isEqualTo("ACTIVE");
        assertThat(countPayload(bystander)).isEqualTo(1L);
        assertThat(countLinks(bystander)).isEqualTo(1L);
        assertThat(lifecycle.decideSessionCreation(bystander)).isEqualTo(AccessDecision.ALLOWED);
    }

    @Test
    void 저장이_실패하면_완료로_보고하지_않고_재시도가_끝낸다() {
        UUID userId = accountWithLink();
        assertThat(deletion.beginDeletion(userId)).isEqualTo(DeletionOutcome.COMPLETED);

        // 첫 호출만 실패하고 두 번째부터는 실제 저장소로 간다. 실패와 재시도를 한 시험 안에서
        // 이어 보기 위한 배치다.
        doThrow(new DataAccessResourceFailureException("저장 실패 주입"))
                .doCallRealMethod()
                .when(repository)
                .deleteAccountPayload(any());

        assertThatThrownBy(() -> deletion.erasePayload(userId))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // 트랜잭션이 통째로 롤백됐다. 매핑 삭제도 되돌아갔고 상태는 전진하지 않았다.
        assertThat(storedState(userId)).isEqualTo("DELETION_IN_PROGRESS");
        assertThat(countPayload(userId)).isEqualTo(1L);
        assertThat(countLinks(userId)).isEqualTo(1L);

        // 실패 뒤에도 상태는 ACTIVE로 돌아가지 않는다. 그 사이에도 쓰기는 계속 막혀 있다.
        assertThat(lifecycle.decideSessionCreation(userId)).isEqualTo(AccessDecision.DENIED);

        assertThat(deletion.erasePayload(userId)).isEqualTo(DeletionOutcome.COMPLETED);
        assertThat(storedState(userId)).isEqualTo("DELETED");
        assertThat(countPayload(userId)).isZero();
        assertThat(countLinks(userId)).isZero();
    }

    /**
     * Story 5.4 2차 리뷰: 매핑 INSERT의 {@code NOT EXISTS}는 그 문장의 스냅숏으로 본다 — 같은 식별자의 삭제가 digest를 쓰고 옛 매핑을 지우는
     * 커밋을 INSERT가 유일 색인에서 기다리면, INSERT는 성공하지만 술어는 그 digest를 보지 못한다. 그 창을 대역으로 재현한다: 실제 INSERT 직후
     * 차단 기록을 넣고, 재확인 문장이 그것을 보면 방금 만든 매핑을 지우고 거부해야 한다(예외 없이 — 호출자의 롤백 표시를 세우지 않는다).
     */
    @Test
    void 매핑_INSERT_뒤_재확인이_차단_기록을_보면_매핑을_지우고_거부한다() {
        UUID userId = lifecycle.createAccount();
        String issuer = "https://accounts.example/" + UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        doAnswer(invocation -> {
            Object inserted = invocation.callRealMethod();
            jdbc.sql("INSERT INTO identity_deleted_identity (identity_digest) VALUES (:digest)")
                    .param("digest", invocation.getArgument(4, String.class))
                    .update();
            return inserted;
        }).when(repository).insertSocialLinkIfActive(any(), any(), any(), any(), any());

        assertThat(lifecycle.linkSocialIdentity(userId, "google", issuer, subject)).isEqualTo(AccessDecision.DENIED);
        assertThat(countLinks(userId)).as("방금 만든 매핑을 지웠다").isZero();
        assertThat(lifecycle.deletedIdentityStateOf(issuer, subject))
                .isEqualTo(AccountLifecycleApi.DeletedIdentityState.DELETED);
    }
}
