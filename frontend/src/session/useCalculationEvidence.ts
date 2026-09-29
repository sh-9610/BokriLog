import { useCallback, useLayoutEffect, useRef, useState } from 'react'

import { calculationEvidenceContent as copy, evidencePath } from '../content/calculationEvidence'
import { METRIC_ORDER, type MetricName } from '../content/planResult'
import { isPlainNonNegative } from '../format/decimal'
import { bundleOf, record, strings, type Dashboard, type PlanResult } from './useDashboard'

/** One metric's character and why (Story 2.9). The value itself is the dashboard's, not the evidence's. */
export interface EvidenceMetric {
  readonly metric: MetricName
  readonly nature: 'OFFICIAL' | 'ESTIMATED'
  readonly unavailableReason?: string
  /**
   * For a defined derived metric of an estimated version: the price evidence kind and the estimated flow
   * count. An undefined metric has none — its reason explains it.
   */
  readonly estimatedCause?: { readonly evidenceType: string; readonly flowCount: number }
}

export interface OfficialVersion {
  readonly pointerVersion: number
  readonly calculationId: string
  readonly publishedAt: string
  /** When the next version replaced it (Story 2.11); `null` for the current one. */
  readonly supersededAt: string | null
  readonly current: boolean
}

/**
 * How the version's cash flows were verified (Story 2.11): `INITIAL` collected `(t0, tc]` whole; `NORMAL`
 * and `EXTENDED` collected `(rangeStart, tc]` and carried the previous official version's flows at or before
 * `rangeStart`.
 */
export interface EvidenceCollection {
  readonly mode: 'INITIAL' | 'NORMAL' | 'EXTENDED'
  readonly rangeStart: string
  readonly lastExtendedVerificationAt: string
  readonly inherited: {
    readonly previousCalculationId: string
    readonly previousPointerVersion: number
    readonly flowCount: number
    readonly through: string
  } | null
}

/** An attempt of the same intent that published nothing. Never an official value. */
export interface UnpublishedAttempt {
  readonly status: 'FAILED' | 'BLOCKED'
  readonly reasonCode: string
  readonly finishedAt: string
}

/**
 * The read-only lineage of the shown official result, as `GET /api/dashboard/evidence` sends it: snapshots →
 * times → input versions → calculation version → metric impact → versions. No internal user id, provider
 * account id, credential, raw provider response or individual cash-flow event is part of it.
 */
export interface CalculationEvidence {
  readonly planId: string
  readonly pointerVersion: number
  readonly calculationId: string
  readonly snapshots: {
    readonly portfolioSnapshotId: string
    readonly portfolioSequence: number
    readonly basisPortfolioSnapshotId: string
    readonly basisSourceSnapshotId: string
    readonly sourceSnapshotId: string
  }
  readonly times: {
    readonly t0: string
    readonly tc: string
    readonly measuredAt: string
    readonly cashflowVerifiedThrough: string
  }
  readonly inputs: {
    readonly connection: {
      readonly provider: string
      readonly connectionId: string
      readonly policyVersion: string
    }
    readonly sourceRange: {
      readonly rangeStart: string
      readonly rangeEnd: string
      readonly collectionPolicy: string
      readonly completenessHash: string
    }
    readonly classification: {
      readonly classificationId: string
      readonly policyVersion: string
      readonly inputScopeHash: string
    }
    readonly conversion: {
      readonly conversionId: string
      readonly pricePolicy: string
      readonly resultFingerprint: string
    }
    readonly cashflows: { readonly appliedCount: number; readonly estimatedCount: number }
    readonly plan: {
      readonly planId: string
      readonly conditions: {
        readonly principal: string
        readonly startAt: string
        readonly endAt: string
        readonly period: string
        readonly targetRate: string
      }
    }
  }
  readonly collection: EvidenceCollection
  readonly calculation: {
    readonly calculationId: string
    readonly calculationPolicy: string
    readonly calendarPolicy: string
    readonly displayPolicy: string
    readonly precisionPolicy: string
    readonly timeZone: string
    readonly implementationVersion: string
    readonly inputHash: string
    readonly resultHash: string
    readonly createdAt: string
  }
  readonly metrics: readonly EvidenceMetric[]
  readonly versions: {
    /** The current version and at most ten earlier ones, newest first. */
    readonly official: readonly OfficialVersion[]
    /** How many official versions the plan has had. */
    readonly officialCount: number
    readonly unpublished: readonly UnpublishedAttempt[]
  }
}

