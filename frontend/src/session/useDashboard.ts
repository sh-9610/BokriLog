import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'

import type { SyncPhase } from './useDashboardSync'

import { comparisonName, planCompletionContent } from '../content/planCompletion'
import { planEndContent } from '../content/planEnd'
import {
  planResultContent as copy,
  copyFor,
  DASHBOARD_PATH,
  METRIC_ORDER,
  type MetricName,
} from '../content/planResult'
import { planSyncContent } from '../content/planSync'
import { planVerificationContent, type ChangeCounts } from '../content/planVerification'
import { formatSeoulTime, isCanonicalDecimal, isPlainNonNegative } from '../format/decimal'
import { isInstant } from './instant'

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
  /**
   * What the version is (Story 2.14): a progress version calculated before `T`, or the completion version of a plan
   * completed at its scheduled end, calculated at `T` with the first observation after it.
   */
  readonly calculationKind: CalculationKind
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

/** The two kinds of calculation version (Story 2.14). The calculation evidence names the same kinds. */
export type CalculationKind = 'PROGRESS' | 'COMPLETION'

/** Whether the value is one of the two calculation kinds. */
export function isCalculationKind(value: unknown): value is CalculationKind {
  return value === 'PROGRESS' || value === 'COMPLETION'
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
    isCalculationKind(row.calculationKind) &&
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

/**
 * The three triggers that admit a sync (SYNC-UX-1.0). A job may carry a trigger this screen does not know
 * (Story 2.12 adds `SYSTEM` for the jobs the service admits): it is kept as it came and folded to safe copy
 * where it is shown.
 */
export type SyncTrigger = 'ENTRY' | 'VISIBILITY' | 'MANUAL'

/**
 * What a job of the plan's single job table is for (Story 2.12): a sync, a precision verification, or the
 * catch-up after a long gap. Whatever the purpose, a plan has at most one job in progress.
 */
export type SyncPurpose = 'SYNC' | 'VERIFY' | 'CATCH_UP'

/** A sync job's status: in progress (`QUEUED`·`RUNNING`), or one of four endings. */
export type SyncJobStatus =
  'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'NEEDS_REVIEW' | 'BLOCKED'
const SYNC_JOB_STATUSES: readonly string[] = [
  'QUEUED',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'NEEDS_REVIEW',
  'BLOCKED',
]

/**
 * One sync job as the server shows it (Story 2.11): codes and server times only. Every code — the purpose,
 * the reason, the trigger, the status, the stage and the failure code — may be one this screen does not
 * know; it is kept as it came and folded to safe copy where it is shown. A status it does not know is an
 * ending (not in progress). A job without a purpose (a server before Story 2.12) is a sync.
 */
export interface SyncJob {
  readonly jobId: string
  readonly purpose: string
  readonly reason: string | null
  readonly trigger: string
  readonly status: string
  readonly stage: string | null
  readonly failureCode: string | null
  readonly admittedAt: string
  readonly finishedAt: string | null
}

/** Whether the job has not ended yet. */
export function inProgress(job: SyncJob): boolean {
  return job.status === 'QUEUED' || job.status === 'RUNNING'
}

/** The job's purpose as the screen groups it: a purpose it does not know is shown with the syncs. */
export function purposeOf(job: SyncJob): SyncPurpose {
  return job.purpose === 'VERIFY' || job.purpose === 'CATCH_UP' ? job.purpose : 'SYNC'
}

/** Whether a sync may be asked for now, as the server judged it for the shown plan. */
export type SyncAvailability = 'AVAILABLE' | 'IN_PROGRESS' | 'COOLDOWN' | 'LONG_GAP' | 'UNAVAILABLE'
const SYNC_AVAILABILITIES: readonly string[] = [
  'AVAILABLE',
  'IN_PROGRESS',
  'COOLDOWN',
  'LONG_GAP',
  'UNAVAILABLE',
]

/**
 * The `sync` block of a `READY` answer (Story 2.11): whether a sync can be asked for, why not
 * (`UNAVAILABLE` only; `null` when the reason is not known), in how many seconds a manual one is admitted
 * again (`COOLDOWN` only — a relative duration, so this screen never compares a server time with its own
 * clock), and the plan's latest job.
 */
export interface SyncStatus {
  readonly availability: SyncAvailability
  readonly reason: string | null
  readonly retryAfterSeconds: number | null
  readonly latest: SyncJob | null
}

/**
 * The sync block this screen cannot read: a sync is not offered, for a reason not known. The financial bundle
 * beside it is still shown — the sync block never makes the bundle unacceptable.
 */
export const UNKNOWN_SYNC: SyncStatus = {
  availability: 'UNAVAILABLE',
  reason: null,
  retryAfterSeconds: null,
  latest: null,
}

/** A nullable field: the server sends `null` explicitly, and an absent key means the same. */
function absent(value: unknown): value is null | undefined {
  return value === null || value === undefined
}

function positiveInteger(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) > 0
}

/**
 * A sync job, or `null` when it is not one whole job: an id, string codes, and server instants. For a
 * status this screen knows, the status and the ending agree — a job in progress has neither an end time
 * nor a code; a finished one has its end time; only an ending other than `SUCCEEDED` names why. A code it
 * does not know is not malformed: it is kept and folded to safe copy on screen.
 */
export function parseSyncJob(value: unknown): SyncJob | null {
  const row = record(value)
  if (row === null) return null
  const { jobId, purpose, reason, trigger, status, stage, failureCode, admittedAt, finishedAt } =
    row
  if (typeof jobId !== 'string' || jobId === '') return null
  if (typeof trigger !== 'string' || typeof status !== 'string') return null
  if (!absent(purpose) && typeof purpose !== 'string') return null
  if (!absent(reason) && typeof reason !== 'string') return null
  if (!absent(stage) && typeof stage !== 'string') return null
  if (!absent(failureCode) && typeof failureCode !== 'string') return null
  if (!isInstant(admittedAt) || !(absent(finishedAt) || isInstant(finishedAt))) return null
  const job: SyncJob = {
    jobId,
    purpose: absent(purpose) ? 'SYNC' : purpose,
    reason: absent(reason) ? null : reason,
    trigger,
    status,
    stage: absent(stage) ? null : stage,
    failureCode: absent(failureCode) ? null : failureCode,
    admittedAt,
    finishedAt: absent(finishedAt) ? null : finishedAt,
  }
  if (!SYNC_JOB_STATUSES.includes(status)) return job
  const running = inProgress(job)
  if (running !== (job.finishedAt === null)) return null
  return (running || job.status === 'SUCCEEDED') === (job.failureCode === null) ? job : null
}

/**
 * The `sync` block, always as something this screen can show (P14: the sync block never makes the financial
 * bundle unacceptable). A whole block is taken as it is: a reason exactly when unavailable, a retry interval
 * exactly when cooling down, and an in-progress answer only with the job in progress. An availability this
 * screen does not know is `UNAVAILABLE` for an unknown reason, keeping the latest job; a block that is missing
 * or does not agree with itself is `UNKNOWN_SYNC` (keeping the latest job when that job is whole).
 */
export function parseSyncStatus(value: unknown): SyncStatus {
  const row = record(value)
  if (row === null) return UNKNOWN_SYNC
  const { availability, reason, retryAfterSeconds, latest } = row
  const job = absent(latest) ? null : parseSyncJob(latest)
  if (job === null && !absent(latest)) return UNKNOWN_SYNC
  const folded: SyncStatus = { ...UNKNOWN_SYNC, latest: job }
  if (typeof availability !== 'string' || !SYNC_AVAILABILITIES.includes(availability)) return folded
  if (!absent(reason) && typeof reason !== 'string') return folded
  if (!absent(retryAfterSeconds) && !positiveInteger(retryAfterSeconds)) return folded
  const status: SyncStatus = {
    availability: availability as SyncAvailability,
    reason: absent(reason) ? null : reason,
    retryAfterSeconds: absent(retryAfterSeconds) ? null : retryAfterSeconds,
    latest: job,
  }
  if ((status.availability === 'UNAVAILABLE') !== (status.reason !== null)) return folded
  if ((status.availability === 'COOLDOWN') !== (status.retryAfterSeconds !== null)) return folded
  if (status.availability === 'IN_PROGRESS' && (job === null || !inProgress(job))) return folded
  return status
}

/** One range a verification read again or reused: its kind and its bounds, as server instants. */
export interface VerificationRange {
  readonly kind: string
  readonly start: string
  readonly end: string
}

/** How the reread events compared with the official ones (Story 2.12): five counts. */
export interface VerificationCounts {
  readonly new: number
  readonly same: number
  readonly missing: number
  readonly superseded: number
  readonly conflict: number
}

