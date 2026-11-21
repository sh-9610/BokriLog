import { useLayoutEffect, useRef, type FocusEvent, type ReactElement, type Ref } from 'react'

import type {
  PlanEndStatus,
  SyncJob,
  SyncStatus,
  VerificationStatus,
} from '../session/useDashboard'
import type { DashboardSync } from '../session/useDashboardSync'
import type { PlanEnd } from '../session/usePlanEnd'

import { calculationEvidenceContent } from '../content/calculationEvidence'
import { planEndContent } from '../content/planEnd'
import { copyFor, planResultContent } from '../content/planResult'
import { planSyncContent as copy, DASHBOARD_SYNC_RETURN_PATH } from '../content/planSync'
import { planVerificationContent } from '../content/planVerification'
import { inLongGap, inProgress, purposeOf } from '../session/useDashboard'
import { Instant, Time } from './EvidenceParts'
import styles from './SyncControl.module.css'

/** The control's root. The screen's accessibility checks name it. */
export const SYNC_CONTROL_ID = 'plan-sync'

const STATE_ID = 'plan-sync-state'
const BUSY_ID = 'plan-sync-busy'
const COOLDOWN_ID = 'plan-sync-cooldown'
const REASON_ID = 'plan-sync-reason'
const CATCH_UP_STATE_ID = 'plan-catch-up-state'
const CATCH_UP_BUSY_ID = 'plan-catch-up-busy'
const CATCH_UP_COOLDOWN_ID = 'plan-catch-up-cooldown'
const CATCH_UP_REASON_ID = 'plan-catch-up-reason'
const NEW_PLAN_REASON_ID = 'plan-new-plan-reason'
const NEW_PLAN_PREPARING_ID = 'plan-new-plan-preparing'
const NEW_PLAN_DIALOG_ID = 'plan-new-plan-dialog-open'
const NEW_PLAN_LEAVING_ID = 'plan-new-plan-leaving'

const catchUp = planVerificationContent.catchUp
const separators = calculationEvidenceContent.separators

/**
 * The long-gap choice `새 계획 시작` (Story 2.13): whether the server would take it for the shown plan (its `planEnd`
 * block — `NEW_PLAN_CHOICE` during a long gap), the end flow it starts, and whether a reauthentication this screen
 * started is leaving for its provider. Without it (a test of another part) the button is shown blocked, for a reason
 * not known.
 */
export interface NewPlanChoice {
  readonly status: PlanEndStatus
  readonly flow: PlanEnd
  readonly leaving: boolean
}

/** What the error summary says: a label, then its lines. */
interface Summary {
  readonly label: string
  readonly lines: readonly string[]
  readonly finishedAt: string | null
}

/**
 * What this screen itself says about the last catch-up request (Story 2.12), or `null`: never the same name
 * twice — the server's reason line, or the notice that the catch-up cannot help, takes the place of a refusal
 * that says the same.
 */
function catchUpLocal(verification: VerificationStatus, sync: DashboardSync): Summary | null {
  if (sync.purpose !== 'CATCH_UP' || sync.running) return null
  const { phase } = sync
  if (phase === 'unavailable' && verification.availability === 'UNAVAILABLE') return null
  if (phase === 'notUseful' && verification.availability === 'NOT_USEFUL') return null
  switch (phase) {
    case 'cooldown':
    case 'unavailable':
    case 'error':
    case 'stalled':
    case 'notLongGap':
    case 'notUseful':
    case 'stale':
    case 'busy':
    case 'refused': {
      const local = catchUp.local[phase]
      return { label: local.label, lines: [local.text], finishedAt: null }
    }
    default:
      return null
  }
}

/** The words of one purpose's summary: its statuses, causes and stages, each with its safe fallback. */
interface SummaryWords {
  readonly statuses: Readonly<Record<string, string>>
  readonly unknownStatus: string
  readonly kept: string
  readonly causeLabel: string
  readonly codes: Readonly<Record<string, string>>
  readonly unknownCode: string
  readonly stageLabel: string
  readonly stages: Readonly<Record<string, string>>
  readonly unknownStage: string
}

/**
 * The summary of a job that ended without a new official result: the server's codes, folded to safe copy, in
 * the words of the job's purpose — a catch-up never says "sync again".
 */
