import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'

import {
  authContent,
  DASHBOARD_PATH,
  noticePath,
  ONBOARDING_PATH,
  SESSION_ENDPOINT,
  SETTINGS_PATH,
} from '../content/auth'
import { connectionContent } from '../content/connection'
import { replaceLocation } from './navigation'

/**
 * 서버 세션 확인.
 *
 * 로그인 상태 경로(`/onboarding`·`/dashboard`)에서만 쓴다. 진입 화면(`/`)은 이 훅을 부르지 않으며,
 * 그 화면의 "문서·번들 외 요청 0건"은 E2E가 고정한다. 목적지는 서버가 302로 정하므로 진입 화면이
 * 세션을 물어볼 이유가 없다.
 *
 * 세션 확인 없이 로그인 상태를 주장하지 않는다. 응답이 오기 전에는 `checking`이고, 실패·오류·미인증은
 * 모두 `unauthenticated` 하나로 수렴한다. 그 값에서 "그런 계정이 있었는가"를 복원할 수 없다.
 *
 * 자동 재시도하지 않는다. 실패를 반복 요청으로 덮으면 사용자는 무엇이 일어났는지 알 수 없고,
 * 화면은 인증 화면으로 되돌리는 대신 계속 기다리게 된다.
 */
export type SessionState = 'authenticated' | 'checking' | 'unauthenticated'

/**
 * 서버가 고른 목적지의 종류.
 *
 * 경로가 아니라 종류다(`GET /api/auth/session`). 서버가 경로 문자열을 내보내면 그 값이 리다이렉트
 * 입력으로 쓰일 자리가 생기므로, 경로를 아는 것은 아래 표 하나뿐이다.
 */
export type SessionDestination = 'dashboard' | 'entry' | 'onboarding' | 'settings'

export interface SessionSnapshot {
  readonly state: SessionState
  readonly destination: SessionDestination
  /**
   * 세션 귀속 CSRF 토큰. 인증된 응답에만 실린다.
   *
   * 메모리에만 둔다. `localStorage`·`sessionStorage`·쿠키·URL 어디에도 쓰지 않으며, 그래서 브라우저에
   * 남는 값은 `BOKRILOG_SESSION` 쿠키 하나로 유지된다. 문서를 다시 요청하면 이 값은 사라지고 새
   * 세션 확인이 다음 토큰을 준다.
   */
  readonly csrfToken: string | null
  /**
   * 이 세션이 재인증을 요구받았는가.
   *
   * 서버가 표시된 세션에만 싣는 필드다(`GET /api/auth/session`). 폐기된 세션과 처음부터 없는
   * 세션의 본문에는 이 필드가 없고 그래야 한다 — 그 둘에는 회복 경로가 없으므로 구분할 이유도
   * 없다. 표시된 세션만 갈라지는 이유는 반대다: 구분하지 않으면 그 세션은 CSRF 토큰을 받지
   * 못하고 로그인 버튼은 서버가 거절하며 목적지는 다시 미인증으로 답한다 — 되살릴 방법이 없는
   * 무한 왕복이다.
   *
   * 참이면 `csrfToken`도 함께 온다. 회복에 필요한 것이 그 둘이다.
   */
  readonly reauthRequired: boolean
  /**
   * 인증을 한 번 관측한 뒤의 미인증인가.
   *
   * 서버 응답은 두 경우에 같다 — 만료된 세션과 처음부터 없는 세션 모두
   * `{"authenticated":false,"destination":"entry"}`다. 그래야 한다: 응답으로 갈라 주면 세션 존재
   * 이력이 새고 계약에 필드가 늘어난다. 구분 재료는 이 훅의 메모리에 있는 전이 하나이고, 그
   * 전이를 만드는 경로가 아래 `pageshow` 재확인과 `recheck`다.
   *
   * **서버가 미인증이라고 답했을 때만** 참이다. 시한 초과·네트워크 단절·취소는 세션의 사실이
   * 아니라 이 브라우저의 사정이므로 여기서 만료로 세지 않는다 — 세면 일시적 오프라인 하나가
   * 멀쩡한 세션의 사용자를 인증 화면으로 밀어내고 "만료됐다"고 말한다.
   */
  readonly expired: boolean
}

