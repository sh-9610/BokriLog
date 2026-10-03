import { useEffect, useRef, type FocusEvent, type ReactElement, type Ref } from 'react'

import type { VerificationJob, VerificationStatus } from '../session/useDashboard'
import type { DashboardSync } from '../session/useDashboardSync'

import { calculationEvidenceContent } from '../content/calculationEvidence'
import { copyFor } from '../content/planResult'
import { planVerificationContent as copy } from '../content/planVerification'
import { inLongGap, inProgress, purposeOf } from '../session/useDashboard'
import { Time } from './EvidenceParts'
import styles from './VerificationControl.module.css'
import { RangeList } from './VerificationRanges'

/** The control's root. The screen's accessibility checks name it. */
export const VERIFICATION_CONTROL_ID = 'plan-verification'

const STATE_ID = 'plan-verification-state'
const BUSY_ID = 'plan-verification-busy'
const COOLDOWN_ID = 'plan-verification-cooldown'
const REASON_ID = 'plan-verification-reason'

const SEPARATOR = calculationEvidenceContent.separators.item

/**
 * What this screen itself says about the last verification request, or `null`: only for a verification, only
 * once it is not running, and never the same name twice — the server's reason line or the not-useful notice
 * takes the place of a refusal that says the same.
 */
function localOf(
  status: VerificationStatus,
  sync: DashboardSync,
): { readonly label: string; readonly text: string } | null {
  if (sync.purpose !== 'VERIFY' || sync.running) return null
  const { phase } = sync
  if (phase === 'unavailable' && status.availability === 'UNAVAILABLE') return null
  if (phase === 'notUseful' && status.availability === 'NOT_USEFUL') return null
  switch (phase) {
    case 'cooldown':
    case 'unavailable':
    case 'error':
    case 'stalled':
    case 'notUseful':
    case 'stale':
    case 'busy':
    case 'refused':
      return copy.local[phase]
    default:
      return null
  }
}

/**
 * Why the button is blocked by a job in progress, or `null` when this tab's own verification runs: one this
 * screen follows, one it no longer follows — named by its own purpose (the block's latest job is the plan's latest
 * verification or catch-up; when that one has ended, the job in progress is a sync) — or an automatic sync
 * admission that has not answered yet.
 */
function busyOf(status: VerificationStatus, sync: DashboardSync): string | null {
  if (sync.running) {
    if (sync.purpose !== 'VERIFY') return copy.inProgressOther
    return sync.followingElsewhere ? copy.inProgressElsewhere : null
  }
  if (status.availability === 'IN_PROGRESS') {
    const { latest } = status
    const purpose = latest !== null && inProgress(latest) ? purposeOf(latest) : 'SYNC'
    return copy.inProgressUnfollowed[purpose]
  }
  // Only where a verification could otherwise be asked for: elsewhere another line already says why not.
  const askable = status.availability === 'AVAILABLE' || status.availability === 'COOLDOWN'
  return sync.admitting && askable ? copy.admitting : null
}

/** Why a verification is not offered now, as the server says it. */
function reasonOf(status: VerificationStatus): string | null {
  if (status.availability !== 'UNAVAILABLE') return null
  return `${copy.unavailableLabel}: ${copyFor(copy.reasons, status.reason ?? '', copy.unknownReason)}`
}

/** The counts of the comparison, in one line. */
function countsLine(job: VerificationJob): string | null {
  const { counts } = job
  if (counts === null) return null
  const names = copy.latest.counts
  return [
    `${names.new} ${copy.latest.countValue(counts.new)}`,
    `${names.same} ${copy.latest.countValue(counts.same)}`,
    `${names.missing} ${copy.latest.countValue(counts.missing)}`,
    `${names.superseded} ${copy.latest.countValue(counts.superseded)}`,
    `${names.conflict} ${copy.latest.countValue(counts.conflict)}`,
  ].join(` ${SEPARATOR} `)
}

/**
 * The latest precision verification: its state, why it did not change the official result (when it did not —
 * in the verification's own words, never the sync's), its ranges and the evidence it reused, what the comparison
 * found, and when it ended. Only a verification that published says it read its ranges again; one that did not
 * names them as the ranges it set out to check — out of range, it ended before any read, and a failure can stop
 * part way. One that did not publish is the control's summary: focus moves here
 * when a verification the user pressed ends that way (`tabIndex=-1`).
 */
