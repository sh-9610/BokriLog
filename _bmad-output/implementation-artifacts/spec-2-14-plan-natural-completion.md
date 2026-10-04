---
title: '자연 만료 계획의 첫 종료 후 관측값과 완료 결과 확정'
type: 'feature'
created: '2026-10-04'
status: 'done'
baseline_commit: '3a5a0cc58c22d21164aba6644375eadf25b41a84'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-13-plan-early-end.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-12-precision-reverification.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.14 범위, ERD-1.0.0 §3 CALC-1.0(CV-11)·§4 SYNC-UX-1.0, PRD §4 u(t)=min(t,T), AD-6·AD-8·AD-16·AD-23, UX Flow 4·UX-DR14">

## Intent

**Problem:**
- 활성 계획이 예정 종료 시각 `T`에 닿아도 상태를 바꾸는 주체가 없다. 계획은 영원히 `ACTIVE`로 남고 동기화·검증·종료가 모두 `PLAN_PERIOD_ENDED`로 막힌다. 대시보드는 활성 계획을 `최신 아님`으로 영구 표시하고 후속 계획도 열 수 없다.
- 계산 모듈과 V10 CHECK가 기준시각 `basis_at < T`를 강제하므로 `T` 이후 관측으로 완료 결과를 저장할 자리가 없다.

**Approach:**
- `serverNow >= T`인 활성 계획은 사용자 시점(대시보드 진입 자동 트리거·수동 동기화)의 동기화 admission이 **같은 트랜잭션에서** `COMPLETED`로 전환하고 완료 기록(비교 `PENDING`)과 완료 작업 하나를 접수한다. 백그라운드 전환·수집은 없다.
- 완료 작업(`purpose=COMPLETION`)은 `T` 이후 첫 정상 wallet 관측을 **한 번** 고정하고, 2.12 정밀 검증 계약으로 `(t0, 관측 상한]`의 공백·미확정 구간만 다시 읽은 뒤, 비교 가능 여부를 판정해 완료 계산 버전·완료 기록·공식 포인터를 원자 발행한다.
- 완료 뒤의 동기화는 wallet만 읽어 portfolio snapshot을 전진시키는 `POST_END_VALUE` 작업이며, 대시보드의 새 상태 `COMPLETED`가 완료 결과와 `종료 후 현재가치`를 따로 보인다.

## Boundaries & Constraints

**Always:**
- **이름.**
  - 계획 상태 `ACTIVE → COMPLETED`(단조, 되돌림 없음). `ENDED_EARLY`와 `COMPLETED`가 종단 상태 둘이다.
  - 완료 비교 `comparison`은 넷으로 닫힌다: `PENDING`(확인 중), `CONFIRMED`(확정), `UNAVAILABLE`(계산 불가), `HELD`(확정 보류).
  - `UNAVAILABLE` 이유: `OBSERVATION_DELAY_EXCEEDED`(지연 > 24시간), `POST_END_CASHFLOW`(`T < ti <= measured_at` 확인된 외부 흐름), `HISTORY_OUT_OF_RANGE`(2년 조회 한계), `DATA_GAP`(전환 시 장기 미동기화).
  - `HELD` 이유: `INPUT_REVIEW_REQUIRED`(미인식·충돌·중복 의심·가격 결측·사라진 사건 등 2.12 검토 사유).
  - 작업 목적 둘을 더한다: `COMPLETION`, `POST_END_VALUE`. 운영 제한 명령은 기존 `plan-sync`를 그대로 쓴다.
- **전환**(AC-1·AC-6).
  - 동기화 admission(`POST /api/dashboard/sync`)의 순서에서 계정 쓰기 접근 → key 재전송 → **활성 계획 조회 직후** DB `statement_timestamp() >= T`를 본다. 참이면 계획 advisory → 생명주기 행 → 계획 행 `FOR UPDATE`(2.13과 같은 잠금 순서) 아래에서 `UPDATE ... SET status='COMPLETED' WHERE status='ACTIVE'`(정확히 1행)와 완료 기록 INSERT(`completed_at = T`, `comparison = PENDING`), 완료 작업 admission을 한 트랜잭션에서 커밋한다.
  - 진행 중인 SYNC·VERIFY·CATCH_UP 작업이 있으면 전환하지 않고 기존처럼 합친다. 그 작업은 기존 `PlanSyncGuard`로 `PLAN_PERIOD_ENDED`에 끝나며 다음 요청이 전환한다.
  - 전환 때 장기 미동기화(DB 시각 `now − cashflow_verified_through > 30일`, 2.11 admission과 같은 판정 함수)면 이력 수집 없이 비교 `UNAVAILABLE`/`DATA_GAP`으로 기록하고, 완료 작업은 관측 고정(wallet 1회)만 한다.
  - 제한·연결·membership 사용 불가면 전환만 하고 작업을 만들지 않는다(비교 `PENDING`, 다음 행동은 사유 설명). 전환은 계정 쓰기 접근이 있을 때만 한다.
  - 2.13 중도 종료(`now < T`)와 이 전환(`now >= T`)은 같은 잠금 안의 재판정으로 갈리고 `WHERE status='ACTIVE'`가 종단 상태 하나만 남긴다.
  - `GET /api/dashboard`는 쓰지 않는다. `ACTIVE`이고 DB 시각 `>= T`면 전환 전이라도 `COMPLETED` 모양(비교 `PENDING`, 관측 없음, `nextAction: SYNC_FOR_OBSERVATION`)으로 보이며 새 수익이 누적되는 활성 상태로 보이지 않는다.
- **완료 작업**(AC-2·AC-3).
  - Bybit `tc`를 2.11과 같이 고정·신뢰 판정한다. `tc < T`면 외부 호출 없이 재시도 가능 실패로 끝난다.
  - 관측: 아직 고정된 관측이 없으면 wallet을 읽어 `measured_at >= T`인 정상값만 받는다. 그 값을 portfolio snapshot으로 발행하고(기존 `publishNext`) 완료 기록에 snapshot ID·값·`measured_at`·지연(`measured_at − T`, ms)을 **한 번만** 쓴다. 이미 고정됐으면 다시 읽지 않는다(재시도·`HELD` 재시도 포함). 이것이 가장 이른 사용자 소유 `T` 이후 정상 snapshot이다 — 그 앞에는 `T` 이후 snapshot이 만들어질 경로가 없다.
  - 검증: 2.12 `VerificationPlanner`로 `(t0, U]`의 기존 증명 구간을 재사용 구간으로 합치고 마지막 미확정 `[verified−7일, verified]`·공백·설명되지 않는 저장 사건 ±24시간·새 구간 `(verified, U]`만 7일 창으로 읽는다. 관측 상한 `U`는 고정 관측의 `measured_at` 이상이어야 하며(작업이 관측 뒤 다시 고정한 `tc`), 그래야 `T < ti <= measured_at` 판정이 완전하다.
  - 판정·환산은 2.11·2.12와 같다. 검증 결과의 매핑: 검토 사유 → `HELD`/`INPUT_REVIEW_REQUIRED`, 2년 초과 → `UNAVAILABLE`/`HISTORY_OUT_OF_RANGE`, 시간 초과·429·5xx·시간 불신 등 일시 실패 → `PENDING` 유지(작업 실패 원인을 대시보드가 보인다).
  - 계산 불가·보류가 계획 상태를 되돌리지 않는다. 어느 결과든 `COMPLETED`는 유지되고 마지막 정상 공식 결과는 보존된다.
