/*
 * ESLint flat config.
 *
 * 정본: _bmad-output/implementation-artifacts/spec-frontend-lint-policy.md, CI-PLAN.md §6·§8.
 * 규칙이 실제로 위반을 잡는지는 `lint-policy.test.ts`가 fixture로 고정한다.
 *
 * 구성 원칙:
 *   - 타입 인지 검사(typescript-eslint strict·stylistic)를 모든 TS·TSX에 적용한다.
 *   - 파일군별 플러그인: `src/**\/*.test.tsx`는 Vitest·Testing Library, `e2e/**`는 Playwright,
 *     루트의 Vitest 시험 파일(`playwright.config.test.ts`, `lint-policy.test.ts`)은 Vitest.
 *   - import 정렬은 perfectionist 하나만 쓴다. 현재 관례(type import → 외부 → 빈 줄 → 상대,
 *     side-effect import는 제자리)를 기본 그룹이 그대로 표현한다. 정렬은 `--fix`가 한다.
 *   - 인라인 예외는 이유(`-- 설명`)가 있는 disable만 허용한다. 이유 없는 disable은
 *     `require-description`, 불필요한 disable은 `reportUnusedDisableDirectives`, 범위가 틀린
 *     disable은 `disable-enable-pair`·`no-unlimited-disable`이 오류로 잡는다.
 *   - 서식 규칙은 `eslint-config-prettier`가 끈다. 서식은 Prettier가 소유한다.
 *   - `eslint-plugin-jsx-a11y`는 ESLint 10 peer 미지원으로 제외했다(deferred-work.md).
 *     렌더 결과의 접근성은 E2E axe 검사가 담당한다.
 *
 * 타입 정보 해석:
 *   `projectService`는 파일 위치에서 가장 가까운 `tsconfig.json`을 찾는다. `src/**`는 그대로
 *   `tsconfig.json`에 잡히고, `e2e/**`는 `e2e/tsconfig.json`(tsconfig.node.json 확장)에 잡힌다.
 *   저장소 루트의 설정·시험 파일(`*.ts`)은 `tsconfig.json`의 include 밖이라 `allowDefaultProject`로
 *   `tsconfig.node.json`의 compilerOptions를 적용한다. 한 프로세스에서 기본 프로젝트로 여는 파일
 *   수는 상한(8) 안이어야 한다. 루트 파일이 늘면 `tsconfig.node.json`과 함께 이 목록을 본다.
 *
 *   `lint-policy.test.ts`는 디스크에 없는 fixture를 `ESLint#lintText`로 검사한다. 프로젝트 서비스는
 *   디스크에 없는 파일을 tsconfig include로 찾지 못하므로, 이름이 `__lint-policy-fixture__`인
 *   가상 경로만 기본 프로젝트로 받는다(`**`는 허용되지 않아 디렉터리를 고정한다). 그 이름의
 *   실제 파일은 만들지 않는다.
 */
import comments from '@eslint-community/eslint-plugin-eslint-comments/configs'
import js from '@eslint/js'
import vitest from '@vitest/eslint-plugin'
import prettier from 'eslint-config-prettier/flat'
import perfectionist from 'eslint-plugin-perfectionist'
import playwright from 'eslint-plugin-playwright'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import testingLibrary from 'eslint-plugin-testing-library'
import { defineConfig, globalIgnores } from 'eslint/config'
import globals from 'globals'
import tseslint from 'typescript-eslint'

/** 루트의 Vitest 시험 파일. 대상이 `src/` 밖이라 시험도 그 옆에 있다(`vite.config.ts` test.include). */
const ROOT_VITEST_FILES = ['playwright.config.test.ts', 'lint-policy.test.ts']

/** `lint-policy.test.ts`가 `lintText`로 검사하는 가상 fixture 경로. 디스크에 존재하지 않는다. */
const POLICY_FIXTURE_FILES = [
  'src/screens/__lint-policy-fixture__*',
  'e2e/__lint-policy-fixture__*',
]

export default defineConfig(
  globalIgnores(['dist/', 'playwright-report/', 'test-results/']),

  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
      comments.recommended,
    ],
    languageOptions: {
      parserOptions: {
        projectService: {
          allowDefaultProject: ['*.ts', ...POLICY_FIXTURE_FILES],
          defaultProject: 'tsconfig.node.json',
        },
        tsconfigRootDir: import.meta.dirname,
      },
    },
    linterOptions: {
      // 위반이 없는 곳의 disable 주석은 오류다. 규칙이 바뀌어 더는 필요 없는 예외를 남기지 않는다.
      reportUnusedDisableDirectives: 'error',
    },
    plugins: { perfectionist },
    rules: {
      // 모든 disable 주석은 `-- 이유`를 달아야 한다. 예외의 근거를 코드 옆에 남긴다.
      '@eslint-community/eslint-comments/require-description': 'error',
      'perfectionist/sort-imports': ['error', { type: 'natural' }],
      // `{ expect, test, type Page }`처럼 값 import 뒤에 type import를 둔다.
      'perfectionist/sort-named-imports': [
        'error',
        { type: 'natural', groups: ['value-import', 'type-import'] },
      ],
    },
  },

  // JS 설정 파일(`stylelint.config.js`). 타입 정보 없이 기본 권장 규칙과 disable 감사를 적용한다.
  // 이 블록이 없으면 `.js`는 `eslint-config-prettier`만 받아 어떤 규칙도 없이 통과한다.
  {
    files: ['**/*.{js,mjs,cjs}'],
    extends: [js.configs.recommended, comments.recommended],
    languageOptions: { globals: globals.node },
    linterOptions: {
      reportUnusedDisableDirectives: 'error',
    },
    rules: {
      '@eslint-community/eslint-comments/require-description': 'error',
    },
  },

  // 앱 코드: 브라우저 전역, React hooks·refresh.
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [reactHooks.configs.flat.recommended, reactRefresh.configs.vite],
    languageOptions: { globals: globals.browser },
  },

  // 도구 설정·E2E: Node 전역.
  {
    files: ['*.ts', 'e2e/**/*.ts'],
    languageOptions: { globals: globals.node },
  },

  // Vitest 화면·단위 시험.
  {
    files: ['src/**/*.test.{ts,tsx}'],
    extends: [vitest.configs.recommended, testingLibrary.configs['flat/react']],
    rules: {
      // 집중 시험(`it.only`)이 남으면 나머지 시험이 조용히 건너뛰어진다.
      'vitest/no-focused-tests': 'error',
    },
  },

  // 루트의 Vitest 계약·정책 시험.
  {
    files: ROOT_VITEST_FILES,
    extends: [vitest.configs.recommended],
    rules: {
      'vitest/no-focused-tests': 'error',
    },
  },

  // Playwright E2E. `playwright.config.ts`의 `forbidOnly`는 CI에서만 켜지므로 lint가 항상 잡는다.
  {
    files: ['e2e/**/*.ts'],
    extends: [playwright.configs['flat/recommended']],
    rules: {
      'playwright/no-focused-test': 'error',
    },
  },

  // 서식 규칙은 Prettier가 소유한다. 마지막에 두어 앞의 모든 서식 규칙을 끈다.
  prettier,
)
