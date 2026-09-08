---
title: 'Story 1.6 — 네이버로 독립적으로 가입·로그인하기'
type: 'feature'
created: '2026-09-08'
status: 'done'
review_loop_iteration: 0
baseline_commit: '3a3cef72301e29f94634445718e51a364ead2e80'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-4-google-sign-in.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-5-logout-session-expiry.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 저장소의 인증 표면은 제공자가 하나임을 구조로 전제한다. `AuthSecurityConfig`의 `GoogleClientRegistrations`는 `ClientRegistration` 한 개를 필드로 들고 `findByRegistrationId`가 `"google"` 하나만 응답하며, `AuthSessionService.java:106`은 DB `identity_social_link.provider`에 문자열 `"google"`을 **무조건** 넣는다. `verify-same-origin.sh:754-756`은 `GET /api/auth/authorize/naver`가 404임을 계약으로 고정해 두었다. Google 계정이 없는 사용자는 BokriLog에 들어올 길이 없고, Epic 1의 남은 스토리(1.7 카카오, 1.10·1.11 수단 연결·해제)는 모두 "제공자가 여럿"인 등록·저장 구조를 기다린다.

**Approach:** Story 1.4가 세운 공통 OIDC·세션 계약을 **그대로** 재사용하고, 단일 제공자를 전제한 세 지점만 제공자별로 가른다 — 등록 저장소(하나 → 이름별 맵), `provider` 컬럼 값(리터럴 → 흐름이 알려 준 registration id), 개발·CI 스텁(인스턴스 하나 → 둘). 네이버는 `CommonOAuth2Provider`에 없으므로 공식 discovery 문서가 광고하는 정적 엔드포인트 네 개와 `client_secret_post`로 등록을 손수 조립한다. 화면은 진입 화면의 세로 목록에 공식 가이드를 따른 두 번째 버튼을 더한다.

## Boundaries & Constraints

**Always:**
- 공통 OIDC 불변식을 네이버에도 예외 없이 적용한다 — Authorization Code + PKCE(S256), 서버가 발급한 일회성 `state`·`nonce`, ID Token JWS·RS256·JWKS·`iss`·`aud`·`azp`·`exp`·`nonce` 검증. 검증은 Spring Security OAuth2 Client가 하고 손으로 만들지 않는다.
- 동의 범위는 `openid` 하나다. 네이버 공식 문서가 `openid`를 필수로 요구하고 discovery가 `openid`·`profile`만 광고하지만 `profile`은 요청하지 않는다. `userInfoUri`는 비운다 — 필요한 값은 ID Token의 `iss`·`sub` 둘뿐이고, URI를 채우면 UserInfo 왕복이 켜진다.
- 제공자 등록은 **제공자 단위로 독립해 fail-closed**다. 네이버 자격정보가 비어도 Google 등록과 그 로그인 경로는 살아 있어야 하고, 그 역도 같다. 어느 쪽도 구성되지 않으면 `oauth2Login` DSL 자체를 배선하지 않는 현재 판정을 유지한다.
- `identity_social_link.provider`에는 인증에 **실제로 쓰인** registration id가 들어간다. 흐름이 그 값을 알려 주지 않는 경로를 남기지 않는다.
- `(issuer, subject)` 전역 유일과 `(user_id, provider)` 유일을 그대로 쓴다. 이메일·표시명은 받지도 저장하지도 않으며 두 제공자의 식별자를 어떤 이유로도 병합·전환하지 않는다.
- 실패·취소는 닫힌 두 표지(`cancelled`·`failed`)로만 수렴한다. 제공자 이름·제공자 오류 문구·계정 존재 여부가 응답·URL·로그에 새지 않는다.
- 세션·로그아웃·만료·의심 세션·삭제 경로는 Story 1.4~1.5 계약을 **그대로** 적용받는다. 네이버 전용 세션 개념을 만들지 않는다.
- 두 버튼은 고정 순서(Google → 네이버)와 동등한 시각 면적으로 전체 폭 세로 목록에 놓인다. 추천 표지·마지막 사용 강조·계정 존재 암시·로고만 있는 버튼을 두지 않는다. 접근 가능한 이름은 제공자명 + "계속하기"다.
- 네이버 버튼의 색·로고·여백은 네이버 공식 버튼 가이드가 BokriLog 토큰보다 우선한다. 색 리터럴은 `tokens.css`에만 두고 컴포넌트 CSS에 쓰지 않는다.
- `naver-*` 토큰에는 공식 가이드가 **본문으로 공표한 값만** 담는다. 가이드가 침묵하는 축(모서리 반경이 그 경우다)은 BokriLog의 기존 `--radius-*` 토큰을 쓰고, 없는 브랜드 값을 지어내지 않는다. 토큰의 정본은 `DESIGN.md` frontmatter이고 `tokens.css`는 그 사본이다.
- 자격정보 이름만 설정·compose에 적고 값은 환경이 준다. 자격정보 모양 리터럴을 어디에도 넣지 않는다.
- 화면당 `role="status"` live region은 정확히 하나로 남는다. 새 버튼은 전역 `:focus-visible`을 쓰고 자기 포커스 규칙을 선언하지 않는다.

**Ask First:**
- **네이버에 대해 `nonce`를 끄거나 ID Token 검증을 완화하는 것.** 아래 Design Notes의 미해결 외부 사실과 직결된다. 어떤 이유에서도 조용히 완화하지 않는다.
- 공식 가이드가 침묵하는 브랜드 축에 BokriLog 토큰이 아닌 **새 `naver-*` 값을 만들어야 한다**고 판단되는 경우. 결정은 "가이드 공표 값만 담는다"이며 그것을 넘으려면 멈추고 묻는다.
- `spring_session`·`identity` 스키마에 컬럼을 더하는 것(V3 마이그레이션).
- `identity` 공개 API에 메서드를 더하거나 `AccessDecision`에 값을 더해야 한다고 판단되는 경우.
- `ci.yml`을 바꾸는 것. 조사 결과 제공자 추가는 CI 변경을 요구하지 않는다.

**Never:**
- `authorizeHttpRequests`에 인증 게이트를 켜는 것. 이 스토리도 사용자 소유 자원을 만들지 않으며 `anyRequest().permitAll()`이 Story 1.2의 404·405 계약을 지탱한다.
- 카카오 어댑터(1.7). 로그인 수단 연결·해제(1.10·1.11). 재인증·세션 회전 UX와 5분 action grant(1.8·1.9). 제공자 측 로그아웃.
- 네이버 로그인 SDK·JS SDK·외부 CDN·외부 폰트·외부 이미지 반입. 브라우저 영속 저장소 쓰기. 액세스·리프레시 토큰 저장.
- 기동 시점 OIDC discovery 왕복. 요청 `Host`·`X-Forwarded-*`에서 redirect URI를 유도하는 것.
- `identity.internal` 직접 호출. `identity`에 HTTP·서블릿 타입 반입. `identity`의 `spring_session` 결합 확대. 새 최상위 모듈 패키지 생성.
- 실제 네이버 왕복을 CI 판정으로 삼는 것. 실패한 CI를 재실행으로 덮는 것. 기존 시험의 기대값을 코드에 맞춰 무르는 것.
- 미룬 항목 두 건(`identity` 행 잠금 대기 상한, `identity`↔`spring_session` 결합 역전)을 이 스토리에서 해소하는 것. 판정은 "재차 미룸"이며 근거는 Design Notes에 있다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| 네이버 인가 시작 | 미인증 + 네이버 등록 구성됨, `GET /api/auth/authorize/naver` | 302, `Location`이 네이버 authorization 엔드포인트. `scope=openid` 단독, `code_challenge_method=S256`, `state`·`nonce` 각 1회, `redirect_uri`가 등록값과 전문 일치. `code_verifier`는 응답에 없음 | N/A |
| 신규 네이버 식별자 | 검증 통과한 콜백, `(naver-issuer, subject)` 미연결 | 계정 1건 + 매핑 1건 원자 생성, `provider='naver'`, 세션 ID 회전, `/onboarding` | 커밋 직전 계정 상태 재검사 실패 시 무변경 + `failed` |
| 기존 네이버 식별자 | 같은 `(naver-issuer, subject)` 재로그인 | 같은 `user_id`, 계정·매핑 증가 0건, 유효한 원래 목적지 또는 `/dashboard` | N/A |
| 두 제공자, 같은 subject 문자열 | Google로 `sub=X` 로그인 후 네이버로 `sub=X` 로그인 | 계정 2건·매핑 2건, 두 `user_id`가 서로 다름. 병합·전환 0건 | N/A |
| 사용자 취소 | 네이버가 `error=access_denied`로 콜백 | 계정·매핑·세션 무변경, 진입 화면 + `auth=cancelled` | 제공자 오류 문구 미노출 |
| 제공자 오류·검증 실패 | 서명·`iss`·`aud`·`azp`·`exp`·`nonce`·`state`·PKCE 중 하나라도 어긋남 | 계정·매핑·세션 무변경, 진입 화면 + `auth=failed`. 기존 세션이 있었다면 그 세션 보존 | 원인이 응답·로그에 남지 않음 |
| 네이버 미구성 | `BOKRILOG_NAVER_CLIENT_ID`·`_SECRET` 중 하나라도 빔 | 네이버 등록 미생성. `/api/auth/authorize/naver`가 공통 오류 계약으로 fail-closed. **Google 로그인은 정상 동작** | 환경 변수 이름이 응답·로그에 남지 않음 |
| 이미 인증된 세션 | 인증된 쿠키로 `GET /api/auth/authorize/naver` | 현재 목적지로 리다이렉트. 두 번째 가입 시작 안 함, 무변경 | N/A |
| 삭제 진행 계정 | `DELETION_IN_PROGRESS` 계정의 네이버 재로그인 | `AccessDecision.DENIED`. 세션 미생성, 신규 계정 미생성 | 타 사용자·미존재와 구분되지 않음 |
| 네이버 계정 삭제 | `erasePayload` 실행 | 그 계정의 네이버 매핑과 세션 행이 함께 사라짐 | 부분 실패를 완료로 보고하지 않음 |
| 알 수 없는 제공자 | `GET /api/auth/authorize/kakao` | 404 + 공통 오류 본문 (현행 유지) | 아는 제공자 목록이 응답에 새지 않음 |

</frozen-after-approval>

## Code Map

**검증된 외부 사실 (2026-09-08 실측)**

