import { useCallback, useEffect, useRef, useState } from 'react'

import type { DashboardSync } from './useDashboardSync'

import { planEndContent as copy, type PlanEndReason, type PlanEndRefusal } from '../content/planEnd'
import { copyFor } from '../content/planResult'
import {
  cancelPlanEnd,
  confirmPlanEnd,
  preparePlanEnd,
  type ConfirmPlanEndAnswer,
  type PlanEndRefusalAnswer,
  type PlanEndRequest,
} from './planEnd'
import {
  bundleOf,
  terminalOf,
  type Accepted,
  type Dashboard,
  type DashboardView,
} from './useDashboard'

/**
 * What the end flow says after a request that did not open or did not finish the dialog: the user must confirm
 * who they are first (`reauthRequired`), the dialog was cancelled (`cancelled`), it was closed after a confirmation
 * that got no answer so whether the plan ended is not known (`uncertain`), the end was confirmed but its record did
 * not come on screen (`endedNotShown`), or the server refused (`refused`, with the unavailability's reason when it
 * named one). Kept in memory only — never in the address or browser storage: the flow does not leave the document.
 */
export type PlanEndNotice =
  | { readonly kind: 'reauthRequired' }
  | { readonly kind: 'cancelled' }
  | { readonly kind: 'uncertain' }
  | { readonly kind: 'endedNotShown' }
  | { readonly kind: 'refused'; readonly refusal: PlanEndRefusal; readonly reason: string | null }

/**
 * A request to move focus that the end flow made: the panel heading after the plan ended (the record replaces the
 * panel's content, and the button that opened the dialog goes with it), or the end area's notice after a request
 * the server refused or that needs the user to confirm who they are.
 */
export interface PlanEndFocus {
  readonly target: 'heading' | 'notice'
  readonly serial: number
}

/** What the confirmation dialog shows: the reason it was opened for and the period of the plan it ends. */
export interface PlanEndDialogView {
  readonly reason: PlanEndReason
  readonly startAt: string
  readonly endAt: string
  /** The confirmation is in flight: the dialog is blocked. */
  readonly confirming: boolean
  /** The last confirmation got no answer: pressing it again sends the same request with the same key. */
  readonly uncertain: boolean
}

export interface PlanEnd {
  /** The prepare request is in flight. */
  readonly preparing: boolean
  /** The open confirmation dialog, or `null`. */
  readonly dialog: PlanEndDialogView | null
  /** Preparing, or a dialog is open: neither end button may start the flow again. */
  readonly busy: boolean
  readonly notice: PlanEndNotice | null
  readonly focus: PlanEndFocus | null
  /**
   * Starts the flow for the shown active plan: `USER_REQUEST` from `계획 중도 종료`, `LONG_GAP_NEW_PLAN` from the
   * long-gap choice `새 계획 시작`. Ignored while the flow is busy, even twice in the same tick.
   */
  readonly request: (reason: PlanEndReason) => void
  /** Confirms the open dialog. Ignored while a confirmation is in flight, even twice in the same tick. */
  readonly confirm: () => void
  /** Closes the dialog without ending anything. Ignored while a confirmation is in flight. */
  readonly cancel: () => void
}

/** One prepared end: what was asked, the server's confirmation text, and the key of its confirmation. */
interface Armed {
  readonly request: PlanEndRequest
  readonly confirmation: string
  /** Made once when the dialog opens: every confirmation of this dialog, a retry too, carries it. */
  readonly key: string
  readonly startAt: string
  readonly endAt: string
}

/** The screen waits for the record of a plan it just ended; it asks again at most this many times. */
const ENDED_READ_TRIES = 3

/** The identity of the plan a view shows, active, ended or completed, or `null` when it shows none. */
function shownPlan(view: DashboardView): string | null {
  const bundle = bundleOf(view)
  return bundle === null ? null : `${bundle.result.planId}/${terminalOf(bundle) ?? 'active'}`
}

