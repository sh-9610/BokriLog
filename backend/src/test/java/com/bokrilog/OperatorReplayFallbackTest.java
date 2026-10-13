package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.OperatorCommandApi;
import org.junit.jupiter.api.Test;

/**
 * 모양이 어긋난 {@code COMPLETED} 행의 재전송(운영 command 공통화 리뷰 patch). 리팩터 전(기준 {@code b318c21})의 재생 차례를
 * 지킨다 — 그 command의 모양으로 읽을 수 없는 완료 행은 제한 생성의 사실이 있으면 제한 생성으로, 아니면 추출로 읽고 없는
 * 상한·행 수는 0, 잘림은 거짓이다. 행을 시험이 직접 고쳐 만든다(운영 원장은 추가 전용이다).
 */
class OperatorReplayFallbackTest extends OperatorTestBase {

    @Test
    void 사실이_빠진_추출_완료_행의_재전송은_빈_추출이다() throws Exception {
        Signed operator = operator("replay-partial", "command,audit");
        String target = recentTarget();
        String key = key("replay-partial");
        var confirmed = export(operator, target, key);
        String operationId = operationIdIn(confirmed.body());
        rewriteCompleted(operationId, "jsonb_build_object('keyDigest', details->>'keyDigest')");

        var replayed = confirmExport(after(operator, confirmed), operationId, phraseOf(target), key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replayed.body())).containsEntry("operationId", operationId)
                .containsEntry("outcome", "COMPLETED").containsEntry("target", target)
                .containsEntry("upperSequence", 0).containsEntry("rowCount", 0).containsEntry("truncated", false);
        assertThat(itemsIn(replayed.body(), "rows")).isEmpty();
    }

    @Test
    void 키_digest가_빠진_완료_행은_같은_키라고_말할_수_없어_충돌이다() throws Exception {
        Signed operator = operator("replay-shape", "command,audit");
        String target = recentTarget();
        String key = key("replay-shape");
        var confirmed = export(operator, target, key);
        String operationId = operationIdIn(confirmed.body());
        // 키 digest가 없는 완료 행이다 — 리팩터 전에도 같은 키라고 말할 수 없어 충돌이었다(500이 아니다).
        rewriteCompleted(operationId, "details - 'keyDigest'");

        var replayed = confirmExport(after(operator, confirmed), operationId, phraseOf(target), key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(409);
        assertThat(topLevelFieldsOf(replayed.body())).containsEntry("code", "IDEMPOTENCY_CONFLICT");
    }

    /**
     * 그 command의 사실이 빠졌지만 제한 생성의 사실(제한 ID·기능·scope·효력 시작)이 있는 완료 행은 리팩터 전처럼 제한 생성의
     * 결과로 재생한다(해제 행에서 해제 시각이 빠진 경우).
     */
    @Test
    void 해제_사실이_빠지고_제한_사실이_남은_완료_행은_제한_생성으로_재생한다() throws Exception {
        Signed operator = operator("replay-cross", "command,audit");
        String key = key("replay-cross");
        var made = restrictVia(operator, OperatorCommandApi.RESTRICT_ADVERTISING, "GLOBAL", "ALL", key);
        String operationId = operationIdIn(made.confirmed().body());
        jdbc().sql("UPDATE operations_audit_event SET command = 'lift-restriction' WHERE operation_id = :o")
                .param("o", operationId).update();

        var replayed = confirmRestriction(made.signed(), OperatorCommandApi.LIFT_RESTRICTION, operationId, "복구 광고 전역", key);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(topLevelFieldsOf(replayed.body())).containsEntry("operationId", operationId)
                .containsEntry("command", OperatorCommandApi.LIFT_RESTRICTION).containsEntry("feature", "ADVERTISING")
                .containsEntry("scope", "GLOBAL").containsKeys("restrictionId", "effectiveFrom")
                .doesNotContainKey("smoke");
    }

    /** 그 operation의 완료 행 {@code details}를 바꾼다. 시험 준비다. */
    private void rewriteCompleted(String operationId, String expression) {
        int updated = jdbc().sql("UPDATE operations_audit_event SET details = " + expression
                        + " WHERE operation_id = :o AND outcome = 'COMPLETED'")
                .param("o", operationId).update();
        assertThat(updated).isEqualTo(1);
    }
}
