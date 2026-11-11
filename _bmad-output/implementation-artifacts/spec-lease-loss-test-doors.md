---
title: '시간 기반 lease 상실·지연 시험의 잠금 문 이전'
type: 'bugfix'
created: '2026-11-11'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'ab6d798f0bcd4fe119938aca551be55cfc860b6e'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-plan-verification-final-sweep-race.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 계획 동기화 백엔드 시험 여럿이 "수집 중 lease 상실"이나 "수집 중 명령"을 시간으로 만든다. `pg_sleep` 페이지 저장 trigger로 늦추고 진행을 기다린 뒤 다른 세션에서 `expireLease`를 걸거나 HTTP 명령을 보낸다. 그래서 holder가 먼저 그 단계를 벗어나면 간헐 실패한다(deferred 177, 같은 구조의 CI 실패 run 42197758258). `PlanEndTest`가 이 구조를 쓰고 있어 159의 원인 후보로 남아 있다.

**Approach:** PR #146의 `FinalSweepDoor`를 `PlanSyncTestBase`의 공용 잠금 문으로 일반화하고, 대상 시험을 모두 옮겨 시험 소스에서 `pg_sleep`을 없앤다. 옮기는 방식은 두 가지다.
- **만료형:** 만료는 holder 자신의 페이지 저장 트랜잭션 안에서 trigger가 건다.
- **지연형:** 페이지 저장이 문(advisory 공유 잠금)에서 기다리는 동안 명령을 끝내거나 명령이 막힌 것을 확인한 뒤 문을 연다.

## Boundaries & Constraints

**Always:**
- 운영 코드(`backend/src/main/**`, `DevBybitStub` 포함)는 바꾸지 않는다.
- 각 시험의 단정 의미(결말·`resume_count`·버전·포인터·차단 code)를 유지한다.
- 순서는 시간이 아니라 `pg_locks`·작업 행 관측으로 고정한다.
- 문 키는 두 정수 advisory 키(`objsubid=2`)다. 운영 키(bigint, `objsubid=1`)와 겹치지 않는다. 문마다 `objid`를 나눈다.
- 문 holder에는 안전 시한이 있고, trigger·함수는 실패해도 지운다. 지금 `FinalSweepDoor`의 보장(획득 `lock_timeout`, 한 번 열기, `addSuppressed`)을 유지한다.
- 만료 없이 막는 문은 lease 30초 안에 연다. 막혀 있는 동안 heartbeat가 `SKIP LOCKED`로 건너뛰기 때문이다.
- `PlanSyncTestBase`의 advisory 계수 helper(`awaitBlockedOnPlanLock`·`awaitPlanLockReleased`)는 두 정수 키(`objsubid=2`)를 세지 않는다.
- 새 주석은 한국어로 쓴다.

**Ask First:**
- 어떤 대상 시험이 운영 코드 변경 없이는 결정적으로 옮겨지지 않을 때.
- 단정을 바꾸거나 시험을 삭제해야 할 때.

**Never:**
- 재시도·대기 시간·timeout을 늘려 덮지 않는다.
- 범위 밖 시험(`IncrementalSyncTest`·`FirstSnapshotTest`·`StrongSignalAutoStopTest`·`AccountErasureRunTest`)을 바꾸지 않는다.
- 자식 수집·첫 스냅샷 작업의 lease를 trigger에서 만료시키지 않는다. 같은 트랜잭션의 자식 lease 재확인이 롤백시킨다.
- 159를 근거 없이 해소로 적지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 만료형 | 첫 holder(`resume_count=0`)의 지정 페이지 INSERT | 같은 트랜잭션이 `sync_plan_sync_job` lease를 과거로 두고 커밋하고, 다음 fenced 문이 `LeaseLost` → 재획득 | 지정 페이지에 닿지 못하면 결말 단정이 상태와 함께 실패 |
| 세 번째 중단 | holder마다 첫 페이지에서 만료 | `SYNC_INTERRUPTED`, `resume_count=2`. 페이지 수를 기다리지 않음 | 같음 |
| 지연형(종료) | 문에서 페이지 대기 확인 → 동기 종료 → 문 열기 | 늦은 sync가 `BLOCKED`/`PLAN_CHANGED` | 문 앞 대기를 못 보면 마감 뒤 상태와 함께 실패 |
| 지연형(해제·재연결) | 문에서 페이지 대기 확인 → 명령 비동기 전송 → 명령 backend의 행 잠금 대기 확인 → 문 열기 | 명령이 먼저 커밋하고 늦은 작업은 `NOT_PERMITTED` | 명령이 막히지 않고 끝나도 단정은 같음. 대기를 못 보면 실패 |

</frozen-after-approval>

## Code Map

