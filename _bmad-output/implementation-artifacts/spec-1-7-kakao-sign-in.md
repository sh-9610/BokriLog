---
title: 'Story 1.7 — 카카오로 가입·로그인하고 세 가지 로그인 수단 중 선택하기'
type: 'feature'
created: '2026-09-09'
status: 'done'
review_loop_iteration: 0
baseline_commit: '8f2161d65f0790dcee0183a5f3e21480a2aa5acb'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-4-google-sign-in.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-6-naver-sign-in.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 세 제공자 중 카카오만 없다. `SIGN_IN_PROVIDERS`가 둘이고 화면은 "카카오 로그인은 아직 준비 중입니다"를 말하며, `SUPPORTED_REGISTRATION_IDS`도 둘이라 `/api/auth/authorize/kakao`는 404다. Epic 1이 확정한 세 제공자 동등 위계 선택 화면이 아직 성립하지 않고, 1.10·1.11의 연결·해제가 다룰 제공자 집합도 미완이다.

**Approach:** Story 1.6이 단일 제공자 전제를 없애며 복수형으로 넓혀 둔 세 자리 — 등록 맵, 스텁 인스턴스 목록, 화면의 닫힌 제공자 맵 — 에 세 번째 값을 더한다. 새 인증 계약도 새 의존도 만들지 않고, 카카오가 공표한 값만 브랜드 토큰으로 옮긴다.

## Boundaries & Constraints

**Always:**
- 공통 OIDC 불변식을 그대로 쓴다 — Authorization Code + PKCE(S256), 일회성 `state`·`nonce`, JWS·RS256·JWKS·`iss`·`aud`·`exp`·`nonce` 검증은 Spring Security OAuth2 Client가 하고 손으로 만들지 않는다.
- fail-closed는 제공자 단위로 독립이다. 카카오 자격정보가 비면 카카오 인가 시작만 공통 오류 계약으로 닫히고 Google·네이버 로그인은 산다.
- 조회 키는 `(issuer, subject)`이고 세 등록의 issuer는 서로 달라야 한다. 겹치면 기동을 거부한다.
- 브랜드 값은 카카오 공식 가이드가 **공표한 수치만** 토큰에 담는다. 공표하지 않은 축은 BokriLog 토큰이 소유한다(1.6이 네이버 모서리에 적용한 규칙과 같다).
- 세 버튼의 바깥 크기·조작 영역·포커스 표시는 동등하고 고정 순서는 Google → 네이버 → 카카오다. 추천 표지·최근 사용 강조를 만들지 않는다.
- 버튼 레이블과 접근 가능한 이름은 **`카카오로 계속하기`**다(2026-09-09 사람의 결정). 카카오 가이드는 `카카오 로그인`·`로그인`을 권장 문구로 공표하지만 문구 변경을 금지 목록에 넣지 않았고(금지는 심볼의 형태·비율·색, CI 대체, 기능 아이콘, 심볼 제거, 지정 팔레트 밖 색), `epics.md` AC-1과 DESIGN.md의 접근 가능한 이름 규칙이 이 형태를 요구한다. 심볼과 색은 공표값을 그대로 지킨다.
- 제공자 이름 목록이 복제된 모든 층(백엔드 main·시험·compose·검증 스크립트·프런트엔드·e2e)을 함께 고친다. 한 곳이라도 빠지면 그 leg이 조용히 건너뛰어진다.

**Ask First:**
- 카카오 콘솔에서 Client Secret을 켜지 않은 운영 구성을 지원하기 위해 `clientSecret` 필수 게이트를 무르는 것. 무르지 않는 쪽이 기본이다.
- `identity` 공개 API·스키마·마이그레이션 변경.

**Never:**
- 로그인 수단 연결·해제(1.10·1.11), 재인증·action grant(1.8·1.9), 제공자 측 로그아웃.
- 제공자 이름 목록의 계층 간 등가성 검사 도입 — 별도로 배포 가능한 딴 목표다(미해소 항목 33·38이 소유).
- 한 제공자만 구성된 스택을 HTTP·화면 층에서 검증하는 층 신설 — 같은 이유로 별도 목표다(미해소 항목 40).
- `authorizeHttpRequests`에 새 보호 경로 추가. 이 스토리는 사용자 소유 자원을 만들지 않는다.
- 이메일·프로필 동의 요청. 동의 범위는 `openid` 하나다.
- 브라우저 영속 저장소 쓰기, 외부 폰트·CDN·이미지. 로고는 인라인 SVG뿐이다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 카카오 신규 가입 | 미연결 `(kauth.kakao.com, sub)`로 콜백 검증 성공 | 계정·매핑 원자 생성, `provider='kakao'`, 세션 개설·ID 회전, `/onboarding` | N/A |
| 카카오 기존 로그인 | 이미 매핑된 `(issuer, subject)` | 그 `user_id`의 세션만 개설, 계정 생성 없음 | N/A |
| 제공자 간 동일 subject | 같은 `sub` 문자열을 세 issuer로 로그인 | 계정 3건·매핑 3건, `user_id` 서로 다름 | 병합 경로 없음 |
| 카카오 미구성 | `BOKRILOG_KAKAO_CLIENT_ID`·`_SECRET` 중 하나라도 빔 | 카카오 등록 없음, 인가 시작이 공통 오류 계약 | 환경 변수 이름·제공자 이름 미노출 |
| issuer 충돌 | 두 등록이 같은 issuer로 해석됨 | 기동 거부 | 자격정보·`BOKRILOG_` 이름·제공자 이름 미노출 |
| 사용자 취소 | 콜백에 `error=access_denied` | 계정·매핑·세션 무변경, 진입 화면 오류 표지 | 자동 재시도·제공자 전환 없음 |
| 토큰 엔드포인트 장애 | code 교환이 5xx | 세션 미개설, 공통 오류 계약 | 상태 변경 0건 |
| 삭제 진행 계정 | `DELETION_IN_PROGRESS`에서 카카오 재로그인 | 차단, 신규 계정도 만들지 않음 | `AccessDecision.DENIED` 하나 |
| 미지 제공자 | `/api/auth/authorize/<미등록 이름>` | 404 | 본문에 아는 제공자 이름·개수 미노출 |

</frozen-after-approval>

## Code Map

**검증된 외부 사실 (2026-09-09 실측)**

