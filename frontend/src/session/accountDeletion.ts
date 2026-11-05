import {
  ACCOUNT_DELETION_CANCEL_PATH,
  ACCOUNT_DELETION_CONFIRM_PATH,
  ACCOUNT_DELETION_PATH,
} from '../content/accountDeletion'
import { objectOf, postJson, postNoContent, readJson } from './http'
import { isInstant } from './instant'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/*
 * 계정 삭제 요청(Story 5.1)의 전송. 요청 init 규칙은 `http.ts` 하나이고 여기서는 시한과 결과 모양만 정한다(`bybitConnection.ts`의
 * 해제 전송과 같은 분담). 허가 참조는 서버 세션에만 있고 브라우저로 오지 않는다. 멱등 키는 헤더로만 나가며 주소·저장소에 남지
 * 않는다.
 */

/** 승인된 법정 분리 보존 항목 하나(G-5 승인 뒤에만 서버가 싣는다 — 지금은 없다). */
export interface LegalRetentionItem {
  readonly field: string
  readonly basis: string
  readonly purpose: string
  readonly period: string
}

/** 서버의 삭제 정책. 화면은 대상 code를 닫힌 맵으로 카피에 잇는다. */
export interface AccountDeletionPolicy {
  readonly version: string
  readonly targets: readonly string[]
  readonly backupDestroyDays: number
  readonly legalRetention: readonly LegalRetentionItem[]
}

/** 이미 만든 삭제 요청. 사용자 식별자가 없다. */
export interface AccountDeletionRequest {
  readonly requestId: string
  readonly requestedAt: string
  readonly state: 'DELETION_IN_PROGRESS'
  readonly policyVersion: string
}

/** 정책·요청 상태 조회의 답. */
export interface AccountDeletionState {
  /** 삭제 요청을 열 수 있는 환경인가(production이면 거짓). */
  readonly available: boolean
  readonly policy: AccountDeletionPolicy
  readonly request: AccountDeletionRequest | null
}

export type AccountDeletionFetch =
  | { readonly outcome: 'loaded'; readonly state: AccountDeletionState }
  | { readonly outcome: 'expired' }
  | { readonly outcome: 'failed' }

export type AccountDeletionPrepare =
  | { readonly outcome: 'armed'; readonly policyVersion: string; readonly confirmation: string }
  | { readonly outcome: 'failed'; readonly code: string | null }

export type AccountDeletionConfirm =
  | { readonly outcome: 'requested'; readonly request: AccountDeletionRequest }
  /** 응답을 받지 못했다. 결과를 단정하지 않고 같은 키로 다시 보낼 수 있다. */
  | { readonly outcome: 'unanswered' }
  | { readonly outcome: 'failed'; readonly code: string | null }

function stringsOf(value: unknown): readonly string[] | null {
  if (!Array.isArray(value)) return null
  const strings = value.filter((item): item is string => typeof item === 'string')
  return strings.length === value.length ? strings : null
}

/** 법정 분리 보존 항목 목록. 네 필드가 모두 비어 있지 않은 문자열인 객체만 받고 하나라도 어긋나면 목록 전체를 믿지 않는다. */
function legalRetentionOf(value: unknown): readonly LegalRetentionItem[] | null {
  if (!Array.isArray(value)) return null
  const items: LegalRetentionItem[] = []
  for (const entry of value) {
    const item = objectOf(entry)
    if (
      item === null ||
      [item.field, item.basis, item.purpose, item.period].some(
        (field) => typeof field !== 'string' || field === '',
      )
    ) {
      return null
    }
    items.push({
      field: item.field as string,
      basis: item.basis as string,
      purpose: item.purpose as string,
      period: item.period as string,
    })
  }
  return items
}

/** 요청 하나. 모르는 모양은 믿지 않는다. */
export function parseDeletionRequest(payload: unknown): AccountDeletionRequest | null {
  const body = objectOf(payload)
  if (body === null) return null
  if (
    typeof body.requestId !== 'string' ||
    body.requestId === '' ||
    !isInstant(body.requestedAt) ||
    body.state !== 'DELETION_IN_PROGRESS' ||
    typeof body.policyVersion !== 'string' ||
    body.policyVersion === ''
  ) {
    return null
  }
  return {
    requestId: body.requestId,
    requestedAt: body.requestedAt,
    state: 'DELETION_IN_PROGRESS',
    policyVersion: body.policyVersion,
  }
}

/** 정책·요청 상태 응답. 모르는 모양은 믿지 않는다. */
export function parseDeletionState(payload: unknown): AccountDeletionState | null {
  const body = objectOf(payload)
  if (body === null || typeof body.available !== 'boolean') return null
  const policy = objectOf(body.policy)
  if (policy === null) return null
  const targets = stringsOf(policy.targets)
  const legalRetention = legalRetentionOf(policy.legalRetention)
  if (
    typeof policy.version !== 'string' ||
    policy.version === '' ||
    targets === null ||
    legalRetention === null ||
    typeof policy.backupDestroyDays !== 'number' ||
    !Number.isInteger(policy.backupDestroyDays) ||
    policy.backupDestroyDays <= 0
  ) {
    return null
  }
  let request: AccountDeletionRequest | null = null
  if (body.request !== null) {
    request = parseDeletionRequest(body.request)
    if (request === null) return null
  }
  return {
    available: body.available,
    policy: {
      version: policy.version,
      targets,
      backupDestroyDays: policy.backupDestroyDays,
      legalRetention,
    },
    request,
  }
}

