package com.bokrilog.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 매핑 연결이 거부될 때 남는 것과 남지 않는 것.
 *
 * <p>확인하는 것이 둘이다.
 *
 * <p>첫째, <b>거부가 호출자의 트랜잭션을 건드리지 않는다.</b> 이 API는 자기 트랜잭션 안에서만
 * 불리지 않는다. Story 1.4의 OIDC 콜백은 "계정을 만들고, 매핑을 연결하고, 세션을 연다"를 자기
 * {@code @Transactional} 하나로 묶을 것이고, 그때 이 메서드는 그 트랜잭션에 합류한다. 거부가
 * rollback-only를 세우면 그 표시는 트랜잭션 전체에 걸리는 전역 값이라 호출자의 커밋이
 * {@code UnexpectedRollbackException}으로 죽고 호출자가 한 작업까지 사라진다. 거부는 값이어야지
 * 호출자 트랜잭션에 대한 판결이면 안 된다.
 *
 * <p>둘째, <b>뜻 없는 값이 전역 유일 슬롯을 점유하지 못한다.</b> {@code (issuer, subject)}는 전역에
 * 하나뿐인 자리다. 빈 문자열이나 공백뿐인 값이 한 번 들어가면 그 자리는 영구히 막히고 아무도 그
 * 매핑으로 로그인하지 못한다. 지나치게 긴 값은 다른 방식으로 위험하다. 유일 인덱스의 btree 한계에
 * 걸리는 오류는 거부 값이 아니라 트랜잭션을 abort시키는 예외라, 위의 첫째 문제를 그대로 되살린다.
 */
class AccountLinkDenialTest extends IdentityKeyedTestBase {

    /** 스키마 CHECK와 `AccountLifecycleService`가 함께 정한 상한을 한 바이트 넘는 값. */
    private static final String TOO_LONG_IDENTIFIER = "x".repeat(513);

    @Autowired private AccountLifecycleApi lifecycle;

    @Autowired private AccountDeletionApi deletion;

    @Autowired private JdbcClient jdbc;

    @Autowired private TransactionTemplate transactionTemplate;

