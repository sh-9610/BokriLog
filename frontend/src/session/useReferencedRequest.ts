import { useLayoutEffect, useRef, useState } from 'react'

/** 참조 조회 하나의 결말. `ready`의 값은 조회가 넘긴 `parse`의 결과다. */
export type ReferencedOutcome<T> =
  | { readonly kind: 'ready'; readonly value: T }
  /** 응답은 왔지만 쓸 수 없다. `retryable`은 다시 물으면 달라질 수 있는가(404는 아니다). */
  | { readonly kind: 'failed'; readonly retryable: boolean }
  /** 거절(403)이거나 세션이 끝났다(401). 조회 결과를 메모리에서 지우고 `onDenied`가 불린다. */
  | { readonly kind: 'denied'; readonly cause: 'refused' | 'signedOut' }

export interface ReferencedRead<T> {
  /** 동일 출처 경로. 요청 옵션(`credentials`·`cache`·`Accept`·`signal`)은 엔진이 붙인다. */
  readonly path: string
  /** 200 본문을 화면의 값으로 바꾼다. `null`이면 재시도 가능한 실패다. 200이 아니면 `null`을 받는다. */
  readonly parse: (payload: unknown) => T | null
  /**
   * 결말을 받는다. 늦은 응답과 중단된 요청에는 불리지 않고, 언제나 `start`가 돌아온 뒤 비동기로 한 번만
   * 불린다. 여기서 난 오류는 엔진이 삼킨다 — 두 번째 결말(`failed`)로 바꿔 부르지 않는다.
   */
  readonly settle: (outcome: ReferencedOutcome<T>) => void
}

export interface ReferencedRequest {
  /** 조회 하나를 시작한다. 정책이 `ignore`이고 진행 중인 요청이 있으면 아무것도 하지 않고 거짓이다. */
  readonly start: <T>(read: ReferencedRead<T>) => boolean
  /** 진행 중인 요청을 중단하고 그 응답을 버린다. 실패가 아니다. */
  readonly close: () => void
  /** 지금 진행 중인 요청이 있는가. */
  readonly inFlight: () => boolean
}

interface ReferencedRequestArguments {
  /**
   * 조회가 가리키는 묶음의 키. 바뀌면 그 커밋에서 진행 중인 요청을 중단하고, 옛 키로 물은 응답은 어떤
   * 답이 와도 받지 않는다. `null`은 보이는 묶음이 없다는 뜻이다.
   */
  readonly key: string | null
  /**
   * 거절·세션 종료에 불린다. 화면은 대시보드에 다시 읽기를 부탁하고 그 읽기가 무엇을 남길지 정한다.
   * 결말 시점의 최신 콜백이 불린다 — 요청 중에 바뀌어도 옛 콜백을 부르지 않는다.
   */
  readonly onDenied: () => void
  /**
   * 진행 중인 요청이 있을 때의 정책. `replace`는 그 요청을 중단하고 새로 묻는다(조정 이력의 필터 변경).
   * `ignore`는 새 요청을 내지 않는다(계산 근거의 같은 tick 두 번 열기 — 합류). 두 훅이 다르게 쓰며
   * 통일은 별도 결정이다.
   */
  readonly concurrency: 'replace' | 'ignore'
}

/**
 * 현재 포인터를 참조하는 조회(계산 근거·조정 이력)의 공통 엔진. 한 묶음에 속한 응답만 받는다 — sequence로
 * "내 요청이 아직 최신인가", 키로 "내가 물은 묶음이 아직 보이는 묶음인가"를 응답 앞뒤에서 두 번 본다. 두
 * 검사 사이에 `response.json()`이 있어 본문을 읽는 동안에도 묶음이 바뀔 수 있기 때문이다.
 *
 * 상태 코드의 뜻은 두 조회가 같다: 401·403은 `denied`(결과를 지우고 `onDenied`), 404는 재시도 불가 실패
 * (서버가 "보이는 묶음은 현재 포인터가 아니다"라고 말한 것이라 다시 물어도 같다), 그 밖의 비정상·본문 파싱
 * 실패·네트워크 오류는 재시도 가능한 실패다. 중단된 요청은 최신이 아니므로 아무것도 결정하지 않는다.
 *
 * 결말은 언제나 `start`가 돌아온 뒤 비동기로 온다. `fetch`는 `start` 안에서 동기로 부르되(요청이 나갔다는
 * 사실 — 중단 손잡이·관측된 요청 — 은 `start`가 돌아온 순간 성립해야 한다) 동기로 throw하는 `fetch`(시험
 * 스텁)는 거부된 약속으로 바꿔 같은 `await` 뒤로 보낸다. 그래서 `start` 안에서는 어떤 결말도 만들어지지
 * 않고, 호출자는 `start` 뒤에 `loading`을 세워도 결말에 덮이지 않는다. 호출자의 `settle`이 throw해도 그
 * 오류는 삼키고 두 번째 결말을 만들지 않는다 — 그렇지 않으면 401/403의 `denied`가 `failed`로 뒤집힌다.
 *
 * view 상태 기계는 각 훅이 소유한다 — `loading`을 세우는 것도, 결말을 자기 view로 옮기는 것도 훅이다.
 * 엔진은 요청의 수명(sequence·중단·키·매핑)만 들고, 반환 객체는 마운트 동안 같은 객체라 의존성에 넣어도
 * 콜백이 다시 만들어지지 않는다.
 */
