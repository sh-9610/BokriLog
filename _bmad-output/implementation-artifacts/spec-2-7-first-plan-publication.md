---
title: '최초 계획 계산과 활성 계획·공식 결과의 원자 발행'
type: 'feature'
created: '2026-09-28'
status: 'done'
baseline_commit: '6248e3c294e9ca814333fbc8f42282e3a34c2d6b'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-BokriLog-2026-08-24/PORTFOLIO-SOLUTION-DESIGN.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-6-cashflow-usd-conversion.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.7 범위와 CALC-1.0·CALENDAR-SEOUL-1.0·AD-23 계약">

## Intent

**Problem:** 계획 조건(2.3)과 검증된 입력(2.4~2.6)이 준비돼도 활성 계획·공식 결과가 없다. 따로 만들면 "계획은 있는데 결과가 없거나 서로 다른 데이터로 계산된 지표"가 보일 수 있다.

**Approach:** 순수 `calculation` 모듈이 `CALC-1.0`·`CALENDAR-SEOUL-1.0`으로 여섯 지표를 계산한다. 그 결과가 성공할 때만 sync 발행 서비스가 하나의 outer `REQUIRED` 트랜잭션에서 다음을 모듈 public port로 함께 확정한다: 활성 계획, 자금흐름 이력 연결, tc 공식 portfolio snapshot, 최초 계산 버전, 두 CAS, intent 소비. `/dashboard`는 그 공식 포인터 하나에서 최초 결과를 보여 준다.

## Boundaries & Constraints

**Always:**
- **자격:** 소유한 OPEN intent가 있어야 하고, 그 intent 계보의 COMPLETE 후보·판정 묶음과 계산 가능(`calculable`) 환산 묶음이 같은 user·portfolio·활성 membership·provider account에 속해야 한다. admission·claim·발행 트랜잭션에서 삭제·운영 제한·활성 계획 부재·연결·정책 버전·`P0 > 0`·`t0 < tc < T`·`serverNow < T`를 서버 권위 값으로 다시 판정한다.
- **계산 기준:** 계산 기준시각은 2.4가 고정한 `tc`다. `E(tc)`는 같은 후보의 `totalEquity`이며 그 `measured_at`을 따로 보존한다. `cashflow_verified_through = tc`다. `Fi`는 `sync_calculable_cashflow`의 `t0 < ti <= min(tc,T)` 행만 `(ti, provider_account_id, source_id)` 순으로 정확히 한 번 쓴다.
- **calculation 모듈:** 순수하다. DB·HTTP·Spring·시계·Bybit·다른 모듈 타입에 의존하지 않고 provider 중립 값 객체만 받는다. 모든 산술은 `MathContext(34, HALF_EVEN)`, 거듭제곱은 버전 고정 `ch.obermuhlner:big-math` 2.3.x `pow`다. 금액·비율은 canonical decimal string(최대 34 유효숫자·scale 24)이다. 같은 입력·정책·구현 버전이면 결과와 canonical input·result hash가 같다. `CV-01`~`CV-07`·`CV-09`·`CV-10`·`CV-12`를 계약 fixture로 고정하고, 하나라도 어긋나면 발행하지 않는다.
- **발행 트랜잭션:** sync publication service가 소유한다. 계산은 트랜잭션 밖에서 하고, 트랜잭션 안에서 다음을 모두 끝낸다.
  - 사용자 잠금과 write 자격 확인
  - 입력·membership(`[started_at, ended_at)`·기준통화·restriction)·lease/fence 재검사
  - tc source snapshot, portfolio snapshot·component 생성과 portfolio `publication_sequence` CAS
  - 불변 활성 계획 생성과 intent `CONSUMED`
  - 계산 버전·지표·자금흐름 이력 저장
  - plan별 공식 포인터의 expected version CAS

  portfolio·plan command는 호출자 트랜잭션에 참여만 한다. 하나라도 실패하면 전부 롤백한다.
