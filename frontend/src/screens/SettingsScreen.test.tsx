import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  authContent,
  authorizePath,
  CSRF_HEADER,
  linkPath,
  settingsCopyStrings,
  SIGN_IN_PROVIDERS,
  unlinkCancelPath,
  unlinkConfirmPath,
  unlinkPath,
} from '../content/auth'
import { navigateTo, replaceLocation } from '../session/navigation'
import { expectReauthGateRejection } from '../test/reauthGate'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubConnectionStatusRequest,
  stubLinkRequest,
  stubLinkStatusRequest,
  stubLogoutRequest,
  stubReauthenticationRequest,
  stubSessionRequest,
  stubUnlinkCancelRequest,
  stubUnlinkConfirmRequest,
  stubUnlinkPrepareRequest,
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

/**
 * Bybit 연결이 없는 계정(Story 2.1).
 *
 * 이 화면은 게이트가 `ready`가 되면 연결 상태를 한 번 묻는다. 그 요청을 열지 않으면 전역 거부가
 * 그것을 기록하고 "요청 0건" 단정이 있는 시험이 실패한다 — 열어 두는 것이 이 화면의 사실이다.
 */
function notConnected(): Promise<Response> {
  return Promise.resolve(jsonResponse({ connected: false }))
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
    stubConnectionStatusRequest(notConnected)

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
    stubConnectionStatusRequest(notConnected)

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    const title = screen.getByRole('heading', { level: 1 })

    expect(title.textContent).toBe('복리 계획 비교')
    expect(title.nextElementSibling?.textContent).toBe('투자성과·계좌 수익률·투자 추천이 아닙니다.')
  })

  it('연결된 수단에는 연결 조작을 주지 않고 미연결 수단에만 준다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))
    stubConnectionStatusRequest(notConnected)

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    /*
     * 목록이 도착할 때까지 기다린다. 확인 전에는 연결 여부를 주장하지 않으므로 그 사이의 화면에는
     * 연결 상태도 연결 버튼도 없다 — 기다리지 않으면 이 단정이 그 중간 상태를 보고 흔들린다.
     */
    // 연결된 제공자는 상태와 해제 조작을 갖고 연결 조작은 갖지 않는다.
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
    stubConnectionStatusRequest(notConnected)

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
    stubConnectionStatusRequest(notConnected)

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
    stubConnectionStatusRequest(notConnected)

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
    stubConnectionStatusRequest(notConnected)
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
    stubConnectionStatusRequest(notConnected)
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
    stubConnectionStatusRequest(notConnected)
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

  it('해제: 수단이 하나뿐이면 조작이 비활성이고 이유를 문장으로 말한다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))
    stubConnectionStatusRequest(notConnected)

    render(<SettingsScreen />)
    await screen.findByText(authContent.settings.linked)

    const unlink = screen.getByRole('button', { name: authContent.settings.unlinkLabels.google })

    /*
     * 마지막 수단은 화면이 미리 설명한다. 서버 응답은 그 거부를 다른 거부와 구분하지 않지만,
     * 숨기면 비활성화된 버튼에 이유가 없어 접근성이 깨진다.
     */
    expect(unlink).toHaveAttribute('aria-disabled', 'true')
    expect(unlink).toHaveAccessibleDescription(authContent.settings.lastMethod)
    expect(screen.getByText(authContent.settings.lastMethod)).toBeInTheDocument()

    fireEvent.click(unlink)

    // 준비 요청도 나가지 않는다. 열지 않은 경로로 나가면 전역 거부가 그것을 기록하고 실패시킨다.
    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toEqual([])
  })

  it('해제: 수단이 둘이면 두 행 모두 해제할 수 있고 설명이 없다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google', 'naver'))
    stubConnectionStatusRequest(notConnected)

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)

    for (const provider of ['google', 'naver'] as const) {
      expect(
        screen.getByRole('button', { name: authContent.settings.unlinkLabels[provider] }),
      ).toHaveAttribute('aria-disabled', 'false')
    }
    expect(screen.queryByText(authContent.settings.lastMethod)).not.toBeInTheDocument()
  })

  it('해제: 준비가 204면 확인 대화상자를 열고 아직 아무것도 지우지 않는다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google', 'naver'))
    stubConnectionStatusRequest(notConnected)
    let observed: RequestInit | undefined
    stubUnlinkPrepareRequest('naver', (init) => {
      observed = init
      return Promise.resolve(new Response(null, { status: 204 }))
    })

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver }))

    const dialog = await screen.findByRole('dialog')

    expect(dialog).toHaveAccessibleName(authContent.settings.unlinkDialog.titles.naver)
    // 준비 요청 하나만 나갔다. 최종 확인 경로는 열지 않았으므로 나갔다면 전역 거부가 잡는다.
    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toEqual([
      `POST ${unlinkPath('naver')}`,
    ])
    expect(observed?.method).toBe('POST')
    expect(observed?.credentials).toBe('same-origin')
    expect(new Headers(observed?.headers).get(CSRF_HEADER)).toBe(CSRF_TOKEN)
    // 목록은 그대로다. 최종 확인 전에는 아무것도 바뀌지 않는다.
    expect(screen.getAllByText(authContent.settings.linked)).toHaveLength(2)
  })

  it('해제: 준비가 거부되면 대화상자를 열지 않고 표지를 말한다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google', 'naver'))
    stubConnectionStatusRequest(notConnected)
    stubUnlinkPrepareRequest('naver', () =>
      Promise.resolve(jsonResponse({ code: 'FORBIDDEN' }, 403)),
    )

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver }))

    expect(await screen.findByText(authContent.notices.unlinkFailed)).toBeInTheDocument()
    /*
     * 대화상자를 열면 소비할 허가가 없는 확인이 되고 그 실패는 설명할 수 없다. 화면은 이동하지도
     * 않는다 — 해제에는 왕복이 없으므로 표지를 주소에 실을 이유가 없다.
     */
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
    expect(vi.mocked(navigateTo)).not.toHaveBeenCalled()
  })

  it('해제: 취소하면 준비를 되돌리고 아무것도 바뀌지 않았다고 말한다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google', 'naver'))
    stubConnectionStatusRequest(notConnected)
    stubUnlinkPrepareRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))
    let cancelled: RequestInit | undefined
    stubUnlinkCancelRequest('naver', (init) => {
      cancelled = init
      return Promise.resolve(new Response(null, { status: 204 }))
    })

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver }))
    await screen.findByRole('dialog')

    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkDialog.cancel }))

    expect(await screen.findByText(authContent.notices.unlinkCancelled)).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    /*
     * 최종 확인은 나가지 않았고 취소는 나갔다. 준비가 서버 상태를 만들었으므로 되돌리는 요청이
     * 없으면 "바뀐 것은 없습니다"가 거짓이 되고 참조할 수 없는 허가가 5분을 산다.
     */
    await waitFor(() => {
      expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toEqual([
        `POST ${unlinkPath('naver')}`,
        `POST ${unlinkCancelPath('naver')}`,
      ])
    })
    expect(new Headers(cancelled?.headers).get(CSRF_HEADER)).toBe(CSRF_TOKEN)
    expect(screen.getAllByText(authContent.settings.linked)).toHaveLength(2)
  })

  it('해제: 취소가 서버에 닿기 전에 다시 열면 다음 준비는 그 취소가 끝난 뒤에 나간다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google', 'naver'))
    stubConnectionStatusRequest(notConnected)
    stubUnlinkPrepareRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))
    let finishCancel: (response: Response) => void = () => undefined
    stubUnlinkCancelRequest(
      'naver',
      () =>
        new Promise<Response>((resolve) => {
          finishCancel = resolve
        }),
    )
    const posts = (): string[] =>
      getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)
    const unlink = screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver })
    fireEvent.click(unlink)
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkDialog.cancel }))
    await screen.findByText(authContent.notices.unlinkCancelled)
    await waitFor(() => {
      expect(posts()).toEqual([`POST ${unlinkPath('naver')}`, `POST ${unlinkCancelPath('naver')}`])
    })

    /*
     * 겹치면 늦게 끝난 취소가 두 번째 준비의 무장과 허가를 지워 그 최종 확인이 거부로 끝난다(CI가
     * 느린 러너에서 잡은 경합). 취소가 끝나기 전에는 두 번째 준비가 나가지 않는다.
     */
    fireEvent.click(unlink)
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(posts()).toHaveLength(2)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    finishCancel(new Response(null, { status: 204 }))
    await screen.findByRole('dialog')
    expect(posts()).toEqual([
      `POST ${unlinkPath('naver')}`,
      `POST ${unlinkCancelPath('naver')}`,
      `POST ${unlinkPath('naver')}`,
    ])
  })

  it('해제: 대화상자가 열려 있는 동안 배경 조작이 막힌다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google', 'naver'))
    stubConnectionStatusRequest(notConnected)
    stubUnlinkPrepareRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver }))
    await screen.findByRole('dialog')

    /*
     * 배경은 `inert`로 비활성화되지만 그 속성이 없는 브라우저도 있다. 배경 조작이 실제로 돌면
     * 열려 있는 해제 무장이 사라지고, 사용자가 확인을 누르는 순간 이유 없이 실패한다.
     */
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.linkLabels.kakao }))
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.reauth.labels.google }))
    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    /*
     * 준비 하나뿐이다. 세 조작은 각자 다른 경로로 나가고 이 시험은 그 어느 것도 열지 않았으므로,
     * 하나라도 실제로 돌면 전역 거부가 그것을 기록한다 — 허용 목록만 보면 거부된 요청이 그
     * 목록에 없어 이 단정이 아무것도 관측하지 않는다.
     */
    expect(getObservedRequests()).toEqual([])
    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toEqual([
      `POST ${unlinkPath('naver')}`,
    ])
  })

  it('해제: 확인하면 목록을 다시 읽고 결과를 말한다', async () => {
    stubSessionRequest(() => authenticated())
    let statusCalls = 0
    stubLinkStatusRequest(() => {
      statusCalls += 1
      // 확인 뒤의 조회는 서버가 이미 매핑을 지운 상태를 답한다.
      return statusCalls === 1 ? linkedWith('google', 'naver') : linkedWith('google')
    })
    stubUnlinkPrepareRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))
    let observed: RequestInit | undefined
    stubUnlinkConfirmRequest('naver', (init) => {
      observed = init
      return Promise.resolve(new Response(null, { status: 204 }))
    })

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver }))
    await screen.findByRole('dialog')

    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkDialog.confirm }))

    expect(await screen.findByText(authContent.notices.unlinkSucceeded)).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(new Headers(observed?.headers).get(CSRF_HEADER)).toBe(CSRF_TOKEN)
    // 두 요청이 순서대로 나갔다.
    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toEqual([
      `POST ${unlinkPath('naver')}`,
      `POST ${unlinkConfirmPath('naver')}`,
    ])
    /*
     * 사라진 수단은 재조회를 기다리지 않고 목록에서 빠진다. 기다리면 그 사이에 남은 해제 버튼이
     * 눌려 곧바로 403 실패 표지가 난다 — 방금 해제한 것을 다시 해제하려다 실패를 보는 상태다.
     */
    expect(
      screen.queryByRole('button', { name: authContent.settings.unlinkLabels.naver }),
    ).not.toBeInTheDocument()
    // 목록을 다시 읽어 지금 상태를 그린다. 이제 Google이 마지막 수단이다.
    await waitFor(() => {
      expect(statusCalls).toBe(2)
    })
    expect(await screen.findByText(authContent.settings.lastMethod)).toBeInTheDocument()
    // 화면은 이동하지 않았다. 해제는 문서를 떠나지 않는 조작이다.
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
    expect(vi.mocked(navigateTo)).not.toHaveBeenCalled()
  })

  it('해제: 확인이 거부되면 대화상자를 닫고 실패를 말한다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google', 'naver'))
    stubConnectionStatusRequest(notConnected)
    stubUnlinkPrepareRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))
    stubUnlinkConfirmRequest('naver', () =>
      Promise.resolve(jsonResponse({ code: 'FORBIDDEN' }, 403)),
    )

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver }))
    await screen.findByRole('dialog')

    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkDialog.confirm }))

    expect(await screen.findByText(authContent.notices.unlinkFailed)).toBeInTheDocument()
    /*
     * 무장은 이 확인 하나로 소비됐으므로 같은 대화상자의 두 번째 확인은 언제나 거부다. 열어 두면
     * 사용자가 통하지 않는 버튼을 다시 누른다.
     */
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getAllByText(authContent.settings.linked)).toHaveLength(2)
  })

  it('해제: 진행 중 중복 제출을 차단한다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google', 'naver'))
    stubConnectionStatusRequest(notConnected)
    let resolvePrepare: (response: Response) => void = () => undefined
    stubUnlinkPrepareRequest(
      'naver',
      () =>
        new Promise<Response>((resolve) => {
          resolvePrepare = resolve
        }),
    )

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)

    const naverUnlink = screen.getByRole('button', {
      name: authContent.settings.unlinkLabels.naver,
    })
    fireEvent.click(naverUnlink)

    expect(naverUnlink).toHaveAttribute('aria-disabled', 'true')
    // `disabled`가 아니다. 그 속성은 방금 이 버튼을 실행한 키보드 사용자의 포커스를 빼앗는다.
    expect(naverUnlink).toBeEnabled()
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status').textContent).toBe(authContent.settings.unlinkPending)

    fireEvent.click(naverUnlink)
    // 다른 행의 해제도 함께 막힌다. 두 흐름이 겹치면 서버가 앞의 무장을 덮는다.
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.google }))

    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toHaveLength(1)
    resolvePrepare(new Response(null, { status: 204 }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })

  it('해제 결과를 화면당 하나뿐인 live region이 말한다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google', 'naver'))
    stubConnectionStatusRequest(notConnected)
    stubUnlinkPrepareRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))
    stubUnlinkCancelRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver }))
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkDialog.cancel }))

    await screen.findByText(authContent.notices.unlinkCancelled)

    /*
     * 섹션 안에 두면 취소·실패에서 포커스도 움직이지 않아 보조기술 사용자에게 아무것도 알려지지
     * 않는다 — 해제는 문서를 떠나지 않으므로 화면 어디에도 다른 변화 신호가 없다.
     */
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status').textContent).toBe(authContent.notices.unlinkCancelled)
  })

  it('해제 성공 뒤 포커스가 결과 문장으로 간다', async () => {
    stubSessionRequest(() => authenticated())
    let statusCalls = 0
    stubLinkStatusRequest(() => {
      statusCalls += 1
      return statusCalls === 1 ? linkedWith('google', 'naver') : linkedWith('google')
    })
    stubUnlinkPrepareRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))
    stubUnlinkConfirmRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver }))
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkDialog.confirm }))

    const result = await screen.findByText(authContent.notices.unlinkSucceeded)

    /*
     * 대화상자를 연 버튼은 사라졌다 — 그 제공자가 더 이상 연결돼 있지 않으므로 행의 조작이 바뀐다.
     * 되돌릴 호출점이 없으면 포커스는 `<body>`에 남고, 키보드·보조기술 사용자는 무엇이 일어났는지
     * 듣지 못한 채 문서 처음으로 떨어진다.
     */
    await waitFor(() => {
      expect(result).toHaveFocus()
    })
  })

  it('해제 성공 뒤 본인 확인 없는 다음 준비는 거절 문구를 말하고, 본인 확인 버튼은 열려 있으며 초점은 움직이지 않는다', async () => {
    stubSessionRequest(() => authenticated())
    let statusCalls = 0
    stubLinkStatusRequest(() => {
      statusCalls += 1
      return statusCalls === 1
        ? linkedWith('google', 'naver', 'kakao')
        : linkedWith('google', 'kakao')
    })
    stubConnectionStatusRequest(notConnected)
    stubUnlinkPrepareRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))
    stubUnlinkConfirmRequest('naver', () => Promise.resolve(new Response(null, { status: 204 })))

    render(<SettingsScreen />)
    await screen.findAllByText(authContent.settings.linked)
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver }))
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkDialog.confirm }))
    const result = await screen.findByText(authContent.notices.unlinkSucceeded)
    await waitFor(() => {
      expect(result).toHaveFocus()
    })

    /*
     * 해제 성공은 재인증 시각을 지웠다(`SessionRotation`) — 본인 확인 1회는 민감한 조작 1건이다. 바로
     * 이어지는 다른 수단의 해제 준비는 403이고, 화면은 성공 문구가 예고한 그 거절을 polite region으로
     * 말한다. 본인 확인 버튼은 열려 있고 초점은 누른 자리에 남는다.
     */
    stubUnlinkPrepareRequest('kakao', () =>
      Promise.resolve(jsonResponse({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403)),
    )
    const unlink = screen.getByRole('button', { name: authContent.settings.unlinkLabels.kakao })
    unlink.focus()
    fireEvent.click(unlink)

    await expectReauthGateRejection(authContent.notices.unlinkFailed, unlink)
  })

  it('본인 확인: 세 제공자 버튼이 고정 순서로 있고 시작 호출이 토큰을 싣는다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))
    stubConnectionStatusRequest(notConnected)
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
      stubConnectionStatusRequest(notConnected)

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
    stubConnectionStatusRequest(notConnected)

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
    stubConnectionStatusRequest(notConnected)
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
    stubConnectionStatusRequest(notConnected)
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
    stubConnectionStatusRequest(notConnected)

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    expect(await screen.findByText(authContent.settings.unavailable)).toBeInTheDocument()
  })

  it('금융값을 담지 않는다', async () => {
    stubSessionRequest(() => authenticated())
    stubLinkStatusRequest(() => linkedWith('google'))
    stubConnectionStatusRequest(notConnected)

    render(<SettingsScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.settings.heading })

    const rendered = document.body.textContent

    expect(rendered).not.toMatch(/\d+(\.\d+)?\s*%/)
    expect(rendered).not.toContain('USDT')
    expect(rendered).not.toContain('$')
  })
})
