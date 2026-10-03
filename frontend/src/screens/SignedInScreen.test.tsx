import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { adjustmentHistoryContent } from '../content/adjustmentHistory'
import {
  authContent,
  authorizePath,
  CSRF_HEADER,
  dashboardCopyStrings,
  reauthCopyStrings,
  reauthNoticePath,
  SETTINGS_PATH,
  SIGN_IN_PROVIDERS,
} from '../content/auth'
import { calculationEvidenceContent } from '../content/calculationEvidence'
import { firstSnapshotContent } from '../content/firstSnapshot'
import { planConditionContent } from '../content/planConditions'
import { PLAN_END_CANCEL_PATH, PLAN_END_CONFIRM_PATH, planEndContent } from '../content/planEnd'
import {
  DASHBOARD_PATH,
  METRIC_ORDER,
  planPublicationContent,
  planResultContent,
} from '../content/planResult'
import { DASHBOARD_SYNC_PATH, planSyncContent } from '../content/planSync'
import { DASHBOARD_VERIFICATION_PATH, planVerificationContent } from '../content/planVerification'
import { formatSeoulTime } from '../format/decimal'
import { navigateTo, replaceLocation } from '../session/navigation'
import {
  adjustmentPageFixture,
  adjustmentRowFixture,
  BASIS_AT,
  calculationEvidenceFixture,
  changeFixture,
  dashboardFixture,
  ENDED_AT,
  endedDashboardFixture,
  endedResultFixture,
  evidenceResultFixture,
  longGapEndRecordFixture,
  MEASURED_AT,
  NO_PLAN_FIXTURE,
  planResultFixture,
  PREPARING_FIXTURE,
  syncFixture,
  syncJobFixture,
  trustFixture,
  verificationFixture,
  verificationJobFixture,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedSessionRequests,
  getObservedStorageEntries,
  stubAdjustmentHistory,
  stubCalculationEvidence,
  stubDashboard,
  stubDashboardSync,
  stubDashboardVerification,
  stubFirstSnapshotStart,
  stubFirstSnapshotState,
  stubLogoutRequest,
  stubPlanEndCancel,
  stubPlanEndConfirm,
  stubPlanEndPrepare,
  stubReauthenticationRequest,
  stubSessionRequest,
} from '../test/setup'
import { SignedInScreen } from './SignedInScreen'

/*
 * 최상위 내비게이션을 가로챈다. jsdom은 `location.replace`·`location.assign`을 수행하지도,
 * 관측하게 하지도 않는다. 그 전환을 소유한 모듈 하나를 대신 세운다
 * (`OnboardingScreen.test.tsx`와 같은 관용).
 */
vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

/** 이 화면이 세션 확인에서 받는 토큰. 로그아웃 요청이 이 값을 헤더로 되돌려 보낸다. */
const CSRF_TOKEN = 'dashboard-csrf-token'

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** The admission's answer to an automatic trigger within 5 minutes of the last one (Story 2.11). */
const NOT_DUE = { outcome: 'NOT_DUE', job: null, retryAfterSeconds: 240 }

/** One late deposit or withdrawal, nothing else changed (Story 2.12 R8). */
const ONE_LATE = { lateAddedCount: 1, valueChangedCount: 0, removedCount: 0 }

/** 응답이 아직 오지 않은 상태. `checking` 분기를 관측하기 위한 의도된 무기한 대기다. */
function pendingResponse(): Promise<Response> {
  return new Promise<Response>(() => {
    // 아무것도 하지 않는다. resolve를 부르지 않는 것이 이 함수의 목적이다.
  })
}

function authenticated(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({ authenticated: true, destination: 'dashboard', csrfToken: CSRF_TOKEN }),
  )
}

/*
 * `restoreMocks`는 `vi.spyOn`이 만든 spy만 원래대로 돌린다. `vi.mock` 팩토리의 `vi.fn()`은 호출
 * 기록을 그대로 들고 다음 시험으로 넘어가므로 "부르지 않았다"를 단정할 수 없다. 매 시험 앞에서
 * 직접 비운다.
 */
beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  vi.mocked(navigateTo).mockClear()
  /*
   * Story 2.11: the first ready answer of a mount sends one entry sync. Unless a test opens another
   * answer, the server says it is not due yet — nothing is admitted and nothing on screen changes.
   */
  stubDashboardSync(() => Promise.resolve(jsonResponse(NOT_DUE)))
})

/**
 * 이 화면의 경로를 그 쿼리와 함께 연다.
 *
 * `App.test.tsx`의 관용이다. jsdom의 기본 `search`는 빈 문자열이라 주소를 옮기지 않으면 화면이
 * 패널에 넘기는 표지가 언제나 `null`이고, 그 배선은 어느 단정도 지나지 않는다.
 */
function navigateToSignedInScreen(search: string): void {
  window.history.pushState({}, '', `/dashboard${search}`)
}

/* 주소를 옮긴 시험이 다음 시험에 그 쿼리를 물려주지 않게 한다. */
afterEach(() => {
  window.history.pushState({}, '', '/dashboard')
})

