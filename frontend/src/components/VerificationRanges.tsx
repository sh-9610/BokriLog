import type { ReactElement } from 'react'

import type { VerificationRange } from '../session/useDashboard'

import { copyFor } from '../content/planResult'
import { planVerificationContent as copy } from '../content/planVerification'
import { TimeRange } from './EvidenceParts'

/**
 * The ranges a verification read again or reused (Story 2.12), one per line: its kind in words, then its two
 * bounds in Asia/Seoul with their machine values (the evidence's own time renderer). A kind this screen does not
 * know folds to safe copy. With no range, the `empty` sentence stands alone; a list the screen could not read
 * (`null`) says so in safe words rather than looking empty. The list carries the caller's class, so each place
 * styles it as its own.
 */
export function RangeList({
  ranges,
  empty,
  className,
  precise = false,
}: {
  readonly ranges: readonly VerificationRange[] | null
  readonly empty: string
  readonly className?: string
  readonly precise?: boolean
}): ReactElement {
  if (ranges === null) return <span>{copy.rangesUnreadable}</span>
  if (ranges.length === 0) return <span>{empty}</span>
  return (
    <ul className={className}>
      {ranges.map((range, index) => (
        <li key={`${range.kind}-${range.start}-${String(index)}`}>
          {copyFor(copy.rangeKinds, range.kind, copy.unknownRangeKind)}:{' '}
          <TimeRange end={range.end} precise={precise} start={range.start} />
        </li>
      ))}
    </ul>
  )
}
