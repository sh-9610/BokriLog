import { useCallback, useRef, useState } from 'react'

import {
  cancelRecovery,
  confirmRecovery,
  prepareRecovery,
  type CompletedRecovery,
  type PreparedRecovery,
  type RecoveryConfirmOutcome,
  type RecoveryInput,
  type RecoveryPrepareOutcome,
} from './operatorApi'

/**
 * 제한 복구의 준비 → 확인 → 확정·취소(Story 3.4).
 *
 * 제한(`useOperatorRestriction`)과 같은 상태 기계다. 준비는 아무것도 열지 않는다 — 서버가 제한이 아직 열려 있는지 보고
 * 허가를 발급한 뒤 다시 열릴 단계·남는 제한·신호 계보의 미리보기와 확인 문구를 준다. 확정은 사용자가 다시 입력한 문구를
 * 보낸다. 멱등 키는 한 입력(제한·원인·검증·증거·배포 버전·조건)마다 한 번 만들고 그 준비·확정에 함께 쓴다.
 *
 * 확정의 결과에는 서버의 커밋 뒤 smoke 결말(`PASSED`·`REVERTED`, 재전송에서 원장에 결말이 아직 없으면 `PENDING`)이
 * 실린다. <b>응답을 받지 못한 확정</b>은 실행 여부를
 * 단정하지 않는다 — 키를 버리지 않고 대화상자를 열어 둔다. 자동으로 다시 실행하지 않는다. 목록이 서버의 지금과 달라졌을
 * 수 있는 결말(완료·닫힌 제한·새 신호·권한 회수)에는 `onListChanged`를 부른다. 값은 메모리에만 있다.
 */

export type RecoveryPhase =
  'armed' | 'cancelled' | 'completed' | 'confirming' | 'idle' | 'preparing' | 'refused'

export interface RecoveryCommandState {
  readonly phase: RecoveryPhase
  readonly prepared: PreparedRecovery | null
  readonly completed: CompletedRecovery | null
  readonly code: string | null
  readonly reauthRequired: boolean
  readonly uncertain: boolean
  readonly uncertainOperationId: string | null
  readonly sessionEnded: boolean
}

export interface OperatorRecoveryAccess extends RecoveryCommandState {
  readonly busy: boolean
  readonly prepare: (input: RecoveryInput) => void
  readonly confirm: (confirmation: string) => void
  readonly cancel: () => void
  readonly reset: () => void
}

const IDLE: RecoveryCommandState = {
  phase: 'idle',
  prepared: null,
  completed: null,
  code: null,
  reauthRequired: false,
  uncertain: false,
  uncertainOperationId: null,
  sessionEnded: false,
}

/** 목록을 다시 읽어야 하는 거절. 확정·준비 순간 제한의 상태가 화면과 달랐다는 답이다. */
const LIST_CHANGING_CODES: ReadonlySet<string> = new Set([
  'RESTRICTION_NOT_ACTIVE',
  'TARGET_INVALID',
  'NEW_SIGNAL_SINCE_PREPARE',
])

export function useOperatorRecovery(
  csrfToken: string | null,
  onListChanged: () => void,
): OperatorRecoveryAccess {
  const [state, setState] = useState<RecoveryCommandState>(IDLE)
  const key = useRef<{ readonly input: string; readonly value: string } | null>(null)
  const running = useRef(false)

  const prepare = useCallback(
    (input: RecoveryInput): void => {
      if (running.current) {
        return
      }
      running.current = true
      const text = [
        input.restrictionId,
        input.reason,
        input.verification,
        input.evidenceRef,
        input.deployVersion,
        input.conditions.join(','),
      ].join('\n')
      if (key.current?.input !== text) {
        key.current = { input: text, value: crypto.randomUUID() }
      }
      const idempotencyKey = key.current.value
      setState({ ...IDLE, phase: 'preparing' })
      void prepareRecovery(csrfToken, input, idempotencyKey)
        // 전송층이 거부로 끝나도 진행 상태에 갇히지 않는다 — 준비는 아무것도 바꾸지 않으므로 거절로 접는다.
        .catch((): RecoveryPrepareOutcome => ({ outcome: 'refused', status: 0, code: null }))
        .then((outcome) => {
          if (outcome.outcome === 'prepared') {
            setState({ ...IDLE, phase: 'armed', prepared: outcome.prepared })
            return
          }
          if (outcome.code === 'IDEMPOTENCY_CONFLICT') {
            key.current = null
          }
          if (outcome.code !== null && LIST_CHANGING_CODES.has(outcome.code)) {
            onListChanged()
          }
          setState({
            ...IDLE,
            phase: 'refused',
            code: outcome.code,
            reauthRequired: outcome.code === 'REAUTHENTICATION_REQUIRED',
            sessionEnded: outcome.status === 401,
          })
        })
        .finally(() => {
          running.current = false
        })
    },
    [csrfToken, onListChanged],
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
      void confirmRecovery(csrfToken, prepared.operationId, confirmation, idempotencyKey)
        // 전송층이 거부로 끝나면 실행 여부를 모른다 — 응답을 받지 못한 확정과 같다.
        .catch((): RecoveryConfirmOutcome => ({ outcome: 'unanswered' }))
        .then((outcome) => {
          if (outcome.outcome === 'completed') {
            key.current = null
            // smoke 결말이 원장에 아직 없으면(PENDING) operation 조회로 이어 가도록 그 operation을 남긴다.
            setState({
              ...IDLE,
              phase: 'completed',
              completed: outcome.completed,
              uncertainOperationId:
                outcome.completed.smoke.status === 'PENDING' ? outcome.completed.operationId : null,
            })
            onListChanged()
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
          if (
            (outcome.code !== null && LIST_CHANGING_CODES.has(outcome.code)) ||
            outcome.status === 404
          ) {
            onListChanged()
          }
        })
        .finally(() => {
          running.current = false
        })
    },
    [csrfToken, onListChanged, state.prepared],
  )

  const cancel = useCallback((): void => {
    if (running.current || state.prepared === null) {
      return
    }
    key.current = null
    setState((current) => ({
      ...IDLE,
      phase: 'cancelled',
      uncertainOperationId: current.uncertainOperationId,
    }))
    // 최선 노력이다. 남은 허가는 5분 뒤 만료되고 서버 무장도 함께 사라진다.
    void cancelRecovery(csrfToken).catch(() => undefined)
  }, [csrfToken, state.prepared])

  /** 다른 제한을 골랐다. 앞 제한의 결과·거절·키를 다음 제한에 남기지 않는다. 진행 중이면 아무것도 하지 않는다. */
  const reset = useCallback((): void => {
    if (running.current) {
      return
    }
    key.current = null
    setState(IDLE)
  }, [])

  return {
    ...state,
    busy: state.phase === 'preparing' || state.phase === 'confirming',
    prepare,
    confirm,
    cancel,
    reset,
  }
}
