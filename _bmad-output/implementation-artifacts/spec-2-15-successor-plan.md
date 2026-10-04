---
title: '새 정상 스냅샷을 기준으로 후속 계획 생성'
type: 'feature'
created: '2026-10-04'
status: 'done'
baseline_commit: '5fd23de478f1b6b63f34550afe49b42b1cc5c50e'
review_loop_iteration: 0
context:
  - '{project-root}/CLAUDE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-3-plan-condition-preview.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-7-first-plan-publication.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-14-plan-natural-completion.md'
---

<frozen-after-approval reason="Story 2.15 AC-1~9의 사용자 소유 범위">

## Intent

**Problem:** 종단 계획이 있으면 조건 생성이 막히고, 기존 기준 선택은 최초 snapshot만 읽는다. 차단만 풀면 이전 P0·t0를 재사용하며 새 계획 뒤 과거 기록도 탐색할 수 없다.

**Approach:** 종단 이후 새 현재가치 조회로 후속 기준을 고정하고, 기존 intent→수집→판정→환산→계산→원자 발행을 재사용한다. 선행 관계와 읽기 전용 과거 기록을 제공한다.

## Boundaries & Constraints

**Always:**
- 자격은 같은 사용자·portfolio의 가장 최근 계획과 실제 종단 사실로 판정한다. 활성 계획 없음, 활성 BYBIT source 하나, 계정·운영 허용을 admission·claim·확인·발행에서 재검사한다. 완료 비교 상태는 자격과 독립이다. 예정 T만 지난 ACTIVE는 불허한다.
- `GET/POST /api/plans/next/baseline`으로 상태 조회/새 기준 작업을 제공한다. POST는 CSRF·Origin·세션과 멱등 키를 요구한다. `sync_job`의 `NEXT_PLAN_BASELINE` 목적은 기존 wallet worker·lease·fencing을 재사용하며 이력을 수집하지 않는다. 요청·완료·측정은 중도 종료 시각 또는 완료의 실제 전환시각보다 모두 뒤여야 한다.
- 기준 준비는 선행 plan·portfolio·source·종단 사실에 서버 귀속한다. wallet·필수 계정 상태가 확인된 source snapshot과 `publishNext`의 공식 portfolio snapshot을 연결한다. 이전 시작/종료/현재 포인터·클라이언트 금액/시각은 기준이 될 수 없다. 장기 공백도 catch-up하지 않는다.
- `P0`는 정확한 USD 합계, `t0`는 snapshot의 measured_at이다. 원 값·구성 provenance·경과 시간을 보여주고 P0≤0·미확인 입력은 생성만 차단한다. 실패한 준비는 마지막 정상 기록을 유지한다.
- `GET /api/plans/conditions`와 기존 미리보기·확인 경로를 재사용한다. 새 준비의 고정 snapshot과 predecessor를 confirmation·intent·plan에 전달하고 canonical hash에 포함한다. 최초 계획의 의미와 입력/시간/정밀도/10분 확인/교체/멱등 계약은 유지한다. 새 준비 성공은 이전 준비의 OPEN intent·미소비 확인을 무효화한다.
- 기준 권위는 준비 기록이다. 생성 파이프라인의 `publishNext` 때문에 현재 포인터가 움직여도 자기 intent를 무효화하지 않는다. 발행은 준비가 여전히 현재이고 같은 선행 계획이 최신 종단인지 잠금 아래 재검사한다.
- 새 흐름 범위는 `new t0 < ti <= min(tc,T)`다. 이전 Fi·조정·계산은 복사하지 않는다. 새 활성 plan·최초 calculation·pointer·intent 소비는 기존 outer 트랜잭션으로 함께 커밋한다. 실패는 종단 기록을 보존하며 거짓 성공을 반환하지 않는다.
- 계획 advisory→계정 생명주기→작업 잠금과 CAS를 유지한다. 기준 준비·완료/종료 후 작업·최초 발행 경합에서 진행 중이면 합치지 않고 대기 안내한다. 늦은 이전 계획 worker는 후속 기준 또는 새 계획의 포인터를 덮지 못한다. 활성 계획/portfolio 단일성 DB 제약을 유지한다.
- `GET /api/dashboard/history`는 소유 계획 요약 목록, `GET /api/dashboard?planId=…`는 소유 종단 기록을 읽는다. 기본 조회는 현재 계획이다. 근거·조정 이력도 선택한 종단 기록의 참조를 검증한다. 타 사용자/미존재는 같은 404, 계보 불일치는 503이다. 과거 조회에는 동기화·종료·종료 후 현재가치를 제공하지 않는다.
- 기존 화면 경로 안에서 새 기준 준비·조건 입력·기록 선택을 제공한다. 이전 조정 이력이 이전되지 않는다고 설명하고 계획 간 합산 지표는 만들지 않는다. 현재 조회의 늦은 과거 응답과 기록 선택 응답을 분리한다. 401/403은 메모리 값을 지우고 화면당 live region 하나·초점·토큰·금지 카피 계약을 유지한다.
- 준비/선행 데이터는 사용자 삭제에 포함하고 타 사용자 기록을 보존한다. 연결 사용 불가·삭제·운영 제한은 늦은 발행도 차단한다. 금융 상세·확인 참조·멱등 입력을 로그/저장소에 노출하지 않는다.