/**
 * 로그인 상태 경로가 자기 화면을 그려도 되는지에 대한 답.
 *
 * `redirecting`은 세션은 있지만 서버가 다른 목적지를 골랐다는 뜻이다. 그 경우 이 화면은 아무
 * 사용자 데이터도 그리지 않고 서버가 고른 목적지로 문서를 다시 요청한다.
 */
export type SessionGate =
  'checking' | 'reauth-required' | 'ready' | 'redirecting' | 'unauthenticated'

/** 목적지 종류 → 그 목적지의 내부 경로. 서버 허용 목록·Nginx 허용 목록과 같은 집합이다. */
const DESTINATION_PATHS = {
  dashboard: DASHBOARD_PATH,
  onboarding: ONBOARDING_PATH,
  settings: SETTINGS_PATH,
} as const

/** 화면이 존재하는 목적지. `entry`는 화면이 아니라 "인증 화면으로 되돌린다"는 뜻이다. */
export type ScreenDestination = keyof typeof DESTINATION_PATHS

/**
 * 이 화면이 서버가 고른 목적지 위에 머물러도 되는가.
 *
 * 서버가 고르는 목적지는 "로그인 뒤 착지할 화면"이다 — 신규 사용자는 온보딩, 기존 사용자는
 * 유효한 원래 목적지 또는 기본 대시보드. 그래서 종류가 다르면 화면을 그리지 않고 그 목적지로
 * 문서를 다시 요청하는 것이 기본 규칙이고, 그 규칙이 "이미 온보딩을 지난 계정에 온보딩 화면이
 * 그려지는" 상태를 막는다.
 *
 * **설정 화면은 어느 방향으로도 이 규칙에 참여하지 않는다.** 그 화면은 서버가 고르는 착지 대상이
 * 아니라 사용자가 스스로 여는 자리다. 밀려나는 쪽으로 두면 그 경로에 도달할 방법이 아예 없어지고
 * (방금 가입한 계정의 `destination`은 `onboarding`이라 설정 링크가 곧바로 되튕긴다, 연결 왕복이
 * 돌아오는 자리도 그 화면이다), 미는 쪽으로 두면 미인증으로 `/settings`를 열어 로그인한 사용자가
 * `destination`이 `settings`로 굳어 그 세션 내내 대시보드에 닿지 못한다. 그래서 미는 일은 착지
 * 화면 둘(온보딩·대시보드) 사이에서만 일어난다.
 */
function staysOn(expected: ScreenDestination, destination: SessionDestination): boolean {
  if (destination === expected) {
    return true
  }
  return expected === 'settings' || destination === 'settings'
}

/**
 * 세션 확인 요청의 시한(ms).
 *
 * 없으면 진입점의 읽기 timeout(30초)까지 화면이 "확인 중"에 머문다. 그 30초 동안 사용자는
 * 되돌아갈 경로도, 무엇이 일어나는지도 보지 못한다. 시한이 지나면 다른 모든 실패와 같은
 * `unauthenticated`로 수렴한다 — 자동 재시도는 하지 않는다.
 *
 * 로그아웃 전송도 같은 값을 쓴다(`signOut`). 두 요청이 같은 진입점을 지나므로 시한이 갈라질
 * 이유가 없고, 값이 두 곳에 적히면 한쪽만 바뀌는 날이 온다.
 */
export const SESSION_REQUEST_TIMEOUT_MS = 10_000

const CHECKING: SessionSnapshot = {
  state: 'checking',
  destination: 'entry',
  csrfToken: null,
  reauthRequired: false,
  expired: false,
}

const UNAUTHENTICATED: SessionSnapshot = {
  state: 'unauthenticated',
  destination: 'entry',
  csrfToken: null,
  reauthRequired: false,
  expired: false,
}

