import { useCallback, useEffect, useRef, useState, type ReactElement } from 'react'

import { planConditionContent as copy } from '../content/planConditions'
import styles from './InitialInputsCollection.module.css'

type CollectionStatus =
  'QUEUED' | 'RUNNING' | 'INPUTS_COMPLETE' | 'NEEDS_REVIEW' | 'RETRY_REQUIRED' | 'CANCELLED'

interface CollectionJob {
  readonly jobId: string
  readonly status: CollectionStatus
  readonly failureCode?: string
  readonly lastCheckedAt?: string
}

interface CollectionResponse {
  readonly job: CollectionJob | null
}

function isJob(value: unknown): value is CollectionJob {
  if (typeof value !== 'object' || value === null) return false
  const job = value as Record<string, unknown>
  return (
    typeof job.jobId === 'string' &&
    typeof job.status === 'string' &&
    [
      'QUEUED',
      'RUNNING',
      'INPUTS_COMPLETE',
      'NEEDS_REVIEW',
      'RETRY_REQUIRED',
      'CANCELLED',
    ].includes(job.status) &&
    (job.failureCode === undefined || typeof job.failureCode === 'string') &&
    (job.lastCheckedAt === undefined || typeof job.lastCheckedAt === 'string')
  )
}

function isCollectionResponse(value: unknown): value is CollectionResponse {
  return (
    typeof value === 'object' &&
    value !== null &&
    'job' in value &&
    (value.job === null || isJob(value.job))
  )
}

function statusCopy(status: CollectionStatus): { label: string; help: string } {
  switch (status) {
    case 'QUEUED':
    case 'RUNNING':
      return { label: copy.collection.running, help: copy.collection.runningHelp }
    case 'INPUTS_COMPLETE':
      return { label: copy.collection.complete, help: copy.collection.completeHelp }
    case 'NEEDS_REVIEW':
      return { label: copy.collection.review, help: copy.collection.reviewHelp }
    case 'RETRY_REQUIRED':
      return { label: copy.collection.retry, help: copy.collection.retryHelp }
    case 'CANCELLED':
      return { label: copy.collection.cancelled, help: copy.collection.cancelledHelp }
  }
}

function dateTime(value: string): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return copy.collection.timeUnavailable
  try {
    return new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(date)
  } catch {
    return copy.collection.timeUnavailable
  }
}

function failureCopy(code: string | undefined): string | null {
  if (code === undefined) return null
  const reviewCodes = new Set([
    'MALFORMED_PROVIDER_RESPONSE',
    'UNKNOWN_OR_MALFORMED_EVENT',
    'SOURCE_REQUIRES_REVIEW',
    'PROVIDER_ID_CONFLICT',
  ])
  const retryCodes = new Set([
    'PROVIDER_UNAVAILABLE',
    'PROVIDER_CALL_FAILED',
    'PAGE_PERSIST_FAILED',
    'SCOPE_PERSIST_FAILED',
    'PUBLISH_FAILED',
  ])
  if (reviewCodes.has(code)) return copy.collection.failureReview
  if (retryCodes.has(code)) return copy.collection.failureRetry
  if (code === 'NOT_PERMITTED') return copy.collection.failureCancelled
  return copy.collection.failureUnknown
}

