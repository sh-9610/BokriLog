import { useEffect, useState } from 'react'

import { METRIC_ORDER, PLAN_RESULT_PATH, type MetricName } from '../content/planResult'
import { isCanonicalDecimal, isPlainNonNegative } from '../format/decimal'

export interface PlanResultMetric {
  readonly metric: MetricName
  readonly value?: string
  readonly nature: 'OFFICIAL' | 'ESTIMATED'
  readonly unavailableReason?: string
}

/** The active plan's official result: one response read through one pointer. */
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
    /** Judged from both times; each keeps its own name (there is no separate "last normal" time). */
    readonly freshness: {
      readonly state: string
      readonly measuredAt: string
      readonly cashflowVerifiedThrough: string
    }
    readonly inputReliability: { readonly state: string }
    readonly calculationNature: {
      readonly state: string
      readonly character: string
      readonly unavailableMetrics: readonly string[]
    }
  }
  readonly metrics: readonly PlanResultMetric[]
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

function strings(value: unknown, keys: readonly string[]): boolean {
  const row = record(value)
  return row !== null && keys.every((key) => typeof row[key] === 'string')
}

function isMetric(value: unknown, expected: MetricName | undefined): boolean {
  const row = record(value)
  if (row === null || row.metric !== expected) return false
  if (row.nature !== 'OFFICIAL' && row.nature !== 'ESTIMATED') return false
  // A value or a reason, never both and never neither; a value is a canonical decimal string.
  if (row.value === undefined) return typeof row.unavailableReason === 'string'
  return isCanonicalDecimal(row.value) && row.unavailableReason === undefined
}

/**
 * The whole result is accepted or rejected as one unit. A response with a missing axis, a metric out
 * of order or a malformed value is not rendered in part. The plan's own terms (`P0`, the rate) are
 * plain decimals: a notation difference such as trailing zeros is not a reason to drop the result.
 */
export function isPlanResult(value: unknown): value is PlanResult {
  const row = record(value)
  if (row === null) return false
  const trust = record(row.trust)
  const conditions = record(row.conditions)
  const metrics = row.metrics
  return (
    strings(row, [
      'planId',
      'planStatus',
      'publishedAt',
      'calculationId',
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
    strings(trust.freshness, ['state', 'measuredAt', 'cashflowVerifiedThrough']) &&
    strings(trust.inputReliability, ['state']) &&
    strings(trust.calculationNature, ['state', 'character']) &&
    Array.isArray(record(trust.calculationNature)?.unavailableMetrics) &&
    Array.isArray(metrics) &&
    metrics.length === METRIC_ORDER.length &&
    metrics.every((metric, index) => isMetric(metric, METRIC_ORDER[index]))
  )
}

/**
 * What the screen knows about the active plan's result. The four are kept apart:
 * `checking` (not answered yet), `none` (the server confirmed there is no active plan, 204),
 * `unavailable` (the read was refused, failed or malformed — nothing is known about a plan), and
 * `loaded`. Only a confirmed `none` may be shown as "no active plan".
 */
export type PlanResultView =
  | { readonly kind: 'checking' | 'none' | 'unavailable' }
  | { readonly kind: 'loaded'; readonly result: PlanResult }

/** The first-snapshot panel's view of the same answer, without the result itself. */
export type PlanPresence = 'checking' | 'none' | 'unavailable' | 'active'

export function planPresenceOf(view: PlanResultView): PlanPresence {
  return view.kind === 'loaded' ? 'active' : view.kind
}

/**
 * Reads the active plan's official result once the session is ready. Financial values live only in
 * React memory. A refusal (403) is not "no plan": it is `unavailable`, like a failed read.
 */
export function usePlanResult(ready: boolean): PlanResultView {
  const [view, setView] = useState<PlanResultView>({ kind: 'checking' })
  useEffect(() => {
    if (!ready) return
    // An object, not a `let`: the cleanup flips it after the reads below were started.
    const mounted = { current: true }
    const read = async (): Promise<void> => {
      try {
        const response = await fetch(PLAN_RESULT_PATH, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
        })
        const payload: unknown = response.status === 200 ? await response.json() : null
        if (!mounted.current) return
        if (response.status === 204) {
          setView({ kind: 'none' })
          return
        }
        setView(
          isPlanResult(payload) ? { kind: 'loaded', result: payload } : { kind: 'unavailable' },
        )
      } catch {
        if (mounted.current) setView({ kind: 'unavailable' })
      }
    }
    void read()
    return () => {
      mounted.current = false
    }
  }, [ready])
  return view
}
