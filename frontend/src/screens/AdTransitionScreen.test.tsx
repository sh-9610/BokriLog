import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'

import { showInterstitial } from '../ads/gptAdapter'
import {
  AD_CLAIM_WINDOW_SECONDS,
  AD_TRANSITION_PATH,
  adTransitionContent,
  adTransitionCopyStrings,
} from '../content/adTransition'
import { authContent, CSRF_HEADER, DASHBOARD_PATH } from '../content/auth'
import { containsForbiddenCopy } from '../content/copyRules'
import { replaceAddress, replaceLocation } from '../session/navigation'
import { SESSION_REQUEST_TIMEOUT_MS } from '../session/useSessionState'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubAdTransitionClaim,
  stubSessionRequest,
} from '../test/setup'
import { AdTransitionScreen } from './AdTransitionScreen'

vi.mock('../session/navigation', () => ({
  navigateTo: vi.fn(),
  replaceAddress: vi.fn(),
  replaceLocation: vi.fn(),
}))
vi.mock('../ads/gptAdapter', () => ({ showInterstitial: vi.fn() }))

/**
 * Story 4.3·4.4: 금융정보 없는 중립 전환 화면. GPT adapter는 대역이다 — 이 시험은 화면이 adapter를 언제 부르고 언제 부르지 않는지,
 * 그리고 DOM·주소·제목에 무엇이 없는지를 본다.
 */

/** 참조 모양 값은 리터럴로 쓰지 않는다(gitleaks) — 반복으로 만든 43자다. */
const TRANSITION = `${'Zy'.repeat(21)}-`
const AD_UNIT_PATH = '/1234567/bokrilog_test'
const TOKEN = 'ad-transition-csrf-token'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function signedIn(): void {
  stubSessionRequest(() =>
    Promise.resolve(json({ authenticated: true, destination: 'dashboard', csrfToken: TOKEN })),
  )
}

function claims(...answers: (() => Promise<Response>)[]): (RequestInit | undefined)[] {
  const inits: (RequestInit | undefined)[] = []
  stubAdTransitionClaim((init) => {
    inits.push(init)
    const next = answers.shift()
    if (next === undefined) throw new Error('an unexpected claim')
    return next()
  })
  return inits
}

function claimCount(): number {
  return getObservedAllowedRequests().filter((entry) => entry.startsWith('POST /api/engagement'))
    .length
}

function open(search: string): void {
  window.history.pushState({}, '', AD_TRANSITION_PATH + search)
}

const originalTitle = document.title

/** adapter가 돌려주는 취소(Story 4.4). 화면이 떠날 때 부르는지 본다. */
const cancelAd = vi.fn()

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  vi.mocked(replaceAddress).mockClear()
  cancelAd.mockReset()
  vi.mocked(showInterstitial).mockReset()
  vi.mocked(showInterstitial).mockReturnValue(cancelAd)
  document.title = '복리 계획 비교 · BokriLog'
})

afterEach(() => {
  window.history.pushState({}, '', '/')
  document.title = originalTitle
})

