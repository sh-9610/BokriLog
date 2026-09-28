/*
 * Vitest 전역 설정.
 *
 * 1. Testing Library 매처 등록과 렌더 정리.
 * 2. 네트워크 진입점(fetch, XMLHttpRequest, sendBeacon)을 감시 훅으로 교체해
 *    "외부 요청 0건"을 관측 가능하게 만든다. 요청이 발생하면 기록하고 즉시 실패시킨다.
 * 3. 브라우저 저장소(localStorage, sessionStorage, cookie, IndexedDB) 사용을 관측한다.
 *    금지 목록은 DEVELOPMENT.md의 `## 프런트엔드` 절과 같아야 한다.
 * 4. 시험이 (메서드, URL) 쌍 단위로 명시적으로 열 수 있는 탈출구를 둔다(`stubSessionRequest`,
 *    `stubLogoutRequest`, `stubReauthenticationRequest`, `stubLinkStatusRequest`,
 *    `stubLinkRequest`, `stubUnlinkPrepareRequest`, `stubUnlinkConfirmRequest`,
 *    `stubUnlinkCancelRequest`, `stubConnectionStatusRequest`, `stubConnectionPrepareRequest`,
 *    `stubConnectRequest`, `stubConnectionCancelRequest`).
 *    전역 거부는 그대로다. 아래 `responders` 절의 근거를 참고한다.
 */

import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'

import {
  LINK_BASE_PATH,
  linkPath,
  LOGOUT_ENDPOINT,
  reauthenticatePath,
  SESSION_ENDPOINT,
  unlinkCancelPath,
  unlinkConfirmPath,
  unlinkPath,
  type SignInProvider,
} from '../content/auth'
import { BYBIT_CANCEL_PATH, BYBIT_CONNECT_PATH, BYBIT_CONNECTION_PATH } from '../content/connection'
import { FIRST_SNAPSHOT_PATH } from '../content/firstSnapshot'
import { PLAN_RESULT_PATH } from '../content/planResult'

const observedRequests: string[] = []
const observedIndexedDbNames: string[] = []
const observedSessionRequests: string[] = []
const observedAllowedRequests: string[] = []

/**
 * 열린 요청과 그것에 답할 함수. 키는 `"<메서드> <URL>"`이다.
 *
 * 범위를 좁힌 탈출구다. 전역 거부를 걷어내지 않는 이유는 그 훅이 이 저장소의 브라우저 경계
 * (외부 폰트·CDN·분석 금지, 진입 화면 요청 0건)를 관측 가능하게 만드는 유일한 장치이기 때문이다.
 *
 * 키가 URL 하나가 아니라 (메서드, URL) 쌍인 것이 Story 1.5의 변화다. URL만 보면 `/api/auth/session`
 * 을 여는 것이 그 경로의 모든 메서드를 함께 여는 것이 되고, 그러면 시험이 `POST`를 실수로 통과시켜도
 * 아무도 모른다. 쌍으로 좁히면 시험이 연 것만 통과한다. `afterEach`가 매번 전부 닫는다.
 */
const responders = new Map<string, RequestResponder>()

/**
 * 열린 요청에 답하는 함수.
 *
 * `init`을 받는다. 로그아웃이 세션 귀속 CSRF 토큰을 헤더로 실었는지, 쿠키를 동일 출처로 제한했는지가
 * 그 인자에만 있고, 시험이 `globalThis.fetch`를 다시 감싸 그 값을 훔쳐보는 대신 여기서 넘긴다.
 * 인자를 쓰지 않는 기존 응답 함수는 그대로 통과한다.
 */
export type RequestResponder = (init?: RequestInit) => Promise<Response>

/** 열린 요청의 키. 메서드는 대문자로 정규화한다. */
function requestKey(method: string, url: string): string {
  return `${method.toUpperCase()} ${url}`
}

export function stubFirstSnapshotState(responder: RequestResponder): void {
  responders.set(requestKey('GET', FIRST_SNAPSHOT_PATH), responder)
}
export function stubFirstSnapshotStart(responder: RequestResponder): void {
  responders.set(requestKey('POST', FIRST_SNAPSHOT_PATH), responder)
}
export function stubPlanConditionState(responder: RequestResponder): void {
  responders.set(requestKey('GET', '/api/plans/conditions'), responder)
}

