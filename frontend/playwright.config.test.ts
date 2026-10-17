import type { FullConfig } from '@playwright/test'

/**
 * `playwright.config.ts` 계약 시험.
 *
 * 왜 있는가: CI-PLAN §6은 필수 Playwright 실행의 `retries: 0`과 `trace: 'retain-on-failure'`를
 * "한 묶음"으로 못박았다. `on-first-retry`인 채로 retry만 0이 되면 trace가 하나도 남지 않고,
 * retry가 되살아나면 flaky 실패가 green으로 덮인다. 두 값은 설정 파일 한 줄로 조용히 뒤집히므로
 * 시험으로 고정한다. `globalTimeout`은 CI의 Playwright 단계 timeout(preview 4분, 주입된 origin
 * 8분)보다 짧아야 실패 진단 artifact를 올릴 시간이 남는다.
 *
 * Story 1.2가 origin 주입 분기를 더했다. 그 분기가 조용히 사라지면 `same-origin-integration`이
 * Nginx가 아니라 자기가 띄운 `vite preview`를 검증하면서도 green으로 끝난다. 두 경우를 모두
 * 단정하는 이유가 이것이다.
 *
 * 위치: 설정 파일이 `src/` 밖이라 이 시험도 저장소 루트에 둔다. `vite.config.ts`의
 * `test.include`가 이 파일 하나를 명시적으로 더 받고, `tsconfig.node.json`이 타입 검사한다.
 * `src/` 안으로 옮기면 `process.env`를 쓰는 설정 파일이 앱 프로그램(`tsconfig.json`)에 끌려
 * 들어와 앱 코드에까지 Node 타입을 열어야 한다.
 *
 * 환경: 공용 `src/test/setup.ts`가 jsdom 전역(`XMLHttpRequest`)을 요구하므로 이 시험도
 * 같은 jsdom 환경에서 돈다. 설정 파일이 import하는 `@playwright/test`는 Node API만 쓰고
 * jsdom 환경에서도 그대로 로드된다.
 */
import { chmodSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'

import globalSetup, { KEPT_RUNS, pruneRuns } from './e2e/support/runOutput'

/*
 * 첫 시험 하나가 `@playwright/test`를 처음 로드한다. 그 모듈 그래프는 크고, worker pool이
 * 다른 시험 파일과 CPU를 나눠 쓰는 실행에서는 기본 timeout(5초)을 넘긴다. Story 1.4가 화면
 * 시험 파일 세 개를 더하면서 이 저장소에서 실제로 그 지점을 지났다.
 *
 * 이 시험이 고정하는 것은 설정 값이지 로드 시간이 아니다. 그래서 timeout을 늘리는 것이
 * 단정을 하나도 무르지 않는다. `lint-policy.test.ts`가 타입 인지 lint의 첫 실행 때문에 같은
 * 조치를 한 선례가 있다.
 */
vi.setConfig({ testTimeout: 30_000 })

const originalCi = process.env.CI
const originalBaseUrl = process.env.PLAYWRIGHT_BASE_URL
const originalRun = process.env.BOKRILOG_E2E_RUN
const originalLastRun = process.env.PLAYWRIGHT_LAST_RUN_OUTPUT_FILE
const originalWorker = process.env.TEST_WORKER_INDEX

/**
 * `undefined`면 지우고, 값이 있으면 설정한다. 빈 문자열도 의미가 있는 값이라 그대로 넣는다.
 * 설정 파일이 두 변수를 각각 다르게 읽으므로 이름을 인자로 받아 한 함수로 다룬다.
 */
function setEnv(
  name:
    | 'BOKRILOG_E2E_RUN'
    | 'CI'
    | 'PLAYWRIGHT_BASE_URL'
    | 'PLAYWRIGHT_LAST_RUN_OUTPUT_FILE'
    | 'TEST_WORKER_INDEX',
  value: string | undefined,
): void {
  if (value === undefined) {
    // eslint-disable-next-line @typescript-eslint/no-dynamic-delete -- 값 대입으로는 "변수 없음"을 재현할 수 없다. 설정 파일이 `undefined`와 빈 문자열을 다르게 다루므로 실제로 지워야 한다.
    delete process.env[name]
  } else {
    process.env[name] = value
  }
}

/** 환경변수를 바꾼 뒤 설정 모듈을 다시 평가한다. 설정은 import 시점에 환경을 읽는다. */
async function loadConfig(ci: string | undefined, baseUrl?: string) {
  setEnv('CI', ci)
  setEnv('PLAYWRIGHT_BASE_URL', baseUrl)
  vi.resetModules()
  const loaded = await import('./playwright.config')
  return loaded.default
}

afterEach(() => {
  setEnv('CI', originalCi)
  setEnv('PLAYWRIGHT_BASE_URL', originalBaseUrl)
  // 로컬 분기는 실행 표지와 `--last-failed` 기록 위치를 환경에 둔다(worker가 물려받게). 시험 사이에 남기지 않는다.
  setEnv('BOKRILOG_E2E_RUN', originalRun)
  setEnv('PLAYWRIGHT_LAST_RUN_OUTPUT_FILE', originalLastRun)
  setEnv('TEST_WORKER_INDEX', originalWorker)
  vi.resetModules()
})

describe('playwright.config.ts 실행 정책', () => {
  it('필수 실행은 재시도하지 않는다', async () => {
    const config = await loadConfig('true')
    expect(config.retries).toBe(0)
  })

  it('CI가 꺼져 있어도 재시도는 0이다', async () => {
    const config = await loadConfig(undefined)
    expect(config.retries).toBe(0)
  })

  it('trace는 첫 실패부터 보존한다', async () => {
    const config = await loadConfig('true')
    expect(config.use?.trace).toBe('retain-on-failure')
  })

  it('실패 screenshot을 남긴다', async () => {
    const config = await loadConfig('true')
    expect(config.use?.screenshot).toBe('only-on-failure')
  })

  it('globalTimeout이 CI의 Playwright 단계 timeout(preview 4분)보다 짧다', async () => {
    const config = await loadConfig('true')
    expect(config.globalTimeout).toBe(180_000)
    expect(config.globalTimeout).toBeLessThan(4 * 60 * 1000)
  })

  it('주입된 origin의 globalTimeout이 통합 job의 Playwright 단계 timeout(8분)보다 짧다', async () => {
    const config = await loadConfig('true', 'http://127.0.0.1:18080')
    expect(config.globalTimeout).toBe(420_000)
    expect(config.globalTimeout).toBeLessThan(8 * 60 * 1000)
  })
})

describe('playwright.config.ts의 CI 해석', () => {
  const truthy = ['true', '1', 'TRUE', 'yes']
  const falsy = [undefined, '', ' ', '0', 'false', 'False']

  it.each(truthy)('CI=%j면 forbidOnly가 켜지고 worker가 1개다', async (value) => {
    const config = await loadConfig(value)
    expect(config.forbidOnly).toBe(true)
    expect(config.workers).toBe(1)
  })

  it.each(falsy)('CI=%j면 forbidOnly가 꺼지고 worker 수는 Playwright 기본값이다', async (value) => {
    const config = await loadConfig(value)
    expect(config.forbidOnly).toBe(false)
    expect(config.workers).toBeUndefined()
  })
})

describe('playwright.config.ts의 origin 주입', () => {
  it('주입이 없으면 preview 서버를 직접 띄우고 4183을 본다', async () => {
    const config = await loadConfig('true', undefined)

    expect(config.use?.baseURL).toBe('http://127.0.0.1:4183')
    expect(config.webServer).toBeDefined()
    expect(config.webServer).toMatchObject({
      url: 'http://127.0.0.1:4183',
      // 재사용하면 낡은 `dist/`를 검증하게 된다. 이 값은 항상 false다.
      reuseExistingServer: false,
    })
    expect(JSON.stringify(config.webServer)).toContain('npm run preview')
  })

  it('주입된 origin이 있으면 자체 서버를 띄우지 않는다', async () => {
    const config = await loadConfig('true', 'http://127.0.0.1:18080')

    expect(config.use?.baseURL).toBe('http://127.0.0.1:18080')
    // 서버를 띄우면 Nginx가 아니라 그 preview를 검증하면서 green이 난다.
    expect(config.webServer).toBeUndefined()
  })

  it('공백만 든 주입값은 주입이 없는 것으로 본다', async () => {
    const config = await loadConfig('true', '   ')

    expect(config.use?.baseURL).toBe('http://127.0.0.1:4183')
    expect(config.webServer).toBeDefined()
  })

  it('주입값의 앞뒤 공백을 제거한다', async () => {
    const config = await loadConfig('true', '  http://127.0.0.1:18080  ')

    expect(config.use?.baseURL).toBe('http://127.0.0.1:18080')
    expect(config.webServer).toBeUndefined()
  })

  it('주입 여부와 무관하게 재시도·trace 정책은 같다', async () => {
    const injected = await loadConfig('true', 'http://127.0.0.1:18080')

    expect(injected.retries).toBe(0)
    expect(injected.use?.trace).toBe('retain-on-failure')
  })
})

/*
 * 로컬 실행은 실행마다 새 출력 디렉터리를 쓴다(Epic 4 회고 AI-6, `deferred-work.md` 155). Playwright는 실행을 시작할 때
 * `outputDir`을 통째로 지우므로, 한 디렉터리를 계속 쓰면 앞선 실패의 trace가 다음 실행에 사라진다. CI의 경로와 artifact는
 * `ci.yml`이 소유한다.
 */
const RUN_DIR = /^test-results\/\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}-\d{3}Z$/
const STAMP = '2026-10-17T03-04-05-678Z'

