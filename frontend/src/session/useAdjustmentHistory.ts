import { useCallback, useLayoutEffect, useRef, useState } from 'react'

import {
  adjustmentsPath,
  adjustmentHistoryContent as copy,
  type AdjustmentDirection,
} from '../content/adjustmentHistory'
import { isCanonicalDecimal } from '../format/decimal'
import {
  bundleOf,
  isInstant,
  record,
  strings,
  type Dashboard,
  type PlanResult,
} from './useDashboard'

/** One leg of a spot price route: the closed one-minute candle whose close was used. */
export interface AdjustmentLeg {
  readonly symbol: string
  readonly candleStartAt: string
  readonly candleEndAt: string
  readonly closePrice: string
  readonly selectionRule: string
  readonly lagMinutes: number
}

/** An earlier conversion version of the event, as it was stored. Never used by an official calculation. */
export interface AdjustmentRevision {
  readonly conversionId: string
  readonly processedAt: string
  readonly status: string
  readonly reasonCode: string | null
  readonly usdValue: string | null
  readonly changeReason: string
  readonly discoveredAt: string
}

/**
 * How this flow differs from the previous official version (Story 2.12): added — found late, or new — or with
 * another USD value, the previous value, and when it was discovered and applied. Only a late-added or
 * value-changed flow of the shown version carries it.
 */
export interface AdjustmentVersionChange {
  readonly change: string
  readonly late: boolean
  readonly previousUsdValue: string | null
  readonly discoveredAt: string
  readonly appliedAt: string
}

/**
 * One external cash flow the current official calculation version applied, in the reading order: event →
 * classification → price → discovery → references. Quantities, unit prices and closes are unrounded
 * canonical decimal strings; the USD value is too, and the screen rounds it once for display.
 */
export interface Adjustment {
  readonly sequence: number
  readonly occurredAt: string
  readonly direction: 'IN' | 'OUT'
  readonly asset: string
  readonly signedQuantity: string
  readonly usdValue: string
  readonly classification: {
    readonly transactionType: string
    readonly ruleId: string
    readonly policyVersion: string
  }
  readonly price: {
    readonly policyVersion: string
    readonly evidenceType: string
    readonly estimated: boolean
    readonly unitPrice: string
    readonly routeId: string
    readonly legs: readonly AdjustmentLeg[]
  }
  readonly discoveredAt: string
  readonly refs: {
    readonly sourceEventId: string
    readonly sourceId: string
    readonly classificationId: string
    readonly conversionId: string
    readonly calculationId: string
  }
  readonly changed: boolean
  readonly revisions: readonly AdjustmentRevision[]
  /** Its change from the previous official version (Story 2.12), if any. */
  readonly versionChange?: AdjustmentVersionChange | null
}

/** The summary of the whole filtered range: the server's exact sum, never one the screen adds up. */
export interface AdjustmentSummary {
  readonly count: number
  readonly netUsdValue: string
  readonly excludedCount: number
}

/** One `GET /api/dashboard/adjustments` page, validated against the shown bundle. */
export interface AdjustmentPage {
  readonly direction: AdjustmentDirection
  readonly appliedAt: string
  readonly coverage: { readonly from: string; readonly through: string }
  readonly summary: AdjustmentSummary
  readonly rows: readonly Adjustment[]
  readonly nextCursor: string | null
}

/** The pages received so far for one filter, as one list. */
export type AdjustmentList = AdjustmentPage

/** The most rows one page may carry. The server sends exactly this many when another page follows. */
export const ADJUSTMENT_PAGE_SIZE = 20

function count(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0
}

function nonEmpty(value: unknown): value is string {
  return typeof value === 'string' && value !== ''
}

/**
 * One price leg, whole. The lag is any non-negative whole number of minutes: how far back a candle may be
 * chosen is the price policy's (BYBIT-PRICE), and the screen does not hold its own copy of that limit.
 */
function isLeg(value: unknown): boolean {
  const leg = record(value)
  return (
    leg !== null &&
    strings(leg, ['symbol', 'selectionRule']) &&
    isInstant(leg.candleStartAt) &&
    isInstant(leg.candleEndAt) &&
    isCanonicalDecimal(leg.closePrice) &&
    !leg.closePrice.startsWith('-') &&
    count(leg.lagMinutes)
  )
}

