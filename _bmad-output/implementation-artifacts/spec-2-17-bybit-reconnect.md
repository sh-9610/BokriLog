---
title: 'Story 2.17 — 해제한 Bybit 연결의 같은 계정 재연결과 활성 계획 동기화 재개'
type: 'feature'
created: '2026-10-14'
status: 'done'
review_loop_iteration: 0
baseline_commit: '888d4702b8746d6032e1589dd80d2b05156e2008'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-2-16-bybit-connection-revocation.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 2.16 이후 연결을 해제한 사용자는 다시 연결할 수 없다.
- 해제 이력이 있으면 연결 준비와 저장이 무조건 거부된다(`BybitConnectionService.java:186-189`, `SourceConnectionService.java:209-211`).
- 계획은 고정된 `connection_id`·`membership_id`에 묶여 있다. 그래서 새 연결이 생겨도 동기화 가용성이 `CONNECTION_REVOKED`에 머문다(`PlanSyncGuard.sameConnection :175-186`).
- 다른 main UID가 활성 계획에 붙는 것을 막는 검사도 없다(`epics.md` Story 2.17 AC-1~8, `RECONNECT-1.0`).

**Approach:**
- **연결 흐름.** 새 경로를 두지 않고 Story 2.1의 연결 흐름(`POST /api/connections/bybit`·`/connect`·`/cancel`)을 재연결에도 쓴다.
- **저장 판정.** 해제 이력 거부를 지우고 main UID 대조로 판정한다.
  - 같은 UID면 기존 provider account를 다시 쓰고 connection·credential·membership은 새로 만든다.
  - 다른 UID가 활성 계획이 있는 portfolio에 붙으면 거절한다.
- **계획 쪽.** 계획의 동기화·정밀 검증·완료 판정은 "계획과 같은 provider account·portfolio의 지금 활성 연결"을 계획의 연결로 받아들인다. 새 attempt는 새 connection·membership으로 만들고, 기존 watermark부터 재개한다.
- **화면.** 해제 화면의 "아직 제공하지 않음" 안내를 연결 폼으로 바꾼다.

## Boundaries & Constraints

**Always:**
- **연결 흐름 재사용(AC-1·AC-5).**
  - 같은 경로·command(`bybit-connection-create`)·세션 무장(`BybitConnectionFlow`)·최근 행동 허가·세션 회전(`SessionRotation`)을 쓴다.
  - 읽기 전용·IP 바인딩·권한 검증과 `NEW_CONNECTION` 제한(준비·확정·트랜잭션 안 세 지점)은 그대로다.
  - 제공자 검증은 트랜잭션 밖이다. 허가 소비·connection·credential·membership은 한 트랜잭션으로 커밋하고, 실패하면 아무것도 남기지 않는다.
- **저장 판정(AC-1·AC-2·AC-4)** — `SourceConnectionService.store`에서 해제 이력 거부를 지우고 다음 순서로 판정한다.
  1. 활성 연결이 이미 있으면 기존 거절이다.
  2. 검증한 main UID의 provider account가 다른 사용자 소유면 기존 `PROVIDER_ACCOUNT_TAKEN`이다.
  3. 같은 사용자의 기존 provider account와 같으면 그 `provider_account_id`를 재사용한다.
  4. 다르면 새 provider account다.
  - 이전 `REVOKED` connection 행, 과거 membership 구간, 계획·사건·snapshot·계산·근거는 바꾸지 않는다.
  - 사용자당 활성 연결 하나, portfolio당 활성 membership 하나는 기존 부분 유일 색인이 지킨다.
- **다른 UID 거절(AC-4).**
  - 확정 트랜잭션 안에서 사용자 portfolio의 활성 계획을 계획 쓰기 잠금 순서(계획 advisory → 생명주기 행)로 잠근다.
  - 활성 계획의 `provider_account_id`가 새 연결의 것과 다르면 저장 없이 거절한다. 새 code `CONNECTION_ACCOUNT_MISMATCH`(422)를 내고, "기존 계획을 먼저 종료해야 한다"는 중립 안내를 단다.
  - 활성 계획이 없으면(종단만 있음) 새 provider account로 연결한다. 후속 계획은 Story 2.15 경로로만 만들고, 이전 provider account의 사건·snapshot은 섞지 않는다. 이것은 기존 intent 재검사가 지킨다.
