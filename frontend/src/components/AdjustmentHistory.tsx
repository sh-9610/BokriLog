import { useEffect, useRef, useState, type ReactElement, type ReactNode } from 'react'

import type {
  Adjustment,
  AdjustmentHistoryState,
  AdjustmentList,
  AdjustmentRevision,
  AdjustmentVersionChange,
  AdjustmentView,
} from '../session/useAdjustmentHistory'
import type { PlanResult } from '../session/useDashboard'

import {
  ADJUSTMENT_DIRECTIONS,
  adjustmentHistoryContent as copy,
  lookup,
  type AdjustmentDirection,
} from '../content/adjustmentHistory'
import { ENTRY_PATH } from '../content/auth'
import { copyFor, planResultContent } from '../content/planResult'
import { planVerificationContent } from '../content/planVerification'
import {
  formatAmount,
  formatQuantity,
  formatSeoulInstant,
  formatSeoulTime,
} from '../format/decimal'
import styles from './AdjustmentHistory.module.css'

const REGION_ID = 'adjustment-history'
const HEADING_ID = 'adjustment-history-heading'
const TERM_ID = 'adjustment-history-term'
const LIST_LABEL_ID = 'adjustment-history-list'

function validTime(value: string): boolean {
  return Number.isFinite(new Date(value).getTime())
}

/** An instant in Asia/Seoul with its machine value. `precise` shows seconds and milliseconds. */
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

function TimeRange({ start, end }: { readonly start: string; readonly end: string }): ReactElement {
  return (
    <>
      <Instant value={start} /> {copy.separators.range} <Instant value={end} />{' '}
      {copy.separators.item} {planResultContent.timeZone}
    </>
  )
}

/** A long machine identifier or code, whole: never shortened, wrapped safely, read as it is shown. */
function Id({ value }: { readonly value: string | number }): ReactElement {
  return <code className={styles.id}>{String(value)}</code>
}

interface Fact {
  readonly label: string
  readonly value: ReactNode
  readonly help?: string
  /** An element the value is described by, such as the term help beside an amount. */
  readonly describedBy?: string
}

function Facts({
  facts,
  className = styles.facts,
}: {
  readonly facts: readonly Fact[]
  readonly className?: string
}): ReactElement {
  return (
    <dl className={className}>
      {facts.map((fact) => (
        <div key={fact.label}>
          <dt>{fact.label}</dt>
          <dd aria-describedby={fact.describedBy}>
            {fact.value}
            {fact.help === undefined ? null : <span className={styles.help}>{fact.help}</span>}
          </dd>
        </div>
      ))}
    </dl>
  )
}

function directionName(direction: string): string {
  return lookup(copy.filter.options, direction, copy.filter.options.ALL)
}

function usd(value: string): ReactElement {
  return (
    <span className={styles.number}>
      {formatAmount(value)} {copy.units.usd}
    </span>
  )
}

function quantity(value: string, unit?: string): ReactElement {
  return (
    <span className={styles.number}>
      {unit === undefined ? formatQuantity(value) : `${formatQuantity(value)} ${unit}`}
    </span>
  )
}

/**
 * The guidance a `검토 필요` input reliability adds, apart from the rows: the safe cause copy, the range it
 * reaches and where the cause and the next action are on this screen. No row is marked by it.
 */
function Review({ result }: { readonly result: PlanResult }): ReactElement | null {
  const reliability = result.trust.inputReliability
  if (reliability.state !== 'REVIEW_REQUIRED') return null
  return (
    <div className={styles.notice}>
      <p className={styles.term}>{copy.review.heading}</p>
      <Facts
        facts={[
          {
            label: copy.review.cause,
            value: copyFor(
              planResultContent.axes.reliability.causes,
              reliability.cause,
              copy.unknownReviewCause,
            ),
          },
          {
            label: copy.review.range,
            value: (
              <TimeRange end={result.cashflowVerifiedThrough} start={result.conditions.startAt} />
            ),
          },
        ]}
      />
      <p>{copy.review.where}</p>
    </div>
  )
}

function Summary({ list }: { readonly list: AdjustmentList }): ReactElement {
  const { summary } = list
  return (
    <div aria-labelledby="adjustment-history-summary" className={styles.part} role="group">
      <p className={styles.term} id="adjustment-history-summary">
        {copy.summary.heading}
      </p>
      <Facts
        facts={[
          {
            label: copy.summary.count,
            value: <span className={styles.number}>{copy.summary.countValue(summary.count)}</span>,
          },
          {
            label: copy.summary.net,
            value: usd(summary.netUsdValue),
            help: copy.summary.netHelp,
            describedBy: TERM_ID,
          },
          {
            label: copy.summary.coverage,
            value: <TimeRange end={list.coverage.through} start={list.coverage.from} />,
          },
          { label: copy.summary.appliedAt, value: <Time value={list.appliedAt} /> },
        ]}
      />
    </div>
  )
}

