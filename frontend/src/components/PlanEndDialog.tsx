import { useEffect, useLayoutEffect, useRef, type ReactElement } from 'react'

import type { PlanEndDialogView } from '../session/usePlanEnd'

import { planEndContent } from '../content/planEnd'
import { TimeRange } from './EvidenceParts'
import styles from './PlanEndDialog.module.css'

const copy = planEndContent.dialog

/** The line that says the dialog got no answer. The confirm button points to it. */
const UNCERTAIN_ID = 'plan-end-dialog-uncertain'

interface PlanEndDialogProps {
  readonly dialog: PlanEndDialogView
  /**
   * Whether closing returns focus to the button that opened the dialog. False while a confirmation is in flight:
   * whatever it ends in — the plan ended (focus goes to the panel heading) or a refusal (focus goes to the end
   * area's notice) — the screen moves focus itself. Only a cancel returns it.
   */
  readonly restoreFocus: boolean
  readonly onCancel: () => void
  readonly onConfirm: () => void
}

/**
 * The confirmation dialog of an early plan end (Story 2.13), in the `UnlinkDialog` idiom (DESIGN.md
 * `confirmation-dialog`): the title names the target and the result; the description says, in order, the plan's
 * period, that the end cannot be undone, that the server decides the actual end time when it is confirmed, that the
 * last normal value and its measurement time are not turned into a valuation at the end time, what stays read-only,
 * that the plan is no longer synced and a new plan starts separately from a new current value and time, and — for
 * the long-gap choice — that the gap is not collected and the completion comparison cannot be calculated. It never
 * calls the end a success, a failure or a completion.
 *
 * Cancel comes first in the document and has the default focus. Focus is trapped inside; `Esc` cancels unless a
 * confirmation is in flight (it never pretends to stop a server request). While confirming, both buttons are
 * `aria-disabled` and a progress line describes the confirm button; the dialog keeps its place. A confirmation that
 * got no answer keeps the dialog open with a line that says so — pressing again sends the same request.
 *
 * Not a `<dialog>` element, for the reasons `UnlinkDialog` gives; the keyboard handler is on `document`, in the
 * capture phase. Closing returns focus to the button that opened it, or — when the dialog was mounted again and
 * that button is no longer known — to the button of its reason. The screen makes the background `inert` (the dialog is its sibling); this dialog only locks the
 * document's scrolling. No status region of its own: the screen's single one is behind the inert background while
 * the dialog is open, and the progress is said here instead, as static text the confirm button points to.
 */
