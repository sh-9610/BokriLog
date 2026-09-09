import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'

import { App } from './App'
import {
  authContent,
  authorizePath,
  DASHBOARD_PATH,
  ONBOARDING_PATH,
  SIGN_IN_PROVIDERS,
} from './content/auth'
import { entryContent } from './content/entry'
import { getObservedRequests, stubSessionRequest } from './test/setup'

/**
 * 경로 → 화면 대응.
 *
 * 라우터 의존이 없으므로 이 대응은 `App`의 닫힌 맵 하나다. 그 맵이 Nginx 허용 목록과 같은 집합인지가
 * 계약이고, 목록이 갈라지면 200을 받은 문서가 엉뚱한 화면을 그린다.
 */
function navigate(path: string, search = ''): void {
  window.history.pushState({}, '', path + search)
}

function jsonResponse(body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  navigate('/')
})

describe('App 경로 맵', () => {
  it('루트는 인증 화면을 마운트하고 어떤 요청도 보내지 않는다', () => {
    navigate('/')

    render(<App />)

    expect(
      screen.getByRole('heading', { level: 1, name: entryContent.pageTitle }),
    ).toBeInTheDocument()
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.getByRole('link', { name: authContent.signIn.labels[provider] }),
      ).toBeInTheDocument()
    }
    // 목적지는 서버가 302로 정한다. 진입 화면이 세션을 물어볼 이유가 없다.
    expect(getObservedRequests()).toEqual([])
  })

  it('루트의 실패 표지를 인증 화면 안내로 옮긴다', () => {
    navigate('/', '?auth=cancelled')

    render(<App />)

    expect(screen.getByRole('status').textContent).toBe(authContent.notices.cancelled)
  })

  it('알 수 없는 표지는 무시한다', () => {
    navigate('/', '?auth=leaked-reason')

    render(<App />)

    // 표지는 서버가 정한 닫힌 집합뿐이다. 그 밖의 값으로 화면에 문장을 만들지 않는다.
    expect(screen.getByRole('status').textContent).toBe('')
  })

  it('온보딩 경로는 온보딩 화면을 마운트한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )
    navigate(ONBOARDING_PATH)

    render(<App />)

    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading }),
    ).toBeInTheDocument()
  })

  it('대시보드 경로는 로그인 상태 화면을 마운트한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'dashboard' })),
    )
    navigate(DASHBOARD_PATH)

    render(<App />)

    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.dashboard.heading }),
    ).toBeInTheDocument()
  })

  it('미인증으로 되돌려진 화면은 원래 목적지를 `next`로 실어 보낸다', async () => {
    /*
     * 서버는 복귀 목적지를 닫힌 허용 목록으로 판정하고 "기존 사용자는 유효한 원래 목적지"로
     * 보낸다. 그 목적지를 알려 주는 것은 `next` 하나이고, 화면에 그 생산자가 없으면 서버의 그
     * 분기는 제품 코드로 도달할 수 없다.
     */
    for (const path of [ONBOARDING_PATH, DASHBOARD_PATH]) {
      stubSessionRequest(() =>
        Promise.resolve(jsonResponse({ authenticated: false, destination: 'entry' })),
      )
      navigate(path)

      const { unmount } = render(<App />)

      // 세 제공자 모두 같은 `next`를 싣는다. 생산자는 `authorizeHref` 하나다.
      await screen.findByRole('link', { name: authContent.signIn.labels.google })
      for (const provider of SIGN_IN_PROVIDERS) {
        expect(
          screen.getByRole('link', { name: authContent.signIn.labels[provider] }),
        ).toHaveAttribute('href', `${authorizePath(provider)}?next=${encodeURIComponent(path)}`)
      }
      unmount()
    }
  })

  it('진입 화면에서는 `next`를 붙이지 않는다', () => {
    navigate('/')

    render(<App />)

    // 목적지는 서버가 신규·기존으로 갈라 정한다. 허용 목록에 없는 경로도 여기서 걸러진다.
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.getByRole('link', { name: authContent.signIn.labels[provider] }),
      ).toHaveAttribute('href', authorizePath(provider))
    }
    // 경로 리터럴을 한 번 고정한다. 서버의 registration id와 갈라지면 인증 시작이 404가 된다.
    expect(authorizePath('google')).toBe('/api/auth/authorize/google')
    expect(authorizePath('naver')).toBe('/api/auth/authorize/naver')
    expect(authorizePath('kakao')).toBe('/api/auth/authorize/kakao')
  })

  it('로그아웃·만료·로그아웃 실패 표지를 인증 화면 안내로 옮긴다', () => {
    /*
     * 두 표지는 서버가 아니라 클라이언트가 만든다(`signOut`·`useSessionGate`). `noticeKindOf`의
     * 닫힌 집합에 없으면 그 값이 조용히 무시되고, 로그아웃한 사용자는 아무 안내도 없는 진입
     * 화면을 본다.
     */
    for (const kind of ['loggedOut', 'expired', 'logoutFailed'] as const) {
      navigate('/', `?auth=${kind}`)

      const { unmount } = render(<App />)

      expect(screen.getByRole('status').textContent).toBe(authContent.notices[kind])
      unmount()
    }
  })

  it('`signedOut`은 URL 표지로 만들 수 없다', () => {
    /*
     * 그 표지는 세션 확인 결과로 정해진다. 주소창에 적어 화면 문장을 만드는 경로를 열지 않는다 —
     * 열면 링크 하나로 "로그인 상태가 아닙니다"를 임의의 화면에 띄울 수 있다.
     */
    navigate('/', '?auth=signedOut')

    render(<App />)

    expect(screen.getByRole('status').textContent).toBe('')
  })

  it('맵에 없는 경로는 인증 화면으로 떨어진다', () => {
    // Nginx 허용 목록이 이 경로에 문서를 주지 않으므로 실제로는 도달하지 않는다.
    // 그래도 화면이 로그인 상태를 주장하지 않는 편이 안전한 기본값이다.
    navigate('/unknown')

    render(<App />)

    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.getByRole('link', { name: authContent.signIn.labels[provider] }),
      ).toBeInTheDocument()
    }
    expect(getObservedRequests()).toEqual([])
  })
})
