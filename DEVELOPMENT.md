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

비ASCII 문자는 curl 인자로 넘기지 않는다. Git Bash의 `curl`은 mingw 네이티브 빌드라 본문·헤더·URL을 가리지 않고 argv 전체를 ANSI 코드 페이지(한국어 Windows는 CP949)로 해석한다. 그래서 인자로 넘긴 한글은 UTF-8이 아니라 CP949 바이트로 나가고, 백엔드는 그 본문을 읽지 못해 400으로 답한다. 본문은 파일에 바이트 그대로 써서 `@<파일>`로 넘기거나 `@-`로 stdin에서 읽힌다. `deploy/compose/verify-same-origin.sh`에서는 `request_body` 헬퍼가 그 파일을 만들고, `fetch`의 가드가 이 규칙을 어긴 호출을 요청 없이 단정 실패로 남긴다. 셸의 코드 페이지(`chcp`)나 로캘을 바꿔 피하지 않는다. 다른 기기와 CI는 그 셸 설정을 공유하지 않으므로 스크립트는 그들에게 여전히 깨진 채로 남는다. 이 결함도 Linux CI에서는 보이지 않는다.

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

모든 명령은 저장소 루트에서 실행한다. npm 명령은 `--prefix frontend`를 붙인다.

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
| `bash frontend/scripts/stress-test.sh` | CPU 부하 아래 Vitest 반복. 기본은 `yes` 부하 프로세스 CPU 수 × 2개, 12회다. `--runs N`·`--load N`, `--` 뒤는 그대로 `vitest run`에 넘긴다. 종료 코드는 0(통과)·1(실패한 실행 있음)·2(준비 실패, 시험 파일을 하나도 찾지 못한 필터 포함) |

`test:e2e`는 스크립트가 먼저 `vite build`를 실행하고, `playwright.config.ts`의 `webServer`가 그 산출물을 `preview`로 띄운다. 별도로 서버를 띄워 둘 필요가 없다. 빌드를 서버 기동과 분리한 이유는 `webServer`가 기존 서버를 재사용하면 빌드까지 건너뛰어 낡은 `dist/`를 검증하게 되기 때문이다. 그래서 `reuseExistingServer`는 항상 `false`이며 e2e는 dev·preview 기본 포트와 겹치지 않는 4183을 쓴다.

`test:e2e:run`은 `playwright test`만 실행한다. CI는 앞 단계에서 이미 `build`를 돌리므로 `test:e2e`를 쓰면 같은 빌드를 두 번 한다. 로컬에서는 한 번에 끝나는 `test:e2e`를 계속 쓴다.

Playwright는 재시도하지 않는다(`retries: 0`). 재시도는 flaky 실패를 green으로 덮어 첫 실패의 원인을 지운다. 그래서 trace도 `retain-on-failure`로 첫 실패부터 남긴다. flaky를 조사할 때만 `npm --prefix frontend run test:e2e:run -- --retries=2`처럼 명령에서 덮어쓴다.

`stress-test.sh`는 부하에서만 드러나는 Vitest 결함을 로컬에서 재현한다. CI 러너는 로컬 8코어보다 붐비고, effect 시점 결함은 React Scheduler가 프레임을 넘겨 양보할 때만 붉게 나온다 — `_bmad-output/implementation-artifacts/spec-frontend-effect-timing.md`의 기준선은 `yes` 16개 아래 전체 Vitest 12회 중 2회 실패였고 부하 없이는 거의 재현되지 않았다. 스크립트는 Node major를 `frontend/.nvmrc`와 대조한 뒤에만 돌고(Node 25의 실패를 부하 탓으로 읽지 않게), 머리에 커밋과 미커밋 변경 여부를 적고, 실패한 회차의 시험 이름을 모아 보이고 그 로그를 남긴다. 정상 종료·실패·Ctrl-C·TERM·HUP에서는 도는 중인 Vitest와 부하 프로세스를 끝내고 바로 나온다. SIGKILL은 트랩을 거치지 않아 부하가 남으므로 그때는 `pkill -x yes`로 지운다. 횟수는 기준선의 실패율에 맞춘다 — 기본 12회는 최소이고, 12회 중 1~2회 실패하던 결함이라면 깨끗한 12회만으로는 약한 근거다. 더 돌리거나 아래 `시험의 대기`의 첫 관측 가드처럼 결정적으로 잡는 시험과 함께 쓴다. CI에는 넣지 않았다 — 넣는다면 판정은 `.github/scripts`가 소유하고 `test-ci-scripts.sh`를 함께 고친다.

### 구조와 규칙

| 경로 | 역할 |
|---|---|
| `frontend/src/styles/tokens.css` | 디자인 토큰 단일 출처. `DESIGN.md` frontmatter의 평면 이름을 그대로 옮긴다 |
| `frontend/src/styles/global.css` | reset, 기본 타이포그래피, 전역 `focus-visible`, 모션 감소 규칙 |
| `frontend/src/content/` | 화면 카피 정본. 화면과 시험이 같은 문자열을 참조해 문구 표류를 막는다 |
| `frontend/src/screens/` | 화면 컴포넌트와 CSS Module |
| `frontend/src/session/` | 서버 세션 확인 훅과 상태 변경 전송(로그아웃·재인증 시작·연결 시작·연결 상태 조회·해제 준비·해제 확인). 로그인 상태 경로의 화면만 쓴다 |
| `frontend/src/test/setup.ts` | Testing Library 등록과 `fetch`·`XMLHttpRequest`·`sendBeacon` 감시 훅. 탈출구는 (메서드, URL) 쌍 단위이며 `stubSessionRequest`·`stubLogoutRequest`·`stubReauthenticationRequest`·`stubLinkStatusRequest`·`stubLinkRequest`·`stubUnlinkPrepareRequest`·`stubUnlinkConfirmRequest`·`stubUnlinkCancelRequest` 여덟이 열 수 있고 기본 거부는 그대로다 |
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
- 외부 요청은 전역 거부가 기본이다. 단위 시험이 여는 예외는 (메서드, URL) 쌍 단위이며 `GET /api/auth/session`·`POST /api/auth/logout`·`POST /api/auth/reauthenticate/<제공자>`·`GET /api/auth/link`·`POST /api/auth/link/<제공자>`·`POST /api/auth/unlink/<제공자>`·`POST /api/auth/unlink/<제공자>/confirm`·`POST /api/auth/unlink/<제공자>/cancel` 여덟뿐이고, 그 허용도 시험 하나의 범위다(`afterEach`가 닫는다). 진입 화면(`/`)은 이 탈출구를 쓰지 않으며 "문서·번들 외 요청 0건"이 그대로 유지된다.
- 재인증 화면은 새 경로가 아니다. 서버가 `reauthRequired`로 답하면 두 로그인 상태 화면이 본문 자리에 공유 패널(`components/ReauthPanel.tsx`)을 **제자리에** 렌더한다. 이동하지 않는 이유는 표시된 세션이 이미 자기 목적지에 있기 때문이며, 인증 화면으로 보내면 그 화면의 로그인 버튼을 서버가 거절해 무한 왕복이 된다. 패널은 세 제공자 버튼을 고정 순서로 그대로 보이고 연결된 제공자를 묻지 않으며, 로그아웃 버튼을 함께 둔다 — 표시된 세션이 화면에서 스스로 나갈 수 있게 되는 자리다. 제공자 공식 마크는 `components/ProviderMark.tsx` 하나가 소유하고 진입 화면·패널·설정 화면이 함께 쓴다.
- 설정 화면(`/settings`)은 로그인 수단 연결·해제를 관리한다. 보여 주는 것은 계정의 사실 하나이고(세 제공자 각각이 연결됐는가) 서버가 제공자 이름만 돌려주므로 `issuer`·`subject`·이메일·연결 시각은 화면에 도달하지 않는다. 이미 연결된 수단에는 상태와 해제 버튼이, 미연결 수단에는 연결 버튼이 있고, 그 아래에 본인 확인 버튼 셋이 함께 있다 — 연결·해제에는 최근 재인증이 필요한데 **일반 로그인은 그 시각을 남기지 않으므로**(Story 1.8) 그 자리가 없으면 첫 조작이 성립할 수 없다. 자동으로 이어 하지 않는다: 시작이 거부되면 표지 하나가 안내하고 사용자가 본인 확인을 직접 누른 뒤 이 화면에서 다시 누른다.
- **마지막 남은 수단만은 화면이 미리 설명한다.** 서버 응답은 그 거부를 다른 거부와 구분하지 않지만(구분하면 그 차이가 계정의 수단 구성을 알려 준다) 화면은 자기 목록에서 그 사실을 직접 만든다 — 사용자가 이미 보고 있는 것이고, 숨기면 비활성화된 버튼에 이유가 없어 접근성이 깨진다. 비활성은 `aria-disabled`이고 설명은 `aria-describedby`로 그 버튼에 붙는다. 권위는 여전히 서버의 삭제 문장이므로 화면을 우회한 직접 호출도 같은 거부로 끝난다.
- 해제의 확인 대화상자(`components/UnlinkDialog.tsx`)는 DESIGN.md `confirmation-dialog`가 규정한 형태다 — 제목에 대상과 결과를 쓰고, 삭제되는 것과 보존되는 것을 문단으로 나누고, **기본 포커스는 취소**이며, 포커스를 가두고, 닫을 때 호출점으로 돌려준다. `<dialog>`를 쓰지 않는 이유는 `showModal`의 동작이 층마다 절반만 살기 때문이고, 그 넷을 `components/useConfirmationDialog.ts` 하나가 직접 들고 세 확인 대화상자(`UnlinkDialog`·`PlanEndDialog`·`ConnectionRevokeDialog`)가 위임하는 편이 어느 층에서도 같은 동작을 보장한다. 대상명 재입력은 요구하지 않는다(DESIGN.md가 그것을 계정 삭제 급의 작업에만 건다). 해제 결과 표지 셋은 **URL을 지나지 않고** 화면당 하나뿐인 `role="status"` 영역이 말한다 — 왕복이 없어 문서가 바뀌지 않으므로 표지를 주소에 실을 이유가 없고(실으면 어떤 서버 경로도 만들지 않는 문장을 주소창이 만들어 낸다, `signedOut`과 같은 자리다), 섹션 안에 두면 취소·실패에서 포커스도 움직이지 않아 보조기술 사용자에게 아무 신호가 없다. 포커스는 성공에서만 결과 문장으로 옮겨 가고(그때 호출점 버튼이 사라진다) 나머지에서는 대화상자가 호출점으로 돌려준다 — 두 자리가 조건을 나눠 갖지 않으면 포커스가 잠깐 그 버튼에 얹혔다가 목록 갱신과 함께 `<body>`로 떨어진다. 대화상자가 열려 있는 동안 배경은 `inert`이고 문서 스크롤도 잠긴다.
- 설정 화면과 대시보드는 **서로를 밀어내지 않는다**. 서버의 `destination`은 "로그인 뒤 착지할 화면"이고 설정은 사용자가 스스로 여는 자리이므로, 대시보드를 이유로 밀어내면 그 경로에 도달할 방법이 없어진다(연결 왕복이 돌아오는 자리도 그 화면이다). 반대 방향도 같은 이유로 열려 있어야 한다 — 예외가 한 방향뿐이면 미인증으로 `/settings`를 열어 로그인한 사용자는 `destination`이 `settings`로 굳어 그 세션 내내 대시보드에 닿지 못한다. 두 화면 모두 온보딩만이 밀어낸다.
- 두 로그인 상태 화면의 머리말이 설정 화면으로 가는 링크를 준다. 전역 내비게이션은 아직 없고(Epic 1의 범위가 아니다) 그 링크가 없으면 설정 화면은 주소를 직접 쳐야만 닿는다. 탭 순서는 건너뛰기 링크 → 설정 링크 → 로그아웃이다.
- 경로는 라우터 없이 `App.tsx`의 닫힌 맵이 화면에 대응한다(`/`·`/onboarding`·`/dashboard`·`/settings`·`/plan-conditions`·`/operator`). 라우트 하나가 **다섯 곳**에 산다 — `App.tsx`의 경로 맵, `deploy/nginx/bokrilog.conf`의 허용 목록 정규식, 서버의 `AuthFlowHandlers.ALLOWED_DESTINATIONS`, `useSessionState`의 목적지 집합, 그리고 `content/auth.ts`의 `RETURN_DESTINATIONS`(로그인 뒤 돌아갈 수 있는 경로)다. 앞의 둘은 화면 경로 다섯을, 뒤의 셋은 복귀 목적지 넷(`/plan-conditions` 제외 — Story 2.3의 계획 조건 화면은 로그인 뒤 돌아가는 곳이 아니다. `/operator`는 서버가 운영자에게만 목적지로 허용하고 아니면 대시보드로 접는다 — Story 3.1)을 들며, 각 묶음 안에서 같은 집합이어야 하고 그 등가를 보는 검사는 아직 없다(미해소 항목). 한 곳만 고치면 증상이 제각각이다: Nginx가 빠지면 404, `App.tsx`가 빠지면 진입 화면, 서버 허용 목록이 빠지면 복귀가 대시보드로 접히고, 목적지 집합이 빠지면 타입이 막으며, `RETURN_DESTINATIONS`가 빠지면 미인증 진입에서 `next`가 실리지 않는다. 로그인 상태 경로는 `GET /api/auth/session`으로 서버 세션을 확인한 뒤에만 내용을 그리고, 미인증·오류는 인증 화면으로 되돌린다.
- 라이트 모드만 제공한다. 다크 모드는 별도 대비 검증 뒤 후속 범위다.
- 확대 검증은 브라우저 zoom 설정 대신 **뷰포트 폭 = 1280px ÷ 배율**로 재현한다(WCAG 1.4.10 표준 해석). 400%는 320×512, 200%는 640×512다.
- **effect 시점.** 커밋이 화면을 바꾸는 순간 함께 성립해야 하는 효과 — 초점 이동, `replaceLocation`, 타이머·리스너 등록과 해제, 그 효과들이 읽는 latest-ref 갱신 — 는 `useLayoutEffect`다. passive `useEffect`는 동기 lane 갱신에서만 커밋 끝에 바로 돌고, fetch 응답이 만든 갱신에서는 Scheduler의 다음 작업으로 미뤄진다. 부하가 걸리면 그 작업이 새 DOM을 이미 본 코드(시험의 `findBy*`·`waitFor` 직후 단정, 사용자의 키 입력)보다 늦고, 사용자에게는 한 프레임 동안 초점이 `<body>`에 머문다. 요청을 시작하는 effect는 passive로 둔다(그 요청의 기한 타이머나 `pageshow` 재확인을 함께 붙이는 것도 같다). 보이는 것을 바꾸는 부모 갱신(`onCalculable` — 그 보고로 발행 구역이 나타난다)과 `live` 표지도 passive다 — layout effect에서 부모 `setState`를 부르면 그리기 전에 동기 재렌더가 일어난다. 그리는 것을 바꾸지 않는 일회성 표지를 지우는 부모 갱신은 layout에 있어도 된다(`AdjustmentHistory`의 `Rows`가 부르는 `onFocused`). passive로 남긴 effect에는 근거를 주석으로 적는다. layout effect 본문에 새 동기 `setState`를 넣지 않고, 서로 순서에 기대는 효과는 함께 옮긴다(자식 → 부모 순서는 두 종류가 같다).
- **`inert` 배경으로 돌려주는 초점은 예외다.** 대화상자가 닫힐 때 호출점으로 돌려주는 초점은 passive effect의 정리에 둔다(`components/useConfirmationDialog.ts` — 세 확인 대화상자가 위임하는 메커니즘 하나). 호출점은 대화상자가 열린 동안 형제 래퍼의 `inert` 아래에 있다. layout effect의 정리는 닫는 커밋의 DOM 변경 단계에서 대화상자 삭제와 함께 돈다. React는 부모의 자식 삭제를 형제 갱신보다 먼저 처리하므로, 그 정리는 `inert` 해제보다 먼저 돌고 브라우저는 그 `focus()`를 무시한다. passive 정리는 커밋이 끝난 뒤 돈다. `Esc`·취소 같은 이산 입력이 닫으면 React가 그 커밋 끝에서 바로 비운다. 열 때의 기본 포커스와 키 처리기는 layout이고, 호출점은 한 번만 기록한다(개발 빌드의 `StrictMode`가 그 effect를 다시 돌릴 때는 포커스가 이미 취소에 있다). 복귀는 포커스를 잃었을 때만 한다. 같은 커밋에서 화면이 layout으로 옮겨 받은 포커스(해제 성공의 결과 문장)를 뒤늦게 덮지 않기 위해서다. jsdom은 `inert`를 모르므로 이 순서는 Playwright만 본다.
- **시험의 대기.** 비동기 대기(`findBy*`·`waitFor`) 직후에 효과나 늦게 오는 읽기 결과를 동기로 단정하지 않는다. 단정할 조건 자체를 `waitFor`로 기다리고, 읽기 전부터 있는 상태 영역은 영역이 아니라 텍스트를 기다린다. 재시도·`testTimeout`·`asyncUtilTimeout` 상향·단일 스레드로 실패를 덮지 않는다. 예외는 의도된 **첫 관측 가드**다. `waitFor`는 효과가 늦게 와도 통과하므로, 효과가 커밋에 붙어 있다는 사실은 따로 고정한다. 가드는 `src/test/firstSight.ts`의 `atFirstSight`로 act 환경을 끈 채 응답을 풀어 주고, 새 내용이 처음 보이는 순간(커밋 직후의 `MutationObserver` 마이크로태스크)에 효과의 결과를 동기로 읽는다. 그때 layout effect는 이미 돌았고 passive effect는 아직이다(커밋이 그리기를 위해 Scheduler를 양보시킨다). 그래서 부하 없이 결정적으로 갈린다. 종류마다 대표 하나를 둔다 — 목적지 이동과 응답이 옮기는 초점(`SignedInScreen.test.tsx`), 폴링 interval(`InitialInputsCollection.test.tsx`). 새 가드는 그 effect를 `useEffect`로 되돌리면 매번 실패하는지 확인한 뒤 넣는다.

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

#### 영업일 달력 seed 갱신(Story 3.6)

문의의 답변 목표("접수 다음 영업일부터 세 번째 영업일 18:00 KST")는 버전 seed 달력이 정한다. 지금 버전은 `KR-HOLIDAY-2026.1`(`V22__support_inquiry.sql`, 2026·2027)이다. 달력이 담지 않은 연도의 날을 지나야 하는 접수는 막지 않고 목표를 정하지 않는다(`CALENDAR_PENDING`, 화면 문장 "답변 목표를 아직 정하지 못했습니다") — 2027-12-30 접수가 그 첫 사례다. 새 버전 달력이 그 연도를 담게 되면 다음 조회 트랜잭션(보존 삭제와 같은 자리)이 그 미정 목표를 접수 시각으로 계산해 채우고 `calendar_version`을 새 버전으로 남긴다. 다음 해 달력을 더하는 순서는 다음과 같다.

1. 그 해 월력요항(우주항공청)이 발표되고 「관공서의 공휴일에 관한 규정」 개정·인사혁신처 공지(대체공휴일·임시공휴일·선거일)를 확인한 뒤에 시작한다. 발표 전 날짜를 추측해 넣지 않는다. 2028년 달력 추가는 Ask First다.
2. **기존 V22를 고치지 않는다.** 새 버전(예: `KR-HOLIDAY-2027.1`)으로 새 마이그레이션(`V<n>__support_calendar_<연도>.sql`)을 쓰고, 그 버전에 담을 모든 연도(앞 버전의 연도 포함)의 `support_calendar_year` 행과 공휴일 행을 넣는다. 머리말에 출처와 확인일을 적는다.
3. `SupportInquiryApi.CALENDAR_VERSION`을 새 버전으로 바꾼다. 이미 목표가 정해진 문의는 접수 시점의 `calendar_version`·`target_at`을 그대로 가진다(소급 재계산하지 않는다). 미정(`CALENDAR_PENDING`) 문의만 다음 조회가 채운다. 집계의 처리 영업시간은 문의마다 그 행의 `calendar_version` 달력으로 센다.
4. `BusinessDayCalendarTest`의 fixture를 새 버전 seed와 맞추고(그 시험은 지금 버전을 넣은 최신 마이그레이션을 찾아 seed = fixture를 단정한다) 새 해의 경계(연말·설 연휴·대체공휴일) 사례를 더한다.

### 구조와 규칙

