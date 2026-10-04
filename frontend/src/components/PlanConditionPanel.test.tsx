import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { DASHBOARD_PATH } from '../content/auth'
import { planConditionContent } from '../content/planConditions'
import { activePlanContent } from '../content/planResult'
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

/** The active plan summary as the server sends it: canonical decimals, `startAt` is `t0`. */
function activePlan(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    planId: 'plan-1',
    status: 'ACTIVE',
    decimal: '1234.56',
    startAt: snapshot.measuredAt,
    endAt: '2026-10-25T04:00:00Z',
    period: 'DAILY',
    targetRate: '0.1',
    snapshotId: snapshot.snapshotId,
    createdAt: '2026-09-27T01:00:00Z',
    ...overrides,
  }
}

function activeState(): Record<string, unknown> {
  return {
    eligible: false,
    reason: 'ACTIVE_PLAN_EXISTS',
    snapshot: null,
    openIntent: null,
    activePlan: activePlan(),
  }
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
      .mockResolvedValueOnce(response({ job: null }))
      .mockResolvedValueOnce(response(null))
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
    await screen.findByText('수집을 아직 시작하지 않았습니다.')
    expect(fetchMock).toHaveBeenCalledTimes(5)
    const confirmInit = fetchMock.mock.calls[2]?.[1]
    expect(confirmInit?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'csrf-value',
      'Idempotency-Key': 'plan-confirm-key-001',
    })
    expect(confirmInit?.body).toBe(JSON.stringify({ confirmationRef: 'opaque-reference' }))
    expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/snapshots/first/inputs?intentId=intent-1')
    expect(fetchMock.mock.calls[4]?.[0]).toBe(
      '/api/snapshots/first/cashflow-classification?intentId=intent-1',
    )
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

  it('shows the active plan summary with a link to its result instead of the condition form', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response({
          eligible: false,
          reason: 'ACTIVE_PLAN_EXISTS',
          snapshot: null,
          openIntent: null,
          activePlan: activePlan({ period: 'WEEKLY', targetRate: '0.0125' }),
        }),
      ),
    )
    render(<PlanConditionPanel csrfToken="csrf-value" ready />)

    expect(await screen.findByRole('heading', { name: '활성 계획' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(
      '이미 활성 계획이 있어 새 조건을 받지 않습니다',
    )
    expect(screen.getByText('1,234.56 USD')).toBeInTheDocument()
    expect(screen.getByText('매주')).toBeInTheDocument()
    expect(screen.getByText('1.25%')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '결과 보기' })).toHaveAttribute('href', '/dashboard')
    expect(screen.queryByLabelText('목표 주기율 (%)')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '조건 검토' })).not.toBeInTheDocument()
  })

  it('reloads into the active plan summary when a preview loses to a publication', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ eligible: true, snapshot }))
      .mockResolvedValueOnce(
        response({ code: 'ACTIVE_PLAN_EXISTS', message: 'x', correlationId: 'c' }, 409),
      )
      .mockResolvedValueOnce(response(activeState()))
    vi.stubGlobal('fetch', fetchMock)

    render(<PlanConditionPanel csrfToken="csrf-value" ready />)
    fireEvent.change(await screen.findByLabelText('종료 시각 (T)'), {
      target: { value: '2026-10-25T13:00' },
    })
    fireEvent.change(screen.getByLabelText('목표 주기율 (%)'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: '조건 검토' }))

    expect(await screen.findByRole('link', { name: '결과 보기' })).toBeInTheDocument()
    // Neutral: this screen does not know whether another tab or this one published the plan.
    expect(screen.getByRole('status')).toHaveTextContent(activePlanContent.conflict)
    expect(screen.getByRole('status')).not.toHaveTextContent('다른 탭')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('reloads into the active plan summary when a confirmation meets an active plan (409)', async () => {
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
        response({ code: 'ACTIVE_PLAN_EXISTS', message: 'x', correlationId: 'c' }, 409),
      )
      .mockResolvedValueOnce(response(activeState()))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'plan-confirm-key-409' })

    render(<PlanConditionPanel csrfToken="csrf-value" ready />)
    fireEvent.change(await screen.findByLabelText('종료 시각 (T)'), {
      target: { value: '2026-10-25T13:00' },
    })
    fireEvent.change(screen.getByLabelText('목표 주기율 (%)'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: '조건 검토' }))
    fireEvent.click(await screen.findByRole('button', { name: '이 조건 확인' }))

    expect(await screen.findByRole('heading', { name: '활성 계획' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '결과 보기' })).toHaveAttribute('href', '/dashboard')
    expect(screen.getByRole('status')).toHaveTextContent(activePlanContent.conflict)
    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/plans/conditions/confirm')
    expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/plans/conditions')
    expect(screen.queryByRole('heading', { name: '계획 조건 검증 완료' })).not.toBeInTheDocument()
  })

  it.each([
    ['a rate BigInt cannot read', { targetRate: '0.1%' }],
    ['a non-canonical amount', { decimal: '1.2e3' }],
    ['a time that does not parse', { startAt: 'yesterday' }],
  ])('does not render an active plan summary with %s', async (_name, broken) => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        response({
          eligible: false,
          reason: 'ACTIVE_PLAN_EXISTS',
          snapshot: null,
          openIntent: null,
          activePlan: activePlan(broken),
        }),
      ),
    )
    render(<PlanConditionPanel csrfToken="csrf-value" ready />)

    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent('현재 계획 조건을 확인할 수 없습니다')
    })
    expect(screen.queryByRole('heading', { name: '활성 계획' })).not.toBeInTheDocument()
  })

  it('does not show inputs when the server rejects qualification', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(response({ eligible: false, reason: 'NOT_ELIGIBLE' })),
    )
    render(<PlanConditionPanel csrfToken="csrf-value" ready />)

    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent('현재 계획 조건을 확인할 수 없습니다')
    })
    expect(screen.queryByLabelText('목표 주기율 (%)')).not.toBeInTheDocument()
  })

  describe('a plan ended early (Story 2.13)', () => {
    const ended = {
      eligible: false,
      reason: 'PLAN_ENDED',
      snapshot: null,
      openIntent: null,
      activePlan: null,
    }

    it('takes no new conditions and points to the record of the ended plan', async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(ended)))
      render(<PlanConditionPanel csrfToken="csrf-value" ready />)
      expect(await screen.findByText(planConditionContent.planEnded.label)).toBeVisible()
      expect(screen.getByText(planConditionContent.planEnded.text)).toBeVisible()
      expect(
        screen.getByRole('link', { name: planConditionContent.planEnded.dashboardLink }),
      ).toHaveAttribute('href', DASHBOARD_PATH)
      expect(screen.queryByLabelText('종료 시각 (T)')).not.toBeInTheDocument()
      expect(screen.queryByRole('button')).not.toBeInTheDocument()
      // It does not promise a new plan, nor judge the ended one.
      expect(planConditionContent.planEnded.text).not.toMatch(/성공|실패|완료|곧|예정입니다/)
    })

    it('reloads into the ended-plan notice when a preview is refused with PLAN_ENDED', async () => {
      const fetchMock = vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(response({ eligible: true, snapshot }))
        .mockResolvedValueOnce(
          response({ code: 'PLAN_ENDED', message: 'x', correlationId: 'c' }, 409),
        )
        .mockResolvedValueOnce(response(ended))
      vi.stubGlobal('fetch', fetchMock)
      render(<PlanConditionPanel csrfToken="csrf-value" ready />)
      fireEvent.change(await screen.findByLabelText('종료 시각 (T)'), {
        target: { value: '2026-10-25T13:00' },
      })
      fireEvent.change(screen.getByLabelText('목표 주기율 (%)'), { target: { value: '10' } })
      fireEvent.click(screen.getByRole('button', { name: '조건 검토' }))
      expect(await screen.findByText(planConditionContent.planEnded.label)).toBeVisible()
      expect(fetchMock).toHaveBeenCalledTimes(3)
      expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/plans/conditions')
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('reloads into the ended-plan notice when a confirmation is refused with PLAN_ENDED', async () => {
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
          response({ code: 'PLAN_ENDED', message: 'x', correlationId: 'c' }, 409),
        )
        .mockResolvedValueOnce(response(ended))
      vi.stubGlobal('fetch', fetchMock)
      vi.stubGlobal('crypto', { randomUUID: () => 'plan-confirm-key-ended' })
      render(<PlanConditionPanel csrfToken="csrf-value" ready />)
      fireEvent.change(await screen.findByLabelText('종료 시각 (T)'), {
        target: { value: '2026-10-25T13:00' },
      })
      fireEvent.change(screen.getByLabelText('목표 주기율 (%)'), { target: { value: '10' } })
      fireEvent.click(screen.getByRole('button', { name: '조건 검토' }))
      fireEvent.click(await screen.findByRole('button', { name: '이 조건 확인' }))
      expect(await screen.findByText(planConditionContent.planEnded.label)).toBeVisible()
      expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/plans/conditions/confirm')
      expect(fetchMock.mock.calls[3]?.[0]).toBe('/api/plans/conditions')
      expect(screen.queryByRole('heading', { name: '계획 조건 검증 완료' })).not.toBeInTheDocument()
    })
  })
})