- **비교 판정과 완료 계산**(AC-4·AC-5).
  - 순서: 입력 완전성(아니면 `HELD`/`UNAVAILABLE`) → 지연 `> 24시간`(정확히 24시간은 비교 가능) → `T < ti <= measured_at` 흐름 존재 → 모두 통과하면 `CONFIRMED`.
  - 입력이 완전하면(`CONFIRMED` 또는 앞의 두 `UNAVAILABLE`) 완료 계산 버전을 발행한다. 입력이 불완전하거나 `DATA_GAP`·`HISTORY_OUT_OF_RANGE`면 완료 계산은 없고 포인터는 마지막 진행 버전에 머문다.
  - 완료 계산은 PRD §4를 `u = T`로 적용한다: 흐름은 `t0 < ti <= T`만(`ti = t0` 제외, `ti = T` 포함, `ti > T` 제외, 같은 ms는 CALC-1.0 안정 순서로 한 번씩), `E` = 고정 관측값. 지표 여섯: `CURRENT_VALUE`=관측값, `PLAN_AMOUNT`=`ADJUSTED_TARGET`=Target(T), `PLAN_DIFFERENCE`=E−Target, `REQUIRED_RATE`=계산 불가(`REMAINING_PERIOD_NOT_POSITIVE`), `GOAL_PROGRESS`=PRD 공식. 비교가 `UNAVAILABLE`이면 `PLAN_DIFFERENCE`·`GOAL_PROGRESS`는 값 없이 `COMPLETION_NOT_COMPARABLE`이다. 정책은 `CALC-1.0`·`CALENDAR-SEOUL-1.0` 그대로다(식이 같다).
  - 계산 버전의 기준시각은 `basis_at = T`, `cashflow_verified_through = U`, `current_value_measured_at = measured_at`이고 kind는 `COMPLETION`이다. 진행 버전의 기존 CHECK는 kind `PROGRESS`에 그대로 남는다.
- **원자 발행**(AC-6).
  - 2.11 발행과 같은 outer 트랜잭션·잠금 순서(계획 advisory → 생명주기 → 작업 행 lease·fencing)에서 완료 계산 버전·지표·흐름·변경 행, plan 포인터 version CAS와 이력, 완료 기록의 비교 상태·이유·완료 계산 ID를 함께 커밋한다. 포인터가 움직였거나 lease를 잃었거나 삭제·제한 중이면 전체 롤백이다.
  - 완료 기록이 이미 `PENDING`이 아니면(`CONFIRMED`·`UNAVAILABLE`) 다시 쓰지 않는다. `HELD → CONFIRMED/UNAVAILABLE`만 허용하고 그 외 비교 상태는 종단이다.
  - 계획 입력, 과거 원천 사실·판정·환산·계산 버전은 수정하지 않는다.
- **완료 뒤 동기화**(AC-7).
  - `COMPLETED` 계획의 동기화 admission: 비교가 `PENDING`(관측·검증 미완)·`HELD`면 `COMPLETION` 작업, 그 밖이면 `POST_END_VALUE` 작업이다. 간격·진행 중 합치기·사용 불가·cooldown 규칙은 2.11 그대로다.
  - `POST_END_VALUE`는 `tc`·wallet만 읽어 portfolio snapshot을 발행한다(이력 수집·계산·plan 포인터 없음). 완료 기록·완료 계산은 바뀌지 않는다.
- **대시보드**(AC-8).
  - "표시할 계획"(2.13의 도우미)에 `COMPLETED`를 더한다. 새 200 상태 `COMPLETED`:
    - `result`: 현재 plan 포인터의 계산(완료 버전 또는 마지막 진행 버전), `planStatus: "COMPLETED"`.
    - 신선도 축: `state:"COMPLETED"`, `cause:"PLAN_COMPLETED"`, 다음 행동 `NONE`. 입력 신뢰도·계산 성격은 그 계산 버전으로 판정한다.
    - `completion`: `scheduledEndAt`(T), `comparison`, `reason`, `observedValue`, `observedMeasuredAt`, `observationDelayMillis`, `completionTarget`(완료 버전이 있을 때만), `postEndCashflowCount`.
    - `postEnd`: portfolio 현재 공식값이 관측 snapshot보다 늦으면 `{value, measuredAt}`, 아니면 null.
    - `sync`: 2.11 블록(완료 뒤 동기화용). `nextAction`: 관측 전 `SYNC_FOR_OBSERVATION`, `HELD`면 `SYNC_FOR_OBSERVATION`, 그 밖 `PREPARE_NEXT_PLAN`.
  - 일관성: 완료 기록·포인터·계산·관측 snapshot이 서로 맞지 않으면 503 `DASHBOARD_UNAVAILABLE`. portfolio 현재 포인터와 결과 계산의 AD-23 대조는 하지 않는다.
  - 근거(2.9)·조정 이력(2.10)은 완료 버전도 읽는다. 완료 버전의 흐름 범위는 `(t0, T]`이다.
  - 종단 계획(중도 종료·완료)이 있으면 계획 조건 경로는 기존 `PLAN_ENDED`로 막는다(후속 계획은 2.15).
- **화면.** 새 경로 없음. 카피는 `content/`, 토큰만.
  - `COMPLETED`는 같은 패널의 완료 모드다. 읽기 순서: 제목·`완료` 표지 → 완료 결과(예정 T, 첫 관측 시각·지연, 완료 목표, 관측값, 비교 상태·이유) → 지표 여섯 → 세 신뢰 축 → 근거·이력 토글 → 별도 영역 `종료 후 현재가치`(값·측정 시각·동기화 버튼) → 계획 조건 → `새 계획 준비` 설명.
  - 관측값은 `T` 시각의 평가액이 아니라고 말한다. 계산 불가는 0·0%로 보이지 않는다. 성공·실패·축하·투자성과 판정 문구 금지(기존 금지어 시험 적용).
  - 자동 진입 트리거는 `COMPLETED`에서도 돈다(관측 전이면 첫 관측을 위해). 수동 동기화 초점·status 문장은 READY 관용을 따른다.
  - 320px·200%·400%·키보드·스크린리더에서 완료 결과와 종료 후 현재가치가 서로 다른 제목의 영역으로 읽힌다. 브라우저 저장소 쓰기 0.
