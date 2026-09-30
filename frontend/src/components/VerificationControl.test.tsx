import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import type { VerificationStatus } from '../session/useDashboard'

import { axisActionCopy, nextActionCopy, planResultContent } from '../content/planResult'
import { planSyncContent } from '../content/planSync'
import {
  planVerificationContent as copy,
  planVerificationCopyStrings,
} from '../content/planVerification'
import { formatSeoulTime } from '../format/decimal'
import { parseVerificationStatus, UNKNOWN_VERIFICATION } from '../session/useDashboard'
import {
  AVAILABLE_VERIFICATION,
  idleSyncState,
  verificationFixture,
  verificationJobFixture,
} from '../test/planResultFixture'
import { getObservedStorageEntries } from '../test/setup'
import { VerificationControl } from './VerificationControl'

const FORBIDDEN =
  /실시간|정확|보장|성공|실패|축하|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/

function status(overrides: Record<string, unknown> = {}): VerificationStatus {
  const parsed = parseVerificationStatus(verificationFixture(overrides))
  // The fixture is whole: an unknown block would fold and hide a broken test.
  if (parsed === UNKNOWN_VERIFICATION) throw new Error('a malformed verification block')
  return parsed
}

function ended(fields: Record<string, unknown>): Record<string, unknown> {
  return verificationJobFixture({ finishedAt: '2026-09-27T01:10:40Z', ...fields })
}

function button(): HTMLElement {
  return screen.getByRole('button', { name: copy.action })
}

/** A summary or notice: a focus target without a role (tabIndex -1), found by its first line. */
function blockWith(label: string): HTMLElement {
  // eslint-disable-next-line testing-library/no-node-access -- 요약은 역할이 없는 초점 대상(tabIndex -1)이다. 첫 문장 요소의 부모로만 찾을 수 있다.
  const found = screen.getByText(label).parentElement
  if (found === null) throw new Error('no block')
  return found
}

