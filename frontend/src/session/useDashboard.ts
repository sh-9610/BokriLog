import { useCallback, useEffect, useRef, useState } from 'react'

import type { SyncPhase } from './useDashboardSync'

import {
  planResultContent as copy,
  copyFor,
  DASHBOARD_PATH,
  METRIC_ORDER,
  type MetricName,
} from '../content/planResult'
import { planSyncContent } from '../content/planSync'
import { planVerificationContent } from '../content/planVerification'
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

/** An instant as the server writes it (`Instant#toString`): UTC, `Z`, whole seconds and an optional fraction. */
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/

/** Whether the value is an instant in the server's format. The calculation evidence shares it. */
export function isInstant(value: unknown): value is string {
  return typeof value === 'string' && INSTANT.test(value) && Number.isFinite(Date.parse(value))
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
 * What the current official version changed (Story 2.12): `CORRECTED` when it applied late or changed
 * cash flows, `VERIFIED_NO_CHANGE` when a verification found none, `NONE` otherwise.
 */
export type VersionChangeKind = 'CORRECTED' | 'VERIFIED_NO_CHANGE' | 'NONE'

/** The `change` block of a `READY` answer: the result marker of the shown version. */
export interface VersionChange {
  readonly kind: VersionChangeKind
  readonly purpose: string | null
  readonly reason: string | null
  readonly lateChangeCount: number
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
  lateChangeCount: 0,
  firstAffectedAt: null,
  verifiedAt: null,
  targets: [],
  reused: [],
}

/**
 * The `change` block, or `NO_CHANGE` when it is missing, not whole, of a kind this screen does not know, or
 * contradicts itself — a marker it cannot vouch for is not shown, and never a reason to drop the bundle. A
 * correction names how many late changes it applied; one with none (only values changed) says it changed,
 * without a count. A version without a late change (`VERIFIED_NO_CHANGE`, `NONE`) has neither a late change
 * count nor an impact start, and an impact starts no later than the shown basis time (`basisAt`, when given).
 */
export function parseVersionChange(value: unknown, basisAt: string | null = null): VersionChange {
  const row = record(value)
  if (row === null) return NO_CHANGE
  const { kind, purpose, reason, lateChangeCount, firstAffectedAt, verifiedAt } = row
  if (kind !== 'CORRECTED' && kind !== 'VERIFIED_NO_CHANGE' && kind !== 'NONE') return NO_CHANGE
  if (!absent(purpose) && typeof purpose !== 'string') return NO_CHANGE
  if (!absent(reason) && typeof reason !== 'string') return NO_CHANGE
  if (!count(lateChangeCount)) return NO_CHANGE
  if (!absent(firstAffectedAt) && !isInstant(firstAffectedAt)) return NO_CHANGE
  if (!absent(verifiedAt) && !isInstant(verifiedAt)) return NO_CHANGE
  if (kind !== 'CORRECTED' && (lateChangeCount > 0 || !absent(firstAffectedAt))) return NO_CHANGE
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
    lateChangeCount,
    firstAffectedAt: absent(firstAffectedAt) ? null : firstAffectedAt,
    verifiedAt: absent(verifiedAt) ? null : verifiedAt,
    targets,
    reused,
  }
}

/**
 * One validated dashboard response. Only `READY` carries financial fields, and its sync block, its
 * verification block and its version change (Story 2.12).
 */
export type DashboardResponse =
  | { readonly state: 'NO_PLAN' | 'PREPARING'; readonly nextAction: string }
  | {
      readonly state: 'READY'
      readonly nextAction: string
      readonly result: PlanResult
      readonly sync: SyncStatus
      readonly verification: VerificationStatus
      readonly change: VersionChange
    }

/**
 * The screen-level next actions each state may carry: Story 2.8's closed set, the two Story 2.11 added to
 * `READY` (`SYNC` when the result is not fresh, `CHOOSE_CONTINUATION` after a long gap) and the one Story 2.12
 * added (`VERIFY` after a sync that ended in review). A pair outside these is not an answer this screen
 * understands.
 */
