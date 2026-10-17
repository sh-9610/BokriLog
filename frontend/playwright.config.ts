import { defineConfig, devices, type PlaywrightTestConfig } from '@playwright/test'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { E2E_RUN_ENV, isRunStamp, RESULTS_ROOT, runStamp } from './e2e/support/runOutput'

/*
 * 이 설정 파일이 있는 디렉터리(`frontend/`). `new URL('./…', import.meta.url)` 꼴은 Vite가 자산 URL로 바꿔 Vitest(이 설정의 계약
 * 시험)에서 파일 경로가 되지 않으므로 경로 함수로 만든다.
 */
const CONFIG_DIR = dirname(fileURLToPath(import.meta.url))

// `vite preview` 기본 포트(4173)를 피한다. 손으로 띄워 둔 preview와 포트가 겹치지 않게 한다.
const PORT = 4183
const HOST = '127.0.0.1'
const PREVIEW_URL = `http://${HOST}:${String(PORT)}`

/** `CI=''`·`CI=0`·`CI=false`는 거짓으로 본다. */
function isTruthyEnv(name: string): boolean {
  const raw = process.env[name]
  if (raw === undefined) {
    return false
  }
  const normalized = raw.trim().toLowerCase()
  return normalized !== '' && normalized !== '0' && normalized !== 'false'
}

const isCi = isTruthyEnv('CI')

/**
 * 외부에서 주입한 origin. 값이 있으면 그 주소를 이미 누군가 띄워 둔 것으로 보고
 * 자체 `preview` 서버를 띄우지 않는다.
 *
 * 쓰는 곳은 Story 1.2의 `same-origin-integration`이다. 같은 시험 본문을 `vite preview`가 아니라
 * Nginx 진입점에 대고 다시 돌려, 정적 제공·응답 헤더·`/api` 경계가 붙은 실제 배포 형태에서도
 * Story 1.1의 화면 계약이 유지되는지 확인한다. 시험 파일은 한 줄도 바꾸지 않는다.
 *
 * 값이 없으면 기존 동작 그대로다. `npm run test:e2e`는 여전히 빌드하고 preview를 띄운다.
 */
const injectedBaseUrl = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''
const usesInjectedOrigin = injectedBaseUrl !== ''

/*
 * 내보내지 않는다. 시험이 볼 주소는 Playwright의 `baseURL` fixture 하나뿐이고
 * `e2e/**`는 그것만 쓴다. 여기서 export하면 "설정에서 직접 import한 상수"와 "fixture"라는
 * 두 경로가 생겨, 주입된 origin에서 한쪽만 갱신되는 순간 시험이 서로 다른 주소를 본다.
 */
const BASE_URL = usesInjectedOrigin ? injectedBaseUrl : PREVIEW_URL

/**
 * 로컬 실행의 출력 디렉터리(`test-results/<UTC 시각>`).
 *
 * Playwright는 실행을 시작할 때 `outputDir`을 통째로 지운다. 한 디렉터리를 계속 쓰면 앞선 실행의 실패 trace가 다음 실행에
 * 사라져(`deferred-work.md` 155) 새 스택 첫 실행에서만 나는 실패를 다시 볼 수 없다. 실행마다 새 디렉터리를 쓰고, 보존 상한은
 * `e2e/support/runOutput.ts`(globalSetup)가 지킨다. CI는 run마다 깨끗한 러너라 지울 앞선 실행이 없고, artifact 경로를
 * `ci.yml`이 소유하므로 `test-results` 그대로다.
 *
 * 표지는 주 프로세스가 설정을 읽을 때마다 새로 정한다 — 셸에 같은 변수가 export돼 있거나 UI 모드가 한 프로세스에서 여러 번
 * 돌아도 실행 디렉터리가 겹치지 않는다. Playwright는 worker(`TEST_WORKER_INDEX`가 있다)에서 이 설정을 다시 읽으므로, worker만
 * 주 프로세스가 환경(`E2E_RUN_ENV`)에 둔 표지를 물려받아 같은 디렉터리를 쓴다.
 */
