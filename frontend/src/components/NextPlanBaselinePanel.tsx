import { useEffect, useLayoutEffect, useRef, useState } from 'react'

import { nextPlanContent as copy } from '../content/nextPlan'
import { planConditionContent } from '../content/planConditions'
import { formatSeoulTime } from '../format/decimal'
import { DASHBOARD_REQUEST_TIMEOUT_MS, isInstant, record } from '../session/useDashboard'
import styles from './PlanConditionPanel.module.css'

const PATH = '/api/plans/next/baseline'
interface Preparation {
  readonly eligible: boolean
  readonly reason: string
  readonly job: { readonly status: string } | null
  readonly baseline: { readonly requestedAt: string; readonly completedAt: string } | null
  readonly snapshot: {
    readonly decimal: string
    readonly measuredAt: string
    readonly snapshotId: string
    readonly sourceSnapshotId: string
    readonly sourcePolicyVersion: string
  } | null
}
function parse(value: unknown): Preparation | null {
  const row = record(value)
  if (row === null || typeof row.eligible !== 'boolean' || typeof row.reason !== 'string')
    return null
  const snapshot = record(row.snapshot)
  const baseline = record(row.baseline)
  if (
    snapshot !== null &&
    (typeof snapshot.decimal !== 'string' ||
      !/^-?[0-9]+(?:\.[0-9]+)?$/.test(snapshot.decimal) ||
      !isInstant(snapshot.measuredAt) ||
      typeof snapshot.snapshotId !== 'string' ||
      typeof snapshot.sourceSnapshotId !== 'string' ||
      typeof snapshot.sourcePolicyVersion !== 'string')
  )
    return null
  if (
    baseline !== null &&
    (!isInstant(baseline.requestedAt) ||
      !isInstant(baseline.completedAt) ||
      new Date(baseline.completedAt).getTime() < new Date(baseline.requestedAt).getTime())
  )
    return null
  const job = record(row.job)
  if (job !== null && typeof job.status !== 'string') return null
  return {
    eligible: row.eligible,
    reason: row.reason,
    snapshot,
    baseline,
    job,
  } as unknown as Preparation
}
/** The deadline includes headers and the complete JSON body, and unmount abort settles the request. */
async function request(
  controller: AbortController,
  init?: RequestInit,
): Promise<{ response: Response; payload: unknown }> {
  let timer: number | undefined
  let abort: (() => void) | undefined
  try {
    const stopped = new Promise<never>((_, reject) => {
      abort = () => {
        reject(new DOMException('request stopped', 'AbortError'))
      }
      controller.signal.addEventListener('abort', abort, { once: true })
      timer = window.setTimeout(() => {
        controller.abort()
      }, DASHBOARD_REQUEST_TIMEOUT_MS)
    })
    return await Promise.race([
      (async () => {
        const response = await fetch(PATH, {
          ...init,
          credentials: 'same-origin',
          cache: 'no-store',
          signal: controller.signal,
        })
        const payload: unknown =
          response.status === 401 || response.status === 403 ? null : await response.json()
        return { response, payload }
      })(),
      stopped,
    ])
  } finally {
    window.clearTimeout(timer)
    if (abort !== undefined) controller.signal.removeEventListener('abort', abort)
  }
}
/** Provider 값은 메모리에만 둔다. 재시도 실패는 마지막 성공 snapshot을 지우지 않는다. */
export function NextPlanBaselinePanel({
  csrfToken,
  onPrepared,
  announce,
}: {
  readonly csrfToken: string | null
  readonly onPrepared?: () => void
  readonly announce?: (message: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [state, setState] = useState<Preparation | null>(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  const heading = useRef<HTMLHeadingElement>(null)
  const mounted = useRef(true)
  const reads = useRef<AbortController | null>(null)
  const writes = useRef<AbortController | null>(null)
  const retryKey = useRef<string | null>(null)
  const awaitingPreparation = useRef(false)
  const noticed = useRef<string | null>(null)
  const callback = useRef(onPrepared)
  const say = useRef(announce)
  useLayoutEffect(() => {
    callback.current = onPrepared
    say.current = announce
  })
  useLayoutEffect(() => {
    if (open) heading.current?.focus()
  }, [open])
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      reads.current?.abort()
      writes.current?.abort()
    }
  }, [])
  useEffect(() => {
    if (!open) return
    let active = true
    let timer: number | undefined
    const load = async (): Promise<void> => {
      const controller = new AbortController()
      reads.current = controller
      try {
        const { response, payload } = await request(controller)
        if (!active || !mounted.current || reads.current !== controller) return
        if (response.status === 401 || response.status === 403) {
          setState(null)
          setBusy(false)
          setMessage(copy.denied)
          say.current?.(copy.denied)
          return
        }
        if (!response.ok) throw new Error('read')
        const next = parse(payload)
        if (next === null) throw new Error('format')
        setState(next)
        const running =
          next.reason === 'JOB_IN_PROGRESS' ||
          next.job?.status === 'QUEUED' ||
          next.job?.status === 'RUNNING'
        setBusy(running)
        if (running) {
          awaitingPreparation.current = true
          setMessage(copy.waiting)
          timer = window.setTimeout(() => {
            void load()
          }, 2000)
          return
        }
        const failed = next.job?.status === 'FAILED' || next.job?.status === 'BLOCKED'
        const usable =
          next.snapshot !== null &&
          !next.snapshot.decimal.startsWith('-') &&
          /[1-9]/.test(next.snapshot.decimal)
        const statusMessage = failed
          ? copy.failed
          : next.reason === 'INPUT_UNCONFIRMED'
            ? copy.unconfirmed
            : next.snapshot === null
              ? next.eligible && next.reason === 'AVAILABLE' && next.job === null
                ? copy.initial
                : copy.failed
              : usable
                ? copy.ready
                : copy.zero
        setMessage(statusMessage)
        say.current?.(statusMessage)
        const changed = next.snapshot !== null && noticed.current !== next.snapshot.snapshotId
        noticed.current = next.snapshot?.snapshotId ?? noticed.current
        if (!failed && changed && usable && awaitingPreparation.current) callback.current?.()
        awaitingPreparation.current = false
      } catch {
        if (active && mounted.current && reads.current === controller) {
          setBusy(false)
          setMessage(copy.failed)
          say.current?.(copy.failed)
        }
      }
    }
    void load()
    return () => {
      active = false
      reads.current?.abort()
      window.clearTimeout(timer)
    }
  }, [revision, open])
  const prepare = async (): Promise<void> => {
    if (busy || csrfToken === null) return
    const previousRead = reads.current
    reads.current = null
    previousRead?.abort()
    setBusy(true)
    setMessage(copy.waiting)
    say.current?.(copy.waiting)
    retryKey.current ??= crypto.randomUUID()
    const controller = new AbortController()
    writes.current = controller
    try {
      const { response } = await request(controller, {
        method: 'POST',
        headers: { 'X-CSRF-TOKEN': csrfToken, 'Idempotency-Key': retryKey.current },
      })
      if (!mounted.current) return
      if (response.status === 401 || response.status === 403) {
        setState(null)
        setMessage(copy.denied)
        setBusy(false)
        retryKey.current = null
        say.current?.(copy.denied)
        return
      }
      if (!response.ok) {
        if (response.status < 500) retryKey.current = null
        throw new Error('prepare')
      }
      retryKey.current = null
      awaitingPreparation.current = true
      setRevision((value) => value + 1)
    } catch {
      if (mounted.current) {
        setBusy(false)
        setMessage(copy.failed)
        say.current?.(copy.failed)
      }
    }
  }
  const snapshot = state?.snapshot ?? null
  const preparation = state?.baseline ?? null
  const positive =
    snapshot !== null && !snapshot.decimal.startsWith('-') && /[1-9]/.test(snapshot.decimal)
  return (
    <section aria-labelledby="next-baseline-heading" aria-busy={busy} className={styles.panel}>
      <h2 id="next-baseline-heading" ref={heading} tabIndex={-1}>
        {copy.heading}
      </h2>
      <p>{copy.intro}</p>
      <p>{message}</p>
      {
        <button
          className={styles.button}
          type="button"
          disabled={busy}
          onClick={() => {
            setOpen(true)
            setRevision((value) => value + 1)
          }}
        >
          {copy.inspect}
        </button>
      }
      {snapshot === null ? null : (
        <dl>
          <dt>{copy.value}</dt>
          <dd>{snapshot.decimal}</dd>
          <dt>{copy.measured}</dt>
          <dd>{formatSeoulTime(snapshot.measuredAt, copy.failed)}</dd>
          <dt>{copy.provenance}</dt>
          <dd>
            {snapshot.snapshotId} / {snapshot.sourceSnapshotId} / {snapshot.sourcePolicyVersion}
          </dd>
          {preparation === null ? null : (
            <>
              <dt>{copy.completed}</dt>
              <dd>{formatSeoulTime(preparation.completedAt, copy.failed)}</dd>
              <dt>{copy.elapsed}</dt>
              <dd>
                {new Date(preparation.completedAt).getTime() -
                  new Date(preparation.requestedAt).getTime()}
              </dd>
            </>
          )}
        </dl>
      )}
      {open ? (
        <button
          className={styles.button}
          type="button"
          disabled={busy || state?.eligible !== true || csrfToken === null}
          onClick={() => {
            void prepare()
          }}
        >
          {copy.prepare}
        </button>
      ) : null}
      {positive && !busy ? (
        <a className={styles.button} href={planConditionContent.path}>
          {copy.conditions}
        </a>
      ) : null}
    </section>
  )
}
