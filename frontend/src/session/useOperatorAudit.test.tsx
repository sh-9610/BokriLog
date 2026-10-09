import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { stubOperatorAudit } from '../test/setup'
import { defaultAuditFilter, seoulDate, useOperatorAudit } from './useOperatorAudit'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function entry(sequence: number): Record<string, unknown> {
  return {
    sequence,
    operationId: `op_${String(sequence)}`,
    actorKind: 'OPERATOR',
    actorDigest: 'b'.repeat(64),
    command: 'audit-export',
    scope: 'AUDIT_LEDGER',
    target: '2026-10-01~2026-10-07',
    reason: '사유',
    outcome: 'REQUESTED',
    policyVersion: 'OPERATOR-COMMAND-1.1',
    requestedAt: '2026-10-07T00:00:00Z',
    recordedAt: '2026-10-07T00:00:00Z',
    correlationId: 'c',
  }
}

const FILTER = { from: '2026-10-01', to: '2026-10-07', outcome: '' }

/** 시험이 직접 풀어 주는 응답. 늦은 응답을 만드는 데 쓴다. */
function deferred(): { promise: Promise<Response>; resolve: (response: Response) => void } {
  let resolve: (response: Response) => void = () => undefined
  const promise = new Promise<Response>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

describe('useOperatorAudit', () => {
  it('기본 기간은 오늘(KST)을 끝으로 하는 7일이다', () => {
    const now = new Date('2026-10-07T20:00:00Z') // KST로는 10월 8일 05시
    expect(seoulDate(now)).toBe('2026-10-08')
    expect(defaultAuditFilter(now)).toEqual({ from: '2026-10-02', to: '2026-10-08', outcome: '' })
  })

  it('늦은 응답은 버리고 새 조회가 화면을 소유한다', async () => {
    const first = deferred()
    const second = deferred()
    const pending = [first, second]
    stubOperatorAudit(() => pending.shift()?.promise ?? Promise.reject(new Error('no more')))
    const { result } = renderHook(() => useOperatorAudit(true, FILTER))
    act(() => {
      result.current.search({ ...FILTER, outcome: 'DENIED' })
    })
    second.resolve(jsonResponse({ items: [entry(2)], nextCursor: null }))
    await waitFor(() => {
      expect(result.current.view.items.map((item) => item.sequence)).toEqual([2])
    })
    first.resolve(jsonResponse({ items: [entry(1)], nextCursor: null }))
    await new Promise((done) => setTimeout(done, 20))
    expect(result.current.view.items.map((item) => item.sequence)).toEqual([2])
    expect(result.current.view.query.outcome).toBe('DENIED')
  })

  it('실패한 다시 조회는 마지막 정상 결과를 유지하고 404는 행을 남기지 않는다', async () => {
    const answers = [
      Promise.resolve(jsonResponse({ items: [entry(1)], nextCursor: null })),
      Promise.resolve(
        jsonResponse({ code: 'INTERNAL_ERROR', message: 'x', correlationId: 'c' }, 500),
      ),
      Promise.resolve(jsonResponse({ code: 'NOT_FOUND', message: 'x', correlationId: 'c' }, 404)),
    ]
    stubOperatorAudit(() => answers.shift() ?? Promise.reject(new Error('no more')))
    const { result } = renderHook(() => useOperatorAudit(true, FILTER))
    await waitFor(() => {
      expect(result.current.view.status).toBe('loaded')
    })
    act(() => {
      result.current.search(FILTER)
    })
    await waitFor(() => {
      expect(result.current.view.status).toBe('failed')
    })
    expect(result.current.view.items).toHaveLength(1)
    act(() => {
      result.current.search(FILTER)
    })
    await waitFor(() => {
      expect(result.current.view.status).toBe('notFound')
    })
    expect(result.current.view.items).toHaveLength(0)
  })

  it('모양이 어긋난 행이 하나라도 있으면 묶음을 믿지 않는다', async () => {
    stubOperatorAudit(() =>
      Promise.resolve(jsonResponse({ items: [entry(1), { sequence: 'x' }], nextCursor: null })),
    )
    const { result } = renderHook(() => useOperatorAudit(true, FILTER))
    await waitFor(() => {
      expect(result.current.view.status).toBe('failed')
    })
    expect(result.current.view.items).toEqual([])
  })

  it('401은 값을 지운다', async () => {
    stubOperatorAudit(() =>
      Promise.resolve(
        jsonResponse({ code: 'UNAUTHENTICATED', message: 'x', correlationId: 'c' }, 401),
      ),
    )
    const { result } = renderHook(() => useOperatorAudit(true, FILTER))
    await waitFor(() => {
      expect(result.current.view.status).toBe('unauthenticated')
    })
    expect(result.current.view.items).toEqual([])
  })

  it('게이트가 닫혀 있으면 묻지 않는다', () => {
    stubOperatorAudit(() => Promise.reject(new Error('should not be called')))
    const { result } = renderHook(() => useOperatorAudit(false, FILTER))
    expect(result.current.view.status).toBe('checking')
    expect(result.current.view.loading).toBe(false)
  })
})
