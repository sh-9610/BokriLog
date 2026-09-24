import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  authContent,
  authorizePath,
  CSRF_HEADER,
  linkPath,
  settingsCopyStrings,
  SIGN_IN_PROVIDERS,
} from '../content/auth'
import { navigateTo, replaceLocation } from '../session/navigation'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubLinkRequest,
  stubLinkStatusRequest,
  stubLogoutRequest,
  stubReauthenticationRequest,
  stubSessionRequest,
} from '../test/setup'
import { SettingsScreen } from './SettingsScreen'

/*
 * 최상위 내비게이션을 가로챈다. jsdom은 `location.replace`·`location.assign`을 수행하지도,
 * 관측하게 하지도 않는다. 그 전환을 소유한 모듈 하나를 대신 세운다(두 인증 화면과 같은 관용).
 */
vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

/** 이 화면이 세션 확인에서 받는 토큰. 연결 시작이 이 값을 헤더로 되돌려 보낸다. */
const CSRF_TOKEN = 'settings-csrf-token'

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

/** 서버가 이 화면을 목적지로 고른 세션. */
function authenticated(destination = 'settings'): Promise<Response> {
  return Promise.resolve(jsonResponse({ authenticated: true, destination, csrfToken: CSRF_TOKEN }))
}

/** 그 제공자들이 연결된 계정. 응답에 실리는 것은 제공자 이름뿐이다. */
function linkedWith(...providers: string[]): Promise<Response> {
  return Promise.resolve(jsonResponse({ providers }))
}

/** 이 화면의 경로를 그 쿼리와 함께 연다(`App.test.tsx`·두 인증 화면과 같은 관용). */
function navigateToSettings(search = ''): void {
  window.history.pushState({}, '', `/settings${search}`)
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  vi.mocked(navigateTo).mockClear()
  navigateToSettings()
})

