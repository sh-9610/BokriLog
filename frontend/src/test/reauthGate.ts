import { screen, within } from '@testing-library/react'
import { expect } from 'vitest'

import { authContent } from '../content/auth'

/**
 * 재인증 게이트의 거절을 화면이 어떻게 보이는지 한 번에 단정한다.
 *
 * 민감한 조작 하나가 성공하면 재인증 시각이 지워지고(`SessionRotation`), 바로 이어지는 다음 준비 요청은
 * 403이다. 그때 화면은 — 어느 흐름이든 — 거절 문구를 화면의 polite region 하나로 말하고, 대화상자를 열지
 * 않으며, 본인 확인 버튼 셋을 열어 두고, 초점을 움직이지 않는다. 세 화면 시험이 같은 단정을 공유한다.
 *
 * `focused`는 사용자가 누른 조작이다. jsdom의 `fireEvent.click`은 초점을 옮기지 않으므로 시험이 먼저
 * `focus()`를 부른 뒤 이 단정이 그 자리에 남아 있음을 본다.
 */
export async function expectReauthGateRejection(
  message: string,
  focused: HTMLElement,
): Promise<void> {
  // 화면의 region이 문서 순서에서 먼저다. 대화상자가 없으므로 하나뿐이지만, 같은 방식으로 고른다.
  const [region] = screen.getAllByRole('status')
  if (region === undefined) throw new Error('화면의 status region이 없다')
  expect(await within(region).findByText(message)).toBeInTheDocument()
  expect(region).toHaveAttribute('aria-live', 'polite')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  for (const label of Object.values(authContent.settings.reauth.labels)) {
    expect(screen.getByRole('button', { name: label })).toHaveAttribute('aria-disabled', 'false')
  }
  expect(focused).toHaveFocus()
}