describe('중립 전환 화면: 진입', () => {
  it('참조를 메모리로 옮기고 주소를 지운 뒤, 첫 조작인 광고 닫기에 최초 포커스를 둔다', async () => {
    signedIn()
    claims(() => new Promise(() => undefined))
    open(`?t=${TRANSITION}`)

    render(<AdTransitionScreen />)

    expect(replaceAddress).toHaveBeenCalledWith(AD_TRANSITION_PATH)
    const close = screen.getByRole('button', { name: adTransitionContent.close })
    expect(close).toHaveFocus()
    // DOM 순서: 광고 표지와 닫기가 첫 조작이고, 그 뒤에 제목·문장·결과 보기가 온다.
    // eslint-disable-next-line testing-library/no-node-access -- 문서 순서의 첫 조작은 역할 조회로 알 수 없다. 조작 요소를 문서 순서대로 모아야 한다.
    const focusables = document.querySelectorAll('a, button')
    expect(focusables[0]).toBe(close)
    expect(screen.getByText(adTransitionContent.adLabel).compareDocumentPosition(close)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    )
    const heading = screen.getByRole('heading', { level: 1, name: adTransitionContent.heading })
    expect(close.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText(adTransitionContent.body)).toBeInTheDocument()
    const view = screen.getByRole('link', { name: adTransitionContent.viewResult })
    expect(view).toHaveAttribute('href', DASHBOARD_PATH)
    // 복리 계획 비교와 비추천 고지를 인접 배치한다.
    const title = screen.getByText(authContent.pageTitle)
    // eslint-disable-next-line testing-library/no-node-access -- "바로 다음 요소"라는 인접 배치는 형제 관계로만 확인할 수 있다.
    expect(title.nextElementSibling).toHaveTextContent(authContent.disclaimer)
    await waitFor(() => {
      expect(claimCount()).toBe(1)
    })
    expect(replaceLocation).not.toHaveBeenCalled()
  })

  it.each([
    ['참조 없음', ''],
    ['빈 참조', '?t='],
    ['모양이 아닌 참조', '?t=abc'],
    ['43자가 아닌 참조', `?t=${TRANSITION}x`],
    ['base64url 밖의 글자', `?t=${TRANSITION.slice(1)}=`],
  ])('%s는 요청 0건으로 결과로 간다(새로고침·뒤로 가기 재진입)', (_name, search) => {
    open(search)

    render(<AdTransitionScreen />)

    expect(replaceAddress).toHaveBeenCalledWith(AD_TRANSITION_PATH)
    expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    expect(getObservedAllowedRequests()).toEqual([])
    expect(getObservedRequests()).toEqual([])
    expect(showInterstitial).not.toHaveBeenCalled()
  })
})

