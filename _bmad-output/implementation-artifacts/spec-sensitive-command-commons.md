---
title: '민감 command 공통 부품 통합: 세션 무장·회전·허가 무효화·멱등 키·lease 상수'
type: 'refactor'
created: '2026-10-06'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'ea56b9833b74ae515ea916a98e7344ae758b838d'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-retro-2026-10-05.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Epic 2 회고(AI-8, D-1~D-3)가 민감 command의 공통 부품이 사본으로 흩어졌음을 확인했다 — 세션 무장 Flow 6벌(넷은 본문이 같다), `rotate()` 3벌, 허가 무효화 단위가 `(user, command, target)`과 참조로 갈림, 멱등 키 규칙 5벌과 같은 code의 메시지 6가지, SQL `interval '30 seconds'` 18회. Epic 3 Story 3.1이 운영자 command를 더하면 7번째 사본이 생긴다. 그리고 Epic 1 회고 item-1 — `bokrilog.auth.reauthenticated-at`을 지우는 코드가 없어 본인 확인 1회가 5분 안에서 민감 command 여러 건을 연쇄 승인한다 — 은 같은 코드를 지난다.

**Approach:** 루트 패키지에 세션 무장 한 벌(`SessionArming<T>`)과 회전 한 벌(`SessionRotation`)을 두고 Flow 6종과 서비스가 그것에 위임한다. 명시적 허가 무효화는 참조 단위 하나(`invalidateReference`)로 통일한다. 민감 command가 실제로 실행된 그 요청에서 회전과 함께 재인증 시각을 지운다. 멱등 키 규칙은 `com.bokrilog.sync` 공개 타입 하나, 메시지는 루트 상수 하나로 모은다. lease 30초는 sync 상수 하나가 SQL 조각을 소유한다.

## Boundaries & Constraints

**Always:**
- 세션 속성 키·무장 수명 5분·Armed record 필드·성공 시 무장 처리의 비대칭(Reauth·Link·PlanEnd·Revocation은 지우고 Unlink·BybitConnection은 재전송 계약 때문에 유지)·세션 id 귀속 방식 셋(boundSessionId 제시, 사전 비교, 미보관)은 그대로다. 바꾸는 것은 메커니즘이지 정책이 아니다.
- `issue()` 내부의 `(user, command, target)` 무효화(같은 작업의 살아 있는 허가는 하나)는 그대로다. 바꾸는 것은 서비스의 명시적 abandon·supersede 호출뿐이다.
- 재인증 시각 제거는 "업무 변경이 이번 요청에서 실제로 실행됨"을 뜻하는 기존 플래그(`stored`·`ran`·`removed`·`LINKED`)와 같은 조건에서, 회전과 같은 자리에서 일어난다. Denied·replay·검증 실패는 지우지 않는다.
- 기존 시험의 단정은 약해지지 않는다. 바뀌는 단정은 "회전 뒤에도 `reauthenticated-at`이 남는다"뿐이며 그 반대 계약으로 바꾼다.
- 모듈은 `jakarta.servlet..`·`org.springframework.web..`에 의존할 수 없고 루트를 import하지 않는다(`ModuleBoundaryTest`). 세션 부품은 루트, 키 규칙은 `sync` 공개 패키지, lease 상수는 `sync.internal`에 둔다.
- 새로 쓰거나 고치는 주석은 한국어다(AI-10).

**Ask First:**
- `ModuleBoundaryTest`의 `MODULES` 목록을 바꾸거나 새 모듈을 만드는 것.
- 시험 삭제·`@Disabled`.

