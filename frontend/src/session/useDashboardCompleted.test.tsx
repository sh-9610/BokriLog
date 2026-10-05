import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { comparisonName, planCompletionContent } from '../content/planCompletion'
import { planResultContent } from '../content/planResult'
import { planSyncContent } from '../content/planSync'
import { formatSeoulTime } from '../format/decimal'
import {
  adjustmentPageFixture,
  changeFixture,
  comparedMetricsFixture,
  completedDashboardFixture,
  completedProgressResultFixture,
  COMPLETION_TARGET,
  COMPLETION_VERIFIED_THROUGH,
  completionRecordFixture,
  completionVersionResultFixture,
  dashboardFixture,
  endedDashboardFixture,
  endedResultFixture,
  EVIDENCE_IDS,
  evidenceChangeFixture,
  laterEvidenceFixture,
  OBSERVED_AT,
  OBSERVED_VALUE,
  observedFixture,
  planResultFixture,
  PREVIOUS_BASIS_AT,
  SCHEDULED_END_AT,
  syncFixture,
  syncJobFixture,
  trustFixture,
} from '../test/planResultFixture'
import { getObservedStorageEntries, stubDashboard } from '../test/setup'
import { parseAdjustmentPage } from './useAdjustmentHistory'
import { parseEvidence } from './useCalculationEvidence'
import {
  bundleOf,
  dashboardAnnouncement,
  isCompletionVersion,
  parseDashboard,
  supersedes,
  terminalOf,
  UNKNOWN_PLAN_END,
  UNKNOWN_SYNC,
  UNKNOWN_VERIFICATION,
  useDashboard,
  type Dashboard,
  type DashboardView,
  type PlanResult,
} from './useDashboard'

/*
 * Story 2.14: a plan completed at its scheduled end `T` (`COMPLETED`), as the dashboard reads it — its completion
 * record, the value later syncs measured, and its sync block. The panel and the screens have their own tests.
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

/** A 24-hour delay, in milliseconds: the longest that still allows the comparison. */
const DAY = 24 * 60 * 60 * 1000

/** The instant `millis` after `T`, in the server's format. */
function afterEnd(millis: number): string {
  return new Date(Date.parse(SCHEDULED_END_AT) + millis).toISOString()
}

/** A completion version observed at the given time, and its record with the given comparison and reason. */
function decided(
  comparison: string,
  reason: string | null,
  options: { readonly comparable?: boolean; readonly observedAt?: string } = {},
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const result = completionVersionResultFixture(options)
  return completedDashboardFixture(
    result,
    completionRecordFixture(result, { comparison, reason, ...overrides }),
  )
}

/** A progress version with its record, observed by default, with the given comparison and reason. */
function undecided(
  comparison: string,
  reason: string | null,
  overrides: Record<string, unknown> = observedFixture(),
): Record<string, unknown> {
  const result = completedProgressResultFixture()
  // A comparison that cannot be calculated is final; checking and held ones run again by default.
  const retryable = comparison !== 'UNAVAILABLE'
  return completedDashboardFixture(
    result,
    completionRecordFixture(result, { comparison, reason, retryable, ...overrides }),
  )
}

