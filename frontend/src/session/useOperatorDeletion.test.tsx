import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { deletionFailureLabel, REQUEUE_DELETION_COMMAND } from '../content/operator'
import { stubOperatorCommand, stubOperatorDeletions } from '../test/setup'
import {
  completedDeletionRequeueOf,
  fetchRetryNeededDeletions,
  preparedDeletionRequeueOf,
} from './operatorApi'
import { useOperatorDeletion } from './useOperatorDeletion'

/**
 * 삭제 실행 재대기열(Epic 5 회고 AI-1)의 판독과 훅. 목록은 재시도 필요 실행만이고 사용자 식별자가 없다. 이 환경이 삭제를 실행하지
 * 않으면(`available: false`) 항목을 보지 않는다. 목록이 서버의 지금과 달라졌을 수 있는 거절·완료 뒤에는 목록을 다시 읽는다.
 */

const TOKEN = 'operator-deletion-csrf'

const REQUEST_ID = 'del_AbCdEfGhIjKlMnOpQrStUv'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function item(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    requestId: REQUEST_ID,
    requestedAt: '2026-11-08T01:00:00Z',
    stoppedAt: '2026-11-08T01:05:00Z',
    failedSteps: [{ step: 'SYNC', failureCode: 'STEP_FAILED', attempts: 2 }],
    ...overrides,
  }
}

function prepared(target = REQUEST_ID): Record<string, unknown> {
  return {
    operationId: 'op_requeue',
    command: REQUEUE_DELETION_COMMAND,
    scope: 'ACCOUNT_DELETION',
    target,
    reason: '일시 장애 복구 확인',
    confirmationPhrase: `삭제 재시도 ${target}`,
    policyVersion: 'OPERATOR-COMMAND-1.5',
    requestedAt: '2026-11-09T00:00:00Z',
    impact: {
      requestId: target,
      requestedAt: '2026-11-08T01:00:00Z',
      stoppedAt: null,
      failedSteps: [{ step: 'SYNC', failureCode: 'STEP_FAILED', attempts: 2 }],
    },
  }
}

function read(): Promise<Awaited<ReturnType<typeof fetchRetryNeededDeletions>>> {
  return fetchRetryNeededDeletions(new AbortController().signal)
}

describe('재시도 필요 목록 판독', () => {
  it('재시도 필요 실행을 읽고 멈춘 시각이 없으면 null이다', async () => {
    stubOperatorDeletions(() =>
      Promise.resolve(
        json({
          available: true,
          truncated: false,
          items: [item(), item({ requestId: 'del_x', stoppedAt: null })],
        }),
      ),
    )
    const result = await read()
    expect(result).toEqual({
      status: 'loaded',
      value: {
        available: true,
        truncated: false,
        items: [
          {
            requestId: REQUEST_ID,
            requestedAt: '2026-11-08T01:00:00Z',
            stoppedAt: '2026-11-08T01:05:00Z',
            failedSteps: [{ step: 'SYNC', failureCode: 'STEP_FAILED', attempts: 2 }],
          },
          {
            requestId: 'del_x',
            requestedAt: '2026-11-08T01:00:00Z',
            stoppedAt: null,
            failedSteps: [{ step: 'SYNC', failureCode: 'STEP_FAILED', attempts: 2 }],
          },
        ],
      },
    })
  })

  it('이 환경이 삭제를 실행하지 않으면 항목을 보지 않는다', async () => {
    stubOperatorDeletions(() =>
      Promise.resolve(json({ available: false, truncated: false, items: [item()] })),
    )
    expect(await read()).toEqual({
      status: 'loaded',
      value: { available: false, truncated: false, items: [] },
    })
  })

  it.each([
    ['요청 ID 모양이 아니다', item({ requestId: 'u-1234' })],
    ['요청 시각이 없다', item({ requestedAt: null })],
    [
      '시도 수가 음수다',
      item({ failedSteps: [{ step: 'SYNC', failureCode: 'STEP_FAILED', attempts: -1 }] }),
    ],
    ['실패 단계가 배열이 아니다', item({ failedSteps: 'SYNC' })],
  ])('행 하나라도 모양이 어긋나면(%s) 목록 전체를 믿지 않는다', async (_name, broken) => {
    stubOperatorDeletions(() =>
      Promise.resolve(json({ available: true, truncated: false, items: [item(), broken] })),
    )
    expect(await read()).toEqual({ status: 'failed', code: null })
  })

  it('상한에서 잘린 목록은 그 사실을 싣는다', async () => {
    stubOperatorDeletions(() =>
      Promise.resolve(json({ available: true, truncated: true, items: [item()] })),
    )
    const result = await read()
    expect(result.status === 'loaded' && result.value.truncated).toBe(true)
  })

  it('가용 여부·잘림 여부가 없으면 실패이고 404는 권한 없음이다', async () => {
    stubOperatorDeletions(() => Promise.resolve(json({ truncated: false, items: [] })))
    expect(await read()).toEqual({ status: 'failed', code: null })
    stubOperatorDeletions(() => Promise.resolve(json({ available: true, items: [] })))
    expect(await read()).toEqual({ status: 'failed', code: null })
    stubOperatorDeletions(() =>
      Promise.resolve(json({ code: 'NOT_FOUND', message: '없음', correlationId: 'c' }, 404)),
    )
    expect(await read()).toEqual({ status: 'notFound' })
  })
})