| 경로 | 역할 |
|---|---|
| `backend/build.gradle.kts` | Java 25 toolchain, Spring Boot 4.1.1 BOM, `web`·`jdbc`·Flyway·Spring Security OAuth2 Client·Spring Session JDBC·Actuator·Prometheus registry(Story 3.7) 운영 의존과 Modulith 2.1.1 BOM·Modulith·Testcontainers 시험 의존. 컴파일 경고는 `-Werror`로 실패다 |
| `backend/gradle.properties` | toolchain 자동 프로비저닝 끄기, 인코딩 고정 |
| `backend/src/main/java/com/bokrilog/BokriLogApplication.java` | 진입점이자 Modulith 루트. 모듈은 이 클래스 패키지의 직속 하위 패키지다 |
| `backend/src/main/java/com/bokrilog/StatusController.java` | `GET /api/status`. 고정된 최소 필드 `{"status":"UP"}`. 외부 liveness 검사의 대상이다(Story 3.7) |
| `backend/src/main/java/com/bokrilog/TelemetryConfig.java`·`TelemetryAllowlist.java` | 메트릭의 닫힌 표와 그것을 집행하는 `MeterFilter`(공통 tag `env`, `uri` 템플릿화, 표 밖 거부). Actuator에 닿는 설정은 여기 한 곳이다(Story 3.7) |
| `backend/src/main/resources/logback-spring.xml`·`RedactingJsonEncoder.java`·`LogSecretGuard.java`·`ConfiguredSecrets.java` | 로그의 유일한 출력(JSON 한 줄, 허용 키, 가림)과 구성 비밀값 감지 → 강한 신호 3번(Story 3.7) |
| `backend/src/main/java/com/bokrilog/ApiErrorHandler.java` | 공통 오류 계약과 프레임워크 오류 경로 대체 |
| `backend/src/main/java/com/bokrilog/AuthSecurityConfig.java` | 인증 표면과 세션 정책. 제공자 등록 맵(Google·네이버·카카오), PKCE 강제, 세션 고정 방어, CSRF·`Origin` 검증, 401·403 응답 |
| `backend/src/main/java/com/bokrilog/AuthSessionService.java` | 검증된 `(issuer, subject)`를 `identity` 공개 API로 계정·세션에 옮긴다. 경합 재시도와 롤백 경계. 재인증의 계정 쪽 판정(`confirmReauthentication`)도 여기 하나다 |
| `backend/src/main/java/com/bokrilog/AuthFlowHandlers.java` | 인증 흐름 경계(인증된 세션의 로그인 시작·콜백 거절, 무장된 재인증·연결 시작 통과), 성공 시 목적지 허용 목록과 인증 주체 교체, 재인증·연결 분기, 연결 성공의 세션 재회전, 실패 표지 수렴 |
| `backend/src/main/java/com/bokrilog/ReauthenticationFlow.java` | 세션에 심는 일회성 재인증 흐름. 무장 record(제공자·사용자·복귀 목적지·무장 시각), `arm`·`peek`·`consume`·`discard`, 무장 수명 5분·신선도 창 5분·clock skew 60초와 `auth_time` 판정 |
| `backend/src/main/java/com/bokrilog/AuthSessionController.java` | `GET /api/auth/session`(인증 여부·목적지 종류·재인증 요구·CSRF 토큰), `POST /api/auth/reauthenticate/<제공자>`, `GET /api/auth/link`(연결된 제공자 이름), `POST /api/auth/link/<제공자>`, `POST /api/auth/unlink/<제공자>`(해제 준비), `POST /api/auth/unlink/<제공자>/confirm`(해제 실행), `POST /api/auth/unlink/<제공자>/cancel`(준비 되돌리기), 인증 시작 fail-closed 응답. 세 흐름이 서로를 버리는 자리도 여기 하나이며 **무장이 확정된 뒤에만** 버린다 |
| `backend/src/main/java/com/bokrilog/LinkFlow.java` | 세션에 심는 일회성 **연결** 흐름. 무장 record(대상 제공자·사용자·복귀 경로·허가 참조·멱등 키·발급 시각의 세션 id·무장 시각), `arm`·`peek`·`consume`·`discard`, 무장 수명 5분. 재인증 무장과 다른 세션 속성이다 |
| `backend/src/main/java/com/bokrilog/LinkService.java` | 연결의 판정과 실행. 시작(이미 연결됨 게이트 → 허가 발급 → 무장)과 완료(허가 소비 트랜잭션 안에서 `linkSocialIdentity`)를 소유하고 거부를 하나의 값으로 접는다. 매핑이 거부되면 예외로 그 트랜잭션을 되돌린다 |
| `backend/src/main/java/com/bokrilog/UnlinkFlow.java` | 세션에 심는 **해제** 무장. record(대상 제공자·사용자·허가 참조·멱등 키·무장 시각), `arm`·`peek`·`discard`, 무장 수명 5분. `LinkFlow`와 달리 `returnPath`·`boundSessionId`·`consume`이 없다 — 제공자 왕복이 없어 복귀할 자리도 그 사이의 세션 회전도 없고, 최종 확인이 무장을 지우지 않으므로(그러면 예외가 허가를 고아로 남기고 재전송 경로가 사라진다) 일회성은 허가가 갖는다 |
| `backend/src/main/java/com/bokrilog/UnlinkService.java` | 해제의 판정과 실행. 준비(대상 연결됨·수단 둘 이상 사전 확인 → 허가 발급 → 무장)와 최종 확인(허가 소비 트랜잭션 안에서 `unlinkSocialIdentity`, 커밋 뒤 다른 세션 폐기와 현재 세션 회전), 취소(무장과 허가 되돌리기)를 소유하고 거부를 하나의 값으로 접는다. **마지막 수단 보호의 권위는 여기가 아니라 `identity`의 삭제 문장이다** |
| `backend/src/main/java/com/bokrilog/ActionGrant.java` | 재인증 허가의 값 표현(다섯 귀속 record)과 정책 상수. 수명 5분·재인증 창 5분·미래 skew 60초, 불투명 참조 생성, 저장과 해시가 함께 쓰는 `normalize`, `canonicalConfirmationHash`, 길이 게이트 |
| `backend/src/main/java/com/bokrilog/ActionGrantRepository.java` | `action_grant` 테이블의 유일한 writer. 발급 INSERT(재인증 신선도 술어), 같은 작업의 살아 있는 허가 무효화, 소비 조건부 UPDATE(다섯 귀속·미소비·미만료), 결과 저장, 같은 요청의 저장된 결과 조회, 사용자 범위 만료 행 sweep. 영향 행 수가 곧 판정이다 |
| `backend/src/main/java/com/bokrilog/ActionGrantService.java` | 민감 작업의 허가 포트. `issue`·`consume` 둘뿐이고 HTTP 표면이 없다. 세션에서 읽은 사실 하나(`SessionFacts`)로 주체·수명·의심 표시를 판정하고, 소비 UPDATE와 호출자 작업을 한 트랜잭션에 넣으며(경계를 포트가 소유한다) 세션 회전 의무를 결과 타입 이름으로 알린다 |
| `backend/src/main/java/com/bokrilog/DevOidcStub.java` | 결정적 local OIDC 제공자의 논리. 자기 RSA 키로 JWKS를 제공하고 ID Token을 서명한다(기본 claim에 `auth_time`이 들어 있고 `TokenPlan`이 그 값을 주입·생략한다). 받은 `max_age`·`prompt`·`claims`는 기록만 하고 행동을 바꾸지 않는다. HTTP도 Spring도 없고 자격정보 리터럴도 없다 |
| `backend/src/main/java/com/bokrilog/DevOidcStubConfig.java` | 그 스텁의 HTTP 표면(`/api/dev-oidc/<제공자>/**`)과 제공자 등록. 제공자마다 `DevOidcStub` 인스턴스가 하나(issuer·client·서명 키가 다르다)이고 라우터 빈은 하나다. `dev-oidc` 프로필에만 존재하고 실제 제공자 등록(Google·네이버·카카오 어느 쪽이든)과 함께 활성이면 기동을 거부한다 |
| `backend/src/main/java/com/bokrilog/identity/` | 첫 모듈. 계정 소유권·삭제 상태의 권위. 공개 API 세 개(`AccountLifecycleApi`·`AccountAccessApi`·`AccountDeletionApi`)와 `internal` 구현. 마지막 수단 보호가 `unlinkSocialIdentity`의 삭제 문장 안에 있다 |
| `backend/src/main/resources/db/migration/` | Flyway 마이그레이션. `V1__identity_account.sql`이 guard·payload·매핑 세 테이블을, `V2__spring_session.sql`이 Spring Session JDBC의 세션 두 테이블을, `V3__action_grant.sql`이 재인증 허가 테이블 하나를 만든다 |
| `backend/src/main/resources/application.yml` | 포트, 상대 리다이렉트, 세션 쿠키 속성, 오류 노출 끄기, 로그 수준, datasource·flyway·session, `bokrilog.auth` 이름, `dev-oidc`·`migrate` 프로필. 자격정보를 두지 않는다 |
| `backend/src/test/java/com/bokrilog/PostgresTestcontainersConfig.java` | 시험 전체가 공유하는 실제 PostgreSQL 컨테이너 하나 |
| `backend/src/test/java/com/bokrilog/OidcStubConfiguration.java` | 위 `DevOidcStub`을 루프백 HTTP 서버에 올리는 시험 표면. 스텁 논리를 다시 만들지 않는다 |
| `backend/src/test/java/com/bokrilog/AuthFlowTestBase.java` | 인증 시험 전부의 공통 배선(왕복·재인증·CSRF·쿠키·저장소 헬퍼). 같은 컨텍스트를 공유하려면 `@DynamicPropertySource`가 한 곳에 있어야 한다 |
| `backend/src/test/java/com/bokrilog/Auth*Test.java` | 토큰·`state` 검증, 계정·매핑, 세션·쿠키·CSRF, 복귀 목적지·redaction, 삭제 경로, 로그아웃·만료, 재인증과 세션 회전, 로그인 수단 연결 |
| `backend/src/test/java/com/bokrilog/ActionGrant*Test.java` | 허가의 발급 게이트·다섯 귀속·만료 경계·무효화, 트랜잭션 경계와 `operation_id` 멱등, 동시 소비 |
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
- 내부 경로·스택·프레임워크 기본 오류 본문·환경 변수 이름은 응답과 로그 어디에도 남기지 않는다. 그래서 `spring.main.log-startup-info`가 꺼져 있고(기동 로그가 jar 경로와 실행 사용자를 남긴다), 예외의 스택·메시지는 **어느 수준에서도** 로그에 찍지 않는다(Story 3.7 — 로그 encoder의 허용 키에 없다). 진단에 남는 것은 예외 클래스의 단순 이름(`exception`)과 `correlationId`뿐이고, 처리하지 못한 스레드 예외도 기본 `UncaughtExceptionHandler`가 같은 모양의 한 줄로 바꾼다(stderr 스택 없음). 스택이 필요한 조사는 같은 입력을 로컬·시험에서 재현해서 한다 — 운영 로그 수준을 올려도 스택은 나오지 않는다.
- 상태 응답에 시각·버전·호스트명·빌드 정보를 넣지 않는다. 인증 없이 공개되는 엔드포인트라 담는 모든 값이 곧 공개 정보다.
- JPA·`spring-security-test` 의존은 아직 없다. 추가는 그것을 필요로 하는 스토리의 SPEC이 목적과 함께 승인한다. Actuator·Micrometer Prometheus registry는 `spec-3-7-observability-foundation.md`가 관리 포트의 메트릭 노출로 승인했다(루트 설정 한 곳, 모듈은 `MeterRegistry`만). Spring Modulith·PostgreSQL·Flyway·Testcontainers는 `spec-1-3-identity-ownership.md`가, Spring Security OAuth2 Client·Spring Session JDBC는 `spec-1-4-google-sign-in.md`가 승인해 들어왔다.
- 검증을 손으로 만들지 않는다. Authorization Code + PKCE(S256), 일회성 `state`·`nonce`, ID Token의 JWS 서명·허용 알고리즘(RS256)·JWKS와 `iss`·`aud`·`azp`·`exp`·`nonce` 검증, 세션 고정 방어는 Spring Security OAuth2 Client의 계약이다. `AuthSecurityConfig`가 하는 일은 그 계약을 이 저장소의 경계에 맞추는 배선뿐이다.
- 백엔드는 보안 응답 헤더를 만들지 않는다. Spring Security의 기본 헤더 writer를 끈 이유가 이것이다. 켜 두면 진입점과 애플리케이션이 같은 헤더를 각각 실어 값이 두 번 나가고, 헤더를 전문 비교하는 `verify-same-origin.sh`가 먼저 깨진다. 캐시 지시가 필요한 응답은 그 응답이 직접 정한다 — 계정의 사실을 돌려주는 두 경로(`GET /api/auth/session`·`GET /api/auth/link`)가 자기 `no-store`를 붙인다. 진입점도 같은 지시를 내므로 그 응답에는 같은 이름의 헤더가 두 번 실리고, 값이 같으므로 의미는 하나다.
- CSRF와 동일 출처 `Origin` 검증은 `/api/auth/` 아래의 상태 변경 요청에만 걸린다. 전역으로 두면 `CsrfFilter`가 서블릿보다 먼저 답해 `POST /api/status`의 405와 identity 탐침의 404가 전부 403으로 뒤집힌다. CSRF 토큰은 세션에 귀속하며 쿠키 저장소를 쓰지 않는다.
- 세션은 Spring Session JDBC로 PostgreSQL에 산다. 브라우저에는 `HttpOnly`·`Secure`·`SameSite=Lax` 불투명 쿠키만 두고 로그인 성공 시 세션 ID를 회전한다. 세션이 담는 값은 내부 `user_id` 하나이며 ID Token·액세스·리프레시 토큰은 검증 직후 버린다. `AuthFlowHandlers`가 인증 주체를 내부 식별자 하나로 바꿔 다시 저장하고, 제공자 토큰 저장소는 아무것도 쓰지 않는다.
- 제공자 엔드포인트를 discovery로 찾지 않는다. `spring.security.oauth2.client.provider.<id>.issuer-uri`는 기동 시점에 네트워크 왕복을 하므로 쓰지 않는다. Google은 `CommonOAuth2Provider.GOOGLE`이 정적으로 들고 있는 정본 주소(`issuer` 포함)를 쓰고, 네이버와 카카오는 `CommonOAuth2Provider`에 없으므로 `AuthSecurityConfig`가 각 공식 discovery 문서의 정적 주소 네 개씩을 상수로 든다. 네이버의 OIDC 경로는 `/oauth2/*`이며 레거시 OAuth 2.0 API의 `/oauth2.0/*`과 다르고, 카카오의 JWKS는 issuer 바로 아래의 `/.well-known/jwks.json`이라 네이버의 `/oauth2/jwks`와 모양이 다르다. 재정의가 필요한 환경만 `bokrilog.auth.<제공자>.*`로 네 값을 준다.
- 제공자 등록은 이름 → `ClientRegistration` 맵이고 fail-closed는 **제공자 단위로 독립**한다. 한 제공자의 자격정보가 비어도 다른 제공자의 등록과 로그인 경로는 살아 있으며, `isConfigured()`는 "하나 이상 구성됨"이다. 판정을 한 번에 접으면 카카오를 등록하지 않은 개발 환경에서 Google 로그인까지 죽는다. 네이버는 discovery가 `client_secret_post`·`none`을, 카카오는 `client_secret_post` 하나만 광고하므로 두 등록의 client 인증 방법을 그렇게 두고 `userNameAttributeName`도 손으로 `sub`을 준다. 카카오의 client secret은 콘솔의 선택 기능이지만 fail-closed 게이트를 무르지 않으므로 운영자가 그것을 켜야 등록이 만들어진다.
- 카카오 콘솔에서 운영자가 켜야 하는 것이 셋이다. **카카오 로그인 활성화**(꺼져 있으면 인가 요청 자체가 서지 않는다), **OpenID Connect 활성화**(꺼져 있으면 ID Token이 오지 않아 `scope=openid`가 뜻을 잃는다), **Client Secret 발급**(선택 기능이고 우리 fail-closed 게이트가 필수로 요구한다). 그리고 Redirect URI를 콘솔에 **사전 등록**해야 한다 — 값은 `<BOKRILOG_PUBLIC_BASE_URL>/api/auth/callback/kakao`이고 `ProviderClientRegistrations.put`이 그 모양으로 조립한다(요청 헤더에서 유도하지 않으므로 콘솔 값과 글자까지 같아야 한다). 넷 중 하나라도 빠지면 로그인은 공통 오류 계약으로 끝나고 응답은 "운영자가 구성하지 않았다"와 구분되지 않는다.
- 카카오의 `sub`은 **pairwise**다(discovery의 `subject_types_supported`). 그 값은 사용자만이 아니라 **client 등록에도** 묶이므로, 조회 키가 `(issuer, subject)` 하나인 이 시스템에서 카카오만 제공자별 단서를 갖는다 — 카카오 앱을 다시 만들거나 REST API 키를 교체하면 기존 `(kauth.kakao.com, sub)` 매핑 전부가 고아가 되고 돌아온 사용자마다 **조용히 새 계정**이 생긴다. 오류도 경고도 없이 신규 가입 경로를 지나므로 그 사건은 응답으로 관측되지 않는다. Google·네이버에는 없는 제약이며, 카카오 쪽 키 교체는 계정 마이그레이션을 동반해야 하는 작업이다.
- `identity_social_link.provider`에는 인증에 **실제로 쓰인** registration id가 들어간다. 값은 `OAuth2AuthenticationToken#getAuthorizedClientRegistrationId()`가 알려 주고 `AuthFlowHandlers`가 `AuthSessionService.establish`로 넘긴다. 리터럴을 쓰면 어느 제공자로 로그인해도 같은 이름이 저장되고, 그 컬럼을 술어로 쓰는 이후 스토리(로그인 수단 연결·해제)가 거짓을 읽는다.
- 재인증 표면은 두 단계다. `POST /api/auth/reauthenticate/<제공자>`가 세션에 일회성 흐름을 심고(204), 그 뒤 브라우저가 `GET /api/auth/authorize/<제공자>`로 최상위 이동한다. 나눈 이유는 보호다 — 무장은 상태 변경이라 세션 귀속 CSRF 토큰과 동일 출처 `Origin`을 지나야 하고, 인가 시작은 제공자로 가는 내비게이션이라 `GET`이어야 한다. 한 경로에 합치면 표지가 붙은 링크 하나로 남의 세션에 재인증 흐름을 심을 수 있다. 무장 요청의 답은 셋뿐이다 — 모르는 제공자 404, 주체 없음 403, 그 밖에는 204. 등록 구성 여부는 조회하지 않으며 그 판정은 뒤따르는 인가 시작이 이미 소유한다.
- 재인증은 **두 번째 인증 경로가 아니다**. 같은 `oauth2Login` 왕복을 지나므로 PKCE·`state`·`nonce`·JWS·`iss`·`aud`·`azp`·`exp` 검증과 세션 회전이 로그인과 같은 배선에서 나오고, 재인증이 더하는 것은 판정 둘뿐이다 — 검증된 `(issuer, subject)`가 현재 사용자의 것인가(`AuthSessionService.confirmReauthentication`, `findUserIdBySocialIdentity` + `AccountAccessApi.decide(SENSITIVE)`)와 제공자가 서명한 `auth_time`이 신선한가(`ReauthenticationFlow.isFreshAuthentication`). 별도 등록 id(`google-reauth` 같은 것)를 만드는 길은 막혀 있다: 두 등록이 같은 issuer로 해석되면 `rejectSharedIssuer`가 기동을 거부한다.
- 복귀 목적지는 **무장 시각에** 붙들어 둔다. 출처는 세션이 이미 가진 확정 목적지 하나이고 클라이언트는 목적지를 보내지 않는다. 그래서 콜백이 들고 온 어떤 값도 목적지가 되지 않으며, 재인증 시작에서는 `next` 처리를 건너뛴다. 성공은 그 목적지로 표지 없이 돌아가고 실패·취소는 같은 목적지로 표지 하나(`reauthFailed`·`reauthCancelled`)를 달고 돌아간다 — 인증 화면이 아니다. 이미 로그인한 사용자를 로그인 화면으로 보내면 표지가 설명하려는 상황과 화면이 어긋난다.
- 최근 인증 증거는 제공자가 서명한 `auth_time` 하나다. 부재·신선도 창(5분) 초과·허용 skew(60초) 밖의 미래가 모두 실패이고 완화하지 않는다. 콜백 도착 시각·제공자 세션의 존재·계정 선택은 증거가 아니다. 제공자별로 다른 것은 **인가 요청 파라미터**뿐이다.

  | 제공자 | 보내는 파라미터 | 공표된 증거 | 증거 부재 시 |
  |---|---|---|---|
  | Google | `max_age=300` + `claims={"id_token":{"auth_time":{"essential":true}}}` | ID Token `auth_time`(공식 문서). `prompt=login`은 **미공표** | 재인증 실패 |
  | 네이버 | `max_age=300` | **없음**(공식 답변: ID Token claim 구성 문서 미제공) | 재인증 실패 |
  | 카카오 | `max_age=300` + `prompt=login` | ID Token `auth_time`(문서·discovery), `prompt=login`(문서) | 재인증 실패 |

  네이버가 실연동에서 fail-closed될 수 있고 그것이 계약이다. 막아 두지 않는 이유는 실제로 `auth_time`을 돌려줄 수도 있기 때문이며, 막으면 그 사실을 확인할 기회 자체가 사라진다. 판정은 콜백의 claim 하나로 하고 결과는 실연동이 알려 준다. 이 파라미터들은 **무장된 흐름에만** 얹는다 — 상시로 얹으면 일반 로그인이 매번 재인증을 요구하고 카카오의 `prompt=login`은 이미 로그인한 사용자에게 매번 자격정보를 다시 묻는다.