function count(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0
}

function list(value: unknown): readonly unknown[] | null {
  return Array.isArray(value) ? value : null
}

/** An instant as the server writes it (`Instant#toString`): UTC, `Z`, whole seconds and an optional fraction. */
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/

function isInstant(value: unknown): boolean {
  return typeof value === 'string' && INSTANT.test(value) && Number.isFinite(Date.parse(value))
}

/**
 * The metric impact agrees with the shown bundle and with itself: the six in order, each with the shown
 * metric's nature and reason; the current value official and without a cause; a derived metric names the
 * estimated cause exactly when it is estimated and defined, with the version's estimated flow count. An
 * undefined metric carries its reason and no cause.
 */
function isImpact(value: unknown, shown: PlanResult, index: number, estimated: number): boolean {
  const row = record(value)
  const expected = shown.metrics[index]
  if (row === null || expected === undefined || row.metric !== METRIC_ORDER[index]) return false
  if (row.metric !== expected.metric || row.nature !== expected.nature) return false
  if (row.unavailableReason !== expected.unavailableReason) return false
  const cause = row.estimatedCause
  if (index === 0 || row.nature === 'OFFICIAL' || row.unavailableReason !== undefined) {
    return cause === undefined
  }
  const fields = record(cause)
  return (
    fields !== null &&
    typeof fields.evidenceType === 'string' &&
    count(fields.flowCount) &&
    fields.flowCount === estimated
  )
}

function isOfficialVersion(value: unknown): boolean {
  const row = record(value)
  return (
    row !== null &&
    typeof row.calculationId === 'string' &&
    isInstant(row.publishedAt) &&
    Number.isInteger(row.pointerVersion) &&
    (row.pointerVersion as number) >= 1 &&
    typeof row.current === 'boolean' &&
    // The current version has no end; an earlier one ended when the next began.
    (row.current ? row.supersededAt === null : isInstant(row.supersededAt))
  )
}

/**
 * The collection block agrees with the version (Story 2.11). The first calculation collected from `t0` and
 * carried nothing, and its collection was its extended verification (at `tc`). An incremental one is the
 * second official version or a later one: it collected from its start — at or after `t0`, before `tc` — and
 * carried at most the version's flows from the version just before the shown one, up to that start. The last
 * extended verification is never before `t0` nor after `tc`. Its range is the collected range the evidence
 * names; `INITIAL` keeps Story 2.9's exact `(t0, tc]`.
 */
function isCollection(value: unknown, shown: PlanResult, rangeStart: unknown): boolean {
  const row = record(value)
  if (row === null || !isInstant(row.rangeStart) || !isInstant(row.lastExtendedVerificationAt))
    return false
  if (row.rangeStart !== rangeStart) return false
  const extended = Date.parse(row.lastExtendedVerificationAt as string)
  if (extended < Date.parse(shown.conditions.startAt) || extended > Date.parse(shown.basisAt))
    return false
  if (row.mode === 'INITIAL')
    return (
      row.inherited === null &&
      row.rangeStart === shown.conditions.startAt &&
      row.lastExtendedVerificationAt === shown.basisAt
    )
  if (row.mode !== 'NORMAL' && row.mode !== 'EXTENDED') return false
  if (shown.pointerVersion < 2) return false
  const inherited = record(row.inherited)
  const start = Date.parse(row.rangeStart as string)
  return (
    inherited !== null &&
    typeof inherited.previousCalculationId === 'string' &&
    inherited.previousPointerVersion === shown.pointerVersion - 1 &&
    count(inherited.flowCount) &&
    inherited.flowCount <= shown.flowCount &&
    inherited.through === row.rangeStart &&
    start >= Date.parse(shown.conditions.startAt) &&
    start < Date.parse(shown.basisAt)
  )
}

