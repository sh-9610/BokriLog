import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { CSRF_HEADER } from '../content/auth'
import {
  PLAN_END_CANCEL_PATH,
  PLAN_END_CONFIRM_PATH,
  PLAN_END_PATH,
  planEndContent,
} from '../content/planEnd'
import {
  dashboardFixture,
  endedDashboardFixture,
  planResultFixture,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubPlanEndCancel,
  stubPlanEndConfirm,
  stubPlanEndPrepare,
} from '../test/setup'
import {
  DASHBOARD_REQUEST_TIMEOUT_MS,
  NO_CHANGE,
  parseDashboard,
  UNKNOWN_PLAN_END,
  UNKNOWN_SYNC,
  UNKNOWN_VERIFICATION,
  type Dashboard,
  type DashboardView,
} from './useDashboard'
import { planEndAnnouncement, refusalCopy, usePlanEnd, type PlanEnd } from './usePlanEnd'

const TOKEN = 'plan-end-csrf-token'
const CONFIRMATION = 'plan-end|USER_REQUEST|plan-1|1|calculation-1'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** A response the test resolves later. */
function deferred(): { promise: Promise<Response>; resolve: (response: Response) => void } {
  let resolve: (response: Response) => void = () => undefined
  const promise = new Promise<Response>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

function readyView(body: Record<string, unknown> = dashboardFixture()): DashboardView {
  const parsed = parseDashboard(body)
  if (parsed?.state !== 'READY') throw new Error('a malformed READY fixture')
  return {
    kind: 'READY',
    result: parsed.result,
    nextAction: parsed.nextAction,
    sync: parsed.sync,
    verification: parsed.verification,
    change: parsed.change,
    planEnd: parsed.planEnd,
    end: null,
  }
}

function endedView(): DashboardView {
  const parsed = parseDashboard(endedDashboardFixture())
  if (parsed?.state !== 'ENDED') throw new Error('a malformed ENDED fixture')
  return {
    kind: 'ENDED',
    result: parsed.result,
    nextAction: parsed.nextAction,
    sync: UNKNOWN_SYNC,
    verification: UNKNOWN_VERIFICATION,
    change: NO_CHANGE,
    planEnd: UNKNOWN_PLAN_END,
    end: parsed.end,
  }
}

function dashboardOf(view: DashboardView): Dashboard {
  return { view, loading: false, reload: vi.fn(), refresh: vi.fn() }
}

function renderPlanEnd(
  initial: Dashboard = dashboardOf(readyView()),
  token: string | null = TOKEN,
): {
  readonly result: { readonly current: PlanEnd }
  readonly rerender: (props: { dashboard: Dashboard }) => void
} {
  return renderHook(({ dashboard }) => usePlanEnd(dashboard, token), {
    initialProps: { dashboard: initial },
  })
}

/** The prepare's answer for the default bundle. */
function prepared(reason = 'USER_REQUEST'): Record<string, unknown> {
  return {
    reason,
    planId: 'plan-1',
    pointerVersion: 1,
    calculationId: 'calculation-1',
    confirmation: CONFIRMATION.replace('USER_REQUEST', reason),
  }
}

function posts(path: string): number {
  return getObservedAllowedRequests().filter((entry) => entry === `POST ${path}`).length
}

function bodyOf(init: RequestInit | undefined): Record<string, unknown> {
  return JSON.parse(init?.body as string) as Record<string, unknown>
}

function headersOf(init: RequestInit | undefined): Record<string, string> {
  return init?.headers as Record<string, string>
}

/** Opens the dialog through a prepare that answers at once. */
async function open(result: { readonly current: PlanEnd }, reason = 'USER_REQUEST'): Promise<void> {
  act(() => {
    result.current.request(reason as 'USER_REQUEST')
  })
  await waitFor(() => {
    expect(result.current.dialog).not.toBeNull()
  })
}

afterEach(() => {
  vi.useRealTimers()
})

describe('usePlanEnd', () => {
  it('prepares with the reason and the shown reference, then opens the dialog with the plan’s period', async () => {
    const answer = deferred()
    let sent: RequestInit | undefined
    stubPlanEndPrepare((init) => {
      sent = init
      return answer.promise
    })
    const { result } = renderPlanEnd()

    act(() => {
      result.current.request('USER_REQUEST')
    })
    // At once, before any answer: the flow is preparing and says so.
    expect(result.current.preparing).toBe(true)
    expect(result.current.busy).toBe(true)
    expect(planEndAnnouncement(result.current)).toBe(planEndContent.status.preparing)
    expect(bodyOf(sent)).toEqual({
      reason: 'USER_REQUEST',
      planId: 'plan-1',
      pointerVersion: 1,
      calculationId: 'calculation-1',
    })
    expect(headersOf(sent)[CSRF_HEADER]).toBe(TOKEN)
    expect(sent?.credentials).toBe('same-origin')
    expect(sent?.cache).toBe('no-store')
    // Preparing sends no idempotency key: nothing is ended yet.
    expect(headersOf(sent)['Idempotency-Key']).toBeUndefined()

    answer.resolve(json(prepared()))
    await waitFor(() => {
      expect(result.current.dialog).not.toBeNull()
    })
    const conditions = planResultFixture().conditions as Record<string, string>
    expect(result.current.dialog).toEqual({
      reason: 'USER_REQUEST',
      startAt: conditions.startAt,
      endAt: conditions.endAt,
      confirming: false,
      uncertain: false,
    })
    expect(result.current.preparing).toBe(false)
    // The status region is behind the inert background while the dialog is open: nothing to say there.
    expect(planEndAnnouncement(result.current)).toBeNull()
    // The confirmation text is kept in memory, never shown in the dialog's view.
    expect(JSON.stringify(result.current.dialog)).not.toContain('plan-end|')
    expect(posts(PLAN_END_PATH)).toBe(1)
  })

  it('sends one prepare for two presses in the same tick', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    const { result } = renderPlanEnd()
    act(() => {
      result.current.request('USER_REQUEST')
      result.current.request('USER_REQUEST')
    })
    await waitFor(() => {
      expect(result.current.dialog).not.toBeNull()
    })
    // Nor while the dialog is open.
    act(() => {
      result.current.request('USER_REQUEST')
    })
    expect(posts(PLAN_END_PATH)).toBe(1)
  })

  it('opens no dialog without a recent reauthentication, and asks the user to confirm who they are', async () => {
    stubPlanEndPrepare(() =>
      Promise.resolve(
        json({ code: 'REAUTHENTICATION_REQUIRED', message: 'x', correlationId: 'c' }, 403),
      ),
    )
    const dashboard = dashboardOf(readyView())
    const { result } = renderPlanEnd(dashboard)
    act(() => {
      result.current.request('USER_REQUEST')
    })
    await waitFor(() => {
      expect(result.current.notice).toEqual({ kind: 'reauthRequired' })
    })
    expect(result.current.dialog).toBeNull()
    expect(result.current.busy).toBe(false)
    expect(result.current.focus).toMatchObject({ target: 'notice' })
    expect(planEndAnnouncement(result.current)).toBe(planEndContent.status.reauthRequired)
    // Nothing to read again: the server changed nothing.
    expect(dashboard.reload).not.toHaveBeenCalled()
    expect(dashboard.refresh).not.toHaveBeenCalled()
  })

  it('confirms with the confirmation text and one key; the end closes the dialog, focuses the heading and reads quietly', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    let sent: RequestInit | undefined
    stubPlanEndConfirm((init) => {
      sent = init
      return Promise.resolve(
        json({
          outcome: 'ENDED',
          planId: 'plan-1',
          reason: 'USER_REQUEST',
          endedAt: '2026-09-28T03:04:05.678Z',
        }),
      )
    })
    const dashboard = dashboardOf(readyView())
    const { result } = renderPlanEnd(dashboard)
    await open(result)

    act(() => {
      result.current.confirm()
    })
    expect(result.current.dialog?.confirming).toBe(true)
    await waitFor(() => {
      expect(result.current.dialog).toBeNull()
    })
    expect(bodyOf(sent)).toEqual({
      reason: 'USER_REQUEST',
      planId: 'plan-1',
      pointerVersion: 1,
      calculationId: 'calculation-1',
      confirmation: CONFIRMATION,
    })
    const key = headersOf(sent)['Idempotency-Key'] ?? ''
    expect(key).toMatch(/^[A-Za-z0-9._:-]{16,128}$/)
    expect(headersOf(sent)[CSRF_HEADER]).toBe(TOKEN)
    expect(result.current.focus).toMatchObject({ target: 'heading' })
    expect(result.current.notice).toBeNull()
    expect(dashboard.refresh).toHaveBeenCalledTimes(1)
    expect(dashboard.reload).not.toHaveBeenCalled()
    // The end itself is the dashboard's sentence, once the record is shown.
    expect(planEndAnnouncement(result.current)).toBeNull()
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('keeps the dialog after a confirmation without an answer, and retries with the same key; a new dialog has a new key', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    stubPlanEndCancel(() => Promise.resolve(new Response(null, { status: 204 })))
    const keys: string[] = []
    const answers: (() => Promise<Response>)[] = [
      () => Promise.reject(new Error('the network went away')),
      () =>
        Promise.resolve(json({ code: 'INTERNAL_ERROR', message: 'x', correlationId: 'c' }, 500)),
      () => Promise.reject(new Error('the network went away')),
    ]
    stubPlanEndConfirm((init) => {
      keys.push(headersOf(init)['Idempotency-Key'] ?? '')
      const next = answers.shift()
      if (next === undefined) throw new Error('an unexpected confirmation')
      return next()
    })
    const dashboard = dashboardOf(readyView())
    const { result } = renderPlanEnd(dashboard)
    await open(result)

    for (const attempt of [1, 2]) {
      act(() => {
        result.current.confirm()
      })
      expect(result.current.dialog?.confirming).toBe(true)
      // A retry stays uncertain while it runs: the dialog says its progress instead.
      await waitFor(() => {
        expect(result.current.dialog).toMatchObject({ confirming: false, uncertain: true })
      })
      expect(keys).toHaveLength(attempt)
    }
    expect(new Set(keys).size).toBe(1)
    // Nothing was refused: no notice, no read.
    expect(result.current.notice).toBeNull()
    expect(dashboard.refresh).not.toHaveBeenCalled()

    // Cancelling after an uncertain confirmation never says "cancelled": the plan may have ended. It says so and
    // reads the dashboard out loud.
    act(() => {
      result.current.cancel()
    })
    expect(result.current.notice).toEqual({ kind: 'uncertain' })
    expect(planEndAnnouncement(result.current)).toBe(
      `${planEndContent.uncertain.label}. ${planEndContent.uncertain.text}`,
    )
    expect(planEndAnnouncement(result.current)).not.toContain(planEndContent.status.cancelled)
    expect(dashboard.reload).toHaveBeenCalledTimes(1)
    expect(dashboard.refresh).not.toHaveBeenCalled()
    await waitFor(() => {
      expect(posts(PLAN_END_CANCEL_PATH)).toBe(1)
    })
    await open(result)
    act(() => {
      result.current.confirm()
    })
    await waitFor(() => {
      expect(keys).toHaveLength(3)
    })
    expect(keys[2]).not.toBe(keys[0])
  })

  it('a confirmation that never answers is given up after the time limit and keeps the dialog', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    stubPlanEndConfirm(
      () =>
        new Promise<Response>(() => {
          // Never answers.
        }),
    )
    const { result } = renderPlanEnd()
    await open(result)
    vi.useFakeTimers()
    act(() => {
      result.current.confirm()
    })
    expect(result.current.dialog?.confirming).toBe(true)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(DASHBOARD_REQUEST_TIMEOUT_MS - 1)
    })
    expect(result.current.dialog?.confirming).toBe(true)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(result.current.dialog).toMatchObject({ confirming: false, uncertain: true })
  })

  it('a prepare that never answers is given up after the time limit with a notice', async () => {
    stubPlanEndPrepare(
      () =>
        new Promise<Response>(() => {
          // Never answers.
        }),
    )
    vi.useFakeTimers()
    const { result } = renderPlanEnd()
    act(() => {
      result.current.request('USER_REQUEST')
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(DASHBOARD_REQUEST_TIMEOUT_MS)
    })
    expect(result.current.preparing).toBe(false)
    expect(result.current.dialog).toBeNull()
    expect(result.current.notice).toEqual({ kind: 'refused', refusal: 'error', reason: null })
  })

  it.each([
    [409, 'REFERENCE_STALE', null, 'stale', 'refresh'],
    [409, 'PLAN_END_UNAVAILABLE', 'RESTRICTED', 'unavailable', 'refresh'],
    [409, 'LONG_GAP', null, 'longGap', 'refresh'],
    [409, 'NOT_LONG_GAP', null, 'notLongGap', 'refresh'],
    [409, 'IDEMPOTENCY_CONFLICT', null, 'conflict', 'refresh'],
    [409, 'SOMETHING_NEW', null, 'error', 'refresh'],
    [404, 'PLAN_NOT_FOUND', null, 'notFound', 'refresh'],
    [403, 'PLAN_END_NOT_CONFIRMED', null, 'notConfirmed', 'refresh'],
    [403, 'FORBIDDEN', null, 'denied', 'reload'],
    [401, 'UNAUTHENTICATED', null, 'denied', 'reload'],
    [400, 'BAD_REQUEST', null, 'error', 'refresh'],
    [422, 'IDEMPOTENCY_KEY_REQUIRED', null, 'error', 'refresh'],
  ])(
    'a confirmation refused with %i %s closes the dialog, says why and reads the dashboard again',
    async (status, code, reason, refusal, read) => {
      stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
      stubPlanEndConfirm(() =>
        Promise.resolve(json({ code, message: 'x', correlationId: 'c', reason }, status)),
      )
      const dashboard = dashboardOf(readyView())
      const { result } = renderPlanEnd(dashboard)
      await open(result)
      act(() => {
        result.current.confirm()
      })
      await waitFor(() => {
        expect(result.current.dialog).toBeNull()
      })
      expect(result.current.notice).toEqual({ kind: 'refused', refusal, reason })
      expect(result.current.focus).toMatchObject({ target: 'notice' })
      const words = refusalCopy(refusal as 'error', reason)
      expect(planEndAnnouncement(result.current)).toBe(`${words.label}. ${words.text}`)
      expect(read === 'reload' ? dashboard.reload : dashboard.refresh).toHaveBeenCalledTimes(1)
      expect(read === 'reload' ? dashboard.refresh : dashboard.reload).not.toHaveBeenCalled()
    },
  )

  it('an unavailability names its reason in the end area’s words; an unknown reason in safe words', () => {
    expect(refusalCopy('unavailable', 'RESTRICTED').text).toContain(
      planEndContent.area.reasons.RESTRICTED ?? 'missing',
    )
    expect(refusalCopy('unavailable', 'NEW_RULE').text).toContain(
      planEndContent.unknownRefusalReason,
    )
    expect(refusalCopy('unavailable', null)).toBe(planEndContent.refusals.unavailable)
  })

  it('a prepare whose answer does not echo what was asked opens nothing', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json({ ...prepared(), pointerVersion: 2 })))
    const { result } = renderPlanEnd()
    act(() => {
      result.current.request('USER_REQUEST')
    })
    await waitFor(() => {
      expect(result.current.notice).toEqual({ kind: 'refused', refusal: 'error', reason: null })
    })
    expect(result.current.dialog).toBeNull()
  })

  it('cancels at once without waiting, and the next prepare goes out only after the cancel answered', async () => {
    const cancelled = deferred()
    stubPlanEndCancel(() => cancelled.promise)
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    const { result } = renderPlanEnd()
    await open(result)

    act(() => {
      result.current.cancel()
    })
    // Closed at once, before the cancel answers; nothing changed, and the status says so.
    expect(result.current.dialog).toBeNull()
    expect(result.current.notice).toEqual({ kind: 'cancelled' })
    expect(planEndAnnouncement(result.current)).toBe(planEndContent.status.cancelled)
    expect(result.current.focus).toBeNull()
    expect(posts(PLAN_END_CANCEL_PATH)).toBe(1)

    act(() => {
      result.current.request('USER_REQUEST')
    })
    await new Promise((settle) => setTimeout(settle, 20))
    // The second prepare waits for the cancel: a late cancel must not release the new flow.
    expect(posts(PLAN_END_PATH)).toBe(1)
    cancelled.resolve(new Response(null, { status: 204 }))
    await waitFor(() => {
      expect(posts(PLAN_END_PATH)).toBe(2)
    })
    await waitFor(() => {
      expect(result.current.dialog).not.toBeNull()
    })
  })

  it('a cancel is ignored while a confirmation is in flight', async () => {
    const answer = deferred()
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    stubPlanEndConfirm(() => answer.promise)
    stubPlanEndCancel(() => Promise.resolve(new Response(null, { status: 204 })))
    const { result } = renderPlanEnd()
    await open(result)
    act(() => {
      result.current.confirm()
      result.current.confirm()
    })
    act(() => {
      result.current.cancel()
    })
    expect(result.current.dialog?.confirming).toBe(true)
    expect(posts(PLAN_END_CANCEL_PATH)).toBe(0)
    expect(posts(PLAN_END_CONFIRM_PATH)).toBe(1)
    answer.resolve(
      json({
        outcome: 'ENDED',
        planId: 'plan-1',
        reason: 'USER_REQUEST',
        endedAt: '2026-09-28T03:04:05Z',
      }),
    )
    await waitFor(() => {
      expect(result.current.dialog).toBeNull()
    })
  })

  it('carries the long-gap reason for 새 계획 시작', async () => {
    let sent: RequestInit | undefined
    stubPlanEndPrepare((init) => {
      sent = init
      return Promise.resolve(json(prepared('LONG_GAP_NEW_PLAN')))
    })
    const { result } = renderPlanEnd()
    await open(result, 'LONG_GAP_NEW_PLAN')
    expect(bodyOf(sent).reason).toBe('LONG_GAP_NEW_PLAN')
    expect(result.current.dialog?.reason).toBe('LONG_GAP_NEW_PLAN')
  })

  it('asks nothing without an active bundle or a token, and says it could not', () => {
    for (const [dashboard, token] of [
      [dashboardOf(endedView()), TOKEN],
      [dashboardOf({ kind: 'checking' }), TOKEN],
      [dashboardOf(readyView()), null],
    ] as const) {
      const { result } = renderPlanEnd(dashboard, token)
      act(() => {
        result.current.request('USER_REQUEST')
      })
      expect(result.current.notice).toEqual({ kind: 'refused', refusal: 'error', reason: null })
      expect(result.current.dialog).toBeNull()
    }
    expect(getObservedRequests()).toEqual([])
    expect(getObservedAllowedRequests()).toEqual([])
  })

  it('after the end, reads quietly again while the plan is still shown active — at most three times', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    stubPlanEndConfirm(() =>
      Promise.resolve(
        json({
          outcome: 'ENDED',
          planId: 'plan-1',
          reason: 'USER_REQUEST',
          endedAt: '2026-09-28T03:04:05Z',
        }),
      ),
    )
    const first = dashboardOf(readyView())
    const { result, rerender } = renderPlanEnd(first)
    await open(result)
    act(() => {
      result.current.confirm()
    })
    await waitFor(() => {
      expect(result.current.dialog).toBeNull()
    })
    expect(first.refresh).toHaveBeenCalledTimes(1)
    // A read that was already in flight answers with the plan still active: ask again.
    const refresh = vi.fn()
    for (let answer = 0; answer < 3; answer += 1) {
      rerender({ dashboard: { ...first, view: readyView(), refresh } })
    }
    expect(refresh).toHaveBeenCalledTimes(3)
    expect(result.current.notice).toBeNull()
    // The record ends the waiting.
    const later = vi.fn()
    rerender({ dashboard: { ...first, view: endedView(), refresh: later } })
    rerender({ dashboard: { ...first, view: readyView(), refresh: later } })
    expect(later).not.toHaveBeenCalled()
    expect(first.reload).not.toHaveBeenCalled()
  })

  it('a notice belongs to the plan it was said about: the record of that plan clears it', async () => {
    stubPlanEndPrepare(() =>
      Promise.resolve(json({ code: 'PLAN_NOT_FOUND', message: 'x', correlationId: 'c' }, 404)),
    )
    const dashboard = dashboardOf(readyView())
    const { result, rerender } = renderPlanEnd(dashboard)
    act(() => {
      result.current.request('USER_REQUEST')
    })
    await waitFor(() => {
      expect(result.current.notice).toMatchObject({ refusal: 'notFound' })
    })
    // A session re-check shows no plan: nothing said about it changes.
    rerender({ dashboard: dashboardOf({ kind: 'checking' }) })
    expect(result.current.notice).toMatchObject({ refusal: 'notFound' })
    rerender({ dashboard: dashboardOf(endedView()) })
    expect(result.current.notice).toBeNull()
  })
})

