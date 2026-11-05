import { useState } from 'react'

import { signOut } from './signOut'

/**
 * 로그아웃 진행 상태.
 *
 * 화면이 이 상태를 갖는 이유는 두 곳이 그것을 함께 써야 하기 때문이다 — 머리말의 버튼과 본문의
 * live region이다. 진행 중임을 버튼의 이름으로 바꿔 말하면 그 이름이 동작("로그아웃")이 아니라
 * 상태("로그아웃하는 중입니다.")가 되고, 보조기술 사용자는 자기가 무엇을 누르는 컨트롤 위에 있는지
 * 알 수 없다. 상태를 말하는 자리는 화면당 하나뿐인 `role="status"` 영역이며, 그 영역은 본문에
 * 있고 버튼은 머리말에 있으므로 두 곳이 같은 값을 보려면 상태가 그 위에 있어야 한다.
 */
export interface SignOutAccess {
  /** 요청이 도는 중인가. 버튼은 `aria-disabled`로, live region은 문장으로 이 값을 반영한다. */
  readonly pending: boolean
  /**
   * 마지막 로그아웃이 실패했는가. 새 요청을 시작하면 거짓으로 돌아간다. 실패해도 이동(`logoutFailed` 표지)은 그대로 일어나고, 이동이
   * 막히는 환경에서 화면이 그 사실을 자기 live region으로 말할 때 쓴다(삭제 진행 화면, Story 5.1).
   */
  readonly failed: boolean
  /** 로그아웃을 시작한다. 이미 도는 중이면 아무 일도 하지 않는다. */
  readonly requestSignOut: () => void
}

/**
 * 로그아웃 하나를 시작하고 그 진행을 화면에 알린다.
 *
 * 성공에서 `pending`을 되돌리지 않는다. 성공한 로그아웃에는 최상위 내비게이션이 따라오므로 이
 * 문서는 곧 사라지고, 그 사이에 버튼을 다시 누를 수 있게 두면 이미 사라진 세션의 토큰으로 두
 * 번째 요청이 나간다. 실패에서는 되돌린다 — 전환이 막히는 환경에서 버튼이 영구히 죽어 있는 상태를
 * 남기지 않는다.
 *
 * 거부도 잡는다. `signOut`은 이동 실패를 스스로 삼키지만, 그 약속이 깨지는 날 버튼이 영구
 * pending으로 남는 것이 사용자가 치를 대가여서는 안 된다.
 */
export function useSignOut(csrfToken: string | null): SignOutAccess {
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)

  return {
    pending,
    failed,
    requestSignOut: (): void => {
      if (pending) {
        // 중복 제출 차단. `aria-disabled`는 알려 주기만 하고 막지는 않으므로 이 가드가 필요하다.
        return
      }
      setPending(true)
      setFailed(false)
      void signOut(csrfToken)
        .then((outcome) => {
          if (outcome === 'failed') {
            setPending(false)
            setFailed(true)
          }
        })
        .catch(() => {
          setPending(false)
          setFailed(true)
        })
    },
  }
}
