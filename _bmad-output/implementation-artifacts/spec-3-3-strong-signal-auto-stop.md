---
title: 'Story 3.3 — 승인된 강한 신호를 감지해 60초 안에 자동 정지하기'
type: 'feature'
created: '2026-10-09'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'c1e5d9ddfb72d7714b7144c559bf555c846948b7'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-2-scoped-manual-restriction.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 3.2가 만든 `기능 × scope × target` 제한은 운영자 command로만 생긴다. 운영 시간 밖(영업일 09:00~18:00 KST 밖)에는 중대 신호가 나도 정지할 주체가 없다. 판정표 `OPS-SIGNAL-1.0`(`EPIC-IMPLEMENTATION-READINESS.md` §6.1)은 문서에만 있고, 감사 원장에 SYSTEM 행을 쓰는 경로도 없다(`epics.md` Story 3.3 AC-1~6).

**Approach:**
- **신호 기반을 만든다.** 판정표를 버전 seed로 저장하고, 감지 지점이 부르는 `raise(신호)` 하나를 둔다. `raise`는 짧은 별도 트랜잭션 하나에 네 가지를 쓴다:
  - 신호 기록
  - 판정된 scope의 제한
  - 감사 원장 SYSTEM 행
  - 경보 로그
- **60초 보장.** 백그라운드 detector 없이 감지 순간에 동기로 정지한다.
- **이번에 연결하는 신호는 넷이다** — 감지 지점이 이미 있는 것들이다.
  - 4: 같은 입력·정책의 다른 result hash
  - 5: 행 잠금 아래 포인터 CAS 위반
  - 6: 같은 source ID 의미 충돌
  - 7: 미인식 Transaction Log enum
- **미루는 신호.**
  - 1·8·9: 후속 스토리
  - 2·3: `deferred-work.md` 기록, 3번은 3.7과 함께

## Boundaries & Constraints

**Always:**
- **판정표(AC-1).** `V19__operations_signal.sql`이 `operations_signal_policy`에 `OPS-SIGNAL-1.0` 9행을 seed로 넣는다. 각 행은 `signal_type`·threshold·window·필수 증거·scope 규칙이다. 코드의 신호 enum·현행 버전 상수와 seed가 어긋나면 시험이 실패한다.
  - 판정표에 없는 신호 종류와 필수 증거가 빠진 신호는 승격하지 않고 거절한다(예외).
  - 단일 사용자의 인증 만료, timeout, rate limit, 5xx, 가격 결측, 정상 경합(`PointerMoved`)은 신호가 아니다.
- **연결 신호와 scope(판정표대로).**

  | 신호 | 감지 지점 | 자동 제한 |
  |---|---|---|
  | 4 다른 result hash | 계산 INSERT 직전, 같은 `(input_hash, calculation_policy)`의 다른 `result_hash` | `CALCULATION_DISPLAY:CALCULATION_POLICY_VERSION`(그 정책). 정책을 모르면 `SYNC:GLOBAL`+`CALCULATION_DISPLAY:GLOBAL` |
  | 5 CAS 불변식 | portfolio 행 잠금 아래 CAS 실패(현재 `IllegalStateException`). portfolio가 구분된 예외를 던지고 sync가 잡는다 | `SYNC:PORTFOLIO`+`CALCULATION_DISPLAY:PORTFOLIO`. portfolio를 모르면 위 전역 둘 |
  | 6 source ID 의미 충돌 | `PROVIDER_ID_CONFLICT` 관측과 검증 대조 `CONFLICT` | 계획 작업이면 `SYNC:PLAN`, 계획 없는 초기 수집이면 `SYNC:PROVIDER_CONNECTION` |
  | 7 미인식 enum | 수집 파싱의 `UNKNOWN_EVENT` | `SYNC:PROVIDER_CONNECTION` |

