package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.ActionGrantService.Consumption;
import com.bokrilog.ActionGrantService.ConsumptionRequest;
import com.bokrilog.identity.DeletionOutcome;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재인증 허가의 발급 게이트·다섯 귀속·만료 경계·무효화.
 *
 * <p>AC-1·AC-2·AC-4·AC-5의 단일 소유자다. 트랜잭션 경계와 {@code operation_id} 멱등은
 * {@code ActionGrantTransactionTest}가, 동시 소비는 {@code ActionGrantConcurrencyTest}가 소유한다.
 *
 * <p><b>시각 경계는 실제 PostgreSQL에서만 볼 수 있다.</b> 시각의 권위가 데이터베이스 하나이므로
 * 자바 시계를 조작해서는 이 축을 만들 수 없고, 대신 저장소의 시각을 {@code now()} 기준 상대 위치로
 * 옮기거나({@code AuthFlowTestBase.moveActionGrantExpiry}) 좌표를
 * {@code AuthFlowTestBase.databaseNow}에서 가져온다. 그 seam이 공짜로 생긴 것이 판정을 DB로 옮긴
 * 결정의 대가이자 이득이다.
 */
class ActionGrantTest extends AuthFlowTestBase {

    @Autowired private ActionGrantService actionGrants;

    @Autowired private TransactionTemplate transactionTemplate;

    // ----------------------------------------------------------------------
    // 발급 게이트
    // ----------------------------------------------------------------------