/**
 * A version change marker, whole and consistent with its row (Story 2.12): a change code (one this screen does
 * not know folds to safe copy), whether it was found late, the previous USD value or none, and two instants.
 * An added flow had no previous value and a changed one had; a removed flow is not a row this version applied;
 * a previous value carries the row's direction as its sign; and the flow was discovered no later than applied.
 * Absent means no marker.
 */
function isVersionChange(value: unknown, out: boolean): boolean {
  if (value === null || value === undefined) return true
  const change = record(value)
  if (
    change === null ||
    typeof change.change !== 'string' ||
    typeof change.late !== 'boolean' ||
    !isInstant(change.discoveredAt) ||
    !isInstant(change.appliedAt)
  ) {
    return false
  }
  const previous = change.previousUsdValue
  const had = previous !== null && previous !== undefined
  if (had && !(isCanonicalDecimal(previous) && previous.startsWith('-') === out)) return false
  if (change.change === 'ADDED' && had) return false
  if (change.change === 'VALUE_CHANGED' && !had) return false
  if (change.change === 'REMOVED') return false
  return Date.parse(change.discoveredAt) <= Date.parse(change.appliedAt)
}

/** The marker as the screen holds it: `null` for none, and a previous value that is absent held as `null`. */
function normalized(row: Adjustment): Adjustment {
  const change = row.versionChange
  if (change === null || change === undefined) return { ...row, versionChange: null }
  return { ...row, versionChange: { ...change, previousUsdValue: change.previousUsdValue ?? null } }
}

function isRevision(value: unknown): boolean {
  const revision = record(value)
  return (
    revision !== null &&
    strings(revision, ['conversionId', 'status', 'changeReason']) &&
    isInstant(revision.processedAt) &&
    isInstant(revision.discoveredAt) &&
    (revision.reasonCode === null || typeof revision.reasonCode === 'string') &&
    (revision.usdValue === null || isCanonicalDecimal(revision.usdValue))
  )
}

/**
 * One row, whole: every field of its shape, the sign of its quantity and USD value agreeing with its
 * direction, the filter's direction, the shown calculation, a change marker exactly when earlier
 * versions are listed, and a whole version change marker when it carries one (Story 2.12).
 */
function isRow(value: unknown, shown: PlanResult, direction: AdjustmentDirection): boolean {
  const row = record(value)
  if (row === null) return false
  const classification = record(row.classification)
  const price = record(row.price)
  const refs = record(row.refs)
  const legs = price?.legs
  const revisions = row.revisions
  if (
    classification === null ||
    price === null ||
    refs === null ||
    !Array.isArray(legs) ||
    !Array.isArray(revisions)
  ) {
    return false
  }
  const out = row.direction === 'OUT'
  return (
    Number.isInteger(row.sequence) &&
    (row.sequence as number) > 0 &&
    isInstant(row.occurredAt) &&
    (row.direction === 'IN' || out) &&
    (direction === 'ALL' || row.direction === direction) &&
    nonEmpty(row.asset) &&
    isCanonicalDecimal(row.signedQuantity) &&
    row.signedQuantity.startsWith('-') === out &&
    isCanonicalDecimal(row.usdValue) &&
    row.usdValue.startsWith('-') === out &&
    strings(classification, ['transactionType', 'ruleId', 'policyVersion']) &&
    strings(price, ['policyVersion', 'evidenceType', 'routeId']) &&
    typeof price.estimated === 'boolean' &&
    isCanonicalDecimal(price.unitPrice) &&
    !price.unitPrice.startsWith('-') &&
    legs.every(isLeg) &&
    isInstant(row.discoveredAt) &&
    strings(refs, [
      'sourceEventId',
      'sourceId',
      'classificationId',
      'conversionId',
      'calculationId',
    ]) &&
    refs.calculationId === shown.calculationId &&
    typeof row.changed === 'boolean' &&
    row.changed === revisions.length > 0 &&
    revisions.every(isRevision) &&
    isVersionChange(row.versionChange, out)
  )
}

