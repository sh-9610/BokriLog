import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { authContent, onboardingCopyStrings, SIGN_IN_PROVIDERS } from '../content/auth'
import { entryContent } from '../content/entry'
import { navigateTo, replaceLocation } from '../session/navigation'
import {
  getObservedAllowedRequests,
  getObservedSessionRequests,
  getObservedStorageEntries,
  stubLogoutRequest,
  stubSessionRequest,
} from '../test/setup'
import { OnboardingScreen } from './OnboardingScreen'

/*
 * 최상위 내비게이션을 가로챈다.
 *
 * jsdom은 `location.replace`·`location.assign`을 실제로 수행하지 않고 관측할 수도 없다. 서버가
 * 고른 목적지로 보내는지와 로그아웃 뒤 표지 경로로 가는지가 이 화면의 계약이므로, 그 전환을
 * 소유한 모듈 하나를 대신 세운다.
 */
vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** bfcache 복원 신호. `persisted`는 읽기 전용이라 생성 후 정의한다. */
function pageShow(persisted: boolean): Event {
  const event = new Event('pageshow')
  Object.defineProperty(event, 'persisted', { value: persisted })
  return event
}

/** 응답이 아직 오지 않은 상태. `checking` 분기를 관측하기 위한 의도된 무기한 대기다. */
function pendingResponse(): Promise<Response> {
  return new Promise<Response>(() => {
    // 아무것도 하지 않는다. resolve를 부르지 않는 것이 이 함수의 목적이다.
  })
}

/*
 * `restoreMocks`는 `vi.spyOn`이 만든 spy만 원래대로 돌린다. `vi.mock` 팩토리의 `vi.fn()`은 호출
 * 기록을 그대로 들고 다음 시험으로 넘어가므로 "부르지 않았다"를 단정할 수 없다. 매 시험 앞에서
 * 직접 비운다.
 */
beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  vi.mocked(navigateTo).mockClear()
})

