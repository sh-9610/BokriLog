import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { planResultContent } from '../content/planResult'
import { planVerificationContent } from '../content/planVerification'
import { formatSeoulTime } from '../format/decimal'
import { parseVersionChange } from '../session/useDashboard'
import { changeFixture } from '../test/planResultFixture'
import { VERSION_CHANGE_NOTE_ID, VersionChangeNote } from './VersionChangeNote'

const copy = planVerificationContent.change
const LATE_AT = '2026-09-25T00:00:01Z'

describe('VersionChangeNote', () => {
  it('a corrected version says how many late cash flows it applied and where their impact starts', () => {
    const { container } = render(
      <VersionChangeNote
        change={parseVersionChange(
          changeFixture({
            kind: 'CORRECTED',
            purpose: 'VERIFY',
            reason: 'GAP_SUSPECTED',
            lateChangeCount: 1,
            firstAffectedAt: LATE_AT,
            targets: [
              { kind: 'ANOMALY', start: '2026-09-25T00:00:00Z', end: '2026-09-26T00:00:01Z' },
            ],
          }),
        )}
      />,
    )
    const start = formatSeoulTime(LATE_AT, planResultContent.timeUnavailable)
    expect(
      screen.getByText(
        (_, element) =>
          element?.tagName === 'P' &&
          element.textContent ===
            `${copy.corrected(1)}, ${copy.impactStart} ${start} · ${planResultContent.timeZone}`,
      ),
    ).toBeInTheDocument()
    // eslint-disable-next-line testing-library/no-node-access -- 시각의 기계값은 `time` 요소의 속성으로만 읽을 수 있다.
    expect(document.querySelector(`time[datetime="${LATE_AT}"]`)).not.toBeNull()
    expect(screen.getByText(copy.correctedHelp)).toBeInTheDocument()
    expect(
      screen.getByText(planVerificationContent.jobReasons.GAP_SUSPECTED ?? 'missing'),
    ).toBeVisible()
    expect(container).toHaveTextContent(planVerificationContent.rangeKinds.ANOMALY ?? 'missing')
    expect(container).toHaveTextContent(`${copy.reusedLabel}${planVerificationContent.none}`)
    // A named group: its first line is its name, so assistive technology can find and name the marker.
    const group = screen.getByRole('group', {
      name: `${copy.corrected(1)}, ${copy.impactStart} ${start} · ${planResultContent.timeZone}`,
    })
    expect(group).toHaveAttribute('id', VERSION_CHANGE_NOTE_ID)
    expect(group).toHaveTextContent(copy.correctedHelp)
  })

  it('a verification with no change says 필요 구간 검증 완료 with the ranges read again, the reuse and the time', () => {
    const { container } = render(
      <VersionChangeNote
        change={parseVersionChange(
          changeFixture({
            kind: 'VERIFIED_NO_CHANGE',
            purpose: 'VERIFY',
            reason: 'USER_REQUEST',
            verifiedAt: '2026-09-27T01:10:40Z',
            targets: [
              { kind: 'UNCONFIRMED', start: '2026-09-20T01:00:00Z', end: '2026-09-27T01:00:00Z' },
              { kind: 'NEW', start: '2026-09-27T01:00:00Z', end: '2026-09-27T01:10:39Z' },
            ],
            reused: [
              { kind: 'REUSED', start: '2026-09-01T00:00:00Z', end: '2026-09-20T01:00:00Z' },
            ],
          }),
        )}
      />,
    )
    expect(screen.getByRole('group', { name: copy.verified })).toHaveTextContent(copy.verifiedHelp)
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
    expect(container).toHaveTextContent(planVerificationContent.rangeKinds.REUSED ?? 'missing')
    expect(container).toHaveTextContent(
      `${copy.verifiedAtLabel}: ${formatSeoulTime('2026-09-27T01:10:40Z', planResultContent.timeUnavailable)}`,
    )
    // Never the claim that the whole history was read again, and nothing judging the values.
    expect(container.textContent).not.toMatch(
      /전체 이력을 다시 읽었|실시간|정확|보장|성공|실패|축하|손실|추천합니다|스트릭|배지/,
    )
  })

  it('shows nothing for a version without a late change, or a marker it cannot vouch for', () => {
    const { container, rerender } = render(
      <VersionChangeNote change={parseVersionChange(changeFixture())} />,
    )
    expect(container).toBeEmptyDOMElement()
    rerender(<VersionChangeNote change={parseVersionChange({ kind: 'SOMETHING_NEW' })} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('folds an unknown purpose and reason to safe copy, and a correction without a start names none', () => {
    const { container } = render(
      <VersionChangeNote
        change={parseVersionChange(
          changeFixture({
            kind: 'CORRECTED',
            purpose: 'NEW_PURPOSE',
            reason: 'NEW_REASON',
            lateChangeCount: 2,
          }),
        )}
      />,
    )
    expect(screen.getByText(copy.corrected(2))).toBeInTheDocument()
    expect(container).not.toHaveTextContent(copy.impactStart)
    expect(container).toHaveTextContent(planVerificationContent.unknownPurpose)
    expect(container).toHaveTextContent(planVerificationContent.unknownJobReason)
    expect(container.textContent).not.toMatch(/NEW_PURPOSE|NEW_REASON/)
  })
})
