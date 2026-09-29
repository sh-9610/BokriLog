---
title: '계획에 반영된 외부 자금흐름 조정 이력'
type: 'feature'
created: '2026-09-29'
status: 'done'
baseline_commit: '1fba948cbb9cee429e73fc7384022134f445afc5'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-9-calculation-evidence.md'
---

<frozen-after-approval reason="승인된 Epic 2 Story 2.10 범위와 UX-DR10 history-row·AD-20 존재 비노출 규칙">

## Intent

**Problem:** 대시보드와 계산 근거는 반영된 외부 자금흐름의 건수만 보인다. 사용자는 어떤 Bybit 입출금이 언제, 어떤 규칙과 가격으로, 얼마의 USD로 계획 기준선에 들어갔는지 확인할 수 없다. 그래서 거래소 기록과 계획 조정을 맞춰 볼 수 없다.

**Approach:** 서버가 현재 공식 계산 버전에 반영된 흐름을 읽기 전용 페이지 목록으로 준다(`GET /api/dashboard/adjustments`). 요청에는 2.9처럼 지금 보이는 묶음의 참조를 싣고, 서버는 그것이 현재 공식 포인터일 때만 답한다. 행마다 원 사건 → 판정 → 환산 → 계산의 계보가 함께 온다. 대시보드 패널 안의 `조정 이력 보기`를 누르면 같은 화면에서 목록이 펼쳐진다. 목록에는 유형 필터, 행 상세, `더 보기`가 있다.

## Boundaries & Constraints

**Always:**
- **참조와 존재 비노출:**
  - 쿼리는 셋이다.
    - 필수: `planId`·`pointerVersion`·`calculationId`.
    - `direction`: `ALL`|`IN`|`OUT`. 없으면 `ALL`이다.
    - `cursor`: 선택.
  - 200은 세 참조가 모두 그 사용자의 현재 공식 포인터와 같고 형식이 맞을 때뿐이다.
  - 그 밖의 경우는 모두 2.9와 글자까지 같은 404다. 여기에 드는 것:
    - 다른 사용자, 지난 버전, 없는 ID, 활성 계획 없음.
    - 형식 오류, 중복 매개변수, 파싱할 수 없는 쿼리.
    - 다른 범위의 cursor.
  - 원천 사건·판정·환산 ID는 입력으로 받지 않는다. 모르는 매개변수는 무시하며, 있어도 응답이 달라지지 않는다.
- **공식 행만:** 행은 그 계산 버전의 반영 흐름(`sync_plan_calculation_flow`)뿐이다. 다음은 행에도 합계에도 넣지 않는다.
  - 판정에서 제외된 사건.
  - 보류·검토 항목.
  - 다른 intent·후보·환산 시도의 사건.
- **한 묶음:** 읽기 전용 `REPEATABLE READ` 트랜잭션 하나에서 읽는다. 계정 접근은 그 앞뒤로 확인하고, 거부면 403이다.
  - 계보 판정은 페이지가 아니라 그 계산 버전의 전체 행에 건다.
  - 계보가 어긋나면 부분 목록을 내지 않는다. 503 `ADJUSTMENT_HISTORY_UNAVAILABLE`로 끝내고, 운영 로그에 `consistent=false`를 남긴다.
  - 어긋남에 드는 경우:
    - 조인이 누락되거나, 계산 버전이 가리키지 않는 판정·환산을 가리키는 행이 있다.
    - 원 사건·판정·환산·반영 행 사이에서 원천 ID, 사건 시각, 자산, 부호 있는 수량, USD 가치 중 하나가 다르다.
    - 판정이 외부 흐름이 아니거나, 부호가 방향과 맞지 않는다.
    - 가격 근거 종류와 가격 구간(leg) 수가 맞지 않는다.
    - 행 수가 계산 버전의 흐름 수와 다르다.
- **행 내용:**
  - 사건: 사건 시각, 유형(외부 입금·외부 출금), 자산, 부호 있는 원수량, 부호 있는 USD 가치.
  - 분류: Bybit 거래 기록 유형, 규칙 ID, 정책 버전.
  - 가격:
    - 정책 버전, 근거 종류, 추정 여부, 단가.
    - 경로, 그리고 구간마다 심볼·캔들 시각·종가·선택 규칙.
  - 시각과 반영:
    - 발견 시각: BokriLog가 그 사건을 처음 관측한 시각.
    - 반영 시각: 그 계산 버전이 공식이 된 시각.
    - 반영 calculation version과 계산 안 순번.
  - 참조: 원천 사건, Bybit 거래 기록 ID, 판정, 환산, 계산 ID.
- **추가 전용 변경 이력:**
  - 변경 표지는 두 조건이 함께 맞을 때 붙는다: 현재 환산이 이전 환산 버전을 잇고(`previous_conversion_id`), 그 사건의 값이 이전 버전과 달랐다.
  - 이전 버전은 최신부터 나열한다. 각 버전에 보이는 것:
    - 버전 ID, 처리 시각, 상태·이유 코드.
    - 이전값. 값이 없으면 없다고 보인다.
    - 바뀐 원인(`change_reason`)과 발견 시각. 발견 시각은 다음 버전의 처리 시각이다.
  - 이전 버전은 늘 `공식 계산에 쓰이지 않은 과거 버전`으로 보인다. 기존 행은 고치지 않는다.
- **정렬·cursor:**
  - 순서는 그 계산 버전의 `sequence`다.
    - 이 값이 `(event_time, provider_account_id, source_id)` 순서를 실현한다.
    - 한 계산 버전 안에서 사건마다 환산 버전은 하나뿐이다.
  - 한 페이지는 최대 20행이다.
  - cursor는 불투명 문자열이다.
    - 내용은 범위 digest와 마지막 순번뿐이다. digest는 `user_id`·참조·`direction`·정렬 정책 ID로 만든다.
    - 금융값과 ID 평문은 들어가지 않는다.
  - 범위가 다르거나 형식이 틀린 cursor는 404다. 클라이언트는 cursor를 해석하지 않는다.
