import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { stubOperatorCommand } from '../test/setup'
import { preparedHeadOf, type PreparedHead } from './operatorApi'
import { useOperatorCommand, type OperatorCommandSpec } from './useOperatorCommand'

/**
 * 공통 운영 command 상태 기계(Epic 3 회고 AI-5). 화면 다섯이 같은 전이를 쓰므로 여기서는 시험용 command 하나로 전이·멱등
 * 키·응답 없는 확정·문구 불일치·취소·목록 콜백을 본다. 화면별 배선은 `OperatorScreen.test.tsx`·`OperatorNotice.test.tsx`·
 * `OperatorInquiry.test.tsx`가 본다.
 */

const TOKEN = 'operator-command-csrf-token'

const COMMAND = 'test-command'

interface Input {
  readonly target: string
  readonly reason: string
}

interface Completed {
  readonly operationId: string
  readonly value: string
}

const SPEC: OperatorCommandSpec<Input, PreparedHead, Completed> = {
  commandOf: () => COMMAND,
  bodyOf: (input) => ({ target: input.target, reason: input.reason }),
  keyOf: (input) => `${input.target}\n${input.reason}`,
  preparedOf: (body, input) => {
    const head = preparedHeadOf(body)
    return head !== null && head.target === input.target ? head : null
  },
  completedOf: (body, operationId) =>
    typeof body.value === 'string' ? { operationId, value: body.value } : null,
  prepareListCodes: new Set(['TARGET_INVALID']),
  confirmListCodes: new Set(['TARGET_INVALID']),
}

const INPUT: Input = { target: 'target-1', reason: '사유' }

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function preparedBody(operationId = 'op_one'): Record<string, unknown> {
  return {
    operationId,
    command: COMMAND,
    scope: 'SCOPE',
    target: INPUT.target,
    reason: INPUT.reason,
    confirmationPhrase: '시험 문구',
    policyVersion: 'OPERATOR-COMMAND-1.4',
    requestedAt: '2026-10-13T00:00:00Z',
  }
}

function prepared(operationId = 'op_one'): Response {
  return json(preparedBody(operationId))
}

function refused(status: number, code: string): Response {
  return json({ code, message: '거절', correlationId: 'c' }, status)
}

function keyOf(init?: RequestInit): string | undefined {
  const headers = init?.headers as Record<string, string> | undefined
  return headers?.['Idempotency-Key']
}

/** 그 단계의 요청을 기록하며 차례로 답한다. */
function recorder(...responses: Response[]): {
  readonly keys: (string | undefined)[]
  readonly bodies: unknown[]
  readonly respond: (init?: RequestInit) => Promise<Response>
} {
  const keys: (string | undefined)[] = []
  const bodies: unknown[] = []
  const queue = [...responses]
  return {
    keys,
    bodies,
    respond: (init) => {
      keys.push(keyOf(init))
      bodies.push(typeof init?.body === 'string' ? JSON.parse(init.body) : null)
      const next = queue.shift()
      return next === undefined
        ? Promise.reject(new Error('응답이 더 없다'))
        : Promise.resolve(next)
    },
  }
}

