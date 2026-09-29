import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { adjustmentHistoryContent } from '../content/adjustmentHistory'
import {
  authContent,
  authorizePath,
  CSRF_HEADER,
  onboardingCopyStrings,
  reauthCopyStrings,
  reauthNoticePath,
  SETTINGS_PATH,
  SIGN_IN_PROVIDERS,
} from '../content/auth'
import { calculationEvidenceContent } from '../content/calculationEvidence'
import { entryContent } from '../content/entry'
import { DASHBOARD_PATH, METRIC_ORDER, planResultContent } from '../content/planResult'
import { formatSeoulTime } from '../format/decimal'
import { navigateTo, replaceLocation } from '../session/navigation'
import {
  adjustmentPageFixture,
  adjustmentRowFixture,
  BASIS_AT,
  calculationEvidenceFixture,
  dashboardFixture,
  EVIDENCE_IDS,
  evidenceResultFixture,
  NO_PLAN_FIXTURE,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedSessionRequests,
  getObservedStorageEntries,
  stubAdjustmentHistory,
  stubCalculationEvidence,
  stubDashboard,
  stubLogoutRequest,
  stubReauthenticationRequest,
  stubSessionRequest,
} from '../test/setup'
import { OnboardingScreen } from './OnboardingScreen'

/*
 * 최상위 내비게이션을 가로챈다.
 *
 * jsdom은 `location.replace`·`location.assign`을 실제로 수행하지 않고 관측할 수도 없다. 서버가
 * 고른 목적지로 보내는지와 로그아웃 뒤 표지 경로로 가는지가 이 화면의 계약이므로, 그 전환을
 * 소유한 모듈 하나를 대신 세운다.
 */
vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** bfcache 복원 신호. `persisted`는 읽기 전용이라 생성 후 정의한다. */
function pageShow(persisted: boolean): Event {
  const event = new Event('pageshow')
  Object.defineProperty(event, 'persisted', { value: persisted })
  return event
}

/** 응답이 아직 오지 않은 상태. `checking` 분기를 관측하기 위한 의도된 무기한 대기다. */
function pendingResponse(): Promise<Response> {
  return new Promise<Response>(() => {
    // 아무것도 하지 않는다. resolve를 부르지 않는 것이 이 함수의 목적이다.
  })
}

/*
 * `restoreMocks`는 `vi.spyOn`이 만든 spy만 원래대로 돌린다. `vi.mock` 팩토리의 `vi.fn()`은 호출
 * 기록을 그대로 들고 다음 시험으로 넘어가므로 "부르지 않았다"를 단정할 수 없다. 매 시험 앞에서
 * 직접 비운다.
 */
beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  vi.mocked(navigateTo).mockClear()
})

/**
 * 이 화면의 경로를 그 쿼리와 함께 연다.
 *
 * `App.test.tsx`의 관용이다. jsdom의 기본 `search`는 빈 문자열이라 주소를 옮기지 않으면 화면이
 * 패널에 넘기는 표지가 언제나 `null`이고, 그 배선은 어느 단정도 지나지 않는다.
 */
function navigateToOnboardingScreen(search: string): void {
  window.history.pushState({}, '', `/onboarding${search}`)
}

/* 주소를 옮긴 시험이 다음 시험에 그 쿼리를 물려주지 않게 한다. */
afterEach(() => {
  window.history.pushState({}, '', '/onboarding')
})

