import { useRef, type ReactElement } from 'react'

import { authContent, type SignInProvider } from '../content/auth'
import styles from './ConfirmationDialog.module.css'
import { onBackdropMouseDown, useConfirmationDialog } from './useConfirmationDialog'

interface UnlinkDialogProps {
  /** 해제 대상. 제목이 이 값으로 정해진다. */
  readonly provider: SignInProvider
  /** 최종 확인이 도는 중인가. 상태는 화면이 소유한다(`useUnlinking`). */
  readonly pending: boolean
  /** 닫은 뒤 호출점으로 포커스를 돌려줄 것인가. 해제에 성공하면 그 버튼이 사라지므로 거짓이다. */
  readonly restoreFocus: boolean
  readonly onCancel: () => void
  readonly onConfirm: () => void
}

/**
 * 로그인 수단 해제의 확인 대화상자.
 *
 * DESIGN.md `confirmation-dialog`가 규정한 형태다 — 제목에 **대상과 결과**를 쓰고, **삭제되는 것과
 * 보존되는 것을 분리**하며, **기본 포커스는 취소**다. 대상명 재입력은 요구하지 않는다: 그 요구는
 * 같은 표가 "계정 삭제처럼 범위가 큰 작업"에만 건 것이고(Epic 5), 해제는 남은 수단으로 계정에
 * 그대로 닿을 수 있는 조작이다.
 *
 * **이 대화상자가 OIDC 왕복의 자리를 대신한다.** 연결은 대상 제공자의 왕복이 최종 확인의 자리였고,
 * 해제에는 그 왕복이 없다 — 허가가 건너야 하는 창은 "대화상자가 열린 뒤 사용자가 확인을 누르기까지"
 * 이며 그 사이 세션은 회전하지 않는다.
 *
 * 메커니즘(기본 포커스·포커스 가둠·`Esc`·스크롤 잠금·호출점 복귀)은 `useConfirmationDialog`가 소유한다.
 * 호출점으로 돌려주지 않는 경우(해제 성공)의 복구는 화면이 결과 문장으로 옮겨 받는다(`SettingsScreen`).
 *
 * 화면당 하나뿐인 `role="status"` 영역을 이 대화상자는 만들지 않는다(`ReauthPanel`과 같은 계약).
 * 진행을 말하는 것은 화면의 live region 하나이고, 아래 문장들은 열릴 때 이미 있는 정적 값이라
 * live region이 안내할 대상이 아니다 — 대신 제목이 본문을 자기 설명으로 가리킨다.
 */
export function UnlinkDialog({
  onCancel,
  onConfirm,
  pending,
  provider,
  restoreFocus,
}: UnlinkDialogProps): ReactElement {
  const dialog = useRef<HTMLDivElement>(null)
  const cancelButton = useRef<HTMLButtonElement>(null)
  useConfirmationDialog({
    surface: dialog,
    cancelButton,
    isPending: pending,
    onCancel,
    restoreFocus,
  })

  return (
    // 바깥 겹. 화면 전체를 덮어 뒤 내용을 가리고, 대화상자 밖의 포인터 조작을 받지 않는다.
    <div className={styles.backdrop} onMouseDown={onBackdropMouseDown}>
      <div
        aria-describedby={authContent.settings.unlinkDialog.descriptionId}
        aria-labelledby={authContent.settings.unlinkDialog.titleId}
        aria-modal="true"
        className={styles.dialog}
        ref={dialog}
        role="dialog"
      >
        <h2 className={styles.title} id={authContent.settings.unlinkDialog.titleId}>
          {authContent.settings.unlinkDialog.titles[provider]}
        </h2>

        {/*
          삭제되는 것과 보존되는 것을 문단으로 나눈다(DESIGN.md `confirmation-dialog`). 한 문단에
          섞으면 "무엇이 사라지고 무엇이 남는가"를 읽는 사람이 문장 안에서 갈라야 한다.

          제목의 `aria-describedby`가 이 묶음을 가리킨다. 포커스는 취소 버튼에 있으므로 제목 문장만
          읽히면 대상만 알고 결과는 모르는 상태가 된다.
        */}
        <div id={authContent.settings.unlinkDialog.descriptionId}>
          <p className={styles.destructive}>{authContent.settings.unlinkDialog.removed}</p>
          <p className={styles.body}>{authContent.settings.unlinkDialog.kept}</p>
          <p className={styles.body}>{authContent.settings.unlinkDialog.sessions}</p>
        </div>

        {/*
          취소가 먼저다. 문서 순서가 곧 탭 순서이고, 비가역 조작의 대화상자에서 먼저 닿는 조작은
          되돌릴 수 있는 쪽이어야 한다.
        */}
        <div className={styles.actions}>
          <button
            aria-disabled={pending}
            className={styles.cancelButton}
            onClick={() => {
              if (pending) {
                return
              }
              onCancel()
            }}
            ref={cancelButton}
            type="button"
          >
            {authContent.settings.unlinkDialog.cancel}
          </button>
          <button
            aria-disabled={pending}
            className={styles.confirmButton}
            onClick={() => {
              if (pending) {
                return
              }
              onConfirm()
            }}
            type="button"
          >
            {authContent.settings.unlinkDialog.confirm}
          </button>
        </div>
      </div>
    </div>
  )
}
