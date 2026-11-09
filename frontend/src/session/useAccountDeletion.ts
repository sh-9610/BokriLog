import { useCallback, useEffect, useRef, useState } from 'react'

import { accountDeletionContent, accountDeletionRefusalMessage } from '../content/accountDeletion'
import { noticePath, SETTINGS_PATH } from '../content/auth'
import {
  cancelDeletion,
  confirmDeletion,
  fetchDeletionState,
  prepareDeletion,
  type AccountDeletionState,
} from './accountDeletion'
import { replaceLocation } from './navigation'

/** 세션이 끝났음을 알리는 공통 오류 계약의 code. 이 값만 이동으로 답한다. */
const UNAUTHENTICATED = 'UNAUTHENTICATED'

/** 확인 문구가 어긋났다 — 같은 대화상자에서 다시 입력한다(서버가 허가를 소비하지 않고 무장을 남긴다). */
const CONFIRMATION_MISMATCH = 'CONFIRMATION_MISMATCH'

/** 이미 삭제가 요청돼 있다. 삭제 진행 화면으로 간다. */
const IN_PROGRESS = 'ACCOUNT_DELETION_IN_PROGRESS'

/**
 * 같은 키가 다른 본문으로 이미 커밋된 요청에 쓰였다. 서버는 이 계정의 요청 행이 그 키로 있을 때만 이 code를 내므로 삭제는 이미
 * 진행 중이다 — 진행 중 거절과 같이 진행 화면으로 간다.
 */
const IDEMPOTENCY_CONFLICT = 'IDEMPOTENCY_CONFLICT'

/**
 * 삭제가 이미 끝났다(Story 5.4) — 응답을 잃은 확정을 같은 키로 다시 보냈을 때 그 사이 삭제가 끝나 세션이 완료 확인 세션이 된 경우다. 진행
 * 중 거절과 같이 문서를 다시 요청한다(세션 확인이 완료를 말하면 그 자리에서 완료 화면이 그려진다).
 */
const COMPLETED = 'ACCOUNT_DELETION_COMPLETED'

/** 정책 조회의 표시 상태. 실패를 "정책 없음"으로 접지 않는다. */
export type AccountDeletionView =
  | { readonly kind: 'checking' }
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'loaded'; readonly state: AccountDeletionState }

/**
 * 열려 있는 확인 대화상자.
 *
 * @param confirming 확정 요청이 도는 중인가
 * @param uncertain 앞선 확정이 응답을 받지 못했는가. 다시 누르면 같은 키로 같은 요청이 나간다
 * @param code 대화상자 안에서 말할 거절 code(문구 불일치). 없으면 `null`이다
 */
export interface AccountDeletionDialogView {
  readonly policyVersion: string
  readonly confirmation: string
  readonly confirming: boolean
  readonly uncertain: boolean
  readonly code: string | null
}

/** 조작의 결과 문장 재료. 화면의 단일 live region이 말한다. */
export type AccountDeletionNotice =
  | { readonly kind: 'cancelled' }
  | { readonly kind: 'unknown' }
  | { readonly kind: 'refused'; readonly code: string | null }

export interface AccountDeletionControl {
  readonly view: AccountDeletionView
  /** 준비·확정이 도는 중인가. */
  readonly pending: boolean
  /** 준비가 도는 중인가(대화상자가 아직 없다). 화면의 live region이 진행 문장을 말한다. */
  readonly preparing: boolean
  readonly dialog: AccountDeletionDialogView | null
  readonly notice: AccountDeletionNotice | null
  /** 정책을 다시 확인한다. 자동 재시도가 아니라 사용자의 조작이다. */
  readonly reload: () => void
  /** 삭제를 준비한다. 성공하면 대화상자가 열린다. */
  readonly start: () => void
  /** 입력한 문구로 확정한다. */
  readonly confirm: (typed: string) => void
  /** 대화상자를 닫고 준비가 만든 무장과 허가를 되돌린다. */
  readonly cancel: () => void
}

