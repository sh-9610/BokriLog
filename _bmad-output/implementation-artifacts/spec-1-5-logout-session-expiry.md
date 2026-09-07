---
title: 'Story 1.5 — 안전하게 로그아웃하고 만료·의심 세션을 차단하기'
type: 'feature'
created: '2026-09-07'
status: 'done'
review_loop_iteration: 0
baseline_commit: '8f4fcaf48fd334a621901760db042c053766e082'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-4-google-sign-in.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 1.4가 들어오는 길을 열었지만 나가는 길이 없다. `AuthSecurityConfig.java:382`가 로그아웃을 `disable()`로 명시적으로 꺼 두었고 엔드포인트·버튼·쿠키 만료가 전부 부재하다. 세션 만료는 `spring.session.timeout: 30m` 한 줄로 존재하지만 그 경계에서 무엇이 일어나는지 단정하는 시험이 없고, Nginx는 캐시 응답 헤더를 **하나도** 설정하지 않아 로그아웃한 브라우저가 이전 인증 문서를 캐시·bfcache에서 복원할 수 있다. 내부 보안 신호가 세션 하나를 격리할 방법도 없다. Epic 1의 남은 스토리와 이후 모든 에픽의 사용자 소유 자원이 이 세션 경계를 상속한다.

**Approach:** Spring Security의 `logout()` DSL을 `POST /api/auth/logout`에 묶어 켜고, 세션 귀속 CSRF 토큰을 이미 있는 세션 확인 응답으로 클라이언트에 넘긴다. 의심 세션은 Spring Session이 소유한 `principal_name` 색인을 통해 사용자의 세션을 `reauth_required`로 표시하거나 폐기하는 **HTTP 표면 없는** 내부 bean이 다룬다. 브라우저 쪽은 Nginx의 `map`으로 계산한 `Cache-Control`과 로그아웃·만료를 구분하는 표지로 닫는다.

## Boundaries & Constraints

**Always:**
- 로그아웃은 `POST /api/auth/logout` 하나이며 Story 1.4가 고정한 `/api/auth/` 상태 변경 계약(세션 귀속 CSRF 토큰 + 동일 출처 `Origin`)을 예외 없이 통과해야 한다. 실패는 인증 여부를 드러내지 않는 403 `FORBIDDEN` 하나로 수렴한다.
- 세션 폐기는 세 가지를 함께 만족한다 — 서버 세션 행 삭제, `SecurityContext` 정리, 세션 쿠키 만료 지시. 셋 중 둘만 만족하는 상태를 로그아웃 완료로 보고하지 않는다.
- 어떤 세션 조작도 대상 `principal_name`의 행만 건드린다. 다른 사용자의 세션 행과 인증 상태는 보존된다.
- 의심 세션 상태 전환은 Java bean 표면만 갖는다. 일반 사용자 HTTP API·Actuator·개발 전용 엔드포인트로 노출하지 않으며 개발·CI는 그 bean을 직접 불러 상태를 만든다.
- 브라우저가 갖는 쿠키는 `BOKRILOG_SESSION` 하나뿐이다. CSRF 토큰은 응답 **본문**으로만 전달하고 쿠키·`localStorage`·`sessionStorage`·URL에 두지 않는다.
- 세션 수명은 두 축이다 — 유휴 30분(현행 `spring.session.timeout` 유지)과 절대 수명 12시간. 절대 수명은 세션 `CREATION_TIME`을 기준으로 세고 재요청·회전으로 늘어나지 않는다.
- 인증된 문서와 `/api` 응답은 캐시하지 않는다. 보안 응답 헤더 5개의 유일한 정의는 `deploy/nginx/bokrilog.conf`의 한 블록으로 남고, 캐시 헤더를 더하는 과정에서 그 5개가 하위 블록 상속으로 사라지지 않는다.
- 세션 ID·CSRF 토큰·`user_id`·`principal_name`·제공자 비밀은 응답 본문·`Set-Cookie` 외 헤더·구조화 로그·오류 문맥·지표 label에 남기지 않는다.
- 화면 카피는 `frontend/src/content/`가 소유하고 화면과 시험이 같은 문자열을 참조한다. 계정 존재 여부를 노출하지 않고 이메일·지원 확인으로 인증을 우회하는 경로를 만들지 않는다.

**Ask First:**
- `authorizeHttpRequests`에 인증 게이트를 켜는 것. 현재 `anyRequest().permitAll()`(`AuthSecurityConfig.java:372`)이 Story 1.2의 404·405 계약을 지탱하며 이 스토리는 보호할 사용자 소유 자원을 만들지 않는다.
- `spring_session` 스키마에 컬럼을 더하는 것(V3 마이그레이션). 세션 부가 상태를 Spring Session 속성으로 담을 수 없다고 판단되면 멈추고 묻는다.
- 위에 고정한 두 수명 값(유휴 30분·절대 12시간)을 다른 값으로 바꾸는 것.
- `identity` 공개 API에 메서드를 더하거나 `AccessDecision`에 값을 더해야 한다고 판단되는 경우.

**Never:**
- 재인증 흐름·세션 회전 UX·5분 action grant 구현(1.8·1.9). 이 스토리는 상태 전환 기계장치와 차단까지만 소유한다.
- 네이버·카카오(1.6·1.7). 제공자 측 로그아웃(RP-initiated logout) 호출과 제공자 세션 종료.
- 사용자에게 노출되는 계정 삭제 화면과 전체 삭제 orchestration(Epic 5).
- 와일드카드 SPA 폴백, `<meta>` CSP, 외부 CDN·폰트·SDK 반입, 브라우저 영속 저장소 쓰기.
- `identity.internal` 직접 호출, `identity`에 HTTP·서블릿 타입 반입, `identity`의 `spring_session` 결합 확대.
- 실패한 CI를 재실행으로 덮는 것. 기존 시험의 기대값을 코드에 맞춰 무르는 것.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 정상 로그아웃 | 인증된 세션 쿠키 + 유효 CSRF 토큰 + 동일 출처 `Origin`, `POST /api/auth/logout` | 204, 본문 없음. `spring_session` 그 행 0건, 속성 행도 0건. `Set-Cookie`가 `BOKRILOG_SESSION`을 빈 값으로 만료 지시 | N/A |
| 반복 로그아웃 | 위 직후 같은 쿠키·같은 토큰으로 재요청 | 403 `{"code":"FORBIDDEN",…}`. 상태 변화 0건, 새 세션 행 생성 0건, 새 인증·제공자 로그아웃 시작 0건 | 토큰이 세션과 함께 사라졌으므로 CSRF 단계에서 수렴. 안전한 무변경 거절 |
| CSRF 토큰 누락·오류·다른 세션 토큰 | 인증된 쿠키 + 잘못된(또는 없는) 토큰 | 403 `FORBIDDEN`. **세션이 살아 있다** — 그 쿠키로 이어지는 세션 확인이 계속 인증 상태 | 무변경 거절. 토큰 값을 응답·로그에 되비추지 않음 |
| 교차 출처 로그아웃 | 인증된 쿠키 + 다른 origin의 `Origin` 헤더, 또는 `Origin` 없음 | 403 `FORBIDDEN`, 무변경 | `SameOriginFilter`가 라우팅·CSRF보다 먼저 차단 |
| 미인증 로그아웃 | 쿠키 없음 | 403 `FORBIDDEN`. 세션 행 생성 0건 | 인증 여부를 응답 코드·본문으로 구분하지 않음 |
| 유휴 만료 후 접근 | `MAX_INACTIVE_INTERVAL` 경과한 세션의 쿠키로 `GET /api/auth/session` | 200 `{"authenticated":false,"destination":"entry"}` + `Cache-Control: no-store`. 이전 세션으로 어떤 사용자 데이터도 조회 불가 | 만료 행 정리는 Spring Session 소유. 응답은 미인증과 동일 모양 |
| 절대 수명 초과 | `CREATION_TIME`이 12시간을 넘은 세션(유휴 만료는 아님) | 그 요청에서 세션이 폐기되고 미인증 응답 + 쿠키 만료 지시 | 유휴 만료와 같은 외부 모양 |
| 관리상 폐기된 세션 | 세션 guard가 폐기한 세션의 쿠키 | 200 미인증 응답. 행 0건 | 동일 |
| 의심 세션(`reauth_required`) | guard가 그 상태로 전환한 세션의 쿠키 | 보호 접근 차단 — 미인증과 같은 응답(`authenticated:false`, `destination:"entry"`). **세션 행은 남는다**(1.8의 재인증이 회전시킬 대상) | 폐기와 응답으로 구분되지 않음 |
| 사용자 단위 폐기 | guard가 `user_id`로 그 사용자의 세션 전부 폐기 | 그 `principal_name`의 행만 0건 | `principal_name` 색인 사용. 다른 principal 행 보존 |
| 다른 사용자 보존 | 사용자 A가 로그아웃하거나 A의 세션이 폐기됨 | B의 세션 행·인증 상태·목적지 전부 불변 | N/A |
| 뒤로 가기·캐시 복원 | 로그아웃 뒤 뒤로 가기로 `/dashboard` | 문서가 캐시에서 복원되지 않고(`no-store`) 재요청된다. 미인증이므로 진입 화면 + 로그아웃·만료 표지. 이전 화면의 항목 문자열 0건 | bfcache 복원 경로는 `pageshow` 재확인이 덮는다 |
| 정적 자산 | `GET /assets/<hash>.js` | `Cache-Control: public, max-age=31536000, immutable` + 보안 헤더 5개 전부 그대로 | N/A |
| 로그아웃 뒤 목적지 | 204 수신 후 클라이언트 | `/?auth=loggedOut`으로 최상위 내비게이션, 로그아웃 완료 카피 | 204가 아닌 응답에서는 인증 상태를 주장하지 않고 진입 화면으로 수렴 |
| 만료 감지 | 화면이 인증을 관측한 뒤 재확인에서 미인증 | `/?auth=expired`, 만료 카피 | 냉시작 미인증(`/dashboard` 직접 진입)은 기존 `signedOut` 표지 유지 |
| 유일 제공자 접근 상실 | `/?auth=failed` 진입 화면 | Google 공식 계정 복구 경로 안내(외부 링크, 새 창 없음). 계정 존재 여부 미노출 | 이메일 확인·지원 담당자 확인 우회 경로 없음 |
| 인증 경로 밖 계약 | `POST /api/status`, `GET /api/nope`, `PUT /onboarding` | 405·404·405 그대로. 403으로 뒤집히지 않음 | Story 1.2·1.4 회귀 |

