import { useCallback, useRef, useState } from 'react'

import {
  cancelExport,
  confirmExport,
  prepareExport,
  type CompletedExport,
  type PreparedExport,
} from './operatorApi'

/**
 * 감사 원장 내보내기의 준비 → 확인 → 확정·취소(Story 3.1).
 *
 * 준비는 아무것도 실행하지 않는다 — 서버가 허가를 발급하고 무장한 뒤 확인 문구를 준다. 확정은 사용자가 다시 입력한 문구를
 * 보낸다. 멱등 키는 한 입력(기간·사유)마다 한 번 만들고 그 준비·확정에 함께 쓴다.
 *
 * <b>응답을 받지 못한 확정</b>(네트워크 실패·모든 5xx)은 실행 여부를 단정하지 않는다. 대화상자를 열어 둔 채 그 사실을
 * 말하고 operation 조회를 안내하며, 키를 버리지 않는다 — 사용자가 같은 문구로 다시 확정하면 같은 키로 가고 서버가 같은
 * 결과를 재생한다. 이 훅은 그 재시도를 <b>자동으로 하지 않는다</b>(AC-6).
 *
 * 401은 세션이 끝났다는 답이다. 화면이 만료 표지와 함께 진입 화면으로 보낸다(`sessionEnded`). 키와 결과는 메모리에만
 * 있다 — 브라우저 저장소·URL에 쓰지 않는다.
 */

export type ExportPhase =
  'armed' | 'cancelled' | 'completed' | 'confirming' | 'idle' | 'preparing' | 'refused'

export interface ExportState {
  readonly phase: ExportPhase
  /** 열린 확인 대화상자의 내용. 준비가 성공한 뒤 완료·거절·취소 전까지만 있다. */
  readonly prepared: PreparedExport | null
  readonly completed: CompletedExport | null
  /** 거절 code. 대화상자 안의 문구 불일치도 여기 실린다. */
  readonly code: string | null
  /** 서버가 최근 본인 확인을 요구했다. */
  readonly reauthRequired: boolean
  /** 마지막 확정의 응답을 받지 못했다. 대화상자가 그 사실을 말한다. */
  readonly uncertain: boolean
  /** 실행 여부를 알 수 없는 operation. 조회 칸의 기본값이다. */
  readonly uncertainOperationId: string | null
  /** 서버가 세션이 끝났다고 답했다(401). */
  readonly sessionEnded: boolean
}

export interface OperatorExportAccess extends ExportState {
  readonly busy: boolean
  readonly prepare: (target: string, reason: string) => void
  readonly confirm: (confirmation: string) => void
  readonly cancel: () => void
}

const IDLE: ExportState = {
  phase: 'idle',
  prepared: null,
  completed: null,
  code: null,
  reauthRequired: false,
  uncertain: false,
  uncertainOperationId: null,
  sessionEnded: false,
}

export function useOperatorExport(
  csrfToken: string | null,
  onCompleted: () => void,
): OperatorExportAccess {
  const [state, setState] = useState<ExportState>(IDLE)
  /** 지금 입력의 멱등 키와 그 입력. 입력이 바뀌거나 operation이 끝나면 새로 만든다. */
  const key = useRef<{ readonly input: string; readonly value: string } | null>(null)
  /** 중복 제출 차단. `aria-disabled`는 알려 주기만 하고 막지 않는다. */
  const running = useRef(false)

  const prepare = useCallback(
    (target: string, reason: string): void => {
      if (running.current) {
        return
      }
      running.current = true
      const input = `${target}\n${reason}`
      if (key.current?.input !== input) {
        key.current = { input, value: crypto.randomUUID() }
      }
      const idempotencyKey = key.current.value
      setState({ ...IDLE, phase: 'preparing' })
      void prepareExport(csrfToken, target, reason, idempotencyKey).then((outcome) => {
        running.current = false
        if (outcome.outcome === 'prepared') {
          setState({ ...IDLE, phase: 'armed', prepared: outcome.prepared })
          return
        }
        if (outcome.code === 'IDEMPOTENCY_CONFLICT') {
          // 이 키는 끝난 operation의 것이다. 다음 준비는 새 키로 한다.
          key.current = null
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
    [csrfToken],
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
      void confirmExport(csrfToken, prepared.operationId, confirmation, idempotencyKey).then(
        (outcome) => {
          running.current = false
          if (outcome.outcome === 'completed') {
            key.current = null
            setState({ ...IDLE, phase: 'completed', completed: outcome.completed })
            onCompleted()
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
            // 허가는 소비되지 않았다. 대화상자를 열어 둔 채 문구를 고치게 한다.
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
        },
      )
    },
    [csrfToken, onCompleted, state.prepared],
  )

  const cancel = useCallback((): void => {
    if (running.current) {
      return
    }
    key.current = null
    setState((current) => ({
      ...IDLE,
      phase: 'cancelled',
      // 응답을 받지 못한 확정 뒤의 닫기라면 조회 칸의 기본값은 그 operation으로 남는다.
      uncertainOperationId: current.uncertainOperationId,
    }))
    // 최선 노력이다. 남은 허가는 5분 뒤 만료되고 서버 무장도 함께 사라진다.
    void cancelExport(csrfToken)
  }, [csrfToken])

  return {
    ...state,
    busy: state.phase === 'preparing' || state.phase === 'confirming',
    prepare,
    confirm,
    cancel,
  }
}
