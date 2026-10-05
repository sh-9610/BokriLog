import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { authContent, CSRF_HEADER } from '../content/auth'
import {
  BYBIT_REVOCATION_CANCEL_PATH,
  BYBIT_REVOCATION_CONFIRM_PATH,
  BYBIT_REVOCATION_PATH,
  connectionContent,
  REVOCATION_REJECTION_CODES,
  revocationContent,
  revocationDialogCopyStrings,
  revocationRejectionMessage,
} from '../content/connection'
import { replaceLocation } from '../session/navigation'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubConnectionPrepareRequest,
  stubConnectionStatusRequest,
  stubConnectRequest,
  stubLinkStatusRequest,
  stubRevocationCancelRequest,
  stubRevocationConfirmRequest,
  stubRevocationPrepareRequest,
  stubSessionRequest,
} from '../test/setup'
import { SettingsScreen } from './SettingsScreen'

/*
 * 설정 화면 안의 Bybit 연결 해제(Story 2.16).
 *
 * 연결된 계정에만 보조 조작 `Bybit 연결 해제`가 있고, 누르면 준비 요청이 허가를 무장한 뒤 확인 대화상자가 열린다.
 * 대화상자는 즉시 삭제·작업 취소·새 동기화 불가·보존 범위·Bybit 원격 폐기 한계를 따로 말하고 취소가 기본 초점이다.
 * 확정이 끝나면 상태를 글자로 `해제됨`으로 그리고, 재연결 조작 대신 중립 안내만 둔다.
 */

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const CSRF_TOKEN = 'settings-revoke-csrf-token'
const CONNECTION_ID = '0b9c3f7e-1d2a-4c5b-8e6f-7a8b9c0d1e2f'
const CONFIRMATION = `bybit-connection-revoke|${CONNECTION_ID}`
const REVOKED_AT = '2026-10-05T01:02:03.456Z'

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function connected(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({
      connected: true,
      connectionId: CONNECTION_ID,
      revoked: false,
      verifiedAt: '2026-10-01T00:00:00Z',
      policyVersion: 'bybit-readonly-uta-1.0',
    }),
  )
}

function revokedState(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({
      connected: false,
      connectionId: CONNECTION_ID,
      revoked: true,
      revokedAt: REVOKED_AT,
      verifiedAt: '2026-10-01T00:00:00Z',
      policyVersion: 'bybit-readonly-uta-1.0',
    }),
  )
}

function prepared(): Promise<Response> {
  return Promise.resolve(jsonResponse({ connectionId: CONNECTION_ID, confirmation: CONFIRMATION }))
}

function revokedResult(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({ outcome: 'REVOKED', connectionId: CONNECTION_ID, revokedAt: REVOKED_AT }),
  )
}

function noContent(): Promise<Response> {
  return Promise.resolve(new Response(null, { status: 204 }))
}

function pendingResponse(): Promise<Response> {
  return new Promise<Response>(() => {
    // 응답이 오지 않는다. 진행 중 분기를 관측하기 위한 의도된 무기한 대기다.
  })
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  window.history.pushState({}, '', '/settings')
  stubSessionRequest(() =>
    Promise.resolve(
      jsonResponse({ authenticated: true, destination: 'settings', csrfToken: CSRF_TOKEN }),
    ),
  )
  stubLinkStatusRequest(() => Promise.resolve(jsonResponse({ providers: ['google', 'naver'] })))
})

function connectionSection(): HTMLElement {
  return screen.getByRole('region', { name: connectionContent.heading })
}

async function renderConnected(): Promise<void> {
  stubConnectionStatusRequest(connected)
  render(<SettingsScreen />)
  await screen.findByRole('button', { name: revocationContent.start })
}

async function openDialog(): Promise<HTMLElement> {
  stubRevocationPrepareRequest(prepared)
  fireEvent.click(screen.getByRole('button', { name: revocationContent.start }))
  return screen.findByRole('dialog', { name: revocationContent.dialog.title })
}

/**
 * The screen's one status region. The dialog, open or not, is the page wrapper's later sibling, so the screen's region
 * comes first in document order.
 */
function status(): HTMLElement {
  const [region] = screen.getAllByRole('status')
  if (region === undefined) throw new Error('no screen status region')
  return region
}

