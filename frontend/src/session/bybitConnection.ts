import {
  BYBIT_CANCEL_PATH,
  BYBIT_CONNECT_PATH,
  BYBIT_CONNECTION_PATH,
  BYBIT_REVOCATION_CANCEL_PATH,
  BYBIT_REVOCATION_CONFIRM_PATH,
  BYBIT_REVOCATION_PATH,
} from '../content/connection'
import { postJson, postNoContent, readJson } from './http'
import { isInstant } from './instant'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/*
 * 읽기 전용 Bybit 연결.
 *
 * 요청이 넷이다 — 상태 조회(`GET`), 폼을 열 준비(`POST`), 검증과 저장(`POST .../connect`), 그리고
 * 그 준비를 되돌리는 취소(`POST .../cancel`). **준비는 아무것도 바꾸지 않는다.** 그래서 폼을
 * 열었다가 닫아도 연결·자격정보·portfolio가 그대로다. 두 요청을 하나로 합치면 폼을 여는 순간
 * 자격정보가 필요해지므로 그 계약이 성립하지 않는다.
 *
 * **자격정보는 본문으로만 나간다.** 쿼리 문자열에 실으면 그 값이 주소·`Referer`·히스토리·access
 * log에 남는다. 브라우저 저장소에도 두지 않는다 — 이 모듈에는 `localStorage`·`sessionStorage`·
 * 쿠키를 쓰는 코드가 없고, 입력값은 React 상태로만 살다가 성공·실패와 함께 사라진다.
 *
 * 전송의 모양은 `unlinkProvider.ts`·`linkProvider.ts`와 같다 — 세션 쿠키
 * (`credentials: 'same-origin'`), 토큰 헤더, 그리고 브라우저가 싣는 `Origin`. 마지막 것은
 * 스크립트가 만들 수 없고, 그래서 여기에 `Origin`을 다루는 코드가 없는 것이 정상이다.
 *
 * **이동이 없다.** 연결에는 제공자 왕복이 없으므로 문서를 떠나지 않는다. 결과는 화면의 상태
 * 하나로 남고 주소는 그대로다(해제와 같다).
 *
 * 자동 재시도하지 않는다. 시간초과·rate limit·제공자 장애를 포함해 한 번 실패하면 그대로
 * 거절이며, 다시 시작하는 것은 사용자의 조작이다(AC-5·AD-21).
 */

/**
 * 최종 확인 하나의 시한.
 *
 * 다른 요청과 **다른 값**이다. 서버의 최악 예산은 제공자 호출 셋 × 호출당 상한(기본 5초) = 15초라
 * 공유 시한 10초로는 느리지만 성공하는 연결이 abort된다 — 그때 화면은 "연결을 끝내지 못했습니다"를
 * 말하지만 서버는 이미 커밋했고, 사용자의 재시도는 "이미 연결됨"으로 거부되어 본인 확인을 다시
 * 하라는 엉뚱한 안내로 이어진다.
 *
 * 그래서 이쪽을 서버 예산보다 넉넉히 위에 둔다. 서버 상한을 내리는 길도 있었지만 그것은 느린
 * 거래소 응답을 실패로 바꾸는 선택이고, 이 스토리는 시간초과를 "사용자가 직접 다시 시작한다"로
 * 다루기로 했으므로 그 문턱을 우리가 임의로 낮추지 않는다.
 *
 * 준비·취소는 외부 호출이 없으므로 공유 시한을 그대로 쓴다.
 */
const CONNECT_REQUEST_TIMEOUT_MS = 20_000

/**
 * 연결 상태 조회의 결과. 실패를 "연결 없음"으로 접지 않는다.
 *
 * `connectionId`는 불투명 참조다(Story 2.16). 해제 요청의 대상이며 화면에 그리지 않는다. 연결한 적이 없으면
 * `null`이다. `revoked`는 해제된 연결의 사실이고 그때만 `revokedAt`이 있다.
 */
export type ConnectionStatus =
  | {
      readonly status: 'loaded'
      readonly connected: boolean
      readonly verifiedAt: string | null
      readonly policyVersion: string | null
      readonly connectionId: string | null
      readonly revoked: boolean
      readonly revokedAt: string | null
    }
  | { readonly status: 'unavailable' }

/** 해제 준비의 결과. */
export type RevocationPrepareOutcome =
  | { readonly outcome: 'armed'; readonly connectionId: string; readonly confirmation: string }
  | { readonly outcome: 'failed'; readonly code: string | null }