/** The 409 codes and what each says. A code this screen does not know is said in general words. */
const CONFLICTS: Readonly<Record<string, PlanEndRefusal>> = {
  REFERENCE_STALE: 'stale',
  PLAN_END_UNAVAILABLE: 'unavailable',
  LONG_GAP: 'longGap',
  NOT_LONG_GAP: 'notLongGap',
  IDEMPOTENCY_CONFLICT: 'conflict',
}

/** What a refused request says, and whether the dashboard's own read must decide what stays on screen. */
function refusalOf(answer: PlanEndRefusalAnswer): {
  readonly notice: PlanEndNotice
  readonly read: 'reload' | 'refresh' | null
} {
  const refused = (refusal: PlanEndRefusal, reason: string | null = null): PlanEndNotice => ({
    kind: 'refused',
    refusal,
    reason,
  })
  switch (answer.status) {
    case 401:
      // The session is gone: the dashboard's own read says so.
      return { notice: refused('denied'), read: 'reload' }
    case 403:
      if (answer.code === 'REAUTHENTICATION_REQUIRED') {
        return { notice: { kind: 'reauthRequired' }, read: null }
      }
      if (answer.code === 'PLAN_END_NOT_CONFIRMED') {
        return { notice: refused('notConfirmed'), read: 'refresh' }
      }
      // The account (or the CSRF/Origin check) refused: if the account is refused, that read's view wins.
      return { notice: refused('denied'), read: 'reload' }
    case 404:
      return { notice: refused('notFound'), read: 'refresh' }
    case 409: {
      const refusal =
        answer.code !== null && Object.hasOwn(CONFLICTS, answer.code)
          ? (CONFLICTS[answer.code] ?? 'error')
          : 'error'
      return {
        notice: refused(refusal, refusal === 'unavailable' ? answer.reason : null),
        read: 'refresh',
      }
    }
    default:
      return { notice: refused('error'), read: 'refresh' }
  }
}

/**
 * The label and text of a refusal: an unavailability that names its reason says it in the end area's words (an
 * unknown one in safe words), then that nothing changed.
 */
export function refusalCopy(
  refusal: PlanEndRefusal,
  reason: string | null,
): { readonly label: string; readonly text: string } {
  const words = copy.refusals[refusal]
  if (refusal !== 'unavailable' || reason === null) return words
  return {
    label: words.label,
    text: `${copyFor(copy.area.reasons, reason, copy.unknownRefusalReason)} ${copy.kept}`,
  }
}

/** The label and text a notice shows in the end area, or `null` for one only the status region says. */
export function noticeCopy(
  notice: PlanEndNotice | null,
): { readonly label: string; readonly text: string } | null {
  switch (notice?.kind) {
    case 'refused':
      return refusalCopy(notice.refusal, notice.reason)
    case 'uncertain':
      return copy.uncertain
    case 'endedNotShown':
      return copy.endedNotShown
    default:
      return null
  }
}

/**
 * Whether the view still shows the plan a dialog was opened for, as it was: the same plan's active bundle at the
 * same pointer and calculation. A view that says nothing new (a session re-check, a read that did not answer) is
 * not a change.
 */
function stillShown(view: DashboardView, request: PlanEndRequest): boolean {
  if (view.kind === 'checking' || view.kind === 'lastGood' || view.kind === 'retryable') return true
  return (
    view.kind === 'READY' &&
    view.result.planId === request.planId &&
    view.result.pointerVersion === request.pointerVersion &&
    view.result.calculationId === request.calculationId
  )
}

/**
 * Whether a job is in progress or being admitted, as the two end buttons see it: a sync, precision verification
 * or catch-up this screen runs or follows, an automatic sync admission that has not answered, or a job the server
 * shows in progress. Both buttons share this gate (the catch-up's) — an end asked for while a job may publish a new
 * version would only be refused as stale.
 */
export function jobInProgress(sync: DashboardSync, bundle: Accepted): boolean {
  return (
    sync.running ||
    sync.admitting ||
    bundle.sync.availability === 'IN_PROGRESS' ||
    bundle.verification.availability === 'IN_PROGRESS'
  )
}

