---
title: '활성 계획의 사용자 시점 증분 동기화와 공식 결과 갱신'
type: 'feature'
created: '2026-09-29'
status: 'done'
baseline_commit: 'b78b9f8ed24009d84c94c95c2ef6c9288ffe0c0a'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-7-first-plan-publication.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-10-cashflow-adjustment-history.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.11 범위와 ERD-1.0.0 §2.2·SYNC-UX-1.0·AD-23 계약">

## Intent

**Problem:** 활성 계획의 공식 결과는 최초 발행 한 묶음에 멈춰 있다. 발행 15분 뒤부터 대시보드는 늘 `최신 아님`이고, 다음 행동도 `NONE`이다. 사용자는 최신 Bybit 값으로 계획을 다시 비교할 방법이 없다.

**Approach:** 대시보드 진입·탭 재활성화·수동 조작이 증분 동기화 작업 하나를 접수한다(`POST /api/dashboard/sync`). worker는 lease 하나로 2.4 수집·2.5 판정·2.6 환산 저장소와 2.7 순수 계산을 차례로 돌린다. 모두 성공하면 발행 트랜잭션 하나에서 새 source/portfolio snapshot과 새 계산 버전을 만들고, 두 공식 포인터를 함께 전진시킨다. 실패하면 마지막 정상 결과를 그대로 두고 원인과 다음 행동을 보인다. 계산 근거(2.9)와 조정 이력(2.10)은 둘째 이후의 공식 버전도 읽는다.

## Boundaries & Constraints

**Always:**
- **트리거:** `DASHBOARD_ENTRY`·`TAB_REACTIVATION`·`MANUAL` 셋이다. 작업에 기록한다.
- **admission** (DB 시계 `statement_timestamp()`, 외부 호출 없음):
  - 판정 순서:
    1. 계정 접근. 삭제 중이면 403.
    2. 운영 제한(`plan-sync`, 그리고 이 흐름이 쓰는 `first-snapshot`·`initial-source-event-collection`·`plan-publication`).
    3. 활성 계획과 그 공식 포인터.
    4. 현재 연결·활성 membership이 공식 계산의 것과 같다.
    5. `now < T`.
    6. 진행 중 작업(`QUEUED`·`RUNNING`)이 있으면 그 참조를 돌려준다.
    7. 장기 공백: `now − cashflow_verified_through > 30일`이면 작업을 만들지 않는다. 정확히 30일은 접수한다.
    8. 트리거 시각 규칙.
    9. 접수(202).
  - 트리거 시각 규칙:
    - 진입·재활성화는 마지막 admission 후 5분이 지나야 접수한다. admission이 아직 없으면 현재 포인터의 발행 시각부터 잰다.
    - 재활성화는 탭이 5분 이상 숨었다가 보일 때만 클라이언트가 보낸다.
    - 수동은 마지막 admission 후 30초 cooldown이다. 트리거와 결과를 가리지 않는다. cooldown 중이면 기존 참조와 남은 초를 돌려준다.
  - 활성 계획당 진행 작업은 하나다. 조건부 유일 색인이 권위다. 경합에서 진 요청은 이긴 작업의 참조를 받는다.
- **worker:**
  - lease·heartbeat·fencing을 쓴다. 단계는 기준시각 → 수집 → 판정 → 환산 → 계산 → 발행이고, 도달한 단계를 기록한다.
  - 외부 호출은 모두 트랜잭션 밖이다. 쓰기는 모두 짧은 트랜잭션이며, 쓰기 전에 lease·fence와 자격(삭제·제한·계획·연결·membership)을 다시 확인한다.
  - 2.4~2.6의 저장소와 정책을 그대로 쓴다. 단계 행은 계획의 intent를 계보 키로 갖고 이 작업에 속한다. 기존 단계 worker는 이 행을 claim하지 않는다.
  - lease를 잃은 작업은 이어서 하지 않는다. `SYNC_INTERRUPTED`로 끝나고 자동 재시도는 없다.
  - 프로세스당 동시에 도는 동기화 작업은 2개까지다.
- **기준시각과 구간** (ERD §2.2):
  - claim 뒤 Bybit market time으로 `tc`를 한 번 고정한다. 실패하면 계정 API를 부르지 않는다.
  - `tc`에 따른 종료:
    - `tc ≤ cashflow_verified_through`: 실패.
    - `tc ≥ T`: `PLAN_PERIOD_ENDED`.
    - `tc − cashflow_verified_through > 30일`: `LONG_GAP_CHOICE_REQUIRED`. 계정 API를 부르지 않는다.
  - 모드: `tc − last_extended_verification_at ≥ 7일`이면 `EXTENDED`, 아니면 `NORMAL`.
  - 시작점: `max(t0, cashflow_verified_through − 24시간)`, `EXTENDED`는 `− 7일`.
  - `(시작점, tc]`를 최대 7일의 연속 창으로 나눈다. 창마다 `TRANSFER_IN`·`TRANSFER_OUT`의 모든 cursor를 읽는다. 공유 경계의 중복은 Bybit `id`로 제거한다.
  - 창 checkpoint는 그 수집의 page evidence다.
  - 최초 계산의 `last_extended_verification_at`은 그 `tc`다. 최초 수집이 `t0`부터 전부 검증했기 때문이다.
- **watermark:**
  - `cashflow_verified_through`와 `last_extended_verification_at`은 공식 계산 버전이 소유한다. 전진은 새 버전의 발행으로만 일어난다.
  - `EXTENDED`가 성공하면 `last_extended_verification_at = tc`다. `NORMAL`은 이전 값을 잇는다.
  - 다음 중 하나라도 있으면 발행도 전진도 없다. 입력 신뢰도는 `검토 필요`다.
    - 수집 검토(페이지·창 누락, 미인식·형식 오류, `PROVIDER_ID_CONFLICT`).
    - 판정 검토, 환산 필요, 환산 검토.
    - 겹친 구간의 기존 사건 변경(아래).
- **계산 입력:**
  - 흐름은 두 몫의 합이다.
    - 이전 공식 버전의 흐름 가운데 사건 시각이 시작점 이하인 것. 원래 환산을 그대로 인용한다.
    - 새 환산의 계산 가능 흐름 `(시작점, tc]`.
  - 이전 버전이 반영하거나 제외한 사건 가운데 `(시작점, 이전 tc]`에 있는 것은 새 수집·판정·환산에서 다시 확인돼야 한다. 판정·자산·수량·시각·USD·성격이 같아야 한다. 다르거나 사라지면 `APPLIED_FLOW_CHANGED` 검토다. 정정은 2.12가 한다.
  - 겹친 구간에 새로 나타난 사건은 포함한다.
  - `P0`·`t0`·`T`·주기·목표율은 계획 행 그대로다. `E`는 새 wallet `totalEquity`이고 기준은 `tc`다.
  - CALC-1.0 적합성 검사가 실패하거나 범위를 넘으면 발행하지 않는다.
- **발행** (outer `REQUIRED` 하나, AD-23):
  - 순서:
    1. 계획 잠금 → 계정 잠금 → 제한 → lease.
    2. `now < T`.
    3. 활성 계획과 포인터 버전이 admission의 기준과 같다. 연결도 같다.
    4. `tc` source snapshot(새 후보).
    5. portfolio `publishNext` CAS. 기준 계산의 snapshot·sequence를 기대한다.
    6. 계산 버전(모드·시작점·`last_extended_verification_at`·이전 계산), 지표, 흐름, 제외 사건.
    7. 계획 포인터의 expected-version CAS와 이력.
    8. 마지막 제한·접근 확인.
    9. 작업 `PUBLISHED`.
  - 하나라도 실패하면 전부 롤백한다.
