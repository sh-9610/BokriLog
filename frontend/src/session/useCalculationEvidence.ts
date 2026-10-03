import { useCallback, useLayoutEffect, useRef, useState } from 'react'

import type { ChangeCounts } from '../content/planVerification'

import { calculationEvidenceContent as copy, evidencePath } from '../content/calculationEvidence'
import { METRIC_ORDER, type MetricName } from '../content/planResult'
import { isCanonicalDecimal, isPlainNonNegative } from '../format/decimal'
import {
  bundleOf,
  isInstant,
  parseRanges,
  record,
  strings,
  type Dashboard,
  type PlanResult,
  type VerificationRange,
} from './useDashboard'

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

/**
 * One official version of the plan, from the pointer history (Story 2.11): when it became official and, for
 * every version but the current one, when it stopped being so.
 */
export interface OfficialVersion {
  readonly pointerVersion: number
  readonly calculationId: string
  readonly publishedAt: string
  readonly appliedUntil: string | null
  readonly current: boolean
}

/** An attempt of the same intent that published nothing. Never an official value. */
export interface UnpublishedAttempt {
  readonly status: 'FAILED' | 'BLOCKED'
  readonly reasonCode: string
  readonly finishedAt: string
}

/** One metric of the previous official version beside the shown one (Story 2.12): a value or a reason each. */
export interface EvidenceChangeMetric {
  readonly metric: MetricName
  readonly previousValue: string | null
  readonly previousUnavailableReason: string | null
  readonly value: string | null
  readonly unavailableReason: string | null
}

/**
 * One cash flow that differs from the previous official version (Story 2.12): added, removed or with another
 * USD value, whether it was found late, both sides' values and characters, and when it was discovered and
 * applied. Its Bybit id is the key to match it with the exchange's own record.
 */
export interface EvidenceChangeEvent {
  readonly sourceId: string
  readonly occurredAt: string
  readonly changeKind: string
  readonly late: boolean
  readonly transactionType: string
  readonly previousUsdValue: string | null
  readonly previousEstimated: boolean | null
  readonly usdValue: string | null
  readonly estimated: boolean | null
  readonly evidenceType: string
  readonly discoveredAt: string
  readonly appliedAt: string
}

/**
 * What changed from the previous official version and why (Story 2.12): the kind, the job's purpose and
 * reason, the three counts of late changes (added late, value changed, removed — the dashboard marker's), the
 * ranges read again and reused, the previous version and its basis time, where the impact starts, the six metrics
 * before and after, and the changed cash flows. The first official version has no previous one, and then no
 * metrics to compare.
 */
export interface EvidenceChange extends ChangeCounts {
  readonly kind: string
  readonly purpose: string | null
  readonly reason: string | null
  readonly targets: readonly VerificationRange[]
  readonly reused: readonly VerificationRange[]
  readonly previous: {
    readonly pointerVersion: number
    readonly calculationId: string
    readonly basisAt: string
  } | null
  readonly firstAffectedAt: string | null
  readonly metrics: readonly EvidenceChangeMetric[]
  readonly events: readonly EvidenceChangeEvent[]
}

/**
 * The read-only lineage of the shown official result, as `GET /api/dashboard/evidence` sends it: change →
 * snapshots → times → input versions → calculation version → metric impact → versions. No internal user id,
 * provider account id, credential or raw provider response is part of it; individual cash flows appear only as
 * the change from the previous version (Story 2.12), by their Bybit id.
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
      /**
       * Where the sync that made this version began reading Bybit again (Story 2.11). The part before it was
       * carried over from the previous official calculation's candidate. The first publication reads from `t0`.
       */
      readonly windowStart: string
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
    readonly official: readonly OfficialVersion[]
    readonly unpublished: readonly UnpublishedAttempt[]
  }
  readonly change: EvidenceChange
}

function count(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0
}

function list(value: unknown): readonly unknown[] | null {
  return Array.isArray(value) ? value : null
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
  if (row === null) return false
  // Only the current version is still applied; every earlier one names when it stopped being official.
  const applied =
    row.current === true
      ? row.appliedUntil === null || row.appliedUntil === undefined
      : isInstant(row.appliedUntil)
  return (
    typeof row.calculationId === 'string' &&
    isInstant(row.publishedAt) &&
    Number.isInteger(row.pointerVersion) &&
    typeof row.current === 'boolean' &&
    applied
  )
}

