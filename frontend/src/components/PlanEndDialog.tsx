import { useRef, type ReactElement } from 'react'

import type { PlanEndDialogView } from '../session/usePlanEnd'

import { planEndContent } from '../content/planEnd'
import styles from './ConfirmationDialog.module.css'
import { TimeRange } from './EvidenceParts'
import { onBackdropMouseDown, useConfirmationDialog } from './useConfirmationDialog'

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
 * 취소가 문서 순서에서 먼저이고 기본 포커스를 가진다. 확정이 도는 동안 두 버튼은 `aria-disabled`이고 진행
 * 문장이 확인 버튼을 설명하며 대화상자는 자리를 지킨다. 응답을 받지 못한 확정은 그렇다고 말하는 문장과 함께
 * 대화상자를 열어 둔다 — 다시 누르면 같은 요청을 보낸다.
 *
 * 메커니즘(기본 포커스·포커스 가둠·`Esc`·스크롤 잠금·호출점 복귀)은 `useConfirmationDialog`가 소유한다.
 * 호출점을 모르면(세션 재확인이 대화상자를 다시 마운트했다) 연 이유의 버튼 — 종료 구역의 버튼이나
 * 장기 공백 선택의 `새 계획 시작` — 으로 돌려준다. 자기 status 영역은 없다: 화면의 하나뿐인 영역은
 * 대화상자가 열린 동안 `inert` 배경 아래에 있으므로, 진행은 확인 버튼이 가리키는 정적 문장으로 여기서 말한다.
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
  useConfirmationDialog({
    surface,
    cancelButton,
    isPending: pending,
    onCancel,
    restoreFocus,
    fallbackTargetId:
      dialog.reason === 'LONG_GAP_NEW_PLAN'
        ? planEndContent.newPlanActionId
        : planEndContent.area.actionId,
  })

  return (
    // The backdrop: it covers the screen and takes no pointer action. Clicking outside does not close the dialog.
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