- **관측·삭제**(AC-9).
  - 작업·발행 로그는 기존 한 줄 관용(목적·결과 코드·지연)이며 `user_id`·금액·자격정보를 남기지 않는다.
  - 계정 데이터 삭제는 완료 기록·완료 작업·완료 계산을 지우고(plan erase 순서에서 `plan_definition`보다 먼저) 다른 사용자는 보존한다. 삭제 상태가 전환·작업·발행보다 우선한다.

**Ask First:**
- 새 내부 화면 경로나 전역 내비게이션.
- identity·portfolio·operations·source의 새 공개 API(필요해 보이면 멈추고 묻는다).
- CALC·SYNC-UX·ERD 숫자(24시간·30일·2년·60초) 변경, 계산 정책 버전 변경.
- 브라우저 층(e2e) 완료 시나리오를 위한 dev 스택 시계·`T` 조작 표면.

**Never:**
- `T` 시각 평가액의 보간·추정, 마지막 이전 값이나 이후 값을 `T` 값으로 쓰는 것.
- 백그라운드 예약 전환·수집·polling. `GET`에서 쓰기.
- 고정된 첫 관측의 교체(더 늦거나 유리한 값), 확정된 비교의 재계산.
- 중도 종료 변경(2.13), 새 계획 생성(2.15), 연결 해제(2.16).
- 다른 모듈 테이블 직접 접근, 모듈 사이 FK, `double`·`float` 금융 계산.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| BEFORE_T | 활성, DB now = T − 1ms | 2.11 동작 그대로, 전환 없음 | N/A |
| AT_T | now = T, 대시보드 진입 | GET은 `COMPLETED`/`PENDING`; ENTRY sync가 전환·완료 작업 접수 | N/A |
| CONFIRMED | 관측 `measured_at = T + 1h`, 입력 완전, 종료 후 흐름 없음 | 완료 기록 `CONFIRMED`, 완료 계산 v+1, 포인터 이동, 지표 여섯(REQUIRED_RATE 불가) | N/A |
| DELAY_24H | 지연 정확히 24h / 24h+1ms | `CONFIRMED` / `UNAVAILABLE`·`OBSERVATION_DELAY_EXCEEDED`, 관측값 보존 | N/A |
| POST_END_FLOW | `T < ti <= measured_at` 입금 | `UNAVAILABLE`·`POST_END_CASHFLOW`; 완료 계산은 `(t0,T]` 흐름만 | N/A |
| BOUNDARY | `ti = t0`, `ti = T`, `ti > T`, 같은 ms 다중 | t0 제외·T 포함·T 뒤 제외, 각 한 번 | N/A |
| HELD | 검증에 설명되지 않는 사건·가격 결측 | 계획 `COMPLETED` 유지, 비교 `HELD`, 포인터 불변; 재동기화는 관측 재사용 | N/A |
| TRANSIENT | 수집 중 429·시간 불신 | 비교 `PENDING` 유지, 작업 실패 원인 표시 | 다음 사용자 동기화가 재시도 |
| LONG_GAP_AT_T | `now − verified > 30일` | 이력 호출 0, `UNAVAILABLE`·`DATA_GAP`, 관측만 고정 | N/A |
| AFTER_COMPLETION | 확정 뒤 여러 번 동기화 | `POST_END_VALUE`만; 완료 기록·완료 계산 불변, `postEnd` 갱신 | N/A |
| RACE_EARLY_END | 중도 종료 확정과 전환 동시 | 종단 상태 하나; 진 쪽은 기존 409·404 | N/A |
| LATE_WORKER | 전환 커밋 뒤 진행 중 SYNC 작업 | `PLAN_PERIOD_ENDED`, 쓰기 0 | N/A |
| ROLLBACK | 완료 발행 단계 실패 주입 | 완료 기록 `PENDING`·포인터·계산 불변 | 작업 실패, 재시도 가능 |
| DELETION | 완료 계획 계정 삭제 / 삭제 중 전환 | 완료 기록·작업·계산 삭제, 타 사용자 보존 / 403·전환 없음 | N/A |

</frozen-after-approval>

## Code Map

**계약**
- `epics.md:2489-2579`: Story 2.14 AC-1~AC-9·테스트 기준. `2601-2610`: 2.15가 종단 상태(중도 종료·완료)를 비교 상태와 독립으로 판별하길 기대.
- `EPIC-IMPLEMENTATION-READINESS.md`: 67(안정 순서), 77(`t0<ti<=min(tc,T)`), 82(계산 불가 reason), 101(CV-11), 104-111(SYNC-UX).
- `prd.md`: 94(`u(t)=min(t,T)`), 117-118(Target), 146-152(AdjustedPrincipal·GoalProgress), 159-162·313-316(완료 결과).
- `EXPERIENCE.md`: 79-81(완료 화면 컴포넌트), 190, 332-341(Flow 4 세 구획), 65·67·80·205(금지 문구), 212(live region 하나).

**스키마** (`backend/src/main/resources/db/migration/`, 새 파일 `V14__plan_completion.sql`)
- `V10`: `plan_definition` 20-50(부분 유일 `WHERE status='ACTIVE'` 48), `sync_plan_calculation` 167-220(`cashflow_verified_through=basis_at` 217, `t0<basis_at<end_at` 218), 지표 235-248(`unavailable_reason`은 REQUIRED_RATE·GOAL_PROGRESS만 248), 포인터 279-290.
- `V11`: `sync_plan_sync_job` 18-90(실패 코드 목록 62-90), 계산 열 146-158, 포인터 이력 165-181.
- `V12`: `purpose`·`reason_code` 49-66(목적 모양 CHECK), `initial_tc` 19-20, 구간 kind 91, 변경 행 153-176.
- `V13`: 상태 CHECK 교체 28-30(이름으로 DROP/ADD 관용), `UNIQUE(plan_id,user_id,status)` 32, `plan_end` 복합 FK·completion CHECK, 주석 16-18("2.14의 완료는 이 행을 넓히지 않는다" → 새 `plan_completion` 표).

**백엔드 — calculation** (`backend/src/main/java/com/bokrilog/calculation/`)
- `PlanCalculator.java`: 공개 유일 클래스. `Metric` 52-59, `UnavailableReason`(`REMAINING_PERIOD_NOT_POSITIVE` 72-73 — 정의만 있고 미도달), `Input` 124, `calculate` 196, `conformant`.
- `internal/PlanFormula.java:80-120`: `tc<T` 강제(87), `horizon`·`upper=min(tc,T)`(93·95)는 이미 T 뒤를 대비. 완료 함수는 elapsed=total, remaining=0, 흐름 상한 T로 같은 식을 쓴다. `SeoulCalendar.periods`(76-90)는 역순 구간에서 던진다.
- `Conformance`: CV-11 fixture를 더한다.

