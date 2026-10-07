import { useCallback, useEffect, useRef, useState } from 'react'

import {
  fetchAuditPage,
  fetchOperation,
  type AuditEntry,
  type AuditQuery,
  type ReadResult,
} from './operatorApi'

/**
 * 감사 원장 조회(Story 3.1).
 *
 * `useReferencedRequest`를 쓰지 않는다 — 그 엔진은 현재 공식 포인터를 참조하는 조회(근거·이력)의 것이고, 감사 원장은
 * 포인터 참조가 아니다. 대신 같은 규칙을 이 훅이 지킨다: 늦은 응답은 버리고(요청 번호), 실패한 다시 조회는 마지막 정상
 * 결과를 유지하며, 401은 값을 메모리에서 지우고, 404(운영자가 아니거나 권한이 없다)는 아무 행도 남기지 않는다. 자동
 * 재시도·폴링이 없다. 값은 메모리에만 있다.
 */

export type AuditStatus = 'checking' | 'failed' | 'loaded' | 'notFound' | 'unauthenticated'

export interface AuditView {
  readonly status: AuditStatus
  readonly items: readonly AuditEntry[]
  readonly nextCursor: string | null
  /** 화면이 보여 주는 묶음의 조건. "더 보기"가 같은 조건으로 이어 읽는다. */
  readonly query: AuditQuery
  /** 조회가 도는 중인가. */
  readonly loading: boolean
  /**
   * 한 번이라도 정상 결과를 받았는가. 운영자 제어는 이 값이 참일 때만 그린다 — 첫 조회가 실패했다는 것은 운영자임이
   * 확인되지 않았다는 뜻이고, 그 상태에서 제어를 그리면 fail-open이다.
   */
  readonly everLoaded: boolean
}

export interface AuditFilter {
  readonly from: string
  readonly to: string
  readonly outcome: string
}

export interface OperatorAuditAccess {
  readonly view: AuditView
  readonly search: (filter: AuditFilter) => void
  readonly loadMore: () => void
}

/** 오늘(KST)의 달력 날짜. 기간 입력의 기본값이다. */
export function seoulDate(at: Date): string {
  // en-CA는 YYYY-MM-DD를 낸다. 시간대만 KST로 고정한다.
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(at)
}

/** 최근 7일(KST, 오늘 포함). */
export function defaultAuditFilter(now: Date = new Date()): AuditFilter {
  const week = new Date(now.getTime() - 6 * 24 * 60 * 60 * 1000)
  return { from: seoulDate(week), to: seoulDate(now), outcome: '' }
}

export function useOperatorAudit(enabled: boolean, initial: AuditFilter): OperatorAuditAccess {
  const [view, setView] = useState<AuditView>({
    status: 'checking',
    items: [],
    nextCursor: null,
    query: { ...initial, cursor: null },
    loading: false,
    everLoaded: false,
  })
  /** 첫 조회의 조건. 마운트 때의 값만 쓴다 — 필터 변경은 `search`가 몬다. */
  const first = useRef(initial)
  /** 지금 도는 조회의 번호와 중단 수단. 새 조회가 앞의 것을 대체한다. */
  const sequence = useRef(0)
  const inFlight = useRef<AbortController | null>(null)

  const run = useCallback((query: AuditQuery, append: boolean): void => {
    inFlight.current?.abort()
    const controller = new AbortController()
    inFlight.current = controller
    sequence.current += 1
    const mine = sequence.current
    setView((current) => ({ ...current, loading: true }))
    void fetchAuditPage(query, controller.signal).then((result) => {
      if (mine !== sequence.current) {
        // 늦은 응답이다. 더 새로운 조회가 화면을 소유한다.
        return
      }
      inFlight.current = null
      setView((current) => settle(current, result, query, append))
    })
  }, [])

  /*
   * 첫 조회는 게이트가 열린 순간 한 번이다. 요청 시작은 커밋이 보여 주는 결과가 아니므로 passive effect다
   * (`useSessionState`의 세션 확인과 같은 근거).
   */
  useEffect(() => {
    if (!enabled) {
      return
    }
    run({ ...first.current, cursor: null }, false)
    return () => {
      inFlight.current?.abort()
      // 화면을 떠난 뒤 도착한 응답이 상태를 바꾸지 않게 번호를 넘긴다.
      sequence.current += 1
    }
  }, [enabled, run])

  const search = useCallback(
    (filter: AuditFilter): void => {
      run({ ...filter, cursor: null }, false)
    },
    [run],
  )

  const loadMore = useCallback((): void => {
    if (view.nextCursor === null || view.loading) {
      return
    }
    run({ ...view.query, cursor: view.nextCursor }, true)
  }, [run, view.loading, view.nextCursor, view.query])

  return { view, search, loadMore }
}