- 재인증을 요구받은 세션만 `GET /api/auth/session`에서 갈라진다. 그 응답에 `reauthRequired: true`와 세션 귀속 CSRF 토큰이 함께 실리고, **폐기된 세션과 미인증 요청의 본문은 글자까지 그대로다**. Story 1.5가 표시와 폐기를 응답으로 구분하지 않게 한 예외를 Story 1.8이 되짚은 자리이며, 구분하지 않으면 표시된 세션은 토큰을 받지 못하고 로그인 버튼은 "이미 인증된 세션"으로 거절당해 목적지로 되돌려진다 — 되살릴 방법이 없는 무한 왕복이다. 구분을 열어도 얻는 것은 "이 세션이 표시됐다"는 사실 하나이고 회복에는 제공자 자격이 필요하다. 표시된 세션에 열리는 상태 변경은 로그아웃과 재인증 시작 둘뿐이고 나머지는 403이다.
- 인증된 세션에 도착한 **무장 없는 콜백**은 검증에 넘기지 않고 지금 목적지로 되돌린다. Story 1.8 전에는 인증된 세션이 진행 중인 authorization request를 가질 수 없어(시작이 거절됐다) 이 경로가 필요 없었다. 재인증이 그 전제를 깨므로 경계를 콜백까지 넓혔다 — 넓히지 않으면 흐름이 만료된 뒤 도착한 콜백이 성공 핸들러의 **로그인 분기**로 들어가 같은 세션 행에 다른 계정을 심을 수 있다.
- **마지막 수단 보호는 삭제 문장의 술어다.** `identity`가 계정 생명주기 행을 `FOR UPDATE`로 잠근 뒤 한 문장으로 `ACTIVE`·대상 매핑 존재·남은 수단 2개 이상을 함께 판정하고 0/1행으로 답한다(`AccountLifecycleApi#unlinkSocialIdentity`). 애플리케이션이 먼저 세고 나중에 지우는 형태를 만들지 않는 이유는 그 사이가 곧 "동시 요청으로 모든 수단이 사라지는" 창이기 때문이며, 잠금과 술어 둘 다 필요하다 — 잠금만으로는 서로 다른 트랜잭션의 스냅숏이 갈리고, 술어만으로는 잠금 없는 동시 실행에서 둘 다 통과한다. `UnlinkService`의 사전 확인은 소비될 수 없는 허가를 만들지 않기 위한 게이트일 뿐 권위가 아니다.
- 해제는 표면이 셋이고 **경로가** 그것을 가른다 — `POST /api/auth/unlink/<제공자>`가 허가를 발급해 무장하고(확인 대화상자를 여는 근거다) `POST /api/auth/unlink/<제공자>/confirm`이 그것을 소비하며 `POST /api/auth/unlink/<제공자>/cancel`이 그 준비를 되돌린다. 앞의 둘을 합치면 대화상자를 여는 순간 매핑이 사라져 "최종 확인 전에는 아무것도 바뀌지 않는다"가 성립하지 않고, 취소가 없으면 화면의 "바뀐 것은 없습니다"가 거짓이 되며 아무도 참조하지 않는 허가가 수명을 산다(취소는 무장 유무로 답을 나누지 않는 멱등 204다 — 나누면 그 경로가 오라클이 된다). 해제에는 OIDC 왕복이 없으므로 **확인 대화상자가 그 자리를 대신하고**, 그래서 1.9 포트의 귀속 세션 id 제시가 필요 없다(발급과 소비 사이에 세션이 회전하지 않는다). 등록 구성 여부도 보지 않는다 — 연결 시작의 그 게이트는 뒤따르는 인가 시작이 fail-closed로 끝나는 것이 근거였고, 자격정보가 빠진 제공자일수록 떼어 낼 수 있어야 한다.
- **무장을 버리는 일은 언제나 게이트 뒤다.** 거절되는 시작 요청 하나가 진행 중이던 다른 흐름의 무장과 허가를 파괴하면, 이미 연결된 제공자를 눌러 본 사용자가 방금 연 해제 대화상자를 잃는다. 그래서 각 `start`는 허가 발급이 성공한 **뒤에** 앞선 무장을 대신하고(같은 대상이면 허가를 다시 무효화하지 않는다 — 방금 발급한 것이 그 술어에 걸린다), 상대 서비스의 흐름은 컨트롤러가 그 `start`의 성공을 보고 버린다.
- **최종 확인은 무장을 지우지 않는다.** 지우면 둘이 깨진다 — `UnlinkRejected`가 아닌 예외가 허가를 고아로 남기고, 응답을 잃은 클라이언트가 같은 `operation_id`를 다시 제시할 길이 없어져 1.9의 재전송 계약이 도달할 수 없는 코드가 된다. 재전송에서는 업무 변경이 돌지 않았으므로 세션 정리도 다시 하지 않는다(그 구분은 허가 포트에 건넨 작업이 불렸는가 하나로 한다). 일회성은 무장이 아니라 허가의 성질이며 권위는 조건부 UPDATE 한 문장이다.
- 해제 성공은 **현재 세션 외의 그 사용자 세션을 모두 폐기하고 현재 세션을 회전시킨다**(`SessionGuard#revokeSessionsOfExcept`). 세션은 어떤 제공자로 인증됐는지 기록하지 않으므로(세션이 담는 값은 내부 `user_id` 하나다) "해제된 수단에서 유래한 세션"을 고를 수 없고, 남는 답은 fail-safe 쪽 하나다. 순서가 계약이다 — 폐기가 먼저이고 회전이 나중이며, 뒤집으면 `changeSessionId`가 요청 끝에야 저장소에 반영되는 탓에 폐기가 자기 세션을 "다른 세션"으로 분류해 지운다. 정리는 커밋 **뒤**에만 한다: 트랜잭션 안에서 하면 매핑 제거가 롤백되는 경로에 세션만 사라진 상태가 남는다. 그 정리가 실패해도 응답은 성공이다 — 매핑은 이미 커밋됐으므로 예외를 올리면 화면이 "해제하지 못했다"를 말하면서 실제로는 해제된 상태가 된다. 그리고 조회와 삭제 사이에 커밋된 로그인은 놓칠 수 있으므로 화면 카피는 "모두 종료됐다"가 아니라 사용자가 할 일을 말한다.
- 민감 작업의 허가는 서버가 보관하는 불투명 행 하나다(`SECURITY-ACTION-1.0`). 포트는 `ActionGrantService`의 `issue`·`consume` 둘뿐이고 **HTTP 표면이 없다** — 실행 가능한 민감 command가 아직 없으므로 지금 엔드포인트를 두면 소비자 없는 표면이 되고, 시험용 상태 변경을 외부 API로 여는 것은 `SessionGuard`가 이미 거절한 형태다. 발급은 재인증 신선도(5분, 미래 쪽은 skew 60초)·의심 세션 표시·세션 절대 수명·세션 주체 일치·삭제 상태를 보고, 소비는 **조건부 UPDATE 한 문장**이 user·session·command·target·canonical confirmation hash 다섯 귀속과 미소비·미만료를 함께 판정한다. 거부는 하나의 값이며 만료·이미 사용됨·귀속 불일치·삭제 상태·의심 세션·사라진 세션이 호출자에게 구분되지 않는다. 반면 **호출자가 계약을 어긴 경우는 예외다** — 이미 트랜잭션을 연 채로 부르거나 상한을 넘는 결과를 돌려주는 것은 사용자가 만든 상황이 아니라 배선 실수이고, 거부로 접으면 원인을 볼 자리가 없어진다.
- 허가의 두 인자(`session`·`userId`)는 모두 호출자가 건네므로 **포트가 서로 대조한다**. 세션 id는 인자로 받지 않고 그 세션에서 읽으며(`HttpSession#getId`), `userId`는 그 세션에 저장된 주체(`AuthFlowHandlers`가 심은 `PreAuthenticatedAuthenticationToken`)와 같아야 한다. 대조가 없으면 "세션이 유일한 `user_id` 권위"가 문장으로만 남는다. 세션이 없거나 이미 무효화된 경우(접근자가 `IllegalStateException`을 던진다)도 같은 거부 하나로 접힌다.
- 발급은 같은 `(user_id, command, target)`의 **살아 있는 허가를 먼저 무효화한다**. 재인증 시각은 세션 속성이라 발급 뒤에도 남고, 그대로 두면 본인 확인 한 번이 같은 작업의 5분짜리 실행 권한을 여럿 만든다. 무효화와 발급은 한 트랜잭션이므로 발급이 서지 않으면 앞의 허가도 그대로 남는다.
- **허가의 시각 권위는 PostgreSQL 하나다.** 발급이 `expires_at`을 절대 시각으로 못 박고 만료 판정은 소비 UPDATE의 술어(`now() < expires_at`)가 한다. 재인증 신선도도 같은 자리에서 판정한다 — 발급은 조건부 INSERT이고 그 술어가 창을 **양쪽에서** 닫는다(과거 쪽 5분, 미래 쪽 skew 60초). 재인증 시각만은 애플리케이션이 쓰는 값이라(`AuthFlowHandlers`) 미래 상한이 없으면 앞선 시계가 기록한 값이 창을 영원히 만족한다. 애플리케이션 시계로 판정하면 인스턴스마다 값이 다르고 뒤로 갈 수 있으며, 판정과 소비가 두 문장으로 갈리는 순간 그 사이가 곧 동시 소비의 창이다. `issued_at + 5분`을 소비 시점에 다시 계산하지 않는 것도 같은 이유이고, 스키마의 `expires_at > issued_at` CHECK가 그 불변식을 적어 둔다. 대가는 시각 경계를 단위 시험으로 볼 수 없다는 것이고 그래서 그 시험이 Testcontainers를 요구한다. 수명 5분·재인증 창 5분·skew 60초는 Story 1.8의 값들과 **같은 숫자지만 다른 상수**다 — 그쪽은 OIDC `max_age`·OAuth 왕복 시간·제공자 시계에 묶여 있고 이쪽은 사람의 시간과 우리 인스턴스들 사이의 시계다.
- 소비와 업무 변경은 **같은 트랜잭션**에서 커밋되고 그 **경계는 포트가 소유한다**(`TransactionTemplate`). 업무 변경이 예외로 끝나면 소비 UPDATE도 함께 롤백되어 "허가만 소비된 성공"이 남지 않고 사용자는 같은 허가로 다시 시도할 수 있다. 호출자가 이미 트랜잭션을 연 채로 부르면 거절한다 — 참여하면 멱등 충돌이 호출자의 트랜잭션을 rollback-only로 만들어 그 커밋이 `UnexpectedRollbackException`으로 끝나고, `REQUIRES_NEW`는 "소비와 업무 변경이 같은 경계"라는 AC-3 자체를 깬다.
- 멱등의 권위는 **`(user_id, operation_id)` 부분 유일 인덱스**다. 사용자 축이 함께 들어가는 이유는 `operation_id`가 소비자가 정하는 추측 가능한 값일 수 있어서다 — 전역 유일이면 먼저 쓴 사용자가 다른 사용자의 그 값을 영구히 막는다. 애플리케이션이 "이미 있나?"를 먼저 조회하는 형태는 동시 재전송에서 둘 다 통과하므로, 인덱스가 두 번째를 거절하게 두고 그 충돌을 "이미 성공한 실행"으로 읽는다. 그 충돌은 **우리 인덱스의 것일 때만** 그렇게 읽는다 — 호출자의 업무 변경이 던지는 `DuplicateKeyException`(유일 제약 위반이 정상 경로인 `linkSocialIdentity`가 대표 사례다)은 그대로 전파된다. 저장된 결과를 돌려주는 조건은 `operation_id` 하나가 아니라 **요청 지문 전체**다(허가 참조·사용자·command·target·확인 해시). 그러지 않으면 같은 값을 다른 작업에 재사용한 요청이 실행되지 않은 채 앞 작업의 결과를 성공으로 받는다. 세션만은 지문에서 뺀다 — 소비 성공이 세션 회전을 요구하므로 응답을 잃은 재전송은 반드시 다른 세션에서 온다.
- 허가 소비 성공 시의 **세션 재회전은 이 포트가 하지 않는다**. 회전은 Spring Security 필터가 HTTP 요청 문맥에서만 수행하고 이 포트에는 그 문맥이 없다. 대신 성공 결과의 타입 이름이 그 의무를 들고 있고(`Consumption.ConsumedRequiringSessionRotation`) 실제 배선은 첫 소비자인 연결 콜백이 `request.changeSessionId()`로 한다(`AuthFlowHandlers.onLinkSuccess`).
- 로그인 수단 연결은 **세 단계가 순서대로** 성립해야 한다 — 기존 수단으로 재인증(1.8) → 연결 작업용 허가 발급(1.9) → 대상 제공자 OIDC 왕복. 표면은 둘이다: `GET /api/auth/link`가 연결된 **제공자 이름**만 돌려주고(미존재·삭제 계정은 빈 집합), `POST /api/auth/link/<제공자>`가 허가를 받아 세션에 일회성 흐름을 심는다(204). 재인증 시작과 같은 두 단계 배치이고 같은 CSRF·`Origin` 보호를 지난다. 다만 **표시된 세션의 예외 목록에는 들어가지 않는다** — 재인증 시작은 회복 경로라 면제되지만, 재인증을 요구받은 세션이 계정에 매핑을 더할 수 있으면 그 표시가 막으려던 것이 그대로 열린다.
- 연결 흐름은 재인증 무장에 섞지 않는다(`LinkFlow`는 별도 세션 속성이다). 재인증의 계약은 "계정을 만들지도 매핑을 만들지도 않는다"이고 연결은 정확히 매핑을 만드는 일이라, 한 record에 목적 필드를 더해 겸용하면 그 계약이 조건부가 되고 성공 핸들러의 분기점이 "무장이 있으면 재인증"에서 "무장이 있고 목적이 X면"으로 바뀐다. 복귀 목적지도 다르다 — 재인증은 세션이 가진 확정 목적지, 연결은 조작이 사는 화면 상수(`/settings`) 하나이며 어느 쪽도 클라이언트가 보내지 않는다.
- 연결의 최종 커밋은 **허가 소비와 `linkSocialIdentity`가 한 트랜잭션**이다. 매핑이 거부되면 `LinkService`가 사설 예외로 그 트랜잭션을 되돌리므로 "허가만 소비되고 매핑이 없는" 반쪽 상태가 남지 않는다. 거부는 하나의 값이다 — 이미 연결됨·다른 계정에 연결됨·허가 만료·세션 불일치·삭제 상태가 응답으로 구분되지 않고 모두 `/settings?auth=linkFailed` 하나로 수렴한다.
- 연결 소비만 **발급 시각의 세션 id를 제시한다**(`ConsumptionRequest.boundSessionId`). OIDC 콜백에서 세션 고정 방어가 성공 핸들러 직전에 ID를 반드시 회전시키므로 발급 시각의 id와 소비 시각의 live id는 **언제나** 다르고, live id로만 판정하면 왕복을 지나는 민감 작업은 구조적으로 성립할 수 없다. 이것이 귀속을 무르지 않는 이유는 그 값의 출처가 **세션 속성에 사는 흐름 기록** 하나이기 때문이다 — `changeSessionId`는 속성을 보존하므로 그 record를 읽을 수 있다는 사실 자체가 회전 전 세션의 연속을 증명하고, 다른 세션은 그 속성을 갖지 못한다. 주체 대조와 세션 판정은 그대로 지금 이 요청의 세션이 답한다. 요청 파라미터·헤더·본문에서 읽은 값을 이 인자로 넘기지 않는다.
- **루트 패키지가 처음으로 자기 테이블을 갖는다**(`action_grant`). 허가는 계정 소유권도 삭제 상태도 아니고 세션과 작업에 귀속하므로 `identity`에 둘 수 없다 — 그 모듈은 서블릿·HTTP 의존이 금지되어 세션 개념을 가질 수 없고 `AccessDecision`은 두 값뿐이라 표현할 자리도 없다. 그 대신 스키마에 루트 → `identity` 방향 외래키 하나가 생긴다(`identity_account(user_id)` `ON DELETE CASCADE`). 그 대가로 `erasePayload`가 이 테이블을 알 필요가 없다 — 계정 payload가 사라지면 그 계정의 허가도 함께 사라진다. 이 `CASCADE`는 `V1` 자식 테이블들의 `RESTRICT` 관용을 뒤집는 것이며 근거는 `V3__action_grant.sql`의 머리말이 소유한다. 판정과 발급 사이에 payload가 사라지는 경합은 외래키 위반이 알리고 포트가 같은 거부 하나로 접는다.
- 만료된 미소비 허가는 **발급 경로가 자기 사용자 범위에서** 기회적으로 쓸어 낸다. 스케줄러도 배치도 두지 않고, 그 정리가 실패해도 발급은 막히지 않는다. 범위 없는 전체 테이블 DELETE를 매 발급마다 돌리면 발급 하나의 비용이 테이블 크기에 비례한다. 소비된 행은 남긴다 — 그 행의 `operation_id`와 `result`가 멱등 재전송의 유일한 근거이고, 남는 행의 정리는 계정 삭제의 `ON DELETE CASCADE`가 맡는다.
- 인증 표면은 servlet 웹 애플리케이션에서만 배선한다(`@ConditionalOnWebApplication(type = SERVLET)`). 같은 이미지의 일회성 migration 역할은 웹 서버 없이 뜨므로 `HttpSecurity`가 없고, 조건이 없으면 마이그레이션이 컨텍스트 생성 실패로 끝난다. 허가 포트도 같은 조건이다 — 그 프로세스에는 세션 개념 자체가 없다.

### Bybit 호출 예산

Story 2.12가 ERD-1.0.0 §2.5의 호출 예산을 `source` 모듈의 `BybitCallBudget`에 두었다. 값은 `application.yml`의 `bokrilog.source.bybit.budget.*`이고 운영 기본값은 ERD 그대로다. 각 값은 환경 변수로 덮어쓸 수 있으며, 비었거나 읽을 수 없거나 범위 밖의 값은 기동을 죽이지 않고 기본값으로 접힌다(값은 예외·로그에 싣지 않는다). 백엔드 시험만 `BybitStubConfiguration`에서 값을 줄인다.

| 속성 | 환경 변수 | 기본값 | 뜻 |
|---|---|---|---|
| `auth-per-second` | `BOKRILOG_BYBIT_AUTH_PER_SECOND` | `5` | 서명 호출의 자격정보(UID)당 초당 요청 수. 0.1 미만은 기본값 |
| `public-per-second` | `BOKRILOG_BYBIT_PUBLIC_PER_SECOND` | `10` | 공개 Market 호출의 서비스 전체 초당 요청 수. 0.1 미만은 기본값 |
| `max-concurrent-collections` | `BOKRILOG_BYBIT_MAX_CONCURRENT_COLLECTIONS` | `2` | 동시에 도는 수집 수. 양의 정수만 받는다(소수는 기본값) |
| `retry-delays` | `BOKRILOG_BYBIT_RETRY_DELAYS` | `2s,10s,30s` | 수집 페이지(거래 기록·지갑)의 timeout·429·`10006`·5xx 재시도 간격. 최대 3개. 제공자 reset 시각에 따른 대기도 이 중 가장 긴 값을 넘지 않는다 |
| `retry-jitter` | `BOKRILOG_BYBIT_RETRY_JITTER` | `0.2` | 재시도 간격 위에 더하는 최대 비율(0~1) |
| `circuit-open` | `BOKRILOG_BYBIT_CIRCUIT_OPEN` | `10m` | HTTP 403(IP 차단) 뒤 모든 Bybit 호출을 요청 없이 막는 시간. 열릴 때 WARN 한 줄(시간만) |
| `low-remaining-ratio` | `BOKRILOG_BYBIT_LOW_REMAINING_RATIO` | `0.2` | `X-Bapi-Limit-Status`가 한도의 이 비율 이하면 다음 페이지를 reset 시각까지 늦춘다 |
| `low-remaining-wait-cap` | `BOKRILOG_BYBIT_LOW_REMAINING_WAIT_CAP` | `5s` | 그 대기의 상한 |

