import type { ReactElement } from 'react'

import type {
  PlanCompletionRecord,
  SyncStatus,
  PostEndValue as Value,
} from '../session/useDashboard'
import type { DashboardSync } from '../session/useDashboardSync'

import { planCompletionContent } from '../content/planCompletion'
import { formatAmount } from '../format/decimal'
import { UNKNOWN_VERIFICATION } from '../session/useDashboard'
import { Time } from './EvidenceParts'
// A part of the result panel: it takes the panel's own groups and fact lists.
import panel from './PlanResultPanel.module.css'
import styles from './PostEndValue.module.css'
import { SyncControl } from './SyncControl'

const copy = planCompletionContent.postEnd

/**
 * `종료 후 현재가치` (Story 2.14): the current value later syncs measured after a completed plan's first observation,
 * with its measurement time, and the sync control that asks for it. Before the first observation the same control
 * asks for that observation, which the completion result then fixes. Its own region with its own heading, apart from
 * the completion result: a sync here never changes the completion. When no later value exists, it says the latest value
 * is the first observation — by the completion result's name, never by its place.
 *
 * The sync control is the dashboard's own (Story 2.11), with its busy, cool-down and unavailable lines and its error
 * summary; a completed plan has no precision verification, no long gap and no end to ask for.
 */
export function PostEndValue({
  completion,
  postEnd,
  unreadable,
  status,
  sync,
  adExpiresAt,
}: {
  readonly completion: PlanCompletionRecord
  readonly postEnd: Value | null
  /** The server sent a later value this screen could not vouch for: said as not read, never as none. */
  readonly unreadable: boolean
  readonly status: SyncStatus
  readonly adExpiresAt?: number | null
  readonly sync: DashboardSync
}): ReactElement {
  return (
    <section aria-labelledby={copy.headingId} className={styles.region} id={copy.id}>
      <h3 id={copy.headingId}>{copy.heading}</h3>
      <p className={panel.meta}>{copy.intro}</p>
      {postEnd === null ? (
        <p>
          {unreadable
            ? copy.unreadable
            : completion.observedValue === null
              ? copy.notObserved
              : copy.sameAsObservation}
        </p>
      ) : (
        <dl className={panel.facts}>
          <div>
            <dt>{copy.value}</dt>
            <dd className={panel.number}>{formatAmount(postEnd.value)} USD</dd>
          </div>
          <div>
            <dt>{copy.measuredAt}</dt>
            <dd>
              <Time precise value={postEnd.measuredAt} />
            </dd>
          </div>
        </dl>
      )}
      <SyncControl
        adExpiresAt={adExpiresAt}
        status={status}
        sync={sync}
        verification={UNKNOWN_VERIFICATION}
      />
    </section>
  )
}
