import { useCallback, useEffect, useRef, useState } from 'react'

import type { RestrictionCommand } from '../content/operator'

import {
  cancelRestriction,
  confirmRestriction,
  fetchRestrictions,
  prepareRestriction,
  type ActiveRestriction,
  type CompletedRestriction,
  type PreparedRestriction,
} from './operatorApi'

/**
 * 영향 범위 제한의 준비 → 확인 → 확정·취소와 활성 제한 목록(Story 3.2).
 *
 * 내보내기(`useOperatorExport`)와 같은 상태 기계다. 준비는 아무것도 막지 않는다 — 서버가 대상을 확인하고 허가를 발급한
 * 뒤 영향 미리보기와 확인 문구를 준다. 확정은 사용자가 다시 입력한 문구를 보낸다. 멱등 키는 한 입력(명령·범위·대상·
 * 사유)마다 한 번 만들고 그 준비·확정에 함께 쓴다.
 *
 * <b>응답을 받지 못한 확정</b>은 실행 여부를 단정하지 않는다 — 키를 버리지 않고 대화상자를 열어 둔다. 자동으로 다시 실행하지
 * 않는다. 목록은 운영자임이 확인된 뒤(`ready`) 한 번 읽고, 확정이 끝나면 다시 읽는다. 목록의 404는 이 운영자에게 제한
 * 권한이 없다는 답이다 — 화면은 제한 영역을 그리지 않는다(fail-closed). 값은 메모리에만 있다.
 */

export type RestrictionPhase =
  'armed' | 'cancelled' | 'completed' | 'confirming' | 'idle' | 'preparing' | 'refused'

export interface RestrictionCommandState {
  readonly phase: RestrictionPhase
  readonly prepared: PreparedRestriction | null
  readonly completed: CompletedRestriction | null
  readonly code: string | null
  readonly reauthRequired: boolean
  readonly uncertain: boolean
  readonly uncertainOperationId: string | null
  readonly sessionEnded: boolean
}

export type RestrictionListStatus =
  'checking' | 'failed' | 'loaded' | 'notFound' | 'unauthenticated'

export interface RestrictionList {
  readonly status: RestrictionListStatus
  readonly items: readonly ActiveRestriction[]
  /** 한 번이라도 읽었다. 그 뒤의 실패는 목록 자리에서만 말한다. */
  readonly everLoaded: boolean
  readonly loading: boolean
}

export interface RestrictionInput {
  readonly command: RestrictionCommand
  readonly scope: string
  readonly target: string
  readonly reason: string
}

export interface OperatorRestrictionAccess extends RestrictionCommandState {
  readonly busy: boolean
  readonly list: RestrictionList
  readonly reloadList: () => void
  readonly prepare: (input: RestrictionInput) => void
  readonly confirm: (confirmation: string) => void
  readonly cancel: () => void
}

const IDLE: RestrictionCommandState = {
  phase: 'idle',
  prepared: null,
  completed: null,
  code: null,
  reauthRequired: false,
  uncertain: false,
  uncertainOperationId: null,
  sessionEnded: false,
}

const CHECKING: RestrictionList = {
  status: 'checking',
  items: [],
  everLoaded: false,
  loading: true,
}

