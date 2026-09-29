import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { AdjustmentHistoryState } from '../session/useAdjustmentHistory'
import type { EvidenceState } from '../session/useCalculationEvidence'

import { ENTRY_PATH } from '../content/auth'
import { planConditionContent } from '../content/planConditions'
import {
  axisActionCopy,
  planResultContent as copy,
  METRIC_ORDER,
  NEXT_ACTION_FALLBACK,
  nextActionCopy,
} from '../content/planResult'
import { formatSeoulTime } from '../format/decimal'
import {
  isPlanResult,
  parseDashboard,
  supersedes,
  type Dashboard,
  type DashboardView,
  type PlanResult,
} from '../session/useDashboard'
import {
  AVAILABLE_SYNC,
  BASIS_AT,
  dashboardFixture,
  estimatedUndefinedFixture,
  idleSyncState,
  MEASURED_AT,
  NO_PLAN_FIXTURE,
  planResultFixture,
  PREPARING_FIXTURE,
  trustFixture,
  VERIFIED_THROUGH,
} from '../test/planResultFixture'
import { PlanResultPanel } from './PlanResultPanel'

function parsed(body: Record<string, unknown>): PlanResult {
  expect(isPlanResult(body)).toBe(true)
  return body as unknown as PlanResult
}

function ready(
  body: Record<string, unknown> = planResultFixture(),
  nextAction = 'NONE',
): DashboardView {
  return { kind: 'READY', nextAction, result: parsed(body), sync: AVAILABLE_SYNC }
}

function dashboard(view: DashboardView, loading = false, reload = vi.fn()): Dashboard {
  return { view, loading, reload, refresh: vi.fn() }
}

/** The calculation evidence while its region is closed (Story 2.9 has its own panel tests). */
function closedEvidence(): EvidenceState {
  return { view: { kind: 'closed' }, open: vi.fn(), close: vi.fn(), retry: vi.fn() }
}

/** The adjustment history while its region is closed (Story 2.10 has its own panel tests). */
function closedAdjustments(): AdjustmentHistoryState {
  return {
    view: { kind: 'closed' },
    open: vi.fn(),
    close: vi.fn(),
    filter: vi.fn(),
    more: vi.fn(),
    retry: vi.fn(),
  }
}

function renderPanel(view: DashboardView, loading = false): void {
  render(
    <PlanResultPanel
      adjustments={closedAdjustments()}
      evidence={closedEvidence()}
      dashboard={dashboard(view, loading)}
      sync={idleSyncState()}
    />,
  )
}

const NAMES = METRIC_ORDER.map((metric) => copy.metrics[metric].name)

const MEASURED = formatSeoulTime(MEASURED_AT, copy.timeUnavailable)

/** An axis group's accessible name: the axis, then the state's copy. */
function named(axis: 'freshness' | 'reliability' | 'nature', state: string): string {
  const table = copy.axes[axis]
  return `${table.name}: ${table.states[state] ?? copy.axes.unknownState}`
}

function cards(): HTMLElement[] {
  return within(screen.getByRole('region', { name: copy.heading })).getAllByRole('article')
}

function axes(): HTMLElement[] {
  return within(screen.getByRole('region', { name: copy.trustHeading })).getAllByRole('group')
}

function axis(index: number): HTMLElement {
  const found = axes()[index]
  if (found === undefined) throw new Error(`no axis ${String(index)}`)
  return found
}

function card(index: number): HTMLElement {
  const found = cards()[index]
  if (found === undefined) throw new Error(`no card ${String(index)}`)
  return found
}

/** A label followed directly by its value (the Asia/Seoul time and zone) inside a group. */
function timeBeside(label: string, value: string): string {
  return `${label}${formatSeoulTime(value, copy.timeUnavailable)} · ${copy.timeZone}`
}

/** The panel's sections in document order, by their headings. */
function sectionOrder(): string[] {
  return within(screen.getByRole('region', { name: copy.heading }))
    .getAllByRole('heading', { level: 3 })
    .map((heading) => heading.textContent)
}

function nextAction(): HTMLElement {
  return screen.getByRole('region', { name: copy.nextActionHeading })
}