describe('playwright.config.ts의 실패 진단물 위치', () => {
  it('CI는 지금 경로 그대로 쓰고 보존 정리를 걸지 않는다', async () => {
    setEnv('BOKRILOG_E2E_RUN', undefined)
    const config = await loadConfig('true')

    expect(config.outputDir).toBe('test-results')
    expect(config.globalSetup).toBeUndefined()
    expect(process.env.BOKRILOG_E2E_RUN).toBeUndefined()
  })

  it('로컬 주 프로세스는 실행 표지 디렉터리를 쓰고 worker가 물려받을 표지를 환경에 둔다', async () => {
    setEnv('TEST_WORKER_INDEX', undefined)
    setEnv('BOKRILOG_E2E_RUN', undefined)
    setEnv('PLAYWRIGHT_LAST_RUN_OUTPUT_FILE', undefined)
    const config = await loadConfig(undefined)

    expect(config.outputDir).toMatch(RUN_DIR)
    expect(config.globalSetup).toBe('./e2e/support/runOutput.ts')
    expect(config.outputDir).toBe(`test-results/${String(process.env.BOKRILOG_E2E_RUN)}`)
    // `--last-failed`의 기록은 실행 디렉터리가 아니라 뿌리에 둔다.
    expect(process.env.PLAYWRIGHT_LAST_RUN_OUTPUT_FILE).toMatch(
      /test-results[\\/]\.last-run\.json$/,
    )
  })

  it('주 프로세스는 환경에 남은 표지를 믿지 않고 실행마다 새로 정한다', async () => {
    // 셸에 export돼 있거나 UI 모드가 한 프로세스에서 다시 읽어도 앞 실행의 디렉터리를 쓰지 않는다.
    setEnv('TEST_WORKER_INDEX', undefined)
    setEnv('BOKRILOG_E2E_RUN', STAMP)
    const config = await loadConfig(undefined)

    expect(config.outputDir).toMatch(RUN_DIR)
    expect(config.outputDir).not.toBe(`test-results/${STAMP}`)
  })

  it('worker는 주 프로세스가 둔 표지를 물려받아 같은 디렉터리를 쓴다', async () => {
    setEnv('TEST_WORKER_INDEX', '0')
    setEnv('BOKRILOG_E2E_RUN', STAMP)
    const config = await loadConfig(undefined)

    expect(config.outputDir).toBe(`test-results/${STAMP}`)
  })

  it('worker라도 표지 모양이 아닌 값은 물려받지 않는다', async () => {
    setEnv('TEST_WORKER_INDEX', '0')
    setEnv('BOKRILOG_E2E_RUN', '../elsewhere')
    const config = await loadConfig(undefined)

    expect(config.outputDir).not.toContain('elsewhere')
    expect(config.outputDir).toMatch(RUN_DIR)
  })

  it('`--last-failed` 기록 위치는 준 값을 두고, 빈 문자열은 주지 않은 것으로 본다', async () => {
    setEnv('PLAYWRIGHT_LAST_RUN_OUTPUT_FILE', 'custom/last.json')
    await loadConfig(undefined)
    expect(process.env.PLAYWRIGHT_LAST_RUN_OUTPUT_FILE).toBe('custom/last.json')

    setEnv('PLAYWRIGHT_LAST_RUN_OUTPUT_FILE', '')
    await loadConfig(undefined)
    expect(process.env.PLAYWRIGHT_LAST_RUN_OUTPUT_FILE).toMatch(
      /test-results[\\/]\.last-run\.json$/,
    )
  })
})