- `backend/src/test/java/com/bokrilog/PlanVerificationTest.java`
  - 원형: `FinalSweepDoor`·`shutFinalSweepDoor`·`awaitBlockedAtFinalSweepDoor`(:1663-1810), 키 상수(:46-47 `20_261_106`/`1`).
  - 옮길 시험:
    - 만료형: `:582` `aReclaimedContinuationGoesOnFromItsCompleteWindows`(CATCH_UP, `stage='COLLECTION'`·`initial_tc IS NULL`, 세 번째 페이지), `:1251`(VERIFY, 두 번째 페이지), helper `verifiedAcrossALostWindow` `:1314`(R1/R2 `:1330`·`:1345`·`:1362`, 첫 페이지 `TRANSFER_IN`).
    - 세 번째 중단: `:613` `theThirdInterruptionEndsTheAttemptAsInterrupted`(`resume_count<=2`면 매 holder 첫 페이지. 부분 창 증거를 지우는 `discardPartialWindows` 때문에 지금의 누적 페이지 대기가 흔들린다).
  - 정리할 helper: `slowPageStores`/`dropSlowPageStores` `:1627/:1635`, `expireLease` `:1806`, `awaitPages` `:1641`.
- `BybitConnectionRevocationTest.java` -- `:453`·`:741` 지연형(해제가 `ConnectionRevocationRepository:45,48`에서 작업 행을 UPDATE해 문에 막힌 페이지 트랜잭션과 행 잠금이 겹친다). `slowPageStores` `:1135/:1143`, `awaitStage` `:1127`.
- `BybitReconnectionTest.java` -- `:577` 지연형(해제+재연결). `slowPageStores` `:1096/:1104`, `awaitStage` `:1088`.
- `PlanEndTest.java` -- `:744` 지연형(종료는 작업 행을 건드리지 않아 문을 닫은 채 동기 호출할 수 있다). `slowPageStores` `:1205/:1212`, `awaitStage` `:1197`.
- `PlanSyncTestBase.java` -- 네 시험의 공통 base(`:36`). `manager`가 있고 공용 문을 둘 자리다. 계수 helper는 `:300-330`.
- 운영 근거(읽기 전용):
  - 페이지 트랜잭션은 `PlanSyncService:1113-1120`이고, 계획 작업 `lockOwned`(`PlanSyncRepository:306`, `FOR UPDATE`+fence) 뒤에 INSERT(`InitialCollectionRepository:162`)한다.
  - INSERT 뒤에는 자식 lease만 다시 본다(`:173-176`).
  - `collection_job_id`·`stage`는 `attachCollection`(`PlanSyncRepository:383`)이 세운다.
- `DEVELOPMENT.md:115` -- lease 경합 시험 규칙 문장. 공용 문과 두 형태로 갱신한다.
- `deferred-work-resolutions.md` 177·159 절과 상태 표, `CLAUDE.md` 진행 표·미룬 결정 문단.
- 걸린 열린 회고 액션은 둘이고, 이 작업은 둘 다 지나간다(건드리지 않음).
  - `epic-5-retro-item-54`: 삭제 시험 공용 base. 대상이 다르다.
  - `epic-5-retro-item-55`: Windows 로컬 build 한계의 `DEVELOPMENT.md` 서술. 과정 항목이다.

## Tasks & Acceptance

**Execution:**
- [x] `PlanSyncTestBase.java` -- 공용 문 helper를 둔다.
  - 무엇: `FinalSweepDoor`를 일반화한다. 문 holder 가상 스레드, 사용자·trigger 술어·만료 여부·`objid`를 받는 trigger 설치, 문 앞 미승인 대기 관측, 행 잠금 대기 관측 helper. 계수 helper 둘은 `objsubid=2`를 뺀다.
  - 이유: 네 시험의 사본 넷과 문 하나를 한 구현으로 모은다.
- [x] `PlanVerificationTest.java` -- 마지막 점검 시험을 공용 문으로 옮기고, 위 다섯 지점을 만료형으로 바꾼다. `slowPageStores`·`expireLease`·`awaitPages`와 내부 문 클래스는 지운다. 이유: 시간 순서를 제거한다.
- [x] `BybitConnectionRevocationTest.java`·`BybitReconnectionTest.java`·`PlanEndTest.java` -- 지연형 문으로 옮기고 `slowPageStores` 사본을 지운다. 이유: 같다.
- [x] `PlanSyncTestBase` 또는 기존 스캔 시험 -- `src/test/java`에 `pg_sleep`이 다시 생기면 실패하는 소스 스캔을 둔다. 이유: 회귀 방지.
- [x] `DEVELOPMENT.md`·`deferred-work-resolutions.md`·`CLAUDE.md` -- 규칙 문장, 177 해소, 159 재판정(아래 Design Notes), 진행 표 한 줄, 미룬 결정 건수를 맞춘다.

