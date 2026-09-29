import type { ReactElement } from 'react'

import type { AdjustmentHistoryState } from '../session/useAdjustmentHistory'
import type { EvidenceState } from '../session/useCalculationEvidence'
import type { DashboardSync } from '../session/useDashboardSync'

import { adjustmentAnnouncement } from '../session/useAdjustmentHistory'
import { evidenceAnnouncement } from '../session/useCalculationEvidence'
import { dashboardAnnouncement, type Dashboard } from '../session/useDashboard'

/**
 * The sentence a landing screen's single status region reads while its session is ready: the dashboard's,
 * then — while the calculation evidence region is open (Story 2.9) — what the evidence read did, then —
 * while the adjustment history region is open (Story 2.10) — what the history read did. Both landing
 * screens render it the same way.
 *
 * The dashboard's part knows about the sync (Story 2.11): while a sync starts, runs and ends, that one part
 * says so — once when it starts and once when it ends, never "reading again" while it polls. A sync that ends
 * with a new bundle changes only that part, so the new result and the sync's end are one sentence.
 *
 * Three elements, in a status region marked `aria-atomic="false"` (a `role="status"` region is atomic by
 * default): a change to one part is announced without the others being read again.
 */
export function DashboardStatusText({
  dashboard,
  evidence,
  adjustments,
  sync,
  className,
}: {
  readonly dashboard: Dashboard
  readonly evidence: EvidenceState
  readonly adjustments: AdjustmentHistoryState
  readonly sync: DashboardSync
  readonly className?: string
}): ReactElement {
  const line = evidenceAnnouncement(evidence.view)
  const history = adjustmentAnnouncement(adjustments.view)
  return (
    <p className={className}>
      <span>{dashboardAnnouncement(dashboard, sync.phase)}</span>
      {line === null ? null : (
        <>
          {' '}
          <span>{line}</span>
        </>
      )}
      {history === null ? null : (
        <>
          {' '}
          <span>{history}</span>
        </>
      )}
    </p>
  )
}
