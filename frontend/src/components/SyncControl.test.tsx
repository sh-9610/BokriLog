import type { ReactElement } from 'react'

import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { PlanResult } from '../session/useDashboard'
import type { DashboardSync } from '../session/useDashboardSync'

import { calculationEvidenceContent } from '../content/calculationEvidence'
import { entryContent } from '../content/entry'
import { planEndContent } from '../content/planEnd'
import { axisActionCopy, nextActionCopy, planResultContent } from '../content/planResult'
import { planSyncContent as copy, planSyncCopyStrings } from '../content/planSync'
import { planVerificationContent } from '../content/planVerification'
import { formatSeoulTime } from '../format/decimal'
import {
  isPlanResult,
  NO_CHANGE,
  parsePlanEndStatus,
  parseSyncStatus,
  parseVerificationStatus,
  UNKNOWN_SYNC,
  UNKNOWN_VERIFICATION,
  type SyncStatus,
  type VerificationStatus,
} from '../session/useDashboard'
import {
  AVAILABLE_PLAN_END,
  AVAILABLE_SYNC,
  AVAILABLE_VERIFICATION,
  idlePlanEnd,
  idleReauthentication,
  idleSyncState,
  planEndFixture,
  planResultFixture,
  syncFixture,
  syncJobFixture,
  verificationFixture,
  verificationJobFixture,
} from '../test/planResultFixture'
import { getObservedStorageEntries } from '../test/setup'
import { PlanResultPanel } from './PlanResultPanel'
import { SyncControl, type NewPlanChoice } from './SyncControl'

function status(overrides: Record<string, unknown> = {}): SyncStatus {
  const parsed = parseSyncStatus(syncFixture(overrides))
  // The fixture is whole: an unknown block would fold to UNKNOWN_SYNC and hide a broken test.
  if (parsed === UNKNOWN_SYNC) throw new Error('a malformed sync block')
  return parsed
}

function ended(fields: Record<string, unknown>): Record<string, unknown> {
  return syncJobFixture({ finishedAt: '2026-09-27T01:10:09Z', stage: 'COLLECTION', ...fields })
}

function button(): HTMLElement {
  return screen.getByRole('button', { name: copy.action })
}

/** The error summary: the focus target of a sync that ended without a new official result. */
function summaryWith(label: string): HTMLElement {
  // eslint-disable-next-line testing-library/no-node-access -- 오류 요약은 역할이 없는 초점 대상(tabIndex -1)이다. 첫 문장 요소의 부모로만 찾을 수 있다.
  const found = screen.getByText(label).parentElement
  if (found === null) throw new Error('no summary')
  return found
}