/**
 * The source events the classification excluded, apart from the filtered summary: it counts the whole
 * classification, whatever type the filter shows, and none of it is a row or part of a total.
 */
function Excluded({ count }: { readonly count: number }): ReactElement {
  return (
    <div className={styles.part}>
      <p className={styles.number}>{copy.excluded.text(count)}</p>
      <p className={styles.help}>{copy.excluded.help}</p>
    </div>
  )
}

/** A USD value rounded once for display, beside its whole canonical value. */
function usdWithFull(value: string): ReactElement {
  return (
    <>
      {usd(value)}{' '}
      <span className={styles.help}>{copy.detail.fullValue(formatQuantity(value))}</span>
    </>
  )
}

function Filter({
  direction,
  onChange,
}: {
  readonly direction: AdjustmentDirection
  readonly onChange: (direction: AdjustmentDirection) => void
}): ReactElement {
  return (
    <fieldset className={styles.filter}>
      <legend className={styles.term}>{copy.filter.legend}</legend>
      <div className={styles.options}>
        {ADJUSTMENT_DIRECTIONS.map((option) => (
          <label className={styles.option} key={option}>
            <input
              checked={direction === option}
              className={styles.radio}
              name="adjustment-direction"
              onChange={() => {
                onChange(option)
              }}
              type="radio"
              value={option}
            />
            {copy.filter.options[option]}
          </label>
        ))}
      </div>
    </fieldset>
  )
}

function Revision({ revision }: { readonly revision: AdjustmentRevision }): ReactElement {
  return (
    <li className={styles.item}>
      <p className={styles.term}>{copy.detail.earlier}</p>
      <Facts
        facts={[
          { label: copy.detail.versionId, value: <Id value={revision.conversionId} /> },
          { label: copy.detail.processedAt, value: <Time precise value={revision.processedAt} /> },
          {
            label: copy.detail.status,
            value: lookup(copy.statuses, revision.status, copy.unknownStatus),
          },
          {
            label: copy.detail.reason,
            value:
              revision.reasonCode === null
                ? copy.noReason
                : copyFor(copy.reasons, revision.reasonCode, copy.unknownReason),
          },
          {
            label: copy.detail.previousValue,
            value:
              revision.usdValue === null ? copy.detail.noValue : usdWithFull(revision.usdValue),
            describedBy: TERM_ID,
          },
          {
            label: copy.detail.changeReason,
            value: lookup(copy.changeReasons, revision.changeReason, copy.unknownChangeReason),
          },
          {
            label: copy.detail.changeDiscoveredAt,
            value: <Time precise value={revision.discoveredAt} />,
          },
        ]}
      />
    </li>
  )
}

/** The sentence of a version change marker: late, new or changed, in words — never a colour alone. */
function versionMarker(change: AdjustmentVersionChange): string {
  const marker = planVerificationContent.history
  if (change.change === 'ADDED') return change.late ? marker.lateAdded : marker.added
  if (change.change === 'VALUE_CHANGED') return marker.valueChanged
  return marker.unknown
}

/**
 * What the shown version changed about this flow (Story 2.12): the marker, the value it had in the previous
 * official version (rounded once for display; none for a flow the previous version did not apply), and when
 * it was discovered and applied.
 */
function VersionChange({ change }: { readonly change: AdjustmentVersionChange }): ReactElement {
  const marker = planVerificationContent.history
  return (
    <div className={styles.part}>
      <p className={styles.changed}>{versionMarker(change)}</p>
      <Facts
        className={styles.rowFacts}
        facts={[
          {
            label: marker.previousValue,
            value:
              change.previousUsdValue === null
                ? planVerificationContent.absent
                : usd(change.previousUsdValue),
            describedBy: change.previousUsdValue === null ? undefined : TERM_ID,
          },
          {
            label: marker.discoveredAt,
            value: <Time precise value={change.discoveredAt} />,
          },
          { label: marker.appliedAt, value: <Time value={change.appliedAt} /> },
        ]}
      />
    </div>
  )
}

