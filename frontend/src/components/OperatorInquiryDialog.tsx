import { useRef, useState, type ReactElement } from 'react'

import type { PreparedInquiryUpdate } from '../session/operatorApi'

import {
  inquiryStatusText,
  noticeTime,
  operatorContent,
  operatorRefusalMessage,
  UPDATE_INQUIRY_COMMAND,
} from '../content/operator'
import styles from './ConfirmationDialog.module.css'
import { onBackdropMouseDown, useConfirmationDialog } from './useConfirmationDialog'

const copy = operatorContent.inquiryDialog

interface OperatorInquiryDialogProps {
  readonly prepared: PreparedInquiryUpdate
  /** 준비에 실은 답변. 서버는 답변 원문을 되돌려 주지 않으므로 화면이 들고 있던 값을 보인다. */
  readonly answer: string | null
  readonly confirming: boolean
  /** 대화상자 안에서 말할 거절 code(문구 불일치). 없으면 `null`. */
  readonly code: string | null
  /** 마지막 확정의 응답을 받지 못했다. 실행 여부를 단정하지 않고 같은 문구의 재실행·조회를 안내한다. */
  readonly uncertain: boolean
  readonly onCancel: () => void
  readonly onConfirm: (confirmation: string) => void
}

/**
 * 문의 처리의 확인 대화상자(Story 3.6, DESIGN.md `confirmation-dialog`).
 *
 * 접수 번호·상태 전이(지금 → 다음)·답변 목표·답변·처리 사유를 먼저 설명하고, 서버가 정한 확인 문구를 <b>그대로 다시
 * 입력</b>하게 한다. 입력이 문구와 같아질 때까지 실행 버튼은 `aria-disabled`이고, 그래도 눌리면 서버가 허가를 소비하지 않고
 * 거절한다.
 *
 * 취소가 문서 순서에서 먼저이고 기본 포커스를 가진다. 메커니즘은 `useConfirmationDialog`가 소유한다. 호출점을 모르면 문의
 * 처리 준비 버튼으로 돌려준다.
 */
export function OperatorInquiryDialog({
  answer,
  code,
  confirming,
  onCancel,
  onConfirm,
  prepared,
  uncertain,
}: OperatorInquiryDialogProps): ReactElement {
  const surface = useRef<HTMLDivElement>(null)
  const cancelButton = useRef<HTMLButtonElement>(null)
  const [typed, setTyped] = useState('')
  useConfirmationDialog({
    surface,
    cancelButton,
    isPending: confirming,
    onCancel,
    restoreFocus: true,
    fallbackTargetId: operatorContent.inquiry.startId,
  })
  const matches =
    typed.normalize('NFC').trim() === prepared.confirmationPhrase.normalize('NFC').trim()
  const blocked = confirming || !matches
  const impact = prepared.impact

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
          <p className={styles.body}>
            {copy.reference}: {impact.reference}
          </p>
          <p className={styles.body}>
            {copy.transition}:{' '}
            {impact.currentStatus === null ? '' : `${inquiryStatusText(impact.currentStatus)} → `}
            {inquiryStatusText(impact.nextStatus)}
          </p>
          <p className={styles.body}>
            {copy.targetAt}:{' '}
            {impact.targetAt === null
              ? operatorContent.inquiry.targetPending
              : noticeTime(impact.targetAt)}
          </p>
          {answer === null ? null : (
            <p className={styles.body}>
              {copy.answer}: {answer}
            </p>
          )}
          <p className={styles.body}>
            {copy.reason}: {prepared.reason}
          </p>
          <p className={styles.destructive}>{copy.effect}</p>
          <p className={styles.body}>{copy.kept}</p>
        </div>

        <label className={styles.body} htmlFor={copy.inputId}>
          {copy.phraseLabel}: <strong>{prepared.confirmationPhrase}</strong>
        </label>
        <input
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
          {code !== null && !confirming ? (
            <p className={styles.notice}>{operatorRefusalMessage(code, UPDATE_INQUIRY_COMMAND)}</p>
          ) : null}
          {uncertain && !confirming ? (
            <p className={styles.notice}>{operatorContent.inquiry.uncertain}</p>
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
            aria-describedby={confirming ? copy.progressId : undefined}
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