**백엔드 — sync** (`backend/src/main/java/com/bokrilog/sync/`)
- `SyncJobApi.java`: `Dashboard` sealed 245-268(`Ended` 267), `PlanEndRecord` 289-291, `VerificationReason` 652-653, `admitSystemVerification` 758.
- `PlanPublicationPort.java`: `activePlan` 44, `PlanEnd` 56-58, `LatestPlan` 65, `latestPlan` 71. 전환 명령(`completePlan`, `MANDATORY`)과 완료 기록 읽기·비교 갱신을 여기 더한다(sync 소유, plan 구현).
- `internal/PlanSyncService.java`: `admitInTransaction` 131(사용 불가 144), `facts` 203, `state` 221, `run` 264-321(`PeriodEnded` 291), `runVerification` 339-435, `fixTime` 455-470, `fixFinalTime` 479-502, `collectAndComplete` 513, `classifyConvertPublish` 778, 거절 매핑 800-803, `prepare` 822-843.
- `internal/SyncWindow.java`: `longGap` 46, `untrustedTime` 51, `scope` 81-88.
- `internal/VerificationPlanner.java`: `precision` 89, `catchUp` 107, `newRange` 136, `finalSweep` 141, `reused` 276. 완료 계획은 이것을 재사용한다.
- `internal/PlanVerificationService.java`: `SYSTEM_REASONS` 53-55, `admitSystem` 140-183(T 뒤 거절).
- `internal/PlanSyncGuard.java`: `unavailableReason` 87-95(`PLAN_PERIOD_ENDED` 93), `blocked` 117-132(130). 완료·종료 후 목적은 T 뒤에도 통과해야 한다.
- `internal/PlanSyncPublisher.java:89-151`: 잠금 순서와 쓰기 순서(`publishNext` → `insertCalculation` → `insertFlow` → `insertChanges` → `pointNext`). 완료 발행이 같은 틀을 쓴다. `POST_END_VALUE`는 `publishNext`까지만.
- `internal/PlanPublicationService.java`: `calculationInput` 492-500, `dashboard` 579, `bundle` 598-658(비활성·비종료는 `Unavailable` 605), 상수 사본 665-670, `endedBundle` 681-709, `endAgrees` 718-737, `shown` 745-753, `planEndBasis` 766.
- `internal/AdjustmentHistoryService.java:122-127`: 표시 계획 참조. 2.10 계보의 흐름 범위 `(t0, verified]`를 완료 버전에서 `(t0, T]`로 판정한다.
- `internal/DashboardTrust.java`: 상수 62-77, `judgeEnded` 175-186, `planEnd` 206-213, `freshness` 252-254.
- `internal/SyncJobService.java:27-36` + `SyncCommandNamesTest`(18-50): plan 상수 사본 대조. `COMPLETED`·비교 이름을 더한다.

**백엔드 — plan·루트**
- `plan/PlanEndApi.java`: `STATUS_*` 62-63, `Completion` 69. `STATUS_COMPLETED`를 더한다.
- `plan/internal/PlanEndRepository.java`: `endPlan` 38-42(UPDATE 관용), `endedPlanExists` 81-83(`ENDED_EARLY`만 → 종단 둘), `latestPlan` 90-110(LEFT JOIN `plan_end` → `plan_completion`도).
- `plan/internal/PlanEndService.java:68-92`: 잠금 순서 본보기. `PlanEndRules.judge` 50-67.
- `plan/internal/PlanConditionService.java`: `PLAN_ENDED` 85·106·135·163, 포트 구현 44. `PlanConditionRepository.java:163-170`: erase 순서.
- `DashboardController.java`: 상태 switch 225-241, 로그 243-250, `DashboardView` 125, `EndView` 134, `endView` 264, `planStatus` 191·905.

**백엔드 시험** (`backend/src/test/java/com/bokrilog/`)
- `PlanSyncTestBase.java`: `publishedPlan` 152-181(T = 첫 snapshot + 30일), `moveVerification` 88, `shiftStart` 190, `connect` 203, `holdPlanLock` 223. `moveEnd(user, interval)` 도우미를 더한다.
- `PlanEndTest.java:314-361`: T를 과거로 옮기는 SQL 선례(basis를 먼저 옮겨 CHECK 회피). `924-980`: V13 CHECK 단정 — V14에 반응한다.
- `FirstSnapshotTest.java:2017·2281`: `end_at = clock_timestamp() + interval` 선례.
- `PlanVerificationTest.java`(이름 주의), `IncrementalSyncTest.java:643-680`(경합 틀).
- `DevBybitStub.java`: 시간 API·wallet 시각 = 실제 시각(449-450·875-894), 시나리오 `INCREMENTAL` 617-670(시간 경과 입금 — `POST_END_CASHFLOW` 재현에 쓴다). 스텁은 바꾸지 않는다.

