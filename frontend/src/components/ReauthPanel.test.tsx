import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import {
  authContent,
  reauthCopyStrings,
  SIGN_IN_PROVIDERS,
  type ReauthNoticeKind,
} from '../content/auth'
import { getObservedRequests, getObservedStorageEntries } from '../test/setup'
import { ReauthPanel } from './ReauthPanel'

/*
 * 패널 렌더는 이 층이 소유한다.
 *
 * 표시된 세션은 스택에서 만들 수 없다. `SessionGuard`에는 HTTP 표면이 없고 세션 속성은 Java
 * 직렬화라 SQL로도 만들 수 없으므로, Playwright가 이 패널을 렌더할 방법이 없다. 그래서 접근
 * 가능한 이름·키보드 도달·표지별 문장·시작 호출은 Vitest가 본다 — 두 인증 화면의 게이트 축이
 * 이미 이 층에 있는 것과 같은 자리다.
 */

function renderPanel(overrides: Partial<Parameters<typeof ReauthPanel>[0]> = {}): {
  readonly onReauthenticate: ReturnType<typeof vi.fn>
  readonly onSignOut: ReturnType<typeof vi.fn>
} {
  const onReauthenticate = vi.fn()
  const onSignOut = vi.fn()
  render(
    <ReauthPanel
      notice={null}
      onReauthenticate={onReauthenticate}
      onSignOut={onSignOut}
      pending={false}
      signingOut={false}
      {...overrides}
    />,
  )
  return { onReauthenticate, onSignOut }
}

