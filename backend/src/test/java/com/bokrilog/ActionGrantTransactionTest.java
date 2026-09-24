package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.ActionGrantService.Consumption;
import com.bokrilog.ActionGrantService.ConsumptionRequest;
import com.bokrilog.identity.AccessDecision;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

/**
 * 소비와 업무 변경의 트랜잭션 경계, 그리고 {@code operation_id} 멱등.
 *
 * <p>AC-3의 단일 소유자다. 확인하려는 것은 자바 분기가 아니라 <b>같은 트랜잭션에서 커밋되는가</b>
 * 이므로 업무 변경도 실제 쓰기여야 한다. 그래서 내부 시험 소비자가 하는 일은
 * {@code identity} 공개 API의 매핑 연결 하나다 — 이 저장소에 이미 있는 쓰기이고, 롤백되면 행 수로
 * 관측된다. 새 테이블도 새 표면도 만들지 않는다.
 *
 * <p>이 소비자는 이 파일이 소유하고 <b>HTTP로 노출되지 않는다</b>. 실행 가능한 민감 command가
 * 아직 없으므로 지금 엔드포인트를 만들면 소비자 없는 표면이 되고, 시험용 상태 변경을 외부 API로
 * 여는 것은 {@code SessionGuard}가 이미 거절한 형태다.
 */
class ActionGrantTransactionTest extends AuthFlowTestBase {

    @Autowired private ActionGrantService actionGrants;

