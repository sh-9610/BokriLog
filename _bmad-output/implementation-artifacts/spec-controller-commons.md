---
title: '루트 컨트롤러 공통 helper와 IdempotencyKey.CONFLICT_CODE'
type: 'chore'
created: '2026-11-03'
status: 'done'
baseline_commit: '6b16acb698baaf858ebbbf394c7b6709a363c470'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 루트 패키지 컨트롤러가 세션 사용자 판독(`currentUserId` 15벌), SENSITIVE 접근 판정(`allowedUser` 5벌과 인라인 1곳), 공통 오류 본문·no-store·correlation id 빌더(C1~C3 여러 벌)를 각자 들고 있고, `"IDEMPOTENCY_CONFLICT"`는 상수 5벌과 인라인 리터럴 10곳으로 흩어져 있다(Epic 4 회고 D-5, AI-8 `epic-4-retro-item-47`의 ① 백엔드 묶음). Epic 5가 사용자 컨트롤러를 더하기 전에 사본이 더 늘어난다.

**Approach:** 루트 패키지에 package-private helper 하나(`ControllerSupport`)를 두어 세 판정·빌더를 옮기고, `IdempotencyKey`에 `CONFLICT_CODE`를 더해 모든 정의·리터럴을 그것으로 바꾼다. 응답(상태·본문·헤더)과 로그는 한 글자도 바꾸지 않는 순수 정리다.

## Boundaries & Constraints

**Always:**
- 모든 응답의 상태 코드·본문 키·`Content-Type`·`Cache-Control`·correlation id 규칙이 바뀌지 않는다. 기존 시험은 고치지 않고 통과해야 한다(상수 참조를 옮기는 컴파일 수정만 예외).
- helper는 `com.bokrilog` 루트 패키지에 두고 engagement 패키지를 import하지 않는다(`ModuleBoundaryTest`의 engagement 접근 7개 목록 유지). identity는 공개 API(`AccountAccessApi`)로만 쓴다.
- `IdempotencyKey`는 sync 공개 패키지에 그대로 둔다. operations 모듈은 참조하지 않는다(sync → operations 의존이 있어 순환이 된다).
- 새 주석은 한국어다. 손대는 범위의 기존 영어 주석만 바꾼다.

**Ask First:**
- 합치려면 응답 헤더·상태·로그 수준이 바뀌는 사본을 만났을 때(예: `Content-Type`이 없는 빌더, 403과 404가 갈리는 사용자 판정).

**Never:**
- 의미가 다른 변형을 합치지 않는다 — 403을 내는 사용자 판정(FirstSnapshot·PlanCondition·NextPlanBaseline·AuthSession unlink), WRITE 접근(BybitRevocation·PlanEnd command service), 전용 오류 record(`OperatorError`·`PlanEndError`·`InquiryError`)와 Bybit 거절 사유 매핑. 이들은 공용 판독(`currentUserId`·correlation id·no-store 응답)만 쓸 수 있다.
- `NextPlanBaselineController`의 오류 응답에 `Content-Type`을 더하지 않는다.
- `internal()` 500 로그의 수준·형식을 통일하지 않는다(별도 결정).
- `ApiErrorHandler`의 자체 응답에 no-store를 더하지 않는다(`GET /api/status` 빈 `Cache-Control` 고정).
- 프런트엔드 POST·JSON 판독 helper, 광고 시험 helper, engagement 바깥 의존 목록(AI-8 ②·③)은 이 작업 범위가 아니다(deferred-work에 둘로 남겼다).
- 새 의존·마이그레이션·HTTP 경로를 더하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 미인증 요청 | 익명·미인증·`getName()`이 UUID 아님 | helper가 `null` — 각 컨트롤러가 지금과 같은 404/403 | 예외 없음 |
| SENSITIVE 거부 | 삭제 진행·삭제된 계정 | `allowedUser` 경로는 지금과 같은 404 | N/A |
| 공통 오류 | C1~C3 빌더를 쓰던 4xx·5xx | 같은 상태·`{"code","message","correlationId"}`·JSON·`no-store` | N/A |
| 멱등 충돌 | 같은 키, 다른 요청 | code `IDEMPOTENCY_CONFLICT`·기존 메시지·상태 그대로 | N/A |
| 헤더 없는 빌더 | NextPlanBaseline 오류 | `Content-Type` 없음 그대로 | N/A |

