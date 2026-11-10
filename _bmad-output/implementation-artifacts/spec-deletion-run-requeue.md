---
title: 'Epic 5 회고 AI-1 — 재시도 필요 삭제 실행을 운영 command로 다시 대기열에 넣기'
type: 'feature'
created: '2026-11-09'
status: 'done'
baseline_commit: '854f15e951ca9fc4061b0d5de1bfc465bfaeca6b'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-retro-2026-11-09.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-2-account-data-erasure.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:**
- `RETRY_NEEDED`로 멈춘 계정 삭제 실행을 `QUEUED`로 되돌리는 길은 요청 세션의 `POST /api/account/deletion/retry` 하나뿐이다.
- 그 세션을 잃으면 계정이 `DELETION_IN_PROGRESS`로 영구히 남는다. 세션을 잃는 경우는 유휴 30분·절대 12시간·로그아웃·`UnreadableSessionValue`다.
- 삭제 중인 계정은 로그인이 거부되고 운영자 경로도 없다. 그래서 30일 뒤 `OVERDUE` 경보만 울린다(회고 B-1).

**Approach:**
- 운영 command `requeue-deletion`(target = 삭제 요청 ID `del_…`)을 더한다. 기존 운영 command 엔진의 준비→확정→취소·재인증·감사 원장을 그대로 쓰고, 그 실행 하나를 `QUEUED`로 되돌린다.
- 운영자 화면에 재시도 필요 실행의 읽기 목록과 command 대화상자를 더한다. 목록 경로는 `GET /api/operator/deletions`, command 권한이다.
- 사용자 결정(2026-11-09)으로 5.2의 Never "운영자 재시도 command"와 `ModuleBoundaryTest`의 "운영자 재시도 없음"을 이 command 하나에 한해 바꾼다.

## Boundaries & Constraints

**Always:**
- 확정은 엔진 prologue 순서를 따른다: 원장 잠금 → 최종 권한 → 실행 행 변경.
- 실행 행 변경은 앞서 잠그지 않는 조건부 UPDATE 한 문장이다. `request_id`와 `status='RETRY_NEEDED'`가 맞고 `FOR UPDATE SKIP LOCKED`로 잡힌 행만 바꾼다.
  - 0행이면 잠그지 않는 읽기로 거절 code를 가른다.
  - 단계 트랜잭션(실행 행 → 원장 순서)과 교착하지 않는다.
- 단계 상태는 바꾸지 않는다. 실패·미실행 단계만 다시 도는 5.2 의미 그대로다.
- `retry_key_digest`에는 운영자 전용 접두사의 digest를 쓴다. 입력은 `account-deletion-operator-requeue\n` + operation ID다.
- 감사 원장 target은 keyed digest(`deletion-request:` 접두사)로만 남긴다. details에는 상태·단계 수·시각만 둔다.
- production(`BOKRILOG_ENV=production`, G-5 전)에서는 준비와 확정이 거절되고, 목록은 `available:false`다.
- 목록과 미리보기에는 요청 ID·요청 시각·멈춘 시각·실패 단계(단계·실패 code·시도 수)만 싣는다. 멈춘 시각은 실패 단계 `finished_at`의 최댓값이다. `user_id`·소셜 식별자는 싣지 않는다.
- 새 identity 메서드의 운영 호출자는 루트 `OperatorCommandFacts` 하나로 `ModuleBoundaryTest`가 고정한다.
- 운영 command 정책 버전을 `OPERATOR-COMMAND-1.5`로 올린다.

**Ask First:**
- 마이그레이션이 필요해질 때
- Worker claim·`acceptRetry`의 동작을 바꿔야 할 때
- `NOWAIT`·`lock_timeout` 없이는 교착을 피할 수 없다고 판단될 때

