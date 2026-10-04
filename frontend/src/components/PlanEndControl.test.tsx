import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { Accepted } from '../session/useDashboard'
import type { PlanEnd } from '../session/usePlanEnd'

import { authContent, SIGN_IN_PROVIDERS } from '../content/auth'
import { calculationEvidenceContent } from '../content/calculationEvidence'
import { planConditionContent } from '../content/planConditions'
import { planEndContent as copy, planEndCopyStrings } from '../content/planEnd'
import { axisActionCopy, nextActionCopy } from '../content/planResult'
import { planSyncContent } from '../content/planSync'
import { planVerificationContent } from '../content/planVerification'
import {
  NO_CHANGE,
  parsePlanEndStatus,
  parseSyncStatus,
  parseVerificationStatus,
  type PlanResult,
} from '../session/useDashboard'
import { refusalCopy } from '../session/usePlanEnd'
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
} from '../test/planResultFixture'
import { getObservedRequests, getObservedStorageEntries } from '../test/setup'
import { PlanEndControl } from './PlanEndControl'

const FORBIDDEN =
  /실시간|정확|보장|성공|실패|축하|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/

function bundle(overrides: Partial<Accepted> = {}): Accepted {
  return {
    result: planResultFixture() as unknown as PlanResult,
    nextAction: 'NONE',
    sync: AVAILABLE_SYNC,
    verification: AVAILABLE_VERIFICATION,
    change: NO_CHANGE,
    planEnd: AVAILABLE_PLAN_END,
    end: null,
    ...overrides,
  }
}

function button(): HTMLElement {
  return screen.getByRole('button', { name: copy.area.action })
}

function renderControl(
  options: {
    readonly bundle?: Accepted
    readonly planEnd?: PlanEnd
    readonly pending?: boolean
    readonly sync?: ReturnType<typeof idleSyncState>
  } = {},
): { readonly requestReauthentication: ReturnType<typeof vi.fn> } {
  const requestReauthentication = vi.fn()
  render(
    <PlanEndControl
      bundle={options.bundle ?? bundle()}
      planEnd={options.planEnd ?? idlePlanEnd()}
      reauthentication={idleReauthentication({
        pending: options.pending ?? false,
        requestReauthentication,
      })}
      sync={options.sync ?? idleSyncState()}
    />,
  )
  return { requestReauthentication }
}

