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
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-10-cashflow-adjustment-history.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.11 범위, ERD-1.0.0 §2.2·SYNC-UX-1.0, AD-7·AD-8·AD-16·AD-23">

## Intent

**Problem:** 활성 계획의 공식 결과는 최초 발행(2.7)의 `tc`에 멈춰 있다. 시간이 지나면 신선도는 `최신 아님`이 되고 사용자가 할 수 있는 일이 없다. 그 뒤의 입출금과 현재가치는 계획 비교에 들어오지 않는다.

**Approach:**
- 대시보드 진입, 탭 재활성화, `동기화` 버튼 세 트리거가 서버에 동기화 작업 하나를 접수한다(`POST /api/dashboard/sync`).
- Worker는 Bybit `tc`를 고정한다. 그다음 `max(t0, cashflow_verified_through - 24시간 또는 7일)`부터 `tc`까지를 2.4의 수집, 2.5의 판정, 2.6의 환산, 2.7의 순수 계산 계약으로 다시 처리한다.
- 결과로 `(t0, tc]` 전체의 새 계보를 만들고, 새 source/portfolio snapshot·계산 버전·두 포인터를 한 트랜잭션에서 원자 발행한다.
- 화면은 진행 중 마지막 정상 묶음을 그대로 두고, 끝나면 대시보드 응답 하나로 교체한다.

## Boundaries & Constraints

**Always:**
- **트리거와 admission**(SYNC-UX-1.0). 요청은 `{trigger: ENTRY|VISIBILITY|MANUAL}`과 `Idempotency-Key`다.
  - admission은 짧은 트랜잭션 하나다. 외부 호출은 하지 않는다.
  - 판정 순서:
    1. 계정 쓰기 접근. 거부면 403.
    2. 같은 key의 재전송. trigger가 같으면 그 작업을 돌려주고, 다르면 409다.
    3. 활성 계획과 공식 포인터가 있는가.
    4. 진행 중 작업이 있으면 그 작업에 합친다.
    5. 사용 불가 사유: 운영 제한, 연결·membership 불일치·없음, `now >= T`.
    6. 장기 미동기화: `DB now - cashflow_verified_through > 30일`이면 작업을 만들지 않는다.
    7. 쿨다운과 자동 트리거 간격.
    8. 접수.
  - 트리거 유형은 작업에 기록한다.
  - 자동 트리거(ENTRY·VISIBILITY)는 `max(마지막 동기화 admission, 현재 공식 포인터 발행 시각)` 뒤 5분이 지나야 접수된다.
  - MANUAL은 마지막 동기화 admission 뒤 30초가 지나야 접수된다. 결과의 성공·실패와 무관하다.
  - 조건에 걸리면 작업을 만들지 않고, 외부 호출 없이 마지막 작업 참조와 다시 가능한 시각을 돌려준다.
  - 탭 재활성화는 클라이언트가 5분 이상 숨겨져 있었던 경우에만 보낸다. 그 판정은 서버가 대신할 수 없다.
- **Bybit `tc`와 모드.**
  - Worker의 첫 외부 호출은 공개 시간 API 한 번이다. 그 값으로 `tc`를 작업에 고정한다(CAS).
  - 인증 호출 전에 같은 `tc`로 다시 판정한다:
    - `tc > cashflow_verified_through`여야 한다.
    - `tc - cashflow_verified_through > 30일`이면 `LONG_GAP`으로 끝낸다.
    - `tc >= T`이면 `PLAN_PERIOD_ENDED`로 끝낸다.
  - 모드는 `tc - last_extended_verification_at >= 7일`이면 `EXTENDED`, 그 밖에는 `NORMAL`이다.
  - 수집 시작점은 `max(t0, cashflow_verified_through - (EXTENDED ? 7일 : 24시간))`이다.
  - 30일 경계는 정확히 30일을 포함한다. 30일·7일·24시간·5분·30초·15분은 바꾸지 않는다.
- **수집과 완전성.**
  - `[시작점, tc]`를 최대 7일 연속 창으로 나눈다. 창마다 `TRANSFER_IN`·`TRANSFER_OUT`의 모든 cursor 페이지를 읽고 기존 `sync_initial_*` 사실 표에 저장한다. 경계 중복은 Bybit `id`가 제거한다.
  - 새 후보는 `(t0, tc]` 전체를 대상으로 하고 두 가지로 이뤄진다:
    - 직전 공식 계산의 후보에 있던 사건. 이어받았다는 표지(provenance)를 단다.
    - 이번 창에서 관측한 사건.
  - 그 범위에 저장돼 있으나 둘 어디에도 없는 사건이 있으면 `검토 필요`다.
  - 창 증거가 `[시작점, tc]`를 빈틈없이 덮고 두 유형이 모두 끝 페이지에 닿아야 완전하다.
- **재사용.**
  - 판정·환산·계산은 기존 정책(`BYBIT-CASHFLOW-1.0`·`BYBIT-PRICE-1.0`·`CALC-1.0`·`CALENDAR-SEOUL-1.0`)과 기존 repository 메서드를 쓴다.
  - 새 후보 전체를 새 판정 묶음, 새 환산 묶음 하나로 처리한다. 이미 있는 캔들은 캐시가 준다.
  - 계산은 계획의 불변 조건 `P0`·`t0`·`T`·주기·목표율과 새 `E(tc)`·`tc`, 모든 흐름으로 한다.
  - 새 source snapshot은 후보의 `totalEquity`에서 만든다. portfolio snapshot은 그 ID 하나와 같은 USD 합계를 담는다.
  - 빠른 경로, 표시값 계산, 다중 source 합산은 없다.
- **워터마크.**
  - 두 확인 시각은 계산 버전의 불변 열이다: `cashflow_verified_through = tc`, `last_extended_verification_at`(EXTENDED면 `tc`, 아니면 직전 값).
  - 두 값은 발행이 성공할 때만 전진한다.
  - 다음 중 하나라도 있으면 발행하지 않고 두 시각도 그대로 둔다: 페이지 누락, 미인식 사건, 중복 의심, 형식 오류, 환산 보류·검토, 계산 거절.
- **원자 발행**(AD-23). 외부 호출 없는 outer `REQUIRED` 트랜잭션 하나에서 순서대로 한다:
  1. 잠근다: plan lock → 계정 쓰기 lock → 작업 lease.
  2. 발행 직전 재검사: 제한, 삭제, 활성 계획, 기준 포인터(버전·계산), portfolio 포인터, membership, 연결, `now < T`.
  3. 쓴다:
     - source snapshot.
     - portfolio `publishNext` CAS.
     - 계산 버전. 직전 계산을 잇고, 두 워터마크와 여섯 지표, 흐름을 담는다.
     - plan 포인터 CAS(version+1)와 이력 행.
  4. 마지막 재검사를 하고 fenced 종료한다.

  하나라도 실패하면 전부 롤백한다. 활성 계획 행과 intent는 바꾸지 않는다.
