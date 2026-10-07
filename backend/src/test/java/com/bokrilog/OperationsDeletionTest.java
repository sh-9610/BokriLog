package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.OperationsDeletionApi;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 3.1 AC-8: 감사 데이터의 삭제·보존 분류.
 *
 * <p>OPERATOR 행은 사용자 귀속 운영 데이터 — 삭제 port가 그 사용자의 귀속만 비식별하고 digest·사실을 남긴다. SYSTEM 행은
 * 순수 운영 데이터 — 바뀌지 않는다. 법정 분리 보존 후보는 G-5 전까지 없다. 계정 payload 삭제는 감사 행을 연쇄로 지우지
 * 않는다(외래키가 없다).
 */
class OperationsDeletionTest extends OperatorTestBase {

    @Autowired
    private OperationsDeletionApi deletion;

    record Row(String operationId, String actorKind, String digest, boolean attributed, boolean erased,
            String outcome) {}

    @Test
    void 삭제는_그_사용자의_귀속만_비식별하고_나머지를_보존하며_멱등이다() throws Exception {
        Signed erased = operator("deletion-erased", "command,audit");
        Signed kept = signedIn("deletion-kept");
        operatorAccess().reinterpret(entry(erased.subject(), "command,audit") + ";"
                + entry(kept.subject(), "command,audit"));
        kept = reauthenticated(kept);
        String erasedOperation = operationIdIn(
                prepareExport(erased, recentTarget(), "사유", key("deletion-erased")).body());
        String keptOperation = operationIdIn(prepareExport(kept, recentTarget(), "사유", key("deletion-kept")).body());
        insertSystemRow();
        List<Row> before = rows();

        assertThat(this.deletion.eraseActor(erased.user())).isEqualTo(1);
        List<Row> after = rows();
        assertThat(after).hasSameSizeAs(before);
        for (int index = 0; index < before.size(); index++) {
            Row was = before.get(index);
            Row now = after.get(index);
            // digest·사실은 그대로다.
            assertThat(now.digest()).isEqualTo(was.digest());
            assertThat(now.outcome()).isEqualTo(was.outcome());
            assertThat(now.operationId()).isEqualTo(was.operationId());
            if (was.operationId().equals(erasedOperation)) {
                assertThat(now.attributed()).isFalse();
                assertThat(now.erased()).isTrue();
            } else {
                assertThat(now).isEqualTo(was);
            }
        }
        assertThat(after).anySatisfy(row -> assertThat(row.operationId()).isEqualTo(keptOperation));
        assertThat(after).anySatisfy(row -> assertThat(row.actorKind()).isEqualTo("SYSTEM"));

        // 멱등이다. 이미 비운 행은 다시 고르지 않는다.
        assertThat(this.deletion.eraseActor(erased.user())).isZero();
        assertThat(this.deletion.eraseActor(null)).isZero();
        assertThat(rows()).isEqualTo(after);

        // 비식별된 행은 그 사용자의 operation 조회로 더는 이어지지 않는다.
        assertThat(operationGet(erased, erasedOperation).statusCode()).isEqualTo(404);

        // 계정 payload 삭제가 감사 행을 연쇄로 지우지 않는다.
        accountDeletion().beginDeletion(kept.user());
        accountDeletion().erasePayload(kept.user());
        assertThat(rows()).hasSameSizeAs(after);
    }

    private void insertSystemRow() {
        jdbc().sql("INSERT INTO operations_audit_event (audit_id, operation_id, actor_user_id, actor_digest, "
                        + "actor_kind, command, scope, target, reason, outcome, policy_version, requested_at, "
                        + "correlation_id) VALUES (:id, 'op_system', NULL, :digest, 'SYSTEM', 'auto-stop', 'GLOBAL', "
                        + "'sync', '강한 신호', 'REQUESTED', 'OPERATOR-COMMAND-1.0', statement_timestamp(), "
                        + "'test-correlation')")
                .param("id", UUID.randomUUID()).param("digest", "b".repeat(64)).update();
    }

    private List<Row> rows() {
        return jdbc().sql("SELECT operation_id, actor_kind, actor_digest, actor_user_id IS NOT NULL AS attributed, "
                        + "actor_erased_at IS NOT NULL AS erased, outcome FROM operations_audit_event ORDER BY sequence")
                .query((rs, row) -> new Row(rs.getString("operation_id"), rs.getString("actor_kind"),
                        rs.getString("actor_digest"), rs.getBoolean("attributed"), rs.getBoolean("erased"),
                        rs.getString("outcome")))
                .list();
    }
}