export function useOperatorRestriction(
  ready: boolean,
  csrfToken: string | null,
): OperatorRestrictionAccess {
  const [state, setState] = useState<RestrictionCommandState>(IDLE)
  const [list, setList] = useState<RestrictionList>(CHECKING)
  const key = useRef<{ readonly input: string; readonly value: string } | null>(null)
  const running = useRef(false)
  /** 지금 도는 목록 읽기의 번호와 중단 수단. 새 읽기가 앞의 것을 대체하고 늦은 응답은 버린다. */
  const sequence = useRef(0)
  const inFlight = useRef<AbortController | null>(null)

  const readList = useCallback((): void => {
    inFlight.current?.abort()
    const controller = new AbortController()
    inFlight.current = controller
    sequence.current += 1
    const mine = sequence.current
    void fetchRestrictions(controller.signal).then((result) => {
      if (mine !== sequence.current) {
        return
      }
      inFlight.current = null
      setList((current) => {
        switch (result.status) {
          case 'loaded':
            return { status: 'loaded', items: result.value, everLoaded: true, loading: false }
          case 'notFound':
            // 제한 권한이 없거나 회수됐다. 보던 행도 남기지 않는다.
            return { status: 'notFound', items: [], everLoaded: false, loading: false }
          case 'unauthenticated':
            return { status: 'unauthenticated', items: [], everLoaded: false, loading: false }
          case 'failed':
            // 마지막으로 읽은 목록을 버리지 않는다. 실패는 목록 자리에서만 말한다.
            return { ...current, status: 'failed', loading: false }
        }
      })
    })
  }, [])

  // 첫 읽기는 게이트가 열린 순간 한 번이다. 요청 시작은 커밋이 보여 주는 결과가 아니므로 passive effect다.
  useEffect(() => {
    if (!ready) {
      return
    }
    readList()
    return () => {
      inFlight.current?.abort()
      sequence.current += 1
    }
  }, [ready, readList])

  /** 사용자가 누른 다시 조회. 도는 동안 목록 자리가 그 사실을 말한다. */
  const reloadList = useCallback((): void => {
    setList((current) => ({ ...current, loading: true }))
    readList()
  }, [readList])

  const prepare = useCallback(
    (input: RestrictionInput): void => {
      if (running.current) {
        return
      }
      running.current = true
      const text = `${input.command}\n${input.scope}\n${input.target}\n${input.reason}`
      if (key.current?.input !== text) {
        key.current = { input: text, value: crypto.randomUUID() }
      }
      const idempotencyKey = key.current.value
      setState({ ...IDLE, phase: 'preparing' })
      void prepareRestriction(
        csrfToken,
        input.command,
        input.scope,
        input.target,
        input.reason,
        idempotencyKey,
      ).then((outcome) => {
        running.current = false
        if (outcome.outcome === 'prepared') {
          setState({ ...IDLE, phase: 'armed', prepared: outcome.prepared })
          return
        }
        if (outcome.code === 'IDEMPOTENCY_CONFLICT') {
          key.current = null
        }
        // 준비가 이미 효력 중인 같은 제한을 알려 왔다. 목록이 그것을 보이도록 다시 읽는다.
        if (outcome.code === 'ALREADY_RESTRICTED') {
          reloadList()
        }
        setState({
          ...IDLE,
          phase: 'refused',
          code: outcome.code,
          reauthRequired: outcome.code === 'REAUTHENTICATION_REQUIRED',
          sessionEnded: outcome.status === 401,
        })
      })
    },
    [csrfToken, reloadList],
  )

  const confirm = useCallback(
    (confirmation: string): void => {
      const prepared = state.prepared
      const idempotencyKey = key.current?.value
      if (running.current || prepared === null || idempotencyKey === undefined) {
        return
      }
      running.current = true
      setState((current) => ({ ...current, phase: 'confirming', code: null, uncertain: false }))
      void confirmRestriction(
        csrfToken,
        prepared.command,
        prepared.operationId,
        confirmation,
        idempotencyKey,
      ).then((outcome) => {
        running.current = false
        if (outcome.outcome === 'completed') {
          key.current = null
          setState({ ...IDLE, phase: 'completed', completed: outcome.completed })
          reloadList()
          return
        }
        if (outcome.outcome === 'unanswered') {
          // 실행됐을 수 있다. 키를 버리지 않고 대화상자를 열어 둔다 — 같은 문구로 다시 확정하면 같은 요청이다.
          setState((current) => ({
            ...current,
            phase: 'armed',
            uncertain: true,
            uncertainOperationId: prepared.operationId,
          }))
          return
        }
        if (outcome.code === 'CONFIRMATION_MISMATCH') {
          setState((current) => ({ ...current, phase: 'armed', code: outcome.code }))
          return
        }
        key.current = null
        setState((current) => ({
          ...IDLE,
          phase: 'refused',
          code: outcome.code,
          reauthRequired: outcome.code === 'REAUTHENTICATION_REQUIRED',
          uncertainOperationId: current.uncertainOperationId,
          sessionEnded: outcome.status === 401,
        }))
        // 확정 순간 목록이 달라졌다는 답이다 — 같은 제한이 생겼거나, 대상이 사라졌거나, 권한이 회수됐다. 목록을 다시
        // 읽어 화면이 서버의 지금을 보이게 한다.
        if (
          outcome.code === 'ALREADY_RESTRICTED' ||
          outcome.code === 'TARGET_INVALID' ||
          outcome.status === 404
        ) {
          reloadList()
        }
      })
    },
    [csrfToken, reloadList, state.prepared],
  )

  const cancel = useCallback((): void => {
    const prepared = state.prepared
    if (running.current || prepared === null) {
      return
    }
    key.current = null
    setState((current) => ({
      ...IDLE,
      phase: 'cancelled',
      uncertainOperationId: current.uncertainOperationId,
    }))
    // 최선 노력이다. 남은 허가는 5분 뒤 만료되고 서버 무장도 함께 사라진다.
    void cancelRestriction(csrfToken, prepared.command)
  }, [csrfToken, state.prepared])

  return {
    ...state,
    busy: state.phase === 'preparing' || state.phase === 'confirming',
    list,
    reloadList,
    prepare,
    confirm,
    cancel,
  }
}
