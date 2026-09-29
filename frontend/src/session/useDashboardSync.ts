import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import { CSRF_HEADER } from '../content/auth'
import {
  planSyncContent as copy,
  DASHBOARD_SYNC_PATH,
  REACTIVATION_HIDDEN_MS,
  SYNC_POLL_FAILURES,
  SYNC_POLL_MS,
  SYNC_REQUEST_TIMEOUT_MS,
  type SyncTrigger,
} from '../content/planSync'
import { bundleOf, record, type Dashboard } from './useDashboard'

/** One sync attempt as the server shows it: codes and times, and the attempt's own id only. */
export interface SyncJob {
  readonly jobId: string
  readonly trigger: string
  readonly status: 'QUEUED' | 'RUNNING' | 'PUBLISHED' | 'REVIEW_REQUIRED' | 'FAILED' | 'BLOCKED'
  readonly stage: string
  readonly mode: string | null
  readonly failureCode: string | null
  /** Whether a new attempt can help, from the server's closed table. */
  readonly retryable: boolean
  readonly createdAt: string
  readonly finishedAt: string | null
  readonly pointerVersion: number | null
}

/** Whether the button would start a sync now, and why not. */
export interface ManualSync {
  readonly available: boolean
  readonly reason?: string
  readonly retryAfterSeconds?: number
}

const STATUSES: readonly string[] = [
  'QUEUED',
  'RUNNING',
  'PUBLISHED',
  'REVIEW_REQUIRED',
  'FAILED',
  'BLOCKED',
]

function nullableString(value: unknown): boolean {
  return value === null || typeof value === 'string'
}

/** A job as the server sends it, or `null`. */
export function parseSyncJob(value: unknown): SyncJob | null {
  const row = record(value)
  if (
    row === null ||
    typeof row.jobId !== 'string' ||
    typeof row.trigger !== 'string' ||
    typeof row.status !== 'string' ||
    !STATUSES.includes(row.status) ||
    typeof row.stage !== 'string' ||
    !nullableString(row.mode) ||
    !nullableString(row.failureCode) ||
    typeof row.retryable !== 'boolean' ||
    typeof row.createdAt !== 'string' ||
    !nullableString(row.finishedAt) ||
    !(row.pointerVersion === null || Number.isInteger(row.pointerVersion))
  ) {
    return null
  }
  return row as unknown as SyncJob
}

/** The button's block as the server sends it, or `null`. */
export function parseManual(value: unknown): ManualSync | null {
  const row = record(value)
  if (row === null || typeof row.available !== 'boolean') return null
  if (row.reason !== undefined && typeof row.reason !== 'string') return null
  if (row.retryAfterSeconds !== undefined && !Number.isInteger(row.retryAfterSeconds)) return null
  return row as unknown as ManualSync
}

export function inProgress(job: SyncJob | null): job is SyncJob {
  return job !== null && (job.status === 'QUEUED' || job.status === 'RUNNING')
}

/**
 * What the sync control and the status region show.
 *
 * `unknown` has no answer yet. `submitting` is the button's request in flight (or waiting behind an automatic
 * one) — the control says `동기화 중` at once, within the second the screen promises. `syncing` watches an
 * attempt in progress. `ended` is an attempt this screen watched finish. `answered` is an admission that started
 * nothing (a cooldown, a long gap, a refusal, a recent sync). `stalled` stopped reading after three failed reads
 * in a row, and `submitFailed` is the button's request that did not reach an answer. `denied` is a refusal of the
 * account (403) or a gone session (401) of the user's own request: it holds until the session's token changes.
 * `userInitiated` says whether the user's own press led here: only then does the end move focus. `token` numbers
 * each state that may move focus.
 */
export type SyncPhase =
  | { readonly kind: 'unknown' }
  | { readonly kind: 'idle' }
  | { readonly kind: 'submitting' }
  | { readonly kind: 'syncing'; readonly job: SyncJob; readonly userInitiated: boolean }
  | {
      readonly kind: 'ended'
      readonly job: SyncJob
      readonly userInitiated: boolean
      readonly token: number
    }
  | {
      readonly kind: 'answered'
      readonly outcome: string
      readonly reason: string | null
      readonly userInitiated: boolean
    }
  | { readonly kind: 'stalled'; readonly userInitiated: boolean; readonly token: number }
  | { readonly kind: 'submitFailed'; readonly token: number }
  | {
      readonly kind: 'denied'
      readonly cause: 'refused' | 'signedOut'
      readonly csrfToken: string | null
    }

