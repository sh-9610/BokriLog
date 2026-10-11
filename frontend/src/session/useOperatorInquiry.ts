import { useCallback, useEffect, useRef, useState } from 'react'

import {
  cancelInquiryUpdate,
  confirmInquiryUpdate,
  fetchInquiries,
  fetchInquiryDetail,
  fetchInquiryMetrics,
  prepareInquiryUpdate,
  type CompletedInquiryUpdate,
  type InquiryConfirmOutcome,
  type InquiryMetrics,
  type InquiryPrepareOutcome,
  type InquiryUpdateInput,
  type OperatorInquiry,
  type OperatorInquiryDetail,
  type PreparedInquiryUpdate,
  type ReadResult,
} from './operatorApi'

/**
 * 문의 처리의 준비 → 확인 → 확정·취소와 문의 목록·상세·집계(Story 3.6).
 *
 * 공지·제한·복구(`useOperatorNotice`·`useOperatorRestriction`·`useOperatorRecovery`)와 같은 상태 기계다. 준비는 아무것도
 * 쓰지 않는다 — 서버가 지금 상태에서의 전이를 보고 허가를 발급한 뒤 미리보기와 확인 문구를 준다. 확정은 사용자가 다시
 * 입력한 문구를 보낸다. 멱등 키는 한 입력(reference·상태·사유·답변)마다 한 번 만들고 그 준비·확정에 함께 쓴다.
 *
 * <b>응답을 받지 못한 확정</b>은 실행 여부를 단정하지 않는다 — 키를 버리지 않고 대화상자를 열어 둔다. 자동으로 다시
 * 실행하지 않는다. 목록은 운영자임이 확인된 뒤(`ready`) 한 번 읽고, 목록이 서버의 지금과 달라졌을 수 있는 결말(완료·전이
 * 불가·대상 없음)에 다시 읽는다. 상세는 운영자가 고른 문의 하나만 읽는다. 목록의 404는 command 권한이 없다는 답이다. 값은
 * 메모리에만 있다.
 */

export type InquiryPhase =
  'armed' | 'cancelled' | 'completed' | 'confirming' | 'idle' | 'preparing' | 'refused'

export interface InquiryCommandState {
  readonly phase: InquiryPhase
  readonly prepared: PreparedInquiryUpdate | null
  readonly completed: CompletedInquiryUpdate | null
  readonly code: string | null
  readonly reauthRequired: boolean
  readonly uncertain: boolean
  readonly uncertainOperationId: string | null
  readonly sessionEnded: boolean
  /** 준비에 실은 답변. 대화상자가 미리 보인다(서버는 답변 원문을 되돌려 주지 않는다). */
  readonly answer: string | null
}

export type InquiryListStatus = 'checking' | 'failed' | 'loaded' | 'notFound' | 'unauthenticated'

export interface InquiryList {
  readonly status: InquiryListStatus
  readonly items: readonly OperatorInquiry[]
  /** 한 번이라도 읽었다. 그 뒤의 실패는 목록 자리에서만 말한다. */
  readonly everLoaded: boolean
  readonly loading: boolean
}

export type InquiryDetailView =
  | { readonly status: 'idle' }
  | { readonly status: 'loading'; readonly reference: string }
  | { readonly status: 'loaded'; readonly reference: string; readonly value: OperatorInquiryDetail }
  | { readonly status: 'notFound' | 'failed'; readonly reference: string }

export type InquiryMetricsView =
  | { readonly status: 'idle' }
  | { readonly status: 'loading' }
  | { readonly status: 'loaded'; readonly value: InquiryMetrics }
  | { readonly status: 'failed' }

export interface OperatorInquiryAccess extends InquiryCommandState {
  readonly busy: boolean
  readonly list: InquiryList
  readonly reloadList: () => void
  /** 목록의 상태 필터. `null`이면 전부(미해결이 먼저)다. */
  readonly listFilter: string | null
  readonly filterList: (status: string | null) => void
  readonly detail: InquiryDetailView
  readonly select: (reference: string) => void
  readonly metrics: InquiryMetricsView
  readonly loadMetrics: (from: string, to: string) => void
  readonly prepare: (input: InquiryUpdateInput) => void
  readonly confirm: (confirmation: string) => void
  readonly cancel: () => void
}

