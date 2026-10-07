import { renderHook, waitFor, type RenderHookResult } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { DASHBOARD_EVIDENCE_PATH } from '../content/calculationEvidence'
import { getObservedAllowedRequests, stubCalculationEvidence } from '../test/setup'
import {
  useReferencedRequest,
  type ReferencedOutcome,
  type ReferencedRequest,
} from './useReferencedRequest'

/*
 * 참조 조회 엔진 하나.
 *
 * 계산 근거·조정 이력이 위임하는 요청의 수명 — sequence, 중단, 키, 상태 코드 매핑, 결말의 시점 — 을 두 훅의
 * view 상태 기계 없이 본다. 경로는 근거 경로를 빌린다: 시험 setup이 (메서드, 경로) 쌍으로 여는 탈출구 중
 * 하나이고, 엔진은 경로의 뜻을 모른다.
 *
 * 고정 시간 대기는 쓰지 않는다(`DEVELOPMENT.md` "시험의 대기"). "앞 결말이 오지 않는다"는 뒤 조회의 결말을
 * 기다린 뒤에 단정한다 — 앞 응답이 먼저 풀렸으므로 그 결말이 올 것이었다면 뒤 결말보다 먼저 왔다.
 */

const PATH = `${DASHBOARD_EVIDENCE_PATH}?planId=plan-1&pointerVersion=1`
const KEY = 'plan-1\n1'
const OTHER_KEY = 'plan-1\n2'

/** 대시보드가 없다: 거절에 불리는 콜백만 센다. */
function noop(): void {
  // 아무것도 하지 않는다.
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function deferred(): { promise: Promise<Response>; resolve: (response: Response) => void } {
  let resolve: (response: Response) => void = () => undefined
  const promise = new Promise<Response>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

/** 헤더는 바로 오고 본문은 `deliver`를 부를 때 온다. 두 번째 `latest()` 검사가 보는 틈이다. */
function delayedBody(body: unknown): { response: Response; deliver: () => void } {
  let deliver: () => void = () => undefined
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      deliver = (): void => {
        controller.enqueue(new TextEncoder().encode(JSON.stringify(body)))
        controller.close()
      }
    },
  })
  return {
    response: new Response(stream, {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }),
    deliver,
  }
}

/** 조회에 순서대로 답하고, 각 요청의 옵션을 기록한다. */
function answers(...queue: (() => Promise<Response>)[]): (RequestInit | undefined)[] {
  const inits: (RequestInit | undefined)[] = []
  stubCalculationEvidence((_url, init) => {
    inits.push(init)
    const next = queue.shift()
    if (next === undefined) throw new Error('예상하지 않은 조회')
    return next()
  })
  return inits
}

function reads(): number {
  return getObservedAllowedRequests().filter((entry) => entry === `GET ${PATH}`).length
}

/** 본문의 `value`를 값으로 읽는다. 없으면 쓸 수 없는 본문이다. */
function parse(payload: unknown): string | null {
  if (typeof payload !== 'object' || payload === null) return null
  const value = (payload as Record<string, unknown>).value
  return typeof value === 'string' ? value : null
}

interface HarnessProps {
  readonly key: string | null
  readonly onDenied: () => void
  readonly concurrency: 'replace' | 'ignore'
}

function harness(
  overrides: Partial<HarnessProps> = {},
): RenderHookResult<ReferencedRequest, HarnessProps> {
  return renderHook((props: HarnessProps) => useReferencedRequest(props), {
    initialProps: { key: KEY, onDenied: noop, concurrency: 'replace', ...overrides },
  })
}

/** 뒤 조회 하나를 보내고 그 결말을 기다린다. 앞 조회의 결말이 올 것이었다면 이보다 먼저 왔다. */
async function settleAnother(result: { readonly current: ReferencedRequest }): Promise<void> {
  const settle = vi.fn()
  result.current.start({ path: PATH, parse, settle })
  await waitFor(() => {
    expect(settle).toHaveBeenCalledTimes(1)
  })
}

