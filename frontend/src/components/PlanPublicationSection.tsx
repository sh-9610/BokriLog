import { useCallback, useEffect, useRef, useState, type ReactElement } from 'react'

import { CSRF_HEADER, DASHBOARD_PATH } from '../content/auth'
import { planPublicationContent as copy, PLAN_PUBLICATION_PATH } from '../content/planResult'
import styles from './InitialInputsCollection.module.css'

type PublicationStatus = 'QUEUED' | 'RUNNING' | 'PUBLISHED' | 'BLOCKED' | 'FAILED'

const STATUSES: readonly string[] = ['QUEUED', 'RUNNING', 'PUBLISHED', 'BLOCKED', 'FAILED']

/** The admission's code for "the account itself does not permit this now". */
const NOT_PERMITTED = 'NOT_PERMITTED'

/** One first-publication attempt. It carries no amount: the result lives on the dashboard. */
interface PublicationJob {
  readonly jobId: string
  readonly status: PublicationStatus
  readonly failureCode?: string
  readonly retryable: boolean
  readonly planId?: string
}

/**
 * The intent's publication state as the server judges it now. `startable` comes from the admission's
 * own readiness: the screen offers a start exactly when the admission would accept one.
 */
interface PublicationState {
  readonly job: PublicationJob | null
  readonly startable: boolean
  readonly reason: string | null
}

function isPublicationJob(value: unknown): value is PublicationJob {
  if (typeof value !== 'object' || value === null) return false
  const job = value as Record<string, unknown>
  return (
    typeof job.jobId === 'string' &&
    typeof job.status === 'string' &&
    STATUSES.includes(job.status) &&
    typeof job.retryable === 'boolean' &&
    (job.failureCode === undefined || typeof job.failureCode === 'string') &&
    (job.planId === undefined || typeof job.planId === 'string')
  )
}

function stateOf(value: unknown): PublicationState | null {
  if (typeof value !== 'object' || value === null) return null
  const body = value as Record<string, unknown>
  if (typeof body.startable !== 'boolean') return null
  const job = body.job
  if (job !== undefined && !isPublicationJob(job)) return null
  const reason = body.reason
  if (reason !== undefined && typeof reason !== 'string') return null
  return { job: job ?? null, startable: body.startable, reason: reason ?? null }
}

/** Safe copy for an allowlisted code. Unknown codes never reach the screen verbatim. */
function reasonCopy(code: string): { cause: string; action: string } {
  const reasons: Readonly<Record<string, { cause: string; action: string }>> = copy.reasons
  return Object.hasOwn(reasons, code) ? (reasons[code] ?? copy.unknownReason) : copy.unknownReason
}

function codeOf(body: unknown): string | null {
  if (typeof body !== 'object' || body === null) return null
  const code = (body as Record<string, unknown>).code
  return typeof code === 'string' ? code : null
}

function inProgress(job: PublicationJob | null): boolean {
  return job?.status === 'QUEUED' || job?.status === 'RUNNING'
}

/** What a refused read (403) means: the account does not permit publishing now; nothing to follow. */
const DENIED: PublicationState = { job: null, startable: false, reason: NOT_PERMITTED }

/**
 * The first plan calculation and publication (Story 2.7), a sibling of the conversion section.
 *
 * It appears only once the conversion is calculable. It starts one attempt, follows it, and on
 * success links to the dashboard where the official result is shown; it renders no metric itself.
 * Its status sentence is its own live region — outside the conversion section, whose single status
 * region stays single.
 *
 * Whether a start is offered is the server's answer (`startable`), never a guess from the last
 * attempt's ending: an ending that says "publish again" gets a button exactly when the admission
 * would take it, and a refusal that the user cannot fix here gets its reason and next step instead.
 */