/**
 * The plan's latest verification or catch-up job, with the ranges it planned and what it found. A list of
 * ranges the screen could not read is `null` — never an empty list, which would say there was none.
 * `holdsResult` is true only for the job whose review holds the shown result: the persistent review of the
 * current basis (the input reliability's `VERIFICATION_REVIEW_REQUIRED` or `HISTORY_OUT_OF_RANGE`).
 */
export interface VerificationJob extends SyncJob {
  readonly targets: readonly VerificationRange[] | null
  readonly reused: readonly VerificationRange[] | null
  readonly counts: VerificationCounts | null
  readonly holdsResult: boolean
}

/** What a precision verification would read if asked for now: the server's plan, before any admission. */
export interface VerificationPreview {
  readonly rangeStart: string
  readonly unconfirmedFrom: string
  readonly anomalyCount: number
  readonly reusedUntil: string | null
}

/** Whether a precision verification may be asked for now, as the server judged it for the shown plan. */
export type VerificationAvailability =
  'AVAILABLE' | 'IN_PROGRESS' | 'COOLDOWN' | 'UNAVAILABLE' | 'NOT_USEFUL' | 'LONG_GAP'
const VERIFICATION_AVAILABILITIES: readonly string[] = [
  'AVAILABLE',
  'IN_PROGRESS',
  'COOLDOWN',
  'UNAVAILABLE',
  'NOT_USEFUL',
  'LONG_GAP',
]

/**
 * The `verification` block of a `READY` answer (Story 2.12): whether a precision verification can be asked
 * for, why not (`UNAVAILABLE` only), in how many seconds it — or, after a long gap, the catch-up — is admitted
 * again (a relative duration), the reason the server would record, what it would read, where a catch-up would
 * start (`LONG_GAP` only), and the plan's latest verification or catch-up job.
 */
export interface VerificationStatus {
  readonly availability: VerificationAvailability
  readonly reason: string | null
  readonly retryAfterSeconds: number | null
  readonly suggestedReason: string | null
  readonly preview: VerificationPreview | null
  readonly catchUpFrom: string | null
  readonly latest: VerificationJob | null
}

/**
 * The verification block this screen cannot read: a verification is not offered, for a reason not known. The
 * financial bundle beside it is still shown — like the sync block, it never makes the bundle unacceptable.
 */
export const UNKNOWN_VERIFICATION: VerificationStatus = {
  availability: 'UNAVAILABLE',
  reason: null,
  retryAfterSeconds: null,
  suggestedReason: null,
  preview: null,
  catchUpFrom: null,
  latest: null,
}

function count(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0
}

/**
 * A list of ranges, or `null` when it is not one: each a string kind and two instants, the start not after the
 * end. The calculation evidence's change part (Story 2.12) shares it.
 */
export function parseRanges(value: unknown): readonly VerificationRange[] | null {
  if (!Array.isArray(value)) return null
  const ranges: VerificationRange[] = []
  for (const entry of value) {
    const range = record(entry)
    if (range === null || typeof range.kind !== 'string') return null
    if (!isInstant(range.start) || !isInstant(range.end)) return null
    if (Date.parse(range.start) > Date.parse(range.end)) return null
    ranges.push({ kind: range.kind, start: range.start, end: range.end })
  }
  return ranges
}

function parseCounts(value: unknown): VerificationCounts | null {
  const row = record(value)
  if (row === null) return null
  const { new: added, same, missing, superseded, conflict } = row
  if (![added, same, missing, superseded, conflict].every(count)) return null
  return row as unknown as VerificationCounts
}

/**
 * The latest verification job, or `null` when it is not one whole job. Its ranges and counts are details: a
 * list that is not whole is `null` (shown in safe words), never a reason to drop the job. Whether it holds the
 * shown result is true only when the server says so in a boolean.
 */
export function parseVerificationJob(value: unknown): VerificationJob | null {
  const job = parseSyncJob(value)
  const row = record(value)
  if (job === null || row === null) return null
  return {
    ...job,
    targets: parseRanges(row.targets),
    reused: parseRanges(row.reused),
    counts: parseCounts(row.counts),
    holdsResult: row.holdsResult === true,
  }
}

/**
 * The job whose review holds the shown result, or `null`: the latest verification or catch-up, ended in review,
 * and marked by the server as the persistent review of the current basis. Another job — one in progress, one
 * that published, one of an earlier basis — never names the range a held input reaches.
 */
export function heldBy(verification: VerificationStatus): VerificationJob | null {
  const job = verification.latest
  return job !== null && job.holdsResult && job.status === 'NEEDS_REVIEW' ? job : null
}

/**
 * Whether the plan is in a long gap (more than 30 days since the last check), as the verification block says
 * it: a long gap by name, or a catch-up start — which the server sends whenever the gap is long, even while
 * another state (a job in progress, an unavailability, a basis that asking again cannot help) is the one named.
 */
export function inLongGap(verification: VerificationStatus): boolean {
  return verification.availability === 'LONG_GAP' || verification.catchUpFrom !== null
}

function parsePreview(value: unknown): VerificationPreview | null {
  const row = record(value)
  if (row === null) return null
  const { rangeStart, unconfirmedFrom, anomalyCount, reusedUntil } = row
  if (!isInstant(rangeStart) || !isInstant(unconfirmedFrom) || !count(anomalyCount)) return null
  if (!absent(reusedUntil) && !isInstant(reusedUntil)) return null
  return {
    rangeStart,
    unconfirmedFrom,
    anomalyCount,
    reusedUntil: absent(reusedUntil) ? null : reusedUntil,
  }
}

/**
 * The `verification` block, always as something this screen can show (like `parseSyncStatus`, it never makes
 * the financial bundle unacceptable). Each field is read where it means something — a reason while
 * unavailable, seconds while cooling down (or after a long gap), the reason the server would record while a
 * verification can be asked for (or is cooling down), a catch-up start whenever the gap may be long (a long gap
 * by name, and the states the server judges before it: a job in progress, an unavailability, a basis that
 * asking again cannot help) — and ignored elsewhere; a long gap without its start still blocks the catch-up,
 * for a reason not known. A preview that is not whole is shown as none. An availability this screen does not
 * know, a field of the wrong type, a cool-down without its seconds, or a block that is missing, is
 * `UNKNOWN_VERIFICATION` (keeping the latest job when that job is whole).
 */
export function parseVerificationStatus(value: unknown): VerificationStatus {
  const row = record(value)
  if (row === null) return UNKNOWN_VERIFICATION
  const { availability, reason, retryAfterSeconds, suggestedReason, catchUpFrom, latest } = row
  const job = absent(latest) ? null : parseVerificationJob(latest)
  if (job === null && !absent(latest)) return UNKNOWN_VERIFICATION
  const folded: VerificationStatus = { ...UNKNOWN_VERIFICATION, latest: job }
  if (typeof availability !== 'string' || !VERIFICATION_AVAILABILITIES.includes(availability)) {
    return folded
  }
  if (!absent(reason) && typeof reason !== 'string') return folded
  if (!absent(retryAfterSeconds) && !positiveInteger(retryAfterSeconds)) return folded
  if (!absent(suggestedReason) && typeof suggestedReason !== 'string') return folded
  if (!absent(catchUpFrom) && !isInstant(catchUpFrom)) return folded
  // A cool-down cannot be counted down without its seconds; nothing else needs a field it may lack.
  if (availability === 'COOLDOWN' && absent(retryAfterSeconds)) return folded
  const seconds = availability === 'COOLDOWN' || availability === 'LONG_GAP'
  // A verification can be asked for (now or after its cool-down): only then is the gap not long.
  const askable = availability === 'AVAILABLE' || availability === 'COOLDOWN'
  return {
    availability: availability as VerificationAvailability,
    // Each field only where it means something: a reason of an unavailability, the seconds of a cool-down
    // (a catch-up after a long gap may be cooling down too), the reason a verification would be recorded with,
    // the start of a catch-up whenever the gap may be long.
    reason: availability === 'UNAVAILABLE' && !absent(reason) ? reason : null,
    retryAfterSeconds: seconds && !absent(retryAfterSeconds) ? retryAfterSeconds : null,
    suggestedReason: askable && !absent(suggestedReason) ? suggestedReason : null,
    preview: parsePreview(row.preview),
    catchUpFrom: !askable && !absent(catchUpFrom) ? catchUpFrom : null,
    latest: job,
  }
}

/**
 * What the current official version changed (Story 2.12): `CORRECTED` when it differs from the previous official
 * version by a late addition, a value change or a removal, `VERIFIED_NO_CHANGE` when a verification or a catch-up
 * made it without any, `NONE` otherwise.
 */
