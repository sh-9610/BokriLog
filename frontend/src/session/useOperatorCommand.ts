import { useCallback, useRef, useState } from 'react'

import {
  cancelCommand,
  confirmCommand,
  prepareCommand,
  type CommandBody,
  type CommandConfirmOutcome,
  type CommandPrepareOutcome,
} from './operatorApi'

/**
 * 운영 command의 준비 → 확인 → 확정·취소 상태 기계(Epic 3 회고 AI-5). 감사 내보내기·제한·복구·공지·문의 다섯 화면이 이것
 * 하나를 쓰고, 각 화면은 command 이름·본문·해석기·목록을 바꾸는 code만 정한다({@link OperatorCommandSpec}).
 *
 * 준비는 아무것도 실행하지 않는다 — 서버가 허가를 발급하고 무장한 뒤 확인 문구(와 미리보기)를 준다. 확정은 사용자가 다시
 * 입력한 문구를 보낸다. 멱등 키는 한 입력마다 한 번 만들고(`keyOf`가 같으면 같은 키) 그 준비·확정에 함께 쓴다. 끝난
 * operation의 키(`IDEMPOTENCY_CONFLICT`)와 결말이 난 확정 뒤에는 다음 준비가 새 키를 만든다.
 *
 * <b>응답을 받지 못한 확정</b>(네트워크 실패·모든 5xx)은 실행 여부를 단정하지 않는다. 대화상자를 열어 둔 채 그 사실을
 * 말하고(`uncertain`) 키를 버리지 않는다 — 사용자가 같은 문구로 다시 확정하면 같은 키로 가고 서버가 같은 결과를
 * 재생한다. 이 훅은 그 재시도를 <b>자동으로 하지 않는다</b>. 문구 불일치(`CONFIRMATION_MISMATCH`)는 허가를 소비하지
 * 않으므로 무장을 유지한다.
 *
 * 목록이 서버의 지금과 달라졌을 수 있는 결말(완료, 그 command가 정한 거절 code, 확정의 404)에는 `onListChanged`를 부른다.
 * 401은 세션이 끝났다는 답이다(`sessionEnded`). 키와 결과는 메모리에만 있다 — 브라우저 저장소·URL에 쓰지 않는다.
 */

export type OperatorCommandPhase =
  'armed' | 'cancelled' | 'completed' | 'confirming' | 'idle' | 'preparing' | 'refused'

export interface OperatorCommandState<I, P, C> {
  readonly phase: OperatorCommandPhase
  /** 열린 확인 대화상자의 내용. 준비가 성공한 뒤 완료·거절·취소 전까지만 있다. */
  readonly prepared: P | null
  readonly completed: C | null
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
  /** 마지막으로 준비한 입력. 완료·취소 뒤에는 없다(대화상자·거절 문장이 그 입력을 다시 본다). */
  readonly input: I | null
}

/**
 * command 하나의 설정. 화면 밖(모듈 수준)에서 한 번 만든다 — 훅의 콜백이 그 정체성에 기댄다.
 *
 * @typeParam I 준비 입력
 * @typeParam P 준비 응답(확인 대화상자의 내용). `operationId`가 있어야 한다
 * @typeParam C 확정 응답
 */
export interface OperatorCommandSpec<I, P extends { readonly operationId: string }, C> {
  /** 그 입력의 command 이름. 제한 넷은 입력이 고른다. */
  readonly commandOf: (input: I) => string
  /** 준비 본문. 수행자를 싣지 않는다. */
  readonly bodyOf: (input: I) => object
  /** 멱등 키를 가르는 입력 글. 같으면 같은 키를 다시 쓴다. */
  readonly keyOf: (input: I) => string
  /** 준비 응답의 해석기. 받지 않으면 `null`(거절로 접는다). */
  readonly preparedOf: (body: CommandBody, input: I) => P | null
  /** 확정 응답의 해석기. 받지 않으면 `null`(실행 여부를 모른다). */
  readonly completedOf: (body: CommandBody, operationId: string) => C | null
  /** 확정의 시한. 없으면 기본 요청 시한이다. */
  readonly confirmTimeoutMs?: number
  /** 준비 거절 가운데 목록을 다시 읽게 하는 code. */
  readonly prepareListCodes?: ReadonlySet<string>
  /** 확정 거절 가운데 목록을 다시 읽게 하는 code. 확정의 404(권한 회수)는 언제나 다시 읽는다. */
  readonly confirmListCodes?: ReadonlySet<string>
  /** 완료 뒤에도 조회 칸의 기본값으로 남길 operation — 결말을 아직 모르는 완료(복구의 smoke `PENDING`). */
  readonly pendingOperationOf?: (completed: C) => string | null
}

export interface OperatorCommandCallbacks<I, C> {
  /** 목록이 서버의 지금과 달라졌을 수 있다. */
  readonly onListChanged?: (input: I) => void
  /** 확정이 완료됐다(재생 포함). */
  readonly onCompleted?: (completed: C) => void
}

export interface OperatorCommandAccess<I, P, C> extends OperatorCommandState<I, P, C> {
  readonly busy: boolean
  readonly prepare: (input: I) => void
  readonly confirm: (confirmation: string) => void
  readonly cancel: () => void
  /** 다른 대상을 골랐다. 앞 대상의 결과·거절·키를 다음에 남기지 않는다. 진행 중이면 아무것도 하지 않는다. */
  readonly reset: () => void
}

const NO_CODES: ReadonlySet<string> = new Set()