- **계획 연결 해석(AC-3)** — `PlanSyncGuard`·`PlanSyncService`·`PlanVerificationService`·완료 판정이 계획 lineage의 connection 대신 "계획의 `provider_account_id`·portfolio와 같은 지금 활성 연결과 그 membership"을 계획의 연결로 받아들인다.
  - 그런 연결이 없을 때만 `CONNECTION_REVOKED`·`CONNECTION_UNAVAILABLE`이다.
  - 새 동기화·정밀 검증 attempt와 발행 lineage에는 **새** connection·membership ID를 쓴다.
  - `plan_definition`의 원래 `connection_id`·`membership_id`는 불변 기록으로 남긴다.
  - watermark(`cashflow_verified_through`)·장기 미동기화 판정은 그대로다(30일 초과 → `LONG_GAP` 선택, 정확히 30일은 허용).
  - 해제 기간의 사건은 provider account 단위 고유성(`(provider_account_id, source_id)`) 덕분에 그대로 이어서 수집된다.
- **계획이 없는 재연결(사용자 승인 2026-10-14 범위 추가).** 첫 스냅샷 뒤·첫 계획 전에 해제한 사용자도 재연결 뒤 첫 계획을 만들 수 있어야 한다.
  - 같은 UID: 계획 조건·첫 계획 발행이 기준 snapshot을 연결 ID가 아니라 provider account·portfolio로 받아들인다. 이전 연결로 받은 snapshot·사건은 같은 provider account의 것이므로 그대로 쓴다. 새 계획의 `plan_definition`에는 발행 시점의 활성 connection·membership을 쓴다.
  - 다른 UID: 이전 provider account의 snapshot은 새 계획의 기준이 되지 않는다. 새 provider account로 새 첫 스냅샷을 받을 수 있고, 첫 계획은 그 snapshot을 기준으로만 만든다. 이전 snapshot을 기준으로 한 계획 조건은 거절한다. 이전 snapshot·사건 기록은 지우거나 고치지 않는다.
- **늦은 Worker(AC-6).**
  - 이전 connection의 lease·작업은 해제 때 이미 종단됐다.
  - 이전 connection ID를 든 Worker는 활성 연결 불일치로 `NOT_PERMITTED`이고, 발행 전 재검사(`PlanSyncService.java:1012` 근처의 정책 버전만 보는 재검사 포함)가 connection ID도 본다.
- **운영 제한 대상.** 계획 대상 해석(`PlanConditionRepository.planTarget`, `RestrictionTargets`)의 `PROVIDER_CONNECTION` 차원은 계획의 지금 활성 연결을 쓴다. 이전 연결 ID에 건 열린 `PROVIDER_CONNECTION` 제한은 같은 provider account의 뒤 연결에도 적용된다 — 해제 → 같은 계정 재연결로 제한을 풀 수 없다. 재연결 자체는 막지 않고, 제한은 운영자 해제(3.4)로만 닫힌다. 다른 provider account의 연결에는 적용되지 않는다(사용자 승인 2026-10-14 수정).
- **기간이 지난 활성 계획(사용자 승인 2026-10-14 추가).** 재연결 확정 트랜잭션은 계획 잠금 아래에서 DB 시각으로 종료 시각이 지난 ACTIVE 계획을 기존 자연 완료 전환(2.14)과 같은 방법으로 `COMPLETED`로 전환한 뒤 계정 대조를 한다. 그래서 다른 UID도 "종단만 있음"과 같이 연결되고, 같은 UID의 결과 문장은 "이어서 동기화"가 아니라 완료 쪽이다. 사용자 선택이 끼지 않는 상태 전환이며 중도 종료와 연결을 잇는 흐름이 아니다.
- **화면(AC-7).**
  - 설정의 해제 분기(`SettingsScreen.tsx:647-670`)에서 "아직 제공하지 않음" 안내(`content/connection.ts:275,299`)를 지우고 기존 연결 폼을 보인다.
  - 거절 code `CONNECTION_ACCOUNT_MISMATCH`의 카피를 닫힌 맵에 더한다.
  - 같은 계정 재연결 성공 뒤 결과 문장은 "기존 계획을 이어서 동기화한다"이고, 공백이 30일을 넘으면 선택이 필요하다는 문장을 덧붙인다(대시보드의 기존 `LONG_GAP` 표시가 그대로 맡는다).
  - 대시보드·동기화·정밀 검증·후속 기준 조작은 기존 가용성 판정을 따르므로 해제 표시가 저절로 거둬진다.
  - 카피는 `content/`에 두고 접근성 규칙(포커스·live region 하나·색상만 쓰지 않기)을 지킨다.