- **합계:**
  - 건수와 부호 있는 USD 합은 필터 범위 전체를 대상으로 한다. 서버가 저장값으로 정확히 더하고 반올림하지 않는다.
  - 화면은 표시 반올림을 한 번만 하며, 표시값으로 다시 더하지 않는다.
  - 판정에서 제외한 원천 사건의 수는 행·합계와 분리해 따로 보인다.
- **표시:**
  - USD 값은 DISPLAY-1.0을 따른다(HALF_UP 2자리, 부호 유지).
  - 원수량·단가·종가는 반올림하지 않은 canonical 전체값이다.
  - 긴 ID·hash·코드는 mono 전체값으로 보이고 줄바꿈한다.
- **빈 상태와 검토 안내:**
  - 반영된 흐름이 0건이면 `확인된 외부 자금흐름이 없습니다`와 확인 구간(`t0`~`cashflowVerifiedThrough`)을 보인다.
  - 필터 결과만 비면 그 유형의 흐름이 없다고 말한다.
  - 입력 신뢰도가 `검토 필요`이면 행과 분리된 안내를 붙인다. 안내에는 원인 카피, 영향 구간, 그리고 원인과 다음 행동을 볼 수 있는 곳(대시보드의 입력 신뢰도)이 들어간다.
- **용어:** 금액 옆 도움말은 외부 입금·출금이 계획 기준선 조정일 뿐 투자 손익·수익률이 아니라고 말한다. 조정값을 평가하지 않는다.
- **화면:**
  - 토글과 초점:
    - `조정 이력 보기`는 토글이다(`aria-expanded`·`aria-controls`).
    - 열면 h4 제목으로 초점이 간다. `조정 이력 닫기`는 초점을 토글로 돌려준다.
  - 구조:
    - 유형 필터는 fieldset 안의 라디오다.
    - 목록은 한 DOM의 `ol`이다. 320px에서는 행마다 h5 제목이 붙은 group 카드다. 768px 이상에서는 같은 구조를 행 배치로 보인다.
  - 행 읽기 순서: 사건 시각 → 유형 → 원수량 → USD 가치 → 분류 → 가격 → 반영 버전.
  - 조작:
    - 행 상세는 행 안의 토글로 연다. 토글 이름에 사건 시각과 유형이 들어간다.
    - `더 보기`는 다음 페이지를 붙이고 초점을 첫 새 행으로 옮긴다.
  - 접근성: 320px·200%·400%에서 가로 스크롤이 없다. 조작 영역은 44px 이상이다. 색만으로 상태를 구분하지 않는다.
- **실패·거부:**
  - 실패는 대시보드 카드·상태를 바꾸지 않는다. 이미 받은 행은 유지하고, 재시도가 가능한지 말한다. 404이면 대시보드를 다시 불러오라고 안내한다.
  - 401·403이면 이력을 메모리에서 지우고, 대시보드에 다시 읽기를 요청한다.
  - 묶음이 바뀌면 이력을 버린다.
- **캐시·로그:**
  - 모든 응답은 `no-store`다. 금융값은 URL·로그·브라우저 저장소에 남지 않는다.
  - 운영 로그는 읽기마다 한 줄이다. 상태·HTTP 상태·지연·`consistent`만 남긴다.
  - 화면당 `role=status`는 하나다. 이력 읽기 문장은 그 안의 별도 요소로 둔다.
- **삭제:** 새 테이블이 없으므로 기존 `eraseSyncData`가 이력 원천을 지운다. 다른 사용자의 데이터는 남는다.

**Ask First:**
- 새 마이그레이션.
- 새 내부 화면 경로, 전역 내비게이션.
- 다른 모듈의 새 공개 API.
- CALC·DISPLAY·BYBIT-CASHFLOW·BYBIT-PRICE 정책 변경.
- 사건 ID를 받는 상세 엔드포인트.

**Never:**
- 다음은 실행하지 않는다: 새 동기화, 재계산, 재분류, 재환산, 수동 분류·가격 수정.
- 다음은 응답에도 화면에도 두지 않는다:
  - 미인식·격리된 원 사건의 원문과 원 금융 응답.
  - 지갑 주소, tx hash.
  - `user_id`, `provider_account_id`, Bybit UID, 자격정보.
  - `semantic_hash`, cursor hash.
- 세금·성과 분석, 무한 스크롤, 폴링은 하지 않는다.
- 과거·비공식 버전을 현재 공식 버전처럼 보이지 않는다.
- 다른 모듈의 테이블을 직접 읽지 않는다.
- 없는 화면으로 가는 링크를 두지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| CURRENT | 현재 참조, 흐름 N건 | 200, 순번 순 최대 20행 + `nextCursor`, 필터 범위 건수·합계 | N/A |
| PAGED | 앞 페이지의 `nextCursor` | 이어지는 행, 중복·누락 0, 마지막 페이지는 `nextCursor=null` | N/A |
| SAME_TIME | 같은 ms 사건 여럿 | `source_id` 순으로 안정, 페이지 경계에서도 같음 | N/A |
| FILTER | `IN` / `OUT` | 그 방향 행과 그 범위 합계만 | 다른 방향으로 만든 cursor는 404 |
| EMPTY | 반영 흐름 0 | 행 0, 빈 문장, 확인 구간 | N/A |
| EXCLUDED | 판정 제외 사건 있음 | 행·합계에 없음, 제외 건수 별도 | N/A |
| REVISED | 현재 환산이 이전 환산을 이음 | 변경 표지, 이전→새 연결, 과거 버전 표지 | N/A |
| TAMPER | 다른 사용자·지난 버전·없는 ID·형식 오류·남의 cursor·변조 cursor | 같은 404 | 존재 비노출 |
| BROKEN | 어느 페이지든 계보 불일치 | 부분 목록 없음, `consistent=false` | 503 `ADJUSTMENT_HISTORY_UNAVAILABLE` |
| DELETING | 앞·뒤 확인 중 하나라도 삭제 | 403, 이력 없음 | 클라이언트가 이력 삭제 |
| READ_FAIL | 네트워크·5xx·503·404 | 대시보드 불변, 받은 행 유지, 재시도 | 404는 재시도 없이 대시보드 다시 불러오기 |

</frozen-after-approval>

## Code Map

