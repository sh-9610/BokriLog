import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { DashboardSync, SyncFocus, SyncJob, SyncPhase } from '../session/useDashboardSync'

import { planSyncContent as copy } from '../content/planSync'
import { getObservedRequests, getObservedStorageEntries } from '../test/setup'
import { SyncControl } from './SyncControl'

const MEASURED = '2026. 9. 27. 오전 9:59:59'

function job(overrides: Partial<SyncJob> = {}): SyncJob {
  return {
    jobId: 'job-1',
    trigger: 'MANUAL',
    status: 'FAILED',
    stage: 'COLLECTION',
    mode: 'NORMAL',
    failureCode: 'SOURCE_UNAVAILABLE',
    retryable: true,
    createdAt: '2026-09-27T01:00:00Z',
    finishedAt: '2026-09-27T01:00:05Z',
    pointerVersion: null,
    ...overrides,
  }
}

function sync(
  phase: SyncPhase,
  overrides: Partial<Omit<DashboardSync, 'phase'>> = {},
): DashboardSync {
  return {
    phase,
    manual: { available: true },
    focus: null,
    requestManual: vi.fn(),
    ...overrides,
  }
}

function button(): HTMLElement {
  return screen.getByRole('button', { name: copy.button })
}

/** 금지 표현: 실시간·정확 보장, 수익 축하, 손실 공포, 스트릭·배지. */
const FORBIDDEN = /실시간|정확|보장|축하|대박|손실 위험|위험합니다|스트릭|연속|배지/