- **60초·원자성(AC-2).**
  - 감지 지점은 자기 트랜잭션의 결말과 무관하게 `raise`를 별도 트랜잭션으로 부른다. 롤백될 트랜잭션 안에서 부르면 신호가 사라진다.
  - `raise`는 원장 잠금 → 판정표 읽기 → 중복 판정 → 제한 INSERT → SYSTEM 감사 행을 한 커밋으로 쓴다. 커밋 직후 evaluator가 차단한다.
  - 감지 시각과 제한 효력 시각은 DB 시각이다. SLI는 그 차(ms)이며 신호 행과 감사 `details`에 남는다.
  - `raise`가 실패하면 예외가 감지 지점까지 올라가 그 작업을 끝내지 않는다(fail-closed, 재시도는 기존 lease 규칙).
  - 판정표를 읽지 못하거나 행이 없으면 `NEW_CONNECTION:GLOBAL`+`SYNC:GLOBAL`로 제한한다(사유 `POLICY_UNAVAILABLE`).
- **중복·병합(AC-3).**
  - fingerprint는 `신호 종류 + 정책 버전 + 판정 scope + target`이다.
  - 같은 fingerprint에 활성 제한이 있으면 새 제한 없이 신호 행만 그 제한에 연결한다(`LINKED`).
  - 더 넓거나 좁은 신호는 각자 제한을 더한다. 차단이 합집합이라 결과가 병합이다.
  - 신호는 제한을 지우거나 줄이지 못한다.
  - 동시 같은 fingerprint는 원장 잠금 아래에서 제한 1행이 된다.
- **표시·자동 복구 금지(AC-5).**
  - 신호가 만든 제한 행은 `origin='SIGNAL'`이고 `operation_id`는 그 신호의 operation ID다. 그래서 3.2의 부분 유일 색인이 적용된다.
  - 운영자 목록(`GET /api/operator/restrictions`)은 `origin`을 싣고, 화면은 출처 `자동 정지`와 상태 `수동 검증 필요`를 보인다.
  - 해제 경로는 없다(3.4).
- **감사·경보(AC-4).**
  - 감사 행은 `actor_kind='SYSTEM'`, command `auto-restrict`, scope `<기능>:<scope>`이다. target은 3.2 규칙대로 사용자 귀속이면 digest다. reason은 신호 종류 code, `details`는 `{signalId, policyVersion, sliMs, outcome}`이다.
  - 경보는 WARN 구조화 로그 한 줄이다. 허용 필드는 신호 종류·정책 버전·`기능:scope`·`CREATED|LINKED`·`sliMs`뿐이다. 식별자·금융값·hash 원문은 넣지 않는다. Actuator·metric은 더하지 않는다.
- **증거·삭제(AC-6).**
  - 신호 행의 증거는 내부 참조(작업 ID·계산 ID·관측 행 ID)와 사용자 분리 열 `target_user_id`뿐이다. 금융값·자격정보·이메일·원 hash는 넣지 않는다.
  - `OperationsDeletionApi.eraseRestrictionTargets(userId)`는 신호 행의 `target_user_id`도 NULL로 비식별한다. 원장 repository의 UPDATE는 여전히 `eraseActor` 하나다.
- **모듈.** `operations`는 다른 모듈에 의존하지 않는다. 신호 API(`OperationalSignalApi`)는 `operations` 공개 API다. portfolio는 구분된 예외만 던지고 operations를 부르지 않는다.
- **마이그레이션.** 신규는 `V19__operations_signal.sql` 하나다. 신호 테이블, 판정표 seed, `operations_restriction.origin`(기본 `OPERATOR`) expand를 담는다.
- **주석·카피.** 새 주석·카피는 한국어다.

**Ask First:**
- 1·8·9번 신호를 이번에 연결하는 것(후속 스토리로 미뤘다).
- 백그라운드 detector·주기 작업을 더하는 것.
- Actuator·metric 의존을 더하는 것.
- 신호가 제한을 해제·축소하는 경로.