- **실패 저하.**
  - 어느 단계가 실패해도 마지막 정상 포인터와 값이 남는다.
  - 대시보드 신선도는 `최신 아님`과 원인·다음 행동을 보인다.
  - `0`이나 부분 결과로 대체하지 않는다. 자동 재시도는 없다.
  - 최근 입출금이 거래소 기록 지연으로 아직 보이지 않을 수 있다고 안내한다.
- **동시성·삭제**(AD-16).
  - 사용자·계획당 진행 작업은 하나다. 조건부 유일 색인이 권위다.
  - 모든 단계와 발행 직전에 lease·fencing과 허용 상태를 다시 읽는다.
  - lease를 잃은 Worker는 아무것도 쓰지 않는다. 다시 claim된 중단 작업은 이어 달리지 않고 `SYNC_INTERRUPTED`로 끝난다.
  - 삭제·해제·제한이 늦은 응답보다 우선한다. 자격정보를 다시 읽지 않는다.
  - 삭제 경로는 이 사용자의 작업·후보·멱등·이력 행을 지운다. 다른 사용자의 행은 남는다.
- **대시보드 읽기 모델.**
  - `GET /api/dashboard`의 `READY`는 기존 묶음에 `sync`를 더한다: 가능 여부, 사유, 쿨다운 끝, 마지막 작업의 트리거·상태·단계·코드·시각.
  - 신선도 `STALE`의 새 원인:
    - `LAST_SYNC_UNSUCCESSFUL`, `LAST_SYNC_NEEDS_REVIEW`.
    - `LONG_GAP`(다음 행동 `CHOOSE_CONTINUATION`).
    - `SYNC_UNAVAILABLE`(다음 행동 `NONE`).
    - 그 밖의 `STALE`은 다음 행동이 `SYNC`다.
  - 입력 신뢰도는 마지막 작업이 `검토 필요`로 끝나면 `REVIEW_REQUIRED`/`SYNC_REVIEW_REQUIRED`다. 영향 지표는 여섯 전부다.
  - 판정은 서버의 순수 함수가 하고, 화면은 닫힌 맵으로 카피를 고른다.
- **화면.**
  - `동기화` 버튼은 신선도 축 바로 뒤에 둔다. 44px 이상 primary이고 `aria-disabled`로 중복을 막는다.
  - 누르면 1초 안에 `동기화 중`을 보인다. 진행 중에는 대시보드를 2초마다 다시 읽는다. 문서가 보일 때만 읽고, 3분이 상한이다.
  - 성공한 새 묶음만 한 번에 교체한다.
  - 화면당 `role=status` 하나에 상태 문장을 한 번 말한다. 폴링 중에는 "다시 불러오는 중"으로 바뀌지 않는다.
  - 초점은 사용자가 누른 동기화에만 옮긴다: 성공이면 대시보드 제목(h2), 그 밖에는 동기화 오류 요약.
  - 장기 미동기화이면 버튼 대신 `장기 미동기화·선택 필요` 설명을 보인다. 두 선택지의 데이터 범위 차이와 "마지막 정상 결과는 읽기 전용으로 유지된다"를 담는다.
  - reduced motion에서는 정적 표지와 텍스트만 쓴다.
- **근거·이력 계약 유지.**
  - 2.9 근거는 증분 계산 버전도 한 계보로 판정한다:
    - 만든 작업이 그 계획·버전·계산을 발행했다.
    - 직전 계산을 잇는다.
    - portfolio 순번이 +1이다.
    - 후보의 기반 후보는 직전 계산의 후보다.
    - 창 시작은 `[t0, 직전 확인 시각]` 안에 있다.
  - 수집 범위는 그대로 `(t0, tc]`로 보이고, 이번 동기화의 창 시작을 따로 보인다.
  - 공식 버전 목록은 이력 표에서 읽는다(적용 시작·끝).
  - 2.10 조정 이력은 새 버전의 전체 흐름을 순번 `1..n`으로 보인다.
- **관측.**
  - admission 한 줄에 트리거·결과·HTTP 상태·지연을 남긴다.
  - 작업 종료 한 줄에 job·트리거·모드·상태·단계·코드·지연·정책 버전을 남긴다.
  - 접수된 작업마다 종료 줄이 정확히 하나라, 성공률의 분자·분모를 뽑을 수 있다.
  - 금융값, 자격정보, 원 응답, `user_id`는 남기지 않는다.
- 모든 응답은 `no-store`다. `/api/dashboard/`의 CSRF·Origin·인증 술어를 그대로 쓴다. 브라우저 저장소 쓰기와 새 의존은 없다. 카피는 `content/`가 소유하고, 색·간격은 토큰만 쓴다.

**Ask First:**
- 새 내부 화면 경로와 전역 내비게이션.
- plan·portfolio·identity·operations의 새 공개 API.
- SYNC-UX·ERD의 숫자, CALC·BYBIT 정책 값의 변경.
- Bybit 호출 예산·페이지 재시도·token bucket(ERD §2.5, 2.12).
- 사용자 행동과 무관한 스케줄 작업.
- 장기 미동기화 선택지의 실행(`CATCH_UP`은 2.12, 새 계획은 2.13·2.15).

**Never:**
- 24시간 폴링, 전 사용자 배치, 확장 검증 전용 백그라운드 작업.
- 30일 초과 상태에서의 Bybit 호출. 자동 재시도 루프.
- 부분 결과·후보의 공식 표시. 활성 계획 조건의 변경. 과거 행의 수정·삭제.
- 광고. 계획 종료. 수동 사건·가격 수정. 전체 이력 무조건 재조회.
- 2년 초과 복구 약속.
- 다른 모듈의 테이블 직접 접근. `double`·`float` 금융 계산. 외부 호출 중 DB 트랜잭션.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| MANUAL | 활성 계획, 30초 안에 admission 없음 | 202 `ACCEPTED`. 1초 안에 `동기화 중`, 기존 값 유지. 성공하면 pointer v+1, 새 현재가치·지표·세 축, 초점은 h2 | N/A |
| JOIN | 진행 중 작업, 다른 탭·반복 클릭·같은 key | 200 `JOINED`, 같은 작업 참조, 새 작업 0 | 같은 key·다른 trigger는 409 |
| COOLDOWN | MANUAL, 마지막 admission 30초 이내 | 200 `COOLDOWN`, 다시 가능한 시각, 외부 호출 0 | N/A |
| NOT_DUE | ENTRY·VISIBILITY, 기준 시각 5분 이내 | 200 `NOT_DUE`, 작업 0, 외부 호출 0 | N/A |
| LONG_GAP | `now - verified > 30일` | 200 `LONG_GAP`, 작업 0, Bybit 0, 선택 설명, 결과 읽기 전용 | 정확히 30일은 접수. Worker가 `tc`로 초과를 보면 인증 호출 없이 `LONG_GAP` |
| WINDOWS | NORMAL 또는 EXTENDED(7일 경과), 29일 공백 | 시작점 `verified-24h` 또는 `-7d`(t0 clamp)부터 7일 창 연속, 모든 페이지, 닫힌 `tc` | N/A |
| REVIEW | 누락·미인식·충돌·설명 없는 저장 사건·판정 검토·환산 검토 | 발행 0, 워터마크 불변, 입력 신뢰도 `검토 필요`, 신선도 `최신 아님` | `NEEDS_REVIEW` |
| FAIL | 시간·인증·rate limit·timeout·5xx, 환산 보류, 계산 범위 초과, DB 오류 | 마지막 정상값 유지, `최신 아님` + 원인 + 다시 동기화, 최근 사건 안내 | `FAILED` + 닫힌 코드, 재시도 없음 |
| RACE | lease 상실, 옛 fencing, 제한, 연결 해제·membership 종료, 포인터 이동, 삭제 | 발행 0, 늦은 응답의 사용자 데이터 재생성 0 | `BLOCKED` + 코드. 삭제는 403 |
| UNAVAILABLE | 제한, 연결 없음, `now >= T` | 409 코드. 대시보드 `sync.availability=UNAVAILABLE`, 버튼 비활성과 사유 | 외부 호출 0 |