describe('usePlanEnd: review hardening (Story 2.13)', () => {
  function ended(reason = 'USER_REQUEST'): Promise<Response> {
    return Promise.resolve(
      json({ outcome: 'ENDED', planId: 'plan-1', reason, endedAt: '2026-09-28T03:04:05Z' }),
    )
  }

  it('F1: when the record does not come within its reads, says the end was confirmed and reads once out loud', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    stubPlanEndConfirm(() => ended())
    const first = dashboardOf(readyView())
    const { result, rerender } = renderPlanEnd(first)
    await open(result)
    act(() => {
      result.current.confirm()
    })
    await waitFor(() => {
      expect(result.current.dialog).toBeNull()
    })
    const refresh = vi.fn()
    const reload = vi.fn()
    const active = readyView()
    const failed: DashboardView = active.kind === 'READY' ? { ...active, kind: 'lastGood' } : active
    // Still active, a read that did not answer, and one with nothing kept: each asks again, quietly.
    for (const view of [readyView(), failed, { kind: 'retryable', kept: null } as const]) {
      rerender({ dashboard: { ...first, view, refresh, reload } })
    }
    expect(refresh).toHaveBeenCalledTimes(3)
    expect(reload).not.toHaveBeenCalled()
    rerender({ dashboard: { ...first, view: readyView(), refresh, reload } })
    expect(result.current.notice).toEqual({ kind: 'endedNotShown' })
    expect(reload).toHaveBeenCalledTimes(1)
    expect(planEndAnnouncement(result.current)).toBe(
      `${planEndContent.endedNotShown.label}. ${planEndContent.endedNotShown.text}`,
    )
    // Once: a later answer asks nothing more.
    rerender({ dashboard: { ...first, view: readyView(), refresh, reload } })
    expect(refresh).toHaveBeenCalledTimes(3)
    expect(reload).toHaveBeenCalledTimes(1)
    // The record clears it.
    rerender({ dashboard: { ...first, view: endedView(), refresh, reload } })
    expect(result.current.notice).toBeNull()
  })

  it('F3: a read that shows the plan ended elsewhere closes the dialog, releases the flow and focuses the heading', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    stubPlanEndCancel(() => Promise.resolve(new Response(null, { status: 204 })))
    const { result, rerender } = renderPlanEnd()
    await open(result)
    // A session re-check or a read that did not answer says nothing new: the dialog stays.
    rerender({ dashboard: dashboardOf({ kind: 'checking' }) })
    expect(result.current.dialog).not.toBeNull()
    rerender({ dashboard: dashboardOf(endedView()) })
    expect(result.current.dialog).toBeNull()
    expect(result.current.focus).toMatchObject({ target: 'heading' })
    expect(result.current.notice).toBeNull()
    await waitFor(() => {
      expect(posts(PLAN_END_CANCEL_PATH)).toBe(1)
    })
    expect(getObservedAllowedRequests()).not.toContain(`POST ${PLAN_END_CONFIRM_PATH}`)
  })

  it('F3: the same plan at another version closes the dialog and says the shown result changed', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    stubPlanEndCancel(() => Promise.resolve(new Response(null, { status: 204 })))
    const { result, rerender } = renderPlanEnd()
    await open(result)
    rerender({
      dashboard: dashboardOf(
        readyView(
          dashboardFixture(
            planResultFixture({ pointerVersion: 2, calculationId: 'calculation-2' }),
          ),
        ),
      ),
    })
    expect(result.current.dialog).toBeNull()
    expect(result.current.notice).toEqual({ kind: 'refused', refusal: 'stale', reason: null })
    expect(result.current.focus).toMatchObject({ target: 'notice' })
  })

  it('F3: a confirmation in flight keeps the dialog whatever a read shows: its answer decides', async () => {
    const answer = deferred()
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    stubPlanEndConfirm(() => answer.promise)
    const { result, rerender } = renderPlanEnd()
    await open(result)
    act(() => {
      result.current.confirm()
    })
    rerender({ dashboard: dashboardOf(endedView()) })
    expect(result.current.dialog?.confirming).toBe(true)
    answer.resolve(await ended())
    await waitFor(() => {
      expect(result.current.dialog).toBeNull()
    })
    expect(result.current.focus).toMatchObject({ target: 'heading' })
    expect(getObservedRequests()).toEqual([])
  })

  it('F13: a confirmation answered for another reason is not taken as this end: the dialog stays, same key next', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    stubPlanEndConfirm(() => ended('LONG_GAP_NEW_PLAN'))
    const { result } = renderPlanEnd()
    await open(result)
    act(() => {
      result.current.confirm()
    })
    await waitFor(() => {
      expect(result.current.dialog).toMatchObject({ confirming: false, uncertain: true })
    })
    expect(result.current.focus).toBeNull()
  })

  it('F14: a key that cannot be made opens nothing and leaves the flow free', async () => {
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    const randomUUID = vi.spyOn(crypto, 'randomUUID').mockImplementation(() => {
      throw new Error('no randomness')
    })
    const { result } = renderPlanEnd()
    act(() => {
      result.current.request('USER_REQUEST')
    })
    await waitFor(() => {
      expect(result.current.notice).toEqual({ kind: 'refused', refusal: 'error', reason: null })
    })
    expect(result.current).toMatchObject({ preparing: false, busy: false, dialog: null })
    randomUUID.mockRestore()
    await open(result)
    expect(posts(PLAN_END_PATH)).toBe(2)
  })

  it('F14: a request that throws while it is sent is no answer, and the flow is free again', async () => {
    stubPlanEndPrepare(() => {
      throw new Error('the request could not be made')
    })
    const { result } = renderPlanEnd()
    act(() => {
      result.current.request('USER_REQUEST')
    })
    await waitFor(() => {
      expect(result.current.notice).toEqual({ kind: 'refused', refusal: 'error', reason: null })
    })
    expect(result.current).toMatchObject({ preparing: false, busy: false })
  })

  it('F12: the cancel carries no body, the session token, and the 10-second limit the next prepare waits for', async () => {
    let cancelInit: RequestInit | undefined
    stubPlanEndCancel((init) => {
      cancelInit = init
      return new Promise<Response>(() => {
        // Never answers.
      })
    })
    stubPlanEndPrepare(() => Promise.resolve(json(prepared())))
    const { result } = renderPlanEnd()
    await open(result)
    vi.useFakeTimers()
    act(() => {
      result.current.cancel()
    })
    expect(cancelInit?.body).toBeUndefined()
    expect(headersOf(cancelInit)['Content-Type']).toBeUndefined()
    expect(headersOf(cancelInit)[CSRF_HEADER]).toBe(TOKEN)
    act(() => {
      result.current.request('USER_REQUEST')
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(DASHBOARD_REQUEST_TIMEOUT_MS - 1)
    })
    expect(posts(PLAN_END_PATH)).toBe(1)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(posts(PLAN_END_PATH)).toBe(2)
  })
})
