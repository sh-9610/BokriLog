import { useRef, useState, type ReactElement } from 'react'

import type { AccountDeletionDialogView } from '../session/useAccountDeletion'

import {
  accountDeletionContent,
  accountDeletionRefusalMessage,
  normalizedPhrase,
} from '../content/accountDeletion'
import styles from './ConfirmationDialog.module.css'
import { onBackdropMouseDown, useConfirmationDialog } from './useConfirmationDialog'

const copy = accountDeletionContent.dialog

interface AccountDeletionDialogProps {
  readonly dialog: AccountDeletionDialogView
  readonly onCancel: () => void
  readonly onConfirm: (typed: string) => void
}

/**
 * 계정 삭제의 확인 대화상자(Story 5.1, DESIGN.md `confirmation-dialog`).
 *
 * 비가역 결과와 삭제하는 것·남기는 것을 먼저 말하고, 서버가 정한 고정 문구(`계정을 삭제합니다`)를 <b>그대로 다시 입력</b>하게 한다
 * — 서비스는 이름·이메일을 저장하지 않으므로 "계정 표시명 재입력" 대신 이 문구다(accepted deviation). 입력이 문구와 같아질 때까지
 * (NFC·양끝 공백 무시) 실행 버튼은 `aria-disabled`이고, 그래도 눌리면 서버가 허가를 소비하지 않고 거절한다 — 최종 판정은 서버의
 * 확인 hash와 대조다. 서버의 문구 불일치 거절은 이 대화상자 안에서 말하고 대화상자를 연 채 다시 입력하게 한다.
 *
 * 취소가 문서 순서에서 먼저이고 기본 포커스를 가진다. 메커니즘(기본 포커스·포커스 가둠·`Esc`·스크롤 잠금·호출점 복귀)은
 * `useConfirmationDialog`가 소유한다. 호출점을 모르면 `계정 삭제 요청` 버튼으로 돌려준다. 확정이 성공하면 화면이 같은 문서를 다시
 * 요청하므로 돌려줄 자리를 따로 정하지 않는다.
 */
export function AccountDeletionDialog({
  dialog,
  onCancel,
  onConfirm,
}: AccountDeletionDialogProps): ReactElement {
  const surface = useRef<HTMLDivElement>(null)
  const cancelButton = useRef<HTMLButtonElement>(null)
  const [typed, setTyped] = useState('')
  const confirming = dialog.confirming
  useConfirmationDialog({
    surface,
    cancelButton,
    isPending: confirming,
    onCancel,
    restoreFocus: true,
    fallbackTargetId: accountDeletionContent.startId,
  })
  // 서버 대조(`ActionGrant.normalize`)와 같은 정의다 — NFC 뒤 Java `strip()`의 공백만 지운다(NBSP 등은 지우지 않는다).
  const matches = normalizedPhrase(typed) === normalizedPhrase(dialog.confirmation)
  /** 서버가 문구 불일치로 거절했다. 입력 칸이 그 문장을 가리킨다. */
  const mismatched = dialog.code === 'CONFIRMATION_MISMATCH' && !confirming
  const blocked = confirming || !matches
  const describedBy = confirming
    ? copy.progressId
    : dialog.code !== null
      ? copy.noticeId
      : dialog.uncertain
        ? copy.uncertainId
        : undefined

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
          <p className={styles.destructive}>{copy.effect}</p>
          <p className={styles.destructive}>{copy.removed}</p>
          <p className={styles.body}>{copy.keptIntro}</p>
          <ul className={styles.body}>
            {accountDeletionContent.kept.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>

        <label className={styles.body} htmlFor={copy.inputId}>
          {copy.phraseLabel}: <strong>{dialog.confirmation}</strong>
        </label>
        <input
          aria-describedby={mismatched ? copy.noticeId : undefined}
          aria-invalid={mismatched}
          autoComplete="off"
          className={styles.input}
          id={copy.inputId}
          onChange={(event) => {
            setTyped(event.target.value)
          }}
          spellCheck={false}
          type="text"
          value={typed}
        />

        {/* 대화상자의 status 하나. 화면의 region은 `inert` 배경 아래에 있으므로 진행·거절을 여기서 한 번 말한다. */}
        <div aria-live="polite" role="status">
          {confirming ? (
            <p className={styles.state} id={copy.progressId}>
              <span aria-hidden="true" className={styles.marker} />
              <span>{copy.confirming}</span>
            </p>
          ) : null}
          {dialog.code !== null && !confirming ? (
            <p className={styles.notice} id={copy.noticeId}>
              {accountDeletionRefusalMessage(dialog.code)}
            </p>
          ) : null}
          {dialog.uncertain && !confirming ? (
            <p className={styles.notice} id={copy.uncertainId}>
              {copy.uncertain}
            </p>
          ) : null}
        </div>

        <div className={styles.actions}>
          <button
            aria-disabled={confirming}
            className={styles.cancelButton}
            onClick={() => {
              if (!confirming) onCancel()
            }}
            ref={cancelButton}
            type="button"
          >
            {copy.cancel}
          </button>
          <button
            aria-describedby={describedBy}
            aria-disabled={blocked}
            className={styles.confirmButton}
            onClick={() => {
              if (!blocked) onConfirm(typed)
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
