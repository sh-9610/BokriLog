---
title: '첫 스냅샷 상태 조회의 원자 읽기 — SUCCEEDED인데 값이 없는 응답(deferred 181)'
type: 'bugfix'
created: '2026-11-10'
status: 'done'
baseline_commit: 'd6c68727c558822b1731f3d04cfdd6231397ee3a'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `GET /api/snapshots/first`가 작업 상태는 `SUCCEEDED`인데 공식값이 없는 응답을 낼 수 있다(deferred 181, develop push run 42134981077의 trace로 확정).
- `SyncJobService.state`는 트랜잭션 없이 문장마다 따로 읽는다. 공식값(`portfolios.officialValue`)을 먼저 읽고 작업(`jobs.latestOf`)을 나중에 읽는다.
- Worker `publish`는 공식값과 작업 `SUCCEEDED`를 한 트랜잭션으로 커밋한다. 그 커밋이 두 읽기 사이에 끼면, 커밋된 적 없는 조합이 응답에 실린다.
- 화면(`useFirstSnapshot`)은 `SUCCEEDED`를 받으면 조회를 멈춘다. 그래서 사용자는 새로 고칠 때까지 값 없는 성공 문구에 머물고 계획 조건 링크도 받지 못한다.
- 다른 계정 재연결 분기(rebase, `accountRebaseAllowed` → `latestOf`)도 같은 모양이다.

**Approach:**
- `state`의 본문 전체를 읽기 전용 `REPEATABLE READ` 트랜잭션 하나로 읽는다. 대시보드 읽기(`PlanPublicationService.dashboardReads`)와 같은 방식이다. 응답은 커밋된 한 시점의 상태만 담는다 — 발행 전(작업 진행 중·값 없음)이거나 발행 뒤(`SUCCEEDED`·값 있음)다.
- 두 읽기 사이에 발행 커밋을 끼우는 결정적 시험으로, 옛 코드에서는 실패하고 새 코드에서는 통과함을 고정한다.

## Boundaries & Constraints

**Always:**
- **트랜잭션.** `SyncJobService`에 읽기 전용·`ISOLATION_REPEATABLE_READ` `TransactionTemplate`을 하나 두고(생성자에서 기존 `manager`로 만든다), `state`의 접근 판정부터 반환까지를 그 안에서 실행한다. 분기·응답 의미(연결 없음·해제·제한·rebase·일반)는 바꾸지 않는다.
- **시험의 결정성.** 시험은 시간·`sleep`·재시도가 아니라 잠금으로 순서를 고정한다.
  - 상태 조회가 공식값을 읽은 뒤 `sync_job` 읽기에서 막힌 것을 `pg_stat_activity`(또는 `pg_locks`)로 확인한다.
  - 그 사이에 공식값과 작업 `SUCCEEDED`를 함께 커밋하고 잠금을 푼다.
  - 응답이 `SUCCEEDED`이면 반드시 값을 담고, 값이 없으면 `SUCCEEDED`가 아님을 단정한다.
  - 같은 시험이 옛 코드(트랜잭션 제거)에서 실패함을 한 번 확인하고 그 결과를 Spec Change Log에 적는다.
  - 기존 helper(`FirstSnapshotTest`의 `holdTableLock`·`awaitBlockedOn`·`holdRowLock`, 또는 `PlanVerificationTest`의 `FinalSweepDoor` 방식)를 쓰고 사본을 만들지 않는다.
- **문서.**
  - `deferred-work-resolutions.md` 181 절과 상태 표·건수를 이 PR에서 해소로 판정한다. done 전환 PR이 없는 fix이기 때문이다.
  - `ci-run-verdicts.md`는 추가 전용이라 고치지 않는다.
  - `CLAUDE.md` 진행 표에 한 줄을 더하고, `## 미룬 결정`의 건수를 맞춘다.

**Ask First:**
- `state` 밖의 상태 조회(`/inputs`·`/cashflow-classification`·`/cashflow-conversion`·`/plan-publication`)를 고쳐야 할 근거가 나올 때. 조사에서는 넷 다 이 모양이 아니었다.
- 프런트엔드 조회 정책을 바꿔야 할 때.

