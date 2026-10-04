import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { NextPlanBaselinePanel } from '../components/NextPlanBaselinePanel'
import { nextPlanContent as copy } from '../content/nextPlan'
import { supersedes, useDashboard, type PlanResult } from '../session/useDashboard'
import {
  dashboardFixture,
  endedDashboardFixture,
  planResultFixture,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubDashboard,
  stubHistoricalDashboard,
  stubNextBaseline,
  stubPlanHistory,
  stubSessionRequest,
} from '../test/setup'
import { OnboardingScreen } from './OnboardingScreen'
import { SignedInScreen } from './SignedInScreen'

function response(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
const normal = {
  eligible: true,
  reason: 'AVAILABLE',
  job: { status: 'SUCCEEDED' },
  baseline: { requestedAt: '2026-10-04T00:00:00.000Z', completedAt: '2026-10-04T00:00:01.000Z' },
  snapshot: {
    decimal: '1250.5',
    measuredAt: '2026-10-04T00:00:00.100Z',
    snapshotId: 'new-snapshot',
    sourceSnapshotId: 'new-source',
    sourcePolicyVersion: 'bybit-readonly-uta-1.0',
  },
}

describe('후속 계획 화면', () => {
  it('새 기준은 원값과 provenance·측정 시각·경과 시간을 보이고 이전 조정을 복사하지 않는다', async () => {
    stubNextBaseline(() => Promise.resolve(response(normal)))
    render(<NextPlanBaselinePanel csrfToken="token" />)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    expect(await screen.findByText('1250.5')).toBeInTheDocument()
    expect(screen.getByText('1000')).toBeInTheDocument()
    expect(screen.getByText(/new-snapshot/)).toBeInTheDocument()
    expect(screen.getByText(copy.intro)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: copy.conditions })).toHaveAttribute(
      'href',
      '/plan-conditions',
    )
    expect(getObservedStorageEntries()).toEqual([])
  })
  it.each(['0', '-3.5'])('정상 기준 %s는 숨기지 않고 생성만 차단한다', async (decimal) => {
    stubNextBaseline(() =>
      Promise.resolve(response({ ...normal, snapshot: { ...normal.snapshot, decimal } })),
    )
    render(<NextPlanBaselinePanel csrfToken="token" />)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    expect(await screen.findByText(decimal)).toBeInTheDocument()
    expect(screen.getByText(copy.zero)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: copy.conditions })).not.toBeInTheDocument()
  })
  it('0 기준을 새로 준비하면 원값을 유지하고 조건 화면으로 전환하지 않는다', async () => {
    let reads = 0
    let prepared = 0
    stubNextBaseline(() =>
      Promise.resolve(
        response(
          reads++ === 0
            ? normal
            : {
                ...normal,
                snapshot: { ...normal.snapshot, snapshotId: 'zero-preparation', decimal: '0' },
              },
        ),
      ),
    )
    stubNextBaseline(() => Promise.resolve(response({}, 202)), 'POST')
    render(
      <NextPlanBaselinePanel
        csrfToken="token"
        onPrepared={() => {
          prepared += 1
        }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await screen.findByText('1250.5')
    fireEvent.click(screen.getByRole('button', { name: copy.prepare }))
    expect(await screen.findByText(copy.zero)).toBeInTheDocument()
    expect(screen.getByText('0')).toBeInTheDocument()
    expect(prepared).toBe(0)
  })
  it('미확인 입력은 0으로 바꾸지 않고 사유와 생성 차단을 보인다', async () => {
    stubNextBaseline(() =>
      Promise.resolve(
        response({ ...normal, eligible: false, reason: 'INPUT_UNCONFIRMED', snapshot: null }),
      ),
    )
    render(<NextPlanBaselinePanel csrfToken="token" />)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    expect(await screen.findByText(copy.unconfirmed)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: copy.conditions })).not.toBeInTheDocument()
    expect(screen.queryByText('0')).not.toBeInTheDocument()
  })
  it('새 기준 요청은 CSRF·동일 출처·멱등 키를 싣고 거절 시 마지막 정상값을 유지한다', async () => {
    stubNextBaseline(() => Promise.resolve(response(normal)))
    stubNextBaseline((init) => {
      expect(init?.credentials).toBe('same-origin')
      const headers = new Headers(init?.headers)
      expect(headers.get('X-CSRF-TOKEN')).toBe('token')
      expect(headers.get('Idempotency-Key')).toHaveLength(36)
      return Promise.resolve(response({}, 409))
    }, 'POST')
    render(<NextPlanBaselinePanel csrfToken="token" />)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await screen.findByText('1250.5')
    fireEvent.click(screen.getByRole('button', { name: copy.prepare }))
    expect(await screen.findByText(copy.failed)).toBeInTheDocument()
    expect(screen.getByText('1250.5')).toBeInTheDocument()
  })
  it.each([401, 403])('접근 거절 %s는 기준 금융값을 지운다', async (status) => {
    stubNextBaseline(() => Promise.resolve(response(normal)))
    stubNextBaseline(() => Promise.resolve(response({}, status)), 'POST')
    render(<NextPlanBaselinePanel csrfToken="token" />)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await screen.findByText('1250.5')
    fireEvent.click(screen.getByRole('button', { name: copy.prepare }))
    await screen.findByText(copy.denied)
    expect(screen.queryByText('1250.5')).not.toBeInTheDocument()
  })
  it('선택 기록의 응답이 현재 조회와 경합해도 다른 대상 값을 섞지 않는다', async () => {
    let old: ((answer: Response) => void) | undefined
    stubDashboard(
      () =>
        new Promise<Response>((resolve) => {
          old = resolve
        }),
    )
    const history = endedDashboardFixture()
    const planId = (history.result as Record<string, unknown>).planId as string
    stubHistoricalDashboard(planId, () => Promise.resolve(response(history)))
    const { result, rerender } = renderHook(
      ({ selected }: { selected: string | null }) => useDashboard(true, selected),
      { initialProps: { selected: null as string | null } },
    )
    await waitFor(() => {
      expect(old).toBeDefined()
    })
    rerender({ selected: planId })
    await waitFor(() => {
      expect(result.current.view.kind).toBe('ENDED')
    })
    await act(async () => {
      old?.(response(dashboardFixture()))
      await Promise.resolve()
    })
    expect(result.current.view.kind).toBe('ENDED')
    expect(result.current.historical).toBe(true)
  })
  it('새 계획 이후 도착한 이전 계획 묶음은 현재 기록을 역행시키지 않는다', () => {
    const old = planResultFixture() as unknown as PlanResult
    const next = {
      ...old,
      planId: 'successor',
      conditions: { ...old.conditions, startAt: '2026-10-04T00:00:00Z' },
    }
    expect(supersedes(next, old)).toBe(true)
    expect(supersedes(old, next)).toBe(false)
  })
  it.each([
    ['dashboard', SignedInScreen],
    ['onboarding', OnboardingScreen],
  ] as const)(
    '%s에서 종단 기록은 읽기 전용이고 단일 live region을 쓴다',
    async (destination, Screen) => {
      stubSessionRequest(() =>
        Promise.resolve(response({ authenticated: true, destination, csrfToken: 'token' })),
      )
      const history = endedDashboardFixture()
      const result = history.result as Record<string, unknown>
      const conditions = result.conditions as Record<string, unknown>
      stubDashboard(() => Promise.resolve(response(history)))
      stubPlanHistory(() =>
        Promise.resolve(
          response([
            {
              planId: result.planId,
              status: 'ENDED_EARLY',
              startAt: conditions.startAt,
              endAt: conditions.endAt,
            },
          ]),
        ),
      )
      stubHistoricalDashboard(result.planId as string, () => Promise.resolve(response(history)))
      render(<Screen />)
      await screen.findByText(copy.intro)
      fireEvent.click(screen.getByRole('button', { name: copy.historyOpen }))
      fireEvent.click(await screen.findByRole('button', { name: copy.select }))
      await screen.findByRole('button', { name: copy.current })
      expect(screen.getAllByRole('status')).toHaveLength(1)
      expect(screen.queryByRole('button', { name: copy.inspect })).not.toBeInTheDocument()
      expect(getObservedAllowedRequests().filter((item) => item.startsWith('POST '))).toEqual([])
      expect(getObservedStorageEntries()).toEqual([])
    },
  )
})