</frozen-after-approval>

## Code Map

**계약**
- `epics.md`
  - 2192-2300: Story 2.11 AC-1~AC-10과 테스트 기준.
  - 305-310: 24시간·7일·30일 규칙과 두 선택지의 의미.
- `EPIC-IMPLEMENTATION-READINESS.md`
  - 16-22: `tc` 고정과 수집 창.
  - 104-111: SYNC-UX-1.0.
  - 50-55: 호출 예산. 이 스토리의 범위 밖이다.
- `EXPERIENCE.md`
  - 61, 81, 162-174, 184, 202-215: 모델 문장, `sync-control`, 원자 갱신, 초점·상태 공지·reduced motion.
- `DESIGN.md`
  - 107-111: `sync-control` 토큰.
  - 262, 264: 신선도 옆 배치와 44px primary.
- `tokens.css`
  - 117-120: `--component-sync-control-*`.
  - 96: `--space-target-min`.

**스키마** (`backend/src/main/resources/db/migration/`. 읽기만 한다. 새 파일은 `V11`)
- `V5`
  - `sync_job`: 첫 snapshot 작업이다. 손대지 않는다.
  - `portfolio_snapshot`·component·current(58-93): component `source_snapshot_id`가 UNIQUE라 발행마다 새 source snapshot이 필요하다.
- `V7`
  - 수집 작업(2-31): `UNIQUE(user,intent,key)`, 활성 intent 하나(29).
  - 사건(34-51): `UNIQUE(provider_account_id,source_id)`.
  - provenance(53-60), 관측(62-81), 페이지 증거(83-109), 후보(111-130).
- `V8`: 판정 작업(2-25), 묶음(27-44), 사건(47-69).
- `V9`
  - 환산 작업(69-122). CHECK들이 CANCELLED에 `NOT_PERMITTED`를 요구한다.
  - 묶음(146-184), 흐름·leg·계산 가능 흐름(200-285).
- `V10`
  - 계산 버전(162-217): `created_by_job_id` UNIQUE이고 FK가 없다. `CHECK(cashflow_verified_through=basis_at)`.
  - 지표·흐름(233-272).
  - 포인터(279-290): "a later one (Story 2.11) updates only when the version it read is still current".

**백엔드** (`backend/src/main/java/com/bokrilog/`)
- `sync/internal/SyncJobService.java`
  - 명령 이름: 27-31.
  - 2.6 환산 실행은 `runConversion`(312-348)과 `fixReference`(350-361)다. 증분 환산의 본보기다.
  - 삭제 순서: `eraseSyncData`(228-237).
  - `initialEligible`(510-516)은 계획 조건 명령까지 막는다. 증분에는 쓰지 않는다.
- `sync/internal/FirstSnapshotWorker.java`
  - 유일한 Worker다. `dispatch*`는 1초 간격이고 `heartbeat`는 5초 간격이며, 가상 스레드에서 돈다.
  - `renew` 격리: 39-55. 새 dispatch와 in-flight 맵을 같은 모양으로 더한다.
- `sync/internal/InitialCollectionRepository.java`
  - `claim`(76-87): 증분 자식을 제외해야 한다.
  - `storePage`(123-212): `parse`의 창은 `(t0, tc]`이고, provenance는 같은 job에 단다.
  - `complete`(216-230)와 `validCoverage`(232-246): 시작점이 t0로 고정돼 있다.
  - `storeCandidate`(265-294): hash 공식과 `(t0,tc]` 전체 사건을 쓴다.
- `sync/internal/CashflowClassificationRepository.java`
  - `latestEligibleCandidate`(57-76)·`inputHash`(119-144): provenance는 후보 job의 것이어야 하고, 범위 사건 수가 같아야 한다.
  - `evidenceHash`(147-159): storeCandidate와 같은 공식이어야 한다.
  - `claim`(103-112): 자식 제외. `publish`(173-237)는 `stillPermitted`를 받는다.
  - `CashflowDecisionTable`: 판정은 순수 함수다.
- `sync/internal/CashflowConversionRepository.java`
  - `admit`(167-202)가 입력을 복사한다. 증분은 RUNNING·lease로 넣는 변형이 필요하다.
  - `claim`(216-228): 자식 제외.
  - `inputsStillValid`(135-143): "intent의 최신 후보"라서 증분 후보가 최신이면 성립한다.
  - `publish`(390-409): 같은 판정 안에서만 이전 버전을 잇는다.
  - `CashflowConversionMarket`: 캔들 캐시 우선.
- `sync/internal/PlanPublicationService.java`
  - 발행 트랜잭션의 본보기: `publishInTransaction`(297-351). 잠금 순서, `insertCandidateSource`, `publishNext`, `insertCalculation`, `insertFlow`, 포인터, 최종 재검사.
  - `calculationInput`(475-483), `notation`.
  - 대시보드 `bundle`(577-615)과 AD-23 확인(588-597).
  - 근거 `lineage`(665-782)와 `calculatedFromPinned`(796-818): 최초 발행 전제에 기대므로 증분 갈래가 필요하다.
  - `restricted`(879-885).
- `sync/internal/PlanPublicationRepository.java`
  - `Pinned`(52-57): 증분도 이 모양으로 만들어 `insertCalculation`(293-331)을 재사용할 수 있다. 새 열 둘을 더해야 한다.
  - `pointFirst`(349-353): 이력 행을 함께 써야 한다.
  - `freshness`(420-431): 진행 판정에 증분 작업 표를 더한다.
  - `collectionRange`(493-499), `erase`(562-568).
- `sync/internal/DashboardTrust.java`
  - `Freshness`(58), `freshness`(103-112), `reliability`(118-126), `nextAction`(75-77), 다음 행동 상수(31-34).
- `sync/internal/AdjustmentHistoryService.java:115-196`
  - 계보 판정은 발행 작업에 기대지 않는다. intent는 계획의 intent다. 증분 버전에서도 그대로 성립해야 한다.
- `sync/SyncJobApi.java`
  - `Dashboard.Ready`(191), `DashboardResult`(160-166), `TrustAxis` javadoc(123-133).
  - 새 admission 타입과 메서드를 같은 파일에 더한다.
- `sync/PlanPublicationPort.java`
  - `activePlan`과 `lockPlanWrites`만으로 충분하다. plan 모듈은 바꾸지 않는다.