export type VersionChangeKind = 'CORRECTED' | 'VERIFIED_NO_CHANGE' | 'NONE'

/**
 * The `change` block of a `READY` answer: the result marker of the shown version. Its three counts split what
 * differs from the previous official version — added late, a USD value or estimated flag changed, removed from the
 * applied flows (an addition after the last check is not a late change and is not counted) — and are said apart,
 * so a value change (another user's price correction, say) is never called a late deposit or withdrawal.
 */
export interface VersionChange extends ChangeCounts {
  readonly kind: VersionChangeKind
  readonly purpose: string | null
  readonly reason: string | null
  readonly firstAffectedAt: string | null
  readonly verifiedAt: string | null
  readonly targets: readonly VerificationRange[]
  readonly reused: readonly VerificationRange[]
}

/** No marker: the version says nothing about late changes, or the block could not be read. */
export const NO_CHANGE: VersionChange = {
  kind: 'NONE',
  purpose: null,
  reason: null,
  lateAddedCount: 0,
  valueChangedCount: 0,
  removedCount: 0,
  firstAffectedAt: null,
  verifiedAt: null,
  targets: [],
  reused: [],
}

/**
 * The `change` block, or `NO_CHANGE` when it is missing, not whole, of a kind this screen does not know, or
 * contradicts itself — a marker it cannot vouch for is not shown, and never a reason to drop the bundle. Its three
 * counts are whole numbers that add up to `lateChangeCount`, and the kind agrees with them: a correction has at
 * least one, any other version none. A version without a late change (`VERIFIED_NO_CHANGE`, `NONE`) has no impact
 * start either, and an impact starts no later than the shown basis time (`basisAt`, when given).
 */
export function parseVersionChange(value: unknown, basisAt: string | null = null): VersionChange {
  const row = record(value)
  if (row === null) return NO_CHANGE
  const { kind, purpose, reason, firstAffectedAt, verifiedAt } = row
  const { lateChangeCount, lateAddedCount, valueChangedCount, removedCount } = row
  if (kind !== 'CORRECTED' && kind !== 'VERIFIED_NO_CHANGE' && kind !== 'NONE') return NO_CHANGE
  if (!absent(purpose) && typeof purpose !== 'string') return NO_CHANGE
  if (!absent(reason) && typeof reason !== 'string') return NO_CHANGE
  if (![lateChangeCount, lateAddedCount, valueChangedCount, removedCount].every(count)) {
    return NO_CHANGE
  }
  const counts: ChangeCounts = {
    lateAddedCount: lateAddedCount as number,
    valueChangedCount: valueChangedCount as number,
    removedCount: removedCount as number,
  }
  const changed = counts.lateAddedCount + counts.valueChangedCount + counts.removedCount
  if (changed !== lateChangeCount) return NO_CHANGE
  if (!absent(firstAffectedAt) && !isInstant(firstAffectedAt)) return NO_CHANGE
  if (!absent(verifiedAt) && !isInstant(verifiedAt)) return NO_CHANGE
  if ((kind === 'CORRECTED') !== changed > 0) return NO_CHANGE
  if (kind !== 'CORRECTED' && !absent(firstAffectedAt)) return NO_CHANGE
  if (
    !absent(firstAffectedAt) &&
    isInstant(basisAt) &&
    Date.parse(firstAffectedAt) > Date.parse(basisAt)
  ) {
    return NO_CHANGE
  }
  const targets = parseRanges(row.targets ?? [])
  const reused = parseRanges(row.reused ?? [])
  if (targets === null || reused === null) return NO_CHANGE
  return {
    kind,
    purpose: absent(purpose) ? null : purpose,
    reason: absent(reason) ? null : reason,
    ...counts,
    firstAffectedAt: absent(firstAffectedAt) ? null : firstAffectedAt,
    verifiedAt: absent(verifiedAt) ? null : verifiedAt,
    targets,
    reused,
  }
}

/**
 * Whether the shown active plan may be ended now, as the server judged it in the same read (Story 2.13):
 * `AVAILABLE` (the user may end it early), `NEW_PLAN_CHOICE` (a long gap: only the long-gap choice `새 계획
 * 시작` ends it, with the gap recorded) or `UNAVAILABLE` (with a reason when known: `RESTRICTED`,
 * `PLAN_PERIOD_ENDED`).
 */
export type PlanEndAvailability = 'AVAILABLE' | 'NEW_PLAN_CHOICE' | 'UNAVAILABLE'

/** The `planEnd` block of a `READY` answer (Story 2.13). */
export interface PlanEndStatus {
  readonly availability: PlanEndAvailability
  readonly reason: string | null
}

/**
 * The plan-end block this screen cannot read: ending is not offered, for a reason not known. Like the sync block
 * it never makes the financial bundle unacceptable.
 */
export const UNKNOWN_PLAN_END: PlanEndStatus = { availability: 'UNAVAILABLE', reason: null }

/**
 * The `planEnd` block, always as something this screen can show. An availability this screen does not know, a
 * reason of the wrong type, or a missing block, is `UNKNOWN_PLAN_END`. A reason is kept only while unavailable
 * (an unknown one is folded to safe copy where it is shown).
 */
export function parsePlanEndStatus(value: unknown): PlanEndStatus {
  const row = record(value)
  if (row === null) return UNKNOWN_PLAN_END
  const { availability, reason } = row
  if (
    availability !== 'AVAILABLE' &&
    availability !== 'NEW_PLAN_CHOICE' &&
    availability !== 'UNAVAILABLE'
  ) {
    return UNKNOWN_PLAN_END
  }
  if (!absent(reason) && typeof reason !== 'string') return UNKNOWN_PLAN_END
  return { availability, reason: availability === 'UNAVAILABLE' && !absent(reason) ? reason : null }
}

/**
 * The end record of a plan ended early (Story 2.13), as `GET /api/dashboard` sends it with `ENDED`: why and when
 * it ended (the server's time), its scheduled end `T`, the last normal measurement it ended on and how long before
 * the end that was (the server's milliseconds — this screen never compares a server time with its own clock), the
 * completion comparison, and — after a long gap — the range that was never collected. The reason and the
 * completion may be codes this screen does not know; they are kept and folded to safe copy where shown.
 */
export interface PlanEndRecord {
  readonly reason: string
  readonly endedAt: string
  readonly scheduledEndAt: string
  readonly lastNormalMeasuredAt: string
  readonly lastNormalDelayMillis: number
  readonly completion: string
  readonly uncollectedFrom: string | null
  readonly uncollectedUntil: string | null
}

/** Whether two server instants name the same millisecond (notation such as `.000` aside). */
function sameInstant(left: string, right: string): boolean {
  return Date.parse(left) === Date.parse(right)
}

/** How each known end reason records the completion comparison and the uncollected range. */
const END_COMPLETIONS: Readonly<Record<string, string>> = {
  USER_REQUEST: 'NOT_APPLICABLE',
  LONG_GAP_NEW_PLAN: 'UNAVAILABLE_DATA_GAP',
}

/**
 * The `end` record of an `ENDED` answer, or `null` when it is not one whole record that agrees with the bundle it
 * came with: every key present, the times server instants; the scheduled end is the plan's `T` and the last normal
 * measurement the bundle's own; the end is not before that measurement and before `T` (an early end); the delay is
 * exactly the end minus that measurement, in milliseconds (both are the server's millisecond instants); the
 * uncollected range is both instants or neither, and when present runs from before the end exactly to the end. A known reason records what the contract fixes for it — no range and no comparison
 * for an early end the user asked for; for a long gap, the range from the last check to the end and a comparison
 * that cannot be calculated. A reason or completion this screen does not know is not checked against them.
 */
