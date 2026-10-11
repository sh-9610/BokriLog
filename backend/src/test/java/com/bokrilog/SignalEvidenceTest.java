package com.bokrilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bokrilog.operations.RestrictionSubject;
import com.bokrilog.operations.Signal;
import com.bokrilog.operations.SignalType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 신호 증거의 값 모양(Story 3.3 AC-6, Story 3.7). 규칙 code(대문자·숫자·밑줄)는 키 {@code ruleId}에만 받는다 — 다른 키에 오면
 * 내부 참조(UUID)·집계 수가 아니라서 거절이다.
 */
class SignalEvidenceTest {

    @Test
    void 규칙_code는_ruleId에만_받는다() {
        Signal secret = new Signal(SignalType.SECRET_EXPOSED, RestrictionSubject.none(), null,
                Map.of("ruleId", "CONFIGURED_SECRET_IN_LOG"), null);
        assertThat(secret.evidence()).containsEntry("ruleId", "CONFIGURED_SECRET_IN_LOG");
        for (String key : new String[] {"jobId", "observationId"}) {
            Map<String, String> evidence = new java.util.HashMap<>(
                    Map.of("jobId", UUID.randomUUID().toString(), "observationId", UUID.randomUUID().toString()));
            evidence.put(key, "SOME_RULE_CODE");
            assertThatThrownBy(() -> new Signal(SignalType.UNKNOWN_TRANSACTION_ENUM, RestrictionSubject.none(), null,
                    evidence, null)).as(key).isInstanceOf(IllegalArgumentException.class);
        }
        // ruleId에도 소문자·hex 원문은 안 된다.
        assertThatThrownBy(() -> new Signal(SignalType.SECRET_EXPOSED, RestrictionSubject.none(), null,
                Map.of("ruleId", "f".repeat(64)), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