- 카카오 OIDC discovery `https://kauth.kakao.com/.well-known/openid-configuration` → `issuer=https://kauth.kakao.com`, `authorization_endpoint=https://kauth.kakao.com/oauth/authorize`, `token_endpoint=https://kauth.kakao.com/oauth/token`, `jwks_uri=https://kauth.kakao.com/.well-known/jwks.json`(issuer 바로 아래 — 네이버의 `/oauth2/jwks`와 모양이 다르다), `userinfo_endpoint=https://kapi.kakao.com/v1/oidc/userinfo`, `response_types_supported=[code]`, `grant_types_supported=[authorization_code, refresh_token]`, **`token_endpoint_auth_methods_supported=[client_secret_post]`**(유일), `code_challenge_methods_supported=[S256]`, `subject_types_supported=[pairwise]`, `id_token_signing_alg_values_supported=[RS256]`. **`claims_supported`에 `nonce`가 있다** — 네이버가 `nonce`를 한 번도 언급하지 않아 1.6이 실연동 위험으로 남긴 축이 카카오에는 없다. `end_session_endpoint`·`scopes_supported` 없음.
- 공식 버튼 가이드(`developers.kakao.com/docs/ko|en/kakaologin/design-guide`, 국·영문 동일): `Container #FEE500` · `Symbol #000000` · `Label #000000 85% 불투명도` · **`Container border radius 12px`**(네이버와 달리 **공표됨**). 권장 문구는 `카카오 로그인`·`로그인`(영문 `Login with Kakao`·`Login`). 금지는 심볼의 형태·비율·색 변경, CI 대체, 기능 아이콘 사용, 심볼 제거, 지정 팔레트 밖 색. **최소 로고 크기·여백·간격은 공표하지 않는다**(본문 값은 내려받는 애셋에 있고 이 스토리는 열지 않는다).
- 대비 실측: 심볼 `#000000` on `#FEE500` = **16.42:1**, 레이블 `#000000@85%`(합성 `#262200`) on `#FEE500` = **12.52:1**. 둘 다 일반 텍스트 4.5:1을 넘는다 — 네이버의 3.095:1이 강제했던 크기 확대가 카카오에는 필요 없다.
- 카카오 콘솔 게이트: OpenID Connect **활성화**가 있어야 ID Token이 나오고 `scope`에 `openid`가 있어야 한다. Client Secret은 콘솔의 선택 기능이므로 운영자가 켜야 우리 fail-closed 게이트를 통과한다. 실연동은 Epic 1의 **실연동 미검증** 게이트가 소유한다.
- 복구 링크 생존 확인(HTTP 200): `https://accounts.kakao.com/weblogin/find_account`. 기존 두 링크(`accounts.google.com/signin/recovery`, `nid.naver.com/user2/help/idInquiry`)와 같은 종류(계정 찾기)다.

**백엔드 main — 넓힐 자리**

- `AuthSecurityConfig.java:87`·`:90` -- 등록 id 상수 둘. `:90`의 javadoc이 "카카오는 Story 1.7이 더한다"를 이미 적어 두었다. 여기에 `KAKAO_REGISTRATION_ID = "kakao"`.
- `AuthSecurityConfig.java:137-143` -- 네이버 정적 엔드포인트 상수 넷. 카카오도 같은 모양으로 넷.
- `AuthSecurityConfig.java:161`·`:173-178` -- `AuthProperties(publicBaseUrl, google, naver)` 레코드 성분과 compact 생성자의 `null → Provider.empty()` 정규화. 성분을 하나 늘리면 시험의 모든 생성자 호출부가 컴파일 오류로 드러난다(의도된 안전망).
- `AuthSecurityConfig.java:236-237` -- `SUPPORTED_REGISTRATION_IDS`. 이 집합이 404와 500을 가른다.
- `AuthSecurityConfig.java:245-252` -- `from()`. 제공자당 `put(...)` 한 줄씩(`:248`·`:249`) 뒤 `rejectSharedIssuer`(`:250`).
- `AuthSecurityConfig.java:272-286` -- `rejectSharedIssuer`. 메시지 `"두 제공자 등록이 같은 issuer를 가리킨다…"`(`:282-284`)의 "두"가 셋에서 낡는다.
- `AuthSecurityConfig.java:294-343` -- `put()`. 제공자 단위 fail-closed 게이트(`:300-305`: clientId·clientSecret·baseUrl 중 하나라도 빔 → 조용히 return), 공통 적용부(`:307-327`: redirectUri·`scope(OPENID)`·`userInfoUri(null)`·`requireProofKey(true)`), 선택 override(`:330-341`).
- `AuthSecurityConfig.java:361-371` -- `naverBuilder()`. 카카오 빌더가 복제할 형태(`AUTHORIZATION_CODE` + `CLIENT_SECRET_POST` + 엔드포인트 4 + `userNameAttributeName(SUB)`). Google은 `CommonOAuth2Provider` 한 줄(`:345-347`)이고 카카오는 그 enum에 없다.
- `DevOidcStubConfig.java:67-70` -- `PROVIDERS`. `:84-102`의 경로 헬퍼와 `:221`의 라우터 루프는 이미 제공자 파라미터형이라 무변경이다.
- `DevOidcStubConfig.java:112-118` -- 기동 guard(`hasCredentials(google) || hasCredentials(naver)`). `:121`·`:123` 스텁 빈 이름 상수와 `:138-146` `@Bean` 둘, `:165-198` `@Primary` 등록 저장소.
- `application.yml:149-157` -- `bokrilog.auth.{public-base-url,google.*,naver.*}`. 엔드포인트 override 키는 여기 선언하지 않고 코드가 기본값을 소유한다(`:140-148`). 환경 변수 이름 주석은 `:17-27`.
- 무변경: `AuthFlowHandlers.java:423`(`getAuthorizedClientRegistrationId()`), `AuthSessionService.java:73`, `AuthSessionController.java:143-151`(`isSupportedProvider` 조회만 한다), `DevOidcStub.java`(issuer가 생성자 인자, `client_secret_post` 이미 수용), `identity/**`, `db/migration/**`, `deploy/nginx/bokrilog.conf`(인증 location이 접두사 매칭이라 제공자 이름을 보지 않는다).

**백엔드 시험 — 뒤집을 자리**

