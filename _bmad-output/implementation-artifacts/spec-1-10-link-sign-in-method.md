---
title: 'Story 1.10 — 새 로그인 수단을 현재 계정에 연결하기'
type: 'feature'
created: '2026-09-24'
status: 'done'
review_loop_iteration: 0
baseline_commit: '9c7b7fb1ad3de9e9bbee614ae66bf530b800607a'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-8-reauthentication-session-rotation.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-9-reauthentication-action-grant.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 사용자는 로그인한 제공자 하나로만 계정에 닿을 수 있다. 그 제공자를 잃으면 계정도 잃는다. 1.8의 재인증과 1.9의 허가는 만들어졌지만 **소비자가 하나도 없어** 두 계약이 실제로 작동하는지 어느 층도 보지 않는다. 화면도 없다 — `/onboarding`·`/dashboard` 둘뿐이라 사용자가 자기 로그인 수단을 볼 자리가 없다.

**Approach:** 설정 화면 하나를 새 경로로 만들고, 거기서 시작하는 연결 흐름이 1.8의 재인증과 1.9의 허가를 **처음으로 실제 소비한다**. 새 제공자의 OIDC 왕복은 로그인도 가입도 아닌 **연결 목적**으로 고정되고, 최종 커밋에서 허가 소비와 `identity` 매핑 추가가 한 트랜잭션으로 끝난다.

## Boundaries & Constraints

**Always:**
- 연결은 **세 단계가 순서대로** 성립해야 한다 — 기존 수단으로 재인증(1.8) → 연결 작업용 허가 발급(1.9) → 대상 제공자 OIDC 왕복. 앞 단계가 없으면 뒷 단계를 시작하지 않는다.
- 대상 제공자의 왕복은 **연결 흐름이 무장했을 때만** 열린다. 무장 없는 인증된 세션의 인가 시작은 지금처럼 거절된다. 연결 콜백이 로그인·가입 분기로 새지 않는다.
- 최종 커밋은 **허가 소비와 `linkSocialIdentity`를 같은 트랜잭션**에서 한다. 허가만 소비되고 매핑이 없는 상태도, 매핑만 생기고 허가가 남는 상태도 만들지 않는다.
- 유일성은 스키마가 최종 방어다 — `(issuer, subject)` 전역 유일과 `(user_id, provider)` 계정당 하나. 이메일·표시명으로 병합하지 않으며 그 값을 읽지도 않는다.
- 거부는 **하나의 값**이다. 이미 내 계정에 연결됨·다른 계정에 연결됨·허가 만료·세션 불일치·삭제 상태가 응답으로 구분되지 않는다. 다른 계정의 존재나 소유자를 드러내지 않는다.
- 연결 상태 조회는 **제공자 이름만** 돌려준다. `issuer`·`subject`·이메일·토큰·연결 시각은 응답에 싣지 않는다.
- 같은 `operation_id` 재호출은 저장된 같은 결과를 돌려주고 매핑을 다시 만들지 않는다(1.9의 멱등이 그대로 권위다).
- 비성공 종료는 **기존 로그인 수단과 세션을 보존한다**. 임시 code·토큰·PKCE verifier·연결 흐름을 폐기하고 자동 재시도·계정 전환·새 계정 생성을 하지 않는다.
- 새 경로 `/settings`를 더할 때 **서버 허용 목록·Nginx location·`App.tsx` 경로 맵·세션 목적지 집합을 함께** 고친다. 한 곳만 고치면 그 경로는 404이거나 서버가 모르는 목적지가 된다.
- 연결 성공 뒤 **세션 ID를 다시 회전한다**(`SECURITY-ACTION-1.0`, 1.9의 `ConsumedRequiringSessionRotation`이 넘긴 의무).

**Ask First:**
- `identity` 공개 API에 **연결된 제공자 목록 조회**를 더하는 것. AC-1은 그것 없이 성립하지 않고, 루트 패키지가 `identity_social_link`를 직접 읽는 길은 `ModuleBoundaryTest`가 막는다.
- 1.9 포트가 **귀속 세션 id를 호출자가 제시**할 수 있게 넓히는 것. 연결 왕복은 콜백에서 세션이 한 번 회전하므로 발급 시각의 세션 id와 소비 시각의 live id가 반드시 다르다.
- 새 경로 이름(`/settings`)과 화면이 노출하는 정보의 범위.