/**
 * The official versions are one unbroken history in publication order: each pointer version after the one
 * before, and every earlier version applied from its publication until exactly the next one's publication
 * (never before its own — two publications in the same millisecond are allowed, so the times only need to
 * be non-decreasing). The one current version is the last of them.
 */
function inPublicationOrder(versions: readonly OfficialVersion[]): boolean {
  return versions.every((version, index) => {
    const next = versions[index + 1]
    if (next === undefined) return true
    return (
      next.pointerVersion > version.pointerVersion &&
      version.appliedUntil === next.publishedAt &&
      Date.parse(version.appliedUntil) >= Date.parse(version.publishedAt)
    )
  })
}

/** Whether `inner` is not before `start` and not after `end` (all three instants). */
function within(inner: string, start: string, end: string): boolean {
  const at = Date.parse(inner)
  return Date.parse(start) <= at && at <= Date.parse(end)
}

/** A nullable field: `null` or absent, or the value that passes the check. */
function optional(value: unknown, check: (present: unknown) => boolean): boolean {
  return value === null || value === undefined || check(value)
}

function isDecimal(value: unknown): boolean {
  return isCanonicalDecimal(value)
}

function isBoolean(value: unknown): boolean {
  return typeof value === 'boolean'
}

function isString(value: unknown): boolean {
  return typeof value === 'string'
}

/** One side of a compared metric: a canonical value or a reason, never both and never neither. */
function isSide(value: unknown, reason: unknown): boolean {
  const hasValue = value !== null && value !== undefined
  const hasReason = reason !== null && reason !== undefined
  return hasValue !== hasReason && optional(value, isDecimal) && optional(reason, isString)
}

/** A nullable field's value, with `null` and absent alike. */
function present(value: unknown): boolean {
  return value !== null && value !== undefined
}

/**
 * One compared metric, whole: in the screen's order, each side a value or a reason, and the new side exactly
 * the shown metric — the change compares with the version the dashboard shows, and with nothing else. `null`
 * and absent mean the same on both sides of that comparison.
 */
function isChangeMetric(value: unknown, shown: PlanResult, index: number): boolean {
  const row = record(value)
  const expected = shown.metrics[index]
  if (row === null || expected === undefined || row.metric !== METRIC_ORDER[index]) return false
  return (
    isSide(row.previousValue, row.previousUnavailableReason) &&
    isSide(row.value, row.unavailableReason) &&
    (row.value ?? undefined) === (expected.value ?? undefined) &&
    (row.unavailableReason ?? undefined) === (expected.unavailableReason ?? undefined)
  )
}

/** The sign each known transaction type's USD values carry: a deposit is not negative, a withdrawal is. */
const OUTFLOW: Readonly<Record<string, boolean>> = { TRANSFER_IN: false, TRANSFER_OUT: true }

/**
 * One changed cash flow, whole and consistent with itself: each side's value comes with its character and
 * without it has none; an added flow has only the new side, a removed one only the previous side, a changed one
 * both (a change kind this screen does not know has at least one); the values of a known deposit or withdrawal
 * carry its sign; and it was discovered no later than it was applied.
 */
function isChangeEvent(value: unknown): boolean {
  const row = record(value)
  if (
    row === null ||
    typeof row.sourceId !== 'string' ||
    row.sourceId === '' ||
    !isInstant(row.occurredAt) ||
    typeof row.changeKind !== 'string' ||
    typeof row.late !== 'boolean' ||
    typeof row.transactionType !== 'string' ||
    !optional(row.previousUsdValue, isDecimal) ||
    !optional(row.previousEstimated, isBoolean) ||
    !optional(row.usdValue, isDecimal) ||
    !optional(row.estimated, isBoolean) ||
    typeof row.evidenceType !== 'string' ||
    !isInstant(row.discoveredAt) ||
    !isInstant(row.appliedAt)
  ) {
    return false
  }
  const before = present(row.previousUsdValue)
  const after = present(row.usdValue)
  if (present(row.previousEstimated) !== before || present(row.estimated) !== after) return false
  const sides: Readonly<Record<string, boolean>> = {
    ADDED: !before && after,
    REMOVED: before && !after,
    VALUE_CHANGED: before && after,
  }
  if (!(Object.hasOwn(sides, row.changeKind) ? sides[row.changeKind] : before || after))
    return false
  if (Object.hasOwn(OUTFLOW, row.transactionType)) {
    const out = OUTFLOW[row.transactionType]
    const values = [row.previousUsdValue, row.usdValue].filter(present) as string[]
    if (values.some((usd) => usd.startsWith('-') !== out)) return false
  }
  return Date.parse(row.discoveredAt) <= Date.parse(row.appliedAt)
}