예산은 **프로세스 하나의 것**이다 — bucket·동시 수집 수·회로가 JVM 메모리에 살고 인스턴스끼리 나누지 않으므로, API 프로세스를 둘 이상 띄우면 제공자가 보는 호출 수는 그 배수가 된다(이 한계는 `deferred-work.md`가 소유한다). 수집의 대기(token·동시 수집·잔여 한도)와 페이지 재시도는 호출자의 lease가 살아 있는 동안만 이어지고, lease를 잃어 멈춘 수집은 제공자 오류 코드를 남기지 않는다. 연결 admission과 현재가치 조회·공개 시세 호출은 lease가 없어 token 대기만 하고 재시도하지 않으며, 대기가 끊긴 호출은 인증 거절이 아니라 `UNAVAILABLE`이다. 시간 값에는 상한이 있다 — 재시도 간격 10분, 회로 1일, 잔여 대기 상한 1분이며 넘는 값은 기본값으로 접힌다.

## 동일 출처 실행 (`deploy/`)

Nginx가 `frontend/dist/`를 origin 루트에서 제공하고 `/api`만 백엔드로 프록시한다. 브라우저는 하나의 origin에서 화면과 서버 응답을 함께 받는다. 이 구성이 개발·CI 공용이며 production 프로필이 아니다. 운영 구성(image digest 고정·자원 제한·blue/green 슬롯·역할 분리)은 아래 `## 운영 배포`가 소유하고, 로그 전송(Alloy)은 후속 작업이다. 이 스택의 백엔드는 역할 `all`(기본값) 하나이고, Nginx의 upstream 블록은 `deploy/nginx/upstream-backend.conf`(`backend:8080`)를 마운트한다.

### 준비

Docker와 Compose v2가 필요하다. compose는 어느 쪽도 빌드하지 않으므로 두 산출물을 먼저 만든다.

```bash
npm --prefix frontend run build
backend/gradlew --project-dir backend build
```

### 명령

| 명령 | 역할 |
|---|---|
| `deploy/compose/verify-same-origin.sh [--keep-stack]` | 마이그레이션 1회 → 기동 → I/O 표 전 행 확인 → identity 미노출 확인 → 인증 표면 확인 → 스텁 제공자 성공 왕복 제공자별 1회(왕복들이 서로 다른 계정·세션을 만드는지 확인하고 끝에서 만든 행을 지워 원래 상태로 되돌린다) → 재인증 왕복 → **연결 왕복**(연결 상태 조회 → 시작의 보호 다섯 → 무장 → 인가 → 콜백 → 매핑 2행·허가 1회 소비·세션 재회전 → 남의 식별자로 거부 탐침) → **운영자 표면 왕복**(미인증 401·비운영자 404·운영자 재인증 → 감사 내보내기 준비 → 확인 문구 → 확정·세션 회전·원장 요청/완료 2행, 끝에서 만든 행을 지운다) → 허용 목록 경로 확인 → 백엔드 정지 후 502/504 확인 → 로그 누출 검사(왕복의 `code`·`state`와 허가 참조 포함) → 정리. `--keep-stack`이면 성공 시 백엔드를 다시 띄워 건강한 스택을 남긴다 |
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

`PLAYWRIGHT_BASE_URL`이 있으면 `playwright.config.ts`가 자체 `preview` 서버를 띄우지 않는다. 이 분기가 없으면 통합 실행이 Nginx가 아니라 자기가 띄운 preview를 검증하면서도 green이 난다. 두 경우는 `frontend/playwright.config.test.ts`가 고정한다. 주입된 origin에서만 도는 시험이 둘 있다 — `same-origin-csp.spec.ts`(진입점 CSP)와 `auth-entry.spec.ts`의 `스텁 제공자 성공 왕복`(제공자별로 버튼 → 스텁 동의 → 온보딩 → 세션 쿠키)이다. preview에는 `/api`도 스텁도 없어 검증할 대상 자체가 없다. 그 뒤 스토리들이 같은 게이트로 더했고, Story 3.1의 `operator.spec.ts`는 운영자 왕복(로그인 → 재인증 → 감사 내보내기)과 비운영자의 404 화면을 그 origin에서만 본다 — 운영자 chunk가 초기 로드에 없다는 단정은 preview에서도 돈다. Story 3.2가 같은 파일에 전역 광고 제한(`restrict-advertising`) 왕복을 더했다 — 광고는 아직 소비자가 없어(Epic 4) 같은 스택의 다른 시험을 흔들지 않는 유일한 전역 제한이다. Story 3.4가 같은 시험의 끝에서 그 제한을 복구(`lift-restriction`)하므로 시험이 통과하면 스택에 광고 제한이 남지 않고, 같은 스택에 이 e2e를 다시 돌려도 된다. Story 3.5가 그 제한을 덮는 공지(`publish-notice`)를 발행해 같은 사용자의 대시보드에서 보고, 복구 뒤 복구 공지로 맺는다 — 복구 공지는 72시간 동안 남으므로 항목은 그 실행의 메시지로 가른다. 시험이 중간에 실패해 광고 제한이 남았으면 다음 실행의 준비가 `ALREADY_RESTRICTED`로 실패한다 — 그때는 운영자 화면에서 그 행을 복구하거나 `docker compose --file deploy/compose/compose.yaml down --remove-orphans --volumes` 뒤 새 스택에서 다시 돈다.

### 구조와 규칙

| 경로 | 역할 |
|---|---|
| `deploy/nginx/bokrilog.conf` | 루트 정적 제공, `/api` 프록시, 알려진 내부 경로 허용 목록, 공통 오류 JSON, 보안 응답 헤더, 쿼리 없는 access log. 개발·CI·운영이 같은 본문이다 |
| `deploy/nginx/upstream-backend.conf` | 본문이 include하는 `upstream bokrilog_backend` 블록 하나(개발·CI의 `backend:8080`). 운영은 배포 스크립트가 활성 슬롯으로 같은 자리에 쓴다 |
| `deploy/compose/compose.yaml` | 네 서비스. `postgres:18.6-alpine` → 백엔드 → `nginx:1.30.4-alpine`이 healthcheck로 이어지고, `migrate`는 `profiles`에 가려 명시 실행만 된다. 산출물과 설정을 읽기 전용 마운트 |
| `deploy/compose/verify-same-origin.sh` | 로컬·CI 공용 검증 명령 |

- 개발·CI compose에 비밀번호를 두지 않는다. `POSTGRES_HOST_AUTH_METHOD: trust`를 쓰고 포트를 게시하지 않는다. 가짜 비밀번호 리터럴을 커밋하면 gitleaks 규칙을 완화하고 싶어지고 그 완화가 남는다. production 자격정보는 Story 3.8이 소유한다.
- 제공자 자격정보도 두지 않는다. compose는 `BOKRILOG_GOOGLE_CLIENT_ID`·`BOKRILOG_GOOGLE_CLIENT_SECRET`·`BOKRILOG_NAVER_CLIENT_ID`·`BOKRILOG_NAVER_CLIENT_SECRET`·`BOKRILOG_KAKAO_CLIENT_ID`·`BOKRILOG_KAKAO_CLIENT_SECRET`를 참조로만 넘기고 값을 적지 않는다. 대신 백엔드가 `SPRING_PROFILES_ACTIVE: dev-oidc`로 떠서 `/api/dev-oidc/<제공자>/**`의 local OIDC 스텁을 제공자 자리에 세우고, `verify-same-origin.sh`가 인가 시작부터 세션 개설까지의 성공 왕복을 **제공자마다** 지난다. 스텁의 client id·secret은 기동 시점에 만들어지므로 설정 파일에도 compose에도 리터럴이 없다. 스텁과 실제 제공자가 함께 구성되면 백엔드가 기동을 거부하며, 자격정보가 없는 기본 프로필의 fail-closed는 `DevOidcStubProfileTest`가 지킨다.
- 스텁이 시험 소스가 아니라 `main`에 있는 이유는 브라우저다. Authorization Code 흐름의 인가 요청은 브라우저가 보내고 code 교환·JWKS 조회는 백엔드가 보내므로, 성공 경로를 브라우저 층에서 검증하려면 한 스텁이 양쪽에서 도달 가능해야 한다. `/api` 아래에 두면 기존 프록시 블록이 그대로 받아 새 서비스·새 게시 포트·CSP 확장이 하나도 필요 없다. 브라우저가 보는 주소(`BOKRILOG_PUBLIC_BASE_URL`)와 백엔드가 자기 스텁에 닿는 주소(`BOKRILOG_DEV_OIDC_INTERNAL_BASE_URL`)는 다른 값이다 — 공개 origin은 compose 네트워크 안에서 해석되지 않는다.
- 리다이렉트의 `Location`은 origin-상대 경로다(`server.tomcat.use-relative-redirects: true`). 기본값에서는 Tomcat이 요청의 `Host`로 절대 URI를 만들고, `proxy_set_header Host $host`가 포트를 잃은 값을 넘기므로 로그인 성공 뒤의 이동이 `http://127.0.0.1/onboarding`(80번 포트)으로 끊긴다. `$http_host`나 `X-Forwarded-*`로 바꾸는 길은 클라이언트가 제어하는 헤더를 목적지 계산에 넣는다.
- access log에 쿼리 문자열을 남기지 않는다. `log_format`이 `$request` 대신 `$request_method $request_path $server_protocol`을 쓰고 `map`이 `?` 앞부분만 잘라 낸다. `$request`를 그대로 쓰면 OIDC 콜백의 `?code=…&state=…`가 로그에 실린다. `$uri`로 대신하지 않는 이유는 그 변수가 내부 redirect 뒤의 값이라 `/onboarding` 요청이 `/index.html`로 기록되기 때문이다.
- 바깥에 열리는 포트는 Nginx 하나(`127.0.0.1:18080`)다. 백엔드와 데이터베이스 포트는 게시하지 않는다. 게시하는 순간 "동일 출처"는 구성이 아니라 관례가 된다. 호스트 포트는 `4183`으로 시작하지 않는 값을 쓴다. Playwright의 origin 비교가 포트 접두사에 취약했던 이력이 있고(`4183` 대 `41830`) preview 기본 포트와도 겹치지 않아야 한다.
- `/api`만 프록시한다. 나머지 경로는 정적 파일이거나 404이며 어떤 경로도 외부 주소로 프록시하지 않는다.
- 와일드카드 SPA 폴백(`try_files … /index.html`)을 두지 않는다. 폴백이 있으면 존재하지 않는 경로가 200 + 진입 화면으로 응답해 "없음"을 숨긴다. 대신 알려진 내부 경로만 여는 허용 목록 location(`^/(onboarding|dashboard|settings|plan-conditions|operator)$`)이 있고, 그 집합은 위 `## 프런트엔드`가 적은 다섯 자리 중 `App.tsx` 경로 맵과 같아야 한다. 라우트를 더할 때는 다섯 곳을 함께 고친다.
- 인증 엔드포인트는 `/api/auth/` 아래에 있어 기존 `/api` 프록시 블록이 그대로 받는다. redirect URI는 요청 헤더에서 유도하지 않고 `BOKRILOG_PUBLIC_BASE_URL`로 받는다. `proxy_set_header Host $host`가 포트를 잃기 때문이며, `$http_host`나 `X-Forwarded-*`로 바꾸는 길은 둘 다 클라이언트가 제어하는 헤더를 redirect URI 계산에 넣는다.
- CSP는 Nginx 응답 헤더가 유일한 정의다. `<meta>` CSP를 쓰지 않는다. `index.html`의 noscript 폴백이 인라인 `style` 속성을 쓰므로 `style-src-attr 'unsafe-inline'`을 뺄 수 없다. 빼면 JS를 끈 사용자의 고지 화면이 스타일 없이 나온다.
- `add_header`는 하위 블록에 하나라도 있으면 상위 블록의 것이 전부 사라진다. 그래서 보안 헤더는 server 블록 한 곳에만 두고 named location에는 두지 않는다. `always`가 붙어야 4xx·5xx 응답에도 실린다.
- `frontend/dist`와 Nginx 설정은 읽기 전용으로 마운트한다. 컨테이너가 저장소 파일을 고칠 경로를 만들지 않는다.
- 운영자 표면(Story 3.1)의 두 값도 환경이 준다. `BOKRILOG_OPERATORS`는 운영자 allowlist(`issuer|subject|permissions;…`, permissions는 `command`·`audit`)이고 **compose 기본값은 비어 있다**(운영자 없음 — dev-oidc 스텁은 subject를 고를 수 있으므로 기본값에 운영자를 두면 그 스택에 닿는 누구나 운영자가 된다). 비면 `/api/operator/**`가 전부 404이고, 형식이 틀리면 기동을 거부한다. `verify-same-origin.sh`가 Google 스텁 subject 둘(자기 왕복용과 Playwright `operator.spec.ts`의 `e2e-operator`)을 명시적으로 넣고, `--keep-stack`으로 남긴 스택에도 그 값이 남는다. `BOKRILOG_AUDIT_DIGEST_KEY`는 감사 행 수행자 digest(HMAC-SHA256)의 키(base64 32바이트 이상)이고 **비면 백엔드가 기동을 거부한다**(migrate 역할은 받지 않는다). 키는 커밋하지 않으므로 `verify-same-origin.sh`가 실행 시점에 만들고, 스택을 손으로 띄울 때는 `BOKRILOG_AUDIT_DIGEST_KEY="$(head -c 32 /dev/urandom | base64)" BOKRILOG_OPERATORS='http://127.0.0.1:18080/api/dev-oidc/google|e2e-operator|command,audit' docker compose --file deploy/compose/compose.yaml up --detach --build --wait --wait-timeout 180`처럼 같은 셸에서 넣는다(운영자가 필요 없으면 `BOKRILOG_OPERATORS`는 빼도 된다).
- 서비스 공지(Story 3.5)의 지원 연락처 `BOKRILOG_SUPPORT_CONTACT`(이메일 한 주소)도 환경이 준다. **compose 기본값은 비어 있고**, 비면 운영자의 공지 발행(`publish-notice`)이 422 `SUPPORT_CONTACT_UNCONFIGURED`이며 사용자 공지에 문의 링크가 없다(허위 지원 경로를 만들지 않는다). 이메일 모양이 아니면 기동을 거부한다. 발행한 공지는 확정 시점의 연락처를 그 버전에 고정하므로 값을 바꿔 재배포해도 이미 발행한 공지의 연락처는 바뀌지 않는다. `verify-same-origin.sh`가 개발용 주소 `support@bokrilog.test`(예약 최상위 도메인이라 전달되지 않는다)를 넣고, `--keep-stack`으로 남긴 스택의 Playwright 회귀가 그 값으로 공지를 발행한다. 손으로 띄울 때는 위 명령에 `BOKRILOG_SUPPORT_CONTACT=support@bokrilog.test`를 함께 넣는다.
- compose 로컬 오버라이드(`compose.override.yaml`)와 `.env`는 `.gitignore`가 제외한다. 저장소가 소유하는 구성은 `deploy/compose/compose.yaml` 하나뿐이다.

## 관측·경보 (Story 3.7)

운영 등급은 **`24×7 실행 목표, best-effort, SLA 없음`**이다. 계측은 운영 상태를 보이기 위한 것이고 목표 달성을 선언하지 않는다. 운영 서버에는 Grafana Alloy만 두고(Story 3.8) Prometheus·Grafana 자체 호스팅·promtool·k6·logcli 같은 개발용 관측 도구를 두지 않는다.

### 메트릭

Actuator는 **관리 포트에서 Prometheus 엔드포인트 하나만** 연다(`GET /actuator/prometheus`, health·info·목록 페이지도 닫혀 있다). 관리 포트는 기본으로 `127.0.0.1`에만 묶여 같은 네트워크 이름공간(같은 컨테이너·같은 호스트 프로세스)에서만 닿는다. compose는 그 포트를 게시하지도 `expose`하지도 않고 Nginx는 `/api`만 넘기므로 공개 origin의 `/actuator/**`는 404다. 외부 liveness는 공개 `GET /api/status`다(`{"status":"UP"}`만, 의존 상태 비노출). Actuator에 닿는 코드는 루트 `TelemetryConfig` 하나이고(`ModuleBoundaryTest`가 강제한다) 모듈은 Micrometer `MeterRegistry`만 쓴다.

| 환경 변수 | 기본값 | 뜻 |
|---|---|---|
| `BOKRILOG_MANAGEMENT_PORT` | `18081` | 관리 포트 |
| `BOKRILOG_MANAGEMENT_ADDRESS` | `127.0.0.1` | 관리 포트가 듣는 주소. 수집기가 다른 이름공간에 있으면 배포(Story 3.8)가 정한다 |
| `BOKRILOG_ENV` | `development` | 모든 메트릭의 공통 tag `env`. `development`·`ci`·`production` 밖이면 기동 거부. CI 스택은 `ci` |

등록할 수 있는 이름과 tag 값은 `TelemetryAllowlist`의 닫힌 표뿐이고 그 밖의 meter는 출력에 없다(기동 실패가 아니다). tag 값은 닫힌 enum 문자열뿐이다 — 사용자·계획·요청·계정·연결 ID, URL, 이메일, 금액, Bybit 원문은 tag에도 이름에도 없다. HTTP 서버 meter의 `uri`는 경로 템플릿이고 템플릿이 없으면 `UNKNOWN`이다(템플릿 집합은 웹 서버가 요청을 받기 전에 확정한다). 카운터의 기간은 프로세스 수명이라 재기동에 0으로 돌아간다 — 비율은 `increase`로 본다. 주요 종류·결말·사유 조합은 기동 때 0으로 미리 등록한다 — 처음 증가할 때 시계열이 생기면 `increase`가 그 증가를 놓친다.

| 메트릭 | tag | 분모·정의 | 기간 |
|---|---|---|---|
| `bokrilog_sync_attempts_total` | `kind`·`trigger` | 유효 시도: admission을 통과해 작업 행이 커밋된 수. 재전송·합류·쿨다운·주기 미도래는 시도가 아니다 | 프로세스 수명 |
| `bokrilog_sync_outcomes_total` | `kind`·`outcome`·`reason` | `success`·`failure`(실패·검토 필요, Bybit 오류 포함)·`excluded`. 제외는 두 가지다 — admission의 제한(`RESTRICTED`, 동기화·정밀 검증·완료 관측)·검증 보류(`REVIEW_HELD`·`LONG_GAP`)는 **요청 단위**(같은 요청의 재전송도 다시 거절되면 다시 센다)이고, 실행 중 제한·권한·계획 변경으로 막힌 작업(`BLOCKED`)과 연결 해제가 끝낸 작업(`NOT_PERMITTED`)은 작업 단위다. 실패율 = `failure / (success + failure)` | 프로세스 수명 |
| `bokrilog_sync_queue_age_seconds` | `kind` | 종류별 가장 오래 기다린 `QUEUED` 작업의 나이. DB 시각, 15초 주기 측정(가상 스레드, `statement_timeout` 2초, `V23` 부분 색인이 받친다). 대기 없음 0, 미측정 NaN | 측정 시점 |
| `bokrilog_support_first_answer_total` | `met` | 첫 답변 하나마다 1. `true`(목표 안)·`false`(목표 밖)·`pending`(목표 미정 — 답변 확정이 지금 달력으로 먼저 채워 보고도 정하지 못한 것). 달성률 = `true / (true + false)`, SLA 아님 | 프로세스 수명 |
| `bokrilog_support_pending_target` | — | 첫 답변을 기다리는 문의 수(접수·처리 중). 60초 주기 측정(가상 스레드, `statement_timeout` 2초) | 측정 시점 |
| `bokrilog_backup_last_success_timestamp_seconds` | — | 마지막 `SUCCEEDED` 백업 기록의 DB 시각(epoch 초, Story 3.9). 60초 주기 측정. **성공 기록이 없으면 등록하지 않는다**(NaN을 내지 않는다 — `absent()`와 나이 비교가 함께 침묵한다). 읽기가 실패하면 이미 낸 값을 유지한다 | 측정 시점 |
| `bokrilog_backup_store_usage_ratio` | `store` | 저장소(`oci`·`offsite`)별 최신 `MEASURED`의 bytes / quota. 측정 기록이 없으면 없다 | 측정 시점 |
| `bokrilog_backup_purge_overdue` | `store` | 그 최신 측정이 생성 후 30일을 넘긴 generation이 남았다고 적었으면 1, 아니면 0 | 측정 시점 |
| `bokrilog_backup_store_measured_timestamp_seconds` | `store` | 그 최신 측정 기록의 DB 시각(epoch 초). 저장소를 읽지 못해 측정이 멈추면 늙는다 — 위 두 값이 옛 값 그대로 남는 것을 알린다 | 측정 시점 |
| `hikaricp_connections*` | `pool=bokrilog` | DB 연결 풀(활성·유휴·대기·최대·최소·timeout) | 측정 시점 |
| `jvm_memory_{used,committed,max}_bytes` | `area`·`id` | JVM 메모리(풀 이름은 JVM이 정한다) | 측정 시점 |
| `http_server_requests_seconds` | `method`·`uri`·`status`·`outcome` | HTTP 요청 시간. `uri`는 경로 템플릿 또는 `UNKNOWN` | 프로세스 수명 |

