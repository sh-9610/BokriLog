# 개발 환경

이 저장소의 BMAD 도구는 운영체제 기본 `python3`를 직접 사용하지 않는다. BMAD 스크립트가 요구하는 Python 3.11 이상을 `uv`가 선택하거나 내려받아 실행한다. 제품 애플리케이션의 기술 기준선은 `ARCHITECTURE-SPINE.md`에서 Java/Spring/PostgreSQL과 React/Vite로 확정되었으며, 이 Python 환경은 제품 런타임과 무관한 기획 도구 전용이다. Story 1.1에서 `frontend/`의 React/Vite SPA와 그 lockfile이 생겼고, Story 1.2에서 `backend/`의 Spring Boot 최소 실행 기반과 `deploy/`의 동일 출처 구성이 생겼다. Story 1.3에서 PostgreSQL 18과 첫 Spring Modulith 모듈(`identity`)이 들어왔고, 그래서 **백엔드 시험은 이제 Docker를 전제한다**(Testcontainers가 실제 PostgreSQL을 띄운다). image digest 고정은 아직 생성 전이다.

## 최초 준비

1. `uv`를 설치한다. Windows에서는 `winget install astral-sh.uv`, macOS에서는 Homebrew의 `brew install uv`를 사용할 수 있다. 이 환경은 `uv 0.12.5`에서 검증했으며, 그보다 오래된 버전은 별도 검증이 필요하다.
2. 점검 스크립트는 POSIX 셸을 요구한다. Windows에서는 Git Bash에서 실행한다.
3. 저장소 루트에서 아래 명령으로 환경을 검증한다.

```bash
./scripts/check-bmad-env.sh
```

점검은 다음을 확인한다.

- `uv` 명령 사용 가능 여부
- 저장소 내부 캐시 경로(`./.uv-cache`)
- `uv` 관리 Python 3.11 이상 사용 여부
- BMAD 중앙 설정 해석 가능 여부
- BMAD 스크립트의 UTF-8 stdout 설정 잔존 여부
- 설치 manifest에 `codex`와 `claude-code` 타깃이 모두 등록되어 있는지
- 두 타깃의 스킬 트리가 서로 동일한지. 차이가 있으면 파일 목록·내용 차이와 줄바꿈 차이를 구분해 보고한다
- BMAD 스킬 렌더링이 두 타깃 모두에서 동작하는지
- BMAD 메모리 로그 도구 실행 가능 여부

## 두 기기 개발 (Windows·macOS)

이 저장소는 Windows와 macOS 양쪽에서 개발한다. 기기 간 차이는 저장소 파일로 고정하고 각 기기의 Git 로컬 설정에 의존하지 않는다.

줄바꿈은 `.gitattributes`가 결정한다. 텍스트 파일은 인덱스와 작업 트리 모두 LF로 통일하고, 셸 스크립트와 빌드 wrapper(`gradlew`, `mvnw`)는 LF로 명시 고정한다. `*.bat`과 `*.cmd`만 CRLF를 유지한다. `core.autocrlf`는 기기별 로컬 설정이라 공유되지 않으므로 신뢰하지 않는다. 셸 스크립트에 CR이 섞이면 bash가 `$'\r': command not found`로 즉시 실패한다.

새 기기에서 clone한 뒤에는 위 `## 최초 준비`만 수행하면 된다. `.agents/skills/`와 `.claude/skills/`가 저장소에 포함되어 있으므로 인스톨러를 다시 실행하지 않아도 점검이 통과한다.

`scripts/check-bmad-env.sh`는 bash 3.2와 호환되며 `sed -i`, `grep -P`, `readlink -f` 같은 GNU 전용 플래그를 쓰지 않는다. macOS 기본 bash에서 수정 없이 동작한다.

작업 트리에 CRLF가 섞인 파일이 남아 있다면 `.gitattributes` 도입 이전에 체크아웃된 파일이다. 인덱스는 이미 LF이므로 커밋 결과와 다른 기기의 clone에는 영향이 없다. 정리하려면 `git status`가 깨끗한 상태에서만 아래를 실행한다. 미커밋 변경이 있으면 사라진다.

```bash
git rm --cached -r . && git reset --hard
```

시험은 경로 구분자를 가정하지 않는다. `path.relative`·`path.join`은 플랫폼 구분자를 돌려주므로 그 결과를 정본 경로 문자열과 직접 비교하면 한쪽 기기에서만 실패한다. 정본은 언제나 POSIX 형태로 적고 도구가 돌려준 경로를 `split(sep).join('/')`로 옮겨 비교한다(`frontend/lint-policy.test.ts`의 `toPosixRelative`가 그 형태다). CI는 `ubuntu-24.04`만 돌아 이 종류의 결함을 구조적으로 보지 못하므로, Windows에서만 빨간 시험을 그 기기의 문제로 넘기지 않는다.

저장소를 두는 경로에는 비ASCII 문자를 피한다. Git 자체에는 영향이 없지만 Java·Gradle 도구가 일부 환경에서 경로 인코딩 문제를 일으킬 수 있다.

## 브랜치 전략

git flow를 따른다.

| 브랜치 | 역할 | 분기 원본 → 병합 대상 |
|---|---|---|
| `main` | 릴리스 상태. 직접 커밋하지 않는다 | — |
| `develop` | 통합 브랜치. 모든 토픽 브랜치의 병합 대상 | `main` → `main`(릴리스 시) |
| `feature/<에픽>-<스토리>-<slug>` | 스토리 구현. 예: `feature/1-1-entry-screen` | `develop` → `develop` |
| `chore/<slug>` | 도구·환경·저장소 정비 | `develop` → `develop` |
| `docs/<slug>` | 기획 산출물·문서 | `develop` → `develop` |
| `fix/<slug>` | `develop` 위 결함 수정 | `develop` → `develop` |
| `hotfix/<slug>` | 릴리스된 `main`의 긴급 수정 | `main` → `main` + `develop` |
| `release/<version>` | 릴리스 준비. 공개 베타 시점부터 사용 | `develop` → `main` + `develop` |

- 병합은 GitHub PR에서 `Create a merge commit`으로만 한다. `develop`·`main`에 직접 push하지 않는다. 로컬에서 병합해 push하면 필수 검사 `ci-required`가 병합을 막을 기회를 잃고 이미 들어간 코드에 대한 사후 알림으로만 남는다.
- PR 머지 커밋은 `--no-ff`와 같은 2-부모 커밋이며 메시지만 `Merge pull request #N from ...` 형태로 바뀐다. 토픽 브랜치 경계는 그대로 이력에 남는다. `Squash and merge`·`Rebase and merge`는 그 경계를 지우므로 쓰지 않는다. 병합 후 토픽 브랜치는 삭제한다.
- slug는 짧은 영문 소문자 kebab-case다. 한글 브랜치명은 두 운영체제의 터미널 인코딩과 GitHub URL에서 문제를 일으키므로 쓰지 않는다. 도구 이름(`claude` 등)을 접두사로 쓰지 않는다.
- 스토리 브랜치의 번호는 `sprint-status.yaml` 키의 앞부분(`1-1`, `2-14`)과 같다.
- 커밋 메시지는 Conventional Commits를 따른다. subject는 영문 소문자, 유형은 `feat` `fix` `chore` `docs` `refactor` `test`.

## 프런트엔드 (`frontend/`)

React 19.2.x + Vite 8.2.x + TypeScript strict 정적 SPA다. Story 1.1 시점에는 백엔드·데이터베이스·소셜 제공자 자격정보 없이 단독으로 빌드·실행·시험이 통과한다. 허용 버전 계열은 `ARCHITECTURE-SPINE.md`의 Stack 표가, 실제 설치 버전은 `frontend/package-lock.json`이 소유한다.

### 준비

Node 24가 필요하다. 버전은 `frontend/.nvmrc`가 고정하고 `frontend/package.json`의 `engines.node`가 `^24.0.0`으로 선언한다. `frontend/.npmrc`의 `engine-strict=true`가 이를 강제하므로 다른 major에서는 설치가 조용히 진행되지 않고 즉시 실패한다. `nvm`을 쓴다면 `nvm use`로 맞춘다. 저장소 루트에서 lockfile 기준으로 설치한다.