</frozen-after-approval>

## Code Map

경로 접두사 `backend/src/main/java/com/bokrilog/`.

- `ApiErrorHandler.java` -- `record ApiError`(:103), `REQUEST_ID_HEADER`(:97), package-private `correlationIdOf(@Nullable String)`(:197). helper가 같은 패키지에서 그대로 쓴다. 자체 응답에는 no-store가 없고 그대로 둔다.
- `currentUserId()` 15벌(모두 `SecurityContextHolder` → 미인증·`AnonymousAuthenticationToken`·UUID 파싱 실패면 `null`, 의미 동일) -- AdConsent:227, AdEligibility:177, AdTransition:175, ClientTelemetry:242, SupportInquiry:315, Operator:865, OperatorGate:114(필터), AuthSession:596, BybitConnection:618, Dashboard:1038, FirstSnapshot:271, PlanCondition:171, PlanEnd:282, ServiceNotice:190, NextPlanBaseline `user()`:24(FQN 인라인, POST에서 세 번 호출).
- `allowedUser()` 5벌(글자 동일, `decide(u,u,SENSITIVE)`) -- AdConsent:184, AdEligibility:145, AdTransition:144, ClientTelemetry:210, SupportInquiry:273. 같은 판정 인라인: ServiceNotice:106-113.
- 오류 빌더 C1(`error(HttpStatus, ApiError)`+`correlationIdOf(HttpServletRequest)`, 글자 동일) -- AdConsent:212/217, AdEligibility:162/167, AdTransition:160/165, ClientTelemetry:227/232. C2 -- Dashboard `error`:759·`correlationOf`:1004, BybitConnection `revocationError`:543. C3 인라인 -- ServiceNotice:123-127, PlanCondition:137·145, FirstSnapshot:223-233. 유지 -- NextPlanBaseline:40·46(`Content-Type` 없음).
- 전용 record(유지, 공용 판독만 사용) -- Operator `OperatorError`(:356, `error`:844), PlanEnd `PlanEndError`(:107, `error`:264 — `PlanEndControllerTest`가 캐스트), SupportInquiry `InquiryError`(:105, `error`:301), BybitConnection `error(SourceRejectionReason, …)`:570.
- `sync/IdempotencyKey.java` -- `HEADER`·`REQUIRED_CODE`(:32) 옆에 `CONFLICT_CODE`를 둔다.
- `IDEMPOTENCY_CONFLICT` 상수 -- AdConsentController:59, SupportInquiryController:67, BybitRevocationCommandService:79, OperatorCommandService:70(OperatorController:156 참조), PlanEndCommandService:53(PlanEndController:80 참조). 인라인 -- DashboardController:129·532, FirstSnapshotController:194, `sync/internal` PlanPublicationService:190, PlanVerificationService:134·171·239, PlanSyncService:152·204·312.
- `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java` -- 모듈의 서블릿 의존 금지(:157), engagement 접근 7개(:508-515). helper 위치의 제약.
- `backend/src/test/java/com/bokrilog/sync/internal/SyncLeaseTest.java` -- 운영 소스 리터럴 재발 금지 시험의 선례.
- 안전망 -- no-store·본문 키 단정: AdConsentTest·AdEligibilityTest·AdTransitionTest·ClientTelemetryTest·SupportInquiryTest·ServiceNoticeTest·OperatorCommandTest(:478 정확 일치)·PlanEndTest·BybitConnectionRevocationTest·IncrementalSyncTest·FirstSnapshotTest. `AdConsentTest:162`가 `AdConsentController.IDEMPOTENCY_CONFLICT`를 참조한다.
- 열린 회고 액션 -- `epic-4-retro-item-47`: ①(백엔드)만 닫고 ②·③은 deferred-work 두 항목으로 남긴다(일부). `epic-1-retro-item-3`(`AuthSessionController.cancelUnlink`의 provider 대조): 같은 파일의 `currentUserId`만 바꾸고 건드리지 않고 지나간다. `epic-2-retro-item-21`(`PlanPublicationService` 책임 분리): 리터럴 한 줄만 바꾸고 건드리지 않고 지나간다.

## Tasks & Acceptance

