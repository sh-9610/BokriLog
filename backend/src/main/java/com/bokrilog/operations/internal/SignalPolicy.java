package com.bokrilog.operations.internal;

import com.bokrilog.operations.RestrictionFeature;
import com.bokrilog.operations.RestrictionScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 판정표 {@code operations_signal_policy}의 행 하나(Story 3.3, AC-1).
 *
 * <p>scope 규칙은 {@code |}로 나뉜 대안의 목록이고, 대안 하나는 {@code +}로 이은 {@code <기능>:<scope>} 항목들이다. 앞의
 * 대안이 더 좁다 — 신호가 그 대안의 대상 차원을 모두 가졌으면 그것을 쓰고, 아니면 다음 대안으로 넓힌다. 문법·기능별 허용
 * scope({@link RestrictionFeature#admits})에 맞지 않는 규칙은 {@link #parse}가 {@code null}로 돌려주고, 서비스는 그것을
 * 판정표를 읽지 못한 것과 같게 다룬다(fail-closed).
 */
record SignalPolicy(String policyVersion, String signalType, int threshold, int perSubjectThreshold,
        int windowSeconds, int distinctSubjects, Set<String> requiredEvidence, List<List<Item>> alternatives) {

    /** 규칙의 항목 하나. */
    record Item(RestrictionFeature feature, RestrictionScope scope) {

        /** 원장·신호 행의 {@code <기능>:<scope>}. */
        String text() {
            return this.feature.name() + ":" + this.scope.name();
        }
    }

    /** 판정표를 읽지 못했을 때의 범위: 신규 연결+동기화 전역(AC-2·AC-5). */
    static final List<Item> UNAVAILABLE_FALLBACK = List.of(
            new Item(RestrictionFeature.NEW_CONNECTION, RestrictionScope.GLOBAL),
            new Item(RestrictionFeature.SYNC, RestrictionScope.GLOBAL));

    /** 창 집계 신호가 증거로 내는 수의 키. */
    static final String OBSERVED_COUNT = "observedCount";

    static final String DISTINCT_SUBJECTS = "distinctSubjects";

    static final String MIN_PER_SUBJECT = "minPerSubject";

    static final String WINDOW_SECONDS = "windowSeconds";

    /** scope 규칙을 읽는다. 문법이나 허용 조합이 맞지 않으면 {@code null}이다. */
    static @Nullable List<List<Item>> parse(String rule) {
        List<List<Item>> alternatives = new ArrayList<>();
        for (String alternative : rule.split("\\|", -1)) {
            List<Item> items = new ArrayList<>();
            for (String part : alternative.split("\\+", -1)) {
                String[] pair = part.split(":", -1);
                if (pair.length != 2) {
                    return null;
                }
                RestrictionFeature feature = enumOf(RestrictionFeature.class, pair[0]);
                RestrictionScope scope = enumOf(RestrictionScope.class, pair[1]);
                if (feature == null || scope == null || !feature.admits(scope)) {
                    return null;
                }
                items.add(new Item(feature, scope));
            }
            alternatives.add(List.copyOf(items));
        }
        if (alternatives.isEmpty()) {
            return null;
        }
        // 마지막 대안은 언제나 성립해야 한다 — 범위를 특정할 수 없으면 전역으로 접는다(AC-2).
        if (alternatives.getLast().stream().anyMatch(item -> item.scope() != RestrictionScope.GLOBAL)) {
            return null;
        }
        return List.copyOf(alternatives);
    }

    /**
     * 증거가 이 행의 필수 증거와 threshold·window를 채우는가. 채우지 못하면 이유(메시지에 값 없음)를, 채우면 {@code null}을
     * 돌려준다. 사건 하나가 곧 신호인 행(threshold 1, 창 없음, 대상 하나)은 필수 키만 본다. 창이 있는 행은 집계 증거 넷을
     * 모두 본다 — 0건이나 형식이 아닌 집계는 승격하지 않는다.
     */
    @Nullable String refusal(Map<String, String> evidence) {
        for (String key : this.requiredEvidence) {
            if (!evidence.containsKey(key)) {
                return "필수 증거가 빠졌다.";
            }
        }
        if (this.windowSeconds == 0) {
            return null;
        }
        long observed = count(evidence, OBSERVED_COUNT);
        long subjects = count(evidence, DISTINCT_SUBJECTS);
        long perSubject = count(evidence, MIN_PER_SUBJECT);
        long window = count(evidence, WINDOW_SECONDS);
        if (observed < 0 || subjects < 0 || perSubject < 0 || window < 0) {
            return "창 집계 증거가 빠졌거나 수가 아니다.";
        }
        if (observed < this.threshold) {
            return "관측 수가 threshold에 못 미친다.";
        }
        if (subjects < this.distinctSubjects) {
            return "서로 다른 대상 수가 판정표에 못 미친다.";
        }
        if (perSubject < this.perSubjectThreshold) {
            return "대상당 관측 수가 판정표에 못 미친다.";
        }
        if (window == 0 || window > this.windowSeconds) {
            return "집계 창이 판정표의 창 밖이다.";
        }
        return null;
    }

    /** 수 증거. 없거나 수가 아니면 -1. */
    private static long count(Map<String, String> evidence, String key) {
        String value = evidence.get(key);
        if (value == null || value.isEmpty() || value.length() > 9 || !value.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        return Long.parseLong(value);
    }

    private static <E extends Enum<E>> @Nullable E enumOf(Class<E> type, String name) {
        for (E value : type.getEnumConstants()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return null;
    }
}
