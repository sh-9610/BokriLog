---
title: 'Story 1.4 — Google로 가입·로그인하기'
type: 'feature'
created: '2026-09-07'
status: 'done'
review_loop_iteration: 0
baseline_commit: '9fbb04516d210b3a8435b653bc553e66bd3947b1'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-3-identity-ownership.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Story 1.3이 계정 소유권·삭제 상태 계약을 세웠지만 그 계정을 만들거나 고를 방법이 없다. `identity`에는 `(issuer, subject)`로 기존 계정을 찾는 연산조차 없고, 서버 세션·인증 표면·로그인 화면이 전부 부재해 사용자가 자기 데이터 경계 안으로 들어갈 수 없다. 이후 Epic 1 스토리(로그아웃·네이버·카카오·재인증)와 Epic 2~5의 모든 사용자 소유 기능이 이 진입점을 기다린다.

**Approach:** Google OIDC Authorization Code + PKCE 흐름을 Spring Security OAuth2 Client로 올리고 세션을 Spring Session JDBC로 PostgreSQL에 둔다. 검증된 콜백에서만 `identity` 공개 API로 계정을 찾거나 만들고 세션을 개설한다. `identity`는 HTTP를 갖지 않으므로 인증 표면과 세션 정책은 루트 패키지가 소유하고, `identity`는 `(issuer, subject)` 조회 하나만 공개 API에 더한다. 프런트엔드는 라우터 의존 없이 경로별 화면 선택만 넣고, Nginx는 와일드카드 SPA 폴백 대신 알려진 내부 경로만 허용 목록으로 연다.

## Boundaries & Constraints

**Always:**
- 검증(서명·허용 알고리즘·`iss`·`aud`·복수 `aud`의 `azp`·`exp`·`nonce`)을 통과한 콜백 뒤에만 계정·매핑·세션을 만든다. `state`는 서버 발급 일회성이며 세션·제공자·흐름·정확한 redirect URI에 귀속한다. 콜백 쿼리 자체를 신뢰하지 않는다.
- 인증 세션이 유일한 내부 `user_id`를 결정한다. 클라이언트가 보낸 소유자 ID는 권위가 아니다(AD-20).
- `(issuer, subject)`는 전역 유일하고 이메일·표시명은 식별키가 아니다. 이메일 일치로 계정을 만들거나 병합·전환하지 않는다. 이메일·표시명을 스키마에 두지 않는다.
- 미연결 식별자는 신규 가입으로만 처리한다. 이미 인증된 세션에서 로그인 시작을 거절한다.
- 계정 소유권·삭제 상태 판정은 `identity` 공개 API가 유일한 권위다. 새 판정을 만들지 않고 `decideSessionCreation`을 세션 개설 전에 통과해야 한다. `identity.internal`과 소유 테이블에 직접 닿지 않는다.
- 브라우저에는 `HttpOnly`·`Secure`·`SameSite=Lax` 불투명 세션 쿠키만 둔다. 로그인 성공 시 세션 ID를 회전한다. 상태 변경 요청은 세션 귀속 CSRF 토큰과 동일 출처 `Origin` 검증을 통과한다.
- 기본 동의 범위는 `openid`뿐이다. 제공자 액세스·리프레시 토큰을 저장하지 않고 ID Token·code·PKCE verifier는 검증 또는 흐름 종료 즉시 폐기한다.
- 제공자 토큰·code·PKCE verifier·`subject`·세션 비밀·`user_id`를 화면·URL·브라우저 저장소·로그·오류 문맥·지표 label·HTTP 응답 어디에도 남기지 않는다(NFR-8, AD-14).
- 복귀 목적지는 서버가 가진 닫힌 허용 목록의 동일 출처 내부 경로만이다. 외부 주소·알 수 없는 경로는 버리고 기본 목적지로 보낸다. 신규 사용자는 온보딩, 기존 사용자는 유효한 원래 목적지 또는 기본 대시보드다.
- 실패(취소·동의 거부·제공자 오류·콜백 검증 실패)는 어떤 상태도 바꾸지 않는다. 자동 재시도·다른 제공자 자동 선택을 하지 않고 계정 존재 여부를 노출하지 않는다.
- 오류 응답은 `{"code","message","correlationId"}` 하나이며 `ApiErrorHandler`가 유일한 정의다. 브라우저 왕복 실패는 인증 화면으로 수렴하고 원인 표지는 계정 존재를 드러내지 않는 닫힌 값 집합이다.
- 보안 응답 헤더와 CSP는 `deploy/nginx/bokrilog.conf`가 유일한 정의다. 진입 화면(`/`)은 문서·번들 외 요청이 0건이고 브라우저 영속 저장소 쓰기가 0건이다.
- 디자인 토큰이 정본이다. `social-sign-in`의 순서·동등한 시각 면적·최소 44×44px·바깥 간격·포커스 링은 BokriLog가, 로고·명칭·채움색·글자색·테두리·모서리·내부 여백은 Google 공식 버튼 가이드가 소유한다. 화면 카피는 `frontend/src/content/`가 소유한다.
- 스키마 변경은 expand 우선 별도 migration 역할이 실행하고 API 기동은 `Flyway::validate`만 한다(AD-12). 적용된 마이그레이션 파일을 고치지 않는다.
- 자격정보 모양 리터럴을 `application.yml`·compose·시험 fixture에 넣지 않는다. 개발·CI는 자격정보 없이 빌드·시험·동일 출처 검증이 통과한다.
- Story 1.1~1.3의 시험은 본문 수정 없이 계속 통과한다. `/api/status` 응답은 `{"status":"UP"}` 그대로다.

**Ask First:**
- Spring Security·Spring Session JDBC의 Boot 4.1.1 BOM 관리 버전이 해결되지 않을 때. 조용히 낮추거나 독립 핀을 박지 말고 보고한다.
- `-Werror`가 Security·OAuth2 API 경로에서 경고를 낼 때. 억제로 통과시키지 않는다.
- Nginx CSP를 넓혀야 하는 설계로 몰릴 때(외부 스크립트·iframe·form POST가 필요해질 때). `bokrilog.conf`와 `verify-same-origin.sh`의 CSP 상수를 함께 바꾸기 전에 보고한다.
- `verify-same-origin.sh`의 PROBES 목록이나 `GET /nope` 404 단정을 완화해야 할 때. 단정을 지우는 대신 설계를 바꾼다.
- `.github/scripts/test-ci-scripts.sh`가 고정한 job 수·action SHA 집합·artifact 개수를 바꿔야 할 때.
- 프런트엔드 런타임 의존(라우터·인증 SDK)을 추가해야 한다고 판단될 때.
- `frontend/src/test/setup.ts`의 전역 네트워크·저장소 거부 훅을 완화해야 할 때. 범위를 좁힌 탈출구인지 전면 완화인지 밝히고 보고한다.

**Never:** 네이버·카카오 어댑터. 로그아웃·세션 만료·의심 세션 차단(1.5). 로그인 수단 연결·해제(1.10·1.11), 민감 작업 재인증과 action grant(1.8·1.9). Bybit 연결·계획·계산·대시보드 지표. 공개 계정 삭제 API·화면과 다른 모듈 데이터 삭제 조율(Epic 5). 제공자 액세스·리프레시 토큰 저장. 이메일·표시명 컬럼. 브라우저 영속 저장소의 인증 토큰·금융 원본. `identity`의 HTTP 표면. `com.bokrilog` 8개 모듈 목록 밖의 새 최상위 모듈. 와일드카드 SPA 폴백. 외부 폰트·CDN·Google JS SDK·분석. JPA·Hibernate·Redis 세션. production OAuth client 등록·secret·image digest 고정(3.8). 실제 Google 왕복을 CI 판정으로 삼는 것.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 신규 가입 | 미연결 `(issuer, subject)`, 검증 통과 | 계정·매핑을 한 트랜잭션에서 생성, 세션 개설·ID 회전, 온보딩으로 이동 | N/A |
| 기존 로그인 | 연결된 `(issuer, subject)`, 계정 `ACTIVE` | 같은 `user_id`의 세션 개설·ID 회전, 유효한 원래 목적지 또는 기본 대시보드 | N/A |
| 동시 첫 로그인 | 같은 `(issuer, subject)`로 두 콜백 동시 도착 | 계정 1개·매핑 1행. 두 요청이 모두 같은 `user_id`의 세션을 얻는다 | 진 쪽은 아무 상태도 남기지 않고 롤백한 뒤 재조회한다. 경합에 졌다는 이유로 정상 로그인을 거절하지 않는다 |
| 이메일만 같은 다른 식별자 | 기존 계정과 이메일 동일, `subject` 다름 | 신규 가입으로 처리(별개 계정) | 병합·전환하지 않음 |
| 삭제 진행·완료 계정 | `decideSessionCreation`이 `DENIED` | 세션을 만들지 않고 인증 화면으로 수렴 | 삭제 여부·계정 존재를 드러내지 않음 |
| `state` 불일치·재사용·만료·흐름 바꿔치기 | 세션 귀속 `state`와 콜백 값이 다름 | 계정·세션 생성 없음, 인증 화면으로 수렴 | code 교환을 시작하지 않음 |
| ID Token 검증 실패 | 서명·알고리즘·`iss`·`aud`·`azp`·`exp`·`nonce` 중 하나 불일치 | 계정·세션 생성 없음, 인증 화면으로 수렴 | 실패 원인을 응답·로그에 남기지 않음 |
| 사용자 취소·동의 거부 | 콜백에 `error=access_denied` | 상태 변경 0건, 인증 화면에 재시도 경로 표시 | 자동 재시도·다른 제공자 자동 선택 없음 |
| 제공자 장애·응답 유실 | 토큰 엔드포인트 5xx·타임아웃 | 상태 변경 0건, 인증 화면으로 수렴 | 부분 상태를 남기지 않음 |
| 이미 인증된 세션에서 로그인 시작 | 유효 세션 보유 | 시작 거절, 목적지로 이동 | 신규 가입을 열지 않음 |
| 외부·알 수 없는 복귀 목적지 | `https://evil.example/x` 또는 `/unknown` | 목적지 폐기, 기본 목적지로 이동 | 리다이렉트하지 않음 |
| 미인증 상태로 로그인 상태 경로 접근 | 세션 없음, `/dashboard` 직접 요청 | 문서는 200이나 화면이 인증 화면으로 되돌린다. 사용자 데이터 0건 | 세션 확인 없이 로그인 상태를 주장하지 않음 |
| 자격정보 없는 개발·CI 스택 | Google 등록이 구성되지 않음 | 인증 시작이 단일 오류 계약으로 fail-closed. 계정 0건 | 성공·빈 데이터로 위장하지 않음 |
| 인증 데이터 삭제 | `erasePayload` 실행 | 새로 추가된 매핑·세션이 함께 사라지고 guard 행은 남는다 | 부분 실패를 완료로 보고하지 않음 |

