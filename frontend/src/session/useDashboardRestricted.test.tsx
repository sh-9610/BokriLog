import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { PlanResultPanel } from '../components/PlanResultPanel'
import { planEndContent } from '../content/planEnd'
import { planResultContent } from '../content/planResult'
import { planSyncContent } from '../content/planSync'
import { dashboardFixture, planResultFixture } from '../test/planResultFixture'
import { getObservedStorageEntries, stubDashboard } from '../test/setup'
import {
  dashboardAnnouncement,
  parseDashboard,
  UNKNOWN_VERIFICATION,
  useDashboard,
  type Dashboard,
  type DashboardView,
} from './useDashboard'

/**
 * Story 3.2 AC-5: 계산 표시 제한. 서버는 상태·계획 정체성·다음 행동과 계산 값이 아닌 블록을 두고 결과를 빼며
 * `calculationDisplay: RESTRICTED`를 싣는다. 화면은 금융값을 남기지 않고, 제한을 글로 말하며, 재시도를 두지 않는다.
 */

const PLAN_ID = '0b3c1f6e-8a3d-4b2c-9f1e-2d4a6c8e0f12'

const AVAILABLE_SYNC = {
  availability: 'AVAILABLE',
  reason: null,
  retryAfterSeconds: null,
  latest: null,
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function restricted(
  state = 'READY',
  nextAction = 'NONE',
  sync: Record<string, unknown> = AVAILABLE_SYNC,
): Record<string, unknown> {
  return {
    state,
    nextAction,
    calculationDisplay: 'RESTRICTED',
    plan: { planId: PLAN_ID, planStatus: state === 'ENDED' ? 'ENDED_EARLY' : 'ACTIVE' },
    sync,
    planEnd: { availability: 'UNAVAILABLE', reason: 'RESTRICTED' },
  }
}

/** 응답 하나를 화면의 막힌 뷰로 옮긴다(`useDashboard`가 하는 것과 같다). */
function heldView(body: Record<string, unknown> = restricted()): DashboardView {
  const parsed = parseDashboard(body)
  if (parsed?.state !== 'DISPLAY_RESTRICTED') throw new Error('표시 제한 응답이 아니다')
  return {
    kind: 'displayRestricted',
    shownState: parsed.shownState,
    nextAction: parsed.nextAction,
    planId: parsed.planId,
    planStatus: parsed.planStatus,
    sync: parsed.sync,
    verification: parsed.verification,
    planEnd: parsed.planEnd,
  }
}

function panel(view: DashboardView): void {
  const dashboard: Dashboard = { view, loading: false, reload: vi.fn(), refresh: vi.fn() }
  const idle = { focus: null }
  render(
    <PlanResultPanel
      adjustments={{} as never}
      dashboard={dashboard}
      evidence={{} as never}
      planEnd={idle as never}
      reauthentication={{ pending: false } as never}
      sync={idle as never}
    />,
  )
}

describe('parseDashboard: 막힌 결과(Story 3.2)', () => {
  it('결과 없이 상태·계획 정체성·다음 행동과 동기화·계획 종료 블록을 읽는다', () => {
    expect(parseDashboard(restricted())).toMatchObject({
      state: 'DISPLAY_RESTRICTED',
      shownState: 'READY',
      nextAction: 'NONE',
      planId: PLAN_ID,
      planStatus: 'ACTIVE',
      sync: { availability: 'AVAILABLE' },
      planEnd: { availability: 'UNAVAILABLE', reason: 'RESTRICTED' },
    })
    const ended = parseDashboard(restricted('ENDED', 'PREPARE_NEXT_PLAN'))
    expect(ended).toMatchObject({ state: 'DISPLAY_RESTRICTED', shownState: 'ENDED' })
    // 끝난 계획의 응답에는 검증 블록의 뜻이 없다.
    expect(ended).toMatchObject({ verification: UNKNOWN_VERIFICATION })
  })

  it('결과를 실었거나, 모르는 표지이거나, 결과 없는 상태이거나, 그 상태의 행동이 아니면 거절한다', () => {
    expect(parseDashboard({ ...restricted(), result: planResultFixture() })).toBeNull()
    expect(parseDashboard({ ...restricted(), calculationDisplay: 'HIDDEN' })).toBeNull()
    expect(parseDashboard(restricted('NO_PLAN', 'CREATE_PLAN'))).toBeNull()
    expect(parseDashboard(restricted('READY', 'PREPARE_NEXT_PLAN'))).toBeNull()
    expect(parseDashboard({ ...restricted(), plan: { planId: PLAN_ID } })).toBeNull()
    // 과거 계획 조회는 끝난 계획의 답만 받는다.
    expect(parseDashboard(restricted(), true)).toBeNull()
    expect(parseDashboard(restricted('ENDED', 'PREPARE_NEXT_PLAN'), true)).not.toBeNull()
  })
})

describe('useDashboard: 막힌 결과(Story 3.2)', () => {
  it('보이던 묶음을 버리고 금융값을 남기지 않으며, 다음 읽기에서 결과가 다시 보일 수 있다', async () => {
    const queue = [
      () => Promise.resolve(json(dashboardFixture())),
      () => Promise.resolve(json(restricted())),
      () => Promise.resolve(json(dashboardFixture())),
    ]
    stubDashboard(() => {
      const next = queue.shift()
      if (next === undefined) throw new Error('예상하지 않은 대시보드 읽기')
      return next()
    })
    const { result } = renderHook(() => useDashboard(true))
    await waitFor(() => {
      expect(result.current.view.kind).toBe('READY')
    })
    act(() => {
      result.current.reload()
    })
    await waitFor(() => {
      expect(result.current.view.kind).toBe('displayRestricted')
    })
    expect(result.current.view).toMatchObject({
      kind: 'displayRestricted',
      shownState: 'READY',
      nextAction: 'NONE',
      planId: PLAN_ID,
      planStatus: 'ACTIVE',
    })
    expect(JSON.stringify(result.current.view)).not.toMatch(/calculationId|metrics|principal/)
    expect(dashboardAnnouncement(result.current)).toBe(planResultContent.status.displayRestricted)
    act(() => {
      result.current.reload()
    })
    await waitFor(() => {
      expect(result.current.view.kind).toBe('READY')
    })
    expect(getObservedStorageEntries()).toEqual([])
  })
})

describe('PlanResultPanel: 막힌 결과(Story 3.2)', () => {
  it('제한을 글로 말하고 동기화·계획 종료·다음 행동 블록을 그리되 값도 재시도 버튼도 없다', () => {
    panel(heldView())
    expect(screen.getByText(planResultContent.displayRestrictedLabel)).toBeInTheDocument()
    expect(screen.getByText(planResultContent.displayRestrictedHelp)).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 3, name: planSyncContent.action }),
    ).toBeInTheDocument()
    expect(screen.getByText(planResultContent.displayRestrictedSync)).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 3, name: planEndContent.area.heading }),
    ).toBeInTheDocument()
    expect(
      screen.getByText(planEndContent.area.reasons.RESTRICTED ?? '', { exact: false }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 3, name: planResultContent.nextActionHeading }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/USD/)
  })

  it('동기화를 받지 않는 사유는 그 사유의 문장으로 말한다', () => {
    panel(
      heldView(
        restricted('READY', 'NONE', {
          availability: 'UNAVAILABLE',
          reason: 'RESTRICTED',
          retryAfterSeconds: null,
          latest: null,
        }),
      ),
    )
    expect(
      screen.getByText(planSyncContent.reasons.RESTRICTED, { exact: false }),
    ).toBeInTheDocument()
  })
})
