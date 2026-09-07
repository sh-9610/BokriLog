# BokriLog

Bybit 읽기 전용 연결로 복리 목표 계획을 기록·비교하는 개인 프로젝트다. BMAD 기획 산출물은 완료됐고 구현은 Epic 1을 진행 중이다. 제품 코드는 `frontend/`(React/Vite 정적 SPA), `backend/`(Spring Boot 최소 실행 기반), `deploy/`(Nginx 동일 출처 구성)로 이뤄져 있다. 절차와 명령의 상세는 `DEVELOPMENT.md`가 소유하고, 이 파일은 세션이 바로 일을 시작하는 데 필요한 규칙과 현재 지점만 담는다.

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
- **의존 추가는 SPEC이 승인한다.** Actuator·JPA·`spring-security-test`는 아직 없다. 각각을 필요로 하는 스토리의 SPEC이 목적과 함께 승인할 때 추가한다. PostgreSQL·Flyway는 `spec-1-3-identity-ownership.md`가, Spring Security OAuth2 Client·Spring Session JDBC는 `spec-1-4-google-sign-in.md`가 승인해 운영 classpath에, Spring Modulith·Testcontainers는 1.3 SPEC이 승인해 시험 classpath에 들어왔다. 경계 검증 라이브러리를 운영 jar에 싣지 않는다. 프런트엔드 런타임 의존 추가도 같다(개발·시험 전용 devDependency는 예외). 프런트엔드에는 라우터도 인증 SDK도 없다 — 경로 대응은 `App.tsx`의 닫힌 맵이고 로그인은 서버가 302로 처리한다.
- **`identity`가 계정 소유권·삭제 상태의 유일한 권위다.** 다른 패키지는 공개 application API(`AccountLifecycleApi`·`AccountAccessApi`·`AccountDeletionApi`)로만 접근하고 `identity.internal`이나 소유 테이블에 직접 닿지 않는다. `identity`에는 HTTP 표면이 없다. 접근 거부는 `AccessDecision.DENIED` 하나이며 타 사용자·미존재·삭제된 대상이 구분되면 안 된다. 상태는 `ACTIVE → DELETION_IN_PROGRESS → DELETED` 단조 전진뿐이다. `ModuleBoundaryTest`가 경계와 미노출을 강제한다.
- **스키마는 별도 migration 역할이 바꾼다(AD-12).** API 기동은 `Flyway::validate`만 하고 `migrate` 프로필만 실제로 마이그레이션한다(`SchemaValidationConfig`). 새 마이그레이션은 `backend/src/main/resources/db/migration/V<n>__<이름>.sql`로 더하고 이미 적용된 파일은 고치지 않는다.
- **동일 출처.** Nginx는 `/api`만 백엔드로 프록시한다. 나머지는 정적 파일이거나 404이며 어떤 경로도 외부 주소로 프록시하지 않는다. 와일드카드 SPA 폴백은 두지 않는다. 화면이 여는 내부 경로는 허용 목록 location(`^/(onboarding|dashboard)$`)이 이름으로 열고, 그 집합은 `frontend/src/App.tsx`의 경로 맵과 같아야 한다. 라우트를 더할 때는 두 곳을 함께 고친다. access log는 쿼리 문자열을 남기지 않는다 — `$request`를 그대로 쓰면 OIDC 콜백의 `code`·`state`가 로그에 실린다.
- **인증 표면은 루트 패키지가 소유한다.** `identity`는 HTTP를 가질 수 없으므로 Google OIDC 콜백·세션 정책·CSRF는 `com.bokrilog`의 `AuthSecurityConfig`·`AuthSessionService`·`AuthFlowHandlers`·`AuthSessionController`가 맡고, 계정 조회·매핑·판정은 `identity` 공개 API만 쓴다. 경로는 `/api/auth/authorize/<provider>`·`/api/auth/callback/<provider>`·`GET /api/auth/session` 셋이다. 검증(PKCE S256, 일회성 `state`·`nonce`, JWS·RS256·JWKS·`iss`·`aud`·`azp`·`exp`·`nonce`)은 Spring Security OAuth2 Client가 하고 손으로 만들지 않는다. 인증 표면은 `@ConditionalOnWebApplication(type = SERVLET)`로 API 프로세스에만 배선한다(`migrate` 역할에는 `HttpSecurity`가 없다).
- **세션.** Spring Session JDBC로 PostgreSQL에 둔다. 브라우저에는 `HttpOnly`·`Secure`·`SameSite=Lax` 불투명 쿠키 하나뿐이고 로그인 성공 시 세션 ID를 회전한다. 세션이 담는 값은 내부 `user_id` 하나이며 ID Token·액세스·리프레시 토큰은 검증 직후 버린다. CSRF 토큰과 동일 출처 `Origin` 검증은 `/api/auth/` 아래의 상태 변경 요청에만 건다 — 전역으로 켜면 Story 1.2·1.3이 고정한 404·405가 403으로 뒤집힌다. 복귀 목적지는 서버가 가진 닫힌 허용 목록(`/onboarding`·`/dashboard`)뿐이다.
- **오류 응답 한 가지.** `{"code":…,"message":…,"correlationId":…}`. Spring과 Nginx가 같은 모양을 돌려주고 `code`는 안정적인 machine code다. 내부 경로·스택·프레임워크 기본 오류 본문·환경 변수 이름은 응답과 로그 어디에도 남기지 않는다.
- **보안 헤더는 Nginx만.** CSP를 비롯한 보안 응답 헤더는 `deploy/nginx/bokrilog.conf`가 유일한 정의다. `<meta>` CSP를 쓰지 않고 Spring Security의 기본 헤더 writer도 끈다(`AuthSecurityConfig`). 켜 두면 같은 헤더가 두 번 나가고 헤더를 전문 비교하는 검증이 먼저 깨진다.
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