- `OidcStubConfiguration.java:74-77`·`:157-160` -- 제공자 목록 둘. 더하면 `DynamicPropertyRegistry` 6개 키가 자동 생성되고 루프백 서버는 하나로 남는다.
- `AuthFlowTestBase.java` -- **무변경**. 헬퍼가 이미 `Authorization`이 든 제공자를 쓰고(`:112-117`·`:188`·`:198`) 제공자 인자 오버로드가 있다(`:146`·`:237`·`:362`).
- `DevOidcStubProfileTest.java:44-47` -- `PROVIDERS`. 대부분의 루프가 여기서 파생된다. `:119`(`isSupportedProvider("kakao")` **false**)와 `:239`(라우터에 카카오 jwks 경로 **없음**)는 뒤집을 negative 단정이다. `:443-449` `propertiesFor(provider)`가 `boolean google = …` 이분 분기라 **셋에서 성립하지 않는다** — 맵·switch로 다시 쓴다.
- `StatusApiTest.java:297-301` -- fail-closed 루프의 인라인 배열. `:320-326`은 `authorize/kakao` → 404를 단정하므로 **미지 이름을 다른 것으로 옮기고** 미노출 목록에 카카오를 더한다.
- `AuthNaverSignInTest.java` / `AuthProviderIndependenceTest.java` -- 전자는 제공자별 왕복·구성 축, 후자는 저장소 격리 축(`:77` `containsExactlyInAnyOrder(GOOGLE, NAVER)`, `:141` `containsExactly(GOOGLE)`, `:153` `distinctProviders()`). 카카오는 후자에 **이름만 더하고** 새 축을 만들지 않는다. `AuthTokenValidationTest`의 검증 실패 18축은 제공자 비종속이므로 되풀지 않는다.
- `AuthProperties` 성분 증가로 컴파일이 깨지는 호출부: `AuthNaverSignInTest.java:221,229,264,273`, `DevOidcStubProfileTest.java:73,77,165,360,445`.

**프런트엔드·배포 — 넓힐 자리**

- `content/auth.ts:27` -- `SIGN_IN_PROVIDERS`. `:202-205` 라벨 맵(`as const`라 키 누락이 타입 오류), `:210` `pending`(카카오 준비 중 — 삭제 대상), `:263-274` `recovery.links`, `:296-301` `signInCopyStrings`.
- `EntryScreen.tsx:25-28`·`:31-34` -- 1.6 리뷰가 삼항식에서 바꿔 둔 닫힌 맵 둘. 카카오 누락을 타입 검사가 잡는다. `:158-172` 렌더 루프는 무변경. `KakaoMark`를 `NaverMark`(`:276-291`)와 같은 방식의 인라인 SVG로 더한다.
- `tokens.css:122-124`(공통)·`:132-142`(google 11개, **자체 `radius: 4px` 보유**)·`:168-174`(naver 7개, 모서리 토큰 **없음** — `--radius-md` 10px 사용). 카카오는 공표값만: background `#fee500`, foreground(85% 합성) · symbol `#000000`, `radius 12px`. 레이블 크기 토큰은 **만들지 않는다**(대비가 base type을 허용한다).
- `EntryScreen.module.css:161-165`(`.providerList` gap만 소유)·`:177-194`(`.googleButton`: base `--type-label-*` 사용)·`:238-263`(`.naverButton`: 전용 20px/700). 카카오 블록은 Google 쪽 형태를 따른다.
- 뒤집을 시험 단정: `EntryScreen.test.tsx:136-147`(`toEqual(['google','naver'])`), **`:180-186`(카카오 링크 부재 단정)**, `:330`(`toHaveLength(2)`), `:352-353`(복구 href). `App.test.tsx:112,134-135`. `e2e/auth-entry.spec.ts:522` 루프·`:145-210` 네이버 전용 측정 시험의 형태.
- `compose.yaml:134-137` -- 환경 변수 두 줄 추가(값 없는 `${…:-}` 모양이라 gitleaks에 걸리지 않는다).
- `verify-same-origin.sh` -- `forbidden_fragments:104-107`, **`:768-773` 미등록 제공자 탐침이 지금 `kakao`를 쓴다**(다른 이름으로 옮기고 카카오 미노출 단정을 더한다), `CALLBACK_PROVIDERS:793-796`, `ROUND_TRIP_PROVIDERS:1061-1064`, `case:1044-1060`, 전역 `:1017-1024`, 완주 단정 `:1071`(`'2'`), 행 수 `:1075,:1082,:1103-1104,:1155-1156`, 누출 루프 `:1466-1482,:1505-1521`, `UPSTREAM_ERROR_PROVIDERS:1381-1384`. (`:814`·`:818`의 `'2'`는 세션 테이블 수이므로 무관하다.)
- `DESIGN.md:112-163` -- `social-sign-in` frontmatter. `naver-*` 리프 `:157-163`, 소유권 주석 `:118-145`. 컴포넌트 표 `:233`은 이미 세 버튼을 규정한다.

## Tasks & Acceptance

**Execution:**