    @Test
    void 재인증이_신선한_세션은_허가를_받는다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);

        String reference = issue(session, userId).orElseThrow();

        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(actionGrantExists(reference)).isTrue();
        assertThat(actionGrantConsumed(reference)).isFalse();
        // 수명은 발급이 못 박는다. 창 안이면서 5분을 넘지 않는다. 비교도 데이터베이스가 한다 —
        // JVM 시계와 컨테이너 시계를 견주면 이 단정이 시차로 흔들린다.
        assertThat(expiresWithinLifetime(reference)).isTrue();
    }

    @Test
    void 재인증_시각이_없는_세션은_발급받지_못한다() {
        UUID userId = accountLifecycle().createAccount();
        // 무장도 재인증도 지나지 않은 세션이다. 로그인만으로는 이 속성이 생기지 않는다.
        HttpSession session =
                grantSession(UUID.randomUUID().toString(), userId, null, Duration.ZERO);

        assertThat(issue(session, userId)).isEmpty();
        assertThat(actionGrantCount()).isZero();
    }

    @Test
    void 재인증_창의_네_경계가_발급을_가른다() {
        UUID userId = accountLifecycle().createAccount();
        // 좌표를 데이터베이스에서 가져온다. 판정의 두 피연산자 중 하나가 `now()`이므로 자바
        // 시계로 좌표를 잡으면 경계가 시차만큼 흔들린다.
        Instant databaseNow = databaseNow();

        // 창 안. 5분에서 30초 모자란다.
        assertThat(issueAt(userId, databaseNow.minus(ActionGrant.REAUTHENTICATION_WINDOW).plusSeconds(30)))
                .isPresent();
        /*
         * 정확히 5분. INSERT의 `now()`는 위 좌표보다 뒤이므로 이 값은 창 밖이다.
         *
         * 여기서 `>`와 `>=`는 구분되지 않는다(변이로 확인했다). 비교 대상 하나가 `now()`라 좌표를
         * 잡는 순간과 판정하는 순간 사이에 경계가 움직이고, 두 술어가 갈리는 지점은 그 순간
         * 하나뿐이라 밖에서 맞출 수 없다. 만료 경계는 사정이 다르다 — 그 좌표는 행에 저장돼 움직이지
         * 않으므로 `정확히_만료_시각이면_소비되지_않는다`가 그 한 점을 실제로 고정한다.
         */
        assertThat(issueAt(userId, databaseNow.minus(ActionGrant.REAUTHENTICATION_WINDOW))).isEmpty();
        // 창 밖.
        assertThat(issueAt(userId, databaseNow.minus(Duration.ofMinutes(6)))).isEmpty();
        // skew 안의 미래. 앞선 시계를 가진 인스턴스가 기록한 값이 이 모양이다.
        assertThat(issueAt(userId, databaseNow.plusSeconds(10))).isPresent();
        /*
         * skew 밖의 미래. 재인증 시각만은 애플리케이션이 쓰는 값이라 상한이 없으면 앞선 시계가
         * 기록한 값이 창을 영원히 만족한다.
         */
        assertThat(issueAt(userId, databaseNow.plus(Duration.ofMinutes(5)))).isEmpty();
    }

    @Test
    void 의심_세션은_발급받지_못한다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        session.setAttribute(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE, Boolean.TRUE);

        assertThat(issue(session, userId)).isEmpty();
        assertThat(actionGrantCount()).isZero();
    }

    @Test
    void 절대_수명을_넘긴_세션은_발급도_소비도_못_한다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession live = grantSession(userId);
        String reference = issue(live, userId).orElseThrow();

        // 같은 세션 id를 그대로 두고 생성 시각만 절대 수명 너머로 옮긴다. 판정이 생성 시각을
        // 보지 않으면 이 두 단정이 함께 통과해 버린다.
        HttpSession aged =
                grantSession(
                        live.getId(),
                        userId,
                        Instant.now(),
                        sessionGuard().absoluteLifetime().plusMinutes(1));

        assertThat(issue(aged, userId)).isEmpty();
        assertThat(consume(aged, userId, reference, operationId()))
                .isInstanceOf(Consumption.Denied.class);
        assertThat(actionGrantConsumed(reference)).isFalse();
        // 허가 행은 하나뿐이다. 거부된 발급이 아무것도 남기지 않았다.
        assertThat(actionGrantCount()).isEqualTo(1);
    }

    @Test
    void 세션의_주체와_다른_사용자로는_발급도_소비도_못_한다() {
        UUID userId = accountLifecycle().createAccount();
        UUID otherUserId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId).orElseThrow();

        // 두 값 모두 호출자가 건넨다. 대조가 없으면 "세션이 유일한 user_id 권위"는 문장으로만 남는다.
        assertThat(issue(session, otherUserId)).isEmpty();
        assertThat(consume(session, otherUserId, reference, operationId()))
                .isInstanceOf(Consumption.Denied.class);
        assertThat(actionGrantConsumed(reference)).isFalse();
    }

    @Test
    void 주체가_없는_세션으로는_발급도_소비도_못_한다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession established = grantSession(userId);
        String reference = issue(established, userId).orElseThrow();

        // 로그인을 개설하지 않은 세션이다. 진행 중인 OIDC 흐름의 중간 결과도 여기서 걸러진다.
        HttpSession anonymous =
                grantSession(established.getId(), null, Instant.now(), Duration.ZERO);

        assertThat(issue(anonymous, userId)).isEmpty();
        assertThat(consume(anonymous, userId, reference, operationId()))
                .isInstanceOf(Consumption.Denied.class);
        assertThat(actionGrantConsumed(reference)).isFalse();
    }

    @Test
    void 세션이_없거나_무효화됐으면_예외가_아니라_거부다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId).orElseThrow();

        MockHttpSession invalidated = grantSession(session.getId(), userId);
        invalidated.invalidate();

        // 로그아웃·절대 만료가 이 요청과 겹치면 접근자가 IllegalStateException을 던진다. 세션이
        // 아예 없으면 null이다. 둘 다 구분되지 않는 거부 하나로 수렴한다.
        assertThat(this.actionGrants.issue(null, userId, GRANT_COMMAND, GRANT_TARGET, GRANT_CONFIRMATION))
                .isEmpty();
        assertThat(issue(invalidated, userId)).isEmpty();
        assertThat(consume(invalidated, userId, reference, operationId()))
                .isInstanceOf(Consumption.Denied.class);
        assertThat(actionGrantConsumed(reference)).isFalse();
    }

    @Test
    void 삭제가_시작된_계정은_발급받지_못한다() {
        UUID userId = accountLifecycle().createAccount();
        assertThat(accountDeletion().beginDeletion(userId)).isEqualTo(DeletionOutcome.COMPLETED);

        assertThat(issue(grantSession(userId), userId)).isEmpty();
        assertThat(actionGrantCount()).isZero();
    }

    @Test
    void 판정과_발급_사이에_payload가_사라지면_거부_하나로_수렴한다() {
        UUID userId = accountLifecycle().createAccount();
        /*
         * 삭제 판정은 생명주기 행을 보고 외래키는 payload 행을 본다. 둘을 갈라 놓으면 "판정은
         * 통과했는데 INSERT가 외래키에 걸리는" 경합의 결과를 결정적으로 만들 수 있다. 실제로는
         * `erasePayload`가 두 행을 함께 옮기므로 이 상태는 그 사이의 순간에만 존재한다.
         */
        jdbc().sql("DELETE FROM identity_account WHERE user_id = :userId")
                .param("userId", userId)
                .update();

        // 외래키 위반이 예외로 새면 호출자는 거부가 아니라 500을 본다.
        assertThat(issue(grantSession(userId), userId)).isEmpty();
        assertThat(actionGrantCount()).isZero();
    }

    @Test
    void 발급은_같은_작업의_살아_있는_허가를_무효화한다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String first = issue(session, userId).orElseThrow();

        String second = issue(session, userId).orElseThrow();

        /*
         * 재인증 속성은 발급 뒤에도 남으므로 창 안에서 반복 발급이 가능하고, 그렇게 생긴 허가는
         * 각자 다시 5분을 산다. 그대로 두면 본인 확인 한 번이 같은 작업의 실행 권한을 여럿 만든다.
         */
        assertThat(actionGrantExists(first)).isFalse();
        assertThat(actionGrantExists(second)).isTrue();
        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(consume(session, userId, first, operationId()))
                .isInstanceOf(Consumption.Denied.class);
    }

    @Test
    void 다른_작업의_허가는_무효화되지_않는다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String other =
                this.actionGrants
                        .issue(session, userId, "unlink-provider", GRANT_TARGET, GRANT_CONFIRMATION)
                        .orElseThrow();

        String mine = issue(session, userId).orElseThrow();

        // 무효화는 같은 `(command, target)`에만 미친다.
        assertThat(actionGrantExists(other)).isTrue();
        assertThat(actionGrantExists(mine)).isTrue();
    }

    @Test
    void 발급_경로가_자기_사용자의_만료된_미소비_허가를_쓸어_낸다() {
        UUID mine = accountLifecycle().createAccount();
        UUID theirs = accountLifecycle().createAccount();
        HttpSession mySession = grantSession(mine);
        String myStale =
                this.actionGrants
                        .issue(mySession, mine, "unlink-provider", GRANT_TARGET, GRANT_CONFIRMATION)
                        .orElseThrow();
        String theirStale = issue(grantSession(theirs), theirs).orElseThrow();
        moveActionGrantExpiry(myStale, Duration.ofSeconds(-1));
        moveActionGrantExpiry(theirStale, Duration.ofSeconds(-1));

        String fresh = issue(mySession, mine).orElseThrow();

        // 스케줄러도 배치도 없다. 발급 경로 하나가 자기 사용자 범위에서 정리한다.
        assertThat(actionGrantExists(myStale)).isFalse();
        assertThat(actionGrantExists(fresh)).isTrue();
        // 범위가 전체 테이블이면 남의 행까지 지운다. 발급 하나의 비용이 테이블 크기에 비례하는 것도
        // 그 모양이다.
        assertThat(actionGrantExists(theirStale)).isTrue();
    }

    @Test
    void 소비된_허가는_만료돼도_쓸려_나가지_않는다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String consumed = issue(session, userId).orElseThrow();
        assertThat(consume(session, userId, consumed, operationId()))
                .isInstanceOf(Consumption.ConsumedRequiringSessionRotation.class);
        moveActionGrantExpiry(consumed, Duration.ofSeconds(-1));

        issue(session, userId).orElseThrow();

        /*
         * 그 행의 `operation_id`와 `result`가 멱등 재전송의 유일한 근거다. 지우면 응답을 잃은
         * 클라이언트의 재전송이 저장된 결과 대신 거부를 받는다.
         */
        assertThat(actionGrantExists(consumed)).isTrue();
    }

    // ----------------------------------------------------------------------
    // 불투명 참조
    // ----------------------------------------------------------------------

    @Test
    void 허가_참조에서는_아무것도_복원할_수_없다() {
        UUID userId = accountLifecycle().createAccount();
        String sessionId = UUID.randomUUID().toString();
        HttpSession session = grantSession(sessionId, userId);

        String first = issue(session, userId).orElseThrow();
        String second = issue(session, userId).orElseThrow();

        // 난수뿐이다. 사용자·세션·작업·대상 어느 것도 값 안에 없다.
        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(first)
                .doesNotContain(userId.toString())
                .doesNotContain(sessionId)
                .doesNotContain(GRANT_COMMAND)
                .doesNotContain(GRANT_TARGET)
                .doesNotContain(GRANT_CONFIRMATION);
        assertThat(first).doesNotContain(confirmationHash());
        // 같은 입력이라도 값이 다르다. 참조 자체가 입력의 함수가 아니다.
        assertThat(first).isNotEqualTo(second);
    }

    // ----------------------------------------------------------------------
    // 다섯 귀속
    // ----------------------------------------------------------------------

    @Test
    void 다섯_귀속이_모두_맞아야_소비된다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId).orElseThrow();
        HttpSession otherSession = grantSession(UUID.randomUUID().toString(), userId);

        // session — 같은 사용자의 다른 세션이다. 주체 대조는 통과하고 귀속만 어긋난다.
        assertThat(consume(otherSession, userId, reference, operationId()))
                .isInstanceOf(Consumption.Denied.class);
        // command
        assertThat(
                        request(
                                session, userId, reference, "unlink-provider", GRANT_TARGET, GRANT_CONFIRMATION))
                .isInstanceOf(Consumption.Denied.class);
        // target
        assertThat(request(session, userId, reference, GRANT_COMMAND, "kakao", GRANT_CONFIRMATION))
                .isInstanceOf(Consumption.Denied.class);
        // confirmation hash
        assertThat(request(session, userId, reference, GRANT_COMMAND, GRANT_TARGET, "해제합니다"))
                .isInstanceOf(Consumption.Denied.class);
        // 참조 자체
        assertThat(consume(session, userId, ActionGrant.newReference(), operationId()))
                .isInstanceOf(Consumption.Denied.class);

        // 다섯 번의 거부가 아무것도 바꾸지 않았다(user 축은 위 주체 대조 시험이 소유한다).
        assertThat(actionGrantConsumed(reference)).isFalse();

        assertThat(consume(session, userId, reference, operationId()))
                .isInstanceOf(Consumption.ConsumedRequiringSessionRotation.class);
        assertThat(actionGrantConsumed(reference)).isTrue();
    }

    @Test
    void command와_target은_저장과_해시가_같은_정규화를_지난다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);

        // 양 끝 공백이 붙은 값으로 발급한다. 한쪽만 정규화하면 해시는 맞고 컬럼은 어긋나
        // 영영 소비할 수 없는 허가가 된다.
        String reference =
                this.actionGrants
                        .issue(session, userId, "  " + GRANT_COMMAND, GRANT_TARGET + "\n", GRANT_CONFIRMATION)
                        .orElseThrow();

        assertThat(consume(session, userId, reference, operationId()))
                .isInstanceOf(Consumption.ConsumedRequiringSessionRotation.class);
    }

    @Test
    void 확인_문구는_정규화된_뒤에만_같은_값이다() {
        // 양 끝 공백은 접히고 대소문자는 접히지 않는다. 사용자가 화면에 보이는 그대로 옮겨 적는
        // 값이므로 대소문자를 접으면 서로 다른 확인이 같은 확인이 된다.
        assertThat(
                        ActionGrant.canonicalConfirmationHash(
                                GRANT_COMMAND, GRANT_TARGET, "  " + GRANT_CONFIRMATION + "\n"))
                .isEqualTo(confirmationHash());
        assertThat(ActionGrant.canonicalConfirmationHash(GRANT_COMMAND, GRANT_TARGET, "delete"))
                .isNotEqualTo(ActionGrant.canonicalConfirmationHash(GRANT_COMMAND, GRANT_TARGET, "DELETE"));
        // 길이 접두사가 없으면 아래 두 쌍이 같은 바이트열이 되어 서로 다른 확인이 같은 해시를 갖는다.
        assertThat(ActionGrant.canonicalConfirmationHash("ab", "c", GRANT_CONFIRMATION))
                .isNotEqualTo(ActionGrant.canonicalConfirmationHash("a", "bc", GRANT_CONFIRMATION));
        // 값이 하나 바뀌면 해시가 바뀐다. 세 값이 모두 들어간다는 뜻이다.
        assertThat(confirmationHash())
                .isNotEqualTo(
                        ActionGrant.canonicalConfirmationHash(GRANT_COMMAND, "kakao", GRANT_CONFIRMATION));
        assertThat(confirmationHash()).hasSize(64);
    }

    // ----------------------------------------------------------------------
    // 값 거부 게이트
    // ----------------------------------------------------------------------

    @Test
    void 상한을_넘거나_빈_값은_예외가_아니라_거부다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId).orElseThrow();
        String longCommand = "c".repeat(ActionGrant.MAX_COMMAND_BYTES + 1);
        String longTarget = "t".repeat(ActionGrant.MAX_TARGET_BYTES + 1);
        String longConfirmation = "화".repeat(ActionGrant.MAX_CONFIRMATION_BYTES);

        /*
         * 이 게이트가 없으면 같은 입력이 스키마 CHECK 위반으로 나타나고, 그 예외는 거부 값이 아니라
         * 트랜잭션을 abort시키는 오류라 호출자의 작업까지 사라진다.
         */
        assertThat(this.actionGrants.issue(session, userId, longCommand, GRANT_TARGET, GRANT_CONFIRMATION))
                .isEmpty();
        assertThat(this.actionGrants.issue(session, userId, GRANT_COMMAND, longTarget, GRANT_CONFIRMATION))
                .isEmpty();
        assertThat(this.actionGrants.issue(session, userId, GRANT_COMMAND, GRANT_TARGET, longConfirmation))
                .isEmpty();
        assertThat(this.actionGrants.issue(session, userId, "  ", GRANT_TARGET, GRANT_CONFIRMATION))
                .isEmpty();

        assertThat(request(session, userId, reference, longCommand, GRANT_TARGET, GRANT_CONFIRMATION))
                .isInstanceOf(Consumption.Denied.class);
        assertThat(consume(session, userId, reference, " ")).isInstanceOf(Consumption.Denied.class);
        assertThat(consume(session, userId, reference, "o".repeat(ActionGrant.MAX_OPERATION_ID_BYTES + 1)))
                .isInstanceOf(Consumption.Denied.class);
        assertThat(consume(session, userId, "", operationId())).isInstanceOf(Consumption.Denied.class);
        assertThat(
                        consume(
                                session,
                                userId,
                                "r".repeat(ActionGrant.MAX_GRANT_REFERENCE_BYTES + 1),
                                operationId()))
                .isInstanceOf(Consumption.Denied.class);

        // 거부가 아무것도 바꾸지 않았다.
        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(actionGrantConsumed(reference)).isFalse();
    }

    @Test
    void 애플리케이션_상한과_스키마_CHECK가_같은_숫자다() throws IOException {
        String migration = migrationSource();
        Map<String, Integer> expected =
                Map.of(
                        "grant_id", ActionGrant.MAX_GRANT_REFERENCE_BYTES,
                        "session_id", ActionGrant.MAX_SESSION_ID_BYTES,
                        "command", ActionGrant.MAX_COMMAND_BYTES,
                        "target", ActionGrant.MAX_TARGET_BYTES,
                        "operation_id", ActionGrant.MAX_OPERATION_ID_BYTES,
                        "result", ActionGrant.MAX_RESULT_BYTES);

        /*
         * 두 숫자가 갈리면 애플리케이션이 값으로 거부하던 입력이 데이터베이스 오류로 바뀌고, 그
         * 오류는 호출자의 트랜잭션을 통째로 지운다. 한쪽만 고치는 변경을 여기서 막는다.
         */
        expected.forEach(
                (column, limit) ->
                        assertThat(upperBoundIn(migration, column))
                                .describedAs("V3의 %s 상한", column)
                                .isEqualTo(limit));
        // 확인 문구는 컬럼이 아니라 해시로만 남는다. 대응 CHECK가 없는 것이 맞고, 해시 자체의
        // 길이는 생산자가 하나라 등식이다.
        assertThat(migration).contains("octet_length(confirmation_hash) = 64");
    }

    @Test
    void 정책_값은_SPEC이_소유한다() {
        /*
         * 네 값의 변경은 SPEC의 Ask First다. Story 1.8의 상수를 재사용하지 않는 것도 여기서
         * 고정한다 — 같은 숫자를 공유하면 OIDC 프로토콜 값이 바뀌는 날 허가 정책이 조용히 따라
         * 움직인다.
         */
        assertThat(ActionGrant.LIFETIME).isEqualTo(Duration.ofMinutes(5));
        assertThat(ActionGrant.REAUTHENTICATION_WINDOW).isEqualTo(Duration.ofMinutes(5));
        assertThat(ActionGrant.REAUTHENTICATION_FUTURE_SKEW).isEqualTo(Duration.ofSeconds(60));
        assertThat(ActionGrant.REFERENCE_BYTES).isEqualTo(32);
    }

    // ----------------------------------------------------------------------
    // 만료 경계
    // ----------------------------------------------------------------------

    @Test
    void 발급_직후에는_소비된다() {
        Fixture fixture = issued();

        assertThat(fixture.consume(operationId()))
                .isInstanceOf(Consumption.ConsumedRequiringSessionRotation.class);
    }

    @Test
    void 만료_직전에는_소비된다() {
        Fixture fixture = issued();
        // 창의 끝자락이되 러너가 느려도 넘지 않을 만큼은 남긴다. 반대편 경계는 아래 두 시험이
        // `now()` 기준으로 정확히 고정하므로 이 값이 커도 관측하는 것이 흐려지지 않는다.
        moveActionGrantExpiry(fixture.reference(), Duration.ofSeconds(30));

        assertThat(fixture.consume(operationId()))
                .isInstanceOf(Consumption.ConsumedRequiringSessionRotation.class);
    }

    @Test
    void 정확히_만료_시각이면_소비되지_않는다() {
        Fixture fixture = issued();
        // `now() >= expires_at`이 만료다. 소비의 `now()`는 이 문장보다 뒤이므로 경계가 닫힌 쪽이다.
        moveActionGrantExpiry(fixture.reference(), Duration.ZERO);

        assertThat(fixture.consume(operationId())).isInstanceOf(Consumption.Denied.class);
        assertThat(actionGrantConsumed(fixture.reference())).isFalse();
    }

    @Test
    void 만료된_뒤에는_소비되지_않는다() {
        Fixture fixture = issued();
        moveActionGrantExpiry(fixture.reference(), Duration.ofSeconds(-1));

        assertThat(fixture.consume(operationId())).isInstanceOf(Consumption.Denied.class);
        assertThat(actionGrantConsumed(fixture.reference())).isFalse();
    }

    @Test
    void 만료를_발급_시각에서_다시_계산하지_않는다() {
        Fixture fixture = issued();
        // 만료는 지났지만 발급 시각은 아직 수명 안이다. 서버 시각이 뒤로 갔을 때 나타나는 모양이다.
        moveActionGrantExpiry(fixture.reference(), Duration.ofSeconds(-30));
        moveActionGrantIssuedAt(fixture.reference(), Duration.ofSeconds(-60));

        /*
         * `issued_at + 5분`을 소비 시점에 다시 계산하는 구현이라면 이 허가는 살아 있다. 그 사실을
         * 먼저 단정해 두지 않으면 아래 거부가 "만료됐으니까"인지 "여기서도 만료로 보이니까"인지
         * 구분되지 않는다.
         */
        assertThat(wouldBeLiveIfRecomputed(fixture.reference())).isTrue();
        assertThat(fixture.consume(operationId())).isInstanceOf(Consumption.Denied.class);
        assertThat(actionGrantConsumed(fixture.reference())).isFalse();
    }

    // ----------------------------------------------------------------------
    // 조회는 소비가 아니다
    // ----------------------------------------------------------------------

    @Test
    void 확인_화면이_허가를_읽어도_소비되지_않는다() {
        Fixture fixture = issued();

        // 읽기만 하는 경로를 여러 번 지난다.
        assertThat(actionGrantConsumed(fixture.reference())).isFalse();
        assertThat(actionGrantExists(fixture.reference())).isTrue();
        assertThat(actionGrantResult(fixture.reference())).isEmpty();
        assertThat(actionGrantConsumed(fixture.reference())).isFalse();

        // 읽은 뒤에도 허가는 그대로 쓸 수 있다. 소비는 실행 요청 하나에서만 일어난다.
        assertThat(fixture.consume(operationId()))
                .isInstanceOf(Consumption.ConsumedRequiringSessionRotation.class);
    }

    // ----------------------------------------------------------------------
    // 무효화
    // ----------------------------------------------------------------------

    @Test
    void 이미_소비된_허가는_다시_소비되지_않는다() {
        Fixture fixture = issued();
        assertThat(fixture.consume(operationId()))
                .isInstanceOf(Consumption.ConsumedRequiringSessionRotation.class);

        // 다른 `operation_id`는 새 요청이다. 이미 소비된 허가로는 통과하지 못한다.
        assertThat(fixture.consume(operationId())).isInstanceOf(Consumption.Denied.class);
    }

    @Test
    void 의심_세션으로는_소비되지_않는다() {
        Fixture fixture = issued();
        fixture.session().setAttribute(SessionGuard.REAUTH_REQUIRED_ATTRIBUTE, Boolean.TRUE);

        assertThat(fixture.consume(operationId())).isInstanceOf(Consumption.Denied.class);
        assertThat(actionGrantConsumed(fixture.reference())).isFalse();
    }

    @Test
    void 삭제가_시작되면_발급된_허가도_소비되지_않는다() {
        Fixture fixture = issued();
        assertThat(accountDeletion().beginDeletion(fixture.userId()))
                .isEqualTo(DeletionOutcome.COMPLETED);

        assertThat(fixture.consume(operationId())).isInstanceOf(Consumption.Denied.class);
        assertThat(actionGrantConsumed(fixture.reference())).isFalse();
    }

    @Test
    void 로그아웃한_세션의_허가는_어느_경로로도_소비되지_않는다() throws Exception {
        String subject = "grant-logout-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String deadSessionId = sessionIdOf(cookie);
        String reference = issue(grantSession(deadSessionId, userId), userId).orElseThrow();

        assertThat(logout(cookie, csrfTokenOf(cookie)).statusCode()).isEqualTo(204);
        assertThat(sessionCount()).isZero();

        /*
         * 이 허가를 소비하려면 id가 `deadSessionId`인 살아 있는 세션을 제시해야 한다. 그 세션은
         * 저장소에서 사라졌고 Spring Session은 같은 id를 다시 발급하지 않는다. 남는 경로는 둘뿐이고
         * 둘 다 막혀 있어야 한다.
         */
        // (1) 다시 로그인해 얻은 새 세션 — 같은 사용자지만 귀속의 세션 축이 어긋난다.
        String next = sessionCookieOf(signIn(subject)).orElseThrow();
        assertThat(sessionIdOf(next)).isNotEqualTo(deadSessionId);
        assertThat(consume(grantSession(sessionIdOf(next), userId), userId, reference, operationId()))
                .isInstanceOf(Consumption.Denied.class);
        // (2) 죽은 id를 주장하는 세션 — 개설된 주체가 없으므로 주체 대조에서 걸린다.
        assertThat(
                        consume(
                                grantSession(deadSessionId, null, Instant.now(), Duration.ZERO),
                                userId,
                                reference,
                                operationId()))
                .isInstanceOf(Consumption.Denied.class);

        assertThat(actionGrantConsumed(reference)).isFalse();
    }

    @Test
    void 세션이_회전하면_이전_세션의_허가는_소비되지_않는다() throws Exception {
        String subject = "grant-rotate-" + UUID.randomUUID();
        String cookie = sessionCookieOf(signIn(subject)).orElseThrow();
        UUID userId = userIdOf(OidcStubConfiguration.issuer(), subject).orElseThrow();
        String beforeId = sessionIdOf(cookie);
        String reference = issue(grantSession(beforeId, userId), userId).orElseThrow();

        String rotated =
                sessionCookieOf(
                                reauthenticate(
                                        AuthSecurityConfig.GOOGLE_REGISTRATION_ID,
                                        cookie,
                                        csrfTokenOf(cookie),
                                        plan -> plan.subject(subject)))
                        .orElseThrow();
        String afterId = sessionIdOf(rotated);

        // 회전 전 세션은 사라졌고 id가 바뀌었다.
        assertThat(afterId).isNotEqualTo(beforeId);
        assertThat(sessionCountOf(userId)).isEqualTo(1);
        assertThat(consume(grantSession(afterId, userId), userId, reference, operationId()))
                .isInstanceOf(Consumption.Denied.class);
        assertThat(actionGrantConsumed(reference)).isFalse();
    }

    @Test
    void 계정_payload_삭제가_그_계정의_허가만_지운다() {
        UUID mine = accountLifecycle().createAccount();
        UUID theirs = accountLifecycle().createAccount();
        HttpSession mySession = grantSession(mine);
        HttpSession theirSession = grantSession(theirs);
        issue(mySession, mine).orElseThrow();
        this.actionGrants
                .issue(mySession, mine, "unlink-provider", GRANT_TARGET, GRANT_CONFIRMATION)
                .orElseThrow();
        String theirReference = issue(theirSession, theirs).orElseThrow();

        assertThat(accountDeletion().beginDeletion(mine)).isEqualTo(DeletionOutcome.COMPLETED);
        assertThat(accountDeletion().erasePayload(mine)).isEqualTo(DeletionOutcome.COMPLETED);

        // 삭제 경로는 이 테이블을 알지 못한다. `ON DELETE CASCADE` 하나가 그 일을 한다.
        assertThat(actionGrantCountOf(mine)).isZero();
        assertThat(actionGrantCountOf(theirs)).isEqualTo(1);
        assertThat(actionGrantExists(theirReference)).isTrue();
    }

    // ----------------------------------------------------------------------
    // 트랜잭션 경계는 포트가 소유한다
    // ----------------------------------------------------------------------

    @Test
    void 호출자가_연_트랜잭션_안에서는_거절한다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId).orElseThrow();

        /*
         * 참여하면 둘이 함께 깨진다 — 멱등 충돌이 호출자의 트랜잭션을 rollback-only로 만들어 그
         * 커밋이 UnexpectedRollbackException으로 끝나고, 업무 변경 실패의 롤백 범위가 이 포트가
         * 약속한 범위보다 넓어진다. 거부가 아니라 예외인 것은 이것이 배선 실수이기 때문이다.
         */
        assertThatThrownBy(
                        () ->
                                this.transactionTemplate.execute(
                                        status -> issue(session, userId).orElse(null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(
                        () ->
                                this.transactionTemplate.execute(
                                        status -> consume(session, userId, reference, operationId())))
                .isInstanceOf(IllegalStateException.class);

        // 거절이 아무것도 바꾸지 않았다.
        assertThat(actionGrantCount()).isEqualTo(1);
        assertThat(actionGrantConsumed(reference)).isFalse();
    }

    // ----------------------------------------------------------------------
    // 도구
    // ----------------------------------------------------------------------

    /** 발급된 허가 하나와 그것을 소비할 문맥. */
    private record Fixture(
            ActionGrantService grants, MockHttpSession session, UUID userId, String reference) {

        Consumption consume(String operationId) {
            return this.grants.consume(
                    new ConsumptionRequest(
                            this.session,
                            this.userId,
                            this.reference,
                            GRANT_COMMAND,
                            GRANT_TARGET,
                            GRANT_CONFIRMATION,
                            operationId),
                    () -> "실행됨");
        }
    }

    private Fixture issued() {
        UUID userId = accountLifecycle().createAccount();
        MockHttpSession session = grantSession(userId);
        return new Fixture(this.actionGrants, session, userId, issue(session, userId).orElseThrow());
    }

    private Optional<String> issue(@Nullable HttpSession session, UUID userId) {
        return this.actionGrants.issue(
                session, userId, GRANT_COMMAND, GRANT_TARGET, GRANT_CONFIRMATION);
    }

    /** 그 시각에 재인증한 세션으로 발급을 시도한다. */
    private Optional<String> issueAt(UUID userId, Instant reauthenticatedAt) {
        return issue(
                grantSession(UUID.randomUUID().toString(), userId, reauthenticatedAt, Duration.ZERO),
                userId);
    }

    private Consumption consume(
            HttpSession session, UUID userId, String reference, String operationId) {
        return this.actionGrants.consume(
                new ConsumptionRequest(
                        session,
                        userId,
                        reference,
                        GRANT_COMMAND,
                        GRANT_TARGET,
                        GRANT_CONFIRMATION,
                        operationId),
                () -> "실행됨");
    }

    private Consumption request(
            HttpSession session,
            UUID userId,
            String reference,
            String command,
            String target,
            String confirmation) {
        return this.actionGrants.consume(
                new ConsumptionRequest(
                        session, userId, reference, command, target, confirmation, operationId()),
                () -> "실행되면 안 된다");
    }

    private static String operationId() {
        return "op-" + UUID.randomUUID();
    }

    private static String confirmationHash() {
        return ActionGrant.canonicalConfirmationHash(GRANT_COMMAND, GRANT_TARGET, GRANT_CONFIRMATION);
    }

    /** 그 허가의 만료 시각이 지금과 지금+수명 사이인가. 비교를 데이터베이스가 한다. */
    private boolean expiresWithinLifetime(String reference) {
        return jdbc()
                .sql(
                        "SELECT expires_at > now() "
                                + "AND expires_at <= now() + make_interval(secs => "
                                + "CAST(:seconds AS double precision)) "
                                + "FROM action_grant WHERE grant_id = :reference")
                .param("seconds", ActionGrant.LIFETIME.toSeconds())
                .param("reference", reference)
                .query(Boolean.class)
                .single();
    }

    /** 발급 시각에서 수명을 다시 계산하면 이 허가가 살아 있는가. */
    private boolean wouldBeLiveIfRecomputed(String reference) {
        return jdbc()
                .sql(
                        "SELECT issued_at + make_interval(secs => CAST(:seconds AS double precision)) "
                                + "> now() FROM action_grant WHERE grant_id = :reference")
                .param("seconds", ActionGrant.LIFETIME.toSeconds())
                .param("reference", reference)
                .query(Boolean.class)
                .single();
    }

    private static String migrationSource() throws IOException {
        try (InputStream stream =
                ActionGrantTest.class.getResourceAsStream("/db/migration/V3__action_grant.sql")) {
            if (stream == null) {
                throw new IllegalStateException("V3 마이그레이션을 classpath에서 찾지 못했다.");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 그 컬럼의 {@code octet_length} 상한. {@code BETWEEN … AND n}과 {@code <= n}을 모두 읽는다. */
    private static int upperBoundIn(String migration, String column) {
        Matcher matcher =
                Pattern.compile(
                                "octet_length\\(" + column + "\\)\\s*(?:BETWEEN\\s+\\d+\\s+AND\\s+|<=\\s*)(\\d+)")
                        .matcher(migration);
        if (!matcher.find()) {
            throw new IllegalStateException("V3에 " + column + "의 길이 CHECK가 없다.");
        }
        return Integer.parseInt(matcher.group(1));
    }
}