```bash
npm --prefix frontend ci
```

Playwright 시험을 처음 실행하기 전에 Chromium을 한 번 내려받는다. 이 브라우저는 사용자 홈의 캐시에 설치되며 저장소에는 남지 않는다.

```bash
npm --prefix frontend exec -- playwright install chromium
```

### 명령

모든 명령은 저장소 루트에서 `--prefix frontend`로 실행한다.

| 명령 | 역할 |
|---|---|
| `npm --prefix frontend run dev` | 개발 서버. 기본 `http://localhost:5173` |
| `npm --prefix frontend run build` | 정적 산출물을 `frontend/dist/`에 생성 |
| `npm --prefix frontend run preview` | 빌드 산출물을 그대로 제공해 확인 |
| `npm --prefix frontend run typecheck` | 앱과 도구 설정 두 tsconfig의 타입 검사 |
| `npm --prefix frontend run lint` | ESLint(타입 인지). 경고도 실패다. import 정렬은 `npm --prefix frontend run lint -- --fix`로 기계가 한다 |
| `npm --prefix frontend run lint:css` | stylelint. `DESIGN.md` 네 범주(색·타이포그래피·모서리·간격)의 리터럴을 금지하고 `tokens.css` 참조를 강제한다 |
| `npm --prefix frontend run format:check` | Prettier 서식 검사. CI가 실행한다 |
| `npm --prefix frontend run format` | Prettier로 서식을 고친다 |
| `npm --prefix frontend run test` | Vitest(jsdom) 단위·화면 시험 |
| `npm --prefix frontend run test:e2e` | 빌드 후 Playwright(Chromium) 접근성·키보드·리플로·요청 시험 |
| `npm --prefix frontend run test:e2e:run` | 빌드를 건너뛰고 현재 `dist/`로 같은 시험 실행. CI 전용 |

`test:e2e`는 스크립트가 먼저 `vite build`를 실행하고, `playwright.config.ts`의 `webServer`가 그 산출물을 `preview`로 띄운다. 별도로 서버를 띄워 둘 필요가 없다. 빌드를 서버 기동과 분리한 이유는 `webServer`가 기존 서버를 재사용하면 빌드까지 건너뛰어 낡은 `dist/`를 검증하게 되기 때문이다. 그래서 `reuseExistingServer`는 항상 `false`이며 e2e는 dev·preview 기본 포트와 겹치지 않는 4183을 쓴다.

`test:e2e:run`은 `playwright test`만 실행한다. CI는 앞 단계에서 이미 `build`를 돌리므로 `test:e2e`를 쓰면 같은 빌드를 두 번 한다. 로컬에서는 한 번에 끝나는 `test:e2e`를 계속 쓴다.

Playwright는 재시도하지 않는다(`retries: 0`). 재시도는 flaky 실패를 green으로 덮어 첫 실패의 원인을 지운다. 그래서 trace도 `retain-on-failure`로 첫 실패부터 남긴다. flaky를 조사할 때만 `npm --prefix frontend run test:e2e:run -- --retries=2`처럼 명령에서 덮어쓴다.

### 구조와 규칙

| 경로 | 역할 |
|---|---|
| `frontend/src/styles/tokens.css` | 디자인 토큰 단일 출처. `DESIGN.md` frontmatter의 평면 이름을 그대로 옮긴다 |
| `frontend/src/styles/global.css` | reset, 기본 타이포그래피, 전역 `focus-visible`, 모션 감소 규칙 |
| `frontend/src/content/` | 화면 카피 정본. 화면과 시험이 같은 문자열을 참조해 문구 표류를 막는다 |
| `frontend/src/screens/` | 화면 컴포넌트와 CSS Module |
| `frontend/src/session/` | 서버 세션 확인 훅. 로그인 상태 경로의 화면만 쓴다 |
| `frontend/src/test/setup.ts` | Testing Library 등록과 `fetch`·`XMLHttpRequest`·`sendBeacon` 감시 훅. `stubSessionRequest`로 `/api/auth/session` 하나만 시험 단위로 여는 탈출구가 있고 기본 거부는 그대로다 |
| `frontend/e2e/` | Playwright 명세 |
| `frontend/playwright.config.test.ts` | `playwright.config.ts`의 실행 정책(`retries: 0`, trace·screenshot, `globalTimeout`, `CI` 해석) 고정. 대상이 `src/` 밖이라 시험도 그 옆에 두고 `vite.config.ts`의 `test.include`가 이 파일을 더 받는다 |
| `frontend/eslint.config.ts` | ESLint flat config. typescript-eslint strict·stylistic(타입 인지), React hooks·refresh, perfectionist import 정렬, 파일군별 Vitest·Testing Library·Playwright 플러그인, disable 주석 감사. `eslint-config-prettier`가 서식 규칙을 끈다 |
| `frontend/stylelint.config.js` | stylelint. `stylelint-config-standard` 위에 `declaration-strict-value`로 `DESIGN.md` 네 범주의 토큰 참조를 강제한다. `*.module.css` 클래스는 camelCase |
| `frontend/.prettierrc.json`, `frontend/.prettierignore` | 서식 정책. 세미콜론 없음, 단일 인용부호, 후행 쉼표, 2칸 들여쓰기, LF, `printWidth` 100 |
| `frontend/lint-policy.test.ts` | 위 세 설정이 실제로 위반을 잡고 준수 코드를 통과시키는지 ESLint·stylelint API로 fixture를 실행해 고정한다. `playwright.config.test.ts`와 같은 이유로 루트에 있다 |
| `frontend/e2e/tsconfig.json` | `tsconfig.node.json`을 상속만 하는 위치 표지. ESLint `projectService`가 `e2e/**`를 타입 인지하는 데 쓴다. 타입 검사는 여전히 `tsconfig.node.json`이 한다 |
| `frontend/.nvmrc`, `frontend/.npmrc` | Node major 고정과 `engine-strict` 강제 |

