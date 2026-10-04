import type { ReactElement } from 'react'

import type { PlanEndRecord as EndRecord, PlanResult } from '../session/useDashboard'

import { formatDelay, planEndContent } from '../content/planEnd'
import { copyFor, planResultContent } from '../content/planResult'
import { formatAmount } from '../format/decimal'
import { Time, TimeRange } from './EvidenceParts'
// A part of the result panel: it takes the panel's own groups and fact lists.
import styles from './PlanResultPanel.module.css'

const copy = planEndContent.record

/**
 * The record of a plan ended early (Story 2.13), in place of the freshness axis in the panel's record mode: the
 * `중도 종료` marker, why it ended, the actual end time the server decided, the scheduled end `T`, the last normal
 * value with its measurement time and how long before the end that was (the server's milliseconds), the completion
 * comparison and — after a long gap — the range that was never collected. Codes this screen does not know are said
 * in safe words. Nothing here is estimated at the end time, and nothing judges the plan's goal; the end is never
 * called a success, a failure or a completion.
 */
export function PlanEndRecord({
  end,
  result,
}: {
  readonly end: EndRecord
  readonly result: PlanResult
}): ReactElement {
  // The current value is always defined and official (`isPlanResult`): the last normal value the plan ended on.
  const current = result.metrics[0]?.value
  return (
    <section aria-labelledby={copy.headingId} className={styles.group} id={copy.id}>
      <h3 id={copy.headingId}>{copy.heading}</h3>
      <p className={styles.marker}>
        {copy.statusLabel}: {copy.marker}
      </p>
      <dl className={styles.facts}>
        <div>
          <dt>{copy.reasonLabel}</dt>
          <dd>{copyFor(copy.reasons, end.reason, copy.unknownReason)}</dd>
        </div>
        <div>
          <dt>{copy.endedAt}</dt>
          <dd>
            <Time precise value={end.endedAt} />
            <span className={styles.factHelp}>{copy.endedAtHelp}</span>
          </dd>
        </div>
        <div>
          <dt>{copy.scheduledEndAt}</dt>
          <dd>
            <Time value={end.scheduledEndAt} />
          </dd>
        </div>
        <div>
          <dt>{copy.lastNormalValue}</dt>
          <dd className={styles.number}>
            {current === undefined
              ? planResultContent.notComputable
              : `${formatAmount(current)} USD`}
          </dd>
        </div>
        <div>
          <dt>{copy.lastNormalMeasuredAt}</dt>
          <dd>
            <Time value={end.lastNormalMeasuredAt} />
          </dd>
        </div>
        <div>
          <dt>{copy.delay}</dt>
          <dd>
            {formatDelay(end.lastNormalDelayMillis)}
            <span className={styles.factHelp}>{copy.delayHelp}</span>
          </dd>
        </div>
        <div>
          <dt>{copy.completion}</dt>
          <dd>{copyFor(copy.completions, end.completion, copy.unknownCompletion)}</dd>
        </div>
        {end.uncollectedFrom === null || end.uncollectedUntil === null ? null : (
          <div>
            <dt>{copy.uncollected}</dt>
            <dd>
              <TimeRange end={end.uncollectedUntil} start={end.uncollectedFrom} />
              <span className={styles.factHelp}>{copy.uncollectedHelp}</span>
            </dd>
          </div>
        )}
      </dl>
      <p className={styles.meta}>{copy.readOnly}</p>
      <p className={styles.meta}>{planEndContent.dialog.distinct}</p>
    </section>
  )
}