**프런트엔드** (`frontend/src/`)
- `session/useDashboard.ts`: `PlanResult` 51-85, `isPlanResult` 151-200, `PlanEndRecord`·`parsePlanEndRecord` 708-782(`endedAt < T` 754 — 완료는 반대로 `measured >= T`), `DashboardResponse` 790-806, `NEXT_ACTIONS` 814-819, 상수 821-826, `parseDashboard` 837-865, `supersedes` 873-883(종단 뒤 활성 거절 880), `Accepted` 890-899, `DashboardView` 908-915, read 1035-1066(ENDED를 UNKNOWN 블록으로 채움 1053), `bundleOf` 1120-1124, `dashboardAnnouncement` 1214-1247.
- `end !== null`을 "비활성"으로 쓰는 곳: `PlanResultPanel.tsx:261·503-504·559·639`, `usePlanEnd.ts:90·318·381`, `useDashboard.ts:1046`. 종단 판별 함수 하나로 바꾼다.
- `session/useDashboardSync.ts`: ENTRY 759-763, VISIBILITY 790-799(`READY`만), `judge` 359-(ENDED 묶음 375), `settled` 464-470, `canRequest` 882-891.
- `components/PlanResultPanel.tsx`: `Trust` 251-363(종단이면 신선도 축·조작 숨김 306-336), 본체 484-649(`PlanEndRecord` 자리 586, 새 계획 준비 616-618), layout effect 초점 528-555.
- `components/PlanEndRecord.tsx`: `<dl>` 관용, `formatDelay` 70-73. `dialog.distinct`(91)는 완료 화면에 쓰지 않는다.
- `components/SyncControl.tsx:324-615`: 버튼 573-585, 사용 불가 사유 605-609. 종료 후 현재가치 영역에서 재사용한다.
- `components/CalculationEvidence.tsx:328-336` + `content/calculationEvidence.ts:201`: `endedCurrent` 문구를 완료와 가른다.
- `content/planSync.ts:42·111`, `planVerification.ts:76·108`, `planEnd.ts:108`, `planResult.ts:155-158·274-322·381`: `PLAN_PERIOD_ENDED`·신선도·다음 행동 카피.
- 시험: `test/planResultFixture.ts`(ENDED 픽스처 259-331 본보기), `session/useDashboardEnded.test.tsx`(250-261: READY의 `COMPLETED` 거절 시험은 유지), `PlanEndRecord.test.tsx`(금지어 252), `PlanEndControl.test.tsx:37·338-`(금지어 정규식·카피 문자열 검사), `test/setup.ts` 스텁 94-144.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V14__plan_completion.sql` -- expand만 -- 상태 CHECK에 `COMPLETED`(이름으로 교체); 새 `plan_completion`(plan 소유: `plan_id` PK, `user_id`, `status='COMPLETED'` 복합 FK, `completed_at`(=T), `comparison`·`reason`(쌍 CHECK), 관측 snapshot ID·값·`observed_measured_at >= completed_at`·지연(=차), 완료 계산 ID, `post_end_cashflow_count`, 시각들); `sync_plan_calculation`에 `calculation_kind`(`PROGRESS` 기본)와 kind별 CHECK 분리; 지표 `unavailable_reason` CHECK에 `PLAN_DIFFERENCE`·`COMPLETION_NOT_COMPARABLE`; 작업 `purpose`에 `COMPLETION`·`POST_END_VALUE`와 모양 CHECK; 머리말에 V10·V13 주석의 이어짐을 적는다.
- [x] `calculation/PlanCalculator.java`, `internal/PlanFormula.java`, `Conformance` -- 완료 함수(`u=T`, 관측 E, 비교 가능 플래그) -- 같은 식·같은 정책; CV-11과 경계(`ti=t0`·`=T`·`>T`·같은 ms) fixture.
- [x] `plan/PlanEndApi.java`(+상수), 새 `plan/internal/PlanCompletionService.java`·`PlanCompletionRepository.java`, `PlanEndRepository.java`, `PlanConditionService.java`·`PlanConditionRepository.java` -- 전환(잠금 → `now>=T` 재판정 → UPDATE → 기록 INSERT), 비교 갱신(종단 규칙), 종단 판별 둘, `latestPlan`에 완료 기록, erase 순서.
- [x] `sync/PlanPublicationPort.java`, `sync/SyncJobApi.java` -- 포트의 전환·완료 기록 읽기·비교 갱신; `Dashboard.Completed`·`PlanCompletionRecord`·`PostEndValue` 타입.
- [x] `sync/internal/PlanSyncService.java`, `SyncWindow.java`, `PlanSyncGuard.java`, `PlanSyncPublisher.java`, 새 `PlanCompletionRunner.java`(필요 시) -- admission의 전환 분기와 목적 선택, `COMPLETION`(tc·관측 1회 고정·2.12 계획자 재사용·비교 판정·완료 발행)과 `POST_END_VALUE`(snapshot 발행만), guard가 두 목적을 T 뒤에 통과.
- [x] `sync/internal/PlanPublicationService.java`, `AdjustmentHistoryService.java`, `DashboardTrust.java`, `SyncJobService.java` -- 표시 계획에 `COMPLETED`, 완료 묶음과 일관성, 전환 전 `ACTIVE ∧ now>=T`의 `COMPLETED`/`PENDING` 표시, 신선도 `COMPLETED`, 근거·이력의 완료 버전 범위, 상수 사본.
- [x] `DashboardController.java` -- `COMPLETED` 직렬화(`completion`·`postEnd`·`sync`), 로그 상태.
- [x] 백엔드 시험 -- 새 `PlanCompletionTest`(PlanSyncTestBase + `moveEnd`)가 I/O matrix 전 행을 덮고 Bybit 호출 수(LONG_GAP 이력 0, 완료 뒤 wallet만)와 로그 민감값 부재를 단정; 계산 단위 시험(CV-11·경계·24h ±1ms); `DashboardTrust`·비교 판정 단위 시험; `SyncCommandNamesTest`; V13 CHECK 단정 갱신과 V14 CHECK 단정; `IncrementalSyncTest` 경합 틀에 전환 갈래.
- [x] 새 `frontend/src/content/planCompletion.ts` -- 완료 결과·비교 상태·이유·종료 후 현재가치·status 카피와 `planCompletionCopyStrings`; `planResult.ts`·`planSync.ts`·`calculationEvidence.ts`의 완료 카피·`SYNC_FOR_OBSERVATION`.
- [x] `frontend/src/session/useDashboard.ts`, `useDashboardSync.ts`, `usePlanEnd.ts` -- `COMPLETED` 엄격 파싱(지연 = `measured − T`, `measured >= T`, 모르는 비교·이유는 안전 카피), 종단 판별 함수, `supersedes`의 "종단 뒤 활성 거절" 일반화, `bundleOf`·공지, `COMPLETED`의 sync 블록 수용, 자동·수동 동기화 허용.
- [x] 새 `frontend/src/components/PlanCompletionResult.tsx`·`PostEndValue.tsx`(+css), `PlanResultPanel.tsx`, `CalculationEvidence.tsx` -- 완료 모드 읽기 순서와 두 영역(`aria-labelledby`), 종료 후 현재가치의 `SyncControl` 재사용, 초점 layout effect 의존성.
- [x] 프런트엔드 시험 -- 픽스처(`completedDashboardFixture` 등), 새 두 컴포넌트·훅 파싱·전이·패널 완료 모드·두 화면 시험: 읽기 순서, 계산 불가 비-0 표시, 금지어·위치어, 저장소 0, `COMPLETED` 자동 진입 1회, 완료 뒤 동기화가 완료 결과를 바꾸지 않음.
- [x] CI 예산 -- 로컬 백엔드 `clean build`가 15분을 넘을 때만 `ci.yml`과 `DEVELOPMENT.md`를 고친다.

**Acceptance Criteria:**
- Given `T`가 지난 활성 계획, when 사용자가 대시보드에 들어오면, then 첫 응답부터 `완료`·`확인 중`으로 보이고 진입 동기화 하나가 전환과 완료 작업을 만들며, 작업이 끝나면 패널이 완료 결과로 한 번에 바뀐다.
- Given 확정된 완료 결과, when 사용자가 여러 번 동기화하면, then 완료 결과 영역의 값·시각·지연은 그대로이고 `종료 후 현재가치` 영역만 새 값·측정 시각으로 바뀐다.
- Given `HELD` 비교, when 사용자가 다시 동기화하고 검증이 풀리면, then 처음 고정한 관측값·지연으로 비교가 확정된다(더 늦은 관측을 쓰지 않는다).
- Given 완료 계획, when 근거·조정 이력을 열면, then 완료 버전의 `(t0, T]` 흐름과 버전 계보를 읽기 전용으로 보인다.

## Spec Change Log

## Design Notes

**전환은 동기화 admission이 한다.** AC-1의 두 진입점(대시보드 진입·동기화 요청)은 모두 이미 `POST /api/dashboard/sync`로 모인다(진입은 ENTRY 트리거). GET은 읽기 전용 `REPEATABLE READ`라 쓰지 않고, 전환 전 상태를 `COMPLETED`/`PENDING`으로 **보이기만** 한다. 백그라운드 전환이 없으므로 이것이 "활성으로 보이지 않는다"를 지키는 유일한 길이다.

**완료 기록은 별도 표, 완료 계산은 기존 계산 표의 새 kind.** V13 주석대로 `plan_end`를 넓히지 않는다. 계산 표를 재사용해야 2.9·2.10 근거·이력이 완료 버전을 그대로 읽는다. 진행 버전의 `cashflow_verified_through = basis_at < T` CHECK는 kind로 나눠 보존한다 — 완료 버전은 `basis_at = T < measured_at <= cashflow_verified_through`다.

**관측은 검증보다 먼저, 한 번.** "가장 이른 `T` 이후 정상 snapshot"을 검증 완료 뒤로 미루면 재시도 사이에 더 늦은(유리할 수 있는) 값이 끼어든다. 그래서 첫 정상 wallet 관측을 바로 기록하고, 검증 보류(`HELD`)가 풀릴 때도 그 값을 쓴다. `T` 이후 snapshot을 만드는 경로가 완료 작업뿐이므로 그것이 곧 가장 이른 값이다.

**비교 불가여도 입력이 완전하면 완료 계산을 낸다.** 완료 목표(Target(T))는 관측과 무관하게 정의되므로 24시간 초과·종료 후 흐름에서도 보인다. 관측과 엮인 `PLAN_DIFFERENCE`·`GOAL_PROGRESS`만 `COMPLETION_NOT_COMPARABLE`이다(0 대체 금지).

**이 SPEC이 정한 것(문서에 없음).**

| 결정 | 값 |
|---|---|
| 상태·비교·이유 이름 | `COMPLETED`; `PENDING`·`CONFIRMED`·`UNAVAILABLE`·`HELD`; 이유 다섯 |
| 관측 상한 `U` | 관측 뒤 다시 고정한 `tc`(≥ `measured_at`) |
| 완료 뒤 동기화 | `POST_END_VALUE`(wallet → portfolio snapshot만) |
| `ti = T` | 포함 |
| 정확히 24시간 | 비교 가능(ERD CV-11 "이하") |

```json
// GET /api/dashboard (COMPLETED)
{ "state": "COMPLETED", "nextAction": "PREPARE_NEXT_PLAN",
  "result": { "planStatus": "COMPLETED", … },
  "completion": { "scheduledEndAt": "…", "comparison": "UNAVAILABLE", "reason": "OBSERVATION_DELAY_EXCEEDED",
                  "observedValue": "1250.5", "observedMeasuredAt": "…", "observationDelayMillis": 90000000,
                  "completionTarget": "1331", "postEndCashflowCount": 0 },
  "postEnd": { "value": "1302.1", "measuredAt": "…" }, "sync": { … } }