</frozen-after-approval>

## Code Map

**백엔드 — 재사용 지점**

- `backend/build.gradle.kts:9-13` -- "Spring Security·Spring Session JDBC는 아직 없으며 SPEC이 승인할 때 들어온다"는 위임 주석. 이 SPEC이 그 승인이다. 문단을 갱신한다.
- `backend/build.gradle.kts:47-48` -- BOM 인용 관용(`platform(SpringBootPlugin.BOM_COORDINATES)`). Security·Session은 좌표에 버전을 적지 않는다.
- `backend/build.gradle.kts:103` -- `-Xlint:all -Werror`. Security API의 deprecation 경고 하나가 빌드를 깬다. 실질 위험 지점.
- `backend/src/main/resources/application.yml:15` -- "Spring Session JDBC는 여기 없다. 세션은 Story 1.4·1.5의 범위다." 이 주석을 실제 설정으로 대체한다.
- `backend/src/main/resources/application.yml:53-55` -- `${BOKRILOG_DB_URL:}` 환경변수 관용. 새 인증 설정도 같은 모양이며 기본값·리터럴을 두지 않는다.
- `backend/src/main/java/com/bokrilog/SchemaValidationConfig.java:28-43` -- **배치 선례.** 기동·전역 정책은 모듈이 아니라 루트 패키지가 소유하고 프로필별 bean 선택은 `ApplicationContextRunner`가 단정한다. 보안·세션 구성이 따를 모양이다.
- `backend/src/main/java/com/bokrilog/ApiErrorHandler.java:47-62` -- code·message 상수 블록. `:121` 주석이 "새 code는 그것을 낼 엔드포인트가 생길 때 SPEC이 함께 추가한다"고 이 지점을 지정한다.
- `backend/src/main/java/com/bokrilog/ApiErrorHandler.java:123-134` -- `errorFor` 상태→code 매핑. 현재 401·403이 `BAD_REQUEST`로 뭉쳐진다. 인증 표면이 생기면 여기서 갈라야 한다.
- `backend/src/main/java/com/bokrilog/ApiErrorHandler.java:144-153` -- `correlationId` 헬퍼. 새 응답도 이 경로로만 correlationId를 얻는다.
- `backend/src/main/java/com/bokrilog/StatusController.java:18-27` -- 루트 패키지 컨트롤러 관용(package-private 클래스 + nested record + `produces` 명시).
- `backend/src/main/java/com/bokrilog/identity/internal/IdentityAccountRepository.java:94-107` -- `insertSocialLinkIfActive`. `issuer`가 SQL에 나오는 유일한 곳이며 **`(issuer, subject)` 조회 메서드는 없다.** 여기 옆에 조회를 더한다.
- `backend/src/main/java/com/bokrilog/identity/internal/AccountLifecycleService.java:59-104` -- `linkSocialIdentity`. `:83-88` 주석이 "Story 1.4의 OIDC 콜백이 자기 `@Transactional` 안에서 부르는 모양"을 이미 전제하고, 거부가 `setRollbackOnly()`를 세우지 않게 설계돼 있다. 콜백을 한 트랜잭션으로 감싸는 것이 의도된 사용법이다.
- `backend/src/main/java/com/bokrilog/identity/internal/AccountLifecycleService.java:107-117` -- `decideSessionCreation`. 세션 개설 전 판정 훅.
- `backend/src/main/java/com/bokrilog/identity/AccountLifecycleApi.java:64` -- 공개 API 마지막 메서드. 조회를 이 뒤에 더한다.
- `backend/src/main/java/com/bokrilog/identity/package-info.java:9-10` -- "여기에는 HTTP 표면이 없다." 이 문장이 인증 컨트롤러를 `identity` 밖으로 밀어내는 근거다.
- `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java:60-87` -- HTTP 금지 규칙. 대상이 `com.bokrilog.identity..`로 **한정**되어 루트 패키지는 자유다(`StatusController`가 이미 통과하는 증거). `:75-81`이 금지 패키지 6개를 고정한다.
- `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java:32-42` -- `modules.verify()`. 새 최상위 모듈을 만들지 않으므로 모듈 간 의존 검증을 새로 열지 않는다.
- `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java:35-58` -- `postgres:18.6-alpine` static 공유 컨테이너 + `@ServiceConnection` + `@Primary` migrate 전략. **수정 불필요**, `@Import` 한 줄로 재사용한다.
- `backend/src/test/java/com/bokrilog/StatusApiTest.java:44-47,78-98` -- HTTP 시험 관용: `@SpringBootTest(RANDOM_PORT)` + `@Import(PostgresTestcontainersConfig.class)` + JDK `HttpClient`(기본 `followRedirects` = NEVER라 302 `Location`을 직접 단정 가능) + `assertNoLeak`. `:53-62` 누출 조각 9개.
- `backend/src/test/java/com/bokrilog/StatusApiTest.java:258-297` -- 시험 전용 엔드포인트를 중첩 `@TestConfiguration`으로 등록하는 관용. 로컬 OIDC 스텁이 따를 모양이다.

**프런트엔드 — 재사용 지점과 위험**

- `frontend/src/App.tsx:10-12` -- `<EntryScreen />` 한 줄. 경로별 화면 선택이 들어가는 **유일한** 지점.
- `frontend/src/content/entry.ts:56-62` -- `entry-next-step` 섹션. `:59` "로그인 수단 선택은 아직 준비 중입니다…"가 이 스토리가 교체하는 문장이며 저장소에서 이 문자열은 여기 한 곳뿐이다.
- `frontend/src/content/entry.ts:68-76,79-86` -- `entryContent`와 `entryCopyStrings`. 후자는 `SECTIONS`에서만 파생되므로 최상위에 새 키를 넣으면 `entryCopyStrings`에도 손으로 등록해야 시험이 인식한다.
- `frontend/src/screens/EntryScreen.module.css:34-35` -- 44×44 확보 관용(`min-block-size`/`min-inline-size: var(--space-target-min)`). `:47`의 `transition`은 E2E `:330-345`가 "전환이 하나 이상"을 단정하는 유일한 선언이라 잃지 않는다.
- `frontend/src/styles/tokens.css:122-124` -- `--component-social-sign-in-{min-target-size,gap,focus}` **3개뿐**. 배경·글자·테두리·모서리 토큰이 없어 Google 가이드 값을 실을 자리가 없다.
- `frontend/stylelint.config.js:59-79,111-120` -- 색·타이포·모서리·간격 리터럴 금지 대상 속성 목록과 `.module.css` camelCase 강제. `:12-13`이 "리터럴 압력은 규칙 완화가 아니라 DESIGN.md 토큰 추가로 향한다"고 규정한다.
- `frontend/src/test/setup.ts:71-105` -- `fetch`·XHR·`sendBeacon`·IndexedDB를 전역 거부하는 훅. **탈출구가 없다.** 세션 확인 요청이 있는 화면의 단위 시험은 이 파일을 건드려야 성립한다.
- `frontend/src/screens/EntryScreen.test.tsx:97-107` -- 요청·저장소 0건 단정. `<EntryScreen />`만 렌더하므로 세션 확인을 `App` 층에 두면 그대로 통과한다.
- `frontend/e2e/entry-screen.spec.ts:98-154` -- **최고 위험.** 모든 focusable에 `outline solid 2px rgb(29, 79, 145)` offset 2px과 44×44를 요구하고 Tab 도달 순서가 DOM 순서와 일치해야 한다. 새 Google 링크가 자동으로 검사 대상이 된다.
- `frontend/e2e/entry-screen.spec.ts:239-274` -- `/`에서 동일 출처 외 요청 0건 + `xhr|fetch|websocket|eventsource` 0건. 세션 확인을 `/`에서 하면 즉시 깨진다.
- `frontend/e2e/entry-screen.spec.ts:180-196` -- 400% 확대에서 `entryCopyStrings` 전부 가시. `:210-237` notice/plain tone 각 1개 이상 필요.
- `frontend/e2e/entry-screen.spec.ts:309-328` -- `localStorage`·`sessionStorage`·`document.cookie`·IndexedDB 0건. `/`에서는 세션 쿠키도 없어야 한다.
- `frontend/lint-policy.test.ts:625,653-666` -- 파일 경로를 하드코딩한다. 기존 파일 이동·개명은 실패, 추가는 안전(`arrayContaining`).
- `frontend/tsconfig.node.json` -- `include`가 루트 `.ts` 파일명 열거식이다. 루트에 새 `.ts`를 만들면 여기와 `eslint.config.ts:68`을 함께 봐야 한다.

