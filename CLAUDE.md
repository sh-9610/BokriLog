# BokriLog

Bybit 읽기 전용 연결로 복리 목표 계획을 기록·비교하는 개인 프로젝트. BMAD 기획 산출물이 완료됐고, 제품 코드는 `frontend/`의 React/Vite 정적 SPA, `backend/`의 Spring Boot 최소 실행 기반, `deploy/`의 Nginx 동일 출처 구성으로 이뤄져 있다.

## 언어

대화와 산출 문서 모두 한국어다. BMAD 중앙 설정(`_bmad/core/config.yaml`)이 `communication_language: Korean`, `document_output_language: Korean`으로 고정되어 있으므로 이 값을 따른다.

## BMAD 스킬 실행

이 저장소의 작업은 `.claude/skills/bmad-*` 스킬로 진행한다. 각 스킬의 `SKILL.md`는 렌더링 명령 하나만 담고 있다.

- `SKILL.md`가 지시하는 `render_skill.py` 명령을 그대로 한 번 실행하고, 출력된 절대경로 `workflow.md`를 읽어 따른다.
- 렌더링이 실패하면 명령 출력을 보고하고 중단한다. `.claude/skills/` 아래의 워크플로 원본을 직접 읽어 대신 실행하지 않는다.
- `.claude/skills/`(Claude Code)와 `.agents/skills/`(Codex)는 같은 설치본에서 생성된 동일 파일이다. 스킬 파일을 손으로 고치지 않는다. 동작을 바꿀 때는 `_bmad/custom/` 또는 `bmad-customize` 스킬을 사용한다.

## 프런트엔드 실행

`frontend/`는 React 19.2.x + Vite 8.2.x + TypeScript strict 정적 SPA다. 백엔드·DB·제공자 자격정보 없이 단독으로 빌드·실행·시험이 통과한다. 명령은 저장소 루트에서 `--prefix frontend`로 실행한다.

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

`test:e2e`는 `vite build` 뒤 Playwright가 산출물을 `preview`로 띄운다. 빌드가 이미 끝난 CI에서는 빌드를 건너뛰는 `test:e2e:run`을 쓴다. 처음 한 번은 `npm --prefix frontend exec -- playwright install chromium`이 필요하다. `PLAYWRIGHT_BASE_URL`을 주면 자체 `preview` 서버를 띄우지 않고 그 origin을 검증한다. 동일 출처 통합에서 같은 시험 본문을 Nginx에 대고 재사용하는 지점이다.

디자인 토큰 정본은 `frontend/src/styles/tokens.css`이며 `DESIGN.md` frontmatter의 평면 이름을 그대로 옮긴 것이다. 색·크기 리터럴을 컴포넌트에 직접 쓰지 않는다. stylelint가 색·타이포그래피·모서리·간격에서 이를 강제하고, ESLint(타입 인지)·Prettier와 함께 CI `frontend-quality`의 필수 단계다. 인라인 예외는 `-- 이유`가 붙은 disable 주석만 허용한다. 자동 정리는 `npm --prefix frontend run lint -- --fix`, `npm --prefix frontend run lint:css -- --fix`, `npm --prefix frontend run format`이다. 화면 카피는 `frontend/src/content/`가 소유하고 화면과 시험이 같은 문자열을 참조한다. 외부 폰트·CDN·분석·광고 SDK와 브라우저 저장소 쓰기는 금지이며 시험이 이를 고정한다. 자세한 절차는 `DEVELOPMENT.md`의 `## 프런트엔드` 절에 있다.

## 백엔드·동일 출처 실행