- `source/SourceConnectionApi.java`
  - `collectInitialInputs`(60)는 항상 `t0`부터이고, 고정 `tc`가 없으면 스스로 시간 API를 부른다.
  - `marketTime`(102).
  - 새 공개 메서드 하나로 `from`과 고정 `tc`를 받는다.
- `source/internal/SourceConnectionService.java:109-130`
  - 접근·연결 ACTIVE·복호화 뒤 어댑터를 부른다. 트랜잭션 밖이다.
- `source/internal/BybitAdapter.java`
  - `collectInitialInputs`(338-459): 창 루프가 388에서 t0부터 돈다.
  - 348·364·403이 전송 거절(`call.rejection()`)을 먼저 보지 않아, TIMEOUT이 인증 거절로 읽힌다.
- `portfolio/PortfolioMembershipApi.java`
  - `publishNext`(82): 실제 트랜잭션을 요구하고 `MembershipChanged`·`PointerMoved`를 돌려준다.
  - `activeSource`(90), `officialValue`(84).
- `operations/OperationalRestrictionApi.java:25`: 명령 문자열 하나를 받는다.
- `DashboardController.java`: 대시보드(120-176)와 로그(178-181). 새 POST 핸들러와 view를 같은 관용으로 둔다.
- `AuthSecurityConfig.java:1195,1211,1231`: `/api/dashboard/**`가 이미 인증·CSRF·Origin에 걸린다. 주석만 고친다.
- `DevBybitStub.java`
  - 시나리오 150-294. 사건은 요청 창의 `start+1` 기준이라, 계획이 24시간 안이면 증분 창 시작이 t0와 같아 기존 시나리오가 그대로 호환된다.
  - `walletList`(692-707)는 상수 잔액이다.
  - `resetScenarioState`(616).

**백엔드 시험** (`backend/src/test/java/com/bokrilog/`)
- `AuthFlowTestBase.java`
  - 유일한 `@DynamicPropertySource`는 74-83이다. 새로 두지 않는다.
  - `resetStore`: 154-172.
- `FirstSnapshotTest.java`
  - 발행까지의 도우미: `connect`/`store`(4736/4742), `initialIntent`(1297), `confirmIntent`(1308), `convertedIntent`/`convert`(4527-4558), `publish`/`awaitPublication`(4594/4600), `assertReproducible`(4629).
  - 대시보드·근거·이력 읽기: `dashboardOf`(2423), `referenceOf`/`evidenceOf`(4464/4469), `historyOf`(4370).
  - 시각 이동: `shiftStart`(4581). 잠금: `holdPlanLock`(4662).
  - 실패 주입 trigger: 2086-2124. 삭제: 2487.
  - 하위 경로 404: 2730.
- `BybitStubConfiguration.java`
  - 요청 수는 96-119. 질의 인자는 기록하지 않는다.
  - `serverTimeMode`: 124.
- `sync/internal/PlanPublicationServiceTest.java`: Mockito 단위 시험의 본보기(67-107).
- `ModuleBoundaryTest.java`: sync는 plan을 import할 수 없다. web·servlet 의존도 없다(136-155).

**프런트엔드** (`frontend/src/`)
- `session/useDashboard.ts`
  - `NEXT_ACTIONS`(206-210): 닫힌 집합.
  - `parseDashboard`(217), `supersedes`(240), 훅(286-373), `dashboardAnnouncement`(383-409), `statusBusy`(436).
  - 요청 시간 상한이 없다(미룬 항목).
- `session/useSessionState.ts:462-474`
  - 탭 재활성화마다 세션을 재확인하고, 그 사이 대시보드가 `checking`이 된다(Story 1.5 보안 계약). 동기화 훅은 이것을 바꾸지 않는다.
- `components/PlanResultPanel.tsx`
  - h2: 408. 신뢰 절: 217-257(신선도 221-235). 다음 행동: 440-460.
  - 초점 effect: 385-391. `.action` CSS: `.module.css:156-178`.
- `components/DashboardStatusText.tsx:19-49`: status 요소 셋.
- 연결 지점
  - `screens/SignedInScreen.tsx`: 45-54, 118-148, 163.
  - `screens/OnboardingScreen.tsx`: 41-50, 111-141, 156.
- `content/planResult.ts`
  - 신선도 원인: 158-167.
  - `nextActionCopy`(243-259)의 `NONE` 문장은 "새로 확인할 수 없습니다"라서 바꿔야 한다.
  - `axisActionCopy`: 266-275.
  - `content/entry.ts:42`: "동기화는 사용자가 직접 요청할 때만" 약속. 자동 진입과 충돌하므로 맞춘다.
- `session/useCalculationEvidence.ts`
  - 229-230: `rangeStart===t0`·`rangeEnd===tc` 단정.
  - 263-275: 현재 버전 정확히 하나.
- `components/InitialInputsCollection.tsx:743-763`: 폴링 본보기다. 실패 중 무한 폴링 결함이 있으므로 따라 하지 않는다.
- 시험
  - `test/setup.ts`: 스텁 없는 fetch는 거절된다(335-354). 스텁 키는 method+URL(66).
  - `test/planResultFixture.ts`: 67-128.
  - `useDashboard.test.tsx:59`: 대시보드 훅 자체는 폴링하지 않는다.
  - `PlanResultPanel.test.tsx:525-535`: 패널 안 live region은 0개, 금지어 목록이 있다.
  - `SignedInScreen.test.tsx:331-368`.
