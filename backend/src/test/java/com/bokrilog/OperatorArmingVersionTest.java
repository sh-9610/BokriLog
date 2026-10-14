package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 배포 전 무장(운영 command 공통화 SPEC의 I/O 행 "배포 전 무장"). 배포 전 인스턴스가 쓴 무장(payload 칸이 없던 옛 모양, 지금
 * 빌드에 없는 클래스) 또는 읽을 수 없는 바이트가 세션의 무장 속성에 남아 있어도 확정은 무장이 없는 것과 같은 단일 거절(재준비 요구)이고, 같은 세션의 다른
 * 요청과 다른 속성(로그인·재인증 시각)은 그대로다.
 *
 * <p>실제 JDBC 세션 저장소를 지난다 — 무장 속성의 {@code ATTRIBUTE_BYTES}를 SQL로 바꿔 놓고 HTTP로 부른다.
 */
class OperatorArmingVersionTest extends OperatorTestBase {

    /**
     * 배포 전 모양: payload 칸이 없던 무장(command별 칸 넷). record는 {@code serialVersionUID}를 대조하지 않고 칸 이름으로
     * 맞춰 읽으므로, 버전 5에 칸 이름이 다른 스트림을 만들어 그 모양을 재현한다.
     */
    @Test
    void 배포_전_모양의_무장은_없는_무장과_같은_거절이고_세션의_나머지는_남는다() throws Exception {
        assertStaleArmingRefused("arming-shape", stored -> withSerialVersion(
                replaceFirst(stored, utf("payload"), utf("inquiry")), OperatorCommandFlow.Armed.class.getName(), 5L));
    }

    /** 배포 전 무장이 지금 빌드에 없는 클래스(예: 옛 `OperatorCommandApi$Lift`)를 담았다. */
    @Test
    void 지금_빌드에_없는_클래스를_담은_무장도_없는_무장과_같은_거절이다() throws Exception {
        String payloadClass = AuditExportCommandNames.EXPORT_PAYLOAD;
        String missing = payloadClass.substring(0, payloadClass.length() - 1) + "X";
        assertStaleArmingRefused("arming-class", stored -> replaceFirst(stored, utf(payloadClass), utf(missing)));
    }