/** 서버가 준 목적지 종류. 아는 값이 아니면 `entry`다. */
function destinationOf(value: unknown): SessionDestination {
  return value === 'dashboard' || value === 'onboarding' || value === 'settings' ? value : 'entry'
}

/** 서버가 준 CSRF 토큰. 비어 있거나 문자열이 아니면 없는 것과 같다. */
function tokenOf(payload: object): string | null {
  if (!('csrfToken' in payload)) {
    return null
  }
  const value = payload.csrfToken
  return typeof value === 'string' && value !== '' ? value : null
}

/** 서버가 표시한 세션인가. 필드가 없거나 `true`가 아니면 표시되지 않은 것과 같다. */
function markedOf(payload: object): boolean {
  return 'reauthRequired' in payload && payload.reauthRequired === true
}

/**
 * 서버 응답에서 읽는 값. `user_id`·`subject`·제공자 토큰은 애초에 오지 않는다.
 *
 * @returns 이 응답이 세션 상태를 답한 것이면 그 스냅샷, 아니면 `null`
 */
function snapshotOf(payload: unknown): SessionSnapshot | null {
  if (typeof payload !== 'object' || payload === null || !('authenticated' in payload)) {
    /*
     * 이 응답은 세션 상태를 답한 것이 아니다. 화면이 할 일은 미인증과 같지만(인증 화면으로
     * 되돌린다) "서버가 미인증이라고 답했다"로 세지 않는다 — 그것을 만료의 근거로 쓰면 JSON이
     * 아닌 응답 하나가 세션 만료 안내를 만든다.
     */
    return null
  }
  if (payload.authenticated !== true) {
    /*
     * 표시된 세션은 미인증으로 답하면서 필드 둘을 더 싣는다. 회복에 필요한 것이 그 둘이므로
     * 여기서 함께 읽는다 — 표시만 읽고 토큰을 버리면 패널은 그려지지만 어느 버튼도 통하지
     * 않는다.
     */
    if (markedOf(payload)) {
      return {
        state: 'unauthenticated',
        destination: 'entry',
        csrfToken: tokenOf(payload),
        reauthRequired: true,
        expired: false,
      }
    }
    return UNAUTHENTICATED
  }
  return {
    state: 'authenticated',
    destination: destinationOf('destination' in payload ? payload.destination : null),
    csrfToken: tokenOf(payload),
    reauthRequired: false,
    expired: false,
  }
}

/** 이 훅이 화면에 주는 것. 스냅샷과 재확인 하나다. */
export interface SessionAccess extends SessionSnapshot {
  /**
   * 세션을 다시 확인한다.
   *
   * 화면이 "지금 상태가 바뀌었을 수 있다"고 아는 순간에만 부른다. 자동 폴링이 아니다 — 반복
   * 요청은 무엇이 일어났는지 감추고 서버 부하만 만든다.
   *
   * 제품 소비자는 아래 `useSessionGate`의 가시성 재확인이다. 탭이 다시 보이게 되는 순간이
   * 정확히 "그 사이에 상태가 바뀌었을 수 있다"이고, 그 재확인이 만료 감지의 실제 생산자다.
   */
  readonly recheck: () => void
}

