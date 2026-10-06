# BokriLog

Bybit 읽기 전용 연결로 복리 목표 계획을 기록·비교하는 개인 프로젝트다. BMAD 기획 산출물은 완료됐고 Epic 1·2는 닫혔으며 다음 구현은 Epic 3이다. 제품 코드는 `frontend/`(React/Vite 정적 SPA), `backend/`(Spring Boot 최소 실행 기반), `deploy/`(Nginx 동일 출처 구성)로 이뤄져 있다. 절차와 명령의 상세는 `DEVELOPMENT.md`가 소유하고, 이 파일은 세션이 바로 일을 시작하는 데 필요한 규칙과 현재 지점만 담는다.

## 언어

대화와 산출 문서 모두 한국어다. BMAD 중앙 설정(`_bmad/core/config.yaml`)이 `communication_language: Korean`, `document_output_language: Korean`으로 고정되어 있으므로 이 값을 따른다. 브랜치명·커밋 subject·코드 식별자만 영문이다. 코드 주석도 한국어다 — 새로 쓰거나 고치는 주석은 한국어로 쓰고, 기존 영어 주석은 그 파일을 고칠 때 손대는 범위에서만 바꾸며 주석만 바꾸는 일괄 치환은 하지 않는다(Epic 2 회고 AI-10, 2026-10-06 결정).

## 세션 시작 시 확인 순서

1. `git status`와 `git log --oneline -5`로 현재 브랜치가 `develop`이고 깨끗한지 본다. 사용자 체크아웃은 항상 `develop`에 둔다.
2. `_bmad-output/implementation-artifacts/sprint-status.yaml`에서 `in-progress`·`review` 스토리가 있는지 본다. 있으면 그 스토리의 `spec-<n>-<m>-<slug>.md` frontmatter `status`를 함께 확인한다.
3. 아래 `## 현재 진행 지점`과 `_bmad-output/implementation-artifacts/epic-<n>-context.md`(현재 Epic 3은 `epic-3-context.md`)로 다음 착수 스토리를 확정한다.
4. 스토리를 시작하거나 코드를 바꾸는 요청이면 `bmad-build` 스킬로 진행한다. 질문·조사·문서 읽기는 스킬 없이 답한다.

## BMAD 스킬 실행

이 저장소의 작업은 `.claude/skills/bmad-*` 스킬로 진행한다. 각 스킬의 `SKILL.md`는 렌더링 명령 하나만 담고 있다.

- `SKILL.md`가 지시하는 `render_skill.py` 명령을 그대로 한 번 실행하고, 출력된 절대경로 `workflow.md`를 읽어 따른다.
- 렌더링이 실패하면 명령 출력을 보고하고 중단한다. `.claude/skills/` 아래의 워크플로 원본을 직접 읽어 대신 실행하지 않는다.
- `.claude/skills/`(Claude Code)와 `.agents/skills/`(Codex)는 같은 설치본에서 생성된 동일 파일이다. 스킬 파일을 손으로 고치지 않는다. 동작을 바꿀 때는 `_bmad/custom/` 또는 `bmad-customize` 스킬을 사용한다.
- `_bmad/config*.toml`과 모듈 `config.yaml`은 인스톨러가 재생성한다. 직접 수정하지 않는다.

### 스토리·변경 작업의 표준 흐름

`bmad-build`가 다음 순서를 강제한다. 단계를 건너뛰지 않는다.