describe('parseDashboard: COMPLETED (Story 2.14)', () => {
  it('reads a plan whose T has passed before any observation: checking, nothing observed, a sync for the observation', () => {
    const parsed = parseDashboard(completedDashboardFixture())
    expect(parsed).toMatchObject({
      state: 'COMPLETED',
      nextAction: 'SYNC_FOR_OBSERVATION',
      result: { planStatus: 'COMPLETED', pointerVersion: 1 },
      completion: {
        scheduledEndAt: SCHEDULED_END_AT,
        comparison: 'PENDING',
        reason: null,
        observedValue: null,
        observedMeasuredAt: null,
        observationDelayMillis: null,
        completionTarget: null,
        postEndCashflowCount: null,
      },
      postEnd: null,
      sync: { availability: 'AVAILABLE', latest: null },
    })
    expect(parsed).not.toHaveProperty('verification')
    expect(parsed).not.toHaveProperty('planEnd')
  })

  it('reads a fixed observation while the comparison is still checking, on the last progress version', () => {
    expect(parseDashboard(undecided('PENDING', null))).toMatchObject({
      state: 'COMPLETED',
      completion: {
        comparison: 'PENDING',
        observedValue: OBSERVED_VALUE,
        observedMeasuredAt: OBSERVED_AT,
        observationDelayMillis: 3_600_000,
        completionTarget: null,
      },
    })
  })

  it('reads a confirmed completion on the completion version: its observation, target and no flow after T', () => {
    const parsed = parseDashboard(decided('CONFIRMED', null))
    expect(parsed).toMatchObject({
      state: 'COMPLETED',
      nextAction: 'PREPARE_NEXT_PLAN',
      result: { basisAt: SCHEDULED_END_AT, measuredAt: OBSERVED_AT },
      completion: {
        comparison: 'CONFIRMED',
        observedValue: OBSERVED_VALUE,
        completionTarget: COMPLETION_TARGET,
        postEndCashflowCount: 0,
      },
    })
    if (parsed?.state !== 'COMPLETED') throw new Error('not completed')
    expect(isCompletionVersion(parsed.result)).toBe(true)
  })

  it('confirms a delay of exactly 24 hours, and cannot calculate one a millisecond longer', () => {
    expect(parseDashboard(decided('CONFIRMED', null, { observedAt: afterEnd(DAY) }))?.state).toBe(
      'COMPLETED',
    )
    expect(
      parseDashboard(
        decided('UNAVAILABLE', 'OBSERVATION_DELAY_EXCEEDED', {
          comparable: false,
          observedAt: afterEnd(DAY + 1),
        }),
      ),
    ).toMatchObject({
      completion: { comparison: 'UNAVAILABLE', reason: 'OBSERVATION_DELAY_EXCEEDED' },
    })
  })

  it('reads flows after T: not comparable, its derived metrics without a value', () => {
    const parsed = parseDashboard(
      decided(
        'UNAVAILABLE',
        'POST_END_CASHFLOW',
        { comparable: false },
        { postEndCashflowCount: 2 },
      ),
    )
    expect(parsed).toMatchObject({
      completion: { reason: 'POST_END_CASHFLOW', postEndCashflowCount: 2 },
      result: {
        metrics: [
          { metric: 'CURRENT_VALUE', value: OBSERVED_VALUE },
          { metric: 'PLAN_AMOUNT', value: COMPLETION_TARGET },
          { metric: 'PLAN_DIFFERENCE', unavailableReason: 'COMPLETION_NOT_COMPARABLE' },
          { metric: 'ADJUSTED_TARGET' },
          { metric: 'REQUIRED_RATE', unavailableReason: 'REMAINING_PERIOD_NOT_POSITIVE' },
          { metric: 'GOAL_PROGRESS', unavailableReason: 'COMPLETION_NOT_COMPARABLE' },
        ],
      },
    })
  })

  it.each([
    ['a data gap before the observation', 'DATA_GAP', {}],
    ['a data gap with the observation', 'DATA_GAP', observedFixture()],
    ['history out of range', 'HISTORY_OUT_OF_RANGE', observedFixture()],
  ])(
    'reads %s without a completion calculation, on the last progress version',
    (_name, reason, overrides) => {
      const parsed = parseDashboard(undecided('UNAVAILABLE', reason, overrides))
      expect(parsed).toMatchObject({
        state: 'COMPLETED',
        completion: { comparison: 'UNAVAILABLE', reason, completionTarget: null },
        result: { pointerVersion: 1 },
      })
    },
  )

  it('reads a held comparison with its review reason, keeping the first observation', () => {
    expect(parseDashboard(undecided('HELD', 'INPUT_REVIEW_REQUIRED'))).toMatchObject({
      nextAction: 'SYNC_FOR_OBSERVATION',
      completion: {
        comparison: 'HELD',
        reason: 'INPUT_REVIEW_REQUIRED',
        observedValue: OBSERVED_VALUE,
      },
    })
  })

  it('keeps a comparison or a reason it does not know, to fold to safe copy, instead of rejecting the answer', () => {
    expect(parseDashboard(undecided('NEW_COMPARISON', 'NEW_REASON'))).toMatchObject({
      completion: { comparison: 'NEW_COMPARISON', reason: 'NEW_REASON' },
    })
    expect(parseDashboard(undecided('UNAVAILABLE', 'NEW_REASON'))).toMatchObject({
      completion: { comparison: 'UNAVAILABLE', reason: 'NEW_REASON' },
    })
    expect(parseDashboard(undecided('HELD', 'NEW_REASON'))?.state).toBe('COMPLETED')
    expect(comparisonName('NEW_COMPARISON')).toBe(planCompletionContent.result.unknownComparison)
  })

  it('accepts absent nullable keys as null and the same instants in another notation', () => {
    const result = completedProgressResultFixture()
    expect(
      parseDashboard({
        state: 'COMPLETED',
        nextAction: 'SYNC_FOR_OBSERVATION',
        result,
        completion: {
          scheduledEndAt: SCHEDULED_END_AT.replace('Z', '.000Z'),
          comparison: 'PENDING',
          retryable: true,
        },
      }),
    ).toMatchObject({ completion: { observedValue: null }, postEnd: null, sync: UNKNOWN_SYNC })
  })

  it.each([
    ['a next action the state cannot carry', { nextAction: 'NONE' }],
    ['an active plan status', { result: planResultFixture() }],
    ['a plan ended early', { result: endedResultFixture() }],
    [
      'a freshness that is not the completed one',
      { result: completedProgressResultFixture({ trust: trustFixture() }) },
    ],
    ['no completion record', { completion: undefined }],
    [
      'a scheduled end that is not the plan’s T',
      {
        completion: completionRecordFixture(completedProgressResultFixture(), {
          scheduledEndAt: '2026-11-01T00:00:00Z',
        }),
      },
    ],
    [
      'only part of an observation',
      {
        completion: completionRecordFixture(completedProgressResultFixture(), {
          observedValue: OBSERVED_VALUE,
        }),
      },
    ],
    [
      'an observation before T',
      {
        completion: completionRecordFixture(completedProgressResultFixture(), {
          ...observedFixture(afterEnd(-1)),
          observationDelayMillis: 0,
        }),
      },
    ],
    [
      'a delay that is not the observation minus T',
      {
        completion: completionRecordFixture(completedProgressResultFixture(), {
          ...observedFixture(),
          observationDelayMillis: 3_600_001,
        }),
      },
    ],
    [
      'an observed value that is not a canonical decimal',
      {
        completion: completionRecordFixture(completedProgressResultFixture(), {
          ...observedFixture(),
          observedValue: '1300.00',
        }),
      },
    ],
    [
      'a completion target on a progress version',
      {
        completion: completionRecordFixture(completedProgressResultFixture(), {
          completionTarget: COMPLETION_TARGET,
        }),
      },
    ],
    [
      'a checking comparison with a reason',
      {
        completion: completionRecordFixture(completedProgressResultFixture(), {
          reason: 'DATA_GAP',
        }),
      },
    ],
  ])('rejects the whole answer for %s', (_name, overrides) => {
    expect(parseDashboard({ ...completedDashboardFixture(), ...overrides })).toBeNull()
  })

  it.each([
    ['a confirmed comparison without a completion version', undecided('CONFIRMED', null)],
    [
      'a confirmed comparison after more than 24 hours',
      decided('CONFIRMED', null, { observedAt: afterEnd(DAY + 1) }),
    ],
    [
      'a confirmed comparison with flows after T',
      decided('CONFIRMED', null, {}, { postEndCashflowCount: 1 }),
    ],
    ['a confirmed comparison with a reason', decided('CONFIRMED', 'DATA_GAP')],
    [
      'a delay not over 24 hours named as exceeded',
      decided('UNAVAILABLE', 'OBSERVATION_DELAY_EXCEEDED', { comparable: false }),
    ],
    [
      'flows after T named without any',
      decided('UNAVAILABLE', 'POST_END_CASHFLOW', { comparable: false }),
    ],
    [
      'a not-comparable completion whose derived metrics still have values',
      decided('UNAVAILABLE', 'POST_END_CASHFLOW', {}, { postEndCashflowCount: 1 }),
    ],
    ['a comparison that cannot be calculated without a reason', undecided('UNAVAILABLE', null)],
    [
      'a data gap on a completion version',
      decided('UNAVAILABLE', 'DATA_GAP', { comparable: false }),
    ],
    ['a held comparison with an unavailable reason', undecided('HELD', 'DATA_GAP')],
    ['a held comparison on a completion version', decided('HELD', 'INPUT_REVIEW_REQUIRED')],
    [
      'a completion version whose current value is not the observation',
      decided('CONFIRMED', null, {}, { observedValue: '1299' }),
    ],
    [
      'a completion version whose plan amount is not the target',
      decided('CONFIRMED', null, {}, { completionTarget: '1330' }),
    ],
    [
      'a completion version without its observation',
      decided(
        'CONFIRMED',
        null,
        {},
        {
          observedValue: null,
          observedMeasuredAt: null,
          observationDelayMillis: null,
        },
      ),
    ],
    [
      'a completion version measured at another time than the observation',
      decided('CONFIRMED', null, {}, observedFixture(afterEnd(1_000), OBSERVED_VALUE)),
    ],
    [
      'a progress version saying the comparison is not comparable',
      completedDashboardFixture(
        completedProgressResultFixture({
          trust: trustFixture({
            freshness: {
              state: 'COMPLETED',
              cause: 'PLAN_COMPLETED',
              nextAction: 'NONE',
            },
            calculationNature: {
              state: 'NOT_COMPUTABLE',
              cause: 'METRIC_UNDEFINED',
              unavailableMetrics: ['PLAN_DIFFERENCE'],
              affectedMetrics: ['PLAN_DIFFERENCE'],
            },
          }),
          metrics: [
            { metric: 'CURRENT_VALUE', value: '1250', nature: 'OFFICIAL' },
            { metric: 'PLAN_AMOUNT', value: '1210', nature: 'OFFICIAL' },
            {
              metric: 'PLAN_DIFFERENCE',
              nature: 'OFFICIAL',
              unavailableReason: 'COMPLETION_NOT_COMPARABLE',
            },
            { metric: 'ADJUSTED_TARGET', value: '1331', nature: 'OFFICIAL' },
            { metric: 'REQUIRED_RATE', value: '0.0648', nature: 'OFFICIAL' },
            { metric: 'GOAL_PROGRESS', value: '0.5', nature: 'OFFICIAL' },
          ],
        }),
      ),
    ],
  ])('rejects a contradicting answer: %s', (_name, body) => {
    expect(parseDashboard(body)).toBeNull()
  })

  it('reads the later value only when it is whole and later than the first observation', () => {
    const result = completionVersionResultFixture()
    const record = completionRecordFixture(result)
    const later = { value: '1302.1', measuredAt: '2026-10-26T01:00:00Z' }
    expect(
      parseDashboard(completedDashboardFixture(result, record, { postEnd: later })),
    ).toMatchObject({ postEnd: later })
    for (const postEnd of [
      { value: '1302.10', measuredAt: later.measuredAt },
      { value: '1302.1', measuredAt: 'later' },
      { value: '1302.1', measuredAt: OBSERVED_AT },
      { value: '1302.1' },
    ]) {
      // A later value this screen cannot vouch for is not shown; the completion stays.
      expect(parseDashboard(completedDashboardFixture(result, record, { postEnd }))).toMatchObject({
        state: 'COMPLETED',
        postEnd: null,
      })
    }
    // Before the first observation there is no later value either.
    expect(
      parseDashboard(
        completedDashboardFixture(completedProgressResultFixture(), undefined, { postEnd: later }),
      ),
    ).toMatchObject({ postEnd: null })
  })

  it('reads the sync block like a ready plan’s, never a long gap, and a job of the new purposes', () => {
    const job = syncJobFixture({ purpose: 'COMPLETION', stage: 'OBSERVATION', status: 'RUNNING' })
    expect(
      parseDashboard(
        completedDashboardFixture(undefined, undefined, {
          sync: syncFixture({ availability: 'IN_PROGRESS', latest: job }),
        }),
      ),
    ).toMatchObject({
      sync: {
        availability: 'IN_PROGRESS',
        latest: { purpose: 'COMPLETION', stage: 'OBSERVATION' },
      },
    })
    expect(
      parseDashboard(
        completedDashboardFixture(undefined, undefined, {
          sync: syncFixture({ availability: 'LONG_GAP' }),
        }),
      ),
    ).toMatchObject({ state: 'COMPLETED', sync: UNKNOWN_SYNC })
    expect(
      parseDashboard(
        completedDashboardFixture(undefined, undefined, {
          sync: syncFixture({ availability: 'MAYBE' }),
        }),
      ),
    ).toMatchObject({ sync: UNKNOWN_SYNC })
  })
})