describe('OnboardingScreen', () => {
  it('확인 중에는 로그인 상태를 주장하지 않고 사용자 데이터를 표시하지 않는다', () => {
    stubSessionRequest(pendingResponse)

    render(<OnboardingScreen />)

    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-busy', 'true')
    expect(status.textContent).toBe(authContent.session.checking)
    expect(screen.queryByText(authContent.onboarding.heading)).not.toBeInTheDocument()
    for (const item of authContent.onboarding.items) {
      expect(screen.queryByText(item)).not.toBeInTheDocument()
    }
  })

  it('세션이 확인되면 온보딩 카피를 렌더한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)

    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading }),
    ).toBeInTheDocument()
    const rendered = document.body.textContent
    for (const copy of onboardingCopyStrings) {
      expect(rendered).toContain(copy)
    }
  })

  it('첫 로그인 세션도 공식 대시보드를 같은 패널·같은 순서로 보고, 계획이 없으면 그렇게 말한다', async () => {
    // A first-login session is sent here from `/dashboard`, so this landing screen shows the dashboard.
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture())))
    const { unmount } = render(<OnboardingScreen />)

    expect(
      await screen.findByRole('heading', { level: 3, name: planResultContent.currentHeading }),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(METRIC_ORDER.length)
    expect(
      screen
        .getAllByRole('heading', { level: 3 })
        .map((heading) => heading.textContent)
        .slice(0, 4),
    ).toEqual([
      planResultContent.currentHeading,
      planResultContent.trustHeading,
      planResultContent.metricsHeading,
      planResultContent.nextActionHeading,
    ])
    expect(screen.getAllByRole('status')).toHaveLength(1)
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 aria-live 영역은 역할 조회로 보이지 않는다. 화면의 live region 수는 속성으로 세야 한다.
    expect(document.querySelectorAll('[role="status"], [aria-live]')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent(
      planResultContent.status.ready(formatSeoulTime(BASIS_AT, planResultContent.timeUnavailable)),
    )
    expect(screen.getAllByText(/1,250\.00 USD/)).toHaveLength(1)
    unmount()

    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )
    stubDashboard(() => Promise.resolve(jsonResponse(NO_PLAN_FIXTURE)))
    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })
    await waitFor(() => {
      expect(getObservedAllowedRequests()).toContain(`GET ${DASHBOARD_PATH}`)
    })
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(planResultContent.status.noPlan)
    })
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })

  it('계산 근거: 첫 로그인 세션의 대시보드에서도 같은 자리에서 펼치고 상태 리전 하나가 그 읽기를 알린다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture(evidenceResultFixture()))))
    stubCalculationEvidence(() =>
      Promise.resolve(jsonResponse(calculationEvidenceFixture(evidenceResultFixture()))),
    )
    render(<OnboardingScreen />)
    await screen.findAllByRole('article')

    fireEvent.click(screen.getByRole('button', { name: calculationEvidenceContent.open }))
    expect(
      screen.getByRole('heading', { level: 4, name: calculationEvidenceContent.heading }),
    ).toHaveFocus()
    await screen.findByRole('group', { name: calculationEvidenceContent.sections.versions })
    const ready = planResultContent.status.ready(
      formatSeoulTime(BASIS_AT, planResultContent.timeUnavailable),
    )
    expect(screen.getByRole('status').textContent).toBe(
      `${ready} ${calculationEvidenceContent.status.ready}`,
    )
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveAttribute('aria-atomic', 'false')
    expect(getObservedAllowedRequests()).toContain(
      `GET /api/dashboard/evidence?planId=${EVIDENCE_IDS.plan}&pointerVersion=1&calculationId=${EVIDENCE_IDS.calculation}`,
    )
  })

  it('조정 이력: 첫 로그인 세션의 대시보드에서도 같은 패널에서 펼치고 이력 문장은 단일 상태 리전의 세 번째 요소다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )
    const result = evidenceResultFixture({ flowCount: 1 })
    stubDashboard(() => Promise.resolve(jsonResponse(dashboardFixture(result))))
    stubCalculationEvidence(() => Promise.resolve(jsonResponse(calculationEvidenceFixture(result))))
    stubAdjustmentHistory(() =>
      Promise.resolve(
        jsonResponse(
          adjustmentPageFixture(result, {
            rows: [adjustmentRowFixture(1, EVIDENCE_IDS.calculation)],
          }),
        ),
      ),
    )
    render(<OnboardingScreen />)
    await screen.findAllByRole('article')
    const ready = planResultContent.status.ready(
      formatSeoulTime(BASIS_AT, planResultContent.timeUnavailable),
    )

    // The toggle is on the panel, beside the evidence's.
    const panel = screen.getByRole('region', { name: planResultContent.heading })
    expect(panel).toContainElement(
      screen.getByRole('button', { name: adjustmentHistoryContent.open }),
    )
    fireEvent.click(screen.getByRole('button', { name: calculationEvidenceContent.open }))
    await screen.findByRole('group', { name: calculationEvidenceContent.sections.versions })
    fireEvent.click(screen.getByRole('button', { name: adjustmentHistoryContent.open }))
    expect(
      screen.getByRole('heading', { level: 4, name: adjustmentHistoryContent.heading }),
    ).toHaveFocus()
    await screen.findByRole('list', { name: adjustmentHistoryContent.list })

    // Three parts of the one status region: the dashboard's, the evidence's, then the history's.
    const status = screen.getByRole('status')
    await waitFor(() => {
      expect(status.textContent).toBe(
        `${ready} ${calculationEvidenceContent.status.ready} ${adjustmentHistoryContent.status.ready(1, 1)}`,
      )
    })
    // eslint-disable-next-line testing-library/no-node-access -- 상태 리전 안의 문장 요소를 순서대로 읽는다.
    expect([...status.querySelectorAll('span')].map((part) => part.textContent)).toEqual([
      ready,
      calculationEvidenceContent.status.ready,
      adjustmentHistoryContent.status.ready(1, 1),
    ])
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(status).toHaveAttribute('aria-atomic', 'false')
    expect(getObservedAllowedRequests()).toContain(
      `GET /api/dashboard/adjustments?planId=${EVIDENCE_IDS.plan}&pointerVersion=1&calculationId=${EVIDENCE_IDS.calculation}&direction=ALL`,
    )
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('설정 화면으로 가는 길을 준다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

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

    render(<OnboardingScreen />)

    // 확인 전에는 이 화면이 로그인 상태를 주장하지 않는다. 갈 곳도 아직 없다.
    expect(screen.queryByRole('link', { name: authContent.settings.link })).not.toBeInTheDocument()
  })

  it('UX-DR23: `복리 계획 비교` 제목과 비추천 고지를 인접 배치한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    const title = screen.getByRole('heading', { level: 1 })

    expect(title.textContent).toBe('복리 계획 비교')
    expect(title.nextElementSibling?.textContent).toBe('투자성과·계좌 수익률·투자 추천이 아닙니다.')
  })

  it('미인증이면 인증 화면으로 되돌리고 사용자 데이터를 표시하지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: false, destination: 'entry' })),
    )

    render(<OnboardingScreen />)

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    // 세 버튼을 함께 본다. 하나만 보면 나머지 제공자의 버튼이 사라져도 이 시험이 통과한다
    // (`expectAuthScreenMounted`·`App.test.tsx`·`EntryScreen.test.tsx`가 쓰는 것과 같은 형태다).
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.getByRole('link', { name: authContent.signIn.labels[provider] }),
      ).toBeInTheDocument()
    }
    expect(screen.queryByText(authContent.onboarding.heading)).not.toBeInTheDocument()
  })

  it('세션 확인이 실패하면 인증 화면으로 되돌린다', async () => {
    // 5xx·네트워크 오류·응답 유실이 모두 같은 결과로 수렴한다. 자동 재시도하지 않는다.
    stubSessionRequest(() => Promise.resolve(jsonResponse({}, 500)))

    render(<OnboardingScreen />)

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    expect(getObservedSessionRequests()).toHaveLength(1)
  })

  it('세션 확인은 정확히 한 번만 요청한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    expect(getObservedSessionRequests()).toEqual(['/api/auth/session'])
  })

  it('세션을 확인해도 브라우저 저장소 항목이 생기지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    // 인증 토큰과 금융 원본을 브라우저 영속 저장소에 두지 않는다. 세션은 서버에만 있다.
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('서버가 다른 목적지를 고르면 그 목적지로 보내고 사용자 데이터를 그리지 않는다', async () => {
    // 이미 온보딩을 지난 계정이 이 경로를 직접 열면 서버는 `dashboard`를 답한다. 그 값을 버리면
    // 화면은 온보딩을 다시 그리고 AC-4의 "기존 사용자는 원래 목적지"가 성립하지 않는다.
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'dashboard' })),
    )

    render(<OnboardingScreen />)

    expect(await screen.findByText(authContent.session.redirecting)).toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith('/dashboard')
    expect(screen.queryByText(authContent.onboarding.heading)).not.toBeInTheDocument()
    for (const item of authContent.onboarding.items) {
      expect(screen.queryByText(item)).not.toBeInTheDocument()
    }
  })

  it('기대한 목적지에서는 내비게이션을 부르지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('뒤로 가기 복원에서 세션이 사라졌으면 민감 화면을 계속 보여 주지 않는다', async () => {
    let signedIn = true
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse(
          signedIn
            ? { authenticated: true, destination: 'onboarding' }
            : { authenticated: false, destination: 'entry' },
        ),
      ),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    /*
     * UX-DR22: "로그아웃·세션 만료·삭제 후 뒤로 가기 복원으로 민감 화면이 노출되지 않아야 한다."
     * bfcache에서 되살아난 문서는 마운트를 다시 하지 않으므로 세션 확인이 다시 돌지 않고, 화면은
     * 저장된 그대로 인증된 상태를 계속 주장한다. `persisted`가 참인 `pageshow`가 그 유일한 신호다.
     */
    signedIn = false
    act(() => {
      window.dispatchEvent(pageShow(true))
    })

    /*
     * 만료의 결과는 이동 하나다. Story 1.5가 그 구분을 들였다 — 인증을 한 번 관측한 뒤의 미인증은
     * 만료이고, 첫 확인부터 미인증인 냉시작과 사용자가 알아야 할 사실이 다르다. 서버 응답은 두
     * 경우에 같으므로 이 전이가 유일한 재료다.
     *
     * 만료 문장을 여기서 단정하지 않는다. 그 문장의 출처는 이동한 주소의 표지(`noticeKindOf`)
     * 하나이며 `EntryScreen.test.tsx`가 그 자리를 덮는다. 제자리 렌더와 이동을 함께 내면 실제
     * 브라우저에서 화면이 두 번 바뀌고 URL이 착지하기 전에 인증 화면 경로로 읽힌다.
     */
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith('/?auth=expired')
    })
    // 이동이 착지하기 전에 아무 상태도 주장하지 않는다 — 만료 문장도, 이전 화면의 본문도 없다.
    expect(screen.queryByText(authContent.notices.expired)).not.toBeInTheDocument()
    expect(screen.queryByText(authContent.notices.signedOut)).not.toBeInTheDocument()
    expect(screen.queryByText(authContent.onboarding.heading)).not.toBeInTheDocument()
    expect(getObservedSessionRequests()).toHaveLength(2)
  })

  it('냉시작 미인증은 만료가 아니라 로그인 상태 아님으로 안내한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: false, destination: 'entry' })),
    )

    render(<OnboardingScreen />)

    // 첫 확인부터 미인증이다. 인증을 관측한 적이 없으므로 만료라고 말할 근거가 없다.
    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    expect(screen.queryByText(authContent.notices.expired)).not.toBeInTheDocument()
    // 그 자리에서 인증 화면을 렌더한다. 최상위 내비게이션을 부르지 않는다.
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('로그아웃 버튼이 세션이 확인된 뒤에만 나타난다', async () => {
    stubSessionRequest(pendingResponse)

    const { unmount } = render(<OnboardingScreen />)

    // 확인 중에는 로그아웃할 대상이 아직 없다.
    expect(
      screen.queryByRole('button', { name: authContent.signOut.label }),
    ).not.toBeInTheDocument()
    unmount()

    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: true, destination: 'onboarding', csrfToken: 'token-a' }),
      ),
    )
    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    // 온보딩에 버튼이 없으면 방금 가입한 사용자가 이 화면에서 나갈 방법이 없다.
    expect(screen.getByRole('button', { name: authContent.signOut.label })).toBeInTheDocument()
  })

  it('로그아웃을 실행하면 표지를 달고 진입 화면으로 이동한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: true, destination: 'onboarding', csrfToken: 'token-a' }),
      ),
    )
    stubLogoutRequest(() => Promise.resolve(new Response(null, { status: 204 })))

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=loggedOut')
    })
    // 세션 확인과 로그아웃 두 요청뿐이다. 이 화면은 그 밖의 요청을 보내지 않는다.
    expect(getObservedAllowedRequests()).toEqual(['GET /api/auth/session', 'POST /api/auth/logout'])
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('접근성: 건너뛰기 링크가 로그아웃보다 먼저 도달한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: true, destination: 'onboarding', csrfToken: 'token-a' }),
      ),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    const skipLink = screen.getByRole('link', { name: entryContent.skipLink })
    const signOut = screen.getByRole('button', { name: authContent.signOut.label })

    // 반복되는 머리말을 지나치려는 사용자가 먼저 만나는 것은 건너뛰기 링크여야 한다.
    expect(skipLink.compareDocumentPosition(signOut)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })

  it('탭이 다시 보이게 되면 세션을 다시 확인하고 만료를 안내한다', async () => {
    let signedIn = true
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse(
          signedIn
            ? { authenticated: true, destination: 'onboarding', csrfToken: 'token-a' }
            : { authenticated: false, destination: 'entry' },
        ),
      ),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    /*
     * 만료 감지의 실제 생산자다. 인증된 화면을 열어 둔 탭은 다시 마운트되지 않으므로 첫 확인의
     * 답을 계속 주장하고, bfcache 재확인은 이 스토리가 진입 문서에 붙인 `no-store` 때문에
     * Chromium에서 오지 않는다. 사용자가 다른 탭을 보다 돌아오는 순간이 그 사이에 세션이 끝났을
     * 수 있는 유일한 관측 지점이다.
     */
    signedIn = false
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith('/?auth=expired')
    })
    expect(getObservedSessionRequests()).toHaveLength(2)
    // 이동만 남는다. 만료 문장은 이동한 주소의 표지가 만든다.
    expect(screen.queryByText(authContent.notices.expired)).not.toBeInTheDocument()
    // 폴링이 아니다. 가시성이 바뀔 때 한 번이고, 진입 화면으로 수렴한 뒤에는 다시 듣지 않는다.
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(getObservedSessionRequests()).toHaveLength(2)
  })

  it('요청이 실패하면 세션이 만료됐다고 말하지 않는다', async () => {
    let reachable = true
    stubSessionRequest(() =>
      reachable
        ? Promise.resolve(
            jsonResponse({ authenticated: true, destination: 'onboarding', csrfToken: 'token-a' }),
          )
        : Promise.reject(new Error('네트워크 단절')),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    /*
     * 재확인이 실패한다. 세션의 사실이 아니라 이 브라우저의 사정이므로 만료로 판정하지 않는다 —
     * 판정하면 일시적 오프라인 하나가 멀쩡한 세션의 사용자를 인증 화면으로 밀어내고 "만료됐다"고
     * 말한다. 서버가 미인증이라고 답했을 때만 만료다.
     */
    reachable = false
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    expect(screen.queryByText(authContent.notices.expired)).not.toBeInTheDocument()
    // 만료가 아니므로 최상위 내비게이션도 부르지 않는다. 그 화면 안에서 표시로 끝난다.
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('bfcache 복원이 아닌 `pageshow`는 세션을 다시 확인하지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    // 첫 로드도 `pageshow`를 낸다. 그때 다시 확인하면 마운트마다 요청이 두 번이 된다.
    act(() => {
      window.dispatchEvent(pageShow(false))
    })

    expect(getObservedSessionRequests()).toHaveLength(1)
  })

  it('응답이 오지 않으면 시한이 지난 뒤 인증 화면으로 되돌린다', async () => {
    vi.useFakeTimers()
    try {
      stubSessionRequest(pendingResponse)

      render(<OnboardingScreen />)
      expect(screen.getByRole('status').textContent).toBe(authContent.session.checking)

      // 클라이언트 시한이 없으면 진입점의 읽기 timeout(30초)까지 "확인 중"에 머문다.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000)
      })

      expect(screen.getByText(authContent.notices.signedOut)).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('접근성: 건너뛰기 링크가 본문 랜드마크를 가리킨다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    const skipLink = screen.getByRole('link', { name: '본문으로 건너뛰기' })
    const main = screen.getByRole('main')

    expect(skipLink).toHaveAttribute('href', '#onboarding-main')
    expect(main).toHaveAttribute('id', 'onboarding-main')
  })

  it('접근성: polite live region이 화면당 하나다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
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

    render(<OnboardingScreen />)

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
    for (const item of authContent.onboarding.items) {
      expect(screen.queryByText(item)).not.toBeInTheDocument()
    }
  })

  it('재인증: live region이 하나로 남고 표시된 세션은 갱신 중이 아니다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )

    render(<OnboardingScreen />)
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

    render(<OnboardingScreen />)
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

    render(<OnboardingScreen />)
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

    render(<OnboardingScreen />)
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

    render(<OnboardingScreen />)
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
          ? jsonResponse({ authenticated: true, destination: 'onboarding' })
          : jsonResponse({
              authenticated: false,
              csrfToken: 'reauth-token',
              reauthRequired: true,
            }),
      )
    })

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

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

    render(<OnboardingScreen />)
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

    render(<OnboardingScreen />)
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
      navigateToOnboardingScreen(`?auth=${kind}`)
      stubSessionRequest(() =>
        Promise.resolve(
          jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
        ),
      )

      const { unmount } = render(<OnboardingScreen />)
      await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })

      // 서버가 붙들어 둔 목적지에 달아 보낸 표지다. 화면이 그것을 패널로 옮기지 않으면
      // 사용자는 왜 돌아왔는지 알 수 없다.
      expect(screen.getByText(authContent.notices[kind])).toBeInTheDocument()
      unmount()
    }
  })

  it('재인증: 재인증과 무관한 표지는 패널에 나타나지 않는다', async () => {
    navigateToOnboardingScreen('?auth=loggedOut')
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading })

    /*
     * "로그아웃했습니다 … 다시 로그인해 주세요"는 아직 로그인해 있는 사용자에게 거짓이다.
     * 주소는 누구나 적을 수 있으므로 거름은 화면의 일이고, 타입이 그 거름을 강제한다.
     */
    expect(screen.queryByText(authContent.notices.loggedOut)).not.toBeInTheDocument()
  })
})
