import { useEffect, useRef, useState, type ReactElement, type SyntheticEvent } from 'react'

import { DASHBOARD_PATH } from '../content/auth'
import { planConditionContent as copy } from '../content/planConditions'
import { activePlanContent } from '../content/planResult'
import { formatAmount, isCanonicalNonNegative, percentFromRatio } from '../format/decimal'
import { InitialInputsCollection } from './InitialInputsCollection'
import styles from './PlanConditionPanel.module.css'

interface Snapshot {
  readonly decimal: string
  readonly measuredAt: string
  readonly publishedAt: string
  readonly portfolioId: string
  readonly snapshotId: string
  readonly membershipId: string
  readonly connectionId: string
  readonly providerAccountId: string
  readonly sourceSnapshotId: string
  readonly inputStatus: string
  readonly sourcePolicyVersion: string
  readonly portfolioPolicyVersion: string
}
interface Intent {
  readonly intentId: string
  readonly status: string
  readonly decimal: string
  readonly measuredAt: string
  readonly endAt: string
  readonly period: 'DAILY' | 'WEEKLY' | 'MONTHLY'
  readonly targetRate: string
  readonly snapshotId: string
  readonly createdAt: string
}
/**
 * The immutable active plan (Story 2.7). Present with the reason `ACTIVE_PLAN_EXISTS`.
 * `startAt` is the plan's `t0`; `decimal` (P0) and `targetRate` are canonical decimal strings.
 */
interface ActivePlan {
  readonly planId: string
  readonly status: string
  readonly decimal: string
  readonly startAt: string
  readonly endAt: string
  readonly period: Intent['period']
  readonly targetRate: string
}
interface StateResponse {
  readonly eligible: boolean
  readonly reason?: string | null
  readonly snapshot?: Snapshot | null
  readonly openIntent?: Intent | null
  readonly activePlan?: ActivePlan | null
}

const ACTIVE_PLAN_EXISTS = 'ACTIVE_PLAN_EXISTS'
/** The account's plan was ended early (Story 2.13): no new conditions are taken here. */
const PLAN_ENDED = 'PLAN_ENDED'

function isInstant(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(new Date(value).getTime())
}

/**
 * The summary is shown only when every value can be displayed: the amounts are canonical decimals
 * (so `formatAmount`/`percentFromRatio` never meet a string `BigInt` cannot read) and the times parse.
 */
function isActivePlan(value: unknown): value is ActivePlan {
  if (typeof value !== 'object' || value === null) return false
  const plan = value as Record<string, unknown>
  return (
    typeof plan.planId === 'string' &&
    typeof plan.status === 'string' &&
    isCanonicalNonNegative(plan.decimal) &&
    isCanonicalNonNegative(plan.targetRate) &&
    isInstant(plan.startAt) &&
    isInstant(plan.endAt) &&
    (plan.period === 'DAILY' || plan.period === 'WEEKLY' || plan.period === 'MONTHLY')
  )
}

/** The stable code of a common-shape error body, if any. */
function codeOf(body: unknown): string | null {
  if (typeof body !== 'object' || body === null) return null
  const code = (body as Record<string, unknown>).code
  return typeof code === 'string' ? code : null
}
interface Preview {
  readonly confirmationRef: string
  readonly expiresAt: string
  readonly snapshot: Snapshot
  readonly endAt: string
  readonly period: Intent['period']
  readonly targetRate: string
  readonly calendarPolicy: string
  readonly calculationPolicy: string
}

function isSnapshot(value: unknown): value is Snapshot {
  if (typeof value !== 'object' || value === null) return false
  const snapshot = value as Record<string, unknown>
  return [
    'decimal',
    'measuredAt',
    'publishedAt',
    'portfolioId',
    'snapshotId',
    'membershipId',
    'connectionId',
    'providerAccountId',
    'sourceSnapshotId',
    'inputStatus',
    'sourcePolicyVersion',
    'portfolioPolicyVersion',
  ].every((key) => typeof snapshot[key] === 'string')
}

