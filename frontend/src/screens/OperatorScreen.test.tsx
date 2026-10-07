import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { authContent, CSRF_HEADER, SIGN_IN_PROVIDERS } from '../content/auth'
import { operatorContent, operatorCopyStrings, operatorRefusalMessage } from '../content/operator'
import { replaceLocation } from '../session/navigation'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubOperatorAudit,
  stubOperatorExportCancel,
  stubOperatorExportConfirm,
  stubOperatorExportPrepare,
  stubOperatorOperation,
  stubSessionRequest,
} from '../test/setup'
import { OperatorScreen } from './OperatorScreen'

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const CSRF_TOKEN = 'operator-csrf-token'

const copy = operatorContent

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function error(code: string, status: number): Promise<Response> {
  return Promise.resolve(
    jsonResponse({ code, message: '거절', correlationId: 'test-correlation' }, status),
  )
}

function authenticated(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({ authenticated: true, destination: 'operator', csrfToken: CSRF_TOKEN }),
  )
}

function entry(sequence: number, outcome = 'REQUESTED', code?: string): Record<string, unknown> {
  return {
    sequence,
    operationId: `op_entry${String(sequence)}`,
    actorKind: 'OPERATOR',
    actorDigest: 'a'.repeat(64),
    command: 'audit-export',
    scope: 'AUDIT_LEDGER',
    target: '2026-10-01~2026-10-07',
    reason: `사유 ${String(sequence)}`,
    outcome,
    ...(code === undefined ? {} : { code, decidedAt: '2026-10-07T01:00:00Z' }),
    policyVersion: 'OPERATOR-COMMAND-1.0',
    requestedAt: '2026-10-07T00:00:00Z',
    recordedAt: '2026-10-07T00:00:00Z',
    correlationId: 'test-correlation',
  }
}

function page(
  items: Record<string, unknown>[],
  nextCursor: string | null = null,
): Promise<Response> {
  return Promise.resolve(jsonResponse({ items, nextCursor }))
}

const PREPARED = {
  operationId: 'op_prepared',
  command: 'audit-export',
  scope: 'AUDIT_LEDGER',
  target: '2026-10-01~2026-10-07',
  reason: '분기 점검',
  confirmationPhrase: '감사 내보내기 AUDIT_LEDGER 2026-10-01~2026-10-07',
  policyVersion: 'OPERATOR-COMMAND-1.0',
  requestedAt: '2026-10-07T00:00:00Z',
}

function navigateToOperator(): void {
  window.history.pushState({}, '', '/operator')
}

/** 운영자 화면을 열고 첫 감사 조회가 그려질 때까지 기다린다. */
async function openAsOperator(items = [entry(2), entry(1, 'DENIED', 'CONFIRMATION_MISMATCH')]) {
  stubSessionRequest(() => authenticated())
  stubOperatorAudit(() => page(items))
  render(<OperatorScreen />)
  await screen.findByRole('heading', { level: 2, name: copy.audit.heading })
}

/** 준비를 눌러 대화상자를 연다. */
async function openDialog(): Promise<HTMLElement> {
  fireEvent.change(screen.getByLabelText(copy.export.reasonLabel), {
    target: { value: '분기 점검' },
  })
  fireEvent.click(screen.getByRole('button', { name: copy.export.start }))
  return screen.findByRole('dialog', { name: copy.dialog.title })
}

beforeEach(() => {
  navigateToOperator()
  vi.mocked(replaceLocation).mockClear()
})

