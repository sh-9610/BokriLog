# BokriLog

Bybit 읽기 전용 연결로 복리 목표 계획을 기록·비교하는 개인 프로젝트다. BMAD 기획 산출물은 완료됐고 Epic 1은 닫혔으며 구현은 Epic 2를 진행 중이다. 제품 코드는 `frontend/`(React/Vite 정적 SPA), `backend/`(Spring Boot 최소 실행 기반), `deploy/`(Nginx 동일 출처 구성)로 이뤄져 있다. 절차와 명령의 상세는 `DEVELOPMENT.md`가 소유하고, 이 파일은 세션이 바로 일을 시작하는 데 필요한 규칙과 현재 지점만 담는다.

## 언어

대화와 산출 문서 모두 한국어다. BMAD 중앙 설정(`_bmad/core/config.yaml`)이 `communication_language: Korean`, `document_output_language: Korean`으로 고정되어 있으므로 이 값을 따른다. 브랜치명·커밋 subject·코드 식별자만 영문이다.

## 세션 시작 시 확인 순서

1. `git status`와 `git log --oneline -5`로 현재 브랜치가 `develop`이고 깨끗한지 본다. 사용자 체크아웃은 항상 `develop`에 둔다.
2. `_bmad-output/implementation-artifacts/sprint-status.yaml`에서 `in-progress`·`review` 스토리가 있는지 본다. 있으면 그 스토리의 `spec-<n>-<m>-<slug>.md` frontmatter `status`를 함께 확인한다.
3. 아래 `## 현재 진행 지점`과 `_bmad-output/implementation-artifacts/epic-1-context.md`로 다음 착수 스토리를 확정한다.
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
4. **리뷰** — 리뷰 서브에이전트를 병렬로 돌리고 결과를 `patch`·`defer`·`reject`로 분류한다. `defer`는 `deferred-work.md`에 **추가만** 한다.
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
- **동일 출처.** Nginx는 `/api`만 백엔드로 프록시한다. 나머지는 정적 파일이거나 404이며 어떤 경로도 외부 주소로 프록시하지 않는다. 와일드카드 SPA 폴백은 두지 않는다. 화면이 여는 내부 경로는 허용 목록 location(`^/(onboarding|dashboard)$`)이 이름으로 열고, 그 집합은 `frontend/src/App.tsx`의 경로 맵과 같아야 한다. 라우트를 더할 때는 두 곳을 함께 고친다. access log는 쿼리 문자열을 남기지 않는다 — `$request`를 그대로 쓰면 OIDC 콜백의 `code`·`state`가 로그에 실린다.
- **인증 표면은 루트 패키지가 소유한다.** `identity`는 HTTP를 가질 수 없으므로 Google OIDC 콜백·세션 정책·CSRF는 `com.bokrilog`의 `AuthSecurityConfig`·`AuthSessionService`·`AuthFlowHandlers`·`AuthSessionController`·`SessionGuard`가 맡고, 계정 조회·매핑·판정은 `identity` 공개 API만 쓴다. 경로는 `/api/auth/authorize/<provider>`·`/api/auth/callback/<provider>`·`GET /api/auth/session`·`POST /api/auth/logout`·`POST /api/auth/reauthenticate/<provider>`·`GET /api/auth/link`·`POST /api/auth/link/<provider>`·`POST /api/auth/unlink/<provider>`(준비·`/confirm`·`/cancel` 셋) 열이다. `SessionGuard`는 세션 상태 전환을 다루지만 HTTP 표면이 없다 — 상태 설정에 도달하는 사용자 경로를 만들지 않는다(Story 1.5). 검증(PKCE S256, 일회성 `state`·`nonce`, JWS·RS256·JWKS·`iss`·`aud`·`azp`·`exp`·`nonce`)은 Spring Security OAuth2 Client가 하고 손으로 만들지 않는다. 인증 표면은 `@ConditionalOnWebApplication(type = SERVLET)`로 API 프로세스에만 배선한다(`migrate` 역할에는 `HttpSecurity`가 없다).
- **세션.** Spring Session JDBC로 PostgreSQL에 둔다. 브라우저에는 `HttpOnly`·`Secure`·`SameSite=Lax` 불투명 쿠키 하나뿐이고 로그인 성공 시 세션 ID를 회전한다. 세션이 담는 값은 내부 `user_id` 하나이며 ID Token·액세스·리프레시 토큰은 검증 직후 버린다. CSRF 토큰과 동일 출처 `Origin` 검증은 `/api/auth/` 아래의 상태 변경 요청에만 건다 — 전역으로 켜면 Story 1.2·1.3이 고정한 404·405가 403으로 뒤집힌다. 복귀 목적지는 서버가 가진 닫힌 허용 목록(`/onboarding`·`/dashboard`)뿐이다. 수명은 두 축이다 — 유휴 30분과 생성 시각 기준 절대 12시간. 폐기는 세션 행 삭제·`SecurityContext` 정리·쿠키 만료 지시 셋을 함께 만족해야 하며 셋 중 둘만 만족한 상태를 로그아웃 완료로 보고하지 않는다. 클라이언트가 CSRF 토큰을 얻는 경로는 세션 확인 응답의 필드 하나뿐이다(쿠키 저장소를 쓰지 않으므로 브라우저 쿠키가 하나로 유지된다). 반복 로그아웃은 403이고 그것이 계약이다.
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

## 백엔드·동일 출처 실행

`backend/`는 Java 25 + Spring Boot 4.1.1 + Gradle wrapper이고 모듈 경계는 Spring Modulith 2.1.1이 시험에서 검증한다(운영 classpath에는 없다). Story 1.3부터 PostgreSQL 18을 쓰고 Story 1.4부터 Spring Security OAuth2 Client·Spring Session JDBC가 있다. HTTP 엔드포인트는 `GET /api/status`, `GET /api/auth/session`, `POST /api/auth/logout`, `POST /api/auth/reauthenticate/<provider>`, 그리고 Spring Security 필터가 받는 `/api/auth/authorize/<provider>`·`/api/auth/callback/<provider>`다. Epic 2가 그 위에 `/api/connections/**`(Bybit 연결), `/api/snapshots/first/**`(첫 스냅샷·수집·판정·환산·계획 발행), `/api/plans/conditions/**`(계획 조건), `GET /api/dashboard`(공식 대시보드 — Story 2.8이 2.7의 `GET /api/plans/active`를 대체했고 옛 경로는 404다), `GET /api/dashboard/evidence`(현재 공식 결과의 계산 근거 — Story 2.9), `GET /api/dashboard/adjustments`(현재 공식 계산에 반영된 외부 자금흐름 조정 이력 — Story 2.10), `POST /api/dashboard/sync`(사용자 시점 증분 동기화 접수 — Story 2.11), `POST /api/dashboard/verification`(정밀 검증·기존 계획 계속 접수 — Story 2.12)를 더했다. 상태·인증 컨트롤러는 루트 패키지 `com.bokrilog`에 있고 모듈 패키지는 `identity`·`source`·`portfolio`·`operations`·`sync`·`plan`·`calculation` 일곱이다(`calculation`은 DB·Spring·시계·다른 모듈에 의존하지 않는 순수 모듈이다). Story 1.9부터 루트 패키지가 자기 테이블 하나(`action_grant`, `V3`)를 소유한다 — 그전까지 모든 테이블은 `identity` 소유였다. 컴파일 경고는 `-Werror`로 실패다.

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
- `_bmad-output/implementation-artifacts/` — `sprint-status.yaml`, `epic-1-context.md`, `spec-*.md`, `deferred-work.md`, `deferred-work-resolutions.md`
- `_bmad/render/` — 스킬 렌더링 스냅샷. 생성물이며 커밋하지 않는다.

충돌 시 우선순위는 PRD의 제품 범위 → 아키텍처의 불변식 → `ERD-1.0.0`의 하위 구현 계약 순이다. 결정을 바꿀 때는 기존 문서를 덮어쓰지 않고 새 버전으로 발행한다. `sprint-status.yaml`의 스토리 키 앞부분(`1-3`)이 브랜치 번호이고, 스토리 본문은 `epics.md`의 해당 `### Story` 절이다.

`deferred-work.md`는 `bmad-build`가 `defer` 판정마다 항목을 **추가만** 하는 로그다. 기존 항목을 고치거나 지우지 않는다. 해소 여부는 `deferred-work-resolutions.md`에 기록하고 상태 요약 표를 함께 맞춘다. 검사가 실제로 성공한 뒤에만 해소로 적는다.

## 현재 진행 지점