/**
 * The explicit early end of the shown active plan (Story 2.13), beside the dashboard it ends.
 *
 * Three steps, in order. `request` asks the server to prepare (`POST /api/dashboard/plan-end`) with the reason and
 * the shown bundle's reference; the server judges eligibility, issues a one-time `plan-end` grant bound to the
 * session and returns its canonical confirmation text — only then does the confirmation dialog open, with a new
 * idempotency key made once for it. `confirm` sends that text back (`…/confirm`) with the key; the server consumes
 * the grant and ends the plan in one transaction. `cancel` closes the dialog and releases the flow (`…/cancel`)
 * without waiting for the answer; a following prepare is sent after it, so a late cancel never clears a new flow.
 *
 * Nothing here ends anything by itself, and nothing is retried automatically. A prepare refused because the session
 * has no recent reauthentication says so (`reauthRequired`): the user confirms who they are and presses again. A
 * confirmation that gets no answer (no response, the time limit, a 5xx, an unreadable 200) keeps the dialog open;
 * pressing again sends the same request with the same key, so an end the server already made is returned again.
 * Any refusal closes the dialog, says what happened in the end area (with focus there) and reads the dashboard
 * again — quietly, or loudly when the session or account was refused, so the dashboard's own read decides. A
 * cancel after a confirmation without an answer does not say "cancelled": whether the plan ended is not known, so
 * it says so and reads out loud. A read that shows another state than the one the dialog was opened for closes it.
 *
 * On success the dialog closes without returning focus to the button that opened it (that button goes with the
 * active plan); focus moves to the panel heading, and the dashboard is read again quietly until the record of the
 * ended plan is shown (at most `ENDED_READ_TRIES` reads, then a notice and one read out loud) — the record's own
 * status sentence is the one announcement. A second press in the same tick is dropped (a ref, not React state), and
 * nothing that throws leaves the flow busy. Every request, the cancel too, has the dashboard's 10-second limit. The
 * confirmation text, the key and the grant never leave memory.
 */