**Never:**
- 자동 복구(3.4).
- 사용자 공지(3.5).
- 2·3번 신호 연결.
- 정상 경합·단일 사용자 오류의 승격.
- 원장 행 수정.
- 타 모듈 테이블 직접 접근.
- 경보·로그에 식별자·금융값 기록.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| hash 불일치 | 같은 `(input_hash, CALC-1.0)`에 다른 `result_hash`의 계산을 발행하려 함 | 발행 롤백, `CALCULATION_DISPLAY:CALCULATION_POLICY_VERSION CALC-1.0` 제한·SYSTEM 감사·WARN 1줄, 대시보드 `RESTRICTED` | 같은 result hash면 신호 없음 |
| CAS 위반 | portfolio 행 잠금 아래 CAS 실패 | 작업 실패 코드, `SYNC:PORTFOLIO`·`CALCULATION_DISPLAY:PORTFOLIO` 제한 | 정상 경합(`PointerMoved`)은 신호 없음 |
| ID 충돌·계획 | 증분 동기화 중 `PROVIDER_ID_CONFLICT` | 작업 `NEEDS_REVIEW` 유지, `SYNC:PLAN` 제한 | 다른 계획은 정상 |
| ID 충돌·초기 | 계획 없는 초기 수집 중 충돌 | `SYNC:PROVIDER_CONNECTION` 제한 | — |
| 미인식 enum | 수집 페이지에 모르는 type | `SYNC:PROVIDER_CONNECTION` 제한 | — |
| 반복 | 같은 fingerprint 두 번(순차·동시) | 제한 1행, 신호 2행(`CREATED`·`LINKED`) | — |
| 판정표 장애 | seed 행 없음 | `NEW_CONNECTION:GLOBAL`+`SYNC:GLOBAL`, 사유 `POLICY_UNAVAILABLE` | — |
| 비신호 | 미승인 신호 종류·필수 증거 누락 | 예외, 제한 없음 | 감지 지점 작업은 실패로 끝남 |
| 60초 | 감지 → 커밋 | `sliMs` < 60000, 커밋 직후 evaluator 차단 | — |
| 운영자 목록 | `GET /api/operator/restrictions` | `origin: SIGNAL`, 화면 `자동 정지`·`수동 검증 필요` | — |
| 삭제 | `eraseRestrictionTargets(u)` | u 신호 행 `target_user_id` NULL | 타 사용자 불변 |

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/epics.md` Story 3.3(`### Story 3.3`) -- AC-1~6. `EPIC-IMPLEMENTATION-READINESS.md` §6.1(판정표 9행)·§6.2. 최고 마이그레이션 V18.
- **3.2 기반**
  - `operations/OperationalRestrictionApi.java`(evaluate·active·isActive)
  - `operations/internal/OperationalRestrictionRepository.java`
    - `insert(restrictionId, feature, scope, target, targetUserId, operationId)`:109(패키지 비공개)
    - `isActive`:93
  - V18 부분 유일 색인 :67-71(`operation_id` 있는 행)
  - 제한 생성의 유일한 호출자 `OperatorEnvelopeService.completeRestriction`:356-389. 잠금 → 권한 → 재검사 → INSERT → `COMPLETED` 순서가 신호 서비스의 본보기다.
  - `RestrictionFeature`·`RestrictionScope`(`userAttributed()`)
- **감사**
  - `OperatorAuditRepository.append(NewRow)`:66(`actorKind` 인자, `actorUserId` nullable), `NewRow`:49
  - V17 CHECK: SYSTEM이면 `actor_user_id` NULL(:52-56)
  - 현재 SYSTEM 쓰기 경로가 없다 — `OperatorEnvelopeService.java:421-428`이 `ACTOR_OPERATOR`로 고정(:48 주석 "SYSTEM은 3.3 몫")
  - `ActorDigest.ofTarget`
  - `OperationsDeletionApi`·`OperationalRestrictionRepository` UPDATE:138
- **감지 지점**
  - 4: `sync/internal/PlanPublicationRepository.java:337-370`(`insertCalculation`, `sync_plan_calculation.input_hash/result_hash` V10:211-212)
    - 호출: 첫 발행 `PlanPublicationService.java:371` 근처, 증분 `PlanSyncPublisher.java:114,238`
    - 기존 비교는 `INPUTS_CHANGED`(입력 변경)뿐이다.
  - 5: `portfolio/internal/PortfolioMembershipService.java:53-75`
    - :73 `IllegalStateException("portfolio pointer moved under its row lock")`이 위반이고, `PointerMoved`는 정상 경합이다.
    - 잡는 곳: `PlanSyncService.java:472,829,853,1245`
    - 계획 pointer `POINTER_CONFLICT`(`PlanSyncPublisher.java:148-150`)는 신호가 아니다(경합과 구분 불가).
  - 6: `sync/internal/InitialCollectionRepository.java`
    - `PROVIDER_ID_CONFLICT` 관측 :177-181·:217-222
    - 검증 대조 `CONFLICT` :386-408(V12:114,122)
    - lease 식별자: `l.userId/providerAccountId/connectionId/jobId`, 계획 lease는 `planId/portfolioId`(`PlanSyncRepository.java:39`)
  - 7: `InitialCollectionRepository.java:647-648` `UNKNOWN_EVENT`, `BybitAdapter.java:537-539` `pageReview`