export function parsePlanEndRecord(value: unknown, result: PlanResult): PlanEndRecord | null {
  const row = record(value)
  if (row === null) return null
  const { reason, endedAt, scheduledEndAt, lastNormalMeasuredAt, lastNormalDelayMillis } = row
  const { completion, uncollectedFrom, uncollectedUntil } = row
  if (typeof reason !== 'string' || typeof completion !== 'string') return null
  if (!isInstant(endedAt) || !isInstant(scheduledEndAt) || !isInstant(lastNormalMeasuredAt)) {
    return null
  }
  if (!count(lastNormalDelayMillis)) return null
  if (!(uncollectedFrom === null || isInstant(uncollectedFrom))) return null
  if (!(uncollectedUntil === null || isInstant(uncollectedUntil))) return null
  if (!sameInstant(scheduledEndAt, result.conditions.endAt)) return null
  if (!sameInstant(lastNormalMeasuredAt, result.measuredAt)) return null
  if (Date.parse(endedAt) < Date.parse(lastNormalMeasuredAt)) return null
  if (Date.parse(endedAt) >= Date.parse(scheduledEndAt)) return null
  if (lastNormalDelayMillis !== Date.parse(endedAt) - Date.parse(lastNormalMeasuredAt)) return null
  if ((uncollectedFrom === null) !== (uncollectedUntil === null)) return null
  if (uncollectedFrom !== null && uncollectedUntil !== null) {
    if (Date.parse(uncollectedFrom) >= Date.parse(uncollectedUntil)) return null
    if (!sameInstant(uncollectedUntil, endedAt)) return null
  }
  const expected = END_COMPLETIONS[reason]
  if (expected !== undefined) {
    if (Object.values(END_COMPLETIONS).includes(completion) && completion !== expected) return null
    if (reason === 'USER_REQUEST' && uncollectedFrom !== null) return null
    if (
      reason === 'LONG_GAP_NEW_PLAN' &&
      (uncollectedFrom === null || !sameInstant(uncollectedFrom, result.cashflowVerifiedThrough))
    ) {
      return null
    }
  }
  return {
    reason,
    endedAt,
    scheduledEndAt,
    lastNormalMeasuredAt,
    lastNormalDelayMillis,
    completion,
    uncollectedFrom,
    uncollectedUntil,
  }
}

/** The plan status of a plan completed at its scheduled end `T` (Story 2.14). The evidence's version list reads it. */
export const PLAN_COMPLETED = 'COMPLETED'

/** The four completion comparisons (Story 2.14): checking, confirmed, not computable, and held for review. */
const COMPARISONS: readonly string[] = ['PENDING', 'CONFIRMED', 'UNAVAILABLE', 'HELD']

/** The reasons a comparison that cannot be calculated may carry, and the one a held comparison carries. */
const UNAVAILABLE_REASONS: readonly string[] = [
  'OBSERVATION_DELAY_EXCEEDED',
  'POST_END_CASHFLOW',
  'HISTORY_OUT_OF_RANGE',
  'DATA_GAP',
]
const HELD_REASON = 'INPUT_REVIEW_REQUIRED'

/** The two `UNAVAILABLE` reasons whose inputs are whole: a completion calculation is published for them. */
const COMPLETION_CALCULATED_REASONS: readonly string[] = [
  'OBSERVATION_DELAY_EXCEEDED',
  'POST_END_CASHFLOW',
]

/** The longest delay from `T` to the first observation that still allows the comparison: 24 hours, inclusive. */
export const OBSERVATION_DELAY_LIMIT_MS = 24 * 60 * 60 * 1000

/** The reason a derived metric of a completion version is not computable when the comparison is not (Story 2.14). */
const NOT_COMPARABLE = 'COMPLETION_NOT_COMPARABLE'

/**
 * The completion record of a plan completed at its scheduled end `T` (Story 2.14), as `GET /api/dashboard` sends it
 * with `COMPLETED`: `T`, the comparison and its reason, the first normal observation after `T` (its value, the time
 * Bybit measured it and the delay from `T`, the server's milliseconds — never a valuation at `T`), the completion
 * target of the completion calculation and the number of external flows between `T` and the observation. The three
 * observed fields are all present or all `null`; the last two only once a completion calculation exists. A
 * comparison or a reason this screen does not know is kept and folded to safe copy where shown.
 */
export interface PlanCompletionRecord {
  readonly scheduledEndAt: string
  readonly comparison: string
  readonly reason: string | null
  readonly observedValue: string | null
  readonly observedMeasuredAt: string | null
  readonly observationDelayMillis: number | null
  readonly completionTarget: string | null
  readonly postEndCashflowCount: number | null
  /**
   * Whether a sync runs the completion again (the server's judgment): no observation yet, a comparison still checking,
   * or one held for a cause a sync can clear. When false the comparison is final — or held for a cause no sync clears —
   * and a sync only refreshes the value after the end.
   */
  readonly retryable: boolean
}

/** The current value after the first observation (Story 2.14): what later syncs measured. Never the completion. */
export interface PostEndValue {
  readonly value: string
  readonly measuredAt: string
}

/**
 * Whether the result is the completion version of its plan (Story 2.14), as the server names its kind. A completion
 * version is calculated at the plan's scheduled end `T` (`parsePlanCompletion` checks it); a progress version before.
 */
export function isCompletionVersion(result: PlanResult): boolean {
  return result.calculationKind === 'COMPLETION'
}

/** The value of a metric of the result, or `undefined` when it is not computable. */
function metricValue(result: PlanResult, name: MetricName): string | undefined {
  return result.metrics.find((metric) => metric.metric === name)?.value
}

function metricReason(result: PlanResult, name: MetricName): string | undefined {
  return result.metrics.find((metric) => metric.metric === name)?.unavailableReason
}

/**
 * The `completion` record of a `COMPLETED` answer, or `null` when it is not one whole record that agrees with the
 * bundle it came with (Story 2.14). Its shape: the scheduled end is an instant and the plan's own `T`; the comparison
 * a string; the reason a string or `null`; the observed value a canonical decimal, its time an instant and its delay
 * a whole number of milliseconds — the three all present or all `null`; the target a canonical decimal or `null`; the
 * count a whole number or `null`. An observation is never before `T`, and its delay is exactly its time minus `T`.
 *
 * The record agrees with the bundle: the bundle is the completion version (calculated at `T`) exactly when the record
 * names a completion target and a count — and then the observation exists, the current value is the observed value,
 * measured at the observation's time, the plan amount is the target, the required rate is not computable, and the
 * derived metrics of a comparison that cannot be calculated say so without a value. A known comparison carries what
 * the contract fixes for it: no reason while checking or confirmed; a confirmed one on a completion version, within 24
 * hours, with no flow after `T`; a comparison that cannot be calculated with one of its four reasons — its delay over 24
 * hours or its flows after `T` on a completion version, its data gap or out-of-range history without one; a held one
 * with its review reason and no completion version. A comparison or a reason this screen does not know is not checked
 * against these.
 */
export function parsePlanCompletion(
  value: unknown,
  result: PlanResult,
  historical = false,
): PlanCompletionRecord | null {
  const row = record(value)
  if (row === null) return null
  const { scheduledEndAt, comparison, reason, observedValue, observedMeasuredAt } = row
  const { observationDelayMillis, completionTarget, postEndCashflowCount, retryable } = row
  if (!isInstant(scheduledEndAt) || typeof comparison !== 'string') return null
  if (!absent(reason) && typeof reason !== 'string') return null
  if (!absent(observedValue) && !isCanonicalDecimal(observedValue)) return null
  if (!absent(observedMeasuredAt) && !isInstant(observedMeasuredAt)) return null
  if (!absent(observationDelayMillis) && !count(observationDelayMillis)) return null
  if (!absent(completionTarget) && !isCanonicalDecimal(completionTarget)) return null
  if (!absent(postEndCashflowCount) && !count(postEndCashflowCount)) return null
  if (typeof retryable !== 'boolean') return null
  const parsed: PlanCompletionRecord = {
    scheduledEndAt,
    comparison,
    reason: absent(reason) ? null : reason,
    observedValue: absent(observedValue) ? null : observedValue,
    observedMeasuredAt: absent(observedMeasuredAt) ? null : observedMeasuredAt,
    observationDelayMillis: absent(observationDelayMillis) ? null : observationDelayMillis,
    completionTarget: absent(completionTarget) ? null : completionTarget,
    postEndCashflowCount: absent(postEndCashflowCount) ? null : postEndCashflowCount,
    retryable,
  }
  if (!sameInstant(scheduledEndAt, result.conditions.endAt)) return null
  // The observation: all three or none, never before T, its delay exactly its time minus T.
  const observed = parsed.observedMeasuredAt !== null
  if (
    (parsed.observedValue !== null) !== observed ||
    (parsed.observationDelayMillis !== null) !== observed
  ) {
    return null
  }
  if (parsed.observedMeasuredAt !== null) {
    const delay = Date.parse(parsed.observedMeasuredAt) - Date.parse(scheduledEndAt)
    if (delay < 0 || parsed.observationDelayMillis !== delay) return null
  }
  // The completion calculation: its target and count come with it, and with nothing else.
  const completion = isCompletionVersion(result)
  // A completion version is calculated at T, a progress version before it.
  if (sameInstant(result.basisAt, result.conditions.endAt) !== completion) return null
  if ((parsed.completionTarget !== null) !== completion) return null
  if ((parsed.postEndCashflowCount !== null) !== completion) return null
  if (completion) {
    if (parsed.observedMeasuredAt === null || parsed.observedValue === null) return null
    if (!sameInstant(result.measuredAt, parsed.observedMeasuredAt)) return null
    if (metricValue(result, 'CURRENT_VALUE') !== parsed.observedValue) return null
    if (metricValue(result, 'PLAN_AMOUNT') !== parsed.completionTarget) return null
    if (metricValue(result, 'REQUIRED_RATE') !== undefined) return null
  }
  if (!agreesWithComparison(parsed, result, completion)) return null
  // A sync runs the completion again while the comparison is checking, never once it is final. (Before an observation a
  // comparison that cannot be calculated — a data gap — is already final; the server says whether a sync still runs.)
  if (!historical && parsed.comparison === 'PENDING' && !parsed.retryable) return null
  if (
    (parsed.comparison === 'CONFIRMED' || parsed.comparison === 'UNAVAILABLE') &&
    parsed.retryable
  ) {
    return null
  }
  return parsed
}

