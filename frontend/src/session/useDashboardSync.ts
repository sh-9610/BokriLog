import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'

import { CSRF_HEADER } from '../content/auth'
import { DASHBOARD_SYNC_PATH } from '../content/planSync'
import { DASHBOARD_VERIFICATION_PATH, type VerificationKind } from '../content/planVerification'
import {
  bundleOf,
  inProgress,
  parseSyncJob,
  purposeOf,
  record,
  type Dashboard,
  type DashboardView,
  type SyncJob,
  type SyncPurpose,
  type SyncStatus,
  type SyncTrigger,
  type VerificationStatus,
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
 * What the job is doing, as the screen says it. `running` from the moment the user presses `동기화`, `정밀
 * 검증` or `기존 계획 계속` (before the answer) until the followed job ends. The endings of a followed job are
 * `succeeded`, `failed`, `review` and `blocked`; the admission's own endings are `cooldown`, `longGap`,
 * `unavailable` and `error`, and — for a verification or a catch-up (Story 2.12) — `notUseful` (the same basis
 * already ended in review), `stale` (the shown result is no longer the current one), `busy` (another job is in
 * progress), `notLongGap`, and `refused` (a refusal that does not fit what was asked: the screen's view is out of
 * date). `refreshed` follows `stale` once the screen's own quiet read has brought the current official result.
 * `stalled` is a job the screen stopped following (its 3 minutes of visible time ran out). `idle` has nothing to
 * say.
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
  | 'notUseful'
  | 'stale'
  | 'busy'
  | 'notLongGap'
  | 'refused'
  | 'refreshed'

type Outcome = Exclude<SyncPhase, 'idle' | 'running'>

/**
 * A request to move focus. Only a job the user pressed asks for one (WCAG 3.2: no unexpected change of
 * context): the dashboard heading after a new bundle, otherwise the summary of the control the job belongs
 * to — the sync control's (`summary`, which also holds the long-gap explanation and the catch-up) or the
 * verification control's (`verification`).
 */
export interface SyncFocus {
  readonly target: 'heading' | 'summary' | 'verification'
  readonly serial: number
}

export interface DashboardSync {
  readonly phase: SyncPhase
  /** Whose phase it is: the job that runs, or the one whose outcome is said. */
  readonly purpose: SyncPurpose
  /** A manual request is in flight, or a job is followed: the control of its purpose shows it runs. */
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
  /**
   * Whether pressing `정밀 검증` asks for a precision verification now (Story 2.12). Not while any job runs or any
   * admission — an automatic sync's too — is in flight.
   */
  readonly canVerify: boolean
  /** Whether pressing `기존 계획 계속` asks for the catch-up now (Story 2.12, after a long gap only; same rule). */
  readonly canCatchUp: boolean
  /**
   * An automatic sync admission (`ENTRY`, `VISIBILITY`) is in flight: until it answers, a verification or a
   * catch-up cannot be asked for, and the controls say why.
   */
  readonly admitting: boolean
  /** Seconds until a verification (or, after a long gap, the catch-up) is admitted again. */
  readonly verificationCooldownSeconds: number | null
  /** The last focus move a job asked for. */
  readonly focus: SyncFocus | null
  /** Asks for a manual sync. Ignored when `canRequest` is false, or while a manual request is in flight. */
  readonly request: () => void
  /**
   * Asks for a precision verification (`PRECISION`) or the catch-up (`CATCH_UP`) of the shown result.
   * Ignored when that one cannot be asked for now, or while a manual request is in flight.
   */
  readonly requestVerification: (kind: VerificationKind) => void
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

/** The outcomes a verification admission shares with the sync admission (`POST /api/dashboard/verification`). */
const VERIFICATION_OUTCOMES: readonly string[] = ['ACCEPTED', 'JOINED', 'COOLDOWN']

/**
 * A verification admission body (Story 2.12), or `null` when it is not one whole answer: an admitted or joined
 * job with that job, or a cool-down with its seconds (and the latest job, if any).
 */
export function parseVerificationAdmission(value: unknown): SyncAdmission | null {
  const admission = parseAdmission(value)
  return admission !== null && VERIFICATION_OUTCOMES.includes(admission.outcome) ? admission : null
}

/** A 409 of the verification admission that tells the screen something it can say. */
type Refusal = 'longGap' | 'notLongGap' | 'notUseful' | 'stale' | 'busy' | 'refused'

/**
 * The 409 codes each kind can meet, and what each says. A precision verification is refused after a long gap;
 * a catch-up when the gap is not long. Either is refused when the same basis already ended in review (Story
 * 2.12: a long gap with a persistent review of this basis makes the catch-up not useful either), when the shown
 * result is stale, or when another job runs.
 */
const REFUSALS: Readonly<Record<VerificationKind, Readonly<Record<string, Refusal>>>> = {
  PRECISION: {
    LONG_GAP: 'longGap',
    VERIFICATION_NOT_USEFUL: 'notUseful',
    REFERENCE_STALE: 'stale',
    JOB_IN_PROGRESS: 'busy',
  },
  CATCH_UP: {
    NOT_LONG_GAP: 'notLongGap',
    VERIFICATION_NOT_USEFUL: 'notUseful',
    REFERENCE_STALE: 'stale',
    JOB_IN_PROGRESS: 'busy',
  },
}

/** Known codes that belong to the other kind: a stable refusal, said in general words, never an error. */
const MISMATCHED_REFUSALS: readonly string[] = ['LONG_GAP', 'NOT_LONG_GAP']

/** The screen's reading of one admission answer. */
type Answer =
  | { readonly kind: 'admitted'; readonly admission: SyncAdmission }
  | { readonly kind: 'denied' }
  | { readonly kind: 'noPlan' }
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'refused'; readonly phase: Refusal }
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
 * What a verification admission answer means here (Story 2.12). Like the sync's, a refusal or a gone session
 * is `denied`; a 409 is told apart by its stable code, read for the kind that was asked. A known code of the
 * other kind (a precision verification told the gap is not long, a catch-up told it is) is a general refusal;
 * a code this screen does not know — an idempotency conflict included, since every request carries a new key —
 * is an `error`.
 */
async function readVerificationAdmission(
  response: Response,
  kind: VerificationKind,
): Promise<Answer> {
  if (response.status === 401 || response.status === 403) return { kind: 'denied' }
  if (response.status === 409) {
    const code = record(await jsonOf(response))?.code
    if (code === 'NO_ACTIVE_PLAN') return { kind: 'noPlan' }
    if (code === 'VERIFICATION_UNAVAILABLE') return { kind: 'unavailable' }
    if (typeof code !== 'string') return { kind: 'error' }
    const table = REFUSALS[kind]
    const refusal = Object.hasOwn(table, code)
      ? table[code]
      : MISMATCHED_REFUSALS.includes(code)
        ? 'refused'
        : undefined
    return refusal === undefined ? { kind: 'error' } : { kind: 'refused', phase: refusal }
  }
  if (response.status !== 200 && response.status !== 202) return { kind: 'error' }
  const admission = parseVerificationAdmission(await jsonOf(response))
  return admission === null ? { kind: 'error' } : { kind: 'admitted', admission }
}

/** The shown result's reference: the selection token a verification admission carries (2.9's reference). */
interface Reference {
  readonly planId: string
  readonly pointerVersion: number
  readonly calculationId: string
}

/** One admission to send: a sync with its trigger, or a verification of the shown result. */
type Operation =
  | { readonly kind: 'sync'; readonly trigger: SyncTrigger }
  | {
      readonly kind: 'verification'
      readonly verification: VerificationKind
      readonly reference: Reference
    }

/**
 * A job the screen is following, from its admission (or its discovery) to its ending. `budget` is the
 * identity of one following: the 3-minute limit belongs to it, so following the same job again later starts a
 * fresh budget, while a following that moves on to the next job in progress keeps its own.
 */
interface Followed {
  readonly jobId: string
  readonly admittedAt: string
  /** What the job is for: which control shows it runs, and whose words its sentences use. */
  readonly purpose: SyncPurpose
  /** The user pressed `동기화`, `정밀 검증` or `기존 계획 계속` for it: its ending moves focus. */
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

/**
 * An outcome, the purpose of the job or request it belongs to, and whether it outlives the next read the user
 * or the session asks for (P12). A refusal because another job runs (`busy`) also keeps the bundle shown when it
 * was said, so the quiet read after it can tell whether that job replaced it.
 */
interface Said {
  readonly phase: Outcome
  readonly purpose: SyncPurpose
  readonly survivesReload: boolean
  readonly shown?: string | null
}

/** The identity of the bundle a view shows, or `null` when it shows none. */
function shownBundle(view: DashboardView): string | null {
  const result = bundleOf(view)?.result
  return result === undefined
    ? null
    : `${result.planId}/${String(result.pointerVersion)}/${result.calculationId}`
}

/** What one new dashboard view changes about the sync. `undefined` fields are left as they are. */
interface Judgement {
  readonly followed: Followed | null
  readonly outcome?: Said | null
  readonly cooldown?: number | null
  readonly verificationCooldown?: number | null
  readonly focus: SyncFocus['target'] | null
}

/** Where the summary of a job of this purpose is: the verification control's, or the sync control's. */
function summaryTarget(purpose: SyncPurpose): SyncFocus['target'] {
  return purpose === 'VERIFY' ? 'verification' : 'summary'
}

/**
 * The plan's newest job the view shows. The sync block and the verification block each name a latest job
 * (Story 2.12 keeps every purpose in one job table); the newer of the two is the one that decides.
 */
function newestJob(sync: SyncStatus, verification: VerificationStatus): SyncJob | null {
  const { latest: synced } = sync
  const { latest: verified } = verification
  if (synced === null) return verified
  if (verified === null || verified.jobId === synced.jobId) return verified ?? synced
  return Date.parse(verified.admittedAt) > Date.parse(synced.admittedAt) ? verified : synced
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
      return {
        followed: null,
        outcome: null,
        cooldown: null,
        verificationCooldown: null,
        focus: null,
      }
    case 'READY': {
      const { sync, verification } = view
      const cooldown = sync.availability === 'COOLDOWN' ? sync.retryAfterSeconds : null
      const verificationCooldown =
        verification.availability === 'COOLDOWN' || verification.availability === 'LONG_GAP'
          ? verification.retryAfterSeconds
          : null
      const stillUnavailable =
        said?.purpose === 'SYNC'
          ? sync.availability === 'UNAVAILABLE'
          : verification.availability === 'UNAVAILABLE'
      const outcome = said?.phase === 'unavailable' && !stillUnavailable ? null : undefined
      const blocks = { outcome, cooldown, verificationCooldown }
      const latest = newestJob(sync, verification)
      if (followed === null) {
        const adopt =
          (sync.availability === 'IN_PROGRESS' || verification.availability === 'IN_PROGRESS') &&
          latest !== null &&
          inProgress(latest) &&
          !gaveUp.includes(latest.jobId)
        if (!adopt) return { followed: null, ...blocks, focus: null }
        return {
          followed: {
            jobId: latest.jobId,
            admittedAt: latest.admittedAt,
            purpose: purposeOf(latest),
            manual: false,
            origin: 'elsewhere',
            since: view,
            budget: {},
          },
          ...blocks,
          focus: null,
        }
      }
      if (view === followed.since || latest === null) return { ...keep, ...blocks }
      const ours = latest.jobId === followed.jobId
      // An answer read before the job was admitted still shows an earlier job: it says nothing about ours.
      if (!ours && Date.parse(latest.admittedAt) < Date.parse(followed.admittedAt)) {
        return { ...keep, ...blocks }
      }
      if (inProgress(latest)) {
        return {
          followed: ours
            ? followed
            : {
                ...followed,
                jobId: latest.jobId,
                admittedAt: latest.admittedAt,
                purpose: purposeOf(latest),
              },
          ...blocks,
          focus: null,
        }
      }
      // An ending this screen does not know is one that published nothing it can vouch for.
      const ending = ENDINGS[latest.status] ?? 'failed'
      const purpose = purposeOf(latest)
      return {
        followed: null,
        outcome: { phase: ending, purpose, survivesReload: false },
        cooldown,
        verificationCooldown,
        focus: followed.manual
          ? ending === 'succeeded'
            ? 'heading'
            : summaryTarget(purpose)
          : null,
      }
    }
  }
}

/**
 * The outcome after a new view: the judgement's, else what the view ends of the one said (`undefined` keeps
 * it). A stale reference was said until the screen's own quiet read answered: this is that answer, so the
 * summary goes — and a new bundle is said once (`refreshed`). A refusal because another job runs lasts until an
 * answer shows the bundle: then that job is either followed (its own phase says it from now on) or over, and the
 * summary goes — with the bundle it replaced said once (`refreshed`) when it published one. A cool-down the view
 * no longer reports ends the cool-down outcome of its clock, like its countdown would.
 */
function settled(view: DashboardView, next: Judgement, said: Said | null): Said | null | undefined {
  if (next.outcome !== undefined) return next.outcome
  if (said === null || view.kind === 'checking') return undefined
  if (said.phase === 'stale') return view.kind === 'READY' ? say('refreshed', said.purpose) : null
  if (said.phase === 'busy') {
    if (view.kind !== 'READY') return undefined
    if (next.followed !== null) return null
    return shownBundle(view) === said.shown ? null : say('refreshed', said.purpose)
  }
  if (said.phase !== 'cooldown') return undefined
  const ended =
    said.purpose === 'SYNC' ? next.cooldown === null : next.verificationCooldown === null
  return ended ? null : undefined
}

/** The next focus request, numbered after the current one. */
function focusOn(target: SyncFocus['target']): (current: SyncFocus | null) => SyncFocus {
  return (current) => ({ target, serial: (current?.serial ?? 0) + 1 })
}

/** An outcome of a job of this purpose, dropped by the next read the user or the session asks for unless it survives it. */
function say(phase: Outcome, purpose: SyncPurpose, survivesReload = false): Said {
  return { phase, purpose, survivesReload }
}

/**
 * The user-time incremental sync of the shown plan (Story 2.11), beside the dashboard it updates, and the
 * precision verification and catch-up that share its job table (Story 2.12).
 *
 * Three triggers ask the server for one sync job (`POST /api/dashboard/sync`): the first `READY` answer of this
 * mount (`ENTRY`, once); the document becoming visible after it was hidden at least 5 minutes (`VISIBILITY`,
 * judged here — the server cannot know it — and only on the first ready answer after the return); and the
 * `동기화` button (`MANUAL`). The two automatic triggers are sent only while the shown bundle says a sync is
 * available; the server still decides whether they are due. There is no scheduled or background sync, and no
 * sync is retried automatically. A precision verification (`정밀 검증`) and, after a long gap, the catch-up
 * (`기존 계획 계속`) are asked for only by the user (`POST /api/dashboard/verification`), with the shown result's
 * reference as the selection token; neither is ever sent by itself.
 *
 * A manual press shows its job running at once, before the answer, and blocks a second press (a ref, so even
 * two presses in one tick send one request). An admitted or joined job is followed — so is a job the server
 * shows in progress (another tab, a reload), unless the screen already gave up on it: the dashboard is read
 * again quietly every 2 seconds while the document is visible. A read that does not answer does not end the
 * following. It ends when an answer shows the job ended — a new bundle then replaces the view once (the
 * dashboard's own rule) — or when its budget of 3 minutes of visible time runs out (`stalled`, and that job is
 * not followed again by itself). A job the user pressed moves focus when it ends: to the dashboard heading
 * after a new bundle, to its control's summary otherwise. The cool-downs count down from the server's relative
 * seconds. A refusal (403) or a gone session (401) asks the dashboard to read again, and the dashboard's own
 * read decides what stays on screen; a manual one also says it could not be handled (a CSRF or Origin refusal
 * leaves the account readable, and must not be silent). A verification refusal that changes what the server
 * shows (a long gap, a basis whose verification cannot help, a stale reference, a job in progress) is said once
 * and read once, quietly.
 *
 * The session's own re-check when a tab is shown again (Story 1.5) is left as it is: while it runs the view is
 * `checking`, following pauses, and it resumes with the next answer.
 */
export function useDashboardSync(dashboard: Dashboard, csrfToken: string | null): DashboardSync {
  const { view, loading } = dashboard
  const [requesting, setRequesting] = useState<SyncPurpose | null>(null)
  // Automatic sync admissions (`ENTRY`, `VISIBILITY`) in flight: a verification or catch-up waits for them.
  const [automatic, setAutomatic] = useState(0)
  const [followed, setFollowed] = useState<Followed | null>(null)
  const [said, setSaid] = useState<Said | null>(null)
  const [cooldown, setCooldown] = useState<number | null>(null)
  const [verificationCooldown, setVerificationCooldown] = useState<number | null>(null)
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
   * A read the user or the session asked for has started: the last job's sentence has been said and is not
   * said again after it (unless it was said to outlive exactly this read). Then each new view is judged once.
   */
  if (loading !== wasLoading) {
    setWasLoading(loading)
    if (loading && said !== null)
      setSaid(said.survivesReload ? say(said.phase, said.purpose) : null)
  }
  if (view !== seen) {
    setSeen(view)
    const next = judge(view, followed, said, gaveUp)
    if (next.followed !== followed) setFollowed(next.followed)
    const outcome = settled(view, next, said)
    if (outcome !== undefined) setSaid(outcome)
    if (next.cooldown !== undefined) setCooldown(next.cooldown)
    if (next.verificationCooldown !== undefined) setVerificationCooldown(next.verificationCooldown)
    if (next.focus !== null) setFocus(focusOn(next.focus))
  }

  /*
   * The latest values for the callbacks below. First, so every later effect of this commit sees them — a layout
   * effect, because the timers and the listener below are layout effects too: they start and stop in the commit
   * that changes what is followed (and a test that has seen that commit can advance its clock at once), and they
   * and their callbacks read the values of that same commit. As passive effects they came on a later task, which
   * under load came after code that had already seen the new state.
   *
   * What stays passive: the `live` marker and the two automatic admissions (`ENTRY`, `VISIBILITY`). Starting a
   * request is not something the commit shows, and its answer comes on a later task anyway.
   */
  useLayoutEffect(() => {
    viewRef.current = view
    dashboardRef.current = dashboard
    csrfRef.current = csrfToken
    followedRef.current = followed
  })

  // An answer that arrives after unmount changes nothing (the request itself is left to finish). Passive: it
  // guards answers that come on later tasks, not anything a commit shows.
  useEffect(() => {
    live.current = true
    return () => {
      live.current = false
    }
  }, [])

  /**
   * Sends one admission. Every state change happens after the answer — or, for the count of automatic
   * admissions in flight, in a microtask: this is also called from effects. A manual request marks itself in
   * flight synchronously, so a second press in the same tick is dropped; any admission in flight (`posts`)
   * drops a verification or catch-up press in the same tick.
   */
  const admit = useCallback((operation: Operation): void => {
    const token = csrfRef.current
    if (token === null) return
    const manual = operation.kind === 'verification' || operation.trigger === 'MANUAL'
    const purpose: SyncPurpose =
      operation.kind === 'sync'
        ? 'SYNC'
        : operation.verification === 'PRECISION'
          ? 'VERIFY'
          : 'CATCH_UP'
    const controller = new AbortController()
    posts.current += 1
    if (manual) manualInFlight.current = true
    // An automatic admission blocks a verification or a catch-up until it answers (`canVerify`, `canCatchUp`).
    // Counted from microtasks: admissions are also sent from effects, which must not set state synchronously.
    // The answer lowers the count from a microtask too, queued later, so the count never falls before it rose.
    else
      queueMicrotask(() => {
        if (live.current) setAutomatic((current) => current + 1)
      })
    const deadline = window.setTimeout(() => {
      controller.abort()
    }, SESSION_REQUEST_TIMEOUT_MS)
    void (async () => {
      let answer: Answer
      try {
        const response = await fetch(
          operation.kind === 'sync' ? DASHBOARD_SYNC_PATH : DASHBOARD_VERIFICATION_PATH,
          {
            method: 'POST',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: {
              'Content-Type': 'application/json',
              Accept: 'application/json',
              'Idempotency-Key': crypto.randomUUID(),
              [CSRF_HEADER]: token,
            },
            body: JSON.stringify(
              operation.kind === 'sync'
                ? { trigger: operation.trigger }
                : { kind: operation.verification, ...operation.reference },
            ),
            signal: controller.signal,
          },
        )
        answer =
          operation.kind === 'sync'
            ? await readAdmission(response)
            : await readVerificationAdmission(response, operation.verification)
      } catch {
        // A network failure, a lost answer or the time limit: whether it was admitted is not known.
        answer = { kind: 'error' }
      } finally {
        window.clearTimeout(deadline)
        posts.current -= 1
        if (manual) manualInFlight.current = false
      }
      if (!live.current) return
      if (manual) setRequesting(null)
      else
        queueMicrotask(() => {
          setAutomatic((current) => Math.max(0, current - 1))
        })
      const here = summaryTarget(purpose)
      switch (answer.kind) {
        case 'denied':
          setFollowed(null)
          setCooldown(null)
          setVerificationCooldown(null)
          if (manual) {
            // Said through the read below: if the account itself is refused, that read's denied view wins.
            setSaid(say('error', purpose, true))
            setFocus(focusOn(here))
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
            setSaid(say('unavailable', purpose))
            setFocus(focusOn(here))
          }
          return
        case 'refused':
          // Only a verification is refused this way, and only a pressed one: said once, then read once quietly
          // for what the server shows now. A long gap is explained by the sync control, whoever met it. A stale
          // reference's summary lasts until that read answers (then `refreshed` says the replacement once); so
          // does another job's, which keeps the bundle shown now to tell whether that job replaced it.
          setSaid({ ...say(answer.phase, purpose), shown: shownBundle(viewRef.current) })
          setFocus(focusOn(answer.phase === 'longGap' ? 'summary' : here))
          dashboardRef.current.refresh()
          return
        case 'error':
          // An automatic trigger says nothing: the dashboard's own trust state explains what is shown.
          if (manual) {
            setSaid(say('error', purpose))
            setFocus(focusOn(here))
          }
          return
        case 'admitted': {
          const { outcome: admitted, job, retryAfterSeconds } = answer.admission
          if ((admitted === 'ACCEPTED' || admitted === 'JOINED') && job !== null) {
            // Whatever the view is now: the next answer about the job decides. The same job already followed
            // stays one following (a manual press is never forgotten by an automatic answer for it). A sync
            // may join a verification in progress: the job decides the purpose, not the request.
            const since = viewRef.current
            setFollowed((current) =>
              current?.jobId === job.jobId
                ? {
                    ...current,
                    purpose: purposeOf(job),
                    manual: current.manual || manual,
                    origin: 'here',
                  }
                : {
                    jobId: job.jobId,
                    admittedAt: job.admittedAt,
                    purpose: purposeOf(job),
                    manual,
                    origin: 'here',
                    since,
                    budget: {},
                  },
            )
            return
          }
          if (admitted === 'COOLDOWN') {
            if (purpose === 'SYNC') setCooldown(retryAfterSeconds)
            else setVerificationCooldown(retryAfterSeconds)
            if (manual) {
              setSaid(say('cooldown', purpose))
              setFocus(focusOn(here))
            }
            return
          }
          if (admitted === 'LONG_GAP') {
            setSaid(say('longGap', purpose))
            if (manual) setFocus(focusOn('summary'))
            dashboardRef.current.refresh()
          }
          // NOT_DUE: nothing was admitted, and nothing changes.
          return
        }
      }
    })()
  }, [])

  // ENTRY: once per mount, on the first ready answer. Passive: it starts a request.
  useEffect(() => {
    if (entrySent.current || view.kind !== 'READY' || loading || csrfToken === null) return
    entrySent.current = true
    if (view.sync.availability === 'AVAILABLE') admit({ kind: 'sync', trigger: 'ENTRY' })
  }, [view, loading, csrfToken, admit])

  // The document's visibility: polling and the budget pause while hidden; a long absence makes `VISIBILITY` due.
  useLayoutEffect(() => {
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
   * that one, sent or not, so it can never fire on a much later, unrelated answer. Passive: it starts a request.
   */
  useEffect(() => {
    const after = visibilityDue.current
    if (after === null || view === after || view.kind === 'checking' || loading) return
    visibilityDue.current = null
    if (view.kind !== 'READY' || csrfToken === null) return
    if (view.sync.availability !== 'AVAILABLE' || followed !== null || requesting !== null) return
    if (posts.current > 0) return
    admit({ kind: 'sync', trigger: 'VISIBILITY' })
  }, [view, loading, csrfToken, followed, requesting, admit])

  // Following: one quiet read 2 seconds after each answer — a failed one too — while visible.
  useLayoutEffect(() => {
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
  useLayoutEffect(() => {
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
      setSaid(say('stalled', job.purpose))
      setGaveUp((current) => [...current, job.jobId])
      if (job.manual) setFocus(focusOn(summaryTarget(job.purpose)))
    }, slot.remaining)
    return () => {
      window.clearTimeout(timer)
      slot.remaining = Math.max(0, slot.remaining - (Date.now() - startedAt))
    }
  }, [owner, visible])

  // The sync's cool-down, one second at a time; when it ends, so does the sync's cool-down outcome.
  useLayoutEffect(() => {
    if (cooldown === null) return
    const timer = window.setTimeout(() => {
      setCooldown(cooldown <= 1 ? null : cooldown - 1)
      if (cooldown <= 1) {
        setSaid((current) =>
          current?.phase === 'cooldown' && current.purpose === 'SYNC' ? null : current,
        )
      }
    }, 1_000)
    return () => {
      window.clearTimeout(timer)
    }
  }, [cooldown])

  // The verification's (or the catch-up's) cool-down, the same way.
  useLayoutEffect(() => {
    if (verificationCooldown === null) return
    const timer = window.setTimeout(() => {
      setVerificationCooldown(verificationCooldown <= 1 ? null : verificationCooldown - 1)
      if (verificationCooldown <= 1) {
        setSaid((current) =>
          current?.phase === 'cooldown' && current.purpose !== 'SYNC' ? null : current,
        )
      }
    }, 1_000)
    return () => {
      window.clearTimeout(timer)
    }
  }, [verificationCooldown])

  const outcome = said?.phase ?? null
  const running = requesting !== null || followed !== null
  const phase: SyncPhase = running ? 'running' : (outcome ?? 'idle')
  const purpose: SyncPurpose = running
    ? (followed?.purpose ?? requesting ?? 'SYNC')
    : (said?.purpose ?? 'SYNC')
  const shown = bundleOf(view)
  const availability = shown?.sync.availability ?? null
  const verification = shown?.verification ?? null
  const canRequest =
    availability !== null &&
    !running &&
    cooldown === null &&
    availability !== 'IN_PROGRESS' &&
    availability !== 'UNAVAILABLE' &&
    availability !== 'LONG_GAP' &&
    outcome !== 'longGap'
  const canVerify =
    verification !== null &&
    !running &&
    automatic === 0 &&
    verificationCooldown === null &&
    // A cool-down the server reported ends with this screen's own countdown of its seconds.
    (verification.availability === 'AVAILABLE' || verification.availability === 'COOLDOWN') &&
    outcome !== 'longGap' &&
    outcome !== 'notUseful'
  const canCatchUp =
    verification !== null &&
    !running &&
    automatic === 0 &&
    verificationCooldown === null &&
    verification.availability === 'LONG_GAP' &&
    verification.catchUpFrom !== null &&
    outcome !== 'notLongGap' &&
    outcome !== 'notUseful'

  const request = useCallback((): void => {
    if (manualInFlight.current || !canRequest) return
    if (csrfRef.current === null) {
      setSaid(say('error', 'SYNC'))
      setFocus(focusOn('summary'))
      return
    }
    setSaid(null)
    setRequesting('SYNC')
    admit({ kind: 'sync', trigger: 'MANUAL' })
  }, [canRequest, admit])

  const requestVerification = useCallback(
    (kind: VerificationKind): void => {
      // Not while any admission is in flight (an automatic one too), even within the same tick.
      if (posts.current > 0 || !(kind === 'PRECISION' ? canVerify : canCatchUp)) return
      const asked: SyncPurpose = kind === 'PRECISION' ? 'VERIFY' : 'CATCH_UP'
      // The reference of the result shown right now: the selection the user made by pressing.
      const result = bundleOf(viewRef.current)?.result ?? null
      if (csrfRef.current === null || result === null) {
        setSaid(say('error', asked))
        setFocus(focusOn(summaryTarget(asked)))
        return
      }
      setSaid(null)
      setRequesting(asked)
      admit({
        kind: 'verification',
        verification: kind,
        reference: {
          planId: result.planId,
          pointerVersion: result.pointerVersion,
          calculationId: result.calculationId,
        },
      })
    },
    [canVerify, canCatchUp, admit],
  )

  return {
    phase,
    purpose,
    running,
    followingElsewhere: followed?.origin === 'elsewhere',
    canRequest,
    cooldownSeconds: cooldown,
    canVerify,
    canCatchUp,
    admitting: automatic > 0,
    verificationCooldownSeconds: verificationCooldown,
    focus,
    request,
    requestVerification,
  }
}