기획은 완료됐다. `epics.md`는 `implementationReadinessStatus: ready-for-sprint-planning`이고 구현 계약은 `ERD-1.0.0`이 권위 기준이다. `sprint-status.yaml`은 2026-09-02에 생성됐고 첫 스프린트 Story 1.1~1.5(진입 화면 → 동일 출처 셸 → 소유권·삭제 상태 → Google 로그인 → 로그아웃)와 스트레치였던 1.6·1.7(네이버·카카오 로그인)이 모두 머지됐다. 첫 스프린트가 끝났고 Epic 1이 확정한 세 제공자 집합이 완성됐다. 그 위에 1.8(재인증·세션 회전)·1.9(5분·일회성 재인증 허가)·1.10(새 로그인 수단 연결)·1.11(로그인 수단 해제)도 머지됐다. **Epic 1의 11개 스토리가 모두 닫혔다.** 회고도 끝났다(판정 accepted-with-open-items). Epic 2에서는 Story 2.1(읽기 전용 Bybit UTA 연결), 2.2(첫 정상 현재가치 스냅샷), 2.3(복리 계획 조건 미리보기), 2.4(현재가치·원천 사건 완전 수집), 2.5(외부 자금흐름 판정), 2.6(사건 시점 USD 환산), 2.7(최초 계획 계산과 활성 계획·공식 결과 원자 발행), 2.8(공식 대시보드의 여섯 지표와 세 신뢰 상태), 2.9(현재 공식 계산의 입력과 버전 근거), 2.10(계획에 반영된 외부 자금흐름 조정 이력), 2.11(활성 계획의 사용자 시점 증분 동기화와 공식 결과 갱신), 2.12(필요한 이력 구간의 정밀 재검증과 늦은 사건의 새 버전 반영)이 `develop`에 머지됐다. 2.7로 M2-2 검증 지점(`입력 검증 → 순수 계산 → 원자 발행 → 최초 결과 표시`)이, 2.12로 M2-3 검증 지점(`반복 증분 동기화·지연 correction·버전 추적`)이 닫혔다. 다음 구현은 2.13(활성 계획을 명시적으로 중도 종료하고 기록 보존하기)이다. 실연동 게이트는 **둘**이다 — Epic 1의 세 제공자 왕복과 Epic 2의 **Bybit 연결 정책·실연동 미검증**(실제 검증 계정과 regional reserved IP 필요)이며, 둘 다 별도 출시 게이트가 소유한다.

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
| Epic 2 | in-progress | 16개 중 12개 done. 다음은 2.13 |

Story 1.3이 `identity` 모듈, PostgreSQL 18 마이그레이션, 소유권·삭제 상태 판정 계약, 해당 데이터의 삭제 경로와 시험을 들였다. 이 스토리가 처음 도입한 것: 첫 모듈 패키지와 Spring Modulith 경계 검사, PostgreSQL Testcontainers, 마이그레이션(expand 우선, startup은 schema 검증만), 두 사용자·동시성·실패 주입 시험.

Story 1.4가 그 계약 위에 Google OIDC와 서버 세션을 올렸다. 처음 도입한 것: Spring Security OAuth2 Client와 Spring Session JDBC, `V2__spring_session.sql`, 루트 패키지의 인증 표면 네 클래스, `AccountLifecycleApi.findUserIdBySocialIdentity`, `dev-oidc` 프로필의 결정적 local OIDC 스텁과 인증 시험 여섯 묶음, Nginx 경로 허용 목록, 프런트엔드의 경로 맵·Google 버튼·온보딩·로그인 상태 화면. `verify-same-origin.sh`와 `e2e/auth-entry.spec.ts`가 그 스텁으로 성공 왕복을 실제로 지난다. `linkSocialIdentity`·`decideSessionCreation`이 계정 쪽 접점이며 새 소유권 판정을 만들지 않았다. `erasePayload`가 인증 매핑과 함께 그 계정의 세션 행도 지운다.

Story 1.4의 범위 밖: 로그아웃·세션 만료·의심 세션 차단(1.5), 네이버·카카오(1.6·1.7), 재인증과 action grant(1.8·1.9), 로그인 수단 연결·해제(1.10·1.11). 인증으로 막는 경로는 아직 없다 — 이 스토리는 사용자 소유 자원을 만들지 않았고, `authorizeHttpRequests`는 전부 `permitAll`이다. 보호는 그 자원이 생기는 스토리가 자원과 함께 더한다.

Story 1.5가 그 세션에 끝나는 길과 경계를 붙였다. 처음 도입한 것: `POST /api/auth/logout` 하나(Spring Security `logout()` DSL을 `logoutRequestMatcher`로 그 경로에 묶고 204를 돌려준다), 세션 확인 응답의 세 번째 필드로 나가는 세션 귀속 CSRF 토큰, HTTP 표면이 없는 `SessionGuard`(사용자·세션 단위 폐기와 `reauth_required` 표시를 Spring Session의 `PRINCIPAL_NAME` 색인과 세션 속성으로만 다룬다 — 새 마이그레이션도 새 의존도 없다), 생성 시각 기준 절대 수명 12시간, Nginx의 두 단계 캐시 `map`, 두 화면이 공유하는 로그아웃 버튼과 네 표지(로그아웃·만료·로그아웃 실패·냉시작 미인증), 그리고 인증 실패 화면의 Google 공식 계정 복구 안내.

Story 1.5가 고정한 계약 넷은 이후 스토리가 되풀어야 한다. 반복 로그아웃은 **403**이다(세션이 사라지면 토큰도 사라지고 두 필터가 라우팅보다 앞에 있다 — AC-1의 "안전하게 처리"는 멱등한 상태 코드가 아니라 무변경이다). `reauth_required`와 폐기의 차이는 행의 생존이다 — 1.5에서는 응답으로도 구분되지 않았으나 **1.8이 그 하나를 의도적으로 되짚었다**(아래 Story 1.8 문단). 폐기와 미인증의 응답은 지금도 구분되지 않는다. 표시된 세션도 **스스로 로그아웃할 수 있다**(상태를 없애기만 하는 조작이라서). 만료의 결과는 **이동 하나**이며 만료 문장의 출처는 주소의 표지(`noticeKindOf`) 하나다 — 제자리 렌더와 이동을 함께 내면 실제 브라우저에서 둘이 경합한다(주입 origin E2E가 그것을 잡아냈다).

Story 1.5의 범위 밖: 재인증·세션 회전 UX와 5분 action grant(1.8·1.9), 네이버·카카오(1.6·1.7), 제공자 측 로그아웃, Epic 5의 삭제 화면. `authorizeHttpRequests`는 여전히 전부 `permitAll`이다. 표시된 세션이 화면에서 로그아웃 버튼을 볼 수 없던 것(게이트가 미인증이라 토큰이 UI에 도달하지 않았다)은 1.8이 재인증 패널과 함께 해소했다.

Story 1.6·1.7이 그 위에 네이버와 카카오를 얹어 Epic 1이 확정한 세 제공자 집합을 완성했다. 1.6이 단일 제공자 전제를 없앤 자리가 셋이고(등록 맵, 스텁 인스턴스 목록, 화면의 닫힌 제공자 맵) 1.7은 거기에 세 번째 값을 더했을 뿐이다 — 새 인증 계약도 새 의존도 없고 `identity`·마이그레이션·Nginx는 두 스토리 모두 무변경이다. 제공자마다 다른 것은 등록 조립 하나다: Google은 `CommonOAuth2Provider`가 한 줄로 주고, 네이버·카카오는 그 enum에 없어 공식 discovery의 정적 주소 넷과 `client_secret_post`·`sub`을 손으로 준다(카카오의 JWKS는 issuer 바로 아래 `/.well-known/jwks.json`이라 네이버의 `/oauth2/jwks`와 모양이 다르다). 브랜드 값은 제공자가 **공표한 수치만** 토큰이 된다 — 그래서 모서리는 Google 4px·카카오 12px이 각자 소유하고 네이버는 공표값이 없어 BokriLog의 `--radius-md`를 쓴다. 세 버튼의 "동등한 위계"는 바깥 크기·간격·순서·추천 표지 부재로 성립하며 타이포그래피의 동일성이 아니다 — 네이버 레이블만 20px·700인 것은 공표 색 조합의 대비가 3.095:1이라 큰 텍스트 문턱으로만 닫히기 때문이고(카카오는 12.52:1) 그 사실을 e2e 단정이 고정한다.

카카오에는 다른 둘에 없는 제약이 둘 있다. `sub`이 **pairwise**라 client 등록에도 묶이므로 앱을 다시 만들거나 REST API 키를 교체하면 기존 `(kauth.kakao.com, sub)` 매핑이 전부 고아가 되고 돌아온 사용자마다 조용히 새 계정이 생긴다. 그리고 Client Secret이 콘솔의 선택 기능인데 fail-closed 게이트를 무르지 않았으므로 운영자가 그것을 켜야 등록이 만들어진다. 콘솔에서 켜야 하는 것과 사전 등록할 Redirect URI는 `DEVELOPMENT.md`가 소유한다.