- **삭제·보안(AC-6·AC-8).**
  - 계정 삭제는 지금처럼 모든 connection(해제 포함)·credential·provider account를 지운다.
  - main UID·자격정보·금융값은 응답 본문(기존 `providerAccount` 노출 정책 외)·URL·로그·지표에 싣지 않는다.
  - 계측은 기존 allowlist 안에서 재연결 결과 code만 센다.
- **시험·문서.**
  - `BybitConnectionRevocationTest.aRevokedUserCannotPrepareANewConnection`은 의도 변경으로 뒤집는다. Change Log에 근거를 적는다.
  - `PlanSyncTestBase`에 UID를 받는 store 변형을 더한다.
  - `CLAUDE.md`의 2.16 계약 줄(해제 사용자 단일 거부)과 진행 문장을 고친다.
  - 새 주석은 한국어다.

**Ask First:**
- 새 경로·새 command 이름
- 새 migration(지금 표로 충분해야 한다)
- `plan_definition`의 connection·membership 열을 고치는 것
- 다른 UID일 때 종료 → 새 연결을 한 흐름으로 잇는 것

**Never:**
- 활성 연결의 자격정보 교체
- 여러 provider account 동시 membership
- 이전 provider account 사건을 새 계획에 섞는 것
- 해제 행·과거 기록 수정
- Bybit 원격 키 폐기

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 같은 UID·활성 계획 | 해제된 연결, 같은 UID 새 키 | 200 연결, 같은 `provider_account_id`, 새 connection·membership, 대시보드 `CONNECTION_REVOKED` 해제, 다음 동기화가 watermark부터 수집·발행(attempt는 새 connection ID) | 세션 회전 |
| 같은 UID·30일 초과 | 해제 뒤 watermark에서 31일 | 연결 성공, 동기화 가용성 `LONG_GAP`(Bybit 미호출), 정밀 검증 선택 흐름 | — |
| 같은 UID·정확히 30일 | 30일 | 일반 동기화 허용 | — |
| 같은 UID·종단 계획만 | 중도 종료·완료만 있음 | 연결 성공, 후속 계획 준비 가능 | — |
| 다른 UID·활성 계획 | 활성 계획 + 다른 UID 키 | 저장 없음 | 422 `CONNECTION_ACCOUNT_MISMATCH` |
| 다른 UID·기간 지난 활성 계획 | 종료 시각이 지난 ACTIVE 계획 + 다른 UID 키 | 확정 트랜잭션이 계획을 `COMPLETED`로 전환한 뒤 새 provider account로 연결 | — |
| 이전 연결 제한 | 이전 연결에 열린 `PROVIDER_CONNECTION` SYNC 제한, 같은 UID 재연결 | 연결 성공, 계획 동기화·첫 확인·계획 종료는 제한 응답, 운영자 해제 뒤 재개 | 기존 제한 응답 |
| 다른 UID·종단만 | 종단 계획만 + 다른 UID | 새 provider account로 연결, 후속 계획은 2.15 경로, 이전 사건 비혼합 | — |
| 같은 UID·계획 없음 | 첫 스냅샷 뒤·첫 계획 전 해제, 같은 UID 재연결 | 연결 성공, 기존 snapshot 기준으로 계획 조건 미리보기·첫 계획 발행 가능, 새 계획의 connection·membership은 새 연결의 것 | — |
| 다른 UID·계획 없음 | 첫 스냅샷 뒤·첫 계획 전 해제, 다른 UID 재연결 | 새 provider account로 연결, 새 첫 스냅샷 접수 가능, 그 snapshot 기준으로 첫 계획 발행, 이전 snapshot·사건 비혼합 | 이전 snapshot 기준 계획 조건은 거절 |
| 다른 사용자의 UID | 다른 사용자 소유 provider account | 저장 없음 | 기존 `CONNECTION_ACCOUNT_TAKEN` |
| 운영 제한 | `NEW_CONNECTION` 제한 중 | 저장 없음 | 기존 제한 응답 |
| 두 탭 동시 | 같은 사용자 두 확정 | 활성 연결 하나, 다른 쪽 기존 거절 | — |
| 늦은 Worker | 해제 전 lease의 Worker가 재연결 뒤 발행 시도 | 발행 없음 | `NOT_PERMITTED` |
| 계정 삭제 | 재연결 뒤 삭제 | 이전·새 connection·credential·provider account 모두 제거, 다른 사용자 보존 | 삭제 우선 |