**Never:**
- 자동 재대기열과 주기 작업
- `QUEUED`·`RUNNING`·완료된 실행의 변경
- 삭제 중 계정의 재로그인 허용
- 사용자 재시도 경로·응답의 변경
- 단계 행 초기화
- 목록에 사용자 식별자 노출
- 새 런타임 의존

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 정상 | 실행 `RETRY_NEEDED`, 운영자 준비→확정 | 실행 `QUEUED`, `COMPLETED` 원장 행, Worker가 다시 집어 실패 단계만 돈다 | N/A |
| 상태 다름 | `QUEUED`·`RUNNING`·`OPERATIONAL_DATA_ERASED`·`DELETION_COMPLETED` | 변경 없음 | 409 `DELETION_NOT_RETRYABLE` 거절 행 |
| 없는 대상 | 형식은 맞는데 요청이 없거나, 요청은 있는데 실행 행이 없다 | 변경 없음 | 422 기존 `TARGET_INVALID` 거절 행 |
| 형식 오류 | `del_` 접두사·모양이 아님, 사유가 없거나 형식 위반 | 변경 없음 | 422 기존 `TARGET_INVALID`·`REASON_REQUIRED`·`REASON_INVALID` |
| 준비 뒤 경합 | 준비와 확정 사이에 사용자 재시도로 `RUNNING`이 됐다 | 0행 → 교착 없이 거절 | 409 `DELETION_NOT_RETRYABLE` |
| production | `BOKRILOG_ENV=production` | 준비·확정 거절, 목록 `available:false`·빈 items | 403 `DELETION_REQUEUE_UNAVAILABLE` |
| 재전송 | 같은 멱등 키로 확정 재전송 | `COMPLETED` 행으로 같은 본문 | N/A |
| 목록 권한 | audit 권한만 있거나 비운영자 | 없는 경로와 같은 404 | `OperatorGate` |

</frozen-after-approval>

## Code Map

**identity**
- `backend/src/main/java/com/bokrilog/identity/AccountDeletionRunApi.java`
  - `acceptRetry`(:169), `RunStatus`·`StepState` record가 있다.
  - 메서드 셋을 더한다: `retryNeededRuns()`·`retryNeededRun(requestId)`·`requeueByOperator(requestId, digest)`.
- `backend/src/main/java/com/bokrilog/identity/internal/AccountDeletionRunService.java:143-169`
  - `acceptRetry`가 본보기다. `HEX_DIGEST` 검사를 재사용한다.
- `backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java`
  - 기존: `requeueRun`(:859, user_id 기준), `findSteps`(:773), `findRunRequestId`(:136).
  - SKIP LOCKED 선례: `claimExistingRun`(:654).
  - 새 쿼리 셋을 더한다: 요청 ID 기준 조건부 UPDATE, 목록, 단건.

**operations**
- `backend/src/main/java/com/bokrilog/operations/OperatorCommandApi.java`
  - 이름 상수(:87-90), `COMMANDS`(:96-97), 거절 code 상수(:147·:195 꼴)를 더한다.
  - `POLICY_VERSION`(:124)과 이력 주석(:113-122)을 고친다.
- `backend/src/main/java/com/bokrilog/operations/internal/UpdateInquiryCommand.java`
  - 전략 본보기(296줄)다. `parse`·`validate`(:122-173), `inspect`(:196), `complete`(:214-253), `afterCommit`·`replay`를 본뜬다.
  - 새 파일은 `RequeueDeletionCommand.java`다.
- `backend/src/main/java/com/bokrilog/operations/internal/OperatorEnvelopeService.java`
  - 등록표(:127-132)에 한 줄을 더한다.
  - 확인 문구 helper는 `inquiryPhrase`(:228)를 본뜬다.
- `backend/src/main/java/com/bokrilog/operations/internal/ActorDigest.java:70`
  - `ofInquiry`를 본떠 `ofDeletionRequest`를 더한다.
- `backend/src/main/java/com/bokrilog/operations/OperatorCommandEnvironment.java:21-74`
  - port에 메서드를 더한다: 단건·목록·재대기열·가용 여부.

**루트**
- `backend/src/main/java/com/bokrilog/OperatorCommandFacts.java`(89줄)
  - port 구현이다. `AccountDeletionRunApi`를 주입하고, 가용 여부는 `AccountDeletionRequestService.available()`(:168)을 쓴다.
- `backend/src/main/java/com/bokrilog/OperatorController.java`
  - `REFUSALS`(:146)와 `COMMAND_REFUSALS`(:224-235)에 새 code를 더한다.
  - 목록은 `restrictions`(:438-455)를 본뜬다. 경로 상수는 `:107` 꼴이고, view record는 `:251-263` 꼴이다.