Story 1.8이 그 위에 재인증과 세션 회전을 올렸다. 재인증은 **두 번째 인증 경로가 아니다** — 같은 `oauth2Login` 왕복을 지나므로 PKCE·`state`·`nonce`·JWS 검증과 세션 회전이 로그인과 같은 배선에서 나오고, 세션에 심은 일회성 흐름(`ReauthenticationFlow`)이 성공 핸들러를 갈라 판정 둘만 더한다: 검증된 `(issuer, subject)`가 현재 사용자의 것인가(`AuthSessionService.confirmReauthentication` — `findUserIdBySocialIdentity` + `AccountAccessApi.decide(SENSITIVE)`)와 제공자가 서명한 `auth_time`이 신선한가(무장 수명 5분·창 5분·허용 skew 60초). `establish`를 부르지 않으므로 계정 생성·매핑·전환 경로를 지나지 않는다. 처음 도입한 것: `POST /api/auth/reauthenticate/<provider>`(무장만 하고 204를 돌려준다), 세션 확인 응답의 네 번째 필드 `reauthRequired`, 흐름 경계를 콜백까지 넓힌 `authenticationFlowFilter`, 표지 둘(`reauthCancelled`·`reauthFailed`), 두 인증 화면이 공유하는 `ReauthPanel`과 `ProviderMark`, 스텁 ID Token의 `auth_time`. 새 마이그레이션·새 의존·새 Nginx location·새 프런트엔드 라우트가 없고 `identity` 공개 API도 무변경이다.

Story 1.8이 되짚은 것과 새로 고정한 것. **표시된 세션에만 `reauthRequired`와 CSRF 토큰이 실린다** — 1.5의 "표시와 폐기가 응답으로 구분되지 않는다"를 의도적으로 깼다. 구분하지 않으면 그 세션은 토큰을 받지 못하고 로그인 버튼은 흐름 경계가 거절해 되살릴 방법이 없는 무한 왕복이 된다. 회복에 필요한 것은 제공자 자격이므로 표시 사실을 아는 것만으로는 아무도 그 세션을 되살리지 못하며, **미인증·폐기 응답 본문은 글자까지 그대로다**. **인증된 세션에 도착한 무장 없는 콜백은 검증 앞에서 접힌다** — 재인증이 "인증된 세션이 진행 중인 authorization request를 갖는" 상태를 처음 만들었고, 접지 않으면 만료된 흐름의 콜백이 성공 핸들러의 로그인 분기로 들어가 같은 세션 행에 다른 계정을 심는다(이 때문에 `AuthTokenValidationTest`의 목적지 단정 둘이 `/?auth=failed`에서 `/onboarding`으로 바뀌었다). **복귀 목적지의 출처는 세션 하나**이며 클라이언트는 목적지를 보내지 않는다. **제공자별 최근 인증 계약은 공표된 것만 쓴다** — 공통 `max_age=300` 위에 Google은 `claims`의 essential `auth_time`을, 카카오는 `prompt=login`을 더하고 네이버는 공표한 추가 수단이 없다(2026-09-18 공식 답변: ID Token claim 구성 문서 미제공). 증거가 없으면 완화하지 않으므로 네이버는 실연동에서 fail-closed될 수 있고 그것이 AC-3이 요구한 결과다.

Story 1.8의 범위 밖: 5분·일회성 action grant(1.9), 로그인 수단 연결·해제(1.10·1.11), 제공자 측 로그아웃. 재인증 성공이 실행하는 파괴적 작업은 하나도 없다 — 남기는 것은 세션의 재인증 시각 하나이고 HTTP로 나가지 않는다. `authorizeHttpRequests`는 여전히 전부 `permitAll`이다. 표시된 세션은 `SessionGuard`에 HTTP 표면이 없고 세션 속성이 Java 직렬화라 브라우저 층에서 만들 수 없다 — 그 경로는 백엔드 시험과 Vitest가 소유하고 `verify-same-origin.sh`는 자발 재인증으로 같은 HTTP 계약을 본다.

Story 1.9가 그 재인증 결과를 **서버 보관형 일회성 허가**로 바꿨다. 소비는 조건부 UPDATE 한 문장이고 그 술어가 다섯 귀속(user·session·command·target·canonical confirmation hash)과 미소비·미만료를 함께 판정한다 — 판정과 소비 사이에 창이 없으므로 동시 소비가 인덱스 하나로 갈린다. 소비와 호출자 업무 변경은 같은 트랜잭션에서 커밋되고, 업무 변경이 실패하면 허가는 미소비로 남으며, 같은 `operation_id` 재전송은 요청 지문이 전부 일치할 때만 저장된 결과를 돌려준다. 처음 도입한 것: `V3__action_grant.sql`과 **루트 패키지가 처음 소유하는 테이블**, `ActionGrant`(수명 5분·재인증 창 5분·미래 skew 60초와 정본 확인 해시), `ActionGrantRepository`(JdbcClient SQL 다섯), `ActionGrantService`(포트 둘), `ReauthenticationFlow.lastAuthenticatedAt`(1.8이 기록만 하고 조회가 없던 짝).

Story 1.9가 고정한 계약 셋. **시각의 권위는 PostgreSQL이다** — 발급이 `expires_at`을 절대 시각으로 못 박고 만료는 소비 술어가 판정하며 애플리케이션 시계는 만료를 판정하지 않는다. 발급 신선도 술어도 과거(창)와 미래(skew) 양쪽을 닫는데, 비교 대상 한쪽이 애플리케이션 시계가 쓴 값이기 때문이다. **`(user_id, operation_id)` 부분 유일 색인이 멱등의 권위다** — 사용자 축이 없으면 추측 가능한 키를 먼저 쓴 쪽이 다른 사용자의 그 작업을 영구히 막는다. **허가는 계정 소유권이 아니라 세션·작업에 귀속하므로 `identity`에 둘 수 없다**(그 모듈은 서블릿·HTTP 의존이 금지돼 세션 개념을 가질 수 없고 `AccessDecision`은 두 값뿐이다). 대신 `action_grant`가 `identity_account`를 `ON DELETE CASCADE`로 참조해 삭제 경로가 새 테이블을 몰라도 된다 — `V1`의 `RESTRICT` 관용을 뒤집는 선택이고 그 근거는 `V3` 머리말에 있다.

Story 1.9의 범위 밖: 로그인 수단 연결·해제(1.10·1.11), Bybit 변경(Epic 2), 계정 삭제의 업무 상태 변경(Epic 5). **HTTP 표면을 하나도 만들지 않았다** — 실행 가능한 민감 command가 아직 없어 지금 엔드포인트를 만들면 소비자 없는 표면이 된다. `SECURITY-ACTION-1.0`이 요구하는 "성공 시 세션 재회전"은 포트가 결과 타입 이름으로 의무를 알리고 첫 소비자(1.10)가 자기 엔드포인트에서 배선한다 — 회전은 HTTP 요청 문맥을 요구하는데 이 스토리엔 그 문맥이 없다.

Story 1.10이 그 둘의 **첫 실소비자**가 됐다. 설정 화면(`/settings`)과 연결 흐름을 더해 "기존 수단으로 재인증(1.8) → 연결 작업용 허가 발급(1.9) → 대상 제공자 OIDC 왕복" 세 단계를 순서대로 강제하고, 최종 커밋에서 허가 소비와 `identity` 매핑 추가가 **한 트랜잭션**으로 끝난다 — 허가만 소비된 상태도 매핑만 생긴 상태도 남지 않는다. 처음 도입한 것: 세 번째 내부 경로 `/settings`와 `SettingsScreen`, `LinkFlow`(재인증 무장과 **별도 세션 속성**), `LinkService`(시작·완료의 판정을 소유), `GET /api/auth/link`·`POST /api/auth/link/<provider>`, `AccountLifecycleApi`의 연결된 제공자 목록 조회(제공자 이름만 답하고 미존재·삭제는 빈 집합), 두 인증 화면의 설정 링크. 새 마이그레이션도 새 의존도 없다.

Story 1.10이 고정한 계약 셋. **허가의 귀속 세션 id는 연결 흐름 record가 들고 있던 값을 제시한다** — 콜백은 Spring Security가 반드시 세션을 회전시키므로 발급 시각의 id와 소비 시각의 live id가 언제나 다르고, live id로만 판정하면 연결이 구조적으로 불가능하다. 그 record가 세션 속성이라 읽을 수 있다는 사실 자체가 회전 전 세션의 연속을 증명하며, 클라이언트가 그 값을 제시할 수 없다는 것을 javadoc과 시험이 고정한다. **콜백의 등록 id가 분기를 가른다** — 연결 무장과 재인증 무장이 한 세션에 함께 살아 있을 수 있고(설정 화면이 두 버튼을 같은 페이지에 그린다), 그때 콜백이 어느 흐름의 것인지는 제공자만이 답한다. 무장 시 상대 흐름과 그 허가를 함께 버린다. **밀어내기는 두 랜딩 화면 사이에서만 일어난다** — `/settings`는 어느 방향으로도 밀려나지 않는다. 대칭으로만 고치면 신규 사용자(목적지가 `onboarding`)가 설정 화면을 영영 못 본다.

