import { useLayoutEffect, useRef, type ReactElement, type ReactNode } from 'react'

import type { AdjustmentHistoryState } from '../session/useAdjustmentHistory'
import type { EvidenceState } from '../session/useCalculationEvidence'
import type {
  Accepted,
  Dashboard,
  DashboardView,
  PlanResult,
  PlanResultMetric,
  TrustAxis,
} from '../session/useDashboard'
import type { DashboardSync } from '../session/useDashboardSync'
import type { PlanEnd } from '../session/usePlanEnd'
import type { ReauthenticationAccess } from '../session/useReauthentication'

import { ENTRY_PATH } from '../content/auth'
import { planConditionContent } from '../content/planConditions'
import { planEndContent } from '../content/planEnd'
import {
  axisActionCopy,
  planResultContent as copy,
  copyFor,
  nextActionCopy,
  type MetricName,
} from '../content/planResult'
import { planVerificationContent } from '../content/planVerification'
import { formatAmount, formatPercent, formatSeoulTime, percentFromRatio } from '../format/decimal'
import { bundleOf, heldBy } from '../session/useDashboard'
import { AdjustmentHistory } from './AdjustmentHistory'
import { CalculationEvidence } from './CalculationEvidence'
import { PlanEndControl } from './PlanEndControl'
import { PlanEndRecord } from './PlanEndRecord'
import styles from './PlanResultPanel.module.css'
import { SyncControl, type NewPlanChoice } from './SyncControl'
import { VerificationControl } from './VerificationControl'
import { RangeList } from './VerificationRanges'
import { VersionChangeNote } from './VersionChangeNote'

const RATIOS: readonly MetricName[] = ['REQUIRED_RATE', 'GOAL_PROGRESS']

/**
 * The input reliability causes a verification or catch-up ended in (Story 2.12): asking again for the same basis
 * cannot change them, so the axis says so and names the ranges the job that holds the result set out to check.
 */
const HELD_CAUSES: readonly string[] = ['VERIFICATION_REVIEW_REQUIRED', 'HISTORY_OUT_OF_RANGE']

function validTime(value: string): boolean {
  return Number.isFinite(new Date(value).getTime())
}

function time(value: string): ReactElement {
  return (
    <time dateTime={validTime(value) ? value : undefined}>
      {formatSeoulTime(value, copy.timeUnavailable)}
    </time>
  )
}

function seoul(value: string): string {
  return formatSeoulTime(value, copy.timeUnavailable)
}

/** The Seoul time, or `null` when it cannot be shown — copy then uses its variant without a time. */
function seoulOrNull(value: string): string | null {
  const shown = seoul(value)
  return validTime(value) && shown !== copy.timeUnavailable ? shown : null
}

function displayValue(metric: PlanResultMetric): string {
  if (metric.value === undefined) return copy.notComputable
  return RATIOS.includes(metric.metric)
    ? formatPercent(metric.value)
    : `${formatAmount(metric.value)} USD`
}

function metricName(name: string): string {
  return Object.hasOwn(copy.metrics, name)
    ? copy.metrics[name as MetricName].name
    : copy.axes.unknownMetric
}

function metricNames(names: readonly string[]): string {
  return names.length === 0 ? copy.axes.noneAffected : names.map(metricName).join(', ')
}

function metricId(metric: string): string {
  return `plan-metric-${metric.toLowerCase().replaceAll('_', '-')}`
}

/**
 * One metric card: name → value → period and basis → character (AC-3). An undefined metric shows
 * `계산 불가` in the value's place, linked once to its reason with `aria-describedby`, never a zero.
 */
