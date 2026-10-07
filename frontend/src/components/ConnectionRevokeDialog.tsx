import { useRef, type ReactElement } from 'react'

import type { RevocationDialogView } from '../session/useBybitConnection'

import { revocationContent } from '../content/connection'
import styles from './ConfirmationDialog.module.css'
import { onBackdropMouseDown, useConfirmationDialog } from './useConfirmationDialog'

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
 * 취소가 문서 순서에서 먼저이고 기본 포커스를 가진다. 확정이 도는 동안 두 버튼은 `aria-disabled`이고 진행
 * 문장이 확인 버튼을 설명한다. 응답을 받지 못한 확정은 그렇다고 말하는 문장과 함께 대화상자를 열어 둔다 —
 * 다시 누르면 같은 키로 같은 요청을 보낸다.
 *
 * 메커니즘(기본 포커스·포커스 가둠·`Esc`·스크롤 잠금·호출점 복귀)은 `useConfirmationDialog`가 소유한다.
 * 호출점을 모르면 해제 버튼(`revocationContent.buttonId`)으로 돌려준다.
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
  useConfirmationDialog({
    surface,
    cancelButton,
    isPending: pending,
    onCancel,
    restoreFocus,
    fallbackTargetId: revocationContent.buttonId,
  })

  return (
    <div className={styles.backdrop} onMouseDown={onBackdropMouseDown}>
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