Story 1.10의 범위 밖: 로그인 수단 **해제**와 마지막 수단 보호(1.11), 계정 간 병합·이전. 거부는 여전히 하나의 값이다 — 이미 연결됨·다른 계정에 연결됨·허가 만료·세션 불일치·삭제 상태가 응답으로 구분되지 않는다. 연결 상태 조회는 제공자 이름만 답하고 `issuer`·`subject`·이메일·시각을 싣지 않는다.

Story 1.11이 해제를 더해 **Epic 1의 11개 스토리가 모두 닫혔다**. 중심은 마지막 수단 보호이며 그것은 **삭제 문장의 술어**다 — 생명주기 행 잠금 아래에서 `DELETE ... WHERE provider=... AND EXISTS(state='ACTIVE') AND (남은 수 > 1)` 한 문장이 판정과 삭제를 함께 한다. "먼저 세고 1개면 거절"은 두 문장 사이에 다른 요청이 들어오면 둘 다 통과해 계정이 영구히 잠기고, 잠금만으로는 스냅숏이 갈리며 술어만으로는 잠금 없는 동시 실행에서 둘 다 통과한다 — 둘 다 필요하다. 처음 도입한 것: `AccountLifecycleApi.unlinkSocialIdentity`, `UnlinkFlow`·`UnlinkService`, `SessionGuard.revokeSessionsOfExcept`("전부 폐기"와 "하나 폐기" 사이가 비어 있었다), `POST /api/auth/unlink/<provider>`의 준비·`/confirm`·`/cancel` 셋, 그리고 `UnlinkDialog`. 새 마이그레이션도 새 경로도 없다.

Story 1.11이 고정한 계약 둘. **확인 대화상자가 OIDC 왕복의 자리를 대신한다** — 해제에는 왕복이 없어 준비와 확인 사이에 세션이 회전하지 않으므로 `UnlinkFlow`에는 1.10이 필요로 했던 `returnPath`·`boundSessionId`가 없다. 대화상자를 여는 요청은 허가를 발급하고 무장할 뿐 아무것도 바꾸지 않으며, 취소는 그 무장과 허가를 실제로 푼다(그래서 엔드포인트가 둘이 아니라 셋이다). **커밋 뒤 현재 세션 외 그 사용자의 세션을 모두 폐기하고 현재 세션만 회전시켜 남긴다** — AC-7의 1차 갈래("대상 identity에서 유래한 세션만 폐기")는 세션이 인증 제공자를 기록해야 하는데 그것은 "세션이 담는 값은 내부 `user_id` 하나"라는 불변식이 금지한다. AC-7이 그 경우를 예상해 준 fail-safe 갈래다.

**세 흐름이 서로를 버리되 게이트를 통과한 뒤에만 버린다.** 설정 화면이 연결·본인 확인·해제 셋을 같은 페이지에 그리므로 무장이 겹칠 수 있고, 겹치면 콜백이나 확인이 엉뚱한 흐름을 소비한다. 다만 **거절되는 시작 요청은 아무것도 버리지 않아야 한다** — 1.11의 리뷰가 그 반대로 된 코드를 잡아냈다. 실패해야 할 뿐인 요청 하나가 다른 탭의 살아 있는 의도를 무음으로 파괴하는 모양이었다.

Epic 1 회고가 끝났다(`epic-1-retro-2026-09-25.md`, 판정 **accepted-with-open-items**). 선언된 완료 기준 — "가입·로그인·로그아웃·로그인 수단 관리가 실제 서버와 저장소를 통해 작동한다" — 은 실 스택에서 관측됐다(`verify-same-origin.sh` 823/823, 백엔드 372, 주입 origin e2e 52, Vitest 248). 회고가 남긴 액션 12건은 `sprint-status.yaml`의 `action_items`가 소유하며, **그중 둘은 실연동 게이트를 열기 전에 닫아야 한다**: 재인증 시각(`bokrilog.auth.reauthenticated-at`)을 지우는 코드가 없어 본인 확인 1회가 5분 창 안에서 연결·해제 여러 건을 연쇄 승인하는 것, 그리고 `onFailure`가 무장을 무조건 버려 교차 사이트 최상위 내비게이션 하나로 피해자의 살아 있는 의도를 반복 파괴할 수 있는 것이다. 회고가 찾은 순수 결함은 하나다 — 해제 확인 대화상자가 AC-4의 세 번째 문장(같은 제공자로 다시 로그인하면 빈 신규 계정이 생길 수 있다)을 고지하지 않는다. Epic 2 착수 전에 정할 것 둘은 인가 규칙 도입 시점(`permitAll` 근거 주석이 1.10부터 거짓이다)과 실연동 게이트를 기계가 읽는 자리에 두는 것이다.

Story 2.1이 Epic 2를 열며 저장소에 처음 있는 것 넷을 한꺼번에 들였다. **모듈이 하나에서 넷이 됐다** — `source`(연결·암호화 자격정보·Bybit 어댑터), `portfolio`(기본 `USD` portfolio와 활성 membership 하나), `operations`(admission과 최종 트랜잭션이 함께 읽는 durable 제한 판독 기반). 셋 다 HTTP 표면이 없고 `identity`의 레이아웃을 그대로 따른다(공개 API는 모듈 루트, 구현은 package-private `internal`, `JdbcClient`). 그리고 **첫 암호화 자재**(AES-256-GCM, 봉할 때마다 새 96비트 CSPRNG nonce, AAD `user_id/connection_id/credential_id/key_version/algorithm_version`, active key ring + decrypt-only 과거 키), **첫 인증 게이트**, **첫 텍스트 입력 폼**이다. `V4__source_connection.sql`이 테이블 여섯을 더하고 한 개 규칙을 조건부 유일 색인으로 강제한다 — 애플리케이션이 먼저 세지 않는다.

흐름은 세 단계가 순서대로 성립해야 한다: 최근 재인증(1.8) → `bybit-connection-create` 허가(1.9) → Bybit 세 엔드포인트(`query-api` → `account/info` → `wallet-balance`). **외부 호출은 트랜잭션 밖에서 끝나고** 그 뒤 허가 포트가 여는 경계 하나에서 허가 소비와 여섯 저장이 함께 커밋되며, 실제로 일이 돈 경우에만 세션이 회전한다. `provider_account_id`는 검증된 메인 UID에서 파생해 자격정보·connection 교체에도 재사용하고 connection ID는 수집 provenance일 뿐 사건 고유키가 아니다.

Story 2.1이 고정한 계약 넷. **인가는 새 접두사에만 건다** — 전역 `authenticated()`는 Story 1.2가 고정한 404·405를 401·302로 뒤집는다. 인가 판정과 CSRF·`Origin` 판정은 **같은 술어 하나**(`isConnectionsPath`)를 부른다. **CSP는 넓히지 않는다** — `form-action`은 네이티브 제출만 제어하고 이 폼은 `fetch`로 보내므로 `'none'`을 남기면 JS가 죽은 상태의 Enter에서도 자격정보가 새지 않는다. **허가의 확인 문구는 클라이언트가 되돌려 보낸 값과 대조한다**(NFC 정규화 후) — 그래야 `ERD-1.0.0` §5의 다섯 번째 축이 판별력을 갖는다. Epic 1의 두 command는 아직 옛 방식이며 개조는 미룬 결정 65번이다. **구성이 비면 제공자를 두드리기 전에 닫는다** — key ring이나 base URL이 없으면 admission에서 422로 끝나고 외부 호출이 0건이다.

Story 2.1의 범위 밖: 공식 현재가치 스냅샷(2.2), 계획 생성(2.3), 동기화 작업 큐와 Worker·lease·fencing(2.2 이후), 자금흐름 수집(2.4~2.6), 연결 해제(2.16), 둘 이상의 금융 source. **permission 허용 목록과 `retCode` 표는 의도적으로 비어 있다** — 어느 계획 문서도 Bybit 문자열을 열거하지 않아 지어내지 않았고, 비어 있으면 닫히는 쪽이 기본값이며 시험이 그 공백을 고정한다. 실연동 전에 API 스파이크가 채울 자리이고, 그때 **Bybit의 실제 rate limit·IP 거부가 429·503이 아니라 200 + `retCode`로 온다**는 것을 함께 봐야 한다(지금 그 두 거절 사유는 스텁만 내는 모양이다).

