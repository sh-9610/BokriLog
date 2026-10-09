---
title: 'Story 3.2 — 문제 영향 범위만 수동으로 제한하기'
type: 'feature'
created: '2026-10-09'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'c02abf818471b7f75de1845ff6e6e169cdd77bff'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-1-operator-surface-audit-ledger.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 운영 제한이 `operations_restriction.command` 문자열 하나로 기능 전체를 막는 전역 스위치다. scope·target 축이 없고 만드는 경로도 없다. 대시보드·근거·조정 이력에는 표시 단계 검사가 없고, 증분 동기화는 lease 시점에 제한을 보지 않는다(회고 R-3·AI-5, `epics.md` Story 3.2 AC-1~6).

**Approach:**
- **제한 모델:** 제한 하나는 `기능 × scope × target`이다.
  - 기능은 NFR-17의 넷이다: `NEW_CONNECTION`·`SYNC`·`CALCULATION_DISPLAY`·`ADVERTISING`.
  - scope는 `GLOBAL`·`PLAN`·`PORTFOLIO`·`CALCULATION_POLICY_VERSION`·`PROVIDER_CONNECTION`이다.
  - AC가 부르는 scope `sync`·`advertising`·global feature는 해당 기능을 `GLOBAL`로 막는 경우다.
- **판독:** V18로 테이블을 확장하고, 7곳의 `isRestricted(command)`를 evaluator 하나 `evaluate(기능 집합, 대상)`로 교체한다. 차단은 일치하는 행의 합집합이고, 보고하는 effective 제한은 가장 구체적인 행이다.
- **생성 command:** 3.1의 `OperatorCommandService`에 command type 넷(`restrict-<기능>`)을 더한다. 준비 응답이 영향 미리보기를 싣고, 확정은 허가 소비·대상 재검사·제한 행·감사 `COMPLETED`를 한 트랜잭션에 쓴다.
- **화면:** 운영자 화면에 제한 생성과 활성 제한 목록을 더한다. 사용자 대시보드는 계산 표시가 제한되면 계산 값 대신 제한 상태를 보여준다.

## Boundaries & Constraints

**Always:**
- **허용 조합(AC-1).** 서버가 검증하고 다른 조합은 `UNSUPPORTED_SCOPE`다.
  - `NEW_CONNECTION`·`ADVERTISING`은 `GLOBAL`만 받는다.
  - `SYNC`는 `GLOBAL`·`PLAN`(ACTIVE 계획)·`PORTFOLIO`·`PROVIDER_CONNECTION`(ACTIVE 연결)을 받는다.
  - `CALCULATION_DISPLAY`는 `GLOBAL`·`PLAN`(상태 무관)·`PORTFOLIO`·`CALCULATION_POLICY_VERSION`(알려진 버전, 현재 `CALC-1.0`)을 받는다.
  - `GLOBAL`의 target은 고정값 `ALL`이다. 대상이 없거나 상태가 맞지 않으면 `TARGET_INVALID`이고, 같은 `기능·scope·target`의 활성 제한이 이미 있으면 `ALREADY_RESTRICTED`(409)다.
  - 쓰기 권한 Bybit key는 지금처럼 그 연결 시도만 거절하고 제한을 만들지 않는다.
- **검사 지점 대응(AC-4).** 아래 표가 대응의 전부다.
  - 대상 차원은 그 지점이 가진 식별자만 넘긴다. 넘기지 않은 차원의 구체 제한은 그 지점에 적용되지 않는다.
  - 판독 오류는 예외로 올라가 접수·발행을 막는다(fail-closed).

  | 검사 지점 | 기능 |
  |---|---|
  | 연결 생성의 시작·저장 | `NEW_CONNECTION` |
  | 첫 스냅샷·초기 수집의 접수·lease·진행·조회 | `SYNC` |
  | 계획 조건 조회·미리보기·확정, 첫 계획 발행 | `SYNC`·`CALCULATION_DISPLAY` |
  | 증분 동기화 접수·**lease(새로 추가)**·진행 | `SYNC` |
  | 증분 동기화 발행 | `SYNC`·`CALCULATION_DISPLAY` |
  | 계획 종료 준비·확정, 대시보드 `planEnd` 가용성 | `SYNC`·`CALCULATION_DISPLAY` |
  | `GET /api/dashboard` 결과·`/evidence`·`/adjustments` | `CALCULATION_DISPLAY` |
  | 연결 해제 | 없음(사용자 자기 보호 행위 — 2.16 결정을 유지) |
  | 광고 | 소비자 없음(Epic 4가 읽는다) |