/** Starts and tracks initial Bybit source input collection without presenting candidate financial values. */
export function InitialInputsCollection({
  csrfToken,
  intentId,
}: {
  readonly csrfToken: string | null
  readonly intentId: string
}): ReactElement {
  const [job, setJob] = useState<CollectionJob | null>(null)
  const [busyIntentId, setBusyIntentId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const key = useRef<string | null>(null)
  const keyIntentId = useRef<string | null>(null)
  const live = useRef(true)
  const currentIntentId = useRef(intentId)
  const [jobIntentId, setJobIntentId] = useState<string | null>(null)
  const [loadedIntentId, setLoadedIntentId] = useState<string | null>(null)
  const [errorIntentId, setErrorIntentId] = useState<string | null>(null)

  const readCollectionState = useCallback(async (): Promise<CollectionResponse> => {
    const response = await fetch(
      `/api/snapshots/first/inputs?intentId=${encodeURIComponent(intentId)}`,
      {
        credentials: 'same-origin',
        cache: 'no-store',
      },
    )
    if (!response.ok) throw new Error('request')
    const body: unknown = await response.json()
    if (!isCollectionResponse(body)) throw new Error('response')
    return body
  }, [intentId])

  const refresh = useCallback(async (): Promise<void> => {
    try {
      const body = await readCollectionState()
      if (live.current && currentIntentId.current === intentId) {
        setJob(body.job)
        setJobIntentId(intentId)
        setError(null)
        setErrorIntentId(intentId)
      }
    } catch {
      if (live.current && currentIntentId.current === intentId) {
        setError(copy.collection.loadError)
        setErrorIntentId(intentId)
      }
    } finally {
      if (live.current && currentIntentId.current === intentId) setLoadedIntentId(intentId)
    }
  }, [intentId, readCollectionState])

  useEffect(() => {
    live.current = true
    currentIntentId.current = intentId
    key.current = null
    keyIntentId.current = intentId
    void readCollectionState()
      .then((body) => {
        if (live.current && currentIntentId.current === intentId) {
          setJob(body.job)
          setJobIntentId(intentId)
          setError(null)
          setErrorIntentId(intentId)
        }
      })
      .catch(() => {
        if (live.current && currentIntentId.current === intentId) {
          setError(copy.collection.loadError)
          setErrorIntentId(intentId)
        }
      })
      .finally(() => {
        if (live.current && currentIntentId.current === intentId) setLoadedIntentId(intentId)
      })
    return () => {
      live.current = false
    }
  }, [intentId, readCollectionState])

  useEffect(() => {
    if (job?.status !== 'QUEUED' && job?.status !== 'RUNNING') return
    const timer = window.setInterval(() => {
      void refresh()
    }, 2000)
    return () => {
      window.clearInterval(timer)
    }
  }, [job?.status, refresh])

  async function start(): Promise<void> {
    if (csrfToken === null) {
      setError(copy.collection.sessionError)
      setErrorIntentId(intentId)
      return
    }
    setBusyIntentId(intentId)
    setError(null)
    setErrorIntentId(intentId)
    if (keyIntentId.current !== intentId) {
      key.current = null
      keyIntentId.current = intentId
    }
    key.current ??= crypto.randomUUID()
    try {
      const response = await fetch('/api/snapshots/first/inputs', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': csrfToken,
          'Idempotency-Key': key.current,
        },
        body: JSON.stringify({ intentId }),
      })
      if (response.status === 409) {
        setError(copy.collection.ineligible)
        setErrorIntentId(intentId)
        await refresh()
        return
      }
      if (!response.ok) throw new Error('request')
      const body: unknown = await response.json()
      if (!isJob(body)) throw new Error('response')
      setJob(body)
      setJobIntentId(intentId)
      key.current = null
    } catch {
      // Keep the same idempotency key after an uncertain response.
      setError(copy.collection.startError)
      setErrorIntentId(intentId)
    } finally {
      if (live.current && currentIntentId.current === intentId) setBusyIntentId(null)
    }
  }

  async function retry(): Promise<void> {
    setJob(null)
    await start()
  }

  const currentJob = jobIntentId === intentId ? job : null
  const currentLoaded = loadedIntentId === intentId
  const currentError = errorIntentId === intentId ? error : null
  const currentStatus = currentJob === null ? null : statusCopy(currentJob.status)
  const currentBusy = busyIntentId === intentId
  const retryable = currentJob?.status === 'RETRY_REQUIRED' || currentJob?.status === 'CANCELLED'
  return (
    <section aria-labelledby="initial-inputs-heading" className={styles.panel}>
      <h3 id="initial-inputs-heading">{copy.collection.heading}</h3>
      <p>{copy.collection.intro}</p>
      {currentLoaded && currentJob === null ? (
        <p role="status">{copy.collection.notStarted}</p>
      ) : null}
      {currentJob !== null && currentStatus !== null ? (
        <div aria-live="polite" className={styles.status} role="status">
          <p className={styles.statusLabel}>{currentStatus.label}</p>
          <p>{currentStatus.help}</p>
          {currentJob.lastCheckedAt !== undefined ? (
            <p>
              {copy.collection.lastChecked}:{' '}
              <time
                dateTime={
                  Number.isFinite(new Date(currentJob.lastCheckedAt).getTime())
                    ? currentJob.lastCheckedAt
                    : undefined
                }
              >
                {dateTime(currentJob.lastCheckedAt)}
              </time>
            </p>
          ) : null}
          {failureCopy(currentJob.failureCode) !== null ? (
            <p>{failureCopy(currentJob.failureCode)}</p>
          ) : null}
        </div>
      ) : null}
      {currentError !== null ? (
        <p className={styles.error} role="alert">
          {currentError}
        </p>
      ) : null}
      <div className={styles.actions}>
        {currentJob === null ? (
          <button
            className={styles.button}
            disabled={currentBusy || !currentLoaded}
            onClick={() => void start()}
            type="button"
          >
            {currentBusy ? copy.collection.submitting : copy.collection.start}
          </button>
        ) : null}
        {retryable ? (
          <button
            className={styles.button}
            disabled={currentBusy}
            onClick={() => void retry()}
            type="button"
          >
            {currentBusy ? copy.collection.submitting : copy.collection.retryAction}
          </button>
        ) : null}
        {currentJob !== null &&
        currentJob.status !== 'QUEUED' &&
        currentJob.status !== 'RUNNING' ? (
          <button
            className={styles.secondary}
            disabled={currentBusy}
            onClick={() => void refresh()}
            type="button"
          >
            {copy.collection.refresh}
          </button>
        ) : null}
      </div>
    </section>
  )
}