**Never:**
- 로그인 수단 **해제**(1.11), 마지막 수단 보호(1.11), 계정 간 병합·이전.
- 이미 연결된 제공자에 중복 연결 동작을 제공하는 것.
- 재인증이 만료됐을 때 **자동으로** 재인증을 시작하거나 연결을 이어 하는 것. 사용자가 명시적으로 다시 시작한다.
- 연결 흐름을 1.8의 재인증 무장(`ReauthenticationFlow.Armed`)에 섞는 것. 재인증은 "계정을 만들지도 바꾸지도 않는다"가 계약이고 연결은 그 반대다.
- `authorizeHttpRequests`를 전역으로 바꾸는 것. Story 1.2·1.3이 고정한 404·405가 401·302로 뒤집힌다.
- 새 마이그레이션. `identity_social_link`와 `action_grant`가 이미 있고 이 스토리는 컬럼을 늘리지 않는다.
- 새 런타임 의존.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 연결 상태 조회 | 인증된 세션 | 세 제공자의 연결 여부만 | 미인증은 공통 거부 |
| 연결 시작(재인증 신선) | 미연결 제공자 + 5분 내 재인증 | 허가 발급 + 연결 흐름 무장, 204 | N/A |
| 연결 시작(재인증 없음·오래됨) | 재인증 시각이 없거나 창 밖 | 무장하지 않음, 거부 하나 | 화면이 재인증을 안내 |
| 연결 시작(이미 연결된 제공자) | 대상이 이미 내 계정에 연결됨 | 거부 하나 | 화면이 연결 버튼을 주지 않는다 |
| 정상 연결 | 무장 + 대상 제공자 콜백 검증 성공 + 미연결 식별자 | 허가 소비와 매핑 추가가 1회 커밋, 세션 회전, `/settings` 복귀 | N/A |
| 다른 계정에 연결된 식별자 | `(issuer, subject)`가 남의 계정 것 | 매핑 무변경(양쪽 다), 거부 하나 | 상대 계정 존재·소유자 미노출 |
| 내 계정에 이미 있는 제공자 | `(user_id, provider)` 충돌 | 매핑 무변경, 같은 거부 | 위와 구분되지 않음 |
| 같은 제공자 다른 subject | 이미 그 제공자로 연결된 계정 | 같은 거부(계정당 제공자 하나) | 〃 |
| 제공자 바꿔치기 | 무장은 naver인데 kakao 콜백 | 실패, 흐름 폐기, 매핑 무변경 | 〃 |
| 허가 만료·이미 사용됨 | 무장은 살아 있으나 허가가 죽음 | 매핑 무변경 | 〃 |
| 흐름 도중 세션 교체·로그아웃·만료 | 세션이 사라지거나 바뀜 | 매핑 무변경, 허가 미소비 | 상태 변경 0건 |
| 흐름 도중 삭제 전환 | `DELETION_IN_PROGRESS` | 매핑 무변경 | `AccessDecision.DENIED` 하나 |
| 사용자 취소 | 콜백 `error=access_denied` | 흐름만 폐기, 허가·세션·매핑 무변경 | `/settings`에 취소 표지 |
| 응답 유실 후 재전송 | 같은 `operation_id` | 저장된 같은 결과, 매핑 재생성 없음 | N/A |
| 두 계정이 같은 식별자를 동시 연결 | 동시 요청 | 정확히 하나만 성공 | 나머지는 같은 거부 |
| 연결 뒤 새 수단 로그인 | 새 제공자로 일반 로그인 | 같은 내부 사용자 | N/A |
| 연결 뒤 계정 삭제 | `erasePayload` | 두 매핑 모두 사라진다 | N/A |
| CSRF·교차 출처 | 토큰 없음 또는 다른 `Origin` | 403 | 기존 계약 |
| 미지 제공자 | `/api/auth/link/<미등록 이름>` | 404 | 아는 제공자 목록·개수 미노출 |

</frozen-after-approval>

## Code Map

**조사가 정한 사실 (2026-09-24)**

- **연결 목록 조회 API가 없다.** `AccountLifecycleApi`는 `(issuer,subject)→userId` 한 방향뿐이고 역방향이 없다(`AccountLifecycleApi.java:17-87`). `AuthSessionController.java:224-226`은 "연결된 제공자 목록도 보지 않는다"를 1.9의 설계 계약으로 적어 두었다 — 그 자리는 그대로 두고 AC-1이 요구하는 조회를 **새 공개 메서드**로 연다. 직접 읽기는 `ModuleBoundaryTest`(`:45-58`)가 막는다.
- **연결의 원자성은 이미 `identity`가 갖고 있다.** `insertSocialLinkIfActive`(`identity/internal/IdentityAccountRepository.java:94-107`)가 `INSERT ... SELECT ... WHERE state='ACTIVE' ON CONFLICT DO NOTHING` 한 문장으로 상태 술어와 유일성 충돌을 함께 판정하고 0/1행으로 답한다. `AccountLifecycleService.linkSocialIdentity`(`internal/AccountLifecycleService.java:61-105`)는 `FOR UPDATE` 잠금 뒤 그 문장을 쓰며 **거부가 호출자 트랜잭션을 오염시키지 않는다**(`:83-89` 주석). 이 스토리는 그 위에 얹기만 한다.
- **동시성·거부 시험이 이미 넓다.** `identity/AccountConcurrencyTest.java:317-340`이 같은 `(issuer,subject)` 동시 연결에서 하나만 성공함을, `AccountAccessDecisionTest.java:242-283`이 계정당 제공자 하나와 삭제된 계정 재연결 거부를 이미 고정한다. 1.10은 그 축을 복제하지 않고 **HTTP·허가 층에서만** 본다.
- **흐름 경계가 갈라야 할 경우가 하나 는다.** `AuthFlowHandlers.authenticationFlowFilter`(`:215-323`)는 지금 인가 시작·콜백 × 인증 여부 × 재인증 무장으로 갈린다. `onSuccess`(`:443-459`)의 분기점은 `ReauthenticationFlow.consume(request)` 한 줄이다. 연결은 **세 번째 흐름**이며 재인증 무장에 섞지 않는다.
- **세션은 콜백에서 반드시 한 번 회전한다.** 배선은 `AuthSecurityConfig.java:819-820` 하나이고 Spring Security 필터가 성공 핸들러 **직전에** 실행한다. 그래서 발급 시각의 세션 id와 소비 시각의 live id는 **언제나 다르다** — 1.9 포트가 live id로만 귀속을 판정하면 연결은 구조적으로 성립할 수 없다.
- **1.9 포트의 모양.** `Optional<String> issue(HttpSession, UUID, String command, String target, String confirmation)`(`ActionGrantService.java:186-191`), `Consumption consume(ConsumptionRequest, Supplier<String>)`(`:262`), `record ConsumptionRequest(HttpSession, UUID, String grantReference, String command, String target, String confirmation, String operationId)`(`:114-121`), `sealed interface Consumption`(`:80`)의 변형 `ConsumedRequiringSessionRotation(String)`(`:92`)·`Denied()`(`:95`).
- **새 경로 하나가 여덟 자리를 건드린다.** `App.tsx:24-29`, `content/auth.ts:82,84`·`:99`(`RETURN_DESTINATIONS`), `useSessionState.ts:33`·`:83-86`·`:89`, `deploy/nginx/bokrilog.conf:227`, `AuthFlowHandlers.java:78,81`·`:90`(`ALLOWED_DESTINATIONS`), `verify-same-origin.sh:1524-1526`·`:1542-1545`, `e2e/auth-entry.spec.ts:479,509`.
- **재사용 가능한 화면 부품이 이미 있다.** `ReauthPanel`(`components/ReauthPanel.tsx:30-45`, 순수 표현), `ProviderMark`(`:34-42`, 닫힌 맵), `SignOutButton`(`:6-23`). 설정 화면 전용 토큰은 없고 `history-row`(`tokens.css:223-225`)가 연결 목록 행에 가장 가깝다.
- **시험 stub 관용.** `frontend/src/test/setup.ts`가 `(메서드, URL)` 화이트리스트다 — `stubSessionRequest`(`:62`)·`stubLogoutRequest`(`:73`)·`stubReauthenticationRequest`(`:84`), 그 외는 전역 거부(`:169-181`). 새 엔드포인트마다 같은 모양의 헬퍼를 더한다.