</frozen-after-approval>

## Code Map

**백엔드 — 인증 표면(루트 패키지가 소유)**

- `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java:382` -- `.logout(logout -> logout.disable())`. 주석 `:379`가 "로그아웃은 Story 1.5의 범위"라고 이 줄을 지정한다. **저장소 전체에서 로그아웃 관련 코드는 이 한 줄뿐이다.** DSL을 켜되 `logoutRequestMatcher`로 `POST /api/auth/logout`에 묶어야 한다 — 그냥 켜면 `/logout` HTTP 표면이 새로 생긴다.
- `AuthSecurityConfig.java:352-448` -- 유일한 `SecurityFilterChain`. `:372` `anyRequest().permitAll()`과 그 근거(`:362-371`, Story 1.2의 404·405 계약)는 **읽기 전용**이다.
- `AuthSecurityConfig.java:389-393` -- `sessionManagement(... changeSessionId())`. 로그인 회전의 유일한 선언. 로그아웃은 회전이 아니라 폐기다.
- `AuthSecurityConfig.java:395-397` -- `HttpSessionCsrfTokenRepository`(세션 귀속). 쿠키 저장소를 쓰지 않으므로 **클라이언트가 토큰을 얻는 경로가 현재 없다.** 이 스토리가 그 경로를 만든다.
- `AuthSecurityConfig.java:86-89` -- `PROTECTED_PATH_PREFIX = "/api/auth/"` + 비-safe 메서드 집합. 로그아웃이 자동으로 이 보호 아래 들어온다.
- `AuthSecurityConfig.java:402`, `:417-421`, `:450-470`, `:472-494` -- CSRF 매처, `SameOriginFilter` 등록(CsrfFilter 앞), 두 필터가 공유하는 판정. 읽기 전용 — 로그아웃 경로를 여기서 예외 처리하지 않는다.
- `AuthSecurityConfig.java:408-416` -- 401·403 응답을 만드는 entry point·access denied handler. 로그아웃 실패가 통과할 경로.
- `AuthSecurityConfig.java:375`, `:387` -- 상태 API의 캐시 헤더 부재 근거, `requestCache.disable()`. 로그아웃 후 복귀는 `ALLOWED_DESTINATIONS` 닫힌 목록만 쓸 수 있다.
- `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java:314-320` -- `converge`: `SecurityContextHolder.clearContext()` → `discardSession` → 리다이렉트. **재사용 본체.**
- `AuthFlowHandlers.java:343-358`, `:371-384` -- `discardSession`. 인증 여부 판별(`carriesEstablishedPrincipal`) 후 `invalidate()` + 두 세션 ID를 `SessionRepository#deleteById`로 삭제. 로그아웃은 이 판별의 **반대 조건**(인증된 세션일 때만 지움)이 필요하다.
- `AuthFlowHandlers.java:270-278` -- `principal_name`에 내부 `user_id` 문자열을 넣는 한 쌍이 만들어지는 곳. 사용자 단위 세션 조작의 전제.
- `AuthFlowHandlers.java:109`, `:149-151`, `:202`, `:214-219`, `:263` -- 인증 전 흐름 세션의 5분 상한과 로그인 성공 시 복원. 절대 수명 상한을 넣을 때 이 두 수명과 충돌하지 않아야 한다.
- `AuthFlowHandlers.java:121`, `:136` -- `SessionRepository`만 주입돼 있다. 사용자 단위 조회는 `FindByIndexNameSessionRepository`가 필요하며 Spring Session JDBC가 이미 제공한다(새 의존 없음).
- `AuthFlowHandlers.java:74`, `:425-433` -- `ALLOWED_DESTINATIONS`와 `destination` 값 집합(`onboarding`/`dashboard`/`entry`). 의심 세션은 새 값을 만들지 않고 `entry`로 수렴한다.
- `backend/src/main/java/com/bokrilog/AuthSessionController.java:47`, `:49-70` -- `record SessionState(boolean authenticated, String destination)`와 핸들러. 미인증도 **항상 200**이고 `Cache-Control: no-store`가 `:68`에 이미 있다. CSRF 토큰 필드를 더할 자리이며 `:28-30`의 금지 목록(`user_id`·subject·토큰)은 그대로 지킨다.
- `backend/src/main/java/com/bokrilog/ApiErrorHandler.java:47-68`, `:148-165` -- code 상수와 `errorFor` 매핑. **401=`UNAUTHENTICATED`·403=`FORBIDDEN`이 이미 갈려 있어 새 code가 필요하지 않다.** `:43-45`가 경고하듯 이 문자열은 `bokrilog.conf`와 `verify-same-origin.sh`에 리터럴로 중복된다.
- `backend/src/main/resources/application.yml:43-69` -- 세션 쿠키 설정 전량(`name BOKRILOG_SESSION` `:50`, `secure` `:66`, `same-site lax` `:69`). `DefaultCookieSerializer`를 만드는 코드는 저장소에 없고 Boot가 이 값을 매핑한다.
- `application.yml:103-109` -- `spring.session.timeout: 30m`. `:104` 주석이 "세션 만료·의심 세션 차단의 나머지는 Story 1.5가 소유한다"고 이 지점을 지정한다. `jdbc.initialize-schema: never`(`:109`)라 스키마 변경은 V3 마이그레이션 + `migrate` 프로필뿐이다.
- `backend/src/main/resources/db/migration/V2__spring_session.sql:24-36`, `:43-44` -- 세션 테이블 컬럼과 색인 3개(`SESSION_ID` unique·`EXPIRY_TIME`·**`PRINCIPAL_NAME`**), 속성 행의 `ON DELETE CASCADE`. `reauth_required` 같은 컬럼은 없고 부가 상태는 Java 직렬화 `ATTRIBUTE_BYTES`에만 들어간다 → SQL로 읽고 쓰지 않는다.
- `backend/src/main/java/com/bokrilog/SchemaValidationConfig.java:28-43` -- 기동·전역 정책은 루트 패키지가 소유하고 프로필별 bean 선택은 `ApplicationContextRunner`가 단정하는 배치 선례. 세션 guard가 따를 모양.

**백엔드 — `identity`(읽기 전용 계약)**

- `backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java:195-199` -- `deleteSessionsOf`: `DELETE FROM spring_session WHERE principal_name = :principalName`. `identity`가 세션 테이블에 닿는 **유일한** 곳이며 `deferred-work.md`에 결합으로 올라가 있다. 이 스토리는 이 결합을 **확대하지 않는다** — 세션 폐기 포트는 루트 패키지에 만들고 identity 쪽은 건드리지 않는다.
- `backend/src/main/java/com/bokrilog/identity/AccountLifecycleApi.java:65` -- `AccessDecision decideSessionCreation(UUID)`. 세션 개설 판정의 유일한 진입. 로그아웃·만료는 계정 상태를 바꾸지 않으므로 이 API를 부를 이유가 없다.
- `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java:44-58`, `:60-87` -- `identity.internal` 외부 접근 금지와 `identity`의 HTTP·서블릿 의존 금지(`org.springframework.http..`까지 포함, 애노테이션도 의존으로 잡힘). 세션 guard를 `identity`에 두면 이 시험이 깨진다.
- `backend/src/main/java/com/bokrilog/identity/AccessDecision.java:6-8`, `:13-16` -- 두 값(`ALLOWED`·`DENIED`)뿐이며 사유 조합으로 계정 존재를 복원하는 방향을 명시적으로 거부한다.

**백엔드 — 시험 기반**

- `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java:114-193` -- OIDC 왕복 도구(`startAuthorization`·`issueCode`·`callback`·`signIn`·`sessionState`·`get`·`send`). `send`가 임의 메서드·헤더 쌍을 받으므로 로그아웃 요청에 그대로 쓴다.
- `AuthFlowTestBase.java:213-234` -- `sessionCookieOf`(회전 관측), `setCookieOf`(**`Set-Cookie` 전문** — `Max-Age=0`·`Expires` 단정 진입점), `cookieValue`가 `:232`에서 **빈 값 = 만료 지시**를 명시적으로 구분한다. "쿠키 즉시 만료"는 이 두 헬퍼 조합으로 고정하는 것이 준비된 관용.
- `AuthFlowTestBase.java:260-287` -- `accountCount`·`socialLinkCount`·`sessionCount`·`userIdOf`·`jdbc`·`accountLifecycle`·`accountDeletion`. `:82-90` `resetStore()`가 세션 2표 + identity 3표를 TRUNCATE. **`principal_name`별 행 수를 세는 헬퍼는 없다** — 더할 자리.
- `backend/src/test/java/com/bokrilog/AuthSessionContractTest.java:223-241` -- "인증된 세션 확인은 상태와 목적지 종류만 돌려준다" + `Cache-Control: no-store` 단정. CSRF 토큰 필드를 더하면 이 시험의 단정 목록을 함께 갱신해야 한다.
- `AuthSessionContractTest.java:243-307` -- Origin·CSRF 거절 4건과 "CSRF 검사는 인증 경로 밖의 오류 계약을 바꾸지 않는다". **CSRF 토큰을 실제로 얻어 통과하는 시험은 아직 없다** — 현재는 "토큰 없음 → 403"만 고정돼 있다.
- `backend/src/test/java/com/bokrilog/StatusApiTest.java:271-281` -- `GET /api/status`의 `Cache-Control`이 **비어 있음**을 단정한다. 전역 `no-store`를 백엔드에 켜면 깨진다 → 캐시 계약은 Nginx 층이 소유해야 한다.
- `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java:35`, `:55-59` -- `postgres:18.6-alpine`, 시험만 `Flyway::migrate`.

**배포·검증**