export function useSessionState(): SessionAccess {
  const [snapshot, setSnapshot] = useState<SessionSnapshot>(CHECKING)

  /**
   * 인증을 한 번이라도 관측했는가.
   *
   * `useState`가 아니라 `useRef`다. 이 값은 렌더를 유발할 이유가 없고, 확인 함수가 `useEffect`
   * 안에서 자기 자신의 과거 결과를 읽어야 하므로 렌더 사이에 살아 있는 상자가 필요하다.
   */
  const sawAuthenticated = useRef(false)

  /** 지금 도는 확인. `check`가 만들고, 새 확인이 앞의 것을 대체한다. */
  const inFlight = useRef<AbortController | null>(null)

  /** 화면을 떠났는가. 언마운트 뒤에는 상태를 바꾸지 않는다. */
  const mounted = useRef(true)

  const check = useCallback(async (): Promise<void> => {
    inFlight.current?.abort()
    const controller = new AbortController()
    inFlight.current = controller

    /**
     * 이 확인이 아직 최신이고 화면이 살아 있을 때만 상태를 바꾼다.
     *
     * @param answered 서버가 이 상태를 직접 답했는가. 만료 판정의 조건이다
     */
    const settle = (next: SessionSnapshot, answered: boolean): void => {
      if (!mounted.current || inFlight.current !== controller) {
        return
      }
      if (next.state === 'authenticated') {
        sawAuthenticated.current = true
        setSnapshot(next)
        return
      }
      /*
       * 만료는 여기서 정해진다.
       *
       * 인증을 한 번 관측한 뒤에 **서버가 미인증이라고 답한 것**이 만료이고, 첫 확인부터
       * 미인증이면 냉시작이다. 서버 응답은 두 경우에 같으므로 이 전이가 유일한 재료다.
       *
       * `answered`가 조건에 있는 이유는 실패 경로가 같은 값으로 수렴하기 때문이다. 시한 초과·
       * 네트워크 단절·취소·JSON 아님은 세션의 사실이 아니라 이 브라우저의 사정이고, 그것을
       * 만료로 세면 지하철에서 터널을 지나는 사용자가 멀쩡한 세션으로 인증 화면에 밀려나
       * "만료됐다"는 문장을 본다. 그 경우 화면 안의 표시로 끝내는 것이 이 변경 전의 동작이다.
       */
      setSnapshot(
        answered &&
          next.state === 'unauthenticated' &&
          sawAuthenticated.current &&
          /*
           * 표시된 세션은 만료가 아니다. 서버가 미인증으로 답하는 모양은 같지만 결과가 다르다 —
           * 만료는 인증 화면으로 보내고, 표시는 제자리에서 회복 경로를 연다. 이 조건이 없으면
           * 열린 탭이 표시되는 순간 사용자가 "만료됐다"는 문장과 함께 로그인 화면으로 밀려나고,
           * 회복에 필요한 토큰은 그 이동과 함께 사라진다.
           */
          !next.reauthRequired
          ? { ...next, expired: true }
          : next,
      )
    }

    const deadline = setTimeout(() => {
      controller.abort()
      /*
       * 시한의 결과는 시한이 정한다.
       *
       * `abort()`가 부른 거부를 기다리지 않는 이유는 그 거부가 온다는 보장이 이 코드의 것이
       * 아니기 때문이다. 그리고 이 확인은 여기서 끝난다 — 늦게 도착한 응답이 시한 뒤에
       * 상태를 되돌리면 사용자는 이미 인증 화면을 보고 있다.
       */
      settle(UNAUTHENTICATED, false)
      if (inFlight.current === controller) {
        inFlight.current = null
      }
    }, SESSION_REQUEST_TIMEOUT_MS)

    try {
      const response = await fetch(SESSION_ENDPOINT, {
        method: 'GET',
        // 동일 출처 세션 쿠키만 실어 보낸다. 토큰을 헤더로 들고 다니지 않는다.
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
        // 뒤로 가기 복원과 공유 캐시가 이전 응답을 되살리지 못하게 한다. 서버와 진입점도 이
        // 응답에 `Cache-Control: no-store`를 붙이지만, 요청 쪽에서도 막는 편이 한 겹 더 확실하다.
        cache: 'no-store',
        signal: controller.signal,
      })
      if (!response.ok) {
        /*
         * 이 계약에서 세션 확인은 언제나 200이다(미인증도 200). 다른 상태 코드는 세션에 대한
         * 답이 아니라 그 요청이 처리되지 않았다는 뜻이므로 만료의 근거가 되지 않는다.
         */
        settle(UNAUTHENTICATED, false)
        return
      }
      const payload: unknown = await response.json()
      const answer = snapshotOf(payload)
      settle(answer ?? UNAUTHENTICATED, answer !== null)
    } catch {
      // 취소·시한 초과·네트워크 단절·JSON 아님이 모두 같은 값으로 수렴한다.
      settle(UNAUTHENTICATED, false)
    } finally {
      clearTimeout(deadline)
    }
  }, [])

  /**
   * 확인을 처음부터 다시 시작한다.
   *
   * 앞선 결과를 먼저 `checking`으로 되돌린다. 되돌리지 않으면 새 확인이 도는 동안 화면이 이전
   * 답(대개 "인증됨")을 계속 주장하고, 그 창이 정확히 로그아웃·만료가 일어나는 지점이다.
   *
   * 마운트 시점의 첫 확인은 이 함수를 지나지 않는다. 초기 상태가 이미 `checking`이므로 되돌릴
   * 것이 없고, effect 본문에서 상태를 바꾸면 렌더가 한 번 더 돈다.
   */
  const restart = useCallback((): void => {
    setSnapshot(CHECKING)
    void check()
  }, [check])

  /*
   * 세션 확인을 여는 effect는 passive로 둔다. 요청을 시작하는 것은 커밋이 보여 주는 결과가 아니고
   * 답은 어차피 응답이라는 뒤 작업에서 온다. 같은 effect가 붙이는 `pageshow` 재확인도 이 확인의
   * 수명이라 떼지 않는다. 커밋과 함께 성립해야 하는 효과(주소 교체, 가시성 리스너)는 아래
   * `useSessionGate`의 layout effect다.
   */
  useEffect(() => {
    mounted.current = true

    /**
     * 뒤로 가기 복원(bfcache).
     *
     * UX-DR22는 "로그아웃·세션 만료·삭제 후 뒤로 가기 복원으로 민감 화면이 노출되지 않아야 한다"를
     * 요구한다. bfcache에서 되살아난 문서는 마운트를 다시 하지 않으므로 위 확인이 다시 돌지 않고,
     * 화면은 저장된 그대로 "인증됨"을 계속 주장한다. `persisted`가 참인 `pageshow`가 그 복원의
     * 유일한 신호다.
     *
     * 이 경로에 의존하지 않는다. 이 스토리가 진입 문서에 붙인 `Cache-Control: no-store`는
     * Chromium의 bfcache 대상에서 그 문서를 빼므로 실제 뒤로 가기는 문서 재요청이 되고
     * `persisted`는 참으로 오지 않는다. 그래서 만료 감지의 실제 생산자는 아래 `useSessionGate`의
     * 가시성 재확인이며, 이 처리기는 bfcache가 적용되는 브라우저를 위한 두 번째 그물이다.
     */
    const onPageShow = (event: PageTransitionEvent): void => {
      if (event.persisted) {
        restart()
      }
    }

    window.addEventListener('pageshow', onPageShow)
    void check()

    return () => {
      mounted.current = false
      window.removeEventListener('pageshow', onPageShow)
      inFlight.current?.abort()
    }
  }, [check, restart])

  return { ...snapshot, recheck: restart }
}