Story 2.4·2.5가 그 연결 위에 계산 입력의 앞 두 단계를 놓았다. 2.4는 Bybit 시간 API로 `tc`를 한 번 고정하고 `t0 < 사건시각 <= tc`를 7일 이하 창으로 나눠 Transaction Log `TRANSFER_IN`·`TRANSFER_OUT`의 모든 cursor를 읽은 뒤, 완전성이 증명된 불변 후보 묶음만 만든다(`V7`). 2.5는 그 후보를 `BYBIT-CASHFLOW-1.0` 결정표로 판정해 정확한 type·부호만 외부 흐름으로 두고 나머지는 제외·검토로 가른다(`V8`). 두 작업 모두 lease·fencing과 짧은 원자 publish, 사용자 범위 삭제를 쓰며 공식 snapshot·계획·포인터를 만들지 않는다.

Story 2.6이 판정된 흐름에 사건 시점 USD 가치를 붙였다(`BYBIT-PRICE-1.0`, `V9`). `USD`·`USDT`만 직접 정규화이고 그 밖의 자산은 spot `ASSETUSDT` → `ASSETUSDC × USDCUSDT` 경로의 **종료된** 1분봉 close를 `ESTIMATED`로 쓴다. 사건 분 candle이 닫혔는데 없을 때만 직전 5분 안의 가장 최근 candle로 내려가고, 미종료면 fallback 없이 재시도 가능한 보류다(실행 시각에 따라 같은 입력이 다른 가격을 고르지 않게). spot Instruments Info에 `launchTime`이 없어 사건 시점 상장의 증거는 candle 존재 자체다. 산술은 정확 곱 뒤 CALC-1.0 canonical(34 유효숫자·scale 24, HALF_EVEN)로 한 번만 반올림한다. 처음 도입한 것: **사용자 식별자 없는 공유 공개 증거**(`sync_market_candle`·`sync_market_instrument_observation`, 사용자 삭제 뒤에도 남는다), source의 **서명 없는 공개 시장 조회** 셋, 시도(job)와 결과 버전(bundle, `previous_conversion_id`·`change_reason`)의 분리, 그리고 main 코드의 첫 로거(환산 중단 시 job id와 예외 클래스 이름만). **Story 2.7은 `sync_calculable_cashflow`만 소비한다** — 복합 FK가 그 행을 계산 가능 묶음 아래에만 허용한다. 호출 예산(token bucket)과 자동 재시도는 2.4처럼 여전히 없다.

Story 2.7이 처음으로 활성 계획과 공식 결과를 만들었다. **계산은 순수 `calculation` 모듈이 한다** — `CALC-1.0`·`CALENDAR-SEOUL-1.0`, `MathContext(34, HALF_EVEN)`, 버전 고정 `big-math` `pow`. `periods`는 `t0`에 고정한 서울 달력 경계에서 구간별 **정확한 분수를 누적한 뒤 한 번만** 반올림한다(구간마다 반올림하면 CV-10 가법성이 마지막 자리에서 깨진다). CV-01~07·09·10·12가 계약 fixture이고 기동 시 적합성 검사가 실패하면 발행을 막는다. 계산 기준시각은 2.4가 고정한 `tc`이고 `cashflow_verified_through = tc`다. **발행은 sync publication service가 소유한 outer `REQUIRED` 트랜잭션 하나**다 — tc source·portfolio snapshot과 portfolio `publication_sequence` CAS, 불변 활성 계획(`plan_definition`, portfolio당 ACTIVE 하나를 조건부 유일 색인이 강제)과 intent `CONSUMED`, 계산 버전·지표·자금흐름 이력, plan별 공식 포인터(`sync_official_plan_result`)의 version CAS가 함께 커밋되거나 함께 사라진다. sync는 plan에 의존할 수 없으므로 계획 command는 sync가 소유하고 plan이 구현하는 `PlanPublicationPort`로 부르고, portfolio·plan command는 실제 트랜잭션이 없으면 거절한다. 고정한 계약 넷: **`Target <= 0`이어도 계획은 발행하고** 목표 기반 비율만 계산 불가, 입력 신뢰도 `검토 필요`는 불변인 계획 행이 아니라 계산 버전이 소유한다. **범위 초과는 발행 전체를 거절한다**(`CALCULATION_RANGE_EXCEEDED`). **결과 읽기는 계정 접근만 본다** — 쓰기 command 제한은 이미 발행된 공식 결과를 가리지 않는다. **`계산 불가`는 계산 성격을 덮지 않는다** — 카드는 `공식/추정`과 `계산 불가: 이유`를 따로 보인다. 결과 패널은 `/dashboard`와 `/onboarding`에 함께 있다(첫 로그인 세션은 늘 `/onboarding`으로 돌아온다). operations의 제한 판정 시각은 `statement_timestamp()`다 — 잠금을 기다리던 트랜잭션이 그 사이 커밋된 제한을 놓치지 않게. Story 2.7의 범위 밖: 반복 증분 동기화·재계산(2.11·2.12), 상세 계산 근거 화면(2.9), 계획 종료·교체(2.13~2.15). 이 스토리로 CI `backend-quality`가 약 10분이 됐다(job timeout 15분).

Story 2.8이 그 결과를 공식 대시보드로 넓혔다. **조회 모델은 `GET /api/dashboard` 하나다** — 200은 `NO_PLAN`(`CREATE_PLAN`)·`PREPARING`(진행 중 발행 시도만, `WAIT_FOR_RESULT`)·`READY` 셋이고 앞의 둘에는 금융 필드가 없다. 403은 계정 접근 거부, 503 `DASHBOARD_UNAVAILABLE`은 묶음 불일치, 500은 읽기 실패이며 모두 `no-store`다. 계획 → 포인터 → 계산 → 지표 → 신선도를 **읽기 전용 `REPEATABLE READ` 트랜잭션 하나**에서 읽고, 계정 접근(삭제 상태)을 그 **앞뒤**로 확인한다(삭제 완료 뒤는 세션 행이 사라져 401). 경로는 식별자를 받지 않는다 — 쿼리의 ID는 무시되고 하위 경로는 존재와 무관하게 같은 404다. 운영 로그는 읽기마다 한 줄이고 상태·HTTP 상태·지연·버전 일치(`true/false/unknown`)만 남긴다. **세 신뢰 축은 서버가 판정한다**(`DashboardTrust` 순수 함수) — 축마다 `state`·`cause`·`lastNormalAt`·`affectedMetrics`·`nextAction` 코드이고 화면은 닫힌 맵으로 카피에 잇는다(모르는 코드는 안전한 카피). 되짚은 2.7의 결정이 둘이다: 204 "결과 없음" 대신 상태 셋, 그리고 "마지막 정상 시각 없음" 대신 축별 정의(신선도 `measuredAt`, 입력 `cashflowVerifiedThrough`, 계산 `basisAt` — 포인터 묶음이 곧 마지막 정상 묶음이라 저장하지 않는다). 고정한 계약 넷: **portfolio 포인터가 계산 버전의 snapshot을 가리켜야 `READY`다** — AD-23(두 포인터가 한 발행에서 함께 전진)을 전제하므로 둘을 따로 전진시키는 스토리(2.11)는 이 판정을 다시 봐야 한다. **입력 신뢰도는 모르는 값을 `확인됨`으로 접지 않는다**, `사용 불가`는 계약·카피만 있고 생산자는 2.11·2.12다. **`최신 아님`의 다음 행동은 `NONE`이다** — 동기화 조작은 2.11이 코드를 더하며, 그 전에는 발행 15분 뒤부터 `최신 아님`이 정상이다. **화면은 응답 하나를 한 단위로 교체한다** — 늦은 응답은 버리고, 버전 역행·충돌은 교체하지 않고 `재시도 가능`, 실패한 다시 불러오기는 `마지막 정상 결과`로 유지, 401·403은 값을 메모리에서 지운다. 결과가 있으면 첫 공식값 패널을 그리지 않는다(그 패널은 `NO_PLAN`·`PREPARING`에서만). Story 2.8의 범위 밖: 동기화 실행(2.11), 계산 근거 상세(2.9), 조정 이력(2.10), 계획 종료(2.13).

