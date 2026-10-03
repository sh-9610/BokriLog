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
- 해제의 확인 대화상자(`components/UnlinkDialog.tsx`)는 DESIGN.md `confirmation-dialog`가 규정한 형태다 — 제목에 대상과 결과를 쓰고, 삭제되는 것과 보존되는 것을 문단으로 나누고, **기본 포커스는 취소**이며, 포커스를 가두고, 닫을 때 호출점으로 돌려준다. `<dialog>`를 쓰지 않는 이유는 `showModal`의 동작이 층마다 절반만 살기 때문이고, 필요한 넷을 직접 들고 있는 편이 어느 층에서도 같은 동작을 보장한다. 대상명 재입력은 요구하지 않는다(DESIGN.md가 그것을 계정 삭제 급의 작업에만 건다). 해제 결과 표지 셋은 **URL을 지나지 않고** 화면당 하나뿐인 `role="status"` 영역이 말한다 — 왕복이 없어 문서가 바뀌지 않으므로 표지를 주소에 실을 이유가 없고(실으면 어떤 서버 경로도 만들지 않는 문장을 주소창이 만들어 낸다, `signedOut`과 같은 자리다), 섹션 안에 두면 취소·실패에서 포커스도 움직이지 않아 보조기술 사용자에게 아무 신호가 없다. 포커스는 성공에서만 결과 문장으로 옮겨 가고(그때 호출점 버튼이 사라진다) 나머지에서는 대화상자가 호출점으로 돌려준다 — 두 자리가 조건을 나눠 갖지 않으면 포커스가 잠깐 그 버튼에 얹혔다가 목록 갱신과 함께 `<body>`로 떨어진다. 대화상자가 열려 있는 동안 배경은 `inert`이고 문서 스크롤도 잠긴다.
- 설정 화면과 대시보드는 **서로를 밀어내지 않는다**. 서버의 `destination`은 "로그인 뒤 착지할 화면"이고 설정은 사용자가 스스로 여는 자리이므로, 대시보드를 이유로 밀어내면 그 경로에 도달할 방법이 없어진다(연결 왕복이 돌아오는 자리도 그 화면이다). 반대 방향도 같은 이유로 열려 있어야 한다 — 예외가 한 방향뿐이면 미인증으로 `/settings`를 열어 로그인한 사용자는 `destination`이 `settings`로 굳어 그 세션 내내 대시보드에 닿지 못한다. 두 화면 모두 온보딩만이 밀어낸다.
- 두 로그인 상태 화면의 머리말이 설정 화면으로 가는 링크를 준다. 전역 내비게이션은 아직 없고(Epic 1의 범위가 아니다) 그 링크가 없으면 설정 화면은 주소를 직접 쳐야만 닿는다. 탭 순서는 건너뛰기 링크 → 설정 링크 → 로그아웃이다.
- 경로는 라우터 없이 `App.tsx`의 닫힌 맵이 화면에 대응한다(`/`·`/onboarding`·`/dashboard`·`/settings`). 라우트 하나가 **다섯 곳**에 산다 — `App.tsx`의 경로 맵, `deploy/nginx/bokrilog.conf`의 허용 목록 정규식, 서버의 `AuthFlowHandlers.ALLOWED_DESTINATIONS`, `useSessionState`의 목적지 집합, 그리고 `content/auth.ts`의 `RETURN_DESTINATIONS`(로그인 뒤 돌아갈 수 있는 경로)다. 다섯이 같은 집합이어야 하고 그 등가를 보는 검사는 아직 없다(미해소 항목). 한 곳만 고치면 증상이 제각각이다: Nginx가 빠지면 404, `App.tsx`가 빠지면 진입 화면, 서버 허용 목록이 빠지면 복귀가 대시보드로 접히고, 목적지 집합이 빠지면 타입이 막으며, `RETURN_DESTINATIONS`가 빠지면 미인증 진입에서 `next`가 실리지 않는다. 로그인 상태 경로는 `GET /api/auth/session`으로 서버 세션을 확인한 뒤에만 내용을 그리고, 미인증·오류는 인증 화면으로 되돌린다.
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
- 내부 경로·스택·프레임워크 기본 오류 본문·환경 변수 이름은 응답과 로그 어디에도 남기지 않는다. 그래서 `spring.main.log-startup-info`가 꺼져 있고(기동 로그가 jar 경로와 실행 사용자를 남긴다), 처리하지 못한 예외의 스택은 기본 로그에 찍지 않는다. 스택이 필요한 조사에서는 `logging.level.com.bokrilog=debug`로 한 번만 열고 다시 닫는다.
- 상태 응답에 시각·버전·호스트명·빌드 정보를 넣지 않는다. 인증 없이 공개되는 엔드포인트라 담는 모든 값이 곧 공개 정보다.
- Actuator·JPA·`spring-security-test` 의존은 아직 없다. 추가는 그것을 필요로 하는 스토리의 SPEC이 목적과 함께 승인한다. Spring Modulith·PostgreSQL·Flyway·Testcontainers는 `spec-1-3-identity-ownership.md`가, Spring Security OAuth2 Client·Spring Session JDBC는 `spec-1-4-google-sign-in.md`가 승인해 들어왔다.
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
| `deploy/compose/verify-same-origin.sh [--keep-stack]` | 마이그레이션 1회 → 기동 → I/O 표 전 행 확인 → identity 미노출 확인 → 인증 표면 확인 → 스텁 제공자 성공 왕복 제공자별 1회(왕복들이 서로 다른 계정·세션을 만드는지 확인하고 끝에서 만든 행을 지워 원래 상태로 되돌린다) → 재인증 왕복 → **연결 왕복**(연결 상태 조회 → 시작의 보호 다섯 → 무장 → 인가 → 콜백 → 매핑 2행·허가 1회 소비·세션 재회전 → 남의 식별자로 거부 탐침) → 허용 목록 경로 확인 → 백엔드 정지 후 502/504 확인 → 로그 누출 검사(왕복의 `code`·`state`와 허가 참조 포함) → 정리. `--keep-stack`이면 성공 시 백엔드를 다시 띄워 건강한 스택을 남긴다 |
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

