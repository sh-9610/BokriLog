---
title: 'Story 1.1 — 서비스 이용 범위와 인증 경계를 확인하는 진입 화면'
type: 'feature'
created: '2026-09-03'
status: 'done'
review_loop_iteration: 0
baseline_commit: '734e11a3336145e5c52c1922f0e65a135b1bf138'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-BokriLog-2026-08-23/DESIGN.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 저장소에 제품 코드가 없어, 방문자가 계정을 만들기 전에 BokriLog가 무엇을 하고 무엇을 하지 않는지 확인할 화면이 없다. Epic 1의 이후 스토리는 모두 이 프런트엔드 기반 위에서만 진행할 수 있다.

**Approach:** `frontend/`에 React 19.2.x + Vite 8.2.x + TypeScript strict 정적 SPA를 세우고 `DESIGN.md` 토큰을 CSS 커스텀 속성 단일 출처로 구현한다. 로그인 없이 열리는 단일 진입 화면이 이용 범위·인증 경계·비추천 고지를 제공하며, 외부 요청과 브라우저 저장소 쓰기가 0건임을 자동 시험으로 고정한다.

## Boundaries & Constraints

**Always:**
- 스택은 `ARCHITECTURE-SPINE.md` Stack 표의 허용 계열(React 19.2.x, Vite 8.2.x)을 따르고 위치는 Structural Seed의 `frontend/`다. 실제 버전은 lockfile이 소유한다.
- 문구는 한국어이며 `EXPERIENCE.md` Voice and Tone을 따른다. `DESIGN.md` frontmatter가 토큰 정본이고, 색·크기 리터럴을 컴포넌트에 직접 쓰지 않는다. 라이트 모드만.
- WCAG 2.2 AA. 전역 `focus-visible`은 `focus-ring` 2px 두께·2px 간격, 상호작용 목표는 최소 44×44px.
- `복리 계획 비교`와 "투자성과·계좌 수익률·투자 추천이 아닙니다" 고지를 인접 배치한다.
- 백엔드·DB·제공자 자격정보 없이 빌드·실행·시험이 통과한다. 신규 파일은 LF(`.gitattributes`).

**Ask First:** Stack 표에 없는 **런타임** 의존성 추가(개발·시험 전용 devDependency 제외). Story 1.2 이후 범위 선취(`app-shell` 전역 탐색, 라우터, `/api` 호출, Nginx, 동작하는 소셜 버튼). 다크 모드 또는 웹폰트.

**Never:** 소셜 인증 실행·제공자 SDK·OAuth 요청. 백엔드·DB·Nginx·배포 구성. 광고 SDK·분석·트래킹·오류 리포팅. `localStorage`·`sessionStorage`·쿠키·IndexedDB 쓰기. 외부 폰트·CDN 등 문서 밖 네트워크 요청. 금융값 표시, 실시간·정확 보장 표현, 수익 축하·손실 공포·스트릭·배지.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| 이용 범위 | 비로그인 방문자가 진입 화면을 연다 | 읽기 전용 Bybit UTA 복리 계획 비교 서비스 설명과 "거래 실행·투자 추천을 제공하지 않습니다" 안내가 렌더된다 | N/A |
| 인증 경계 | 같은 화면 | "Google·네이버·카카오는 본인 확인에만 사용하며 Bybit 자격정보·금융 데이터·계획은 공유하지 않습니다" 문구가 렌더된다 | N/A |
| 외부 요청 0건 | 렌더 완료 | 문서·번들 외 네트워크 요청이 없다 | 요청 관측 시 시험 실패 |
| 저장소 쓰기 0건 | 렌더 완료 | `localStorage`·`sessionStorage`·`document.cookie` 항목이 없다 | 항목 생성 시 시험 실패 |
| 제목 구조 | 렌더 완료 | `h1` 정확히 1개, heading 레벨 건너뜀 없음 | N/A |
| 키보드 탐색 | `Tab` 반복 | focusable 요소에 문서 순서로 도달하고 `focus-visible` 외곽선이 보인다 | N/A |
| 좁은 뷰포트 | 320×640 CSS px | 가로 스크롤 없음, 텍스트 잘림·겹침 없음 | N/A |
| 확대 | 1280px 기준 200%·400% | 400%에서 양방향 스크롤 없이 전체 내용 확인, 200%에서 텍스트 잘림 없음 | N/A |
| 모션 감소 | `prefers-reduced-motion: reduce` | 전환·애니메이션이 제거되고 정적 표현만 남는다 | N/A |

