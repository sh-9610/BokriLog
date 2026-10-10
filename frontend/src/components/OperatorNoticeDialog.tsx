import { useRef, useState, type ReactElement } from 'react'

import type { PreparedNotice } from '../session/operatorApi'

import {
  featuresText,
  NEW_INCIDENT,
  noticeStatusLabel,
  noticeTime,
  operatorContent,
  operatorRefusalMessage,
  PUBLISH_NOTICE_COMMAND,
} from '../content/operator'
import styles from './ConfirmationDialog.module.css'
import { onBackdropMouseDown, useConfirmationDialog } from './useConfirmationDialog'

const copy = operatorContent.noticeDialog

interface OperatorNoticeDialogProps {
  readonly prepared: PreparedNotice
  readonly confirming: boolean
  /** 대화상자 안에서 말할 거절 code(문구 불일치). 없으면 `null`. */
  readonly code: string | null
  /** 마지막 확정의 응답을 받지 못했다. 실행 여부를 단정하지 않고 같은 문구의 재실행·조회를 안내한다. */
  readonly uncertain: boolean
  readonly onCancel: () => void
  readonly onConfirm: (confirmation: string) => void
}

/**
 * 서비스 공지 발행의 확인 대화상자(Story 3.5, DESIGN.md `confirmation-dialog`).
 *
 * 사건·버전·상태·메시지와 서버가 덮는 제한에서 계산한 미리보기(멈춘 기능·계속 쓸 수 있는 기능·덮는 제한 수·다음 안내
 * 시각)를 먼저 설명하고, 서버가 정한 확인 문구를 <b>그대로 다시 입력</b>하게 한다. 입력이 문구와 같아질 때까지 실행 버튼은
 * `aria-disabled`이고, 그래도 눌리면 서버가 허가를 소비하지 않고 거절한다.
 *
 * 취소가 문서 순서에서 먼저이고 기본 포커스를 가진다. 메커니즘은 `useConfirmationDialog`가 소유한다. 호출점을 모르면
 * 공지 발행 준비 버튼으로 돌려준다.
 */
export function OperatorNoticeDialog({
  code,
  confirming,
  onCancel,
  onConfirm,
  prepared,
  uncertain,
}: OperatorNoticeDialogProps): ReactElement {
  const surface = useRef<HTMLDivElement>(null)
  const cancelButton = useRef<HTMLButtonElement>(null)
  const [typed, setTyped] = useState('')
  useConfirmationDialog({
    surface,
    cancelButton,
    isPending: confirming,
    onCancel,
    restoreFocus: true,
    fallbackTargetId: operatorContent.notice.startId,
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
            {copy.incident}: {impact.incident === NEW_INCIDENT ? copy.newIncident : impact.incident}
          </p>
          <p className={styles.body}>
            {copy.version}: v{String(impact.version)} · {copy.status}:{' '}
            {noticeStatusLabel(impact.status)}
          </p>
          <p className={styles.body}>
            {copy.message}: {prepared.reason}
          </p>
          <p className={styles.body}>{copy.impactHeading}</p>
          <p className={styles.body}>
            {copy.affected}: {featuresText(impact.affectedFeatures)}
          </p>
          <p className={styles.body}>
            {copy.continuing}: {featuresText(impact.continuingFeatures)}
          </p>
          <p className={styles.body}>{copy.covers(impact.covers.length)}</p>
          {impact.nextUpdateAt === null ? null : (
            <p className={styles.body}>
              {copy.nextUpdateAt}: {noticeTime(impact.nextUpdateAt)}
            </p>
          )}
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
            <p className={styles.notice}>
              {operatorRefusalMessage(code, PUBLISH_NOTICE_COMMAND, impact.status)}
            </p>
          ) : null}
          {uncertain && !confirming ? (
            <p className={styles.notice}>{operatorContent.notice.uncertain}</p>
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
