import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { NextPlanBaselinePanel } from '../components/NextPlanBaselinePanel'
import { PlanHistoryPanel } from '../components/PlanHistoryPanel'
import { nextPlanContent as copy } from '../content/nextPlan'
import { planCompletionContent } from '../content/planCompletion'
import { planConditionContent } from '../content/planConditions'
import { planEndContent } from '../content/planEnd'
import { planResultContent } from '../content/planResult'
import { planSyncContent } from '../content/planSync'
import { DASHBOARD_REQUEST_TIMEOUT_MS, parseDashboard, useDashboard } from '../session/useDashboard'
import {
  completedDashboardFixture,
  completedProgressResultFixture,
  completionRecordFixture,
  endedDashboardFixture,
  endedResultFixture,
} from '../test/planResultFixture'
import {
  stubDashboard,
  stubHistoricalDashboard,
  stubNextBaseline,
  stubPlanConditionState,
  stubPlanHistory,
  stubSessionRequest,
} from '../test/setup'
import { OnboardingScreen } from './OnboardingScreen'
import { PlanConditionScreen } from './PlanConditionScreen'
import { SignedInScreen } from './SignedInScreen'

const json = (value: unknown, status = 200): Response =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const normal = {
  eligible: true,
  reason: 'AVAILABLE',
  job: { status: 'SUCCEEDED' },
  baseline: { requestedAt: '2026-10-04T00:00:00Z', completedAt: '2026-10-04T00:00:01Z' },
  snapshot: {
    decimal: '1250.5',
    measuredAt: '2026-10-04T00:00:00.100Z',
    snapshotId: 'prepared',
    sourceSnapshotId: 'source',
    sourcePolicyVersion: 'bybit-readonly-uta-1.0',
  },
}
const tick = async (): Promise<void> => {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}
const advance = async (ms: number): Promise<void> => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}
afterEach(() => {
  vi.useRealTimers()
})