- [x] `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java` -- `KAKAO_REGISTRATION_ID`와 정적 엔드포인트 넷을 더하고, `AuthProperties`에 `kakao` 성분과 정규화를 더하고, `SUPPORTED_REGISTRATION_IDS`·`from()`에 카카오를 넣고, `naverBuilder()`와 같은 형태의 `kakaoBuilder()`를 만든다. `rejectSharedIssuer`의 "두 제공자" 문구를 제공자 수에 중립인 표현으로 고친다. -- `CommonOAuth2Provider`에 KAKAO가 없고 카카오가 `client_secret_post` 하나만 광고하므로 정적 조립이 유일한 길이다. 메시지가 "두"에 머물면 셋째 등록이 충돌할 때 거짓말을 한다.
- [x] `backend/src/main/java/com/bokrilog/DevOidcStubConfig.java` -- `PROVIDERS`에 카카오를 더하고, 기동 guard가 카카오 자격정보도 보게 하고, 스텁 빈 이름 상수와 `@Bean` 하나를 더하고, `@Primary` 등록 저장소가 세 등록을 들게 한다. 라우터 빈은 **하나로 유지**한다. -- 경로 헬퍼와 라우터 루프가 이미 제공자 파라미터형이라 이것이 최소 변경이다. 라우터를 쪼개면 `DevOidcStubProfileTest`의 단수 단정이 깨진다.
- [x] `backend/src/main/resources/application.yml` -- `bokrilog.auth.kakao.client-id`·`client-secret` 두 이름과 환경 변수 주석을 더한다. 엔드포인트 override 키는 선언하지 않는다(Google·네이버와 같은 규약). 자격정보 모양 리터럴을 넣지 않는다. -- 이름은 이 파일이 정하고 값은 환경이 준다.
- [x] `backend/src/test/java/com/bokrilog/OidcStubConfiguration.java` -- 제공자 목록 둘(`:74-77`·`:157-160`)에 카카오를 더한다. 루프백 서버는 하나로 유지한다. -- 컨텍스트 캐시를 가르지 않는 것이 CI wall-clock의 전제다.
- [x] `backend/src/test/java/com/bokrilog/DevOidcStubProfileTest.java` -- `PROVIDERS`에 카카오를 더하고, `:119`·`:239`의 "카카오는 아직 없다" 단정을 뒤집고, `propertiesFor(provider)`의 이분 분기를 제공자 맵으로 다시 쓰고, 기동 거부 guard 루프와 등록 내용 단정에 카카오 축(`CLIENT_SECRET_POST`, 정적 엔드포인트 넷, `sub`)을 편입한다. `RouterFunction`·`ProviderClientRegistrations` 단수 단정은 유지한다. -- 이분 분기를 남기면 셋째 제공자의 속성이 조용히 틀린 값으로 주입된다.
- [x] `backend/src/test/java/com/bokrilog/AuthKakaoSignInTest.java` -- 카카오 왕복의 정상·기존 로그인·취소·제공자 오류·토큰 엔드포인트 장애와 "카카오 미구성에서도 나머지 둘이 산다"를 단정한다. `AuthTokenValidationTest`가 덮는 검증 실패 축은 **복제하지 않는다**. -- AC-1·AC-4.
- [x] `backend/src/test/java/com/bokrilog/AuthProviderIndependenceTest.java` -- 기존 축에 카카오를 편입한다(같은 subject 세 issuer → 계정 3·매핑 3·`user_id` 셋 다름, `provider` 컬럼 실제 값, 삭제 진행 중 차단, `erasePayload`가 카카오 매핑·세션도 지움). 새 축을 만들지 않는다. -- AC-2·AC-5. 이 파일이 저장소 격리의 단일 소유자다.
- [x] `backend/src/test/java/com/bokrilog/StatusApiTest.java` -- fail-closed 루프에 카카오를 더하고, 미지 제공자 404 시험의 이름을 등록되지 않은 다른 값으로 옮기고 미노출 목록에 카카오를 더한다. -- 미지 이름이 실제 제공자가 되면 그 시험은 제 뜻을 잃는다.
- [x] `_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/DESIGN.md` -- `social-sign-in` frontmatter에 `kakao-*` 리프를 **공표값만**으로 더하고(`kakao-background: #FEE500`, `kakao-symbol: #000000`, `kakao-label: rgb(0 0 0 / 85%)`, `kakao-radius: 12px`), 소유권 주석에 "카카오는 모서리를 공표했고 레이블 크기는 공표하지 않았다"를 적는다. -- Google이 이미 자체 `radius`를 갖고 네이버는 갖지 않는 것과 같은 규칙이며 새 버전 발행이 아니다.
- [x] `frontend/src/styles/tokens.css` -- `--component-social-sign-in-kakao-*`를 DESIGN.md frontmatter에서 그대로 옮긴다. 레이블 크기·굵기 토큰은 만들지 않고 근거(대비 12.52:1이라 base type으로 충분)를 주석으로 남긴다. -- 없는 토큰을 지어내지 않는다.
- [x] `frontend/src/content/auth.ts` -- `SIGN_IN_PROVIDERS`에 `'kakao'`를 더하고 라벨을 넣고 `pending`을 **삭제**하고 `recovery.links`에 카카오 항목(`https://accounts.kakao.com/weblogin/find_account`, 2026-09-09 HTTP 200 확인)을 `SIGN_IN_PROVIDERS`와 같은 순서로 더한다. `signInCopyStrings`에서 `pending`을 뺀다. -- 세 제공자가 모두 살아 있으면 "준비 중" 문장은 거짓이 된다.
- [x] `frontend/src/screens/EntryScreen.tsx` + `EntryScreen.module.css` -- 닫힌 맵 둘에 카카오를 더하고 `KakaoMark`를 인라인 SVG로 만들고(`aria-hidden`·`focusable="false"`, 접근 가능한 이름은 `<span>` 하나에서만), `.kakaoButton`을 Google 블록과 같은 형태(base `--type-label-*`, 자체 radius 토큰)로 더한다. `pending` 렌더를 지운다. 포커스 규칙을 선언하지 않는다. -- 심볼 형태·비율·색 변경이 가이드의 명시적 금지이므로 로고는 공식 애셋에서 추출한 경로여야 한다.
- [x] `frontend/src/screens/EntryScreen.test.tsx` + `App.test.tsx` -- `:180-186`의 카카오 부재 단정을 존재·href·라벨 단정으로 뒤집고, 순서 단정을 세 값으로, `toHaveLength(2)`를 3으로, 복구 링크 시험을 세 제공자로 넓힌다. `App.test.tsx`의 경로 리터럴에 카카오를 더한다. `role="status"` 개수 1을 유지한다. -- 기존 단정 축을 줄이지 않고 뒤집을 것만 뒤집는다.
- [x] `frontend/e2e/auth-entry.spec.ts` -- 게이트 안쪽 왕복을 카카오로도 돌리고, 세 버튼의 고정 순서·키보드 도달·최소 44×44px·axe WCAG 2.2 AA 0위반을 단정한다. 카카오 버튼의 공표 색 둘과 모서리 12px, **세 버튼의 바깥 크기가 서로 같음**을 이름으로 단정한다. 데이터 요청 pathname 목록을 맞춘다. -- AC-3의 "동등한 위계"를 세 버튼에서 실제로 보는 유일한 층이다.
- [x] `deploy/compose/compose.yaml` -- `BOKRILOG_KAKAO_CLIENT_ID`·`_SECRET` 두 줄을 기존과 같은 `${…:-}` 모양으로 더한다. -- 이름만 적고 값을 비우는 형태라 gitleaks에 걸리지 않는다.
- [x] `deploy/compose/verify-same-origin.sh` -- 세 목록(`ROUND_TRIP_PROVIDERS`·`CALLBACK_PROVIDERS`·`UPSTREAM_ERROR_PROVIDERS`)과 `case` 분기·전역·`forbidden_fragments`에 카카오를 더하고, `:768-773`의 미등록 탐침 이름을 실제로 등록되지 않은 값으로 옮기고, 왕복 완주 기대값과 `identity_social_link` 행 수를 3 기준으로 맞추고, code·state 누출 검사에 세 번째 왕복 값을 더한다. **나머지 두 루프에도 완주 수 가드를 더한다.** `set -o pipefail`에서 `생산자 | grep -q`를 조건에 쓰지 않는다. -- 1.6이 실제로 겪은 결함이 "목록에서 빠진 leg이 붉게 나오지 않고 사라진다"였고, 가드 없는 루프가 아직 둘 남아 있다.
- [x] `_bmad-output/implementation-artifacts/epic-1-context.md` -- `## Goal` 마지막 문장을 1.7 완료 기준으로 맞춘다. `CLAUDE.md`는 건드리지 않는다(머지 뒤 별도 docs PR이 소유한다). -- 같은 사실이 두 곳에서 갈리지 않게 한다.