describe('PlanEndControl', () => {
  it('AVAILABLE: says what ending does and keeps, beside one secondary 계획 중도 종료 button', () => {
    const request = vi.fn()
    renderControl({ planEnd: idlePlanEnd({ request }) })
    const area = screen.getByRole('region', { name: copy.area.heading })
    expect(area).toHaveAttribute('id', copy.area.id)
    expect(area).toHaveTextContent(copy.area.text)
    expect(copy.area.text).toContain('되돌릴 수 없습니다')
    expect(copy.area.text).toContain('읽기 전용')
    expect(button()).toHaveAttribute('aria-disabled', 'false')
    fireEvent.click(button())
    expect(request).toHaveBeenCalledWith('USER_REQUEST')
    // Nothing is sent by the control itself, and nothing is stored.
    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('NEW_PLAN_CHOICE: only a note naming the long-gap choice, with no button and no position words', () => {
    renderControl({
      bundle: bundle({
        planEnd: parsePlanEndStatus(planEndFixture({ availability: 'NEW_PLAN_CHOICE' })),
      }),
    })
    const area = screen.getByRole('region', { name: copy.area.heading })
    expect(area).toHaveTextContent(copy.area.newPlanChoice)
    expect(copy.area.newPlanChoice).toContain('새 계획 시작')
    expect(copy.area.newPlanChoice).not.toMatch(/위의|아래의|위쪽|아래쪽|오른쪽|왼쪽|위에|아래에/)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it.each([
    ['RESTRICTED', copy.area.reasons.RESTRICTED],
    ['PLAN_PERIOD_ENDED', copy.area.reasons.PLAN_PERIOD_ENDED],
    ['RESULT_TOO_RECENT', copy.area.reasons.RESULT_TOO_RECENT],
    ['RESULT_UNAVAILABLE', copy.area.reasons.RESULT_UNAVAILABLE],
    ['NEW_RULE', copy.area.unknownReason],
    [null, copy.area.unknownReason],
  ])('UNAVAILABLE (%s): only the reason, folded to safe words when unknown', (reason, text) => {
    renderControl({
      bundle: bundle({
        planEnd: parsePlanEndStatus(planEndFixture({ availability: 'UNAVAILABLE', reason })),
      }),
    })
    expect(screen.getByText(`${copy.area.unavailableLabel}: ${text ?? 'missing'}`)).toBeVisible()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText(copy.area.text)).not.toBeInTheDocument()
  })

  it.each([
    [
      'the prepare runs',
      { planEnd: idlePlanEnd({ preparing: true, busy: true }), pending: false },
      copy.area.preparing,
    ],
    [
      'the dialog is open',
      {
        planEnd: idlePlanEnd({
          busy: true,
          dialog: {
            reason: 'USER_REQUEST',
            startAt: '2026-09-25T00:00:00Z',
            endAt: '2026-10-25T04:00:00Z',
            confirming: false,
            uncertain: false,
          },
        }),
        pending: false,
      },
      copy.area.dialogOpen,
    ],
    ['a reauthentication is leaving', { planEnd: idlePlanEnd(), pending: true }, copy.area.leaving],
  ])('is blocked, keeping its place, while %s — and says why', (_name, options, line) => {
    const request = vi.fn()
    renderControl({ ...options, planEnd: { ...options.planEnd, request } })
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toBeEnabled()
    expect(button()).toHaveAccessibleDescription(line)
    expect(button()).toHaveAttribute('id', copy.area.actionId)
    fireEvent.click(button())
    expect(request).not.toHaveBeenCalled()
  })

  it('names every cause at once when several block it', () => {
    renderControl({
      pending: true,
      planEnd: idlePlanEnd({ preparing: true, busy: true }),
      sync: idleSyncState({ running: true }),
    })
    expect(button()).toHaveAccessibleDescription(
      [copy.area.preparing, copy.area.leaving, copy.area.busy].join(' '),
    )
  })

  it.each([
    [
      'an end whose record did not come on screen',
      { kind: 'endedNotShown' } as const,
      copy.endedNotShown,
    ],
    [
      'a dialog closed without knowing whether the plan ended',
      { kind: 'uncertain' } as const,
      copy.uncertain,
    ],
  ])('says %s in the area', (_name, notice, words) => {
    renderControl({ planEnd: idlePlanEnd({ notice }) })
    expect(screen.getByText(words.label)).toBeVisible()
    expect(screen.getByText(words.text)).toBeVisible()
  })

  it.each([
    ['RESULT_TOO_RECENT', copy.area.reasons.RESULT_TOO_RECENT],
    ['RESULT_UNAVAILABLE', copy.area.reasons.RESULT_UNAVAILABLE],
    ['NEW_RULE', copy.unknownRefusalReason],
  ])(
    'a refusal as unavailable (%s) names its reason, then that nothing changed',
    (reason, text) => {
      renderControl({
        planEnd: idlePlanEnd({ notice: { kind: 'refused', refusal: 'unavailable', reason } }),
      })
      expect(screen.getByText(`${text ?? 'missing'} ${copy.kept}`)).toBeVisible()
      expect(screen.queryByText(copy.refusals.unavailable.text)).not.toBeInTheDocument()
    },
  )

  it.each([
    ['this screen runs or follows a job', { sync: idleSyncState({ running: true }) }],
    ['an automatic admission is in flight', { sync: idleSyncState({ admitting: true }) }],
    [
      'the server shows a job in progress',
      {
        bundle: bundle({
          sync: parseSyncStatus(
            syncFixture({ availability: 'IN_PROGRESS', latest: syncJobFixture() }),
          ),
        }),
      },
    ],
    [
      'the server shows a verification in progress',
      {
        bundle: bundle({
          verification: parseVerificationStatus({
            ...AVAILABLE_VERIFICATION,
            availability: 'IN_PROGRESS',
            latest: syncJobFixture({ purpose: 'VERIFY', reason: 'USER_REQUEST' }),
          }),
        }),
      },
    ],
  ])('shares the catch-up’s busy gate: blocked while %s, and says why', (_name, options) => {
    const request = vi.fn()
    renderControl({ ...options, planEnd: idlePlanEnd({ request }) })
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleDescription(copy.area.busy)
    fireEvent.click(button())
    expect(request).not.toHaveBeenCalled()
  })

  it('without a recent reauthentication: the guidance and the three provider buttons, in the fixed order', () => {
    const { requestReauthentication } = renderControl({
      planEnd: idlePlanEnd({ notice: { kind: 'reauthRequired' } }),
    })
    expect(screen.getByText(copy.reauth.label)).toBeVisible()
    expect(screen.getByText(copy.reauth.text)).toBeVisible()
    const names = SIGN_IN_PROVIDERS.map((provider) => authContent.settings.reauth.labels[provider])
    expect(
      screen
        .getAllByRole('button')
        .map((each) => each.textContent)
        .slice(1),
    ).toEqual(names)
    fireEvent.click(screen.getByRole('button', { name: names[1] }))
    expect(requestReauthentication).toHaveBeenCalledWith('naver')
    expect(screen.getByText(authContent.reauth.boundary)).toBeVisible()
  })

  it('the provider buttons are blocked while a reauthentication is leaving', () => {
    const { requestReauthentication } = renderControl({
      pending: true,
      planEnd: idlePlanEnd({ notice: { kind: 'reauthRequired' } }),
    })
    const google = screen.getByRole('button', {
      name: authContent.settings.reauth.labels.google,
    })
    expect(google).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(google)
    expect(requestReauthentication).not.toHaveBeenCalled()
  })

  it('a focus request moves focus to the notice; one made before it was mounted is not replayed', () => {
    const refused = idlePlanEnd({
      notice: { kind: 'refused', refusal: 'stale', reason: null },
      focus: { target: 'notice', serial: 1 },
    })
    const { rerender } = render(
      <PlanEndControl
        bundle={bundle()}
        planEnd={refused}
        reauthentication={idleReauthentication()}
        sync={idleSyncState()}
      />,
    )
    const words = refusalCopy('stale', null)
    // eslint-disable-next-line testing-library/no-node-access -- 표지는 역할이 없는 초점 대상(tabIndex -1)이다.
    const notice = screen.getByText(words.label).parentElement
    expect(notice).not.toHaveFocus()
    expect(notice).toHaveTextContent(words.text)
    rerender(
      <PlanEndControl
        bundle={bundle()}
        planEnd={{ ...refused, focus: { target: 'notice', serial: 2 } }}
        reauthentication={idleReauthentication()}
        sync={idleSyncState()}
      />,
    )
    expect(notice).toHaveFocus()
    expect(notice).toHaveAttribute('tabindex', '-1')
    // A heading request is the panel's.
    rerender(
      <PlanEndControl
        bundle={bundle()}
        planEnd={{ ...refused, focus: { target: 'heading', serial: 3 } }}
        reauthentication={idleReauthentication()}
        sync={idleSyncState()}
      />,
    )
    expect(notice).toHaveFocus()
  })

  it('says each refusal by name, with no live region of its own', () => {
    for (const refusal of Object.keys(copy.refusals) as (keyof typeof copy.refusals)[]) {
      const { unmount } = render(
        <PlanEndControl
          bundle={bundle()}
          planEnd={idlePlanEnd({ notice: { kind: 'refused', refusal, reason: null } })}
          reauthentication={idleReauthentication()}
          sync={idleSyncState()}
        />,
      )
      expect(screen.getByText(copy.refusals[refusal].text)).toBeVisible()
      expect(screen.queryByRole('status')).not.toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      unmount()
    }
  })

  it('uses no forbidden wording, names buttons instead of placing them, and never calls the end a success or completion', () => {
    for (const text of planEndCopyStrings) {
      expect(text).not.toMatch(FORBIDDEN)
      expect(text).not.toMatch(/위의|아래의|위쪽|아래쪽|오른쪽|왼쪽/)
    }
    const { ended } = copy.status
    expect(ended('2026-10-03 10:00')).not.toContain('완료')
    expect(copy.dialog.distinct).toContain('자연 만료와 구분')
  })

  it('F17: the copy Story 2.13 added elsewhere uses no forbidden wording and promises no later feature', () => {
    const catchUp = planVerificationContent.catchUp
    const added = [
      planConditionContent.planEnded.label,
      planConditionContent.planEnded.text,
      planConditionContent.planEnded.dashboardLink,
      calculationEvidenceContent.versions.endedCurrent,
      nextActionCopy('PREPARE_NEXT_PLAN', '2026-10-03 10:00'),
      nextActionCopy('PREPARE_NEXT_PLAN', null),
      nextActionCopy('CHOOSE_CONTINUATION', null),
      axisActionCopy('CHOOSE_CONTINUATION'),
      planSyncContent.longGap.newPlanText,
      planSyncContent.longGap.newPlanAction,
      planSyncContent.longGap.newPlanUnavailableLabel,
      catchUp.inProgressOther,
      catchUp.inProgressElsewhere,
      ...Object.values(catchUp.inProgressUnfollowed),
      catchUp.admitting,
      catchUp.inProgress,
      copy.area.reasons.RESULT_TOO_RECENT ?? 'missing',
      copy.area.reasons.RESULT_UNAVAILABLE ?? 'missing',
      copy.endedNotShown.label,
      copy.endedNotShown.text,
      copy.uncertain.label,
      copy.uncertain.text,
    ]
    for (const text of added) {
      expect(text).not.toMatch(FORBIDDEN)
      expect(text).not.toMatch(/위의|아래의|위쪽|아래쪽|오른쪽|왼쪽/)
    }
    // Present facts only: nothing says a new plan "is not yet" possible.
    for (const text of [
      planConditionContent.planEnded.text,
      nextActionCopy('PREPARE_NEXT_PLAN', null),
      ...planEndCopyStrings,
    ]) {
      expect(text).not.toContain('아직')
    }
  })
})