- 네이버 OIDC discovery `https://nid.naver.com/.well-known/openid-configuration` → `issuer=https://nid.naver.com`, `authorization_endpoint=https://nid.naver.com/oauth2/authorize`, `token_endpoint=https://nid.naver.com/oauth2/token`, `jwks_uri=https://nid.naver.com/oauth2/jwks`, `userinfo_endpoint=https://openapi.naver.com/v1/nid/me`, `response_types_supported=[code]`, `grant_types_supported=[authorization_code]`, `scopes_supported=[openid,profile]`, **`token_endpoint_auth_methods_supported=[client_secret_post,none]`**, `code_challenge_methods_supported=[S256]`, `subject_types_supported=[pairwise]`, `id_token_signing_alg_values_supported=[RS256]`. `end_session_endpoint` 없음.
- 공식 개발 가이드 §3.5: 네이버 OIDC는 기존 OAuth 2.0 API와 **다른 경로**다 — OIDC는 `/oauth2/*`, 레거시는 `/oauth2.0/*`. `scope=openid` **필수**. token 응답에 `id_token` 포함. `nonce`는 가이드 전체에 **0회** 등장.
- 공식 버튼 가이드(`developers.naver.com/docs/login/bi/bi.md`): 권장 조합 `Background: #03A94D`(RGB 3/169/77) · `Logo: #FFFFFF` · `Label: #FFFFFF`, **"지정 컬러는 변경할 수 없으며"**. 완성형(로고+레이블) 권장. N 로고 완성형 16px 이상, 로고 변형·조합·그라데이션 금지. 가운데 정렬 시 로고-레이블 간격 8px, 왼쪽 정렬 시 로고 좌측 여백 20px. 텍스트는 로고 높이보다 작게. **문구 변경 허용** — 따라서 "네이버로 계속하기"가 가이드와 충돌하지 않는다. "타사 로그인 버튼과 같이 사용될 때 크기를 줄이는 것을 지양", "타사 버튼이 컬러일 경우 흰색 배경 버튼 지양" → 녹색 완성형이 우리 배치에 맞는 선택이다. **모서리 반경은 본문에 없음.**
- `spring-security-config-7.1.1.jar`의 `CommonOAuth2Provider` 상수는 `GOOGLE`·`GITHUB`·`FACEBOOK`·`OKTA` **4개뿐 — NAVER 없음.**
- `spring-security-oauth2-client-7.1.1.jar`: `ClientRegistration.withRegistrationId(String)`와 `Builder#clientAuthenticationMethod(...)` 존재. `ClientAuthenticationMethod.CLIENT_SECRET_POST` 존재.
- **(2026-09-09 실측, 리뷰 라운드에서 추가)** 공식 버튼 애셋 `https://developers.naver.com/inc/devcenter/downloads/bi/NAVER_login_KR.ai`(공식 버튼 가이드 `developers.naver.com/docs/login/bi/bi.md`가 링크하는 한글 버튼 애셋, PDF 1.5, 2026-09-09 내려받음, 애셋 내 버튼 파일 날짜 2025-12-04). 그 안의 N 마크는 곡선 없는 **10점 직선 경로**이고 bounding box가 **정확한 정사각형**이다 — 버튼 크기별로 16×16·18×18·20×20 인스턴스가 있고 **모두 정사각형을 가득 채운다**. 16×16 인스턴스를 SVG 좌표로 변환한 경로(y축 뒤집음): `M10.848 8.565 L4.916 0 L0 0 L0 16 L5.151 16 L5.151 7.438 L11.083 16 L16 16 L16 0 L10.848 0 Z`. **bbox가 정사각형이라 24px 박스에서 잉크가 박스를 가득 채운다** — 그래서 "텍스트는 로고 높이보다 작은 크기"가 CSS 선언이 아니라 렌더 결과에서 성립한다.
- **(2026-09-09 실측, 리뷰 라운드에서 추가)** 네이버 공식 계정 찾기 `https://nid.naver.com/user2/help/idInquiry`는 **HTTP 200**으로 해석된다. `frontend/src/content/auth.ts`의 `recovery.links` 네이버 항목이 그 주소를 쓰며 카피 주석이 같은 근거를 적는다. Google 쪽 `https://accounts.google.com/signin/recovery`는 Story 1.4가 이미 들여온 값이다.

**백엔드 — 등록 구조 (핵심 변경 대상)**

- `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java:81` -- `GOOGLE_REGISTRATION_ID`. 주석이 "네이버·카카오는 Story 1.6·1.7이 더한다"고 이 줄을 지정한다. 소비 지점 6곳: `:190`(등록 id), `:195`(redirect URI 마지막 세그먼트), `:227`(조회 키), `AuthSessionController.java:141`(경로 매칭), `AuthSessionService.java:106`(**DB provider 값**), 그리고 시험 5파일.
- `AuthSecurityConfig.java:127-152` -- `AuthProperties(publicBaseUrl, Google google)`와 compact 생성자의 null-접기 정규화(`:130-143`). `AuthProperties.Google`(`:145-151`)은 `clientId·clientSecret·issuerUri·authorizationUri·tokenUri·jwkSetUri` 6개 전부 `@Nullable`. **정규화가 여기 하나뿐인 것이 요점**(`:136-138`)이므로 제공자를 늘려도 그 성질을 유지해야 한다.
- `AuthSecurityConfig.java:172-292` -- `GoogleClientRegistrations`. 상태는 단일 `@Nullable ClientRegistration`(`:174`). `from()`의 fail-closed는 clientId·clientSecret·`normalizedBaseUrl` **세 값 AND**(`:183-186`). `findByRegistrationId`(`:225-228`)는 단일 등호. `isConfigured()`(`:237-239`)의 유일 소비 지점이 `:491`이며 false면 `oauth2Login` DSL을 배선하지 않는다(근거 `:231-236`). `normalizedBaseUrl`(`:255-287`)은 static·package-private이고 `DevOidcStubConfig`가 3회 호출한다 — **시그니처를 바꾸지 않는다.**
- `AuthSecurityConfig.java:188-221` -- 빌더에 실제로 세팅되는 값 전부: `CommonOAuth2Provider.GOOGLE.getBuilder(...)`, clientId/secret, `redirectUri = baseUrl + CALLBACK_BASE_URI + "/" + id`(절대 URI), `scope(OidcScopes.OPENID)` 단독(`:198`, profile·email을 **대체**), `userInfoUri(null)`(`:199-203`, Spring Security 7은 scope가 아니라 URI 존재로 UserInfo 왕복을 판단), `clientSettings.requireProofKey(true)`(`:204-207`), 엔드포인트 4개는 `hasText()`일 때만 재정의(`:209-221`). 네이버는 `CommonOAuth2Provider` 기반이 없으므로 엔드포인트 4개 + `userNameAttributeName`(`sub`) + `authorizationGrantType` + `clientAuthenticationMethod`가 **필수 입력**이다.
- `AuthSecurityConfig.java:75`, `:78`, `:308-334`, `:501-502` -- `AUTHORIZATION_BASE_URI`·`CALLBACK_BASE_URI`, `DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION_BASE_URI)`가 `{registrationId}`를 경로에서 추출, `redirectionEndpoint().baseUri(CALLBACK_BASE_URI + "/*")`. **와일드카드이므로 네이버 URL 표면은 추가 배선이 없다.** 주석 `:505-509`가 `authorizationEndpoint().baseUri(...)`를 쓰지 않는 이유를 적는다. 모르는 등록 id의 `IllegalArgumentException`은 익명 resolver가 null로 접는다(`:315-332`).

**백엔드 — 콜백·저장 경로**

- `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java:402-414` -- `establish()`. `OidcUser`에서 `getIssuer()`(`:407`)·`getSubject()`(`:408`)를 뽑아 `authSessionService.establish(issuer.toExternalForm(), subject)`(`:413`)를 부른다. **registration id가 이 시그니처에 없다** — `OAuth2AuthenticationToken#getAuthorizedClientRegistrationId()`로 얻어 넘겨야 한다.
- `backend/src/main/java/com/bokrilog/AuthSessionService.java:106` -- `linkSocialIdentity(userId, GOOGLE_REGISTRATION_ID, issuer, subject)`. **인증에 쓰인 제공자와 무관하게 항상 `"google"`을 저장한다.** 스텁 로그인도 `"google"`이다. AC-2의 주축이자 1.10·1.11의 전제.
- `AuthFlowHandlers.java:189-198` -- 이미 인증된 세션의 로그인 시작 거절. 근거(`:190-195`): 열면 두 번째 신규 가입이 시작되고 그 콜백이 기존 세션을 다른 계정으로 갈아탄다. **제공자 비종속 — 네이버에 자동 적용된다.**
- `AuthFlowHandlers.java:532-538`, `:93`, `:102` -- `marker()`. `ACCESS_DENIED`만 `cancelled`, 나머지 전부 `failed`. 주석 `:97-101`이 "값을 나누는 순간 계정 존재·상태 신호가 된다"고 못 박는다. **제공자별 오류 매핑을 만들지 않는다.**
- `AuthFlowHandlers.java:116`(흐름 세션 5분), `:81`·`:560-562`(`ALLOWED_DESTINATIONS`·`allowedDestination`), `:565-573`(`destinationKind` 3값), `:575-578`·`:589-594`(`isAuthorizationStart`·`isAuthenticationFlow`) -- 전부 제공자 비종속. **경로 판정이 registration id를 보지 않으므로 네이버 경로가 자동 포함된다.**
- `backend/src/main/java/com/bokrilog/AuthSessionController.java:138-146` -- `:141`이 `GOOGLE_REGISTRATION_ID`로 "아는 제공자 이름인가"를 판정해 불일치 404, 일치 500. 등록 저장소에 물어보는 형태로 바꿔야 하며 **아는 제공자 목록이 응답에 새지 않아야 한다.**
- `backend/src/main/java/com/bokrilog/identity/AccountLifecycleApi.java:50-54`, `:86` -- `linkSocialIdentity(userId, provider, issuer, subject)`와 `findUserIdBySocialIdentity(issuer, subject)`. **이미 제공자 중립이다. `identity`는 바뀌지 않는다.** 유일성은 `V1__identity_account.sql:60-63`(`(issuer,subject)` 전역 유일)·`:66-67`(`(user_id,provider)` 유일), 길이 CHECK는 `:52-57`(provider 1~64B, issuer/subject 1~512B).

**백엔드 — 읽기 전용(건드리면 다른 계약이 깨진다)**

- `AuthSecurityConfig.java:397`(`anyRequest().permitAll()`, 근거 `:387-396`), `:402`(`headers(disable())`), `:452-455`·`:516-518`(CSRF 매처), `:454`·`:571-598`(`SessionBoundCsrfTokenRepository`), `:471-474`·`:477`·`:489`(필터 순서 3개), `:539-541`(`isLogoutRequest` 동등 비교), `:549-554`(`isProtectedRequest`), `:423-430`(로그아웃 DSL·204), `:435`(`requestCache.disable()`), `:440-441`(세션 회전), `:461-469`(401·403 핸들러), `:347-375`(`discardingAuthorizedClientRepository` — 기본 구현은 토큰을 PostgreSQL로 보낸다).
- `AuthSecurityConfig.java:67`, `AuthFlowHandlers.java:62`, `AuthSessionService.java:30`, `AuthSessionController.java:38`, `SessionGuard.java:39` -- `@ConditionalOnWebApplication(type = SERVLET)`. `migrate` 역할에는 `HttpSecurity`가 없다. **새 빈을 더하면 같은 조건을 붙인다.**
- `ApiErrorHandler.java:47-82` -- code·message 리터럴. 같은 문자열이 `bokrilog.conf`에 있고 `verify-same-origin.sh`가 두 본문을 비교한다(`:43-46`). **새 code가 필요하지 않다.**

**백엔드 — 스텁 (인스턴스 둘, 클래스 하나)**

- `backend/src/main/java/com/bokrilog/DevOidcStub.java` -- **`google`·registration id 문자열이 이 클래스에 없다.** issuer는 생성자 인자 하나(`:86-87`), clientId/secret은 생성자마다 새로 만들고(`:90-91`) 서명 키도 인스턴스 단위(`:93-95`)다. `discovery(...)`(`:148-160`)는 엔드포인트 3개를 **인자로 받는다**. `claim` 집합(`:463-475`)에 email·name·picture가 **없다**. **결론: `new DevOidcStub(issuer)`를 두 번 부르면 시그니처 변경이 0이다.**
- `DevOidcStub.java:412-422`, `:440` -- `authenticatedClient`. 주석이 **"`client_secret_post`로 온 요청도 받아 두어 client 인증 방법을 바꿔도 이 검사가 그대로 성립한다"**고 적는다. 네이버 등록을 `CLIENT_SECRET_POST`로 두어도 스텁 변경이 없다.
- `backend/src/main/java/com/bokrilog/DevOidcStubConfig.java:50-58`(경로 상수), `:73-80`(실제 자격정보와 동시 활성 시 기동 거부 — 네이버 자격정보도 함께 봐야 한다), `:82-85`(스텁 빈, issuer = `browserBase + BASE_PATH`), `:94-110`(`@Primary GoogleClientRegistrations`로 운영 빈을 가리고 `AuthProperties.Google`을 손수 조립), `:118-143`(라우터 빈 **하나**에 경로 4개), `:152-174`(동의 HTML), `:204-218`(base URL 해석).
- `backend/src/test/java/com/bokrilog/OidcStubConfiguration.java:46`·`:51`·`:53`(상수), `:58-66`(`DynamicPropertyRegistry`로 프로퍼티 **7개** 주입), `:97-105`(루프백 서버, 컨텍스트는 `/jwks`·`/token` **둘만** — `/authorize`는 올리지 않는다, 근거 `:32-34`), `:69-71`(`issuer()`), `:80-82`(`issueCode`).

**백엔드 — 시험**

