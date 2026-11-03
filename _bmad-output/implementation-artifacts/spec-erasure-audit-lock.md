---
title: '계정 삭제의 operations 비식별을 감사 원장 잠금과 직렬화'
type: 'bugfix'
created: '2026-11-03'
status: 'done'
baseline_commit: '5a990d85d1c93c16f5e030c53b37fff6b9e30692'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 계정 삭제(`AccountErasure` → `OperationsDeletionApi.eraseRestrictionTargets`·`eraseActor`)는 감사 원장 잠금(`lockAppends`) 없이 사용자 귀속 행을 읽고 비식별하는데, 제한 생성·강한 신호 `raise`·해제 smoke 되돌림은 그 잠금 아래에서 사용자 귀속 제한을 INSERT한다. 둘이 겹치면 삭제가 아직 커밋되지 않은 제한 행을 보지 못하거나, 삭제가 커밋된 뒤의 신호가 지워진 사용자의 원문 target·귀속을 가진 열린 제한을 새로 만든다(Epic 3 회고 B-2). 지금은 사용자 삭제 경로가 없어 잠재 결함이지만 Epic 5가 그 경로를 연다.

**Approach:** operations의 삭제 비식별을 같은 원장 잠금 아래에서 하게 해 쓰기 경로와 한 줄로 세우고, 잠금을 기다린 뒤 도는 강한 신호는 대상 사용자의 삭제가 이미 시작됐으면 같은 트랜잭션에서 자기가 쓴 귀속을 즉시 비식별한다.

## Boundaries & Constraints

**Always:**
- 잠금은 기존 `OperatorAuditRepository.lockAppends` 하나를 쓰고 새 advisory 잠금을 만들지 않는다. 삭제 쪽은 `AccountErasure`의 바깥 트랜잭션에 참여해 커밋까지 쥔다.
- 잠금 순서는 바꾸지 않는다 — 삭제는 지금처럼 identity 전환 → portfolio·sync·plan·source erase 뒤 operations 단계에서 원장 잠금을 잡는다(앞당기면 신호 감지 트랜잭션과 애플리케이션 수준 교착이 생긴다).
- 원장 잠금 아래의 계정 상태 판독은 잠금 없는 일반 SELECT다(identity 생명주기 행을 `FOR UPDATE`/`FOR SHARE`로 잡지 않는다).
- operations는 identity에 의존하지 않는다. 계정 상태는 operations가 소유하고 루트가 구현하는 port(웹 조건 없이 모든 역할에 배선)로 묻는다.
- 활성 사용자에 대한 신호·제한 생성 동작과 감사 원장 모양은 그대로다.

**Ask First:**
- 감사 원장 행의 수행자 귀속(`actor_user_id`)을 삭제 뒤에 붙는 거절 행까지 막도록 범위를 넓혀야 할 때.

