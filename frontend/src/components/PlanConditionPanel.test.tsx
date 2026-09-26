import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { PlanConditionPanel } from './PlanConditionPanel'

const snapshot = {
  decimal: '1234.56',
  measuredAt: '2026-09-25T00:00:00Z',
  publishedAt: '2026-09-25T00:00:01Z',
  portfolioId: 'portfolio-1',
  snapshotId: 'snapshot-1',
  membershipId: 'membership-1',
  connectionId: 'connection-1',
  providerAccountId: 'provider-account-1',
  sourceSnapshotId: 'source-1',
  inputStatus: 'VERIFIED',
  sourcePolicyVersion: 'bybit-readonly-uta-1.0',
  portfolioPolicyVersion: 'bybit-readonly-uta-1.0',
}

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('PlanConditionPanel', () => {
  it('converts percent text exactly and requires explicit confirmation', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ eligible: true, snapshot }))
      .mockResolvedValueOnce(
        response({
          confirmationRef: 'opaque-reference',
          expiresAt: '2026-09-25T00:10:00Z',
          snapshot,
          endAt: '2026-10-25T04:00:00Z',
          period: 'DAILY',
          targetRate: '0.1',
          calendarPolicy: 'CALENDAR-SEOUL-1.0',
          calculationPolicy: 'CALC-1.0',
        }),
      )
      .mockResolvedValueOnce(
        response(
          {
            accepted: true,
            conflict: false,
            intent: {
              intentId: 'intent-1',
              status: 'OPEN',
              decimal: '1234.56',
              measuredAt: snapshot.measuredAt,
              endAt: '2026-10-25T04:00:00Z',
              period: 'DAILY',
              targetRate: '0.1',
              snapshotId: snapshot.snapshotId,
              createdAt: '2026-09-25T00:01:00Z',
            },
          },
          201,
        ),
      )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'plan-confirm-key-001' })

    render(<PlanConditionPanel csrfToken="csrf-value" ready />)

    fireEvent.change(await screen.findByLabelText('종료 시각 (T)'), {
      target: { value: '2026-10-25T13:00' },
    })
    fireEvent.change(screen.getByLabelText('목표 주기율 (%)'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: '조건 검토' }))

    await screen.findByRole('heading', { name: '검토할 조건' })
    const previewRequest = fetchMock.mock.calls[1]
    expect(previewRequest?.[1]?.body).toBe(
      JSON.stringify({
        endAtSeoul: '2026-10-25T13:00',
        period: 'DAILY',
        targetRate: '0.1',
      }),
    )
    expect(screen.getByRole('button', { name: '조건 수정' })).toHaveFocus()
    expect(screen.getAllByText(/Asia\/Seoul/)).toHaveLength(2)
    expect(screen.getByText(/CALENDAR-SEOUL-1\.0/)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '이 조건 확인' }))
    await screen.findByRole('heading', { name: '계획 조건 검증 완료' })
    expect(fetchMock).toHaveBeenCalledTimes(3)
    const confirmInit = fetchMock.mock.calls[2]?.[1]
    expect(confirmInit?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'csrf-value',
      'Idempotency-Key': 'plan-confirm-key-001',
    })
    expect(confirmInit?.body).toBe(JSON.stringify({ confirmationRef: 'opaque-reference' }))
  })

  it('blocks out-of-range percentages before the preview request', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(response({ eligible: true, snapshot }))
    vi.stubGlobal('fetch', fetchMock)

    render(<PlanConditionPanel csrfToken="csrf-value" ready />)
    fireEvent.change(await screen.findByLabelText('종료 시각 (T)'), {
      target: { value: '2026-10-25T13:00' },
    })
    fireEvent.change(screen.getByLabelText('목표 주기율 (%)'), { target: { value: '1001' } })
    fireEvent.click(screen.getByRole('button', { name: '조건 검토' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('0%부터 1000%')
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(1)
    })
  })

  it('shows changed eligibility as a state error instead of an end-time error', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ eligible: true, snapshot }))
      .mockResolvedValueOnce(response({}, 409))
    vi.stubGlobal('fetch', fetchMock)

    render(<PlanConditionPanel csrfToken="csrf-value" ready />)
    fireEvent.change(await screen.findByLabelText('종료 시각 (T)'), {
      target: { value: '2026-10-25T13:00' },
    })
    fireEvent.change(screen.getByLabelText('목표 주기율 (%)'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: '조건 검토' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '계정, 연결 또는 기준 스냅샷 상태가 바뀌었습니다',
    )
    expect(
      screen.queryByText('서울 시각을 YYYY-MM-DDTHH:mm 형식으로 입력하고 기간을 확인하세요.'),
    ).not.toBeInTheDocument()
  })

  it('rejects a malformed preview response without rendering invalid values', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ eligible: true, snapshot }))
      .mockResolvedValueOnce(
        response({
          confirmationRef: 'opaque-reference',
          expiresAt: '2026-09-25T00:10:00Z',
          snapshot: null,
          endAt: '2026-10-25T04:00:00Z',
          period: 'DAILY',
          targetRate: '0.1',
          calendarPolicy: 'CALENDAR-SEOUL-1.0',
          calculationPolicy: 'CALC-1.0',
        }),
      )
    vi.stubGlobal('fetch', fetchMock)

    render(<PlanConditionPanel csrfToken="csrf-value" ready />)
    fireEvent.change(await screen.findByLabelText('종료 시각 (T)'), {
      target: { value: '2026-10-25T13:00' },
    })
    fireEvent.change(screen.getByLabelText('목표 주기율 (%)'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: '조건 검토' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('요청을 완료하지 못했습니다')
    expect(screen.queryByRole('heading', { name: '검토할 조건' })).not.toBeInTheDocument()
  })

  it('does not show inputs when the server rejects qualification', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(response({ eligible: false, reason: 'NOT_ELIGIBLE' })),
    )
    render(<PlanConditionPanel csrfToken="csrf-value" ready />)

    expect(await screen.findByRole('status')).toHaveTextContent(
      '현재 계획 조건을 확인할 수 없습니다',
    )
    expect(screen.queryByLabelText('목표 주기율 (%)')).not.toBeInTheDocument()
  })
})
