import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CSRF_HEADER } from '../content/auth'
import {
  deletionFailedStepText,
  deletionFailureLabel,
  operatorContent,
  operatorRefusalMessage,
  REQUEUE_DELETION_COMMAND,
} from '../content/operator'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubOperatorAudit,
  stubOperatorDeletions,
  stubOperatorRestrictionCommand,
  stubOperatorRestrictions,
  stubSessionRequest,
} from '../test/setup'
import { OperatorScreen } from './OperatorScreen'

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

/**
 * Epic 5 회고 AI-1: 운영자 화면의 재시도 필요 삭제 실행 영역. 목록(요청 ID·시각·실패 단계, 사용자 식별자 없음) → 행 고르기 → 사유 →
 * 준비 → 확인 대화상자(미리보기, 문구 재입력, 취소 기본 포커스) → 확정 → 목록 다시 읽기. production(G-5 전)에서는 목록도 동작도 없다.
 */

const copy = operatorContent.deletion

const dialogCopy = operatorContent.deletionDialog

const CSRF_TOKEN = 'operator-deletion-csrf'

const REQUEST_ID = 'del_AbCdEfGhIjKlMnOpQrStUv'

const PHRASE = `삭제 재시도 ${REQUEST_ID}`

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const OTHER_ID = 'del_ZyXwVuTsRqPoNmLkJiHgFe'

function item(requestId = REQUEST_ID): Record<string, unknown> {
  return {
    requestId,
    requestedAt: '2026-11-08T01:00:00Z',
    stoppedAt: '2026-11-08T01:05:00Z',
    failedSteps: [{ step: 'SYNC', failureCode: 'STEP_FAILED', attempts: 2 }],
  }
}

async function open(): Promise<HTMLElement> {
  stubSessionRequest(() =>
    Promise.resolve(json({ authenticated: true, destination: 'operator', csrfToken: CSRF_TOKEN })),
  )
  stubOperatorAudit(() => Promise.resolve(json({ items: [], nextCursor: null })))
  stubOperatorRestrictions(() => Promise.resolve(json({ items: [] })))
  render(<OperatorScreen />)
  await screen.findByRole('heading', { level: 2, name: copy.heading })
  return screen.getByRole('region', { name: copy.heading })
}

beforeEach(() => {
  window.history.pushState({}, '', '/operator')
})