describe('중립 전환 화면: 소비', () => {
  it('SHOW면 세션 토큰으로 한 번 소비하고 그 단위 경로로 adapter를 부른다', async () => {
    signedIn()
    const inits = claims(() => Promise.resolve(json({ outcome: 'SHOW', adUnitPath: AD_UNIT_PATH })))
    open(`?t=${TRANSITION}`)

    render(<AdTransitionScreen />)

    await waitFor(() => {
      expect(showInterstitial).toHaveBeenCalledTimes(1)
    })
    expect(vi.mocked(showInterstitial).mock.calls[0]?.[0]).toBe(AD_UNIT_PATH)
    expect(inits).toHaveLength(1)
    expect(JSON.parse(inits[0]?.body as string)).toEqual({ transition: TRANSITION })
    expect((inits[0]?.headers as Record<string, string>)[CSRF_HEADER]).toBe(TOKEN)
    expect(inits[0]?.credentials).toBe('same-origin')
    expect(inits[0]?.cache).toBe('no-store')
    // 주소는 adapter보다 먼저 지워졌다.
    expect(vi.mocked(replaceAddress).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(showInterstitial).mock.invocationCallOrder[0] ?? 0,
    )
    expect(replaceLocation).not.toHaveBeenCalled()
    expect(getObservedStorageEntries()).toEqual([])
  })

  it.each([
    'CONSUMED',
    'EXPIRED',
    'CONSENT_REQUIRED',
    'RESTRICTED',
    'PROVIDER_DISABLED',
    'NEW_REASON',
  ])('SKIP %s는 adapter 없이 결과로 간다', async (reason) => {
    signedIn()
    claims(() => Promise.resolve(json({ outcome: 'SKIP', reason })))
    open(`?t=${TRANSITION}`)

    render(<AdTransitionScreen />)

    await waitFor(() => {
      expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    })
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(showInterstitial).not.toHaveBeenCalled()
  })

  it.each([
    [
      '401',
      () =>
        Promise.resolve(json({ code: 'UNAUTHENTICATED', message: 'x', correlationId: 'c' }, 401)),
    ],
    [
      '403',
      () => Promise.resolve(json({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403)),
    ],
    [
      '404',
      () => Promise.resolve(json({ code: 'NOT_FOUND', message: 'x', correlationId: 'c' }, 404)),
    ],
    [
      '500',
      () =>
        Promise.resolve(json({ code: 'INTERNAL_ERROR', message: 'x', correlationId: 'c' }, 500)),
    ],
    ['네트워크 실패', () => Promise.reject(new Error('network'))],
    [
      '모양이 아닌 SHOW',
      () => Promise.resolve(json({ outcome: 'SHOW', adUnitPath: 'https://x.test/1/a' })),
    ],
  ] as const)('%s는 adapter 없이 결과로 간다', async (_name, answer) => {
    signedIn()
    claims(answer)
    open(`?t=${TRANSITION}`)

    render(<AdTransitionScreen />)

    await waitFor(() => {
      expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    })
    expect(showInterstitial).not.toHaveBeenCalled()
  })

  it('미인증 세션은 소비 없이 결과로 간다', async () => {
    stubSessionRequest(() => Promise.resolve(json({ authenticated: false, destination: 'entry' })))
    open(`?t=${TRANSITION}`)

    render(<AdTransitionScreen />)

    await waitFor(() => {
      expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    })
    expect(claimCount()).toBe(0)
    expect(getObservedRequests()).toEqual([])
    expect(showInterstitial).not.toHaveBeenCalled()
  })

  it('본인 확인이 필요한 세션은 소비 없이 결과로 간다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        json({ authenticated: false, csrfToken: 'reauth-token', reauthRequired: true }),
      ),
    )
    open(`?t=${TRANSITION}`)

    render(<AdTransitionScreen />)

    await waitFor(() => {
      expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    })
    await new Promise((settle) => setTimeout(settle, 20))
    expect(claimCount()).toBe(0)
    expect(getObservedRequests()).toEqual([])
    expect(showInterstitial).not.toHaveBeenCalled()
  })

  it('세션 확인이 끝나지 않아 시한 안에 소비가 시작되지 않으면 결과로 간다', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      // 세션 확인이 답하지 않는다(중단 신호에도). 소비 요청은 시작되지 않는다.
      stubSessionRequest(() => new Promise(() => undefined))
      open(`?t=${TRANSITION}`)

      render(<AdTransitionScreen />)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(SESSION_REQUEST_TIMEOUT_MS - 1)
      })
      expect(replaceLocation).not.toHaveBeenCalled()
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1)
      })
      expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
      expect(replaceLocation).toHaveBeenCalledTimes(1)
      expect(claimCount()).toBe(0)
      expect(showInterstitial).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('소비가 시한 안에 시작되면 화면 시한은 떠나게 하지 않는다', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      signedIn()
      claims(() => new Promise(() => undefined))
      open(`?t=${TRANSITION}`)

      render(<AdTransitionScreen />)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
      expect(claimCount()).toBe(1)
      await act(async () => {
        await vi.advanceTimersByTimeAsync(SESSION_REQUEST_TIMEOUT_MS)
      })
      // 시작된 소비는 자기 시한(`claimAdTransition`)이 끝낸다. 이 대역은 중단 신호에도 답하지 않으므로, 화면 시한이 떠나게 했다면
      // 여기서 이동이 보였을 것이다.
      expect(replaceLocation).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('adapter가 실패를 알리면(null slot·script 오류) 결과로 간다', async () => {
    vi.mocked(showInterstitial).mockImplementation((_path, onFailure) => {
      onFailure()
      return cancelAd
    })
    signedIn()
    claims(() => Promise.resolve(json({ outcome: 'SHOW', adUnitPath: AD_UNIT_PATH })))
    open(`?t=${TRANSITION}`)

    render(<AdTransitionScreen />)

    await waitFor(() => {
      expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    })
    expect(showInterstitial).toHaveBeenCalledTimes(1)
  })

  it('adapter의 늦은 실패(시간초과·미채움)는 결과로 한 번 가고, 그 뒤의 실패·닫기는 두 번째 이동을 만들지 않는다', async () => {
    let failLater: () => void = () => undefined
    vi.mocked(showInterstitial).mockImplementation((_path, onFailure) => {
      failLater = onFailure
      return cancelAd
    })
    signedIn()
    claims(() => Promise.resolve(json({ outcome: 'SHOW', adUnitPath: AD_UNIT_PATH })))
    open(`?t=${TRANSITION}`)
    render(<AdTransitionScreen />)
    await waitFor(() => {
      expect(showInterstitial).toHaveBeenCalledTimes(1)
    })
    expect(replaceLocation).not.toHaveBeenCalled()

    act(() => {
      failLater()
    })
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    // 떠날 때 이동보다 먼저 취소를 부른다(adapter는 이미 실패로 종단했으므로 실제 adapter에서는 아무것도 하지 않는다).
    expect(cancelAd).toHaveBeenCalledTimes(1)
    expect(cancelAd.mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(replaceLocation).mock.invocationCallOrder[0] ?? 0,
    )

    // 늦은 callback이 다시 실패를 알리거나 사용자가 닫아도 이동은 한 번이고 광고를 다시 시도하지 않는다.
    act(() => {
      failLater()
    })
    fireEvent.click(screen.getByRole('button', { name: adTransitionContent.close }))
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(showInterstitial).toHaveBeenCalledTimes(1)
    expect(claimCount()).toBe(1)
    expect(getObservedRequests()).toEqual([])
  })
})