- `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java:129-133`, `:283` -- **제공자 하드코딩은 정확히 이 두 곳뿐**(`AUTHORIZATION_BASE_URI + "/" + GOOGLE_REGISTRATION_ID`, `callbackPath()`). issuer 하드코딩은 없다 — 시험이 `OidcStubConfiguration.issuer()`를 직접 부른다.
- `AuthFlowTestBase.java:456` -- `userIdOf(String issuer, String subject)`가 **이미 issuer를 인자로 받는다. AC-2 단정의 축이 여기다.** 카운터는 `:361`(`accountCount`)·`:367`(`socialLinkCount`)·`:372`(`sessionCount`)·`:382`(`sessionCountOf`). `resetStore()`(`:91-99`)가 세션 2표 + identity 3표를 TRUNCATE.
- `AuthFlowTestBase.java:38-41` -- `@DynamicPropertySource`를 기반 클래스에만 두는 이유. **새 시험 파일이 자기 `@DynamicPropertySource`를 쓰면 컨텍스트가 갈라져 CI wall-clock이 늘어난다.**
- `backend/src/test/java/com/bokrilog/AuthAccountMappingTest.java:57-76` -- `subject가_다르면_별개_계정이다`. 주석 `:58-64`가 **"스키마에 이메일 컬럼이 없고 `AccountLifecycleApi` 시그니처에도 이메일이 없어 '이메일만 같은 두 식별자' 행을 subject 축으로 대체했다"**고 적는다. `DevOidcStub`에 email claim이 없다는 사실과 합치면 **AC-2는 이메일 동일성으로 단정할 수 없고 `(issuer, subject)` 축으로만 단정 가능하다.** 다른 시험 3개: `:27-37`·`:40-54`·`:79-148`(동시 로그인, 유일 인덱스가 접음).
- `backend/src/test/java/com/bokrilog/AuthTokenValidationTest.java:109`·`:114` -- **`nonce` 불일치와 `nonce` 없음이 모두 거절임을 이미 고정한다.** 실패 축 18개 전부 OIDC 코어 검증이라 제공자 비종속이고, 값 참조만 스텁에 묶인다(`:34`·`:39`·`:43-44`·`:269`). 헬퍼 `assertRejected`(`:274`)가 인자 없는 `startAuthorization()`을 부른다 — **축을 제공자로 곱하면 34개가 되므로 곱하지 않는다.**
- `backend/src/test/java/com/bokrilog/DevOidcStubProfileTest.java:41`·`:43`·`:45`(기본 프로필 미노출), `:118-129`(`hasSingleBean` × 3), `:132-174`(등록 내용 8축, 전부 `GOOGLE_REGISTRATION_ID`로 조회), `:196-225`(실제 자격정보 동시 활성 시 기동 거부 + 메시지에 `BOKRILOG_`·`client-id` 없음). **`:43`·`:126`이 `RouterFunction`을 단수로 단정하므로 라우터 빈은 하나로 유지한다.**
- `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java:45-58`, `:61-87` -- `identity.internal` 외부 접근 금지, `identity`의 `org.springframework.web|http|boot.web*`·`jakarta.servlet` 의존 금지(애노테이션도 의존). **새 어댑터는 루트 `com.bokrilog`에 둔다.** 새 패키지 `com.bokrilog.auth`를 만들면 Modulith가 모듈로 인식해 `:41` `verify()`가 모듈 간 의존 선언을 요구한다.

**배포·검증**

- `deploy/compose/verify-same-origin.sh:754-756` -- **`GET /api/auth/authorize/naver` → 404 기대. 이 스토리가 뒤집는 유일한 기존 단정이다.**
- `verify-same-origin.sh:89-109` -- `forbidden_fragments`. `BOKRILOG_GOOGLE_CLIENT_ID`·`_SECRET`이 `:104-105`에 있다. **네이버 이름 2개를 여기 더한다.** 이 목록이 로그 누출 검사의 유일한 정본(사용처 `:115-128`·`:231`·`:1264`).
- `verify-same-origin.sh:812-929` -- "스텁 제공자 성공 왕복" 그룹. 단계와 하드코딩: discovery `:821`, issuer 기대값 `:824-825`(`${base_url}/api/dev-oidc`), S256 `:826`, JWKS `:829-832`, 인가 시작 `:835-836`, 스텁 인가 접두사 `:838-839`, PKCE `:840`, scope 전문 `:843-844`, nonce `:845`, redirect_uri `:846`, verifier 부재 `:848`, 동의 화면 `:858-863`, 콜백 목적지 접두사 `:867-868`, 콜백 `:876-883`, 쿠키 속성 `:886-891`, 회전 `:892-897`, 세션 확인 `:901-921`, 행 수 `:924-929`. 그 외 제공자 이름: `:760`·`:768`·`:939`·`:1213`.
- `verify-same-origin.sh:487-513` -- `assert_security_headers`. 경로·제공자를 인자로 받지 않고 `last_headers`만 읽는다. **제공자 개수와 무관하다.** 재사용 함수: `fetch`(`:262`), `header_value`(`:280`), `psql_value`(`:299`), `session_cookie_value`(`:326`), `assert_equals`(`:336`), `assert_body_contains`(`:345`), `assert_status_in`(`:355`), `assert_prefix`(`:378`), `resolve_location`(`:392`), `assert_value_lacks`(`:409`), `assert_rows`(`:418`), `assert_cache_control`(`:472`).
- `deploy/compose/compose.yaml:133-134` -- `BOKRILOG_GOOGLE_CLIENT_ID|_SECRET: ${...:-}`. 주석 `:131-132`("가짜 값을 채우지 않는다"). **네이버 두 줄이 같은 모양으로 그 뒤에 들어간다.** `:120` `SPRING_PROFILES_ACTIVE`는 의도적으로 `-`(`:-` 아님).
- `deploy/nginx/bokrilog.conf:188`(`^~ /api/`), `:209-212`(`^~ /api/auth/callback/` + `error_log crit`), `:32-37`·`:39`·`:111`(쿼리 제거 access log) -- **`/api/auth/authorize/naver`와 `/api/auth/callback/naver`는 무변경으로 들어온다.** 가장 긴 `^~` 접두사가 이긴다는 근거 `:207`, `^~`가 정규식 location보다 먼저 확정된다는 근거 `:181-183`. `/api/dev-oidc/`는 별도 location 없이 `:188`에 포함된다.
- `.github/workflows/ci.yml:308` -- 주석이 "제공자 자격정보는 여전히 필요 없다"를 명시. `same-origin-integration`은 `:383`에서 스크립트를 그대로 부른다. **`ci.yml`은 바뀌지 않는다.** 바꾸면 `test-ci-scripts.sh:909-957`(job별 단계 목록 전문)도 함께 고쳐야 한다.
- `.gitleaksignore` -- 설정 파일 없이 기본 규칙으로 스캔하며 예외는 fingerprint 1건뿐. 위험은 이름이 아니라 **값**이다.

**프런트엔드**

- `frontend/src/screens/EntryScreen.tsx:98-119` -- 로그인 블록. `<section>` → `<h2>` → `<a className={styles.googleButton} href={authorizeHref(window.location.pathname)}>`(`:112`) → `<GoogleMark />`(`:113`) + `<span>{googleLabel}</span>`(`:114`) → `boundary`(`:118`) → `pending`(`:119`). **접근 가능한 이름은 `:114`의 `<span>`에서만 온다**(SVG는 `aria-hidden`·`focusable="false"`, `:165-167`). `GoogleMark`는 인라인 SVG(`:162-189`)로 path 4개의 `fill`이 토큰을 인용한다 — **네이버 N 로고도 같은 방식의 인라인 SVG여야 한다**(외부 이미지 금지).
- `frontend/src/screens/EntryScreen.module.css:141-149` -- `.signIn`이 이미 `flex column` + `--component-social-sign-in-gap`. 단 그 gap이 h2·버튼·안내문 **전부**에 걸리므로 제공자 목록 전용 래퍼를 둘지가 판단 지점이다. `.googleButton`(`:161-178`)이 인용하는 토큰 10개, `.googleMark`(`:180-191`), 포커스 규칙은 여기 없고 전역 `:focus-visible`(주석 `:158-159`).
- `frontend/src/styles/tokens.css:126-142` -- `/* components */` 섹션 안, 주석 `:126-131`("값의 소유자는 Google이다") 아래 `--component-social-sign-in-google-*` 11개. 파일 머리 규칙 `:4-6`이 "frontmatter의 평면 이름을 그대로 옮긴다. 값을 재해석하거나 새 이름을 만들지 않는다". BokriLog 소유 셋은 `:122-124`.
- `_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/DESIGN.md:112-134` -- `social-sign-in` frontmatter. `layout`·`min-target-size`·`gap`·`provider-treatment`·`focus` + `google-*` 11개. **`naver-*`가 없다.** 네이버 언급은 산문 `:204` 한 줄뿐.
- `frontend/stylelint.config.js:58-98`(네 범주 강제), `:124-126` -- `tokens.css` override에서 `declaration-strict-value: null`. 주석 `:122`가 "리터럴이 정의되는 유일한 장소"라고 명시. **새 브랜드 색 리터럴은 `tokens.css`에만 허용된다.** `lint:css` glob은 `package.json:16`의 `"src/**/*.css"`.
- `frontend/src/content/auth.ts:24` -- `AUTHORIZE_PATH = '/api/auth/authorize/google'`. `:79-85` `authorizeHref(pathname)`가 그 상수를 쓰고 `next`의 **유일한 생산자**다(근거 `:68-78`). `:172` `googleLabel`, `:177` `pending`, `:238-243` `signInCopyStrings`.
- `frontend/src/content/auth.ts:203-218` -- `recovery` 블록. 주석 `:205-206`이 **"지금 연결할 수 있는 수단이 Google 하나뿐이므로"**를 근거로 적는다. 같은 취지 주석이 `EntryScreen.tsx:74-84`·`:104-105`에도 있다. `recoveryCopyStrings`는 `:252-257`.
- `frontend/src/screens/EntryScreen.test.tsx:143-150` -- **`:148`이 "네이버 링크 없음"을, `:149`가 "카카오 링크 없음"을 단정한다. `:148`이 이 스토리가 뒤집는 단정이다.** `:119-129`(Google 버튼 href·tagName·라벨)는 exact 이름이라 그대로 통과. `:152-160` `signInCopyStrings` 전량, `:195-200` `role="status"` 개수 1, `:239-306` 복구 안내 4축.
- `frontend/src/App.test.tsx:102-105`, `:116-119` -- `/api/auth/authorize/google?next=…`·`/api/auth/authorize/google` 경로 리터럴. `:39`·`:157` Google 링크 존재.
- `frontend/e2e/auth-entry.spec.ts:27`·`:361-366` -- `PLAYWRIGHT_BASE_URL` 게이트. 게이트 안쪽 시험 7개(`:368`·`:405`·`:428`·`:445`·`:493`·`:521`·`:567`). `:42-45` `expectAuthScreenMounted` canary가 **Google 링크 visible을 요구** — 라벨이 바뀌면 전 시험이 깨진다. 데이터 요청 pathname 고정: `:260-262`·`:483`·`:561`. Tab 순서 `:87-132`가 `textContent.trim()`을 키로 문서 순서와 동등 비교 → **두 버튼 라벨이 달라야 한다.**
- `frontend/e2e/entry-screen.spec.ts:98-153` -- 도달한 **모든** 요소에 포커스 링·최소 44×44 요구. `:156`·`:168`·`:180`·`:198` 320px/768px/400%/200% 리플로에서 `elementsOverflowingViewport`·`clippedElements` 0건. **"네이버로 계속하기"가 320px에서 넘치거나 잘리면 실패한다.** `:239-274` 문서·번들 외 요청 0건.

## Tasks & Acceptance

**Execution:**

