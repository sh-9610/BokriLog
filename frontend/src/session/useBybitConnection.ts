import { useCallback, useEffect, useRef, useState } from 'react'

import { noticePath } from '../content/auth'
import {
  cancelConnection,
  cancelRevocation,
  confirmRevocation,
  connectBybit,
  fetchConnectionStatus,
  prepareConnection,
  prepareRevocation,
} from './bybitConnection'
import { replaceLocation } from './navigation'

/**
 * 연결 결과의 표지.
 *
 * URL에 실리지 않는다. 연결에는 제공자 왕복이 없어 문서를 떠나지 않으므로 표지를 주소에 나를
 * 이유가 없고, 실으면 아무 연결도 하지 않은 사용자가 주소창으로 "연결했습니다"를 만들 수 있다
 * (해제 표지 셋이 URL 집합에서 빠진 것과 같은 근거).
 */
export type ConnectionNotice =
  | { readonly kind: 'cancelled' }
  | { readonly kind: 'rejected'; readonly code: string | null }
  | { readonly kind: 'succeeded' }
  /** 연결을 해제했다(Story 2.16). */
  | { readonly kind: 'revoked' }
  | { readonly kind: 'revokeCancelled' }
  | { readonly kind: 'revokeRejected'; readonly code: string | null }
  /** 응답을 받지 못한 확정 뒤에 취소했다. 결과를 단정하지 않고 상태를 다시 읽는다. */
  | { readonly kind: 'revokeUnknown' }

/**
 * 열려 있는 해제 확인 대화상자.
 *
 * @param confirming 확정 요청이 도는 중인가
 * @param uncertain 앞선 확정이 응답을 받지 못했는가. 다시 누르면 같은 키로 같은 요청이 나간다
 */
export interface RevocationDialogView {
  readonly connectionId: string
  readonly confirmation: string
  readonly confirming: boolean
  readonly uncertain: boolean
}

/** 세션이 끝났음을 알리는 공통 오류 계약의 code. 이 값만 이동으로 답한다. */
const UNAUTHENTICATED = 'UNAUTHENTICATED'

/** 연결 상태 조회의 표시 상태. 실패를 "연결 없음"으로 접지 않는다. */
export type ConnectionView =
  | { readonly status: 'checking' }
  | { readonly status: 'unavailable' }
  | {
      readonly status: 'loaded'
      readonly connected: boolean
      readonly verifiedAt: string | null
      readonly policyVersion: string | null
      /** 불투명 연결 참조. 해제 요청의 대상이며 화면에 그리지 않는다. */
      readonly connectionId: string | null
      readonly revoked: boolean
      readonly revokedAt: string | null
    }

/** 제출의 결과. `ignored`는 가드가 막아 요청이 나가지 않은 경우다. */
export type SubmitOutcome = 'connected' | 'ignored' | 'rejected'

export interface BybitConnectionAccess {
  /** 요청이 도는 중인가. 버튼은 `aria-disabled`로, live region은 문장으로 이 값을 반영한다. */
  readonly pending: boolean
  /** 지금 연결 상태. */
  readonly view: ConnectionView
  /** 폼이 열려 있다면 서버가 준 확인 문구. 닫혀 있으면 `null`이다. */
  readonly confirmation: string | null
  /** 마지막 조작의 결과. 아직 아무 조작도 없으면 `null`이다. */
  readonly notice: ConnectionNotice | null
  /** 연결 상태를 다시 확인한다. 자동 재시도가 아니라 사용자의 조작이다. */
  readonly refresh: () => void
  /** 폼을 열 준비를 요청한다. 성공하면 확인 문구가 생긴다. */
  readonly start: () => void
  /**
   * 검증과 저장을 요청한다.
   *
   * 값이 셋인 것이 요점이다. `ignored`는 이 훅의 가드가 요청을 보내지 않았다는 뜻이며, 그때
   * 폼이 Secret을 지우면 사용자는 아무 요청도 나가지 않았는데 값을 잃는다.
   */
  readonly submit: (apiKey: string, apiSecret: string) => Promise<SubmitOutcome>
  /** 폼을 닫고 준비가 만든 무장과 허가를 되돌린다. */
  readonly cancel: () => void
  /** 열려 있는 해제 확인 대화상자. 닫혀 있으면 `null`이다(Story 2.16). */
  readonly revocation: RevocationDialogView | null
  /** 해제를 준비한다. 성공하면 대화상자가 열린다. */
  readonly startRevocation: () => void
  /** 해제를 확정한다. */
  readonly confirmRevocation: () => void
  /** 대화상자를 닫고 준비가 만든 무장과 허가를 되돌린다. */
  readonly cancelRevocation: () => void
}