describe('SyncControl', () => {
  it('offers one primary 동기화 button with the recent-events note, and nothing else to report', () => {
    const request = vi.fn()
    render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ request })}
      />,
    )

    expect(button()).toHaveAttribute('aria-disabled', 'false')
    expect(button()).not.toHaveAttribute('aria-describedby')
    expect(screen.getByText(copy.recentEvents)).toBeInTheDocument()
    expect(screen.queryByText(copy.running)).not.toBeInTheDocument()
    fireEvent.click(button())
    expect(request).toHaveBeenCalledTimes(1)
    // No live region of its own and nothing to follow: the screen's status region speaks.
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 live region은 역할 조회로 보이지 않는다. 속성으로만 찾을 수 있다.
    expect(document.querySelector('[aria-live]')).toBeNull()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('while running: 동기화 중 with a decorative marker, the same button blocked and described by it', () => {
    const request = vi.fn()
    render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'running', running: true, canRequest: false, request })}
      />,
    )

    const state = screen.getByText(copy.running, { exact: true })
    expect(state).toBeVisible()
    expect(screen.getByText(copy.runningHelp)).toBeInTheDocument()
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleName(copy.action)
    expect(button()).toHaveAccessibleDescription(copy.running)
    // The marker says nothing to assistive technology; the text carries the state.
    // eslint-disable-next-line testing-library/no-node-access -- 장식 표지는 역할도 이름도 없다. 속성으로만 찾을 수 있다.
    expect(state.parentElement?.querySelector('[aria-hidden="true"]')).not.toBeNull()
    fireEvent.click(button())
    expect(request).not.toHaveBeenCalled()
  })

  it('counts the cool-down down in seconds and blocks the button meanwhile', () => {
    const request = vi.fn()
    const { rerender } = render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({ availability: 'COOLDOWN', retryAfterSeconds: 25 })}
        sync={idleSyncState({ cooldownSeconds: 25, canRequest: false, request })}
      />,
    )
    expect(screen.getByText(copy.cooldown(25))).toBeInTheDocument()
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleDescription(copy.cooldown(25))
    fireEvent.click(button())
    expect(request).not.toHaveBeenCalled()

    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({ availability: 'COOLDOWN', retryAfterSeconds: 25 })}
        sync={idleSyncState({ request })}
      />,
    )
    expect(screen.queryByText(/초 뒤 다시 동기화할 수 있습니다/)).not.toBeInTheDocument()
    expect(button()).toHaveAttribute('aria-disabled', 'false')
  })

  it.each([
    ['RESTRICTED', copy.reasons.RESTRICTED],
    ['CONNECTION_UNAVAILABLE', copy.reasons.CONNECTION_UNAVAILABLE],
    ['PLAN_PERIOD_ENDED', copy.reasons.PLAN_PERIOD_ENDED],
    // F8: a persistent review of this basis holds the result; syncing again cannot change it.
    ['REVIEW_HELD', copy.reasons.REVIEW_HELD],
    ['SOMETHING_NEW', copy.unknownReason],
  ])('unavailable (%s): the button is blocked and the reason is beside it', (reason, text) => {
    render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({ availability: 'UNAVAILABLE', reason })}
        sync={idleSyncState({ canRequest: false })}
      />,
    )
    const line = `${copy.unavailableLabel}: ${text}`
    expect(screen.getByText(line)).toBeInTheDocument()
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleDescription(line)
    expect(document.body.textContent).not.toContain('SOMETHING_NEW')
  })

  it('a revoked connection (Story 2.16) shows no sync control, only the reason as text', () => {
    const request = vi.fn()
    render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({ availability: 'UNAVAILABLE', reason: 'CONNECTION_REVOKED' })}
        sync={idleSyncState({ canRequest: false, request })}
      />,
    )
    expect(screen.getByText(copy.reasons.CONNECTION_REVOKED)).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText(copy.recentEvents)).not.toBeInTheDocument()
    expect(request).not.toHaveBeenCalled()
  })

  it.each([
    ['FAILED', 'PROVIDER_UNAVAILABLE', copy.summary.statuses.FAILED],
    ['NEEDS_REVIEW', 'CONVERSION_REQUIRES_REVIEW', copy.summary.statuses.NEEDS_REVIEW],
    ['BLOCKED', 'MEMBERSHIP_CHANGED', copy.summary.statuses.BLOCKED],
  ] as const)(
    'the latest job ended %s: the summary names the cause, the kept result, the stage, the request and the time',
    (jobStatus, code, label) => {
      render(
        <SyncControl
          verification={AVAILABLE_VERIFICATION}
          status={status({ latest: ended({ status: jobStatus, failureCode: code }) })}
          sync={idleSyncState()}
        />,
      )
      const summary = summaryWith(label)
      expect(summary).toHaveAttribute('tabindex', '-1')
      expect(summary).toHaveTextContent(`${copy.summary.causeLabel}: ${copy.summary.codes[code]}`)
      expect(summary).toHaveTextContent(copy.summary.kept)
      expect(summary).toHaveTextContent(
        `${copy.summary.stageLabel}: ${copy.summary.stages.COLLECTION}`,
      )
      expect(summary).toHaveTextContent(
        `${copy.summary.triggerLabel}: ${copy.summary.triggers.MANUAL}`,
      )
      expect(summary).toHaveTextContent(
        formatSeoulTime('2026-09-27T01:10:09Z', planResultContent.timeUnavailable),
      )
      // Pressing again is allowed: nothing is retried by itself.
      expect(button()).toHaveAttribute('aria-disabled', 'false')
    },
  )

  it('folds unknown codes, stages and triggers to safe copy and never shows them verbatim', () => {
    render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({
          latest: ended({ status: 'FAILED', failureCode: 'NEW_CODE', stage: 'NEW_STAGE' }),
        })}
        sync={idleSyncState()}
      />,
    )
    const summary = summaryWith(copy.summary.statuses.FAILED)
    expect(summary).toHaveTextContent(copy.summary.unknownCode)
    expect(summary).toHaveTextContent(copy.summary.unknownStage)
    expect(document.body.textContent).not.toMatch(/NEW_CODE|NEW_STAGE/)
  })

  it('shows no summary while a sync runs or after one that published', () => {
    const { rerender } = render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({ latest: ended({ status: 'FAILED', failureCode: 'SYNC_FAILED' }) })}
        sync={idleSyncState({ phase: 'running', running: true, canRequest: false })}
      />,
    )
    expect(screen.queryByText(copy.summary.statuses.FAILED)).not.toBeInTheDocument()
    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({ latest: ended({ status: 'SUCCEEDED', stage: null }) })}
        sync={idleSyncState({ phase: 'succeeded' })}
      />,
    )
    for (const label of Object.values(copy.summary.statuses)) {
      expect(screen.queryByText(label)).not.toBeInTheDocument()
    }
  })

  it.each(['cooldown', 'unavailable', 'error', 'stalled'] as const)(
    'an admission that ended as %s has a summary of its own',
    (phase) => {
      render(
        <SyncControl
          verification={AVAILABLE_VERIFICATION}
          status={AVAILABLE_SYNC}
          sync={idleSyncState({ phase })}
        />,
      )
      const summary = summaryWith(copy.local[phase].label)
      expect(summary).toHaveTextContent(copy.local[phase].text)
      expect(summary).toHaveAttribute('tabindex', '-1')
    },
  )

  it('moves focus to the summary when asked, once, and leaves the heading requests to the panel', () => {
    const { rerender } = render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'error' })}
      />,
    )
    button().focus()
    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'error', focus: { target: 'heading', serial: 1 } })}
      />,
    )
    expect(button()).toHaveFocus()
    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'error', focus: { target: 'summary', serial: 2 } })}
      />,
    )
    expect(summaryWith(copy.local.error.label)).toHaveFocus()
    // The same request again is not a new one.
    button().focus()
    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'error', focus: { target: 'summary', serial: 2 } })}
      />,
    )
    expect(button()).toHaveFocus()
  })

  it('a request made before it was mounted is not replayed', () => {
    render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'error', focus: { target: 'summary', serial: 4 } })}
      />,
    )
    expect(summaryWith(copy.local.error.label)).not.toHaveFocus()
  })

  it.each([
    [
      'the server says so',
      status({ availability: 'LONG_GAP' }),
      idleSyncState({ canRequest: false }),
    ],
    [
      'the admission said so',
      AVAILABLE_SYNC,
      idleSyncState({ phase: 'longGap', canRequest: false }),
    ],
  ])(
    'after a long gap (%s): no sync button, the two choices and their data ranges, and a read-only result',
    (_name, block, sync) => {
      render(<SyncControl verification={AVAILABLE_VERIFICATION} status={block} sync={sync} />)
      expect(screen.queryByRole('button', { name: copy.action })).not.toBeInTheDocument()
      const explanation = summaryWith(copy.longGap.label)
      expect(explanation).toHaveAttribute('tabindex', '-1')
      for (const text of [
        copy.longGap.text,
        copy.longGap.continueTitle,
        copy.longGap.continueText,
        copy.longGap.continueCost,
        copy.longGap.newPlanTitle,
        copy.longGap.newPlanText,
        copy.longGap.readOnly,
      ]) {
        expect(explanation).toHaveTextContent(text)
      }
      // Story 2.12: the catch-up runs from here; the old "cannot choose here" wording is gone.
      expect(explanation).not.toHaveTextContent('선택할 수 없습니다')
      expect(screen.getByRole('button', { name: copy.longGap.continueAction })).toBeVisible()
      // Story 2.13: both ways to continue are buttons, the catch-up first; nothing links to a screen that does not
      // exist. Without the end flow (a test of another part) `새 계획 시작` is blocked, for a reason not known.
      expect(screen.getAllByRole('button').map((each) => each.textContent)).toEqual([
        copy.longGap.continueAction,
        copy.longGap.newPlanAction,
      ])
      expect(screen.getByRole('button', { name: copy.longGap.newPlanAction })).toHaveAttribute(
        'aria-disabled',
        'true',
      )
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
    },
  )

  it('moves focus to the long-gap explanation when a pressed sync ended that way', () => {
    const { rerender } = render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState()}
      />,
    )
    button().focus()
    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({
          phase: 'longGap',
          canRequest: false,
          focus: { target: 'summary', serial: 1 },
        })}
      />,
    )
    expect(summaryWith(copy.longGap.label)).toHaveFocus()
  })

  it('P13: an unavailable sync never shows the same name twice', () => {
    // Before the server's reason arrives, the refused request has its own summary.
    const { rerender } = render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'unavailable' })}
      />,
    )
    expect(screen.getAllByText(new RegExp(copy.unavailableLabel))).toHaveLength(1)
    expect(summaryWith(copy.local.unavailable.label)).toHaveTextContent(copy.local.unavailable.text)
    // Then the server's reason line says it, and the summary gives way to it.
    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })}
        sync={idleSyncState({ phase: 'unavailable', canRequest: false })}
      />,
    )
    expect(screen.getAllByText(new RegExp(copy.unavailableLabel))).toHaveLength(1)
    expect(screen.getByText(`${copy.unavailableLabel}: ${copy.reasons.RESTRICTED}`)).toBeVisible()
    expect(screen.queryByText(copy.local.unavailable.text)).not.toBeInTheDocument()
  })

  it('P14: a sync block it could not read blocks the button with a safe reason', () => {
    render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={UNKNOWN_SYNC}
        sync={idleSyncState({ canRequest: false })}
      />,
    )
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleDescription(`${copy.unavailableLabel}: ${copy.unknownReason}`)
  })

  it('P16: a job found in progress elsewhere is followed with its reason beside the blocked button', () => {
    const { rerender } = render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({
          availability: 'IN_PROGRESS',
          latest: syncJobFixture({ trigger: 'VISIBILITY', status: 'RUNNING' }),
        })}
        sync={idleSyncState({
          phase: 'running',
          running: true,
          followingElsewhere: true,
          canRequest: false,
        })}
      />,
    )
    expect(screen.getByText(copy.inProgressElsewhere)).toBeInTheDocument()
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleDescription(`${copy.running} ${copy.inProgressElsewhere}`)
    // A job still in progress that this screen no longer follows: blocked, and it says why.
    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({
          availability: 'IN_PROGRESS',
          latest: syncJobFixture({ status: 'RUNNING' }),
        })}
        sync={idleSyncState({ phase: 'stalled', canRequest: false })}
      />,
    )
    expect(screen.queryByText(copy.running, { exact: true })).not.toBeInTheDocument()
    expect(button()).toHaveAccessibleDescription(copy.inProgressUnfollowed.SYNC)
    // This tab's own job: 동기화 중, without the elsewhere line.
    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'running', running: true, canRequest: false })}
      />,
    )
    expect(screen.queryByText(copy.inProgressElsewhere)).not.toBeInTheDocument()
    expect(button()).toHaveAccessibleDescription(copy.running)
  })

  it('moves focus to the button when the summary that held it goes away, instead of dropping it', () => {
    const { rerender } = render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'unavailable', focus: { target: 'summary', serial: 1 } })}
      />,
    )
    // The request was made before this mount; focus it by hand as the hook's request would have.
    summaryWith(copy.local.unavailable.label).focus()
    expect(summaryWith(copy.local.unavailable.label)).toHaveFocus()
    // The server's reason arrives: the refused request's summary gives way to the reason line.
    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })}
        sync={idleSyncState({
          phase: 'unavailable',
          canRequest: false,
          focus: { target: 'summary', serial: 1 },
        })}
      />,
    )
    expect(button()).toHaveFocus()
  })

  it('uses no forbidden wording anywhere in its copy', () => {
    for (const text of planSyncCopyStrings) {
      expect(text).not.toMatch(
        /실시간|정확|보장|성공|실패|축하|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/,
      )
    }
  })

  it('P19: the copy Story 2.11 added elsewhere uses no forbidden wording either', () => {
    const { freshness, reliability } = planResultContent.axes
    const added = [
      freshness.causes.LAST_SYNC_UNSUCCESSFUL,
      freshness.causes.LAST_SYNC_NEEDS_REVIEW,
      freshness.causes.LONG_GAP,
      freshness.causes.SYNC_UNAVAILABLE,
      reliability.causes.SYNC_REVIEW_REQUIRED,
      ...['NONE', 'SYNC', 'CHOOSE_CONTINUATION', 'WAIT_FOR_SYNC'].flatMap((code) => [
        nextActionCopy(code, '2026-09-29 10:00'),
        nextActionCopy(code, null),
        axisActionCopy(code),
      ]),
      calculationEvidenceContent.inputs.windowStart,
      calculationEvidenceContent.inputs.windowStartHelp,
      calculationEvidenceContent.inputs.windowStartCarried,
      calculationEvidenceContent.inputs.windowStartFromT0,
      calculationEvidenceContent.versions.officialHelp,
    ]
    for (const text of added) {
      expect(text).toBeDefined()
      expect(text).not.toMatch(
        /실시간|정확|보장|성공|실패|축하|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/,
      )
    }
    // The entry screen's sync sentence names the three triggers; "실시간" appears there only as a denial.
    const entry = entryContent.sections
      .flatMap((section) => section.items)
      .find((item) => item.includes('동기화'))
    expect(entry).toContain('5분 이상')
    expect(entry).not.toMatch(/성공|실패|축하|손실|보장|정확|스트릭|배지/)
  })

  it('P13: the copy does not claim what the screen cannot know or do', () => {
    // The last admission may be another tab's or an automatic trigger's.
    expect(copy.local.cooldown.text).not.toContain('방금')
    expect(copy.status.cooldown).not.toContain('방금')
    // Whether the request reached the server is not known.
    expect(copy.local.error.text).not.toContain('닿았는지')
    expect(copy.local.error.label).not.toContain('요청하지 못했습니다')
    // The choice that runs from here (Story 2.12) is pointed to by its button; neither is pressed on the user.
    for (const text of [
      nextActionCopy('CHOOSE_CONTINUATION', null),
      axisActionCopy('CHOOSE_CONTINUATION'),
    ]) {
      expect(text).toContain(copy.longGap.continueAction)
      expect(text).not.toContain('아직 선택할 수 없습니다')
      expect(text).not.toMatch(/선택해 주세요|선택하세요|정해야 합니다/)
    }
    expect(nextActionCopy('CHOOSE_CONTINUATION', null)).toContain('읽기 전용')
    expect(copy.summary.codes.PROVIDER_TIME_UNTRUSTED).toContain('기준 시각')
    expect(copy.summary.codes.INPUTS_CHANGED).toContain('발행하지 않았습니다')
  })
})