- **포인터 이력:** 공식 버전마다 버전·계산·공식 시작 시각·끝 시각을 남긴다. 최초 발행도 이력을 쓴다.
- **대시보드 신뢰 축** (서버 판정, 기존 값 불변):
  - 동기화 작업이 진행 중이면 `동기화 중`.
  - `최신 아님`의 원인: 동기화 제한, 계획 기간 종료, 장기 공백, 현재 버전 발행 뒤 마지막 시도의 실패·검토·차단, 15분 초과.
  - 다음 행동은 `SYNC_NOW`·`REVIEW_LONG_GAP`·`WAIT_FOR_SYNC`·`NONE` 중 하나다.
  - 마지막 시도가 검토 필요로 끝났으면 입력 신뢰도는 `검토 필요`다.
- **계산 근거·조정 이력:**
  - 새 버전에도 2.9·2.10의 참조·404·503 계약이 그대로다.
  - 계보는 세 가지를 판정한다.
    - 이 버전의 최신 계보(후보·판정·환산).
    - 행마다 자기 환산 → 판정 → 후보 → 원 사건의 연결.
    - 이어받은 행이 이전 공식 버전의 행과 같다.
  - 근거 응답에 더하는 것: 수집 모드, 수집 시작점, 이어받은 흐름(이전 버전·건수·경계), 공식 버전 이력(현재, 최근의 이전 버전 최대 10개, 총수).
  - 조정 이력의 제외 사건 수는 이 버전의 확인 구간 전체에 대한 것이다.
- **화면** (두 랜딩 화면의 결과 패널, 다음 행동 절):
  - 조작:
    - `수동 동기화` 버튼. primary sync-control 토큰, 44px, `aria-disabled`, 중복 차단, 초점 유지.
    - 누른 뒤 1초 안에 `동기화 중`.
    - 마지막 정상 시각과, 최근 입출금이 거래소 기록에 늦게 보일 수 있다는 안내가 인접한다.
  - 자동 트리거:
    - 진입은 `READY`를 처음 받았을 때 한 번이다.
    - 재활성화는 5분 이상 숨었다가 보일 때다.
  - 진행 확인:
    - 작업이 진행 중일 때만 2초 간격으로 상태를 읽는다. 연속 3번 실패하면 멈춘다.
    - 끝나면 대시보드를 다시 읽는다(묶음 교체).
  - 끝의 표시:
    - 실패: "동기화하지 못했습니다. 기존 정상값은 그대로 유지됩니다." + 원인 + 다음 행동.
    - 검토: 영향 지표와 신뢰 축 안내.
    - 장기 공백: `장기 미동기화·선택 필요`와 두 선택의 차이 설명. 선택 조작은 두지 않는다.
  - 초점:
    - 사용자가 누른 수동 동기화만 끝날 때 초점을 옮긴다. 성공이면 결과 제목, 그 밖에는 오류 요약이다.
    - 자동 트리거는 초점을 옮기지 않는다.
  - 상태 리전은 화면당 하나다. 동기화 문장은 그 안의 네 번째 요소이고, 변화마다 한 번만 말한다.
- **관측** (ID·금액·원문·자격정보 없음):
  - admission 한 줄: `trigger`·`outcome`·`reason`.
  - 종료 한 줄: `trigger`·`mode`·`status`·`stage`·`failure`·지연·창·페이지·흐름 수·정책 버전.
  - 성공률 분자(`PUBLISHED`)와 분모(접수된 작업)를 로그와 표에서 뽑을 수 있다.
- **보안·캐시:**
  - 두 경로 모두 인증이 필요하다. POST는 기존 대시보드 술어로 CSRF·`Origin`을 거친다.
  - 모든 응답은 `no-store`다.
  - 작업 보기에는 금융값, 작업 ID 밖의 ID, 자격정보가 없다.
- **삭제:** `eraseSyncData`가 대상 사용자의 동기화 작업·단계 행·후보·이력·제외 행을 지운다. 다른 사용자의 것은 남는다. 삭제 상태가 늦은 worker보다 우선한다.

**Ask First:**
- 30일·24시간·7일·5분·30초·15분 값 변경.
- CALC·BYBIT-CASHFLOW·BYBIT-PRICE 정책 변경.
- `tc`와 DB 시계의 skew 상한(미룬 결정 73).
- token bucket과 자동 재시도(ERD §2.5).
- 새 내부 화면 경로, 전역 내비게이션.
- 다른 모듈의 새 공개 API.
- 광고 연동.

**Never:**
- 24시간 폴링, 상시·일괄 작업, 사용자 행동과 무관한 동기화.
- 30일 초과의 catch-up 실행, 선택 token, 새 계획 시작(2.12·2.13·2.15).
- 정밀 재검증·정정 반영(2.12), 계획 종료 판정(2.13·2.14), 수동 사건·가격 수정.
- 검증을 건너뛴 빠른 경로, 표시값 기반 계산, 다중 source 합산.
- 부분 결과, 현재가치 `0` 대체, 자동 재시도 루프.
- `REQUIRES_NEW`·비동기·after-commit 발행, `max(created_at)`으로 현재값 추론.
- V1~V10 수정, 다른 모듈 테이블 직접 접근.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| ENTRY | 진입, 마지막 admission 뒤 5분 이상 | 202, 1초 안 `동기화 중`, 끝나면 `pointerVersion+1`로 묶음 교체 | N/A |
| RECENT | 진입·재활성화, 5분 미만 | 200 `RECENTLY_SYNCED`, 작업·외부 호출 0 | N/A |
| COOLDOWN | admission 30초 안의 수동 | 200 `COOLDOWN`, 기존 참조와 남은 초 | 버튼 `aria-disabled` |
| MERGE | 여러 탭, 반복 클릭, 새로고침 | 같은 진행 작업 참조 | 경합은 유일 색인 |
| GAP | 공백이 30일−1ms / 정확히 30일 / 30일+1ms | 앞의 둘은 접수, 마지막은 200 `LONG_GAP`(작업·외부 호출 0, 선택 설명, 마지막 정상 결과) | N/A |
| RANGE | `NORMAL`, 7일 경과 `EXTENDED`, `t0` clamp, 29일 공백 | 시작점 규칙, 최대 7일 연속 창, 경계 중복 제거 | N/A |
| REVIEW | 누락·미인식·충돌·가격 결측·겹친 사건 변경 | 발행·전진 없음, 입력 신뢰도 `검토 필요`, 마지막 정상 값 | `REVIEW_REQUIRED` |
| FAIL | 기준시각·API·timeout·rate limit·계산 실패 | 마지막 정상 값, `최신 아님`, 원인·재시도 가능 여부 | `FAILED` |
| LATE | 오래된 fence, lease 상실, 삭제·제한·연결·membership·포인터 변경 | 아무것도 발행하지 않음 | `BLOCKED`·`SYNC_INTERRUPTED` |
| READS | 둘째 이후 버전의 근거·조정 이력 | 2.9·2.10 계약 그대로 | 이어받은 행 불일치는 503 |

</frozen-after-approval>

## Code Map

**계약**
- `epics.md`
  - 2192-2300: Story 2.11 AC-1~AC-10과 테스트 기준.
  - 305-311: 일반·확장 중첩, 30일 경계, `NORMAL`·`EXTENDED`·`CATCH_UP`의 한 엔진.
  - 2302-2399: Story 2.12의 범위. catch-up·정정·token bucket이 거기 있다.
- `EPIC-IMPLEMENTATION-READINESS.md`
  - 26-33: ERD §2.2 `tc`·창·시작점·30일·2년.
  - 104-111: `SYNC-UX-1.0`의 상태·5분·30초.
  - 55-58: 호출 예산(여기서는 동시 2개만 쓴다).
