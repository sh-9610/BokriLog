---
title: '프런트엔드 lint·stylelint·format 정책과 required gate 승격'
type: 'chore'
created: '2026-09-05'
status: 'done'
review_loop_iteration: 0
baseline_commit: '5a5e617495956200df31496e86241a0ebf09ee4c'
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-BokriLog-2026-08-24/CI-PLAN.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/DESIGN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `DEVELOPMENT.md`가 "색·크기 리터럴을 컴포넌트 CSS에 쓰지 않는다"와 import 순서를 규칙으로 두지만 검사 수단이 없어 Story 1.1 커밋에서 네 곳이 규칙을 벗어났다(`deferred-work.md` 12). CI-PLAN §6·§8은 Story 1.2 완료 전에 ESLint·stylelint·format check를 합의하고 `ci-required`의 필수 단계로 승격하도록 정했다.

**Approach:** `frontend/`에 ESLint(타입 인지)·stylelint(토큰 참조 강제)·Prettier를 도입하고, 규칙이 실제로 위반을 잡는지 Vitest 정책 시험으로 고정한 뒤 `ci.yml`의 typecheck 다음 단계로 넣는다. 기존 코드는 규칙에 맞추되 렌더 결과와 동작은 바꾸지 않는다.

## Boundaries & Constraints

**Always:**
- 세 검사는 `ci-required` 안에서 typecheck 뒤, build 앞에 실행한다. 이름이 고정된 job 하나에 들어가므로 별도 보호 규칙 변경 없이 필수가 된다.
- stylelint는 `DESIGN.md`가 소유하는 네 범주(색, 타이포그래피, 모서리, 간격)에서 리터럴을 금지하고 `tokens.css`의 `var(--…)`만 허용한다. `tokens.css` 자체는 이 규칙에서 제외한다.
- `DESIGN.md`에 토큰이 없는 범주(모션 duration·easing, `z-index`, 구조적 `1px` 테두리 두께)는 규칙 대상이 아니다. 규칙 밖 범주에 새 규칙을 만들지 않는다.
- 인라인 예외는 이유가 있는 `disable` 주석만 허용한다. 이유 없는 disable, 불필요한 disable, 범위가 틀린 disable은 stylelint·ESLint 양쪽에서 오류다.
- 서식은 기존 코드의 관례를 그대로 고정한다. 세미콜론 없음, 단일 인용부호, 후행 쉼표, 2칸 들여쓰기, LF. `printWidth`는 100이며 문자열은 접지 않는다.
- ESLint 10과 `typescript-eslint`의 타입 인지 strict·stylistic 규칙, React hooks·refresh, import 정렬, 그리고 시험 파일별 플러그인(Vitest, Testing Library, Playwright)을 쓴다. `eslint-config-prettier`가 서식 규칙을 끈다. 정적 접근성 검사(`eslint-plugin-jsx-a11y`)는 ESLint 10 peer 미지원으로 이번 범위에서 제외하고 `deferred-work.md`에 남긴다. 렌더 결과의 접근성은 기존 E2E axe 검사가 담당한다.
- 정책 시험은 규칙 설정을 문자열로 읽지 않고 ESLint·stylelint를 실제로 실행해 위반 fixture가 오류를 내고 준수 fixture가 통과함을 단정한다.
- 외부 action·의존은 lockfile로 고정하고 `npm ci`로 설치한다. 새 devDependency는 `^` 범위로 `package.json`에 적는다.
- `.github/scripts/test-ci-scripts.sh`가 단정하는 `ci.yml` 계약(문서-only 게이트 수 등)을 새 단계에 맞게 함께 갱신한다.

**Ask First:**
- 자동 수정(`--fix`, `prettier --write`)이 서식·import 순서·선택자 표기 이외의 것을 바꾸려 하면 멈춘다. 렌더 결과, 계산 스타일, 시험 단정에 영향을 주는 CSS·TSX 변경은 사람이 결정한다.
- `stylelint-config-standard`·typescript-eslint 권장 규칙이 기존 제품 코드와 충돌해 규칙을 끄거나 낮춰야 하면, 그 규칙과 이유를 목록으로 보고하고 승인을 받는다. 임의로 `off`하지 않는다.
- 정책 시험이 jsdom 환경에서 ESLint·stylelint API를 실행하지 못하면 대안을 제시하고 결정을 받는다.