**Never:**
- 사용자 삭제 경로(HTTP 표면)·Epic 5 범위를 만들지 않는다.
- 삭제된 사용자에게 귀속될 신호를 조용히 버리지 않는다(정지 기록은 남기고 귀속만 비식별한다).
- 새 마이그레이션을 더하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 삭제가 쓰기 뒤에 줄 섬 | 원장 잠금을 다른 트랜잭션이 쥔 채 사용자 귀속 제한을 INSERT 중, 같은 사용자 삭제 시작 | 삭제가 operations 단계에서 잠금을 기다리고, 커밋된 그 행까지 digest target·귀속 NULL로 비식별 | N/A |
| 신호가 삭제 뒤에 줄 섬 | 삭제가 커밋된 사용자를 대상으로 PLAN·PORTFOLIO·PROVIDER_CONNECTION 신호 | 제한·신호·감사 SYSTEM 행은 남고, 제한 target은 digest·`target_user_id` NULL·`target_erased_at` 있음, 신호 `target_user_id` NULL | N/A |
| 활성 사용자 신호 | 삭제가 없는 사용자 | 지금과 같다(원문 target·귀속 유지) | N/A |
| 귀속 없는 신호 | GLOBAL·CALCULATION_POLICY_VERSION, `targetUserId` 없음 | 계정 상태를 묻지 않고 지금과 같다 | N/A |
| digest 키 없는 역할 | 비식별할 행이 있는데 키 없음 | 지금처럼 실패하고 삭제 전체가 롤백 | `IllegalStateException` |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/bokrilog/operations/internal/OperationsDeletionService.java:51-80` -- `eraseActor`(트랜잭션·잠금 없음)·`eraseRestrictionTargets`(TransactionTemplate REQUIRED, 잠금 없음). 둘 다 `audit.lockAppends()` 뒤에 돌게 한다. 바깥 트랜잭션 없이 불려도(시험) 자기 트랜잭션 안에서 잠근다.
- `backend/src/main/java/com/bokrilog/operations/internal/OperatorAuditRepository.java:53-59` -- `lockAppends`(xact advisory 잠금, 트랜잭션 밖이면 무효). 읽기 전용, 재사용.
- `backend/src/main/java/com/bokrilog/operations/internal/OperationalSignalService.java:118-170` -- `record`: `lockAppends`(:123) 뒤 `owner = signal.targetUserId()`로 제한 INSERT·신호 INSERT. 루프 뒤 같은 트랜잭션에서 owner의 삭제가 시작됐으면 귀속 비식별(`OperationsDeletionService`의 같은 논리를 재사용).
- `backend/src/main/java/com/bokrilog/operations/RestrictionConnectionScope.java` + `backend/src/main/java/com/bokrilog/ConnectionRestrictionScope.java` -- operations 소유·루트 구현·웹 조건 없는 port의 선례. 새 계정 상태 port가 이 모양을 따른다.
- `backend/src/main/java/com/bokrilog/OperatorCommandFacts.java:87-89` -- `accountUsable`은 `AccountAccessApi.decide(…, SENSITIVE)`이지만 SERVLET 전용 빈이라 재사용하지 않는다. 판정식만 같은 것을 쓴다(`AccountAccessService.decide`는 잠금 없는 `findLifecycle`).
- `backend/src/main/java/com/bokrilog/RestrictionTargets.java:101-119` -- 운영자 제한 생성의 확정 재검사. 삭제가 지운 plan·portfolio·connection 행이면 `Invalid` → `TARGET_INVALID`라 직렬화만으로 안전하다(변경 없음).
- `backend/src/main/java/com/bokrilog/operations/internal/OperationalRestrictionRepository.java:394-405` -- `reinstate`는 원 행의 귀속·비식별 상태를 복사하므로 직렬화만으로 안전하다(변경 없음).
- `backend/src/main/java/com/bokrilog/AccountErasure.java:94-108` -- 순서 유지. 클래스 주석에 operations 단계의 원장 잠금을 한 줄 더한다.
- `backend/src/test/java/com/bokrilog/AdEligibilityTest.java:62-63,403-425` -- advisory 잠금을 쥐고 `pg_locks` 대기자를 세는 시험 선례.
- 걸린 열린 회고 액션: `epic-3-retro-item-27` -- 이 작업이 닫는다. `epic-2-retro-item-24`(erase 완전성 시험) -- 같은 erase 경로지만 건드리지 않고 지나간다. 신호 대상 귀속 외의 수행자 거절 행(`actor_user_id`)은 Ask First로 남긴다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/java/com/bokrilog/operations/AccountErasureState.java` -- 새 port `boolean erasureStarted(UUID userId)`(삭제가 시작됐거나 계정이 없으면 true) -- operations가 identity 없이 계정 상태를 묻는다.
- [x] `backend/src/main/java/com/bokrilog/IdentityAccountErasureState.java` -- 루트 구현(웹 조건 없음, `AccountAccessApi` SENSITIVE 판정이 ALLOWED가 아니면 true) -- 모든 역할에서 신호가 판정을 쓴다.
- [x] `backend/src/main/java/com/bokrilog/operations/internal/OperationsDeletionService.java` -- `eraseActor`·`eraseRestrictionTargets`를 트랜잭션 안 `lockAppends` 뒤에서 수행, 귀속 비식별 본문을 신호 서비스가 부를 수 있는 package-private 메서드로 둔다 -- 삭제를 쓰기 경로와 직렬화.
- [x] `backend/src/main/java/com/bokrilog/operations/internal/OperationalSignalService.java` -- `record` 끝에서 owner가 있고 `erasureStarted`면 그 사용자 귀속을 같은 트랜잭션에서 비식별 -- 잠금을 기다린 신호가 지워진 사용자를 되살리지 않는다.
- [x] `backend/src/main/java/com/bokrilog/AccountErasure.java` -- 주석 갱신 -- 순서·잠금 근거 기록.
- [x] `backend/src/test/java/com/bokrilog/ErasureAuditLockTest.java` -- I/O 행 1·2·3을 실제 PostgreSQL로 단정(행 1은 잠금 보유·`pg_locks` 대기 확인으로 시간 추측 없이) -- 회귀 방지.

