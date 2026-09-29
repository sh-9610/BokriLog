import { useCallback, useEffect, useRef, useState } from 'react'

import { CSRF_HEADER } from '../content/auth'
import { DASHBOARD_SYNC_PATH } from '../content/planSync'
import {
  bundleOf,
  inProgress,
  parseSyncJob,
  record,
  type Dashboard,
  type DashboardView,
  type SyncJob,
  type SyncTrigger,
} from './useDashboard'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/** While a sync job is followed, the dashboard is read again this often (SYNC-UX-1.0). */
export const SYNC_POLL_INTERVAL_MS = 2_000

/**
 * A followed job is read again for at most this much visible time (hidden time does not count); then the
 * screen stops asking and says so.
 */
export const SYNC_POLL_LIMIT_MS = 3 * 60_000

/** A tab that was hidden at least this long asks for a sync when it is shown again (SYNC-UX-1.0). */
export const VISIBILITY_HIDDEN_MIN_MS = 5 * 60_000

/**
 * What the sync is doing, as the screen says it. `running` from the moment the user presses `동기화` (before
 * the answer) until the followed job ends. The endings of a followed job are `succeeded`, `failed`, `review`
 * and `blocked`; the admission's own endings are `cooldown`, `longGap`, `unavailable` and `error`; `stalled`
 * is a job the screen stopped following (its 3 minutes of visible time ran out). `idle` has nothing to say.
 */
export type SyncPhase =
  | 'idle'
  | 'running'
  | 'succeeded'
  | 'failed'
  | 'review'
  | 'blocked'
  | 'cooldown'
  | 'longGap'
  | 'unavailable'
  | 'error'
  | 'stalled'

type Outcome = Exclude<SyncPhase, 'idle' | 'running'>

/** A request to move focus. Only a sync the user pressed asks for one (WCAG 3.2: no unexpected change of context). */
export interface SyncFocus {
  readonly target: 'heading' | 'summary'
  readonly serial: number
}

export interface DashboardSync {
  readonly phase: SyncPhase
  /** A manual request is in flight, or a job is followed: the control shows `동기화 중`. */
  readonly running: boolean
  /** The followed job was found in progress, not asked for from this tab (another tab, a reload). */
  readonly followingElsewhere: boolean
  /** Whether pressing `동기화` asks for a sync now. When not, the button is `aria-disabled`. */
  readonly canRequest: boolean
  /**
   * Seconds until a manual sync is admitted again: the server's relative value, counted down on this device.
   * The screen never compares a server time with its own clock.
   */
  readonly cooldownSeconds: number | null
  /** The last focus move the sync asked for. */
  readonly focus: SyncFocus | null
  /** Asks for a manual sync. Ignored when `canRequest` is false, or while a manual request is in flight. */
  readonly request: () => void
}

/** The admission's answer (`POST /api/dashboard/sync`). */
export type SyncAdmissionOutcome = 'ACCEPTED' | 'JOINED' | 'COOLDOWN' | 'NOT_DUE' | 'LONG_GAP'

export interface SyncAdmission {
  readonly outcome: SyncAdmissionOutcome
  readonly job: SyncJob | null
  readonly retryAfterSeconds: number | null
}

const ADMISSION_OUTCOMES: readonly string[] = [
  'ACCEPTED',
  'JOINED',
  'COOLDOWN',
  'NOT_DUE',
  'LONG_GAP',
]

/**
 * An admission body, or `null` when it is not one whole answer. An admitted or joined job comes with that
 * job; a cool-down comes with the seconds until a manual sync is admitted again.
 */
export function parseAdmission(value: unknown): SyncAdmission | null {
  const row = record(value)
  if (row === null) return null
  const { outcome, job, retryAfterSeconds } = row
  if (typeof outcome !== 'string' || !ADMISSION_OUTCOMES.includes(outcome)) return null
  const parsedJob = job === null || job === undefined ? null : parseSyncJob(job)
  if (parsedJob === null && job !== null && job !== undefined) return null
  const retry = retryAfterSeconds ?? null
  if (retry !== null && !(Number.isInteger(retry) && (retry as number) > 0)) return null
  const admission: SyncAdmission = {
    outcome: outcome as SyncAdmissionOutcome,
    job: parsedJob,
    retryAfterSeconds: retry as number | null,
  }
  if ((admission.outcome === 'ACCEPTED' || admission.outcome === 'JOINED') && parsedJob === null) {
    return null
  }
  return admission.outcome === 'COOLDOWN' && retry === null ? null : admission
}