function jobSummary(job: SyncJob, words: SummaryWords): Summary {
  const { summary } = copy
  return {
    label: copyFor(words.statuses, job.status, words.unknownStatus),
    lines: [
      `${words.causeLabel}: ${copyFor(words.codes, job.failureCode ?? '', words.unknownCode)}`,
      words.kept,
      ...(job.stage === null
        ? []
        : [`${words.stageLabel}: ${copyFor(words.stages, job.stage, words.unknownStage)}`]),
      `${summary.triggerLabel}: ${copyFor(summary.triggers, job.trigger, summary.unknownTrigger)}`,
    ],
    finishedAt: job.finishedAt,
  }
}

/** Whether the job ended without publishing: the ones a summary explains. */
function unpublished(job: SyncJob | null): job is SyncJob {
  return job !== null && !inProgress(job) && job.status !== 'SUCCEEDED'
}

/**
 * The catch-up's summary: this screen's own outcome, else the plan's latest catch-up when it ended without a new
 * official result. It does not depend on which of the control's two forms is shown — a catch-up that ended and
 * left the long gap for another state (an unavailability, say) keeps its summary.
 */
function catchUpSummaryOf(
  status: SyncStatus,
  verification: VerificationStatus,
  sync: DashboardSync,
): Summary | null {
  const local = catchUpLocal(verification, sync)
  if (local !== null || sync.running) return local
  const latest = [verification.latest, status.latest].find(
    (job) => job !== null && purposeOf(job) === 'CATCH_UP',
  )
  if (latest === undefined || !unpublished(latest)) return null
  return jobSummary(latest, catchUp.summary)
}

/**
 * The error summary, or `null` when there is nothing to report. What this screen judged itself comes first
 * (the admission was not handled, or the screen stopped following); otherwise the plan's latest job, when it
 * ended without a new official result — the server's codes, folded to safe copy. A refused admission has no
 * summary of its own once the server's reason line is shown: the same name is never shown twice. A catch-up's
 * outcome and ending are said here too, in its own words, whichever form the control has; a precision
 * verification's outcomes are the verification control's.
 */
function summaryOf(
  status: SyncStatus,
  verification: VerificationStatus,
  sync: DashboardSync,
): Summary | null {
  const { phase } = sync
  if (sync.purpose === 'CATCH_UP') return catchUpSummaryOf(status, verification, sync)
  if (sync.purpose === 'SYNC') {
    if (phase === 'unavailable' && status.availability === 'UNAVAILABLE') return null
    if (
      phase === 'cooldown' ||
      phase === 'unavailable' ||
      phase === 'error' ||
      phase === 'stalled'
    ) {
      const local = copy.local[phase]
      return { label: local.label, lines: [local.text], finishedAt: null }
    }
  }
  const latest = status.latest
  if (sync.running || !unpublished(latest)) return null
  if (purposeOf(latest) === 'CATCH_UP') return catchUpSummaryOf(status, verification, sync)
  if (purposeOf(latest) !== 'SYNC') return null
  return jobSummary(latest, copy.summary)
}

/**
 * The purpose of the job in progress that this screen no longer follows, as the two blocks show it: the newest
 * latest job that has not ended (the sync block's latest is the plan's latest job of any purpose). A job of a
 * purpose this screen does not know — or none whole enough to say — is named with the syncs.
 */
function unfollowedPurpose(
  status: SyncStatus,
  verification: VerificationStatus,
): 'SYNC' | 'VERIFY' | 'CATCH_UP' {
  const job = [status.latest, verification.latest].find(
    (candidate) => candidate !== null && inProgress(candidate),
  )
  return job === undefined || job === null ? 'SYNC' : purposeOf(job)
}

/**
 * Why the sync button is blocked by a job in progress that is not this tab's own sync, or `null`: a
 * verification or catch-up in progress (Story 2.12), one found in a read and followed (another tab, a reload),
 * or one still in progress that this screen no longer follows — named by its own purpose.
 */
function busyOf(
  status: SyncStatus,
  verification: VerificationStatus,
  sync: DashboardSync,
): string | null {
  if (sync.running) {
    if (sync.purpose !== 'SYNC') return copy.inProgressOther
    return sync.followingElsewhere ? copy.inProgressElsewhere : null
  }
  if (status.availability !== 'IN_PROGRESS') return null
  return copy.inProgressUnfollowed[unfollowedPurpose(status, verification)]
}