`kind`는 `plan_sync`·`first_snapshot`·`initial_collection`·`classification`·`conversion`·`first_publication`이고, 시도·결말을 세는 것은 앞의 둘이다(나머지 넷은 대기 나이만). 모든 시계열에 `env`가 붙어 개발·CI 수치가 운영 실적과 섞이지 않는다.

### 로그

로그는 표준 출력의 **JSON 한 줄** 하나다(`logback-spring.xml` → `RedactingJsonEncoder`, 모든 환경 공통). 허용 키는 `ts·level·logger·event·code·outcome·reason·kind·correlationId·durationMs·count·exception`이고 `event`가 메시지 본문이다. 나머지 여덟은 SLF4J 구조 인자(`addKeyValue`)·MDC에서 같은 이름으로 오며, 그 밖의 MDC·구조 인자와 예외의 메시지·스택은 버린다.

- **진단.** 예외는 클래스의 단순 이름 하나(`exception`, `[A-Za-z0-9_$]{1,80}`)만 남는다 — 예외를 함께 넘긴 줄은 encoder가 자동으로 채우고, 작업자 실패 줄·`ApiErrorHandler`의 500 줄은 직접 싣는다. 처리하지 못한 스레드 예외(작업자 가상 스레드 포함)는 JVM 기본 `UncaughtExceptionHandler`가 같은 모양의 한 줄로 바꾼다(stderr 스택 없음). 스택은 어느 로그 수준에서도 나오지 않는다 — 조사는 같은 입력을 재현해서 한다.
- **`correlationId`.** 응답 오류 본문의 값과 같고 문자 집합은 하나(`ApiErrorHandler.CORRELATION_ID`, `[A-Za-z0-9._-]{1,64}`)다. 진입점(Nginx)의 `$request_id`(소문자 hex 32자 — Nginx가 클라이언트 헤더를 덮어쓰는 서버 값)는 그대로 쓰고, 그 밖의 값에 숫자 6자리 이상 연속이 있으면 서버가 만든 값으로 바꾼다. 사용자가 신고한 ID로 `event="처리하지 못한 요청"` 줄을 찾는다.
- **가림.** 본문은 문의 민감값 규칙(Story 3.6)의 SECRET·EMAIL·WALLET 모양을 `[REDACTED:<규칙>]`로, UUID를 `[REDACTED:ID]`로, 숫자 6자리 이상을 `[REDACTED:DIGITS]`로 가린다. 작업·사용자 ID를 로그에 싣지 않는다 — 계정 삭제 뒤 원장과 잇는 근거가 된다. `code·outcome·reason·kind`에 UUID·숫자 6자리 이상 모양이 오면 그 키를 통째로 버린다.
- **AC-6 스캔의 예외는 계약으로 둘이다.** `correlationId`(요청마다 새로 만드는 값, 사용자 귀속이 아니다)와 구조 수치 `durationMs`·`count`(정수 그대로 둔다). 시험(`SyncMetricsTest`)은 이 셋을 빼고 모든 필드에 UUID·이메일·숫자 6자리 이상이 0건임을 단정한다.
- **구성 비밀값.** 프로세스가 실제로 가진 비밀값(OIDC client secret, 감사 digest 키, 자격정보 암호화 key ring의 키 부분, DB 비밀번호 — 8자 이상)이 메시지·인자·구조 인자·MDC·예외 문자열에 나타나면 그 줄의 본문 전체가 `[REDACTED:CONFIGURED_SECRET]`이 되고, 강한 신호 3번 `SECRET_EXPOSED`(`ruleId=CONFIGURED_SECRET_IN_LOG`)가 비동기로 한 번 올라간다(같은 규칙은 프로세스당 60초에 1번, 신호 기록 스레드의 로그는 다시 올리지 않는다, 기록이 실패하면 억제를 푼다). 판정·scope는 판정표 `OPS-SIGNAL-1.0`이 정한다. 8자보다 짧은 값은 감지에서 빠지고 기동 때 설정 이름만 WARN으로 남는다. 패턴 탐지는 가리기만 하고 신호로 올리지 않는다.
- **한계.** 사용자마다 다른 Bybit API Secret·Key는 감지 집합에 없다 — 프로세스가 복호화 전에는 갖고 있지 않은 값이다. 그 값은 패턴 가림(토큰 모양)만 받고, 로그에 싣지 않는 것은 코드 규칙(자격정보를 로그 인자로 넘기지 않는다)과 `verify-same-origin.sh`의 실제 값 누출 검사가 지킨다. migrate 역할과 컨텍스트가 뜨기 전 기동 초기 줄도 패턴 가림만 받는다.
- 시험은 로그 본문을 `LogLines`로 꺼내 단정한다(줄 전체가 아니라 `event`). 계획 동기화 시험은 시작 표지 뒤의 줄만 본다(`PlanSyncTestBase.TEST_START`).

### 경보

규칙은 `deploy/observability/alerts.yml`(Prometheus 규칙), 단위시험은 같은 디렉터리의 `alerts.test.yml`(promtool)이다. 규칙마다 `for:`·`severity`·`group`(중복 억제 단위)을 갖고, 강한 신호 후보만 `strong_signal="true"`를 갖는다 — 승인 목록(`check-alert-rules.sh`)은 지금 비어 있고 아래는 모두 일반 경보다(자동 정지로 잇지 않는다). **모든 selector가 `env="production"`이다** — 애플리케이션 메트릭은 공통 tag로, 수집기 계열(probe·node·alloy)은 운영 Alloy·synthetic이 붙이는 같은 이름의 외부 label로(Story 3.8).

**미측정은 `severity: info`·`group: unmeasured`다.** synthetic·backup·비용 장부·Grafana 사용량이 연결되기 전(Story 3.8·3.9·분리된 후속)에는 `...Unmeasured`가 계속 울린다. 연결 전 침묵 정책: 알림 경로는 `group="unmeasured"`를 사람을 깨우는 채널이 아니라 일일 요약으로만 보낸다 — 조용히 통과하지는 않되 밤에 깨우지 않는다.

| 경보 | 조건 | for | severity·group |
|---|---|---|---|
| `LivenessProbeFailed` | `probe_success{job="bokrilog-liveness"} == 0` | 5m | critical·availability |
| `LivenessProbeUnmeasured` | synthetic 결과 없음(3.8 전) | 10m | info·unmeasured |
| `AlloyHeartbeatMissing` | `up{job="alloy"} == 1`이 없음 | 10m | critical·observability |
| `BackupAgeExceeded` | 마지막 성공 백업이 26시간 이상 | 0m | critical·backup |
| `BackupAgeUnmeasured` | `bokrilog_backup_last_success_timestamp_seconds` 없음(성공 기록 전 — 운영 첫 백업 전) | 1h | info·unmeasured |
| `BackupStoreUsageWarning`·`Critical` | 백업 저장소 사용량이 quota의 70%·85% 이상(저장소별). warning은 위쪽 경계가 없다 — 85% 위에서는 둘 다 울려 warning이 풀리고 critical이 아직 울리지 않는 침묵 구간이 없다 | 5m | warning·critical·backup |
| `BackupStoreMeasurementStale` | 저장소별 최신 측정이 26시간 넘게 없다(저장소를 읽지 못한다) | 0m | warning·backup |
| `BackupPurgeOverdue` | 생성 후 30일을 넘긴 generation이 파기 뒤에도 남았다(저장소별) | 0m | critical·backup |
| `SyncFailureRateHigh` | 15분 창 실패율 20% 이상, 분모 10 이상 | 0m | warning·sync |
| `SyncQueueAgeHigh` | 대기 나이 15분 이상 | 0m | warning·sync |
| `SyncQueueAgeUnmeasured` | 대기 나이가 NaN(측정 실패·timeout) | 15m | info·unmeasured |
| `DatabaseConnectionsSaturated` | Hikari 활성/최대 90% 이상 | 10m | warning·capacity |
| `JvmHeapPressure` | heap 사용/최대 90% 이상 | 10m | warning·capacity |
| `DiskUsageWarning`·`Critical` | 디스크 사용 70%·85% 이상(구간을 나눠 하나만) | 5m | warning·critical·capacity |
| `GrafanaUsageWarning`·`Critical` | Grafana Cloud 사용량 70%·85% 이상 | 5m | warning·critical·capacity |
| `GrafanaUsageUnmeasured` | Grafana Cloud 사용량 없음(3.8 전) | 1h | info·unmeasured |
| `MonthlyCostExceeded` | `bokrilog_monthly_cost_krw` 30,000원 이상 | 0m | warning·cost |
| `MonthlyCostUnmeasured` | 월 비용 없음(운영 장부 전) | 1h | info·unmeasured |

값 자체가 나이·누적인 규칙(backup·queue age·비용)과 창을 품은 비율(실패율)은 `for: 0m`이다 — 기준값이 곧 경보 시점이다. 단위시험은 값 경계(직전 미발화·직후 발화 — 실패율 19.9%/20%·분모 9/10, 연결·heap 89.x%/90%, queue age 14/15분, backup 25h59m/26h, 비용 29,999/30,000)와 지속시간 경계를 따로 고정하고, 복구·미측정과 개발 시계열 무시도 본다.

| 명령 | 역할 |
|---|---|
| `bash .github/scripts/check-alert-rules.sh` | 형식 계약(규칙 머리 들여쓰기, labels 구간의 `severity`·`group`, 블록 표기, 승인된 `strong_signal`) → `promtool check rules` → `promtool test rules`. promtool은 Prometheus 3.5.0 배포본을 받아(curl 60초·재시도 2회) SHA-256을 대조해 캐시(`~/.cache/bokrilog/promtool-3.5.0`)에 원자 저장하고, 쓸 때마다 다시 대조한 뒤 임시 디렉터리로 꺼낸다. `PROMTOOL`로 경로를 줄 수 있다. 종료 코드 0(통과)·1(판정 실패)·2(준비 실패 — 내려받기·checksum·미지원 플랫폼·promtool 실행 불가 126/127) |

## 운영 배포 (`deploy/production/`, Story 3.8)

**상태: 운영 배포 미검증.** 이 절차는 CI 리허설(`deploy-rehearsal` job)만 통과했다. 실제 운영 대상(OCI A1 호스트·레지스트리 digest·서명)에서 smoke를 돌리기 전까지 운영 배포는 검증되지 않은 것으로 다룬다. 리허설의 green을 운영 배포의 증거로 쓰지 않는다.

**이중화 한계.** blue/green은 **배포 실패만** 완화한다. 같은 VM 하나·같은 volume 하나·같은 PostgreSQL 하나·같은 Nginx 하나 위에서 슬롯 둘을 바꿔 끼울 뿐이라 VM·volume·DB·Nginx·리전 장애를 이중화하지 않는다. 무중단·고가용성·SLA를 약속하지 않는다 — 운영 등급은 위 `## 관측·경보`의 `24×7 실행 목표, best-effort, SLA 없음` 그대로다. 전환 순간의 진행 중 요청, Worker drain 동안의 작업 지연(최대 drain-timeout 120초), migration 동안의 동기화 정지는 정상 동작이다.

**release 밖의 것.** Nginx가 내보내는 `frontend/dist`와 Nginx 설정(`deploy/nginx/bokrilog.conf`)은 운영 호스트의 checkout에서 마운트한다. 이 둘은 release manifest·digest 고정·rollback의 대상이 아니다 — 앱 이미지를 이전 digest로 되돌려도 화면 산출물과 Nginx 설정은 그 checkout의 것 그대로다. 정적 산출물을 release에 묶는 일은 후속 작업이다(`deferred-work.md`).

### 역할

같은 jar·이미지를 `BOKRILOG_ROLE`로 나눠 실행한다(`BokrilogRole`).

| 역할 | 하는 일 | 하지 않는 일 |
|---|---|---|
| `api` | 공개 HTTP 표면 | 작업 dispatch·heartbeat·주기 측정(스케줄링 자체가 없다 — 대기 나이·대기 문의 gauge도 없다) |
| `worker` | 작업 dispatch, 주기 측정, 종료 신호에 drain, PostgreSQL session advisory lock으로 단일 실행. 같은 servlet 앱이지만 공개 포트를 컨테이너 루프백(`127.0.0.1`)에만 묶는다 | Nginx upstream·포트 게시 대상이 아니다 |
| `all` | 둘 다(지금까지의 동작). 개발·CI·시험의 기본값 | drain·단일 잠금이 없다. **`BOKRILOG_ENV=production`이면 기동 거부** |

일회성 역할은 웹 서버 없이 뜨고 역할 값을 보지 않는다 — `migrate`(schema를 바꾸는 유일한 프로세스, AD-12)와 `deploy-audit`(감사 원장에 배포 단계 SYSTEM 행 하나를 더하고 끝난다, Flyway 검사 없음 — 미적용 migration이 있는 DB에도 쓴다), `backup-record`(백업 실행 기록을 `operations_backup_event`에 더하고 끝난다, Flyway 검사 없음 — 아래 `## 백업`), 그리고 Story 3.10의 셋 — `deletion-sample`(검증용 표본 계정 삭제, 운영 환경 거부)·`tombstone-export`(tombstone outbox 내보내기)·`restore-apply`(격리 복원의 tombstone 적용·검증)이고 셋 다 Flyway를 검증한다(아래 `## 복원`). 모든 메트릭에 공통 tag `role`이 붙는다(`TelemetryAllowlist`). 대기 나이·대기 문의 수 같은 주기 측정 gauge는 측정하는 역할(`worker`·`all`)에만 있다 — API 슬롯이 NaN을 영원히 내 미측정 경보를 울리지 않게.

**Worker drain.** `docker compose stop`의 SIGTERM이 곧 drain 요청이다(별도 HTTP 경로를 두지 않는다). Worker는 새 lease claim을 멈추고, heartbeat를 계속 밀며, 진행 중 작업(여섯 종류 모두)이 비거나 `bokrilog.sync.worker.drain-timeout`(기본 120초)이 지날 때까지 기다린다(`WorkerDrain` — 컨텍스트 닫기의 첫 사건에서 돌아 shutdown phase 5초가 자르지 않는다). 종료 코드는 0(비었다)·75(`worker.drain.incomplete`, 남은 작업은 lease 만료 뒤 다음 Worker가 잡는다)·76(`worker.lock.lost`, 잠금 세션을 잃어 스스로 끝냈다)이다(`WorkerExit`). 시험이 고정하는 것은 빈의 `exitCode()`까지다 — 실제 JVM이 신호를 받아 컨텍스트를 닫은 뒤 그 코드로 끝나는 경로(`BokriLogApplication`의 halt)는 시험·리허설이 고정하지 않는다(리허설의 drain 미완료는 결함 주입으로 75를 넣어 절차의 반응만 본다). 운영 compose의 Worker `stop_grace_period`는 150초다.

**단일 Worker.** 잠금은 연결 풀 밖의 전용 JDBC 연결이 쥔다(풀의 연결이면 풀 한 칸을 영원히 차지하고, 닫을 때 잠금을 쥔 세션이 풀로 돌아간다). 5초마다 그 세션이 `pg_locks`에서 실제로 잠금을 쥐고 있는지 보고, 아니면(연결 끊김·세션 종료) 새 claim을 멈추고 76으로 끝낸다. 컨텍스트가 닫히면 `pg_advisory_unlock` 뒤 연결을 닫는다. 두 번째 Worker는 잠금을 잡지 못해 기동 실패로 끝난다(`worker.lock.unavailable`).

### 입력

| 파일 | 위치 | 내용 |
|---|---|---|
| release manifest | 배포마다 하나(`--release`) | `image`(`<repo>@sha256:<64hex>`, 저장소 180자까지, tag·`latest` 금지)·`platform=linux/arm64`·`migration_version`·`migration_kind=expand\|contract`·SBOM·서명 파일과 각 SHA-256. 서명 신원은 **여기 없다**(있으면 거부). 견본 `release.env.example` |
| host 사실 | `/etc/bokrilog/host-facts.env`(`BOKRILOG_HOST_FACTS`) | home region = region, `VM.Standard.A1.Flex` 2 OCPU/12GB, reserved IPv4, Always Free, 예상 월 비용 0, OS 예약 MiB, 서명 신원(`signer_identity`·`signer_issuer` — manifest를 고친 사람이 신원을 고르지 못하게 운영자가 소유한다). 운영자가 콘솔에서 확인해 적는다. 견본 `host-facts.env.example` |
| 운영 비밀 | `/etc/bokrilog/production.env`(`BOKRILOG_SECRETS_FILE`, 권한 600) | DB URL·사용자·비밀번호(`BOKRILOG_DB_PASSWORD`, PostgreSQL 컨테이너의 `POSTGRES_PASSWORD`), 공개 base URL, 감사 digest 키, 자격정보 key ring, Bybit 주소·reserved IP, 지원 연락처, 로그인 제공자 하나 이상. 프로필·역할·환경·Worker 설정·배포 수행자를 덮는 이름(`SPRING_PROFILES_*`·`SPRING_APPLICATION_JSON`·`BOKRILOG_ROLE`·`BOKRILOG_ENV`·`BOKRILOG_SYNC_WORKER_*`·`BOKRILOG_DEPLOY_ACTOR`)은 담을 수 없다. compose의 `--env-file`로만 쓰이고 서비스는 이름으로 고른 값만 받는다(PostgreSQL은 자기 비밀번호, migrate는 DB 접속, 감사 역할은 DB 접속과 digest 키). **저장소 밖에만 있다.** 비밀 공급(누가·어떻게 이 파일을 만들고 돌리는가)은 후속 작업이다 |
| 배포 수행자 | 배포를 돌리는 셸(`--actor` 또는 `BOKRILOG_DEPLOY_ACTOR`) | `[A-Za-z0-9._@-]{1,128}`. 원장에는 `deployer:` keyed digest로만 남는다. 비밀 파일에 두면 그 호스트의 모든 배포가 같은 수행자가 되므로 거기서는 받지 않는다 |
| 상태 | `/var/lib/bokrilog/deploy`(`BOKRILOG_STATE_DIR`) | `deploy.state` 하나가 활성 슬롯·활성 이미지·Worker 이미지·이전 이미지·schema 버전·rollback 창을 기록한다. `nginx/upstream-backend.conf`는 Nginx가 읽기 전용으로 마운트하는 upstream include다(`zone`·Docker DNS `resolver 127.0.0.11 valid=10s`·`server api-<slot>:8080 resolve` — 슬롯이 재시작하거나 아직 없어도 nginx가 뜨고 주소를 다시 해석한다). `logs/`에 배포마다 기록 하나 |

운영 compose는 망을 둘로 나눈다(`edge`: Nginx ↔ API 슬롯, `data`: API 슬롯·Worker·migrate·감사 역할 ↔ PostgreSQL — Nginx는 DB에 닿지 못한다). 모든 서비스가 json-file 로그 회전(10MB × 5)을 쓴다.

### 명령