/** The screen's reading of one admission answer. */
type Answer =
  | { readonly kind: 'admitted'; readonly admission: SyncAdmission }
  | { readonly kind: 'denied' }
  | { readonly kind: 'noPlan' }
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'error' }

async function jsonOf(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

/**
 * What an admission answer means here. A refusal (403, the account or the CSRF/Origin check) or a gone
 * session (401) is `denied`; a 409 is told apart by its stable code; any other answer that is not a whole
 * admission is an `error` — the request's fate is not known.
 */
async function readAdmission(response: Response): Promise<Answer> {
  if (response.status === 401 || response.status === 403) return { kind: 'denied' }
  if (response.status === 409) {
    const code = record(await jsonOf(response))?.code
    if (code === 'NO_ACTIVE_PLAN') return { kind: 'noPlan' }
    return code === 'SYNC_UNAVAILABLE' ? { kind: 'unavailable' } : { kind: 'error' }
  }
  if (response.status !== 200 && response.status !== 202) return { kind: 'error' }
  const admission = parseAdmission(await jsonOf(response))
  return admission === null ? { kind: 'error' } : { kind: 'admitted', admission }
}

/**
 * A job the screen is following, from its admission (or its discovery) to its ending. `budget` is the
 * identity of one following: the 3-minute limit belongs to it, so following the same job again later starts a
 * fresh budget, while a following that moves on to the next job in progress keeps its own.
 */
interface Followed {
  readonly jobId: string
  readonly admittedAt: string
  /** The user pressed `동기화` for it: its ending moves focus. */
  readonly manual: boolean
  /** Asked for from this tab (an admission answer), or found in progress in a read (another tab, a reload). */
  readonly origin: 'here' | 'elsewhere'
  /** The view shown when following began. Only a later answer can end it. */
  readonly since: DashboardView
  readonly budget: object
}

const ENDINGS: Readonly<Record<string, Outcome>> = {
  SUCCEEDED: 'succeeded',
  FAILED: 'failed',
  NEEDS_REVIEW: 'review',
  BLOCKED: 'blocked',
}

/** An outcome, and whether it outlives the next read the user or the session asks for (P12). */
interface Said {
  readonly phase: Outcome
  readonly survivesReload: boolean
}

/** What one new dashboard view changes about the sync. `undefined` fields are left as they are. */
interface Judgement {
  readonly followed: Followed | null
  readonly outcome?: Said | null
  readonly cooldown?: number | null
  readonly focus: SyncFocus['target'] | null
}

/**
 * Judges a new dashboard view (a pure function of it, what is followed, what was said and which jobs the
 * screen gave up on). A job that ended in the view ends the following, with focus for a sync the user pressed
 * — the dashboard heading after a new bundle, the sync's error summary otherwise. A job still in progress
 * keeps it; a job in progress the screen is not following is followed quietly, unless the screen already gave
 * up on it. A view that is not an answer about the job — the one following began with, an answer read before
 * the job was admitted, a session re-check — changes nothing, and neither does a read that did not answer
 * (`lastGood`, `retryable`): following goes on until the job ends or its budget does. A plan or an account
 * that is gone ends everything. A read that shows a sync is available again ends an `unavailable` outcome.
 */
function judge(
  view: DashboardView,
  followed: Followed | null,
  said: Said | null,
  gaveUp: readonly string[],
): Judgement {
  const keep: Judgement = { followed, focus: null }
  switch (view.kind) {
    case 'checking':
    case 'lastGood':
    case 'retryable':
      return keep
    case 'denied':
    case 'NO_PLAN':
    case 'PREPARING':
      return { followed: null, outcome: null, cooldown: null, focus: null }
    case 'READY': {
      const { sync } = view
      const cooldown = sync.availability === 'COOLDOWN' ? sync.retryAfterSeconds : null
      const outcome =
        said?.phase === 'unavailable' && sync.availability !== 'UNAVAILABLE' ? null : undefined
      const latest = sync.latest
      if (followed === null) {
        const adopt =
          sync.availability === 'IN_PROGRESS' &&
          latest !== null &&
          inProgress(latest) &&
          !gaveUp.includes(latest.jobId)
        if (!adopt) return { followed: null, outcome, cooldown, focus: null }
        return {
          followed: {
            jobId: latest.jobId,
            admittedAt: latest.admittedAt,
            manual: false,
            origin: 'elsewhere',
            since: view,
            budget: {},
          },
          outcome,
          cooldown,
          focus: null,
        }
      }
      if (view === followed.since || latest === null) return { ...keep, outcome, cooldown }
      const ours = latest.jobId === followed.jobId
      // An answer read before the job was admitted still shows an earlier job: it says nothing about ours.
      if (!ours && Date.parse(latest.admittedAt) < Date.parse(followed.admittedAt)) {
        return { ...keep, outcome, cooldown }
      }
      if (inProgress(latest)) {
        return {
          followed: ours
            ? followed
            : { ...followed, jobId: latest.jobId, admittedAt: latest.admittedAt },
          outcome,
          cooldown,
          focus: null,
        }
      }
      // An ending this screen does not know is one that published nothing it can vouch for.
      const ending = ENDINGS[latest.status] ?? 'failed'
      return {
        followed: null,
        outcome: { phase: ending, survivesReload: false },
        cooldown,
        focus: followed.manual ? (ending === 'succeeded' ? 'heading' : 'summary') : null,
      }
    }
  }
}

/** The next focus request, numbered after the current one. */
function focusOn(target: SyncFocus['target']): (current: SyncFocus | null) => SyncFocus {
  return (current) => ({ target, serial: (current?.serial ?? 0) + 1 })
}

/** The outcome said once, and dropped by the next read the user or the session asks for. */
function once(phase: Outcome): Said {
  return { phase, survivesReload: false }
}

/**
 * The user-time incremental sync of the shown plan (Story 2.11), beside the dashboard it updates.
 *
 * Three triggers ask the server for one sync job (`POST /api/dashboard/sync`): the first `READY` answer of this
 * mount (`ENTRY`, once); the document becoming visible after it was hidden at least 5 minutes (`VISIBILITY`,
 * judged here — the server cannot know it — and only on the first ready answer after the return); and the
 * `동기화` button (`MANUAL`). The two automatic triggers are sent only while the shown bundle says a sync is
 * available; the server still decides whether they are due. There is no scheduled or background sync, and no
 * sync is retried automatically.
 *
 * A manual press shows `동기화 중` at once, before the answer, and blocks a second press (a ref, so even two
 * presses in one tick send one request). An admitted or joined job is followed — so is a job the server shows
 * in progress (another tab, a reload), unless the screen already gave up on it: the dashboard is read again
 * quietly every 2 seconds while the document is visible. A read that does not answer does not end the
 * following. It ends when an answer shows the job ended — a new bundle then replaces the view once (the
 * dashboard's own rule) — or when its budget of 3 minutes of visible time runs out (`stalled`, and that job is
 * not followed again by itself). A sync the user pressed moves focus when it ends: to the dashboard heading
 * after a new bundle, to the sync's error summary otherwise. The cool-down counts down from the server's
 * relative seconds. A refusal (403) or a gone session (401) asks the dashboard to read again, and the
 * dashboard's own read decides what stays on screen; a manual one also says it could not be handled (a CSRF or
 * Origin refusal leaves the account readable, and must not be silent).
 *
 * The session's own re-check when a tab is shown again (Story 1.5) is left as it is: while it runs the view is
 * `checking`, following pauses, and it resumes with the next answer.
 */
export function useDashboardSync(dashboard: Dashboard, csrfToken: string | null): DashboardSync {
  const { view, loading } = dashboard
  const [requesting, setRequesting] = useState(false)
  const [followed, setFollowed] = useState<Followed | null>(null)
  const [said, setSaid] = useState<Said | null>(null)
  const [cooldown, setCooldown] = useState<number | null>(null)
  const [focus, setFocus] = useState<SyncFocus | null>(null)
  const [gaveUp, setGaveUp] = useState<readonly string[]>([])
  const [visible, setVisible] = useState(() => document.visibilityState !== 'hidden')
  const [seen, setSeen] = useState(view)
  const [wasLoading, setWasLoading] = useState(loading)

  const live = useRef(true)
  const viewRef = useRef(view)
  const dashboardRef = useRef(dashboard)
  const csrfRef = useRef(csrfToken)
  const followedRef = useRef(followed)
  const entrySent = useRef(false)
  const manualInFlight = useRef(false)
  const posts = useRef(0)
  const hiddenAt = useRef<number | null>(null)
  // The view shown when a long-hidden tab came back; the first answer after it decides `VISIBILITY`.
  const visibilityDue = useRef<DashboardView | null>(null)
  // What is left of the 3-minute budget of the following it belongs to.
  const budget = useRef<{ readonly owner: object; remaining: number } | null>(null)

  /*
   * Adjusting state to changed props during render (React's documented pattern, as `useDashboard` does).
   * A read the user or the session asked for has started: the last sync's sentence has been said and is not
   * said again after it (unless it was said to outlive exactly this read). Then each new view is judged once.
   */
  if (loading !== wasLoading) {
    setWasLoading(loading)
    if (loading && said !== null) setSaid(said.survivesReload ? once(said.phase) : null)
  }
  if (view !== seen) {
    setSeen(view)
    const next = judge(view, followed, said, gaveUp)
    if (next.followed !== followed) setFollowed(next.followed)
    if (next.outcome !== undefined) setSaid(next.outcome)
    if (next.cooldown !== undefined) setCooldown(next.cooldown)
    if (next.focus !== null) setFocus(focusOn(next.focus))
  }

  // The latest values for the callbacks below. First, so every later effect of this commit sees them.
  useEffect(() => {
    viewRef.current = view
    dashboardRef.current = dashboard
    csrfRef.current = csrfToken
    followedRef.current = followed
  })

  // An answer that arrives after unmount changes nothing (the request itself is left to finish).
  useEffect(() => {
    live.current = true
    return () => {
      live.current = false
    }
  }, [])

  /**
   * Sends one admission. Every state change happens after the answer: this is also called from effects. A
   * manual request marks itself in flight synchronously, so a second press in the same tick is dropped.
   */
  const post = useCallback((trigger: SyncTrigger): void => {
    const token = csrfRef.current
    if (token === null) return
    const manual = trigger === 'MANUAL'
    const controller = new AbortController()
    posts.current += 1
    if (manual) manualInFlight.current = true
    const deadline = window.setTimeout(() => {
      controller.abort()
    }, SESSION_REQUEST_TIMEOUT_MS)
    void (async () => {
      let answer: Answer
      try {
        const response = await fetch(DASHBOARD_SYNC_PATH, {
          method: 'POST',
          credentials: 'same-origin',
          cache: 'no-store',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
            'Idempotency-Key': crypto.randomUUID(),
            [CSRF_HEADER]: token,
          },
          body: JSON.stringify({ trigger }),
          signal: controller.signal,
        })
        answer = await readAdmission(response)
      } catch {
        // A network failure, a lost answer or the time limit: whether it was admitted is not known.
        answer = { kind: 'error' }
      } finally {
        window.clearTimeout(deadline)
        posts.current -= 1
        if (manual) manualInFlight.current = false
      }
      if (!live.current) return
      if (manual) setRequesting(false)
      switch (answer.kind) {
        case 'denied':
          setFollowed(null)
          setCooldown(null)
          if (manual) {
            // Said through the read below: if the account itself is refused, that read's denied view wins.
            setSaid({ phase: 'error', survivesReload: true })
            setFocus(focusOn('summary'))
          } else {
            setSaid(null)
          }
          dashboardRef.current.reload()
          return
        case 'noPlan':
          setFollowed(null)
          setSaid(null)
          dashboardRef.current.reload()
          return
        case 'unavailable':
          // One quiet read brings the server's reason; the screen does not ask again by itself.
          dashboardRef.current.refresh()
          if (manual) {
            setSaid(once('unavailable'))
            setFocus(focusOn('summary'))
          }
          return
        case 'error':
          // An automatic trigger says nothing: the dashboard's own trust state explains what is shown.
          if (manual) {
            setSaid(once('error'))
            setFocus(focusOn('summary'))
          }
          return
        case 'admitted': {
          const { outcome: admitted, job, retryAfterSeconds } = answer.admission
          if ((admitted === 'ACCEPTED' || admitted === 'JOINED') && job !== null) {
            // Whatever the view is now: the next answer about the job decides. The same job already followed
            // stays one following (a manual press is never forgotten by an automatic answer for it).
            const since = viewRef.current
            setFollowed((current) =>
              current?.jobId === job.jobId
                ? { ...current, manual: current.manual || manual, origin: 'here' }
                : {
                    jobId: job.jobId,
                    admittedAt: job.admittedAt,
                    manual,
                    origin: 'here',
                    since,
                    budget: {},
                  },
            )
            return
          }
          if (admitted === 'COOLDOWN') {
            setCooldown(retryAfterSeconds)
            if (manual) {
              setSaid(once('cooldown'))
              setFocus(focusOn('summary'))
            }
            return
          }
          if (admitted === 'LONG_GAP') {
            setSaid(once('longGap'))
            if (manual) setFocus(focusOn('summary'))
            dashboardRef.current.refresh()
          }
          // NOT_DUE: nothing was admitted, and nothing changes.
          return
        }
      }
    })()
  }, [])

  // ENTRY: once per mount, on the first ready answer.
  useEffect(() => {
    if (entrySent.current || view.kind !== 'READY' || loading || csrfToken === null) return
    entrySent.current = true
    if (view.sync.availability === 'AVAILABLE') post('ENTRY')
  }, [view, loading, csrfToken, post])

  // The document's visibility: polling and the budget pause while hidden; a long absence makes `VISIBILITY` due.
  useEffect(() => {
    if (document.visibilityState === 'hidden') hiddenAt.current = Date.now()
    const onVisibilityChange = (): void => {
      if (document.visibilityState === 'hidden') {
        hiddenAt.current ??= Date.now()
        setVisible(false)
        return
      }
      const since = hiddenAt.current
      hiddenAt.current = null
      if (since !== null && Date.now() - since >= VISIBILITY_HIDDEN_MIN_MS) {
        visibilityDue.current = viewRef.current
      }
      setVisible(true)
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [])

  /*
   * VISIBILITY: after a long absence, decided on the first answer read after the tab came back — and only on
   * that one, sent or not, so it can never fire on a much later, unrelated answer.
   */
  useEffect(() => {
    const after = visibilityDue.current
    if (after === null || view === after || view.kind === 'checking' || loading) return
    visibilityDue.current = null
    if (view.kind !== 'READY' || csrfToken === null) return
    if (view.sync.availability !== 'AVAILABLE' || followed !== null || requesting) return
    if (posts.current > 0) return
    post('VISIBILITY')
  }, [view, loading, csrfToken, followed, requesting, post])

  // Following: one quiet read 2 seconds after each answer — a failed one too — while visible.
  useEffect(() => {
    if (followed === null || !visible || loading) return
    if (view.kind === 'checking' || view.kind === 'denied') return
    const timer = window.setTimeout(() => {
      // Not after the budget ended the following in the same turn, before this effect could be cleaned up.
      if (followedRef.current !== null) dashboardRef.current.refresh()
    }, SYNC_POLL_INTERVAL_MS)
    return () => {
      window.clearTimeout(timer)
    }
  }, [followed, visible, view, loading])

  /*
   * The following's budget: 3 minutes of visible time, on a timer of its own (whatever the reads do). Hidden
   * time does not count; following the same job again later is a new following with a new budget.
   */
  const owner = followed?.budget ?? null
  useEffect(() => {
    if (owner === null || !visible) return
    let left = budget.current
    if (left?.owner !== owner) {
      left = { owner, remaining: SYNC_POLL_LIMIT_MS }
      budget.current = left
    }
    const slot = left
    const startedAt = Date.now()
    const timer = window.setTimeout(() => {
      slot.remaining = 0
      const job = followedRef.current
      if (job?.budget !== owner) return
      followedRef.current = null
      setFollowed(null)
      setSaid(once('stalled'))
      setGaveUp((current) => [...current, job.jobId])
      if (job.manual) setFocus(focusOn('summary'))
    }, slot.remaining)
    return () => {
      window.clearTimeout(timer)
      slot.remaining = Math.max(0, slot.remaining - (Date.now() - startedAt))
    }
  }, [owner, visible])

  // The cool-down, one second at a time; when it ends, so does the cool-down outcome.
  useEffect(() => {
    if (cooldown === null) return
    const timer = window.setTimeout(() => {
      setCooldown(cooldown <= 1 ? null : cooldown - 1)
      if (cooldown <= 1) setSaid((current) => (current?.phase === 'cooldown' ? null : current))
    }, 1_000)
    return () => {
      window.clearTimeout(timer)
    }
  }, [cooldown])

  const outcome = said?.phase ?? null
  const running = requesting || followed !== null
  const phase: SyncPhase = running ? 'running' : (outcome ?? 'idle')
  const availability = bundleOf(view)?.sync.availability ?? null
  const canRequest =
    availability !== null &&
    !running &&
    cooldown === null &&
    availability !== 'IN_PROGRESS' &&
    availability !== 'UNAVAILABLE' &&
    availability !== 'LONG_GAP' &&
    outcome !== 'longGap'

  const request = useCallback((): void => {
    if (manualInFlight.current || !canRequest) return
    if (csrfRef.current === null) {
      setSaid(once('error'))
      setFocus(focusOn('summary'))
      return
    }
    setSaid(null)
    setRequesting(true)
    post('MANUAL')
  }, [canRequest, post])

  return {
    phase,
    running,
    followingElsewhere: followed?.origin === 'elsewhere',
    canRequest,
    cooldownSeconds: cooldown,
    focus,
    request,
  }
}