function isPreview(value: unknown): value is Preview {
  if (typeof value !== 'object' || value === null) return false
  const preview = value as Record<string, unknown>
  return (
    typeof preview.confirmationRef === 'string' &&
    typeof preview.expiresAt === 'string' &&
    isSnapshot(preview.snapshot) &&
    typeof preview.endAt === 'string' &&
    (preview.period === 'DAILY' || preview.period === 'WEEKLY' || preview.period === 'MONTHLY') &&
    typeof preview.targetRate === 'string' &&
    typeof preview.calendarPolicy === 'string' &&
    typeof preview.calculationPolicy === 'string'
  )
}

function ratioFromPercent(value: string): string | null {
  if (!/^(?:0|[1-9]\d{0,3})(?:\.\d{1,14})?$/.test(value)) return null
  const [whole = '0', fraction = ''] = value.split('.')
  const digits = `${whole}${fraction}`.replace(/^0+(?=\d)/, '')
  const scale = fraction.length + 2
  const padded = digits.padStart(scale + 1, '0')
  const integer = padded.slice(0, -scale)
  const decimal = padded.slice(-scale).replace(/0+$/, '')
  const result = decimal.length === 0 ? integer : `${integer}.${decimal}`
  if (result.length > 20) return null
  const [resultWhole = '0', resultFraction = ''] = result.split('.')
  if (resultFraction.length > 12 || resultWhole.length + resultFraction.length > 18) return null
  const num =
    BigInt(resultWhole) * 10n ** BigInt(resultFraction.length) + BigInt(resultFraction || '0')
  if (num > 10n * 10n ** BigInt(resultFraction.length)) return null
  return result
}