function LatestJob({
  job,
  summaryRef,
}: {
  readonly job: VerificationJob
  readonly summaryRef: Ref<HTMLDivElement>
}): ReactElement {
  const published = job.status === 'SUCCEEDED'
  const latest = copy.latest
  const counts = countsLine(job)
  return (
    <div
      className={published ? styles.latest : styles.summary}
      ref={published ? undefined : summaryRef}
      tabIndex={published ? undefined : -1}
    >
      <p className={styles.label}>
        {latest.heading}: {copyFor(latest.statuses, job.status, latest.unknownStatus)}
      </p>
      {published ? null : (
        <>
          <p>
            {latest.causeLabel}: {copyFor(latest.codes, job.failureCode ?? '', latest.unknownCode)}
          </p>
          <p>{latest.kept}</p>
          {job.stage === null ? null : (
            <p>
              {latest.stageLabel}: {copyFor(latest.stages, job.stage, latest.unknownStage)}
            </p>
          )}
        </>
      )}
      <dl className={styles.facts}>
        {job.reason === null ? null : (
          <div>
            <dt>{latest.reasonLabel}</dt>
            <dd>{copyFor(copy.jobReasons, job.reason, copy.unknownJobReason)}</dd>
          </div>
        )}
        <div>
          <dt>{published ? latest.targetsLabel : latest.plannedTargetsLabel}</dt>
          <dd>
            <RangeList className={styles.ranges} empty={copy.none} ranges={job.targets} />
          </dd>
        </div>
        <div>
          <dt>{published ? latest.reusedLabel : latest.plannedReusedLabel}</dt>
          <dd>
            <RangeList className={styles.ranges} empty={copy.none} ranges={job.reused} />
          </dd>
        </div>
        {counts === null ? null : (
          <div>
            <dt>{latest.countsLabel}</dt>
            <dd>{counts}</dd>
          </div>
        )}
        {job.finishedAt === null ? null : (
          <div>
            <dt>{latest.finishedLabel}</dt>
            <dd>
              <Time value={job.finishedAt} />
            </dd>
          </div>
        )}
      </dl>
    </div>
  )
}

/**
 * The precision verification (Story 2.12), right after the sync control it complements.
 *
 * It says what a verification is for (only the ranges that need it are read again; the whole history is not),
 * what it costs (several reads, tens of seconds or more), and — from the server's plan — the range it would
 * read: why it is suggested when the server would record a suspected gap, where reading starts, the start of
 * the last 7 days before the last check, how many unexplained stored events it would look around, and where
 * reusing earlier evidence ends. One secondary `정밀 검증` button, at least 44px, whose name stays the action.
 * While a verification runs, `정밀 검증 중` and a progress marker appear beside it — static under reduced motion
 * — and the button is `aria-disabled`: it keeps its place and the keyboard focus, and a press does nothing. The
 * same blocking holds while another job is in progress, while an automatic sync admission has not answered yet,
 * during the cool-down and while the server says a verification is unavailable; each has its reason line, which
 * describes the button. When asking again cannot help — the same basis already ended in review — there is no
 * button: the notice that asking again will not change it takes its place (EXPERIENCE UJ-3).
 *
 * During a long gap a verification is not taken at all: instead of its purpose and cost, one short sentence
 * points to the catch-up by its button's name (or, when the catch-up cannot help either, says only that a
 * verification is not taken), beside any summary of this tab's own request.
 *
 * The latest precision verification's result follows; one that did not publish is the summary (`tabIndex=-1`)
 * that a verification the user pressed moves focus to when it ends that way. A job of another purpose — a
 * catch-up, or one this screen does not know — is not shown under that name. When what held focus inside the
 * control goes away, focus moves to the button, or to the notice, rather than falling to the document body.
 *
 * No live region of its own: the screen's single status region says what the verification did, once.
 */