`PLAYWRIGHT_BASE_URL`이 있으면 `playwright.config.ts`가 자체 `preview` 서버를 띄우지 않는다. 이 분기가 없으면 통합 실행이 Nginx가 아니라 자기가 띄운 preview를 검증하면서도 green이 난다. 두 경우는 `frontend/playwright.config.test.ts`가 고정한다. 주입된 origin에서만 도는 시험이 둘 있다 — `same-origin-csp.spec.ts`(진입점 CSP)와 `auth-entry.spec.ts`의 `스텁 제공자 성공 왕복`(제공자별로 버튼 → 스텁 동의 → 온보딩 → 세션 쿠키)이다. preview에는 `/api`도 스텁도 없어 검증할 대상 자체가 없다.

### 구조와 규칙

| 경로 | 역할 |
|---|---|
| `deploy/nginx/bokrilog.conf` | 루트 정적 제공, `/api` 프록시, 알려진 내부 경로 허용 목록, 공통 오류 JSON, 보안 응답 헤더, 쿼리 없는 access log |
| `deploy/compose/compose.yaml` | 네 서비스. `postgres:18.6-alpine` → 백엔드 → `nginx:1.30.4-alpine`이 healthcheck로 이어지고, `migrate`는 `profiles`에 가려 명시 실행만 된다. 산출물과 설정을 읽기 전용 마운트 |
| `deploy/compose/verify-same-origin.sh` | 로컬·CI 공용 검증 명령 |