- [x] `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java` -- `AuthProperties`를 제공자별 값으로 넓히고(정규화는 compact 생성자 한 곳에 유지), `GoogleClientRegistrations`를 이름 → `ClientRegistration` 맵으로 바꾸고 클래스·상수를 제공자 중립으로 재명명한다. `from()`의 fail-closed를 **제공자 단위로 독립**시키고 `isConfigured()`를 "하나 이상 구성됨"으로 바꾼다. 네이버 등록은 `ClientRegistration.withRegistrationId("naver")`로 정적 엔드포인트 4개 + `userNameAttributeName(sub)` + `authorizationGrantType(AUTHORIZATION_CODE)` + `clientAuthenticationMethod(CLIENT_SECRET_POST)` + `scope(OPENID)` + `requireProofKey(true)` + `userInfoUri(null)`로 조립한다. `normalizedBaseUrl` 시그니처와 읽기 전용 목록은 건드리지 않는다. -- `CommonOAuth2Provider`에 NAVER가 없고 네이버가 `client_secret_basic`을 광고하지 않으므로 이 조립이 유일한 길이다. 판정을 제공자별로 가르지 않으면 한 제공자의 자격정보 부재가 다른 제공자의 로그인을 죽인다.
- [x] `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` -- `establish()`가 `OAuth2AuthenticationToken#getAuthorizedClientRegistrationId()`로 실제 제공자를 얻어 서비스에 넘긴다. `marker()`·목적지·흐름 세션 수명·경로 판정은 바꾸지 않는다. -- 이 값이 없으면 `provider` 컬럼이 계속 거짓말을 한다.
- [x] `backend/src/main/java/com/bokrilog/AuthSessionService.java` -- `establish` 시그니처에 registration id를 받아 `linkSocialIdentity`의 `provider` 인자로 넘긴다. 하드코딩 리터럴을 없앤다. -- `:106`이 인증 제공자와 무관하게 `"google"`을 저장하는 것이 AC-2와 1.10·1.11을 동시에 막는 결함이다.
- [x] `backend/src/main/java/com/bokrilog/AuthSessionController.java` -- `:141`의 제공자 이름 판정을 등록 저장소 조회로 바꾼다. 아는 제공자 목록·개수가 응답 본문·헤더에 새지 않게 한다. -- 상수 하나로 판정하면 네이버 경로가 404로 남는다.
- [x] `backend/src/main/resources/application.yml` -- `bokrilog.auth.naver.*`(client-id·client-secret + 재정의용 엔드포인트 4개) 이름을 더하고 주석으로 "네이버는 `CommonOAuth2Provider`에 없어 엔드포인트가 필수"임을 적는다. 자격정보 모양 리터럴을 넣지 않는다. -- 이름은 이 파일이 정하고 값은 환경이 준다는 규약을 유지한다.
- [x] `backend/src/main/java/com/bokrilog/DevOidcStubConfig.java` -- 제공자별 경로 세그먼트(`/api/dev-oidc/google`·`/api/dev-oidc/naver`)로 `DevOidcStub`을 **두 개 인스턴스** 만들고, 기동 guard가 네이버 자격정보도 함께 보게 하고, `@Primary` 등록 저장소가 두 등록을 들게 한다. **라우터 빈은 하나로 유지**하고 제공자 세그먼트로 분기한다. `DevOidcStub.java`는 바꾸지 않는다. -- 스텁 논리를 두 번 만들지 않는다는 계약이 이렇게 성립한다(issuer·client·키가 이미 인스턴스 단위다). 라우터를 둘로 쪼개면 `DevOidcStubProfileTest:43`·`:126`의 단수 단정이 깨진다.
- [x] `backend/src/test/java/com/bokrilog/OidcStubConfiguration.java` -- 제공자별 컨텍스트(`/google/jwks`·`/google/token`·`/naver/jwks`·`/naver/token`)와 제공자 인자를 받는 `issuer()`·`issueCode()`로 넓히고 `DynamicPropertyRegistry` 주입을 두 제공자로 늘린다. 루프백 서버는 하나로 유지한다. -- 시험 컨텍스트를 갈라지지 않게 하는 것이 CI wall-clock의 전제다.
- [x] `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java` -- `:129-133`·`:283`의 제공자 하드코딩 두 곳만 제공자 인자를 받게 바꾸고 기본값을 google로 둔다. 기존 헬퍼 시그니처와 `@DynamicPropertySource` 배치는 유지한다. -- 기본값을 두면 기존 시험 호출부가 전부 그대로 통과한다.
- [x] `backend/src/test/java/com/bokrilog/AuthNaverSignInTest.java` -- 네이버 왕복의 정상·취소·제공자 오류와 "네이버 미구성에서도 Google이 살아 있다"를 단정한다. 검증 실패 축은 `AuthTokenValidationTest`가 이미 제공자 비종속으로 덮으므로 **복제하지 않는다**. -- AC-1·AC-4.
- [x] `backend/src/test/java/com/bokrilog/AuthProviderIndependenceTest.java` -- 같은 `subject` 문자열을 두 issuer로 로그인해 계정 2건·매핑 2건·서로 다른 `user_id`(`userIdOf(issuer, subject)`)를, 그리고 `provider` 컬럼에 실제 제공자 값이 들어감을 단정한다. 삭제 진행 상태에서 네이버 재로그인·신규 계정 생성이 차단됨과 `erasePayload`가 네이버 매핑·세션을 함께 지움도 단정한다. -- AC-2·AC-5. 이메일 동일성은 시스템이 이메일을 받지 않아 흉내낼 자리가 없으므로 `(issuer, subject)` 축으로 단정한다.
- [x] `backend/src/test/java/com/bokrilog/DevOidcStubProfileTest.java` -- `hasSingleBean(DevOidcStub)`을 제공자 수로, 등록 내용 8축을 registration id 파라미터로 일반화하고, 기동 거부 guard 단정에 네이버 자격정보 2건을 더한다. `RouterFunction` 단수 단정은 그대로 둔다. -- fail-closed 계약이 제공자 수에 따라 조용히 헐거워지지 않게 한다.
- [x] `deploy/compose/compose.yaml` -- `:134` 뒤에 `BOKRILOG_NAVER_CLIENT_ID|_SECRET: ${...:-}` 두 줄을 같은 모양으로 더한다. -- 이름만 적고 값을 비우는 형태라 gitleaks에 걸리지 않는다.
- [x] `deploy/compose/verify-same-origin.sh` -- `:754-756`의 404 단정을 네이버 성공 왕복으로 **뒤집고**, `:812-929` 그룹을 제공자별로 돌려 두 왕복이 서로 다른 계정을 만드는 것까지 확인한다. `forbidden_fragments`(`:89-109`)에 네이버 이름 2개를 더하고, code·state 누출 검사(`:1294-1308`)에 두 번째 왕복 값을 더하고, `identity_social_link` 기대 행 수(`:925`·`:956`·`:995-996`)를 맞춘다. `set -o pipefail`에서 `생산자 | grep -q`를 조건에 쓰지 않는다. -- 판정을 스크립트가 소유하고, 뒤집는 단정을 남겨 두면 이 스토리가 CI에서 먼저 깨진다.
- [x] `_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/DESIGN.md` -- `social-sign-in` frontmatter에 `naver-*` 리프를 **공식 가이드가 공표한 값만**으로 더한다(`naver-background: #03a94d`, `naver-foreground: #ffffff`, `naver-logo: #ffffff`, `naver-logo-size: 16px`, `naver-logo-gap: 8px`). 모서리 리프는 만들지 않는다 — 가이드가 공표하지 않았다. `:118-123`·`provider-treatment` 주석을 제공자 복수형으로 고친다. -- `tokens.css`가 이 frontmatter의 사본이라는 규칙이 정본을 여기 둔다. `DESIGN.md:204`가 이미 세 제공자 고정 순서와 공식 가이드 우선을 결정했으므로 이것은 그 결정을 채우는 일이며 새 버전 발행이 아니다.
- [x] `frontend/src/styles/tokens.css` -- `--component-social-sign-in-naver-*`를 DESIGN.md frontmatter에서 **그대로** 옮긴다. 주석 `:126-131`을 제공자 복수형으로 고치고, 모서리는 기존 `--radius-*`를 쓴다는 사실과 그 근거(가이드 본문에 없음)를 주석으로 남긴다. 없는 토큰을 지어내지 않는다. -- 리터럴이 허용되는 유일한 파일이며 컴포넌트 CSS는 `var()`만 쓴다.
- [x] `frontend/src/content/auth.ts` -- `AUTHORIZE_PATH`를 닫힌 제공자 목록과 `authorizePath(provider)`로 바꾸고 `authorizeHref`가 제공자를 받게 한다(`next` 생산자는 계속 하나). `naverLabel`을 더하고 `pending`을 카카오만으로 줄이고 `signInCopyStrings`에 새 라벨을 편입한다. `recovery`를 제공자 중립으로 바꿔 **두 제공자의 공식 복구 안내를 언제나 함께** 보여 준다. `:8-13`의 카피 제약을 지킨다. -- 화면이 사용자가 어느 제공자를 썼는지 알 수 있게 만들면 그 자체가 계정 존재 신호다. 항상 둘을 보여 주는 것이 유일한 비누출 배치다.
- [x] `frontend/src/screens/EntryScreen.tsx` + `EntryScreen.module.css` -- 네이버 버튼을 Google 다음 고정 순서로 더한다. N 로고는 `GoogleMark`와 같은 방식의 인라인 SVG(`aria-hidden`·`focusable="false"`)이고 접근 가능한 이름은 `<span>` 텍스트 하나에서만 온다. 제공자 목록 gap이 안내문에까지 걸리지 않게 배치를 정리한다. 포커스 규칙을 선언하지 않는다. 복구 안내를 두 제공자로 렌더한다. -- 외부 이미지·CDN이 금지이므로 로고는 인라인 SVG뿐이다. 320px에서 넘치거나 잘리면 `entry-screen.spec.ts`의 리플로 시험이 잡는다.
- [x] `frontend/src/screens/EntryScreen.test.tsx`, `frontend/src/App.test.tsx` -- `EntryScreen.test.tsx:148`의 "네이버 링크 없음"을 존재·href·라벨 단정으로 뒤집고 `:149`(카카오)는 남긴다. `signInCopyStrings` 전량 단정과 `role="status"` 개수 1을 유지한다. `App.test.tsx:102-119`의 google 경로 리터럴을 제공자별로 가른다. 복구 안내 시험을 두 제공자로 넓힌다. -- 기존 단정 축을 줄이지 않고 뒤집을 것만 뒤집는다.
- [x] `frontend/e2e/auth-entry.spec.ts` -- `expectAuthScreenMounted`(`:42-45`) canary가 두 버튼을 보게 하고, 게이트 안쪽 왕복을 네이버로도 돌린다. 고정 순서·키보드 도달·오류 복귀·axe WCAG 2.2 AA 0위반을 단정하고 데이터 요청 pathname 목록(`:260-262`·`:483`·`:561`)을 맞춘다. -- 인증된 화면 마크업과 실제 브라우저 왕복을 보는 유일한 층이다.

첫 구현 뒤 실측에서 드러난 두 결함을 고치며 더한 작업이다.

- [x] `deploy/compose/verify-same-origin.sh` -- `psql_value`·`psql_exec`의 `docker compose exec` stdin을 `/dev/null`로 닫고, 왕복 루프가 제공자 수만큼 돌았음을 단정하고, 왕복 결과 변수를 루프 앞에서 빈 값으로 선언한다. -- `compose exec`는 `-T`로도 stdin을 컨테이너로 넘기므로 루프 안의 psql이 heredoc에 남은 제공자 이름을 먹어 네이버 leg이 시작조차 하지 않았다. 없는 단정은 실패로 보이지 않으므로 루프 완주 단정이 그 형태를 잡고, 빈 값 선언은 `set -u`가 아래 guard보다 먼저 스크립트를 죽이는 것을 막는다.
- [x] `backend/src/test/java/com/bokrilog/DevOidcStubProfileTest.java` -- 라우터 빈이 제공자마다 경로 넷을 실제로 받는지(그리고 토큰 경로가 `POST`뿐이고 모르는 제공자 경로가 없는지) `RouterFunction#route`로 단정한다. -- 스텁 표면이 둘인데 시험이 루프백 쪽만 지나 개발·CI 스택 라우터의 네이버 절반이 비어도 `build`가 통과하던 공백을 Docker 없이 닫는다.
- [x] `frontend/src/styles/tokens.css` + `DESIGN.md` frontmatter + `EntryScreen.module.css` -- 네이버 레이블 크기·굵기 토큰(20px·700)을 더하고 로고를 24px로 올리고 내부 여백을 `--space-3`으로 좁힌다. 색 토큰은 그대로다. -- 공표된 색 조합의 대비 3.095:1은 일반 텍스트 4.5:1을 넘지 못하고 큰 텍스트 3:1은 넘으므로, 색을 바꿀 수 없는 버튼에서 접근성 하한을 지키는 축이 크기 하나다(사람의 결정, 아래 Change Log).
- [x] `frontend/e2e/auth-entry.spec.ts` -- 네이버 버튼의 공표된 색 두 개, 큰 텍스트 문턱, 레이블 < 로고 높이, 320px 한 줄을 이름으로 단정한다. -- axe 위반은 "왜 그 값이어야 하는가"를 말해 주지 않으므로 색을 바꾸는 회귀와 크기를 되돌리는 회귀가 먼저 여기서 걸리게 한다.

