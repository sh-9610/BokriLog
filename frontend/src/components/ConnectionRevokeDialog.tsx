import { useEffect, useLayoutEffect, useRef, type ReactElement } from 'react'

import type { RevocationDialogView } from '../session/useBybitConnection'

import { revocationContent } from '../content/connection'
import styles from './ConnectionRevokeDialog.module.css'

const copy = revocationContent.dialog

interface ConnectionRevokeDialogProps {
  readonly dialog: RevocationDialogView
  /**
   * Whether closing returns focus to the button that opened the dialog. False once the connection was revoked: that
   * button is gone, and the screen moves focus to its status sentence itself.
   */
  readonly restoreFocus: boolean
  readonly onCancel: () => void
  readonly onConfirm: () => void
}

/**
 * The confirmation dialog of a Bybit connection revocation (Story 2.16), in the `PlanEndDialog` idiom (DESIGN.md
 * `confirmation-dialog`). The description says, each on its own line: the stored credential is deleted at once and
 * this cannot be undone; the queued and running jobs are cancelled; no new sync can run afterwards and the values stop
 * updating; the plans, adjustment history, calculation evidence and completion results stay read-only; BokriLog cannot
 * revoke the API Key on Bybit and the user can do that there; reconnecting is not offered yet.
 *
 * Cancel comes first in the document and has the default focus. Focus is trapped inside; `Esc` cancels unless a
 * confirmation is in flight. While confirming, both buttons are `aria-disabled` and a progress line describes the
 * confirm button. A confirmation that got no answer keeps the dialog open with a line that says so — pressing again
 * sends the same request with the same key. The screen makes the background `inert`; the return of focus to the
 * opener is the one passive cleanup (`DEVELOPMENT.md`, `inert` 배경으로 돌려주는 초점은 예외다).
 */
export function ConnectionRevokeDialog({
  dialog,
  onCancel,
  onConfirm,
  restoreFocus,
}: ConnectionRevokeDialogProps): ReactElement {
  const surface = useRef<HTMLDivElement>(null)
  const cancelButton = useRef<HTMLButtonElement>(null)
  const pending = dialog.confirming

  const latest = useRef({ onCancel, pending, restoreFocus })
  useLayoutEffect(() => {
    latest.current = { onCancel, pending, restoreFocus }
  })

  const opener = useRef<Element | null>(null)

  // A layout effect: the default focus and the key handler are in place in the commit that shows the dialog.
  useLayoutEffect(() => {
    // Recorded once: `StrictMode` runs this effect again after focus already moved to cancel.
    opener.current ??= document.activeElement

    // The default focus is cancel: one `Enter` must not delete the credential.
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

    document.addEventListener('keydown', onKeyDown, true)

    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
      document.body.style.overflow = previousOverflow
    }
  }, [])

  /*
   * Back to the opener, when the screen asks for it and focus has nowhere else to be. A passive cleanup: the opener sits
   * under the screen's `inert` background until the closing commit lifts it (the `PlanEndDialog` rule).
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
          : document.getElementById(revocationContent.buttonId)
      known?.focus()
    },
    [],
  )

  return (
    <div
      className={styles.backdrop}
      onMouseDown={(event) => {
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
          {copy.title}
        </h2>

        <div className={styles.description} id={copy.descriptionId}>
          <p className={styles.destructive}>{copy.credentials}</p>
          <p className={styles.body}>{copy.jobs}</p>
          <p className={styles.body}>{copy.future}</p>
          <p className={styles.body}>{copy.kept}</p>
          <p className={styles.body}>{revocationContent.remoteKey}</p>
          <p className={styles.body}>{copy.reconnect}</p>
        </div>

        {/*
          The dialog's one status region. The screen's region sits under the `inert` background while the dialog is open,
          so a change here — the confirmation in flight, or no answer — is said here, once; the region is always
          rendered so a reader registers it before its content changes.
        */}
        <div aria-live="polite" role="status">
          {pending ? (
            <p className={styles.state} id={copy.progressId}>
              <span aria-hidden="true" className={styles.marker} />
              <span>{copy.confirming}</span>
            </p>
          ) : null}
          {dialog.uncertain && !pending ? (
            <p className={styles.notice} id={copy.uncertainId}>
              {copy.uncertain}
            </p>
          ) : null}
        </div>

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
              pending ? copy.progressId : dialog.uncertain ? copy.uncertainId : undefined
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