/** 정책과 요청 상태를 읽는다. 401은 만료이고 그 밖의 실패는 하나다. 자동 재시도하지 않는다. */
export async function fetchDeletionState(): Promise<AccountDeletionFetch> {
  const controller = new AbortController()
  const deadline = setTimeout(() => {
    controller.abort()
  }, SESSION_REQUEST_TIMEOUT_MS)
  try {
    const response = await fetch(ACCOUNT_DELETION_PATH, {
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    })
    if (response.status === 401) {
      return { outcome: 'expired' }
    }
    const payload = await readJson(response)
    const state = response.status === 200 ? parseDeletionState(payload) : null
    return state === null ? { outcome: 'failed' } : { outcome: 'loaded', state }
  } catch {
    return { outcome: 'failed' }
  } finally {
    clearTimeout(deadline)
  }
}

/** 진입점이 백엔드에 닿지 못했다는 상태. 확정이 커밋됐는지 알 수 없다. */
const UPSTREAM_STATUSES: ReadonlySet<number> = new Set([502, 503, 504])

/** 본문이 있는 상태 변경 하나의 결과. `received`는 응답이 왔는가다(시한 초과·단절이면 거짓). */
interface RequestResult {
  readonly ok: boolean
  readonly status: number
  readonly payload: unknown
  readonly code: string | null
  readonly received: boolean
}

async function request(
  path: string,
  csrfToken: string | null,
  body: object | null,
  idempotencyKey?: string,
): Promise<RequestResult> {
  if (csrfToken === null) {
    // 토큰이 없으면 보내지 않는다. 보내도 서버가 403으로 거절한다.
    return { ok: false, status: 0, payload: null, code: null, received: false }
  }
  const controller = new AbortController()
  const deadline = setTimeout(() => {
    controller.abort()
  }, SESSION_REQUEST_TIMEOUT_MS)
  try {
    const response = await postJson(path, {
      csrfToken,
      body: body ?? undefined,
      idempotencyKey,
      signal: controller.signal,
    })
    const payload = await readJson(response)
    const code = objectOf(payload)?.code
    return {
      ok: response.ok,
      status: response.status,
      payload,
      code: typeof code === 'string' ? code : null,
      received: true,
    }
  } catch {
    return { ok: false, status: 0, payload: null, code: null, received: false }
  } finally {
    clearTimeout(deadline)
  }
}

/**
 * 삭제를 준비한다. **아직 아무것도 바꾸지 않는다.** 200과 서버의 확인 문구만 성공이다. `seenPolicyVersion`은 사용자가 이 화면에서 본
 * 정책 버전이고, 서버 정책과 다르면 409 `ACCOUNT_DELETION_POLICY_CHANGED`다.
 */
export async function prepareDeletion(
  csrfToken: string | null,
  seenPolicyVersion: string,
): Promise<AccountDeletionPrepare> {
  const result = await request(ACCOUNT_DELETION_PATH, csrfToken, {
    policyVersion: seenPolicyVersion,
  })
  const body = result.ok ? objectOf(result.payload) : null
  if (
    body !== null &&
    typeof body.policyVersion === 'string' &&
    body.policyVersion !== '' &&
    typeof body.confirmation === 'string' &&
    body.confirmation !== ''
  ) {
    return { outcome: 'armed', policyVersion: body.policyVersion, confirmation: body.confirmation }
  }
  return { outcome: 'failed', code: result.code }
}

/**
 * 삭제를 확정한다. `typed`는 사용자가 입력한 문구 그대로다(서버가 대조한다). 멱등 키는 대화상자를 열 때 한 번 만든 값이다 —
 * 응답을 받지 못한 확정을 다시 보내면 서버가 같은 요청을 돌려준다.
 */
export async function confirmDeletion(
  csrfToken: string | null,
  policyVersion: string,
  typed: string,
  idempotencyKey: string,
): Promise<AccountDeletionConfirm> {
  const result = await request(
    ACCOUNT_DELETION_CONFIRM_PATH,
    csrfToken,
    { policyVersion, confirmation: typed },
    idempotencyKey,
  )
  if (!result.received) {
    return csrfToken === null ? { outcome: 'failed', code: null } : { outcome: 'unanswered' }
  }
  if (result.ok) {
    const parsed = parseDeletionRequest(result.payload)
    // 200인데 모르는 모양이다. 결과를 단정하지 않는다.
    return parsed === null ? { outcome: 'unanswered' } : { outcome: 'requested', request: parsed }
  }
  /*
   * 진입점이 백엔드에 닿지 못한 응답(502·503·504 — Nginx 공통 본문 `UPSTREAM_UNAVAILABLE` 등)과 공통 오류 본문이 없는 5xx(프록시가
   * 끊은 응답)는 커밋 여부를 단정하지 않고 같은 키 재시도로 둔다. `failed`는 백엔드가 직접 답한 거절뿐이다.
   */
  if (UPSTREAM_STATUSES.has(result.status)) {
    return { outcome: 'unanswered' }
  }
  const common = objectOf(result.payload)
  if (
    result.status >= 500 &&
    (common === null || typeof common.code !== 'string' || typeof common.correlationId !== 'string')
  ) {
    return { outcome: 'unanswered' }
  }
  return { outcome: 'failed', code: result.code }
}

/** 준비를 되돌린다. 최선 노력이며 결과를 돌려주지 않는다. */
export async function cancelDeletion(csrfToken: string | null): Promise<void> {
  await postNoContent(ACCOUNT_DELETION_CANCEL_PATH, csrfToken)
}