```

**구현에서 확정한 것 (frozen 블록 밖, 리뷰 뒤):**
- **지속성 검토로 보류된 비교는 다시 확인하지 않는다(B1, 리뷰 뒤).**
  - frozen은 "`HELD`면 `COMPLETION` 작업, 다음 행동 `SYNC_FOR_OBSERVATION`"이었다. 그대로 두면 사라진 사건·충돌·조회 범위 불능·영구 판정/환산 사유처럼 다시 물어도 같은 답이 나오는 검토에서 매 요청이 이력을 다시 수집하고 다시 `HELD`가 되는, 풀 수 없는 루프가 된다.
  - 그래서 2.12의 불변식(지속성 검증 검토는 같은 기준 계산 동안 끈적이고 작업을 만들지 않으며 다음 행동은 `NONE`)을 완료에 그대로 적용했다. 관측이 고정됐고 `HELD`의 원인이 같은 기준 계산에 대한 지속성 검토(`PlanSyncRepository`의 2.12 술어를 `COMPLETION` 목적에 적용)면 admission은 `COMPLETION` 대신 `POST_END_VALUE`를 접수한다. 비교는 `HELD`로 남고, 종료 후 현재가치만 갱신되며, 대시보드 `nextAction`은 `PREPARE_NEXT_PLAN`이다.
  - 지속성이 아닌 검토(관측 전 wallet 응답 형식 오류처럼 수집 진단이 없는 검토)만 다시 시도한다.
  - 판별 필드: `completion.retryable`(boolean). `nextAction === 'SYNC_FOR_OBSERVATION'` ⇔ `retryable`이다. 화면은 이 값으로 `HELD` 문장을 가른다 — 재시도가 없을 때 "다시 동기화하면 확인한다"고 약속하지 않는다.
  - `HELD`면 입력 신뢰도 축은 `REVIEW_REQUIRED`/`VERIFICATION_REVIEW_REQUIRED`/다음 행동 `NONE`이다(B12). 다시 동기화할지는 화면 수준 다음 행동이 말한다.
- **완료 뒤 대기 기준(B2, 리뷰 뒤).** 완료된 계획의 요청은 2.11의 간격·cooldown 규칙을 따르되, 기준은 완료 전환 시각(`transitioned_at`) **이후**의 마지막 admission뿐이다(`completedWait`). 공식 발행 시각(그 결과는 `T` 이전)도, 전환 전 진행 동기화의 admission도 첫 관측을 미루지 않는다. 처음 구현은 발행 시각만 뺐고 전환 전 admission까지 셌다.
- **다음 행동.** `COMPLETED`의 화면 수준 다음 행동은 관측 전·`PENDING`·지속성이 아닌 `HELD`면 `SYNC_FOR_OBSERVATION`, 그 밖(확정·계산 불가·지속성 `HELD`)은 `PREPARE_NEXT_PLAN`이다. frozen의 "관측 전/HELD면 `SYNC_FOR_OBSERVATION`"을 B1로 좁혔고, `PENDING`(관측이 고정됐지만 검증이 일시 실패)도 재시도 대상으로 넣었다.
- **완료 계산의 남은 필요 등가수익률**은 언제나 `REMAINING_PERIOD_NOT_POSITIVE`다. 현재가치가 0 이하여도 그렇다 — `u = T`에서는 남은 기간이 없다는 것이 가장 근본적인 이유다.
- **비교 가능 판정은 계산기가 스스로 한다(B9, 리뷰 뒤).** `CompletionInput`은 관측 측정 시각과 "종료 후 확인된 외부 흐름이 있는가"를 받는다. 계산기가 `observationWithinLimit`으로 지연을 판정하고, 관측 시각을 canonical 입력 hash에 넣는다. sync의 `CompletionComparison`은 사유(지연 먼저, 그다음 종료 후 흐름)를 같은 사실로 판정하고, 두 판정이 다르면 작업을 `CALCULATION_INPUT_INVALID`로 끝낸다.
- **지연은 밀리초 하나다(B3, 리뷰 뒤).** wallet `measured_at`·`received_at`을 저장·판정 전에 ms로 자른다. 그래서 SQL의 지연 계산, Java `Duration.toMillis`, V14 CHECK, `observationWithinLimit`이 같은 값을 본다.
- **`T` 직후의 정상 시계 차는 별도 코드다(B8, 리뷰 뒤).** Bybit 시각이나 wallet 측정 시각이 신뢰 범위(60초) 안이지만 아직 `T` 전이면 `PROVIDER_END_NOT_REACHED`(FAILED, 재시도 가능)다. `PROVIDER_TIME_UNTRUSTED`는 60초 밖의 시계에만 쓴다. V14가 실패 코드 두 CHECK를 함께 넓힌다.
- **종료 후 현재가치(`postEnd`).** portfolio 현재 공식값이 관측 snapshot이 아니고 측정 시각이 관측보다 늦을 때만 싣는다. 없으면 키를 싣지 않는다(`DashboardView`는 NON_NULL). 화면은 키 부재와 `null`을 같게 읽는다.
- **완료된 계획의 현재 버전**은 근거 버전 목록에서 `appliedUntil = null`이다. 완료 버전은 다음 버전으로 대체되지 않는다. 계보가 이어지는 것은 2.15의 후속 계획이다.
- **결과의 계산 종류(F2, 리뷰 뒤).** 대시보드 `result.calculationKind`(`PROGRESS`|`COMPLETION`)와 근거 `calculation.calculationKind`를 모든 상태에 싣는다. 화면은 시각 비교(`basisAt == T`) 대신 이 값으로 완료 버전을 판별한다.
- **완료 모드의 변경 표지(F6, 리뷰 뒤).** `COMPLETED`도 `change` 블록을 싣는다. 완료 버전도 직전 버전과의 흐름 차이를 쓰는 증분 발행이다(2.12 계약). 목적은 `COMPLETION`이고, 늦은 변경이 없으면 `VERIFIED_NO_CHANGE`다.
- **완료 버전 조정 이력의 제외 건수(B7, 리뷰 뒤)**는 보이는 범위 `(t0, T]` 안에서 센다. 분류 묶음은 `(t0, U]`를 덮는다. 입금 판정 술어는 조정 이력 합계의 `usd_value !~ '^-'` 하나로 맞췄다(B6).
- **표시 계획 일치 판정 하나(B5, 리뷰 뒤).** 활성은 AD-23 portfolio 포인터, 중도 종료는 종료 기록, 완료는 완료 기록과 관측 snapshot이다. 이 판정을 `ShownPlanAgreement` 하나로 모아 대시보드·근거·조정 이력이 함께 쓴다.
- **완료 발행의 스키마 거절(B4, 리뷰 뒤).** V14 CHECK 위반으로 거절되면 lease 만료를 기다리지 않는다. 작업을 `FAILED`/`SYNC_FAILED`로 바로 닫고, 예외 클래스 이름만 담은 로그 한 줄을 남긴다. `PlanCompletionService`가 비교·이유·완료 계산·종료 후 흐름 수의 짝을 Java로 먼저 검사한다.
- **도달하기 어려운 갈래.** `HISTORY_OUT_OF_RANGE`는 장기 미동기화가 먼저 `DATA_GAP`으로 가므로 정상 흐름에서 드물다. 통합 시험은 계획 시작을 SQL로 2년 넘게 되돌려 재현한다(2.12 시험의 범위 밖 설정 관용). 지속성이 아닌 `HELD`는 스텁이 만들 수 없어, 관측을 고정한 작업 행을 SQL로 "진단 없는 검토"로 바꿔 재현한다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 clean build` -- expected: Testcontainers 포함 전체 통과.
- Node 24: `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` -- expected: 모두 통과.
- 새 compose 스택(migrate V1~V14)에서 `deploy/compose/verify-same-origin.sh --keep-stack`, 이어서 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080`으로 전체 e2e `--workers=1` -- expected: 기존 회귀 통과, 스택 정리.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check` -- expected: 통과.
- `bash frontend/scripts/stress-test.sh` -- expected: 새 화면 시험이 부하에서도 통과.

