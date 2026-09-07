/**
 * lint 정책 시험.
 *
 * 왜 있는가: `eslint.config.ts`·`stylelint.config.js`는 "색·크기 리터럴을 컴포넌트 CSS에 쓰지 않는다",
 * "import 순서", "이유 있는 disable만" 같은 DEVELOPMENT.md 규칙을 기계로 강제하는 유일한 수단이다.
 * 설정 한 줄이 조용히 바뀌면 규칙은 남고 검사만 사라진다. 그래서 설정을 문자열로 읽지 않고 ESLint·
 * stylelint를 실제로 실행해, 위반 fixture가 기대한 규칙 ID로 오류를 내고 준수 fixture가 통과함을 단정한다.
 * fixture는 spec-frontend-lint-policy.md `## I/O & Edge-Case Matrix`의 모든 행을 덮고, 그 위에 규칙의 경계
 * (축약형 펼치기의 구멍, 토큰이 섞인 계산식, 논리 축약형, 명령 층의 선택 범위)를 고정하는 fixture를 더 둔다.
 *
 * 위치: 대상 설정 파일이 `src/` 밖이라 이 시험도 `playwright.config.test.ts`와 같은 이유로 루트에 있다.
 * `vite.config.ts`의 `test.include`가 이 파일을 명시적으로 더 받고 `tsconfig.node.json`이 타입 검사한다.
 *
 * fixture 경로: ESLint fixture는 디스크에 없는 가상 경로 `__lint-policy-fixture__*`로 `lintText`에 넘긴다.
 * typescript-eslint의 프로젝트 서비스는 디스크에 없는 파일을 tsconfig include로 찾지 못하므로
 * `eslint.config.ts`가 이 이름만 기본 프로젝트로 받는다. 경로는 실제 파일군 규칙(`src/**`, `*.test.ts`,
 * `e2e/**`)에 걸리도록 고른다. 한계: `src/` 경로의 가상 fixture는 `allowDefaultProject`로 `tsconfig.node.json`의
 * compilerOptions를 받으므로, 앱 tsconfig(`jsx`, `lib` 등)에서만 드러나는 동작은 이 시험이 보증하지 못한다.
 * stylelint는 `codeFilename`으로 override(`*.module.css`, `tokens.css`)를 고른다.
 *
 * 환경: 공용 `src/test/setup.ts`가 jsdom 전역을 요구하므로 이 시험도 jsdom 환경에서 돈다. ESLint·stylelint는
 * Node API만 쓰고 jsdom 전역과 충돌하지 않는다. 타입 인지 lint는 첫 실행에서 TypeScript 프로그램을
 * 만들기 때문에 기본 timeout(5초)보다 길게 둔다.
 *
 * 경로 비교: 정본 경로는 언제나 POSIX 형태(`src/styles/global.css`)로 적고, 도구가 돌려준 절대경로는
 * `toPosixRelative`로 옮겨 비교한다. 두 기기 개발이라 시험이 플랫폼 구분자를 가정하면 한쪽에서만
 * 실패한다.
 */