**계약**
- `epics.md`
  - 2101-2190: Story 2.10 AC-1~AC-9와 테스트 기준.
  - 115: UX-DR10 `history-row`.
  - 85, 89, 94: AR-5, AR-9, AR-14.
- `EXPERIENCE.md`
  - 43: 대시보드·신뢰 상세에서 진입한다.
  - 85, 216, 224, 226: 표·카드 ARIA와 행 펼침.
  - 187: 빈 상태와 실패 시 행 유지.
  - 205: 무한 스크롤 금지.
  - 217: 숫자는 부호를 유지하고 자르지 않는다.
- `DESIGN.md`
  - 206-209: `history-row` 토큰.
  - 241: tabular-nums.
  - 243: 긴 식별자.
  - 268: 읽기 순서.
- `EPIC-IMPLEMENTATION-READINESS.md`
  - 37-41: 방향·수량·원천 identity.
  - 45-51: 가격 근거.
  - 64-69: canonical decimal과 표시 반올림.
  - 77: `ti=t0`는 흐름이 아니다.

**백엔드 스키마** (읽기만 한다. `backend/src/main/resources/db/migration/`)
- 원 사건 `sync_initial_source_event`(V7:34-51)
  - 컬럼: `event_type`, `currency`, `cash_flow`, `transaction_time`, 발견 시각 `first_observed_at`(46).
  - identity는 `UNIQUE(provider_account_id, source_id)`이고 revision 열은 없다.
- 후보 소속 `sync_initial_candidate_event`: V7:126.
- 판정 `sync_cashflow_classification_event`(V8:47-69)
  - 컬럼: `verdict`, `rule_id`, `policy_version`, `signed_amount`, `asset_code`, `occurred_at`.
  - 제외 요약은 `sync_cashflow_exclusion_summary`(V8:83)다.
- 환산 bundle: V9:167-172(`previous_conversion_id`, `change_reason`, `processed_at`).
- 환산 흐름 `sync_cashflow_conversion_flow`(V9:200-232)
  - 컬럼: `status`, `reason_code`, `evidence_type`, `route_id`, `usd_unit_price`, `usd_value`, `estimated`.
- 가격 구간 `sync_cashflow_conversion_leg`(V9:235)
  - 컬럼: `symbol`, `candle_start_at`/`candle_end_at`, `close_price`, `selection_rule`, `lag_minutes`.
- 계산 입력 `sync_calculable_cashflow`: V9:262-285.
- 계산 버전 `sync_plan_calculation`: V10:167.
  - `candidate_id`/`classification_id`/`conversion_id`를 담는다.
- 반영 행 `sync_plan_calculation_flow`(V10:253-272)
  - PK `(calculation_id, source_event_id)`와 `UNIQUE(calculation_id, sequence)`.
  - 자산·수량·규칙 열이 없으므로 조인해서 얻는다.
- 공식 포인터 `sync_official_plan_result`: V10:279-290(`published_at`).
- 조인 경로: 포인터 → 계산 → 반영 행 → `sync_calculable_cashflow`·환산 흐름(`(conversion_id, source_event_id)`) → leg.
  - 판정은 `(c.classification_id, source_event_id)`로 잇는다. FK가 없는 논리 연결이다.
  - 후보 소속은 `(c.candidate_id, event_id)`로 잇고, 원 사건은 그 `event_id`다.

**백엔드 코드** (`backend/src/main/java/com/bokrilog/`)
- `sync/internal/PlanPublicationService.java`
  - `evidence`(629-643)가 그대로 따를 입구 모양이다: 앞뒤 `readable`(863), `dashboardReads` RR template(79, 95-97), 참조 일치 판정(`lineage` 665-671), `belongs`(838-845).
  - sync는 plan을 읽지 못한다. 활성 계획은 `PlanPublicationPort.activePlan`으로 받는다.
- `sync/internal/PlanPublicationRepository.java`
  - 반영 순서: 103-107.
  - 반영 USD는 계산기 값이다: 340. 환산값과 십진수로 비교한다.
  - `pointer`(376), `calculation`(379-391), `appliedFlowCounts`(532), `erase`(562-568).
  - 이력 쿼리는 여기에 더하거나 같은 패키지의 새 repository에 둔다.
- `sync/internal/CashflowConversionRepository.java:389-450` -- 이전 버전 사슬과 `change_reason`을 결정한다.
  - 미종료 캔들로 `CONVERSION_REQUIRED`가 된 뒤 재시도해 환산되면 `EVIDENCE_CHANGE`로 이어진다.
- `sync/internal/CashflowDecisionTable.java:10-54` -- `EXCLUDED`와 `REVIEW_REQUIRED` 판정.
- `sync/SyncJobApi.java:210-320` -- 2.9 모델·결과 sealed 타입(`EvidenceReference` 214).
  - 이력 모델과 결과 타입을 같은 모양으로 더한다.
  - 파사드는 `SyncJobService.java:214-216`이다.
- `DashboardController.java`
  - `EVIDENCE_PATH`(59), 핸들러(201-239), 로그(241-245).
  - `referenceOf`(258-285): 단일 값만 받는다. UUID·버전 정규식(66-69), 파싱 예외는 null.
  - 새 핸들러와 매개변수 파서를 같은 관용으로 둔다. cursor 문자열은 그대로 sync에 넘긴다(sync는 web에 의존할 수 없다).
- `AuthSecurityConfig.java:225-239,1228-1230` -- `/api/dashboard/**`가 이미 인증·CSRF 술어에 걸린다. 주석만 갱신한다.
- `DevBybitStub.java`
  - `Scenario`는 150-294, 키는 `<token>.<uid>`다.
  - `transactionLog`는 402-458, `eventPage`는 460-466이다.
  - 발행 가능한 다중 흐름 시나리오가 없어 새로 필요하다.