function dateTime(value: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function age(value: string): string {
  const elapsed = Math.max(0, Date.now() - new Date(value).getTime())
  const minutes = Math.floor(elapsed / 60_000)
  if (minutes < 60) return `${String(minutes)}분`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${String(hours)}시간 ${String(minutes % 60)}분`
  const days = Math.floor(hours / 24)
  return `${String(days)}일 ${String(hours % 24)}시간`
}

function periodLabel(period: Intent['period']): string {
  if (period === 'DAILY') return copy.daily
  if (period === 'WEEKLY') return copy.weekly
  return copy.monthly
}

function canonicalLocalInput(value: string): string | null {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value) ? value : null
}

/** First-snapshot-based plan input and explicit confirmation. */
export function PlanConditionPanel({
  csrfToken,
  ready,
}: {
  readonly csrfToken: string | null
  readonly ready: boolean
}): ReactElement | null {
  const [state, setState] = useState<StateResponse | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [saved, setSaved] = useState<Intent | null>(null)
  const [endAt, setEndAt] = useState('')
  const [rate, setRate] = useState('')
  const [period, setPeriod] = useState<Intent['period']>('DAILY')
  const [error, setError] = useState<string | null>(null)
  const [endAtError, setEndAtError] = useState<string | null>(null)
  const [rateError, setRateError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  const [lostToActivePlan, setLostToActivePlan] = useState(false)
  const retryKey = useRef<string | null>(null)

  useEffect(() => {
    if (!ready) return
    let live = true
    void fetch('/api/plans/conditions', { credentials: 'same-origin', cache: 'no-store' })
      .then(async (response) => {
        if (!response.ok) throw new Error('load')
        return (await response.json()) as StateResponse
      })
      .then((value) => {
        if (live) {
          setState(value)
          if (value.openIntent !== undefined) setSaved(value.openIntent)
        }
      })
      .catch(() => {
        if (live) setError(copy.errors.network)
      })
    return () => {
      live = false
    }
  }, [ready, revision])

  /**
   * A request was refused because an active plan exists: reload the state, which carries its summary.
   * Who published it (this tab or another) is not known here, so the notice does not say.
   */
  function reloadForActivePlan(): void {
    setPreview(null)
    setSaved(null)
    setError(null)
    retryKey.current = null
    setLostToActivePlan(true)
    setRevision((n) => n + 1)
  }

  /** A request was refused because the plan was ended (another tab, say): reload the state, which says so. */
  function reloadForEndedPlan(): void {
    setPreview(null)
    setSaved(null)
    setError(null)
    retryKey.current = null
    setRevision((n) => n + 1)
  }

  if (!ready) return null
  if (state?.reason === PLAN_ENDED) {
    return (
      <section aria-labelledby="plan-condition-heading" className={styles.panel}>
        <h2 id="plan-condition-heading">{copy.heading}</h2>
        <div className={styles.savedStatus} role="status">
          <h3>{copy.planEnded.label}</h3>
          <p>{copy.planEnded.text}</p>
        </div>
        <p>
          <a className={styles.button} href={DASHBOARD_PATH}>
            {copy.planEnded.dashboardLink}
          </a>
        </p>
      </section>
    )
  }
  if (state !== null && isActivePlan(state.activePlan)) {
    const plan = state.activePlan
    return (
      <section aria-labelledby="plan-condition-heading" className={styles.panel}>
        <h2 id="plan-condition-heading">{copy.heading}</h2>
        <div className={styles.savedStatus} role="status">
          <h3>{activePlanContent.heading}</h3>
          <p>{activePlanContent.help}</p>
          {lostToActivePlan ? <p>{activePlanContent.conflict}</p> : null}
        </div>
        <dl className={styles.facts}>
          <div>
            <dt>{copy.p0}</dt>
            <dd>{formatAmount(plan.decimal)} USD</dd>
          </div>
          <div>
            <dt>{copy.t0}</dt>
            <dd>{dateTime(plan.startAt)} · Asia/Seoul</dd>
          </div>
          <div>
            <dt>{copy.endAt}</dt>
            <dd>{dateTime(plan.endAt)} · Asia/Seoul</dd>
          </div>
          <div>
            <dt>{copy.period}</dt>
            <dd>{periodLabel(plan.period)}</dd>
          </div>
          <div>
            <dt>{copy.rate}</dt>
            <dd>{percentFromRatio(plan.targetRate)}%</dd>
          </div>
        </dl>
        <p>
          <a className={styles.button} href={DASHBOARD_PATH}>
            {activePlanContent.dashboardLink}
          </a>
        </p>
      </section>
    )
  }
  if (state === null) {
    return (
      <section aria-labelledby="plan-condition-heading" className={styles.panel}>
        <h2 id="plan-condition-heading">{copy.heading}</h2>
        <p aria-live="polite" role={error === null ? 'status' : 'alert'}>
          {error ?? copy.intro}
        </p>
      </section>
    )
  }
  if (!state.eligible || state.snapshot === undefined || state.snapshot === null) {
    return (
      <section aria-labelledby="plan-condition-heading" className={styles.panel}>
        <h2 id="plan-condition-heading">{copy.heading}</h2>
        <p role="status">{copy.blocked}</p>
      </section>
    )
  }

  async function requestPreview(event: SyntheticEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault()
    setError(null)
    setEndAtError(null)
    setRateError(null)
    const local = canonicalLocalInput(endAt)
    const ratio = ratioFromPercent(rate)
    if (local === null) {
      setEndAtError(endAt.trim() === '' ? copy.errors.requiredEnd : copy.errors.time)
      return
    }
    if (rate.trim() === '') {
      setRateError(copy.errors.requiredRate)
      return
    }
    if (ratio === null) {
      setRateError(copy.errors.rate)
      return
    }
    if (csrfToken === null) {
      setError(copy.errors.network)
      return
    }
    setBusy(true)
    try {
      const response = await fetch('/api/plans/conditions/preview', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrfToken },
        body: JSON.stringify({ endAtSeoul: local, period, targetRate: ratio }),
      })
      if (response.status === 409) {
        const refusal: unknown = await response.json().catch(() => null)
        if (codeOf(refusal) === ACTIVE_PLAN_EXISTS) {
          reloadForActivePlan()
          return
        }
        if (codeOf(refusal) === PLAN_ENDED) {
          reloadForEndedPlan()
          return
        }
        setError(copy.errors.unavailable)
        return
      }
      if (!response.ok) throw new Error(response.status === 422 ? 'invalid' : 'network')
      const body: unknown = await response.json()
      if (!isPreview(body)) throw new Error('invalid preview')
      setPreview(body)
      setEndAt(local)
      retryKey.current = null
    } catch (caught) {
      if (caught instanceof Error && caught.message === 'invalid') {
        setEndAtError(copy.errors.time)
      } else {
        setError(copy.errors.network)
      }
    } finally {
      setBusy(false)
    }
  }

  async function confirm(): Promise<void> {
    if (preview === null || csrfToken === null) return
    setBusy(true)
    setError(null)
    retryKey.current ??= crypto.randomUUID()
    try {
      const response = await fetch('/api/plans/conditions/confirm', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': csrfToken,
          'Idempotency-Key': retryKey.current,
        },
        body: JSON.stringify({ confirmationRef: preview.confirmationRef }),
      })
      const body = (await response.json()) as {
        accepted: boolean
        conflict: boolean
        intent?: Intent
        code?: string
      }
      if (response.status === 409 && body.code === ACTIVE_PLAN_EXISTS) {
        reloadForActivePlan()
      } else if (response.status === 409 && body.code === PLAN_ENDED) {
        reloadForEndedPlan()
      } else if (body.conflict && body.intent !== undefined) {
        setSaved(body.intent)
        setPreview(null)
        retryKey.current = null
        setError(copy.errors.conflict)
      } else if (body.accepted && body.intent !== undefined) {
        setSaved(body.intent)
        setPreview(null)
        retryKey.current = null
      } else {
        retryKey.current = null
        setPreview(null)
        setError(copy.errors.stale)
      }
    } catch {
      // Keep the same idempotency key and confirmation reference after an uncertain response.
      setError(copy.errors.network)
    } finally {
      setBusy(false)
    }
  }

  const snapshot = state.snapshot
  return (
    <section aria-labelledby="plan-condition-heading" className={styles.panel}>
      <h2 id="plan-condition-heading">{copy.heading}</h2>
      <p>{copy.intro}</p>
      <dl className={styles.facts}>
        <div>
          <dt>{copy.p0}</dt>
          <dd>{snapshot.decimal} USD</dd>
        </div>
        <div>
          <dt>{copy.t0}</dt>
          <dd>{dateTime(snapshot.measuredAt)} · Asia/Seoul</dd>
        </div>
        <div>
          <dt>{copy.age}</dt>
          <dd>{age(snapshot.measuredAt)}</dd>
        </div>
        <div>
          <dt>{copy.source}</dt>
          <dd>
            Portfolio {snapshot.portfolioId} · snapshot {snapshot.snapshotId} · membership{' '}
            {snapshot.membershipId} · connection {snapshot.connectionId} · provider account{' '}
            {snapshot.providerAccountId} · source {snapshot.sourceSnapshotId}
          </dd>
        </div>
        <div>
          <dt>입력 상태 · 정책</dt>
          <dd>
            {snapshot.inputStatus} · {snapshot.sourcePolicyVersion} /{' '}
            {snapshot.portfolioPolicyVersion}
          </dd>
        </div>
        <div>
          <dt>게시 시각</dt>
          <dd>{dateTime(snapshot.publishedAt)}</dd>
        </div>
      </dl>

      {saved !== null && preview === null ? (
        <div className={styles.saved}>
          {/*
           * Only the confirmation is a live region. The collection, classification and conversion
           * sections below announce their own state; nesting them in this region would read each
           * change twice.
           */}
          <div className={styles.savedStatus} role="status">
            <h3>{copy.saved}</h3>
            <p>{copy.savedHelp}</p>
          </div>
          {error !== null ? (
            <p className={styles.error} role="alert">
              {error}
            </p>
          ) : null}
          <p>
            {periodLabel(saved.period)} · {percentFromRatio(saved.targetRate)}% ·{' '}
            {dateTime(saved.endAt)}
          </p>
          <p>{copy.savedAgain}</p>
          <InitialInputsCollection
            key={saved.intentId}
            csrfToken={csrfToken}
            intentId={saved.intentId}
          />
          <button
            className={styles.button}
            type="button"
            onClick={() => {
              setSaved(null)
            }}
          >
            {copy.cancel}
          </button>
        </div>
      ) : null}

      {saved === null && preview === null ? (
        <form
          className={styles.form}
          onSubmit={(event) => {
            void requestPreview(event)
          }}
        >
          <label htmlFor="plan-end-at">{copy.endAt}</label>
          <input
            aria-describedby={
              endAtError === null ? 'plan-end-help' : 'plan-end-help plan-end-error'
            }
            aria-invalid={endAtError !== null}
            autoComplete="off"
            id="plan-end-at"
            inputMode="text"
            onChange={(event) => {
              setEndAt(event.currentTarget.value)
              setEndAtError(null)
            }}
            placeholder="YYYY-MM-DDTHH:mm"
            value={endAt}
          />
          <p className={styles.help} id="plan-end-help">
            {copy.endHelp}
          </p>
          <label htmlFor="plan-period">{copy.period}</label>
          <select
            id="plan-period"
            onChange={(event) => {
              setPeriod(event.currentTarget.value as Intent['period'])
            }}
            value={period}
          >
            <option value="DAILY">{copy.daily}</option>
            <option value="WEEKLY">{copy.weekly}</option>
            <option value="MONTHLY">{copy.monthly}</option>
          </select>
          <label htmlFor="plan-rate">{copy.rate} (%)</label>
          <input
            aria-describedby={
              rateError === null ? 'plan-rate-help' : 'plan-rate-help plan-rate-error'
            }
            aria-invalid={rateError !== null}
            autoComplete="off"
            id="plan-rate"
            inputMode="decimal"
            onChange={(event) => {
              setRate(event.currentTarget.value)
              setRateError(null)
            }}
            value={rate}
          />
          <p className={styles.help} id="plan-rate-help">
            {copy.rateHelp}
          </p>
          {endAtError !== null ? (
            <p className={styles.error} id="plan-end-error">
              {endAtError}
            </p>
          ) : null}
          {rateError !== null ? (
            <p className={styles.error} id="plan-rate-error">
              {rateError}
            </p>
          ) : null}
          {endAtError !== null || rateError !== null ? (
            <p className={styles.error} role="alert">
              {copy.fieldErrors}:{' '}
              {[endAtError, rateError].filter((value) => value !== null).join(' ')}
            </p>
          ) : null}
          {error !== null ? (
            <p className={styles.error} id="plan-error" role="alert">
              {copy.fieldErrors}: {error}
            </p>
          ) : null}
          <button className={styles.button} disabled={busy} type="submit">
            {copy.review}
          </button>
        </form>
      ) : null}

      {preview !== null ? (
        <div className={styles.review}>
          <h3>검토할 조건</h3>
          <dl className={styles.facts}>
            <div>
              <dt>{copy.p0}</dt>
              <dd>{preview.snapshot.decimal} USD</dd>
            </div>
            <div>
              <dt>{copy.t0}</dt>
              <dd>{dateTime(preview.snapshot.measuredAt)}</dd>
            </div>
            <div>
              <dt>{copy.endAt}</dt>
              <dd>{dateTime(preview.endAt)}</dd>
            </div>
            <div>
              <dt>{copy.period}</dt>
              <dd>{periodLabel(preview.period)}</dd>
            </div>
            <div>
              <dt>{copy.rate}</dt>
              <dd>{percentFromRatio(preview.targetRate)}%</dd>
            </div>
            <div>
              <dt>{copy.age}</dt>
              <dd>{age(preview.snapshot.measuredAt)}</dd>
            </div>
            <div>
              <dt>입력 상태</dt>
              <dd>{preview.snapshot.inputStatus}</dd>
            </div>
            <div>
              <dt>source 정책 버전</dt>
              <dd>{preview.snapshot.sourcePolicyVersion}</dd>
            </div>
            <div>
              <dt>portfolio 정책 버전</dt>
              <dd>{preview.snapshot.portfolioPolicyVersion}</dd>
            </div>
            <div>
              <dt>{copy.source}</dt>
              <dd>
                Portfolio {preview.snapshot.portfolioId} · snapshot {preview.snapshot.snapshotId} ·
                membership {preview.snapshot.membershipId} · connection{' '}
                {preview.snapshot.connectionId} · account {preview.snapshot.providerAccountId} ·
                source {preview.snapshot.sourceSnapshotId}
              </dd>
            </div>
            <div>
              <dt>시간대 · 정책</dt>
              <dd>
                Asia/Seoul · {preview.calendarPolicy} · {preview.calculationPolicy}
              </dd>
            </div>
          </dl>
          {error !== null ? (
            <p className={styles.error} role="alert">
              {error}
            </p>
          ) : null}
          <div className={styles.actions}>
            <button
              autoFocus
              className={styles.secondary}
              disabled={busy}
              onClick={() => {
                setPreview(null)
                setError(null)
                retryKey.current = null
              }}
              type="button"
            >
              {copy.cancel}
            </button>
            <button
              className={styles.button}
              disabled={busy}
              onClick={() => {
                void confirm()
              }}
              type="button"
            >
              {copy.confirm}
            </button>
          </div>
        </div>
      ) : null}
    </section>
  )
}
