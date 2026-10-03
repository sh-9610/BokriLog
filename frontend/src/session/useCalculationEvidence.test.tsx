import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { DASHBOARD_EVIDENCE_PATH } from '../content/calculationEvidence'
import {
  calculationEvidenceFixture,
  EVIDENCE_IDS,
  evidenceResultFixture,
  laterEvidenceFixture,
  trustFixture,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubCalculationEvidence,
} from '../test/setup'
import { parseEvidence, useCalculationEvidence, type EvidenceState } from './useCalculationEvidence'
import { isPlanResult, type PlanResult } from './useDashboard'

/** No dashboard to ask: these cases are about the evidence alone. */
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
  const body = evidenceResultFixture(overrides)
  expect(isPlanResult(body)).toBe(true)
  return body as unknown as PlanResult
}

function deferred(): { promise: Promise<Response>; resolve: (response: Response) => void } {
  let resolve: (response: Response) => void = () => undefined
  const promise = new Promise<Response>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

/** Answers the evidence reads in order. */
function answers(...queue: (() => Promise<Response>)[]): void {
  stubCalculationEvidence(() => {
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected evidence read')
    return next()
  })
}

function reads(): number {
  return getObservedAllowedRequests().filter((entry) =>
    entry.startsWith(`GET ${DASHBOARD_EVIDENCE_PATH}?`),
  ).length
}

describe('useCalculationEvidence', () => {
  it('reads nothing until opened, then once per open — no polling', async () => {
    answers(() => Promise.resolve(json(calculationEvidenceFixture(evidenceResultFixture()))))
    const shown = bundle()
    const { result } = renderHook(() => useCalculationEvidence(shown, noop))
    await new Promise((settle) => setTimeout(settle, 20))
    expect(reads()).toBe(0)
    expect(result.current.view).toEqual({ kind: 'closed' })

    act(() => {
      result.current.open()
      // A second press in the same tick joins the read in flight.
      result.current.open()
    })
    expect(result.current.view).toEqual({ kind: 'loading', retrying: false })
    await waitFor(() => {
      expect(result.current.view.kind).toBe('ready')
    })
    await new Promise((settle) => setTimeout(settle, 20))
    expect(reads()).toBe(1)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('does nothing without a shown bundle', () => {
    const { result } = renderHook(() => useCalculationEvidence(null, noop))
    act(() => {
      result.current.open()
    })
    expect(result.current.view).toEqual({ kind: 'closed' })
    expect(reads()).toBe(0)
  })

  it('drops an answer that arrives after the region was closed, and a late answer to an earlier open', async () => {
    const first = deferred()
    const second = deferred()
    answers(
      () => first.promise,
      () => second.promise,
    )
    const shown = bundle()
    const { result } = renderHook(() => useCalculationEvidence(shown, noop))
    act(() => {
      result.current.open()
    })
    act(() => {
      result.current.close()
    })
    act(() => {
      result.current.open()
    })
    await act(async () => {
      first.resolve(json(calculationEvidenceFixture(evidenceResultFixture())))
      await Promise.resolve()
    })
    // The first read belongs to nobody: the second is still the one in flight.
    expect(result.current.view).toEqual({ kind: 'loading', retrying: false })
    await act(async () => {
      second.resolve(json({ code: 'EVIDENCE_UNAVAILABLE', message: 'x', correlationId: 'c' }, 503))
      await Promise.resolve()
    })
    await waitFor(() => {
      expect(result.current.view).toEqual({ kind: 'failed', retryable: true })
    })
  })

  it('maps the answers: 503 and malformed are retryable, 404 is not, 403 and 401 drop the evidence', async () => {
    answers(
      () => Promise.resolve(json({}, 503)),
      () => Promise.resolve(json({ planId: 'plan-1' })),
      () => Promise.resolve(json({}, 404)),
      () => Promise.resolve(json(calculationEvidenceFixture(evidenceResultFixture()))),
      () => Promise.resolve(json({}, 403)),
      () => Promise.resolve(json({}, 401)),
    )
    const shown = bundle()
    const { result } = renderHook(() => useCalculationEvidence(shown, noop))
    const expected = [
      { kind: 'failed', retryable: true },
      { kind: 'failed', retryable: true },
      { kind: 'failed', retryable: false },
      'ready',
      { kind: 'denied', cause: 'refused' },
      { kind: 'denied', cause: 'signedOut' },
    ] as const
    for (const view of expected) {
      act(() => {
        result.current.close()
      })
      act(() => {
        result.current.open()
      })
      await waitFor(() => {
        expect(result.current.view.kind).not.toBe('loading')
      })
      const shownView = result.current.view
      expect(shownView.kind === 'ready' ? 'ready' : shownView).toEqual(view)
    }
  })

  it('asks the dashboard to read again on a 403 or a 401 only, never on a plain failure', async () => {
    answers(
      () => Promise.resolve(json({}, 503)),
      () => Promise.resolve(json({}, 404)),
      () => Promise.resolve(json({}, 403)),
      () => Promise.resolve(json({}, 401)),
    )
    const onDenied = vi.fn()
    const shown = bundle()
    const { result } = renderHook(() => useCalculationEvidence(shown, onDenied))
    for (const denied of [false, false, true, true]) {
      act(() => {
        result.current.close()
      })
      act(() => {
        result.current.open()
      })
      await waitFor(() => {
        expect(result.current.view.kind).not.toBe('loading')
      })
      expect(result.current.view.kind).toBe(denied ? 'denied' : 'failed')
    }
    expect(onDenied).toHaveBeenCalledTimes(2)
  })

  it('marks a read asked for again after a failure as a retry, and aborts a read on close', async () => {
    const pending = deferred()
    let signal: AbortSignal | null = null
    const queue: (() => Promise<Response>)[] = [
      () => Promise.reject(new Error('network')),
      () => pending.promise,
    ]
    stubCalculationEvidence((_url, init) => {
      signal = init?.signal ?? null
      return (queue.shift() ?? (() => Promise.reject(new Error('extra'))))()
    })
    const shown = bundle()
    const { result } = renderHook(() => useCalculationEvidence(shown, noop))
    act(() => {
      result.current.open()
    })
    await waitFor(() => {
      expect(result.current.view).toEqual({ kind: 'failed', retryable: true })
    })
    act(() => {
      result.current.retry()
    })
    expect(result.current.view).toEqual({ kind: 'loading', retrying: true })
    act(() => {
      result.current.close()
    })
    expect((signal as AbortSignal | null)?.aborted).toBe(true)
    expect(result.current.view).toEqual({ kind: 'closed' })
  })

  it('retries only after a retryable failure', async () => {
    answers(
      () => Promise.resolve(json({}, 404)),
      () => Promise.reject(new Error('network')),
      () => Promise.resolve(json(calculationEvidenceFixture(evidenceResultFixture()))),
    )
    const shown = bundle()
    const { result } = renderHook(() => useCalculationEvidence(shown, noop))
    act(() => {
      result.current.retry()
    })
    expect(reads()).toBe(0)
    act(() => {
      result.current.open()
    })
    await waitFor(() => {
      expect(result.current.view).toEqual({ kind: 'failed', retryable: false })
    })
    act(() => {
      result.current.retry()
    })
    expect(reads()).toBe(1)
    act(() => {
      result.current.close()
    })
    act(() => {
      result.current.open()
    })
    await waitFor(() => {
      expect(result.current.view).toEqual({ kind: 'failed', retryable: true })
    })
    act(() => {
      result.current.retry()
    })
    await waitFor(() => {
      expect(result.current.view.kind).toBe('ready')
    })
    expect(reads()).toBe(3)
  })

  it('discards the evidence and closes when the shown bundle changes or goes away, but keeps it for the same pointer', async () => {
    answers(
      () => Promise.resolve(json(calculationEvidenceFixture(evidenceResultFixture()))),
      () =>
        Promise.resolve(
          json(
            laterEvidenceFixture(
              evidenceResultFixture({
                pointerVersion: 2,
                calculationId: EVIDENCE_IDS.newerCalculation,
              }),
            ),
          ),
        ),
    )
    const { result, rerender } = renderHook<EvidenceState, { shown: PlanResult | null }>(
      ({ shown }) => useCalculationEvidence(shown, noop),
      { initialProps: { shown: bundle() } },
    )
    act(() => {
      result.current.open()
    })
    await waitFor(() => {
      expect(result.current.view.kind).toBe('ready')
    })

    // The same pointer read again (a new object): the evidence stays.
    rerender({ shown: bundle() })
    expect(result.current.view.kind).toBe('ready')

    rerender({ shown: bundle({ pointerVersion: 2, calculationId: EVIDENCE_IDS.newerCalculation }) })
    expect(result.current.view).toEqual({ kind: 'closed' })
    act(() => {
      result.current.open()
    })
    await waitFor(() => {
      expect(result.current.view.kind).toBe('ready')
    })
    const view = result.current.view
    expect(view.kind === 'ready' ? view.evidence.calculationId : null).toBe(
      EVIDENCE_IDS.newerCalculation,
    )

    rerender({ shown: null })
    expect(result.current.view).toEqual({ kind: 'closed' })
  })
})

describe('the evidence of a plan ended early (Story 2.13)', () => {
  const ENDED = '2026-09-28T03:04:05.678Z'

  function endedBundle(): PlanResult {
    return bundle({
      planStatus: 'ENDED_EARLY',
      trust: trustFixture({
        freshness: { state: 'ENDED', cause: 'PLAN_ENDED_EARLY' },
      }),
    })
  }

  /** The evidence of the shown version, its current version applied until `appliedUntil`. */
  function evidenceUntil(result: PlanResult, appliedUntil: string | null): Record<string, unknown> {
    const body = result as unknown as Record<string, unknown>
    return calculationEvidenceFixture(body, {
      versions: {
        official: [
          {
            pointerVersion: result.pointerVersion,
            calculationId: result.calculationId,
            publishedAt: result.publishedAt,
            appliedUntil,
            current: true,
          },
        ],
        unpublished: [],
      },
    })
  }

  it('the current version of an ended plan is applied until exactly its end time', () => {
    const shown = endedBundle()
    expect(parseEvidence(evidenceUntil(shown, ENDED), shown, ENDED)).not.toBeNull()
    // Still applied, another time, or no end time to compare with: not the lineage of this record.
    expect(parseEvidence(evidenceUntil(shown, null), shown, ENDED)).toBeNull()
    expect(parseEvidence(evidenceUntil(shown, '2026-09-28T03:04:05Z'), shown, ENDED)).toBeNull()
    expect(parseEvidence(evidenceUntil(shown, ENDED), shown, null)).toBeNull()
  })

  it('an active plan’s current version is still applied', () => {
    const shown = bundle()
    expect(parseEvidence(evidenceUntil(shown, null), shown)).not.toBeNull()
    expect(parseEvidence(evidenceUntil(shown, ENDED), shown)).toBeNull()
  })

  it('reads the record’s evidence with the same three reference values', async () => {
    const shown = endedBundle()
    let asked = ''
    stubCalculationEvidence((url) => {
      asked = url
      return Promise.resolve(json(evidenceUntil(shown, ENDED)))
    })
    const { result } = renderHook(() => useCalculationEvidence(shown, noop, ENDED))
    act(() => {
      result.current.open()
    })
    await waitFor(() => {
      expect(result.current.view.kind).toBe('ready')
    })
    const query = new URL(asked, 'http://localhost').searchParams
    expect(query.get('planId')).toBe(shown.planId)
    expect(query.get('pointerVersion')).toBe(String(shown.pointerVersion))
    expect(query.get('calculationId')).toBe(shown.calculationId)
  })

  it('closes the evidence read while the plan was active when the same version becomes the record', async () => {
    const active = bundle()
    answers(() => Promise.resolve(json(evidenceUntil(active, null))))
    const { result, rerender } = renderHook(
      ({ shown, endedAt }: { shown: PlanResult; endedAt: string | null }) =>
        useCalculationEvidence(shown, noop, endedAt),
      { initialProps: { shown: active, endedAt: null as string | null } },
    )
    act(() => {
      result.current.open()
    })
    await waitFor(() => {
      expect(result.current.view.kind).toBe('ready')
    })
    rerender({ shown: endedBundle(), endedAt: ENDED })
    expect(result.current.view).toEqual({ kind: 'closed' })
  })
})