**Ask First:** 새 런타임 의존, 계산/시간 정책 변경, 새 브라우저 경로, dev 스택의 시계·계획 종료 조작 표면.

**Never:** 이전 계획 수정·자금흐름 이전, 복수 활성 계획, 미연결 생성, 백그라운드 수집, 다른 모듈 소유 테이블 직접 접근. 실제 연결 해제 UI와 전체 삭제 조율은 후속 스토리 범위다.

## I/O & Edge-Case Matrix

| 상태/입력 | 결과 | 실패 처리 |
|---|---|---|
| 중도 종료 / COMPLETED의 모든 비교 상태 | 새 정상 준비 후 독립 계획 | 전환 미완료면 거절 |
| 종단 전 요청·완료·측정 / 오래된 포인터 / 클라이언트 기준 | 새 기준 불인정 | 새 준비 안내 |
| 장기 공백 종료 | wallet·계정 상태만 조회 | 이전 공백 이력 호출 0 |
| P0=0·음수 / 미확인 입력 | 원값·사유 표시, 생성 차단 | 0 대체 없음 |
| 새 준비 / 오래된 확인 / 동일·충돌 key | 기존 확인 무효화 / 기존 멱등 계약 | 새 흐름 또는 충돌 안내 |
| ti=t0 / 이전 Fi / ti>t0 | 제외 / 비이전 / 새 범위만 반영 | 이전 기록 불변 |
| 두 탭 / 발행 중 삭제·제한·연결 사용 불가 / 단계 실패 | 최대 하나 성공 / 전체 발행 롤백 | 실제 활성 요약 또는 사유 |
| 과거 기록·근거·이력 / 타 사용자 | 독립 읽기 전용 / 동일 404 | 계보 오류 503 |
| 새 계획 뒤 늦은 이전 응답·worker | 화면·현재 포인터 역행 없음 | 이전 공식 기록 보존 |

</frozen-after-approval>

## Code Map