Story 2.9가 그 대시보드에 현재 공식 결과의 계보를 붙였다. **근거는 새 경로가 아니라 대시보드 패널 안에서 펼친다** — `계산 근거 보기` 버튼(`aria-expanded`)이 같은 화면에 스냅샷 → 시각 → 입력 버전 → 계산 버전 → 지표 영향 → 버전 목록을 펼치고 `계산 근거 닫기`가 초점을 되돌린다(새 route는 다섯 곳 동기화와 금융값 재조회를 부른다). **`GET /api/dashboard/evidence`는 참조를 받는 유일한 대시보드 경로다** — 클라이언트가 지금 보이는 묶음의 `planId`·`pointerVersion`·`calculationId`를 보내고, 셋이 그 사용자의 현재 공식 포인터와 모두 같을 때만 200이다. 다른 사용자·지난 버전·없는 ID·계획 없음·형식 오류·누락·파싱할 수 없는 인코딩은 전부 같은 404이며(접근 확인이 먼저라 삭제 중이면 403), `/api/dashboard`의 다른 하위 경로는 2.8대로 같은 404다. 읽기 전용 `REPEATABLE READ` 한 묶음에서 **계보 전체가 서로 맞아야 답한다** — 발행 시도가 고정한 입력(`input_scope_hash`는 plan 쪽 정책 값이 필요해 재계산하지 않는다), 기준·tc 원천 스냅샷, 수집 범위 `[t0, tc]`, 판정·환산·반영 흐름 중 하나라도 어긋나면 503 `EVIDENCE_UNAVAILABLE`과 `consistent=false`다. 새 마이그레이션도 다른 모듈의 새 공개 API도 없다 — 연결 정책 버전은 첫 스냅샷 작업이 쓴 기준 원천 스냅샷에서, 수집 정책은 수집 후보에서 읽는다. `provider_account_id`·Bybit UID·자격정보·원 금융 응답·개별 자금흐름 사건(2.10)은 싣지 않는다. 긴 ID와 hash는 줄이지 않고 mono 전체값으로 줄바꿈한다. 고정한 계약 넷: **공식 버전은 2.11 전까지 늘 하나다** — 포인터에 이력이 없어 적용 시작은 발행 시각, 끝은 현재이고 이력 테이블은 포인터를 전진시키는 2.11이 소유한다. **계보 판정이 최초 발행의 불변식에 기댄다**(포인터 하나, portfolio 포인터 동시 전진, 흐름이 한 환산만 인용, 발행 전 포인터가 기준 스냅샷이고 순번 +1) — 2.11·2.15가 다시 봐야 한다. **근거 읽기의 실패는 대시보드를 바꾸지 않는다** — 최소 참조(snapshot·calculation ID)와 재시도만 보이고, 401·403은 근거를 지우고 대시보드가 스스로 다시 읽게 한다. **화면 status는 `aria-atomic="false"`로 대시보드 문장과 근거 문장을 따로 말한다**(`role=status`는 기본이 atomic이다). Story 2.9의 범위 밖: 조정 사건 상세(2.10), 지난 공식 버전의 근거, 운영자 감사·정지(Epic 3).

Story 2.10이 그 계보 아래의 개별 입출금을 보이게 했다. **조정 이력도 새 경로가 아니라 대시보드 패널 안에서 펼친다** — `계산 근거 보기` 바로 뒤의 `조정 이력 보기` 토글이 설명·합계·유형 필터(전체·외부 입금·외부 출금)·행 카드·`더 보기`를 같은 화면에 펼치고 `조정 이력 닫기`가 초점을 되돌린다(UX는 전역 내비 목적지로 그리지만 앱에 전역 내비가 없다). 목록은 한 DOM의 `ol`이고 320px 카드, 768px 이상 행 배치다(표와 카드를 두 벌 두면 보조기술이 두 번 읽는다). **`GET /api/dashboard/adjustments`는 2.9처럼 참조를 받는다** — `planId`·`pointerVersion`·`calculationId`와 선택 `direction`(`ALL`·`IN`·`OUT`)·`cursor`이고, 참조가 현재 공식 포인터와 같을 때만 200이다. 변조 참조·다른 범위 cursor·형식 오류는 2.9와 글자까지 같은 404, 삭제 중이면 403이며 원천 사건·판정·환산 ID는 입력으로 받지 않는다. 행은 그 계산 버전의 반영 흐름뿐이고 순서는 그 버전의 `sequence`다(`(event_time, provider_account_id, source_id)`를 실현하고 한 버전 안에서 사건당 환산 버전은 하나다). 한 페이지 20행이며 **cursor는 서명하지 않는다** — 범위 digest(요청자 `user_id`·참조·방향·정렬 정책 `ADJUSTMENT-ORDER-1.0`)와 마지막 순번뿐이라 남의 cursor는 대조에서 떨어지고 자기 cursor 변조는 자기 행만 보인다. 읽기 전용 `REPEATABLE READ` 한 묶음에서 **페이지가 아니라 계산 버전 전체 행의 계보**(원 사건·판정·환산·반영 행의 원천 ID·시각·자산·수량·USD, 방향과 부호, 가격 근거와 leg 수, `(t0, cashflowVerifiedThrough]`, 순번, 판정·환산 묶음과 환산 사슬, 제외 건수)를 판정하고 하나라도 어긋나면 503 `ADJUSTMENT_HISTORY_UNAVAILABLE`과 `consistent=false`다. 합계는 PostgreSQL `numeric`의 정확한 합이고 화면이 한 번만 반올림한다. 판정 제외 사건 수는 필터와 무관한 값이라 행·합계와 분리해 보인다. 새 마이그레이션·새 경로·다른 모듈의 새 공개 API는 없고, e2e용 스텁 시나리오 `adjustments`(USDT 23건)만 더했다. 고정한 계약 넷: **변경 이력은 환산 버전 사슬(`previous_conversion_id`)로만 실현한다** — 원 사건 revision(`source_revision`은 1로 고정)과 판정 이전 링크가 아직 없고, 사건의 값이 과거 버전과 달랐을 때만 표지와 "공식 계산에 쓰이지 않은 과거 버전" 목록이 붙는다. **공식 버전이 하나라는 전제에 기댄다** — 과거 버전 표기와 cursor의 "사건당 환산 버전 하나"는 2.11 전까지만 참이라 2.11·2.12·2.15가 다시 봐야 하고, 영구히 끊긴 사슬도 지금은 재시도 가능한 503으로 보인다. **원수량·단가·종가는 반올림하지 않는다** — DISPLAY-1.0의 2자리는 USD 금액의 규칙이다(`formatQuantity`). **Bybit 거래 기록 ID는 보인다** — 거래소 기록과 맞춰 볼 열쇠이며 UID·`provider_account_id`는 싣지 않는다. 행 제목과 상세 토글 이름에는 순번이 들어가 같은 ms의 행도 구분되고, 화면 status의 세 번째 요소가 이력 읽기 문장이다. Story 2.10의 범위 밖: 사건 ID를 받는 상세 엔드포인트, 수동 분류·가격 수정, 새 동기화·재계산(2.11·2.12), 지난 계획의 이력(2.13·2.15).

Story 2.11이 공식 결과를 사용자 시점에 갱신하게 했다. **트리거는 셋이고 폴링은 없다** — 대시보드 진입·5분 이상 숨겼던 탭으로 복귀·`동기화` 버튼이 `POST /api/dashboard/sync`(`{trigger}` + `Idempotency-Key`)로 동기화 작업 하나를 접수한다. admission은 외부 호출 없는 짧은 트랜잭션이고 순서는 계정 쓰기 접근 → key 재전송 → 활성 계획 → 진행 중 작업에 합치기 → 사용 불가(제한·연결·membership·기간 종료) → 장기 미동기화(DB 시각으로 확인 시각에서 30일 초과) → 간격(자동은 `max(마지막 admission, 공식 발행)` 뒤 5분, 수동은 30초)이다. **작업 하나가 단계를 차례로 실행한다** — `sync_plan_sync_job`이 lease 하나로 tc 고정 → 수집 → 판정 → 환산 → 계산 → 발행을 한 가상 스레드에서 돈다. 2.4~2.6의 단계 표 행은 FK·계보를 위해 그대로 만들되 이 작업이 소유하는 자식이라 독립 claim이 집지 않고, 다시 claim된 실행 중 작업은 이어 달리지 않고 `SYNC_INTERRUPTED`로 끝난다(자동 재시도 없음). Bybit `tc`는 DB 시각 ±60초 안에서만 믿고(`PROVIDER_TIME_UNTRUSTED`), 모드는 `tc - last_extended_verification_at >= 7일`이면 EXTENDED(7일 중첩), 아니면 NORMAL(24시간)이며 수집은 `max(t0, 확인 시각 - 중첩)`부터 `tc`까지 7일 창이다. **매번 `(t0, tc]` 전체를 새 계보로 만든다** — 직전 공식 후보의 사건은 provenance 표지를 달아 이어받고 창의 관측을 더하며, 설명되지 않는 저장 사건은 `검토 필요`다. 전체를 다시 판정·환산하므로(캔들은 캐시) 2.9·2.10의 "계산 버전 하나 = 계보 하나"가 그대로 선다. 발행은 2.7과 같은 잠금 순서의 outer 트랜잭션 하나이며 source/portfolio snapshot, 계산 버전(두 워터마크와 `previous_calculation_id`), plan 포인터 CAS(`statement_timestamp()`)와 추가 전용 `sync_official_plan_result_history`가 함께 커밋된다. 처음 도입한 것: `V11__incremental_plan_sync.sql`, `PlanSyncService`·`PlanSyncPublisher`·`PlanSyncRepository`·`PlanSyncGuard`·`SyncWindow`, source의 `collectIncrementalInputs`, 운영 제한 명령 `plan-sync`, 대시보드 `READY`의 `sync` 블록과 신선도 원인 넷(`LAST_SYNC_UNSUCCESSFUL`·`LAST_SYNC_NEEDS_REVIEW`·`LONG_GAP`·`SYNC_UNAVAILABLE`), 화면의 `SyncControl`·`useDashboardSync`, 스텁 시나리오 `incremental`. 고정한 계약 넷: **30일 판정의 시계는 admission의 DB 시각이다** — 30일 초과면 외부 호출이 없어야 하므로 Bybit 시간을 먼저 물을 수 없고, Worker가 고정한 `tc`로 인증 호출 전에 다시 판정한다. **초점은 사용자가 누른 동기화에만 옮긴다**(성공은 h2, 그 밖은 오류 요약) — 자동 트리거의 완료는 status 문장 한 번이다. **진행 중 폴링은 2초·보이는 시간 3분 상한이고 대시보드 읽기는 전부 10초 상한이다** — 실패한 폴링은 따라가기를 끝내지 않고, `sync` 블록의 모르는 값은 결과를 거부하지 않고 안전하게 접힌다. **2.4의 동작 한 곳이 바뀌었다** — 어댑터가 전송 거절을 먼저 보게 되어 서명 호출에 JSON이 아닌 본문이 오면 첫 수집도 `NEEDS_REVIEW`다(시간 초과·429·5xx는 전과 같다). Story 2.11의 범위 밖: 장기 미동기화 선택지의 실행(`CATCH_UP`은 2.12, 새 계획은 2.13·2.15 — 지금은 두 선택지의 차이 설명만), 늦은 사건·정정의 정밀 재검증과 입력 신뢰도 `사용 불가`(2.12), Bybit 호출 예산·페이지 재시도(ERD §2.5), 연결 해제(2.16).