describe('supersedes: the two ends of a plan (Story 2.14)', () => {
  const active = planResultFixture() as unknown as PlanResult
  const pending = completedProgressResultFixture() as unknown as PlanResult
  const confirmed = completionVersionResultFixture() as unknown as PlanResult
  const ended = endedResultFixture() as unknown as PlanResult

  it('takes a completion after the same plan’s active bundle, and a newer completion version after it', () => {
    expect(supersedes(pending, active, 'COMPLETED', null)).toBe(true)
    expect(supersedes(confirmed, pending, 'COMPLETED', 'COMPLETED')).toBe(true)
  })

  it('never takes the same plan back as active, nor the other end, after a completion', () => {
    expect(supersedes(active, pending, null, 'COMPLETED')).toBe(false)
    expect(supersedes(ended, pending, 'ENDED', 'COMPLETED')).toBe(false)
    expect(supersedes(pending, ended, 'COMPLETED', 'ENDED')).toBe(false)
    expect(supersedes(pending, confirmed, 'COMPLETED', 'COMPLETED')).toBe(false)
  })

  it('refuses another plan with the old baseline', () => {
    const other = planResultFixture({ planId: 'plan-2' }) as unknown as PlanResult
    expect(supersedes(other, confirmed, null, 'COMPLETED')).toBe(false)
  })
})

