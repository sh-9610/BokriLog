import type { ComponentType } from 'react'

import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { authContent } from '../content/auth'
import { planCompletionContent } from '../content/planCompletion'
import { planResultContent } from '../content/planResult'
import { DASHBOARD_SYNC_PATH, planSyncContent } from '../content/planSync'
import { DASHBOARD_VERIFICATION_PATH, planVerificationContent } from '../content/planVerification'
import { formatSeoulTime } from '../format/decimal'
import { navigateTo, replaceLocation } from '../session/navigation'
import {
  completedDashboardFixture,
  completionRecordFixture,
  completionVersionResultFixture,
  dashboardFixture,
  SCHEDULED_END_AT,
  syncFixture,
  syncJobFixture,
  verificationJobFixture,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubDashboard,
  stubDashboardSync,
  stubDashboardVerification,
  stubSessionRequest,
} from '../test/setup'
import { OnboardingScreen } from './OnboardingScreen'
import { SignedInScreen } from './SignedInScreen'

/*
 * Story 2.14: both landing screens show a plan completed at its scheduled end in the panel's completion mode. The
 * entry sync runs on it too — only a sync fixes the first observation after the end — and a later sync changes only the
 * `종료 후 현재가치` region. The panel and the hook have their own tests.
 */

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const TOKEN = 'completed-csrf-token'

const FORBIDDEN =
  /실시간|정확|보장|성공|실패|축하|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/

const NOT_DUE = { outcome: 'NOT_DUE', job: null, retryAfterSeconds: 240 }

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function syncPosts(): number {
  return getObservedAllowedRequests().filter((entry) => entry === `POST ${DASHBOARD_SYNC_PATH}`)
    .length
}

/** Answers the reads in order; one more read fails the test. */
function inOrder(queue: (() => Promise<Response>)[]): () => Promise<Response> {
  return () => (queue.shift() ?? (() => Promise.reject(new Error('extra'))))()
}

const SCREENS: readonly (readonly [string, ComponentType, string])[] = [
  ['SignedInScreen', SignedInScreen, 'dashboard'],
  ['OnboardingScreen', OnboardingScreen, 'onboarding'],
]

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  vi.mocked(navigateTo).mockClear()
  stubDashboardSync(() => Promise.resolve(jsonResponse(NOT_DUE)))
})

afterEach(() => {
  window.history.pushState({}, '', '/')
})