- `DESIGN.md`가 실제로 소유하는 범주(색, 타이포그래피, 모서리, 간격)는 컴포넌트 CSS에서 리터럴을 쓰지 않고 반드시 `tokens.css`의 변수를 참조한다. stylelint(`scale-unlimited/declaration-strict-value`)가 이를 강제한다. 대상 속성은 `*color`·`fill`·`stroke`, `font`·`font-family`·`font-size`·`font-weight`·`line-height`·`letter-spacing`, `*radius`, `padding-*`·`margin-*`·`gap`·`inset-*`·`top|right|bottom|left`·`scroll-padding-*`·`scroll-margin-*`·`outline-offset`·`outline-width`이며 `border`·`outline`·`background`·`font` 축약형은 longhand로 펼쳐 검사한다. `0`·`auto`·`inherit`·`initial`·`unset`·`revert`·`normal`·`currentcolor`·`transparent`·`none`·`100%`, forced-colors 시스템 색, 그리고 `var(--…)`를 인용하는 `calc`·`max`·`min`·`clamp`만 리터럴 예외다. `tokens.css` 자체는 이 규칙에서 제외한다.
- Elevation(`box-shadow`·`text-shadow`)은 `DESIGN.md`가 토큰이 아니라 문장으로 정한 값이라 stylelint 대상 밖이다. 논리 border 축약형(`border-inline`, `border-block-start` 등)은 `expandShorthand`가 펼치지 못해 색 리터럴 패턴만 `declaration-property-value-disallowed-list`로 금지하고, 두께·스타일 리터럴은 아래 예외 범주다. 토큰을 하나라도 인용한 `calc`·`max`·`min`·`clamp`는 `calc(var(--space-1) + 13px)`처럼 리터럴이 섞여도 통과하는 의도된 경계다. `index.html`의 noscript 인라인 스타일은 JS 없이는 `tokens.css`가 로드되지 않아 `var(--…, 폴백)` 폴백 리터럴이 필요하며 stylelint 대상(`src/**/*.css`) 밖이다.
- `DESIGN.md`에 대응 토큰이 없는 범주는 예외다. 현재 모션 duration·easing, 레이어링(`z-index`), 구조적 1px 테두리 두께가 여기 해당한다. 이 범주의 속성은 stylelint 규칙 대상이 아니므로 disable 주석 없이 리터럴을 쓸 수 있다. 없는 토큰을 지어내면 "값을 재해석하거나 새 이름을 만들지 않는다" 원칙을 어기게 되므로 이유를 주석으로 남기고, `DESIGN.md`가 나중에 해당 토큰을 정의하면 그때 옮긴다. 예외 범주에 새 stylelint 규칙을 만들지 않는다.
- 인라인 예외는 이유가 있는 disable 주석만 허용한다. stylelint는 `/* stylelint-disable-next-line <규칙> -- 이유 */`, ESLint는 `// eslint-disable-next-line <규칙> -- 이유` 형태다. 이유가 없는 disable, 위반이 없는 곳의 disable, 범위가 틀린 disable은 양쪽 모두 오류다. 규칙 대상 속성에서 리터럴이 정말 필요하면 disable 대신 `DESIGN.md`에 토큰을 추가하는 쪽을 먼저 검토한다.
- 서식은 Prettier가 소유한다. 세미콜론 없음, 단일 인용부호, 후행 쉼표, 2칸 들여쓰기, LF, `printWidth` 100. ESLint의 서식 규칙은 `eslint-config-prettier`가 끄므로 두 도구가 충돌하지 않는다.
- 외부 폰트·CDN·분석·광고 SDK를 넣지 않는다. 진입 화면은 문서와 번들 외 네트워크 요청이 0건이어야 하고 시험이 이를 고정한다.
- `localStorage`·`sessionStorage`·쿠키·IndexedDB에 쓰지 않는다. 인증 토큰과 금융 원본은 브라우저 영속 저장소에 두지 않는다. 브라우저에 남는 유일한 값은 서버가 심는 `HttpOnly`·`Secure`·`SameSite=Lax` 세션 쿠키이며 애플리케이션 코드가 그것을 읽지도 쓰지도 않는다.
- 외부 요청은 전역 거부가 기본이다. 단위 시험에서 여는 예외는 `stubSessionRequest`가 허용하는 `GET /api/auth/session` 하나뿐이고, 그 허용도 시험 하나의 범위다(`afterEach`가 닫는다). 진입 화면(`/`)은 이 탈출구를 쓰지 않으며 "문서·번들 외 요청 0건"이 그대로 유지된다.
- 경로는 라우터 없이 `App.tsx`의 닫힌 맵이 화면에 대응한다(`/`·`/onboarding`·`/dashboard`). 그 세 값은 `deploy/nginx/bokrilog.conf`의 허용 목록과 같은 집합이어야 한다. 라우트를 더할 때는 두 곳을 함께 고친다. 로그인 상태 경로는 `GET /api/auth/session`으로 서버 세션을 확인한 뒤에만 내용을 그리고, 미인증·오류는 인증 화면으로 되돌린다.
- 라이트 모드만 제공한다. 다크 모드는 별도 대비 검증 뒤 후속 범위다.
- 확대 검증은 브라우저 zoom 설정 대신 **뷰포트 폭 = 1280px ÷ 배율**로 재현한다(WCAG 1.4.10 표준 해석). 400%는 320×512, 200%는 640×512다.

## 백엔드 (`backend/`)

Java 25 LTS + Spring Boot 4.1.1 + Gradle 실행 기반이다. Story 1.3부터 PostgreSQL 18을 쓰며, 소셜 제공자 자격정보는 여전히 필요 없다. 모듈 경계는 Spring Modulith 2.1.1이 검증하지만 그 의존은 시험 classpath에만 있다. 경계 확인은 `ModuleBoundaryTest` 하나의 관심사이고, 운영 코드가 Modulith 타입을 참조하지 않으므로 ArchUnit(4.5MB)과 `spring-modulith-apt`를 `bokrilog-backend.jar`에 실을 이유가 없다. 허용 버전 계열은 `ARCHITECTURE-SPINE.md`의 Stack 표가, 실제 Gradle 버전은 `backend/gradle/wrapper/gradle-wrapper.properties`가 소유한다.

### 준비

JDK 25**와 Docker**가 필요하다. Docker가 새 전제다. 시험이 Testcontainers로 실제 `postgres:18.6-alpine`을 띄우므로, 데몬이 떠 있지 않으면 `build`가 컨테이너 시작 실패로 멈춘다. 인메모리 데이터베이스로 대체하지 않는 이유는 이 모듈이 고정하려는 것이 SQL 문법이 아니라 `SELECT … FOR UPDATE`의 직렬화와 유일 인덱스 위반 같은 실제 엔진의 동작이기 때문이다. Gradle의 toolchain 자동 프로비저닝은 `backend/gradle.properties`가 껐다. 켜 두면 JDK가 없는 기기에서 Gradle이 조용히 하나를 내려받아 "준비된 환경"과 "빌드가 만든 환경"을 구분할 수 없게 된다.

**macOS.** Homebrew의 `openjdk@25`는 keg-only라 설치만으로는 `/usr/libexec/java_home`이 찾지 못한다. 사용자 홈의 JVM 디렉터리에 최초 1회 심볼릭 링크를 걸면 그때부터 `java_home`과 Gradle의 toolchain 탐색이 함께 찾는다. sudo가 필요 없고 시스템 디렉터리를 건드리지 않는다.

```bash
brew install openjdk@25
mkdir -p ~/Library/Java/JavaVirtualMachines
ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk ~/Library/Java/JavaVirtualMachines/openjdk-25.jdk
/usr/libexec/java_home -v 25   # 링크가 걸렸는지 확인
```

경로의 `/opt/homebrew`는 Apple Silicon Homebrew의 접두사다. Intel Mac이라면 `/usr/local`이다. `brew --prefix openjdk@25`로 확인한다.

**Windows.**

```bash
winget install EclipseAdoptium.Temurin.25.JDK
```

Temurin은 표준 설치 경로에 들어가고 Gradle의 toolchain 자동 탐색이 그 위치를 본다. 별도 설정이 필요 없다. 그래도 빌드가 `No matching toolchain`으로 멈추면 저장소 파일이 아니라 **사용자 Gradle 홈**(`%USERPROFILE%\.gradle\gradle.properties`)에 `org.gradle.java.installations.paths=C:\\Program Files\\Eclipse Adoptium\\jdk-25...`를 추가한다.

**두 기기 공통.** `JAVA_HOME`을 셸 프로필에서 25로 바꿔 두지 않는다. 기기마다 다른 값이고, `backend/gradle.properties`에 박으면 다른 기기의 빌드가 그 경로를 찾다 실패한다. PATH의 `java -version`이 25가 아니어도(예: 다른 도구가 요구하는 17) 정상이며 의도한 상태다. Gradle은 PATH의 `java`가 아니라 `build.gradle.kts`의 `languageVersion = JavaLanguageVersion.of(25)`로 toolchain을 고르고, 설치된 JDK 목록에서 25를 찾아 컴파일·시험에 쓴다. 25가 없으면 자동 프로비저닝이 꺼져 있으므로 조용히 내려받지 않고 명확한 오류로 멈춘다. CI는 `actions/setup-java`가 JDK 25를 준비한다.

### 명령

Gradle wrapper는 `backend/`에 있다. 저장소 루트에서 `--project-dir backend`로 실행한다.

| 명령 | 역할 |
|---|---|
| `backend/gradlew --project-dir backend build` | 컴파일·시험·실행 가능한 jar 생성 |
| `backend/gradlew --project-dir backend test` | JUnit 시험만 실행 |
| `backend/gradlew --project-dir backend bootRun` | 백엔드 단독 기동. 기본 `http://localhost:8080` |
| `backend/gradlew --project-dir backend clean` | `backend/build/` 정리 |

`build`가 만드는 산출물은 `backend/build/libs/bokrilog-backend.jar` 하나다. 이름을 고정한 것은 `backend/Dockerfile`·`deploy/compose/compose.yaml`·CI artifact가 모두 이 한 경로를 참조하기 때문이다. 라이브러리용 plain jar는 만들지 않는다.