- `EXPERIENCE.md`: 81(sync-control), 162-174(원자 교체), 184·202·205(중복·재시도 금지), 210-215(44px·status·`aria-busy`·초점·reduced motion).
- `DESIGN.md`: 107-111(sync-control 토큰), 234(primary는 명시적 사용자 행동에만), 262(최근 입출금 안내), 264(진행 중에도 배치 유지).
- `ARCHITECTURE-SPINE.md`: AD-5·AD-7·AD-8(82-100), AD-17(154), AD-23(190).
- `PORTFOLIO-SOLUTION-DESIGN.md`: 60-84(요청 경로·상태기계·fencing), 133(연결당 실행 하나).

**스키마** (읽기만 한다. `backend/src/main/resources/db/migration/`)
- V7(`sync_initial_*`)
  - 수집 작업은 2-32이고, 활성 intent 유일 색인이 30이다.
  - 원 사건 `UNIQUE(provider_account_id,source_id)`가 47이다.
  - page evidence는 82-106, 후보 묶음·사건은 108-130이다.
  - 모든 행이 `intent_id`를 갖지만 FK는 없다. 그래서 계획의 intent를 넣을 수 있다.
- V8: 판정 작업은 2-24(`UNIQUE(user_id,candidate_id,policy_version)`), 묶음은 26-43, 사건은 47-69다.
- V9: 환산 작업은 66-121(lifecycle CHECK 101-110), 묶음은 143-188, 흐름은 200-232, 계산 가능 흐름은 262-286이다.
- V10
  - 계산 버전 167-221.
    - `created_by_job_id`에는 FK가 없다.
    - `cashflow_verified_through=basis_at` CHECK는 유지한다.
    - `candidate_id`·`classification_id`·`conversion_id`는 이 버전의 최신 계보를 뜻한다.
  - 흐름 253-272: 행마다 `conversion_id`가 있고 계산 가능 흐름을 FK로 가리킨다.
  - 포인터 279-291: `published_at DEFAULT`는 INSERT에서만 적용된다.
  - source snapshot의 후보 출처 73-80: 증분 후보도 같은 표이므로 그대로 쓴다.

**백엔드 코드** (`backend/src/main/java/com/bokrilog/`)
- `sync/internal/SyncJobService.java`
  - 단계 래퍼는 238-449다. 모두 `plans.eligibleIntent`(OPEN)를 요구하므로 증분은 이 래퍼를 쓰지 않고 저장소를 직접 부른다.
  - `eraseSyncData`는 229-237, 제한 명령 이름은 27-31이다.
- `sync/internal/InitialCollectionRepository.java`
  - `Lease`(34)의 `t0`는 구간 시작으로만 쓰인다. 쓰는 곳: `parse` 창 304, `validCoverage` 232-246, `storeCandidate` 265-294.
  - `claim`(76-87)에 증분 행 제외 조건을 더한다.
  - `storePage`(123)·`complete`(216)는 lease 행의 fence를 스스로 확인한다.
- `sync/internal/CashflowClassificationRepository.java`
  - `inputHash`(119)·`inputs`(160)·`publish`(173-237)를 lease 하나로 그대로 쓴다.
  - `claim`(103)에 제외 조건을 더한다.
- `sync/internal/CashflowConversionRepository.java`
  - `admit`(167-202)은 intent의 최신 판정을 고른다. 특정 판정을 고정하는 변형이 필요하다.
  - `inputsStillValid`(135)도 "최신"을 요구한다.
  - `publish`(390-409)와 `insertBundle`(411-500)은 그대로 쓴다.
  - `claim`(216)에 제외 조건을 더한다.
- `sync/internal/CashflowConversionMarket.java` -- `convert(flows, reference, active)`를 그대로 쓴다. 참조 시각을 고정하는 모양은 `SyncJobService.fixReference`(350-361)를 따른다.
- `source/SourceConnectionApi.java:60` `collectInitialInputs(user, connection, t0, fixedTc, …)`
  - `t0`는 구간 시작이다. `fixedTc`를 주면 시간 API를 부르지 않는다(`BybitAdapter.java:345-360`).
  - 호출 순서는 query-api(권한·UID 재검증) → account/info → wallet → 창별 page(386-453)다.
  - `marketTime`은 102다. `SourceRejectionReason`은 `source/SourceRejectionReason.java:19-66`이다.
- `sync/internal/PlanPublicationService.java`
  - `publishInTransaction`(297-351): 잠금 순서, 두 CAS, 마지막 확인의 본보기다.
  - `calculationInput`(475)·`notation`(486)은 재사용한다.
  - `bundle`(577-615)
    - AD-23 확인(588-597)은 두 포인터가 함께 전진하므로 유지한다. 주석만 갱신한다.
    - `freshness`·`DashboardTrust.judge` 입력을 넓힌다.
  - `lineage`(665-782)를 두 갈래로 가른다.
    - 갈라지는 곳: `creatingAttempt`(686), 후보 `t0`(696), `collectionRange`(719), `appliedFlowCounts`의 `foreign`(742-745), 공식 버전 목록(776-780).
    - `calculatedFromPinned`(796-818)는 최초 발행 전용으로 남긴다.
  - `restricted`(879-885)는 발행의 제한 판정이다.
- `sync/internal/PlanPublicationRepository.java`
  - `flows`(104), `insertCandidateSource`(279), `insertCalculation`(293), `insertFlow`(333).
  - `pointFirst`(349)는 이력도 써야 한다.
  - `pointer`(376), `calculation`(390), `freshness`(420-431: 진행 작업 EXISTS 목록), `lineage`(448), `collectionRange`(493), `appliedFlowCounts`(532), `erase`(562-568).
- `sync/internal/DashboardTrust.java`
  - 코드는 31-44, `Freshness`는 58, `Input`은 61, `judge`는 69, `nextAction`은 75다.
  - 신선도는 103-112, 신뢰도는 118-126다. 26-28과 40-44의 주석이 이 스토리를 가리킨다.
- `sync/internal/AdjustmentHistoryService.java`
  - `page`(122-199)의 단일 계보 가정: AD-23 134-137, 판정 수 140-152, 환산 수 153-164, 사슬 175-180·223-225, 행 참조 261-262.
- `sync/internal/AdjustmentHistoryRepository.java`
  - `totals`(92-126)와 `ROW_HOLDS`(129-164)는 머리 행의 `conversion_id`·`classification_id`·`candidate_id`로 조인한다. 행의 것으로 바꾼다.
  - `page`(207-229)와 `legs`(232)도 같다.
- `sync/internal/AdjustmentCursor.java:29-35` -- digest에 `pointerVersion`이 있다. "사건마다 환산 하나"는 PK로 여전히 참이다. javadoc만 갱신한다.
- `sync/SyncJobApi.java`
  - 근거 모델은 222-286(`SourceRange` 252, `OfficialVersion` 285)이다.
  - 이력 `Revision`은 398-406이다. "공식에 쓰이지 않은 과거 버전"은 계속 참이고, javadoc의 근거만 바꾼다.
- `sync/PlanPublicationPort.java` -- `activePlan`·`lockPlanWrites`로 충분하다. 새 port 메서드는 없다.
- `portfolio/PortfolioMembershipApi.java` -- `eligibleMembership` 64, `publishNext` 82, `officialValue` 84, `activeSource` 90.
- `sync/internal/FirstSnapshotWorker.java` -- dispatcher·heartbeat·`renew`(46)의 본보기다.
- `DashboardController.java`
  - 대시보드 120-172, 근거 214, 이력 369.
  - 새 핸들러 두 개를 같은 관용으로 둔다(`no-store`, 한 줄 로그).
