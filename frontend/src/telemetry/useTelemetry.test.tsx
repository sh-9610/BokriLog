import { render } from '@testing-library/react'
import { StrictMode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { onCLS, onINP, onLCP, type Metric } from 'web-vitals'

import { getObservedRequests, getObservedTelemetryRequests } from '../test/setup'
import { type TelemetryRoute } from './beacon'
import { useTelemetry } from './useTelemetry'

vi.mock('web-vitals', () => ({ onCLS: vi.fn(), onINP: vi.fn(), onLCP: vi.fn() }))

/**
 * Story 4.5: 화면 넷이 부르는 계측 훅. `web-vitals`는 대역이다 — jsdom에는 성능 관측이 없다. 훅이 문서당 한 번 등록하는지, 보고된
 * 값을 그 화면 경로로 쌓는지, 숨김·떠남에서만 보내는지, 토큰이 없으면 보내지 않는지를 본다.
 */

const TOKEN = 'telemetry-hook-token'

function Probe({
  route,
  token,
}: {
  readonly route: TelemetryRoute
  readonly token: string | null
}): null {
  useTelemetry(route, token)
  return null
}

/** 등록된 보고 함수 하나에 값을 보낸다. */
function report(register: unknown, name: Metric['name'], value: number): void {
  const { calls } = (register as { mock: { calls: unknown[][] } }).mock
  const callback = calls[0]?.[0] as ((metric: Metric) => void) | undefined
  if (callback === undefined) throw new Error(`${name}가 등록되지 않았다.`)
  callback({ name, value } as Metric)
}

function hide(): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
  document.dispatchEvent(new Event('visibilitychange'))
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
}

beforeEach(() => {
  vi.mocked(onLCP).mockReset()
  vi.mocked(onINP).mockReset()
  vi.mocked(onCLS).mockReset()
})

describe('계측 훅', () => {
  it('문서당 한 번 LCP·INP·CLS를 등록한다(StrictMode·다시 그리기에도)', () => {
    const { rerender } = render(
      <StrictMode>
        <Probe route="/dashboard" token={null} />
      </StrictMode>,
    )
    rerender(
      <StrictMode>
        <Probe route="/dashboard" token={TOKEN} />
      </StrictMode>,
    )

    expect(onLCP).toHaveBeenCalledTimes(1)
    expect(onINP).toHaveBeenCalledTimes(1)
    expect(onCLS).toHaveBeenCalledTimes(1)
  })

  it('숨김(visibilitychange hidden)과 떠남(pagehide)에서 그 화면 경로의 값을 보낸다 — 보이는 동안은 보내지 않는다', () => {
    render(<Probe route="/settings" token={TOKEN} />)
    report(onLCP, 'LCP', 1800)
    report(onCLS, 'CLS', 0.12)

    document.dispatchEvent(new Event('visibilitychange'))
    expect(getObservedTelemetryRequests()).toEqual([])

    hide()
    expect(getObservedTelemetryRequests()).toHaveLength(1)
    expect(getObservedTelemetryRequests()[0]?.body).toEqual({
      events: [
        { kind: 'VITAL', metric: 'LCP', route: '/settings', value: 1800, ad: false },
        { kind: 'VITAL', metric: 'CLS', route: '/settings', value: 0.12, ad: false },
      ],
    })

    report(onINP, 'INP', 240)
    window.dispatchEvent(new Event('pagehide'))
    expect(getObservedTelemetryRequests()).toHaveLength(2)
    expect(getObservedTelemetryRequests()[1]?.body).toEqual({
      events: [{ kind: 'VITAL', metric: 'INP', route: '/settings', value: 240, ad: false }],
    })
    expect(getObservedRequests()).toEqual([])
  })

  it('세션 토큰이 없으면(미인증·확인 전) 숨김·떠남에도 보내지 않는다', () => {
    render(<Probe route="/plan-conditions" token={null} />)
    report(onLCP, 'LCP', 900)

    hide()
    window.dispatchEvent(new Event('pagehide'))

    expect(getObservedTelemetryRequests()).toEqual([])
    expect(getObservedRequests()).toEqual([])
  })

  it('측정을 지원하지 않는 환경이어도(등록이 던져도) 화면은 그대로다', () => {
    vi.mocked(onLCP).mockImplementation(() => {
      throw new Error('unsupported')
    })

    expect(() => render(<Probe route="/dashboard" token={TOKEN} />)).not.toThrow()
  })
})