export function PlanPublicationSection({
  csrfToken,
  intentId,
}: {
  readonly csrfToken: string | null
  readonly intentId: string
}): ReactElement {
  const [state, setState] = useState<PublicationState | null>(null)
  const [busy, setBusy] = useState(false)
  const [refusal, setRefusal] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const key = useRef<string | null>(null)
  const live = useRef(true)

  const read = useCallback(async (): Promise<PublicationState> => {
    const response = await fetch(
      `${PLAN_PUBLICATION_PATH}?intentId=${encodeURIComponent(intentId)}`,
      { credentials: 'same-origin', cache: 'no-store' },
    )
    // Refused: the account does not permit this now. Following an attempt it cannot read is pointless.
    if (response.status === 403) return DENIED
    if (!response.ok) throw new Error('request')
    const parsed = stateOf(await response.json())
    if (parsed === null) throw new Error('response')
    return parsed
  }, [intentId])

  const refresh = useCallback(async (): Promise<void> => {
    try {
      const result = await read()
      if (live.current) {
        setState(result)
        setError(null)
      }
    } catch {
      if (live.current) setError(copy.loadError)
    }
  }, [read])

  useEffect(() => {
    live.current = true
    void read()
      .then((result) => {
        if (live.current) setState(result)
      })
      .catch(() => {
        if (live.current) setError(copy.loadError)
      })
    return () => {
      live.current = false
    }
  }, [read])

  const job = state?.job ?? null
  const following = inProgress(job)
  useEffect(() => {
    // Only an attempt in progress is followed; a refusal (403) or any ending clears the interval.
    if (!following) return
    const timer = window.setInterval(() => {
      void refresh()
    }, 2000)
    return () => {
      window.clearInterval(timer)
    }
  }, [following, refresh])

  async function start(): Promise<void> {
    if (csrfToken === null) {
      setError(copy.sessionError)
      return
    }
    setBusy(true)
    setError(null)
    setRefusal(null)
    key.current ??= crypto.randomUUID()
    try {
      const response = await fetch(PLAN_PUBLICATION_PATH, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          [CSRF_HEADER]: csrfToken,
          'Idempotency-Key': key.current,
        },
        body: JSON.stringify({ intentId }),
      })
      if (response.status === 409 || response.status === 403) {
        // A definite refusal: nothing was accepted under this key. Re-read what is startable now.
        key.current = null
        const code = response.status === 403 ? NOT_PERMITTED : codeOf(await response.json())
        if (live.current) setRefusal(code ?? NOT_PERMITTED)
        await refresh()
        return
      }
      if (!response.ok) throw new Error('request')
      const body: unknown = await response.json()
      if (!isPublicationJob(body)) throw new Error('response')
      if (live.current) setState({ job: body, startable: false, reason: null })
      key.current = null
    } catch {
      // Keep the same idempotency key after an uncertain response.
      if (live.current) setError(copy.startError)
    } finally {
      if (live.current) setBusy(false)
    }
  }

  const loaded = state !== null
  const startable = state?.startable === true
  const reason = state?.reason ?? null
  const settled = job !== null && !following
  const endingCode = refusal ?? (settled ? (job.failureCode ?? null) : null)
  const ending = endingCode === null ? null : reasonCopy(endingCode)
  // Why a start is not offered now (the server's reason), and whether that adds to the ending above.
  const blocking = !startable && reason !== null ? reasonCopy(reason) : null
  // After this attempt published, "an active plan exists" is its success, not a reason to report.
  const current =
    job?.status !== 'PUBLISHED' && blocking !== null && reason !== endingCode ? blocking : null
  const published =
    job?.status === 'PUBLISHED' ||
    refusal === 'ACTIVE_PLAN_EXISTS' ||
    reason === 'ACTIVE_PLAN_EXISTS'
  const canStart = loaded && startable && !following && !published
  // The next step: the ending's own when a start is offered; otherwise what the refusal asks for.
  const action =
    job?.status === 'PUBLISHED'
      ? null
      : canStart
        ? (ending?.action ?? null)
        : (blocking?.action ?? ending?.action ?? null)
  let label: string | null = null
  let help: string | null = null
  if (following) {
    label = copy.running
    help = copy.runningHelp
  } else if (job?.status === 'PUBLISHED') {
    label = copy.published
    help = copy.publishedHelp
  } else if (refusal !== null || job?.status === 'BLOCKED') {
    label = copy.blocked
  } else if (job?.status === 'FAILED') {
    label = copy.failed
  } else if (loaded && job === null && !startable) {
    label = copy.notStartable
  }
  return (
    <section aria-labelledby="plan-publication-heading" className={styles.status}>
      <h4 className={styles.statusLabel} id="plan-publication-heading">
        {copy.heading}
      </h4>
      <p>{copy.intro}</p>
      <div aria-live="polite" className={styles.liveStatus} role="status">
        {loaded && job === null && refusal === null && startable && error === null ? (
          <p>{copy.notStarted}</p>
        ) : null}
        {label !== null ? <p className={styles.statusLabel}>{label}</p> : null}
        {help !== null ? <p>{help}</p> : null}
        {ending !== null ? (
          <p>
            {copy.reasonLabel}: {ending.cause}
          </p>
        ) : null}
        {current !== null ? (
          <p>
            {ending === null ? copy.reasonLabel : copy.currentReasonLabel}: {current.cause}
          </p>
        ) : null}
      </div>
      {error !== null ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : null}
      {action !== null ? (
        <p>
          {copy.actionLabel}: {action}
        </p>
      ) : null}
      <div className={styles.actions}>
        {published ? (
          <a className={styles.button} href={DASHBOARD_PATH}>
            {copy.dashboardLink}
          </a>
        ) : null}
        {canStart ? (
          <button
            className={styles.button}
            disabled={busy}
            onClick={() => void start()}
            type="button"
          >
            {busy ? copy.submitting : job === null ? copy.start : copy.retryAction}
          </button>
        ) : null}
        {loaded && !following && (settled || refusal !== null || !startable) ? (
          <button
            className={styles.secondary}
            disabled={busy}
            onClick={() => {
              setRefusal(null)
              void refresh()
            }}
            type="button"
          >
            {copy.refresh}
          </button>
        ) : null}
      </div>
    </section>
  )
}