`build`와 `test`는 Docker 데몬을 요구한다. Testcontainers가 JVM 하나에 `postgres:18.6-alpine` 컨테이너 하나를 띄워 모든 시험이 공유한다(`PostgresTestcontainersConfig`). `bootRun`은 다르다. 데이터베이스를 스스로 띄우지 않으므로 아래 두 환경 변수를 직접 주어야 하고, 그렇지 않으면 datasource 생성에서 즉시 멈춘다. 이미 마이그레이션이 끝난 데이터베이스여야 한다 — 기본 프로필의 기동은 schema 검증만 하기 때문이다.

```bash
BOKRILOG_DB_URL=jdbc:postgresql://127.0.0.1:5432/bokrilog \
BOKRILOG_DB_USERNAME=bokrilog \
  backend/gradlew --project-dir backend bootRun
```

### 스키마와 마이그레이션

스키마 변경은 expand 우선의 별도 migration 역할이 실행하고 API 기동은 검증만 한다(AD-12). 그 두 역할을 가르는 것이 `SchemaValidationConfig`다. 기본 프로필은 `FlywayMigrationStrategy`를 `Flyway::validate`로 대체하고, `migrate` 프로필만 `Flyway::migrate`를 쓴다. 적용되지 않은 마이그레이션이 있으면 API 기동이 실패하며 그것이 의도한 fail-closed다.

마이그레이션은 `deploy/compose`의 `migrate` 서비스가 실행한다. 백엔드와 같은 이미지이고 차이는 `SPRING_PROFILES_ACTIVE=migrate` 하나다. 그 프로필은 웹 서버를 띄우지 않으므로 마이그레이션이 끝나면 프로세스가 종료하고, 종료 코드가 곧 성공 여부다. `verify-same-origin.sh`가 스택 기동 앞에서 한 번 부른다.

```bash
docker compose --file deploy/compose/compose.yaml --profile migrate run --rm --build migrate
```

새 마이그레이션은 `backend/src/main/resources/db/migration/V<n>__<이름>.sql`로 더한다. 이미 적용된 파일은 고치지 않는다. Flyway가 체크섬으로 검증하므로 수정하면 다음 기동이 검증 실패로 멈춘다.

### 구조와 규칙

| 경로 | 역할 |
|---|---|
| `backend/build.gradle.kts` | Java 25 toolchain, Spring Boot 4.1.1 BOM, `web`·`jdbc`·Flyway·Spring Security OAuth2 Client·Spring Session JDBC 운영 의존과 Modulith 2.1.1 BOM·Modulith·Testcontainers 시험 의존. 컴파일 경고는 `-Werror`로 실패다 |
| `backend/gradle.properties` | toolchain 자동 프로비저닝 끄기, 인코딩 고정 |
| `backend/src/main/java/com/bokrilog/BokriLogApplication.java` | 진입점이자 Modulith 루트. 모듈은 이 클래스 패키지의 직속 하위 패키지다 |
| `backend/src/main/java/com/bokrilog/StatusController.java` | `GET /api/status`. 고정된 최소 필드 `{"status":"UP"}` |
| `backend/src/main/java/com/bokrilog/ApiErrorHandler.java` | 공통 오류 계약과 프레임워크 오류 경로 대체 |
| `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java` | 인증 표면과 세션 정책. Google 등록, PKCE 강제, 세션 고정 방어, CSRF·`Origin` 검증, 401·403 응답 |
| `backend/src/main/java/com/bokrilog/AuthSessionService.java` | 검증된 `(issuer, subject)`를 `identity` 공개 API로 계정·세션에 옮긴다. 경합 재시도와 롤백 경계 |
| `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` | 인증 시작 전처리, 성공 시 목적지 허용 목록과 인증 주체 교체, 실패 표지 수렴 |
| `backend/src/main/java/com/bokrilog/AuthSessionController.java` | `GET /api/auth/session`(인증 여부·목적지 종류)과 인증 시작 fail-closed 응답 |
| `backend/src/main/java/com/bokrilog/DevOidcStub.java` | 결정적 local OIDC 제공자의 논리. 자기 RSA 키로 JWKS를 제공하고 ID Token을 서명한다. HTTP도 Spring도 없고 자격정보 리터럴도 없다 |
| `backend/src/main/java/com/bokrilog/DevOidcStubConfig.java` | 그 스텁의 HTTP 표면(`/api/dev-oidc/**`)과 제공자 등록. `dev-oidc` 프로필에만 존재하고 실제 Google 등록과 함께 활성이면 기동을 거부한다 |
| `backend/src/main/java/com/bokrilog/identity/` | 첫 모듈. 계정 소유권·삭제 상태의 권위. 공개 API 세 개(`AccountLifecycleApi`·`AccountAccessApi`·`AccountDeletionApi`)와 `internal` 구현 |
| `backend/src/main/resources/db/migration/` | Flyway 마이그레이션. `V1__identity_account.sql`이 guard·payload·매핑 세 테이블을, `V2__spring_session.sql`이 Spring Session JDBC의 세션 두 테이블을 만든다 |
| `backend/src/main/resources/application.yml` | 포트, 상대 리다이렉트, 세션 쿠키 속성, 오류 노출 끄기, 로그 수준, datasource·flyway·session, `bokrilog.auth` 이름, `dev-oidc`·`migrate` 프로필. 자격정보를 두지 않는다 |
| `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java` | 시험 전체가 공유하는 실제 PostgreSQL 컨테이너 하나 |
| `backend/src/test/java/com/bokrilog/OidcStubConfiguration.java` | 위 `DevOidcStub`을 루프백 HTTP 서버에 올리는 시험 표면. 스텁 논리를 다시 만들지 않는다 |
| `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java` | 인증 시험 다섯 개의 공통 배선. 같은 컨텍스트를 공유하려면 `@DynamicPropertySource`가 한 곳에 있어야 한다 |
| `backend/src/test/java/com/bokrilog/Auth*Test.java` | 토큰·`state` 검증, 계정·매핑, 세션·쿠키·CSRF, 복귀 목적지·redaction, 삭제 경로 |
| `backend/src/test/java/com/bokrilog/DevOidcStubProfileTest.java` | 기본 프로필의 스텁 빈 부재·경로 미노출·fail-closed 등록과 프로필 오용 시 기동 거부 |
| `backend/src/test/java/com/bokrilog/ModuleBoundaryTest.java` | Modulith 경계 검증, `internal` 미노출, identity에 HTTP 표면 없음 |
| `backend/src/test/java/com/bokrilog/StatusApiTest.java` | 실제 HTTP로 상태·404·405·누출 금지를 시험 |
| `backend/src/test/java/com/bokrilog/identity/` | 소유권·삭제 상태 판정, 삭제 경로, 경합·실패 주입 시험 |
| `backend/Dockerfile`, `backend/.dockerignore` | 미리 빌드한 jar만 올리는 런타임 전용 이미지 |

