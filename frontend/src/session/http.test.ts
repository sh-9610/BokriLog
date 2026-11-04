import { afterEach, describe, expect, it, vi } from 'vitest'

import { CSRF_HEADER } from '../content/auth'
import { objectOf, postJson, postNoContent, readJson } from './http'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/*
 * 동일 출처 POST·JSON 판독 helper의 계약(spec-frontend-request-commons.md 매트릭스)과 재발 방지 검사.
 *
 * 시험 값은 리터럴 토큰 모양을 피한다 — gitleaks가 PR 커밋 범위를 훑는다.
 */

const TOKEN = ['csrf', 'value'].join('-')
const KEY = ['idem', 'key'].join('-')

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

/** 운영 소스 전체(시험·시험 지원 파일 제외). 키는 이 파일 기준 상대 경로다. */
const SOURCES: Readonly<Record<string, string>> = import.meta.glob(
  ['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}', '!../test/**'],
  { query: '?raw', import: 'default', eager: true },
)

function filesMatching(pattern: RegExp): string[] {
  return Object.entries(SOURCES)
    .filter(([, text]) => pattern.test(text))
    .map(([path]) => path)
    .sort()
}

describe('postJson', () => {
  it('본문·멱등 키·시한이 있으면 헤더 넷과 본문 문자열 하나를 싣는다', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const controller = new AbortController()

    const response = await postJson('/api/example', {
      csrfToken: TOKEN,
      body: { choice: 'GRANTED', nested: { order: [2, 1] } },
      idempotencyKey: KEY,
      signal: controller.signal,
    })

    expect(response.status).toBe(200)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [path, init] = fetchMock.mock.calls[0] ?? []
    expect(path).toBe('/api/example')
    expect(init).toEqual({
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: {
        Accept: 'application/json',
        [CSRF_HEADER]: TOKEN,
        'Content-Type': 'application/json',
        'Idempotency-Key': KEY,
      },
      body: JSON.stringify({ choice: 'GRANTED', nested: { order: [2, 1] } }),
      signal: controller.signal,
    })
  })

  it('본문이 없으면 Content-Type도 본문도 없고, 멱등 키·시한이 없으면 그 둘도 없다', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await postJson('/api/example', { csrfToken: TOKEN })

    const init = fetchMock.mock.calls[0]?.[1]
    expect(init).toBeDefined()
    expect(Object.keys(init ?? {}).sort()).toEqual(['cache', 'credentials', 'headers', 'method'])
    expect(init?.headers).toEqual({ Accept: 'application/json', [CSRF_HEADER]: TOKEN })
  })

  it('본문이 null이면 본문 없음과 같다 — 문자열 "null"을 보내지 않는다', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await postJson('/api/example', { csrfToken: TOKEN, body: null })

    const init = fetchMock.mock.calls[0]?.[1]
    expect(init?.body).toBeUndefined()
    expect(init?.headers).toEqual({ Accept: 'application/json', [CSRF_HEADER]: TOKEN })
  })

  it('직렬화할 수 없는 본문은 보내지 않고 거절로 나간다', async () => {
    const fetchMock = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetchMock)

    const sent = postJson('/api/example', { csrfToken: TOKEN, body: { amount: 1n } })

    await expect(sent).rejects.toThrow(TypeError)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('호출 시점의 전역 fetch를 부르고 네트워크 거절을 그대로 넘긴다', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline')))

    await expect(postJson('/api/example', { csrfToken: TOKEN })).rejects.toThrow('offline')
  })
})

describe('readJson', () => {
  it('JSON 본문을 읽는다', async () => {
    expect(await readJson(new Response('{"code":"X"}'))).toEqual({ code: 'X' })
  })

  it('깨진 JSON이나 빈 본문이면 예외 없이 null이다', async () => {
    expect(await readJson(new Response('{'))).toBeNull()
    expect(await readJson(new Response(null, { status: 204 }))).toBeNull()
    expect(await readJson(new Response(''))).toBeNull()
  })
})

describe('objectOf', () => {
  it('배열이 아닌 객체만 그대로 돌려준다', () => {
    const value = { code: 'X' }
    expect(objectOf(value)).toBe(value)
  })

  it.each([[[]], [null], [undefined], [1], ['text'], [true]])('비객체 %j는 null이다', (value) => {
    expect(objectOf(value)).toBeNull()
  })
})

describe('postNoContent', () => {
  it('토큰이 없으면 보내지 않고 거짓이다', async () => {
    const fetchMock = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetchMock)

    expect(await postNoContent('/api/example', null)).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('204만 참이고 본문 없는 POST 하나를 보낸다', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    expect(await postNoContent('/api/example', TOKEN)).toBe(true)
    const init = fetchMock.mock.calls[0]?.[1]
    expect(init?.method).toBe('POST')
    expect(init?.body).toBeUndefined()
    expect(init?.headers).toEqual({ Accept: 'application/json', [CSRF_HEADER]: TOKEN })
    expect(init?.signal).toBeInstanceOf(AbortSignal)
  })

  it.each([[200], [403], [404], [500]])('%i는 거짓이다', async (status) => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status })))

    expect(await postNoContent('/api/example', TOKEN)).toBe(false)
  })

  it('네트워크 오류는 거짓이다', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline')))

    expect(await postNoContent('/api/example', TOKEN)).toBe(false)
  })

  it('시한(SESSION_REQUEST_TIMEOUT_MS)이 지나면 중단하고 거짓이다', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn<typeof fetch>(
      (_input, init) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => {
            reject(new DOMException('aborted', 'AbortError'))
          })
        }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const outcome = postNoContent('/api/example', TOKEN)
    await vi.advanceTimersByTimeAsync(SESSION_REQUEST_TIMEOUT_MS - 1)
    expect(fetchMock.mock.calls[0]?.[1]?.signal?.aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(1)

    expect(await outcome).toBe(false)
    expect(fetchMock.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
  })
})

describe('운영 소스의 재발 방지', () => {
  it('운영 소스를 훑는다', () => {
    expect(Object.keys(SOURCES)).toContain('./http.ts')
    expect(Object.keys(SOURCES)).toContain('../telemetry/beacon.ts')
    expect(Object.keys(SOURCES).some((path) => path.endsWith('.test.ts'))).toBe(false)
  })

  it("method: 'POST'는 http.ts와 telemetry/beacon.ts에만 있고 Request 객체로 우회하지 않는다", () => {
    expect(filesMatching(/method:\s*['"`]post['"`]/i)).toEqual([
      '../telemetry/beacon.ts',
      './http.ts',
    ])
    expect(filesMatching(/\bnew\s+Request\s*\(/)).toEqual([])
  })

  it('JSON 판독 helper 정의는 http.ts에만 있고 인라인 판독 사본도 없다', () => {
    expect(
      filesMatching(/(?:function\s+|(?:const|let|var)\s+)(?:readJson|jsonOf|objectOf|fieldsOf)\b/),
    ).toEqual(['./http.ts'])
    expect(filesMatching(/\.json\(\)\s*\.catch\(/)).toEqual([])
  })

  it('CSRF 헤더는 리터럴 하나(content/auth.ts)이고 요청에 싣는 곳은 http.ts와 beacon.ts뿐이다', () => {
    expect(filesMatching(/['"`]x-csrf-token['"`]/i)).toEqual(['../content/auth.ts'])
    expect(filesMatching(/import\s*\{[^}]*\bCSRF_HEADER\b[^}]*\}\s*from/)).toEqual([
      '../telemetry/beacon.ts',
      './http.ts',
    ])
  })
})
