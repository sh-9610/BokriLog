import { useEffect, useLayoutEffect, useRef, type MouseEvent, type RefObject } from 'react'

interface ConfirmationDialogOptions {
  /** 대화상자 표면. 포커스 가둠이 이 안의 버튼을 문서 순서로 모은다. */
  readonly surface: RefObject<HTMLElement | null>
  /** 취소 버튼. 열리는 커밋에서 기본 포커스가 여기로 간다. */
  readonly cancelButton: RefObject<HTMLButtonElement | null>
  /** 최종 확인이 도는 중인가. 참이면 `Esc`가 닫지 않는다 — 서버 요청을 멈추는 척하지 않는다. */
  readonly isPending: boolean
  readonly onCancel: () => void
  /**
   * 닫은 뒤 호출점으로 포커스를 돌려줄 것인가. 뜻은 대화상자마다 다르다 — 해제 성공(버튼이 사라진다),
   * 확정 진행 중(화면이 스스로 옮긴다) 등 — 그래서 각 대화상자가 넘긴다.
   */
  readonly restoreFocus: boolean
  /**
   * 호출점을 모를 때 대신 돌려줄 요소의 id. 세션 재확인이 대화상자를 다시 마운트하면 기록된 호출점이
   * `<body>`이거나 문서를 떠난 요소라, 그 대화상자를 연 이유의 버튼으로 돌려준다. 없으면 복귀하지 않는다.
   */
  readonly fallbackTargetId?: string
}

/**
 * 겹의 `onMouseDown`. 훅 상태와 무관한 모듈 상수라 대화상자가 겹에 직접 붙인다.
 *
 * 조작이 아닌 자리를 누르면 포커스를 옮기지 않는다. 기본 동작을 그대로 두면 포커스가 `<body>`로
 * 떨어져 사용자가 방금 있던 자리를 잃는다. 바깥을 눌러 닫는 동작은 두지 않는다 — 비가역 조작의
 * 확인에서 그 동작이 있으면 "무엇을 눌렀는지" 모르는 상태로 대화상자가 사라진다. 닫는 길은 취소
 * 버튼과 `Esc` 둘이고 둘 다 사용자가 의도한 조작이다.
 */
export function onBackdropMouseDown(event: MouseEvent<HTMLElement>): void {
  if (!(event.target instanceof HTMLElement) || event.target.closest('button') === null) {
    event.preventDefault()
  }
}

/**
 * 확인 대화상자의 메커니즘(DESIGN.md `confirmation-dialog`). `UnlinkDialog`·`PlanEndDialog`·
 * `ConnectionRevokeDialog`·`OperatorExportDialog`가 마크업과 status 표시 방식은 각자 들고 이 넷을 여기에 위임한다 — 기본
 * 포커스(취소), 포커스 가둠, `Esc` 닫기, 호출점 복귀. 문서 스크롤 잠금도 함께다. 겹 클릭 방어는 모듈
 * 상수 `onBackdropMouseDown`이고 대화상자가 겹에 직접 붙인다.
 *
 * `<dialog>`를 쓰지 않는다. `showModal`의 동작(`::backdrop`, inert 처리, `Esc` 취소)은 브라우저마다
 * 구현 시점이 다르고 시험 환경에서는 절반만 산다. 필요한 넷을 직접 들고 있는 편이 어느 층에서도 같은
 * 동작을 보장한다.
 *
 * **키보드 처리기가 `document`에 있다.** 겹 `<div>`의 `onKeyDown`에 두면 그 겹을 한 번 클릭하는 것
 * 만으로 방어가 통째로 사라진다 — 겹은 포커스를 받을 수 없어 포커스가 `<body>`로 떨어지고, React의
 * 이벤트는 루트 컨테이너에서 듣기 때문에 `<body>`에서 난 keydown은 그 처리기에 닿지 않는다. 그러면
 * `Esc`가 먹지 않고 `Tab`이 건너뛰기 링크로 빠져나간다. 포인터로 포커스를 잃는 경로 자체도
 * `onBackdropMouseDown`이 막지만, 방어를 그 하나에 걸지 않는다.
 *
 * 배경은 화면이 비활성화한다(`inert`). 그 속성은 대화상자를 형제로 둔 쪽에서만 걸 수 있으므로 화면이
 * 소유하고, 여기서는 문서 스크롤만 잠근다. live region도 만들지 않는다 — 진행을 말하는 자리는 대화상자
 * 마다 다르다(화면의 region 하나, 또는 대화상자 안의 정적 문장이나 status 하나).
 */