**Execution:**
- [x] `ControllerSupport.java`(신규, 루트, package-private final) -- `currentUserId()`, `allowedUser(AccountAccessApi)`, `correlationIdOf(HttpServletRequest)`, JSON+no-store 응답 `error(HttpStatus, ApiError)`·`error(HttpStatus, code, message, request)`와 전용 record용 `noStoreJson(HttpStatus, T)`를 둔다 -- 사본의 단일 정의.
- [x] `sync/IdempotencyKey.java` -- `CONFLICT_CODE = "IDEMPOTENCY_CONFLICT"`와 한국어 주석 -- code 상수의 단일 정의.
- [x] 위 Code Map의 컨트롤러 15곳·`OperatorGate`·command service 셋·`sync/internal` 셋 -- 사본·상수·리터럴을 helper·`CONFLICT_CODE`로 바꾼다. 전용 record와 403 변형은 판정·본문을 그대로 두고 공용 판독만 쓴다 -- 동작 불변.
- [x] `backend/src/test/java/com/bokrilog/AdConsentTest.java` -- 상수 참조를 `IdempotencyKey.CONFLICT_CODE`로 -- 컴파일 수정.
- [x] `backend/src/test/java/com/bokrilog/ControllerSupportTest.java`(신규) -- 운영 소스를 훑어 `"IDEMPOTENCY_CONFLICT"` 리터럴이 `IdempotencyKey` 밖에 없고, `currentUserId`·`allowedUser` 정의가 `ControllerSupport` 밖에 없음을 단정한다. helper 단위 시험: 익명·미인증·비 UUID 이름은 `null`, SENSITIVE 거부는 `null`, 오류 응답은 JSON·`no-store`·세 키 -- 재발 방지와 매트릭스 검증.
- [x] `CLAUDE.md` -- 백엔드 공통 부품 문장에 `ControllerSupport`·`IdempotencyKey.CONFLICT_CODE`를 더한다(새 컨트롤러는 사본을 만들지 않는다) -- 규칙의 자리.

**Acceptance Criteria:**
- Given 이 변경, when `backend/gradlew --project-dir backend build`, then 기존 시험이 상수 참조 한 곳 외에 수정 없이 통과하고 `ModuleBoundaryTest`·`-Werror`가 통과한다.
- Given 운영 소스, when grep, then `currentUserId()` 정의는 `ControllerSupport` 하나이고 `"IDEMPOTENCY_CONFLICT"` 리터럴은 `IdempotencyKey` 하나다.

## Spec Change Log

- 구현에서 확정한 것(2026-11-03): Code Map 밖의 16번째 사본 `AuthFlowHandlers.sessionPrincipal()`(같은 판정, 다른 이름)도 `ControllerSupport.currentUserId()`로 옮겼다 — 동작 동일, 소스 훑기 시험이 어차피 잡는다. `DashboardController`만 긴 줄 때문에 `currentUserId`·`error`를 정적 import한다. `NextPlanBaselineController`의 POST는 사용자를 세 번 대신 한 번 읽는다(같은 요청 안이라 같은 값).
- 매트릭스 감사(2026-11-03): "헤더 없는 빌더" 행을 덮는 시험이 없어 `SuccessorPlanTest` keyless 422 단정에 `no-store`·협상된 `application/json`·세 키를 더했다.
- 1차 리뷰(2026-11-03, blind·edge-case·verification-gap): patch 6 · defer 0 · reject 12. patch — NextPlanBaseline 주석이 "Content-Type 없음"이라 적었으나 실제는 명시 없이 협상(주석·시험 주석 정정), 소스 훑기가 연쇄 호출 판독과 요청 식별자 헤더 사본을 놓침(정규식 확장·헤더 훑기 추가), 시험의 32자리 hex 리터럴(gitleaks 모양, 실행 시 생성으로), `ControllerSupport` Javadoc의 쓰임 범위(필터·인증 핸들러)와 BybitConnection에서 지운 이중 방어 근거 복원, `CLAUDE.md` 문장의 "이 둘" 모호·시험 범위와 불일치, 이번 작업이 더한 deferred 항목의 파일 수("다섯"→"여섯"). reject — 409 `NEXT_BASELINE_UNAVAILABLE` 무시험(이 변경과 무관한 기존 공백), correlation id 재계산(기존과 같은 위치·값), Dashboard 정적 import, `CONFLICT_CODE` 위치(SPEC 결정), 프런트 code 대조 장치(범위 밖), WRITE command service의 판정(의미가 다른 변형), 안 쓰는 import(확인 결과 0건), 지운 상수의 시험 참조(컴파일 통과), 작업 디렉터리 의존 경로(`SyncLeaseTest` 선례), `error` 오버로드 선택 규칙, deferred 장부 건수(done 전환이 맞춘다), SPEC 부재(diff 밖에 있었다).