</frozen-after-approval>

## Code Map

- **연결 흐름:**
  - 경로는 `AuthSecurityConfig.java:278,287`이다.
  - `BybitConnectionController.java:71-95`(reason → code), `:196-223`(상태 GET `revoked`), `:280-315`(확정)
  - `BybitConnectionService.java:66,69`(command·target), `:186-198`(해제 거부·`NEW_CONNECTION`), `:216-264`(준비), `:319-376`(확정, 검증은 트랜잭션 밖 :352), `:379-457`(`consume` — 트랜잭션 안 제한 재검사 :415·`sources.store`·`attachActiveSource` :430)
- **저장:**
  - `source/internal/SourceConnectionService.java:184-256`(`store`: 삭제 재검사 :200, **해제 거부 :209-211**, 다른 사용자 계정 :216-223, 계정 재사용·삽입 :225-235, connection·credential :237-255)
  - `SourceConnectionRepository.java:60-69`(`snapshotConnection`), `:183-187`(`isRevoked`), `:239-250`(`eraseOf` — 모든 연결)
  - `portfolio/internal/PortfolioMembershipService.java:100-124`(`attachActiveSource`)
  - 스키마 `V4__source_connection.sql:35-169`(계정 전역 유일, 활성 연결·membership 부분 유일), `V16`(해제 열)
- **계획 연결 해석:**
  - `plan_definition` 열(V10:20-49), attempt 열(V11:18-50), lineage(`PlanPublicationRepository.Lineage :698-715`)
  - `PlanSyncService.admitInTransaction :148-207`(가용성 :358-363, 장기 공백 :365-369, attempt 삽입 :192-196), `:1012`(발행 전 정책 재검사)
  - `PlanSyncGuard.java:126-186,211`(제한 대상·`sameConnection`·`sameMembership`·`blocked`)
  - `PlanVerificationService :110-124`, 완료 판정 `:331-336`
  - `SyncWindow :44,122-126`
  - `PortfolioRepository.java:84-96`(`lockEligible`)
  - `PlanConditionRepository.planTarget :261-269`, `RestrictionTargets:107`, `PlanEndService:102`
- **다른 UID:**
  - `SyncJobService.successorPredecessor :133-144`, `admitNextBaseline :151-173`
  - `PlanConditionRepository :29-35`(intent 재검사)
  - `sync/PlanPublicationPort.java:30`(`ActivePlan` — provider account가 없다, lineage에 있다)
  - 계획 잠금 순서(`lockPlanWrites` → `lockWriteAccess`, `BybitRevocationCommandService.confirm :186-240` 선례)
- **화면:**
  - `frontend/src/screens/SettingsScreen.tsx:647-695`
  - `content/connection.ts:258-300,350`(재연결 불가 카피와 그 문자열 목록)
  - 해제 사유 카피 `content/planSync.ts:43`·`planVerification.ts:79`·`planResult.ts:202`(그대로 — 가용성이 바뀌면 안 보인다)
- **시험:**
  - `BybitConnectionTest`(`connect(prefix, Scenario, uid)` :670, 계정 재사용 :242)
  - `BybitConnectionRevocationTest`(:537-548 뒤집기, :453 늦은 Worker, :558 삭제, 도우미 :939-1022)
  - `PlanSyncTestBase`(`store` :257-268 — 임의 UID·해제 거부에 걸림, `moveVerification` :89, `publishedPlan` :200-230)
  - `IncrementalSyncTest`(~:307 장기 공백), `SuccessorPlanTest`, `AuthFlowTestBase.connectBybitRoundTrip :867`
  - 스텁 키 `<scenario>.<uid>`(`DevBybitStub.java:148,420-421,472`, UID별 증분 상태 :386-390,614-632)
  - `verify-same-origin.sh:2260-2507`(연결 왕복, 해제 절 없음)
  - 프런트엔드 `SettingsConnection.test.tsx`, `SettingsConnectionRevoke.test.tsx`(:165,:353 안내 단정 — 의도 변경), e2e `bybit-connection.spec.ts`