import { ESLint } from 'eslint'
import { readFileSync } from 'node:fs'
import { dirname, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as prettier from 'prettier'
import stylelint from 'stylelint'
import { describe, expect, it, vi } from 'vitest'

vi.setConfig({ testTimeout: 60_000 })

// jsdom 환경의 전역 `URL`은 Node의 `fileURLToPath`가 받지 않으므로 문자열 그대로 넘긴다.
const cwd = dirname(fileURLToPath(import.meta.url))

/**
 * 절대경로를 `cwd` 기준 POSIX 상대경로로.
 *
 * `relative`는 플랫폼 구분자를 쓴다. Windows에서는 `src\styles\global.css`가 나와 시험이 적어 둔
 * 정본 경로(`src/styles/global.css`)와 어긋나고, 그래서 이 파일의 경로 단정이 그 플랫폼에서만
 * 실패한다. 검사 대상은 "명령이 어떤 파일을 골랐는가"이고 구분자는 그 사실이 아니다.
 *
 * 기대값을 `join(cwd, …)`로 절대경로로 만들어 비교하는 대안도 있다. 정본을 사람이 읽는 상대경로로
 * 두는 편이 시험 본문에서 더 잘 읽히므로 정규화를 골랐다.
 *
 * `sep`으로만 자른다. POSIX에서 `\`는 합법적인 파일명 문자라 무조건 치환하면 이름의 일부를 구분자로
 * 잘못 바꿀 수 있다.
 */
function toPosixRelative(absolutePath: string): string {
  return relative(cwd, absolutePath).split(sep).join('/')
}

// --------------------------------------------------------------------------
// ESLint
// --------------------------------------------------------------------------

/** `src/**`에 걸리는 앱 코드 fixture. JSX 없이도 import 정렬·disable 규칙을 검증할 수 있다. */
const APP_FIXTURE = 'src/screens/__lint-policy-fixture__.ts'
/** `.tsx` 경로. React hooks 규칙과 타입 인지 규칙이 같은 파일군에 걸리는지 고정한다. */
const COMPONENT_FIXTURE = 'src/screens/__lint-policy-fixture__.tsx'
/** `src/**\/*.test.{ts,tsx}`에 걸리는 Vitest fixture. */
const UNIT_TEST_FIXTURE = 'src/screens/__lint-policy-fixture__.test.ts'
/** `e2e/**`에 걸리는 Playwright fixture. */
const E2E_FIXTURE = 'e2e/__lint-policy-fixture__.spec.ts'

const eslint = new ESLint({ cwd })
const eslintWithFix = new ESLint({ cwd, fix: true })

async function lintTs(filePath: string, code: string, runner = eslint): Promise<ESLint.LintResult> {
  const [result] = await runner.lintText(code, { filePath })
  if (result === undefined) {
    throw new Error(`ESLint가 ${filePath}에 대한 결과를 내지 않았다.`)
  }
  return result
}

/** 규칙 ID 목록. 규칙이 없는 메시지(파싱 오류, 불필요한 disable)는 메시지 본문으로 대신한다. */
function ruleIds(result: ESLint.LintResult): string[] {
  return result.messages.map((message) => message.ruleId ?? `(${message.message})`)
}

// I/O: import 순서 -- 상대 경로 import가 외부 패키지 앞에 옴
const UNSORTED_IMPORTS = `import { entryContent } from '../content/entry'
import { StrictMode } from 'react'

export const fixture = [entryContent.brand, StrictMode] as const
`

// 통과 형태. 외부 → 빈 줄 → 상대. \`--fix\`의 결과와 같아야 한다.
const SORTED_IMPORTS = `import { StrictMode } from 'react'

import { entryContent } from '../content/entry'

export const fixture = [entryContent.brand, StrictMode] as const
`

// I/O: 집중 시험 잔존 -- Vitest에 \`it.only\`
const FOCUSED_UNIT_TEST = `import { describe, expect, it } from 'vitest'

describe('fixture', () => {
  it.only('집중 시험', () => {
    expect(1).toBe(1)
  })
})
`
const PLAIN_UNIT_TEST = FOCUSED_UNIT_TEST.replace('it.only(', 'it(')

// I/O: 집중 시험 잔존 -- e2e에 \`test.only\`
const FOCUSED_E2E_TEST = `import { expect, test } from '@playwright/test'

test.only('집중 시험', async ({ page }) => {
  await expect(page).toHaveTitle('BokriLog')
})
`
const PLAIN_E2E_TEST = FOCUSED_E2E_TEST.replace('test.only(', 'test(')

// 이유 없는 disable
const DESCRIPTIONLESS_DISABLE = `// eslint-disable-next-line @typescript-eslint/no-unused-vars
const unused = 1
export {}
`

// 위반이 없는 곳의 disable
const NEEDLESS_DISABLE = `// eslint-disable-next-line @typescript-eslint/no-unused-vars -- fixture: 위반이 없는 곳이다
export const used = 1
`

// 범위가 틀린 disable: 블록 disable을 열고 닫지 않는다
const UNPAIRED_DISABLE = `/* eslint-disable @typescript-eslint/no-unused-vars -- fixture: enable 짝이 없다 */
const unused = 1
export {}
`

// 이유가 있고 실제 위반을 가리는 disable. 이것만 허용된다.
const JUSTIFIED_DISABLE = `// eslint-disable-next-line @typescript-eslint/no-unused-vars -- fixture: 이유 있는 예외가 통과하는지 확인한다
const unused = 1
export {}
`

// 타입 인지 규칙 하나로 projectService·strict 묶음이 살아 있는지 고정한다. strict는 템플릿에 number를 허용하지 않는다.
const NUMBER_IN_TEMPLATE = `const n = 1
export const s = \`\${n}\`
`

// React hooks: 조건문 안의 hook 호출.
const CONDITIONAL_HOOK = `import { useState } from 'react'

export function Fixture(flag: boolean): number {
  if (flag) {
    const [n] = useState(0)
    return n
  }
  return 0
}
`

// type import가 값 import 뒤에 온다. 관례는 type import → 외부 → 상대.
const TYPE_IMPORT_AFTER_VALUE = `import { StrictMode } from 'react'
import type { ReactElement } from 'react'

export const fixture = [StrictMode] as const
export type Fixture = ReactElement
`

// side-effect import는 정렬하지 않고 제자리에 둔다.
const SIDE_EFFECT_IMPORT_IN_PLACE = `import { StrictMode } from 'react'

import { entryContent } from '../content/entry'
import '../styles/tokens.css'

export const fixture = [entryContent.brand, StrictMode] as const
`

// named import 안에서 type이 값 앞에 온다. 관례는 \`{ expect, test, type Page }\`.
const TYPE_SPECIFIER_BEFORE_VALUE = `import { type Page, expect } from '@playwright/test'

export async function mounted(page: Page): Promise<void> {
  await expect(page).toHaveTitle('BokriLog')
}
`

describe('ESLint 정책', () => {
  it('상대 경로 import가 외부 패키지 앞에 오면 perfectionist/sort-imports 오류다', async () => {
    const result = await lintTs(APP_FIXTURE, UNSORTED_IMPORTS)

    expect(result.fatalErrorCount).toBe(0)
    expect(ruleIds(result)).toEqual(['perfectionist/sort-imports'])
  })

  it('import 순서는 --fix로 정렬된다', async () => {
    const result = await lintTs(APP_FIXTURE, UNSORTED_IMPORTS, eslintWithFix)

    expect(result.output).toBe(SORTED_IMPORTS)
    expect(ruleIds(result)).toEqual([])
  })

  it('외부 → 빈 줄 → 상대 순서의 import는 통과한다', async () => {
    const result = await lintTs(APP_FIXTURE, SORTED_IMPORTS)

    expect(ruleIds(result)).toEqual([])
  })

  it('Vitest 시험의 it.only는 vitest/no-focused-tests 오류다', async () => {
    const result = await lintTs(UNIT_TEST_FIXTURE, FOCUSED_UNIT_TEST)

    expect(ruleIds(result)).toEqual(['vitest/no-focused-tests'])
  })

  it('집중하지 않은 Vitest 시험은 통과한다', async () => {
    const result = await lintTs(UNIT_TEST_FIXTURE, PLAIN_UNIT_TEST)

    expect(ruleIds(result)).toEqual([])
  })

  it('e2e의 test.only는 playwright/no-focused-test 오류다', async () => {
    const result = await lintTs(E2E_FIXTURE, FOCUSED_E2E_TEST)

    expect(ruleIds(result)).toEqual(['playwright/no-focused-test'])
  })

  it('집중하지 않은 e2e 시험은 통과한다', async () => {
    const result = await lintTs(E2E_FIXTURE, PLAIN_E2E_TEST)

    expect(ruleIds(result)).toEqual([])
  })

  it('이유 없는 eslint-disable은 require-description 오류다', async () => {
    const result = await lintTs(APP_FIXTURE, DESCRIPTIONLESS_DISABLE)

    expect(ruleIds(result)).toEqual(['@eslint-community/eslint-comments/require-description'])
  })

  it('위반이 없는 곳의 eslint-disable은 불필요한 지시문으로 오류다', async () => {
    const result = await lintTs(APP_FIXTURE, NEEDLESS_DISABLE)

    expect(result.errorCount).toBe(1)
    expect(
      result.messages.some(
        (message) =>
          message.ruleId === null && /unused eslint-disable directive/i.test(message.message),
      ),
    ).toBe(true)
  })

  it('enable 짝이 없는 블록 eslint-disable은 disable-enable-pair 오류다', async () => {
    const result = await lintTs(APP_FIXTURE, UNPAIRED_DISABLE)

    expect(ruleIds(result)).toEqual(['@eslint-community/eslint-comments/disable-enable-pair'])
  })

  it('이유가 있고 실제 위반을 가리는 eslint-disable은 통과한다', async () => {
    const result = await lintTs(APP_FIXTURE, JUSTIFIED_DISABLE)

    expect(ruleIds(result)).toEqual([])
  })

  it('타입 인지 strict 규칙이 .tsx에 걸린다: 템플릿의 number는 restrict-template-expressions 오류다', async () => {
    const result = await lintTs(COMPONENT_FIXTURE, NUMBER_IN_TEMPLATE)

    expect(result.fatalErrorCount).toBe(0)
    expect(ruleIds(result)).toEqual(['@typescript-eslint/restrict-template-expressions'])
  })

  it('조건문 안의 hook 호출은 react-hooks/rules-of-hooks 오류다', async () => {
    const result = await lintTs(COMPONENT_FIXTURE, CONDITIONAL_HOOK)

    expect(ruleIds(result)).toEqual(['react-hooks/rules-of-hooks'])
  })

  it('type import가 값 import 뒤에 오면 perfectionist/sort-imports 오류다', async () => {
    const result = await lintTs(APP_FIXTURE, TYPE_IMPORT_AFTER_VALUE)

    expect(ruleIds(result)).toEqual(['perfectionist/sort-imports'])
  })

  it('side-effect import는 제자리에 있으면 통과한다', async () => {
    const result = await lintTs(APP_FIXTURE, SIDE_EFFECT_IMPORT_IN_PLACE)

    expect(ruleIds(result)).toEqual([])
  })

  it('named import에서 type이 값 앞에 오면 perfectionist/sort-named-imports 오류다', async () => {
    const result = await lintTs(E2E_FIXTURE, TYPE_SPECIFIER_BEFORE_VALUE)

    expect(ruleIds(result)).toEqual(['perfectionist/sort-named-imports'])
  })
})

// --------------------------------------------------------------------------
// stylelint
// --------------------------------------------------------------------------

/** `*.module.css` override(camelCase 클래스)에 걸리는 경로. */
const MODULE_CSS_FIXTURE = 'src/screens/__lint-policy-fixture__.module.css'
/** override에 걸리지 않는 전역 CSS 경로. */
const GLOBAL_CSS_FIXTURE = 'src/styles/__lint-policy-fixture__.css'
/** 토큰 정의 파일. strict-value 규칙에서 제외된다. */
const TOKENS_CSS = 'src/styles/tokens.css'

const STRICT_VALUE = 'scale-unlimited/declaration-strict-value'

async function lintCss(codeFilename: string, code: string): Promise<stylelint.LintResult> {
  const { results } = await stylelint.lint({ cwd, code, codeFilename })
  const [result] = results
  if (result === undefined) {
    throw new Error(`stylelint가 ${codeFilename}에 대한 결과를 내지 않았다.`)
  }
  // 설정 오류는 규칙 위반이 아니라 시험 준비 실패다. 어느 시험에서든 즉시 드러나야 한다.
  expect(result.invalidOptionWarnings).toEqual([])
  expect(result.parseErrors).toEqual([])
  return result
}

function ruleNames(result: stylelint.LintResult): string[] {
  return result.warnings.map((warning) => warning.rule)
}

describe('stylelint 정책', () => {
  // I/O: 리터럴 색
  it('리터럴 색은 strict-value 오류이고 메시지가 tokens.css 참조를 안내한다', async () => {
    const result = await lintCss(MODULE_CSS_FIXTURE, '.a {\n  color: #17211b;\n}\n')

    expect(ruleNames(result)).toEqual([STRICT_VALUE])
    expect(result.warnings[0]?.severity).toBe('error')
    expect(result.warnings[0]?.text).toContain('"color"')
    expect(result.warnings[0]?.text).toContain('#17211b')
    expect(result.warnings[0]?.text).toContain('src/styles/tokens.css')
  })

  // I/O: 토큰 참조
  it('토큰 참조는 통과한다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      '.b {\n  color: var(--color-ink-primary);\n}\n',
    )

    expect(ruleNames(result)).toEqual([])
  })

  // I/O: 예외 범주 리터럴
  it('z-index·transition·1px 테두리 두께는 규칙 대상 속성이 아니라 통과한다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      [
        '.c {',
        '  z-index: 1;',
        '  transition: inset-block-start 120ms ease-out;',
        '  border: 1px solid var(--color-border-default);',
        '  outline: var(--space-focus-width) solid var(--color-focus-ring);',
        '}',
        '',
      ].join('\n'),
    )

    expect(ruleNames(result)).toEqual([])
  })

  it('DESIGN.md 네 범주(색·타이포그래피·모서리·간격)의 리터럴은 각각 오류다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      [
        '.d {',
        '  background: #f7f8f6;',
        '  font-size: 14px;',
        '  border-radius: 6px;',
        '  padding: 4px;',
        '}',
        '',
      ].join('\n'),
    )

    expect(ruleNames(result)).toEqual([STRICT_VALUE, STRICT_VALUE, STRICT_VALUE, STRICT_VALUE])
    const reportedProperties = result.warnings.map(
      (warning) => /^"([^"]+)"/.exec(warning.text)?.[1],
    )
    expect(reportedProperties).toEqual(['background', 'font-size', 'border-radius', 'padding'])
  })

  it('색 함수 리터럴(rgb)은 함수라는 이유로 통과하지 않는다', async () => {
    const result = await lintCss(MODULE_CSS_FIXTURE, '.f {\n  color: rgb(23 33 27);\n}\n')

    expect(ruleNames(result)).toEqual([STRICT_VALUE])
  })

  it('font 축약형의 리터럴(bold 14px/1.5 sans-serif)은 오류다', async () => {
    // 펼치기만으로는 `14px/1.5`가 한 토큰이라 새던 구멍이다. `font`를 직접 대상에 넣어 막는다.
    const result = await lintCss(MODULE_CSS_FIXTURE, '.l {\n  font: bold 14px/1.5 sans-serif;\n}\n')

    expect(ruleNames(result)).toContain(STRICT_VALUE)
    expect(ruleNames(result).every((rule) => rule === STRICT_VALUE)).toBe(true)
  })

  it('전부 토큰인 font 축약형은 통과한다', async () => {
    // 크기/행간은 `var(--a)/var(--b)`처럼 붙여 쓴다. `/`를 띄우면 `/`가 별도 토큰이 되어 리터럴로 보고된다.
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      '.m {\n  font: var(--type-body-font-weight) var(--type-body-font-size)/var(--type-body-line-height) var(--type-body-font-family);\n}\n',
    )

    expect(ruleNames(result)).toEqual([])
  })

  it('radius longhand(border-top-left-radius)의 리터럴은 오류다', async () => {
    const result = await lintCss(MODULE_CSS_FIXTURE, '.n {\n  border-top-left-radius: 6px;\n}\n')

    expect(ruleNames(result)).toEqual([STRICT_VALUE])
  })

  it('물리 오프셋(top)의 리터럴은 오류다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      '.o {\n  position: absolute;\n  top: 4px;\n}\n',
    )

    expect(ruleNames(result)).toEqual([STRICT_VALUE])
  })

  it('논리 border 축약형의 색 리터럴은 disallowed-list 오류이고 longhand를 안내한다', async () => {
    const result = await lintCss(MODULE_CSS_FIXTURE, '.p {\n  border-inline: 1px solid #000;\n}\n')

    expect(ruleNames(result)).toEqual(['declaration-property-value-disallowed-list'])
    expect(result.warnings[0]?.text).toContain('border-inline-color')
  })

  it('논리 border 축약형이 토큰을 참조하면 통과한다(두께·스타일은 예외 범주)', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      '.q {\n  border-inline: 1px solid var(--color-border-default);\n}\n',
    )

    expect(ruleNames(result)).toEqual([])
  })

  it('토큰과 리터럴이 섞인 다중값(padding-block)은 오류다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      '.r {\n  padding-block: var(--space-5) 13px;\n}\n',
    )

    expect(ruleNames(result)).toEqual([STRICT_VALUE])
  })

  it('토큰을 하나라도 인용한 계산식은 리터럴이 섞여도 통과한다', async () => {
    // 의도된 경계다. 식 안의 리터럴까지 막으면 `env()` 폴백·비율 계산이 모두 disable을 요구한다.
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      '.s {\n  padding-top: calc(var(--space-1) + 13px);\n}\n',
    )

    expect(ruleNames(result)).toEqual([])
  })

  it('CSS 전역 키워드(unset)는 통과한다', async () => {
    const result = await lintCss(MODULE_CSS_FIXTURE, '.t {\n  color: unset;\n}\n')

    expect(ruleNames(result)).toEqual([])
  })

  it('소문자 시스템 색 이름(highlight)도 통과한다', async () => {
    const result = await lintCss(
      GLOBAL_CSS_FIXTURE,
      '@media (forced-colors: active) {\n  :focus-visible {\n    outline-color: highlight;\n  }\n}\n',
    )

    expect(ruleNames(result)).toEqual([])
  })

  it('box-shadow 리터럴은 strict-value 대상이 아니다(Elevation은 DESIGN.md가 문장으로 정한 값)', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      '.u {\n  box-shadow: 0 12px 32px rgb(23 33 27 / 14%);\n}\n',
    )

    expect(ruleNames(result)).not.toContain(STRICT_VALUE)
  })

  it('토큰을 인용하는 calc·max 계산식은 여러 줄이어도 통과한다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      [
        '.e {',
        '  padding-inline: max(var(--space-page-mobile), env(safe-area-inset-left, 0px));',
        '  margin-block-start: calc(',
        '    (var(--type-body-font-size) * var(--type-body-line-height) - var(--space-1)) / 2',
        '  );',
        '}',
        '',
      ].join('\n'),
    )

    expect(ruleNames(result)).toEqual([])
  })

  it('forced-colors 시스템 색 이름은 통과한다', async () => {
    const result = await lintCss(
      GLOBAL_CSS_FIXTURE,
      '@media (forced-colors: active) {\n  :focus-visible {\n    outline-color: Highlight !important;\n  }\n}\n',
    )

    expect(ruleNames(result)).toEqual([])
  })

  // I/O: 이유 없는 disable
  it('이유 없는 stylelint-disable은 reportDescriptionlessDisables 오류다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      `.g {\n  /* stylelint-disable-next-line ${STRICT_VALUE} */\n  color: #17211b;\n}\n`,
    )

    expect(ruleNames(result)).toEqual(['--report-descriptionless-disables'])
  })

  // I/O: 불필요한 disable
  it('위반이 없는 곳의 stylelint-disable은 reportNeedlessDisables 오류다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      `.h {\n  /* stylelint-disable-next-line ${STRICT_VALUE} -- fixture: 위반이 없는 곳이다 */\n  color: var(--color-ink-primary);\n}\n`,
    )

    expect(ruleNames(result)).toEqual(['--report-needless-disables'])
  })

  it('설정에 없는 규칙을 가리는 stylelint-disable은 reportInvalidScopeDisables 오류다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      '.i {\n  /* stylelint-disable-next-line no-such-rule -- fixture: 존재하지 않는 규칙이다 */\n  color: var(--color-ink-primary);\n}\n',
    )

    expect(ruleNames(result)).toContain('--report-invalid-scope-disables')
  })

  it('이유가 있고 실제 위반을 가리는 stylelint-disable은 통과한다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      `.j {\n  /* stylelint-disable-next-line ${STRICT_VALUE} -- fixture: 이유 있는 예외가 통과하는지 확인한다 */\n  color: #17211b;\n}\n`,
    )

    expect(ruleNames(result)).toEqual([])
  })

  // I/O: tokens.css
  it('tokens.css의 리터럴 정의는 규칙 제외 파일이라 통과한다', async () => {
    const result = await lintCss(
      TOKENS_CSS,
      ':root {\n  --color-ink-primary: #17211b;\n  --space-1: 4px;\n}\n',
    )

    expect(ruleNames(result)).toEqual([])
  })

  it('CSS Module 클래스는 camelCase여야 한다', async () => {
    const kebab = await lintCss(
      MODULE_CSS_FIXTURE,
      '.kebab-case {\n  color: var(--color-ink-primary);\n}\n',
    )
    const camel = await lintCss(
      MODULE_CSS_FIXTURE,
      '.camelCase {\n  color: var(--color-ink-primary);\n}\n',
    )
    const globalKebab = await lintCss(
      GLOBAL_CSS_FIXTURE,
      '.kebab-case {\n  color: var(--color-ink-primary);\n}\n',
    )

    expect(ruleNames(kebab)).toEqual(['selector-class-pattern'])
    expect(ruleNames(camel)).toEqual([])
    expect(ruleNames(globalKebab)).toEqual([])
  })

  it('기존 min-width 미디어 쿼리 표기는 통과한다', async () => {
    const result = await lintCss(
      MODULE_CSS_FIXTURE,
      '@media (min-width: 768px) {\n  .k {\n    gap: var(--space-4);\n  }\n}\n',
    )

    expect(ruleNames(result)).toEqual([])
  })
})