describe('useOperatorCommand', () => {
  it('준비 → 무장 → 확정은 같은 멱등 키를 쓰고 완료 뒤 다음 준비는 새 키다', async () => {
    const prepare = recorder(prepared('op_one'), prepared('op_two'))
    const confirm = recorder(json({ operationId: 'op_one', outcome: 'COMPLETED', value: 'done' }))
    stubOperatorCommand(COMMAND, 'prepare', prepare.respond)
    stubOperatorCommand(COMMAND, 'confirm', confirm.respond)
    const onCompleted = vi.fn()
    const onListChanged = vi.fn()
    const { result } = renderHook(() =>
      useOperatorCommand(SPEC, TOKEN, { onCompleted, onListChanged }),
    )
    expect(result.current.phase).toBe('idle')

    act(() => {
      result.current.prepare(INPUT)
    })
    expect(result.current.phase).toBe('preparing')
    expect(result.current.busy).toBe(true)
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    expect(result.current.prepared?.operationId).toBe('op_one')
    expect(result.current.input).toEqual(INPUT)
    expect(prepare.bodies[0]).toEqual({ target: INPUT.target, reason: INPUT.reason })

    act(() => {
      result.current.confirm('시험 문구')
    })
    expect(result.current.phase).toBe('confirming')
    await waitFor(() => {
      expect(result.current.phase).toBe('completed')
    })
    expect(result.current.completed).toEqual({ operationId: 'op_one', value: 'done' })
    expect(result.current.prepared).toBeNull()
    expect(result.current.input).toBeNull()
    expect(confirm.bodies[0]).toEqual({ operationId: 'op_one', confirmation: '시험 문구' })
    expect(confirm.keys[0]).toBe(prepare.keys[0])
    expect(onCompleted).toHaveBeenCalledWith({ operationId: 'op_one', value: 'done' })
    expect(onListChanged).toHaveBeenCalledWith(INPUT)

    // 끝난 operation의 키를 다시 쓰지 않는다 — 같은 입력이어도 새 키다.
    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    expect(prepare.keys[1]).toBeDefined()
    expect(prepare.keys[1]).not.toBe(prepare.keys[0])
  })

  it('거절된 준비의 같은 입력은 같은 키를 다시 쓰고 IDEMPOTENCY_CONFLICT 뒤에는 새 키다', async () => {
    const prepare = recorder(
      refused(403, 'REAUTHENTICATION_REQUIRED'),
      refused(409, 'IDEMPOTENCY_CONFLICT'),
      prepared(),
    )
    stubOperatorCommand(COMMAND, 'prepare', prepare.respond)
    const { result } = renderHook(() => useOperatorCommand(SPEC, TOKEN))

    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('refused')
    })
    expect(result.current.code).toBe('REAUTHENTICATION_REQUIRED')
    expect(result.current.reauthRequired).toBe(true)

    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.code).toBe('IDEMPOTENCY_CONFLICT')
    })
    expect(prepare.keys[1]).toBe(prepare.keys[0])

    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    expect(prepare.keys[2]).not.toBe(prepare.keys[1])
  })

  it('응답을 받지 못한 확정은 무장을 열어 두고 같은 키로만 다시 확정하며 자동으로 다시 보내지 않는다', async () => {
    const prepare = recorder(prepared())
    const confirm = recorder(
      refused(500, 'INTERNAL_ERROR'),
      json({ operationId: 'op_one', outcome: 'COMPLETED', value: 'replayed' }),
    )
    stubOperatorCommand(COMMAND, 'prepare', prepare.respond)
    stubOperatorCommand(COMMAND, 'confirm', confirm.respond)
    const { result } = renderHook(() => useOperatorCommand(SPEC, TOKEN))
    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })

    act(() => {
      result.current.confirm('시험 문구')
    })
    await waitFor(() => {
      expect(result.current.uncertain).toBe(true)
    })
    expect(result.current.phase).toBe('armed')
    expect(result.current.uncertainOperationId).toBe('op_one')
    expect(result.current.prepared?.operationId).toBe('op_one')
    expect(confirm.keys).toHaveLength(1)

    act(() => {
      result.current.confirm('시험 문구')
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('completed')
    })
    expect(confirm.keys).toEqual([prepare.keys[0], prepare.keys[0]])
    expect(result.current.completed?.value).toBe('replayed')
  })

  it('문구 불일치는 무장을 유지하고 code를 싣는다', async () => {
    stubOperatorCommand(COMMAND, 'prepare', recorder(prepared()).respond)
    stubOperatorCommand(COMMAND, 'confirm', recorder(refused(409, 'CONFIRMATION_MISMATCH')).respond)
    const { result } = renderHook(() => useOperatorCommand(SPEC, TOKEN))
    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    act(() => {
      result.current.confirm('틀린 문구')
    })
    await waitFor(() => {
      expect(result.current.code).toBe('CONFIRMATION_MISMATCH')
    })
    expect(result.current.phase).toBe('armed')
    expect(result.current.prepared?.operationId).toBe('op_one')
    expect(result.current.uncertain).toBe(false)
  })

  it('취소는 무장을 버리고 cancel을 보내며 응답 없는 확정의 operation을 조회 기본값으로 남긴다', async () => {
    const cancel = recorder(new Response(null, { status: 204 }))
    stubOperatorCommand(COMMAND, 'prepare', recorder(prepared()).respond)
    stubOperatorCommand(COMMAND, 'confirm', recorder(refused(502, 'BAD_GATEWAY')).respond)
    stubOperatorCommand(COMMAND, 'cancel', cancel.respond)
    const { result } = renderHook(() => useOperatorCommand(SPEC, TOKEN))
    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    act(() => {
      result.current.confirm('시험 문구')
    })
    await waitFor(() => {
      expect(result.current.uncertain).toBe(true)
    })

    act(() => {
      result.current.cancel()
    })
    expect(result.current.phase).toBe('cancelled')
    expect(result.current.prepared).toBeNull()
    expect(result.current.uncertainOperationId).toBe('op_one')
    await waitFor(() => {
      expect(cancel.keys).toHaveLength(1)
    })
    // 취소는 멱등 키를 싣지 않는다.
    expect(cancel.keys[0]).toBeUndefined()
  })

  it('목록을 바꾸는 code·확정의 404에 목록 콜백을 부르고 401은 세션 끝으로 말한다', async () => {
    const prepare = recorder(refused(422, 'TARGET_INVALID'), prepared(), prepared('op_two'))
    const confirm = recorder(refused(404, 'NOT_FOUND'), refused(401, 'UNAUTHENTICATED'))
    stubOperatorCommand(COMMAND, 'prepare', prepare.respond)
    stubOperatorCommand(COMMAND, 'confirm', confirm.respond)
    const onListChanged = vi.fn()
    const { result } = renderHook(() => useOperatorCommand(SPEC, TOKEN, { onListChanged }))

    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.code).toBe('TARGET_INVALID')
    })
    expect(onListChanged).toHaveBeenCalledTimes(1)

    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    act(() => {
      result.current.confirm('시험 문구')
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('refused')
    })
    expect(onListChanged).toHaveBeenCalledTimes(2)
    expect(result.current.sessionEnded).toBe(false)

    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    act(() => {
      result.current.confirm('시험 문구')
    })
    await waitFor(() => {
      expect(result.current.sessionEnded).toBe(true)
    })
    expect(onListChanged).toHaveBeenCalledTimes(2)
  })

  it('진행 중의 두 번째 준비는 보내지 않고 해석기가 받지 않은 준비는 거절로 접는다', async () => {
    // 다른 대상을 되돌려 준 준비 응답 — 해석기가 받지 않는다.
    const prepare = recorder(json({ ...preparedBody(), target: 'other' }))
    stubOperatorCommand(COMMAND, 'prepare', prepare.respond)
    const { result } = renderHook(() => useOperatorCommand(SPEC, TOKEN))
    act(() => {
      result.current.prepare(INPUT)
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('refused')
    })
    expect(prepare.keys).toHaveLength(1)
    expect(result.current.prepared).toBeNull()

    // reset은 결과·거절을 비운다.
    act(() => {
      result.current.reset()
    })
    expect(result.current.phase).toBe('idle')
    expect(result.current.code).toBeNull()
  })

  it('진행 중의 두 번째 확정은 보내지 않는다', async () => {
    const confirm = recorder(json({ operationId: 'op_one', outcome: 'COMPLETED', value: 'done' }))
    stubOperatorCommand(COMMAND, 'prepare', recorder(prepared()).respond)
    stubOperatorCommand(COMMAND, 'confirm', confirm.respond)
    const { result } = renderHook(() => useOperatorCommand(SPEC, TOKEN))
    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    act(() => {
      result.current.confirm('시험 문구')
      result.current.confirm('시험 문구')
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('completed')
    })
    expect(confirm.keys).toHaveLength(1)
  })

  it('전송층이 거부로 끝난 준비는 거절로, 확정은 응답 없음으로 접는다', async () => {
    const rejecting: OperatorCommandSpec<Input, PreparedHead, Completed> = {
      ...SPEC,
      preparedOf: () => {
        throw new Error('해석기 실패')
      },
    }
    stubOperatorCommand(COMMAND, 'prepare', recorder(prepared()).respond)
    const { result: refusing } = renderHook(() => useOperatorCommand(rejecting, TOKEN))
    act(() => {
      refusing.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(refusing.current.phase).toBe('refused')
    })
    expect(refusing.current.code).toBeNull()
    expect(refusing.current.busy).toBe(false)

    const throwingConfirm: OperatorCommandSpec<Input, PreparedHead, Completed> = {
      ...SPEC,
      completedOf: () => {
        throw new Error('해석기 실패')
      },
    }
    stubOperatorCommand(COMMAND, 'prepare', recorder(prepared()).respond)
    stubOperatorCommand(
      COMMAND,
      'confirm',
      recorder(json({ operationId: 'op_one', outcome: 'COMPLETED', value: 'done' })).respond,
    )
    const { result: unanswered } = renderHook(() => useOperatorCommand(throwingConfirm, TOKEN))
    act(() => {
      unanswered.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(unanswered.current.phase).toBe('armed')
    })
    act(() => {
      unanswered.current.confirm('시험 문구')
    })
    await waitFor(() => {
      expect(unanswered.current.uncertain).toBe(true)
    })
    expect(unanswered.current.phase).toBe('armed')
    expect(unanswered.current.busy).toBe(false)
  })

  it('결말을 모르는 완료는 그 operation을 조회 기본값으로 남긴다', async () => {
    const pending: OperatorCommandSpec<Input, PreparedHead, Completed> = {
      ...SPEC,
      pendingOperationOf: (completed) =>
        completed.value === 'PENDING' ? completed.operationId : null,
    }
    stubOperatorCommand(COMMAND, 'prepare', recorder(prepared()).respond)
    stubOperatorCommand(
      COMMAND,
      'confirm',
      recorder(json({ operationId: 'op_one', outcome: 'COMPLETED', value: 'PENDING' })).respond,
    )
    const { result } = renderHook(() => useOperatorCommand(pending, TOKEN))
    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    act(() => {
      result.current.confirm('시험 문구')
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('completed')
    })
    expect(result.current.uncertainOperationId).toBe('op_one')
  })

  it('확정 시한은 설정의 값을 쓴다', async () => {
    const quick: OperatorCommandSpec<Input, PreparedHead, Completed> = {
      ...SPEC,
      confirmTimeoutMs: 30,
    }
    stubOperatorCommand(COMMAND, 'prepare', recorder(prepared()).respond)
    // 응답하지 않고 중단 신호만 따른다 — 시한이 지나면 전송이 중단된다.
    stubOperatorCommand(
      COMMAND,
      'confirm',
      (init) =>
        new Promise<Response>((_, reject) => {
          init?.signal?.addEventListener('abort', () => {
            reject(new DOMException('중단', 'AbortError'))
          })
        }),
    )
    const { result } = renderHook(() => useOperatorCommand(quick, TOKEN))
    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    act(() => {
      result.current.confirm('시험 문구')
    })
    await waitFor(
      () => {
        expect(result.current.uncertain).toBe(true)
      },
      { timeout: 1000 },
    )
  })

  it('무장 전의 취소와 진행 중의 reset은 아무것도 하지 않는다', async () => {
    const cancel = recorder(new Response(null, { status: 204 }))
    stubOperatorCommand(COMMAND, 'cancel', cancel.respond)
    let release: (response: Response) => void = () => undefined
    stubOperatorCommand(
      COMMAND,
      'prepare',
      () =>
        new Promise<Response>((resolve) => {
          release = resolve
        }),
    )
    const { result } = renderHook(() => useOperatorCommand(SPEC, TOKEN))
    act(() => {
      result.current.cancel()
    })
    expect(result.current.phase).toBe('idle')
    expect(cancel.keys).toHaveLength(0)

    act(() => {
      result.current.prepare(INPUT)
    })
    expect(result.current.phase).toBe('preparing')
    act(() => {
      result.current.reset()
    })
    expect(result.current.phase).toBe('preparing')
    release(prepared())
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    expect(cancel.keys).toHaveLength(0)
  })
})
