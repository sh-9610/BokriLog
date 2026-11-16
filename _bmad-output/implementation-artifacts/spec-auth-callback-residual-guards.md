---
title: '인증 콜백 잔여 가드 — 로그인 전 세션의 위조 콜백과 재인증 무장 만료 경합'
type: 'bugfix'
created: '2026-11-16'
status: 'done'
baseline_commit: '61517e7a511f9cb264fbad638c4eb720914b18cc'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 인증 흐름 무장 가드 fix(PR #163)가 남긴 두 경로다.
- (deferred 190) 로그인 전 세션에 외부 사이트가 `top.location`으로 `state` 없는(또는 모르는 `state`의) 콜백을 열면, `onFailure` → `converge` → `discardSession`이 진행 중인 인증 전 세션과 그 authorization request를 지운다. 피해자의 로그인 시도를 반복해 취소시킬 수 있다. 같은 브라우저에서 로그인을 두 번 시작한 사용자의 앞 탭 콜백도 뒤 탭 흐름을 지운다.
- (deferred 191) 흐름 경계가 재인증 무장을 살아 있다고 판정한 뒤, 성공 핸들러 전에 그 무장이 만료되면 `ReauthenticationFlow.consume`이 `null`을 낸다. 그러면 콜백이 로그인 분기(`establish`)로 떨어져 인증된 세션에 검증된 다른 계정이 실릴 수 있다.

**Approach:**
- (190) 이 세션의 인가 요청에 닿지 못한 실패(`missedAuthorizationRequest`)는 로그인 전 세션도 지우지 않는다. 응답은 지금처럼 진입 화면과 실패 표지다.
- (191) 성공 핸들러가 수명이 지난 재인증 무장도 먼저 읽고, 연결이 소비하지 않았는데 재인증 무장이 만료돼 있으면 재인증 실패(`reauth-failed`)로 접는다. 로그인 분기로 가지 않는다. PR #163이 연결 쪽에 둔 방어선과 같은 모양이다.

## Boundaries & Constraints

**Always:**
- (190) 가드에 걸린 로그인 전 실패의 응답(302 진입 화면 + 실패 표지)은 지금과 같다. 바뀌는 것은 세션 행과 그 안의 인가 요청을 남긴다는 것 하나다. 그래서 이어지는 정상 콜백이 로그인을 마친다.
- (190) 이 세션의 인가 요청을 소비한 실패(사용자 취소·제공자 오류·토큰 검증 실패·세션 개설 거부)는 지금처럼 그 흐름의 세션을 지운다("실패한 흐름의 세션은 남기지 않는다").
- (191) 만료 재인증 실패는 기존 재인증 실패와 같은 수렴(`convergeReauthentication` — 저장된 주체를 무장의 사용자로 다시 쓰고 무장의 `returnPath`에 `reauth-failed`)이다. 재인증 시각을 기록하지 않고 `reauth_required` 표시도 풀지 않는다.
- 흐름 선택 판정(연결·재인증·로그인 중 무엇이 이 콜백을 받는가)은 시험할 수 있는 순수 함수로 둔다. 만료 경합은 HTTP 왕복으로 만들 수 없으므로 그 함수의 단위 시험이 191을 덮는다(`AuthFlowHandlersRotationTest`처럼 Spring 컨텍스트 없이).
- 시험의 code·state·토큰 모양 값은 리터럴로 쓰지 않는다(실행 시점 UUID).

**Ask First:**
- 기존 시험의 기대를 이 Intent 밖에서 바꿔야 하는 경우.
  - 예외(승인 범위): 로그인 전 세션에 **인가 요청에 닿지 못한** 콜백(`state` 없음·어긋남)을 보내고 "세션 행이 0"을 단정하던 시험(예: `AuthTokenValidationTest.state가_어긋나면_유효한_code로도_로그인되지_않는다`)은 "세션 행이 남고 주체는 미인증"으로 바꾼다.
- `missedAuthorizationRequest`의 판정 범위(code 둘과 요청 모양)를 바꿔야 하는 경우.

**Never:**
- 인증된 세션의 F2·F3·F6 가드(PR #163)의 판정과 응답을 바꾸지 않는다.
- 흐름 세션 수명(`FLOW_SESSION_TIMEOUT_SECONDS`)·쿠키·필터 순서·전역 CSRF를 바꾸지 않는다.
- 재인증 신선도(Epic 1 item-6)·확인 hash(item-7)는 다루지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 190 위조 콜백 | 로그인 전 세션 + 진행 중 Google 인가, 외부가 `state` 없는(또는 모르는 `state`의) Google 콜백을 연다 | 302 진입 화면 + 실패 표지. 세션 행이 남고, 이어지는 정상 콜백이 로그인을 완료한다 | 계정·매핑 0건 추가 |
| 190 두 번 시작 | 같은 브라우저에서 로그인 시작 두 번, 앞 흐름의 `state`로 콜백 | 앞 콜백은 실패 표지. 뒤 흐름의 콜백은 로그인을 완료한다 | — |
| 190 소비한 실패 유지 | 로그인 전 세션 + 유효한 `state`의 `access_denied`·`server_error`, 또는 토큰 검증 실패 | 지금처럼 세션 행이 지워지고 취소·실패 표지 | — |
| 191 만료 경합 | 재인증 무장이 성공 핸들러 시점에 만료, 연결 무장 없음 | 재인증 실패 수렴(`returnPath?auth=reauth-failed`), 세션 주체는 무장의 사용자, 로그인 분기 미진입 | 재인증 시각 미기록 |
| 191 살아 있는 무장 | 재인증 무장이 살아 있음 | 지금처럼 재인증 분기 | — |
| 191 무장 없음 | 연결·재인증 무장 모두 없음(로그인 전 세션) | 지금처럼 로그인 분기 | — |

</frozen-after-approval>

## Code Map

- `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java`
  - `onSuccess`(:590~692)
    - 연결 쪽 방어선: `storedLink`는 `peekIncludingExpired`로 읽는다(:609, :616~632).
    - 재인증 분기: `ReauthenticationFlow.consume`(:639)이 만료 무장에 `null`을 내고, 그다음이 로그인 분기 `establish`(:645)다(191).
    - 흐름 선택 판정을 static package-private 함수로 뽑을 자리다.
  - `onFailure`(:1034~1131): 인증된 세션의 F2 가드(:1038~1067), 마지막 `converge(request, response, marker(exception))`(:1130)(190).
  - `converge`(:1139~)·`discardSession`(:1059~ 근처)·`destroySession`: 로그인 전 세션을 지우는 본체.
  - `missedAuthorizationRequest`(:1300): 재사용할 판정.
  - `convergeReauthentication`(:844~855): 191의 수렴.
- `backend/src/main/java/com/bokrilog/SessionArming.java:134` — `consume`은 `peek`(만료면 버리고 `null`)과 `discard`다.
- 시험:
  - `backend/src/test/java/com/bokrilog/AuthTokenValidationTest.java`: :129 기대가 바뀌는 시험, :143·:157 미인증 상태를 단정하는 시험.
  - `AuthFlowTestBase.java`: `forgedCallbacks`(위조 여섯 변형), `startAuthorization`·`callback`·`sessionCount`.
  - `AuthFlowHandlersRotationTest.java`: Spring 없이 핸들러 판정을 보는 단위 시험 선례.
  - 로그인 전 흐름의 다른 `sessionCount()).isZero()` 단정(Kakao·Naver·Link 등)은 대부분 소비한 실패라 그대로다. 인가 요청에 닿지 못한 입력만 바뀐다.
- 걸린 열린 회고 액션:
  - `epic-1-retro-item-6`(주제 — 재인증 시각·신선도, `AuthFlowHandlers`): 지나간다.
  - `epic-1-retro-item-7`(주제 — 확인 hash): 지나간다.
  - `epic-3-retro-item-39`(과정 — 시험의 토큰 모양 값): 따른다(Always).
  - `epic-1-retro-item-8`·`epic-3-retro-item-38`(과정 — defer 판정): done 전환에서 190·191을 해소로 판정한다.

## Tasks & Acceptance

**Execution:**
- [x] `AuthFlowHandlers.java`
  - `onSuccess`의 흐름 선택을 static package-private 함수로 뽑는다(입력은 저장된 연결·재인증 무장(만료 포함), 콜백 제공자, 시각. 출력은 연결·재인증·만료 재인증 실패·로그인 중 하나).
  - 만료 재인증이면 그 무장을 버리고 `convergeReauthentication`(`reauth-failed`)으로 접는다.
- [x] `AuthFlowHandlers.java` — `onFailure`의 마지막 수렴에서 `missedAuthorizationRequest`면 세션을 지우지 않는다(`clearContext` + 진입 화면 302 + 표지). 근거 주석은 F2 가드와 같다(교차 사이트 최상위 내비게이션).
- [x] `AuthTokenValidationTest.java`·필요한 Kakao·Naver 시험 — Ask First 예외 범위의 기대를 바꾼다. 190 위조(`forgedCallbacks`)·두 번 시작 시험을 더하고, 각각 이어지는 정상 콜백이 로그인을 완료하는 것까지 단정한다.
- [x] 단위 시험(새 파일 또는 `AuthFlowHandlersRotationTest` 옆) — 흐름 선택 함수로 I/O 표의 191 세 행을 덮는다.

**Acceptance Criteria:**
- Given 수정, when `backend/gradlew --project-dir backend build`를 돌리면, then Ask First 예외 밖의 기존 시험 기대는 바뀌지 않고 모두 통과한다.
- Given 흐름 선택 함수, when 연결·재인증 무장의 모든 조합(없음·살아 있음·만료 × 제공자 일치·불일치)을 넣으면, then 인증된 세션의 무장이 있었던 조합은 어느 것도 로그인 분기를 내지 않는다.

## Spec Change Log

- 2026-11-16 구현: 흐름 선택 함수 `selectCallbackFlow`(sealed `CallbackFlow` — `Link`·`Reauthentication`·`ExpiredReauthentication`·`Login`)는 SPEC대로 두 무장을 만료 포함으로 받는다. 그래서 연결 무장도 만료 포함으로 `callbackLinkFlow`에 넘긴다(흐름 경계 `authenticationFlowFilter`와 같은 판정). 모든 무장이 살아 있으면 예전과 값이 같다. 달라지는 것은 경계 판정과 성공 핸들러 사이의 만료 경합 창뿐이고, 그때도 경계가 고른 흐름을 잇는다. 예를 들어 살아 있는 연결 naver와 경계 뒤 만료된 재인증 google에 google 콜백이 오면, 예전은 `link-failed`였고 지금은 `reauth-failed`이며 연결 무장이 남는다.
- 2026-11-16 1차 리뷰(blind-hunter·edge-case-hunter·verification-gap) patch 루프, 14건:
  - 판정 시각 하나(`now`를 하위 판정까지 넘긴다).
  - 로그인 분기를 `onLogin`으로 뽑아 네 case를 대칭으로 했다(고르지 않은 무장은 만료일 때만 버리고, `Login`은 둘 다 버린다).
  - 시험을 보강했다.
    - 성공 핸들러 배선 시험: `ExpiredReauthentication`을 Spring 컨텍스트에서 직접 실행한다.
    - 연결 쪽 거울 시험.
    - 전수 조합 시험: 독립 기대 함수, 무장이 있는 조합 96건.
    - 수명 상수 분리와 전제 단정, 수명 경계 1ns 시험.
    - 190 위조 응답에 쿠키 만료 지시가 없음을 단정한다.
    - 로그인 전 `invalid_request` 두 모양을 가르는 시험.
  - 190 가드 주석: 완료 확인 세션 보존, 흐름 세션 유휴 연장의 수용 근거.
  - 낡은 javadoc 정정.
  - 항목 10(토큰 검증 실패 세션 삭제)은 기존 `assertRejected` 11건이 덮는다.
  - blind-hunter가 "만료 연결 무장도 선택에 넘긴 것은 frozen 밖 해석"이라고 짚었다. F3 판정 함수(`callbackLinkFlow`)와 F2·F3·F6 응답은 바뀌지 않았고, 흐름 경계와 같은 판정이며, 결과가 다른 것은 경합 창뿐이라 Never 위반이 아니라고 판단했다. 그 규칙은 거울 시험으로 고정했다.
  - defer 1건은 deferred 192(`onFailure`의 같은 만료 경합 — 표지만 잃는다)다. reject는 관측 계측(새 기능)이다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend test --tests 'com.bokrilog.Auth*' --tests 'com.bokrilog.OperatorAccessTest'` — expected: 전부 통과.
- `backend/gradlew --project-dir backend build` — expected: 통과(Windows 로컬의 루프백 `Bad address`(deferred 183)는 CI 결과로 대신한다).

## Suggested Review Order

**191 재인증 만료 경합**

- 진입점: 흐름 선택을 순수 함수로, 일치하는 무장이 수명과 무관하게 이긴다
  [`AuthFlowHandlers.java:940`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L940)

- 네 case 대칭 분기와 판정 시각 하나
  [`AuthFlowHandlers.java:594`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L594)

- 만료 재인증을 재인증 실패로 접는 자리
  [`AuthFlowHandlers.java:644`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L644)

**190 로그인 전 위조 콜백**

- 인가 요청에 닿지 못한 실패는 로그인 전 세션도 지우지 않는다
  [`AuthFlowHandlers.java:1160`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L1160)

**시험**

- 성공 핸들러를 직접 실행하는 배선 시험
  [`AuthReauthenticationTest.java:594`](../../backend/src/test/java/com/bokrilog/AuthReauthenticationTest.java#L594)

- 독립 기대 함수로 전수 조합 단정
  [`AuthCallbackFlowSelectionTest.java:`](../../backend/src/test/java/com/bokrilog/AuthCallbackFlowSelectionTest.java#L)

- 위조 콜백 뒤 로그인 완료, 기대가 바뀐 시험
  [`AuthTokenValidationTest.java:152`](../../backend/src/test/java/com/bokrilog/AuthTokenValidationTest.java#L152)
  [`AuthTokenValidationTest.java:129`](../../backend/src/test/java/com/bokrilog/AuthTokenValidationTest.java#L129)