`backend/`는 Java 25 + Spring Boot 4.1.1 + Gradle wrapper이고 모듈 경계는 Spring Modulith 2.1.1이 시험에서 검증한다(운영 classpath에는 없다). Story 1.3부터 PostgreSQL 18을 쓰고 Story 1.4부터 Spring Security OAuth2 Client·Spring Session JDBC가 있다. HTTP 엔드포인트는 `GET /api/status`, `GET /api/auth/session`, 그리고 Spring Security 필터가 받는 `/api/auth/authorize/<provider>`·`/api/auth/callback/<provider>`다. 상태·인증 컨트롤러는 루트 패키지 `com.bokrilog`에 있고 첫 모듈 패키지는 `com.bokrilog.identity`다. 컴파일 경고는 `-Werror`로 실패다.

제공자 자격정보는 여전히 필요 없다. `BOKRILOG_PUBLIC_BASE_URL`·`BOKRILOG_GOOGLE_CLIENT_ID`·`BOKRILOG_GOOGLE_CLIENT_SECRET` 세 이름 중 하나라도 비면 Google 등록이 만들어지지 않고 인증 시작이 공통 오류 계약으로 fail-closed다(`DevOidcStubProfileTest`가 그 계약을 지킨다). 정상 흐름의 결정성은 `dev-oidc` 프로필에서만 사는 local OIDC 스텁이 만든다. 스텁 논리는 `DevOidcStub`이 소유하고 표면은 두 곳이다 — 개발·CI 스택은 `DevOidcStubConfig`가 `/api/dev-oidc/**`에 올리고(브라우저와 백엔드가 같은 origin에서 도달해야 성공 경로를 브라우저 층에서 검증할 수 있다), 백엔드 시험은 `OidcStubConfiguration`이 루프백 HTTP 서버에 올린다. 스텁을 두 번 만들지 않는다. 그 프로필이 실제 Google 등록과 함께 활성이면 기동을 거부하고, 실제 Google 왕복은 Epic 1의 **실연동 미검증** 게이트가 소유한다. 백엔드가 자기 스텁에 닿는 주소는 `BOKRILOG_DEV_OIDC_INTERNAL_BASE_URL`이며 브라우저가 보는 origin과 다른 값이다.

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
- 통합 job timeout 15분은 CI-PLAN §8의 시작값 5분과 다르다. `backend-quality`도 Story 1.3에서 10분 → 15분으로 올렸다(Testcontainers 콜드 실행). 두 값 모두 실측이 몇 회 더 쌓인 뒤 CI-PLAN 새 버전으로 보정하기로 했고 아직 발행 전이다.
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