describe('ReauthPanel', () => {
  it('안내와 세 제공자 버튼과 로그아웃 버튼을 렌더한다', () => {
    renderPanel()

    expect(
      screen.getByRole('heading', { level: 2, name: authContent.reauth.heading }),
    ).toBeInTheDocument()
    /*
     * 패널이 소유한 문장만 여기서 본다. `reauthCopyStrings`에는 화면이 소유하는 제목·고지도
     * 들어 있고(UX-DR23의 인접 배치), 그 배열 전체는 두 화면의 재인증 가지가 단정한다 — 그래야
     * "패널이 화면을 통째로 차지하므로 그 화면에도 제목과 고지가 인접해야 한다"가 실제로
     * 지켜지는 자리를 갖는다.
     */
    for (const copy of [
      authContent.reauth.heading,
      authContent.reauth.body,
      authContent.reauth.boundary,
      ...SIGN_IN_PROVIDERS.map((provider) => authContent.signIn.labels[provider]),
      authContent.signOut.label,
    ]) {
      expect(screen.getByText(copy)).toBeInTheDocument()
    }
    // 그 둘은 패널에 없다. 화면이 낸다.
    expect(reauthCopyStrings).toContain(authContent.pageTitle)
    expect(reauthCopyStrings).toContain(authContent.disclaimer)
    expect(screen.queryByText(authContent.pageTitle)).not.toBeInTheDocument()
  })

  it('세 제공자 버튼이 고정 순서의 네이티브 버튼이고 이름이 제공자명 + 계속하기다', () => {
    renderPanel()

    const buttons = screen.getAllByRole('button')
    /*
     * 순서가 문서 순서다. 마지막이 로그아웃이고 그 앞이 Google → 네이버 → 카카오다.
     * 이 목록 하나가 `Tab` 도달 순서이기도 하다 — 네이티브 `<button>`이라 순서를 따로 만들지
     * 않는다.
     */
    expect(buttons.map((button) => button.textContent)).toEqual([
      ...SIGN_IN_PROVIDERS.map((provider) => authContent.signIn.labels[provider]),
      authContent.signOut.label,
    ])
    for (const button of buttons) {
      // 링크가 아니라 버튼이다. 재인증은 상태 변경을 먼저 지나야 하므로 안전한 메서드가 될 수 없다.
      expect(button.tagName).toBe('BUTTON')
      expect(button).toHaveAttribute('type', 'button')
    }
  })

  it('추천 표지도 마지막 사용 제공자 강조도 없다', () => {
    renderPanel()

    /*
     * 마크업의 모양을 잰다. 약속된 속성 이름을 찾는 단정은 그런 속성이 애초에 없으므로 언제나
     * 통과하고, 실제 추천 표지가 들어오는 모양(배지 형제 요소, 세 번째 자식)은 걸리지 않는다.
     */
    const providerButtons = SIGN_IN_PROVIDERS.map((provider) =>
      screen.getByRole('button', { name: authContent.signIn.labels[provider] }),
    )
    // eslint-disable-next-line testing-library/no-node-access -- "목록에 버튼만 있는가"와 "버튼 안쪽이 마크 하나와 레이블 하나뿐인가"는 역할 조회로 볼 수 없다. 배지·강조 표지는 역할을 갖지 않는 형제·자식으로 들어온다.
    const list = providerButtons[0]?.parentElement
    // eslint-disable-next-line testing-library/no-node-access -- 위와 같은 이유.
    expect(list?.children).toHaveLength(SIGN_IN_PROVIDERS.length)
    for (const button of providerButtons) {
      // eslint-disable-next-line testing-library/no-node-access -- 위와 같은 이유.
      const childTags = Array.from(button.children).map((child) => child.tagName.toLowerCase())
      expect(childTags).toEqual(['svg', 'span'])
    }
  })

  it('버튼을 누르면 그 제공자로 시작을 요청한다', () => {
    const { onReauthenticate } = renderPanel()

    for (const provider of SIGN_IN_PROVIDERS) {
      fireEvent.click(screen.getByRole('button', { name: authContent.signIn.labels[provider] }))
    }

    // 사용자가 고른 제공자가 그대로 전달된다. 닫힌 맵이 아니면 여기서 남의 이름이 나온다.
    const chosen = onReauthenticate.mock.calls.map((call) => String(call[0]))
    expect(chosen).toEqual([...SIGN_IN_PROVIDERS])
  })

  it('진행 중에도 버튼이 탭 순서에 남고 이름이 동작 그대로다', () => {
    const { onReauthenticate } = renderPanel({ pending: true })

    const google = screen.getByRole('button', { name: authContent.signIn.labels.google })
    /*
     * `disabled`가 아니라 `aria-disabled`다. 그 속성은 요소를 탭 순서에서 떨어뜨리므로 방금
     * 그 버튼을 실행한 키보드·스크린리더 사용자가 포커스를 잃는다.
     */
    expect(google).toHaveAttribute('aria-disabled', 'true')
    expect(google).not.toBeDisabled()
    // 이름은 언제나 동작이다. 진행 문구로 바꾸면 컨트롤의 이름이 상태가 된다.
    expect(google).toHaveAccessibleName(authContent.signIn.labels.google)

    /*
     * 두 번째 시작이 나가지 않는다. 훅의 중복 제출 가드가 같은 일을 하지만 이 자리에도
     * 가드가 있는 이유는 교차 차단이다 — 패널은 두 조작의 상태를 함께 아는 유일한 곳이고,
     * `aria-disabled`는 알려 주기만 하고 막지 않는다.
     */
    fireEvent.click(google)
    expect(onReauthenticate).not.toHaveBeenCalled()
  })

  it('로그아웃 버튼이 패널에 있고 표시된 세션을 끝낼 수 있다', () => {
    const { onSignOut } = renderPanel()

    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    // 표시된 세션이 화면에서 스스로 나갈 수 있게 되는 자리다.
    expect(onSignOut).toHaveBeenCalledTimes(1)
  })

  it('재인증이 도는 동안 로그아웃이 막힌다', () => {
    const { onSignOut } = renderPanel({ pending: true })

    const signOut = screen.getByRole('button', { name: authContent.signOut.label })
    expect(signOut).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(signOut)

    /*
     * 막지 않으면 세션이 사라진 뒤 인가 시작이 열리고, 그 흐름은 무장을 잃었으므로 재인증이
     * 아니라 **새 로그인**이 된다 — 사용자가 누른 것과 일어나는 일이 달라진다.
     */
    expect(onSignOut).not.toHaveBeenCalled()
  })

  it('로그아웃이 도는 동안 재인증 시작이 막힌다', () => {
    const { onReauthenticate } = renderPanel({ signingOut: true })

    for (const provider of SIGN_IN_PROVIDERS) {
      const button = screen.getByRole('button', { name: authContent.signIn.labels[provider] })
      expect(button).toHaveAttribute('aria-disabled', 'true')
      fireEvent.click(button)
    }

    // 곧 사라질 세션에 흐름을 심지 않는다.
    expect(onReauthenticate).not.toHaveBeenCalled()
  })

  it('표지가 있으면 그 문장을 렌더하고 제목으로 포커스를 보낸다', () => {
    const kinds: readonly ReauthNoticeKind[] = ['reauthCancelled', 'reauthFailed']

    for (const kind of kinds) {
      const { unmount } = render(
        <ReauthPanel
          notice={kind}
          onReauthenticate={vi.fn()}
          onSignOut={vi.fn()}
          pending={false}
          signingOut={false}
        />,
      )

      const notice = screen.getByText(authContent.notices[kind])
      const panelHeading = screen.getByRole('heading', {
        level: 2,
        name: authContent.reauth.heading,
      })

      expect(notice).toBeInTheDocument()
      // 이동 뒤 포커스가 문서 처음에 남으면 무엇이 바뀌었는지 알 수 없다.
      expect(panelHeading).toHaveFocus()
      /*
       * 포커스가 오는 그 제목이 표지 문장을 자기 설명으로 가리킨다. 없으면 제목 문구가 언제나
       * "본인 확인이 필요합니다"라 스크린리더 사용자는 **왜** 돌아왔는지 듣지 못한다.
       */
      expect(panelHeading).toHaveAccessibleDescription(authContent.notices[kind])
      unmount()
    }
  })

  it('표지가 없으면 제목에 설명을 걸지 않는다', () => {
    renderPanel()

    expect(
      screen.getByRole('heading', { level: 2, name: authContent.reauth.heading }),
    ).not.toHaveAttribute('aria-describedby')
  })

  it('두 재인증 표지의 문장이 서로 다르다', () => {
    /*
     * 같으면 스스로 그만둔 사용자와 확인에 실패한 사용자가 같은 문장을 본다. 둘의 다음 행동은
     * 같지만(다시 시도) 무엇이 일어났는지는 다르다.
     */
    expect(authContent.notices.reauthCancelled).not.toBe(authContent.notices.reauthFailed)
    // 어느 문장도 계정의 존재·연결 상태를 말하지 않는다.
    for (const message of [authContent.notices.reauthCancelled, authContent.notices.reauthFailed]) {
      expect(message).not.toContain('계정')
      for (const provider of SIGN_IN_PROVIDERS) {
        expect(message.toLowerCase()).not.toContain(provider)
      }
    }
  })

  it('연결된 제공자를 묻지도 말하지도 않는다', () => {
    renderPanel({ notice: 'reauthFailed' })

    /*
     * 세 버튼이 언제나 함께 보인다. 어느 수단으로 가입했는지를 화면에 도달시키는 경로가 곧 계정
     * 소속 신호이므로 하나만 고를 수 없고, 연결되지 않은 수단을 고른 결과는 다른 실패와
     * 구분되지 않는 표지 하나로 수렴한다.
     */
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(
        screen.getByRole('button', { name: authContent.signIn.labels[provider] }),
      ).toBeInTheDocument()
    }
    expect(screen.getByText(authContent.reauth.boundary)).toBeInTheDocument()
  })

  it('요청도 저장소 쓰기도 스스로 하지 않는다', () => {
    renderPanel()

    // 전송은 화면이 소유한 훅의 일이다. 패널은 표현만 맡는다.
    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
  })
})