**Never:**
- 첫 스냅샷 `SyncJobService.admit`의 느슨한 키 규칙, `PlanConditionController`의 code 없는 422, 422 응답 빌더 표기 통일(P-2), lease 시계 함수(`now()`/`statement_timestamp()`) 변경, `Lease` record 6벌 통합, `rotateIfLinked`의 반환·예외 모양 변경, 프런트엔드(D-4, 별도 chore).
- 새 마이그레이션, 새 의존, 새 HTTP 경로.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 연쇄 승인 차단 | 재인증 1회 → 해제 준비·확정 성공 → 5분 안에 Bybit 연결(또는 다른 민감 command) 준비 | 두 번째 준비는 재인증 요구로 거절(허가 발급 불가) | 기존 `reauthRequired` 계약 |
| 재전송 | 확정 성공 뒤 같은 키 재전송 | 회전 없음, 시각도 이미 없음, 저장된 결과 그대로 | N/A |
| Denied | 허가 소비 거절(확인 문구 불일치 등) | 시각 유지, 회전 없음, 같은 무장으로 재시도 가능 | 기존 코드 |
| 오래된 탭 취소 | 탭 A 준비 → 탭 B 준비(새 허가) → 탭 A 취소 | 탭 A의 참조만 무효화, 탭 B 허가 생존 | N/A |
| 키 형식 오류 | `Idempotency-Key` 없음·15자·`-`시작·`..`포함·129자 | 모든 엄격 경로에서 422 `IDEMPOTENCY_KEY_REQUIRED` + 같은 메시지 한 문장 | 본문 400이 먼저인 기존 우선순위 유지 |

</frozen-after-approval>

## Code Map

루트 `backend/src/main/java/com/bokrilog/`, 시험 `backend/src/test/java/com/bokrilog/`.

- `UnlinkFlow`·`BybitConnectionFlow`·`PlanEndFlow`·`BybitRevocationFlow` -- `arm/peek/peekIncludingExpired/discard(HttpSession)` 본문이 같다. `LinkFlow`는 `HttpServletRequest` 오버로드와 `consume`, `ReauthenticationFlow`(`FLOW_ATTRIBUTE`·`AUTHENTICATED_AT_ATTRIBUTE`:53, `recordAuthentication`:199, `lastAuthenticatedAt`:219)는 신선도 로직이 더 있다.
- `BybitConnectionService.java:451`, `PlanEndCommandService.java:233`, `BybitRevocationCommandService.java:279` -- `private static rotate`, 예외 삼킴. `UnlinkService.settleSessions`:367(다른 세션 폐기 + changeSessionId), `AuthFlowHandlers.rotateIfLinked`:751(id 반환, 예외 전파, `AuthFlowHandlersRotationTest`).
- 실행 플래그와 회전 호출: `BybitConnectionService:353`, `PlanEndCommandService:190`, `BybitRevocationCommandService:242`, `UnlinkService:314`, `AuthFlowHandlers:704-707`.
- `ActionGrantService` -- `invalidate(u,c,t)`:254, `invalidateReference`:271, `SessionFacts.of`:499가 `lastAuthenticatedAt`의 유일한 읽기. `ActionGrantRepository.invalidateLiveGrants`:111.
- `(u,c,t)` 명시 호출자: `LinkService:236`(abandon)·`:252`(supersede, provider 다를 때만), `UnlinkService:223`·`:238`, `BybitConnectionService:266`. 참조 단위 선례: `PlanEndCommandService:107`·`:212`, `BybitRevocationCommandService:171`·`:261`.
- 멱등 키 규칙 5벌: `PlanEndController.validKey`:243(`BybitConnectionController:441` 재사용), `NextPlanBaselineController:38`, `plan/internal/PlanConditionService:475`, `sync/internal/SyncJobService.validCollectionKey`:709, `sync/internal/PlanPublicationService.validKey`:1468(`PlanSyncService:129`·`PlanVerificationService:81,144` 재사용). 규칙: 16~128자, `[A-Za-z0-9._:-]+`, `..` 없음, `-` 시작 아님. 규칙 시험은 `PlanEndControllerTest:107`.
- 메시지 6가지: `FirstSnapshotController:200`, `PlanEndController:82`, `DashboardController:119`·`:472`, `BybitConnectionController:374`, `NextPlanBaselineController:41`(인라인). 메시지 문장을 고정한 시험은 없다. code·422 고정: `IncrementalSyncTest:771`, `PlanEndTest:691`, `PlanVerificationTest:879`, `BybitConnectionRevocationTest:372`.
- `interval '30 seconds'` 18회: `sync/internal/SyncJobRepository`(56,81), `InitialCollectionRepository`(84,105,131), `CashflowClassificationRepository`(118,136), `CashflowConversionRepository`(232,279,310), `PlanPublicationRepository`(191,233), `PlanSyncRepository`(264,309,315,319,323,544). 컴파일 타임 SQL 조각 선례: `PlanSyncRepository.FENCE`:330.
- `reauthenticated-at` 잔존을 단정하는 시험: `AuthLinkProviderTest:149,575,612`, `AuthReauthenticationTest:138,162`. 재인증 창 시험: `ActionGrantTest`.
- `CLAUDE.md` `## 스토리가 고정한 계약` 세션·인증 절 -- 새 계약 한 줄.