describe('로컬 실행 디렉터리의 보존 상한', () => {
  const runs = [
    '2026-10-10T00-00-00-000Z',
    '2026-10-11T00-00-00-000Z',
    '2026-10-12T00-00-00-000Z',
    '2026-10-13T00-00-00-000Z',
    '2026-10-14T00-00-00-000Z',
    '2026-10-15T00-00-00-000Z',
    '2026-10-16T00-00-00-000Z',
  ]
  const current = '2026-10-17T00-00-00-000Z'
  const legacy = 'ad-consent-legacy-chromium'
  const roots: string[] = []

  /** 실행 디렉터리 일곱·이번 실행·이 규칙 이전의 시험별 디렉터리·`.last-run.json`을 둔 뿌리. */
  function seededRoot(): string {
    const root = mkdtempSync(join(tmpdir(), 'bokrilog-e2e-runs-'))
    roots.push(root)
    for (const name of [...runs, current, legacy]) {
      mkdirSync(join(root, name))
      writeFileSync(join(root, name, 'trace.zip'), '')
    }
    writeFileSync(join(root, '.last-run.json'), '{}')
    return root
  }

  /** globalSetup이 받는 것과 같은 모양 — 첫 project의 절대 `outputDir`. */
  function fullConfig(projects: { outputDir: string }[]): FullConfig {
    return { projects } as unknown as FullConfig
  }

  const kept = [...runs.slice(runs.length - (KEPT_RUNS - 1)), current, legacy, '.last-run.json']

  afterEach(() => {
    for (const root of roots.splice(0)) {
      chmodSync(root, 0o755)
      rmSync(root, { recursive: true, force: true })
    }
    vi.restoreAllMocks()
  })

  it('이번 실행을 포함해 최근 실행만 남기고 표지 모양이 아닌 항목은 건드리지 않는다', () => {
    const root = seededRoot()

    const removed = pruneRuns(root, current)

    expect(KEPT_RUNS).toBe(5)
    expect(removed.sort()).toEqual(runs.slice(0, runs.length - (KEPT_RUNS - 1)))
    expect(readdirSync(root).sort()).toEqual([...kept].sort())
  })

  it('globalSetup은 실제 설정 모양의 첫 project 출력 디렉터리 형제를 상한까지 줄인다', () => {
    const root = seededRoot()

    globalSetup(fullConfig([{ outputDir: join(root, current) }]))

    expect(readdirSync(root).sort()).toEqual([...kept].sort())
  })

  it('globalSetup은 project가 없거나 출력 디렉터리가 실행 표지가 아니면 아무것도 지우지 않는다', () => {
    const root = seededRoot()
    const before = readdirSync(root).sort()

    globalSetup(fullConfig([]))
    globalSetup(fullConfig([{ outputDir: join(root, legacy) }]))
    globalSetup(fullConfig([{ outputDir: root }]))

    expect(readdirSync(root).sort()).toEqual(before)
  })

  it('뿌리가 없으면(첫 실행) 경고 없이 아무것도 지우지 않는다', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)

    expect(pruneRuns(join(tmpdir(), 'bokrilog-e2e-runs-missing', 'nowhere'), current)).toEqual([])
    expect(warn).not.toHaveBeenCalled()
  })

  it('뿌리를 읽지 못하면 경고 한 줄만 남기고 실행을 막지 않는다', () => {
    const root = seededRoot()
    const file = join(root, '.last-run.json')
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)

    expect(pruneRuns(file, current)).toEqual([])
    expect(warn).toHaveBeenCalledTimes(1)
    expect(String(warn.mock.calls[0]?.[0])).toContain('ENOTDIR')
  })

  // 권한으로 지우기를 막는 재현은 POSIX의 비root 사용자에서만 성립한다(root는 권한을 무시하고, Windows는 모드를 보지 않는다).
  it.skipIf(process.platform === 'win32' || process.getuid?.() === 0)(
    '지난 실행을 지우지 못하면 그 디렉터리마다 경고 한 줄만 남기고 나머지를 이어 간다',
    () => {
      const root = seededRoot()
      chmodSync(root, 0o555)
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)

      expect(pruneRuns(root, current)).toEqual([])
      expect(warn).toHaveBeenCalledTimes(runs.length - (KEPT_RUNS - 1))
      chmodSync(root, 0o755)
      expect(readdirSync(root)).toHaveLength(runs.length + 3)
    },
  )
})
