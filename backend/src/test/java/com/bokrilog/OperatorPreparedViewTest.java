package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bokrilog.operations.OperatorCommandApi;
import com.bokrilog.operations.RestrictionFeature;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 준비 응답의 감싸기(운영 command 공통화 리뷰 patch). 미리보기 맵의 {@code null} 값은 준비 응답의 {@code NON_NULL}에 걸리지 않고
 * 그대로 나간다 — 리팩터 전 {@code LiftImpactView}가 신호가 없을 때 {@code "latestSignalAt":null}을 실은 것과 같다. 맵 자체의
 * 계약은 {@code OperatorCommandContractTest}가 본다.
 */
class OperatorPreparedViewTest extends OperatorTestBase {

    @Test
    void 해제_미리보기의_빈_신호_시각은_null로_실린다() throws Exception {
        UUID lifted = restrictGlobally(RestrictionFeature.ADVERTISING);
        Signed operator = operator("prepared-view", "command");
        String conditions = String.join(",", OperatorCommandApi.RECOVERY_CONDITIONS.stream()
                .map(condition -> "\"" + condition + "\"").toList());
        var prepared = sendJson("POST", commandPath(OperatorCommandApi.LIFT_RESTRICTION), "{\"target\":\"" + lifted
                        + "\",\"reason\":\"원인 수정\",\"verification\":\"재현 시험\",\"evidenceRef\":\"INC-1\","
                        + "\"deployVersion\":\"1.0\",\"conditions\":[" + conditions + "]}", operator.cookie(),
                headers(operator.csrf(), key("prepared-view")));
        assertThat(prepared.statusCode()).as(prepared.body()).isEqualTo(200);
        assertThat(prepared.body()).contains("\"linkedSignals\":0,\"latestSignalAt\":null}");
    }
}