**새로 만드는 자리**

- `backend/src/main/java/com/bokrilog/identity/AccountLifecycleApi.java` -- 읽기 전용 조회 하나를 더한다. 반환은 **제공자 이름 집합**이며 `issuer`·`subject`·시각을 담지 않는다. 미존재·삭제 계정은 빈 집합으로 접힌다(존재를 드러내지 않는다).
- `backend/src/main/java/com/bokrilog/identity/internal/` -- 그 조회의 구현과 저장소 SQL 한 줄. `ACTIVE` 계정만 답한다.
- `backend/src/main/java/com/bokrilog/LinkFlow.java` -- 연결 흐름. 세션 속성 하나에 `(targetProvider, userId, returnPath, grantReference, operationId, boundSessionId, armedAt)`를 담는다. `ReauthenticationFlow`와 같은 모양의 `arm`·`peek`·`consume`·`discard`와 수명 상수.
- `backend/src/main/java/com/bokrilog/LinkService.java` -- 연결의 판정과 실행. 시작(재인증 신선도 → 허가 발급 → 무장)과 완료(허가 소비 트랜잭션 안에서 `linkSocialIdentity`)를 소유한다. `identity` 공개 API와 `ActionGrantService`만 부른다.
- `frontend/src/screens/SettingsScreen.tsx` (+ `.module.css`) -- 로그인 수단 설정 화면. 세 제공자를 동등한 위계로 보이고 연결된 것에는 연결 동작을 주지 않는다. `useSessionGate('settings')`를 쓰고 `ReauthPanel`·`ProviderMark`를 재사용한다.
- `frontend/src/session/linkProvider.ts` -- 연결 시작 호출. `reauthenticate.ts`와 같은 모양(CSRF 헤더·`same-origin`·시한·204 성공·실패 시 표지 이동).

**기존 파일에서 넓힐 자리**

- `AuthSessionController` -- 엔드포인트 둘. 연결 상태 조회(`GET`)와 연결 시작(`POST .../{provider}`, 404/403/204). 기존 관용대로 `sendError`로 공통 오류 계약을 탄다.
- `AuthSecurityConfig` -- 경로 상수 둘과 `isLinkStart` 판정. `authenticationFlowFilter`가 연결 무장을 볼 수 있어야 하므로 `isAuthenticationFlow`에 연결 시작을 넣는다. `authorizeHttpRequests`는 그대로 `permitAll`이다 — 연결 표면은 `/api/auth/` 아래라 CSRF·`Origin`이 이미 걸리고, 주체 없음은 컨트롤러가 403으로 접는다.
- `AuthFlowHandlers` -- 경로 상수 `/settings`와 `ALLOWED_DESTINATIONS`, 흐름 경계의 연결 분기, `onSuccess`의 세 번째 경로, 연결 표지 둘.
- `ActionGrantService` -- 귀속 세션 id를 호출자가 제시할 수 있게 넓힌다. 기본은 지금처럼 live id이고, 연결 경로만 **현재 세션의 흐름 기록이 들고 있던 값**을 제시한다. javadoc과 시험이 "그 값은 클라이언트가 아니라 서버 측 흐름에서만 온다"를 고정한다.
- 프런트엔드 경로 맵·목적지 집합·카피·Nginx 허용 목록·검증 스크립트·e2e 경로 배열 -- 위 조사의 여덟 자리.

## Tasks & Acceptance

**Execution:**