/**
 * 활성 계획의 공식 결과 조회 하나를 이 시험 동안만 허용한다(Story 2.7).
 *
 * 두 로그인 화면(`/dashboard`·`/onboarding`)이 이 요청을 보낸다 — 첫 로그인 세션은 대시보드에서
 * 온보딩으로 밀려나므로 결과 패널이 두 화면에 있다. 열지 않은 시험에서는 전역 거부가 그 요청을
 * 기록하고 거절한다. 그 거절은 "계획 없음"이 아니라 읽기 실패(`unavailable`)다: 결과 패널을 그리지
 * 않고, 첫 자산 패널은 계획이 있다고도 없다고도 말하지 않는다. "계획 없음" 경로를 보려는 시험은
 * 204를 답하게 연다.
 */
export function stubPlanResult(responder: RequestResponder): void {
  responders.set(requestKey('GET', PLAN_RESULT_PATH), responder)
}

/**
 * 세션 확인 요청 하나를 이 시험 동안만 허용한다.
 *
 * 로그인 상태 화면(`/onboarding`·`/dashboard`)은 서버 세션으로 상태를 확인하므로, 그 분기를
 * 단위 시험에서 다루려면 이 요청 하나가 성립해야 한다. 진입 화면은 이 함수를 부르지 않으며
 * 그 화면의 "요청 0건" 단정은 그대로 유지된다.
 */
export function stubSessionRequest(responder: RequestResponder): void {
  responders.set(requestKey('GET', SESSION_ENDPOINT), responder)
}

/**
 * 로그아웃 요청 하나를 이 시험 동안만 허용한다.
 *
 * 로그아웃은 `POST`이고 위 세션 확인과 다른 경로다. 이 함수를 부르지 않은 시험에서 로그아웃이
 * 나가면 전역 거부가 그것을 기록하고 실패시킨다 — 화면이 몰래 상태 변경 요청을 보내지 않는다는
 * 단정이 그 기록이다.
 */
export function stubLogoutRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', LOGOUT_ENDPOINT), responder)
}

/**
 * 그 제공자의 재인증 시작 요청 하나를 이 시험 동안만 허용한다.
 *
 * 제공자마다 경로가 다르므로 여는 것도 제공자 단위다. 시험이 열지 않은 제공자로 요청이 나가면
 * 전역 거부가 그것을 기록하고 실패시킨다 — 패널이 사용자가 고른 것과 다른 제공자를 여는 회귀가
 * 그 기록으로 드러난다.
 */
export function stubReauthenticationRequest(
  provider: SignInProvider,
  responder: RequestResponder,
): void {
  responders.set(requestKey('POST', reauthenticatePath(provider)), responder)
}

/**
 * 연결 상태 조회 하나를 이 시험 동안만 허용한다.
 *
 * 설정 화면만 이 요청을 보낸다. 다른 화면에서 나가면 전역 거부가 그것을 기록하고 실패시킨다 —
 * 계정의 사실을 필요 없는 화면이 물어보지 않는다는 단정이 그 기록이다.
 */
export function stubLinkStatusRequest(responder: RequestResponder): void {
  responders.set(requestKey('GET', LINK_BASE_PATH), responder)
}

/**
 * 그 제공자의 연결 시작 요청 하나를 이 시험 동안만 허용한다.
 *
 * 제공자마다 경로가 다르므로 여는 것도 제공자 단위다({@code stubReauthenticationRequest}와 같은
 * 근거). 시험이 열지 않은 제공자로 요청이 나가면 전역 거부가 그것을 기록하고 실패시킨다 —
 * 화면이 사용자가 고른 것과 다른 제공자를 연결하려는 회귀가 그 기록으로 드러난다.
 */
export function stubLinkRequest(provider: SignInProvider, responder: RequestResponder): void {
  responders.set(requestKey('POST', linkPath(provider)), responder)
}

/**
 * 그 제공자의 해제 **준비** 요청 하나를 이 시험 동안만 허용한다.
 *
 * 최종 확인과 따로 연다. 두 경로를 한 번에 열면 "대화상자를 여는 요청은 아무것도 바꾸지 않는다"를
 * 확인하려는 시험이 확인 요청까지 통과시키고, 그때 그 시험은 자기가 무엇을 본 것인지 알 수 없다.
 */