/** Whether the record carries what the contract fixes for its comparison (a known one only). */
function agreesWithComparison(
  completionRecord: PlanCompletionRecord,
  result: PlanResult,
  completion: boolean,
): boolean {
  const { comparison, reason, observationDelayMillis, postEndCashflowCount } = completionRecord
  const notComparable = [
    metricReason(result, 'PLAN_DIFFERENCE'),
    metricReason(result, 'GOAL_PROGRESS'),
  ]
  // The derived metrics say "not comparable" only on a completion version whose comparison cannot be calculated.
  const saysNotComparable = notComparable.includes(NOT_COMPARABLE)
  if (saysNotComparable && !completion) return false
  if (!COMPARISONS.includes(comparison)) return true
  if (saysNotComparable && comparison !== 'UNAVAILABLE') return false
  const withinDay =
    observationDelayMillis !== null && observationDelayMillis <= OBSERVATION_DELAY_LIMIT_MS
  const flows = postEndCashflowCount ?? 0
  switch (comparison) {
    case 'PENDING':
      return reason === null && !completion
    case 'CONFIRMED':
      return (
        reason === null &&
        completion &&
        withinDay &&
        flows === 0 &&
        metricValue(result, 'PLAN_DIFFERENCE') !== undefined
      )
    case 'HELD':
      return reason !== null && (reason === HELD_REASON || !isKnownReason(reason)) && !completion
    default: {
      // UNAVAILABLE: a reason, one of its own when known.
      if (reason === null) return false
      if (!isKnownReason(reason)) return true
      if (!UNAVAILABLE_REASONS.includes(reason)) return false
      if (COMPLETION_CALCULATED_REASONS.includes(reason) !== completion) return false
      if (completion && !notComparable.every((code) => code === NOT_COMPARABLE)) return false
      if (reason === 'OBSERVATION_DELAY_EXCEEDED') return !withinDay
      if (reason === 'POST_END_CASHFLOW') return flows > 0
      return true
    }
  }
}

function isKnownReason(reason: string): boolean {
  return UNAVAILABLE_REASONS.includes(reason) || reason === HELD_REASON
}

/**
 * The `postEnd` value of a `COMPLETED` answer, or `null` when there is none: absent, `null`, not one whole value
 * (a canonical decimal and an instant), or not later than the first observation it follows. It is a value of its
 * own area: one this screen cannot vouch for is not shown, and never a reason to drop the completion record.
 */
export function parsePostEndValue(
  value: unknown,
  completionRecord: PlanCompletionRecord,
): PostEndValue | null {
  const row = record(value)
  if (row === null) return null
  const { value: amount, measuredAt } = row
  if (!isCanonicalDecimal(amount) || !isInstant(measuredAt)) return null
  const observed = completionRecord.observedMeasuredAt
  if (observed === null || Date.parse(measuredAt) <= Date.parse(observed)) return null
  return { value: amount, measuredAt }
}

/**
 * The sync block of a `COMPLETED` answer (Story 2.14): read like a ready plan's, except that a completed plan never
 * meets a long gap — a block that says so is one this screen cannot read, and a sync is not offered.
 */
function parseCompletedSync(value: unknown): SyncStatus {
  const status = parseSyncStatus(value)
  return status.availability === 'LONG_GAP' ? { ...UNKNOWN_SYNC, latest: status.latest } : status
}

/**
 * One validated dashboard response. `READY` carries the active plan's financial fields, its sync block, its
 * verification block and its version change (Story 2.12), and whether it may be ended (Story 2.13). `ENDED`
 * (Story 2.13) carries the same bundle of a plan ended early — its last official result, read-only — with its end
 * record, and nothing about syncs or verifications. `COMPLETED` (Story 2.14) carries the bundle of a plan completed
 * at its scheduled end — its completion version, or its last progress version until the comparison is decided — with
 * its completion record, the value later syncs measured after the first observation, and the sync block that asks
 * for them.
 */
export type DashboardResponse =
  | { readonly state: 'NO_PLAN' | 'PREPARING'; readonly nextAction: string }
  /**
   * 계산 표시 제한이 보이는 계획의 결과를 막는다(Story 3.2). 서버는 상태·계획 정체성·다음 행동과 계산 값이 아닌
   * 블록(동기화·정밀 검증 상태, 계획 종료 가용성)을 두고 결과를 뺀다. 공식 기록은 바뀌지 않았고 화면은 값을 보이지
   * 않는다.
   */
  | {
      readonly state: 'DISPLAY_RESTRICTED'
      readonly shownState: 'READY' | 'ENDED' | 'COMPLETED'
      readonly nextAction: string
      readonly planId: string
      readonly planStatus: string
      readonly sync: SyncStatus
      readonly verification: VerificationStatus
      readonly planEnd: PlanEndStatus
    }
  | {
      readonly state: 'READY'
      readonly nextAction: string
      readonly result: PlanResult
      readonly sync: SyncStatus
      readonly verification: VerificationStatus
      readonly change: VersionChange
      readonly planEnd: PlanEndStatus
    }
  | {
      readonly state: 'ENDED'
      readonly nextAction: string
      readonly result: PlanResult
      readonly end: PlanEndRecord
    }
  | {
      readonly state: 'COMPLETED'
      readonly nextAction: string
      readonly result: PlanResult
      readonly completion: PlanCompletionRecord
      readonly postEnd: PostEndValue | null
      /** The server sent a later value this screen could not vouch for (`parsePostEndValue` dropped it). */
      readonly postEndUnreadable: boolean
      readonly sync: SyncStatus
      readonly change: VersionChange
    }

/**
 * The screen-level next actions each state may carry: Story 2.8's closed set, the two Story 2.11 added to
 * `READY` (`SYNC` when the result is not fresh, `CHOOSE_CONTINUATION` after a long gap), the one Story 2.12
 * added (`VERIFY` after a sync that ended in review), and the one of a plan ended early (Story 2.13). A pair
 * outside these is not an answer this screen understands.
 */
const NEXT_ACTIONS: Readonly<
  Record<Exclude<DashboardResponse['state'], 'DISPLAY_RESTRICTED'>, readonly string[]>
> = {
  NO_PLAN: ['CREATE_PLAN'],
  PREPARING: ['WAIT_FOR_RESULT'],
  READY: ['NONE', 'WAIT_FOR_SYNC', 'SYNC', 'VERIFY', 'CHOOSE_CONTINUATION'],
  ENDED: ['PREPARE_NEXT_PLAN'],
  COMPLETED: ['SYNC_FOR_OBSERVATION', 'PREPARE_NEXT_PLAN'],
}

/** The plan status an `ENDED` bundle carries (Story 2.13), and the one a `READY` bundle carries. */
const ENDED_EARLY = 'ENDED_EARLY'
const ACTIVE = 'ACTIVE'

/** The freshness an ended plan's bundle carries: it is no longer synced (Story 2.13). */
const ENDED_FRESHNESS = 'ENDED'

/** The freshness a completed plan's bundle carries (Story 2.14): later syncs do not change it. */
const COMPLETED_FRESHNESS = 'COMPLETED'

/**
 * A `GET /api/dashboard` body, or `null` when it is not one whole answer. A state outside the four, a
 * next action that state cannot carry, a financial field on a state that has none, or a malformed bundle
 * all reject it. The sync block, the verification block, the version change and the plan-end block never do:
 * on `READY` each is read as far as it can be (`parseSyncStatus`, `parseVerificationStatus`,
 * `parseVersionChange`, `parsePlanEndStatus`), and on the states without them they have no meaning and are
 * ignored. A `READY` bundle is an active plan's. An `ENDED` bundle is a plan ended early, its freshness folded to
 * `ENDED`, whose end record agrees with it (`parsePlanEndRecord`). A `COMPLETED` bundle (Story 2.14) is a plan
 * completed at its scheduled end, its freshness folded to `COMPLETED`, whose completion record agrees with it
 * (`parsePlanCompletion`); its later value and its sync block never reject it (`parsePostEndValue`, a sync block
 * read like a ready one's).
 */