describe('중립 전환 화면: 결과 보기·복원·언마운트', () => {
  async function showing(): Promise<{ failLater: () => void; unmount: () => void }> {
    let onFailure: () => void = () => undefined
    vi.mocked(showInterstitial).mockImplementation((_path, failure) => {
      onFailure = failure
      return cancelAd
    })
    signedIn()
    claims(() => Promise.resolve(json({ outcome: 'SHOW', adUnitPath: AD_UNIT_PATH })))
    open(`?t=${TRANSITION}`)
    const { unmount } = render(<AdTransitionScreen />)
    await waitFor(() => {
      expect(showInterstitial).toHaveBeenCalledTimes(1)
    })
    return {
      failLater: () => {
        onFailure()
      },
      unmount,
    }
  }

  it('결과 보기를 누른 뒤의 늦은 실패·닫기는 두 번째 이동을 만들지 않고, anchor의 기본 이동을 막지 않으며 광고를 취소하지 않는다', async () => {
    const { failLater } = await showing()
    const view = screen.getByRole('link', { name: adTransitionContent.viewResult })

    // jsdom은 anchor 이동을 하지 않는다 — 기본 동작이 막히지 않았는지만 본다.
    const notPrevented = fireEvent.click(view)
    expect(notPrevented).toBe(true)
    act(() => {
      failLater()
    })
    fireEvent.click(screen.getByRole('button', { name: adTransitionContent.close }))

    expect(replaceLocation).not.toHaveBeenCalled()
    expect(cancelAd).not.toHaveBeenCalled()
  })

  it('bfcache로 복원되면(persisted) 광고를 취소하고 결과로 간다', async () => {
    await showing()
    const view = screen.getByRole('link', { name: adTransitionContent.viewResult })
    // 결과 보기로 떠났던 문서가 뒤로 가기로 복원된다 — 떠나는 표시와 무관하게 간다.
    fireEvent.click(view)

    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    })

    expect(cancelAd).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    expect(cancelAd.mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(replaceLocation).mock.invocationCallOrder[0] ?? 0,
    )
  })

  it('복원이 아닌 pageshow는 아무것도 하지 않는다', async () => {
    await showing()

    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: false }))
    })

    expect(cancelAd).not.toHaveBeenCalled()
    expect(replaceLocation).not.toHaveBeenCalled()
  })

  it('언마운트되면 광고를 취소하고 pageshow 리스너를 뗀다', async () => {
    const { unmount } = await showing()

    unmount()
    expect(cancelAd).toHaveBeenCalledTimes(1)
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))

    expect(cancelAd).toHaveBeenCalledTimes(1)
    expect(replaceLocation).not.toHaveBeenCalled()
  })
})

