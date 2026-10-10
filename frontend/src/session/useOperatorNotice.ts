import { useCallback, useEffect, useRef, useState } from 'react'

import {
  cancelNotice,
  confirmNotice,
  fetchNoticeIncidents,
  prepareNotice,
  type CompletedNotice,
  type NoticeConfirmOutcome,
  type NoticeIncident,
  type NoticeInput,
  type NoticePrepareOutcome,
  type PreparedNotice,
} from './operatorApi'

/**
 * 서비스 공지 발행의 준비 → 확인 → 확정·취소와 사건 목록(Story 3.5).
 *
 * 제한·복구(`useOperatorRestriction`·`useOperatorRecovery`)와 같은 상태 기계다. 준비는 아무것도 쓰지 않는다 — 서버가
 * 사건·덮는 제한·다음 안내 시각을 보고 허가를 발급한 뒤, 덮는 제한에서 계산한 영향·계속 가능 기능의 미리보기와 확인
 * 문구를 준다. 확정은 사용자가 다시 입력한 문구를 보낸다. 멱등 키는 한 입력(사건·상태·덮는 제한·메시지·다음 안내 시각)마다
 * 한 번 만들고 그 준비·확정에 함께 쓴다.
 *
 * <b>응답을 받지 못한 확정</b>은 실행 여부를 단정하지 않는다 — 키를 버리지 않고 대화상자를 열어 둔다. 자동으로 다시 실행하지
 * 않는다. 사건 목록은 운영자임이 확인된 뒤(`ready`) 한 번 읽고, 목록이 서버의 지금과 달라졌을 수 있는 결말(완료·번호 충돌·
 * 닫힌 사건·대상 오류)에 다시 읽는다. 목록의 404는 이 운영자에게 command 권한이 없다는 답이다. 값은 메모리에만 있다.
 */

export type NoticePhase =
  'armed' | 'cancelled' | 'completed' | 'confirming' | 'idle' | 'preparing' | 'refused'

export interface NoticeCommandState {
  readonly phase: NoticePhase
  readonly prepared: PreparedNotice | null
  readonly completed: CompletedNotice | null
  readonly code: string | null
  readonly reauthRequired: boolean
  readonly uncertain: boolean
  readonly uncertainOperationId: string | null
  readonly sessionEnded: boolean
  /** 마지막으로 준비한 공지 상태. 같은 거절 code의 문장을 가른다(복구 공지의 대상 거절). */
  readonly requestedStatus: string | null
}

export type NoticeListStatus = 'checking' | 'failed' | 'loaded' | 'notFound' | 'unauthenticated'

export interface NoticeList {
  readonly status: NoticeListStatus
  readonly items: readonly NoticeIncident[]
  readonly supportContactConfigured: boolean
  /** 한 번이라도 읽었다. 그 뒤의 실패는 목록 자리에서만 말한다. */
  readonly everLoaded: boolean
  readonly loading: boolean
}

export interface OperatorNoticeAccess extends NoticeCommandState {
  readonly busy: boolean
  readonly list: NoticeList
  readonly reloadList: () => void
  readonly prepare: (input: NoticeInput) => void
  readonly confirm: (confirmation: string) => void
  readonly cancel: () => void
}

const IDLE: NoticeCommandState = {
  phase: 'idle',
  prepared: null,
  completed: null,
  code: null,
  reauthRequired: false,
  uncertain: false,
  uncertainOperationId: null,
  sessionEnded: false,
  requestedStatus: null,
}

const CHECKING: NoticeList = {
  status: 'checking',
  items: [],
  supportContactConfigured: false,
  everLoaded: false,
  loading: true,
}

/** 사건 목록을 다시 읽어야 하는 거절. 준비·확정 순간 사건·제한의 상태가 화면과 달랐다는 답이다. */
const LIST_CHANGING_CODES: ReadonlySet<string> = new Set([
  'NOTICE_VERSION_CONFLICT',
  'INCIDENT_CLOSED',
  'TARGET_INVALID',
  'SUPPORT_CONTACT_UNCONFIGURED',
])

export function useOperatorNotice(ready: boolean, csrfToken: string | null): OperatorNoticeAccess {
  const [state, setState] = useState<NoticeCommandState>(IDLE)
  const [list, setList] = useState<NoticeList>(CHECKING)
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
    void fetchNoticeIncidents(controller.signal).then((result) => {
      if (mine !== sequence.current) {
        return
      }
      inFlight.current = null
      setList((current) => {
        switch (result.status) {
          case 'loaded':
            return {
              status: 'loaded',
              items: result.value.items,
              supportContactConfigured: result.value.supportContactConfigured,
              everLoaded: true,
              loading: false,
            }
          case 'notFound':
            // command 권한이 없거나 회수됐다. 보던 사건도 남기지 않는다.
            return { ...CHECKING, status: 'notFound', loading: false }
          case 'unauthenticated':
            return { ...CHECKING, status: 'unauthenticated', loading: false }
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

  /** 다시 조회. 도는 동안 목록 자리가 그 사실을 말한다. */
  const reloadList = useCallback((): void => {
    setList((current) => ({ ...current, loading: true }))
    readList()
  }, [readList])

  const prepare = useCallback(
    (input: NoticeInput): void => {
      if (running.current) {
        return
      }
      running.current = true
      const text = [
        input.incident,
        input.status,
        [...input.covers].sort().join(','),
        input.message,
        input.nextUpdateAt ?? '-',
      ].join('\n')
      if (key.current?.input !== text) {
        key.current = { input: text, value: crypto.randomUUID() }
      }
      const idempotencyKey = key.current.value
      setState({ ...IDLE, phase: 'preparing', requestedStatus: input.status })
      void prepareNotice(csrfToken, input, idempotencyKey)
        // 전송층이 거부로 끝나도 진행 상태에 갇히지 않는다 — 준비는 아무것도 쓰지 않으므로 거절로 접는다.
        .catch((): NoticePrepareOutcome => ({ outcome: 'refused', status: 0, code: null }))
        .then((outcome) => {
          if (outcome.outcome === 'prepared') {
            setState({
              ...IDLE,
              phase: 'armed',
              prepared: outcome.prepared,
              requestedStatus: input.status,
            })
            return
          }
          if (outcome.code === 'IDEMPOTENCY_CONFLICT') {
            key.current = null
          }
          if (outcome.code !== null && LIST_CHANGING_CODES.has(outcome.code)) {
            readList()
          }
          setState({
            ...IDLE,
            phase: 'refused',
            code: outcome.code,
            reauthRequired: outcome.code === 'REAUTHENTICATION_REQUIRED',
            sessionEnded: outcome.status === 401,
            requestedStatus: input.status,
          })
        })
        .finally(() => {
          running.current = false
        })
    },
    [csrfToken, readList],
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
      void confirmNotice(csrfToken, prepared.operationId, confirmation, idempotencyKey)
        // 전송층이 거부로 끝나면 실행 여부를 모른다 — 응답을 받지 못한 확정과 같다.
        .catch((): NoticeConfirmOutcome => ({ outcome: 'unanswered' }))
        .then((outcome) => {
          if (outcome.outcome === 'completed') {
            key.current = null
            setState({ ...IDLE, phase: 'completed', completed: outcome.completed })
            readList()
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
            requestedStatus: current.requestedStatus,
          }))
          if (
            (outcome.code !== null && LIST_CHANGING_CODES.has(outcome.code)) ||
            outcome.status === 404
          ) {
            readList()
          }
        })
        .finally(() => {
          running.current = false
        })
    },
    [csrfToken, readList, state.prepared],
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
    void cancelNotice(csrfToken).catch(() => undefined)
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