- e2e `e2e/first-snapshot.spec.ts`
  - `connectAndSavePlan`(37-99, 키 55), `publishAndOpenResult`(151-168).
  - `expectFirstResult`(174-289)의 다음 행동 단정: 213.
  - `expectKeyboardOrder`(294-326): 탭 여섯 개.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V11__incremental_plan_sync.sql`
  - `sync_plan_sync_job`
    - 담는 것:
      - 사용자·계획·portfolio·intent·membership·연결·계정.
      - trigger, key.
      - 기준: 계산, 포인터 버전, 후보, verified, extended.
      - `tc`·mode·창 시작, stage, status, 닫힌 코드.
      - 자식·결과 id, fencing·lease, admitted·finished 시각.
    - 제약:
      - `UNIQUE(user,key)`, 진행 중 `(user,plan)` 하나.
      - 생명주기 CHECK.
    - FK는 `identity_account` 하나뿐이다. 나머지는 코드가 검증하는 논리 참조다.
  - 수집·판정·환산 작업 셋에 `plan_sync_job_id`(FK RESTRICT)를 더한다.
    - 수집 작업에는 창 시작·기반 후보 열도 더한다.
    - 후보 묶음에는 `base_candidate_id`·`window_start`를 더한다. 둘 다 있거나 둘 다 없어야 하는 CHECK를 둔다.
    - provenance에는 `carried_from_candidate_id`를 더한다.
  - `sync_plan_calculation`
    - `last_extended_verification_at`: `= cashflow_verified_through`로 백필한 뒤 NOT NULL로 만들고 CHECK를 단다.
    - `previous_calculation_id`: 자기 참조 FK, NO ACTION.
  - `sync_official_plan_result_history`: PK는 `(plan_id,pointer_version)`이다. 현재 포인터로 백필한다.
  - expand만 한다.
- [x] `source/SourceConnectionApi.java`, `source/internal/SourceConnectionService.java`, `source/internal/BybitAdapter.java`
  - `collectIncrementalInputs(user, connection, t0, from, tc, pageConsumer)`를 더한다. 시간 API를 부르지 않고, 창은 `from`부터 돈다.
  - 두 수집이 공유하는 핵심에서 전송 거절을 먼저 본다.
  - 이유: 공개 API는 하나뿐이다. 기존 수집 동작은 한 경우만 바뀐다. 서명 호출에 JSON이 아닌 본문이 오면, 2.4는 이제 자기 형식 오류 규칙(`NEEDS_REVIEW`)을 따른다. 시간 초과·rate limit·5xx는 전과 같이 재시도 대상이다.
- [x] 새 `sync/internal/SyncWindow.java`: 순수 함수.
  - 30일 게이트, 모드, 창 시작, 5분·30초 간격, 가능 여부 판정.
  - 이유: 경계 시험을 ms 단위로 고정한다.
- [x] 새 `sync/internal/PlanSyncRepository.java`
  - admission, claim, lease·heartbeat.
  - heartbeat는 자식 lease도 함께 연장한다(SKIP LOCKED 관용).
  - fenced 종료, 중단 정리(자식 종료), 최신 작업과 view, 삭제.
- [x] 새 `sync/internal/PlanSyncService.java`
  - admission과 가능 여부(같은 `SyncWindow`).
  - `run`:
    1. `tc`·모드 고정.
    2. 수집 자식.
    3. 판정 자식.
    4. 환산 자식: 참조 시각 고정, 트랜잭션 밖 시세.
    5. 순수 계산.
    6. 발행.
  - 단계마다 허용 재검사를 한다. 종료 로그를 남긴다.
  - 발행 트랜잭션은 새 `sync/internal/PlanSyncPublisher.java`가 소유한다(2.7 순서 그대로).
- [x] `InitialCollectionRepository.java`, `CashflowClassificationRepository.java`, `CashflowConversionRepository.java`
  - 자식 삽입(RUNNING, lease)과 증분 완료.
    - 창 시작부터의 coverage를 본다.
    - 기반 후보 사건을 이어받는다(표지를 단다).
    - 설명 없는 저장 사건은 review다.
  - 기반을 포함한 후보 hash는 두 곳이 같은 공식이어야 한다.
  - 독립 claim 셋에서 자식을 제외한다.
- [x] `PlanPublicationRepository.java`, `PlanPublicationService.java`
  - 계산 행 새 열, `pointNext` CAS와 이력.
  - `pointFirst`도 이력을 쓴다.
  - 진행 판정에 증분 작업을 더한다. 대시보드 `sync` 블록과 신선도 사실을 같은 RR 트랜잭션에서 읽는다.
  - 근거의 증분 갈래, 창 시작, 공식 버전 목록(적용 끝)을 더한다. 주석의 "2.11" 전제 문장을 고친다.
- [x] `DashboardTrust.java`: 새 원인·다음 행동(`SYNC`·`CHOOSE_CONTINUATION`)과 입력 신뢰도 `SYNC_REVIEW_REQUIRED`.
- [x] `SyncJobApi.java`, `SyncJobService.java`
  - 트리거, admission sealed 결과, 작업 view, `Ready.sync`.
  - 명령 `plan-sync`. `eraseSyncData`는 증분 작업을 자식 뒤에 지운다.
- [x] `sync/internal/FirstSnapshotWorker.java`: 증분 dispatch·heartbeat 맵.
- [x] `DashboardController.java`, `AuthSecurityConfig.java`(주석만)
  - `POST /api/dashboard/sync`
    - 202 `ACCEPTED`.
    - 200 `JOINED`·`COOLDOWN`·`NOT_DUE`·`LONG_GAP`.
    - 403·409(`NO_ACTIVE_PLAN`·`SYNC_UNAVAILABLE`·`IDEMPOTENCY_CONFLICT`)·422(key)·400(본문).
  - `READY.sync` view를 더한다. admission 로그 한 줄.
- [x] `DevBybitStub.java`, `test/.../BybitStubConfiguration.java`
  - 시나리오 `INCREMENTAL("incremental")`
    - uid별 기준은 첫 지갑 조회 시각이다.
    - 기준+5초×k(k≤24)의 USDT 입금 `+10`을 낸다. 요청 창 안이고 벽시계가 지난 것만 낸다.
    - 잔액은 `1000+10×지난 사건 수`다.
  - 시험 스텁은 거래 기록 질의 인자(type·start·end)를 기록한다.
  - 리셋에 새 상태를 더한다.
- [x] 백엔드 시험
  - 새 `IncrementalSyncTest`(AuthFlowTestBase)가 I/O matrix 전 행을 덮는다.
    - 창 시작 단정은 기록된 질의로 한다. 오래된 계획은 `shiftStart`와 SQL로 만든다.
    - 삭제 앞뒤와 보존 사용자.
    - v2에서 근거 200과 이력 1..n.
    - 로그 캡처.
  - 발행까지의 도우미는 FirstSnapshotTest에서 공유 추상 기반으로 옮기거나 최소만 복제한다.
  - 경계는 `SyncWindowTest`, `DashboardTrustTest`, `PlanSyncServiceTest`(Mockito) 단위 시험으로 고정한다.
  - 기존 하위 경로 404 시험을 갱신한다.
- [x] 프런트엔드 로직
  - 새 `content/planSync.ts`: 결과·코드·사유 닫힌 맵과 장기 미동기화 설명.
  - `content/planResult.ts`: 새 원인·다음 행동, `NONE` 문장. `content/entry.ts` 문구를 맞춘다.
  - 새 `session/useDashboardSync.ts`
    - ENTRY는 마운트당 첫 READY에서 한 번 보낸다.
    - VISIBILITY는 숨김이 5분 이상일 때 보낸다.
    - MANUAL은 누르는 즉시 `동기화 중`을 보인다.
    - 폴링: 2초, 보일 때만, 3분 상한.
    - 완료 판정, 초점 요청, 쿨다운 타이머는 서버의 상대 초로 잡는다.
  - `session/useDashboard.ts`: `sync` 파싱과 새 다음 행동. 공지 문장은 동기화를 알고, 폴링 중 busy·reloading을 억제한다.
- [x] 프런트엔드 화면
  - 새 `components/SyncControl.tsx`(+`.module.css`): 버튼, 진행 표지, 쿨다운·사유, 최근 사건 안내, 오류 요약(tabIndex -1), 장기 미동기화 설명.
  - 연결: `PlanResultPanel.tsx`(신선도 뒤), `DashboardStatusText.tsx`, `SignedInScreen.tsx`, `OnboardingScreen.tsx`.
  - 근거 화면: 창 시작과 버전 적용 끝.
- [x] 프런트엔드 시험
  - Vitest(`useDashboardSync.test.tsx`, `SyncControl.test.tsx`, `setup.ts` 스텁, 기존 패널·화면·훅 시험).
    - 트리거 셋과 간격.
    - 1초 안의 표시, 중복 차단.
    - 성공·실패·검토·쿨다운·장기·사용 불가.
    - 초점, 공지 한 번, 폴링 상한과 숨김.
    - 저장소 0, 금지어.
  - e2e 새 `e2e/incremental-sync.spec.ts`(`incremental` 키, 공유 도우미)
    - 수동 동기화, 1초 안의 `동기화 중`, v2 교체, 초점과 공지.
    - 키보드 순서, 320·1024px, axe.
  - `expectKeyboardOrder`·다음 행동 단정을 갱신한다.

**Acceptance Criteria:**
- Given 발행을 마친 계획, when 새 입금 뒤 `동기화`를 누르면, then 1초 안에 `동기화 중`이 보이고 기존 여섯 값은 그대로다. 끝나면 pointer v2의 현재가치·지표·세 축·흐름 수가 한 번에 바뀌고, 초점이 대시보드 제목으로 간다. 계획 조건은 같다.
- Given 진행 중이거나 실패한 동기화, when 대시보드를 읽으면, then 공식 포인터와 값은 마지막 정상 묶음이고 원인·다음 행동이 보인다. 자동 재요청은 없다.
- Given 계정 삭제·제한·연결 해제·포인터 이동이 Worker 실행 중에 일어나면, when 발행 직전에 이르면, then 아무것도 발행되지 않고 워터마크도 그대로다.
- Given v2 공식 결과, when 근거와 조정 이력을 열면, then 둘 다 200이다. 버전 목록은 v1(적용 끝 있음)과 현재 v2이고, 이력은 `(t0, tc2]`의 흐름 전부를 1..n으로 보인다.

## Spec Change Log

## Design Notes

**작업 하나가 단계를 차례로 실행한다.**
- 기존 네 단계는 각각 intent가 OPEN일 때만 허용된다. 활성 계획의 intent는 CONSUMED다.
- 그래서 단계마다 독립 작업을 접수해 이어 붙이는 대신 두 층으로 나눈다.
  - 동기화 작업 하나가 lease 하나로 네 단계를 한 가상 스레드에서 차례로 실행한다.
  - 단계별 기존 표(수집·판정·환산 작업)의 행은 FK와 계보 판정을 위해 그대로 만든다. 그 행을 "자식"으로 두고, 증분 작업이 lease를 갖는다.
- 독립 claim은 자식을 집지 않는다.
- Worker가 죽으면 다시 claim한 쪽이 이어 달리지 않는다. 중단으로 끝내고 자식을 정리한다. 사용자가 다시 동기화하면 되고, 자동 재시도 금지(AC-7)와도 맞는다.

**`(t0, tc]` 전체를 새 계보로 만든다.**
- 2.9·2.10의 계보 판정은 "계산 버전 하나가 후보·판정·환산 한 계보를 인용한다"에 기댄다.
- 증분 사건만 새로 판정하고 앞의 것을 이어 붙이면 판정·환산이 여러 묶음으로 갈라진다. 그러면 두 스토리의 불변식을 모두 다시 써야 한다.
- 전체를 다시 판정·환산하면 비용은 둘뿐이다:
  - 판정은 DB 안의 순수 계산이다.
  - 환산은 캔들이 캐시에 있으면 외부 호출이 없다.
- 외부 조회는 여전히 창 안에서만 일어난다.
- 앞 구간의 완전성은 직전 공식 후보가 증명한다. 그 사건들에는 이어받았다는 provenance 표지를 단다.
- 설명되지 않는 저장 사건은 늦은 사건일 수 있다. 그것은 2.12가 소유하므로, 2.11은 `검토 필요`로 멈춘다. 다음 확장 검증 창이 그 사건을 다시 관측하면 풀린다.

**30일 판정의 시계**
- admission은 DB 시각으로 판정한다. 30일 초과면 외부 호출 없이 끝나야 하므로 Bybit 시간을 먼저 물을 수 없다. epics.md 307도 "admission에서 고정한 서버 시각"이라고 쓴다.
- 접수 직후 Worker가 Bybit `tc`를 고정한다(ERD §2.2 "admission 직후"). 인증 호출 전에 같은 판정을 그 값으로 다시 한다.

**자동 트리거의 5분 기준에 공식 발행 시각을 넣는다.** 최초 발행 직후 대시보드 진입이 곧바로 동기화를 부르지 않게 하기 위해서다. 발행은 그 입력을 접수한 admission보다 늦으므로 규칙보다 보수적이고, 기존 e2e도 흔들지 않는다.

**초점은 사용자가 누른 동기화에만 옮긴다.** 진입·재활성화로 끝난 동기화가 읽는 중인 초점을 빼앗으면 예기치 않은 문맥 변경이다(WCAG 3.2). 결과는 status 문장 한 번으로 알린다.

**탭 재활성화의 세션 재확인은 그대로 둔다.** Story 1.5가 만료 감지를 위해 고정한 경로다. 재확인 동안 대시보드가 잠깐 비는 것은 기존 동작이며, 동기화 진행 중의 값 유지와는 별개다.

**`사용 불가`는 아직 만들지 않는다.** 이 스토리의 실패는 전부 "마지막 정상 결과는 유효하나 갱신되지 않음"이고, 신선도 원인이 그것을 말한다. 입력 자체를 쓸 수 없게 되는 판정은 2.12가 만든다.

**Bybit `tc`는 DB 시각과 60초 안에서만 믿는다**(리뷰 뒤 확정).
- 시간 응답 하나가 크게 틀린 경우의 결과:
  - 30일을 넘겨 과거로 틀리면 `LONG_GAP`이 붙는다. 같은 기준에서 판정이 고정돼 계획이 영구히 선택 화면에 묶인다.
  - 미래로 틀리면 `cashflow_verified_through`가 미래로 전진한다.
- 그래서 `|tc − DB now| > 60초`이거나 `tc <= cashflow_verified_through`이면 인증 호출 전에 `FAILED`/`PROVIDER_TIME_UNTRUSTED`로 끝낸다.
- 60초는 재인증 허가가 쓰는 미래 skew와 같은 값이다. 2.6이 미룬 "Bybit 서버 시간의 로컬 시계 상한"을 동기화 경로에서만 닫는다. 첫 수집(2.4)과 환산(2.6)의 기준시각은 그대로다.

**제공자 거절을 닫힌 코드로 옮긴다.**

| 제공자 거절 | 결과 |
|---|---|
| 인증·권한·하위 계정 | `FAILED`/`SOURCE_REJECTED` |
| 형식 오류 | `NEEDS_REVIEW`/`SOURCE_REQUIRES_REVIEW`. 2.4의 형식 오류 = 검토와 같다 |
| 나머지(시간 초과·rate limit·5xx·중단) | `FAILED`/`PROVIDER_UNAVAILABLE` |

환산 결과는 이렇게 끝난다:
- `CONVERSION_REQUIRED`: `FAILED`/`CONVERSION_REQUIRED`. 사건 분이 닫히면 다음 동기화가 푼다.
- `REVIEW_REQUIRED`: `NEEDS_REVIEW`/`CONVERSION_REQUIRES_REVIEW`.

**근거의 `unpublished` 목록은 발행 시도만 둔다.** 실패한 동기화는 사용자가 누를 때마다 쌓일 수 있다. 근거 화면은 계보를 보이는 곳이지 시도 기록부가 아니다. 마지막 시도는 대시보드 `sync.latest`가 보인다.

**응답 요지:**
```json
{ "outcome": "ACCEPTED", "job": { "jobId": "…", "trigger": "MANUAL", "status": "QUEUED",
    "stage": null, "failureCode": null, "admittedAt": "…", "finishedAt": null },
  "retryAfterSeconds": null }
