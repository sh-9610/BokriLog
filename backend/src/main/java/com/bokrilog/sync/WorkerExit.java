package com.bokrilog.sync;

/**
 * Worker 역할 프로세스의 종료 코드(Story 3.8). Worker 역할({@code bokrilog.role=worker})에만 빈이 있다.
 *
 * <p>종료 신호({@code SIGTERM}, {@code docker compose stop})가 곧 drain 요청이다. 별도 HTTP drain 경로를 두지 않는 이유는 공개
 * 표면이 하나 늘기 때문이다. drain의 결과는 프로세스 종료 코드 하나로 배포 스크립트에 전해진다 — JVM은 신호로 끝나면 143을
 * 내므로, 진입점({@code BokriLogApplication})이 컨텍스트가 닫힌 뒤 이 값으로 프로세스를 끝낸다.
 *
 * <ul>
 *   <li>{@link #DRAINED} — 진행 중인 작업이 모두 끝났다.
 *   <li>{@link #DRAIN_INCOMPLETE} — drain-timeout 안에 끝나지 않았다(로그 {@code worker.drain.incomplete}). 남은 작업은 lease 만료
 *       뒤 다음 Worker가 다시 잡는다.
 *   <li>{@link #LOCK_LOST} — 단일 Worker 잠금의 전용 연결을 잃었다(로그 {@code worker.lock.lost}). 새 claim을 멈추고 끝낸다 —
 *       다른 Worker가 잠금을 잡을 수 있게 된 순간부터 이 프로세스는 dispatch하지 않는다.
 * </ul>
 */
public interface WorkerExit {

    int DRAINED = 0;

    int DRAIN_INCOMPLETE = 75;

    int LOCK_LOST = 76;

    /** 지금까지의 결과에 따른 종료 코드. */
    int exitCode();
}
