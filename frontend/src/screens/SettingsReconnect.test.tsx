import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  BYBIT_CONNECT_PATH,
  BYBIT_CONNECTION_PATH,
  connectionContent,
  connectionFormCopyStrings,
  reconnectCopyStrings,
  rejectionMessage,
  revocationContent,
} from '../content/connection'
import { replaceLocation } from '../session/navigation'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubConnectionCancelRequest,
  stubConnectionPrepareRequest,
  stubConnectionStatusRequest,
  stubConnectRequest,
  stubLinkStatusRequest,
  stubSessionRequest,
} from '../test/setup'
import { SettingsScreen } from './SettingsScreen'

/*
 * 설정 화면의 Bybit 재연결(Story 2.17, `RECONNECT-1.0`).
 *
 * 해제된 연결에는 "아직 제공하지 않음" 대신 재연결 안내와 시작 조작이 있고, 그 조작은 Story 2.1과 같은 준비 → 입력 →
 * 검증 흐름을 연다. 폼 앞에서 같은 계정·다른 계정의 결과를 말하고, 같은 계정으로 이어지면 결과 문장이 "기존 계획을
 * 이어서 동기화한다"(30일 초과면 선택 필요)이며, 다른 계정의 거절은 폼 안의 오류 요약이 원인과 계획 종료 경로를 말한다.
 */

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const CSRF_TOKEN = 'settings-reconnect-csrf-token'
const CONNECTION_ID = '5d1f0c2a-7b3e-4f69-9a1d-2e3f4a5b6c7d'
const NEW_CONNECTION_ID = '9e8d7c6b-5a49-4382-a716-0f1e2d3c4b5a'
const CONFIRMATION = '읽기 전용 Bybit 연결을 만듭니다. 확인 코드 aB3-cD'

/** 스텁 시나리오 선택자 모양의 API Key와 실행 시점 난수 Secret — 자격정보 모양 리터럴을 두지 않는다. */
const API_KEY = 'uta2.200001'
const API_SECRET = `s-${Math.random().toString(36).slice(2)}`

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function revokedState(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({
      connected: false,
      connectionId: CONNECTION_ID,
      revoked: true,
      revokedAt: '2026-10-05T01:02:03.456Z',
      verifiedAt: '2026-10-01T00:00:00Z',
      policyVersion: 'bybit-readonly-uta-1.0',
    }),
  )
}

function connected(planResumed: boolean): Promise<Response> {
  return Promise.resolve(
    jsonResponse({
      connected: true,
      verifiedAt: '2026-10-14T01:02:03Z',
      policyVersion: 'bybit-readonly-uta-1.0',
      connectionId: NEW_CONNECTION_ID,
      revoked: false,
      ...(planResumed ? { planResumed: true } : {}),
    }),
  )
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  window.history.pushState({}, '', '/settings')
  stubSessionRequest(() =>
    Promise.resolve(
      jsonResponse({ authenticated: true, destination: 'settings', csrfToken: CSRF_TOKEN }),
    ),
  )
  stubLinkStatusRequest(() => Promise.resolve(jsonResponse({ providers: ['google'] })))
  stubConnectionStatusRequest(revokedState)
  stubConnectionPrepareRequest(() => Promise.resolve(jsonResponse({ confirmation: CONFIRMATION })))
})

function connectionSection(): HTMLElement {
  return screen.getByRole('region', { name: connectionContent.heading })
}

function status(): HTMLElement {
  return screen.getByRole('status')
}