/** Where a user-initiated end moves focus: the result heading on a publication, else the error summary. */
export interface SyncFocus {
  readonly target: 'heading' | 'summary'
  readonly token: number
}

export interface DashboardSync {
  readonly phase: SyncPhase
  /** The button's availability from the server's latest answer; `null` before one, or when it no longer holds. */
  readonly manual: ManualSync | null
  readonly focus: SyncFocus | null
  /**
   * An ended publication the dashboard now shows: the bundle on screen is the attempt's version (or a later one).
   * Only then is the publication announced and does focus move to the result heading — a reload that failed or
   * conflicted never reads as a success.
   */
  readonly shown?: boolean
  /** The button. Ignored while an attempt is in progress, when the server said not now, or after a refusal. */
  readonly requestManual: () => void
}

/** Whether the button may send a request now. Focus stays on it either way. */
export function manualEnabled(sync: DashboardSync): boolean {
  const { phase, manual } = sync
  if (phase.kind === 'submitting' || phase.kind === 'syncing' || phase.kind === 'denied')
    return false
  return manual === null || manual.available
}

interface Answer {
  readonly outcome: string
  readonly reason: string | null
  readonly job: SyncJob | null
  readonly manual: ManualSync
}

function parseAnswer(value: unknown): Answer | null {
  const row = record(value)
  if (row === null || typeof row.outcome !== 'string') return null
  const manual = parseManual(row.manual)
  if (manual === null) return null
  if (row.reason !== undefined && typeof row.reason !== 'string') return null
  const job = row.job === undefined ? null : parseSyncJob(row.job)
  if (row.job !== undefined && job === null) return null
  return {
    outcome: row.outcome,
    reason: typeof row.reason === 'string' ? row.reason : null,
    job,
    manual,
  }
}

function parseState(value: unknown): { job: SyncJob | null; manual: ManualSync } | null {
  const row = record(value)
  if (row === null) return null
  const manual = parseManual(row.manual)
  if (manual === null) return null
  const job = row.job === undefined ? null : parseSyncJob(row.job)
  if (row.job !== undefined && job === null) return null
  return { job, manual }
}

/** One request, cut off after {@link SYNC_REQUEST_TIMEOUT_MS}: an answer that never comes counts as a failure. */
async function timedFetch(path: string, init: RequestInit): Promise<Response> {
  const controller = new AbortController()
  const timer = setTimeout(() => {
    controller.abort()
  }, SYNC_REQUEST_TIMEOUT_MS)
  try {
    return await fetch(path, { ...init, signal: controller.signal })
  } finally {
    clearTimeout(timer)
  }
}

const IDLE: SyncPhase = { kind: 'idle' }

/** The focus an ended state asks for, if it asks for any. */
function focusOf(phase: SyncPhase, shown: boolean): SyncFocus | null {
  switch (phase.kind) {
    case 'ended':
      if (!phase.userInitiated) return null
      if (phase.job.status === 'PUBLISHED')
        return shown ? { target: 'heading', token: phase.token } : null
      return { target: 'summary', token: phase.token }
    case 'stalled':
      return phase.userInitiated ? { target: 'summary', token: phase.token } : null
    case 'submitFailed':
      return { target: 'summary', token: phase.token }
    default:
      return null
  }
}

