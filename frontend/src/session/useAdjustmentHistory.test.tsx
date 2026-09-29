import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { DASHBOARD_ADJUSTMENTS_PATH } from '../content/adjustmentHistory'
import {
  adjustmentPageFixture,
  adjustmentRowFixture,
  EVIDENCE_IDS,
  evidenceResultFixture,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubAdjustmentHistory,
} from '../test/setup'
import { useAdjustmentHistory, type AdjustmentView } from './useAdjustmentHistory'
import { isPlanResult, type PlanResult } from './useDashboard'

/** No dashboard to ask: these cases are about the history alone. */
function noop(): void {
  // Nothing to do.
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function bundle(overrides: Record<string, unknown> = {}): PlanResult {
  const body = evidenceResultFixture({ flowCount: 3, ...overrides })
  expect(isPlanResult(body)).toBe(true)
  return body as unknown as PlanResult
}

function page(direction = 'ALL', calculationId: string = EVIDENCE_IDS.calculation): unknown {
  const result = evidenceResultFixture({ flowCount: 3, calculationId })
  const rows = [1, 2, 3].map((sequence) => adjustmentRowFixture(sequence, calculationId))
  return adjustmentPageFixture(result, {
    direction,
    rows: direction === 'OUT' ? [] : rows,
    summary: { count: direction === 'OUT' ? 0 : 3, netUsdValue: '30.75', excludedCount: 0 },
  })
}

function deferred(): { promise: Promise<Response>; resolve: (response: Response) => void } {
  let resolve: (response: Response) => void = () => undefined
  const promise = new Promise<Response>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

/** Answers the history reads in order, recording the request options of each. */
function answers(...queue: (() => Promise<Response>)[]): (RequestInit | undefined)[] {
  const inits: (RequestInit | undefined)[] = []
  stubAdjustmentHistory((_url, init) => {
    inits.push(init)
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected history read')
    return next()
  })
  return inits
}

function reads(): string[] {
  return getObservedAllowedRequests().filter((entry) =>
    entry.startsWith(`GET ${DASHBOARD_ADJUSTMENTS_PATH}?`),
  )
}

function kindOf(view: AdjustmentView): string {
  return view.kind === 'open' ? `open:${view.direction}:${view.read.kind}` : view.kind
}

describe('useAdjustmentHistory', () => {
  it('reads nothing until opened, then once per open — a second press joins the read, and nothing polls', async () => {
    answers(() => Promise.resolve(json(page())))
    const shown = bundle()
    const { result } = renderHook(() => useAdjustmentHistory(shown, noop))
    await new Promise((settle) => setTimeout(settle, 20))
    expect(reads()).toHaveLength(0)
    expect(result.current.view).toEqual({ kind: 'closed' })

    act(() => {
      result.current.open()
      result.current.open()
    })
    expect(kindOf(result.current.view)).toBe('open:ALL:loading')
    await waitFor(() => {
      expect(kindOf(result.current.view)).toBe('open:ALL:ready')
    })
    await new Promise((settle) => setTimeout(settle, 20))
    expect(reads()).toHaveLength(1)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('a filter change aborts the read in flight and reads the new filter’s first page; the late answer renders nothing', async () => {
    const pending = deferred()
    const inits = answers(
      () => pending.promise,
      () => Promise.resolve(json(page('OUT'))),
    )
    const shown = bundle()
    const { result } = renderHook(() => useAdjustmentHistory(shown, noop))
    act(() => {
      result.current.open()
    })
    act(() => {
      result.current.filter('OUT')
    })
    expect(inits[0]?.signal?.aborted).toBe(true)
    await waitFor(() => {
      expect(kindOf(result.current.view)).toBe('open:OUT:ready')
    })
    await act(async () => {
      pending.resolve(json(page('ALL')))
      await Promise.resolve()
    })
    expect(kindOf(result.current.view)).toBe('open:OUT:ready')
    expect(
      reads().map((entry) =>
        new URL(entry.slice(4), 'http://h.test').searchParams.get('direction'),
      ),
    ).toEqual(['ALL', 'OUT'])
  })

  it('close aborts the read in flight; the abort is not a failure', async () => {
    const pending = deferred()
    const inits = answers(() => pending.promise)
    const shown = bundle()
    const { result } = renderHook(() => useAdjustmentHistory(shown, noop))
    act(() => {
      result.current.open()
    })
    act(() => {
      result.current.close()
    })
    expect(inits[0]?.signal?.aborted).toBe(true)
    await act(async () => {
      pending.resolve(json({}, 503))
      await Promise.resolve()
    })
    expect(result.current.view).toEqual({ kind: 'closed' })
  })

  it('a replaced bundle discards the history, aborts its read, and never takes the old bundle’s answer', async () => {
    const pending = deferred()
    const inits = answers(() => pending.promise)
    let shown = bundle()
    const { result, rerender } = renderHook(() => useAdjustmentHistory(shown, noop))
    act(() => {
      result.current.open()
    })
    shown = bundle({ pointerVersion: 2, calculationId: EVIDENCE_IDS.newerCalculation })
    rerender()
    expect(result.current.view).toEqual({ kind: 'closed' })
    expect(inits[0]?.signal?.aborted).toBe(true)
    await act(async () => {
      pending.resolve(json(page()))
      await Promise.resolve()
    })
    expect(result.current.view).toEqual({ kind: 'closed' })
  })

  it('an answer that is not the shown bundle’s whole page is a retryable failure; the retry reads the same page', async () => {
    answers(
      () => Promise.resolve(json(page('ALL', EVIDENCE_IDS.newerCalculation))),
      () => Promise.resolve(json(page())),
    )
    const shown = bundle()
    const { result } = renderHook(() => useAdjustmentHistory(shown, noop))
    act(() => {
      result.current.open()
    })
    await waitFor(() => {
      expect(result.current.view).toMatchObject({
        kind: 'open',
        read: { kind: 'failed', more: false, retryable: true },
      })
    })
    act(() => {
      result.current.retry()
    })
    expect(result.current.view).toMatchObject({
      kind: 'open',
      read: { kind: 'loading', more: false, retrying: true },
    })
    await waitFor(() => {
      expect(kindOf(result.current.view)).toBe('open:ALL:ready')
    })
  })

  it('a refusal drops the history and asks the dashboard to read again', async () => {
    answers(() =>
      Promise.resolve(json({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403)),
    )
    const onDenied = vi.fn()
    const shown = bundle()
    const { result } = renderHook(() => useAdjustmentHistory(shown, onDenied))
    act(() => {
      result.current.open()
    })
    await waitFor(() => {
      expect(result.current.view).toEqual({ kind: 'denied', cause: 'refused' })
    })
    expect(onDenied).toHaveBeenCalledTimes(1)
  })

  it('does nothing without a bundle', async () => {
    const { result } = renderHook(() => useAdjustmentHistory(null, noop))
    act(() => {
      result.current.open()
    })
    await new Promise((settle) => setTimeout(settle, 20))
    expect(result.current.view).toEqual({ kind: 'closed' })
    expect(reads()).toHaveLength(0)
  })
})