Story 2.12가 정밀 재검증과 장기 공백 이어가기를 더해 M2-3 검증 지점을 닫았다. **2.11의 작업 표·엔진에 목적 둘을 얹었다** — `sync_plan_sync_job.purpose`가 `SYNC`·`VERIFY`(정밀 검증)·`CATCH_UP`(기존 계획 계속)이고 사유는 일곱으로 닫혀 있다. HTTP는 `POST /api/dashboard/verification`(`{kind: PRECISION|CATCH_UP, planId, pointerVersion, calculationId}` + `Idempotency-Key`, 표시 중인 참조가 선택 token) 하나이고 사용자 사유는 서버가 정하는 `USER_REQUEST`·`GAP_SUSPECTED`·`LONG_GAP_CONTINUATION`뿐이며, 종료 준비·미인식·중복·운영 조사는 `SyncJobApi.admitSystem`으로만 받는다(호출자는 2.13·2.14와 Epic 3). admission이 공식 계보(`base_candidate_id` 사슬)의 증명 구간을 합쳐 재사용 구간과 대상 구간(마지막 미확정 `[verified−7일, verified]`·공백·설명되지 않는 저장 사건 ±24시간·운영 지정·새 구간)을 고정하고(`VerificationPlanner`), Worker는 대상만 7일 창으로 다시 읽어 Bybit `id`+`semantic_hash`로 `SAME`·`NEW`·`MISSING`·`SUPERSEDED`·`CONFLICT`를 가른 뒤 `(t0, tc]` 전체를 2.11과 같은 판정·환산·계산·원자 발행으로 새 버전으로 만든다. CATCH_UP은 `[verified−7일, tc]`와 이상 구간을 처리하고 발행 직전 `tcFinal`로 최근 7일과 `totalEquity`를 다시 읽어 한 번 발행하며, 다시 claim된 VERIFY·CATCH_UP은 수집 단계까지 완전한 창을 건너뛰고 이어 달린다(최대 2회). 처음 도입한 것: `V12__plan_verification.sql`(목적·사유·`initial_tc`·재개 수, page 단위 review 표시, 구간·대조·후보 제외·계산 변경 네 표와 기존 증분 버전의 변경 행 backfill), source의 `BybitCallBudget`(ERD §2.5 — 자격정보당 5/s·공개 10/s·동시 수집 2·잔여 헤더 대기·수집 page 재시도 2·10·30초+jitter·HTTP 403 10분 회로, 속성 `BOKRILOG_BYBIT_*` 여덟), 최초·증분·검증이 공유하는 수집 핵심 `collectWindows`(`collectIncrementalInputs`는 지웠다), 대시보드 `verification`·`change` 블록, 근거의 `변경 근거`, 조정 이력 행의 버전 변경 표지, 화면의 `VerificationControl`·`VersionChangeNote`·`EvidenceChange`와 장기 공백의 `기존 계획 계속` 버튼. 고정한 계약 다섯: **공식이 아니던 저장 사건이 재수집에서 보이지 않으면 제외 목록에 남기고 발행한다** — 2.11의 풀 수 없는 검토 루프가 이것으로 끊기고 이후 후보가 제외를 이어받는다. 공식 사건이 사라지면 `SOURCE_EVENT_MISSING` 검토이며 SYNC도 같은 규칙이다(2.11 동작 변경). **지속성 검증 검토는 그 기준 계산 동안 끈적인다** — 같은 기준에서 검증이 사라진 사건·충돌·조회 범위 불능·판정/환산의 영구 사유로 끝나면 재요청은 409 `VERIFICATION_NOT_USEFUL`, SYNC는 `UNAVAILABLE`/`REVIEW_HELD`로 작업을 만들지 않고, 입력 신뢰도의 다음 행동은 `NONE`이다. 동기화 검토의 다음 행동은 정밀 검증을 실제로 받을 수 있을 때만 `VERIFY`다. **모든 증분 발행이 직전 버전과의 흐름 차이를 같은 트랜잭션에 쓴다**(`sync_plan_calculation_change`, 늦은 추가·값 변경·제외를 나눠 센다) — 변경 행이 흐름 차이와 맞지 않으면 근거·이력은 503이고 대시보드 표지는 `NONE`으로 접힌다. **검증도 늘 새 `tc`까지 가서 v+1을 낸다** — "변경 없음"은 늦은 흐름 변경이 없다는 뜻이고 `필요 구간 검증 완료`로 보인다. **HTTP 403은 IP 차단 회로, retCode `10006`은 재시도 대상이다**(2.1·2.4 동작 변경, ERD §2.5). 리뷰 뒤 SPEC frozen의 "CATCH_UP 대상은 둘뿐"을 보완해 CATCH_UP도 이상 구간을 읽는다(오래된 설명되지 않는 사건이 있으면 장기 공백에서 풀 수단이 없었다). PR #54의 CI가 드러낸 `OnboardingScreen` 재인증 시험 실패는 탭 재표시 리스너를 layout effect로 옮겨 고쳤다 — passive effect를 돌리는 Scheduler의 `setImmediate`와 Testing Library의 `setTimeout(0)` 순서를 Node가 보장하지 않아, 부하 아래에서 리스너보다 이벤트가 먼저 갔다. Story 2.12의 범위 밖: 계획 종료와 완료 결과(2.13·2.14), 새 계획(2.15), 연결 해제(2.16), 운영자 HTTP 표면(Epic 3), 예산·회로의 다중 인스턴스 공유(3.8).

프런트엔드는 이제 `/api` 호출 열둘(Bybit 연결의 상태 조회·준비·확인·취소가 더해졌다. `GET /api/auth/session`, `POST /api/auth/logout`, `POST /api/auth/reauthenticate/<제공자>`, `GET /api/auth/link`, `POST /api/auth/link/<제공자>`, 그리고 해제의 준비·`/confirm`·`/cancel` 셋)과 고정 순서(Google → 네이버 → 카카오)의 제공자 버튼 셋, 로그아웃 버튼, 두 인증 화면이 공유하는 재인증 패널, 설정 화면을 갖는다. 라우터 의존은 여전히 없고 `App.tsx`의 닫힌 경로 맵이 그 역할을 한다 — 내부 경로는 여전히 셋이며(Bybit 연결은 `/settings` 안에서 연다) 그 집합이 **다섯 곳**(그 맵, Nginx 허용 목록, 서버 `ALLOWED_DESTINATIONS`, 세션 목적지 맵, 복귀 목적지 집합)에서 같아야 하고 타입 검사가 잡아 주는 것은 하나뿐이다. `/api/status`는 서버 경계를 확인하는 엔드포인트이며 화면이 부르지 않는다.

