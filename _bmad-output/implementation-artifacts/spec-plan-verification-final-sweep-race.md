---
title: '마지막 점검 재획득 시험을 잠금 문으로 고정'
type: 'bugfix'
created: '2026-11-06'
status: 'done'
baseline_commit: '5e4e61d73f5aff985130bea396d9ecdbda41bb51'
review_loop_iteration: 1
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `PlanVerificationTest.aContinuationReclaimedInItsFinalSweepPublishesOnceAtItsTcFinal`이 간헐적으로 실패한다(PR #145 run 42197758258 2차 시도, 같은 커밋 1차는 통과). 시험은 페이지 저장을 `pg_sleep(0.7)`로 늦출 뿐이라 `FINAL_SWEEP`을 본 뒤 lease를 만료시키기 전에 원래 holder가 `CALCULATION`으로 넘어갈 수 있고, 그러면 재획득 규칙(재개 가능 단계는 `TIME`·`COLLECTION`·`FINAL_SWEEP`)대로 `FAILED SYNC_INTERRUPTED`로 끝난다.

**Approach:** 시간이 아니라 잠금으로 holder를 붙잡는다. 시험이 advisory lock 문을 쥔 채 이 사용자의 마지막 점검 페이지 저장을 문 앞에서 기다리게 하고, holder가 `FINAL_SWEEP`에서 문에 막힌 것을 확인한 뒤 lease를 만료시키고 나서 문을 연다. 제품 코드는 바꾸지 않는다.

## Boundaries & Constraints

**Always:** 시험이 단정하는 계약(재획득 1회 `resume_count=1`, 발행 1회 `pointerVersion=2`, 두 watermark가 첫 holder가 고정한 `tcFinal`, 진행 중 자식 없음)은 그대로 둔다. 문이 기대한 자리에서 막지 못하면 시험은 조용히 통과하지 않고 대기 시간 초과로 실패한다. 문과 trigger는 시험이 실패해도 `finally`에서 풀고 지운다.

**Ask First:** 제품 코드(`sync`·`plan`)를 바꿔야만 고칠 수 있다는 결론이 나면 멈추고 묻는다.

**Never:** 대기 시간·`pg_sleep` 값만 늘려 확률을 낮추는 수정. 재시도(JUnit retry·Gradle `retry`) 도입. 같은 도우미를 쓰는 다른 재획득 시험 셋(수집 중 재획득)의 동작 변경.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 정상 | 문을 쥔 채 continuation 접수 → holder가 마지막 점검 첫 페이지에서 막힘 → lease 만료 → 문 열기 | 재획득된 작업이 `FINAL_SWEEP`에서 이어 `SUCCEEDED`, 기존 단정 전부 통과 | N/A |
| holder가 문에 닿지 않음 | 마지막 점검 페이지 판별이 틀렸거나 단계가 바뀜 | 막힘 대기가 시간 초과로 실패하고 마지막 상태를 메시지에 싣는다 | `AssertionError` |
| 시험 중간 실패 | 단정·대기 실패 | 문 해제와 trigger·함수 삭제가 `finally`에서 일어나 다음 시험에 남지 않는다 | `finally` |

</frozen-after-approval>

## Code Map

- `backend/src/test/java/com/bokrilog/PlanVerificationTest.java` `aContinuationReclaimedInItsFinalSweepPublishesOnceAtItsTcFinal` -- 고칠 시험. 기준 커밋에서는 `slowPageStores` → `continuation` → `awaitStage(job,"FINAL_SWEEP")` → `expireLease` → `awaitSync(job,120)` 순서이고, 그 뒤 `initial_tc`·`tc`·`resume_count`·발행 단정이 이어진다(이하 "기존 단정").
- `PlanVerificationTest.java:1597-1608` (`slowPageStores`·`dropSlowPageStores`) -- 공유 도우미. 571·602·1228의 수집 중 재획득 시험이 쓴다 — 바꾸지 않는다.
- `PlanVerificationTest.java:1634-1645` (`awaitStage`) -- 이 시험만 쓴다(1198). 문 앞 막힘 대기로 대체한다. 마지막 점검 페이지 판별식 `e.range_end > j.initial_tc`(`j.collection_job_id = e.job_id`)를 여기서 가져온다.
- `PlanVerificationTest.java:1647-1650` (`expireLease`) -- 다른 시험이 계속 쓴다. 이 시험은 더 쓰지 않는다.
- `PlanVerificationTest.java:641`·`1118` -- 이 파일은 `TransactionTemplate`을 완전 수식 이름으로 쓴다(import 없음). 새 코드도 그 관례를 따른다.
- `backend/src/test/java/com/bokrilog/PlanSyncTestBase.java:287-315` (`holdPlanLock`·`awaitBlockedOnPlanLock`) -- 전용 가상 스레드 트랜잭션이 advisory lock을 쥐고 `CountDownLatch`로 푸는 기존 패턴(안전 시한 30초). 문을 쥐는 도우미는 이 모양을 따른다.
- `backend/src/main/java/com/bokrilog/sync/internal/PlanSyncRepository.java:270-292` (`RESUMABLE`·`claim`) -- 읽기 전용 근거: `CALCULATION`에서 재획득되면 중단이다.
- `backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java:1113-1119`·`PlanSyncRepository.java:305-310`·`InitialCollectionRepository.java:124-129,160-175` (`lockOwned`·`storePage`) -- 읽기 전용 근거: 페이지 저장 트랜잭션은 계획 작업 행과 수집 작업 행을 `FOR UPDATE`로 잡고 fencing(`lease_until>statement_timestamp()`)을 본 뒤 INSERT하고, INSERT 뒤에는 계획 작업 lease를 다시 보지 않는다. 따라서 그 INSERT의 trigger가 같은 트랜잭션에서 계획 작업 lease를 만료시키면 잠금 대기 없이 페이지와 함께 커밋된다.
- 걸린 열린 회고 액션 없음. 같은 계열의 닫힌 작업은 Epic 4 회고 AI-6(`spec-flaky-test-diagnostics.md`)이고, 그것이 부분 해소로 남긴 159(계획 종단 시험 간헐 실패)는 이 fix가 만든 defer와 연결한다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/test/java/com/bokrilog/PlanVerificationTest.java` -- 문 키 상수(두 정수, 값의 근거와 운영 코드에 두 정수 advisory 키가 없다는 주석)를 클래스의 다른 상수 곁에 둔다. 이 사용자의 마지막 점검 페이지 INSERT이고 그 계획 작업의 `resume_count=0`일 때만, trigger가 문(공유 advisory lock)을 기다린 뒤 같은 트랜잭션에서 그 계획 작업의 `lease_until`을 과거로 만든다 -- holder가 `FINAL_SWEEP`을 벗어나기 전에 lease를 잃는 것을 잠금과 트랜잭션 원자성만으로 보장한다. 재획득된 holder(`resume_count=1`)의 페이지는 막지도 만료시키지도 않는다.
- [x] 같은 파일 -- 문을 배타적으로 쥐는 `AutoCloseable` 도우미: 안전 시한 90초(2차 리뷰 patch, 원래 30초), 문 열기는 holder 스레드가 끝났고 예외 없이 끝났음을 단정한다(시한 경과·스레드 예외는 시험 실패). trigger·함수 생성이 도중에 실패해도 둘 다 지우고, 정리 중 예외는 원래 예외에 `addSuppressed`로 붙인다. 정리는 시험이 실패해도 일어난다.
- [x] 같은 파일 -- `awaitStage`를 "작업이 `RUNNING`·`FINAL_SWEEP`이고 문 키(`classid`·`objid`·`objsubid=2`)에 미승인 대기가 있다"를 기다리는 대기로 바꾼다. 시간 초과 메시지에 마지막 상태(상태|단계|대기 수)를 싣는다.
- [x] 같은 파일 -- 시험 본문을 문 쥐기 → continuation 접수 → 문 앞 막힘 대기 → 문 열기 → `awaitSync` 순서로 바꾸고 `slowPageStores`·`expireLease`를 쓰지 않는다. `awaitSync` 결과가 `SUCCEEDED`가 아니면 그 작업 행의 `status|stage|failure_code|resume_count`를 실패 메시지에 싣는다. 기존 단정은 그대로 둔다.

**Acceptance Criteria:**
- Given 고친 시험, when 단독으로 5회 연속 돌리면, then 5회 모두 통과한다.
- Given 고친 시험, when CPU 수 × 2개의 bash 바쁜 루프를 건 채 5회 돌리면, then 모두 통과한다.
- Given 문 판별식을 일부러 틀리게 한 국소 실험, when 시험을 돌리면, then 막힘 대기 시간 초과로 실패한다(실험은 커밋하지 않는다).
- Given `PlanVerificationTest` 전체, when 돌리면, then 수집 중 재획득 시험 셋을 포함해 모두 통과한다.
- Given 문 holder가 안전 시한으로 끝나는 경우, when 문을 열면, then 시험이 실패한다(안전 시한에 기대어 통과하지 않는다 — `open()`이 단정).

## Spec Change Log

- 구현(1차): Code Map의 "페이지 저장 트랜잭션은 수집 작업 행만 잠근다(교착 없음)" 전제가 틀렸다 — `PlanSyncService.java:1114`가 `syncs.lockOwned`로 계획 작업 행도 `FOR UPDATE`로 잡는다. 전제대로 만든 첫 구현은 만료 UPDATE가 문 뒤에서 막혀 문 holder의 180초 안전 시한이 지나서야 통과했다(181초). 고친 것: 만료를 별도 가상 스레드로 걸고 `pg_stat_activity`에서 그 UPDATE가 행 잠금을 기다리는 것을 본 뒤 문을 연다(`expireLeaseBehindHolder`) — holder가 커밋하면 대기열 앞의 만료가 holder의 다음 fenced 문보다 먼저 행을 얻는다. Code Map·Task 3·Design Notes를 맞췄다. 피한 상태: 시간 초과에 기대어 통과하는 시험. KEEP: 두 정수 키 문·`pg_locks` 정확 대조·`slowPageStores` 불변.
- 1차 리뷰(bad_spec, `review_loop_iteration` 1): verification-gap·blind-hunter·edge-case-hunter가 같은 결함을 짚었다 — PostgreSQL 행 잠금 대기는 엄격한 선착순이 아니어서(커밋된 locker 뒤에 새로 온 트랜잭션은 tuple lock 대기열을 거치지 않고 행을 얻을 수 있다) 문이 열린 직후 holder의 다음 트랜잭션이 대기 중인 만료보다 먼저 행을 잡고 fencing을 통과해 `CALCULATION`으로 갈 수 있다. 또 `pg_stat_activity` 문자열 대조는 heartbeat·다른 시험의 같은 접두사 문과 job 구분이 없고, 만료 스레드는 실패 경로에서 정리되지 않는다. 고친 것: 만료를 시험 스레드가 아니라 문 trigger가 holder 자신의 페이지 저장 트랜잭션 안에서 한다(`resume_count=0`일 때만) — 별도 만료 스레드·`pg_stat_activity` 대조를 없앴다. 같은 루프의 patch(새 구현에 포함): 문 holder 시한 30초와 시한 경과·스레드 예외의 시험 실패, 정리 중 `addSuppressed`, trigger 생성 실패 시 함수 정리, 실패 메시지에 작업 행 상태, `TransactionTemplate` 완전 수식 이름, 문 키 상수 위치·근거, Verification 반복 명령. defer 2건(`deferred-work.md`): 첫 holder `tc` 미대조, 수집 중 재획득 시험 셋의 같은 시간 기반 경합. reject: `context: []`, `finally`↔try-with-resources 표현 차이. 피한 상태: 좁지만 남는 원래 경합. KEEP: 두 정수 키 문과 `pg_locks` 정확 대조, 문 앞 막힘을 본 뒤에만 문을 여는 순서, `slowPageStores` 불변, 기존 단정 불변, 문이 틀리면 시간 초과로 크게 실패.
- 2차 리뷰 patch: `awaitSync` 시간 초과에도 작업 행 상태(`status|stage|failure_code|resume_count`)를 싣는다(edge-case-hunter).
- 2차 리뷰 patch: 문 획득 트랜잭션에 `lock_timeout`을 걸어 문을 닫지 못한 holder 스레드·연결이 남지 않게 한다(edge-case-hunter).
- 2차 리뷰 patch: 대기는 반복 횟수가 아니라 `System.nanoTime()` 마감으로 재고 예산을 넓힌다 — 문 앞 막힘 대기 60초(기준 커밋 `awaitStage`와 같다), 문 holder 안전 시한 90초(문 생성부터 재므로 막힘 대기보다 길어야 한다). 부하 러너에서 수집 단계가 길어지면 엉뚱한 "door holder failed"로 실패하던 위험이다(verification-gap·blind-hunter).
- 2차 리뷰 patch: 막힘 대기 상태에 `resume_count`를 싣는다(edge-case-hunter).
- 2차 리뷰 patch: 문 앞에서 막힌 시점의 `tc`를 읽어 완료 뒤 `tc`(=`tcFinal`)와 같은지 단정한다 — Always의 "첫 holder가 고정한 `tcFinal`"을 실제로 확인한다(blind-hunter). 1차에서 append한 같은 defer는 아직 커밋 전이라 뺐다.
- 2차 리뷰 patch: 문 키 주석의 "뜻이 없다"와 "(SPEC 작성일과 1)"의 모순을 고친다. SPEC은 줄 번호 대신 메서드 이름으로 지칭하고, Design Notes에 수집 작업 lease 재검사가 `now()`(트랜잭션 시작 시각)라 문 대기 중에도 통과한다는 전제를 적고, Verification 부하 명령을 이식 가능하게 바꾼다. 수집 중 재획득 defer는 다른 파일의 같은 구조와 159까지 넓혔다. `DEVELOPMENT.md` `### 간헐 실패`에 잠금 문 패턴 한 항목을 더했다(blind-hunter).
- 2차 리뷰 reject: 회당 시간을 시험 안에서 강제(`open()`이 시한 경과를 이미 실패로 단정), 재획득 단계 직접 단정(재개 holder가 곧 넘어가 단정 자체가 경합이 된다), 문 대기 중 heartbeat 막힘(heartbeat는 `FOR UPDATE SKIP LOCKED`라 막히지 않는다), 두 정수 키 충돌 방지 시험. 동결 Approach의 "lease를 만료시키고 나서 문을 연다"를 trigger 안 만료로 읽은 해석은 사용자의 상시 지시(추천안으로 진행·보고)에 따라 진행하고 present에서 보고한다.

## Design Notes

실패 근거(run 42197758258 2차 시도의 gradle 보고서): 시험 실패 직전 이 작업이 `purpose=CATCH_UP reason=LONG_GAP_CONTINUATION status=FAILED stage=CALCULATION code=SYNC_INTERRUPTED`로 끝났다 — 원래 holder가 `CALCULATION`에 들어간 뒤 재획득됐다.

holder는 문 앞에서 이미 `lockOwned`로 계획 작업·수집 작업의 fencing을 통과하고 두 행을 쥐었다. 문을 열면 trigger가 같은 트랜잭션에서 계획 작업 `lease_until`을 과거로 만들고 페이지와 함께 커밋한다 — 다른 세션의 잠금 대기가 끼어들 틈이 없다. holder의 다음 fenced 문은 실패해 `LeaseLost`가 되고(heartbeat도 만료된 행을 갱신하지 않는다), dispatcher가 단계 `FINAL_SWEEP`인 채로 재획득하므로 재개 가능이다. 재획득 holder는 `resume_count=1`이라 trigger를 그대로 지난다. 문 대기 중에도 `storePage`의 수집 작업 lease 재검사(`lease_until>now()`)가 통과하는 것은 `now()`가 트랜잭션 시작 시각이기 때문이다 — 그 검사가 `statement_timestamp()`로 바뀌거나 문 대기가 수집 lease(30초)를 넘으면 시험은 `LeaseLost`가 아니라 수집 lease 상실로 실패해 드러난다. Intent의 "lease를 만료시키고 나서 문을 연다"는 lease 상실이 holder가 마지막 점검을 벗어나기 전에 확정된다는 뜻으로 읽었고, 만료가 문 바로 뒤 holder 트랜잭션 안에서 일어나는 것이 그 순서를 가장 강하게 보장한다.

## Verification

**Commands:**
- `for i in 1 2 3 4 5; do backend/gradlew --project-dir backend test --tests 'com.bokrilog.PlanVerificationTest.aContinuationReclaimedInItsFinalSweepPublishesOnceAtItsTcFinal' --rerun || break; done` -- expected: 5회 통과, 회당 시험 시간 30초 안
- 같은 반복을 부하 아래에서: `n=$(( $(getconf _NPROCESSORS_ONLN) * 2 )); pids=(); for k in $(seq 1 $n); do (while :; do :; done) & pids+=($!); done; trap 'kill "${pids[@]}"' EXIT` 뒤 위 루프 -- expected: 5회 통과(Windows Git Bash도 `getconf`가 있다)
- `backend/gradlew --project-dir backend test --tests 'com.bokrilog.PlanVerificationTest'` -- expected: 전부 통과
- `backend/gradlew --project-dir backend compileTestJava --rerun` -- expected: `-Werror` 경고 없음

**Manual checks:**
- 문 판별식을 일부러 틀리게 바꾼 국소 실험이 막힘 대기 시간 초과로 실패하는지 보고 원복한다.

## Suggested Review Order

**잠금 문과 trigger 안 만료(설계의 중심)**

- 마지막 점검 첫 페이지가 문을 기다린 뒤 같은 트랜잭션에서 lease를 만료시킨다
  [`PlanVerificationTest.java:1698`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1698)

- 만료 UPDATE — holder 자신의 트랜잭션이라 잠금 대기 순서에 기대지 않는다
  [`PlanVerificationTest.java:1706`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1706)

- 문 holder: 시한 경과·예외는 `open()`이 시험 실패로 바꾼다
  [`PlanVerificationTest.java:1729`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1729)

- 문 획득에 `lock_timeout` 4초 — 닫지 못한 문이 스레드·연결을 남기지 않는다
  [`PlanVerificationTest.java:1741`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1741)

- 두 정수 키 근거 — 운영 advisory 키(bigint 하나)와 키 공간이 겹치지 않는다
  [`PlanVerificationTest.java:46`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L46)

**시험 본문의 순서와 단정**

- 문 닫기 → 접수 → 막힘 확인 → 문 열기 → 완료
  [`PlanVerificationTest.java:1216`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1216)

- 첫 holder가 문 앞에서 고정한 `tc`를 최종 `tcFinal`과 대조한다
  [`PlanVerificationTest.java:1237`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1237)

- 막힘 대기: RUNNING·FINAL_SWEEP·문 키 미승인 대기, 60초 마감
  [`PlanVerificationTest.java:1668`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1668)

**주변**

- 간헐 실패 절에 잠금 문 패턴 한 항목
  [`DEVELOPMENT.md:115`](../../DEVELOPMENT.md#L115)

- 시간 기반 lease 상실 시험들의 defer(177)
  [`deferred-work.md:542`](deferred-work.md#L542)