describe('SyncControl', () => {
  it('AC-7: one primary button, the last normal time and the late-events note beside it', () => {
    const state = sync({ kind: 'idle' })
    render(<SyncControl longGap={false} measured={MEASURED} sync={state} />)

    expect(screen.getAllByRole('button')).toHaveLength(1)
    expect(button()).toHaveAttribute('aria-disabled', 'false')
    expect(button()).not.toHaveAttribute('aria-describedby')
    expect(screen.getByText(copy.lastNormal(MEASURED))).toBeInTheDocument()
    expect(screen.getByText(copy.lateEvents)).toBeInTheDocument()

    fireEvent.click(button())
    expect(state.requestManual).toHaveBeenCalledTimes(1)
    // The control itself sends nothing and stores nothing: the hook does.
    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
  })

  const REFUSALS = [
    { cause: 'refused', line: copy.deniedLine },
    { cause: 'signedOut', line: copy.signedOutLine },
  ] as const

  it.each(REFUSALS)(
    'P13-f: after a refusal ($cause) the reason is on screen under the button, as its description',
    ({ cause, line }) => {
      const state = sync({ kind: 'denied', cause, csrfToken: 'csrf' })
      render(<SyncControl longGap={false} measured={MEASURED} sync={state} />)
      expect(button()).toHaveAttribute('aria-disabled', 'true')
      expect(button()).toHaveAccessibleDescription(line)
      expect(screen.getByText(line)).toBeVisible()
      fireEvent.click(button())
      expect(state.requestManual).not.toHaveBeenCalled()
    },
  )

  it('says it cannot show the last normal time rather than showing a blank', () => {
    render(<SyncControl longGap={false} measured={null} sync={sync({ kind: 'idle' })} />)
    expect(screen.getByText(copy.lastNormalUnknown)).toBeInTheDocument()
  })

  const IN_PROGRESS: readonly { readonly name: string; readonly phase: SyncPhase }[] = [
    { name: 'the request in flight', phase: { kind: 'submitting' } },
    {
      name: 'the attempt in progress',
      phase: { kind: 'syncing', job: job({ status: 'RUNNING' }), userInitiated: true },
    },
  ]

  it.each(IN_PROGRESS)(
    'AC-7: $name says 동기화 중 under the unchanged name, and a press does nothing while focus stays',
    ({ phase }) => {
      const state = sync(phase)
      render(<SyncControl longGap={false} measured={MEASURED} sync={state} />)

      const control = button()
      expect(control).toHaveAttribute('aria-disabled', 'true')
      expect(control).toHaveAccessibleDescription(`${copy.syncing} ${copy.syncingHelp}`)
      control.focus()
      fireEvent.click(control)
      fireEvent.click(control)
      expect(state.requestManual).not.toHaveBeenCalled()
      expect(control).toHaveFocus()
      // No live region of its own: the screen's status region says what happened.
      expect(screen.queryByRole('status')).toBeNull()
    },
  )

  it.each([
    {
      reason: 'COOLDOWN',
      manual: { available: false, reason: 'COOLDOWN', retryAfterSeconds: 12 },
      line: copy.cooldown(12),
    },
    {
      reason: 'SYNC_RESTRICTED',
      manual: { available: false, reason: 'SYNC_RESTRICTED' },
      line: copy.unavailable('SYNC_RESTRICTED'),
    },
    {
      reason: 'SOURCE_CHANGED',
      manual: { available: false, reason: 'SOURCE_CHANGED' },
      line: copy.unavailable('SOURCE_CHANGED'),
    },
    {
      reason: 'an unknown reason',
      manual: { available: false, reason: 'SOMETHING_NEW' },
      line: copy.unavailable('SOMETHING_NEW'),
    },
  ])(
    'a server that said not now ($reason) keeps the button unavailable and says why',
    ({ manual, line }) => {
      const state = sync(
        { kind: 'answered', outcome: 'COOLDOWN', reason: null, userInitiated: true },
        {
          manual,
        },
      )
      render(<SyncControl longGap={false} measured={MEASURED} sync={state} />)

      expect(button()).toHaveAttribute('aria-disabled', 'true')
      expect(button()).toHaveAccessibleDescription(line)
      expect(screen.queryByText('SOMETHING_NEW')).toBeNull()
      fireEvent.click(button())
      expect(state.requestManual).not.toHaveBeenCalled()
    },
  )

  it('AC-8: a failed attempt the user started shows its cause, next action and whether another request helps, and takes focus', () => {
    const focus: SyncFocus = { target: 'summary', token: 1 }
    const ended = job()
    const { rerender } = render(
      <SyncControl
        longGap={false}
        measured={MEASURED}
        sync={sync({ kind: 'ended', job: ended, userInitiated: true, token: 1 })}
      />,
    )
    // The token the control mounted with is already handled: a remount never moves focus.
    expect(document.body).toHaveFocus()

    rerender(
      <SyncControl
        longGap={false}
        measured={MEASURED}
        sync={sync({ kind: 'ended', job: ended, userInitiated: true, token: 1 }, { focus })}
      />,
    )
    const summary = screen.getByRole('group', { name: copy.summary.failed })
    expect(summary).toHaveFocus()
    expect(summary).toHaveAttribute('tabindex', '-1')
    const reason = copy.failure('SOURCE_UNAVAILABLE')
    const facts = within(summary)
    expect(facts.getByText(reason.cause)).toBeInTheDocument()
    expect(facts.getByText(`${reason.action} ${copy.summary.retryable}`)).toBeInTheDocument()
    // The button is open again for a retry.
    expect(button()).toHaveAttribute('aria-disabled', 'false')
  })

  it('AC-9: a review names its impact and where the cause is explained; a not-retryable end says another request gives the same answer', () => {
    render(
      <SyncControl
        longGap={false}
        measured={MEASURED}
        sync={sync(
          {
            kind: 'ended',
            job: job({
              status: 'REVIEW_REQUIRED',
              failureCode: 'APPLIED_FLOW_CHANGED',
              retryable: false,
            }),
            userInitiated: true,
            token: 1,
          },
          { focus: { target: 'summary', token: 1 } },
        )}
      />,
    )
    const summary = screen.getByRole('group', { name: copy.summary.review })
    const reason = copy.failure('APPLIED_FLOW_CHANGED')
    expect(within(summary).getByText(reason.cause)).toBeInTheDocument()
    expect(
      within(summary).getByText(`${reason.action} ${copy.summary.notRetryable}`),
    ).toBeInTheDocument()
    expect(within(summary).getByText(copy.summary.reviewImpact)).toBeInTheDocument()
  })

  it('a publication shows no summary, and a heading focus request leaves the summary alone', () => {
    render(
      <SyncControl
        longGap={false}
        measured={MEASURED}
        sync={sync(
          {
            kind: 'ended',
            job: job({ status: 'PUBLISHED', failureCode: null, pointerVersion: 2 }),
            userInitiated: true,
            token: 1,
          },
          { focus: { target: 'heading', token: 1 } },
        )}
      />,
    )
    expect(screen.queryByText(copy.summary.failed)).toBeNull()
    expect(document.body).toHaveFocus()
  })

  it('an unknown failure code is folded into the safe copy, never shown as it is', () => {
    render(
      <SyncControl
        longGap={false}
        measured={MEASURED}
        sync={sync({
          kind: 'ended',
          job: job({ failureCode: 'BRAND_NEW_CODE' }),
          userInitiated: false,
          token: 1,
        })}
      />,
    )
    expect(screen.queryByText(/BRAND_NEW_CODE/)).toBeNull()
    expect(screen.getByText(copy.failure('BRAND_NEW_CODE').cause)).toBeInTheDocument()
  })

  const LONG_GAPS: readonly {
    readonly name: string
    readonly longGap: boolean
    readonly phase: SyncPhase
  }[] = [
    { name: 'the result says so', longGap: true, phase: { kind: 'idle' } },
    {
      name: 'the admission said so',
      longGap: false,
      phase: {
        kind: 'answered',
        outcome: 'LONG_GAP',
        reason: null,
        userInitiated: false,
      },
    },
    {
      name: 'the worker said so',
      longGap: false,
      phase: {
        kind: 'ended',
        job: job({ status: 'BLOCKED', failureCode: 'LONG_GAP_CHOICE_REQUIRED', retryable: false }),
        userInitiated: true,
        token: 1,
      },
    },
  ]

  it.each(LONG_GAPS)(
    'AC-1: a long gap ($name) explains both choices and offers no control for either',
    ({ longGap, phase }) => {
      render(
        <SyncControl
          longGap={longGap}
          measured={MEASURED}
          sync={sync(phase, { manual: { available: false, reason: 'LONG_GAP' } })}
        />,
      )
      const section = screen.getByRole('region', { name: copy.longGap.heading })
      expect(within(section).getByText(copy.longGap.intro)).toBeInTheDocument()
      for (const choice of copy.longGap.choices) {
        expect(within(section).getByText(choice.name)).toBeInTheDocument()
      }
      expect(within(section).getByText(copy.longGap.notYet)).toBeInTheDocument()
      // Neither choice is a control yet, and the button is unavailable.
      expect(within(section).queryByRole('button')).toBeNull()
      expect(within(section).queryByRole('link')).toBeNull()
      expect(screen.getAllByRole('button')).toHaveLength(1)
      expect(button()).toHaveAttribute('aria-disabled', 'true')
    },
  )

  it('says nothing the product forbids, in any state', () => {
    const phases: SyncPhase[] = [
      { kind: 'submitting' },
      { kind: 'ended', job: job(), userInitiated: true, token: 1 },
      {
        kind: 'ended',
        job: job({ status: 'REVIEW_REQUIRED', failureCode: 'COLLECTION_REVIEW_REQUIRED' }),
        userInitiated: true,
        token: 1,
      },
      { kind: 'stalled', userInitiated: true, token: 1 },
      { kind: 'submitFailed', token: 1 },
    ]
    for (const phase of phases) {
      const { container, unmount } = render(
        <SyncControl longGap measured={MEASURED} sync={sync(phase)} />,
      )
      expect(container.textContent).not.toMatch(FORBIDDEN)
      unmount()
    }
  })

  it('every closed code the server sends has its own copy, never the safe fallback', () => {
    const unknown = copy.failure('NOT_A_CODE')
    for (const code of [
      'NOT_PERMITTED',
      'PLAN_CHANGED',
      'MEMBERSHIP_CHANGED',
      'SOURCE_CHANGED',
      'POINTER_CONFLICT',
      'LONG_GAP_CHOICE_REQUIRED',
      'PLAN_PERIOD_ENDED',
      'COLLECTION_REVIEW_REQUIRED',
      'CLASSIFICATION_REVIEW_REQUIRED',
      'CONVERSION_REQUIRED',
      'CONVERSION_REVIEW_REQUIRED',
      'APPLIED_FLOW_CHANGED',
      'REFERENCE_TIME_UNAVAILABLE',
      'REFERENCE_TIME_INVALID',
      'SOURCE_UNAVAILABLE',
      'CONVERSION_PENDING',
      'SYNC_INTERRUPTED',
      'SOURCE_REJECTED',
      'CALCULATION_RANGE_EXCEEDED',
      'CALCULATION_INPUT_INVALID',
      'CALCULATION_POLICY_UNVERIFIED',
    ]) {
      expect({ code, copy: copy.failure(code) }).not.toEqual({ code, copy: unknown })
    }
    const fallback = copy.unavailable('NOT_A_CODE')
    for (const reason of [
      'IN_PROGRESS',
      'LONG_GAP',
      'NO_ACTIVE_PLAN',
      'SYNC_RESTRICTED',
      'SOURCE_CHANGED',
      'PLAN_PERIOD_ENDED',
    ]) {
      expect({ reason, copy: copy.unavailable(reason) }).not.toEqual({ reason, copy: fallback })
    }
  })
})