**Acceptance Criteria:**
- Given 원장 잠금을 쥔 트랜잭션이 사용자 귀속 제한을 INSERT한 뒤 커밋을 미루고, when 같은 사용자 삭제가 시작되면, then 삭제는 operations 단계에서 잠금 대기자로 보이고 잠금이 풀린 뒤 그 행을 포함해 귀속 행 0건으로 끝난다.
- Given 기존 시험 전체, when 백엔드 빌드를 돌리면, then `ModuleBoundaryTest`(operations의 identity 비의존 포함)를 비롯해 모두 통과한다.

## Spec Change Log

- 구현에서 확정한 것(2026-11-03): ① 삭제가 이미 비식별한 같은 대상의 열린 제한에 신호가 다시 오면 원문 target으로는 찾지 못해 같은 digest의 열린 제한이 둘이 되고 활성 유일 색인에 걸린다 — 삭제가 시작된 사용자·귀속 scope일 때만 digest(`ledgerTarget`)로 한 번 더 찾아 LINKED로 잇는다(INSERT 변형은 만들지 않음). ② port가 "계정 없음"도 삭제 시작으로 보므로 무작위 UUID를 신호 소유자로 쓰던 시험(`StrongSignalAutoStopTest`·`OperatorRecoveryTest`·`SignalPolicyTest`)을 실제 계정 소유자로 바꿨다(단정 불변). ③ I/O 행 4·5를 덮는 시험이 없어 matrix 감사에서 더했다(`OperationsTestHook`).
- 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap): patch 8·defer 1·reject 7. patch — 잠금 대기 단정을 삭제 pid로 좁힘, 수행자 비식별 잠금을 따로 고정하는 시험, operations 단계가 다른 모듈 erase 뒤이고 그때만 잠금을 쥔다는 순서 시험, 삭제가 잠금을 쥔 동안 신호가 기다렸다 깨어나는 실제 경합 시험과 전역으로 접히는 섞인 신호 시험, `OperatorCommandFacts.accountUsable`을 새 port 한 곳으로 모음, `SignalPolicyTest` 소유자, 수행자 비식별 한계 주석, 자기 계정 삭제와 동시 command의 감지된 교착(40P01) 주석. defer — 삭제 커밋 뒤 붙는 운영자 거절 행의 `actor_user_id`(`deferred-work.md` 162번째, SPEC Ask First). reject — "계정 없음 = 삭제 시작"(SPEC 정의, 운영 경로 없음), 비웹 역할 배선 시험(전체 빌드의 역할 컨텍스트가 이미 확인), 비식별 제한의 원문 판독 미적용(의도), 실제 command 경로로 I/O 1 재현, 시험 hook 갈라짐, 문서 갱신(PR·done 전환이 소유), 생명주기 전용 identity API(공개 API가 없고 판정식을 한 곳으로 모아 대신함).

## Design Notes

잠금을 operations 단계에서 잡는 이유: 신호 `raise`는 감지 트랜잭션(sync 행 잠금 보유)이 애플리케이션에서 기다리는 REQUIRES_NEW 트랜잭션이다. 삭제가 sync erase 전에 원장 잠금을 쥐면 "삭제 → sync 행(감지 tx) → 감지 tx가 앱에서 raise를 기다림 → raise가 원장 잠금(삭제)" 고리가 PostgreSQL이 모르는 교착이 된다. sync erase 뒤라면 그 행은 이미 삭제가 쥐었거나 감지가 끝났다.

