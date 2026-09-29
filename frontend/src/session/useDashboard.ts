import { useCallback, useEffect, useRef, useState } from 'react'

import {
  planResultContent as copy,
  copyFor,
  DASHBOARD_PATH,
  METRIC_ORDER,
  type MetricName,
} from '../content/planResult'
import { formatSeoulTime, isCanonicalDecimal, isPlainNonNegative } from '../format/decimal'

export interface PlanResultMetric {
  readonly metric: MetricName
  readonly value?: string
  readonly nature: 'OFFICIAL' | 'ESTIMATED'
  readonly unavailableReason?: string
}

/**
 * One trust axis as the server judged it (Story 2.8). Every field is a code or a time: the screen maps
 * codes to copy and never judges time itself.
 */
export interface TrustAxis {
  readonly state: string
  readonly cause: string
  /** The time of the official bundle this axis vouches for. */
  readonly lastNormalAt: string
  readonly affectedMetrics: readonly string[]
  readonly nextAction: string
}

/** Freshness vouches for the measurement; both times it is judged from keep their own names. */
export interface FreshnessAxis extends TrustAxis {
  readonly measuredAt: string
  readonly cashflowVerifiedThrough: string
}

/** Calculation nature, and the character "not computable" never overwrites. */
export interface NatureAxis extends TrustAxis {
  readonly character: string
  readonly unavailableMetrics: readonly string[]
  readonly estimatedFlowCount: number
}

/** The active plan's official bundle: one response read through one pointer. */
export interface PlanResult {
  readonly planId: string
  readonly planStatus: string
  readonly conditions: {
    readonly principal: string
    readonly startAt: string
    readonly endAt: string
    readonly period: string
    readonly targetRate: string
  }
  readonly pointerVersion: number
  readonly publishedAt: string
  readonly calculationId: string
  /** The official snapshot the version was calculated on. The evidence's failure view names it (Story 2.9). */
  readonly portfolioSnapshotId: string
  readonly basisPortfolioSnapshotId: string
  readonly basisAt: string
  readonly measuredAt: string
  readonly cashflowVerifiedThrough: string
  readonly policies: {
    readonly calculation: string
    readonly calendar: string
    readonly display: string
    readonly price: string
    readonly implementation: string
  }
  readonly flowCount: number
  readonly estimatedFlowCount: number
  readonly trust: {
    readonly freshness: FreshnessAxis
    readonly inputReliability: TrustAxis
    readonly calculationNature: NatureAxis
  }
  readonly metrics: readonly PlanResultMetric[]
}

/** The value as an object, or `null`. The calculation evidence's validator shares these helpers. */
export function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

/** Whether every key of the object holds a string. */
export function strings(value: unknown, keys: readonly string[]): boolean {
  const row = record(value)
  return row !== null && keys.every((key) => typeof row[key] === 'string')
}

function stringList(value: unknown): value is readonly string[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string')
}

function isMetric(value: unknown, expected: MetricName | undefined): boolean {
  const row = record(value)
  if (row === null || row.metric !== expected) return false
  if (row.nature !== 'OFFICIAL' && row.nature !== 'ESTIMATED') return false
  // A value or a reason, never both and never neither; a value is a canonical decimal string.
  if (row.value === undefined) return typeof row.unavailableReason === 'string'
  return isCanonicalDecimal(row.value) && row.unavailableReason === undefined
}

/** An axis has its five fields; unknown codes are allowed here and folded to safe copy on screen. */
function isAxis(value: unknown, extra: readonly string[] = []): boolean {
  const row = record(value)
  return (
    row !== null &&
    strings(row, ['state', 'cause', 'lastNormalAt', 'nextAction', ...extra]) &&
    stringList(row.affectedMetrics)
  )
}

function sameList(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && left.every((entry, index) => entry === right[index])
}

/**
 * The metrics agree with the nature axis (CALC-1.0): the current value is always defined and official;
 * any undefined metric exactly when the nature is `NOT_COMPUTABLE`, and those are the listed undefined
 * metrics; and the character is the one nature every derived metric shares — estimated when an
 * estimated flow reached them, official otherwise.
 */
function agreesWithNature(result: PlanResult): boolean {
  const [current, ...derived] = result.metrics
  const nature = result.trust.calculationNature
  if (current?.value === undefined || current.nature !== 'OFFICIAL') return false
  const undefinedMetrics = result.metrics
    .filter((metric) => metric.value === undefined)
    .map((metric) => metric.metric)
  if (undefinedMetrics.length > 0 !== (nature.state === 'NOT_COMPUTABLE')) return false
  if (!sameList(nature.unavailableMetrics, undefinedMetrics)) return false
  if (nature.character !== 'OFFICIAL' && nature.character !== 'ESTIMATED') return false
  return derived.every((metric) => metric.nature === nature.character)
}