- `AuthSecurityConfig.java` -- `isProtectedRequest`(1177-1197), `requiresAuthentication`(1210-1213), `isDashboardPath`(1231). `/api/dashboard/sync`는 이미 두 술어에 걸린다. 225-241의 "핸들러가 있는 하위 경로" 주석만 갱신한다.
- `DevBybitStub.java`
  - 시나리오는 150-280이다.
  - 사건 시각이 요청 `startTime`에 상대적이다(451-468). 겹치는 재조회가 `PROVIDER_ID_CONFLICT`가 되므로 절대 시각 시나리오가 필요하다.
  - `uta2`(흐름 0건)는 겹쳐도 안전하다.

**백엔드 시험**
- `FirstSnapshotTest.java`(4779줄)
  - 발행 도우미: `convertedIntent` 4527, `publish` 4594, `awaitPublication` 4600, `holdPlanLock` 4662.
  - 근거·이력 도우미: `referenceOf`·`evidenceOf`·`rawGet`, `Corruption`(`session_replication_role=replica`).
  - 새 시험은 이 파일이 아니라 새 클래스 `PlanSyncTest`에 둔다.
- `BybitStubConfiguration.java` -- `requestCount(path)`(101)·`serverTimeMode`(124)·`lastWalletEnvelopeTime`(121).
- 단위 시험: `DashboardTrustTest`, `AdjustmentHistoryServiceTest`, `PlanPublicationServiceTest`, `FirstSnapshotWorkerHeartbeatTest`.
- `ModuleBoundaryTest.java` -- 경계는 바뀌지 않는다.

**프런트엔드** (`frontend/src/`)
- `session/useDashboard.ts`
  - `NEXT_ACTIONS`(206-210)에 코드를 더한다.
  - `supersedes`(240)는 버전 전진을 이미 받는다.
  - `reload`(366)는 읽기 중이면 무시된다. `dashboardAnnouncement`는 383-409다.
- `components/PlanResultPanel.tsx`
  - 제목은 408(`tabIndex=-1`), 초점 되돌림은 385-391이다.
  - 다음 행동 절(440-460)에 동기화 조작을 둔다. 신뢰 축은 205-258이다.
- `content/planResult.ts` -- 신선도 원인 155-174, 신뢰도 175-187, `nextActionCopy` 243-259(236-239 주석), `axisActionCopy` 266-275.
- `session/useFirstSnapshot.ts:49-125` -- `csrfToken`과 `CSRF_HEADER` POST, 1초 `setTimeout` 사슬의 본보기다.
- `session/useCalculationEvidence.ts`
  - `parseEvidence`가 거절하는 것: `range`가 정확히 `(t0,tc]`가 아님(223·229-230), `classification`·`conversion`이 하나가 아님(233-234).
  - 공식 버전은 여럿을 받고 current는 하나다(268-277). 키에 `pointerVersion`이 있다(326-352).
- `session/useAdjustmentHistory.ts` -- 행 `calculationId`(183), `appliedAt`·`coverage`(215-218), 페이지 대조(240-254), 키(333).
- `session/useSessionState.ts:462-475` -- `visibilitychange` 관용구다.
- `components/DashboardStatusText.tsx:19-49` -- 네 번째 요소를 더한다.
  - 연결 지점은 `screens/SignedInScreen.tsx`(h1 107, status 118-148, 패널 160-165)와 `screens/OnboardingScreen.tsx`(h1 100, 111-158)다.