/** 화면이 게이트와 함께 알아야 하는 값. */
export interface SessionGateResult {
  readonly gate: SessionGate
  /** 로그아웃 요청에 실을 토큰. 게이트가 `ready`가 아니면 없다. */
  readonly csrfToken: string | null
}

/**
 * 인증된 화면의 live region이 말할 문장.
 *
 * 화면당 하나뿐인 `role="status"` 영역의 내용을 두 화면이 같은 규칙으로 고른다. 규칙을 화면마다
 * 두면 한쪽에만 새 상태가 추가되는 자리가 생긴다.
 *
 * 로그아웃 진행이 세션 상태보다 앞선다. 그 순간 사용자가 알아야 하는 것은 자기가 시작한 조작이
 * 도는 중이라는 사실이고, 게이트는 그 조작이 끝날 때까지 `ready`로 남아 있다. 연결·해제·재인증
 * 시작도 같은 이유로 게이트보다 앞서며, 네 조작은 서로를 막으므로 둘이 동시에 참일 수 없다.
 *
 * @returns 말할 문장. 게이트가 `ready`이고 로그아웃도 도는 중이 아니면 화면이 이 값을 쓰지 않는다
 */
export function liveRegionMessage(
  gate: SessionGate,
  signingOut: boolean,
  reauthenticating = false,
  linking = false,
  unlinking = false,
  connecting = false,
): string {
  if (signingOut) {
    return authContent.signOut.pending
  }
  if (linking) {
    return authContent.settings.pending
  }
  if (unlinking) {
    return authContent.settings.unlinkPending
  }
  if (connecting) {
    // Story 2.1의 다섯 번째 진행 상태. 같은 화면이 다섯 조작을 그리므로 문장도 같은 자리에서 난다.
    return connectionContent.pending
  }
  if (reauthenticating) {
    return authContent.reauth.pending
  }
  if (gate === 'reauth-required') {
    return authContent.reauth.status
  }
  if (gate === 'redirecting') {
    return authContent.session.redirecting
  }
  return authContent.session.checking
}

