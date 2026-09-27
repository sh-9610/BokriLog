---
title: '초기 계산 입력을 위한 Bybit 현재가치와 원천 사건 수집'
type: 'feature'
created: '2026-09-27'
status: 'done'
baseline_commit: 'f76f5e912d547ec8f017c7c49463b3029553495d'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/EPIC-IMPLEMENTATION-READINESS.md'
  - '{project-root}/_bmad-output/planning-artifacts/prds/prd-BokriLog-2026-08-23/prd.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-3-plan-condition-preview.md'
---

<frozen-after-approval reason="승인된 Epic 2 범위와 ERD-1.0.0 수집 계약">

## Intent

**Problem:** 계획 조건을 확정해도 최초 계산에 필요한 최신 Bybit 현재가치와 외부 자금 이동 원천 사건이 완전하게 수집되지 않으면 신뢰 가능한 계산 입력을 만들 수 없다.

**Approach:** 유효한 portfolio 소유 plan intent를 기준으로 Bybit UTA의 `totalEquity`와 Transaction Log `TRANSFER_IN`·`TRANSFER_OUT` 전 구간·전 페이지를 수집한다. provenance와 완전성 증거를 결합한 불변 후보 입력 묶음만 만든다.

## Boundaries & Constraints

**Always:** `CLAUDE.md`, Epic 2 Story 2.4 AC, `BYBIT-CASHFLOW-1.0`, `ERD-1.0.0` §2.2를 따른다. admission과 publish에서 user·portfolio·intent·활성 connection·삭제·운영 제한·활성 plan 상태를 서버에서 재검증한다. Bybit 시간 API로 `tc`를 한 번 고정하고 intent `t0`보다 큰 시각부터 `tc`까지를 최대 7일 연속 요청 창으로 나눠 각 필수 type의 모든 cursor를 조회한다. wallet envelope `time`은 측정시각이다. API 호출은 DB 트랜잭션 밖에서 한다. `source_id`는 Bybit `id`, 유일성은 `(provider_account_id, source_id)`이며 핵심 의미 필드의 canonical hash만 비교한다. 연결은 provenance로 저장한다. 페이지 단위 batch 처리, lease·fencing, 짧은 원자 publish, user 범위 삭제와 늦은 worker 차단을 보장한다. 수집된 financial response 원문·credential은 로그·화면·진단에 남기지 않는다. Plan·Source·Portfolio 모듈 경계를 지키며 plan 저장소를 직접 읽지 않는다.

**Ask First:** 승인 문서의 정책·범위 밖으로 endpoint, 수집 유형, 데이터 보존 의미 또는 공식 결과 계약을 바꾸는 경우만 해당한다. 이 구현은 현재 승인 계약을 따른다.

