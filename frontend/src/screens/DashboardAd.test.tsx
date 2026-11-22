import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { showInterstitial } from '../ads/gptAdapter'
import { useDashboardAd } from '../ads/useDashboardAd'
import { CSRF_HEADER, DASHBOARD_PATH } from '../content/auth'
import { DASHBOARD_SYNC_RETURN_PATH, planSyncContent } from '../content/planSync'
import { dashboardFixture } from '../test/planResultFixture'
import {
  stubDashboard,
  stubDashboardAd,
  stubDashboardSync,
  stubSessionRequest,
} from '../test/setup'
import { SignedInScreen } from './SignedInScreen'

vi.mock('../ads/gptAdapter', () => ({ showInterstitial: vi.fn() }))

const UNIT = '/1234567/bokrilog_test'
const CSRF = 'dashboard-csrf'
const NOT_DUE = { outcome: 'NOT_DUE', job: null, retryAfterSeconds: 240 }
const action = { name: planSyncContent.action }

describe('대시보드 수동 동기화 광고', () => {
  let manual: RequestInit[]
  let automatic: RequestInit[]

  beforeEach(() => {
    window.history.replaceState({}, '', DASHBOARD_PATH)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    vi.mocked(showInterstitial).mockReset()
    manual = []
    automatic = []
    stubSessionRequest(() =>
      Promise.resolve(
        Response.json({ authenticated: true, destination: 'dashboard', csrfToken: CSRF }),
      ),
    )
    stubDashboard(() => Promise.resolve(Response.json(dashboardFixture())))
    stubDashboardSync((init) => {
      const body =
        typeof init?.body === 'string' ? (JSON.parse(init.body) as { trigger: string }) : null
      if (body?.trigger === 'MANUAL') {
        if (init !== undefined) manual.push(init)
        return new Promise(() => undefined)
      }
      if (init !== undefined) automatic.push(init)
      return Promise.resolve(Response.json(NOT_DUE))
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    window.history.replaceState({}, '', '/')
  })

  it('다른 링크는 제외하고 준비된 일반 링크 하나가 수동 POST를 한 건 보낸다', async () => {
    stubDashboardAd(() => Promise.resolve(Response.json({ adUnitPath: UNIT })))
    const release = vi.fn()
    vi.mocked(showInterstitial).mockReturnValue(release)
    render(<SignedInScreen />)
    const button = await screen.findByRole('button', action)
    button.focus()
    await waitFor(() => {
      expect(showInterstitial).toHaveBeenCalledWith(
        UNIT,
        expect.any(Function),
        expect.any(Function),
      )
    })
    expect(manual).toHaveLength(0)
    expect(automatic).toHaveLength(1)
    act(() => vi.mocked(showInterstitial).mock.calls[0]?.[2]())

    const link = screen.getByRole('link', action)
    expect(link).toHaveAttribute('href', DASHBOARD_SYNC_RETURN_PATH)
    expect(link).toHaveFocus()
    for (const item of screen.getAllByRole('link').filter((item) => item !== link)) {
      expect(item).toHaveAttribute('data-google-interstitial', 'false')
    }

    fireEvent.click(link, { ctrlKey: true })
    expect(manual).toHaveLength(0)

    // GPT의 문서 리스너 위치에서 기본 이동이 열려 있는지 관측한 뒤 jsdom의 이동만 막는다.
    const defaults: boolean[] = []
    const observe = (event: Event) => {
      defaults.push(event.defaultPrevented)
      event.preventDefault()
    }
    document.addEventListener('click', observe)
    try {
      act(() => {
        link.click()
        link.click()
      })
    } finally {
      document.removeEventListener('click', observe)
    }
    expect(defaults).toEqual([false])
    expect(manual).toHaveLength(1)
    expect(manual[0]?.keepalive).toBe(true)
    expect(new Headers(manual[0]?.headers).get(CSRF_HEADER)).toBe(CSRF)
    expect(new Headers(manual[0]?.headers).has('Idempotency-Key')).toBe(true)
    expect(screen.getByRole('button', action)).toHaveFocus()
  })

  it('운영 중단이면 GPT를 부르지 않고 기존 버튼으로 동기화한다', async () => {
    render(<SignedInScreen />)
    fireEvent.click(await screen.findByRole('button', action))
    expect(showInterstitial).not.toHaveBeenCalled()
    expect(manual).toHaveLength(1)
    expect(manual[0]?.keepalive).toBeUndefined()
  })

  it.each(['BLOCKED', 'NO_FILL', 'TIMEOUT'] as const)(
    '%s 뒤에는 버튼으로 동기화한다',
    async (reason) => {
      stubDashboardAd(() => Promise.resolve(Response.json({ adUnitPath: UNIT })))
      vi.mocked(showInterstitial).mockReturnValue(vi.fn())
      render(<SignedInScreen />)
      await waitFor(() => {
        expect(showInterstitial).toHaveBeenCalledTimes(1)
      })
      act(() => vi.mocked(showInterstitial).mock.calls[0]?.[1](reason))
      fireEvent.click(screen.getByRole('button', action))
      expect(manual).toHaveLength(1)
      expect(manual[0]?.keepalive).toBeUndefined()
    },
  )

  it('열린 탭에서 운영 중단을 확인하면 기존 슬롯을 파기하고 버튼으로 돌아간다', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] })
    let active = true
    stubDashboardAd(() =>
      Promise.resolve(
        active ? Response.json({ adUnitPath: UNIT }) : new Response(null, { status: 204 }),
      ),
    )
    const release = vi.fn()
    vi.mocked(showInterstitial).mockReturnValue(release)
    render(<SignedInScreen />)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(showInterstitial).toHaveBeenCalledTimes(1)
    act(() => vi.mocked(showInterstitial).mock.calls[0]?.[2]())
    expect(screen.getByRole('link', action)).toBeInTheDocument()

    active = false
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000)
    })
    expect(release).toHaveBeenCalledTimes(1)
    expect(showInterstitial).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', action))
    expect(manual).toHaveLength(1)
    expect(manual[0]?.keepalive).toBeUndefined()
  })

  it('같은 세션을 다시 조회해도 파기한 슬롯의 준비 상태는 사용하지 않는다', async () => {
    stubDashboardAd(() => Promise.resolve(Response.json({ adUnitPath: UNIT })))
    const release = vi.fn()
    vi.mocked(showInterstitial).mockReturnValue(release)
    const { result, rerender } = renderHook(({ enabled }) => useDashboardAd(enabled, CSRF), {
      initialProps: { enabled: true },
    })
    await waitFor(() => {
      expect(showInterstitial).toHaveBeenCalledTimes(1)
    })
    act(() => vi.mocked(showInterstitial).mock.calls[0]?.[2]())
    expect(result.current).not.toBeNull()

    rerender({ enabled: false })
    expect(release).toHaveBeenCalledTimes(1)
    rerender({ enabled: true })
    expect(result.current).toBeNull()
    await waitFor(() => {
      expect(showInterstitial).toHaveBeenCalledTimes(2)
    })
    act(() => vi.mocked(showInterstitial).mock.calls[1]?.[2]())
    expect(result.current).not.toBeNull()
  })

  it('캐시 복원과 직접 복귀 주소에서는 수동 작업이 시작되지 않는다', async () => {
    window.history.replaceState({}, '', DASHBOARD_SYNC_RETURN_PATH)
    stubDashboardAd(() => Promise.resolve(Response.json({ adUnitPath: UNIT })))
    const release = vi.fn()
    vi.mocked(showInterstitial).mockReturnValue(release)
    render(<SignedInScreen />)
    await waitFor(() => {
      expect(showInterstitial).toHaveBeenCalledTimes(1)
    })
    act(() => vi.mocked(showInterstitial).mock.calls[0]?.[2]())
    expect(screen.getByRole('link', action)).toBeInTheDocument()
    act(() => {
      window.dispatchEvent(new Event('pagehide'))
      window.dispatchEvent(new Event('pageshow'))
    })
    expect(release).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', action)).toBeInTheDocument()
    expect(manual).toHaveLength(0)
    expect(automatic).toHaveLength(0)
  })
})