/**
 * A `GET /api/dashboard/adjustments` body, or `null` when it is not one whole page of the shown bundle under
 * the asked filter. The three reference values must be the shown pointer's and the direction the asked one;
 * the application time is the shown publication, the range the shown `(t0, cashflowVerifiedThrough]`; the
 * summary is whole (under `ALL`, the shown flow count); the rows are at most one page, in strictly rising
 * sequence, each whole; a next page is announced only after a full page. Anything else is dropped whole,
 * never rendered in part.
 */
export function parseAdjustmentPage(
  value: unknown,
  shown: PlanResult,
  direction: AdjustmentDirection,
): AdjustmentPage | null {
  const page = record(value)
  if (page === null) return null
  const coverage = record(page.coverage)
  const summary = record(page.summary)
  const rows = page.rows
  if (coverage === null || summary === null || !Array.isArray(rows)) return null
  const next = page.nextCursor
  const whole =
    page.planId === shown.planId &&
    page.pointerVersion === shown.pointerVersion &&
    page.calculationId === shown.calculationId &&
    page.direction === direction &&
    isInstant(page.appliedAt) &&
    page.appliedAt === shown.publishedAt &&
    coverage.from === shown.conditions.startAt &&
    coverage.through === shown.cashflowVerifiedThrough &&
    count(summary.count) &&
    isCanonicalDecimal(summary.netUsdValue) &&
    count(summary.excludedCount) &&
    (direction !== 'ALL' || summary.count === shown.flowCount) &&
    rows.length <= ADJUSTMENT_PAGE_SIZE &&
    rows.length <= summary.count &&
    rows.every((row) => isRow(row, shown, direction)) &&
    (next === null || (nonEmpty(next) && rows.length === ADJUSTMENT_PAGE_SIZE))
  if (!whole) return null
  const raw = page as unknown as AdjustmentPage
  const parsed: AdjustmentPage = { ...raw, rows: raw.rows.map(normalized) }
  const rising = parsed.rows.every(
    (row, index) => index === 0 || row.sequence > (parsed.rows[index - 1]?.sequence ?? 0),
  )
  return rising ? parsed : null
}

/**
 * A next page appended to what the screen holds, or `null` when it does not continue it: another summary,
 * range or application time than the first page's, a sequence that goes back or repeats, an event already
 * listed, or a last page whose rows do not add up to the summary's count.
 */
export function appendPage(held: AdjustmentList, next: AdjustmentPage): AdjustmentList | null {
  const last = held.rows[held.rows.length - 1]
  const [first] = next.rows
  if (
    next.direction !== held.direction ||
    next.appliedAt !== held.appliedAt ||
    next.coverage.from !== held.coverage.from ||
    next.coverage.through !== held.coverage.through ||
    next.summary.count !== held.summary.count ||
    next.summary.netUsdValue !== held.summary.netUsdValue ||
    next.summary.excludedCount !== held.summary.excludedCount ||
    first === undefined ||
    (last !== undefined && first.sequence <= last.sequence)
  ) {
    return null
  }
  const seen = new Set(held.rows.map((row) => row.refs.sourceEventId))
  if (next.rows.some((row) => seen.has(row.refs.sourceEventId))) return null
  const rows = [...held.rows, ...next.rows]
  return complete({ ...next, rows })
}

/** The list as it stands, or `null` when it claims to be whole but its rows do not add up. */
function complete(list: AdjustmentList): AdjustmentList | null {
  if (list.rows.length > list.summary.count) return null
  if (list.nextCursor === null && list.rows.length !== list.summary.count) return null
  return list
}

/**
 * The state of a read. `loading` is a read in flight: the first page of the filter, or (`more`) the next
 * page — `retrying` when it was asked for again after a retryable failure, so the region keeps the retry
 * control in place. `failed` is a read that ended without its page — `retryable` when asking again can help
 * (network, 5xx, a broken lineage, a malformed answer), not when the server said the shown bundle is not its
 * current pointer (404).
 */
