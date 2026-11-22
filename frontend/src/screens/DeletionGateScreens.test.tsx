import type { ReactElement } from 'react'

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { onCLS, onINP, onLCP, type Metric } from 'web-vitals'

import { showInterstitial } from '../ads/gptAdapter'
import {
  ACCOUNT_DELETION_PATH,
  ACCOUNT_DELETION_POLICY_VERSION,
  accountDeletionContent,
} from '../content/accountDeletion'
import accountDeletionPolicy from '../content/accountDeletionPolicy.fixture.json'
import { AD_TRANSITION_PATH } from '../content/adTransition'
import {
  authContent,
  DASHBOARD_PATH,
  LOGOUT_ENDPOINT,
  ONBOARDING_PATH,
  OPERATOR_PATH,
  SESSION_ENDPOINT,
  SETTINGS_PATH,
} from '../content/auth'
import { planConditionContent } from '../content/planConditions'
import { navigateTo, replaceLocation } from '../session/navigation'
import { FIRST_MOUNT_TIMEOUT_MS } from '../test/firstMount'
import {
  getObservedAllowedRequests,
  getObservedNoticeRequests,
  getObservedRequests,
  getObservedTelemetryRequests,
  stubAccountDeletionState,
  stubLogoutRequest,
  stubSessionRequest,
} from '../test/setup'
import { AdTransitionScreen } from './AdTransitionScreen'
import { OnboardingScreen } from './OnboardingScreen'
import { OperatorScreen } from './OperatorScreen'
import { PlanConditionScreen } from './PlanConditionScreen'
import { SettingsScreen } from './SettingsScreen'
import { SignedInScreen } from './SignedInScreen'

/*
 * 삭제 진행 게이트(Story 5.1)가 모든 인증 화면에서 같게 동작한다 — 진행 화면만 그리고, 다른 조회를 시작하지 않고, 서버가 고른
 * 목적지로 밀어내지 않으며(`!deleting` 가드), 로그아웃할 수 있다. 중립 전환 화면은 소비 요청 없이 결과 화면으로 간다.
 */

vi.mock('../session/navigation', () => ({
  navigateTo: vi.fn(),
  replaceAddress: vi.fn(),
  replaceLocation: vi.fn(),
}))
vi.mock('../ads/gptAdapter', () => ({ showInterstitial: vi.fn() }))
// jsdom에는 성능 관측이 없다 — 계측 화면의 Vitals 보고는 이 대역의 콜백으로 만든다(`telemetry/screens.test.tsx`와 같은 방식).
vi.mock('web-vitals', () => ({ onCLS: vi.fn(), onINP: vi.fn(), onLCP: vi.fn() }))

const CSRF_TOKEN = 'deletion-gate-csrf-token'

function reportLcp(value: number): void {
  const { calls } = (onLCP as unknown as { mock: { calls: unknown[][] } }).mock
  const callback = calls[0]?.[0] as ((metric: Metric) => void) | undefined
  if (callback === undefined) throw new Error('LCP was not registered')
  callback({ name: 'LCP', value } as Metric)
}

function hide(): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
  document.dispatchEvent(new Event('visibilitychange'))
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function progressState(): Promise<Response> {
  return Promise.resolve(
    json({
      available: true,
      policy: {
        version: accountDeletionPolicy.version,
        targets: accountDeletionPolicy.targets,
        backupDestroyDays: accountDeletionPolicy.backupDestroyDays,
        legalRetention: accountDeletionPolicy.legalRetention,
      },
      request: {
        requestId: 'del_gate',
        requestedAt: '2026-11-05T01:02:03.456Z',
        state: 'DELETION_IN_PROGRESS',
        policyVersion: ACCOUNT_DELETION_POLICY_VERSION,
        progress: { status: 'IN_PROGRESS', failedSteps: [] },
      },
    }),
  )
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  vi.mocked(navigateTo).mockClear()
  vi.mocked(showInterstitial).mockReset()
  vi.mocked(onLCP).mockReset()
  vi.mocked(onINP).mockReset()
  vi.mocked(onCLS).mockReset()
  // 서버가 고른 목적지는 대시보드다 — 온보딩 화면은 게이트 가드가 없으면 그리로 밀려난다.
  stubSessionRequest(() =>
    Promise.resolve(
      json({
        authenticated: true,
        destination: 'dashboard',
        csrfToken: CSRF_TOKEN,
        deletionInProgress: true,
      }),
    ),
  )
  stubAccountDeletionState(progressState)
})