</frozen-after-approval>

## Code Map

- 제품 코드 없음. 추적 파일은 `.gitattributes`, `.gitignore`, `CLAUDE.md`, `DEVELOPMENT.md`, `scripts/check-bmad-env.sh`, `uv.toml`뿐이라 충돌 지점이 없다. `frontend/`는 신규다.
- `.gitignore` -- `node_modules/`·`dist/`·`build/`를 이미 제외(주석이 Story 1.1~1.2를 명시). Playwright 산출물 경로만 추가한다.
- `ux-designs/ux-BokriLog-2026-08-23/DESIGN.md` -- **읽기 전용 정본.** frontmatter가 토큰, Colors 절이 검증된 대비값(`ink-primary`/`surface-base` 15.52:1, `ink-muted`/`surface-raised` 5.05:1, `focus-ring`/흰 바탕 8.14:1)을 준다. `border-default`는 장식 구분 전용이라 의미 있는 경계에 쓰지 않는다.
- 같은 폴더 `mockups/key-social-login.html` -- **읽기 전용 참조.** 톤·카피만 참고한다. 목업의 소셜 버튼은 Story 1.4 범위이므로 옮기지 않는다.
- 같은 폴더 `EXPERIENCE.md` -- **읽기 전용.** Voice and Tone(금지 표현), Accessibility Floor, Responsive & Platform(320px·400% 계약).
- `planning-artifacts/epics.md` Story 1.1 절 -- **읽기 전용.** AC 정본.
- `DEVELOPMENT.md` -- 갱신 대상. `## BMAD 명령 실행 원칙` 앞에 프런트엔드 절차 절을 넣는다.
- `CLAUDE.md` -- 갱신 대상. `## 현재 진행 지점`이 "제품 코드는 아직 없다"라고 서술해 사실과 어긋나게 된다.

## Tasks & Acceptance

**Execution:**
- [x] `frontend/package.json`, `frontend/tsconfig*.json`, `frontend/vite.config.ts`, `frontend/playwright.config.ts` -- 의존성·strict TS·Vitest(jsdom)·Playwright(Chromium, `preview` 자동 기동)를 선언하고 `dev`/`build`/`preview`/`typecheck`/`test`/`test:e2e` 스크립트를 정의한다 -- AC-4의 재현 가능한 절차를 한 진입점으로 고정
- [x] `frontend/index.html` -- `lang="ko"`, viewport meta, 문서 제목. 외부 리소스 참조 없음
- [x] `frontend/src/styles/tokens.css`, `frontend/src/styles/global.css` -- `DESIGN.md` frontmatter 값을 CSS 커스텀 속성으로 옮기고 reset·기본 타이포·전역 `:focus-visible`·`prefers-reduced-motion` 규칙을 둔다 -- UX-DR1·UX-DR19·UX-DR21 바닥선
- [x] `frontend/src/content/entry.ts` -- 진입 화면 한국어 카피를 상수로 선언 -- 화면과 시험이 같은 문자열을 참조해 문구 표류를 막는다
- [x] `frontend/src/screens/EntryScreen.tsx`, `EntryScreen.module.css` -- 이용 범위·인증 경계·비추천 고지를 semantic 마크업으로 렌더 -- AC-1·AC-2·AC-3
- [x] `frontend/src/main.tsx`, `frontend/src/App.tsx` -- 진입 화면 마운트. 라우터 없음
- [x] `frontend/src/test/setup.ts` -- Testing Library 정리와 `fetch`/`XMLHttpRequest` 감시 훅 -- 외부 요청 0건을 관측 가능하게 한다
- [x] `frontend/src/screens/EntryScreen.test.tsx` -- I/O 표의 렌더·문구·제목 구조·요청·저장소 시나리오를 단위 시험 -- AC-1·AC-2
- [x] `frontend/e2e/entry-screen.spec.ts` -- axe 검사, 키보드 탐색, 320px·200%·400% 리플로, 모션 감소, 요청 0건을 실제 Chromium에서 검증 -- AC-3
- [x] `.gitignore` -- `frontend/test-results/`, `frontend/playwright-report/` 추가 -- 시험 산출물 커밋 방지
- [x] `DEVELOPMENT.md` -- 프런트엔드 준비·빌드·실행·시험 절차 절 추가 -- AC-4의 "문서화된 절차"
- [x] `CLAUDE.md` -- 현재 진행 지점과 도구 실행 절을 제품 코드 도입 사실에 맞게 갱신

