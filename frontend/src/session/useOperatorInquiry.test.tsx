import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { UPDATE_INQUIRY_COMMAND } from '../content/operator'
import { stubOperatorCommand } from '../test/setup'
import { useOperatorInquiry } from './useOperatorInquiry'

/**
 * 문의 처리 훅의 답변 보존(운영 command 공통화 리뷰 patch). 준비에 실은 답변은 대화상자가 열린 동안만 있고, 준비·확정이
 * 거절되거나 취소·완료되면 비운다 — 공통 훅으로 옮기기 전과 같다.
 */

const TOKEN = 'operator-inquiry-csrf'

const REFERENCE = 'Q-AAAAAAAAAA'

const INPUT = { reference: REFERENCE, status: 'ANSWERED', reason: '답변 등록', answer: '답변 원문' }

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function prepared(): Response {
  return json({
    operationId: 'op_inquiry',
    command: UPDATE_INQUIRY_COMMAND,
    scope: 'INQUIRY:ANSWERED',
    target: REFERENCE,
    reason: '답변 등록',
    confirmationPhrase: `문의 답변 완료 ${REFERENCE}`,
    policyVersion: 'OPERATOR-COMMAND-1.5',
    requestedAt: '2026-10-13T00:00:00Z',
    impact: { reference: REFERENCE, nextStatus: 'ANSWERED', answerProvided: true },
  })
}

function refused(status: number, code: string): Response {
  return json({ code, message: '거절', correlationId: 'c' }, status)
}

describe('useOperatorInquiry 답변', () => {
  it('거절된 준비 뒤에는 답변이 없다', async () => {
    stubOperatorCommand(UPDATE_INQUIRY_COMMAND, 'prepare', () =>
      Promise.resolve(refused(422, 'INQUIRY_SENSITIVE_CONTENT')),
    )
    const { result } = renderHook(() => useOperatorInquiry(false, TOKEN))
    act(() => {
      result.current.prepare(INPUT)
    })
    expect(result.current.answer).toBe('답변 원문')
    await waitFor(() => {
      expect(result.current.phase).toBe('refused')
    })
    expect(result.current.answer).toBeNull()
  })

  it('무장 중에는 답변을 보이고 거절된 확정 뒤에는 비운다', async () => {
    stubOperatorCommand(UPDATE_INQUIRY_COMMAND, 'prepare', () => Promise.resolve(prepared()))
    stubOperatorCommand(UPDATE_INQUIRY_COMMAND, 'confirm', () =>
      Promise.resolve(refused(409, 'INQUIRY_TRANSITION_INVALID')),
    )
    const { result } = renderHook(() => useOperatorInquiry(false, TOKEN))
    act(() => {
      result.current.prepare(INPUT)
    })
    await waitFor(() => {
      expect(result.current.phase).toBe('armed')
    })
    expect(result.current.answer).toBe('답변 원문')
    act(() => {
      result.current.confirm(`문의 답변 완료 ${REFERENCE}`)
    })
    expect(result.current.answer).toBe('답변 원문')
    await waitFor(() => {
      expect(result.current.phase).toBe('refused')
    })
    expect(result.current.answer).toBeNull()
  })
})
