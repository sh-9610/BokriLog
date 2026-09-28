import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

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
import { firstSnapshotContent } from '../content/firstSnapshot'
import { planConditionContent } from '../content/planConditions'
import { METRIC_ORDER, PLAN_RESULT_PATH, planResultContent } from '../content/planResult'
import { navigateTo, replaceLocation } from '../session/navigation'
import { planResultFixture } from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedSessionRequests,
  getObservedStorageEntries,
  stubFirstSnapshotStart,
  stubFirstSnapshotState,
  stubLogoutRequest,
  stubPlanResult,
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
    // 활성 계획이 없다고 서버가 답한 경로다. 그때만 "계획 없음" 문장과 임시 세 축이 보인다.
    stubPlanResult(() => Promise.resolve(new Response(null, { status: 204 })))
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

  it('공식 결과가 없으면 금융값을 담지 않는다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    const rendered = document.body.textContent

    // 결과 응답이 없는 경로다(Story 2.7 이후에도). 지표·비율·통화 표기가 없고 빈 카드로 위장하지도 않는다.
    expect(rendered).not.toMatch(/\d+(\.\d+)?\s*%/)
    expect(rendered).not.toContain('USDT')
    expect(rendered).not.toContain('$')
    expect(
      screen.queryByRole('heading', { name: planResultContent.heading }),
    ).not.toBeInTheDocument()
  })

  /*
   * Story 2.7: 활성 계획의 공식 결과. 결과 응답 하나에서 패널 전체를 그린다.
   */

  it('결과: 공식 포인터 응답 하나에서 여섯 지표와 세 축을 그리고 단일 상태 리전이 한 번 알린다', async () => {
    stubSessionRequest(authenticated)
    stubPlanResult(() => Promise.resolve(jsonResponse(planResultFixture())))
    stubFirstSnapshotState(() =>
      Promise.resolve(
        jsonResponse({
          connected: true,
          restricted: false,
          job: { jobId: 'job-1', status: 'SUCCEEDED' },
          decimal: '1250',
          currency: 'USD',
          measuredAt: '2026-09-27T00:59:59Z',
        }),
      ),
    )

    render(<SignedInScreen />)

    expect(
      await screen.findByRole('heading', { level: 2, name: planResultContent.heading }),
    ).toBeInTheDocument()
    for (const metric of METRIC_ORDER) {
      expect(
        screen.getByRole('heading', { level: 4, name: planResultContent.metrics[metric].name }),
      ).toBeInTheDocument()
    }
    const statuses = screen.getAllByRole('status')
    expect(statuses).toHaveLength(1)
    expect(statuses[0]).toHaveTextContent('복리 계획 결과를 표시합니다')
    expect(getObservedAllowedRequests()).toContain(`GET ${PLAN_RESULT_PATH}`)
    // The pointer now shows the plan's current value: the panel no longer calls it the first value,
    // no longer claims there is no plan, and its provisional axes give way.
    expect(await screen.findByText(firstSnapshotContent.currentSucceeded)).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 2, name: firstSnapshotContent.currentHeading }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: firstSnapshotContent.heading }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText(firstSnapshotContent.succeeded)).not.toBeInTheDocument()
    expect(screen.getAllByText(/1,250\.00 USD/)).toHaveLength(2)
    expect(screen.queryByText(firstSnapshotContent.noPlan)).not.toBeInTheDocument()
    expect(screen.queryByText(/계산 성격 · 계산 불가/)).not.toBeInTheDocument()
    // The title and its disclaimer stay adjacent above the result.
    const title = screen.getByRole('heading', { level: 1 })
    expect(title.nextElementSibling?.textContent).toBe(authContent.disclaimer)
  })

  it('결과: 없음(204)·거절(403)·읽기 실패는 기존 화면을 바꾸지 않는다', async () => {
    for (const answer of [
      () => Promise.resolve(new Response(null, { status: 204 })),
      () => Promise.resolve(new Response('{}', { status: 403 })),
      () => Promise.reject(new Error('network')),
      () => Promise.resolve(jsonResponse({ planId: 'partial' })),
    ]) {
      stubSessionRequest(authenticated)
      stubPlanResult(answer)
      const { unmount } = render(<SignedInScreen />)
      await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })
      await waitFor(() => {
        expect(getObservedAllowedRequests()).toContain(`GET ${PLAN_RESULT_PATH}`)
      })
      expect(
        screen.queryByRole('heading', { name: planResultContent.heading }),
      ).not.toBeInTheDocument()
      expect(screen.getAllByRole('status')).toHaveLength(1)
      expect(screen.getByRole('status').textContent).toBe('')
      unmount()
    }
  })

  /*
   * 거절·읽기 실패는 "계획 없음"이 아니다. 서버가 없다고 답했을 때(204)만 그 문장과 계획 조건
   * 링크를 보이고, 그 밖에는 계획이 있다고도 없다고도 말하지 않는다.
   */
  it.each([
    ['refused (403)', () => Promise.resolve(new Response('{}', { status: 403 }))],
    ['a failed read', () => Promise.reject(new Error('network'))],
    ['a malformed body', () => Promise.resolve(jsonResponse({ planId: 'partial' }))],
  ])('결과: %s는 계획 없음으로 말하지 않고 중립 문장을 보인다', async (_name, answer) => {
    stubSessionRequest(authenticated)
    stubPlanResult(answer)
    stubFirstSnapshotState(() =>
      Promise.resolve(
        jsonResponse({
          connected: true,
          restricted: false,
          job: { jobId: 'job-1', status: 'SUCCEEDED' },
          decimal: '1234.56',
          currency: 'USD',
          measuredAt: '2026-09-25T00:00:00Z',
        }),
      ),
    )
    render(<SignedInScreen />)

    expect(await screen.findByText(firstSnapshotContent.planUnknown)).toBeInTheDocument()
    expect(screen.queryByText(firstSnapshotContent.noPlan)).not.toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: planConditionContent.openConditions }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText(/계산 성격 · 계산 불가/)).not.toBeInTheDocument()
  })

  it('결과: 서버가 활성 계획이 없다고 답하면(204) 계획 없음 문장과 계획 조건 링크를 보인다', async () => {
    stubSessionRequest(authenticated)
    stubPlanResult(() => Promise.resolve(new Response(null, { status: 204 })))
    stubFirstSnapshotState(() =>
      Promise.resolve(
        jsonResponse({
          connected: true,
          restricted: false,
          job: { jobId: 'job-1', status: 'SUCCEEDED' },
          decimal: '1234.56',
          currency: 'USD',
          measuredAt: '2026-09-25T00:00:00Z',
        }),
      ),
    )
    render(<SignedInScreen />)

    expect(await screen.findByText(firstSnapshotContent.noPlan)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: planConditionContent.openConditions })).toHaveAttribute(
      'href',
      planConditionContent.path,
    )
    expect(screen.getByText(firstSnapshotContent.succeeded)).toBeInTheDocument()
    expect(screen.queryByText(firstSnapshotContent.planUnknown)).not.toBeInTheDocument()
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
