import { useEffect, useState } from 'react'

import { DASHBOARD_PATH, ONBOARDING_PATH, SESSION_ENDPOINT } from '../content/auth'
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
export type SessionDestination = 'dashboard' | 'entry' | 'onboarding'

export interface SessionSnapshot {
  readonly state: SessionState
  readonly destination: SessionDestination
}

/**
 * 로그인 상태 경로가 자기 화면을 그려도 되는지에 대한 답.
 *
 * `redirecting`은 세션은 있지만 서버가 다른 목적지를 골랐다는 뜻이다. 그 경우 이 화면은 아무
 * 사용자 데이터도 그리지 않고 서버가 고른 목적지로 문서를 다시 요청한다.
 */
export type SessionGate = 'checking' | 'ready' | 'redirecting' | 'unauthenticated'

/** 목적지 종류 → 그 목적지의 내부 경로. 서버 허용 목록·Nginx 허용 목록과 같은 집합이다. */
const DESTINATION_PATHS = {
  dashboard: DASHBOARD_PATH,
  onboarding: ONBOARDING_PATH,
} as const

/** 화면이 존재하는 목적지. `entry`는 화면이 아니라 "인증 화면으로 되돌린다"는 뜻이다. */
export type ScreenDestination = keyof typeof DESTINATION_PATHS

/**
 * 세션 확인 요청의 시한(ms).
 *
 * 없으면 진입점의 읽기 timeout(30초)까지 화면이 "확인 중"에 머문다. 그 30초 동안 사용자는
 * 되돌아갈 경로도, 무엇이 일어나는지도 보지 못한다. 시한이 지나면 다른 모든 실패와 같은
 * `unauthenticated`로 수렴한다 — 자동 재시도는 하지 않는다.
 */
const SESSION_REQUEST_TIMEOUT_MS = 10_000

const CHECKING: SessionSnapshot = { state: 'checking', destination: 'entry' }

const UNAUTHENTICATED: SessionSnapshot = { state: 'unauthenticated', destination: 'entry' }

/** 서버가 준 목적지 종류. 아는 값이 아니면 `entry`다. */
function destinationOf(value: unknown): SessionDestination {
  return value === 'dashboard' || value === 'onboarding' ? value : 'entry'
}

/** 서버 응답에서 읽는 값. `user_id`·`subject`·토큰은 애초에 오지 않는다. */
function snapshotOf(payload: unknown): SessionSnapshot {
  if (typeof payload !== 'object' || payload === null || !('authenticated' in payload)) {
    return UNAUTHENTICATED
  }
  if (payload.authenticated !== true) {
    return UNAUTHENTICATED
  }
  return {
    state: 'authenticated',
    destination: destinationOf('destination' in payload ? payload.destination : null),
  }
}

export function useSessionState(): SessionSnapshot {
  const [snapshot, setSnapshot] = useState<SessionSnapshot>(CHECKING)

  useEffect(() => {
    let mounted = true
    let inFlight: AbortController | null = null

    /** 화면을 떠난 뒤와, 새 확인이 이 확인을 대체한 뒤에는 상태를 바꾸지 않는다. */
    const settle = (controller: AbortController, next: SessionSnapshot): void => {
      if (mounted && inFlight === controller) {
        setSnapshot(next)
      }
    }

    const check = async (): Promise<void> => {
      inFlight?.abort()
      const controller = new AbortController()
      inFlight = controller
      setSnapshot(CHECKING)
      const deadline = setTimeout(() => {
        controller.abort()
        /*
         * 시한의 결과는 시한이 정한다.
         *
         * `abort()`가 부른 거부를 기다리지 않는 이유는 그 거부가 온다는 보장이 이 코드의 것이
         * 아니기 때문이다. 그리고 이 확인은 여기서 끝난다 — 늦게 도착한 응답이 시한 뒤에
         * 상태를 되돌리면 사용자는 이미 인증 화면을 보고 있다.
         */
        settle(controller, UNAUTHENTICATED)
        if (inFlight === controller) {
          inFlight = null
        }
      }, SESSION_REQUEST_TIMEOUT_MS)

      try {
        const response = await fetch(SESSION_ENDPOINT, {
          method: 'GET',
          // 동일 출처 세션 쿠키만 실어 보낸다. 토큰을 헤더로 들고 다니지 않는다.
          credentials: 'same-origin',
          headers: { Accept: 'application/json' },
          // 뒤로 가기 복원과 공유 캐시가 이전 응답을 되살리지 못하게 한다. 서버도 이 응답에
          // `Cache-Control: no-store`를 붙이지만, 요청 쪽에서도 막는 편이 한 겹 더 확실하다.
          cache: 'no-store',
          signal: controller.signal,
        })
        if (!response.ok) {
          settle(controller, UNAUTHENTICATED)
          return
        }
        const payload: unknown = await response.json()
        settle(controller, snapshotOf(payload))
      } catch {
        // 취소·시한 초과·네트워크 단절·JSON 아님이 모두 같은 값으로 수렴한다.
        settle(controller, UNAUTHENTICATED)
      } finally {
        clearTimeout(deadline)
      }
    }

    /**
     * 뒤로 가기 복원(bfcache).
     *
     * UX-DR22는 "로그아웃·세션 만료·삭제 후 뒤로 가기 복원으로 민감 화면이 노출되지 않아야 한다"를
     * 요구한다. bfcache에서 되살아난 문서는 마운트를 다시 하지 않으므로 위 확인이 다시 돌지 않고,
     * 화면은 저장된 그대로 "인증됨"을 계속 주장한다. `persisted`가 참인 `pageshow`가 그 복원의
     * 유일한 신호다.
     */
    const onPageShow = (event: PageTransitionEvent): void => {
      if (event.persisted) {
        void check()
      }
    }

    window.addEventListener('pageshow', onPageShow)
    void check()

    return () => {
      mounted = false
      window.removeEventListener('pageshow', onPageShow)
      inFlight?.abort()
    }
  }, [])

  return snapshot
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
 */
export function useSessionGate(expected: ScreenDestination): SessionGate {
  const { destination, state } = useSessionState()

  const target =
    state === 'authenticated' && destination !== expected && destination !== 'entry'
      ? DESTINATION_PATHS[destination]
      : null

  useEffect(() => {
    if (target !== null) {
      replaceLocation(target)
    }
  }, [target])

  if (state === 'checking') {
    return 'checking'
  }
  if (state === 'unauthenticated' || destination === 'entry') {
    // 미인증과 "인증됐다는데 목적지가 인증 화면"은 화면이 할 일이 같다. 되돌린다.
    return 'unauthenticated'
  }
  return destination === expected ? 'ready' : 'redirecting'
}