export function VerificationControl({
  status,
  sync,
}: {
  readonly status: VerificationStatus
  readonly sync: DashboardSync
}): ReactElement {
  const summaryRef = useRef<HTMLDivElement>(null)
  const noticeRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  // A focus request made before this control was mounted is not replayed.
  const handled = useRef(sync.focus?.serial ?? 0)
  // The element inside the control that last had focus, until focus leaves the control for another element.
  const lastFocused = useRef<Element | null>(null)
  const { focus } = sync

  useEffect(() => {
    if (focus === null || focus.serial === handled.current) return
    if (focus.target !== 'verification') return
    handled.current = focus.serial
    ;(summaryRef.current ?? noticeRef.current ?? buttonRef.current)?.focus()
  }, [focus])

  // After every render: focus that was inside the control on something that went away is not dropped.
  useEffect(() => {
    const last = lastFocused.current
    const active = document.activeElement
    const lost = active === null || active === document.body || !active.isConnected
    if (last === null || last.isConnected || !lost) return
    lastFocused.current = null
    ;(buttonRef.current ?? noticeRef.current ?? summaryRef.current)?.focus()
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

  const local = localOf(status, sync)
  const localSummary =
    local === null ? null : (
      <div className={styles.summary} ref={summaryRef} tabIndex={-1}>
        <p className={styles.label}>{local.label}</p>
        <p>{local.text}</p>
      </div>
    )

  if (inLongGap(status) && !(sync.running && sync.purpose === 'VERIFY')) {
    return (
      <div className={styles.verification} id={VERIFICATION_CONTROL_ID} {...tracking}>
        <div ref={noticeRef} tabIndex={-1}>
          <p>
            {status.availability === 'NOT_USEFUL' ? copy.longGap.held : copy.longGap.continuation}
          </p>
        </div>
        {localSummary}
      </div>
    )
  }

  const verifying = sync.running && sync.purpose === 'VERIFY'
  const notUseful = status.availability === 'NOT_USEFUL'
  const cooling = sync.verificationCooldownSeconds !== null && !sync.running
  const busy = busyOf(status, sync)
  const reason = reasonOf(status)
  const latest = status.latest
  // Only a precision verification is shown under that name: a catch-up or a purpose this screen does not know
  // is not folded into it.
  const shownLatest =
    latest?.purpose === 'VERIFY' && !inProgress(latest) && !verifying ? latest : null
  const preview = notUseful ? null : status.preview
  const described = [
    verifying ? STATE_ID : null,
    busy === null ? null : BUSY_ID,
    cooling ? COOLDOWN_ID : null,
    reason === null ? null : REASON_ID,
  ].filter((id) => id !== null)
  return (
    <div className={styles.verification} id={VERIFICATION_CONTROL_ID} {...tracking}>
      <p>{copy.purpose}</p>
      <p className={styles.help}>{copy.cost}</p>
      {preview === null ? null : (
        <>
          <p className={styles.label}>{copy.preview.label}</p>
          {status.suggestedReason === 'GAP_SUSPECTED' ? <p>{copy.preview.gapSuspected}</p> : null}
          <dl className={styles.facts}>
            <div>
              <dt>{copy.preview.rangeStart}</dt>
              <dd>
                <Time value={preview.rangeStart} />
                <span className={styles.help}>{copy.preview.rangeStartHelp}</span>
              </dd>
            </div>
            <div>
              <dt>{copy.preview.unconfirmedFrom}</dt>
              <dd>
                <Time value={preview.unconfirmedFrom} />
                <span className={styles.help}>{copy.preview.unconfirmedHelp}</span>
              </dd>
            </div>
            <div>
              <dt>{copy.preview.anomalies}</dt>
              <dd>
                {copy.preview.anomalyCount(preview.anomalyCount)}
                <span className={styles.help}>{copy.preview.anomalyHelp}</span>
              </dd>
            </div>
            <div>
              <dt>{copy.preview.reusedUntil}</dt>
              <dd>
                {preview.reusedUntil === null ? (
                  copy.preview.noReuse
                ) : (
                  <>
                    <Time value={preview.reusedUntil} />
                    <span className={styles.help}>{copy.preview.reusedHelp}</span>
                  </>
                )}
              </dd>
            </div>
          </dl>
        </>
      )}
      {notUseful ? (
        <div className={styles.summary} ref={noticeRef} tabIndex={-1}>
          <p className={styles.label}>{copy.notUseful.label}</p>
          <p>{copy.notUseful.text}</p>
        </div>
      ) : (
        <button
          aria-describedby={described.length === 0 ? undefined : described.join(' ')}
          aria-disabled={!sync.canVerify}
          className={styles.button}
          onClick={() => {
            if (sync.canVerify) sync.requestVerification('PRECISION')
          }}
          ref={buttonRef}
          type="button"
        >
          {copy.action}
        </button>
      )}
      {verifying ? (
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
          {copy.cooldown(sync.verificationCooldownSeconds)}
        </p>
      ) : null}
      {reason === null ? null : (
        <p className={styles.state} id={REASON_ID}>
          {reason}
        </p>
      )}
      {localSummary}
      {shownLatest === null || (local !== null && shownLatest.status !== 'SUCCEEDED') ? null : (
        <LatestJob job={shownLatest} summaryRef={summaryRef} />
      )}
    </div>
  )
}
