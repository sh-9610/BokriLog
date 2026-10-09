---
title: 'Story 3.4 — 검증 증거를 확인한 뒤 제한을 수동 복구하기'
type: 'feature'
created: '2026-10-10'
status: 'done'
review_loop_iteration: 0
baseline_commit: '8896362c8c00d61999cf2098a4283653bd8b948a'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-3-strong-signal-auto-stop.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 3.2·3.3이 만든 제한(운영자 command·강한 신호)은 풀 길이 없다. `effective_until`은 늘 NULL로 남는다. 운영자는 원인이 해소돼도 제한 범위만 재개할 수 없고, 해제가 어떤 증거로 이뤄졌는지 남길 곳도 없다(`epics.md` Story 3.4 AC-1~5. AC-6 훈련 증거는 `deferred-work.md`로 분리).

**Approach:**
- **해제 command.** 3.1 envelope에 운영 command `lift-restriction` 하나를 더한다. target은 제한 ID다.
  - 준비가 받는 것: 복구 조건 네 가지 확인(`OPS-SIGNAL-1.0` §6.2: 원인 제거, 영향 범위 재현 시험, 고정 회귀·소유권·pointer 검사, 새 배포·버전 증거), 원인·수행한 검증·증거 참조·배포 버전.
  - 준비가 돌려주는 것: 재개될 단계, 남는 제한, 연결된 신호 계보.
- **확정.** 제한 행 하나를 조건부로 닫고 복구 감사 행을 같은 커밋에 쓴다.
- **확정 뒤 smoke.** evaluator로 재개와 잔존을 확인하고, 어긋나면 같은 범위를 새 제한으로 되돌린다.
- **화면.** 운영자 화면 활성 목록의 각 행에 복구 흐름을 둔다.

## Boundaries & Constraints

**Always:**
- **자격(AC-1).**
  - 대상은 지금 효력 중인 제한 하나다(`effective_until IS NULL`).
  - 이미 닫힌 제한은 거절한다(`RESTRICTION_NOT_ACTIVE`, 409). 단, 같은 멱등 키의 재전송은 저장 결과를 돌려준다.
  - 없는 ID는 `TARGET_INVALID`다.
  - 권한은 `command`다. 재인증·허가·`SessionRotation`·멱등 키는 3.1 그대로다.
  - 확정 트랜잭션에서 원장 잠금 → 최종 권한 → 제한 행 잠금 순서를 거친 뒤 두 가지를 다시 본다. 하나라도 어긋나면 `DENIED`이고 아무것도 바꾸지 않는다.
    - 그 제한이 아직 열려 있는가.
    - 준비 이후 그 제한에 새 신호가 연결되지 않았는가(연결 신호 수·최신 감지 시각 비교, 어긋나면 `NEW_SIGNAL_SINCE_PREPARE`).
- **증거 입력(AC-2).**
  - 복구 조건 네 코드(`CAUSE_REMOVED`·`IMPACT_REPRODUCED`·`REGRESSION_OWNERSHIP_POINTER_PASSED`·`NEW_DEPLOY_EVIDENCE`)를 모두 확인해야 한다. 빠지면 `RECOVERY_CONDITION_MISSING`(422)다.
  - 원인·수행한 검증·증거 참조는 각각 필수 텍스트이고 3.1 사유 규칙(길이·이메일 모양 거절)을 따른다.
  - 배포 버전은 필수이고 `[A-Za-z0-9._+-]{1,64}`이다.
  - 원장에는 사유 = 원인, `details` = `{conditions, verification, evidenceRef, deployVersion, restrictionId, linkedSignals}`가 남는다. 금융값·이메일·사용자 원식별자는 남기지 않는다.
- **미리보기.** 준비 응답에 `impact {reopenedStages, remainingRestrictions[{feature, scope, origin}], linkedSignals, latestSignalAt}`를 싣는다. 식별자는 싣지 않는다.
  - `remainingRestrictions`는 해제 뒤에도 같은 기능을 막는 다른 활성 제한(더 넓은 scope·같은 대상의 다른 출처)이다.
  - 확인 문구는 `복구 <기능 라벨> <scope 라벨> <target 라벨>`이다. `GLOBAL`이면 `전역`이 들어가 대상명을 다시 입력하게 된다.
  - 화면 대화상자는 취소가 기본 포커스다.