**Never:**
- Biome·oxlint 등 다른 도구로 대체하지 않는다. 합의 대상은 CI-PLAN이 명시한 세 도구다.
- pre-commit·husky 훅을 넣지 않는다. 게이트는 CI다. 로컬 실행은 `npm run` 명령으로 제공한다.
- `DESIGN.md`·`tokens.css`의 값이나 이름을 바꾸지 않는다. 없는 토큰을 만들어 리터럴을 대체하지 않는다.
- `_bmad-output/**`, 저장소 루트 `*.md`, `.github/**`, `scripts/**`는 검사 대상이 아니다. 범위는 `frontend/`다.
- backend·Nginx·Java 검사를 미리 만들지 않는다(CI-PLAN §8 Story 1.2 범위).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 리터럴 색 | `*.module.css`에 `color: #17211b` | stylelint 오류. 메시지가 `tokens.css` 참조를 안내 | `ci-required` 실패 |
| 토큰 참조 | `color: var(--color-ink-primary)` | 통과 | N/A |
| 예외 범주 리터럴 | `z-index: 1`, `transition: … 120ms ease-out`, `border: 1px solid var(--color-border-default)` | 통과. 규칙 대상 속성이 아님 | N/A |
| 이유 없는 disable | `/* stylelint-disable-next-line … */` 뒤 설명 없음 | 오류(`reportDescriptionlessDisables`) | 실패 |
| 불필요한 disable | 위반이 없는 곳의 disable 주석 | 오류(`reportNeedlessDisables`) | 실패 |
| import 순서 | 상대 경로 import가 외부 패키지 앞에 옴 | ESLint 오류, `--fix`로 정렬 가능 | 실패 |
| 집중 시험 잔존 | `e2e`에 `test.only`, Vitest에 `it.only` | ESLint 오류 | 실패 |
| 서식 불일치 | 세미콜론이 있는 파일 | `prettier --check` 실패 | 실패 |
| 문서-only PR | `_bmad-output/**`만 변경 | 세 검사 모두 건너뜀. 기존 분류와 동일 | N/A |
| `tokens.css` | 리터럴 정의 | 통과. 규칙 제외 파일 | N/A |

</frozen-after-approval>

## Code Map