- [x] `backend/src/main/java/com/bokrilog/identity/` -- 연결된 제공자 이름 집합을 돌려주는 읽기 전용 공개 메서드와 그 구현·저장소 SQL을 더한다. `ACTIVE` 계정만 답하고 미존재·삭제는 빈 집합이다. 외부 식별자·시각을 반환에 담지 않는다. -- AC-1. 이것 없이는 화면이 연결 여부를 알 수 없고 직접 읽기는 모듈 경계가 막는다.
- [x] `backend/src/main/java/com/bokrilog/LinkFlow.java` -- 연결 무장 record와 `arm`·`peek`·`consume`·`discard`, 수명 상수. 재인증 무장과 **별도 세션 속성**이다. -- 재인증은 계정을 바꾸지 않는다는 1.8의 계약을 섞지 않는다.
- [x] `backend/src/main/java/com/bokrilog/ActionGrantService.java` -- 귀속 세션 id를 호출자가 제시할 수 있게 넓히고, 그 값의 출처가 서버 측 흐름 기록이어야 한다는 계약을 javadoc에 적는다. 기본 경로의 동작은 바뀌지 않는다. -- 콜백이 세션을 반드시 회전시키므로 live id만으로는 연결이 성립할 수 없다.
- [x] `backend/src/main/java/com/bokrilog/LinkService.java` -- 시작(재인증 신선도 → 허가 발급 → 무장)과 완료(허가 소비 트랜잭션 안에서 `linkSocialIdentity`)를 소유한다. 거부는 하나의 값으로 접는다. -- AC-2~AC-5. `identity`를 부르는 자리를 한 곳으로 모은다.
- [x] `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` -- `/settings` 상수와 `ALLOWED_DESTINATIONS` 편입, 흐름 경계의 연결 분기(무장 있으면 통과, 만료면 표지와 함께 복귀), `onSuccess`의 세 번째 경로, 성공 시 세션 재회전, 연결 표지 둘. -- AC-3·AC-6. 콜백이 로그인 분기로 새면 새 계정이 생긴다.
- [x] `backend/src/main/java/com/bokrilog/AuthSessionController.java` + `AuthSecurityConfig.java` -- 엔드포인트 둘과 경로 상수·판정, `isAuthenticationFlow` 편입. `authorizeHttpRequests`는 그대로 둔다. -- AC-1·AC-2.
- [x] `backend/src/test/java/com/bokrilog/AuthLinkProviderTest.java` -- 정상 연결, 재인증 없음·오래됨, 이미 연결된 제공자, 다른 계정의 식별자, 같은 제공자 다른 subject, 제공자 바꿔치기, 허가 만료·소비됨, 세션 교체·로그아웃·삭제 전환, 취소, 같은 `operation_id` 재전송, 연결 뒤 새 수단 로그인, CSRF·교차 출처·미지 제공자. `identity`가 이미 고정한 유일성·동시성 축은 복제하지 않는다. -- AC-2~AC-7의 단일 소유자.
- [x] `backend/src/test/java/com/bokrilog/identity/` -- 연결 목록 조회의 축(연결 없음·하나·셋, 타 사용자 미노출, 삭제 진행·완료에서 빈 집합)을 identity 시험 관용대로 더한다. -- AC-1.
- [x] `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java` -- 연결 시작·연결 왕복 헬퍼와 연결 목록 조회 헬퍼를 기존 자리에 더한다. 새 `@DynamicPropertySource`를 만들지 않는다. -- 컨텍스트 캐시를 가르지 않는다.
- [x] `frontend/src/content/auth.ts` -- `/settings` 경로 상수와 복귀 목적지 편입, 연결 엔드포인트 둘, 연결 표지 둘을 닫힌 집합 세 곳에 함께, 설정 화면 카피와 카피 표류 방지 문자열. 고지와 `복리 계획 비교`를 인접 배치한다. -- 표지 집합이 한 목록이어야 조용히 무시되는 값이 생기지 않는다.
- [x] `frontend/src/session/useSessionState.ts` + `frontend/src/session/linkProvider.ts` -- 목적지 유니언·경로 맵에 `settings`를 더하고, 연결 시작 호출을 `reauthenticate.ts`와 같은 모양으로 만든다. -- 서버가 고르는 목적지 종류와 화면의 경로 맵이 같아야 한다.
- [x] `frontend/src/screens/SettingsScreen.tsx` (+ `.module.css`) + `frontend/src/App.tsx` -- 설정 화면과 경로 맵 편입. 세 제공자를 동등한 위계로 보이고 연결된 것에는 연결 동작을 주지 않으며 외부 식별자를 노출하지 않는다. 기존 토큰만 쓴다. -- AC-1·AC-6.
- [x] `frontend/src/screens/SettingsScreen.test.tsx` + `App.test.tsx` + `frontend/src/test/setup.ts` -- 게이트 상태별 렌더, 연결 버튼의 유무, 표지별 문장, 시작 호출의 토큰 헤더·중복 제출 차단, 저장소 미사용, 경로 맵. 새 엔드포인트용 stub 헬퍼를 더한다. -- 화면 축은 이 층이 소유한다.
- [x] `deploy/nginx/bokrilog.conf` + `deploy/compose/verify-same-origin.sh` -- 허용 목록 정규식에 `settings`를 더하고, 연결 왕복 절(재인증 → 연결 시작 204 → 대상 제공자 왕복 → 매핑 2행 → 세션 회전 → `/settings` 복귀)과 거부 탐침을 더한다. 완주 가드 형태를 따르고 `set -o pipefail`에서 `생산자 | grep -q`를 쓰지 않는다. -- HTTP 층에서 연결 계약을 보는 유일한 자리이자 1.8·1.9가 실제로 작동하는지 보는 첫 층이다.
- [x] `frontend/e2e/auth-entry.spec.ts` -- 로그인 상태 경로 배열에 `/settings`를 더한다(미인증 접근 축). 게이트 안쪽 왕복은 스크립트가 소유하므로 여기서 늘리지 않는다. -- 기존 축을 줄이지 않는다.
- [x] `DEVELOPMENT.md` + `_bmad-output/implementation-artifacts/epic-1-context.md` -- 연결 표면·새 경로·세션 회전 의무의 소비 지점과 `## Goal`의 다음 착수 지점(1.11)을 적는다. `CLAUDE.md`는 건드리지 않는다. -- 같은 사실이 두 곳에서 갈리지 않게 한다.

**Acceptance Criteria:**

- Given `develop`의 Story 1.1~1.9 계약, when 이 스토리의 변경을 얹으면, then `GET /api/nope` 404·`POST /api/status` 405·`POST /api/auth/session` 403·반복 로그아웃 403·오류 본문 전문 동등성·보안 헤더·캐시 헤더·세 제공자 로그인 왕복·재인증 왕복이 모두 그대로 통과한다.
- Given 연결에 성공한 계정, when `identity_social_link`를 검사하면, then 그 계정의 행이 2개이고 `provider`가 서로 다르며 `user_id`가 같다.
- Given 연결 성공, when 새 제공자로 일반 로그인하면, then 같은 내부 `user_id`의 세션이 열리고 계정이 늘지 않는다.
- Given 연결된 계정, when `erasePayload`가 실행되면, then 두 매핑이 모두 사라지고 다른 사용자의 매핑은 남는다.
- Given 연결이 거부되는 모든 경우, when 호출자가 응답을 관찰하면, then 이미 연결됨·다른 계정·허가 만료·세션 불일치·삭제 상태가 서로 구분되지 않는다.
- Given 설정 화면, when 키보드만으로 조작하면, then 세 제공자 항목이 문서 순서로 `Tab` 도달되고 조작 대상은 최소 44×44px이며 axe WCAG 2.2 AA 위반이 0건이고 320 CSS px에서 넘치지 않는다.
- Given 브라우저 경계, when 연결 왕복 전후를 검사하면, then `localStorage`·`sessionStorage`·IndexedDB 항목이 0건이고 외부 origin 요청이 0건이며 `issuer`·`subject`·허가 참조·세션 ID가 응답·로그 어디에도 남지 않는다.
- Given 새 경로 `/settings`, when Nginx·`App.tsx`·서버 허용 목록·세션 목적지 집합을 비교하면, then 네 곳이 같은 집합을 갖는다.

## Spec Change Log

