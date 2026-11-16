---
title: '인증 흐름 무장 가드 — 남의 요청이 살아 있는 연결·재인증·해제 무장을 소비·파괴하지 못하게'
type: 'bugfix'
created: '2026-11-16'
status: 'done'
baseline_commit: '5bff192036682b5de7885893f90a5eb703549cfb'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 인증된 세션에 살아 있는 무장(연결 `LinkFlow`·재인증 `ReauthenticationFlow`·해제 `UnlinkFlow`)을 그 흐름과 무관한 요청이 소비하거나 지울 수 있는 경로가 셋 있다(Epic 1 회고 F2·F3·F6, 액션 item-2·3·4).
- (F2) 외부 사이트가 `top.location`으로 `state` 없는 콜백을 열면 실패 핸들러(`onFailure`)가 무장을 조건 없이 버린다. 이것을 반복하면 그 사용자의 연결·재인증을 계속 거부할 수 있다. 회고는 이 결함을 실연동 게이트 전 필수로 적었다.
- (F3) 재인증 무장이 없을 때 `callbackLinkFlow`가 콜백 제공자를 보지 않는다. 그래서 다른 제공자의 콜백이 연결 무장을 소비해 실패시킨다.
- (F6) `cancelUnlink`가 경로의 제공자를 보지 않는다. 그래서 다른 제공자의 취소 요청이 진행 중인 해제 무장과 허가를 지운다.

**Approach:** 무장은 그 흐름의 사실인 요청만 소비하거나 버린다.
- (F2) 인증된 세션에서 이 세션의 authorization request에 닿지 못한 실패(`invalid_request`·`authorization_request_not_found`)는 무장을 건드리지 않는다. 기존 "무장 없는 콜백"과 같이 세션을 바꾸지 않고 지금 목적지로 되돌린다.
- (F3) 재인증 무장이 없을 때 연결 무장은 제공자가 일치하는 콜백만 소비한다.
- (F6) 해제 취소는 무장의 대상 제공자와 경로 제공자가 같을 때만 버린다.

## Boundaries & Constraints

