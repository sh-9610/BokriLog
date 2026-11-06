package com.bokrilog.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 소유권·삭제 상태 판정의 I/O 표.
 *
 * <p>실제 PostgreSQL로 돈다. 판정이 상태 행 하나를 읽는 일이라 저장소를 흉내 내면 시험이 검증하는
 * 것은 흉내의 정확성뿐이다.
 *
 * <p>모든 시험이 자기 계정을 새로 만들어 쓴다. 그래서 시험 간 정리가 필요 없고 실행 순서에도
 * 의존하지 않는다.
 */
class AccountAccessDecisionTest extends IdentityKeyedTestBase {

    @Autowired private AccountLifecycleApi lifecycle;

    @Autowired private AccountAccessApi access;

    @Autowired private AccountDeletionApi deletion;

    /** 판정 대상이 되는 모든 종류. 새 종류가 생기면 이 목록을 지나는 시험들이 함께 커진다. */
    private static final List<AccountAccessKind> ALL_KINDS = List.of(AccountAccessKind.values());

    /**
     * 저장소를 직접 본다. 판정 API로 저장 상태를 확인하면 "판정이 틀렸다"와 "저장이 틀렸다"를
     * 구분할 수 없다.
     */
    @Autowired private JdbcClient jdbc;

    /** 알 수 없는 상태 시험이 스키마 제약을 잠시 내렸다가 롤백으로 되돌리는 데 쓴다. */
    @Autowired private TransactionTemplate transactionTemplate;