1. **SPEC 작성** — `_bmad-output/implementation-artifacts/spec-<slug>.md`. 스토리는 `spec-1-3-<slug>.md`처럼 스토리 번호로 시작하고, chore·fix도 SPEC을 쓴다(예: `spec-ci-foundation.md`). SPEC의 `## Intent`·`## Boundaries & Constraints`는 사람이 소유하며 승인 뒤 고치지 않는다.
2. **CHECKPOINT 1(Intent 승인)** — 사용자 승인을 받기 전에는 구현하지 않는다. SPEC 토큰 초과 경고가 나오면 사용자는 보통 `[K] 유지`를, 도구 선택 안이 여럿이면 추천안을 고른다.
3. **구현** — 아래 `## 코드 규칙`을 지킨다. `bmad-build`가 sprint-status를 `in-progress`로 올리고 에픽을 `in-progress`로 든다.
4. **리뷰** — 리뷰 서브에이전트를 병렬로 돌리고 결과를 `patch`·`defer`·`reject`로 분류한다. `defer`는 `deferred-work.md`에 **추가만** 한다. patch 루프와 2차 리뷰의 결과는 SPEC `## Spec Change Log`에 한 줄씩 적고, `review_loop_iteration`은 리뷰 전체를 다시 돌린 2차 리뷰에만 올린다(Epic 2 회고 AI-13, 2026-10-06 결정).
5. **present** — SPEC `status: done`, sprint-status `review`. 여기서 스킬은 끝난다.
6. **PR** — 토픽 브랜치를 push하고 `develop`으로 PR을 연다. `ci-required`가 green이면 `Create a merge commit`으로 머지한다(`gh pr merge --merge`).
7. **done 전환** — 머지 뒤 `chore/sprint-status-<n>-<m>-done` 브랜치에서 sprint-status 한 줄을 `done`으로 바꾸는 docs PR을 따로 올린다(PR #3, #10 패턴). 문서-only 경로라 `changes → ci-required`만 돌고 1분 안에 끝난다.

토픽 브랜치 작업은 사용자 체크아웃을 옮기지 않고 별도 `git worktree`에서 하고, 머지 뒤 worktree와 브랜치를 모두 지운다.

### 커밋·푸시·머지의 자율 범위

- 커밋·푸시는 사용자가 요청할 때만 한다.
- "진행해줘"·"병합하는 데 문제가 없다면 진행해줘"처럼 명시적 진행 지시가 있고 `ci-required`가 green이며 충돌이 없으면 push → PR → 머지까지 한 번에 진행한다. 사용자는 PR 페이지에서 사후 검토한다.
- "확인해줘"류 지시면 PR까지만 열고 머지는 묻는다.
- 실패한 CI를 재시도로 덮지 않는다. 원인을 찾아 고친 커밋을 올린다.

## 코드 규칙

세 디렉터리 공통 불변식이다. 근거는 `ARCHITECTURE-SPINE.md`의 AD-1~AD-15와 각 스토리 SPEC이다.

- **스택 고정.** Java 25 LTS, Spring Boot 4.1.x(현재 4.1.1), Spring Modulith 2.1.x(현재 2.1.1), React 19.2.x, Vite 8.2.x, Nginx 1.30.x, PostgreSQL 18.x(현재 18.6). 허용 계열은 `ARCHITECTURE-SPINE.md` Stack 표가, 실제 버전은 lockfile·wrapper·이미지 태그가 소유한다. 버전이 해결되지 않으면 조용히 낮추지 말고 보고한다.
- **의존 추가는 SPEC이 승인한다.** Actuator·JPA·`spring-security-test`는 아직 없다. 각각을 필요로 하는 스토리의 SPEC이 목적과 함께 승인할 때 추가한다. PostgreSQL·Flyway는 `spec-1-3-identity-ownership.md`가, Spring Security OAuth2 Client·Spring Session JDBC는 `spec-1-4-google-sign-in.md`가 승인해 운영 classpath에, Spring Modulith·Testcontainers는 1.3 SPEC이 승인해 시험 classpath에 들어왔다. `ch.obermuhlner:big-math` 2.3.2는 `spec-2-7-first-plan-publication.md`가 승인해 운영 classpath에 있고, 쓰는 곳은 `calculation` 모듈 하나다(`ModuleBoundaryTest`가 강제한다). 경계 검증 라이브러리를 운영 jar에 싣지 않는다. 프런트엔드 런타임 의존 추가도 같다(개발·시험 전용 devDependency는 예외). 프런트엔드에는 라우터도 인증 SDK도 없다 — 경로 대응은 `App.tsx`의 닫힌 맵이고 로그인은 서버가 302로 처리한다.
- **`identity`가 계정 소유권·삭제 상태의 유일한 권위다.** 다른 패키지는 공개 application API(`AccountLifecycleApi`·`AccountAccessApi`·`AccountDeletionApi`)로만 접근하고 `identity.internal`이나 소유 테이블에 직접 닿지 않는다. `identity`에는 HTTP 표면이 없다. 접근 거부는 `AccessDecision.DENIED` 하나이며 타 사용자·미존재·삭제된 대상이 구분되면 안 된다. 상태는 `ACTIVE → DELETION_IN_PROGRESS → DELETED` 단조 전진뿐이다. `ModuleBoundaryTest`가 경계와 미노출을 강제한다.
- **스키마는 별도 migration 역할이 바꾼다(AD-12).** API 기동은 `Flyway::validate`만 하고 `migrate` 프로필만 실제로 마이그레이션한다(`SchemaValidationConfig`). 새 마이그레이션은 `backend/src/main/resources/db/migration/V<n>__<이름>.sql`로 더하고 이미 적용된 파일은 고치지 않는다.
- **동일 출처.** Nginx는 `/api`만 백엔드로 프록시한다. 나머지는 정적 파일이거나 404이며 어떤 경로도 외부 주소로 프록시하지 않는다. 와일드카드 SPA 폴백은 두지 않는다. 화면이 여는 내부 경로는 허용 목록 location(`^/(onboarding|dashboard|settings|plan-conditions)$`)이 이름으로 열고, 그 집합은 `frontend/src/App.tsx`의 경로 맵과 같아야 한다. 그중 로그인 복귀 목적지는 셋(`/onboarding`·`/dashboard`·`/settings`)뿐이고 `/plan-conditions`는 화면 경로이지만 복귀 목적지가 아니다. 라우트를 더할 때는 아래 `## 스토리가 고정한 계약`의 다섯 곳을 함께 고친다. access log는 쿼리 문자열을 남기지 않는다 — `$request`를 그대로 쓰면 OIDC 콜백의 `code`·`state`가 로그에 실린다.
- **인증 표면은 루트 패키지가 소유한다.** `identity`는 HTTP를 가질 수 없으므로 Google OIDC 콜백·세션 정책·CSRF는 `com.bokrilog`의 `AuthSecurityConfig`·`AuthSessionService`·`AuthFlowHandlers`·`AuthSessionController`·`SessionGuard`가 맡고, 계정 조회·매핑·판정은 `identity` 공개 API만 쓴다. 경로는 `/api/auth/authorize/<provider>`·`/api/auth/callback/<provider>`·`GET /api/auth/session`·`POST /api/auth/logout`·`POST /api/auth/reauthenticate/<provider>`·`GET /api/auth/link`·`POST /api/auth/link/<provider>`·`POST /api/auth/unlink/<provider>`(준비·`/confirm`·`/cancel` 셋) 열이다. `SessionGuard`는 세션 상태 전환을 다루지만 HTTP 표면이 없다 — 상태 설정에 도달하는 사용자 경로를 만들지 않는다(Story 1.5). 검증(PKCE S256, 일회성 `state`·`nonce`, JWS·RS256·JWKS·`iss`·`aud`·`azp`·`exp`·`nonce`)은 Spring Security OAuth2 Client가 하고 손으로 만들지 않는다. 인증 표면은 `@ConditionalOnWebApplication(type = SERVLET)`로 API 프로세스에만 배선한다(`migrate` 역할에는 `HttpSecurity`가 없다).
- **세션.** Spring Session JDBC로 PostgreSQL에 둔다. 브라우저에는 `HttpOnly`·`Secure`·`SameSite=Lax` 불투명 쿠키 하나뿐이고 로그인 성공 시 세션 ID를 회전한다. 세션이 담는 값은 내부 `user_id` 하나이며 ID Token·액세스·리프레시 토큰은 검증 직후 버린다. CSRF 토큰과 동일 출처 `Origin` 검증은 `/api/auth/` 아래의 상태 변경 요청에만 건다 — 전역으로 켜면 Story 1.2·1.3이 고정한 404·405가 403으로 뒤집힌다. 복귀 목적지는 서버가 가진 닫힌 허용 목록(`/onboarding`·`/dashboard`·`/settings`)뿐이다. 수명은 두 축이다 — 유휴 30분과 생성 시각 기준 절대 12시간. 폐기는 세션 행 삭제·`SecurityContext` 정리·쿠키 만료 지시 셋을 함께 만족해야 하며 셋 중 둘만 만족한 상태를 로그아웃 완료로 보고하지 않는다. 클라이언트가 CSRF 토큰을 얻는 경로는 세션 확인 응답의 필드 하나뿐이다(쿠키 저장소를 쓰지 않으므로 브라우저 쿠키가 하나로 유지된다). 반복 로그아웃은 403이고 그것이 계약이다.
- **오류 응답 한 가지.** `{"code":…,"message":…,"correlationId":…}`. Spring과 Nginx가 같은 모양을 돌려주고 `code`는 안정적인 machine code다. 내부 경로·스택·프레임워크 기본 오류 본문·환경 변수 이름은 응답과 로그 어디에도 남기지 않는다.
- **보안·캐시 헤더는 Nginx만.** CSP를 비롯한 보안 응답 헤더와 `Cache-Control`은 `deploy/nginx/bokrilog.conf`가 유일한 정의다. `<meta>` CSP를 쓰지 않고 Spring Security의 기본 헤더 writer도 끈다(`AuthSecurityConfig`). 켜 두면 같은 헤더가 두 번 나가고 헤더를 전문 비교하는 검증이 먼저 깨진다. 캐시 값은 `$status` → `$request_path` 두 단계 `map`이 갈라 내고 헤더는 상위 `add_header` 블록 **한 곳**이 낸다 — 하위 location에 `add_header`를 하나라도 두면 그 응답에서 상위 헤더 다섯 개가 전부 사라진다. 성공한 `/assets/` 해시 자산만 `immutable`이고 문서와 모든 오류 응답은 `no-store`다. 백엔드에 전역 `no-store`를 켜지 않는다 — `GET /api/status`의 빈 `Cache-Control`을 시험이 고정했다.
- **브라우저 경계.** 외부 폰트·CDN·분석·광고 SDK 금지. `localStorage`·`sessionStorage`·쿠키·IndexedDB 쓰기 금지. 진입 화면은 문서·번들 외 요청 0건이며 시험이 고정한다. 인증 토큰과 금융 원본은 브라우저 영속 저장소에 두지 않는다.
- **디자인 토큰.** `frontend/src/styles/tokens.css`가 정본이며 `DESIGN.md` frontmatter의 평면 이름을 그대로 옮긴 것이다. 색·타이포그래피·모서리·간격 리터럴을 컴포넌트에 직접 쓰지 않는다. stylelint가 강제하고, 인라인 예외는 `-- 이유`가 붙은 disable 주석만 허용한다. 없는 토큰을 지어내지 않는다.
- **화면 카피.** `frontend/src/content/`가 소유하고 화면과 시험이 같은 문자열을 참조한다. 모든 핵심 화면에 `복리 계획 비교`와 "투자성과·계좌 수익률·투자 추천이 아닙니다" 고지를 인접 배치한다. 실시간·정확 보장, 수익 축하, 손실 공포, 스트릭·배지 표현은 금지다.
- **금융 계산.** `BigDecimal`·`MathContext(34, HALF_EVEN)`만 쓴다. `double`·`float`를 금융 계산에 쓰지 않는다. 표시 반올림은 HALF_UP 2자리 한 번만이다. 상세는 `ERD-1.0.0` §3 `CALC-1.0`.
- **자격정보.** `application.yml`·compose·시험 fixture에 자격정보 모양 리터럴을 넣지 않는다. 값이 가짜여도 gitleaks가 잡는다. 탐지가 나면 규칙을 완화하지 말고 리터럴을 없앤다. 예외는 `.gitleaksignore` 한 줄과 근거 주석으로만 둔다.
- **줄바꿈.** 신규 파일은 LF다. `.gitattributes`가 결정하고 `*.bat`·`*.cmd`만 CRLF다.
- **셸 스크립트.** `set -o pipefail` 아래에서 `생산자 | grep -q`를 조건에 쓰지 않는다. grep이 첫 매치에서 끝나 생산자가 SIGPIPE를 받고 러너에서만 실패한다(PR #9 첫 실행 실패 원인). `grep … >/dev/null`로 입력 끝까지 읽는다. bash 3.2 호환, GNU 전용 플래그(`sed -i`, `grep -P`, `readlink -f`) 금지.
- **판정 논리는 스크립트로.** CI 판정은 `.github/scripts/*.sh`가 소유하고 `test-ci-scripts.sh`가 계약을 단정한다. `ci.yml`의 인라인 `run`에 판정을 넣지 않는다. `ci.yml`을 바꾸면 그 시험도 함께 갱신한다.

## 프런트엔드 실행

`frontend/`는 React 19.2.x + Vite 8.2.x + TypeScript strict 정적 SPA다. 백엔드·DB·제공자 자격정보 없이 단독으로 빌드·실행·시험이 통과한다. Node는 `frontend/.nvmrc`의 24다. Node 25에서는 Vitest가 내장 `localStorage` 때문에 실패하므로 버전 관리자로 24를 맞춘다. 명령은 저장소 루트에서 `--prefix frontend`로 실행한다.

```bash
npm --prefix frontend ci
npm --prefix frontend run typecheck
npm --prefix frontend run lint
npm --prefix frontend run lint:css
npm --prefix frontend run format:check
npm --prefix frontend run build
npm --prefix frontend run test
npm --prefix frontend run test:e2e
```

`test:e2e`는 `vite build` 뒤 Playwright가 산출물을 `preview`로 띄운다. 빌드가 이미 끝난 CI에서는 `test:e2e:run`을 쓴다. 처음 한 번은 `npm --prefix frontend exec -- playwright install chromium`이 필요하다. `PLAYWRIGHT_BASE_URL`을 주면 자체 `preview`를 띄우지 않고 그 origin을 검증하며, `e2e/same-origin-csp.spec.ts`와 `e2e/auth-entry.spec.ts`의 성공 왕복 시험은 이때만 실행된다. 자동 정리는 `npm --prefix frontend run lint -- --fix`, `npm --prefix frontend run lint:css -- --fix`, `npm --prefix frontend run format`이다. 상세는 `DEVELOPMENT.md`의 `## 프런트엔드`.

커밋과 함께 성립해야 하는 효과(초점 이동·`replaceLocation`·타이머·리스너, 그 효과가 읽는 latest-ref)는 `useLayoutEffect`, 요청 시작과 `inert` 배경으로 돌려주는 대화상자의 초점 복귀는 근거 주석을 단 passive `useEffect`이며, 부하에서만 드러나는 시험 결함은 `bash frontend/scripts/stress-test.sh`(CPU 수 × 2 부하, 12회)로 재현·확인한다 — 상세는 `DEVELOPMENT.md`의 `### 구조와 규칙`.

## 백엔드·동일 출처 실행

`backend/`는 Java 25 + Spring Boot 4.1.1 + Gradle wrapper이고 모듈 경계는 Spring Modulith 2.1.1이 시험에서 검증한다(운영 classpath에는 없다). Story 1.3부터 PostgreSQL 18을 쓰고 Story 1.4부터 Spring Security OAuth2 Client·Spring Session JDBC가 있다. HTTP 엔드포인트는 `GET /api/status`, `GET /api/auth/session`, `POST /api/auth/logout`, `POST /api/auth/reauthenticate/<provider>`, 그리고 Spring Security 필터가 받는 `/api/auth/authorize/<provider>`·`/api/auth/callback/<provider>`다. Epic 2가 그 위에 `/api/connections/**`(Bybit 연결), `/api/snapshots/first/**`(첫 스냅샷·수집·판정·환산·계획 발행), `/api/plans/conditions/**`(계획 조건), `GET /api/dashboard`(공식 대시보드 — Story 2.8이 2.7의 `GET /api/plans/active`를 대체했고 옛 경로는 404다), `GET /api/dashboard/evidence`(현재 공식 결과의 계산 근거 — Story 2.9), `GET /api/dashboard/adjustments`(현재 공식 계산에 반영된 외부 자금흐름 조정 이력 — Story 2.10), `POST /api/dashboard/sync`(사용자 시점 증분 동기화 접수 — Story 2.11), `POST /api/dashboard/verification`(정밀 검증·기존 계획 계속 접수 — Story 2.12), `POST /api/dashboard/plan-end`와 그 `/confirm`·`/cancel`(활성 계획 중도 종료의 준비·확정·취소 — Story 2.13)를 더했다. Story 2.14는 새 경로 없이 `GET /api/dashboard`에 `COMPLETED` 상태를 더했다. Story 2.16이 `POST /api/connections/bybit/revocation`과 그 `/confirm`·`/cancel`(Bybit 연결 해제의 준비·확정·취소)을 더했다. 상태·인증 컨트롤러는 루트 패키지 `com.bokrilog`에 있고 모듈 패키지는 `identity`·`source`·`portfolio`·`operations`·`sync`·`plan`·`calculation` 일곱이다(`calculation`은 DB·Spring·시계·다른 모듈에 의존하지 않는 순수 모듈이다). `ARCHITECTURE-SPINE.md`가 적은 여덟 번째 모듈 `engagement`(광고 동의·빈도 제한)는 Epic 4가 만들며 아직 패키지가 없다. Story 1.9부터 루트 패키지가 자기 테이블 하나(`action_grant`, `V3`)를 소유한다 — 그전까지 모든 테이블은 `identity` 소유였다. 컴파일 경고는 `-Werror`로 실패다.

제공자 자격정보는 여전히 필요 없다. 판정은 **제공자마다 독립**이다 — `BOKRILOG_PUBLIC_BASE_URL`과 그 제공자의 `BOKRILOG_<제공자>_CLIENT_ID`·`_CLIENT_SECRET` 중 하나라도 비면 그 등록만 만들어지지 않고 그 제공자의 인증 시작이 공통 오류 계약으로 fail-closed이며, 다른 제공자의 로그인은 살아 있다. 어느 제공자도 구성되지 않으면 `oauth2Login` DSL 자체를 배선하지 않는다. 서로 다른 두 등록이 같은 issuer로 해석되면 기동을 거부한다 — 조회 키가 `(issuer, subject)`라서 override 하나로 계정 병합이 열리기 때문이다(`DevOidcStubProfileTest`·`AuthNaverSignInTest`가 이 계약을 지킨다). 정상 흐름의 결정성은 `dev-oidc` 프로필에서만 사는 local OIDC 스텁이 만든다. 스텁 논리는 `DevOidcStub`이 소유하고 표면은 두 곳이다 — 개발·CI 스택은 `DevOidcStubConfig`가 `/api/dev-oidc/<제공자>/**`에 제공자마다 인스턴스 하나로 올리고(라우터 빈은 하나다)(브라우저와 백엔드가 같은 origin에서 도달해야 성공 경로를 브라우저 층에서 검증할 수 있다), 백엔드 시험은 `OidcStubConfiguration`이 루프백 HTTP 서버에 올린다. 스텁을 두 번 만들지 않는다. 그 프로필이 어느 제공자든 실제 등록과 함께 활성이면 기동을 거부하고, 실제 제공자 왕복은 Epic 1의 **실연동 미검증** 게이트가 소유한다. 백엔드가 자기 스텁에 닿는 주소는 `BOKRILOG_DEV_OIDC_INTERNAL_BASE_URL`이며 브라우저가 보는 origin과 다른 값이다.

리다이렉트의 `Location`은 origin-상대 경로다(`server.tomcat.use-relative-redirects: true`). 기본값에서는 Tomcat이 요청 `Host`로 절대 URI를 만들고 Nginx가 포트를 잃은 값을 넘기므로 로그인 뒤의 이동이 80번 포트로 끊긴다.

**백엔드 시험은 Docker를 전제한다.** Testcontainers가 실제 `postgres:18.6-alpine`을 JVM당 하나 띄운다. 데몬이 없으면 `build`가 컨테이너 시작 실패로 멈추며, 인메모리 데이터베이스로 대체하지 않는다.

JDK 25가 필요하고 toolchain 자동 프로비저닝은 꺼져 있다. macOS는 `brew install openjdk@25` 뒤 사용자 홈에 최초 1회 심볼릭 링크를 걸면 `/usr/libexec/java_home -v 25`와 Gradle이 함께 찾는다(sudo 불필요). Windows는 `winget install EclipseAdoptium.Temurin.25.JDK`로 끝난다. PATH의 `java -version`이 17로 남아 있어도 정상이다. `JAVA_HOME`을 셸에서 바꿔 두지 않는다.

```bash
mkdir -p ~/Library/Java/JavaVirtualMachines
ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk ~/Library/Java/JavaVirtualMachines/openjdk-25.jdk
backend/gradlew --project-dir backend build
```

`deploy/`는 Nginx가 `frontend/dist/`를 origin 루트에서 제공하고 `/api`만 백엔드로 프록시하는 개발·CI 공용 compose 구성이다. production 프로필이 아니다. 서비스는 넷(`postgres`·`migrate`·`backend`·`nginx`)이고 `migrate`는 `profiles`에 가려 명시 실행만 된다. 두 산출물을 먼저 빌드한 뒤 검증 스크립트 하나로 I/O 계약 전체를 확인한다. Docker Desktop이 떠 있어야 한다. 바깥에 열리는 포트는 Nginx 하나(`127.0.0.1:18080`)이며 데이터베이스 포트도 게시하지 않는다.

```bash
deploy/compose/verify-same-origin.sh
```

스크립트가 `up` 앞에서 마이그레이션을 1회 실행한다. compose를 직접 다룰 때는 그 순서를 손으로 지켜야 한다. 기본 프로필의 백엔드 기동은 schema 검증만 하므로 빈 데이터베이스에 `up`부터 하면 백엔드가 healthy가 되지 못한다.

```bash
docker compose --file deploy/compose/compose.yaml --profile migrate run --rm --build migrate
```

개발·CI compose에는 비밀번호가 없다. `POSTGRES_HOST_AUTH_METHOD: trust`를 쓰고 포트를 게시하지 않는다. production 자격정보는 Story 3.8이 소유한다.

종료 코드는 0(통과), 1(단정 실패), 2(준비 실패: 산출물이나 docker 없음)다. `--keep-stack`으로 스택을 남기면 같은 origin에 Playwright 회귀를 돌릴 수 있다. 상세는 `DEVELOPMENT.md`의 `## 백엔드`·`## 동일 출처 실행`.

## CI

`.github/workflows/ci.yml`이 유일한 workflow다. 구조는 `changes → frontend-quality·backend-quality → same-origin-integration → ci-required`이고 필수 검사 이름은 `ci-required`로 고정이다. GitHub ruleset `protect-integration-branches`가 `develop`·`main`에 PR 필수, merge commit만 허용, `ci-required` 필수(strict), 삭제·force push 금지를 건다.

- 첫 GitHub 실측(2026-09-06, PR #9): wall-clock 4분 3초. 문서-only 경로(`_bmad-output/**`, 최상위 `*.md`)는 `changes`와 `ci-required`만 돌아 1분 안에 끝난다.
- artifact 이름에 `run_attempt`가 들어가 `Re-run failed jobs`는 쓸 수 없다. 재실행은 `Re-run all jobs`다.
- 통합 job timeout 17분은 CI-PLAN §8의 시작값 5분과 다르다. `backend-quality`도 Story 1.3에서 10분 → 15분으로 올렸다(Testcontainers 콜드 실행). Story 2.11이 다시 `backend-quality`를 15 → 20분, 통합 job을 15 → 17분(회귀 단계 4 → 6분, 주입 origin Playwright `globalTimeout` 3 → 5분)으로 올렸다 — PR #49 실측이 백엔드 11분 5초·회귀 2분 30초였고 PR #52의 백엔드가 13분 0초였다. 그 뒤 dispatch 간격 설정화(`spec-backend-test-speed.md`)로 로컬 백엔드 `clean build`가 13분 2초 → 3분 13초가 됐고 timeout 값은 그대로다(상세는 `DEVELOPMENT.md`). 두 값 모두 실측이 몇 회 더 쌓인 뒤 CI-PLAN 새 버전으로 보정하기로 했고 아직 발행 전이다.
- Testcontainers는 러너의 docker 데몬을 그대로 쓴다. `backend-quality`에 서비스 컨테이너도, docker를 설치하는 action도 더하지 않았다. 시험은 기존 `Gradle 빌드` 단계 안에서 돈다.
- 로컬에서 CI 계약을 미리 확인하는 명령은 `bash .github/scripts/test-ci-scripts.sh`다.

## Python 도구 실행

BMAD Python 스크립트는 저장소 루트에서 `uv run --managed-python --no-cache`로 실행한다. 이 명령은 BMAD 기획 도구 전용이며 제품 런타임과 무관하다. Windows에서 `python3`는 존재하지 않으므로 직접 호출하지 않는다. 셸 스크립트는 POSIX 셸을 요구하고 Windows에서는 Git Bash로 실행한다. 환경이 의심되면 먼저 점검한다.

```bash
./scripts/check-bmad-env.sh
```

## 산출물 위치와 권위

- `_bmad-output/planning-artifacts/` — `epics.md`(5 에픽·45 스토리 승인), `EPIC-IMPLEMENTATION-READINESS.md`(`ERD-1.0.0`), `architecture/architecture-BokriLog-2026-08-24/`(`ARCHITECTURE-SPINE.md`, `PORTFOLIO-SOLUTION-DESIGN.md`, `CI-PLAN.md`), `prds/prd-BokriLog-2026-08-23/prd.md`, `ux-designs/ux-BokriLog-2026-08-23/`(`DESIGN.md`, `EXPERIENCE.md`), `briefs/`
- `_bmad-output/implementation-artifacts/` — `sprint-status.yaml`, `epic-<n>-context.md`(1~3), `epic-<n>-retro-<날짜>.md`, `spec-*.md`, `deferred-work.md`, `deferred-work-resolutions.md`
- `_bmad/render/` — 스킬 렌더링 스냅샷. 생성물이며 커밋하지 않는다.

충돌 시 우선순위는 PRD의 제품 범위 → 아키텍처의 불변식 → `ERD-1.0.0`의 하위 구현 계약 순이다. 결정을 바꿀 때는 기존 문서를 덮어쓰지 않고 새 버전으로 발행한다. `sprint-status.yaml`의 스토리 키 앞부분(`1-3`)이 브랜치 번호이고, 스토리 본문은 `epics.md`의 해당 `### Story` 절이다.

`deferred-work.md`는 `bmad-build`가 `defer` 판정마다 항목을 **추가만** 하는 로그다. 기존 항목을 고치거나 지우지 않는다. 해소 여부는 `deferred-work-resolutions.md`에 기록하고 상태 요약 표를 함께 맞춘다. 검사가 실제로 성공한 뒤에만 해소로 적는다.

## 현재 진행 지점

기획은 완료됐고 구현 계약은 `ERD-1.0.0`이 권위 기준이다. **Epic 1(11개)은 닫혔고 회고도 끝났다**(`epic-1-retro-2026-09-25.md`, 판정 `accepted-with-open-items`, 액션 12건은 `sprint-status.yaml`의 `action_items`가 소유). **Epic 2(16개)도 모두 `develop`에 머지됐고** M2-2(2.7)·M2-3(2.12)·M2-4(2.13~2.16) 검증 지점이 닫혔다. Epic 2 회고도 끝났다(`epic-2-retro-2026-10-05.md`, 판정 `accepted-with-open-items`, 액션 13건). **다음은 회고 액션 AI-8(민감 command 공통 부품 정리, `spec-sensitive-command-commons.md`)을 chore로 먼저 끝내고, 그 뒤 Epic 3 Story 3.1(보호된 운영자 표면과 추가 전용 감사 원장)이다.** 2026-10-06 결정: Epic 1 액션 item-1(재인증 시각 연쇄 승인)은 같은 코드를 만지므로 AI-8 범위에 넣고, item-7(확인 hash 판별력)은 운영 envelope가 실제 확인 문구를 정의하는 3.1 SPEC의 입력으로 넘긴다. `RECONNECT-1.0`(AI-1)은 Epic 3 완료 뒤 Epic 2 보완 스토리로 배치하고 그때까지 해제 사용자의 새 연결 영구 차단은 accepted deviation이다. 실연동 게이트는 둘이며 둘 다 별도 출시 게이트가 소유한다 — Epic 1의 세 제공자 왕복, Epic 2의 Bybit 연결 정책·실연동(실제 검증 계정과 regional reserved IP 필요).

스토리마다 무엇을 도입했고 리뷰가 무엇을 잡았는지는 아래 표의 SPEC이 소유한다. 그 코드를 건드릴 때 해당 SPEC의 `## Design Notes`와 "구현에서 확정한 것"을 읽는다.

| 스토리 | 상태 | 근거 |
|---|---|---|
| 1.1 진입 화면 | done | `spec-1-1-entry-screen.md`, PR #3 |
| 1.2 동일 출처 셸 | done | `spec-1-2-same-origin-shell.md`, PR #9 머지 커밋 `d9a1fc2`, PR #10 |
| 1.3 소유권·삭제 상태 | done | `spec-1-3-identity-ownership.md`, PR #12 머지 커밋 `89b8948` |
| 1.4 Google 로그인 | done | `spec-1-4-google-sign-in.md`, PR #14 머지 커밋 `2f300f6` |
| 1.5 로그아웃·세션 만료 | done | `spec-1-5-logout-session-expiry.md`, PR #17 머지 커밋 `5b6ae01` |
| 1.6 네이버 로그인 | done | `spec-1-6-naver-sign-in.md`, PR #20 머지 커밋 `7f0f816` |
| 1.7 카카오 로그인 | done | `spec-1-7-kakao-sign-in.md`, PR #22 머지 커밋 `c60729f` |
| 1.8 재인증·세션 회전 | done | `spec-1-8-reauthentication-session-rotation.md`, PR #24 머지 커밋 `b2a4d5d` |
| 1.9 재인증 허가(5분·일회성) | done | `spec-1-9-reauthentication-action-grant.md`, PR #26 머지 커밋 `3798010` |
| 1.10 로그인 수단 연결 | done | `spec-1-10-link-sign-in-method.md`, PR #28 머지 커밋 `97c2d3a` |
| 1.11 로그인 수단 해제 | done | `spec-1-11-unlink-sign-in-method.md`, PR #30 머지 커밋 `59415e7` |
| Epic 1 | **완료** | 11개 스토리 전부 done |
| Epic 1 회고 | done | `epic-1-retro-2026-09-25.md`, 판정 `accepted-with-open-items`, 액션 12건 |
| 2.1 Bybit 연결 검증·저장 | done | `spec-2-1-bybit-connection.md`, PR #34 머지 커밋 `ad16b7a` |
| 2.2 첫 현재가치 스냅샷 | done | `spec-2-2-first-equity-snapshot.md`, PR #36 머지 커밋 `f1df593d0dd233bc72e54e4231cad8a2aacf9e43` |
| 2.3 계획 조건 미리보기 | done | `spec-2-3-plan-condition-preview.md`, PR #36 머지 커밋 `f1df593d0dd233bc72e54e4231cad8a2aacf9e43` |
| 2.4 현재가치·원천 사건 완전 수집 | done | `spec-2-4-bybit-source-event-collection.md`, PR #38 머지 커밋 `0883c24` |
| 2.5 외부 자금흐름 판정 | done | `spec-2-5-cashflow-classification.md`, PR #39 머지 커밋 `6446332` |
| 2.6 사건 시점 USD 환산 | done | `spec-2-6-cashflow-usd-conversion.md`, PR #41 머지 커밋 `d3b8bbe` |
| 2.7 최초 계획 계산·원자 발행 | done | `spec-2-7-first-plan-publication.md`, PR #43 머지 커밋 `568089e` |
| 2.8 공식 대시보드 | done | `spec-2-8-official-dashboard.md`, PR #45 머지 커밋 `ff0d915` |
| 2.9 계산 근거 | done | `spec-2-9-calculation-evidence.md`, PR #47 머지 커밋 `5f770f8` |
| 2.10 조정 이력 | done | `spec-2-10-cashflow-adjustment-history.md`, PR #49 머지 커밋 `752cb1a` |
| 2.11 증분 동기화 | done | `spec-2-11-incremental-plan-sync.md`, PR #52 머지 커밋 `de6290c` |
| 2.12 정밀 재검증 | done | `spec-2-12-precision-reverification.md`, PR #54 머지 커밋 `4551b5b` |
| 2.13 계획 중도 종료 | done | `spec-2-13-plan-early-end.md`, PR #57 머지 커밋 `436f1c2` |
| 2.14 계획 자연 만료·완료 결과 | done | `spec-2-14-plan-natural-completion.md`, PR #60 머지 커밋 `1c92f5f` |
| 2.15 새 정상 기준·후속 계획 | done | `spec-2-15-successor-plan.md`, PR #63 머지 커밋 `816289366f57f6e3d067ed59359d7399c02b433b` |
| 2.16 연결 해제·기록 보존 | done | `spec-2-16-bybit-connection-revocation.md`, PR #65 머지 커밋 `9aae04d` |
| Epic 2 | **완료** | 16개 스토리 전부 done |
| Epic 2 회고 | done | `epic-2-retro-2026-10-05.md`, 판정 `accepted-with-open-items`, 액션 13건 |

## 스토리가 고정한 계약

여러 스토리에 걸쳐 지켜야 하는 것만 한 줄씩 둔다. 괄호는 근거 SPEC의 스토리 번호다.

**세션·인증 (Epic 1)**
- 반복 로그아웃은 403이 계약이다. 표시(`reauth_required`)된 세션에만 `reauthRequired`와 CSRF 토큰이 실리고, 미인증·폐기 응답 본문은 글자까지 같다. 표시된 세션도 스스로 로그아웃할 수 있다(1.5·1.8).
- 만료의 결과는 이동 하나이고 만료 문장의 출처는 주소 표지(`noticeKindOf`) 하나다 — 제자리 렌더와 이동을 함께 내면 브라우저에서 경합한다(1.5).
- 재인증은 두 번째 인증 경로가 아니라 같은 `oauth2Login` 왕복이다. 인증된 세션에 도착한 무장 없는 콜백은 검증 앞에서 접는다. 복귀 목적지의 출처는 세션 하나이며 클라이언트는 목적지를 보내지 않는다(1.8).
- 최근 인증은 공표된 계약만 쓴다: 공통 `max_age=300`, Google은 essential `auth_time`, 카카오는 `prompt=login`, 네이버는 추가 수단이 없어 실연동에서 fail-closed될 수 있다(1.8).
- 카카오 `sub`은 pairwise라 앱 재생성·REST 키 교체 시 기존 매핑이 고아가 되고, Client Secret은 콘솔에서 켜야 등록이 생긴다(1.7, 상세는 `DEVELOPMENT.md`). 브랜드 값은 제공자가 공표한 수치만 토큰이 된다(1.6·1.7).
- 일회성 허가(`action_grant`): 소비는 다섯 귀속(user·session·command·target·확인 hash)과 미소비·미만료를 함께 보는 조건부 UPDATE 한 문장이고, 소비와 업무 변경은 같은 트랜잭션이다. 시각의 권위는 PostgreSQL, 멱등의 권위는 `(user_id, operation_id)` 부분 유일 색인이다(1.9).
- 허가의 귀속 세션 id는 흐름 record가 들고 있던 값을 쓴다 — OIDC 콜백이 세션을 반드시 회전시키기 때문이다. 콜백의 등록 id가 연결·재인증 분기를 가른다(1.10).
- 마지막 로그인 수단 보호는 생명주기 행 잠금과 DELETE 술어 둘 다로 지킨다. 해제 확정 뒤 현재 세션 외 그 사용자의 세션을 폐기하고 현재 세션만 회전시킨다(1.11).
- 설정 화면의 흐름들(연결·본인 확인·해제)은 게이트를 통과한 뒤에만 서로의 무장을 버린다 — 거절되는 시작 요청은 아무것도 버리지 않는다. 앞선 허가 무효화는 참조 단위다(1.11·2.13).
- 밀어내기는 두 랜딩 화면(`/onboarding`·`/dashboard`) 사이에서만 일어나고 `/settings`는 밀리지 않는다(1.10).
- 인가는 새 접두사에만 건다 — 전역 `authenticated()`는 1.2가 고정한 404·405를 뒤집는다. 인가와 CSRF·`Origin` 판정은 같은 술어를 부른다(2.1).

**경로·화면 경계**
- 화면 경로는 넷(`/onboarding`·`/dashboard`·`/settings`·`/plan-conditions`)이고 `App.tsx` 경로 맵과 Nginx 허용 목록이 같은 집합이어야 한다. 그중 복귀 목적지는 셋(`/plan-conditions` 제외)이고 서버 `ALLOWED_DESTINATIONS`, 세션 목적지 맵, 복귀 목적지 집합이 같은 집합이어야 한다 — 합쳐 **다섯 곳**이다. 타입 검사가 잡는 것은 하나뿐이다. 근거·조정 이력·종료·완료는 새 경로가 아니라 대시보드 패널 안에서 연다(2.9~2.14).
- CSP는 넓히지 않는다. 폼은 `fetch`로 보내고 `form-action 'none'`을 유지한다(2.1).
- 화면은 대시보드 응답 하나를 한 단위로 교체한다. 늦은 응답은 버리고, 버전 역행·충돌은 교체하지 않고, 실패한 다시 불러오기는 마지막 정상 결과를 유지하며, 401·403은 값을 메모리에서 지운다. 같은 계획의 종단(중도 종료·완료) 뒤 활성 묶음과 새 계획 시작 뒤 이전 계획 응답의 역행은 거절한다(2.8·2.13~2.15).
- 초점은 사용자가 누른 조작의 결과에만 옮긴다. 자동 트리거의 완료는 status 문장 한 번이다. 화면당 live region은 하나이고 대시보드 status는 `aria-atomic="false"`다(2.9·2.11).

**Bybit·수집·계산 (Epic 2)**
- 모듈 레이아웃: 공개 API는 모듈 루트, 구현은 package-private `internal`, DB 접근은 `JdbcClient`. sync는 plan에 의존할 수 없으므로 plan이 필요한 일은 sync가 소유하고 plan이 구현하는 `PlanPublicationPort`로 부른다(2.1·2.7).
- 자격정보는 AES-256-GCM(봉할 때마다 새 96비트 nonce, AAD `user_id/connection_id/credential_id/key_version/algorithm_version`)이다. 외부 호출은 트랜잭션 밖에서 끝내고, 구성이 비면 제공자를 두드리기 전에 422로 닫는다(2.1).
- Bybit permission 허용 목록과 `retCode` 표는 의도적으로 비어 있다 — 실연동 스파이크가 채운다. 실제 rate limit·IP 거부는 200 + `retCode`로 온다(2.1). HTTP 403은 IP 차단 회로, `retCode` `10006`은 재시도 대상이다(2.12).
- 작업은 lease·heartbeat·fencing과 짧은 원자 publish를 쓰고, `tc`를 한 번 고정해 DB 시각 ±60초 안에서만 믿는다. 30일 장기 미동기화 판정의 시계는 admission의 DB 시각이다(2.4·2.11).
- 환산은 `USD`·`USDT`만 직접이고 나머지는 spot 종료 1분봉 close를 `ESTIMATED`로 쓴다. 미종료 candle은 fallback 없이 보류다. 공유 공개 증거(`sync_market_*`)는 사용자 식별자가 없고 삭제 뒤에도 남는다(2.6).
- 계산은 순수 `calculation` 모듈이 한다. `periods`는 정확한 분수를 누적한 뒤 한 번만 반올림한다. `Target <= 0`이어도 발행하고 목표 기반 비율만 계산 불가다. 범위 초과는 발행 전체를 거절한다. `계산 불가`는 계산 성격을 덮지 않는다(2.7).
- 공식 발행은 sync가 소유한 outer 트랜잭션 하나이고 잠금 순서는 계획 advisory → 생명주기 행 → 작업 행이다. portfolio 포인터가 계산 버전의 snapshot을 가리켜야 `READY`다(AD-23). 제한 판정 시각은 `statement_timestamp()`다(2.7·2.8·2.11).
- 대시보드 읽기는 읽기 전용 `REPEATABLE READ` 한 묶음이고 계정 접근을 그 앞뒤로 본다. 세 신뢰 축은 서버가 판정하고 화면은 닫힌 맵으로 카피에 잇는다(모르는 코드는 안전한 카피). 현재 근거·조정 이력은 참조(`planId`·`pointerVersion`·`calculationId`)가 현재 포인터와 같을 때만 200이다. 명시적 과거 조회는 소유 종단 계획의 고정 묶음만 읽고, 불일치는 모두 같은 404, 계보가 어긋나면 503이다(2.8~2.10·2.15).
- 모든 증분 발행은 직전 버전과의 흐름 차이(`sync_plan_calculation_change`)를 같은 트랜잭션에 쓴다. 지속성 검증 검토는 그 기준 계산 동안 끈적이며 작업을 다시 만들지 않는다 — 풀 수 없는 재수집 루프를 막는 규칙이고 완료 계획에도 적용된다(2.12·2.14).
- 계획 상태는 `ACTIVE → ENDED_EARLY | COMPLETED` 단조이고 활성 포인터는 부분 유일 색인 `WHERE status='ACTIVE'`다. 종료·완료 사실은 별도 표(`plan_end`·`plan_completion`)에 두고 상태와 복합 FK로 짝짓는다. 종단 뒤 새 준비가 없으면 계획 조건 경로는 `PLAN_ENDED`이고, 후속 계획은 종단 이후 요청·완료·측정된 양수 정상 기준에 확인→intent→발행을 고정한다. 이전 흐름은 옮기지 않고 새 `t0 < ti`만 반영한다(2.13~2.15).
- 연결 해제는 연결 행을 지우지 않는다 — 자격정보 행 삭제·`ACTIVE → REVOKED`·membership 종료·진행 중 작업 종단을 허가 소비와 같은 트랜잭션 하나로 커밋하고, 기존 `snapshotConnection` 재검사가 늦은 worker를 막는다. 해제 시각은 검증·membership 시작보다 늦게 단조다. 해제된 사용자의 새 연결은 `RECONNECT-1.0` 구현 전까지 단일 거부이며 운영 제한은 해제를 막지 않는다(2.16).
- 자연 만료는 사용자 시점의 동기화 admission이 전환한다(백그라운드 없음, `GET`은 쓰지 않음). 첫 종료 후 관측은 한 번 고정하고 교체하지 않으며, 관측 시각은 ms로 잘라 저장·판정한다(2.14).

**시험·CI**
- 순차 시험 중에도 scheduler·virtual worker는 실행된다. DB 초기화는 public 전체 테이블의 NOWAIT 잠금을 확보한 트랜잭션에서 하며, 경합이면 부분 잠금을 모두 롤백한 뒤 기다린다. 정리 시작 뒤 오류는 재시도하지 않는다(PR #63).
- 시험의 멱등 키·토큰 모양 값은 리터럴로 쓰지 않는다. gitleaks `generic-api-key`가 잡고, PR 커밋 범위가 스캔되므로 뒤 커밋으로는 지워지지 않는다(PR #60).
- `inert` 배경으로 돌려주는 대화상자의 초점 복귀만 passive 정리이고 나머지 초점 이동은 layout effect다(PR #57·#58, `DEVELOPMENT.md`).

## 미룬 결정

`deferred-work.md`가 항목을 추가만 하는 로그(현재 103건)이고 판정은 `deferred-work-resolutions.md`의 상태 요약 표가 소유한다(해소 16·부분 해소 4·미해소 31·미판정 52). 판정이 멈춰 있는 것 자체가 Epic 1 회고 액션 8번이고 Epic 2 회고 AI-6이 같은 일을 다시 요구한다. 회고가 실연동 게이트 전에 닫으라고 남긴 둘 — 재인증 시각을 지우는 코드가 없어 본인 확인 1회가 5분 안에서 민감 command 여러 건을 연쇄 승인하는 것(AI-8 범위), `onFailure`가 무장을 무조건 버리는 것 — 은 아직 열려 있다.

## 브랜치·커밋

git flow를 따른다. `main`은 릴리스만 받고 직접 커밋하지 않는다. `develop`이 통합 브랜치이며 모든 토픽 브랜치는 `develop`에서 분기한다.

병합은 GitHub PR의 `Create a merge commit`으로만 한다. `develop`·`main`에 직접 push하지 않는다. `Squash and merge`·`Rebase and merge`는 토픽 브랜치 경계를 지우므로 쓰지 않으며 ruleset도 막는다. 병합 후 토픽 브랜치는 삭제한다.

- `feature/<에픽>-<스토리>-<slug>` — 스토리 구현. 예: `feature/1-3-identity-ownership`
- `chore/<slug>` — 도구·환경·저장소 정비, sprint-status `done` 전환
- `docs/<slug>` — 기획 산출물·문서
- `fix/<slug>` — `develop` 위 결함 수정
- `hotfix/<slug>`, `release/<version>` — 릴리스 이후·릴리스 준비에만 사용

slug는 짧은 영문 소문자 kebab-case다. 한글 브랜치명과 도구 이름(`claude` 등) 접두사는 쓰지 않는다. 커밋 메시지는 Conventional Commits의 영문 소문자 subject를 따르고 유형은 `feat` `fix` `chore` `docs` `refactor` `test`다.

## 주의

- BMAD 인스톨러를 다시 실행하면 `_bmad/scripts/`의 로컬 수정(UTF-8 stdout)이 업스트림 원본으로 덮어쓰인다. 절차와 복구 방법은 `DEVELOPMENT.md`의 `## 에이전트 타깃`에 있다.
- 제품 기술 스택은 `ARCHITECTURE-SPINE.md`가 권위 기준이다(Java/Spring/PostgreSQL, React/Vite). 이 저장소의 Python 환경은 기획 도구 전용이며 제품 런타임과 무관하다.
- `.idea/`, `.claude/settings.local.json`, `compose.override.yaml`, `.env*`는 기기별 파일이라 커밋하지 않는다. 저장소가 소유하는 compose 구성은 `deploy/compose/compose.yaml` 하나다.
- 이 파일의 `## 현재 진행 지점`은 스토리가 머지될 때마다 갱신한다. 표에 한 줄을 더하고 다음 스토리를 바꾸며, 여러 스토리에 걸치는 새 계약이 있을 때만 `## 스토리가 고정한 계약`에 한 줄을 더한다. 스토리 서술(도입한 것, 리뷰 경위)은 SPEC에 두고 여기에 쓰지 않는다 — 이 파일은 모든 세션과 서브에이전트에 통째로 실린다. `DEVELOPMENT.md`와 `epic-1-context.md`에 같은 진행 문장이 있으면 함께 맞춘다.
- `AGENTS.md`는 Codex가 읽는 진입 파일이며 이 파일을 대체하지 않는다. 규칙의 권위는 `CLAUDE.md` 하나이고 `AGENTS.md`는 오리엔테이션·실행 명령과 "제품 경로를 바꾸기 전에 `CLAUDE.md`를 읽으라"는 지시만 담는다. 규칙을 그리로 복사하지 않는다 — 두 벌이 되면 갈라지고 맞춰 줄 검사가 없다. `AGENTS.md`의 `bmad:context` 마커 안쪽은 `bmad-project-context` 스킬이 소유하며 refresh 때 교체된다.