/** The lineage of one row: source event → classification → conversion → calculation → change history. */
function Detail({
  row,
  appliedAt,
}: {
  readonly row: Adjustment
  readonly appliedAt: string
}): ReactElement {
  return (
    <>
      <p className={styles.term}>{copy.detail.event}</p>
      <Facts
        facts={[
          { label: copy.detail.sourceId, value: <Id value={row.refs.sourceId} /> },
          { label: copy.detail.sourceEventId, value: <Id value={row.refs.sourceEventId} /> },
          {
            label: copy.detail.transactionType,
            value: <Id value={row.classification.transactionType} />,
          },
          {
            label: copy.detail.discoveredAt,
            value: <Time precise value={row.discoveredAt} />,
            help: copy.detail.discoveredHelp,
          },
        ]}
      />
      <p className={styles.term}>{copy.detail.classification}</p>
      <Facts
        facts={[
          { label: copy.detail.classificationId, value: <Id value={row.refs.classificationId} /> },
          { label: copy.detail.ruleId, value: <Id value={row.classification.ruleId} /> },
          { label: copy.detail.policy, value: <Id value={row.classification.policyVersion} /> },
        ]}
      />
      <p className={styles.term}>{copy.detail.conversion}</p>
      <Facts
        facts={[
          { label: copy.detail.conversionId, value: <Id value={row.refs.conversionId} /> },
          { label: copy.detail.policy, value: <Id value={row.price.policyVersion} /> },
          {
            label: copy.detail.evidenceType,
            value: lookup(copy.evidenceTypes, row.price.evidenceType, copy.unknownEvidenceType),
          },
          {
            label: copy.detail.estimated,
            value: row.price.estimated ? copy.estimated.true : copy.estimated.false,
          },
          {
            label: copy.detail.unitPrice,
            value: quantity(row.price.unitPrice, copy.units.usd),
          },
          {
            label: copy.detail.route,
            value: lookup(copy.routes, row.price.routeId, copy.unknownRoute),
          },
        ]}
      />
      {row.price.legs.length === 0 ? null : (
        <>
          <p className={styles.term}>{copy.detail.legs}</p>
          <ol className={styles.list}>
            {row.price.legs.map((leg, index) => (
              <li className={styles.item} key={`${leg.symbol}-${String(index)}`}>
                <Facts
                  facts={[
                    { label: copy.detail.symbol, value: <Id value={leg.symbol} /> },
                    {
                      label: copy.detail.candle,
                      value: <TimeRange end={leg.candleEndAt} start={leg.candleStartAt} />,
                    },
                    { label: copy.detail.close, value: quantity(leg.closePrice) },
                    {
                      label: copy.detail.selectionRule,
                      // The event's own minute has no lag to name; only an earlier candle says how far back.
                      value:
                        leg.lagMinutes === 0
                          ? lookup(
                              copy.selectionRules,
                              leg.selectionRule,
                              copy.unknownSelectionRule,
                            )
                          : `${lookup(copy.selectionRules, leg.selectionRule, copy.unknownSelectionRule)} ${copy.separators.item} ${copy.lag(leg.lagMinutes)}`,
                    },
                  ]}
                />
              </li>
            ))}
          </ol>
        </>
      )}
      <p className={styles.term}>{copy.detail.calculation}</p>
      <Facts
        facts={[
          { label: copy.detail.calculationId, value: <Id value={row.refs.calculationId} /> },
          { label: copy.detail.sequence, value: <Id value={row.sequence} /> },
          {
            label: copy.detail.appliedAt,
            value: <Time value={appliedAt} />,
            help: copy.detail.appliedHelp,
          },
        ]}
      />
      {row.revisions.length === 0 ? null : (
        <>
          <p className={styles.term}>{copy.detail.revisions}</p>
          <p className={styles.help}>{copy.detail.revisionsHelp}</p>
          {/* The whole values: a change below the second decimal would look alike rounded. */}
          <Facts
            facts={[
              {
                label: copy.detail.currentValue,
                value: usdWithFull(row.usdValue),
                describedBy: TERM_ID,
              },
            ]}
          />
          <ol className={styles.list}>
            {row.revisions.map((revision) => (
              <Revision key={revision.conversionId} revision={revision} />
            ))}
          </ol>
        </>
      )}
    </>
  )
}

/**
 * One applied flow as a named group: its title (sequence, time and type — two flows of one instant and one
 * type still differ by their sequence, in the title and in the toggle's name), then the facts in the reading order —
 * event time → type → quantity → USD value → classification → price → applied version — the change
 * marker in words, the version change marker with the previous value (Story 2.12), and a toggle for the whole
 * lineage.
 */
