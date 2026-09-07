import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { authContent, dashboardCopyStrings } from '../content/auth'
import { replaceLocation } from '../session/navigation'
import {
  getObservedSessionRequests,
  getObservedStorageEntries,
  stubSessionRequest,
} from '../test/setup'
import { SignedInScreen } from './SignedInScreen'

/*
 * 최상위 내비게이션을 가로챈다. jsdom은 `location.replace`를 수행하지도, 관측하게 하지도 않는다.
 * 그 전환을 소유한 모듈 하나를 대신 세운다(`OnboardingScreen.test.tsx`와 같은 관용).
 */
vi.mock('../session/navigation', () => ({ replaceLocation: vi.fn() }))

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
  return Promise.resolve(jsonResponse({ authenticated: true, destination: 'dashboard' }))
}

/*
 * `restoreMocks`는 `vi.spyOn`이 만든 spy만 원래대로 돌린다. `vi.mock` 팩토리의 `vi.fn()`은 호출
 * 기록을 그대로 들고 다음 시험으로 넘어가므로 "부르지 않았다"를 단정할 수 없다. 매 시험 앞에서
 * 직접 비운다.
 */
beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
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
})