/**
 * 이 화면이 서버가 고른 목적지인지 확인한다.
 *
 * 서버가 주는 `destination`을 버리지 않는다. 버리면 "신규 사용자는 온보딩, 기존 사용자는 유효한
 * 원래 목적지 또는 기본 대시보드"라는 계약의 절반이 브라우저에서 성립하지 않는다 — 사용자가
 * `/onboarding`을 직접 열면 이미 온보딩을 지난 계정에도 온보딩 화면이 그려진다.
 *
 * 종류가 다르면 이 화면을 그리지 않고 서버가 고른 목적지로 보낸다. 그 경로는 아래 표에만 있고,
 * 표에 없는 값(`entry`)은 인증 화면으로 되돌리는 것과 같다.
 *
 * 만료를 관측하면 표지를 달고 진입 화면으로 보낸다. 이 화면 안에서 안내만 바꾸지 않는 이유는 두
 * 가지다 — 인증된 화면의 문서가 히스토리에 남아 있으면 뒤로 가기가 그것을 되살리려 하고, 만료된
 * 사용자가 볼 화면은 로그인 수단이 있는 인증 화면이다. 냉시작 미인증은 표지 없이 그 자리에서
 * 인증 화면을 렌더한다(기존 `signedOut`).
 *
 * 그 만료를 관측할 기회를 이 훅이 만든다. 탭이 다시 보이게 될 때 한 번 재확인하며, 그것이
 * `useSessionState`의 `recheck`를 부르는 유일한 제품 경로다.
 */