| 명령 | 역할 |
|---|---|
| `deploy/production/deploy.sh deploy --release <release.env> --actor <이름>` | 아래 순서 전체. 종료 0(배포 완료)·1(gate 거부 또는 실패 — 정리·rollback을 마쳤다)·2(준비 실패) |
| `deploy/production/deploy.sh close-window --actor <이름>` | rollback 창을 닫는다(유일한 닫는 경로). contract 배포 전에 한 번. 같은 `.lock`을 잡고, 감사 행 `WINDOW_CLOSED COMPLETED`(target=활성 이미지)를 먼저 쓴 뒤에만 창을 닫는다 |
| `deploy/production/deploy.sh status` | 상태 파일을 보인다 |
| `bash deploy/production/preflight.sh --release … --host-facts … --secrets … --compose … [--state …] [--actor …] [--project …] [--override …] [--rehearsal]` | gate 단독 실행(배포가 먼저 부른다). 종료 0·1(거부, 줄마다 `거부  <CODE>`)·2(값 없는 인자 포함 준비 실패) |
| `bash deploy/production/test-preflight.sh` | gate 고정 입력 시험(가짜 docker·cosign, Docker 불필요). Windows의 Git Bash는 POSIX 권한을 표현하지 못해 권한 경로만 건너뛴다 |
| `deploy/production/rehearse-deploy.sh` | 운영 compose + `compose.rehearsal.yaml`(trust DB, `BOKRILOG_ENV=ci`)로 실제 절차를 돈다: 1 정상(첫 배포), 2 정상(GLOBAL SYNC 제한 중), 3 두 번째 Worker(띄운 뒤 120초 상한), 3b drain 미완료(결함 주입 75), 4 전환 전 실패, 5 전환 뒤 실패 → rollback, 6 rollback 창이 열린 채 contract 거부, 7 창 닫기. 같은 jar로 만든 이미지 둘(로컬 image ID)을 쓰고 아키텍처·서명·SBOM attestation 검사만 건너뛴다(감사 행 `rehearsal:true`). 공개 포트 `127.0.0.1:18090`, 프로젝트 `bokrilog-rehearsal`. `BOKRILOG_REHEARSAL_DIAGNOSTICS`를 주면 실패 때 기록을 그곳에 옮긴다(CI가 진단 artifact로 올린다). 종료 0·1·2 |

### 순서

단계마다 일회성 `deploy-audit` 역할이 감사 원장 `operations_audit_event`에 SYSTEM 행 하나를 더한다 — `command=deploy`, `scope=DEPLOY`, target=image digest(형식 거부의 `GATE FAILED`만 `INVALID_REFERENCE`, 그 밖에 빈 target은 거절), reason=단계명, outcome은 기존 enum(`REQUESTED`·`COMPLETED`·`FAILED`), 수행자는 배포 수행자 이름의 `deployer:` keyed digest, 한 배포의 행은 같은 operation ID로 묶인다. details는 닫힌 키(실패 code·슬롯·이전 digest·리허설 표지·그 시점의 활성 제한 수)뿐이다.

0. **상태 대조** — 상태 파일의 활성 슬롯과 실제로 도는 API 슬롯이 같아야 한다. 상태 파일이 없는데 슬롯이 돌거나(`STATE_MISSING`) 둘이 다르면(`STATE_MISMATCH`) 아무것도 바꾸지 않고 거부한다 — 도는 활성 슬롯을 대기 슬롯으로 착각해 다시 만들지 않게.
1. **gate**(`preflight.sh`) — 모드(리허설은 `BOKRILOG_ENV` ∈ {ci, development} + override, 운영은 비었거나 `production` + override 없음)·프로젝트 이름·배포 수행자·형식·실제 이미지 아키텍처·SBOM·서명 SHA-256·`cosign verify`·SBOM attestation(`cosign verify-attestation --type spdxjson`, host 사실의 같은 신원, cosign이 없으면 준비 실패)·host 사실·`mem_limit`(API 768m·Worker 512m·PostgreSQL 3g·Nginx 128m, 일회성 ≤ 512m, Alloy 256MiB 예약)·blue/green peak(API×2 + 나머지 = 5,504MiB) + OS 예약 ≤ 12GB·compose 계약(인프라 이미지 digest 고정·`build`·`env_file` 없음·API·Worker에 migrate 프로필 없음)·비밀(권한 600·필수 이름·덮는 이름 없음)·migration 종류. 거부면 **아무 컨테이너도 바꾸지 않고** 지금 활성 이미지의 감사 역할로 `GATE FAILED <code>`를 남긴다(첫 배포면 기록할 곳이 없다).
2. `GATE REQUESTED`(PostgreSQL은 이미 돌면 다시 만들지 않는다 — `up --no-recreate`).
3. **Worker drain** — 종료 코드 0이면 `DRAINED`. 75면 `DRAINED FAILED DRAIN_INCOMPLETE`, 76이면 `LOCK_LOST`, 그 밖은 `WORKER_EXIT_UNEXPECTED`이고 이전 Worker를 재개한 뒤 중단한다.
4. **단일 migrate 역할** — 읽은 schema 최대 버전을 그 자리에서 상태 파일에 남긴다(배포가 뒤에서 실패해도 다음 gate가 옛 값으로 판정하지 않게). 그 값이 `migration_version`(rollback 창 안의 이전 이미지면 지금 schema 그대로)이어야 `MIGRATED`.
5. **기존 슬롯 smoke**(old API + new schema).
6. **대기 슬롯 기동**(`api-blue`·`api-green` 중 쉬는 쪽, 새 digest).
7. **readiness·smoke·telemetry** — healthy, 미인증 smoke 셋(`/api/status` 200 `{"status":"UP"}`, `/api/auth/session` 200 미인증 본문, `/api/connections/bybit` 401 공통 오류 `UNAUTHENTICATED`), 관리 포트 출력의 모든 시계열에 `env`·`role="api"`. `INACTIVE_VERIFIED`.
8. **전환** — upstream include를 대기 슬롯으로 바꾸고(쓰지 못하면 `CUTOVER FAILED UPSTREAM_WRITE_FAILED`) `nginx -t` 뒤 reload.
9. **전환 뒤 smoke**(Nginx 경유). `CUTOVER`.
10. **새 Worker 하나**(새 digest) — healthy와 관리 포트의 `env`·`role="worker"`.
11. **이전 슬롯 정지**, 상태 파일 갱신. `VERIFIED`.

빈 데이터베이스에 대한 첫 배포에는 원장 표가 아직 없다. 그때는 2·3단계의 행을 줄 세워 두었다가 4단계의 migration이 표를 만든 직후(`MIGRATED`보다 앞) 같은 순서로 쓴다. 첫 배포에는 drain할 Worker도 기존 슬롯도 없어 5단계를 건너뛰고, 대기 슬롯이 healthy가 되면 upstream을 그 슬롯으로 두고 Nginx를 처음 띄운다. 첫 배포가 전환 뒤에 실패하면 되돌릴 슬롯이 없다.

smoke 경로는 운영 제한이 막는 기능이 아니다 — 제한 중 배포도 smoke를 통과하고, 배포는 제한 행을 쓰거나 닫지 않는다. 시작(`GATE`)·끝(`VERIFIED`) 행의 활성 제한 수가 다르면 배포가 경고한다(운영자가 그 사이 제한을 바꾼 것이다).

### 실패와 rollback

- **전환 전 실패**(3~8단계): 그 단계 `FAILED <code>`, 대기 슬롯을 지우고 upstream은 그대로이며 이전 digest의 Worker를 재개한다.
- **전환 뒤 실패**(9~10단계): `FAILED <code>` 뒤 upstream을 이전 슬롯으로 되돌리고 reload, 새 Worker를 멈추고 이전 digest의 Worker를 재개, 새 슬롯을 지운 뒤 smoke가 통과하면 `ROLLED_BACK COMPLETED`. upstream을 되돌려 쓰지 못하면 `ROLLED_BACK FAILED UPSTREAM_WRITE_FAILED`로 남기고 수동 확인을 요구한다.
- **신호**(INT·HUP·TERM): 아직 아무것도 바꾸지 않았으면 그냥 끝나고, 전환 전이면 `INTERRUPTED SIGNAL`로 위 전환 전 정리, 전환 뒤면 위 되돌림을 돈다.
- **이전 슬롯 정지 뒤의 되돌리기**는 이전 release manifest로 같은 절차를 다시 도는 것이다. rollback 창이 열린 동안에는 expand만 받으므로 이전 digest가 새 schema 위에서 뜬다(Flyway 검증은 미래 migration을 무시한다). gate는 창 안의 이전 이미지만 schema 역행을 받는다.
- **contract**는 rollback 창이 열려 있으면 거부하고(`CONTRACT_WINDOW_OPEN`), 창이 닫혀 있어도 migration을 정확히 하나만 더해야 한다(`CONTRACT_NOT_ISOLATED` — contract와 expand를 한 배포에 섞지 않는다). 창은 `deploy.sh close-window`로만 닫힌다.

### 규칙

- 운영 서버에서 이미지를 빌드하지 않는다(운영 compose에 `build`가 없고 gate가 본다). 앱 이미지는 digest로 받기만 한다. 이미지 빌드·SBOM 생성·서명·SBOM attestation·레지스트리 게시는 아직 이 저장소에 없다(`deferred-work.md`, 공급망 파이프라인) — 그 파이프라인이 release manifest를 만들 때까지 운영 배포는 미검증이다.
- startup migration을 하지 않는다. API·Worker 기동은 schema를 검증만 하고 `migrate` 역할만 바꾼다.
- 결함 주입 변수 `BOKRILOG_DEPLOY_FAULT`(`drain-incomplete`·`inactive-readiness`·`post-cutover-smoke`)는 리허설 전용이다. 리허설 모드 자체가 `BOKRILOG_ENV` ∈ {ci, development}와 리허설 override를 함께 요구하므로, 운영 셸(대개 `BOKRILOG_ENV`가 빈 값)에서 `--rehearsal` 하나로 검사를 건너뛸 수 없다. compose 프로젝트 이름은 `bokrilog-<소문자>` 모양만 받는다.
- 배포와 창 닫기는 한 번에 하나다(상태 디렉터리의 `.lock`, 일일 백업도 같은 잠금 — 안의 `owner`가 쥔 쪽·pid·operation·시작 시각을 말한다). 다른 배포·백업이 쥐고 있으면 준비 실패다. 소유자가 죽었으면 `LOCK_STALE`로 거부하고 스스로 지우지 않는다 — 복구는 아래 `## 백업`의 "잠금이 남았을 때".
- 값을 받는 인자(`--release`·`--actor` 등)를 값 없이 마지막에 두면 준비 실패(2)다.
- TLS 종단(443·인증서), Grafana Alloy 수집·Grafana Cloud·외부 synthetic 연결, nginx 컨테이너 권한 강화, 감사 digest 키 회전, 3.4 훈련 증거 기록, 정적 산출물·Nginx 설정의 release 묶기, Worker 종료 코드의 실제 JVM 경로 시험은 후속 작업이다(`deferred-work.md`).

## 백업 (`deploy/production/backup.sh`, Story 3.9)

**상태: 실저장소 미검증.** 절차는 CI 리허설(`deploy-rehearsal` job의 `백업 리허설` 단계 — MinIO 두 개, 가상 시계)과 고정 입력 시험(`백업 스크립트 시험`)만 통과했다. 실제 OCI Object Storage와 OCI 밖 저장소에 올리고 30일 파기를 그 저장소에서 확인하기 전까지 백업은 검증되지 않은 것으로 다룬다. 가상 시계의 파기 결과를 실제 파기 완료로 선언하지 않는다. OCI 밖 저장소의 업체는 아직 정하지 않았다(정하는 것은 Ask First). 복원은 Story 3.10이 소유한다 — 이 절차에는 복원 실행이 없고, 복원은 아래 `## 복원`이다. 배포·백업·복원·tombstone 내보내기는 같은 `.lock`을 쓴다(kind `deploy|backup|restore|tombstone`).

### 순서

운영 서버의 systemd timer(`bokrilog-backup.timer`, 매일 18:30 UTC = 03:30 KST, `Persistent=true`)가 `backup.sh run`을 돈다.

0. **같은 날 재실행** — generation ID는 `gen_<UTC YYYYMMDD>`다. 표에 그 generation의 `SUCCEEDED`가 있으면 아무것도 하지 않는다(행·object 불변, 종료 0).
1. **잠금** — 배포와 같은 `$BOKRILOG_STATE_DIR/.lock`을 쥔다. tombstone 내보내기(Story 3.10, 길어야 1분)가 쥐었으면 5분까지 기다린다(리허설만 `BOKRILOG_BACKUP_LOCK_WAIT`로 줄인다). 잠금 안의 `owner` 파일이 쥔 쪽(`kind=deploy|backup`·`pid`·`operation`·`started`)을 말한다. 배포 중이면 `FAILED DEPLOY_IN_PROGRESS`, 다른 백업 중이면 `FAILED BACKUP_IN_PROGRESS`, 소유자 pid가 죽었거나 `owner`가 없으면 `FAILED LOCK_STALE`이고 셋 다 종료 1이다. **이 실패 행은 기록 역할 JVM을 띄우지 않고 대기열에만 넣는다** — 배포가 잠금을 쥔 동안(blue/green peak) 512m를 더 띄우지 않는다.
2. **대기열** — 잠금을 쥔 실행은 `$BOKRILOG_BACKUP_DIR/pending/`의 행을 먼저 기록하고, 0을 다시 본다(그사이 손으로 돌린 실행이 성공했을 수 있다).
3. **로컬 임시본·시작** — 앞선 실행이 남긴 `tmp/` 아래 것을 지우고(`PURGED LOCAL_TEMP`) `STARTED`를 기록한다.
4. **incoming 정리** — 두 저장소의 `incoming/`(앞선 실행의 부분 object)을 비운다(`PURGED INCOMING_CLEARED`).
5. **dump** — 운영 postgres 컨테이너의 `pg_dump --format=custom`(로컬 소켓, `compose exec -T`)을 권한 700 임시 디렉터리에 쓴다. 종료 코드 0과 비어 있지 않음을 확인한 뒤에만
6. **암호화** — `age -R active.pub`. 평문은 암호화 직후, 그리고 어느 실패·신호에서든 지운다(trap).
7. **암호문 SHA-256.** checksum 파일은 `sha256sum` 모양의 첫 줄과 dump 시작 시각(`dump_started_at=<epoch>`, 실제 시계 — 복원의 RPO 기준) 둘째 줄이다.
8. **catalog** — `age -d -i identities/<key_id>.key | pg_restore --list`. pg_restore는 TOC만 읽고 끝나므로 남은 stream을 끝까지 읽어 age가 EOF까지 가게 한다(그 전에 끊기면 큰 dump에서 EPIPE이고, 마지막 chunk까지 인증하지 못한다). age·pg_restore 두 종료 코드가 모두 0이고 목록이 비어 있지 않아야 한다.
9. **업로드** — 두 저장소 각각 `incoming/`에 암호문·checksum을 올리고, 원격 object를 내려받아 계산한 SHA-256과 checksum 파일을 대조한다.
10. **이동** — 둘 다 맞으면 두 저장소 모두 `generations/`로 옮긴다. 한쪽 이동이 실패하면 이미 옮긴 쪽을 `incoming/`으로 되돌리고(못 하면 지운다) `FAILED MOVE_<STORE>`다 — `generations/`에 기록되지 않은 generation이 남지 않는다. 같은 날 앞선 실패의 같은 generation을 먼저 지우는 단계의 실패도 이동 실패다. 여기까지 모두 성공해야 `SUCCEEDED`(암호문 bytes·SHA-256·key ID)다 — 한쪽 업로드만으로는 성공이 아니다.
11. **결과 기록** — 결과 행 하나를 따로 기록한다(파기 행 묶음의 거절이 결과를, 결과의 거절이 파기 감사 행을 되돌리지 않게).
12. **파기·측정** — 아래 `### 파기`. 실패한 실행에서도 돈다. 그 행을 200행 이하 묶음으로 따로 기록한다.

**기록은 확인한다.** 기록 역할(`backup-record`, 활성 이미지)을 띄운 뒤 표에 정확히 그만큼의 행이 새로 생겼는지 psql로 본다 — rollback 뒤의 활성 이미지가 3.9 이전 것이면 그 프로필이 없어 아무것도 쓰지 않고 끝나기 때문이다(그 서비스는 `SPRING_MAIN_WEB_APPLICATION_TYPE=none`이라 웹 서버로 뜨지 않는다). 기록하지 못한 행은 대기열에 남고 종료 1이다 — 기록 없는 결과는 성공이 아니다. 대기열의 성공 행이 이미 표에 있으면(기록 뒤 확인만 실패했다) 다시 쓰지 않는다.

**DB에 닿지 못해도 파기는 돈다.** PostgreSQL이 멈췄거나 V24 표를 읽지 못하거나 `STARTED`를 기록하지 못하면 dump하지 않고(`FAILED DB_UNAVAILABLE`·`RECORD_TABLE_MISSING`·`RECORD_UNAVAILABLE`) 저장소 목록만으로 파기·측정을 한 뒤 행을 대기열에 넣고 종료 1이다 — DB 장애가 29일 넘은 object를 살려 두지 않는다(AC-4).

**신호.** 긴 단계(dump·암호화·catalog·rclone·기록 역할·psql)는 배경에서 돌리고 `wait`한다 — 그래서 systemd의 SIGTERM이 오면 bash가 그 단계가 끝나기를 기다리지 않고 바로 처리기를 돌아 그 단계의 process group을 끝내고, 임시본을 지우고, `FAILED INTERRUPTED`를 기록하고(90초 상한, 못 하면 대기열), 잠금을 놓는다. 단계마다 상한(1시간, 기록 5분, 조회 1분)이 있다.

Worker(와 `all`)가 그 표를 60초마다 읽어 `bokrilog_backup_last_success_timestamp_seconds`·`bokrilog_backup_store_usage_ratio{store}`·`bokrilog_backup_purge_overdue{store}`·`bokrilog_backup_store_measured_timestamp_seconds{store}`를 낸다(위 `### 메트릭`). 성공 기록이 없으면 gauge를 등록하지 않으므로 `BackupAgeUnmeasured`가 울린다. 저장소를 읽지 못해 측정이 멈추면 사용률·기한 초과는 옛 값 그대로 남으므로 측정 시각이 26시간 늙으면 `BackupStoreMeasurementStale`이 울린다.

### 파기

매 실행 끝에 두 저장소의 `generations/`·`incoming/`에서 generation 날짜 기준 **생성 후 29일을 넘은** 것을 지운다(하루 한 번 실행이면 30일 안에 제거된다). 이 30일 규칙에는 예외가 없다 — 새 성공이 없어 마지막 성공마저 29일을 넘기면 지운다(그 전에 26시간 경보가 이미 울린다). 그다음 저장소마다 수량 상한(`max_generations`)과 bytes 상한(`max_bytes_<store>`)을 넘으면 오래된 것부터 지우되, 이 둘은 마지막 `SUCCEEDED` 하나를 지우지 않는다. **마지막 성공을 알 수 없으면**(조회 실패·DB 없음·기록되지 않은 대기열 — "아직 성공이 없음"과 다르다) 수량·bytes 상한 파기를 건너뛰고 `PURGE_FAILED LIST_FAILED`를 남긴다. 로컬 임시본은 3단계가 매 실행 지운다.

지우지 못하면 `PURGE_FAILED`, 파기 뒤에도 30일을 넘긴 generation이 남으면 `PURGE_OVERDUE`이고, 둘 다 감사 원장에도 SYSTEM 행을 남긴다(`command=backup`, `scope=BACKUP`, target=generation ID, outcome `FAILED`, 수행자는 `BOKRILOG_BACKUP_ACTOR`의 `backup:` keyed digest). 저장소마다 `MEASURED`(bytes·generation 수·quota, 기한 초과가 남았으면 code `PURGE_OVERDUE`)를 남긴다. 사용률의 분자는 이 백업 object(`generations/`·`incoming/`)뿐이다 — 같은 계정의 다른 bucket은 세지 않으므로 계정 전체 사용률이 아니다(후속 작업). 파기 판단의 권위는 저장소 목록이고 표는 기록이다. 파기에 문제가 있으면 백업이 성공했어도 종료 1이다(systemd에서 실패로 보인다).

저장소 문제를 고친 뒤 파기만 다시 돌리려면 `backup.sh purge`다 — 같은 날 `run`은 no-op이라 파기를 다시 돌지 않는다.

시각은 실제 시계(UTC)다. 리허설만 `BOKRILOG_BACKUP_NOW`(epoch 초)로 덮고 운영에서는 거부한다.

### 키

| 파일 | 위치 | 내용 |
|---|---|---|
| recipient | `/etc/bokrilog/backup/active.pub`(644, group·other 쓰기 금지) | 첫 줄 `# key_id=<id>`(소문자·숫자·`_-`), 다음 줄 `age1…` 하나. `recipients/<id>.pub`과 같아야 한다 |
| key별 recipient | `/etc/bokrilog/backup/recipients/<key_id>.pub` | 그 key의 `age1…`(공개 값). 과거 key의 것도 남긴다 |
| identity | `/etc/bokrilog/backup/identities/<key_id>.key`(디렉터리 700·파일 600) | age identity(비밀). 현재 key의 것은 catalog 검사에 필요하다 |
| 저장소 설정 | `/etc/bokrilog/backup/rclone.conf`(600) | remote 둘(OCI·OCI 밖 — 서로 다른 rclone remote)의 자격정보. 값은 `rclone config`로 운영자가 만든다 |
| 백업 설정 | `/etc/bokrilog/backup/backup.env` | remote 이름·상한·quota(견본 `backup.env.example`, 비밀 아님, 끝의 `/`·`//` 금지) |