- 상태 엔드포인트는 루트 패키지 `com.bokrilog`에 둔다. Spring Modulith는 루트 패키지를 모듈로 보지 않으므로 `identity`가 첫 모듈로 깨끗하게 인식된다.
- `identity`는 계정 생명주기·삭제 상태의 유일한 권위다. 다른 패키지는 공개 application API로만 접근하고 `internal` 패키지나 소유 테이블에 직접 닿지 않는다. `ModuleBoundaryTest`가 강제한다.
- `identity`에는 HTTP 표면이 없다. 컨트롤러도 엔드포인트도 Actuator도 없다. 계정 생성·상태 변경·삭제를 밖에서 부를 수 있는 경로가 존재하지 않는 것이 계약이며, 구조는 `ModuleBoundaryTest`가, 실행 중인 스택은 `verify-same-origin.sh`의 `identity 미노출` 절이 확인한다.
- 접근 거부는 하나의 값(`AccessDecision.DENIED`)이다. 타 사용자 대상·미존재 대상·삭제된 대상이 호출자에게 구분되면 안 된다. 거부 사유를 반환하지도, 로그에 남기지도 않으며 `user_id`를 포함한 사용자 식별자도 로그·지표·오류 응답 어디에도 남기지 않는다(AD-14).
- 계정 상태는 `ACTIVE → DELETION_IN_PROGRESS → DELETED` 단조 전진뿐이다. 상태 행 `SELECT … FOR UPDATE` 잠금과 술어가 붙은 UPDATE가 강제하며, 반복 요청·실패·재시도로 이전 상태가 되지 않는다.
- JPA를 쓰지 않는다. `JdbcClient`면 SQL이 그대로 보이고, Hibernate의 dirty checking·flush 시점은 "커밋 직전 재검사"를 오히려 흐린다.
- 오류 응답은 `{"code":…,"message":…,"correlationId":…}` 한 가지뿐이다. `code`는 `BAD_REQUEST`·`NOT_FOUND`·`METHOD_NOT_ALLOWED`·`UNAUTHENTICATED`·`FORBIDDEN`·`UPSTREAM_UNAVAILABLE`·`INTERNAL_ERROR`이며 안정적인 machine code라 문자열을 바꾸지 않는다. Nginx가 만드는 오류(502, 정적 404)도 같은 모양을 돌려준다. `UNAUTHENTICATED`(401)·`FORBIDDEN`(403)은 Story 1.4의 인증 표면이 그 두 상태를 실제로 내면서 들어왔다. Nginx는 이 구성에서 그 두 상태를 스스로 만들 수 없어 진입점의 `error_page` 목록에 대응 항목이 없고, 두 계층이 그 code를 나눠 갖지도 않는다.
- 내부 경로·스택·프레임워크 기본 오류 본문·환경 변수 이름은 응답과 로그 어디에도 남기지 않는다. 그래서 `spring.main.log-startup-info`가 꺼져 있고(기동 로그가 jar 경로와 실행 사용자를 남긴다), 처리하지 못한 예외의 스택은 기본 로그에 찍지 않는다. 스택이 필요한 조사에서는 `logging.level.com.bokrilog=debug`로 한 번만 열고 다시 닫는다.
- 상태 응답에 시각·버전·호스트명·빌드 정보를 넣지 않는다. 인증 없이 공개되는 엔드포인트라 담는 모든 값이 곧 공개 정보다.
- Actuator·JPA·`spring-security-test` 의존은 아직 없다. 추가는 그것을 필요로 하는 스토리의 SPEC이 목적과 함께 승인한다. Spring Modulith·PostgreSQL·Flyway·Testcontainers는 `spec-1-3-identity-ownership.md`가, Spring Security OAuth2 Client·Spring Session JDBC는 `spec-1-4-google-sign-in.md`가 승인해 들어왔다.
- 검증을 손으로 만들지 않는다. Authorization Code + PKCE(S256), 일회성 `state`·`nonce`, ID Token의 JWS 서명·허용 알고리즘(RS256)·JWKS와 `iss`·`aud`·`azp`·`exp`·`nonce` 검증, 세션 고정 방어는 Spring Security OAuth2 Client의 계약이다. `AuthSecurityConfig`가 하는 일은 그 계약을 이 저장소의 경계에 맞추는 배선뿐이다.
- 백엔드는 보안 응답 헤더를 만들지 않는다. Spring Security의 기본 헤더 writer를 끈 이유가 이것이다. 켜 두면 진입점과 애플리케이션이 같은 헤더를 각각 실어 값이 두 번 나가고, 헤더를 전문 비교하는 `verify-same-origin.sh`가 먼저 깨진다. 캐시 지시가 필요한 응답은 그 응답이 직접 정한다(`GET /api/auth/session`의 `no-store`).
- CSRF와 동일 출처 `Origin` 검증은 `/api/auth/` 아래의 상태 변경 요청에만 걸린다. 전역으로 두면 `CsrfFilter`가 서블릿보다 먼저 답해 `POST /api/status`의 405와 identity 탐침의 404가 전부 403으로 뒤집힌다. CSRF 토큰은 세션에 귀속하며 쿠키 저장소를 쓰지 않는다.
- 세션은 Spring Session JDBC로 PostgreSQL에 산다. 브라우저에는 `HttpOnly`·`Secure`·`SameSite=Lax` 불투명 쿠키만 두고 로그인 성공 시 세션 ID를 회전한다. 세션이 담는 값은 내부 `user_id` 하나이며 ID Token·액세스·리프레시 토큰은 검증 직후 버린다. `AuthFlowHandlers`가 인증 주체를 내부 식별자 하나로 바꿔 다시 저장하고, 제공자 토큰 저장소는 아무것도 쓰지 않는다.
- 제공자 엔드포인트를 discovery로 찾지 않는다. `spring.security.oauth2.client.provider.<id>.issuer-uri`는 기동 시점에 네트워크 왕복을 하므로 쓰지 않고, `CommonOAuth2Provider.GOOGLE`이 정적으로 들고 있는 Google 정본 주소(`issuer` 포함)를 쓴다. 재정의가 필요한 환경만 `bokrilog.auth.google.*`로 네 값을 준다.
- 인증 표면은 servlet 웹 애플리케이션에서만 배선한다(`@ConditionalOnWebApplication(type = SERVLET)`). 같은 이미지의 일회성 migration 역할은 웹 서버 없이 뜨므로 `HttpSecurity`가 없고, 조건이 없으면 마이그레이션이 컨텍스트 생성 실패로 끝난다.

## 동일 출처 실행 (`deploy/`)

Nginx가 `frontend/dist/`를 origin 루트에서 제공하고 `/api`만 백엔드로 프록시한다. 브라우저는 하나의 origin에서 화면과 서버 응답을 함께 받는다. 이 구성이 개발·CI 공용이며 production 프로필이 아니다. image digest 고정·자원 제한·로그 전송·blue/green 슬롯은 Story 3.8과 배포 작업이 소유한다.

### 준비

Docker와 Compose v2가 필요하다. compose는 어느 쪽도 빌드하지 않으므로 두 산출물을 먼저 만든다.

```bash
npm --prefix frontend run build
backend/gradlew --project-dir backend build
```

### 명령

| 명령 | 역할 |
|---|---|
| `deploy/compose/verify-same-origin.sh [--keep-stack]` | 마이그레이션 1회 → 기동 → I/O 표 전 행 확인 → identity 미노출 확인 → 인증 표면 확인 → 스텁 제공자 성공 왕복(끝에서 만든 행을 지워 원래 상태로 되돌린다) → 허용 목록 경로 확인 → 백엔드 정지 후 502/504 확인 → 로그 누출 검사(왕복의 `code`·`state` 포함) → 정리. `--keep-stack`이면 성공 시 백엔드를 다시 띄워 건강한 스택을 남긴다 |
| `docker compose --file deploy/compose/compose.yaml --profile migrate run --rm --build migrate` | 스키마 마이그레이션 1회. 기동 전에 실행한다 |
| `docker compose --file deploy/compose/compose.yaml up --detach --build --wait --wait-timeout 180` | 스택 기동. `http://127.0.0.1:18080` |
| `docker compose --file deploy/compose/compose.yaml down --remove-orphans --volumes` | 정리 |

직접 다룰 때는 **마이그레이션을 먼저** 실행한다. 기본 프로필의 백엔드 기동은 schema 검증만 하므로(AD-12), 빈 데이터베이스에 `up`부터 하면 백엔드가 검증 실패로 healthy가 되지 못하고 `--wait`가 timeout까지 기다린다. `verify-same-origin.sh`는 그 순서를 이미 담고 있다.

**postgres는 휘발성이다.** compose에 명명 볼륨이 없어 데이터베이스가 컨테이너 수명만큼만 산다. 개발·CI 스택이 매번 같은 빈 상태에서 시작하고, 어제 남은 행이 오늘 시험 결과를 바꾸지 않게 하려는 선택이다. 그래서 `down`(위 정리 명령) 뒤에는 스키마도 함께 사라진다. `--keep-stack`으로 남긴 스택을 내렸다가 다시 세울 때는 `up`이 아니라 **마이그레이션부터** 다시 실행한다. 마이그레이션 파일을 고친 뒤에도 마찬가지다 — 이미 뜬 데이터베이스에는 옛 체크섬이 기록돼 있어 백엔드 기동이 검증 실패로 끝나므로, 스택을 내리고 마이그레이션부터 다시 시작한다.