- `backend/src/main/java/com/bokrilog/OperatorGate.java:89-109`
  - `DELETIONS_PATH` 분기를 COMMAND로 더한다. 모르는 경로는 권한 없이 통과하므로 필수다.

**백엔드 시험**
- `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java:460-465`
  - `acceptRetry` 규칙 문구를 고친다.
  - 새 identity 메서드의 호출자를 `OperatorCommandFacts`로 고정하는 규칙을 더한다.
- `OperatorCommandRegistryTest.java:60-66`, `OperatorCommandContractTest.java:186-202`, `OperatorAccessTest.java:197-201`, `OperatorControllerRefusalsTest`
  - 등록 완전성·JSON 모양·권한·거절 표를 늘린다.
- 정책 버전 리터럴이 들어 있는 시험을 갱신한다: `SupportInquiryTest`·`OperatorRecoveryTest`·`ServiceNoticeTest`.
- `backend/src/test/java/com/bokrilog/OperatorTestBase.java`
  - helper를 재사용한다: `operator`(:78), `reauthenticated`(:84), `key`(:110), `phraseIn`(:323), `concurrently`(:264).
- `backend/src/test/java/com/bokrilog/AccountErasureRunTest.java`
  - `requestDeletion`(:1103)과 `runnerWith`·`failingSync`(:1122-1145)가 `RETRY_NEEDED`를 만든다. 둘 다 private이라 새 시험에 필요한 만큼만 둔다.

**프런트엔드**
- `frontend/src/session/operatorApi.ts`
  - `fetchRestrictions`(:348-369)를 본떠 목록 판독과 항목 파서를 둔다.
- `frontend/src/session/useOperatorRecovery.ts`(67줄), `useOperatorRestriction.ts:82-130`
  - 목록 + command 훅의 본보기다. 같은 꼴로 `useOperatorDeletion.ts`를 만든다.
- `frontend/src/screens/OperatorScreen.tsx`
  - section(:1755 꼴)과 행 컴포넌트(:305 꼴)를 더한다.
- `frontend/src/components/OperatorRecoveryDialog.tsx`(194줄)
  - 대화상자 본보기다.
- `frontend/src/content/operator.ts`
  - 경로 상수(:33-39), command 이름(:115 꼴), 새 카피 블록과 `operatorCopyStrings`(:1001-1029)를 더한다.
  - 실패 code 라벨은 아직 어디에도 없으므로 새로 만든다.
- `frontend/src/content/accountDeletion.ts:225-249`
  - `accountDeletionStepScope`를 재사용한다.

**문서**
- `CLAUDE.md`
  - 계정 삭제 5.2 계약 줄의 "다시 집는 것은 …뿐이다"를 고친다.
  - 운영자 경로 목록에 `/api/operator/deletions`·`requeue-deletion`을 더한다.

**걸린 열린 회고 액션**
- `epic-5-retro-item-48`(AI-1): 이 작업이 닫는다.
- `epic-5-retro-item-54`(AI-7): 같은 저장소·시험 helper다. 분할·공용 base는 지나간다.
- `epic-5-retro-item-49`(AI-2): `ModuleBoundaryTest`의 security 규칙을 지나간다.
- `epic-5-retro-item-53`(AI-6): production 게이트 문구를 지나간다.
- `epic-1-retro-item-7`: 확인은 문구 재입력이라 해당 없음이고, 지나간다.

## Tasks & Acceptance

**Execution:**
- [x] identity
  - `AccountDeletionRunApi`·`AccountDeletionRunService`·`IdentityAccountRepository`에 목록·단건·`requeueByOperator`를 둔다. 결과 sealed interface는 `Requeued`·`NotRetryable`·`NotFound`다.
  - 이유: identity가 실행 상태의 유일한 권위다.
- [x] operations
  - `OperatorCommandApi`(이름·code·정책 1.5)를 고친다.
  - `RequeueDeletionCommand`를 새로 만든다.
  - `OperatorEnvelopeService`에 등록하고 문구 helper를 둔다.
  - `ActorDigest.ofDeletionRequest`를 더한다.
  - `OperatorCommandEnvironment`에 port 메서드를 더한다.
  - 이유: 전략 하나와 등록표 한 줄이라는 엔진 관례.
- [x] 루트
  - `OperatorCommandFacts`: port를 구현한다.
  - `OperatorController`: 목록 엔드포인트와 거절 표.
  - `OperatorGate`: COMMAND 분기.