**백엔드 시험**
- `FirstSnapshotTest.java`
  - 흐름 주입:
    - `insertCandidateEvent`(251)로 유형을 직접 줄 수 있다. 제외 사건은 `FEE`·`TRADE`다.
    - `refreshCandidateHash`(269), `Flow`(302).
    - `convertedIntent`/`convert`/`injectFlows`(3718-3755)의 흐름 유형은 부호가 정한다. 제외 사건을 넣으려면 수집과 판정 사이에 끼우는 변형이 필요하다.
  - 발행과 읽기:
    - `shiftStart`(3772), `publish`(3785), `awaitPublication`(3791).
    - `referenceOf`(3655), `evidenceOf`(3660), `query`(3668-3680), 로그 필터(3688).
  - 추정 흐름: 1805. 시간 도우미 `clearMarketEvidence`(1125), `closedTenMinuteBoundary`(1119).
  - 같은 404: 3266-3298. 쿼리는 원시 소켓 `rawGet`(3641)으로 보낸다.
  - 계보 손상: `Corruption`(3367-3474, `session_replication_role=replica`).
  - 삭제: 2487-2527.
    - 2513은 표 전체가 비었다고 단정한다. 보존 사용자에게 흐름이 있으면 계산 범위로 좁혀야 한다.
- `BybitStubConfiguration.java`: `serverTimeMode`(124), `requestCount`(96-112).
- `ModuleBoundaryTest.java`
  - 79-95: 루트는 `SyncJobApi` 타입만 쓴다.
  - 136-155: sync에 web·servlet 의존이 없다.

**프런트엔드** (`frontend/src/`)
- `components/PlanResultPanel.tsx`
  - 본문 362-459. 지표 절(419-432) 안의 423에 `CalculationEvidence`가 있다. 그 바로 뒤에 둔다. 새 h3는 만들지 않는다.
  - 초점 effect는 376-386이다.
- `session/useCalculationEvidence.ts` -- 새 훅의 본보기다.
  - 묶음 키: 326-329. 키가 바뀌면 렌더 중에 리셋한다: 338-341.
  - layout effect 가드와 중단: 345-352.
  - `read`는 354-400이다. 401·403은 `onDenied`, 404는 재시도 불가, 파싱은 전부 아니면 무다.
  - `useDashboardEvidence`(424-426)가 `dashboard.reload`를 `onDenied`로 넘긴다.
- `components/CalculationEvidence.tsx`(+`.module.css`)
  - `Id` mono(82-84), `Part` h5 group(109-127), `Failure` 최소 참조(462-482).
  - 초점 effect: 512-526.
  - 재시도 `aria-disabled`: 591-602.
  - CSS: `.facts`(63-84), `.id`(90-96, `overflow-wrap:anywhere`), `.action` 44px(142-165).
- `components/DashboardStatusText.tsx:16-37` -- 이력 문장용 세 번째 요소를 더한다.
  - 연결 지점은 `SignedInScreen.tsx`(46-48, 114-143, 158)와 `OnboardingScreen.tsx`(43-45, 152)다.
- `session/useDashboard.ts`
  - `PlanResult`(46-80): `trust.input`, `basisAt`, `cashflowVerifiedThrough`, `flowCount`.
  - `record`(83), `strings`(88), `reload`(366-370).
- `content/calculationEvidence.ts` -- `evidencePath`(27-34), `lookup`(42-48). 새 `content/adjustmentHistory.ts`의 모양이다.
- `format/decimal.ts`
  - `formatAmount`(48-52)는 2자리로 반올림하므로 원수량에 쓰면 안 된다.
  - `formatSeoulInstant`(97-127), `isCanonicalDecimal`(21).
- `styles/tokens.css` -- `--component-history-row-*`(223-225), mono(74-77), `--space-target-min`(96).
  - 선례: `SettingsScreen.module.css:205-208`.
- 시험
  - `test/setup.ts`
    - `stubCalculationEvidence`는 108-110이다.
    - 질의 responder(333-339)는 `?` 앞 경로로 맞춘다.
    - 저장소 관측: 264-281.
  - `test/planResultFixture.ts`: 67-289.
  - `CalculationEvidence.test.tsx`: Harness는 65-119, 저장소 0건·금지어는 686-695다. 금지어에 `성공|실패|정확`이 있다.
  - `PlanResultPanel.test.tsx:54` -- `closedEvidence()`를 약 11곳에서 넘긴다.
- `e2e/first-snapshot.spec.ts`
  - `connectAndSavePlan`(36-97, 키 53), `publishAndOpenResult`(150-164).
  - `expectKeyboardOrder`(293-318)는 탭 정지점을 정확히 단정하므로 새 버튼을 더해야 한다.
  - `expectEvidence`(329-467), `expectNoHorizontalScroll`(469-482).

## Tasks & Acceptance

**Execution:**
- [x] `sync/SyncJobApi.java`, `SyncJobService.java`, `sync/internal/PlanPublicationService.java`, `PlanPublicationRepository.java`(또는 새 `sync/internal` repository), 새 `sync/internal/AdjustmentCursor.java`
  - 이력 읽기의 순서: 앞 접근 확인 → 참조·cursor 판정 → RR 한 묶음 → 뒤 접근 확인.
  - 전체 계보 일관성과 필터 범위 합계를 집계 쿼리 하나로 낸다.
  - 행은 `sequence` 키셋으로 21개를 읽어 다음 cursor를 정한다.
  - 행마다 leg와 이전 환산 버전 사슬을 붙이고, 제외 건수를 낸다.
- [x] `DashboardController.java`, `AuthSecurityConfig.java`(주석)
  - `GET /api/dashboard/adjustments`: 200/403/404/503/500, 모두 `no-store`.
  - 로그 한 줄: `adjustment history read: state= status= latencyMs= consistent=`.
- [x] `DevBybitStub.java` -- e2e용 `ADJUSTMENTS` 시나리오를 더한다.
  - USDT 입금·출금 23건. 같은 ms 사건 둘을 넣고, 발행 가능하게 둔다.
- [x] `FirstSnapshotTest.java` -- I/O matrix 전 행.
  - 흐름 25건으로 페이지 둘을 만들고 이어 읽어 중복·누락이 0인지 본다.
  - `IN`/`OUT` 필터와 그 합계.
  - 제외 사건이 섞인 변형.
  - REVISED: 미종료 캔들 뒤 재시도, 또는 계보를 지키는 SQL fixture.
  - BROKEN 변형마다 503.
  - 404 본문은 모든 변조에서 같아야 한다.
  - 로그 캡처, 삭제 앞뒤와 보존 사용자.
  - 응답에 `user_id`·`provider_account_id`·자격정보가 없다.
  - 정확 합계: 0.005씩 세 건 → `0.015`.