- **모듈:** sync → operations 의존은 이미 있다. portfolio·operations는 의존이 없다. `ModuleBoundaryTest.java:65-76`
- **로그 패턴:** `PlanSyncService.java:1402`, `DashboardController.java:357`. Actuator 금지는 `ModuleBoundaryTest.java:102`.
- **시각:** 운영 권위는 DB(`statement_timestamp()`). 시험은 SQL로 행 시각을 옮기는 패턴이다(`PlanSyncTestBase.java:88-101`, `OperatorRestrictionTest.java:312`).
- **운영자 화면:** `OperatorController` restrictions 응답, `frontend/src/screens/OperatorScreen.tsx` 활성 목록, `session/useOperatorRestriction.ts`, `content/operator.ts`
- **시험 기반:** `AuthFlowTestBase.java:228`(TRUNCATE에 신호 테이블 추가), `restrict`/`lift` helper, `RestrictionEnforcementTest`·`OperatorRestrictionTest`

## Tasks & Acceptance

**Execution:**
- [x] `db/migration/V19__operations_signal.sql`
  - `operations_signal_policy(policy_version, signal_type, threshold, window_seconds, required_evidence, scope_rule, PK(policy_version, signal_type))`에 `OPS-SIGNAL-1.0` 9행 seed를 넣는다.
  - `operations_signal(signal_id PK, operation_id, signal_type, policy_version, fingerprint, detected_at default statement_timestamp(), evidence jsonb, target_user_id NULL, restriction_id, outcome CHECK CREATED|LINKED, sli_ms)`와 색인을 만든다.
  - `operations_restriction.origin text NOT NULL DEFAULT 'OPERATOR' CHECK(OPERATOR|SIGNAL)`를 더한다.
- [x] `operations/OperationalSignalApi.java`·`SignalType`·`Signal`(public) + `internal/OperationalSignalService`·`SignalRepository`·`SignalPolicy`
  - `raise`(REQUIRES_NEW): 잠금, 판정표, fingerprint 중복, 제한(`origin=SIGNAL`), SYSTEM 감사, WARN 경보, 정책 장애 시 전역 fallback.
  - 삭제 port를 확장한다.
  - `OperatorAuditRepository`에 SYSTEM append 경로를 더한다.
- [x] `portfolio` -- 행 잠금 아래 CAS 실패를 공개 예외 타입(예: `PortfolioPointerInvariantViolation`)으로 바꾼다.
- [x] `sync/internal`(`PlanPublicationRepository`·`PlanPublicationService`·`PlanSyncPublisher`·`PlanSyncService`·`InitialCollectionRepository`) -- 대응표의 네 지점에서 증거를 모아 롤백과 무관하게 `raise`를 부르고, 작업 결말은 기존 코드로 맺는다.
- [x] 루트 `OperatorController`·프런트(`OperatorScreen`·`useOperatorRestriction`·`operatorApi`·`content/operator.ts`) -- 활성 목록에 `origin`, 출처·`수동 검증 필요` 표시.
- [x] 시험
  - `SignalPolicyTest`: seed 9행과 상수 일치, 비신호·필수 증거, 정책 장애 fallback.
  - `StrongSignalAutoStopTest`: 네 신호 각각 실제 지점에서 제한·감사·경보, SLI < 60000, 반복·동시 1행, scope 불명 fallback, 다른 사용자 무영향, 정상 경합 무신호, 삭제.
  - 운영자 목록 `origin`.
  - 프런트 화면 시험.
  - TRUNCATE에 신호 테이블을 더한다.