/**
 * 해제 확정의 결과. `unanswered`는 응답을 받지 못했다는 뜻이다 — 서버가 커밋했을 수도 있으므로 화면은 결과를
 * 단정하지 않고 같은 키로 다시 시도하게 한다.
 */
export type RevocationConfirmOutcome =
  | { readonly outcome: 'revoked'; readonly revokedAt: string }
  | { readonly outcome: 'failed'; readonly code: string | null }
  | { readonly outcome: 'unanswered' }

/** 준비의 결과. 화면이 폼을 열지 정하는 데 쓴다. */
export type PrepareOutcome =
  | { readonly outcome: 'armed'; readonly confirmation: string }
  | { readonly outcome: 'failed'; readonly code: string | null }

/** 최종 확인의 결과. */
export type ConnectOutcome =
  | {
      readonly outcome: 'connected'
      readonly verifiedAt: string | null
      readonly policyVersion: string | null
      readonly connectionId: string | null
      /** 같은 Bybit 계정의 재연결로 활성 계획이 동기화를 이어 가는가(Story 2.17). */
      readonly planResumed: boolean
    }
  | { readonly outcome: 'failed'; readonly code: string | null }

/**
 * 지금 연결 상태를 확인한다.
 *
 * 응답에 실리는 것은 연결 사실과 검증 시각·정책 버전뿐이다. API Key·Secret·계정 식별자는 서버가
 * 보내지 않으므로 여기서 읽을 값도 없다.
 *
 * 비정상 응답·시한 초과·네트워크 단절·JSON 아님은 모두 `unavailable` 하나로 수렴한다. 무엇이
 * 일어났는지 추측하지 않으며 자동 재시도도 하지 않는다 — 다시 확인하는 것은 사용자의 조작이다.
 */