리뷰 세 층이 낸 patch 판정 11건이다(2026-09-09).

- [x] `frontend/src/screens/EntryScreen.tsx` -- `NaverMark`의 손으로 쓴 path를 **공식 애셋에서 추출한 10점 직선 경로**로 바꾸고 `viewBox`를 `0 0 16 16`으로 맞춘다. 출처 URL·파일명·내려받은 날짜와 "bbox가 정사각형이라 24px 박스에서 잉크가 박스를 가득 채운다"는 사실을 주석에 남기고, 같은 사실을 위 `## Code Map`의 "검증된 외부 사실"에 한 줄 더한다. 색·크기 토큰은 그대로다. -- 이전 경로는 `viewBox="0 0 20 20"` 안에서 잉크가 10×9만 차지해 24px 박스의 실제 잉크 높이가 10.8px이었고, 그러면 가이드의 "텍스트는 로고 높이보다 작은 크기"가 렌더 결과에서 깨진다(레이블 20px). 게다가 가이드가 금지하는 첫 항목이 로고 변형이다.
- [x] `frontend/e2e/auth-entry.spec.ts` -- 로고 높이 단정이 `<svg>` **요소 박스**가 아니라 `<path>`의 `getBBox()`와 `viewBox` 스케일로 계산한 **렌더된 잉크 높이**를 재게 한다. 잉크 높이가 요소 박스 높이와 사실상 같은지도 함께 본다. -- 요소 박스는 CSS 크기 하나로 정해지므로 경로가 박스의 절반만 채워도 24px로 읽힌다. 그 단정은 선언만 확인하고 렌더 결과를 보지 않는다.
- [x] `frontend/e2e/auth-entry.spec.ts` -- 같은 시험에 로고 `fill`이 `rgb(255, 255, 255)`임, 로고-레이블 간격이 공표값 `8px`임, 제공자 목록 래퍼의 `row-gap`이 `--component-social-sign-in-gap`(12px)임을 더한다. -- 셋 중 어느 것도 단정이 없어서 토큰 이름 오타 하나로 로고가 검정으로 그려지거나 두 버튼이 맞붙어도 전 층이 green이었다.
- [x] `frontend/src/screens/EntryScreen.tsx` -- 제공자별 버튼 클래스·로고를 2항 삼항식에서 `Record<SignInProvider, …>` 닫힌 맵 둘로 바꾼다. -- 두 삼항식의 else가 모두 네이버라 `SIGN_IN_PROVIDERS`에 카카오를 더하는 순간 카카오 버튼이 네이버 색과 N 로고로 렌더되고 타입 검사도 시험도 막지 못한다. 닫힌 맵은 제공자가 늘 때 누락을 타입 검사가 잡는다.
- [x] `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java` + `AuthNaverSignInTest.java` -- `ProviderClientRegistrations.from()`에 "두 등록의 issuer가 겹치면 기동 거부"를 더하고 `from()` 직접 호출로 그 거부와 메시지 규율(자격정보·`BOKRILOG_` 이름·제공자 이름 미노출)을 단정한다. -- 조회 키가 `(issuer, subject)` 전역 유일이므로 `bokrilog.auth.naver.issuer-uri`에 Google issuer가 잘못 들어오면 네이버 로그인이 `provider='google'` 매핑을 찾아 그 계정의 세션을 개설한다. SPEC이 Never로 금지한 병합이 구성 오류 하나로 열린다. 조회 키에 provider를 더하는 방향은 `identity` 공개 API 변경(Ask First)이라 택하지 않았다.
- [x] `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java` + 콜백 호출부 6파일 -- 제공자 인자 없는 `callback`·`callbackWithError` 오버로드를 없애고 `Authorization`이 들고 있는 제공자를 쓰는 형태로 바꾼다. 인가 요청이 없는 요청(외부 사이트가 콜백 URL을 그냥 여는 모양)만 제공자를 이름으로 받는 형태를 쓴다. -- 네이버 흐름 뒤 그 오버로드를 부르면 요청이 google 콜백 경로로 가고, 실패를 단정하는 시험이 기대한 실패 축이 아니라 경로 불일치로 통과한다. `issueCode(Authorization, TokenPlan)`가 같은 이유로 이미 제공자를 인자로 받지 않는다.
- [x] `deploy/compose/verify-same-origin.sh` -- `round_trip()` 진입에서 `rt_flow_cookie`·`rt_session_cookie`·`rt_csrf_token`·`rt_code`·`rt_state` 다섯 값을 빈 값으로 되돌린다. `local`은 쓸 수 없다 — 루프의 `case`가 함수 반환 **뒤에** 그 값을 `google_*`·`naver_*`로 옮기므로 `local`이면 그 경로가 `set -u` 아래에서 죽는다. -- 이 스토리가 방금 고친 결함이 "leg 하나가 조용히 사라진다"였고 그때 남는 상태가 정확히 이 전역들이다. 두 번째 leg이 첫 leg의 값을 물려받으면 `case`가 google의 값을 `naver_*`로 옮기고 "두 왕복이 서로 다른 세션을 만들었다"가 같은 값을 두 번 보며 통과한다.
- [x] `frontend/src/screens/OnboardingScreen.test.tsx`, `SignedInScreen.test.tsx` -- 미인증 폴백 화면의 버튼 단정을 `SIGN_IN_PROVIDERS` 루프로 바꾼다. -- 같은 diff가 `expectAuthScreenMounted`·`App.test.tsx`·`EntryScreen.test.tsx`에는 "하나만 보면 나머지 제공자 버튼이 사라져도 통과한다"는 이유로 두 버튼 루프를 넣었는데 이 두 파일만 기계적 개명으로 남았다.
- [x] `frontend/src/content/auth.ts` + `## Code Map` -- 네이버 복구 URL의 실측 근거(HTTP 200, 2026-09-09)를 카피 주석과 "검증된 외부 사실" 목록에 남긴다. URL 자체는 바꾸지 않는다. -- 화면이 외부로 내보내는 유일한 링크이며 죽은 주소는 카피의 약속을 지키지 못한다.
- [x] `_bmad-output/implementation-artifacts/epic-1-context.md` -- `## Goal` 마지막 문장을 "1.6은 리뷰, 다음은 1.7 카카오"로 맞춘다. `CLAUDE.md`는 건드리지 않는다(머지 뒤 별도 docs PR이 그 진행 지점을 소유한다). -- 구현자가 갱신한 `DEVELOPMENT.md`와 같은 사실이 되게 한다.
- [x] `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` -- 항목 23(`identity` 행 잠금 대기 상한)·27(`identity`↔`spring_session` 결합 역전)에 **재차 미룸** 판정을, 이 라운드가 append한 항목 38~44에 **미해소** 판정을 적고 `## 상태 요약` 수치를 44건 기준으로 맞춘다. `deferred-work.md`는 건드리지 않는다. -- 근거는 아래 `## Design Notes` 마지막 절이 소유하고, 원장 자신의 `## 갱신 규칙`이 "append한 라운드가 그 라운드 안에서 판정도 적는다"를 요구한다.

**Acceptance Criteria:**

- Given 두 제공자의 스텁 왕복을 모두 지난 저장소, when `identity_social_link`를 검사하면, then 각 행의 `provider`가 인증에 실제로 쓰인 registration id이고 어느 행도 다른 제공자의 이름을 갖지 않는다.
- Given `develop`의 Story 1.1~1.5 계약, when 이 스토리의 변경을 얹으면, then `GET /api/nope` 404·`POST /api/status` 405·`POST /api/auth/session` 403·반복 로그아웃 403·오류 본문 전문 동등성·보안 헤더 5개·CSP 전문·캐시 헤더 두 갈래가 모두 그대로 통과한다.
- Given 한 제공자만 구성된 환경, when 그 제공자로 로그인하면, then 성공하고, 구성되지 않은 제공자의 인가 시작만 공통 오류 계약으로 fail-closed되며 환경 변수 이름이 응답·로그에 남지 않는다.
- Given `identity` 모듈, when 이 스토리가 끝나면, then `identity`의 공개 API·내부 코드·스키마가 바뀌지 않았고 `ModuleBoundaryTest`가 경계와 미노출을 그대로 단정한다.
- Given 진입 화면, when 키보드만으로 조작하면, then 두 버튼이 문서 순서대로 `Tab` 도달되고 각각 최소 44×44px이며 전역 포커스 링이 보이고 axe WCAG 2.2 AA 위반이 0건이다.
- Given 320 CSS px과 400% 확대, when 진입 화면을 렌더하면, then 두 버튼 모두 뷰포트를 넘치거나 잘리지 않는다.
- Given 브라우저 경계, when 진입 화면과 네이버 왕복 전후를 검사하면, then `localStorage`·`sessionStorage`·IndexedDB 항목이 0건이고 문서·번들 외 요청이 0건이며 외부 origin으로 나가는 요청이 0건이다.
- Given 실연동, when 이 스토리가 끝나면, then 실제 네이버 클라이언트 등록·사전 검수는 통과하지 않은 상태이며 에픽은 **실연동 미검증**을 유지한다.

## Spec Change Log

