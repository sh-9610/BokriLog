import type { ReactElement } from 'react'

import { firstSnapshotContent as copy, failureActions } from '../content/firstSnapshot'
import { planConditionContent } from '../content/planConditions'
import { useFirstSnapshot } from '../session/useFirstSnapshot'
import styles from './FirstSnapshotPanel.module.css'

function amount(decimal: string): string {
  const parts = /^(-?)(\d+)(?:\.(\d+))?$/.exec(decimal)
  if (parts === null) return decimal
  const fraction = (parts[3] ?? '').padEnd(3, '0')
  const minor =
    BigInt(parts[2] ?? '0') * 100n +
    BigInt(fraction.slice(0, 2)) +
    ((fraction[2] ?? '0') >= '5' ? 1n : 0n)
  const whole = (minor / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const sign = minor === 0n ? '' : (parts[1] ?? '')
  return `${sign}${whole}.${(minor % 100n).toString().padStart(2, '0')}`
}

/** First official value always comes from the portfolio pointer returned by the server. */
export function FirstSnapshotPanel({
  csrfToken,
  ready,
}: {
  readonly csrfToken: string | null
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
        </div>
      ) : null}
      {view.kind === 'loaded' && canPlan ? (
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