**Always:**
- 응답 모양은 그대로 둔다. 해제 취소는 모르는 제공자 404, 그 밖은 언제나 204다(무장 유무·대상 불일치를 응답으로 구분하지 않는다). 가드에 걸린 콜백은 다른 "무장 없는 콜백"과 같은 302(지금 목적지)다.
- 세션 행·주체·CSRF 토큰은 가드에 걸린 요청에서 바뀌지 않는다.
- 사용자 취소(`access_denied`)·제공자 오류·검증 실패처럼 **이 세션의 authorization request를 소비한 실패**는 지금처럼 무장을 소비하고 표지와 함께 수렴한다.
- 미인증(로그인 흐름) 세션의 실패 수렴은 바꾸지 않는다(`converge`·`discardSession`).
- `UnlinkService.abandon(HttpSession)`의 기존 호출자(로그아웃·흐름 갈아타기·계정 삭제)는 대상을 보지 않고 버리는 그대로다. 대상 대조는 취소 경로에만 둔다.
- 시험의 code·state·토큰 모양 값은 리터럴로 쓰지 않는다(gitleaks `generic-api-key`, PR #60).

**Ask First:**
- 기존 Story 1.8·1.10·1.11 시험의 기대를 이 Intent가 요구하는 것 밖에서 바꿔야 하는 경우.
  - 예외: `AuthLinkProviderTest.무장과_다른_제공자의_콜백은_실패한다`는 F3이 정확히 뒤집는 계약이다. 그 기대를 "연결 무장은 살아 남고 콜백은 지금 목적지로 되돌린다"로 바꾸는 것은 승인 범위 안이다.
- Spring Security가 `state` 없는·모르는 `state` 콜백에 위 두 code가 아닌 다른 code를 내는 것을 확인한 경우.

**Never:**
- 재인증 신선도(item-6), 확인 hash(item-7), 해제 대화상자 문구(item-5), 콜백 경계의 고아 허가(F5)는 다루지 않는다.
- 전역 CSRF·인가 규칙, 쿠키 속성, 필터 순서를 바꾸지 않는다.
- 새 HTTP 경로·응답 code를 만들지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| F2 위조 콜백(연결) | 인증 세션 + 살아 있는 연결 무장(네이버), 외부가 `/api/auth/callback/naver`를 `state`·code 없이 연다 | 302 지금 목적지. 연결 무장·허가가 그대로라 이어지는 정상 네이버 콜백이 연결을 완료한다 | 세션·주체 불변 |
| F2 위조 콜백(재인증) | 인증 세션 + 살아 있는 재인증 무장(Google), 모르는 `state`의 Google 콜백 | 302 지금 목적지. 이어지는 정상 콜백이 재인증을 완료한다 | 세션 불변 |
| F2 정상 실패 유지 | 같은 무장 + 유효한 `state`의 `access_denied`·`server_error` | 지금처럼 무장 소비, 취소·실패 표지 | — |
| F3 남의 제공자 콜백 | 재인증 무장 없음, 연결 무장 카카오, 유효한 `state`의 네이버 콜백 | 연결 무장을 소비하지 않는다. 302 지금 목적지, 매핑·허가 소비 0건. 이어지는 카카오 콜백이 연결을 완료한다 | — |
| F3 기존 판정 유지 | 두 무장이 함께 있고 콜백이 재인증 제공자 | 재인증이 이긴다(지금과 같다) | — |
| F6 다른 제공자 취소 | 네이버 해제 무장, `POST /api/auth/unlink/google/cancel` | 204, 네이버 무장·허가가 그대로라 이어지는 네이버 확정이 성공한다 | — |
| F6 같은 제공자 취소 | 네이버 해제 무장, 네이버 취소 | 204, 무장·허가가 사라진다(지금과 같다) | — |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java`
  - `authenticationFlowFilter`(:311~380): 인증 세션의 콜백 판정이다. 연결 무장 → 통과, 무장 없음 → `currentDestination` 302(:349~357, 가드가 재사용할 응답), 재인증 무장 → 통과.
  - `callbackLinkFlow`(:816~827): `armed == null`이면 제공자를 보지 않는다(F3).
  - `onFailure`(:975~1026): `LinkFlow.discard`(:1001)·`ReauthenticationFlow.consume`(:1013)을 조건 없이 부른다(F2).
  - `carriesEstablishedPrincipal`(:1117): 인증 세션 판정. 실패 핸들러에서는 저장된 `SecurityContext`를 본다. thread-local은 이미 비어 있다.
  - `isUserCancellation`(:1176): `OAuth2ErrorCodes` 판독의 선례다.
- Spring Security `OAuth2LoginAuthenticationFilter`: 응답 매개변수가 없으면 `invalid_request`, 세션의 authorization request와 `state`가 맞지 않으면 `authorization_request_not_found`(필터의 private 상수 — 리터럴과 근거 주석이 필요)를 던진다. 저장된 요청은 `state`가 맞을 때만 지워진다.
- `backend/src/main/java/com/bokrilog/AuthSessionController.java:592-604` — `cancelUnlink`가 `unlinkService.abandon(session)`을 부른다(F6). javadoc :575~590이 404/204 계약이다.
- `backend/src/main/java/com/bokrilog/UnlinkService.java:224-230` — `abandon(HttpSession)`. `UnlinkFlow.Armed.targetProvider`(`UnlinkFlow.java:93`)로 대조한다. 같은 무효화 본문(`invalidateReference`)을 재사용한다.
- 시험:
  - `backend/src/test/java/com/bokrilog/AuthLinkProviderTest.java`: :334 F3로 기대를 바꿀 시험, :440·:464 정상 실패가 유지되는지.
  - `AuthReauthenticationTest.java`: :445 무장 없는 콜백, :285·:313 정상 실패.
  - `AuthUnlinkProviderTest.java`: :578~658 취소 시험 셋, 같은 제공자만 다룬다.
  - `AuthFlowTestBase.java`: `callback`·`callbackWithError`·`cancelUnlink`(:345~395, :673~700) helper.
- 걸린 열린 회고 액션:
  - `epic-1-retro-item-2`·`item-3`·`item-4`(주제·이름): 닫는다.
  - `epic-1-retro-item-6`(주제 — `AuthFlowHandlers` 재인증 시각 기록): 지나간다.
  - `epic-1-retro-item-7`(주제 — 해제 허가의 확인 hash): 지나간다.
  - `epic-1-retro-item-5`(주제 — 해제 대화상자): 지나간다.
  - `epic-3-retro-item-39`(과정 — 시험의 토큰 모양 값): 따른다. 리터럴 금지는 Always에 있다.
  - `epic-1-retro-item-8`·`epic-3-retro-item-38`(과정 — defer 판정): done 전환에서 판정한다.

## Tasks & Acceptance

**Execution:**
- [x] `AuthFlowHandlers.java` — `callbackLinkFlow`에서 재인증 무장이 없으면 제공자가 일치할 때만 연결 무장을 돌려준다. javadoc의 "일치하는 쪽이 이긴다"를 그 경우까지 넓혀 고친다.
- [x] `AuthFlowHandlers.java` — `onFailure` 맨 앞에 가드를 둔다. 저장된 주체가 있는 세션이고 예외 code가 `invalid_request`·`authorization_request_not_found`면 무장을 건드리지 않고 `currentDestination`으로 302한다. 근거 주석은 `discardSession`의 교차 사이트 위협과 같다.
- [x] `UnlinkService.java` — 대상 제공자를 받는 취소용 `abandon` 오버로드를 둔다. 무장 대상과 다르면 아무것도 하지 않는다.
- [x] `AuthSessionController.java` — `cancelUnlink`가 경로 제공자를 넘긴다. javadoc에 대상 대조를 적는다.
- [x] 시험 — I/O 표의 모든 행을 `AuthLinkProviderTest`·`AuthReauthenticationTest`·`AuthUnlinkProviderTest`에 둔다. 위조 콜백은 `state`·code 없음과 모르는 `state` 두 축이다. 각 행은 이어지는 정상 흐름이 성공하는 것까지 단정한다.

**Acceptance Criteria:**
- Given 위 수정, when `backend/gradlew --project-dir backend build`를 돌리면, then 기존 인증 시험(1.4~1.11)이 F3 예외 하나 말고는 기대 변경 없이 통과한다.
- Given 가드에 걸린 요청, when 응답·세션 행을 보면, then 응답 status·Location과 세션 ID·`principal_name`이 "무장 없는 콜백"·기존 204와 같다.

## Spec Change Log

- 2026-11-16 구현: SPEC 문구 밖에서 두 가지를 정했다.
  - (1) `onSuccess`에서 `callbackLinkFlow`가 null이고 재인증 무장이 없으면 남은 연결 무장을 연결 분기로 넘긴다. 검증까지 온 제공자 불일치 콜백이 로그인 분기(`establish`)로 떨어지는 방어선이다. 실제 경로는 흐름 경계가 먼저 접는다.
  - (2) `invalid_request`는 필터가 스스로 던진 경우(요청이 인가 응답 모양이 아님)에만 가드에 건다. 제공자가 유효한 `state`와 함께 보낸 `error=invalid_request`는 기존처럼 소비한다(Always "제공자 오류는 지금처럼").
  - 운영자 목적지 접기가 흐름 경계와 같도록 가드는 저장된 세션 주체로 `currentDestination`을 판정한다.
- 2026-11-16 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) patch 루프, 12건:
  - `onSuccess` 방어선이 만료된 연결 무장도 연결 분기로 넘긴다(`peekIncludingExpired`, 흐름 경계와 성공 핸들러 사이 만료 경합). `onLinkSuccess`가 만료 무장을 DENIED로 접는다(무장 수명은 JVM 시계, 허가는 DB 시계라 따로 본다).
  - `onFailure`에서 소비되지 않은 실패는 저장된 주체가 있으면 인증 화면 대신 지금 목적지로 보낸다(도달하지 않는 대칭 방어선).
  - 필터 주석 정정.
  - 취소용 `abandon`은 한 번 읽은 무장으로 판정·폐기한다.
  - `abandon` javadoc 호출자 목록을 실제 코드에 맞췄다. 로그아웃은 부르지 않고, 새 해제 준비 덮기는 `supersedePreviousArming`이 한다.
  - 시험 셋을 더했다: 제공자가 보낸 `invalid_request`는 무장을 소비한다, 운영자 `/operator` 목적지, 위조 변형 둘(code만·state만).
  - 재인증 무장 동등 단정, CSRF 속성 지연 초기화, 출처 표기(F2=item-2, F6=item-3, F3=item-4).
  - defer 2건은 deferred 190(미인증 세션의 같은 위조 콜백 위협)·191(재인증 무장의 같은 만료 경합)이다.
  - reject는 CLAUDE.md·원장 갱신(done 전환 몫), 표지 없는 되돌림(Always가 정한 302), 위조 콜백 계측(새 기능), 만료된 다른 대상 무장의 취소 시험(영향 없음)이다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend test --tests 'com.bokrilog.Auth*'` — expected: 전부 통과.
- `backend/gradlew --project-dir backend build` — expected: 통과(Windows 로컬의 루프백 `Bad address`(deferred 183)는 CI 결과로 대신한다).

## Suggested Review Order

**위조 콜백 가드 (F2, item-2)**

- 진입점: 이 세션의 인가 요청에 닿지 못한 실패는 무장을 건드리지 않는다
  [`AuthFlowHandlers.java:1034`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L1034)

- 필터가 스스로 던진 실패만 가려내는 판정, 제공자 오류는 제외
  [`AuthFlowHandlers.java:1300`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L1300)

- Spring private 상수를 리터럴로 든 근거
  [`AuthFlowHandlers.java:143`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L143)

**제공자 일치 소비 (F3, item-4)**

- 연결 무장은 제공자가 일치하는 콜백만 소비
  [`AuthFlowHandlers.java:872`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L872)

- 만료 경합에서도 로그인 분기로 떨어지지 않는 방어선
  [`AuthFlowHandlers.java:590`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L590)

**해제 취소 대상 대조 (F6, item-3)**

- 한 번 읽은 무장으로 대상 대조 후 폐기
  [`UnlinkService.java:246`](../../backend/src/main/java/com/bokrilog/UnlinkService.java#L246)

- 취소 경로가 제공자를 넘긴다, 응답은 언제나 204
  [`AuthSessionController.java:605`](../../backend/src/main/java/com/bokrilog/AuthSessionController.java#L605)

**시험**

- 위조 콜백 여섯 변형 helper
  [`AuthFlowTestBase.java:405`](../../backend/src/test/java/com/bokrilog/AuthFlowTestBase.java#L405)

- 기대가 뒤집힌 F3 시험과 위조 연결 시험
  [`AuthLinkProviderTest.java:334`](../../backend/src/test/java/com/bokrilog/AuthLinkProviderTest.java#L334)
  [`AuthLinkProviderTest.java:374`](../../backend/src/test/java/com/bokrilog/AuthLinkProviderTest.java#L374)

- 위조 재인증과 제공자 invalid_request 소비
  [`AuthReauthenticationTest.java:415`](../../backend/src/test/java/com/bokrilog/AuthReauthenticationTest.java#L415)
  [`AuthReauthenticationTest.java:483`](../../backend/src/test/java/com/bokrilog/AuthReauthenticationTest.java#L483)

- 다른 제공자 취소, 운영자 목적지 유지
  [`AuthUnlinkProviderTest.java:598`](../../backend/src/test/java/com/bokrilog/AuthUnlinkProviderTest.java#L598)
  [`OperatorAccessTest.java:185`](../../backend/src/test/java/com/bokrilog/OperatorAccessTest.java#L185)