describe('SettingsScreen의 Bybit 연결 해제', () => {
  it('연결된 계정에 해제 조작이 있고 준비는 대상 연결만 본문으로 보낸다', async () => {
    await renderConnected()
    let body: unknown = null
    let csrf: string | null = null
    stubRevocationPrepareRequest((init) => {
      body = JSON.parse(init?.body as string)
      csrf = new Headers(init?.headers).get(CSRF_HEADER)
      return prepared()
    })
    fireEvent.click(screen.getByRole('button', { name: revocationContent.start }))

    const dialog = await screen.findByRole('dialog', { name: revocationContent.dialog.title })
    expect(body).toEqual({ connectionId: CONNECTION_ID })
    expect(csrf).toBe(CSRF_TOKEN)
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    for (const copy of revocationDialogCopyStrings) {
      expect(dialog.textContent).toContain(copy)
    }
    // 취소가 문서 처음이고 기본 초점이다.
    const cancel = within(dialog).getByRole('button', { name: revocationContent.dialog.cancel })
    expect(cancel).toHaveFocus()
    expect(within(dialog).getAllByRole('button')[0]).toBe(cancel)
    // 확인 문구·허가 참조는 화면에 그리지 않고 주소에도 싣지 않는다.
    expect(document.body.textContent).not.toContain(CONFIRMATION)
    expect(window.location.search).toBe('')
  })

  it('확정하면 자격정보 삭제 결과를 글자로 그리고 재연결 조작 대신 안내만 둔다', async () => {
    await renderConnected()
    const dialog = await openDialog()
    let request: { body: unknown; key: string | null; csrf: string | null } | null = null
    stubRevocationConfirmRequest((init) => {
      const headers = new Headers(init?.headers)
      request = {
        body: JSON.parse(init?.body as string),
        key: headers.get('Idempotency-Key'),
        csrf: headers.get(CSRF_HEADER),
      }
      return revokedResult()
    })
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.confirm }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(request).not.toBeNull()
    const sent = request as unknown as { body: unknown; key: string | null; csrf: string | null }
    expect(sent.body).toEqual({ connectionId: CONNECTION_ID, confirmation: CONFIRMATION })
    expect(sent.key).toMatch(/^[A-Za-z0-9-]{16,128}$/)
    expect(sent.csrf).toBe(CSRF_TOKEN)

    const section = connectionSection()
    expect(within(section).getByText(revocationContent.revoked)).toBeInTheDocument()
    expect(within(section).getByText(revocationContent.revokedBoundary)).toBeInTheDocument()
    expect(within(section).getByText(revocationContent.reconnectUnavailable)).toBeInTheDocument()
    expect(within(section).getByText(revocationContent.remoteKey)).toBeInTheDocument()
    expect(within(section).queryByRole('button')).not.toBeInTheDocument()
    // 결과는 화면의 live region 하나가 한 번 말하고 초점이 그 문장으로 간다(해제 버튼이 사라졌다).
    const notice = within(status()).getByText(revocationContent.revokedNotice)
    await waitFor(() => {
      expect(notice).toHaveFocus()
    })
    expect(screen.getAllByText(revocationContent.revokedNotice)).toHaveLength(1)
    expect(getObservedStorageEntries()).toEqual([])
    expect(getObservedRequests()).toEqual([])
    expect(window.location.search).toBe('')
    expect(document.body.textContent).not.toContain(sent.key ?? '-')
  })

  it('취소는 무장을 되돌리고 바뀐 것이 없다고 말한다', async () => {
    await renderConnected()
    const opener = screen.getByRole('button', { name: revocationContent.start })
    opener.focus()
    const dialog = await openDialog()
    stubRevocationCancelRequest(noContent)
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.cancel }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    await waitFor(() => {
      expect(getObservedAllowedRequests()).toContain(`POST ${BYBIT_REVOCATION_CANCEL_PATH}`)
    })
    expect(getObservedAllowedRequests()).not.toContain(`POST ${BYBIT_REVOCATION_CONFIRM_PATH}`)
    expect(within(status()).getByText(revocationContent.cancelled)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: revocationContent.start })).toBeInTheDocument()
  })

  it('Esc는 취소이고 Tab은 대화상자 안에 갇힌다', async () => {
    await renderConnected()
    const dialog = await openDialog()
    stubRevocationCancelRequest(noContent)
    const [first, last] = within(dialog).getAllByRole('button')
    if (first === undefined || last === undefined) throw new Error('two buttons')
    last.focus()
    fireEvent.keyDown(document, { key: 'Tab' })
    expect(first).toHaveFocus()
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true })
    expect(last).toHaveFocus()

    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(within(status()).getByText(revocationContent.cancelled)).toBeInTheDocument()
  })

  it('확정이 도는 동안 두 버튼이 막히고 Esc가 닫지 않는다', async () => {
    await renderConnected()
    const dialog = await openDialog()
    stubRevocationConfirmRequest(pendingResponse)
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.confirm }))

    const confirm = await within(dialog).findByRole('button', {
      name: revocationContent.dialog.confirm,
    })
    await waitFor(() => {
      expect(confirm).toHaveAttribute('aria-disabled', 'true')
    })
    expect(
      within(dialog).getByRole('button', { name: revocationContent.dialog.cancel }),
    ).toHaveAttribute('aria-disabled', 'true')
    expect(within(dialog).getByText(revocationContent.dialog.confirming)).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('응답을 받지 못하면 대화상자를 유지하고 같은 키로 다시 보낸다', async () => {
    await renderConnected()
    const dialog = await openDialog()
    const keys: (string | null)[] = []
    stubRevocationConfirmRequest((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      return keys.length === 1 ? Promise.reject(new TypeError('network')) : revokedResult()
    })
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.confirm }))
    expect(await within(dialog).findByText(revocationContent.dialog.uncertain)).toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.confirm }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(keys).toHaveLength(2)
    expect(keys[1]).toBe(keys[0])
    expect(within(connectionSection()).getByText(revocationContent.revoked)).toBeInTheDocument()
  })

  it('응답을 받지 못한 뒤 취소하면 결과를 단정하지 않고 상태를 다시 읽는다', async () => {
    await renderConnected()
    const dialog = await openDialog()
    stubRevocationConfirmRequest(() => Promise.reject(new TypeError('network')))
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.confirm }))
    await within(dialog).findByText(revocationContent.dialog.uncertain)
    stubRevocationCancelRequest(noContent)
    stubConnectionStatusRequest(revokedState)
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.cancel }))

    expect(await within(status()).findByText(revocationContent.unknown)).toBeInTheDocument()
    expect(
      await within(connectionSection()).findByText(revocationContent.revoked),
    ).toBeInTheDocument()
  })

  it('본인 확인이 없으면 대화상자 대신 본인 확인 안내를 말한다', async () => {
    await renderConnected()
    stubRevocationPrepareRequest(() =>
      Promise.resolve(
        jsonResponse({ code: 'REAUTHENTICATION_REQUIRED', message: 'x', correlationId: 'c' }, 403),
      ),
    )
    fireEvent.click(screen.getByRole('button', { name: revocationContent.start }))

    expect(
      await within(status()).findByText(revocationRejectionMessage('REAUTHENTICATION_REQUIRED')),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(getObservedAllowedRequests()).not.toContain(`POST ${BYBIT_REVOCATION_CONFIRM_PATH}`)
  })

  it('확정이 거절되면 대화상자를 닫고 원인을 말한 뒤 상태를 다시 읽는다', async () => {
    await renderConnected()
    const dialog = await openDialog()
    stubRevocationConfirmRequest(() =>
      Promise.resolve(
        jsonResponse({ code: 'REVOCATION_NOT_CONFIRMED', message: 'x', correlationId: 'c' }, 403),
      ),
    )
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.confirm }))

    expect(
      await within(status()).findByText(revocationRejectionMessage('REVOCATION_NOT_CONFIRMED')),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(within(connectionSection()).queryByText(revocationContent.revoked)).toBeNull()
  })

  it('이미 해제된 연결은 상태를 글자로 그리고 연결·해제 조작을 내지 않는다', async () => {
    stubConnectionStatusRequest(revokedState)
    render(<SettingsScreen />)

    const section = await screen.findByRole('region', { name: connectionContent.heading })
    expect(await within(section).findByText(revocationContent.revoked)).toBeInTheDocument()
    expect(within(section).getByText(revocationContent.reconnectUnavailable)).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: connectionContent.start })).toBeNull()
    expect(within(section).queryByRole('button', { name: revocationContent.start })).toBeNull()
    expect(getObservedAllowedRequests()).not.toContain(`POST ${BYBIT_REVOCATION_PATH}`)
  })

  it('서버가 공통 오류로 답한 5xx는 롤백된 실패다: 대화상자를 닫고 무장을 버리고 상태를 다시 읽는다', async () => {
    await renderConnected()
    const dialog = await openDialog()
    let confirms = 0
    stubRevocationConfirmRequest(() => {
      confirms += 1
      return Promise.resolve(
        jsonResponse({ code: 'INTERNAL_ERROR', message: 'x', correlationId: 'c' }, 500),
      )
    })
    stubRevocationCancelRequest(noContent)
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.confirm }))

    expect(
      await within(status()).findByText(revocationRejectionMessage('INTERNAL_ERROR')),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(confirms).toBe(1)
    await waitFor(() => {
      expect(getObservedAllowedRequests()).toContain(`POST ${BYBIT_REVOCATION_CANCEL_PATH}`)
    })
    expect(screen.getByRole('button', { name: revocationContent.start })).toBeInTheDocument()
  })

  it('본문 없는 5xx는 결과를 단정하지 않고 같은 키로 다시 보내게 둔다', async () => {
    await renderConnected()
    const dialog = await openDialog()
    stubRevocationConfirmRequest(() =>
      Promise.resolve(new Response('bad gateway', { status: 502 })),
    )
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.confirm }))

    expect(await within(dialog).findByText(revocationContent.dialog.uncertain)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(getObservedAllowedRequests()).not.toContain(`POST ${BYBIT_REVOCATION_CANCEL_PATH}`)
  })

  it('4xx 거절로 닫힐 때 서버 무장과 허가를 버린다', async () => {
    await renderConnected()
    const dialog = await openDialog()
    stubRevocationConfirmRequest(() =>
      Promise.resolve(
        jsonResponse({ code: 'CONNECTION_NOT_FOUND', message: 'x', correlationId: 'c' }, 404),
      ),
    )
    stubRevocationCancelRequest(noContent)
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.confirm }))

    await waitFor(() => {
      expect(getObservedAllowedRequests()).toContain(`POST ${BYBIT_REVOCATION_CANCEL_PATH}`)
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('대화상자 안의 status 하나가 진행과 무응답을 낭독한다', async () => {
    await renderConnected()
    const dialog = await openDialog()
    const region = within(dialog).getByRole('status')
    expect(region).toHaveAttribute('aria-live', 'polite')
    expect(region).toBeEmptyDOMElement()
    let answer: (value: Response) => void = () => undefined
    stubRevocationConfirmRequest(
      () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    )
    fireEvent.click(within(dialog).getByRole('button', { name: revocationContent.dialog.confirm }))
    expect(await within(region).findByText(revocationContent.dialog.confirming)).toBeInTheDocument()
    answer(new Response('bad gateway', { status: 502 }))
    expect(await within(region).findByText(revocationContent.dialog.uncertain)).toBeInTheDocument()
    expect(within(dialog).getAllByRole('status')).toHaveLength(1)
  })

  it('대화상자가 열려 있는 동안 배경 조작이 요청을 보내지 않는다', async () => {
    await renderConnected()
    await openDialog()
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.linkLabels.kakao }))
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.reauth.labels.google }))
    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    expect(getObservedRequests()).toEqual([])
    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toEqual([
      `POST ${BYBIT_REVOCATION_PATH}`,
    ])
  })

  it('연결 직후 새로고침 없이 해제 조작이 보인다', async () => {
    stubConnectionStatusRequest(() => Promise.resolve(jsonResponse({ connected: false })))
    stubConnectionPrepareRequest(() =>
      Promise.resolve(jsonResponse({ confirmation: 'connection-confirmation-phrase' })),
    )
    stubConnectRequest(() =>
      Promise.resolve(
        jsonResponse({
          connected: true,
          connectionId: CONNECTION_ID,
          revoked: false,
          verifiedAt: '2026-10-01T00:00:00Z',
          policyVersion: 'bybit-readonly-uta-1.0',
        }),
      ),
    )
    render(<SettingsScreen />)
    fireEvent.click(await screen.findByRole('button', { name: connectionContent.start }))
    await screen.findByRole('heading', { level: 3, name: connectionContent.form.heading })
    fireEvent.change(screen.getByLabelText(connectionContent.form.apiKeyLabel), {
      target: { value: 'uta2.100001' },
    })
    fireEvent.change(screen.getByLabelText(connectionContent.form.apiSecretLabel), {
      target: { value: `s-${Math.random().toString(36).slice(2)}` },
    })
    fireEvent.click(screen.getByRole('button', { name: connectionContent.form.submit }))

    expect(await screen.findByRole('button', { name: revocationContent.start })).toBeInTheDocument()
    expect(
      getObservedAllowedRequests().filter((entry) => entry === 'GET /api/connections/bybit'),
    ).toHaveLength(1)
    await openDialog()
  })

  it('해제 거절 code마다 안전한 문장이 있고 모르는 code는 하나로 접힌다', () => {
    for (const code of REVOCATION_REJECTION_CODES) {
      expect(revocationRejectionMessage(code)).not.toBe(revocationRejectionMessage('UNKNOWN_CODE'))
    }
    expect(revocationRejectionMessage(null)).toBe(revocationRejectionMessage('UNKNOWN_CODE'))
  })

  it('해제 카피는 성공·실패의 감정이나 보장 표현을 쓰지 않는다', () => {
    const all = [
      ...revocationDialogCopyStrings,
      revocationContent.revokedNotice,
      revocationContent.revokedBoundary,
      revocationContent.cancelled,
      revocationContent.unknown,
    ].join(' ')
    for (const forbidden of ['축하', '성공', '실패', '실시간', '정확', '보장', '안전합니다']) {
      expect(all).not.toContain(forbidden)
    }
  })
})