/**
 * How many of the changed flows fall in each of the three late-change counts (the dashboard marker's own rule):
 * a late addition, a value change, a removal. An addition after the last check is in none of them.
 */
function countedChanges(events: readonly unknown[]): ChangeCounts {
  const of = (late: (row: Record<string, unknown>) => boolean): number =>
    events.filter((event) => {
      const row = record(event)
      return row !== null && late(row)
    }).length
  return {
    lateAddedCount: of((row) => row.changeKind === 'ADDED' && row.late === true),
    valueChangedCount: of((row) => row.changeKind === 'VALUE_CHANGED'),
    removedCount: of((row) => row.changeKind === 'REMOVED'),
  }
}

/** Whole numbers that are not negative. */
function isCount(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0
}

/**
 * The previous version, whole: exactly the official version right before the shown one in the list (the list
 * ends with the shown one), with a basis time no later than the shown one's.
 */
function isPrevious(
  previous: Record<string, unknown>,
  shown: PlanResult,
  official: readonly unknown[],
): boolean {
  const before = record(official.at(-2))
  return (
    before !== null &&
    Number.isInteger(previous.pointerVersion) &&
    (previous.pointerVersion as number) < shown.pointerVersion &&
    typeof previous.calculationId === 'string' &&
    previous.calculationId !== shown.calculationId &&
    before.pointerVersion === previous.pointerVersion &&
    before.calculationId === previous.calculationId &&
    isInstant(previous.basisAt) &&
    Date.parse(previous.basisAt) <= Date.parse(shown.basisAt)
  )
}

/**
 * The change part (Story 2.12), or `null` when it is not whole or contradicts itself. Codes it does not know
 * (the kind, the purpose, the reason, a range or change kind) are kept and folded to safe copy on screen.
 *
 * Only the first official version — `pointerVersion` 1, the only one in the list — has no previous version,
 * and then nothing to compare: no metrics, no changed flow, and not a correction or a verification. Every later
 * version names the one right before it, with exactly the six compared metrics. Each changed flow is whole and
 * appears once. The three counts are exactly the changed flows of each late kind (so the marker, the status
 * sentence and this list never disagree), and a correction has at least one while a version of a kind this screen
 * knows otherwise has none. A verification that found no change has no impact start; an impact starts no later
 * than the shown basis time.
 */
