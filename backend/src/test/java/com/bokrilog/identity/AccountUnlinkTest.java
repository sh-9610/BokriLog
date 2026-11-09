package com.bokrilog.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 인증 매핑 해제와 마지막 수단 보호.
 *
 * <p>{@code AccountLinkedProvidersTest}와 같은 배선이다 — 실제 PostgreSQL로 돌고, 모든 시험이 자기
 * 계정을 새로 만들어 써서 시험 간 정리도 실행 순서 의존도 없다.
 *
 * <p><b>마지막 수단 보호의 단일 소유자다.</b> 그 판정이 삭제 문장 안에 있다는 것이 Story 1.11의
 * 중심 결정이고, 그 사실이 관측되는 층은 여기 하나다 — HTTP 층은 그 답이 거부 하나로 접히는지만
 * 본다. 동시 해제 축은 {@code AccountConcurrencyTest}가 소유한다(잠금 대기열을 세우는 도구가 그
 * 파일에 있다).
 *
 * <p>거부가 사유를 구분하지 않는다는 계약도 여기서 고정된다. 마지막 수단·미연결 제공자·삭제 진행·
 * 삭제 완료·미존재 계정·받아들일 수 없는 값이 모두 같은 {@link AccessDecision#DENIED}이며, 호출자는
 * 그 값에서 대상 계정이 어떤 수단을 몇 개 갖고 있었는지 복원할 수 없다.
 */
class AccountUnlinkTest extends IdentityKeyedTestBase {

    @Autowired private AccountLifecycleApi lifecycle;

    @Autowired private AccountDeletionApi deletion;

    @Autowired private JdbcClient jdbc;

    @Test
    void 수단이_둘이면_하나를_해제할_수_있다() {
        UUID userId = accountWith("google", "naver");

        assertThat(lifecycle.unlinkSocialIdentity(userId, "naver")).isEqualTo(AccessDecision.ALLOWED);

        assertThat(lifecycle.findLinkedProviders(userId)).containsExactly("google");
    }

    @Test
    void 수단이_셋이면_둘까지_해제할_수_있고_마지막_하나는_남는다() {
        UUID userId = accountWith("google", "naver", "kakao");

        assertThat(lifecycle.unlinkSocialIdentity(userId, "kakao")).isEqualTo(AccessDecision.ALLOWED);
        assertThat(lifecycle.unlinkSocialIdentity(userId, "naver")).isEqualTo(AccessDecision.ALLOWED);
        /*
         * 셋째 해제는 거부다. 같은 호출을 반복해도 마지막 하나는 남는다 — 이 세 줄이 곧 "사용자는
         * 자기 계정에서 잠기지 않는다"의 정의다.
         */
        assertThat(lifecycle.unlinkSocialIdentity(userId, "google")).isEqualTo(AccessDecision.DENIED);

        assertThat(lifecycle.findLinkedProviders(userId)).containsExactly("google");
    }

    @Test
    void 마지막_수단은_해제되지_않는다() {
        UUID userId = accountWith("google");

        assertThat(lifecycle.unlinkSocialIdentity(userId, "google")).isEqualTo(AccessDecision.DENIED);

        // 삭제 0행이다. 행 수가 그대로인 것이 이 계약의 관측 가능한 형태다.
        assertThat(lifecycle.findLinkedProviders(userId)).containsExactly("google");
    }

    @Test
    void 연결되지_않은_제공자의_해제는_같은_거부다() {
        UUID userId = accountWith("google", "naver");

        assertThat(lifecycle.unlinkSocialIdentity(userId, "kakao")).isEqualTo(AccessDecision.DENIED);

        /*
         * "마지막 수단이라 거부됐다"와 "그 제공자가 애초에 없었다"가 같은 값이다. 값을 나누면 그
         * 차이가 곧 계정의 수단 구성을 알려 주는 신호가 된다.
         */
        assertThat(lifecycle.findLinkedProviders(userId)).containsExactlyInAnyOrder("google", "naver");
    }

    @Test
    void 삭제가_시작된_계정은_해제되지_않는다() {
        UUID userId = accountWith("google", "naver");
        assertThat(deletion.beginDeletion(userId)).isEqualTo(DeletionOutcome.COMPLETED);

        assertThat(lifecycle.unlinkSocialIdentity(userId, "naver")).isEqualTo(AccessDecision.DENIED);

        /*
         * 상태 술어가 DELETE 안에 있다. 삭제가 시작된 계정의 매핑 행은 아직 남아 있고, 그 행을
         * 지우는 것은 삭제 경로(`erasePayload`)의 일이지 이 API의 일이 아니다.
         */
        assertThat(countLinks(userId)).isEqualTo(2L);
    }

    @Test
    void 삭제가_끝난_계정과_미존재_계정도_같은_거부다() {
        UUID deleted = accountWith("google", "naver");
        deletion.beginDeletion(deleted);
        deletion.erasePayload(deleted);

        assertThat(lifecycle.unlinkSocialIdentity(deleted, "naver")).isEqualTo(AccessDecision.DENIED);
        assertThat(lifecycle.unlinkSocialIdentity(UUID.randomUUID(), "google"))
                .isEqualTo(AccessDecision.DENIED);
    }

    @Test
    void 받아들일_수_없는_값은_예외가_아니라_같은_거부다() {
        UUID userId = accountWith("google", "naver");

        assertThat(lifecycle.unlinkSocialIdentity(null, "naver")).isEqualTo(AccessDecision.DENIED);
        assertThat(lifecycle.unlinkSocialIdentity(userId, null)).isEqualTo(AccessDecision.DENIED);
        assertThat(lifecycle.unlinkSocialIdentity(userId, "   ")).isEqualTo(AccessDecision.DENIED);
        assertThat(lifecycle.unlinkSocialIdentity(userId, "x".repeat(200)))
                .isEqualTo(AccessDecision.DENIED);

        assertThat(lifecycle.findLinkedProviders(userId)).containsExactlyInAnyOrder("google", "naver");
    }

    @Test
    void 다른_사용자의_매핑은_건드리지_않는다() {
        UUID mine = accountWith("google", "naver");
        UUID theirs = accountWith("google", "naver");

        assertThat(lifecycle.unlinkSocialIdentity(mine, "naver")).isEqualTo(AccessDecision.ALLOWED);

        /*
         * 삭제 술어가 `user_id`로 좁혀져 있다. 그 축이 빠지면 같은 제공자로 연결된 모든 계정의
         * 매핑이 함께 사라지며, 그것은 이 시험 하나로만 드러난다.
         */
        assertThat(lifecycle.findLinkedProviders(mine)).containsExactly("google");
        assertThat(lifecycle.findLinkedProviders(theirs)).containsExactlyInAnyOrder("google", "naver");
    }

    @Test
    void 해제된_식별자는_어느_계정에도_닿지_않는다() {
        UUID userId = lifecycle.createAccount();
        String issuer = "https://accounts.example/" + UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        assertThat(lifecycle.linkSocialIdentity(userId, "google", issuer, subject))
                .isEqualTo(AccessDecision.ALLOWED);
        assertThat(
                        lifecycle.linkSocialIdentity(
                                userId,
                                "naver",
                                "https://naver.example/" + UUID.randomUUID(),
                                UUID.randomUUID().toString()))
                .isEqualTo(AccessDecision.ALLOWED);

        assertThat(lifecycle.unlinkSocialIdentity(userId, "google")).isEqualTo(AccessDecision.ALLOWED);

        /*
         * 그 `(issuer, subject)`로는 이 계정에 닿을 수 없다. 자동 재연결도 없다 — 되살리는 경로가
         * 공개 API에 없으므로 같은 식별자로 다시 로그인하면 신규 가입 경로를 지난다.
         */
        assertThat(lifecycle.findUserIdBySocialIdentity(issuer, subject)).isEmpty();
        // 전역 유일 슬롯도 비었다. 다른 계정이 그 자리를 쓸 수 있다.
        UUID other = lifecycle.createAccount();
        assertThat(lifecycle.linkSocialIdentity(other, "google", issuer, subject))
                .isEqualTo(AccessDecision.ALLOWED);
        assertThat(lifecycle.findUserIdBySocialIdentity(issuer, subject)).contains(other);
    }

    @Test
    void 해제_뒤_계정_삭제는_남은_매핑을_지우고_다른_사용자는_보존한다() {
        UUID mine = accountWith("google", "naver");
        UUID theirs = accountWith("kakao", "naver");
        assertThat(lifecycle.unlinkSocialIdentity(mine, "naver")).isEqualTo(AccessDecision.ALLOWED);

        deletion.beginDeletion(mine);
        deletion.erasePayload(mine);

        assertThat(lifecycle.findLinkedProviders(mine)).isEmpty();
        assertThat(lifecycle.findLinkedProviders(theirs)).containsExactlyInAnyOrder("kakao", "naver");
    }

    /** 그 제공자들이 연결된 새 계정. 제공자마다 다른 issuer를 써서 전역 유일 슬롯이 겹치지 않는다. */
    private UUID accountWith(String... providers) {
        UUID userId = lifecycle.createAccount();
        for (String provider : providers) {
            assertThat(
                            lifecycle.linkSocialIdentity(
                                    userId,
                                    provider,
                                    "https://" + provider + ".example/" + UUID.randomUUID(),
                                    UUID.randomUUID().toString()))
                    .isEqualTo(AccessDecision.ALLOWED);
        }
        return userId;
    }

    /**
     * 그 계정의 매핑 행 수.
     *
     * <p>공개 API로는 볼 수 없는 사실 하나가 있다 — 삭제가 시작된 계정의 매핑 행은 아직 남아 있지만
     * {@code findLinkedProviders}는 상태 술어 때문에 빈 집합을 돌려준다. "거부가 아무것도 지우지
     * 않았다"를 그 상태에서 확인하려면 행을 직접 세는 수밖에 없고, 그래서 이 파일이 저장소를 읽는
     * 자리는 여기 하나다({@code AccountConcurrencyTest.countLinks}와 같은 관용).
     */
    private long countLinks(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM identity_social_link WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