- [x] 새 `content/adjustmentHistory.ts`, 새 `session/useAdjustmentHistory.ts`, `format/decimal.ts`
  - 경로·카피·코드 닫힌 맵(모르는 코드는 안전 카피).
  - 훅: 참조 대조 검증, 순번 가드, 중단, 필터 변경 시 첫 페이지부터 다시 읽기.
    - `더 보기`에서 순번이 역행하거나 중복되면 실패로 본다.
    - 실패해도 받은 행을 유지한다.
  - 반올림하지 않는 수량 표시 함수를 더한다.
- [x] 새 `components/AdjustmentHistory.tsx`(+`.module.css`), `PlanResultPanel.tsx`, `DashboardStatusText.tsx`, `SignedInScreen.tsx`, `OnboardingScreen.tsx`
  - 토글, 영역, 설명·합계·제외 건수·검토 안내·빈 상태, 필터, 행 카드와 상세, `더 보기`, 닫기.
  - 초점 이동, status 세 번째 요소.
- [x] 프런트엔드 시험 (`AdjustmentHistory.test.tsx`, `useAdjustmentHistory.test.tsx`, `setup.ts`, `planResultFixture.ts`, 기존 패널·화면 시험의 prop)
  - Vitest:
    - 행 읽기 순서와 제목.
    - 방향별 부호, 원수량 전체값, 합계 표시 반올림 한 번.
    - 필터, 페이지, 상세, 변경 표지, 빈 상태 둘, 검토 안내.
    - 실패·404·403, 묶음 교체.
    - 저장소 0, 금지어.
  - e2e(`adjustments`):
    - 키보드로 열기 → 20행 → `더 보기` → 23행.
    - 필터, 상세, 닫기와 초점.
    - 320·640px, axe.
    - `expectKeyboardOrder` 갱신.

**Acceptance Criteria:**
- Given 흐름이 반영된 발행을 마친 사용자, when `조정 이력 보기`를 누르면, then 같은 화면에 설명과 합계, 그리고 필터와 행이 읽기 순서대로 펼쳐진다. 행 상세는 원 사건 → 판정 → 환산 → 계산 참조를 보인다. `조정 이력 닫기`는 초점을 토글로 돌려준다.
- Given 첫 페이지를 받은 화면, when `더 보기` 읽기가 실패하면, then 받은 행과 대시보드 카드·세 축·상태 문장의 결과 부분은 그대로다. 재시도가 제자리에 남는다.
- Given 어떤 변조 참조나 cursor, when 이력을 요청하면, then 응답은 404 한 가지이며 본문이 글자까지 같다.
- Given 소수점 셋째 자리 이하가 있는 USD 가치들, when 합계를 보이면, then 저장값의 정확한 합을 한 번만 반올림한 값이다. 표시값을 더한 값이 아니다.

## Spec Change Log

## Design Notes

**새 경로 대신 페이지 안 영역이다.**
- UX는 조정 이력을 전역 내비게이션의 목적지로 그리지만, 앱에는 아직 전역 내비게이션이 없고 목업도 없다.
- 패널 안에 두면 두 가지를 얻는다.
  - 이력이 지금 보이는 공식 묶음에 묶이고, 두 랜딩 화면에 한 번에 적용된다.
  - 문서를 옮기지 않으므로 메모리의 금융값을 다시 읽지 않는다.
- 새 경로와 전역 내비게이션은 Ask First로 남긴다.
- AC-8은 "표 또는 목록"을 허용한다. 표와 카드를 두 벌 두면 보조기술이 같은 내용을 두 번 읽으므로, 한 DOM의 목록을 반응형으로 배치한다.

**cursor 위치가 `sequence`인 이유**
- 2.11 전에는 공식 계산 버전이 하나이고, 그 안에서 `valuation_version`(환산 버전)은 사건마다 하나다. 그래서 셋째 정렬 키가 동률을 만들지 않고, `sequence`가 승인된 순서와 같다.
- 새 계산 버전이 생기면 포인터가 바뀌고, 참조는 404가 된다. 클라이언트는 대시보드를 다시 읽고 첫 페이지부터 읽는다. 두 버전의 행이 섞이지 않는 것이 "중복·누락 최소화"의 실현이다.
- cursor는 서명하지 않는다.
  - 범위 digest가 요청자의 `user_id`를 포함하므로, 남의 cursor는 대조에서 떨어진다.
  - 자기 범위의 순번을 변조해도 자기 행만 보인다. 그래서 비밀 키도 새 구성도 필요 없다.

**변경 이력은 환산 사슬로 실현한다.**
- 원 사건의 revision은 아직 없다. `source_revision`은 1로 고정이고, 같은 ID에 다른 내용이 오면 `PROVIDER_ID_CONFLICT` 검토가 된다.
- 판정에는 이전 버전 링크가 없다.
- 지금 실제로 생기는 변경은 환산 bundle의 `previous_conversion_id` 사슬뿐이다. 예를 들어 미종료 캔들로 `CONVERSION_REQUIRED`가 되고, 재시도해 `EVIDENCE_CHANGE`로 환산되는 경우다.
- 사건 revision과 재판정의 연결은 2.11·2.12가 늘린다.

**보류 항목은 구조적으로 공식 계보에 없다.**
- 2.7은 완전하고 계산 가능한 계보만 발행한다. 보류는 다른 시도에만 있고, AC-1 규칙에 따라 섞지 않는다.
- 지금 입력 신뢰도의 `검토 필요`는 `TARGET_NOT_POSITIVE`다. 출금이 기준선을 0 이하로 만든 결과라서 자금흐름 탓이다.
- 그래서 안내는 서버에 새 필드를 두지 않고, 메모리의 `trust.input`을 쓴다. 원인은 2.8의 닫힌 카피 맵을 재사용하고, 영향 구간은 확인 구간(`t0`~`cashflowVerifiedThrough`)이다.