/**
 * The active plan's incremental sync at the user's moment (Story 2.11, SYNC-UX-1.0).
 *
 * Three triggers, and nothing else: entering the dashboard (once per plan, when its first ready bundle
 * arrives), the tab becoming visible after at least five minutes hidden (measured with `performance.now`),
 * and the button. There is no timer that syncs on its own. The server decides whether anything starts —
 * entry within five minutes of the last admission, or a press within thirty seconds, starts nothing. A press
 * while an automatic request is still out is not dropped: it says `동기화 중` at once and is sent when that
 * request ends (or answered by the attempt that request started).
 *
 * While an attempt is in progress, and only then, its state is read every two seconds; three failed reads
 * in a row stop the reading (no retry loop), and the button's availability becomes unknown again, so the
 * next press asks the server. If the server names another attempt, one in progress is followed and one that
 * ended is left to the dashboard's quiet reload — no failure is claimed for it. Every request is cut off after
 * ten seconds. When the watched attempt ends, the dashboard reads again quietly and swaps the whole bundle at
 * once; a publication is announced — and focus moves to the result heading — only once the dashboard shows
 * its version. Any other end the user's own press led to moves focus to the error summary. Automatic triggers
 * never move focus, and their failures — a refusal included — are silent: nothing the user did went
 * unanswered.
 *
 * A refusal (403) or a gone session (401) of the user's own request stops the sync until the session's token
 * changes. A cooldown the server named ends on its own after its seconds. Nothing is stored in the browser.
 */