- 개발·CI compose에 비밀번호를 두지 않는다. `POSTGRES_HOST_AUTH_METHOD: trust`를 쓰고 포트를 게시하지 않는다. 가짜 비밀번호 리터럴을 커밋하면 gitleaks 규칙을 완화하고 싶어지고 그 완화가 남는다. production 자격정보는 Story 3.8이 소유한다.
- 제공자 자격정보도 두지 않는다. compose는 `BOKRILOG_GOOGLE_CLIENT_ID`·`BOKRILOG_GOOGLE_CLIENT_SECRET`·`BOKRILOG_NAVER_CLIENT_ID`·`BOKRILOG_NAVER_CLIENT_SECRET`·`BOKRILOG_KAKAO_CLIENT_ID`·`BOKRILOG_KAKAO_CLIENT_SECRET`를 참조로만 넘기고 값을 적지 않는다. 대신 백엔드가 `SPRING_PROFILES_ACTIVE: dev-oidc`로 떠서 `/api/dev-oidc/<제공자>/**`의 local OIDC 스텁을 제공자 자리에 세우고, `verify-same-origin.sh`가 인가 시작부터 세션 개설까지의 성공 왕복을 **제공자마다** 지난다. 스텁의 client id·secret은 기동 시점에 만들어지므로 설정 파일에도 compose에도 리터럴이 없다. 스텁과 실제 제공자가 함께 구성되면 백엔드가 기동을 거부하며, 자격정보가 없는 기본 프로필의 fail-closed는 `DevOidcStubProfileTest`가 지킨다.
- 스텁이 시험 소스가 아니라 `main`에 있는 이유는 브라우저다. Authorization Code 흐름의 인가 요청은 브라우저가 보내고 code 교환·JWKS 조회는 백엔드가 보내므로, 성공 경로를 브라우저 층에서 검증하려면 한 스텁이 양쪽에서 도달 가능해야 한다. `/api` 아래에 두면 기존 프록시 블록이 그대로 받아 새 서비스·새 게시 포트·CSP 확장이 하나도 필요 없다. 브라우저가 보는 주소(`BOKRILOG_PUBLIC_BASE_URL`)와 백엔드가 자기 스텁에 닿는 주소(`BOKRILOG_DEV_OIDC_INTERNAL_BASE_URL`)는 다른 값이다 — 공개 origin은 compose 네트워크 안에서 해석되지 않는다.
- 리다이렉트의 `Location`은 origin-상대 경로다(`server.tomcat.use-relative-redirects: true`). 기본값에서는 Tomcat이 요청의 `Host`로 절대 URI를 만들고, `proxy_set_header Host $host`가 포트를 잃은 값을 넘기므로 로그인 성공 뒤의 이동이 `http://127.0.0.1/onboarding`(80번 포트)으로 끊긴다. `$http_host`나 `X-Forwarded-*`로 바꾸는 길은 클라이언트가 제어하는 헤더를 목적지 계산에 넣는다.
- access log에 쿼리 문자열을 남기지 않는다. `log_format`이 `$request` 대신 `$request_method $request_path $server_protocol`을 쓰고 `map`이 `?` 앞부분만 잘라 낸다. `$request`를 그대로 쓰면 OIDC 콜백의 `?code=…&state=…`가 로그에 실린다. `$uri`로 대신하지 않는 이유는 그 변수가 내부 redirect 뒤의 값이라 `/onboarding` 요청이 `/index.html`로 기록되기 때문이다.
- 바깥에 열리는 포트는 Nginx 하나(`127.0.0.1:18080`)다. 백엔드와 데이터베이스 포트는 게시하지 않는다. 게시하는 순간 "동일 출처"는 구성이 아니라 관례가 된다. 호스트 포트는 `4183`으로 시작하지 않는 값을 쓴다. Playwright의 origin 비교가 포트 접두사에 취약했던 이력이 있고(`4183` 대 `41830`) preview 기본 포트와도 겹치지 않아야 한다.
- `/api`만 프록시한다. 나머지 경로는 정적 파일이거나 404이며 어떤 경로도 외부 주소로 프록시하지 않는다.
- 와일드카드 SPA 폴백(`try_files … /index.html`)을 두지 않는다. 폴백이 있으면 존재하지 않는 경로가 200 + 진입 화면으로 응답해 "없음"을 숨긴다. 대신 알려진 내부 경로만 여는 허용 목록 location(`^/(onboarding|dashboard|settings)$`)이 있고, 그 집합은 위 `## 프런트엔드`가 적은 다섯 자리와 같아야 한다. 라우트를 더할 때는 다섯 곳을 함께 고친다.
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
| `backend-quality` | JDK 25 준비 → Gradle `build`(컴파일·Modulith 경계 검사·PostgreSQL Testcontainers 시험·jar) | 25분 |
| `same-origin-integration` | 두 품질 job의 산출물로 동일 출처 검증(`--keep-stack`) → 같은 스택에서 Story 1.1 회귀 | 17분 |
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