/**
 * The whole bundle is accepted or rejected as one unit. A response with a missing axis, a metric out of
 * order or a malformed value is not rendered in part. It must also agree with itself (AC-9): each axis
 * vouches for its own time of this bundle, freshness repeats both times under their own names, and the
 * nature axis matches the metrics. The plan's own terms (`P0`, the rate) are plain decimals: a notation
 * difference such as trailing zeros is not a reason to drop it.
 */
export function isPlanResult(value: unknown): value is PlanResult {
  const row = record(value)
  if (row === null) return false
  const trust = record(row.trust)
  const conditions = record(row.conditions)
  const metrics = row.metrics
  if (!(
    strings(row, [
      'planId',
      'planStatus',
      'publishedAt',
      'calculationId',
      'portfolioSnapshotId',
      'basisPortfolioSnapshotId',
      'basisAt',
      'measuredAt',
      'cashflowVerifiedThrough',
    ]) &&
    strings(conditions, ['principal', 'startAt', 'endAt', 'period', 'targetRate']) &&
    conditions !== null &&
    isPlainNonNegative(conditions.principal) &&
    isPlainNonNegative(conditions.targetRate) &&
    strings(row.policies, ['calculation', 'calendar', 'display', 'price', 'implementation']) &&
    Number.isInteger(row.pointerVersion) &&
    Number.isInteger(row.flowCount) &&
    Number.isInteger(row.estimatedFlowCount) &&
    trust !== null &&
    isAxis(trust.freshness, ['measuredAt', 'cashflowVerifiedThrough']) &&
    isAxis(trust.inputReliability) &&
    isAxis(trust.calculationNature, ['character']) &&
    stringList(record(trust.calculationNature)?.unavailableMetrics) &&
    Number.isInteger(record(trust.calculationNature)?.estimatedFlowCount) &&
    Array.isArray(metrics) &&
    metrics.length === METRIC_ORDER.length &&
    metrics.every((metric, index) => isMetric(metric, METRIC_ORDER[index]))
  )) {
    return false
  }
  const result = row as unknown as PlanResult
  const { freshness, inputReliability, calculationNature } = result.trust
  return (
    agreesWithNature(result) &&
    calculationNature.estimatedFlowCount === result.estimatedFlowCount &&
    freshness.measuredAt === result.measuredAt &&
    freshness.cashflowVerifiedThrough === result.cashflowVerifiedThrough &&
    freshness.lastNormalAt === result.measuredAt &&
    inputReliability.lastNormalAt === result.cashflowVerifiedThrough &&
    calculationNature.lastNormalAt === result.basisAt
  )
}

/** One validated dashboard response. Only `READY` carries financial fields. */
export type DashboardResponse =
  | { readonly state: 'NO_PLAN' | 'PREPARING'; readonly nextAction: string }
  | { readonly state: 'READY'; readonly nextAction: string; readonly result: PlanResult }

/**
 * The screen-level next actions each state may carry (Story 2.8's closed set, and Story 2.11's
 * `SYNC_NOW` and `REVIEW_LONG_GAP` for a ready result). A pair outside these is not an answer this screen
 * understands.
 */
const NEXT_ACTIONS: Readonly<Record<DashboardResponse['state'], readonly string[]>> = {
  NO_PLAN: ['CREATE_PLAN'],
  PREPARING: ['WAIT_FOR_RESULT'],
  READY: ['NONE', 'WAIT_FOR_SYNC', 'SYNC_NOW', 'REVIEW_LONG_GAP'],
}

/**
 * A `GET /api/dashboard` body, or `null` when it is not one whole answer. A state outside the three, a
 * next action that state cannot carry, a financial field on a state that has none, or a malformed bundle
 * all reject it.
 */
export function parseDashboard(value: unknown): DashboardResponse | null {
  const row = record(value)
  if (row === null || typeof row.nextAction !== 'string') return null
  if (row.state === 'NO_PLAN' || row.state === 'PREPARING') {
    return row.result === undefined && NEXT_ACTIONS[row.state].includes(row.nextAction)
      ? { state: row.state, nextAction: row.nextAction }
      : null
  }
  if (
    row.state === 'READY' &&
    NEXT_ACTIONS.READY.includes(row.nextAction) &&
    isPlanResult(row.result)
  ) {
    return { state: 'READY', nextAction: row.nextAction, result: row.result }
  }
  return null
}

/**
 * Whether `next` may replace `shown` (AC-9). Within one plan the pointer only moves forward, and one
 * pointer version names one calculation: a version that goes back, or the same version with another
 * calculation, is not a newer bundle.
 */