// READY 대시보드: …, "sync": { "availability": "AVAILABLE|IN_PROGRESS|COOLDOWN|LONG_GAP|UNAVAILABLE",
//   "reason": null, "retryAfterSeconds": null, "latest": { …job } }
```

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 build` -- expected: Testcontainers를 포함해 전체 통과, 실패 0.
- Node 24: `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` -- expected: 모두 통과.
- 수동 compose 스택(migrate V1~V11) 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080`으로 전체 e2e를 `--workers=1`로 돌리고, 이어서 `deploy/compose/verify-same-origin.sh` -- expected: 통과, 스택 정리.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check` -- expected: 통과.

**구현에서 확정한 것 (frozen 블록 밖):**
- 동기화를 막는 운영 제한은 넷이다: `plan-sync`, `first-snapshot`, `initial-source-event-collection`, `plan-publication`.
  - 계획 조건 명령(`plan-condition-create`)은 기존 계획의 동기화와 무관해 넣지 않았다.
- 작업 상태는 `QUEUED·RUNNING·SUCCEEDED·FAILED·NEEDS_REVIEW·BLOCKED` 여섯이다. Worker가 본 장기 공백은 `BLOCKED`/`LONG_GAP`이다.
  - 같은 기준 계산에 그 행이 있으면 admission과 대시보드도 장기 미동기화로 답한다(`longGapObserved`). 같은 기준을 되풀이해 접수하지 않게 하기 위해서다.
  - `tc` 신뢰 범위가 60초라 이 경로는 30일 경계의 몇십 초 안에서만 생긴다.