명령은 `ci.yml`과 **같은 형태**로 적는다. `-f`/`--file`, `-d`/`--detach`, `-p`/`--project-dir`처럼 같은 뜻의 축약형을 문서마다 다르게 쓰면, 로컬에서 재현할 때 무엇이 CI와 다른지 눈으로 대조할 수 없다. `gradlew`도 `--project-dir backend` 한 형태만 쓴다. CI에만 `bash` 접두와 `--no-daemon`이 붙는데, 앞은 체크아웃에서 실행 비트가 유실돼도 동작하게 하려는 것이고 뒤는 러너에 daemon을 남기지 않으려는 것이다. 그 두 가지 외에는 로컬과 CI의 명령이 같다.

검증 스크립트의 종료 코드는 0(전부 통과), 1(단정 실패), 2(준비 실패)다. 준비 실패는 산출물이나 docker가 없어 단정이 하나도 돌지 않았다는 뜻이라 단정 실패와 구분한다. CI의 `same-origin-integration` job이 같은 명령을 실행한다.

스택이 떠 있으면(`verify-same-origin.sh --keep-stack` 직후, 또는 위 `up` 명령 뒤) Story 1.1의 Playwright 시험을 그 origin에 대고 다시 돌릴 수 있다. 시험 본문은 바꾸지 않는다. CI의 통합 job이 정확히 이 순서로 돈다.

```bash
PLAYWRIGHT_BASE_URL=http://127.0.0.1:18080 npm --prefix frontend run test:e2e:run
```

`PLAYWRIGHT_BASE_URL`이 있으면 `playwright.config.ts`가 자체 `preview` 서버를 띄우지 않는다. 이 분기가 없으면 통합 실행이 Nginx가 아니라 자기가 띄운 preview를 검증하면서도 green이 난다. 두 경우는 `frontend/playwright.config.test.ts`가 고정한다. 주입된 origin에서만 도는 시험이 둘 있다 — `same-origin-csp.spec.ts`(진입점 CSP)와 `auth-entry.spec.ts`의 `스텁 제공자 성공 왕복`(버튼 → 스텁 동의 → 온보딩 → 세션 쿠키)이다. preview에는 `/api`도 스텁도 없어 검증할 대상 자체가 없다.

### 구조와 규칙

| 경로 | 역할 |
|---|---|
| `deploy/nginx/bokrilog.conf` | 루트 정적 제공, `/api` 프록시, 알려진 내부 경로 허용 목록, 공통 오류 JSON, 보안 응답 헤더, 쿼리 없는 access log |
| `deploy/compose/compose.yaml` | 네 서비스. `postgres:18.6-alpine` → 백엔드 → `nginx:1.30.4-alpine`이 healthcheck로 이어지고, `migrate`는 `profiles`에 가려 명시 실행만 된다. 산출물과 설정을 읽기 전용 마운트 |
| `deploy/compose/verify-same-origin.sh` | 로컬·CI 공용 검증 명령 |

- 개발·CI compose에 비밀번호를 두지 않는다. `POSTGRES_HOST_AUTH_METHOD: trust`를 쓰고 포트를 게시하지 않는다. 가짜 비밀번호 리터럴을 커밋하면 gitleaks 규칙을 완화하고 싶어지고 그 완화가 남는다. production 자격정보는 Story 3.8이 소유한다.
- Google 자격정보도 두지 않는다. compose는 `BOKRILOG_GOOGLE_CLIENT_ID`·`BOKRILOG_GOOGLE_CLIENT_SECRET`를 참조로만 넘기고 값을 적지 않는다. 대신 백엔드가 `SPRING_PROFILES_ACTIVE: dev-oidc`로 떠서 `/api/dev-oidc/**`의 local OIDC 스텁을 제공자 자리에 세우고, `verify-same-origin.sh`가 인가 시작부터 세션 개설까지의 성공 왕복을 실제로 지난다. 스텁의 client id·secret은 기동 시점에 만들어지므로 설정 파일에도 compose에도 리터럴이 없다. 두 제공자가 함께 구성되면 백엔드가 기동을 거부하며, 자격정보가 없는 기본 프로필의 fail-closed는 `DevOidcStubProfileTest`가 지킨다.
- 스텁이 시험 소스가 아니라 `main`에 있는 이유는 브라우저다. Authorization Code 흐름의 인가 요청은 브라우저가 보내고 code 교환·JWKS 조회는 백엔드가 보내므로, 성공 경로를 브라우저 층에서 검증하려면 한 스텁이 양쪽에서 도달 가능해야 한다. `/api` 아래에 두면 기존 프록시 블록이 그대로 받아 새 서비스·새 게시 포트·CSP 확장이 하나도 필요 없다. 브라우저가 보는 주소(`BOKRILOG_PUBLIC_BASE_URL`)와 백엔드가 자기 스텁에 닿는 주소(`BOKRILOG_DEV_OIDC_INTERNAL_BASE_URL`)는 다른 값이다 — 공개 origin은 compose 네트워크 안에서 해석되지 않는다.
- 리다이렉트의 `Location`은 origin-상대 경로다(`server.tomcat.use-relative-redirects: true`). 기본값에서는 Tomcat이 요청의 `Host`로 절대 URI를 만들고, `proxy_set_header Host $host`가 포트를 잃은 값을 넘기므로 로그인 성공 뒤의 이동이 `http://127.0.0.1/onboarding`(80번 포트)으로 끊긴다. `$http_host`나 `X-Forwarded-*`로 바꾸는 길은 클라이언트가 제어하는 헤더를 목적지 계산에 넣는다.
- access log에 쿼리 문자열을 남기지 않는다. `log_format`이 `$request` 대신 `$request_method $request_path $server_protocol`을 쓰고 `map`이 `?` 앞부분만 잘라 낸다. `$request`를 그대로 쓰면 OIDC 콜백의 `?code=…&state=…`가 로그에 실린다. `$uri`로 대신하지 않는 이유는 그 변수가 내부 redirect 뒤의 값이라 `/onboarding` 요청이 `/index.html`로 기록되기 때문이다.
- 바깥에 열리는 포트는 Nginx 하나(`127.0.0.1:18080`)다. 백엔드와 데이터베이스 포트는 게시하지 않는다. 게시하는 순간 "동일 출처"는 구성이 아니라 관례가 된다. 호스트 포트는 `4183`으로 시작하지 않는 값을 쓴다. Playwright의 origin 비교가 포트 접두사에 취약했던 이력이 있고(`4183` 대 `41830`) preview 기본 포트와도 겹치지 않아야 한다.
- `/api`만 프록시한다. 나머지 경로는 정적 파일이거나 404이며 어떤 경로도 외부 주소로 프록시하지 않는다.
- 와일드카드 SPA 폴백(`try_files … /index.html`)을 두지 않는다. 폴백이 있으면 존재하지 않는 경로가 200 + 진입 화면으로 응답해 "없음"을 숨긴다. 대신 알려진 내부 경로만 여는 허용 목록 location(`^/(onboarding|dashboard)$`)이 있고, 그 집합은 프런트엔드 `App.tsx`의 경로 맵과 같아야 한다. 라우트를 더할 때는 두 곳을 함께 고친다.
- 인증 엔드포인트는 `/api/auth/` 아래에 있어 기존 `/api` 프록시 블록이 그대로 받는다. redirect URI는 요청 헤더에서 유도하지 않고 `BOKRILOG_PUBLIC_BASE_URL`로 받는다. `proxy_set_header Host $host`가 포트를 잃기 때문이며, `$http_host`나 `X-Forwarded-*`로 바꾸는 길은 둘 다 클라이언트가 제어하는 헤더를 redirect URI 계산에 넣는다.
- CSP는 Nginx 응답 헤더가 유일한 정의다. `<meta>` CSP를 쓰지 않는다. `index.html`의 noscript 폴백이 인라인 `style` 속성을 쓰므로 `style-src-attr 'unsafe-inline'`을 뺄 수 없다. 빼면 JS를 끈 사용자의 고지 화면이 스타일 없이 나온다.
- `add_header`는 하위 블록에 하나라도 있으면 상위 블록의 것이 전부 사라진다. 그래서 보안 헤더는 server 블록 한 곳에만 두고 named location에는 두지 않는다. `always`가 붙어야 4xx·5xx 응답에도 실린다.
- `frontend/dist`와 Nginx 설정은 읽기 전용으로 마운트한다. 컨테이너가 저장소 파일을 고칠 경로를 만들지 않는다.
- compose 로컬 오버라이드(`compose.override.yaml`)와 `.env`는 `.gitignore`가 제외한다. 저장소가 소유하는 구성은 `deploy/compose/compose.yaml` 하나뿐이다.