describe.each(SCREENS)(
  '%s: a plan completed at its scheduled end (Story 2.14)',
  (_name, Screen, destination) => {
    beforeEach(() => {
      window.history.pushState({}, '', `/${destination}`)
      stubSessionRequest(() =>
        Promise.resolve(jsonResponse({ authenticated: true, destination, csrfToken: TOKEN })),
      )
    })

    function completionRegion(): HTMLElement {
      return screen.getByRole('region', { name: planCompletionContent.result.heading })
    }

    function postEndRegion(): HTMLElement {
      return screen.getByRole('region', { name: planCompletionContent.postEnd.heading })
    }

    it('the first answer is already 완료 · 확인 중; the entry sync is sent once and its job swaps the panel to the completion once', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      try {
        const job = syncJobFixture({ purpose: 'COMPLETION', trigger: 'ENTRY' })
        const result = completionVersionResultFixture()
        stubDashboard(
          inOrder([
            () => Promise.resolve(jsonResponse(completedDashboardFixture())),
            () =>
              Promise.resolve(
                jsonResponse(
                  completedDashboardFixture(result, completionRecordFixture(result), {
                    sync: syncFixture({
                      availability: 'COOLDOWN',
                      retryAfterSeconds: 20,
                      latest: {
                        ...job,
                        status: 'SUCCEEDED',
                        stage: 'PUBLICATION',
                        finishedAt: '2026-10-25T05:00:06Z',
                      },
                    }),
                  }),
                ),
              ),
          ]),
        )
        const triggers: string[] = []
        stubDashboardSync((init) => {
          triggers.push((JSON.parse(init?.body as string) as { trigger: string }).trigger)
          return Promise.resolve(
            jsonResponse({ outcome: 'ACCEPTED', job, retryAfterSeconds: null }, 202),
          )
        })
        render(<Screen />)
        expect(
          await screen.findByRole('region', { name: planCompletionContent.result.heading }),
        ).toBeVisible()
        expect(
          within(completionRegion()).getByText(
            `${planCompletionContent.result.comparisonLabel}: 확인 중`,
          ),
        ).toBeVisible()
        await waitFor(() => {
          expect(triggers).toEqual(['ENTRY'])
        })
        // The job runs: the screen says so once, and the shown values stay.
        await waitFor(() => {
          expect(screen.getByRole('status')).toHaveTextContent(planSyncContent.status.syncing)
        })
        await act(async () => {
          await vi.advanceTimersByTimeAsync(2_000)
        })
        await waitFor(() => {
          expect(
            within(completionRegion()).getByText(
              `${planCompletionContent.result.comparisonLabel}: 확정`,
            ),
          ).toBeVisible()
        })
        expect(within(completionRegion()).getAllByText(/1,300\.00 USD/).length).toBeGreaterThan(0)
        expect(screen.getByRole('status')).toHaveTextContent(
          planCompletionContent.status.synced('확정'),
        )
        // An automatic sync moves no focus.
        expect(
          screen.getByRole('heading', { level: 2, name: planResultContent.heading }),
        ).not.toHaveFocus()
        expect(triggers).toEqual(['ENTRY'])
        expect(screen.getAllByRole('status')).toHaveLength(1)
        expect(getObservedStorageEntries()).toEqual([])
      } finally {
        vi.useRealTimers()
      }
    })

    it('a later sync changes only 종료 후 현재가치: the completion result keeps its value, time and delay', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout', 'Date'] })
      try {
        const job = syncJobFixture({
          purpose: 'POST_END_VALUE',
          admittedAt: '2026-10-26T01:00:00Z',
        })
        const result = completionVersionResultFixture()
        const record = completionRecordFixture(result)
        stubDashboard(
          inOrder([
            () => Promise.resolve(jsonResponse(completedDashboardFixture(result, record))),
            () =>
              Promise.resolve(
                jsonResponse(
                  completedDashboardFixture(result, record, {
                    postEnd: { value: '1302.1', measuredAt: '2026-10-26T01:00:03Z' },
                    sync: syncFixture({
                      availability: 'COOLDOWN',
                      retryAfterSeconds: 25,
                      latest: {
                        ...job,
                        status: 'SUCCEEDED',
                        stage: 'PUBLICATION',
                        finishedAt: '2026-10-26T01:00:04Z',
                      },
                    }),
                  }),
                ),
              ),
          ]),
        )
        const triggers: string[] = []
        const admissions: (() => Promise<Response>)[] = [
          () => Promise.resolve(jsonResponse(NOT_DUE)),
          () =>
            Promise.resolve(
              jsonResponse({ outcome: 'ACCEPTED', job, retryAfterSeconds: null }, 202),
            ),
        ]
        stubDashboardSync((init) => {
          triggers.push((JSON.parse(init?.body as string) as { trigger: string }).trigger)
          return inOrder(admissions)()
        })
        render(<Screen />)
        expect(
          await screen.findByRole('region', { name: planCompletionContent.postEnd.heading }),
        ).toBeVisible()
        await waitFor(() => {
          expect(triggers).toEqual(['ENTRY'])
        })
        const before = completionRegion().textContent
        const metrics = screen.getAllByRole('article').map((card) => card.textContent)
        expect(postEndRegion()).toHaveTextContent(planCompletionContent.postEnd.sameAsObservation)

        const button = within(postEndRegion()).getByRole('button', { name: planSyncContent.action })
        await waitFor(() => {
          expect(button).toHaveAttribute('aria-disabled', 'false')
        })
        button.focus()
        fireEvent.click(button)
        expect(
          within(postEndRegion()).getByText(planSyncContent.running, { exact: true }),
        ).toBeVisible()
        await waitFor(() => {
          expect(triggers).toEqual(['ENTRY', 'MANUAL'])
        })
        await act(async () => {
          await vi.advanceTimersByTimeAsync(2_000)
        })
        await waitFor(() => {
          expect(postEndRegion()).toHaveTextContent('1,302.10 USD')
        })
        expect(completionRegion().textContent).toBe(before)
        expect(screen.getAllByRole('article').map((card) => card.textContent)).toEqual(metrics)
        expect(screen.getByRole('status')).toHaveTextContent(
          planCompletionContent.status.postEndSynced,
        )
        await waitFor(() => {
          expect(
            screen.getByRole('heading', { level: 2, name: planResultContent.heading }),
          ).toHaveFocus()
        })
        expect(getObservedStorageEntries()).toEqual([])
      } finally {
        vi.useRealTimers()
      }
    })

    it('says T and the comparison once, keeps the title beside its disclaimer, and uses no forbidden wording', async () => {
      stubDashboard(() => Promise.resolve(jsonResponse(completedDashboardFixture())))
      render(<Screen />)
      expect(
        await screen.findByRole('region', { name: planCompletionContent.result.heading }),
      ).toBeVisible()
      await waitFor(() => {
        expect(syncPosts()).toBe(1)
      })
      expect(screen.getByRole('status')).toHaveTextContent(
        planCompletionContent.status.shown(
          formatSeoulTime(SCHEDULED_END_AT, planResultContent.timeUnavailable),
          '확인 중',
        ),
      )
      const title = screen.getByRole('heading', { level: 1 })

      expect(title.nextElementSibling?.textContent).toBe(authContent.disclaimer)
      const text = document.body.textContent.replace(authContent.disclaimer, '')
      expect(text).not.toMatch(FORBIDDEN)
      expect(screen.getAllByRole('status')).toHaveLength(1)
      expect(getObservedStorageEntries()).toEqual([])
    })
  },
)