describe('useDashboard: a plan completed at its scheduled end (Story 2.14)', () => {
  it('replaces the same plan’s active bundle at once, with its sync block and nothing to verify or end', async () => {
    answers(
      () => Promise.resolve(json(dashboardFixture())),
      () => Promise.resolve(json(completedDashboardFixture())),
    )
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'READY')
    act(() => {
      result.current.reload()
    })
    const view = await settledView(result, 'COMPLETED')
    const bundle = bundleOf(view)
    expect(terminalOf(bundle)).toBe('COMPLETED')
    expect(bundle?.completion).toMatchObject({ comparison: 'PENDING' })
    expect(bundle?.sync.availability).toBe('AVAILABLE')
    expect(bundle?.verification).toBe(UNKNOWN_VERIFICATION)
    expect(bundle?.planEnd).toBe(UNKNOWN_PLAN_END)
    expect(bundle?.change.kind).toBe('NONE')
    expect(bundle?.end).toBeNull()
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('swaps a checking completion for its completion version in one piece, and keeps it against an older answer', async () => {
    const result = completionVersionResultFixture()
    answers(
      () => Promise.resolve(json(completedDashboardFixture())),
      () => Promise.resolve(json(completedDashboardFixture(result))),
      () => Promise.resolve(json(completedDashboardFixture())),
    )
    const { result: dashboard } = renderHook(() => useDashboard(true))
    await settledView(dashboard, 'COMPLETED')
    act(() => {
      dashboard.current.reload()
    })
    await waitFor(() => {
      expect(bundleOf(dashboard.current.view)?.completion?.comparison).toBe('CONFIRMED')
    })
    act(() => {
      dashboard.current.reload()
    })
    const view = await settledView(dashboard, 'retryable')
    expect(bundleOf(view)?.completion?.comparison).toBe('CONFIRMED')
  })

  it('never takes the plan back as active after its completion: the completion stays, as a read to retry', async () => {
    answers(
      () => Promise.resolve(json(completedDashboardFixture())),
      () => Promise.resolve(json(dashboardFixture())),
    )
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'COMPLETED')
    act(() => {
      result.current.reload()
    })
    const view = await settledView(result, 'retryable')
    expect(terminalOf(bundleOf(view))).toBe('COMPLETED')
  })

  it('never switches a completed plan to ended early, nor the other way', async () => {
    answers(
      () => Promise.resolve(json(endedDashboardFixture())),
      () => Promise.resolve(json(completedDashboardFixture())),
    )
    const { result } = renderHook(() => useDashboard(true))
    await settledView(result, 'ENDED')
    act(() => {
      result.current.reload()
    })
    const view = await settledView(result, 'retryable')
    expect(terminalOf(bundleOf(view))).toBe('ENDED')
  })
})

