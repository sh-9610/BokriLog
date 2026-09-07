import type { ReactElement } from 'react'

import { authContent } from '../content/auth'
import styles from './SignOutButton.module.css'

interface SignOutButtonProps {
  /**
   * 요청이 도는 중인가. 상태는 화면이 소유한다(`useSignOut`).
   *
   * 이 버튼이 스스로 들고 있지 않은 이유는 같은 값을 본문의 live region도 봐야 하기 때문이다.
   * 두 곳이 각자 상태를 가지면 한쪽만 갱신되는 날이 온다.
   */
  readonly pending: boolean

  /**
   * 로그아웃을 시작한다. 진행 중 판정과 전송은 화면이 소유한 훅이 한다.
   *
   * 토큰이 없는 상태에서도 버튼은 남는다. 그때 버튼을 숨기는 대신 눌렀을 때 인증 화면으로
   * 수렴시키는 이유는, 토큰이 없는 것은 세션 확인이 늦거나 실패한 상태이고 그때 사용자에게
   * 필요한 것도 "여기서 나가는 길" 하나이기 때문이다. 버튼이 사라지면 인증된 화면에 갇힌다.
   */
  readonly onSignOut: () => void
}

/**
 * 두 인증 화면이 공유하는 로그아웃 버튼.
 *
 * `<button>`이다. 로그아웃은 상태 변경이므로 링크가 될 수 없고(안전한 메서드가 아니다), 네이티브
 * 버튼이라 `Tab` 도달과 `Enter`/`Space` 실행이 기본으로 성립한다. 포커스 표시는 `global.css`의
 * 전역 `:focus-visible`이 처리한다 — 여기서 다시 선언하면 특이도가 올라가 forced-colors 탈출구를
 * 이겨 버린다.
 *
 * 진행 중에도 `disabled`를 쓰지 않는다. 그 속성은 요소를 탭 순서에서 떨어뜨리므로, 방금 `Enter`로
 * 이 버튼을 실행한 키보드·스크린리더 사용자가 그 순간 포커스를 잃는다. `aria-disabled`는 "지금은
 * 받지 않는다"를 알려 주면서 포커스를 남기고, 실제 중복 제출 차단은 화면이 소유한 가드가 한다
 * (`useSignOut`).
 *
 * 접근 가능한 이름은 언제나 동작("로그아웃")이다. 진행 중 문구로 이름을 바꾸면 컨트롤의 이름이
 * 동작이 아니라 상태가 되고, 마침표까지 붙은 완전문이 버튼 이름으로 읽힌다. 그 문장이 있어야 하는
 * 자리는 화면당 하나뿐인 `role="status"` 영역이며 진행 중임을 그 영역이 안내한다 — 버튼의 문구만
 * 바꾸면 그 변화는 live region이 아니어서 보조기술에 전달되지도 않는다.
 *
 * 화면을 복제하지 않는다. 두 화면에 버튼을 각각 두면 헤더 하나가 빠진 요청이나 다른 문구가 생기고,
 * `deferred-work.md`가 이미 적은 두 화면의 중복이 그만큼 커진다.
 */
export function SignOutButton({ onSignOut, pending }: SignOutButtonProps): ReactElement {
  return (
    <button aria-disabled={pending} className={styles.button} onClick={onSignOut} type="button">
      {authContent.signOut.label}
    </button>
  )
}