아래 Java 경로는 `backend/src/main/java/com/bokrilog/` 기준이다.
- `plan/internal/PlanConditionService.java`: state/preview/confirm의 종단 차단, eligibleSnapshot·currentTerms의 최초 기준 고정. `PlanConditionRepository.java`: 확인·intent·plan 저장/삭제.
- `plan/internal/PlanEndRepository.java`: latestPlan·종단 사실. `sync/PlanPublicationPort.java`: 모듈 사이 계획 읽기/발행 경계.
- `sync/internal/SyncJobService.java`, `SyncJobRepository.java`, `FirstSnapshotWorker.java`: 첫 snapshot 작업은 재사용점이나 기존 admission/publishFirst를 후속 기준에 그대로 쓰면 안 된다.
- `portfolio/PortfolioMembershipApi.java`, `portfolio/internal/PortfolioMembershipService.java`: publishNext CAS 재사용, 특정 소유 snapshot의 구성 provenance 조회 추가.
- `sync/internal/PlanPublicationService.java`: readiness·publishInTransaction·endedBundle·completedBundle. `PlanSyncGuard.java`: 이전 작업 차단. `ShownPlanAgreement.java`, `AdjustmentHistoryService.java`: 선택 종단 참조 검증.
- `frontend/src/components/PlanConditionPanel.tsx`: 기준·조건·확인·수집 재사용. `PlanResultPanel.tsx`: 종단의 새 준비 설명을 조작으로 확장.
- `frontend/src/session/useDashboard.ts`: supersedes는 다른 planId를 무조건 수락하므로 조회 대상별 응답 세대 검증 필요.
- `frontend/src/screens/PlanConditionScreen.tsx`, `OnboardingScreen.tsx`, `SignedInScreen.tsx`: 기존 화면 진입점. 실제 `/plan-conditions`도 이미 App.tsx에 존재한다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V15__successor_plan.sql` — 작업 목적·sync 준비 귀속/현재 준비·plan 선행 참조를 expand로 추가. 모듈 사이 FK 없이 소유 검증과 사용자 삭제 순서를 지킨다.
- [x] `backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java`, `SyncJobRepository.java`, `FirstSnapshotWorker.java`, `SyncJobApi.java`; 새 `NextPlanBaselineController.java`와 `AuthSecurityConfig.java` — 후속 admission·관측·발행·상태·보안 경계 구현.
- [x] `backend/src/main/java/com/bokrilog/portfolio/PortfolioMembershipApi.java`, `portfolio/internal/PortfolioMembershipService.java` — 특정 소유 snapshot provenance 조회. `plan/internal/PlanConditionService.java`, `PlanConditionRepository.java`, `PlanConditionApi.java`, `sync/PlanPublicationPort.java` — 최초/후속 기준 공통 판정·선행 전달·삭제 확장.
- [x] `backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java`, `PlanSyncGuard.java`, `ShownPlanAgreement.java`, `AdjustmentHistoryService.java`, `DashboardController.java` — 원자 발행 재검사·과거 목록/조회·근거/이력 선택과 이전 worker 차단.
- [x] `frontend/src/components/NextPlanBaselinePanel.tsx`·`PlanHistoryPanel.tsx`(신규), `PlanConditionPanel.tsx`, `PlanResultPanel.tsx`, `frontend/src/session/useDashboard.ts`, 세 화면 및 `frontend/src/content/nextPlan.ts`(신규) — 준비→조건→새 결과와 과거 기록 탐색·카피·응답 경합 처리. 필요한 CSS는 기존 토큰만 사용한다.
- [x] `backend/src/test/java/com/bokrilog/SuccessorPlanTest.java`(신규), 기존 계획/모듈/삭제 시험, `frontend/src/screens/SuccessorPlanScreens.test.tsx`(신규)·관련 hook/panel 시험 — matrix 전 행과 보안·롤백·다중 탭·화면 접근성을 검증한다.

**Acceptance Criteria:**
- Given Story 2.15 AC-1~9, when 각 테스트 기준을 실행하면, then 종단 기록이 불변이며 새 기준의 독립 계획 하나만 원자 발행되고 두 계획을 각각 탐색할 수 있다.

## Spec Change Log

## Design Notes

기준 준비는 과거 완료 검증과 분리한다. 완료 비교가 보류되어도 새 기준 준비는 허용하지만 동시 작업은 대기시킨다. 준비 기록의 고정 provenance를 재사용해야 최초 결과 발행이 portfolio 포인터를 전진시킬 때 자기 기준을 잃지 않는다. 종단의 정확한 시각은 `plan_end.ended_at` 또는 `plan_completion.transitioned_at`이다.

구현 결정:
- `sync_next_plan_baseline`은 성공 snapshot·source snapshot·실제 종단·요청/완료 시각을 고정한다. 현재 portfolio 포인터가 움직여도 조건과 intent는 이 소유 snapshot을 읽는다.
- 기존 wallet worker를 그대로 사용하며 `NEXT_PLAN_BASELINE` 목적의 admission/claim/publication만 분기한다. 신규 브라우저 경로나 런타임 의존을 추가하지 않았다.
- 후속 작업 및 COMPLETED 후 작업만 admission의 계획 잠금으로 직렬화한다. 기존 ACTIVE sync의 nonblocking admission 계약은 유지한다.
- 제공자 쿼리의 inclusive lower bound에서 검증된 `ti=t0` 이벤트는 기준값에 포함되므로 수집 후보에서 제외한다. `ti<t0`·미확인 이벤트의 진단은 유지한다. 기존 OUT_OF_WINDOW fixture는 실제 `t0−1ms`를 반환하도록 맞췄다.
- 과거 목록과 기준 조회는 명시적인 화면 조작 뒤 읽으며 금융 값은 React 메모리에만 둔다. 선택 대상이 바뀌면 응답 세대를 전진시키고 소유 종단 묶음만 표시한다.

## Verification

- `backend/gradlew --project-dir backend --no-parallel --max-workers=1 clean build` — Docker·JDK25, 전체 시험/경계 통과.
- Node24에서 `npm --prefix frontend run typecheck`, `lint`, `lint:css`, `format:check`, `test`, `build`, `test:e2e` — 전체 통과.
- `deploy/compose/verify-same-origin.sh --keep-stack` 뒤 주입 origin Playwright — V15와 기존 동일 출처 회귀 통과·스택 정리. 종료 시각 조작 없이 가능한 후속 화면은 Vitest와 PostgreSQL 통합 시험으로 검증한다.
- `bash .github/scripts/test-ci-scripts.sh`, `git diff --check`, gitleaks — 통과. 초점·응답 경합 변경에는 `bash frontend/scripts/stress-test.sh`도 실행한다.

### 실행된 matrix 대응 시험

| Matrix 행 | PostgreSQL 통합/회귀 시험 | 화면 시험 |
|---|---|---|
| 중도 종료 / COMPLETED 비교 상태 | `SuccessorPlanTest.newWalletBaselinePublishesAnIndependentPlanAndBothHistoriesRemainReadable`, `pendingHeldAndUnavailableCompletionComparisonsDoNotRestrictSuccessorEligibility`, `anActivePlanEvenPastItsScheduledEndCannotPrepareABaseline`; 완료 확인/보류/불가 상태의 기존 `PlanCompletionTest` 전체 | `SuccessorPlanScreens` 두 화면의 과거 기록 선택 |
| 종단 전 요청·완료·측정 / 오래된 포인터 / 클라이언트 기준 | `aRequestThatClaimsToPrecedeTheTerminalFactIsRejectedBeforePublication`, `observationMeasuredOrReceivedBeforeTheActualTerminalTimeCannotBecomeABaseline`, `unconfirmedSourceInputNeverQualifiesAsAConditionBaseline`; `FirstSnapshotTest.aPointerThatMovesBeforeItsCasEndsThePublicationWithPointerConflict` | 새 기준 provenance·측정 시각·경과 시간 표시 |
| 장기 공백 종료 | `newWalletBaselinePublishesAnIndependentPlanAndBothHistoriesRemainReadable`의 wallet 준비 중 거래 이력 호출 0 단정 | 이전 조정 비이전 설명 |
| P0=0·음수 / 미확인 입력 | `nonPositiveNormalBaselinesRetainTheirOriginalValuesAndOnlyBlockCreation`, `unconfirmedSourceInputNeverQualifiesAsAConditionBaseline` | 0/음수 원값과 차단 사유 parameterized 시험 |
| 새 준비 / 오래된 확인 / key | `anotherPreparationInvalidatesUnconsumedConfirmationAndOpenIntent`, `twoTabsAndIdempotentReplaysAdmitAtMostOneWalletJob`; 기존 조건 확인 멱등/충돌 회귀 | CSRF·동일 출처·멱등 키, 실패 시 마지막 정상값 보존 |
| ti=t0 / 이전 Fi / ti>t0 | `previousFlowsAndAnEventExactlyAtTheNewStartAreNotAppliedToTheSuccessor`; `IncrementalSyncTest.aManualSyncPublishesTheNextVersionAtomicallyAndKeepsItsLineageReadable`의 유효 범위 흐름 반영; `FirstSnapshotTest.outOfWindowTransactionTimeIsQuarantinedWithoutCandidate` | 이전 조정 비이전 설명 |
| 두 탭 / 삭제·제한·연결 불가 / 단계 실패 | `twoTabsAndIdempotentReplaysAdmitAtMostOneWalletJob`, `publicationRechecksDeletionRestrictionAndConnectionAvailability`, `baselinePublicationFailureRollsBackThePortfolioAndPreservesLastNormalPreparation`, `deletionBlocksAdmissionAndErasesOnlyTheOwnersPreparationAndLineage`; `FirstSnapshotTest.aFailureAtAnyPublicationStepRollsEverythingBack`, `aChangeDuringThePublicationRollsItBack` | 거절 후 정상값 보존, 401/403 메모리 초기화 |
| 과거 기록·근거·이력 / 타 사용자 | `newWalletBaselinePublishesAnIndependentPlanAndBothHistoriesRemainReadable`, `historicalReadsHideUnknownForeignAndActivePlans`, `nextBaselineHttpRequiresSessionOriginCsrfAndIdempotencyAndUsesNoStore`; 기존 근거/조정 계보503 회귀 | 두 화면의 읽기 전용 과거 기록·단일 live region |
| 늦은 이전 응답·worker | `aLateFencedWalletWorkerCannotOverwriteTheSuccessorBaselineOrNewPlansPointer`; `IncrementalSyncTest.aLostLeaseEndsAsInterruptedAndTheLateWorkerWritesNothing` | 선택 기록 응답 경합 / 새 계획 이후 이전 계획 묶음 역행 차단 |

`SuccessorPlanTest`의 메서드 접두사는 위 첫 행 이후 같은 클래스다. 기존 파이프라인을 재사용하는 단계 실패·범위·계보 판정은 전체 backend 회귀의 해당 시험으로 함께 검증한다.

### 최종 실행 결과 (2026-10-04)

- JDK25·Docker PostgreSQL18.6: 전체 `build` 68 suite / 1,090개 / 실패·skip 0 (`/tmp/successor-final-backend-2.log`). 이후 금융 기준/목록의 삭제 전후 `SENSITIVE` 재검사와 삭제 POST403 보완은 `test --tests '*SuccessorPlanTest' bootJar` 16/16으로 다시 검증했다 (`/tmp/successor-final-guards-2.log`).
- Node24.20.0: typecheck·lint·lint:css·format 검사·build 통과. 최종 Vitest 36파일 / 1,143개 / 실패0. 준비0·음수 유지, 미확인 입력 사유, 양수 준비에서만 조건 화면 전환을 검증한다.
- standalone Playwright 38통과 / 주입 origin 전용24skip. 동일 출처 `verify-same-origin.sh --keep-stack` 959/959. Nginx 주입 origin Playwright는 키보드 순서에 신규 기록/기준 버튼을 포함하여 62/62 통과했다. 신규 dev 시계 조작 표면은 추가하지 않았다.
- CPU부하16개(default CPU×2), 대상3파일의84시험을12회 반복:12/12통과. 일반 실행과 별도로 응답 경합·초점·조건 분기를 검증했다.
- CI 판정 셸147/147, `git diff --check` 통과. gitleaks8.24.3 변경·신규 파일 전체0건. 전체 dir 스캔1건은 기존 `.gitleaksignore`에 문서화된 `_bmad/_config/files-manifest.csv:48`의 SHA256 해시 false positive였고 규칙·예외 파일은 변경하지 않았다.
- 비차단 build 경고: JavaScript chunk 약508.7kB(minified)로 Vite의500kB 경고가 있다. 런타임 의존이나 계산 정책 변경은 없다. commit/push는 하지 않았으며 SPEC/sprint 상태는 부모 리뷰 절차를 위해 `in-progress`로 유지한다.

### 리뷰 patch 반영 및 실행된 시험 (2026-10-05)

승인된 Intent·Boundaries·I/O matrix·Tasks 블록은 변경하지 않았다. 아래는 step-04의 구현 보완과 추가 검증 기록이다. SPEC 상태 전환은 부모 리뷰 절차가 관리한다.

| patch | 최종 변경과 실행된 covering test |
|---|---|
| 1 과거 PENDING 읽기 | 현재 조회의 strict parser를 유지하고 명시적인 종단 과거 조회만 PENDING/retryable=false를 수락한다. `SuccessorPlanTest.historicalPendingIsReadOnlyAndHistorySummariesUseActualEarlyEndButScheduledCompletionEnd`; `SuccessorReviewScreens`의 `명시적 과거 PENDING만 retryable=false를 수락하고 조작 필드를 거절한다`, 두 화면 `%s 과거 PENDING에는 동기화·종료·종료 후 현재가치가 없다` |
| 2 준비 상태와 재시도 | 실패/차단·초기 AVAILABLE·거절·요청 오류를 구분하고 마지막 정상값과 재조회 조작을 유지한다. `SuccessorReviewScreens`의 `첫 조회 실패 뒤 명시적으로 재조회할 수 있다`, `재준비 %s는 같은 정상값을 유지하면서 대기 안내를 끝낸다`, `아직 준비하지 않은 AVAILABLE 상태는 실패 대신 첫 조회를 안내한다`, `polling 중 GET %s의 %s 본문과 무관하게 금융값과 busy를 지운다`(401/403 × malformed/stalled; json 미호출) |
| 3 요청 수명과 전환 | GET/POST 전체 JSON 본문 deadline·unmount abort, 불확실한 POST의 동일 key 재시도, 취소한 GET이 새 POST를 덮지 않는 요청 세대 경계를 적용했다. `SuccessorReviewScreens`의 `POST 본문 deadline 뒤 같은 key로 재시도하고 unmount에서 POST를 abort한다`, `지연 GET 취소 catch가 진행 중 POST의 busy와 대기 안내를 덮지 않는다`, `GET 본문 deadline과 unmount abort가 재조회 조작을 남긴다`, `실제 조건 화면에서 QUEUED→RUNNING→양수 성공 뒤 입력 제목으로 초점을 옮기고 live region 하나를 유지한다` |
| 4 기록 종료 시각 | ENDED 요약은 실제 endedAt, COMPLETED 요약은 예정 T를 표시한다. 목록은 단일 joined query로 조회한다. `SuccessorPlanTest.historicalPendingIsReadOnlyAndHistorySummariesUseActualEarlyEndButScheduledCompletionEnd`; `SuccessorReviewScreens.과거 목록은 서버의 실제 중도 종료 시각을 그대로 표시한다` |
| 5 과거 기록 초점 | 선택한 planId 변경에만 제목 초점 이동. `PlanResultPanel`의 `선택한 planId가 바뀔 때만 제목으로 이동하고 조용한 새 객체 refresh는 초점을 보존한다` |
| 6 준비 안내 수명 | 두 화면에서 기록 선택·현재 계획 변경·준비 패널 제거 시 이전 안내를 정리하고 계획별 패널을 다시 생성한다. `SuccessorReviewScreens`의 두 화면 `%s 기록 선택은 이전 준비 live 안내를 지운다`, `%s 현재 계획 변경과 준비 패널 제거는 준비 안내를 정리한다` |
| 7 단일 live region | 실제 조건 화면의 준비 안내를 상시 status로 통합한다. `SuccessorReviewScreens.실제 조건 화면에서 QUEUED→RUNNING→양수 성공 뒤 입력 제목으로 초점을 옮기고 live region 하나를 유지한다` |
| 8 PostgreSQL 경합 | 실제 advisory lock 대기를 동반한 same/different key admission, 준비/최초 발행 경합, 모든 준비·생성 stage의 QUEUED/RUNNING 동안 이전 COMPLETION/POST_END_VALUE admission 거절과 작업 수·포인터 불변, 늦은 이전 publisher의 준비/새 계획 포인터 비침범을 검증한다. `SuccessorPlanTest.concurrentTabsWithSameOrDifferentKeysAdmitOnePreparation`, `concurrentPreparationAndFirstPublicationHaveOnlyOneAdmissionWinner`, `queuedAndRunningPreparationAndEveryCreationStageRefuseOldCompletionOrPostEndAdmission`, `lateCompletionAndPostEndPublishersCannotMoveThePreparingOrNewPlanPointers` |

patch8 시험은 PostgreSQL18.6 Testcontainers에서 실행했으며 인메모리 대체나 순차 호출로 경합을 대신하지 않는다. 초기 두 실패는 응답 NON_NULL 직렬화의 absent-field 기대와 FAILED fixture의 finished_at 제약 불일치였고 production 판정 변경 없이 시험을 수정했다. 금융 응답 본문은 기록하지 않으며 HTTP 시험은 필요한 상태·필드 존재·시각만 단정한다.

### 리뷰 patch 최종 트리 검증 (2026-10-05)

- Docker PostgreSQL18.6·JDK25 전체 `clean build`: 68 suite / 1,095개 / 실패·skip 0, 7분29초 (`/tmp/successor-review-full-backend.log`; XML 보존 `/tmp/successor-review-full-backend-xml/`, 개수 `/tmp/successor-review-full-backend-count.json`). 이후 금융 본문을 출력하지 않는 HTTP 단정 보완까지 `test --tests '*SuccessorPlanTest' bootJar`로 21/21 재확인했다 (`/tmp/successor-review-final-pg.log`).
- Node24.20.0 최종 Vitest 37파일 / 1,164개 / 실패0 (`/tmp/successor-review-final-frontend.log`). typecheck·lint·lint:css·format:check·build 통과. 최초 화면17회귀에 본문 무관401/403 2추가조건·정상 초기 안내1개를 보완하여 리뷰 화면시험은20개다.
- CPU부하16개(default CPU×2), 응답 경합·완료·조건·초점 관련5파일 / 170시험을12회 반복:12/12통과 (`/tmp/successor-review-final-stress.log`).
- standalone Playwright 38통과 / 주입 origin 전용24skip (`/tmp/successor-review-standalone-e2e.log`). 동일 출처959/959 (`/tmp/successor-review-same-origin.log`), Nginx 주입 origin62/62 (`/tmp/successor-review-injected-e2e.log`). 초기 정상 안내 카피를 포함한 최종 산출물의 동일 출처·주입 origin 재확인은 아래 추가 기록을 따른다.
- CI 판정147/147 (`/tmp/successor-review-ci.log`), `git diff --check` 통과. gitleaks8.24.3 변경·신규44파일 전체0건 (`/tmp/successor-review-gitleaks.log`). 규칙과 예외는 변경하지 않았다.
- 비차단 Vite 경고: JavaScript chunk 약510.2kB(minified). commit/push하지 않았으며 frozen 블록과 SPEC `in-review` 상태를 유지한다.

- 최종 카피·본문 거절 시험 보완 이후 산출물 재확인: 빈 볼륨의 동일 출처959/959 (`/tmp/successor-review-clean-same-origin.log`), standalone38통과/24skip (`/tmp/successor-review-final-standalone-e2e.log`), 주입 origin62/62 (`/tmp/successor-review-final-injected-e2e.log`). `--keep-stack` 데이터가 남은 상태의 재실행은 기존 초기 개수 단정과 충돌하여 중단했으며 볼륨을 정리한 새 스택에서 전부 통과했다. 마지막 compose 스택·볼륨 정리 완료 (`/tmp/successor-review-final-cleanup.log`).
- 문서 기록까지 포함한 변경·신규44파일 gitleaks0건 및 최종 `git diff --check` 통과. patch1~8에 남은 미구현 항목은 없다.

## Suggested Review Order

- 후속 기준 접수의 종단 상태·멱등성·동시성 경계를 확인한다.
  [SyncJobService.java:122](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L122)

- wallet 발행이 고정된 준비 계보와 현재 포인터를 보호하는지 확인한다.
  [SyncJobService.java:657](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L657)

- 새 조건 확인이 양수 정상 기준과 최신 준비에 묶이는지 확인한다.
  [PlanConditionService.java:379](../../backend/src/main/java/com/bokrilog/plan/internal/PlanConditionService.java#L379)

- 후속 계획이 기존 원자 발행 파이프라인을 사용하는지 확인한다.
  [PlanPublicationService.java:339](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L339)

- 과거 기록 조회가 소유권과 읽기 전용 종단 상태를 지키는지 확인한다.
  [PlanPublicationService.java:613](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L613)

- 현재·과거 응답 파싱과 늦은 응답의 역행 방지를 확인한다.
  [useDashboard.ts:1109](../../frontend/src/session/useDashboard.ts#L1109)

- 준비 요청의 취소·시간 제한·재시도와 정상값 보존을 확인한다.
  [NextPlanBaselinePanel.tsx:93](../../frontend/src/components/NextPlanBaselinePanel.tsx#L93)

- 기록 선택과 초점 이동 흐름을 확인한다.
  [PlanHistoryPanel.tsx:14](../../frontend/src/components/PlanHistoryPanel.tsx#L14)

- 조건 화면의 준비 전환과 단일 상태 안내를 확인한다.
  [PlanConditionScreen.tsx:17](../../frontend/src/screens/PlanConditionScreen.tsx#L17)

- 스키마의 준비 계보와 후속 계획 연결 제약을 확인한다.
  [V15__successor_plan.sql:6](../../backend/src/main/resources/db/migration/V15__successor_plan.sql#L6)

- 실제 PostgreSQL에서 두 탭의 동시 접수 결과를 확인한다.
  [SuccessorPlanTest.java:389](../../backend/src/test/java/com/bokrilog/SuccessorPlanTest.java#L389)

- 늦은 이전 발행이 준비·새 계획 포인터를 건드리지 않는지 확인한다.
  [SuccessorPlanTest.java:508](../../backend/src/test/java/com/bokrilog/SuccessorPlanTest.java#L508)

- 화면 요청 경합·본문 시간 제한·초점·접근성 회귀를 확인한다.
  [SuccessorReviewScreens.test.tsx:62](../../frontend/src/screens/SuccessorReviewScreens.test.tsx#L62)

검토 준비 완료: SPEC은 `done`, 스프린트 Story 2.15는 `review`로 전환했다. 커밋·푸시는 사용자 요청 시 진행한다.