- 2026-09-09 첫 구현. frozen 블록은 바꾸지 않았다. 아래 다섯 건은 구현 중에 확정한 결정이고 마지막 하나는 **사람의 결정을 기다리는 충돌**이다.

  - **`GoogleClientRegistrations` → `ProviderClientRegistrations`.** 단일 `@Nullable ClientRegistration` 필드를 이름 → `ClientRegistration` 맵으로 바꾸고 `AuthProperties.Google`을 `AuthProperties.Provider`로 재명명해 제공자 중립으로 만들었다. `GOOGLE_REGISTRATION_ID`는 이름을 그대로 두고 `NAVER_REGISTRATION_ID`를 옆에 더했다 — 값이 `"google"`인 상수를 "제공자 중립"으로 개명할 방법이 없고, 시험 호출부 여러 곳이 그 이름을 인용한다. `from()`은 제공자마다 따로 판정해 맵에 넣거나 넣지 않으므로 fail-closed가 제공자 단위로 독립한다. `publicBaseUrl`만 두 제공자가 공유하는 값이며 그것이 비면 어느 등록도 만들어지지 않는다(redirect URI를 조립할 수 없다).
  - **"아는 제공자 이름"은 등록 조회로 판정할 수 없다.** Code Map은 `AuthSessionController:141`을 "등록 저장소에 물어보는 형태"로 바꾸라고 했지만, 구성된 등록을 술어로 쓰면 두 답이 모두 404가 된다 — 등록이 있으면 인가 요청 필터가 302로 끝내므로 이 컨트롤러에 도달하는 것은 언제나 맵에 없는 이름이고, 그러면 "아는 제공자인데 구성되지 않았다"의 500이 사라진다(`StatusApiTest`가 그 500을 고정한다). 그래서 `ProviderClientRegistrations`에 구성 여부와 무관한 닫힌 이름 집합(`isSupportedProvider`)을 두었다. 판정의 소유자는 여전히 등록 저장소 하나다.
  - **스텁은 빈 둘이고 라우터는 하나다.** `Map<String, DevOidcStub>` 하나를 빈으로 두면 그 타입을 주입받는 자리에서 Spring이 "`DevOidcStub` 타입 빈 전부"를 모으려 하고, 그런 빈이 없어 주입이 실패한다. 제공자별 `@Bean` 둘 + `@Qualifier` 주입으로 바꿨고, 그래서 `DevOidcStubProfileTest`가 "스텁 빈이 제공자 수만큼"을 빈 개수로 단정할 수 있다. 라우터는 계약대로 하나이며 `RouterFunctions.route()` 빌더 하나에 제공자별 경로를 쌓는다. 경로는 `/api/dev-oidc/<제공자>/...`이고 issuer도 그 세그먼트를 포함한다 — 한 경로에 두 스텁을 올리면 어느 issuer의 키로 서명된 토큰인지 요청만으로 정해지지 않는다. `DevOidcStub.java`는 한 줄도 바꾸지 않았다.
  - **시험 스텁도 인스턴스 둘·루프백 서버 하나다.** `OidcStubConfiguration`의 컨텍스트를 `/<제공자>/jwks`·`/<제공자>/token`으로 갈랐고, `issuer()`·`clientId()`·`clientSecret()`·`issueCode()`에 제공자 인자를 받는 형태를 더했다. 인자 없는 형태는 Google을 뜻하도록 남겼다 — Story 1.4·1.5의 호출부 약 40곳이 그 모양이고, 그 시험들이 고정하는 것은 제공자에 종속되지 않는 계약이다. `AuthFlowTestBase.Authorization`에 `provider` 필드를 더해 `issueCode(Authorization, TokenPlan)`가 흐름과 같은 제공자의 스텁을 고르게 했다 — 인자로 다시 받으면 흐름과 다른 제공자에 code를 발급하는 호출이 가능해지고 그것이 곧 계약 우회다.
  - **`StatusApiTest`의 모르는 제공자 탐침을 카카오로 옮겼다(task 목록에 없던 변경).** 그 시험은 `GET /api/auth/authorize/naver`의 404를 고정하고 있었고, 이 스토리가 네이버를 아는 제공자로 만들었으므로 그 기대는 성립할 수 없다. 기대값을 무른 것이 아니라 I/O 매트릭스가 그 행을 카카오로 옮겨 둔 것을 따랐고(마지막 행: `GET /api/auth/authorize/kakao` → 404), 아는 제공자 둘의 500이 서로 같은 본문임을 함께 단정했다. `verify-same-origin.sh`의 같은 탐침도 카카오로 옮겼다. Code Map이 이 두 지점을 지목하지 않은 것은 누락이다.

  - **미해결 충돌(사람의 결정 필요): 네이버 공식 색과 WCAG AA 텍스트 대비가 함께 성립하지 않는다.** 가이드가 공표한 조합(배경 `#03A94D` + 레이블 `#FFFFFF`)을 그대로 쓰면 axe가 `color-contrast`를 낸다 — 실측 **3.09:1**이고 요구값은 4.5:1이다(`fontSize: "10.5pt (14px)", fontWeight: "normal"`; `--type-label-font-weight: 600`은 axe의 bold 기준 700에 못 미친다). 이 스토리의 AC는 "axe WCAG 2.2 AA 위반이 0건"이고 `DESIGN.md:190`은 "일반 텍스트 4.5:1"을 하한으로 못박았는데, frozen `## Boundaries & Constraints`는 "네이버 버튼의 색은 공식 가이드가 BokriLog 토큰보다 우선한다"를 요구한다. 세 가지가 동시에 성립할 수 없다.

    구현은 **SPEC이 지시한 대로**(녹색 + 흰 레이블) 두고 axe 단정도 그대로 남겼다. 어느 쪽도 조용히 무르지 않는 것이 이 저장소의 규율이기 때문이다 — 색을 바꾸면 frozen 불변식과 "지정 컬러는 변경할 수 없으며"를 어기고, axe 단정을 완화하면 `## Boundaries & Constraints`의 Never("기존 시험의 기대값을 코드에 맞춰 무르는 것")를 어긴다. 그 결과 preview E2E 6개가 `color-contrast`로 실패한다(`auth-entry.spec.ts`의 axe 5개, `entry-screen.spec.ts`의 axe 1개).

    사람이 고를 수 있는 길은 셋이고 각각 어느 조항을 건드리는지가 다르다.
    1. **타이포그래피로 해결(권장 검토안).** 글꼴은 `provider-treatment`가 넘기는 범주에 없고 DESIGN.md가 명시적으로 BokriLog에 남겨 둔 축이다. 네이버 레이블을 WCAG 큰 텍스트 기준(≥18.67px + `font-weight: 700`, 또는 ≥24px)으로 올리면 요구 대비가 3:1이 되어 3.09:1이 통과한다. 대가는 두 버튼의 레이블 크기가 갈리는 것과, 가이드의 "텍스트는 로고 높이보다 작게"를 지키려면 `naver-logo-size`를 공표 최솟값 16px보다 크게 두어야 한다는 것이다(16px은 최솟값이므로 가이드 위반은 아니다).
    2. **가이드가 공표한 다른 조합을 쓴다.** 네이버 가이드에는 흰 배경 + 녹색 로고 + 어두운 레이블 변형이 있고 그 조합은 4.5:1을 쉽게 넘긴다. 이 SPEC의 "검증된 외부 사실"에는 그 변형의 레이블 색이 없으므로 값을 확인하지 않고는 쓸 수 없다(없는 브랜드 값을 지어내지 않는다). 가이드의 "타사 버튼이 컬러일 경우 흰색 배경 버튼 지양"은 우리 배치에 적용되지 않는다 — Google 버튼이 흰 배경이다.
    3. **대비 예외를 명시적으로 기록한다.** 브랜드 의무를 근거로 그 노드 하나에 좁힌 axe 예외를 두고 `deferred-work.md`에 올린다. 가장 싸지만 접근성 하한을 실제로 낮추는 유일한 선택이다.

- 2026-09-09 실측 검증 뒤. 위 항목들은 그대로 두고 아래에 결론과 새 결정을 덧붙인다. 첫 구현이 남긴 두 결함을 고쳤고 그중 하나는 위 미해결 충돌의 해소다.

  - **위 대비 충돌의 결론: 1번(타이포그래피)을 사람이 골랐다.** 색은 한 픽셀도 바꾸지 않는다. 레이블을 `20px`·굵기 `700`으로 올려 axe의 큰 텍스트 판정(`fontSize ≥ 24px`, 또는 `fontSize ≥ 18.67px`이면서 `fontWeight ≥ 700`; `boldValue: 700`·`boldTextPt: 14`·`largeTextPt: 18`을 axe-core 4.13.0 소스에서 확인)에 넣으면 요구 대비가 3:1이 되고 실측 3.095:1이 그것을 넘는다. 굵기 600으로는 성립하지 않는다 — 그 값은 bold로 세지 않는다.

    가이드의 "텍스트는 로고 높이보다 작은 크기"를 함께 지키려면 로고가 레이블보다 커야 하므로 `naver-logo-size`를 `24px`로 올렸다. 가이드가 주는 것은 하한("완성형 16px 이상")뿐이고 24px은 그 하한을 넘으므로 위반이 아니다. 그래서 이 두 값(`naver-label-font-size`·`naver-label-font-weight`)과 로고 크기는 **공표값이 아니라 BokriLog가 고른 값**이고, 그 사실을 `DESIGN.md` frontmatter와 `tokens.css` 주석이 함께 적는다. 글꼴 축은 `provider-treatment`가 넘기는 범주에 없어 원래부터 BokriLog 소유이므로 이 결정은 frozen `## Boundaries & Constraints`의 "색·로고·여백은 가이드가 우선"과 충돌하지 않는다. Google 버튼은 14px/600 그대로다(16.48:1).

    내부 여백은 `--space-4`에서 `--space-3`으로 좁혔다. 320 CSS px에서 버튼 폭이 238px이고 로고 24 + 간격 8 + 레이블 한 줄이 그 안에 들어가야 한다. 실측 레이블 폭은 142.8px이라 `--space-4`로도 들어가지만 그 값은 이 기기의 한글 자간에 기댄 값이고, 한글 글자당 1em을 쓰는 대체 폰트에서는 약 165px이 되어 여유가 한 자릿수 px로 떨어진다. 좁은 여백이 그 경우를 흡수한다. 320px·400%·200%·768px 네 뷰포트에서 실제로 측정해 한 줄·넘침 0·잘림 0을 확인했다.

    axe 단정은 하나도 약화하지 않았고 대비 예외도 두지 않았다. 3번(예외 기록)은 고르지 않았다.

  - **네이버 왕복이 compose 스택에서 한 번도 실행되지 않았다(블로커, 해소).** 원인은 백엔드도 스텁도 아니라 `verify-same-origin.sh`의 stdin이었다. `docker compose exec`는 `-T`로도 stdin을 컨테이너로 넘기므로, 제공자 왕복 루프의 첫 iteration이 `psql_value`에 닿는 순간 heredoc에 남은 `naver` 줄이 통째로 먹히고 다음 `read`가 EOF를 본다. 그래서 네이버 iteration은 **시작조차 하지 않았고** 단정이 실패한 것이 아니라 존재하지 않았다 — 로그에는 아무것도 붉게 나오지 않고 집계 단정 둘만 어긋났다. `psql_value`·`psql_exec`의 stdin을 `/dev/null`로 닫아 그 계층에서 닫았다(같은 루프의 curl은 stdin을 읽지 않아 무해했고, 그래서 왕복 밖의 네이버 탐침 루프는 정상이었다).

    두 겹을 더 뒀다. 하나는 "왕복 루프가 제공자 수만큼 돌았다"는 단정이다 — 없는 단정은 실패로 보이지 않으므로 iteration 수 자체를 세는 자리가 있어야 같은 형태의 다음 사고가 붉게 나온다. 다른 하나는 왕복 결과 변수를 루프 앞에서 빈 값으로 선언한 것이다. `set -u` 아래에서 leg 하나가 빠지면 그 이름을 처음 읽는 줄에서 스크립트가 죽어 뒤의 단정 수백 개와 "어느 제공자의 쿠키를 얻지 못했다"를 보고하는 guard까지 함께 사라진다. 빈 값이면 그 guard가 실제로 돌아 이름을 말한다. 기대값을 낮추거나 `naver_session_cookie`에 기본값을 주는 처리는 하지 않았다 — 집계는 여전히 `2`를 요구한다.

  - **그 결함이 백엔드 시험에 잡히지 않은 공백도 닫았다.** 스텁 표면은 둘인데(개발·CI 스택의 `DevOidcStubConfig` 라우터, 백엔드 시험의 `OidcStubConfiguration` 루프백 서버) 시험이 지나는 것은 뒤의 하나뿐이었다. 그래서 라우터의 네이버 절반이 비어 있어도 `build`는 통과하고 결함은 compose 검증까지 가서야 드러나며, 그 검증마저 위 stdin 결함으로 조용히 건너뛰면 아무도 보지 않는다. `DevOidcStubProfileTest`에 `RouterFunction#route`를 직접 물어보는 단정을 더해 제공자마다 경로 넷(discovery·JWKS·authorize·token)이 실제로 매칭되는지, 토큰 경로가 `POST`뿐인지, 모르는 제공자의 경로가 없는지를 본다. `ServerRequest.create`가 요청 경로를 스스로 파싱하므로 서블릿 컨테이너도 Docker도 필요 없고 `RouterFunction` 단수 단정은 그대로다. 남는 공백은 핸들러 **본문**의 동작이며 그것은 여전히 compose 검증이 소유한다 — 그 층을 시험으로 옮기면 스텁이 두 벌이 된다.