export function PlanEndDialog({
  dialog,
  onCancel,
  onConfirm,
  restoreFocus,
}: PlanEndDialogProps): ReactElement {
  const surface = useRef<HTMLDivElement>(null)
  const cancelButton = useRef<HTMLButtonElement>(null)
  const pending = dialog.confirming

  /*
   * The current callbacks and state, carried in a box the effect fills: the document handler and its cleanup are
   * attached once (on open) and removed once (on close), so a key pressed in between is never lost.
   */
  const reason = dialog.reason
  const latest = useRef({ onCancel, pending, restoreFocus, reason })
  useLayoutEffect(() => {
    latest.current = { onCancel, pending, restoreFocus, reason }
  })

  // The element that opened the dialog: recorded in the commit that shows it, given back after it closes (below).
  const opener = useRef<Element | null>(null)

  // A layout effect: the default focus and the key handler are in place in the commit that shows the dialog, not on
  // a later task that a keypress or a reader of the new dialog can come before.
  useLayoutEffect(() => {
    /*
     * Recorded once. `StrictMode` in a development build cleans this effect up and runs it again, and by then the
     * line below has moved focus to cancel: recording again would make the opener the dialog's own button, gone
     * after the close, and focus would fall to the body.
     */
    opener.current ??= document.activeElement

    // The default focus is cancel: one `Enter` must not end the plan.
    cancelButton.current?.focus()

    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.preventDefault()
        if (!latest.current.pending) latest.current.onCancel()
        return
      }
      if (event.key !== 'Tab') return
      // Focus trap: the dialog's controls in document order, both ends joined. Focus outside is pulled in.
      const root = surface.current
      if (root === null) return
      const focusable = root.querySelectorAll<HTMLElement>('button')
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (first === undefined || last === undefined) return
      const active = document.activeElement
      if (!(active instanceof HTMLElement) || !root.contains(active)) {
        event.preventDefault()
        first.focus()
        return
      }
      if (event.shiftKey && active === first) {
        event.preventDefault()
        last.focus()
        return
      }
      if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    }

    // The capture phase: answered before any handler of the background.
    document.addEventListener('keydown', onKeyDown, true)

    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
      document.body.style.overflow = previousOverflow
    }
  }, [])

  /*
   * Back to the button that opened it, when the screen asks for it and focus has nowhere else to be. When that button
   * is no longer known — the dialog was mounted again (a session re-check unmounts it), so the captured element is
   * the document body or one that left — back to the button of the reason it was opened for: the end area's, or the
   * long-gap choice's `새 계획 시작`. When the same commit already moved focus elsewhere (a read that showed another
   * state closes the dialog and sends focus to the end area's notice or the panel heading), that move stands.
   *
   * Only this return is a passive effect's cleanup. The button sits under the screen's `inert` background while the
   * dialog is open, and can take focus only once the closing commit lifts that attribute. A layout effect's cleanup
   * runs in that commit's mutation phase with the dialog's deletion, and React handles a parent's deletions before
   * its other children's updates — so it ran while the button was still inert, the browser ignored the `focus()`,
   * and focus fell to the body (CI run 37143229430, `e2e/plan-end.spec.ts`; jsdom has no `inert`, so only Playwright
   * sees it). A passive cleanup runs after the commit, and when a discrete input (`Esc`, cancel) closes the dialog,
   * React flushes it at the end of that commit, so it is not late either. The rule is in `DEVELOPMENT.md` (`inert`
   * 배경으로 돌려주는 초점은 예외다), with `UnlinkDialog` as its first case.
   */
  useEffect(
    () => () => {
      if (!latest.current.restoreFocus) return
      const active = document.activeElement
      const lost = active === null || active === document.body || !active.isConnected
      if (!lost) return
      const target = opener.current
      const known =
        target instanceof HTMLElement && target !== document.body && target.isConnected
          ? target
          : document.getElementById(
              latest.current.reason === 'LONG_GAP_NEW_PLAN'
                ? planEndContent.newPlanActionId
                : planEndContent.area.actionId,
            )
      known?.focus()
    },
    [],
  )

  return (
    // The backdrop: it covers the screen and takes no pointer action. Clicking outside does not close the dialog.
    <div
      className={styles.backdrop}
      onMouseDown={(event) => {
        // Pressing a place that is not a control does not move focus to the document body.
        if (!(event.target instanceof HTMLElement) || event.target.closest('button') === null) {
          event.preventDefault()
        }
      }}
    >
      <div
        aria-describedby={copy.descriptionId}
        aria-labelledby={copy.titleId}
        aria-modal="true"
        className={styles.dialog}
        ref={surface}
        role="dialog"
      >
        <h2 className={styles.title} id={copy.titleId}>
          {copy.titles[dialog.reason]}
        </h2>

        <div className={styles.description} id={copy.descriptionId}>
          <p className={styles.body}>
            {copy.periodLabel}: <TimeRange end={dialog.endAt} start={dialog.startAt} />
          </p>
          <p className={styles.destructive}>{copy.irreversible}</p>
          <p className={styles.body}>{copy.endTime}</p>
          <p className={styles.body}>{copy.lastNormal}</p>
          <p className={styles.body}>{copy.kept}</p>
          <p className={styles.body}>{copy.after}</p>
          {dialog.reason === 'LONG_GAP_NEW_PLAN' ? (
            <p className={styles.body}>{copy.longGap}</p>
          ) : null}
          <p className={styles.body}>{copy.distinct}</p>
        </div>

        {pending ? (
          <p className={styles.state} id={copy.progressId}>
            <span aria-hidden="true" className={styles.marker} />
            <span>{copy.confirming}</span>
          </p>
        ) : null}
        {dialog.uncertain && !pending ? (
          <p className={styles.notice} id={UNCERTAIN_ID}>
            {copy.uncertain}
          </p>
        ) : null}

        {/* Cancel first: the document order is the tab order, and the first control reached can be undone. */}
        <div className={styles.actions}>
          <button
            aria-disabled={pending}
            className={styles.cancelButton}
            onClick={() => {
              if (!pending) onCancel()
            }}
            ref={cancelButton}
            type="button"
          >
            {copy.cancel}
          </button>
          <button
            aria-describedby={
              pending ? copy.progressId : dialog.uncertain ? UNCERTAIN_ID : undefined
            }
            aria-disabled={pending}
            className={styles.confirmButton}
            onClick={() => {
              if (!pending) onConfirm()
            }}
            type="button"
          >
            {copy.confirm}
          </button>
        </div>
      </div>
    </div>
  )
}
