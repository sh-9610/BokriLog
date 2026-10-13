package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.OperatorCommandApi;
import org.junit.jupiter.api.Test;

/**
 * 운영 command 거절 문장 표(운영 command 공통화 리뷰 patch). command별 문장({@code COMMAND_REFUSALS})은 같은 code의 다른
 * 문장일 뿐이라 그 code가 공통 표({@code REFUSALS})에 있어야 한다 — 상태가 거기서 오고, 없으면 그 거절이 500이 된다. 키의
 * command도 아는 command다. 데이터베이스 없이 표만 본다.
 */
class OperatorControllerRefusalsTest {

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
}