/** 해제 상태에서 재연결 폼을 연다. 여는 요청은 Story 2.1의 준비이고 아무것도 바꾸지 않는다. */
async function openReconnectForm(): Promise<void> {
  render(<SettingsScreen />)
  fireEvent.click(await screen.findByRole('button', { name: revocationContent.reconnectStart }))
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

describe('SettingsScreen의 Bybit 재연결', () => {
  it('해제 상태의 시작 조작은 Story 2.1의 준비를 부르고, 폼 앞에서 같은·다른 계정의 결과를 말한다', async () => {
    await openReconnectForm()

    expect(getObservedAllowedRequests()).toContain(`POST ${BYBIT_CONNECTION_PATH}`)
    const section = within(connectionSection())
    for (const text of reconnectCopyStrings) {
      expect(section.getByText(text)).toBeInTheDocument()
    }
    // 폼 문구 중 일부("Bybit 연결")는 구역 제목과 겹친다 — 하나 이상 있으면 된다.
    for (const text of connectionFormCopyStrings) {
      expect(section.getAllByText(text).length).toBeGreaterThan(0)
    }
    expect(section.getByText(CONFIRMATION)).toBeInTheDocument()
    // 해제 상태 표지와 시작 조작은 폼이 열리면 사라진다 — 폼 하나가 남는다.
    expect(section.queryByRole('button', { name: revocationContent.reconnectStart })).toBeNull()
  })

  it('같은 계정으로 이어지면 기존 계획을 이어서 동기화한다고 한 번 말하고 연결됨을 그린다', async () => {
    let sent: unknown = null
    stubConnectRequest((init) => {
      sent = JSON.parse(init?.body as string)
      return connected(true)
    })
    await openReconnectForm()
    fillAndSubmit()

    const notice = await within(status()).findByText(connectionContent.resumed)
    expect(notice).toBeInTheDocument()
    expect(screen.getAllByText(connectionContent.resumed)).toHaveLength(1)
    expect(screen.queryByText(connectionContent.succeeded)).toBeNull()
    const section = within(connectionSection())
    expect(section.getByText(connectionContent.connected)).toBeInTheDocument()
    expect(section.queryByText(revocationContent.revoked)).toBeNull()
    expect(section.queryByText(revocationContent.reconnectSameAccount)).toBeNull()
    expect(section.getByRole('button', { name: revocationContent.start })).toBeInTheDocument()
    // 자격정보는 본문으로만 나가고 화면·주소·브라우저 저장소에 남지 않는다.
    expect(sent).toEqual({ apiKey: API_KEY, apiSecret: API_SECRET, confirmation: CONFIRMATION })
    expect(getObservedAllowedRequests()).toContain(`POST ${BYBIT_CONNECT_PATH}`)
    expect(document.body.textContent).not.toContain(API_SECRET)
    expect(document.body.textContent).not.toContain(API_KEY)
    expect(window.location.search).toBe('')
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('이어 갈 활성 계획이 없으면(종단 계획만·새 계정) 일반 연결 완료 문장이다', async () => {
    stubConnectRequest(() => connected(false))
    await openReconnectForm()
    fillAndSubmit()

    expect(await within(status()).findByText(connectionContent.succeeded)).toBeInTheDocument()
    expect(screen.queryByText(connectionContent.resumed)).toBeNull()
  })

  it('다른 계정의 거절은 폼을 남기고 오류 요약이 원인과 계획 종료 경로를 한 번 말한다', async () => {
    stubConnectRequest(() =>
      Promise.resolve(
        jsonResponse(
          { code: 'CONNECTION_ACCOUNT_MISMATCH', message: 'x', correlationId: 'c' },
          422,
        ),
      ),
    )
    await openReconnectForm()
    fillAndSubmit()

    const rejection = rejectionMessage('CONNECTION_ACCOUNT_MISMATCH')
    expect(rejection).not.toBe(rejectionMessage(null))
    const alert = await screen.findByRole('alert')
    await waitFor(() => {
      expect(within(alert).getByText(rejection)).toBeInTheDocument()
    })
    // live region은 같은 문장을 다시 읽지 않는다(오류 요약이 그 문장의 소유자다).
    expect(within(status()).queryByText(rejection)).toBeNull()
    expect(
      screen.getByRole('heading', { level: 3, name: connectionContent.form.heading }),
    ).toBeInTheDocument()
    expect(
      screen.getByLabelText<HTMLInputElement>(connectionContent.form.apiSecretLabel).value,
    ).toBe('')
    // 다른 계정의 결과 안내가 폼 앞에 그대로 있다.
    expect(
      within(connectionSection()).getByText(revocationContent.reconnectOtherAccount),
    ).toBeInTheDocument()
  })

  it('키보드 사용자: 재연결을 시작하면 폼 제목이, 성공하면 결과 문장이, 취소하면 다시 그려진 시작 버튼이 초점을 받는다', async () => {
    stubConnectionCancelRequest(() => Promise.resolve(new Response(null, { status: 204 })))
    await openReconnectForm()
    // 여는 버튼은 폼과 함께 사라진다 — 초점은 폼 제목이다.
    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 3, name: connectionContent.form.heading }),
      ).toHaveFocus()
    })
    // 취소하면 폼을 연 그 버튼(같은 id)으로 돌아온다.
    fireEvent.click(screen.getByRole('button', { name: connectionContent.form.cancel }))
    const restart = await screen.findByRole('button', { name: revocationContent.reconnectStart })
    await waitFor(() => {
      expect(restart).toHaveFocus()
    })
    expect(restart.id).toBe(connectionContent.startId)

    // 다시 열어 성공하면 결과 문장이 초점을 받는다.
    stubConnectRequest(() => connected(true))
    fireEvent.click(restart)
    await screen.findByRole('heading', { level: 3, name: connectionContent.form.heading })
    fillAndSubmit()
    const notice = await within(status()).findByText(connectionContent.resumed)
    await waitFor(() => {
      expect(notice).toHaveFocus()
    })
  })
})