## CI (`.github/`)

`.github/workflows/ci.yml`이 이 저장소의 유일한 workflow다. `develop`·`main`의 PR과 push, 그리고 배포 권한이 없는 수동 진단(`workflow_dispatch`)에서 돈다. 정본은 `CI-PLAN.md`이고 구현 계약은 `_bmad-output/implementation-artifacts/spec-ci-foundation.md`와 `spec-1-2-same-origin-shell.md`가 소유한다.

Story 1.2에서 backend와 deploy가 생기면서 단일 job이 `CI-PLAN.md` §1의 병렬 구조로 갈라졌다. branch protection이 참조하는 이름은 전환 전후 모두 `ci-required`이며 바꾸지 않는다. 다만 이제 그 job은 검사를 직접 실행하지 않고 선행 job의 결과만 집계한다.

```text
changes ──> frontend-quality ──┐
        └─> backend-quality ───┼─> same-origin-integration ─┐
                               └────────────────────────────┴─> ci-required
```

| job | 역할 | timeout |
|---|---|---|
| `changes` | secret 검사, CI 계약 시험, 변경 분류 | 5분 |
| `frontend-quality` | 타입 검사 → ESLint → stylelint → Prettier → 빌드 → Vitest → Chromium E2E | 8분 |
| `backend-quality` | JDK 25 준비 → Gradle `build`(컴파일·Modulith 경계 검사·PostgreSQL Testcontainers 시험·jar) | 15분 |
| `same-origin-integration` | 두 품질 job의 산출물로 동일 출처 검증(`--keep-stack`) → 같은 스택에서 Story 1.1 회귀 | 15분 |
| `ci-required` | 선행 job 결과 집계. 병합 가능 여부 판정 | 1분 |

`backend-quality`의 15분은 Story 1.3이 10분에서 올린 값이다. 이 job은 이제 Testcontainers로 실제 PostgreSQL을 띄우고, 콜드 실행에는 `postgres:18.6-alpine` 내려받기가, 동시성 시험에는 실제 잠금 경합 대기가 더해진다. 러너에 docker 데몬이 이미 있으므로 서비스 컨테이너도 새 action도 더하지 않았고, 시험은 기존 `Gradle 빌드` 단계 안에서 돈다. `CI-PLAN.md` §4·§10의 backend 예산 8분은 이 상한과 별개로 계속 측정한다.