- `deploy/nginx/bokrilog.conf:87-94` -- CSP·`X-Content-Type-Options`·`Referrer-Policy`·`X-Frame-Options`·`X-Request-Id`를 `always`로 내는 **단일** `add_header` 블록. `:70-71` 주석이 못 박는다 — 하위 블록에 `add_header`를 하나라도 두면 상위 5개가 전부 사라진다. 파일 전체에 `Cache-Control`·`Pragma`·`Expires`·`expires`가 **하나도 없다**.
- `bokrilog.conf:32-39` -- `map $request_uri $request_path`로 쿼리 문자열을 지우는 기존 `map` 관용. 캐시 값 분기가 따를 모양이다.
- `bokrilog.conf:128`, `:132`, `:153-156`, `:171-178`, `:184-198` -- location 5개: `= /api`, `^~ /api/`, `^~ /api/auth/callback/`(`error_log … crit`), `~ ^/(onboarding|dashboard)$`(경로 허용 목록 + 405 가드 + `try_files /index.html =404`), `/`(정적 + `=404`).
- `bokrilog.conf:213-217`, `:225-252` -- `error_page` 매핑과 명명 location 5개(JSON 오류 본문).
- `deploy/compose/verify-same-origin.sh:455`, `:458-482` -- `expected_csp` 상수와 `assert_security_headers()`(CSP 전문 동등성 + 나머지 3개 + `Server` + `X-Request-Id`). 모든 응답 그룹이 이 함수를 부르므로 여기에 한 줄을 더하면 전 그룹에 전파된다.
- `verify-same-origin.sh:262-330`, `:336-482` -- `fetch`(전역 `last_status`/`last_headers`/`last_body`), `header_value`, `psql_value`, `psql_exec`, `session_cookie_value`, 단정 함수 13개(`assert_equals`·`assert_body_contains`·`assert_status_in`·`assert_body_lacks`·`assert_rows`·`assert_value_contains`·`assert_value_lacks` 등).
- `verify-same-origin.sh:760-870` -- "스텁 제공자 성공 왕복" 그룹(인가 시작 → 동의 → 콜백·세션 회전 → 세션 확인). `:853-854`가 **유일한 `Cache-Control: no-store` 단정**이고 `:857-863`이 `assert_rows`로 저장소를 확인한다. 로그아웃 왕복이 이 그룹 뒤에 붙는다.
- `verify-same-origin.sh:719-730`, `:871-907` -- `POST /api/auth/session`이 403 `FORBIDDEN`(405가 아님)으로 고정된 단정, 그리고 here-doc `ALLOWED_PATHS`/`REJECTED_PATHS` 반복 관용.
- `verify-same-origin.sh:1012-1105` -- 로그 누출 검사(요청 ID 상관·경로 보존·`code`·`state` 누출·`error_log` 경로). 새 표지가 로그로 새지 않는지 확인할 자리.
- `verify-same-origin.sh:75`, `:1125-1142` -- 종료 코드 규약 0·1·2와 `--keep-stack`.
- `deploy/compose/compose.yaml:72-87`, `:163-167` -- `migrate` 프로필, 유일한 게시 포트 `127.0.0.1:18080`, 읽기 전용 마운트 2개.

**프런트엔드**

- `frontend/src/App.tsx:21-31` -- 닫힌 경로 맵(라우터 없음). `ONBOARDING_PATH`·`DASHBOARD_PATH`·그 외→`EntryScreen notice={noticeKindOf(search)}`. 경로 집합은 `content/auth.ts:30-32`와 `bokrilog.conf:171`과 **셋이 같아야** 한다(이 스토리는 경로를 더하지 않는다).
- `frontend/src/session/useSessionState.ts:19-58`, `:91-163` -- 상태·목적지 타입, `SESSION_ENDPOINT` fetch(`credentials:'same-origin'`, `cache:'no-store'`, 10초 시한), 실패 전량을 `UNAUTHENTICATED` 하나로 수렴, 경합 방지, **`pageshow` + `event.persisted` 재확인이 이미 있다**(`:142-157`, UX-DR22). `:166`이 재확인 함수를 외부에 노출하지 않으므로 로그아웃 후 상태 전이를 위해 반환 계약을 넓혀야 한다. **로그아웃 코드는 전무.**
- `useSessionState.ts:179-201` -- `useSessionGate(expected)`가 `DESTINATION_PATHS`로 경로를 찾아 `replaceLocation()`을 부르고 게이트를 `redirecting`으로 둔다. 만료 감지(인증→미인증 전이)를 이 훅이 관측한다.
- `frontend/src/session/navigation.ts:11-13` -- `replaceLocation` 하나. "최상위 내비게이션의 유일한 자리"이며 시험은 `vi.mock`으로 모듈째 대체한다.
- `frontend/src/screens/SignedInScreen.tsx:26-81` -- **`<button>`이 하나도 없다.** 구조: skipLink → header/brand → `main tabIndex={-1}` → intro(h1 + disclaimer) → `role="status"` live region **정확히 1개** → `ready`일 때만 section. `:29-31`이 미인증에서 `<EntryScreen notice="signedOut" />`으로 조기 반환. `OnboardingScreen.tsx:24-79`가 동일 골격.
- `frontend/src/screens/EntryScreen.tsx`, `EntryScreen.module.css:161` -- `.googleButton`이 저장소의 유일한 버튼 스타일 선례. 복구 안내가 붙을 화면.
- `frontend/src/content/auth.ts:24-38`, `:74-92`, `:94-155` -- 경로·파라미터 상수, `AuthNoticeKind = 'cancelled'|'failed'|'signedOut'` + `noticeKindOf`(닫힌 집합, 그 밖은 `null`), `authContent` 객체와 화면별 `*CopyStrings` 배열. `signedOut`은 "로그인 상태가 아닙니다"로 **만료·방금 로그아웃함을 구분하지 않는다.** `:8-13`의 카피 제약(완전문, 보장 표현 금지, 금융값 금지, 계정 존재 비노출).
- `frontend/src/styles/tokens.css` -- `--color-destructive`·`--color-on-destructive`, `--component-confirmation-dialog-*`, `--component-operator-control-*`, `--space-target-min`, `--space-focus-*`가 **이미 있다.** 로그아웃 버튼에 새 토큰을 만들 필요가 없다. 경고 전용 토큰은 없고 가장 가까운 것이 `--color-trust-review*`·`--color-info*`.
- `frontend/src/test/setup.ts:41-73`, `:103-143`, `:151-161` -- `globalThis.fetch`를 전역 거부로 바꾸고 **URL이 `SESSION_ENDPOINT`와 정확히 같을 때만** 통과시킨다(메서드 무관). `stubSessionRequest`·`getObservedSessionRequests`·`getObservedRequests`·`getObservedStorageEntries`. **로그아웃 POST는 이 탈출구를 통과할 통로가 없다** — 탈출구를 (메서드, URL) 쌍으로 일반화해야 한다.
- `frontend/src/screens/SignedInScreen.test.tsx:17`, `:42-144`, `:168-187` -- `vi.mock('../session/navigation')` 관용, 로컬 `jsonResponse`/`authenticated` 헬퍼, 단정 축 10개(확인 중 상태 비주장·카피 전량·UX-DR23 인접·금융값 부재·미인증 복귀·요청 1회·저장소 0건·목적지 불일치 시 `replaceLocation`·skipLink→랜드마크·`role="status"` 정확히 1개). 로그아웃 시험도 같은 축을 따른다.
- `frontend/src/App.test.tsx:15-28` -- `window.history.pushState`로 경로를 옮기고 `afterEach`에서 `/`로 복귀.
- `frontend/e2e/auth-entry.spec.ts:27`, `:253-318` -- `PLAYWRIGHT_BASE_URL`이 있을 때만 도는 "스텁 제공자 성공 왕복" 블록. `:298-302` 주석이 "인증된 화면 마크업을 보는 유일한 자리"라고 명시하므로 **로그아웃 버튼의 a11y·포커스·뒤로 가기 시험은 여기에 붙을 수밖에 없다.**
- `frontend/e2e/auth-entry.spec.ts:119-134`, `:167-241` -- 저장소 0건 단정, 미인증 로그인 상태 경로 4변형 + axe WCAG 2.2 AA 0위반, `/dashboard`의 데이터 요청 pathname이 정확히 `['/api/auth/session']`. 로그아웃 요청이 더해지면 이 목록을 갱신해야 한다.

## Tasks & Acceptance

**Execution:**