기획은 완료됐다. `epics.md`는 `implementationReadinessStatus: ready-for-sprint-planning`이고 구현 계약은 `ERD-1.0.0`이 권위 기준이다. `sprint-status.yaml`은 2026-09-02에 생성됐고 첫 스프린트는 Story 1.1~1.5(진입 화면 → 동일 출처 셸 → 소유권·삭제 상태 → Google 로그인 → 로그아웃), 1.6~1.7은 스트레치다.

| 스토리 | 상태 | 근거 |
|---|---|---|
| 1.1 진입 화면 | done | `spec-1-1-entry-screen.md`, PR #3 |
| 1.2 동일 출처 셸 | done | `spec-1-2-same-origin-shell.md`, PR #9 머지 커밋 `d9a1fc2`, PR #10 |
| 1.3 소유권·삭제 상태 | done | `spec-1-3-identity-ownership.md`, PR #12 머지 커밋 `89b8948` |
| 1.4 Google 로그인 | done | `spec-1-4-google-sign-in.md`, PR #14 머지 커밋 `2f300f6` |
| 1.5 로그아웃·세션 만료 | backlog — **다음 착수** | `epics.md` Story 1.5, `epic-1-context.md` |

Story 1.3이 `identity` 모듈, PostgreSQL 18 마이그레이션, 소유권·삭제 상태 판정 계약, 해당 데이터의 삭제 경로와 시험을 들였다. 이 스토리가 처음 도입한 것: 첫 모듈 패키지와 Spring Modulith 경계 검사, PostgreSQL Testcontainers, 마이그레이션(expand 우선, startup은 schema 검증만), 두 사용자·동시성·실패 주입 시험.

Story 1.4가 그 계약 위에 Google OIDC와 서버 세션을 올렸다. 처음 도입한 것: Spring Security OAuth2 Client와 Spring Session JDBC, `V2__spring_session.sql`, 루트 패키지의 인증 표면 네 클래스, `AccountLifecycleApi.findUserIdBySocialIdentity`, `dev-oidc` 프로필의 결정적 local OIDC 스텁과 인증 시험 여섯 묶음, Nginx 경로 허용 목록, 프런트엔드의 경로 맵·Google 버튼·온보딩·로그인 상태 화면. `verify-same-origin.sh`와 `e2e/auth-entry.spec.ts`가 그 스텁으로 성공 왕복을 실제로 지난다. `linkSocialIdentity`·`decideSessionCreation`이 계정 쪽 접점이며 새 소유권 판정을 만들지 않았다. `erasePayload`가 인증 매핑과 함께 그 계정의 세션 행도 지운다.

Story 1.4의 범위 밖: 로그아웃·세션 만료·의심 세션 차단(1.5), 네이버·카카오(1.6·1.7), 재인증과 action grant(1.8·1.9), 로그인 수단 연결·해제(1.10·1.11). 인증으로 막는 경로는 아직 없다 — 이 스토리는 사용자 소유 자원을 만들지 않았고, `authorizeHttpRequests`는 전부 `permitAll`이다. 보호는 그 자원이 생기는 스토리가 자원과 함께 더한다.

프런트엔드는 이제 `/api` 호출 하나(`GET /api/auth/session`)와 동작하는 Google 버튼을 갖는다. 라우터 의존은 여전히 없고 `App.tsx`의 닫힌 경로 맵이 그 역할을 한다. `/api/status`는 서버 경계를 확인하는 엔드포인트이며 화면이 부르지 않는다.

미룬 결정(`deferred-work.md`, 해소 원장 참조): artifact `run_attempt`와 재실행 복구의 충돌, 통합 job의 `npm ci`·Chromium 설치 중복, 백엔드 포매터·정적 분석 게이트를 소유할 스토리, nginx 컨테이너 권한 강화, 그리고 Story 1.4가 더한 셋 — `identity`가 `spring_session`에 직접 닿는 결합, 로그인 상태 화면 두 개의 중복, 백엔드 import 정렬. 어느 것도 Story 1.5 착수를 막지 않는다.

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