function MetricCard({
  metric,
  result,
}: {
  readonly metric: PlanResultMetric
  readonly result: PlanResult
}): ReactElement {
  const id = metricId(metric.metric)
  const reasonId = `${id}-reason`
  const unit = copyFor(copy.periodUnit, result.conditions.period, copy.unknownPeriodUnit)
  const basis = seoul(result.basisAt)
  return (
    <article aria-labelledby={id} className={styles.card}>
      <h4 className={styles.metricName} id={id}>
        {copy.metrics[metric.metric].name}
      </h4>
      <p
        aria-describedby={metric.value === undefined ? reasonId : undefined}
        className={styles.value}
      >
        {displayValue(metric)}
      </p>
      <p className={styles.meta}>
        {metric.metric === 'CURRENT_VALUE'
          ? `${copy.basisLine(unit, basis)} · ${copy.measuredLine(seoul(result.measuredAt))}`
          : copy.basisLine(unit, basis)}
      </p>
      {/*
        The character (official or estimated) is always shown. "Not computable" is a separate fact
        with its own reason and never replaces it: an undefined ratio of an estimated plan is still
        estimated.
      */}
      <p className={styles.nature}>
        {copy.natureLabel}: {copyFor(copy.natures, metric.nature, copy.axes.unknownState)}
      </p>
      {metric.unavailableReason !== undefined ? (
        <p id={reasonId}>
          {copy.reasonLabel}: {copyFor(copy.reasons, metric.unavailableReason, copy.unknownReason)}
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
  )
}

/** One named time an axis shows. Each time keeps its own label and value. */
interface AxisTime {
  readonly label: string
  readonly value: string
}

/** One more named fact an axis shows after its times, such as the range a held input reaches. */
interface AxisRow {
  readonly label: string
  readonly value: ReactNode
}

/**
 * One trust axis as its own group: state → cause → times → affected metrics → next action, the order
 * EXPERIENCE gives ("what is it → why → which values → what can the user do"). The axis's next action
 * is a short sentence without a time; the approved sentence with the measurement time appears once, in
 * the screen's next-action section. `after` follows the group inside the same item — the freshness axis's
 * sync control (Story 2.11), right after the axis it acts on. `action` replaces the axis's own next action — the
 * record of a plan ended early (Story 2.13) has nothing left to do on any axis.
 */
function Axis({
  id,
  name,
  states,
  causes,
  axis,
  extra,
  times,
  note,
  rows = [],
  after,
  action = axis.nextAction,
}: {
  readonly id: string
  readonly name: string
  readonly states: Readonly<Record<string, string>>
  readonly causes: Readonly<Record<string, string>>
  readonly axis: TrustAxis
  readonly extra?: readonly string[]
  readonly times: readonly AxisTime[]
  readonly note?: { readonly label: string; readonly text: string }
  readonly rows?: readonly AxisRow[]
  readonly after?: ReactNode
  readonly action?: string
}): ReactElement {
  return (
    <li className={styles.axis}>
      <div aria-labelledby={id} role="group">
        <h4 className={styles.axisName} id={id}>
          {name}: {copyFor(states, axis.state, copy.axes.unknownState)}
        </h4>
        <dl className={styles.facts}>
          <div>
            <dt>{copy.axes.cause}</dt>
            <dd>
              {copyFor(causes, axis.cause, copy.axes.unknownCause)}
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
              <dd>
                {time(entry.value)} · {copy.timeZone}
              </dd>
            </div>
          ))}
          {note === undefined ? null : (
            <div>
              <dt>{note.label}</dt>
              <dd>{note.text}</dd>
            </div>
          )}
          {rows.map((row) => (
            <div key={row.label}>
              <dt>{row.label}</dt>
              <dd>{row.value}</dd>
            </div>
          ))}
          <div>
            <dt>{copy.axes.affected}</dt>
            <dd>{metricNames(axis.affectedMetrics)}</dd>
          </div>
          <div>
            <dt>{copy.axes.action}</dt>
            <dd>{axisActionCopy(action)}</dd>
          </div>
        </dl>
      </div>
      {after}
    </li>
  )
}

/**
 * The three trust axes of an active plan, each its own group. The record of a plan ended early (Story 2.13) has no
 * freshness axis — it is no longer synced, and the end record takes that place — so it shows the input reliability
 * and the calculation nature only, without the sync and verification controls. In the record neither axis points to
 * an action (their next action is `NONE`), and a held input is said by its cause alone: the held notice and the
 * ranges a verification set out to check belong to the controls the record does not show.
 */