**배포·CI — 깨지는 계약과 회피**

- `deploy/nginx/bokrilog.conf:135` -- `try_files $uri $uri/index.html =404`. `:133-134` 주석이 폴백을 두지 않는 이유를 적는다. 알려진 경로만 여는 허용 목록을 더하고 주석을 갱신한다.
- `deploy/nginx/bokrilog.conf:111-117` -- `/api` 프록시 두 블록. 인증 엔드포인트를 `/api/auth/` 아래 두면 이 블록이 그대로 받는다.
- `deploy/nginx/bokrilog.conf:87-94` -- `proxy_set_header Host $host`가 **포트를 잃는다**(`127.0.0.1`, `:18080` 없음). redirect URI를 요청에서 유도하면 틀어진다. 절대 URI를 환경변수로 주면 이 줄을 건드리지 않는다.
- `deploy/nginx/bokrilog.conf:71` + `deploy/compose/verify-same-origin.sh:372` -- CSP 정확 문자열이 **두 곳**에서 전문 비교된다. 최상위 내비게이션만 쓰면 둘 다 그대로 둘 수 있다.
- `deploy/compose/compose.yaml:103-109` -- backend env 3개(`SERVER_PORT`·`BOKRILOG_DB_URL`·`BOKRILOG_DB_USERNAME`)가 전부. `:37-42`가 가짜 자격정보 리터럴을 금지한다.
- `deploy/compose/verify-same-origin.sh:89-104` -- 로그 누출 조각 14개. `:100-103`이 환경변수 이름을 등록한 선례다.
- `deploy/compose/verify-same-origin.sh:531-551` -- PROBES 11개가 `/api/users/me`·`DELETE /api/users/me`·`POST /api/sessions`·`/api/accounts*`·`/api/identity*`를 404·405로 고정한다. **`/api/auth/*`는 목록에 없다.**
- `deploy/compose/verify-same-origin.sh:609-620` -- `GET /nope` 404 + `<div id="root">` 부재. 와일드카드 폴백이 이 절을 깬다. `:476-479`(`/nope.js`)·`:505-512`(`/apifoo`)도 같은 위험.
- `deploy/compose/verify-same-origin.sh:565-571` -- `identity_account_lifecycle` 행 0개. 검증 중 어떤 요청도 계정을 만들지 않아야 한다.
- `deploy/compose/verify-same-origin.sh:426-431` -- `/api/status` 본문 정확히 `{"status":"UP"}`. `:579-587` 게시 포트 정확히 `18080` 하나.
- `deploy/compose/verify-same-origin.sh:202-227` -- `up --wait` 앞 마이그레이션 1회. 세션 테이블도 기존 `migrate` 서비스가 그대로 처리한다.
- `.github/scripts/test-ci-scripts.sh:825-827,835-836,845-853,963-965,997-999,1034-1035,1091-1126` -- job 수·`runs-on` 5회·`needs` 전문·docs gate 3회·action SHA 6개·`ref` 5회·artifact 개수와 이름 정규식. **새 job·새 action·새 artifact를 만들지 않는 것이 이 스토리의 선택이다.**
- `.github/scripts/test-ci-scripts.sh:909-957` -- job별 단계 이름 배열. 단계 추가는 통과하지만 관용상 함께 등록한다.
- `.github/scripts/classify-changes.sh:116-121` -- `*/*`가 `backend/**`·`frontend/**`·`deploy/**`를 이미 제품 변경으로 분류한다. **수정 불필요.**
- `CLAUDE.md`, `DEVELOPMENT.md:269-280` -- "Spring Security·Spring Session JDBC는 아직 없다", "SPA 폴백을 두지 않는다", "라우터도 `/api` 호출도 동작하는 소셜 버튼도 없다"가 도입 즉시 거짓이 된다.
- `_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/DESIGN.md` -- `components.social-sign-in` frontmatter가 `layout`·`min-target-size`·`gap`·`provider-treatment`·`focus` 5개다. Google 가이드 값을 실을 리프 키가 없어 추가 등록이 필요하다.

## Tasks & Acceptance