**실행 결과 (리뷰 patch 반영 뒤 최종 트리):**
- 백엔드 `clean build`(`--no-parallel --max-workers=1`): 1074 tests 통과, 실패 0, 5분 30초. 15분을 넘지 않아 `ci.yml`은 그대로다. 리뷰 전 트리는 1068/1068(5분 31초)이었다.
- 프런트엔드(Node 24): `typecheck`·`lint`·`lint:css`·`format:check`·`build` 통과, Vitest 35파일 1131/1131. `frontend/scripts/stress-test.sh` 12/12. 빌드가 JS 번들 500.31kB로 Vite의 500kB 경고선을 넘는다는 경고를 낸다(CI 판정 대상 아님).
- 새 compose 스택(migrate V1~V14): `verify-same-origin.sh --keep-stack` 959/959, 이어서 같은 스택의 주입 origin e2e 62/62(2.1분). 스택은 정리했다. 완료 시나리오의 e2e는 dev 스택에서 `T`를 당길 수 없어 더하지 않았다(Ask First).
- `test-ci-scripts.sh` 147/147, `git diff --check` 통과.
- PR #60 첫 CI: `changes`의 gitleaks(`generic-api-key`)가 `PlanCompletionTest`의 멱등 키 리터럴(`"completion-<이름>-key-<번호>"`) 15건을 잡았다. 규칙은 그대로 두고 리터럴을 실행 시점 조립 도우미 `key(이름, 번호)`로 바꿨다(17/17 통과). PR 커밋 범위가 스캔되므로 커밋을 고쳐 다시 올렸다.
- 리뷰: 리뷰어 6명(blind·edge·verification × 백엔드·프런트엔드).
  - patch: 백엔드 13건(B1~B13)과 누락 시험 여섯 묶음, 프런트엔드 11건(F1~F11)과 누락 시험 다섯 묶음. 전부 반영했다. 가장 무거운 것은 풀 수 없는 `HELD`가 매 요청 이력을 다시 수집하는 루프였고, 2.12의 지속성 검토 불변식을 완료에 적용해 닫았다(위 "구현에서 확정한 것").
  - defer 2건: 완료 비교 코드 목록의 계층 간 복제와 대조 검사 부재, 새 CSS 모듈 두 개의 영역 규칙 사본.
  - reject: 정책 수치의 카피 리터럴(기존 관행), e2e 부재(Ask First), ENDED↔COMPLETED 정정 경로(단조 상태라 불가), 진입마다의 자동 동기화(5분 간격이 제한), 결정적 계산 실패의 반복(2.11 SYNC 실패와 같은 관용), 비확정 비교의 `judged_at` 부재, 데이터 공백 관측 경합의 빈 성공(진행 중 작업 유일성이 막는다), 전환 전 활성 계획 묶음의 AD-23 대조(frozen이 완료 표시에서 제외), 목적별 진행 문장, 타입 선택 필드.