/**
 * Bybit 연결 하나를 준비하고 확인하고 그 진행을 화면에 알린다.
 *
 * 세 조작이 순서대로다 — `start`(준비, 200이면 폼을 연다) → `submit`(검증과 저장) 또는
 * `cancel`(닫기). 준비가 거부되면 폼을 열지 않고 표지 하나로 끝난다: 그때 사용자가 할 수 있는
 * 일은 본인 확인이고, 자동으로 이어 하지 않는 것이 계약이다.
 *
 * **성공에서도 `pending`을 되돌린다.** 연결은 같은 문서에 남으므로(제공자 왕복이 없다) 되돌리지
 * 않으면 사용자가 연결에 성공한 화면에서 아무 조작도 할 수 없다(`useUnlinking`과 같은 판단).
 *
 * **중복 제출을 ref가 막는다.** React 상태를 읽는 가드는 같은 tick의 두 번째 호출을 막지 못한다
 * (미해소 항목이 그 결함을 기록해 두었다). 자격정보가 실린 요청이 두 번 나가면 그만큼 제공자
 * 호출도 두 번이므로 여기서는 그 결함을 반복하지 않는다.
 */
export function useBybitConnection(
  csrfToken: string | null,
  ready: boolean,
): BybitConnectionAccess {
  const [pending, setPending] = useState(false)
  const [view, setView] = useState<ConnectionView>({ status: 'checking' })
  const [confirmation, setConfirmation] = useState<string | null>(null)
  const [notice, setNotice] = useState<ConnectionNotice | null>(null)
  const [attempt, setAttempt] = useState(0)

  /** 같은 tick의 두 번째 호출을 막는 상자. React 상태는 이 tick 안에서 갱신되지 않는다. */
  const inFlight = useRef(false)

  /*
   * 연결 상태는 게이트가 `ready`일 때만 확인한다.
   *
   * 미인증·확인 중·표시된 세션에서 이 요청을 보내면 서버가 401로 답하는 조회가 하나 늘어날
   * 뿐이고, 그 응답으로 화면이 할 일도 달라지지 않는다. 자동 재시도하지 않는 것은 세션 확인·
   * 연결 목록과 같은 규율이다(`SettingsScreen`).
   *
   * 요청을 시작하는 effect라 passive로 둔다. 커밋이 보여 주는 결과가 아니고 답은 어차피 뒤 작업에서
   * 온다.
   */
  useEffect(() => {
    if (!ready) {
      return
    }
    let current = true
    void fetchConnectionStatus().then((result) => {
      if (!current) {
        return
      }
      setView(
        result.status === 'loaded'
          ? {
              status: 'loaded',
              connected: result.connected,
              verifiedAt: result.verifiedAt,
              policyVersion: result.policyVersion,
              connectionId: result.connectionId,
              revoked: result.revoked,
              revokedAt: result.revokedAt,
            }
          : { status: 'unavailable' },
      )
    })
    return () => {
      current = false
    }
  }, [attempt, ready])

  const refresh = useCallback((): void => {
    setView({ status: 'checking' })
    setAttempt((value) => value + 1)
  }, [])

  const start = useCallback((): void => {
    if (inFlight.current) {
      return
    }
    inFlight.current = true
    setPending(true)
    /*
     * 앞선 결과를 먼저 지운다. 남겨 두면 새 조작이 도는 동안 화면이 지난 조작의 결과를 계속
     * 주장하고, 그 문장은 지금 일어나는 일과 무관하다.
     */
    setNotice(null)
    void prepareConnection(csrfToken).then(
      (result) => {
        inFlight.current = false
        setPending(false)
        if (result.outcome === 'armed') {
          setConfirmation(result.confirmation)
          return
        }
        // 폼을 열지 않는다. 열면 소비할 허가가 없는 확인이 되고 그 실패는 설명할 수 없다.
        setNotice({ kind: 'rejected', code: result.code })
      },
      () => {
        // `prepareConnection`은 거부하지 않는다. 그 약속이 깨지는 날 버튼이 영구 pending으로
        // 남는 것이 사용자가 치를 대가여서는 안 된다(`useUnlinking`과 같은 근거).
        inFlight.current = false
        setPending(false)
        setNotice({ kind: 'rejected', code: null })
      },
    )
  }, [csrfToken])

  const submit = useCallback(
    async (apiKey: string, apiSecret: string): Promise<SubmitOutcome> => {
      if (inFlight.current || confirmation === null) {
        // 요청이 나가지 않았다. 폼은 이 값을 보고 Secret을 그대로 둔다.
        return 'ignored'
      }
      inFlight.current = true
      setPending(true)
      setNotice(null)
      try {
        const result = await connectBybit(csrfToken, apiKey, apiSecret, confirmation)
        if (result.outcome === 'connected') {
          /*
           * 폼을 닫는다. 무장은 이 확인 하나로 소비됐으므로 같은 폼의 두 번째 제출은 언제나
           * 거부다 — 열어 두면 사용자가 통하지 않는 버튼을 다시 누른다.
           */
          setConfirmation(null)
          setView({
            status: 'loaded',
            connected: true,
            verifiedAt: result.verifiedAt,
            policyVersion: result.policyVersion,
            // 해제 요청의 대상인 불투명 참조. 연결 응답이 싣는다(Story 2.16).
            connectionId: result.connectionId,
            revoked: false,
            revokedAt: null,
          })
          setNotice({ kind: 'succeeded' })
          return 'connected'
        }
        if (result.code === UNAUTHENTICATED) {
          /*
           * 흐름 도중에 세션이 끝났다. 표지를 달고 진입 화면으로 보낸다 — 만료의 결과는 이동
           * 하나이며 만료 문장의 출처는 주소의 표지 하나다(Story 1.5의 계약). 제자리에 "잠시 후
           * 다시 시도"를 그리면 로그인으로 돌아갈 길이 없는 화면이 남는다.
           */
          setConfirmation(null)
          replaceLocation(noticePath('expired'))
          return 'rejected'
        }
        /*
         * 폼을 **닫지 않는다.** 검증 실패는 정상 경로이고 허가도 소비되지 않았으므로, 사용자는
         * 같은 확인 문구로 고친 키를 다시 넣을 수 있다. 닫으면 본인 확인부터 다시 해야 한다.
         */
        setNotice({ kind: 'rejected', code: result.code })
        return 'rejected'
      } finally {
        inFlight.current = false
        setPending(false)
      }
    },
    [confirmation, csrfToken],
  )

  const cancel = useCallback((): void => {
    if (inFlight.current || confirmation === null) {
      return
    }
    setConfirmation(null)
    /*
     * 취소도 표지를 남긴다. 폼을 여는 요청이 서버에 이미 나갔으므로 "아무 일도 없었다"를
     * 사용자가 스스로 알 방법이 없고, 그 침묵이 곧 불안이다(`useUnlinking`과 같은 판단).
     */
    setNotice({ kind: 'cancelled' })
    /*
     * 준비가 만든 무장과 허가를 되돌린다. 결과를 기다리지 않는다 — 폼은 이미 닫혔고, 이 요청이
     * 실패해도 사용자가 할 일은 없다(남은 무장은 스스로 만료되고 다음 조작이 버린다).
     */
    void cancelConnection(csrfToken)
  }, [confirmation, csrfToken])

  // ---------------------------------------------------------------- Story 2.16: 연결 해제

  const [revocation, setRevocation] = useState<RevocationDialogView | null>(null)
  /** 이 대화상자의 멱등 키. 열 때 한 번 만들고, 응답을 받지 못한 확정의 재시도가 같은 값을 쓴다. */
  const revocationKey = useRef<string | null>(null)
  const connectionId = view.status === 'loaded' && view.connected ? view.connectionId : null

  const startRevocation = useCallback((): void => {
    if (inFlight.current || connectionId === null) {
      return
    }
    inFlight.current = true
    setPending(true)
    setNotice(null)
    void prepareRevocation(csrfToken, connectionId).then(
      (result) => {
        inFlight.current = false
        setPending(false)
        if (result.outcome === 'armed') {
          revocationKey.current = crypto.randomUUID()
          setRevocation({
            connectionId: result.connectionId,
            confirmation: result.confirmation,
            confirming: false,
            uncertain: false,
          })
          return
        }
        if (result.code === UNAUTHENTICATED) {
          replaceLocation(noticePath('expired'))
          return
        }
        setNotice({ kind: 'revokeRejected', code: result.code })
        if (result.code === 'CONNECTION_NOT_FOUND') {
          // 화면이 본 연결이 이미 바뀌었다. 지금 상태를 다시 읽는다.
          setAttempt((value) => value + 1)
        }
      },
      () => {
        inFlight.current = false
        setPending(false)
        setNotice({ kind: 'revokeRejected', code: null })
      },
    )
  }, [connectionId, csrfToken])

  const confirmRevocationNow = useCallback((): void => {
    const open = revocation
    const key = revocationKey.current
    if (inFlight.current || open === null || key === null) {
      return
    }
    inFlight.current = true
    setPending(true)
    setRevocation({ ...open, confirming: true })
    void confirmRevocation(csrfToken, open.connectionId, open.confirmation, key).then(
      (result) => {
        inFlight.current = false
        setPending(false)
        if (result.outcome === 'revoked') {
          revocationKey.current = null
          setRevocation(null)
          setView((current) =>
            current.status === 'loaded'
              ? {
                  ...current,
                  connected: false,
                  revoked: true,
                  revokedAt: result.revokedAt,
                  connectionId: open.connectionId,
                }
              : current,
          )
          setNotice({ kind: 'revoked' })
          return
        }
        if (result.outcome === 'unanswered') {
          // 대화상자를 유지한다. 다시 누르면 같은 키로 나가고, 이미 커밋됐다면 서버가 같은 결과를 재생한다.
          setRevocation({ ...open, confirming: false, uncertain: true })
          return
        }
        revocationKey.current = null
        setRevocation(null)
        if (result.code === UNAUTHENTICATED) {
          replaceLocation(noticePath('expired'))
          return
        }
        /*
         * 거절로 대화상자를 닫는다. 서버에 남은 무장과 허가를 버린다 — 결과를 기다리지 않고 실패는 무시한다(남은 무장은
         * 스스로 만료된다).
         */
        void cancelRevocation(csrfToken)
        setNotice({ kind: 'revokeRejected', code: result.code })
        setAttempt((value) => value + 1)
      },
      () => {
        inFlight.current = false
        setPending(false)
        setRevocation({ ...open, confirming: false, uncertain: true })
      },
    )
  }, [csrfToken, revocation])

  const cancelRevocationNow = useCallback((): void => {
    if (inFlight.current || revocation === null) {
      return
    }
    const uncertain = revocation.uncertain
    revocationKey.current = null
    setRevocation(null)
    if (uncertain) {
      // 앞선 확정이 커밋됐을 수 있다. 결과를 단정하지 않고 지금 상태를 다시 읽는다.
      setNotice({ kind: 'revokeUnknown' })
      setAttempt((value) => value + 1)
    } else {
      setNotice({ kind: 'revokeCancelled' })
    }
    void cancelRevocation(csrfToken)
  }, [csrfToken, revocation])

  return {
    pending,
    view,
    confirmation,
    notice,
    refresh,
    start,
    submit,
    cancel,
    revocation,
    startRevocation,
    confirmRevocation: confirmRevocationNow,
    cancelRevocation: cancelRevocationNow,
  }
}