export function useConfirmationDialog({
  cancelButton,
  fallbackTargetId,
  isPending,
  onCancel,
  restoreFocus,
  surface,
}: ConfirmationDialogOptions): void {
  /**
   * 지금 렌더의 콜백과 상태.
   *
   * 아래 `document` 처리기와 정리는 한 번만 붙는다(열릴 때 붙고 닫힐 때 떨어진다). 이 값들을
   * 의존성에 넣어 매 렌더 다시 붙이면 그 사이에 도착한 키 입력이 사라질 수 있고, 닫는 조작이 바로
   * 그 입력이다. 그래서 값은 상자에 담아 나르고 상자는 렌더가 아니라 effect가 채운다 — 렌더 중에
   * ref를 쓰면 같은 커밋을 두 번 그리는 모드에서 값이 갈린다.
   *
   * 상자를 채우는 effect와 아래 첫 effect는 layout effect다. 대화상자는 준비 요청의 응답 뒤에 열리고,
   * 기본 포커스와 키 처리기가 대화상자를 그린 그 커밋 안에서 자리를 잡아야 한다 — passive effect였을
   * 때는 그리기 뒤 별도 작업으로 돌아, 부하가 걸리면 새 대화상자를 이미 본 코드나 키 입력보다 늦게
   * 왔다. 상자도 같은 커밋에 채운다 — 키 처리기와 정리가 읽는 값이 그 커밋의 값이어야 한다.
   */
  const latest = useRef({ fallbackTargetId, isPending, onCancel, restoreFocus })
  useLayoutEffect(() => {
    latest.current = { fallbackTargetId, isPending, onCancel, restoreFocus }
  })

  /**
   * 대화상자를 연 요소. 열린 커밋에서 기록하고 닫힌 뒤 돌려준다(아래 두 번째 effect). `undefined`가
   * "아직 기록하지 않았다"이고 `null`은 "기록했지만 아무 요소도 아니었다"다 — 둘을 가르지 않으면(`??=`)
   * `activeElement`가 `null`이었던 열기에서 재실행이 다시 기록한다.
   */
  const opener = useRef<Element | null | undefined>(undefined)
  /** 스크롤 잠금 전의 `overflow`. 첫 실행에서만 기록한다 — 재실행이 `hidden`을 이전 값으로 적지 않게. */
  const savedOverflow = useRef<string | null>(null)

  useLayoutEffect(() => {
    /*
     * 한 번만 기록한다. 개발 빌드의 `StrictMode`는 이 effect를 정리했다가 다시 돌리는데, 그때는 이미
     * 아래 줄이 취소 버튼으로 포커스를 옮긴 뒤라 다시 기록하면 호출점이 대화상자 자신의 버튼이 된다 —
     * 닫힌 뒤 돌려줄 곳이 사라진 요소라 포커스가 `<body>`로 떨어진다. 호출자가 렌더마다 새 ref 상자를
     * 넘겨 effect가 다시 도는 경우도 같다.
     */
    if (opener.current === undefined) {
      opener.current = document.activeElement
    }

    // 기본 포커스는 취소다. 비가역 조작의 대화상자에서 `Enter` 한 번이 실행이 되면 안 된다.
    cancelButton.current?.focus()

    /*
     * 문서 스크롤을 잠근다. 잠그지 않으면 대화상자가 열린 채로 뒤 화면이 스크롤되고, 그 움직임은
     * 사용자가 지금 답해야 하는 질문과 무관하다. 복원값도 한 번만 기록한다.
     */
    savedOverflow.current ??= document.body.style.overflow
    const previousOverflow = savedOverflow.current
    document.body.style.overflow = 'hidden'

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.preventDefault()
        if (!latest.current.isPending) {
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
      const root = surface.current
      if (root === null) {
        return
      }
      // 입력 칸도 가둔다 — 운영자 화면의 확인 문구 재입력 칸(Story 3.1)이 버튼 사이에서 건너뛰어지면 키보드로 다시
      // 닿을 수 없다. 입력 칸이 없는 대화상자에서는 결과가 같다.
      const focusable = root.querySelectorAll<HTMLElement>('button, input')
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      if (first === undefined || last === undefined) {
        return
      }
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

    // capture 단계다. 배경의 어떤 처리기보다 먼저 답해야 `Esc`가 다른 조작에 먹히지 않는다.
    document.addEventListener('keydown', onKeyDown, true)

    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
      document.body.style.overflow = previousOverflow
    }
    // 두 ref 상자는 대화상자가 사는 동안 같은 객체다. 열릴 때 한 번 붙고 닫힐 때 한 번 떨어진다.
  }, [cancelButton, surface])

  /*
   * 닫히면 호출점으로 돌려준다 — 화면이 그러라고 했고(`restoreFocus`), 포커스가 갈 곳을 잃었을 때만.
   *
   * 호출점을 모르면(대화상자가 다시 마운트되어 기록된 요소가 `<body>`이거나 문서를 떠났다) 그
   * 대화상자를 연 이유의 버튼(`fallbackTargetId`)으로 돌려주고, 그것도 없으면 돌려주지 않는다.
   *
   * 포커스를 잃었을 때만인 이유: 화면이 같은 커밋에서 layout effect로 포커스를 옮겨 받는 경우가 있다
   * (해제 성공의 결과 문장, 다른 상태를 보여 준 읽기가 옮기는 종료 구역의 표지). 대화상자는 그 커밋에서
   * 바로 사라지므로 상자의 `restoreFocus`는 마지막으로 열려 있던 렌더의 값이고, 그 값만 보면 화면이
   * 옮긴 포커스를 뒤늦게 덮는다.
   *
   * 이 복귀만 passive effect의 정리다. 호출점은 대화상자가 열린 동안 배경의 `inert` 아래에 있고, 닫는
   * 커밋이 그 속성을 걷어야 포커스를 받을 수 있다. layout effect의 정리는 그 커밋의 DOM 변경 단계에서
   * 대화상자 삭제와 함께 도는데 React는 부모의 자식 삭제를 형제 갱신보다 먼저 처리한다 — 아직 비활성인
   * 버튼의 `focus()`를 브라우저가 무시해 포커스가 `<body>`로 떨어진다(CI run 37143229430,
   * `e2e/plan-end.spec.ts`; jsdom은 `inert`를 모르므로 Playwright만 이것을 본다). passive 정리는 그
   * 커밋이 끝난 뒤에 돌고, `Esc`·취소 같은 이산 입력이 닫으면 React가 그 커밋 끝에서 바로 비우므로
   * 늦지도 않다. 응답이 닫는 경우(최종 확인의 실패)에는 복귀가 Scheduler의 다음 작업 하나만큼 늦게
   * 온다 — 그 복귀를 동기로 단정하는 시험은 없다. 규칙은 `DEVELOPMENT.md`(`inert` 배경으로 돌려주는
   * 초점은 예외다)에 있다.
   */
  useEffect(
    () => () => {
      if (!latest.current.restoreFocus) {
        return
      }
      const active = document.activeElement
      const lost = active === null || active === document.body || !active.isConnected
      if (!lost) {
        return
      }
      const target = opener.current
      const fallbackId = latest.current.fallbackTargetId
      const known =
        target instanceof HTMLElement && target !== document.body && target.isConnected
          ? target
          : fallbackId === undefined
            ? null
            : document.getElementById(fallbackId)
      known?.focus()
    },
    [],
  )
}