**Acceptance Criteria:**

- Given 세 제공자의 스텁 왕복을 모두 지난 저장소, when `identity_social_link`를 검사하면, then 행이 3개이고 각 `provider`가 인증에 실제로 쓰인 registration id이며 `user_id`가 서로 다르다.
- Given `develop`의 Story 1.1~1.6 계약, when 이 스토리의 변경을 얹으면, then `GET /api/nope` 404·`POST /api/status` 405·`POST /api/auth/session` 403·반복 로그아웃 403·오류 본문 전문 동등성·보안 헤더 5개·CSP 전문·캐시 헤더 두 갈래·Google·네이버 왕복이 모두 그대로 통과한다.
- Given 카카오만 구성되지 않은 환경, when Google 또는 네이버로 로그인하면, then 성공하고 카카오 인가 시작만 공통 오류 계약으로 fail-closed되며 환경 변수 이름·제공자 이름이 응답·로그에 남지 않는다.
- Given `identity` 모듈, when 이 스토리가 끝나면, then 공개 API·내부 코드·스키마가 바뀌지 않았고 `ModuleBoundaryTest`가 경계와 미노출을 그대로 단정한다.
- Given 진입 화면, when 키보드만으로 조작하면, then 세 버튼이 Google→네이버→카카오 문서 순서로 `Tab` 도달되고 각각 최소 44×44px이며 바깥 크기가 서로 같고 전역 포커스 링이 보이며 axe WCAG 2.2 AA 위반이 0건이다.
- Given 320 CSS px과 400% 확대, when 진입 화면을 렌더하면, then 세 버튼 모두 뷰포트를 넘치거나 잘리지 않는다.
- Given 브라우저 경계, when 진입 화면과 카카오 왕복 전후를 검사하면, then `localStorage`·`sessionStorage`·IndexedDB 항목이 0건이고 문서·번들 외 요청과 외부 origin 요청이 0건이다.
- Given 실연동, when 이 스토리가 끝나면, then 실제 카카오 클라이언트 등록·OpenID Connect 활성화·Client Secret 설정은 통과하지 않은 상태이며 에픽은 **실연동 미검증**을 유지한다.

## Spec Change Log