export function useReferencedRequest({
  concurrency,
  key,
  onDenied,
}: ReferencedRequestArguments): ReferencedRequest {
  const sequence = useRef(0)
  /** 진행 중인 요청의 중단 손잡이. `null`이면 진행 중인 요청이 없다. */
  const controller = useRef<AbortController | null>(null)
  const keyRef = useRef(key)
  const onDeniedRef = useRef(onDenied)
  const concurrencyRef = useRef(concurrency)

  // 상자는 렌더가 아니라 layout effect가 채운다 — 결말이 읽는 값은 그 커밋의 값이어야 한다.
  useLayoutEffect(() => {
    onDeniedRef.current = onDenied
    concurrencyRef.current = concurrency
  })

  // 커밋 안에서, 어떤 응답도 닿기 전에: 보이는 키가 옮겨 가고, 옛 묶음으로 물은 요청은 중단되어 누구의
  // 것도 아니다. 언마운트도 같은 정리를 돈다.
  useLayoutEffect(() => {
    keyRef.current = key
    return () => {
      sequence.current += 1
      controller.current?.abort()
      controller.current = null
    }
  }, [key])

  const [request] = useState<ReferencedRequest>(() => ({
    start<T>(read: ReferencedRead<T>): boolean {
      if (controller.current !== null) {
        // 같은 tick의 두 번째 열기는 진행 중인 요청에 합류한다.
        if (concurrencyRef.current === 'ignore') return false
        controller.current.abort()
      }
      sequence.current += 1
      const mine = sequence.current
      const askedFor = keyRef.current
      const own = new AbortController()
      controller.current = own
      const latest = (): boolean => sequence.current === mine && keyRef.current === askedFor
      // 결말은 한 번이다. 호출자의 오류는 삼키고, `catch`로 떨어져도 다시 결말을 만들지 않는다.
      const settle = (outcome: ReferencedOutcome<T>): void => {
        controller.current = null
        try {
          read.settle(outcome)
        } catch {
          // 호출자의 오류다. 결말은 이미 넘겼으므로 여기서 할 일이 없다.
        }
      }
      // 요청은 지금 나간다. 동기 throw는 거부된 약속이 되어 아래 `await`에서 같은 `catch`로 떨어진다.
      let pending: Promise<Response>
      try {
        pending = fetch(read.path, {
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json' },
          signal: own.signal,
        })
      } catch (error: unknown) {
        pending = Promise.reject(error instanceof Error ? error : new Error(String(error)))
      }
      void (async () => {
        try {
          const response = await pending
          if (!latest()) return
          if (response.status === 401 || response.status === 403) {
            settle({ kind: 'denied', cause: response.status === 401 ? 'signedOut' : 'refused' })
            onDeniedRef.current()
            return
          }
          if (response.status === 404) {
            settle({ kind: 'failed', retryable: false })
            return
          }
          const payload: unknown = response.status === 200 ? await response.json() : null
          if (!latest()) return
          const value = read.parse(payload)
          settle(value === null ? { kind: 'failed', retryable: true } : { kind: 'ready', value })
        } catch {
          // 중단된 요청은 더 이상 최신이 아니다: 아무것도 결정하지 않고 실패도 아니다.
          if (latest()) settle({ kind: 'failed', retryable: true })
        }
      })()
      return true
    },
    close(): void {
      sequence.current += 1
      controller.current?.abort()
      controller.current = null
    },
    inFlight(): boolean {
      return controller.current !== null
    },
  }))

  return request
}