function Trust({
  bundle,
  sync,
  newPlan,
}: {
  readonly bundle: Accepted
  readonly sync: DashboardSync
  readonly newPlan: NewPlanChoice | undefined
}): ReactElement {
  const { result, verification } = bundle
  const ended = bundle.end !== null
  const { freshness, inputReliability, calculationNature } = result.trust
  // A job that ended in review or out of range holds the input (spec: 미해소): the notice that asking again for
  // the same basis will not change it — in the words of that job's purpose — and the ranges it set out to
  // check. Only the job the server marks as holding the result names ranges: one in progress, one that
  // published or one of an earlier basis does not. Ranges it could not read are said in safe words.
  const held = !ended && HELD_CAUSES.includes(inputReliability.cause)
  // In the record nothing is left to do: no axis points to a control it does not show.
  const action = ended ? 'NONE' : undefined
  const holder = held ? heldBy(verification) : null
  const heldCopy = planVerificationContent.held
  const heldNotice =
    holder === null ? heldCopy.notice : copyFor(heldCopy.notices, holder.purpose, heldCopy.notice)
  const heldRows: AxisRow[] =
    holder === null || holder.targets?.length === 0
      ? []
      : [
          {
            label: heldCopy.targetsLabel,
            value: (
              <RangeList
                className={styles.ranges}
                empty={planVerificationContent.none}
                ranges={holder.targets}
              />
            ),
          },
        ]
  const unavailable = calculationNature.unavailableMetrics.map(metricName)
  const natureExtra = [
    ...(unavailable.length > 0
      ? [copy.axes.nature.unavailableMetrics(unavailable.join(', '))]
      : []),
    ...(calculationNature.state === 'NOT_COMPUTABLE' && calculationNature.character === 'ESTIMATED'
      ? [copy.axes.nature.estimatedToo]
      : []),
  ]
  return (
    <section aria-labelledby="plan-result-trust" className={styles.group}>
      <h3 id="plan-result-trust">{copy.trustHeading}</h3>
      {/*
        Equal groups, never merged into one score or badge: three for an active plan, two in the record of a plan
        ended early (the end record takes the freshness axis's place).
      */}
      <ul className={styles.axes}>
        {ended ? null : (
          <Axis
            after={
              <>
                <SyncControl
                  newPlan={newPlan}
                  status={bundle.sync}
                  sync={sync}
                  verification={verification}
                />
                <VerificationControl status={verification} sync={sync} />
              </>
            }
            axis={freshness}
            causes={copy.axes.freshness.causes}
            id="plan-result-axis-freshness"
            name={copy.axes.freshness.name}
            note={{
              label: copy.axes.freshness.timesNoteLabel,
              text: copy.axes.freshness.timesNote,
            }}
            states={copy.axes.freshness.states}
            times={[
              { label: copy.axes.freshness.lastNormal, value: freshness.lastNormalAt },
              {
                label: copy.axes.freshness.verifiedThrough,
                value: freshness.cashflowVerifiedThrough,
              },
            ]}
          />
        )}
        <Axis
          action={action}
          axis={inputReliability}
          causes={copy.axes.reliability.causes}
          extra={held ? [heldNotice] : undefined}
          id="plan-result-axis-reliability"
          name={copy.axes.reliability.name}
          rows={heldRows}
          states={copy.axes.reliability.states}
          times={[
            { label: copy.axes.reliability.lastNormal, value: inputReliability.lastNormalAt },
          ]}
        />
        <Axis
          action={action}
          axis={calculationNature}
          causes={copy.axes.nature.causes}
          extra={natureExtra}
          id="plan-result-axis-nature"
          name={copy.axes.nature.name}
          states={copy.axes.nature.states}
          times={[{ label: copy.axes.nature.lastNormal, value: calculationNature.lastNormalAt }]}
        />
      </ul>
    </section>
  )
}

function Conditions({ result }: { readonly result: PlanResult }): ReactElement {
  const periodName = copyFor(copy.periods, result.conditions.period, copy.unknownPeriod)
  return (
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
      <p className={styles.meta}>
        {copy.policies}: {result.policies.calculation} · {result.policies.calendar} ·{' '}
        {result.policies.display} · {result.policies.price}
      </p>
    </section>
  )
}

/** The screen-level next action code of the view; a view without one gets the safe default copy. */
function nextActionOf(view: DashboardView): string {
  const bundle = bundleOf(view)
  if (bundle !== null) return bundle.nextAction
  return view.kind === 'NO_PLAN' || view.kind === 'PREPARING' ? view.nextAction : ''
}

/** The state notice beside the values: the last good result, a read to retry, or a refusal. */
function Notice({
  view,
  measured,
}: {
  readonly view: DashboardView
  readonly measured: string | null
}): ReactElement | null {
  if (view.kind === 'lastGood') {
    return (
      <div className={styles.notice}>
        <p className={styles.noticeName}>{copy.lastGoodLabel}</p>
        <p>{copy.lastGoodHelp(measured)}</p>
      </div>
    )
  }
  if (view.kind === 'retryable') {
    return (
      <div className={styles.notice}>
        <p className={styles.noticeName}>{copy.retryableLabel}</p>
        <p>{view.kept === null ? copy.retryableHelp : copy.versionConflictHelp(measured)}</p>
      </div>
    )
  }
  if (view.kind === 'denied') {
    return (
      <div className={styles.notice}>
        {view.cause === 'signedOut' ? (
          // A document link: the entry screen checks the session again and offers the sign-in.
          <a className={styles.action} href={ENTRY_PATH}>
            {copy.signInLink}
          </a>
        ) : (
          <p>{copy.deniedHelp}</p>
        )}
      </div>
    )
  }
  return null
}