**Execution:**
- [x] `backend/build.gradle.kts` -- `spring-boot-starter-security-oauth2-client`·`spring-boot-starter-session-jdbc`를 `implementation`으로 선언하고 `:9-13` 위임 주석을 갱신 -- 이 SPEC이 승인하는 의존은 이 둘뿐이다. 버전은 Boot BOM이 소유하고 독립 핀을 박지 않는다. 좌표 이름의 근거는 `## Spec Change Log`의 두 항목이다(`spring-boot-starter-oauth2-client`는 스스로 deprecated로 표시한 별칭이고, Boot 4의 세션 저장소는 `store-type`이 아니라 classpath가 고른다). `spring-security-test`가 필요해지면 추가하기 전에 보고한다
- [x] `backend/src/main/resources/db/migration/V2__spring_session.sql` -- Spring Session JDBC의 세션·속성 테이블과 인덱스를 expand 전용으로 추가 -- 세션이 PostgreSQL에 산다. V1은 고치지 않는다
- [x] `backend/src/main/resources/application.yml` -- `spring.session`(store-type jdbc, `initialize-schema: never`, timeout), 쿠키 속성(HttpOnly·Secure·SameSite=Lax·경로), Google 등록(`openid` 범위, 환경변수 참조 client id·secret, **절대 redirect URI를 `BOKRILOG_PUBLIC_BASE_URL`에서 조립**)을 추가하고 `:15` 위임 주석을 대체 -- 자격정보 리터럴 금지. redirect URI를 요청 Host에서 유도하지 않아 Nginx `:88`을 건드리지 않는다
- [x] `backend/src/main/java/com/bokrilog/identity/AccountLifecycleApi.java`, `internal/AccountLifecycleService.java`, `internal/IdentityAccountRepository.java` -- `(issuer, subject)` → `Optional<UUID>` 조회를 공개 API·서비스·리포지토리에 더한다. 삭제 진행·완료 계정과 미존재는 구분 없이 빈 값 -- 재로그인 경로의 유일한 진입. 거부 사유를 값으로 노출하지 않는다
- [x] `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java` (신규) -- `SecurityFilterChain`: authorization endpoint `/api/auth/authorize`, redirection endpoint `/api/auth/callback/*`, PKCE(S256) 강제, 일회성 `state`·`nonce`, 세션 고정 방어(로그인 성공 시 ID 회전), CSRF 활성, `/api/status`와 정적 경로는 인증 불요 -- 루트 패키지가 전역 정책을 소유하는 `SchemaValidationConfig` 선례를 따른다. `/api/auth/*`는 PROBES 목록과 충돌하지 않는다
- [x] `backend/src/main/java/com/bokrilog/AuthSessionService.java` (신규) -- 검증된 OIDC 사용자로 `identity` 공개 API를 호출: 조회 → 없으면 `createAccount`+`linkSocialIdentity`, `decideSessionCreation` 통과 시에만 세션 확정. 전체를 하나의 `@Transactional`로 감싸고 거부·경합 패배 시 아무 상태도 남기지 않는다 -- AC-2의 원자성. `identity.internal`을 참조하지 않는다
- [x] `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` (신규) -- 성공 핸들러(신규→온보딩, 기존→허용 목록 검증을 통과한 원래 목적지 또는 기본 대시보드)와 실패 핸들러(닫힌 표지 집합으로 인증 화면 수렴, 상태 변경 0건). 목적지 허용 목록은 서버가 가진 닫힌 집합 -- AC-4·AC-5. 외부·미등록 경로는 폐기한다
- [x] `backend/src/main/java/com/bokrilog/AuthSessionController.java` (신규) -- `GET /api/auth/session`이 인증 여부와 목적지 종류만 돌려준다. `user_id`·`subject`·이메일·토큰을 담지 않는다 -- 로그인 상태 화면이 서버 세션으로 상태를 확인하는 유일한 경로
- [x] `backend/src/main/java/com/bokrilog/ApiErrorHandler.java` -- 인증 실패용 code·message 상수를 `:47-62`에 더하고 `errorFor`(`:123-134`)가 401·403을 갈라 매핑하도록 확장 -- `:121` 주석이 지정한 지점. 응답 모양과 correlationId 경로는 그대로
- [x] `backend/src/test/java/com/bokrilog/OidcStubConfiguration.java` (신규) -- 결정적 local OIDC 스텁: 시험이 생성한 키로 JWKS를 제공하고 ID Token을 서명한다. 자격정보·외부 네트워크 없이 정상·비정상 토큰을 만든다 -- `StatusApiTest:258-297`의 중첩 `@TestConfiguration` 관용
- [x] `backend/src/test/java/com/bokrilog/AuthTokenValidationTest.java` (신규) -- ID Token 서명·허용 알고리즘·`iss`·`aud`·복수 `aud`의 `azp`·`exp`·`nonce`와 `state` 불일치·재사용·만료·흐름 바꿔치기. 각 실패에서 계정·세션 0건 -- AC-1·AC-5
- [x] `backend/src/test/java/com/bokrilog/AuthAccountMappingTest.java` (신규) -- 신규 가입, 재로그인 동일 `user_id`, 동시 첫 로그인에 계정·매핑 1개, 이메일만 같을 때 병합 금지 -- AC-2. 실제 PostgreSQL로 돈다
- [x] `backend/src/test/java/com/bokrilog/AuthSessionContractTest.java` (신규) -- 쿠키 속성 3종, 로그인 시 세션 ID 회전, 이미 인증된 세션의 로그인 시작 거절, 삭제 진행·완료 계정의 세션 개설 거부, 삭제 전환과 세션 개설 경합, 미인증 `/api/auth/session`, CSRF·`Origin` 검증 -- AC-3·AC-6
- [x] `backend/src/test/java/com/bokrilog/AuthRedirectDestinationTest.java` (신규) -- 신규·기존 목적지, 외부 주소·알 수 없는 경로 폐기, 실패 표지의 닫힌 집합, 응답·로그에 code·verifier·`subject`·`user_id` 부재 -- AC-4·AC-5와 redaction
- [x] `backend/src/test/java/com/bokrilog/AuthDeletionPathTest.java` (신규) -- `erasePayload`가 새 매핑과 세션을 함께 지우고 guard 행은 남기며 부분 실패를 완료로 보고하지 않음 -- AC-6
- [x] `backend/src/test/java/com/bokrilog/StatusApiTest.java` -- Security 필터 체인 도입 뒤에도 404·405·406·500 오류 계약과 `/api/status`가 그대로인지 확인하고 필요한 최소 배선만 더한다. **단정 본문은 손대지 않는다** -- Story 1.2 회귀 유지
- [x] `_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/DESIGN.md` -- `components.social-sign-in`에 Google 공식 가이드가 소유하는 채움색·글자색·테두리·모서리 리프 키를 등록 -- stylelint가 리터럴을 막으므로 토큰이 없으면 버튼을 만들 수 없다. `provider-treatment` 규칙의 소유권 분할은 그대로
- [x] `frontend/src/styles/tokens.css` -- 위 리프 키를 `--component-social-sign-in-google-*`로 옮긴다 -- DESIGN.md frontmatter의 평면 이름을 그대로 옮기는 관용
- [x] `frontend/src/content/auth.ts` (신규) -- 로그인 버튼 라벨(`Google로 계속하기`), 데이터 경계 안내, 실패 표지별 카피, 온보딩·로그인 상태 화면 카피와 `복리 계획 비교`·"투자성과·계좌 수익률·투자 추천이 아닙니다" 고지 -- 화면과 시험이 같은 문자열을 참조한다
- [x] `frontend/src/content/entry.ts` -- `entry-next-step`의 "준비 중" 문장(`:59`)을 실제 로그인 안내로 교체 -- 이 스토리가 그 카피를 대체한다고 `CLAUDE.md`가 예고했다
- [x] `frontend/src/App.tsx` -- `location.pathname`을 닫힌 맵으로 화면에 대응(`/`·`/onboarding`·`/dashboard`). 라우터 의존을 추가하지 않고 세션 확인 요청은 로그인 상태 경로에서만 -- `/`의 요청 0건 E2E를 지킨다
- [x] `frontend/src/screens/EntryScreen.tsx`, `EntryScreen.module.css` -- Google 버튼을 `/api/auth/authorize/google`로 가는 최상위 내비게이션 링크로 추가. 44×44·전역 포커스 링·접근 가능한 이름을 지키고 새 토큰만 사용 -- CSP를 넓히지 않고 키보드 E2E를 통과한다
- [x] `frontend/src/screens/SignedInScreen.tsx`, `OnboardingScreen.tsx`와 각 `.module.css` (신규) -- 최소 로그인 상태·온보딩 화면. 서버 세션 확인 실패 시 인증 화면으로 되돌리고 사용자 데이터를 표시하지 않는다 -- AC-4. 화면당 `role="status"` live region 하나
- [x] `frontend/src/test/setup.ts` -- 세션 확인 요청을 단위 시험에서 다룰 수 있는 **범위를 좁힌** 탈출구를 더하고 기본 거부는 유지 -- 전역 거부를 걷어내지 않는다. 금지 목록은 `DEVELOPMENT.md`와 계속 같아야 한다
- [x] `frontend/src/screens/*.test.tsx` -- 버튼 라벨·역할·목적지, 카피 정본 일치, 고지 인접 배치, 제목 구조, 세션 확인 성공·실패 분기, 저장소 쓰기 0건 -- 기존 `EntryScreen.test.tsx` 9개 단정을 유지한 채 확장
- [x] `frontend/e2e/auth-entry.spec.ts` (신규) -- 버튼 포커스 링·44×44·Tab 순서, 링크 목적지, 로그인 상태 경로의 미인증 복귀, 실패 표지 화면, `/`의 쿠키·저장소 0건 -- 브라우저 층 AC-4~AC-6
- [x] `deploy/nginx/bokrilog.conf` -- `/onboarding`·`/dashboard`만 `index.html`로 여는 **허용 목록** location을 더하고 `:133-134` 주석을 갱신. 그 밖의 경로는 계속 404 -- 와일드카드 폴백을 두지 않아 `verify-same-origin.sh:609-620`·`:476-479`·`:505-512`가 그대로 통과한다. CSP·`Host` 헤더는 건드리지 않는다
- [x] `deploy/compose/compose.yaml` -- backend에 `BOKRILOG_PUBLIC_BASE_URL`과 Google 등록 환경변수를 **값 리터럴 없이** 참조로 추가 -- `:37-42`의 가짜 자격정보 금지를 지킨다. 새 서비스·새 게시 포트를 만들지 않는다
- [x] `deploy/compose/verify-same-origin.sh` -- 새 환경변수 이름을 누출 조각(`:89-104`)에 등록하고, 자격정보 없는 스택에서 인증 시작·콜백이 단일 오류 계약으로 fail-closed이며 `identity_account_lifecycle` 행이 계속 0개임을 단정하는 절과 허용 목록 경로 200·그 밖 404 단정을 더한다 -- 기존 단정을 완화하지 않는다
- [ ] `.github/scripts/test-ci-scripts.sh` -- **변경 없음.** 이 스토리는 `ci.yml`을 한 줄도 바꾸지 않았다(새 job·새 action·새 artifact·새 단계 없음). 그래서 이 시험도 갱신할 것이 없고 `bash .github/scripts/test-ci-scripts.sh`는 그대로 147개 전부 통과한다. 단계 이름이 늘어나는 변경이 생기면 그때 해당 job 배열을 갱신한다
- [x] `CLAUDE.md`, `DEVELOPMENT.md` -- Spring Security·Spring Session JDBC 도입, 경로 허용 목록, 새 환경변수와 인증 엔드포인트 경로, "라우터도 소셜 버튼도 없다"의 철회를 반영 -- 문서와 사실을 맞춘다