- [x] 문서 -- `CLAUDE.md` 계약 한 줄(진행 지점·표 행은 done PR 몫), `DEVELOPMENT.md`는 필요할 때.

**Acceptance Criteria:**
- Given 네 신호 중 하나가 감지되면, when 감지 지점의 트랜잭션이 롤백되더라도, then 제한·신호 행·SYSTEM 감사 행이 커밋돼 있고 다음 접수·발행이 evaluator로 막힌다.
- Given 신호가 만든 제한이 있을 때, when 신호가 다시 오지 않아도, then 제한은 해제되지 않고 목록에 `수동 검증 필요`로 남는다.
- Given 경보 로그를 캡처하면, then 허용 필드 외 값(UUID·hash·금액·이메일 모양)이 0건이다.
- Given 원장 repository를 훑으면, then UPDATE는 `eraseActor` 한 문장뿐이다.
- Given 백엔드 build·프런트엔드 전체 검증·`verify-same-origin.sh`를 돌리면, then 모두 통과한다.

## Spec Change Log

- 2026-10-09 Matrix Test Audit(step-03): 11행 모두 `StrongSignalAutoStopTest`·`SignalPolicyTest`가 덮고 Testcontainers 빌드 1241건 통과. 구현이 해석한 곳 — `LINKED`는 fingerprint가 아니라 같은 기능·scope·target의 활성 제한 기준(3.2 부분 유일 색인과 정합, fingerprint는 기록), 신호 4는 같은 트랜잭션의 INSERT 직후 감지(예외가 함께 롤백), 신호를 낸 수집 작업은 자기 제한에 막히지 않고 검토 필요로 끝남(발행 없음), 신호 기록 실패는 작업을 끝내지 않고 lease에 맡김, 판정표 열 `distinct_subjects` 추가. SPEC 본문 변경 없음.

- 2026-10-09 리뷰 patch 루프(blind·edge-case·verification-gap): patch 15건·defer 1건·reject 2건. 주요 patch — 수집 경로의 제한 우회를 "그 작업을 증거(`jobId`·`attemptId`)로 가진 신호가 만들거나 연결한 제한"만 빼는 판독(`evaluate(…, excluding)`·`restrictionsRaisedBy`)으로 좁히고 intent·연결·portfolio·계정 재검사를 유지(조기 종료는 2.12 검증 대조 기록을 깨서 차선 채택, 재claim·takeover·단계 진입이 같은 판정), 신호 6·7은 페이지 트랜잭션 커밋 뒤 종류마다 `raise` 한 번, `Unraised` 상한(fencing 세대 3 → 초기 수집 `NEEDS_REVIEW`/`SIGNAL_UNRECORDED`·스냅샷 `FAILED`/`SIGNAL_UNRECORDED`·첫 발행 `PUBLICATION_INTERRUPTED`, 계획 동기화는 기존 중단 규칙), 신호 4 술어에 계산 종류·달력 정책·구현 버전, `LINKED` SLI는 그 제한의 효력 시작 기준, 판정표 장애의 감사 사유는 신호 종류(`POLICY_UNAVAILABLE`은 `scope_decision`·`details.scopeDecision`)와 코드 최소 필수 증거 선검사, V19 `per_subject_threshold`와 창 집계 검증(0건 승격 금지), 제한 목록의 `linkedSignals`·`requiresVerification`과 `activeRow`의 `effective_until IS NULL` 한정, 프런트의 모르는 출처 행 단위 안전 문구, 시험 보강(증분 발행 신호 4·첫 발행과 후속 기준 신호 5·같은 결과 재발행 무신호·판정표 거절 시 작업 실패·운영자 전역 제한이 신호 수집을 멈춤·재claim 같은 결말·기록 실패 상한·`signalRaised` 세 갈래). defer — 사용자 API가 신호 뒤 수집 상태를 감추는 변화(3.5 입력). SPEC 본문 변경 없음.

## Design Notes