- **계산 표시 제한(AC-5).**
  - 대시보드는 상태·계획 정체성·`nextAction`을 유지한다. `result`는 빼고 `calculationDisplay: "RESTRICTED"`를 싣는다.
  - 근거·조정 이력은 409 `CALCULATION_DISPLAY_RESTRICTED`다.
  - 원 공식 기록·포인터는 바꾸지 않는다. 화면은 제한 카피를 보이고 재시도 버튼을 두지 않는다.
- **원자성·멱등(AC-3).** 3.1 envelope·재인증·허가·`SessionRotation`·멱등 키를 그대로 쓴다.
  - 확정 Supplier 안에서 원장 잠금 → 최종 권한 → 대상 재검사 → 제한 INSERT → `COMPLETED` 순서로 쓴다.
  - 부분 유일 색인이 활성 중복을 막는다.
  - 거절 행은 롤백 뒤 따로 쓴다.
- **감사 원장·삭제(AC-6).**
  - 원장 `scope` 열은 `<기능>:<scope>`다.
  - 사용자에게 귀속된 target(계획·portfolio·연결)은 원장에 keyed digest(`ActorDigest` 키, 별도 접두사)로만 남고, 원문은 제한 행에만 있다.
  - `OperationsDeletionApi`에 `eraseRestrictionTargets(userId)`를 더한다. 그 사용자 target의 제한 행을 digest로 바꾸고 `target_user_id`를 NULL로 만든다. 원장 repository의 UPDATE는 `eraseActor` 하나로 유지된다.
  - 금융값은 원장·로그·미리보기에 없다.
- **미리보기(AC-2).**
  - 준비 응답에 `impact {blockedStages, affectedPlans, affectedConnections, remainingFeatures}`를 싣는다. 식별자는 없고 수만 담는다.
  - 운영자 화면 대화상자는 취소가 기본 포커스이고, 확인 문구(`GLOBAL`이면 대상명 `전역`을 포함)를 다시 입력해야 한다.
- **조회(AC-5).** `GET /api/operator/restrictions`는 `command` 권한으로 쓴다. 활성 제한의 기능·scope·target(비식별된 것은 `삭제됨`)·시작 시각·사유·수행자 digest를 돌려준다.
- **모듈 경계.** operations는 다른 모듈에 의존하지 않는다. 대상 검증·집계는 루트 어댑터가 각 모듈 **공개 API에 새로 더한 메서드**로 한다.
- **신규 마이그레이션.** `V18__operations_restriction_scope.sql` 하나다. expand만 하고 `command`는 nullable로 남긴다. 기존 행은 대응표대로 되채운다.
- **주석·카피.** 새로 쓰는 주석·카피는 한국어다.

**Ask First:**
- `operations_restriction.command` 열을 지우는 contract migration(rollback 호환 창).
- 제한을 해제하는 경로(3.4 범위).
- 광고 제한을 읽는 소비자를 만드는 것.