## Tasks & Acceptance

**Execution:**
- [x] `SessionArming.java`(신규, 루트, package-private) -- 속성 키·수명을 받는 제네릭 `arm/peek/peekIncludingExpired/consume/discard`(`HttpSession`·`HttpServletRequest` 오버로드) -- Flow 4벌의 본문을 한 곳으로.
- [x] `UnlinkFlow`·`BybitConnectionFlow`·`PlanEndFlow`·`BybitRevocationFlow`·`LinkFlow`·`ReauthenticationFlow` -- 공통 메서드를 `SessionArming` 인스턴스에 위임, 키·record·고유 메서드 유지 -- 호출자 시그니처 불변.
- [x] `SessionRotation.java`(신규, 루트) -- `completeSensitiveCommand(HttpServletRequest)`: `reauthenticated-at` 제거 후 `changeSessionId`(예외 삼킴); `ReauthenticationFlow.clearAuthentication(HttpSession)` 추가 -- item-1.
- [x] `BybitConnectionService`·`PlanEndCommandService`·`BybitRevocationCommandService`·`UnlinkService.settleSessions`·`AuthFlowHandlers.rotateIfLinked` -- 사본 `rotate` 제거, 실행 플래그 자리에서 공통 호출(`rotateIfLinked`는 제거만 더하고 모양 유지).
- [x] `LinkService`·`UnlinkService`·`BybitConnectionService` -- abandon·supersede의 `invalidate(u,c,t)`를 무장의 `grantReference`로 `invalidateReference`; `ActionGrantService.invalidate(u,c,t)` 공개 메서드는 호출자가 없으면 제거.
- [x] `sync/IdempotencyKey.java`(신규, public) -- `HEADER`와 `isValid(@Nullable String)` 한 규칙; 5벌 삭제·위임. 루트 `IdempotencyKeyErrors`(또는 `ApiErrorHandler` 상수) -- `IDEMPOTENCY_KEY_REQUIRED` 메시지 한 문장, 6곳 교체.
- [x] `sync/internal/SyncLease.java`(신규) -- `DURATION = Duration.ofSeconds(30)`, `SQL_INTERVAL = "interval '30 seconds'"`; 18곳 교체.
- [x] 시험 -- `ActionGrantTest`(또는 `AuthUnlinkProviderTest`)에 I/O 1·3 행, `AuthLinkProviderTest`·`AuthReauthenticationTest`의 잔존 단정 반전, `IdempotencyKeyTest`(`PlanEndControllerTest:107` 축 이전), `SyncLeaseTest`(30초 고정).
- [x] `CLAUDE.md` -- 세션·인증 계약에 "민감 command 실행 성공은 재인증 시각을 지운다 — 본인 확인 1회는 command 1건. 명시적 허가 무효화는 참조 단위다" 한 줄, 백엔드 절에 부품 셋 이름.