/** 결과 문장. 결과가 없으면 `null`이다. */
export function accountDeletionSentence(notice: AccountDeletionNotice | null): string | null {
  if (notice === null) return null
  if (notice.kind === 'cancelled') return accountDeletionContent.cancelled
  if (notice.kind === 'unknown') return accountDeletionContent.unknown
  return accountDeletionRefusalMessage(notice.code)
}

/**
 * 설정 화면의 계정 삭제 요청(Story 5.1).
 *
 * 세션이 준비되면 정책을 한 번 읽는다. 다시 읽기는 사용자의 조작(다시 확인)과 정책이 바뀌었다는 응답 뒤뿐이다 — 자동 재시도가
 * 없다. 조작은 순서대로다 — `start`(준비, 200이면 대화상자) → `confirm`(입력 문구로 확정) 또는 `cancel`.
 *
 * <b>멱등 키는 대화상자를 열 때 한 번 만든다.</b> 응답을 받지 못한 확정은 대화상자를 열어 둔 채 같은 키로 다시 보낼 수 있고,
 * 이미 커밋됐다면 서버가 같은 요청을 돌려준다. 확정에 성공하면 이 세션은 삭제 진행 상태라 같은 문서를 다시 요청한다 — 세션 확인이
 * 삭제 진행을 말하면 어느 화면이든 진행 화면만 그린다. 중복 제출은 ref가 막는다. 값은 메모리에만 있다.
 */
