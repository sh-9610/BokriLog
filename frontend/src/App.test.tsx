import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { App } from './App'
import { AD_TRANSITION_PATH } from './content/adTransition'
import {
  authContent,
  authorizePath,
  DASHBOARD_PATH,
  linkNoticeKindOf,
  ONBOARDING_PATH,
  OPERATOR_PATH,
  reauthNoticeKindOf,
  SETTINGS_PATH,
  SIGN_IN_PROVIDERS,
} from './content/auth'
import { entryContent } from './content/entry'
import { operatorContent } from './content/operator'
import { planConditionContent } from './content/planConditions'
import * as navigation from './session/navigation'
import { FIRST_MOUNT_TIMEOUT_MS } from './test/firstMount'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  stubLinkStatusRequest,
  stubOperatorAudit,
  stubOperatorRestrictions,
  stubPlanConditionState,
  stubSessionRequest,
} from './test/setup'

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

  it('설정 경로는 설정 화면을 마운트한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'settings' })),
    )
    stubLinkStatusRequest(() => Promise.resolve(jsonResponse({ providers: ['google'] })))
    navigate(SETTINGS_PATH)

    render(<App />)

    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.settings.heading }),
    ).toBeInTheDocument()
  })

  it('계획 조건 경로는 세션과 조건 상태를 확인한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: true, destination: 'settings', csrfToken: 'csrf' }),
      ),
    )
    stubPlanConditionState(() => Promise.resolve(jsonResponse({ eligible: false })))
    navigate(planConditionContent.path)

    render(<App />)

    expect(
      await screen.findByRole('heading', { level: 2, name: planConditionContent.heading }),
    ).toBeInTheDocument()
    expect(await screen.findByText(planConditionContent.blocked)).toBeInTheDocument()
  })

  it('이전 광고 주소는 소비 요청 없이 대시보드로 복귀한다', () => {
    const replace = vi.spyOn(navigation, 'replaceLocation').mockImplementation(() => undefined)
    navigate(AD_TRANSITION_PATH, '?t=legacy')
    render(<App />)
    expect(replace).toHaveBeenCalledWith(DASHBOARD_PATH)
    expect(getObservedAllowedRequests()).toEqual([])
    expect(getObservedRequests()).toEqual([])
    replace.mockRestore()
  })

  it('미인증으로 되돌려진 화면은 원래 목적지를 `next`로 실어 보낸다', async () => {
    /*
     * 서버는 복귀 목적지를 닫힌 허용 목록으로 판정하고 "기존 사용자는 유효한 원래 목적지"로
     * 보낸다. 그 목적지를 알려 주는 것은 `next` 하나이고, 화면에 그 생산자가 없으면 서버의 그
     * 분기는 제품 코드로 도달할 수 없다.
     */
    for (const path of [ONBOARDING_PATH, DASHBOARD_PATH, SETTINGS_PATH]) {
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

  it('재인증·연결 표지는 인증 화면에서 문장이 되지 않는다', () => {
    /*
     * 다섯 값 모두 로그인한 사용자의 화면으로 오는 표지다(`AuthFlowHandlers`가 붙들어 둔
     * 목적지나 설정 화면으로 보낸다). 진입 화면에서 렌더되면 거짓을 말한다 —
     * `/?auth=linkFailed`는 본인 확인도 연결도 없는 화면에서 "아래에서 본인 확인을 마친 뒤 다시
     * 연결해 주세요"를 보여 준다. `noticeKindOf`의 거름망이 그 값을 걸러 낸다.
     */
    for (const kind of [
      'reauthCancelled',
      'reauthFailed',
      'linkCancelled',
      'linkFailed',
      'linkSucceeded',
    ] as const) {
      navigate('/', `?auth=${kind}`)

      const { unmount } = render(<App />)

      expect(screen.getByRole('status').textContent).toBe('')
      expect(document.body.textContent).not.toContain(authContent.notices[kind])
      unmount()
    }
  })

  it('그래도 다섯 값은 URL 표지의 닫힌 집합에 든다', () => {
    /*
     * 거름망과 집합은 다른 일을 한다. 집합에서 빠지면 그 값이 어느 화면에서도 조용히 무시되고
     * 재인증·연결 실패가 아무 설명 없는 화면으로 끝난다. 화면별 거름망
     * (`reauthNoticeKindOf`·`linkNoticeKindOf`)이 그 집합을 읽으므로, 두 함수가 값을 돌려주는
     * 것이 곧 집합에 들어 있다는 사실이다.
     */
    for (const kind of ['reauthCancelled', 'reauthFailed'] as const) {
      expect(reauthNoticeKindOf(`?auth=${kind}`)).toBe(kind)
    }
    for (const kind of ['linkCancelled', 'linkFailed', 'linkSucceeded'] as const) {
      expect(linkNoticeKindOf(`?auth=${kind}`)).toBe(kind)
    }
    // 거름망은 서로의 값을 받지 않는다. 화면마다 보여 줄 수 있는 표지가 그만큼 좁다.
    expect(reauthNoticeKindOf('?auth=linkFailed')).toBeNull()
    expect(linkNoticeKindOf('?auth=reauthFailed')).toBeNull()
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

  it('운영자 경로는 따로 받는 운영자 화면을 마운트한다', async () => {
    navigate(OPERATOR_PATH)
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: true, destination: 'operator', csrfToken: 'operator-csrf' }),
      ),
    )
    stubOperatorAudit(() =>
      Promise.resolve(
        new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'x', correlationId: 'c' }), {
          status: 404,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )
    stubOperatorRestrictions(() =>
      Promise.resolve(
        new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'x', correlationId: 'c' }), {
          status: 404,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )

    render(<App />)

    expect(
      await screen.findByRole(
        'heading',
        { level: 1, name: operatorContent.notFound.heading },
        { timeout: FIRST_MOUNT_TIMEOUT_MS },
      ),
    ).toBeInTheDocument()
    expect(getObservedRequests()).toEqual([])
  })

  it('미인증으로 운영자 경로에 오면 `next`로 그 경로를 실어 보낸다', async () => {
    navigate(OPERATOR_PATH)
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: false, destination: 'entry' })),
    )

    render(<App />)

    const link = await screen.findByRole(
      'link',
      { name: authContent.signIn.labels.google },
      { timeout: FIRST_MOUNT_TIMEOUT_MS },
    )
    expect(link).toHaveAttribute('href', `${authorizePath('google')}?next=%2Foperator`)
  })
})