describe('SignedInScreen: syncs around a completed plan (Story 2.14)', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/dashboard')
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: true, destination: 'dashboard', csrfToken: TOKEN }),
      ),
    )
  })

  function triggersOf(triggers: string[]): (init?: RequestInit) => Promise<Response> {
    return (init) => {
      triggers.push((JSON.parse(init?.body as string) as { trigger: string }).trigger)
      return Promise.resolve(jsonResponse(NOT_DUE))
    }
  }

  it('a tab shown again after 5 minutes sends exactly one VISIBILITY sync on a completed plan', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    let visibility: DocumentVisibilityState = 'visible'
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibility,
    })
    try {
      stubDashboard(() => Promise.resolve(jsonResponse(completedDashboardFixture())))
      const triggers: string[] = []
      stubDashboardSync(triggersOf(triggers))
      render(<SignedInScreen />)
      await screen.findByRole('region', { name: planCompletionContent.result.heading })
      await waitFor(() => {
        expect(triggers).toEqual(['ENTRY'])
      })
      visibility = 'hidden'
      act(() => {
        document.dispatchEvent(new Event('visibilitychange'))
      })
      vi.setSystemTime(Date.now() + 5 * 60_000)
      visibility = 'visible'
      act(() => {
        document.dispatchEvent(new Event('visibilitychange'))
      })
      await waitFor(() => {
        expect(triggers).toEqual(['ENTRY', 'VISIBILITY'])
      })
      await screen.findByRole('region', { name: planCompletionContent.result.heading })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5_000)
      })
      expect(triggers).toEqual(['ENTRY', 'VISIBILITY'])
    } finally {
      vi.useRealTimers()
      Object.defineProperty(document, 'visibilityState', {
        configurable: true,
        get: () => 'visible',
      })
    }
  })

  it('F5: a plan seen ready that completes before its first observation gets one more entry sync, and only one', async () => {
    const queue: (() => Promise<Response>)[] = [
      () => Promise.resolve(jsonResponse(dashboardFixture())),
      () => Promise.resolve(jsonResponse(completedDashboardFixture())),
      () => Promise.resolve(jsonResponse(completedDashboardFixture())),
    ]
    stubDashboard(inOrder(queue))
    const triggers: string[] = []
    stubDashboardSync(triggersOf(triggers))
    render(<SignedInScreen />)
    await screen.findAllByRole('article')
    await waitFor(() => {
      expect(triggers).toEqual(['ENTRY'])
    })
    fireEvent.click(screen.getByRole('button', { name: planResultContent.reload }))
    await screen.findByRole('region', { name: planCompletionContent.result.heading })
    await waitFor(() => {
      expect(triggers).toEqual(['ENTRY', 'ENTRY'])
    })
    fireEvent.click(screen.getByRole('button', { name: planResultContent.reload }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'false')
    })
    await new Promise((settle) => setTimeout(settle, 30))
    expect(triggers).toEqual(['ENTRY', 'ENTRY'])
  })

  it('F4: a precision verification followed on the ready plan stops being polled when the completion arrives', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    try {
      const job = verificationJobFixture()
      let reads = 0
      const queue: (() => Promise<Response>)[] = [
        () => Promise.resolve(jsonResponse(dashboardFixture())),
        () => Promise.resolve(jsonResponse(completedDashboardFixture())),
      ]
      stubDashboard(() => {
        reads += 1
        return inOrder(queue)()
      })
      stubDashboardVerification(() =>
        Promise.resolve(jsonResponse({ outcome: 'ACCEPTED', job, retryAfterSeconds: null }, 202)),
      )
      render(<SignedInScreen />)
      const control = await screen.findByRole('button', { name: planVerificationContent.action })
      await waitFor(() => {
        expect(control).toHaveAttribute('aria-disabled', 'false')
      })
      fireEvent.click(control)
      await waitFor(() => {
        expect(getObservedAllowedRequests()).toContain(`POST ${DASHBOARD_VERIFICATION_PATH}`)
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000)
      })
      await screen.findByRole('region', { name: planCompletionContent.result.heading })
      expect(reads).toBe(2)
      // No more polling: a third read would fail the queue and keep the completion as "retryable".
      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000)
      })
      expect(reads).toBe(2)
      expect(screen.getByRole('status')).not.toHaveTextContent(
        planVerificationContent.status.stalled,
      )
    } finally {
      vi.useRealTimers()
    }
  })
})