- **멱등성과 단일성:** portfolio당 활성 계획은 하나이며 조건부 유일 색인이 권위다. 같은 key·같은 intent는 같은 시도를 돌려주고, 같은 key·다른 intent는 409다. 경합에서 진 요청은 성공인 척하지 않고 실제 활성 계획 안내를 받는다.
- **계산 불가와 성격:** 정의되지 않는 지표는 0으로 두지 않고 지표별 이유 코드를 단다. 음수 필요율과 0% 미만·100% 초과 달성도는 자르지 않는다. 현재가치는 늘 `공식`이다. 추정 흐름이 하나라도 쓰이면 나머지 다섯 지표는 `추정`이다. `계산 불가`는 성격을 덮지 않는 별도 값이다.
- **표시:** 표시만 HALF_UP 2자리로 반올림하고 `-0.00`은 `0.00`으로 보인다. 화면은 다음을 같은 포인터 응답 하나에서 그린다: 여섯 값, 계획 기간·주기·목표율, 기준시각과 측정시각, 세 신뢰 축(`최신/동기화 중/최신 아님`, `확인됨/검토 필요/사용 불가`, `공식/추정/계산 불가`). 원자 교체, 상태 낭독, 키보드, 320px·200%·400% 확대를 지원한다.
- **삭제:** 모듈별 deletion port가 대상 사용자의 계획·snapshot·계산·포인터·시도를 지우고 다른 사용자 것은 보존한다. 삭제 상태가 늦은 worker보다 우선한다.

**Ask First:** CALC·CALENDAR 정책의 공식·경계·정밀도·범위를 바꾸는 변경, 반복 증분 동기화나 재계산(2.11·2.12), 새 내부 화면 경로, `big-math` 외의 새 운영 의존.

**Never:**
- `double`/`float`, 중간 표시 반올림, 표시값 재사용을 하지 않는다.
- `REQUIRES_NEW`·비동기·after-commit 발행을 쓰지 않는다. 현재값을 `max(created_at)`으로 추론하지 않는다.
- 다른 모듈 internal·테이블을 직접 읽거나 쓰지 않는다.
- 부분·검토·환산 필요 묶음이나 서로 다른 수집 계보를 섞지 않는다.
- 미래 자금흐름을 예측하지 않는다.
- 투자 추천이나 성과수익률로 오인할 문구를 쓰지 않는다.
- V1~V9를 수정하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| PUBLISH_OFFICIAL | 직접 정규화 흐름만(0건 포함), 유효 자격 | 활성 계획·공식 결과 1묶음, 다섯 지표 `공식` | N/A |
| PUBLISH_ESTIMATED | 추정 환산 흐름 포함 | 같은 발행, 현재가치 `공식`·나머지 `추정`과 추정 근거 | N/A |
| NOT_COMPUTABLE | `E <= 0`, `Target <= 0`, `GoalProfit <= 0` | 금액은 보존. 해당 비율 `계산 불가`+이유, `Target <= 0`이면 입력 신뢰도 `검토 필요` | 0·0% 대체 없음 |
| RANGE | 입력·중간·결과 `\|x\| >= 1E24`나 0 아닌 `\|x\| < 1E-24` | 계획·포인터 미생성 | `CALCULATION_RANGE_EXCEEDED` |
| INELIGIBLE | 부분·검토·환산 필요, 타 사용자, 대체·만료·소비 intent, 활성 계획 존재, `P0<=0`, `t0>=tc`, `tc>=T` | 접수 거절 또는 시도 `BLOCKED`+이유, 아무것도 발행 안 함 | 403/409, 새 조건·데이터 준비 안내 |
| REPLAY / RACE | 같은 key 재전송, 같은 key 다른 intent, 두 탭 | 같은 시도 반환 / 409 / 활성 계획 하나와 패배 쪽 실제 요약 | 거짓 성공 없음 |
| LATE / DELETE | stale fence, 발행 직전·중 삭제·제한·membership 변경 | 전체 롤백, 늦은 worker 재발행 차단, 타 사용자 보존 | 안전한 이유 코드 |

</frozen-after-approval>

## Code Map

