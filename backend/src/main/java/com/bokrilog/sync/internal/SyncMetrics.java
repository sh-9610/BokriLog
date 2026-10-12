package com.bokrilog.sync.internal;

import com.bokrilog.sync.SyncTelemetry;
import com.bokrilog.sync.SyncTelemetry.Kind;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 동기화 계측(Story 3.7 AC-3). 이름과 tag 값은 {@link SyncTelemetry}의 닫힌 어휘뿐이고, 등록은 Micrometer {@link MeterRegistry}
 * 하나로 한다 — 이 모듈은 Actuator를 모른다. 정의(분모·기간·환경)는 {@link SyncTelemetry}에 있다.
 *
 * <p><b>커밋된 사실만 센다.</b> 시도·결말은 그것을 쓴 트랜잭션이 커밋된 뒤에 센다({@link #afterCommit}). 롤백된 admission이나
 * 경합으로 다시 판정한 admission이 두 번 세지지 않는다.
 *
 * <p><b>대기 나이는 여기 없다.</b> 그 주기 측정은 스케줄링이 도는 역할({@code worker}·{@code all})에만 있는 {@link SyncQueueAgeMetrics}가
 * 한다(Story 3.8) — 측정하지 않는 API 역할이 NaN gauge를 내보내지 않게.
 *
 * <p><b>카운터는 미리 0으로 등록한다.</b> 처음 증가할 때 시계열이 생기면 {@code increase()}가 그 첫 증가를 놓친다. 주요 종류·결말·
 * 사유 조합({@link #PRE_REGISTERED})을 기동 때 0으로 만든다. 표 밖 조합은 처음 셀 때 생긴다.
 */
@Component
class SyncMetrics {

    /** 기동 때 0으로 등록하는 결말 조합: 종류 → {결말, 사유}. */
    static final Map<Kind, java.util.List<String[]>> PRE_REGISTERED = Map.of(
            Kind.PLAN_SYNC, java.util.List.of(
                    new String[] {SyncTelemetry.SUCCESS, SyncTelemetry.NONE},
                    new String[] {SyncTelemetry.EXCLUDED, "RESTRICTED"},
                    new String[] {SyncTelemetry.EXCLUDED, "REVIEW_HELD"},
                    new String[] {SyncTelemetry.EXCLUDED, "LONG_GAP"},
                    new String[] {SyncTelemetry.EXCLUDED, "NOT_PERMITTED"},
                    new String[] {SyncTelemetry.FAILURE, "PROVIDER_UNAVAILABLE"},
                    new String[] {SyncTelemetry.FAILURE, "SYNC_FAILED"},
                    new String[] {SyncTelemetry.FAILURE, "SYNC_INTERRUPTED"},
                    new String[] {SyncTelemetry.FAILURE, SyncTelemetry.OTHER}),
            Kind.FIRST_SNAPSHOT, java.util.List.of(
                    new String[] {SyncTelemetry.SUCCESS, SyncTelemetry.NONE},
                    new String[] {SyncTelemetry.EXCLUDED, "RESTRICTED"},
                    new String[] {SyncTelemetry.EXCLUDED, "NOT_PERMITTED"},
                    new String[] {SyncTelemetry.FAILURE, "UNAVAILABLE"},
                    new String[] {SyncTelemetry.FAILURE, "PROVIDER_UNAVAILABLE"},
                    new String[] {SyncTelemetry.FAILURE, SyncTelemetry.OTHER}));

    /** 기동 때 0으로 등록하는 시도 조합. */
    static final Map<Kind, java.util.List<String>> PRE_REGISTERED_TRIGGERS = Map.of(
            Kind.PLAN_SYNC, java.util.List.of("ENTRY", "VISIBILITY", "MANUAL", "SYSTEM"),
            Kind.FIRST_SNAPSHOT, java.util.List.of("USER"));

    private final MeterRegistry registry;

    SyncMetrics(MeterRegistry registry) {
        this.registry = registry;
        PRE_REGISTERED_TRIGGERS.forEach((kind, triggers) -> triggers.forEach(trigger ->
                registry.counter(SyncTelemetry.ATTEMPTS, "kind", kind.tag(), "trigger", trigger)));
        PRE_REGISTERED.forEach((kind, pairs) -> pairs.forEach(pair ->
                registry.counter(SyncTelemetry.OUTCOMES, "kind", kind.tag(), "outcome", pair[0], "reason", pair[1])));
    }

    /** 유효 시도 하나(작업 행이 커밋되면). */
    void attempt(Kind kind, @Nullable String trigger) {
        String value = SyncTelemetry.trigger(trigger);
        afterCommit(() -> this.registry.counter(SyncTelemetry.ATTEMPTS, "kind", kind.tag(), "trigger", value)
                .increment());
    }

    /** 결말 또는 제외 하나(그 사실이 커밋되면). */
    void outcome(Kind kind, String outcome, @Nullable String code) {
        outcomes(kind, outcome, code, 1);
    }

    /** 같은 결말 여럿(한 문장이 작업 여럿을 끝냈을 때). 0 이하면 아무것도 하지 않는다. */
    void outcomes(Kind kind, String outcome, @Nullable String code, int count) {
        if (count <= 0) return;
        String reason = SyncTelemetry.SUCCESS.equals(outcome) ? SyncTelemetry.NONE : SyncTelemetry.reason(code);
        afterCommit(() -> this.registry.counter(SyncTelemetry.OUTCOMES, "kind", kind.tag(), "outcome", outcome,
                "reason", reason).increment(count));
    }

    /**
     * 작업 행의 종단 상태를 결말로. 성공은 {@code SUCCEEDED}, 막힘은 {@code BLOCKED}(제한·권한·계획 변경 — 정책이 막은 것),
     * 그 밖({@code FAILED}·{@code NEEDS_REVIEW})은 실패다. 지운 계정의 종단({@code ERASED})은 세지 않는다.
     */
    void ending(Kind kind, String status, @Nullable String code) {
        switch (status) {
            case "SUCCEEDED" -> outcome(kind, SyncTelemetry.SUCCESS, null);
            case "BLOCKED" -> outcome(kind, SyncTelemetry.EXCLUDED, code);
            case "ERASED" -> {
                // 계정 삭제가 끝낸 작업은 운영 결말이 아니다.
            }
            default -> outcome(kind, SyncTelemetry.FAILURE, code);
        }
    }

    /** 트랜잭션 안이면 커밋 뒤에, 밖이면 바로. */
    private static void afterCommit(Runnable count) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    count.run();
                }
            });
        } else {
            count.run();
        }
    }
}