describe('중립 전환 화면: 준비된 광고의 수명 상한', () => {
  /** 서버 소비 창(ms). 화면은 문서 시작(`performance.now()` 0)부터 센다. */
  const WINDOW_MS = AD_CLAIM_WINDOW_SECONDS * 1000

  /** 문서 나이(ms). `performance.now`는 가짜 시계가 아니다 — 마운트 시점의 값을 고정한다(기본은 방금 시작한 문서). */
  let documentAge: MockInstance<() => number>

  beforeEach(() => {
    documentAge = vi.spyOn(performance, 'now').mockReturnValue(0)
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  /** 가짜 시계 아래에서는 `waitFor`가 돌지 않는다 — 응답·effect 연쇄를 microtask로 흘린다. */
  async function settle(): Promise<void> {
    for (let turn = 0; turn < 10; turn += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
    }
  }

  async function advance(ms: number): Promise<void> {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ms)
    })
  }

  function showingAd(): { failLater: () => void } {
    let onFailure: () => void = () => undefined
    vi.mocked(showInterstitial).mockImplementation((_path, failure) => {
      onFailure = failure
      return cancelAd
    })
    signedIn()
    claims(() => Promise.resolve(json({ outcome: 'SHOW', adUnitPath: AD_UNIT_PATH })))
    open(`?t=${TRANSITION}`)
    return {
      failLater: () => {
        onFailure()
      },
    }
  }

  it('창 안에서 결과 보기를 눌렀으면 상한이 와도 광고를 취소하지 않고 이동하지 않는다', async () => {
    showingAd()
    render(<AdTransitionScreen />)
    await settle()
    expect(showInterstitial).toHaveBeenCalledTimes(1)

    await advance(WINDOW_MS - 1)
    const notPrevented = fireEvent.click(
      screen.getByRole('link', { name: adTransitionContent.viewResult }),
    )
    expect(notPrevented).toBe(true)
    await advance(WINDOW_MS)

    expect(cancelAd).not.toHaveBeenCalled()
    expect(replaceLocation).not.toHaveBeenCalled()
  })

  it('준비된 채 상한까지 누르지 않으면 광고를 한 번 취소한 뒤 결과로 한 번 간다', async () => {
    showingAd()
    render(<AdTransitionScreen />)
    await settle()
    expect(showInterstitial).toHaveBeenCalledTimes(1)

    await advance(WINDOW_MS - 1)
    expect(cancelAd).not.toHaveBeenCalled()
    expect(replaceLocation).not.toHaveBeenCalled()
    await advance(1)

    expect(cancelAd).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    expect(cancelAd.mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(replaceLocation).mock.invocationCallOrder[0] ?? 0,
    )

    // 그 뒤의 닫기·시간은 두 번째 이동을 만들지 않는다.
    fireEvent.click(screen.getByRole('button', { name: adTransitionContent.close }))
    await advance(WINDOW_MS)
    expect(cancelAd).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(showInterstitial).toHaveBeenCalledTimes(1)
    expect(claimCount()).toBe(1)
  })

  it('남은 시간만큼만 기다린다 — 늦게 열린 문서는 문서 시작 기준의 상한에 떠난다', async () => {
    documentAge.mockReturnValue(WINDOW_MS - 1_000)
    showingAd()
    render(<AdTransitionScreen />)
    await settle()
    expect(showInterstitial).toHaveBeenCalledTimes(1)

    await advance(999)
    expect(replaceLocation).not.toHaveBeenCalled()
    await advance(1)

    expect(cancelAd).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
  })

  it('소비 응답이 상한 뒤에 오면 SHOW여도 adapter를 부르지 않고 이동은 한 번이다', async () => {
    signedIn()
    let answer: (response: Response) => void = () => undefined
    claims(
      () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    )
    open(`?t=${TRANSITION}`)
    render(<AdTransitionScreen />)
    await settle()
    expect(claimCount()).toBe(1)

    // 이 대역은 중단 신호에도 답하지 않는다 — 소비 요청 자기 시한이 아니라 상한이 떠나게 한다.
    await advance(WINDOW_MS - 1)
    expect(replaceLocation).not.toHaveBeenCalled()
    await advance(1)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)

    answer(json({ outcome: 'SHOW', adUnitPath: AD_UNIT_PATH }))
    await settle()

    expect(showInterstitial).not.toHaveBeenCalled()
    expect(replaceLocation).toHaveBeenCalledTimes(1)
  })

  it('창이 이미 지난 문서는 마운트 커밋에서 주소를 지운 뒤 바로 결과로 가고 소비하지 않는다', async () => {
    documentAge.mockReturnValue(WINDOW_MS)
    showingAd()

    render(<AdTransitionScreen />)

    // 마운트 커밋에서 이미 떠났다 — 시계를 흘리지 않았다.
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    expect(replaceAddress).toHaveBeenCalledWith(AD_TRANSITION_PATH)
    expect(vi.mocked(replaceAddress).mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(replaceLocation).mock.invocationCallOrder[0] ?? 0,
    )
    await settle()
    await advance(WINDOW_MS)
    expect(claimCount()).toBe(0)
    expect(showInterstitial).not.toHaveBeenCalled()
    expect(replaceLocation).toHaveBeenCalledTimes(1)
  })

  it('창이 이미 지난 문서를 StrictMode로 렌더해도 이동은 한 번이다', async () => {
    documentAge.mockReturnValue(WINDOW_MS)
    showingAd()

    render(
      <StrictMode>
        <AdTransitionScreen />
      </StrictMode>,
    )

    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    await settle()
    await advance(WINDOW_MS)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(claimCount()).toBe(0)
    expect(showInterstitial).not.toHaveBeenCalled()
  })

  it.each([
    ['Ctrl', { ctrlKey: true }],
    ['Cmd', { metaKey: true }],
    ['Shift', { shiftKey: true }],
    ['Alt', { altKey: true }],
    ['가운데 버튼', { button: 1 }],
  ] as const)(
    '%s 클릭은 떠남이 아니다 — 이 탭은 남으므로 상한에 광고를 한 번 취소하고 한 번 이동한다',
    async (_name, init) => {
      showingAd()
      render(<AdTransitionScreen />)
      await settle()
      expect(showInterstitial).toHaveBeenCalledTimes(1)

      const notPrevented = fireEvent.click(
        screen.getByRole('link', { name: adTransitionContent.viewResult }),
        init,
      )
      expect(notPrevented).toBe(true)
      expect(replaceLocation).not.toHaveBeenCalled()
      await advance(WINDOW_MS)

      expect(cancelAd).toHaveBeenCalledTimes(1)
      expect(replaceLocation).toHaveBeenCalledTimes(1)
      expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
      expect(cancelAd.mock.invocationCallOrder[0]).toBeLessThan(
        vi.mocked(replaceLocation).mock.invocationCallOrder[0] ?? 0,
      )
    },
  )

  it('창이 지난 시점의 결과 보기 클릭은(상한 타이머가 늦었다) 기본 이동을 막고 광고를 취소한 뒤 한 번 이동한다', async () => {
    showingAd()
    render(<AdTransitionScreen />)
    await settle()
    expect(showInterstitial).toHaveBeenCalledTimes(1)

    // 백그라운드 탭 스로틀링·절전으로 상한 타이머가 아직 돌지 않았다 — 시계를 흘리지 않고 문서 나이만 창을 넘긴다.
    documentAge.mockReturnValue(WINDOW_MS)
    const notPrevented = fireEvent.click(
      screen.getByRole('link', { name: adTransitionContent.viewResult }),
    )

    expect(notPrevented).toBe(false)
    expect(cancelAd).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    expect(cancelAd.mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(replaceLocation).mock.invocationCallOrder[0] ?? 0,
    )

    // 늦게 도는 상한 타이머·다시 누른 클릭은 두 번째 이동을 만들지 않는다.
    await advance(WINDOW_MS)
    fireEvent.click(screen.getByRole('link', { name: adTransitionContent.viewResult }))
    expect(cancelAd).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
  })

  it.each([
    [
      '닫기',
      async (): Promise<void> => {
        showingAd()
        render(<AdTransitionScreen />)
        await settle()
        fireEvent.click(screen.getByRole('button', { name: adTransitionContent.close }))
      },
      1,
    ],
    [
      'adapter 실패',
      async (): Promise<void> => {
        const { failLater } = showingAd()
        render(<AdTransitionScreen />)
        await settle()
        act(() => {
          failLater()
        })
      },
      1,
    ],
    [
      '건너뜀',
      async (): Promise<void> => {
        signedIn()
        claims(() => Promise.resolve(json({ outcome: 'SKIP', reason: 'CONSUMED' })))
        open(`?t=${TRANSITION}`)
        render(<AdTransitionScreen />)
        await settle()
      },
      0,
    ],
  ] as const)(
    '%s로 이미 떠났으면 상한은 두 번째 이동을 만들지 않는다',
    async (_name, conclude, cancels) => {
      await conclude()
      expect(replaceLocation).toHaveBeenCalledTimes(1)

      await advance(WINDOW_MS)

      expect(replaceLocation).toHaveBeenCalledTimes(1)
      expect(cancelAd).toHaveBeenCalledTimes(cancels)
    },
  )

  it('StrictMode 이중 실행에서도 상한의 이동은 한 번이고, 언마운트 뒤 남는 타이머가 없다', async () => {
    showingAd()
    const { unmount } = render(
      <StrictMode>
        <AdTransitionScreen />
      </StrictMode>,
    )
    await settle()
    expect(showInterstitial).toHaveBeenCalledTimes(1)

    await advance(WINDOW_MS)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(cancelAd).toHaveBeenCalledTimes(1)

    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('상한 전에 언마운트되면 상한 타이머가 남지 않는다', async () => {
    showingAd()
    const { unmount } = render(
      <StrictMode>
        <AdTransitionScreen />
      </StrictMode>,
    )
    await settle()

    unmount()
    expect(vi.getTimerCount()).toBe(0)
    await advance(WINDOW_MS)
    expect(replaceLocation).not.toHaveBeenCalled()
  })
})

