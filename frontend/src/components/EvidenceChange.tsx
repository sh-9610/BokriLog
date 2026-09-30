import type { ReactElement } from 'react'

import type {
  CalculationEvidence,
  EvidenceChangeEvent,
  EvidenceChangeMetric,
} from '../session/useCalculationEvidence'

import { calculationEvidenceContent } from '../content/calculationEvidence'
import { copyFor, planResultContent, type MetricName } from '../content/planResult'
import { planVerificationContent } from '../content/planVerification'
import { formatAmount, formatPercent, formatSeoulTime } from '../format/decimal'
import styles from './CalculationEvidence.module.css'
import { Facts, Id, Part, Time } from './EvidenceParts'
import { RangeList } from './VerificationRanges'

const copy = planVerificationContent.evidence
const ABSENT = planVerificationContent.absent

const RATIOS: readonly MetricName[] = ['REQUIRED_RATE', 'GOAL_PROGRESS']

/** A metric side: its value rounded once for display (DISPLAY-1.0), or `계산 불가` with its reason. */
function side(metric: MetricName, value: string | null, reason: string | null): string {
  if (value === null) {
    const why = copyFor(planResultContent.reasons, reason ?? '', planResultContent.unknownReason)
    return `${planResultContent.notComputable} ${calculationEvidenceContent.separators.item} ${why}`
  }
  return RATIOS.includes(metric)
    ? formatPercent(value)
    : `${formatAmount(value)} ${calculationEvidenceContent.units.usd}`
}

/** A USD value rounded once for display, or `없음` when that side has none (an added or removed flow). */
function usd(value: string | null | undefined): string {
  return value === null || value === undefined
    ? ABSENT
    : `${formatAmount(value)} ${calculationEvidenceContent.units.usd}`
}

function estimated(value: boolean | null | undefined): string {
  return value === null || value === undefined
    ? ABSENT
    : value
      ? copy.estimatedValues.true
      : copy.estimatedValues.false
}

/**
 * What the change was, in one sentence of its kind: only an added flow can have been found late; a changed or
 * removed flow says what happened to it, never that it is new.
 */
function eventNote(event: EvidenceChangeEvent): string {
  const notes = copy.eventNotes
  switch (event.changeKind) {
    case 'ADDED':
      return event.late ? notes.lateAdded : notes.added
    case 'VALUE_CHANGED':
      return notes.valueChanged
    case 'REMOVED':
      return notes.removed
    default:
      return notes.unknown
  }
}

/** Two sides of one fact, the previous first: `이전 → 새`. */
function pair(previous: string, next: string): string {
  return `${previous} ${copy.arrow} ${next}`
}

function MetricChange({ metric }: { readonly metric: EvidenceChangeMetric }): ReactElement {
  return (
    <li className={styles.item}>
      <p className={styles.term}>{planResultContent.metrics[metric.metric].name}</p>
      <Facts
        facts={[
          {
            label: copy.previousValue,
            value: (
              <span className={styles.number}>
                {side(metric.metric, metric.previousValue, metric.previousUnavailableReason)}
              </span>
            ),
          },
          {
            label: copy.newValue,
            value: (
              <span className={styles.number}>
                {side(metric.metric, metric.value, metric.unavailableReason)}
              </span>
            ),
          },
        ]}
      />
    </li>
  )
}

function EventChange({ event }: { readonly event: EvidenceChangeEvent }): ReactElement {
  const kind = copyFor(copy.eventKinds, event.changeKind, copy.unknownEventKind)
  return (
    <li className={styles.item}>
      <p className={styles.term}>{copy.eventTitle(kind, event.sourceId)}</p>
      <p>{eventNote(event)}</p>
      <Facts
        facts={[
          { label: copy.sourceId, value: <Id value={event.sourceId} /> },
          { label: copy.occurredAt, value: <Time precise value={event.occurredAt} /> },
          {
            label: copy.transactionType,
            value: copyFor(
              copy.transactionTypes,
              event.transactionType,
              copy.unknownTransactionType,
            ),
          },
          {
            label: copy.usd,
            value: (
              <span className={styles.number}>
                {pair(usd(event.previousUsdValue), usd(event.usdValue))}
              </span>
            ),
          },
          {
            label: copy.estimated,
            value: pair(estimated(event.previousEstimated), estimated(event.estimated)),
          },
          {
            label: copy.evidenceType,
            value: copyFor(copy.evidenceTypes, event.evidenceType, copy.unknownEvidenceType),
          },
          {
            label: copy.discoveredAt,
            value: <Time precise value={event.discoveredAt} />,
            help: copy.discoveredHelp,
          },
          {
            label: copy.appliedAt,
            value: <Time value={event.appliedAt} />,
            help: copy.appliedHelp,
          },
        ]}
      />
    </li>
  )
}