    private String storedState(UUID userId) {
        return jdbc
                .sql("SELECT state FROM identity_account_lifecycle WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse("<행 없음>");
    }

    @Test
    void 계정_생성은_서버가_만든_UUID와_ACTIVE_guard_행을_남긴다() {
        UUID userId = lifecycle.createAccount();

        assertThat(userId).isNotNull();
        assertThat(storedState(userId)).isEqualTo("ACTIVE");
        // payload 행도 함께 생긴다. 삭제 시험이 확인하는 "지워졌다"의 반대편이다.
        assertThat(
                        jdbc.sql("SELECT count(*) FROM identity_account WHERE user_id = :userId")
                                .param("userId", userId)
                                .query(Long.class)
                                .single())
                .isEqualTo(1L);
    }

    @Test
    void identity_스키마에_이메일도_표시명도_제공자_토큰도_없다() {
        // 이메일을 받지 않는다는 것은 시그니처가, 저장하지 않는다는 것은 스키마가 보장한다.
        // 이메일 일치로 계정을 병합·전환하지 않는다는 Epic 1의 불변식이 컬럼 수준에서 지켜진다.
        List<String> columns =
                jdbc
                        .sql(
                                "SELECT column_name FROM information_schema.columns "
                                        + "WHERE table_schema = 'public' AND table_name LIKE 'identity\\_%'")
                        .query(String.class)
                        .list();

        assertThat(columns).isNotEmpty();
        assertThat(columns)
                .noneMatch(
                        column ->
                                column.contains("email")
                                        || column.contains("display")
                                        || column.contains("token")
                                        || column.contains("password"));
    }

    @Test
    void 자기_계정_접근은_모든_종류에서_허용된다() {
        UUID userId = lifecycle.createAccount();

        for (AccountAccessKind kind : ALL_KINDS) {
            assertThat(access.decide(userId, userId, kind))
                    .describedAs("kind=%s", kind)
                    .isEqualTo(AccessDecision.ALLOWED);
        }
    }

    @Test
    void 타_사용자_접근은_모든_종류에서_거부된다() {
        UUID owner = lifecycle.createAccount();
        UUID stranger = lifecycle.createAccount();

        for (AccountAccessKind kind : ALL_KINDS) {
            assertThat(access.decide(stranger, owner, kind))
                    .describedAs("kind=%s", kind)
                    .isEqualTo(AccessDecision.DENIED);
        }

        // 거부는 상대의 데이터에 아무 영향도 주지 않는다. 소유자 자신의 접근은 그대로 허용이다.
        assertThat(access.decide(owner, owner, AccountAccessKind.WRITE))
                .isEqualTo(AccessDecision.ALLOWED);
        assertThat(storedState(owner)).isEqualTo("ACTIVE");
        assertThat(storedState(stranger)).isEqualTo("ACTIVE");
    }

    @Test
    void 미존재_대상은_타_사용자_사례와_같은_값으로_거부된다() {
        UUID caller = lifecycle.createAccount();
        UUID other = lifecycle.createAccount();
        UUID missing = UUID.randomUUID();

        for (AccountAccessKind kind : ALL_KINDS) {
            AccessDecision forMissing = access.decide(caller, missing, kind);
            AccessDecision forOther = access.decide(caller, other, kind);

            // 값이 같아야 한다. 다르면 그 차이가 곧 "그 계정이 존재하는가"의 답이 된다.
            assertThat(forMissing).describedAs("kind=%s", kind).isEqualTo(forOther);
            assertThat(forMissing).isEqualTo(AccessDecision.DENIED);
        }
    }

    @Test
    void 삭제_진행_중에는_조회만_허용된다() {
        UUID userId = lifecycle.createAccount();
        deletion.beginDeletion(userId);

        assertThat(storedState(userId)).isEqualTo("DELETION_IN_PROGRESS");
        assertThat(access.decide(userId, userId, AccountAccessKind.READ))
                .isEqualTo(AccessDecision.ALLOWED);
        assertThat(access.decide(userId, userId, AccountAccessKind.WRITE))
                .isEqualTo(AccessDecision.DENIED);
        assertThat(access.decide(userId, userId, AccountAccessKind.SENSITIVE))
                .isEqualTo(AccessDecision.DENIED);
    }

    @Test
    void 삭제가_끝난_계정은_모든_종류에서_거부된다() {
        UUID userId = lifecycle.createAccount();
        deletion.beginDeletion(userId);
        deletion.erasePayload(userId);

        assertThat(storedState(userId)).isEqualTo("DELETED");
        for (AccountAccessKind kind : ALL_KINDS) {
            // payload가 없다는 사실을 오류로 보고하지 않는다. 다른 거부와 같은 값이다.
            assertThat(access.decide(userId, userId, kind))
                    .describedAs("kind=%s", kind)
                    .isEqualTo(AccessDecision.DENIED);
        }
    }

    @Test
    void 알_수_없는_상태_문자열은_예외가_아니라_거부다() {
        UUID userId = UUID.randomUUID();

        transactionTemplate.execute(
                status -> {
                    // 스키마 CHECK가 세 값만 허용하므로 그 벽을 이 트랜잭션 안에서만 잠시 내린다.
                    // PostgreSQL은 DDL도 트랜잭션에 넣으므로 아래 롤백이 제약을 그대로 되돌린다.
                    jdbc.sql(
                                    "ALTER TABLE identity_account_lifecycle "
                                            + "DROP CONSTRAINT identity_account_lifecycle_state_check")
                            .update();
                    jdbc.sql(
                                    "INSERT INTO identity_account_lifecycle (user_id, state) "
                                            + "VALUES (:userId, 'MYSTERY')")
                            .param("userId", userId)
                            .update();
                    assertThat(storedState(userId)).isEqualTo("MYSTERY");

                    // 판정은 "어떤 입력에도 예외를 던지지 않는다"를 약속한다. 상태 문자열을 enum으로
                    // 그대로 읽으면 그 약속이 행 하나에 뒤집히고, 거부여야 할 응답이 500이 된다.
                    // 모르는 상태의 안전한 답은 거부다.
                    for (AccountAccessKind kind : ALL_KINDS) {
                        assertThat(access.decide(userId, userId, kind))
                                .describedAs("kind=%s", kind)
                                .isEqualTo(AccessDecision.DENIED);
                    }
                    assertThat(lifecycle.decideSessionCreation(userId))
                            .isEqualTo(AccessDecision.DENIED);
                    assertThat(
                                    lifecycle.linkSocialIdentity(
                                            userId,
                                            "google",
                                            "https://accounts.example/" + UUID.randomUUID(),
                                            UUID.randomUUID().toString()))
                            .isEqualTo(AccessDecision.DENIED);

                    // 모르는 상태를 전진시키지도 않는다.
                    assertThat(deletion.beginDeletion(userId))
                            .isEqualTo(DeletionOutcome.NOT_APPLICABLE);
                    assertThat(deletion.erasePayload(userId))
                            .isEqualTo(DeletionOutcome.NOT_APPLICABLE);
                    assertThat(storedState(userId)).isEqualTo("MYSTERY");

                    // 제약과 이 행을 함께 되돌린다. 다른 시험이 보는 스키마는 그대로다.
                    status.setRollbackOnly();
                    return null;
                });

        assertThat(storedState(userId)).isEqualTo("<행 없음>");
    }

    @Test
    void 비어_있는_입력도_예외가_아니라_거부다() {
        UUID userId = lifecycle.createAccount();

        assertThat(access.decide(null, userId, AccountAccessKind.READ))
                .isEqualTo(AccessDecision.DENIED);
        assertThat(access.decide(userId, null, AccountAccessKind.READ))
                .isEqualTo(AccessDecision.DENIED);
        assertThat(access.decide(userId, userId, null)).isEqualTo(AccessDecision.DENIED);
    }

    @Test
    void 세션_생성은_ACTIVE에서만_허용된다() {
        UUID active = lifecycle.createAccount();
        UUID deleting = lifecycle.createAccount();
        deletion.beginDeletion(deleting);

        assertThat(lifecycle.decideSessionCreation(active)).isEqualTo(AccessDecision.ALLOWED);
        assertThat(lifecycle.decideSessionCreation(deleting)).isEqualTo(AccessDecision.DENIED);
        assertThat(lifecycle.decideSessionCreation(UUID.randomUUID()))
                .isEqualTo(AccessDecision.DENIED);
        assertThat(lifecycle.decideSessionCreation(null)).isEqualTo(AccessDecision.DENIED);
    }

    @Test
    void 매핑은_한_계정에_한_번만_연결된다() {
        UUID owner = lifecycle.createAccount();
        UUID other = lifecycle.createAccount();
        String issuer = "https://accounts.example/" + UUID.randomUUID();
        String subject = UUID.randomUUID().toString();

        assertThat(lifecycle.linkSocialIdentity(owner, "google", issuer, subject))
                .isEqualTo(AccessDecision.ALLOWED);

        // 같은 `(issuer, subject)`가 다른 계정으로 옮겨 붙지 않는다. 어느 계정에 있었는지도 밝히지
        // 않으며, 거부 값은 다른 모든 거부와 같다.
        assertThat(lifecycle.linkSocialIdentity(other, "google", issuer, subject))
                .isEqualTo(AccessDecision.DENIED);

        // 같은 `(user_id, provider)`도 두 번 연결되지 않는다.
        assertThat(
                        lifecycle.linkSocialIdentity(
                                owner, "google", issuer, UUID.randomUUID().toString()))
                .isEqualTo(AccessDecision.DENIED);

        // 거부한 호출은 아무것도 남기지 않는다.
        assertThat(countLinks(owner)).isEqualTo(1L);
        assertThat(countLinks(other)).isZero();
    }

    @Test
    void 삭제된_계정에는_매핑을_다시_연결하지_못한다() {
        UUID userId = lifecycle.createAccount();
        deletion.beginDeletion(userId);
        deletion.erasePayload(userId);

        assertThat(
                        lifecycle.linkSocialIdentity(
                                userId,
                                "google",
                                "https://accounts.example/" + UUID.randomUUID(),
                                UUID.randomUUID().toString()))
                .isEqualTo(AccessDecision.DENIED);
        assertThat(countLinks(userId)).isZero();
        assertThat(storedState(userId)).isEqualTo("DELETED");
    }

    private long countLinks(UUID userId) {
        return jdbc
                .sql("SELECT count(*) FROM identity_social_link WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