export async function fetchConnectionStatus(): Promise<ConnectionStatus> {
  const controller = new AbortController()
  const deadline = setTimeout(() => {
    controller.abort()
  }, SESSION_REQUEST_TIMEOUT_MS)

  try {
    const response = await fetch(BYBIT_CONNECTION_PATH, {
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
    return statusIn(payload)
  } catch {
    return { status: 'unavailable' }
  } finally {
    clearTimeout(deadline)
  }
}

/**
 * 자격정보 입력 폼을 열 준비를 요청한다. **아직 아무것도 연결하지 않는다.**
 *
 * 200과 확인 문구만 성공이다. 그 밖(403·네트워크 단절·시한 초과)은 전부 `failed`이며, 서버가
 * 준 code가 있으면 그대로 들고 온다 — 화면이 자기 카피를 고르는 열쇠이고 문장 자체는 서버에서
 * 가져오지 않는다(`content/connection.ts`).
 */
export async function prepareConnection(csrfToken: string | null): Promise<PrepareOutcome> {
  const result = await request(BYBIT_CONNECTION_PATH, csrfToken, null, SESSION_REQUEST_TIMEOUT_MS)
  if (result.ok && typeof result.payload === 'object' && result.payload !== null) {
    const confirmation: unknown = (result.payload as Record<string, unknown>).confirmation
    if (typeof confirmation === 'string' && confirmation.length > 0) {
      return { outcome: 'armed', confirmation }
    }
  }
  return { outcome: 'failed', code: result.code }
}

/**
 * 검증과 저장을 요청한다. **여기서만 자격정보가 나간다.**
 *
 * 성공하면 서버가 여섯을 한 트랜잭션으로 커밋하고 세션 ID를 회전시킨다. 회전은 `Set-Cookie`
 * 하나로 끝나므로 이 함수가 할 일이 없다 — 브라우저가 새 쿠키를 그대로 쓴다.
 */
export async function connectBybit(
  csrfToken: string | null,
  apiKey: string,
  apiSecret: string,
  confirmation: string,
): Promise<ConnectOutcome> {
  const result = await request(
    BYBIT_CONNECT_PATH,
    csrfToken,
    { apiKey, apiSecret, confirmation },
    CONNECT_REQUEST_TIMEOUT_MS,
  )
  if (result.ok && typeof result.payload === 'object' && result.payload !== null) {
    const body = result.payload as Record<string, unknown>
    if (body.connected === true) {
      return {
        outcome: 'connected',
        verifiedAt: typeof body.verifiedAt === 'string' ? body.verifiedAt : null,
        policyVersion: typeof body.policyVersion === 'string' ? body.policyVersion : null,
        connectionId: typeof body.connectionId === 'string' ? body.connectionId : null,
        // 같은 Bybit 계정으로 다시 연결해 기존 계획을 이어 동기화한다(Story 2.17). 서버가 그때만 싣는다.
        planResumed: body.planResumed === true,
      }
    }
  }
  return { outcome: 'failed', code: result.code }
}

/**
 * 열어 둔 준비를 되돌린다. **최선 노력이며 결과를 돌려주지 않는다.**
 *
 * 준비가 서버 상태를 만들므로(무장과 허가) 취소에도 되돌리는 요청이 있어야 한다 — 없으면 화면의
 * "바뀐 것은 없습니다"가 거짓이 되고 아무도 참조하지 않는 허가가 5분을 산다.
 *
 * 전송은 `http.ts`의 `postNoContent`를 그대로 쓴다. 모양이 정확히 같고(`POST`, 토큰 헤더, 204만
 * 성공, 재시도 없음) 복제하면 시한·실패 접기 규칙이 또 하나의 사본으로 갈라진다.
 */
export async function cancelConnection(csrfToken: string | null): Promise<void> {
  await postNoContent(BYBIT_CANCEL_PATH, csrfToken)
}

/**
 * 연결 해제를 준비한다(Story 2.16). **아직 아무것도 지우지 않는다.** 200과 서버의 정본 확인 문구만 성공이다.
 */
export async function prepareRevocation(
  csrfToken: string | null,
  connectionId: string,
): Promise<RevocationPrepareOutcome> {
  const result = await request(
    BYBIT_REVOCATION_PATH,
    csrfToken,
    { connectionId },
    SESSION_REQUEST_TIMEOUT_MS,
  )
  if (result.ok && typeof result.payload === 'object' && result.payload !== null) {
    const body = result.payload as Record<string, unknown>
    if (
      body.connectionId === connectionId &&
      typeof body.confirmation === 'string' &&
      body.confirmation.length > 0
    ) {
      return { outcome: 'armed', connectionId, confirmation: body.confirmation }
    }
  }
  return { outcome: 'failed', code: result.code }
}

/**
 * 연결 해제를 확정한다. 멱등 키는 대화상자를 열 때 한 번 만든 값이다 — 응답을 받지 못한 확정을 다시 보내면
 * 서버가 같은 결과를 재생한다. 키는 헤더로만 나가며 주소·저장소에 남지 않는다.
 */
export async function confirmRevocation(
  csrfToken: string | null,
  connectionId: string,
  confirmation: string,
  idempotencyKey: string,
): Promise<RevocationConfirmOutcome> {
  const result = await request(
    BYBIT_REVOCATION_CONFIRM_PATH,
    csrfToken,
    { connectionId, confirmation },
    SESSION_REQUEST_TIMEOUT_MS,
    idempotencyKey,
  )
  if (!result.received) {
    return csrfToken === null ? { outcome: 'failed', code: null } : { outcome: 'unanswered' }
  }
  if (result.ok && typeof result.payload === 'object' && result.payload !== null) {
    const body = result.payload as Record<string, unknown>
    if (
      body.outcome === 'REVOKED' &&
      body.connectionId === connectionId &&
      isInstant(body.revokedAt)
    ) {
      return { outcome: 'revoked', revokedAt: body.revokedAt }
    }
    // 200인데 모르는 모양이다. 결과를 단정하지 않는다.
    return { outcome: 'unanswered' }
  }
  /*
   * Nginx는 백엔드 응답을 기다리다 504가 나면 공통 오류 본문을 보낸다. 이미 해제가 커밋됐을 수 있으므로
   * 504와 공통 오류 본문이 없는 5xx는 결과 미확인으로 두고 같은 키로 재시도할 수 있게 한다.
   */
  if (result.status === 504 || (result.status >= 500 && !commonError(result.payload))) {
    return { outcome: 'unanswered' }
  }
  return { outcome: 'failed', code: result.code }
}

/** 해제 준비를 되돌린다. 최선 노력이며 결과를 돌려주지 않는다(`cancelConnection`과 같다). */
export async function cancelRevocation(csrfToken: string | null): Promise<void> {
  await postNoContent(BYBIT_REVOCATION_CANCEL_PATH, csrfToken)
}

/** 본문이 있는 상태 변경 하나의 결과. `received`는 응답이 왔는가다(시한 초과·단절이면 거짓). */
interface RequestResult {
  readonly ok: boolean
  readonly payload: unknown
  readonly code: string | null
  readonly received: boolean
  readonly status: number
}

/**
 * 준비·확인이 공유하는 전송. 요청 모양은 `http.ts`의 `postJson`이 정하고, 여기서는 시한과 결과 모양만 정한다.
 *
 * 본문 없는 204 묶음(`postNoContent`)을 쓰지 않는다. 그쪽은 204만 보며, 여기서는 응답 본문을 읽어야
 * 한다 — 같은 함수로 접으면 그 함수가 두 계약을 겸하게 된다. 취소는 모양이 정확히 같으므로 그쪽을 쓴다.
 */
async function request(
  path: string,
  csrfToken: string | null,
  body: object | null,
  timeoutMs: number,
  idempotencyKey?: string,
): Promise<RequestResult> {
  if (csrfToken === null) {
    /*
     * 토큰이 없으면 보내지 않는다. 보내도 서버가 403으로 거절하므로 결과는 같지만, 그 요청은
     * 실패가 예정된 상태 변경 시도 하나를 로그에 남긴다(`linkProvider.ts`와 같은 근거).
     * 자격정보가 실린 요청이면 더욱 그렇다.
     */
    return { ok: false, payload: null, code: null, received: false, status: 0 }
  }

  // 시한은 호출자가 정한다. 준비·취소는 공유 값이고 최종 확인만 서버 예산을 덮는 더 큰 값이다.
  const controller = new AbortController()
  const deadline = setTimeout(() => {
    controller.abort()
  }, timeoutMs)

  try {
    const response = await postJson(path, {
      csrfToken,
      body: body ?? undefined,
      idempotencyKey,
      signal: controller.signal,
    })
    const payload: unknown = await readJson(response)
    return {
      ok: response.ok,
      payload,
      code: codeIn(payload),
      received: true,
      status: response.status,
    }
  } catch {
    // 네트워크 단절·응답 유실·시한 초과도 같은 결과다. 무엇이 일어났는지 추측하지 않는다.
    return { ok: false, payload: null, code: null, received: false, status: 0 }
  } finally {
    clearTimeout(deadline)
  }
}

/** 공통 오류 계약의 모양인가 — 서버가 직접 답한 오류다. */
function commonError(payload: unknown): boolean {
  if (typeof payload !== 'object' || payload === null) {
    return false
  }
  const body = payload as Record<string, unknown>
  return typeof body.code === 'string' && typeof body.correlationId === 'string'
}

/**
 * 공통 오류 계약의 안정 machine code. 문장은 가져오지 않는다 — 카피는 `content/`가 소유한다.
 *
 * 401의 `UNAUTHENTICATED`도 그대로 나른다. 사용자가 Bybit 콘솔에 다녀오는 동안 유휴 30분이 지나는
 * 것은 흔한 경로이고, 그 응답을 "잠시 후 다시 시도"로 접으면 로그인으로 돌아갈 길이 사라진다.
 */
function codeIn(payload: unknown): string | null {
  if (typeof payload !== 'object' || payload === null) {
    return null
  }
  const code: unknown = (payload as Record<string, unknown>).code
  return typeof code === 'string' ? code : null
}

/** 상태 응답에서 읽는 값. 모르는 모양은 "확인하지 못했다"로 접는다. */
function statusIn(payload: unknown): ConnectionStatus {
  if (typeof payload !== 'object' || payload === null || !('connected' in payload)) {
    // 이 응답은 연결 상태를 답한 것이 아니다. 연결이 없다고 주장하지 않는다.
    return { status: 'unavailable' }
  }
  const body = payload as Record<string, unknown>
  if (typeof body.connected !== 'boolean') {
    return { status: 'unavailable' }
  }
  const revoked = body.revoked === true
  const revokedAt = typeof body.revokedAt === 'string' ? body.revokedAt : null
  if (revoked && (body.connected || revokedAt === null)) {
    // 해제됐다면서 연결됐다고 하거나 해제 시각이 없다. 어느 쪽도 주장하지 않는다.
    return { status: 'unavailable' }
  }
  return {
    status: 'loaded',
    connected: body.connected,
    verifiedAt: typeof body.verifiedAt === 'string' ? body.verifiedAt : null,
    policyVersion: typeof body.policyVersion === 'string' ? body.policyVersion : null,
    connectionId: typeof body.connectionId === 'string' ? body.connectionId : null,
    revoked,
    revokedAt: revoked ? revokedAt : null,
  }
}