function localOutputDir(): string {
  const inherited = process.env[E2E_RUN_ENV]?.trim() ?? ''
  const inWorker = (process.env.TEST_WORKER_INDEX ?? '') !== ''
  const run = inWorker && isRunStamp(inherited) ? inherited : runStamp(new Date())
  process.env[E2E_RUN_ENV] = run
  /*
   * `--last-failed`는 기본으로 `<outputDir>/.last-run.json`을 읽는다. 실행마다 디렉터리가 바뀌면 직전 실행의 기록을 찾지
   * 못하므로 기록 파일만 뿌리에 고정한다. 손으로 준 값은 그대로 두고, 빈 문자열은 주지 않은 것으로 본다.
   */
  if ((process.env.PLAYWRIGHT_LAST_RUN_OUTPUT_FILE?.trim() ?? '') === '') {
    process.env.PLAYWRIGHT_LAST_RUN_OUTPUT_FILE = join(CONFIG_DIR, RESULTS_ROOT, '.last-run.json')
  }
  return `${RESULTS_ROOT}/${run}`
}

const outputDir = isCi ? RESULTS_ROOT : localOutputDir()

/*
 * 빌드는 `test:e2e` 스크립트가 먼저 실행한다. 여기서는 산출물을 제공하기만 한다.
 * 빌드를 서버 기동과 묶으면 서버가 재사용될 때 빌드까지 건너뛰어 낡은 `dist/`를 검증하게 된다.
 * 그래서 `reuseExistingServer`는 항상 false다. 백엔드·DB·제공자 자격정보는 요구하지 않는다.
 */
const previewServer: NonNullable<PlaywrightTestConfig['webServer']> = {
  command: `npm run preview -- --host ${HOST} --port ${String(PORT)} --strictPort`,
  url: PREVIEW_URL,
  reuseExistingServer: false,
  timeout: 120_000,
  stdout: 'ignore',
  stderr: 'pipe',
}

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: isCi,
  /*
   * 필수 CI는 재시도하지 않는다. 재시도는 flaky 실패를 green으로 덮어 첫 실패의 원인을 지운다.
   * flaky 조사는 배포 권한이 없는 별도 진단 실행에서 `--retries=N`으로 덮어쓴다.
   */
  retries: 0,
  workers: isCi ? 1 : undefined,
  /*
   * CI의 Playwright 단계 timeout보다 짧다. 남는 시간에 실패 진단 artifact를 업로드한다.
   *
   * 주입된 origin(`same-origin-integration`)은 실제 스택에서 전체 묶음을 돈다. 그 단계는 8분이고
   * 여기 상한은 7분이다. Story 2.10의 PR #49에서 이미 2분 30초(59건)를 썼고, Story 2.11이 동기화
   * 왕복을 더해 3분 상한을 5분으로 올렸다. Story 2.17이 재연결 왕복을 더하자 CI 모드 실측(69건)이
   * 4분 36초로 5분에 붙어 7분으로 다시 올렸다. preview(`frontend-quality`, 단계 4분)는 스택이
   * 필요한 시험을 건너뛰므로 3분 그대로다.
   */
  globalTimeout: usesInjectedOrigin ? 420_000 : 180_000,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir,
  use: {
    baseURL: BASE_URL,
    // `on-first-retry`는 retries가 0이면 trace를 하나도 남기지 않는다. 첫 실패부터 보존한다.
    trace: 'retain-on-failure',
    // CI가 업로드하는 진단물은 report·trace·screenshot 세 가지다. 기본값 `off`면 screenshot이 없다.
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  // 로컬 실행만 보존 상한을 지킨다. CI의 출력 디렉터리에는 지울 앞선 실행이 없다.
  ...(isCi ? {} : { globalSetup: './e2e/support/runOutput.ts' }),
  // 주입된 origin에서는 `webServer` 키 자체를 두지 않는다. `undefined`를 넣어도 Playwright는
  // 같게 다루지만, 키가 없는 편이 "이 실행에는 관리할 서버가 없다"를 설정 자체로 드러낸다.
  ...(usesInjectedOrigin ? {} : { webServer: previewServer }),
})