- [x] 백엔드 시험
  - 통합 시험 `OperatorDeletionRequeueTest`(`OperatorTestBase` 상속)로 I/O 여덟 행과 다음을 단정한다.
    - 재대기열 뒤 실패 단계만 다시 돌아 운영 완료에 이르는 것
    - 준비 뒤 실행이 `RUNNING`인 경합
  - 경계·등록·계약·권한·거절 표 시험을 고치고, 정책 버전 리터럴을 갱신한다.
- [x] 프런트엔드
  - `operatorApi`: 목록 판독과 command 응답 해석기.
  - `useOperatorDeletion`: 목록과 command 훅.
  - `OperatorScreen`: 패널.
  - `OperatorDeletionRequeueDialog`: 대화상자.
  - `content/operator.ts`: 카피와 실패 code 라벨.
  - 각 시험을 두고 정책 버전 리터럴을 갱신한다.
- [x] `CLAUDE.md`: 계약 줄과 경로 목록을 고친다.

**Acceptance Criteria:**
- Given 요청 세션을 잃고 `RETRY_NEEDED`로 멈춘 계정, when 운영자가 목록에서 그 실행을 골라 재인증하고 문구를 입력해 확정하면, then Worker가 실패 단계부터 다시 돌아 생명주기가 `DELETED`가 된다.
- Given 같은 실행, when 사용자 요청 세션이 남아 있으면, then 사용자 재시도 경로는 이전과 같이 동작한다.
- Given 모든 운영 소스, when `ModuleBoundaryTest`가 돌면, then `requeueByOperator` 호출자는 `OperatorCommandFacts`뿐이고 `acceptRetry` 호출자는 `AccountDeletionRequestService`뿐이다.
- Given 원장, when command가 끝나면, then 원장에는 요청 ID 원문도 `user_id`도 남지 않는다.

## Spec Change Log

### 1차 리뷰(2026-11-09) — patch 12건·defer 0건·reject 7건

세 갈래 리뷰(Blind·Edge Case·Verification Gap)를 돌렸다. intent_gap·bad_spec은 없었다.

**patch(12건):**
1. 목록·단건의 `requestedAt` 계약을 맞춘다. 두 조회 모두 요청 행을 INNER JOIN하고, 재대기열도 요청 행이 있을 때만 한다(claim이 요청 행을 요구하기 때문이다).
2. 200건에서 잘린 목록에 `truncated` 필드를 싣고 화면이 안내한다.
3. 목록과 단건을 한 스냅샷에서 읽는다.
4. `retryNeededRun`을 `runByRequest`로 이름을 바꾼다. 상태와 무관하게 실행을 돌려주기 때문이다.
5. `SKIP LOCKED` 경합에도 맞는 `DELETION_NOT_RETRYABLE` 문장으로 바꾼다.
6. 새 거절 문장 두 개를 서버와 화면에서 글자까지 같게 맞춘다.
7. `retrySteps` 라벨을 "재시도 필요 단계"로 바꾼다.
8. 실패 code 라벨 조회를 `Object.hasOwn`으로 한다.
9. 행을 고를 때마다 상태를 비우고, 목록에서 빠진 선택은 닫는다.
10. 가용 여부를 "아직 모름"으로 시작한다.
11. 시험을 보강한다.
    - 백엔드: 실행 둘의 단계 대응·순서·`stoppedAt`, 운영자 재대기열 뒤 사용자의 옛 키 재전송 409, 확정 시점 production 거절의 DENIED 행
    - 프런트: 확정 409 뒤 목록 다시 읽기, 두 행 전환, 잘림 안내, 첫 읽기 실패
12. `CLAUDE.md`의 "훅 여섯"과 키 덮어쓰기 계약을 고친다.

**reject(7건):**
- 실패 code가 null인 재시도 필요 단계: V31·V32 CHECK가 막는다.
- 요청 ID 모양 규칙의 세 벌 대조: 권위는 서버이고, 프런트는 판독만 한다.
- 재대기열 횟수 상한·경보: 매번 운영자의 재인증과 사유가 필요하고, 관측은 172가 맡는다.
- 컨트롤러가 port로 목록을 읽는 것: 설계상 수용한다.
- 정책 버전 상향이 무장 세션에 주는 영향: 기존 상향과 같은 경로다.
- diff에 SPEC이 없음: 리뷰용 diff에서 일부러 뺐다.
- 행 버튼의 `aria-pressed`: 기존 목록 행에 선례가 없다.