describe('dashboardAnnouncement: COMPLETED (Story 2.14)', () => {
  function dashboardOf(body: Record<string, unknown>): Dashboard {
    const parsed = parseDashboard(body)
    if (parsed?.state !== 'COMPLETED') throw new Error('not completed')
    const view: DashboardView = {
      kind: 'COMPLETED',
      result: parsed.result,
      nextAction: parsed.nextAction,
      sync: parsed.sync,
      verification: UNKNOWN_VERIFICATION,
      change: parsed.change,
      planEnd: UNKNOWN_PLAN_END,
      end: null,
      completion: parsed.completion,
      postEnd: parsed.postEnd,
      postEndUnreadable: parsed.postEndUnreadable,
    }
    return { view, loading: false, reload: () => undefined, refresh: () => undefined }
  }

  const T = formatSeoulTime(SCHEDULED_END_AT, planResultContent.timeUnavailable)

  it('names T and the comparison once the completion is shown', () => {
    expect(dashboardAnnouncement(dashboardOf(completedDashboardFixture()))).toBe(
      planCompletionContent.status.shown(T, '확인 중'),
    )
    expect(dashboardAnnouncement(dashboardOf(decided('CONFIRMED', null)))).toBe(
      planCompletionContent.status.shown(T, '확정'),
    )
  })

  it('says the sync’s own sentences while it runs or ends without a new bundle', () => {
    const dashboard = dashboardOf(completedDashboardFixture())
    expect(dashboardAnnouncement(dashboard, 'running')).toBe(planSyncContent.status.syncing)
    expect(dashboardAnnouncement(dashboard, 'failed')).toBe(planSyncContent.status.failed)
  })

  it('after a sync: the completion result when it ran the completion or still decides, the later value once final', () => {
    const result = completionVersionResultFixture()
    const record = completionRecordFixture(result)
    const finished = (purpose: string): Record<string, unknown> =>
      syncFixture({
        latest: syncJobFixture({
          purpose,
          status: 'SUCCEEDED',
          finishedAt: '2026-10-26T01:00:04Z',
        }),
      })
    // The completion job that confirmed it.
    expect(
      dashboardAnnouncement(
        dashboardOf(completedDashboardFixture(result, record, { sync: finished('COMPLETION') })),
        'succeeded',
      ),
    ).toBe(planCompletionContent.status.synced('확정'))
    // A comparison still deciding.
    expect(
      dashboardAnnouncement(dashboardOf(undecided('HELD', 'INPUT_REVIEW_REQUIRED')), 'succeeded'),
    ).toBe(planCompletionContent.status.synced('확정 보류'))
    // F8: after the comparison is final, what the later sync brought — never "the completion result".
    const later = { value: '1302.1', measuredAt: '2026-10-26T01:00:00Z' }
    expect(
      dashboardAnnouncement(
        dashboardOf(
          completedDashboardFixture(result, record, {
            postEnd: later,
            sync: finished('POST_END_VALUE'),
          }),
        ),
        'succeeded',
      ),
    ).toBe(planCompletionContent.status.postEndSynced)
    expect(
      dashboardAnnouncement(
        dashboardOf(
          completedDashboardFixture(result, record, { sync: finished('POST_END_VALUE') }),
        ),
        'succeeded',
      ),
    ).toBe(planCompletionContent.status.postEndNone)
    expect(
      dashboardAnnouncement(
        dashboardOf(
          completedDashboardFixture(result, record, {
            postEnd: { value: 'x', measuredAt: later.measuredAt },
            sync: finished('POST_END_VALUE'),
          }),
        ),
        'succeeded',
      ),
    ).toBe(planCompletionContent.status.postEndUnreadable)
  })
})