**Acceptance Criteria:**
- Given 깨끗한 체크아웃과 Node 24, when `npm --prefix frontend ci && npm --prefix frontend run build`를 실행하면, then 백엔드·DB·제공자 자격정보 없이 `frontend/dist/`가 생성된다
- Given 빌드 산출물, when `npm --prefix frontend run preview`로 실행하면, then 진입 화면이 독립적으로 표시된다
- Given 구현 완료, when `npm --prefix frontend run typecheck`를 실행하면, then TypeScript 오류가 0건이다
- Given 진입 화면 코드, when 검색하면, then 소셜 제공자 SDK·광고·분석 의존성과 브라우저 저장소 쓰기 호출이 없다

## Design Notes

토큰은 `DESIGN.md` frontmatter의 **평면 이름**을 그대로 옮긴다. 값을 재해석하거나 새 이름을 만들지 않아야 이후 스토리가 같은 이름으로 컴포넌트를 만든다.

```css
:root {
  --color-surface-base: #F7F8F6;
  --color-focus-ring: #1D4F91;
  --space-4: 16px;
  --space-target-min: 44px;
  --radius-md: 10px;
}
```

확대 검증은 브라우저 zoom 설정 대신 **뷰포트 폭 = 1280px ÷ 배율**로 재현한다(WCAG 1.4.10의 표준 해석). 400%는 320×512, 200%는 640×512로 두고 `scrollWidth <= clientWidth`를 확인하면 양방향 스크롤 없음이 증명된다.

진입 화면에는 동작하는 버튼이 없다. focusable 요소가 문서 내 앵커뿐일 수 있으므로 시험은 요소 개수를 단정하지 말고 **존재하는 모든 focusable 요소가 focus-visible 외곽선을 갖는지**를 검증한다.

## Verification

**Commands:**
- `npm --prefix frontend ci` -- expected: lockfile 기준 설치 성공
- `npm --prefix frontend run typecheck` -- expected: 오류 0건
- `npm --prefix frontend run build` -- expected: `frontend/dist/index.html`과 asset 생성
- `npm --prefix frontend run test` -- expected: 모든 Vitest 시험 통과
- `npm --prefix frontend run test:e2e` -- expected: 모든 Playwright 시험 통과(axe violations 0건 포함)
- `git status --short` -- expected: 시험 산출물이 미추적 파일로 남지 않음

## Suggested Review Order

**카피 정본과 화면 계약**