describe('SignedInScreen', () => {
  it('확인 중에는 로그인 상태를 주장하지 않고 사용자 데이터를 표시하지 않는다', () => {
    stubSessionRequest(pendingResponse)

    render(<SignedInScreen />)

    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-busy', 'true')
    expect(status.textContent).toBe(authContent.session.checking)
    expect(screen.queryByText(authContent.dashboard.heading)).not.toBeInTheDocument()
  })

  it('세션이 확인되면 로그인 상태 카피를 렌더한다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)

    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading }),
    ).toBeInTheDocument()
    const rendered = document.body.textContent
    for (const copy of dashboardCopyStrings) {
      expect(rendered).toContain(copy)
    }
  })

  it('첫 공식값과 제공자 측정 시각, 세 신뢰 축을 표시한다', async () => {
    stubSessionRequest(authenticated)
    // 활성 계획이 없다고 서버가 답한 경로다. 그때만 첫 값의 임시 세 축이 보인다.
    stubDashboard(() => Promise.resolve(jsonResponse(NO_PLAN_FIXTURE)))
    stubFirstSnapshotState(() =>
      Promise.resolve(
        jsonResponse({
          connected: true,
          restricted: false,
          job: { jobId: 'job-1', status: 'SUCCEEDED' },
          decimal: '-12.345',
          currency: 'USD',
          measuredAt: '2026-09-25T00:00:00Z',
        }),
      ),
    )
    render(<SignedInScreen />)
    expect(await screen.findByText(/-12\.35 USD/)).toBeInTheDocument()
    expect(screen.getByText(/Bybit 측정 시각/)).toBeInTheDocument()
    for (const axis of ['신선도', '입력 신뢰도', '계산 성격']) {
      expect(screen.getByText(new RegExp(axis))).toBeInTheDocument()
    }
    expect(screen.getByText(/현재가치가 0 이하/)).toBeInTheDocument()
  })

  it('표시 반올림 뒤 음수 영은 0.00으로 표시한다', async () => {
    stubSessionRequest(authenticated)
    // The first-snapshot panel leads only a dashboard that answered without a bundle.
    stubDashboard(() => Promise.resolve(jsonResponse(NO_PLAN_FIXTURE)))
    stubFirstSnapshotState(() =>
      Promise.resolve(
        jsonResponse({
          connected: true,
          restricted: false,
          decimal: '-0.001',
          currency: 'USD',
          measuredAt: '2026-09-25T00:00:00Z',
        }),
      ),
    )
    render(<SignedInScreen />)
    expect(await screen.findByText('0.00 USD')).toBeInTheDocument()
  })

  it('운영 제한 상태를 연결 해제와 구분하고 금융값을 숨긴다', async () => {
    stubSessionRequest(authenticated)
    // The first-snapshot panel leads only a dashboard that answered without a bundle.
    stubDashboard(() => Promise.resolve(jsonResponse(NO_PLAN_FIXTURE)))
    stubFirstSnapshotState(() =>
      Promise.resolve(jsonResponse({ connected: true, restricted: true })),
    )
    render(<SignedInScreen />)
    expect(await screen.findByText(/계정 또는 연결 상태가 바뀌어 확인을 중단/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '첫 자산 확인' })).not.toBeInTheDocument()
    expect(screen.queryByText(/공식 현재가치:/)).not.toBeInTheDocument()
  })

  it('첫 확인을 202로 접수하고 진행 중 중복 조작을 막는다', async () => {
    stubSessionRequest(authenticated)
    // The first-snapshot panel leads only a dashboard that answered without a bundle.
    stubDashboard(() => Promise.resolve(jsonResponse(NO_PLAN_FIXTURE)))
    stubFirstSnapshotState(() =>
      Promise.resolve(jsonResponse({ connected: true, restricted: false })),
    )
    stubFirstSnapshotStart(() =>
      Promise.resolve(
        jsonResponse(
          {
            jobId: 'job-2',
            status: 'QUEUED',
          },
          202,
        ),
      ),
    )
    render(<SignedInScreen />)
    const button = await screen.findByRole('button', { name: '첫 자산 확인' })
    fireEvent.click(button)
    expect(await screen.findByText('첫 자산 확인을 기다리고 있습니다.')).toBeInTheDocument()
    expect(button).toBeDisabled()
  })

  it('UX-DR23: `복리 계획 비교` 제목과 비추천 고지를 인접 배치한다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    const title = screen.getByRole('heading', { level: 1 })

    expect(title.textContent).toBe('복리 계획 비교')
    expect(title.nextElementSibling?.textContent).toBe('투자성과·계좌 수익률·투자 추천이 아닙니다.')
  })

  it('서버가 설정 화면을 골라도 대시보드는 밀려나지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: true, destination: 'settings', csrfToken: CSRF_TOKEN }),
      ),
    )

    render(<SignedInScreen />)

    /*
     * 미인증으로 `/settings`를 열어 로그인한 사용자는 확정 목적지가 `settings`로 굳는다. 그
     * 방향을 열지 않으면 그 세션은 내내 대시보드에 닿지 못하고 `/settings`로 튕긴다 — 예외가
     * 한 방향뿐일 때 생기는 고착이다.
     */
    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading }),
    ).toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('온보딩을 지나지 않은 계정은 온보딩으로 밀려난다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<SignedInScreen />)

    // 온보딩만이 강제 목적지다. 설정·대시보드는 서로를 밀어내지 않는다.
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith('/onboarding')
    })
  })

  it('설정 화면으로 가는 길을 준다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    /*
     * 이 링크가 없으면 설정 화면은 주소를 직접 쳐야만 닿고, 사용자가 자기 로그인 수단을 볼
     * 자리가 사실상 없다. 전역 내비게이션은 아직 없으므로 머리말의 링크 하나가 그 자리다.
     */
    const link = screen.getByRole('link', { name: authContent.settings.link })

    expect(link).toHaveAttribute('href', SETTINGS_PATH)
    // 경로 리터럴을 한 번 고정한다. Nginx 허용 목록·서버 목적지 집합과 같은 값이어야 한다.
    expect(SETTINGS_PATH).toBe('/settings')
  })

  it('세션이 확인되기 전에는 설정 링크도 없다', () => {
    stubSessionRequest(pendingResponse)

    render(<SignedInScreen />)

    // 확인 전에는 이 화면이 로그인 상태를 주장하지 않는다. 갈 곳도 아직 없다.
    expect(screen.queryByRole('link', { name: authContent.settings.link })).not.toBeInTheDocument()
  })

  it('공식 결과를 읽지 못하면 금융값을 담지 않는다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    // 대시보드 조회가 열리지 않은 경로다: 이전 결과가 없는 읽기 실패이므로 `재시도 가능`이다.
    expect(await screen.findByText(planResultContent.retryableLabel)).toBeInTheDocument()
    const rendered = document.body.textContent
    // 지표·비율·통화 표기가 없고 빈 카드로 위장하지도 않는다.
    expect(rendered).not.toMatch(/\d+(\.\d+)?\s*%/)
    expect(rendered).not.toContain('USDT')
    expect(rendered).not.toContain('$')
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })

  /*
   * Story 2.8: 공식 대시보드. 조회 모델 응답 하나에서 화면 전체를 그린다.
   */

  function firstSnapshotLoaded(decimal = '1250', measuredAt = '2026-09-27T00:59:59Z'): void {
    stubFirstSnapshotState(() =>
      Promise.resolve(
        jsonResponse({
          connected: true,
          restricted: false,
          job: { jobId: 'job-1', status: 'SUCCEEDED' },
          decimal,
          currency: 'USD',
          measuredAt,
        }),
      ),
    )
  }

  /** Every `role=status` and `aria-live` element of the screen. */
  function liveRegions(): Element[] {
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 aria-live 영역은 역할 조회로 보이지 않는다. 화면의 live region 수는 속성으로 세야 한다.
    return [...document.querySelectorAll('[role="status"], [aria-live]')]
  }

  it('대시보드: 응답 하나에서 현재가치 → 세 축 → 지표 → 다음 행동 순서로 그리고 단일 상태 리전이 알린다', async () => {
    stubSessionRequest(authenticated)
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture())))
    firstSnapshotLoaded()

    render(<SignedInScreen />)

    expect(
      await screen.findByRole('heading', { level: 3, name: planResultContent.currentHeading }),
    ).toBeInTheDocument()
    for (const metric of METRIC_ORDER) {
      expect(
        screen.getByRole('heading', { level: 4, name: planResultContent.metrics[metric].name }),
      ).toBeInTheDocument()
    }
    const order = screen.getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)
    expect(order.slice(0, 4)).toEqual([
      planResultContent.currentHeading,
      planResultContent.trustHeading,
      planResultContent.metricsHeading,
      planResultContent.nextActionHeading,
    ])
    // One status region, and no other live region: the first-snapshot panel steps aside.
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(liveRegions()).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent(
      planResultContent.status.ready(formatSeoulTime(BASIS_AT, planResultContent.timeUnavailable)),
    )
    expect(getObservedAllowedRequests()).toContain(`GET ${DASHBOARD_PATH}`)
    // The current value appears once, from the dashboard response.
    expect(screen.getAllByText(/1,250\.00 USD/)).toHaveLength(1)
    expect(
      screen.queryByRole('heading', { name: firstSnapshotContent.heading }),
    ).not.toBeInTheDocument()
    expect(getObservedAllowedRequests()).not.toContain(`GET /api/snapshots/first`)
    expect(screen.queryByText(/계산 성격 · 계산 불가/)).not.toBeInTheDocument()
    // The title and its disclaimer stay adjacent above the result.
    const title = screen.getByRole('heading', { level: 1 })
    expect(title.nextElementSibling?.textContent).toBe(authContent.disclaimer)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('대시보드: 계획 없음은 문장과 계획 조건 링크를 화면에 한 번만 보인다', async () => {
    stubSessionRequest(authenticated)
    stubDashboard(() => Promise.resolve(jsonResponse(NO_PLAN_FIXTURE)))
    firstSnapshotLoaded('1234.56', '2026-09-25T00:00:00Z')
    render(<SignedInScreen />)

    expect(await screen.findByText(firstSnapshotContent.succeeded)).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(planResultContent.status.noPlan)
    })
    expect(screen.getAllByText(new RegExp(planResultContent.noPlanLabel))).toHaveLength(1)
    const links = screen.getAllByRole('link', { name: planConditionContent.openConditions })
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveAttribute('href', planConditionContent.path)
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    // The first value leads, then the plan's next action.
    const first = screen.getByRole('heading', { level: 2, name: firstSnapshotContent.heading })
    const dashboard = screen.getByRole('heading', { level: 2, name: planResultContent.heading })
    expect(first.compareDocumentPosition(dashboard) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('대시보드: 결과 준비 중은 계획 없음과 구분되고 계획 조건 링크를 보이지 않는다', async () => {
    stubSessionRequest(authenticated)
    stubDashboard(() => Promise.resolve(jsonResponse(PREPARING_FIXTURE)))
    firstSnapshotLoaded('1234.56', '2026-09-25T00:00:00Z')
    render(<SignedInScreen />)

    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(planResultContent.status.preparing)
    })
    // The first value leads while the plan is prepared.
    expect(
      await screen.findByRole('heading', { level: 2, name: firstSnapshotContent.heading }),
    ).toBeInTheDocument()
    expect(await screen.findByText(/1,234\.56 USD/)).toBeInTheDocument()
    expect(screen.queryByText(new RegExp(planResultContent.noPlanLabel))).not.toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: planConditionContent.openConditions }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: planResultContent.reload })).toBeInTheDocument()
  })

  it.each([
    ['a failed read', () => Promise.reject(new Error('network'))],
    [
      'an inconsistent bundle (503)',
      () =>
        Promise.resolve(
          jsonResponse({ code: 'DASHBOARD_UNAVAILABLE', message: 'x', correlationId: 'c' }, 503),
        ),
    ],
    ['a malformed body', () => Promise.resolve(jsonResponse({ planId: 'partial' }))],
  ])('대시보드: %s는 계획 없음으로 말하지 않고 재시도 가능으로 둔다', async (_name, answer) => {
    stubSessionRequest(authenticated)
    stubDashboard(answer)
    firstSnapshotLoaded('1234.56', '2026-09-25T00:00:00Z')
    render(<SignedInScreen />)

    expect(await screen.findByText(planResultContent.retryableLabel)).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(planResultContent.status.retryable)
    expect(screen.queryByText(new RegExp(planResultContent.noPlanLabel))).not.toBeInTheDocument()
    // Nothing financial: the first-snapshot panel is not mounted, so its amount never appears.
    await new Promise((settle) => setTimeout(settle, 20))
    expect(document.body.textContent).not.toContain('1,234.56')
    expect(document.body.textContent).not.toMatch(/\d\s*USD/)
    expect(
      screen.queryByRole('heading', { name: firstSnapshotContent.heading }),
    ).not.toBeInTheDocument()
    expect(getObservedAllowedRequests()).not.toContain('GET /api/snapshots/first')
    expect(
      screen.queryByRole('link', { name: planConditionContent.openConditions }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    expect(screen.queryByText(/계산 성격 · 계산 불가/)).not.toBeInTheDocument()
  })

  it('AC-8: 다시 불러오기가 실패하면 같은 값이 마지막 정상 결과로 남고 0이나 부분 값이 나오지 않는다', async () => {
    stubSessionRequest(authenticated)
    const queue: (() => Promise<Response>)[] = [
      () => Promise.resolve(jsonResponse(dashboardFixture())),
      () => Promise.reject(new Error('network')),
    ]
    stubDashboard(() => (queue.shift() ?? (() => Promise.reject(new Error('extra'))))())
    render(<SignedInScreen />)

    const before = await screen.findAllByRole('article')
    const values = before.map((card) => card.textContent)
    const reload = screen.getByRole('button', { name: planResultContent.reload })
    reload.focus()
    fireEvent.click(reload)

    expect(await screen.findByText(planResultContent.lastGoodLabel)).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(planResultContent.status.lastGood)
    const after = screen.getAllByRole('article')
    expect(after.map((card) => card.textContent)).toEqual(values)
    expect(document.body.textContent).not.toMatch(/(^|[^\d,.])0\.00 USD/)
    // The reload button is the same element and keeps the keyboard focus.
    expect(screen.getByRole('button', { name: planResultContent.reload })).toBe(reload)
    expect(reload).toHaveFocus()
  })

  it('AC-13: 403이면 보이던 금융값이 화면과 메모리에서 사라진다', async () => {
    stubSessionRequest(authenticated)
    const queue: (() => Promise<Response>)[] = [
      () => Promise.resolve(jsonResponse(dashboardFixture())),
      () =>
        Promise.resolve(jsonResponse({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403)),
    ]
    stubDashboard(() => (queue.shift() ?? (() => Promise.reject(new Error('extra'))))())
    render(<SignedInScreen />)

    expect(await screen.findAllByRole('article')).toHaveLength(6)
    fireEvent.click(screen.getByRole('button', { name: planResultContent.reload }))

    expect(await screen.findByText(planResultContent.deniedHelp)).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(planResultContent.status.denied)
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/USD|%/)
    // The first-snapshot panel does not bring a value back either.
    expect(
      screen.queryByRole('heading', { name: firstSnapshotContent.heading }),
    ).not.toBeInTheDocument()
  })

  it('AC-9: 버전이 뒤로 간 응답은 화면을 바꾸지 않고 재시도 가능으로 둔다', async () => {
    stubSessionRequest(authenticated)
    const queue: (() => Promise<Response>)[] = [
      () =>
        Promise.resolve(
          jsonResponse(dashboardFixture(planResultFixture({ pointerVersion: 2 }), 'WAIT_FOR_SYNC')),
        ),
      () =>
        Promise.resolve(
          jsonResponse(
            dashboardFixture(
              planResultFixture({
                pointerVersion: 1,
                calculationId: 'calculation-0',
                metrics: [
                  { metric: 'CURRENT_VALUE', value: '999', nature: 'OFFICIAL' },
                  { metric: 'PLAN_AMOUNT', value: '1', nature: 'OFFICIAL' },
                  { metric: 'PLAN_DIFFERENCE', value: '1', nature: 'OFFICIAL' },
                  { metric: 'ADJUSTED_TARGET', value: '1', nature: 'OFFICIAL' },
                  { metric: 'REQUIRED_RATE', value: '1', nature: 'OFFICIAL' },
                  { metric: 'GOAL_PROGRESS', value: '1', nature: 'OFFICIAL' },
                ],
              }),
            ),
          ),
        ),
    ]
    stubDashboard(() => (queue.shift() ?? (() => Promise.reject(new Error('extra'))))())
    render(<SignedInScreen />)

    expect(await screen.findByText('1,250.00 USD')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: planResultContent.reload }))

    expect(await screen.findByText(planResultContent.retryableLabel)).toBeInTheDocument()
    expect(screen.queryByText(planResultContent.lastGoodLabel)).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(planResultContent.status.versionConflict)
    expect(
      screen.getByText(
        planResultContent.versionConflictHelp(
          formatSeoulTime(MEASURED_AT, planResultContent.timeUnavailable),
        ),
      ),
    ).toBeInTheDocument()
    // The shown bundle is kept, not swapped: the old values stay, the conflicting ones never appear.
    expect(screen.getByText('1,250.00 USD')).toBeInTheDocument()
    expect(screen.queryByText('999.00 USD')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: planResultContent.reload })).toBeInTheDocument()
  })

  it('AC-13: 401이면 금융값을 지우고 세션 만료 문장과 진입 화면으로 가는 문서 링크를 준다', async () => {
    stubSessionRequest(authenticated)
    const queue: (() => Promise<Response>)[] = [
      () => Promise.resolve(jsonResponse(dashboardFixture())),
      () =>
        Promise.resolve(
          jsonResponse({ code: 'UNAUTHENTICATED', message: 'x', correlationId: 'c' }, 401),
        ),
    ]
    stubDashboard(() => (queue.shift() ?? (() => Promise.reject(new Error('extra'))))())
    render(<SignedInScreen />)

    expect(await screen.findAllByRole('article')).toHaveLength(6)
    // A keyboard user on the reload button: focus it, then activate it.
    const reload = screen.getByRole('button', { name: planResultContent.reload })
    reload.focus()
    fireEvent.click(reload)

    const link = await screen.findByRole('link', { name: planResultContent.signInLink })
    expect(link).toHaveAttribute('href', '/')
    expect(screen.getByRole('status')).toHaveTextContent(planResultContent.status.signedOut)
    expect(planResultContent.status.signedOut).toBe(planPublicationContent.sessionError)
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/USD|%/)
    // Focus that was on the vanished reload button moves to the dashboard heading.
    expect(screen.getByRole('heading', { level: 2, name: planResultContent.heading })).toHaveFocus()
  })

  it('입력 신뢰도가 검토 필요인 결과는 상태 리전이 그 사실을 같은 문장에서 먼저 알린다', async () => {
    stubSessionRequest(authenticated)
    const review = planResultFixture({
      trust: trustFixture({
        inputReliability: {
          state: 'REVIEW_REQUIRED',
          cause: 'TARGET_NOT_POSITIVE',
          affectedMetrics: ['ADJUSTED_TARGET'],
        },
      }),
    })
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture(review))))
    render(<SignedInScreen />)

    await screen.findAllByRole('article')
    expect(screen.getByRole('status')).toHaveTextContent(
      planResultContent.status.readyWithReliability(
        planResultContent.axes.reliability.states.REVIEW_REQUIRED ?? 'missing copy',
        formatSeoulTime(BASIS_AT, planResultContent.timeUnavailable),
      ),
    )
  })

  it('계산 근거: 같은 화면에서 펼치고, 단일 상태 리전이 대시보드 문장 뒤에 근거 읽기 상태를 잇는다', async () => {
    stubSessionRequest(authenticated)
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture(evidenceResultFixture()))))
    let release: (response: Response) => void = () => undefined
    stubCalculationEvidence(
      () =>
        new Promise<Response>((resolve) => {
          release = resolve
        }),
    )
    render(<SignedInScreen />)
    await screen.findAllByRole('article')
    const ready = planResultContent.status.ready(
      formatSeoulTime(BASIS_AT, planResultContent.timeUnavailable),
    )
    expect(screen.getByRole('status').textContent).toBe(ready)
    // A role=status region is atomic by default: this one is not, so the evidence part is read alone.
    expect(screen.getByRole('status')).toHaveAttribute('aria-atomic', 'false')

    fireEvent.click(screen.getByRole('button', { name: calculationEvidenceContent.open }))
    expect(screen.getByRole('status').textContent).toBe(
      `${ready} ${calculationEvidenceContent.status.loading}`,
    )
    await act(async () => {
      release(jsonResponse(calculationEvidenceFixture(evidenceResultFixture())))
      await Promise.resolve()
    })
    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(
        `${ready} ${calculationEvidenceContent.status.ready}`,
      )
    })
    // Still one live region on the screen: the evidence region has none of its own.
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(liveRegions()).toHaveLength(1)
    expect(
      screen.getByRole('group', { name: calculationEvidenceContent.sections.versions }),
    ).toBeInTheDocument()
    expect(window.location.pathname).toBe('/dashboard')
    expect(getObservedStorageEntries()).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: calculationEvidenceContent.close }))
    expect(screen.getByRole('status').textContent).toBe(ready)
  })

  it('조정 이력: 같은 화면에서 펼치고, 단일 상태 리전이 대시보드·근거 문장과 따로 이력 읽기 상태를 잇는다', async () => {
    stubSessionRequest(authenticated)
    const result = evidenceResultFixture({ flowCount: 1 })
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture(result))))
    let release: (response: Response) => void = () => undefined
    stubAdjustmentHistory(
      () =>
        new Promise<Response>((resolve) => {
          release = resolve
        }),
    )
    render(<SignedInScreen />)
    await screen.findAllByRole('article')
    const ready = planResultContent.status.ready(
      formatSeoulTime(BASIS_AT, planResultContent.timeUnavailable),
    )
    expect(screen.getByRole('status').textContent).toBe(ready)

    fireEvent.click(screen.getByRole('button', { name: adjustmentHistoryContent.open }))
    expect(screen.getByRole('status').textContent).toBe(
      `${ready} ${adjustmentHistoryContent.status.loading}`,
    )
    await act(async () => {
      release(
        jsonResponse(
          adjustmentPageFixture(result, {
            rows: [adjustmentRowFixture(1, result.calculationId as string)],
            summary: { count: 1, netUsdValue: '10.25', excludedCount: 0 },
          }),
        ),
      )
      await Promise.resolve()
    })
    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(
        `${ready} ${adjustmentHistoryContent.status.ready(1, 1)}`,
      )
    })
    // The parts are separate elements of one status region, and there is still only one live region.
    // eslint-disable-next-line testing-library/no-node-access -- 상태 리전 안의 문장 요소를 센다.
    expect(screen.getByRole('status').querySelectorAll('span')).toHaveLength(2)
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(liveRegions()).toHaveLength(1)
    expect(screen.getByRole('list', { name: adjustmentHistoryContent.list })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/dashboard')
    expect(window.location.search).toBe('')
    expect(getObservedStorageEntries()).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: adjustmentHistoryContent.close }))
    expect(screen.getByRole('status').textContent).toBe(ready)
  })

  it('계산 근거: 근거 읽기가 403이면 대시보드가 스스로 다시 읽어 금융값을 지우고, 단순 실패는 대시보드를 건드리지 않는다', async () => {
    stubSessionRequest(authenticated)
    const dashboards: (() => Promise<Response>)[] = [
      () => Promise.resolve(jsonResponse(dashboardFixture(evidenceResultFixture()))),
      () =>
        Promise.resolve(jsonResponse({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403)),
    ]
    stubDashboard(() => (dashboards.shift() ?? (() => Promise.reject(new Error('extra'))))())
    const evidences: (() => Promise<Response>)[] = [
      () => Promise.reject(new Error('network')),
      () =>
        Promise.resolve(jsonResponse({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403)),
    ]
    stubCalculationEvidence(() =>
      (evidences.shift() ?? (() => Promise.reject(new Error('extra'))))(),
    )
    render(<SignedInScreen />)
    await screen.findAllByRole('article')
    const dashboardReads = (): number =>
      getObservedAllowedRequests().filter((entry) => entry === `GET ${DASHBOARD_PATH}`).length

    // A plain failure: the dashboard is not asked, and its values stay.
    fireEvent.click(screen.getByRole('button', { name: calculationEvidenceContent.open }))
    await screen.findByText(calculationEvidenceContent.failure.retryable)
    expect(dashboardReads()).toBe(1)
    expect(screen.getAllByRole('article')).toHaveLength(6)

    // A refusal: the dashboard reads again and its own 403 clears the values.
    fireEvent.click(screen.getByRole('button', { name: calculationEvidenceContent.retry }))
    await waitFor(() => {
      expect(dashboardReads()).toBe(2)
    })
    await waitFor(() => {
      expect(screen.queryByRole('article')).not.toBeInTheDocument()
    })
    expect(screen.queryByText(/1,250\.00 USD/)).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(planResultContent.status.denied)
  })

  it('다시 불러오는 동안 상태 리전이 그 사실을 알리고 busy로 표시된다', async () => {
    stubSessionRequest(authenticated)
    let release: (response: Response) => void = () => undefined
    const queue: (() => Promise<Response>)[] = [
      () => Promise.resolve(jsonResponse(dashboardFixture())),
      () =>
        new Promise<Response>((resolve) => {
          release = resolve
        }),
    ]
    stubDashboard(() => (queue.shift() ?? (() => Promise.reject(new Error('extra'))))())
    render(<SignedInScreen />)

    await screen.findAllByRole('article')
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'false')
    fireEvent.click(screen.getByRole('button', { name: planResultContent.reload }))

    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(planResultContent.status.reloading)
    })
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('region', { name: planResultContent.heading })).toHaveAttribute(
      'aria-busy',
      'true',
    )
    // The values stay while the read is in flight.
    expect(screen.getAllByRole('article')).toHaveLength(6)

    await act(async () => {
      release(jsonResponse(dashboardFixture()))
      await Promise.resolve()
    })
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'false')
    })
    expect(screen.getByRole('status')).toHaveTextContent(
      planResultContent.status.ready(formatSeoulTime(BASIS_AT, planResultContent.timeUnavailable)),
    )
  })

  /*
   * Story 2.11: 사용자 시점 증분 동기화. 진입·탭 복귀·버튼 세 트리거와 그 공지·초점.
   */

  function syncPosts(): string[] {
    return getObservedAllowedRequests().filter((entry) => entry === `POST ${DASHBOARD_SYNC_PATH}`)
  }

  /** Every text the status region showed after the observer started, in order, without repeats. */
  function recordStatus(): { texts: string[]; busy: string[]; stop: () => void } {
    const region = screen.getByRole('status')
    const texts: string[] = []
    const busy: string[] = []
    const observer = new MutationObserver(() => {
      const text = region.textContent
      if (texts.at(-1) !== text) texts.push(text)
      busy.push(region.getAttribute('aria-busy') ?? '')
    })
    observer.observe(region, {
      attributes: true,
      characterData: true,
      childList: true,
      subtree: true,
    })
    return {
      texts,
      busy,
      stop: () => {
        observer.disconnect()
      },
    }
  }

  it('동기화: 첫 READY에서 진입 동기화를 한 번 보내고, 아직 때가 아니면 화면이 그대로다', async () => {
    stubSessionRequest(authenticated)
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture())))
    let observed: RequestInit | undefined
    stubDashboardSync((init) => {
      observed = init
      return Promise.resolve(jsonResponse(NOT_DUE))
    })
    render(<SignedInScreen />)
    await screen.findAllByRole('article')
    await waitFor(() => {
      expect(syncPosts()).toHaveLength(1)
    })
    expect(JSON.parse(observed?.body as string)).toEqual({ trigger: 'ENTRY' })
    expect((observed?.headers as Record<string, string>)[CSRF_HEADER]).toBe(CSRF_TOKEN)
    // The control sits right after the freshness axis, and nothing about the sync is said yet.
    const control = screen.getByRole('button', { name: planSyncContent.action })
    expect(control).toHaveAttribute('aria-disabled', 'false')
    expect(screen.getByRole('status').textContent).toBe(
      planResultContent.status.ready(formatSeoulTime(BASIS_AT, planResultContent.timeUnavailable)),
    )
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(liveRegions()).toHaveLength(1)
    // A reload is not a new mount: no second entry sync.
    fireEvent.click(screen.getByRole('button', { name: planResultContent.reload }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'false')
    })
    expect(syncPosts()).toHaveLength(1)
  })

  it('동기화: 누르면 곧바로 동기화 중이 보이고, 조용히 따라가다 새 묶음으로 한 번에 바뀌며 초점은 제목으로 간다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    try {
      stubSessionRequest(authenticated)
      const job = syncJobFixture()
      const v2 = planResultFixture({
        pointerVersion: 2,
        calculationId: 'calculation-2',
        basisAt: '2026-09-27T01:20:00Z',
        measuredAt: '2026-09-27T01:19:59Z',
        cashflowVerifiedThrough: '2026-09-27T01:20:00Z',
        publishedAt: '2026-09-27T01:20:01Z',
        flowCount: 2,
        trust: trustFixture({
          freshness: {
            lastNormalAt: '2026-09-27T01:19:59Z',
            measuredAt: '2026-09-27T01:19:59Z',
            cashflowVerifiedThrough: '2026-09-27T01:20:00Z',
          },
          inputReliability: { lastNormalAt: '2026-09-27T01:20:00Z' },
          calculationNature: { lastNormalAt: '2026-09-27T01:20:00Z' },
        }),
        metrics: [
          { metric: 'CURRENT_VALUE', value: '1290', nature: 'OFFICIAL' },
          { metric: 'PLAN_AMOUNT', value: '1230', nature: 'OFFICIAL' },
          { metric: 'PLAN_DIFFERENCE', value: '60', nature: 'OFFICIAL' },
          { metric: 'ADJUSTED_TARGET', value: '1351', nature: 'OFFICIAL' },
          { metric: 'REQUIRED_RATE', value: '0.0648', nature: 'OFFICIAL' },
          { metric: 'GOAL_PROGRESS', value: '0.5', nature: 'OFFICIAL' },
        ],
      })
      const queue: (() => Promise<Response>)[] = [
        () => Promise.resolve(jsonResponse(dashboardFixture())),
        () =>
          Promise.resolve(
            jsonResponse(
              dashboardFixture(
                planResultFixture({
                  trust: trustFixture({
                    freshness: {
                      state: 'SYNCING',
                      cause: 'JOB_IN_PROGRESS',
                      nextAction: 'WAIT_FOR_SYNC',
                    },
                  }),
                }),
                'WAIT_FOR_SYNC',
                syncFixture({
                  availability: 'IN_PROGRESS',
                  latest: { ...job, status: 'RUNNING', stage: 'COLLECTION' },
                }),
              ),
            ),
          ),
        () =>
          Promise.resolve(
            jsonResponse(
              dashboardFixture(
                v2,
                'NONE',
                syncFixture({
                  availability: 'COOLDOWN',
                  retryAfterSeconds: 25,
                  latest: {
                    ...job,
                    status: 'SUCCEEDED',
                    stage: 'PUBLICATION',
                    finishedAt: '2026-09-27T01:20:01Z',
                  },
                }),
              ),
            ),
          ),
      ]
      stubDashboard(() => (queue.shift() ?? (() => Promise.reject(new Error('extra'))))())
      const triggers: string[] = []
      const answers: (() => Promise<Response>)[] = [
        () => Promise.resolve(jsonResponse(NOT_DUE)),
        () =>
          Promise.resolve(jsonResponse({ outcome: 'ACCEPTED', job, retryAfterSeconds: null }, 202)),
      ]
      stubDashboardSync((init) => {
        triggers.push((JSON.parse(init?.body as string) as { trigger: string }).trigger)
        return (answers.shift() ?? (() => Promise.reject(new Error('extra'))))()
      })
      render(<SignedInScreen />)
      expect(await screen.findByText('1,250.00 USD')).toBeInTheDocument()
      await waitFor(() => {
        expect(triggers).toEqual(['ENTRY'])
      })
      const before = screen.getAllByRole('article').map((card) => card.textContent)
      const recorder = recordStatus()

      const control = screen.getByRole('button', { name: planSyncContent.action })
      control.focus()
      fireEvent.click(control)
      fireEvent.click(control)
      // At once, before any answer: 동기화 중, the button blocked and still focused, the values kept.
      expect(screen.getByText(planSyncContent.running, { exact: true })).toBeVisible()
      expect(control).toHaveAttribute('aria-disabled', 'true')
      expect(control).toHaveFocus()
      expect(screen.getByRole('status').textContent).toBe(planSyncContent.status.syncing)
      expect(screen.getAllByRole('article').map((card) => card.textContent)).toEqual(before)
      await waitFor(() => {
        expect(triggers).toEqual(['ENTRY', 'MANUAL'])
      })

      // A poll in progress: the old bundle stays, freshness says 동기화 중, and the status is unchanged.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000)
      })
      await waitFor(() => {
        expect(
          screen.getByRole('heading', {
            level: 4,
            name: `${planResultContent.axes.freshness.name}: ${planResultContent.axes.freshness.states.SYNCING ?? ''}`,
          }),
        ).toBeInTheDocument()
      })
      expect(screen.getByText('1,250.00 USD')).toBeInTheDocument()
      expect(screen.getByRole('status').textContent).toBe(planSyncContent.status.syncing)

      // The poll that shows the job ended: the new bundle in one piece, and focus to the heading.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000)
      })
      expect(await screen.findByText('1,290.00 USD')).toBeInTheDocument()
      expect(screen.queryByText('1,250.00 USD')).not.toBeInTheDocument()
      const basis = formatSeoulTime('2026-09-27T01:20:00Z', planResultContent.timeUnavailable)
      expect(screen.getByRole('status').textContent).toBe(planSyncContent.status.synced(basis))
      expect(
        screen.getByRole('heading', { level: 2, name: planResultContent.heading }),
      ).toHaveFocus()
      expect(screen.getByText(planSyncContent.cooldown(25))).toBeInTheDocument()
      recorder.stop()
      // One sentence when it started, one when it ended — never "reading again", never busy.
      expect(recorder.texts).toEqual([
        planSyncContent.status.syncing,
        planSyncContent.status.synced(basis),
      ])
      expect(recorder.busy.every((value) => value === 'false')).toBe(true)
      expect(screen.getAllByRole('status')).toHaveLength(1)
      expect(liveRegions()).toHaveLength(1)
      expect(triggers).toEqual(['ENTRY', 'MANUAL'])
      expect(getObservedStorageEntries()).toEqual([])
    } finally {
      vi.useRealTimers()
    }
  })

  it('정밀 검증(Story 2.12): 누르면 곧바로 정밀 검증 중이 보이고, 끝나면 v2로 한 번에 바뀌며 초점은 제목, 결과 표지는 늦은 입금과 영향 시작을 말한다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    try {
      stubSessionRequest(authenticated)
      const job = verificationJobFixture({ reason: 'GAP_SUSPECTED' })
      const lateAt = '2026-09-25T00:00:01Z'
      const v2 = planResultFixture({
        pointerVersion: 2,
        calculationId: 'calculation-2',
        basisAt: '2026-09-27T01:20:00Z',
        measuredAt: '2026-09-27T01:19:59Z',
        cashflowVerifiedThrough: '2026-09-27T01:20:00Z',
        publishedAt: '2026-09-27T01:20:01Z',
        flowCount: 1,
        trust: trustFixture({
          freshness: {
            lastNormalAt: '2026-09-27T01:19:59Z',
            measuredAt: '2026-09-27T01:19:59Z',
            cashflowVerifiedThrough: '2026-09-27T01:20:00Z',
          },
          inputReliability: { lastNormalAt: '2026-09-27T01:20:00Z' },
          calculationNature: { lastNormalAt: '2026-09-27T01:20:00Z' },
        }),
        metrics: [
          { metric: 'CURRENT_VALUE', value: '1275', nature: 'OFFICIAL' },
          { metric: 'PLAN_AMOUNT', value: '1235', nature: 'OFFICIAL' },
          { metric: 'PLAN_DIFFERENCE', value: '40', nature: 'OFFICIAL' },
          { metric: 'ADJUSTED_TARGET', value: '1358.5', nature: 'OFFICIAL' },
          { metric: 'REQUIRED_RATE', value: '0.0648', nature: 'OFFICIAL' },
          { metric: 'GOAL_PROGRESS', value: '0.5', nature: 'OFFICIAL' },
        ],
      })
      const running = { ...job, status: 'RUNNING', stage: 'COLLECTION' }
      const done = {
        ...job,
        status: 'SUCCEEDED',
        stage: 'PUBLICATION',
        finishedAt: '2026-09-27T01:20:01Z',
      }
      const queue: (() => Promise<Response>)[] = [
        () => Promise.resolve(jsonResponse(dashboardFixture())),
        () =>
          Promise.resolve(
            jsonResponse(
              dashboardFixture(
                planResultFixture({
                  trust: trustFixture({
                    freshness: {
                      state: 'SYNCING',
                      cause: 'JOB_IN_PROGRESS',
                      nextAction: 'WAIT_FOR_SYNC',
                    },
                  }),
                }),
                'WAIT_FOR_SYNC',
                syncFixture({ availability: 'IN_PROGRESS', latest: running }),
                verificationFixture({ availability: 'IN_PROGRESS', latest: running }),
              ),
            ),
          ),
        () =>
          Promise.resolve(
            jsonResponse(
              dashboardFixture(
                v2,
                'NONE',
                syncFixture({ availability: 'COOLDOWN', retryAfterSeconds: 25, latest: done }),
                verificationFixture({
                  availability: 'COOLDOWN',
                  retryAfterSeconds: 25,
                  latest: done,
                }),
                changeFixture({
                  kind: 'CORRECTED',
                  purpose: 'VERIFY',
                  reason: 'GAP_SUSPECTED',
                  lateAddedCount: 1,
                  firstAffectedAt: lateAt,
                }),
              ),
            ),
          ),
      ]
      stubDashboard(() => (queue.shift() ?? (() => Promise.reject(new Error('extra'))))())
      const bodies: unknown[] = []
      stubDashboardVerification((init) => {
        bodies.push(JSON.parse(init?.body as string))
        return Promise.resolve(
          jsonResponse({ outcome: 'ACCEPTED', job, retryAfterSeconds: null }, 202),
        )
      })
      render(<SignedInScreen />)
      expect(await screen.findByText('1,250.00 USD')).toBeInTheDocument()
      await waitFor(() => {
        expect(syncPosts()).toHaveLength(1)
      })
      const before = screen.getAllByRole('article').map((card) => card.textContent)
      const recorder = recordStatus()

      const control = screen.getByRole('button', { name: planVerificationContent.action })
      control.focus()
      fireEvent.click(control)
      fireEvent.click(control)
      // At once, before any answer: 정밀 검증 중, the button blocked and still focused, the values kept.
      expect(screen.getByText(planVerificationContent.running, { exact: true })).toBeVisible()
      expect(control).toHaveAttribute('aria-disabled', 'true')
      expect(control).toHaveFocus()
      expect(screen.getByRole('status').textContent).toBe(planVerificationContent.status.running)
      expect(screen.getAllByRole('article').map((card) => card.textContent)).toEqual(before)
      // The sync button is blocked too, and says why.
      expect(screen.getByRole('button', { name: planSyncContent.action })).toHaveAttribute(
        'aria-disabled',
        'true',
      )
      await waitFor(() => {
        expect(bodies).toEqual([
          {
            kind: 'PRECISION',
            planId: 'plan-1',
            pointerVersion: 1,
            calculationId: 'calculation-1',
          },
        ])
      })

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000)
      })
      expect(screen.getByText('1,250.00 USD')).toBeInTheDocument()
      expect(screen.getByRole('status').textContent).toBe(planVerificationContent.status.running)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000)
      })
      expect(await screen.findByText('1,275.00 USD')).toBeInTheDocument()
      expect(screen.queryByText('1,250.00 USD')).not.toBeInTheDocument()
      expect(
        screen.getByRole('heading', { level: 2, name: planResultContent.heading }),
      ).toHaveFocus()
      const basis = formatSeoulTime('2026-09-27T01:20:00Z', planResultContent.timeUnavailable)
      const closing = planVerificationContent.status.succeeded(
        planVerificationContent.change.correctedSentence(ONE_LATE),
        null,
        basis,
      )
      expect(screen.getByRole('status').textContent).toBe(closing)
      // The result marker: one late deposit, and where its impact starts.
      const start = formatSeoulTime(lateAt, planResultContent.timeUnavailable)
      expect(screen.getByRole('region', { name: planResultContent.heading })).toHaveTextContent(
        `${planVerificationContent.change.corrected(ONE_LATE)}, ${planVerificationContent.change.impactStart} ${start}`,
      )
      expect(screen.getByText(planVerificationContent.cooldown(25))).toBeInTheDocument()
      recorder.stop()
      expect(recorder.texts).toEqual([planVerificationContent.status.running, closing])
      expect(recorder.busy.every((value) => value === 'false')).toBe(true)
      expect(screen.getAllByRole('status')).toHaveLength(1)
      expect(liveRegions()).toHaveLength(1)
      expect(
        getObservedAllowedRequests().filter(
          (entry) => entry === `POST ${DASHBOARD_VERIFICATION_PATH}`,
        ),
      ).toHaveLength(1)
      expect(getObservedStorageEntries()).toEqual([])
    } finally {
      vi.useRealTimers()
    }
  })

  it('동기화: 공식 결과를 바꾸지 못한 수동 동기화는 오류 요약으로 초점을 옮기고 원인을 보인다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    try {
      stubSessionRequest(authenticated)
      const job = syncJobFixture()
      const queue: (() => Promise<Response>)[] = [
        () => Promise.resolve(jsonResponse(dashboardFixture())),
        () =>
          Promise.resolve(
            jsonResponse(
              dashboardFixture(
                planResultFixture({
                  trust: trustFixture({
                    freshness: {
                      state: 'STALE',
                      cause: 'LAST_SYNC_UNSUCCESSFUL',
                      nextAction: 'SYNC',
                      affectedMetrics: [...METRIC_ORDER],
                    },
                  }),
                }),
                'SYNC',
                syncFixture({
                  availability: 'COOLDOWN',
                  retryAfterSeconds: 20,
                  latest: {
                    ...job,
                    status: 'FAILED',
                    stage: 'COLLECTION',
                    failureCode: 'PROVIDER_UNAVAILABLE',
                    finishedAt: '2026-09-27T01:10:04Z',
                  },
                }),
              ),
            ),
          ),
      ]
      stubDashboard(() => (queue.shift() ?? (() => Promise.reject(new Error('extra'))))())
      const answers: (() => Promise<Response>)[] = [
        () => Promise.resolve(jsonResponse(NOT_DUE)),
        () =>
          Promise.resolve(jsonResponse({ outcome: 'ACCEPTED', job, retryAfterSeconds: null }, 202)),
      ]
      stubDashboardSync(() => (answers.shift() ?? (() => Promise.reject(new Error('extra'))))())
      render(<SignedInScreen />)
      await screen.findByText('1,250.00 USD')
      await waitFor(() => {
        expect(syncPosts()).toHaveLength(1)
      })
      fireEvent.click(screen.getByRole('button', { name: planSyncContent.action }))
      await waitFor(() => {
        expect(syncPosts()).toHaveLength(2)
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000)
      })
      const label = await screen.findByText(planSyncContent.summary.statuses.FAILED)
      // eslint-disable-next-line testing-library/no-node-access -- 오류 요약은 역할이 없는 초점 대상(tabIndex -1)이다. 첫 문장 요소의 부모로만 찾을 수 있다.
      const summary = label.parentElement
      expect(summary).toHaveFocus()
      expect(summary).toHaveTextContent(planSyncContent.summary.codes.PROVIDER_UNAVAILABLE)
      expect(screen.getByRole('status').textContent).toBe(planSyncContent.status.failed)
      // The last normal values stay, with the freshness cause and its next action.
      expect(screen.getByText('1,250.00 USD')).toBeInTheDocument()
      expect(
        screen.getByText(planResultContent.axes.freshness.causes.LAST_SYNC_UNSUCCESSFUL ?? ''),
      ).toBeInTheDocument()
      // No automatic retry.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(30_000)
      })
      expect(syncPosts()).toHaveLength(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('동기화: 5분 이상 숨겨 둔 탭으로 돌아오면 세션을 다시 확인한 뒤 탭 복귀 동기화를 한 번 보낸다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    let visibility: DocumentVisibilityState = 'visible'
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibility,
    })
    try {
      stubSessionRequest(authenticated)
      stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture())))
      const triggers: string[] = []
      stubDashboardSync((init) => {
        triggers.push((JSON.parse(init?.body as string) as { trigger: string }).trigger)
        return Promise.resolve(jsonResponse(NOT_DUE))
      })
      render(<SignedInScreen />)
      await screen.findAllByRole('article')
      await waitFor(() => {
        expect(triggers).toEqual(['ENTRY'])
      })

      visibility = 'hidden'
      act(() => {
        document.dispatchEvent(new Event('visibilitychange'))
      })
      vi.setSystemTime(Date.now() + 5 * 60_000)
      visibility = 'visible'
      act(() => {
        document.dispatchEvent(new Event('visibilitychange'))
      })
      // The session is checked again first (Story 1.5); the answer read after it sends VISIBILITY.
      await waitFor(() => {
        expect(triggers).toEqual(['ENTRY', 'VISIBILITY'])
      })
      expect(getObservedSessionRequests()).toHaveLength(2)
      await screen.findAllByRole('article')
      expect(triggers).toEqual(['ENTRY', 'VISIBILITY'])
    } finally {
      vi.useRealTimers()
      Object.defineProperty(document, 'visibilityState', {
        configurable: true,
        get: () => 'visible',
      })
    }
  })

  it('AC-10: 대시보드에 투자 추천·성과·축하·공포·스트릭·배지 문구가 없다', async () => {
    stubSessionRequest(authenticated)
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture())))
    render(<SignedInScreen />)

    await screen.findAllByRole('article')
    const text = document.body.textContent.replace(authContent.disclaimer, '')
    expect(text).not.toMatch(
      /축하|손실|추천합니다|투자성과|계좌 수익률|스트릭|배지|달성하세요|놓치지|서두르/,
    )
  })

  it('미인증으로 이 경로에 직접 들어오면 인증 화면으로 되돌린다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: false, destination: 'entry' })),
    )

    render(<SignedInScreen />)

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    // 세 버튼을 함께 본다. 하나만 보면 나머지 제공자의 버튼이 사라져도 이 시험이 통과한다
    // (`expectAuthScreenMounted`·`App.test.tsx`·`EntryScreen.test.tsx`가 쓰는 것과 같은 형태다).
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.getByRole('link', { name: authContent.signIn.labels[provider] }),
      ).toBeInTheDocument()
    }
    expect(screen.queryByText(authContent.dashboard.heading)).not.toBeInTheDocument()
  })

  it('세션 확인이 실패하면 인증 화면으로 되돌린다', async () => {
    stubSessionRequest(() => Promise.reject(new Error('네트워크 단절')))

    render(<SignedInScreen />)

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    // 자동 재시도하지 않는다. 요청은 한 번뿐이다.
    expect(getObservedSessionRequests()).toHaveLength(1)
  })

  it('세션을 확인해도 브라우저 저장소 항목이 생기지 않는다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    expect(getObservedStorageEntries()).toEqual([])
  })

  it('서버가 온보딩을 고르면 그 목적지로 보내고 사용자 데이터를 그리지 않는다', async () => {
    // 온보딩을 지나야 하는 계정이 이 경로를 직접 열면 서버는 `onboarding`을 답한다. 그 값을
    // 버리면 화면은 로그인 상태를 그리고 AC-4의 "신규 사용자는 온보딩"이 성립하지 않는다.
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<SignedInScreen />)

    expect(await screen.findByText(authContent.session.redirecting)).toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith('/onboarding')
    expect(screen.queryByText(authContent.dashboard.heading)).not.toBeInTheDocument()
    for (const item of authContent.dashboard.items) {
      expect(screen.queryByText(item)).not.toBeInTheDocument()
    }
  })

  it('기대한 목적지에서는 내비게이션을 부르지 않는다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('목적지가 인증 화면이면 사용자 데이터를 그리지 않고 되돌린다', async () => {
    // "인증됐다"와 "목적지는 인증 화면"이 함께 오는 응답은 계약상 나오지 않는다. 그래도 화면이
    // 로그인 상태를 주장하지 않는 편이 안전한 기본값이다.
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'entry' })),
    )

    render(<SignedInScreen />)

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('접근성: 건너뛰기 링크가 본문 랜드마크를 가리킨다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    const skipLink = screen.getByRole('link', { name: '본문으로 건너뛰기' })

    expect(skipLink).toHaveAttribute('href', '#signed-in-main')
    expect(screen.getByRole('main')).toHaveAttribute('id', 'signed-in-main')
  })

  it('접근성: polite live region이 화면당 하나다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    expect(screen.getAllByRole('status')).toHaveLength(1)
  })

  /*
   * 아래는 Story 1.5가 더한 로그아웃이다. 위 단정 축은 한 줄도 바뀌지 않았다.
   */

  it('로그아웃: 세션 확인이 준 토큰을 헤더로 되돌려 보낸다', async () => {
    stubSessionRequest(authenticated)
    let observed: RequestInit | undefined
    stubLogoutRequest((init) => {
      observed = init
      return Promise.resolve(new Response(null, { status: 204 }))
    })

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })
    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=loggedOut')
    })
    // 토큰은 헤더로만 간다. 쿼리 문자열은 로그·`Referer`·히스토리에 남는다.
    expect(new Headers(observed?.headers).get(CSRF_HEADER)).toBe(CSRF_TOKEN)
    // 세션 쿠키를 실어 보낸다. 동일 출처로 제한한다.
    expect(observed?.credentials).toBe('same-origin')
    // 캐시가 이 응답을 되살리지 못하게 한다.
    expect(observed?.cache).toBe('no-store')
    // 세션 확인과 로그아웃 두 요청뿐이다. 이 화면은 그 밖의 요청을 보내지 않는다.
    expect(getObservedAllowedRequests()).toEqual(['GET /api/auth/session', 'POST /api/auth/logout'])
  })

  it('로그아웃: 토큰이 없으면 상태 변경을 보내지 않고 실패 표지로 수렴한다', async () => {
    // 세션 확인이 토큰 없이 인증됐다고 답하는 경우다. 보내도 서버가 403으로 거절하므로, 실패가
    // 예정된 상태 변경 시도 하나를 로그에 남기지 않는다.
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'dashboard' })),
    )

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })
    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    /*
     * 표지를 단다. 표지 없이 `/`로 보내면 `noticeKindOf`가 `null`을 돌려주어 안내가 렌더되지
     * 않고 진입 화면의 제목 포커스도 건너뛴다 — 세션이 살아 있는데 아무 설명 없는 로그인 화면이
     * 보이는 상태다.
     */
    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=logoutFailed')
    })
    expect(getObservedAllowedRequests()).toEqual(['GET /api/auth/session'])
  })

  it('로그아웃: 204가 아니면 인증 상태를 주장하지 않고 진입 화면으로 수렴한다', async () => {
    stubSessionRequest(authenticated)
    stubLogoutRequest(() => Promise.resolve(new Response('{}', { status: 403 })))

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })
    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    /*
     * 로그아웃 표지를 달지 않는다. 403으로 거절된 요청에서는 세션이 살아 있고, 그때 로그아웃했다고
     * 말하면 사용자는 끝나지 않은 세션을 끝난 것으로 믿는다. 대신 실패를 말하는 표지를 단다.
     */
    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=logoutFailed')
    })
    expect(vi.mocked(navigateTo)).not.toHaveBeenCalledWith('/?auth=loggedOut')
    // 자동 재시도하지 않는다. 요청은 하나뿐이다.
    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toHaveLength(1)
  })

  it('로그아웃: 네트워크가 끊겨도 인증 상태를 주장하지 않는다', async () => {
    stubSessionRequest(authenticated)
    stubLogoutRequest(() => Promise.reject(new Error('네트워크 단절')))

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })
    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=logoutFailed')
    })
    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toHaveLength(1)
  })

  it('로그아웃: 진행 중 중복 제출을 차단한다', async () => {
    stubSessionRequest(authenticated)
    let resolveLogout: (response: Response) => void = () => undefined
    stubLogoutRequest(
      () =>
        new Promise<Response>((resolve) => {
          resolveLogout = resolve
        }),
    )

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    const button = screen.getByRole('button', { name: authContent.signOut.label })
    fireEvent.click(button)

    /*
     * 접근 가능한 이름은 동작으로 남고 상태는 live region이 말한다. 이름을 진행 중 문구로 바꾸면
     * 컨트롤의 이름이 동작 아닌 상태가 되고, 그 변화는 live region이 아니어서 안내되지도 않는다.
     */
    const pending = screen.getByRole('button', { name: authContent.signOut.label })
    expect(pending).toHaveAttribute('aria-disabled', 'true')
    // `disabled`가 아니다. 그 속성은 방금 이 버튼을 실행한 키보드 사용자의 포커스를 빼앗는다.
    expect(pending).toBeEnabled()
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status').textContent).toBe(authContent.signOut.pending)

    fireEvent.click(pending)

    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toHaveLength(1)
    resolveLogout(new Response(null, { status: 204 }))
    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=loggedOut')
    })
  })

  it('로그아웃: 저장소에 아무것도 남기지 않는다', async () => {
    stubSessionRequest(authenticated)
    stubLogoutRequest(() => Promise.resolve(new Response(null, { status: 204 })))

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })
    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=loggedOut')
    })
    // 토큰은 메모리에만 있었다. `localStorage`·`sessionStorage`·쿠키·IndexedDB 항목이 0건이다.
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('로그아웃: 최소 44×44px 목표 크기를 선언한다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    /*
     * jsdom은 레이아웃을 계산하지 않아 실제 크기를 잴 수 없다. 실측은 E2E가 한다
     * (`e2e/auth-entry.spec.ts`). 여기서 보는 것은 버튼이 그 크기를 선언한 클래스를 들고 있다는
     * 사실이며, 클래스가 빠지면 E2E보다 먼저 여기서 드러난다.
     */
    const button = screen.getByRole('button', { name: authContent.signOut.label })

    expect(button.className).not.toBe('')
    expect(button).toHaveAttribute('type', 'button')
  })

  it('로그아웃: 세션 확인 없이 상태 변경 요청을 보내지 않는다', () => {
    stubSessionRequest(pendingResponse)

    render(<SignedInScreen />)

    // 확인 중에는 버튼이 없으므로 상태 변경이 시작될 자리도 없다.
    expect(
      screen.queryByRole('button', { name: authContent.signOut.label }),
    ).not.toBeInTheDocument()
    expect(getObservedAllowedRequests()).toEqual(['GET /api/auth/session'])
  })

  // --------------------------------------------------------------------
  // 재인증 (Story 1.8)
  // --------------------------------------------------------------------

  it('재인증: 표시된 세션에서 패널을 제자리에 렌더하고 이동하지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )

    render(<SignedInScreen />)

    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading }),
    ).toBeInTheDocument()
    /*
     * 미인증 응답이지만 인증 화면으로 되돌리지 않는다. 표시된 세션은 이미 자기 목적지에 있고,
     * 인증 화면으로 보내면 그 화면의 로그인 버튼을 서버가 "이미 인증된 세션"으로 거절해
     * 무한 왕복이 된다.
     */
    expect(screen.queryByText(authContent.notices.signedOut)).not.toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
    expect(vi.mocked(navigateTo)).not.toHaveBeenCalled()
    // 사용자 데이터는 한 글자도 없다. 이 세션은 아직 쓸 수 없는 상태다.
    for (const item of authContent.dashboard.items) {
      expect(screen.queryByText(item)).not.toBeInTheDocument()
    }
  })

  it('재인증: live region이 하나로 남고 표시된 세션은 갱신 중이 아니다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })

    // 패널이 자기 live region을 만들지 않는다. 상태를 말하는 자리는 화면당 하나다.
    expect(screen.getAllByRole('status')).toHaveLength(1)
    const status = screen.getByRole('status')
    expect(status.textContent).toBe(authContent.reauth.status)
    expect(status).toHaveAttribute('aria-busy', 'false')
  })

  it('재인증: 로그아웃 버튼이 패널에 하나뿐이다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })

    /*
     * 머리말 버튼은 게이트가 `ready`일 때만 나온다. 표시된 세션에서 둘이 함께 나오면 같은
     * 조작이 화면에 두 번 있고, 그중 하나는 사용자가 방금 읽은 안내와 멀리 떨어져 있다.
     */
    expect(screen.getAllByRole('button', { name: authContent.signOut.label })).toHaveLength(1)
  })

  it('재인증: 버튼을 누르면 토큰을 헤더에 싣고 그 제공자의 인가 경로로 이동한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )
    let observedInit: RequestInit | undefined
    stubReauthenticationRequest('naver', (init) => {
      observedInit = init
      return Promise.resolve(new Response(null, { status: 204 }))
    })

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })

    fireEvent.click(screen.getByRole('button', { name: authContent.signIn.labels.naver }))

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith(authorizePath('naver'))
    })
    // 세션 귀속 토큰이 헤더로 간다. 쿼리 문자열이면 로그·`Referer`·히스토리에 남는다.
    expect(new Headers(observedInit?.headers).get(CSRF_HEADER)).toBe('reauth-token')
    expect(observedInit?.credentials).toBe('same-origin')
    expect(getObservedAllowedRequests()).toEqual([
      'GET /api/auth/session',
      'POST /api/auth/reauthenticate/naver',
    ])
  })

  it('재인증: 진행 중에는 두 번째 요청이 나가지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )
    stubReauthenticationRequest('google', pendingResponse)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })

    const google = screen.getByRole('button', { name: authContent.signIn.labels.google })
    fireEvent.click(google)
    await waitFor(() => {
      expect(google).toHaveAttribute('aria-disabled', 'true')
    })
    fireEvent.click(google)
    // 다른 제공자도 막힌다. 두 흐름이 심기면 사용자가 고른 것과 실제로 열리는 것이 달라진다.
    fireEvent.click(screen.getByRole('button', { name: authContent.signIn.labels.kakao }))

    expect(getObservedAllowedRequests()).toEqual([
      'GET /api/auth/session',
      'POST /api/auth/reauthenticate/google',
    ])
    expect(screen.getByRole('status').textContent).toBe(authContent.reauth.pending)
  })

  it('재인증: 시작이 성립하지 않으면 제자리에 표지를 달고 제공자로 가지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )
    stubReauthenticationRequest('google', () =>
      Promise.resolve(new Response(null, { status: 403 })),
    )

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })

    fireEvent.click(screen.getByRole('button', { name: authContent.signIn.labels.google }))

    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(
        reauthNoticePath(window.location.pathname),
      )
    })
    // 무장이 없으면 인가 시작은 목적지로 되돌려질 뿐이다. 보내지 않는다.
    expect(vi.mocked(navigateTo)).not.toHaveBeenCalled()
  })

  it('재인증: 표시된 세션은 만료로 세지 않는다', async () => {
    let answered = 0
    stubSessionRequest(() => {
      answered += 1
      return Promise.resolve(
        answered === 1
          ? jsonResponse({ authenticated: true, destination: 'dashboard' })
          : jsonResponse({
              authenticated: false,
              csrfToken: 'reauth-token',
              reauthRequired: true,
            }),
      )
    })

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    // 탭이 다시 보이는 순간의 재확인이 표시를 관측한다.
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading }),
    ).toBeInTheDocument()
    /*
     * 만료가 아니다. 서버 응답의 모양은 같지만 결과가 다르다 — 만료는 인증 화면으로 보내고
     * 표시는 제자리에서 회복 경로를 연다. 만료로 세면 회복에 필요한 토큰이 그 이동과 함께
     * 사라진다.
     */
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('재인증: 브라우저 저장소에 아무것도 쓰지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )
    stubReauthenticationRequest('google', () =>
      Promise.resolve(new Response(null, { status: 204 })),
    )

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })
    fireEvent.click(screen.getByRole('button', { name: authContent.signIn.labels.google }))
    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalled()
    })

    // 토큰은 메모리에만 있다. 브라우저에 남는 값은 세션 쿠키 하나로 유지된다.
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('재인증: 패널이 차지한 화면에도 제목과 고지가 인접한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })

    /*
     * 배열 전체를 본다. 패널이 화면의 본문 자리를 통째로 차지하므로 UX-DR23의 두 문장도 이
     * 상태에서 함께 렌더돼야 하고, 그 둘은 패널이 아니라 화면이 낸다 — 배열을 쪼개 보면 그
     * 경계가 어느 쪽에서도 확인되지 않는다.
     */
    for (const copy of reauthCopyStrings) {
      expect(screen.getByText(copy)).toBeInTheDocument()
    }
  })

  it('재인증: 주소의 표지를 패널 문장으로 옮긴다', async () => {
    for (const kind of ['reauthCancelled', 'reauthFailed'] as const) {
      navigateToSignedInScreen(`?auth=${kind}`)
      stubSessionRequest(() =>
        Promise.resolve(
          jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
        ),
      )

      const { unmount } = render(<SignedInScreen />)
      await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })

      // 서버가 붙들어 둔 목적지에 달아 보낸 표지다. 화면이 그것을 패널로 옮기지 않으면
      // 사용자는 왜 돌아왔는지 알 수 없다.
      expect(screen.getByText(authContent.notices[kind])).toBeInTheDocument()
      unmount()
    }
  })

  it('재인증: 재인증과 무관한 표지는 패널에 나타나지 않는다', async () => {
    navigateToSignedInScreen('?auth=loggedOut')
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })

    /*
     * "로그아웃했습니다 … 다시 로그인해 주세요"는 아직 로그인해 있는 사용자에게 거짓이다.
     * 주소는 누구나 적을 수 있으므로 거름은 화면의 일이고, 타입이 그 거름을 강제한다.
     */
    expect(screen.queryByText(authContent.notices.loggedOut)).not.toBeInTheDocument()
  })
})