/**
 * Why the catch-up button is blocked by a job in progress, or `null` when this tab's own catch-up runs: one this
 * screen follows, one it no longer follows (named by its own purpose), or an automatic sync admission that has not
 * answered yet.
 */
function catchUpBusyOf(
  status: SyncStatus,
  verification: VerificationStatus,
  sync: DashboardSync,
): string | null {
  if (sync.running) {
    if (sync.purpose !== 'CATCH_UP') return catchUp.inProgressOther
    return sync.followingElsewhere ? catchUp.inProgressElsewhere : null
  }
  if (verification.availability === 'IN_PROGRESS') {
    return catchUp.inProgressUnfollowed[unfollowedPurpose(status, verification)]
  }
  // Only where the catch-up could otherwise be asked for: elsewhere another line already says why not.
  return sync.admitting && verification.availability === 'LONG_GAP' ? catchUp.admitting : null
}

/**
 * Why the catch-up cannot be asked for, beyond a job in progress, a cool-down and a basis it cannot help, or
 * `null`: the server says it is unavailable, or the screen does not know whether it can be (the block could not
 * be read, or it has not caught up with a long gap the sync admission just met).
 */
function catchUpReasonOf(verification: VerificationStatus, sync: DashboardSync): string | null {
  if (sync.running || sync.verificationCooldownSeconds !== null) return null
  if (verification.availability === 'UNAVAILABLE') {
    return `${catchUp.unavailableLabel}: ${copyFor(
      planVerificationContent.reasons,
      verification.reason ?? '',
      catchUp.unknownReason,
    )}`
  }
  if (verification.availability === 'IN_PROGRESS' || verification.availability === 'NOT_USEFUL') {
    return null
  }
  if (verification.availability !== 'LONG_GAP' || verification.catchUpFrom === null) {
    return catchUp.unknownReason
  }
  return null
}

/**
 * Why `새 계획 시작` cannot be asked for, beyond the flow, a reauthentication or a job in progress, or `null` when it can:
 * the server does not take the long-gap choice now. An unavailability names its reason in the end area's words; a plan
 * the server no longer sees in a long gap (its end block says the plan may be ended early) points to that end; a block
 * that could not be read is said in safe words.
 */
function newPlanReasonOf(newPlan: NewPlanChoice | undefined): string | null {
  const status = newPlan?.status ?? null
  if (status?.availability === 'NEW_PLAN_CHOICE') return null
  const reason =
    status?.availability === 'AVAILABLE'
      ? planEndContent.notLongGapReason
      : status?.availability === 'UNAVAILABLE' && status.reason !== null
        ? copyFor(planEndContent.area.reasons, status.reason, planEndContent.area.unknownReason)
        : planEndContent.area.unknownReason
  return `${copy.longGap.newPlanUnavailableLabel}: ${reason}`
}

function SummaryBlock({
  summary,
  summaryRef,
}: {
  readonly summary: Summary
  readonly summaryRef: Ref<HTMLDivElement>
}): ReactElement {
  return (
    <div className={styles.summary} ref={summaryRef} tabIndex={-1}>
      <p className={styles.label}>{summary.label}</p>
      {summary.lines.map((line) => (
        <p key={line}>{line}</p>
      ))}
      {summary.finishedAt === null ? null : (
        <p className={styles.help}>
          {copy.summary.finishedLabel}: <Time value={summary.finishedAt} />
        </p>
      )}
    </div>
  )
}

/** Whether the server says no sync can run because the user revoked the connection (Story 2.16). */
function revoked(status: SyncStatus): boolean {
  return status.availability === 'UNAVAILABLE' && status.reason === 'CONNECTION_REVOKED'
}