    @Test
    void 소비와_업무_변경이_한_트랜잭션으로_커밋된다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId);
        AtomicInteger executions = new AtomicInteger();

        Consumption outcome =
                consume(session, userId, reference, "op-1", linking(userId, executions));

        assertThat(outcome).isEqualTo(new Consumption.ConsumedRequiringSessionRotation("연결됨"));
        assertThat(executions).hasValue(1);
        assertThat(actionGrantConsumed(reference)).isTrue();
        assertThat(actionGrantResult(reference)).contains("연결됨");
        // 업무 변경이 실제로 커밋됐다.
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 업무_변경이_실패하면_허가는_미소비로_남는다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId);
        AtomicInteger executions = new AtomicInteger();

        assertThatThrownBy(
                        () ->
                                consume(
                                        session,
                                        userId,
                                        reference,
                                        "op-fails",
                                        () -> {
                                            linking(userId, executions).get();
                                            throw new IllegalStateException("업무 변경 실패");
                                        }))
                // 예외를 값으로 접지 않는다. 접으면 실패가 거부와 구분되지 않는다.
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("업무 변경 실패");

        assertThat(executions).hasValue(1);
        // 소비 UPDATE도 함께 롤백됐다. "허가만 소비된 성공"이 남지 않는다.
        assertThat(actionGrantConsumed(reference)).isFalse();
        assertThat(actionGrantResult(reference)).isEmpty();
        assertThat(socialLinkCount()).isZero();

        // 그래서 사용자는 새 재인증 없이 같은 허가로 다시 시도할 수 있다.
        assertThat(consume(session, userId, reference, "op-retry", linking(userId, executions)))
                .isEqualTo(new Consumption.ConsumedRequiringSessionRotation("연결됨"));
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 업무_변경이_던진_유일_제약_위반은_그대로_전파된다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId);

        /*
         * 첫 소비자가 될 1.10의 `linkSocialIdentity`는 유일 제약 위반이 정상 경로인 대표 사례다.
         * 그 예외를 이 포트가 자기 `operation_id` 충돌로 읽으면, 실행되지 않은 작업이 "이미 성공한
         * 실행"으로 보고된다. 같은 타입이지만 출처가 다르다는 것을 이 축이 고정한다.
         */
        assertThatThrownBy(
                        () ->
                                consume(
                                        session,
                                        userId,
                                        reference,
                                        "op-duplicate",
                                        () -> {
                                            jdbc()
                                                    .sql(
                                                            "INSERT INTO identity_account_lifecycle "
                                                                    + "(user_id, state) VALUES (:userId, 'ACTIVE')")
                                                    .param("userId", userId)
                                                    .update();
                                            return "여기에 닿지 않는다";
                                        }))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(actionGrantConsumed(reference)).isFalse();
    }

    @Test
    void 상한을_넘는_결과는_저장되지_않고_소비도_남지_않는다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId);
        AtomicInteger executions = new AtomicInteger();
        String oversized = "r".repeat(ActionGrant.MAX_RESULT_BYTES + 1);

        /*
         * 게이트가 없으면 이 값은 스키마 CHECK 위반으로 나타나고, 그 실패는 업무 변경이 이미
         * 실행된 뒤 SQL 오류의 모양으로 호출자 트랜잭션을 지운다. 소비자의 배선 실수이므로 거부가
         * 아니라 예외이며, 롤백 범위는 업무 변경 실패와 같다.
         */
        assertThatThrownBy(
                        () ->
                                consume(
                                        session,
                                        userId,
                                        reference,
                                        "op-oversized",
                                        () -> {
                                            linking(userId, executions).get();
                                            return oversized;
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(actionGrantConsumed(reference)).isFalse();
        assertThat(socialLinkCount()).isZero();
    }

    @Test
    void 같은_요청의_재전송은_저장된_결과를_돌려주고_업무를_다시_실행하지_않는다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId);
        AtomicInteger executions = new AtomicInteger();
        Consumption first = consume(session, userId, reference, "op-same", linking(userId, executions));

        // 응답을 잃은 클라이언트가 같은 요청을 그대로 다시 보낸다. 성공이 세션 회전을 요구하므로
        // 그 재전송은 <b>다른 세션</b>에서 오며, 그래서 세션은 재전송 판정의 축이 아니다.
        Consumption second =
                consume(
                        grantSession(UUID.randomUUID().toString(), userId),
                        userId,
                        reference,
                        "op-same",
                        linking(userId, executions));

        assertThat(second).isEqualTo(first);
        assertThat(executions).hasValue(1);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 같은_operation_id를_다른_작업에_재사용하면_거부된다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String linkGrant = issue(session, userId);
        AtomicInteger executions = new AtomicInteger();
        consume(session, userId, linkGrant, "op-reused", linking(userId, executions));
        String unlinkGrant =
                this.actionGrants
                        .issue(session, userId, "unlink-provider", GRANT_TARGET, GRANT_CONFIRMATION)
                        .orElseThrow();

        Consumption reused =
                this.actionGrants.consume(
                        new ConsumptionRequest(
                                session,
                                userId,
                                unlinkGrant,
                                "unlink-provider",
                                GRANT_TARGET,
                                GRANT_CONFIRMATION,
                                "op-reused"),
                        linking(userId, executions));

        /*
         * 멱등 키가 요청 지문에 묶이지 않으면 여기서 해제는 실행되지 않은 채 연결의 결과가 "이미
         * 성공한 실행"으로 돌아간다. 값 하나를 다시 쓴 것은 재전송이 아니라 다른 요청이다.
         */
        assertThat(reused).isInstanceOf(Consumption.Denied.class);
        assertThat(executions).hasValue(1);
        assertThat(actionGrantConsumed(unlinkGrant)).isFalse();
    }

    @Test
    void 다른_operation_id_재전송은_이미_소비된_허가로_통과하지_못한다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String reference = issue(session, userId);
        AtomicInteger executions = new AtomicInteger();
        consume(session, userId, reference, "op-first", linking(userId, executions));

        Consumption second =
                consume(session, userId, reference, "op-second", linking(userId, executions));

        // 다른 값은 새 요청이다. 이미 소비된 허가로는 통과하지 못한다.
        assertThat(second).isInstanceOf(Consumption.Denied.class);
        assertThat(executions).hasValue(1);
        assertThat(socialLinkCount()).isEqualTo(1);
    }

    @Test
    void 다른_허가에_같은_operation_id를_쓰면_업무_변경보다_먼저_막힌다() {
        UUID userId = accountLifecycle().createAccount();
        HttpSession session = grantSession(userId);
        String first = issue(session, userId);
        AtomicInteger executions = new AtomicInteger();
        consume(session, userId, first, "op-shared", linking(userId, executions));
        // 앞의 허가가 소비됐으므로 같은 작업의 새 허가를 다시 받을 수 있다.
        String second = issue(session, userId);

        Consumption blocked =
                consume(session, userId, second, "op-shared", linking(userId, executions));

        /*
         * 멱등의 권위는 유일 인덱스 하나다. 애플리케이션이 "이미 있나?"를 먼저 조회하는 형태였다면
         * 두 번째 소비가 업무 변경을 실행한 뒤에야 충돌을 알게 된다. 그리고 요청 지문이 다르므로
         * (허가 참조가 다르다) 저장된 결과가 돌아가지도 않는다.
         */
        assertThat(blocked).isInstanceOf(Consumption.Denied.class);
        assertThat(executions).hasValue(1);
        assertThat(socialLinkCount()).isEqualTo(1);
        assertThat(actionGrantConsumed(second)).isFalse();
    }

    @Test
    void 두_사용자가_같은_operation_id로_각자_성공한다() {
        UUID mine = accountLifecycle().createAccount();
        UUID theirs = accountLifecycle().createAccount();
        HttpSession mySession = grantSession(mine);
        HttpSession theirSession = grantSession(theirs);
        AtomicInteger executions = new AtomicInteger();

        Consumption first =
                consume(mySession, mine, issue(mySession, mine), "link-naver-1", linking(mine, executions));
        Consumption second =
                consume(
                        theirSession,
                        theirs,
                        issue(theirSession, theirs),
                        "link-naver-1",
                        linking(theirs, executions));

        /*
         * `operation_id`는 소비자가 정하는 값이고 추측 가능한 모양일 수 있다. 유일 인덱스가 전역이면
         * 먼저 쓴 사용자가 다른 사용자의 그 값을 영구히 막는다 — 되돌릴 방법이 없는 선점이다.
         * 사용자 축이 인덱스에 함께 들어가야 이 둘이 서로를 막지 않는다.
         */
        assertThat(first).isInstanceOf(Consumption.ConsumedRequiringSessionRotation.class);
        assertThat(second).isInstanceOf(Consumption.ConsumedRequiringSessionRotation.class);
        assertThat(executions).hasValue(2);
        assertThat(socialLinkCount()).isEqualTo(2);
    }

    // ----------------------------------------------------------------------
    // 내부 시험 소비자
    // ----------------------------------------------------------------------

    /**
     * 업무 변경 하나.
     *
     * <p>{@code identity} 공개 API의 매핑 연결이다. 실제 쓰기여야 롤백을 행 수로 관측할 수 있고,
     * 그 API는 호출자의 트랜잭션에 참여하므로({@code AccountLinkDenialTest}가 고정한 계약) 소비
     * UPDATE와 같은 경계에서 커밋된다.
     */
    private Supplier<String> linking(UUID userId, AtomicInteger executions) {
        return () -> {
            executions.incrementAndGet();
            AccessDecision decision =
                    accountLifecycle()
                            .linkSocialIdentity(
                                    userId,
                                    GRANT_TARGET + "-" + UUID.randomUUID(),
                                    "https://provider.example/" + UUID.randomUUID(),
                                    UUID.randomUUID().toString());
            assertThat(decision).isEqualTo(AccessDecision.ALLOWED);
            return "연결됨";
        };
    }

    private String issue(HttpSession session, UUID userId) {
        return this.actionGrants
                .issue(session, userId, GRANT_COMMAND, GRANT_TARGET, GRANT_CONFIRMATION)
                .orElseThrow();
    }

    private Consumption consume(
            HttpSession session,
            UUID userId,
            String reference,
            String operationId,
            Supplier<String> operation) {
        return this.actionGrants.consume(
                new ConsumptionRequest(
                        session,
                        userId,
                        reference,
                        GRANT_COMMAND,
                        GRANT_TARGET,
                        GRANT_CONFIRMATION,
                        operationId),
                operation);
    }
}
