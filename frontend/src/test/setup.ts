/*
 * Vitest 전역 설정.
 *
 * 1. Testing Library 매처 등록과 렌더 정리.
 * 2. 네트워크 진입점(fetch, XMLHttpRequest, sendBeacon)을 감시 훅으로 교체해
 *    "외부 요청 0건"을 관측 가능하게 만든다. 요청이 발생하면 기록하고 즉시 실패시킨다.
 * 3. 브라우저 저장소(localStorage, sessionStorage, cookie, IndexedDB) 사용을 관측한다.
 *    금지 목록은 DEVELOPMENT.md의 `## 프런트엔드` 절과 같아야 한다.
 */

import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'

const observedRequests: string[] = []
const observedIndexedDbNames: string[] = []

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

globalThis.fetch = (input: RequestInfo | URL): Promise<Response> =>
  Promise.reject(refuse('fetch', toUrl(input)))

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
})

afterEach(() => {
  cleanup()
  observedRequests.length = 0
  observedIndexedDbNames.length = 0
  window.localStorage.clear()
  window.sessionStorage.clear()
  // 한 시험이 남긴 쿠키가 다음 시험의 저장소 단정을 엉뚱하게 실패시키지 않게 한다.
  clearCookies()
})