## Tasks & Acceptance

**Execution:**
- [x] `source/internal/SourceConnectionService`·`BybitConnectionService` -- 해제 거부 제거, UID 대조 판정, 다른 UID·활성 계획 거절(계획 잠금 순서), code 매핑.
- [x] `sync`의 `PlanSyncGuard`·`PlanSyncService`·`PlanVerificationService`·완료 판정·발행 전 재검사, `plan`의 `planTarget`·`RestrictionTargets` -- 계획의 지금 활성 연결 해석, 새 attempt ID.
- [x] `frontend` `SettingsScreen.tsx`·`content/connection.ts`·연결 결과·code 맵 -- 해제 분기의 연결 폼, 같은/다른 계정 문장.
- [x] 시험 -- 백엔드: 표의 열세 행(같은 UID 재개·30일 경계·종단·계획 없음·다른 UID 셋·다른 사용자·제한·동시·늦은 Worker·삭제). 프런트엔드: 해제 화면 폼·거절 카피·결과 문장. 주입 origin e2e: 해제 → 재연결 → 동기화 왕복 하나.
- [x] `CLAUDE.md`(2.16 계약 줄·진행 문장)·Epic 2 회고 AI-1 근거.

**Acceptance Criteria:**
- Given 해제 → 같은 UID 재연결 → 동기화를 브라우저로 돌리면, then 같은 계획에 해제 기간 사건이 반영된 새 공식 버전이 발행되고 대시보드에 해제 표시가 없다.
- Given 백엔드 build·프런트엔드 전체 검증·`verify-same-origin.sh --keep-stack`·주입 origin e2e·`test-ci-scripts.sh`를 돌리면, then 모두 통과한다.

## Spec Change Log

- 2026-10-14 구현: `BybitConnectionRevocationTest.aRevokedUserCannotPrepareANewConnection`을 `aRevokedUserCanPrepareAReconnectionOnAFreshReauthentication`으로 뒤집었다 — 근거는 Intent의 "해제 이력 거부를 지우고 main UID 대조로 판정한다"(RECONNECT-1.0, Epic 2 회고 AI-1·R-1). 해제가 재인증 시각을 지우므로 같은 세션의 준비는 여전히 403(재인증 부재)이고, 새 재인증 위에서는 200이다. 같은 이유로 프런트엔드 `SettingsConnectionRevoke.test.tsx`의 "아직 제공하지 않음" 단정 두 곳을 재연결 안내·시작 조작 단정으로 바꿨다.
- 2026-10-14 구현: 계산 근거의 기준 source snapshot 대조를 연결 ID에서 provider account로 넓혔다(`PlanPublicationService` 근거 읽기) — 같은 UID로 재연결한 뒤의 버전은 새 연결로 수집했고 기준 snapshot은 처음 연결의 것이라, 연결 ID를 대조하면 재연결 뒤 근거가 503이 된다. 그래서 `PlanPublicationServiceTest.eachLineageConditionAloneBreaksTheWholeLineage`의 "basis source of another connection" 행을 뺐다(같은 계정의 다른 연결은 계보를 깨지 않는다, 다른 계정 행은 그대로).

