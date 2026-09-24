package com.bokrilog;

import com.bokrilog.ActionGrant.Binding;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code action_grant} 테이블에 대한 유일한 writer.
 *
 * <p>{@code IdentityAccountRepository}와 같은 모양이다 — SQL이 그대로 보이고, 메서드는 트랜잭션을
 * 열지 않으며, 판정을 쓰기 문장의 술어로 옮겨 "판정과 쓰기 사이의 창"을 없앤다. 다른 점은 술어가
 * 상태 문자열이 아니라 <b>다섯 귀속과 시각</b>이라는 것뿐이다.
 *
 * <p><b>시각의 권위는 이 데이터베이스 하나다.</b> 발급이 {@code expires_at}을 절대 시각으로 못
 * 박고 만료 판정은 {@link #consume} 한 문장의 술어가 한다. 애플리케이션이 만료를 판정하면 판정과
 * 소비가 두 문장으로 갈리고 그 사이가 곧 동시 소비의 창이며, 인스턴스마다 다른 시계가 보안 판정을
 * 흔든다. 대가는 만료 경계를 단위 시험으로 볼 수 없다는 것이고, 그래서 경계 시험이
 * Testcontainers를 요구한다.
 *
 * <p>영향 행 수가 곧 판정이다. 모든 문장이 그 수를 돌려주며, 0행을 조용히 성공으로 접는 자리가
 * 없다.
 *
 * <p>{@code identity} 소유 테이블에 닿지 않는다. 계정 상태는 공개 API가 답하고
 * ({@code AccountAccessApi}) 이 클래스가 아는 것은 {@code user_id} 컬럼 하나다.
 *
 * <p>servlet 웹 애플리케이션에서만 배선한다. 이 테이블을 읽고 쓰는 것은 세션에 귀속된 허가 포트
 * 하나뿐이고, 웹 서버 없이 뜨는 일회성 migration 역할에는 그 포트도 세션 개념도 없다
 * ({@code ActionGrantService}와 같은 조건).
 */
@Repository
@ConditionalOnWebApplication(type = Type.SERVLET)
class ActionGrantRepository {

    private final JdbcClient jdbc;

    ActionGrantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 재인증이 신선할 때만 허가를 발급한다.
     *
     * <p>신선도 판정이 INSERT 안에 있다. 자바가 {@code Instant.now()}로 비교하면 세션에 기록된
     * 시각을 만든 인스턴스와 지금 판정하는 인스턴스의 시계가 다를 수 있고, 그 차이가 그대로 허가
     * 정책의 완화가 된다. 여기서는 두 시각 모두 데이터베이스가 읽는다 — 하나는 파라미터로 들어온
     * 절대 시각, 다른 하나는 {@code now()}다.
     *
     * <p><b>창이 양쪽에서 닫힌다.</b> 과거 쪽은 재인증 창이고 미래 쪽은 허용 skew다. 재인증 시각만은
     * 애플리케이션이 쓰는 값이므로({@code AuthFlowHandlers}) 미래 상한이 없으면 앞선 시계가 기록한
     * 값이 창을 영원히 만족한다.
     *
     * <p>{@code expires_at}은 이 문장이 못 박는다. 소비 시점에 {@code issued_at + 수명}을 다시
     * 계산하지 않으므로 서버 시각이 뒤로 가도 수명이 늘어나지 않는다.
     *
     * @return 1이면 발급됐고, 0이면 재인증이 그 창 밖이다. 호출자에게 두 경우는 값 하나다.
     */
    int issueIfReauthenticationFresh(
            String reference,
            Binding binding,
            Instant reauthenticatedAt,
            Duration lifetime,
            Duration window,
            Duration futureSkew) {
        return this.jdbc
                .sql(
                        "INSERT INTO action_grant "
                                + "(grant_id, user_id, session_id, command, target, confirmation_hash, "
                                + "expires_at) "
                                + "SELECT :reference, :userId, :sessionId, :command, :target, "
                                + ":confirmationHash, "
                                + "now() + make_interval(secs => CAST(:lifetimeSeconds AS double precision)) "
                                + "WHERE :reauthenticatedAt > "
                                + "now() - make_interval(secs => CAST(:windowSeconds AS double precision)) "
                                + "AND :reauthenticatedAt <= "
                                + "now() + make_interval(secs => CAST(:skewSeconds AS double precision))")
                .param("reference", reference)
                .param("userId", binding.userId())
                .param("sessionId", binding.sessionId())
                .param("command", binding.command())
                .param("target", binding.target())
                .param("confirmationHash", binding.confirmationHash())
                .param("lifetimeSeconds", lifetime.toSeconds())
                .param("windowSeconds", window.toSeconds())
                .param("skewSeconds", futureSkew.toSeconds())
                .param("reauthenticatedAt", OffsetDateTime.ofInstant(reauthenticatedAt, ZoneOffset.UTC))
                .update();
    }

    /**
     * 같은 사용자·같은 {@code (command, target)}의 미소비 허가를 무효화한다.
     *
     * <p>재인증 한 번이 같은 작업의 허가를 여럿 만들지 않게 한다. 세션 속성은 발급 뒤에도 남으므로
     * 창 안에서 반복 발급이 가능하고, 그렇게 생긴 허가는 각자 다시 5분을 산다 — "최근 본인 확인을
     * 마친 작업을 <b>한 번</b> 실행한다"는 뜻과 어긋난다.
     *
     * <p>세션과 확인 문구는 술어에 넣지 않는다. 다른 세션에서 받아 둔 허가도, 다른 문구로 확인한
     * 허가도 같은 작업에 대한 것이라면 살아 있을 이유가 없다.
     *
     * <p>소비된 행은 건드리지 않는다. 그 행은 멱등 재전송의 근거다.
     *
     * @return 무효화한 행 수
     */
    int invalidateLiveGrants(UUID userId, String command, String target) {
        return this.jdbc
                .sql(
                        "DELETE FROM action_grant "
                                + "WHERE user_id = :userId AND command = :command AND target = :target "
                                + "AND consumed_at IS NULL")
                .param("userId", userId)
                .param("command", command)
                .param("target", target)
                .update();
    }

    /**
     * 허가를 소비한다. <b>조건부 UPDATE 한 문장</b>이다.
     *
     * <p>술어가 다섯 귀속과 미소비와 미만료를 함께 본다. 그래서 판정과 소비 사이에 창이 없고, 같은
     * 허가로 동시에 도착한 두 요청은 행 잠금 앞에 줄을 선 뒤 PostgreSQL이 술어를 다시 평가해
     * 정확히 하나만 1행을 받는다. 판정을 먼저 SELECT로 하고 나중에 UPDATE하면 그 사이가 곧 두 번
     * 실행되는 창이다.
     *
     * <p>{@code operation_id}를 <b>이 문장에서 함께</b> 적는다. 결과 저장 시점으로 미루면 서로 다른
     * 허가에 같은 {@code operation_id}를 들고 온 두 요청이 모두 업무 변경을 실행한 뒤에야 유일
     * 인덱스에 부딪힌다. 여기서 적으면 충돌이 업무 변경보다 앞에서 난다.
     *
     * <p>유일 인덱스 위반을 값으로 접지 않는다. 그 예외가 곧 "이 사용자가 그 {@code operation_id}로
     * 이미 실행했다"는 사실이며, 호출자가 그것을 저장된 결과 조회로 읽는다
     * ({@code ActionGrantService}). {@code ON CONFLICT DO NOTHING}으로 0행에 접으면 그 사실이
     * "귀속 불일치"와 구분되지 않는다.
     *
     * @return 1이면 이 호출이 소비했고, 0이면 다섯 중 하나가 어긋났거나 이미 소비됐거나 만료됐다.
     *     어느 쪽인지는 호출자에게 구분되지 않으며 그것이 거부 계약이다.
     */
    int consume(String reference, Binding binding, String operationId) {
        return this.jdbc
                .sql(
                        "UPDATE action_grant "
                                + "SET consumed_at = now(), operation_id = :operationId "
                                + "WHERE grant_id = :reference "
                                + "AND user_id = :userId "
                                + "AND session_id = :sessionId "
                                + "AND command = :command "
                                + "AND target = :target "
                                + "AND confirmation_hash = :confirmationHash "
                                + "AND consumed_at IS NULL "
                                + "AND now() < expires_at")
                .param("operationId", operationId)
                .param("reference", reference)
                .param("userId", binding.userId())
                .param("sessionId", binding.sessionId())
                .param("command", binding.command())
                .param("target", binding.target())
                .param("confirmationHash", binding.confirmationHash())
                .update();
    }

    /**
     * 소비된 허가에 실행 결과를 적는다.
     *
     * <p>{@link #consume}과 같은 트랜잭션 안에서만 부른다. 그래야 "소비됐는데 결과가 없는" 중간
     * 상태를 다른 트랜잭션이 볼 수 없다.
     *
     * @return 적은 행 수. 1이 아니면 방금 소비한 행이 사라졌다는 뜻이고 호출자가 소리 내어 실패한다
     */
    int storeResult(String reference, String result) {
        return this.jdbc
                .sql("UPDATE action_grant SET result = :result WHERE grant_id = :reference")
                .param("result", result)
                .param("reference", reference)
                .update();
    }

    /**
     * <b>같은 요청</b>이 이미 성공했다면 그때 저장된 결과.
     *
     * <p>멱등 재전송이 읽는 값이다. 술어가 {@code operation_id} 하나가 아니라 <b>요청 지문 전체</b>인
     * 것이 요점이다 — 같은 사용자가 어떤 작업에 쓴 {@code operation_id}를 뒤에 다른 작업에 재사용하면,
     * 술어가 좁지 않은 한 그 다른 작업은 실행되지 않은 채 앞 작업의 결과가 "이미 성공한 실행"으로
     * 돌아간다. 그래서 허가 참조·사용자·{@code command}·{@code target}·확인 해시가 모두 같을 때만
     * 재전송으로 본다.
     *
     * <p><b>세션만은 술어에 없다.</b> 허가 소비 성공은 세션 ID 회전을 요구하므로
     * ({@code SECURITY-ACTION-1.0}) 응답을 잃은 클라이언트의 재전송은 <b>반드시</b> 다른 세션에서
     * 온다. 세션을 넣으면 멱등 경로가 실제 흐름에서 한 번도 성립하지 않는다.
     *
     * @return 그 실행의 결과. 요청 지문이 어긋나거나 행이 없으면 빈 값
     */
    Optional<String> findStoredResult(String reference, Binding binding, String operationId) {
        return this.jdbc
                .sql(
                        "SELECT coalesce(result, '') FROM action_grant "
                                + "WHERE grant_id = :reference "
                                + "AND user_id = :userId "
                                + "AND command = :command "
                                + "AND target = :target "
                                + "AND confirmation_hash = :confirmationHash "
                                + "AND operation_id = :operationId")
                .param("reference", reference)
                .param("userId", binding.userId())
                .param("command", binding.command())
                .param("target", binding.target())
                .param("confirmationHash", binding.confirmationHash())
                .param("operationId", operationId)
                .query(String.class)
                .optional();
    }

    /**
     * 이 사용자의 만료된 미소비 허가를 지운다.
     *
     * <p>스케줄러도 배치도 두지 않는다. 발급 경로가 기회적으로 부르며, 그 호출이 실패해도 발급이
     * 막히지 않아야 하므로 호출자가 예외를 삼킨다.
     *
     * <p><b>사용자 범위다.</b> 범위 없는 전체 테이블 DELETE를 매 발급마다 돌리면 발급 하나의 비용이
     * 테이블 크기에 비례한다. 사용자로 좁히면 {@code action_grant_user_id_idx}를 타고, 지우는 대상도
     * "지금 발급하는 사람이 남긴 만료 행"이라는 뜻이 분명해진다.
     *
     * <p>소비된 행은 남긴다. 그 행의 {@code operation_id}와 {@code result}가 멱등 재전송의 유일한
     * 근거이고, 지우면 응답을 잃은 클라이언트의 재전송이 저장된 결과 대신 거부를 받는다. 남는 행의
     * 정리는 계정 삭제의 {@code ON DELETE CASCADE}가 맡는다.
     *
     * @return 지운 행 수
     */
    int deleteExpiredUnconsumedOf(UUID userId) {
        return this.jdbc
                .sql(
                        "DELETE FROM action_grant "
                                + "WHERE user_id = :userId AND consumed_at IS NULL AND now() >= expires_at")
                .param("userId", userId)
                .update();
    }
}