// --------------------------------------------------------------------------
// Prettier
// --------------------------------------------------------------------------

/** `.prettierrc.json`을 실제로 해석한다. 설정 파일이 사라지거나 키가 바뀌면 여기서 드러난다. */
async function prettierConforms(filePath: string, code: string): Promise<boolean> {
  const config = await prettier.resolveConfig(`${cwd}/${filePath}`, { editorconfig: false })
  if (config === null) {
    throw new Error('.prettierrc.json을 해석하지 못했다.')
  }
  return prettier.check(code, { ...config, filepath: filePath })
}

// I/O: 서식 불일치 -- 세미콜론이 있는 파일
const SEMICOLON_FILE = "export const fixture = 'a';\n"
// 통과 형태. 세미콜론 없음, 단일 인용부호.
const CONFORMANT_FILE = "export const fixture = 'a'\n"
// 이중 인용부호도 서식 불일치다.
const DOUBLE_QUOTE_FILE = 'export const fixture = "a"\n'
// 여러 줄 객체에 후행 쉼표가 없다.
const NO_TRAILING_COMMA_FILE = `export const fixture = {
  alpha: 1,
  beta: 2
}
`
// 100자를 넘지만 접을 수 있는 줄.
const OVERLONG_FOLDABLE_LINE = `export const fixture = [${Array.from({ length: 12 }, (_, i) => `'item-${String(i)}'`).join(', ')}]\n`