- [x] `backend/src/main/java/com/bokrilog/DevOidcStubConfig.java` (신규) + 기존 시험 스텁 이관 -- local OIDC 제공자(discovery·JWKS·인가·토큰)를 `@Profile("dev-oidc")`로 `/api/dev-oidc/**`에 두고, 시험 소스의 `OidcStubConfiguration`은 이 bean을 재사용하도록 줄인다 -- 브라우저와 백엔드가 같은 origin에서 도달해야 성공 경로를 브라우저 층에서 검증할 수 있다. 스텁을 두 번 만들지 않는다
- [x] `backend/src/main/java/com/bokrilog/DevOidcStubConfig.java` 기동 guard + `backend/src/test/java/com/bokrilog/DevOidcStubProfileTest.java` (신규) -- `dev-oidc`가 실제 Google 등록과 함께 활성이면 기동 거부. 기본 프로필에서 스텁 bean 부재와 `/api/dev-oidc/**` 미노출을 `ApplicationContextRunner`로 단정 -- 프로필이 운영에서 조용히 켜지는 경로를 막는다
- [x] `deploy/compose/compose.yaml` -- backend에 `SPRING_PROFILES_ACTIVE: dev-oidc`와 스텁을 가리키는 등록 환경변수 참조를 더한다(값 리터럴 없음) -- 개발·CI 스택에서 왕복이 완성된다. 새 서비스·새 게시 포트를 만들지 않는다
- [x] `deploy/compose/verify-same-origin.sh` -- 인가 시작 → 콜백 → 세션 쿠키 → 목적지까지의 성공 왕복 단정을 더하고, 계정·세션 행이 그 왕복에서만 늘었음을 확인한 뒤 스택을 원래 상태로 되돌린다. `identity_account_lifecycle` 0행 단정은 왕복 앞으로 옮긴다 -- 기존 단정을 완화하지 않는다
- [x] `frontend/e2e/auth-entry.spec.ts` -- `PLAYWRIGHT_BASE_URL`이 주어진 실행에서만 도는 성공 경로 시험을 더한다(버튼 클릭 → 스텁 동의 → 온보딩 도달 → 쿠키 존재). `same-origin-csp.spec.ts:27-31`의 조건 실행 관용을 따른다 -- `epics.md`가 요구한 "성공" 브라우저 시험
- [x] `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` -- 실패 핸들러가 **이 흐름이 만든 인증 전 세션만 정리한다. 이미 인증된 주체가 실린 세션은 건드리지 않는다.** 거부된 로그인 뒤 `spring_session` 증가는 0이고, 개설된 세션은 실패하는 콜백을 지나도 그대로 남는다 -- 앞의 절반은 `AuthSessionContractTest`의 `expected: 0L but was: 1L`가 지목한 코드 결함이다(시험 기대값을 고치지 않는다). 뒤의 절반은 그 수정이 만들 수 있는 크로스 사이트 강제 로그아웃을 막는다 -- 콜백은 미인증 최상위 GET이고 쿠키가 `SameSite=Lax`라 외부 사이트가 `top.location`으로 이 경로를 열 수 있다
- [x] `backend/src/test/java/com/bokrilog/AuthSessionContractTest.java` -- 미인증 CSRF 실패 기대를 401에서 403 `FORBIDDEN`으로 맞추고 이유를 주석으로 남긴다 -- CSRF 실패는 인증 여부와 무관하게 하나의 코드다. 인증 여부가 응답으로 드러나지 않는다

**리뷰 patch 라운드(2026-09-07):**