- 2026-10-14 범위 추가(사용자 승인 "포함하는 걸로 추천대로 진행해줘"): 구현 중 첫 스냅샷 뒤·첫 계획 전에 해제한 사용자가 재연결 뒤에도 첫 계획을 만들 수 없다는 빈틈을 찾았다 — 같은 UID는 계획 조건이 기준 snapshot의 처음 연결을 요구하고, 다른 UID는 공식값이 이미 있어 첫 스냅샷 admission이 거절되며 후속 경로는 종단 계획을 요구한다. 고정 절 `Boundaries`에 "계획이 없는 재연결" 항목을, 표에 두 행(같은/다른 UID·계획 없음)을 더했다. 같은 범위에서 주입 origin e2e가 4.6분으로 `globalTimeout` 5분에 붙어 있어 7분으로 올리고 `test-ci-scripts.sh` 단정을 함께 고친다.
- 2026-10-14 범위 추가 구현: 계획이 없을 때 계획 조건의 기준 snapshot을 "첫 공식 snapshot"에서 "활성 연결 계정의 기준 snapshot"(`PortfolioMembershipApi.accountBaselineSnapshot` — 다른 계정 snapshot 가운데 가장 늦은 순번 뒤의 그 계정 첫 snapshot, 한 계정만 쓴 portfolio에서는 순번 1)으로 바꿨다. 지금 포인터를 쓰지 않은 이유는 첫 발행 트랜잭션이 `publishNext` 뒤에 `createActivePlan`의 조건 재검사를 하기 때문이다(포인터가 이미 움직였다). `PlanConditionService.eligibleSnapshot`은 같은 계정·portfolio이면 연결 ID가 달라도 받아들이고 membership·연결을 지금 활성의 것으로 바꿔 돌려준다 — 확인·intent·`plan_definition`이 발행 시점의 활성 연결을 싣는다(열 수정·migration 없음). 다른 UID의 새 첫 확인은 `SyncJobService.accountRebaseAllowed`(계획이 하나도 없고, 공식값은 있는데 그 계정의 기준 snapshot이 없음)일 때만 admission을 받고, 발행은 계획 쓰기 잠금 아래에서 자격을 다시 본 뒤 `publishNext`로 포인터를 한 칸 옮기고 열린 확인·intent를 무효화한다. 그동안 첫 확인 상태(`GET /api/snapshots/first`)는 이전 계정의 값을 싣지 않고 `accountChanged: true`와 이 연결의 작업만 싣는다. 화면은 `firstSnapshotContent.accountChanged` 문장과 기존 첫 확인 버튼을 보인다. 주입 origin 회귀는 `globalTimeout` 7분, `ci.yml` 회귀 단계 8분(`test-ci-scripts.sh`가 단정), 통합 job 19분(단계 합에 맞춤)으로 올렸다.
- 2026-10-14 리뷰 결정(사용자 승인 "1, 2 둘 다 추천대로 진행해줘"): ① edge-case-hunter가 연결 단위 SYNC 제한(강한 신호 7 포함)을 해제 → 같은 계정 재연결로 운영자 해제 없이 풀 수 있음을 찾았다 — 고정 절 "운영 제한 대상"의 "이전 연결 ID에 건 제한은 그 연결에만 남는다"를 "같은 provider account의 뒤 연결에도 적용"으로 고치고 표에 "이전 연결 제한" 행을 더했다. ② blind·edge-case가 종료 시각이 지난 ACTIVE 계획이 해제 상태에서는 완료로 전환되지 않아 다른 UID 연결이 422로 영구히 막히는 막다른 길을 찾았다 — 재연결 확정이 계획 잠금 아래 자연 완료 전환을 먼저 하도록 "기간이 지난 활성 계획" 항목과 표 행을 더했다. 리뷰 분류: patch 18건(1차), defer 2건(정책 버전 변경 latent, 다른 계정 연결 뒤 완료 관측 보류·종단 계획 대상 범위), reject 1건(재연결 문맥의 `NEW_CONNECTION` 트랜잭션 안 재검사 — 첫 연결과 공유 경로이고 `BybitConnectionTransactionTest`가 본다).
- 2026-10-14 리뷰 patch 1차: AC-8의 계측은 metric이 아니라 결과 code만 담은 운영 로그 줄 하나(`bybit reconnection: result=SAME_ACCOUNT|NEW_ACCOUNT|ACCOUNT_MISMATCH planResumed=…`)로 했다 — 새 meter는 `TelemetryAllowlist`에 행을 더해야 하는데 재연결은 드문 사건이라 시계열보다 사건 줄이 맞고, 장기 미동기화 진입은 기존 `bokrilog_sync_outcomes_total{outcome="excluded",reason="LONG_GAP"}`가 이미 센다(allowlist 변경 없음). `SAME_ACCOUNT`는 "가장 최근에 해제한 연결과 같은 provider account"다. 재전송 응답의 `planResumed`는 지금 상태(같은 계정의 활성 계획)에서 다시 계산한다. 같은 사용자의 서로 다른 두 허가는 동시에 살 수 없어(새 준비가 같은 command·target의 앞 허가를 지운다) "두 세션 동시 확정"은 확정 대기 중 직접 저장한 활성 연결과의 경합으로 시험했다.
- 2026-10-14 사용자 결정 구현: ① 연결 단위 제한의 판독(`OperationalRestrictionRepository`의 대상 술어 하나 — `effective`·`matching`·공지의 `matchingAmong`이 함께 지난다)이 넘겨받은 연결 ID를 같은 사용자·같은 provider account의 연결 전부로 넓힌다. 연결 표는 source 소유라 operations port `RestrictionConnectionScope`를 루트 `ConnectionRestrictionScope`가 source 공개 API(`sameAccountConnections`)로 구현한다(migration 없음). 그래서 evaluator·3.4 smoke·`RestrictionTargets.remaining`·공지 `covers`가 같은 판정이다. ② 재연결 확정은 계획 잠금 → 생명주기 → 제한 재검사 뒤 `SyncJobApi.completeEndedPlanForReconnection`(→ `PlanSyncService.completeIfEnded`, 2.14의 `PlanPublicationPort.completePlan`과 같은 판정·장기 미동기화 데이터 공백)을 부르고 그 뒤 계정을 대조한다. 완료 계산·첫 종료 후 관측은 그 계정의 연결로 하는 다음 동기화 요청이 한다(2.14 규칙 그대로).