**시각의 뜻**
- 발견 시각: `first_observed_at`.
- 반영 시각: 포인터의 `published_at`. 계산 생성 시각과는 다르다.

**원수량은 반올림하지 않는다.** DISPLAY-1.0의 2자리는 USD 금액의 표시 규칙이다. 원수량을 2자리로 줄이면 `0.00012 BTC`가 `0.00`이 된다. 이는 EXPERIENCE:217("자르지 않는다")과 FR-10(원수량 보존)에 어긋난다.

**Bybit 거래 기록 ID는 보인다.** 사용자가 거래소 기록과 맞춰 보는 유일한 열쇠이기 때문이다. 계정을 가리키는 UID·`provider_account_id`와 달리 이 ID는 사건 하나를 가리킨다. 입력으로는 받지 않는다.

**응답 모양** (요지):
```json
{ "planId": "…", "pointerVersion": 1, "calculationId": "…", "direction": "ALL",
  "appliedAt": "…", "coverage": { "from": "…t0", "through": "…tc" },
  "summary": { "count": 23, "netUsdValue": "1234.5", "excludedCount": 2 },
  "rows": [ { "sequence": 1, "occurredAt": "…", "direction": "IN", "asset": "BTC",
      "signedQuantity": "0.00012", "usdValue": "7.80003", "classification": {…},
      "price": { "evidenceType": "SPOT_KLINE_CLOSE", "estimated": true, "legs": […] },
      "discoveredAt": "…", "refs": {…}, "revisions": [] } ],
  "nextCursor": "opaque-or-null" }
```

## Verification

