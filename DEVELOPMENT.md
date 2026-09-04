# 개발 환경

이 저장소의 BMAD 도구는 운영체제 기본 `python3`를 직접 사용하지 않는다. BMAD 스크립트가 요구하는 Python 3.11 이상을 `uv`가 선택하거나 내려받아 실행한다. 제품 애플리케이션의 기술 기준선은 `ARCHITECTURE-SPINE.md`에서 Java/Spring/PostgreSQL과 React/Vite로 확정되었으며, 이 Python 환경은 제품 런타임과 무관한 기획 도구 전용이다. Story 1.1에서 `frontend/`의 React/Vite SPA와 그 lockfile이 생겼다. 백엔드 코드와 image digest는 아직 생성 전이다.

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
| `frontend/src/test/setup.ts` | Testing Library 등록과 `fetch`·`XMLHttpRequest`·`sendBeacon` 감시 훅 |
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
- `localStorage`·`sessionStorage`·쿠키·IndexedDB에 쓰지 않는다. 인증 토큰과 금융 원본은 브라우저 영속 저장소에 두지 않는다.
- 라이트 모드만 제공한다. 다크 모드는 별도 대비 검증 뒤 후속 범위다.
- 확대 검증은 브라우저 zoom 설정 대신 **뷰포트 폭 = 1280px ÷ 배율**로 재현한다(WCAG 1.4.10 표준 해석). 400%는 320×512, 200%는 640×512다.

## CI (`.github/`)

`.github/workflows/ci.yml`이 이 저장소의 유일한 workflow이고 job은 `ci-required` 하나다. `develop`·`main`의 PR과 push, 그리고 배포 권한이 없는 수동 진단(`workflow_dispatch`)에서 돈다. 이 이름이 branch protection의 필수 검사 이름이므로 바꾸지 않는다. 정본은 `CI-PLAN.md`이고 구현 계약은 `_bmad-output/implementation-artifacts/spec-ci-foundation.md`가 소유한다.

판정 논리는 YAML이 아니라 `.github/scripts/`의 셸 스크립트가 소유한다. YAML 안의 인라인 `run` 블록은 저장소에서 실행할 수 없어 시험 대상이 되지 못하기 때문이다.

| 경로 | 역할 |
|---|---|
| `.github/workflows/ci.yml` | 이벤트·권한·단계 순서. 판정은 아래 스크립트에 위임한다 |
| `.github/scripts/verify-merge-commit.sh` | PR merge commit의 부모가 이벤트의 base·head와 같은 순서인지 검증 |
| `.github/scripts/classify-changes.sh` | 문서-only 여부 판정. `docs-only`·`reason`을 `GITHUB_OUTPUT`과 job summary에 기록 |
| `.github/scripts/test-ci-scripts.sh` | 위 두 스크립트와 `ci.yml`의 계약 시험 |

frontend 검사는 `타입 검사` → `ESLint` → `stylelint` → `Prettier 검사` → `프로덕션 빌드` → `Vitest` → `Chromium E2E` 순서다. lint·stylelint·format 세 단계는 `spec-frontend-lint-policy.md`가 합의한 품질 정책이며 이름이 고정된 `ci-required` 안에 있으므로 별도 보호 규칙 변경 없이 필수 검사다. 문서-only 변경(`_bmad-output/**`와 최상위 `*.md`)은 secret 검사만 돌고 frontend 검사를 건너뛴다. 분류는 fail-closed다. 규칙에 없는 경로가 하나라도 있으면 전체 검사를 실행한다. PR에서는 체크아웃된 head가 아니라 target base의 승인된 분류기를 꺼내 실행한다. PR이 자기 분류 규칙을 고쳐 그 PR의 검사를 축소할 수 없게 한다.

secret 검사(gitleaks)의 예외는 저장소 루트 `.gitleaksignore`가 소유한다. 한 줄에 fingerprint 하나이며 각 항목에 근거 주석을 남긴다. 규칙 자체를 완화하는 `.gitleaks.toml`은 두지 않는다. 필수 경로는 PR 커밋 범위만 검사하므로 기존 이력 전체는 `workflow_dispatch`로 돌리는 기준선 스캔에서만 드러난다.

### 명령

| 명령 | 역할 |
|---|---|
| `bash .github/scripts/test-ci-scripts.sh` | CI 계약 시험. 임시 git fixture로 분류 규칙·`reason` enum·merge commit 검증을 실행하고, `ci.yml`의 job 이름·러너·고정 action SHA·문서-only 게이트 수를 단정한다 |

종료 코드는 0(전부 통과), 1(단정 실패), 2(fixture 준비 실패)다. 준비 실패는 단정 결과가 없다는 뜻이라 단정 실패와 구분한다. Windows에서는 Git Bash로 실행한다. CI도 이 시험을 조건 없이 항상 실행하며, 분류 단계보다 앞에 둔다. 분류기를 분류기로 게이트하지 않기 위해서다.

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
- `.github/`: GitHub Actions workflow와 CI 판정 스크립트. 위 `## CI` 절을 따른다.
- `.gitattributes`: 줄바꿈 규칙을 저장소에 고정한다. Windows·macOS가 같은 바이트를 보게 한다.
- `.gitignore`: 로컬 캐시, 가상환경, Python 바이트코드, 두 운영체제의 메타데이터, IDE 로컬 상태와 빌드 산출물을 제외한다.
- `scripts/check-bmad-env.sh`: 위 설정이 실제로 동작하는지 한 번에 검증한다.

애플리케이션 기술 스택과 허용 버전은 아키텍처 문서를 권위 기준으로 삼고, 실제 설치 버전은 lockfile과 image digest가 소유한다. 프런트엔드는 `frontend/package-lock.json`이, 백엔드와 운영 이미지는 향후 Gradle lockfile과 image digest가 소유한다. 제품이 Python 애플리케이션이라는 전제에서 `.python-version`, `pyproject.toml`, 애플리케이션 `.venv`를 만들지 않는다.