**Acceptance Criteria:**
- Given 대상 시험 클래스 넷, when 각각 `--tests` 단독으로 세 번 돌리면, then 모두 통과한다.
- Given 시험 소스, when `pg_sleep`을 검색하면, then 0건이고 스캔 시험이 이를 고정한다.
- Given 문을 쓰는 시험이 실패하면, when 정리가 돌면, then 문 trigger·함수와 holder 연결이 남지 않는다.

## Spec Change Log

- 2026-11-11 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) patch 루프. bad_spec·intent_gap은 없었다. 반영한 patch는 다음과 같다.
  - `runBehindDoor`: 명령 backend를 `pg_blocking_pids` 재귀 대기열 수의 baseline 초과로 식별한다. transactionid·tuple 대기를 모두 잡고 남의 대기는 통과시키지 않는다. 통과 갈래(`QUEUED_BEHIND_PAGE`·`FINISHED_FIRST`)를 기록한다.
  - `runBehindDoor` 실패 경로: 문을 열고 명령을 제한 시간 기다린 뒤 다시 던진다(남은 명령 스레드가 시험 밖에서 커밋하지 않게).
  - `PlanEndTest`: 종료도 `runBehindDoor`로 묶고 `FINISHED_FIRST`를 단정한다.
  - 막힘 기준 시각: 관측 시각이 아니라 `pg_locks.waitstart`다. `DEVELOPMENT.md`는 "lease의 절반"으로 적는다.
  - lease 상한: 주입할 수 있게 하고 자가 시험 `aDelayingDoorLetsGoAndFailsPastItsLeaseBound`를 더했다.
  - 정리 시험: 만료형 정리와 suppressed가 비었음을 함께 본다. 자가 시험은 전용 키를 쓴다.
  - 생성자 시간 초과 갈래가 holder를 join한다. `awaitBlockedAtDoor`는 작업 행이 없으면 `GONE`을 남긴다.
  - `verifiedAcrossALostWindow`가 결말을 단정한다.
  - `PageDoorSourceTest`: 대소문자를 가리지 않고 `src/test` 아래 resources까지 훑고, `PageDoorKey` objid가 서로 다름을 단정한다. 문서 서술은 실제로 막는 것으로 좁혔다.
  - 문장 정합: `awaitPlanLockReleased` javadoc, 159 문장의 모순("하나로 좁혔다"), 177의 앞으로의 CI 실패 기록 규칙(`미조사`·후속 159)을 고쳤다.
  - defer 184: 운영 코드 두 정수 advisory 키 소스 스캔.
  - reject: 진행 표·SPEC 상태의 이른 `done` 표기(present·PR 흐름이 채운다)와 만료형 trigger 발동 횟수 기록(`resume_count` 단정이 lease 상실을 이미 고정하고, 시험 소스에 시간 지연이 없어 다른 상실 경로가 없다).
  - KEEP: 운영 코드 무변경, `PlanSyncTestBase`의 공용 `PageDoor` 하나, 문마다 `PageDoorKey` objid, 만료는 holder 자신의 페이지 트랜잭션 안에서 계획 작업 lease만 건드린다.

## Design Notes