**Acceptance Criteria:**
- Given 재인증 뒤 민감 command 하나가 실행됐을 때, when 같은 세션이 5분 안에 다른 민감 command를 준비하면, then 허가가 발급되지 않고 재인증이 요구된다.
- Given 루트·plan·sync의 모든 엄격 키 경로가 있을 때, when 같은 잘못된 키를 보내면, then 422·`IDEMPOTENCY_KEY_REQUIRED`·같은 메시지다.
- Given `grep -rn "interval '30 seconds'" backend/src/main`일 때, then `SyncLease` 한 곳만 나온다.
- Given `backend/gradlew --project-dir backend build`, then 통과하고 시험 수가 줄지 않는다.

## Design Notes

- `SessionArming<T extends Serializable>`은 `armedAt`을 꺼내는 `Function<T, Instant>`(또는 작은 인터페이스)로 만료를 판정한다. 네 Flow의 `Armed.isLive(now)`는 그대로 두고 어댑터만 넘긴다. `ReauthenticationFlow.arm(session, provider, userId, returnPath)`처럼 record를 만들어 넘기는 편의 메서드는 Flow에 남는다.
- 재인증 시각 제거를 소비 트랜잭션 안이 아니라 실행 플래그 자리에 두는 이유: `ActionGrantService.consume`은 `HttpSession`을 모르고 replay도 같은 `ConsumedRequiringSessionRotation`을 돌려준다. 실행 플래그만이 첫 실행을 안다.
- `issue()`의 `(u,c,t)` 무효화는 세션을 구분하지 않아 다른 세션의 같은 작업 허가도 지운다 — 이는 "같은 작업의 살아 있는 허가는 하나" 규칙이라 유지한다. 참조 단위로 바뀌는 것은 사용자가 취소·교체한 그 무장의 허가뿐이다.
- 첫 스냅샷 `admit`의 느슨한 규칙은 민감 command가 아니고 `FirstSnapshotTest`가 14자 키에 의존한다. 통일은 별도 결정으로 미룬다.

## 구현에서 확정한 것