/**
 * The sync control (Story 2.11), right after the freshness axis it acts on.
 *
 * One primary `동기화` button, at least 44px, whose name stays the action. While a sync runs, `동기화 중` and a
 * progress marker appear beside it — static under reduced motion — and the button is `aria-disabled`: it keeps
 * its place and the keyboard focus, and a press does nothing. The same blocking holds while a job this tab did
 * not ask for is in progress (a precision verification included), during the cool-down (counted from the
 * server's relative seconds) and while the server says a sync is unavailable; each has its reason line, which
 * describes the button. The note on recent deposits and withdrawals is always beside it.
 *
 * The error summary (`tabIndex=-1`) says why the last sync did not change the official result; a sync the user
 * pressed moves focus to it when it ends that way. After a long gap (more than 30 days since the last check)
 * there is no sync button: the explanation of the two ways to continue and their data ranges takes its place,
 * and says the last normal result stays read-only. Since Story 2.12 one of the two runs from here — the
 * `기존 계획 계속` button (secondary, at least 44px), with the range it collects (from the server's catch-up start
 * until now) and its cost; since Story 2.13 the other does too — the `새 계획 시작` button, which opens the plan's end
 * flow with the long-gap reason (its reauthentication guidance and notices are the end area's, where focus goes when
 * a press ends that way). Both share one busy gate. The catch-up runs, cools down, is refused and
 * ends inside this same explanation, which stays while it runs, with its own summary for an ending without a
 * new official result — a summary that stays when the control leaves the long-gap form. When a job that ended
 * in review holds the same basis, asking for the catch-up again cannot help either: a notice takes the button's
 * place (`tabIndex=-1`), like the precision verification's.
 *
 * When what held focus inside the control goes away (a summary that is no longer true), focus moves to the
 * button — the sync button, the catch-up button or the new-plan button — or to that notice, rather than falling to the document
 * body; unless the same change asked for the dashboard heading, which the panel then focuses.
 *
 * No live region of its own: the screen's single status region says what the job did, once.
 */