159 재판정은 이렇게 한다.
- 잠금 문 이전은 177의 시험 구조만 고친다.
- 조사 결과 `slowPageStores` 시험은 뒤 시험에 `RESULT_TOO_RECENT`를 남길 경로가 없다. trigger는 그 사용자 한정이고 `finally`로 지워진다. 그 시험이 경합에 지면 `REFERENCE_STALE`이 먼저 나온다(`PlanEndRules:57-63`).
- `RESULT_TOO_RECENT`는 DB `now` < 스텁 JVM 시각에서 온 `measuredAt`이다. 그래서 16건이 함께 실패한 모양은 시계 차이 가설과 맞는다.
- 159는 부분 해소로 두고, `RESULT_TOO_RECENT` 16건의 후보는 시계 차이로 좁혀 적는다. `PlanCompletionTest`의 작업 `FAILED` 한 번은 이 판정으로 설명되지 않은 채 남는다고 함께 적는다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend test --tests 'com.bokrilog.PlanVerificationTest' --tests 'com.bokrilog.PlanEndTest' --tests 'com.bokrilog.BybitConnectionRevocationTest' --tests 'com.bokrilog.BybitReconnectionTest' --tests 'com.bokrilog.PageDoorSourceTest' --rerun` -- expected: 3회 모두 통과
- `backend/gradlew --project-dir backend compileTestJava` -- expected: `-Werror` 통과
- `grep -rn pg_sleep backend/src/test/java` -- expected: 0건

## Suggested Review Order

**공용 페이지 저장 문**

- 문 하나가 만료형·지연형 두 갈래를 trigger 설치 한 곳에서 낸다.
  [`PlanSyncTestBase.java:630`](../../backend/src/test/java/com/bokrilog/PlanSyncTestBase.java#L630)

- 만료형 진입점 — holder 없이 페이지 트랜잭션 안에서 계획 작업 lease만 만료.
  [`PlanSyncTestBase.java:426`](../../backend/src/test/java/com/bokrilog/PlanSyncTestBase.java#L426)

- 지연형 진입점 — 문 키를 쥔 holder, lease 상한은 주입 가능.
  [`PlanSyncTestBase.java:406`](../../backend/src/test/java/com/bokrilog/PlanSyncTestBase.java#L406)

- 명령을 대기열 수의 baseline 초과로 식별하고 실패 시 명령을 거둔다.
  [`PlanSyncTestBase.java:494`](../../backend/src/test/java/com/bokrilog/PlanSyncTestBase.java#L494)

- 막힘 시각은 `pg_locks.waitstart`에서 — lease 절반 상한의 기준점.
  [`PlanSyncTestBase.java:439`](../../backend/src/test/java/com/bokrilog/PlanSyncTestBase.java#L439)

- holder의 안전 시한과 lease 절반 상한.
  [`PlanSyncTestBase.java:611`](../../backend/src/test/java/com/bokrilog/PlanSyncTestBase.java#L611)

- 문마다 objid 하나 — trigger·함수 이름에도 붙는다.
  [`PlanSyncTestBase.java:374`](../../backend/src/test/java/com/bokrilog/PlanSyncTestBase.java#L374)

- 계획 잠금 대기 helper가 문 키(`objsubid=2`)를 세지 않는다.
  [`PlanSyncTestBase.java:312`](../../backend/src/test/java/com/bokrilog/PlanSyncTestBase.java#L312)

**옮긴 시험**

- 이어가기: 첫 holder의 세 번째 페이지에서 만료.
  [`PlanVerificationTest.java:577`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L577)

- 세 번째 중단: holder마다 첫 페이지에서 만료, 페이지 수 대기 없음.
  [`PlanVerificationTest.java:608`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L608)

- 마지막 점검 시험이 공용 문으로 옮겨졌다.
  [`PlanVerificationTest.java:1268`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1268)

- 정밀 검증 재획득: 두 번째 페이지에서 만료.
  [`PlanVerificationTest.java:1312`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1312)

- R1/R2: 첫 TRANSFER_IN 페이지에서 만료하고 결말을 단정한다.
  [`PlanVerificationTest.java:1370`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1370)

- 해제: 명령이 페이지 트랜잭션 뒤에 줄 선 뒤 문을 연다.
  [`BybitConnectionRevocationTest.java:464`](../../backend/src/test/java/com/bokrilog/BybitConnectionRevocationTest.java#L464)

- 정밀 검증 중 해제.
  [`BybitConnectionRevocationTest.java:755`](../../backend/src/test/java/com/bokrilog/BybitConnectionRevocationTest.java#L755)

- 해제 뒤 재연결.
  [`BybitReconnectionTest.java:589`](../../backend/src/test/java/com/bokrilog/BybitReconnectionTest.java#L589)

- 종료는 작업 행을 건드리지 않아 `FINISHED_FIRST`를 단정한다.
  [`PlanEndTest.java:754`](../../backend/src/test/java/com/bokrilog/PlanEndTest.java#L754)

**회귀 방지와 자가 시험**

- `src/test` 전체에서 `pg_sleep`을 막고 objid 중복을 막는다.
  [`PageDoorSourceTest.java:33`](../../backend/src/test/java/com/bokrilog/PageDoorSourceTest.java#L33)

- 실패한 시험 뒤 두 종류 문 모두 흔적이 남지 않는다.
  [`PlanVerificationTest.java:1188`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1188)

- lease 상한을 넘긴 지연형 문이 스스로 풀리고 시험을 실패시킨다.
  [`PlanVerificationTest.java:1223`](../../backend/src/test/java/com/bokrilog/PlanVerificationTest.java#L1223)

**문서·원장**

- lease 경합 시험 규칙 문장.
  [`DEVELOPMENT.md:115`](../../DEVELOPMENT.md#L115)

- 177 해소 판정과 앞으로의 CI 실패 기록 규칙.
  [`deferred-work-resolutions.md:1788`](deferred-work-resolutions.md#L1788)

- 159 재판정 — 부분 해소 그대로.
  [`deferred-work-resolutions.md:1542`](deferred-work-resolutions.md#L1542)

- 새 defer 184.
  [`deferred-work-resolutions.md:1891`](deferred-work-resolutions.md#L1891)