**Never:**
- 자동 신호·자동 정지(3.3), 제한 해제(3.4), 사용자 공지(3.5).
- 클라이언트만 막는 제한(UI-only).
- 원장 행 수정.
- 타 모듈 테이블 직접 접근.
- 원장·URL·로그에 사용자 귀속 원식별자를 남기는 것.
- 제한이 공식 기록·포인터를 지우거나 되돌리는 것.
- `OPERATOR_INVESTIGATION` 호출자 추가.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 준비 | 운영자, 재인증 5분 안, `POST /api/operator/commands/restrict-sync` `{scope:PLAN,target:<planId>,reason}` | 200: 3.1 필드 + `impact`, 허가 발급, 감사 `REQUESTED`(target digest) | 미지원 조합 422 `UNSUPPORTED_SCOPE`, 대상 없음·비활성 422 `TARGET_INVALID`, 중복 409 `ALREADY_RESTRICTED` |
| 확정 | 문구 일치 | 제한 행과 `COMPLETED`를 한 트랜잭션에, 세션 회전 | 확정 순간 대상이 사라지거나 중복이면 `DENIED`, 제한 없음 |
| 재전송 | 같은 멱등 키 | 저장된 결과, 회전 없음, 제한 1행 | — |
| 계획 scope | `SYNC:PLAN` A | A의 증분 동기화 접수 409, lease된 작업은 `BLOCKED`, 발행 롤백 | 다른 계획 B는 정상 |
| 전역 연결 | `NEW_CONNECTION:GLOBAL` | 연결 시작·저장 403 | 기존 계획의 동기화·대시보드는 정상 |
| 표시 제한 | `CALCULATION_DISPLAY:CALCULATION_POLICY_VERSION` `CALC-1.0` | 대시보드 `result` 없음 + `RESTRICTED`, 근거·조정 409 | 공식 계산 행·포인터 불변, 동기화 접수 정상 |
| 광고 | `ADVERTISING:GLOBAL` | 제한 행·감사만 생성 | 다른 기능 영향 없음 |
| 목록 | `GET /api/operator/restrictions` | 활성 제한, 구체 우선 정렬 | 비운영자·`audit`만 가진 운영자 404 |
| 삭제 | `eraseRestrictionTargets(u)` | u 귀속 target 행만 digest·NULL, 멱등 | 타 사용자·`GLOBAL`·정책 버전 행 불변 |

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/epics.md:2857-2894` -- 본문·AC. `EPIC-IMPLEMENTATION-READINESS.md` §6(`OPS-SIGNAL-1.0`), `ARCHITECTURE-SPINE.md:150` AD-17.
- `operations/OperationalRestrictionApi.java`·`internal/OperationalRestrictionService`·`Repository:29-41` -- 지금의 판독 한 문장(`count(*) WHERE command AND statement_timestamp()` 구간). evaluator로 교체한다. 테이블 `V4__source_connection.sql:177-200`, 최고 마이그레이션 V17.
- **판독 지점** (`backend/src/main/java/com/bokrilog/`)
  - `BybitConnectionService.java:186,405`
  - `plan/internal/PlanConditionService.java:374-377`
    - 호출처: `state`:81, `preview`:110·135, `confirm`:154·159·183, `publishableTerms`:249, `createActivePlan`:311
    - Terms에 portfolioId·connectionId·calculationPolicy가 있다.
  - `plan/internal/PlanEndService.java:63,77`
  - `sync/internal/PlanSyncGuard.java:123-127`
    - 인자 없는 `restricted()`라서 Base·Lease의 planId·portfolioId·connectionId·calculationPolicy를 넘기도록 바꾼다.
    - 호출처: `PlanSyncService.java:160,350,1012,1095,1135,1167,1321`, `PlanSyncPublisher.java:97,150,274,280`
  - `sync/internal/SyncJobService.java:79-82,206-207,703-708`
  - `sync/internal/PlanPublicationService.java:696`(대시보드 planEnd), `:1448-1453`, `dashboard()`:603·`bundle()`:696·`evidence()`:989
  - `AdjustmentHistoryService`:84-313
- **lease 공백:** 증분 동기화 `PlanSyncService.claim()`:385, `PlanSyncRepository.claim()`:259. claim과 같은 트랜잭션에 SYNC 검사를 더한다.
- **command 확장 자리**
  - `operations/OperatorCommandApi.java`: 상수 :29-67, `ExportResult`·`completeExport`:184-198, `Replay.Stored`:201-210
    - 결과를 sealed `CommandResult`(Export | Restriction)로 일반화한다. 허가 결과는 4096B 상한이다.
  - `operations/internal/OperatorEnvelopeService.java`: `isSupported`:79, `permissionsOf`:84, `validate`:93-120(문구 :117), `SAFE_SCOPE`/`SAFE_TARGET`:53, `completeExport`:205-232, `replay`:234
  - `OperatorAuditRepository.completed()`:205-223. `details` 키를 읽는다.
  - 루트 `OperatorCommandService.java`: 권한 :140-146·230-238, Supplier :261-274, 실행 뒤 :285-304, `replayed`:359
  - `OperatorController.java`: REFUSALS :100-120(`TARGET_INVALID` 문구가 추출 전용), `PreparedView`:141, `CompletedView`:144, 직렬화 :245·289, `proposalOf`:352
  - `OperatorGate.java:36`. 새 type은 경로 변경 없이 `isSupported`로 열린다.
- **digest·삭제:** `operations/internal/ActorDigest.java`. `of(UUID)` 접두사 `operator:`가 고정이라 target용 접두사 메서드를 더한다. `OperationsDeletionApi`·`Service`(`eraseActor`).
- **대상 공개 API(없음 → 더함)**
  - plan: `PlanConditionApi`에 `planTarget(planId)`(userId·portfolioId·status·calculationPolicy)와 count를 더한다.
  - portfolio: `PortfolioMembershipApi`에 `portfolioTarget(portfolioId)`를 더한다.
  - source: `SourceConnectionApi`에 `connectionTarget(connectionId)`(userId·portfolio 무관·revoked)와 `countActiveConnections()`를 더한다.
  - 모든 메서드는 userId 없이 id로 찾는다. `ModuleBoundaryTest.java:49-155`가 사이클·internal·servlet을 금지한다.
- **대시보드**
  - `DashboardController.java`: `DashboardView`:120-130(NON_NULL), 상태 조합 :258-275, `ResultView`:204, evidence 오류 :648-677, adjustments :823-853, code 상수 :89-99
  - 프런트 `session/useDashboard.ts` `parseDashboard`:1102-1160(모르는 모양은 `null` → 실패 처리), `components/PlanResultPanel.tsx`(result 없음 가드 :635, Notice :433-471), `content/planResult.ts`
- **운영자 화면**
  - `screens/OperatorScreen.tsx`: 섹션 :268-498, `dialogOpen`/`busy`:187, 단일 live region :519-564
  - `session/useOperatorExport.ts`(상태 기계·멱등 키 재사용·불확실), `session/operatorApi.ts`:61-232, `content/operator.ts`(REFUSALS :55, 금지 표현 목록 :211)
  - 대화상자 패턴은 `OperatorExportDialog` + `components/useConfirmationDialog.ts`다.
- **시험**
  - `AuthFlowTestBase.java:228` TRUNCATE, `restrictCommand(String)`:895-904(id 반환 없음)
  - 직접 INSERT: `IncrementalSyncTest:659,782`, `PlanVerificationTest:778,888`, `FirstSnapshotTest:557,604,858,1277,1668,2009,2192,2687,3226`(:910은 plpgsql 트리거 안)
  - `OperatorTestBase.java:33-156`
  - `PlanPublicationServiceTest.java:322-385`: `isRestricted` mock

## Tasks & Acceptance

**Execution:**
- [x] `db/migration/V18__operations_restriction_scope.sql` -- 테이블과 색인을 바꾼다.
  - 열을 더한다: `feature`·`scope`·`target`(CHECK, NOT NULL), `target_user_id uuid NULL`, `operation_id text NULL`, `target_erased_at NULL`.
  - 기존 command 행을 대응표대로 `(기능, GLOBAL, ALL)`로 되채운다.
  - `command`는 NULL을 허용한다.
  - 색인: 판독용 `(feature, scope, target)`, 활성 중복 방지용 부분 유일 색인(`effective_until IS NULL`), `(target_user_id)`.
- [x] `operations/RestrictionFeature`·`RestrictionScope`·`RestrictionSubject`·`OperationalRestrictionApi`(evaluate·list·생성 port)·`internal/` 구현 -- evaluator, 구체 우선 effective 선택, 허용 조합표, 활성 목록, `eraseRestrictionTargets`.
- [x] `operations/OperatorCommandApi`·`OperatorEnvelopeService`·`OperatorAuditRepository` -- `restrict-*` 넷의 검증·문구·target digest를 더한다. `completeRestriction`(잠금 → 최종 권한 → 대상 재검사 콜백 → INSERT → `COMPLETED`)을 두고, 결과·replay를 sealed로 일반화한다.
- [x] plan·portfolio·source 공개 API와 `internal` 구현 -- id 기반 대상 조회와 집계.
- [x] 루트 `RestrictionTargets.java`(새 파일)·`OperatorCommandService`·`OperatorController`
  - 대상 검증·`impact` 계산을 한다.
  - `GET /api/operator/restrictions`를 더한다.
  - REFUSALS에 `ALREADY_RESTRICTED`를 더하고 `TARGET_INVALID` 문구를 일반화한다.
- [x] 7개 판독 지점 + 증분 동기화 claim + 대시보드·근거·조정 이력 -- 대응표대로 evaluator로 바꾼다. `DashboardController`에 `calculationDisplay`와 409 code를 더한다.
- [x] 프런트엔드
  - `session/useDashboard.ts`·`PlanResultPanel`·근거·조정 패널·`content/planResult.ts`: 표시 제한 상태를 그린다.
  - `OperatorScreen`에 제한 섹션을 둔다(기능·scope·target·사유 입력 → 미리보기 → 확인 대화상자 → 확정·취소, 활성 목록).
  - `session/useOperatorRestriction.ts`·`operatorApi.ts`·`content/operator.ts`를 함께 고친다.
  - live region은 하나로 합친다.
- [x] 시험
  - 백엔드
    - `RestrictionScopeTest`: 조합·대상·중복·쓰기 key 개별 거절·우선순위.
    - `OperatorRestrictionTest`: 준비·확정·재전송·확정 순간 경합·원장 digest·목록·삭제.
    - `RestrictionEnforcementTest`: 지점별 차단과 독립성 행렬, 실행 중 lease·발행 재검사, 표시 제한 중 기록 불변.
    - 기존 시험의 command INSERT는 helper `restrict(feature, scope, target)`·`lift(id)`로 옮긴다. 트리거 안 SQL은 새 열로 고친다.
    - `PlanPublicationServiceTest` mock을 갱신하고 `ModuleBoundaryTest`를 통과시킨다.
  - 프런트엔드: 운영자 화면·훅·대시보드 표시 제한 시험, e2e `operator.spec.ts`의 주입 origin `restrict-advertising` 왕복과 axe 검사.
- [x] 문서
  - `CLAUDE.md`: 엔드포인트, 계약 한 줄, 진행 지점.
  - `DEVELOPMENT.md`: 필요하면 고친다.

**Acceptance Criteria:**
- Given `SYNC:GLOBAL`·`NEW_CONNECTION:GLOBAL`·`CALCULATION_DISPLAY:GLOBAL`·`ADVERTISING:GLOBAL` 중 하나만 활성일 때, when 네 기능의 대표 경로를 부르면, then 그 기능만 막힌다(독립성 행렬 4×4).
- Given 증분 동기화 작업이 lease된 뒤 그 계획에 `SYNC:PLAN` 제한이 생기면, when worker가 진행·발행하면, then 공식 포인터는 바뀌지 않고 작업은 `BLOCKED`다.
- Given 운영 소스를 훑으면, then `isRestricted(String`과 `operations_restriction`의 `command` 판독이 0건이다.
- Given 원장 repository를 훑으면, then UPDATE는 여전히 `eraseActor` 한 문장이고, 사용자 귀속 scope의 원장 target은 64자 hex digest다.
- Given 백엔드 build·프런트엔드 전체 검증·`verify-same-origin.sh`를 돌리면, then 모두 통과한다.

## Spec Change Log

- 2026-10-09 리뷰 patch 루프(blind·edge-case·verification-gap): patch 23건·defer 1건. 주요 patch — 운영자 화면의 감사·제한 영역을 각 조회로 독립 판정(제한 목록 실패는 실패 문장·재조회, 확정 거절 뒤 목록 재조회), 표시 제한 대시보드가 계산 값(`result`·`change`·완료 목표값)만 빼고 동기화·정밀 검증·계획 종료·종료/완료 기록 사실·종료 후 현재가치를 유지(화면은 읽기 전용 블록, 재시도 없음), 근거·조정 이력의 409를 별도 `restricted` 결말로, 계획 종료가 계획의 portfolio·연결 차원도 넘기고 영향 미리보기 단계가 실제 차단 지점과 일치, 초기 수집 intent 판정은 동기화만 봄, 판독·목록 정렬 통일, 부분 유일 색인 위반을 `ALREADY_RESTRICTED` 거절로, 준비 순서를 멱등 키·재인증 판정 뒤 대상 검사로, envelope 정책 `OPERATOR-COMMAND-1.1`, `TARGET_INVALID` 문구를 command별로, V18 머리말에 rollback 한계, 새 주석 한국어화, 시험 보강(lease claim 단위 시험, portfolio·연결·정책 차원, ENDED·COMPLETED·과거 조회 표시 제한, 동시 확정 경합, V18 되채우기 probe, 콜론 scope 필터·영향 수, 화면 불확실·불일치·취소). SPEC 본문 변경 없음.

- 2026-10-09 Matrix Test Audit(step-03): 9행 모두 새 백엔드 시험이 덮고 Testcontainers(PostgreSQL 18.6) 빌드 1208건 통과. 주입 origin e2e에서 3.1 시험의 `사유(필수)`·`본인 확인 필요` locator가 새 제한 입력란·감사 행과 부분 일치해 깨진 것을 `exact: true`로 고쳤다(새 스택 67건 통과). SPEC 본문 변경 없음.

## Design Notes

- **NEW_CONNECTION은 연결 생성만 막는다.** 지금은 첫 스냅샷·초기 수집·발행도 `bybit-connection-create`를 함께 본다. 그러면 신규 연결 정지가 동기화를 멈춰 NFR-17의 독립성이 깨진다. 그래서 그 파이프라인은 `SYNC`로 옮긴다. 의도된 동작 변경이다.
- **"구체 우선"은 차단을 줄이지 않는다.** 제한 행만 있고 허용 행이 없으므로 차단은 합집합이다. 우선순위는 어떤 제한이 효력을 갖는지 보고(목록 정렬, 3.5 공지 매핑)하는 데만 쓴다. 범위가 겹친 해제의 의미는 3.4가 이 순서로 정한다.
- **대상 차원을 넘기지 않으면 그 구체 제한은 적용되지 않는다.** 예를 들어 첫 스냅샷에는 계획이 없으므로 `SYNC:PLAN`이 적용되지 않는다. "모름"을 전부 차단으로 접으면 계획 하나의 제한이 모든 온보딩을 막는다. 그래서 각 지점이 넘기는 차원을 대응표·시험이 고정하고, 판독 실패만 fail-closed로 둔다.
- **원장 target을 digest로 남기는 이유.** 3.1이 원장의 원식별자를 금지했다. 운영자는 제한 목록(원문)으로 대상을 보고, 원장 operation 조회로는 사실과 순서를 본다.
- **연결 해제가 제한을 보지 않는 이유(2.16 → 여기로 옮김).** 해제는 자격정보를 지우는 사용자 보호 행위다. 제한 중에 막으면 장애가 사용자의 키 철회를 붙잡는 꼴이 된다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test && npm --prefix frontend run test:e2e` -- expected: 통과.
- `deploy/compose/verify-same-origin.sh` -- expected: 종료 코드 0.
- `grep -rn "isRestricted(" backend/src/main` -- expected: 0건.

## Suggested Review Order

**제한 모델과 evaluator**

- 진입점: 기능 집합과 대상 차원으로 판정하고, 효력 제한은 가장 구체적인 행이다
  [`OperationalRestrictionApi.java:43`](../../backend/src/main/java/com/bokrilog/operations/OperationalRestrictionApi.java#L43)

- 기능별 허용 scope 닫힌 조합(AC-1)
  [`RestrictionFeature.java:13`](../../backend/src/main/java/com/bokrilog/operations/RestrictionFeature.java#L13)

- 판독·목록이 같은 정렬(구체성 → 시작 → ID)을 쓰는 SQL
  [`OperationalRestrictionRepository.java:51`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalRestrictionRepository.java#L51)

- 스키마 expand, 옛 command 되채우기, 활성 중복 부분 유일 색인
  [`V18__operations_restriction_scope.sql:37`](../../backend/src/main/resources/db/migration/V18__operations_restriction_scope.sql#L37)

**제한 생성 command(준비→확정)**

- 준비 순서: 멱등 키 → 재인증 → 대상·중복 검사 → 미리보기
  [`OperatorCommandService.java:182`](../../backend/src/main/java/com/bokrilog/OperatorCommandService.java#L182)

- 확정 트랜잭션: 잠금 → 최종 권한 → 대상 재검사 → INSERT → `COMPLETED`
  [`OperatorEnvelopeService.java:357`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorEnvelopeService.java#L357)

- 사용자 귀속 target은 원장에 keyed digest로만
  [`OperatorEnvelopeService.java:170`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorEnvelopeService.java#L170)

- 대상 검증·영향 미리보기는 루트 어댑터가 모듈 공개 API로
  [`RestrictionTargets.java:126`](../../backend/src/main/java/com/bokrilog/RestrictionTargets.java#L126)

- 활성 목록 경로와 409 `ALREADY_RESTRICTED`
  [`OperatorController.java:60`](../../backend/src/main/java/com/bokrilog/OperatorController.java#L60)

**검사 지점 교체**

- 증분 동기화 lease에 새로 더한 SYNC 검사
  [`PlanSyncService.java:400`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L400)

- 증분 동기화가 넘기는 대상 차원(plan·portfolio·connection)
  [`PlanSyncGuard.java:124`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncGuard.java#L124)

- 대시보드 표시 제한을 같은 읽기 트랜잭션에서 판정
  [`PlanPublicationService.java:614`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationService.java#L614)

- 계획 종료가 lineage의 portfolio·연결 차원까지 넘긴다
  [`PlanEndService.java:65`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanEndService.java#L65)

- 계획 조건은 SYNC·CALCULATION_DISPLAY, 초기 수집은 SYNC만
  [`PlanConditionService.java:94`](../../backend/src/main/java/com/bokrilog/plan/internal/PlanConditionService.java#L94)

- 첫 스냅샷·초기 수집은 신규 연결 제한에서 분리(의도된 변경)
  [`SyncJobService.java:73`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L73)

- 표시 제한 응답: 계산 값만 빼고 상태·다음 행동 유지
  [`DashboardController.java:271`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L271)

**화면**

- 감사·제한 영역을 각 조회 결과로 독립 판정
  [`OperatorScreen.tsx:210`](../../frontend/src/screens/OperatorScreen.tsx#L210)

- 제한 확인 대화상자: 취소 기본 포커스, 확인 문구 재입력
  [`OperatorRestrictionDialog.tsx:46`](../../frontend/src/components/OperatorRestrictionDialog.tsx#L46)

- 대시보드 표시 제한 파싱과 렌더
  [`useDashboard.ts:1123`](../../frontend/src/session/useDashboard.ts#L1123)
  [`PlanResultPanel.tsx:458`](../../frontend/src/components/PlanResultPanel.tsx#L458)

- 근거·조정 이력의 409는 재시도 없는 제한 결말
  [`useReferencedRequest.ts:11`](../../frontend/src/session/useReferencedRequest.ts#L11)

**주변**

- 삭제 port: 사용자 귀속 target 비식별(원장 UPDATE는 그대로 하나)
  [`OperationalRestrictionRepository.java:138`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalRestrictionRepository.java#L138)

- 시험: 독립성 행렬·lease·표시 제한·scope별 실제 지점
  [`RestrictionEnforcementTest.java:1`](../../backend/src/test/java/com/bokrilog/RestrictionEnforcementTest.java#L1)
  [`OperatorRestrictionTest.java:1`](../../backend/src/test/java/com/bokrilog/OperatorRestrictionTest.java#L1)
  [`RestrictionMigrationTest.java:1`](../../backend/src/test/java/com/bokrilog/RestrictionMigrationTest.java#L1)