- 2026-09-09 첫 구현. frozen 블록은 바꾸지 않았다. 아래는 구현 중에 확정한 결정과, Code Map이
  지목하지 않았지만 함께 고쳐야 했던 자리들이다.

  - **카카오는 로그인 버튼 애셋을 벡터로 배포하지 않는다. 그래서 심볼을 공식 래스터에서 추적해
    기하로 복원했다.** 네이버는 `.ai`(PDF) 버튼 애셋을 공개하므로 Story 1.6이 그 안의 경로를 그대로
    옮길 수 있었지만, 카카오가 내보내는 것은 둘 다 래스터다 — 디자인 가이드가 싣는 이미지도,
    리소스 도구(`developers.kakao.com/tool/resource/login`)가 주는 버튼·심볼 묶음도 PNG이며 그 밖에는
    PSD뿐이다(2026-09-09 실측). 그래서 가이드가 싣는 원본
    `developers.kakao.com/docs/img/login_button_component_ko.png`(2040px 폭, 그 안의 심볼이 56×53px로
    가장 크다)의 심볼 영역을 부화소 정밀도로 추적했다.

    복원 결과는 기하 셋이다 — 몸통은 축 정렬 타원, 꼬리는 그 타원에서 좌하로 내려가는 직선 둘,
    꼬리 끝은 작은 둥근 모서리. 타원과 두 직선의 최소제곱 적합에서 잔차는 각각 0.04·0.07·0.17px
    이었다(즉 몸통은 실제로 타원이고 꼬리 변은 실제로 직선이다). 완성된 기하에 대한 추적 윤곽점
    222개의 편차는 **RMS 0.11px · 최대 0.42px**이며, 원본 심볼 높이 52.9px 기준 0.2%·0.8%이고 렌더
    크기 16px에서는 최대 0.13px에 해당한다. 픽셀 커버리지를 4×4 초과표본으로 비교한 L1 차이는 잉크
    면적의 4.4%이고 그 대부분이 안티에일리어싱 띠다.

    `viewBox`는 `0 0 25.39 24`다. 복원한 잉크의 가로세로비(1.0579)를 그대로 옮긴 값이며 공식 버튼
    PNG(180×90)에서 잰 심볼 36×34(1.0588)와도 일치한다. CSS는 높이만 정하고 폭을 `auto`로 두어 그
    비율이 렌더에서 유지된다 — 두 축을 같은 값으로 묶으면 `preserveAspectRatio`가 여백을 만들어
    잉크가 선언한 크기보다 작아진다. 심볼의 형태·비율·색 변경이 가이드가 금지하는 첫 항목들이므로
    셋 모두를 이름으로 단정한다(`e2e/auth-entry.spec.ts`).

  - **심볼 크기·간격·내부 여백에 카카오 토큰을 만들지 않았다.** 가이드가 공표하는 수치는 색 셋과
    모서리 반경 하나뿐이고 SPEC의 DESIGN.md 작업도 그 넷만 리프로 열거한다. 그래서 심볼 크기는
    BokriLog의 `--space-4`(16px)가 소유한다. 그 값의 근거는 공식 버튼 애셋의 비율이다 — 180×90
    버튼의 심볼이 36×34이므로 심볼 높이가 버튼 높이의 0.378배이고, 최소 목표 크기 44px에 그 비율을
    적용하면 16.6px이며 그것에 가장 가까운 기존 토큰이 16px이다. 간격은 `--space-2`, 내부 여백은
    `--space-3`으로 네이버 버튼과 같은 자리를 쓴다.

  - **미등록 제공자 탐침의 이름을 브랜드가 아닌 `unregistered-provider`로 옮겼다.** SPEC이 지목한
    두 곳(`StatusApiTest`·`verify-same-origin.sh`)과 함께 `DevOidcStubProfileTest`의 두 단정
    (`isSupportedProvider("kakao")`가 false, 라우터에 카카오 jwks 경로 없음)도 같은 이유로 옮겼다.
    Code Map은 뒤의 것을 "뒤집을 negative 단정"으로만 지목했는데, 그 자리를 실제 제공자 이름으로
    되돌려 두면 다음 제공자가 들어오는 날 같은 일이 되풀이된다. 등록될 수 없는 이름이면 그 반복이
    끝난다.

  - **`AuthProperties` 성분 증가로 시험 호출부가 컴파일 오류로 드러났다.** Code Map이 예고한
    그대로다. 그중 `DevOidcStubProfileTest.propertiesFor`의 이분 분기는 제공자마다 성분을 골라 넣는
    형태로 다시 썼고, `credentials()`로 두 자리를 채우던 시험은 세 자리를 채우도록 넓혔다.

  - **`AuthProviderIndependenceTest`의 축을 제공자 목록 루프로 바꿨다.** SPEC이 "새 축을 만들지
    않는다"를 요구하므로 카카오 전용 시험을 더하는 대신 기존 다섯 축이 `PROVIDERS`를 돈다. 삭제
    관련 두 축은 행 수를 세므로 iteration마다 `resetStore()`로 저장소를 비운다 — 비우지 않으면 그
    단정이 무엇을 세는지 알 수 없게 된다. `erasePayload` 축은 대상 제공자마다 다른 제공자를 방관자로
    세워 "그 계정만 지워진다"를 세 조합에서 본다.

  - **`verify-same-origin.sh`의 로그아웃 절이 세 세션을 다룬다.** 왕복이 셋이 되어 세션도 셋이므로
    행 수 기대값을 옮기고 세 번째 세션의 보존 단정과 마지막 로그아웃을 더했다. "세 사용자 모두
    나갈 수 있어야 한다"가 그 절의 계약이며, 마지막 로그아웃이 없으면 `세 로그아웃 뒤 세션 행이
    0개다`가 성립하지 않는다.

  - **가드 없던 루프 둘에 완주 수 단정을 더했다**(SPEC의 지시). 흐름 없는 콜백 탐침과 업스트림 오류
    탐침이며 둘 다 이제 "제공자 수만큼 돌았다"를 단정한다. 왕복 루프의 같은 가드가 Story 1.6에서
    실제 사고를 잡은 형태다.

  - **카카오 브랜드 토큰의 리프 이름을 다른 제공자와 같은 모양으로 맞췄다(리뷰 반영).** 처음
    구현은 카카오 가이드의 용어를 그대로 옮겨 `-symbol`(마크 색)·`-label`(레이블 색)을 썼는데,
    Google·네이버는 같은 축을 `-logo`·`-foreground`로 부른다. 이름이 제공자마다 갈리면 "제공자별
    레이블 색·마크 크기를 비교한다"가 한 번의 조회로 끝나지 않는다. 그래서 `kakao-logo`·
    `kakao-foreground`로 옮기고, CSS가 `--space-*`를 직접 집던 두 축에 `kakao-logo-size`·
    `kakao-logo-gap`을 두었다 — 값은 여전히 `{spacing.4}`·`{spacing.2}` 참조이므로 공표되지 않은
    브랜드 값을 지어내지 않는다(소유자가 BokriLog라는 사실을 참조가 그대로 말한다). 내부 여백은
    리프 없이 네이버와 같은 자리를 쓴다. 위 Tasks의 `kakao-symbol`·`kakao-label`은 그 시점의
    이름이며 지금 정본은 DESIGN.md frontmatter와 `tokens.css`다. 계층 간 등가성 검사를 만들지
    않는다는 Never는 그대로다 — 여기서 닫은 것은 한 층 안의 이름 규칙뿐이다.

  - **실패할 수 없던 단정 셋을 실패할 수 있는 모양으로 바꿨다(리뷰 반영).** 추천 표지 부재는
    이 화면에 없는 속성(`data-recommended`)을 찾고 있어 언제나 통과했고 실제 배지·강조 자식은
    지나갔다 — 목록의 자식 수와 버튼 안쪽의 자식 구성(`[svg, span]`)으로 옮겼다. 카카오 심볼의
    `fill`은 검정이 SVG 기본값이라 토큰이 사라져도 통과했으므로 `var()` 참조·토큰 값·계산값 셋을
    함께 본다. 레이블 14px·600은 카카오 공표값이 아니라 BokriLog `--type-label-*`이므로 리터럴
    대신 그 토큰과 비교한다(전역 타이포그래피 변경이 브랜드 시험을 엉뚱한 이유로 깨지 않는다).

  - **`application.yml`을 실제로 읽는 시험을 하나 더했다(`AuthPropertyBindingTest`, 리뷰 반영).**
    기존의 green 경로는 전부 `bokrilog.auth.<제공자>.*`를 직접 주입하거나 등록 저장소를 갈아
    끼워서, 그 파일의 placeholder 이름에 오타가 나도 — `kakao:` 블록이 통째로 없어져도 — 묶음
    전체가 통과했다. 그때 깨지는 것은 문서가 약속한 환경 변수를 정확히 준 실제 배포이고, 결과가
    fail-closed 500이라 "운영자가 구성하지 않았다"와 응답으로 구분되지 않는다.

  - **`verify-same-origin.sh`의 제공자 목록을 하나로 모았다(리뷰 반영).** 세 heredoc과 세 완주
    가드가 각자 `'3'`을 들고 있었다 — 가드가 자기가 지키는 목록과 어긋날 수 있는 구조이며 그것이
    이 스토리가 막으려던 결함과 같은 모양이다. 목록 하나에서 개수를 세어 셋에 먹인다. 누출 검사
    `case`의 마지막 `*)`도 같은 종류였다(모르는 이름이 마지막 값을 다른 이름으로 다시 검사하며
    통과했다) — 마지막 이름에 자기 분기를 주고 `*)`는 `record_fail`로 바꿨다.

  - **`DEVELOPMENT.md`를 함께 고쳤다(task 목록에 없던 변경).** 그 파일의 다섯 문장이 이 스토리로
    거짓이 된다 — 스텁 guard가 보는 제공자 목록, 정적 엔드포인트를 손으로 드는 제공자, compose가
    넘기는 환경 변수 이름, `AuthSecurityConfig`의 역할 요약, 왕복이 "둘"이라는 서술이다.
    `CLAUDE.md`는 건드리지 않았다 — 그 파일의 진행 지점은 머지 뒤 별도 docs PR이 소유한다.

## Design Notes

**카카오는 네이버가 남긴 위험을 되풀지 않는다.** 1.6의 미해결 외부 사실은 "네이버가 `nonce`를 되돌려주는지 확인되지 않았다"였고, 그 불확실성이 실연동에서 검증 실패로 나타날 수 있다는 경고를 남겼다. 카카오 discovery는 `claims_supported`에 `nonce`를 명시하므로 같은 위험이 없다. 그래서 이 스토리는 계약을 그대로 두는 데 아무 대가도 치르지 않는다 — 완화할 것이 없다.

**모서리 12px은 규칙의 결과이지 예외가 아니다.** 1.6이 네이버 모서리를 BokriLog `--radius-md`에 둔 근거는 "네이버 가이드 본문이 반경을 공표하지 않았다"였지 "모든 제공자가 같은 반경을 쓴다"가 아니었다. Google은 이미 자체 `--component-social-sign-in-google-radius: 4px`를 갖는다. 카카오가 12px을 공표했으므로 같은 규칙이 카카오에 자체 토큰을 준다. 세 버튼의 반경이 4·10·12px로 갈리는 것은 "각 제공자의 공식 가이드가 BokriLog 토큰보다 우선한다"는 결정이 실제로 작동한 모습이다.

**세 버튼의 "동등한 위계"는 바깥 크기로 성립하고 타이포그래피로는 성립하지 않는다.** 네이버 레이블이 20px/700인 이유는 브랜드 취향이 아니라 공표 색 조합의 대비가 3.095:1이어서 일반 텍스트 4.5:1을 넘지 못하고 큰 텍스트 3:1로만 닫히기 때문이다. 카카오는 12.52:1이라 그 확대가 필요 없고, 필요 없는 확대를 하면 공표되지 않은 값을 지어내는 것이 된다. 결과적으로 셋 중 네이버만 레이블이 크다. 이것이 AC-3 위반이 아닌 이유는 AC-3이 요구하는 것이 **시각 면적과 위계**이지 글자 크기의 동일성이 아니기 때문이다 — 세 버튼은 전체 폭·같은 최소 조작 영역·같은 간격·같은 순서를 갖고 어느 것에도 추천 표지가 없다. 다만 1.6이 이 축을 "판단하는 층이 없다"로 미뤄 두었으므로(미해소 항목 41), 세 번째 버튼이 들어오는 지금 그 판단을 e2e 단정 하나로 고정한다: 바깥 크기 동일 + 공표 색·반경 + 추천 표지 부재. 이 단정이 있어야 다음 사람이 "네이버만 커 보인다"를 회귀로 착각하지 않는다.

**Client Secret 게이트는 무르지 않는다.** 카카오 콘솔에서 Client Secret은 선택 기능이고 discovery는 `client_secret_post` 하나만 광고한다. `put()`의 게이트가 secret을 필수로 요구하므로 운영자가 콘솔에서 그것을 켜야 한다. 게이트를 무르면 "자격정보 셋이 다 있어야 등록이 생긴다"는 fail-closed의 모양이 제공자마다 달라지고, 그 차이는 구성 실수를 조용한 성공으로 바꾼다. 콘솔 설정 요구는 실연동 게이트가 이미 소유한 종류의 비용이다.

**미등록 제공자 탐침의 이름이 실제 제공자가 된다.** `verify-same-origin.sh:768-773`과 `StatusApiTest.java:320-326`이 지금 `kakao`를 "등록되지 않은 이름"으로 쓴다. 이 스토리가 카카오를 등록하는 순간 두 시험은 여전히 통과하지만 **뜻을 잃는다** — 404를 단정하던 자리가 실제 등록을 만나면 판정이 뒤집히거나(스크립트) 다른 이유로 통과한다. 이름을 옮기는 것은 기계적 개명이 아니라 그 시험이 지키던 계약을 되살리는 일이다.

**미룬 두 항목은 이번에도 미룬다.** 제공자 이름 목록의 계층 간 등가성 검사(항목 33·38)와 부분 구성 스택의 HTTP·화면 층 검증(항목 40)은 둘 다 독립적으로 배포 가능한 별도 목표이고, 이 스토리에 넣으면 단일 목표가 깨진다. 이 스토리는 그 대가를 실제로 지불한다 — 목록 열 곳 남짓을 손으로 고친다 — 므로 그 사실 자체가 다음 판단의 재료다. 대신 값이 크고 폭발 반경이 이 스토리 안에 있는 것 하나는 지금 한다: `verify-same-origin.sh`의 가드 없는 루프 둘에 완주 수 단정을 더하는 것. 1.6이 겪은 결함이 정확히 그 형태였고 제공자가 셋이 되면 빠질 자리가 하나 늘어난다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: 카카오 왕복·제공자 독립·스텁 프로필 시험 통과, 경고 0건(`-Werror`), jar 생성. Docker 데몬 필요(Testcontainers)
- `npm --prefix frontend ci && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test` -- expected: 전부 통과, 경고 0건. Node 24(`frontend/.nvmrc`)
- `npm --prefix frontend run test:e2e` -- expected: Story 1.1·1.4·1.5·1.6 회귀와 세 버튼 시험 통과
- `deploy/compose/verify-same-origin.sh` -- expected: 세 제공자 왕복 포함 모든 단정 통과, exit 0
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 전부 통과. `ci.yml`을 바꾸지 않았으므로 무변경 통과여야 한다
- `git status --short` -- expected: 빌드·시험 산출물이 미추적 파일로 남지 않음

**Manual checks:**
- `grep -rn "kakao" backend/src/main/java/com/bokrilog/AuthSessionService.java backend/src/main/java/com/bokrilog/AuthSessionController.java backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` -- expected: 제공자 리터럴 0건
- `grep -rn "준비 중\|pending" frontend/src/content/auth.ts` -- expected: 카카오 준비 중 문구 0건
- `grep -rn "#fee500\|#FEE500" frontend/src` -- expected: `tokens.css` 한 곳만
- `git diff --stat develop -- backend/src/main/java/com/bokrilog/identity/ backend/src/main/resources/db/migration/ deploy/nginx/` -- expected: 변경 0건
- 실행 중인 스택의 `docker compose logs`에서 `BOKRILOG_KAKAO_CLIENT_ID`·`_SECRET`·세션 ID·`code`·`state` 검색 -- expected: 0건
- 브라우저 개발자 도구에서 진입 화면의 쿠키·저장소·외부 요청 -- expected: 저장소 0건, 외부 origin 요청 0건

## Suggested Review Order

**제공자 등록 — 세 번째 값이 들어가는 자리**

- 카카오가 기존 맵에 한 줄로 들어간다. 이 줄이 이 스토리의 설계 의도 전부다.
  [`AuthSecurityConfig.java:278`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L278)

- 제공자 고유는 여기뿐이다. `CommonOAuth2Provider`에 KAKAO가 없어 넷을 손으로 준다.
  [`AuthSecurityConfig.java:430`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L430)

- 실측 discovery 값. JWKS가 issuer 바로 아래라 네이버와 모양이 다르다.
  [`AuthSecurityConfig.java:161`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L161)

- record 성분이 늘어 시험 호출부가 컴파일로 드러난다. 정규화는 여전히 한 곳이다.
  [`AuthSecurityConfig.java:186`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L186)

**fail-closed와 issuer 경계 — 계정 병합이 열리지 않는 이유**

- 404와 500을 가르는 닫힌 집합. 구성 여부와 무관하게 이름만 본다.
  [`AuthSecurityConfig.java:264`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L264)

- 조회 키가 `(issuer, subject)`라 등록 둘이 같은 issuer면 기동을 거부한다.
  [`AuthSecurityConfig.java:301`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L301)

- 세 쌍 모두를 충돌시킨다. "첫 등록하고만 비교"하는 구현을 잡는다.
  [`AuthNaverSignInTest.java:228`](../../backend/src/test/java/com/bokrilog/AuthNaverSignInTest.java#L228)

- 문서대로 환경 변수를 채운 배포가 등록을 얻는지 본다. 이전엔 어느 층도 보지 않았다.
  [`AuthPropertyBindingTest.java:41`](../../backend/src/test/java/com/bokrilog/AuthPropertyBindingTest.java#L41)

**개발·CI 스텁 — 인스턴스는 셋, 라우터는 하나**

- 목록 한 줄이 스텁·라우터·등록을 함께 넓힌다.
  [`DevOidcStubConfig.java:67`](../../backend/src/main/java/com/bokrilog/DevOidcStubConfig.java#L67)

- 스텁이 실제 제공자와 함께 뜨는 것을 막는 guard. 카카오도 본다.
  [`DevOidcStubConfig.java:116`](../../backend/src/main/java/com/bokrilog/DevOidcStubConfig.java#L116)

**화면과 브랜드 토큰 — 공표값만 옮긴다**

- 배열 한 줄이 버튼·순서·복구 안내·시험 루프를 함께 넓힌다.
  [`auth.ts:27`](../../frontend/src/content/auth.ts#L27)

- 닫힌 맵 둘. 제공자가 늘면 누락을 타입 검사가 잡는다.
  [`EntryScreen.tsx:25`](../../frontend/src/screens/EntryScreen.tsx#L25)

- 공식 벡터가 없어 래스터에서 추출한 심볼. 근거는 Spec Change Log가 소유한다.
  [`EntryScreen.tsx:322`](../../frontend/src/screens/EntryScreen.tsx#L322)

- 가이드가 공표한 값만. 모서리 12px은 카카오가 공표해서 자체 토큰이다.
  [`tokens.css:206`](../../frontend/src/styles/tokens.css#L206)

- 레이블은 base 타이포다. 대비 12.52:1이라 네이버 같은 확대가 필요 없다.
  [`EntryScreen.module.css:291`](../../frontend/src/screens/EntryScreen.module.css#L291)

**실패할 수 있게 만든 자리 — 리뷰가 뒤집은 단정들**

- 속성 이름 대신 마크업 모양을 잰다. 배지·강조 자식·구분선이 모두 걸린다.
  [`auth-entry.spec.ts:145`](../../frontend/e2e/auth-entry.spec.ts#L145)

- AC-3의 동등 위계는 바깥 크기로 성립한다. 네이버만 레이블이 큰 이유가 여기 적혀 있다.
  [`auth-entry.spec.ts:113`](../../frontend/e2e/auth-entry.spec.ts#L113)

- 목록 하나에서 센 개수가 세 루프의 완주 가드를 먹인다.
  [`verify-same-origin.sh:58`](../../deploy/compose/verify-same-origin.sh#L58)

- 빠진 leg은 통과가 아니라 실패다.
  [`verify-same-origin.sh:1113`](../../deploy/compose/verify-same-origin.sh#L1113)

**주변부**

- 이름은 이 파일이 정하고 값은 환경이 준다.
  [`application.yml:165`](../../backend/src/main/resources/application.yml#L165)

- 이름만 넘기고 값을 비운다. gitleaks에 걸리지 않는 모양이다.
  [`compose.yaml:138`](../../deploy/compose/compose.yaml#L138)

- 운영자가 콘솔에서 켜야 하는 넷과 사전 등록할 Redirect URI.
  [`DEVELOPMENT.md:247`](../../DEVELOPMENT.md#L247)

- 카카오만 갖는 pairwise 제약. 키 교체가 계정 마이그레이션을 동반한다.
  [`DEVELOPMENT.md:248`](../../DEVELOPMENT.md#L248)

- 공표값을 담은 frontmatter. `tokens.css`가 이것의 사본이다.
  [`DESIGN.md:190`](../../_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/DESIGN.md#L190)