- `styles/tokens.css:117-120` -- `--component-sync-control-*`.
- 시험
  - `test/setup.ts`: `stubDashboard` 90-92, 근거·이력 stub 110·121. 새 stub이 필요하다.
  - `test/planResultFixture.ts`: 14-131·183-289.
  - `e2e/first-snapshot.spec.ts`
    - `connectAndSavePlan` 37, `publishAndOpenResult` 151.
    - `expectKeyboardOrder`(294)는 탭 정지점 여섯을 정확히 단정한다. 새 버튼을 더해야 한다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V11__incremental_plan_sync.sql`
  - `sync_plan_sync_job`
    - 사용자·계획·portfolio·intent·membership·연결·provider account.
    - trigger, 상태(`QUEUED`·`RUNNING`·`PUBLISHED`·`REVIEW_REQUIRED`·`FAILED`·`BLOCKED`), 도달 단계, 닫힌 `failure_code`.
    - 기준 계산·포인터 버전·확인 시각 둘, `mode`·`tc`·시작점.
    - 단계 행·후보·판정·환산·계산 ID, 발행 포인터 버전.
    - 창·페이지·흐름 수, 정책 버전.
    - fence·lease·heartbeat·시각.
    - lifecycle CHECK, 활성 계획당 진행 하나의 유일 색인, 큐 색인.
  - 세 단계 작업 표에 `plan_sync_job_id`를 더한다(CASCADE와 색인).
  - `sync_plan_calculation`에 더하는 열: `collection_mode`, `collection_range_start`, `last_extended_verification_at`, `previous_calculation_id`.
    - 기존 행은 `INITIAL`·`t0`·`basis_at`으로 채운 뒤 NOT NULL로 둔다.
    - 모드별 CHECK를 둔다.
    - 같은 계획의 자기 참조 FK는 NO ACTION이다. 한 문장 삭제가 되게 하려는 것이다.
  - `sync_plan_calculation_exclusion`
    - 버전이 확인 구간에서 제외한 사건이고, 판정 사건을 FK로 가리킨다.
    - 기존 버전은 그 판정의 `EXCLUDED`로 채운다.
  - `sync_official_plan_result_history`
    - PK `(plan_id, pointer_version)`, `calculation_id UNIQUE`, `published_at`, `superseded_at`.
    - 기존 포인터로 채운다.
- [x] `sync/internal/InitialCollectionRepository.java`, `CashflowClassificationRepository.java`, `CashflowConversionRepository.java`
  - `claim`에서 `plan_sync_job_id IS NOT NULL` 행을 뺀다.
  - 증분 단계 행을 `RUNNING`(fence 1, lease는 작업의 것)으로 넣는 메서드를 둔다.
  - 환산에는 판정 하나를 고정하는 접수·유효성 변형을 둔다.
  - 기존 메서드의 동작은 바꾸지 않는다.
- [x] 새 `sync/internal/PlanSyncRepository.java`
  - 작업 행: 접수, claim, 만료 lease 정리(`SYNC_INTERRUPTED`, 단계 행 `CANCELLED`), heartbeat(단계 행 lease 함께), 단계 기록, fenced 종료.
  - 포인터: expected-version CAS와 이력.
  - 제외 행, 신뢰 판정용 사실(마지막 시도, 장기 공백, 기간 종료), 삭제.
- [x] 새 `sync/internal/PlanSyncService.java`
  - admission과 상태 보기. 판정 순서·시각 규칙·경합 수렴을 여기서 한다.
  - claim·run
    - 기준시각, 수집(`collectInitialInputs(… 시작점, tc …)`), 판정, 환산, 흐름 조립, 겹친 사건 대조.
    - 계산은 트랜잭션 밖에서 한다.
  - 발행 트랜잭션.
  - `SourceRejectionReason`·환산 결과 → 상태·`failure_code`의 닫힌 표. 재시도 가능 여부도 이 표에서 나온다.
  - 로그 두 줄.
- [x] `sync/internal/FirstSnapshotWorker.java` -- 동기화 dispatcher(진행 2개 상한)와 heartbeat 갱신을 더한다.
- [x] `sync/internal/PlanPublicationRepository.java`, `PlanPublicationService.java`, `DashboardTrust.java`
  - `pointFirst`가 이력 v1을 쓴다.
  - 신선도 EXISTS에 새 작업 표를 더한다.
  - 신뢰 입력에 제한·기간 종료·장기 공백·마지막 시도를 더한다.
  - 새 원인·다음 행동 코드를 더한다.
  - 근거 계보를 `INITIAL`과 증분의 두 갈래로 나눈다.
  - 공식 버전 목록은 이력에서 읽는다.
  - `erase`에 새 표를 더한다.
- [x] `sync/internal/AdjustmentHistoryService.java`, `AdjustmentHistoryRepository.java`, `AdjustmentCursor.java`
  - 행마다 자기 환산 계보로 조인한다.
  - 최신 계보 수와 이어받은 행이 같은지 본다.
  - 제외 수는 제외 행에서 센다.
  - 환산 사슬은 행의 환산별로 걷는다.
- [x] `sync/SyncJobApi.java`, `SyncJobService.java`
  - 동기화 접수·상태 모델과 결과 sealed 타입.
  - 근거 응답의 `collection` 블록과 버전 이력 필드.
  - `eraseSyncData` 순서: 발행 → 동기화 작업 → 기존 순서.
- [x] `DashboardController.java`, `AuthSecurityConfig.java`(주석)
  - `POST /api/dashboard/sync`
    - 본문 `{trigger}`.
    - 202(접수) / 200(`IN_PROGRESS`·`RECENTLY_SYNCED`·`COOLDOWN`·`LONG_GAP`·`NOT_AVAILABLE`+reason) / 400(trigger 형식) / 403.
  - `GET /api/dashboard/sync`: 200 `{job?, manual:{available, reason?, retryAfterSeconds?}}` / 403.
  - 모두 `no-store`다.
- [x] `DevBybitStub.java` -- 절대 시각 사건 시나리오를 더한다. 창 안의 것만 내고, 같은 `id`는 같은 내용이다. 시험이 사건과 시각을 정할 수 있어야 한다.
- [x] 새 `backend/src/test/java/com/bokrilog/PlanSyncTest.java`와 단위 시험
  - I/O matrix의 모든 행.
  - 경계: 30일 ±1ms, 7일 ±1ms, 5분, 30초.
  - 창: 수와 경계, `t0` clamp, 29일, 0건, 경계 사건.
  - 단계별 실패 주입 뒤 포인터·watermark 불변.
  - 성공 시 두 포인터·이력·계획 입력 불변, `NORMAL`·`EXTENDED`의 watermark.
  - v2 근거·이력: 200, 이어받은 행 손상은 503.
  - 늦은 worker·삭제 경합, 로그, 응답 금지 필드, 외부 호출 수(`requestCount`).
- [x] `frontend/src/content/planSync.ts`(새), `content/planResult.ts`, `session/useDashboard.ts`
  - 경로, 카피, 닫힌 코드 맵(모르는 코드는 안전 카피).
  - 새 신선도·신뢰도 원인과 다음 행동 `SYNC_NOW`·`REVIEW_LONG_GAP`.
  - 조용한 다시 읽기: `reloading` 문장 없이 읽는다.
- [x] 새 `frontend/src/session/useDashboardSync.ts`
  - 트리거 셋(진입은 계획당 한 번, `visibilitychange`의 hidden 시간은 `performance.now`).
  - POST(CSRF), 진행 중 2초 확인(중단·순번·연속 실패 3회 상한).
  - 접수 뒤 조용한 다시 읽기, 끝난 뒤 다시 읽기.
  - 수동 조작에만 초점 요청, 401·403은 멈춤.
- [x] 새 `frontend/src/components/SyncControl.tsx`(+`.module.css`), `PlanResultPanel.tsx`, `DashboardStatusText.tsx`, `SignedInScreen.tsx`, `OnboardingScreen.tsx`
  - 버튼, 진행·cooldown·불가 이유, 마지막 정상 시각, 최근 입출금 안내.
  - 오류 요약(`tabIndex=-1`), 장기 공백 설명, 상태 네 번째 요소.
- [x] `session/useCalculationEvidence.ts`, `components/CalculationEvidence.tsx`, `session/useAdjustmentHistory.ts`(필요한 만큼)
  - 증분 버전의 수집 시작점·이어받은 흐름·버전 이력을 받고 보인다.
  - `INITIAL`은 기존 검사를 그대로 둔다.
- [x] 프런트엔드 시험 (`useDashboardSync.test.tsx`, `SyncControl.test.tsx`, 기존 패널·화면·근거 시험, `setup.ts`, fixture)
  - Vitest
    - 1초 안 `동기화 중`, 중복 차단, cooldown.
    - 재활성화 5분 경계(가짜 타이머), 진입 한 번.
    - 성공 교체와 초점, 실패·검토·장기 공백 문장과 오류 요약 초점.
    - 자동 트리거 초점 불변, 확인 상한, 저장소 0, 금지어.
  - e2e(`uta2`)
    - 발행 → 대시보드(진입은 `RECENTLY_SYNCED`) → 키보드로 `수동 동기화` → `동기화 중` → 새 버전과 제목 초점 → 근거의 버전 이력.
    - `expectKeyboardOrder` 갱신, 320px, axe.

**Acceptance Criteria:**
- Given 공식 결과가 있는 활성 계획, when 사용자가 `수동 동기화`를 누르면, then 1초 안에 `동기화 중`이 보이고 버튼은 다시 누를 수 없다. 끝나면 현재가치·여섯 지표·세 신뢰 축이 새 계산 버전 하나로 함께 바뀌고 초점은 결과 제목으로 간다. 계획 조건은 그대로다.
- Given 증분 동기화가 어느 단계에서든 실패·검토·차단으로 끝나면, when 대시보드를 다시 읽으면, then 공식 포인터·watermark·현재가치는 이전과 같다. 신선도는 원인과 함께 `최신 아님`이다. 새 source/portfolio snapshot·계산 버전은 공식이 되지 않았다.
- Given 발행 트랜잭션의 어느 쓰기(snapshot·portfolio CAS·계산·흐름·제외·포인터 CAS·이력)에서 실패를 주입하면, when 커밋하려 하면, then 새 행은 하나도 남지 않는다.
- Given 둘째 공식 버전, when 계산 근거와 조정 이력을 열면, then 2.9·2.10의 계약대로 200이다. 근거는 수집 모드·시작점·이어받은 흐름·버전 이력을 보이고, 조정 이력은 `t0`부터의 모든 반영 흐름을 순번대로 보인다.
- Given 대상 사용자의 삭제가 진행 중인 동기화와 경합하면, when 삭제 port를 순서대로 부르면, then 대상의 작업·단계 행·후보·계산·이력이 모두 사라지고 다른 사용자의 것은 남는다. 늦은 worker는 아무것도 다시 만들지 못한다.

## Spec Change Log

## Design Notes

**단계 행을 작업의 자식으로 둔다.**
- 2.4~2.6의 단계 표와 저장소는 page evidence·후보·판정·환산 묶음을 자기 작업 행의 fence로 쓴다. 증분 작업이 각 단계의 행을 자식으로 직접 `RUNNING`으로 만들면, 그 저장소 메서드를 고치지 않고 그대로 쓸 수 있다.
- 자식의 lease는 부모의 heartbeat가 함께 늘린다. 쓰기 트랜잭션마다 부모 행을 먼저 잠그고 fence를 확인한다. 그래서 fence는 사실상 하나다.
- 기존 단계 dispatcher는 자식을 claim하지 않는다. 부모가 끝나거나 lease를 잃으면 남은 자식은 `CANCELLED`가 된다. 신선도의 "진행 중" 판정이 영원히 켜져 있지 않게 하기 위해서다.
- 단계 행의 `intent_id`는 계획의 intent다. `plan_definition.intent_id`가 UNIQUE이고 V7~V9의 `intent_id`에 FK가 없어서 스키마를 바꿀 필요가 없다. 반면 OPEN intent를 요구하는 기존 HTTP 단계 경로는 이 계보를 받지 않는다.

**watermark는 계산 버전의 열이다.**
- AC-5의 전진 조건과 발행 조건이 같다. 두 확인 시각을 공식 버전에 두면 포인터 CAS 하나가 둘을 함께 지킨다. 따로 watermark 표를 두면 둘째 CAS와 그 경합이 생긴다.
- 최초 버전의 `last_extended_verification_at`은 그 `tc`다. 첫 확장 검증은 최초 발행 7일 뒤의 첫 동기화다.

**흐름은 나눠 인용한다.**
- 새 버전은 시작점 이하의 흐름을 이전 버전의 행(원래 환산 인용)으로 잇는다. 새 판정·환산은 `(시작점, tc]`만 덮는다.
- 증분 후보에는 그 수집이 실제로 관측한 사건만 들어간다. 이전 사건에 이 작업의 provenance를 지어 붙이지 않는다.
- 그래서 조정 이력은 행마다 자기 환산 → 판정 → 후보로 조인한다. 최신 계보의 수 검사는 시작점 뒤의 행에만 건다. 시작점 이하의 행은 이전 공식 버전의 같은 행과 글자까지 같아야 한다.
- 이전 버전 자체의 묶음 수준 완전성은 그 발행 때 판정됐으므로 읽기마다 재귀하지 않는다. 행 수준 계보는 모든 행에 여전히 건다.
- 제외 사건은 행이 아니다. 그래서 흐름처럼 버전마다 표에 남긴다. 버전마다 반영 흐름을 복사하는 V10의 방식과 같다.

**겹친 구간의 기존 사건이 달라지면 멈춘다.** 같은 `id`의 내용 변화는 이미 `PROVIDER_ID_CONFLICT`다. 사건이 사라지면 후보 완전성이 깨진다. 남는 경우는 같은 사건의 판정·USD가 달라지는 것뿐이다. 그 정정과 영향 기록은 2.12의 AC-3·AC-4이므로, 여기서는 발행하지 않고 검토로 둔다. 겹친 구간에 늦게 나타난 새 사건은 중첩을 두는 이유 그 자체이므로 포함한다.

**장기 공백은 설명만 한다.** `기존 계획 계속`은 선택 token과 `CATCH_UP`이 필요하다(2.12 AC-1). `새 계획 시작`은 계획 종료(2.13)와 후속 계획(2.15)이 필요하다. 없는 조작을 그리지 않는다. 화면은 두 선택의 데이터 범위·결과 차이, 그리고 선택 전에는 Bybit를 부르지 않는다는 사실을 말한다. 마지막 정상 결과는 읽기 전용이다.

**admission의 장기 공백은 DB 시계로 잰다.** AC-1은 "30일 초과면 Bybit를 부르지 않는다"고 한다. 그래서 admission은 외부 호출 없이 DB 시계로 판정한다. worker는 고정한 `tc`로 다시 판정한 뒤에야 계정 API를 부른다. 두 시계의 차이는 미룬 결정 73의 몫이다.

**초점은 사용자가 시작한 동기화만 옮긴다.** EXPERIENCE 213은 조작한 버튼에 초점을 둔다. AC-9는 끝날 때 제목 또는 오류 요약으로 옮긴다. 둘을 이렇게 맞춘다: 진행 중에는 버튼에 머물고, 끝나면 옮긴다. 진입·재활성화는 사용자가 조작하지 않았으므로 옮기지 않고 상태 문장으로만 알린다.

**상태 리전의 문장 수.** 접수 뒤의 대시보드 다시 읽기는 조용히 한다. 대시보드 문장이 `다시 불러오는 중`으로 흔들리지 않게 하려는 것이다. 동기화 문장은 접수·끝을 한 번씩 말하고, 대시보드 문장은 묶음이 바뀔 때만 바뀐다.

**광고:** 광고 trigger는 수동 동기화 성공 뒤의 전환 화면이다(Story 4.2, 선행이 2.11). 이 스토리에는 광고 코드가 없으므로 AC-1의 "자동 트리거 광고 부재"는 호출 0건으로 성립한다.

**닫힌 코드** (재시도 가능 여부는 서버가 이 표에서 정한다):

| 종류 | 코드 |
|---|---|
| admission `outcome` | `ADMITTED`·`IN_PROGRESS`·`RECENTLY_SYNCED`·`COOLDOWN`·`LONG_GAP`·`NOT_AVAILABLE` |
| `NOT_AVAILABLE`·`manual.reason` | `NO_ACTIVE_PLAN`·`SYNC_RESTRICTED`·`SOURCE_CHANGED`·`PLAN_PERIOD_ENDED`, 그리고 `manual`만 `IN_PROGRESS`·`COOLDOWN`·`LONG_GAP` |
| `BLOCKED` | `NOT_PERMITTED`·`PLAN_CHANGED`·`MEMBERSHIP_CHANGED`·`SOURCE_CHANGED`·`POINTER_CONFLICT`(재시도 가능)·`LONG_GAP_CHOICE_REQUIRED`·`PLAN_PERIOD_ENDED` |
| `REVIEW_REQUIRED` | `COLLECTION_REVIEW_REQUIRED`·`CLASSIFICATION_REVIEW_REQUIRED`·`CONVERSION_REQUIRED`·`CONVERSION_REVIEW_REQUIRED`·`APPLIED_FLOW_CHANGED` |
| `FAILED` 재시도 가능 | `REFERENCE_TIME_UNAVAILABLE`·`REFERENCE_TIME_INVALID`·`SOURCE_UNAVAILABLE`·`CONVERSION_PENDING`·`SYNC_INTERRUPTED` |
| `FAILED` 재시도 불가 | `SOURCE_REJECTED`·`CALCULATION_RANGE_EXCEEDED`·`CALCULATION_INPUT_INVALID`·`CALCULATION_POLICY_UNVERIFIED` |
| 신선도 원인(추가) | `SYNC_RESTRICTED`·`PLAN_PERIOD_ENDED`·`LONG_GAP_CHOICE_REQUIRED`·`LAST_SYNC_FAILED`·`LAST_SYNC_REVIEW_REQUIRED`·`LAST_SYNC_BLOCKED` |
| 신뢰도 원인(추가) | `LATEST_INPUTS_REVIEW_REQUIRED` |
| 다음 행동(추가) | `SYNC_NOW`·`REVIEW_LONG_GAP` |
| admission 로그 `outcome`(로그 전용) | 위 여섯에 더해 `NOT_PERMITTED` — 계정 자체의 거절(403). 응답에는 `outcome`이 없다 |

- 두 source 코드의 뜻은 하나씩이다.
  - `SOURCE_UNAVAILABLE`은 "Bybit가 제때 답하지 않았다"뿐이다(재시도 가능).
  - `SOURCE_CHANGED`는 "연결이 공식 계산의 것과 다르다"이다(재시도 불가). admission에서는 연결·membership이 공식 버전과 다를 때, worker에서는 실행 중 연결·provider account·연결 정책 버전이 바뀌었을 때(`BLOCKED`), 그리고 수집 결과의 정책 버전이 현재 연결과 다를 때다.
  - 수집이 답한 `tc`가 고정한 `tc`와 다르면 실행이 한 묶음이 아니므로 `SYNC_INTERRUPTED`다.
- source 거절의 대응:
  - 어댑터 자신의 형식 검사(`RESPONSE_FORMAT`: 목록이 목록이 아님, 반복 cursor 등)는 수집 검토다.
  - 전송 거절 `TIMEOUT`·`RATE_LIMITED`·`PROVIDER_UNAVAILABLE`(5xx·연결 실패)과 `WALLET_UNAVAILABLE`, 구성 부재는 `SOURCE_UNAVAILABLE`이다.
  - 200인데 파싱할 수 없는 본문(JSON 아님)은 형식 거절로 올리지 않고 호출 자리의 기본값을 따른다 — 시각·Transaction Log는 `SOURCE_UNAVAILABLE`, 키·계정 조회는 `SOURCE_REJECTED`. Story 2.4의 최초 수집 결과(`RETRY_REQUIRED`)가 그대로 유지된다.
  - 인증·권한·IP·하위 계정·계정 모드 거절은 `SOURCE_REJECTED`이다.
  - 수집 단계 행의 `failure_code`에는 부모 작업의 닫힌 코드를 적는다.
- 환산 결과의 대응:
  - 재시도 가능한 `CONVERSION_REQUIRED`(미종료 캔들·가격 제공자 불가)는 `CONVERSION_PENDING`이다.
  - 재시도할 수 없는 가격 결측은 `CONVERSION_REQUIRED` 검토다.
- 신선도 판정의 우선순위: 진행 중 → 제한 → 기간 종료 → 장기 공백 → 마지막 시도 → 15분 규칙.
  - 15분 초과와 재시도 가능한 실패의 다음 행동은 `SYNC_NOW`다.
  - 장기 공백의 다음 행동은 `REVIEW_LONG_GAP`이다.
  - 나머지는 `NONE`이다.
- 마지막 시도는 현재 포인터의 발행 시각 뒤에(같은 시각 포함) 접수된 작업 가운데 가장 최근에 끝난 것이다.

**잃어버린 작업은 진행 중으로 답하지 않는다.**
- lease를 잃은 `RUNNING`, 그리고 5분 넘게 claim되지 않은 `QUEUED`는 `FAILED SYNC_INTERRUPTED`로 끝나고 단계 행은 `CANCELLED`다. 오래된 `QUEUED`는 claim되지 않는다.
- dispatcher는 동시 상한에 걸린 tick에도 이 정리를 먼저 한다. admission은 그 계획의 만료분을 정리한 뒤에 진행 중 작업을 판정한다.
- lease마다 claim 시각을 기록한다(`claimed_at`). claim 뒤 10분이 지나면 heartbeat가 갱신을 멈추므로, 멈춘 작업도 한 lease 안에 자리를 내준다.

**근거와 조정 이력은 같은 머리를 판정한다.** 포인터 이력(현재 버전이 가장 새롭고 포인터의 발행 시각에 공식이 됨), 그리고 증분 버전이면 그것을 발행한 작업·기록된 모드·시작점·확장 시각·직전 공식 버전(그 버전의 끝 = 이 버전의 시작)을 한 판정(`OfficialVersionHead`)이 본다. 그래서 한 버전은 두 읽기 모두에 503이거나 어느 쪽에도 아니다.

**응답 모양** (요지):
```json
POST /api/dashboard/sync  {"trigger":"MANUAL"}
→ 202 {"outcome":"ADMITTED","job":{"jobId":"…","trigger":"MANUAL","status":"QUEUED","stage":"ADMITTED",
        "mode":null,"failureCode":null,"retryable":false,"createdAt":"…","finishedAt":null,"pointerVersion":null},
       "manual":{"available":false,"reason":"IN_PROGRESS"}}
