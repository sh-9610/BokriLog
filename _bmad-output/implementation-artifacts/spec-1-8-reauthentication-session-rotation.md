---
title: 'Story 1.8 — 민감 작업 전에 본인임을 다시 확인하고 세션을 회전하기'
type: 'feature'
created: '2026-09-21'
status: 'done'
review_loop_iteration: 0
baseline_commit: 'd3d9c3d1dc7562a26606eda2ab2b3b383d06fe57'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-5-logout-session-expiry.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-7-kakao-sign-in.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 재인증을 요구받은 세션에는 되살아날 길이 없다. 세션 확인이 미인증으로 답해 CSRF 토큰이 화면에 닿지 않고, 진입 화면의 로그인 버튼을 눌러도 `authorizationStartFilter`가 "이미 인증된 세션"으로 거절해 목적지로 되돌린다 — 목적지는 다시 미인증으로 답하므로 무한 왕복이다. 민감 command 앞에서 본인을 다시 확인할 서버 표면도 없어 1.9~1.11이 소비할 재인증 계약이 비어 있다.

**Approach:** 기존 OIDC 왕복을 그대로 재사용하고, 세션에 심은 **일회성 재인증 흐름**이 성공 핸들러를 로그인과 갈라 놓는다. 계정을 만들지도 바꾸지도 않고 검증된 `(issuer, subject)`가 현재 사용자의 것인지만 확인하며, "방금 실제로 인증했는가"는 제공자가 서명한 `auth_time`의 신선도로 판정한다.

## Boundaries & Constraints

**Always:**
- 공통 OIDC 검증(PKCE S256, 일회성 `state`·`nonce`, JWS·RS256·JWKS·`iss`·`aud`·`azp`·`exp`·`nonce`)은 Spring Security OAuth2 Client가 그대로 한다. 재인증은 **같은** `oauth2Login` 왕복을 지나며 두 번째 인증 경로를 만들지 않는다.
- 재인증 경로는 계정을 만들지 않고, 매핑을 만들지 않고, 세션의 사용자를 바꾸지 않는다. `AuthSessionService.establish`를 부르지 않는다.
- 최근 인증 증거는 제공자가 서명한 `auth_time` 하나다. 부재·신선도 창 초과·허용 skew 밖의 미래는 모두 실패다. 콜백 도착 시각·제공자 세션의 존재·계정 선택은 증거가 아니다. **증거가 없으면 완화하지 않고 실패한다** — 제공자가 무엇을 공표했든 판정은 콜백의 claim 하나로 한다.
- 제공자마다 다른 것은 **인가 요청 파라미터**뿐이다. 공통 `max_age`(OIDC Core: 이 파라미터를 쓰면 ID Token은 `auth_time`을 실어야 한다) 위에 각 제공자가 공표한 수단을 더한다 — Google은 `claims`의 essential `auth_time`, 카카오는 `prompt=login`, 네이버는 공표한 추가 수단이 없다. 검증·판정·오류는 셋이 공유한다.
- 세션 회전은 기존 배선(`sessionFixation().changeSessionId()`)이 콜백에서 이미 한다. 재인증 결과는 회전이 끝난 뒤의 세션에만 쓴다.
- 복귀 목적지는 `AuthFlowHandlers.ALLOWED_DESTINATIONS` 닫힌 집합뿐이고, 재인증 흐름이 **시작 시각에** 붙들어 둔 값이다. 그 값의 출처는 세션이 이미 가진 `DESTINATION_ATTRIBUTE` 하나이며 **클라이언트는 목적지를 보내지 않는다**. 콜백이 들고 온 어떤 값도 목적지가 되지 않는다.
- 취소·실패는 이미 개설된 세션을 건드리지 않는다(`discardSession`의 기존 계약). 재인증 흐름만 버린다.
- `GET /api/auth/session`은 표시된 세션에만 `reauthRequired: true`와 세션 귀속 CSRF 토큰을 싣는다. Story 1.5가 고정한 "표시와 폐기가 응답으로 구분되지 않는다"를 **이 스토리가 의도적으로 되짚는다** — 구분되지 않으면 표시된 세션이 회복할 길이 없고, `SessionGuard` javadoc이 그 예외를 "1.8 범위"로 이미 적어 두었다. 회복에 필요한 것은 제공자 자격이므로 표시 사실을 아는 것만으로는 누구도 그 세션을 되살리지 못한다. **미인증·폐기 응답의 본문은 글자까지 그대로다.**
- `authorizeHttpRequests`는 전부 `permitAll` 그대로다.

**Ask First:**
- `identity` 공개 API·스키마·마이그레이션 변경. 이 스토리는 필요 없다고 판단했다(`findUserIdBySocialIdentity` + `AccountAccessApi.decide(SENSITIVE)` 조합으로 충분하다).
- 신선도 창 5분·허용 clock skew 60초 값의 변경.
- 네이버를 "최근 인증 증거 미공표"로 판정한 것을 뒤집는 것. 실연동 증거 없이 완화하지 않는다.

**Never:**
- action grant·5분 일회성 허가(1.9), 로그인 수단 연결·해제(1.10·1.11), 제공자 측 로그아웃, Epic 5의 삭제 화면.
- 세션에 재인증 요구를 **만드는** HTTP 표면. `SessionGuard`는 이 스토리 뒤에도 HTTP를 갖지 않는다.
- 연결된 제공자 목록을 응답이나 화면에 노출하는 것. 재인증 화면은 같은 세 버튼을 그대로 보이고, 연결되지 않은 제공자는 다른 실패와 구분되지 않는 하나로 수렴한다.
- 새 내부 경로·Nginx location·프런트엔드 라우터. 재인증 화면은 두 인증 화면이 공유하는 게이트 상태 하나로 제자리 렌더한다.
- 새 마이그레이션·새 컬럼·새 의존(운영·시험 양쪽).
- 재인증 성공만으로 어떤 파괴적 작업도 실행하는 것.
- 두 인증 화면의 레이아웃 CSS 중복 해소와 제공자 이름 목록의 계층 간 등가성 검사. 둘 다 별도로 배포 가능한 딴 목표다(미해소 항목 33·38·42).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 표시된 세션의 회복 | `reauth_required` 표시 + 현재 사용자에게 연결된 제공자, `auth_time` 신선 | 표시 제거, 세션 ID 회전, 재인증 시각 기록, 붙들어 둔 목적지로 복귀, 이후 세션 확인이 인증됨 | N/A |
| 인증된 세션의 자발 재인증 | 표시 없는 인증 세션이 흐름을 시작 | 같은 결과. 표시가 없었을 뿐 재인증 시각과 회전은 같다 | N/A |
| 타 사용자 식별자 | 검증은 통과했으나 `(issuer, subject)`가 다른 사용자의 것 | 재인증 실패. 계정·매핑·세션·표시 무변경 | 복귀 경로에 실패 표지 하나 |
| 미연결 식별자 | 어느 계정에도 없는 `(issuer, subject)` | 같은 실패. **계정을 만들지 않는다** | 위와 응답으로 구분되지 않음 |
| `auth_time` 부재 | ID Token에 claim 없음 | 재인증 실패 | 완화 없음 |
| `auth_time` 오래됨·미래 | 신선도 창 + skew 밖 | 재인증 실패 | 〃 |
| 사용자 취소 | 콜백 `error=access_denied` | 흐름 폐기, 세션·표시 무변경 | 복귀 경로에 취소 표지, 자동 재시도 없음 |
| 제공자 바꿔치기 | google로 무장하고 kakao 콜백 도착 | 실패, 흐름 폐기 | 실패 표지 하나 |
| 흐름 재사용 | 같은 무장으로 두 번째 콜백 | 두 번째는 실패 | 일회성 |
| 흐름 없는 콜백 | 재인증 무장 없이 콜백 도착 | 기존 로그인 판정 그대로 | 변화 없음 |
| 삭제 전환 | 흐름 도중 계정이 `DELETION_IN_PROGRESS` | 재인증 실패 | `AccessDecision.DENIED` 하나 |
| 흐름 도중 로그아웃·만료 | 세션 행이 사라짐 | 흐름도 함께 사라짐. 콜백은 기존 실패 경로로 수렴 | 상태 변경 0건 |
| 미인증 시작 | 세션 없이 `POST /api/auth/reauthenticate/google` | 403 | 세션 귀속 토큰이 없다 |
| CSRF·교차 출처 시작 | 인증 세션 + 토큰 누락 또는 다른 `Origin` | 403 | 기존 계약 그대로 |
| 미지 제공자 시작 | `POST /api/auth/reauthenticate/<미등록 이름>` | 404 | 아는 제공자 이름·개수 미노출 |