export function useSessionGate(expected: ScreenDestination): SessionGateResult {
  const { csrfToken, destination, expired, reauthRequired, recheck, state } = useSessionState()

  /*
   * 탭이 다시 보이게 되면 세션을 다시 확인한다.
   *
   * 만료 감지가 도달 가능한 경로가 이것 하나다. 인증된 화면을 열어 둔 탭은 다시 마운트되지
   * 않으므로 첫 확인의 답을 계속 주장하고, bfcache 재확인은 이 스토리가 붙인 진입 문서의
   * `no-store` 때문에 Chromium에서 발생하지 않는다. 사용자가 다른 탭을 보다 돌아오는 순간이
   * 정확히 "그 사이에 세션이 끝났을 수 있다"이며, 그때 화면이 아직 인증 상태를 주장하고 있으면
   * 그것이 곧 UX-DR22가 막으려는 상태다.
   *
   * 인증을 관측한 뒤에만 듣는다. 폴링이 아니고(가시성이 바뀔 때 한 번), 진입 화면으로 이미
   * 수렴한 뒤에는 다시 확인할 이유가 없다 — 확인해도 답은 같고 요청만 하나 늘어난다.
   * 단일 in-flight·abort 규율은 `recheck`가 그대로 지킨다.
   *
   * 인증된 화면이 커밋되는 그 순간에 듣기 시작한다(layout effect). passive effect는 그리기 뒤
   * 별도 작업으로 돌아서, 화면이 이미 보이는데 아직 듣지 않는 틈이 생긴다. 그 틈에 탭이 다시
   * 보이면 재확인을 놓치고, 시험에서는 부하가 걸린 러너에서만 그 틈이 드러났다(Story 2.12 CI).
   */
  const observing = state === 'authenticated'

  useLayoutEffect(() => {
    if (!observing) {
      return
    }
    const onVisibilityChange = (): void => {
      if (document.visibilityState === 'visible') {
        recheck()
      }
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [observing, recheck])

  const target =
    state === 'authenticated' && destination !== 'entry' && !staysOn(expected, destination)
      ? DESTINATION_PATHS[destination]
      : null

  /*
   * 다른 화면으로 보내는 두 이동(서버가 고른 목적지, 만료 표지)도 layout effect다. "이동 중"을 그린
   * 그 커밋에서 떠난다 — passive effect였을 때는 그 화면이 이미 보이는데 아직 떠나지 않은 틈이
   * 있었고, 그 틈은 부하가 걸린 러너에서만 드러났다(`OnboardingScreen` 목적지 시험). 위 가시성
   * 리스너와 같은 원리다.
   */
  useLayoutEffect(() => {
    if (target !== null) {
      replaceLocation(target)
    }
  }, [target])

  const expiredTarget = state === 'unauthenticated' && expired

  useLayoutEffect(() => {
    if (expiredTarget) {
      replaceLocation(noticePath('expired'))
    }
  }, [expiredTarget])

  if (state === 'checking') {
    return { gate: 'checking', csrfToken: null }
  }
  if (reauthRequired) {
    /*
     * 표시된 세션이다. 제자리에서 회복 경로를 연다.
     *
     * 이동하지 않는 것이 요점이다. 표시된 세션은 이미 자기 목적지에 있고 필요한 것은 그 자리에서
     * 본인을 확인할 수단이지 다른 주소가 아니다. 인증 화면으로 보내면 그 화면의 로그인 버튼은
     * 서버가 "이미 인증된 세션"으로 거절하고 목적지는 다시 미인증으로 답한다 — 무한 왕복이다.
     *
     * 토큰을 보존한다. 재인증 시작과 로그아웃이 둘 다 그 값을 필요로 하고, 표시된 세션이 화면에서
     * 스스로 나갈 수 있게 되는 것도 이 한 줄이다.
     */
    return { gate: 'reauth-required', csrfToken }
  }
  if (expiredTarget) {
    /*
     * 만료의 결과는 이동 하나다.
     *
     * 여기서 진입 화면을 제자리에 그리면 같은 전이가 두 가지 결과를 내고, 그 둘이 서로 경합한다 —
     * 브라우저는 제자리 렌더를 먼저 보여 준 뒤 `/?auth=expired`로 이동하므로 화면이 두 번 바뀌고,
     * 이동이 착지하기 전의 URL은 아직 인증 화면의 경로다. 이동만 남기면 만료 문장의 출처가
     * `noticeKindOf`(주소의 표지) 하나로 유지되고, 그 상태에서 새로고침해도 같은 화면이 나온다.
     * 그 사이 화면은 아무 상태도 주장하지 않는다(`redirecting`).
     */
    return { gate: 'redirecting', csrfToken: null }
  }
  if (state === 'unauthenticated' || destination === 'entry') {
    // 미인증과 "인증됐다는데 목적지가 인증 화면"은 화면이 할 일이 같다. 되돌린다.
    return { gate: 'unauthenticated', csrfToken: null }
  }
  const stays = staysOn(expected, destination)
  return {
    gate: stays ? 'ready' : 'redirecting',
    csrfToken: stays ? csrfToken : null,
  }
}