- 2026-09-09 리뷰 세 층 뒤. 위 항목들은 그대로 두고 아래에 이 라운드의 patch 판정 11건과 그 근거를 덧붙인다. frozen 블록은 이번에도 바꾸지 않았다.

  - **네이버 N 로고를 공식 애셋의 진짜 경로로 교체했다(이 라운드의 가장 큰 변경).** 첫 구현의 `NaverMark`는 손으로 쓴 path였고 `viewBox="0 0 20 20"` 안에서 잉크가 x 5~15 · y 5.5~14.5(10×9)만 차지했다. 24px 박스로 스케일하면 **실제 잉크 높이가 10.8px**이라 가이드의 "텍스트는 로고 높이보다 작은 크기"가 렌더 결과에서 깨진다(레이블 20px). 게다가 가이드가 금지하는 첫 항목이 로고 변형이므로 근사로 그린 경로 자체가 위반 후보다.

    사용자 허락을 받아 공식 버튼 애셋 `https://developers.naver.com/inc/devcenter/downloads/bi/NAVER_login_KR.ai`(공식 버튼 가이드 `developers.naver.com/docs/login/bi/bi.md`가 링크하는 한글 버튼 애셋, PDF 1.5, 2026-09-09 내려받음, 애셋 내 버튼 파일 날짜 2025-12-04)에서 벡터를 추출했다. 그 안의 N 마크는 곡선 없는 **10점 직선 경로**이고 bounding box가 **정확한 정사각형**이다 — 버튼 크기별 16×16·18×18·20×20 인스턴스가 모두 정사각형을 가득 채운다. 16×16 인스턴스를 SVG 좌표로 변환해(y축 뒤집음) `viewBox="0 0 16 16"`과 함께 넣었고, 그래서 24px 박스에서 잉크가 박스를 가득 채운다. 색·크기·간격 토큰은 한 픽셀도 바뀌지 않았다. 출처와 그 사실은 `## Code Map`의 "검증된 외부 사실"과 `NaverMark`의 주석이 함께 소유한다.

  - **로고 높이 단정은 요소 박스가 아니라 잉크를 재야 한다.** 첫 구현의 `auth-entry.spec.ts`는 `<svg>` **요소 박스**의 `getBoundingClientRect().height`(24px)로 "레이블 < 로고 높이"를 통과시켰다. 요소 박스는 CSS 크기 하나로 정해지므로 경로가 박스의 절반만 채워도 그 값은 24px이고, 실제로 위 10.8px 상태가 그 단정을 통과했다 — 즉 그 시험은 CSS 선언을 확인하고 렌더 결과를 보지 않았다. `<path>`의 `getBBox()`에 `viewBox` 스케일(`preserveAspectRatio` 기본값에서 두 축 비율의 최솟값)을 곱해 렌더된 잉크 높이를 계산하는 형태로 바꿨고, 잉크 높이가 요소 박스 높이와 사실상 같은지도 함께 단정한다. 경로가 다시 박스보다 작아지면 그 사실이 먼저 붉게 나온다.

  - **단정 없이 통과하던 축 셋을 같은 시험에 더했다.** 로고 `fill`(`rgb(255, 255, 255)`), 로고-레이블 간격(공표값 8px), 제공자 목록 래퍼의 `row-gap`(`--component-social-sign-in-gap`, 12px)이다. 셋 모두 토큰 이름 오타 하나로 조용히 깨지는 축이었다 — `fill`이 빈 `var()`로 접히면 로고가 검정으로 그려지고(녹색 배경에서 대비가 무너진다) gap 토큰이 사라지면 두 버튼이 맞붙는다. axe도 두 버튼의 폭·높이 등식도 그 셋을 보지 않는다.

  - **제공자 UI 분기를 닫힌 맵으로 바꿨다.** `provider === 'google' ? styles.googleButton : styles.naverButton`과 `provider === 'google' ? <GoogleMark /> : <NaverMark />` 두 삼항식은 else가 곧 "나머지 제공자 전부"였다. `SIGN_IN_PROVIDERS`에 카카오를 더하는 순간 카카오 버튼이 **네이버 색과 N 로고로** 렌더되고 타입 검사도 시험도 막지 못한다(라벨만 카카오라 접근 가능한 이름 단정은 통과한다). `Record<SignInProvider, …>` 둘로 바꿨다 — `content/auth.ts`의 `signIn.labels`·`NOTICES`가 이미 쓰는 형태이며 제공자가 늘 때 누락을 타입 검사가 잡는다. 값 타입에 `undefined`가 붙는 것은 `noUncheckedIndexedAccess` 아래 CSS 모듈 조회가 `string | undefined`이기 때문이고 `className`이 그 값을 그대로 받는다.

  - **두 등록이 같은 issuer를 갖는 구성을 기동에서 거부한다.** `AuthSessionService`의 조회는 `findUserIdBySocialIdentity(issuer, subject)`이고 `(issuer, subject)`는 전역 유일이다. 그래서 `bokrilog.auth.naver.issuer-uri`에 Google issuer가 잘못 들어오면 네이버로 로그인한 `sub`이 Google 쪽 매핑과 같은 namespace에서 조회되고, 그 매핑이 있으면 **그 계정의 세션이 개설된다** — frozen `## Boundaries & Constraints`가 Never로 금지한 "두 제공자 식별자의 병합·전환"이 구성 오류 하나로 열린다. `ProviderClientRegistrations.from()`이 만들어진 등록들의 issuer 겹침을 보고 `IllegalStateException`으로 기동을 멈춘다.

    조회 키에 provider를 넣는 방향은 택하지 않았다 — `identity` 공개 API 변경이고 이 SPEC의 Ask First다. 거부 메시지에는 자격정보도 `BOKRILOG_` 환경 변수 이름도 어느 제공자인지도 없다(`DevOidcStubConfig` guard와 같은 규율이고 `DevOidcStubProfileTest`가 그 규율을 스텁 쪽에 세워 두었다). `AuthNaverSignInTest`가 `from()`을 직접 불러 거부와 그 미노출을 함께 단정하며, Google issuer 문자열도 리터럴로 적지 않고 google-only 등록에서 읽어 온다.

  - **시험 헬퍼가 다른 제공자 콜백으로 새는 구멍을 닫았다.** `AuthFlowTestBase.callback`·`callbackWithError`에 제공자 인자 없는 오버로드가 있어 google로 접혔다. 네이버 흐름 뒤 그것을 부르면 요청이 **google 콜백 경로**로 가고, 그 요청은 흐름의 `state`·PKCE와 어긋나므로 실패를 단정하는 시험이 기대한 실패 축이 아니라 경로 불일치로 통과한다. 두 오버로드를 없애고 `Authorization`이 들고 있는 제공자를 쓰는 형태로 바꿨다(`issueCode(Authorization, TokenPlan)`가 같은 이유로 이미 그 모양이다). 쿠키·`state`를 흐름과 다르게 주는 시험은 `callback(Authorization, cookie, code, state)`를 쓰고, 인가 요청 없이 만드는 요청 — 외부 사이트가 콜백 URL을 그냥 여는 크로스 사이트 강제 로그아웃 시험 하나 — 만 제공자를 이름으로 받는 형태를 남겼다. 기존 시험의 기대값은 하나도 바뀌지 않았다.

  - **`round_trip()`의 결과 전역을 함수 진입에서 비운다.** 다섯 값(`rt_flow_cookie`·`rt_session_cookie`·`rt_csrf_token`·`rt_code`·`rt_state`)은 `local`이 될 수 없다 — 루프의 `case`가 함수 반환 **뒤에** 그 값을 `google_*`·`naver_*`로 옮기므로 `local`이면 그 경로가 `set -u` 아래에서 통째로 죽는다. 그래서 전역으로 남기고 진입에서 되돌린다. 비우지 않으면 leg 하나가 중간에 사라질 때 다음 leg이 앞 leg의 값을 물려받고, `case`가 google의 쿠키·code·state를 `naver_*`로 옮겨 "두 왕복이 서로 다른 세션을 만들었다"가 같은 값을 두 번 보며 통과한다. 위 라운드가 고친 결함(psql이 heredoc을 먹어 네이버 iteration이 시작조차 하지 않았다)이 남기는 상태가 정확히 그것이었다. 루프 앞의 `google_*`·`naver_*` 빈 값 선언은 그대로 있고 이 변경은 함수 안쪽에 같은 겹을 하나 더한다.

  - **두 폴백 화면 시험이 버튼 하나만 보던 것을 두 버튼 루프로 바꿨다.** `OnboardingScreen.test.tsx`·`SignedInScreen.test.tsx`의 미인증 복귀 단정은 기계적 개명만 되어 `labels.google` 하나를 보고 있었다. 같은 diff가 `expectAuthScreenMounted`·`App.test.tsx`·`EntryScreen.test.tsx`에는 "하나만 보면 나머지 제공자 버튼이 사라져도 통과한다"는 이유로 루프를 넣었으므로 그 형태를 두 파일에도 적용했다.

  - **네이버 복구 URL의 출처를 기록했다.** `https://nid.naver.com/user2/help/idInquiry`는 2026-09-09 실측에서 **HTTP 200**으로 해석된다. URL은 바꾸지 않고 그 사실과 날짜를 `## Code Map`의 "검증된 외부 사실"과 `content/auth.ts`의 카피 주석에 남겼다. 화면이 외부로 내보내는 유일한 링크이므로 주소를 추측으로 두지 않는다.

  - **진행 지점 문서와 해소 원장을 맞췄다.** `epic-1-context.md`의 `## Goal` 마지막 문장이 아직 "다음 착수 지점은 스트레치였던 Story 1.6"이었다. `DEVELOPMENT.md`와 같은 사실("1.6은 구현·검증을 마쳐 리뷰 상태, 다음은 1.7 카카오")로 맞췄고 `CLAUDE.md`는 건드리지 않았다 — 그 파일의 진행 지점은 머지 뒤 별도 docs PR이 소유한다. 해소 원장에는 항목 23·27의 **재차 미룸** 판정과, 이 라운드가 `deferred-work.md`에 append한 항목 38~44의 **미해소** 판정을 함께 적고 `## 상태 요약`을 44건 기준으로 맞췄다. 후자를 함께 적은 것은 원장 자신의 `## 갱신 규칙` 마지막 줄이 "append한 라운드가 그 라운드 안에서 판정도 적는다"를 요구하고, 그러지 않으면 표 수치가 `deferred-work.md`의 항목 수와 갈리기 때문이다. `deferred-work.md`는 한 줄도 고치지 않았다.

## Design Notes

**미해결 외부 사실 — 네이버가 `nonce`를 되돌려주는지 확인되지 않았다.** 네이버 공식 개발 가이드와 웹 가이드에서 `nonce`는 **0회** 등장하고, OIDC 인가 요청 파라미터 표(§3.5.4)에도 없고, discovery 문서에도 관련 필드가 없다. 반면 이 저장소는 `nonce`를 계약으로 고정해 두었다 — `AuthTokenValidationTest.java:109`·`:114`가 `nonce` 불일치와 **부재를 모두 거절**로 단정하고, Spring Security는 scope에 `openid`가 있으면 인가 요청에 `nonce`를 싣고 ID Token에서 그 값을 되찾지 못하면 검증을 실패시킨다. 즉 **네이버가 `nonce`를 무시하면 실제 왕복이 검증 단계에서 실패한다.** 이 사실은 등록된 클라이언트 없이는 닫히지 않고, 그 등록은 Epic 1의 **실연동 미검증** 게이트가 이미 소유한다. 그래서 이 스토리는 불변식을 지키는 쪽을 고른다 — `nonce`를 그대로 보내고 검증을 그대로 두고, 위험을 여기 적어 게이트가 그것을 발견하게 한다. 확인되지 않은 이유로 보안 계약을 먼저 헐겁게 만들면 그 완화가 어디서 왜 일어났는지 추적할 자리가 없어진다(같은 논리가 `application.yml:154-159`의 절대 수명 clamp 근거다). 완화는 Ask First다.

**등록을 맵으로 바꾸는 것이 최소 변경인 이유.** URL 표면은 이미 제공자 복수형이다 — `DefaultOAuth2AuthorizationRequestResolver`가 base URI 뒤에서 `{registrationId}`를 뽑고(`AuthSecurityConfig.java:311-312`) 콜백은 `CALLBACK_BASE_URI + "/*"` 와일드카드다(`:501-502`). Nginx도 접두사 매칭이라 무변경이다(`bokrilog.conf:188`·`:209`). 경로 판정(`isAuthorizationStart`·`isAuthenticationFlow`)과 실패 표지·목적지·흐름 세션 수명도 registration id를 보지 않는다. 단일 제공자를 실제로 전제하는 곳은 **등록 저장소 하나, `provider` 리터럴 하나, 스텁 하나** 셋뿐이고, 이 스토리가 그 셋만 넓힌다. fail-closed를 제공자 단위로 독립시키는 것이 그중 유일하게 미묘한 부분이다 — 현재 `from()`은 세 값 AND로 접혀 하나만 비어도 등록 전체가 사라지는데(`:183-186`), 그 판정을 제공자별로 두지 않으면 네이버 자격정보가 없는 개발 환경에서 Google 로그인까지 죽는다.

