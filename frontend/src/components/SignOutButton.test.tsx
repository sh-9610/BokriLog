import type { ReactElement } from 'react'

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { authContent, CSRF_HEADER } from '../content/auth'
import { navigateTo } from '../session/navigation'
import { useSignOut } from '../session/useSignOut'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubLogoutRequest,
} from '../test/setup'
import { SignOutButton } from './SignOutButton'

/*
 * 최상위 내비게이션을 가로챈다. jsdom은 `location.assign`을 수행하지도, 관측하게 하지도 않는다.
 * 두 화면의 시험과 같은 관용이다.
 */
vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const TOKEN = 'sign-out-button-token'

/**
 * 버튼과 그 상태를 소유한 훅을 두 화면과 같은 모양으로 조립한다.
 *
 * 버튼은 표시만 한다 — 진행 상태와 전송은 화면이 소유한 훅의 것이고, 그 상태를 화면당 하나뿐인
 * `role="status"` 영역이 함께 본다. 여기서 그 조립을 재현하지 않으면 이 시험은 버튼의 마크업만
 * 보고 "누르면 무엇이 나가는가"는 아무도 보지 않게 된다.
 */
function SignOutHarness({ csrfToken }: { readonly csrfToken: string | null }): ReactElement {
  const signOut = useSignOut(csrfToken)

  return (
    <>
      <SignOutButton onSignOut={signOut.requestSignOut} pending={signOut.pending} />
      <div aria-live="polite" role="status">
        {signOut.pending ? <p>{authContent.signOut.pending}</p> : null}
      </div>
    </>
  )
}

function noContent(): Promise<Response> {
  return Promise.resolve(new Response(null, { status: 204 }))
}

/** 나간 로그아웃 요청의 수. `POST` 항목만 센다. */
function logoutRequestCount(): number {
  return getObservedAllowedRequests().filter((entry) => entry.startsWith('POST')).length
}

/** 지금 화면의 로그아웃 버튼. 이름은 언제나 동작이므로 조회도 언제나 같은 이름으로 한다. */
function signOutButton(): HTMLElement {
  return screen.getByRole('button', { name: authContent.signOut.label })
}

beforeEach(() => {
  vi.mocked(navigateTo).mockReset()
})