export function supersedes(next: PlanResult, shown: PlanResult | null): boolean {
  if (shown?.planId !== next.planId) return true
  if (next.pointerVersion < shown.pointerVersion) return false
  return next.pointerVersion !== shown.pointerVersion || next.calculationId === shown.calculationId
}

/** The bundle last accepted, with the next action it came with. */
export interface Accepted {
  readonly result: PlanResult
  readonly nextAction: string
}

/**
 * What the screen shows. `checking` (no answer yet) is never "no plan". After a failed read the last
 * accepted bundle stays unchanged as `lastGood` (READ_FAIL). `retryable` is a read to retry: with
 * nothing kept it has no financial value at all; after a response whose version may not replace the
 * shown bundle it keeps that bundle unswapped (RACE). `denied` has dropped the bundle from memory — a
 * refusal (403), or a session that is gone (401).
 */
export type DashboardView =
  | { readonly kind: 'checking' }
  | { readonly kind: 'NO_PLAN' | 'PREPARING'; readonly nextAction: string }
  | { readonly kind: 'READY'; readonly nextAction: string; readonly result: PlanResult }
  | { readonly kind: 'lastGood'; readonly nextAction: string; readonly result: PlanResult }
  | { readonly kind: 'retryable'; readonly kept: Accepted | null }
  | { readonly kind: 'denied'; readonly cause: 'refused' | 'signedOut' }

/** How a read is asked for. A quiet read (Story 2.11) swaps the bundle without saying "reloading". */
export interface ReloadOptions {
  readonly quiet?: boolean
}

export interface Dashboard {
  readonly view: DashboardView
  /** A read is in flight. The screen marks the result container and its status region busy. */
  readonly loading: boolean
  /**
   * The read in flight was asked for quietly: the incremental sync's own reads (after an admission and after
   * a job ends) must not make the dashboard's sentence waver into "reloading".
   */
  readonly quiet?: boolean
  /**
   * Reads again. A plain reload while a read is in flight joins it — only the user reloads, and there is no
   * polling — and a quiet read it joins stops being quiet, so the user's press is answered with "reloading". A
   * quiet reload asked for while a read is in flight runs once after it, so the answer the sync waited for is
   * never lost to an earlier read.
   */
  readonly reload: (options?: ReloadOptions) => void
}

/**
 * Reads the dashboard once the session is ready, and again on each reload. Financial values live only
 * in React memory — never in the URL or browser storage — and only while the session is ready: when it
 * stops being ready the bundle is dropped and the view goes back to `checking`.
 *
 * Each read has a sequence number; an answer that is not the latest read's is dropped, so a late
 * response never overwrites a newer one. A validated `READY` bundle replaces the whole view at once. A
 * failed read keeps the last accepted bundle as `lastGood`, or is `retryable` without one; a response
 * whose version may not replace the shown bundle is `retryable` with that bundle kept. A refusal (403)
 * or a gone session (401) drops the accepted bundle. `loading` is true for every read in flight.
 */
export function useDashboard(ready: boolean): Dashboard {
  const [view, setView] = useState<DashboardView>({ kind: 'checking' })
  // One number per read the screen asks for: a reload, or the session becoming ready again.
  const [request, setRequest] = useState(0)
  const [settled, setSettled] = useState(-1)
  const [wasReady, setWasReady] = useState(ready)
  const sequence = useRef(0)
  const inFlight = useRef(false)
  const accepted = useRef<Accepted | null>(null)
  const [quiet, setQuiet] = useState(false)
  // Whether the read in flight was asked for quietly (the callbacks' own copy of `quiet`).
  const quietRead = useRef(false)
  // A quiet read asked for while another was in flight: it runs right after that one settles.
  const queued = useRef(false)

  // Adjusting state to a changed prop during render (React's documented pattern, not an effect): the
  // bundle leaves the view the moment the session stops being ready, and a new read starts as loading.
  if (ready !== wasReady) {
    setWasReady(ready)
    if (ready) setRequest((current) => current + 1)
    else setView({ kind: 'checking' })
  }

  useEffect(() => {
    if (!ready) {
      accepted.current = null
      return
    }
    sequence.current += 1
    const mine = sequence.current
    inFlight.current = true
    const latest = (): boolean => sequence.current === mine
    const failed = (): DashboardView => {
      const kept = accepted.current
      return kept === null ? { kind: 'retryable', kept: null } : { kind: 'lastGood', ...kept }
    }
    const settle = (next: DashboardView): void => {
      inFlight.current = false
      setView(next)
      setSettled(request)
      if (queued.current) {
        queued.current = false
        inFlight.current = true
        quietRead.current = true
        setQuiet(true)
        setRequest((current) => current + 1)
      }
    }
    const read = async (): Promise<void> => {
      try {
        const response = await fetch(DASHBOARD_PATH, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
        })
        if (!latest()) return
        if (response.status === 401 || response.status === 403) {
          accepted.current = null
          settle({ kind: 'denied', cause: response.status === 401 ? 'signedOut' : 'refused' })
          return
        }
        const payload: unknown = response.status === 200 ? await response.json() : null
        if (!latest()) return
        const parsed = parseDashboard(payload)
        if (parsed === null) {
          settle(failed())
          return
        }
        if (parsed.state !== 'READY') {
          accepted.current = null
          settle({ kind: parsed.state, nextAction: parsed.nextAction })
          return
        }
        if (!supersedes(parsed.result, accepted.current?.result ?? null)) {
          // Not swapped in: the shown bundle stays, as a read to retry.
          settle({ kind: 'retryable', kept: accepted.current })
          return
        }
        accepted.current = { result: parsed.result, nextAction: parsed.nextAction }
        settle({ kind: 'READY', result: parsed.result, nextAction: parsed.nextAction })
      } catch {
        if (latest()) settle(failed())
      }
    }
    void read()
    return () => {
      // Invalidates this read: its answer, if it still comes, belongs to nobody.
      sequence.current += 1
      inFlight.current = false
    }
  }, [ready, request])

  const reload = useCallback((options?: ReloadOptions): void => {
    if (inFlight.current) {
      if (options?.quiet === true) {
        queued.current = true
      } else if (quietRead.current) {
        // The user asked while a quiet read was out: that read is theirs now, and it says "reloading".
        quietRead.current = false
        setQuiet(false)
      }
      return
    }
    inFlight.current = true
    quietRead.current = options?.quiet === true
    setQuiet(options?.quiet === true)
    setRequest((current) => current + 1)
  }, [])

  const loading = ready && settled !== request
  return { view, loading, quiet: loading && quiet, reload }
}

