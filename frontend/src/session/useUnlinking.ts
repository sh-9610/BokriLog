import { useRef, useState } from 'react'

import { type SignInProvider, type UnlinkNoticeKind } from '../content/auth'
import { cancelUnlink, confirmUnlink, prepareUnlink } from './unlinkProvider'

/**
 * 해제 조작의 진행 상태와 확인 대화상자의 개폐.
 *
 * 화면이 아니라 훅이 이 상태를 갖는 이유는 `useLinking`·`useReauthentication`과 같다 — 여러 자리가
 * 그것을 함께 써야 한다. 제공자별 해제 버튼, 화면당 하나뿐인 `role="status"` 영역, 그리고 대화상자
 * 자신이다.
 *
 * **결과가 이 훅에 남는다.** 해제는 문서를 떠나지 않으므로 표지를 주소에 실을 수 없고 실을 이유도
 * 없다(`unlinkProvider.ts`). 연결이 왕복 뒤의 새 문서에서 주소의 표지를 읽어야 했던 것과 반대다.
 */
export interface UnlinkingAccess {
  /** 요청이 도는 중인가. 버튼은 `aria-disabled`로, live region은 문장으로 이 값을 반영한다. */
  readonly pending: boolean
  /** 확인 대화상자가 열려 있다면 그 대상 제공자. 닫혀 있으면 `null`이다. */
  readonly confirming: SignInProvider | null
  /** 마지막 해제 조작의 결과. 아직 아무 조작도 없으면 `null`이다. */
  readonly notice: UnlinkNoticeKind | null
  /** 그 제공자의 해제를 시작한다. 성공하면 확인 대화상자가 열린다. */
  readonly requestUnlink: (provider: SignInProvider) => void
  /** 최종 확인. 열려 있는 대화상자의 대상을 해제한다. */
  readonly confirm: () => void
  /** 대화상자를 닫는다. 아무것도 바뀌지 않았다는 사실을 표지로 말한다. */
  readonly cancel: () => void
}

/**
 * 해제 하나를 준비하고 확인하고 그 진행을 화면에 알린다.
 *
 * 세 조작이 순서대로다 — `requestUnlink`(준비, 204면 대화상자를 연다) → `confirm`(최종 확인) 또는
 * `cancel`(닫기). 준비가 거부되면 대화상자를 열지 않고 표지 하나로 끝난다: 그때 사용자가 할 수
 * 있는 일은 본인 확인이고, 자동으로 이어 하지 않는 것이 계약이다.
 *
 * **성공에서도 `pending`을 되돌린다.** 연결과 다른 점이며 이유는 이동의 유무다 — 연결은 성공하면
 * 최상위 내비게이션이 따라와 이 문서가 곧 사라지지만, 해제는 같은 문서에 남는다. 되돌리지 않으면
 * 사용자는 해제에 성공한 화면에서 아무 조작도 할 수 없다.
 *
 * 중복 제출은 `pending` 가드가 막는다. `aria-disabled`는 알려 주기만 하고 막지는 않는다.
 *
 * @param onUnlinked 해제가 끝난 뒤 화면이 할 일. 사라진 제공자를 목록에서 빼고 다시 조회하는
 *   자리다 — 이 훅은 그 목록을 알지 못하고, 알게 만들면 두 상태의 소유자가 둘이 된다
 */
