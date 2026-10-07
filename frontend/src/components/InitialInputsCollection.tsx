import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactElement } from 'react'

import { CSRF_HEADER } from '../content/auth'
import { planConditionContent as copy } from '../content/planConditions'
import { isInstant } from '../session/instant'
import styles from './InitialInputsCollection.module.css'
import { PlanPublicationSection } from './PlanPublicationSection'

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

interface ClassificationJob {
  readonly jobId: string
  readonly status: string
  readonly failureCode?: string
  readonly processedAt?: string
  readonly depositCount: number
  readonly withdrawalCount: number
  readonly excludedCount: number
  readonly reviewCount: number
  readonly reasonCode?: string
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

function isClassificationJob(value: unknown): value is ClassificationJob {
  if (typeof value !== 'object' || value === null) return false
  const job = value as Record<string, unknown>
  return (
    typeof job.jobId === 'string' &&
    typeof job.status === 'string' &&
    ['QUEUED', 'RUNNING', 'COMPLETE', 'NEEDS_REVIEW', 'CANCELLED'].includes(job.status) &&
    Number.isInteger(job.depositCount) &&
    Number.isInteger(job.withdrawalCount) &&
    Number.isInteger(job.excludedCount) &&
    Number.isInteger(job.reviewCount) &&
    (job.failureCode === undefined || typeof job.failureCode === 'string') &&
    (job.processedAt === undefined || typeof job.processedAt === 'string') &&
    (job.reasonCode === undefined || typeof job.reasonCode === 'string')
  )
}

type ConversionStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'CONVERTED'
  | 'CONVERTED_ESTIMATED'
  | 'CONVERSION_REQUIRED'
  | 'REVIEW_REQUIRED'
  | 'CANCELLED'

const CONVERSION_STATUSES: readonly string[] = [
  'QUEUED',
  'RUNNING',
  'CONVERTED',
  'CONVERTED_ESTIMATED',
  'CONVERSION_REQUIRED',
  'REVIEW_REQUIRED',
  'CANCELLED',
]

/** A held flow: conversion-required or review-required. */
interface ConversionPendingFlow {
  readonly direction: 'DEPOSIT' | 'WITHDRAWAL'
  readonly quantity: string
  readonly occurredAt: string
  readonly status: 'CONVERSION_REQUIRED' | 'REVIEW_REQUIRED'
  readonly reasonCode: string
  readonly retryable: boolean
}

interface ConversionPendingAsset {
  readonly assetCode: string
  readonly count: number
  readonly flows: readonly ConversionPendingFlow[]
}

/**
 * Server view of one conversion attempt. It carries no USD value by contract. The four outcome
 * counts exist only when the attempt has a result bundle; they are absent together otherwise.
 */
interface ConversionJob {
  readonly jobId: string
  readonly status: ConversionStatus
  readonly reasonCode?: string
  readonly retryable: boolean
  readonly processedAt?: string
  readonly flowCount: number
  readonly convertedCount?: number
  readonly estimatedCount?: number
  readonly conversionRequiredCount?: number
  readonly reviewCount?: number
  readonly calculable: boolean
  readonly pricePolicyVersion: string
  readonly calculationPolicyVersion: string
  readonly pendingAssets: readonly ConversionPendingAsset[]
}

function isCount(value: unknown): boolean {
  return Number.isInteger(value) && (value as number) >= 0
}

function isPendingFlow(value: unknown): value is ConversionPendingFlow {
  if (typeof value !== 'object' || value === null) return false
  const flow = value as Record<string, unknown>
  return (
    (flow.direction === 'DEPOSIT' || flow.direction === 'WITHDRAWAL') &&
    typeof flow.quantity === 'string' &&
    /^(0|[1-9]\d*)(\.\d*[1-9])?$/.test(flow.quantity) &&
    typeof flow.occurredAt === 'string' &&
    (flow.status === 'CONVERSION_REQUIRED' || flow.status === 'REVIEW_REQUIRED') &&
    typeof flow.reasonCode === 'string' &&
    typeof flow.retryable === 'boolean'
  )
}

const OUTCOME_COUNTS = [
  'convertedCount',
  'estimatedCount',
  'conversionRequiredCount',
  'reviewCount',
] as const

/** The outcome counts are all present (with a result) or all absent (without one). */
function hasConsistentCounts(job: Record<string, unknown>): boolean {
  const present = OUTCOME_COUNTS.filter((name) => job[name] !== undefined)
  if (present.length === 0) return true
  return present.length === OUTCOME_COUNTS.length && present.every((name) => isCount(job[name]))
}

/** The outcome counts when the attempt has a result bundle, otherwise `null`. */
function outcomeCounts(
  job: ConversionJob,
): { converted: number; estimated: number; required: number; review: number } | null {
  if (
    job.convertedCount === undefined ||
    job.estimatedCount === undefined ||
    job.conversionRequiredCount === undefined ||
    job.reviewCount === undefined
  )
    return null
  return {
    converted: job.convertedCount,
    estimated: job.estimatedCount,
    required: job.conversionRequiredCount,
    review: job.reviewCount,
  }
}

function isPendingAsset(value: unknown): value is ConversionPendingAsset {
  if (typeof value !== 'object' || value === null) return false
  const asset = value as Record<string, unknown>
  return (
    typeof asset.assetCode === 'string' &&
    /^[A-Z0-9]{1,32}$/.test(asset.assetCode) &&
    isCount(asset.count) &&
    Array.isArray(asset.flows) &&
    asset.flows.every(isPendingFlow)
  )
}

function isConversionJob(value: unknown): value is ConversionJob {
  if (typeof value !== 'object' || value === null) return false
  const job = value as Record<string, unknown>
  return (
    typeof job.jobId === 'string' &&
    typeof job.status === 'string' &&
    CONVERSION_STATUSES.includes(job.status) &&
    (job.reasonCode === undefined || typeof job.reasonCode === 'string') &&
    typeof job.retryable === 'boolean' &&
    (job.processedAt === undefined || typeof job.processedAt === 'string') &&
    isCount(job.flowCount) &&
    hasConsistentCounts(job) &&
    typeof job.calculable === 'boolean' &&
    typeof job.pricePolicyVersion === 'string' &&
    typeof job.calculationPolicyVersion === 'string' &&
    Array.isArray(job.pendingAssets) &&
    job.pendingAssets.every(isPendingAsset)
  )
}

function conversionStatusCopy(job: ConversionJob): { label: string; help: string } {
  switch (job.status) {
    case 'QUEUED':
    case 'RUNNING':
      return { label: copy.conversion.running, help: copy.conversion.runningHelp }
    case 'CONVERTED':
      // Nothing to convert is still a completed conversion, but not one "by direct normalization".
      return {
        label: copy.conversion.converted,
        help: job.flowCount === 0 ? copy.conversion.noFlowsHelp : copy.conversion.convertedHelp,
      }
    case 'CONVERTED_ESTIMATED':
      return { label: copy.conversion.estimated, help: copy.conversion.estimatedHelp }
    case 'CONVERSION_REQUIRED':
      // An interrupted attempt has no result: it says processing stopped, not that prices are missing.
      return {
        label: copy.conversion.required,
        help:
          job.reasonCode === 'CONVERSION_INTERRUPTED'
            ? copy.conversion.interruptedHelp
            : copy.conversion.requiredHelp,
      }
    case 'REVIEW_REQUIRED':
      return { label: copy.conversion.review, help: copy.conversion.reviewHelp }
    case 'CANCELLED':
      return { label: copy.conversion.cancelled, help: copy.conversion.cancelledHelp }
  }
}

/** Safe copy for an allowlisted reason code. Unknown codes never reach the screen verbatim. */
function reasonCopy(code: string): { cause: string; action: string } {
  const reasons: Readonly<Record<string, { cause: string; action: string }>> =
    copy.conversion.reasons
  return Object.hasOwn(reasons, code)
    ? (reasons[code] ?? copy.conversion.unknownReason)
    : copy.conversion.unknownReason
}

function eventTime(value: string): string {
  const date = new Date(value)
  if (!Number.isFinite(date.getTime())) return copy.conversion.timeUnavailable
  try {
    return new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      dateStyle: 'medium',
      timeStyle: 'medium',
    }).format(date)
  } catch {
    return copy.conversion.timeUnavailable
  }
}