`same-origin-integration`의 15분은 `CI-PLAN.md` §8이 적은 시작값 5분과 다르다. 그 값은 이 job이 무엇을 하는지 정해지기 전의 추정이고, 지금은 Story 1.1 회귀 단계 하나가 이미 4분을 쓴다. 단계별 상한의 합(내려받기 1 + 동일 출처 검증 7 + `npm ci` 2 + Chromium 설치 2 + 회귀 4 + 정리·업로드 1)이 상한 17분이지만 15분을 유지한다. 검증 단계에 Story 1.3이 세 가지를 더했다 — `postgres:18.6-alpine` 이미지 내려받기, migrate 이미지 빌드, 마이그레이션 실행 — 그래서 그 단계의 예산을 5분에서 7분으로 본다. 실측(PR #9의 2분 2초)과 상한 사이에 아직 여유가 크므로 job 상한은 그대로 두고, 실측이 몇 회 더 쌓이면 두 값을 `CI-PLAN.md` 새 버전이 함께 보정한다. 검증 스크립트가 `--keep-stack`으로 남긴 스택을 회귀가 그대로 쓰므로 compose는 한 번만 뜬다. 필수 검사를 빼서 5분에 맞추지 않는다(CI-PLAN §10). 이 job이 `frontend-quality`의 `npm ci`와 Chromium 설치를 되풀이하는 중복은 `deferred-work.md`에 남겼다. 목표는 `CI-PLAN.md` §10의 PR wall-clock p95 8분이며 job timeout은 목표가 아니라 멈춘 job을 끊는 상한이다.

이 job 구조는 Story 1.2의 PR #9에서 2026-09-06에 처음 GitHub에서 돌았다. 첫 실측 wall-clock은 4분 3초로 `CI-PLAN.md` §10의 PR p95 8분 안이다. job별로는 `changes` 16초, `frontend-quality` 1분 9초, `backend-quality` 1분 26초, `same-origin-integration` 2분 2초, `ci-required` 7초다. 같은 날 문서-only PR #10은 `changes` 10초 → `ci-required` 6초로 끝났다. 위 표의 timeout은 이 실측이 몇 회 더 쌓인 뒤 `CI-PLAN.md` 새 버전에서 보정한다.

두 품질 job은 `same-origin-integration`이 소비할 산출물(`frontend/dist`, `backend/build/libs/bokrilog-backend.jar`)을 업로드한다. 통합 job은 그 산출물을 다시 빌드하지 않는다. 재빌드하면 통합 시험이 검증하는 바이트가 품질 job이 검증한 바이트와 같다는 보장이 사라진다. artifact 이름에는 run ID와 attempt가 들어가 취소되거나 대체된 실행의 것을 재사용하지 않고, retention은 1일이다. 이름에 attempt가 들어가는 대가로 GitHub의 `Re-run failed jobs`는 쓸 수 없다. 이미 성공한 품질 job은 다시 돌지 않아 새 attempt 번호의 artifact를 만들지 않고, 통합 job만 다시 돌면 없는 이름을 찾는다. 재실행이 필요하면 `Re-run all jobs`를 쓴다. 이 제약은 `deferred-work.md`에 남겼다.

집계는 fail-closed다. `success`와 "예상한 `skipped`" 두 가지만 통과이고 `failure`·`cancelled`·빈 값·예상하지 않은 `skipped`는 모두 실패다. GitHub의 `needs.<job>.result`는 job이 실행되지 않으면 빈 문자열로 오는데, 그것을 통과로 읽으면 job 하나를 지우는 것만으로 필수 검사가 사라진다.

판정 논리는 YAML이 아니라 `.github/scripts/`의 셸 스크립트가 소유한다. YAML 안의 인라인 `run` 블록과 표현식은 저장소에서 실행할 수 없어 시험 대상이 되지 못하기 때문이다.

| 경로 | 역할 |
|---|---|
| `.github/workflows/ci.yml` | 이벤트·권한·job 구조·단계 순서. 판정은 아래 스크립트에 위임한다 |
| `.github/scripts/verify-merge-commit.sh` | PR merge commit의 부모가 이벤트의 base·head와 같은 순서인지 검증 |
| `.github/scripts/classify-changes.sh` | 문서-only 여부 판정. `docs-only`·`reason`을 `GITHUB_OUTPUT`과 job summary에 기록 |
| `.github/scripts/aggregate-results.sh` | 선행 job 결과를 모아 병합 가능 여부 판정 |
| `.github/scripts/test-ci-scripts.sh` | 위 세 스크립트와 `ci.yml`의 계약 시험 |

lint·stylelint·format 세 단계는 `spec-frontend-lint-policy.md`가 합의한 품질 정책이며 `ci-required`가 집계하는 job 안에 있으므로 별도 보호 규칙 변경 없이 필수 검사다. 문서-only 변경(`_bmad-output/**`와 최상위 `*.md`)은 `changes` job만 돌고 제품 job 셋은 건너뛴다. 분류는 fail-closed다. 규칙에 없는 경로가 하나라도 있으면 전체 검사를 실행하며, `backend/`·`deploy/`처럼 `<디렉터리>/<파일>` 모양인 경로는 자동으로 제품 변경이라 분류 규칙을 손댈 필요가 없다. PR에서는 체크아웃된 head가 아니라 target base의 승인된 분류기를 꺼내 실행한다. PR이 자기 분류 규칙을 고쳐 그 PR의 검사를 축소할 수 없게 한다.

secret 검사(gitleaks)의 예외는 저장소 루트 `.gitleaksignore`가 소유한다. 한 줄에 fingerprint 하나이며 각 항목에 근거 주석을 남긴다. 규칙 자체를 완화하는 `.gitleaks.toml`은 두지 않는다. 필수 경로는 PR 커밋 범위만 검사하므로 기존 이력 전체는 `workflow_dispatch`로 돌리는 기준선 스캔에서만 드러난다.

### 명령

| 명령 | 역할 |
|---|---|
| `bash .github/scripts/test-ci-scripts.sh` | CI 계약 시험. 임시 git fixture로 분류 규칙·`reason` enum·merge commit 검증·집계 판정을 실행하고, `ci.yml`의 job 목록·`needs` 배선·러너·job별 단계 목록·고정 action SHA 집합·문서-only 게이트·통합 입력과 진단 artifact 계약을 단정한다 |

종료 코드는 0(전부 통과), 1(단정 실패), 2(fixture 준비 실패)다. 준비 실패는 단정 결과가 없다는 뜻이라 단정 실패와 구분한다. Windows에서는 Git Bash로 실행한다. CI도 이 시험을 `changes` job 안에서 조건 없이 항상 실행하며, 분류 단계보다 앞에 둔다. 분류기를 분류기로 게이트하지 않기 위해서다.

`ci.yml` 파싱에는 YAML 파서를 쓰지 않는다. PyYAML은 러너의 PATH 위 `python3`에 있다고 보장되지 않고 Windows의 `python3`는 Microsoft Store 스텁이라 실행되지 않는다. 대신 이 파일의 들여쓰기 규약(0칸 최상위 키, 2칸 job id, 4칸 job 속성, 6칸 단계 머리, 8칸 단계 속성)에 기대어 job 블록을 잘라 낸 뒤 그 안에서 단정한다. 같은 단계 이름과 같은 action이 여러 job에 나오므로 "파일 전체에서 몇 번"만으로는 계약을 고정할 수 없기 때문이다.

## BMAD 명령 실행 원칙

BMAD Python 도구는 항상 저장소 루트에서 `uv run --managed-python --no-cache`로 실행한다.

```bash
uv run --managed-python --no-cache _bmad/scripts/resolve_config.py \
  --project-root "$PWD"
```

`--managed-python`은 오래된 시스템 Python이 실수로 선택되는 것을 막는다. `--no-cache`는 BMAD의 일회성 도구 실행이 이전 캐시 상태에 의존하지 않도록 한다. 반면 패키지 설치처럼 캐시를 사용하는 일반 `uv` 명령은 `uv.toml`에 따라 저장소의 `.uv-cache`만 사용하며, 이 디렉터리는 Git에 포함되지 않는다.

## 에이전트 타깃

BMAD 스킬은 에이전트별 디렉터리로 설치되며, 이 저장소는 Codex와 Claude Code를 함께 사용한다. 두 디렉터리는 같은 설치본에서 생성된 동일한 파일이며 한쪽만 갱신하면 안 된다. 스킬 본문은 실행 시점에 `_bmad/render`의 불변 스냅샷으로 렌더링되므로, 두 타깃은 같은 스냅샷 하나를 공유한다.

인스톨러를 다시 실행할 때는 항상 두 타깃을 함께 지정한다.

```bash
npx bmad-method@6.11.0 install \
  --directory . --action update --modules bmm --tools codex,claude-code \
  --user-name "SSAFY" --communication-language "Korean" \
  --document-output-language "Korean" --output-folder "_bmad-output" \
  --set bmm.user_skill_level=intermediate \
  --set 'bmm.planning_artifacts={project-root}/_bmad-output/planning-artifacts' \
  --set 'bmm.implementation_artifacts={project-root}/_bmad-output/implementation-artifacts' \
  --set 'bmm.project_knowledge={project-root}/docs' \
  --yes
```

기존 설치가 있으면 `--action`은 `update` 또는 `quick-update`만 받는다. `install`은 거부된다.

인스톨러는 `_bmad/scripts/`의 로컬 수정을 `.bak`로 백업한 뒤 업스트림 원본으로 덮어쓴다. 이 저장소는 `resolve_config.py`에 stdout을 UTF-8로 재설정하는 수정을 유지하고 있는데, 업스트림에는 그 코드가 없어 한글 산출물이 Windows 기본 콘솔 인코딩에서 깨질 수 있다. 따라서 인스톨러 실행 후에는 반드시 다음을 수행한다.

1. `git diff _bmad/scripts/` 로 사라진 수정을 확인한다.
2. 필요한 수정을 되돌린다: `git checkout -- _bmad/scripts/resolve_config.py`
3. `.bak` 파일을 삭제한다.
4. `./scripts/check-bmad-env.sh` 를 다시 실행한다. 5번 항목이 이 수정의 잔존을 검사한다.

## 파일 역할

- `uv.toml`: `uv` 캐시를 저장소 내부로 제한한다.
- `.codex/config.toml`: BMAD 도구의 UTF-8 출력을 Codex 작업 환경에 적용한다.
- `.agents/skills/`: Codex가 읽는 BMAD 스킬. 인스톨러가 생성하며 커밋한다.
- `.claude/skills/`: Claude Code가 읽는 BMAD 스킬. 같은 설치본에서 생성하며 커밋한다.
- `CLAUDE.md`: Claude Code 세션에 프로젝트 규칙과 현재 진행 지점을 전달한다.
- `_bmad/`: BMAD 설치본과 설정. `_bmad/config*.toml`과 모듈 `config.yaml`은 인스톨러가 재생성하므로 직접 수정하지 않고 `_bmad/custom/`을 사용한다.
- `_bmad/render/`: 스킬 렌더링 스냅샷. 생성물이며 Git에 포함되지 않는다.
- `_bmad-output/`: 기획·구현 산출물 정본.
- `frontend/`: React/Vite 정적 SPA. 위 `## 프런트엔드` 절을 따른다.
- `backend/`: Java/Spring Boot 백엔드와 Gradle wrapper. 위 `## 백엔드` 절을 따른다.
- `deploy/`: Nginx 설정과 개발·CI 공용 compose 구성. 위 `## 동일 출처 실행` 절을 따른다.
- `.github/`: GitHub Actions workflow와 CI 판정 스크립트. 위 `## CI` 절을 따른다.
- `.gitattributes`: 줄바꿈 규칙을 저장소에 고정한다. Windows·macOS가 같은 바이트를 보게 한다.
- `.gitignore`: 로컬 캐시, 가상환경, Python 바이트코드, 두 운영체제의 메타데이터, IDE 로컬 상태와 빌드 산출물을 제외한다.
- `scripts/check-bmad-env.sh`: 위 설정이 실제로 동작하는지 한 번에 검증한다.

애플리케이션 기술 스택과 허용 버전은 아키텍처 문서를 권위 기준으로 삼고, 실제 설치 버전은 lockfile과 image digest가 소유한다. 프런트엔드는 `frontend/package-lock.json`이, 백엔드의 Gradle 버전은 `backend/gradle/wrapper/gradle-wrapper.properties`가 소유하며 그 파일은 배포본 SHA-256까지 고정한다. 백엔드 의존 버전은 `build.gradle.kts`가 인용한 BOM이 결정한다. BOM은 둘이다. Spring Boot BOM(플러그인이 노출하는 좌표를 그대로 인용하며 Flyway·PostgreSQL 드라이버·Testcontainers까지 관리한다)과 Spring Modulith BOM 2.1.1(Boot BOM이 관리하지 않아 버전을 여기서 고정하며 시험 classpath에만 적용한다)이다. 두 BOM이 겹치는 좌표는 없다. Gradle dependency lock과 운영 image digest는 아직 도입 전이다. 제품이 Python 애플리케이션이라는 전제에서 `.python-version`, `pyproject.toml`, 애플리케이션 `.venv`를 만들지 않는다.