- `frontend/package.json:10-17` -- scripts. `lint`, `lint:css`, `format`, `format:check`를 추가. devDependencies에 ESLint 10·`@eslint/js`·`typescript-eslint` 8·`eslint-plugin-react-hooks` 7·`eslint-plugin-react-refresh`·`eslint-plugin-perfectionist`(import 정렬)·`@eslint-community/eslint-plugin-eslint-comments`(이유 없는 disable을 오류로)·`jiti`(ESLint 10.10이 `.ts` 설정을 읽는 로더)·`@vitest/eslint-plugin`·`eslint-plugin-testing-library`·`eslint-plugin-playwright`·`eslint-config-prettier`·`globals`·Prettier 3·stylelint 17·`stylelint-config-standard`·`stylelint-declaration-strict-value`
- `frontend/eslint.config.ts` -- 신규. flat config. `projectService`로 `tsconfig.json`·`tsconfig.node.json` 양쪽을 타입 인지. 파일군별 override: `src/**/*.test.tsx`(Vitest·Testing Library), `playwright.config.test.ts`(Vitest), `e2e/**`(Playwright). `reportUnusedDisableDirectives: 'error'`
- `frontend/stylelint.config.js` -- 신규. `stylelint-config-standard` 확장. `scale-unlimited/declaration-strict-value`를 `/color$/`·`fill`·`stroke`·`font`(축약형 직접, `bold 14px/1.5` 구멍 차단)·`/^font-(family|size|weight)$/`·`line-height`·`letter-spacing`·`/radius$/`·`/^(padding|margin|gap|inset)/`·`row-gap`·`column-gap`·`/^(top|right|bottom|left)$/`·`/^scroll-(padding|margin)/`·`outline-offset`·`outline-width`에 적용하고 `expandShorthand`로 `border`·`outline`·`background`·`font`·`padding`·`margin`을 펼친다. `ignoreValues`: `0`·`auto`·`inherit`·`initial|unset|revert|revert-layer|normal`·`currentcolor`(대소문자 무시)·`transparent`·`none`·`100%`·forced-colors 시스템 색 이름(대소문자 무시)·`var(--`를 포함한 `calc|max|min|clamp` 함수(토큰과 리터럴이 섞인 식도 통과하는 의도된 경계). 논리 border 축약형(`/^border-(inline|block)(-(start|end))?$/`)은 `expandShorthand`가 펼치지 못해 `declaration-property-value-disallowed-list`로 색 리터럴 패턴(hex, `rgb|hsl|hwb|lab|lch|oklab|oklch|color(`)만 금지하고 메시지가 longhand(`border-inline-color`)를 안내. `box-shadow`·`text-shadow`(Elevation)는 대상 밖. `*.module.css`는 `selector-class-pattern` camelCase. `media-feature-range-notation`은 기존 `min-width` 표기를 유지(`prefix`). `reportDescriptionlessDisables`·`reportNeedlessDisables`·`reportInvalidScopeDisables` 모두 true. `src/styles/tokens.css` override: strict-value·`custom-property-empty-line-before`(토큰 그룹 사이 빈 줄은 DESIGN.md 범주 구분)·`color-hex-length`·`value-keyword-case`(값 표기는 DESIGN.md 그대로) 해제, 나머지 표준 규칙은 적용. 파일 예외는 인라인 disable 없이 이 override 한 곳에 둔다
- `frontend/.prettierrc.json`, `frontend/.prettierignore` -- 신규. `semi: false`, `singleQuote: true`, `trailingComma: 'all'`, `printWidth: 100`. ignore: `dist`, `playwright-report`, `test-results`, `package-lock.json`
- `frontend/lint-policy.test.ts` -- 신규. I/O 표의 fixture를 ESLint `lintText`, stylelint `lint({ code, codeFilename })`, Prettier `check`·`getFileInfo`로 실행해 단정하고, `lint:css` glob·`eslint .`의 선택 범위도 명령 층에서 고정(49개). `playwright.config.test.ts`와 같은 위치·이유(대상이 `src/` 밖)
- `frontend/vite.config.ts:23` -- `test.include`에 `lint-policy.test.ts` 추가
- `frontend/tsconfig.node.json` -- include에 `eslint.config.ts`·`lint-policy.test.ts` 추가. `projectService`는 루트 `*.ts`를 `allowDefaultProject`+`defaultProject: tsconfig.node.json`으로, `e2e/**`는 신규 `frontend/e2e/tsconfig.json`(상속만 하는 위치 표지)으로 타입 인지한다
- `frontend/src/**`, `frontend/e2e/**`, `frontend/*.ts`, `frontend/index.html` -- 기존 코드. `--fix`·`prettier --write` 결과 반영. 기존 예외 리터럴 3곳(`EntryScreen.module.css`의 `z-index`, `transition`, `1px`)은 규칙 대상 밖이므로 disable 주석 불필요. 사람 승인(2026-09-05)으로 반영한 비서식 변경: `padding-inline-start/end` 3쌍을 `padding-inline` 축약형으로 병합, `restrict-template-expressions`(number 금지) 7곳 `String()`, `EntryScreen.tsx` className 템플릿 → `[styles.section, styles.sectionNotice].join(' ')`(`noUncheckedIndexedAccess`로 CSS Module 값이 `string | undefined`), `textContent ?? ''` 제거 3곳(`no-unnecessary-condition`, lib.dom에서 `Element.textContent`는 `string`), `setup.ts`의 `as typeof fetch`·`as XMLHttpRequest['open']` 캐스트 2곳 제거(`no-unnecessary-type-assertion`), 이유 있는 disable 7건(Playwright `no-networkidle` 3·`no-conditional-in-test` 2, Testing Library `no-node-access` 1, `property-no-vendor-prefix` 1). tokens.css의 `color-hex-length`·`value-keyword-case` 예외는 인라인 disable이 아니라 stylelint override에 둔다
- `.github/workflows/ci.yml` -- `타입 검사` 뒤에 `ESLint`, `stylelint`, `Prettier 검사` 3단계 추가. 각각 `docs-only != 'true'` 조건
- `.github/scripts/test-ci-scripts.sh` -- `ci.yml` 단정 갱신. `docs-only 게이트를 단 단계는 7개` 단정을 10개로, `gated_step` 목록에 새 단계 이름 3개 추가
- `DEVELOPMENT.md` §프런트엔드 -- 명령 표에 4개 추가. 예외 규칙(이유 있는 disable만, 예외 범주 목록) 서술. `CLAUDE.md` 명령 블록에 `lint`·`lint:css`·`format:check` 추가
- `CI-PLAN.md` §6 -- 실행 순서 번호 목록에 typecheck(7) 다음으로 ESLint·stylelint·Prettier 단계를 넣고 이후 번호를 밀어 정렬. "ESLint·stylelint·formatter 설정과 명령은 없으므로…" 문단(현재 237행)을 적용 사실과 날짜로 갱신. §8 표의 "Story 1.2 완료 전" 행에 적용 표기
- `_bmad-output/implementation-artifacts/deferred-work-resolutions.md` 항목 12 -- 해소 기록(병합·CI green 뒤)