`backend/`는 Java 25 + Spring Boot 4.1.1 + Gradle wrapper다. 데이터베이스·자격정보 없이 기동하며 엔드포인트는 `GET /api/status` 하나다. JDK 25가 필요하고 toolchain 자동 프로비저닝은 꺼져 있다. macOS는 `brew install openjdk@25` 뒤 사용자 홈에 최초 1회 심볼릭 링크를 걸면 `/usr/libexec/java_home -v 25`와 Gradle이 함께 찾는다(sudo 불필요). Windows는 `winget install EclipseAdoptium.Temurin.25.JDK`로 끝난다. PATH의 `java -version`이 17로 남아 있어도 정상이다. Gradle toolchain이 `languageVersion`으로 25를 고른다. `JAVA_HOME`을 셸에서 바꿔 두지 않는다.

```bash
mkdir -p ~/Library/Java/JavaVirtualMachines
ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk ~/Library/Java/JavaVirtualMachines/openjdk-25.jdk
backend/gradlew --project-dir backend build
```

`deploy/`는 Nginx가 `frontend/dist/`를 origin 루트에서 제공하고 `/api`만 백엔드로 프록시하는 개발·CI 공용 compose 구성이다. 두 산출물을 먼저 빌드한 뒤 검증 스크립트 하나로 I/O 계약 전체를 확인한다. 바깥에 열리는 포트는 Nginx 하나(`127.0.0.1:18080`)다.

```bash
deploy/compose/verify-same-origin.sh
```

오류 응답은 `{"code":…,"message":…,"correlationId":…}` 한 가지뿐이고 Spring과 Nginx가 같은 모양을 돌려준다. CSP를 비롯한 보안 응답 헤더는 `deploy/nginx/bokrilog.conf`가 유일한 정의이며 `<meta>` CSP를 쓰지 않는다. 내부 경로·스택·프레임워크 기본 오류 본문·환경 변수 이름은 응답과 로그 어디에도 남기지 않는다. Spring Modulith·Actuator·Spring Security·PostgreSQL 의존은 아직 없고 추가는 그것을 필요로 하는 스토리의 SPEC이 승인한다. 자세한 절차는 `DEVELOPMENT.md`의 `## 백엔드`·`## 동일 출처 실행` 절에 있다.

## Python 도구 실행

BMAD Python 스크립트는 저장소 루트에서 `uv run --managed-python --no-cache`로 실행한다. 이 명령은 BMAD 기획 도구 전용이며 제품 런타임과 무관하다. Windows에서 `python3`는 존재하지 않으므로 직접 호출하지 않는다.

```bash
uv run --managed-python --no-cache _bmad/scripts/resolve_config.py --project-root "$PWD"
```

셸 스크립트는 POSIX 셸을 요구한다. Windows에서는 Git Bash로 실행한다. 환경이 의심되면 먼저 점검한다.

```bash
./scripts/check-bmad-env.sh
```

## 산출물 위치

- `_bmad-output/planning-artifacts/` — 브리프, PRD, UX 설계, 아키텍처, `epics.md`, `EPIC-IMPLEMENTATION-READINESS.md`
- `_bmad-output/implementation-artifacts/` — SPEC, 스프린트 상태, 스토리, 리뷰, `deferred-work.md`
- `_bmad/render/` — 스킬 렌더링 스냅샷. 생성물이며 커밋하지 않는다.

## 현재 진행 지점

기획은 완료됐다. `epics.md`는 5개 에픽·45개 스토리가 승인된 상태이고 `implementationReadinessStatus: ready-for-sprint-planning`이다. 구현 계약은 `EPIC-IMPLEMENTATION-READINESS.md`의 `ERD-1.0.0`이 권위 기준이다.

`sprint-status.yaml`은 2026-09-02에 생성됐다(5 에픽·45 스토리). 첫 스프린트는 Story 1.1~1.5(진입 화면 → 동일 출처 셸 → 소유권·삭제 상태 → Google 로그인 → 로그아웃)이며 1.6~1.7은 스트레치다.

Story 1.1(진입 화면)은 `spec-1-1-entry-screen.md`대로 구현했다. `frontend/`에 React/Vite 정적 SPA, `DESIGN.md` 토큰 시스템, 로그인 없이 열리는 진입 화면, Vitest·Playwright 시험이 있다.