export function parseDashboard(value: unknown, historical = false): DashboardResponse | null {
  const row = record(value)
  if (row === null || typeof row.nextAction !== 'string') return null
  if (historical && row.state !== 'ENDED' && row.state !== 'COMPLETED') return null
  if (row.calculationDisplay !== undefined) return parseDisplayRestricted(row, row.nextAction)
  if (row.state === 'NO_PLAN' || row.state === 'PREPARING') {
    return row.result === undefined && NEXT_ACTIONS[row.state].includes(row.nextAction)
      ? { state: row.state, nextAction: row.nextAction }
      : null
  }
  if (row.state === 'ENDED') {
    if (!NEXT_ACTIONS.ENDED.includes(row.nextAction) || !isPlanResult(row.result)) return null
    if (row.result.planStatus !== ENDED_EARLY || row.result.calculationKind !== 'PROGRESS') {
      return null
    }
    if (row.result.trust.freshness.state !== ENDED_FRESHNESS) return null
    const end = parsePlanEndRecord(row.end, row.result)
    return end === null
      ? null
      : { state: 'ENDED', nextAction: row.nextAction, result: row.result, end }
  }
  if (row.state === 'COMPLETED') {
    if (!NEXT_ACTIONS.COMPLETED.includes(row.nextAction) || !isPlanResult(row.result)) return null
    if (row.result.planStatus !== PLAN_COMPLETED) return null
    if (row.result.trust.freshness.state !== COMPLETED_FRESHNESS) return null
    const completion = parsePlanCompletion(row.completion, row.result, historical)
    if (completion === null) return null
    if (
      historical &&
      (completion.retryable || !absent(row.sync) || !absent(row.postEnd) || !absent(row.planEnd))
    )
      return null
    // The screen-level next action is the sync for the observation exactly while a sync runs the completion again.
    if ((row.nextAction === 'SYNC_FOR_OBSERVATION') !== completion.retryable) return null
    const postEnd = parsePostEndValue(row.postEnd, completion)
    return {
      state: 'COMPLETED',
      nextAction: row.nextAction,
      result: row.result,
      completion,
      postEnd,
      postEndUnreadable: postEnd === null && !absent(row.postEnd),
      sync: parseCompletedSync(row.sync),
      change: parseVersionChange(row.change, row.result.basisAt),
    }
  }
  if (row.state !== 'READY' || !NEXT_ACTIONS.READY.includes(row.nextAction)) return null
  if (!isPlanResult(row.result) || row.result.planStatus !== ACTIVE) return null
  if (row.result.calculationKind !== 'PROGRESS') return null
  return {
    state: 'READY',
    nextAction: row.nextAction,
    result: row.result,
    sync: parseSyncStatus(row.sync),
    verification: parseVerificationStatus(row.verification),
    change: parseVersionChange(row.change, row.result.basisAt),
    planEnd: parsePlanEndStatus(row.planEnd),
  }
}

/** 계산 표시 제한이 결과를 막는 동안의 `calculationDisplay` 값(Story 3.2). */
const DISPLAY_RESTRICTED = 'RESTRICTED'

/**
 * 계산 표시 제한이 결과를 막은 대시보드(Story 3.2): 결과가 있는 세 상태 가운데 하나, 그 상태가 가질 수 있는 다음 행동,
 * 계획 정체성, 그리고 결과 없음 — 결과를 실었거나 이 화면이 모르는 `calculationDisplay` 값이면 온전한 답이 아니다.
 * 동기화·정밀 검증·계획 종료 블록은 `READY`의 것처럼 읽을 수 있는 만큼 읽고(`COMPLETED`는 동기화만), 그 밖의 상태에는
 * 뜻이 없으므로 모름으로 둔다.
 */
function parseDisplayRestricted(
  row: Record<string, unknown>,
  nextAction: string,
): DashboardResponse | null {
  if (row.calculationDisplay !== DISPLAY_RESTRICTED || row.result !== undefined) return null
  const state = row.state
  if (state !== 'READY' && state !== 'ENDED' && state !== 'COMPLETED') return null
  if (!NEXT_ACTIONS[state].includes(nextAction)) return null
  const plan = record(row.plan)
  if (plan === null || !strings(plan, ['planId', 'planStatus'])) return null
  return {
    state: 'DISPLAY_RESTRICTED',
    shownState: state,
    nextAction,
    planId: plan.planId as string,
    planStatus: plan.planStatus as string,
    sync:
      state === 'READY'
        ? parseSyncStatus(row.sync)
        : state === 'COMPLETED'
          ? parseCompletedSync(row.sync)
          : UNKNOWN_SYNC,
    verification:
      state === 'READY' ? parseVerificationStatus(row.verification) : UNKNOWN_VERIFICATION,
    planEnd: state === 'READY' ? parsePlanEndStatus(row.planEnd) : UNKNOWN_PLAN_END,
  }
}

/** The two ends a plan has (Story 2.14): ended early (Story 2.13), or completed at its scheduled end. */
export type PlanTerminal = 'ENDED' | 'COMPLETED'

/**
 * Whether `next` may replace `shown` (AC-9). Within one plan the pointer only moves forward, and one
 * pointer version names one calculation: a version that goes back, or the same version with another
 * calculation, is not a newer bundle. A plan that has ended (Story 2.13) or completed (Story 2.14) never becomes
 * active again: within one plan an active bundle after a terminal one is not a newer bundle, and neither is the
 * other terminal state — a plan ends one way only. A terminal bundle after an active one of the same plan is.
 */
export function supersedes(
  next: PlanResult,
  shown: PlanResult | null,
  nextTerminal: PlanTerminal | null = null,
  shownTerminal: PlanTerminal | null = null,
): boolean {
  if (shown === null) return true
  if (shown.planId !== next.planId)
    return (
      new Date(next.conditions.startAt).getTime() > new Date(shown.conditions.startAt).getTime()
    )
  if (shownTerminal !== null && nextTerminal !== shownTerminal) return false
  if (next.pointerVersion < shown.pointerVersion) return false
  return next.pointerVersion !== shown.pointerVersion || next.calculationId === shown.calculationId
}

/**
 * The bundle last accepted, with the next action and the blocks it came with. A plan ended early (Story 2.13)
 * has its end record, and the sync, verification, change and plan-end blocks of a plan that is not active: no
 * sync, no verification, no change marker and no end to ask for.
 */
export interface Accepted {
  readonly result: PlanResult
  readonly nextAction: string
  readonly sync: SyncStatus
  readonly verification: VerificationStatus
  readonly change: VersionChange
  readonly planEnd: PlanEndStatus
  /** The end record of a plan ended early (Story 2.13); `null` while the plan is active. */
  readonly end: PlanEndRecord | null
  /**
   * The completion record of a plan completed at its scheduled end (Story 2.14); absent or `null` otherwise. Optional,
   * so a bundle built by hand for another state need not name it.
   */
  readonly completion?: PlanCompletionRecord | null
  /** The value later syncs measured after the first observation of a completed plan (Story 2.14), if any. */
  readonly postEnd?: PostEndValue | null
  /** The server sent such a value but this screen could not vouch for it (Story 2.14). */
  readonly postEndUnreadable?: boolean
}

/** How the bundle's plan ended, or `null` while it is active: the one terminal-state test the screen uses. */
export function terminalOf(bundle: Accepted | null): PlanTerminal | null {
  if (bundle === null) return null
  if (bundle.end !== null) return 'ENDED'
  return (bundle.completion ?? null) === null ? null : 'COMPLETED'
}

/**
 * What the screen shows. `checking` (no answer yet) is never "no plan". `ENDED` is the record of a plan ended
 * early (Story 2.13). `COMPLETED` is a plan completed at its scheduled end (Story 2.14). After a failed read the last accepted bundle stays unchanged as `lastGood` (READ_FAIL).
 * `retryable` is a read to retry: with nothing kept it has no financial value at all; after a response whose
 * version may not replace the shown bundle it keeps that bundle unswapped (RACE). `denied` has dropped the
 * bundle from memory — a refusal (403), or a session that is gone (401).
 */