- [x] `backend/src/main/java/com/bokrilog/SessionGuard.java` -- 세션 상태 전환의 내부 표면을 새로 만든다: 사용자 단위 폐기, 세션 단위 폐기, `reauth_required` 표시, 그리고 요청 경로가 부를 판정 하나. `FindByIndexNameSessionRepository`로 `principal_name` 색인을 쓰고 `reauth_required`는 Spring Session 속성으로 담는다(새 컬럼·새 SQL 없음). **HTTP 표면을 만들지 않는다** — 컨트롤러·`@Endpoint`·서블릿 타입 없음. -- AC-3의 "일반 사용자 HTTP API로 노출하지 않는다"와 `identity`의 세션 결합 확대 금지를 함께 만족하는 유일한 배치다.
- [x] `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java` -- `:382`의 `logout.disable()`을 `logoutRequestMatcher`로 `POST /api/auth/logout`에 묶은 DSL로 대체하고, 성공 응답을 204(리다이렉트 아님)로 두고, `anyRequest().permitAll()`·CSRF 매처·`SameOriginFilter` 순서는 건드리지 않는다. -- `logout()`을 그냥 켜면 `/logout` 표면이 새로 생기고, 게이트를 켜면 Story 1.2의 404·405 계약이 무너진다.
- [x] `backend/src/main/java/com/bokrilog/AuthSessionController.java` -- `SessionState`에 세션 귀속 CSRF 토큰 필드를 더해 클라이언트가 로그아웃을 보낼 수 있게 한다. `user_id`·subject·제공자 토큰 금지는 그대로 유지하고 `Cache-Control: no-store`(`:68`)를 그대로 둔다. 의심 세션·폐기 세션은 미인증과 같은 응답으로 수렴시킨다. -- 쿠키 저장소를 쓰지 않는 구성에서 토큰을 얻는 유일한 경로이며, 브라우저 쿠키를 하나로 유지한다.
- [x] `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` -- `discardSession`(`:343-384`)을 로그아웃과 만료가 함께 쓸 수 있게 정리하고, 세션 절대 수명 상한 검사와 `reauth_required`·폐기 판정을 요청 경로에 배선한다. 인증 전 흐름 세션의 5분 상한(`:109`·`:214-219`)과 충돌하지 않게 한다. -- 폐기 본체를 두 벌 만들면 "행 삭제·컨텍스트 정리·쿠키 만료" 셋 중 하나가 빠진 경로가 생긴다.
- [x] `backend/src/main/resources/application.yml` -- `:104` 주석이 이 스토리로 위임한 세션 만료 설정을 실제 값으로 채운다(유휴 30m 유지, 절대 수명 12h 신설). 자격정보 모양 리터럴을 넣지 않는다. -- 주석이 위임한 지점을 비워 두면 만료 계약의 소유자가 없어진다.
- [x] `deploy/nginx/bokrilog.conf` -- `map`으로 `Cache-Control` 값을 계산해(`/assets/` 해시 자산은 `public, max-age=31536000, immutable`, 그 밖의 문서는 `no-store`) `:87-94`의 **같은 블록에서** `always`로 낸다. 하위 location에 `add_header`를 추가하지 않는다. -- 하위 블록에 `add_header`를 두면 상위 보안 헤더 5개가 사라지고 `assert_security_headers`가 먼저 깨진다. `map`은 `:32-39`가 이미 쓰는 관용이다.
- [x] `deploy/compose/verify-same-origin.sh` -- `assert_security_headers`에 캐시 헤더 단정을 더하고(문서·자산 분기 포함), "스텁 제공자 성공 왕복" 뒤에 로그아웃 왕복 그룹을 더한다: 204 + 세션 행 0건 + 쿠키 만료 지시 + 반복 요청 403 + 다른 사용자 세션 보존. 새 표지가 access·error 로그로 새지 않는지 `:1012` 그룹에서 확인한다. `set -o pipefail`에서 `생산자 | grep -q`를 조건에 쓰지 않는다. -- 판정을 스크립트가 소유하고, PR #9의 SIGPIPE 실패를 재현하지 않는다.
- [x] `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java` -- CSRF 토큰을 실제로 얻어 상태 변경 요청을 통과시키는 헬퍼와 `principal_name`별 세션 행 수 헬퍼를 더한다. 기존 헬퍼 시그니처는 바꾸지 않는다. -- 현재 시험은 "토큰 없음 → 403"만 고정하며 통과 경로를 만들 도구가 없다.
- [x] `backend/src/test/java/com/bokrilog/AuthLogoutTest.java` -- I/O 매트릭스의 로그아웃 5행을 단정한다: 정상(204·행 0건·쿠키 만료 전문), 반복(403·무변경), 토큰 누락·오류·타 세션(403·**세션 생존**), 교차 출처, 미인증. 다른 사용자 세션 보존을 두 사용자로 확인한다. -- AC-1·AC-5.
- [x] `backend/src/test/java/com/bokrilog/AuthSessionExpiryTest.java` -- 유휴 만료, 절대 수명 초과, 관리상 폐기 세션의 접근이 모두 미인증 응답 하나로 수렴하고 이전 세션으로 데이터가 조회되지 않음을 단정한다. -- AC-2.
- [x] `backend/src/test/java/com/bokrilog/SessionGuardTest.java` -- `reauth_required` 전환은 세션 행을 남기고 폐기는 지우며, 사용자 단위 폐기가 그 `principal_name`만 지우고, 상태 설정 기능에 도달하는 HTTP 경로가 없음을 단정한다(`ApplicationContextRunner` 또는 요청 시도로). -- AC-3·AC-5.
- [x] `backend/src/test/java/com/bokrilog/AuthSessionContractTest.java` -- `:223-241`의 "상태와 목적지 종류만" 단정을 새 응답 필드에 맞춰 갱신하고, 토큰이 세션에 귀속되며 다른 세션의 토큰이 통하지 않음을 더한다. `:302-307`의 "CSRF 검사는 인증 경로 밖의 오류 계약을 바꾸지 않는다"는 그대로 통과해야 한다. -- 응답 계약을 넓히면서 무엇이 여전히 금지인지 시험이 말하게 한다.
- [x] `frontend/src/content/auth.ts` -- `AuthNoticeKind`에 로그아웃 완료·세션 만료를 더하고 `noticeKindOf`의 닫힌 집합을 함께 넓힌다. 로그아웃 버튼 라벨, 만료 안내, Google 공식 계정 복구 안내 카피를 `authContent`에 더하고 화면별 `*CopyStrings`에 편입한다. `:8-13`의 제약(완전문·보장 표현 금지·계정 존재 비노출)을 지킨다. -- 화면과 시험이 같은 문자열을 참조하는 계약이다. 새 표지 둘은 클라이언트가 만들지만(서버가 만드는 것은 `cancelled`·`failed`뿐) `noticeKindOf`의 닫힌 집합에 없으면 `?auth=` 값이 조용히 무시된다.
- [x] `frontend/src/session/useSessionState.ts` -- 응답의 CSRF 토큰을 스냅샷에 싣고, 재확인 함수를 반환 계약에 노출하고 **그 함수를 부르는 제품 소비자를 함께 만들고**(`useSessionGate`의 가시성 재확인), 인증→미인증 전이를 관측해 만료와 냉시작 미인증을 구분한다. 만료로 세는 것은 **서버가 미인증이라고 답한** 재확인뿐이다 — 시한 초과·네트워크 단절·취소는 실패 전량을 미인증으로 수렴시키는 현재 계약을 그대로 따르되 만료 표지를 만들지 않는다. `pageshow` 재확인(`:142-157`)은 유지한다. -- 브라우저 저장소를 쓰지 않고 만료를 구분할 수 있는 유일한 재료가 이 훅의 메모리 안 전이이고, 그 전이를 만드는 경로가 없으면 만료 감지 행에 아무도 도달하지 않는다.
- [x] `frontend/src/session/signOut.ts` -- `POST /api/auth/logout`을 토큰·`Origin`과 함께 보내고 성공 시 로그아웃 표지 경로로 최상위 내비게이션한다. 204가 아닌 응답에서는 인증 상태를 주장하지 않고 진입 화면으로 수렴한다. 자동 재시도하지 않는다. -- 로그아웃 전송을 한 곳이 소유해야 화면 둘이 같은 계약을 쓴다.
- [x] `frontend/src/components/SignOutButton.tsx` + `.module.css` -- 두 인증 화면이 공유하는 로그아웃 버튼. `--color-destructive`·`--space-target-min`·`--space-focus-*` 기존 토큰만 쓰고 색·크기 리터럴을 넣지 않는다. 최소 44×44px, 키보드 도달·`Enter`/`Space` 실행, 진행 중 중복 제출 차단. -- 화면 둘에 버튼을 복제하면 `deferred-work.md`가 이미 적은 중복이 커진다.
- [x] `frontend/src/screens/SignedInScreen.tsx` + `OnboardingScreen.tsx` -- 로그아웃 버튼을 배치한다. `role="status"` live region이 화면당 **정확히 1개**로 남고 skipLink→랜드마크 순서와 `main tabIndex={-1}` 골격이 유지되게 한다. -- 온보딩에 버튼이 없으면 인증된 사용자가 그 화면에서 나갈 수 없다.
- [x] `frontend/src/screens/EntryScreen.tsx` -- 인증 실패 표지에 Google 공식 계정 복구 안내를 붙인다. 새 창을 열지 않고 `rel`로 참조 유출을 막으며 계정 존재 여부를 노출하지 않는다. -- AC-4. 현재 유일한 제공자가 Google이다.
- [x] `frontend/src/test/setup.ts` -- fetch 탈출구를 (메서드, URL) 쌍으로 일반화해 로그아웃 POST가 시험에서 통과할 통로를 만든다. XHR·`sendBeacon`·IndexedDB·저장소 거부와 관측 기록은 그대로 둔다. -- 지금 구조로는 로그아웃 단위 시험을 쓸 수 없다.
- [x] `frontend/src/components/SignOutButton.test.tsx`, `frontend/src/screens/SignedInScreen.test.tsx`, `OnboardingScreen.test.tsx`, `EntryScreen.test.tsx`, `frontend/src/App.test.tsx` -- 기존 단정 축 10개를 유지한 채 로그아웃 성공·실패·중복 제출, 만료와 냉시작 미인증의 표지 구분, 복구 안내 렌더, 저장소 0건을 더한다. -- I/O 매트릭스의 브라우저 행들.
- [x] `frontend/e2e/auth-entry.spec.ts` -- `PLAYWRIGHT_BASE_URL` 블록에 로그아웃 왕복을 더한다: 버튼 실행 → 쿠키 만료 → 진입 화면 표지 → **뒤로 가기로 이전 인증 화면이 복원되지 않음** → 포커스 복귀 → axe 0위반. `:212-241`의 데이터 요청 pathname 목록을 갱신한다. -- 인증된 화면 마크업과 캐시·bfcache 동작을 보는 유일한 층이다.

**Acceptance Criteria:**

- Given 세션 저장소와 관측 출력을 검사할 때, when 로그아웃·만료·의심 세션 처리가 실행되면, then 대상 세션과 그 임시 인증 비밀만 사라지고 다른 사용자의 세션 행은 보존되며 세션 ID·CSRF 토큰·`user_id`·`principal_name`이 응답 본문·로그·오류 문맥에 남지 않는다.
- Given `develop`의 Story 1.2·1.4 계약, when 이 스토리의 변경을 얹으면, then `GET /api/nope` 404·`POST /api/status` 405·`POST /api/auth/session` 403·오류 본문 전문 동등성·보안 헤더 5개·CSP 전문이 모두 그대로 통과한다.
- Given `identity` 모듈, when 이 스토리가 끝나면, then `identity`에 HTTP·서블릿 타입이 없고 `identity.internal`을 외부에서 부르는 코드가 없으며 `deleteSessionsOf`의 결합이 세션 상태 전반으로 확대되지 않았음을 `ModuleBoundaryTest`가 단정한다.
- Given 의심 세션 상태 설정 기능, when 외부에서 도달을 시도하면, then 일반 사용자 HTTP API·Actuator·개발 전용 엔드포인트 어디에도 그 경로가 없다.
- Given 로그인 상태 화면, when 키보드만으로 조작하면, then 로그아웃이 `Tab` 도달과 `Enter`/`Space`로 실행되고 최소 44×44px이며 실행 뒤 포커스가 페이지 제목 또는 표지로 이동하고 axe WCAG 2.2 AA 위반이 0건이다.
- Given 브라우저 경계, when 로그아웃 전후의 모든 화면을 검사하면, then `localStorage`·`sessionStorage`·IndexedDB 항목이 0건이고 쿠키는 `BOKRILOG_SESSION` 하나이며 외부 origin으로 나가는 요청이 0건이다.

## Spec Change Log