export function useDashboardSync(dashboard: Dashboard, csrfToken: string | null): DashboardSync {
  const bundle = bundleOf(dashboard.view)
  const planId = bundle?.result.planId ?? null
  const firstReady = dashboard.view.kind === 'READY'
  const reload = dashboard.reload
  const [phase, setPhase] = useState<SyncPhase>({ kind: 'unknown' })
  const [manual, setManual] = useState<ManualSync | null>(null)
  const entered = useRef(new Set<string>())
  const inFlight = useRef(false)
  // A press that arrived while an automatic request was out: it is sent (or answered) when that one ends.
  const pendingManual = useRef(false)
  const watching = useRef<string | null>(null)
  // Whether the user's own press led to the attempt watched now (a press can join an automatic one).
  const initiated = useRef(false)
  const generation = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const hiddenAt = useRef<number | null>(null)
  const mounted = useRef(true)
  const tokens = useRef(0)
  // The latest phase, for the callbacks' own checks (never read while rendering).
  const current = useRef<SyncPhase>({ kind: 'unknown' })
  const admitRef = useRef<(trigger: SyncTrigger) => void>(() => undefined)
  // Read through a function: a check after an `await` must see the ref as it is then.
  const alive = useCallback((): boolean => mounted.current, [])

  const update = useCallback((next: SyncPhase): void => {
    current.current = next
    setPhase(next)
  }, [])

  const nextToken = useCallback((): number => {
    tokens.current += 1
    return tokens.current
  }, [])

  const stopWatching = useCallback((): void => {
    generation.current += 1
    watching.current = null
    if (timer.current !== null) clearTimeout(timer.current)
    timer.current = null
  }, [])

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      stopWatching()
    }
  }, [stopWatching])

  const end = useCallback(
    (job: SyncJob): void => {
      const userInitiated = initiated.current
      stopWatching()
      update({ kind: 'ended', job, userInitiated, token: nextToken() })
      reload({ quiet: true })
    },
    [nextToken, reload, stopWatching, update],
  )

  /** Reads the job's state every two seconds while it is in progress; three failed reads in a row stop. */
  const watch = useCallback(
    (job: SyncJob, userInitiated: boolean): void => {
      stopWatching()
      watching.current = job.jobId
      initiated.current = userInitiated
      const mine = generation.current
      let followed = job
      let failures = 0
      const latest = (): boolean => mounted.current && generation.current === mine
      const tick = (): void => {
        timer.current = setTimeout(() => {
          void read()
        }, SYNC_POLL_MS)
      }
      const read = async (): Promise<void> => {
        try {
          const response = await timedFetch(DASHBOARD_SYNC_PATH, {
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { Accept: 'application/json' },
          })
          if (!latest()) return
          if (response.status === 401 || response.status === 403) {
            stopWatching()
            // Only the user's own press is answered with the refusal; an automatic one stays silent.
            update(
              initiated.current
                ? {
                    kind: 'denied',
                    cause: response.status === 401 ? 'signedOut' : 'refused',
                    csrfToken,
                  }
                : { kind: 'idle' },
            )
            return
          }
          const parsed = response.status === 200 ? parseState(await response.json()) : null
          if (!latest()) return
          if (parsed === null) throw new Error('sync state unreadable')
          failures = 0
          setManual(parsed.manual)
          const now = parsed.job
          if (now?.jobId !== followed.jobId) {
            if (inProgress(now)) {
              // Another attempt is the plan's one in progress now (another tab): follow it.
              followed = now
              watching.current = now.jobId
              update({ kind: 'syncing', job: now, userInitiated: initiated.current })
              tick()
              return
            }
            // The attempt is gone from view and none is in progress: the dashboard says what is there.
            stopWatching()
            update({ kind: 'idle' })
            reload({ quiet: true })
            return
          }
          if (inProgress(now)) {
            update({ kind: 'syncing', job: now, userInitiated: initiated.current })
            tick()
            return
          }
          end(now)
        } catch {
          if (!latest()) return
          failures += 1
          if (failures >= SYNC_POLL_FAILURES) {
            stopWatching()
            // The server's last word on the button may no longer hold: the next press asks it again.
            setManual(null)
            update({ kind: 'stalled', userInitiated: initiated.current, token: nextToken() })
            return
          }
          tick()
        }
      }
      update({ kind: 'syncing', job, userInitiated })
      tick()
    },
    [csrfToken, end, nextToken, reload, stopWatching, update],
  )

  /** Sends one admission. The answer decides everything; the request itself changes nothing on screen. */
  const admit = useCallback(
    (trigger: SyncTrigger): void => {
      const userInitiated = trigger === 'MANUAL'
      const now = current.current
      if (csrfToken === null || watching.current !== null) return
      if (now.kind === 'denied' && now.csrfToken === csrfToken) return
      if (inFlight.current) {
        // A press behind an automatic request: shown at once, sent when that request ends.
        if (userInitiated) {
          pendingManual.current = true
          update({ kind: 'submitting' })
        }
        return
      }
      inFlight.current = true
      if (userInitiated) update({ kind: 'submitting' })
      void (async () => {
        try {
          const response = await timedFetch(DASHBOARD_SYNC_PATH, {
            method: 'POST',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: {
              [CSRF_HEADER]: csrfToken,
              'Content-Type': 'application/json',
              Accept: 'application/json',
            },
            body: JSON.stringify({ trigger }),
          })
          if (!alive()) return
          if (response.status === 401 || response.status === 403) {
            if (userInitiated) {
              update({
                kind: 'denied',
                cause: response.status === 401 ? 'signedOut' : 'refused',
                csrfToken,
              })
            } else if (!pendingManual.current) {
              update({ kind: 'idle' })
            }
            return
          }
          const answer =
            response.status === 200 || response.status === 202
              ? parseAnswer(await response.json())
              : null
          if (!alive()) return
          if (answer === null) throw new Error('sync admission unreadable')
          setManual(answer.manual)
          if (inProgress(answer.job)) {
            // A new attempt, or the one already in progress (another tab, another press): watch it. A press that
            // waited behind this automatic request takes it as its own answer.
            const joined = userInitiated || pendingManual.current
            pendingManual.current = false
            watch(answer.job, joined)
            reload({ quiet: true })
            return
          }
          // A press waiting behind this request is sent next; its answer is the one to show.
          if (pendingManual.current) return
          update({
            kind: 'answered',
            outcome: answer.outcome,
            reason: answer.reason,
            userInitiated,
          })
        } catch {
          if (!alive()) return
          if (pendingManual.current) return
          // Only the user's own press is answered with a failure; an automatic one stays silent.
          update(userInitiated ? { kind: 'submitFailed', token: nextToken() } : { kind: 'idle' })
        } finally {
          inFlight.current = false
          if (pendingManual.current && alive()) {
            pendingManual.current = false
            admitRef.current('MANUAL')
          }
        }
      })()
    },
    [alive, csrfToken, nextToken, reload, update, watch],
  )

  useEffect(() => {
    admitRef.current = admit
  }, [admit])

  // A cooldown ends on its own: once its seconds have passed, the button's availability is unknown again and
  // the next press asks the server, which decides. Nothing is sent when it ends, and nothing says "N seconds"
  // after it.
  useEffect(() => {
    if (manual?.available !== false || manual.reason !== 'COOLDOWN') return
    const seconds = manual.retryAfterSeconds ?? 1
    const ended = setTimeout(
      () => {
        setManual((shown) => (shown === manual ? null : shown))
        const now = current.current
        if (now.kind === 'answered' && now.outcome === 'COOLDOWN') update({ kind: 'idle' })
      },
      Math.max(seconds, 1) * 1000,
    )
    return () => {
      clearTimeout(ended)
    }
  }, [manual, update])

  // Entry: once per plan, when its first ready bundle arrives.
  useEffect(() => {
    if (!firstReady || planId === null || csrfToken === null) return
    if (entered.current.has(planId)) return
    entered.current.add(planId)
    admit('DASHBOARD_ENTRY')
  }, [admit, csrfToken, firstReady, planId])

  // Reactivation: the tab was hidden for at least five minutes and is visible again.
  useEffect(() => {
    const changed = (): void => {
      if (document.visibilityState === 'hidden') {
        hiddenAt.current = performance.now()
        return
      }
      const since = hiddenAt.current
      hiddenAt.current = null
      if (since === null || performance.now() - since < REACTIVATION_HIDDEN_MS) return
      if (planId !== null) admit('TAB_REACTIVATION')
    }
    document.addEventListener('visibilitychange', changed)
    return () => {
      document.removeEventListener('visibilitychange', changed)
    }
  }, [admit, planId])

  // A refusal holds for the session it came from: a new session token lifts it.
  const effective = useMemo<SyncPhase>(
    () => (phase.kind === 'denied' && phase.csrfToken !== csrfToken ? IDLE : phase),
    [csrfToken, phase],
  )
  // A publication counts as shown once the dashboard's ready bundle is its version, or a later one.
  const shown =
    effective.kind === 'ended' &&
    effective.job.status === 'PUBLISHED' &&
    effective.job.pointerVersion !== null &&
    dashboard.view.kind === 'READY' &&
    dashboard.view.result.pointerVersion >= effective.job.pointerVersion

  const requestManual = useCallback((): void => {
    if (planId === null) return
    const state: DashboardSync = {
      phase: effective,
      manual,
      focus: null,
      requestManual: () => undefined,
    }
    if (!manualEnabled(state)) return
    admit('MANUAL')
  }, [admit, effective, manual, planId])

  return { phase: effective, manual, focus: focusOf(effective, shown), shown, requestManual }
}