## Design Notes

- **연결 ID 대신 provider account로 계획을 잇는 이유.** 사건 고유성과 source snapshot은 이미 provider account에 귀속한다(AR-12, V7). connection은 "교체 가능한 수집 수단"이다. 계획을 지금 활성 연결로 해석하면 새 표·migration 없이 재연결이 성립한다. 계획의 원래 connection ID는 그 계획을 처음 연 수단의 불변 기록으로 남는다.
- **다른 UID 검사를 확정 트랜잭션 안 계획 잠금 아래 두는 이유.** 준비 때 확인하고 확정 사이에 계획이 생기거나(두 탭), 계획 종료와 경합하면 검사가 낡는다. 계획 쓰기 잠금 순서를 따르면 발행·종료와 직렬화된다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test && npm --prefix frontend run test:e2e` -- expected: 통과.
- `deploy/compose/verify-same-origin.sh --keep-stack && PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` 뒤 스택 down -- expected: 통과.
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 통과.

## Suggested Review Order

1. `backend/src/main/java/com/bokrilog/source/internal/SourceConnectionService.java` — `store`의 판정 순서(활성 연결 → 다른 사용자 계정 → 같은 계정 재사용 → 새 계정)와 해제 거부 제거.
2. `backend/src/main/java/com/bokrilog/BybitConnectionService.java` — 확정 트랜잭션의 계획 잠금 → 기간 지난 계획 완료 전환(`completeEndedPlanForReconnection`) → 계정 대조(422 `CONNECTION_ACCOUNT_MISMATCH`) → 결과 로그·재전송의 `planResumed`.
3. `backend/src/main/java/com/bokrilog/sync/internal/PlanSyncGuard.java` — `planSource`(계획의 provider account·portfolio의 지금 활성 연결), `connectionCause`, lease 연결 대조.
4. `backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java`·`PlanVerificationService.java`·`PlanPublicationService.java` — 새 attempt ID, 발행 전 연결 재검사, 근거의 계정 단위 대조.
5. `backend/src/main/java/com/bokrilog/operations/internal/OperationalRestrictionRepository.java`·`backend/src/main/java/com/bokrilog/ConnectionRestrictionScope.java` — 연결 단위 제한이 같은 계정의 뒤 연결로 넓어지는 판독 술어 한 곳.
6. `backend/src/main/java/com/bokrilog/plan/internal/PlanConditionService.java`·`portfolio/internal/PortfolioMembershipService.java`·`sync/internal/SyncJobService.java` — 계획 없는 재연결: `accountBaselineSnapshot`, `accountRebaseAllowed`, `accountChanged`.
7. `frontend/src/screens/SettingsScreen.tsx`·`content/connection.ts`·`components/FirstSnapshotPanel.tsx` — 해제 분기의 연결 폼, 결과·거절·계정 변경 문장.
8. `backend/src/test/java/com/bokrilog/BybitReconnectionTest.java`, `PlanSyncGuardTest.java`, `frontend/e2e/bybit-reconnect.spec.ts` — 표 열다섯 행의 시험.
9. `.github/workflows/ci.yml`·`test-ci-scripts.sh`·`DEVELOPMENT.md` — 회귀 단계 8분·통합 job 19분·`globalTimeout` 7분.