describe('SignedInScreen: 계획 중도 종료 (Story 2.13)', () => {
  const FORBIDDEN =
    /실시간|정확|보장|성공|실패|축하|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/

  /** The prepare's answer for the default bundle. */
  function prepared(reason = 'USER_REQUEST'): Record<string, unknown> {
    return {
      reason,
      planId: 'plan-1',
      pointerVersion: 1,
      calculationId: 'calculation-1',
      confirmation: `plan-end|${reason}|plan-1|1|calculation-1`,
    }
  }

  function syncPosts(): number {
    return getObservedAllowedRequests().filter((entry) => entry === `POST ${DASHBOARD_SYNC_PATH}`)
      .length
  }

  /** The end button, once it can be pressed (the entry sync's automatic admission blocks it until it answers). */
  async function pressableEnd(): Promise<HTMLElement> {
    const end = await screen.findByRole('button', { name: planEndContent.area.action })
    await waitFor(() => {
      expect(end).toHaveAttribute('aria-disabled', 'false')
    })
    return end
  }

  /** Records each distinct text of the screen's status region from now on (never browser storage). */
  function recordStatus(): { readonly texts: string[]; readonly stop: () => void } {
    const texts: string[] = []
    const region = screen.getByRole('status')
    const observer = new MutationObserver(() => {
      const text = region.textContent
      if (texts.at(-1) !== text) texts.push(text)
    })
    observer.observe(region, { characterData: true, childList: true, subtree: true })
    return {
      texts,
      stop: () => {
        observer.disconnect()
      },
    }
  }

  it('AC: the dialog opens over an inert page; the end closes it, focuses the panel heading and says the record once', async () => {
    stubSessionRequest(authenticated)
    let ended = false
    stubDashboard(() =>
      Promise.resolve(jsonResponse(ended ? endedDashboardFixture() : dashboardFixture())),
    )
    const sent: (RequestInit | undefined)[] = []
    stubPlanEndPrepare((init) => {
      sent.push(init)
      return Promise.resolve(jsonResponse(prepared()))
    })
    stubPlanEndConfirm((init) => {
      sent.push(init)
      ended = true
      return Promise.resolve(
        jsonResponse({
          outcome: 'ENDED',
          planId: 'plan-1',
          reason: 'USER_REQUEST',
          endedAt: ENDED_AT,
        }),
      )
    })
    render(<SignedInScreen />)

    const end = await pressableEnd()
    expect(syncPosts()).toBe(1)
    end.focus()
    fireEvent.click(end)
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveAccessibleName(planEndContent.dialog.titles.USER_REQUEST)
    // The dialog is the sibling of the inert page, never inside it.
    // eslint-disable-next-line testing-library/no-node-access -- `inert`는 역할 조회로 드러나지 않는 속성이다.
    const page = document.querySelector('[inert]')
    expect(page).not.toBeNull()
    expect(page).toContainElement(screen.getByRole('main'))
    expect(page).not.toContainElement(dialog)
    const cancel = within(dialog).getByRole('button', { name: planEndContent.dialog.cancel })
    expect(cancel).toHaveFocus()
    // Both requests carry the session token; the CSRF header is the session's.
    expect((sent[0]?.headers as Record<string, string>)[CSRF_HEADER]).toBe(CSRF_TOKEN)

    const recorder = recordStatus()
    const { texts } = recorder
    fireEvent.click(within(dialog).getByRole('button', { name: planEndContent.dialog.confirm }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    const heading = screen.getByRole('heading', { level: 2, name: planResultContent.heading })
    expect(heading).toHaveFocus()
    expect(await screen.findByRole('region', { name: planEndContent.record.heading })).toBeVisible()
    expect(heading).toHaveFocus()
    // eslint-disable-next-line testing-library/no-node-access -- `inert`는 역할 조회로 드러나지 않는 속성이다.
    expect(document.querySelector('[inert]')).toBeNull()
    const confirmed = sent[1]
    expect((confirmed?.headers as Record<string, string>)['Idempotency-Key']).toMatch(
      /^[A-Za-z0-9._:-]{16,128}$/,
    )
    expect(JSON.parse(confirmed?.body as string)).toMatchObject({
      confirmation: 'plan-end|USER_REQUEST|plan-1|1|calculation-1',
    })

    // One announcement of the end: the record's sentence, never "reading again".
    const sentence = planEndContent.status.ended(
      formatSeoulTime(ENDED_AT, planResultContent.timeUnavailable),
    )
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(sentence)
    })
    recorder.stop()
    expect(texts.filter((text) => text.includes(sentence))).toHaveLength(1)
    expect(texts.some((text) => text.includes(planResultContent.status.reloading))).toBe(false)
    expect(screen.getAllByRole('status')).toHaveLength(1)
    // The record: no sync, verification or end control, and no further sync request.
    for (const name of [planSyncContent.action, planEndContent.area.action]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    await new Promise((settle) => setTimeout(settle, 30))
    expect(syncPosts()).toBe(1)
    expect(getObservedStorageEntries()).toEqual([])
    expect(window.location.search).toBe('')
  })

  it('without a recent reauthentication: the guidance and provider buttons in the end area, and a provider leaves for its sign-in', async () => {
    stubSessionRequest(authenticated)
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture())))
    stubPlanEndPrepare(() =>
      Promise.resolve(
        jsonResponse({ code: 'REAUTHENTICATION_REQUIRED', message: 'x', correlationId: 'c' }, 403),
      ),
    )
    stubReauthenticationRequest('google', () =>
      Promise.resolve(new Response(null, { status: 204 })),
    )
    render(<SignedInScreen />)
    fireEvent.click(await pressableEnd())
    const guidance = await screen.findByText(planEndContent.reauth.text)
    // eslint-disable-next-line testing-library/no-node-access -- 안내 묶음은 역할이 없는 초점 대상(tabIndex -1)이다.
    expect(guidance.parentElement).toHaveFocus()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(planEndContent.status.reauthRequired)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.reauth.labels.google }))
    await waitFor(() => {
      expect(navigateTo).toHaveBeenCalledWith(authorizePath('google'))
    })
    expect(screen.getByRole('status')).toHaveTextContent(authContent.reauth.pending)
  })

  it('`Esc` cancels: the dialog closes, focus returns to the button, the flow is released and nothing changed', async () => {
    stubSessionRequest(authenticated)
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture())))
    stubPlanEndPrepare(() => Promise.resolve(jsonResponse(prepared())))
    stubPlanEndCancel(() => Promise.resolve(new Response(null, { status: 204 })))
    render(<SignedInScreen />)
    const end = await pressableEnd()
    end.focus()
    fireEvent.click(end)
    await screen.findByRole('dialog')
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(end).toHaveFocus()
    expect(screen.getByRole('status')).toHaveTextContent(planEndContent.status.cancelled)
    await waitFor(() => {
      expect(getObservedAllowedRequests()).toContain(`POST ${PLAN_END_CANCEL_PATH}`)
    })
    expect(getObservedAllowedRequests()).not.toContain(`POST ${PLAN_END_CONFIRM_PATH}`)
  })

  it('the record of a plan ended early: no sync request at all, the disclaimer beside the title, and no forbidden wording', async () => {
    stubSessionRequest(authenticated)
    const result = endedResultFixture()
    stubDashboard(() =>
      Promise.resolve(jsonResponse(endedDashboardFixture(result, longGapEndRecordFixture(result)))),
    )
    render(<SignedInScreen />)
    expect(await screen.findByRole('region', { name: planEndContent.record.heading })).toBeVisible()
    await new Promise((settle) => setTimeout(settle, 30))
    expect(syncPosts()).toBe(0)
    const title = screen.getByRole('heading', { level: 1 })
    expect(title.nextElementSibling?.textContent).toBe(authContent.disclaimer)
    const text = document.body.textContent.replace(authContent.disclaimer, '')
    expect(text).not.toMatch(FORBIDDEN)
    expect(text.replaceAll(planEndContent.record.completion, '')).not.toContain('완료')
    expect(getObservedStorageEntries()).toEqual([])
  })
})
