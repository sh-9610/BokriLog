/*
 * Vitest 전역 설정.
 *
 * 1. Testing Library 매처 등록과 렌더 정리.
 * 2. 네트워크 진입점(fetch, XMLHttpRequest, sendBeacon)을 감시 훅으로 교체해
 *    "외부 요청 0건"을 관측 가능하게 만든다. 요청이 발생하면 기록하고 즉시 실패시킨다.
 * 3. 브라우저 저장소(localStorage, sessionStorage, cookie, IndexedDB) 사용을 관측한다.
 *    금지 목록은 DEVELOPMENT.md의 `## 프런트엔드` 절과 같아야 한다.
 * 4. 세션 확인 요청 하나만 시험이 명시적으로 열 수 있는 탈출구를 둔다(`stubSessionRequest`).
 *    전역 거부는 그대로다. 아래 `SESSION_ENDPOINT` 절의 근거를 참고한다.
 */

import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'

import { SESSION_ENDPOINT } from '../content/auth'

const observedRequests: string[] = []
const observedIndexedDbNames: string[] = []
const observedSessionRequests: string[] = []

/**
 * 세션 확인 요청에 답할 함수. 시험이 설치하지 않으면 `null`이고, 그 경로도 다른 모든 요청과 함께
 * 거부된다.
 *
 * 범위를 좁힌 탈출구다. 전역 거부를 걷어내지 않는 이유는 그 훅이 이 저장소의 브라우저 경계
 * (외부 폰트·CDN·분석 금지, 진입 화면 요청 0건)를 관측 가능하게 만드는 유일한 장치이기 때문이다.
 * 여기서 여는 것은 URL 하나(`/api/auth/session`)이고, 그것도 시험이 이 함수를 부른 동안만이다.
 * `afterEach`가 매번 닫는다.
 */
let sessionResponder: (() => Promise<Response>) | null = null

/**
 * 세션 확인 요청 하나를 이 시험 동안만 허용한다.
 *
 * 로그인 상태 화면(`/onboarding`·`/dashboard`)은 서버 세션으로 상태를 확인하므로, 그 분기를
 * 단위 시험에서 다루려면 이 요청 하나가 성립해야 한다. 진입 화면은 이 함수를 부르지 않으며
 * 그 화면의 "요청 0건" 단정은 그대로 유지된다.
 */
export function stubSessionRequest(responder: () => Promise<Response>): void {
  sessionResponder = responder
}

/** 허용된 세션 확인 요청의 기록. 몇 번 불렸는지 단정하는 데 쓴다. */
export function getObservedSessionRequests(): readonly string[] {
  return [...observedSessionRequests]
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

globalThis.fetch = (input: RequestInfo | URL): Promise<Response> => {
  const url = toUrl(input)
  if (sessionResponder !== null && url === SESSION_ENDPOINT) {
    observedSessionRequests.push(url)
    return sessionResponder()
  }
  return Promise.reject(refuse('fetch', url))
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
})

afterEach(() => {
  cleanup()
  // 탈출구는 시험 하나의 범위다. 다음 시험은 다시 전역 거부에서 시작한다.
  sessionResponder = null
  observedRequests.length = 0
  observedIndexedDbNames.length = 0
  observedSessionRequests.length = 0
  window.localStorage.clear()
  window.sessionStorage.clear()
  // 한 시험이 남긴 쿠키가 다음 시험의 저장소 단정을 엉뚱하게 실패시키지 않게 한다.
  clearCookies()
})