export type AdjustmentRead =
  | { readonly kind: 'loading'; readonly more: boolean; readonly retrying: boolean }
  | { readonly kind: 'ready' }
  | { readonly kind: 'failed'; readonly more: boolean; readonly retryable: boolean }

/**
 * What the history region shows. `closed` has nothing. `open` is the region for one filter: the rows received
 * so far (`list`, kept across a failed `더 보기`), and the state of the last read. `denied` has dropped the
 * history from memory (403, or a gone session).
 */
export type AdjustmentView =
  | { readonly kind: 'closed' }
  | {
      readonly kind: 'open'
      readonly direction: AdjustmentDirection
      readonly list: AdjustmentList | null
      readonly read: AdjustmentRead
    }
  | { readonly kind: 'denied'; readonly cause: 'refused' | 'signedOut' }

export interface AdjustmentHistoryState {
  readonly view: AdjustmentView
  /** Opens the region and reads the first page of every flow. Ignored without a bundle. */
  readonly open: () => void
  /** Closes the region and forgets whatever it held. A read still in flight is aborted. */
  readonly close: () => void
  /** Shows another filter: what was held is dropped, and its first page is read. */
  readonly filter: (direction: AdjustmentDirection) => void
  /** Reads the next page and appends it. Ignored without one, or while reading. */
  readonly more: () => void
  /** Reads again what failed, after a retryable failure. */
  readonly retry: () => void
}

/**
 * The adjustment history of the bundle the dashboard shows (Story 2.10), in its own state — never mixed into
 * the dashboard's or the evidence's. Nothing is read until the user opens the region; each open, each filter
 * change and each `더 보기` reads once. There is no polling and no infinite scroll. The history lives only in
 * React memory, only while it belongs to the shown bundle: when the bundle is replaced (a newer pointer,
 * another plan) or goes away, the history is discarded and the region closes.
 *
 * A read in flight is aborted when the region closes, the filter changes or the shown bundle changes; an
 * aborted read is not a failure and settles nothing. An answer is taken only if its read is still the latest
 * and the bundle it was asked for is still the shown one — the bundle key moves in the commit that changes
 * it, before any answer can land. A next page must continue what is held (`appendPage`): a sequence that goes
 * back or repeats is a failed read, and the rows already received stay.
 *
 * A plain failure never touches the dashboard, and the received rows stay. A refusal (403) or a gone session
 * (401) drops the history and calls `onDenied` — the screen asks the dashboard to read again.
 */