function settle(
  current: AuditView,
  result: ReadResult<{ readonly items: readonly AuditEntry[]; readonly nextCursor: string | null }>,
  query: AuditQuery,
  append: boolean,
): AuditView {
  switch (result.status) {
    case 'loaded':
      return {
        status: 'loaded',
        items: append ? [...current.items, ...result.value.items] : result.value.items,
        nextCursor: result.value.nextCursor,
        query: { ...query, cursor: null },
        loading: false,
        everLoaded: true,
      }
    case 'notFound':
      // 운영자가 아니거나 권한이 회수됐다. 보던 행도 남기지 않는다.
      return {
        ...current,
        status: 'notFound',
        items: [],
        nextCursor: null,
        loading: false,
        everLoaded: false,
      }
    case 'unauthenticated':
      return {
        ...current,
        status: 'unauthenticated',
        items: [],
        nextCursor: null,
        loading: false,
        everLoaded: false,
      }
    case 'failed':
      // 마지막 정상 결과를 유지한다. 첫 조회의 실패면 보일 행이 없다.
      return { ...current, status: 'failed', loading: false }
  }
}

/** operation 조회의 상태. */
export type OperationLookup =
  | { readonly status: 'idle' }
  | { readonly status: 'loading'; readonly operationId: string }
  | {
      readonly status: 'loaded'
      readonly operationId: string
      readonly items: readonly AuditEntry[]
    }
  | { readonly status: 'notFound'; readonly operationId: string }
  | { readonly status: 'unauthenticated'; readonly operationId: string }
  | { readonly status: 'failed'; readonly operationId: string }

export interface OperationLookupAccess {
  readonly lookup: OperationLookup
  readonly find: (operationId: string) => void
}

/** operation 하나의 원장 행. 같은 늦은 응답 규칙을 지킨다. 남의·미존재 operation은 같은 "찾지 못함"이다. */
export function useOperationLookup(): OperationLookupAccess {
  const [lookup, setLookup] = useState<OperationLookup>({ status: 'idle' })
  const sequence = useRef(0)
  const inFlight = useRef<AbortController | null>(null)

  useEffect(
    () => () => {
      inFlight.current?.abort()
      sequence.current += 1
    },
    [],
  )

  const find = useCallback((operationId: string): void => {
    const trimmed = operationId.trim()
    if (trimmed === '') {
      return
    }
    inFlight.current?.abort()
    const controller = new AbortController()
    inFlight.current = controller
    sequence.current += 1
    const mine = sequence.current
    setLookup({ status: 'loading', operationId: trimmed })
    void fetchOperation(trimmed, controller.signal).then((result) => {
      if (mine !== sequence.current) {
        return
      }
      inFlight.current = null
      if (result.status === 'loaded') {
        setLookup({ status: 'loaded', operationId: trimmed, items: result.value })
      } else if (result.status === 'notFound') {
        setLookup({ status: 'notFound', operationId: trimmed })
      } else if (result.status === 'unauthenticated') {
        setLookup({ status: 'unauthenticated', operationId: trimmed })
      } else {
        setLookup({ status: 'failed', operationId: trimmed })
      }
    })
  }, [])

  return { lookup, find }
}
