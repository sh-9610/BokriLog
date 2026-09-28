import type { ReactElement } from 'react'

import type { PlanPresence } from '../session/usePlanResult'

import { firstSnapshotContent as copy, failureActions } from '../content/firstSnapshot'
import { planConditionContent } from '../content/planConditions'
import { formatAmount as amount } from '../format/decimal'
import { useFirstSnapshot } from '../session/useFirstSnapshot'
import styles from './FirstSnapshotPanel.module.css'

/**
 * The official value always comes from the portfolio pointer returned by the server.
 *
 * What it says about a plan depends on what the screen knows (`plan`):
 * - `none` (the server confirmed there is no active plan): the "no plan yet" sentence, its provisional
 *   axes and the link to the plan conditions.
 * - `active`: the result panel owns the plan's trust axes, and the pointer has moved to the plan's
 *   current value — the heading and status stop calling it the first value.
 * - `unavailable`: a neutral sentence; it neither claims a plan nor its absence.
 * - `checking`: nothing about a plan yet.
 */
export function FirstSnapshotPanel({
  csrfToken,
  plan = 'none',
  ready,
}: {
  readonly csrfToken: string | null
  readonly plan?: PlanPresence
  readonly ready: boolean
}): ReactElement {
  const { view, pending, start, refresh } = useFirstSnapshot(csrfToken, ready)
  const job = view.kind === 'loaded' ? view.state.job : undefined
  const value = view.kind === 'loaded' ? view.state.decimal : undefined
  const active = pending || job?.status === 'QUEUED' || job?.status === 'RUNNING'
  const current = plan === 'active'
  let message: string = copy.checking
  if (view.kind === 'unconnected') message = copy.unconnected
  if (view.kind === 'unavailable') message = copy.unavailable
  if (view.kind === 'loaded') {
    if (view.state.restricted) message = copy.blocked
    else if (job?.status === 'QUEUED') message = copy.queued
    else if (job?.status === 'RUNNING') message = copy.running
    else if (job?.status === 'SUCCEEDED') message = current ? copy.currentSucceeded : copy.succeeded
    else if (job?.status === 'FAILED') message = copy.failed
    else if (job?.status === 'BLOCKED') message = copy.blocked
    else message = copy.noValue
  }
  const measured = view.kind === 'loaded' ? view.state.measuredAt : undefined
  const canPlan = value !== undefined && !value.startsWith('-') && /[1-9]/.test(value)
  return (
    <section aria-labelledby="first-snapshot-heading" className={styles.panel}>
      <h2 id="first-snapshot-heading">{current ? copy.currentHeading : copy.heading}</h2>
      <div aria-live="polite" aria-busy={active}>
        <p>{message}</p>
        {job?.status === 'FAILED' ? (
          <p role="alert">{failureActions[job.failureReason ?? ''] ?? copy.unknownFailure}</p>
        ) : null}
      </div>
      {value !== undefined && measured !== undefined ? (
        <div>
          <p>
            {copy.official}:{' '}
            <strong>
              {amount(value)} {view.kind === 'loaded' ? view.state.currency : 'USD'}
            </strong>
          </p>
          <p>
            {copy.measured}:{' '}
            <time dateTime={measured}>
              {new Intl.DateTimeFormat('ko-KR', {
                timeZone: 'Asia/Seoul',
                dateStyle: 'medium',
                timeStyle: 'long',
              }).format(new Date(measured))}
            </time>
          </p>
          {plan === 'unavailable' ? <p>{copy.planUnknown}</p> : null}
          {plan === 'none' ? (
            <>
              <p>{canPlan ? copy.noPlan : copy.cannotPlan}</p>
              <dl className={styles.axes}>
                {copy.axes.map((axis) => (
                  <div key={axis.name}>
                    <dt>
                      {axis.name} · {axis.state}
                    </dt>
                    <dd>
                      {axis.cause} 마지막 정상 시각: {measured}. 영향: {axis.impact}. 다음 행동:{' '}
                      {axis.action}
                    </dd>
                  </div>
                ))}
              </dl>
            </>
          ) : null}
        </div>
      ) : null}
      {view.kind === 'loaded' && canPlan && plan === 'none' ? (
        <p>
          <a href={planConditionContent.path}>{planConditionContent.openConditions}</a>
        </p>
      ) : null}
      {view.kind === 'unconnected' ? <a href="/settings">{copy.connectionSettings}</a> : null}
      {view.kind === 'unavailable' ? (
        <button type="button" onClick={refresh}>
          {copy.refresh}
        </button>
      ) : null}
      {view.kind === 'loaded' && !view.state.restricted && value === undefined ? (
        <button type="button" disabled={active} onClick={start}>
          {job?.status === 'FAILED' ? copy.retry : copy.start}
        </button>
      ) : null}
    </section>
  )
}