/** The official versions the evidence lists at most: the current one and ten earlier ones. */
const OFFICIAL_VERSIONS_LISTED = 11

/** Strictly newest first: each listed version's order is below the one before it. */
function newestFirst(versions: readonly unknown[]): boolean {
  return versions.every((version, index) => {
    if (index === 0) return true
    const before = record(versions[index - 1])?.pointerVersion
    const here = record(version)?.pointerVersion
    return typeof before === 'number' && typeof here === 'number' && here < before
  })
}

function isUnpublished(value: unknown): boolean {
  const row = record(value)
  return (
    row !== null &&
    (row.status === 'FAILED' || row.status === 'BLOCKED') &&
    typeof row.reasonCode === 'string' &&
    isInstant(row.finishedAt)
  )
}

/**
 * A `GET /api/dashboard/evidence` body, or `null` when it is not the whole lineage of the shown bundle. The
 * three reference values must be the shown pointer's; the snapshots, the times and the plan terms the shown
 * bundle's; the collected range exactly `(start, tc]` with the collection block that names the start (`t0` for
 * the first calculation, Story 2.11's overlap start for a later one); the one current official version that
 * pointer; and the
 * metric impact the shown metrics'. Every instant is in the server's format. Anything else — a missing
 * section, a malformed field, another bundle's lineage — is dropped whole, never rendered in part.
 */