export type DashboardView =
  | { readonly kind: 'checking' }
  | { readonly kind: 'NO_PLAN' | 'PREPARING'; readonly nextAction: string }
  | ({ readonly kind: 'READY' } & Accepted)
  | ({ readonly kind: 'ENDED' } & Accepted & { readonly end: PlanEndRecord })
  | ({ readonly kind: 'COMPLETED' } & Accepted & { readonly completion: PlanCompletionRecord })
  | ({ readonly kind: 'lastGood' } & Accepted)
  | { readonly kind: 'retryable'; readonly kept: Accepted | null }
  | { readonly kind: 'denied'; readonly cause: 'refused' | 'signedOut' }
  /**
   * 계산 표시 제한이 보이는 계획의 결과를 막는다(Story 3.2). 금융값은 메모리에 두지 않는다. 화면은 제한 문장과 계산 값이
   * 아닌 블록(동기화 상태·계획 종료 가용성·다음 행동)을 보이고 재시도를 두지 않는다 — 제한은 다시 불러오기가 아니라
   * 운영자가 푼다.
   */
  | {
      readonly kind: 'displayRestricted'
      readonly shownState: 'READY' | 'ENDED' | 'COMPLETED'
      readonly nextAction: string
      readonly planId: string
      readonly planStatus: string
      readonly sync: SyncStatus
      readonly verification: VerificationStatus
      readonly planEnd: PlanEndStatus
    }

export interface Dashboard {
  readonly historical?: boolean
  readonly view: DashboardView
  /**
   * A read the user or the session asked for is in flight. The screen marks the result container and its
   * status region busy, and says it is reading again. A quiet read (`refresh`) never sets it.
   */
  readonly loading: boolean
  /** Reads again. Ignored while such a read is in flight; a quiet read in flight gives way to it. */
  readonly reload: () => void
  /**
   * Reads again quietly: the answer is judged exactly like any other, but nothing says a read is in
   * flight. Only the sync hook (`useDashboardSync`) uses it: to follow a sync job in progress — one this tab
   * asked for or one the server shows it — every 2 seconds while the document is visible, for at most 3
   * minutes of visible time; and to read once after an admission answer that changes what the sync control
   * shows (unavailable, long gap). So polling never turns the status into "다시 불러오는 중". Ignored while any
   * read is in flight.
   */
  readonly refresh: () => void
}

/**
 * The longest a dashboard read may take (P9). A read that has not answered by then — the request or its
 * body — is aborted and counts as a failed read, so no read, loud or quiet, can hold the screen forever.
 */
export const DASHBOARD_REQUEST_TIMEOUT_MS = 10_000

