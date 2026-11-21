import { CSRF_HEADER } from '../content/auth'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/*
 * 동일 출처 상태 변경 요청의 전송 모양과 JSON 판독의 단일 정의(Epic 4 회고 AI-8 ②).
 *
 * 이 모듈은 전송 모양만 소유하고 판정은 갖지 않는다 — 시한·상태 매핑·결과 모양(`{ok,payload,code}` 같은)은 각 호출자의
 * 계약이다. 그래서 `postJson`은 `Response`를 그대로 돌려주고, 상태 하나만 보는 204 묶음만 `postNoContent`가 결과까지 접는다.
 *
 * 요청 init 규칙은 하나다 — `POST`, 세션 쿠키(`credentials: 'same-origin'`), `cache: 'no-store'`, 헤더는 평범한 객체로
 * `Accept`와 CSRF 토큰, 본문이 있을 때만 `Content-Type`, 멱등 키를 받았을 때만 `Idempotency-Key`. 동일 출처 `Origin`은
 * 브라우저가 싣고 스크립트가 만들 수 없으므로 여기에 그것을 다루는 코드가 없는 것이 정상이다.
 *
 * 광고 링크에서 시작한 수동 동기화는 문서 이동과 함께 전송이 끝나도록 `keepalive`를 쓴다.
 */

/** JSON 객체 하나. 응답 해석기가 받는 모양이다. */
export type JsonObject = Readonly<Record<string, unknown>>

/** 본문을 JSON으로 읽는다. 깨진 JSON·빈 본문이면 예외 없이 `null`이다 — 오류 계약은 언제나 JSON이지만 그 사실에 기대지 않는다. */
export async function readJson(response: Response): Promise<unknown> {
  try {
    return (await response.json()) as unknown
  } catch {
    return null
  }
}

/** 배열이 아닌 객체면 그대로, 그 밖(배열·`null`·원시값)은 `null`이다. */
export function objectOf(value: unknown): JsonObject | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as JsonObject)
    : null
}

/** `postJson`의 선택값. 본문·멱등 키·시한은 있을 때만 요청에 실린다. */
export interface PostJsonOptions {
  readonly csrfToken: string
  /**
   * `JSON.stringify` 한 번으로 본문이 된다. 지정하지 않거나 `null`이면 본문도 `Content-Type`도 없다 — 본문 없는 요청을
   * `null`로 표현하는 호출자가 문자열 `"null"`을 보내지 않게 한다.
   */
  readonly body?: unknown
  readonly idempotencyKey?: string
  /** 시한·중단은 호출자가 정한다. 지정하지 않으면 시한이 없다. */
  readonly signal?: AbortSignal
  readonly keepalive?: boolean
}

/**
 * 동일 출처 JSON POST 하나. 응답을 그대로 돌려주고 상태를 해석하지 않는다. 네트워크 단절·중단은 `fetch`의 거절 그대로다.
 * `async`라서 본문 직렬화 실패도 동기 예외가 아니라 거절로 나간다.
 *
 * 전역 `fetch`를 호출 시점에 찾는다 — 시험이 그것을 바꿔 끼운다.
 */
export async function postJson(path: string, options: PostJsonOptions): Promise<Response> {
  const headers: Record<string, string> = {
    Accept: 'application/json',
    [CSRF_HEADER]: options.csrfToken,
  }
  const init: RequestInit = {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers,
  }
  if (options.body !== undefined && options.body !== null) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(options.body)
  }
  if (options.idempotencyKey !== undefined) {
    headers['Idempotency-Key'] = options.idempotencyKey
  }
  if (options.signal !== undefined) {
    init.signal = options.signal
  }
  if (options.keepalive === true) init.keepalive = true
  return await fetch(path, init)
}

/**
 * 본문 없이 204 하나만 성공으로 보는 상태 변경 — 로그아웃·재인증 무장처럼 결과가 "됐다/안 됐다" 하나인 요청이 쓴다.
 *
 * 자동 재시도하지 않는다. 실패를 반복 요청으로 덮으면 사용자는 무엇이 일어났는지 알 수 없다.
 *
 * @returns 서버가 204로 답했는가. 그 밖(403·404·네트워크 단절·시한 초과)은 전부 거짓 하나다
 */
export async function postNoContent(path: string, csrfToken: string | null): Promise<boolean> {
  if (csrfToken === null) {
    /*
     * 토큰이 없으면 보내지 않는다. 보내도 서버가 403으로 거절하므로 결과는 같지만, 그 요청은 실패가 예정된 상태 변경 시도
     * 하나를 로그에 남긴다.
     */
    return false
  }

  // 시한은 세션 확인과 같은 값이다. 짧은 상태 변경 하나라 세션 요청과 다른 관용을 둘 근거가 없다.
  const controller = new AbortController()
  const deadline = setTimeout(() => {
    controller.abort()
  }, SESSION_REQUEST_TIMEOUT_MS)

  try {
    const response = await postJson(path, { csrfToken, signal: controller.signal })
    return response.status === 204
  } catch {
    // 네트워크 단절·응답 유실·시한 초과도 같은 결과다. 무엇이 일어났는지 추측하지 않는다.
    return false
  } finally {
    clearTimeout(deadline)
  }
}