## Tasks & Acceptance

**Execution:**
- [x] `frontend/package.json` -- devDependencies·scripts 추가, `npm install`로 lockfile 갱신 -- 도구 버전을 lockfile이 소유한다
- [x] `frontend/.prettierrc.json`, `frontend/.prettierignore` -- 서식 정책 -- 기존 관례를 그대로 고정한다
- [x] `frontend/eslint.config.ts` -- flat config 작성 -- 파일군별 플러그인, import 정렬, disable 감사
- [x] `frontend/stylelint.config.js` -- 토큰 강제 규칙과 예외 -- `DESIGN.md` 네 범주만, 이유 있는 disable만
- [x] `frontend/tsconfig.node.json`, `frontend/vite.config.ts` -- 새 파일 포함 -- 타입 검사와 시험 수집 범위
- [x] `frontend/lint-policy.test.ts` -- I/O 표 fixture 시험 -- 규칙이 실제로 잡는지 고정
- [x] `frontend/**` 기존 코드 -- `prettier --write`, `eslint --fix`, `stylelint --fix` 적용 후 잔여 위반 검토 -- Ask First 조건에 걸리면 멈춘다
- [x] `.github/workflows/ci.yml`, `.github/scripts/test-ci-scripts.sh` -- 3단계 추가와 계약 시험 갱신 -- required gate 승격
- [x] `DEVELOPMENT.md`, `CLAUDE.md`, `CI-PLAN.md` -- 명령·예외 규칙·적용 사실 -- 문서와 구현 일치
- [x] `_bmad-output/implementation-artifacts/deferred-work.md` -- `eslint-plugin-jsx-a11y` 도입 항목 append -- ESLint 10 peer 지원 시 별도 의존 갱신 PR(CI-PLAN §11)로 추가한다
- [x] [원격] `deferred-work-resolutions.md` 항목 12 -- 해소 기록 -- 병합 후 `ci-required` green 확인 뒤에만 -- 2026-09-05 기록. PR #6 병합(e0452a5), develop push green, PR #7로 차단 확인 뒤 작성. 항목 17·18도 원장에 미해소로 추가