describe('SettingsScreen', () => {
  it('확인 중에는 로그인 상태도 연결 상태도 주장하지 않는다', () => {
    stubSessionRequest(pendingResponse)

    render(<SettingsScreen />)

    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-busy', 'true')
    expect(status.textContent).toBe(authContent.session.checking)
    expect(screen.queryByText(authContent.settings.heading)).not.toBeInTheDocument()
    // 게이트가 확정되기 전에는 연결 목록을 묻지도 않는다.
    expect(getObservedAllowedRequests()).toEqual(['GET /api/auth/session'])
  })

  it('세션이 확인되면 설정 카피를 렌더한다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))

    render(<SettingsScreen />)

    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.settings.heading }),
    ).toBeInTheDocument()
    const rendered = document.body.textContent
    for (const copy of settingsCopyStrings) {
      expect(rendered).toContain(copy)
    }
  })

  it('UX-DR23: `복리 계획 비교` 제목과 비추천 고지를 인접 배치한다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    const title = screen.getByRole('heading', { level: 1 })

    expect(title.textContent).toBe('복리 계획 비교')
    expect(title.nextElementSibling?.textContent).toBe('투자성과·계좌 수익률·투자 추천이 아닙니다.')
  })

  it('연결된 수단에는 연결 조작을 주지 않고 미연결 수단에만 준다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    /*
     * 목록이 도착할 때까지 기다린다. 확인 전에는 연결 여부를 주장하지 않으므로 그 사이의 화면에는
     * 연결 상태도 연결 버튼도 없다 — 기다리지 않으면 이 단정이 그 중간 상태를 보고 흔들린다.
     */
    // 연결된 제공자는 상태만 보인다. 해제는 이 스토리의 범위가 아니다(Story 1.11).
    expect(await screen.findByText(authContent.settings.linked)).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: authContent.settings.linkLabels.google }),
    ).not.toBeInTheDocument()
    // 미연결 제공자에만 연결 버튼이 있다.
    for (const provider of ['naver', 'kakao'] as const) {
      expect(
        screen.getByRole('button', { name: authContent.settings.linkLabels[provider] }),
      ).toBeInTheDocument()
    }
    // 세 제공자 이름이 고정 순서로 모두 보인다.
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(screen.getByText(authContent.settings.providerNames[provider])).toBeInTheDocument()
    }
  })

  it('모든 수단이 연결된 계정에는 연결 조작이 하나도 없다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google', 'naver', 'kakao'))

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    expect(await screen.findAllByText(authContent.settings.linked)).toHaveLength(3)
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.queryByRole('button', { name: authContent.settings.linkLabels[provider] }),
      ).not.toBeInTheDocument()
    }
  })

  it('외부 식별자를 화면에 그리지 않는다', async () => {
    stubSessionRequest(() => authenticated())
    /*
     * 서버는 이 값들을 보내지 않는다. 보내더라도 화면이 아는 것은 제공자 이름뿐이라는 사실을
     * 여기서 고정한다 — 응답에 필드가 늘어도 화면이 그것을 그리기 시작하지 않는다.
     */
    stubLinkStatusRequest(() =>
      Promise.resolve(
        jsonResponse({
          providers: ['google'],
          issuer: 'https://accounts.example',
          subject: 'leaked-subject',
        }),
      ),
    )

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })
    await screen.findByText(authContent.settings.linked)

    const rendered = document.body.textContent
    expect(rendered).not.toContain('leaked-subject')
    expect(rendered).not.toContain('accounts.example')
  })

  it('미인증으로 이 경로에 직접 들어오면 인증 화면으로 되돌린다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: false, destination: 'entry' })),
    )

    render(<SettingsScreen />)

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.getByRole('link', { name: authContent.signIn.labels[provider] }),
      ).toBeInTheDocument()
    }
    // 연결 목록을 묻지 않았다. 계정의 사실은 인증된 소유자에게만 간다.
    expect(getObservedAllowedRequests()).toEqual(['GET /api/auth/session'])
  })

  it('서버가 대시보드를 골라도 설정 화면은 밀려나지 않는다', async () => {
    stubSessionRequest(() => authenticated('dashboard'))
    stubLinkStatusRequest(() => linkedWith('google'))

    render(<SettingsScreen />)

    /*
     * 설정 화면은 서버가 고르는 착지 대상이 아니라 사용자가 스스로 여는 자리다. 대시보드라는
     * 이유로 밀려나면 이 경로에 도달할 방법이 없어지고, 연결 왕복이 돌아오는 자리도 사라진다.
     */
    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.settings.heading }),
    ).toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('방금 가입한 계정도 설정 화면을 열 수 있다', async () => {
    stubSessionRequest(() => authenticated('onboarding'))
    stubLinkStatusRequest(() => linkedWith('google'))

    render(<SettingsScreen />)

    /*
     * 첫 로그인의 목적지는 언제나 온보딩이다. 그 값이 설정 화면을 밀어내면 방금 가입한 사용자는
     * 온보딩 화면의 설정 링크를 눌러도 곧바로 되튕기고, 그 세션에서는 자기 로그인 수단을 볼 수
     * 없다 — 미는 일은 착지 화면 둘 사이에서만 일어난다.
     */
    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.settings.heading }),
    ).toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('연결 시작: 토큰 헤더와 동일 출처 쿠키를 함께 보낸다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))
    let observed: RequestInit | undefined
    stubLinkRequest('naver', (init) => {
      observed = init
      return Promise.resolve(new Response(null, { status: 204 }))
    })

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })
    fireEvent.click(
      await screen.findByRole('button', { name: authContent.settings.linkLabels.naver }),
    )

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith(authorizePath('naver'))
    })
    expect(observed?.method).toBe('POST')
    expect(observed?.credentials).toBe('same-origin')
    expect(new Headers(observed?.headers).get(CSRF_HEADER)).toBe(CSRF_TOKEN)
    // 사용자가 고른 제공자 하나만 나갔다.
    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toEqual([
      `POST ${linkPath('naver')}`,
    ])
  })

  it('연결 시작: 거부되면 제공자로 보내지 않고 표지를 단다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))
    stubLinkRequest('naver', () => Promise.resolve(jsonResponse({ code: 'FORBIDDEN' }, 403)))

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })
    fireEvent.click(
      await screen.findByRole('button', { name: authContent.settings.linkLabels.naver }),
    )

    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith('/settings?auth=linkFailed')
    })
    /*
     * 204가 아니면 제공자로 보내지 않는다. 보내면 무장 없는 인가 시작이 되고 사용자는 아무 설명
     * 없이 같은 화면으로 돌아온다.
     */
    expect(vi.mocked(navigateTo)).not.toHaveBeenCalled()
  })

  it('연결 시작: 진행 중 중복 제출을 차단한다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))
    let resolveLink: (response: Response) => void = () => undefined
    stubLinkRequest(
      'naver',
      () =>
        new Promise<Response>((resolve) => {
          resolveLink = resolve
        }),
    )

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    const button = await screen.findByRole('button', {
      name: authContent.settings.linkLabels.naver,
    })
    fireEvent.click(button)

    // 접근 가능한 이름은 동작으로 남고 상태는 live region이 말한다.
    expect(button).toHaveAttribute('aria-disabled', 'true')
    // `disabled`가 아니다. 그 속성은 방금 이 버튼을 실행한 키보드 사용자의 포커스를 빼앗는다.
    expect(button).toBeEnabled()
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status').textContent).toBe(authContent.settings.pending)

    fireEvent.click(button)
    // 다른 제공자도 함께 막힌다. 두 흐름이 겹치면 사용자가 고른 것과 열리는 것이 달라진다.
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.linkLabels.kakao }))

    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toHaveLength(1)
    resolveLink(new Response(null, { status: 204 }))
    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith(authorizePath('naver'))
    })
  })

  it('본인 확인: 세 제공자 버튼이 고정 순서로 있고 시작 호출이 토큰을 싣는다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))
    let observed: RequestInit | undefined
    stubReauthenticationRequest('google', (init) => {
      observed = init
      return Promise.resolve(new Response(null, { status: 204 }))
    })

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    const labels: readonly string[] = SIGN_IN_PROVIDERS.map(
      (provider) => authContent.settings.reauth.labels[provider],
    )
    const rendered = screen
      .getAllByRole('button')
      .map((button) => button.textContent)
      .filter((text) => labels.includes(text))
    // 문서 순서가 곧 Google → 네이버 → 카카오다.
    expect(rendered).toEqual(labels)

    fireEvent.click(screen.getByRole('button', { name: authContent.settings.reauth.labels.google }))

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith(authorizePath('google'))
    })
    expect(new Headers(observed?.headers).get(CSRF_HEADER)).toBe(CSRF_TOKEN)
  })

  it('연결 표지 셋을 문장으로 옮긴다', async () => {
    for (const kind of ['linkCancelled', 'linkFailed', 'linkSucceeded'] as const) {
      navigateToSettings(`?auth=${kind}`)
      stubSessionRequest(() => authenticated())
      stubLinkStatusRequest(() => linkedWith('google'))

      const { unmount } = render(<SettingsScreen />)
      await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

      expect(screen.getByText(authContent.notices[kind])).toBeInTheDocument()
      unmount()
    }
  })

  it('연결과 무관한 표지는 이 화면에서 문장이 되지 않는다', async () => {
    navigateToSettings('?auth=loggedOut')
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    /*
     * `/settings?auth=loggedOut`이 "로그아웃했습니다 … 다시 로그인해 주세요"를 아직 로그인해
     * 있는 사용자에게 보여 주면 그 문장은 거짓이다. 거름망이 그 값을 걸러 낸다.
     */
    expect(screen.queryByText(authContent.notices.loggedOut)).not.toBeInTheDocument()
  })

  it('재인증을 요구받은 세션에서는 패널을 제자리에 렌더한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )

    render(<SettingsScreen />)

    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.reauth.heading }),
    ).toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
    // 연결 목록도 연결 조작도 그 상태에는 없다.
    expect(getObservedAllowedRequests()).toEqual(['GET /api/auth/session'])
    expect(screen.queryByText(authContent.settings.heading)).not.toBeInTheDocument()
  })

  it('로그아웃 버튼이 하나뿐이고 상태 변경을 보낸다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))
    stubLogoutRequest(() => Promise.resolve(new Response(null, { status: 204 })))

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    expect(screen.getAllByRole('button', { name: authContent.signOut.label })).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=loggedOut')
    })
  })

  it('저장소에 아무것도 남기지 않는다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))
    stubLinkRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })
    fireEvent.click(
      await screen.findByRole('button', { name: authContent.settings.linkLabels.naver }),
    )

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith(authorizePath('naver'))
    })
    // 토큰도 연결 상태도 메모리에만 있었다.
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('연결 상태를 확인하지 못하면 목록 대신 다시 확인할 길을 준다', async () => {
    stubSessionRequest(() => authenticated())
    let attempts = 0
    stubLinkStatusRequest(() => {
      attempts += 1
      return attempts === 1
        ? Promise.resolve(jsonResponse({ code: 'FORBIDDEN' }, 403))
        : linkedWith('google')
    })

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    /*
     * 빈 목록으로 접지 않는다. 접으면 이미 연결된 Google에도 연결 버튼이 그려지고, 그 클릭은
     * 서버가 거절해 "본인 확인을 마친 뒤 다시 연결해 주세요"라는 엉뚱한 안내로 끝난다.
     */
    expect(await screen.findByText(authContent.settings.unavailable)).toBeInTheDocument()
    expect(screen.queryByText(authContent.settings.linked)).not.toBeInTheDocument()
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.queryByRole('button', { name: authContent.settings.linkLabels[provider] }),
      ).not.toBeInTheDocument()
    }

    // 자동 재시도하지 않는다. 다시 확인하는 것은 사용자의 조작 하나다.
    expect(attempts).toBe(1)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.retry }))

    expect(await screen.findByText(authContent.settings.linked)).toBeInTheDocument()
    expect(attempts).toBe(2)
    expect(screen.queryByText(authContent.settings.unavailable)).not.toBeInTheDocument()
  })

  it('연결 상태가 JSON이 아니어도 빈 목록으로 주장하지 않는다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => Promise.resolve(jsonResponse({ providers: 'google' })))

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    expect(await screen.findByText(authContent.settings.unavailable)).toBeInTheDocument()
  })

  it('금융값을 담지 않는다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    const rendered = document.body.textContent

    expect(rendered).not.toMatch(/\d+(\.\d+)?\s*%/)
    expect(rendered).not.toContain('USDT')
    expect(rendered).not.toContain('$')
  })
})
