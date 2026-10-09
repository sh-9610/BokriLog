import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { authContent } from '../content/auth'
import { planResultContent } from '../content/planResult'
import { serviceNoticeContent } from '../content/serviceNotice'
import { dashboardFixture } from '../test/planResultFixture'
import {
  getObservedStorageEntries,
  stubConnectionStatusRequest,
  stubDashboard,
  stubLinkStatusRequest,
  stubServiceNotices,
  stubSessionRequest,
} from '../test/setup'
import { SettingsScreen } from './SettingsScreen'
import { SignedInScreen } from './SignedInScreen'

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

/**
 * Story 3.5 AC-4: 대시보드와 설정 화면 상단의 서비스 공지 영역. 마지막 정상값(결과 패널) 밖에 있고, 화면의 단일 live
 * region을 늘리지 않으며, 재시도 버튼이 없다.
 */

const copy = serviceNoticeContent

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function session(destination: string): () => Promise<Response> {
  return () =>
    Promise.resolve(json({ authenticated: true, destination, csrfToken: 'notice-csrf-token' }))
}

function notices(): Promise<Response> {
  const now = Date.now()
  return Promise.resolve(
    json({
      asOf: new Date(now).toISOString(),
      expiresAt: new Date(now + 5 * 60_000).toISOString(),
      items: [
        {
          kind: 'NOTICE',
          incidentId: '7d0c2b1a-4e5f-4a6b-8c9d-0e1f2a3b4c5d',
          version: 1,
          status: 'INVESTIGATING',
          startedAt: '2026-10-10T00:00:00Z',
          affectedFeatures: ['SYNC'],
          continuingFeatures: ['NEW_CONNECTION', 'CALCULATION_DISPLAY', 'ADVERTISING'],
          message: '동기화 지연 원인 조사 중',
          nextUpdateAt: '2026-10-10T03:00:00Z',
          supportContact: 'support@bokrilog.test',
        },
      ],
    }),
  )
}

beforeEach(() => {
  window.history.pushState({}, '', '/dashboard')
})

describe('서비스 공지 영역의 배치', () => {
  it('대시보드는 공지를 상단에, 결과 패널 밖에 두고 live region은 하나다', async () => {
    stubSessionRequest(session('dashboard'))
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    stubServiceNotices(notices)
    render(<SignedInScreen />)

    const region = await screen.findByRole('region', { name: copy.heading })
    expect(within(region).getByText(`${copy.stateLabel}: 조사 중`)).toBeInTheDocument()
    const result = await screen.findByRole('heading', { level: 2, name: planResultContent.heading })
    // 결과 패널(마지막 정상값) 안에 있지 않고 그보다 앞이다.
    expect(within(region).queryByRole('heading', { name: planResultContent.heading })).toBeNull()
    expect(region.compareDocumentPosition(result) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // 제목·고지 인접 배치를 깨지 않는다.
    const title = screen.getByRole('heading', { level: 1 })
    expect(title.nextElementSibling?.textContent).toBe(authContent.disclaimer)
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('설정 화면도 상단에 같은 영역을 둔다', async () => {
    window.history.pushState({}, '', '/settings')
    stubSessionRequest(session('settings'))
    stubLinkStatusRequest(() => Promise.resolve(json({ providers: ['google'] })))
    stubConnectionStatusRequest(() => Promise.resolve(json({ connected: false })))
    stubServiceNotices(notices)
    render(<SettingsScreen />)

    const region = await screen.findByRole('region', { name: copy.heading })
    const settings = await screen.findByRole('heading', {
      level: 2,
      name: authContent.settings.heading,
    })
    expect(region.compareDocumentPosition(settings) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(within(region).getByRole('link', { name: 'support@bokrilog.test' })).toHaveAttribute(
      'href',
      'mailto:support@bokrilog.test',
    )
  })

  it('공지를 읽지 못하면 확인하지 못했다는 한 문장이고 다른 화면은 그대로다', async () => {
    stubSessionRequest(session('dashboard'))
    stubDashboard(() => Promise.resolve(json(dashboardFixture())))
    stubServiceNotices(() => Promise.resolve(json({ code: 'INTERNAL_ERROR' }, 500)))
    render(<SignedInScreen />)

    const region = await screen.findByRole('region', { name: copy.heading })
    expect(await within(region).findByText(copy.unavailable)).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { level: 2, name: planResultContent.heading }),
    ).toBeInTheDocument()
  })
})