</frozen-after-approval>

## Code Map

**검증된 외부 사실 (2026-09-21 실측)**

- **카카오** — discovery `claims_supported`에 `auth_time` 있음. 공식 REST 문서가 `/oauth/authorize`의 `prompt` 값으로 `login`("이미 인증되어 있어도 재인증을 요청할 때")을 공표하고, ID Token claim 표에 `auth_time`("이용자 인증 시각, Unix timestamp")을 공표한다. `max_age`는 공표하지 않는다. → 강제 수단과 증거 **둘 다** 공표됨.
- **Google** — discovery `claims_supported`에 `auth_time` **없음**. 그러나 공식 OpenID Connect 문서는 ID Token payload 목록에 `auth_time`을 싣고, `claims` 요청 파라미터의 `id_token` 객체에 `{"auth_time":{"essential":true}}`를 넣어 요청하라고 안내한다. 문서가 공표하는 `prompt` 값은 `none`·`consent`·`select_account`뿐이며 **`login`이 없고 `max_age`도 목록에 없다**. → 증거는 요청 가능, 재인증 **강제** 수단은 미공표.
- **네이버** — discovery에 `claims_supported` 자체가 없다. 네이버 공식 답변(`github.com/naver/naveridlogin-API` 이슈 #92, 2026-09-18): "`nonce`는 지원하고 있습니다. 다만 ID Token의 claim 구성과 nonce를 별도로 설명한 **공식 문서는 현재 제공되고 있지 않습니다.**" → `auth_time`도 재인증 요청 파라미터도 공표된 것이 없다. 이 스토리는 공통 `max_age`만 보내고 증거 부재 시 fail-closed한다.
- OIDC Core: `max_age`를 쓰면 OP는 필요 시 재인증해야 하고 ID Token은 `auth_time`을 **반드시** 실어야 한다. 세 제공자 모두 이 공통 파라미터를 받는다(모르는 파라미터는 무시된다).

**백엔드 main — 고칠 자리**

- `AuthFlowHandlers.java:82` `ALLOWED_DESTINATIONS`, `:85`·`:88` 세션 속성 상수 둘, `:98`·`:101` 실패 표지 둘 -- 재인증 표지 둘이 여기 붙는다.
- `AuthFlowHandlers.java:178-230` `authorizationStartFilter()` -- `:186-196`이 인증된 세션의 시작을 거절한다. 무장된 재인증 흐름이 요청 제공자와 맞으면 **통과**시키는 분기가 여기 들어간다. `:199-211`의 `next` 처리는 재인증 시작에서 건너뛴다(목적지는 무장 시각에 이미 정해졌다). `:214`의 `capFlowSessionLifetime`은 `carriesEstablishedPrincipal`로 이미 인증 세션을 건너뛰므로 무변경이다(`:233-240`).
- `AuthFlowHandlers.java:275-305` `sessionGuardFilter()` -- `REAUTH_REQUIRED` 가지의 403 예외에 재인증 시작을 더하고(`isLogoutRequest` 옆), `isAuthenticationFlow`(`:597-605`)가 재인증 시작을 포함하게 한다. 두 번째가 없으면 컨트롤러가 주체를 읽지 못한다.
- `AuthFlowHandlers.java:340-401` `onSuccess` -- 무장된 흐름이 있으면 **로그인 분기 전체를 타지 않는다**. `:341`의 `establish`(→`:415-431`, 계정 생성 경로)를 부르지 않고, 재인증 판정 → `auth_time` 검증 → 표시 제거·재인증 시각 기록 → 붙들어 둔 목적지로 복귀만 한다. 주체 교체(`:385-398`)도 하지 않는다 — 세션의 주체는 이미 그 사용자다.
- `AuthFlowHandlers.java:433-456` `onFailure`·`converge` -- 무장된 흐름이 있으면 진입 화면이 아니라 그 흐름의 목적지로 재인증 표지를 달고 보낸다. `discardSession`(`:470-481`)의 "인증된 세션은 건드리지 않는다"가 그대로 성립해야 한다.
- `AuthSecurityConfig.java:538-564` `authorizationRequestResolver` -- 지금은 예외만 접는 래핑이다. 여기서 무장된 흐름을 보고 `OAuth2AuthorizationRequest.from(resolved).additionalParameters(Map::putAll)`로 제공자별 파라미터를 얹는다. `Consumer<Map>` 오버로드를 써서 `nonce`·PKCE가 든 기존 값을 덮지 않는다.
- `AuthSecurityConfig.java:87`·`:90`·`:93` 등록 id 상수 셋, `:294-343` `put()`, `:345-371` 제공자 빌더 -- 제공자별 재인증 파라미터 맵이 이 근처에 선언된다(제공자 차이가 사는 유일한 자리).
- `AuthSecurityConfig.java:126` `PROTECTED_PATH_PREFIX`, `:770-785` `isLogoutRequest`·`isProtectedRequest` -- 재인증 시작 경로 상수와 판정이 같은 모양으로 더해진다. `/api/auth/` 아래라 CSRF·`Origin` 보호는 자동이다.
- `AuthSessionController.java:60-61` `SessionState` record -- `reauthRequired` 성분 추가(`@JsonInclude(NON_NULL)`이라 평소에는 나가지 않는다). `:64-91` `session()`이 표시 여부를 판정에 넣고, `:106-116` `csrfToken(...)`의 `authenticated` 게이트가 "인증됐거나 표시됨"으로 넓어진다. `:139-153` `authorizationUnavailable` 옆에 재인증 시작 핸들러가 온다.
- `AuthSessionService.java:70-88` `establish` -- 무변경. 옆에 `confirmReauthentication(UUID, String, String)`을 더한다. `identity` 공개 API를 부르는 자리는 이 클래스 하나라는 계약을 유지한다. `AccountAccessApi`(`identity/AccountAccessApi.java:28` `decide(callerUserId, targetUserId, kind)`)를 새로 주입하고 `AccountAccessKind.SENSITIVE`로 삭제 상태를 본다.
- `SessionGuard.java` -- **무변경**. `REAUTH_REQUIRED_ATTRIBUTE`(`:50`)를 읽고 지우는 쪽만 늘어난다.
- 새 파일 `ReauthenticationFlow.java` -- 세션 속성 이름 하나, 무장 record(provider·userId·returnPath·armedAt), `arm`·`peek`·`consume`·`discard`, 신선도 창·skew 상수와 `auth_time` 판정. `AuthFlowHandlers`가 150줄 더 커지는 대신 이 계약이 한 파일로 읽힌다.
- 무변경: `identity/**`, `db/migration/**`, `deploy/nginx/bokrilog.conf`, `application.yml`(새 설정 키 없음 — 신선도 값은 `FLOW_SESSION_TIMEOUT_SECONDS`와 같은 이유로 코드 상수다).

**개발·CI 스텁 — 넓힐 자리**

- `DevOidcStub.java:458-491` `idToken()` -- claim 목록에 `auth_time`을 더한다. `:283-340` `TokenPlan`에 `authTime(Instant)`와 "claim 생략" 변형을 더해 시험이 부재·오래됨·미래를 주입한다.
- `DevOidcStub.java:203-224` `consentRedirect(Map)` -- 받은 `max_age`·`prompt`·`claims`를 기록해 시험이 "우리가 실제로 보냈는가"를 볼 수 있게 한다. 값에 따라 행동을 바꾸지 않는다(결정성 유지).
- `DevOidcStubConfig.java:224-267` 라우터 -- 무변경. `OidcStubConfiguration.java:62-100` -- 무변경(새 프로퍼티 없음).

**백엔드 시험 — 더할 자리**

- `AuthFlowTestBase.java:130-243` 왕복 헬퍼, `:259-274` CSRF 헬퍼, `:401-423` 쿠키 헬퍼, `:460-534` 저장소 헬퍼 -- 재인증 시작 POST 헬퍼와 재인증 왕복 헬퍼가 여기 붙는다. `@DynamicPropertySource`를 새로 만들지 않는다(컨텍스트 캐시가 갈라진다).
- `SessionGuardTest.java:133` -- "1.8의 재인증이 회전시킬 대상이 여기 있다"고 적어 둔 자리. 표시된 세션의 회복 시험이 이 단정과 짝을 이룬다.
- `AuthSessionContractTest.java:47-64` 회전 단정의 형태를 재인증 회전이 그대로 쓴다. `:390`·`:418`의 `spring_session` 직접 조회 관용도 같다.
- `AuthTokenValidationTest.java`의 18축은 **되풀지 않는다**(재인증도 같은 검증기를 지난다). `AuthProviderIndependenceTest.java`의 `PROVIDERS` 루프 형태를 제공자별 재인증 축이 재사용한다.
- `AuthSessionStateJsonTest.java:26` -- `reauthRequired` 직렬화 축. 미인증 본문 전문 동등성이 여기서 지켜진다.

**프런트엔드·배포 — 넓힐 자리**

- `session/useSessionState.ts:25` `SessionState`, `:39-45` 스냅샷, `:67` `SessionGate`, `:110-116` `tokenOf`, `:306-309` `SessionGateResult`, `:323-327` `liveRegionMessage`, `:351-425` `useSessionGate` -- `reauthRequired`를 읽어 게이트 값 하나를 더한다.
- `content/auth.ts:45`·`:52` 엔드포인트 상수, `:100` `authorizeHref`, `:120-121` `AuthNoticeKind`, `:137-143` `URL_NOTICE_KINDS`, `:154` `noticeKindOf`, `:172-200` `NOTICES`, `:186-304` `authContent`, `:307-343` 카피 문자열 -- 재인증 엔드포인트·표지 둘·재인증 안내 카피.
- `session/signOut.ts:76-82` -- 재인증 시작 호출(`session/reauthenticate.ts`)이 복제할 형태(CSRF 헤더·`same-origin`·중복 제출 차단).
- `screens/OnboardingScreen.tsx:34-42`·`SignedInScreen.tsx:36-44` -- 게이트 분기. `'reauth-required'` 가지가 공유 패널을 렌더한다. 레이아웃 CSS 복제는 **손대지 않는다**.
- `components/SignOutButton.tsx:43-44` -- "두 화면 복제 금지" 관용. 재인증 패널도 `components/`의 공유 컴포넌트 하나다. 패널이 로그아웃 버튼을 함께 둔다 — 표시된 세션이 화면에서 스스로 나갈 수 있게 되는 자리다.
- `App.tsx:21-31` -- **무변경**. 새 경로가 없다.
- `deploy/compose/verify-same-origin.sh:58-70` 제공자 목록·개수, `:890-1041` `round_trip`, `:1138` 로그아웃 절, `:1452` 누출 검사 -- 인증된 세션의 재인증 왕복 절 하나와 미지 제공자 404 탐침을 더한다. 완주 가드 형태(`:829`·`:1113`·`:1450`)를 그대로 쓴다.
- `deploy/nginx/bokrilog.conf` -- **무변경**(`^~ /api/`가 새 경로를 이미 받는다).

## Tasks & Acceptance

**Execution:**

- [x] `backend/src/main/java/com/bokrilog/ReauthenticationFlow.java` -- 세션 속성 하나, 무장 record(provider·userId·returnPath·armedAt), `arm`·`peek`·`consume`·`discard`, 무장 수명(5분)·신선도 창(5분)·clock skew(60초) 상수와 `auth_time` 판정(부재·초과·미래 모두 실패)을 담는다. -- 계약 하나가 한 파일에서 읽히고, 신선도 정책을 두 곳에 적지 않는다.
- [x] `backend/src/main/java/com/bokrilog/AuthSessionService.java` -- `AccountAccessApi`를 주입하고 `confirmReauthentication(currentUserId, issuer, subject)`를 더한다. `findUserIdBySocialIdentity`가 현재 사용자와 같고 `decide(user, user, SENSITIVE)`가 `ALLOWED`일 때만 참이다. 계정 생성·매핑·전환 경로를 만들지 않는다. -- `identity`를 부르는 자리는 이 클래스 하나라는 계약을 지킨다. AC-2.
- [x] `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java` -- 재인증 시작 경로 상수와 `isReauthenticationStart` 판정을 더하고, 제공자별 재인증 파라미터 맵(공통 `max_age`, Google `claims`, 카카오 `prompt=login`, 네이버 추가 없음)을 선언하고, `authorizationRequestResolver`가 무장된 흐름을 볼 때만 그 파라미터를 얹게 한다. -- 제공자 차이가 사는 자리는 등록 조립 하나다. 파라미터를 상시로 얹으면 일반 로그인이 매번 재인증을 요구한다.
- [x] `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` -- 재인증 표지 둘을 더하고, `authorizationStartFilter`가 무장·제공자 일치 시 인증된 세션의 시작을 통과시키게 하고(그때 `next` 처리는 건너뛴다), `sessionGuardFilter`의 403 예외와 `isAuthenticationFlow`에 재인증 시작을 넣고, `onSuccess`에 재인증 분기(`establish` 미호출, `auth_time` 검증, 표시 제거, 재인증 시각 기록, 붙들어 둔 목적지 복귀)를, `converge`에 재인증 실패 복귀를 더한다. -- AC-1·AC-3~AC-6. 성공 핸들러가 `establish`를 타면 미연결 식별자로 계정이 생긴다.
- [x] `backend/src/main/java/com/bokrilog/AuthSessionController.java` -- `SessionState`에 `reauthRequired`를 더하고, 표시된 세션에 CSRF 토큰을 싣고, `POST /api/auth/reauthenticate/{provider}`를 더한다(미지 이름 404, 주체 없음 403, 그 밖에는 목적지를 붙들어 무장하고 204). 등록 구성 여부는 조회하지 않는다 — 그 판정은 뒤따르는 인가 시작이 이미 소유한다. -- AC-1. 미인증 응답 본문은 그대로여야 한다.
- [x] `backend/src/main/java/com/bokrilog/DevOidcStub.java` -- ID Token에 `auth_time`을 싣고, `TokenPlan`에 `authTime` 주입과 claim 생략 변형을 더하고, 받은 `max_age`·`prompt`·`claims`를 기록만 한다(행동은 바꾸지 않는다). -- AC-3의 세 실패 축과 "우리가 실제로 보냈는가"를 결정적으로 볼 유일한 층이다.
- [x] `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java` -- 재인증 시작 POST와 재인증 왕복 헬퍼, 세션 속성 조회 헬퍼(주입된 `SessionRepository` 경유)를 더한다. 새 `@DynamicPropertySource`를 만들지 않는다. -- 컨텍스트 캐시를 가르지 않는 것이 CI wall-clock의 전제다.
- [x] `backend/src/test/java/com/bokrilog/AuthReauthenticationTest.java` -- 표시된 세션의 회복, 자발 재인증, 타 사용자·미연결 식별자, `auth_time` 부재·오래됨·미래, 취소, 제공자 바꿔치기, 흐름 재사용, 흐름 없는 콜백, 삭제 전환, 흐름 도중 로그아웃, 시작의 CSRF·교차 출처·미인증·미지 제공자, 세 제공자에 실제로 실린 요청 파라미터를 단정한다. `AuthTokenValidationTest`의 18축은 복제하지 않는다. -- AC-1~AC-6의 단일 소유자.
- [x] `backend/src/test/java/com/bokrilog/AuthSessionContractTest.java` + `AuthSessionStateJsonTest.java` -- 재인증 성공의 회전(전/후 ID 다름, 이전 쿠키로 미인증, 세션 행 1개)과 `reauthRequired` 직렬화를 더한다. 미인증 응답 본문 전문 동등성을 유지한다. -- AC-4.
- [x] `frontend/src/session/useSessionState.ts` + `frontend/src/session/reauthenticate.ts` -- `reauthRequired`를 읽어 게이트 값 하나를 더하고, 표시된 세션에서 CSRF 토큰을 보존하고, 재인증 시작 호출(CSRF 헤더·`same-origin`·중복 제출 차단·성공 시 인가 경로로 최상위 이동)을 `signOut.ts`와 같은 모양으로 만든다. -- 표지는 URL이, 상태는 서버가 소유한다는 기존 분업을 유지한다.
- [x] `frontend/src/content/auth.ts` -- 재인증 엔드포인트 상수, 표지 둘(취소·실패)을 `AuthNoticeKind`·`URL_NOTICE_KINDS`·`NOTICES` 세 곳에 함께 더하고, 재인증 안내 카피와 카피 표류 방지 문자열을 더한다. 고지 문구와 `복리 계획 비교`를 인접 배치한다. -- 표지 집합이 한 목록이어야 조용히 무시되는 값이 생기지 않는다.
- [x] `frontend/src/components/ReauthPanel.tsx` (+ `.module.css`) -- 두 인증 화면이 공유하는 재인증 패널. 안내·표지·세 제공자 버튼(고정 순서·동등 위계·최소 44×44px)·로그아웃 버튼을 담고 기존 토큰만 쓴다. 연결된 제공자를 묻지 않는다. -- 표시된 세션이 처음으로 스스로 나갈 수 있게 되는 자리이기도 하다.
- [x] `frontend/src/screens/OnboardingScreen.tsx` + `SignedInScreen.tsx` -- 게이트의 새 값에서 패널을 렌더한다. 레이아웃 CSS 복제는 손대지 않는다. -- 중복 해소는 별도 목표다.
- [x] `frontend/src/components/ReauthPanel.test.tsx` + 두 화면 시험 + `App.test.tsx` -- 패널의 접근 가능한 이름·키보드 도달·표지별 문장·시작 호출의 토큰 헤더·중복 제출 차단·저장소 미사용과, 두 화면의 새 게이트 가지를 단정한다. 기존 게이트 4축을 줄이지 않는다. -- 패널 렌더는 이 층이 소유한다(표시된 세션은 스택에서 만들 수 없다).
- [x] `deploy/compose/verify-same-origin.sh` -- 인증된 세션의 재인증 왕복 절(시작 204 → 인가 → 콜백 → 회전 → 목적지 복귀 → 세션 행 1개)과 CSRF 없는 시작 403·미지 제공자 404 탐침을 더하고, 기존 완주 가드 형태를 따르고, 누출 검사에 재인증 왕복의 `code`·`state`를 넣는다. `set -o pipefail`에서 `생산자 | grep -q`를 쓰지 않는다. -- HTTP 층에서 재인증 계약을 보는 유일한 자리다.
- [x] `DEVELOPMENT.md` + `_bmad-output/implementation-artifacts/epic-1-context.md` -- 재인증 표면·제공자별 최근 인증 계약 표·`dev-oidc` 스텁의 `auth_time`을 적고, `## Goal`의 다음 착수 지점을 1.9로 맞춘다. `CLAUDE.md`는 건드리지 않는다(머지 뒤 별도 docs PR이 소유한다). -- 같은 사실이 두 곳에서 갈리지 않게 한다.

**Acceptance Criteria:**

- Given `develop`의 Story 1.1~1.7 계약, when 이 스토리의 변경을 얹으면, then `GET /api/nope` 404·`POST /api/status` 405·`POST /api/auth/session` 403·반복 로그아웃 403·오류 본문 전문 동등성·보안 헤더 5개·CSP 전문·캐시 헤더 두 갈래·세 제공자 로그인 왕복이 모두 그대로 통과한다.
- Given 미인증 세션과 폐기된 세션, when `GET /api/auth/session`을 부르면, then 응답 본문이 이 스토리 전과 **글자까지 같다**(새 필드가 나가지 않는다).
- Given 재인증에 성공한 세션, when 이전 쿠키로 세션을 확인하면, then 미인증이고 세션 행은 1개이며 새 쿠키로는 인증된 상태가 보인다.
- Given `identity` 모듈, when 이 스토리가 끝나면, then 공개 API 시그니처·내부 코드·스키마·마이그레이션이 바뀌지 않았고 `ModuleBoundaryTest`가 경계와 미노출을 그대로 단정한다.
- Given 재인증 왕복을 지난 저장소, when `identity_account`와 `identity_social_link`를 검사하면, then 재인증 전후로 행 수와 내용이 같다(성공·실패 어느 쪽에서도 늘지 않는다).
- Given 재인증 패널, when 키보드만으로 조작하면, then 세 버튼과 로그아웃 버튼이 문서 순서로 `Tab` 도달되고 각각 최소 44×44px이며 전역 포커스 링이 보이고 axe WCAG 2.2 AA 위반이 0건이다.
- Given 브라우저 경계, when 재인증 왕복 전후를 검사하면, then `localStorage`·`sessionStorage`·IndexedDB 항목이 0건이고 외부 origin 요청이 0건이며 `auth_time`·`code`·`state`·세션 ID가 응답·로그 어디에도 남지 않는다.
- Given 실연동, when 이 스토리가 끝나면, then 세 제공자의 실제 `auth_time` 반환은 확인되지 않은 상태이며 에픽은 **실연동 미검증**을 유지한다. 네이버는 공표된 증거가 없어 실연동에서 fail-closed될 수 있고 그것이 계약이다.

## Spec Change Log

- 2026-09-22 첫 구현. frozen 블록은 바꾸지 않았다. 아래 열 건은 구현 중에 확정한 결정이고 마지막 하나는 전량 검증 결과다.

  - **성공 핸들러의 재인증 분기가 주체를 다시 쓴다(Code Map의 "주체 교체도 하지 않는다"와 다르다).** `AbstractAuthenticationProcessingFilter`는 성공 핸들러를 부르기 **전에** 자기 인증 결과를 `securityContextRepository`로 저장한다(배포된 소스로 확인). 그 결과는 ID Token과 claim을 들고 있는 `OAuth2AuthenticationToken`이므로 그대로 두면 세 가지가 함께 깨진다 — 검증 직후 버려야 할 ID Token이 PostgreSQL 세션 행에 남고, 세션 행의 `principal_name`이 내부 `user_id`에서 제공자 `sub`으로 갈아타 `SessionGuard`의 사용자 단위 조작이 그 세션을 영원히 찾지 못하며, `carriesEstablishedPrincipal`이 거짓이 되어 뒤이은 실패 경로가 인증된 세션을 지운다. Code Map이 말한 "주체 교체"는 **사용자를 바꾸는 것**이고 여기서 하는 것은 같은 `user_id`를 다시 쓰는 것이라 frozen 블록의 "세션의 사용자를 바꾸지 않는다"와 어긋나지 않는다. 로그인 경로와 본체를 하나로 합쳤다(`writeEstablishedPrincipal`).
  - **인증 흐름 경계가 콜백까지 본다(`authorizationStartFilter` → `authenticationFlowFilter`).** 이 스토리 전에는 인증된 세션이 진행 중인 authorization request를 가질 수 없었다 — 시작이 그 필터에서 거절됐으므로 콜백에는 대조할 `state`가 없었고, 그래서 콜백을 보지 않아도 계정 갈아타기 경로가 없었다. 재인증이 그 전제를 깬다. 무장이 만료된 뒤(5분) 도착한 콜백은 `consume`이 빈 값을 돌려주므로 성공 핸들러의 **로그인 분기**로 들어가고, 그때 제공자가 다른 계정의 `sub`을 돌려주면 `establish`가 새 계정을 만들어 그 세션이 갈아탄다. 인증된 세션에 도착한 무장 없는 콜백을 검증 앞에서 접어 그 창을 닫았다. 필터 이름도 하는 일에 맞췄다. 미인증 콜백의 판정은 한 글자도 바뀌지 않는다.
  - **검증을 통과한 뒤 거절된 재인증은 세션 ID가 회전한 상태다.** 세션 고정 방어(`sessionFixation().changeSessionId()`)가 성공 핸들러보다 앞에서 돌기 때문이고, 우리가 취소할 수 있는 것이 아니다. 같은 302 응답이 새 쿠키를 함께 실어 보내므로 사용자는 로그인 상태로 남고 사라지는 것은 회전 전 식별자 하나뿐이다 — "세션 무변경"이 성립하는 축은 행의 생존·주체·계정 상태이며 식별자는 오히려 바뀌는 편이 안전하다. 취소와 `state` 불일치처럼 인증이 확정되기 전에 끝난 실패에서는 회전이 일어나지 않는다. `verify-same-origin.sh`의 재인증 절이 회전 뒤의 쿠키·토큰으로 두 전역을 갱신하는 이유도 이것이다.
  - **제공자 공식 마크를 공유 컴포넌트로 옮겼다(`components/ProviderMark.tsx`).** 패널이 두 번째 소비자가 되면서 선택지가 둘이었다 — 공식 애셋에서 추출한 경로 데이터(카카오는 원본 래스터를 부화소 정밀도로 추적해 복원한 기하다)를 복사하거나, 한 곳으로 옮기거나. 복사하면 한쪽만 고쳐지는 날 같은 서비스가 두 가지 로고를 렌더하고 그중 하나는 가이드가 금지하는 "변형된 로고"다. 크기 규칙도 함께 옮겼다(`ProviderMark.module.css`). 진입 화면의 렌더 결과(DOM 모양 `svg`+`span`, 계산된 크기·색)는 그대로이며 E2E 36개가 통과한다. 반면 **제공자 버튼의 규칙 자체는 복제했다** — 값은 모두 같은 토큰을 참조하므로 브랜드 값이 두 벌이 되지는 않고, 그 중복을 닫는 것은 frozen 블록이 Never로 미룬 "두 인증 화면의 레이아웃 CSS 중복 해소"와 같은 목표다.
  - **재인증 시작 실패는 클라이언트도 `reauthFailed` 표지를 만든다.** Code Map은 표지 둘을 서버가 만드는 값으로 적었지만, 무장 요청 자체가 성립하지 않은 경우(403·네트워크 단절·시한 초과)는 콜백을 지나지 않아 서버가 그 표지를 만들 자리가 없다. 표지 없이 두면 사용자는 아무 설명 없이 같은 화면에 남는다. 생산자는 `content/auth.ts`의 `reauthNoticePath` 하나이고 목적지는 인증 화면이 아니라 **지금 경로**다 — 이 표지를 보는 사람은 이미 로그인한 상태다.
  - **패널의 진행 상태를 화면이 소유한다(`session/useReauthentication.ts`).** `useSignOut`과 같은 배치이고 같은 이유다 — 진행 문구를 말하는 자리는 화면당 하나뿐인 `role="status"` 영역이고 그 영역은 본문에, 버튼은 패널에 있다. 두 곳이 같은 값을 보려면 상태가 그 위에 있어야 한다. 표시된 세션의 `aria-busy`는 거짓이다: 게이트가 `reauth-required`인 상태는 갱신 중이 아니라 다음 행동을 기다리는 자리이고, 참으로 두면 보조기술에 "지금 바뀌는 중"이라고 잘못 말한다.
  - **`DevOidcStub`의 기본 계획이 `auth_time`을 싣는다.** 정상 흐름은 "방금 인증했다"이므로 그것이 기본이어야 하고, 기본이 없으면 재인증 성공 경로를 어떤 시험도 지날 수 없다. 일반 로그인 왕복에도 같은 claim이 실리며 그것은 OIDC Core가 `max_age`와 함께 요구하는 모양 그대로다. 초 단위 정수로 싣는다 — `Instant`를 그대로 담으면 Nimbus가 다른 표현으로 직렬화해 `OidcIdTokenDecoderFactory`의 instant 변환기가 읽지 못한다. 받은 `max_age`·`prompt`·`claims`는 기록만 하고 행동을 바꾸지 않으며, 다음 인가 요청이 앞의 값을 물려받지 않는다(물려받으면 "일반 로그인에는 얹지 않는다"를 보려는 시험이 통과할 수 없다).

  - **개발·CI 스텁이 `subject` 고정을 받는다(`dev_subject` 인가 파라미터).** 브라우저 왕복의 `subject`는 요청마다 새 UUID였고 그것이 "개발 스택의 왕복은 늘 신규 가입"의 근거였다. 그 상태에서는 **재인증 성공 경로가 개발·CI 스택에 존재할 수 없다** — 재인증은 검증된 식별자가 현재 사용자의 것인지를 묻는데 스텁이 매번 다른 사람을 주장하므로 `verify-same-origin.sh`의 왕복 절이 언제나 "미연결 식별자" 실패를 본다(첫 실행이 정확히 그렇게 실패했다). 값을 호출자가 주게 해서 리터럴은 저장소에 남기지 않고, 스크립트가 실행마다 다른 표지(`verify-subject-$$`)를 세 왕복과 재인증 왕복에 함께 보낸다. 이 예외가 스텁의 검사를 무르지 않는다 — PKCE 재계산·code 일회성·client 인증·`redirect_uri` 일치는 그대로이고, 바뀌는 것은 스텁이 주장하는 신원 하나이며 실제 제공자도 그 값을 스스로 정한다. 표면은 `dev-oidc` 프로필에만 있고 그 프로필은 실제 제공자 등록과 함께 활성일 수 없다.
  - **`AuthTokenValidationTest`의 두 단정이 목적지를 바꾼다.** 위 "인증 흐름 경계가 콜백까지 본다"의 직접적 결과다. `한_번_쓴_state는_다시_쓸_수_없다`와 `개설된_세션은_실패하는_콜백으로_지워지지_않는다`는 인증된 세션에 콜백을 던지는 시험이고, 그 요청이 이제 검증 앞에서 접히므로 목적지가 `/?auth=failed`에서 `/onboarding`으로 바뀐다. **두 시험이 실제로 고정하려던 것은 그대로 통과한다** — 세션 행 1개, 그 세션이 여전히 인증 상태, 계정·매핑 무증가. 기대값을 코드에 맞춰 무른 것이 아니라 이 스토리가 그 경로를 새로 소유한 것이며, 바뀐 쪽이 낫기도 하다: 이미 로그인한 사용자에게 "로그인을 끝내지 못했습니다"는 거짓 설명이었다.
  - **`AuthSessionExpiryTest`의 표시된 세션 본문 단정 셋을 갱신했다.** frozen 블록이 "`GET /api/auth/session`은 표시된 세션에만 `reauthRequired: true`와 토큰을 싣는다 … 이 스토리가 의도적으로 되짚는다"로 못박은 변화다. **폐기된 세션과 미인증 요청의 본문은 한 글자도 바뀌지 않았고** 같은 파일의 나머지 다섯 단정이 그것을 그대로 지킨다. 표시된 세션 쪽은 `authenticated:false`·`reauthRequired:true`·`destination:"entry"`·토큰 존재와 "폐기 본문과 다르다"를 함께 단정하도록 바꿨다.

  - **검증(전량 실측, Docker 있음).** 백엔드 `build` 221 시험 통과·경고 0건(`-Werror`)·jar 생성, `deploy/compose/verify-same-origin.sh` **582/582 통과 exit 0**(재인증 왕복 절 신규 47 단정 포함), `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080` 주입 E2E 48/48, preview E2E 36 통과·12 건너뜀, 프런트엔드 전 검사(typecheck·ESLint·stylelint·format:check·build·Vitest 184개), `bash .github/scripts/test-ci-scripts.sh` 147/147. 수동 검사 셋도 통과했다 — 실행 중인 스택의 `docker compose logs`에서 `auth_time` 0건·`max_age`·`prompt=`·`claims=` 0건(`reauthenticate`는 access log의 경로로만 5건 남고 쿼리는 없다), `grep -rn "auth_time" backend/src/main/java/com/bokrilog/`가 `DevOidcStub`과 `AuthSecurityConfig`의 파라미터 선언·문서 주석 외에 판정을 두지 않음, `git diff --stat develop -- identity/ db/migration/ deploy/nginx/ frontend/src/App.tsx`가 변경 0건. 프레임워크 동작 가정은 실행 전에 배포된 소스로 먼저 확인했다 — `AbstractAuthenticationProcessingFilter`가 `saveContext`를 성공 핸들러보다 앞에서 부르는 것, `AbstractSessionFixationProtectionStrategy`가 기존 유효 세션에 `changeSessionId`를 적용하는 것, `HttpSessionOAuth2AuthorizationRequestRepository`가 세션당 authorization request를 **하나만** 들고 새 시작이 앞의 것을 덮는 것, `OAuth2AuthorizationRequest.from(...)`이 attribute와 additionalParameters를 모두 복사하고 `build()`가 URI를 다시 만드는 것, `IdTokenClaimAccessor#getAuthenticatedAt`이 `ClaimConversionService`로 숫자 claim을 `Instant`로 옮기는 것, `OidcIdTokenDecoderFactory`가 `auth_time`에 instant 변환기를 등록하는 것.
  - **남은 것: 실연동은 여전히 미검증이다.** 세 제공자가 실제로 `auth_time`을 돌려주는지 확인되지 않았고 에픽은 **실연동 미검증**을 유지한다. 네이버는 공표된 증거가 없어 실연동에서 fail-closed될 수 있으며 그것이 AC-3이 요구한 결과다. 그리고 표시된 세션의 브라우저 동작(패널 렌더·키보드·axe)은 Vitest만 본다 — `SessionGuard`에 HTTP 표면이 없고 세션 속성은 Java 직렬화라 Playwright가 그 상태를 만들 수 없기 때문이며, 같은 HTTP 계약은 `verify-same-origin.sh`가 인증된 세션의 자발 재인증으로 본다.

- 2026-09-22 적대적 리뷰 세 층의 `patch` 판정 16건. frozen 블록은 바꾸지 않았고 설계 변경도 없다. 무게순으로, 열 건이 "검증이 아무것도 관측하지 않는 자리"였다.

  - **`verify-same-origin.sh`의 무장 단정이 항진명제였다.** `SELECT (count(*) > 0)::int FROM spring_session_attributes` = 1은 앞선 세 로그인이 남긴 `SPRING_SECURITY_CONTEXT`·목적지 속성 때문에 이미 참이라 `arm` 호출을 통째로 지워도 green이었다. 무장 전후의 속성 행 수 **델타**를 재고(정확히 +1), 콜백 뒤에 그 값이 무장 전으로 되돌아오는지도 함께 본다 — 소비가 성립하지 않으면 그 자리에서 드러난다.
  - **`AuthSessionExpiryTest`의 표시된 세션 본문 단정 셋이 조각 포함 검사로 약해져 있었다.** 그 파일의 일은 응답 본문의 모양을 고정하는 것이고 상위집합을 받아들이면 필드가 새어 나가도 통과한다. 토큰 값만 가리는 `maskToken`과 `REAUTH_REQUIRED_BODY` 상수를 두고 세 자리를 전문 동등성으로 되돌렸다(`verify-same-origin.sh`·`AuthSessionContractTest`와 같은 관용). 폐기·미인증의 `UNAUTHENTICATED_BODY` 전문 비교 다섯은 그대로다.
  - **무장 수명과 clock skew를 가르는 축이 없었다(`ReauthenticationFlowTest`, 신규).** `isLive`를 언제나 참으로, `CLOCK_SKEW`를 `Duration.ZERO`로 바꿔도 백엔드 전체와 검증 스크립트가 통과했다. Spring 없이 도는 순수 시험으로 무장 수명 경계 양쪽과 신선도 네 경계(창 안·창 끝·창+skew 안·창+skew 밖·skew 안의 미래·skew 밖의 미래)를 고정한다. **경계를 리터럴로 적는다** — 첫 초안은 상수에서 좌표를 유도했고 그러면 시험이 상수에 대한 항진명제가 되어 skew를 0으로 바꿔도 그대로 통과했다(실제로 그랬다). 대신 `정책_값은_SPEC이_소유한다`가 상수 셋을 직접 고정한다. 세 값의 변경은 SPEC의 Ask First이므로 조용히 바뀌어서는 안 되는 값이다.
  - **`confirmReauthentication`의 `decide(SENSITIVE)` 갈래가 답을 바꾸지 않았다(`AuthSessionServiceReauthenticationTest`, 신규).** `findUserIdBySocialIdentity`가 SQL에서 이미 활성 계정만 돌려주므로 삭제 전환 축의 실패는 조회 하나가 만들었고, 그 줄을 `return true`로 바꿔도 전 시험이 통과했다. 두 API를 직접 세워 "조회는 사용자를 돌려주는데 `SENSITIVE`가 `DENIED`"라는, 실제 저장소로는 만들 수 없지만 그 호출이 존재하는 이유인 조합을 고정한다. 타 사용자·미연결 축에서는 판정을 **묻지도 않는다**는 사실도 함께 본다. Spring 컨텍스트를 새로 가르지 않는다.
  - **무장된 세션의 취소가 아닌 실패 축이 없었다.** 실패 표지 판정이 언제나 취소를 돌려주게 바꿔도 통과했다(무장된 실패가 `access_denied` 하나뿐이었다). `callbackWithError(..., "server_error", ...)` 축을 더해 `?auth=reauthFailed`와 취소 표지의 부재를 함께 단정한다.
  - **두 화면이 URL 표지를 패널로 나르는 배선이 시험되지 않았다.** jsdom 기본 `search`가 빈 문자열이라 패널은 언제나 `notice={null}`로 렌더됐고, 그 prop을 `null`로 바꿔도 전부 통과했다. `App.test.tsx`의 `navigate` 관용으로 두 경로에 표지를 달아 렌더하고 문장을 단정한다(그 prop을 `null`로 바꾸면 이제 두 시험이 실패한다).
  - **`reauthCopyStrings`를 강제하는 자리가 없었다.** 유일한 소비자가 제목·고지를 `continue`로 건너뛰었다. 두 화면의 재인증 가지가 배열 **전체**를 단정하고, 패널 시험은 패널이 소유한 부분집합만 보며 제목·고지가 패널에 없다는 사실을 함께 고정한다.
  - **회전 여부를 가르지 않는 헬퍼를 갈랐다.** `sessionCookieOf(callback).orElse(cookie)`가 어느 쪽이든 받아들여, 변경 기록이 구분한 두 경우("검증 통과 뒤 거절은 회전한다"·"인증 확정 전 실패는 회전하지 않는다") 중 후자만 고정돼 있었다. `assertRotatedSessionSurvived`가 새 쿠키를 실제로 요구하고 회전 전 쿠키가 죽었음까지 본다.
  - **스텁 기록의 소비자가 손으로 만든 맵뿐이었다.** `OidcStubConfiguration.consent`·`reauthenticationParametersOf`를 더해, 제공자별 파라미터 축이 `Location` 파싱이 아니라 **제공자 자리에 도착한 기록**을 본다. 포함 검사도 전문 비교로 바꿨다 — 공표하지 않은 수단의 추가가 통과하지 않는다.
  - **무장 만료 뒤 도착한 콜백이 아무 설명 없이 튕겼다.** `peekIncludingExpired`를 더해 인증 흐름 경계가 "무장이 없었다"와 "무장이 있었는데 늦었다"를 가르고, 뒤의 것은 흐름만 버린 뒤 붙들어 둔 목적지로 `reauthFailed`와 함께 보낸다. 그 왕복을 보는 축도 함께 더했다(저장소의 무장 시각을 옮기는 `ageReauthenticationFlow` 헬퍼는 `ageLastAccess`와 같은 근거다).
  - **패널의 `notice` 타입을 좁혔다.** `AuthNoticeKind`를 그대로 받아 `/dashboard?auth=loggedOut`이 "로그아웃했습니다 … 다시 로그인해 주세요"를 아직 로그인해 있는 사용자에게 보여 줬다. `ReauthNoticeKind`(두 값)와 `reauthNoticeKindOf` 거름망을 두어 타입 검사가 두 화면에 거름을 강제한다.
  - **제공자 버튼과 로그아웃 버튼이 서로를 막는다.** 재인증 직후 로그아웃이 성립하면 세션이 사라진 뒤 인가 시작이 열려 재인증이 아니라 새 로그인이 된다. 교차 차단은 패널이 소유한다 — 두 조작의 상태를 함께 아는 유일한 곳이고, 각 훅은 자기 중복 제출만 막는다.
  - **재인증 표지가 보조기술에 연결되지 않았다.** 포커스가 가는 `h2`의 문구는 언제나 "본인 확인이 필요합니다"라 `?auth=reauthFailed`로 돌아온 스크린리더 사용자는 이유를 듣지 못했다. 그 제목이 표지 문단을 `aria-describedby`로 가리킨다(두 번째 live region을 만들지 않는다).
  - **인가 시작 URI의 제공자 segment 추출이 두 벌이었다.** 둘 다 같은 `armed.provider().equals(...)` 비교로 들어가므로 한쪽만 고쳐지면 한쪽은 통과시키고 다른 쪽은 파라미터를 얹지 않는 상태가 된다. `AuthSecurityConfig.authorizationStartProvider` 하나가 소유한다(`isLogoutRequest`·`isReauthenticationStart`와 같은 배치).
  - **거짓이 된 주석 여섯을 고쳤다.** `AuthNoticeKind`의 "서버가 만드는 닫힌 네 값"(`reauthFailed`는 양쪽이 만든다), `noticePath`의 "유일한 생산자", `noticeKindOf`의 "나머지 다섯"(일곱이다), `ClientNoticeKind`가 `reauthFailed`를 담지 않는 이유, `sessionGuardFilter`의 "예외가 둘"(셋이다), `AuthSessionExpiryTest`의 "표시 뒤에는 토큰이 실리지 않는다". `FRESHNESS_WINDOW`의 javadoc도 과거 쪽 실제 허용치가 창+skew(6분)이고 미래 쪽이 skew(60초)라는 사실을 적는다.
  - **`DevOidcStub`의 기록 필드를 필드 구역으로 옮겼다.** `consentRedirect`와 `token` 사이에 있어 이 파일의 필드-다음-메서드 배치를 깼다. 인스턴스당 "마지막 요청" 하나라 동시 인가 요청에서는 순서 의존이라는 점도 javadoc에 적었다.

  - **변이 확인.** 고친 축이 실제로 관측하는지 직접 확인했다 — `isLive`를 항상 참으로(2건 실패), `CLOCK_SKEW`를 `Duration.ZERO`로(3건 실패), `decide` 줄을 `return true`로(2건 실패), 재인증 표지 판정을 항상 취소로(1건 실패), 두 화면의 `notice`를 `null`로(2건 실패) 바꾼 뒤 각각 원복했다.
  - **검증(전량 실측, Docker 있음).** 백엔드 `build` **233** 시험 통과·경고 0건·jar 생성, `verify-same-origin.sh` **583/583 exit 0**(깨끗한 스택), `PLAYWRIGHT_BASE_URL` 주입 E2E 48/48, preview E2E 36 통과·12 건너뜀, 프런트엔드 전 검사(typecheck·ESLint·stylelint·format:check·build·Vitest **193**), `test-ci-scripts.sh` 147/147. 수동 검사도 그대로다 — 스택 로그에 `auth_time`·`max_age`·`prompt=`·`claims=`·`reauthFailed` 0건, `identity/`·`db/migration/`·`deploy/nginx/`·`App.tsx` 변경 0건, `auth_time` 판정이 `ReauthenticationFlow` 밖에 없음.

## Design Notes

**재인증을 두 번째 인증 경로로 만들지 않는다.** 같은 `oauth2Login` 왕복을 지나므로 PKCE·`state`·`nonce`·JWS·`iss`·`aud`·`azp`·`exp` 검증이 전부 그대로 적용되고, `AuthTokenValidationTest`의 18축을 복제할 이유가 없다. 재인증이 더하는 것은 **판정 두 개**뿐이다 — "이 식별자가 현재 사용자의 것인가"와 "제공자가 증명한 인증 시각이 신선한가". 별도 등록 id(`google-reauth` 같은)를 만드는 길은 막혀 있다: 두 등록이 같은 issuer로 해석되면 `rejectSharedIssuer`가 기동을 거부한다(Story 1.7이 고정한 계약).

**제공자별 최근 인증 계약 (epics.md가 요구한 표).**

| 제공자 | 보내는 파라미터 | 공표된 증거 | 증거 부재 시 |
|---|---|---|---|
| Google | `max_age=300` + `claims={"id_token":{"auth_time":{"essential":true}}}` | ID Token `auth_time`(문서), `prompt=login`은 **미공표** | 재인증 실패 |
| 네이버 | `max_age=300` | **없음**(공식 답변: claim 구성 문서 미제공) | 재인증 실패 |
| 카카오 | `max_age=300` + `prompt=login` | ID Token `auth_time`(문서·discovery), `prompt=login`(문서) | 재인증 실패 |

허용 clock skew는 셋 모두 60초다. 신선도 창은 5분이며 `max_age` 값과 같다 — 두 값이 갈리면 제공자가 통과시킨 인증을 우리가 거절하거나 그 반대가 된다.

**네이버가 fail-closed되는 것은 결함이 아니라 AC-3이 요구한 결과다.** 네이버는 `auth_time`을 공표하지 않았고 재인증을 강제하는 파라미터도 공표하지 않았다. AC-3은 "제공자별로 최근 인증을 신뢰할 수 있게 확인할 수 없다면 보안 조건을 완화하지 않고 재인증 실패로 처리한다"를 명시한다. 그래서 이 스토리는 네이버를 **시작 단계에서 막지 않는다** — 실제로 `auth_time`을 돌려줄 수도 있고, 막아 두면 그 사실을 실연동에서 확인할 기회 자체가 사라진다. 판정은 콜백의 claim 하나로 하고, 결과는 실연동이 알려 준다. 네이버 하나만 연결한 사용자가 1.10·1.11의 민감 작업을 하지 못하는 상태가 될 수 있으며, 그 해소는 실연동 증거를 가진 뒤의 일이다.

**`reauthRequired`가 Story 1.5의 계약을 되짚는 자리.** 1.5는 표시와 폐기를 응답으로 구분하지 않게 했고 그 근거를 "이 스토리에는 재인증 화면이 없으므로"라고 `SessionGuard` javadoc에 적었다. 1.8이 그 화면을 만든다. 구분하지 않으면 표시된 세션은 CSRF 토큰을 받지 못하고, 진입 화면의 로그인 버튼은 `authorizationStartFilter`에 거절당해 목적지로 되돌려지며, 목적지는 다시 미인증으로 답한다 — 되살릴 방법이 없는 무한 왕복이다. 구분을 열어도 공격자가 얻는 것은 "이 세션이 표시됐다"는 사실 하나이고, 회복에 필요한 것은 제공자 자격이라 그 사실만으로는 아무 일도 할 수 없다. 반대로 **폐기와 미인증의 응답은 그대로 둔다** — 그 둘에는 회복 경로가 없으므로 구분할 이유도 없다.

**무장은 시작에, 판정은 콜백에.** 목적지를 무장 시각에 붙들어 두는 이유는 콜백이 들고 오는 어떤 값도 목적지가 되지 않게 하기 위해서다. 제공자·사용자도 함께 붙들어 두므로 콜백에서 세 가지가 어긋나면(다른 제공자, 다른 사용자, 흐름 없음) 판정할 것도 없이 실패다. 일회성은 소비 시 제거로 성립하고, 무장 수명 5분은 `FLOW_SESSION_TIMEOUT_SECONDS`와 같은 근거다 — 왕복은 초 단위로 끝난다.

**재인증 성공은 아무것도 실행하지 않는다.** 이 스토리가 남기는 것은 세션의 재인증 시각 하나이고 HTTP로 나가지 않는다. 그 값을 5분·일회성 허가로 바꾸는 것은 1.9이며, 파괴적 작업의 최종 확인 화면은 1.10·1.11이 그 작업과 함께 만든다. AC-6의 "성공 콜백만으로 파괴적 작업을 실행하지 않는다"는 이 스토리에서 **실행할 작업이 하나도 없다**는 형태로 성립한다.

**표시된 세션을 스택에서 만들 수 없다.** `SessionGuard`에는 HTTP 표면이 없고 세션 속성은 Java 직렬화라 SQL로도 만들 수 없다(그 javadoc이 근거를 적어 두었다). 그래서 표시된 세션의 회복은 **백엔드 시험만** 볼 수 있고, `verify-same-origin.sh`는 인증된 세션의 자발 재인증으로 같은 HTTP 계약을 본다. Playwright는 패널을 렌더할 수 없으므로 패널의 접근성·키보드는 Vitest가 소유한다 — 두 인증 화면의 게이트 4축이 이미 그 층에 있다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 재인증·회전·스텁 시험 통과, 경고 0건(`-Werror`), jar 생성. Docker 데몬 필요(Testcontainers)
- `npm --prefix frontend ci && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test` -- expected: 전부 통과, 경고 0건. Node 24(`frontend/.nvmrc`)
- `npm --prefix frontend run test:e2e` -- expected: Story 1.1·1.4~1.7 회귀가 그대로 통과
- `deploy/compose/verify-same-origin.sh` -- expected: 재인증 왕복 절 포함 모든 단정 통과, exit 0
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 전부 통과. `ci.yml`을 바꾸지 않았으므로 무변경 통과여야 한다
- `git diff --stat develop -- backend/src/main/java/com/bokrilog/identity/ backend/src/main/resources/db/migration/ deploy/nginx/ frontend/src/App.tsx` -- expected: 변경 0건

**Manual checks:**
- `grep -rn "auth_time" backend/src/main/java/com/bokrilog/ | grep -v ReauthenticationFlow` -- expected: `DevOidcStub`과 파라미터 선언 외 0건(판정은 한 곳이다)
- 실행 중인 스택의 `docker compose logs`에서 `auth_time`·`code`·`state`·세션 ID 검색 -- expected: 0건
- 브라우저 개발자 도구에서 재인증 왕복 전후의 쿠키·저장소·외부 요청 -- expected: 쿠키 1개, 저장소 0건, 외부 origin 요청 0건

## Suggested Review Order

**재인증 계약 — 판정이 사는 한 파일**

- 무장 record 하나가 제공자·사용자·목적지·시각을 함께 붙든다. 넷 중 하나라도 어긋나면 실패다.
  [`ReauthenticationFlow.java:101`](../../backend/src/main/java/com/bokrilog/ReauthenticationFlow.java#L101)

- 최근 인증 판정. 부재·창 초과·skew 밖 미래가 모두 하나의 실패로 접힌다.
  [`ReauthenticationFlow.java:205`](../../backend/src/main/java/com/bokrilog/ReauthenticationFlow.java#L205)

- 제공자에게 보내는 `max_age`와 우리가 재는 창이 상수 하나에서 나온다.
  [`ReauthenticationFlow.java:88`](../../backend/src/main/java/com/bokrilog/ReauthenticationFlow.java#L88)

**흐름 경계 — 인증된 세션에 왕복을 여는 자리**

- 무장이 있을 때만 인증된 세션의 인가 시작이 열린다. 이 스토리 전에는 전부 거절이었다.
  [`AuthFlowHandlers.java:215`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L215)

- 무장이 만료된 뒤 도착한 콜백이 로그인 분기로 새지 않는다. 새면 같은 세션 행에 다른 계정이 심긴다.
  [`AuthFlowHandlers.java:251`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L251)

- 성공 핸들러가 `establish` **앞에서** 갈린다. 재인증은 계정 생성 경로를 지나지 않는다.
  [`AuthFlowHandlers.java:443`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L443)

- 재인증 실패는 진입 화면이 아니라 무장이 붙들어 둔 목적지로 수렴한다.
  [`AuthFlowHandlers.java:576`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L576)

**계정 판정 — `identity` 공개 API만으로**

- 조회가 현재 사용자와 같고 `SENSITIVE`가 허용일 때만 참. 매핑도 계정도 만들지 않는다.
  [`AuthSessionService.java:123`](../../backend/src/main/java/com/bokrilog/AuthSessionService.java#L123)

**제공자 차이 — 요청 파라미터 하나**

- 제공자가 공표한 최근 인증 수단이 여기 한 곳에 선언된다. 검증·판정은 셋이 공유한다.
  [`AuthSecurityConfig.java:505`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L505)

- 무장을 본 인가 요청에만 그 파라미터가 얹힌다. 상시로 얹으면 일반 로그인이 매번 재인증이 된다.
  [`AuthSecurityConfig.java:668`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L668)

**HTTP 표면**

- 표시된 세션에만 필드 둘이 실린다. 미인증·폐기 본문은 글자까지 그대로다.
  [`AuthSessionController.java:99`](../../backend/src/main/java/com/bokrilog/AuthSessionController.java#L99)

- 무장 엔드포인트. 목적지는 세션이 이미 가진 값이고 클라이언트는 보내지 않는다.
  [`AuthSessionController.java:228`](../../backend/src/main/java/com/bokrilog/AuthSessionController.java#L228)

**화면**

- 게이트 값 하나가 늘었다. 표시된 세션이 처음으로 미인증과 구분된다.
  [`useSessionState.ts:80`](../../frontend/src/session/useSessionState.ts#L80)

- 패널이 받는 표지가 재인증 둘로 좁다. 타입 검사가 두 화면에 필터를 강제한다.
  [`ReauthPanel.tsx:38`](../../frontend/src/components/ReauthPanel.tsx#L38)

- 무장 POST 뒤 최상위 이동. `next`를 붙이지 않는 것이 목적지 단일 출처의 클라이언트 쪽이다.
  [`reauthenticate.ts:32`](../../frontend/src/session/reauthenticate.ts#L32)

**개발·CI 스텁**

- `auth_time`이 기본으로 실린다. 어긋난 값은 시험만 주입한다.
  [`DevOidcStub.java:597`](../../backend/src/main/java/com/bokrilog/DevOidcStub.java#L597)

**검증**

- 수명·신선도·skew 경계를 Spring 없이 고정한다. 리터럴 오프셋이라 상수를 바꾸면 깨진다.
  [`ReauthenticationFlowTest.java:62`](../../backend/src/test/java/com/bokrilog/ReauthenticationFlowTest.java#L62)

- 두 identity API가 서로 다른 답을 주는 경우. 실제 저장소로는 만들 수 없는 조합이다.
  [`AuthSessionServiceReauthenticationTest.java:58`](../../backend/src/test/java/com/bokrilog/AuthSessionServiceReauthenticationTest.java#L58)

- AC-1~AC-6 전 축의 단일 소유자. 검증 실패 18축은 복제하지 않는다.
  [`AuthReauthenticationTest.java:105`](../../backend/src/test/java/com/bokrilog/AuthReauthenticationTest.java#L105)

- HTTP 층에서 재인증 왕복을 보는 유일한 자리. 무장은 속성 행 델타로 관측한다.
  [`verify-same-origin.sh:972`](../../deploy/compose/verify-same-origin.sh#L972)