describe('OnboardingScreen', () => {
  it('확인 중에는 로그인 상태를 주장하지 않고 사용자 데이터를 표시하지 않는다', () => {
    stubSessionRequest(pendingResponse)

    render(<OnboardingScreen />)

    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-busy', 'true')
    expect(status.textContent).toBe(authContent.session.checking)
    expect(screen.queryByText(authContent.onboarding.heading)).not.toBeInTheDocument()
    for (const item of authContent.onboarding.items) {
      expect(screen.queryByText(item)).not.toBeInTheDocument()
    }
  })

  it('세션이 확인되면 온보딩 카피를 렌더한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)

    expect(
      await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading }),
    ).toBeInTheDocument()
    const rendered = document.body.textContent
    for (const copy of onboardingCopyStrings) {
      expect(rendered).toContain(copy)
    }
  })

  it('UX-DR23: `복리 계획 비교` 제목과 비추천 고지를 인접 배치한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    const title = screen.getByRole('heading', { level: 1 })

    expect(title.textContent).toBe('복리 계획 비교')
    expect(title.nextElementSibling?.textContent).toBe('투자성과·계좌 수익률·투자 추천이 아닙니다.')
  })

  it('미인증이면 인증 화면으로 되돌리고 사용자 데이터를 표시하지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: false, destination: 'entry' })),
    )

    render(<OnboardingScreen />)

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    // 세 버튼을 함께 본다. 하나만 보면 나머지 제공자의 버튼이 사라져도 이 시험이 통과한다
    // (`expectAuthScreenMounted`·`App.test.tsx`·`EntryScreen.test.tsx`가 쓰는 것과 같은 형태다).
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.getByRole('link', { name: authContent.signIn.labels[provider] }),
      ).toBeInTheDocument()
    }
    expect(screen.queryByText(authContent.onboarding.heading)).not.toBeInTheDocument()
  })

  it('세션 확인이 실패하면 인증 화면으로 되돌린다', async () => {
    // 5xx·네트워크 오류·응답 유실이 모두 같은 결과로 수렴한다. 자동 재시도하지 않는다.
    stubSessionRequest(() => Promise.resolve(jsonResponse({}, 500)))

    render(<OnboardingScreen />)

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    expect(getObservedSessionRequests()).toHaveLength(1)
  })

  it('세션 확인은 정확히 한 번만 요청한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    expect(getObservedSessionRequests()).toEqual(['/api/auth/session'])
  })

  it('세션을 확인해도 브라우저 저장소 항목이 생기지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    // 인증 토큰과 금융 원본을 브라우저 영속 저장소에 두지 않는다. 세션은 서버에만 있다.
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('서버가 다른 목적지를 고르면 그 목적지로 보내고 사용자 데이터를 그리지 않는다', async () => {
    // 이미 온보딩을 지난 계정이 이 경로를 직접 열면 서버는 `dashboard`를 답한다. 그 값을 버리면
    // 화면은 온보딩을 다시 그리고 AC-4의 "기존 사용자는 원래 목적지"가 성립하지 않는다.
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'dashboard' })),
    )

    render(<OnboardingScreen />)

    expect(await screen.findByText(authContent.session.redirecting)).toBeInTheDocument()
    expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith('/dashboard')
    expect(screen.queryByText(authContent.onboarding.heading)).not.toBeInTheDocument()
    for (const item of authContent.onboarding.items) {
      expect(screen.queryByText(item)).not.toBeInTheDocument()
    }
  })

  it('기대한 목적지에서는 내비게이션을 부르지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('뒤로 가기 복원에서 세션이 사라졌으면 민감 화면을 계속 보여 주지 않는다', async () => {
    let signedIn = true
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse(
          signedIn
            ? { authenticated: true, destination: 'onboarding' }
            : { authenticated: false, destination: 'entry' },
        ),
      ),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    /*
     * UX-DR22: "로그아웃·세션 만료·삭제 후 뒤로 가기 복원으로 민감 화면이 노출되지 않아야 한다."
     * bfcache에서 되살아난 문서는 마운트를 다시 하지 않으므로 세션 확인이 다시 돌지 않고, 화면은
     * 저장된 그대로 인증된 상태를 계속 주장한다. `persisted`가 참인 `pageshow`가 그 유일한 신호다.
     */
    signedIn = false
    act(() => {
      window.dispatchEvent(pageShow(true))
    })

    /*
     * 만료의 결과는 이동 하나다. Story 1.5가 그 구분을 들였다 — 인증을 한 번 관측한 뒤의 미인증은
     * 만료이고, 첫 확인부터 미인증인 냉시작과 사용자가 알아야 할 사실이 다르다. 서버 응답은 두
     * 경우에 같으므로 이 전이가 유일한 재료다.
     *
     * 만료 문장을 여기서 단정하지 않는다. 그 문장의 출처는 이동한 주소의 표지(`noticeKindOf`)
     * 하나이며 `EntryScreen.test.tsx`가 그 자리를 덮는다. 제자리 렌더와 이동을 함께 내면 실제
     * 브라우저에서 화면이 두 번 바뀌고 URL이 착지하기 전에 인증 화면 경로로 읽힌다.
     */
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith('/?auth=expired')
    })
    // 이동이 착지하기 전에 아무 상태도 주장하지 않는다 — 만료 문장도, 이전 화면의 본문도 없다.
    expect(screen.queryByText(authContent.notices.expired)).not.toBeInTheDocument()
    expect(screen.queryByText(authContent.notices.signedOut)).not.toBeInTheDocument()
    expect(screen.queryByText(authContent.onboarding.heading)).not.toBeInTheDocument()
    expect(getObservedSessionRequests()).toHaveLength(2)
  })

  it('냉시작 미인증은 만료가 아니라 로그인 상태 아님으로 안내한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: false, destination: 'entry' })),
    )

    render(<OnboardingScreen />)

    // 첫 확인부터 미인증이다. 인증을 관측한 적이 없으므로 만료라고 말할 근거가 없다.
    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    expect(screen.queryByText(authContent.notices.expired)).not.toBeInTheDocument()
    // 그 자리에서 인증 화면을 렌더한다. 최상위 내비게이션을 부르지 않는다.
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('로그아웃 버튼이 세션이 확인된 뒤에만 나타난다', async () => {
    stubSessionRequest(pendingResponse)

    const { unmount } = render(<OnboardingScreen />)

    // 확인 중에는 로그아웃할 대상이 아직 없다.
    expect(
      screen.queryByRole('button', { name: authContent.signOut.label }),
    ).not.toBeInTheDocument()
    unmount()

    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: true, destination: 'onboarding', csrfToken: 'token-a' }),
      ),
    )
    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    // 온보딩에 버튼이 없으면 방금 가입한 사용자가 이 화면에서 나갈 방법이 없다.
    expect(screen.getByRole('button', { name: authContent.signOut.label })).toBeInTheDocument()
  })

  it('로그아웃을 실행하면 표지를 달고 진입 화면으로 이동한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: true, destination: 'onboarding', csrfToken: 'token-a' }),
      ),
    )
    stubLogoutRequest(() => Promise.resolve(new Response(null, { status: 204 })))

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=loggedOut')
    })
    // 세션 확인과 로그아웃 두 요청뿐이다. 이 화면은 그 밖의 요청을 보내지 않는다.
    expect(getObservedAllowedRequests()).toEqual(['GET /api/auth/session', 'POST /api/auth/logout'])
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('접근성: 건너뛰기 링크가 로그아웃보다 먼저 도달한다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse({ authenticated: true, destination: 'onboarding', csrfToken: 'token-a' }),
      ),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    const skipLink = screen.getByRole('link', { name: entryContent.skipLink })
    const signOut = screen.getByRole('button', { name: authContent.signOut.label })

    // 반복되는 머리말을 지나치려는 사용자가 먼저 만나는 것은 건너뛰기 링크여야 한다.
    expect(skipLink.compareDocumentPosition(signOut)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })

  it('탭이 다시 보이게 되면 세션을 다시 확인하고 만료를 안내한다', async () => {
    let signedIn = true
    stubSessionRequest(() =>
      Promise.resolve(
        jsonResponse(
          signedIn
            ? { authenticated: true, destination: 'onboarding', csrfToken: 'token-a' }
            : { authenticated: false, destination: 'entry' },
        ),
      ),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    /*
     * 만료 감지의 실제 생산자다. 인증된 화면을 열어 둔 탭은 다시 마운트되지 않으므로 첫 확인의
     * 답을 계속 주장하고, bfcache 재확인은 이 스토리가 진입 문서에 붙인 `no-store` 때문에
     * Chromium에서 오지 않는다. 사용자가 다른 탭을 보다 돌아오는 순간이 그 사이에 세션이 끝났을
     * 수 있는 유일한 관측 지점이다.
     */
    signedIn = false
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith('/?auth=expired')
    })
    expect(getObservedSessionRequests()).toHaveLength(2)
    // 이동만 남는다. 만료 문장은 이동한 주소의 표지가 만든다.
    expect(screen.queryByText(authContent.notices.expired)).not.toBeInTheDocument()
    // 폴링이 아니다. 가시성이 바뀔 때 한 번이고, 진입 화면으로 수렴한 뒤에는 다시 듣지 않는다.
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(getObservedSessionRequests()).toHaveLength(2)
  })

  it('요청이 실패하면 세션이 만료됐다고 말하지 않는다', async () => {
    let reachable = true
    stubSessionRequest(() =>
      reachable
        ? Promise.resolve(
            jsonResponse({ authenticated: true, destination: 'onboarding', csrfToken: 'token-a' }),
          )
        : Promise.reject(new Error('네트워크 단절')),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    /*
     * 재확인이 실패한다. 세션의 사실이 아니라 이 브라우저의 사정이므로 만료로 판정하지 않는다 —
     * 판정하면 일시적 오프라인 하나가 멀쩡한 세션의 사용자를 인증 화면으로 밀어내고 "만료됐다"고
     * 말한다. 서버가 미인증이라고 답했을 때만 만료다.
     */
    reachable = false
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    expect(await screen.findByText(authContent.notices.signedOut)).toBeInTheDocument()
    expect(screen.queryByText(authContent.notices.expired)).not.toBeInTheDocument()
    // 만료가 아니므로 최상위 내비게이션도 부르지 않는다. 그 화면 안에서 표시로 끝난다.
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('bfcache 복원이 아닌 `pageshow`는 세션을 다시 확인하지 않는다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    // 첫 로드도 `pageshow`를 낸다. 그때 다시 확인하면 마운트마다 요청이 두 번이 된다.
    act(() => {
      window.dispatchEvent(pageShow(false))
    })

    expect(getObservedSessionRequests()).toHaveLength(1)
  })

  it('응답이 오지 않으면 시한이 지난 뒤 인증 화면으로 되돌린다', async () => {
    vi.useFakeTimers()
    try {
      stubSessionRequest(pendingResponse)

      render(<OnboardingScreen />)
      expect(screen.getByRole('status').textContent).toBe(authContent.session.checking)

      // 클라이언트 시한이 없으면 진입점의 읽기 timeout(30초)까지 "확인 중"에 머문다.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000)
      })

      expect(screen.getByText(authContent.notices.signedOut)).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('접근성: 건너뛰기 링크가 본문 랜드마크를 가리킨다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    const skipLink = screen.getByRole('link', { name: '본문으로 건너뛰기' })
    const main = screen.getByRole('main')

    expect(skipLink).toHaveAttribute('href', '#onboarding-main')
    expect(main).toHaveAttribute('id', 'onboarding-main')
  })

  it('접근성: polite live region이 화면당 하나다', async () => {
    stubSessionRequest(() =>
      Promise.resolve(jsonResponse({ authenticated: true, destination: 'onboarding' })),
    )

    render(<OnboardingScreen />)
    await screen.findByRole('heading', { level: 2, name: authContent.onboarding.heading })

    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  })
})