- 진입점. 화면·시험·`noscript`가 모두 참조하는 한국어 카피 단일 출처
  [`entry.ts:24`](../../frontend/src/content/entry.ts#L24)
- UX-DR23이 문자 그대로 요구하는 두 문장. 시험이 리터럴로 고정한다
  [`entry.ts:72`](../../frontend/src/content/entry.ts#L72)
- `tone: 'notice'`가 인증 경계 섹션만 `service-notice` 표면으로 올린다
  [`entry.ts:46`](../../frontend/src/content/entry.ts#L46)
- semantic 마크업. 라우터·소셜 버튼 없이 섹션만 렌더한다
  [`EntryScreen.tsx:12`](../../frontend/src/screens/EntryScreen.tsx#L12)
- landmark id를 상수로 공유해 건너뛰기 링크와 `main`이 어긋나지 않게 한다
  [`EntryScreen.tsx:23`](../../frontend/src/screens/EntryScreen.tsx#L23)

**디자인 토큰과 접근성 바닥선**

- `DESIGN.md` frontmatter의 평면 이름을 그대로 옮긴 토큰 정본
  [`tokens.css:11`](../../frontend/src/styles/tokens.css#L11)
- 전역 `focus-visible` 2px/2px. 컴포넌트가 특이도로 이기지 못하게 한 곳에서만 정의한다
  [`global.css:78`](../../frontend/src/styles/global.css#L78)
- forced-colors에서 시스템 포커스 색을 되돌려 주는 탈출구
  [`global.css:89`](../../frontend/src/styles/global.css#L89)
- 지속시간을 0s로 두어 "전환이 사라졌다"를 계산 스타일로 관측 가능하게 만든다
  [`global.css:99`](../../frontend/src/styles/global.css#L99)
- `env()` 폴백. 미지원 브라우저에서 선언이 무효가 되어 여백이 0으로 떨어지는 것을 막는다
  [`EntryScreen.module.css:19`](../../frontend/src/screens/EntryScreen.module.css#L19)
- 건너뛰기 링크는 `:focus`와 `:focus-visible` 양쪽에서 화면 안으로 들어온다
  [`EntryScreen.module.css:51`](../../frontend/src/screens/EntryScreen.module.css#L51)

**"요청 0건·저장 0건" 불변식의 강제 수단**

- 파비콘을 `data:` URI로 인라인해 동일 출처 404 요청 자체를 없앤다
  [`index.html:17`](../../frontend/index.html#L17)
- JS가 꺼져도 이용 범위·비추천 고지·인증 경계가 전달된다
  [`index.html:24`](../../frontend/index.html#L24)
- 네트워크 진입점을 거부 훅으로 교체해 요청을 관측 가능한 실패로 바꾼다
  [`setup.ts:71`](../../frontend/src/test/setup.ts#L71)
- IndexedDB까지 감시해 문서가 금지한 저장소 네 종을 모두 덮는다
  [`setup.ts:90`](../../frontend/src/test/setup.ts#L90)

**시험이 실제로 물게 만든 장치**

- 마운트 실패한 빈 페이지가 axe·저장소·모션 시험을 통과하는 것을 막는 canary
  [`entry-screen.spec.ts:32`](../../frontend/e2e/entry-screen.spec.ts#L32)
- 확대 배율을 뷰포트 폭으로 환산한다(WCAG 1.4.10 표준 해석)
  [`entry-screen.spec.ts:21`](../../frontend/e2e/entry-screen.spec.ts#L21)
- `URL.origin` 비교. `startsWith`는 포트 접두사 충돌로 외부 요청을 놓친다
  [`entry-screen.spec.ts:231`](../../frontend/e2e/entry-screen.spec.ts#L231)
- 알림 톤 분기가 사라지면 실패한다. 색 대비만으로는 axe가 잡지 못한다
  [`entry-screen.spec.ts:203`](../../frontend/e2e/entry-screen.spec.ts#L203)
- 고지 문구를 리터럴로 고정한다. 공유 상수만 쓰면 문구 변경을 못 잡는다
  [`EntryScreen.test.tsx:34`](../../frontend/src/screens/EntryScreen.test.tsx#L34)

**설정과 문서**

- 빌드를 `webServer`에서 분리하고 재사용을 끈다. 낡은 `dist/` 검증을 막는다
  [`playwright.config.ts:46`](../../frontend/playwright.config.ts#L46)
- `CI='0'`·`'false'`를 거짓으로 읽는다
  [`playwright.config.ts:9`](../../frontend/playwright.config.ts#L9)
- 프런트엔드 준비·명령·구조·규칙. AC-4의 "문서화된 절차"
  [`DEVELOPMENT.md:65`](../../DEVELOPMENT.md#L65)
- 제품 코드 도입 사실과 다음 단계를 갱신해 이후 세션의 전제를 맞춘다
  [`CLAUDE.md:17`](https://github.com/sh-9610/BokriLog/blob/2d9f85a3ee73dddce9b7feb786c97ef7d1f12fe2/CLAUDE.md#L17)