describe('중립 전환 화면: 광고 닫기', () => {
  it('소비 응답 전에도 결과로 가고, 늦게 온 SHOW는 adapter를 부르지 않는다', async () => {
    signedIn()
    let answer: (response: Response) => void = () => undefined
    claims(
      () =>
        new Promise<Response>((resolve) => {
          answer = resolve
        }),
    )
    open(`?t=${TRANSITION}`)
    render(<AdTransitionScreen />)
    await waitFor(() => {
      expect(claimCount()).toBe(1)
    })

    fireEvent.click(screen.getByRole('button', { name: adTransitionContent.close }))
    expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)

    await act(async () => {
      answer(json({ outcome: 'SHOW', adUnitPath: AD_UNIT_PATH }))
      await Promise.resolve()
    })
    await new Promise((settle) => setTimeout(settle, 20))
    expect(showInterstitial).not.toHaveBeenCalled()
    expect(replaceLocation).toHaveBeenCalledTimes(1)
  })

  it('SHOW 화면에서 닫으면 interstitial 없이 결과로 간다', async () => {
    signedIn()
    claims(() => Promise.resolve(json({ outcome: 'SHOW', adUnitPath: AD_UNIT_PATH })))
    open(`?t=${TRANSITION}`)
    render(<AdTransitionScreen />)
    await waitFor(() => {
      expect(showInterstitial).toHaveBeenCalledTimes(1)
    })

    expect(cancelAd).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: adTransitionContent.close }))

    // 이동보다 먼저 광고를 취소한다(Story 4.4) — 리스너·시한을 떼고 slot을 파기한다.
    expect(cancelAd).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledWith(DASHBOARD_PATH)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
    expect(cancelAd.mock.invocationCallOrder[0]).toBeLessThan(
      vi.mocked(replaceLocation).mock.invocationCallOrder[0] ?? 0,
    )

    // 다시 눌러도 이동·취소는 한 번이다.
    fireEvent.click(screen.getByRole('button', { name: adTransitionContent.close }))
    expect(cancelAd).toHaveBeenCalledTimes(1)
    expect(replaceLocation).toHaveBeenCalledTimes(1)
  })
})