미룬 결정은 96건이고 항목과 판정은 `deferred-work.md`와 해소 원장이 소유한다(2026-09-09 기준 해소 14·부분 해소 4·미해소 32이고 그 뒤 Story 1.8이 다섯, 1.9가 넷, 1.10이 셋, 1.11이 둘을 더했다). Epic 1 회고가 이 목록을 훑었고 **원장이 네 라운드째 멈춰 있다는 것**이 액션 8번이다 — 항목 51~64가 판정을 받지 못했다. 아래는 지금 코드에 닿는 미해소만 고른 선택적 열거다: artifact `run_attempt`와 재실행 복구의 충돌, 통합 job의 `npm ci`·Chromium 설치 중복, 백엔드 포매터·정적 분석 게이트를 소유할 스토리와 그 게이트가 함께 정할 import 정렬, nginx 컨테이너 권한 강화, `identity` 행 잠금 대기 상한, Testcontainers 컨테이너 수명과 컨텍스트 캐시 분리, `verify-same-origin.sh` 마이그레이션 호출의 시간 상한, `identity`가 `spring_session`에 직접 닿는 결합, 두 인증 화면의 중복과 그 레이아웃 CSS 복제, `lint:css` 범위 단정이 새 CSS 모듈을 놓치는 것, Windows 전용 결함을 CI가 보지 못하는 공백, 계층을 넘나드는 계약 리터럴의 중복을 Docker 없는 검사로 묶는 문제, 그리고 Story 1.6이 더한 일곱 — 그중 무게 있는 둘은 **제공자 이름 목록이 열 곳 넘게 복제됐고 그 목록들이 서로 같은지 보는 검사가 없다는 것**과 **한 제공자만 구성된 스택이 HTTP·화면 층에서 검증되지 않아 실제 제공자 등록이 하나씩 들어오는 시점에 사용자가 원본 오류 JSON을 보는 창이 열린다는 것**이다. 어느 것도 Epic 2 착수를 막지 않는다. 행 잠금 대기 상한과 `spring_session` 결합은 Story 1.6이 같은 경로를 지나며 보고 **재차 미뤘다**(근거는 해소 원장 항목 23·27). Story 1.7이 여섯을 더했다 — 제공자별 왕복 시험 축의 3차 복제, `credentials()` fixture 3중 복제, 스텁 기동 guard의 손으로 쓴 OR 사슬, 계층 **안** 목록 중복과 `SUPPORTED_REGISTRATION_IDS` 등가 단정 부재, 카카오 심볼 추출 산출물 부재, 그리고 `OnboardingScreen.test.tsx`가 부하 아래 간헐 실패하는 것이다. Story 1.8이 다섯을 더했다 — `dev_subject` 파라미터 이름의 계층 간 리터럴 중복, `ReauthPanel.module.css`가 복제한 제공자 버튼 규칙, 구성되지 않은 제공자로 무장할 때 최상위 내비게이션이 원본 오류 JSON에 닿는 것(미해소 항목 40의 두 번째 진입점), 절대 수명 끝에 성공한 재인증이 몇 분 뒤 만료로 끝나는 것, 그리고 두 훅의 중복 제출 가드가 React 상태를 읽어 같은 tick의 두 번째 호출을 막지 못하는 것이다. Story 1.9가 넷을 더했고 **넷 다 첫 소비자 스토리가 같은 경로를 지나며 함께 정할 일이다** — 소비된 허가 행의 보존 상한 부재, 허가 포트가 `MockHttpSession`으로만 구동되고 실제 Spring Session 세션으로 도는 층이 없는 것, 같은 귀속의 동시 발급이 살아 있는 허가를 둘 남길 수 있는 창, 그리고 사용자 범위로 좁힌 sweep이 돌아오지 않는 사용자의 만료 행을 남기는 것이다. Story 1.10이 셋을 더했다 — `/settings`에서 시작한 재인증이 그 화면이 아니라 세션의 확정 목적지로 돌아오는 것(닫으려면 Story 1.8의 frozen 경계 "클라이언트는 목적지를 보내지 않는다"를 다시 협상해야 한다), 내부 경로 집합이 다섯 곳에 복제됐는데 등가 검사가 없는 것, 그리고 제공자 버튼 CSS 규칙의 세 번째 사본이다. Story 1.11이 둘을 더했다 — 제공자별 닫힌 맵이 화면 카피에 둘 더 생겼는데 값이 엉뚱한 제공자를 가리켜도 타입 검사가 잡지 못하는 것, 그리고 44×44 최소 조작 영역 규칙의 네 번째 CSS 사본이다. Story 2.6이 일곱을 더했다 — 캐시된 사건 분 candle이 다시 관측되지 않아 제공자 정정이 2.12의 재검증 전까지 보이지 않는 것, Bybit 서버 시간을 로컬 시계 상한 없이 기준시각으로 쓰는 것(skew 경계는 새 정책이라 Ask First), 만료 lease 재claim에 횟수 상한이 없는 것(2.4·2.5 작업과 같은 관용구), token bucket 부재로 settled 뒤 재접수가 공개 호출을 반복시키는 것, 세 상태 섹션의 실패 중 무한 폴링, 수집 섹션 `start()`의 409 문구 소거, 그리고 Windows Git Bash에서 `verify-same-origin.sh`가 한글 확인 문구를 CP949로 보내 902/959로 끝나는 것이다(Linux CI는 통과한다). Story 2.8이 다섯을 더했다 — 조회 훅 전체에 요청 시간 상한이 없어 끝나지 않는 응답이 다시 불러오기를 막는 것, 대시보드의 state·cause·nextAction 코드와 지표 순서가 백엔드 리터럴·SQL·프런트엔드 맵에 따로 적혀 대조 검사가 없는 것, 계획 없는 랜딩 화면에 live region이 둘인 것(첫 공식값 패널의 `aria-live`), 로그인 수단 해제의 취소가 **다른 탭**의 다음 준비와 서버에서 겹치면 새 무장·허가를 지우는 것(같은 탭은 PR #45가 막았다 — CI가 느린 러너에서 잡았다), 그리고 `InitialInputsCollection.test.tsx`의 한 시험이 부하 아래 간헐 실패하는 것이다. Story 2.10이 하나를 더했다 — `AdjustmentHistory.module.css`가 `CalculationEvidence.module.css`의 규칙을 옮겨 44×44 최소 조작 영역 규칙의 다섯 번째 CSS 사본이 된 것이다. Story 2.11이 넷을 더했다 — 매번 전체 재처리·새 버전 발행으로 늘어나는 저장량과 30초 lease를 넘는 단계 트랜잭션, 버전을 건너는 조정 값 변경이 2.10 이력에 보이지 않는 것, `검토 필요` 뒤 다음 행동이 풀 수 없는 재동기화인 루프(2.12 소유), 그리고 44×44 규칙의 여섯 번째 CSS 사본이다. PR #55(`spec-backend-test-speed.md`)가 넷을 더했다 — `FirstSnapshotTest`의 2초 대기가 스텁 지연 2초와 같아 늦은 워커가 쓰기를 시도하기 전에 단정이 끝날 수 있는 것, 느린 스텁 호출 중의 늦은 워커를 기다리는 고정 대기 묶음, 세 fence 시험이 DB fence를 확률적으로만 지나는 것, 그리고 compose 스택의 dispatch가 운영 기본 1초인 것이다. Story 2.12가 다섯을 더했다 — 44×44 규칙의 일곱·여덟 번째 CSS 사본, 현재가치 측정 시각과 `tc` 사이 간격의 상한 부재(재시도·마지막 7일 재수집이 넓힌다), 호출 예산·회로가 프로세스 전용이라 다중 인스턴스가 공유하지 않는 것(3.8), heartbeat `SKIP LOCKED`가 긴 page 트랜잭션 동안 lease 갱신을 건너뛰는 것, 그리고 V12 backfill과 배포 순서(3.8)다.

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
- 이 파일의 `## 현재 진행 지점`은 스토리가 머지될 때마다 갱신한다. `DEVELOPMENT.md`와 `epic-1-context.md`에 같은 진행 문장이 있으면 함께 맞춘다.
- `AGENTS.md`는 Codex가 읽는 진입 파일이며 이 파일을 대체하지 않는다. 규칙의 권위는 `CLAUDE.md` 하나이고 `AGENTS.md`는 오리엔테이션·실행 명령과 "제품 경로를 바꾸기 전에 `CLAUDE.md`를 읽으라"는 지시만 담는다. 규칙을 그리로 복사하지 않는다 — 두 벌이 되면 갈라지고 맞춰 줄 검사가 없다. `AGENTS.md`의 `bmad:context` 마커 안쪽은 `bmad-project-context` 스킬이 소유하며 refresh 때 교체된다.