async function prettierIgnores(filePath: string): Promise<boolean> {
  const info = await prettier.getFileInfo(`${cwd}/${filePath}`, {
    ignorePath: `${cwd}/.prettierignore`,
  })
  return info.ignored
}

describe('Prettier 정책', () => {
  it('세미콜론이 있는 파일은 prettier --check에 실패한다', async () => {
    expect(await prettierConforms(APP_FIXTURE, SEMICOLON_FILE)).toBe(false)
  })

  it('이중 인용부호 문자열은 prettier --check에 실패한다', async () => {
    expect(await prettierConforms(APP_FIXTURE, DOUBLE_QUOTE_FILE)).toBe(false)
  })

  it('세미콜론 없음·단일 인용부호 파일은 통과한다', async () => {
    expect(await prettierConforms(APP_FIXTURE, CONFORMANT_FILE)).toBe(true)
  })

  it('여러 줄 객체에 후행 쉼표가 없으면 실패한다', async () => {
    expect(await prettierConforms(APP_FIXTURE, NO_TRAILING_COMMA_FILE)).toBe(false)
  })

  it('100자를 넘는데 접을 수 있는 줄은 실패한다', async () => {
    expect(OVERLONG_FOLDABLE_LINE.length).toBeGreaterThan(100)
    expect(await prettierConforms(APP_FIXTURE, OVERLONG_FOLDABLE_LINE)).toBe(false)
  })

  it('.prettierignore가 생성물과 lockfile만 제외한다', async () => {
    expect(await prettierIgnores('package-lock.json')).toBe(true)
    expect(await prettierIgnores('dist/x.js')).toBe(true)
    expect(await prettierIgnores('src/screens/EntryScreen.tsx')).toBe(false)
  })
})