- **앱 key와 분리한다.** backup key는 자격정보 암호화 key ring·감사 digest 키와 섞지 않고, DB·Git·로그·compose 환경·앱 프로세스 어디에도 넣지 않는다 — 호스트 도구(age·rclone)만 읽는다. 운영 비밀 파일(`production.env`)에 age identity 모양의 값이 있으면 배포 gate가 거부한다(`BACKUP_KEY_IN_SECRETS`).
- **generation 기록과 object 이름에 key ID가 실린다**(`gen_<YYYYMMDD>.<key_id>.dump.age`·`.sha256`). object 이름에는 그 밖의 것이 없다 — 사용자·금융 식별자가 없다.
- **만들기.** `age-keygen -o identities/<id>.key`, `age-keygen -y identities/<id>.key > recipients/<id>.pub`, 그리고 `active.pub`을 `# key_id=<id>` + 그 recipient로 쓴다.
- **오프라인 복구 사본(필수).** key를 만들 때 **그 age identity의 사본을 OCI 밖 오프라인 자리(예: 비밀번호 관리자)에 바로 둔다**(PORTFOLIO-SOLUTION-DESIGN의 "서버·DB·OCI 밖에 복구 사본"). Story 3.10의 versioned recovery manifest(`backup_age_keys`)는 이 사본의 자리를 가리킬 뿐 사본을 대신하지 않는다 — 이 사본이 유일한 복구 경로다 — 사본 없이 서버를 잃으면 두 저장소의 백업은 **복호화할 수 없다**.
- **회전.** 새 key를 위와 같이 만들고 `active.pub`을 바꾼다. **보존이 끝나기 전에는(마지막으로 그 key를 쓴 generation이 30일 규칙으로 지워질 때까지) 과거 identity·recipient를 지우지 않는다.** `backup.sh keys`가 두 저장소에 남은 generation이 쓰는 key ID마다 identity가 있고 권한이 600이며 `age-keygen -y identity`가 `recipients/<id>.pub`과 같은지, 디렉터리 권한(key 디렉터리·`identities/` 700, `rclone.conf` 600, `active.pub` group·other 쓰기 금지)을 확인하고, 문제가 있거나 저장소를 읽지 못하면 그 key ID·이유를 보고하며 종료 1이다. 회전 뒤와 과거 identity를 지우기 전에 돌린다.

### 설치

1. **계정·디렉터리.** 백업은 배포를 돌리는 운영 계정 `bokrilog`(docker 그룹)으로 돈다. root로 한 번 만든다:
   `install -d -o bokrilog -g bokrilog -m 700 /etc/bokrilog/backup /etc/bokrilog/backup/identities /var/lib/bokrilog/backup` 과 `install -d -o bokrilog -g bokrilog -m 755 /etc/bokrilog/backup/recipients`.
2. **도구.** root로 `bash deploy/production/install-backup-tools.sh` — age v1.3.2·rclone v1.75.1을 고정 SHA-256 표로 받아 `/opt/bokrilog/backup-tools/bin`에 두고, 꺼낸 바이너리의 SHA-256을 설치 manifest(`/opt/bokrilog/backup-tools/MANIFEST`)에 남긴다(`.partial`에 받은 뒤 대조하고 원자 이동, 캐시는 쓸 때마다 다시 대조). **`/opt/bokrilog/backup-tools`는 root 소유이고 `bokrilog`가 쓸 수 없어야 한다** — `backup.sh`는 돌 때마다 `--check`로 버전과 manifest를 다시 대조한다. 운영 서버에서 이미지를 빌드하지 않으므로 두 도구는 호스트 바이너리다. `unzip`이 필요하다. `pg_dump`·`pg_restore`는 따로 설치하지 않는다 — 운영 postgres 컨테이너(18.6)의 것을 쓴다.
3. **파일.** `bokrilog`로 위 `### 키` 표의 파일을 두고(오프라인 사본 포함) bucket 둘은 운영자가 미리 만든다.
4. **systemd.** `deploy/production/systemd/bokrilog-backup.{service,timer}`를 `/etc/systemd/system/`에 두고(checkout 경로가 `/srv/bokrilog`가 아니면 `ExecStart`·`WorkingDirectory`를 고친다) `systemctl daemon-reload && systemctl enable --now bokrilog-backup.timer`. 실패한 날(단계 실패·배포 중·기록 실패)은 30분 뒤 다시 돈다(`Restart=on-failure`, 12시간에 6번까지 — 성공한 날의 재시작은 같은 날 no-op이라 멈추고, 파기 실패만 남은 날·LOCK_STALE도 이 상한에서 멈춘다). 정지 유예는 180초다(`TimeoutStopSec` — 신호 처리기의 기록 90초보다 길다).
5. **첫 실행.** `systemctl start bokrilog-backup.service` 뒤 `journalctl -u bokrilog-backup`과 `backup.sh keys`로 확인한다. 이것이 실저장소 검증의 시작이고, 30일 파기의 실제 증거가 쌓이기 전까지 상태는 실저장소 미검증이다.

**잠금이 남았을 때(LOCK_STALE).** 배포·백업이 SIGKILL·전원 차단으로 끝나면 `.lock`이 남는다. 스크립트는 그것을 스스로 지우지 않는다. `cat $BOKRILOG_STATE_DIR/.lock/owner`로 쥔 쪽·pid·operation을 보고, 그 pid가 없고 `docker ps`에 진행 중인 배포·백업 컨테이너가 없음을 확인한 뒤 `rm -rf $BOKRILOG_STATE_DIR/.lock`으로 지운다. 그 사이 쌓인 실패 행은 다음 백업이 대기열에서 기록한다.

### 명령

| 명령 | 역할 |
|---|---|
| `deploy/production/backup.sh run` | 위 순서 전체. 종료 0(성공·같은 날 no-op, 파기 완료, 모든 행 기록)·1(단계 실패·파기 실패·기한 초과·기록 실패(대기열)·잠금·중단)·2(준비 실패 — 설정·도구 버전·manifest·key 파일·권한·모드) |
| `deploy/production/backup.sh purge` | 파기·측정만 다시(잠금·대기열 기록 포함). 같은 날 `run`이 no-op일 때 파기를 다시 돌리는 길이다 |
| `deploy/production/backup.sh keys` | 남은 generation이 쓰는 key ID(와 active key)의 identity·recipient 짝·권한. 0·1(문제 key ID와 이유를 보고, 저장소를 읽지 못해도 1)·2 |
| `bash deploy/production/install-backup-tools.sh [--check] [--prefix …]` | 도구 설치·확인(버전·설치 manifest). `backup.sh`는 `--check`가 통과해야 돈다 |
| `bash deploy/production/test-backup-scripts.sh` | 고정 입력 시험(Docker·네트워크 불필요): 잘못된 배포본 → 2·빈 `bin/`, `--check`의 버전·manifest 불일치 → 1, `backup.sh`의 운영 모드 가상 시계·결함 주입·`production`의 리허설·설정 키·같은 remote·`/`·`//`·max_bytes > quota·권한·identity 없음·짝 불일치 → 2, `keys`의 저장소 읽기 실패·짝 불일치 → 1. Windows의 Git Bash는 권한 경로만 건너뛴다 |
| `deploy/production/rehearse-backup.sh` | 운영 compose + `compose.rehearsal.yaml` + `compose.rehearsal-backup.yaml`(MinIO 두 개, digest 고정)로 실제 절차를 돈다: 정상(전에는 gauge 미등록)·같은 날 no-op·단계 실패(dump·암호화·변조된 checksum·catalog)·한쪽 업로드 실패(OCI 밖 MinIO 정지)·부분 object 정리 뒤 재시도·한쪽 이동 실패(되돌림)·dump 중 SIGTERM(바로 정리·`FAILED INTERRUPTED`·잠금 해제)·잠금(배포 중·다른 백업 중·LOCK_STALE — 대기열과 다음 실행의 기록)·수량 상한·용량 상한(마지막 성공은 남는다)·29일 경계(정확히 29일은 남고 1초 뒤 지운다, 마지막 성공도 — 그 실행은 PostgreSQL을 멈춘 채 돈다)·30일 기한 초과(감사·gauge·`backup.sh purge`로 해소)·키 회전과 `keys`(과거 identity 삭제·짝 불일치·저장소 정지)·큰 dump(수 MB, 리허설 전용 scratch 표)·누출(출력·도구 기록·Worker 컨테이너 로그·object 이름·메트릭). 결과에 "실저장소 미검증"을 출력한다. 종료 0·1·2. `BOKRILOG_REHEARSAL_DIAGNOSTICS`를 주면 실패 기록을 옮긴다(key·저장소 설정·비밀 파일 제외) |

결함 주입 `BOKRILOG_BACKUP_FAULT`(`dump`·`slow-dump`·`encrypt`·`catalog`·`tamper-oci`·`move-offsite`·`purge-oci`·`purge-offsite`)와 가상 시계 `BOKRILOG_BACKUP_NOW`는 리허설 전용이고, 리허설 모드는 `BOKRILOG_ENV` ∈ {ci, development}와 리허설 override를 함께 요구한다(배포 gate와 같은 규칙). 공식 `minio/minio` 이미지는 Docker Hub에서 받을 수 없어(2026-10 확인) 같은 소스의 공개 빌드 `pgsty/minio`를 digest로 고정해 리허설에만 쓴다.

## 복원 (`deploy/production/restore.sh`, Story 3.10)

**상태: 실제 복원 미검증.** 절차는 CI 리허설(`restore-rehearsal` job — 같은 호스트의 MinIO 두 개, 가상 시계, 리허설 key)과 고정 입력 시험(`복원 스크립트 시험`)만 통과했다. 별도 환경에서 실제 manifest와 실제 generation으로 복원하기 전까지 복원은 검증되지 않은 것으로 다룬다. 자동시험·manifest 검사를 실제 복원 성공으로 표시하지 않는다. 훈련 증거 원장(시작·완료·실패·RPO·수동 작업시간·발견 결함의 감사), 새 송신 IP 재바인딩(`BYBIT_IP_REBIND_REQUIRED`), 호스트 전환 runbook, tombstone 키 회전 절차는 `deferred-work.md`에 있다.

### manifest

서버·OCI 계정을 잃은 뒤 다시 세우는 법은 **OCI 밖 저장소에 버전을 붙여 둔 복구 manifest** 하나가 담는다(예: `recovery-manifest-1.0.0.env`). 저장소에는 양식 `deploy/production/recovery-manifest.example`과 검사기 `check-recovery-manifest.sh`만 있다.

- 형식은 `key=value` 한 줄씩이고 키는 정해진 열여섯 개다 — `manifest_version`(버전), `image`(`<repository>@sha256:<64hex>`, tag만은 거부), `terraform`(`manual` 또는 `ref:`), `dns`·`tls`·`oauth`·`grafana`·`backup_config`(설정 위치), `backup_key_ids`(저장소에 남은 generation이 쓰는 key ID 전부), `credential_active_key_version`, 암호화된 운영 비밀의 위치 다섯(`credential_keys`·`audit_digest_key`·`tombstone_digest_key`·`tombstone_age_key`·`backup_age_keys`), `bootstrap_order`.
- 값은 `ref:<소문자 경로>`(위치 참조)·`age:<base64>`(age 암호문 — 본문이 `age-encryption.org/v1`로 시작해야 한다)·버전·digest·이름뿐이다. **평문 비밀을 넣지 않는다** — 검사기는 어느 줄에서든(주석·참조 경로·이름 칸 포함) `AGE-SECRET-KEY-`, PEM(`-----BEGIN`), Terraform state 본문, 16진 43자 이상(`HEX_SECRET`), 대소문자·숫자가 섞인 43자 이상의 base64·base64url(`LONG_BASE64`), 글자·숫자가 섞이고 구분자가 거의 없는 43자 이상의 토큰(`LONG_TOKEN`)을 거부한다. 걷어 내는 것은 비밀 키의 값 전체가 진짜 age 암호문인 경우와 `image=` 값 전체가 digest인 경우뿐이다. 알 수 없는 키·중복 키·필수 키 누락도 거부하고, 거부 출력에는 키 이름과 code만 있다.
- 바꿀 때 덮어쓰지 않고 새 버전으로 발행하고, 발행 전에 `bash deploy/production/check-recovery-manifest.sh <파일>`이 0이어야 한다. 검사기 통과는 모양의 증거일 뿐 복원의 증거가 아니다.

### tombstone

계정 삭제는 루트 `AccountErasure` 한 트랜잭션이다 — 삭제 전환 → **tombstone outbox 행**(V25 `operations_deletion_tombstone`, origin `DELETION`) → 모듈 erase port(portfolio → sync → plan → source → operations) → payload 삭제. 그래서 기록 없는 탈퇴가 없다. 사용자 삭제 경로(HTTP·화면)는 아직 없다(Epic 5) — 지금 부르는 것은 검증용 일회성 역할 `deletion-sample`(리허설 표지 `BOKRILOG_DELETION_SAMPLE_REHEARSAL=true`가 없거나 운영 환경이면 기동 거부, 사유를 code 한 줄로 남긴다)과 복원 적용뿐이다.

- 행은 `user_digest`(내부 user_id의 HMAC-SHA256, 입력 접두사 `tombstone:`)·`request_id`·`requested_at`(DB 시각, ms)·`destroy_after`(+35일 = 840시간)·`origin`(`DELETION` 또는 복원이 옮긴 `RESTORE`)뿐이다. 사용자 ID 원문·이메일·소셜 식별자·금융값·자격정보가 없다. 같은 요청 ID가 다른 계정·다른 digest를 가리키면 기록·옮기기 모두 실패한다(조용히 넘기면 tombstone 없는 계정이 생긴다).
- **두 key가 있다.** digest 키 `BOKRILOG_TOMBSTONE_DIGEST_KEY`(base64 32바이트 이상, 운영 비밀 파일 필수 — 배포 gate가 본다)는 **감사 digest 키와 달라야 한다** — 앱과 gate가 바이트로 비교하고 같으면 거부한다(`TOMBSTONE_KEY_REUSED`). 사본 암호화 key는 tombstone 전용 age key(`/etc/bokrilog/tombstone/recipient.pub`·`identity.key`, 디렉터리 700·identity 600)이고 **backup key와 다르다** — 같으면 내보내기가 거부한다. 둘 다 잃으면 외부 사본을 읽거나 복원된 계정과 맞출 수 없으므로 manifest(`tombstone_digest_key`·`tombstone_age_key`)가 가리키는 OCI 밖 자리에 사본을 둔다.
- **키 ID.** 내보내기 파일 머리는 `# bokrilog deletion tombstones DELETION-TOMBSTONE-1.0 key=<digest 키 ID> exported=<시각>`이다(키 ID는 키에서 나온 16진 16자 — 키를 되짚을 수 없다). 복원 적용은 사본의 키 ID가 자기 키와 다르면 아무것도 바꾸지 않고 실패한다(`TOMBSTONE_KEY_ID_MISMATCH`). **키 회전은 아직 지원하지 않는다** — 내보내기가 앞선 사본과 키 ID가 다르면 latest를 바꾸지 않는다(`TOMBSTONE_KEY_CHANGED`). 회전 절차는 `deferred-work.md`다.
- **내보내기.** systemd timer(`bokrilog-tombstone-export.timer`, 15분)가 `tombstone-export.sh`를 돈다. 배포·백업과 같은 잠금(kind `tombstone`)을 쥐고, 일회성 `tombstone-export` 역할이 기한 지난 outbox 행을 지우고 **파기 기한이 지금 + 24시간보다 뒤인 행 전체**를 권한 700 임시 디렉터리의 파일 하나로 쓴다(매번 전체라 "보냈다" 표시가 없고 재시도해도 같다). 그다음 **줄어듦 검사** — `latest`가 가리키는 앞선 사본을 풀어 기한이 지금 + 25시간보다 뒤인 digest가 새 파일에 모두 있는지 보고, 하나라도 없으면 `TOMBSTONE_SHRANK`이고 latest를 바꾸지 않는다(복원 중·잘못 붙은 DB의 내보내기가 사본을 덮어 tombstone을 잃지 않게. 앞선 사본을 풀 수 없으면 `PREVIOUS_UNREADABLE`). 통과하면 바로 암호화하고 평문을 지운 뒤 OCI 밖 저장소의 `tombstones/<UTC YYYYMMDDTHHMMSSZ>.age`에 올려 원격 SHA-256을 대조하고, 그다음에만 `tombstones/latest`(그 object 이름 한 줄)를 갱신한다. 이름의 시각으로 24시간을 넘긴 사본 object는 지운다.
- **외부 수명의 계산.** 사본은 24시간만 남고 각 사본은 기한이 24시간보다 더 남은 행만 싣는다 → 어느 tombstone도 외부에서 `requested_at + 35일`을 넘겨 남지 않는다. tombstone은 요청 뒤 34일까지 사본에 실린다. 복원은 30일을 넘긴 generation을 받지 않고(`GENERATION_TOO_OLD`), 지워진 계정이 든 generation은 요청보다 앞서므로 그 복원은 요청 뒤 30일 안에 일어난다 → 34일 안이라 언제나 그 tombstone이 실린 사본을 본다(백업 수명 30일 ≤ 외부 tombstone 34일 < 35일).
- **잠금 경합.** 배포·백업·복원이 잠금을 쥐고 있으면 내보내기는 **조용히 건너뛴다**(종료 0과 한 줄, 다음 주기가 다시 돈다 — 그 틈은 주기 하나다). 소유자가 죽은 잠금은 종료 1이다. 반대로 백업은 내보내기가 쥔 잠금을 5분까지 기다린다(내보내기는 길어야 1분 — 그 때문에 하루 백업을 놓치지 않게).
- **백업 안의 outbox는 권위가 아니다.** 백업은 그 뒤의 삭제를 모른다. 복원은 언제나 외부 사본(`latest`)을 적용하고, 백업 안의 outbox는 대조에만 쓴다.

### 복원 순서

`restore.sh --generation <gen_YYYYMMDD|latest> --failure-at <ISO-8601> [--project bokrilog-restore-<x>] [--image <repository@sha256:…>] [--tombstone-max-lag <초>] [--allow-stale-tombstones] [--keep-on-failure]`. 같은 호스트의 **빈 격리 compose 프로젝트**에 복원한다 — 이름은 `bokrilog-restore-` 접두사여야 하고, 운영 프로젝트(`BOKRILOG_PROJECT`)와 같거나 이미 컨테이너·volume·망이 있는 프로젝트면 거부한다(종료 2). 운영 프로젝트·volume 위에 복원하지 않는다(Ask First). 호스트를 잃어 배포 상태가 없으면 `--image`에 manifest의 `image`를 준다(digest만 받는다).