const SCREENS: readonly (readonly [string, string, () => ReactElement])[] = [
  ['온보딩', ONBOARDING_PATH, () => <OnboardingScreen />],
  ['운영자', OPERATOR_PATH, () => <OperatorScreen />],
  ['계획 조건', planConditionContent.path, () => <PlanConditionScreen />],
]

describe('삭제 진행 게이트', () => {
  for (const [name, path, screenOf] of SCREENS) {
    it(`${name} 화면도 진행 화면만 그리고, 다른 조회·이동 없이 로그아웃할 수 있다`, async () => {
      window.history.pushState({}, '', path)
      stubLogoutRequest(() => Promise.resolve(new Response(null, { status: 204 })))
      render(screenOf())

      expect(
        await screen.findByRole(
          'heading',
          { name: accountDeletionContent.progress.title },
          { timeout: FIRST_MOUNT_TIMEOUT_MS },
        ),
      ).toBeInTheDocument()
      await screen.findByText(ACCOUNT_DELETION_POLICY_VERSION)
      expect(getObservedAllowedRequests()).toEqual([
        `GET ${SESSION_ENDPOINT}`,
        `GET ${ACCOUNT_DELETION_PATH}`,
      ])
      expect(getObservedNoticeRequests()).toEqual([])
      expect(getObservedRequests()).toEqual([])
      // 서버가 고른 목적지(대시보드)로 밀어내지 않는다.
      expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()

      fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))
      await waitFor(() => {
        expect(getObservedAllowedRequests()).toContain(`POST ${LOGOUT_ENDPOINT}`)
      })
      expect(getObservedTelemetryRequests()).toEqual([])
    })
  }

  /*
   * 계측 화면 셋. 계측 훅은 게이트보다 먼저 마운트에서 Vitals를 등록하므로(Story 4.5 — 세션 확인 전에 등록해야 첫 LCP를 놓치지
   * 않는다) 등록 자체는 일어난다. 그래서 측정값 하나를 실제로 보고하게 한 뒤 숨김·`pagehide`를 일으켜, 보낼 것이 있어도 삭제 진행
   * 게이트에서는 beacon이 나가지 않음(세션 토큰이 계측에 넘어가지 않는다)을 본다.
   */
  const TELEMETRY_SCREENS: readonly (readonly [string, string, () => ReactElement])[] = [
    ['대시보드', DASHBOARD_PATH, () => <SignedInScreen />],
    ['설정', SETTINGS_PATH, () => <SettingsScreen />],
    ['계획 조건', planConditionContent.path, () => <PlanConditionScreen />],
  ]

  for (const [name, path, screenOf] of TELEMETRY_SCREENS) {
    it(`${name} 화면은 삭제 진행 게이트에서 보고된 Vitals가 있어도 숨김·떠남에 계측을 보내지 않는다`, async () => {
      window.history.pushState({}, '', path)
      render(screenOf())
      expect(
        await screen.findByRole(
          'heading',
          { name: accountDeletionContent.progress.title },
          { timeout: FIRST_MOUNT_TIMEOUT_MS },
        ),
      ).toBeInTheDocument()
      await screen.findByText(ACCOUNT_DELETION_POLICY_VERSION)

      // 보낼 측정값을 실제로 만든다 — 이것이 없으면 "요청 0건"은 아무것도 증명하지 않는다.
      expect(onLCP).toHaveBeenCalledTimes(1)
      reportLcp(1700)
      hide()
      window.dispatchEvent(new Event('pagehide'))
      await new Promise((resolve) => setTimeout(resolve, 0))

      expect(getObservedTelemetryRequests()).toEqual([])
      expect(getObservedRequests()).toEqual([])
    })
  }

  it('중립 전환 화면은 소비 요청 없이 곧바로 결과 화면으로 간다', async () => {
    window.history.pushState({}, '', `${AD_TRANSITION_PATH}?t=${'Zy'.repeat(21)}-`)
    render(<AdTransitionScreen />)

    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(DASHBOARD_PATH)
    })
    expect(vi.mocked(replaceLocation)).toHaveBeenCalledTimes(1)
    expect(getObservedAllowedRequests()).toEqual([])
    expect(getObservedRequests()).toEqual([])
    expect(vi.mocked(showInterstitial)).not.toHaveBeenCalled()
  })
})