/**
 * Event-time USD conversion of a COMPLETE classification (Story 2.6).
 *
 * The status sentence is the only live region: polling that returns the same state changes no text
 * and announces nothing, a changed state is announced once. Held flows are grouped by asset in
 * native disclosure widgets outside the live region. No USD amount, rate of return or plan value is
 * rendered — the server does not send one.
 */
function CashflowConversionSection({
  csrfToken,
  intentId,
  lineage,
  onCalculable,
}: {
  readonly csrfToken: string | null
  readonly intentId: string
  /** The classification this section converts. Its "calculable" is about this lineage only. */
  readonly lineage: string
  /**
   * Told whether this lineage's current result is a calculable bundle, so the publication sibling can
   * appear. The report names the lineage: a flag set for one classification never carries over to the
   * next one.
   */
  readonly onCalculable: (lineage: string, calculable: boolean) => void
}): ReactElement {
  const [job, setJob] = useState<ConversionJob | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const key = useRef<string | null>(null)
  const live = useRef(true)
  const calculableNow =
    job !== null &&
    (job.status === 'CONVERTED' || job.status === 'CONVERTED_ESTIMATED') &&
    job.calculable

  /*
   * The report to the parent stays a passive effect. It is not what this commit shows (the parent's sibling
   * appears from it), and from a layout effect the parent's state update would re-render synchronously before
   * the browser paints.
   */
  useEffect(() => {
    onCalculable(lineage, calculableNow)
    // An unmounted (or remounted) conversion section no longer vouches for a calculable result.
    return () => {
      onCalculable(lineage, false)
    }
  }, [calculableNow, lineage, onCalculable])

  const read = useCallback(async (): Promise<ConversionJob | null> => {
    const response = await fetch(
      `/api/snapshots/first/cashflow-conversion?intentId=${encodeURIComponent(intentId)}`,
      { credentials: 'same-origin', cache: 'no-store' },
    )
    if (!response.ok) throw new Error('request')
    if (response.status === 204) return null
    const body: unknown = await response.json()
    if (!isConversionJob(body)) throw new Error('response')
    return body
  }, [intentId])

  const refresh = useCallback(async (): Promise<void> => {
    try {
      const result = await read()
      if (live.current) {
        setJob(result)
        setError(null)
      }
    } catch {
      if (live.current) setError(copy.conversion.loadError)
    } finally {
      if (live.current) setLoaded(true)
    }
  }, [read])

  // The first read stays a passive effect: starting a request is not something the commit shows, and its answer
  // comes on a later task anyway. The polling below is a layout effect (see the collection's).
  useEffect(() => {
    live.current = true
    void read()
      .then((result) => {
        if (live.current) setJob(result)
      })
      .catch(() => {
        if (live.current) setError(copy.conversion.loadError)
      })
      .finally(() => {
        if (live.current) setLoaded(true)
      })
    return () => {
      live.current = false
    }
  }, [read])

  useLayoutEffect(() => {
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
      return
    }
    setBusy(true)
    setError(null)
    key.current ??= crypto.randomUUID()
    try {
      const response = await fetch('/api/snapshots/first/cashflow-conversion', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          [CSRF_HEADER]: csrfToken,
          'Idempotency-Key': key.current,
        },
        body: JSON.stringify({ intentId }),
      })
      if (response.status === 409) {
        // A definite refusal: nothing was accepted under this key. The state is refreshed first
        // because a successful refresh clears the error; the refusal must outlive it.
        key.current = null
        await refresh()
        if (live.current) setError(copy.conversion.ineligible)
        return
      }
      if (!response.ok) throw new Error('request')
      const body: unknown = await response.json()
      if (!isConversionJob(body)) throw new Error('response')
      if (live.current) setJob(body)
      key.current = null
    } catch {
      // Keep the same idempotency key after an uncertain response.
      if (live.current) setError(copy.conversion.startError)
    } finally {
      if (live.current) setBusy(false)
    }
  }

  const status = job === null ? null : conversionStatusCopy(job)
  const settled = job !== null && job.status !== 'QUEUED' && job.status !== 'RUNNING'
  const counts = settled ? outcomeCounts(job) : null
  const reason = settled && job.reasonCode !== undefined ? reasonCopy(job.reasonCode) : null
  const retryable =
    job !== null &&
    ((job.status === 'CONVERSION_REQUIRED' && job.retryable) || job.status === 'CANCELLED')
  const held =
    job !== null &&
    ((job.status === 'CONVERSION_REQUIRED' && !job.retryable) || job.status === 'REVIEW_REQUIRED')
  return (
    <section aria-labelledby="cashflow-conversion-heading" className={styles.status}>
      <h4 className={styles.statusLabel} id="cashflow-conversion-heading">
        {copy.conversion.heading}
      </h4>
      <p>{copy.conversion.intro}</p>
      <div aria-live="polite" className={styles.liveStatus} role="status">
        {loaded && job === null && error === null ? <p>{copy.conversion.notStarted}</p> : null}
        {status !== null ? <p className={styles.statusLabel}>{status.label}</p> : null}
        {status !== null ? <p>{status.help}</p> : null}
        {reason !== null ? <p>{reason.cause}</p> : null}
      </div>
      {error !== null ? (
        <p className={styles.error} role="alert">
          {error}
        </p>
      ) : null}
      {settled ? (
        <>
          {counts !== null ? (
            <p>
              {copy.conversion.counts(
                job.flowCount,
                counts.converted,
                counts.estimated,
                counts.required,
                counts.review,
              )}
            </p>
          ) : null}
          <p>
            {copy.conversion.policies}: {job.pricePolicyVersion} · {job.calculationPolicyVersion}
          </p>
          {job.calculable ? <p>{copy.conversion.calculable}</p> : null}
          {reason !== null ? (
            <p>
              {copy.conversion.nextAction}: {reason.action}
            </p>
          ) : null}
        </>
      ) : null}
      {settled && job.pendingAssets.length > 0 ? (
        <div className={styles.pending}>
          <p className={styles.statusLabel}>{copy.conversion.pendingHeading}</p>
          <ul className={styles.assetList}>
            {job.pendingAssets.map((asset) => (
              <li key={asset.assetCode}>
                <details className={styles.asset}>
                  <summary>{copy.conversion.pendingAsset(asset.assetCode, asset.count)}</summary>
                  <ul className={styles.flowList}>
                    {asset.flows.map((flow, index) => (
                      <li key={`${asset.assetCode}-${String(index)}`}>
                        <dl className={styles.facts}>
                          <div>
                            <dt>{copy.conversion.flowStatus}</dt>
                            <dd>
                              {flow.status === 'REVIEW_REQUIRED'
                                ? copy.conversion.review
                                : copy.conversion.required}
                            </dd>
                          </div>
                          <div>
                            <dt>{copy.conversion.direction}</dt>
                            <dd>
                              {flow.direction === 'DEPOSIT'
                                ? copy.conversion.deposit
                                : copy.conversion.withdrawal}
                            </dd>
                          </div>
                          <div>
                            <dt>{copy.conversion.quantity}</dt>
                            <dd className={styles.quantity}>
                              {flow.quantity} {asset.assetCode}
                            </dd>
                          </div>
                          <div>
                            <dt>{copy.conversion.occurredAt}</dt>
                            <dd>
                              <time
                                dateTime={isInstant(flow.occurredAt) ? flow.occurredAt : undefined}
                              >
                                {eventTime(flow.occurredAt)}
                              </time>
                            </dd>
                          </div>
                          <div>
                            <dt>{copy.conversion.reason}</dt>
                            <dd>{reasonCopy(flow.reasonCode).cause}</dd>
                          </div>
                          <div>
                            <dt>{copy.conversion.impact}</dt>
                            <dd>{copy.conversion.impactText}</dd>
                          </div>
                        </dl>
                      </li>
                    ))}
                  </ul>
                </details>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {held ? <p>{copy.conversion.planSwitch}</p> : null}
      <div className={styles.actions}>
        {loaded && job === null ? (
          <button
            className={styles.button}
            disabled={busy}
            onClick={() => void start()}
            type="button"
          >
            {busy ? copy.conversion.submitting : copy.conversion.start}
          </button>
        ) : null}
        {retryable ? (
          <button
            className={styles.button}
            disabled={busy}
            onClick={() => void start()}
            type="button"
          >
            {busy ? copy.conversion.submitting : copy.conversion.retryAction}
          </button>
        ) : null}
        {settled ? (
          <button
            className={styles.secondary}
            disabled={busy}
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
  const [classification, setClassification] = useState<ClassificationJob | null>(null)
  const [classificationIntentId, setClassificationIntentId] = useState<string | null>(null)
  const [classifying, setClassifying] = useState(false)
  const [busyIntentId, setBusyIntentId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const key = useRef<string | null>(null)
  const keyIntentId = useRef<string | null>(null)
  const classificationKey = useRef<string | null>(null)
  const live = useRef(true)
  const currentIntentId = useRef(intentId)
  const [jobIntentId, setJobIntentId] = useState<string | null>(null)
  const [loadedIntentId, setLoadedIntentId] = useState<string | null>(null)
  const [errorIntentId, setErrorIntentId] = useState<string | null>(null)
  /** The classification whose conversion is calculable now, if any. Never inherited by the next one. */
  const [calculableLineage, setCalculableLineage] = useState<string | null>(null)
  const reportCalculable = useCallback((lineage: string, calculable: boolean): void => {
    setCalculableLineage((current) => (calculable ? lineage : current === lineage ? null : current))
  }, [])

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

  const readClassification = useCallback(async (): Promise<ClassificationJob | null> => {
    const response = await fetch(
      `/api/snapshots/first/cashflow-classification?intentId=${encodeURIComponent(intentId)}`,
      { credentials: 'same-origin', cache: 'no-store' },
    )
    if (!response.ok) throw new Error('request')
    if (response.status === 204) return null
    const body: unknown = await response.json()
    if (body === null) return null
    if (!isClassificationJob(body)) throw new Error('response')
    return body
  }, [intentId])

  const refreshClassification = useCallback(async (): Promise<void> => {
    try {
      const result = await readClassification()
      if (live.current && currentIntentId.current === intentId) {
        setClassification(result)
        setClassificationIntentId(intentId)
      }
    } catch {
      if (live.current && currentIntentId.current === intentId) {
        setError(copy.collection.classificationLoadError)
        setErrorIntentId(intentId)
      }
    }
  }, [intentId, readClassification])

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

  // The first reads stay a passive effect: starting a request is not something the commit shows, and the answers
  // come on a later task anyway.
  useEffect(() => {
    live.current = true
    currentIntentId.current = intentId
    key.current = null
    keyIntentId.current = intentId
    classificationKey.current = null
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
    void readClassification()
      .then((result) => {
        if (live.current && currentIntentId.current === intentId) {
          setClassification(result)
          setClassificationIntentId(intentId)
        }
      })
      .catch(() => {
        if (live.current && currentIntentId.current === intentId) {
          setError(copy.collection.classificationLoadError)
          setErrorIntentId(intentId)
        }
      })
    return () => {
      live.current = false
    }
  }, [intentId, readCollectionState, readClassification])

  /*
   * The three pollings (this one, the classification's below and the conversion's) are layout effects: an
   * interval starts in the commit that shows a job in progress and is cleared in the commit that shows it ended.
   * As passive effects both came on a later task, and under load a test that had seen the new state could
   * advance its clock before the interval was set or after it should have been cleared.
   */
  useLayoutEffect(() => {
    if (job?.status !== 'QUEUED' && job?.status !== 'RUNNING') return
    const timer = window.setInterval(() => {
      void refresh()
    }, 2000)
    return () => {
      window.clearInterval(timer)
    }
  }, [job?.status, refresh])

  useLayoutEffect(() => {
    const current = classificationIntentId === intentId ? classification : null
    if (current?.status !== 'QUEUED' && current?.status !== 'RUNNING') return
    const timer = window.setInterval(() => {
      void refreshClassification()
    }, 2000)
    return () => {
      window.clearInterval(timer)
    }
  }, [classification, classificationIntentId, intentId, refreshClassification])

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

  async function classify(): Promise<void> {
    if (csrfToken === null) {
      setError(copy.collection.sessionError)
      setErrorIntentId(intentId)
      return
    }
    setClassifying(true)
    classificationKey.current = crypto.randomUUID()
    try {
      const response = await fetch('/api/snapshots/first/cashflow-classification', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': csrfToken,
          'Idempotency-Key': classificationKey.current,
        },
        body: JSON.stringify({ intentId }),
      })
      if (response.status === 409) throw new Error('ineligible')
      if (!response.ok) throw new Error('request')
      const body: unknown = await response.json()
      if (!isClassificationJob(body)) throw new Error('response')
      setClassification(body)
      setClassificationIntentId(intentId)
      classificationKey.current = null
    } catch {
      setError(copy.collection.classificationStartError)
      setErrorIntentId(intentId)
    } finally {
      if (live.current && currentIntentId.current === intentId) setClassifying(false)
    }
  }

  const currentJob = jobIntentId === intentId ? job : null
  const currentLoaded = loadedIntentId === intentId
  const currentError = errorIntentId === intentId ? error : null
  const currentClassification = classificationIntentId === intentId ? classification : null
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
                  isInstant(currentJob.lastCheckedAt) ? currentJob.lastCheckedAt : undefined
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
      {currentJob?.status === 'INPUTS_COMPLETE' ? (
        <div className={styles.status}>
          <p className={styles.statusLabel}>{copy.collection.classificationHeading}</p>
          <p>{copy.collection.classificationIntro}</p>
          {currentClassification !== null ? (
            <div aria-live="polite" role="status">
              <p className={styles.statusLabel}>
                {currentClassification.status === 'COMPLETE'
                  ? copy.collection.classificationComplete
                  : currentClassification.status === 'NEEDS_REVIEW'
                    ? copy.collection.classificationReview
                    : currentClassification.status === 'CANCELLED'
                      ? copy.collection.classificationCancelled
                      : copy.collection.classificationRunning}
              </p>
              <p>
                {copy.collection.deposits}: {currentClassification.depositCount} ·{' '}
                {copy.collection.withdrawals}: {currentClassification.withdrawalCount} ·{' '}
                {copy.collection.excluded}: {currentClassification.excludedCount} ·{' '}
                {copy.collection.needsReview}: {currentClassification.reviewCount}
              </p>
              {currentClassification.reasonCode === 'UNRECOGNIZED_EVENT_TYPE' ? (
                <p>{copy.collection.unknownTypeReason}</p>
              ) : currentClassification.reasonCode !== undefined ||
                currentClassification.failureCode !== undefined ? (
                <p>{copy.collection.genericReviewReason}</p>
              ) : null}
            </div>
          ) : null}
          <div className={styles.actions}>
            {currentClassification === null || currentClassification.status === 'CANCELLED' ? (
              <button
                className={styles.button}
                disabled={classifying}
                onClick={() => void classify()}
                type="button"
              >
                {classifying ? copy.collection.submitting : copy.collection.classify}
              </button>
            ) : null}
            {currentClassification !== null &&
            currentClassification.status !== 'QUEUED' &&
            currentClassification.status !== 'RUNNING' ? (
              <button
                className={styles.secondary}
                onClick={() => void refreshClassification()}
                type="button"
              >
                {copy.collection.refresh}
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
      {currentJob?.status === 'INPUTS_COMPLETE' && currentClassification?.status === 'COMPLETE' ? (
        <CashflowConversionSection
          csrfToken={csrfToken}
          intentId={intentId}
          /* Sibling keys must differ: a key shared with the publication section is unsupported. */
          key={`conversion-${currentClassification.jobId}`}
          lineage={currentClassification.jobId}
          onCalculable={reportCalculable}
        />
      ) : null}
      {/*
        The publication is a sibling of the conversion section, never inside it: that section keeps
        exactly one status region, and its no-amount contract stays scoped to it. It appears only for
        the lineage whose conversion reported "calculable" — never on another lineage's stale report.
      */}
      {currentJob?.status === 'INPUTS_COMPLETE' &&
      currentClassification?.status === 'COMPLETE' &&
      calculableLineage === currentClassification.jobId ? (
        <PlanPublicationSection
          csrfToken={csrfToken}
          intentId={intentId}
          key={`publication-${currentClassification.jobId}`}
        />
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