**Never:** `id` 대체값 생성, 같은 ID 충돌 덮어쓰기, 미완료 입력·watermark 발행, client clock 신뢰, 원문 JSON 저장·노출, 보조 입출금/내부 이체 API를 별도 공식 사건으로 생성, 현금흐름 분류·가격 환산·복리 계산·활성 계획·공식 snapshot pointer 생성은 금지한다. 후보 데이터는 공식 재무 지표로 표시하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Expected behavior | Error handling |
|---|---|---|
| VALID / ACTIVE_JOB | 유효 intent에서 비동기 접수, 같은 목적의 기존 active job은 재사용 | `202`와 안전한 상태 참조 |
| INELIGIBLE | 타 사용자·만료/대체/소비 intent, 삭제·제한·비활성 연결·활성 plan | 외부 호출 없이 거절 |
| COMPLETE | 두 type의 모든 창·cursor 및 wallet 값이 검증됨 | 후보 bundle과 completeness를 한 트랜잭션으로 저장 |
| PARTIAL / CURSOR_ERROR | 누락·반복 cursor, page 실패, 창 gap, malformed 응답 | 미완료 상태 유지, watermark 미전진, 재시도 가능한 상태 |
| DUPLICATE / CONFLICT | 같은 ID·hash는 no-op; 같은 ID·다른 핵심 의미값은 충돌 관측 보존 | 충돌·미인식 응답은 격리하고 `검토 필요` |
| STALE_WORKER / DELETION_RACE | lease/fence 상실 또는 사용자 삭제와 경합 | 늦은 저장·완료·재생성을 차단 |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java` -- 기존 서명·HTTP 경계에 server time과 UTA Transaction Log 읽기 추가
- `backend/src/main/java/com/bokrilog/source/SourceConnectionApi.java` 및 `SourceConnectionService.java` -- source 소유권·credential 사용 경계와 읽기 DTO
- `backend/src/main/java/com/bokrilog/sync/SyncJobApi.java` -- admission·상태 조회·user data erase 공개 port
- `backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java` 및 `FirstSnapshotWorker.java` -- admission 재검사, lease/fencing, 외부 호출 분리 패턴
- `backend/src/main/java/com/bokrilog/sync/internal/SyncJobRepository.java` -- DB 시간 기반 lease와 fencing 구현
- `backend/src/main/java/com/bokrilog/plan/` 및 `PlanConditionService.java` -- intent 적격성·불변 t0를 공유 트랜잭션용 공개 port로 제공; sync가 plan 테이블을 직접 조회하지 않음
- `backend/src/main/resources/db/migration/V6__plan_intent.sql` 및 다음 migration -- intent schema pattern과 Story 2.4 job/event/evidence/bundle 저장소
- `backend/src/main/java/com/bokrilog/portfolio/PortfolioMembershipApi.java` -- portfolio·membership provenance 경계
- `backend/src/main/java/com/bokrilog/PlanConditionController.java`, `frontend/src/screens/PlanConditionScreen.tsx`, `frontend/src/components/PlanConditionPanel.tsx` -- 조건 확정 이후 수집 진입·상태 화면
- `frontend/src/App.tsx`, `deploy/nginx/bokrilog.conf`, `deploy/compose/verify-same-origin.sh` -- 로그인 경로·same-origin allowlist 계약
- `backend/src/test/java/com/bokrilog/FirstSnapshotTest.java`, `frontend/e2e/first-snapshot.spec.ts` -- worker·접근성·통합 시험 패턴

## Tasks & Acceptance

**Execution:**
- [x] source 공개 DTO와 Bybit adapter에 고정 `tc`, wallet 측정값, UNIFIED Transaction Log 두 type의 창·cursor 조회를 추가하고 fixture contract 시험을 둔다.
- [x] 별도 Story 2.4 job/event/observation/completeness/candidate schema와 공개 port를 구현한다. 기존 first-snapshot 공식 자료와 혼합하지 않는다.
- [x] intent 적격성 검사와 admission, 중복 job 재사용, worker lease/fencing, API 외부 호출 분리, 모든 페이지 재개·batch 저장을 구현한다.
- [x] 안정 ID·semantic hash 멱등성, ID 충돌 및 unknown schema 격리, 완전성 증거와 원자 후보 publish를 구현한다.
- [x] 사용자 범위 삭제 orchestration에 신규 자료 삭제·취소를 연결하고 deletion race에서 worker 재생성을 막는다.
- [x] 조건 확정 화면에 네 상태·마지막 확인·안전한 다음 행동·허용 재시도를 연결하고 후보와 공식값을 구분한다.
- [x] matrix 전 행을 실행되는 backend/frontend/same-origin 시험으로 덮고 사용자 변경 파일 외부 credential 로그가 없음을 검증한다.

**Acceptance Criteria:**
- Given 요청 사용자가 소유한 유효 plan intent와 활성 연결이 있을 때, when 수집을 접수하면, then 삭제·제한·활성 plan·intent 상태를 검사하고 비동기 `202`를 반환하며 기존 동일 목적 작업을 재사용한다.
- Given worker가 유효 lease를 얻으면, when 범위를 정할 때, then 서버에서 한 번 정한 `tc`, 불변 `t0`, `t0 < event_time <= tc`와 최대 7일 연속 창을 사용한다.
- Given 범위가 정해지면, when provider를 조회할 때, then wallet `totalEquity`/envelope `time` 및 각 창의 두 Transaction Log type 모든 cursor를 읽고 페이지별 진행·상태·시간·건수·dedupe 증거를 남긴다. provider 호출 동안 DB transaction은 열려 있지 않다.
- Given 원천 사건이 저장되면, when 같은 사건을 재수집할 때, then provider account+Bybit ID와 semantic hash로 멱등 처리하고 비핵심 필드 변경은 무시하며 핵심 충돌은 보존·격리한다. 빠진 ID는 대체하지 않는다.
- Given unknown type/schema, page/cursor 누락, 시간 gap, API/DB 오류가 생기면, when 작업을 처리할 때, then 안전한 진단 reference와 `검토 필요` 또는 `재시도 필요`를 남기고 partial bundle·watermark를 완료 처리하지 않는다.
- Given 모든 필수 입력과 completeness가 유효하면, when bundle을 발행할 때, then 동일 트랜잭션에서 intent·connection·deletion·restriction·lease/fence를 재검사해 immutable candidate만 저장하고 공식 snapshot·계산·활성 plan은 만들지 않는다.
- Given stale worker 또는 삭제가 경합하면, when 늦은 응답이 돌아오면, then stale fence는 상태·bundle·watermark를 쓸 수 없고 삭제 사용자 자료를 재생성하지 못한다.
- Given 사용자가 상태를 열면, when 상태가 변할 때, then `수집 중`, `입력 수집 완료`, `검토 필요`, `재시도 필요`, 마지막 확인 시각과 다음 행동을 접근성 있게 알리고 후보 금액·수익률 등 공식 지표로 오인시킬 수 없다.
- Given 동일 origin에서 실행하면, when backend/frontend 통합 검증을 수행할 때, then 인증·보안 경로와 320px·200/400%·키보드·reduced-motion 동작을 통과한다.
- Given 사용자 연결 해제/삭제가 실행되면, when sync erase port가 호출될 때, then 해당 사용자의 job/event/candidate/evidence만 제거되고 반복 호출 및 늦은 worker가 데이터를 복원하지 못한다.

## Design Notes

작업과 후보 저장은 Story 2.2의 공식 portfolio snapshot schema를 재사용하지 않는다. Sync는 plan module의 공개 검증 경계를 통해 intent 유효성만 확인한다. 각 provider 호출은 7일 이하이며, 인접 구간 중첩은 사건 ID로 제거한다. `tc`는 논리 구간 상한이며 wallet `measured_at` 및 로컬 request/response 시각과 별도 provenance로 보존한다. 실제 Bybit 계정이 없는 계약 fixture 검증은 실연동 검증으로 간주하지 않는다.

## Verification

- matrix 및 리뷰 수정 실행 증거 (Docker/Testcontainers, serial): `backend/gradlew --project-dir backend --no-parallel --max-workers=1 build` 통과 (507 tests, failures/errors/skips 0). `VALID / ACTIVE_JOB` 및 `COMPLETE`는 `initialInputsReuseAdmissionAndPublishOnlyACompleteCandidate`, `INELIGIBLE`은 `ineligibleCollectionIntentDoesNotCallProvider`, `PARTIAL / CURSOR_ERROR`는 `repeatedTransactionCursorLeavesPartialEvidenceAndNeedsReview`, `transactionLogPageFailureLeavesNoCandidateAndRequestsRetry`, `malformedTransactionLogResponseNeedsReviewWithoutExposingPayload`, `DUPLICATE / CONFLICT`는 `duplicateEventIdWithDifferentSemanticHashPreservesOriginalAndRequiresReview`, `unknownTransactionEventSchemaIsQuarantinedWithoutCandidate`, `STALE_WORKER / DELETION_RACE`는 `staleInitialCollectionFenceCannotPublishCandidate`, `eraseDuringInitialCollectionCannotRecreateUserData`에서 검증했다. 리뷰 경합·접근·입력 검증은 `concurrentAdmissionCreatesOnlyOneActiveJobForAnIntent`, `collectionStateIsScopedToSelectedIntentAndReturnsEmptyOnlyForEligibleIntent`, `collectionStateDeniesRestrictedAndDeletedAccounts`, `oversizedUtf8EventIdIsQuarantinedWithoutInsert`, `excessiveCashFlowSizeOrScaleIsQuarantined`, `outOfWindowTransactionTimeIsQuarantinedWithoutCandidate`, `overLimitProviderPageLeavesNoCandidate`로 확인했다. Bybit 계약 시험 `initialCollectionFixesServerTimeAndReadsBothTypesAcrossSevenDayWindowsAndEveryCursor`는 정확한 `totalEquity`와 provider wallet envelope `time`, URL 인코딩 cursor를 단정하며, `rejectsTransactionLogPagesLargerThanRequestedLimit`은 50개 초과 응답을 거부한다. 진단 관측의 page-evidence 연결도 `unknownTransactionEventSchemaIsQuarantinedWithoutCandidate`에서 확인했다.
- 프런트엔드 (Node 24): `npm run lint`, `npm run typecheck`, `npm run lint:css`, `npm run format:check`, `npm run test` (14 files / 295 tests), `npm run build` 통과. 상태 UI 테스트에서 intent 전환 시 이전 job 재시도 숨김, allowlisted/unknown `failureCode` 안전 안내, 잘못된 `lastCheckedAt` fallback을 검증했다.
- Playwright: `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 ./node_modules/.bin/playwright test e2e/first-snapshot.spec.ts --workers=1` 통과 (1/1; Docker Nginx origin). Story 2.4 시나리오에 keyboard, reduced-motion, 320px 및 200/400% 확대 레이아웃 검사를 포함한다. 전체 `npm run test:e2e` 통과 (38 passed, 19 skipped, 0 failed); 미인증 진입·정적 경로 전용 시나리오는 이 실행에서 skipped이며 Story 2.4 경로는 injected-origin 실행에서 통과했다.
- 동일 출처: `deploy/compose/verify-same-origin.sh --keep-stack` 통과 (959/959); Story Playwright 뒤 compose stack을 종료했다. CI script 계약 `bash .github/scripts/test-ci-scripts.sh` 147/147 통과.
- `git diff --check` 통과.

**마지막 상태 조회 port 조정:** review 뒤 `/inputs/jobs/{id}` HTTP 경로는 제거했다. 사용자 소유 내부 조회 port는 사용자 접근·운영 제한을 확인한 채 유지한다. 대체 plan intent 이후에도 본인 과거 job 상태를 조회할 수 있도록 내부 port의 intent 유효성 요구는 두지 않는다. `collectionStateIsScopedToSelectedIntentAndReturnsEmptyOnlyForEligibleIntent`와 재개 후 전체 backend build가 통과했다.

## Suggested Review Order

**접수와 worker 경계**

- 진입 API에서 intent를 확인하고 비동기 접수 계약을 파악한다.
  [`FirstSnapshotController.java:83`](../../backend/src/main/java/com/bokrilog/FirstSnapshotController.java#L83)

- 같은 intent의 active job을 재사용하고 user·restriction을 검사한다.
  [`SyncJobService.java:91`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L91)

- 외부 수집과 fenced page 저장을 연결한다.
  [`FirstSnapshotWorker.java:41`](../../backend/src/main/java/com/bokrilog/sync/internal/FirstSnapshotWorker.java#L41)

**Bybit 시간과 pagination**

- 고정 `tc`, 지갑 측정시각, 7일 창과 모든 cursor 처리를 확인한다.
  [`BybitAdapter.java:320`](../../backend/src/main/java/com/bokrilog/source/internal/BybitAdapter.java#L320)

**저장 완전성과 후보 발행**

- 사건·페이지 evidence를 batch 저장하고 충돌을 격리한다.
  [`InitialCollectionRepository.java:123`](../../backend/src/main/java/com/bokrilog/sync/internal/InitialCollectionRepository.java#L123)

- 완전성 증명 뒤 immutable candidate만 저장한다.
  [`InitialCollectionRepository.java:216`](../../backend/src/main/java/com/bokrilog/sync/internal/InitialCollectionRepository.java#L216)

- 공식 snapshot과 분리된 job·event·evidence·candidate 스키마다.
  [`V7__initial_source_event_collection.sql:1`](../../backend/src/main/resources/db/migration/V7__initial_source_event_collection.sql#L1)

**사용자 intent와 상태 화면**

- Plan 모듈 접근은 공개 intent port로 제한한다.
  [`InitialInputsPlanPort.java:8`](../../backend/src/main/java/com/bokrilog/sync/InitialInputsPlanPort.java#L8)

- 사용자가 후보 금액을 보지 않고 안전한 수집 상태를 확인한다.
  [`InitialInputsCollection.tsx:100`](../../frontend/src/components/InitialInputsCollection.tsx#L100)

**회귀 시험**

- admission, replay, fencing, 삭제 경합과 보존을 검증한다.
  [`FirstSnapshotTest.java:96`](../../backend/src/test/java/com/bokrilog/FirstSnapshotTest.java#L96)

- server time, wallet 측정시각, 창 분할과 cursor encoding을 단정한다.
  [`BybitAdapterTest.java:204`](../../backend/src/test/java/com/bokrilog/source/internal/BybitAdapterTest.java#L204)
