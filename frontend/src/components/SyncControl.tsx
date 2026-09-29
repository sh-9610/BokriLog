import { useEffect, useRef, type FocusEvent, type ReactElement } from 'react'

import type { DashboardSync } from '../session/useDashboardSync'

import { copyFor, planResultContent } from '../content/planResult'
import { planSyncContent as copy } from '../content/planSync'
import { formatSeoulTime } from '../format/decimal'
import { inProgress, isInstant, type SyncStatus } from '../session/useDashboard'
import styles from './SyncControl.module.css'

/** The control's root. The screen's accessibility checks name it. */
export const SYNC_CONTROL_ID = 'plan-sync'

const STATE_ID = 'plan-sync-state'
const BUSY_ID = 'plan-sync-busy'
const COOLDOWN_ID = 'plan-sync-cooldown'
const REASON_ID = 'plan-sync-reason'

/** What the error summary says: a label, then its lines. */
interface Summary {
  readonly label: string
  readonly lines: readonly string[]
  readonly finishedAt: string | null
}

/**
 * The error summary, or `null` when there is nothing to report. What this screen judged itself comes first
 * (the admission was not handled, or the screen stopped following); otherwise the plan's latest job, when it
 * ended without a new official result — the server's codes, folded to safe copy. A refused admission has no
 * summary of its own once the server's reason line is shown: the same name is never shown twice.
 */
function summaryOf(status: SyncStatus, sync: DashboardSync): Summary | null {
  const { phase } = sync
  if (phase === 'unavailable' && status.availability === 'UNAVAILABLE') return null
  if (phase === 'cooldown' || phase === 'unavailable' || phase === 'error' || phase === 'stalled') {
    const local = copy.local[phase]
    return { label: local.label, lines: [local.text], finishedAt: null }
  }
  const latest = status.latest
  if (sync.running || latest === null || inProgress(latest) || latest.status === 'SUCCEEDED') {
    return null
  }
  const { summary } = copy
  return {
    label: copyFor(summary.statuses, latest.status, summary.unknownStatus),
    lines: [
      `${summary.causeLabel}: ${copyFor(summary.codes, latest.failureCode ?? '', summary.unknownCode)}`,
      summary.kept,
      ...(latest.stage === null
        ? []
        : [
            `${summary.stageLabel}: ${copyFor(summary.stages, latest.stage, summary.unknownStage)}`,
          ]),
      `${summary.triggerLabel}: ${copyFor(summary.triggers, latest.trigger, summary.unknownTrigger)}`,
    ],
    finishedAt: latest.finishedAt,
  }
}

/**
 * Why the button is blocked by a job in progress that this tab did not ask for, or `null`: one found in a read
 * and followed (another tab, a reload), or one still in progress that this screen no longer follows.
 */
function busyOf(status: SyncStatus, sync: DashboardSync): string | null {
  if (sync.running) return sync.followingElsewhere ? copy.inProgressElsewhere : null
  return status.availability === 'IN_PROGRESS' ? copy.inProgressUnfollowed : null
}

/**
 * The sync control (Story 2.11), right after the freshness axis it acts on.
 *
 * One primary `동기화` button, at least 44px, whose name stays the action. While a sync runs, `동기화 중` and a
 * progress marker appear beside it — static under reduced motion — and the button is `aria-disabled`: it keeps
 * its place and the keyboard focus, and a press does nothing. The same blocking holds while a job this tab did
 * not ask for is in progress, during the cool-down (counted from the server's relative seconds) and while the
 * server says a sync is unavailable; each has its reason line, which describes the button. The note on recent
 * deposits and withdrawals is always beside it.
 *
 * The error summary (`tabIndex=-1`) says why the last sync did not change the official result; a sync the user
 * pressed moves focus to it when it ends that way. After a long gap (more than 30 days since the last check)
 * there is no button: the explanation of the two ways to continue and their data ranges takes its place, and
 * says the last normal result stays read-only. It links nowhere — neither choice runs from this screen.
 *
 * When what held focus inside the control goes away (a summary that is no longer true), focus moves to the
 * button, or to the long-gap explanation, rather than falling to the document body.
 *
 * No live region of its own: the screen's single status region says what the sync did, once.
 */
export function SyncControl({
  status,
  sync,
}: {
  readonly status: SyncStatus
  readonly sync: DashboardSync
}): ReactElement {
  const summaryRef = useRef<HTMLDivElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  // A focus request made before this control was mounted is not replayed.
  const handled = useRef(sync.focus?.serial ?? 0)
  // The element inside the control that last had focus, until focus leaves the control for another element.
  const lastFocused = useRef<Element | null>(null)
  const { focus } = sync

  useEffect(() => {
    if (focus === null || focus.serial === handled.current) return
    if (focus.target !== 'summary') return
    handled.current = focus.serial
    summaryRef.current?.focus()
  }, [focus])

  // After every render: focus that was inside the control on something that went away is not dropped.
  useEffect(() => {
    const last = lastFocused.current
    const active = document.activeElement
    const lost = active === null || active === document.body || !active.isConnected
    if (last === null || last.isConnected || !lost) return
    lastFocused.current = null
    ;(buttonRef.current ?? summaryRef.current)?.focus()
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

  const longGap = status.availability === 'LONG_GAP' || sync.phase === 'longGap'
  if (longGap) {
    const gap = copy.longGap
    return (
      <div className={styles.sync} id={SYNC_CONTROL_ID} {...tracking}>
        <div className={styles.summary} ref={summaryRef} tabIndex={-1}>
          <p className={styles.label}>{gap.label}</p>
          <p>{gap.text}</p>
          <dl className={styles.choices}>
            <div>
              <dt>{gap.continueTitle}</dt>
              <dd>{gap.continueText}</dd>
            </div>
            <div>
              <dt>{gap.newPlanTitle}</dt>
              <dd>{gap.newPlanText}</dd>
            </div>
          </dl>
          <p>{gap.readOnly}</p>
          <p className={styles.help}>{gap.notHere}</p>
        </div>
      </div>
    )
  }

  const unavailable = status.availability === 'UNAVAILABLE'
  const cooling = sync.cooldownSeconds !== null && !sync.running
  const busy = busyOf(status, sync)
  const summary = summaryOf(status, sync)
  const described = [
    sync.running ? STATE_ID : null,
    busy === null ? null : BUSY_ID,
    cooling ? COOLDOWN_ID : null,
    unavailable ? REASON_ID : null,
  ].filter((id) => id !== null)
  return (
    <div className={styles.sync} id={SYNC_CONTROL_ID} {...tracking}>
      <button
        aria-describedby={described.length === 0 ? undefined : described.join(' ')}
        aria-disabled={!sync.canRequest}
        className={styles.button}
        onClick={() => {
          if (sync.canRequest) sync.request()
        }}
        ref={buttonRef}
        type="button"
      >
        {copy.action}
      </button>
      {sync.running ? (
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
      {summary === null ? null : (
        <div className={styles.summary} ref={summaryRef} tabIndex={-1}>
          <p className={styles.label}>{summary.label}</p>
          {summary.lines.map((line) => (
            <p key={line}>{line}</p>
          ))}
          {summary.finishedAt === null ? null : (
            <p className={styles.help}>
              {copy.summary.finishedLabel}:{' '}
              <time dateTime={isInstant(summary.finishedAt) ? summary.finishedAt : undefined}>
                {formatSeoulTime(summary.finishedAt, planResultContent.timeUnavailable)}
              </time>{' '}
              · {planResultContent.timeZone}
            </p>
          )}
        </div>
      )}
    </div>
  )
}
