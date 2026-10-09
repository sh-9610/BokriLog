package com.bokrilog.operations;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 감지 지점이 올리는 강한 신호 하나(Story 3.3).
 *
 * <p><b>증거는 내부 참조뿐이다(AC-6).</b> 값은 정규 UUID(작업·계산·관측 행 ID)이거나 음 아닌 정수(집계 수)만 받는다 —
 * 금융값·이메일·hash 원문·자유 문장이 들어올 모양이 없다. 키도 짧은 camelCase 이름뿐이다. 모양이 아니면 생성이 실패한다.
 *
 * @param type 판정표가 승인한 종류
 * @param subject 감지 지점이 가진 대상 차원. scope 규칙이 이것으로 대안을 고른다(모르는 차원은 넘기지 않는다)
 * @param targetUserId 대상의 소유자. 사용자 귀속 scope(계획·portfolio·연결)로 판정되면 필수다
 * @param evidence 판정표의 필수 증거 키를 담은 내부 참조
 * @param detectedAt 감지 지점이 읽은 DB 시각. 없으면 기록 트랜잭션의 첫 DB 시각이 감지 시각이다
 */
public record Signal(SignalType type, RestrictionSubject subject, @Nullable UUID targetUserId,
        Map<String, String> evidence, @Nullable Instant detectedAt) {

    private static final Pattern KEY = Pattern.compile("[a-z][A-Za-z]{0,31}");

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final Pattern COUNT = Pattern.compile("0|[1-9][0-9]{0,8}");

    /** 증거 항목 수의 상한. 신호는 참조 몇 개면 충분하다. */
    private static final int MAX_EVIDENCE = 8;

    public Signal {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.size() > MAX_EVIDENCE) {
            throw new IllegalArgumentException("증거 항목이 너무 많다.");
        }
        for (var entry : evidence.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("증거 키의 모양이 아니다.");
            }
            if (value == null || !(UUID_TEXT.matcher(value).matches() || COUNT.matcher(value).matches())) {
                // 값 자체는 메시지에 담지 않는다.
                throw new IllegalArgumentException("증거 값은 내부 참조(UUID)나 집계 수만 받는다.");
            }
        }
        // 정렬된 불변 사본: 같은 증거는 같은 JSON이 된다.
        evidence = java.util.Collections.unmodifiableMap(new TreeMap<>(evidence));
    }
}