**계약**
- `EPIC-IMPLEMENTATION-READINESS.md:60-102` -- CALC-1.0 입력·범위·정밀도, `periods`, 계산 불가, 정책 ID, CV-01~12.
- `PRD §4`(`prds/prd-BokriLog-2026-08-23/prd.md:85-163`) -- 여섯 공식, 계산 불가 규칙.
- `epics.md:1755-1890` -- AC-1~AC-14.
- `PORTFOLIO-SOLUTION-DESIGN.md` §3·AD-23 -- 소유권과 두 CAS.

**plan 모듈**
- `backend/src/main/java/com/bokrilog/plan/internal/PlanConditionService.java`
  - 정책 상수는 36-41, `confirm`의 잠금·멱등 관용구는 126-175, `collectionIntent`(OPEN 요구)는 178-196, `eligibleUser`는 214-223, `eligibleSnapshot`은 225-246이다.
  - intent를 `CONSUMED`로 바꾸는 코드는 아직 없다.
- `backend/src/main/java/com/bokrilog/plan/internal/PlanConditionRepository.java`
  - `lockUser`(42)는 advisory lock이다.
  - `openIntent`(18)는 OPEN만 읽는다.
  - 정책 문자열이 SQL에 중복돼 있다(33, 56).
- `backend/src/main/resources/db/migration/V6__plan_intent.sql:9-80` -- intent·confirmation·idempotency 모양.
- **활성 계획 테이블 이름에 `active_plan`을 쓰지 않는다.** `FirstSnapshotTest.java:91-92,195,362`가 `to_regclass('active_plan') IS NULL`을 단정한다.

**portfolio 모듈**
- `backend/src/main/java/com/bokrilog/portfolio/internal/PortfolioMembershipService.java:33-45` -- `publishFirst`는 seq 1 insert 전용이다.
- `PortfolioRepository.java`
  - `lockEligible`(84), `insertSnapshot`(103), `insertComponent`(113), `pointFirst`(126).
  - 현재 포인터를 읽는 `official`(133)과 seq 1을 읽는 `firstOfficial`(147)이 따로 있다.
  - 포인터를 전진시키는 CAS는 없다. portfolio는 자기 트랜잭션을 열지 않는다.
- `V5__first_snapshot.sql:31-93`
  - `sync_source_snapshot.job_id`는 NOT NULL UNIQUE이고 FK가 first-snapshot `sync_job`을 가리킨다. 그래서 후보에서 만든 tc snapshot을 담으려면 V10에서 expand해야 한다.
  - component는 snapshot당 하나이고 `source_snapshot_id`가 UNIQUE다.

**sync 모듈**
- `SyncJobService.java` -- publish 관용구는 329-345, 재검증 `conversionStillPermitted`는 346, `initialEligible`은 477, erase는 197-204(portfolio → sync → source → identity 순).
- `CashflowConversionRepository.java` -- `eligibleClassification`(88-103), 판정의 현재 결과(420-425: 가장 최근에 끝난 시도의 bundle), `inputsStillValid`(135).
- `V9__cashflow_usd_conversion.sql:259-286` -- `sync_calculable_cashflow`가 유일한 자금흐름 입력이다.
- 후보 `tc`·`equity_decimal`·`measured_at`은 V7 `sync_initial_candidate_bundle`에 있다. 조회 경로는 conversion → classification → candidate다.
- `sync/InitialInputsPlanPort.java` -- sync가 소유하고 plan이 구현하는 역전 port다. sync가 plan에 의존하면 cycle이 된다.
- `FirstSnapshotWorker.java` -- 기존 worker처럼 dispatch·heartbeat(`renew`)·fence를 쓴다.
- `PriceConversionPolicy.canonicalResult`(336) -- 같은 canonical 형식의 전례다. calculation은 이것을 import하지 않고 자기 규칙으로 둔다.

**HTTP**
- `FirstSnapshotController.java:146-168` -- 202/409, 200/204/403, `no-store` 관용구.
- `PlanConditionController.java:50-106` -- 조건 GET·preview·confirm.
- `AuthSecurityConfig.java:1159-1213`
  - `/api/snapshots/first/**`와 `/api/plans/conditions/**`만 보호된다.
  - 새 접두사는 `requiresAuthentication`과 `isProtectedRequest`에 같은 술어로 더한다.

**경계 시험**
- `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java:38-142` -- `MODULES`에 `calculation`을 더한다.