export function stubUnlinkPrepareRequest(
  provider: SignInProvider,
  responder: RequestResponder,
): void {
  responders.set(requestKey('POST', unlinkPath(provider)), responder)
}

/**
 * 그 제공자의 해제 **최종 확인** 요청 하나를 이 시험 동안만 허용한다.
 *
 * 열지 않은 시험에서 이 요청이 나가면 전역 거부가 그것을 기록하고 실패시킨다 — 화면이 확인 없이
 * 해제를 보내는 회귀가 그 기록으로 드러난다.
 */
export function stubUnlinkConfirmRequest(
  provider: SignInProvider,
  responder: RequestResponder,
): void {
  responders.set(requestKey('POST', unlinkConfirmPath(provider)), responder)
}

/**
 * 그 제공자의 해제 **취소** 요청 하나를 이 시험 동안만 허용한다.
 *
 * 취소는 준비가 만든 무장과 허가를 되돌린다. 열지 않은 시험에서 이 요청이 나가면 전역 거부가 그것을
 * 기록하고 실패시킨다 — 대화상자를 닫기만 해도 서버로 나가는 요청이 있다는 사실이 그 기록이다.
 */
export function stubUnlinkCancelRequest(
  provider: SignInProvider,
  responder: RequestResponder,
): void {
  responders.set(requestKey('POST', unlinkCancelPath(provider)), responder)
}

/**
 * Bybit 연결 상태 조회 하나를 이 시험 동안만 허용한다(Story 2.1).
 *
 * 설정 화면만 이 요청을 보내고, 그것도 게이트가 `ready`일 때만이다. 다른 화면에서 나가면 전역
 * 거부가 그것을 기록하고 실패시킨다 — 계정의 사실을 필요 없는 화면이 묻지 않는다는 단정이다.
 */
export function stubConnectionStatusRequest(responder: RequestResponder): void {
  responders.set(requestKey('GET', BYBIT_CONNECTION_PATH), responder)
}

/**
 * 연결 **준비** 요청 하나를 이 시험 동안만 허용한다.
 *
 * 최종 확인과 따로 연다. 두 경로를 한 번에 열면 "폼을 여는 요청은 아무것도 바꾸지 않는다"를
 * 확인하려는 시험이 확인 요청까지 통과시키고, 그때 그 시험은 자기가 무엇을 본 것인지 알 수 없다
 * (`stubUnlinkPrepareRequest`와 같은 근거).
 */
export function stubConnectionPrepareRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', BYBIT_CONNECTION_PATH), responder)
}

/**
 * 연결 **최종 확인** 요청 하나를 이 시험 동안만 허용한다. <b>자격정보가 나가는 유일한 경로다.</b>
 *
 * 열지 않은 시험에서 이 요청이 나가면 전역 거부가 그것을 기록하고 실패시킨다 — 화면이 확인 없이
 * 자격정보를 보내는 회귀가 그 기록으로 드러난다.
 */
export function stubConnectRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', BYBIT_CONNECT_PATH), responder)
}

/** 연결 **취소** 요청 하나를 이 시험 동안만 허용한다. 준비가 만든 무장과 허가를 되돌린다. */
export function stubConnectionCancelRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', BYBIT_CANCEL_PATH), responder)
}

/** 허용된 세션 확인 요청의 기록. 몇 번 불렸는지 단정하는 데 쓴다. */
export function getObservedSessionRequests(): readonly string[] {
  return [...observedSessionRequests]
}

/**
 * 허용된 요청 전부의 기록. `"<메서드> <URL>"` 형태이며 순서가 보존된다.
 *
 * 로그아웃이 정확히 한 번 나갔는지, 세션 확인 뒤에 나갔는지를 이 목록으로 본다.
 */
export function getObservedAllowedRequests(): readonly string[] {
  return [...observedAllowedRequests]
}

/** 지금까지 관측된 네트워크 요청. 비어 있어야 한다. */
export function getObservedRequests(): readonly string[] {
  return [...observedRequests]
}

