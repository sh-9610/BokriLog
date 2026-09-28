import type { ReactElement } from 'react'

import type { PlanResult, PlanResultMetric } from '../session/usePlanResult'

import { planResultContent as copy, type MetricName } from '../content/planResult'
import { formatAmount, formatPercent, formatSeoulTime, percentFromRatio } from '../format/decimal'
import styles from './PlanResultPanel.module.css'

const RATIOS: readonly MetricName[] = ['REQUIRED_RATE', 'GOAL_PROGRESS']

function time(value: string): ReactElement {
  const valid = Number.isFinite(new Date(value).getTime())
  return (
    <time dateTime={valid ? value : undefined}>{formatSeoulTime(value, copy.timeUnavailable)}</time>
  )
}

function displayValue(metric: PlanResultMetric): string {
  if (metric.value === undefined) return copy.notComputable
  return RATIOS.includes(metric.metric)
    ? formatPercent(metric.value)
    : `${formatAmount(metric.value)} USD`
}

/** The copy for a known code; an unknown code folds to `fallback` and never shows verbatim. */
function lookup(table: Readonly<Record<string, string>>, key: string, fallback: string): string {
  return Object.hasOwn(table, key) ? (table[key] ?? fallback) : fallback
}

function MetricCard({
  metric,
  result,
}: {
  readonly metric: PlanResultMetric
  readonly result: PlanResult
}): ReactElement {
  const id = `plan-metric-${metric.metric.toLowerCase().replaceAll('_', '-')}`
  const unit = lookup(copy.periodUnit, result.conditions.period, copy.unknownPeriodUnit)
  const basis = formatSeoulTime(result.basisAt, copy.timeUnavailable)
  return (
    <li className={styles.card}>
      <article aria-labelledby={id}>
        <h4 className={styles.metricName} id={id}>
          {copy.metrics[metric.metric].name}
        </h4>
        <p className={styles.value}>{displayValue(metric)}</p>
        <p className={styles.meta}>
          {metric.metric === 'CURRENT_VALUE'
            ? `${copy.basisLine(unit, basis)} · ${copy.measuredLine(formatSeoulTime(result.measuredAt, copy.timeUnavailable))}`
            : copy.basisLine(unit, basis)}
        </p>
        {/*
          The character (official or estimated) is always shown. "Not computable" is a separate fact
          with its own reason and never replaces it: an undefined ratio of an estimated plan is still
          estimated.
        */}
        <p className={styles.nature}>
          {copy.natureLabel}: {lookup(copy.natures, metric.nature, copy.axes.unknownState)}
        </p>
        {metric.unavailableReason !== undefined ? (
          <p>
            {copy.notComputable}:{' '}
            {lookup(copy.reasons, metric.unavailableReason, copy.unknownReason)}
          </p>
        ) : null}
        {metric.nature === 'ESTIMATED' ? (
          <p>{copy.estimatedEvidence(result.estimatedFlowCount)}</p>
        ) : null}
        <p className={styles.help}>{copy.metrics[metric.metric].help}</p>
        {metric.metric === 'REQUIRED_RATE' ? (
          <p className={styles.help}>{copy.requiredRateBoundary(unit)}</p>
        ) : null}
      </article>
    </li>
  )
}

/** One named time an axis is judged from. Each time keeps its own name. */
interface AxisTime {
  readonly label: string
  readonly value: string
}

function Axis({
  name,
  state,
  cause,
  extra,
  times,
  impact,
  action,
}: {
  readonly name: string
  readonly state: string
  readonly cause: string
  readonly extra?: readonly string[]
  readonly times: readonly AxisTime[]
  readonly impact: string
  readonly action: string
}): ReactElement {
  return (
    <li className={styles.axis}>
      <p className={styles.axisName}>
        {name}: {state}
      </p>
      <dl className={styles.facts}>
        <div>
          <dt>{copy.axes.cause}</dt>
          <dd>
            {cause}
            {extra?.map((line) => (
              <span className={styles.line} key={line}>
                {line}
              </span>
            ))}
          </dd>
        </div>
        {times.map((entry) => (
          <div key={entry.label}>
            <dt>{entry.label}</dt>
            <dd>{time(entry.value)}</dd>
          </div>
        ))}
        <div>
          <dt>{copy.axes.impact}</dt>
          <dd>{impact}</dd>
        </div>
        <div>
          <dt>{copy.axes.action}</dt>
          <dd>{action}</dd>
        </div>
      </dl>
    </li>
  )
}

/**
 * The first official result on the dashboard (Story 2.7).
 *
 * Everything on it comes from one response read through one pointer, held in one piece of state, so a
 * newer version replaces the whole panel at once — cards are never assembled from separate requests.
 * Values are rounded for display here and only here. Undefined metrics show `계산 불가` with their
 * reason instead of a zero, beside their unchanged character; negative rates and progress outside
 * 0–100% are shown as they are, with no colour or icon judging them. The screen's single status region
 * announces the result, so this panel adds no live region of its own.
 *
 * Every axis names the times it is judged from — the measurement, the cash-flow verification, the
 * calculation basis — instead of repeating one "last normal" time under three labels.
 */
