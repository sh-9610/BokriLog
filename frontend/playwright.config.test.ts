/**
 * `playwright.config.ts` 계약 시험.
 *
 * 왜 있는가: CI-PLAN §6은 필수 Playwright 실행의 `retries: 0`과 `trace: 'retain-on-failure'`를
 * "한 묶음"으로 못박았다. `on-first-retry`인 채로 retry만 0이 되면 trace가 하나도 남지 않고,
 * retry가 되살아나면 flaky 실패가 green으로 덮인다. 두 값은 설정 파일 한 줄로 조용히 뒤집히므로
 * 시험으로 고정한다. `globalTimeout`은 CI의 Playwright 단계 timeout(4분)보다 짧아야 실패 진단
 * artifact를 올릴 시간이 남는다.
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

const originalCi = process.env.CI

/** `CI` 값을 바꾼 뒤 설정 모듈을 다시 평가한다. 설정은 import 시점에 `CI`를 읽는다. */
async function loadConfig(ci: string | undefined) {
  if (ci === undefined) {
    delete process.env.CI
  } else {
    process.env.CI = ci
  }
  vi.resetModules()
  const loaded = await import('./playwright.config')
  return loaded.default
}

afterEach(() => {
  if (originalCi === undefined) {
    delete process.env.CI
  } else {
    process.env.CI = originalCi
  }
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

  it('globalTimeout이 CI의 Playwright 단계 timeout(4분)보다 짧다', async () => {
    const config = await loadConfig('true')
    expect(config.globalTimeout).toBe(180_000)
    expect(config.globalTimeout).toBeLessThan(4 * 60 * 1000)
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