- 2026-09-07 첫 구현. frozen 블록은 바꾸지 않았다. 아래 여덟 건은 구현 중에 확정한 결정이고 마지막 하나는 검증 공백이다.

  - **세션 귀속 CSRF 저장소를 감쌌다(task 목록에 없던 코드).** 기본 `HttpSessionCsrfTokenRepository#saveToken`은 토큰을 저장하려고 `request.getSession()`을 부르고, 그 호출 하나가 세션 행을 만든다(배포된 jar의 바이트코드로 확인). 그러면 I/O 매트릭스의 "반복 로그아웃 … 새 세션 행 생성 0건"이 성립할 수 없다 — 세션이 사라진 뒤의 재요청마다 거절과 함께 행이 하나 늘어난다. `AuthSecurityConfig.SessionBoundCsrfTokenRepository`가 "저장할 세션이 없으면 저장하지 않는다"만 더한다. 검사는 그대로 성립한다: 저장되지 않은 새 토큰과 요청이 들고 온 값은 어차피 다르므로 결과는 무변경 403 하나다. 이 배치에서 토큰이 만들어지는 정당한 순간은 인증된 세션이 `GET /api/auth/session`을 부르는 때뿐이고 그때는 세션이 이미 있다.
  - **재인증을 요구받은 세션의 `/api/auth/` 상태 변경을 guard가 직접 막는다.** 토큰은 세션에 그대로 남아 있으므로 CSRF 검사만으로는 걸리지 않는다. 매트릭스의 "보호 접근 차단 … 미인증과 같은 응답"을 상태 변경에까지 적용하면 그 세션은 1.8의 재인증이 회전시킬 때까지 완전히 무력해진다 — 세션 확인은 미인증으로, 상태 변경은 403 `FORBIDDEN`으로 답하고 둘 다 미인증 요청과 같은 모양이다. 대안(로그아웃만 허용)은 표시된 세션이 상태를 바꿀 수 있는 창을 남기고 그 창의 크기를 1.8·1.9가 다시 판단해야 한다. 판정 조건은 `AuthSecurityConfig.isProtectedRequest` 하나를 공유한다(package-private로 넓혔다).
  - **절대 수명 설정 이름은 `bokrilog.session.absolute-lifetime`이다.** `spring.session`은 Boot의 이름 공간이라 우리 값을 넣지 않는다. 유휴 수명(`spring.session.timeout: 30m`)은 그대로 두고 절대 수명 12h를 이 이름으로 신설했다. 환경 변수로 받지 않는다 — 배치가 아니라 보안 정책이고, 환경이 조용히 늘릴 수 있게 두면 그 완화의 출처를 추적할 자리가 없다. `SessionGuard.DEFAULT_ABSOLUTE_LIFETIME`은 설정이 없는 구성에서 상한이 사라지지 않게 하는 fail-closed 바닥이며 두 값이 갈라지면 `AuthSessionExpiryTest`가 드러낸다.
  - **`navigation.ts`에 `navigateTo`(assign)를 더했다.** 로그아웃만 히스토리 항목을 남긴다. 덮어쓰면 방금 보던 화면이 히스토리에서 사라져 "로그아웃 뒤 뒤로 가기로 이전 인증 화면이 복원되지 않는다"를 사용자도 시험도 밟을 수 없다. 만료와 목적지 불일치는 그대로 `replaceLocation`이다.
  - **`EntryScreen`이 표지와 함께 열릴 때 페이지 제목에 포커스를 준다.** AC-5의 "실행 뒤 포커스가 페이지 제목 또는 표지로 이동"은 로그아웃이 최상위 내비게이션이라 새 문서에서 성립해야 하고, 그 자리는 이 화면 하나다. 표지가 없는 첫 방문에서는 옮기지 않는다 — `entry-screen.spec.ts`가 고정한 "건너뛰기 링크가 첫 `Tab`"이 그대로 유지된다. `h1`은 `tabIndex={-1}`이라 `Tab` 순서에 들어가지 않는다.
  - **`@testing-library/user-event`를 들이지 않았다.** 키보드 활성화(`Enter`·`Space`)는 jsdom이 구현하지 않는 브라우저 동작이라 단위 시험이 확인할 수 있는 것은 "요소 종류가 네이티브 `<button>`인가"뿐이다. 실제 실행은 `e2e/auth-entry.spec.ts`의 주입 origin 블록이 Chromium에서 `Tab` → `Tab` → `Enter`로 확인한다. 단위 시험은 `fireEvent`를 쓴다 — 새 의존 0건이다.
  - **`useSessionState`의 `recheck`에는 아직 제품 소비자가 없다.** task가 "재확인 함수를 반환 계약에 노출"을 명시해 그대로 노출했지만, 그 함수를 부르는 제품 코드는 없다. task가 든 이유("만료를 구분할 수 있는 유일한 재료가 이 훅의 메모리 안 전이다")는 이미 `pageshow` 재확인이 만족시키고, 로그아웃 실패 경로는 frozen 매트릭스가 "진입 화면으로 수렴"으로 고정해 재확인이 들어갈 자리가 없다. `## Design Notes`가 인용한 Story 1.4의 교훈("소비자가 없는 필드를 만들지 않는다")과 부딪히는 자리라 여기 남긴다 — 실제 소비자는 민감 command 앞에서 상태를 다시 보는 1.8이다. 지우는 편이 낫다고 판단되면 한 줄 삭제로 끝난다.
  - **`frontend/src/test/setup.ts`의 탈출구가 응답 함수에 `init`을 넘긴다.** 로그아웃이 토큰을 헤더에 실었는지, 쿠키를 동일 출처로 제한했는지는 그 인자에만 있다. 시험이 `globalThis.fetch`를 다시 감싸 그 값을 훔쳐보는 대신 훅이 넘긴다. 인자를 쓰지 않는 기존 응답 함수는 그대로 통과한다(전역 거부와 관측 기록은 손대지 않았다).
  - **`AuthSessionStateJsonTest`(신규, task 목록에 없던 것).** 미인증 응답 본문이 이 스토리 전후로 글자까지 같아야 하고 그것을 지키는 것은 `@JsonInclude(NON_NULL)` 한 줄인데, 애노테이션은 붙어 있어도 동작하지 않을 수 있다(Boot 4는 Jackson 3을 쓰고 애노테이션 패키지만 2.x를 공유한다). Docker 없이 도는 이 시험이 그 한 줄의 효력을 직접 본다. `AuthSessionContractTest`는 같은 계약을 실제 HTTP 응답에서 다시 보지만 컨테이너를 전제하므로 원인을 가리키지 못한다.
  - **`OnboardingScreen.test.tsx`의 bfcache 단정을 `signedOut`에서 `expired`로 바꿨다.** 기대값을 코드에 맞춰 무른 것이 아니라 이 스토리가 그 구분을 새로 들인 것이다("만료와 냉시작 미인증의 표지 구분"이 task에 있다). 같은 시험에 `signedOut`이 나타나지 않는다는 단정과, 냉시작이 여전히 `signedOut`이라는 별도 시험을 함께 더했다.