export function PlanResultPanel({ result }: { readonly result: PlanResult }): ReactElement {
  const { freshness, inputReliability, calculationNature } = result.trust
  const periodName = lookup(copy.periods, result.conditions.period, copy.unknownPeriod)
  const unavailable = calculationNature.unavailableMetrics
    .filter((name): name is MetricName => Object.hasOwn(copy.metrics, name))
    .map((name) => copy.metrics[name].name)
  const natureExtra = [
    ...(unavailable.length > 0
      ? [copy.axes.nature.unavailableMetrics(unavailable.join(', '))]
      : []),
    ...(calculationNature.state === 'NOT_COMPUTABLE' && calculationNature.character === 'ESTIMATED'
      ? [copy.axes.nature.estimatedToo]
      : []),
  ]
  // Which metrics the character reaches: all six when official, five when estimated (the current
  // value is always official), and the undefined ones by name when some are not computable.
  const natureImpact =
    unavailable.length > 0
      ? unavailable.join(', ')
      : lookup(copy.axes.nature.impacts, calculationNature.character, copy.axes.unknownImpact)
  const measured = { label: copy.measuredAt, value: freshness.measuredAt }
  const verified = { label: copy.verifiedThrough, value: freshness.cashflowVerifiedThrough }
  const basis = { label: copy.basisAt, value: result.basisAt }
  return (
    <section aria-labelledby="plan-result-heading" className={styles.panel}>
      <h2 id="plan-result-heading">{copy.heading}</h2>
      <p>{copy.intro}</p>

      <section aria-labelledby="plan-result-conditions" className={styles.group}>
        <h3 id="plan-result-conditions">{copy.conditionsHeading}</h3>
        <dl className={styles.facts}>
          <div>
            <dt>{copy.principal}</dt>
            <dd className={styles.number}>{formatAmount(result.conditions.principal)} USD</dd>
          </div>
          <div>
            <dt>{copy.planPeriod}</dt>
            <dd>
              {time(result.conditions.startAt)} – {time(result.conditions.endAt)} · {copy.timeZone}
            </dd>
          </div>
          <div>
            <dt>{copy.period}</dt>
            <dd>{periodName}</dd>
          </div>
          <div>
            <dt>{copy.targetRate}</dt>
            <dd className={styles.number}>{percentFromRatio(result.conditions.targetRate)}%</dd>
          </div>
        </dl>
      </section>

      <section aria-labelledby="plan-result-times" className={styles.group}>
        <h3 id="plan-result-times">{copy.timesHeading}</h3>
        <dl className={styles.facts}>
          <div>
            <dt>{copy.basisAt}</dt>
            <dd>
              {time(result.basisAt)} · {copy.timeZone}
            </dd>
          </div>
          <div>
            <dt>{copy.measuredAt}</dt>
            <dd>
              {time(result.measuredAt)} · {copy.timeZone}
            </dd>
          </div>
          <div>
            <dt>{copy.verifiedThrough}</dt>
            <dd>
              {time(result.cashflowVerifiedThrough)} · {copy.timeZone}
            </dd>
          </div>
        </dl>
      </section>

      <section aria-labelledby="plan-result-trust" className={styles.group}>
        <h3 id="plan-result-trust">{copy.trustHeading}</h3>
        <ul className={styles.axes}>
          <Axis
            action={lookup(copy.axes.freshness.actions, freshness.state, copy.axes.unknownAction)}
            cause={lookup(copy.axes.freshness.causes, freshness.state, copy.axes.unknownCause)}
            impact={copy.axes.freshness.impact}
            name={copy.axes.freshness.name}
            state={lookup(copy.axes.freshness.states, freshness.state, copy.axes.unknownState)}
            times={[measured, verified]}
          />
          <Axis
            action={lookup(
              copy.axes.reliability.actions,
              inputReliability.state,
              copy.axes.unknownAction,
            )}
            cause={lookup(
              copy.axes.reliability.causes,
              inputReliability.state,
              copy.axes.unknownCause,
            )}
            impact={copy.axes.reliability.impact}
            name={copy.axes.reliability.name}
            state={lookup(
              copy.axes.reliability.states,
              inputReliability.state,
              copy.axes.unknownState,
            )}
            times={[verified]}
          />
          <Axis
            action={lookup(
              copy.axes.nature.actions,
              calculationNature.state,
              copy.axes.unknownAction,
            )}
            cause={lookup(copy.axes.nature.causes, calculationNature.state, copy.axes.unknownCause)}
            extra={natureExtra}
            impact={natureImpact}
            name={copy.axes.nature.name}
            state={lookup(copy.axes.nature.states, calculationNature.state, copy.axes.unknownState)}
            times={[basis]}
          />
        </ul>
      </section>

      <section aria-labelledby="plan-result-metrics" className={styles.group}>
        <h3 id="plan-result-metrics">{copy.metricsHeading}</h3>
        <ul className={styles.metrics}>
          {result.metrics.map((metric) => (
            <MetricCard key={metric.metric} metric={metric} result={result} />
          ))}
        </ul>
      </section>

      <p className={styles.meta}>
        {copy.policies}: {result.policies.calculation} · {result.policies.calendar} ·{' '}
        {result.policies.display} · {result.policies.price}
      </p>
    </section>
  )
}