→ 200 {"outcome":"COOLDOWN","job":{…"status":"PUBLISHED","pointerVersion":2},
       "manual":{"available":false,"reason":"COOLDOWN","retryAfterSeconds":18}}
```
- `manual`과 답의 값 없는 필드는 싣지 않는다(`NON_NULL`) — `retryAfterSeconds`는 `COOLDOWN`에만, `reason`은 불가일 때만 있다. `job`의 필드는 값이 없어도 `null`로 싣는다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 build` -- expected: Testcontainers를 포함해 전체 통과, 실패 0.
- Node 24: `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` -- expected: 모두 통과.
- 수동 compose 스택(migrate V1~V11)을 올린 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080`으로 전체 e2e를 `--workers=1`로 돌린다 -- expected: 통과, 스택 정리. Windows의 `verify-same-origin.sh`는 CP949 결함(미룬 항목) 때문에 Linux CI가 판정한다.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check` -- expected: 통과.

**구현에서 확정한 것 (리뷰 반영 후, frozen 블록 밖):**
- 동기화 단계 행은 부모 작업의 lease를 따른다.
  - 단계 행은 `RUNNING`(fence 1)으로 들어가고, heartbeat가 부모와 함께 늘린다.
  - 2.4~2.6의 `claim`은 `plan_sync_job_id IS NULL`인 행만 잡는다.