/**
 * The official dashboard (Story 2.8), and the sync that updates it (Story 2.11).
 *
 * Everything comes from one response, held as one piece of state, so a newer bundle replaces the six
 * values and the three axes at once — cards are never assembled from separate requests. The order is
 * mobile first: the last normal current value with its measurement time, the three trust axes (with the
 * note that separates the two times), the other five metrics, the next action, then the plan terms and
 * policies. The current value appears once, as the first of the six cards.
 *
 * Values are rounded for display here and only here. Undefined metrics show `계산 불가` with their reason
 * instead of a zero, beside their unchanged character; negative rates and progress outside 0–100% are
 * shown as they are, with no colour or icon judging them. The screen's single status region announces
 * each change, so this panel has no live region of its own and does not repeat that sentence.
 *
 * The next-action block keeps its place in every state that has it, so the reload button a keyboard
 * user pressed stays the focused element when the view changes under it. When the view changes to a
 * state without that block, or to a state without a bundle (which takes the calculation evidence and its
 * button with it), while focus was inside the panel on something that went away, focus moves to the
 * panel's heading rather than falling to the document body.
 *
 * The calculation evidence (Story 2.9) opens beside the metrics heading, on this same screen, for the
 * bundle shown here, and the adjustment history (Story 2.10) opens right after it. Their states are their
 * own (`evidence`, `adjustments`); the screen's status region says what they did.
 *
 * The sync control (Story 2.11) sits right after the freshness axis, for the bundle shown, and the precision
 * verification (Story 2.12) right after it. While a job runs the bundle stays as it is; when it ends with a new
 * bundle, that bundle replaces this one in one piece (the dashboard's own rule), and a job the user pressed
 * moves focus to this panel's heading. Their state is their own (`sync`); the screen's status region says what
 * they did. The result marker of the shown version (Story 2.12) sits under the introduction: how many late
 * cash flows it applied and where their impact starts, or that a verification found no change.
 *
 * The `계획 종료` area (Story 2.13) closes the active plan's panel, after the plan terms; the long-gap choice
 * `새 계획 시작` in the sync control starts the same end flow. When a plan ended early is shown, the panel is in its
 * record mode — the same panel, read-only: the end record takes the freshness axis's place (under the
 * introduction), the six metrics, the input reliability and calculation nature, the evidence and history toggles
 * and the plan terms stay, the next action is the preparation of a new plan (explained only), and there is no sync,
 * verification or end control. When the end flow ends the plan, focus moves to this heading.
 */