- [x] `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` -- `discardSession`이 세션에 저장된 `SecurityContext`를 보고 이 흐름의 인증 전 세션만 지운다. 개설된 세션(주체가 `PreAuthenticatedAuthenticationToken`)은 그대로 둔다 -- **보안.** 조건 없는 삭제는 외부 사이트가 콜백 URL을 최상위로 여는 것만으로 피해자를 로그아웃시킨다. 판정은 thread-local이 아니라 세션 속성으로 한다(실패 핸들러 진입 전에 thread-local은 비워진다)
- [x] `backend/src/test/java/com/bokrilog/AuthTokenValidationTest.java` -- `한_번_쓴_state는_다시_쓸_수_없다`의 `sessionCount()).isZero()`를 뒤집어 기존 세션이 살아 있음을 단정하고, 개설된 세션 쿠키로 실패하는 콜백을 부른 뒤에도 그 세션이 그대로임을 보는 시험과 이미 교환된 code가 새 흐름에서도 거부됨을 보는 시험을 더한다 -- 앞의 단정은 강제 로그아웃을 옳은 동작으로 고정하고 있었다
- [x] `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java` -- `normalizedBaseUrl`이 `java.net.URI`로 파싱해 host가 없거나 경로·쿼리·프래그먼트·userinfo가 있으면 거부한다 -- 스킴 접두사만 보면 경로가 붙은 값이 통과하고, 그 값은 `SameOriginFilter`의 `Origin` 비교를 영구히 어긋나게 해 모든 상태 변경 요청을 조용히 403으로 만든다
- [x] `backend/src/test/java/com/bokrilog/DevOidcStubProfileTest.java` -- `normalizedBaseUrl`의 수용·거부 사례와 "경로가 붙은 base URL로는 등록이 만들어지지 않는다"를 단정 -- Docker 없이 도는 자리다
- [x] `backend/src/main/java/com/bokrilog/DevOidcStub.java` -- 발급한 code를 인가 요청(`code_challenge`·`redirect_uri`·`client_id`)에 귀속시키고, 토큰 교환에서 code를 `remove`로 소비하며 `code_verifier`의 S256을 재계산해 비교하고 `redirect_uri`와 client 자격정보(`client_secret_basic`)를 확인한다. PKCE S256이 아닌 인가 요청에는 code를 발급하지 않는다 -- 스텁이 검사하지 않으면 우리 쪽에서 PKCE 결합이 깨지거나 code 재사용이 열려도 모든 시험이 통과한다. AC-1의 핵심이 이 자리다
- [x] `backend/src/test/java/com/bokrilog/DevOidcStubTest.java` (신규) -- 스텁의 거절 경로를 직접 단정한다(code 재사용, verifier 불일치·부재, redirect URI 불일치, client 자격정보 오류, PKCE 없는 인가). Spring도 Docker도 쓰지 않는다 -- 스텁이 검사하는 척만 하는 상태를 이 시험이 막는다
- [x] `backend/src/test/java/com/bokrilog/{AuthFlowTestBase,OidcStubConfiguration}.java`와 인증 시험 네 개 -- `issueCode(TokenPlan)`을 `issueCode(Authorization, TokenPlan)`으로 좁혀 모든 code가 인가 요청에 귀속되게 한다 -- 귀속 없이 발급할 수 있는 경로가 남으면 그것이 곧 검사 우회다
- [x] `frontend/src/session/useSessionState.ts`, `frontend/src/session/navigation.ts` (신규), `OnboardingScreen.tsx`, `SignedInScreen.tsx` -- 서버가 주는 `destination`을 소비한다. `useSessionGate(expected)`가 종류를 비교해 다르면 화면을 그리지 않고 서버가 고른 목적지로 최상위 내비게이션한다. 그 전환은 `navigation.ts` 한 곳이 소유한다 -- AC-4. 값을 버리면 `/onboarding`을 직접 연 기존 사용자에게 온보딩이 다시 그려진다
- [x] `frontend/src/session/useSessionState.ts` -- `pageshow`의 `persisted`에서 세션을 다시 확인하고, 요청에 클라이언트 시한(10초)을 둔다 -- UX-DR22가 "뒤로 가기 복원으로 민감 화면 노출 금지"를 직접 요구한다. bfcache 복원은 마운트를 다시 하지 않아 확인이 돌지 않는다. 시한이 없으면 프록시의 읽기 timeout(30초)까지 "확인 중"에 머문다
- [x] `frontend/src/content/auth.ts`, `EntryScreen.tsx` -- 현재 경로가 서버 허용 목록에 있을 때 `?next=<경로>`를 붙이는 `authorizeHref`를 더하고 로그인 링크가 그것을 쓴다 -- 생산자가 없어 서버의 "기존 사용자는 원래 목적지" 분기가 제품 코드로 도달 불가였다
- [x] `OnboardingScreen.tsx`, `SignedInScreen.tsx`와 각 `.module.css` -- `entryContent.skipLink` 카피로 건너뛰기 링크를 넣는다(진입 화면과 같은 관용) -- `tabIndex={-1}` skip 타깃만 있고 링크가 없어 그 타깃에 도달할 방법이 없었다
- [x] `frontend/e2e/auth-entry.spec.ts` -- axe 검사를 미인증 화면 변형 네 개로 넓히고, 주입된 origin의 성공 왕복 뒤 **인증된** 온보딩 화면의 건너뛰기 링크·Tab 순서·axe를 단정한다 -- 미인증 복귀 화면은 진입 화면을 렌더하므로 그 검사가 두 새 화면의 마크업을 한 번도 보지 않았다
- [x] `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` -- 인증 전 흐름 세션의 유휴 수명을 5분으로 낮추고 로그인 성공 시 `spring.session.timeout`으로 되돌린다 -- 미인증 인증 시작 한 번마다 세션 행이 하나 생기고 상한이 없었다. 저장소를 바꾸면 브라우저에 두는 값이 세션 쿠키 하나라는 계약이 깨지므로 수명으로 경계를 만든다
- [x] `backend/src/test/java/com/bokrilog/StatusApiTest.java` -- 자격정보 없는 컨텍스트에서 `GET /api/auth/authorize/google`이 500 + 공통 오류 본문을, `/api/auth/authorize/naver`가 404를 돌려주는 시험을 더한다. **기존 단정 본문은 손대지 않는다** -- I/O 매트릭스 마지막 두 행이 어떤 시험에도 없었다
- [x] `backend/src/test/java/com/bokrilog/AuthAccountMappingTest.java` -- 동시 첫 로그인의 `satisfiesAnyOf`에서 실패 수렴을 뺀다. 두 응답이 모두 목적지로 갔고 같은 `principal_name`의 세션이 둘임을 단정한다 -- 실패를 허용하면 사람이 승인한 "경합 패배자도 로그인된다"가 회귀해도 통과한다
- [x] `backend/src/test/java/com/bokrilog/AuthSessionContractTest.java` -- 로그인 뒤 `spring_session_attributes.attribute_bytes`에 제공자 토큰·ID Token·`subject`·issuer가 없고 내부 `user_id`만 있음을 단정하고, 흐름 세션의 짧은 수명과 로그인 뒤 복원도 단정한다 -- 행 개수만 세면 무엇이 저장됐는지 아무도 보지 않는다
- [x] `backend/src/test/java/com/bokrilog/AuthDeletionPathTest.java` -- `@MockitoSpyBean`으로 `deleteAccountPayload`에 실패를 주입해 예외 뒤 세션 행이 롤백됨을 단정한다(Story 1.3의 `doThrow` 관용) -- 세션 삭제만 커밋되는 부분 상태를 어떤 시험도 보지 않았다
- [x] `deploy/compose/verify-same-origin.sh` -- access log이 허용 목록 경로를 요청된 이름으로 남기고 내부 redirect 뒤의 `/index.html`로 바꿔 적지 않음을 단정하고, `scope` 파라미터를 부분 문자열이 아니라 정확히 비교한다 -- `$request_path`를 `$uri`로 바꾸는 회귀와 `scope=openid%20profile`이 둘 다 잡히지 않았다
- [x] `backend/src/main/resources/application.yml` -- `Secure` 쿠키 주석을 `## Design Notes`의 정정된 내용에 맞춘다(루프백은 W3C potentially trustworthy origin이라 평문에서도 동작한다) -- 주석이 정반대 근거를 적고 있었다
- [x] `deploy/nginx/bokrilog.conf` -- `/api` 블록 위 주석이 "이 파일에 정규식 location은 없다"고 하던 것을 바로 아래의 허용 목록 정규식 location을 지목하도록 고친다
- [x] `AuthSessionController.java`, `AuthSecurityConfig.java`, `DevOidcStubConfig.java`, `IdentityAccountRepository.java` -- 도달 불가 분기와 버려지는 반환값을 지운다(`authorizationUnavailable`의 첫 조건절과 그것만 쓰던 `registrations` 의존, `AuthProperties.google()`의 `null` 검사 둘, `deleteSessionsOf`의 `int` 반환) -- 아무도 지나지 않는 분기는 계약을 표현하지 않고 읽는 사람만 헷갈리게 한다
- [x] `backend/src/test/java/com/bokrilog/AuthSessionContractTest.java` -- 세션 속성 검사를 데이터베이스의 `convert_from(…, 'LATIN1')`에서 bytea를 받아 Java에서 `ISO_8859_1`로 옮기는 방식으로 바꾼다 -- Java 직렬화 바이트에 `0x00`이 있고 PostgreSQL `text`는 어떤 인코딩에서도 NUL을 담을 수 없어 검사 자체가 죽었다. 검사 의도는 그대로다
- [x] `deploy/nginx/bokrilog.conf` -- `^~ /api/auth/callback/` 블록을 더해 그 경로의 `error_log` 수준만 `crit`으로 올린다 -- `error_log`는 형식을 바꿀 수 없어 항목마다 원본 요청 줄을 적고, 업스트림 오류에서 authorization code와 `state`가 그대로 남는다(실측 확인). access log의 `map`으로는 막을 수 없는 절반이다
- [x] `deploy/compose/verify-same-origin.sh` -- 백엔드 정지 절에서 고정 표지를 붙인 콜백을 한 번 부르고, 로그 누출 검사에 그 표지 두 개의 부재 단정을 더한다 -- 위 nginx 블록이 사라지는 회귀를 잡는다. 정지 절에 두어 `proxy_read_timeout` 30초를 더 쓰지 않는다
**Acceptance Criteria:**
- Given Docker가 실행 중인 깨끗한 체크아웃, when `backend/gradlew --project-dir backend build`를 실행하면, then OIDC 스텁·PostgreSQL 통합 시험과 Modulith 경계 시험이 통과하고 `-Werror` 아래 경고 없이 `bokrilog-backend.jar`가 생성된다
- Given 자격정보가 없는 개발·CI 스택, when `deploy/compose/verify-same-origin.sh`를 실행하면, then Story 1.1~1.3의 모든 단정이 그대로 통과하고 인증 표면이 fail-closed이며 계정 행은 0개이고 종료 코드가 0이다
- Given 실행 중인 스택, when 외부에서 `/api/users/me`·`/api/sessions`·`/api/accounts`·`/api/identity` 아래 경로를 호출하면, then 여전히 404 또는 405이며 `user_id`가 응답에 없다
- Given 실행 중인 스택, when `/onboarding`·`/dashboard`와 `/nope`·`/nope.js`를 요청하면, then 앞의 둘만 200 문서이고 나머지는 404 오류 계약이다
- Given 검증을 통과하지 못한 콜백, when 어떤 실패 경로로든 흐름이 끝나면, then 계정·매핑·세션이 0건 늘고 응답·로그·URL에 code·PKCE verifier·`subject`·`user_id`가 없다
- Given 로그인에 성공한 세션, when 응답 쿠키와 세션 저장소를 확인하면, then 불투명 쿠키가 `HttpOnly`·`Secure`·`SameSite=Lax`이고 세션 ID가 로그인 시점에 회전했으며 세션 행이 PostgreSQL에 있다
- Given 빌드된 프런트엔드, when `npm --prefix frontend run typecheck`·`lint`·`lint:css`·`format:check`·`build`·`test`·`test:e2e`를 실행하면, then Story 1.1 회귀를 포함해 전부 통과하고 `/`에서 문서·번들 외 요청과 저장소 쓰기가 0건이다
- Given 변경된 워크플로 계약, when `bash .github/scripts/test-ci-scripts.sh`를 실행하면, then 모든 단정이 통과한다

## Spec Change Log