describe('SignOutButton', () => {
  it('네이티브 버튼이고 접근 가능한 이름이 카피 정본과 같다', () => {
    render(<SignOutHarness csrfToken={TOKEN} />)

    const button = signOutButton()

    /*
     * 링크가 아니라 네이티브 버튼이다. 로그아웃은 상태 변경이므로 안전한 메서드로 실행될 수 없고,
     * `<button>`이면 `Tab` 도달과 `Enter`/`Space` 실행이 브라우저 기본 동작으로 성립한다. jsdom은
     * 키보드 활성화를 구현하지 않으므로 그 실행은 실제 Chromium에서 확인한다
     * (`e2e/auth-entry.spec.ts`). 여기서 고정하는 것은 그 동작을 받는 요소 종류다.
     */
    expect(button.tagName.toLowerCase()).toBe('button')
    // 폼이 없으므로 제출 버튼이 되지 않게 명시한다.
    expect(button).toHaveAttribute('type', 'button')
    expect(button).toBeEnabled()
    expect(button).toHaveAttribute('aria-disabled', 'false')
    expect(authContent.signOut.label).toBe('로그아웃')
  })

  it('렌더만으로는 어떤 요청도 보내지 않는다', () => {
    render(<SignOutHarness csrfToken={TOKEN} />)

    expect(getObservedAllowedRequests()).toEqual([])
    expect(getObservedRequests()).toEqual([])
  })

  it('클릭이 토큰을 헤더에 실어 로그아웃을 보낸다', async () => {
    let observed: RequestInit | undefined
    stubLogoutRequest((init) => {
      observed = init
      return noContent()
    })

    render(<SignOutHarness csrfToken={TOKEN} />)
    fireEvent.click(signOutButton())

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=loggedOut')
    })
    expect(getObservedAllowedRequests()).toEqual(['POST /api/auth/logout'])
    // 토큰은 헤더로만 간다. 쿼리 문자열은 로그·`Referer`·히스토리에 남는다.
    expect(new Headers(observed?.headers).get(CSRF_HEADER)).toBe(TOKEN)
    expect(observed?.method).toBe('POST')
    expect(observed?.credentials).toBe('same-origin')
    expect(observed?.cache).toBe('no-store')
    // 매달린 요청이 버튼을 영구 pending으로 남기지 않도록 시한을 함께 보낸다.
    expect(observed?.signal).toBeInstanceOf(AbortSignal)
  })

  it('진행 중에는 이름이 동작으로 남고 live region이 상태를 안내한다', () => {
    stubLogoutRequest(() => new Promise<Response>(() => undefined))

    render(<SignOutHarness csrfToken={TOKEN} />)
    fireEvent.click(signOutButton())

    /*
     * 이름은 여전히 "로그아웃"이다. 진행 중 문구를 버튼 이름으로 쓰면 컨트롤의 이름이 동작이
     * 아니라 상태가 되고, 마침표까지 붙은 완전문이 버튼 이름으로 읽힌다.
     */
    const pending = signOutButton()

    /*
     * `disabled`가 아니라 `aria-disabled`다. `disabled`는 요소를 탭 순서에서 떨어뜨리므로 방금
     * 이 버튼을 실행한 키보드·스크린리더 사용자가 포커스를 잃는다.
     */
    expect(pending).toHaveAttribute('aria-disabled', 'true')
    expect(pending).toBeEnabled()
    expect(pending).not.toHaveAttribute('disabled')

    // 상태를 말하는 자리는 화면당 하나뿐인 live region이다.
    expect(screen.getByRole('status').textContent).toBe(authContent.signOut.pending)
  })

  it('진행 중 중복 클릭이 두 번째 요청을 만들지 않는다', async () => {
    let resolveLogout: (response: Response) => void = () => undefined
    stubLogoutRequest(
      () =>
        new Promise<Response>((resolve) => {
          resolveLogout = resolve
        }),
    )

    render(<SignOutHarness csrfToken={TOKEN} />)
    fireEvent.click(signOutButton())

    // `aria-disabled`는 알려 주기만 하고 막지는 않는다. 실제 차단은 훅의 가드가 한다.
    fireEvent.click(signOutButton())
    fireEvent.click(signOutButton())

    expect(logoutRequestCount()).toBe(1)
    resolveLogout(new Response(null, { status: 204 }))
    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=loggedOut')
    })
  })

  it('실패하면 표지를 달고 버튼을 되돌려 다시 시도할 수 있게 둔다', async () => {
    stubLogoutRequest(() => Promise.resolve(new Response('{}', { status: 403 })))

    render(<SignOutHarness csrfToken={TOKEN} />)
    fireEvent.click(signOutButton())

    /*
     * 실제 브라우저에서는 최상위 내비게이션이 문서를 곧 대체한다. 그 전환이 막히는 환경에서
     * 버튼이 영구히 죽어 있는 상태를 남기지 않는다. 자동 재시도는 하지 않는다 — 요청은 하나뿐이다.
     */
    await waitFor(() => {
      expect(signOutButton()).toHaveAttribute('aria-disabled', 'false')
    })
    expect(logoutRequestCount()).toBe(1)
    /*
     * 로그아웃 실패 표지를 단다. 표지 없이 `/`로 보내면 안내가 렌더되지 않고 제목 포커스도
     * 건너뛰어, 세션이 살아 있는데 아무 설명 없는 로그인 화면이 보인다.
     */
    expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=logoutFailed')
    // 로그아웃 완료라고 말하지 않는다. 거절된 요청에서는 세션이 살아 있다.
    expect(vi.mocked(navigateTo)).not.toHaveBeenCalledWith('/?auth=loggedOut')
  })

  it('네트워크가 끊겨도 인증 상태를 주장하지 않는다', async () => {
    stubLogoutRequest(() => Promise.reject(new Error('네트워크 단절')))

    render(<SignOutHarness csrfToken={TOKEN} />)
    fireEvent.click(signOutButton())

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=logoutFailed')
    })
    expect(logoutRequestCount()).toBe(1)
  })

  it('토큰이 없으면 요청을 보내지 않고 실패 표지로 수렴한다', async () => {
    render(<SignOutHarness csrfToken={null} />)
    fireEvent.click(signOutButton())

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=logoutFailed')
    })
    // 실패가 예정된 상태 변경 시도 하나를 로그에 남기지 않는다.
    expect(getObservedAllowedRequests()).toEqual([])
    expect(getObservedRequests()).toEqual([])
    expect(vi.mocked(navigateTo)).not.toHaveBeenCalledWith('/?auth=loggedOut')
  })

  it('이동이 던져도 버튼이 영구 pending으로 남지 않는다', async () => {
    stubLogoutRequest(noContent)
    vi.mocked(navigateTo).mockImplementation(() => {
      // 전환이 막히는 환경이다. 브라우저가 이동을 거부해도 죽은 버튼만 남기지 않는다.
      throw new Error('이동이 막혔다')
    })

    render(<SignOutHarness csrfToken={TOKEN} />)
    fireEvent.click(signOutButton())

    await waitFor(() => {
      expect(signOutButton()).toHaveAttribute('aria-disabled', 'false')
    })
    // 요청은 하나뿐이다. 성공을 실패로 뒤집어 두 번째 이동을 부르지도 않는다.
    expect(logoutRequestCount()).toBe(1)
    expect(vi.mocked(navigateTo)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=loggedOut')
  })

  it('토큰을 브라우저 저장소에 두지 않는다', async () => {
    stubLogoutRequest(noContent)

    render(<SignOutHarness csrfToken={TOKEN} />)
    fireEvent.click(signOutButton())

    await waitFor(() => {
      expect(vi.mocked(navigateTo)).toHaveBeenCalledWith('/?auth=loggedOut')
    })
    // 인증 비밀은 메모리에만 있다. `localStorage`·`sessionStorage`·쿠키·IndexedDB 항목이 0건이다.
    expect(getObservedStorageEntries()).toEqual([])
  })
})