function parseChange(
  value: unknown,
  shown: PlanResult,
  official: readonly unknown[],
): EvidenceChange | null {
  const row = record(value)
  if (row === null) return null
  const targets = parseRanges(row.targets)
  const reused = parseRanges(row.reused)
  const metrics = list(row.metrics)
  const events = list(row.events)
  const previous = record(row.previous)
  if (targets === null || reused === null || metrics === null || events === null) return null
  if (row.previous !== null && row.previous !== undefined && previous === null) return null
  const first = shown.pointerVersion === 1 && official.length === 1
  const compared =
    previous === null
      ? first &&
        metrics.length === 0 &&
        events.length === 0 &&
        row.kind !== 'CORRECTED' &&
        row.kind !== 'VERIFIED_NO_CHANGE'
      : !first &&
        isPrevious(previous, shown, official) &&
        metrics.length === METRIC_ORDER.length &&
        metrics.every((metric, index) => isChangeMetric(metric, shown, index))
  const ids = events.map((event) => record(event)?.sourceId)
  const counted = countedChanges(events)
  const { lateAddedCount, valueChangedCount, removedCount } = row
  const counts =
    isCount(lateAddedCount) &&
    isCount(valueChangedCount) &&
    isCount(removedCount) &&
    lateAddedCount === counted.lateAddedCount &&
    valueChangedCount === counted.valueChangedCount &&
    removedCount === counted.removedCount
  const changed = counted.lateAddedCount + counted.valueChangedCount + counted.removedCount
  const whole =
    typeof row.kind === 'string' &&
    optional(row.purpose, isString) &&
    optional(row.reason, isString) &&
    optional(row.firstAffectedAt, isInstant) &&
    compared &&
    events.every(isChangeEvent) &&
    new Set(ids).size === ids.length &&
    counts &&
    (row.kind === 'CORRECTED'
      ? changed > 0
      : !(row.kind === 'VERIFIED_NO_CHANGE' || row.kind === 'NONE') || changed === 0) &&
    (row.kind !== 'VERIFIED_NO_CHANGE' || !present(row.firstAffectedAt)) &&
    (!isInstant(row.firstAffectedAt) ||
      Date.parse(row.firstAffectedAt) <= Date.parse(shown.basisAt))
  if (!whole) return null
  return {
    kind: row.kind as string,
    purpose: (row.purpose ?? null) as string | null,
    reason: (row.reason ?? null) as string | null,
    ...counted,
    targets,
    reused,
    previous:
      previous === null
        ? null
        : {
            pointerVersion: previous.pointerVersion as number,
            calculationId: previous.calculationId as string,
            basisAt: previous.basisAt as string,
          },
    firstAffectedAt: (row.firstAffectedAt ?? null) as string | null,
    metrics: metrics.map((metric) => {
      const entry = record(metric) ?? {}
      return {
        metric: entry.metric as MetricName,
        previousValue: (entry.previousValue ?? null) as string | null,
        previousUnavailableReason: (entry.previousUnavailableReason ?? null) as string | null,
        value: (entry.value ?? null) as string | null,
        unavailableReason: (entry.unavailableReason ?? null) as string | null,
      }
    }),
    events: events.map((event) => {
      const entry = record(event) ?? {}
      return {
        sourceId: entry.sourceId as string,
        occurredAt: entry.occurredAt as string,
        changeKind: entry.changeKind as string,
        late: entry.late as boolean,
        transactionType: entry.transactionType as string,
        previousUsdValue: (entry.previousUsdValue ?? null) as string | null,
        previousEstimated: (entry.previousEstimated ?? null) as boolean | null,
        usdValue: (entry.usdValue ?? null) as string | null,
        estimated: (entry.estimated ?? null) as boolean | null,
        evidenceType: entry.evidenceType as string,
        discoveredAt: entry.discoveredAt as string,
        appliedAt: entry.appliedAt as string,
      }
    }),
  }
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
 * bundle's; the collected range exactly `(t0, tc]`, with the window this version's sync read again inside it;
 * the official versions in publication order, the last and only current one that pointer; the metric impact
 * the shown metrics'; and the change part (Story 2.12) whole, its new metrics the shown ones and its previous
 * version one of the official list. Every instant is in the server's format. Anything else — a missing
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
    // The collection covered exactly (t0, tc].
    range.rangeStart === times.t0 &&
    range.rangeEnd === times.tc &&
    isInstant(range.rangeStart) &&
    isInstant(range.rangeEnd) &&
    // The window the sync read again starts inside [t0, tc] (at t0 for the first publication).
    isInstant(range.windowStart) &&
    within(range.windowStart, range.rangeStart, range.rangeEnd) &&
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
    official.every(isOfficialVersion) &&
    unpublished.every(isUnpublished)
  if (!whole) return null
  const change = parseChange(row.change, shown, official)
  if (change === null) return null
  const evidence = { ...(row as unknown as CalculationEvidence), change }
  // Exactly one official version is current, it is the latest, and it is the pointer the screen shows.
  const officials = evidence.versions.official
  const current = officials.filter((version) => version.current)
  const [only] = current
  if (
    current.length !== 1 ||
    officials.at(-1) !== only ||
    !inPublicationOrder(officials) ||
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