- 리뷰 뒤 더한 닫힌 코드 둘:
  - `FAILED`/`PROVIDER_TIME_UNTRUSTED`: `|tc − DB now| > 60초`이거나 `tc`가 확인 시각을 넘지 못함. 인증 호출 전에 끝난다.
  - `BLOCKED`/`INPUTS_CHANGED`: 발행 직전 자기 입력이 자식 결과·고정 범위와 다름.
- 환산 참조 시각은 고정한 `tc`다.
  - `tc` 직전 1분 안의 시세 사건은 `CONVERSION_REQUIRED`로 끝나고 다음 동기화가 푼다(Design Notes).
  - 2.6처럼 시간 API를 한 번 더 부르지 않는다.
- 최초 발행(2.7)도 `last_extended_verification_at = tc`를 쓴다. `t0`부터의 전체 수집이 곧 전체 검증이기 때문이다. V11은 기존 행을 `cashflow_verified_through`로 백필한다.
- 포인터 발행 시각은 `statement_timestamp()`다. 이력의 발행 시각은 비감소면 받는다(같은 ms가 영구 503이 되지 않게).
- 요청 본문은 직접 파싱한다. 형식 오류·모르는 trigger는 `no-store` 400(`BAD_REQUEST`)이다. 모르는 거절 코드는 500이다(서버 누락을 클라이언트 오류로 숨기지 않는다).
- key 규칙은 `PlanPublicationService.validKey` 하나를 쓴다.
- 어댑터는 두 수집이 공유하는 핵심에서 전송 거절을 먼저 본다.
  - 그래서 2.4에서 서명 호출에 JSON이 아닌 본문이 오면 `NEEDS_REVIEW`/`MALFORMED_PROVIDER_RESPONSE`가 된다(전에는 재시도).
  - 시간 초과·429·5xx는 전과 같다.
- 화면
  - 대시보드 읽기는 전부 10초 상한이다(`DASHBOARD_REQUEST_TIMEOUT_MS`). 2.8이 미룬 "조회 훅의 요청 시간 상한"을 대시보드 훅에서만 닫는다. 다른 조회 훅은 그대로다.
  - 폴링 규칙:
    - 실패·중단된 읽기가 따라가기를 끝내지 않는다.
    - 3분 상한은 자기 타이머이고, 보이는 시간만 센다.
    - 같은 작업을 다시 따라가면 예산이 새로 시작된다.
    - 다른 탭이 시작한 진행 작업도 따라간다.
  - `sync` 블록의 모르는 값은 결과 전체를 거부하지 않고 안전 카피나 "사용 불가"로 접힌다.
  - 탭 복귀 표지는 복귀 뒤 첫 settled 응답에서 지운다(실패 응답 포함).
- `DevBybitStub` INCREMENTAL
  - UID별 첫 지갑 조회를 기준으로 10초마다 `+10 USDT`를 최대 48건 낸다(8분, 유형당 한 페이지).
  - 잔액은 `1000 + 10 × 지난 사건 수`다.
  - 시험 스텁의 `resetRequestCount()`가 UID 기준도 지우므로, 한 흐름 도중에 부르지 않는다.
- CI 예산을 올렸다(`ci.yml`, `playwright.config.ts`, `DEVELOPMENT.md`).
  - `backend-quality` 15 → 20분.
  - `same-origin-integration` 15 → 17분.
  - 그 회귀 단계 4 → 6분, 주입된 origin의 `globalTimeout` 3 → 5분.
  - 근거: PR #49에서 백엔드 job 11분 5초, 회귀 2분 30초(59건)였다. 이 스토리의 로컬 백엔드가 12분 56초다. preview E2E(`frontend-quality`)는 그대로다.
- 기존 시험 변경
  - `FirstSnapshotTest`의 포인터 변조 사례(이력 행 없이 v2로 옮김)는 이제 200이 아니라 503이다. 이력이 버전과 맞지 않기 때문이다. 실제 v2는 `IncrementalSyncTest`가 덮는다.
  - `DashboardTrustTest`의 `STALE` 다음 행동은 `SYNC`다.
  - `first-snapshot.spec.ts`의 키보드 순서는 일곱 정지점이고, `e2e/support/firstPlan.ts`로 도우미를 옮겼다.
- heartbeat 시험은 package-private `PlanSyncService#heartbeat`를 reflection으로 부른다. 운영 API 표면을 늘리지 않기 위해서다.

**실행 결과 (리뷰 patch 반영 후, 최종 트리, 2026-09-30 독립 재실행):**
- 백엔드 `clean build`(`--no-parallel --max-workers=1`) 통과: 869 tests, 실패·skip 0, 12분 56초.
  - 그중 `IncrementalSyncTest` 17건(124초). 새 단위 시험은 `SyncWindowTest`·`PlanSyncServiceTest`·`PlanSyncPublisherTest`다.
