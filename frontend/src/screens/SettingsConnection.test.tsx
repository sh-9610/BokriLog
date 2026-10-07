import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { authContent, noticePath } from '../content/auth'
import {
  BYBIT_CANCEL_PATH,
  BYBIT_CONNECT_PATH,
  BYBIT_CONNECTION_PATH,
  CONNECTION_REJECTION_CODES,
  connectionContent,
  connectionCopyStrings,
  formatVerifiedAt,
  hasRejectionCopy,
  rejectionMessage,
} from '../content/connection'
import { navigateTo, replaceLocation } from '../session/navigation'
import { expectReauthGateRejection } from '../test/reauthGate'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubConnectionCancelRequest,
  stubConnectionPrepareRequest,
  stubConnectionStatusRequest,
  stubConnectRequest,
  stubLinkStatusRequest,
  stubSessionRequest,
  stubUnlinkPrepareRequest,
} from '../test/setup'
import { SettingsScreen } from './SettingsScreen'

/*
 * 설정 화면 안의 Bybit 연결 흐름(Story 2.1).
 *
 * `SettingsScreen.test.tsx`와 따로 둔다. 그 파일은 로그인 수단 목록·해제 대화상자의 계약을
 * 소유하고 이 파일은 연결 하나를 소유한다 — 한 파일에 섞으면 무엇이 무엇을 고정하는지 읽는 사람이
 * 매번 확인해야 한다.
 *
 * 새 내부 경로가 없다. 이 흐름은 `/settings` 안에서 열리고 닫히므로 이 시험도 그 경로 하나만
 * 연다(UX-DR15).
 */

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const CSRF_TOKEN = 'settings-connection-csrf-token'

const CONFIRMATION = '읽기 전용 Bybit 연결을 만듭니다. 확인 코드 zZ9-xY'

/**
 * 이 시험이 쓰는 값 둘.
 *
 * API Key는 개발 스텁의 시나리오 선택자 모양이고(`DevBybitStub`) Secret은 실행 시점 난수다 —
 * 자격정보 모양 리터럴을 시험 fixture에 두지 않는다.
 */
const API_KEY = 'uta2.100001'

const API_SECRET = `s-${Math.random().toString(36).slice(2)}`

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function authenticated(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({ authenticated: true, destination: 'settings', csrfToken: CSRF_TOKEN }),
  )
}

function linkedWith(...providers: string[]): Promise<Response> {
  return Promise.resolve(jsonResponse({ providers }))
}

function notConnected(): Promise<Response> {
  return Promise.resolve(jsonResponse({ connected: false }))
}

/** 응답이 아직 오지 않은 상태. 진행 중 분기를 관측하기 위한 의도된 무기한 대기다. */
function pendingResponse(): Promise<Response> {
  return new Promise<Response>(() => {
    // 아무것도 하지 않는다. resolve를 부르지 않는 것이 이 함수의 목적이다.
  })
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  vi.mocked(navigateTo).mockClear()
  window.history.pushState({}, '', '/settings')
  stubSessionRequest(() => authenticated())
  stubLinkStatusRequest(() => linkedWith('google', 'naver'))
})

/** 연결 구역이 그려질 때까지 기다린다. 게이트가 `ready`가 된 뒤에만 나타난다. */
async function renderSettings(): Promise<void> {
  render(<SettingsScreen />)
  await screen.findByRole('heading', { level: 2, name: connectionContent.heading })
}

/**
 * 연결 구역 하나.
 *
 * 범위를 좁히는 이유는 문자열이 겹치기 때문이다 — "연결됨"은 로그인 수단 목록의 상태 표지이기도
 * 하다. 전역 조회로 두면 어느 구역의 사실을 본 것인지 시험이 말하지 못한다.
 */
function connectionSection(): HTMLElement {
  return screen.getByRole('region', { name: connectionContent.heading })
}

/** 폼을 연다. 이 요청은 아무것도 바꾸지 않는다. */
async function openForm(): Promise<void> {
  fireEvent.click(await screen.findByRole('button', { name: connectionContent.start }))
  await screen.findByRole('heading', { level: 3, name: connectionContent.form.heading })
}

