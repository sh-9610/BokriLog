import { useEffect, useRef, type ReactElement } from 'react'

import { authContent, type SignInProvider } from '../content/auth'
import styles from './UnlinkDialog.module.css'

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
 * `<dialog>`를 쓰지 않는다. `showModal`의 동작(`::backdrop`, inert 처리, `Esc` 취소)은 브라우저마다
 * 구현 시점이 다르고 시험 환경에서는 절반만 산다. 여기서 필요한 넷을 직접 들고 있는 편이 어느
 * 층에서도 같은 동작을 보장한다 — 기본 포커스, 포커스 가둠, `Esc` 닫기, 호출점 복귀.
 *
 * **키보드 처리기가 `document`에 있다.** 겹 `<div>`의 `onKeyDown`에 두면 그 겹을 한 번 클릭하는 것
 * 만으로 방어가 통째로 사라진다 — 겹은 포커스를 받을 수 없어 포커스가 `<body>`로 떨어지고, React의
 * 이벤트는 루트 컨테이너에서 듣기 때문에 `<body>`에서 난 keydown은 그 처리기에 닿지 않는다. 그러면
 * `Esc`가 먹지 않고 `Tab`이 건너뛰기 링크로 빠져나간다. 포인터로 포커스를 잃는 경로 자체도 아래
 * `onMouseDown`이 막지만, 방어를 그 하나에 걸지 않는다.
 *
 * 배경은 화면이 비활성화한다(`inert`). 그 속성은 대화상자를 형제로 둔 쪽에서만 걸 수 있으므로
 * `SettingsScreen`이 소유하고, 여기서는 문서 스크롤만 잠근다.
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

  /**
   * 지금 렌더의 콜백과 상태.
   *
   * 아래 `document` 처리기와 정리는 한 번만 붙는다(열릴 때 붙고 닫힐 때 떨어진다). 이 값들을
   * 의존성에 넣어 매 렌더 다시 붙이면 그 사이에 도착한 키 입력이 사라질 수 있고, 닫는 조작이 바로
   * 그 입력이다. 그래서 값은 상자에 담아 나르고 상자는 렌더가 아니라 effect가 채운다 — 렌더 중에
   * ref를 쓰면 같은 커밋을 두 번 그리는 모드에서 값이 갈린다.
   */
  const latest = useRef({ onCancel, pending, restoreFocus })
  useEffect(() => {
    latest.current = { onCancel, pending, restoreFocus }
  })

  useEffect(() => {
    const opener = document.activeElement

    // 기본 포커스는 취소다. 비가역 조작의 대화상자에서 `Enter` 한 번이 실행이 되면 안 된다.
    cancelButton.current?.focus()

    /*
     * 문서 스크롤을 잠근다. 잠그지 않으면 대화상자가 열린 채로 뒤 화면이 스크롤되고, 그 움직임은
     * 사용자가 지금 답해야 하는 질문과 무관하다.
     */
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.preventDefault()
        if (!latest.current.pending) {
          latest.current.onCancel()
        }
        return
      }
      if (event.key !== 'Tab') {
        return
      }
      /*
       * 포커스 가둠.
       *
       * 대화상자 안의 조작을 문서 순서로 모아 양 끝을 잇는다. 요소를 이 자리에서 매번 읽는 이유는
       * 상태에 따라 버튼이 바뀔 수 있기 때문이고, 개수가 둘이라 비용이 없다.
       *
       * 포커스가 대화상자 밖에 있으면 안으로 끌어온다. 겹을 클릭해 포커스를 잃은 뒤의 첫 `Tab`이
       * 그 경우이며, 끌어오지 않으면 그 `Tab`이 배경의 조작으로 나간다.
       *
       * `aria-disabled`를 쓰고 `disabled`를 쓰지 않으므로 진행 중에도 두 요소가 탭 순서에 남는다 —
       * 그것이 의도다. `disabled`는 방금 `Enter`로 그 버튼을 실행한 사용자의 포커스를 빼앗는다.
       */
      const surface = dialog.current
      if (surface === null) {
        return
      }
      const focusable = surface.querySelectorAll<HTMLElement>('button')
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (first === undefined || last === undefined) {
        return
      }
      const active = document.activeElement
      if (!(active instanceof HTMLElement) || !surface.contains(active)) {
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

    // capture 단계다. 배경의 어떤 처리기보다 먼저 답해야 `Esc`가 다른 조작에 먹히지 않는다.
    document.addEventListener('keydown', onKeyDown, true)

    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
      document.body.style.overflow = previousOverflow
      /*
       * 호출점으로 돌려준다 — 화면이 그러라고 했고 그 요소가 아직 문서에 있을 때만.
       *
       * 해제에 성공하면 그 버튼은 곧 사라진다(그 제공자가 더 이상 연결돼 있지 않다). 그때 여기서
       * 포커스를 돌려주면 사라지는 요소에 얹는 셈이고 포커스는 잠시 뒤 `<body>`로 떨어진다 —
       * 그 경우의 복구는 화면이 결과 문장으로 옮겨 받는다(`SettingsScreen`).
       */
      if (latest.current.restoreFocus && opener instanceof HTMLElement && opener.isConnected) {
        opener.focus()
      }
    }
  }, [])

  return (
    /*
     * 바깥 겹. 화면 전체를 덮어 뒤 내용을 가리고, 대화상자 밖의 포인터 조작을 받지 않는다.
     *
     * 바깥을 눌러 닫는 동작을 두지 않는다. 비가역 조작의 확인에서 실수로 닫히는 것은 취소 쪽이라
     * 안전해 보이지만, 그 동작이 있으면 "무엇을 눌렀는지" 모르는 상태로 대화상자가 사라진다 —
     * 닫는 길은 취소 버튼과 `Esc` 둘이고 둘 다 사용자가 의도한 조작이다.
     */
    <div
      className={styles.backdrop}
      onMouseDown={(event) => {
        /*
         * 조작이 아닌 자리를 누르면 포커스를 옮기지 않는다. 기본 동작을 그대로 두면 포커스가
         * `<body>`로 떨어져 사용자가 방금 있던 자리를 잃는다.
         */
        if (!(event.target instanceof HTMLElement) || event.target.closest('button') === null) {
          event.preventDefault()
        }
      }}
    >
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
