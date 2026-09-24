package com.bokrilog.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.PostgresTestcontainersConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 연결된 제공자 조회.
 *
 * <p>{@code AccountAccessDecisionTest}와 같은 배선이다 — 실제 PostgreSQL로 돌고, 모든 시험이 자기
 * 계정을 새로 만들어 써서 시험 간 정리도 실행 순서 의존도 없다.
 *
 * <p>이 조회가 Story 1.10의 Ask First였다. 설정 화면이 "어느 수단이 연결됐는가"를 알아야 하는데
 * 루트 패키지가 {@code identity_social_link}를 직접 읽는 길은 {@code ModuleBoundaryTest}가 막기
 * 때문이다. 그래서 여는 표면을 <b>제공자 이름 집합</b> 하나로 좁혔고, 이 파일이 그 좁음을 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestcontainersConfig.class)
class AccountLinkedProvidersTest {

    @Autowired private AccountLifecycleApi lifecycle;

    @Autowired private AccountDeletionApi deletion;

    @Test
    void 연결이_없으면_빈_집합이다() {
        UUID userId = lifecycle.createAccount();

        assertThat(lifecycle.findLinkedProviders(userId)).isEmpty();
    }

    @Test
    void 연결된_제공자_이름만_돌려준다() {
        UUID userId = lifecycle.createAccount();
        String issuer = "https://accounts.example/" + UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        assertThat(lifecycle.linkSocialIdentity(userId, "google", issuer, subject))
                .isEqualTo(AccessDecision.ALLOWED);

        assertThat(lifecycle.findLinkedProviders(userId)).containsExactly("google");
        /*
         * 외부 식별자가 반환에 없다. 저장소 질의가 `provider` 하나만 고르므로 담을 수도 없고,
         * 그것이 이 API를 여는 조건이었다.
         */
        assertThat(lifecycle.findLinkedProviders(userId)).doesNotContain(issuer, subject);
    }

    @Test
    void 세_제공자가_모두_연결된_계정은_셋을_돌려준다() {
        UUID userId = lifecycle.createAccount();
        for (String provider : new String[] {"google", "naver", "kakao"}) {
            assertThat(
                            lifecycle.linkSocialIdentity(
                                    userId,
                                    provider,
                                    "https://" + provider + ".example/" + UUID.randomUUID(),
                                    UUID.randomUUID().toString()))
                    .isEqualTo(AccessDecision.ALLOWED);
        }

        assertThat(lifecycle.findLinkedProviders(userId))
                .containsExactlyInAnyOrder("google", "naver", "kakao");
    }

    @Test
    void 다른_사용자의_연결은_보이지_않는다() {
        UUID mine = lifecycle.createAccount();
        UUID theirs = lifecycle.createAccount();
        assertThat(
                        lifecycle.linkSocialIdentity(
                                theirs,
                                "naver",
                                "https://accounts.example/" + UUID.randomUUID(),
                                UUID.randomUUID().toString()))
                .isEqualTo(AccessDecision.ALLOWED);

        assertThat(lifecycle.findLinkedProviders(mine)).isEmpty();
        assertThat(lifecycle.findLinkedProviders(theirs)).containsExactly("naver");
    }

    @Test
    void 삭제_진행과_삭제_완료는_모두_빈_집합이다() {
        UUID deleting = lifecycle.createAccount();
        UUID deleted = lifecycle.createAccount();
        for (UUID userId : new UUID[] {deleting, deleted}) {
            assertThat(
                            lifecycle.linkSocialIdentity(
                                    userId,
                                    "google",
                                    "https://accounts.example/" + UUID.randomUUID(),
                                    UUID.randomUUID().toString()))
                    .isEqualTo(AccessDecision.ALLOWED);
        }
        deletion.beginDeletion(deleting);
        deletion.beginDeletion(deleted);
        deletion.erasePayload(deleted);

        /*
         * 삭제가 시작된 계정은 매핑 행이 아직 남아 있지만 상태 술어가 질의 안에 있어 0행이고,
         * 삭제가 끝난 계정은 매핑 자체가 없어 역시 0행이다. 두 경우와 "그런 계정이 없다"가 호출자
         * 에게 같은 빈 집합으로 보이는 것이 이 계약이다.
         */
        assertThat(lifecycle.findLinkedProviders(deleting)).isEmpty();
        assertThat(lifecycle.findLinkedProviders(deleted)).isEmpty();
    }

    @Test
    void 미존재_계정과_null도_같은_빈_집합이다() {
        assertThat(lifecycle.findLinkedProviders(UUID.randomUUID())).isEmpty();
        assertThat(lifecycle.findLinkedProviders(null)).isEmpty();
    }
}