describe('the sync control in the dashboard panel', () => {
  function panel(sync: DashboardSync = idleSyncState()): ReactElement {
    const body = planResultFixture()
    expect(isPlanResult(body)).toBe(true)
    return (
      <PlanResultPanel
        planEnd={idlePlanEnd()}
        reauthentication={idleReauthentication()}
        adjustments={{
          view: { kind: 'closed' },
          open: vi.fn(),
          close: vi.fn(),
          filter: vi.fn(),
          more: vi.fn(),
          retry: vi.fn(),
        }}
        dashboard={{
          view: {
            kind: 'READY',
            nextAction: 'NONE',
            result: body as unknown as PlanResult,
            sync: AVAILABLE_SYNC,
            verification: AVAILABLE_VERIFICATION,
            change: NO_CHANGE,
            planEnd: AVAILABLE_PLAN_END,
            end: null,
          },
          loading: false,
          reload: vi.fn(),
          refresh: vi.fn(),
        }}
        evidence={{ view: { kind: 'closed' }, open: vi.fn(), close: vi.fn(), retry: vi.fn() }}
        sync={sync}
      />
    )
  }

  it('sits right after the freshness axis, before the input reliability axis, as the first control of the panel', () => {
    render(panel())
    const trust = screen.getByRole('region', { name: planResultContent.trustHeading })
    const [freshness, reliability] = within(trust).getAllByRole('group')
    if (freshness === undefined || reliability === undefined) throw new Error('axes')
    const control = within(trust).getByRole('button', { name: copy.action })
    expect(
      freshness.compareDocumentPosition(control) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    expect(
      control.compareDocumentPosition(reliability) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    // Still three axis groups: the control is not one of them.
    expect(within(trust).getAllByRole('group')).toHaveLength(3)
    const region = screen.getByRole('region', { name: planResultContent.heading })
    expect(within(region).getAllByRole('button')[0]).toBe(control)
  })

  it('P20: a panel mounted after a heading request does not replay it', () => {
    const request = idleSyncState({ phase: 'succeeded', focus: { target: 'heading', serial: 3 } })
    const { unmount } = render(panel(request))
    const heading = screen.getByRole('heading', { level: 2, name: planResultContent.heading })
    expect(heading).not.toHaveFocus()
    unmount()
    render(panel(request))
    expect(
      screen.getByRole('heading', { level: 2, name: planResultContent.heading }),
    ).not.toHaveFocus()
  })

  it('a pressed sync that swapped in a new bundle moves focus to the dashboard heading, once', () => {
    const { rerender } = render(panel())
    button().focus()
    rerender(panel(idleSyncState({ phase: 'succeeded', focus: { target: 'heading', serial: 1 } })))
    const heading = screen.getByRole('heading', { level: 2, name: planResultContent.heading })
    expect(heading).toHaveFocus()
    // A summary request is the control's, not the panel's.
    button().focus()
    rerender(panel(idleSyncState({ phase: 'error', focus: { target: 'summary', serial: 2 } })))
    expect(heading).not.toHaveFocus()
    expect(summaryWith(copy.local.error.label)).toHaveFocus()
  })
})

describe('the catch-up after a long gap (Story 2.12)', () => {
  const catchUp = planVerificationContent.catchUp
  const FROM = '2026-08-20T01:00:00Z'

  function gap(overrides: Record<string, unknown> = {}): VerificationStatus {
    return parseVerificationStatus(
      verificationFixture({
        availability: 'LONG_GAP',
        preview: null,
        catchUpFrom: FROM,
        ...overrides,
      }),
    )
  }

  function continueButton(): HTMLElement {
    return screen.getByRole('button', { name: copy.longGap.continueAction })
  }

  const longGapStatus = (): SyncStatus => status({ availability: 'LONG_GAP' })

  it('offers 기존 계획 계속 with the range it collects and its cost, and runs it on a press', () => {
    const requestVerification = vi.fn()
    render(
      <SyncControl
        status={longGapStatus()}
        sync={idleSyncState({ canRequest: false, canCatchUp: true, requestVerification })}
        verification={gap()}
      />,
    )
    const explanation = summaryWith(copy.longGap.label)
    expect(explanation).toHaveTextContent(
      `${copy.longGap.continueRangeLabel}: ${formatSeoulTime(FROM, planResultContent.timeUnavailable)} – ${copy.longGap.continueRangeUntil}`,
    )
    expect(explanation).toHaveTextContent(copy.longGap.continueCost)
    // eslint-disable-next-line testing-library/no-node-access -- 시각의 기계값은 `time` 요소의 속성으로만 읽을 수 있다.
    expect(explanation.querySelector(`time[datetime="${FROM}"]`)).not.toBeNull()
    expect(continueButton()).toHaveAttribute('aria-disabled', 'false')
    expect(continueButton()).not.toHaveAttribute('aria-describedby')
    fireEvent.click(continueButton())
    expect(requestVerification).toHaveBeenCalledWith('CATCH_UP')
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('keeps the long-gap explanation while the catch-up runs, with its own progress and the button blocked in place', () => {
    const requestVerification = vi.fn()
    render(
      <SyncControl
        status={status({
          availability: 'IN_PROGRESS',
          latest: verificationJobFixture({ purpose: 'CATCH_UP', status: 'RUNNING' }),
        })}
        sync={idleSyncState({
          phase: 'running',
          purpose: 'CATCH_UP',
          running: true,
          canRequest: false,
          canCatchUp: false,
          requestVerification,
        })}
        verification={parseVerificationStatus(verificationFixture({ availability: 'IN_PROGRESS' }))}
      />,
    )
    expect(summaryWith(copy.longGap.label)).toBeVisible()
    expect(screen.getByText(catchUp.running, { exact: true })).toBeVisible()
    expect(continueButton()).toHaveAttribute('aria-disabled', 'true')
    expect(continueButton()).toHaveAccessibleDescription(catchUp.running)
    fireEvent.click(continueButton())
    expect(requestVerification).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: copy.action })).not.toBeInTheDocument()
  })

  it('counts the catch-up cool-down down beside its button', () => {
    render(
      <SyncControl
        status={longGapStatus()}
        sync={idleSyncState({
          canRequest: false,
          canCatchUp: false,
          verificationCooldownSeconds: 7,
        })}
        verification={gap({ retryAfterSeconds: 7 })}
      />,
    )
    expect(screen.getByText(catchUp.cooldown(7))).toBeInTheDocument()
    expect(continueButton()).toHaveAccessibleDescription(catchUp.cooldown(7))
  })

  it.each([
    [
      'the server says it is unavailable',
      parseVerificationStatus(
        verificationFixture({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' }),
      ),
      `${catchUp.unavailableLabel}: ${planVerificationContent.reasons.RESTRICTED}`,
    ],
    [
      'the block could not be read',
      UNKNOWN_VERIFICATION,
      `${catchUp.unavailableLabel}: ${catchUp.unknownReason}`,
    ],
    [
      'the block has not caught up with the long gap yet',
      AVAILABLE_VERIFICATION,
      catchUp.unknownReason,
    ],
  ])('blocked when %s, with the reason beside it', (_name, verification, line) => {
    render(
      <SyncControl
        status={longGapStatus()}
        sync={idleSyncState({ canRequest: false, canCatchUp: false })}
        verification={verification}
      />,
    )
    expect(continueButton()).toHaveAttribute('aria-disabled', 'true')
    expect(continueButton()).toHaveAccessibleDescription(line)
  })

  it('a catch-up that ended without a new result has its summary, which a pressed catch-up moves focus to', () => {
    const review = verificationJobFixture({
      purpose: 'CATCH_UP',
      reason: 'LONG_GAP_CONTINUATION',
      status: 'NEEDS_REVIEW',
      failureCode: 'HISTORY_OUT_OF_RANGE',
      stage: 'COLLECTION',
      finishedAt: '2026-09-27T01:12:00Z',
    })
    const { rerender } = render(
      <SyncControl
        status={status({ availability: 'LONG_GAP', latest: review })}
        sync={idleSyncState({ canRequest: false, canCatchUp: true })}
        verification={gap({ latest: review })}
      />,
    )
    continueButton().focus()
    rerender(
      <SyncControl
        status={status({ availability: 'LONG_GAP', latest: review })}
        sync={idleSyncState({
          phase: 'review',
          purpose: 'CATCH_UP',
          canRequest: false,
          canCatchUp: true,
          focus: { target: 'summary', serial: 1 },
        })}
        verification={gap({ latest: review })}
      />,
    )
    const summary = summaryWith(catchUp.summary.statuses.NEEDS_REVIEW ?? 'missing')
    expect(summary).toHaveFocus()
    // In the catch-up's own words: its cause, its kept result and its stage.
    expect(summary).toHaveTextContent(
      `${catchUp.summary.causeLabel}: ${catchUp.summary.codes.HISTORY_OUT_OF_RANGE ?? 'missing'}`,
    )
    expect(summary).toHaveTextContent(catchUp.summary.kept)
    expect(summary).toHaveTextContent(
      `${catchUp.summary.stageLabel}: ${catchUp.summary.stages.COLLECTION ?? 'missing'}`,
    )
    // The sync's own summary does not claim the catch-up's ending.
    expect(screen.queryByText(copy.summary.statuses.NEEDS_REVIEW)).not.toBeInTheDocument()
  })

  it.each(['cooldown', 'error', 'stalled', 'stale', 'busy', 'refused', 'notUseful'] as const)(
    'a catch-up request that ended as %s is said inside the long-gap explanation',
    (phase) => {
      render(
        <SyncControl
          status={longGapStatus()}
          sync={idleSyncState({ phase, purpose: 'CATCH_UP', canRequest: false })}
          verification={gap()}
        />,
      )
      expect(summaryWith(catchUp.local[phase].label)).toHaveTextContent(catchUp.local[phase].text)
    },
  )

  it('a catch-up that met no long gap is said beside the sync button once the long gap is gone', () => {
    render(
      <SyncControl
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'notLongGap', purpose: 'CATCH_UP' })}
        verification={AVAILABLE_VERIFICATION}
      />,
    )
    expect(button()).toBeVisible()
    expect(summaryWith(catchUp.local.notLongGap.label)).toHaveTextContent(
      catchUp.local.notLongGap.text,
    )
  })

  it('C3: a long gap whose basis a review already holds offers no catch-up: a notice takes the button’s place', () => {
    const review = verificationJobFixture({
      status: 'NEEDS_REVIEW',
      failureCode: 'SOURCE_EVENT_MISSING',
      finishedAt: '2026-09-27T01:12:00Z',
      holdsResult: true,
    })
    const { rerender } = render(
      <SyncControl
        status={longGapStatus()}
        sync={idleSyncState({
          canRequest: false,
          canCatchUp: false,
          verificationCooldownSeconds: 5,
        })}
        verification={gap({ availability: 'NOT_USEFUL', latest: review, retryAfterSeconds: null })}
      />,
    )
    // The long-gap explanation stays, with the range a catch-up would collect.
    expect(summaryWith(copy.longGap.label)).toHaveTextContent(copy.longGap.continueRangeLabel)
    expect(
      screen.queryByRole('button', { name: copy.longGap.continueAction }),
    ).not.toBeInTheDocument()
    // Story 2.13: ending the plan with the gap recorded is still a way out; it is the one button left.
    expect(screen.getAllByRole('button').map((each) => each.textContent)).toEqual([
      copy.longGap.newPlanAction,
    ])
    const notice = summaryWith(catchUp.notUseful.label)
    expect(notice).toHaveTextContent(catchUp.notUseful.text)
    expect(notice).toHaveAttribute('tabindex', '-1')
    // Neither a cool-down nor an unknown reason is said for a button that is not there.
    expect(screen.queryByText(catchUp.cooldown(5))).not.toBeInTheDocument()
    expect(screen.queryByText(catchUp.unknownReason)).not.toBeInTheDocument()
    // It does not tell the user to ask for a precision verification either.
    expect(notice).not.toHaveTextContent(planVerificationContent.action)
    // A pressed catch-up refused as not useful: the notice says it; no second summary with the same name.
    rerender(
      <SyncControl
        status={longGapStatus()}
        sync={idleSyncState({
          phase: 'notUseful',
          purpose: 'CATCH_UP',
          canRequest: false,
          canCatchUp: false,
          focus: { target: 'summary', serial: 1 },
        })}
        verification={gap({ availability: 'NOT_USEFUL', latest: review, retryAfterSeconds: null })}
      />,
    )
    expect(screen.queryByText(catchUp.local.notUseful.text)).not.toBeInTheDocument()
    expect(screen.getAllByText(catchUp.notUseful.label)).toHaveLength(1)
    expect(summaryWith(catchUp.notUseful.label)).toHaveFocus()
  })

  it('a long gap the verification block names while another state is said still shows the catch-up, blocked with that reason', () => {
    render(
      <SyncControl
        status={status({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })}
        sync={idleSyncState({ canRequest: false, canVerify: false, canCatchUp: false })}
        verification={gap({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })}
      />,
    )
    expect(screen.queryByRole('button', { name: copy.action })).not.toBeInTheDocument()
    expect(summaryWith(copy.longGap.label)).toHaveTextContent(copy.longGap.continueRangeLabel)
    expect(continueButton()).toHaveAttribute('aria-disabled', 'true')
    expect(continueButton()).toHaveAccessibleDescription(
      `${catchUp.unavailableLabel}: ${planVerificationContent.reasons.RESTRICTED}`,
    )
  })

  it('P17: a catch-up that ended keeps its summary after the control leaves the long-gap form', () => {
    const failed = verificationJobFixture({
      purpose: 'CATCH_UP',
      reason: 'LONG_GAP_CONTINUATION',
      status: 'FAILED',
      failureCode: 'PROVIDER_UNAVAILABLE',
      stage: 'COLLECTION',
      finishedAt: '2026-09-27T01:12:00Z',
    })
    const unavailable = (): SyncStatus =>
      status({ availability: 'UNAVAILABLE', reason: 'RESTRICTED', latest: failed })
    const { rerender } = render(
      <SyncControl
        status={unavailable()}
        sync={idleSyncState({
          phase: 'running',
          purpose: 'CATCH_UP',
          running: true,
          canRequest: false,
        })}
        verification={parseVerificationStatus(
          verificationFixture({
            availability: 'UNAVAILABLE',
            reason: 'RESTRICTED',
            latest: failed,
          }),
        )}
      />,
    )
    // The pressed catch-up ended (said, with focus) while the plan is now shown as unavailable.
    rerender(
      <SyncControl
        status={unavailable()}
        sync={idleSyncState({
          phase: 'failed',
          purpose: 'CATCH_UP',
          canRequest: false,
          focus: { target: 'summary', serial: 1 },
        })}
        verification={parseVerificationStatus(
          verificationFixture({
            availability: 'UNAVAILABLE',
            reason: 'RESTRICTED',
            latest: failed,
          }),
        )}
      />,
    )
    const summary = summaryWith(catchUp.summary.statuses.FAILED ?? 'missing')
    expect(summary).toHaveFocus()
    expect(summary).toHaveTextContent(catchUp.summary.codes.PROVIDER_UNAVAILABLE ?? 'missing')
    // In its own words: never "sync again".
    expect(summary.textContent).not.toContain('동기화할 수 있습니다')
    // Once the outcome is dropped (a reload), the plan's latest job still explains it.
    rerender(
      <SyncControl
        status={unavailable()}
        sync={idleSyncState({ canRequest: false })}
        verification={parseVerificationStatus(
          verificationFixture({
            availability: 'UNAVAILABLE',
            reason: 'RESTRICTED',
            latest: failed,
          }),
        )}
      />,
    )
    expect(summaryWith(catchUp.summary.statuses.FAILED ?? 'missing')).toBeVisible()
    expect(screen.queryByText(copy.summary.statuses.FAILED)).not.toBeInTheDocument()
  })

  it('F7: while an automatic sync admission has not answered, the blocked catch-up button says why', () => {
    render(
      <SyncControl
        status={longGapStatus()}
        sync={idleSyncState({ canRequest: false, canCatchUp: false, admitting: true })}
        verification={gap()}
      />,
    )
    expect(continueButton()).toHaveAttribute('aria-disabled', 'true')
    expect(continueButton()).toHaveAccessibleDescription(catchUp.admitting)
  })

  it('F7: a job this screen no longer follows blocks the catch-up, named by its own purpose', () => {
    const job = verificationJobFixture({ purpose: 'VERIFY', status: 'RUNNING' })
    render(
      <SyncControl
        status={status({ availability: 'IN_PROGRESS', latest: job })}
        sync={idleSyncState({ phase: 'stalled', purpose: 'VERIFY', canRequest: false })}
        verification={gap({ availability: 'IN_PROGRESS', latest: job, retryAfterSeconds: null })}
      />,
    )
    expect(continueButton()).toHaveAttribute('aria-disabled', 'true')
    expect(continueButton()).toHaveAccessibleDescription(catchUp.inProgressUnfollowed.VERIFY)
  })

  it('F6: a pressed catch-up that ended with a new bundle sends focus to the heading only, never to the sync button first', () => {
    function gapPanel(
      syncStatus: SyncStatus,
      verification: VerificationStatus,
      sync: DashboardSync,
    ): ReactElement {
      const body = planResultFixture()
      return (
        <PlanResultPanel
          planEnd={idlePlanEnd()}
          reauthentication={idleReauthentication()}
          adjustments={{
            view: { kind: 'closed' },
            open: vi.fn(),
            close: vi.fn(),
            filter: vi.fn(),
            more: vi.fn(),
            retry: vi.fn(),
          }}
          dashboard={{
            view: {
              kind: 'READY',
              nextAction: 'NONE',
              result: body as unknown as PlanResult,
              sync: syncStatus,
              verification,
              change: NO_CHANGE,
              planEnd: AVAILABLE_PLAN_END,
              end: null,
            },
            loading: false,
            reload: vi.fn(),
            refresh: vi.fn(),
          }}
          evidence={{ view: { kind: 'closed' }, open: vi.fn(), close: vi.fn(), retry: vi.fn() }}
          sync={sync}
        />
      )
    }
    const { rerender } = render(
      gapPanel(longGapStatus(), gap(), idleSyncState({ canRequest: false, canCatchUp: true })),
    )
    continueButton().focus()
    expect(continueButton()).toHaveFocus()
    const focused: string[] = []
    const record = (event: FocusEvent): void => {
      focused.push((event.target as HTMLElement).textContent)
    }
    document.addEventListener('focusin', record)
    // The catch-up published: the long gap is over (its form and button go) and the heading is asked for.
    rerender(
      gapPanel(
        AVAILABLE_SYNC,
        AVAILABLE_VERIFICATION,
        idleSyncState({
          phase: 'succeeded',
          purpose: 'CATCH_UP',
          focus: { target: 'heading', serial: 1 },
        }),
      ),
    )
    document.removeEventListener('focusin', record)
    expect(screen.getByRole('heading', { level: 2, name: planResultContent.heading })).toHaveFocus()
    expect(focused).toEqual([planResultContent.heading])
    expect(button()).not.toHaveFocus()
  })

  it('moves focus to the catch-up button when the summary that held it goes away', () => {
    const { rerender } = render(
      <SyncControl
        status={longGapStatus()}
        sync={idleSyncState({ phase: 'error', purpose: 'CATCH_UP', canRequest: false })}
        verification={gap()}
      />,
    )
    summaryWith(catchUp.local.error.label).focus()
    rerender(
      <SyncControl
        status={longGapStatus()}
        sync={idleSyncState({ canRequest: false, canCatchUp: true })}
        verification={gap()}
      />,
    )
    expect(continueButton()).toHaveFocus()
  })
})