**프런트엔드**
- `frontend/src/screens/SignedInScreen.tsx:91-144` -- `/dashboard` 셸(h1과 인접 고지, live region 하나, `FirstSnapshotPanel` 130)이다. 결과 패널은 이 셸에 둔다.
- `frontend/src/components/InitialInputsCollection.tsx:277-536,917-923`
  - 환산 섹션이다. 발행 섹션은 그 **밖의 형제**로 둔다.
  - 환산 section 안에 `role=status`가 두 개 생기면 시험(`within(section).findByRole`, Playwright strict)이 깨진다.
- `frontend/src/components/PlanConditionPanel.tsx`
  - 335-371은 저장된 intent 블록이고, 352-353은 기간·주기·목표율 줄을 재사용할 수 있다.
  - `percentFromRatio`(100-109)는 private다.
- `FirstSnapshotPanel.tsx`
  - `amount()`(8-19)는 HALF_UP 2자리 formatter인데 private다. 공용으로 추출한다.
  - 세 축(75-87)의 상태 어휘는 EXPERIENCE와 다르다.
- 발행 뒤 거짓이 되는 카피:
  - `content/auth.ts:667-682`(시험 `SignedInScreen.test.tsx:103`·`OnboardingScreen.test.tsx:107`)
  - `firstSnapshotContent.noPlan`
  - `planConditionContent.savedHelp`·`conversion.calculable`
- 값 부재 단정:
  - `SignedInScreen.test.tsx:253-265`는 `%`·`$`·USDT를 단정한다. 결과 stub이 없는 경로로 한정한다.
  - `InitialInputsCollection.test.tsx:392,450`, `e2e/first-snapshot.spec.ts:170,222`는 환산 영역 안의 `수익률`·USD 금액을 단정한다. 결과를 그 영역 밖에 두면 유지된다.
