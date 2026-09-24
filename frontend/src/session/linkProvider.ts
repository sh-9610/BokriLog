import {
  authorizePath,
  CSRF_HEADER,
  LINK_BASE_PATH,
  linkNoticePath,
  linkPath,
  SIGN_IN_PROVIDERS,
  type SignInProvider,
} from '../content/auth'
import { navigateTo, replaceLocation } from './navigation'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/*
 * 로그인 수단 연결.
 *
 * 시작은 두 단계다. 먼저 `POST /api/auth/link/<제공자>`가 허가를 발급받아 세션에 일회성 흐름을
 * 심고, 그 다음 브라우저가 `GET /api/auth/authorize/<제공자>`로 최상위 이동한다. 서버가 그 요청을
 * 302로 제공자에 보낸다. 나누는 근거는 `reauthenticate.ts`와 같다 — 무장은 상태 변경이라 세션
 * 귀속 CSRF 토큰과 동일 출처 `Origin`을 지나야 하고, 인가 시작은 제공자로 가는 내비게이션이라
 * `GET`이어야 한다.
 *
 * 전송의 모양도 같다 — 세션 쿠키(`credentials: 'same-origin'`), 토큰 헤더, 그리고 브라우저가 싣는
 * `Origin`. 마지막 것은 스크립트가 만들 수 없고, 그래서 여기에 `Origin`을 다루는 코드가 없는 것이
 * 정상이다.
 *
 * 자동 재시도하지 않는다. 실패를 반복 요청으로 덮으면 사용자는 무엇이 일어났는지 알 수 없고,
 * 재인증이 만료된 경우에는 자동으로 재인증을 시작하지도 않는다 — 그것이 AC-6의 계약이다.
 */

/** 연결 시작의 결과. 화면이 버튼을 되돌릴지 정하는 데 쓴다. */
export type LinkOutcome = 'failed' | 'started'

/**
 * 연결 상태 조회의 결과.
 *
 * **실패를 빈 목록으로 접지 않는다.** 접으면 일시적 실패에서 이미 연결된 수단에도 연결 버튼이
 * 그려지고, 그 클릭은 서버가 거절해 "본인 확인을 마친 뒤 다시 연결해 주세요"라는 엉뚱한 안내로
 * 끝난다 — 사용자는 무엇이 잘못됐는지 알 수 없고 고칠 수도 없다. "확인하지 못했다"와 "연결된
 * 것이 없다"는 다른 사실이므로 값에서도 갈라 둔다.
 */
export type LinkedProvidersResult =
  | { readonly status: 'loaded'; readonly providers: readonly SignInProvider[] }
  | { readonly status: 'unavailable' }

/**
 * 이 계정에 연결된 제공자를 확인한다.
 *
 * 응답에 실리는 것은 제공자 이름뿐이다. `issuer`·`subject`·이메일·연결 시각은 서버가 보내지
 * 않으므로 여기서 읽을 값도 없다.
 *
 * 비정상 응답·시한 초과·네트워크 단절·JSON 아님은 모두 `unavailable` 하나로 수렴한다. 무엇이
 * 일어났는지 추측하지 않으며 자동 재시도도 하지 않는다 — 다시 확인하는 것은 사용자의 조작이다.
 */
export async function fetchLinkedProviders(): Promise<LinkedProvidersResult> {
  const controller = new AbortController()
  const deadline = setTimeout(() => {
    controller.abort()
  }, SESSION_REQUEST_TIMEOUT_MS)

  try {
    const response = await fetch(LINK_BASE_PATH, {
      method: 'GET',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
      // 뒤로 가기 복원과 공유 캐시가 이전 응답을 되살리지 못하게 한다. 계정의 사실이다.
      cache: 'no-store',
      signal: controller.signal,
    })
    if (!response.ok) {
      return { status: 'unavailable' }
    }
    const payload: unknown = await response.json()
    return linkedIn(payload)
  } catch {
    return { status: 'unavailable' }
  } finally {
    clearTimeout(deadline)
  }
}