describe('the evidence and the history of a completion version (Story 2.14)', () => {
  const result = completionVersionResultFixture()
  const shown = result as unknown as PlanResult

  /** The evidence of the completion version, its collection range ending at the given time. */
  function evidenceUntil(rangeEnd: unknown): Record<string, unknown> {
    const evidence = laterEvidenceFixture(result, {
      change: evidenceChangeFixture({
        kind: 'VERIFIED_NO_CHANGE',
        purpose: 'COMPLETION',
        previous: {
          pointerVersion: 1,
          calculationId: EVIDENCE_IDS.calculation,
          basisAt: PREVIOUS_BASIS_AT,
        },
        metrics: comparedMetricsFixture(result),
      }),
    })
    const inputs = evidence.inputs as Record<string, Record<string, unknown>>
    return { ...evidence, inputs: { ...inputs, sourceRange: { ...inputs.sourceRange, rangeEnd } } }
  }

  it('reads the evidence whose collection runs up to the check after the observation, (t0, U]', () => {
    expect(parseEvidence(evidenceUntil(COMPLETION_VERIFIED_THROUGH), shown)).not.toBeNull()
    // The basis time T is not where a completion version's collection ends.
    expect(parseEvidence(evidenceUntil(SCHEDULED_END_AT), shown)).toBeNull()
  })

  it('reads the history whose range is (t0, T], not up to the later check', () => {
    const page = adjustmentPageFixture(result, { rows: [] })
    const coverage = page.coverage as Record<string, unknown>
    expect(
      parseAdjustmentPage(
        { ...page, coverage: { ...coverage, through: SCHEDULED_END_AT } },
        shown,
        'ALL',
      ),
    ).not.toBeNull()
    expect(
      parseAdjustmentPage(
        { ...page, coverage: { ...coverage, through: COMPLETION_VERIFIED_THROUGH } },
        shown,
        'ALL',
      ),
    ).toBeNull()
  })
})

