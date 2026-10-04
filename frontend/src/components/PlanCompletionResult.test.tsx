import type { ReactElement } from 'react'

import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { AdjustmentHistoryState } from '../session/useAdjustmentHistory'
import type { EvidenceState } from '../session/useCalculationEvidence'
import type { DashboardSync } from '../session/useDashboardSync'

import { adjustmentHistoryContent } from '../content/adjustmentHistory'
import { calculationEvidenceContent } from '../content/calculationEvidence'
import {
  completionNextActionCopy,
  planCompletionContent,
  planCompletionCopyStrings,
} from '../content/planCompletion'
import { formatDelay, planEndContent } from '../content/planEnd'
import { planResultContent } from '../content/planResult'
import { planSyncContent } from '../content/planSync'
import { planVerificationContent } from '../content/planVerification'
import { parseEvidence } from '../session/useCalculationEvidence'
import {
  parseDashboard,
  UNKNOWN_PLAN_END,
  UNKNOWN_VERIFICATION,
  type Accepted,
  type Dashboard,
  type DashboardView,
  type PlanCompletionRecord,
  type PlanResult,
} from '../session/useDashboard'
import {
  calculationEvidenceFixture,
  changeFixture,
  comparedMetricsFixture,
  completedDashboardFixture,
  completedProgressResultFixture,
  COMPLETION_TARGET,
  completionRecordFixture,
  completionVersionResultFixture,
  dashboardFixture,
  EVIDENCE_IDS,
  evidenceChangeFixture,
  idlePlanEnd,
  idleReauthentication,
  idleSyncState,
  laterEvidenceFixture,
  OBSERVED_AT,
  observedFixture,
  PREVIOUS_BASIS_AT,
  SCHEDULED_END_AT,
  syncFixture,
  syncJobFixture,
} from '../test/planResultFixture'
import { getObservedStorageEntries } from '../test/setup'
import { PlanResultPanel } from './PlanResultPanel'

const copy = planCompletionContent.result
const post = planCompletionContent.postEnd

const FORBIDDEN =
  /실시간|정확|보장|성공|실패|축하|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/
const LOCATION = /위의|아래의|위쪽|아래쪽|오른쪽|왼쪽/

/** The view the dashboard shows for a `COMPLETED` answer, through the parser. */
function viewOf(body: Record<string, unknown>): DashboardView {
  return { kind: 'COMPLETED', ...bundleOfAnswer(body) }
}