export function usePlanEnd(dashboard: Dashboard, csrfToken: string | null): PlanEnd {
  const { view } = dashboard
  const [preparing, setPreparing] = useState(false)
  const [armed, setArmed] = useState<Armed | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [notice, setNotice] = useState<PlanEndNotice | null>(null)
  const [focus, setFocus] = useState<PlanEndFocus | null>(null)
  const [lastPlan, setLastPlan] = useState<string | null>(() => shownPlan(view))

  const live = useRef(true)
  const inFlight = useRef(false)
  const armedRef = useRef<Armed | null>(null)
  const uncertainRef = useRef(false)
  const viewRef = useRef(view)
  const dashboardRef = useRef(dashboard)
  const csrfRef = useRef(csrfToken)
  // A cancel that may not have reached the server yet: the next prepare is sent after it.
  const cancelling = useRef<Promise<void> | null>(null)
  // The plan just ended, while its record is not shown yet.
  const awaiting = useRef<{ readonly planId: string; tries: number } | null>(null)

  /*
   * Adjusting state to a changed prop during render (as `useDashboard` does): a notice belongs to the plan it was
   * said about. When another plan is shown — or this plan's record replaces it — the notice goes. A view without a
   * plan (a session re-check, a read to retry with nothing kept) says nothing about it and changes nothing.
   */
  const plan = shownPlan(view)
  if (plan !== null && plan !== lastPlan) {
    setLastPlan(plan)
    if (lastPlan !== null) setNotice(null)
  }

  // The latest values for the callbacks below. First, so every later effect of this commit sees them. Passive, like
  // every effect of this hook: the effects and callbacks that read these values are passive or run on later tasks.
  useEffect(() => {
    viewRef.current = view
    dashboardRef.current = dashboard
    csrfRef.current = csrfToken
  })

  // An answer that arrives after unmount changes nothing (the request itself is left to finish). Passive: it guards
  // answers that come on later tasks, not anything a commit shows.
  useEffect(() => {
    live.current = true
    return () => {
      live.current = false
    }
  }, [])

  const focusOn = useCallback((target: PlanEndFocus['target']): void => {
    setFocus((current) => ({ target, serial: (current?.serial ?? 0) + 1 }))
  }, [])

  const close = useCallback((): void => {
    armedRef.current = null
    uncertainRef.current = false
    setArmed(null)
    setUncertain(false)
  }, [])

  /*
   * Releases the flow and the grant the prepare made, without waiting: a failed cancel leaves nothing for the user
   * to do (the flow expires by itself and the next prepare drops it). Only the next prepare waits for it.
   */
  const release = useCallback((): void => {
    const released = cancelPlanEnd(csrfRef.current)
    cancelling.current = released
    void released.finally(() => {
      if (cancelling.current === released) cancelling.current = null
    })
  }, [])

  /*
   * After an end: until the record of the ended plan is shown, each answer that still shows it active — or a read
   * that did not answer — asks again, quietly (a quiet read is ignored while another read is in flight, and that
   * read's answer may predate the end). After `ENDED_READ_TRIES` of them the screen says the end was confirmed but
   * its record is not on screen yet, and reads once out loud. Another state (no plan, a refusal, another plan) says
   * what it is itself.
   *
   * Passive: it starts a read, which is not something the commit shows, and the read's answer comes on a later task
   * anyway.
   */
  useEffect(() => {
    const wait = awaiting.current
    if (wait === null || view.kind === 'checking') return
    const bundle = bundleOf(view)
    const ours = bundle !== null && bundle.result.planId === wait.planId
    // Whichever way the plan ended — this end, or its scheduled end that came first (Story 2.14) — it is shown now.
    if (ours && terminalOf(bundle) !== null) {
      awaiting.current = null
      return
    }
    if (!ours && view.kind !== 'retryable' && view.kind !== 'lastGood') {
      awaiting.current = null
      return
    }
    if (wait.tries < ENDED_READ_TRIES) {
      wait.tries += 1
      dashboardRef.current.refresh()
      return
    }
    awaiting.current = null
    setNotice({ kind: 'endedNotShown' })
    dashboardRef.current.reload()
  }, [view])

  /*
   * The dialog belongs to the plan it was opened for, as it was shown. When a read shows another state — the plan
   * ended elsewhere, another plan, no plan, a refusal, or the same plan at another version — the dialog closes and
   * releases its flow, unless a confirmation is in flight (its answer decides). Focus goes to the end area's notice
   * when the same plan only moved on (asking again with the new result is what is left), to the panel heading
   * otherwise (the button that opened the dialog may have gone).
   *
   * Passive: it releases the flow (a request) and changes this hook's state; from a layout effect that state would
   * re-render synchronously before paint. The focus it asks for moves in the end area's and the panel's layout
   * effects, in the commit that closes the dialog.
   */
  useEffect(() => {
    const current = armedRef.current
    if (current === null || inFlight.current || stillShown(view, current.request)) return
    close()
    release()
    if (view.kind === 'READY' && view.result.planId === current.request.planId) {
      setNotice({ kind: 'refused', refusal: 'stale', reason: null })
      focusOn('notice')
      return
    }
    setNotice(null)
    focusOn('heading')
  }, [view, close, release, focusOn])

  const refuse = useCallback(
    (answer: PlanEndRefusalAnswer | null): void => {
      const { notice: said, read } =
        answer === null
          ? { notice: { kind: 'refused', refusal: 'error', reason: null } as const, read: null }
          : refusalOf(answer)
      setNotice(said)
      focusOn('notice')
      if (read === 'reload') dashboardRef.current.reload()
      if (read === 'refresh') dashboardRef.current.refresh()
    },
    [focusOn],
  )

  const request = useCallback(
    (reason: PlanEndReason): void => {
      if (inFlight.current || armedRef.current !== null) return
      // The reference of the active plan shown right now: the selection the user made by pressing.
      const bundle = bundleOf(viewRef.current)
      const token = csrfRef.current
      // Only an active plan may be ended early: not one that ended or completed (Story 2.14).
      if (bundle === null || terminalOf(bundle) !== null || token === null) {
        refuse(null)
        return
      }
      inFlight.current = true
      setPreparing(true)
      setNotice(null)
      const { result } = bundle
      const asked: PlanEndRequest = {
        reason,
        planId: result.planId,
        pointerVersion: result.pointerVersion,
        calculationId: result.calculationId,
      }
      // With no cancel to wait for, it goes now: a tick later, a second press would see another order.
      const previous = cancelling.current
      const prepared =
        previous === null
          ? preparePlanEnd(asked, token)
          : previous.then(() => preparePlanEnd(asked, token))
      void (async () => {
        try {
          const answer = await prepared
          if (!live.current) return
          if (answer.kind !== 'armed') {
            refuse(answer.kind === 'noAnswer' ? null : answer)
            return
          }
          const opened: Armed = {
            request: asked,
            confirmation: answer.confirmation,
            key: crypto.randomUUID(),
            startAt: result.conditions.startAt,
            endAt: result.conditions.endAt,
          }
          armedRef.current = opened
          setArmed(opened)
        } catch {
          // Anything that throws (making the key, say) opens nothing: the flow is free again, and says so.
          if (live.current) refuse(null)
        } finally {
          inFlight.current = false
          if (live.current) setPreparing(false)
        }
      })()
    },
    [refuse],
  )

  const confirm = useCallback((): void => {
    const current = armedRef.current
    if (inFlight.current || current === null) return
    inFlight.current = true
    setConfirming(true)
    void (async () => {
      let answer: ConfirmPlanEndAnswer
      try {
        answer = await confirmPlanEnd(
          current.request,
          current.confirmation,
          current.key,
          csrfRef.current,
        )
      } catch {
        // Whether it was sent is not known: the same as no answer, never a blocked dialog.
        answer = { kind: 'noAnswer' }
      } finally {
        inFlight.current = false
      }
      if (!live.current) return
      setConfirming(false)
      if (answer.kind === 'noAnswer') {
        // Whether it ended is not known: the dialog stays, and the next press sends the same key.
        uncertainRef.current = true
        setUncertain(true)
        return
      }
      close()
      if (answer.kind === 'ended') {
        setNotice(null)
        focusOn('heading')
        awaiting.current = { planId: current.request.planId, tries: 0 }
        dashboardRef.current.refresh()
        return
      }
      refuse(answer)
    })()
  }, [close, focusOn, refuse])

  const cancel = useCallback((): void => {
    if (inFlight.current || armedRef.current === null) return
    const unsure = uncertainRef.current
    close()
    release()
    if (unsure) {
      // After a confirmation that got no answer the plan may have ended: never "cancelled" — the dashboard's own
      // read decides, out loud.
      setNotice({ kind: 'uncertain' })
      dashboardRef.current.reload()
      return
    }
    setNotice({ kind: 'cancelled' })
  }, [close, release])

  return {
    preparing,
    dialog:
      armed === null
        ? null
        : {
            reason: armed.request.reason,
            startAt: armed.startAt,
            endAt: armed.endAt,
            confirming,
            uncertain,
          },
    busy: preparing || armed !== null,
    notice,
    focus,
    request,
    confirm,
    cancel,
  }
}

/**
 * The end flow's part of the screen's single status region, or `null` when it has nothing to say: that it is
 * preparing, that the user must confirm who they are, that the dialog was cancelled, or why the server refused.
 * While the dialog is open the background (and this region with it) is inert: the dialog says its own progress. An
 * end itself is said by the dashboard's part, once, when the record comes on screen.
 */
export function planEndAnnouncement(planEnd: PlanEnd): string | null {
  if (planEnd.preparing) return copy.status.preparing
  if (planEnd.dialog !== null || planEnd.notice === null) return null
  const { notice } = planEnd
  switch (notice.kind) {
    case 'reauthRequired':
      return copy.status.reauthRequired
    case 'cancelled':
      return copy.status.cancelled
    case 'uncertain':
    case 'endedNotShown':
    case 'refused': {
      const words = noticeCopy(notice)
      return words === null ? null : `${words.label}. ${words.text}`
    }
  }
}
