/**
 * `playwright.config.ts` 계약 시험.
 *
 * 왜 있는가: CI-PLAN §6은 필수 Playwright 실행의 `retries: 0`과 `trace: 'retain-on-failure'`를
 * "한 묶음"으로 못박았다. `on-first-retry`인 채로 retry만 0이 되면 trace가 하나도 남지 않고,
 * retry가 되살아나면 flaky 실패가 green으로 덮인다. 두 값은 설정 파일 한 줄로 조용히 뒤집히므로
 * 시험으로 고정한다. `globalTimeout`은 CI의 Playwright 단계 timeout(preview 4분, 주입된 origin
 * 6분)보다 짧아야 실패 진단 artifact를 올릴 시간이 남는다.
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
import { afterEach, describe, expect, it, vi } from 'vitest'

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

/**
 * `undefined`면 지우고, 값이 있으면 설정한다. 빈 문자열도 의미가 있는 값이라 그대로 넣는다.
 * 설정 파일이 두 변수를 각각 다르게 읽으므로 이름을 인자로 받아 한 함수로 다룬다.
 */
function setEnv(name: 'CI' | 'PLAYWRIGHT_BASE_URL', value: string | undefined): void {
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

  it('주입된 origin의 globalTimeout이 통합 job의 Playwright 단계 timeout(6분)보다 짧다', async () => {
    const config = await loadConfig('true', 'http://127.0.0.1:18080')
    expect(config.globalTimeout).toBe(300_000)
    expect(config.globalTimeout).toBeLessThan(6 * 60 * 1000)
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