- 2026-09-07 Docker 복구 뒤 실측 검증에서 두 건을 더 고쳤다. frozen 블록은 바꾸지 않았다.
  - **세션 속성 검사가 기법 때문에 죽고 있었다.** `convert_from(attribute_bytes, 'LATIN1')`은 "LATIN1이 256개 바이트를 모두 받는다"는 전제로 쓰였지만, NUL 금지는 문자 집합의 성질이 아니라 PostgreSQL `text`의 제약이다. Java 직렬화 바이트에는 `0x00`이 있어 `invalid byte sequence`로 죽었다. bytea를 받아 Java에서 `ISO_8859_1`로 옮기는 쪽으로 바꿨다 — 검사 대상과 단정 목록은 그대로다.
  - **nginx `error_log`가 콜백 쿼리를 남기고 있었다(실측 확인).** 백엔드를 멈추고 콜백을 부르면 `upstream timed out` 항목에 authorization code와 `state`가 실렸다. `error_log`는 `access_log`와 달리 형식을 바꿀 수 없어 `map`으로 막을 수 없다. `^~ /api/auth/callback/` 블록에서 그 경로의 오류 수준만 `crit`으로 올려 막고, 같은 표지로 회귀 단정을 검증 스크립트에 넣었다. 잃는 것은 그 한 경로의 업스트림 오류 진단 줄이며 상태 코드는 access log가 보존한다.
  - 이 두 건은 리뷰 목록에 없었다. 하나는 리뷰가 위치를 잘못 지목해 미확인으로 남겨 둔 항목이고(내가 실험으로 확정했다), 하나는 리뷰 patch가 새로 넣은 시험이 처음 실행되면서 드러났다. 둘 다 Docker 없이는 볼 수 없었다.

  - **`AuthFlowHandlers` task를 날카롭게 했다.** "실패 핸들러가 세션 행을 남기지 않게 고친다"는 문구가 구현에서 "요청이 들고 온 쿠키의 세션을 조건 없이 지운다"로 실현됐고, 그것이 크로스 사이트 강제 로그아웃이었다. 콜백은 미인증 최상위 GET이고 쿠키가 `SameSite=Lax`라 외부 사이트가 `top.location`으로 이 경로를 열 수 있다. task를 "이 흐름이 만든 인증 전 세션만 정리한다. 이미 인증된 세션은 건드리지 않는다"로 고쳤다. frozen 불변식("실패는 어떤 상태도 바꾸지 않는다")의 해석이지 변경이 아니다.
  - **의존 좌표 이름을 실제 좌표로 고쳤다.** task 문구가 아직 `spring-boot-starter-oauth2-client`·`spring-session-jdbc`를 적고 있었다. 실제로 선언된 것은 `spring-boot-starter-security-oauth2-client`·`spring-boot-starter-session-jdbc`이며, 그 근거는 위 2026-09-07 항목의 마지막 두 줄이 이미 적었다. 문구만 그 결론을 따라가지 못한 상태였다.
  - **`.github/scripts/test-ci-scripts.sh` task의 체크를 해제했다.** 이 스토리는 `ci.yml`을 한 줄도 바꾸지 않았고(새 job·action·artifact·단계 없음) 그 시험 파일도 바뀌지 않았다. 완료로 표시해 두면 "무엇을 갱신했는가"라는 질문에 답이 없다. 문구를 "변경 없음"으로 고쳤고 `bash .github/scripts/test-ci-scripts.sh`는 147개 전부 통과한다.
  - **인증 전 흐름 세션에 수명 경계를 넣었다.** 미인증 인증 시작 한 번마다 `spring_session` 행이 하나 생기고 상한이 없었다(Spring Security가 authorization request를 HTTP 세션에 담는다). 저장소를 세션 밖으로 옮기는 길은 브라우저에 두는 값이 세션 쿠키 하나라는 계약과 부딪히므로, 흐름 세션의 유휴 수명을 5분으로 낮추고 로그인 성공 시 `spring.session.timeout`으로 되돌리는 쪽을 골랐다.
  - **스텁이 제공자 자리의 검사를 실제로 한다.** `DevOidcStub`이 code를 일회성으로 다루고 PKCE verifier의 S256을 인가 시점의 challenge와 재계산해 비교하며 `redirect_uri`와 client 자격정보도 확인한다. 그 전에는 verifier의 존재만 확인해, 우리 쪽에서 PKCE 결합이 깨져도 모든 시험이 통과했다. 시험이 code를 귀속 없이 발급하는 경로도 함께 닫았다(`issueCode`가 인가 요청을 요구한다).
  - **프런트엔드가 `destination`을 소비한다.** 서버 응답의 그 필드가 제품 코드에서 쓰이지 않아 AC-4의 절반이 브라우저에서 성립하지 않았다. `useSessionGate`가 종류를 비교해 다르면 화면을 그리지 않고 서버가 고른 목적지로 보낸다. 최상위 내비게이션은 `frontend/src/session/navigation.ts` 한 곳이 소유한다(jsdom이 `location.replace`를 수행하지도 관측하게 하지도 않으므로, 시험이 그 모듈을 대신 세운다).
  - **`?next=`의 생산자를 화면에 두었다.** 서버의 복귀 목적지 허용 목록 판정과 "기존 사용자는 원래 목적지" 분기가 제품 코드로는 도달할 수 없었다. `authorizeHref`가 현재 경로가 허용 목록에 있을 때만 그 값을 싣는다.

- 2026-09-07 첫 구현 검증 뒤 개정. 사람이 세 결정을 확정했고 그중 하나는 frozen 매트릭스 행이다.
  - **frozen 변경(사람 승인).** "동시 첫 로그인" 행의 Expected 칸("한쪽만 세션을 얻는다")과 Error handling 칸("롤백 후 재조회 **또는** 실패 수렴")이 서로 부딪혀 구현이 어느 쪽도 확정적으로 만족시킬 수 없었다. 경합에 진 요청도 재조회로 같은 계정의 세션을 얻는 쪽으로 고정했다. 정상 사용자를 경합 때문에 거절하지 않는 편이 낫고, 지켜야 할 불변식은 "계정 1개·매핑 1행"이다.
  - **OIDC 스텁을 시험 소스에서 `main` + `dev-oidc` 프로필로 옮긴다.** 시험 소스에만 두면 브라우저 층이 성공 경로에 닿지 못해 `epics.md`가 Story 1.4에 요구한 "브라우저 시험: … 성공" 기준을 미달한다. Authorization Code 흐름은 인가 요청이 브라우저에서, code 교환과 JWKS 조회가 백엔드에서 나가므로 Playwright의 `page.route()`로 대체할 수 없다. 배치 근거와 이중 봉인은 `## Design Notes` 참조.
  - **거부된 로그인이 `spring_session` 행을 남기던 결함을 코드에서 고친다.** Spring Security가 인가 요청을 HTTP 세션에 담아 흐름 시작만으로 세션이 생기고, 실패 핸들러의 무효화가 그 행을 지우지 못했다. 시험이 매트릭스와 일치하고 코드가 어긋난 경우라 기대값을 건드리지 않는다.
  - **CSRF 실패는 인증 여부와 무관하게 403 `FORBIDDEN` 하나다.** 미인증 실패를 401로 갈랐던 시험 기대를 되돌린다. Spring이 익명 접근 거부를 entry point로 보낼 것이라는 전제가 이 구성에서 성립하지 않았고, 하나로 두는 편이 인증 여부를 응답으로 드러내지 않는다.
  - `## Design Notes`의 `Secure` 쿠키 문단이 틀렸던 것을 고쳤다. `127.0.0.1`은 W3C Secure Contexts의 potentially trustworthy origin이라 평문 루프백에서도 `Secure` 쿠키가 동작한다. 원래 문단은 그 반대를 근거로 삼고 있었다.
  - `spring.session.store-type`은 Boot 4에서 사라졌다(저장소는 classpath가 고른다). 그 이름을 지목한 task 문구는 유효하지 않다.
  - 의존 좌표는 `spring-boot-starter-security-oauth2-client`다. SPEC이 적은 `spring-boot-starter-oauth2-client`는 그 POM 자신이 deprecated로 표시하며 내용과 관리 버전이 같다.

## Design Notes

**Spring Security를 쓰고 손으로 만들지 않는다.** AD-10이 요구하는 목록(Authorization Code + PKCE S256, 일회성 `state`·`nonce`, JWS 서명·허용 알고리즘·JWKS·`iss`·`aud`·`azp`·`exp`·`nonce` 검증, 세션 고정 방어, CSRF)은 Spring Security OAuth2 Client가 이미 계약으로 제공한다. 직접 구현하면 JWKS 캐시·시계 오차·알고리즘 혼동 공격을 우리가 다시 틀리게 만들 여지가 열린다. 버전은 Boot 4.1.1 BOM이 소유한다(`ARCHITECTURE-SPINE.md` Stack 표에 Spring Security 항목이 없고, "실제 artifact는 lockfile이 소유한다"가 그 공백의 처리 규칙이다).

**새 모듈을 만들지 않는다.** 허용 모듈은 8개로 열거돼 있고(`AR-2`) `ARCHITECTURE-SPINE.md`의 Structural Seed가 "`identity/` — social identity and session-facing application API"로 이 능력을 `identity`에 귀속한다. 그런데 `identity`는 HTTP를 가질 수 없다(`package-info.java:9-10`, `ModuleBoundaryTest:60-87`). 두 제약을 함께 만족시키는 배치는 하나뿐이다 — 판정·매핑·조회는 `identity` 공개 API가, HTTP 표면과 세션 정책은 루트 패키지가 소유한다. 루트 패키지는 Modulith 모듈이 아니라서(`BokriLogApplication.java:9-11`) `modules.verify()`에 새 모듈 간 의존 간선을 만들지 않는다. `com.bokrilog.auth`를 만들면 세 검사 중 둘은 통과하지만 모듈 간 의존 검증이 새로 열린다.

**redirect URI를 요청에서 유도하지 않는다.** Nginx가 `Host $host`를 넘겨 포트를 잃으므로(`bokrilog.conf:88`) 요청에서 조립한 redirect URI는 `http://127.0.0.1/...`가 되어 등록값과 어긋난다. `$http_host`로 바꾸거나 `X-Forwarded-*`를 더하는 길은 둘 다 클라이언트가 제어하는 헤더를 redirect URI 계산에 넣는다. 절대 URI를 환경변수로 주면 그 입력 경로 자체가 없어지고, "사전 등록된 정확한 redirect URI"(EXPERIENCE)와도 같은 모양이며 Nginx 파일을 건드리지 않는다.

**폴백이 아니라 허용 목록이다.** `try_files … =404`(`:135`)는 "없는 것은 없다고 답한다"는 결정이고 `verify-same-origin.sh:614`가 그것을 단정한다. 와일드카드 폴백을 넣으면 그 단정과 함께 `/nope.js`·`/apifoo` 절까지 재설계해야 하고, 존재하지 않는 경로가 200 + 진입 화면으로 응답하는 상태로 되돌아간다. 알려진 두 경로만 명시 location으로 여는 편이 계약을 하나도 깨지 않고, 라우트가 늘 때마다 사람이 한 줄을 더하는 비용은 이 규모에서 이점이다.

