import type { ReactElement } from 'react'

import type { PlanCompletionRecord } from '../session/useDashboard'

import { comparisonName, planCompletionContent } from '../content/planCompletion'
import { formatDelay } from '../content/planEnd'
import { copyFor, planResultContent } from '../content/planResult'
import { formatAmount } from '../format/decimal'
import { Time } from './EvidenceParts'
import styles from './PlanCompletionResult.module.css'
// A part of the result panel: it takes the panel's own groups and fact lists.
import panel from './PlanResultPanel.module.css'

const copy = planCompletionContent.result

/**
 * The completion result of a plan completed at its scheduled end `T` (Story 2.14), first in the panel's completion
 * mode: the comparison's state, `T`, the first normal observation after `T` — the time Bybit measured it, the delay
 * from `T` (the server's milliseconds) and its value, which is never called the valuation at `T` — the completion
 * target, the external flows between `T` and the observation, and why the comparison is what it is, with what is left
 * to do. Before the observation every observed fact says it has not been observed; a target that was not calculated
 * is never shown as zero. Codes this screen does not know are said in safe words. Nothing here judges the plan's goal;
 * the completion is never called a success or a failure.
 *
 * Its own region with its own heading, apart from `종료 후 현재가치`: later syncs never change it.
 */
export function PlanCompletionResult({
  completion,
}: {
  readonly completion: PlanCompletionRecord
}): ReactElement {
  const { observedValue, observedMeasuredAt, observationDelayMillis, completionTarget } = completion
  const comparison = comparisonName(completion.comparison)
  const reason =
    completion.reason === null ? null : copyFor(copy.reasons, completion.reason, copy.unknownReason)
  // A target exists only with a completion calculation. Without one, a comparison that cannot be calculated has none
  // to show; one still being decided has none yet.
  const target =
    completionTarget !== null
      ? `${formatAmount(completionTarget)} USD`
      : completion.comparison === 'UNAVAILABLE'
        ? planResultContent.notComputable
        : copy.noCompletionTarget
  return (
    <section aria-labelledby={copy.headingId} className={styles.region} id={copy.id}>
      <h3 id={copy.headingId}>{copy.heading}</h3>
      <p className={panel.marker}>
        {copy.comparisonLabel}: {comparison}
      </p>
      <dl className={panel.facts}>
        <div>
          <dt>{copy.scheduledEndAt}</dt>
          <dd>
            <Time value={completion.scheduledEndAt} />
          </dd>
        </div>
        <div>
          <dt>{copy.observedMeasuredAt}</dt>
          <dd>
            {observedMeasuredAt === null ? (
              copy.notObserved
            ) : (
              <>
                <Time precise value={observedMeasuredAt} />
                <span className={panel.factHelp}>{copy.observedMeasuredAtHelp}</span>
              </>
            )}
          </dd>
        </div>
        <div>
          <dt>{copy.delay}</dt>
          <dd>
            {observationDelayMillis === null
              ? copy.notObserved
              : formatDelay(observationDelayMillis)}
            {/* What the delay decides is said once there is a delay to read it against. */}
            {observationDelayMillis === null ? null : (
              <span className={panel.factHelp}>{copy.delayHelp}</span>
            )}
          </dd>
        </div>
        <div>
          <dt>{copy.observedValue}</dt>
          <dd className={panel.number}>
            {observedValue === null ? copy.notObserved : `${formatAmount(observedValue)} USD`}
            <span className={panel.factHelp}>{copy.observedValueHelp}</span>
          </dd>
        </div>
        <div>
          <dt>{copy.completionTarget}</dt>
          <dd className={panel.number}>
            {target}
            {completionTarget === null ? null : (
              <span className={panel.factHelp}>{copy.completionTargetHelp}</span>
            )}
          </dd>
        </div>
        {completion.postEndCashflowCount === null ? null : (
          <div>
            <dt>{copy.postEndCashflows}</dt>
            <dd>{copy.postEndCashflowCount(completion.postEndCashflowCount)}</dd>
          </div>
        )}
        {reason === null ? null : (
          <div>
            <dt>{copy.reasonLabel}</dt>
            <dd>{reason}</dd>
          </div>
        )}
      </dl>
      <p>
        {/* A held comparison a sync cannot clear promises no re-check (the server's `retryable`). */}
        {completion.comparison === 'HELD' && !completion.retryable
          ? copy.heldFinal
          : copyFor(copy.next, completion.comparison, copy.unknownNext)}
      </p>
      <p className={panel.meta}>{copy.fixed}</p>
      <p className={panel.meta}>{copy.distinct}</p>
    </section>
  )
}