export function useOperatorCommand<I, P extends { readonly operationId: string }, C>(
  spec: OperatorCommandSpec<I, P, C>,
  csrfToken: string | null,
  callbacks: OperatorCommandCallbacks<I, C> = {},
): OperatorCommandAccess<I, P, C> {
  const [state, setState] = useState<OperatorCommandState<I, P, C>>(idle)
  /** 지금 입력의 멱등 키와 그 입력. 입력이 바뀌거나 operation이 끝나면 새로 만든다. */
  const key = useRef<{ readonly input: string; readonly value: string } | null>(null)
  /** 중복 제출 차단. `aria-disabled`는 알려 주기만 하고 막지 않는다. */
  const running = useRef(false)
  const { onListChanged, onCompleted } = callbacks

  const prepare = useCallback(
    (input: I): void => {
      if (running.current) {
        return
      }
      running.current = true
      const text = spec.keyOf(input)
      if (key.current?.input !== text) {
        key.current = { input: text, value: crypto.randomUUID() }
      }
      const idempotencyKey = key.current.value
      setState({ ...idle(), phase: 'preparing', input })
      void prepareCommand(
        csrfToken,
        spec.commandOf(input),
        spec.bodyOf(input),
        idempotencyKey,
        (body) => spec.preparedOf(body, input),
      )
        // 전송층이 거부로 끝나도 진행 상태에 갇히지 않는다 — 준비는 아무것도 바꾸지 않으므로 거절로 접는다.
        .catch((): CommandPrepareOutcome<P> => ({ outcome: 'refused', status: 0, code: null }))
        .then((outcome) => {
          if (outcome.outcome === 'prepared') {
            setState({ ...idle(), phase: 'armed', prepared: outcome.prepared, input })
            return
          }
          if (outcome.code === 'IDEMPOTENCY_CONFLICT') {
            // 이 키는 끝난 operation의 것이다. 다음 준비는 새 키로 한다.
            key.current = null
          }
          if (outcome.code !== null && (spec.prepareListCodes ?? NO_CODES).has(outcome.code)) {
            onListChanged?.(input)
          }
          setState({
            ...idle(),
            phase: 'refused',
            code: outcome.code,
            reauthRequired: outcome.code === 'REAUTHENTICATION_REQUIRED',
            sessionEnded: outcome.status === 401,
            input,
          })
        })
        .finally(() => {
          running.current = false
        })
    },
    [csrfToken, onListChanged, spec],
  )

  const confirm = useCallback(
    (confirmation: string): void => {
      const prepared = state.prepared
      const input = state.input
      const idempotencyKey = key.current?.value
      if (running.current || prepared === null || input === null || idempotencyKey === undefined) {
        return
      }
      running.current = true
      setState((current) => ({ ...current, phase: 'confirming', code: null, uncertain: false }))
      void confirmCommand(
        csrfToken,
        spec.commandOf(input),
        prepared.operationId,
        confirmation,
        idempotencyKey,
        spec.completedOf,
        spec.confirmTimeoutMs,
      )
        // 전송층이 거부로 끝나면 실행 여부를 모른다 — 응답을 받지 못한 확정과 같다.
        .catch((): CommandConfirmOutcome<C> => ({ outcome: 'unanswered' }))
        .then((outcome) => {
          if (outcome.outcome === 'completed') {
            key.current = null
            setState({
              ...idle(),
              phase: 'completed',
              completed: outcome.completed,
              uncertainOperationId: spec.pendingOperationOf?.(outcome.completed) ?? null,
            })
            onCompleted?.(outcome.completed)
            onListChanged?.(input)
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
            ...idle(),
            phase: 'refused',
            code: outcome.code,
            reauthRequired: outcome.code === 'REAUTHENTICATION_REQUIRED',
            uncertainOperationId: current.uncertainOperationId,
            sessionEnded: outcome.status === 401,
            input: current.input,
          }))
          // 확정 순간 목록이 달라졌다는 답이다(대상이 사라졌거나 상태가 바뀌었거나 권한이 회수됐다).
          if (
            (outcome.code !== null && (spec.confirmListCodes ?? NO_CODES).has(outcome.code)) ||
            outcome.status === 404
          ) {
            onListChanged?.(input)
          }
        })
        .finally(() => {
          running.current = false
        })
    },
    [csrfToken, onCompleted, onListChanged, spec, state.input, state.prepared],
  )

  const cancel = useCallback((): void => {
    const input = state.input
    if (running.current || state.prepared === null || input === null) {
      return
    }
    key.current = null
    setState((current) => ({
      ...idle(),
      phase: 'cancelled',
      // 응답을 받지 못한 확정 뒤의 닫기라면 조회 칸의 기본값은 그 operation으로 남는다.
      uncertainOperationId: current.uncertainOperationId,
    }))
    // 최선 노력이다. 남은 허가는 5분 뒤 만료되고 서버 무장도 함께 사라진다.
    void cancelCommand(csrfToken, spec.commandOf(input)).catch(() => undefined)
  }, [csrfToken, spec, state.input, state.prepared])

  const reset = useCallback((): void => {
    if (running.current) {
      return
    }
    key.current = null
    setState(idle())
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

function idle<I, P, C>(): OperatorCommandState<I, P, C> {
  return {
    phase: 'idle',
    prepared: null,
    completed: null,
    code: null,
    reauthRequired: false,
    uncertain: false,
    uncertainOperationId: null,
    sessionEnded: false,
    input: null,
  }
}