**CSP를 넓히지 않는다.** 버튼을 `<a href>` 최상위 내비게이션으로 두면 `form-action 'none'`·`connect-src 'self'`·`default-src 'none'`이 모두 그대로다. Google JS SDK나 iframe을 쓰는 순간 `bokrilog.conf:71`과 `verify-same-origin.sh:372`를 함께 넓혀야 하고, 그것이 "외부 CDN·SDK 금지"와 정면으로 부딪힌다. 서버사이드 리다이렉트 흐름이 이 저장소의 브라우저 경계와 맞는 유일한 모양이다.

**세션 확인은 로그인 상태 경로에서만 한다.** `/`는 문서·번들 외 요청 0건이 시험으로 고정돼 있다(`e2e/entry-screen.spec.ts:239-274`). 목적지는 서버가 302로 정하므로 `/`가 세션을 물어볼 이유가 없다. `/onboarding`·`/dashboard`만 `GET /api/auth/session` 한 번으로 상태를 확인하고, 그 응답에는 인증 여부와 목적지 종류만 담아 `user_id`·`subject`가 브라우저로 나가지 않는다.

**Google 버튼 값은 토큰으로 등록한다.** DESIGN.md는 채움색·글자색·테두리·모서리를 제공자 가이드에 넘기지만 stylelint는 그 네 범주의 리터럴을 막는다(`stylelint.config.js:59-79`). 규칙을 완화하는 대신 값을 토큰으로 등록하는 것이 `:12-13`이 지정한 방향이다. 소유자가 Google이라는 사실은 토큰 이름(`--component-social-sign-in-google-*`)과 주석이 나른다.

**`Secure` 쿠키는 평문 루프백에서도 동작한다.** compose가 게시하는 `http://127.0.0.1:18080`은 W3C Secure Contexts가 규정한 potentially trustworthy origin(`127.0.0.0/8`·`::1`·`localhost`)이므로 Chromium·Firefox가 스킴이 `http`여도 `Secure` 쿠키의 설정과 회신을 허용한다. `SameSite=Lax`도 콜백이 최상위 GET 내비게이션이라 통과한다. 그래서 `Secure`를 프로필로 갈라 끌 이유가 없다 — AD-10을 개발 편의로 무르면 그 완화가 production까지 남는다. 단 Spring Session의 `DefaultCookieSerializer`는 `useSecureCookie`를 명시하지 않으면 `request.isSecure()`를 따르고 Nginx가 `X-Forwarded-Proto: http`를 넘기므로, 속성을 실제로 붙이려면 명시적으로 `true`로 못박아야 한다.

**개발·CI에 자격정보를 두지 않고, 스텁은 프로필이 가른다.** 정상 흐름의 결정성은 `dev-oidc` 프로필에서만 살아나는 local OIDC 제공자가 만든다. 이 스텁은 자기 키로 JWKS를 제공하고 ID Token을 서명하므로 외부 네트워크도, 가짜 secret 리터럴도 필요 없다(가짜 자격정보를 커밋하면 gitleaks 규칙을 완화하고 싶어지고 그 완화가 남는다 — `compose.yaml:37-42`). 스텁을 시험 소스가 아니라 `main`에 두는 이유는 브라우저다. Authorization Code 흐름의 인가 요청은 **브라우저**가 보내고 code 교환과 JWKS 조회는 **백엔드**가 보내므로, 한 곳이 양쪽에서 도달 가능해야 성공 경로를 브라우저 층에서 검증할 수 있다. `/api` 아래에 두면 Nginx가 이미 프록시하는 경로라 새 서비스·새 게시 포트·새 compose 파일·CSP 확장이 하나도 필요 없다. compose에 별도 스텁 서비스를 두는 길은 `verify-same-origin.sh:579-587`의 "게시 포트는 18080 하나" 단정과 "저장소가 소유하는 compose 구성은 하나"에 부딪힌다.

**프로필이 운영에서 켜지지 않게 두 겹으로 막는다.** 첫째, 스텁 bean이 기본 프로필에서 하나도 만들어지지 않음을 `ApplicationContextRunner`로 단정한다 — `SchemaValidationConfigTest`가 Flyway 전략 선택에 쓰는 것과 같은 관용이다. 둘째, `dev-oidc`가 실제 Google 등록과 함께 활성이면 기동을 거부한다. 기본 프로필의 fail-closed 계약(I/O 매트릭스 마지막 두 행)은 compose가 아니라 이 시험들이 지킨다.

## Verification

**Commands:**
- `backend/gradlew --project-dir backend build` -- expected: OIDC 스텁·통합·경계 시험 통과, 경고 0건, jar 생성
- `npm --prefix frontend ci && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run lint:css && npm --prefix frontend run format:check && npm --prefix frontend run build && npm --prefix frontend run test` -- expected: 전부 통과, 경고 0건
- `npm --prefix frontend run test:e2e` -- expected: Story 1.1 회귀와 새 인증 화면 시험 통과
- `deploy/compose/verify-same-origin.sh` -- expected: 모든 단정 통과, exit 0
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 갱신된 계약 통과
- `git status --short` -- expected: 빌드·시험 산출물이 미추적 파일로 남지 않음

**Manual checks:**
- `grep -rn "identity" deploy/nginx/bokrilog.conf` -- expected: 0건
- 실행 중인 스택의 `docker compose logs`에서 `code=`·`state=`·`client-secret`·`user_id` 값 검색 -- expected: 0건
- `git diff` 전체에서 자격정보 모양 리터럴 검색 -- expected: 0건

## Suggested Review Order

**진입점**

- 인증 표면 전체의 모양이 한 곳에 있다 — 엔드포인트 위치, PKCE, CSRF, Origin 검증
  [`AuthSecurityConfig.java:353`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L353)

**계정 결정 — `identity` 공개 API 접점**

- 조회 → 없으면 생성·연결 → 세션 판정을 한 트랜잭션으로 묶는다
  [`AuthSessionService.java:68`](../../backend/src/main/java/com/bokrilog/AuthSessionService.java#L68)

- 경합에 진 요청도 재조회로 같은 계정의 세션을 얻는다. 시도 상한 2회
  [`AuthSessionService.java:41`](../../backend/src/main/java/com/bokrilog/AuthSessionService.java#L41)

- 재로그인 경로의 유일한 진입. 삭제·미존재는 구분 없이 빈 값
  [`AccountLifecycleApi.java:86`](../../backend/src/main/java/com/bokrilog/identity/AccountLifecycleApi.java#L86)

**실패 처리 — 어떤 상태도 바꾸지 않는다**

- 이 흐름이 만든 인증 전 세션만 지운다. 개설된 세션은 건드리지 않는다
  [`AuthFlowHandlers.java:343`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L343)

- 실패는 닫힌 표지 집합으로 인증 화면에 수렴한다
  [`AuthFlowHandlers.java:317`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L317)

- 복귀 목적지는 서버가 가진 닫힌 집합뿐이다
  [`AuthFlowHandlers.java:74`](../../backend/src/main/java/com/bokrilog/AuthFlowHandlers.java#L74)

**제공자 토큰과 redirect URI**

- 액세스·리프레시 토큰을 어디에도 저장하지 않는다
  [`AuthSecurityConfig.java:323`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L323)

- redirect URI를 요청 Host에서 유도하지 않는다. 경로·userinfo가 붙은 값은 거부
  [`AuthSecurityConfig.java:230`](../../backend/src/main/java/com/bokrilog/AuthSecurityConfig.java#L230)

**동일 출처 경계와 로그**

- 콜백만 오류 로그 수준을 올린다. `error_log`는 형식을 바꿀 수 없다
  [`bokrilog.conf:153`](../../deploy/nginx/bokrilog.conf#L153)

- access log에서 쿼리 문자열을 잘라낸다. `$uri`를 쓰지 않은 이유가 함께 있다
  [`bokrilog.conf:32`](../../deploy/nginx/bokrilog.conf#L32)

- 폴백이 아니라 허용 목록이다. 이름을 적은 두 경로만 문서를 받는다
  [`bokrilog.conf:171`](../../deploy/nginx/bokrilog.conf#L171)

**개발·CI 스텁**

- 인가 코드를 일회성으로 소비하고 PKCE S256을 재계산해 비교한다
  [`DevOidcStub.java:245`](../../backend/src/main/java/com/bokrilog/DevOidcStub.java#L245)

- `dev-oidc` 프로필에서만 살아난다. 실제 자격정보와 함께면 기동을 거부한다
  [`DevOidcStubConfig.java:76`](../../backend/src/main/java/com/bokrilog/DevOidcStubConfig.java#L76)

**프런트엔드**

- 경로를 닫힌 맵으로 화면에 대응시킨다. 라우터 의존을 추가하지 않았다
  [`App.tsx:22`](../../frontend/src/App.tsx#L22)

- 서버가 고른 목적지와 다르면 그리지 않고 그쪽으로 보낸다
  [`useSessionState.ts:179`](../../frontend/src/session/useSessionState.ts#L179)

- 허용 목록에 있는 경로일 때만 `next`를 붙인다
  [`auth.ts:60`](../../frontend/src/content/auth.ts#L60)
