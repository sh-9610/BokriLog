import { LOGOUT_ENDPOINT, noticePath } from '../content/auth'
import { postNoContent } from './http'
import { navigateTo } from './navigation'

/**
 * 로그아웃 전송.
 *
 * 두 인증 화면이 같은 버튼을 쓰고 그 버튼이 이 함수 하나를 부른다. 전송을 화면마다 두면 헤더 하나가
 * 빠진 경로나 실패에서 인증 상태를 계속 주장하는 경로가 생긴다.
 *
 * 세 가지를 함께 보낸다 — 세션 쿠키(`credentials: 'same-origin'`), 세션 귀속 CSRF 토큰(헤더),
 * 동일 출처 `Origin`. 마지막 것은 스크립트가 만들 수 없고 브라우저가 싣는다. 그것이 이 검사가
 * 의미를 갖는 이유이며, 그래서 여기서 `Origin`을 다루는 코드가 없는 것이 정상이다.
 *
 * 자동 재시도하지 않는다. 실패를 반복 요청으로 덮으면 사용자는 자기 세션이 끝났는지 알 수 없다.
 */

/** 로그아웃 전송의 결과. 화면이 버튼을 되돌릴지 정하는 데 쓴다. */
export type SignOutOutcome = 'failed' | 'signedOut'

/** 결과별 목적지. 두 경우 모두 표지를 달고 인증 화면으로 간다. */
const NOTICE_PATHS: Readonly<Record<SignOutOutcome, string>> = {
  /*
   * 실패도 표지를 단다.
   *
   * 표지 없이 `/`로 보내면 `noticeKindOf`가 `null`을 돌려주어 안내가 렌더되지 않고 진입 화면의
   * 제목 포커스도 건너뛴다. 그러면 서버 세션이 아직 살아 있는데 사용자에게는 아무 설명 없는
   * 로그인 화면이 보이고, AC-5의 "실행 뒤 포커스가 페이지 제목 또는 표지로 이동"이 그 경로에서
   * 성립하지 않는다. 로그아웃 완료라고 말하지 않는 것과 아무 말도 하지 않는 것은 다른 일이다.
   */
  failed: noticePath('logoutFailed'),
  signedOut: noticePath('loggedOut'),
}

export async function signOut(csrfToken: string | null): Promise<SignOutOutcome> {
  const outcome = await attempt(csrfToken)

  /*
   * 결과가 먼저 확정된다.
   *
   * 이동이 전송의 `try` 밖에 있는 것이 요점이다. 안에 두면 이동이 던진 오류가 요청 실패로 읽혀
   * 204를 받은 뒤에도 실패 분기로 떨어진다 — 서버는 세션을 폐기했는데 화면은 "끝내지 못했다"고
   * 말하는 상태다. 204를 받은 뒤의 사실은 브라우저가 전환을 거부하든 말든 바뀌지 않는다.
   *
   * 그 거부를 삼키지도 않는다. 이 거부는 호출자가 버튼을 되돌릴 유일한 신호이며(`useSignOut`),
   * 삼키면 전환이 막히는 환경에서 버튼이 영구 pending으로 남는다. 전환이 성공하면 이 문서는 곧
   * 사라지므로 아래 반환은 관측되지 않는다.
   */
  navigateTo(NOTICE_PATHS[outcome])
  return outcome
}

/** 전송 하나. 이동을 하지 않고 결과만 정한다. */
async function attempt(csrfToken: string | null): Promise<SignOutOutcome> {
  /*
   * 전송은 `http.ts`의 `postNoContent` 하나다. 토큰이 없으면 보내지 않고(보내도 서버가 403으로 거절한다),
   * 시한은 `useSessionState`의 세션 확인과 같은 값이다.
   *
   * 시한이 없으면 매달린 요청 하나가 버튼을 영구히 pending으로 남긴다 — 진입점의 읽기 timeout(30초)을
   * 넘겨 응답이 아예 오지 않는 경우에는 그 상태가 끝나지 않는다. 시한 초과·네트워크 단절은 다른 모든
   * 실패와 같은 결과로 수렴하고, 자동 재시도는 하지 않는다.
   */
  if (await postNoContent(LOGOUT_ENDPOINT, csrfToken)) {
    // 서버가 세션을 폐기했다. 여기서 성공이 확정된다.
    return 'signedOut'
  }

  /*
   * 204가 아니면 인증 상태를 주장하지 않는다.
   *
   * 로그아웃 표지를 달지 않는 것이 요점이다. 그 표지는 "세션이 끝났다"는 사실이고, 403으로
   * 거절된 요청에서는 세션이 살아 있다. 그때 로그아웃했다고 말하면 사용자는 끝나지 않은 세션을
   * 끝난 것으로 믿는다.
   */
  return 'failed'
}
