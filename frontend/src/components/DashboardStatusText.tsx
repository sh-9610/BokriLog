import type { ReactElement } from 'react'

import type { AdjustmentHistoryState } from '../session/useAdjustmentHistory'
import type { EvidenceState } from '../session/useCalculationEvidence'
import type { DashboardSync } from '../session/useDashboardSync'

import { adjustmentAnnouncement } from '../session/useAdjustmentHistory'
import { evidenceAnnouncement } from '../session/useCalculationEvidence'
import { dashboardAnnouncement, type Dashboard } from '../session/useDashboard'
import { syncAnnouncement } from '../session/useDashboardSync'

/**
 * The sentence a landing screen's single status region reads while its session is ready: the dashboard's,
 * then — while the calculation evidence region is open (Story 2.9) — what the evidence read did, then —
 * while the adjustment history region is open (Story 2.10) — what the history read did, then — once the
 * incremental sync has something to say (Story 2.11) — what the sync did. Both landing screens render it the
 * same way.
 *
 * Four elements, in a status region marked `aria-atomic="false"` (a `role="status"` region is atomic by
 * default): a change to one part is announced without the others being read again. The sync's sentence
 * changes once per change of the sync, and the dashboard's stays still through the sync's quiet reads.
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
  readonly sync?: DashboardSync
  readonly className?: string
}): ReactElement {
  const line = evidenceAnnouncement(evidence.view)
  const history = adjustmentAnnouncement(adjustments.view)
  const synced = sync === undefined ? null : syncAnnouncement(sync)
  return (
    <p className={className}>
      <span>{dashboardAnnouncement(dashboard)}</span>
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
      {synced === null ? null : (
        <>
          {' '}
          <span>{synced}</span>
        </>
      )}
    </p>
  )
}