export function parseEvidence(value: unknown, shown: PlanResult): CalculationEvidence | null {
  const row = record(value)
  if (row === null) return null
  const snapshots = record(row.snapshots)
  const times = record(row.times)
  const inputs = record(row.inputs)
  const calculation = record(row.calculation)
  const versions = record(row.versions)
  const range = record(inputs?.sourceRange)
  const cashflows = record(inputs?.cashflows)
  const plan = record(inputs?.plan)
  const conditions = record(plan?.conditions)
  const metrics = list(row.metrics)
  const official = list(versions?.official)
  const unpublished = list(versions?.unpublished)
  if (
    snapshots === null ||
    times === null ||
    inputs === null ||
    calculation === null ||
    range === null ||
    cashflows === null ||
    plan === null ||
    conditions === null ||
    metrics === null ||
    official === null ||
    unpublished === null
  ) {
    return null
  }
  const shownTerms = shown.conditions
  const whole =
    row.planId === shown.planId &&
    row.pointerVersion === shown.pointerVersion &&
    row.calculationId === shown.calculationId &&
    strings(snapshots, [
      'portfolioSnapshotId',
      'basisPortfolioSnapshotId',
      'basisSourceSnapshotId',
      'sourceSnapshotId',
    ]) &&
    count(snapshots.portfolioSequence) &&
    snapshots.portfolioSnapshotId === shown.portfolioSnapshotId &&
    snapshots.basisPortfolioSnapshotId === shown.basisPortfolioSnapshotId &&
    strings(times, ['t0', 'tc', 'measuredAt', 'cashflowVerifiedThrough']) &&
    times.t0 === shownTerms.startAt &&
    times.tc === shown.basisAt &&
    times.measuredAt === shown.measuredAt &&
    times.cashflowVerifiedThrough === shown.cashflowVerifiedThrough &&
    strings(inputs.connection, ['provider', 'connectionId', 'policyVersion']) &&
    strings(range, ['collectionPolicy', 'completenessHash']) &&
    // The collection covered exactly (start, tc]; the start is t0 for the first calculation.
    isCollection(row.collection, shown, range.rangeStart) &&
    range.rangeEnd === times.tc &&
    isInstant(range.rangeStart) &&
    isInstant(range.rangeEnd) &&
    strings(inputs.classification, ['classificationId', 'policyVersion', 'inputScopeHash']) &&
    strings(inputs.conversion, ['conversionId', 'pricePolicy', 'resultFingerprint']) &&
    count(cashflows.appliedCount) &&
    count(cashflows.estimatedCount) &&
    cashflows.appliedCount === shown.flowCount &&
    cashflows.estimatedCount === shown.estimatedFlowCount &&
    plan.planId === shown.planId &&
    // The plan version is the shown plan's terms, to the letter.
    conditions.principal === shownTerms.principal &&
    conditions.startAt === shownTerms.startAt &&
    conditions.endAt === shownTerms.endAt &&
    conditions.period === shownTerms.period &&
    conditions.targetRate === shownTerms.targetRate &&
    isPlainNonNegative(conditions.principal) &&
    isPlainNonNegative(conditions.targetRate) &&
    strings(calculation, [
      'calculationId',
      'calculationPolicy',
      'calendarPolicy',
      'displayPolicy',
      'precisionPolicy',
      'timeZone',
      'implementationVersion',
      'inputHash',
      'resultHash',
    ]) &&
    isInstant(calculation.createdAt) &&
    calculation.calculationId === shown.calculationId &&
    metrics.length === METRIC_ORDER.length &&
    metrics.every((metric, index) => isImpact(metric, shown, index, shown.estimatedFlowCount)) &&
    official.length > 0 &&
    // The current version and at most ten earlier ones, never more than the plan has had.
    official.length <= OFFICIAL_VERSIONS_LISTED &&
    official.every(isOfficialVersion) &&
    newestFirst(official) &&
    count(versions?.officialCount) &&
    versions.officialCount >= official.length &&
    unpublished.every(isUnpublished)
  if (!whole) return null
  const evidence = row as unknown as CalculationEvidence
  // Exactly one official version is current, and it is the pointer the screen shows.
  const current = evidence.versions.official.filter((version) => version.current)
  const [only] = current
  if (
    current.length !== 1 ||
    only?.pointerVersion !== shown.pointerVersion ||
    only.calculationId !== shown.calculationId
  ) {
    return null
  }
  return evidence
}

/**
 * What the evidence region shows. `closed` has nothing. `loading` is a read in flight — `retrying` when it
 * was asked for again after a retryable failure, so the region keeps the retry control in place. `ready` is
 * the whole lineage of the shown bundle. `failed` is a read that ended without it — `retryable` when asking
 * again can help (network, 5xx, a broken lineage, a malformed answer), not when the server said the shown
 * bundle is not its current pointer (404). `denied` has dropped the evidence from memory (403, or a gone
 * session).
 */
export type EvidenceView =
  | { readonly kind: 'closed' }
  | { readonly kind: 'loading'; readonly retrying: boolean }
  | { readonly kind: 'ready'; readonly evidence: CalculationEvidence }
  | { readonly kind: 'failed'; readonly retryable: boolean }
  | { readonly kind: 'denied'; readonly cause: 'refused' | 'signedOut' }

export interface EvidenceState {
  readonly view: EvidenceView
  /** Opens the region and reads the shown bundle's evidence. Ignored without a bundle or while reading. */
  readonly open: () => void
  /** Closes the region and forgets whatever it held. A read still in flight is aborted. */
  readonly close: () => void
  /** Reads again after a retryable failure. */
  readonly retry: () => void
}

