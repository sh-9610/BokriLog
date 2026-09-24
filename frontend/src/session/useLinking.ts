import { useState } from 'react'

import { type SignInProvider } from '../content/auth'
import { startLinking } from './linkProvider'

/**
 * 연결 시작의 진행 상태.
 *
 * 화면이 이 상태를 갖는 이유는 `useSignOut`·`useReauthentication`과 같다 — 두 곳이 그것을 함께
 * 써야 한다. 제공자별 연결 버튼과 화면당 하나뿐인 `role="status"` 영역이며, 그 영역과 버튼이
 * 서로 다른 자리에 있으므로 상태가 그 위에 있어야 한다.
 */
export interface LinkingAccess {
  /** 요청이 도는 중인가. 버튼은 `aria-disabled`로, live region은 문장으로 이 값을 반영한다. */
  readonly pending: boolean
  /** 그 제공자를 연결하기 시작한다. 이미 도는 중이면 아무 일도 하지 않는다. */
  readonly requestLink: (provider: SignInProvider) => void
}

/**
 * 연결 하나를 시작하고 그 진행을 화면에 알린다.
 *
 * 성공에서 `pending`을 되돌리지 않는다. 성공한 시작에는 제공자로 가는 최상위 내비게이션이
 * 따라오므로 이 문서는 곧 사라지고, 그 사이에 다른 버튼을 누를 수 있게 두면 방금 심은 흐름을
 * 두 번째 요청이 덮는다 — 사용자가 고른 제공자와 실제로 열리는 제공자가 달라지는 상태다.
 *
 * 실패에서도 되돌리지 않는다. 실패 경로 역시 표지를 달고 같은 화면을 다시 요청하는 이동이며,
 * 그 이동이 막히는 환경에서만 아래 `catch`가 버튼을 되살린다.
 */
export function useLinking(csrfToken: string | null): LinkingAccess {
  const [pending, setPending] = useState(false)

  return {
    pending,
    requestLink: (provider: SignInProvider): void => {
      if (pending) {
        // 중복 제출 차단. `aria-disabled`는 알려 주기만 하고 막지는 않으므로 이 가드가 필요하다.
        return
      }
      setPending(true)
      void startLinking(provider, csrfToken).catch(() => {
        /*
         * 이동이 던졌다. `startLinking`은 그 거부를 삼키지 않으며, 그 약속이 깨지는 날 버튼이
         * 영구 pending으로 남는 것이 사용자가 치를 대가여서는 안 된다.
         */
        setPending(false)
      })
    },
  }
}
