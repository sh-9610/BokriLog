import { describe, expect, it } from 'vitest'

import {
  getObservedRequests,
  getObservedStorageEntries,
  getObservedTelemetryRequests,
  stubTelemetry,
} from '../test/setup'
import {
  flushTelemetry,
  MAX_BATCH_EVENTS,
  recordAdEvent,
  recordVital,
  setTelemetryToken,
  TELEMETRY_PATH,
} from './beacon'

/**
 * Story 4.5: 동일 출처 계측 beacon. 어휘·묶음 크기·보내는 시점의 조건(토큰)·실패 무영향을 본다. 서버가 같은 어휘로 본문을 검사하고
 * 어휘 밖이면 묶음 전체를 거절하므로, 여기서 만드는 본문은 언제나 서버 어휘 안이어야 한다.
 */

const TOKEN = 'telemetry-csrf-token'

/** 지금까지 보낸 beacon들의 이벤트를 순서대로 편다. */
function sentEvents(): unknown[] {
  return getObservedTelemetryRequests().flatMap(
    (request) => (request.body as { events: unknown[] }).events,
  )
}

describe('계측 beacon', () => {
  it('경로는 서버 컨트롤러와 같고 묶음 상한은 16건이다', () => {
    expect(TELEMETRY_PATH).toBe('/api/engagement/telemetry')
    expect(MAX_BATCH_EVENTS).toBe(16)
  })

  it('토큰이 없으면 보내지 않고 큐에 둔다 — 토큰이 생긴 뒤 한 번에 보낸다', () => {
    recordVital('LCP', '/dashboard', 1200)
    flushTelemetry()
    expect(getObservedTelemetryRequests()).toEqual([])

    setTelemetryToken(TOKEN)
    flushTelemetry()
    const [request] = getObservedTelemetryRequests()
    expect(getObservedTelemetryRequests()).toHaveLength(1)
    expect(request).toEqual({
      body: {
        events: [{ kind: 'VITAL', metric: 'LCP', route: '/dashboard', value: 1200, ad: false }],
      },
      keepalive: true,
      csrfToken: TOKEN,
    })
    // 보낸 이벤트는 큐에서 빠진다 — 두 번째 flush는 아무것도 보내지 않는다(재시도 없음).
    flushTelemetry()
    expect(getObservedTelemetryRequests()).toHaveLength(1)
    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('광고 이벤트는 이벤트마다 한 번이고, REQUESTED가 기록된 문서의 Vitals는 그 전에 보고된 것도 보낼 때 ad=true다', () => {
    setTelemetryToken(TOKEN)
    recordVital('CLS', '/ad-transition', 0.02)
    recordAdEvent('REQUESTED')
    recordAdEvent('REQUESTED')
    recordAdEvent('READY')
    recordAdEvent('FAILED', 'NO_FILL')
    recordAdEvent('FAILED', 'TIMEOUT')
    recordAdEvent('RESULT')
    recordVital('LCP', '/ad-transition', 2600)
    flushTelemetry()

    // SHOW 응답 전 키 입력으로 먼저 확정된 CLS도 광고 문서의 값이다(ad는 보낼 때 정한다).
    expect(sentEvents()).toEqual([
      { kind: 'VITAL', metric: 'CLS', route: '/ad-transition', value: 0.02, ad: true },
      { kind: 'AD', event: 'REQUESTED' },
      { kind: 'AD', event: 'READY' },
      { kind: 'AD', event: 'FAILED', reason: 'NO_FILL' },
      { kind: 'AD', event: 'RESULT' },
      { kind: 'VITAL', metric: 'LCP', route: '/ad-transition', value: 2600, ad: true },
    ])
  })

  it('0 이상 유한수가 아닌 값은 버린다 — 서버가 묶음 전체를 거절하지 않게', () => {
    setTelemetryToken(TOKEN)
    recordVital('INP', '/settings', Number.NaN)
    recordVital('INP', '/settings', Number.POSITIVE_INFINITY)
    recordVital('INP', '/settings', -1)
    recordVital('INP', '/settings', 0)
    flushTelemetry()

    expect(sentEvents()).toEqual([
      { kind: 'VITAL', metric: 'INP', route: '/settings', value: 0, ad: false },
    ])
  })

  it('같은 metric의 재보고는 무시한다 — 문서당 metric마다 첫 보고 하나', () => {
    setTelemetryToken(TOKEN)
    recordVital('CLS', '/dashboard', 0.05)
    recordVital('CLS', '/dashboard', 0.3)
    recordVital('LCP', '/dashboard', 1000)
    flushTelemetry()
    recordVital('LCP', '/dashboard', 9000)
    flushTelemetry()

    expect(sentEvents()).toEqual([
      { kind: 'VITAL', metric: 'CLS', route: '/dashboard', value: 0.05, ad: false },
      { kind: 'VITAL', metric: 'LCP', route: '/dashboard', value: 1000, ad: false },
    ])
    expect(getObservedTelemetryRequests()).toHaveLength(1)
  })

  it('광고를 시도하지 않은 문서의 Vitals는 ad=false다', () => {
    setTelemetryToken(TOKEN)
    recordVital('LCP', '/ad-transition', 1200)
    recordAdEvent('RESULT')
    flushTelemetry()

    expect(sentEvents()[0]).toEqual({
      kind: 'VITAL',
      metric: 'LCP',
      route: '/ad-transition',
      value: 1200,
      ad: false,
    })
  })

  it('문서 하나가 만들 수 있는 모든 이벤트는 한 묶음(16건·8 KiB 이하)에 든다', () => {
    setTelemetryToken(TOKEN)
    for (const metric of ['LCP', 'INP', 'CLS'] as const) {
      recordVital(metric, '/plan-conditions', 123_456.789)
    }
    recordAdEvent('REQUESTED')
    recordAdEvent('READY')
    recordAdEvent('CLOSED')
    recordAdEvent('FAILED', 'WINDOW_EXPIRED')
    recordAdEvent('RESULT')
    flushTelemetry()

    expect(getObservedTelemetryRequests()).toHaveLength(1)
    const [request] = getObservedTelemetryRequests()
    expect((request?.body as { events: unknown[] }).events.length).toBeLessThanOrEqual(
      MAX_BATCH_EVENTS,
    )
    expect(new TextEncoder().encode(JSON.stringify(request?.body)).length).toBeLessThanOrEqual(
      8 * 1024,
    )
  })

  it('전송 실패(네트워크 오류)와 거절은 던지지 않고 다시 보내지 않는다', async () => {
    setTelemetryToken(TOKEN)
    stubTelemetry(() => Promise.reject(new TypeError('network down')))
    recordAdEvent('REQUESTED')
    expect(() => {
      flushTelemetry()
    }).not.toThrow()
    await Promise.resolve()
    flushTelemetry()
    expect(getObservedTelemetryRequests()).toHaveLength(1)

    stubTelemetry(() => Promise.resolve(new Response(null, { status: 400 })))
    recordAdEvent('RESULT')
    flushTelemetry()
    await Promise.resolve()
    flushTelemetry()
    expect(getObservedTelemetryRequests()).toHaveLength(2)
    expect(getObservedRequests()).toEqual([])
  })
})