describe('VerificationControl', () => {
  it('says what a verification is for, what it costs and what it would read, beside one secondary button', () => {
    const requestVerification = vi.fn()
    render(
      <VerificationControl
        status={status({
          preview: {
            rangeStart: '2026-09-20T01:00:00Z',
            unconfirmedFrom: '2026-09-20T01:00:00Z',
            anomalyCount: 1,
            reusedUntil: '2026-09-20T01:00:00Z',
          },
        })}
        sync={idleSyncState({ requestVerification })}
      />,
    )
    expect(screen.getByText(copy.purpose)).toBeInTheDocument()
    expect(screen.getByText(copy.cost)).toBeInTheDocument()
    expect(screen.getByText(copy.preview.label)).toBeInTheDocument()
    expect(screen.getByText(copy.preview.anomalyCount(1))).toBeInTheDocument()
    expect(screen.getByText(copy.preview.reusedHelp)).toBeInTheDocument()
    expect(
      screen.getAllByText(
        formatSeoulTime('2026-09-20T01:00:00Z', planResultContent.timeUnavailable),
      ),
    ).toHaveLength(3)
    expect(button()).toHaveAttribute('aria-disabled', 'false')
    expect(button()).not.toHaveAttribute('disabled')
    expect(button()).not.toHaveAttribute('aria-describedby')
    fireEvent.click(button())
    expect(requestVerification).toHaveBeenCalledTimes(1)
    expect(requestVerification).toHaveBeenCalledWith('PRECISION')
    // No live region of its own: the screen's status region speaks.
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 live region은 역할 조회로 보이지 않는다. 속성으로만 찾을 수 있다.
    expect(document.querySelector('[aria-live]')).toBeNull()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('with nothing to reuse, says so instead of a reuse end', () => {
    render(<VerificationControl status={AVAILABLE_VERIFICATION} sync={idleSyncState()} />)
    expect(screen.getByText(copy.preview.noReuse)).toBeInTheDocument()
    expect(screen.getByText(copy.preview.anomalyCount(0))).toBeInTheDocument()
  })

  it('P12: a suspected gap is the reason given in the expected range; a plain request gives none', () => {
    const { rerender } = render(
      <VerificationControl
        status={status({ suggestedReason: 'GAP_SUSPECTED' })}
        sync={idleSyncState()}
      />,
    )
    const reason = screen.getByText(copy.preview.gapSuspected)
    const label = screen.getByText(copy.preview.label)
    expect(label.compareDocumentPosition(reason) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    for (const suggestedReason of ['USER_REQUEST', null, 'SOMETHING_NEW']) {
      rerender(<VerificationControl status={status({ suggestedReason })} sync={idleSyncState()} />)
      expect(screen.queryByText(copy.preview.gapSuspected)).not.toBeInTheDocument()
    }
    expect(document.body.textContent).not.toContain('SOMETHING_NEW')
  })

  it('while running: 정밀 검증 중 with a decorative marker, the same button blocked and described by it', () => {
    const requestVerification = vi.fn()
    render(
      <VerificationControl
        status={status({ availability: 'IN_PROGRESS' })}
        sync={idleSyncState({
          phase: 'running',
          purpose: 'VERIFY',
          running: true,
          canVerify: false,
          canRequest: false,
          requestVerification,
        })}
      />,
    )
    const state = screen.getByText(copy.running, { exact: true })
    expect(state).toBeVisible()
    expect(screen.getByText(copy.runningHelp)).toBeInTheDocument()
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleName(copy.action)
    expect(button()).toHaveAccessibleDescription(copy.running)
    // eslint-disable-next-line testing-library/no-node-access -- 장식 표지는 역할도 이름도 없다. 속성으로만 찾을 수 있다.
    expect(state.parentElement?.querySelector('[aria-hidden="true"]')).not.toBeNull()
    fireEvent.click(button())
    expect(requestVerification).not.toHaveBeenCalled()
  })

  it('while a sync runs, the button is blocked and says another job is in progress', () => {
    render(
      <VerificationControl
        status={status({ availability: 'IN_PROGRESS' })}
        sync={idleSyncState({ phase: 'running', running: true, canVerify: false })}
      />,
    )
    expect(screen.queryByText(copy.running, { exact: true })).not.toBeInTheDocument()
    expect(button()).toHaveAccessibleDescription(copy.inProgressOther)
  })

  it('counts the cool-down down and blocks the button meanwhile', () => {
    const requestVerification = vi.fn()
    render(
      <VerificationControl
        status={status({ availability: 'COOLDOWN', retryAfterSeconds: 12 })}
        sync={idleSyncState({
          verificationCooldownSeconds: 12,
          canVerify: false,
          requestVerification,
        })}
      />,
    )
    expect(screen.getByText(copy.cooldown(12))).toBeInTheDocument()
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleDescription(copy.cooldown(12))
    fireEvent.click(button())
    expect(requestVerification).not.toHaveBeenCalled()
  })

  it.each([
    ['RESTRICTED', copy.reasons.RESTRICTED],
    ['CONNECTION_UNAVAILABLE', copy.reasons.CONNECTION_UNAVAILABLE],
    ['PLAN_PERIOD_ENDED', copy.reasons.PLAN_PERIOD_ENDED],
    ['SOMETHING_NEW', copy.unknownReason],
  ])('unavailable (%s): blocked, with the reason beside it', (reason, text) => {
    render(
      <VerificationControl
        status={status({ availability: 'UNAVAILABLE', reason, preview: null })}
        sync={idleSyncState({ canVerify: false })}
      />,
    )
    const line = `${copy.unavailableLabel}: ${text}`
    expect(screen.getByText(line)).toBeInTheDocument()
    expect(button()).toHaveAccessibleDescription(line)
    expect(document.body.textContent).not.toContain('SOMETHING_NEW')
  })

  it('a block it could not read blocks the button with a safe reason', () => {
    render(
      <VerificationControl
        status={UNKNOWN_VERIFICATION}
        sync={idleSyncState({ canVerify: false })}
      />,
    )
    expect(button()).toHaveAttribute('aria-disabled', 'true')
    expect(button()).toHaveAccessibleDescription(`${copy.unavailableLabel}: ${copy.unknownReason}`)
  })

  it('P20: after a long gap, one short sentence pointing to the catch-up by name — no purpose, cost, range or button', () => {
    render(
      <VerificationControl
        status={status({
          availability: 'LONG_GAP',
          preview: null,
          catchUpFrom: '2026-08-20T01:00:00Z',
          retryAfterSeconds: 10,
        })}
        sync={idleSyncState({ canVerify: false, verificationCooldownSeconds: 10 })}
      />,
    )
    expect(screen.getByText(copy.longGap.continuation)).toBeVisible()
    expect(copy.longGap.continuation).toContain(planSyncContent.longGap.continueAction)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText(copy.purpose)).not.toBeInTheDocument()
    expect(screen.queryByText(copy.cost)).not.toBeInTheDocument()
    // The cool-down belongs to the catch-up then: the sync control says it.
    expect(screen.queryByText(copy.cooldown(10))).not.toBeInTheDocument()
    expect(screen.queryByText(copy.preview.label)).not.toBeInTheDocument()
    // No position words: the catch-up is named, not placed.
    expect(copy.longGap.continuation).not.toMatch(/위의|아래의|위쪽|아래쪽/)
  })

  it('C3: a long gap whose basis a review holds says only that a verification is not taken', () => {
    render(
      <VerificationControl
        status={status({
          availability: 'NOT_USEFUL',
          preview: null,
          catchUpFrom: '2026-08-20T01:00:00Z',
          latest: ended({
            status: 'NEEDS_REVIEW',
            failureCode: 'SOURCE_EVENT_MISSING',
            holdsResult: true,
          }),
        })}
        sync={idleSyncState({ canVerify: false })}
      />,
    )
    expect(screen.getByText(copy.longGap.held)).toBeVisible()
    // Neither the catch-up (it cannot help either) nor the notice the verification control keeps outside a gap.
    expect(screen.queryByText(copy.longGap.continuation)).not.toBeInTheDocument()
    expect(screen.queryByText(copy.notUseful.label)).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('a verification request met by a long gap keeps its own summary beside the short sentence', () => {
    render(
      <VerificationControl
        status={status({
          availability: 'LONG_GAP',
          preview: null,
          catchUpFrom: '2026-08-20T01:00:00Z',
        })}
        sync={idleSyncState({ phase: 'error', purpose: 'VERIFY', canVerify: false })}
      />,
    )
    expect(blockWith(copy.local.error.label)).toHaveAttribute('tabindex', '-1')
  })

  it('when asking again cannot help: no button, the notice in its place, and the latest review', () => {
    render(
      <VerificationControl
        status={status({
          availability: 'NOT_USEFUL',
          latest: ended({
            status: 'NEEDS_REVIEW',
            failureCode: 'SOURCE_EVENT_MISSING',
            stage: 'COLLECTION',
            counts: { new: 0, same: 2, missing: 1, superseded: 0, conflict: 0 },
          }),
        })}
        sync={idleSyncState({ canVerify: false })}
      />,
    )
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    const notice = blockWith(copy.notUseful.label)
    expect(notice).toHaveTextContent(copy.notUseful.text)
    expect(notice).toHaveAttribute('tabindex', '-1')
    expect(screen.queryByText(copy.preview.label)).not.toBeInTheDocument()
    const summary = blockWith(
      `${copy.latest.heading}: ${copy.latest.statuses.NEEDS_REVIEW ?? 'missing'}`,
    )
    expect(summary).toHaveAttribute('tabindex', '-1')
    expect(summary).toHaveTextContent(
      `${copy.latest.causeLabel}: ${copy.latest.codes.SOURCE_EVENT_MISSING ?? 'missing'}`,
    )
    expect(summary).toHaveTextContent(copy.latest.kept)
    expect(summary).toHaveTextContent(`${copy.latest.counts.missing} ${copy.latest.countValue(1)}`)
    expect(summary).toHaveTextContent(copy.rangeKinds.UNCONFIRMED ?? 'missing')
  })

  it('the latest verification that published is a plain record: ranges, reuse, counts and time', () => {
    render(
      <VerificationControl
        status={status({
          latest: ended({
            status: 'SUCCEEDED',
            reason: 'GAP_SUSPECTED',
            reused: [
              { kind: 'REUSED', start: '2026-09-25T00:00:00Z', end: '2026-09-25T12:00:00Z' },
            ],
            counts: { new: 1, same: 3, missing: 0, superseded: 1, conflict: 0 },
          }),
        })}
        sync={idleSyncState()}
      />,
    )
    const record = blockWith(
      `${copy.latest.heading}: ${copy.latest.statuses.SUCCEEDED ?? 'missing'}`,
    )
    expect(record).not.toHaveAttribute('tabindex')
    expect(record).toHaveTextContent(copy.jobReasons.GAP_SUSPECTED ?? 'missing')
    expect(record).toHaveTextContent(copy.rangeKinds.REUSED ?? 'missing')
    expect(record).toHaveTextContent(`${copy.latest.counts.new} ${copy.latest.countValue(1)}`)
    expect(record).toHaveTextContent(
      `${copy.latest.counts.superseded} ${copy.latest.countValue(1)}`,
    )
    expect(record).toHaveTextContent(
      formatSeoulTime('2026-09-27T01:10:40Z', planResultContent.timeUnavailable),
    )
    expect(button()).toHaveAttribute('aria-disabled', 'false')
  })

  it('P2: a verification that could be asked again says so in its own words, never "sync again"', () => {
    render(
      <VerificationControl
        status={status({
          latest: ended({
            status: 'FAILED',
            failureCode: 'PROVIDER_UNAVAILABLE',
            stage: 'COLLECTION',
          }),
        })}
        sync={idleSyncState()}
      />,
    )
    const summary = blockWith(`${copy.latest.heading}: ${copy.latest.statuses.FAILED ?? 'missing'}`)
    expect(summary).toHaveTextContent(copy.latest.codes.PROVIDER_UNAVAILABLE ?? 'missing')
    expect(summary).toHaveTextContent('다시 정밀 검증을 요청할 수 있습니다')
    expect(summary).toHaveTextContent(
      `${copy.latest.stageLabel}: ${copy.latest.stages.COLLECTION ?? 'missing'}`,
    )
    expect(summary.textContent).not.toMatch(/동기화할 수 있습니다|다시 동기화/)
    for (const text of [
      ...Object.values(copy.latest.codes),
      ...Object.values(copy.catchUp.summary.codes),
    ]) {
      expect(text).not.toMatch(/다시 동기화/)
    }
    // A cause that asking again cannot change never invites asking again.
    expect(copy.latest.codes.SOURCE_EVENT_MISSING).not.toContain('요청할 수 있습니다')
  })

  it('P23: a pressed verification that ended FAILED or NEEDS_REVIEW moves focus to its summary', () => {
    for (const jobStatus of ['FAILED', 'NEEDS_REVIEW'] as const) {
      const latest = ended({
        status: jobStatus,
        failureCode: jobStatus === 'FAILED' ? 'SYNC_FAILED' : 'SOURCE_REQUIRES_REVIEW',
      })
      const { rerender, unmount } = render(
        <VerificationControl
          status={status({ latest })}
          sync={idleSyncState({ phase: 'running', purpose: 'VERIFY', running: true })}
        />,
      )
      rerender(
        <VerificationControl
          status={status({ latest })}
          sync={idleSyncState({
            phase: jobStatus === 'FAILED' ? 'failed' : 'review',
            purpose: 'VERIFY',
            focus: { target: 'verification', serial: 1 },
          })}
        />,
      )
      expect(
        blockWith(`${copy.latest.heading}: ${copy.latest.statuses[jobStatus] ?? 'missing'}`),
      ).toHaveFocus()
      unmount()
    }
  })

  it('P10: ranges it could not read are said in safe words, never as an empty list', () => {
    render(
      <VerificationControl
        status={status({
          latest: ended({ status: 'SUCCEEDED', targets: [{ kind: 'NEW', start: 'x', end: 'y' }] }),
        })}
        sync={idleSyncState()}
      />,
    )
    const record = blockWith(
      `${copy.latest.heading}: ${copy.latest.statuses.SUCCEEDED ?? 'missing'}`,
    )
    expect(record).toHaveTextContent(`${copy.latest.targetsLabel}${copy.rangesUnreadable}`)
    expect(record).toHaveTextContent(`${copy.latest.reusedLabel}${copy.none}`)
  })

  it('a verification that ended out of range names its targets as ranges to check, never as ranges read again', () => {
    const targets = [
      { kind: 'ANOMALY', start: '2024-01-01T00:00:00Z', end: '2024-01-02T00:00:00Z' },
    ]
    const { rerender } = render(
      <VerificationControl
        status={status({
          availability: 'NOT_USEFUL',
          latest: ended({
            status: 'NEEDS_REVIEW',
            failureCode: 'HISTORY_OUT_OF_RANGE',
            stage: 'COLLECTION',
            targets,
            holdsResult: true,
          }),
        })}
        sync={idleSyncState({ canVerify: false })}
      />,
    )
    const review = blockWith(
      `${copy.latest.heading}: ${copy.latest.statuses.NEEDS_REVIEW ?? 'missing'}`,
    )
    expect(review).toHaveTextContent(
      `${copy.latest.plannedTargetsLabel}${copy.rangeKinds.ANOMALY ?? 'missing'}`,
    )
    expect(review).not.toHaveTextContent(copy.latest.targetsLabel)
    expect(copy.latest.plannedTargetsLabel).not.toMatch(/읽은/)
    // Its reuse was only planned too: no result was built on that evidence.
    expect(review).toHaveTextContent(copy.latest.plannedReusedLabel)
    expect(review).not.toHaveTextContent(copy.latest.reusedLabel)
    // Any ending without a new official result says the same: a failure can stop part way.
    rerender(
      <VerificationControl
        status={status({
          latest: ended({ status: 'FAILED', failureCode: 'PROVIDER_UNAVAILABLE', targets }),
        })}
        sync={idleSyncState()}
      />,
    )
    expect(
      blockWith(`${copy.latest.heading}: ${copy.latest.statuses.FAILED ?? 'missing'}`),
    ).toHaveTextContent(copy.latest.plannedTargetsLabel)
    expect(screen.queryByText(copy.latest.targetsLabel)).not.toBeInTheDocument()
    // One that published did read them again.
    rerender(
      <VerificationControl
        status={status({ latest: ended({ status: 'SUCCEEDED', targets }) })}
        sync={idleSyncState()}
      />,
    )
    expect(
      blockWith(`${copy.latest.heading}: ${copy.latest.statuses.SUCCEEDED ?? 'missing'}`),
    ).toHaveTextContent(`${copy.latest.targetsLabel}${copy.rangeKinds.ANOMALY ?? 'missing'}`)
    expect(screen.queryByText(copy.latest.plannedTargetsLabel)).not.toBeInTheDocument()
    expect(screen.getByText(copy.latest.reusedLabel)).toBeInTheDocument()
    expect(screen.queryByText(copy.latest.plannedReusedLabel)).not.toBeInTheDocument()
  })

  it('P20: only a precision verification is shown as the latest one — never a job of another or an unknown purpose', () => {
    for (const purpose of ['SOMETHING_NEW', 'SYNC', 'CATCH_UP']) {
      const { unmount } = render(
        <VerificationControl
          status={status({
            latest: ended({ purpose, status: 'FAILED', failureCode: 'SYNC_FAILED' }),
          })}
          sync={idleSyncState()}
        />,
      )
      expect(screen.queryByText(new RegExp(copy.latest.heading))).not.toBeInTheDocument()
      expect(document.body.textContent).not.toContain(copy.unknownPurpose)
      unmount()
    }
  })

  it('folds unknown codes and never shows them verbatim; a catch-up job is not its to show', () => {
    const { rerender } = render(
      <VerificationControl
        status={status({
          latest: ended({
            status: 'FAILED',
            failureCode: 'NEW_CODE',
            stage: 'NEW_STAGE',
            reason: 'NEW_REASON',
            targets: [
              { kind: 'NEW_KIND', start: '2026-09-25T00:00:00Z', end: '2026-09-26T00:00:00Z' },
            ],
          }),
        })}
        sync={idleSyncState()}
      />,
    )
    expect(document.body).toHaveTextContent(copy.latest.unknownCode)
    expect(document.body).toHaveTextContent(copy.latest.unknownStage)
    // The verification's own fallbacks, not the sync's.
    expect(document.body).not.toHaveTextContent(planSyncContent.summary.unknownCode)
    expect(document.body).toHaveTextContent(copy.unknownJobReason)
    expect(document.body).toHaveTextContent(copy.unknownRangeKind)
    expect(document.body.textContent).not.toMatch(/NEW_CODE|NEW_STAGE|NEW_REASON|NEW_KIND/)
    rerender(
      <VerificationControl
        status={status({
          latest: ended({ purpose: 'CATCH_UP', status: 'FAILED', failureCode: 'SYNC_FAILED' }),
        })}
        sync={idleSyncState()}
      />,
    )
    expect(screen.queryByText(new RegExp(copy.latest.heading))).not.toBeInTheDocument()
  })

  it.each([
    'cooldown',
    'unavailable',
    'error',
    'stalled',
    'notUseful',
    'stale',
    'busy',
    'refused',
  ] as const)('a verification request that ended as %s has a summary of its own', (phase) => {
    render(
      <VerificationControl
        status={AVAILABLE_VERIFICATION}
        sync={idleSyncState({ phase, purpose: 'VERIFY' })}
      />,
    )
    const summary = blockWith(copy.local[phase].label)
    expect(summary).toHaveTextContent(copy.local[phase].text)
    expect(summary).toHaveAttribute('tabindex', '-1')
  })

  it("a sync's outcome is not the verification's to say", () => {
    render(
      <VerificationControl
        status={AVAILABLE_VERIFICATION}
        sync={idleSyncState({ phase: 'error', purpose: 'SYNC' })}
      />,
    )
    expect(screen.queryByText(copy.local.error.label)).not.toBeInTheDocument()
  })

  it('never shows the same name twice: the server reason and the notice replace the refusal summaries', () => {
    const { rerender } = render(
      <VerificationControl
        status={status({ availability: 'UNAVAILABLE', reason: 'RESTRICTED' })}
        sync={idleSyncState({ phase: 'unavailable', purpose: 'VERIFY', canVerify: false })}
      />,
    )
    expect(screen.queryByText(copy.local.unavailable.text)).not.toBeInTheDocument()
    rerender(
      <VerificationControl
        status={status({ availability: 'NOT_USEFUL' })}
        sync={idleSyncState({ phase: 'notUseful', purpose: 'VERIFY', canVerify: false })}
      />,
    )
    expect(screen.queryByText(copy.local.notUseful.text)).not.toBeInTheDocument()
    expect(screen.getByText(copy.notUseful.text)).toBeInTheDocument()
  })

  it('moves focus to its summary when asked, once, and leaves other targets alone', () => {
    const { rerender } = render(
      <VerificationControl
        status={AVAILABLE_VERIFICATION}
        sync={idleSyncState({ phase: 'error', purpose: 'VERIFY' })}
      />,
    )
    button().focus()
    rerender(
      <VerificationControl
        status={AVAILABLE_VERIFICATION}
        sync={idleSyncState({
          phase: 'error',
          purpose: 'VERIFY',
          focus: { target: 'summary', serial: 1 },
        })}
      />,
    )
    expect(button()).toHaveFocus()
    rerender(
      <VerificationControl
        status={AVAILABLE_VERIFICATION}
        sync={idleSyncState({
          phase: 'error',
          purpose: 'VERIFY',
          focus: { target: 'verification', serial: 2 },
        })}
      />,
    )
    expect(blockWith(copy.local.error.label)).toHaveFocus()
    button().focus()
    rerender(
      <VerificationControl
        status={AVAILABLE_VERIFICATION}
        sync={idleSyncState({
          phase: 'error',
          purpose: 'VERIFY',
          focus: { target: 'verification', serial: 2 },
        })}
      />,
    )
    expect(button()).toHaveFocus()
  })

  it('a request made before it was mounted is not replayed', () => {
    render(
      <VerificationControl
        status={AVAILABLE_VERIFICATION}
        sync={idleSyncState({
          phase: 'error',
          purpose: 'VERIFY',
          focus: { target: 'verification', serial: 4 },
        })}
      />,
    )
    expect(blockWith(copy.local.error.label)).not.toHaveFocus()
  })

  it('moves focus to the notice when the button that held it goes away', () => {
    const { rerender } = render(
      <VerificationControl status={AVAILABLE_VERIFICATION} sync={idleSyncState()} />,
    )
    button().focus()
    rerender(
      <VerificationControl
        status={status({ availability: 'NOT_USEFUL' })}
        sync={idleSyncState({ canVerify: false })}
      />,
    )
    expect(blockWith(copy.notUseful.label)).toHaveFocus()
  })

  it('uses no forbidden wording, and never claims the whole history was read again', () => {
    for (const text of planVerificationCopyStrings) {
      expect(text).not.toMatch(FORBIDDEN)
      expect(text).not.toMatch(/전체 이력을 다시 읽었|전체 이력을 모두/)
    }
    for (const text of [
      planResultContent.axes.freshness.causes.LAST_VERIFICATION_NEEDS_REVIEW,
      planResultContent.axes.reliability.causes.VERIFICATION_REVIEW_REQUIRED,
      planResultContent.axes.reliability.causes.HISTORY_OUT_OF_RANGE,
      planSyncContent.summary.codes.SOURCE_EVENT_MISSING,
      planSyncContent.summary.codes.HISTORY_OUT_OF_RANGE,
      planSyncContent.summary.stages.FINAL_SWEEP,
      planSyncContent.inProgressOther,
      ...Object.values(planSyncContent.longGap),
      nextActionCopy('VERIFY', '2026-09-29 10:00'),
      nextActionCopy('VERIFY', null),
      axisActionCopy('VERIFY'),
      nextActionCopy('CHOOSE_CONTINUATION', null),
      axisActionCopy('CHOOSE_CONTINUATION'),
    ]) {
      expect(text).toBeDefined()
      expect(text).not.toMatch(FORBIDDEN)
    }
    // The purpose says only the needed ranges are read, and that the whole history is not.
    expect(copy.purpose).toContain('필요한 구간만 다시 읽어')
    expect(copy.cost).toContain('수십 초 이상')
    expect(copy.notUseful.label).toContain('다시 요청해도 바뀌지 않습니다')
    // P11: the control's notice and the reliability axis's notices are different sentences.
    for (const notice of [copy.held.notice, ...Object.values(copy.held.notices)]) {
      expect(notice).not.toBe(copy.notUseful.text)
    }
    // A hold that a catch-up caused does not tell the user to ask for a precision verification again.
    expect(copy.held.notices.CATCH_UP).not.toContain(copy.action)
    // P13: buttons are named, never placed, and may be blocked.
    for (const text of [
      nextActionCopy('VERIFY', null),
      axisActionCopy('VERIFY'),
      nextActionCopy('CHOOSE_CONTINUATION', null),
      axisActionCopy('CHOOSE_CONTINUATION'),
      copy.longGap.continuation,
    ]) {
      expect(text).not.toMatch(/위의|아래의|위쪽|아래쪽|오른쪽|왼쪽/)
      expect(text).toContain('지금 요청할 수 없으면')
    }
  })
})
