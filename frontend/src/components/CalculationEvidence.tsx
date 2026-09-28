import { useEffect, useRef, type ReactElement, type ReactNode } from 'react'

import type {
  CalculationEvidence as Evidence,
  EvidenceState,
  EvidenceView,
} from '../session/useCalculationEvidence'
import type { PlanResult } from '../session/useDashboard'

import { ENTRY_PATH } from '../content/auth'
import { calculationEvidenceContent as copy, lookup } from '../content/calculationEvidence'
import {
  copyFor,
  planPublicationContent,
  planResultContent,
  type MetricName,
} from '../content/planResult'
import {
  formatAmount,
  formatSeoulInstant,
  formatSeoulTime,
  percentFromRatio,
} from '../format/decimal'
import styles from './CalculationEvidence.module.css'

const REGION_ID = 'calculation-evidence'
const HEADING_ID = 'calculation-evidence-heading'

function validTime(value: string): boolean {
  return Number.isFinite(new Date(value).getTime())
}

/** An instant in Asia/Seoul with its machine value, or the unavailable sentence (never spliced into another). */
function Instant({
  value,
  precise = false,
}: {
  readonly value: string
  readonly precise?: boolean
}): ReactElement {
  const format = precise ? formatSeoulInstant : formatSeoulTime
  return (
    <time dateTime={validTime(value) ? value : undefined}>
      {format(value, planResultContent.timeUnavailable)}
    </time>
  )
}

/**
 * A time in Asia/Seoul with its zone. `precise` shows seconds and milliseconds: the times section says
 * whether two times are the same, and two times in the same minute must not look alike there.
 */
function Time({
  value,
  precise = false,
}: {
  readonly value: string
  readonly precise?: boolean
}): ReactElement {
  return (
    <>
      <Instant precise={precise} value={value} /> {copy.separators.item}{' '}
      {planResultContent.timeZone}
    </>
  )
}

/** A range of two times in Asia/Seoul, with its zone once. */
function TimeRange({ start, end }: { readonly start: string; readonly end: string }): ReactElement {
  return (
    <>
      <Instant value={start} /> {copy.separators.range} <Instant value={end} />{' '}
      {copy.separators.item} {planResultContent.timeZone}
    </>
  )
}

/**
 * A long machine identifier, whole: never shortened, wrapped safely even without spaces, and read by
 * assistive technology exactly as it is shown (DESIGN.md: mono, full value from the start).
 */
function Id({ value }: { readonly value: string | number }): ReactElement {
  return <code className={styles.id}>{String(value)}</code>
}

interface Fact {
  readonly label: string
  readonly value: ReactNode
  readonly help?: string
}

function Facts({ facts }: { readonly facts: readonly Fact[] }): ReactElement {
  return (
    <dl className={styles.facts}>
      {facts.map((fact) => (
        <div key={fact.label}>
          <dt>{fact.label}</dt>
          <dd>
            {fact.value}
            {fact.help === undefined ? null : <span className={styles.help}>{fact.help}</span>}
          </dd>
        </div>
      ))}
    </dl>
  )
}

/** One of the six parts, a named group in the reading order. */
function Part({
  id,
  title,
  children,
}: {
  readonly id: string
  readonly title: string
  readonly children: ReactNode
}): ReactElement {
  const headingId = `calculation-evidence-${id}`
  return (
    <div aria-labelledby={headingId} className={styles.part} role="group">
      <h5 className={styles.partTitle} id={headingId}>
        {title}
      </h5>
      {children}
    </div>
  )
}

