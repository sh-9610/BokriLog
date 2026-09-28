import type { ReactElement } from 'react'

import type { PlanPresence } from '../session/useDashboard'

import { firstSnapshotContent as copy, failureActions } from '../content/firstSnapshot'
import { formatAmount as amount } from '../format/decimal'
import { useFirstSnapshot } from '../session/useFirstSnapshot'
import styles from './FirstSnapshotPanel.module.css'

/**
 * The official value always comes from the portfolio pointer returned by the server.
 *
 * The screens show this panel only when the dashboard answered `NO_PLAN` or `PREPARING` (Story 2.8,
 * `planPresenceOf`). The dashboard owns every sentence about the plan — "no plan", its next action and
 * the link to the plan conditions appear once, there. What stays here with `none` is the first value's
 * own story: whether it can start a plan, and its provisional axes.
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
  let message: string = copy.checking
  if (view.kind === 'unconnected') message = copy.unconnected
  if (view.kind === 'unavailable') message = copy.unavailable
  if (view.kind === 'loaded') {
    if (view.state.restricted) message = copy.blocked
    else if (job?.status === 'QUEUED') message = copy.queued
    else if (job?.status === 'RUNNING') message = copy.running
    else if (job?.status === 'SUCCEEDED') message = copy.succeeded
    else if (job?.status === 'FAILED') message = copy.failed
    else if (job?.status === 'BLOCKED') message = copy.blocked
    else message = copy.noValue
  }
  const measured = view.kind === 'loaded' ? view.state.measuredAt : undefined
  const canPlan = value !== undefined && !value.startsWith('-') && /[1-9]/.test(value)
  return (
    <section aria-labelledby="first-snapshot-heading" className={styles.panel}>
      <h2 id="first-snapshot-heading">{copy.heading}</h2>
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
          {plan === 'none' ? (
            <>
              {canPlan ? null : <p>{copy.cannotPlan}</p>}
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