const IDLE: InquiryCommandState = {
  phase: 'idle',
  prepared: null,
  completed: null,
  code: null,
  reauthRequired: false,
  uncertain: false,
  uncertainOperationId: null,
  sessionEnded: false,
  answer: null,
}

const CHECKING: InquiryList = { status: 'checking', items: [], everLoaded: false, loading: true }

/** 목록·상세를 다시 읽어야 하는 거절. 준비·확정 순간 문의의 상태가 화면과 달랐다는 답이다. */
const LIST_CHANGING_CODES: ReadonlySet<string> = new Set([
  'INQUIRY_TRANSITION_INVALID',
  'TARGET_INVALID',
])

export function useOperatorInquiry(
  ready: boolean,
  csrfToken: string | null,
): OperatorInquiryAccess {
  const [state, setState] = useState<InquiryCommandState>(IDLE)
  const [list, setList] = useState<InquiryList>(CHECKING)
  const [detail, setDetail] = useState<InquiryDetailView>({ status: 'idle' })
  const [metrics, setMetrics] = useState<InquiryMetricsView>({ status: 'idle' })
  const key = useRef<{ readonly input: string; readonly value: string } | null>(null)
  const running = useRef(false)
  const sequence = useRef(0)
  const inFlight = useRef<AbortController | null>(null)
  const detailSequence = useRef(0)
  const detailFlight = useRef<AbortController | null>(null)
  const metricsSequence = useRef(0)
  const metricsFlight = useRef<AbortController | null>(null)
  const [listFilter, setListFilter] = useState<string | null>(null)
  const filter = useRef<string | null>(null)

  const readList = useCallback((): void => {
    inFlight.current?.abort()
    const controller = new AbortController()
    inFlight.current = controller
    sequence.current += 1
    const mine = sequence.current
    void fetchInquiries(controller.signal, filter.current)
      // 전송층이 거부로 끝나도 읽는 중에 갇히지 않는다 — 실패로 접는다.
      .catch((): ReadResult<readonly OperatorInquiry[]> => ({ status: 'failed', code: null }))
      .then((result) => {
        if (mine !== sequence.current) {
          return
        }
        inFlight.current = null
        setList((current) => {
          switch (result.status) {
            case 'loaded':
              return { status: 'loaded', items: result.value, everLoaded: true, loading: false }
            case 'notFound':
              // command 권한이 없거나 회수됐다. 보던 문의도 남기지 않는다.
              return { ...CHECKING, status: 'notFound', loading: false }
            case 'unauthenticated':
              return { ...CHECKING, status: 'unauthenticated', loading: false }
            case 'failed':
              // 마지막으로 읽은 목록을 버리지 않는다. 실패는 목록 자리에서만 말한다.
              return { ...current, status: 'failed', loading: false }
          }
        })
        if (result.status === 'notFound' || result.status === 'unauthenticated') {
          setDetail({ status: 'idle' })
        }
      })
  }, [])

  const readDetail = useCallback((reference: string): void => {
    detailFlight.current?.abort()
    const controller = new AbortController()
    detailFlight.current = controller
    detailSequence.current += 1
    const mine = detailSequence.current
    setDetail({ status: 'loading', reference })
    void fetchInquiryDetail(reference, controller.signal)
      .catch((): ReadResult<OperatorInquiryDetail> => ({ status: 'failed', code: null }))
      .then((result) => {
        if (mine !== detailSequence.current) {
          return
        }
        detailFlight.current = null
        if (result.status === 'loaded') {
          setDetail({ status: 'loaded', reference, value: result.value })
        } else if (result.status === 'notFound') {
          setDetail({ status: 'notFound', reference })
        } else {
          setDetail({ status: 'failed', reference })
        }
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
      detailFlight.current?.abort()
      metricsFlight.current?.abort()
      sequence.current += 1
      detailSequence.current += 1
      metricsSequence.current += 1
    }
  }, [ready, readList])

  const reloadList = useCallback((): void => {
    setList((current) => ({ ...current, loading: true }))
    readList()
  }, [readList])

  /** 상태 필터를 바꾸고 목록을 다시 읽는다. 상한 밖의 미해결 문의도 필터로 연다. */
  const filterList = useCallback(
    (status: string | null): void => {
      filter.current = status
      setListFilter(status)
      setList((current) => ({ ...current, loading: true }))
      readList()
    },
    [readList],
  )

  const select = useCallback(
    (reference: string): void => {
      readDetail(reference)
    },
    [readDetail],
  )

  const loadMetrics = useCallback((from: string, to: string): void => {
    metricsFlight.current?.abort()
    const controller = new AbortController()
    metricsFlight.current = controller
    metricsSequence.current += 1
    const mine = metricsSequence.current
    setMetrics({ status: 'loading' })
    void fetchInquiryMetrics(from, to, controller.signal)
      .catch((): ReadResult<InquiryMetrics> => ({ status: 'failed', code: null }))
      .then((result) => {
        if (mine !== metricsSequence.current) {
          return
        }
        metricsFlight.current = null
        setMetrics(
          result.status === 'loaded'
            ? { status: 'loaded', value: result.value }
            : { status: 'failed' },
        )
      })
  }, [])

  /** 결말 뒤 목록과 (보고 있던) 상세를 다시 읽는다. */
  const refreshAfter = useCallback(
    (reference: string): void => {
      readList()
      if (detailSequence.current > 0) {
        readDetail(reference)
      }
    },
    [readDetail, readList],
  )

  const prepare = useCallback(
    (input: InquiryUpdateInput): void => {
      if (running.current) {
        return
      }
      running.current = true
      const text = [input.reference, input.status, input.reason, input.answer ?? '-'].join('\n')
      if (key.current?.input !== text) {
        key.current = { input: text, value: crypto.randomUUID() }
      }
      const idempotencyKey = key.current.value
      setState({ ...IDLE, phase: 'preparing', answer: input.answer })
      void prepareInquiryUpdate(csrfToken, input, idempotencyKey)
        // 전송층이 거부로 끝나도 진행 상태에 갇히지 않는다 — 준비는 아무것도 쓰지 않으므로 거절로 접는다.
        .catch((): InquiryPrepareOutcome => ({ outcome: 'refused', status: 0, code: null }))
        .then((outcome) => {
          if (outcome.outcome === 'prepared') {
            setState({ ...IDLE, phase: 'armed', prepared: outcome.prepared, answer: input.answer })
            return
          }
          if (outcome.code === 'IDEMPOTENCY_CONFLICT') {
            key.current = null
          }
          if (outcome.code !== null && LIST_CHANGING_CODES.has(outcome.code)) {
            refreshAfter(input.reference)
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
    [csrfToken, refreshAfter],
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
      void confirmInquiryUpdate(csrfToken, prepared.operationId, confirmation, idempotencyKey)
        // 전송층이 거부로 끝나면 실행 여부를 모른다 — 응답을 받지 못한 확정과 같다.
        .catch((): InquiryConfirmOutcome => ({ outcome: 'unanswered' }))
        .then((outcome) => {
          if (outcome.outcome === 'completed') {
            key.current = null
            setState({ ...IDLE, phase: 'completed', completed: outcome.completed })
            refreshAfter(prepared.target)
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
            refreshAfter(prepared.target)
          }
        })
        .finally(() => {
          running.current = false
        })
    },
    [csrfToken, refreshAfter, state.prepared],
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
    void cancelInquiryUpdate(csrfToken).catch(() => undefined)
  }, [csrfToken, state.prepared])

  return {
    ...state,
    busy: state.phase === 'preparing' || state.phase === 'confirming',
    list,
    reloadList,
    listFilter,
    filterList,
    detail,
    select,
    metrics,
    loadMetrics,
    prepare,
    confirm,
    cancel,
  }
}
