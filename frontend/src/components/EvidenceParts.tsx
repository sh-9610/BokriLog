import type { ReactElement, ReactNode } from 'react'

import { calculationEvidenceContent as copy } from '../content/calculationEvidence'
import { planResultContent } from '../content/planResult'
import { formatSeoulInstant, formatSeoulTime } from '../format/decimal'
import { isInstant } from '../session/useDashboard'
import styles from './CalculationEvidence.module.css'

/*
 * The building blocks the calculation evidence (Story 2.9) and its change part (Story 2.12) share: times in
 * Asia/Seoul, whole identifiers, fact lists and the named parts of the reading order. One place, so the two
 * render a time, an id or a part the same way. The times are also the sync, verification and version-change
 * controls' (Story 2.12): every Seoul time on the dashboard is rendered here, with one guard — a machine value
 * only for an instant in the server's own format (`isInstant`).
 */

/** An instant in Asia/Seoul with its machine value, or the unavailable sentence (never spliced into another). */
export function Instant({
  value,
  precise = false,
}: {
  readonly value: string
  readonly precise?: boolean
}): ReactElement {
  const format = precise ? formatSeoulInstant : formatSeoulTime
  return (
    <time dateTime={isInstant(value) ? value : undefined}>
      {format(value, planResultContent.timeUnavailable)}
    </time>
  )
}

/**
 * A time in Asia/Seoul with its zone. `precise` shows seconds and milliseconds: the times section says
 * whether two times are the same, and two times in the same minute must not look alike there.
 */
export function Time({
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
export function TimeRange({
  start,
  end,
  precise = false,
}: {
  readonly start: string
  readonly end: string
  readonly precise?: boolean
}): ReactElement {
  return (
    <>
      <Instant precise={precise} value={start} /> {copy.separators.range}{' '}
      <Instant precise={precise} value={end} /> {copy.separators.item} {planResultContent.timeZone}
    </>
  )
}

/**
 * A long machine identifier, whole: never shortened, wrapped safely even without spaces, and read by
 * assistive technology exactly as it is shown (DESIGN.md: mono, full value from the start).
 */
export function Id({ value }: { readonly value: string | number }): ReactElement {
  return <code className={styles.id}>{String(value)}</code>
}

export interface Fact {
  readonly label: string
  readonly value: ReactNode
  readonly help?: string
}

export function Facts({ facts }: { readonly facts: readonly Fact[] }): ReactElement {
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

/** One of the parts, a named group in the reading order. */
export function Part({
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