- `frontend/src/test/setup.ts:50-74,286-298` -- 정확 URL responder다. 새 endpoint는 stub 도우미를 더한다.
- `frontend/e2e/first-snapshot.spec.ts:25-172`
  - `connectAndSavePlan`·`startConversionByKeyboard`·`expectNoHorizontalScroll`을 재사용한다.
  - `uta2`가 계산 가능 `CONVERTED`(0건) 경로다.
  - 계산 가능 `CONVERTED_ESTIMATED` 스텁 시나리오는 없다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/build.gradle.kts` -- 운영 classpath에 `ch.obermuhlner:big-math:2.3.2`를 더한다. calculation만 쓴다.
- [x] `backend/src/main/java/com/bokrilog/calculation/**`(새 모듈), `ModuleBoundaryTest.java`
  - 공개 API는 값 객체와 `PlanCalculator`다. internal에 달력 `periods`와 공식을 둔다.
  - 경계 규칙: 다른 모듈·Spring·JDBC·`Instant.now`/`Clock`/`System.currentTimeMillis` 의존 금지를 ArchUnit으로 단정한다.
- [x] `backend/src/main/resources/db/migration/V10__first_plan_publication.sql`
  - plan 활성 계획(`plan_definition`, portfolio당 ACTIVE 하나).
  - intent `consumed_at`·`consumed_by_plan_id`.
  - `sync_source_snapshot`에 후보 출처 expand.
  - sync 발행 시도(job), 계산 버전·지표·자금흐름 이력, `sync_official_plan_result` 포인터(`pointer_version`).
  - 닫힌 이유·상태 CHECK와 FK 색인.
- [x] `portfolio/PortfolioMembershipApi.java`와 internal -- 호출자 트랜잭션 전용 command 하나: 후보 component로 새 snapshot을 만들고 포인터를 `publication_sequence = 기존+1` CAS로 전진한다. 실제 트랜잭션이 없으면 거절한다.
- [x] `plan/**`
  - sync 소유 port 구현: 활성 계획 존재 확인, 활성 계획 생성 + intent `CONSUMED`(호출자 트랜잭션).
  - 활성 계획이 있으면 조건 GET이 요약을 담는다. preview·confirm은 `ACTIVE_PLAN_EXISTS`로 409다.
  - `erasePlanData`를 새 테이블까지 넓힌다.
- [x] `sync/**`(`SyncJobApi`, 새 publication repository·service, `FirstSnapshotWorker`)
  - admission과 멱등, claim·fence, 입력 고정과 계산(트랜잭션 밖), 발행 트랜잭션, 결과 조회.
  - 결과 조회는 공식 포인터 기준이다. 신선도는 `serverNow` 기준 15분 규칙(ERD §4)이다.
  - `eraseSyncData` 확장.
- [x] `FirstSnapshotController.java`, 새 `PlanResultController.java`, `AuthSecurityConfig.java`
  - `POST`·`GET /api/snapshots/first/plan-publication`: POST는 202/409/422(`Idempotency-Key` 누락), GET은 200(`{job?, startable, reason?}`)/403(계정 거부).
  - `GET /api/plans/active`: 200/204/403, `no-store`, 새 술어 하나를 인가·CSRF 양쪽에 둔다.
- [x] `backend/src/main/java/com/bokrilog/DevBybitStub.java` -- BTC 입금 하나만 내는 계산 가능 추정 시나리오를 추가한다.
- [x] 프런트엔드
  - 환산 섹션 밖의 발행 섹션(`InitialInputsCollection.tsx`)과 발행 뒤 `/dashboard` 링크.
  - `PlanConditionPanel` 활성 계획 요약.
  - 새 `PlanResultPanel`과 `content/planResult.ts`, `SignedInScreen` 통합, 공용 formatter 추출.
  - 거짓이 되는 카피와 부재 단정 정리.
- [x] 시험
  - `calculation` 단위: CV fixture·달력·경계·정밀도·결정성·순수성.
  - `FirstSnapshotTest`(기존 사슬·SQL·await 도우미 재사용): matrix·원자성·경합·삭제.
  - Vitest와 e2e: `uta2` 공식 발행·표시, BTC 추정 발행·표시.

**Acceptance Criteria:**
- Given 계산 가능 환산을 끝낸 사용자, when 발행을 요청하면, then 202로 시도를 접수한다. worker는 입력을 고정하고 트랜잭션 밖에서 순수 계산한 뒤, 발행 트랜잭션 하나에서 활성 계획·source/portfolio snapshot·계산 버전·자금흐름 이력·두 CAS·intent 소비를 함께 커밋한다.
- Given 발행 트랜잭션의 어느 단계(계획·이력·component·snapshot·계산·포인터 CAS)에서 실패를 주입하면, when 커밋하려 할 때, then 새 행은 하나도 남지 않고 포인터·sequence·intent 상태도 이전과 같다.
- Given CV fixture, when 계산기를 돌리면, then 표의 canonical 값과 글자까지 같다. 같은 입력을 순서만 바꿔 다시 돌려도 결과와 hash가 같다.
- Given 발행이 끝나면, when 사용자가 `/dashboard`를 열면, then 결과 패널이 같은 포인터 응답 하나에서 다음을 그린다.
  - 여섯 카드: 이름 → 값 → 주기·기준시각 → 성격 순서, 계산 불가 이유, 추정 근거 표시.
  - 계획 조건, 기준·측정 시각(Asia/Seoul), 세 신뢰 축.
  - 인접한 `복리 계획 비교` 제목과 고지. 투자 추천·성과수익률 문구는 없다.
  - 결과가 없을 때의 기존 화면은 바뀌지 않는다.
- Given 활성 계획이 있으면, when 조건 화면을 열거나 새 preview·confirm·발행을 요청하면, then 조건 화면은 활성 계획 요약과 결과 링크를 보인다. 요청은 새 계획 없이 409와 이유를 돌려준다.
- Given 사용자 삭제가 발행과 경합하면, when 삭제 port를 순서대로 부르면, then 대상 사용자의 계획·intent·snapshot·계산·포인터·시도가 모두 사라지고 다른 사용자 데이터는 남는다. 늦은 worker는 아무것도 다시 만들지 못한다.

## Design Notes

**periods 계산**
- `periods(a, b)`는 구간마다 `경과 ms / 구간 길이 ms`를 **정확한 분수로 누적**한 뒤 34자리 HALF_EVEN으로 한 번만 바꾼다.
- 경계는 `t0`의 서울 현지 날짜·시각에 고정한다. 일은 +1일, 주는 +7일, 월은 +k개월(기준 일이 없으면 말일, 다음 달엔 원래 일로 복귀)이다.
- 구간마다 따로 반올림해 더하면 `periods(a,c) = periods(a,b) + periods(b,c)`가 마지막 자리에서 깨질 수 있다(CV-10).

**연산 순서** (CV와 글자까지 맞춘다)
- `Plan = P0·(1+r)^p(t0,tc) + Σ Fi·(1+r)^p(ti,tc)`
- `Target`은 같은 식에서 지수를 `p(·,T)`로 쓴다.
- `Difference = E − Plan`
- `RequiredRate = (Target ÷ E)^(1 ÷ p(tc,T)) − 1`: 나눗셈 → 거듭제곱 → 뺄셈 순서로, 각 단계에서 MC34를 적용한다.
- `GoalProgress = (E − AP) ÷ (Target − AP)`, 여기서 `AP = P0 + ΣFi`다.
- 검산 예: CV-02의 `1452 ÷ 1340 − 1 = 0.083582089552238805970149253731343`.

**이유 코드** (CALC-1.0 아래)
- `CURRENT_VALUE_NOT_POSITIVE`: 필요율.
- `TARGET_NOT_POSITIVE`: 필요율·달성도. 입력 신뢰도를 `검토 필요`로 둔다.
- `GOAL_PROFIT_NOT_POSITIVE`: 달성도.
- `CALCULATION_RANGE_EXCEEDED`: 발행 전체를 거절한다. CV-12와 "중간값 하나라도 범위를 벗어나면 계획을 만들지 않는다"는 규칙을 따른다.

**`Target <= 0`**
- AC-7·CV-07·PRD §4.7("계획을 `검토 필요`로 전환")에 따라 발행하되, 입력 신뢰도를 `검토 필요`로 계산 버전에 기록한다.
- 계획 행은 불변이므로 이 상태는 계산 버전이 소유한다.

**snapshot 두 개**
- 기준 snapshot은 2.2의 seq 1이고 `P0`·`t0`를 준다. 현재 snapshot은 tc의 새 seq이고 `E`를 준다.
- 계산 버전은 둘 다 기록한다. 발행 뒤 `GET /api/snapshots/first`는 포인터를 따라가므로 tc 값을 보인다.

**발행 경로의 모양**
- 2.5·2.6처럼 worker 시도다. 그래서 AC-14의 "늦은 worker"가 그대로 성립하고, 두 탭은 intent당 활성 시도 하나로 수렴한다.
- 최종 단일성은 조건부 유일 색인이 권위다.

**결과 표시**
- `/dashboard`가 결과 화면이다. 새 경로를 만들지 않으므로 다섯 곳 동기화가 필요 없다.
- 2.8은 이 패널을 공식 대시보드로 넓힌다. 이 스토리는 동기화 조작·상세 근거 화면을 만들지 않는다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 build` -- expected: Docker/Testcontainers 전체 통과, 실패 0.
- Node 24: `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` -- expected: 모두 통과.
- 수동 compose 스택(migrate V10) 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080` 전체 e2e -- expected: 통과, 스택 정리. Windows의 `verify-same-origin.sh`는 기존 CP949 결함으로 실패하므로 CI가 판정한다.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check` -- expected: 통과.

**구현에서 확정한 것 (리뷰 반영 후, frozen 블록 밖):**
- 결과 패널은 `/dashboard`와 `/onboarding` 둘 다에 붙는다. 첫 로그인 세션은 언제나 `/onboarding`으로 돌아오므로, 한 곳에만 두면 신규 사용자가 결과를 못 본다. 새 경로는 없다.
- CV-08(가격 결측)은 발행 자격에서 `환산 필요` 묶음을 거절하는 경로(INELIGIBLE)로 덮는다. CV-11(첫 종료 후 관측)은 Story 2.14의 범위다.
- CV 표의 34자리 값은 내부값으로 글자까지 단정한다. 저장·응답·hash는 CALC-1.0 canonical(scale 24 이하, HALF_EVEN, 끝 0 제거)이다. 원금·목표율·환산 USD 같은 입력은 손실 없는 plain 형식으로 정규화하고, 원 provider 문자열은 intent가 provenance로 보존한다.
- 결과 읽기는 계정 접근만 본다. 삭제 진행 중이면 거부하고, 쓰기 command 제한으로는 막지 않는다. 운영 제한 중에도 마지막 공식 결과는 보인다.
- 발행 GET은 `startable`로 새 시도 가능 여부를 서버가 판정한다. 같은 입력 hash로 이미 범위 초과·입력 무효로 끝난 시도가 있으면 새 접수를 그 이유로 거절한다(같은 실패를 재시도로 권하지 않기 위해).
- operations의 제한 판정 시각을 `now()`에서 `statement_timestamp()`로 바꿨다. 잠금을 기다리며 열려 있던 발행 트랜잭션이 그 사이 커밋된 제한을 놓치지 않게 하려는 것이다. 발행 트랜잭션 안의 lease 판정도 같은 시각을 쓴다.
- sync는 plan에 의존할 수 없으므로 `plan-condition-create` 문자열을 자기 상수로 두고, `SyncCommandNamesTest`가 plan의 상수와 같음을 고정한다.

**실행 결과 (리뷰 patch 반영 후):**
- 백엔드 build 통과: 678 tests, 실패·skip 0. 이후 sync 상수 정리 뒤 `SyncCommandNamesTest`·`ModuleBoundaryTest`(20)·`PlanPublicationServiceTest`(6)를 다시 돌려 통과했다.
- 프런트엔드 (Node 24): `typecheck`·`lint`·`lint:css`·`format:check`·`build` 통과, Vitest 370/370(3회 연속).
- 주입 origin e2e 58/58 통과(약 2.4분): 수동 compose 스택, V10 migrate, 런타임 생성 key ring export. `uta2` 공식 발행과 `btcdeposit` 추정 발행이 포함된다. 전체 제한 180초와의 여유가 좁다.
- `test-ci-scripts.sh` 147/147, `git diff --check` 통과. `verify-same-origin.sh`는 Windows CP949 결함 때문에 Linux CI가 판정한다.
- 새 시험 49곳은 제품 코드를 임시로 되돌려 의도한 단정으로 실패하는 것을 확인한 뒤 복원했다.

## Suggested Review Order

**발행 경로 (입구)**

- 입구: claim → 입력 고정 → 트랜잭션 밖 계산 → 발행 트랜잭션 하나.
  [`PlanPublicationService.java:232`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L232)

- 발행 트랜잭션: 잠금·재검사·source/portfolio snapshot·계획·계산·포인터 CAS·intent 소비.
  [`PlanPublicationService.java:282`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L282)

- 자격 판정 하나를 admission·상태·claim·트랜잭션이 함께 쓴다. T는 DB 시계로 판정한다.
  [`PlanPublicationService.java:390`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L390)

- 접수: 멱등 key 필수, 경합에 지면 최신 시도로 수렴, 같은 실패 재접수 거절.
  [`PlanPublicationService.java:102`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L102)

**순수 계산 (CALC-1.0)**

- provider 중립 입력과 결과 타입. 정규화와 적합성 검사가 공개 API다.
  [`PlanCalculator.java:196`](../../backend/src/main/java/com/bokrilog/calculation/PlanCalculator.java#L196)

- 여섯 지표와 계산 불가·범위 초과, 길이 접두 hash.
  [`PlanFormula.java:59`](../../backend/src/main/java/com/bokrilog/calculation/internal/PlanFormula.java#L59)

- `t0`에 고정한 서울 달력 경계. 월말로 붙었다가 원래 일로 돌아온다.
  [`SeoulCalendar.java:62`](../../backend/src/main/java/com/bokrilog/calculation/internal/SeoulCalendar.java#L62)

- canonical 형식(34 유효숫자·scale 24·범위)의 한 곳.
  [`Decimals.java:130`](../../backend/src/main/java/com/bokrilog/calculation/internal/Decimals.java#L130)

**모듈 port와 CAS**

- sync가 소유하고 plan이 구현하는 port. 계획 command는 호출자 트랜잭션 전용이다.
  [`PlanPublicationPort.java:17`](../../backend/src/main/java/com/bokrilog/sync/PlanPublicationPort.java#L17)

- portfolio 포인터를 `publication_sequence` CAS로 전진한다. 실제 트랜잭션이 없으면 거절한다.
  [`PortfolioMembershipService.java:53`](../../backend/src/main/java/com/bokrilog/portfolio/internal/PortfolioMembershipService.java#L53)

- 활성 계획이 있으면 새 조건을 받지 않는다(409).
  [`PlanConditionService.java:151`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanConditionService.java#L151)

- 신선도 판정(SYNC-UX-1.0, DB 시계)과 사용자 범위 삭제.
  [`PlanPublicationRepository.java:390`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationRepository.java#L390)

**스키마**

- 활성 계획 테이블과 portfolio당 ACTIVE 하나.
  [`V10__first_plan_publication.sql:20`](../../backend/src/main/resources/db/migration/V10__first_plan_publication.sql#L20)

- 후보에서 만든 tc source snapshot을 받도록 한 expand.
  [`V10__first_plan_publication.sql:73`](../../backend/src/main/resources/db/migration/V10__first_plan_publication.sql#L73)

- 계산 버전·지표·자금흐름 이력과 plan별 공식 포인터.
  [`V10__first_plan_publication.sql:167`](../../backend/src/main/resources/db/migration/V10__first_plan_publication.sql#L167)

**HTTP와 보안**

- 발행 POST(202/409/422)와 상태 GET(`startable`).
  [`FirstSnapshotController.java:207`](../../backend/src/main/java/com/bokrilog/FirstSnapshotController.java#L207)

- 공식 포인터 하나에서 읽는 결과 GET, `no-store`.
  [`PlanResultController.java:56`](../../backend/src/main/java/com/bokrilog/PlanResultController.java#L56)

- 새 경로는 인가와 CSRF·Origin 판정이 같은 술어를 부른다.
  [`AuthSecurityConfig.java:1220`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L1220)

**화면**

- 여섯 카드: 성격과 계산 불가를 따로 보이고 추정 근거를 표시한다.
  [`PlanResultPanel.tsx:30`](../../frontend/src/components/PlanResultPanel.tsx#L30)

- 세 신뢰 축: 축마다 자기 시각, 모르는 코드는 안전한 카피로 접는다.
  [`PlanResultPanel.tsx:84`](../../frontend/src/components/PlanResultPanel.tsx#L84)

- 확인 중·없음·못 읽음·있음을 구분한다(못 읽음을 "계획 없음"으로 말하지 않는다).
  [`usePlanResult.ts:135`](../../frontend/src/session/usePlanResult.ts#L135)

- 서버가 판정한 `startable`로만 시작 버튼을 보인다.
  [`PlanPublicationSection.tsx:24`](../../frontend/src/components/PlanPublicationSection.tsx#L24)

- 표시 반올림(HALF_UP 2자리, `-0.00` 정규화)의 한 곳.
  [`decimal.ts:48`](../../frontend/src/format/decimal.ts#L48)

**시험**

- CV fixture: 34자리 내부값과 canonical 값을 함께 단정한다.
  [`PlanCalculatorTest.java:63`](../../backend/src/test/java/com/bokrilog/calculation/internal/PlanCalculatorTest.java#L63)

- 원자 발행의 중심 통합 시험.
  [`FirstSnapshotTest.java:1703`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L1703)

- 발행 중 변경의 이유 코드별 롤백.
  [`FirstSnapshotTest.java:2116`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L2116)

- 두 탭 경합과 portfolio당 활성 계획 하나.
  [`FirstSnapshotTest.java:1990`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L1990)

- 삭제 경합과 다른 사용자 보존.
  [`FirstSnapshotTest.java:2398`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L2398)

- 주입 origin에서 공식·추정 발행과 결과 화면.
  [`first-snapshot.spec.ts:145`](../../frontend/e2e/first-snapshot.spec.ts#L145)
