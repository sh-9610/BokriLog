import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { planEndContent } from '../content/planEnd'
import { planResultContent } from '../content/planResult'
import { formatSeoulTime } from '../format/decimal'
import {
  dashboardFixture,
  ENDED_AT,
  endedDashboardFixture,
  endedResultFixture,
  endRecordFixture,
  longGapEndRecordFixture,
  MEASURED_AT,
  planEndFixture,
  planResultFixture,
  trustFixture,
  VERIFIED_THROUGH,
} from '../test/planResultFixture'
import { getObservedStorageEntries, stubDashboard } from '../test/setup'
import {
  bundleOf,
  dashboardAnnouncement,
  NO_CHANGE,
  parseDashboard,
  parsePlanEndStatus,
  UNKNOWN_PLAN_END,
  UNKNOWN_SYNC,
  UNKNOWN_VERIFICATION,
  useDashboard,
  type DashboardView,
} from './useDashboard'

/*
 * Story 2.13: the record of a plan ended early (`ENDED`) and the `planEnd` block of an active plan (`READY`), as
 * the dashboard reads them. The panel and the screens have their own tests.
 */

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** Answers the dashboard reads in order. */
function answers(...queue: (() => Promise<Response>)[]): void {
  stubDashboard(() => {
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected dashboard read')
    return next()
  })
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

describe('parseDashboard: ENDED (Story 2.13)', () => {
  it('reads the record of a plan ended early: its last bundle, its end record, and nothing about syncs', () => {
    const parsed = parseDashboard(endedDashboardFixture())
    expect(parsed).toMatchObject({
      state: 'ENDED',
      nextAction: 'PREPARE_NEXT_PLAN',
      result: { planStatus: 'ENDED_EARLY', pointerVersion: 1 },
      end: {
        reason: 'USER_REQUEST',
        endedAt: ENDED_AT,
        lastNormalMeasuredAt: MEASURED_AT,
        completion: 'NOT_APPLICABLE',
        uncollectedFrom: null,
        uncollectedUntil: null,
      },
    })
    expect(parsed).not.toHaveProperty('sync')
    expect(parsed).not.toHaveProperty('planEnd')
  })

  it('reads a long-gap end: the gap from the last check to the end, and a comparison that cannot be calculated', () => {
    const result = endedResultFixture()
    expect(
      parseDashboard(endedDashboardFixture(result, longGapEndRecordFixture(result))),
    ).toMatchObject({
      state: 'ENDED',
      end: {
        reason: 'LONG_GAP_NEW_PLAN',
        completion: 'UNAVAILABLE_DATA_GAP',
        uncollectedFrom: VERIFIED_THROUGH,
        uncollectedUntil: ENDED_AT,
      },
    })
  })

  it('ignores sync, verification, change and plan-end blocks an ended answer should not carry', () => {
    const body = { ...endedDashboardFixture(), sync: {}, verification: {}, change: {}, planEnd: {} }
    expect(parseDashboard(body)?.state).toBe('ENDED')
  })

  it('folds an end reason or a completion it does not know to safe copy instead of rejecting the record', () => {
    const result = endedResultFixture()
    const parsed = parseDashboard(
      endedDashboardFixture(
        result,
        endRecordFixture(result, { reason: 'SOMETHING_NEW', completion: 'NEW_COMPARISON' }),
      ),
    )
    expect(parsed).toMatchObject({
      end: { reason: 'SOMETHING_NEW', completion: 'NEW_COMPARISON' },
    })
    // A known reason with a comparison this screen does not know is folded too, never rejected.
    expect(
      parseDashboard(
        endedDashboardFixture(result, endRecordFixture(result, { completion: 'NEW_COMPARISON' })),
      )?.state,
    ).toBe('ENDED')
  })

  it.each([
    ['a next action the state cannot carry', { nextAction: 'NONE' }],
    ['an active plan status', { result: planResultFixture() }],
    ['no end record', { end: undefined }],
    [
      'an end time that is not an instant',
      { end: endRecordFixture(endedResultFixture(), { endedAt: 'soon' }) },
    ],
    [
      'an end before the last normal measurement',
      {
        end: endRecordFixture(endedResultFixture(), {
          endedAt: '2026-09-27T00:00:00Z',
          lastNormalDelayMillis: 0,
        }),
      },
    ],
    [
      'a scheduled end that is not the plan’s T',
      { end: endRecordFixture(endedResultFixture(), { scheduledEndAt: '2026-11-01T00:00:00Z' }) },
    ],
    [
      'a last normal measurement that is not the bundle’s',
      {
        end: endRecordFixture(endedResultFixture(), {
          lastNormalMeasuredAt: '2026-09-26T00:00:00Z',
        }),
      },
    ],
    [
      'a negative delay',
      { end: endRecordFixture(endedResultFixture(), { lastNormalDelayMillis: -1 }) },
    ],
    [
      'a delay that is not a whole number',
      { end: endRecordFixture(endedResultFixture(), { lastNormalDelayMillis: 1.5 }) },
    ],
    [
      'a delay that is not the end minus the last normal measurement',
      {
        end: endRecordFixture(endedResultFixture(), {
          lastNormalDelayMillis: Date.parse(ENDED_AT) - Date.parse(MEASURED_AT) + 1,
        }),
      },
    ],
    [
      'an end at the scheduled end T (that is no early end)',
      {
        end: endRecordFixture(endedResultFixture(), {
          endedAt: (endedResultFixture().conditions as Record<string, string>).endAt,
        }),
      },
    ],
    [
      'an end after the scheduled end T',
      { end: endRecordFixture(endedResultFixture(), { endedAt: '2026-10-26T00:00:00Z' }) },
    ],
    [
      'a freshness that is not the ended one',
      { result: endedResultFixture({ trust: trustFixture() }) },
    ],
    [
      'an early end the user asked for with an uncollected range',
      {
        end: endRecordFixture(endedResultFixture(), {
          uncollectedFrom: VERIFIED_THROUGH,
          uncollectedUntil: ENDED_AT,
        }),
      },
    ],
    [
      'an early end the user asked for whose comparison is a data gap',
      { end: endRecordFixture(endedResultFixture(), { completion: 'UNAVAILABLE_DATA_GAP' }) },
    ],
    [
      'a long-gap end without its range',
      {
        end: longGapEndRecordFixture(endedResultFixture(), {
          uncollectedFrom: null,
          uncollectedUntil: null,
        }),
      },
    ],
    [
      'a long-gap end whose comparison is not applicable',
      { end: longGapEndRecordFixture(endedResultFixture(), { completion: 'NOT_APPLICABLE' }) },
    ],
    [
      'a long-gap range that does not start at the last check',
      {
        end: longGapEndRecordFixture(endedResultFixture(), {
          uncollectedFrom: '2026-09-27T02:00:00Z',
        }),
      },
    ],
    [
      'a range that does not end at the end time',
      {
        end: longGapEndRecordFixture(endedResultFixture(), {
          uncollectedUntil: '2026-09-28T00:00:00Z',
        }),
      },
    ],
    [
      'only half a range',
      {
        end: endRecordFixture(endedResultFixture(), {
          reason: 'SOMETHING_NEW',
          uncollectedFrom: VERIFIED_THROUGH,
        }),
      },
    ],
  ])('rejects the whole answer for %s', (_name, overrides) => {
    expect(parseDashboard({ ...endedDashboardFixture(), ...overrides })).toBeNull()
  })

  it('accepts the same instants in another notation', () => {
    const result = endedResultFixture()
    const end = endRecordFixture(result, {
      lastNormalMeasuredAt: MEASURED_AT.replace('Z', '.000Z'),
    })
    expect(parseDashboard(endedDashboardFixture(result, end))?.state).toBe('ENDED')
  })
})

describe('parseDashboard: READY is an active plan (Story 2.13)', () => {
  it.each(['ENDED_EARLY', 'COMPLETED', 'SOMETHING_NEW'])(
    'rejects a READY answer whose plan status is %s',
    (planStatus) => {
      expect(parseDashboard(dashboardFixture(planResultFixture({ planStatus })))).toBeNull()
    },
  )

  it('accepts an active one', () => {
    expect(parseDashboard(dashboardFixture())?.state).toBe('READY')
  })
})

describe('parsePlanEndStatus: the plan-end block of READY (Story 2.13)', () => {
  it('reads the three availabilities, keeping a reason only while unavailable', () => {
    expect(parsePlanEndStatus(planEndFixture())).toEqual({
      availability: 'AVAILABLE',
      reason: null,
    })
    expect(parsePlanEndStatus(planEndFixture({ availability: 'NEW_PLAN_CHOICE' }))).toEqual({
      availability: 'NEW_PLAN_CHOICE',
      reason: null,
    })
    expect(
      parsePlanEndStatus(planEndFixture({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })),
    ).toEqual({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })
    expect(
      parsePlanEndStatus(planEndFixture({ availability: 'AVAILABLE', reason: 'RESTRICTED' })),
    ).toEqual({ availability: 'AVAILABLE', reason: null })
    // A reason this screen does not know is kept, and folded to safe copy where it is shown.
    expect(
      parsePlanEndStatus(planEndFixture({ availability: 'UNAVAILABLE', reason: 'NEW_RULE' })),
    ).toEqual({ availability: 'UNAVAILABLE', reason: 'NEW_RULE' })
  })

  it.each([
    ['missing', undefined],
    ['not an object', 'AVAILABLE'],
    ['an availability it does not know', planEndFixture({ availability: 'MAYBE' })],
    ['a reason of the wrong type', planEndFixture({ availability: 'UNAVAILABLE', reason: 3 })],
  ])('folds a block that is %s to unavailable, never rejecting the bundle', (_name, block) => {
    expect(parsePlanEndStatus(block)).toBe(UNKNOWN_PLAN_END)
    const body = { ...dashboardFixture(), planEnd: block }
    expect(parseDashboard(body)).toMatchObject({ state: 'READY', planEnd: UNKNOWN_PLAN_END })
  })
})

describe('useDashboard: the record of a plan ended early (Story 2.13)', () => {
  it('replaces the same plan’s active bundle with its record at once, and bundleOf gives the record', async () => {
    answers(
      () => Promise.resolve(json(dashboardFixture())),
      () => Promise.resolve(json(endedDashboardFixture())),
    )
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'READY')
    act(() => {
      result.current.reload()
    })
    const view = await settledView(result, 'ENDED')
    const bundle = bundleOf(view)
    expect(bundle?.result.planStatus).toBe('ENDED_EARLY')
    expect(bundle?.end).toMatchObject({ endedAt: ENDED_AT })
    // Nothing to sync, verify or end: the blocks of a plan that is not active.
    expect(bundle?.sync.availability).toBe('UNAVAILABLE')
    expect(bundle?.verification.availability).toBe('UNAVAILABLE')
    expect(bundle?.planEnd).toBe(UNKNOWN_PLAN_END)
    expect(bundle?.change.kind).toBe('NONE')
  })

  it('never takes the same plan back as active after its record: the record stays, as a read to retry', async () => {
    answers(
      () => Promise.resolve(json(endedDashboardFixture())),
      () => Promise.resolve(json(dashboardFixture())),
      () => Promise.resolve(json(endedDashboardFixture())),
    )
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'ENDED')
    act(() => {
      result.current.reload()
    })
    const view = await settledView(result, 'retryable')
    expect(view).toMatchObject({
      kind: 'retryable',
      kept: { result: { planStatus: 'ENDED_EARLY' }, end: { endedAt: ENDED_AT } },
    })
    act(() => {
      result.current.reload()
    })
    await settledView(result, 'ENDED')
  })

  it('a failed read keeps the record as the last good result', async () => {
    answers(
      () => Promise.resolve(json(endedDashboardFixture())),
      () => Promise.resolve(json({}, 500)),
    )
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'ENDED')
    act(() => {
      result.current.reload()
    })
    const view = await settledView(result, 'lastGood')
    expect(bundleOf(view)?.end).toMatchObject({ endedAt: ENDED_AT })
  })

  it('says the record once, with the actual end time, and never as a success, failure or completion', async () => {
    answers(() => Promise.resolve(json(endedDashboardFixture())))
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'ENDED')
    const sentence = dashboardAnnouncement(result.current)
    expect(sentence).toBe(
      planEndContent.status.ended(formatSeoulTime(ENDED_AT, planResultContent.timeUnavailable)),
    )
    expect(sentence).not.toMatch(/성공|실패|완료|축하|손실/)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('a reload of the record says it is reading, like any other', () => {
    const parsed = parseDashboard(endedDashboardFixture())
    if (parsed?.state !== 'ENDED') throw new Error('a malformed ENDED fixture')
    const view: DashboardView = {
      kind: 'ENDED',
      result: parsed.result,
      nextAction: parsed.nextAction,
      sync: UNKNOWN_SYNC,
      verification: UNKNOWN_VERIFICATION,
      change: NO_CHANGE,
      planEnd: UNKNOWN_PLAN_END,
      end: parsed.end,
    }
    expect(dashboardAnnouncement({ view, loading: true, reload: vi.fn(), refresh: vi.fn() })).toBe(
      planResultContent.status.reloading,
    )
  })
})