/** One read the screen asked for, numbered; a quiet one does not mark the screen busy. */
interface Request {
  readonly serial: number
  readonly quiet: boolean
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
 * or a gone session (401) drops the accepted bundle. `loading` is true for every read in flight except a
 * quiet one (`refresh`), which is judged the same way but says nothing while it is in flight. Every read
 * is aborted after `DASHBOARD_REQUEST_TIMEOUT_MS` and then counts as a failed read.
 */
export function useDashboard(ready: boolean, planId: string | null = null): Dashboard {
  const [target, setTarget] = useState(planId)
  const [view, setView] = useState<DashboardView>({ kind: 'checking' })
  // One number per read the screen asks for: a reload, a quiet refresh, or the session becoming ready.
  const [request, setRequest] = useState<Request>({ serial: 0, quiet: false })
  const [settled, setSettled] = useState(-1)
  const [wasReady, setWasReady] = useState(ready)
  const sequence = useRef(0)
  // The kind of the read in flight, if any.
  const inFlight = useRef<'loud' | 'quiet' | null>(null)
  const accepted = useRef<Accepted | null>(null)

  if (target !== planId) {
    setTarget(planId)
    setView({ kind: 'checking' })
    setRequest((current) => ({ serial: current.serial + 1, quiet: false }))
  }
  // A selected record has its own response generation; current responses cannot overwrite it.
  useLayoutEffect(() => {
    sequence.current += 1
    accepted.current = null
  }, [planId])

  // Adjusting state to a changed prop during render (React's documented pattern, not an effect): the
  // bundle leaves the view the moment the session stops being ready, and a new read starts as loading.
  if (ready !== wasReady) {
    setWasReady(ready)
    if (ready) setRequest((current) => ({ serial: current.serial + 1, quiet: false }))
    else setView({ kind: 'checking' })
  }

  // The read stays a passive effect: starting a request is not something the commit shows, and its answer comes
  // on a later task anyway. Its time limit is that request's own and goes with it.
  useEffect(() => {
    if (!ready) {
      accepted.current = null
      return
    }
    sequence.current += 1
    const mine = sequence.current
    inFlight.current = request.quiet ? 'quiet' : 'loud'
    const latest = (): boolean => sequence.current === mine
    const failed = (): DashboardView => {
      const kept = accepted.current
      return kept === null ? { kind: 'retryable', kept: null } : { kind: 'lastGood', ...kept }
    }
    const settle = (next: DashboardView): void => {
      inFlight.current = null
      setView(next)
      setSettled(request.serial)
    }
    const controller = new AbortController()
    let deadline: number | undefined
    // The time limit covers the request and its body. It wins even over a request that ignores the abort.
    const timedOut = new Promise<never>((_, reject) => {
      deadline = window.setTimeout(() => {
        controller.abort()
        reject(new Error('The dashboard read timed out.'))
      }, DASHBOARD_REQUEST_TIMEOUT_MS)
    })
    const answer = async (): Promise<{ readonly status: number; readonly payload: unknown }> => {
      const response = await fetch(
        planId === null ? DASHBOARD_PATH : `${DASHBOARD_PATH}?planId=${encodeURIComponent(planId)}`,
        {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
          signal: controller.signal,
        },
      )
      const payload: unknown = response.status === 200 ? await response.json() : null
      return { status: response.status, payload }
    }
    const read = async (): Promise<void> => {
      try {
        const { status, payload } = await Promise.race([answer(), timedOut])
        window.clearTimeout(deadline)
        if (!latest()) return
        if (status === 401 || status === 403) {
          accepted.current = null
          settle({ kind: 'denied', cause: status === 401 ? 'signedOut' : 'refused' })
          return
        }
        const parsed = parseDashboard(payload, planId !== null)
        if (parsed === null) {
          settle(failed())
          return
        }
        if (parsed.state === 'DISPLAY_RESTRICTED') {
          if (planId !== null && parsed.planId !== planId) {
            settle(failed())
            return
          }
          // 결과가 막혔다: 금융값을 메모리에 남기지 않는다(Story 3.2).
          accepted.current = null
          settle({
            kind: 'displayRestricted',
            shownState: parsed.shownState,
            nextAction: parsed.nextAction,
            planId: parsed.planId,
            planStatus: parsed.planStatus,
            sync: parsed.sync,
            verification: parsed.verification,
            planEnd: parsed.planEnd,
          })
          return
        }
        if (parsed.state !== 'READY' && parsed.state !== 'ENDED' && parsed.state !== 'COMPLETED') {
          accepted.current = null
          settle({ kind: parsed.state, nextAction: parsed.nextAction })
          return
        }
        if (planId !== null && parsed.result.planId !== planId) {
          settle(failed())
          return
        }
        const kept = accepted.current
        if (
          !supersedes(
            parsed.result,
            kept?.result ?? null,
            parsed.state === 'READY' ? null : parsed.state,
            terminalOf(kept),
          )
        ) {
          // Not swapped in: the shown bundle stays, as a read to retry.
          settle({ kind: 'retryable', kept })
          return
        }
        if (parsed.state === 'ENDED') {
          // A plan ended early (Story 2.13): its last official bundle, read-only, with its end record.
          const ended: Accepted & { readonly end: PlanEndRecord } = {
            result: parsed.result,
            nextAction: parsed.nextAction,
            sync: UNKNOWN_SYNC,
            verification: UNKNOWN_VERIFICATION,
            change: NO_CHANGE,
            planEnd: UNKNOWN_PLAN_END,
            end: parsed.end,
          }
          accepted.current = ended
          settle({ kind: 'ENDED', ...ended })
          return
        }
        if (parsed.state === 'COMPLETED') {
          // A plan completed at its scheduled end (Story 2.14): its bundle, its completion record, the value later syncs
          // measured, the sync block that asks for them, and the version change marker. Nothing to verify or to end.
          const completed: Accepted & { readonly completion: PlanCompletionRecord } = {
            result: parsed.result,
            nextAction: parsed.nextAction,
            sync: parsed.sync,
            verification: UNKNOWN_VERIFICATION,
            change: parsed.change,
            planEnd: UNKNOWN_PLAN_END,
            end: null,
            completion: parsed.completion,
            postEnd: parsed.postEnd,
            postEndUnreadable: parsed.postEndUnreadable,
          }
          accepted.current = completed
          settle({ kind: 'COMPLETED', ...completed })
          return
        }
        const bundle: Accepted = {
          result: parsed.result,
          nextAction: parsed.nextAction,
          sync: parsed.sync,
          verification: parsed.verification,
          change: parsed.change,
          planEnd: parsed.planEnd,
          end: null,
        }
        accepted.current = bundle
        settle({ kind: 'READY', ...bundle })
      } catch {
        // A network failure, a body that is not JSON, or the time limit.
        window.clearTimeout(deadline)
        if (latest()) settle(failed())
      }
    }
    void read()
    return () => {
      // Invalidates this read: its answer, if it still comes, belongs to nobody.
      sequence.current += 1
      inFlight.current = null
      window.clearTimeout(deadline)
      controller.abort()
    }
  }, [ready, request, planId])

  const reload = useCallback((): void => {
    // A quiet read in flight gives way: the user asked, so the screen says it is reading.
    if (inFlight.current === 'loud') return
    inFlight.current = 'loud'
    setRequest((current) => ({ serial: current.serial + 1, quiet: false }))
  }, [])

  const refresh = useCallback((): void => {
    if (inFlight.current !== null) return
    inFlight.current = 'quiet'
    setRequest((current) => ({ serial: current.serial + 1, quiet: true }))
  }, [])

  return {
    historical: planId !== null,
    view,
    loading: ready && settled !== request.serial && !request.quiet,
    reload,
    refresh,
  }
}

/**
 * The bundle a view shows, if any: ready, the record of a plan ended early (Story 2.13 — the calculation evidence
 * and the adjustment history read it like any other), a plan completed at its scheduled end (Story 2.14), kept after
 * a failure, or kept unswapped after a conflict.
 */
export function bundleOf(view: DashboardView): Accepted | null {
  if (
    view.kind === 'READY' ||
    view.kind === 'ENDED' ||
    view.kind === 'COMPLETED' ||
    view.kind === 'lastGood'
  ) {
    return view
  }
  if (view.kind === 'retryable') return view.kept
  return null
}

/** The reliability's name when it is not `확인됨`, or `null`: status sentences name it first when it matters. */
function reliabilityName(result: PlanResult): string | null {
  const reliability = result.trust.inputReliability.state
  if (reliability === 'CONFIRMED') return null
  return copyFor(copy.axes.reliability.states, reliability, copy.axes.unknownState)
}

/** The sentence a job of this purpose says in this phase, when the phase is one of its fixed sentences. */
function fixedSentence(
  phase: Exclude<SyncPhase, 'idle' | 'succeeded' | 'refreshed'>,
  purpose: SyncPurpose,
): string {
  const sync = planSyncContent.status
  const verify = planVerificationContent.status
  const catchUp = planVerificationContent.catchUp.status
  switch (phase) {
    case 'running':
      return purpose === 'VERIFY'
        ? verify.running
        : purpose === 'CATCH_UP'
          ? catchUp.running
          : sync.syncing
    case 'longGap':
      // Whichever request met the long gap, the long gap is the plan's state and has one sentence.
      return sync.longGap
    case 'notLongGap':
      return catchUp.notLongGap
    case 'failed':
    case 'review':
    case 'blocked':
    case 'cooldown':
    case 'unavailable':
    case 'error':
    case 'stalled':
      return purpose === 'VERIFY'
        ? verify[phase]
        : purpose === 'CATCH_UP'
          ? catchUp[phase]
          : sync[phase]
    case 'notUseful':
    case 'stale':
    case 'busy':
    case 'refused':
      return purpose === 'CATCH_UP' ? catchUp[phase] : verify[phase]
  }
}

/**
 * The sentence the status region reads for a shown `READY` bundle while a job is in its phase (Story 2.11),
 * or `null` when no job has anything to say. The phase changes once when a job starts and once when it ends;
 * a poll in between changes nothing, so nothing is read again. A precision verification or a catch-up (Story
 * 2.12) says its own sentences; a verification that ended with a new bundle also says what the version changed.
 */
function syncSentence(phase: SyncPhase, purpose: SyncPurpose, bundle: Accepted): string | null {
  if (phase === 'idle') return null
  if (phase !== 'succeeded' && phase !== 'refreshed') return fixedSentence(phase, purpose)
  const { result, change } = bundle
  const basis = formatSeoulTime(result.basisAt, copy.timeUnavailable)
  const reliability = reliabilityName(result)
  // A refused request's own quiet read brought the current official result: said once, in its own words.
  if (phase === 'refreshed') return planVerificationContent.status.refreshed(reliability, basis)
  if (purpose === 'VERIFY') {
    const marker = planVerificationContent.change
    const changed =
      change.kind === 'CORRECTED'
        ? marker.correctedSentence(change)
        : change.kind === 'VERIFIED_NO_CHANGE'
          ? marker.verifiedSentence
          : null
    return planVerificationContent.status.succeeded(changed, reliability, basis)
  }
  if (purpose === 'CATCH_UP')
    return planVerificationContent.catchUp.status.succeeded(reliability, basis)
  const status = planSyncContent.status
  return reliability === null
    ? status.synced(basis)
    : status.syncedWithReliability(reliability, basis)
}

/**
 * The sentence the status region reads for a shown `COMPLETED` bundle (Story 2.14). Without a job to speak of, it
 * names the scheduled end and the comparison — once when the completion comes on screen, and again only when the
 * comparison changes. A job that runs or ends without a new bundle says the sync's own sentences. A job that ended
 * with one says what it showed: the completion result when it ran the completion or the comparison is still being
 * decided; once the comparison is final, the value after the end — or that there is none, or that it could not be read
 * — and that the completion result did not change.
 */
function completedSentence(
  phase: SyncPhase,
  purpose: SyncPurpose,
  view: Accepted & { readonly completion: PlanCompletionRecord },
): string {
  const { completion } = view
  const comparison = comparisonName(completion.comparison)
  const status = planCompletionContent.status
  if (phase === 'idle') {
    return status.shown(
      formatSeoulTime(completion.scheduledEndAt, copy.timeUnavailable),
      comparison,
    )
  }
  if (phase !== 'succeeded' && phase !== 'refreshed') return fixedSentence(phase, purpose)
  // The job that ended ran the completion, or the comparison is still being decided: the completion result is what
  // it brought.
  if (view.sync.latest?.purpose === 'COMPLETION' || completion.retryable) {
    return status.synced(comparison)
  }
  // Once the comparison is final a sync only measures the value after the end; the completion result is unchanged.
  if ((view.postEnd ?? null) !== null) return status.postEndSynced
  return view.postEndUnreadable === true ? status.postEndUnreadable : status.postEndNone
}

/**
 * The single sentence the screen's status region reads for the dashboard. It knows about the sync (Story
 * 2.11) and the precision verification and catch-up that share its job table (Story 2.12): while the shown
 * bundle is ready, the job's phase has the say — one sentence when it starts, one when it ends — in the words
 * of its purpose. A quiet read never makes the screen say it is reading again. While a job is followed, or
 * after the screen stopped following one, the job also has the say over a read that did not answer
 * (`lastGood`, `retryable`): a failed poll does not end the following, so it does not change the sentence
 * either, and the one sentence that ends it is `stalled` whatever the view.
 */
export function dashboardAnnouncement(
  dashboard: Dashboard,
  phase: SyncPhase = 'idle',
  purpose: SyncPurpose = 'SYNC',
): string {
  const { view } = dashboard
  if (dashboard.loading && view.kind !== 'checking') return copy.status.reloading
  switch (view.kind) {
    case 'checking':
      return copy.status.checking
    case 'NO_PLAN':
      return copy.status.noPlan
    case 'PREPARING':
      return copy.status.preparing
    case 'READY': {
      const sync = syncSentence(phase, purpose, view)
      if (sync !== null) return sync
      const basis = formatSeoulTime(view.result.basisAt, copy.timeUnavailable)
      const reliability = reliabilityName(view.result)
      if (reliability === null) return copy.status.ready(basis)
      return copy.status.readyWithReliability(reliability, basis)
    }
    case 'ENDED':
      // Said once, when the record comes on screen — the end flow itself says nothing more (Story 2.13).
      return planEndContent.status.ended(formatSeoulTime(view.end.endedAt, copy.timeUnavailable))
    case 'COMPLETED':
      return completedSentence(phase, purpose, view)
    case 'lastGood':
    case 'retryable':
      if (phase === 'running' || phase === 'stalled') return fixedSentence(phase, purpose)
      if (view.kind === 'lastGood') return copy.status.lastGood
      return view.kept === null ? copy.status.retryable : copy.status.versionConflict
    case 'denied':
      return view.cause === 'signedOut' ? copy.status.signedOut : copy.status.denied
    case 'displayRestricted':
      return copy.status.displayRestricted
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
 * reauthentication request is in flight, or the dashboard is reading — not quietly: a sync's polling never
 * marks the region busy, or its one sentence would wait behind it.
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