**Acceptance Criteria:**
- Given 기존 `develop`의 frontend 코드, when 세 검사를 실행하면, then 모두 통과하고 `vite build`·Vitest·E2E 결과가 변경 전과 같다.
- Given I/O 표의 위반 fixture, when `lint-policy.test.ts`가 실행되면, then 각 fixture가 기대한 규칙 ID로 오류를 낸다.
- Given `frontend/**`를 바꾼 PR, when `ci-required`가 돌면, then ESLint·stylelint·Prettier 단계가 typecheck 뒤 build 앞에서 실행된다.
- Given 문서-only PR, when `ci-required`가 돌면, then 세 단계가 건너뛰어지고 `test-ci-scripts.sh`의 게이트 수 단정이 통과한다.
- Given 이유 없는 `eslint-disable`·`stylelint-disable`, when 검사가 돌면, then 오류로 실패한다.

## Spec Change Log

## Design Notes

**strict-value의 함수 허용은 토큰을 포함할 때만.** `ignoreFunctions: true`는 `rgb()`·`hsl()`도 통과시켜 색 규칙에 구멍을 낸다. 대신 `ignoreValues`에 `/^(calc|max|min|clamp)\(.*var\(--/`를 두어 토큰을 인용하는 계산식만 허용한다. `EntryScreen.module.css`의 `max(var(--space-page-mobile), env(...))`, `calc((var(--type-body-font-size) * …)`가 이 형태다.

**stylelint 예외 범주는 속성 목록으로 표현한다.** `z-index`, `transition`, `border-width`를 규칙 대상에서 빼면 `DEVELOPMENT.md`의 "토큰이 없는 범주" 예외가 주석 없이도 성립한다. 반대로 대상 속성에서 리터럴을 쓰려면 반드시 이유 있는 disable이 필요하다. 예외가 늘어나면 규칙이 아니라 `DESIGN.md`에 토큰을 추가하는 쪽으로 압력이 걸린다.

**import 정렬은 perfectionist 하나만.** `import-x`의 resolver 설정 없이 현재 관례(type import → 외부 → 빈 줄 → 상대 → side-effect CSS)를 고정한다. 정렬은 `--fix`로 기계가 한다.

**정책 시험 fixture 예시:**
```css
/* 오류: scale-unlimited/declaration-strict-value */
.a { color: #17211b; }
/* 통과 */
.b { color: var(--color-ink-primary); border: 1px solid var(--color-border-default); z-index: 1; }
```

## Verification

**Commands:**
- `npm --prefix frontend run lint` -- expected: 오류 0
- `npm --prefix frontend run lint:css` -- expected: 오류 0
- `npm --prefix frontend run format:check` -- expected: 모든 파일 일치
- `npm --prefix frontend run typecheck` -- expected: 통과(`eslint.config.ts`·`lint-policy.test.ts` 포함)
- `npm --prefix frontend run test` -- expected: 기존 24개 + 정책 시험 통과
- `npm --prefix frontend run build && CI=1 npm --prefix frontend run test:e2e:run` -- expected: 13개 통과, 변경 전과 동일
- `bash .github/scripts/test-ci-scripts.sh` -- expected: 전부 통과(게이트 수 갱신 반영)

**Manual checks:**
- PR의 `ci-required` 로그에서 세 단계가 `타입 검사` 뒤 `프로덕션 빌드` 앞에 실행됐는지 확인한다.
- 일부러 리터럴 색을 넣은 커밋을 PR에 올려 `ci-required`가 실패하고 병합 버튼이 잠기는지 확인한다(spec-ci-foundation의 미확인 수동 항목을 겸한다).

## Suggested Review Order

**토큰 강제 규칙 (핵심 정책)**