Story 1.2(동일 출처 셸)는 `spec-1-2-same-origin-shell.md`대로 구현했고 아직 **리뷰 중**이다(SPEC frontmatter `status: in-review`). `backend/`에 Spring Boot 최소 실행 기반과 `GET /api/status`, `deploy/`에 Nginx 동일 출처 구성과 검증 스크립트가 있고, CI는 `changes → frontend-quality·backend-quality → same-origin-integration → ci-required` 구조로 바뀌었다.

다만 이 CI 구조는 GitHub에서 한 번도 실행된 적이 없다. 지금까지의 근거는 로컬 실행(`deploy/compose/verify-same-origin.sh`, `.github/scripts/test-ci-scripts.sh`, 프런트엔드 시험)뿐이다. 러너에서만 드러나는 것 — 이미지 빌드 시간, `playwright install --with-deps`의 apt 설치, job 사이 artifact 주고받기, job 시간 예산 — 은 이 스토리의 PR이 green이 되어야 확인된다. Story 1.3(`identity` 모듈·PostgreSQL 마이그레이션·소유권/삭제 상태 계약)은 그 뒤에 착수한다.

프런트엔드 **애플리케이션 코드**에는 아직 라우터도 `/api` 호출도 동작하는 소셜 버튼도 없다. `/api/status`는 서버 경계를 확인하는 엔드포인트이며 화면이 부르지 않는다. 진입 화면의 "문서·번들 외 요청 0건" 시험이 이를 고정한다.

제품 범위 충돌 시 우선순위는 PRD의 제품 범위, 아키텍처의 불변식, `ERD-1.0.0`의 하위 구현 계약 순이다. 결정을 바꿀 때는 기존 문서를 덮어쓰지 않고 새 버전으로 발행한다.

## 브랜치·커밋

git flow를 따른다. `main`은 릴리스만 받고 직접 커밋하지 않는다. `develop`이 통합 브랜치이며 모든 토픽 브랜치는 `develop`에서 분기한다.

병합은 GitHub PR의 `Create a merge commit`으로만 한다. `develop`·`main`에 직접 push하지 않는다. 로컬에서 병합해 push하면 필수 검사 `ci-required`가 병합을 막을 기회를 잃고 사후 알림으로만 남는다. PR 머지 커밋은 `--no-ff`와 같은 2-부모 커밋이라 토픽 브랜치 경계가 이력에 남는다. `Squash and merge`·`Rebase and merge`는 그 경계를 지우므로 쓰지 않는다. 병합 후 토픽 브랜치는 삭제한다.

- `feature/<에픽>-<스토리>-<slug>` — 스토리 구현. 예: `feature/1-1-entry-screen`
- `chore/<slug>` — 도구·환경·저장소 정비
- `docs/<slug>` — 기획 산출물·문서
- `fix/<slug>` — `develop` 위 결함 수정
- `hotfix/<slug>`, `release/<version>` — 릴리스 이후·릴리스 준비에만 사용

slug는 짧은 영문 소문자 kebab-case다. 한글 브랜치명은 Windows·macOS 터미널 인코딩과 URL에서 문제를 일으키므로 쓰지 않는다. 도구 이름(`claude` 등)을 접두사로 쓰지 않는다. 커밋 메시지는 Conventional Commits의 영문 소문자 subject를 따른다. 커밋·푸시는 사용자가 요청할 때만 한다.

## 주의

- BMAD 인스톨러를 다시 실행하면 `_bmad/scripts/`의 로컬 수정이 업스트림 원본으로 덮어쓰인다. 절차와 복구 방법은 `DEVELOPMENT.md`에 있다.
- 제품 기술 스택은 `ARCHITECTURE-SPINE.md`가 권위 기준이다(Java/Spring/PostgreSQL, React/Vite). 이 저장소의 Python 환경은 기획 도구 전용이며 제품 런타임과 무관하다.