describe('the sync control beside a precision verification (Story 2.12)', () => {
  it('while a verification runs, the sync button is blocked and says so, without 동기화 중', () => {
    render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({
          availability: 'IN_PROGRESS',
          latest: verificationJobFixture({ status: 'RUNNING' }),
        })}
        sync={idleSyncState({
          phase: 'running',
          purpose: 'VERIFY',
          running: true,
          canRequest: false,
        })}
      />,
    )
    expect(screen.queryByText(copy.running, { exact: true })).not.toBeInTheDocument()
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleDescription(copy.inProgressOther)
  })

  it.each([
    ['a precision verification', 'VERIFY', copy.inProgressUnfollowed.VERIFY],
    ['a catch-up', 'CATCH_UP', copy.inProgressUnfollowed.CATCH_UP],
  ] as const)(
    'F7: %s still in progress that this screen no longer follows is named by its own purpose, never as a sync',
    (_name, purpose, text) => {
      const job = verificationJobFixture({ purpose, status: 'RUNNING' })
      render(
        <SyncControl
          verification={parseVerificationStatus(
            verificationFixture({ availability: 'IN_PROGRESS', latest: job }),
          )}
          status={status({ availability: 'IN_PROGRESS', latest: job })}
          sync={idleSyncState({ phase: 'stalled', purpose, canRequest: false })}
        />,
      )
      expect(button()).toHaveAttribute('aria-disabled', 'true')
      expect(button()).toHaveAccessibleDescription(text)
      expect(screen.queryByText(copy.inProgressUnfollowed.SYNC)).not.toBeInTheDocument()
    },
  )

  it("a verification's ending and outcomes are not the sync control's to say", () => {
    const { rerender } = render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({
          latest: verificationJobFixture({
            status: 'NEEDS_REVIEW',
            failureCode: 'SOURCE_EVENT_MISSING',
            finishedAt: '2026-09-27T01:10:40Z',
          }),
        })}
        sync={idleSyncState()}
      />,
    )
    expect(screen.queryByText(copy.summary.statuses.NEEDS_REVIEW)).not.toBeInTheDocument()
    rerender(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'error', purpose: 'VERIFY' })}
      />,
    )
    expect(screen.queryByText(copy.local.error.label)).not.toBeInTheDocument()
  })
})