describe('the calculation kind and the retry flag (Story 2.14 review)', () => {
  it('F2: every result names its kind; ready and ended ones are progress versions', () => {
    const withoutKind = planResultFixture({ calculationKind: undefined })
    expect(parseDashboard(dashboardFixture(withoutKind))).toBeNull()
    expect(
      parseDashboard(dashboardFixture(planResultFixture({ calculationKind: 'OTHER' }))),
    ).toBeNull()
    expect(
      parseDashboard(dashboardFixture(planResultFixture({ calculationKind: 'COMPLETION' }))),
    ).toBeNull()
    expect(
      parseDashboard({
        ...endedDashboardFixture(),
        result: endedResultFixture({ calculationKind: 'COMPLETION' }),
      }),
    ).toBeNull()
  })

  it('F2: the completion version is the one named so, and it is calculated at T', () => {
    const progressAtT = completedProgressResultFixture({ calculationKind: 'COMPLETION' })
    expect(
      parseDashboard(completedDashboardFixture(progressAtT, completionRecordFixture(progressAtT))),
    ).toBeNull()
    const named = completionVersionResultFixture({}, { calculationKind: 'PROGRESS' })
    expect(
      parseDashboard(
        completedDashboardFixture(named, completionRecordFixture(completionVersionResultFixture())),
      ),
    ).toBeNull()
  })

  it('F2: the evidence names the shown version’s kind', () => {
    const result = completionVersionResultFixture()
    const shown = result as unknown as PlanResult
    const evidence = laterEvidenceFixture(result, {
      change: evidenceChangeFixture({
        kind: 'VERIFIED_NO_CHANGE',
        purpose: 'COMPLETION',
        previous: {
          pointerVersion: 1,
          calculationId: EVIDENCE_IDS.calculation,
          basisAt: PREVIOUS_BASIS_AT,
        },
        metrics: comparedMetricsFixture(result),
      }),
    })
    const inputs = evidence.inputs as Record<string, Record<string, unknown>>
    const whole: Record<string, unknown> = {
      ...evidence,
      inputs: {
        ...inputs,
        sourceRange: { ...inputs.sourceRange, rangeEnd: COMPLETION_VERIFIED_THROUGH },
      },
    }
    expect(parseEvidence(whole, shown)).not.toBeNull()
    const calculation = whole.calculation as Record<string, unknown>
    expect(
      parseEvidence(
        { ...whole, calculation: { ...calculation, calculationKind: 'PROGRESS' } },
        shown,
      ),
    ).toBeNull()
  })

  it('C2: the retry flag is required, false once final, and the next action follows it', () => {
    const result = completionVersionResultFixture()
    const record = completionRecordFixture(result)
    const withoutFlag = { ...record, retryable: undefined }
    expect(parseDashboard(completedDashboardFixture(result, withoutFlag))).toBeNull()
    expect(
      parseDashboard(completedDashboardFixture(result, { ...record, retryable: true })),
    ).toBeNull()
    expect(
      parseDashboard(
        completedDashboardFixture(result, record, { nextAction: 'SYNC_FOR_OBSERVATION' }),
      ),
    ).toBeNull()
    expect(
      parseDashboard(
        completedDashboardFixture(undefined, undefined, { nextAction: 'PREPARE_NEXT_PLAN' }),
      ),
    ).toBeNull()
    // A held comparison may be final: no re-check, the preparation of a new plan.
    expect(
      parseDashboard(
        undecided('HELD', 'INPUT_REVIEW_REQUIRED', { ...observedFixture(), retryable: false }),
      ),
    ).toMatchObject({ nextAction: 'PREPARE_NEXT_PLAN', completion: { retryable: false } })
  })

  it('C3: a completed answer carries its version change', () => {
    const result = completionVersionResultFixture()
    expect(
      parseDashboard(
        completedDashboardFixture(result, completionRecordFixture(result), {
          change: changeFixture({ kind: 'VERIFIED_NO_CHANGE', purpose: 'COMPLETION' }),
        }),
      ),
    ).toMatchObject({ change: { kind: 'VERIFIED_NO_CHANGE', purpose: 'COMPLETION' } })
  })
})
