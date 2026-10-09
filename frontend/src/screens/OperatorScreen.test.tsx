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
  stubOperatorRestrictionCommand,
  stubOperatorRestrictions,
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
    policyVersion: 'OPERATOR-COMMAND-1.1',
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
  policyVersion: 'OPERATOR-COMMAND-1.1',
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
  // 활성 제한 목록(Story 3.2). 운영자임이 감사 조회로 확인된 뒤에만 읽힌다. 시험이 다시 열면 그 응답이 이긴다.
  stubOperatorRestrictions(() => Promise.resolve(jsonResponse({ items: [] })))
})

describe('OperatorScreen', () => {
  it('비운영자에게는 없는 화면과 같은 문장 하나만 그리고 운영자 제어를 DOM에 두지 않는다', async () => {
    stubSessionRequest(() => authenticated())
    stubOperatorAudit(() => error('NOT_FOUND', 404))
    stubOperatorRestrictions(() => error('NOT_FOUND', 404))
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
    stubOperatorRestrictions(() => error('NOT_FOUND', 404))
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

  // ------------------------------------------------------------------ Story 3.2 영향 범위 제한

  const PLAN_ID = '0b3c1f6e-8a3d-4b2c-9f1e-2d4a6c8e0f12'

  const PREPARED_RESTRICTION = {
    operationId: 'op_restrict',
    command: 'restrict-sync',
    scope: 'SYNC:PLAN',
    target: PLAN_ID,
    reason: '계획 입력 이상',
    confirmationPhrase: `동기화 제한 계획 ${PLAN_ID}`,
    policyVersion: 'OPERATOR-COMMAND-1.1',
    requestedAt: '2026-10-09T00:00:00Z',
    impact: {
      blockedStages: ['INCREMENTAL_SYNC', 'PLAN_END'],
      affectedPlans: 1,
      affectedConnections: 1,
      remainingFeatures: ['NEW_CONNECTION', 'SYNC', 'CALCULATION_DISPLAY', 'ADVERTISING'],
    },
  }

  function activeRestriction(overrides: Record<string, unknown>): Record<string, unknown> {
    return {
      restrictionId: 'r-1',
      feature: 'SYNC',
      scope: 'PLAN',
      target: PLAN_ID,
      targetErased: false,
      effectiveFrom: '2026-10-09T00:00:00Z',
      reason: '계획 입력 이상',
      actorDigest: 'b'.repeat(64),
      ...overrides,
    }
  }

  async function openRestrictionSection(): Promise<HTMLElement> {
    await openAsOperator()
    return screen.findByRole('heading', { level: 2, name: copy.restriction.heading })
  }

  it('활성 제한을 구체 우선으로 그리고 비식별된 대상은 삭제됨으로만 보인다', async () => {
    stubOperatorRestrictions(() =>
      Promise.resolve(
        jsonResponse({
          items: [
            activeRestriction({}),
            activeRestriction({
              restrictionId: 'r-2',
              scope: 'PORTFOLIO',
              target: undefined,
              targetErased: true,
            }),
            activeRestriction({
              restrictionId: 'r-3',
              feature: 'NEW_CONNECTION',
              scope: 'GLOBAL',
              target: 'ALL',
              reason: undefined,
              actorDigest: undefined,
            }),
          ],
        }),
      ),
    )
    await openRestrictionSection()
    const list = screen.getByRole('list', { name: copy.restriction.active.heading })
    const rows = within(list).getAllByRole('listitem')
    expect(rows).toHaveLength(3)
    expect(rows[0]?.textContent).toContain(PLAN_ID)
    expect(rows[1]?.textContent).toContain(copy.restriction.active.erased)
    expect(rows[2]?.textContent).toContain('신규 연결')
    expect(rows[2]?.textContent).toContain('전역')
    expect(rows[2]?.textContent).toContain(copy.restriction.active.noReason)
  })

  it('기능이 받지 않는 범위는 고를 수 없다', async () => {
    await openRestrictionSection()
    fireEvent.change(screen.getByLabelText(copy.restriction.featureLabel), {
      target: { value: 'NEW_CONNECTION' },
    })
    const scope = screen.getByLabelText(copy.restriction.scopeLabel)
    expect(
      within(scope)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['전역'])
    fireEvent.change(screen.getByLabelText(copy.restriction.featureLabel), {
      target: { value: 'CALCULATION_DISPLAY' },
    })
    expect(
      within(screen.getByLabelText(copy.restriction.scopeLabel))
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['전역', '계획', '포트폴리오', '계산 정책 버전'])
  })

  it('제한 준비 → 미리보기·확인 문구 재입력 → 확정이 같은 키로 가고 목록을 다시 읽는다', async () => {
    let listCalls = 0
    stubOperatorRestrictions(() => {
      listCalls += 1
      return Promise.resolve(
        jsonResponse({ items: listCalls === 1 ? [] : [activeRestriction({})] }),
      )
    })
    await openRestrictionSection()
    const keys: string[] = []
    const bodies: unknown[] = []
    stubOperatorRestrictionCommand('restrict-sync', 'prepare', (init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key') ?? '')
      expect(new Headers(init?.headers).get(CSRF_HEADER)).toBe(CSRF_TOKEN)
      bodies.push(JSON.parse(init?.body as string))
      return Promise.resolve(jsonResponse(PREPARED_RESTRICTION))
    })
    stubOperatorRestrictionCommand('restrict-sync', 'confirm', (init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key') ?? '')
      bodies.push(JSON.parse(init?.body as string))
      return Promise.resolve(
        jsonResponse({
          operationId: 'op_restrict',
          outcome: 'COMPLETED',
          command: 'restrict-sync',
          feature: 'SYNC',
          scope: 'PLAN',
          restrictionId: 'r-1',
          effectiveFrom: '2026-10-09T00:00:01Z',
        }),
      )
    })

    fireEvent.change(screen.getByLabelText(copy.restriction.scopeLabel), {
      target: { value: 'PLAN' },
    })
    fireEvent.change(screen.getByLabelText(copy.restriction.targetLabel), {
      target: { value: PLAN_ID },
    })
    fireEvent.change(screen.getByLabelText(copy.restriction.reasonLabel), {
      target: { value: '계획 입력 이상' },
    })
    fireEvent.click(screen.getByRole('button', { name: copy.restriction.start }))
    const dialog = await screen.findByRole('dialog', { name: copy.restrictionDialog.title })
    // 취소가 기본 포커스이고, 미리보기는 수만 말한다.
    expect(
      within(dialog).getByRole('button', { name: copy.restrictionDialog.cancel }),
    ).toHaveFocus()
    expect(dialog.textContent).toContain('증분 동기화, 계획 종료')
    expect(dialog.textContent).toContain(copy.restrictionDialog.affectedPlans(1))
    expect(dialog.textContent).toContain(copy.restrictionDialog.kept)
    const confirm = within(dialog).getByRole('button', { name: copy.restrictionDialog.confirm })
    expect(confirm).toHaveAttribute('aria-disabled', 'true')
    fireEvent.change(within(dialog).getByRole('textbox'), {
      target: { value: PREPARED_RESTRICTION.confirmationPhrase },
    })
    fireEvent.click(confirm)

    const result = await screen.findByRole('heading', {
      level: 3,
      name: copy.restriction.result.heading,
    })
    await waitFor(() => {
      expect(result).toHaveFocus()
    })
    expect(screen.getByRole('status').textContent).toBe(
      copy.status.restrictionCompleted('동기화', '계획'),
    )
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
    expect(bodies[0]).toEqual({ scope: 'PLAN', target: PLAN_ID, reason: '계획 입력 이상' })
    expect(bodies[1]).toEqual({
      operationId: 'op_restrict',
      confirmation: PREPARED_RESTRICTION.confirmationPhrase,
    })
    await waitFor(() => {
      expect(listCalls).toBe(2)
    })
    expect(
      within(screen.getByRole('list', { name: copy.restriction.active.heading })).getAllByRole(
        'listitem',
      ),
    ).toHaveLength(1)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('전역 제한은 대상을 입력받지 않고 ALL을 보낸다', async () => {
    await openRestrictionSection()
    const bodies: unknown[] = []
    stubOperatorRestrictionCommand('restrict-advertising', 'prepare', (init) => {
      bodies.push(JSON.parse(init?.body as string))
      return error('ALREADY_RESTRICTED', 409)
    })
    fireEvent.change(screen.getByLabelText(copy.restriction.featureLabel), {
      target: { value: 'ADVERTISING' },
    })
    expect(screen.getByLabelText(copy.restriction.targetLabel)).toHaveAttribute('readonly')
    fireEvent.change(screen.getByLabelText(copy.restriction.reasonLabel), {
      target: { value: '광고 SDK 오류' },
    })
    fireEvent.click(screen.getByRole('button', { name: copy.restriction.start }))
    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(
        operatorRefusalMessage('ALREADY_RESTRICTED'),
      )
    })
    expect(bodies).toEqual([{ scope: 'GLOBAL', target: 'ALL', reason: '광고 SDK 오류' }])
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('제한 목록이 404면(제한 권한 없음) 제한 영역을 그리지 않는다', async () => {
    stubOperatorRestrictions(() => error('NOT_FOUND', 404))
    await openAsOperator()
    await waitFor(() => {
      expect(getObservedAllowedRequests()).toContain('GET /api/operator/restrictions')
    })
    expect(
      screen.queryByRole('heading', { name: copy.restriction.heading }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: copy.restriction.start })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: copy.audit.heading })).toBeInTheDocument()
  })

  // ------------------------------------------------------------------ Story 3.2 리뷰 patch: 영역별 판정·불확실·취소

  it('command 권한만 가진 운영자는 감사 영역 없이 제한 영역을 본다', async () => {
    stubSessionRequest(() => authenticated())
    stubOperatorAudit(() => error('NOT_FOUND', 404))
    stubOperatorRestrictions(() =>
      Promise.resolve(jsonResponse({ items: [activeRestriction({})] })),
    )
    render(<OperatorScreen />)
    expect(
      await screen.findByRole('heading', { level: 2, name: copy.restriction.heading }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: copy.heading })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: copy.audit.heading })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: copy.export.start })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: copy.restriction.start })).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(copy.status.restrictionsLoaded(1))
    })
  })

  it('audit 권한만 가진 운영자는 제한 영역 없이 감사 영역을 본다', async () => {
    stubOperatorRestrictions(() => error('NOT_FOUND', 404))
    await openAsOperator()
    await waitFor(() => {
      expect(getObservedAllowedRequests()).toContain('GET /api/operator/restrictions')
    })
    expect(screen.getByRole('heading', { level: 2, name: copy.audit.heading })).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: copy.restriction.heading }),
    ).not.toBeInTheDocument()
  })

  it('첫 제한 목록 조회가 실패하면 실패 문장과 다시 조회만 보이고, 다시 조회가 성공하면 폼을 그린다', async () => {
    let calls = 0
    stubOperatorRestrictions(() => {
      calls += 1
      return calls === 1
        ? error('INTERNAL_ERROR', 500)
        : Promise.resolve(jsonResponse({ items: [] }))
    })
    await openRestrictionSection()
    expect(await screen.findByText(copy.restriction.active.failed)).toBeInTheDocument()
    // 실패와 "없음"을 함께 말하지 않는다.
    expect(screen.queryByText(copy.restriction.active.empty)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: copy.restriction.start })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: copy.restriction.active.reload }))
    expect(await screen.findByRole('button', { name: copy.restriction.start })).toBeInTheDocument()
    expect(screen.getByText(copy.restriction.active.empty)).toBeInTheDocument()
    expect(screen.queryByText(copy.restriction.active.failed)).not.toBeInTheDocument()
  })

  it('다시 조회가 실패하면 받아 둔 행을 두고 실패만 말한다', async () => {
    let calls = 0
    stubOperatorRestrictions(() => {
      calls += 1
      return calls === 1
        ? Promise.resolve(jsonResponse({ items: [activeRestriction({})] }))
        : error('INTERNAL_ERROR', 500)
    })
    await openRestrictionSection()
    await screen.findByText(PLAN_ID)
    fireEvent.click(screen.getByRole('button', { name: copy.restriction.active.reload }))
    expect(await screen.findByText(copy.restriction.active.failed)).toBeInTheDocument()
    expect(screen.getByText(PLAN_ID)).toBeInTheDocument()
    expect(screen.queryByText(copy.restriction.active.empty)).not.toBeInTheDocument()
  })

  /** 계획 범위 동기화 제한을 준비해 대화상자를 연다. */
  async function openRestrictionDialog(): Promise<HTMLElement> {
    fireEvent.change(screen.getByLabelText(copy.restriction.scopeLabel), {
      target: { value: 'PLAN' },
    })
    fireEvent.change(screen.getByLabelText(copy.restriction.targetLabel), {
      target: { value: PLAN_ID },
    })
    fireEvent.change(screen.getByLabelText(copy.restriction.reasonLabel), {
      target: { value: '계획 입력 이상' },
    })
    fireEvent.click(screen.getByRole('button', { name: copy.restriction.start }))
    return screen.findByRole('dialog', { name: copy.restrictionDialog.title })
  }

  const COMPLETED_RESTRICTION = {
    operationId: 'op_restrict',
    outcome: 'COMPLETED',
    command: 'restrict-sync',
    feature: 'SYNC',
    scope: 'PLAN',
    restrictionId: 'r-1',
    effectiveFrom: '2026-10-09T00:00:01Z',
  }

  it('확정이 ALREADY_RESTRICTED로 거절되면 목록을 다시 읽는다', async () => {
    let listCalls = 0
    stubOperatorRestrictions(() => {
      listCalls += 1
      return Promise.resolve(
        jsonResponse({ items: listCalls === 1 ? [] : [activeRestriction({})] }),
      )
    })
    await openRestrictionSection()
    stubOperatorRestrictionCommand('restrict-sync', 'prepare', () =>
      Promise.resolve(jsonResponse(PREPARED_RESTRICTION)),
    )
    stubOperatorRestrictionCommand('restrict-sync', 'confirm', () =>
      error('ALREADY_RESTRICTED', 409),
    )
    const dialog = await openRestrictionDialog()
    fireEvent.change(within(dialog).getByRole('textbox'), {
      target: { value: PREPARED_RESTRICTION.confirmationPhrase },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: copy.restrictionDialog.confirm }))
    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(
        operatorRefusalMessage('ALREADY_RESTRICTED'),
      )
    })
    await waitFor(() => {
      expect(listCalls).toBe(2)
    })
    expect(await screen.findByText(PLAN_ID)).toBeInTheDocument()
  })

  it('제한 확정의 문구 불일치는 대화상자를 열어 둔 채 말한다', async () => {
    await openRestrictionSection()
    stubOperatorRestrictionCommand('restrict-sync', 'prepare', () =>
      Promise.resolve(jsonResponse(PREPARED_RESTRICTION)),
    )
    let calls = 0
    stubOperatorRestrictionCommand('restrict-sync', 'confirm', () => {
      calls += 1
      return error('CONFIRMATION_MISMATCH', 409)
    })
    const dialog = await openRestrictionDialog()
    fireEvent.change(within(dialog).getByRole('textbox'), {
      target: { value: PREPARED_RESTRICTION.confirmationPhrase },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: copy.restrictionDialog.confirm }))
    expect(
      await within(dialog).findByText(operatorRefusalMessage('CONFIRMATION_MISMATCH')),
    ).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBe(dialog)
    expect(calls).toBe(1)
  })

  it('제한 취소는 취소 요청을 보내고 대화상자를 닫는다', async () => {
    await openRestrictionSection()
    stubOperatorRestrictionCommand('restrict-sync', 'prepare', () =>
      Promise.resolve(jsonResponse(PREPARED_RESTRICTION)),
    )
    stubOperatorRestrictionCommand('restrict-sync', 'cancel', () =>
      Promise.resolve(new Response(null, { status: 204 })),
    )
    const dialog = await openRestrictionDialog()
    fireEvent.click(within(dialog).getByRole('button', { name: copy.restrictionDialog.cancel }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(getObservedAllowedRequests()).toContain(
      'POST /api/operator/commands/restrict-sync/cancel',
    )
    expect(screen.getByRole('status').textContent).toBe(copy.status.restrictionCancelled)
  })

  for (const [label, answer] of [
    ['공통 본문 500', () => error('INTERNAL_ERROR', 500)],
    ['무응답', () => Promise.reject(new Error('network'))],
  ] as const) {
    it(`제한 확정의 ${label}은 불확실이고 같은 키로 다시 확정하면 같은 요청이다`, async () => {
      await openRestrictionSection()
      stubOperatorRestrictionCommand('restrict-sync', 'prepare', () =>
        Promise.resolve(jsonResponse(PREPARED_RESTRICTION)),
      )
      stubOperatorRestrictionCommand('restrict-sync', 'cancel', () =>
        Promise.resolve(new Response(null, { status: 204 })),
      )
      const keys: string[] = []
      let calls = 0
      stubOperatorRestrictionCommand('restrict-sync', 'confirm', (init) => {
        keys.push(new Headers(init?.headers).get('Idempotency-Key') ?? '')
        calls += 1
        return calls === 1 ? answer() : Promise.resolve(jsonResponse(COMPLETED_RESTRICTION))
      })
      const dialog = await openRestrictionDialog()
      fireEvent.change(within(dialog).getByRole('textbox'), {
        target: { value: PREPARED_RESTRICTION.confirmationPhrase },
      })
      fireEvent.click(within(dialog).getByRole('button', { name: copy.restrictionDialog.confirm }))
      expect(await within(dialog).findByText(copy.restriction.uncertain)).toBeInTheDocument()
      expect(screen.getByRole('dialog')).toBe(dialog)
      expect(screen.queryByText(/바뀐 것은 없습니다/)).not.toBeInTheDocument()

      fireEvent.click(within(dialog).getByRole('button', { name: copy.restrictionDialog.confirm }))
      expect(
        await screen.findByRole('heading', { level: 3, name: copy.restriction.result.heading }),
      ).toBeInTheDocument()
      expect(keys).toHaveLength(2)
      expect(keys[1]).toBe(keys[0])
    })
  }

  it('응답을 받지 못한 제한 확정 뒤 닫으면 operation 조회 칸에 그 operation을 채운다', async () => {
    await openRestrictionSection()
    stubOperatorRestrictionCommand('restrict-sync', 'prepare', () =>
      Promise.resolve(jsonResponse(PREPARED_RESTRICTION)),
    )
    stubOperatorRestrictionCommand('restrict-sync', 'confirm', () =>
      Promise.reject(new Error('network')),
    )
    stubOperatorRestrictionCommand('restrict-sync', 'cancel', () =>
      Promise.resolve(new Response(null, { status: 204 })),
    )
    const dialog = await openRestrictionDialog()
    fireEvent.change(within(dialog).getByRole('textbox'), {
      target: { value: PREPARED_RESTRICTION.confirmationPhrase },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: copy.restrictionDialog.confirm }))
    expect(await within(dialog).findByText(copy.restriction.uncertain)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: copy.restrictionDialog.cancel }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(screen.getByRole('status').textContent).toBe(copy.restriction.uncertainClosed)
    expect(screen.getByLabelText(copy.operation.label)).toHaveValue('op_restrict')
  })
})
