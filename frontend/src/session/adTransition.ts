import {
  AD_ELIGIBILITY_PATH,
  AD_TRANSITION_CLAIM_PATH,
  TRANSITION_PATTERN,
} from '../content/adTransition'
import { objectOf, postJson, readJson } from './http'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/*
 * 광고 자격 판정(Story 4.2)과 transition 소비(Story 4.3)의 두 요청.
 *
 * 둘 다 광고 쪽 결정만 묻는다 — 결과 조회의 성공 조건이 아니다. 그래서 어느 실패(네트워크·시간 초과·거절·모르는 응답)도 "광고
 * 없음"과 같은 값으로 접힌다. 호출자는 그 값으로 아무것도 바꾸지 않거나(대시보드) 광고 없이 결과로 간다(중립 화면).
 *
 * 전송 모양은 `http.ts`의 `postJson`이 정하고(동일 출처 쿠키·`no-store`·CSRF 헤더) 시한은 여기서 둔다. 멱등 키 헤더는 싣지
 * 않는다 — 판정의 자연 키는 `(사용자, jobId)`이고 소비의 참조는 그 자체로 일회성이다.
 */

/** GPT ad unit 경로의 모양. 서버 `AdProviderState.AD_UNIT_PATH`와 같다. 모양이 아니면 광고를 시도하지 않는다. */
const AD_UNIT_PATH_PATTERN = /^\/[0-9]+(\/[A-Za-z0-9_.-]+)+$/

/** transition 소비의 답. 광고를 시도하는 것은 `show` 하나이고 나머지는 모두 광고 없이 결과로 간다. */
export type AdTransitionClaim =
  { readonly kind: 'show'; readonly adUnitPath: string } | { readonly kind: 'skip' }

const SKIP: AdTransitionClaim = { kind: 'skip' }

/** 시한이 있는 동일 출처 JSON POST. 200의 본문만 돌려주고, 그 밖의 모든 결말은 `null`이다. */
async function post(path: string, body: unknown, csrfToken: string): Promise<unknown> {
  const controller = new AbortController()
  const deadline = window.setTimeout(() => {
    controller.abort()
  }, SESSION_REQUEST_TIMEOUT_MS)
  try {
    const response = await postJson(path, { csrfToken, body, signal: controller.signal })
    return response.status === 200 ? await readJson(response) : null
  } catch {
    // 네트워크 실패·시간 초과. 광고 쪽 결정을 모르는 것은 광고가 없는 것과 같다.
    return null
  } finally {
    window.clearTimeout(deadline)
  }
}

/**
 * 그 수동 동기화 결과의 광고 자격을 묻는다(Story 4.2). 자격이면 서버가 발급한 transition 참조를, 그 밖의 모든 결말(건너뜀·404·
 * 오류·시간 초과·모양이 아닌 답)은 `null`을 돌려준다.
 */
export async function requestAdEligibility(
  syncJobId: string,
  csrfToken: string,
): Promise<string | null> {
  const fields = objectOf(await post(AD_ELIGIBILITY_PATH, { syncJobId }, csrfToken))
  if (fields?.outcome !== 'ELIGIBLE') {
    return null
  }
  const { transition } = fields
  return typeof transition === 'string' && TRANSITION_PATTERN.test(transition) ? transition : null
}

/**
 * transition 참조를 서버에 소비시킨다(Story 4.3). 처음 소비이고 관문을 다시 지나면 `show`와 공급자 단위 경로이고, 그 밖의 모든
 * 결말(건너뜀·401·403·404·오류·시간 초과·모양이 아닌 답)은 `skip`이다.
 */
export async function claimAdTransition(
  transition: string,
  csrfToken: string,
): Promise<AdTransitionClaim> {
  const fields = objectOf(await post(AD_TRANSITION_CLAIM_PATH, { transition }, csrfToken))
  if (fields?.outcome !== 'SHOW') {
    return SKIP
  }
  const { adUnitPath } = fields
  return typeof adUnitPath === 'string' && AD_UNIT_PATH_PATTERN.test(adUnitPath)
    ? { kind: 'show', adUnitPath }
    : SKIP
}