0. 30일을 넘긴 generation은 거부한다(`GENERATION_TOO_OLD`). 외부 `tombstones/latest`가 없으면 `TOMBSTONES_UNAVAILABLE`, 그 사본이 장애 시각 − `--tombstone-max-lag`(기본 3600초)보다 앞서면 `TOMBSTONES_STALE`이다 — 둘 다 아무것도 띄우지 않는다. `--allow-stale-tombstones`는 경고를 남기고 계속하고 마지막 줄에 그 사실을 적는다(그 사이의 삭제는 그 복원에 없다). 그다음 배포·백업과 같은 잠금(kind `restore`)을 쥐고 앞선 실행의 임시본을 지운다.
1. OCI 밖 저장소 `generations/`(OCI는 보지 않는다)에서 고른 generation의 암호문·checksum을 권한 700 임시 디렉터리에 받아 SHA-256(checksum 파일 첫 줄)을 대조한다 — 다르면 `CHECKSUM_MISMATCH`, 아무것도 띄우지 않는다.
2. 이름의 key ID로 backup identity(`identities/<key_id>.key`)를 골라 복호화한다 — 없으면 `KEY_UNAVAILABLE`. 필요한 것은 identity 하나다(호스트를 잃고 오프라인 사본만 남은 경우) — `recipients/<id>.pub`이 있으면 짝을 대조하고 없으면 경고만 한다. **보존이 끝나기 전에는 과거 identity를 지우지 않는다**.
3. 새 프로젝트의 빈 PostgreSQL(새 volume)에 `pg_restore --single-transaction --exit-on-error`로 복원하고 평문 dump를 바로 지운다.
4. migrate 역할(AD-12).
5. 0에서 본 사본을 받아 tombstone identity로 복호화한다 — 풀리지 않거나 머리(`… key=`)가 없으면 `TOMBSTONES_UNAVAILABLE`이고 **서비스를 열지 않는다**.
6. 일회성 `restore-apply` 역할(web none, Flyway 검증). **격리 표지**(`BOKRILOG_RESTORE_ISOLATED_PROJECT`, `bokrilog-restore-*` — 절차가 준다)가 없으면 아무것도 하지 않고 실패한다(`RESTORE_NOT_ISOLATED`). 먼저 **대조**한다 — 사본의 키 ID가 자기 키와 같아야 하고(`TOMBSTONE_KEY_ID_MISMATCH`), 복원된 outbox의 `DELETION` 행 가운데 사본보다 먼저 요청됐고 사본에 실릴 만큼 기한이 남은 것은 사본에 있어야 하며, 그 행들은 지금 키로 계산한 어느 복원된 계정의 digest와 맞아야 한다(`TOMBSTONE_KEY_MISMATCH` — 키가 틀리면 아무 계정도 맞지 않아 "0건 일치"로 조용히 통과하기 때문이다). 어긋나면 아무것도 바꾸지 않는다. 맞으면 복원된 모든 user_id를 tombstone 키로 digest해 사본과 맞추고, 맞은 계정을 `AccountErasure`로 다시 지워 `DELETED`로 만든다(이미 `DELETED`면 그대로). 사본의 tombstone 전부를 복원된 outbox로 옮긴다(origin `RESTORE` — 다음 내보내기가 잃지 않게). `spring_session` 전체를 지운다. 불변식(활성 계획 유일, 공식 포인터 → 계산 버전 → portfolio snapshot 귀속 — 대시보드 묶음과 같은 술어, portfolio 현재 포인터, tombstone이 다시 지우지 않은 `DELETION_IN_PROGRESS` 계정 0, 삭제 계정의 잔여 행 0 — 모듈마다 자기 표의 사용자 열을 카탈로그로 센다)과 자격정보 표본 복호(key version마다의 최신 행과 최근 20행, active key 필수, 평문은 반환하지 않고 외부 호출 없음)를 센다. 결과는 수와 code만 담은 보고 한 줄(`restore.apply … result=PASSED|FAILED codes=…`)이고, 실패면 종료 0이 아니어서 `APPLY_FAILED`이다.
7. 그다음에만 `api-blue`·`worker`를 띄운다. nginx는 띄우지 않고 공개 포트도 없다(있으면 API·Worker를 먼저 멈추고 `ISOLATION_BROKEN`). 로그인 제공자·Bybit·공개 base URL·운영자 값은 빈 값으로 덮어 띄우고(fail-closed — 외부 호출 0) 메트릭 공통 tag `env`는 `restore`다(운영 경보 selector는 `env="production"`). 늦게 뜬 Worker·새 세션은 기존 접근 판정(`DELETED` → `DENIED`)이 막는다.
8. RPO를 판정한다.

**실패하면 지운다.** 3단계(격리 PostgreSQL 기동) 뒤의 어느 실패든 격리 프로젝트를 volume까지 지운다 — 되살린 데이터를 남기지 않는다. 조사가 필요하면 `--keep-on-failure`로 남기고, 그때는 경고와 지우는 명령을 출력하므로 끝나면 반드시 지운다. 평문 dump·tombstone은 권한 700 임시 디렉터리(`/var/lib/bokrilog/restore/tmp`)에만 두고 쓰자마자, 그리고 어느 실패·신호에서든 지운다.

**성공한 격리 스택은 남는다**(조사·전환용). 남아 도는 동안에는 `deploy.sh`가 시작하지 않는다(`RESTORE_STACK_RUNNING` — PostgreSQL 3g·API·Worker가 blue/green peak 위에 겹친다). 끝나면 `docker compose --project-name <project> --file deploy/production/compose.yaml down --volumes`로 지운다.

**적용만 다시 돌리기.** 적용은 다시 돌려도 안전하다 — 이미 `DELETED`인 계정은 건너뛰고, 옮긴 행은 그대로 두고, 세션 폐기는 0행이어도 된다. 그래서 중간에 멈춘 적용(예: `--keep-on-failure`로 남긴 프로젝트에서 원인을 고친 뒤)은 같은 사본으로 다시 돌린다: 권한 700 디렉터리에 사본을 풀어 `tombstones.txt`로 두고, `BOKRILOG_ROLE_IO_DIR=<그 디렉터리> BOKRILOG_ENV=restore docker compose --project-name <project> --env-file <운영 비밀 파일> --file deploy/production/compose.yaml --profile restore-apply run --rm --no-deps -T --user "$(id -u):$(id -g)" -e BOKRILOG_RESTORE_ISOLATED_PROJECT=<project> restore-apply`(이미지 변수는 `restore.sh`의 `compose()`와 같다), 0이면 `up -d --no-deps --wait api-blue worker`. 다 쓰면 그 디렉터리를 지운다. 처음부터 다시 하려면 프로젝트를 지우고 `restore.sh`를 다시 돈다.

### RPO

RPO = `--failure-at` − generation 생성 시각. 생성 시각은 checksum 파일 둘째 줄의 dump 시작 시각(`dump_started_at=<epoch>`, `backup.sh`가 실제 시계로 적는다)이고, 그 줄이 없는 옛 generation은 generation 날짜의 00:00 UTC다(보수적). 저장소 object 시각은 쓰지 않는다 — dump보다 늦다. 24시간 이하면 통과, 넘으면 `RPO_EXCEEDED`이고 종료 1이다(복원은 끝났고 프로젝트는 남는다). `--rpo-only`는 저장소 목록과 checksum 파일만 보고 RPO만 잰다(내려받지 않는다). 장애 시각은 시간대가 붙은 실제 ISO-8601 시각이어야 한다(달의 길이·윤년·±14:00 안).

### 설치

1. `install -d -o bokrilog -g bokrilog -m 700 /etc/bokrilog/tombstone /var/lib/bokrilog/tombstone /var/lib/bokrilog/restore`.
2. `bokrilog`로 `age-keygen -o /etc/bokrilog/tombstone/identity.key`(600)와 `age-keygen -y … > /etc/bokrilog/tombstone/recipient.pub`. identity의 사본을 바로 OCI 밖 오프라인 자리에 두고 manifest의 `tombstone_age_key`가 그 자리를 가리키게 한다. 내보내기도 identity를 읽는다(줄어듦 검사).
3. 운영 비밀 파일에 `BOKRILOG_TOMBSTONE_DIGEST_KEY`(감사 digest 키와 다른 32바이트 이상 base64 — 배포 gate의 필수 이름이다)를 더하고 그 사본도 manifest가 가리키는 자리에 둔다. 비밀 파일에 age identity 모양의 값을 넣지 않는다(gate가 거부한다).
4. `deploy/production/systemd/bokrilog-tombstone-export.{service,timer}`를 `/etc/systemd/system/`에 두고 `systemctl daemon-reload && systemctl enable --now bokrilog-tombstone-export.timer`.
5. 복원 훈련은 손으로 돈다: `restore.sh --generation latest --failure-at <지금>`. 백업과 같은 `BOKRILOG_BACKUP_KEYS_DIR`(identity·`rclone.conf`·`backup.env`)·`BOKRILOG_BACKUP_TOOLS`를 쓴다. 같은 호스트의 격리 복원은 PostgreSQL 3g·API 768m·Worker 512m를 더 쓰므로 여유 메모리를 먼저 본다. 끝나면 격리 프로젝트를 지운다(지우기 전에는 배포가 시작되지 않는다).

### 명령

| 명령 | 역할 |
|---|---|
| `deploy/production/restore.sh --generation … --failure-at … [--project …] [--image …] [--tombstone-max-lag …] [--allow-stale-tombstones] [--keep-on-failure] [--rpo-only]` | 위 순서. 종료 0(복원·적용·검증·기동 성공, RPO ≤ 24h)·1(`GENERATION_TOO_OLD`·`TOMBSTONES_STALE`·`CHECKSUM_MISMATCH`·`KEY_UNAVAILABLE`·`TOMBSTONES_UNAVAILABLE`·`APPLY_FAILED`·`ISOLATION_BROKEN`·`RPO_EXCEEDED`·잠금·중단 …)·2(인자·프로젝트 이름·이미지·설정·도구·key·권한·모드) |
| `deploy/production/tombstone-export.sh` | tombstone 외부 사본. 0(완료 또는 잠금 때문에 건너뜀)·1(역할·줄어듦·키 ID 변경·암호화·업로드·대조·파기 실패, 죽은 잠금)·2 |
| `bash deploy/production/check-recovery-manifest.sh <파일>` | manifest 검사. 0·1(거부)·2(파일·인자) |
| `bash deploy/production/test-restore-scripts.sh` | 고정 입력 시험(Docker·네트워크 불필요): manifest 견본·age 값 → 0, 평문 identity·PEM·base64·base64url key·16진 비밀·주석의 `age:` 비밀·가짜 `age:` 값·이름 칸·참조 경로의 긴 토큰·tfstate·tag 이미지·키 누락·알 수 없는 키·중복·대문자 참조 → 1, `restore.sh`·`tombstone-export.sh`의 준비 거부(운영 프로젝트 이름·이미지·lag·모드·설정·recipient·identity) → 2, `epoch_of_iso`(달의 길이·윤년·시간대 범위) |
| `deploy/production/rehearse-restore.sh` | 운영 compose + 리허설 override + MinIO 두 개의 운영 대역에서 실제 `backup.sh`로 generation 둘(key 회전), 앱과 같은 형식으로 봉한 표본 자격정보, 표본 삭제(운영 환경·표지 없음이면 사유와 함께 거부), 내보내기(기한 지난 행·24시간 지난 object 제외, 줄어든 내보내기 거부, 잠금 중 건너뜀)를 만든 뒤 시나리오를 돈다: `latest` 선택·tombstone 없음·잠금 중 복원·삭제 이전 generation의 정상 복원(과거 key, 표본 `DELETED`·세션 0·유지 계정·자격정보 1/1, OIDC·Bybit 값 없음, env restore, nginx·공개 포트 없음)·RPO 경계(dump 시작·옛 generation의 날짜 기준)·30일 넘은 generation·과거 identity 없음·checksum 변조·다른 key ring(APPLY_FAILED, `--keep-on-failure`)·다른 digest 키(TOMBSTONE_KEY_ID_MISMATCH, 지워짐)·풀 수 없는·머리 없는·오래된 latest·운영 이름·비어 있지 않은 프로젝트·누출. 결과에 "실제 복원 미검증"을 출력한다. 종료 0·1·2 |

공용 함수(배경 실행·권한·key 짝·rclone·시각)는 `backup-lib.sh`에 있고 `backup.sh`·`restore.sh`·`tombstone-export.sh`가 함께 읽는다(`backup.sh`는 최상위 `exit` 때문에 `.`로 읽을 수 없다).

## CI (`.github/`)

`.github/workflows/ci.yml`이 이 저장소의 유일한 workflow다. `develop`·`main`의 PR과 push, 그리고 배포 권한이 없는 수동 진단(`workflow_dispatch`)에서 돈다. 정본은 `CI-PLAN.md`이고 구현 계약은 `_bmad-output/implementation-artifacts/spec-ci-foundation.md`와 `spec-1-2-same-origin-shell.md`가 소유한다.

Story 1.2에서 backend와 deploy가 생기면서 단일 job이 `CI-PLAN.md` §1의 병렬 구조로 갈라졌다. branch protection이 참조하는 이름은 전환 전후 모두 `ci-required`이며 바꾸지 않는다. 다만 이제 그 job은 검사를 직접 실행하지 않고 선행 job의 결과만 집계한다.

```text
changes ──> frontend-quality ──┐
        └─> backend-quality ───┼─> same-origin-integration ─┐
                 │             ├─> deploy-rehearsal ────────┤
                 │             └────────────────────────────┤
                 └─> restore-rehearsal ─────────────────────┴─> ci-required
```

| job | 역할 | timeout |
|---|---|---|
| `changes` | secret 검사, CI 계약 시험, 변경 분류 | 5분 |
| `frontend-quality` | 타입 검사 → ESLint → stylelint → Prettier → 빌드 → Vitest → Chromium E2E | 8분 |
| `backend-quality` | JDK 25 준비 → Gradle `build`(컴파일·Modulith 경계 검사·PostgreSQL Testcontainers 시험·jar) | 20분 |
| `same-origin-integration` | 경보 규칙 시험 → 두 품질 job의 산출물로 동일 출처 검증(`--keep-stack`, `BOKRILOG_ENV=ci`) → 같은 스택에서 Story 1.1 회귀 | 17분 |
| `deploy-rehearsal` | 배포 gate 고정 입력 시험 → 같은 두 산출물로 운영 배포 리허설(Story 3.8, 위 `## 운영 배포`) → 백업 스크립트 고정 입력 시험 → 같은 jar로 백업 리허설(Story 3.9, 위 `## 백업` — MinIO 두 개). green은 운영 배포·실저장소의 증거가 아니다 | 70분 |
| `restore-rehearsal` | 복원 스크립트 고정 입력 시험 → backend jar로 격리 복원 리허설(Story 3.10, 위 `## 복원` — `deploy-rehearsal`과 병렬). green은 실제 복원의 증거가 아니다 | 40분 |
| `ci-required` | 선행 job 결과 집계. 병합 가능 여부 판정 | 1분 |

`backend-quality`의 15분은 Story 1.3이 10분에서 올린 값이다. 이 job은 이제 Testcontainers로 실제 PostgreSQL을 띄우고, 콜드 실행에는 `postgres:18.6-alpine` 내려받기가, 동시성 시험에는 실제 잠금 경합 대기가 더해진다. 러너에 docker 데몬이 이미 있으므로 서비스 컨테이너도 새 action도 더하지 않았고, 시험은 기존 `Gradle 빌드` 단계 안에서 돈다. `CI-PLAN.md` §4·§10의 backend 예산 8분은 이 상한과 별개로 계속 측정한다. Story 2.11이 15분을 20분으로 올렸다. PR #49에서 이 job이 11분 5초였고, 같은 시험의 로컬 실행보다 1분쯤 길었다. 2.11의 로컬 `clean build`가 12분 56초라 CI는 14분 안팎이 된다. PR #52의 이 job은 13분 0초였다. 그 시간의 대부분은 실제 파이프라인을 모는 `FirstSnapshotTest`·`IncrementalSyncTest`가 워커의 1초 dispatch tick과 고정 `Thread.sleep`을 기다린 몫이었다. `spec-backend-test-speed.md`가 시험의 dispatch 간격을 100ms로 줄이고(`bokrilog.sync.worker.dispatch-interval`, 운영 기본 1초, heartbeat 5초는 그대로) 관측 가능한 조건은 조건 대기로 바꿨다. 그 변경이 변경 전 커밋(`3824c3c`)을 다시 잰 로컬 `clean build`는 13분 2초였고(위 2.11의 12분 56초와 별개의 재측정이다), 변경 뒤에는 3분 13초다(시험 869건에 운영 주기를 고정하는 Docker 없는 시험 두 건을 더해 871건, 두 클래스 605초 → 115초, 124초 → 28초). Story 2.12가 그 위에 정밀 검증·호출 예산 시험을 더해 로컬 `clean build`가 4분 41초가 됐다(989건, 새 `PlanVerificationTest` 75초). timeout 20분은 CI-PLAN 새 버전이 보정할 때까지 그대로 둔다.

`same-origin-integration`의 17분은 `CI-PLAN.md` §8이 적은 시작값 5분과 다르다. 그 값은 이 job이 무엇을 하는지 정해지기 전의 추정이고, 지금은 Story 1.1 회귀 단계 하나가 6분을 쓴다. 회귀 단계는 Story 2.11이 4분에서 6분으로 올렸고, 주입된 origin의 Playwright `globalTimeout`도 3분에서 5분으로 올렸다. PR #49에서 이미 2분 30초(59건)였고 2.11이 실제 스택의 동기화 왕복을 더했기 때문이다. preview를 쓰는 `frontend-quality`의 E2E 단계는 4분·3분 그대로다. 단계별 상한의 합(내려받기 1 + 동일 출처 검증 7 + `npm ci` 2 + Chromium 설치 2 + 회귀 6 + 정리·업로드 1)이 19분이지만 17분을 유지한다. 검증 단계에 Story 1.3이 세 가지를 더했다 — `postgres:18.6-alpine` 이미지 내려받기, migrate 이미지 빌드, 마이그레이션 실행 — 그래서 그 단계의 예산을 5분에서 7분으로 본다. 실측(PR #9의 2분 2초)과 상한 사이에 아직 여유가 크므로 job 상한은 그대로 두고, 실측이 몇 회 더 쌓이면 두 값을 `CI-PLAN.md` 새 버전이 함께 보정한다. 검증 스크립트가 `--keep-stack`으로 남긴 스택을 회귀가 그대로 쓰므로 compose는 한 번만 뜬다. 필수 검사를 빼서 5분에 맞추지 않는다(CI-PLAN §10). 이 job이 `frontend-quality`의 `npm ci`와 Chromium 설치를 되풀이하는 중복은 `deferred-work.md`에 남겼다. 목표는 `CI-PLAN.md` §10의 PR wall-clock p95 8분이며 job timeout은 목표가 아니라 멈춘 job을 끊는 상한이다.

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
| `.github/scripts/check-alert-rules.sh` | 경보 규칙의 형식 계약·문법·promtool 단위시험(Story 3.7). 통합 job에서 돈다 |
| `.github/scripts/test-ci-scripts.sh` | 위 스크립트들과 `ci.yml`의 계약 시험(경보 규칙 스크립트는 가짜 promtool로 종료 코드 0/1/2를 단정한다) |
| `deploy/production/test-preflight.sh`·`rehearse-deploy.sh`·`rehearse-backup.sh` | 배포 gate 고정 입력 시험, 운영 배포 리허설(Story 3.8), 백업 리허설(Story 3.9). `deploy-rehearsal` job에서 돈다 |
| `deploy/production/test-restore-scripts.sh`·`rehearse-restore.sh` | 복원 스크립트 고정 입력 시험, 격리 복원 리허설(Story 3.10). `restore-rehearsal` job에서 돈다 |

lint·stylelint·format 세 단계는 `spec-frontend-lint-policy.md`가 합의한 품질 정책이며 `ci-required`가 집계하는 job 안에 있으므로 별도 보호 규칙 변경 없이 필수 검사다. 문서-only 변경(`_bmad-output/**`와 최상위 `*.md`)은 `changes` job만 돌고 제품 job 다섯(`frontend-quality`·`backend-quality`·`same-origin-integration`·`deploy-rehearsal`·`restore-rehearsal`)은 건너뛴다. 분류는 fail-closed다. 규칙에 없는 경로가 하나라도 있으면 전체 검사를 실행하며, `backend/`·`deploy/`처럼 `<디렉터리>/<파일>` 모양인 경로는 자동으로 제품 변경이라 분류 규칙을 손댈 필요가 없다. PR에서는 체크아웃된 head가 아니라 target base의 승인된 분류기를 꺼내 실행한다. PR이 자기 분류 규칙을 고쳐 그 PR의 검사를 축소할 수 없게 한다.

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

지침 파일은 스킬과 달리 **한 벌이 아니다.** Claude Code는 `CLAUDE.md`를, Codex는 `AGENTS.md`를 자동으로 읽는다. 규칙의 권위는 `CLAUDE.md` 하나이며 `AGENTS.md`는 작은 블록이다 — 오리엔테이션, 정책 몇 줄, 루트에 매니페스트가 없어서 어느 파일도 말해 주지 않는 실행 명령, 그리고 `backend/`·`frontend/`·`deploy/`·`.github/`·`_bmad-output/`을 바꾸기 전에 `CLAUDE.md`를 읽으라는 지시다. 규칙을 두 파일에 나눠 적지 않는다 — 스킬 트리는 `check-bmad-env.sh`가 동일성을 검사하지만 지침 파일에는 그런 장치가 없어 복사본은 조용히 갈라진다.

`AGENTS.md`의 `<!-- bmad:context -->` 마커 안쪽은 `bmad-project-context` 스킬이 소유한다. 손으로 고친 내용은 다음 refresh에서 교체되므로 보존할 것은 마커 바깥에 둔다. 저장소가 크게 바뀌거나 에이전트가 같은 실수를 되풀이하면 그 스킬을 다시 돌린다.

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