/** The bundle the dashboard keeps for a `COMPLETED` answer, through the parser. */
function bundleOfAnswer(
  body: Record<string, unknown>,
): Accepted & { readonly completion: PlanCompletionRecord } {
  const parsed = parseDashboard(body)
  if (parsed?.state !== 'COMPLETED') throw new Error('a malformed fixture')
  return {
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
}

/** The view the dashboard shows for a `READY` answer, through the parser. */
function readyViewOf(body: Record<string, unknown>): DashboardView {
  const parsed = parseDashboard(body)
  if (parsed?.state !== 'READY') throw new Error('a malformed fixture')
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

function closedEvidence(): EvidenceState {
  return { view: { kind: 'closed' }, open: vi.fn(), close: vi.fn(), retry: vi.fn() }
}

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

function panel(
  view: DashboardView,
  sync: DashboardSync = idleSyncState(),
  evidence: EvidenceState = closedEvidence(),
): ReactElement {
  const dashboard: Dashboard = { view, loading: false, reload: vi.fn(), refresh: vi.fn() }
  return (
    <PlanResultPanel
      adjustments={closedAdjustments()}
      dashboard={dashboard}
      evidence={evidence}
      planEnd={idlePlanEnd()}
      reauthentication={idleReauthentication()}
      sync={sync}
    />
  )
}

function completionRegion(): HTMLElement {
  return screen.getByRole('region', { name: copy.heading })
}

function postEndRegion(): HTMLElement {
  return screen.getByRole('region', { name: post.heading })
}

/** The value of a term in a fact list: the term's next sibling. */
function term(region: HTMLElement, label: string): HTMLElement {
  // eslint-disable-next-line testing-library/no-node-access -- 설명 목록의 값은 용어의 다음 형제다.
  const value = within(region).getByText(label).nextElementSibling
  if (!(value instanceof HTMLElement)) throw new Error(`no value for ${label}`)
  return value
}

/** The panel's own sections, by their level-3 headings, in document order. */
function sectionOrder(): (string | null)[] {
  return within(screen.getByRole('region', { name: planResultContent.heading }))
    .getAllByRole('heading', { level: 3 })
    .map((heading) => heading.textContent)
}

/** A confirmed completion with a later value. */
function confirmed(postEnd: Record<string, unknown> | null = null): Record<string, unknown> {
  const result = completionVersionResultFixture()
  return completedDashboardFixture(result, completionRecordFixture(result), { postEnd })
}

describe('PlanCompletionResult and the panel’s completion mode (Story 2.14)', () => {
  it('reads in the completion order: marker, completion result, six metrics, three axes, toggles, later value, terms, new plan', () => {
    render(panel(viewOf(confirmed())))
    const region = screen.getByRole('region', { name: planResultContent.heading })
    expect(
      within(region).getByText(
        `${planCompletionContent.statusLabel}: ${planCompletionContent.marker}`,
      ),
    ).toBeVisible()
    expect(region).toHaveTextContent(planCompletionContent.intro)
    expect(region).not.toHaveTextContent(planResultContent.intro)
    expect(sectionOrder()).toEqual([
      copy.heading,
      planResultContent.metricsHeading,
      planResultContent.trustHeading,
      planCompletionContent.evidenceHeading,
      post.heading,
      planResultContent.conditionsHeading,
      planCompletionContent.nextPlan.heading,
    ])
    expect(within(region).getAllByRole('article')).toHaveLength(6)
    // Three axes, the freshness folded to 계획 완료 without a control of its own.
    const trust = screen.getByRole('region', { name: planResultContent.trustHeading })
    expect(
      within(trust)
        .getAllByRole('group')
        .map((group) => group.getAttribute('aria-labelledby')),
    ).toEqual([
      'plan-result-axis-freshness',
      'plan-result-axis-reliability',
      'plan-result-axis-nature',
    ])
    expect(
      within(trust).getByRole('heading', {
        level: 4,
        name: `${planResultContent.axes.freshness.name}: ${planResultContent.axes.freshness.states.COMPLETED ?? ''}`,
      }),
    ).toBeVisible()
    expect(within(trust).queryByRole('button')).toBeNull()
    // The only controls: the two toggles, the sync of the later value, and the reload. No verification or end.
    expect(
      within(region)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual([
      calculationEvidenceContent.open,
      adjustmentHistoryContent.open,
      planSyncContent.action,
      planResultContent.reload,
    ])
    for (const name of [
      planVerificationContent.action,
      planEndContent.area.action,
      planSyncContent.longGap.newPlanAction,
      planSyncContent.longGap.continueAction,
    ]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByRole('region', { name: planEndContent.area.heading })).toBeNull()
    expect(
      within(postEndRegion()).getByRole('button', { name: planSyncContent.action }),
    ).toBeVisible()
    expect(
      screen.getByRole('region', { name: planCompletionContent.nextPlan.heading }),
    ).toHaveTextContent(completionNextActionCopy('PREPARE_NEXT_PLAN'))
  })

  it('the completion result and the later value are two regions, each named by its own heading', () => {
    render(panel(viewOf(confirmed())))
    const result = completionRegion()
    const later = postEndRegion()
    expect(result).toHaveAttribute('aria-labelledby', copy.headingId)
    expect(later).toHaveAttribute('aria-labelledby', post.headingId)
    expect(result).not.toContainElement(later)
    expect(later).not.toContainElement(result)
    expect(within(result).getByRole('heading', { level: 3 })).toHaveTextContent(copy.heading)
    expect(within(later).getByRole('heading', { level: 3 })).toHaveTextContent(post.heading)
  })

  it('shows a confirmed completion: T, the first observation’s time, delay and value — never the valuation at T — and the target', () => {
    render(panel(viewOf(confirmed())))
    const region = completionRegion()
    expect(within(region).getByText(`${copy.comparisonLabel}: 확정`)).toBeVisible()
    // eslint-disable-next-line testing-library/no-node-access -- 기계 값은 `time` 요소의 속성에만 있다.
    expect(term(region, copy.scheduledEndAt).querySelector('time')).toHaveAttribute(
      'datetime',
      SCHEDULED_END_AT,
    )
    // eslint-disable-next-line testing-library/no-node-access -- 기계 값은 `time` 요소의 속성에만 있다.
    expect(term(region, copy.observedMeasuredAt).querySelector('time')).toHaveAttribute(
      'datetime',
      OBSERVED_AT,
    )
    expect(term(region, copy.delay)).toHaveTextContent(formatDelay(3_600_000))
    expect(term(region, copy.delay)).toHaveTextContent('1시간')
    expect(term(region, copy.observedValue)).toHaveTextContent('1,300.00 USD')
    expect(term(region, copy.observedValue)).toHaveTextContent(copy.observedValueHelp)
    expect(copy.observedValueHelp).toContain('예정 종료 시각의 평가액이 아니라')
    expect(term(region, copy.completionTarget)).toHaveTextContent(
      `${COMPLETION_TARGET.replace('1331', '1,331.00')} USD`,
    )
    expect(term(region, copy.postEndCashflows)).toHaveTextContent('0건')
    expect(within(region).queryByText(copy.reasonLabel)).toBeNull()
    expect(region).toHaveTextContent(copy.next.CONFIRMED ?? 'missing')
    expect(region).toHaveTextContent(copy.fixed)
    expect(region).toHaveTextContent(copy.distinct)
  })

  it('checking before any observation: nothing observed, no target yet, and the sync for the observation', () => {
    render(panel(viewOf(completedDashboardFixture())))
    const region = completionRegion()
    expect(within(region).getByText(`${copy.comparisonLabel}: 확인 중`)).toBeVisible()
    expect(term(region, copy.observedMeasuredAt)).toHaveTextContent(copy.notObserved)
    expect(term(region, copy.delay)).toHaveTextContent(copy.notObserved)
    expect(term(region, copy.observedValue)).toHaveTextContent(copy.notObserved)
    expect(term(region, copy.completionTarget)).toHaveTextContent(copy.noCompletionTarget)
    expect(within(region).queryByText(copy.postEndCashflows)).toBeNull()
    expect(region).toHaveTextContent(copy.next.PENDING ?? 'missing')
    expect(postEndRegion()).toHaveTextContent(post.notObserved)
    expect(
      screen.getByRole('region', { name: planCompletionContent.nextPlan.heading }),
    ).toHaveTextContent(completionNextActionCopy('SYNC_FOR_OBSERVATION'))
    // The last progress version's metrics are shown as they are.
    expect(screen.getAllByRole('article')).toHaveLength(6)
  })

  it.each([
    ['a delay over 24 hours', 'OBSERVATION_DELAY_EXCEEDED', 0],
    ['flows between T and the observation', 'POST_END_CASHFLOW', 2],
  ])(
    'not comparable for %s: 계산 불가 with its reason, the target kept, never 0 or 0%%',
    (_name, reason, flows) => {
      const observedAt =
        reason === 'OBSERVATION_DELAY_EXCEEDED' ? '2026-10-26T05:00:00Z' : undefined
      const result = completionVersionResultFixture({
        comparable: false,
        ...(observedAt === undefined ? {} : { observedAt }),
      })
      render(
        panel(
          viewOf(
            completedDashboardFixture(
              result,
              completionRecordFixture(result, {
                comparison: 'UNAVAILABLE',
                reason,
                postEndCashflowCount: flows,
              }),
            ),
          ),
        ),
      )
      const region = completionRegion()
      expect(within(region).getByText(`${copy.comparisonLabel}: 계산 불가`)).toBeVisible()
      expect(term(region, copy.reasonLabel)).toHaveTextContent(copy.reasons[reason] ?? 'missing')
      expect(term(region, copy.completionTarget)).toHaveTextContent('1,331.00 USD')
      // The two metrics tied to the observation say 계산 불가 with their reason — never a zero.
      for (const name of ['PLAN_DIFFERENCE', 'GOAL_PROGRESS'] as const) {
        const card = screen.getByRole('article', { name: planResultContent.metrics[name].name })
        expect(card).toHaveTextContent(planResultContent.notComputable)
        expect(card).toHaveTextContent(
          planResultContent.reasons.COMPLETION_NOT_COMPARABLE ?? 'missing',
        )
        expect(card.textContent).not.toMatch(/(^|[^\d,.])0(\.00)?( USD|%)/)
      }
      const required = screen.getByRole('article', {
        name: planResultContent.metrics.REQUIRED_RATE.name,
      })
      expect(required).toHaveTextContent(
        planResultContent.reasons.REMAINING_PERIOD_NOT_POSITIVE ?? 'missing',
      )
    },
  )

  it('a data gap without a completion calculation: the target is 계산 불가, never a zero', () => {
    const result = completedProgressResultFixture()
    render(
      panel(
        viewOf(
          completedDashboardFixture(
            result,
            completionRecordFixture(result, {
              comparison: 'UNAVAILABLE',
              reason: 'DATA_GAP',
              retryable: false,
              ...observedFixture(),
            }),
          ),
        ),
      ),
    )
    const region = completionRegion()
    expect(term(region, copy.completionTarget)).toHaveTextContent(planResultContent.notComputable)
    expect(term(region, copy.reasonLabel)).toHaveTextContent(copy.reasons.DATA_GAP ?? 'missing')
  })

  it('held: 확정 보류 with its reason, and a sync again reuses the first observation', () => {
    const result = completedProgressResultFixture()
    render(
      panel(
        viewOf(
          completedDashboardFixture(
            result,
            completionRecordFixture(result, {
              comparison: 'HELD',
              reason: 'INPUT_REVIEW_REQUIRED',
              ...observedFixture(),
            }),
          ),
        ),
      ),
    )
    const region = completionRegion()
    expect(within(region).getByText(`${copy.comparisonLabel}: 확정 보류`)).toBeVisible()
    expect(term(region, copy.reasonLabel)).toHaveTextContent(
      copy.reasons.INPUT_REVIEW_REQUIRED ?? 'missing',
    )
    expect(region).toHaveTextContent(copy.next.HELD ?? 'missing')
    expect(term(region, copy.observedValue)).toHaveTextContent('1,300.00 USD')
    expect(postEndRegion()).toHaveTextContent(post.sameAsObservation)
  })

  it('folds a comparison and a reason it does not know to safe words', () => {
    const result = completedProgressResultFixture()
    render(
      panel(
        viewOf(
          completedDashboardFixture(
            result,
            completionRecordFixture(result, { comparison: 'NEW_ONE', reason: 'NEW_REASON' }),
          ),
        ),
      ),
    )
    const region = completionRegion()
    expect(region).toHaveTextContent(copy.unknownComparison)
    expect(region).toHaveTextContent(copy.unknownReason)
    expect(region).toHaveTextContent(copy.unknownNext)
    expect(region).not.toHaveTextContent('NEW_ONE')
    expect(region).not.toHaveTextContent('NEW_REASON')
  })

  it('a later value changes only the 종료 후 현재가치 region: the completion result stays as it was', () => {
    const { rerender } = render(panel(viewOf(confirmed())))
    const before = completionRegion().textContent
    expect(postEndRegion()).toHaveTextContent(post.sameAsObservation)
    const metrics = screen.getAllByRole('article').map((card) => card.textContent)

    rerender(panel(viewOf(confirmed({ value: '1302.1', measuredAt: '2026-10-26T01:00:00Z' }))))
    expect(completionRegion().textContent).toBe(before)
    expect(screen.getAllByRole('article').map((card) => card.textContent)).toEqual(metrics)
    const later = postEndRegion()
    expect(term(later, post.value)).toHaveTextContent('1,302.10 USD')
    // eslint-disable-next-line testing-library/no-node-access -- 기계 값은 `time` 요소의 속성에만 있다.
    expect(term(later, post.measuredAt).querySelector('time')).toHaveAttribute(
      'datetime',
      '2026-10-26T01:00:00Z',
    )
    expect(later).not.toHaveTextContent(post.sameAsObservation)
  })

  it('a running sync shows its progress in the 종료 후 현재가치 region, not in the completion result', () => {
    render(
      panel(
        viewOf(confirmed()),
        idleSyncState({ phase: 'running', running: true, canRequest: false }),
      ),
    )
    expect(
      within(postEndRegion()).getByText(planSyncContent.running, { exact: true }),
    ).toBeVisible()
    expect(
      within(postEndRegion()).getByRole('button', { name: planSyncContent.action }),
    ).toHaveAttribute('aria-disabled', 'true')
    expect(completionRegion()).not.toHaveTextContent(planSyncContent.running)
  })

  it('a pressed sync that ends with a new bundle moves focus to the panel heading, as on an active plan', () => {
    const view = viewOf(confirmed())
    const { rerender } = render(panel(view))
    within(postEndRegion()).getByRole('button', { name: planSyncContent.action }).focus()
    rerender(panel(view, idleSyncState({ focus: { target: 'heading', serial: 1 } })))
    expect(screen.getByRole('heading', { level: 2, name: planResultContent.heading })).toHaveFocus()
  })

  it('says no success, failure or celebration, points by name only, has no live region and stores nothing', () => {
    const result = completionVersionResultFixture({ comparable: false })
    render(
      panel(
        viewOf(
          completedDashboardFixture(
            result,
            completionRecordFixture(result, {
              comparison: 'UNAVAILABLE',
              reason: 'POST_END_CASHFLOW',
              postEndCashflowCount: 1,
            }),
            { postEnd: { value: '1302.1', measuredAt: '2026-10-26T01:00:00Z' } },
          ),
        ),
      ),
    )
    const text = screen.getByRole('region', { name: planResultContent.heading }).textContent
    expect(text).not.toMatch(FORBIDDEN)
    expect(text).not.toMatch(LOCATION)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 live region은 속성으로만 찾을 수 있다.
    expect(document.querySelector('[aria-live]')).toBeNull()
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('every completion string avoids forbidden and location words, and the new plan is a present fact', () => {
    const added = [
      ...planCompletionCopyStrings,
      planResultContent.axes.freshness.states.COMPLETED ?? 'missing',
      planResultContent.axes.freshness.causes.PLAN_COMPLETED ?? 'missing',
      planResultContent.reasons.COMPLETION_NOT_COMPARABLE ?? 'missing',
      calculationEvidenceContent.versions.completedProgressCurrent,
      planSyncContent.summary.codes.PROVIDER_END_NOT_REACHED,
      planVerificationContent.purposes.COMPLETION ?? 'missing',
      planVerificationContent.purposes.POST_END_VALUE ?? 'missing',
      completionNextActionCopy('NEW_CODE'),
      calculationEvidenceContent.versions.completedCurrent,
      planSyncContent.summary.stages.OBSERVATION,
    ]
    for (const text of added) {
      expect(text).not.toMatch(FORBIDDEN)
      expect(text).not.toMatch(LOCATION)
    }
    for (const text of [
      planCompletionContent.nextPlan.text,
      planCompletionContent.nextPlan.observe,
    ]) {
      expect(text).not.toContain('아직')
    }
    // The observed value is said not to be the valuation at T.
    expect(planCompletionCopyStrings.join(' ')).toContain('예정 종료 시각의 평가액이 아니라')
  })

  it('focus on a control the completion mode removes goes to the panel heading when the same plan completes', () => {
    const { rerender } = render(panel(readyViewOf(dashboardFixture())))
    screen.getByRole('button', { name: planVerificationContent.action }).focus()
    rerender(panel(viewOf(completedDashboardFixture())))
    expect(screen.getByRole('heading', { level: 2, name: planResultContent.heading })).toHaveFocus()
  })

  it('the completion mode’s freshness axis has no note on flows not yet applied', () => {
    render(panel(viewOf(confirmed())))
    const trust = screen.getByRole('region', { name: planResultContent.trustHeading })
    expect(trust).not.toHaveTextContent(planResultContent.axes.freshness.timesNote)
    expect(within(trust).queryByText(planResultContent.axes.freshness.timesNoteLabel)).toBeNull()
  })

  it('F1: the freshness cause says the shown version is final only on the completion version', () => {
    const { unmount } = render(panel(viewOf(confirmed())))
    const causes = planResultContent.axes.freshness.causes
    expect(screen.getByRole('region', { name: planResultContent.trustHeading })).toHaveTextContent(
      causes.PLAN_COMPLETED ?? 'missing',
    )
    unmount()
    render(panel(viewOf(completedDashboardFixture())))
    const trust = screen.getByRole('region', { name: planResultContent.trustHeading })
    expect(trust).toHaveTextContent(planCompletionContent.freshnessProgressCause)
    expect(trust).not.toHaveTextContent(causes.PLAN_COMPLETED ?? 'missing')
  })

  it.each(['lastGood', 'retryable'] as const)(
    'a completed plan kept as %s is still shown in completion mode',
    (kind) => {
      const kept = bundleOfAnswer(confirmed())
      const view: DashboardView =
        kind === 'lastGood' ? { kind: 'lastGood', ...kept } : { kind: 'retryable', kept }
      render(panel(view))
      expect(completionRegion()).toBeVisible()
      expect(postEndRegion()).toBeVisible()
      expect(
        screen.getByText(`${planCompletionContent.statusLabel}: ${planCompletionContent.marker}`),
      ).toBeVisible()
      expect(
        screen.queryByRole('button', { name: planEndContent.area.action }),
      ).not.toBeInTheDocument()
    },
  )

  it('F6: the version change marker is drawn in completion mode', () => {
    const result = completionVersionResultFixture()
    render(
      panel(
        viewOf(
          completedDashboardFixture(result, completionRecordFixture(result), {
            change: changeFixture({ kind: 'VERIFIED_NO_CHANGE', purpose: 'COMPLETION' }),
          }),
        ),
      ),
    )
    const region = screen.getByRole('region', { name: planResultContent.heading })
    expect(region).toHaveTextContent(planVerificationContent.change.verified)
    expect(region).toHaveTextContent(planVerificationContent.purposes.COMPLETION ?? 'missing')
  })

  it('F7: a later value the server sent but this screen could not read is said as not read, never as none', () => {
    const result = completionVersionResultFixture()
    render(
      panel(
        viewOf(
          completedDashboardFixture(result, completionRecordFixture(result), {
            postEnd: { value: '1302.10', measuredAt: '2026-10-26T01:00:00Z' },
          }),
        ),
      ),
    )
    expect(postEndRegion()).toHaveTextContent(post.unreadable)
    expect(postEndRegion()).not.toHaveTextContent(post.sameAsObservation)
  })

  it('C2: a held comparison promises a re-check only while a sync can clear it', () => {
    const result = completedProgressResultFixture()
    const held = (retryable: boolean): DashboardView =>
      viewOf(
        completedDashboardFixture(
          result,
          completionRecordFixture(result, {
            comparison: 'HELD',
            reason: 'INPUT_REVIEW_REQUIRED',
            retryable,
            ...observedFixture(),
          }),
        ),
      )
    const { unmount } = render(panel(held(true)))
    expect(completionRegion()).toHaveTextContent(copy.next.HELD ?? 'missing')
    unmount()
    render(panel(held(false)))
    expect(completionRegion()).toHaveTextContent(copy.heldFinal)
    expect(completionRegion()).not.toHaveTextContent(copy.next.HELD ?? 'missing')
    expect(
      screen.getByRole('region', { name: planCompletionContent.nextPlan.heading }),
    ).toHaveTextContent(completionNextActionCopy('PREPARE_NEXT_PLAN'))
  })

  it('F10: the delay rule is said only once there is an observation', () => {
    render(panel(viewOf(completedDashboardFixture())))
    expect(completionRegion()).not.toHaveTextContent(copy.delayHelp)
  })

  it('C5: a job that ended before Bybit reached T says so in the 종료 후 현재가치 region', () => {
    const job = syncJobFixture({
      purpose: 'COMPLETION',
      status: 'FAILED',
      stage: 'OBSERVATION',
      failureCode: 'PROVIDER_END_NOT_REACHED',
      finishedAt: '2026-10-25T04:00:30Z',
    })
    render(
      panel(
        viewOf(
          completedDashboardFixture(undefined, undefined, { sync: syncFixture({ latest: job }) }),
        ),
      ),
    )
    expect(postEndRegion()).toHaveTextContent(
      planSyncContent.summary.codes.PROVIDER_END_NOT_REACHED,
    )
    expect(postEndRegion()).toHaveTextContent(planSyncContent.summary.stages.OBSERVATION)
  })

  it.each([
    ['the completion version', completionVersionResultFixture(), 'completedCurrent'],
    [
      'a progress version still checking',
      completedProgressResultFixture(),
      'completedProgressCurrent',
    ],
  ] as const)('the evidence of %s names its current version accordingly', (_name, result, help) => {
    const evidence = parseEvidence(
      result.calculationKind === 'COMPLETION'
        ? completionEvidence(result)
        : calculationEvidenceFixture(result),
      result as unknown as PlanResult,
    )
    if (evidence === null) throw new Error('a malformed evidence fixture')
    render(
      panel(
        viewOf(completedDashboardFixture(result, completionRecordFixture(result))),
        idleSyncState(),
        {
          ...closedEvidence(),
          view: { kind: 'ready', evidence },
        },
      ),
    )
    const versions = calculationEvidenceContent.versions
    expect(screen.getByText(versions[help])).toBeVisible()
    const other = help === 'completedCurrent' ? 'completedProgressCurrent' : 'completedCurrent'
    expect(screen.queryByText(versions[other])).toBeNull()
  })
})

/** The evidence of a completion version: two official versions, its collection up to the check after the observation. */
function completionEvidence(result: Record<string, unknown>): Record<string, unknown> {
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
  return {
    ...evidence,
    inputs: {
      ...inputs,
      sourceRange: { ...inputs.sourceRange, rangeEnd: result.cashflowVerifiedThrough },
    },
  }
}