function fillAndSubmit(): void {
  fireEvent.change(screen.getByLabelText(connectionContent.form.apiKeyLabel), {
    target: { value: API_KEY },
  })
  fireEvent.change(screen.getByLabelText(connectionContent.form.apiSecretLabel), {
    target: { value: API_SECRET },
  })
  fireEvent.click(screen.getByRole('button', { name: connectionContent.form.submit }))
}

describe('SettingsScreen의 Bybit 연결', () => {
  it('연결되지 않은 계정에는 시작 조작만 있고 폼은 없다', async () => {
    stubConnectionStatusRequest(notConnected)

    await renderSettings()

    const rendered = document.body.textContent
    for (const copy of connectionCopyStrings) {
      expect(rendered).toContain(copy)
    }
    expect(await screen.findByText(connectionContent.notConnected)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: connectionContent.start })).toBeInTheDocument()
    // 폼은 준비가 성립한 뒤에만 열린다.
    expect(screen.queryByLabelText(connectionContent.form.apiSecretLabel)).not.toBeInTheDocument()
  })

  it('상태를 확인하지 못하면 연결 여부를 주장하지 않고 다시 확인 조작을 준다', async () => {
    stubConnectionStatusRequest(() => Promise.resolve(jsonResponse({}, 500)))

    await renderSettings()

    expect(await screen.findByText(connectionContent.unavailable)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: connectionContent.retry })).toBeInTheDocument()
    // "연결 없음"으로 접지 않는다. 시작 조작도 연결됨 표시도 그리지 않는다.
    expect(screen.queryByText(connectionContent.notConnected)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: connectionContent.start })).not.toBeInTheDocument()
  })

  it('폼을 여는 요청은 자격정보를 보내지 않는다', async () => {
    stubConnectionStatusRequest(notConnected)
    const seen: (RequestInit | undefined)[] = []
    stubConnectionPrepareRequest((init) => {
      seen.push(init)
      return Promise.resolve(jsonResponse({ confirmation: CONFIRMATION }))
    })

    await renderSettings()
    await openForm()

    expect(seen).toHaveLength(1)
    expect(seen[0]?.body).toBeUndefined()
    expect((seen[0]?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(CSRF_TOKEN)
    // 확인 문구는 서버가 만들고 화면이 그대로 렌더한다.
    expect(screen.getByText(CONFIRMATION)).toBeInTheDocument()
    expect(getObservedAllowedRequests()).toContain(`POST ${BYBIT_CONNECTION_PATH}`)
    expect(getObservedAllowedRequests()).not.toContain(`POST ${BYBIT_CONNECT_PATH}`)
  })

  it('준비가 거부되면 폼을 열지 않고 다음 행동을 말한다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ code: 'FORBIDDEN', message: '', correlationId: 'x' }, 403)),
    )

    await renderSettings()
    fireEvent.click(await screen.findByRole('button', { name: connectionContent.start }))

    expect(await screen.findByText(rejectionMessage('FORBIDDEN'))).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { level: 3, name: connectionContent.form.heading }),
    ).not.toBeInTheDocument()
  })

  it('자격정보는 본문으로만 나가고 주소에 남지 않는다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ confirmation: CONFIRMATION })),
    )
    let body = ''
    stubConnectRequest((init) => {
      body = typeof init?.body === 'string' ? init.body : ''
      return Promise.resolve(
        jsonResponse({
          connected: true,
          verifiedAt: '2026-09-25T01:02:03Z',
          policyVersion: 'bybit-readonly-uta-1.0',
        }),
      )
    })

    await renderSettings()
    await openForm()
    fillAndSubmit()

    await screen.findByText(connectionContent.succeeded)
    const sent: unknown = JSON.parse(body)
    expect(sent).toEqual({
      apiKey: API_KEY,
      apiSecret: API_SECRET,
      confirmation: CONFIRMATION,
    })
    // 자격정보가 실린 요청은 하나뿐이고 그 URL에는 값이 없다.
    const credentialRequests = getObservedAllowedRequests().filter((entry) =>
      entry.includes(API_SECRET),
    )
    expect(credentialRequests).toEqual([])
    expect(getObservedRequests()).toEqual([])
  })

  it('성공하면 연결됨과 검증 시각·정책 버전을 보여 주고 Secret을 재표시하지 않는다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ confirmation: CONFIRMATION })),
    )
    stubConnectRequest(() =>
      Promise.resolve(
        jsonResponse({
          connected: true,
          verifiedAt: '2026-09-25T01:02:03Z',
          policyVersion: 'bybit-readonly-uta-1.0',
        }),
      ),
    )

    await renderSettings()
    await openForm()
    fillAndSubmit()
    await screen.findByText(connectionContent.succeeded)

    const section = within(connectionSection())
    expect(section.getByText(connectionContent.connected)).toBeInTheDocument()
    expect(section.getByText(connectionContent.verifiedAtLabel)).toBeInTheDocument()
    expect(section.getByText('bybit-readonly-uta-1.0')).toBeInTheDocument()
    expect(section.getByText(connectionContent.connectedBoundary)).toBeInTheDocument()

    // 폼이 사라졌고 값은 화면 어디에도 없다. 마스킹 값조차 재표시하지 않는다.
    expect(screen.queryByLabelText(connectionContent.form.apiSecretLabel)).not.toBeInTheDocument()
    const rendered = document.body.textContent
    expect(rendered).not.toContain(API_SECRET)
    expect(rendered).not.toContain(API_KEY)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('거절되면 폼이 남고 다음 행동을 말한다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ confirmation: CONFIRMATION })),
    )
    stubConnectRequest(() =>
      Promise.resolve(
        jsonResponse({ code: 'CONNECTION_WRITE_PERMISSION', message: '', correlationId: 'x' }, 422),
      ),
    )

    await renderSettings()
    await openForm()
    fillAndSubmit()

    const rejection = rejectionMessage('CONNECTION_WRITE_PERMISSION')
    await waitFor(() => {
      expect(screen.getAllByText(rejection).length).toBeGreaterThan(0)
    })
    // 폼이 남아 있다. 허가도 소비되지 않았으므로 같은 확인 문구로 다시 시도할 수 있다.
    expect(
      screen.getByRole('heading', { level: 3, name: connectionContent.form.heading }),
    ).toBeInTheDocument()
    expect(
      screen.getByLabelText<HTMLInputElement>(connectionContent.form.apiSecretLabel).value,
    ).toBe('')
    expect(screen.getByLabelText<HTMLInputElement>(connectionContent.form.apiKeyLabel).value).toBe(
      API_KEY,
    )
  })

  it('모르는 code도 화면 문장 하나로 접힌다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ confirmation: CONFIRMATION })),
    )
    stubConnectRequest(() =>
      Promise.resolve(jsonResponse({ code: 'CONNECTION_FUTURE_REASON' }, 422)),
    )

    await renderSettings()
    await openForm()
    fillAndSubmit()

    await waitFor(() => {
      expect(screen.getAllByText(rejectionMessage(null)).length).toBeGreaterThan(0)
    })
  })

  it('취소는 준비를 되돌리고 바뀐 것이 없다고 말한다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ confirmation: CONFIRMATION })),
    )
    stubConnectionCancelRequest(() => Promise.resolve(new Response(null, { status: 204 })))

    await renderSettings()
    await openForm()
    fireEvent.click(screen.getByRole('button', { name: connectionContent.form.cancel }))

    expect(await screen.findByText(connectionContent.cancelled)).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { level: 3, name: connectionContent.form.heading }),
    ).not.toBeInTheDocument()
    await waitFor(() => {
      expect(getObservedAllowedRequests()).toContain(`POST ${BYBIT_CANCEL_PATH}`)
    })
    // 자격정보는 나가지 않았다.
    expect(getObservedAllowedRequests()).not.toContain(`POST ${BYBIT_CONNECT_PATH}`)
  })

  it('서버가 낼 수 있는 code를 화면 사전이 모두 덮는다', () => {
    /*
     * 두 계층이 한 계약을 나눠 갖는 자리다(`CSRF_HEADER`와 같은 종류의 중복). 백엔드는
     * `BybitConnectionController.emittedCodes()`가 같은 집합임을 자기 시험으로 단정하고, 이쪽은
     * 그 목록에 문장이 하나도 빠지지 않았는지를 본다 — 빠지면 사용자는 고칠 수 있는 원인 대신
     * "잠시 후 다시 시도"를 듣는다.
     *
     * 역방향도 함께 본다. 사전에만 있고 서버가 내지 않는 code는 아무도 렌더하지 않는 카피다.
     */
    const uncovered = CONNECTION_REJECTION_CODES.filter((code) => !hasRejectionCopy(code))
    expect(uncovered).toEqual([])
    // 모르는 code로 접히는 문장을 그대로 쓰는 항목이 없다. 있으면 그 code는 덮이지 않은 것과 같다.
    const generic = CONNECTION_REJECTION_CODES.filter(
      (code) => rejectionMessage(code) === rejectionMessage(null),
    )
    expect(generic).toEqual([])
    expect(CONNECTION_REJECTION_CODES.length).toBe(15)
  })

  it('검증 시각을 Asia/Seoul로 표시한다', () => {
    /*
     * 라벨만 단정하면 시간대 고정을 지워도 green이다. 저장은 UTC instant이고 표시는 `Asia/Seoul`
     * 이라는 것이 이 계약이며(epic-2-context), 클라이언트가 자기 시간대를 쓰면 같은 사실이
     * 기기마다 다른 문장이 된다.
     */
    const formatted = formatVerifiedAt('2026-09-25T01:02:03Z')

    // UTC 01:02는 KST 10:02다.
    expect(formatted).toContain('10:02')
    expect(formatted).toContain('2026')
    // 읽을 수 없는 값은 추측해서 그리지 않는다.
    expect(formatVerifiedAt('nonsense')).toBe('')
  })

  it('연결이 도는 동안 live region이 진행을 말한다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(pendingResponse)

    await renderSettings()
    fireEvent.click(await screen.findByRole('button', { name: connectionContent.start }))

    const status = screen.getByRole('status')
    await waitFor(() => {
      expect(status.textContent).toBe(connectionContent.pending)
    })
    expect(status).toHaveAttribute('aria-busy', 'true')
  })

  it('상태를 다시 확인하는 조작이 실제로 다시 묻는다', async () => {
    let attempts = 0
    stubConnectionStatusRequest(() => {
      attempts += 1
      return attempts === 1 ? Promise.resolve(jsonResponse({}, 500)) : notConnected()
    })

    await renderSettings()
    const retry = await screen.findByRole('button', { name: connectionContent.retry })
    // 형제 조작과 같은 표현이다. `aria-disabled`가 없으면 `busy` 가드가 조용히 무시한다.
    expect(retry).toHaveAttribute('aria-disabled', 'false')

    fireEvent.click(retry)

    expect(await screen.findByText(connectionContent.notConnected)).toBeInTheDocument()
    expect(attempts).toBe(2)
  })

  it('흐름 도중 세션이 끝나면 만료 표지를 달고 진입 화면으로 보낸다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ confirmation: CONFIRMATION })),
    )
    stubConnectRequest(() =>
      Promise.resolve(
        jsonResponse({ code: 'UNAUTHENTICATED', message: '', correlationId: 'x' }, 401),
      ),
    )

    await renderSettings()
    await openForm()
    fillAndSubmit()

    /*
     * 사용자가 Bybit 콘솔에 다녀오는 동안 유휴 30분이 지나는 것은 흔한 경로다. 제자리에 "잠시 후
     * 다시 시도"를 그리면 로그인으로 돌아갈 길이 없는 화면이 남는다 — 만료의 결과는 이동 하나이며
     * 만료 문장의 출처는 주소의 표지 하나다(Story 1.5의 계약).
     */
    await waitFor(() => {
      expect(replaceLocation).toHaveBeenCalledWith(noticePath('expired'))
    })
  })

  it('거절은 오류 요약에서만 읽히고 live region이 두 번 말하지 않는다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ confirmation: CONFIRMATION })),
    )
    stubConnectRequest(() =>
      Promise.resolve(
        jsonResponse({ code: 'CONNECTION_IP_NOT_BOUND', message: '', correlationId: 'x' }, 422),
      ),
    )

    await renderSettings()
    await openForm()
    fillAndSubmit()

    const rejection = rejectionMessage('CONNECTION_IP_NOT_BOUND')
    await waitFor(() => {
      expect(screen.getByRole('alert').textContent).toContain(rejection)
    })
    /*
     * polite live region과 assertive 요약이 같은 문장을 함께 내면 보조기술이 그것을 두 번 읽는다.
     * 폼이 열려 있는 동안 그 문장의 소유자는 요약 하나다.
     */
    expect(screen.getAllByText(rejection)).toHaveLength(1)
    expect(screen.getByRole('status').textContent).not.toContain(rejection)
  })

  it('연결 성공 뒤 본인 확인 없는 다음 준비는 거절 문구를 말하고, 본인 확인 버튼은 열려 있으며 초점은 움직이지 않는다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ confirmation: CONFIRMATION })),
    )
    stubConnectRequest(() =>
      Promise.resolve(
        jsonResponse({
          connected: true,
          verifiedAt: '2026-09-25T01:02:03Z',
          policyVersion: 'bybit-readonly-uta-1.0',
        }),
      ),
    )

    await renderSettings()
    await openForm()
    fillAndSubmit()
    const succeeded = await screen.findByText(connectionContent.succeeded)
    await waitFor(() => {
      expect(succeeded).toHaveFocus()
    })

    /*
     * 연결 성공은 재인증 시각을 지웠다(`SessionRotation`) — 본인 확인 1회는 민감한 조작 1건이다. 바로
     * 이어지는 로그인 수단 해제 준비는 403이고, 화면은 성공 문구가 예고한 그 거절을 polite region으로
     * 말한다. 본인 확인 버튼은 열려 있고 초점은 누른 자리에 남는다.
     */
    stubUnlinkPrepareRequest('naver', () =>
      Promise.resolve(jsonResponse({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403)),
    )
    const unlink = screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver })
    unlink.focus()
    fireEvent.click(unlink)

    await expectReauthGateRejection(authContent.notices.unlinkFailed, unlink)
  })

  it('해제 표지가 지난 연결 표지를 되살리지 않는다', async () => {
    stubConnectionStatusRequest(notConnected)
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ confirmation: CONFIRMATION })),
    )
    stubConnectionCancelRequest(() => Promise.resolve(new Response(null, { status: 204 })))
    stubUnlinkPrepareRequest('naver', () => Promise.resolve(new Response(null, { status: 403 })))

    await renderSettings()
    await openForm()
    fireEvent.click(screen.getByRole('button', { name: connectionContent.form.cancel }))
    expect(await screen.findByText(connectionContent.cancelled)).toBeInTheDocument()

    // 이제 다른 흐름을 시작한다. 그 흐름이 live region을 소유한다.
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkLabels.naver }))

    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(authContent.notices.unlinkFailed)
    })
    // 지난 연결 표지가 그 자리로 되살아나지 않는다.
    expect(screen.getByRole('status').textContent).not.toContain(connectionContent.cancelled)
  })

  it('이미 연결된 계정에는 시작 조작을 주지 않는다', async () => {
    stubConnectionStatusRequest(() =>
      Promise.resolve(
        jsonResponse({
          connected: true,
          verifiedAt: '2026-09-25T01:02:03Z',
          policyVersion: 'bybit-readonly-uta-1.0',
        }),
      ),
    )

    await renderSettings()

    await waitFor(() => {
      expect(within(connectionSection()).getByText(connectionContent.connected)).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: connectionContent.start })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(connectionContent.form.apiSecretLabel)).not.toBeInTheDocument()
  })
})