## Suggested Review Order

**전환 (입구)**

- `now >= T`인 활성 계획을 admission 트랜잭션 안에서 완료로 바꾸고 작업 목적을 고른다.
  [`PlanSyncService.java:210`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L210)

- 완료 계획의 목적 선택: 열린 비교는 COMPLETION, 지속 검토 보류·확정은 POST_END_VALUE.
  [`PlanSyncService.java:226`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L226)

- 잠금 아래 `end_at <= statement_timestamp()` 재판정 뒤 `WHERE status='ACTIVE'` 한 문장.
  [`PlanCompletionRepository.java:36`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanCompletionRepository.java#L36)

- plan이 소유하는 전환·관측·비교 기록. 호출자 트랜잭션만 받는다.
  [`PlanCompletionService.java:44`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanCompletionService.java#L44)

**완료 작업과 비교 판정**

- 관측 1회 고정 → 2.12 계획자로 필요 구간만 검증 → 완료 발행.
  [`PlanSyncService.java:602`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L602)

- 지연 판정이 종료 후 흐름보다 먼저다. 정확히 24시간은 비교 가능.
  [`CompletionComparison.java:33`](../../backend/src/main/java/com/bokrilog/sync/internal/CompletionComparison.java#L33)

- 계산기가 비교 가능 여부를 스스로 판정하고 관측 시각을 hash에 담는다.
  [`PlanCalculator.java:162`](../../backend/src/main/java/com/bokrilog/calculation/PlanCalculator.java#L162)

- 같은 식을 `u = T`로: 흐름은 `(t0, T]`, E는 관측값.
  [`PlanFormula.java:67`](../../backend/src/main/java/com/bokrilog/calculation/internal/PlanFormula.java#L67)

- 무결성 위반을 잡아 작업을 닫고 한 줄 로그를 남긴다.
  [`PlanSyncService.java:779`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L779)

**원자 발행**

- 완료 계산·지표·흐름·변경 행·포인터 CAS·비교 기록을 한 트랜잭션으로.
  [`PlanSyncPublisher.java:214`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncPublisher.java#L214)

- 첫 관측과 종료 후 현재가치는 portfolio snapshot만 전진시킨다.
  [`PlanSyncPublisher.java:170`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncPublisher.java#L170)

- 완료·종료 후 목적은 T 뒤에도 통과하고 나머지 차단 규칙은 같다.
  [`PlanSyncGuard.java:162`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncGuard.java#L162)

**스키마**

- 상태 `COMPLETED`와 완료 기록 표. 비교·이유·관측·지연·순서를 CHECK로 짝짓는다.
  [`V14__plan_completion.sql:33`](../../backend/src/main/resources/db/migration/V14__plan_completion.sql#L33)

- 계산 kind로 V10의 `basis_at < T` 규칙을 진행 버전에만 남긴다.
  [`V14__plan_completion.sql:97`](../../backend/src/main/resources/db/migration/V14__plan_completion.sql#L97)

**대시보드·근거·이력 읽기**

- 표시할 계획이 완료면 완료 묶음. T가 지난 활성 계획도 쓰지 않고 완료로 보인다.
  [`PlanPublicationService.java:625`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L625)

- 완료 묶음의 일관성: 기록·포인터·계산·관측 snapshot이 모두 맞아야 200.
  [`PlanPublicationService.java:760`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L760)

- 대시보드·근거·이력이 공유하는 단일 판정.
  [`ShownPlanAgreement.java:38`](../../backend/src/main/java/com/bokrilog/sync/internal/ShownPlanAgreement.java#L38)

- 완료의 신뢰 축: 신선도 `COMPLETED`, 보류면 입력 신뢰도 검토 필요.
  [`DashboardTrust.java:197`](../../backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java#L197)

- `completion`·`postEnd` 직렬화.
  [`DashboardController.java:139`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L139)

**화면**

- `COMPLETED` 엄격 파싱: 지연 = `measured − T`, `retryable`, `calculationKind`.
  [`useDashboard.ts:891`](../../frontend/src/session/useDashboard.ts#L891)

- 종단 판별 하나로 ENDED·COMPLETED를 묶고, 종단 뒤 활성은 거절한다.
  [`useDashboard.ts:1209`](../../frontend/src/session/useDashboard.ts#L1209)

- 완료 모드의 동기화 판정과 READY→COMPLETED 전이의 관측 트리거.
  [`useDashboardSync.ts:386`](../../frontend/src/session/useDashboardSync.ts#L386)

- 읽기 순서: 완료 결과 → 지표 → 축 → 근거·이력 → 종료 후 현재가치.
  [`PlanResultPanel.tsx:635`](../../frontend/src/components/PlanResultPanel.tsx#L635)

- 예정 T·관측 시각·지연·완료 목표·비교 상태 영역.
  [`PlanCompletionResult.tsx:27`](../../frontend/src/components/PlanCompletionResult.tsx#L27)

- 별도 제목의 종료 후 현재가치 영역과 동기화 버튼.
  [`PostEndValue.tsx:31`](../../frontend/src/components/PostEndValue.tsx#L31)

- 재시도 없는 보류는 다시 확인을 약속하지 않는다.
  [`planCompletion.ts:80`](../../frontend/src/content/planCompletion.ts#L80)

**시험**

- I/O matrix 전 행과 리뷰 뒤 회귀를 실제 스택에서 확인한다.
  [`PlanCompletionTest.java:47`](../../backend/src/test/java/com/bokrilog/PlanCompletionTest.java#L47)

- 지속 검토 보류: 다음 요청은 종료 후 현재가치이고 이력 호출 0.
  [`PlanCompletionTest.java:381`](../../backend/src/test/java/com/bokrilog/PlanCompletionTest.java#L381)

- 완료 모드 패널·두 화면·훅 전이.
  [`CompletedPlanScreens.test.tsx:1`](../../frontend/src/screens/CompletedPlanScreens.test.tsx#L1)