export function PlanResultPanel({
  dashboard,
  evidence,
  adjustments,
  sync,
  planEnd,
  reauthentication,
}: {
  readonly dashboard: Dashboard
  readonly evidence: EvidenceState
  readonly adjustments: AdjustmentHistoryState
  readonly sync: DashboardSync
  readonly planEnd: PlanEnd
  readonly reauthentication: ReauthenticationAccess
}): ReactElement {
  const { view, loading, reload } = dashboard
  const bundle = bundleOf(view)
  const result = bundle?.result ?? null
  // The record of a plan ended early (Story 2.13): the same panel, read-only.
  const end = bundle?.end ?? null
  const ended = end !== null
  const [current, ...rest] = result?.metrics ?? []
  const measured = result === null ? null : seoulOrNull(result.measuredAt)
  const hasNextAction = view.kind !== 'checking' && view.kind !== 'denied'
  const hasResult = result !== null
  const heading = useRef<HTMLHeadingElement>(null)
  // The element inside the panel that last had focus, until focus leaves the panel for another element.
  const lastFocused = useRef<Element | null>(null)
  // A sync's focus request made before this panel was mounted is not replayed.
  const handledFocus = useRef(sync.focus?.serial ?? 0)
  const syncFocus = sync.focus
  // The same for the end flow's.
  const handledEndFocus = useRef(planEnd.focus?.serial ?? 0)
  const endFocus = planEnd.focus

  /*
   * Focus on something inside the panel that went away is not dropped: when the view changes to a state without
   * the next-action block or without a bundle, or to the record of a plan ended early (which takes the sync,
   * verification and end controls with it).
   *
   * The three focus moves are layout effects: they run in the commit that removes or replaces the content, so focus
   * never rests on the body for a frame. As passive effects they ran after the commit on the scheduler's own task,
   * and under load that came after code that had already seen the new content (a loaded CI runner, run 37134688361).
   */
  useLayoutEffect(() => {
    if (hasNextAction && hasResult && !ended) return
    const last = lastFocused.current
    const active = document.activeElement
    const lost = active === null || active === document.body || !active.isConnected
    if (last !== null && !last.isConnected && lost) heading.current?.focus()
  }, [hasNextAction, hasResult, ended])

  // A sync the user pressed ended with a new bundle: focus goes to the heading of what replaced the old one.
  useLayoutEffect(() => {
    if (syncFocus === null || syncFocus.serial === handledFocus.current) return
    if (syncFocus.target !== 'heading') return
    handledFocus.current = syncFocus.serial
    heading.current?.focus()
  }, [syncFocus])

  /*
   * The plan ended (Story 2.13): the dialog closed without returning focus to the button that opened it, and focus
   * goes to this heading — the record replaces the panel's content under it. A notice the end area would take while
   * the area is not shown (the record is) comes here too.
   */
  useLayoutEffect(() => {
    if (endFocus === null || endFocus.serial === handledEndFocus.current) return
    // Every request is seen once: a notice request the end area took is never replayed here later.
    handledEndFocus.current = endFocus.serial
    if (endFocus.target === 'notice' && !ended) return
    heading.current?.focus()
  }, [endFocus, ended])

  // The long-gap choice `새 계획 시작` starts the same end flow (Story 2.13).
  const newPlan: NewPlanChoice | undefined =
    bundle === null || ended
      ? undefined
      : { status: bundle.planEnd, flow: planEnd, leaving: reauthentication.pending }

  return (
    <section
      aria-busy={loading}
      aria-labelledby="plan-result-heading"
      className={styles.panel}
      onBlur={(event) => {
        const next = event.relatedTarget
        // Focus that moves to another element outside the panel is gone; focus that merely vanishes
        // (its element removed, or the window left) is remembered.
        if (next !== null && !event.currentTarget.contains(next)) lastFocused.current = null
      }}
      onFocus={(event) => {
        lastFocused.current = event.target
      }}
    >
      <h2 className={styles.heading} id="plan-result-heading" ref={heading} tabIndex={-1}>
        {copy.heading}
      </h2>

      <Notice measured={measured} view={view} />

      {result === null ? null : <p>{ended ? planEndContent.record.intro : copy.intro}</p>}
      {bundle === null ? null : <VersionChangeNote change={bundle.change} />}
      {result === null || end === null ? null : <PlanEndRecord end={end} result={result} />}
      {result === null || current === undefined ? null : (
        <section aria-labelledby="plan-result-current" className={styles.group}>
          <h3 id="plan-result-current">{copy.currentHeading}</h3>
          <div className={styles.lead}>
            <MetricCard metric={current} result={result} />
          </div>
        </section>
      )}
      {bundle === null ? null : <Trust bundle={bundle} newPlan={newPlan} sync={sync} />}
      {result === null ? null : (
        <section aria-labelledby="plan-result-metrics" className={styles.group}>
          <h3 id="plan-result-metrics">{copy.metricsHeading}</h3>
          <p className={styles.meta}>{copy.metricsIntro}</p>
          <CalculationEvidence evidence={evidence} result={result} />
          <AdjustmentHistory history={adjustments} result={result} />
          <ul className={styles.metrics}>
            {rest.map((metric) => (
              <li key={metric.metric}>
                <MetricCard metric={metric} result={result} />
              </li>
            ))}
          </ul>
        </section>
      )}

      {hasNextAction ? (
        <section aria-labelledby="plan-result-next" className={styles.group}>
          {/* The record's next action is the preparation of a new plan, explained only (Story 2.13). */}
          <h3 id="plan-result-next">
            {ended ? planEndContent.nextPlan.heading : copy.nextActionHeading}
          </h3>
          <p>{nextActionCopy(nextActionOf(view), measured)}</p>
          {view.kind === 'NO_PLAN' ? (
            <a className={styles.action} href={planConditionContent.path}>
              {planConditionContent.openConditions}
            </a>
          ) : null}
          <button
            aria-disabled={loading}
            className={styles.action}
            onClick={() => {
              if (!loading) reload()
            }}
            type="button"
          >
            {copy.reload}
          </button>
        </section>
      ) : null}

      {result === null ? null : <Conditions result={result} />}

      {bundle === null || ended ? null : (
        <PlanEndControl
          bundle={bundle}
          planEnd={planEnd}
          reauthentication={reauthentication}
          sync={sync}
        />
      )}
    </section>
  )
}