**스텁은 클래스 하나에 인스턴스 둘이다.** `DevOidcStub`은 issuer를 생성자 인자로 받고(`:86-87`) client id·secret·서명 키를 인스턴스마다 새로 만들며(`:90-101`) `discovery(...)`가 엔드포인트를 인자로 받는다(`:148`). registration id 문자열이 클래스 안에 아예 없다. 그래서 `new DevOidcStub(issuer)`를 두 번 부르면 **시그니처 변경이 0**이고 CLAUDE.md의 "스텁을 두 번 만들지 않는다"가 그대로 성립한다. 대가는 RSA 키 2개 추가 생성이다. 게다가 `authenticatedClient`(`:412-422`)의 주석이 "`client_secret_post`로 온 요청도 받아 둔다"고 이미 적어 두었으므로, 네이버 등록을 `CLIENT_SECRET_POST`로 두는 것이 스텁 변경을 만들지 않는다. 라우터 빈만 하나로 유지해야 한다(`DevOidcStubProfileTest:43`·`:126`).

**AC-2를 이메일로 단정할 수 없고, 그것이 더 강한 형태다.** AC-2는 "이메일·표시명이 같아도 병합하지 않는다"를 요구하지만 이 시스템은 이메일을 **받지 않는다** — 스키마에 컬럼이 없고(`V1__identity_account.sql`), `AccountLifecycleApi`에 인자가 없고(`:78-83` 주석이 그 이유를 적는다), 동의 범위가 `openid` 하나이며, `DevOidcStub`의 claim 집합(`:463-475`)에 email이 없다. `AuthAccountMappingTest.java:58-64`가 이미 같은 이유로 그 행을 subject 축으로 대체해 두었다. 그러므로 단정은 "이메일이 같은 두 계정이 병합되지 않는다"가 아니라 **"`(issuer, subject)`가 다르면 별개이고 병합 경로가 존재하지 않는다"**이며, 후자는 시험이 아니라 구조가 보증한다. 시험은 그 구조를 고정하는 역할만 한다. 없는 것을 시험으로 흉내내 통과시키지 않는다.

**복구 안내는 두 제공자를 언제나 함께 보여 준다.** 진입 화면은 사용자가 어느 제공자로 가입했는지 알 수 없고 **알아서도 안 된다** — 그 정보를 화면에 도달시키는 경로가 곧 계정 존재·소속 신호다(`AuthFlowHandlers.java:97-101`이 표지를 둘로 닫아 둔 것과 같은 이유다). 그래서 "당신이 쓴 제공자의 복구 절차"는 만들 수 없고, 남는 배치는 둘을 무조건 함께 보여 주는 것 하나다. 이 배치는 아무것도 누출하지 않고 `content/auth.ts:205-206`이 적은 "수단이 하나뿐"이라는 전제를 정직하게 갱신한다.

**브랜드 토큰은 가이드가 공표한 값에서 멈춘다.** 공식 가이드 페이지 본문이 수치로 주는 것은 색 둘(`#03A94D`·`#FFFFFF`), 로고 최소 크기(완성형 16px·아이콘형 18px), 로고-레이블 간격 8px, 왼쪽 정렬 시 로고 좌측 여백 20px, "텍스트는 로고 높이보다 작게"다. **모서리 반경은 없다** — 권위 있는 값은 내려받는 애셋(Figma/AI/PNG)에 있고 이 스토리는 그것을 열지 않는다. DESIGN.md는 모서리를 제공자의 열에 두었지만(`:118-123`), 제공자가 값을 공표하지 않은 축은 사실상 비어 있는 것이고 그 빈 자리를 추측한 브랜드 값으로 채우는 것은 "없는 토큰을 지어내지 않는다"를 정면으로 어긴다. 그래서 `naver-*`에는 공표된 값만 담고 모서리는 BokriLog의 `--radius-*`가 소유한다. 이 선택은 가이드가 실제로 금지한 것(지정 컬러 변경, 로고 변형·조합·그라데이션, 로고 축소, 타사 컬러 버튼과 함께 쓸 때의 흰 배경·크기 축소)을 하나도 건드리지 않는다. 나중에 애셋에서 값을 읽으면 토큰 하나가 `--radius-*`에서 `naver-radius`로 옮겨 갈 뿐이다.

**미룬 항목 두 건은 재차 미룬다.** `identity` 행 잠금 대기 상한은 JDBC·트랜잭션 정책 변경이라 이 스토리가 만드는 변경과 폭발 반경이 겹치지 않고, 값의 근거도 이 스토리에는 없다. `identity`↔`spring_session` 결합 역전(`IdentityAccountRepository.java:195-199`)은 `identity` 내부 변경이고 이 스토리는 `identity`를 한 줄도 바꾸지 않는다 — 여기서 손대면 "제공자를 늘린다"는 단일 목표에 관계없는 모듈 리팩터가 섞인다. 두 항목의 주인 없음은 그대로 남으므로 해소 원장에 "Story 1.6이 보았고 재차 미뤘다"를 근거와 함께 적는다. 실제로 이 스토리가 그 경로를 다시 지났다는 사실이 다음 판단의 재료다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 네이버 왕복·제공자 독립·스텁 프로필 시험 통과, 경고 0건(`-Werror`), jar 생성. Docker 데몬 필요(Testcontainers)
- `npm --prefix frontend ci && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test` -- expected: 전부 통과, 경고 0건. Node 24(`frontend/.nvmrc`)
- `npm --prefix frontend run test:e2e` -- expected: Story 1.1·1.4·1.5 회귀와 두 버튼 시험 통과
- `deploy/compose/verify-same-origin.sh` -- expected: 두 제공자 왕복 포함 모든 단정 통과, exit 0
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 전부 통과. `ci.yml`을 바꾸지 않았으므로 무변경 통과여야 한다
- `git status --short` -- expected: 빌드·시험 산출물이 미추적 파일로 남지 않음

**Manual checks:**
- `grep -rn "google" backend/src/main/java/com/bokrilog/AuthSessionService.java backend/src/main/java/com/bokrilog/AuthSessionController.java` -- expected: 제공자 리터럴 0건
- `grep -rn "naver" deploy/compose/verify-same-origin.sh` -- expected: 404 기대 단정이 남아 있지 않음
- `grep -rn "org.springframework.http\|jakarta.servlet\|RestController" backend/src/main/java/com/bokrilog/identity/` -- expected: 0건
- `git diff --stat develop -- backend/src/main/java/com/bokrilog/identity/ backend/src/main/resources/db/migration/` -- expected: 변경 0건
- `grep -rn "#03a94d\|#03A94D" frontend/src` -- expected: `tokens.css` 한 곳만
- 실행 중인 스택의 `docker compose logs`에서 `BOKRILOG_NAVER_CLIENT_ID`·`_SECRET`·세션 ID·`code`·`state` 검색 -- expected: 0건
- 브라우저 개발자 도구에서 진입 화면의 쿠키·저장소·외부 요청 -- expected: 저장소 0건, 외부 origin 요청 0건

## Suggested Review Order

**진입점 — 등록 저장소가 하나에서 맵으로 바뀐 자리**

- 단일 필드가 이름별 맵이 됐다. 이 스토리의 설계 의도가 여기 다 있다
  [`AuthSecurityConfig.java:226`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L226)

- 판정이 제공자마다 따로 접힌다. 한쪽 자격정보 부재가 다른 쪽을 죽이지 않는다
  [`AuthSecurityConfig.java:245`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L245)

- `CommonOAuth2Provider`에 NAVER가 없어 엔드포인트 넷과 `client_secret_post`를 손으로 준다
  [`AuthSecurityConfig.java:90`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L90)

- 두 등록이 같은 issuer면 기동 거부. 구성 오류 하나로 열리던 계정 병합을 닫는다
  [`AuthSecurityConfig.java:250`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L250)

**`provider` 컬럼이 진실을 담게 되는 경로**

- 흐름이 실제 registration id를 알려 준다. 이게 없으면 컬럼이 계속 거짓말한다
  [`AuthFlowHandlers.java:407`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L407)

- `"google"` 리터럴이 사라진 자리. AC-2와 1.10·1.11의 전제다
  [`AuthSessionService.java:112`](../../backend/src/main/java/com/bokrilog/AuthSessionService.java#L112)

- 404·500 판정이 상수 하나에서 등록 저장소 조회로 옮겼다
  [`AuthSessionController.java:146`](../../backend/src/main/java/com/bokrilog/AuthSessionController.java#L146)

**스텁 — 클래스 하나에 인스턴스 둘**

- 제공자별 경로·스텁 빈. `DevOidcStub`은 한 줄도 안 바꿨다
  [`DevOidcStubConfig.java:67`](../../backend/src/main/java/com/bokrilog/DevOidcStubConfig.java#L67)

**검증 스크립트 — 조용히 사라진 leg을 붉게 만드는 두 가드**

- `docker compose exec`가 `-T`로도 stdin을 먹는다. 원인을 여기서 닫았다
  [`verify-same-origin.sh:143`](../../deploy/compose/verify-same-origin.sh#L143)

- 루프 회전 수를 센다. 없는 단정은 붉은 단정이 아니기 때문이다
  [`verify-same-origin.sh:1071`](../../deploy/compose/verify-same-origin.sh#L1071)

- 결과 전역을 함수 진입에서 비운다. leg이 앞 leg의 값을 물려받지 않게
  [`verify-same-origin.sh:857`](../../deploy/compose/verify-same-origin.sh#L857)

**브랜드 — 공식 애셋에서 뽑은 마크와 대비 하한**

- 공식 `.ai`에서 추출한 10점 경로. bbox가 정사각형이라 잉크가 박스를 채운다
  [`EntryScreen.tsx:286`](../../frontend/src/screens/EntryScreen.tsx#L286)

- 제공자 UI가 닫힌 맵이다. 삼항식이면 카카오가 네이버 색으로 렌더된다
  [`EntryScreen.tsx:31`](../../frontend/src/screens/EntryScreen.tsx#L31)

- 레이블 20px/700은 axe 큰 텍스트 문턱을 넘어 3.095:1로 3:1을 만족시킨다
  [`tokens.css:173`](../../frontend/src/styles/tokens.css#L173)

- 공표 색·간격은 가이드가, 크기·모서리는 BokriLog가 소유한다
  [`EntryScreen.module.css:238`](../../frontend/src/screens/EntryScreen.module.css#L238)

- 정본은 frontmatter다. 모서리 리프는 가이드가 침묵해 만들지 않았다
  [`DESIGN.md:157`](../../_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/DESIGN.md#L157)

**프런트엔드 계약**

- 닫힌 제공자 목록. 경로·라벨·복구 안내가 모두 이 집합을 따른다
  [`auth.ts:27`](../../frontend/src/content/auth.ts#L27)

- `next` 생산자는 여전히 하나이고 제공자를 인자로 받는다
  [`auth.ts:100`](../../frontend/src/content/auth.ts#L100)

**시험 — 새로 생긴 축**

- 같은 subject를 두 issuer로. 병합 금지를 구조가 보증하고 시험이 고정한다
  [`AuthProviderIndependenceTest.java:26`](../../backend/src/test/java/com/bokrilog/AuthProviderIndependenceTest.java#L26)

- 네이버 왕복의 정상·취소·오류와 한쪽만 구성된 등록
  [`AuthNaverSignInTest.java:29`](../../backend/src/test/java/com/bokrilog/AuthNaverSignInTest.java#L29)

- 잉크 높이를 요소 박스가 아니라 `getBBox()`로 잰다. 박스를 재면 위반이 통과한다
  [`auth-entry.spec.ts:127`](../../frontend/e2e/auth-entry.spec.ts#L127)