- `SessionArming<T>`는 `(attribute, Class<T>, lifetime, Function<T, Instant> armedAt)`로 만들고 각 Flow가 `private static final ARMING` 하나를 든다. `LinkFlow`·`ReauthenticationFlow`의 `HttpServletRequest` 오버로드와 `consume`, 네 Flow의 `peekIncludingExpired`도 전부 위임이며 Flow의 공개 시그니처는 그대로다. 각 record의 `isLive`는 남겼다(`ReauthenticationFlowTest`·`AuthFlowHandlers` 콜백 경계가 쓴다).
- `SessionRotation`은 둘이다 — `completeSensitiveCommand(request)`(제거 → 회전, 예외 삼킴)와 `rotateAfterSensitiveCommand(request)`(같은 본체, 예외 전파·새 id 반환). `rotateIfLinked`와 `UnlinkService.settleSessions`(폐기 → 회전 순서를 지키는 try 안)가 뒤의 것을 쓴다.
- `LinkService`·`UnlinkService`의 `supersedePreviousArming`에서 "대상이 다를 때만" 조건을 뺐다. 참조 단위라 같은 대상이면 `issue()`가 이미 지운 참조를 다시 지우는 0행 호출이고 방금 발급한 허가는 걸리지 않는다. `ActionGrantService.invalidate(u,c,t)`는 호출자가 사라져 제거했고 `ActionGrantRepository.invalidateLiveGrants`는 `issue()`가 그대로 쓴다.
- `IdempotencyKey`(public, `sync`)는 `HEADER`·`REQUIRED_CODE`·`MIN_LENGTH`·`MAX_LENGTH`·`isValid`다. 열한 곳의 `@RequestHeader(name="Idempotency-Key")`도 `IdempotencyKey.HEADER`로 바꿨다. 메시지 한 문장은 `ApiErrorHandler.MESSAGE_IDEMPOTENCY_KEY_REQUIRED`("요청 키가 없거나 형식이 맞지 않아 요청을 받지 않았습니다. 화면을 새로 열어 다시 시도해 주세요.")이며 어떤 시험도 옛 문장을 고정하지 않았다.
- `AuthReauthenticationTest:138,162`와 `AuthLinkProviderTest:575,612`의 잔존 단정은 **재인증 콜백 직후**의 기록이라 뒤집지 않았다 — 뒤집으면 재인증 자체가 시각을 남기지 않는다는 뜻이 된다. 뒤집은 것은 민감 command(연결) 실행 뒤를 보는 `AuthLinkProviderTest:149` 하나다.
- 한 세션에서 민감 command를 연쇄하던 시험·검증은 사이에 재인증을 더했다 — `BybitConnectionRevocationTest`(종료 → 해제, 해제 → 종료 둘), `deploy/compose/verify-same-origin.sh`(해제 → Bybit 연결; 거부를 먼저 단정한 뒤 재인증), `frontend/e2e/auth-entry.spec.ts`(연결 → 해제). 프런트엔드 제품 코드는 손대지 않았다.
- `PlanEndControllerTest`의 키 규칙 단정은 `sync/IdempotencyKeyTest`로 옮겼다. 새 시험은 `IdempotencyKeyTest` 3건, `SyncLeaseTest` 2건, `AuthUnlinkProviderTest` 2건(I/O 1·3)이다.
- 리뷰 patch 16건(2026-10-06): `verify-same-origin.sh`의 옛 계약 단정("연결 뒤에도 재인증 기록이 남아 있다" → `'0'`)과 카카오 무장 전 재인증을 `reauthenticate_google_session` 함수 하나로 모았고 Bybit 절도 같은 함수를 쓴다(거부 뒤 무장·시각 부재 단정 추가). `UnlinkService.settleSessions`는 폐기 예외 경로에서도 시각이 지워지도록 `clearAuthentication`을 try 앞에 둔다. supersede 셋은 `(session)` 하나를 받고 전부 이전 참조를 지운다(Bybit도 0행이어도 호출). `SyncLease`는 `public`·`SQL_INTERVAL` 하나뿐이고 `SyncLeaseTest`가 `src/main/java`를 훑어 리터럴이 그 파일에만 있음을 고정하며 `SuccessorPlanTest` 픽스처도 상수를 쓴다. 채택 고정: `BybitConnectionTest`·`PlanEndTest` 성공 뒤 시각 null, `BybitConnectionRevocationTest` 연쇄 둘의 선행 403·허가 불변, replay 뒤 시각 null, `AuthFlowHandlersRotationTest` LINKED/DENIED, 신규 `SessionArmingTest`·`SessionRotationTest`(DB 없음), 메시지 상수 단정 `FirstSnapshotTest`·`PlanVerificationTest`·`SuccessorPlanTest`. `FirstSnapshotController`의 code 별칭 제거, `IdempotencyKey` javadoc은 경로별 거절 모양을 사실대로 적는다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: BUILD SUCCESSFUL, `-Werror` 경고 0, 시험 수 ≥ 기준(`ea56b98`).
- `grep -rn "interval '30 seconds'" backend/src/main/java` -- expected: `SyncLease.java` 1건.
- `grep -rn "private static void rotate\|invalidate(.*\"link-provider\"\|invalidate(.*\"unlink-provider\"\|invalidate(.*\"bybit-connection-create\"" backend/src/main/java` -- expected: 0건.
- `grep -rn "IDEMPOTENCY_KEY_REQUIRED" backend/src/main/java` -- expected: 메시지 문장 리터럴은 1곳.