describe('재대기열 응답 판독', () => {
  it('준비 응답은 고른 요청 ID의 것만 받는다', () => {
    expect(preparedDeletionRequeueOf(prepared(), REQUEST_ID)?.impact.failedSteps).toEqual([
      { step: 'SYNC', failureCode: 'STEP_FAILED', attempts: 2 },
    ])
    expect(preparedDeletionRequeueOf(prepared('del_other'), REQUEST_ID)).toBeNull()
    expect(
      preparedDeletionRequeueOf({ ...prepared(), command: 'update-inquiry' }, REQUEST_ID),
    ).toBeNull()
    expect(preparedDeletionRequeueOf({ ...prepared(), impact: null }, REQUEST_ID)).toBeNull()
  })

  it('확정 응답은 상태·시각·다시 실행할 단계 수가 있어야 한다', () => {
    const body = {
      operationId: 'op_requeue',
      outcome: 'COMPLETED',
      command: REQUEUE_DELETION_COMMAND,
      status: 'QUEUED',
      requeuedAt: '2026-11-09T00:01:00Z',
      retrySteps: 1,
    }
    expect(completedDeletionRequeueOf(body, 'op_requeue')).toEqual({
      operationId: 'op_requeue',
      status: 'QUEUED',
      requeuedAt: '2026-11-09T00:01:00Z',
      retrySteps: 1,
    })
    expect(completedDeletionRequeueOf({ ...body, retrySteps: 1.5 }, 'op_requeue')).toBeNull()
    expect(completedDeletionRequeueOf({ ...body, requeuedAt: '어제' }, 'op_requeue')).toBeNull()
  })
})

describe('useOperatorDeletion', () => {
  it('게이트가 열리면 목록을 한 번 읽는다', async () => {
    let reads = 0
    stubOperatorDeletions(() => {
      reads += 1
      return Promise.resolve(json({ available: true, truncated: false, items: [item()] }))
    })
    const { result, rerender } = renderHook(({ ready }) => useOperatorDeletion(ready, TOKEN), {
      initialProps: { ready: false },
    })
    expect(reads).toBe(0)
    // 성공한 읽기 전에는 이 환경이 삭제를 실행하는지 모른다.
    expect(result.current.list.available).toBeNull()
    rerender({ ready: true })
    await waitFor(() => {
      expect(result.current.list.status).toBe('loaded')
    })
    expect(reads).toBe(1)
    expect(result.current.list.items.map((entry) => entry.requestId)).toEqual([REQUEST_ID])
    expect(result.current.list.everLoaded).toBe(true)
  })

  it('준비가 재시도 필요가 아니라고 거절되면 목록을 다시 읽는다', async () => {
    let reads = 0
    stubOperatorDeletions(() => {
      reads += 1
      return Promise.resolve(
        json({ available: true, truncated: false, items: reads === 1 ? [item()] : [] }),
      )
    })
    const bodies: unknown[] = []
    stubOperatorCommand(REQUEUE_DELETION_COMMAND, 'prepare', (init) => {
      bodies.push(JSON.parse(init?.body as string))
      return Promise.resolve(
        json({ code: 'DELETION_NOT_RETRYABLE', message: '거절', correlationId: 'c' }, 409),
      )
    })
    const { result } = renderHook(() => useOperatorDeletion(true, TOKEN))
    await waitFor(() => {
      expect(result.current.list.items).toHaveLength(1)
    })
    act(() => {
      result.current.prepare({ requestId: REQUEST_ID, reason: '일시 장애 복구 확인' })
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('refused')
    })
    expect(result.current.code).toBe('DELETION_NOT_RETRYABLE')
    expect(bodies).toEqual([{ target: REQUEST_ID, reason: '일시 장애 복구 확인' }])
    await waitFor(() => {
      expect(result.current.list.items).toHaveLength(0)
    })
    expect(reads).toBe(2)
  })

  it('준비 뒤 확정이 재시도 필요가 아니라고 거절되면 목록을 다시 읽고 그 행이 빠진다', async () => {
    let reads = 0
    stubOperatorDeletions(() => {
      reads += 1
      return Promise.resolve(
        json({ available: true, truncated: false, items: reads === 1 ? [item()] : [] }),
      )
    })
    stubOperatorCommand(REQUEUE_DELETION_COMMAND, 'prepare', () =>
      Promise.resolve(json(prepared())),
    )
    stubOperatorCommand(REQUEUE_DELETION_COMMAND, 'confirm', () =>
      Promise.resolve(
        json({ code: 'DELETION_NOT_RETRYABLE', message: '거절', correlationId: 'c' }, 409),
      ),
    )
    const { result } = renderHook(() => useOperatorDeletion(true, TOKEN))
    await waitFor(() => {
      expect(result.current.list.items).toHaveLength(1)
    })
    act(() => {
      result.current.prepare({ requestId: REQUEST_ID, reason: '일시 장애 복구 확인' })
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    expect(reads).toBe(1)
    act(() => {
      result.current.confirm(`삭제 재시도 ${REQUEST_ID}`)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('refused')
    })
    expect(result.current.code).toBe('DELETION_NOT_RETRYABLE')
    await waitFor(() => {
      expect(result.current.list.items).toHaveLength(0)
    })
    expect(reads).toBe(2)
  })
})

describe('실패 code 문장', () => {
  it('원형의 키는 문장이 아니라 원 code다', () => {
    expect(deletionFailureLabel('STEP_FAILED')).not.toBe('STEP_FAILED')
    expect(deletionFailureLabel('constructor')).toBe('constructor')
    expect(deletionFailureLabel('toString')).toBe('toString')
    expect(deletionFailureLabel('__proto__')).toBe('__proto__')
  })
})
