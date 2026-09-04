import { defineConfig, devices } from '@playwright/test'

// `vite preview` 기본 포트(4173)를 피한다. 손으로 띄워 둔 preview와 포트가 겹치지 않게 한다.
const PORT = 4183
const HOST = '127.0.0.1'
export const BASE_URL = `http://${HOST}:${String(PORT)}`

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
  /* CI의 Playwright 단계 timeout(4분)보다 짧다. 남는 시간에 실패 진단 artifact를 업로드한다. */
  globalTimeout: 180_000,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
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
  /*
   * 빌드는 `test:e2e` 스크립트가 먼저 실행한다. 여기서는 산출물을 제공하기만 한다.
   * 빌드를 서버 기동과 묶으면 서버가 재사용될 때 빌드까지 건너뛰어 낡은 `dist/`를 검증하게 된다.
   * 그래서 `reuseExistingServer`는 항상 false다. 백엔드·DB·제공자 자격정보는 요구하지 않는다.
   */
  webServer: {
    command: `npm run preview -- --host ${HOST} --port ${String(PORT)} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
})