export function SyncControl({
  status,
  verification,
  sync,
  newPlan,
  adExpiresAt = null,
}: {
  readonly status: SyncStatus
  readonly verification: VerificationStatus
  readonly sync: DashboardSync
  readonly newPlan?: NewPlanChoice
  readonly adExpiresAt?: number | null
}): ReactElement {
  const summaryRef = useRef<HTMLDivElement>(null)
  const explanationRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement | HTMLAnchorElement>(null)
  const catchUpRef = useRef<HTMLButtonElement>(null)
  const newPlanRef = useRef<HTMLButtonElement>(null)
  const noticeRef = useRef<HTMLDivElement>(null)
  // A focus request made before this control was mounted is not replayed.
  const handled = useRef(sync.focus?.serial ?? 0)
  // The last focus request this control has seen, of any target.
  const seen = useRef(sync.focus?.serial ?? 0)
  // The element inside the control that last had focus, until focus leaves the control for another element.
  const lastFocused = useRef<Element | null>(null)
  const { focus, phase } = sync

  /*
   * Both focus moves are layout effects: focus follows in the commit that changes the content, so it never rests
   * on the document body for a frame. As passive effects they ran after the commit on the scheduler's own task,
   * and under load that came after code that had already seen the new content. The order below still holds —
   * a child's layout effects run before its parent's, as passive ones did — so the panel's heading focus comes
   * after this control's recovery.
   */
  useLayoutEffect(() => {
    if (focus === null || focus.serial === handled.current) return
    if (focus.target !== 'summary') return
    handled.current = focus.serial
    // A long gap met by a pressed request is explained by the explanation itself.
    const target = phase === 'longGap' ? explanationRef.current : summaryRef.current
    ;(target ?? summaryRef.current ?? noticeRef.current ?? explanationRef.current)?.focus()
  }, [focus, phase])

  /*
   * After every render: focus that was inside the control on something that went away is not dropped. Except
   * when the same render brought a new request to focus the dashboard heading (a pressed catch-up that ended with
   * a new bundle, which also ends the long-gap form and its button): the panel moves focus there, after this
   * control's effects, so recovering here first would send focus to the sync button and then away again.
   */
  useLayoutEffect(() => {
    const fresh = focus !== null && focus.serial !== seen.current
    if (focus !== null) seen.current = focus.serial
    const last = lastFocused.current
    const active = document.activeElement
    const lost = active === null || active === document.body || !active.isConnected
    if (last === null || last.isConnected || !lost) return
    lastFocused.current = null
    if (fresh && focus.target === 'heading') return
    ;(
      buttonRef.current ??
      catchUpRef.current ??
      newPlanRef.current ??
      noticeRef.current ??
      summaryRef.current ??
      explanationRef.current
    )?.focus()
  })

  const tracking = {
    onBlur: (event: FocusEvent<HTMLDivElement>) => {
      const next = event.relatedTarget
      if (next !== null && !event.currentTarget.contains(next)) lastFocused.current = null
    },
    onFocus: (event: FocusEvent<HTMLDivElement>) => {
      lastFocused.current = event.target
    },
  }

  /*
   * A revoked connection (Story 2.16): no sync control at all — neither the button nor the long-gap choices — only the
   * reason, as text. Nothing can run on that plan any more; a disabled button would promise an action that never comes.
   */
  if (revoked(status) && !sync.running) {
    /*
     * The one control that stays: after a long gap the plan can only end through the long-gap choice's `새 계획
     * 시작` (Story 2.13 refuses an early end then, so the gap is recorded), and the end area points here. A revoked
     * plan must still be endable, so the button stays — ending reads nothing from Bybit.
     */
    const choice = newPlan?.status.availability === 'NEW_PLAN_CHOICE' ? newPlan : undefined
    const preparing = choice?.flow.preparing ?? false
    const dialogOpen = (choice?.flow.dialog ?? null) !== null
    const leaving = choice?.leaving ?? false
    const canStartNewPlan = choice !== undefined && !choice.flow.busy && !leaving
    const described = [
      preparing ? NEW_PLAN_PREPARING_ID : null,
      dialogOpen ? NEW_PLAN_DIALOG_ID : null,
      leaving ? NEW_PLAN_LEAVING_ID : null,
    ].filter((id) => id !== null)
    return (
      <div className={styles.sync} id={SYNC_CONTROL_ID} {...tracking}>
        <div ref={noticeRef} tabIndex={-1}>
          <p className={styles.state}>{copy.reasons.CONNECTION_REVOKED}</p>
        </div>
        {choice === undefined ? null : (
          <>
            <p>{copy.longGap.newPlanText}</p>
            <button
              aria-describedby={described.length === 0 ? undefined : described.join(' ')}
              aria-disabled={!canStartNewPlan}
              className={styles.secondary}
              id={planEndContent.newPlanActionId}
              onClick={() => {
                if (canStartNewPlan) choice.flow.request('LONG_GAP_NEW_PLAN')
              }}
              ref={newPlanRef}
              type="button"
            >
              {copy.longGap.newPlanAction}
            </button>
            {preparing ? (
              <p className={styles.help} id={NEW_PLAN_PREPARING_ID}>
                {planEndContent.area.preparing}
              </p>
            ) : null}
            {dialogOpen ? (
              <p className={styles.help} id={NEW_PLAN_DIALOG_ID}>
                {planEndContent.area.dialogOpen}
              </p>
            ) : null}
            {leaving ? (
              <p className={styles.help} id={NEW_PLAN_LEAVING_ID}>
                {planEndContent.area.leaving}
              </p>
            ) : null}
          </>
        )}
      </div>
    )
  }

  const catchingUp = sync.running && sync.purpose === 'CATCH_UP'
  // The long gap as the sync block names it, as a pressed request met it, or as the verification block says
  // it (it keeps the catch-up start while another state — an unavailability, say — is the one it names).
  const longGap =
    status.availability === 'LONG_GAP' ||
    phase === 'longGap' ||
    catchingUp ||
    inLongGap(verification)
  if (longGap) {
    const gap = copy.longGap
    // The same basis already ended in review: the catch-up cannot help either (Story 2.12, a long gap with a
    // persistent review of this basis). The notice takes the button's place; it is never shown while one runs.
    const held = verification.availability === 'NOT_USEFUL' && !catchingUp
    /*
     * `새 계획 시작` (Story 2.13) shares the catch-up's busy gate — any job in progress or admission in flight — and is
     * also blocked while the end flow prepares or its dialog is open, while a reauthentication is leaving, and while
     * the server does not take the long-gap choice. A cool-down of the catch-up does not block it: ending collects
     * nothing.
     */
    const jobBusy =
      sync.running ||
      sync.admitting ||
      status.availability === 'IN_PROGRESS' ||
      verification.availability === 'IN_PROGRESS'
    // One busy line for both buttons: the catch-up's cause when it has one, else a job in progress in general (a
    // catch-up of this tab says itself in its progress line).
    const busy =
      catchUpBusyOf(status, verification, sync) ??
      (jobBusy && !catchingUp ? catchUp.inProgress : null)
    const cooling = sync.verificationCooldownSeconds !== null && !sync.running && !held
    const reason = catchUpReasonOf(verification, sync)
    const summary = catchUpSummaryOf(status, verification, sync)
    const described = [
      catchingUp ? CATCH_UP_STATE_ID : null,
      busy === null ? null : CATCH_UP_BUSY_ID,
      cooling ? CATCH_UP_COOLDOWN_ID : null,
      reason === null ? null : CATCH_UP_REASON_ID,
    ].filter((id) => id !== null)
    const newPlanReason = newPlanReasonOf(newPlan)
    const preparing = newPlan?.flow.preparing ?? false
    const dialogOpen = (newPlan?.flow.dialog ?? null) !== null
    const leaving = newPlan?.leaving ?? false
    const canStartNewPlan =
      newPlan !== undefined && newPlanReason === null && !jobBusy && !newPlan.flow.busy && !leaving
    // Every cause that blocks it is said, and describes it.
    const newPlanDescribed = [
      preparing ? NEW_PLAN_PREPARING_ID : null,
      dialogOpen ? NEW_PLAN_DIALOG_ID : null,
      leaving ? NEW_PLAN_LEAVING_ID : null,
      busy !== null ? CATCH_UP_BUSY_ID : catchingUp ? CATCH_UP_STATE_ID : null,
      newPlanReason === null ? null : NEW_PLAN_REASON_ID,
    ].filter((id) => id !== null)
    return (
      <div className={styles.sync} id={SYNC_CONTROL_ID} {...tracking}>
        <div className={styles.summary} ref={explanationRef} tabIndex={-1}>
          <p className={styles.label}>{gap.label}</p>
          <p>{gap.text}</p>
          <dl className={styles.choices}>
            <div>
              <dt>{gap.continueTitle}</dt>
              <dd>{gap.continueText}</dd>
              {verification.catchUpFrom === null ? null : (
                <dd>
                  {gap.continueRangeLabel}: <Instant value={verification.catchUpFrom} />{' '}
                  {separators.range} {gap.continueRangeUntil} {separators.item}{' '}
                  {planResultContent.timeZone}
                </dd>
              )}
              <dd>{gap.continueCost}</dd>
            </div>
            <div>
              <dt>{gap.newPlanTitle}</dt>
              <dd>{gap.newPlanText}</dd>
            </div>
          </dl>
          <p>{gap.readOnly}</p>
        </div>
        {/*
          The two ways to continue, as equals: `기존 계획 계속` first (it keeps the plan), then `새 계획 시작`, which opens
          the end flow — reauthentication, the confirmation dialog, then the end. Its notices are the end area's.
        */}
        {held ? (
          <div className={styles.summary} ref={noticeRef} tabIndex={-1}>
            <p className={styles.label}>{catchUp.notUseful.label}</p>
            <p>{catchUp.notUseful.text}</p>
          </div>
        ) : (
          <button
            aria-describedby={described.length === 0 ? undefined : described.join(' ')}
            aria-disabled={!sync.canCatchUp}
            className={styles.secondary}
            onClick={() => {
              if (sync.canCatchUp) sync.requestVerification('CATCH_UP')
            }}
            ref={catchUpRef}
            type="button"
          >
            {gap.continueAction}
          </button>
        )}
        {catchingUp ? (
          <div className={styles.progress}>
            <p className={styles.state} id={CATCH_UP_STATE_ID}>
              <span aria-hidden="true" className={styles.marker} />
              <span>{catchUp.running}</span>
            </p>
            <p className={styles.help}>{catchUp.runningHelp}</p>
          </div>
        ) : null}
        {busy === null ? null : (
          <p className={styles.help} id={CATCH_UP_BUSY_ID}>
            {busy}
          </p>
        )}
        {cooling ? (
          <p className={styles.state} id={CATCH_UP_COOLDOWN_ID}>
            {catchUp.cooldown(sync.verificationCooldownSeconds)}
          </p>
        ) : null}
        {reason === null ? null : (
          <p className={styles.state} id={CATCH_UP_REASON_ID}>
            {reason}
          </p>
        )}
        <button
          aria-describedby={newPlanDescribed.length === 0 ? undefined : newPlanDescribed.join(' ')}
          aria-disabled={!canStartNewPlan}
          className={styles.secondary}
          id={planEndContent.newPlanActionId}
          onClick={() => {
            if (canStartNewPlan) newPlan.flow.request('LONG_GAP_NEW_PLAN')
          }}
          ref={newPlanRef}
          type="button"
        >
          {gap.newPlanAction}
        </button>
        {preparing ? (
          <p className={styles.help} id={NEW_PLAN_PREPARING_ID}>
            {planEndContent.area.preparing}
          </p>
        ) : null}
        {dialogOpen ? (
          <p className={styles.help} id={NEW_PLAN_DIALOG_ID}>
            {planEndContent.area.dialogOpen}
          </p>
        ) : null}
        {leaving ? (
          <p className={styles.help} id={NEW_PLAN_LEAVING_ID}>
            {planEndContent.area.leaving}
          </p>
        ) : null}
        {newPlanReason === null ? null : (
          <p className={styles.state} id={NEW_PLAN_REASON_ID}>
            {newPlanReason}
          </p>
        )}
        {summary === null ? null : <SummaryBlock summary={summary} summaryRef={summaryRef} />}
      </div>
    )
  }

  const running = sync.running && sync.purpose === 'SYNC'
  const unavailable = status.availability === 'UNAVAILABLE'
  const cooling = sync.cooldownSeconds !== null && !sync.running
  const busy = busyOf(status, verification, sync)
  const summary = summaryOf(status, verification, sync)
  const described = [
    running ? STATE_ID : null,
    busy === null ? null : BUSY_ID,
    cooling ? COOLDOWN_ID : null,
    unavailable ? REASON_ID : null,
  ].filter((id) => id !== null)
  return (
    <div className={styles.sync} id={SYNC_CONTROL_ID} {...tracking}>
      {adExpiresAt !== null && sync.canRequest ? (
        <a
          aria-describedby={described.length === 0 ? undefined : described.join(' ')}
          className={styles.button}
          href={DASHBOARD_SYNC_RETURN_PATH}
          onClickCapture={(event) => {
            const eligibleClick =
              event.button === 0 &&
              !event.ctrlKey &&
              !event.metaKey &&
              !event.shiftKey &&
              !event.altKey
            const fresh = performance.now() < adExpiresAt
            const admitted = eligibleClick && sync.request(fresh)
            if (!admitted || !fresh) {
              event.preventDefault()
              event.stopPropagation()
            }
          }}
          ref={(element) => {
            buttonRef.current = element
          }}
        >
          {copy.action}
        </a>
      ) : (
        <button
          aria-describedby={described.length === 0 ? undefined : described.join(' ')}
          aria-disabled={!sync.canRequest}
          className={styles.button}
          onClick={() => {
            if (sync.canRequest) sync.request()
          }}
          ref={(element) => {
            buttonRef.current = element
          }}
          type="button"
        >
          {copy.action}
        </button>
      )}
      {running ? (
        <div className={styles.progress}>
          <p className={styles.state} id={STATE_ID}>
            <span aria-hidden="true" className={styles.marker} />
            <span>{copy.running}</span>
          </p>
          <p className={styles.help}>{copy.runningHelp}</p>
        </div>
      ) : null}
      {busy === null ? null : (
        <p className={styles.help} id={BUSY_ID}>
          {busy}
        </p>
      )}
      {cooling ? (
        <p className={styles.state} id={COOLDOWN_ID}>
          {copy.cooldown(sync.cooldownSeconds)}
        </p>
      ) : null}
      {unavailable ? (
        <p className={styles.state} id={REASON_ID}>
          {copy.unavailableLabel}: {copyFor(copy.reasons, status.reason ?? '', copy.unknownReason)}
        </p>
      ) : null}
      <p className={styles.help}>{copy.recentEvents}</p>
      {summary === null ? null : <SummaryBlock summary={summary} summaryRef={summaryRef} />}
    </div>
  )
}
