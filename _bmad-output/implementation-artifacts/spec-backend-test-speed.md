---
title: '백엔드 시험 wall-clock 단축: 워커 dispatch 간격 설정화와 고정 대기 제거'
type: 'chore'
created: '2026-09-30'
status: 'done'
review_loop_iteration: 0
baseline_commit: '3824c3cb530e1a09012b4cc8a2fe899d0802c8b6'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-BokriLog-2026-08-24/CI-PLAN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 백엔드 `clean build`(`--no-parallel --max-workers=1`)가 로컬 12분 56초, CI `backend-quality`(PR #52) 13분 0초로 CI-PLAN의 backend 예산 8분을 넘고, Story 2.11은 그 때문에 job timeout을 15분에서 20분으로 올렸다. 시간의 대부분은 `FirstSnapshotTest`(117건 601초, 건당 약 5.1초)와 `IncrementalSyncTest`(17건 124초)다. 원인은 둘이다 — `FirstSnapshotWorker`의 모든 dispatch가 `@Scheduled(fixedDelay=1000)`이라 실제 파이프라인(첫 스냅샷 → 수집 → 판정 → 환산 → 발행 → 증분 동기화)을 모는 시험이 단계마다 최대 1초씩 tick을 기다리고, 시험 곳곳에 고정 `Thread.sleep`(1.2~6초, `tc1+11초`)이 있다.

**Approach:** dispatch 간격을 property(`bokrilog.sync.worker.dispatch-interval`, 운영 기본 `1s`)로 바꾸고, 시험은 기존의 유일한 등록점 `AuthFlowTestBase`의 `@DynamicPropertySource`에서 100ms를 준다. 관측 가능한 조건을 기다리는 고정 sleep은 조건 대기로 바꾸고, "일어나지 않음"을 증명하는 시간 창은 남기되 새 tick 기준(5 tick = 500ms)으로 줄인다. 단계 완료를 기다리는 helper의 poll 간격도 100ms에서 20ms로 줄인다(각 helper의 총 대기 상한은 그대로).

## Boundaries & Constraints

**Always:**
- 운영 동작은 그대로다. dispatch 기본 `1s`, heartbeat `5s`, lease 30초, claim·heartbeat SQL 불변.
- 시험 property는 `AuthFlowTestBase.authProperties` 한 곳에만 더한다. 새 `@DynamicPropertySource`·새 컨텍스트를 만들지 않는다.
- 단정은 약해지지 않는다. 기대값·행 수·로그 줄 수 단정을 그대로 두고 대기 방식만 바꾼다.
- 부재 증명 창은 원래 창이 준 dispatch 기회 이상을 준다(원래 1.2~2.5 tick → 5 tick).
- 시험 수 869건이 그대로이고 `clean build`가 통과한다. 전후 시간을 이 SPEC의 `## 측정`에 기록한다.
- `ci.yml`을 바꾸면 `bash .github/scripts/test-ci-scripts.sh`를 돌린다.

**Ask First:**
- 시험의 heartbeat 간격을 운영값과 다르게 두는 것. 아래 Design Notes 근거로 이번에는 두지 않는다.
- `backend-quality`의 `timeout-minutes` 값 자체를 바꾸는 것. CI-PLAN 새 버전의 보정 몫이므로 주석의 실측만 갱신한다.

**Never:**
- 시험 병렬화(`maxParallelForks`·JUnit 병렬), DB 초기화 전략(`resetStore`의 TRUNCATE) 변경.
- `DevBybitStub` 시나리오의 응답·지연(2초·3.5초·입금 10초 간격) 변경. 개발·CI 스택과 E2E가 같은 스텁을 쓴다. 읽기 전용 접근자 하나만 더한다.
- 시험 삭제·`@Disabled`·단정 완화, lease 길이 변경.

## I/O & Edge-Case Matrix

| 대기 종류 | 위치(develop 기준 행) | 바뀐 뒤 | 근거 |
|---|---|---|---|
| 단계 완료 | `await`·`awaitInitial`·`awaitClassification`·`awaitConversion`·`awaitPublication`, `awaitSync`·`awaitTable`·`publishedPlan` | 20ms poll, 상한(10·15·30초) 그대로 | 조건이 관측된다 |
| 재시도·제공자 호출·재발행 없음 | FS 491·895·981·1621·1689, IS 487·605 | `QUIET_WINDOW`(5 tick) | 부재 증명이라 시간 창 유지 |
| 제한 뒤 차단 | FS 1674 | job이 `QUEUED`·`RUNNING`을 벗어날 때까지 | claim이 `BLOCKED`로 끝낸다 |
| 잠금에서 깨어난 늦은 워커 | FS 2155·2202(DELETION), IS 685 | advisory lock이 모두 풀릴 때까지 + `QUIET_WINDOW` | 늦은 워커의 트랜잭션 종료가 관측된다 |
| 삭제 뒤 늦은 동기화 | IS 792 | 그 시도의 `plan sync finished` 줄 + `QUIET_WINDOW` | 시도의 끝 표지 |
| DB 시계 도달 | FS 2280(`end_at`), 2355(lease 1초) | DB `clock_timestamp()`가 기한을 넘을 때까지 | 조건이 관측된다 |
| 새 입금 | IS 74(`tc1+11초`) | 스텁의 `tc1` 뒤 첫 입금 시각 + 1초 | 기준 시각을 스텁에서 읽는다 |
| 느린 스텁 호출 중의 늦은 워커 | FS 582·627·1175·1197·1659 | 유지 | 스텁 지연 2초에 묶이고 끝이 관측되지 않는다 |
| heartbeat가 잡힌 행을 만남 | FS 2321(6초) | 유지 | heartbeat 5초 유지 |

FS = `FirstSnapshotTest`, IS = `IncrementalSyncTest`.

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java:29,61,78,96,109,116` -- 여섯 dispatch의 `fixedDelay=1000`을 `fixedDelayString="${bokrilog.sync.worker.dispatch-interval}"`로. `:42` heartbeat `fixedDelay=5000`은 그대로
- `backend/src/main/resources/application.yml:185` 부근 -- `bokrilog.sync.worker.dispatch-interval: 1s`와 이유 주석(유일한 기본값 정의)
- `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java:74-83` -- `authProperties`에 dispatch 간격 등록. `WORKER_TICK`(100ms)·`QUIET_WINDOW`(5 tick)·`POLL`(20ms)·`pollUntil(timeout, read, done)`(단정 없음 — 기반 클래스 규칙) 추가
- `backend/src/test/java/com/bokrilog/FirstSnapshotTest.java` -- 위 표의 FS 행. helper는 `1139`(`awaitConversion`), `4612`(`awaitPublication`), `4766`·`4774`·`4782`. 늦은 워커용 `awaitPlanLockReleased()` 신설(`awaitBlockedOnPlanLock` `4739` 옆, 같은 `pg_locks` 관용)
- `backend/src/test/java/com/bokrilog/IncrementalSyncTest.java` -- 위 표의 IS 행. `store`(`999` 부근)가 UID를 돌려준다. helper `825`(`awaitSync`), `947`(`publishedPlan` 루프), `971`(`awaitTable`), `lines`(`925`)
- `backend/src/main/java/com/bokrilog/DevBybitStub.java:663-667` -- `resetScenarioState`·`lastWalletEnvelopeTime` 옆에 `incrementalBase(uid)` 읽기 전용 접근자(없으면 -1). 상수 `INCREMENTAL_SPACING_MILLIS`·`INCREMENTAL_FLOWS`(`522-523`) 재사용
- `backend/src/test/java/com/bokrilog/BybitStubConfiguration.java:120` 부근 -- `nextIncrementalDeposit(uid, after)`: 그 UID의 기준 시각에서 `after` 뒤 첫 입금 시각
- `.github/workflows/ci.yml:266-277` -- `backend-quality` timeout 주석에 이번 실측 추가(값 20분 유지)
- `DEVELOPMENT.md:379` -- 같은 실측 문장. `CLAUDE.md` `## CI` 항목의 백엔드 시간 문장도 맞춘다

## Tasks & Acceptance

**Execution:**
- [x] `FirstSnapshotWorker.java`, `application.yml` -- dispatch 간격 property화, 운영 기본 `1s` -- 시험만 짧게 돌릴 수 있게
- [x] `AuthFlowTestBase.java` -- 등록·상수·`pollUntil` -- 등록점 하나를 유지하고 대기 관용을 한 곳에 둔다
- [x] `DevBybitStub.java`, `BybitStubConfiguration.java` -- 입금 기준 시각 접근자와 다음 입금 계산 -- `tc1+11초` 고정 대기를 대체
- [x] `FirstSnapshotTest.java` -- 표의 FS 행 적용, 단계 helper를 `pollUntil`로 -- 단정 불변
- [x] `IncrementalSyncTest.java` -- 표의 IS 행 적용, 단계 helper를 `pollUntil`로 -- 단정 불변
- [x] 측정 -- 변경 전·후 `clean build` 총시간과 두 클래스 시간을 `## 측정`에 기록, 변경 후 두 클래스 반복 실행으로 안정성 확인
- [x] `ci.yml`, `DEVELOPMENT.md`, `CLAUDE.md` -- 실측 문장 갱신, `test-ci-scripts.sh` 통과 -- 문서와 실측 일치

**Acceptance Criteria:**
- Given 이 변경, when `backend/gradlew --project-dir backend --no-parallel --max-workers=1 clean build`, then 통과하고 기존 869건이 모두 남아 있으며(새 `FirstSnapshotWorkerIntervalTest` 2건을 더해 871건, 실패·건너뜀 0) 총시간이 변경 전보다 뚜렷이 짧다.
- Given 운영 구성(시험 property 없음), when 애플리케이션이 뜨면, then 여섯 dispatch가 1초, heartbeat가 5초 간격이다.
- Given 변경 전후의 두 시험 클래스, when diff를 보면, then `assertThat` 단정의 기대값·대상이 하나도 바뀌지 않았다.
- Given 변경 후, when `FirstSnapshotTest`·`IncrementalSyncTest`를 연속 3회 돌리면, then 매번 통과한다.

## Spec Change Log

## Design Notes

**heartbeat는 시험에서도 5초로 둔다.** 세 fence 시험(FS 614·1167·1649)은 제공자 호출(스텁 지연 2초) 도중 `fencing_generation`을 올리고 늦은 워커가 아무것도 쓰지 못함을 본다. 늦은 워커를 막는 층은 둘이다 — heartbeat가 갱신에 실패해 in-flight lease를 지우는 메모리 guard와, 쓰기 SQL의 `fencing_generation=:g` DB fence다. heartbeat가 5초면 2초 창 안에서 heartbeat가 돌 확률이 약 40%라 대부분의 실행이 DB fence를 지난다. 시험 heartbeat를 100ms~1초로 줄이면 거의 모든 실행이 메모리 guard에서 끝나 DB fence 회귀를 이 세 시험이 더는 잡지 못한다. 단정은 같아도 검증력이 준다. 얻는 것은 FS 2321의 약 4초뿐이라 heartbeat를 설정으로 열지 않는다(설정으로 열면 lease 30초를 넘는 값이 들어갈 경로만 생긴다).

**부재 창은 tick 배수다.** `QUIET_WINDOW = WORKER_TICK × 5`. `fixedDelay`는 앞 실행이 끝난 뒤부터 세므로, 원래 창(1.2~2.5초)은 1초 간격에서 dispatch 기회 1~3번이었고 새 창(500ms)은 100ms 간격에서 4~5번이다.

**간격 0은 거부한다.** Spring은 음수 `fixedDelay`만 거부하고 0은 받아 여섯 claim이 scheduler 스레드 하나(heartbeat도 같은 스레드)에서 쉬지 않고 돈다. relaxed binding으로 환경 변수가 이 값을 덮을 수 있으므로 `FirstSnapshotWorker` 생성자가 0 이하를 기동 실패로 막는다. 시험 컨텍스트가 모두 이 값을 덮으므로 운영 간격(1초·heartbeat 5초)은 Docker 없는 `FirstSnapshotWorkerIntervalTest`가 실제 `application.yml`과 애너테이션으로 고정한다. 그래서 시험 수는 기존 869건 그대로에 새 시험 2건이 더해진 871건이다(리뷰 반영).

**시각에 묶인 픽스처 하나.** IS `aFailureAtAnyStageKeepsTheLastOfficialVersionAndIsNotRetried[CONVERSION]`은 v1 candidate에 끼울 이월 출금을 `tc1 − 500ms`에 뒀다. 1초 tick에서는 `t0`부터 `tc1`까지가 몇 초라 그 시각이 늘 `(t0, tc1]` 안이었지만, 100ms tick에서는 그 구간이 500ms보다 짧아져 `t0` 이하로 떨어지고 미설명 사건(`NEEDS_REVIEW`)이 됐다. 픽스처의 뜻("구간 안의 이월 사건")을 지키려고 시각을 `(t0, tc1]`의 중점으로 옮겼다. 대기가 아니라 설정값이지만 단정은 그대로다.

**늦은 워커의 끝.** 잠금을 기다리던 워커는 풀려난 뒤 publishing 트랜잭션 안에서 plan advisory xact lock을 쥔다. 시험의 holder가 끝난(`join`) 뒤 `pg_locks`에 advisory lock이 하나도 없으면 늦은 워커의 트랜잭션이 끝났다는 뜻이다. 그 뒤의 별도 쓰기(버그라면)는 즉시 일어나므로 `QUIET_WINDOW`로 덮는다.

**claim 경합 확률.** tick이 10배 촘촘해지면 "admission 직후 상태를 바꾸는" 시험에서 claim이 그 사이에 끼어들 확률도 커진다. 해당 시험들은 두 순서를 모두 계약으로 다루고 있어 단정은 그대로 성립하며, 반복 실행으로 확인한다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 clean build` -- expected: 통과, 871건(기존 869 + 새 2), 총시간 기록
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 test --tests com.bokrilog.FirstSnapshotTest --tests com.bokrilog.IncrementalSyncTest --rerun` ×3 -- expected: 매번 통과
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 전부 통과

## 측정

| 실행 | 총시간 | FirstSnapshotTest | IncrementalSyncTest | 나머지 | 시험 수 |
|---|---|---|---|---|---|
| 변경 전(사용자 보고, 로컬) | 12분 56초 | 601초 | 124초 | 44초 | 869 |
| 변경 전(CI, PR #52) | 13분 0초 | — | — | — | 869 |
| 변경 전(이 세션 재측정, 로컬, `3824c3c`) | 13분 2초 | 605.1초 | 124.5초 | 41.3초 | 869 |
| 변경 후 1차(이 세션, 로컬, `clean build`, 리뷰 전) | 3분 3초 | 112.0초 | 27.3초 | 38.9초 | 869 |
| 변경 후 두 클래스 반복 1(`--rerun`) | 2분 27초 | 117.9초 | 27.3초 | — | 134 |
| 변경 후 두 클래스 반복 2(`--rerun`) | 2분 28초 | 118.3초 | 27.3초 | — | 134 |
| 변경 후 두 클래스 반복 3(`--rerun`) | 2분 27초 | 118.2초 | 27.2초 | — | 134 |
| 리뷰 반영 뒤 `clean build`(구현 에이전트) | 3분 13초 | 115.1초 | 27.5초 | 43.7초 | 871 |
| 리뷰 반영 뒤 `clean build`(독립 재실행) | 3분 7초 | 113.8초 | 27.7초 | 39.6초 | 871 |
| 리뷰 반영 뒤 두 클래스 반복 1·2·3(`--rerun`) | 2분 53초·2분 28초·2분 32초 | 140.9·119.1·122.6초 | 27.8·27.7·27.4초 | — | 134 |
| PR 직전 `clean build`(2026-10-03, 최종 트리) | 3분 16초 | 113.3초 | 27.3초 | 44.1초 | 871 |

리뷰 반영 뒤의 871건은 기존 869건이 이름까지 모두 남아 있고(변경 전 결과와 대조해 빠진 시험 0) `FirstSnapshotWorkerIntervalTest` 2건이 더해진 수다. 모든 실행에서 실패·건너뜀은 0이다. `bash .github/scripts/test-ci-scripts.sh`는 147/147 통과. 남은 가장 긴 시험은 IS 첫 시험(11.4초 — 스텁 입금 간격 10초에 묶임)과 FS의 heartbeat 시험(6.7초)이다.

변경 후 첫 두 클래스 실행은 IS `aFailureAtAnyStageKeepsTheLastOfficialVersionAndIsNotRetried[CONVERSION]` 1건이 `FAILED` 대신 `NEEDS_REVIEW`(`SOURCE_REQUIRES_REVIEW|COLLECTION`)로 실패했다. 이 시험은 v1 candidate에 끼워 넣는 이월 출금을 `tc1 − 500ms`에 두는데, 100ms tick에서는 첫 스냅샷부터 v1 발행까지가 500ms 안에 끝나 그 시각이 `t0` 이하로 떨어지고, `(t0, tc]` 밖의 candidate 사건은 이월되지 않아 미설명으로 판정된다. 픽스처 시각을 `(t0, tc1]`의 중점으로 바꿨다(단정 불변). 위 `clean build`와 반복 세 번은 그 수정 뒤의 실행이다.

## Suggested Review Order

**워커 dispatch 간격의 설정화 (진입점)**

- 여섯 dispatch가 읽는 유일한 placeholder. 기본값은 애너테이션이 아니라 yml 한 곳
  [`FirstSnapshotWorker.java:27`](../../backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java#L27)

- Spring이 받아 주는 0을 막는다. 0이면 claim이 scheduler 스레드에서 쉬지 않는다
  [`FirstSnapshotWorker.java:40`](../../backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java#L40)

- heartbeat는 5초 고정. fence 시험이 DB fence까지 가는 창을 지킨다
  [`FirstSnapshotWorker.java:56`](../../backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java#L56)

- 운영 기본 1초와 환경 변수 override 경로의 설명
  [`application.yml:203`](../../backend/src/main/resources/application.yml#L203)

**시험의 대기 관용**

- 기존 유일한 등록점에서 100ms. 새 컨텍스트가 생기지 않는다
  [`AuthFlowTestBase.java:106`](../../backend/src/test/java/com/bokrilog/AuthFlowTestBase.java#L106)

- 부재 증명 창 = 5 tick. 원래 창보다 dispatch 기회가 많다
  [`AuthFlowTestBase.java:91`](../../backend/src/test/java/com/bokrilog/AuthFlowTestBase.java#L91)

- 단정 없는 poll helper. 시간 초과의 의미는 호출한 시험이 정한다
  [`AuthFlowTestBase.java:116`](../../backend/src/test/java/com/bokrilog/AuthFlowTestBase.java#L116)

**고정 sleep → 조건 대기 (위험이 큰 순)**

- 늦은 워커의 트랜잭션 종료를 advisory lock 소멸로 관측. 전역 count는 더 기다릴 뿐
  [`FirstSnapshotTest.java:4761`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L4761)

- holder가 가린 lease_until 대신 DB 시계 +1초를 기한으로
  [`FirstSnapshotTest.java:2361`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L2361)

- 제한 시험: claim이 BLOCKED로 끝낼 때까지, 시간 초과는 조용히 통과하지 않는다
  [`FirstSnapshotTest.java:1675`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L1675)

- 삭제 뒤 늦은 동기화: 종료 줄 → 잠금 해제 → 부재 창
  [`IncrementalSyncTest.java:800`](../../backend/src/test/java/com/bokrilog/IncrementalSyncTest.java#L800)

- `tc1+11초` 대신 스텁의 다음 입금 시각 + 원래와 같은 1초 여유
  [`IncrementalSyncTest.java:75`](../../backend/src/test/java/com/bokrilog/IncrementalSyncTest.java#L75)

- 빨라진 파이프라인이 드러낸 시각 픽스처. 이월 출금을 (t0, tc1] 중점으로
  [`IncrementalSyncTest.java:574`](../../backend/src/test/java/com/bokrilog/IncrementalSyncTest.java#L574)

- 부재 증명의 대표 예: 자동 재시도 없음(1.2초 → 5 tick)
  [`FirstSnapshotTest.java:1619`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L1619)

**스텁 접근자**

- 입금 기준 시각의 읽기 전용 접근자. 응답·지연은 그대로
  [`DevBybitStub.java:671`](../../backend/src/main/java/com/bokrilog/DevBybitStub.java#L671)

- 기준 시각에서 `after` 뒤 첫 입금을 계산(엄격한 초과)
  [`BybitStubConfiguration.java:132`](../../backend/src/test/java/com/bokrilog/BybitStubConfiguration.java#L132)

**운영 주기 고정 시험과 문서**

- 모든 Docker 시험이 값을 덮으므로 운영 1초·5초는 여기서만 고정된다
  [`FirstSnapshotWorkerIntervalTest.java:28`](../../backend/src/test/java/com/bokrilog/sync/internal/FirstSnapshotWorkerIntervalTest.java#L28)

- 0 이하 거부의 단위 시험
  [`FirstSnapshotWorkerIntervalTest.java:52`](../../backend/src/test/java/com/bokrilog/sync/internal/FirstSnapshotWorkerIntervalTest.java#L52)

- timeout 20분 유지와 새 실측
  [`ci.yml:278`](../../.github/workflows/ci.yml#L278)

- 같은 실측의 상세 문장
  [`DEVELOPMENT.md:379`](../../DEVELOPMENT.md#L379)
