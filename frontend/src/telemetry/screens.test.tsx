import { render, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { onCLS, onINP, onLCP, type Metric } from 'web-vitals'

import { OnboardingScreen } from '../screens/OnboardingScreen'
import { OperatorScreen } from '../screens/OperatorScreen'
import { PlanConditionScreen } from '../screens/PlanConditionScreen'
import { SettingsScreen } from '../screens/SettingsScreen'
import { SignedInScreen } from '../screens/SignedInScreen'
import {
  getObservedSessionRequests,
  getObservedTelemetryRequests,
  stubConnectionStatusRequest,
  stubDashboard,
  stubLinkStatusRequest,
  stubSessionRequest,
} from '../test/setup'

vi.mock('web-vitals', () => ({ onCLS: vi.fn(), onINP: vi.fn(), onLCP: vi.fn() }))
vi.mock('../session/navigation', () => ({
  navigateTo: vi.fn(),
  replaceAddress: vi.fn(),
  replaceLocation: vi.fn(),
}))

/**
 * Story 4.5: 화면별 계측 범위. 대시보드·설정·계획 조건은 자기 경로로 Web Vitals를 보내고, 온보딩·운영자 화면은 계측하지 않는다(요청
 * 0건). `web-vitals`는 대역이다 — jsdom에는 성능 관측이 없다. 세션 확인 뒤 그 화면이 다른 요청을 기다리는 동안 문서를 숨긴다.
 */

const TOKEN = 'screen-telemetry-token'

function session(destination: string): void {
  stubSessionRequest(() =>
    Promise.resolve(
      new Response(JSON.stringify({ authenticated: true, destination, csrfToken: TOKEN }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  )
}

/** 화면이 기다리는 그 밖의 요청은 답하지 않는다 — 이 시험은 계측만 본다. */
function pending(): Promise<Response> {
  return new Promise(() => undefined)
}

function reportLcp(value: number): void {
  const { calls } = (onLCP as unknown as { mock: { calls: unknown[][] } }).mock
  const callback = calls[0]?.[0] as ((metric: Metric) => void) | undefined
  callback?.({ name: 'LCP', value } as Metric)
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
  stubDashboard(pending)
  stubLinkStatusRequest(pending)
  stubConnectionStatusRequest(pending)
})

describe('화면별 계측', () => {
  it.each([
    ['대시보드', '/dashboard', 'dashboard', () => <SignedInScreen />],
    ['설정', '/settings', 'settings', () => <SettingsScreen />],
    ['계획 조건', '/plan-conditions', 'dashboard', () => <PlanConditionScreen />],
  ] as const)(
    '%s는 숨김에서 자기 경로의 Vitals를 보낸다',
    async (_name, route, destination, screen) => {
      session(destination)
      render(screen())
      expect(onLCP).toHaveBeenCalledTimes(1)
      reportLcp(1700)

      await waitFor(() => {
        hide()
        expect(getObservedTelemetryRequests()).toHaveLength(1)
      })
      expect(getObservedTelemetryRequests()[0]).toMatchObject({
        keepalive: true,
        csrfToken: TOKEN,
        body: { events: [{ kind: 'VITAL', metric: 'LCP', route, value: 1700, ad: false }] },
      })
    },
  )

  it.each([
    ['온보딩', 'onboarding', () => <OnboardingScreen />],
    ['운영자', 'operator', () => <OperatorScreen />],
  ] as const)('%s 화면은 계측하지 않는다(요청 0건)', async (_name, destination, screen) => {
    session(destination)
    render(screen())
    await waitFor(() => {
      expect(getObservedSessionRequests().length).toBeGreaterThan(0)
    })
    await new Promise((resolve) => setTimeout(resolve, 0))

    hide()
    window.dispatchEvent(new Event('pagehide'))

    expect(onLCP).not.toHaveBeenCalled()
    expect(getObservedTelemetryRequests()).toEqual([])
  })
})
