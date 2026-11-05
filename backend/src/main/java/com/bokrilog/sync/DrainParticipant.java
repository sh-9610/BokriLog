package com.bokrilog.sync;

/**
 * Worker 종료 drain(Story 3.8)에 함께 서는 다른 작업자(Story 5.2). sync 밖의 Worker 작업(계정 삭제 실행)이 이 계약을 구현하면 Worker 역할의
 * drain이 같은 순간에 새 claim을 멈추게 하고, 같은 {@code drain-timeout} 예산 안에서 진행 중이 0이 되기를 기다리며, 그동안 heartbeat를
 * 민다. 시간 안에 비지 않으면 종료 코드는 {@link WorkerExit#DRAIN_INCOMPLETE}다. 단일 Worker 잠금을 잃었을 때도 claim을 멈춘다.
 */
public interface DrainParticipant {

    /** 새 claim을 멈춘다. 되돌리지 않는다. 진행 중 작업은 다음 경계에서 lease를 놓고 끝나도 된다. */
    void stopClaims();

    /** 진행 중인 claim 구간과 작업의 수. */
    int inFlight();

    /** 진행 중 lease를 민다. 실패는 삼킨다(lease 만료가 권위다). */
    void heartbeat();
}