/** The bundle a view shows, if any: ready, kept after a failure, or kept unswapped after a conflict. */
export function bundleOf(view: DashboardView): Accepted | null {
  if (view.kind === 'READY' || view.kind === 'lastGood') return view
  if (view.kind === 'retryable') return view.kept
  return null
}

/** The single sentence the screen's status region reads for the dashboard. */
export function dashboardAnnouncement(dashboard: Dashboard): string {
  const { view } = dashboard
  // A quiet read keeps the sentence of the bundle it may replace; the new bundle then speaks for itself.
  if (dashboard.loading && !dashboard.quiet && view.kind !== 'checking')
    return copy.status.reloading
  switch (view.kind) {
    case 'checking':
      return copy.status.checking
    case 'NO_PLAN':
      return copy.status.noPlan
    case 'PREPARING':
      return copy.status.preparing
    case 'READY': {
      const basis = formatSeoulTime(view.result.basisAt, copy.timeUnavailable)
      const reliability = view.result.trust.inputReliability.state
      if (reliability === 'CONFIRMED') return copy.status.ready(basis)
      return copy.status.readyWithReliability(
        copyFor(copy.axes.reliability.states, reliability, copy.axes.unknownState),
        basis,
      )
    }
    case 'lastGood':
      return copy.status.lastGood
    case 'retryable':
      return view.kept === null ? copy.status.retryable : copy.status.versionConflict
    case 'denied':
      return view.cause === 'signedOut' ? copy.status.signedOut : copy.status.denied
  }
}

/**
 * What the dashboard knows about a plan, as far as the first-snapshot panel needs it: `none` when the
 * server answered `NO_PLAN`, `unknown` while a publication is being prepared.
 */
export type PlanPresence = 'none' | 'unknown'

/**
 * Whether and how the screen shows the first-snapshot panel beside this view (mobile-first order:
 * current value → trust axes → metrics → next action). Only a server answer without a bundle — `NO_PLAN`
 * or `PREPARING` — shows it, leading with the first value before the dashboard's next action. With a
 * bundle the dashboard's first card is the current value, so a second one from a second read would
 * contradict "one value, once"; after a failed read with nothing kept, or a refusal, nothing financial
 * may appear; and before the answer nothing is known yet. `null` means "not mounted".
 */
export function planPresenceOf(view: DashboardView): PlanPresence | null {
  if (view.kind === 'NO_PLAN') return 'none'
  if (view.kind === 'PREPARING') return 'unknown'
  return null
}

/**
 * Whether the screen's single status region is busy. The session is being checked or moved (not the
 * reauthentication-required state, which waits for the user rather than changing), a sign-out or
 * reauthentication request is in flight, or the dashboard is reading.
 */
export function statusBusy(input: {
  readonly ready: boolean
  readonly reauthRequired: boolean
  readonly requestPending: boolean
  readonly dashboard: Dashboard
}): boolean {
  return (
    (!input.ready && !input.reauthRequired) ||
    input.requestPending ||
    (input.ready && input.dashboard.loading)
  )
}