- 프런트엔드 (Node 24): `typecheck`·`lint`·`lint:css`·`format:check`·`build` 통과. Vitest 23개 파일 675/675. CI 예산 변경 뒤 `playwright.config.test.ts` 21/21.
- 새 compose 스택(migrate V1~V11)에서 CI 순서대로 `verify-same-origin.sh --keep-stack` 959/959(93초), 이어서 같은 스택의 주입 origin e2e 60/60(1.8분). `incremental-sync.spec.ts`는 17.3초. 스택은 정리했다.
- `test-ci-scripts.sh` 147/147, `git diff --check` 통과.
- 리뷰 분류: 리뷰어 6명(blind·edge·verification × 백엔드·프런트엔드).
  - patch 20건: 전부 반영.
  - defer 4건: `deferred-work.md`.
  - reject: SPEC대로인 항목과 잡음.

## Suggested Review Order

**동기화 실행 (입구)**

- 진입점: tc 고정 → 창 → 수집 → 판정 → 환산 → 계산·발행을 lease 하나로 차례로 돈다.
  [`PlanSyncService.java:197`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L197)

- Bybit `tc`를 고정하고, 서명 호출 전에 DB 시각과 60초 안인지 판정한다.
  [`PlanSyncService.java:239`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L239)

- 30일·7일·24시간·60초 경계는 순수 함수 하나가 ms 단위로 가진다.
  [`SyncWindow.java:81`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncWindow.java#L81)

- admission: 쓰기 접근 → key 재전송 → 계획 → 합치기 → 사용 불가 → 장기 → 간격 → 접수.
  [`PlanSyncService.java:99`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L99)

- 단계마다·발행 직전 같은 허용 판정: 삭제·제한·계획·포인터·연결·membership.
  [`PlanSyncGuard.java:76`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncGuard.java#L76)

**원자 발행**

- 2.7 잠금 순서, 자기 입력 재확인 뒤 source·portfolio·계산·포인터를 한 트랜잭션에 쓴다.
  [`PlanSyncPublisher.java:88`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncPublisher.java#L88)

- 후보·판정·환산이 이 시도의 자식 결과이고 창·tc가 고정 범위와 같아야 쓴다.
  [`PlanSyncPublisher.java:155`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncPublisher.java#L155)

- 포인터 CAS(version+1)와 추가 전용 이력. 발행 시각은 `statement_timestamp()`다.
  [`PlanPublicationRepository.java:375`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationRepository.java#L375)

**계보와 완전성**

- 직전 후보 사건을 이어받고 창 사건을 더한다. 설명 없는 저장 사건은 검토다.
  [`InitialCollectionRepository.java:253`](../../backend/src/main/java/com/bokrilog/sync/internal/InitialCollectionRepository.java#L253)

- 독립 claim은 동기화 자식 행을 집지 않는다(수집·판정·환산 셋).
  [`InitialCollectionRepository.java:79`](../../backend/src/main/java/com/bokrilog/sync/internal/InitialCollectionRepository.java#L79)

- 근거: 증분 버전을 만든 작업·직전 계산·자식 결과·창에 묶어 한 계보로 판정한다.
  [`PlanPublicationService.java:867`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L867)

- 공식 버전 목록은 이력 표에서 읽고 적용 끝은 다음 버전의 발행 시각이다.
  [`PlanPublicationService.java:835`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L835)

**lease와 작업 표**

- heartbeat가 시도와 RUNNING 자식 lease를 함께 연장한다.
  [`PlanSyncRepository.java:177`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncRepository.java#L177)

- 다시 claim된 실행 중 시도는 이어 달리지 않고 중단으로 끝난다.
  [`PlanSyncRepository.java:144`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncRepository.java#L144)

- 스키마: 동기화 작업 표, 자식 연결 열, 계산 워터마크, 포인터 이력.
  [`V11__incremental_plan_sync.sql:18`](../../backend/src/main/resources/db/migration/V11__incremental_plan_sync.sql#L18)

**신뢰 축과 HTTP**

- 신선도 새 원인(`LAST_SYNC_*`·`LONG_GAP`·`SYNC_UNAVAILABLE`)과 다음 행동 `SYNC`·`CHOOSE_CONTINUATION`.
  [`DashboardTrust.java:130`](../../backend/src/main/java/com/bokrilog/sync/internal/DashboardTrust.java#L130)

- `POST /api/dashboard/sync`: 202·200·403·409·422·400, 전부 `no-store`, 로그 한 줄.
  [`DashboardController.java:227`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L227)

- 어댑터: 창을 `from`부터 돌고 시간 API를 부르지 않는다. 전송 거절을 먼저 본다.
  [`BybitAdapter.java:371`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L371)

**화면**

- 훅: 세 트리거, 즉시 `동기화 중`, 2초 폴링·보이는 시간 3분 상한, 완료 판정과 초점.
  [`useDashboardSync.ts:303`](../../frontend/src/session/useDashboardSync.ts#L303)

- 완료 판정: 성공은 제목, 그 밖은 요약으로 초점을 옮긴다(수동만).
  [`useDashboardSync.ts:197`](../../frontend/src/session/useDashboardSync.ts#L197)

- 대시보드 읽기 10초 상한, `sync` 블록은 모르는 값이 와도 결과를 거부하지 않는다.
  [`useDashboard.ts:450`](../../frontend/src/session/useDashboard.ts#L450)

- 상태 리전 문장은 동기화 단계를 알고, 폴링 중 "다시 불러오는 중"으로 바뀌지 않는다.
  [`useDashboard.ts:644`](../../frontend/src/session/useDashboard.ts#L644)

- 버튼·진행 표지·쿨다운·사유·오류 요약·장기 미동기화 설명.
  [`SyncControl.tsx:89`](../../frontend/src/components/SyncControl.tsx#L89)

- 신선도 축 바로 뒤의 자리.
  [`PlanResultPanel.tsx:237`](../../frontend/src/components/PlanResultPanel.tsx#L237)

**시험**

- 수동 동기화 v2, 합치기·재전송·충돌, v2 근거와 이력 1..n.
  [`IncrementalSyncTest.java:63`](../../backend/src/test/java/com/bokrilog/IncrementalSyncTest.java#L63)

- NORMAL 24시간·EXTENDED 7일 창과 v2·v3 근거(window>t0, 세 버전).
  [`IncrementalSyncTest.java:350`](../../backend/src/test/java/com/bokrilog/IncrementalSyncTest.java#L350)

- 발행 직전 경합(삭제·제한·연결·membership·포인터)이 늦은 Worker를 이긴다.
  [`IncrementalSyncTest.java:620`](../../backend/src/test/java/com/bokrilog/IncrementalSyncTest.java#L620)

- 실 스택: 1초 안의 `동기화 중`, v2 교체, 제목 초점.
  [`incremental-sync.spec.ts:93`](../../frontend/e2e/incremental-sync.spec.ts#L93)

- CI 예산: `backend-quality` 20분, 통합 회귀 6분·globalTimeout 5분.
  [`ci.yml:274`](../../.github/workflows/ci.yml#L274)