- 2026-09-25 첫 구현. frozen 블록은 바꾸지 않았고 설계 결정도 뒤집지 않았다. 아래 아홉은 구현 중에 확정한 것이고 마지막 둘은 변이 확인과 전량 검증 결과다.

  - **설정 화면의 게이트 규칙을 하나 넓혔다.** `useSessionGate(expected)`는 서버가 고른 `destination`과 다르면 그 목적지로 문서를 다시 요청한다. 그 규칙을 그대로 두면 `/settings`에 도달할 방법이 **없다** — 로그인 뒤의 `destination`은 온보딩이거나 대시보드이고, 설정 화면을 열면 곧바로 대시보드로 밀려나며 연결 왕복이 돌아오는 자리도 같은 이유로 사라진다. 그래서 규칙을 성질로 갈랐다: 서버가 고르는 목적지는 "로그인 뒤 착지할 화면"이고 설정은 **사용자가 스스로 여는 자리**이므로, 설정 화면을 밀어내는 것은 온보딩 하나뿐이다(아직 온보딩을 지나지 않은 계정에는 그것이 강제 목적지다). 온보딩·대시보드의 기존 판정은 한 글자도 바뀌지 않았고 `staysOn` 한 함수가 그 차이를 든다.
  - **연결 흐름의 복귀 경로는 서버 상수다.** 재인증은 세션이 가진 확정 목적지를 붙들어 두지만(1.8), 연결 조작이 사는 화면은 하나뿐이므로 `AuthFlowHandlers.SETTINGS_PATH` 리터럴이 그 값이다. 클라이언트가 목적지를 보내지 않는다는 계약은 두 흐름에서 같은 방식으로 성립한다.
  - **본인 확인 버튼이 설정 화면에 있다.** 없으면 이 기능이 **한 번도 성립하지 않는다** — 일반 로그인은 재인증 시각을 남기지 않으므로(1.8은 `recordAuthentication`을 재인증 성공에서만 부른다) 방금 로그인한 사용자의 첫 연결 시도는 언제나 거부된다. Design Notes의 "두 클릭"이 성립하려면 사용자가 재인증을 **시작할 자리**가 그 화면에 있어야 하고, 그래서 연결 목록 아래에 제공자 버튼 셋을 둔다. 자동으로 이어 하지 않는 계약(AC-6)은 그대로다: 거부는 표지 하나로 안내하고 다음 조작은 사용자가 누른다.
  - **재인증의 복귀 목적지는 여전히 세션의 확정 목적지다.** 그래서 설정 화면에서 시작한 본인 확인은 로그인 시각에 정해진 목적지(대개 `/onboarding`·`/dashboard`)로 돌아가고, 사용자가 `/settings`를 다시 열어야 한다. 미인증으로 `/settings`를 열어 로그인한 경우에는 `next=/settings`가 확정 목적지를 그 값으로 만들어 두므로 곧장 돌아온다. 1.8의 "클라이언트는 목적지를 보내지 않는다"를 무르지 않고 이 이음매를 남긴 것이며, 그것을 닫으려면 그 계약을 다시 협상해야 한다.
  - **`isLinkStart` 판정을 만들지 않았다.** SPEC의 Code Map은 그 메서드와 `isAuthenticationFlow` 편입을 적었지만 둘 다 도달하지 않는 코드다. 세션 guard의 `REAUTH_REQUIRED` 가지는 `isProtectedRequest && !isLogoutRequest && !isReauthenticationStart`에서 이미 403으로 끝나고, 연결 시작은 그 예외 목록에 **들어가서는 안 된다** — 재인증을 요구받은 세션이 계정에 매핑을 더할 수 있으면 그 표시가 막으려던 것이 그대로 열린다. 그래서 판정 메서드 대신 `LINK_BASE_URI`의 javadoc이 "예외 목록에 들어가지 않는다"를 계약으로 적고, `AuthLinkProviderTest.표시된_세션의_연결_시작은_403이다`가 그 결과를 고정한다.
  - **`SUPPORTED_REGISTRATION_IDS`를 `Set`에서 `List`로 바꿨다.** 연결 상태 조회가 그 순서를 그대로 응답에 싣기 때문이다. 집합이면 본문이 실행마다 흔들려 전문 비교가 성립하지 않고, 화면이 아는 고정 순서(Google → 네이버 → 카카오)와도 갈린다. `isSupportedProvider`의 답은 바뀌지 않는다.
  - **거부를 값이 아니라 예외로 되돌린다.** `linkSocialIdentity`가 `DENIED`를 값으로 돌려주므로 그것을 그대로 결과 문자열로 옮기면 소비 UPDATE가 커밋되어 "허가만 소비되고 매핑이 없는" 반쪽 상태가 정확히 남는다. `LinkService`의 사설 `LinkRejected`가 그 트랜잭션을 되돌리고 같은 파일 안에서 잡힌다 — 스택도 메시지도 담지 않으므로 그 사이에 로그를 지나지 않는다.
  - **성공 핸들러가 주체를 먼저 되돌린다.** `AbstractAuthenticationProcessingFilter`는 성공 핸들러보다 앞에서 `OAuth2AuthenticationToken`을 세션에 저장한다. 그 상태로 허가 포트를 부르면 포트의 주체 대조(`PreAuthenticatedAuthenticationToken`의 이름)가 실패해 **정상 연결이 거부된다**. 그래서 `writeEstablishedPrincipal`이 판정보다 앞에 있고, 그 호출이 ID Token을 세션에서 걷어 내는 자리이기도 하다.
  - **연결 인가 요청에는 재인증 파라미터가 실리지 않는다.** 연결은 재인증이 아니므로 `max_age`·`prompt=login`을 얹지 않으며, 그 판정은 `ReauthenticationFlow.peek`이 무장을 보지 못해 자연히 성립한다(두 흐름은 별도 속성이고, 같은 제공자로 동시에 무장될 수도 없다 — 연결은 미연결 제공자를, 재인증은 연결된 제공자를 요구한다). `verify-same-origin.sh`가 그 부재를 단정한다.
  - **두 번째 세션 회전은 외부에서 관측되지 않는다.** 세션 고정 방어가 한 번, 허가 소비 성공이 한 번 더 회전을 요구하는데, Spring Session은 요청 끝에 최종 id 하나만 쓰고 `Set-Cookie`도 한 번만 나간다. 변이로 확인했다 — `request.changeSessionId()`를 지워도 백엔드 시험과 검증 스크립트가 전부 통과한다. 단정이 지키는 것은 "그 두 번째 회전이 세션을 깨뜨리지 않는다"이며, 목적지·재인증 기록 속성이 두 회전을 건너 살아남는지를 백엔드 시험과 스크립트가 이름으로 함께 본다(`spring_session_attributes.attribute_name`). 전체 속성 행 수의 델타로 보지 않는 이유도 같은 자리에서 드러났다 — 성공한 인증마다 `CsrfAuthenticationStrategy`가 CSRF 토큰 속성을 갈아치우므로 그 델타에는 무장 하나가 묻힌다.

  - **변이 확인.** 새 축이 실제로 관측하는지 직접 확인했다 — 매핑 거부를 값으로 되돌리고(1건 실패), 이미 연결된 제공자 게이트를 지우고(1건), 제시된 귀속 세션 id를 live id로 되돌리고(6건), 성공 핸들러의 연결 분기를 지우고(8건), 인가 시작의 연결 무장 판정을 지운 뒤(11건) 각각 원복했다. 관측되지 않은 하나는 위에 적은 두 번째 세션 회전이며 그 이유는 구조적이다.
  - **검증(전량 실측, Docker 있음).** 백엔드 `build` **314** 시험 통과·경고 0건(`-Werror`)·jar 생성(1.9의 279에서 35 증가), `deploy/compose/verify-same-origin.sh` **694/694 exit 0**(연결 왕복 절 신규 60여 단정 포함), `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080` 주입 E2E 50/50, preview E2E 38 통과·12 건너뜀, 프런트엔드 전 검사(`ci`·typecheck·ESLint·stylelint·`format:check`·build·Vitest **214**), `bash .github/scripts/test-ci-scripts.sh` 147/147. 수동 검사도 통과했다 — `git diff --stat develop -- backend/src/main/resources/db/migration/`가 변경 0건(새 마이그레이션 없음), `grep -rn "issuer\|subject" frontend/src`가 주석과 음성 단정 fixture 외 0건, 실행 중인 스택의 `docker compose logs`에 `issuer`·`subject`·`grant_id`·`confirmation_hash`·`link-provider`·`bokrilog.auth.link`·`dev_subject` 0건(연결 경로는 access log에 경로로만 12건 남고 쿼리는 없다).