/**
 * The calculation evidence of the bundle the dashboard shows (Story 2.9), in its own state — never mixed
 * into the dashboard's. Nothing is read until the user opens the region, and each open reads once: there
 * is no polling. The evidence lives only in React memory, only while it belongs to the shown bundle: when
 * the bundle is replaced by another (a newer pointer, another plan) or goes away, the evidence is discarded
 * and the region closes.
 *
 * A read in flight is aborted when the region closes or the shown bundle changes; an aborted read is not a
 * failure and settles nothing. An answer is taken only if its read is still the latest and the bundle it
 * was asked for is still the shown one — the bundle key is updated in the commit that changes it, before
 * any answer can land, so a late answer never renders under a newer bundle.
 *
 * A plain read failure never touches the dashboard. A refusal (403) or a gone session (401) drops the
 * evidence and calls `onDenied` — the screen asks the dashboard to read again, and the dashboard's own read
 * decides what stays on screen (it clears its values on 401/403).
 */
export function useCalculationEvidence(
  bundle: PlanResult | null,
  onDenied: () => void,
): EvidenceState {
  const key =
    bundle === null
      ? null
      : `${bundle.planId}\n${String(bundle.pointerVersion)}\n${bundle.calculationId}`
  const [view, setView] = useState<EvidenceView>({ kind: 'closed' })
  const [shownKey, setShownKey] = useState(key)
  const sequence = useRef(0)
  const inFlight = useRef<AbortController | null>(null)
  const keyRef = useRef(key)

  // Adjusting state to a changed prop during render (as `useDashboard` does): the evidence of a bundle
  // that is no longer shown leaves the screen in the same render that stops showing the bundle.
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

  const read = useCallback(
    (retrying: boolean): void => {
      if (bundle === null || inFlight.current !== null) return
      sequence.current += 1
      const mine = sequence.current
      const askedFor = keyRef.current
      const controller = new AbortController()
      inFlight.current = controller
      setView({ kind: 'loading', retrying })
      const shown = bundle
      const latest = (): boolean => sequence.current === mine && keyRef.current === askedFor
      const settle = (next: EvidenceView): void => {
        inFlight.current = null
        setView(next)
      }
      void (async () => {
        try {
          const response = await fetch(evidencePath(shown), {
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
            settle({ kind: 'failed', retryable: false })
            return
          }
          const payload: unknown = response.status === 200 ? await response.json() : null
          if (!latest()) return
          const evidence = parseEvidence(payload, shown)
          settle(
            evidence === null ? { kind: 'failed', retryable: true } : { kind: 'ready', evidence },
          )
        } catch {
          // An aborted read is no longer the latest: it settles nothing and is not a failure.
          if (latest()) settle({ kind: 'failed', retryable: true })
        }
      })()
    },
    [bundle, onDenied],
  )

  const open = useCallback((): void => {
    if (view.kind === 'closed') read(false)
  }, [read, view.kind])

  const close = useCallback((): void => {
    sequence.current += 1
    inFlight.current?.abort()
    inFlight.current = null
    setView({ kind: 'closed' })
  }, [])

  const retry = useCallback((): void => {
    if (view.kind === 'failed' && view.retryable) read(true)
  }, [read, view])

  return { view, open, close, retry }
}

/**
 * The evidence of the bundle a dashboard shows, wired the way both landing screens need it: a refusal of
 * the evidence read asks the dashboard to read again, so the dashboard's own read decides what it keeps.
 */
export function useDashboardEvidence(dashboard: Dashboard): EvidenceState {
  return useCalculationEvidence(bundleOf(dashboard.view)?.result ?? null, dashboard.reload)
}

/**
 * The sentence the screen's single status region adds after the dashboard's, or `null` while the region is
 * closed. The evidence region itself has no live region.
 */
export function evidenceAnnouncement(view: EvidenceView): string | null {
  switch (view.kind) {
    case 'closed':
      return null
    case 'loading':
      return copy.status.loading
    case 'ready':
      return copy.status.ready
    case 'failed':
      return view.retryable ? copy.status.retryable : copy.status.stale
    case 'denied':
      return view.cause === 'signedOut' ? copy.status.signedOut : copy.status.denied
  }
}