function Snapshots({ evidence }: { readonly evidence: Evidence }): ReactElement {
  const { snapshots } = evidence
  return (
    <Part id="snapshots" title={copy.sections.snapshots}>
      <Facts
        facts={[
          { label: copy.snapshots.portfolio, value: <Id value={snapshots.portfolioSnapshotId} /> },
          {
            label: copy.snapshots.portfolioSequence,
            value: <Id value={snapshots.portfolioSequence} />,
          },
          { label: copy.snapshots.basis, value: <Id value={snapshots.basisPortfolioSnapshotId} /> },
          {
            label: copy.snapshots.basisSource,
            value: <Id value={snapshots.basisSourceSnapshotId} />,
            help: copy.snapshots.basisSourceHelp,
          },
          {
            label: copy.snapshots.source,
            value: <Id value={snapshots.sourceSnapshotId} />,
            help: copy.snapshots.sourceHelp,
          },
        ]}
      />
    </Part>
  )
}

/**
 * The four times under their own names, each with what it means, then the fact that Bybit has no common
 * snapshot token, and the sentence that fits whether the measurement and the cash-flow verification are
 * the same instant or not. A time that cannot be read gets no comparison sentence at all. Only here are
 * times shown to the millisecond.
 */
function Times({ evidence }: { readonly evidence: Evidence }): ReactElement {
  const { times } = evidence
  const measured = new Date(times.measuredAt).getTime()
  const verified = new Date(times.cashflowVerifiedThrough).getTime()
  const comparable = Number.isFinite(measured) && Number.isFinite(verified)
  return (
    <Part id="times" title={copy.sections.times}>
      <Facts
        facts={[
          {
            label: copy.times.t0,
            value: <Time precise value={times.t0} />,
            help: copy.times.t0Help,
          },
          {
            label: copy.times.tc,
            value: <Time precise value={times.tc} />,
            help: copy.times.tcHelp,
          },
          {
            label: copy.times.measuredAt,
            value: <Time precise value={times.measuredAt} />,
            help: copy.times.measuredAtHelp,
          },
          {
            label: copy.times.verifiedThrough,
            value: <Time precise value={times.cashflowVerifiedThrough} />,
            help: copy.times.verifiedThroughHelp,
          },
        ]}
      />
      <p>{copy.times.noCommonSnapshot}</p>
      {comparable ? <p>{measured === verified ? copy.times.same : copy.times.different}</p> : null}
    </Part>
  )
}

function Inputs({ evidence }: { readonly evidence: Evidence }): ReactElement {
  const { connection, sourceRange, classification, conversion, cashflows, plan } = evidence.inputs
  const conditions = plan.conditions
  return (
    <Part id="inputs" title={copy.sections.inputs}>
      <p className={styles.term}>{copy.inputs.connection}</p>
      <Facts
        facts={[
          {
            label: copy.inputs.provider,
            value: lookup(copy.providers, connection.provider, copy.unknownProvider),
          },
          { label: copy.inputs.connectionId, value: <Id value={connection.connectionId} /> },
          {
            label: copy.inputs.connectionPolicy,
            value: <Id value={connection.policyVersion} />,
            help: copy.inputs.connectionPolicyHelp,
          },
        ]}
      />
      <p className={styles.term}>{copy.inputs.sourceRange}</p>
      <Facts
        facts={[
          {
            label: copy.inputs.range,
            value: <TimeRange end={sourceRange.rangeEnd} start={sourceRange.rangeStart} />,
          },
          {
            label: copy.inputs.collectionPolicy,
            value: <Id value={sourceRange.collectionPolicy} />,
          },
          {
            label: copy.inputs.completenessHash,
            value: <Id value={sourceRange.completenessHash} />,
          },
        ]}
      />
      <p className={styles.term}>{copy.inputs.classification}</p>
      <Facts
        facts={[
          {
            label: copy.inputs.classificationId,
            value: <Id value={classification.classificationId} />,
          },
          {
            label: copy.inputs.classificationPolicy,
            value: <Id value={classification.policyVersion} />,
          },
          {
            label: copy.inputs.inputScopeHash,
            value: <Id value={classification.inputScopeHash} />,
          },
        ]}
      />
      <p className={styles.term}>{copy.inputs.conversion}</p>
      <Facts
        facts={[
          { label: copy.inputs.conversionId, value: <Id value={conversion.conversionId} /> },
          { label: copy.inputs.pricePolicy, value: <Id value={conversion.pricePolicy} /> },
          {
            label: copy.inputs.resultFingerprint,
            value: <Id value={conversion.resultFingerprint} />,
          },
        ]}
      />
      <p className={styles.term}>{copy.inputs.cashflows}</p>
      <p>{copy.inputs.cashflowCounts(cashflows.appliedCount, cashflows.estimatedCount)}</p>
      <p className={styles.term}>{copy.inputs.plan}</p>
      <Facts
        facts={[
          { label: copy.inputs.planId, value: <Id value={plan.planId} /> },
          {
            label: planResultContent.principal,
            value: (
              <span className={styles.number}>
                {formatAmount(conditions.principal)} {copy.units.usd}
              </span>
            ),
          },
          {
            label: planResultContent.planPeriod,
            value: <TimeRange end={conditions.endAt} start={conditions.startAt} />,
          },
          {
            label: planResultContent.period,
            value: copyFor(
              planResultContent.periods,
              conditions.period,
              planResultContent.unknownPeriod,
            ),
          },
          {
            label: planResultContent.targetRate,
            value: (
              <span className={styles.number}>
                {percentFromRatio(conditions.targetRate)}
                {copy.units.percent}
              </span>
            ),
          },
        ]}
      />
    </Part>
  )
}