- 수집 창은 `(시작점, tc]`다. Bybit의 `startTime`은 포함 경계이므로, 시작 ms에 정확히 놓인 사건은 수집이 버린다. 그 사건은 이어받은 쪽(시작점 이하)에만 남는다.
- `BybitAdapter.failureOf`는 전송 계층 거절(timeout·429·5xx)만 호출 자리의 기본값보다 먼저 읽는다.
  - 파싱할 수 없는 본문은 예전처럼 호출 자리의 기본값이다. 그래서 2.4 최초 수집의 결과는 바뀌지 않는다.
  - 실제 Bybit의 200 + `retCode` 거절은 이 스토리가 정하지 않는다(미룬 항목, 실연동 게이트).
- `SOURCE_CHANGED`(연결·membership·연결 정책이 공식 계산과 다름)와 `SOURCE_UNAVAILABLE`(Bybit 무응답)을 나눴다. 수집 단계 행에는 부모 작업의 코드를 적는다.
- 운영 로그는 세 줄이며 ID가 없다.
  - `plan sync admission: trigger= outcome= reason=`: 거부는 로그 전용 `NOT_PERMITTED`.
  - `plan sync finished: …`
  - `plan sync interrupted: stage= failure=<클래스>`
- worker 간격은 속성으로 뺐다: `bokrilog.sync.worker.dispatch-delay-ms`(기본 1000)와 `heartbeat-delay-ms`(기본 5000).
  - `PlanSyncTest`만 50/500ms를 쓰고, 끝날 때 자기 컨텍스트의 예약 작업을 취소한다.
  - 컨텍스트는 닫지 않는다. 공유 PostgreSQL 컨테이너가 `@Bean`이어서 Spring Boot의 Testcontainers 수명 처리기가 컨텍스트를 닫을 때 `close()`한다. `destroyMethod=""`도 같다. 그래서 `@DirtiesContext`는 뒤에 도는 시험 클래스의 연결을 모두 끊는다(검증 중 `SessionGuardTest` 10건이 그렇게 실패했다).
- 화면(`useDashboardSync`)
  - 발행 알림과 제목 초점은 대시보드가 그 작업의 `pointerVersion`을 실제로 보일 때만 낸다.
  - 다른 작업 ID를 받으면 실패를 짓지 않는다. 진행 중이면 그 작업을 이어서 보고, 끝났으면 조용히 다시 읽는다.
  - 자동 요청이 날아가는 중의 수동 누름은 대기시켜 보낸다.
  - 자동 트리거의 401·403은 조용히 넘긴다.
  - 모든 요청은 10초에 끊는다.

**실행 결과 (리뷰 patch 반영 후, 최종 트리):**
- 백엔드 `build`(`--no-parallel --max-workers=1`): 통과. 시험 796건, 실패·skip 0. 로컬 wall-clock은 12분 8초다.
  - `PlanSyncTest` 19건·45초. 공유 world 루프로 주입 11, 실패 12, 검토 6, LATE 6 케이스를 돈다.
  - `FirstSnapshotTest` 117건·589초.
  - 2.10 PR의 CI `backend-quality`는 11분 5초였다(job timeout 15분). 이 스토리의 CI 실측으로 여유를 확인해야 한다.
