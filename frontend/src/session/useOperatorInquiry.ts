import { useCallback, useEffect, useRef, useState } from 'react'

import { UPDATE_INQUIRY_COMMAND } from '../content/operator'
import {
  completedInquiryUpdateOf,
  fetchInquiries,
  fetchInquiryDetail,
  fetchInquiryMetrics,
  inquiryUpdateBodyOf,
  preparedInquiryUpdateOf,
  type CompletedInquiryUpdate,
  type InquiryMetrics,
  type InquiryUpdateInput,
  type OperatorInquiry,
  type OperatorInquiryDetail,
  type PreparedInquiryUpdate,
  type ReadResult,
} from './operatorApi'
import {
  useOperatorCommand,
  type OperatorCommandAccess,
  type OperatorCommandPhase,
  type OperatorCommandSpec,
} from './useOperatorCommand'
import { useSequencedRead } from './useSequencedRead'

/**
 * 문의 처리의 준비 → 확인 → 확정·취소와 문의 목록·상세·집계(Story 3.6). 상태 기계는 공통 훅(`useOperatorCommand`)이다.
 *
 * 준비는 아무것도 쓰지 않는다 — 서버가 지금 상태에서의 전이를 보고 허가를 발급한 뒤 미리보기와 확인 문구를 준다. 멱등 키는
 * 한 입력(reference·상태·사유·답변)마다 한 번이다. 목록은 운영자임이 확인된 뒤(`ready`) 한 번 읽고, 목록이 서버의 지금과
 * 달라졌을 수 있는 결말(완료·전이 불가·대상 없음)에 목록과 (보고 있던) 상세를 다시 읽는다. 상세는 운영자가 고른 문의 하나만
 * 읽는다. 목록의 404는 command 권한이 없다는 답이다. 값은 메모리에만 있다.
 */

export type InquiryPhase = OperatorCommandPhase

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

export interface OperatorInquiryAccess extends OperatorCommandAccess<
  InquiryUpdateInput,
  PreparedInquiryUpdate,
  CompletedInquiryUpdate
> {
  /** 준비에 실은 답변. 대화상자가 미리 보인다(서버는 답변 원문을 되돌려 주지 않는다). */
  readonly answer: string | null
  readonly list: InquiryList
  readonly reloadList: () => void
  /** 목록의 상태 필터. `null`이면 전부(미해결이 먼저)다. */
  readonly listFilter: string | null
  readonly filterList: (status: string | null) => void
  readonly detail: InquiryDetailView
  readonly select: (reference: string) => void
  readonly metrics: InquiryMetricsView
  readonly loadMetrics: (from: string, to: string) => void
}

/** 준비한 답변을 보여 주는 단계. */
const ANSWER_PHASES: ReadonlySet<string> = new Set(['preparing', 'armed', 'confirming'])

const CHECKING: InquiryList = { status: 'checking', items: [], everLoaded: false, loading: true }

/** 목록·상세를 다시 읽어야 하는 거절. 준비·확정 순간 문의의 상태가 화면과 달랐다는 답이다. */
const LIST_CHANGING_CODES: ReadonlySet<string> = new Set([
  'INQUIRY_TRANSITION_INVALID',
  'TARGET_INVALID',
])

const INQUIRY: OperatorCommandSpec<
  InquiryUpdateInput,
  PreparedInquiryUpdate,
  CompletedInquiryUpdate
> = {
  commandOf: () => UPDATE_INQUIRY_COMMAND,
  bodyOf: inquiryUpdateBodyOf,
  keyOf: (input) => [input.reference, input.status, input.reason, input.answer ?? '-'].join('\n'),
  preparedOf: (body, input) => preparedInquiryUpdateOf(body, input.reference),
  completedOf: completedInquiryUpdateOf,
  prepareListCodes: LIST_CHANGING_CODES,
  confirmListCodes: LIST_CHANGING_CODES,
}

export function useOperatorInquiry(
  ready: boolean,
  csrfToken: string | null,
): OperatorInquiryAccess {
  const [list, setList] = useState<InquiryList>(CHECKING)
  const [detail, setDetail] = useState<InquiryDetailView>({ status: 'idle' })
  const [metrics, setMetrics] = useState<InquiryMetricsView>({ status: 'idle' })
  const listReads = useSequencedRead()
  const detailReads = useSequencedRead()
  const metricsReads = useSequencedRead()
  /** 상세를 한 번이라도 읽었는가. 결말 뒤 상세를 다시 읽을지 가른다. */
  const detailOpened = useRef(false)
  const [listFilter, setListFilter] = useState<string | null>(null)
  const filter = useRef<string | null>(null)

  const readList = useCallback((): void => {
    const ticket = listReads.begin()
    void fetchInquiries(ticket.signal, filter.current)
      // 전송층이 거부로 끝나도 읽는 중에 갇히지 않는다 — 실패로 접는다.
      .catch((): ReadResult<readonly OperatorInquiry[]> => ({ status: 'failed', code: null }))
      .then((result) => {
        if (!ticket.settle()) {
          return
        }
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
  }, [listReads])

  const readDetail = useCallback(
    (reference: string): void => {
      const ticket = detailReads.begin()
      detailOpened.current = true
      setDetail({ status: 'loading', reference })
      void fetchInquiryDetail(reference, ticket.signal)
        .catch((): ReadResult<OperatorInquiryDetail> => ({ status: 'failed', code: null }))
        .then((result) => {
          if (!ticket.settle()) {
            return
          }
          if (result.status === 'loaded') {
            setDetail({ status: 'loaded', reference, value: result.value })
          } else if (result.status === 'notFound') {
            setDetail({ status: 'notFound', reference })
          } else {
            setDetail({ status: 'failed', reference })
          }
        })
    },
    [detailReads],
  )

  // 첫 읽기는 게이트가 열린 순간 한 번이다. 요청 시작은 커밋이 보여 주는 결과가 아니므로 passive effect다.
  useEffect(() => {
    if (!ready) {
      return
    }
    readList()
    return () => {
      listReads.stop()
      detailReads.stop()
      metricsReads.stop()
    }
  }, [ready, readList, listReads, detailReads, metricsReads])

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

  const loadMetrics = useCallback(
    (from: string, to: string): void => {
      const ticket = metricsReads.begin()
      setMetrics({ status: 'loading' })
      void fetchInquiryMetrics(from, to, ticket.signal)
        .catch((): ReadResult<InquiryMetrics> => ({ status: 'failed', code: null }))
        .then((result) => {
          if (!ticket.settle()) {
            return
          }
          setMetrics(
            result.status === 'loaded'
              ? { status: 'loaded', value: result.value }
              : { status: 'failed' },
          )
        })
    },
    [metricsReads],
  )

  /** 결말 뒤 목록과 (보고 있던) 상세를 다시 읽는다. */
  const refreshAfter = useCallback(
    (input: InquiryUpdateInput): void => {
      readList()
      if (detailOpened.current) {
        readDetail(input.reference)
      }
    },
    [readDetail, readList],
  )

  const command = useOperatorCommand(INQUIRY, csrfToken, { onListChanged: refreshAfter })
  return {
    ...command,
    // 준비에 실은 답변은 대화상자가 열린 동안(준비·무장·확정 중)만 있다 — 거절·취소·완료 뒤에는 없다(공통 훅 전과 같다).
    answer: ANSWER_PHASES.has(command.phase) ? (command.input?.answer ?? null) : null,
    list,
    reloadList,
    listFilter,
    filterList,
    detail,
    select,
    metrics,
    loadMetrics,
  }
}