- 2026-09-25 적대적 리뷰 세 층의 `patch` 판정 18건. frozen 블록은 바꾸지 않았고 설계 재작업도 없다. 앞의 일곱은 실제 사용자 경로가 성립하지 않던 자리이고, 다음 넷은 검증이 아무것도 관측하지 않던 자리이며, 나머지는 화면·카피·정리다.

  - **연결 무장이 살아 있으면 재인증 콜백이 연결 분기에 삼켜졌다.** 성공 핸들러·실패 핸들러·흐름 경계가 전부 `LinkFlow`를 먼저 보고 콜백의 등록 id를 비교하지 않았다. 설정 화면이 연결 버튼 셋과 본인 확인 버튼 셋을 **같은 페이지에** 그리므로 "네이버 연결 무장 → 제공자 화면에서 이탈 → Google 본인 확인"은 평범한 경로이고, 그때 Google 콜백이 제공자 불일치로 `/settings?auth=linkFailed`에 수렴해 **재인증이 성립하지 않고** `reauth_required` 표시도 풀리지 않았다. "두 흐름이 같은 세션에 함께 무장될 이유가 없다"는 첫 구현의 전제가 틀렸다. 방어를 둘로 뒀다 — 무장 시각에 상대 흐름을 버리고(그때 버려지는 연결 무장의 허가도 함께 무효화한다) 그 뒤에 남는 상태는 `callbackLinkFlow`가 콜백의 등록 id로 가른다(일치하는 쪽이 이기고, 어느 쪽도 일치하지 않으면 Story 1.8이 고정한 재인증 실패 수렴이 그대로 남는다).
  - **`/settings`가 고착 목적지가 됐다.** 첫 구현의 `staysOn`은 `expected==='settings' && destination==='dashboard'` 한 방향만 봐줬다. 미인증으로 `/settings`를 열어 로그인한 사용자는 `DESTINATION_ATTRIBUTE`가 `/settings`로 굳어 그 세션 내내 `/dashboard`가 되튕겼다. 그리고 e2e를 붙이자 **반대편 결함**이 드러났다 — 방금 가입한 계정의 목적지는 언제나 `onboarding`이라 온보딩 화면의 설정 링크가 곧바로 되튕겼다(첫 세션에서는 자기 로그인 수단을 아예 볼 수 없었다). 그래서 예외를 대칭으로 만드는 대신 규칙을 성질로 갈랐다: 미는 일은 **착지 화면 둘(온보딩·대시보드) 사이에서만** 일어나고 설정 화면은 어느 방향으로도 참여하지 않는다.
  - **어느 화면도 `/settings`로 가는 길을 주지 않았다.** 두 로그인 상태 화면의 머리말에 링크를 뒀다(탭 순서는 건너뛰기 → 설정 → 로그아웃). 재인증 복귀가 `/settings`가 아닌 것은 defer로 남았으므로, `authContent.settings.reauth.body`의 "확인을 마치면 이 화면으로 돌아와"를 사실에 맞게 고쳤다 — 확인 뒤에는 로그인 상태 화면으로 돌아가고 거기 링크로 다시 온다.
  - **인증 상태의 설정 화면에 접근성 검사가 하나도 없었다.** e2e의 `/settings` 축은 미인증뿐이었고 그 상태는 `EntryScreen`을 그리므로, 이 스토리가 더한 가장 큰 마크업 표면이 검증 밖이었다. 스텁 왕복으로 로그인한 뒤 설정 링크로 들어가 axe·`boundingBox` 44px·320px 넘침을 보는 축을 게이트 안쪽에 더했다.
  - **아는 이름이지만 구성되지 않은 제공자의 연결 시작이 204를 돌려줬다.** 이어지는 인가 시작이 fail-closed로 끝나 사용자는 원본 오류 JSON에 닿고, 참조할 수 없게 된 허가가 5분을 살았다. Story 1.9의 "등록 구성 여부는 조회하지 않는다"는 **재인증 시작**의 계약이고 그 경로는 남는 상태가 없다 — 연결 시작은 만든다. `isConfiguredProvider` 게이트를 `LinkService.start`에 뒀다(인증된 소유자에게만 답하는 경로라 열거 위험이 다르다).
  - **두 번째 연결 시작이 앞의 허가를 고아로 남겼다.** `invalidateLiveGrants`의 술어가 `(user_id, command, target)`이고 target이 제공자 이름이라, 네이버로 무장한 뒤 카카오로 다시 무장하면 네이버 허가만 살아남았다. 무장을 버리는 자리와 허가를 무효화하는 자리를 `LinkService.abandon` 하나로 묶었다.
  - **`Set`에서 `List`로 바꾸며 중복 등록 id guard가 사라졌다.** `rejectDuplicateRegistrationIds`를 명시적으로 두고 목록이 그 경로를 지나 만들어지게 했다.

  - **두 번째 세션 회전에 관측자가 없었다.** 판정과 호출을 `AuthFlowHandlers.rotateIfLinked` 하나로 모으고 `LinkService.complete`가 `boolean` 대신 이름 있는 결과(`Outcome`)를 돌려주게 해서, "소비에 성공했을 때만 회전한다"를 HTTP 밖에서 본다(`AuthFlowHandlersRotationTest`, `changeSessionId()` 호출을 세는 `MockHttpServletRequest`). 이제 그 호출을 지우면 시험이 실패한다.
  - **`/settings` 복귀 목적지와 `destination:"settings"`를 서버 쪽에서 실행하는 시험이 없었다.** `AuthRedirectDestinationTest`에 `?next=/settings` 축과 그 세션의 `"destination":"settings"` 단정을 더했다.
  - **검증 스크립트의 새 단정 몇 개가 실패할 수 없었다.** 본문을 전문 동등성으로 고정한 직후의 `assert_body_lacks` 셋과 고정된 오류 본문의 이름 부재 단정을 지우고(그 계약은 전문 동등성이 이미 소유한다), 다른 사용자 탐침 셋에 상태 코드 단정을 더했다.
  - **I/O 표의 두 행과 접두사 계약에 시험이 없었다.** "같은 제공자 다른 subject"(왕복 도중 같은 제공자가 붙는 경합)와 "흐름 도중 세션 교체"(연결 무장 도중 재인증으로 갈아타기)를 백엔드와 스크립트 양쪽에 더했고, `/api/auth/link` 접두사를 메서드가 가른다는 계약을 두 탐침이 본다(`GET /api/auth/link/<제공자>` 405, 제공자 없는 `POST /api/auth/link`). 빈 목록의 HTTP 모양은 `구성되지_않은_제공자` 축과 identity 시험이 함께 덮는다.
  - **연결 상태 조회 실패가 "연결된 것이 없음"과 구분되지 않았다.** `fetchLinkedProviders`가 판별 결과(`loaded`·`unavailable`)를 돌려주고 화면이 실패 상태와 "다시 확인" 조작을 갖는다. 접으면 일시적 실패에서 **이미 연결된 제공자에도 연결 버튼이 그려지고** 그 클릭이 엉뚱한 안내로 끝난다.

  - **연결 성공에 확인이 없었다.** 화면은 시작을 `role="status"`로 알리는데 결과를 알릴 재료가 없었다. 서버가 성공에도 표지 하나를 달고(`linkSucceeded`, 이 파일의 유일한 비실패 표지) 무엇을 연결했는지는 말하지 않는다 — 왕복 뒤의 문서는 새로 열리므로 "방금 연결했다"를 아는 값은 주소의 표지뿐이다.
  - **`linkCancelled`·`linkFailed` 문구가 진입 화면에서 거짓이었다.** URL 표지 집합을 화면별로 좁혔다 — `noticeKindOf`가 인증 화면의 값만 돌려주고(`EntryNoticeKind`), 재인증·연결 표지는 각자의 거름망이 읽는다. 셋이 같은 `URL_NOTICE_KINDS`를 지나므로 어느 값도 조용히 무시되지 않는다.
  - **경로 집합을 세는 문서가 서로 달랐다.** 실제로 다섯 자리다(`RETURN_DESTINATIONS`가 빠져 있었다). `DEVELOPMENT.md`·`AuthFlowHandlers.SETTINGS_PATH` javadoc·`bokrilog.conf`·`content/auth.ts`를 사실에 맞췄다. 등가 검사 도입은 defer다.
  - **한 문자열에 상수 이름이 둘이었다.** 계층마다 하나로 줄였다(`AuthSecurityConfig.LINK_BASE_URI`, `content/auth.ts`의 `LINK_BASE_PATH`).
  - **정리.** `isLinkStart`를 지운 자리의 빈 줄, 104열 `@GetMapping`, `SettingsScreen.module.css`의 같은 선언 두 클래스(`.noticeText`·`.linkNotice`를 한 규칙으로 합쳤다), `DEVELOPMENT.md`의 "자기 캐시 지시를 내는 유일한 응답"(이제 둘이다).

  - **변이 확인.** 고친 축마다 직접 확인했다 — 콜백 제공자 판정을 지우고(1건 실패), 재인증 시작의 상대 흐름 정리를 지우고(1건), 버려지는 무장의 허가 무효화를 지우고(2건), 등록 구성 게이트를 지우고(1건), 두 번째 세션 회전을 지우고(1건 — 이 스토리 전에는 **0건**이었다), 중복 등록 id 검사를 지우고(1건), `destinationKind`의 `settings` 가지와 서버 허용 목록을 지우고(1건), 성공 표지를 지우고(1건), `staysOn`의 예외를 지우고(3건), 연결 상태 실패를 빈 목록으로 접고(1건), 대시보드의 설정 링크를 지운 뒤(2건) 각각 원복했다.
  - **검증(전량 실측, Docker 있음).** 백엔드 `clean build` **323** 시험 통과·경고 0건(`-Werror`)·jar 생성(리뷰 전 314에서 9 증가), `deploy/compose/verify-same-origin.sh` **707/707 exit 0**(694에서 13 증가), `PLAYWRIGHT_BASE_URL` 주입 E2E **51/51**, preview E2E 38 통과·13 건너뜀, 프런트엔드 전 검사(`ci`·typecheck·ESLint·stylelint·`format:check`·build·Vitest **222**), `bash .github/scripts/test-ci-scripts.sh` 147/147. 수동 검사도 그대로다 — 새 마이그레이션 0건, `grep issuer|subject frontend/src`가 주석과 음성 단정 fixture 외 0건, 실행 중인 스택 로그에 `issuer`·`subject`·`grant_id`·`confirmation_hash`·`link-provider`·`bokrilog.auth.link`·`dev_subject`·`linkSucceeded` 0건.