function Row({
  row,
  appliedAt,
  open,
  onToggle,
  titleRef,
}: {
  readonly row: Adjustment
  readonly appliedAt: string
  readonly open: boolean
  readonly onToggle: () => void
  readonly titleRef: (element: HTMLHeadingElement | null) => void
}): ReactElement {
  const id = `adjustment-row-${String(row.sequence)}`
  const type = directionName(row.direction)
  const time = formatSeoulInstant(row.occurredAt, planResultContent.timeUnavailable)
  return (
    <li className={styles.row}>
      <div aria-labelledby={`${id}-title`} className={styles.card} role="group">
        <h5 className={styles.rowTitle} id={`${id}-title`} ref={titleRef} tabIndex={-1}>
          {copy.row.title(row.sequence)} {copy.separators.item}{' '}
          <Instant precise value={row.occurredAt} /> {copy.separators.item} {type}
        </h5>
        <Facts
          className={styles.rowFacts}
          facts={[
            { label: copy.row.occurredAt, value: <Time precise value={row.occurredAt} /> },
            { label: copy.row.type, value: type },
            { label: copy.row.quantity, value: quantity(row.signedQuantity, row.asset) },
            { label: copy.row.usd, value: usd(row.usdValue), describedBy: TERM_ID },
            {
              label: copy.row.classification,
              value: (
                <>
                  <Id value={row.classification.transactionType} /> {copy.separators.item}{' '}
                  <Id value={row.classification.ruleId} />
                </>
              ),
            },
            {
              label: copy.row.price,
              value: `${lookup(copy.evidenceTypes, row.price.evidenceType, copy.unknownEvidenceType)} ${copy.separators.item} ${row.price.estimated ? copy.estimated.true : copy.estimated.false}`,
            },
            {
              label: copy.row.applied,
              value: (
                <>
                  {copy.row.appliedValue(row.sequence)} {copy.separators.item}{' '}
                  <Instant value={appliedAt} />
                </>
              ),
            },
          ]}
        />
        {row.changed ? <p className={styles.changed}>{copy.row.changed}</p> : null}
        {row.versionChange === undefined || row.versionChange === null ? null : (
          <VersionChange change={row.versionChange} />
        )}
        <button
          aria-controls={`${id}-detail`}
          aria-expanded={open}
          className={styles.action}
          onClick={onToggle}
          type="button"
        >
          {copy.row.detailOpen(row.sequence, time, type)}
        </button>
        <div className={styles.detail} hidden={!open} id={`${id}-detail`}>
          {open ? <Detail appliedAt={appliedAt} row={row} /> : null}
        </div>
      </div>
    </li>
  )
}

/**
 * The rows received so far, in one list. Which rows' details are open is this list's own state: a new
 * filter mounts a new list. After `더 보기`, focus moves to the first new row's title.
 */
function Rows({
  list,
  focusFrom,
  onFocused,
}: {
  readonly list: AdjustmentList
  readonly focusFrom: number | null
  readonly onFocused: () => void
}): ReactElement {
  const [opened, setOpened] = useState<ReadonlySet<string>>(new Set())
  const titles = useRef(new Map<number, HTMLHeadingElement>())

  useEffect(() => {
    if (focusFrom === null || list.rows.length <= focusFrom) return
    titles.current.get(focusFrom)?.focus()
    onFocused()
  }, [focusFrom, list.rows.length, onFocused])

  return (
    <>
      <p className={styles.term} id={LIST_LABEL_ID}>
        {copy.list}
      </p>
      <ol aria-labelledby={LIST_LABEL_ID} className={styles.rows}>
        {list.rows.map((row, index) => (
          <Row
            appliedAt={list.appliedAt}
            key={row.refs.sourceEventId}
            onToggle={() => {
              setOpened((current) => {
                const next = new Set(current)
                if (next.has(row.refs.sourceEventId)) next.delete(row.refs.sourceEventId)
                else next.add(row.refs.sourceEventId)
                return next
              })
            }}
            open={opened.has(row.refs.sourceEventId)}
            row={row}
            titleRef={(element) => {
              if (element === null) titles.current.delete(index)
              else titles.current.set(index, element)
            }}
          />
        ))}
      </ol>
    </>
  )
}

function Empty({
  list,
  result,
}: {
  readonly list: AdjustmentList
  readonly result: PlanResult
}): ReactElement {
  if (result.flowCount === 0) {
    return (
      <div className={styles.part}>
        <p className={styles.term}>{copy.empty.none}</p>
        <p>{copy.empty.noneHelp}</p>
      </div>
    )
  }
  return <p>{copy.empty.filtered(directionName(list.direction))}</p>
}

