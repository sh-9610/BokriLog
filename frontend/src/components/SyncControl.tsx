import { useEffect, useId, useRef, type ReactElement } from 'react'

import { planSyncContent as copy } from '../content/planSync'
import { manualEnabled, type DashboardSync, type SyncPhase } from '../session/useDashboardSync'
import styles from './SyncControl.module.css'

/** The line under the button: what the sync is doing, or why the button does not start one now. */
function stateLine(sync: DashboardSync): { readonly name: string; readonly help?: string } | null {
  const { phase, manual } = sync
  if (phase.kind === 'submitting' || phase.kind === 'syncing')
    return { name: copy.syncing, help: copy.syncingHelp }
  // After a refusal the button does nothing: the reason is on screen, not only in the status region.
  if (phase.kind === 'denied')
    return { name: phase.cause === 'signedOut' ? copy.signedOutLine : copy.deniedLine }
  if (manual === null || manual.available) return null
  if (manual.reason === 'COOLDOWN') return { name: copy.cooldown(manual.retryAfterSeconds ?? 1) }
  return { name: copy.unavailable(manual.reason ?? '') }
}

/** The summary of an end that published nothing, or of a request that got no answer. */
function summaryOf(phase: SyncPhase): {
  readonly title: string
  readonly cause: string
  readonly action: string
  readonly retry?: string
  readonly impact?: string
} | null {
  if (phase.kind === 'submitFailed')
    return { title: copy.summary.submitFailed, cause: copy.summary.submitFailedCause, action: '' }
  if (phase.kind === 'stalled')
    return { title: copy.summary.stalled, cause: copy.summary.stalledAction, action: '' }
  if (phase.kind !== 'ended' || phase.job.status === 'PUBLISHED') return null
  const { job } = phase
  const reason = copy.failure(job.failureCode ?? '')
  const retry = job.retryable ? copy.summary.retryable : copy.summary.notRetryable
  if (job.status === 'REVIEW_REQUIRED')
    return {
      title: copy.summary.review,
      cause: reason.cause,
      action: reason.action,
      retry,
      impact: copy.summary.reviewImpact,
    }
  return {
    title: job.status === 'BLOCKED' ? copy.summary.blocked : copy.summary.failed,
    cause: reason.cause,
    action: reason.action,
    retry,
  }
}

/** Whether the long-gap explanation belongs on screen: the result says so, or this screen's sync learned it. */
function longGapOf(phase: SyncPhase, resultSaysLongGap: boolean): boolean {
  if (resultSaysLongGap) return true
  if (phase.kind === 'answered') return phase.outcome === 'LONG_GAP'
  return phase.kind === 'ended' && phase.job.failureCode === 'LONG_GAP_CHOICE_REQUIRED'
}

/**
 * The manual sync control (Story 2.11, DESIGN.md sync-control), in the dashboard's next-action section.
 *
 * The button is `수동 동기화` and keeps its name; `aria-disabled` and a guard stop a second press while a
 * request or an attempt is in progress, when the server said not now, or after a refusal, and focus stays on
 * it — the line under it (its description) says `동기화 중` at once, the cooldown's seconds, or why not.
 * Beside it, always: the last normal
 * time and the note that recent deposits and withdrawals can appear late in the exchange's records.
 *
 * When an attempt the user started ends without publishing, the error summary (a group named by its sentence,
 * `tabIndex=-1`) takes focus:
 * the sentence, the cause, the next action and whether another request can help — and for a review, which
 * metrics stay the last normal ones and where the cause is explained. The long-gap explanation says what the
 * two choices would do and that Bybit is not called before one is made; it offers no control, because
 * neither choice exists on this screen yet. The control has no live region: the screen's status region says
 * what happened.
 */
export function SyncControl({
  sync,
  measured,
  longGap,
}: {
  readonly sync: DashboardSync
  /** The Seoul time of the last normal measurement, or `null` when it cannot be shown. */
  readonly measured: string | null
  /** The result's own next action is `REVIEW_LONG_GAP`. */
  readonly longGap: boolean
}): ReactElement {
  const stateId = useId()
  const summaryId = useId()
  const gapId = useId()
  const summary = useRef<HTMLDivElement>(null)
  const enabled = manualEnabled(sync)
  const line = stateLine(sync)
  const ended = summaryOf(sync.phase)
  const focusToken = sync.focus?.token ?? 0
  const focusTarget = sync.focus?.target ?? null
  // The request already handled when this control mounted: a remount never moves focus again.
  const handled = useRef(focusToken)

  useEffect(() => {
    // Only the end of the user's own press moves focus here; an automatic trigger never does.
    if (focusToken === handled.current) return
    handled.current = focusToken
    if (focusTarget === 'summary') summary.current?.focus()
  }, [focusTarget, focusToken])

  return (
    <div className={styles.control}>
      <button
        aria-describedby={line === null ? undefined : stateId}
        aria-disabled={!enabled}
        className={styles.button}
        onClick={() => {
          if (enabled) sync.requestManual()
        }}
        type="button"
      >
        {copy.button}
      </button>
      {line === null ? null : (
        <p id={stateId}>
          <span className={styles.state}>{line.name}</span>
          {line.help === undefined ? null : (
            <>
              {' '}
              <span className={styles.help}>{line.help}</span>
            </>
          )}
        </p>
      )}
      <p className={styles.meta}>
        {measured === null ? copy.lastNormalUnknown : copy.lastNormal(measured)}
      </p>
      <p className={styles.help}>{copy.lateEvents}</p>
      {ended === null ? null : (
        <div
          aria-labelledby={summaryId}
          className={styles.summary}
          ref={summary}
          role="group"
          tabIndex={-1}
        >
          <p className={styles.summaryTitle} id={summaryId}>
            {ended.title}
          </p>
          <dl className={styles.facts}>
            <div>
              <dt>{copy.summary.causeLabel}</dt>
              <dd>{ended.cause}</dd>
            </div>
            {ended.action === '' ? null : (
              <div>
                <dt>{copy.summary.actionLabel}</dt>
                <dd>
                  {ended.action}
                  {ended.retry === undefined ? null : ` ${ended.retry}`}
                </dd>
              </div>
            )}
          </dl>
          {ended.impact === undefined ? null : <p>{ended.impact}</p>}
        </div>
      )}
      {longGapOf(sync.phase, longGap) ? (
        <section aria-labelledby={gapId} className={styles.summary}>
          <h4 className={styles.summaryTitle} id={gapId}>
            {copy.longGap.heading}
          </h4>
          <p>{copy.longGap.intro}</p>
          <ul className={styles.choices}>
            {copy.longGap.choices.map((choice) => (
              <li key={choice.name}>
                <strong>{choice.name}</strong>: {choice.text}
              </li>
            ))}
          </ul>
          <p className={styles.help}>{copy.longGap.notYet}</p>
        </section>
      ) : null}
    </div>
  )
}