/**
 * 응답에서 읽는 값.
 *
 * 아는 제공자 이름만 남긴다. 서버가 보내는 목록은 이미 닫힌 집합이지만, 화면의 닫힌 맵과 갈리는
 * 값이 들어오면 그 값으로는 아무 행도 그릴 수 없다 — 버리는 편이 정의된 동작이다.
 */
function linkedIn(payload: unknown): LinkedProvidersResult {
  if (typeof payload !== 'object' || payload === null || !('providers' in payload)) {
    // 이 응답은 연결 상태를 답한 것이 아니다. 목록이 비었다고 주장하지 않는다.
    return { status: 'unavailable' }
  }
  const providers: unknown = payload.providers
  if (!Array.isArray(providers)) {
    return { status: 'unavailable' }
  }
  return {
    status: 'loaded',
    providers: SIGN_IN_PROVIDERS.filter((provider) => providers.includes(provider)),
  }
}

/**
 * 그 제공자를 지금 계정에 연결하기 시작한다.
 *
 * 성공하면 제공자로 최상위 이동한다. 실패하면 표지를 달고 설정 화면을 다시 요청해 화면이 이유를
 * 말하게 한다 — 사용자가 다음에 할 수 있는 일은 본인 확인 하나이고 그 안내가 표지의 문장이다.
 */
export async function startLinking(
  provider: SignInProvider,
  csrfToken: string | null,
): Promise<LinkOutcome> {
  const outcome = await arm(provider, csrfToken)

  /*
   * 결과가 먼저 확정된다. 이동은 전송의 `try` 밖이다 — 안에 두면 이동이 던진 오류가 요청 실패로
   * 읽혀 서버가 흐름을 심은 뒤에도 실패 분기로 떨어진다(`signOut`·`reauthenticate`와 같은 근거).
   */
  if (outcome === 'started') {
    /*
     * 제공자로 가는 최상위 내비게이션. `next`를 붙이지 않는다 — 복귀 목적지는 서버 상수 하나이고
     * (연결 조작이 사는 화면은 하나다) 여기서 값을 보내면 클라이언트가 목적지를 정하는 두 번째
     * 경로가 열린다.
     */
    navigateTo(authorizePath(provider))
    return outcome
  }

  /*
   * 실패는 제자리에 남는다. 표지를 달고 같은 화면을 다시 요청하고 히스토리 항목은 남기지 않는다 —
   * 성립하지 않은 시도로 뒤로 가기가 막히지 않게 한다.
   */
  replaceLocation(linkNoticePath())
  return outcome
}

/** 무장 요청 하나. 이동을 하지 않고 결과만 정한다. */
async function arm(provider: SignInProvider, csrfToken: string | null): Promise<LinkOutcome> {
  if (csrfToken === null) {
    /*
     * 토큰이 없으면 보내지 않는다. 보내도 서버가 403으로 거절하므로 결과는 같지만, 그 요청은
     * 실패가 예정된 상태 변경 시도 하나를 로그에 남긴다.
     */
    return 'failed'
  }

  // 시한은 세션 확인·로그아웃·재인증 시작과 같은 값이다. 같은 진입점을 지나므로 갈라질 이유가 없다.
  const controller = new AbortController()
  const deadline = setTimeout(() => {
    controller.abort()
  }, SESSION_REQUEST_TIMEOUT_MS)

  try {
    const response = await fetch(linkPath(provider), {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { [CSRF_HEADER]: csrfToken },
      signal: controller.signal,
    })
    if (response.status === 204) {
      // 서버가 허가를 발급하고 흐름을 심었다. 여기서 성공이 확정된다.
      return 'started'
    }
  } catch {
    // 네트워크 단절·응답 유실·시한 초과도 아래와 같은 결과다. 무엇이 일어났는지 추측하지 않는다.
  } finally {
    clearTimeout(deadline)
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
