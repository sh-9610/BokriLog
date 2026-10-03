import type { ReactElement } from 'react'

import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { AdjustmentHistoryState } from '../session/useAdjustmentHistory'
import type { EvidenceState } from '../session/useCalculationEvidence'
import type { PlanEnd } from '../session/usePlanEnd'

import { adjustmentHistoryContent } from '../content/adjustmentHistory'
import { calculationEvidenceContent } from '../content/calculationEvidence'
import { formatDelay, planEndContent } from '../content/planEnd'
import { nextActionCopy, planResultContent } from '../content/planResult'
import { planSyncContent } from '../content/planSync'
import { planVerificationContent } from '../content/planVerification'
import { formatSeoulTime } from '../format/decimal'
import { parseDashboard, type Dashboard, type DashboardView } from '../session/useDashboard'
import {
  dashboardFixture,
  ENDED_AT,
  endedDashboardFixture,
  endedResultFixture,
  endRecordFixture,
  idlePlanEnd,
  idleReauthentication,
  idleSyncState,
  longGapEndRecordFixture,
  MEASURED_AT,
  VERIFIED_THROUGH,
} from '../test/planResultFixture'
import { getObservedStorageEntries } from '../test/setup'
import { PlanResultPanel } from './PlanResultPanel'

const copy = planEndContent.record
const MEASURED = formatSeoulTime(MEASURED_AT, planResultContent.timeUnavailable)