신호 쪽은 "INSERT 뒤 같은 트랜잭션에서 비식별"로 기존 비식별 경로 하나를 재사용한다 — 제한 INSERT에 별도 "이미 비식별" 변형을 만들지 않는다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend test --tests "com.bokrilog.ErasureAuditLockTest" --tests "com.bokrilog.AccountErasureTest" --tests "com.bokrilog.StrongSignalAutoStopTest" --tests "com.bokrilog.OperatorRestrictionTest" --tests "com.bokrilog.ModuleBoundaryTest"` -- expected: 모두 통과
- `backend/gradlew --project-dir backend build` -- expected: 성공(`-Werror` 포함)

## Suggested Review Order

**삭제 쪽 직렬화**

- operations 비식별이 원장 잠금을 먼저 쥐고 바깥 삭제 트랜잭션의 커밋까지 간다
  [`OperationsDeletionService.java:73`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationsDeletionService.java#L73)

- 수행자 비식별도 같은 잠금 — 삭제보다 먼저 잠금을 쥔 쓰기만 덮는다는 한계 포함
  [`OperationsDeletionService.java:60`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationsDeletionService.java#L60)

- 비식별 본문 하나를 삭제 port와 신호가 함께 쓴다
  [`OperationsDeletionService.java:92`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationsDeletionService.java#L92)

- 잠금을 sync erase 뒤에만 잡는 이유(앱 수준 교착)와 감지된 교착 40P01
  [`AccountErasure.java:29`](../../backend/src/main/java/com/bokrilog/AccountErasure.java#L29)

**잠금을 기다린 신호**

- 원장 잠금 뒤 잠금 없는 계정 상태 판독, 귀속 사용자 없는 신호는 묻지 않음
  [`OperationalSignalService.java:144`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalSignalService.java#L144)

- 이미 비식별된 같은 대상의 열린 제한을 digest로 찾아 유일 색인 충돌을 피함
  [`OperationalSignalService.java:158`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalSignalService.java#L158)

- 정지 기록은 남기고 같은 트랜잭션에서 자기 귀속만 비식별
  [`OperationalSignalService.java:194`](../../backend/src/main/java/com/bokrilog/operations/internal/OperationalSignalService.java#L194)

**계정 상태 port**

- operations 소유 port — identity 비의존
  [`AccountErasureState.java:22`](../../backend/src/main/java/com/bokrilog/operations/AccountErasureState.java#L22)

- 루트 구현, 웹 조건 없음, 판정식은 이 한 곳
  [`IdentityAccountErasureState.java:27`](../../backend/src/main/java/com/bokrilog/IdentityAccountErasureState.java#L27)

- 문의 답변의 계정 판정이 같은 port를 쓴다
  [`OperatorCommandFacts.java:87`](../../backend/src/main/java/com/bokrilog/OperatorCommandFacts.java#L87)

**시험**

- 삭제가 잠금을 쥔 동안 온 신호가 기다렸다 비식별하는 실제 경합
  [`ErasureAuditLockTest.java:139`](../../backend/src/test/java/com/bokrilog/ErasureAuditLockTest.java#L139)

- 잠금 보유 쓰기 뒤에 삭제가 줄 서는 것(삭제 pid로 대기 확인)
  [`ErasureAuditLockTest.java:85`](../../backend/src/test/java/com/bokrilog/ErasureAuditLockTest.java#L85)

- operations 단계가 다른 모듈 erase 뒤이고 그때만 잠금을 쥔다
  [`AccountErasureTest.java:267`](../../backend/src/test/java/com/bokrilog/AccountErasureTest.java#L267)

- 키 없는 역할·귀속 없는 신호(I/O 행 4·5)
  [`ErasureAuditLockTest.java:257`](../../backend/src/test/java/com/bokrilog/ErasureAuditLockTest.java#L257)

- 시험 전용 배선 hook
  [`OperationsTestHook.java:27`](../../backend/src/test/java/com/bokrilog/operations/internal/OperationsTestHook.java#L27)

- 신호 소유자를 실제 계정으로
  [`OperatorTestBase.java:206`](../../backend/src/test/java/com/bokrilog/OperatorTestBase.java#L206)
