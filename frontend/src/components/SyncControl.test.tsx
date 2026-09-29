import type { ReactElement } from 'react'

import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { PlanResult } from '../session/useDashboard'
import type { DashboardSync } from '../session/useDashboardSync'

import { calculationEvidenceContent } from '../content/calculationEvidence'
import { entryContent } from '../content/entry'
import { axisActionCopy, nextActionCopy, planResultContent } from '../content/planResult'
import { planSyncContent as copy, planSyncCopyStrings } from '../content/planSync'
import { formatSeoulTime } from '../format/decimal'
import {
  isPlanResult,
  parseSyncStatus,
  UNKNOWN_SYNC,
  type SyncStatus,
} from '../session/useDashboard'
import {
  AVAILABLE_SYNC,
  idleSyncState,
  planResultFixture,
  syncFixture,
  syncJobFixture,
} from '../test/planResultFixture'
import { getObservedStorageEntries } from '../test/setup'
import { PlanResultPanel } from './PlanResultPanel'
import { SyncControl } from './SyncControl'

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
    render(<SyncControl status={AVAILABLE_SYNC} sync={idleSyncState({ request })} />)

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
    ['SOMETHING_NEW', copy.unknownReason],
  ])('unavailable (%s): the button is blocked and the reason is beside it', (reason, text) => {
    render(
      <SyncControl
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

  it.each([
    ['FAILED', 'PROVIDER_UNAVAILABLE', copy.summary.statuses.FAILED],
    ['NEEDS_REVIEW', 'CONVERSION_REQUIRES_REVIEW', copy.summary.statuses.NEEDS_REVIEW],
    ['BLOCKED', 'MEMBERSHIP_CHANGED', copy.summary.statuses.BLOCKED],
  ] as const)(
    'the latest job ended %s: the summary names the cause, the kept result, the stage, the request and the time',
    (jobStatus, code, label) => {
      render(
        <SyncControl
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
        status={status({ latest: ended({ status: 'FAILED', failureCode: 'SYNC_FAILED' }) })}
        sync={idleSyncState({ phase: 'running', running: true, canRequest: false })}
      />,
    )
    expect(screen.queryByText(copy.summary.statuses.FAILED)).not.toBeInTheDocument()
    rerender(
      <SyncControl
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
      render(<SyncControl status={AVAILABLE_SYNC} sync={idleSyncState({ phase })} />)
      const summary = summaryWith(copy.local[phase].label)
      expect(summary).toHaveTextContent(copy.local[phase].text)
      expect(summary).toHaveAttribute('tabindex', '-1')
    },
  )

  it('moves focus to the summary when asked, once, and leaves the heading requests to the panel', () => {
    const { rerender } = render(
      <SyncControl status={AVAILABLE_SYNC} sync={idleSyncState({ phase: 'error' })} />,
    )
    button().focus()
    rerender(
      <SyncControl
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'error', focus: { target: 'heading', serial: 1 } })}
      />,
    )
    expect(button()).toHaveFocus()
    rerender(
      <SyncControl
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'error', focus: { target: 'summary', serial: 2 } })}
      />,
    )
    expect(summaryWith(copy.local.error.label)).toHaveFocus()
    // The same request again is not a new one.
    button().focus()
    rerender(
      <SyncControl
        status={AVAILABLE_SYNC}
        sync={idleSyncState({ phase: 'error', focus: { target: 'summary', serial: 2 } })}
      />,
    )
    expect(button()).toHaveFocus()
  })

  it('a request made before it was mounted is not replayed', () => {
    render(
      <SyncControl
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
    'after a long gap (%s): no button, the two choices and their data ranges, and a read-only result',
    (_name, block, sync) => {
      render(<SyncControl status={block} sync={sync} />)
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
      const explanation = summaryWith(copy.longGap.label)
      expect(explanation).toHaveAttribute('tabindex', '-1')
      for (const text of [
        copy.longGap.text,
        copy.longGap.continueTitle,
        copy.longGap.continueText,
        copy.longGap.newPlanTitle,
        copy.longGap.newPlanText,
        copy.longGap.readOnly,
        copy.longGap.notHere,
      ]) {
        expect(explanation).toHaveTextContent(text)
      }
      // Described only: neither choice runs from here, and nothing links to a screen that does not exist.
      expect(screen.queryByRole('link')).not.toBeInTheDocument()
    },
  )

  it('moves focus to the long-gap explanation when a pressed sync ended that way', () => {
    const { rerender } = render(<SyncControl status={AVAILABLE_SYNC} sync={idleSyncState()} />)
    button().focus()
    rerender(
      <SyncControl
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
      <SyncControl status={AVAILABLE_SYNC} sync={idleSyncState({ phase: 'unavailable' })} />,
    )
    expect(screen.getAllByText(new RegExp(copy.unavailableLabel))).toHaveLength(1)
    expect(summaryWith(copy.local.unavailable.label)).toHaveTextContent(copy.local.unavailable.text)
    // Then the server's reason line says it, and the summary gives way to it.
    rerender(
      <SyncControl
        status={status({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })}
        sync={idleSyncState({ phase: 'unavailable', canRequest: false })}
      />,
    )
    expect(screen.getAllByText(new RegExp(copy.unavailableLabel))).toHaveLength(1)
    expect(screen.getByText(`${copy.unavailableLabel}: ${copy.reasons.RESTRICTED}`)).toBeVisible()
    expect(screen.queryByText(copy.local.unavailable.text)).not.toBeInTheDocument()
  })

  it('P14: a sync block it could not read blocks the button with a safe reason', () => {
    render(<SyncControl status={UNKNOWN_SYNC} sync={idleSyncState({ canRequest: false })} />)
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleDescription(`${copy.unavailableLabel}: ${copy.unknownReason}`)
  })

  it('P16: a job found in progress elsewhere is followed with its reason beside the blocked button', () => {
    const { rerender } = render(
      <SyncControl
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
        status={status({
          availability: 'IN_PROGRESS',
          latest: syncJobFixture({ status: 'RUNNING' }),
        })}
        sync={idleSyncState({ phase: 'stalled', canRequest: false })}
      />,
    )
    expect(screen.queryByText(copy.running, { exact: true })).not.toBeInTheDocument()
    expect(button()).toHaveAccessibleDescription(copy.inProgressUnfollowed)
    // This tab's own job: 동기화 중, without the elsewhere line.
    rerender(
      <SyncControl
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
    // A choice the screen cannot perform is described, not asked for.
    for (const text of [
      nextActionCopy('CHOOSE_CONTINUATION', null),
      axisActionCopy('CHOOSE_CONTINUATION'),
    ]) {
      expect(text).toContain('아직 선택할 수 없습니다')
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