export function useAdjustmentHistory(
  bundle: PlanResult | null,
  onDenied: () => void,
): AdjustmentHistoryState {
  const key =
    bundle === null
      ? null
      : `${bundle.planId}\n${String(bundle.pointerVersion)}\n${bundle.calculationId}`
  const [view, setView] = useState<AdjustmentView>({ kind: 'closed' })
  const [shownKey, setShownKey] = useState(key)
  const sequence = useRef(0)
  const inFlight = useRef<AbortController | null>(null)
  const keyRef = useRef(key)

  // Adjusting state to a changed prop during render (as `useCalculationEvidence` does): the history of a
  // bundle that is no longer shown leaves the screen in the same render that stops showing the bundle.
  if (key !== shownKey) {
    setShownKey(key)
    setView({ kind: 'closed' })
  }

  // In the commit itself, before any answer can land: the shown key moves, and a read asked for the old
  // bundle is aborted and belongs to nobody. Unmounting runs the same cleanup.
  useLayoutEffect(() => {
    keyRef.current = key
    return () => {
      sequence.current += 1
      inFlight.current?.abort()
      inFlight.current = null
    }
  }, [key])

  /**
   * Reads one page. `held` is what the region keeps meanwhile and, for `더 보기`, what the page continues;
   * `null` reads the filter's first page. A read still in flight (another filter's) is aborted first.
   */
  const read = useCallback(
    (
      direction: AdjustmentDirection,
      held: AdjustmentList | null,
      more: boolean,
      retrying: boolean,
    ): void => {
      if (bundle === null) return
      inFlight.current?.abort()
      sequence.current += 1
      const mine = sequence.current
      const askedFor = keyRef.current
      const controller = new AbortController()
      inFlight.current = controller
      setView({ kind: 'open', direction, list: held, read: { kind: 'loading', more, retrying } })
      const shown = bundle
      const cursor = more ? (held?.nextCursor ?? null) : null
      const latest = (): boolean => sequence.current === mine && keyRef.current === askedFor
      const settle = (next: AdjustmentView): void => {
        inFlight.current = null
        setView(next)
      }
      const failed = (retryable: boolean): AdjustmentView => ({
        kind: 'open',
        direction,
        list: held,
        read: { kind: 'failed', more, retryable },
      })
      void (async () => {
        try {
          const response = await fetch(adjustmentsPath(shown, direction, cursor), {
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { Accept: 'application/json' },
            signal: controller.signal,
          })
          if (!latest()) return
          if (response.status === 401 || response.status === 403) {
            settle({ kind: 'denied', cause: response.status === 401 ? 'signedOut' : 'refused' })
            onDenied()
            return
          }
          if (response.status === 404) {
            settle(failed(false))
            return
          }
          const payload: unknown = response.status === 200 ? await response.json() : null
          if (!latest()) return
          const page = parseAdjustmentPage(payload, shown, direction)
          const list =
            page === null ? null : more && held !== null ? appendPage(held, page) : complete(page)
          settle(
            list === null
              ? failed(true)
              : { kind: 'open', direction, list, read: { kind: 'ready' } },
          )
        } catch {
          // An aborted read is no longer the latest: it settles nothing and is not a failure.
          if (latest()) settle(failed(true))
        }
      })()
    },
    [bundle, onDenied],
  )

  const open = useCallback((): void => {
    // A second press in the same tick joins the read in flight.
    if (view.kind === 'closed' && inFlight.current === null) read('ALL', null, false, false)
  }, [read, view.kind])

  const close = useCallback((): void => {
    sequence.current += 1
    inFlight.current?.abort()
    inFlight.current = null
    setView({ kind: 'closed' })
  }, [])

  const filter = useCallback(
    (direction: AdjustmentDirection): void => {
      if (view.kind !== 'open' || view.direction === direction) return
      read(direction, null, false, false)
    },
    [read, view],
  )

  const more = useCallback((): void => {
    if (view.kind !== 'open' || view.read.kind === 'loading' || inFlight.current !== null) return
    const list = view.list
    if (list?.nextCursor == null) return
    read(view.direction, list, true, false)
  }, [read, view])

  const retry = useCallback((): void => {
    if (view.kind !== 'open' || view.read.kind !== 'failed' || !view.read.retryable) return
    if (inFlight.current !== null) return
    read(view.direction, view.list, view.read.more && view.list !== null, true)
  }, [read, view])

  return { view, open, close, filter, more, retry }
}

/**
 * The history of the bundle a dashboard shows, wired the way both landing screens need it: a refusal of the
 * history read asks the dashboard to read again, so the dashboard's own read decides what it keeps.
 */
export function useDashboardAdjustments(dashboard: Dashboard): AdjustmentHistoryState {
  return useAdjustmentHistory(bundleOf(dashboard.view)?.result ?? null, dashboard.reload)
}

/**
 * The sentence the screen's single status region adds after the dashboard's (and the evidence's), or `null`
 * while the region is closed. The history region itself has no live region.
 */
export function adjustmentAnnouncement(view: AdjustmentView): string | null {
  switch (view.kind) {
    case 'closed':
      return null
    case 'denied':
      return view.cause === 'signedOut' ? copy.status.signedOut : copy.status.denied
    case 'open': {
      const { read, list } = view
      if (read.kind === 'loading') return read.more ? copy.status.loadingMore : copy.status.loading
      if (read.kind === 'failed') {
        if (!read.retryable) return copy.status.stale
        // Only a failed next page has rows to keep; a failed first page has none to speak of.
        return read.more ? copy.status.moreRetryable : copy.status.retryable
      }
      return copy.status.ready(list?.rows.length ?? 0, list?.summary.count ?? 0)
    }
  }
}