## Design Notes

**콜백의 세션 회전이 이 스토리의 가장 어려운 제약이다.** Spring Security는 성공 핸들러 직전에 세션 ID를 반드시 회전시킨다(`AuthSecurityConfig.java:819-820`). 그래서 연결 시작에서 발급한 허가의 `session_id`와 콜백 시점의 live id는 **언제나 다르다** — 1.9 포트가 live id로만 귀속을 판정하면 연결은 구조적으로 불가능하다. 해결은 연결 흐름 record가 **발급 시각의 세션 id를 함께 들고** 그 값을 소비에 제시하는 것이다. 이것이 귀속을 무르지 않는 이유는 그 record가 **세션 속성**이기 때문이다 — `changeSessionId`는 속성을 보존하므로 그 값을 읽을 수 있다는 사실 자체가 "회전 전 세션의 연속"을 증명한다. 다른 세션은 그 속성을 갖지 못한다. 포트의 javadoc과 시험이 "이 값은 클라이언트가 아니라 서버 측 흐름 기록에서만 온다"를 고정해야 하며, 그러지 않으면 요청 파라미터로 남의 허가를 겨누는 길이 열린다.

**연결 흐름을 재인증 무장에 섞지 않는다.** `ReauthenticationFlow`의 클래스 주석은 "재인증 경로는 계정을 만들지 않고 매핑을 만들지 않고 사용자를 바꾸지 않는다"를 계약으로 적는다. 연결은 정확히 매핑을 만드는 일이다. 한 record에 목적 필드를 더해 겸용하면 그 계약이 조건부가 되고, `onSuccess`의 분기점(`:455-459`)이 "무장이 있으면 재인증"에서 "무장이 있고 목적이 X면"으로 바뀌어 읽는 사람이 매번 목적을 확인해야 한다. 별도 클래스가 더 싸다.