describe('OperatorScreen', () => {
  it('비운영자에게는 없는 화면과 같은 문장 하나만 그리고 운영자 제어를 DOM에 두지 않는다', async () => {
    stubSessionRequest(() => authenticated())
    stubOperatorAudit(() => error('NOT_FOUND', 404))
    render(<OperatorScreen />)

    expect(
      await screen.findByRole('heading', { level: 1, name: copy.notFound.heading }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: copy.audit.heading })).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toContain('운영자')
    expect(getObservedRequests()).toEqual([])
  })

  it('운영자에게 제목·고지·감사 행을 그리고 결과를 글자로 말한다', async () => {
    await openAsOperator()
    const title = screen.getByRole('heading', { level: 1, name: copy.heading })
    // 고지가 제목 바로 다음 형제다(UX-DR23).
    expect(title.nextElementSibling?.textContent).toBe(authContent.disclaimer)
    expect(screen.getByText('사유 2')).toBeInTheDocument()
    expect(screen.getByText(/거절 · 확인 문구 불일치/)).toBeInTheDocument()
    expect(screen.getByRole('status').textContent).toBe(copy.status.loaded(2))
    expect(getObservedStorageEntries()).toEqual([])
    for (const text of operatorCopyStrings) {
      expect(text).not.toMatch(/실시간|정확|보장|축하|손실/)
    }
  })

  it('필터 조회는 날짜·결과만 URL에 싣고 더 보기는 cursor로 이어 읽는다', async () => {
    const urls: string[] = []
    stubSessionRequest(() => authenticated())
    stubOperatorAudit((url) => {
      urls.push(url)
      return url.includes('cursor=') ? page([entry(1)]) : page([entry(3), entry(2)], 'next-cursor')
    })
    render(<OperatorScreen />)
    await screen.findByText('사유 3')

    fireEvent.click(screen.getByRole('button', { name: copy.audit.more }))
    await screen.findByText('사유 1')
    expect(screen.getByText('사유 3')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(copy.audit.outcomeLabel), {
      target: { value: 'DENIED' },
    })
    fireEvent.click(screen.getByRole('button', { name: copy.audit.search }))
    await waitFor(() => {
      expect(urls.at(-1)).toContain('outcome=DENIED')
    })
    expect(urls[0]).toMatch(/^\/api\/operator\/audit\?from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}$/)
    expect(urls[1]).toContain('cursor=next-cursor')
  })

  it('준비 → 확인 문구 재입력 → 확정이 같은 키로 가고 결과로 초점을 옮긴다', async () => {
    await openAsOperator()
    const keys: string[] = []
    const bodies: unknown[] = []
    stubOperatorExportPrepare((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key') ?? '')
      expect(new Headers(init?.headers).get(CSRF_HEADER)).toBe(CSRF_TOKEN)
      bodies.push(JSON.parse(init?.body as string))
      return Promise.resolve(jsonResponse(PREPARED))
    })
    stubOperatorExportConfirm((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key') ?? '')
      bodies.push(JSON.parse(init?.body as string))
      return Promise.resolve(
        jsonResponse({
          operationId: 'op_prepared',
          outcome: 'COMPLETED',
          command: 'audit-export',
          target: PREPARED.target,
          upperSequence: 2,
          rowCount: 1,
          truncated: false,
          rows: [entry(2)],
        }),
      )
    })

    const dialog = await openDialog()
    // 취소가 기본 포커스다.
    expect(within(dialog).getByRole('button', { name: copy.dialog.cancel })).toHaveFocus()
    const confirm = within(dialog).getByRole('button', { name: copy.dialog.confirm })
    expect(confirm).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(confirm)
    expect(getObservedAllowedRequests().filter((r) => r.includes('confirm'))).toEqual([])

    fireEvent.change(within(dialog).getByRole('textbox'), {
      target: { value: PREPARED.confirmationPhrase },
    })
    expect(confirm).toHaveAttribute('aria-disabled', 'false')
    fireEvent.click(confirm)

    const result = await screen.findByRole('heading', {
      level: 3,
      name: copy.export.result.heading,
    })
    await waitFor(() => {
      expect(result).toHaveFocus()
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
    expect(bodies[0]).toEqual({
      scope: 'AUDIT_LEDGER',
      target: expect.stringMatching(/^\d{4}-\d{2}-\d{2}~\d{4}-\d{2}-\d{2}$/) as unknown,
      reason: '분기 점검',
    })
    expect(bodies[1]).toEqual({
      operationId: 'op_prepared',
      confirmation: PREPARED.confirmationPhrase,
    })
    // 본문은 수행자를 싣지 않는다.
    expect(JSON.stringify(bodies)).not.toMatch(/actor|userId/)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('문구 불일치는 대화상자를 열어 둔 채 말하고 같은 키로 다시 확정한다', async () => {
    await openAsOperator()
    stubOperatorExportPrepare(() => Promise.resolve(jsonResponse(PREPARED)))
    let calls = 0
    stubOperatorExportConfirm(() => {
      calls += 1
      return error('CONFIRMATION_MISMATCH', 409)
    })
    const dialog = await openDialog()
    fireEvent.change(within(dialog).getByRole('textbox'), {
      target: { value: PREPARED.confirmationPhrase },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: copy.dialog.confirm }))
    expect(
      await within(dialog).findByText(operatorRefusalMessage('CONFIRMATION_MISMATCH')),
    ).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBe(dialog)
    expect(calls).toBe(1)
  })

  it('최근 본인 확인이 필요하면 세 로그인 수단을 보이고 자동으로 이어 하지 않는다', async () => {
    await openAsOperator()
    stubOperatorExportPrepare(() => error('REAUTHENTICATION_REQUIRED', 403))
    fireEvent.change(screen.getByLabelText(copy.export.reasonLabel), {
      target: { value: '분기 점검' },
    })
    fireEvent.click(screen.getByRole('button', { name: copy.export.start }))
    expect(await screen.findByText(copy.export.reauthLabel)).toBeInTheDocument()
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.getByRole('button', { name: authContent.settings.reauth.labels[provider] }),
      ).toBeInTheDocument()
    }
    expect(screen.getByRole('status').textContent).toBe(
      operatorRefusalMessage('REAUTHENTICATION_REQUIRED'),
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('취소는 취소 요청을 보내고 대화상자를 닫는다', async () => {
    await openAsOperator()
    stubOperatorExportPrepare(() => Promise.resolve(jsonResponse(PREPARED)))
    stubOperatorExportCancel(() => Promise.resolve(new Response(null, { status: 204 })))
    const dialog = await openDialog()
    fireEvent.click(within(dialog).getByRole('button', { name: copy.dialog.cancel }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(getObservedAllowedRequests()).toContain(
      'POST /api/operator/commands/audit-export/cancel',
    )
    expect(screen.getByRole('status').textContent).toBe(copy.status.cancelled)
  })

  it('응답을 받지 못한 확정은 대화상자를 열어 둔 채 단정하지 않고, 닫으면 operation 조회로 안내한다', async () => {
    await openAsOperator()
    stubOperatorExportPrepare(() => Promise.resolve(jsonResponse(PREPARED)))
    stubOperatorExportConfirm(() => Promise.reject(new Error('network')))
    stubOperatorExportCancel(() => Promise.resolve(new Response(null, { status: 204 })))
    stubOperatorOperation('op_prepared', () =>
      Promise.resolve(jsonResponse({ operationId: 'op_prepared', items: [entry(9, 'COMPLETED')] })),
    )
    const dialog = await openDialog()
    fireEvent.change(within(dialog).getByRole('textbox'), {
      target: { value: PREPARED.confirmationPhrase },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: copy.dialog.confirm }))
    expect(await within(dialog).findByText(copy.export.uncertain)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBe(dialog)

    fireEvent.click(within(dialog).getByRole('button', { name: copy.dialog.cancel }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    // 닫기는 취소가 아니다 — 실행됐을 수 있다.
    expect(screen.getByRole('status').textContent).toBe(copy.export.uncertainClosed)
    const lookup = screen.getByLabelText(copy.operation.label)
    expect(lookup).toHaveValue('op_prepared')
    fireEvent.click(screen.getByRole('button', { name: copy.operation.search }))
    expect(await screen.findByText('사유 9')).toBeInTheDocument()

    // 사용자가 칸을 비우면 그 값이 이긴다 — 불확실한 operation으로 다시 채우지 않는다.
    fireEvent.change(lookup, { target: { value: '' } })
    expect(lookup).toHaveValue('')
  })

  for (const [label, answer] of [
    ['공통 본문 500', () => error('INTERNAL_ERROR', 500)],
    [
      '본문 없는 502',
      () => Promise.resolve(new Response('<html>bad gateway</html>', { status: 502 })),
    ],
  ] as const) {
    it(`확정의 ${label}은 불확실이고 같은 키로 다시 확정하면 같은 요청이다`, async () => {
      await openAsOperator()
      stubOperatorExportPrepare(() => Promise.resolve(jsonResponse(PREPARED)))
      const keys: string[] = []
      let calls = 0
      stubOperatorExportConfirm((init) => {
        keys.push(new Headers(init?.headers).get('Idempotency-Key') ?? '')
        calls += 1
        return calls === 1
          ? answer()
          : Promise.resolve(
              jsonResponse({
                operationId: 'op_prepared',
                outcome: 'COMPLETED',
                command: 'audit-export',
                target: PREPARED.target,
                upperSequence: 2,
                rowCount: 1,
                truncated: false,
                rows: [entry(2)],
              }),
            )
      })
      const dialog = await openDialog()
      fireEvent.change(within(dialog).getByRole('textbox'), {
        target: { value: PREPARED.confirmationPhrase },
      })
      fireEvent.click(within(dialog).getByRole('button', { name: copy.dialog.confirm }))
      expect(await within(dialog).findByText(copy.export.uncertain)).toBeInTheDocument()
      expect(screen.queryByText(/바뀐 것은 없습니다/)).not.toBeInTheDocument()

      fireEvent.click(within(dialog).getByRole('button', { name: copy.dialog.confirm }))
      expect(
        await screen.findByRole('heading', { level: 3, name: copy.export.result.heading }),
      ).toBeInTheDocument()
      expect(keys).toHaveLength(2)
      expect(keys[1]).toBe(keys[0])
    })
  }

  it('첫 감사 조회가 실패하면 운영자 제어를 그리지 않고 일반 문장을 낸다', async () => {
    stubSessionRequest(() => authenticated())
    stubOperatorAudit(() => error('INTERNAL_ERROR', 500))
    render(<OperatorScreen />)
    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(copy.loadFailed)
    })
    expect(screen.queryByRole('heading', { name: copy.audit.heading })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: copy.export.start })).not.toBeInTheDocument()
    expect(document.body.textContent).not.toContain('운영자')
  })

  it('operation 조회의 실패는 문장으로 말한다', async () => {
    await openAsOperator()
    stubOperatorOperation('op_broken', () => error('INTERNAL_ERROR', 500))
    fireEvent.change(screen.getByLabelText(copy.operation.label), {
      target: { value: 'op_broken' },
    })
    fireEvent.click(screen.getByRole('button', { name: copy.operation.search }))
    expect(await screen.findByText(copy.operation.failed)).toBeInTheDocument()
  })

  it('준비의 401은 만료 표지와 함께 진입 화면으로 보낸다', async () => {
    await openAsOperator()
    stubOperatorExportPrepare(() => error('UNAUTHENTICATED', 401))
    fireEvent.change(screen.getByLabelText(copy.export.reasonLabel), {
      target: { value: '분기 점검' },
    })
    fireEvent.click(screen.getByRole('button', { name: copy.export.start }))
    await waitFor(() => {
      expect(replaceLocation).toHaveBeenCalledWith('/?auth=expired')
    })
  })

  it('operation 조회의 401도 만료로 보낸다', async () => {
    await openAsOperator()
    stubOperatorOperation('op_expired', () => error('UNAUTHENTICATED', 401))
    fireEvent.change(screen.getByLabelText(copy.operation.label), {
      target: { value: 'op_expired' },
    })
    fireEvent.click(screen.getByRole('button', { name: copy.operation.search }))
    await waitFor(() => {
      expect(replaceLocation).toHaveBeenCalledWith('/?auth=expired')
    })
  })

  it('세션이 끝났다는 답은 만료 표지와 함께 진입 화면으로 보낸다', async () => {
    stubSessionRequest(() => authenticated())
    stubOperatorAudit(() => error('UNAUTHENTICATED', 401))
    render(<OperatorScreen />)
    await waitFor(() => {
      expect(replaceLocation).toHaveBeenCalledWith('/?auth=expired')
    })
  })
})