    private String storedState(UUID userId) {
        return jdbc
                .sql("SELECT state FROM identity_account_lifecycle WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse("<행 없음>");
    }

    private long countLinks(UUID userId) {
        return jdbc
                .sql("SELECT count(*) FROM identity_social_link WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private static String newIssuer() {
        return "https://accounts.example/" + UUID.randomUUID();
    }

    @Test
    void 이미_쓰인_식별자의_거부가_호출자_트랜잭션을_오염시키지_않는다() {
        UUID owner = lifecycle.createAccount();
        String issuer = newIssuer();
        String subject = UUID.randomUUID().toString();
        assertThat(lifecycle.linkSocialIdentity(owner, "google", issuer, subject))
                .isEqualTo(AccessDecision.ALLOWED);

        // 호출자가 자기 트랜잭션을 열고 그 안에서 거부를 받는다. Story 1.4의 콜백과 같은 모양이다.
        UUID caller =
                transactionTemplate.execute(
                        status -> {
                            UUID newAccount = lifecycle.createAccount();

                            assertThat(
                                            lifecycle.linkSocialIdentity(
                                                    newAccount, "google", issuer, subject))
                                    .describedAs("이미 다른 계정에 있는 식별자")
                                    .isEqualTo(AccessDecision.DENIED);

                            // 거부 뒤에도 같은 트랜잭션에서 계속 쓸 수 있다. 데이터베이스가 이
                            // 트랜잭션을 abort시켰다면 이 호출부터 실패한다.
                            assertThat(
                                            lifecycle.linkSocialIdentity(
                                                    newAccount,
                                                    "naver",
                                                    newIssuer(),
                                                    UUID.randomUUID().toString()))
                                    .isEqualTo(AccessDecision.ALLOWED);
                            return newAccount;
                        });

        // 호출자의 트랜잭션이 실제로 커밋됐다. 거부가 호출자의 작업을 되돌리지 않았다.
        assertThat(storedState(caller)).isEqualTo("ACTIVE");
        assertThat(countLinks(caller)).isEqualTo(1L);
        // 거부된 쪽은 아무것도 남기지 않았고, 원래 소유자의 매핑도 그대로다.
        assertThat(countLinks(owner)).isEqualTo(1L);
    }

    @Test
    void 삭제된_계정의_거부도_호출자_트랜잭션을_오염시키지_않는다() {
        UUID deleted = lifecycle.createAccount();
        deletion.beginDeletion(deleted);
        deletion.erasePayload(deleted);

        UUID caller =
                transactionTemplate.execute(
                        status -> {
                            UUID newAccount = lifecycle.createAccount();

                            assertThat(
                                            lifecycle.linkSocialIdentity(
                                                    deleted,
                                                    "google",
                                                    newIssuer(),
                                                    UUID.randomUUID().toString()))
                                    .isEqualTo(AccessDecision.DENIED);

                            assertThat(
                                            lifecycle.linkSocialIdentity(
                                                    newAccount,
                                                    "google",
                                                    newIssuer(),
                                                    UUID.randomUUID().toString()))
                                    .isEqualTo(AccessDecision.ALLOWED);
                            return newAccount;
                        });

        assertThat(storedState(caller)).isEqualTo("ACTIVE");
        assertThat(countLinks(caller)).isEqualTo(1L);
        assertThat(countLinks(deleted)).isZero();
        assertThat(storedState(deleted)).isEqualTo("DELETED");
    }

    @Test
    void 비어_있거나_공백뿐인_값은_매핑을_만들지_않는다() {
        UUID userId = lifecycle.createAccount();
        String issuer = newIssuer();
        String subject = UUID.randomUUID().toString();

        for (String blank : List.of("", " ", "\t", "\n", "   \t  ")) {
            assertThat(lifecycle.linkSocialIdentity(userId, blank, issuer, subject))
                    .describedAs("provider=%s", blank.strip().isEmpty() ? "<공백>" : blank)
                    .isEqualTo(AccessDecision.DENIED);
            assertThat(lifecycle.linkSocialIdentity(userId, "google", blank, subject))
                    .isEqualTo(AccessDecision.DENIED);
            assertThat(lifecycle.linkSocialIdentity(userId, "google", issuer, blank))
                    .isEqualTo(AccessDecision.DENIED);
        }

        assertThat(lifecycle.linkSocialIdentity(userId, null, issuer, subject))
                .isEqualTo(AccessDecision.DENIED);

        // 전역 유일 슬롯이 뜻 없는 값으로 채워지지 않았다.
        assertThat(countLinks(userId)).isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM identity_social_link "
                                                + "WHERE btrim(issuer) = '' OR btrim(subject) = '' "
                                                + "OR btrim(provider) = ''")
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void 지나치게_긴_값도_거부일_뿐_호출자_트랜잭션을_죽이지_않는다() {
        UUID caller =
                transactionTemplate.execute(
                        status -> {
                            UUID newAccount = lifecycle.createAccount();

                            assertThat(
                                            lifecycle.linkSocialIdentity(
                                                    newAccount,
                                                    "google",
                                                    TOO_LONG_IDENTIFIER,
                                                    UUID.randomUUID().toString()))
                                    .describedAs("상한을 넘는 issuer")
                                    .isEqualTo(AccessDecision.DENIED);
                            assertThat(
                                            lifecycle.linkSocialIdentity(
                                                    newAccount,
                                                    "google",
                                                    newIssuer(),
                                                    TOO_LONG_IDENTIFIER))
                                    .describedAs("상한을 넘는 subject")
                                    .isEqualTo(AccessDecision.DENIED);
                            assertThat(
                                            lifecycle.linkSocialIdentity(
                                                    newAccount,
                                                    "g".repeat(65),
                                                    newIssuer(),
                                                    UUID.randomUUID().toString()))
                                    .describedAs("상한을 넘는 provider")
                                    .isEqualTo(AccessDecision.DENIED);

                            // 거부는 값으로 끝났다. 트랜잭션은 살아 있다.
                            assertThat(
                                            lifecycle.linkSocialIdentity(
                                                    newAccount,
                                                    "google",
                                                    newIssuer(),
                                                    UUID.randomUUID().toString()))
                                    .isEqualTo(AccessDecision.ALLOWED);
                            return newAccount;
                        });

        assertThat(storedState(caller)).isEqualTo("ACTIVE");
        assertThat(countLinks(caller)).isEqualTo(1L);
    }

    @Test
    void 길이_상한은_스키마도_강제한다() {
        UUID userId = lifecycle.createAccount();

        // 애플리케이션이 먼저 값으로 거부하지만, 이 모듈 밖에서 행이 들어오는 경로의 최종 방어는
        // 마이그레이션의 CHECK다. 그 제약이 실제로 존재하는지 저장소에 직접 넣어 확인한다.
        assertThatThrownBy(
                        () ->
                                transactionTemplate.execute(
                                        status ->
                                                jdbc.sql(
                                                                "INSERT INTO identity_social_link "
                                                                        + "(link_id, user_id, provider, issuer, subject) "
                                                                        + "VALUES (:linkId, :userId, 'google', :issuer, :subject)")
                                                        .param("linkId", UUID.randomUUID())
                                                        .param("userId", userId)
                                                        .param("issuer", TOO_LONG_IDENTIFIER)
                                                        .param("subject", UUID.randomUUID().toString())
                                                        .update()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countLinks(userId)).isZero();
    }
}