- 프런트엔드(Node 24.18): `typecheck`·`lint`·`lint:css`·`format:check`·`build` 통과. Vitest 23개 파일 617/617.
- 수동 compose 스택(V1~V11, 실행 시점에 만든 key ring) 뒤 주입 origin e2e `--workers=1`: 59/59, 스택 정리.
  - 새 `수동 동기화` 시험(`expectManualSync`)이 포함된다: 320px 키보드 조작, 1초 안 `동기화 중`, v2 발행과 제목 초점, 근거의 버전 이력 둘, cooldown, axe.
  - 이 실행은 리뷰 patch 뒤 구현 쪽에서 돌렸다. 그 뒤의 변경은 백엔드 시험 파일(`PlanSyncTest`의 `@AfterAll`)뿐이다.
- `test-ci-scripts.sh` 147/147, `git diff --check` 통과. `verify-same-origin.sh`는 Windows CP949 결함 때문에 Linux CI가 판정한다.

## Suggested Review Order

**동기화 admission (입구)**

- 판정 순서 하나: 접근 → 제한 → 계획·포인터 → 연결 → 기간 → 진행 중 → 장기 공백 → 시각 규칙.
  [`PlanSyncService.java:190`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L190)

- 접수·합치기·cooldown의 답. 외부 호출은 없고 경합은 유일 색인이 가른다.
  [`PlanSyncService.java:249`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L249)

- 30일·7일·시작점의 경계는 순수 함수 셋이다.
  [`PlanSyncService.java:135`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L135)

- `POST`·`GET /api/dashboard/sync`, 모두 `no-store`.
  [`DashboardController.java:538`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L538)

**worker (lease 하나로 여섯 단계)**

- 기준시각 → 수집 → 판정 → 환산을 한 lease로 돈다. 외부 호출은 트랜잭션 밖이다.
  [`PlanSyncService.java:412`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L412)

- 단계 행을 부모의 lease로 넣어, 2.4 저장소 메서드를 그대로 쓴다.
  [`InitialCollectionRepository.java:99`](../../backend/src/main/java/com/bokrilog/sync/internal/InitialCollectionRepository.java#L99)

- 기존 단계 dispatcher는 동기화의 단계 행을 잡지 않는다.
  [`InitialCollectionRepository.java:83`](../../backend/src/main/java/com/bokrilog/sync/internal/InitialCollectionRepository.java#L83)

- claim, 만료 정리(`SYNC_INTERRUPTED`), 단계 행까지 늘리는 heartbeat.
  [`PlanSyncRepository.java:112`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncRepository.java#L112)

- claim 뒤 10분이 지나면 갱신을 멈춰, 멈춘 작업이 자리를 내준다.
  [`PlanSyncService.java:358`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L358)

- 동시 상한 2이며, 상한에 걸린 tick에도 만료를 먼저 정리한다.
  [`FirstSnapshotWorker.java:105`](../../backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java#L105)

**흐름 조립과 발행 (AD-23)**

- 이어받은 흐름과 새 흐름, 겹친 구간의 기존 사건 대조(`APPLIED_FLOW_CHANGED`).
  [`PlanSyncService.java:635`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L635)

- 발행 트랜잭션 하나에서 snapshot, portfolio CAS, 계산 버전, 제외 행, 포인터 CAS와 이력을 쓴다.
  [`PlanSyncService.java:714`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L714)

- 포인터의 expected-version CAS와 이력 교체.
  [`PlanSyncRepository.java:339`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncRepository.java#L339)

- source 거절의 닫힌 대응과 재시도 가능 표.
  [`PlanSyncService.java:538`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L538)

**스키마**

- 동기화 작업 표와 lifecycle·닫힌 코드 CHECK.
  [`V11__incremental_plan_sync.sql:19`](../../backend/src/main/resources/db/migration/V11__incremental_plan_sync.sql#L19)

- 활성 계획당 진행 작업 하나를 조건부 유일 색인이 강제한다.
  [`V11__incremental_plan_sync.sql:103`](../../backend/src/main/resources/db/migration/V11__incremental_plan_sync.sql#L103)

- 버전별 제외 사건과 포인터 이력, 그리고 기존 행 backfill.
  [`V11__incremental_plan_sync.sql:170`](../../backend/src/main/resources/db/migration/V11__incremental_plan_sync.sql#L170)

**읽기 계약 (2.8~2.10)**

- 신선도 우선순위: 진행 중 → 제한 → 기간 종료 → 장기 공백 → 마지막 시도 → 15분.
  [`DashboardTrust.java:134`](../../backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java#L134)

- 근거와 조정 이력이 함께 쓰는 공식 버전 머리 판정.
  [`OfficialVersionHead.java:56`](../../backend/src/main/java/com/bokrilog/sync/internal/OfficialVersionHead.java#L56)

- 증분 근거: 이어받은 행이 직전 버전의 행과 같아야 한다.
  [`PlanPublicationService.java:722`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L722)

- 조정 이력은 행마다 자기 환산 → 판정 → 후보로 조인한다.
  [`AdjustmentHistoryRepository.java:147`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentHistoryRepository.java#L147)

- 전송 계층 거절만 먼저 읽고, 본문 형식은 호출 자리의 기본값이다.
  [`BybitAdapter.java:365`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L365)

**화면**

- 트리거 셋, 진행 중에만 확인, 조용한 다시 읽기, 수동 조작만 초점을 옮긴다.
  [`useDashboardSync.ts:239`](../../frontend/src/session/useDashboardSync.ts#L239)

- 초점은 대시보드가 새 버전을 실제로 보일 때만 제목으로 간다.
  [`useDashboardSync.ts:200`](../../frontend/src/session/useDashboardSync.ts#L200)

- 버튼(`aria-disabled`), 이유 줄, 오류 요약, 장기 공백 설명.
  [`SyncControl.tsx:76`](../../frontend/src/components/SyncControl.tsx#L76)

- 다음 행동 절, 다시 불러오기 앞의 동기화 조작.
  [`PlanResultPanel.tsx:367`](../../frontend/src/components/PlanResultPanel.tsx#L367)

- `reloading`을 말하지 않고 묶음을 바꾸는 조용한 읽기.
  [`useDashboard.ts:268`](../../frontend/src/session/useDashboard.ts#L268)

**시험**

- v2 발행, 두 포인터, 이력, 이어받은 행, 근거·이력의 v2 계약.
  [`PlanSyncTest.java:94`](../../backend/src/test/java/com/bokrilog/PlanSyncTest.java#L94)

- 발행 쓰기 어디에서든 실패하면 전부 롤백된다.
  [`PlanSyncTest.java:747`](../../backend/src/test/java/com/bokrilog/PlanSyncTest.java#L747)

- 늦은 worker, 삭제, 제한, 연결, membership, 포인터 변경.
  [`PlanSyncTest.java:798`](../../backend/src/test/java/com/bokrilog/PlanSyncTest.java#L798)

- 머리가 어긋난 증분 버전은 두 읽기 모두에 Unavailable이다.
  [`PlanSyncTest.java:1009`](../../backend/src/test/java/com/bokrilog/PlanSyncTest.java#L1009)

- ±1ms 경계의 순수 함수 시험.
  [`PlanSyncServiceTest.java:147`](../../backend/src/test/java/com/bokrilog/sync/internal/PlanSyncServiceTest.java#L147)

- 진입 한 번, 1초 안 `동기화 중`, 확인·교체·초점.
  [`useDashboardSync.test.tsx:145`](../../frontend/src/session/useDashboardSync.test.tsx#L145)

- 실 스택에서 키보드로 수동 동기화, v2와 제목 초점, cooldown.
  [`first-snapshot.spec.ts:338`](../../frontend/e2e/first-snapshot.spec.ts#L338)