- **동기 `raise`를 고른 이유.** 감지 지점이 사실을 손에 쥔 순간 정지하면 60초 SLI가 구조적으로 성립한다. 주기 detector는 놓친 실행·lease·시계를 새로 증명해야 한다. 시간 창이 필요한 8·9번이 오면 같은 `raise`를 관측 집계 뒤에 부르면 된다.
- **별도 트랜잭션인 이유.** 4·5번은 발행 트랜잭션이 롤백되는 경로에서 감지된다. 같은 트랜잭션에 쓰면 신호가 롤백과 함께 사라진다. 순서는 롤백 → `raise` 커밋이고, 그 사이 다른 작업이 막히지 않은 창은 ms 단위다.
- **계획 pointer `POINTER_CONFLICT`를 빼는 이유.** advisory 잠금 밖 정상 경합과 같은 코드로 접혀 있어 위반만 골라낼 수 없다. 오탐은 전역 정지로 번질 수 있다. portfolio 쪽 "행 잠금 아래 CAS 실패"만 구분이 확실하다.
- **fingerprint가 scope·target을 포함하는 이유.** 같은 신호 종류라도 다른 계획·연결이면 독립 사건이다. 같은 대상의 반복만 하나로 묶는다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test && npm --prefix frontend run test:e2e` -- expected: 통과.
- `deploy/compose/verify-same-origin.sh` -- expected: 종료 코드 0.
- `grep -n "UPDATE" backend/src/main/java/com/bokrilog/operations/internal/OperatorAuditRepository.java` -- expected: `eraseActor` 1건.

## Suggested Review Order

**신호 기반**

- 진입점: 원장 잠금 → 판정표 → 중복 연결 → 제한 → SYSTEM 감사를 한 커밋으로
  [`OperationalSignalService.java:97`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalSignalService.java#L97)

- 같은 대상의 활성 제한에는 새 행 없이 연결(`LINKED`)
  [`OperationalSignalService.java:146`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalSignalService.java#L146)

- 판정표 seed와 CHECK(대상당 최소 수·창)
  [`V19__operations_signal.sql:63`](../../backend/src/main/resources/db/migration/V19__operations_signal.sql#L63)

- 감사 원장의 SYSTEM 행 경로(원장 UPDATE는 그대로 하나)
  [`OperatorAuditRepository.java:103`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorAuditRepository.java#L103)

**감지 지점(sync)**

- sync 쪽 신호 자리 하나와 기록 실패 상한
  [`StrongSignals.java:37`](../../backend/src/main/java/com/bokrilog/sync/internal/StrongSignals.java#L37)

- 신호 4: 같은 입력·정책·종류의 다른 result hash
  [`PlanPublicationRepository.java:70`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanPublicationRepository.java#L70)

- 신호 5: 행 잠금 아래 CAS 실패를 구분된 예외로
  [`PortfolioPointerInvariantViolation.java:15`](../../backend/src/main/java/com/bokrilog/portfolio/PortfolioPointerInvariantViolation.java#L15)

- 증분 동기화는 롤백 뒤 신호를 올린다
  [`PlanSyncService.java:482`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L482)

- 후속 기준 첫 스냅샷의 신호 5
  [`SyncJobService.java:743`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L743)

**제한 우회의 범위**

- 신호를 낸 작업은 자기 신호 제한만 건너뛴다(나머지 재검사 유지)
  [`OperationalSignalApi.java:69`](../../backend/src/main/java/com/bokrilog/operations/OperationalSignalApi.java#L69)
  [`SyncJobService.java:88`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncJobService.java#L88)
  [`PlanSyncGuard.java:110`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncGuard.java#L110)

**화면·주변**

- 연결된 신호가 있으면 출처와 무관하게 `수동 검증 필요`
  [`OperatorController.java:174`](../../backend/src/main/java/com/bokrilog/OperatorController.java#L174)
  [`OperatorScreen.tsx:123`](../../frontend/src/screens/OperatorScreen.tsx#L123)

- 시험: 네 신호의 실제 지점·반복·동시·fallback·상한·운영자 전역 정지
  [`StrongSignalAutoStopTest.java:1`](../../backend/src/test/java/com/bokrilog/StrongSignalAutoStopTest.java#L1)
  [`SignalPolicyTest.java:1`](../../backend/src/test/java/com/bokrilog/SignalPolicyTest.java#L1)