    private void assertStaleArmingRefused(String prefix, java.util.function.UnaryOperator<byte[]> stale)
            throws Exception {
        Signed operator = operator(prefix, "command,audit");
        String target = recentTarget();
        String key = key(prefix);
        // 무장이 없는 세션의 확정 — 비교 기준이다(상태·code·문장).
        var baseline = confirmExport(operator, "op_none", phraseOf(target), key(prefix + "-none"));
        assertThat(baseline.statusCode()).as(baseline.body()).isEqualTo(403);
        var prepared = prepareExport(operator, target, "배포 전 무장", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        String operationId = operationIdIn(prepared.body());
        String sessionId = sessionIdOf(operator.cookie());
        Instant reauthenticatedAt = (Instant) sessionAttribute(sessionId,
                ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE);
        assertThat(reauthenticatedAt).isNotNull();

        // 지금 무장의 바이트를 배포 전 인스턴스가 썼을 바이트로 바꾼다.
        writeArmedBytes(sessionId, stale.apply(armedBytes(sessionId)));

        var confirmed = confirmExport(operator, operationId, phraseOf(target), key);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(baseline.statusCode());
        Map<String, Object> refused = topLevelFieldsOf(confirmed.body());
        Map<String, Object> expected = topLevelFieldsOf(baseline.body());
        assertThat(refused).containsEntry("code", OperatorCommandService.NOT_CONFIRMED)
                .containsEntry("message", expected.get("message"));
        assertThat(outcomesOf(operationId)).doesNotContain("COMPLETED");

        // 읽을 수 없는 무장은 버려졌고, 로그인·재인증 시각은 남는다 — 같은 세션으로 다시 준비할 수 있다.
        assertThat(armedRowCount(sessionId)).isZero();
        assertThat(sessionAttribute(sessionId, ReauthenticationFlow.AUTHENTICATED_AT_ATTRIBUTE))
                .isEqualTo(reauthenticatedAt);
        assertThat(get(OperatorGate.AUDIT_PATH, operator.cookie()).statusCode()).isEqualTo(200);
        var again = prepareExport(operator, target, "배포 뒤 재준비", key(prefix + "-again"));
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
    }

    @Test
    void 읽을_수_없는_무장_바이트도_준비_확정_취소를_막지_않는다() throws Exception {
        Signed operator = operator("arming-garbage", "command,audit");
        String target = recentTarget();
        String key = key("arming-garbage");
        var prepared = prepareExport(operator, target, "깨진 무장", key);
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        String sessionId = sessionIdOf(operator.cookie());
        writeArmedBytes(sessionId, "not a serialized object".getBytes(StandardCharsets.UTF_8));

        // 취소는 무장이 없는 것과 같이 204다.
        assertThat(cancelExport(operator).statusCode()).isEqualTo(204);
        assertThat(armedRowCount(sessionId)).isZero();

        // 다시 깨뜨린 뒤의 확정은 단일 거절, 그 뒤의 새 준비는 앞선 무장 없이 무장한다.
        var rearmed = prepareExport(operator, target, "깨진 무장 재준비", key("arming-garbage-2"));
        assertThat(rearmed.statusCode()).as(rearmed.body()).isEqualTo(200);
        writeArmedBytes(sessionId, new byte[] {(byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x7E});
        var confirmed = confirmExport(operator, operationIdIn(rearmed.body()), phraseOf(target),
                key("arming-garbage-2"));
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(403);
        assertThat(topLevelFieldsOf(confirmed.body())).containsEntry("code", OperatorCommandService.NOT_CONFIRMED);
        writeArmedBytes(sessionId, new byte[] {0x00});
        var fresh = prepareExport(operator, target, "깨진 무장 위 새 준비", key("arming-garbage-3"));
        assertThat(fresh.statusCode()).as(fresh.body()).isEqualTo(200);
        assertThat(get(OperatorGate.AUDIT_PATH, operator.cookie()).statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 도구

    /** 감사 내보내기 무장의 payload 클래스 이름. */
    private static final class AuditExportCommandNames {
        static final String EXPORT_PAYLOAD = "com.bokrilog.operations.internal.AuditExportCommand$Export";
    }

    private static byte[] utf(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 같은 길이의 바이트 열로 첫 출현 하나를 바꾼 사본. 없으면 시험 준비가 틀렸다. */
    private static byte[] replaceFirst(byte[] stream, byte[] from, byte[] to) {
        if (from.length != to.length) {
            throw new IllegalArgumentException("같은 길이여야 한다.");
        }
        for (int index = 0; index + from.length <= stream.length; index++) {
            if (java.util.Arrays.equals(stream, index, index + from.length, from, 0, from.length)) {
                byte[] patched = stream.clone();
                System.arraycopy(to, 0, patched, index, to.length);
                return patched;
            }
        }
        throw new IllegalStateException("직렬화 스트림에 그 바이트가 없다: " + new String(from, StandardCharsets.UTF_8));
    }

    private static final String ARMED_ROW = "FROM spring_session_attributes a JOIN spring_session s "
            + "ON a.session_primary_id = s.primary_id WHERE s.session_id = :id AND a.attribute_name = :name";

    private byte[] armedBytes(String sessionId) {
        return jdbc().sql("SELECT a.attribute_bytes " + ARMED_ROW).param("id", sessionId)
                .param("name", OperatorCommandFlow.FLOW_ATTRIBUTE).query(byte[].class).single();
    }

    private long armedRowCount(String sessionId) {
        return jdbc().sql("SELECT count(*) " + ARMED_ROW).param("id", sessionId)
                .param("name", OperatorCommandFlow.FLOW_ATTRIBUTE).query(Long.class).single();
    }

    /** 무장 속성의 바이트를 덮어쓴다. 행이 없으면 만든다(무장을 지운 뒤의 재현). */
    private void writeArmedBytes(String sessionId, byte[] bytes) {
        int updated = jdbc().sql("UPDATE spring_session_attributes a SET attribute_bytes = :bytes FROM spring_session s "
                        + "WHERE a.session_primary_id = s.primary_id AND s.session_id = :id AND a.attribute_name = :name")
                .param("bytes", bytes).param("id", sessionId).param("name", OperatorCommandFlow.FLOW_ATTRIBUTE)
                .update();
        if (updated == 0) {
            jdbc().sql("INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes) "
                            + "SELECT primary_id, :name, :bytes FROM spring_session WHERE session_id = :id")
                    .param("bytes", bytes).param("id", sessionId).param("name", OperatorCommandFlow.FLOW_ATTRIBUTE)
                    .update();
        }
    }

    /**
     * 직렬화 스트림에서 그 클래스 설명의 {@code serialVersionUID}(클래스 이름 바로 뒤 8바이트)를 바꾼 사본.
     */
    private static byte[] withSerialVersion(byte[] stream, String className, long version) {
        byte[] name = className.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index + name.length + 8 <= stream.length; index++) {
            boolean match = true;
            for (int offset = 0; offset < name.length && match; offset++) {
                match = stream[index + offset] == name[offset];
            }
            if (match) {
                byte[] patched = stream.clone();
                ByteBuffer.wrap(patched, index + name.length, 8).putLong(version);
                return patched;
            }
        }
        throw new IllegalStateException("직렬화 스트림에 그 클래스 설명이 없다: " + className);
    }
}
