import {
  authorizePath,
  reauthenticatePath,
  reauthNoticePath,
  type SignInProvider,
} from '../content/auth'
import { postNoContent } from './http'
import { navigateTo, replaceLocation } from './navigation'

/*
 * 재인증 시작.
 *
 * 두 단계다. 먼저 `POST /api/auth/reauthenticate/<제공자>`가 세션에 일회성 흐름을 심고, 그 다음
 * 브라우저가 `GET /api/auth/authorize/<제공자>`로 최상위 이동한다. 서버가 그 요청을 302로 제공자에
 * 보낸다.
 *
 * 나누는 이유는 보호다. 무장은 상태 변경이라 세션 귀속 CSRF 토큰과 동일 출처 `Origin`을 지나야
 * 하고, 인가 시작은 제공자로 가는 내비게이션이라 `GET`이어야 한다. 한 경로에 합치면 표지가 붙은
 * 링크 하나로 남의 세션에 재인증 흐름을 심을 수 있다.
 *
 * 전송의 모양은 `signOut`과 같다 — 세션 쿠키(`credentials: 'same-origin'`), 토큰 헤더, 그리고
 * 브라우저가 싣는 `Origin`. 마지막 것은 스크립트가 만들 수 없고, 그래서 여기에 `Origin`을 다루는
 * 코드가 없는 것이 정상이다.
 *
 * 자동 재시도하지 않는다. 실패를 반복 요청으로 덮으면 사용자는 무엇이 일어났는지 알 수 없다.
 */

/** 재인증 시작의 결과. 화면이 버튼을 되돌릴지 정하는 데 쓴다. */
export type ReauthenticationOutcome = 'failed' | 'started'

export async function startReauthentication(
  provider: SignInProvider,
  csrfToken: string | null,
): Promise<ReauthenticationOutcome> {
  const outcome = await arm(provider, csrfToken)

  /*
   * 결과가 먼저 확정된다. 이동은 전송의 `try` 밖이다 — 안에 두면 이동이 던진 오류가 요청
   * 실패로 읽혀 서버가 흐름을 심은 뒤에도 실패 분기로 떨어진다(`signOut`과 같은 근거).
   */
  if (outcome === 'started') {
    /*
     * 제공자로 가는 최상위 내비게이션. `next`를 붙이지 않는다 — 복귀 목적지는 방금 서버가
     * 무장 시각에 붙들어 두었고, 여기서 값을 보내면 클라이언트가 목적지를 정하는 두 번째 경로가
     * 열린다.
     */
    navigateTo(authorizePath(provider))
    return outcome
  }

  /*
   * 실패는 제자리에 남는다. 표지를 달고 같은 경로를 다시 요청해 화면이 이유를 말하게 하고,
   * 히스토리 항목은 남기지 않는다 — 성립하지 않은 시도로 뒤로 가기가 막히지 않게 한다.
   *
   * 표지를 URL이 나르는 것이 기존 분업이다. 화면 안 상태로 두면 새로고침에 사라지고, 서버가
   * 만드는 같은 표지와 두 가지 경로가 생긴다.
   */
  replaceLocation(reauthNoticePath(window.location.pathname))
  return outcome
}

/** 무장 요청 하나. 이동을 하지 않고 결과만 정한다. */
async function arm(
  provider: SignInProvider,
  csrfToken: string | null,
): Promise<ReauthenticationOutcome> {
  /*
   * 전송은 `http.ts`의 `postNoContent` 하나다. 토큰이 없으면 보내지 않고(보내도 서버가 403으로
   * 거절한다), 시한은 세션 확인·로그아웃과 같은 값이며, 네트워크 단절·응답 유실·시한 초과도
   * 아래와 같은 결과다.
   */
  if (await postNoContent(reauthenticatePath(provider), csrfToken)) {
    // 서버가 흐름을 심었다. 여기서 성공이 확정된다.
    return 'started'
  }

  /*
   * 204가 아니면 제공자로 보내지 않는다.
   *
   * 보내면 무장 없는 인가 시작이 되고, 서버는 그것을 "이미 인증된 세션의 로그인 시작"으로 보아
   * 목적지로 되돌린다 — 사용자는 아무 설명 없이 같은 화면으로 돌아오고 무엇이 실패했는지 알 수
   * 없다.
   */
  return 'failed'
}
