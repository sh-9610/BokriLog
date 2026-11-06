package com.bokrilog.sync;

import com.bokrilog.sync.internal.SyncLease;
import java.time.Duration;

/**
 * 작업 lease 수명의 공개 값(Story 5.2). 값의 권위는 {@code sync.internal.SyncLease}다 — SQL 조각과 같은 30초를 {@link Duration}으로
 * 밖에 보인다. 계정 삭제 실행(identity 표, 루트 Worker)이 같은 lease 규칙을 쓰려고 이 값을 받는다. 리터럴을 다시 쓰지 않는다.
 */
public final class WorkerLease {

    /** lease 수명. heartbeat가 이 간격만큼 다시 민다. */
    public static final Duration DURATION = SyncLease.DURATION;

    private WorkerLease() {}
}