const NEXT_ACTIONS: Readonly<Record<DashboardResponse['state'], readonly string[]>> = {
  NO_PLAN: ['CREATE_PLAN'],
  PREPARING: ['WAIT_FOR_RESULT'],
  READY: ['NONE', 'WAIT_FOR_SYNC', 'SYNC', 'VERIFY', 'CHOOSE_CONTINUATION'],
}

/**
 * A `GET /api/dashboard` body, or `null` when it is not one whole answer. A state outside the three, a
 * next action that state cannot carry, a financial field on a state that has none, or a malformed bundle
 * all reject it. The sync block, the verification block and the version change never do: on `READY` each is
 * read as far as it can be (`parseSyncStatus`, `parseVerificationStatus`, `parseVersionChange`), and on the
 * states without a bundle they have no meaning and are ignored.
 */
export function parseDashboard(value: unknown): DashboardResponse | null {
  const row = record(value)
  if (row === null || typeof row.nextAction !== 'string') return null
  if (row.state === 'NO_PLAN' || row.state === 'PREPARING') {
    return row.result === undefined && NEXT_ACTIONS[row.state].includes(row.nextAction)
      ? { state: row.state, nextAction: row.nextAction }
      : null
  }
  if (row.state !== 'READY' || !NEXT_ACTIONS.READY.includes(row.nextAction)) return null
  if (!isPlanResult(row.result)) return null
  return {
    state: 'READY',
    nextAction: row.nextAction,
    result: row.result,
    sync: parseSyncStatus(row.sync),
    verification: parseVerificationStatus(row.verification),
    change: parseVersionChange(row.change, row.result.basisAt),
  }
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

/** The bundle last accepted, with the next action and the sync and verification blocks it came with. */
export interface Accepted {
  readonly result: PlanResult
  readonly nextAction: string
  readonly sync: SyncStatus
  readonly verification: VerificationStatus
  readonly change: VersionChange
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
  | ({ readonly kind: 'READY' } & Accepted)
  | ({ readonly kind: 'lastGood' } & Accepted)
  | { readonly kind: 'retryable'; readonly kept: Accepted | null }
  | { readonly kind: 'denied'; readonly cause: 'refused' | 'signedOut' }

export interface Dashboard {
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
export function useDashboard(ready: boolean): Dashboard {
  const [view, setView] = useState<DashboardView>({ kind: 'checking' })
  // One number per read the screen asks for: a reload, a quiet refresh, or the session becoming ready.
  const [request, setRequest] = useState<Request>({ serial: 0, quiet: false })
  const [settled, setSettled] = useState(-1)
  const [wasReady, setWasReady] = useState(ready)
  const sequence = useRef(0)
  // The kind of the read in flight, if any.
  const inFlight = useRef<'loud' | 'quiet' | null>(null)
  const accepted = useRef<Accepted | null>(null)

  // Adjusting state to a changed prop during render (React's documented pattern, not an effect): the
  // bundle leaves the view the moment the session stops being ready, and a new read starts as loading.
  if (ready !== wasReady) {
    setWasReady(ready)
    if (ready) setRequest((current) => ({ serial: current.serial + 1, quiet: false }))
    else setView({ kind: 'checking' })
  }

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
      const response = await fetch(DASHBOARD_PATH, {
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      })
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
        const bundle: Accepted = {
          result: parsed.result,
          nextAction: parsed.nextAction,
          sync: parsed.sync,
          verification: parsed.verification,
          change: parsed.change,
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
  }, [ready, request])

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
    view,
    loading: ready && settled !== request.serial && !request.quiet,
    reload,
    refresh,
  }
}

/** The bundle a view shows, if any: ready, kept after a failure, or kept unswapped after a conflict. */
export function bundleOf(view: DashboardView): Accepted | null {
  if (view.kind === 'READY' || view.kind === 'lastGood') return view
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
        ? marker.correctedSentence(change.lateChangeCount)
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
    case 'lastGood':
    case 'retryable':
      if (phase === 'running' || phase === 'stalled') return fixedSentence(phase, purpose)
      if (view.kind === 'lastGood') return copy.status.lastGood
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
