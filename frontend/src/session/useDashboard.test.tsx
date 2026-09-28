import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { DASHBOARD_PATH } from '../content/planResult'
import {
  dashboardFixture,
  NO_PLAN_FIXTURE,
  planResultFixture,
  PREPARING_FIXTURE,
} from '../test/planResultFixture'
import { getObservedAllowedRequests, getObservedStorageEntries, stubDashboard } from '../test/setup'
import { useDashboard, type DashboardView } from './useDashboard'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** Answers the dashboard reads in order; each entry is a response or a failure. */
function answers(...queue: (() => Promise<Response>)[]): void {
  stubDashboard(() => {
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected dashboard read')
    return next()
  })
}

function reads(): number {
  return getObservedAllowedRequests().filter((entry) => entry === `GET ${DASHBOARD_PATH}`).length
}

/** A response the test resolves later. */
function deferred(): { promise: Promise<Response>; resolve: (response: Response) => void } {
  let resolve: (response: Response) => void = () => undefined
  const promise = new Promise<Response>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

function versioned(pointerVersion: number, calculationId: string): Record<string, unknown> {
  return dashboardFixture(planResultFixture({ pointerVersion, calculationId }))
}

async function settledView(
  result: { current: { view: DashboardView; loading: boolean } },
  kind: DashboardView['kind'],
): Promise<DashboardView> {
  await waitFor(() => {
    expect(result.current.loading).toBe(false)
    expect(result.current.view.kind).toBe(kind)
  })
  return result.current.view
}

describe('useDashboard', () => {
  it('does not read before the session is ready, and then reads once — no polling', async () => {
    answers(() => Promise.resolve(json(NO_PLAN_FIXTURE)))
    const { result, rerender } = renderHook(({ ready }) => useDashboard(ready), {
      initialProps: { ready: false },
    })
    expect(result.current.view.kind).toBe('checking')
    expect(result.current.loading).toBe(false)
    expect(reads()).toBe(0)

    rerender({ ready: true })
    // The read the session's readiness started is in flight at once.
    expect(result.current.loading).toBe(true)
    await settledView(result, 'NO_PLAN')
    await new Promise((settle) => setTimeout(settle, 50))
    expect(reads()).toBe(1)
  })

  it('tells no plan and being prepared apart', async () => {
    answers(
      () => Promise.resolve(json(PREPARING_FIXTURE)),
      () => Promise.resolve(json(NO_PLAN_FIXTURE)),
    )
    const { result } = renderHook(() => useDashboard(true))
    expect(await settledView(result, 'PREPARING')).toEqual({
      kind: 'PREPARING',
      nextAction: 'WAIT_FOR_RESULT',
    })
    act(() => {
      result.current.reload()
    })
    expect(await settledView(result, 'NO_PLAN')).toEqual({
      kind: 'NO_PLAN',
      nextAction: 'CREATE_PLAN',
    })
  })

  it('AC-8 (READ_FAIL): a failed reload keeps the same bundle as the last good result, for every kind of failure', async () => {
    answers(
      () => Promise.resolve(json(dashboardFixture())),
      () => Promise.reject(new Error('network')),
      () =>
        Promise.resolve(
          json({ code: 'DASHBOARD_UNAVAILABLE', message: 'x', correlationId: 'c' }, 503),
        ),
      () => Promise.resolve(json({}, 500)),
      () => Promise.resolve(json({ state: 'READY', nextAction: 'NONE', result: { planId: 'x' } })),
      () => Promise.resolve(new Response('not json', { status: 200 })),
    )
    const { result } = renderHook(() => useDashboard(true))
    const first = await settledView(result, 'READY')
    const shown = first.kind === 'READY' ? first.result : null
    expect(shown).not.toBeNull()

    for (let attempt = 0; attempt < 5; attempt += 1) {
      act(() => {
        result.current.reload()
      })
      const view = await settledView(result, 'lastGood')
      expect(view).toMatchObject({ kind: 'lastGood', nextAction: 'NONE' })
      // The very same bundle: no zero, no partial value, nothing re-assembled.
      expect(view.kind === 'lastGood' ? view.result : null).toBe(shown)
    }
    expect(reads()).toBe(6)
  })

  it('AC-8 (READ_FAIL): without an earlier bundle a failed read is retryable and carries nothing', async () => {
    answers(
      () => Promise.reject(new Error('network')),
      () =>
        Promise.resolve(
          json({ code: 'DASHBOARD_UNAVAILABLE', message: 'x', correlationId: 'c' }, 503),
        ),
    )
    const { result } = renderHook(() => useDashboard(true))
    expect(await settledView(result, 'retryable')).toEqual({ kind: 'retryable', kept: null })
    act(() => {
      result.current.reload()
    })
    expect(await settledView(result, 'retryable')).toEqual({ kind: 'retryable', kept: null })
  })

  it('AC-13: a 403 drops the bundle from memory, and a 401 does too with its own cause', async () => {
    answers(
      () => Promise.resolve(json(dashboardFixture())),
      () => Promise.resolve(json({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403)),
      () => Promise.reject(new Error('network')),
      () => Promise.resolve(json(dashboardFixture())),
      () =>
        Promise.resolve(json({ code: 'UNAUTHENTICATED', message: 'x', correlationId: 'c' }, 401)),
      () => Promise.reject(new Error('network')),
    )
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'READY')
    act(() => {
      result.current.reload()
    })
    expect(await settledView(result, 'denied')).toEqual({ kind: 'denied', cause: 'refused' })
    act(() => {
      result.current.reload()
    })
    // Nothing is left to fall back on.
    expect(await settledView(result, 'retryable')).toEqual({ kind: 'retryable', kept: null })
    act(() => {
      result.current.reload()
    })
    await settledView(result, 'READY')
    act(() => {
      result.current.reload()
    })
    expect(await settledView(result, 'denied')).toEqual({ kind: 'denied', cause: 'signedOut' })
    act(() => {
      result.current.reload()
    })
    expect(await settledView(result, 'retryable')).toEqual({ kind: 'retryable', kept: null })
  })

  it('AC-9 (RACE): a pointer that goes back or a same version with another calculation is not swapped in, and the state is retryable', async () => {
    answers(
      () => Promise.resolve(json(versioned(2, 'calculation-2'))),
      () => Promise.resolve(json(versioned(1, 'calculation-1'))),
      () => Promise.resolve(json(versioned(2, 'calculation-x'))),
      () => Promise.resolve(json(versioned(3, 'calculation-3'))),
    )
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'READY')

    // An older version, then the same version with another calculation.
    for (let attempt = 0; attempt < 2; attempt += 1) {
      act(() => {
        result.current.reload()
      })
      const view = await settledView(result, 'retryable')
      expect(view).toMatchObject({
        kind: 'retryable',
        kept: { nextAction: 'NONE', result: { pointerVersion: 2, calculationId: 'calculation-2' } },
      })
    }
    act(() => {
      result.current.reload()
    })
    expect(await settledView(result, 'READY')).toMatchObject({
      result: { pointerVersion: 3, calculationId: 'calculation-3' },
    })
  })

  it('AC-9: a late answer to an earlier read is dropped instead of overwriting a newer one', async () => {
    const early = deferred()
    answers(
      () => early.promise,
      () => Promise.resolve(json(versioned(2, 'calculation-2'))),
    )
    const { result, rerender } = renderHook(({ ready }) => useDashboard(ready), {
      initialProps: { ready: true },
    })
    // The session is re-checked: the first read's answer no longer belongs to anyone.
    rerender({ ready: false })
    rerender({ ready: true })
    await waitFor(() => {
      expect(result.current.view.kind).toBe('READY')
    })
    early.resolve(json(versioned(5, 'calculation-5')))
    await new Promise((settle) => setTimeout(settle, 20))
    expect(result.current.view).toMatchObject({
      kind: 'READY',
      result: { pointerVersion: 2, calculationId: 'calculation-2' },
    })
    expect(reads()).toBe(2)
  })

  it('drops the bundle when the session stops being ready, and the next read starts as loading', async () => {
    const again = deferred()
    answers(
      () => Promise.resolve(json(dashboardFixture())),
      () => again.promise,
    )
    const { result, rerender } = renderHook(({ ready }) => useDashboard(ready), {
      initialProps: { ready: true },
    })
    await settledView(result, 'READY')

    rerender({ ready: false })
    // Not kept in memory while the session is not ready.
    expect(result.current.view).toEqual({ kind: 'checking' })
    expect(result.current.loading).toBe(false)

    rerender({ ready: true })
    expect(result.current.view).toEqual({ kind: 'checking' })
    // The read that readiness started is in flight: loading, and a reload now does nothing.
    expect(result.current.loading).toBe(true)
    act(() => {
      result.current.reload()
    })
    expect(reads()).toBe(2)
    // The earlier bundle is gone too: a failure now has no last good result to fall back on.
    again.resolve(json({}, 500))
    expect(await settledView(result, 'retryable')).toEqual({ kind: 'retryable', kept: null })
  })

  it('ignores a reload while a read is in flight, even twice in one tick', async () => {
    const slow = deferred()
    answers(
      () => Promise.resolve(json(dashboardFixture())),
      () => slow.promise,
    )
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'READY')
    act(() => {
      result.current.reload()
      result.current.reload()
    })
    expect(result.current.loading).toBe(true)
    act(() => {
      result.current.reload()
    })
    slow.resolve(json(dashboardFixture()))
    await settledView(result, 'READY')
    expect(reads()).toBe(2)
  })

  it('keeps financial values out of browser storage', async () => {
    answers(() => Promise.resolve(json(dashboardFixture())))
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'READY')
    expect(getObservedStorageEntries()).toEqual([])
    expect(window.location.search).toBe('')
  })
})