describe('중립 전환 화면: 데이터 경계', () => {
  it('결과 보기 외의 anchor는 interstitial trigger가 아니다', () => {
    signedIn()
    claims(() => new Promise(() => undefined))
    open(`?t=${TRANSITION}`)

    render(<AdTransitionScreen />)

    // eslint-disable-next-line testing-library/no-node-access -- href 없는 anchor까지 모두 훑어야 한다. 역할 조회는 href 있는 anchor만 link로 본다.
    const anchors = [...document.querySelectorAll('a')]
    const view = screen.getByRole('link', { name: adTransitionContent.viewResult })
    expect(anchors).toContain(view)
    expect(view).not.toHaveAttribute('data-google-interstitial')
    for (const anchor of anchors.filter((candidate) => candidate !== view)) {
      expect(anchor).toHaveAttribute('data-google-interstitial', 'false')
    }
  })

  it('DOM·속성·제목에 금액·%·UUID·transition 값이 없고 카피에 금지 표현이 없다', async () => {
    signedIn()
    claims(() => Promise.resolve(json({ outcome: 'SHOW', adUnitPath: AD_UNIT_PATH })))
    open(`?t=${TRANSITION}`)

    render(<AdTransitionScreen />)
    await waitFor(() => {
      expect(showInterstitial).toHaveBeenCalledTimes(1)
    })

    // CSS Module의 class 이름(시험 환경의 해시)은 화면이 쓴 값이 아니다.
    // eslint-disable-next-line testing-library/no-node-access -- 모든 요소의 모든 속성 값을 훑는 단정이다. 역할 조회로는 속성 값 전체에 닿을 수 없다.
    const attributes = [...document.body.querySelectorAll('*')]
      .flatMap((element) =>
        [...element.attributes]
          .filter((attribute) => attribute.name !== 'class')
          .map((attribute) => attribute.value),
      )
      .join(' ')
    for (const surface of [document.body.textContent, attributes, document.title]) {
      expect(surface).not.toContain(TRANSITION)
      expect(surface).not.toMatch(/\d/)
      expect(surface).not.toContain('%')
      expect(surface).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i)
    }
    expect(document.title).toBe('복리 계획 비교 · BokriLog')
    for (const copy of adTransitionCopyStrings) {
      expect(containsForbiddenCopy(copy)).toBe(false)
      expect(document.body.textContent).toContain(copy)
    }
    // "광고를 시청하면 결과가 열립니다"류가 아니다.
    expect(document.body.textContent).not.toContain('시청')
    expect(getObservedStorageEntries()).toEqual([])
  })
})