- **원자 재개(AC-3).**
  - `UPDATE operations_restriction SET effective_until = statement_timestamp(), lifted_by_operation_id = :op WHERE restriction_id = :id AND effective_until IS NULL` 한 문장과 감사 `COMPLETED`를 같은 트랜잭션에 쓴다. 갱신 행이 0이면 `DENIED`다.
  - evaluator는 그 행 하나만 빠지고, 더 넓은 제한·독립 기능 제한·같은 대상의 다른 제한은 그대로 막는다.
  - 신호 행·원장 행은 고치지 않는다.
- **자동 재개 차단(AC-4).**
  - `effective_until`을 쓰는 운영 SQL은 위 한 문장뿐이다. 시험이 운영 소스를 훑어 강제한다.
  - 시간 경과·신호 부재·재시작으로 바뀌는 경로가 없다. 신호(`raise`)는 계속 닫지 못한다.
  - 사용자 UI·다른 HTTP 경로에서 도달하지 못한다.
- **smoke(AC-5).**
  - 커밋 직후 해제된 제한의 대상 차원으로 evaluator를 부른다. 확인하는 것은 둘이다.
    - 그 제한 ID가 더 이상 효력 제한으로 보고되지 않는다.
    - `remainingRestrictions`가 여전히 막는다.
  - 결과는 확정 응답 `smoke {status: PASSED|REVERTED, reopenedStages, stillBlockedStages}`에 싣는다.
  - 어긋나면 같은 `기능·scope·target`의 새 제한(`origin=OPERATOR`, 같은 operation ID)과 감사 `FAILED`(code `SMOKE_FAILED`)를 별도 트랜잭션으로 쓴다.
  - smoke 실패는 확정 자체를 되돌리지 않는다 — 해제 사실과 되돌림 사실이 둘 다 원장에 남는다.
- **화면.**
  - 활성 목록 각 행에 `복구` 동작이 있다.
  - 폼 → 준비(미리보기) → `useConfirmationDialog` 대화상자(문구 재입력) → 확정·취소 순서다. 결과는 smoke 상태다. 화면당 live region은 하나다.
  - `수동 검증 필요` 행은 연결 신호 계보(수·최신 시각)를 미리보기에 보인다.
- **마이그레이션.** 신규는 `V20__operations_restriction_lift.sql` 하나다(`lifted_by_operation_id text NULL`, 열린 행 색인). 적용된 파일은 고치지 않는다.
- **주석·카피.** 새 주석·카피는 한국어다. 정책 버전은 `OPERATOR-COMMAND-1.2`로 올린다.

**Ask First:**
- 여러 제한의 일괄 해제.
- 해제 대상을 제한 ID 대신 scope로 고르는 방식.
- 실제 HTTP 경로를 두드리는 smoke(지금은 evaluator 수준).
- 훈련 증거 기록(AC-6, 분리됨).