**KEEP:**
- 교착을 피하는 조건부 UPDATE(`SKIP LOCKED` 하위 질의)
- 단계 행을 건드리지 않는 것
- 원장 target digest(`deletion-request:`)
- `ModuleBoundaryTest`의 호출자 하나 규칙

### patch 적용(2026-11-09)

- patch 1의 단건 조회(`runByRequest`)는 LEFT JOIN으로 남겼다. 요청 행은 운영 완료 때 지워진다. 그래서 INNER JOIN으로 바꾸면 완료된 실행이 422 `TARGET_INVALID`가 되어, frozen 매트릭스의 409 `DELETION_NOT_RETRYABLE`과 어긋난다.
- 목록은 INNER JOIN이다. 재대기열은 요청 행 `EXISTS`를 요구하고, 그런 행이 없는 재시도 필요 실행은 `TARGET_INVALID`로 판정한다.
- 확인 범위: 백엔드는 `OperatorDeletionRequeueTest`(9건)와 경계·등록·계약·권한·거절 표 시험이 통과했다. 프런트엔드는 typecheck·lint·lint:css·format:check·test(1,668건)가 통과했다.
- 로컬 전체 build의 판정은 CI가 맡는다. 이 PC에는 Windows 소켓 결함이 있다.

### PR 전 최종 검증(2026-11-10) — patch 1건

**patch:** 새 `OperatorDeletion.test.tsx`가 `stress-test.sh` 부하 12회 중 11회 실패했다.
- 원인: 운영자 화면의 첫 마운트 대기가 Testing Library 기본 1초를 넘었다.
- 수정: 첫 대기 두 곳에 `FIRST_MOUNT_TIMEOUT_MS`를 주었다. `firstMount.ts`와 `DEVELOPMENT.md`에 적힌 적용 범위에 이 시험을 더했다. 같은 관례를 쓰는 `OperatorNotice`는 같은 부하에서 12회 모두 통과했다.
- 수정 뒤: 12회 모두 통과했다.

**기존 결함(이 변경과 무관):** 같은 부하에서 `OperatorInquiry`·`SettingsReconnect`도 실패했다. 이 변경 전인 `develop`(`854f15e`)에서도 각각 10회·11회 실패해 기존 문제로 확인했다.

**확인한 범위:**
- 백엔드 전체 시험 1,767건이 모두 통과했다. 첫 실행의 119건 실패는 모두 인프라 문제였고(Docker 경합과 `Bad address: listen`), 해당 클래스를 다시 돌려 통과했다.
- 프런트엔드 시험 전체 1,668건이 통과했다.
- 동일 출처 검증 1,103건, 주입 origin e2e 73건이 통과했다.
- 실제 스택 왕복도 확인했다.
  1. 사용자가 삭제를 요청하고, 세션을 잃은 `RETRY_NEEDED` 상태를 만들었다.
  2. 운영자가 목록 → 재인증 → 준비 → 문구 → 확정을 거쳤다.
  3. Worker가 `CREDENTIALS`를 두 번째로 시도하고 나머지 단계를 돌아 `DELETED`가 되었다.
  4. 원장 행에는 요청 ID 원문과 사용자 ID가 0건이었다.

## Design Notes

**교착을 피하는 문장 모양:**

```sql
UPDATE identity_deletion_run SET status = 'QUEUED', retry_key_digest = :digest,
       retry_requested_at = statement_timestamp()
 WHERE user_id = (SELECT user_id FROM identity_deletion_run
                   WHERE request_id = :requestId AND status = 'RETRY_NEEDED'
                   FOR UPDATE SKIP LOCKED)
   AND status = 'RETRY_NEEDED'
```

- READ COMMITTED에서 커밋된 상태가 `RUNNING`이면 술어가 맞지 않아 기다리지 않는다.
- 단일 모드나 사용자 재시도가 그 행을 쥐고 있으면 `SKIP LOCKED`가 건너뛴다.
- `NOWAIT`·`lock_timeout`은 트랜잭션을 abort시켜 거절이 아니라 500이 된다.

