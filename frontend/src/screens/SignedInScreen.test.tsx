import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { authContent, CSRF_HEADER, dashboardCopyStrings } from '../content/auth'
import { navigateTo, replaceLocation } from '../session/navigation'
import {
  getObservedAllowedRequests,
  getObservedSessionRequests,
  getObservedStorageEntries,
  stubLogoutRequest,
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

  it('UX-DR23: `복리 계획 비교` 제목과 비추천 고지를 인접 배치한다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    const title = screen.getByRole('heading', { level: 1 })

    expect(title.textContent).toBe('복리 계획 비교')
    expect(title.nextElementSibling?.textContent).toBe('투자성과·계좌 수익률·투자 추천이 아닙니다.')
  })

  it('금융값을 담지 않는다', async () => {
    stubSessionRequest(authenticated)

    render(<SignedInScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading })

    const rendered = document.body.textContent

    // 지표·수익률·통화 표기는 이 스토리의 범위가 아니다. 빈 카드로 위장하지도 않는다.
    expect(rendered).not.toMatch(/\d+(\.\d+)?\s*%/)
    expect(rendered).not.toContain('USDT')
    expect(rendered).not.toContain('$')
  })

  it('미인증으로 이 경로에 직접 들어오면 인증 화면으로 되돌린다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: false, destination: 'entry' })),
    )

    render(<SignedInScreen />)

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: authContent.signIn.googleLabel })).toBeInTheDocument()
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
})