**Never:**
- 프런트엔드 변경. 서버가 비원자 응답을 내지 않으면 화면은 그대로 맞다.
- 쓰기 경로(`publish`)의 트랜잭션·잠금 순서 변경, 새 마이그레이션, 새 의존.
- 행 잠금(`FOR UPDATE`)·advisory 잠금을 상태 조회에 들이는 것. 읽기는 스냅숏 하나로 충분하다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 발행 커밋이 두 읽기 사이 | 조회가 공식값을 읽은 뒤 `sync_job`에서 대기하는 동안 발행이 커밋됨 | 응답은 커밋 전 시점(작업 미완료·값 없음)으로 일관된다. `SUCCEEDED`이면서 값 없음은 없다 | N/A |
| 발행 뒤 조회 | 작업 `SUCCEEDED`·공식값 있음 | `SUCCEEDED`와 값이 함께 온다(기존 시험 유지) | N/A |
| rebase 분기 | 다른 계정으로 재연결, 계획 없음 | `accountChanged` 응답의 의미 불변(기존 `BybitReconnectionTest` 유지) | N/A |
| 접근 거부·해제·제한 | 기존 분기 | 기존 응답과 같다(기존 시험 유지) | 403 경로 불변 |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java:234-268` -- `state`. 전부 `JdbcClient` SELECT이고 잠금·쓰기가 없다. `accounts.decide`는 `@Transactional(readOnly=true)` REQUIRED라 바깥 트랜잭션에 합류한다. `:74` `transactions`(READ COMMITTED) 옆에 읽기 템플릿을 둔다. `:768` 이하 `publish`가 `publishFirst`/`publishNext`와 `finish(SUCCEEDED)`를 한 트랜잭션으로 커밋한다.
- `backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java:119-122` -- `dashboardReads` 설정 선례(`setReadOnly(true)`, `setIsolationLevel(ISOLATION_REPEATABLE_READ)`, `Objects.requireNonNull(dashboardReads.execute(...))`). `AdjustmentHistoryService.java:79-80`도 같다.
- `backend/src/main/java/com/bokrilog/FirstSnapshotController.java:81-98` -- 유일한 운영 호출자. 바꾸지 않는다.
- `backend/src/test/java/com/bokrilog/FirstSnapshotTest.java` -- 새 시험의 자리.
  - `holdTableLock`(`:4546`) + `awaitBlockedOn`(`:4755`) 조합: 대시보드의 같은 경합 시험 `aDeletionOnEitherSideOfTheReadRefusesTheDashboard`(`:2925-2960`)가 선례다.
  - `holdRowLock`(`:4724`)은 잠금을 쥔 트랜잭션 안에서 쓰기를 하고 닫을 때 보이게 한다.
  - `state`에서 `latestOf` 앞의 문장은 `sync_job`을 읽지 않는다. 그래서 `sync_job` 테이블 잠금이면 조회가 공식값을 읽은 뒤 멈춘다.
  - 발행을 Worker 없이 잠금 트랜잭션 안에서 합성하는 방식과, Worker 발행을 trigger 문으로 커밋 직전에 세우는 방식(`PlanVerificationTest` `FinalSweepDoor`, `:1698-1780`) 가운데 구현이 고른다.
- `backend/src/test/java/com/bokrilog/BybitReconnectionTest.java`·`BybitConnectionRevocationTest.java:806`·`RestrictionEnforcementTest.java` -- 분기 회귀를 지키는 기존 시험. 그대로 통과해야 한다.
- `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` 181 절(`### 181.`) -- 해소 판정과 상태 표(해소 37 → 38, 미해소 120 → 119).
- `CLAUDE.md` -- 진행 표 한 줄, `## 미룬 결정` 건수.
- 걸린 열린 회고 액션 없음.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java` -- 읽기 전용 REPEATABLE READ 템플릿을 더하고 `state` 본문을 그 안으로 옮긴다. 이유를 한국어 주석으로 단다(181) -- 원자 읽기
- [x] `backend/src/test/java/com/bokrilog/FirstSnapshotTest.java` -- 발행 커밋을 두 읽기 사이에 끼우는 결정적 시험을 더한다 -- 회귀 고정
- [x] `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` -- 181 해소 판정, 상태 표·건수 -- 원장
- [x] `CLAUDE.md` -- 진행 표·미룬 결정 건수 -- 문서 정합

**Acceptance Criteria:**
- Given 새 시험, when 트랜잭션을 뺀 옛 `state`로 돌리면, then 실패한다(`SUCCEEDED`인데 값 없음). when 새 코드로 돌리면, then 통과한다.
- Given 백엔드 전체 시험, when `backend/gradlew --project-dir backend build`를 돌리면, then 통과한다(`-Werror`, `ModuleBoundaryTest` 포함).

## Spec Change Log

- 2026-11-10 구현: 시험은 Worker 없는 발행 합성을 택했다 — 실제 발행 뒤 포인터 행을 지우고 작업을 `RUNNING`(lease 1시간 앞)으로 되돌린 뒤, `holdRowLock`의 트랜잭션이 `LOCK TABLE sync_job IN ACCESS EXCLUSIVE MODE` → 포인터 복원 → 작업 `SUCCEEDED`를 쓰고, 조회가 `latestOf`(`FROM sync_job WHERE user_id=$1 AND connection_id=$2`)에서 막힌 것을 `awaitBlockedOn`으로 확인한 뒤 커밋한다. Worker trigger 방식은 heartbeat가 잡은 작업 행과 표 잠금 승격이 교착할 창이 있어 택하지 않았다. 옛 코드 확인: `state`를 `return stateAt(userId);`(트랜잭션 없음)로 바꿔 돌리자 `[SUCCEEDED는 반드시 값을 담는다] Expecting actual not to be null`로 실패했고(1 failed), 되돌린 새 코드에서는 통과했다(1 passed).
- 2026-11-10 검증: 전체 백엔드 build는 이 호스트의 루프백 bind 오류(`java.net.SocketException: Bad address: listen`)로 로컬 green을 받지 못해 PR CI(`ci-required`)로 확인한다.
- 2026-11-10 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap). verification-gap 0건.
  - **patch 7건(반영):**
    - `state`가 스냅숏을 닫은 뒤 계정 접근을 다시 본다(대시보드 앞뒤 판정 계약).
    - 읽기 전용 트랜잭션 제약을 주석에 적었다(callee의 쓰기·잠금 SELECT 금지).
    - 시험의 잠금 helper에서 `FOR UPDATE`를 빼, 행 잠금을 쥔 채 표 잠금을 올리는 교착 창을 없앴다.
    - `awaitBlockedOn` 문자열이 `latestOf` SQL 원문에 묶여 있음을 주석으로 적었다.
    - 시험 import와 local record를 정리했다.
    - `CLAUDE.md`의 대시보드 읽기 계약에 첫 스냅샷 상태 조회를 더했다.
    - 원장 181 절에 다른 상태 조회 넷의 근거를 적었다.
  - 옛 코드 재확인: 새 잠금 구조에서도 `[SUCCEEDED는 반드시 값을 담는다]`로 실패한다. 대상 네 클래스 181건 통과(PowerShell, 3분 43초).
  - **defer 0건.**
  - **reject:** I/O 표의 403 정정(컨트롤러가 실제로 403을 낸다), rebase·해제 분기의 별도 경합 시험(같은 트랜잭션이 구조적으로 덮는다), 바깥 트랜잭션 합류 가드(운영 호출자는 트랜잭션 없는 컨트롤러 하나), 조건부 단정 정리, 실패 경로의 비동기 조회 정리(잠금이 `finally`에서 풀려 조회가 끝난다).
- 2026-11-10 전체 build 재시도(PowerShell): 1773건 중 215건 실패, 59분. 실패는 모두 환경 원인이다 — Testcontainers DB 연결 실패 `Could not open JDBC Connection` 114건, 시험 DB 시계 읽기 실패 94건, 그 여파의 컨텍스트 실패 임계 6건, 루프백 bind 1건. 대상 시험 네 클래스는 같은 코드로 따로 통과했다. 전체 green은 PR CI(`ci-required`)가 확인한다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend test --tests "com.bokrilog.FirstSnapshotTest" --tests "com.bokrilog.BybitReconnectionTest" --tests "com.bokrilog.BybitConnectionRevocationTest" --tests "com.bokrilog.RestrictionEnforcementTest"` -- expected: 통과
- `backend/gradlew --project-dir backend build` -- expected: 통과(Docker 필요)

## Suggested Review Order

**원자 읽기**

- 상태 조회를 읽기 전용 REPEATABLE READ 하나로 읽고, 닫은 뒤 접근을 다시 본다
  [`SyncJobService.java:247`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L247)

- 분기 본문은 그대로 옮겼다 — 해제·제한·rebase·일반
  [`SyncJobService.java:258`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L258)

**결정적 재현**

- 발행 커밋을 두 읽기 사이에 끼우는 잠금 시험
  [`FirstSnapshotTest.java:73`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L73)

**원장·문서**

- 181 해소 판정과 다른 상태 조회 넷의 근거
  [`deferred-work-resolutions.md:1797`](deferred-work-resolutions.md#L1797)

- 진행 표와 대시보드 읽기 계약 한 줄
  [`CLAUDE.md:235`](../../CLAUDE.md#L235)