describe('OperatorScreen 삭제 재대기열', () => {
  it('목록에서 고른 실행을 준비하고 문구를 다시 입력해 다시 대기열에 넣는다', async () => {
    let listed = 0
    stubOperatorDeletions(() => {
      listed += 1
      return Promise.resolve(
        json({ available: true, truncated: false, items: listed > 1 ? [] : [item()] }),
      )
    })
    const bodies: unknown[] = []
    const keys: (string | null)[] = []
    stubOperatorRestrictionCommand(REQUEUE_DELETION_COMMAND, 'prepare', (init) => {
      bodies.push(JSON.parse(init?.body as string))
      const headers = new Headers(init?.headers)
      keys.push(headers.get('Idempotency-Key'))
      expect(headers.get(CSRF_HEADER)).toBe(CSRF_TOKEN)
      return Promise.resolve(
        json({
          operationId: 'op_requeue',
          command: REQUEUE_DELETION_COMMAND,
          scope: 'ACCOUNT_DELETION',
          target: REQUEST_ID,
          reason: '일시 장애 복구 확인',
          confirmationPhrase: PHRASE,
          policyVersion: 'OPERATOR-COMMAND-1.5',
          requestedAt: '2026-11-09T00:00:00Z',
          impact: {
            requestId: REQUEST_ID,
            requestedAt: '2026-11-08T01:00:00Z',
            stoppedAt: null,
            failedSteps: [{ step: 'SYNC', failureCode: 'STEP_FAILED', attempts: 2 }],
          },
        }),
      )
    })
    stubOperatorRestrictionCommand(REQUEUE_DELETION_COMMAND, 'confirm', (init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      expect(JSON.parse(init?.body as string)).toEqual({
        operationId: 'op_requeue',
        confirmation: PHRASE,
      })
      return Promise.resolve(
        json({
          operationId: 'op_requeue',
          outcome: 'COMPLETED',
          command: REQUEUE_DELETION_COMMAND,
          status: 'QUEUED',
          requeuedAt: '2026-11-09T00:01:00Z',
          retrySteps: 1,
        }),
      )
    })
    const section = await open()
    const list = await within(section).findByRole('list', { name: copy.heading })
    const row = within(list).getByRole('listitem')
    expect(row).toHaveTextContent(REQUEST_ID)
    expect(row).toHaveTextContent(deletionFailedStepText('SYNC', 'STEP_FAILED', 2))
    expect(row).toHaveTextContent(deletionFailureLabel('STEP_FAILED'))
    expect(within(section).queryByLabelText(copy.reasonLabel)).not.toBeInTheDocument()

    fireEvent.click(within(row).getByRole('button', { name: copy.choose(REQUEST_ID) }))
    const heading = await within(section).findByRole('heading', {
      level: 3,
      name: `${copy.selected}: ${REQUEST_ID}`,
    })
    await waitFor(() => {
      expect(heading).toHaveFocus()
    })
    const start = within(section).getByRole('button', { name: copy.start })
    expect(start).toHaveAttribute('aria-disabled', 'true')
    fireEvent.change(within(section).getByLabelText(copy.reasonLabel), {
      target: { value: '일시 장애 복구 확인' },
    })
    expect(start).toHaveAttribute('aria-disabled', 'false')
    fireEvent.click(start)

    const dialog = await screen.findByRole('dialog', { name: dialogCopy.title })
    expect(within(dialog).getByRole('button', { name: dialogCopy.cancel })).toHaveFocus()
    expect(dialog.textContent).toContain(`${dialogCopy.requestId}: ${REQUEST_ID}`)
    expect(dialog.textContent).toContain(`${dialogCopy.stoppedAt}: ${copy.unknownTime}`)
    expect(dialog.textContent).toContain(deletionFailedStepText('SYNC', 'STEP_FAILED', 2))
    expect(bodies).toEqual([{ target: REQUEST_ID, reason: '일시 장애 복구 확인' }])
    const confirm = within(dialog).getByRole('button', { name: dialogCopy.confirm })
    expect(confirm).toHaveAttribute('aria-disabled', 'true')
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: PHRASE } })
    fireEvent.click(confirm)

    const result = await screen.findByRole('heading', { level: 3, name: copy.result.heading })
    await waitFor(() => {
      expect(result).toHaveFocus()
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('status').textContent).toBe(operatorContent.status.deletionCompleted(1))
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
    // 다시 대기열에 넣은 뒤 목록을 다시 읽는다 — 그 행이 빠지면 폼도 사라진다.
    expect(await within(section).findByText(copy.empty)).toBeInTheDocument()
    expect(within(section).queryByLabelText(copy.reasonLabel)).not.toBeInTheDocument()
    expect(listed).toBe(2)
    expect(getObservedStorageEntries()).toEqual([])
    expect(new URL(window.location.href).search).toBe('')
  })

  it('대상 없음 준비 거절은 이 command의 문장 하나이고 목록을 다시 읽는다', async () => {
    let listed = 0
    stubOperatorDeletions(() => {
      listed += 1
      return Promise.resolve(json({ available: true, truncated: false, items: [item()] }))
    })
    stubOperatorRestrictionCommand(REQUEUE_DELETION_COMMAND, 'prepare', () =>
      Promise.resolve(json({ code: 'TARGET_INVALID', message: '…', correlationId: 'c' }, 422)),
    )
    const section = await open()
    const list = await within(section).findByRole('list', { name: copy.heading })
    fireEvent.click(within(list).getByRole('button', { name: copy.choose(REQUEST_ID) }))
    fireEvent.change(await within(section).findByLabelText(copy.reasonLabel), {
      target: { value: '다시 시도' },
    })
    fireEvent.click(within(section).getByRole('button', { name: copy.start }))
    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(
        operatorRefusalMessage('TARGET_INVALID', REQUEUE_DELETION_COMMAND),
      )
    })
    await waitFor(() => {
      expect(listed).toBe(2)
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('이 환경이 삭제를 실행하지 않으면 목록도 동작도 없다', async () => {
    stubOperatorDeletions(() =>
      Promise.resolve(json({ available: false, truncated: false, items: [] })),
    )
    const section = await open()
    expect(await within(section).findByText(copy.unavailable)).toBeInTheDocument()
    expect(within(section).queryByRole('list')).not.toBeInTheDocument()
    expect(within(section).queryByRole('button')).not.toBeInTheDocument()
    expect(getObservedAllowedRequests().filter((request) => request.startsWith('POST'))).toEqual([])
  })

  it('목록 권한이 없으면 영역을 그리지 않는다', async () => {
    stubOperatorDeletions(() =>
      Promise.resolve(json({ code: 'NOT_FOUND', message: '없음', correlationId: 'c' }, 404)),
    )
    stubSessionRequest(() =>
      Promise.resolve(
        json({ authenticated: true, destination: 'operator', csrfToken: CSRF_TOKEN }),
      ),
    )
    stubOperatorAudit(() => Promise.resolve(json({ items: [], nextCursor: null })))
    stubOperatorRestrictions(() => Promise.resolve(json({ items: [] })))
    render(<OperatorScreen />)
    await screen.findByRole('heading', { level: 2, name: operatorContent.restriction.heading })
    await waitFor(() => {
      expect(getObservedAllowedRequests()).toContain('GET /api/operator/deletions')
    })
    expect(screen.queryByRole('heading', { name: copy.heading })).not.toBeInTheDocument()
  })

  it('행을 열 때마다 깨끗한 상태에서 시작한다', async () => {
    stubOperatorDeletions(() =>
      Promise.resolve(json({ available: true, truncated: false, items: [item(), item(OTHER_ID)] })),
    )
    stubOperatorRestrictionCommand(REQUEUE_DELETION_COMMAND, 'prepare', () =>
      Promise.resolve(json({ code: 'REASON_INVALID', message: '…', correlationId: 'c' }, 422)),
    )
    const section = await open()
    const list = await within(section).findByRole('list', { name: copy.heading })
    fireEvent.click(within(list).getByRole('button', { name: copy.choose(REQUEST_ID) }))
    fireEvent.change(await within(section).findByLabelText(copy.reasonLabel), {
      target: { value: 'A의 사유' },
    })
    fireEvent.click(within(section).getByRole('button', { name: copy.start }))
    const refusal = operatorRefusalMessage('REASON_INVALID', REQUEUE_DELETION_COMMAND)
    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(refusal)
    })

    // 다른 행: 사유가 비고 앞 행의 거절이 남지 않는다.
    fireEvent.click(within(list).getByRole('button', { name: copy.choose(OTHER_ID) }))
    expect(
      await within(section).findByRole('heading', {
        level: 3,
        name: `${copy.selected}: ${OTHER_ID}`,
      }),
    ).toHaveFocus()
    expect(within(section).getByLabelText(copy.reasonLabel)).toHaveValue('')
    expect(screen.getByRole('status').textContent).not.toBe(refusal)
    expect(
      within(section).queryByRole('heading', { name: copy.result.heading }),
    ).not.toBeInTheDocument()

    // 같은 행을 닫았다 다시 열어도 앞 입력이 남지 않는다.
    fireEvent.change(within(section).getByLabelText(copy.reasonLabel), {
      target: { value: 'B의 사유' },
    })
    fireEvent.click(within(section).getByRole('button', { name: copy.close }))
    expect(within(section).queryByLabelText(copy.reasonLabel)).not.toBeInTheDocument()
    fireEvent.click(within(list).getByRole('button', { name: copy.choose(OTHER_ID) }))
    expect(await within(section).findByLabelText(copy.reasonLabel)).toHaveValue('')
  })

  it('목록에서 빠진 실행의 선택은 닫히고 다시 나타나도 저절로 열리지 않는다', async () => {
    let listed = 0
    stubOperatorDeletions(() => {
      listed += 1
      return Promise.resolve(
        json({ available: true, truncated: false, items: listed === 2 ? [] : [item()] }),
      )
    })
    const section = await open()
    const list = await within(section).findByRole('list', { name: copy.heading })
    fireEvent.click(within(list).getByRole('button', { name: copy.choose(REQUEST_ID) }))
    fireEvent.change(await within(section).findByLabelText(copy.reasonLabel), {
      target: { value: '사유' },
    })
    const reload = within(section).getByRole('button', { name: copy.reload })
    fireEvent.click(reload)
    expect(await within(section).findByText(copy.empty)).toBeInTheDocument()
    expect(within(section).queryByLabelText(copy.reasonLabel)).not.toBeInTheDocument()
    reload.focus()
    fireEvent.click(reload)
    await within(section).findByRole('list', { name: copy.heading })
    expect(listed).toBe(3)
    expect(within(section).queryByLabelText(copy.reasonLabel)).not.toBeInTheDocument()
    expect(reload).toHaveFocus()
  })

  it('목록이 상한에서 잘렸으면 그 사실을 말한다', async () => {
    stubOperatorDeletions(() =>
      Promise.resolve(json({ available: true, truncated: true, items: [item()] })),
    )
    const section = await open()
    expect(await within(section).findByText(copy.truncated)).toBeInTheDocument()
  })

  it('첫 읽기가 실패하면 설명도 불가 안내도 그리지 않는다', async () => {
    stubOperatorDeletions(() =>
      Promise.resolve(json({ code: 'INTERNAL_ERROR', message: '…', correlationId: 'c' }, 500)),
    )
    const section = await open()
    expect(await within(section).findByText(copy.failed)).toBeInTheDocument()
    expect(within(section).queryByText(copy.body)).not.toBeInTheDocument()
    expect(within(section).queryByText(copy.unavailable)).not.toBeInTheDocument()
    expect(within(section).queryByRole('list')).not.toBeInTheDocument()
    expect(within(section).getByRole('button', { name: copy.reload })).toBeInTheDocument()
  })

  it('거절 문장은 두 새 code를 안다', () => {
    expect(operatorRefusalMessage('DELETION_NOT_RETRYABLE')).toBe(
      operatorRefusalMessage('DELETION_NOT_RETRYABLE', REQUEUE_DELETION_COMMAND),
    )
    expect(operatorRefusalMessage('DELETION_NOT_RETRYABLE')).not.toBe(operatorRefusalMessage(null))
    expect(operatorRefusalMessage('DELETION_REQUEUE_UNAVAILABLE')).not.toBe(
      operatorRefusalMessage(null),
    )
    expect(deletionFailureLabel('UNKNOWN_CODE')).toBe('UNKNOWN_CODE')
  })
})