/** The view the dashboard shows for an answer, through the parser. */
function viewOf(body: Record<string, unknown>): DashboardView {
  const parsed = parseDashboard(body)
  if (parsed === null) throw new Error('a malformed fixture')
  if (parsed.state === 'ENDED') {
    return {
      kind: 'ENDED',
      result: parsed.result,
      nextAction: parsed.nextAction,
      sync: { availability: 'UNAVAILABLE', reason: null, retryAfterSeconds: null, latest: null },
      verification: {
        availability: 'UNAVAILABLE',
        reason: null,
        retryAfterSeconds: null,
        suggestedReason: null,
        preview: null,
        catchUpFrom: null,
        latest: null,
      },
      change: {
        kind: 'NONE',
        purpose: null,
        reason: null,
        lateAddedCount: 0,
        valueChangedCount: 0,
        removedCount: 0,
        firstAffectedAt: null,
        verifiedAt: null,
        targets: [],
        reused: [],
      },
      planEnd: { availability: 'UNAVAILABLE', reason: null },
      end: parsed.end,
    }
  }
  if (parsed.state !== 'READY') throw new Error('not a bundle')
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

function panel(view: DashboardView, planEnd: PlanEnd = idlePlanEnd()): ReactElement {
  const dashboard: Dashboard = { view, loading: false, reload: vi.fn(), refresh: vi.fn() }
  return (
    <PlanResultPanel
      adjustments={closedAdjustments()}
      dashboard={dashboard}
      evidence={closedEvidence()}
      planEnd={planEnd}
      reauthentication={idleReauthentication()}
      sync={idleSyncState({ canRequest: false, canVerify: false })}
    />
  )
}

function record(): HTMLElement {
  return screen.getByRole('region', { name: copy.heading })
}

/** The panel's own sections, by their level-3 headings, in document order. */
function sectionOrder(): (string | null)[] {
  return within(screen.getByRole('region', { name: planResultContent.heading }))
    .getAllByRole('heading', { level: 3 })
    .map((heading) => heading.textContent)
}

describe('PlanEndRecord and the panel’s record mode (Story 2.13)', () => {
  it('shows the record: the 중도 종료 marker, the reason, the actual and scheduled end, the last normal value and its time, and the delay', () => {
    render(panel(viewOf(endedDashboardFixture())))
    const facts = record()
    expect(facts).toHaveAttribute('id', copy.id)
    expect(within(facts).getByText(`${copy.statusLabel}: ${copy.marker}`)).toBeVisible()
    expect(copy.marker).toBe('중도 종료')
    const term = (label: string): HTMLElement => {
      // eslint-disable-next-line testing-library/no-node-access -- 설명 목록의 값은 용어의 다음 형제다.
      const value = within(facts).getByText(label).nextElementSibling
      if (!(value instanceof HTMLElement)) throw new Error(`no value for ${label}`)
      return value
    }
    expect(term(copy.reasonLabel)).toHaveTextContent(copy.reasons.USER_REQUEST ?? 'missing')
    // The actual end time is the server's, to the millisecond, as a machine value.
    // eslint-disable-next-line testing-library/no-node-access -- 기계 값은 `time` 요소의 속성에만 있다.
    expect(term(copy.endedAt).querySelector('time')).toHaveAttribute('datetime', ENDED_AT)
    expect(term(copy.endedAt)).toHaveTextContent(copy.endedAtHelp)
    const scheduled = (endedResultFixture().conditions as Record<string, string>).endAt ?? ''
    // eslint-disable-next-line testing-library/no-node-access -- 기계 값은 `time` 요소의 속성에만 있다.
    expect(term(copy.scheduledEndAt).querySelector('time')).toHaveAttribute('datetime', scheduled)
    expect(term(copy.lastNormalValue)).toHaveTextContent('1,250.00 USD')
    // The last normal measurement time is the one the panel showed before the end — never the end time.
    expect(term(copy.lastNormalMeasuredAt)).toHaveTextContent(MEASURED)
    // eslint-disable-next-line testing-library/no-node-access -- 기계 값은 `time` 요소의 속성에만 있다.
    expect(term(copy.lastNormalMeasuredAt).querySelector('time')).toHaveAttribute(
      'datetime',
      MEASURED_AT,
    )
    expect(term(copy.delay)).toHaveTextContent(
      formatDelay(Date.parse(ENDED_AT) - Date.parse(MEASURED_AT)),
    )
    expect(term(copy.completion)).toHaveTextContent(copy.completions.NOT_APPLICABLE ?? 'missing')
    expect(within(facts).queryByText(copy.uncollected)).not.toBeInTheDocument()
    expect(facts).toHaveTextContent(copy.readOnly)
    expect(facts).toHaveTextContent(planEndContent.dialog.distinct)
  })

  it('after a long gap: the comparison cannot be calculated and the uncollected range is shown', () => {
    const result = endedResultFixture()
    render(panel(viewOf(endedDashboardFixture(result, longGapEndRecordFixture(result)))))
    const facts = record()
    expect(facts).toHaveTextContent(copy.reasons.LONG_GAP_NEW_PLAN ?? 'missing')
    expect(facts).toHaveTextContent(
      `${copy.completion}${copy.completions.UNAVAILABLE_DATA_GAP ?? 'missing'}`,
    )
    expect(within(facts).getByText(copy.uncollected)).toBeVisible()
    expect(facts).toHaveTextContent(copy.uncollectedHelp)
    // eslint-disable-next-line testing-library/no-node-access -- 기계 값은 `time` 요소의 속성에만 있다.
    const times = [...facts.querySelectorAll('time')].map((time) => time.dateTime)
    expect(times).toContain(VERIFIED_THROUGH)
    expect(times.filter((time) => time === ENDED_AT)).toHaveLength(2)
  })

  it('folds a reason and a completion it does not know to safe words', () => {
    const result = endedResultFixture()
    render(
      panel(
        viewOf(
          endedDashboardFixture(
            result,
            endRecordFixture(result, { reason: 'SOMETHING_NEW', completion: 'NEW_COMPARISON' }),
          ),
        ),
      ),
    )
    expect(record()).toHaveTextContent(copy.unknownReason)
    expect(record()).toHaveTextContent(copy.unknownCompletion)
    expect(record()).not.toHaveTextContent('SOMETHING_NEW')
    expect(record()).not.toHaveTextContent('NEW_COMPARISON')
  })

  it('formats the delay in days, hours and minutes, never from this device’s clock', () => {
    expect(formatDelay(0)).toBe('1분 미만')
    expect(formatDelay(59_999)).toBe('1분 미만')
    expect(formatDelay(60_000)).toBe('1분')
    expect(formatDelay(3 * 86_400_000 + 2 * 3_600_000 + 5 * 60_000)).toBe('3일 2시간 5분')
    expect(formatDelay(86_400_000)).toBe('1일')
  })

  it('record mode keeps the six metrics, two axes, the toggles and the terms, and offers no sync, verification or end', () => {
    render(panel(viewOf(endedDashboardFixture())))
    const region = screen.getByRole('region', { name: planResultContent.heading })
    expect(region).toHaveTextContent(copy.intro)
    expect(region).not.toHaveTextContent(planResultContent.intro)
    expect(sectionOrder()).toEqual([
      copy.heading,
      planResultContent.currentHeading,
      planResultContent.trustHeading,
      planResultContent.metricsHeading,
      planEndContent.nextPlan.heading,
      planResultContent.conditionsHeading,
    ])
    expect(within(region).getAllByRole('article')).toHaveLength(6)
    // The freshness axis gives its place to the record; the other two axes stay.
    const trust = screen.getByRole('region', { name: planResultContent.trustHeading })
    expect(
      within(trust)
        .getAllByRole('group')
        .map((group) => group.getAttribute('aria-labelledby')),
    ).toEqual(['plan-result-axis-reliability', 'plan-result-axis-nature'])
    // The only controls: the evidence and history toggles and the reload. No new-plan button either.
    expect(
      within(region)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual([
      calculationEvidenceContent.open,
      adjustmentHistoryContent.open,
      planResultContent.reload,
    ])
    for (const name of [
      planSyncContent.action,
      planVerificationContent.action,
      planEndContent.area.action,
      planSyncContent.longGap.newPlanAction,
    ]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByRole('region', { name: planEndContent.area.heading })).toBeNull()
    expect(screen.getByRole('region', { name: planEndContent.nextPlan.heading })).toHaveTextContent(
      nextActionCopy('PREPARE_NEXT_PLAN', MEASURED),
    )
    // A present fact, never a promise of a later feature.
    expect(nextActionCopy('PREPARE_NEXT_PLAN', MEASURED)).not.toContain('아직')
  })

  it('the record says no success, failure or completion, has no live region of its own and stores nothing', () => {
    const result = endedResultFixture()
    render(panel(viewOf(endedDashboardFixture(result, longGapEndRecordFixture(result)))))
    const text = screen.getByRole('region', { name: planResultContent.heading }).textContent
    expect(text).not.toMatch(
      /실시간|정확|보장|성공|실패|축하|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/,
    )
    // "완료" only names the comparison.
    expect(text.replaceAll(copy.completion, '')).not.toContain('완료')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 live region은 속성으로만 찾을 수 있다.
    expect(document.querySelector('[aria-live]')).toBeNull()
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('READY: the 계획 종료 area closes the panel, after the plan terms', () => {
    render(panel(viewOf(dashboardFixture())))
    expect(sectionOrder().slice(-2)).toEqual([
      planResultContent.conditionsHeading,
      planEndContent.area.heading,
    ])
    expect(screen.queryByRole('region', { name: copy.heading })).toBeNull()
    expect(screen.getByRole('button', { name: planEndContent.area.action })).toBeVisible()
  })

  it('the end moves focus to the panel heading; a notice the area cannot take while the record is shown goes there too', () => {
    const ready = viewOf(dashboardFixture())
    const { rerender } = render(panel(ready, idlePlanEnd()))
    const heading = screen.getByRole('heading', { level: 2, name: planResultContent.heading })
    screen.getByRole('button', { name: planEndContent.area.action }).focus()
    rerender(panel(ready, idlePlanEnd({ focus: { target: 'heading', serial: 1 } })))
    expect(heading).toHaveFocus()

    // The record replaces the content under the focused heading: focus stays.
    const ended = viewOf(endedDashboardFixture())
    rerender(panel(ended, idlePlanEnd({ focus: { target: 'heading', serial: 1 } })))
    expect(heading).toHaveFocus()
    heading.blur()
    rerender(panel(ended, idlePlanEnd({ focus: { target: 'notice', serial: 2 } })))
    expect(heading).toHaveFocus()
  })

  it('focus on an end control that goes with the active plan is not dropped when the record comes', () => {
    const { rerender } = render(panel(viewOf(dashboardFixture())))
    screen.getByRole('button', { name: planEndContent.area.action }).focus()
    rerender(panel(viewOf(endedDashboardFixture())))
    expect(screen.getByRole('heading', { level: 2, name: planResultContent.heading })).toHaveFocus()
  })

  it('F5: a notice request the end area took is never replayed on the heading when the record comes later', () => {
    const active = viewOf(dashboardFixture())
    const refused = idlePlanEnd({
      notice: { kind: 'refused', refusal: 'stale', reason: null },
      focus: { target: 'notice', serial: 1 },
    })
    const { rerender } = render(panel(active))
    rerender(panel(active, refused))
    const words = planEndContent.refusals.stale
    // eslint-disable-next-line testing-library/no-node-access -- 표지는 역할이 없는 초점 대상(tabIndex -1)이다.
    expect(screen.getByText(words.label).parentElement).toHaveFocus()
    const reload = screen.getByRole('button', { name: planResultContent.reload })
    reload.focus()
    rerender(panel(viewOf(endedDashboardFixture()), refused))
    expect(reload).toHaveFocus()
    expect(
      screen.getByRole('heading', { level: 2, name: planResultContent.heading }),
    ).not.toHaveFocus()
  })
})
