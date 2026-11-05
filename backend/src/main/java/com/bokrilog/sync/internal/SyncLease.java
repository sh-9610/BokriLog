package com.bokrilog.sync.internal;

/**
 * 작업 lease의 수명 <b>한 곳</b>.
 *
 * <p>여섯 작업 표(첫 스냅샷·초기 수집·판정·환산·발행·증분 동기화)의 claim·heartbeat·child insert·take-over가 각자
 * 30초 interval SQL 리터럴을 각자 들고 있던 것(18회)을 이 상수가 소유한다. 값은 바꾸지 않았다 — 바뀐 것은 사본의
 * 수뿐이다(Epic 2 회고 AI-8). 저장소에서 이 리터럴을 다시 쓰지 않는다 — {@code SyncLeaseTest}가 운영 소스를 훑어 이
 * 파일에만 있음을 고정한다. {@code public}인 이유는 시험 픽스처({@code SuccessorPlanTest})가 같은 조각을 쓰기 때문이며
 * 운영 코드에서 {@code sync.internal} 밖의 접근은 {@code ModuleBoundaryTest}가 막는다.
 *
 * <p>시계 함수는 여기 없다. {@code now()}를 쓰는 표와 {@code statement_timestamp()}를 쓰는 표가 갈라져 있고 그 차이는
 * 각 저장소의 근거 주석이 소유한다({@code PlanPublicationRepository}·{@code PlanSyncRepository}). 이 상수는 더해지는
 * 간격만 말한다.
 */
public final class SyncLease {

    /**
     * lease 수명 30초의 SQL 조각. heartbeat가 이 간격만큼 다시 민다. 컴파일 타임 상수라 SQL 문자열 연결에 그대로
     * 들어간다({@code PlanSyncRepository.FENCE} 선례).
     */
    public static final String SQL_INTERVAL = "interval '30 seconds'";

    /**
     * 같은 수명의 {@link java.time.Duration}(Story 5.2). SQL 조각과 한 쌍이고 {@code SyncLeaseTest}가 둘의 일치를 본다 — 공개 값은
     * {@code com.bokrilog.sync.WorkerLease}가 낸다.
     */
    public static final java.time.Duration DURATION = java.time.Duration.ofSeconds(30);

    private SyncLease() {}
}