describe('PlanResultPanel', () => {
  it('AC-3/AC-11: leads with the current value, then the axes, the other five metrics, the next action and the terms', () => {
    renderPanel(ready())

    expect(sectionOrder()).toEqual([
      copy.currentHeading,
      copy.trustHeading,
      copy.metricsHeading,
      copy.nextActionHeading,
      copy.conditionsHeading,
    ])
    // Six cards in the metric order; the current value is the first and appears once.
    expect(cards().map((each) => within(each).getByRole('heading').textContent)).toEqual(NAMES)
    expect(
      within(screen.getByRole('region', { name: copy.currentHeading })).getAllByRole('article'),
    ).toHaveLength(1)
    expect(
      within(screen.getByRole('region', { name: copy.metricsHeading })).getAllByRole('article'),
    ).toHaveLength(5)
    expect(screen.getAllByText('1,250.00 USD')).toHaveLength(1)
    const values = ['1,250.00 USD', '1,210.00 USD', '40.00 USD', '1,331.00 USD', '6.48%', '75.53%']
    const basisLine = copy.basisLine('일', formatSeoulTime(BASIS_AT, copy.timeUnavailable))
    cards().forEach((each, index) => {
      const text = each.textContent
      const name = NAMES[index] ?? ''
      const value = values[index] ?? ''
      expect(text).toContain(value)
      expect(text.indexOf(name)).toBeLessThan(text.indexOf(value))
      expect(text.indexOf(value)).toBeLessThan(text.indexOf(basisLine))
      expect(text.indexOf(basisLine)).toBeLessThan(
        text.indexOf(`${copy.natureLabel}: ${copy.natures.OFFICIAL ?? ''}`),
      )
    })
    // The current value's card carries its measurement time.
    expect(card(0)).toHaveTextContent(copy.measuredLine(MEASURED))
    expect(screen.getByText(copy.requiredRateBoundary('일'))).toBeInTheDocument()
  })

  it('AC-5: three independent groups, each with state, cause, last normal time, affected metrics and next action', () => {
    renderPanel(ready())

    expect(axes()).toHaveLength(3)
    const freshness = axis(0)
    const reliability = axis(1)
    const nature = axis(2)
    expect(freshness).toHaveAccessibleName(named('freshness', 'FRESH'))
    expect(reliability).toHaveAccessibleName(named('reliability', 'CONFIRMED'))
    expect(nature).toHaveAccessibleName(named('nature', 'OFFICIAL'))
    for (const [group, lastNormal] of [
      [freshness, copy.axes.freshness.lastNormal],
      [reliability, copy.axes.reliability.lastNormal],
      [nature, copy.axes.nature.lastNormal],
    ] as const) {
      expect(group).toHaveTextContent(copy.axes.cause)
      expect(group).toHaveTextContent(lastNormal)
      expect(group).toHaveTextContent(`${copy.axes.affected}${copy.axes.noneAffected}`)
      // A short action without a time: the approved sentence is the next-action section's, once.
      expect(group).toHaveTextContent(`${copy.axes.action}${axisActionCopy('NONE')}`)
      expect(group).not.toHaveTextContent(nextActionCopy('NONE', MEASURED))
    }
    expect(freshness).toHaveTextContent(copy.axes.freshness.causes.BOTH_WITHIN_WINDOW ?? '')
    expect(reliability).toHaveTextContent(copy.axes.reliability.causes.INPUTS_VERIFIED ?? '')
    expect(nature).toHaveTextContent(copy.axes.nature.causes.NO_ESTIMATED_INPUT ?? '')
    // Each axis vouches for its own time of the bundle, in Asia/Seoul.
    expect(freshness).toHaveTextContent(timeBeside(copy.axes.freshness.lastNormal, MEASURED_AT))
    expect(freshness).toHaveTextContent(
      timeBeside(copy.axes.freshness.verifiedThrough, VERIFIED_THROUGH),
    )
    expect(reliability).toHaveTextContent(
      timeBeside(copy.axes.reliability.lastNormal, VERIFIED_THROUGH),
    )
    expect(nature).toHaveTextContent(timeBeside(copy.axes.nature.lastNormal, BASIS_AT))
    for (const each of within(freshness).getAllByRole('time')) {
      expect([MEASURED_AT, VERIFIED_THROUGH]).toContain(each.getAttribute('datetime'))
    }
    // The approved NONE sentence appears exactly once on the panel, in the next-action section.
    expect(screen.getAllByText(nextActionCopy('NONE', MEASURED))).toHaveLength(1)
    expect(nextAction()).toHaveTextContent(nextActionCopy('NONE', MEASURED))
  })

  it.each([
    ['freshness', 'STALE', 'MEASUREMENT_OUTDATED', copy.axes.freshness.causes],
    ['freshness', 'STALE', 'CASHFLOW_VERIFICATION_OUTDATED', copy.axes.freshness.causes],
    ['freshness', 'STALE', 'BOTH_OUTDATED', copy.axes.freshness.causes],
    ['freshness', 'SYNCING', 'JOB_IN_PROGRESS', copy.axes.freshness.causes],
    // Story 2.11: the endings of a sync that published nothing, and the states that stop it.
    ['freshness', 'STALE', 'LAST_SYNC_UNSUCCESSFUL', copy.axes.freshness.causes],
    ['freshness', 'STALE', 'LAST_SYNC_NEEDS_REVIEW', copy.axes.freshness.causes],
    ['freshness', 'STALE', 'LONG_GAP', copy.axes.freshness.causes],
    ['freshness', 'STALE', 'SYNC_UNAVAILABLE', copy.axes.freshness.causes],
    ['reliability', 'REVIEW_REQUIRED', 'TARGET_NOT_POSITIVE', copy.axes.reliability.causes],
    ['reliability', 'REVIEW_REQUIRED', 'SYNC_REVIEW_REQUIRED', copy.axes.reliability.causes],
  ] as const)('maps the %s cause %s/%s to its copy', (which, state, cause, causes) => {
    const trust =
      which === 'freshness'
        ? trustFixture({ freshness: { state, cause, affectedMetrics: [...METRIC_ORDER] } })
        : trustFixture({
            inputReliability: { state, cause, affectedMetrics: ['ADJUSTED_TARGET'] },
          })
    renderPanel(ready(planResultFixture({ trust })))

    const group = axis(which === 'freshness' ? 0 : 1)
    expect(group).toHaveAccessibleName(named(which, state))
    expect(group).toHaveTextContent(causes[cause] ?? 'missing copy')
    expect(group).not.toHaveTextContent(copy.axes.unknownCause)
  })

  it.each([
    ['different', VERIFIED_THROUGH],
    ['equal', MEASURED_AT],
  ])(
    'AC-4: the two times keep their own labels and values when they are %s, with the note and next action beside them',
    (_name, verified) => {
      const result = planResultFixture({
        cashflowVerifiedThrough: verified,
        trust: trustFixture({
          freshness: { cashflowVerifiedThrough: verified },
          inputReliability: { lastNormalAt: verified },
        }),
      })
      renderPanel(ready(result))

      const freshness = axis(0)
      const measuredLabel = within(freshness).getByText(copy.axes.freshness.lastNormal)
      const verifiedLabel = within(freshness).getByText(copy.axes.freshness.verifiedThrough)
      expect(measuredLabel).not.toBe(verifiedLabel)
      expect(freshness).toHaveTextContent(timeBeside(copy.axes.freshness.lastNormal, MEASURED_AT))
      expect(freshness).toHaveTextContent(timeBeside(copy.axes.freshness.verifiedThrough, verified))
      expect(
        within(freshness)
          .getAllByRole('time')
          .map((each) => each.getAttribute('datetime')),
      ).toEqual([MEASURED_AT, verified])
      // The note sits between the two times and the next action, in the same group.
      const text = freshness.textContent
      expect(text.indexOf(copy.axes.freshness.verifiedThrough)).toBeLessThan(
        text.indexOf(copy.axes.freshness.timesNote),
      )
      expect(text.indexOf(copy.axes.freshness.timesNote)).toBeLessThan(
        text.indexOf(copy.axes.action),
      )
    },
  )

  it('AC-6: an undefined metric shows 계산 불가 in the value’s place, linked once to its reason, beside its character', () => {
    renderPanel(ready(estimatedUndefinedFixture()))

    const rate = card(4)
    const value = within(rate).getByText(copy.notComputable)
    const reason = `${copy.reasonLabel}: ${copy.reasons.CURRENT_VALUE_NOT_POSITIVE ?? ''}`
    expect(value).toHaveAccessibleDescription(reason)
    // Once, on the value: the card itself does not describe itself with the same reason again.
    expect(rate).not.toHaveAttribute('aria-describedby')
    expect(rate).toHaveAccessibleDescription('')
    // The undefined ratio of an estimated plan is still estimated, and never a zero.
    expect(rate).toHaveTextContent(`${copy.natureLabel}: ${copy.natures.ESTIMATED ?? ''}`)
    expect(rate).not.toHaveTextContent(/0\.00%|0%/)
    expect(card(0)).toHaveTextContent('0.00 USD')
    expect(card(0)).not.toHaveTextContent('-0.00')
    expect(card(0)).toHaveTextContent(`${copy.natureLabel}: ${copy.natures.OFFICIAL ?? ''}`)
    expect(card(0)).not.toHaveTextContent(copy.estimatedEvidence(1))
    expect(card(1)).toHaveTextContent('1,358.02 USD')
    expect(card(2)).toHaveTextContent('-1,358.02 USD')
    expect(card(3)).toHaveTextContent('1,500.50 USD')
    expect(card(5)).toHaveTextContent('-456.79%')
    for (const index of [1, 2, 3, 4, 5]) {
      expect(card(index)).toHaveTextContent(copy.estimatedEvidence(1))
    }
    expect(axis(0)).toHaveAccessibleName(named('freshness', 'STALE'))
    expect(axis(0)).toHaveTextContent(copy.axes.freshness.causes.MEASUREMENT_OUTDATED ?? '')
    expect(axis(0)).toHaveTextContent(`${copy.axes.affected}${NAMES.join(', ')}`)
    expect(axis(2)).toHaveAccessibleName(named('nature', 'NOT_COMPUTABLE'))
    expect(axis(2)).toHaveTextContent(copy.axes.nature.causes.METRIC_UNDEFINED ?? 'missing copy')
    expect(axis(2)).toHaveTextContent(copy.axes.nature.estimatedToo)
    expect(axis(2)).toHaveTextContent(
      copy.axes.nature.unavailableMetrics(copy.metrics.REQUIRED_RATE.name),
    )
    expect(axis(2)).toHaveTextContent(`${copy.axes.affected}${NAMES.slice(1).join(', ')}`)
  })

  it('names the estimated character and its cause when an estimated flow reached every derived metric', () => {
    const estimated = planResultFixture({
      flowCount: 1,
      estimatedFlowCount: 1,
      trust: trustFixture({
        calculationNature: {
          state: 'ESTIMATED',
          character: 'ESTIMATED',
          cause: 'ESTIMATED_CASHFLOW_PRICE',
          affectedMetrics: METRIC_ORDER.slice(1),
          estimatedFlowCount: 1,
        },
      }),
      metrics: [
        { metric: 'CURRENT_VALUE', value: '1250', nature: 'OFFICIAL' },
        { metric: 'PLAN_AMOUNT', value: '1210', nature: 'ESTIMATED' },
        { metric: 'PLAN_DIFFERENCE', value: '40', nature: 'ESTIMATED' },
        { metric: 'ADJUSTED_TARGET', value: '1331', nature: 'ESTIMATED' },
        { metric: 'REQUIRED_RATE', value: '0.0648', nature: 'ESTIMATED' },
        { metric: 'GOAL_PROGRESS', value: '0.75', nature: 'ESTIMATED' },
      ],
    })
    renderPanel(ready(estimated))

    expect(axis(2)).toHaveAccessibleName(named('nature', 'ESTIMATED'))
    expect(axis(2)).toHaveTextContent(
      copy.axes.nature.causes.ESTIMATED_CASHFLOW_PRICE ?? 'missing copy',
    )
    expect(axis(2)).toHaveTextContent(`${copy.axes.affected}${NAMES.slice(1).join(', ')}`)
  })

  it('AC-6: negative rates and progress above 100% are shown as they are, with no colour, icon or class judging them', () => {
    const result = planResultFixture({
      metrics: [
        { metric: 'CURRENT_VALUE', value: '5000', nature: 'OFFICIAL' },
        { metric: 'PLAN_AMOUNT', value: '1210', nature: 'OFFICIAL' },
        { metric: 'PLAN_DIFFERENCE', value: '3790', nature: 'OFFICIAL' },
        { metric: 'ADJUSTED_TARGET', value: '1331', nature: 'OFFICIAL' },
        { metric: 'REQUIRED_RATE', value: '-0.2', nature: 'OFFICIAL' },
        { metric: 'GOAL_PROGRESS', value: '12.5', nature: 'OFFICIAL' },
      ],
    })
    renderPanel(ready(result))

    const shown = [
      '5,000.00 USD',
      '1,210.00 USD',
      '3,790.00 USD',
      '1,331.00 USD',
      '-20.00%',
      '1,250.00%',
    ]
    // One value style for every sign and size: nothing marks a value as good or bad.
    const valueClasses = new Set(
      shown.map((text, index) => within(card(index)).getByText(text).className),
    )
    expect(valueClasses.size).toBe(1)
    const panel = screen.getByRole('region', { name: copy.heading })
    expect(within(panel).queryAllByRole('img')).toEqual([])
    // eslint-disable-next-line testing-library/no-node-access -- 역할이 없는 장식 SVG는 역할 조회로 보이지 않는다. 아이콘이 없다는 것을 태그로 확인해야 한다.
    expect(panel.getElementsByTagName('svg')).toHaveLength(0)
    expect(panel.textContent).not.toMatch(/▲|▼|↑|↓/)
  })

  it('AC-7: rounds HALF_UP to two places once, and shows -0.00 as 0.00', () => {
    const result = planResultFixture({
      metrics: [
        { metric: 'CURRENT_VALUE', value: '1234.565', nature: 'OFFICIAL' },
        { metric: 'PLAN_AMOUNT', value: '-0.004', nature: 'OFFICIAL' },
        { metric: 'PLAN_DIFFERENCE', value: '1234.569', nature: 'OFFICIAL' },
        { metric: 'ADJUSTED_TARGET', value: '1000000.005', nature: 'OFFICIAL' },
        { metric: 'REQUIRED_RATE', value: '0.00005', nature: 'OFFICIAL' },
        { metric: 'GOAL_PROGRESS', value: '-0.00004', nature: 'OFFICIAL' },
      ],
    })
    renderPanel(ready(result))

    const expected = [
      '1,234.57 USD',
      '0.00 USD',
      '1,234.57 USD',
      '1,000,000.01 USD',
      '0.01%',
      '0.00%',
    ]
    expected.forEach((text, index) => {
      expect(within(card(index)).getByText(text)).toBeInTheDocument()
    })
    expect(document.body.textContent).not.toContain('-0.00')
  })

  it('folds unknown codes to safe copy and never shows them verbatim', () => {
    const unknown = planResultFixture({
      conditions: {
        principal: '1234.56',
        startAt: '2026-09-25T00:00:00Z',
        endAt: '2026-10-25T04:00:00Z',
        period: 'FORTNIGHTLY',
        targetRate: '0.1',
      },
      trust: trustFixture({
        freshness: {
          state: 'LAGGING_SOMEHOW',
          cause: 'CLOCK_ON_FIRE',
          nextAction: 'PRESS_THE_BUTTON',
          affectedMetrics: ['MYSTERY_METRIC'],
        },
        inputReliability: { state: 'QUESTIONABLE', cause: 'HUNCH' },
        calculationNature: { state: 'MYSTERY', cause: 'SOMETHING' },
      }),
    })
    renderPanel(ready(unknown, 'TELEPORT'))

    const text = document.body.textContent
    for (const code of [
      'LAGGING_SOMEHOW',
      'CLOCK_ON_FIRE',
      'PRESS_THE_BUTTON',
      'MYSTERY_METRIC',
      'QUESTIONABLE',
      'HUNCH',
      'MYSTERY',
      'SOMETHING',
      'FORTNIGHTLY',
      'TELEPORT',
    ]) {
      expect(text).not.toContain(code)
    }
    expect(axis(0)).toHaveAccessibleName(`${copy.axes.freshness.name}: ${copy.axes.unknownState}`)
    expect(axis(0)).toHaveTextContent(copy.axes.unknownCause)
    expect(axis(0)).toHaveTextContent(copy.axes.unknownMetric)
    expect(axis(0)).toHaveTextContent(NEXT_ACTION_FALLBACK)
    expect(axis(1)).toHaveAccessibleName(`${copy.axes.reliability.name}: ${copy.axes.unknownState}`)
    expect(axis(2)).toHaveAccessibleName(`${copy.axes.nature.name}: ${copy.axes.unknownState}`)
    expect(nextAction()).toHaveTextContent(NEXT_ACTION_FALLBACK)
  })

  it('shows the input as 사용 불가 when the server says UNUSABLE (its producers come later)', () => {
    const unusable = planResultFixture({
      trust: trustFixture({
        inputReliability: {
          state: 'UNUSABLE',
          cause: 'SOURCE_WITHDRAWN',
          affectedMetrics: [...METRIC_ORDER],
        },
      }),
    })
    renderPanel(ready(unusable))

    expect(axis(1)).toHaveAccessibleName(named('reliability', 'UNUSABLE'))
    expect(axis(1)).toHaveTextContent(copy.axes.unknownCause)
    expect(axis(1)).toHaveTextContent(`${copy.axes.affected}${NAMES.join(', ')}`)
  })

  it('tells a syncing reader to wait and reload rather than promising that the values will change', () => {
    const syncing = planResultFixture({
      trust: trustFixture({
        freshness: {
          state: 'SYNCING',
          cause: 'JOB_IN_PROGRESS',
          nextAction: 'WAIT_FOR_SYNC',
          affectedMetrics: [...METRIC_ORDER],
        },
      }),
    })
    renderPanel(ready(syncing, 'WAIT_FOR_SYNC'))

    expect(axis(0)).toHaveAccessibleName(named('freshness', 'SYNCING'))
    expect(axis(0)).toHaveTextContent(copy.axes.freshness.causes.JOB_IN_PROGRESS ?? 'missing copy')
    expect(axis(0)).toHaveTextContent(axisActionCopy('WAIT_FOR_SYNC'))
    expect(nextAction()).toHaveTextContent(nextActionCopy('WAIT_FOR_SYNC', MEASURED))
  })

  it.each([
    ['SYNC', 'STALE', 'MEASUREMENT_OUTDATED'],
    ['CHOOSE_CONTINUATION', 'STALE', 'LONG_GAP'],
  ] as const)(
    'Story 2.11: the next action %s has its own sentence on the axis and in the next-action section',
    (code, state, cause) => {
      const stale = planResultFixture({
        trust: trustFixture({
          freshness: { state, cause, nextAction: code, affectedMetrics: [...METRIC_ORDER] },
        }),
      })
      renderPanel(ready(stale, code))

      expect(axis(0)).toHaveTextContent(axisActionCopy(code))
      expect(nextAction()).toHaveTextContent(nextActionCopy(code, MEASURED))
      for (const sentence of [axisActionCopy(code), nextActionCopy(code, MEASURED)]) {
        expect(sentence).not.toBe(NEXT_ACTION_FALLBACK)
      }
      expect(nextActionCopy(code, null)).not.toContain(copy.timeUnavailable)
    },
  )

  it('Story 2.11: the NONE sentence no longer says nothing can be checked here — a sync control exists', () => {
    expect(nextActionCopy('NONE', MEASURED)).not.toContain('새로 확인할 수 없습니다')
    expect(axisActionCopy('NONE')).not.toContain('할 수 있는 조치가 없습니다')
  })

  it('the next action when nothing can be done here names the measurement time and links nowhere', () => {
    renderPanel(ready())

    expect(nextAction()).toHaveTextContent(nextActionCopy('NONE', MEASURED))
    // No link to a detail screen that does not exist yet.
    expect(
      within(screen.getByRole('region', { name: copy.heading })).queryAllByRole('link'),
    ).toEqual([])
  })

  it('never splices the "time unavailable" sentence into another sentence', () => {
    const noTime = planResultFixture({
      measuredAt: 'not-a-time',
      trust: trustFixture({
        freshness: { lastNormalAt: 'not-a-time', measuredAt: 'not-a-time' },
      }),
    })
    renderPanel({
      kind: 'lastGood',
      nextAction: 'NONE',
      result: parsed(noTime),
      sync: AVAILABLE_SYNC,
    })

    expect(nextAction()).toHaveTextContent(nextActionCopy('NONE', null))
    expect(screen.getByText(copy.lastGoodHelp(null))).toBeInTheDocument()
    expect(document.body.textContent).not.toContain(`${copy.timeUnavailable}의`)
    expect(document.body.textContent).not.toContain(`${copy.timeUnavailable}에`)
  })

  it('renders plan terms sent in a trailing-zero notation instead of dropping the whole result', () => {
    const notation = planResultFixture({
      conditions: {
        principal: '1250.00',
        startAt: '2026-09-25T00:00:00Z',
        endAt: '2026-10-25T04:00:00Z',
        period: 'DAILY',
        targetRate: '0.10',
      },
    })
    renderPanel(ready(notation))

    const conditions = within(screen.getByRole('region', { name: copy.conditionsHeading }))
    expect(conditions.getByText('1,250.00 USD')).toBeInTheDocument()
    expect(conditions.getByText('10%')).toBeInTheDocument()
    expect(conditions.getByText(copy.periods.DAILY ?? 'missing copy')).toBeInTheDocument()
    // Only notation is tolerated: an exponent or a sign in the terms still rejects the result.
    expect(
      isPlanResult(
        planResultFixture({
          conditions: { ...(notation.conditions as object), principal: '1.25E3' },
        }),
      ),
    ).toBe(false)
  })

  it('AC-10/AC-11: no live region of its own, no recommendation, celebration, fear, streak or badge wording', () => {
    renderPanel(ready(estimatedUndefinedFixture()))

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 live region은 역할 조회로 보이지 않는다. 속성으로만 찾을 수 있다.
    expect(document.querySelector('[aria-live]')).toBeNull()
    expect(document.body.textContent).not.toMatch(
      /축하|성공|실패|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/,
    )
  })

  describe('AC-8: the states are told apart, each with its next action and no partial value', () => {
    it('no plan: a neutral pointer to the plan conditions, the link and reload, and no financial field', () => {
      expect(parseDashboard(NO_PLAN_FIXTURE)).toEqual({
        state: 'NO_PLAN',
        nextAction: 'CREATE_PLAN',
      })
      renderPanel({ kind: 'NO_PLAN', nextAction: 'CREATE_PLAN' })

      const next = nextAction()
      expect(next).toHaveTextContent(nextActionCopy('CREATE_PLAN', null))
      // It points to where that is decided; it does not say a plan can be made.
      expect(next).not.toHaveTextContent(/발행하면|만들 수 있습니다/)
      expect(
        within(next).getByRole('link', { name: planConditionContent.openConditions }),
      ).toHaveAttribute('href', planConditionContent.path)
      expect(within(next).getByRole('button', { name: copy.reload })).toBeInTheDocument()
      expect(screen.queryByRole('article')).not.toBeInTheDocument()
      expect(document.body.textContent).not.toMatch(/USD|%/)
    })

    it('being prepared: wait for the result and reload', () => {
      expect(parseDashboard(PREPARING_FIXTURE)).toEqual({
        state: 'PREPARING',
        nextAction: 'WAIT_FOR_RESULT',
      })
      renderPanel({ kind: 'PREPARING', nextAction: 'WAIT_FOR_RESULT' })

      expect(nextAction()).toHaveTextContent(nextActionCopy('WAIT_FOR_RESULT', null))
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
      expect(screen.queryByRole('article')).not.toBeInTheDocument()
    })

    it('last good result (a failed read): the same values under 마지막 정상 결과, with reload', () => {
      renderPanel({
        kind: 'lastGood',
        nextAction: 'NONE',
        result: parsed(planResultFixture()),
        sync: AVAILABLE_SYNC,
      })

      expect(screen.getByText(copy.lastGoodLabel)).toBeInTheDocument()
      expect(screen.getByText(copy.lastGoodHelp(MEASURED))).toBeInTheDocument()
      expect(screen.queryByText(copy.retryableLabel)).not.toBeInTheDocument()
      expect(cards()).toHaveLength(6)
      expect(card(0)).toHaveTextContent('1,250.00 USD')
      expect(screen.getByRole('button', { name: copy.reload })).toBeInTheDocument()
    })

    it('retryable after a version conflict: the unswapped values under 재시도 가능, with reload', () => {
      const kept = { result: parsed(planResultFixture()), nextAction: 'NONE', sync: AVAILABLE_SYNC }
      renderPanel({ kind: 'retryable', kept })

      expect(screen.getByText(copy.retryableLabel)).toBeInTheDocument()
      expect(screen.getByText(copy.versionConflictHelp(MEASURED))).toBeInTheDocument()
      expect(screen.queryByText(copy.lastGoodLabel)).not.toBeInTheDocument()
      expect(cards()).toHaveLength(6)
      expect(card(0)).toHaveTextContent('1,250.00 USD')
      expect(screen.getByRole('button', { name: copy.reload })).toBeInTheDocument()
    })

    it('retryable with nothing kept: no financial value at all, and reload', () => {
      renderPanel({ kind: 'retryable', kept: null })

      expect(screen.getByText(copy.retryableLabel)).toBeInTheDocument()
      expect(screen.getByText(copy.retryableHelp)).toBeInTheDocument()
      expect(screen.queryByRole('article')).not.toBeInTheDocument()
      expect(document.body.textContent).not.toMatch(/\d\s*USD|%|0\.00/)
      expect(screen.getByRole('button', { name: copy.reload })).toBeInTheDocument()
    })

    it('refused (403): a neutral sentence, nothing financial and nothing to press', () => {
      renderPanel({ kind: 'denied', cause: 'refused' })

      expect(screen.getByText(copy.deniedHelp)).toBeInTheDocument()
      expect(copy.deniedHelp).not.toMatch(/지웠습니다/)
      expect(screen.queryByRole('article')).not.toBeInTheDocument()
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
    })

    it('signed out (401): nothing financial, and a document link back to the entry screen', () => {
      renderPanel({ kind: 'denied', cause: 'signedOut' })

      expect(screen.getByRole('link', { name: copy.signInLink })).toHaveAttribute(
        'href',
        ENTRY_PATH,
      )
      expect(ENTRY_PATH).toBe('/')
      expect(screen.queryByRole('article')).not.toBeInTheDocument()
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
    })

    it('checking: only the heading, marked busy — never judged empty before the answer', () => {
      renderPanel({ kind: 'checking' }, true)

      const panel = screen.getByRole('region', { name: copy.heading })
      expect(panel).toHaveAttribute('aria-busy', 'true')
      expect(panel.textContent).toBe(copy.heading)
    })
  })

  it('the reload button reports busy, does not read twice, and stays the same element', () => {
    const reload = vi.fn()
    const { rerender } = render(
      <PlanResultPanel
        adjustments={closedAdjustments()}
        evidence={closedEvidence()}
        dashboard={dashboard(ready(), false, reload)}
        sync={idleSyncState()}
      />,
    )
    const button = screen.getByRole('button', { name: copy.reload })
    fireEvent.click(button)
    expect(reload).toHaveBeenCalledTimes(1)

    rerender(
      <PlanResultPanel
        adjustments={closedAdjustments()}
        evidence={closedEvidence()}
        dashboard={dashboard(ready(), true, reload)}
        sync={idleSyncState()}
      />,
    )
    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('region', { name: copy.heading })).toHaveAttribute('aria-busy', 'true')
    fireEvent.click(button)
    expect(reload).toHaveBeenCalledTimes(1)

    // A keyboard user's focus survives the view changing under it.
    button.focus()
    rerender(
      <PlanResultPanel
        adjustments={closedAdjustments()}
        evidence={closedEvidence()}
        dashboard={dashboard(
          {
            kind: 'lastGood',
            nextAction: 'NONE',
            result: parsed(planResultFixture()),
            sync: AVAILABLE_SYNC,
          },
          false,
          reload,
        )}
        sync={idleSyncState()}
      />,
    )
    expect(screen.getByRole('button', { name: copy.reload })).toBe(button)
    expect(button).toHaveFocus()
  })

  it.each([
    ['refused', { kind: 'denied', cause: 'refused' }],
    ['signed out', { kind: 'denied', cause: 'signedOut' }],
    ['checking', { kind: 'checking' }],
  ] as const)(
    'moves focus to the heading when the reload button goes away (%s) instead of dropping it to the body',
    (_name, next) => {
      const { rerender } = render(
        <PlanResultPanel
          adjustments={closedAdjustments()}
          evidence={closedEvidence()}
          dashboard={dashboard(ready())}
          sync={idleSyncState()}
        />,
      )
      screen.getByRole('button', { name: copy.reload }).focus()

      rerender(
        <PlanResultPanel
          adjustments={closedAdjustments()}
          evidence={closedEvidence()}
          dashboard={dashboard(next)}
          sync={idleSyncState()}
        />,
      )

      expect(screen.queryByRole('button', { name: copy.reload })).not.toBeInTheDocument()
      expect(screen.getByRole('heading', { level: 2, name: copy.heading })).toHaveFocus()
    },
  )

  it('leaves focus alone when it was not inside the panel', () => {
    const { rerender } = render(
      <>
        <button type="button">outside</button>
        <PlanResultPanel
          adjustments={closedAdjustments()}
          evidence={closedEvidence()}
          dashboard={dashboard(ready())}
          sync={idleSyncState()}
        />
      </>,
    )
    const outside = screen.getByRole('button', { name: 'outside' })
    screen.getByRole('button', { name: copy.reload }).focus()
    outside.focus()

    rerender(
      <>
        <button type="button">outside</button>
        <PlanResultPanel
          adjustments={closedAdjustments()}
          evidence={closedEvidence()}
          dashboard={dashboard({ kind: 'denied', cause: 'refused' })}
          sync={idleSyncState()}
        />
      </>,
    )

    expect(outside).toHaveFocus()
  })

  describe('AC-9: a bundle is accepted whole and only when it agrees with itself', () => {
    it('rejects a missing axis field, a metric out of order, a malformed value or a zero for an undefined metric', () => {
      const missingMetric = planResultFixture()
      missingMetric.metrics = (missingMetric.metrics as unknown[]).slice(0, 5)
      expect(isPlanResult(missingMetric)).toBe(false)
      const zeroForUndefined = planResultFixture({
        metrics: [
          { metric: 'CURRENT_VALUE', value: '1', nature: 'OFFICIAL' },
          { metric: 'PLAN_AMOUNT', value: '1', nature: 'OFFICIAL' },
          { metric: 'PLAN_DIFFERENCE', value: '1', nature: 'OFFICIAL' },
          { metric: 'ADJUSTED_TARGET', value: '1', nature: 'OFFICIAL' },
          { metric: 'REQUIRED_RATE', value: '0', nature: 'OFFICIAL', unavailableReason: 'X' },
          { metric: 'GOAL_PROGRESS', value: '1', nature: 'OFFICIAL' },
        ],
      })
      expect(isPlanResult(zeroForUndefined)).toBe(false)
      const exponent = planResultFixture()
      ;(exponent.metrics as Record<string, unknown>[])[1] = {
        metric: 'PLAN_AMOUNT',
        value: '1E3',
        nature: 'OFFICIAL',
      }
      expect(isPlanResult(exponent)).toBe(false)
      expect(isPlanResult({ ...planResultFixture(), trust: {} })).toBe(false)
      for (const field of ['state', 'cause', 'lastNormalAt', 'affectedMetrics', 'nextAction']) {
        const reliability = { ...(trustFixture().inputReliability as Record<string, unknown>) }
        Reflect.deleteProperty(reliability, field)
        expect(
          isPlanResult(
            planResultFixture({ trust: { ...trustFixture(), inputReliability: reliability } }),
          ),
        ).toBe(false)
      }
    })

    it('rejects a bundle whose axes vouch for another time, or whose undefined metrics do not match', () => {
      for (const trust of [
        trustFixture({ freshness: { lastNormalAt: VERIFIED_THROUGH } }),
        trustFixture({ freshness: { measuredAt: '2026-09-27T00:00:00Z' } }),
        trustFixture({ freshness: { cashflowVerifiedThrough: MEASURED_AT } }),
        trustFixture({ inputReliability: { lastNormalAt: MEASURED_AT } }),
        trustFixture({ calculationNature: { lastNormalAt: MEASURED_AT } }),
        trustFixture({ calculationNature: { unavailableMetrics: ['REQUIRED_RATE'] } }),
        trustFixture({ calculationNature: { estimatedFlowCount: 3 } }),
      ]) {
        expect(isPlanResult(planResultFixture({ trust }))).toBe(false)
      }
    })

    it('rejects a nature axis that disagrees with the metrics, or a current value that is not defined and official', () => {
      const official = (overrides: Record<string, unknown>): Record<string, unknown> =>
        planResultFixture({ trust: trustFixture({ calculationNature: overrides }) })
      // No undefined metric, but "not computable".
      expect(isPlanResult(official({ state: 'NOT_COMPUTABLE' }))).toBe(false)
      // An undefined metric, but not "not computable".
      const undefinedRate = estimatedUndefinedFixture()
      const undefinedTrust = undefinedRate.trust as Record<string, Record<string, unknown>>
      expect(isPlanResult(undefinedRate)).toBe(true)
      expect(
        isPlanResult({
          ...undefinedRate,
          trust: {
            ...undefinedTrust,
            calculationNature: { ...undefinedTrust.calculationNature, state: 'ESTIMATED' },
          },
        }),
      ).toBe(false)
      // The character is not the one nature the derived metrics share.
      expect(isPlanResult(official({ character: 'ESTIMATED' }))).toBe(false)
      expect(isPlanResult(official({ character: 'SOMETHING' }))).toBe(false)
      const mixed = planResultFixture()
      ;(mixed.metrics as Record<string, unknown>[])[2] = {
        metric: 'PLAN_DIFFERENCE',
        value: '40',
        nature: 'ESTIMATED',
      }
      expect(isPlanResult(mixed)).toBe(false)
      // The current value is always defined and official.
      const estimatedCurrent = planResultFixture()
      ;(estimatedCurrent.metrics as Record<string, unknown>[])[0] = {
        metric: 'CURRENT_VALUE',
        value: '1250',
        nature: 'ESTIMATED',
      }
      expect(isPlanResult(estimatedCurrent)).toBe(false)
      const undefinedCurrent = planResultFixture({
        trust: trustFixture({
          calculationNature: { state: 'NOT_COMPUTABLE', unavailableMetrics: ['CURRENT_VALUE'] },
        }),
      })
      ;(undefinedCurrent.metrics as Record<string, unknown>[])[0] = {
        metric: 'CURRENT_VALUE',
        nature: 'OFFICIAL',
        unavailableReason: 'CURRENT_VALUE_NOT_POSITIVE',
      }
      expect(isPlanResult(undefinedCurrent)).toBe(false)
    })

    it('rejects an answer that is not one of the three states, a next action the state cannot carry, or a financial field on a state without one', () => {
      expect(parseDashboard(dashboardFixture())).not.toBeNull()
      expect(parseDashboard(dashboardFixture(planResultFixture(), 'WAIT_FOR_SYNC'))).not.toBeNull()
      expect(parseDashboard({ state: 'READY', nextAction: 'NONE' })).toBeNull()
      expect(parseDashboard({ ...NO_PLAN_FIXTURE, result: planResultFixture() })).toBeNull()
      expect(parseDashboard({ state: 'PREPARING' })).toBeNull()
      expect(parseDashboard({ state: 'SOMETHING', nextAction: 'NONE' })).toBeNull()
      expect(parseDashboard({ planId: 'partial' })).toBeNull()
      expect(parseDashboard(null)).toBeNull()
      for (const [state, nextActionCode] of [
        ['NO_PLAN', 'WAIT_FOR_RESULT'],
        ['NO_PLAN', 'NONE'],
        ['PREPARING', 'CREATE_PLAN'],
        ['PREPARING', 'WAIT_FOR_SYNC'],
      ]) {
        expect(parseDashboard({ state, nextAction: nextActionCode })).toBeNull()
      }
      for (const nextActionCode of ['CREATE_PLAN', 'WAIT_FOR_RESULT', 'TELEPORT']) {
        expect(parseDashboard(dashboardFixture(planResultFixture(), nextActionCode))).toBeNull()
      }
    })

    it('within one plan, a pointer version that goes back or names another calculation does not supersede', () => {
      const shown = parsed(planResultFixture({ pointerVersion: 2, calculationId: 'calculation-2' }))
      const older = parsed(planResultFixture({ pointerVersion: 1, calculationId: 'calculation-1' }))
      const conflicting = parsed(
        planResultFixture({ pointerVersion: 2, calculationId: 'calculation-x' }),
      )
      const same = parsed(planResultFixture({ pointerVersion: 2, calculationId: 'calculation-2' }))
      const newer = parsed(planResultFixture({ pointerVersion: 3, calculationId: 'calculation-3' }))
      const otherPlan = parsed(planResultFixture({ planId: 'plan-2', pointerVersion: 1 }))
      expect(supersedes(older, shown)).toBe(false)
      expect(supersedes(conflicting, shown)).toBe(false)
      expect(supersedes(same, shown)).toBe(true)
      expect(supersedes(newer, shown)).toBe(true)
      expect(supersedes(otherPlan, shown)).toBe(true)
      expect(supersedes(older, null)).toBe(true)
    })
  })
})