function Calculation({ evidence }: { readonly evidence: Evidence }): ReactElement {
  const c = evidence.calculation
  return (
    <Part id="calculation" title={copy.sections.calculation}>
      <Facts
        facts={[
          { label: copy.calculation.calculationId, value: <Id value={c.calculationId} /> },
          { label: copy.calculation.calculationPolicy, value: <Id value={c.calculationPolicy} /> },
          { label: copy.calculation.calendarPolicy, value: <Id value={c.calendarPolicy} /> },
          { label: copy.calculation.displayPolicy, value: <Id value={c.displayPolicy} /> },
          { label: copy.calculation.precisionPolicy, value: <Id value={c.precisionPolicy} /> },
          { label: copy.calculation.timeZone, value: <Id value={c.timeZone} /> },
          {
            label: copy.calculation.implementationVersion,
            value: <Id value={c.implementationVersion} />,
          },
          { label: copy.calculation.inputHash, value: <Id value={c.inputHash} /> },
          { label: copy.calculation.resultHash, value: <Id value={c.resultHash} /> },
          { label: copy.calculation.createdAt, value: <Time value={c.createdAt} /> },
        ]}
      />
    </Part>
  )
}

function metricName(metric: MetricName): string {
  return planResultContent.metrics[metric].name
}

/** Why a metric has the character it has, or `null` for an undefined metric — its reason explains it. */
function causeOf(metric: Evidence['metrics'][number]): string | null {
  if (metric.metric === 'CURRENT_VALUE') return copy.metrics.currentValueCause
  if (metric.unavailableReason !== undefined) return null
  const cause = metric.estimatedCause
  if (cause === undefined) return copy.metrics.noEstimatedCause
  return copy.metrics.estimatedCause(
    lookup(copy.evidenceTypes, cause.evidenceType, copy.unknownEvidenceType),
    cause.flowCount,
  )
}

/**
 * Each metric's character and why, in the dashboard's order. The current value is Bybit's measurement and
 * nothing propagates to it; a defined derived metric names its estimated cause or says there is none; an
 * undefined metric names its reason and no cause — no flow reached a value it does not have. No value, no
 * interpretation.
 */