export function useUnlinking(
  csrfToken: string | null,
  onUnlinked: (provider: SignInProvider) => void,
): UnlinkingAccess {
  const [pending, setPending] = useState(false)
  const [confirming, setConfirming] = useState<SignInProvider | null>(null)
  const [notice, setNotice] = useState<UnlinkNoticeKind | null>(null)
  /*
   * 아직 서버에 닿지 않은 취소. 다음 준비는 이것이 끝난 뒤에 나간다.
   *
   * 취소는 결과를 기다리지 않고 대화상자를 닫으므로, 바로 다시 열면 두 요청이 서버에서 겹칠 수
   * 있다. 겹치면 늦게 끝난 취소가 방금 선 무장(세션 속성)과 허가(`(user, command, target)` 무효화)를
   * 함께 지우고, 그 대화상자의 최종 확인은 소비할 것이 없어 거부로 끝난다 — 사용자는 취소한 적 없는
   * 조작의 실패를 본다. 같은 탭 안의 순서는 여기서 세운다.
   */
  const cancelling = useRef<Promise<void> | null>(null)

  return {
    pending,
    confirming,
    notice,
    requestUnlink: (provider: SignInProvider): void => {
      if (pending || confirming !== null) {
        return
      }
      setPending(true)
      /*
       * 앞선 결과를 먼저 지운다. 남겨 두면 새 조작이 도는 동안 화면이 지난 조작의 결과를 계속
       * 주장하고, 그 문장은 지금 일어나는 일과 무관하다.
       */
      setNotice(null)
      // 기다릴 취소가 없으면 지금 나간다. 한 tick 미루면 그 사이의 두 번째 클릭이 보는 순서가 바뀐다.
      const previous = cancelling.current
      const prepared =
        previous === null
          ? prepareUnlink(provider, csrfToken)
          : previous.then(() => prepareUnlink(provider, csrfToken))
      void prepared.then(
        (outcome) => {
          setPending(false)
          if (outcome === 'armed') {
            setConfirming(provider)
            return
          }
          // 대화상자를 열지 않는다. 열면 소비할 허가가 없는 확인이 되고 그 실패는 설명할 수 없다.
          setNotice('unlinkFailed')
        },
        () => {
          // `prepareUnlink`는 거부하지 않는다. 그 약속이 깨지는 날 버튼이 영구 pending으로 남는
          // 것이 사용자가 치를 대가여서는 안 된다(`useLinking`과 같은 근거).
          setPending(false)
          setNotice('unlinkFailed')
        },
      )
    },
    confirm: (): void => {
      if (pending || confirming === null) {
        return
      }
      const provider = confirming
      setPending(true)
      void confirmUnlink(provider, csrfToken).then(
        (outcome) => {
          setPending(false)
          /*
           * 결과와 무관하게 대화상자를 닫는다. 무장은 이 확인 하나로 소비됐으므로(성공이든
           * 거부든) 같은 대화상자의 두 번째 확인은 언제나 거부다 — 열어 두면 사용자가 통하지
           * 않는 버튼을 다시 누른다.
           */
          setConfirming(null)
          setNotice(outcome === 'unlinked' ? 'unlinkSucceeded' : 'unlinkFailed')
          if (outcome === 'unlinked') {
            onUnlinked(provider)
          }
        },
        () => {
          setPending(false)
          setConfirming(null)
          setNotice('unlinkFailed')
        },
      )
    },
    cancel: (): void => {
      if (pending || confirming === null) {
        // 최종 확인이 도는 중이다. 여기서 닫으면 그 결과를 받을 자리가 사라진다.
        return
      }
      const provider = confirming
      setConfirming(null)
      /*
       * 취소도 표지를 남긴다. 대화상자를 여는 요청이 서버에 이미 나갔으므로 "아무 일도 없었다"를
       * 사용자가 스스로 알 방법이 없고, 그 침묵이 곧 불안이다.
       */
      setNotice('unlinkCancelled')
      /*
       * 준비가 만든 무장과 허가를 되돌린다. 결과를 기다리지 않는다 — 대화상자는 이미 닫혔고, 이
       * 요청이 실패해도 사용자가 할 일은 없다(남은 무장은 스스로 만료되고 다음 조작이 버린다).
       * 그 문장이 참이어야 위 표지의 "바뀐 것은 없습니다"도 참이다. 기다리는 것은 다음 준비
       * 하나뿐이다(`cancelling`).
       */
      const request = cancelUnlink(provider, csrfToken)
      cancelling.current = request
      void request.finally(() => {
        if (cancelling.current === request) {
          cancelling.current = null
        }
      })
    },
  }
}