**Never:**
- 자동 복구.
- 신호·시간·재시작으로 제한이 닫히는 경로.
- 원장·신호 행 수정.
- 판정표 변경.
- 사용자 공지(3.5).
- 해제가 다른 제한을 함께 닫는 것.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 준비 | 운영자, 재인증 5분 안, `POST /api/operator/commands/lift-restriction` `{target:<restrictionId>, reason, verification, evidenceRef, deployVersion, conditions[4]}` | 200: 3.1 필드 + `impact`, 감사 `REQUESTED` | 조건 누락 422 `RECOVERY_CONDITION_MISSING`, 닫힌 제한 409 `RESTRICTION_NOT_ACTIVE`, 없음 422 `TARGET_INVALID`, 재인증 없음 403 |
| 확정 | 문구 일치 | 제한 `effective_until` 설정·감사 `COMPLETED` 한 커밋, 세션 회전, `smoke.status=PASSED` | — |
| 재전송 | 같은 멱등 키 | 저장 결과, 회전 없음 | — |
| 경합 | 준비 뒤 다른 operation이 먼저 해제 | `DENIED`, 변경 없음 | 409 `RESTRICTION_NOT_ACTIVE` |
| 새 신호 | 준비 뒤 같은 제한에 신호 `LINKED` | `DENIED` `NEW_SIGNAL_SINCE_PREPARE`, 제한 유지 | 409 |
| 정확한 범위 | `SYNC:PLAN` A 해제, `SYNC:GLOBAL`·`CALCULATION_DISPLAY:PLAN` A도 활성 | A 동기화는 여전히 막힘(전역), 표시 제한 유지, `remainingRestrictions` 둘 | — |
| 독립 재개 | `NEW_CONNECTION:GLOBAL`만 해제 | 연결 생성 재개, 다른 기능 제한 불변 | — |
| 자동 재개 없음 | 시간 경과(행 시각 이동)·신호 재발 없음·컨텍스트 재생성 | 제한 유지 | — |
| smoke 실패 | 시험 훅으로 evaluator가 해제된 제한을 계속 보고 | `smoke.status=REVERTED`, 새 제한 1행, 감사 `FAILED SMOKE_FAILED` | — |
| 권한 | `audit`만 가진 운영자·비운영자 | 404 | — |

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/epics.md` Story 3.4 -- AC-1~6(AC-6 분리). `EPIC-IMPLEMENTATION-READINESS.md` §6.2 복구 조건. 최고 마이그레이션 V19.
- **command 확장(3.2 본보기)**
  - `operations/OperatorCommandApi.java`: `RESTRICT_*` 상수 :36-53, `POLICY_VERSION`(현재 `OPERATOR-COMMAND-1.1`), 거절 code들, 결과 sealed `CommandResult`
  - `operations/internal/OperatorEnvelopeService.java`: `validate`·확인 문구·`completeRestriction`(잠금 → 권한 → 재검사 → INSERT → `COMPLETED`, 본보기), `recordDecision`:272
  - 루트 `OperatorCommandService.java`: 준비 순서(키 → 재인증 → 대상 검사), Supplier, replay
  - `OperatorController.java`: REFUSALS·`PreparedView`·`CompletedView`, `RestrictionView`:179, `RESTRICTIONS_PATH`:78
- **제한 저장**
  - `operations/internal/OperationalRestrictionRepository.java`: 효력 술어 :36, `isActive`:99, `activeRow`:115, `insert`:137, 정렬 규칙
  - 신호 연결 정보: 3.3의 `linkedSignals`·`requiresVerification`(`OperationalSignalService`, `operations_signal.restriction_id`)
- **미리보기:** 루트 `RestrictionTargets.java` `stages(feature, scope)`:185(차단 단계 목록 재사용), `remaining`:201
- **evaluator:** `OperationalRestrictionApi.evaluate`·`blocks`(3.3의 `excluding` 인자 있음) — smoke가 쓴다.
- **화면:** `frontend/src/screens/OperatorScreen.tsx` 제한 영역(활성 목록 `requiresVerification` :123, 결과 :742), `session/useOperatorRestriction.ts`(상태 기계 본보기), `components/OperatorRestrictionDialog.tsx`, `session/operatorApi.ts`, `content/operator.ts`, `components/useConfirmationDialog.ts`
- **시험 본보기:** `OperatorRestrictionTest`(준비·확정·재전송·경합), `RestrictionEnforcementTest`(지점별 차단), `StrongSignalAutoStopTest`(신호 연결), `OperatorTestBase`, `OperatorScreen.test.tsx`

## Tasks & Acceptance

**Execution:**
- [x] `db/migration/V20__operations_restriction_lift.sql` -- `lifted_by_operation_id text NULL`, 부분 색인(열린 행).
- [x] `operations/OperatorCommandApi`·`OperatorEnvelopeService`·`OperationalRestrictionRepository`
  - `lift-restriction` 검증(조건 네 개·텍스트·버전 형식)·문구를 둔다.
  - `completeLift`(잠금 → 권한 → 행 잠금 → 열림·신호 계보 재검사 → 조건부 UPDATE → `COMPLETED`)를 둔다.
  - 결과 `LiftResult`, replay를 더한다.
  - 정책 `OPERATOR-COMMAND-1.2`.
- [x] 루트 `RestrictionTargets`(미리보기 `reopenedStages`·`remainingRestrictions`·신호 계보)·`OperatorCommandService`(smoke와 되돌림)·`OperatorController`(새 code·응답 필드)
- [x] 프런트엔드
  - `OperatorScreen` 활성 행 `복구` → 폼(조건 체크 넷·원인·검증·증거·배포 버전) → 미리보기 대화상자 → 확정·취소·결과 smoke.
  - `session/useOperatorRecovery.ts`(새 파일)·`operatorApi.ts`·`content/operator.ts`를 함께 고친다.
- [x] 시험
  - 백엔드 `OperatorRecoveryTest`: 매트릭스 전 행, 소스 스캔(`effective_until` 쓰기 1문장), 감사 행 모양.
  - 프런트 화면 시험: 폼 필수·대화상자 포커스·문구·smoke 결과.
  - e2e `operator.spec.ts` 주입 origin: 광고 제한을 만든 뒤 복구하는 왕복과 axe. 같은 스택 재실행에서 광고 제한 시험이 다시 돌 수 있게 순서를 맞춘다.
- [x] 문서 -- `CLAUDE.md` 계약 한 줄(해제는 `lift-restriction` 한 경로·조건부 UPDATE 한 문장·smoke 되돌림), `DEVELOPMENT.md`의 "같은 스택 재실행은 새 스택에서" 문장을 3.4 기준으로 갱신.

**Acceptance Criteria:**
- Given 활성 제한 하나를 복구하면, then evaluator는 그 행만 제외하고 다른 활성 제한의 차단은 하나도 바뀌지 않는다.
- Given 운영 소스를 훑으면, then `effective_until`을 쓰는 SQL은 해제 UPDATE 한 문장뿐이다.
- Given 원장 repository를 훑으면, then UPDATE는 `eraseActor` 한 문장뿐이다.
- Given 백엔드 build·프런트엔드 전체 검증·`verify-same-origin.sh`·주입 origin e2e를 돌리면, then 모두 통과한다.

## Spec Change Log

- 2026-10-10 Matrix Test Audit(step-03): 10행 모두 `OperatorRecoveryTest`가 덮고 Testcontainers 빌드 1260건·새 스택 주입 origin e2e 67건 통과. 구현이 정한 곳 — 해제 UPDATE에 `effective_from < statement_timestamp()`를 더함(효력 시작 전 행은 닫지 않음), 형식 오류용 거절 code `RECOVERY_EVIDENCE_INVALID`(422), 남는 제한은 해제 대상 차원(계획이면 portfolio·연결·정책 포함)으로 evaluator와 같은 술어로 센다. SPEC 본문 변경 없음.

- 2026-10-10 리뷰 patch 루프(blind·edge-case·verification-gap): patch 17건·defer 1건·reject 1건. 주요 patch — smoke 첫 확인을 가장 구체적인 한 행(`evaluate`)이 아니라 후보 전체(`matching`)에 해제한 ID가 없음으로 보고, 빠진 남는 제한은 지금도 열려 있을 때만 실패로 셈(그사이 정상 해제·비식별은 통과), smoke 결말을 늘 해제 operation의 행 하나로 남김(통과 `APPROVED SMOKE_PASSED`·실패 `FAILED SMOKE_FAILED`, 재전송은 그 행으로 재구성하고 없으면 `PENDING` — 되돌림 쓰기 실패 뒤 PASSED 재생 금지), V20에 `reinstated_from`을 더해 되돌림 행이 원 제한과 출처(`SIGNAL`)를 잇고 목록 신호 수·수동 검증·다음 해제의 신호 계보 재검사가 그 사슬을 셈(되돌림 행 사유는 표지 `SMOKE_REVERTED`), 이미 막힌 대상의 되돌림은 새 행 없이 `FAILED`만, 열림 판정·해제 UPDATE·신호 연결 판정이 같은 `OPEN` 술어(효력 시작 전 제한은 준비 `RESTRICTION_NOT_ACTIVE`), 거절 code 분리(빈 원인 `REASON_REQUIRED`·빈 검증·증거·배포 버전 `RECOVERY_EVIDENCE_REQUIRED`·형식·중복·모르는 조건 `RECOVERY_EVIDENCE_INVALID`, 조건 배열 상한은 조건 수), `PreparedView.impact`를 sealed 인터페이스로, 시험 보강(portfolio 차원 남는 제한·비식별 해제·확정 순간 권한 회수·같은 키 다른 증거·같은 키 동시 확정·정규화한 소스 스캔과 DELETE 0건·시작·주기 경로 무접촉), 프런트(복구 뒤 목록에서 빠진 제한의 폼 닫기, 다른 행 선택 시 상태 기계 reset, 행별 접근 가능한 버튼 이름, 프라미스 거부 처리, 운영자 카피 쪽 시각 포맷터, `PENDING` 문장과 operation 조회 채움, 화면 시험 보강). SPEC 본문 변경 없음.

## Design Notes

- **제한 ID로 고르는 이유.** 같은 대상에 출처가 다른 제한이 있을 수 있다. 3.3에서는 운영자 제한에 신호가 연결될 수 있다. scope로 고르면 무엇을 닫는지가 모호해진다. ID 하나를 닫으면 나머지는 evaluator 합집합 규칙대로 그대로 막는다 — "정확한 범위만 재개"가 구조적으로 성립한다.
- **smoke가 evaluator 수준인 이유.** 해제 직후 실제 사용자 경로를 두드리려면 대상 사용자의 세션·작업이 필요하다. 그러면 운영자가 남의 데이터를 건드리게 된다. 차단 판정의 유일한 출처가 evaluator이므로, 그 판정이 기대와 같으면 경로들이 같은 결론을 낸다(3.2 AC-4). 실제 경로 smoke는 배포 smoke(3.8)가 맡는다.
- **smoke 실패가 해제를 되돌리지 않고 새 제한을 더하는 이유.** 감사 원장과 제한 행은 사실의 연속이다. 커밋된 해제를 지우지 않고 "해제 → 재제한"을 둘 다 남겨야 원인 분석이 가능하다.
- **AC-6 분리.** 훈련 기록은 해제와 독립적으로 배포할 수 있다. "실제 훈련"과 리허설을 가르는 배포 환경 표지는 3.8이 정한다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 통과, 경고 0.
- `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test && npm --prefix frontend run test:e2e` -- expected: 통과.
- `docker compose --file deploy/compose/compose.yaml down --remove-orphans --volumes && deploy/compose/verify-same-origin.sh --keep-stack && PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run` -- expected: 모두 통과(끝나면 스택 down).
- `grep -rn "effective_until *=" backend/src/main` -- expected: 해제 UPDATE 1건.

## Suggested Review Order

**해제의 원자성**

- 진입점: 원장 잠금 → 권한 → 행 잠금 → 열림·신호 계보 재검사 → 조건부 UPDATE → `COMPLETED`
  [`OperatorEnvelopeService.java:494`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorEnvelopeService.java#L494)

- `effective_until`을 쓰는 유일한 SQL과 열림 술어 하나
  [`OperationalRestrictionRepository.java:228`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalRestrictionRepository.java#L228)
  [`OperationalRestrictionRepository.java:43`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalRestrictionRepository.java#L43)

- 되돌림 사슬까지 세는 신호 계보
  [`OperationalRestrictionRepository.java:49`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalRestrictionRepository.java#L49)

**미리보기와 smoke**

- 해제 대상 차원과 남는 제한(계획이면 portfolio·연결·정책 포함)
  [`RestrictionTargets.java:204`](../../backend/src/main/java/com/bokrilog/RestrictionTargets.java#L204)
  [`RestrictionTargets.java:255`](../../backend/src/main/java/com/bokrilog/RestrictionTargets.java#L255)

- 커밋 뒤 smoke: 해제 ID 부재·남는 제한 차단, 그사이 정상 해제는 실패로 세지 않음
  [`OperatorCommandService.java:545`](../../backend/src/main/java/com/bokrilog/OperatorCommandService.java#L545)

- smoke 실패의 되돌림: 새 제한이 원 제한을 가리키고 출처를 잇는다
  [`OperatorEnvelopeService.java:545`](../../backend/src/main/java/com/bokrilog/operations/internal/OperatorEnvelopeService.java#L545)
  [`V20__operations_restriction_lift.sql:27`](../../backend/src/main/resources/db/migration/V20__operations_restriction_lift.sql#L27)

**command 표면**

- `lift-restriction`과 복구 조건 네 코드
  [`OperatorCommandApi.java:51`](../../backend/src/main/java/com/bokrilog/operations/OperatorCommandApi.java#L51)

**화면**

- 활성 행마다 대상을 담은 이름의 `복구` 버튼과 복구 흐름
  [`OperatorScreen.tsx:186`](../../frontend/src/screens/OperatorScreen.tsx#L186)

- 미리보기 대화상자: 남는 제한·신호 계보, 문구 재입력, 취소 기본 포커스
  [`OperatorRecoveryDialog.tsx:51`](../../frontend/src/components/OperatorRecoveryDialog.tsx#L51)

**주변**

- 시험: 매트릭스 전 행, smoke 세 갈래·되돌림 실패, 비식별 해제, 소스 스캔
  [`OperatorRecoveryTest.java:1`](../../backend/src/test/java/com/bokrilog/OperatorRecoveryTest.java#L1)