- 2026-09-08 적대적 리뷰 3계층의 `patch` 판정 29건. frozen 블록은 바꾸지 않았다. 아래는 무엇이 발견을 촉발했고, 무엇을 고쳤고, 어떤 알려진-나쁜 상태를 피했는지다.

  - **캐시 `map`이 상태 코드를 먼저 본다(`bokrilog.conf`).** 경로만 보는 `map` 하나로는 `/assets/` 아래의 404(없는 파일)와 405(non-GET)까지 `public, max-age=31536000, immutable`을 달고 나간다. `immutable`은 재검증까지 막으므로 공유 캐시나 브라우저가 자산 404를 1년간 고정하면 그 파일을 실제로 추가하는 배포 뒤에도 깨진 상태가 남는다. `$status`로 성공(200·206·304)을 고른 뒤 그 안에서 `$request_path`로 갈리는 두 단계 `map`으로 바꿨다. 304를 성공에 넣는 것이 중요하다 — 방금 재검증한 항목에 `no-store`를 실으면 영구 캐시가 무의미해진다. `add_header`는 여전히 상위 블록 한 곳이므로 보안 헤더 5개는 어느 응답에서도 사라지지 않는다.
  - **`verify-same-origin.sh`가 그 자리를 실제로 탐침한다.** `/assets/` 아래의 없는 경로와 같은 자산 경로의 `POST` 둘을 더했고, 두 응답이 `no-store` + 보안 헤더 5개를 받는지 `assert_security_headers`로 본다. 위 결함이 정확히 어긋나는 자리이며, 이 두 탐침이 없으면 `map`을 다시 경로만 보게 되돌려도 371개 단정이 전부 통과한다.
  - **204 로그아웃 응답도 헤더를 검증받는다.** 쿠키를 지우는 `Set-Cookie`를 실어 나르는 그 하나의 응답이 유일하게 헤더 미검증이었다. `assert_security_headers '로그아웃 204'`를 더했다. 만료 쿠키의 속성도 `HttpOnly`·`Secure`·`SameSite=Lax`까지 단정한다(스크립트와 `AuthLogoutTest` 양쪽) — 속성이 어긋난 `Set-Cookie`를 브라우저가 원래 쿠키와 다른 것으로 보면 삭제가 성립하지 않아 쿠키가 살아남는다. 배포된 jar의 `DefaultCookieSerializer` 바이트코드로 그 세 속성이 값이 빈 쿠키에도 붙는 것을 먼저 확인했다.
  - **인증된 세션 확인의 본문 전문 비교를 되살렸다.** `csrfToken`을 더하면서 조각 포함 검사 둘로 바뀌어 있었고, 그러면 응답에 필드가 하나 늘어도 통과한다. 토큰 값만 `<가림>`으로 바꾼 뒤 전문을 비교한다.
  - **만료는 서버가 미인증이라고 답했을 때만이다(`useSessionState`).** `settle(UNAUTHENTICATED)`이 `sawAuthenticated` 하나만 보고 `expired`를 찍었고, 그 경로에는 abort·10초 시한 초과·`catch`가 모두 들어 있었다. 그래서 지하철에서 터널을 지나는 사용자가 멀쩡한 세션으로 인증 화면에 밀려나 "만료됐다"는 문장을 본다 — 이 변경 전에는 같은 실패가 화면 안 표시로 끝났으므로 회귀다. `settle`에 "서버가 답했는가" 인자를 더하고, `snapshotOf`는 세션 상태를 답한 것이 아닌 본문에 `null`을 돌려준다. 실패 전량이 `unauthenticated`로 수렴하는 계약은 그대로다.
  - **`recheck`에 실제 소비자를 만들었다(`useSessionGate`의 가시성 재확인).** 앞선 항목을 적용하면 `expired` 표지의 생산자가 아예 없어질 수 있었다. 이 스토리가 진입 문서에 붙인 `Cache-Control: no-store`는 Chromium의 bfcache 대상에서 그 문서를 빼므로 `pageshow`가 `persisted: true`로 오지 않는다. 인증을 관측한 화면이 `visibilitychange` → `visible`에서 한 번 재확인하게 했다 — 폴링이 아니고, 진입 화면으로 수렴한 뒤에는 듣지 않으며, 단일 in-flight·abort 규율은 `recheck`가 그대로 지킨다. 이것으로 frozen 매트릭스의 "만료 감지" 행이 bfcache 없이도 도달 가능하고, 앞선 change log의 "`recheck`에는 아직 제품 소비자가 없다"는 이 항목이 대체한다.
  - **`signOut`에 10초 시한을 뒀다.** 시한이 없으면 매달린 요청 하나가 버튼을 영구 pending으로 남긴다. `useSessionState`의 `AbortController` 관용을 그대로 따르고 값은 그 모듈이 export하는 상수 하나를 공유한다 — 두 요청이 같은 진입점을 지나므로 시한이 갈라질 이유가 없다.
  - **로그아웃 실패에도 표지가 있다(`logoutFailed`).** 모든 실패 경로가 맨 `/`로 이동했고 `noticeKindOf('')`는 `null`이라 표지가 렌더되지 않았다. 그러면 `EntryScreen`의 제목 포커스 effect도 건너뛰어 AC-5의 "실행 뒤 포커스가 페이지 제목 또는 표지로 이동"이 그 경로에서 성립하지 않고, 서버 세션이 살아 있는데 아무 설명 없는 로그인 화면이 보인다(주석이 피한다고 적은 바로 그 결과다). 카피는 세션이 남아 있을 수 있음을 숨기지 않고 로그아웃 완료라고 말하지도 않는다.
  - **204 뒤 이동이 던져도 성공이 실패로 뒤집히지 않는다.** `navigateTo`를 전송의 `try` 밖으로 옮겼다. 안에 있으면 이동이 던진 오류가 요청 실패로 읽혀 서버가 세션을 폐기한 뒤에도 실패 분기로 떨어진다. 그 거부를 삼키지도 않는다 — 호출자가 버튼을 되돌릴 유일한 신호다.
  - **`noticeKindOf`가 한 목록을 한 번 검사한다.** 닫힌 집합이 `cancelled|expired|failed` 직접 반환과 `loggedOut` 별도 `if`로 쪼개져 있었고, 그것이 다음 값이 한쪽에만 추가되는 자리다. `URL_NOTICE_KINDS` 하나에 대한 `find` 하나로 합쳤다. `signedOut` 제외 이유의 주석도 사실대로 고쳤다 — `expired`·`loggedOut`은 URL로 설정 가능하며, 표지는 문장 하나를 고를 뿐이고 어느 값도 로그인 상태를 주장하거나 바꾸지 않는다.
  - **로그아웃 버튼이 `aria-disabled`를 쓰고 상태는 live region이 말한다.** `disabled`는 활성화되는 순간 컨트롤을 탭 순서에서 떨어뜨려 방금 `Enter`로 그 버튼을 실행한 키보드·스크린리더 사용자가 포커스를 잃는다. 그리고 pending 문장이 버튼의 접근 가능한 이름이 되어 컨트롤의 이름이 동작이 아니라 상태(마침표까지 붙은 완전문)가 됐고, 버튼 안의 문구 변화는 live region이 아니어서 안내되지도 않았다. 버튼을 표시 전용으로 바꾸고(`pending`·`onSignOut`), 진행 상태와 전송 판정을 새 훅 `session/useSignOut.ts`가 소유한다. 화면이 그 상태를 소유하는 것이 요점이다 — 머리말의 버튼과 본문의 `role="status"` 영역이 같은 값을 봐야 하고 그 영역은 화면당 하나다. 문장을 고르는 규칙도 `liveRegionMessage` 하나로 모아 두 화면이 갈라지지 않게 했다. promise 거부도 잡아 이동이 던져도 버튼이 영구 pending으로 남지 않는다.
  - **`.recoveryLink`가 `--color-primary`를 쓴다.** 글자색과 1px 테두리 양쪽에 `--color-focus-ring`을 쓰고 있어 같은 색 링이 같은 색 테두리 2px 바깥에 앉았다. 새 토큰을 만들지 않았고, `--color-primary`는 흰 표면에서 대비 7.7:1로 AA를 넘긴다. 전역 포커스 링 색을 고정한 E2E 단정(`rgb(29, 79, 145)`)은 그대로다.
  - **표지 없는 화면과 세 표지에서 복구 안내의 부재를 단정한다.** `cancelled`에서만 확인하고 있어 정상적으로 로그아웃한 사용자에게 Google 계정 복구 패널을 보여 주는 상태가 통과했다. 다섯 표지와 표지 없는 첫 방문 전부에 대해 제목·본문·링크·주의문의 부재를 본다.
  - **표시된 세션이 새 인증 흐름을 시작할 수 없다(`AuthFlowHandlers`).** `REAUTH_REQUIRED` 분기가 `clearContext()`를 부르고 상태 변경만 403으로 끊었다. `GET /api/auth/authorize/google`은 safe method라 그 403에 걸리지 않으므로, thread-local이 비워진 채 `authorizationStartFilter`에 도달해 "이미 인증됨 → 거절" 판정이 성립하지 않았다. 콜백도 safe GET이라 같은 경로를 지나 `onSuccess`가 같은 세션 행에 새 주체를 심고, 다른 계정으로 로그인하면 그 행의 `principal_name`이 갈아탄다 — 주변 주석이 막는다고 적은 계정 갈아타기다. 인증 흐름 요청(인증 시작·콜백)에서는 thread-local을 비우지 않아 그 판정이 의존하는 인증을 남기고, 거절은 기존 필터가 한다. 세션 확인은 인증 흐름이 아니므로 그대로 미인증으로 수렴한다.
  - **`onSuccess`가 재인증 표시를 지운다.** `changeSessionId`는 속성을 보존하므로 표시가 남으면 성공적으로 다시 로그인한 세션이 영구히 미인증으로 답한다 — 되살릴 방법이 없는 상태다. 확정된 인증이 곧 그 표시가 요구한 것이므로 성공 시점이 그것을 지우는 자리다.
  - **로그아웃은 표시된 세션에도 허용된다.** 같은 분기가 `POST /api/auth/logout`까지 거절해 표시된 세션은 스스로를 끝낼 방법이 없었고, 저장된 `SecurityContext`와 CSRF 토큰을 실은 행이 유휴 만료까지 남았다. 로그아웃은 상태를 없애기만 하므로 통과시킨다. 판정은 `AuthSecurityConfig.isLogoutRequest` 하나를 공유한다(`isProtectedRequest`와 같은 배치).
  - **`revoke`가 지운 뒤 다시 확인하고 실제로 지운 수를 돌려준다(`SessionGuard`).** `findByPrincipalName`으로 읽고 나서 지우는 사이에 로그인이 끼면 폐기된 사용자에게 살아 있는 세션이 남고, 반환값도 찾은 수였다. `AuthSessionService`의 2회 시도 관용을 따라 재확인을 넣고 삭제가 실제로 성립한 건수만 센다.
  - **절대 수명에 fail-closed 상한 clamp를 넣었다.** `SessionProperties`가 하한만 접었고 yml 주석은 "환경 변수로 받지 않는다"고 적었지만, Boot의 relaxed binding은 `BOKRILOG_SESSION_ABSOLUTELIFETIME` 하나로 그 값을 덮는다 — 환경이 상한을 조용히 아무 값으로나 올릴 수 있었다. 12시간이 이제 바닥이자 천장이라 설정은 상한을 줄일 수만 있다. yml 주석도 그 사실대로 고쳤다.
  - **빈 CSRF 토큰을 부재로 다룬다(`AuthSessionController`).** `@JsonInclude(NON_NULL)`은 `NON_EMPTY`가 아니라 빈 문자열을 걸러내지 않고, 클라이언트는 그 값으로 어떤 상태 변경도 통과시킬 수 없다. 필드가 있으면 토큰을 받았다는 뜻이어야 하므로 컨트롤러가 접는다.
  - **로그아웃 주석을 실제 동작대로 고쳤다(`AuthSecurityConfig`).** `LogoutConfigurer`는 등록된 사용자 핸들러 뒤에 기본 `SecurityContextLogoutHandler`를 항상 덧붙이므로 그 핸들러를 "의도적으로 피했다"는 것은 사실이 아니다. 폐기 본체는 여전히 한 곳이고, 우리 핸들러가 먼저 세션을 무효화하므로 그 시점의 `getSession(false)`는 null이며 남는 일은 이미 비어 있는 컨텍스트를 한 번 더 정리하는 것뿐이다 — 이 배선에서 중요한 것은 "피했다"가 아니라 "앞에 있다"다.
  - **`ageLastAccess`·`ageCreation`이 대상을 인자로 받는다(`AuthFlowTestBase`).** `UPDATE spring_session SET …`에 `WHERE`가 없어 모든 행을 옮기는데 javadoc은 "세션 하나의"라고 적었다. 두 세션·두 사용자 만료 시험이 뒤에 붙는 날 조용히 둘 다 늙는다. `sessionCountOf`처럼 대상을 받고, 바뀐 행이 하나가 아니면 소리 내어 실패한다 — 0건이면 세션 id 가정이 틀렸다는 뜻이고 그때 뒤따르는 단정은 아무 일도 없던 세션을 보며 통과한다.
  - **필드 이름 단정을 파싱으로 바꿨다.** `fieldNamesOf`·`csrfTokenIn`이 본문 어디에 있는 `"key":`든 정규식으로 잡으면서 javadoc은 "최상위 필드 이름"이라고 말했고, 그러면 값이 중첩 객체 안으로 옮겨지는 변경이 "필드가 하나라도 늘면 걸린다"는 계약을 조용히 약화시킨다. `AuthFlowTestBase.topLevelFieldsOf`가 본문을 파싱해 최상위 필드만 순서대로 돌려준다.
  - **교차 세션 CSRF 시험이 두 세션이 살아 있는 동안 시도한다.** 첫 토큰으로 먼저 로그아웃(204)을 해 버려 뒤이은 403이 "토큰이 다른 세션 것이다"가 아니라 "그 토큰이 이제 존재하지 않는다"로 설명됐다. 순서를 뒤집고, 거절이 어느 세션도 건드리지 않았음과 두 토큰이 각자 자기 세션에서 통함을 함께 단정한다.
  - **수명 시험이 바인딩 자체를 단정한다.** `absoluteLifetime()`을 하드코딩 12h와 비교하는 것은 코드 기본값이 바로 12h라 `absolute-lifetime` 오타나 prefix 변경 뒤에도 통과한다. `SchemaValidationConfigTest`가 쓰는 `ApplicationContextRunner`로 지정(90m)·미지정·상한 초과(48h)·0 네 컨텍스트의 값이 실제로 달라짐을 단정하고, 실행 중인 애플리케이션의 유휴 수명은 세션 행의 `max_inactive_interval`을 리터럴 1800과 비교해 따로 본다.
  - **표시된 세션의 403이 본문 모양까지 고정된다.** `statusCode() == 403`만 보고 있었고, 이 403은 설정된 `accessDeniedHandler`가 아니라 필터의 `sendError`에서 나오므로 그 경로가 공통 오류 계약을 지나는지 아무 시험도 확인하지 않았다. `"code":"FORBIDDEN"`·correlation id·표시 이름과 토큰의 부재를 함께 단정한다. 확인 대상도 로그아웃이 아니라 `POST /api/auth/session`으로 바꿨다 — 로그아웃은 이제 통과해야 하고, 그 경로는 건강한 세션이라면 405가 되므로 403이 곧 guard가 답했다는 뜻이다(미인증 요청과 같은 코드이므로 응답으로 구분되지 않는다). 위 세 항목의 시험 공백도 함께 메웠다: 표시된 세션이 인증 흐름을 시작할 수 없고(제공자가 아니라 자기 목적지로 302, `state` 미발급, 행 불변), 스스로 로그아웃할 수 있고(204·행 0건·쿠키 만료), 성공적인 재인증 뒤에는 표시가 사라진다(흐름 세션을 표시한 뒤 콜백을 완주시켜 그 경로를 만든다).
  - **뒤로 가기 시험이 표지 하나를 고정한다(`auth-entry.spec.ts`).** `signedOut`·`expired` 둘 중 하나로 받아 주면 bfcache가 어느 쪽으로 동작해도 통과해 이 층이 아무것도 고정하지 않는다. 진입 문서의 `no-store`가 그 문서를 bfcache에서 빼므로 뒤로 가기는 문서 재요청이고 첫 확인은 미인증이다 — 계약은 `signedOut` 하나다. 만료 경로는 가시성 기반 시험이 따로 덮는다(쿠키를 지운 뒤 `visibilitychange`를 내고 `?auth=expired`·표지·이전 화면 문자열 0건·요청 하나·axe 0위반을 본다). `/dashboard`의 데이터 요청 목록은 그대로 `['/api/auth/session']`이다 — 가시성 재확인은 인증을 관측한 화면만 듣고 문서를 처음 여는 것은 가시성 변화가 아니다.
  - **`AuthLogoutTest`의 다른 사용자 보존 단정이 stale했다.** 이 스토리가 더한 `csrfToken`이 인증된 응답마다 실리므로 `{"authenticated":true,"destination":"onboarding"}` 전문 비교는 성립할 수 없다. 아래 검증 공백 때문에 첫 Docker 실행이 이제야 그것을 드러냈다. 값을 무르지 않고 필드 집합(`containsOnlyKeys`)과 세 값을 함께 단정하는 형태로 바꿨다 — 열지 않은 필드가 늘면 여전히 걸린다.

  - **검증 공백: Docker를 쓸 수 없었다(2026-09-07).** 이 기기의 Docker Desktop이 관리자 권한 프롬프트에서 멈춰 있어 세 가지를 실행하지 못했다 — `backend/gradlew --project-dir backend build`의 Testcontainers 시험 전량, `deploy/compose/verify-same-origin.sh`, 그리고 `PLAYWRIGHT_BASE_URL` 주입이 필요한 E2E 블록 7개. 실행한 것은 프런트엔드 전 검사(typecheck·ESLint·stylelint·format·build·Vitest 150개·preview E2E 33개), `bash .github/scripts/test-ci-scripts.sh` 147개, 그리고 Docker를 요구하지 않는 백엔드 시험(`ModuleBoundaryTest`·`DevOidcStubTest`·`DevOidcStubProfileTest`·`SchemaValidationConfigTest`·`AuthSessionStateJsonTest`)이다. 실행하지 못한 경로의 프레임워크 동작 가정은 배포된 jar의 바이트코드로 하나씩 확인했다 — `CsrfTokenRequestAttributeHandler`가 `CsrfToken.class.getName()` 속성을 얹는 것, `HttpSessionCsrfTokenRepository#saveToken`의 세션 생성과 `loadToken`의 비생성, `LogoutConfigurer`가 사용자 핸들러를 `SecurityContextLogoutHandler`보다 앞에 두는 것, `HttpSessionWrapper#invalidate`가 `requestedSessionInvalidated`를 세워 commit 시점에 쿠키 만료를 내는 것, `FilterOrderRegistration`의 `SecurityContextHolderFilter` → `CsrfFilter` → `LogoutFilter` 순서, `JdbcIndexedSessionRepository`가 `FindByIndexNameSessionRepository<JdbcSession>`을 구현하고 새 속성을 `DeltaValue.ADDED`로 INSERT하는 것. Nginx 구성은 문법 검사조차 돌리지 못했다(로컬에 nginx도 없다). 위 세 명령을 Docker가 있는 환경에서 돌리는 것이 이 스토리의 남은 일이다.

  - **그 공백을 2026-09-08에 메웠다.** 같은 기기의 Docker Desktop이 응답하게 되어 세 가지를 모두 실행했다 — `backend/gradlew --project-dir backend build`(Testcontainers 포함 158개 통과, 경고 0건), `deploy/compose/verify-same-origin.sh`(371개 단정 통과, exit 0), 그리고 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080`을 주입한 E2E 41개 통과(주입 origin에서만 도는 블록 전부 포함). 프런트엔드 전 검사도 함께 통과했다(typecheck·ESLint·stylelint·format·build·Vitest 154개). 위 목록에서 확인하지 못한 채로 남은 것은 하나다: 로컬에 nginx가 없어 구성 파일의 문법 검사는 여전히 컨테이너 기동으로만 확인한다(스택이 뜨고 371개 단정이 통과하는 것이 그 확인이다).

- 2026-09-08 patch 검증 재실행에서 실패 하나를 찾아 고쳤다. frozen 블록은 바꾸지 않았다.

  - **만료의 결과를 이동 하나로 좁혔다.** 주입 origin E2E의 `탭이 다시 보이게 되면 만료를 감지해 표지를 달고 진입 화면으로 보낸다`가 실패했다 — `role="status"`의 만료 문장은 나왔는데 URL의 쿼리가 비어 있었다. 원인은 같은 전이가 결과를 **두 개** 냈다는 것이다: `useSessionGate`가 `replaceLocation('/?auth=expired')`를 부르면서 같은 렌더에서 `gate: 'unauthenticated'` + `notice: 'expired'`를 돌려줘 화면이 진입 화면을 제자리에도 그렸다. jsdom에서는 `replaceLocation`이 대체돼 이동이 일어나지 않으므로 제자리 렌더만 관측되고 단위 시험이 통과했고, 실제 브라우저에서는 제자리 렌더가 먼저 성립해 이동이 착지하기 전에 URL이 읽혔다. frozen 매트릭스는 그 행의 결과를 `/?auth=expired`로 못박았으므로 기대값이 아니라 코드를 고쳤다 — 만료는 `gate: 'redirecting'`으로 답해 아무 상태도 주장하지 않고 이동만 남긴다. 그래서 만료 문장의 출처가 주소의 표지(`noticeKindOf`) 하나로 유지되고 그 상태에서 새로고침해도 같은 화면이 나온다. 냉시작 미인증은 그대로 제자리에서 `signedOut`을 그린다(Story 1.4의 동작).
  - **`SessionGateResult.notice`를 없앴다.** 위 수정으로 제자리 렌더의 표지가 언제나 `signedOut`이 되어 그 필드는 값이 하나뿐인 채로 남는다. Design Notes가 인용한 Story 1.4의 교훈("소비자가 없는 필드를 만들지 않는다")이 그대로 적용되는 자리라 필드를 지우고 두 화면이 `notice="signedOut"`을 직접 넘긴다.
  - **피한 알려진-나쁜 상태:** 만료된 사용자가 진입 화면을 본 뒤 화면이 한 번 더 바뀌는 이중 렌더, 그리고 그 사이 주소가 아직 인증 화면 경로여서 새로고침하면 다시 인증 화면으로 돌아가는 상태. 단위 시험이 이동과 제자리 렌더를 **함께** 단정하고 있었던 것이 그 모순을 green으로 덮고 있었으므로, 두 시험(`OnboardingScreen.test.tsx`의 bfcache·가시성 경로)을 "이동을 부르고 아무 문장도 주장하지 않는다"로 고쳤다.
  - **KEEP:** `$status` 기반 두 단계 캐시 `map`과 `/assets/` 404·405 탐침, 204 응답의 헤더·쿠키 속성 단정, 표시된 세션이 스스로 로그아웃할 수 있다는 계약, 절대 수명 바인딩을 `ApplicationContextRunner`로 단정하는 형태는 모두 이 라운드에서 유지된다.
  - **검증(전량 실측, Docker 있음):** 백엔드 `clean build` 158 시험 통과·경고 0건, `verify-same-origin.sh` 371/371 exit 0(깨끗한 스택), 주입 origin E2E 41/41, preview E2E 33 통과·8 건너뜀, Vitest 154 통과, `test-ci-scripts.sh` 147/147. `--keep-stack`으로 남긴 스택에 E2E를 돌린 뒤 같은 스택에 검증 스크립트를 다시 돌리면 계정·세션 행이 남아 행 수 단정 17건이 실패한다 — 스크립트 결함이 아니라 그 순서의 오염이며, 검증은 깨끗한 스택에서 돌려야 한다.

## Design Notes

**세션 guard를 루트 패키지에 두고 새 테이블을 만들지 않는다.** 두 제약이 배치를 거의 하나로 좁힌다. `identity`는 `org.springframework.http..`까지 의존이 금지돼(`ModuleBoundaryTest.java:60-87`) 세션·쿠키·요청 표면을 가질 수 없고, `AccessDecision`은 두 값뿐이며 사유 조합으로 계정 존재를 복원하는 방향을 명시적으로 거부한다(`AccessDecision.java:6-8`). 반대로 `spring_session`에는 `reauth_required` 컬럼이 없고 부가 상태가 사는 `ATTRIBUTE_BYTES`는 Java 직렬화라 SQL로 다룰 수 없다(`V2__spring_session.sql:38-45`). 남는 길은 Spring Session **자신의 API**로 상태를 담고 `principal_name` 색인(`:36`)으로 사용자 단위 조작을 하는 것이다. 이렇게 하면 V3 마이그레이션도, 새 의존도, `identity`의 세션 결합 확대도 없다. `identity`의 `deleteSessionsOf`는 삭제 경로 전용으로 그대로 남고 `deferred-work.md`의 결합 역전은 이 스토리가 건드리지 않는다.

**반복 로그아웃이 403인 것은 결함이 아니라 계약의 결과다.** `SameOriginFilter`와 `CsrfFilter`가 라우팅보다 앞에 있고(`AuthSecurityConfig.java:417-421`) 토큰 저장소가 세션 귀속이므로(`:395`), 세션이 사라진 뒤의 재요청은 라우팅에 닿기 전에 403으로 수렴한다. 이것을 204로 만들려면 로그아웃을 CSRF 보호에서 빼야 하고, 그러면 `verify-same-origin.sh:719-726`이 고정한 "`/api/auth/` 아래 상태 변경은 예외 없이 보호된다"가 깨진다. AC-1이 요구하는 "안전하게 처리"는 멱등한 상태 코드가 아니라 **무변경**이며 403이 그것을 정확히 만족한다.

**캐시 계약은 Nginx가 소유하고 `map`으로 갈린다.** `bokrilog.conf:70-71`이 못 박은 `add_header` 상속 규칙 때문에 하위 location에서 캐시 헤더를 내면 보안 헤더 5개가 그 응답에서 전부 사라진다. 값만 `map`으로 계산해 상위 블록에서 한 번 내면 분기와 상속이 동시에 해결되고, 같은 파일 `:32-39`가 이미 쓰는 관용이라 새 개념이 없다. 보는 변수는 `$uri`가 아니라 `$request_path`다 — `$uri`는 내부 redirect 뒤의 값이라 허용 목록 location의 `/onboarding` 요청이 `/index.html`로 읽히고, `$request_uri`로는 쿼리 문자열이 붙은 요청의 접두사 비교가 어긋난다(그 근거가 두 `map`의 주석에 있다). 갈림은 두 단계다: `$status`로 성공한 응답(200·206·304)만 골라 낸 뒤 그 안에서 `$request_path`로 자산과 문서를 가른다. 경로만 보면 `/assets/` 아래의 404·405까지 `immutable`을 달고 나가고, `immutable`은 재검증까지 막으므로 그 파일을 실제로 추가하는 배포 뒤에도 깨진 상태가 남는다. 백엔드에 전역 `no-store`를 켜는 길은 `StatusApiTest.java:271-281`이 고정한 "`/api/status`의 `Cache-Control`은 비어 있다"와 정면으로 부딪힌다.

**만료와 냉시작 미인증을 브라우저 저장소 없이 구분한다.** 두 경우 모두 서버 응답은 `{"authenticated":false,"destination":"entry"}` 하나이고 그래야 한다 — 응답으로 갈라 주면 세션 존재 이력이 새고 계약에 필드가 늘어난다. 구분 재료는 훅의 메모리에 있다. `useSessionState`가 인증을 한 번 관측한 뒤의 재확인에서 미인증을 보면 그것이 만료이고, 첫 확인부터 미인증이면 냉시작이다. `pageshow` 재확인(`useSessionState.ts:142-157`)이 이미 그 전이를 만드는 경로다. Story 1.4의 교훈("`destination`이 제품 코드에서 쓰이지 않아 AC의 절반이 브라우저에서 성립하지 않았다")대로, 소비자가 없는 필드를 서버 응답에 만들지 않는다.

**`reauth_required`가 폐기와 다른 지점은 저장소 한 곳이다.** 이 스토리에는 민감 command도 재인증 화면도 없으므로(1.8·1.9 범위) 두 상태의 **외부 관측**은 같아야 한다 — 둘 다 보호 접근을 차단하고 미인증 응답으로 수렴한다. 차이는 행의 생존이다. `reauth_required`는 세션 행을 남겨 1.8의 재인증이 회전시킬 대상을 보존하고, 폐기는 지운다. 이 차이가 시험으로 고정되면 1.8이 상태 기계를 다시 만들지 않는다.

**CSRF 토큰을 응답 본문에 싣는 것이 쿠키를 하나로 유지하는 유일한 길이다.** `CookieCsrfTokenRepository`는 브라우저에 두 번째 쿠키를 만들어 "불투명 쿠키 하나" 계약을 깬다. 정적 SPA라 서버가 토큰을 HTML에 심을 수도 없다. 이미 화면이 정확히 한 번 부르는 `GET /api/auth/session`(`e2e/auth-entry.spec.ts:212-241`이 그 요청 목록을 고정)에 필드를 더하는 것이 새 엔드포인트도, 새 쿠키도, 새 요청도 만들지 않는 배치다. 토큰은 세션 귀속이므로 다른 세션에서는 통하지 않고, `Referrer-Policy: no-referrer`와 `no-store`가 이미 유출 경로를 닫아 둔다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 로그아웃·만료·guard·경계 시험 통과, 경고 0건(`-Werror`), jar 생성. Docker 데몬 필요(Testcontainers)
- `npm --prefix frontend ci && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test` -- expected: 전부 통과, 경고 0건. Node 24(`frontend/.nvmrc`)
- `npm --prefix frontend run test:e2e` -- expected: Story 1.1·1.4 회귀와 새 로그아웃 화면 시험 통과
- `deploy/compose/verify-same-origin.sh` -- expected: 캐시 헤더·로그아웃 왕복 포함 모든 단정 통과, exit 0
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 전부 통과. `ci.yml`을 바꾸면 이 시험도 함께 갱신했는지 확인
- `git status --short` -- expected: 빌드·시험 산출물이 미추적 파일로 남지 않음

**Manual checks:**
- `grep -rn "Cache-Control\|expires" deploy/nginx/bokrilog.conf` -- expected: `map`과 상위 `add_header` 블록에만 등장하고 하위 location에는 `add_header`가 없음
- 실행 중인 스택의 `docker compose logs`에서 세션 ID·CSRF 토큰·`user_id` 값 검색 -- expected: 0건
- `grep -rn "org.springframework.http\|jakarta.servlet\|RestController" backend/src/main/java/com/bokrilog/identity/` -- expected: 0건
- 브라우저 개발자 도구에서 로그아웃 후 쿠키 목록과 저장소 -- expected: `BOKRILOG_SESSION` 없음, 저장소 항목 0건

## Suggested Review Order

**진입점 — 로그아웃 표면의 모양**

- 로그아웃 DSL이 `POST /api/auth/logout` 하나에 묶이고 204로 끝난다
  [`AuthSecurityConfig.java:423`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L423)

**세션 상태 기계 — HTTP 없는 내부 표면**

- 판정 하나가 절대 수명과 재인증 표시만 본다. 새 테이블도 새 SQL도 없다
  [`SessionGuard.java:140`](../../backend/src/main/java/com/bokrilog/SessionGuard.java#L140)

- 재인증 표시는 세션 속성이다. `principal_name` 색인이 사용자 단위 조작을 준다
  [`SessionGuard.java:180`](../../backend/src/main/java/com/bokrilog/SessionGuard.java#L180)

- 폐기는 지운 뒤 다시 확인한다. 경합에 로그인이 끼어도 살아남지 않는다
  [`SessionGuard.java:159`](../../backend/src/main/java/com/bokrilog/SessionGuard.java#L159)

- guard 필터가 인증 흐름 요청의 인증은 남긴다 — 계정 갈아타기를 막는 판정이 그것에 의존한다
  [`AuthFlowHandlers.java:274`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L274)

- 폐기 본체 하나가 행 삭제·컨텍스트 정리·쿠키 만료를 함께 만족한다
  [`AuthFlowHandlers.java:487`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L487)

**CSRF 토큰 전달 — 쿠키를 하나로 유지하는 자리**

- 토큰이 세션 확인 응답의 세 번째 필드다. 인증된 세션에만, 빈 값은 부재로
  [`AuthSessionController.java:61`](../../backend/src/main/java/com/bokrilog/AuthSessionController.java#L61)

- 저장할 세션이 없으면 저장하지 않는다 — 반복 로그아웃이 세션 행을 만들지 않게 한다
  [`AuthSecurityConfig.java:571`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L571)

- 로그아웃만 표시된 세션의 403에서 면제된다. 상태를 없애기만 하는 조작이다
  [`AuthSecurityConfig.java:528`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L528)

**캐시 경계 — 뒤로 가기로 민감 화면이 복원되지 않는 근거**

- 두 단계 `map`이 상태 코드를 먼저 본다. 자산 404·405가 1년 고정되지 않는다
  [`bokrilog.conf:80`](../../deploy/nginx/bokrilog.conf#L80)

- 값만 갈리고 헤더는 상위 한 블록이 낸다 — 보안 헤더 5개가 사라지지 않는다
  [`bokrilog.conf:118`](../../deploy/nginx/bokrilog.conf#L118)

**브라우저 — 만료 판정과 로그아웃 전송**

- 만료는 서버가 미인증이라고 답했을 때만이다. 오프라인 blip이 만료가 아니다
  [`useSessionState.ts:395`](../../frontend/src/session/useSessionState.ts#L395)

- 탭이 다시 보이는 순간이 만료의 유일한 관측 지점이다. 폴링이 아니다
  [`useSessionState.ts:378`](../../frontend/src/session/useSessionState.ts#L378)

- 전송이 한 곳이다. 10초 시한, 실패는 표지와 함께 진입 화면으로
  [`signOut.ts:35`](../../frontend/src/session/signOut.ts#L35)

- pending·중복 제출 가드가 훅에 있고 버튼은 표현만 맡는다
  [`useSignOut.ts:32`](../../frontend/src/session/useSignOut.ts#L32)

- `aria-disabled`로 탭 순서를 지킨다. 진행 안내는 화면의 live region 하나가 맡는다
  [`SignOutButton.tsx:34`](../../frontend/src/components/SignOutButton.tsx#L34)

**카피 — 네 표지와 제공자 복구**

- 로그아웃·만료·로그아웃 실패가 닫힌 집합에 함께 든다
  [`auth.ts:91`](../../frontend/src/content/auth.ts#L91)

- 유일 제공자 복구 안내. 새 창을 열지 않고 계정 존재를 노출하지 않는다
  [`EntryScreen.tsx:86`](../../frontend/src/screens/EntryScreen.tsx#L86)

**시험·검증**

- 로그아웃 5행의 계약. 거절이 세션을 살려 두는지까지 본다
  [`AuthLogoutTest.java:29`](../../backend/src/test/java/com/bokrilog/AuthLogoutTest.java#L29)

- 동일 출처 층의 로그아웃 왕복. 204 헤더·쿠키 속성·행 수를 함께 본다
  [`verify-same-origin.sh:931`](../../deploy/compose/verify-same-origin.sh#L931)