- 진입점. 네 범주의 대상 속성 목록과 예외 범주가 속성 목록으로 표현된 곳
  [`stylelint.config.js:58`](../../frontend/stylelint.config.js#L58)
- 토큰을 인용하는 계산식만 허용하는 정규식. `rgb()` 구멍을 막는 대신 열어 둔 경계
  [`stylelint.config.js:41`](../../frontend/stylelint.config.js#L41)
- expandShorthand가 펼치지 못하는 논리 border 축약형의 색 리터럴 금지
  [`stylelint.config.js:100`](../../frontend/stylelint.config.js#L100)
- tokens.css만 예외. 리터럴이 정의되는 유일한 장소
  [`stylelint.config.js:124`](../../frontend/stylelint.config.js#L124)
- 이유 없는·불필요한·범위 틀린 disable을 오류로 만드는 세 옵션
  [`stylelint.config.js:54`](../../frontend/stylelint.config.js#L54)

**ESLint 정책**

- 타입 인지 strict·stylistic과 projectService. 루트 파일·가상 fixture의 기본 프로젝트 처리
  [`eslint.config.ts:61`](../../frontend/eslint.config.ts#L61)
- disable 주석 감사. 코어가 못 잡는 "이유 없는 disable"을 플러그인이 잡는다
  [`eslint.config.ts:76`](../../frontend/eslint.config.ts#L76)
- 파일군별 플러그인. 앱·시험·E2E가 다른 규칙을 받는다
  [`eslint.config.ts:108`](../../frontend/eslint.config.ts#L108)
- `.js` 설정 파일도 검사 대상. 리뷰에서 발견된 공백을 막는다
  [`eslint.config.ts:94`](../../frontend/eslint.config.ts#L94)

**정책 시험 (규칙이 실제로 잡는지)**

- ESLint fixture. 타입 인지 규칙과 hooks 규칙이 발화하는지가 설정 묶음 전체를 고정한다
  [`lint-policy.test.ts:167`](../../frontend/lint-policy.test.ts#L167)
- stylelint fixture. I/O 표의 행과 리뷰가 찾은 경계(font 축약형, radius longhand, 혼합값)
  [`lint-policy.test.ts:303`](../../frontend/lint-policy.test.ts#L303)
- 명령 층 시험. glob·ignore가 바뀌어 검사 대상이 조용히 줄어드는 것을 막는다
  [`lint-policy.test.ts:637`](../../frontend/lint-policy.test.ts#L637)
- Prettier fixture와 `.prettierignore` 검증
  [`lint-policy.test.ts:600`](../../frontend/lint-policy.test.ts#L600)

**CI 승격**

- 세 단계가 타입 검사 뒤, 빌드 앞. 같은 `ci-required` job이라 보호 규칙 변경 없이 필수
  [`ci.yml:171`](../../.github/workflows/ci.yml#L171)
- 게이트 수 7→10, 단계 순서와 npm 명령을 행 단위로 단정
  [`test-ci-scripts.sh:620`](../../.github/scripts/test-ci-scripts.sh#L620)
- CI가 부르는 명령의 정의. 경고도 실패다
  [`package.json:15`](../../frontend/package.json#L15)

**기존 코드에 반영된 비서식 변경 (사람 승인)**

- `padding-inline-start/end` 3쌍을 축약형으로 병합. 계산 스타일 동일, E2E 13개로 확인
  [`EntryScreen.module.css:20`](../../frontend/src/screens/EntryScreen.module.css#L20)
- `restrict-template-expressions`: 템플릿의 number를 `String()`으로. 금융값 무심코 문자열화 방지
  [`EntryScreen.tsx:48`](../../frontend/src/screens/EntryScreen.tsx#L48)
- 이유 있는 Playwright disable. `networkidle`은 "요청 0건" 단정의 전제
  [`entry-screen.spec.ts:246`](../../frontend/e2e/entry-screen.spec.ts#L246)

**문서**

- 예외 범주와 disable 규칙의 사람용 정본
  [`DEVELOPMENT.md:108`](../../DEVELOPMENT.md#L108)
- CI-PLAN §6 실행 순서 8~10번과 적용 사실
  [`CI-PLAN.md:212`](../planning-artifacts/architecture/architecture-BokriLog-2026-08-24/CI-PLAN.md#L212)
- jsx-a11y 제외와 noscript 폴백 표류 시험을 미룬 기록
  [`deferred-work.md:60`](deferred-work.md#L60)