/**
 * The change part of the calculation evidence (Story 2.12), before the snapshots: what the shown version
 * changed from the previous official version and why. It reads kind → job and reason → ranges read again and
 * reused → previous version and its basis time → where the impact starts → the six metrics before and after
 * (and the fact that the basis time moved too, so a metric may differ without any cash-flow change) → each
 * changed cash flow with its Bybit id, time, type (in words: an external deposit or withdrawal), USD value and
 * character before and after, price evidence, and discovery and application times. The first official version
 * (the only one in the list) says there is nothing to compare. Nothing here says the whole history was read
 * again: the ranges are exactly the ones read. The two basis times it compares are the same kind of field: the
 * previous version's basis and the shown result's (`basisAt`).
 */
export function EvidenceChange({
  evidence,
  basisAt,
}: {
  readonly evidence: CalculationEvidence
  readonly basisAt: string
}): ReactElement {
  const { change } = evidence
  const verification = planVerificationContent
  const { previous } = change
  const first = evidence.versions.official.length === 1
  return (
    <Part id="change" title={copy.title}>
      <p>{first ? copy.first : copyFor(copy.kinds, change.kind, copy.unknownKind)}</p>
      <Facts
        facts={[
          {
            label: copy.purpose,
            value:
              change.purpose === null
                ? ABSENT
                : copyFor(verification.purposes, change.purpose, verification.unknownPurpose),
          },
          {
            label: copy.reason,
            value:
              change.reason === null
                ? ABSENT
                : copyFor(verification.jobReasons, change.reason, verification.unknownJobReason),
          },
          {
            label: copy.targets,
            value: (
              <RangeList
                className={styles.list}
                empty={verification.none}
                ranges={change.targets}
              />
            ),
          },
          {
            label: copy.reused,
            value: (
              <RangeList className={styles.list} empty={verification.none} ranges={change.reused} />
            ),
          },
        ]}
      />
      {previous === null ? null : (
        <>
          <p className={styles.term}>{copy.previous}</p>
          <Facts
            facts={[
              { label: copy.previousOrder, value: <Id value={previous.pointerVersion} /> },
              { label: copy.previousCalculation, value: <Id value={previous.calculationId} /> },
              { label: copy.previousBasis, value: <Time precise value={previous.basisAt} /> },
            ]}
          />
        </>
      )}
      <Facts
        facts={[
          {
            label: copy.firstAffected,
            value:
              change.firstAffectedAt === null ? (
                ABSENT
              ) : (
                <Time precise value={change.firstAffectedAt} />
              ),
            help: change.firstAffectedAt === null ? undefined : copy.firstAffectedHelp,
          },
        ]}
      />
      {previous === null ? null : (
        <>
          <p className={styles.term}>{copy.metrics}</p>
          <p className={styles.help}>
            {copy.metricsHelp(
              formatSeoulTime(previous.basisAt, planResultContent.timeUnavailable),
              formatSeoulTime(basisAt, planResultContent.timeUnavailable),
            )}
          </p>
          <ul className={styles.list}>
            {change.metrics.map((metric) => (
              <MetricChange key={metric.metric} metric={metric} />
            ))}
          </ul>
        </>
      )}
      <p className={styles.term}>{copy.events}</p>
      {change.events.length === 0 ? (
        <p>{copy.noEvents}</p>
      ) : (
        <ul className={styles.list}>
          {change.events.map((event) => (
            <EventChange event={event} key={`${event.changeKind}-${event.sourceId}`} />
          ))}
        </ul>
      )}
    </Part>
  )
}