function Metrics({ evidence }: { readonly evidence: Evidence }): ReactElement {
  return (
    <Part id="metrics" title={copy.sections.metrics}>
      <ul className={styles.list}>
        {evidence.metrics.map((metric) => {
          const cause = causeOf(metric)
          return (
            <li className={styles.item} key={metric.metric}>
              <p className={styles.term}>{metricName(metric.metric)}</p>
              <Facts
                facts={[
                  {
                    label: copy.metrics.nature,
                    value: lookup(copy.natures, metric.nature, copy.unknownNature),
                  },
                  ...(metric.unavailableReason === undefined
                    ? []
                    : [
                        {
                          label: copy.metrics.reason,
                          value: copyFor(
                            planResultContent.reasons,
                            metric.unavailableReason,
                            planResultContent.unknownReason,
                          ),
                        },
                      ]),
                  ...(cause === null ? [] : [{ label: copy.metrics.cause, value: cause }]),
                ]}
              />
            </li>
          )
        })}
      </ul>
    </Part>
  )
}

/** The attempt's closed reason as the publication section words it; an unknown code folds to safe copy. */
function attemptReason(code: string): string {
  const reasons = planPublicationContent.reasons
  return Object.hasOwn(reasons, code)
    ? (reasons[code]?.cause ?? planPublicationContent.unknownReason.cause)
    : planPublicationContent.unknownReason.cause
}

/**
 * The official versions, each with its publication order and the time it became official, the current one
 * named so; then — apart, and read-only — the attempts that never published. Nothing here links anywhere:
 * no earlier version or attempt has a screen of its own.
 */
function Versions({ evidence }: { readonly evidence: Evidence }): ReactElement {
  const { official, unpublished } = evidence.versions
  return (
    <Part id="versions" title={copy.sections.versions}>
      <p className={styles.term}>{copy.versions.official}</p>
      <ul className={styles.list}>
        {official.map((version) => (
          <li
            className={styles.item}
            key={`${String(version.pointerVersion)}-${version.calculationId}`}
          >
            <Facts
              facts={[
                { label: copy.versions.order, value: <Id value={version.pointerVersion} /> },
                { label: copy.versions.effectiveFrom, value: <Time value={version.publishedAt} /> },
                {
                  label: copy.versions.effectiveUntil,
                  value: version.current ? copy.versions.current : copy.versions.earlier,
                },
                { label: copy.versions.calculationId, value: <Id value={version.calculationId} /> },
              ]}
            />
          </li>
        ))}
      </ul>
      <p className={styles.term}>{copy.versions.unpublished}</p>
      <p className={styles.help}>{copy.versions.unpublishedHelp}</p>
      {unpublished.length === 0 ? (
        <p>{copy.versions.none}</p>
      ) : (
        <ul className={styles.list}>
          {unpublished.map((attempt, index) => (
            <li className={styles.item} key={`${attempt.finishedAt}-${String(index)}`}>
              <Facts
                facts={[
                  {
                    label: copy.versions.status,
                    value: lookup(copy.attemptStatuses, attempt.status, copy.unknownAttemptStatus),
                  },
                  { label: copy.versions.reason, value: attemptReason(attempt.reasonCode) },
                  {
                    label: copy.versions.finishedAt,
                    value: <Time value={attempt.finishedAt} />,
                  },
                ]}
              />
            </li>
          ))}
        </ul>
      )}
    </Part>
  )
}

/**
 * A read that ended without the lineage. The dashboard is untouched; the region names the shown bundle by
 * the minimal references already in memory and says whether asking again can help.
 */
function Failure({
  view,
  result,
}: {
  readonly view: Extract<EvidenceView, { kind: 'failed' }>
  readonly result: PlanResult
}): ReactElement {
  return (
    <div className={styles.notice}>
      <p>{view.retryable ? copy.failure.retryable : copy.failure.stale}</p>
      <p className={styles.term}>{copy.failure.minimalReference}</p>
      <Facts
        facts={[
          { label: copy.failure.snapshotId, value: <Id value={result.portfolioSnapshotId} /> },
          { label: copy.failure.calculationId, value: <Id value={result.calculationId} /> },
        ]}
      />
      <p>{view.retryable ? copy.failure.retryableHelp : copy.failure.staleHelp}</p>
    </div>
  )
}

