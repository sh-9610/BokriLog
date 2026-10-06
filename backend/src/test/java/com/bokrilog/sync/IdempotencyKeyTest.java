package com.bokrilog.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 멱등 키 규칙 한 벌. {@code PlanEndControllerTest}가 컨트롤러 사본에 대해 고정하던 축을 그대로 옮겨 왔다 — 루트·plan·
 * sync의 모든 엄격 경로가 이 규칙 하나를 지나므로 여기서 고정하면 전부에 대해 고정된다.
 */
class IdempotencyKeyTest {

    @Test
    void 열여섯자_이상_안전한_알파벳의_키만_유효하다() {
        assertThat(IdempotencyKey.isValid("plan-end-key-00000001")).isTrue();
        assertThat(IdempotencyKey.isValid("a".repeat(IdempotencyKey.MIN_LENGTH))).isTrue();
        assertThat(IdempotencyKey.isValid("k".repeat(IdempotencyKey.MAX_LENGTH))).isTrue();
        assertThat(IdempotencyKey.isValid("ns:dash-board.key_0001")).as("`:`·`.`·`_`·`-`는 허용").isTrue();
    }

    @Test
    void 없거나_짧거나_대시로_시작하거나_점_둘이_있거나_길면_거부된다() {
        assertThat(IdempotencyKey.isValid(null)).isFalse();
        for (String key : List.of("", "short", "a".repeat(IdempotencyKey.MIN_LENGTH - 1), "-starts-with-dash-0001",
                "has..dots-0000000001", "has space 0000000001", "k".repeat(IdempotencyKey.MAX_LENGTH + 1),
                "한글은허용되지않는다0000000"))
            assertThat(IdempotencyKey.isValid(key)).as(key).isFalse();
    }

    @Test
    void 헤더_이름과_code는_고정이다() {
        assertThat(IdempotencyKey.HEADER).isEqualTo("Idempotency-Key");
        assertThat(IdempotencyKey.REQUIRED_CODE).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }
}
