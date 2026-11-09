package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.OperatorCommandApi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 운영 command 거절 문장 표(운영 command 공통화 리뷰 patch). command별 문장({@code COMMAND_REFUSALS})은 같은 code의 다른
 * 문장일 뿐이라 그 code가 공통 표({@code REFUSALS})에 있어야 한다 — 상태가 거기서 오고, 없으면 그 거절이 500이 된다. 키의
 * command도 아는 command다. 데이터베이스 없이 표만 본다.
 */
class OperatorControllerRefusalsTest {

    /** 운영자 화면의 거절 문장이 있는 파일. */
    private static final Path OPERATOR_CONTENT = Path.of("../frontend/src/content/operator.ts");

    @Test
    void command별_문장의_code는_모두_공통_거절표에_있고_command는_아는_이름이다() {
        assertThat(OperatorController.COMMAND_REFUSALS).isNotEmpty();
        for (String key : OperatorController.COMMAND_REFUSALS.keySet()) {
            String[] parts = key.split("\\|");
            assertThat(parts.length).as(key).isBetween(2, 3);
            assertThat(OperatorCommandApi.COMMANDS).as(key).contains(parts[0]);
            assertThat(OperatorController.REFUSALS).as(key).containsKey(parts[1]);
        }
    }

    /**
     * 삭제 재대기열(Epic 5 회고 AI-1)의 거절 문장은 서버와 화면이 글자까지 같다 — 화면은 서버 본문이 아니라 자기 표의 문장을 보이므로 한쪽만
     * 고치면 같은 거절이 두 말로 갈린다. 화면 파일에 각 문장이 한 문자열 그대로 있어야 한다.
     */
    @Test
    void 삭제_재대기열의_거절_문장은_화면_문장과_글자까지_같다() throws IOException {
        String content = Files.readString(OPERATOR_CONTENT, StandardCharsets.UTF_8);
        List<String> sentences = List.of(
                OperatorController.REFUSALS.get(OperatorCommandApi.DELETION_NOT_RETRYABLE).message(),
                OperatorController.REFUSALS.get(OperatorCommandApi.DELETION_REQUEUE_UNAVAILABLE).message(),
                OperatorController.DELETION_TARGET_INVALID);
        for (String sentence : sentences) {
            assertThat(content).as(sentence).contains("'" + sentence + "'");
        }
    }
}
