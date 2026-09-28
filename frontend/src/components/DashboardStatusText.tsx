import type { ReactElement } from 'react'

import type { EvidenceState } from '../session/useCalculationEvidence'

import { evidenceAnnouncement } from '../session/useCalculationEvidence'
import { dashboardAnnouncement, type Dashboard } from '../session/useDashboard'

/**
 * The sentence a landing screen's single status region reads while its session is ready: the dashboard's,
 * then — while the calculation evidence region is open (Story 2.9) — what the evidence read did. Both
 * landing screens render it the same way.
 *
 * Two elements, in a status region marked `aria-atomic="false"` (a `role="status"` region is atomic by
 * default): a change to the evidence part is announced without the dashboard sentence being read again.
 */
export function DashboardStatusText({
  dashboard,
  evidence,
  className,
}: {
  readonly dashboard: Dashboard
  readonly evidence: EvidenceState
  readonly className?: string
}): ReactElement {
  const line = evidenceAnnouncement(evidence.view)
  return (
    <p className={className}>
      <span>{dashboardAnnouncement(dashboard)}</span>
      {line === null ? null : (
        <>
          {' '}
          <span>{line}</span>
        </>
      )}
    </p>
  )
}