describe('후속 계획 리뷰 회귀', () => {
  it('명시적 과거 PENDING만 retryable=false를 수락하고 조작 필드를 거절한다', async () => {
    const result = completedProgressResultFixture()
    const historical = {
      ...completedDashboardFixture(result, completionRecordFixture(result, { retryable: false })),
      sync: null,
    }
    expect(parseDashboard(historical)).toBeNull()
    expect(parseDashboard(historical, true)).toMatchObject({
      state: 'COMPLETED',
      completion: { retryable: false },
      sync: { availability: 'UNAVAILABLE' },
      postEnd: null,
    })
    expect(parseDashboard({ ...historical, sync: {} }, true)).toBeNull()
    expect(parseDashboard({ ...historical, postEnd: {} }, true)).toBeNull()
    expect(parseDashboard({ ...historical, planEnd: {} }, true)).toBeNull()
    stubHistoricalDashboard(result.planId as string, () => Promise.resolve(json(historical)))
    const view = renderHook(() => useDashboard(true, result.planId as string))
    await tick()
    expect(view.result.current.view.kind).toBe('COMPLETED')
  })
  it('첫 조회 실패 뒤 명시적으로 재조회할 수 있다', async () => {
    let reads = 0
    stubNextBaseline(() => Promise.resolve(reads++ === 0 ? json({}, 503) : json(normal)))
    render(<NextPlanBaselinePanel csrfToken="csrf" />)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await screen.findByText(copy.failed)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    expect(await screen.findByText(normal.snapshot.decimal)).toBeInTheDocument()
  })
  it.each(['FAILED', 'BLOCKED'])(
    '재준비 %s는 같은 정상값을 유지하면서 대기 안내를 끝낸다',
    async (status) => {
      let reads = 0
      stubNextBaseline(() =>
        Promise.resolve(json(reads++ === 0 ? normal : { ...normal, job: { status } })),
      )
      stubNextBaseline(() => Promise.resolve(json({}, 202)), 'POST')
      render(<NextPlanBaselinePanel csrfToken="csrf" />)
      fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
      await screen.findByText(normal.snapshot.decimal)
      fireEvent.click(screen.getByRole('button', { name: copy.prepare }))
      await screen.findByText(copy.failed)
      expect(screen.getByText(normal.snapshot.decimal)).toBeInTheDocument()
      expect(screen.queryByText(copy.waiting)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: copy.prepare })).toBeEnabled()
    },
  )
  it('아직 준비하지 않은 AVAILABLE 상태는 실패 대신 첫 조회를 안내한다', async () => {
    stubNextBaseline(() =>
      Promise.resolve(
        json({ eligible: true, reason: 'AVAILABLE', job: null, baseline: null, snapshot: null }),
      ),
    )
    render(<NextPlanBaselinePanel csrfToken="csrf" />)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await screen.findByText(copy.initial)
    expect(screen.queryByText(copy.failed)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: copy.prepare })).toBeEnabled()
  })
  it.each([
    [401, 'malformed'],
    [401, 'stalled'],
    [403, 'malformed'],
    [403, 'stalled'],
  ] as const)(
    'polling 중 GET %s의 %s 본문과 무관하게 금융값과 busy를 지운다',
    async (status, body) => {
      vi.useFakeTimers()
      let reads = 0
      const denied = json({}, status)
      const readBody = vi.fn(() =>
        body === 'malformed'
          ? Promise.reject(new SyntaxError('invalid JSON'))
          : new Promise<unknown>(() => {
              /* deliberately stalled body */
            }),
      )
      denied.json = readBody
      stubNextBaseline(() =>
        Promise.resolve(
          reads++ === 0
            ? json({ ...normal, reason: 'JOB_IN_PROGRESS', job: { status: 'RUNNING' } })
            : denied,
        ),
      )
      render(<NextPlanBaselinePanel csrfToken="csrf" />)
      fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
      await tick()
      await advance(2000)
      expect(screen.getByText(copy.denied)).toBeInTheDocument()
      expect(screen.queryByText(normal.snapshot.decimal)).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: copy.inspect })).toBeEnabled()
      expect(readBody).not.toHaveBeenCalled()
    },
  )
  it('POST 본문 deadline 뒤 같은 key로 재시도하고 unmount에서 POST를 abort한다', async () => {
    vi.useFakeTimers()
    const keys: string[] = []
    let calls = 0
    let lastSignal: AbortSignal | null = null
    stubNextBaseline(() => Promise.resolve(json(normal)))
    stubNextBaseline((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key') ?? '')
      lastSignal = init?.signal ?? null
      if (calls++ !== 0) return Promise.resolve(json({}, 202))
      const value = json({})
      value.json = () =>
        new Promise<unknown>(() => {
          /* deliberately stalled body */
        })
      return Promise.resolve(value)
    }, 'POST')
    const { unmount } = render(<NextPlanBaselinePanel csrfToken="csrf" />)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await tick()
    fireEvent.click(screen.getByRole('button', { name: copy.prepare }))
    await tick()
    await advance(DASHBOARD_REQUEST_TIMEOUT_MS)
    expect(screen.getByText(copy.failed)).toBeInTheDocument()
    expect((lastSignal as AbortSignal | null)?.aborted).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: copy.prepare }))
    await tick()
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
    stubNextBaseline((init) => {
      lastSignal = init?.signal ?? null
      return new Promise<Response>(() => {
        /* pending headers */
      })
    }, 'POST')
    fireEvent.click(screen.getByRole('button', { name: copy.prepare }))
    unmount()
    expect((lastSignal as AbortSignal | null)?.aborted).toBe(true)
    await tick()
  })
  it('지연 GET 취소 catch가 진행 중 POST의 busy와 대기 안내를 덮지 않는다', async () => {
    let reads = 0
    let releasePost: ((value: Response) => void) | undefined
    stubNextBaseline(() =>
      reads++ === 0
        ? Promise.resolve(json(normal))
        : new Promise<Response>(() => {
            /* old read never settles itself */
          }),
    )
    stubNextBaseline(
      () =>
        new Promise<Response>((resolve) => {
          releasePost = resolve
        }),
      'POST',
    )
    render(<NextPlanBaselinePanel csrfToken="csrf" />)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await screen.findByText(normal.snapshot.decimal)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await tick()
    fireEvent.click(screen.getByRole('button', { name: copy.prepare }))
    await tick()
    expect(screen.getByRole('button', { name: copy.prepare })).toBeDisabled()
    expect(screen.getByText(copy.waiting)).toBeInTheDocument()
    expect(screen.queryByText(copy.failed)).not.toBeInTheDocument()
    stubNextBaseline(() => Promise.resolve(json(normal)))
    await act(async () => {
      releasePost?.(json({}, 202))
      await Promise.resolve()
    })
    await screen.findByText(copy.ready)
    expect(screen.getByRole('button', { name: copy.prepare })).toBeEnabled()
  })
  it('GET 본문 deadline과 unmount abort가 재조회 조작을 남긴다', async () => {
    vi.useFakeTimers()
    let signal: AbortSignal | null = null
    stubNextBaseline((init) => {
      signal = init?.signal ?? null
      const value = json({})
      value.json = () =>
        new Promise<unknown>(() => {
          /* stalled body */
        })
      return Promise.resolve(value)
    })
    const { unmount } = render(<NextPlanBaselinePanel csrfToken="csrf" />)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await tick()
    await advance(DASHBOARD_REQUEST_TIMEOUT_MS)
    expect(screen.getByText(copy.failed)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await tick()
    unmount()
    expect((signal as AbortSignal | null)?.aborted).toBe(true)
    await tick()
  })
  it('실제 조건 화면에서 QUEUED→RUNNING→양수 성공 뒤 입력 제목으로 초점을 옮기고 live region 하나를 유지한다', async () => {
    vi.useFakeTimers()
    let prepared = false
    let started = false
    let polls = 0
    stubSessionRequest(() =>
      Promise.resolve(json({ authenticated: true, destination: 'settings', csrfToken: 'csrf' })),
    )
    stubPlanConditionState(() =>
      Promise.resolve(
        json(
          prepared
            ? {
                eligible: true,
                reason: null,
                openIntent: null,
                snapshot: {
                  ...normal.snapshot,
                  portfolioId: 'portfolio',
                  membershipId: 'membership',
                  connectionId: 'connection',
                  providerAccountId: 'provider',
                  publishedAt: normal.baseline.completedAt,
                  inputStatus: 'VERIFIED',
                  portfolioPolicyVersion: 'bybit-readonly-uta-1.0',
                },
              }
            : { eligible: false, reason: 'PLAN_ENDED', snapshot: null, openIntent: null },
        ),
      ),
    )
    stubNextBaseline(() => {
      if (!started) return Promise.resolve(json({ ...normal, snapshot: null, job: null }))
      if (polls++ < 2)
        return Promise.resolve(
          json({
            ...normal,
            snapshot: null,
            reason: 'JOB_IN_PROGRESS',
            job: { status: polls === 1 ? 'QUEUED' : 'RUNNING' },
          }),
        )
      prepared = true
      return Promise.resolve(json(normal))
    })
    stubNextBaseline(() => {
      started = true
      return Promise.resolve(json({}, 202))
    }, 'POST')
    render(<PlanConditionScreen />)
    await tick()
    expect(screen.getAllByRole('status')).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await tick()
    fireEvent.click(screen.getByRole('button', { name: copy.prepare }))
    await tick()
    expect(screen.getByRole('button', { name: copy.prepare })).toBeDisabled()
    await advance(2000)
    await advance(2000)
    await tick()
    expect(screen.getByLabelText(planConditionContent.endAt)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: planConditionContent.heading })).toHaveFocus()
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })
  it('과거 목록은 서버의 실제 중도 종료 시각을 그대로 표시한다', async () => {
    const ended = '2026-10-04T00:05:00Z'
    stubPlanHistory(() =>
      Promise.resolve(
        json([
          { planId: 'old', status: 'ENDED_EARLY', startAt: '2026-10-01T00:00:00Z', endAt: ended },
        ]),
      ),
    )
    render(
      <PlanHistoryPanel
        selected={null}
        onSelect={() => {
          /* selection is not requested */
        }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: copy.historyOpen }))
    await screen.findByRole('button', { name: copy.select })
    expect(screen.getByText(/오전 9:05/)).toBeInTheDocument()
  })
  it.each([
    ['dashboard', SignedInScreen],
    ['onboarding', OnboardingScreen],
  ] as const)('%s 기록 선택은 이전 준비 live 안내를 지운다', async (destination, Screen) => {
    const history = endedDashboardFixture()
    const result = history.result as Record<string, unknown>
    const conditions = result.conditions as Record<string, unknown>
    stubSessionRequest(() =>
      Promise.resolve(json({ authenticated: true, destination, csrfToken: 'csrf' })),
    )
    stubDashboard(() => Promise.resolve(json(history)))
    stubNextBaseline(() => Promise.resolve(json(normal)))
    stubPlanHistory(() =>
      Promise.resolve(
        json([
          {
            planId: result.planId,
            status: 'ENDED_EARLY',
            startAt: conditions.startAt,
            endAt: conditions.endAt,
          },
        ]),
      ),
    )
    stubHistoricalDashboard(result.planId as string, () => Promise.resolve(json(history)))
    render(<Screen />)
    await screen.findByRole('button', { name: copy.inspect })
    fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
    await screen.findAllByText(copy.ready)
    expect(screen.getByRole('status')).toHaveTextContent(copy.ready)
    fireEvent.click(screen.getByRole('button', { name: copy.historyOpen }))
    fireEvent.click(await screen.findByRole('button', { name: copy.select }))
    await screen.findByRole('button', { name: copy.current })
    expect(screen.getByRole('status')).not.toHaveTextContent(copy.ready)
  })
  it.each([
    ['dashboard', SignedInScreen],
    ['onboarding', OnboardingScreen],
  ] as const)(
    '%s 과거 PENDING에는 동기화·종료·종료 후 현재가치가 없다',
    async (destination, Screen) => {
      const result = completedProgressResultFixture()
      const payload = {
        ...completedDashboardFixture(result, completionRecordFixture(result, { retryable: false })),
        sync: null,
      }
      const conditions = result.conditions as Record<string, unknown>
      stubSessionRequest(() =>
        Promise.resolve(json({ authenticated: true, destination, csrfToken: 'csrf' })),
      )
      stubDashboard(() => Promise.resolve(json(endedDashboardFixture())))
      stubPlanHistory(() =>
        Promise.resolve(
          json([
            {
              planId: result.planId,
              status: 'COMPLETED',
              startAt: conditions.startAt,
              endAt: conditions.endAt,
            },
          ]),
        ),
      )
      stubHistoricalDashboard(result.planId as string, () => Promise.resolve(json(payload)))
      render(<Screen />)
      await screen.findByRole('button', { name: copy.historyOpen })
      fireEvent.click(screen.getByRole('button', { name: copy.historyOpen }))
      fireEvent.click(await screen.findByRole('button', { name: copy.select }))
      await screen.findByRole('button', { name: copy.current })
      await waitFor(() => {
        expect(
          screen.getByRole('heading', { name: planCompletionContent.result.heading }),
        ).toBeInTheDocument()
      })
      expect(screen.queryByRole('button', { name: planSyncContent.action })).not.toBeInTheDocument()
      expect(
        screen.queryByRole('button', { name: planEndContent.area.action }),
      ).not.toBeInTheDocument()
      expect(
        screen.queryByRole('heading', { name: planCompletionContent.postEnd.heading }),
      ).not.toBeInTheDocument()
    },
  )
  it.each([
    ['dashboard', SignedInScreen],
    ['onboarding', OnboardingScreen],
  ] as const)(
    '%s 현재 계획 변경과 준비 패널 제거는 준비 안내를 정리한다',
    async (destination, Screen) => {
      let current = endedDashboardFixture()
      stubSessionRequest(() =>
        Promise.resolve(json({ authenticated: true, destination, csrfToken: 'csrf' })),
      )
      stubDashboard(() => Promise.resolve(json(current)))
      stubNextBaseline(() => Promise.resolve(json(normal)))
      render(<Screen />)
      await screen.findByRole('button', { name: copy.inspect })
      fireEvent.click(screen.getByRole('button', { name: copy.inspect }))
      await screen.findAllByText(copy.ready)
      const oldResult = current.result as Record<string, unknown>
      const conditions = oldResult.conditions as Record<string, unknown>
      current = endedDashboardFixture(
        endedResultFixture({
          planId: 'new-terminal',
          conditions: {
            ...conditions,
            startAt: new Date(Date.parse(conditions.startAt as string) + 1).toISOString(),
          },
        }),
      )
      fireEvent.click(screen.getByRole('button', { name: planResultContent.reload }))
      await waitFor(() => {
        expect(screen.getByRole('status')).not.toHaveTextContent(copy.ready)
      })
      expect(screen.queryByText(normal.snapshot.decimal)).not.toBeInTheDocument()
      stubDashboard(() => Promise.resolve(json({}, 403)))
      fireEvent.click(screen.getByRole('button', { name: planResultContent.reload }))
      await waitFor(() => {
        expect(screen.queryByRole('button', { name: copy.inspect })).not.toBeInTheDocument()
      })
      expect(screen.getByRole('status')).not.toHaveTextContent(copy.ready)
    },
  )
})