// --------------------------------------------------------------------------
// 명령 층의 선택 범위
// --------------------------------------------------------------------------

/**
 * API를 직접 부르는 위 시험은 glob·ignore가 바뀌어도 알지 못한다. `package.json`의 명령이 실제로
 * 어떤 파일을 고르는지 여기서 고정한다.
 */
describe('명령 층의 선택 범위', () => {
  it('lint:css의 glob이 전역 CSS·토큰·CSS Module을 모두 고른다', async () => {
    const pkg = JSON.parse(readFileSync(`${cwd}/package.json`, 'utf8')) as {
      scripts: Record<string, string>
    }
    const script = pkg.scripts['lint:css']
    const globPattern = /"([^"]+)"/.exec(script ?? '')?.[1]
    if (globPattern === undefined) {
      throw new Error(`lint:css 스크립트에서 glob을 읽지 못했다: ${script ?? '(없음)'}`)
    }

    const { results } = await stylelint.lint({ cwd, files: globPattern })
    const sources = results.map((result) => {
      // `?? ''`로 접으면 `relative(cwd, '')`가 `..` 형태의 값을 내고, 아래 `arrayContaining`이
      // 그 이상값을 조용히 통과시킨다. source 없는 결과는 경로 단정이 성립하지 않는 상태다.
      if (result.source === undefined) {
        throw new Error('stylelint 결과에 source가 없다. 경로 단정이 성립하지 않는다.')
      }
      return toPosixRelative(result.source)
    })

    expect(sources).toEqual(
      expect.arrayContaining([
        'src/styles/global.css',
        'src/styles/tokens.css',
        'src/screens/EntryScreen.module.css',
      ]),
    )
    // 비교하는 값이 POSIX 형태라는 계약을 코드로 못박는다. Linux에서는 `relative`가 이미 `/`를
    // 내므로 이 단정이 정규화 부재를 잡지는 못한다 — 그것을 잡을 수 있는 플랫폼은 Windows뿐이고,
    // CI가 Linux만 돌므로 그 공백은 `deferred-work.md`에 남겼다.
    expect(sources.filter((source) => source.includes('\\'))).toEqual([])
  })

  it('eslint .이 앱·E2E·설정 파일을 무시하지 않는다', async () => {
    const paths = [
      'src/screens/EntryScreen.tsx',
      'e2e/entry-screen.spec.ts',
      'eslint.config.ts',
      'stylelint.config.js',
    ]
    const ignored = await Promise.all(
      paths.map(async (filePath) => ({
        filePath,
        ignored: await eslint.isPathIgnored(`${cwd}/${filePath}`),
      })),
    )

    expect(ignored).toEqual(paths.map((filePath) => ({ filePath, ignored: false })))
  })
})