**사용자 키 재전송:** 운영자 재대기열 뒤 사용자가 예전 키로 재시도를 재전송하면 409 `NOT_RETRYABLE`이다. digest가 다르기 때문이다. 결과적으로 실행은 이미 다시 돌고 있으므로 수용한다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build`: 성공(Docker 필요)
- `npm --prefix frontend run typecheck`, `lint`, `lint:css`, `format:check`, `test`: 모두 통과

## Suggested Review Order

**교착 없는 재대기열(핵심)**

- 원장 뒤 실행 행을 SKIP LOCKED 조건부 UPDATE 한 문장으로 바꾼다
  [`IdentityAccountRepository.java:945`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L945)

- 0행이면 잠그지 않는 읽기로 거절 code를 가른다
  [`AccountDeletionRunService.java:201`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountDeletionRunService.java#L201)

- 운영자 전용 접두사의 재시도 키 digest
  [`RequeueDeletionCommand.java:51`](../../backend/src/main/java/com/bokrilog/operations/internal/RequeueDeletionCommand.java#L51)

**운영 command 전략**

- 확정: 엔진 prologue 뒤 identity port 한 번, details는 상태·단계 수·시각만
  [`RequeueDeletionCommand.java:180`](../../backend/src/main/java/com/bokrilog/operations/internal/RequeueDeletionCommand.java#L180)

- 준비 미리보기: 재인증·멱등 판정 뒤 거절 적용
  [`RequeueDeletionCommand.java:147`](../../backend/src/main/java/com/bokrilog/operations/internal/RequeueDeletionCommand.java#L147)

- 루트 port 구현이 identity 실행 API를 잇는다
  [`OperatorCommandFacts.java:124`](../../backend/src/main/java/com/bokrilog/OperatorCommandFacts.java#L124)

**목록 읽기와 권한**

- 한 스냅샷(REPEATABLE_READ)에서 실행·실패 단계를 읽는다
  [`AccountDeletionRunService.java:177`](../../backend/src/main/java/com/bokrilog/identity/internal/AccountDeletionRunService.java#L177)

- 요청 행과 내부 결합하고 200건 상한, 잘림 표시
  [`IdentityAccountRepository.java:894`](../../backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java#L894)

- `GET /api/operator/deletions` — production이면 available:false
  [`OperatorController.java:619`](../../backend/src/main/java/com/bokrilog/OperatorController.java#L619)

- 모르는 경로는 권한 없이 통과하므로 COMMAND 분기 필수
  [`OperatorGate.java:106`](../../backend/src/main/java/com/bokrilog/OperatorGate.java#L106)

**운영자 화면**

- 목록 판독: 항목 하나라도 틀리면 목록 전체 failed
  [`operatorApi.ts:1062`](../../frontend/src/session/operatorApi.ts#L1062)

- 목록 + command 훅(useOperatorCommand·useSequencedRead 재사용)
  [`useOperatorDeletion.ts:93`](../../frontend/src/session/useOperatorDeletion.ts#L93)

- 빠진 선택을 렌더 중에 닫는다(초점 자동 이동 없음)
  [`OperatorScreen.tsx:635`](../../frontend/src/screens/OperatorScreen.tsx#L635)

- 재시도 필요 실행 패널
  [`OperatorScreen.tsx:2298`](../../frontend/src/screens/OperatorScreen.tsx#L2298)

- 확인 대화상자(useConfirmationDialog 위임)
  [`OperatorDeletionRequeueDialog.tsx:43`](../../frontend/src/components/OperatorDeletionRequeueDialog.tsx#L43)

- 실패 code 라벨(Object.hasOwn)
  [`operator.ts:263`](../../frontend/src/content/operator.ts#L263)

**경계·시험·문서**

- 새 identity 메서드의 호출자를 OperatorCommandFacts 하나로 고정
  [`ModuleBoundaryTest.java:472`](../../backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java#L472)

- I/O 여덟 행과 두 실행·옛 키 재전송·확정 시 production 거절
  [`OperatorDeletionRequeueTest.java:68`](../../backend/src/test/java/com/bokrilog/OperatorDeletionRequeueTest.java#L68)

- 5.2 계약 줄: 운영자 재대기열과 키 덮어쓰기
  [`CLAUDE.md:294`](../../CLAUDE.md#L294)