**Commands:**
- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 build` -- expected: Testcontainers를 포함해 전체 통과, 실패 0.
- Node 24: `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run test && npm --prefix frontend run build` -- expected: 모두 통과.
- 수동 compose 스택(migrate)을 올린 뒤 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080`으로 전체 e2e를 `--workers=1`로 돌리고, 이어서 `deploy/compose/verify-same-origin.sh`를 돌린다 -- expected: 통과, 스택 정리.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check` -- expected: 통과.

**구현에서 확정한 것 (frozen 블록 밖):**
- 입구는 새 `sync/internal/AdjustmentHistoryService`다. `PlanPublicationService`가 이미 900줄이라 나눴고, `belongs`·`notation`만 패키지 범위로 열어 같은 규칙을 쓴다.
  - 판정 순서: 계정 접근 → 쿼리 형식(컨트롤러) → cursor 범위(트랜잭션 전, DB를 읽지 않는다) → 현재 포인터 → 계보 → 페이지 → 뒤 접근 확인.
  - 삭제 중인 계정은 형식이 틀린 참조·cursor에도 403이다.
- 쿼리 규칙.
  - `direction`은 없으면 `ALL`, 있으면 정확히 `ALL`·`IN`·`OUT`(대문자) 하나다. 소문자·빈 값·중복은 404다.
  - `cursor`는 중복이면 404이고, 값은 그대로 sync로 넘긴다. 빈 값·형식 오류·다른 범위는 sync가 404로 판정한다.
  - 컨테이너가 파싱하지 못하는 쿼리는 2.9와 같은 404다.
- cursor 형식: 버전 1바이트 + 범위 SHA-256 32바이트 + 마지막 순번 4바이트를 base64url(패딩 없음) 50자로 쓴다.
  - digest는 `user_id`·`planId`·`pointerVersion`·`calculationId`·`direction`·`ADJUSTMENT-ORDER-1.0`을 길이 접두 필드로 만든다.
  - 다시 부호화해 같은 글자일 때만 받는다. 마지막 글자의 쓰지 않는 비트만 바꾼 다른 표기를 막기 위해서다.
- 계보 판정(하나만 어긋나도 503, 전체 행 대상).
  - 묶음: `belongs`, portfolio 포인터(AD-23), 판정은 COMPLETE·같은 후보·기록된 정책·intent·portfolio·연결·계정이고 검토 0이며 입금+출금 = 흐름 수이고 `excluded_count`가 `EXCLUDED` 판정 사건 수와 같으며, 환산은 calculable·그 판정·가격 정책·같은 소유이고 흐름 수·추정 수가 계산 버전과 같다.
  - 행 집계 쿼리 하나(`AdjustmentHistoryRepository.totals`): 반영 행 → 계산 가능 행·환산 흐름·판정 사건·후보 소속·원 사건·leg 수를 LEFT JOIN한다. 원천 ID·semantic hash·사건 시각(`(t0, cashflow_verified_through]` 안 — 응답이 `coverage`로 보이는 같은 열)·자산·부호 있는 수량·USD(십진수 비교)가 모든 연결에서 같고, 판정이 외부 흐름이며 Bybit 유형·부호가 방향과 맞고, 근거 종류·추정 여부·경로·leg 수(직접 0, `SPOT_ASSET_USDT` 1, `SPOT_ASSET_USDC_USDCUSDT` 2, 0부터 연속)가 맞아야 한다. 경로 ID는 `PriceConversionPolicy` 상수를 매개변수로 넘긴다.
  - 순번은 `1..n` 연속이고 `(occurred_at, source_id COLLATE "C")` 순서를 따른다. 입금 수는 판정의 `deposit_count`와 같다.
  - 환산 사슬: 같은 사용자·판정 안에서만 걷고, 현재 버전을 포함해 최대 64개만 읽으며 그 안에 `NULL`로 끝나야 한다. 교체한 버전의 이유가 있어야 하고, 과거 버전마다 흐름 수가 같으며 모든 반영 사건의 흐름을 가져야 한다.
- 합계는 PostgreSQL `numeric` sum을 canonical 표기로만 바꾼다. 필터의 범위 전체이며 페이지마다 같은 값이다.
- 행 필드 출처.
  - USD는 반영 행의 값(계산기가 적용한 값)이고, 수량·자산은 계산 가능 행, Bybit 유형과 발견 시각은 원 사건(`first_observed_at`), 규칙·판정 정책은 판정 사건, 근거·추정·단가·경로는 환산 흐름, leg는 환산 leg다.
  - 반영 시각은 포인터의 `published_at`이며 응답 최상위 `appliedAt` 하나로 보낸다.
- 변경 표지: 현재 환산이 이전 버전을 잇고, 사슬의 어느 과거 버전에서든 그 사건의 값이 달랐거나 없었을 때 붙는다. 그때만 과거 버전 전부를 최신부터 싣는다. 값이 모두 같은 행은 표지도 목록도 없다.
- 운영 로그: `adjustment history read: state=FOUND|NOT_FOUND|INCONSISTENT|DENIED|ERROR status= latencyMs= consistent=true|false|unknown`.
- 화면.
  - `조정 이력 보기`는 `계산 근거 보기` 바로 뒤다. 영역은 h4, 행 제목은 h5 group, 필터는 fieldset 라디오 셋이다.
  - 읽기 순서: 설명·용어 도움말 → 검토 안내(`검토 필요`일 때만) → 합계 → 필터 → 행 → 행동 칸 → 닫기.
  - 행동 칸은 버튼 하나다. 첫 페이지 실패면 `조정 이력 다시 불러오기`, 그 밖에는 `더 보기`다. `더 보기` 실패는 행을 유지하고 `더 보기`가 제자리에서 같은 페이지를 다시 요청한다. 두 종류는 `key`가 달라, 재시도가 성공해 사라지면 초점이 영역 제목으로 간다.
  - 실패 문장은 둘이다. 첫 페이지 실패는 받은 행이 없으므로 행을 말하지 않고, `더 보기` 실패만 "이미 받은 행은 그대로"라고 말한다. 상태 리전 문장도 같다(`status.retryable`·`status.moreRetryable`).
  - 판정에서 제외한 원천 사건 수는 필터의 합계 묶음 밖에 "유형과 관계없이 판정에서 제외한 원천 사건 N건"으로 따로 둔다. 필터를 바꿔도 같은 수다.
  - 행 제목과 상세 토글 이름에 `순번 N`이 앞선다. 같은 밀리초·같은 유형의 두 행도 보조기술에서 갈린다.
  - 가격 구간의 선택 규칙은 지연이 0분이면 지연 문구를 붙이지 않는다. 지연의 상한은 가격 정책의 것이라 화면이 검사하지 않는다(음이 아닌 정수만 본다).
  - 변경 이력 상세는 현재 버전의 값과 과거 버전의 값을 둘 다 표시 반올림 값 옆에 canonical 전체값("전체값 … USD")으로 보인다. 둘째 자리 밑에서만 바뀐 값도 다르게 보인다.
  - 필터를 바꾸면 받은 행을 버리고 새 필터의 첫 페이지를 읽는다. 진행 중인 읽기는 중단한다.
  - 다음 페이지는 요약·범위·반영 시각이 첫 페이지와 같고, 순번이 올라가며, 사건이 겹치지 않아야 붙는다. 마지막 페이지에서 행 수가 요약 건수와 다르면 실패다.
  - 원수량·단가·종가는 `formatQuantity`(반올림 없음, 정수부 쉼표만)이고 USD는 `formatAmount`다.
- 기존 시험 변경.
  - `CalculationEvidence.test.tsx`의 재시도 뒤 초점 단정을 `waitFor`로 기다린다. 전체 병렬 실행의 부하에서 수동 effect보다 단정이 먼저 도는 경합이 드러났다(단독 실행은 통과).
  - `erasureRemovesTheUsersPublicationAndKeepsOtherUsers`는 보존 사용자에게 흐름 하나를 주고, 표 전체가 비었다는 단정을 삭제된 계산 범위로 좁혔다.
- `DevBybitStub`의 `ADJUSTMENTS`는 23행이 요청의 `[startTime, endTime]` 창에 모두 들어갈 때만 내보낸다. 들어가지 않는 창에서 같은 ID를 다른 시각으로 다시 내지 않는다(ID 충돌이 된다).
- 두-leg 경로(`SPOT_ASSET_USDC_USDCUSDT`)는 스텁 시세가 ETH에 대해 이미 제공한다(ETHUSDT 미상장 → ETHUSDC × USDCUSDT). 그래서 BROKEN 시험이 그 경로를 정상 계보로 한 번 지나고, 한-leg 경로 이름으로 바꾸는 손상을 거절한다.
- 알려진 한계와 다시 볼 곳.
  - (a) 페이지마다 계산 버전 전체의 계보를 다시 판정한다. 비용이 반영 행 수에 비례하므로, 이력이 커지는 2.11에서 다시 본다(판정 결과를 계산 버전 단위로 재사용하는 방법 등).
  - (b) "공식 계산에 쓰이지 않은 과거 버전"과 cursor의 전제("한 계산 버전 안에서 사건마다 환산 버전은 하나")는 계획의 공식 버전이 하나일 때만 성립한다. 2.11·2.12·2.15가 다시 봐야 한다. `SyncJobApi.AdjustmentHistory.Revision`과 `AdjustmentCursor.ORDER_POLICY`의 javadoc에도 한 줄씩 적었다.
  - (c) 영구히 끊긴 환산 사슬도 다른 불일치처럼 재시도 가능한 503으로 보고된다. 재분류가 정당하게 사슬을 가로지를 수 있게 되는 2.12에서 다시 본다.


**실행 결과 (리뷰 patch 반영 후, 최종 트리):**
- 백엔드 `build`(`--no-parallel --max-workers=1`) 통과: 51개 시험 클래스, 752 tests, 실패·skip 0.
  - 그중 `FirstSnapshotTest` 117(새 이력 시험 여섯, BROKEN 손상 29종 포함), `AdjustmentHistoryServiceTest` 7(계보·묶음 불일치 43종 포함), `AdjustmentCursorTest` 3.
- 프런트엔드 (Node 24): `typecheck`·`lint`·`lint:css`·`format:check`·`build` 통과. Vitest 21개 파일 546/546.
- 수동 compose 스택(migrate V1~V10, 런타임 생성 key ring) 뒤 주입 origin e2e `--workers=1`: 59/59(`조정 이력` 시험 포함), 스택 정리.
- 이어서 `verify-same-origin.sh`: 959개 중 902개 통과. 실패 57건은 전부 Bybit 연결 확인 문구 단계(400 ≠ 422)에서 시작하는 연쇄다. Windows Git Bash의 CP949 결함(Story 2.6이 미룬 항목)과 같은 수이고, 대시보드·이력 단정은 실패 목록에 없다. Linux CI에서 확인해야 한다. 스택은 정리됐다.
- `test-ci-scripts.sh` 147/147, `git diff --check` 통과.
- 변이 확인(리뷰 전 실행, 되돌리면 의도한 시험이 실패했고 모두 복원했다): 백엔드 환산 사슬 끝 판정, 프런트엔드 다음 페이지 순번·중복 가드와 묶음 교체 시 폐기.

## Suggested Review Order

**이력 읽기 (입구)**

- 입구: 앞 접근 확인 → cursor 범위 → RR 한 묶음 → 뒤 접근 확인.
  [`AdjustmentHistoryService.java:81`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentHistoryService.java#L81)

- 참조가 현재 포인터와 같아진 뒤로는 모든 어긋남이 503이다.
  [`AdjustmentHistoryService.java:129`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentHistoryService.java#L129)

- 전체 행 계보를 집계 한 번으로 판정하고, 필터 범위의 정확한 합계도 함께 낸다.
  [`AdjustmentHistoryRepository.java:92`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentHistoryRepository.java#L92)

- 행 하나가 지켜야 할 술어: 조인, 원천 ID·시각·수량·USD, 방향, leg 수.
  [`AdjustmentHistoryRepository.java:129`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentHistoryRepository.java#L129)

- `sequence` 키셋 페이지이며, 21행을 읽어 다음 페이지가 있는지 안다.
  [`AdjustmentHistoryRepository.java:207`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentHistoryRepository.java#L207)

**변경 이력 (환산 사슬)**

- 같은 판정 안에서만 걷고, `MAX_CHAIN` 안에서 끝나야 하는 재귀 사슬.
  [`AdjustmentHistoryRepository.java:175`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentHistoryRepository.java#L175)

- 과거 버전에서 값이 달랐거나 없었을 때만 표지와 목록이 붙는다.
  [`AdjustmentHistoryService.java:214`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentHistoryService.java#L214)

**cursor**

- 서명 없는 불투명 cursor: 범위 digest에 요청자 `user_id`가 들어간다.
  [`AdjustmentCursor.java:80`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentCursor.java#L80)

- 다시 부호화해 같은 글자일 때만 받는다. 다른 범위는 404다.
  [`AdjustmentCursor.java:57`](../../backend/src/main/java/com/bokrilog/sync/internal/AdjustmentCursor.java#L57)

**HTTP·보안·관측**

- `GET /api/dashboard/adjustments`: 200·403·404·503·500, 전부 `no-store`.
  [`DashboardController.java:369`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L369)

- 단일 값 매개변수만 받고, 대문자 방향만 허용한다. 파싱할 수 없으면 같은 404다.
  [`DashboardController.java:422`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L422)

- 운영 로그는 한 줄이고 네 필드만 남긴다.
  [`DashboardController.java:411`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L411)

- 응답 계약: 이력 모델과 결과 sealed 타입.
  [`SyncJobApi.java:348`](../../backend/src/main/java/com/bokrilog/sync/SyncJobApi.java#L348)

**화면**

- 훅: 커밋에 맞춘 키 ref, 중단, 필터를 바꾸면 첫 페이지부터, 거부되면 대시보드를 다시 읽는다.
  [`useAdjustmentHistory.ts:326`](../../frontend/src/session/useAdjustmentHistory.ts#L326)

- 응답을 보이는 묶음과 대조하고, 행 하나라도 틀리면 페이지 전체를 거절한다.
  [`useAdjustmentHistory.ts:198`](../../frontend/src/session/useAdjustmentHistory.ts#L198)

- 다음 페이지는 순번이 올라가고 겹치지 않을 때만 붙는다.
  [`useAdjustmentHistory.ts:240`](../../frontend/src/session/useAdjustmentHistory.ts#L240)

- 영역: 토글, 설명, 합계, 필터, 행, 행동 칸, 닫기와 초점 복귀.
  [`AdjustmentHistory.tsx:622`](../../frontend/src/components/AdjustmentHistory.tsx#L622)

- 행 카드: 순번이 붙은 h5와 읽기 순서, 이름에 사건이 드러나는 상세 토글.
  [`AdjustmentHistory.tsx:421`](../../frontend/src/components/AdjustmentHistory.tsx#L421)

- 지표 절 안, 계산 근거 바로 뒤의 진입점.
  [`PlanResultPanel.tsx:429`](../../frontend/src/components/PlanResultPanel.tsx#L429)

- 반올림하지 않는 원수량·단가 표시.
  [`decimal.ts:59`](../../frontend/src/format/decimal.ts#L59)

- 768px 이상에서는 같은 DOM이 행 배치로 바뀐다.
  [`AdjustmentHistory.module.css:258`](../../frontend/src/components/AdjustmentHistory.module.css#L258)

**시험**

- 25건 두 페이지, 같은 ms 경계, 필터, HTTP 키 집합, 로그.
  [`FirstSnapshotTest.java:3665`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L3665)

- 환산 사슬의 변경 표지와 이전→새 연결.
  [`FirstSnapshotTest.java:3868`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L3868)

- 변조 참조·cursor에 같은 404를 돌려준다.
  [`FirstSnapshotTest.java:3942`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L3942)

- 계보 오염 29종마다 503과 `consistent=false`.
  [`FirstSnapshotTest.java:4052`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L4052)

- 묶음 조건을 하나씩 깨는 단위 시험.
  [`AdjustmentHistoryServiceTest.java:228`](../../backend/src/test/java/com/bokrilog/sync/internal/AdjustmentHistoryServiceTest.java#L228)

- 실 스택에서 키보드로 20행, `더 보기`, 23행, 필터, 상세, 320·1024px.
  [`first-snapshot.spec.ts:482`](../../frontend/e2e/first-snapshot.spec.ts#L482)

- e2e용 스텁 시나리오: USDT 23건, 창 안에 들어갈 때만 낸다.
  [`DevBybitStub.java:456`](../../backend/src/main/java/com/bokrilog/DevBybitStub.java#L456)