**두 클릭이 정상이다.** 재인증이 신선하지 않으면 연결 시작이 거부되고 화면이 재인증을 안내한다. 사용자는 재인증을 마치고 `/settings`로 돌아와 다시 `연결`을 누른다. 자동으로 이어 하지 않는 이유는 AC-6의 "자동 재시도하지 않는다"와 AC-2의 "재인증 취소·실패·만료 시 새 제공자 인증을 시작하지 않는다"가 같은 것을 요구하기 때문이다. 재인증 뒤 목적지에 대상 제공자를 실어 나르려면 복귀 경로에 파라미터가 필요한데, 복귀 목적지는 **경로만 담는 닫힌 집합**이라 그 길은 막혀 있다 — 그 제약을 무르는 대신 클릭 하나를 받는다.

**`identity`가 이미 원자성을 갖고 있다.** `insertSocialLinkIfActive`는 `INSERT ... SELECT ... WHERE state='ACTIVE' ON CONFLICT DO NOTHING` 한 문장으로 삭제 상태와 두 유일 인덱스를 함께 판정하고 0/1행으로 답한다. 이 스토리가 더할 것은 그 호출을 **허가 소비와 같은 트랜잭션에 넣는 것** 하나이며, 유일성·동시성·거부 축은 `AccountConcurrencyTest`·`AccountAccessDecisionTest`가 이미 고정했으므로 복제하지 않는다. HTTP·허가 층에서만 본다.

**연결 목록 조회를 새로 여는 것이 AC-1의 유일한 길이다.** `AuthSessionController.java:224-226`은 1.9에서 "연결된 제공자 목록도 보지 않는다"를 계약으로 적었는데, 그것은 **미인증·재인증 시작 경로**의 계약이다(그 경로에서 목록을 보면 열거 표면이 된다). 설정 화면은 인증된 소유자에게 자기 계정의 사실을 보여 주는 자리이므로 성질이 다르다. 반환을 제공자 이름 집합으로 좁히고 미존재·삭제를 빈 집합으로 접어, 그 응답에서 계정의 존재나 외부 식별자를 복원할 수 없게 한다.

**`/settings`는 네 곳이 같은 집합을 가져야 한다.** Nginx 허용 목록, `App.tsx` 경로 맵, 서버의 `ALLOWED_DESTINATIONS`, 세션 목적지 유니언이다. 한 곳만 빠지면 증상이 제각각이다 — Nginx가 빠지면 404, `App.tsx`가 빠지면 진입 화면, 서버 허용 목록이 빠지면 복귀가 대시보드로 접히고, 목적지 유니언이 빠지면 타입이 막는다. 마지막 하나만 컴파일 시점에 드러나므로 나머지 셋을 e2e·검증 스크립트가 본다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend clean build` -- expected: 연결·거부·멱등·모듈 경계 시험 통과, 경고 0건(`-Werror`), jar 생성. Docker 데몬 필요
- `npm --prefix frontend ci && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test` -- expected: 전부 통과, 경고 0건
- `npm --prefix frontend run test:e2e` -- expected: Story 1.1·1.4~1.8 회귀와 `/settings` 미인증 축 통과
- `deploy/compose/verify-same-origin.sh` -- expected: 연결 왕복 절 포함 모든 단정 통과, exit 0
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 전부 통과. `ci.yml`을 바꾸지 않았으므로 무변경 통과여야 한다
- `git diff --stat develop -- backend/src/main/resources/db/migration/` -- expected: 변경 0건(새 마이그레이션 없음)

**Manual checks:**
- 네 곳의 경로 집합 비교 -- `deploy/nginx/bokrilog.conf`의 정규식, `App.tsx`의 분기, `AuthFlowHandlers.ALLOWED_DESTINATIONS`, `useSessionState`의 `DESTINATION_PATHS`가 같은 집합인지
- `grep -rn "issuer\|subject" frontend/src` -- expected: 0건(외부 식별자가 화면 층에 없다)
- 실행 중인 스택의 `docker compose logs`에서 `issuer`·`subject`·허가 참조·세션 ID 검색 -- expected: 0건
- 브라우저 개발자 도구에서 `/settings`의 쿠키·저장소·외부 요청 -- expected: 쿠키 1개, 저장소 0건, 외부 origin 요청 0건