/** 브라우저 저장소에 남은 항목과 시도된 IndexedDB 접근. 비어 있어야 한다. */
export function getObservedStorageEntries(): readonly string[] {
  const entries: string[] = []

  for (let index = 0; index < window.localStorage.length; index += 1) {
    entries.push(`localStorage:${window.localStorage.key(index) ?? ''}`)
  }
  for (let index = 0; index < window.sessionStorage.length; index += 1) {
    entries.push(`sessionStorage:${window.sessionStorage.key(index) ?? ''}`)
  }
  if (document.cookie !== '') {
    entries.push(`cookie:${document.cookie}`)
  }
  for (const name of observedIndexedDbNames) {
    entries.push(`indexedDB:${name}`)
  }

  return entries
}

/** 현재 문서에 남은 모든 쿠키를 만료시킨다. jsdom에는 일괄 삭제 API가 없다. */
function clearCookies(): void {
  for (const pair of document.cookie.split(';')) {
    const name = pair.split('=')[0]?.trim() ?? ''
    if (name !== '') {
      document.cookie = `${name}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT`
    }
  }
}

function refuse(kind: string, url: string): Error {
  observedRequests.push(`${kind} ${url}`)
  return new Error(`진입 화면은 네트워크 요청을 보내지 않습니다: ${kind} ${url}`)
}

function toUrl(input: unknown): string {
  if (typeof input === 'string') {
    return input
  }
  if (input instanceof URL) {
    return input.href
  }
  if (input instanceof Request) {
    return input.url
  }
  return String(input)
}

/** 요청의 메서드. `init`이 우선이고, `Request` 객체면 그것이 들고 있는 값이다. */
function toMethod(input: unknown, init?: RequestInit): string {
  if (typeof init?.method === 'string') {
    return init.method
  }
  if (input instanceof Request) {
    return input.method
  }
  return 'GET'
}

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = toUrl(input)
  const method = toMethod(input, init).toUpperCase()
  const responder = responders.get(requestKey(method, url))
  if (responder !== undefined) {
    observedAllowedRequests.push(requestKey(method, url))
    if (method === 'GET' && url === SESSION_ENDPOINT) {
      observedSessionRequests.push(url)
    }
    return responder(init)
  }
  return Promise.reject(refuse(`fetch:${method}`, url))
}

XMLHttpRequest.prototype.open = function open(method: string, url: string | URL): void {
  throw refuse(`xhr:${method}`, toUrl(url))
}

Object.defineProperty(navigator, 'sendBeacon', {
  configurable: true,
  writable: true,
  value: (url: string | URL): boolean => {
    throw refuse('sendBeacon', toUrl(url))
  },
})

/*
 * IndexedDB도 금지 목록에 있다. e2e만 검사하고 단위 시험이 비어 있으면 구멍이 남는다.
 * 접근을 기록한 뒤 거부해 `getObservedStorageEntries()`가 관측할 수 있게 한다.
 */
Object.defineProperty(window, 'indexedDB', {
  configurable: true,
  writable: true,
  value: {
    open: (name: string): never => {
      observedIndexedDbNames.push(name)
      throw new Error(`진입 화면은 IndexedDB를 사용하지 않습니다: ${name}`)
    },
    deleteDatabase: (name: string): never => {
      observedIndexedDbNames.push(name)
      throw new Error(`진입 화면은 IndexedDB를 사용하지 않습니다: ${name}`)
    },
    databases: (): Promise<never[]> => Promise.resolve([]),
    cmp: (): number => 0,
  },
})

beforeEach(() => {
  observedRequests.length = 0
  observedIndexedDbNames.length = 0
  observedSessionRequests.length = 0
  observedAllowedRequests.length = 0
})

afterEach(() => {
  cleanup()
  // 탈출구는 시험 하나의 범위다. 다음 시험은 다시 전역 거부에서 시작한다.
  responders.clear()
  observedRequests.length = 0
  observedIndexedDbNames.length = 0
  observedSessionRequests.length = 0
  observedAllowedRequests.length = 0
  window.localStorage.clear()
  window.sessionStorage.clear()
  // 한 시험이 남긴 쿠키가 다음 시험의 저장소 단정을 엉뚱하게 실패시키지 않게 한다.
  clearCookies()
})