export function useAccountDeletion(
  ready: boolean,
  csrfToken: string | null,
): AccountDeletionControl {
  const [view, setView] = useState<AccountDeletionView>({ kind: 'checking' })
  const [attempt, setAttempt] = useState(0)
  const [pending, setPending] = useState(false)
  const [preparing, setPreparing] = useState(false)
  const [dialog, setDialog] = useState<AccountDeletionDialogView | null>(null)
  const [notice, setNotice] = useState<AccountDeletionNotice | null>(null)
  /** 같은 tick의 두 번째 호출을 막는 상자. */
  const inFlight = useRef(false)
  /** 이 대화상자의 멱등 키. 열 때 한 번 만들고 응답을 받지 못한 확정의 재시도가 같은 값을 쓴다. */
  const key = useRef<string | null>(null)

  // 요청 시작은 passive effect다. 커밋이 보여 주는 것이 아니고 답은 어차피 다음 task에 온다.
  useEffect(() => {
    if (!ready) {
      return
    }
    let current = true
    void fetchDeletionState().then((result) => {
      if (!current) return
      if (result.outcome === 'expired') {
        replaceLocation(noticePath('expired'))
        return
      }
      setView(
        result.outcome === 'loaded'
          ? { kind: 'loaded', state: result.state }
          : { kind: 'unavailable' },
      )
    })
    return () => {
      current = false
    }
  }, [attempt, ready])

  const reload = useCallback((): void => {
    setView({ kind: 'checking' })
    setAttempt((value) => value + 1)
  }, [])

  /** 사용자가 이 화면에서 본 정책 버전. 준비·확정이 이 값을 싣는다 — 서버 응답의 값이 아니다. */
  const seenVersion = view.kind === 'loaded' ? view.state.policy.version : null

  const start = useCallback((): void => {
    if (inFlight.current || seenVersion === null) return
    inFlight.current = true
    setPending(true)
    setPreparing(true)
    setNotice(null)
    void prepareDeletion(csrfToken, seenVersion).then(
      (result) => {
        inFlight.current = false
        setPending(false)
        setPreparing(false)
        if (result.outcome === 'armed') {
          key.current = crypto.randomUUID()
          setDialog({
            // 확정도 사용자가 본 버전을 싣는다. 그 사이 정책이 바뀌었으면 서버가 409로 거절한다.
            policyVersion: seenVersion,
            confirmation: result.confirmation,
            confirming: false,
            uncertain: false,
            code: null,
          })
          return
        }
        if (result.code === UNAUTHENTICATED) {
          replaceLocation(noticePath('expired'))
          return
        }
        if (result.code === IN_PROGRESS) {
          // 다른 탭이 이미 요청했다. 같은 문서를 다시 요청하면 진행 화면이 그려진다.
          replaceLocation(SETTINGS_PATH)
          return
        }
        setNotice({ kind: 'refused', code: result.code })
      },
      () => {
        inFlight.current = false
        setPending(false)
        setPreparing(false)
        setNotice({ kind: 'refused', code: null })
      },
    )
  }, [csrfToken, seenVersion])

  const confirm = useCallback(
    (typed: string): void => {
      const open = dialog
      const current = key.current
      if (inFlight.current || open === null || current === null) return
      inFlight.current = true
      setPending(true)
      setDialog({ ...open, confirming: true, code: null })
      void confirmDeletion(csrfToken, open.policyVersion, typed, current).then(
        (result) => {
          inFlight.current = false
          setPending(false)
          if (result.outcome === 'requested') {
            // 삭제가 시작됐다. 이 세션은 상태 조회·로그아웃만 쓸 수 있고, 같은 문서를 다시 요청하면 진행 화면이 그려진다.
            key.current = null
            replaceLocation(SETTINGS_PATH)
            return
          }
          if (result.outcome === 'unanswered') {
            // 대화상자를 유지한다. 다시 누르면 같은 키로 나가고, 이미 커밋됐다면 서버가 같은 요청을 돌려준다.
            setDialog({ ...open, confirming: false, uncertain: true, code: null })
            return
          }
          if (result.code === CONFIRMATION_MISMATCH) {
            // 서버가 허가를 소비하지 않고 무장을 남겼다. 같은 대화상자에서 다시 입력한다.
            setDialog({ ...open, confirming: false, code: result.code })
            return
          }
          key.current = null
          setDialog(null)
          if (result.code === UNAUTHENTICATED) {
            replaceLocation(noticePath('expired'))
            return
          }
          if (
            result.code === IN_PROGRESS ||
            result.code === IDEMPOTENCY_CONFLICT ||
            result.code === COMPLETED
          ) {
            replaceLocation(SETTINGS_PATH)
            return
          }
          // 거절로 대화상자를 닫는다. 서버에 남은 무장과 허가를 버린다 — 결과를 기다리지 않는다.
          void cancelDeletion(csrfToken)
          setNotice({ kind: 'refused', code: result.code })
          setView({ kind: 'checking' })
          setAttempt((value) => value + 1)
        },
        () => {
          inFlight.current = false
          setPending(false)
          setDialog({ ...open, confirming: false, uncertain: true, code: null })
        },
      )
    },
    [csrfToken, dialog],
  )

  const cancel = useCallback((): void => {
    if (inFlight.current || dialog === null) return
    const uncertain = dialog.uncertain
    key.current = null
    setDialog(null)
    void cancelDeletion(csrfToken)
    if (!uncertain) {
      setNotice({ kind: 'cancelled' })
      return
    }
    /*
     * 앞선 확정이 커밋됐을 수 있다. 결과를 단정하지 않고 정책·요청 상태를 다시 읽는다 — 요청이 있으면 삭제 진행 중이므로 같은
     * 문서를 다시 요청해 진행 화면으로 가고(접수 거절 `ACCOUNT_DELETION_IN_PROGRESS`와 같은 처리), 없으면 결과를 모른다고 말한다.
     */
    setView({ kind: 'checking' })
    void fetchDeletionState().then((result) => {
      if (result.outcome === 'expired') {
        replaceLocation(noticePath('expired'))
        return
      }
      if (result.outcome === 'loaded' && result.state.request !== null) {
        replaceLocation(SETTINGS_PATH)
        return
      }
      setNotice({ kind: 'unknown' })
      setView(
        result.outcome === 'loaded'
          ? { kind: 'loaded', state: result.state }
          : { kind: 'unavailable' },
      )
    })
  }, [csrfToken, dialog])

  return { view, pending, preparing, dialog, notice, reload, start, confirm, cancel }
}