/**
 * The sentence the screen's single status region reads fourth, after the dashboard's, the evidence's and the
 * history's, or `null` when there is nothing to say. Each change is one sentence, said once.
 */
export function syncAnnouncement(sync: DashboardSync): string | null {
  const { phase } = sync
  switch (phase.kind) {
    case 'unknown':
    case 'idle':
      return null
    case 'submitting':
    case 'syncing':
      return copy.status.syncing
    case 'ended': {
      const { job } = phase
      const reason = copy.failure(job.failureCode ?? '')
      // A publication is said once the dashboard shows it; until then (or if its reload failed) nothing is.
      if (job.status === 'PUBLISHED') return sync.shown === true ? copy.status.published : null
      if (job.status === 'REVIEW_REQUIRED') return copy.status.review
      if (job.status === 'BLOCKED') {
        return job.failureCode === 'LONG_GAP_CHOICE_REQUIRED'
          ? copy.status.longGap
          : copy.status.blocked(reason.cause)
      }
      return copy.status.failed(reason.cause)
    }
    case 'answered':
      if (phase.outcome === 'LONG_GAP') return copy.status.longGap
      if (!phase.userInitiated) return null
      if (phase.outcome === 'COOLDOWN') {
        // Only while the server's cooldown still holds: never "N seconds" after it ended.
        return sync.manual?.reason === 'COOLDOWN'
          ? copy.status.cooldown(sync.manual.retryAfterSeconds ?? 1)
          : null
      }
      if (phase.outcome === 'NOT_AVAILABLE') return copy.status.unavailable(phase.reason ?? '')
      return null
    case 'stalled':
      return copy.status.stalled
    case 'submitFailed':
      return copy.status.submitFailed
    case 'denied':
      return phase.cause === 'signedOut' ? copy.status.signedOut : copy.status.denied
  }
}