## Design Notes

공용 판독과 판정을 가르는 선: `currentUserId`·correlation id·no-store JSON 응답은 모든 컨트롤러가 같은 값을 내므로 공유한다. 판정(404 SENSITIVE / 403 미인증 / WRITE)과 본문 타입은 컨트롤러 계약이라 helper가 고르지 않는다 — `allowedUser`는 지금 404 SENSITIVE 다섯 곳과 ServiceNotice만 쓴다.

```java
// 전용 record도 같은 헤더 규칙만 빌린다
return ControllerSupport.noStoreJson(status, new PlanEndError(code, message, ControllerSupport.correlationIdOf(request), reason));
```

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 시험 전부 통과(이 PC는 Windows 소켓 `Bad address` 간헐 실패가 있어 바뀐 클래스 묶음 `--tests`로 확인하고 전체 판정은 CI `ci-required`)
- `grep -rn '"IDEMPOTENCY_CONFLICT"' backend/src/main` -- expected: `IdempotencyKey.java` 한 줄

## Suggested Review Order

**공통 helper의 경계**

- 판정은 고르지 않고 판독·헤더 규칙만 공유한다는 선이 여기 있다
  [`ControllerSupport.java:32`](../../backend/src/main/java/com/bokrilog/ControllerSupport.java#L32)

- 15벌 사본과 같은 null 규칙, 이중 방어 근거
  [`ControllerSupport.java:46`](../../backend/src/main/java/com/bokrilog/ControllerSupport.java#L46)

- 404 SENSITIVE 경로만 쓰는 접근 판정
  [`ControllerSupport.java:63`](../../backend/src/main/java/com/bokrilog/ControllerSupport.java#L63)

- 전용 record도 JSON·no-store 헤더만 빌린다
  [`ControllerSupport.java:89`](../../backend/src/main/java/com/bokrilog/ControllerSupport.java#L89)

**멱등 충돌 code 단일화**

- sync 공개 패키지의 단일 정의, operations는 순환 때문에 쓰지 않는다
  [`IdempotencyKey.java:39`](../../backend/src/main/java/com/bokrilog/sync/IdempotencyKey.java#L39)

- 모듈 내부 리터럴도 같은 상수로
  [`PlanSyncService.java:152`](../../backend/src/main/java/com/bokrilog/sync/internal/PlanSyncService.java#L152)

**호출자 전환(동작 불변)**

- 대표 전환: 사본 삭제 뒤 helper 호출
  [`AdConsentController.java:79`](../../backend/src/main/java/com/bokrilog/AdConsentController.java#L79)

- 전용 record는 본문을 지키고 헤더만 공유
  [`PlanEndController.java:263`](../../backend/src/main/java/com/bokrilog/PlanEndController.java#L263)

- 합치지 않은 예외와 그 이유
  [`NextPlanBaselineController.java:35`](../../backend/src/main/java/com/bokrilog/NextPlanBaselineController.java#L35)

- 필터·인증 핸들러도 같은 판독(SPEC 밖 16번째 사본 포함)
  [`OperatorGate.java:57`](../../backend/src/main/java/com/bokrilog/OperatorGate.java#L57)
  [`AuthFlowHandlers.java:1155`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L1155)

- 긴 줄 때문에 유일하게 정적 import
  [`DashboardController.java:3`](../../backend/src/main/java/com/bokrilog/DashboardController.java#L3)

**재발 방지와 문서**

- 운영 소스 훑기 셋: 리터럴·사용자 판독·요청 식별자 헤더
  [`ControllerSupportTest.java:152`](../../backend/src/test/java/com/bokrilog/ControllerSupportTest.java#L152)
  [`ControllerSupportTest.java:179`](../../backend/src/test/java/com/bokrilog/ControllerSupportTest.java#L179)

- 매트릭스 "헤더 없는 빌더" 행의 협상 결과 고정
  [`SuccessorPlanTest.java:299`](../../backend/src/test/java/com/bokrilog/SuccessorPlanTest.java#L299)

- 새 컨트롤러가 따를 규칙 문장
  [`CLAUDE.md:91`](../../CLAUDE.md#L91)