- **CI 첫 실행 실패(PR #71, `backend-quality`)**: `PlanVerificationTest.aFlakyPageIsRetriedAndTheSyncSucceeds`가 `plan sync finished` 줄을 0건으로 읽었다. 그 줄은 상태 커밋과 종료 사실 재조회 뒤에 찍히는데 시험은 `awaitSync`(상태만 폴링) 직후 읽는 기존 경합이며 이 chore의 변경과 무관하다. `PlanSyncTestBase.endingLines(output, job)`(줄이 보일 때까지 최대 10초 폴링, `IncrementalSyncTest:841` 선례)를 두고 같은 패턴 9곳을 바꿨다(`cae71b4`). 재시도로 덮지 않았다.

## Suggested Review Order

**재인증 시각 제거와 세션 회전(item-1, 새 계약)**

- 진입점 — 실행 성공의 두 효과(시각 제거·회전)를 한 호출로 묶고 예외 삼킴·전파 두 모양만 남겼다
  [`SessionRotation.java:34`](../../backend/src/main/java/com/bokrilog/SessionRotation.java#L34)
- 실행 플래그(`stored`) 자리에서만 부른다 — Denied·replay는 지우지 않는다
  [`BybitConnectionService.java:363`](../../backend/src/main/java/com/bokrilog/BybitConnectionService.java#L363)
- 폐기가 예외로 끝나도 시각은 먼저 지운다(리뷰 patch)
  [`UnlinkService.java:378`](../../backend/src/main/java/com/bokrilog/UnlinkService.java#L378)
- 콜백 경로는 반환·예외 모양을 유지한 채 본체만 공통으로
  [`AuthFlowHandlers.java:760`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L760)
- 계약 문장 한 줄
  [`CLAUDE.md:205`](../../CLAUDE.md#L205)

**세션 무장 메커니즘 한 벌**

- 키·수명·`armedAt` 추출만 받는 제네릭; 만료는 peek에서 버리고 타입 불일치는 남긴다
  [`SessionArming.java:29`](../../backend/src/main/java/com/bokrilog/SessionArming.java#L29)
- 여섯 Flow가 같은 한 줄로 위임한다 — 키·record·고유 메서드는 그대로
  [`ReauthenticationFlow.java:65`](../../backend/src/main/java/com/bokrilog/ReauthenticationFlow.java#L65)

**허가 무효화 참조 단위 통일**

- abandon·supersede가 자기 무장의 참조만 지운다 — 다른 세션의 허가는 살린다
  [`LinkService.java:239`](../../backend/src/main/java/com/bokrilog/LinkService.java#L239)
- 셋 중 유일하게 무효화가 없던 supersede를 같은 모양으로
  [`BybitConnectionService.java:285`](../../backend/src/main/java/com/bokrilog/BybitConnectionService.java#L285)

**멱등 키 규칙·메시지 한 벌**

- 루트·plan·sync가 모두 닿는 유일한 자리라 `sync` 공개 패키지에 둔다
  [`IdempotencyKey.java:26`](../../backend/src/main/java/com/bokrilog/sync/IdempotencyKey.java#L26)
- 같은 code의 메시지 6가지를 한 문장으로
  [`ApiErrorHandler.java:90`](../../backend/src/main/java/com/bokrilog/ApiErrorHandler.java#L90)

**lease 30초 상수**

- 리터럴 18회를 컴파일 타임 SQL 조각 하나로; 시계 함수는 손대지 않았다
  [`SyncLease.java:22`](../../backend/src/main/java/com/bokrilog/sync/internal/SyncLease.java#L22)

**검증 스크립트·시험**

- 옛 계약 단정의 반전과 카카오 무장 전 재인증 왕복(리뷰 patch, CI 통합 job이 깨질 자리였다)
  [`verify-same-origin.sh:1593`](../../deploy/compose/verify-same-origin.sh#L1593)
- I/O 1행 연쇄 승인 차단
  [`AuthUnlinkProviderTest.java:119`](../../backend/src/test/java/com/bokrilog/AuthUnlinkProviderTest.java#L119)
- I/O 4행 오래된 탭 취소(참조 단위가 아니었다면 실패)
  [`BybitConnectionTest.java:495`](../../backend/src/test/java/com/bokrilog/BybitConnectionTest.java#L495)
- 새 부품의 메커니즘 시험
  [`SessionArmingTest.java`](../../backend/src/test/java/com/bokrilog/SessionArmingTest.java)
- 리터럴 재발을 막는 소스 스캔
  [`SyncLeaseTest.java`](../../backend/src/test/java/com/bokrilog/sync/internal/SyncLeaseTest.java)