function Failure({
  view,
}: {
  readonly view: Extract<AdjustmentView, { kind: 'open' }>
}): ReactElement | null {
  const { read } = view
  if (read.kind !== 'failed') return null
  return (
    <div className={styles.notice}>
      {/* Only a failed next page has rows it kept; a failed first page does not claim any. */}
      <p>
        {!read.retryable
          ? copy.failure.stale
          : read.more
            ? copy.failure.moreRetryable
            : copy.failure.retryable}
      </p>
      <p>
        {!read.retryable
          ? copy.failure.staleHelp
          : read.more
            ? copy.failure.moreRetryableHelp
            : copy.failure.retryableHelp}
      </p>
    </div>
  )
}

/**
 * The control in the region's one action slot, so it is the same element before and during a read: the
 * retry of a failed first page, or `더 보기` (which also asks again after a failed next page).
 */
function slotOf(
  view: Extract<AdjustmentView, { kind: 'open' }>,
): { readonly label: string; readonly busy: boolean; readonly run: 'retry' | 'more' } | null {
  const { read, list } = view
  if (read.kind === 'failed' && !read.more) {
    return read.retryable ? { label: copy.retry, busy: false, run: 'retry' } : null
  }
  if (read.kind === 'loading' && !read.more) {
    return read.retrying ? { label: copy.retry, busy: true, run: 'retry' } : null
  }
  if (read.kind === 'failed' && !read.retryable) return null
  if (list?.nextCursor == null) return null
  return { label: copy.more, busy: read.kind === 'loading', run: 'more' }
}

/**
 * The adjustment history of the shown result (Story 2.10): a `조정 이력 보기` button and the region it opens on
 * this same screen — a state change, not a navigation, so a button and not a link.
 *
 * Opening moves focus to the region's heading; `조정 이력 닫기` returns it to the button. When the region closes
 * for another reason (the shown bundle was replaced) while focus was inside it, focus also returns to the
 * button; when what held focus goes away while the region stays open, focus moves to its heading. The
 * region reads: description and the term's help → review guidance → summary → excluded count → filter → rows →
 * `더 보기`.
 * The region has no live region: the screen's single status region says what happened.
 */
export function AdjustmentHistory({
  history,
  result,
}: {
  readonly history: AdjustmentHistoryState
  readonly result: PlanResult
}): ReactElement {
  const { view, open, close, filter, more, retry } = history
  const expanded = view.kind !== 'closed'
  const toggle = useRef<HTMLButtonElement>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  const lastFocused = useRef<Element | null>(null)
  const wasExpanded = useRef(expanded)
  // The number of rows held when `더 보기` was pressed: the first new row takes focus when it arrives.
  const [focusFrom, setFocusFrom] = useState<number | null>(null)
  const loading = view.kind === 'open' && view.read.kind === 'loading'

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
  }, [expanded, view])

  const shown = view.kind === 'open' ? view : null
  const list = shown === null ? null : shown.list
  const slot = shown === null ? null : slotOf(shown)
  return (
    <div className={styles.history}>
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
        aria-busy={loading}
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
            <p className={styles.help} id={TERM_ID}>
              {copy.term}
            </p>
            <Review result={result} />
            {list === null ? null : <Summary list={list} />}
            {list === null ? null : <Excluded count={list.summary.excludedCount} />}
            {shown === null ? null : (
              <Filter
                direction={shown.direction}
                onChange={(direction) => {
                  setFocusFrom(null)
                  filter(direction)
                }}
              />
            )}
            {shown?.read.kind === 'loading' ? (
              <p>{shown.read.more ? copy.status.loadingMore : copy.status.loading}</p>
            ) : null}
            {list === null ? null : list.rows.length === 0 ? (
              <Empty list={list} result={result} />
            ) : (
              <Rows
                focusFrom={focusFrom}
                list={list}
                onFocused={() => {
                  setFocusFrom(null)
                }}
              />
            )}
            {shown === null ? null : <Failure view={shown} />}
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
            {/*
              One element per kind of control: a retry that succeeded goes away (focus then moves to the
              heading, above the rows it brought), while `더 보기` stays the same element across its reads.
            */}
            {slot === null ? null : (
              <button
                aria-disabled={slot.busy}
                className={styles.action}
                key={slot.run}
                onClick={() => {
                  if (slot.busy) return
                  if (slot.run === 'retry') {
                    retry()
                    return
                  }
                  setFocusFrom(list === null ? null : list.rows.length)
                  more()
                }}
                type="button"
              >
                {slot.label}
              </button>
            )}
            <button className={styles.action} onClick={close} type="button">
              {copy.close}
            </button>
          </>
        ) : null}
      </section>
    </div>
  )
}