/**
 * The calculation evidence of the shown result (Story 2.9): a `계산 근거 보기` button and the region it
 * opens on this same screen — a state change, not a navigation, so a button and not a link.
 *
 * Opening moves focus to the region's heading; `계산 근거 닫기` returns it to the button. When the region
 * closes for another reason (the shown bundle was replaced) while focus was inside it, focus also returns
 * to the button. The retry control keeps its place (marked `aria-disabled`) while it reads again, so a
 * keyboard user's focus stays on it; when what held focus inside the region goes away while it stays open
 * (the retry control once the lineage arrives), focus moves to the region's heading. The region has no
 * live region: the screen's single status region says what happened.
 */
export function CalculationEvidence({
  evidence,
  result,
}: {
  readonly evidence: EvidenceState
  readonly result: PlanResult
}): ReactElement {
  const { view, open, close, retry } = evidence
  const expanded = view.kind !== 'closed'
  const retrying = view.kind === 'loading' && view.retrying
  const retryable = view.kind === 'failed' && view.retryable
  const toggle = useRef<HTMLButtonElement>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  // The element inside the region that last had focus, until focus leaves the region for another element.
  const lastFocused = useRef<Element | null>(null)
  const wasExpanded = useRef(expanded)

  useEffect(() => {
    const opened = expanded && !wasExpanded.current
    wasExpanded.current = expanded
    if (opened) {
      heading.current?.focus()
      return
    }
    const last = lastFocused.current
    const active = document.activeElement
    const lost = active === null || active === document.body || !active.isConnected
    if (last === null || last.isConnected || !lost) return
    lastFocused.current = null
    if (expanded) heading.current?.focus()
    else toggle.current?.focus()
  }, [expanded, view.kind])

  return (
    <div className={styles.evidence}>
      <button
        aria-controls={REGION_ID}
        aria-expanded={expanded}
        className={styles.action}
        onClick={() => {
          if (expanded) close()
          else open()
        }}
        ref={toggle}
        type="button"
      >
        {copy.open}
      </button>
      <section
        aria-busy={view.kind === 'loading'}
        aria-labelledby={HEADING_ID}
        className={styles.region}
        hidden={!expanded}
        id={REGION_ID}
        onBlur={(event) => {
          const next = event.relatedTarget
          if (next !== null && !event.currentTarget.contains(next)) lastFocused.current = null
        }}
        onFocus={(event) => {
          lastFocused.current = event.target
        }}
      >
        {expanded ? (
          <>
            <h4 className={styles.heading} id={HEADING_ID} ref={heading} tabIndex={-1}>
              {copy.heading}
            </h4>
            <p className={styles.help}>{copy.intro}</p>
            {view.kind === 'loading' ? <p>{copy.status.loading}</p> : null}
            {view.kind === 'ready' ? (
              <>
                <Snapshots evidence={view.evidence} />
                <Times evidence={view.evidence} />
                <Inputs evidence={view.evidence} />
                <Calculation evidence={view.evidence} />
                <Metrics evidence={view.evidence} />
                <Versions evidence={view.evidence} />
              </>
            ) : null}
            {view.kind === 'failed' ? <Failure result={result} view={view} /> : null}
            {view.kind === 'denied' ? (
              <div className={styles.notice}>
                {view.cause === 'signedOut' ? (
                  <>
                    <p>{copy.failure.signedOut}</p>
                    {/* A document link: the entry screen checks the session again and offers the sign-in. */}
                    <a className={styles.link} href={ENTRY_PATH}>
                      {copy.failure.signInLink}
                    </a>
                  </>
                ) : (
                  <p>{copy.failure.denied}</p>
                )}
              </div>
            ) : null}
            {/* One place for the retry control, so it is the same element before and during a retry. */}
            {retryable || retrying ? (
              <button
                aria-disabled={retrying}
                className={styles.action}
                onClick={() => {
                  if (retryable) retry()
                }}
                type="button"
              >
                {copy.retry}
              </button>
            ) : null}
            <button className={styles.action} onClick={close} type="button">
              {copy.close}
            </button>
          </>
        ) : null}
      </section>
    </div>
  )
}