describe('새 계획 시작 in the long-gap choice (Story 2.13)', () => {
  const FROM = '2026-08-20T01:00:00Z'
  const longGapStatus = (): SyncStatus => status({ availability: 'LONG_GAP' })

  function gap(overrides: Record<string, unknown> = {}): VerificationStatus {
    return parseVerificationStatus(
      verificationFixture({
        availability: 'LONG_GAP',
        preview: null,
        catchUpFrom: FROM,
        ...overrides,
      }),
    )
  }

  function choice(overrides: Partial<NewPlanChoice> = {}): NewPlanChoice {
    return {
      status: parsePlanEndStatus(planEndFixture({ availability: 'NEW_PLAN_CHOICE' })),
      flow: idlePlanEnd(),
      leaving: false,
      ...overrides,
    }
  }

  function newPlanButton(): HTMLElement {
    return screen.getByRole('button', { name: copy.longGap.newPlanAction })
  }

  it('a revoked plan after a long gap keeps 새 계획 시작 so it can still end (Story 2.16)', () => {
    const request = vi.fn()
    render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({ availability: 'UNAVAILABLE', reason: 'CONNECTION_REVOKED' })}
        sync={idleSyncState({ canRequest: false })}
        newPlan={choice({ flow: idlePlanEnd({ request }) })}
      />,
    )
    expect(screen.getByText(copy.reasons.CONNECTION_REVOKED)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: copy.action })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: copy.longGap.continueAction }),
    ).not.toBeInTheDocument()
    expect(newPlanButton()).toHaveAttribute('id', planEndContent.newPlanActionId)
    expect(newPlanButton()).toHaveAttribute('aria-disabled', 'false')
    fireEvent.click(newPlanButton())
    expect(request).toHaveBeenCalledWith('LONG_GAP_NEW_PLAN')
  })

  it('a revoked plan without a long gap shows no new-plan button: the end area offers the early end', () => {
    render(
      <SyncControl
        verification={AVAILABLE_VERIFICATION}
        status={status({ availability: 'UNAVAILABLE', reason: 'CONNECTION_REVOKED' })}
        sync={idleSyncState({ canRequest: false })}
        newPlan={choice({
          status: parsePlanEndStatus(planEndFixture({ availability: 'AVAILABLE' })),
        })}
      />,
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('sits right after 기존 계획 계속 and starts the end flow with the long-gap reason', () => {
    const request = vi.fn()
    render(
      <SyncControl
        newPlan={choice({ flow: idlePlanEnd({ request }) })}
        status={longGapStatus()}
        sync={idleSyncState({ canRequest: false, canCatchUp: true })}
        verification={gap()}
      />,
    )
    expect(screen.getAllByRole('button').map((each) => each.textContent)).toEqual([
      copy.longGap.continueAction,
      copy.longGap.newPlanAction,
    ])
    expect(newPlanButton()).toHaveAttribute('aria-disabled', 'false')
    expect(newPlanButton()).not.toHaveAttribute('aria-describedby')
    fireEvent.click(newPlanButton())
    expect(request).toHaveBeenCalledWith('LONG_GAP_NEW_PLAN')
    // The explanation says what it does: the gap is not collected, and the plan ends rather than continues.
    expect(copy.longGap.newPlanText).toContain('수집하지 않습니다')
    expect(copy.longGap.newPlanText).toContain(
      planEndContent.record.completions.UNAVAILABLE_DATA_GAP ?? 'missing',
    )
    expect(getObservedStorageEntries()).toEqual([])
  })

  it.each([
    [
      'this tab’s catch-up runs: its progress line',
      idleSyncState({ running: true, purpose: 'CATCH_UP', phase: 'running' }),
      planVerificationContent.catchUp.running,
    ],
    [
      'another job this tab follows: the shared busy line',
      idleSyncState({ running: true, purpose: 'SYNC', phase: 'running' }),
      planVerificationContent.catchUp.inProgressOther,
    ],
    [
      'an automatic admission in flight: the shared busy line',
      idleSyncState({ admitting: true }),
      planVerificationContent.catchUp.admitting,
    ],
  ])(
    'shares the catch-up’s busy gate (%s), described by the same line as the catch-up',
    (_name, sync, line) => {
      const request = vi.fn()
      render(
        <SyncControl
          newPlan={choice({ flow: idlePlanEnd({ request }) })}
          status={longGapStatus()}
          sync={{ ...sync, canRequest: false }}
          verification={gap()}
        />,
      )
      expect(newPlanButton()).toHaveAttribute('aria-disabled', 'true')
      expect(newPlanButton()).toHaveAccessibleDescription(line)
      // One line for both buttons, never a second near-identical one.
      expect(screen.queryByText(planEndContent.area.busy)).not.toBeInTheDocument()
      expect(screen.getAllByText(line)).toHaveLength(1)
      fireEvent.click(newPlanButton())
      expect(request).not.toHaveBeenCalled()
    },
  )

  it('a job in progress while the catch-up cannot help is said once, for the new-plan button', () => {
    const review = verificationJobFixture({
      status: 'NEEDS_REVIEW',
      failureCode: 'SOURCE_EVENT_MISSING',
      finishedAt: '2026-09-27T01:12:00Z',
      holdsResult: true,
    })
    render(
      <SyncControl
        newPlan={choice()}
        status={status({ availability: 'IN_PROGRESS', latest: syncJobFixture() })}
        sync={idleSyncState({ canRequest: false })}
        verification={gap({ availability: 'NOT_USEFUL', latest: review })}
      />,
    )
    expect(newPlanButton()).toHaveAttribute('aria-disabled', 'true')
    expect(newPlanButton()).toHaveAccessibleDescription(planVerificationContent.catchUp.inProgress)
  })

  it.each([
    [
      'the prepare runs',
      { flow: idlePlanEnd({ busy: true, preparing: true }), leaving: false },
      planEndContent.area.preparing,
    ],
    [
      'the dialog is open',
      {
        flow: idlePlanEnd({
          busy: true,
          dialog: {
            reason: 'LONG_GAP_NEW_PLAN',
            startAt: '2026-09-25T00:00:00Z',
            endAt: '2026-10-25T04:00:00Z',
            confirming: false,
            uncertain: false,
          },
        }),
        leaving: false,
      },
      planEndContent.area.dialogOpen,
    ],
    [
      'a reauthentication is leaving',
      { flow: idlePlanEnd(), leaving: true },
      planEndContent.area.leaving,
    ],
  ])('is blocked while %s, and says why', (_name, overrides, line) => {
    const request = vi.fn()
    render(
      <SyncControl
        newPlan={choice({ ...overrides, flow: { ...overrides.flow, request } })}
        status={longGapStatus()}
        sync={idleSyncState({ canRequest: false })}
        verification={gap()}
      />,
    )
    expect(newPlanButton()).toHaveAttribute('aria-disabled', 'true')
    expect(newPlanButton()).toHaveAccessibleDescription(line)
    fireEvent.click(newPlanButton())
    expect(request).not.toHaveBeenCalled()
  })

  it('is not blocked by the catch-up’s cool-down: ending collects nothing', () => {
    render(
      <SyncControl
        newPlan={choice()}
        status={longGapStatus()}
        sync={idleSyncState({ canRequest: false, verificationCooldownSeconds: 9 })}
        verification={gap({ retryAfterSeconds: 9 })}
      />,
    )
    expect(newPlanButton()).toHaveAttribute('aria-disabled', 'false')
  })

  it.each([
    [
      'RESTRICTED',
      planEndFixture({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' }),
      planEndContent.area.reasons.RESTRICTED,
    ],
    [
      'an unknown reason',
      planEndFixture({ availability: 'UNAVAILABLE', reason: 'NEW_RULE' }),
      planEndContent.area.unknownReason,
    ],
    // The sync block met a long gap the end block no longer sees: the end area's early end is the way.
    ['not a long gap for the server', planEndFixture(), planEndContent.notLongGapReason],
    [
      'a result newer than the server clock',
      planEndFixture({ availability: 'UNAVAILABLE', reason: 'RESULT_TOO_RECENT' }),
      planEndContent.area.reasons.RESULT_TOO_RECENT,
    ],
  ])(
    'blocked when the server does not take the choice (%s), with the reason beside it',
    (_name, block, reason) => {
      const request = vi.fn()
      render(
        <SyncControl
          newPlan={choice({ status: parsePlanEndStatus(block), flow: idlePlanEnd({ request }) })}
          status={longGapStatus()}
          sync={idleSyncState({ canRequest: false })}
          verification={gap()}
        />,
      )
      const line = `${copy.longGap.newPlanUnavailableLabel}: ${reason ?? 'missing'}`
      expect(screen.getByText(line)).toBeVisible()
      expect(newPlanButton()).toHaveAttribute('aria-disabled', 'true')
      expect(newPlanButton()).toHaveAccessibleDescription(line)
      fireEvent.click(newPlanButton())
      expect(request).not.toHaveBeenCalled()
    },
  )

  it('takes focus when what held it inside the control goes away and the catch-up button is not there', () => {
    const review = verificationJobFixture({
      status: 'NEEDS_REVIEW',
      failureCode: 'SOURCE_EVENT_MISSING',
      finishedAt: '2026-09-27T01:12:00Z',
      holdsResult: true,
    })
    const { rerender } = render(
      <SyncControl
        newPlan={choice()}
        status={longGapStatus()}
        sync={idleSyncState({ phase: 'error', purpose: 'CATCH_UP', canRequest: false })}
        verification={gap({ availability: 'NOT_USEFUL', latest: review })}
      />,
    )
    // The catch-up's own summary holds focus, then goes (the outcome was said).
    summaryWith(planVerificationContent.catchUp.local.error.label).focus()
    rerender(
      <SyncControl
        newPlan={choice()}
        status={longGapStatus()}
        sync={idleSyncState({ canRequest: false })}
        verification={gap({ availability: 'NOT_USEFUL', latest: review })}
      />,
    )
    expect(newPlanButton()).toHaveFocus()
  })

  it('is not part of the sync control outside a long gap', () => {
    render(
      <SyncControl
        newPlan={choice({ status: parsePlanEndStatus(planEndFixture()) })}
        status={AVAILABLE_SYNC}
        sync={idleSyncState()}
        verification={AVAILABLE_VERIFICATION}
      />,
    )
    expect(
      screen.queryByRole('button', { name: copy.longGap.newPlanAction }),
    ).not.toBeInTheDocument()
  })
})