describe('useReferencedRequest', () => {
  it('묶음 키가 바뀌면 진행 중인 조회를 중단하고 그 늦은 응답은 아무것도 결정하지 않는다', async () => {
    const pending = deferred()
    const inits = answers(
      () => pending.promise,
      () => Promise.resolve(json({ value: 'next' })),
    )
    const settle = vi.fn()
    const { result, rerender } = harness()

    expect(result.current.start({ path: PATH, parse, settle })).toBe(true)
    expect(result.current.inFlight()).toBe(true)

    rerender({ key: OTHER_KEY, onDenied: noop, concurrency: 'replace' })

    expect(inits[0]?.signal?.aborted).toBe(true)
    expect(result.current.inFlight()).toBe(false)
    pending.resolve(json({ value: 'late' }))
    await settleAnother(result)
    expect(settle).not.toHaveBeenCalled()
    expect(reads()).toBe(2)
  })

  it('헤더가 온 뒤 본문을 읽는 동안 묶음이 바뀌어도 그 본문은 아무것도 결정하지 않는다', async () => {
    const late = delayedBody({ value: 'late' })
    let headersSeen = false
    answers(
      () => {
        headersSeen = true
        return Promise.resolve(late.response)
      },
      () => Promise.resolve(json({ value: 'next' })),
    )
    const settle = vi.fn()
    const { result, rerender } = harness()
    result.current.start({ path: PATH, parse, settle })
    // 헤더가 도착해 첫 `latest()` 검사를 지나고 `response.json()`에서 본문을 기다리는 상태로 만든다.
    await waitFor(() => {
      expect(headersSeen).toBe(true)
    })
    await Promise.resolve()
    await Promise.resolve()

    rerender({ key: OTHER_KEY, onDenied: noop, concurrency: 'replace' })
    late.deliver()

    await settleAnother(result)
    expect(settle).not.toHaveBeenCalled()
  })

  it('닫으면 진행 중인 조회를 중단하고 중단은 실패가 아니다', async () => {
    const pending = deferred()
    const inits = answers(
      () => pending.promise,
      () => Promise.resolve(json({ value: 'next' })),
    )
    const settle = vi.fn()
    const { result } = harness()

    result.current.start({ path: PATH, parse, settle })
    result.current.close()

    expect(inits[0]?.signal?.aborted).toBe(true)
    expect(result.current.inFlight()).toBe(false)
    pending.resolve(json({}, 503))
    await settleAnother(result)
    expect(settle).not.toHaveBeenCalled()
  })

  it('언마운트도 진행 중인 조회를 중단한다', () => {
    const pending = deferred()
    const inits = answers(() => pending.promise)
    const { result, unmount } = harness()
    result.current.start({ path: PATH, parse, settle: vi.fn() })

    unmount()

    expect(inits[0]?.signal?.aborted).toBe(true)
  })

  it('`ignore`는 진행 중이면 새 조회를 내지 않고, `replace`는 앞 조회를 중단하고 그 응답을 버린다', async () => {
    const first = deferred()
    const inits = answers(
      () => first.promise,
      () => Promise.resolve(json({ value: 'second' })),
    )
    const firstSettle = vi.fn()
    const secondSettle = vi.fn()
    const { result, rerender } = harness({ concurrency: 'ignore' })

    result.current.start({ path: PATH, parse, settle: firstSettle })
    expect(result.current.start({ path: PATH, parse, settle: vi.fn() })).toBe(false)
    expect(reads()).toBe(1)
    expect(inits[0]?.signal?.aborted).toBe(false)

    rerender({ key: KEY, onDenied: noop, concurrency: 'replace' })
    expect(result.current.start({ path: PATH, parse, settle: secondSettle })).toBe(true)
    expect(inits[0]?.signal?.aborted).toBe(true)
    first.resolve(json({ value: 'first' }))
    await waitFor(() => {
      expect(secondSettle).toHaveBeenCalledWith({ kind: 'ready', value: 'second' })
    })
    expect(firstSettle).not.toHaveBeenCalled()
    expect(result.current.inFlight()).toBe(false)
  })

  it('상태 코드를 옮긴다: 401은 세션 종료, 403은 거절, 404는 재시도 불가, 그 밖과 깨진 본문은 재시도 가능', async () => {
    answers(
      () => Promise.resolve(json({}, 401)),
      () => Promise.resolve(json({}, 403)),
      () => Promise.resolve(json({}, 404)),
      () => Promise.resolve(json({}, 503)),
      () => Promise.resolve(json({ value: 1 })),
      () => Promise.reject(new TypeError('network')),
      () => Promise.resolve(json({ value: 'ok' })),
    )
    const onDenied = vi.fn()
    const { result } = harness({ onDenied, concurrency: 'ignore' })
    const expected: readonly ReferencedOutcome<string>[] = [
      { kind: 'denied', cause: 'signedOut' },
      { kind: 'denied', cause: 'refused' },
      { kind: 'failed', retryable: false },
      { kind: 'failed', retryable: true },
      { kind: 'failed', retryable: true },
      { kind: 'failed', retryable: true },
      { kind: 'ready', value: 'ok' },
    ]
    for (const outcome of expected) {
      const settle = vi.fn()
      result.current.start({ path: PATH, parse, settle })
      await waitFor(() => {
        expect(settle).toHaveBeenCalledTimes(1)
      })
      expect(settle).toHaveBeenCalledWith(outcome)
      expect(result.current.inFlight()).toBe(false)
    }
    // 거절·세션 종료에만 불린다.
    expect(onDenied).toHaveBeenCalledTimes(2)
    expect(reads()).toBe(7)
  })

  it('요청 중에 `onDenied`가 바뀌면 결말 시점의 최신 콜백이 불린다', async () => {
    const pending = deferred()
    answers(() => pending.promise)
    const before = vi.fn()
    const after = vi.fn()
    const settle = vi.fn()
    const { result, rerender } = harness({ onDenied: before })
    result.current.start({ path: PATH, parse, settle })

    rerender({ key: KEY, onDenied: after, concurrency: 'replace' })
    pending.resolve(json({}, 403))

    await waitFor(() => {
      expect(settle).toHaveBeenCalledWith({ kind: 'denied', cause: 'refused' })
    })
    expect(after).toHaveBeenCalledTimes(1)
    expect(before).not.toHaveBeenCalled()
  })

  it('호출자의 `settle`이 throw해도 결말은 한 번이고 `denied`가 `failed`로 뒤집히지 않는다', async () => {
    answers(
      () => Promise.resolve(json({}, 403)),
      () => Promise.resolve(json({ value: 'next' })),
    )
    const onDenied = vi.fn()
    const settle = vi.fn(() => {
      throw new Error('호출자의 오류')
    })
    const { result } = harness({ onDenied })
    result.current.start({ path: PATH, parse, settle })

    await waitFor(() => {
      expect(onDenied).toHaveBeenCalledTimes(1)
    })
    await settleAnother(result)
    expect(settle).toHaveBeenCalledTimes(1)
    expect(settle).toHaveBeenCalledWith({ kind: 'denied', cause: 'refused' })
  })

  it('동기로 throw하는 `fetch`에서도 결말은 `start`가 돌아온 뒤에 온다', async () => {
    stubCalculationEvidence(() => {
      throw new TypeError('동기 실패')
    })
    const settle = vi.fn()
    const { result } = harness()

    expect(result.current.start({ path: PATH, parse, settle })).toBe(true)
    // `start`가 돌아온 지금은 아직 결말이 없다 — 호출자는 이 뒤에 `loading`을 세워도 안전하다.
    expect(settle).not.toHaveBeenCalled()
    expect(result.current.inFlight()).toBe(true)

    await waitFor(() => {
      expect(settle).toHaveBeenCalledWith({ kind: 'failed', retryable: true })
    })
    expect(settle).toHaveBeenCalledTimes(1)
    expect(result.current.inFlight()).toBe(false)
  })
})
