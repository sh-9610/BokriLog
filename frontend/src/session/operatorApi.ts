import { CSRF_HEADER } from '../content/auth'
import {
  AUDIT_LEDGER_SCOPE,
  LIFT_RESTRICTION_COMMAND,
  OPERATOR_AUDIT_ENDPOINT,
  OPERATOR_COMMANDS_ENDPOINT,
  OPERATOR_EXPORT_CANCEL_ENDPOINT,
  OPERATOR_EXPORT_CONFIRM_ENDPOINT,
  OPERATOR_EXPORT_ENDPOINT,
  OPERATOR_OPERATIONS_ENDPOINT,
  OPERATOR_RESTRICTIONS_ENDPOINT,
  type RecoveryCondition,
  type RestrictionCommand,
} from '../content/operator'
import { isInstant } from './instant'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/**
 * 보호된 운영자 표면의 전송(Story 3.1). 이 모듈은 운영자 화면 chunk만 import한다.
 *
 * 응답 값은 메모리에만 둔다 — 브라우저 저장소·URL에 쓰지 않는다. 멱등 키는 헤더로만 나간다. 수행자를 본문에 싣지
 * 않는다(서버가 세션으로 정하고, 실으면 거절한다).
 */

/** 확정의 시한. 추출은 최대 10,000행을 읽고 직렬화하므로 다른 요청보다 길다. */
const CONFIRM_TIMEOUT_MS = 30_000

/** 감사 행 하나의 allowlist 필드. */
export interface AuditEntry {
  readonly sequence: number
  readonly operationId: string
  readonly actorKind: string
  readonly actorDigest: string
  readonly command: string
  readonly scope: string
  readonly target: string
  readonly reason: string
  readonly outcome: string
  readonly code: string | null
  readonly policyVersion: string
  readonly requestedAt: string
  readonly decidedAt: string | null
  readonly recordedAt: string
  readonly correlationId: string
}

/** 읽기 결과. 404는 운영자가 아니거나 권한이 없다는 뜻이고 그 이유를 가르지 않는다. */
export type ReadResult<T> =
  | { readonly status: 'loaded'; readonly value: T }
  | { readonly status: 'notFound' }
  | { readonly status: 'unauthenticated' }
  | { readonly status: 'failed'; readonly code: string | null }

export interface AuditPage {
  readonly items: readonly AuditEntry[]
  readonly nextCursor: string | null
}

export interface AuditQuery {
  readonly from: string
  readonly to: string
  readonly outcome: string
  readonly cursor: string | null
}

export interface PreparedExport {
  readonly operationId: string
  readonly scope: string
  readonly target: string
  readonly reason: string
  readonly confirmationPhrase: string
  readonly policyVersion: string
}

export interface CompletedExport {
  readonly operationId: string
  readonly target: string
  readonly rowCount: number
  readonly truncated: boolean
  readonly rows: readonly AuditEntry[]
}

export type PrepareOutcome =
  | { readonly outcome: 'prepared'; readonly prepared: PreparedExport }
  | { readonly outcome: 'refused'; readonly status: number; readonly code: string | null }

export type ConfirmOutcome =
  | { readonly outcome: 'completed'; readonly completed: CompletedExport }
  | { readonly outcome: 'refused'; readonly status: number; readonly code: string | null }
  /** 응답을 받지 못했거나 서버가 커밋 여부를 말하지 못했다. 자동으로 다시 실행하지 않는다. */
  | { readonly outcome: 'unanswered' }

/** 감사 조회 쿼리. 빈 값은 싣지 않는다. 식별자를 URL에 싣지 않는다 — 날짜·결과·불투명 cursor뿐이다. */
export function auditQueryString(query: AuditQuery): string {
  const params = new URLSearchParams()
  if (query.from !== '') params.set('from', query.from)
  if (query.to !== '') params.set('to', query.to)
  if (query.outcome !== '') params.set('outcome', query.outcome)
  if (query.cursor !== null) params.set('cursor', query.cursor)
  const text = params.toString()
  return text === '' ? '' : `?${text}`
}

export async function fetchAuditPage(
  query: AuditQuery,
  signal: AbortSignal,
): Promise<ReadResult<AuditPage>> {
  const result = await read(`${OPERATOR_AUDIT_ENDPOINT}${auditQueryString(query)}`, signal)
  if (result.status !== 'loaded') {
    return result
  }
  const body = result.value
  const items = entriesIn(body.items)
  const cursor = body.nextCursor
  if (items === null || (cursor !== null && cursor !== undefined && typeof cursor !== 'string')) {
    return { status: 'failed', code: null }
  }
  return {
    status: 'loaded',
    value: { items, nextCursor: typeof cursor === 'string' && cursor !== '' ? cursor : null },
  }
}

export async function fetchOperation(
  operationId: string,
  signal: AbortSignal,
): Promise<ReadResult<readonly AuditEntry[]>> {
  const result = await read(
    `${OPERATOR_OPERATIONS_ENDPOINT}/${encodeURIComponent(operationId)}`,
    signal,
  )
  if (result.status !== 'loaded') {
    return result
  }
  const items = entriesIn(result.value.items)
  return items === null ? { status: 'failed', code: null } : { status: 'loaded', value: items }
}

/** 내보내기 준비. 아직 아무것도 실행하지 않는다 — 허가를 발급하고 무장할 뿐이다. */
export async function prepareExport(
  csrfToken: string | null,
  target: string,
  reason: string,
  idempotencyKey: string,
): Promise<PrepareOutcome> {
  const result = await post(
    OPERATOR_EXPORT_ENDPOINT,
    csrfToken,
    { scope: AUDIT_LEDGER_SCOPE, target, reason },
    idempotencyKey,
    SESSION_REQUEST_TIMEOUT_MS,
  )
  if (result.ok) {
    const body = objectOf(result.payload)
    if (
      body !== null &&
      typeof body.operationId === 'string' &&
      typeof body.scope === 'string' &&
      typeof body.target === 'string' &&
      typeof body.reason === 'string' &&
      typeof body.confirmationPhrase === 'string' &&
      body.confirmationPhrase !== '' &&
      typeof body.policyVersion === 'string'
    ) {
      return {
        outcome: 'prepared',
        prepared: {
          operationId: body.operationId,
          scope: body.scope,
          target: body.target,
          reason: body.reason,
          confirmationPhrase: body.confirmationPhrase,
          policyVersion: body.policyVersion,
        },
      }
    }
  }
  return { outcome: 'refused', status: result.status, code: result.code }
}

/** 내보내기 확정. 같은 키의 재전송은 서버가 같은 묶음을 재생한다. */
export async function confirmExport(
  csrfToken: string | null,
  operationId: string,
  confirmation: string,
  idempotencyKey: string,
): Promise<ConfirmOutcome> {
  const result = await post(
    OPERATOR_EXPORT_CONFIRM_ENDPOINT,
    csrfToken,
    { operationId, confirmation },
    idempotencyKey,
    CONFIRM_TIMEOUT_MS,
  )
  if (!result.received) {
    return csrfToken === null
      ? { outcome: 'refused', status: 0, code: null }
      : { outcome: 'unanswered' }
  }
  if (result.ok) {
    const body = objectOf(result.payload)
    const rows = body === null ? null : entriesIn(body.rows)
    if (
      body !== null &&
      rows !== null &&
      body.outcome === 'COMPLETED' &&
      body.operationId === operationId &&
      typeof body.target === 'string' &&
      typeof body.rowCount === 'number' &&
      typeof body.truncated === 'boolean'
    ) {
      return {
        outcome: 'completed',
        completed: {
          operationId,
          target: body.target,
          rowCount: body.rowCount,
          truncated: body.truncated,
          rows,
        },
      }
    }
    return { outcome: 'unanswered' }
  }
  /*
   * 5xx는 모두 커밋 여부를 단정하지 않는다. 서버가 공통 본문으로 답한 500도 실행이 커밋된 **뒤**의 실패(행 재조회·
   * 세션 회전)일 수 있다 — "바뀐 것은 없습니다"로 접으면 실행된 추출을 실패로 말하게 된다. 같은 키로 다시 확정하면
   * 원장이 같은 결과를 재생한다.
   */
  if (result.status >= 500) {
    return { outcome: 'unanswered' }
  }
  return { outcome: 'refused', status: result.status, code: result.code }
}

/** 준비를 되돌린다. 최선 노력이며 결과를 돌려주지 않는다. */
export async function cancelExport(csrfToken: string | null): Promise<void> {
  await post(OPERATOR_EXPORT_CANCEL_ENDPOINT, csrfToken, null, null, SESSION_REQUEST_TIMEOUT_MS)
}

// ------------------------------------------------------------------ 제한(Story 3.2)

/** 제한 생성의 영향 미리보기. 수만 담는다 — 식별자도 금융값도 없다. */
export interface RestrictionImpact {
  readonly blockedStages: readonly string[]
  readonly affectedPlans: number
  readonly affectedConnections: number
  readonly remainingFeatures: readonly string[]
}

export interface PreparedRestriction {
  readonly operationId: string
  readonly command: RestrictionCommand
  /** 원장의 범위(`<기능>:<scope>`). */
  readonly scope: string
  readonly target: string
  readonly reason: string
  readonly confirmationPhrase: string
  readonly impact: RestrictionImpact
}

export interface CompletedRestriction {
  readonly operationId: string
  readonly restrictionId: string
  readonly feature: string
  readonly scope: string
  readonly effectiveFrom: string
}

/** 효력 중인 제한 하나. 계정 삭제로 비식별된 target은 오지 않고 `targetErased`가 참이다. */
export interface ActiveRestriction {
  readonly restrictionId: string
  readonly feature: string
  readonly scope: string
  readonly target: string | null
  readonly targetErased: boolean
  readonly effectiveFrom: string
  /** 운영자 command의 사유, 또는 자동 정지의 신호 종류 code. */
  readonly reason: string | null
  readonly actorDigest: string | null
  /**
   * 제한을 만든 출처(Story 3.3): `OPERATOR`·`SIGNAL`. 모르는 값은 그대로 두고 화면이 안전 문구로 접는다. 모양이 아니면
   * 빈 문자열이다.
   */
  readonly origin: string
  /**
   * 수동 검증이 필요한가 — 신호가 만들었거나 신호가 하나라도 연결된 제한. 서버가 말하지 않았거나 출처가 운영자가
   * 아니면 참으로 접는다(안전한 쪽).
   */
  readonly requiresVerification: boolean
  /** 해제 smoke가 어긋나 같은 범위로 되돌린 행(Story 3.4). 서버가 말하지 않으면 거짓이다. */
  readonly reinstated: boolean
}

export type RestrictionPrepareOutcome =
  | { readonly outcome: 'prepared'; readonly prepared: PreparedRestriction }
  | { readonly outcome: 'refused'; readonly status: number; readonly code: string | null }

export type RestrictionConfirmOutcome =
  | { readonly outcome: 'completed'; readonly completed: CompletedRestriction }
  | { readonly outcome: 'refused'; readonly status: number; readonly code: string | null }
  /** 응답을 받지 못했거나 서버가 커밋 여부를 말하지 못했다. 자동으로 다시 실행하지 않는다. */
  | { readonly outcome: 'unanswered' }

export async function fetchRestrictions(
  signal: AbortSignal,
): Promise<ReadResult<readonly ActiveRestriction[]>> {
  const result = await read(OPERATOR_RESTRICTIONS_ENDPOINT, signal)
  if (result.status !== 'loaded') {
    return result
  }
  const items = result.value.items
  if (!Array.isArray(items)) {
    return { status: 'failed', code: null }
  }
  const restrictions: ActiveRestriction[] = []
  for (const item of items) {
    const restriction = restrictionOf(item)
    if (restriction === null) {
      return { status: 'failed', code: null }
    }
    restrictions.push(restriction)
  }
  return { status: 'loaded', value: restrictions }
}

/** 제한 준비. 아직 아무것도 막지 않는다 — 허가를 발급하고 무장한 뒤 영향 미리보기와 확인 문구를 준다. */
export async function prepareRestriction(
  csrfToken: string | null,
  command: RestrictionCommand,
  scope: string,
  target: string,
  reason: string,
  idempotencyKey: string,
): Promise<RestrictionPrepareOutcome> {
  const result = await post(
    `${OPERATOR_COMMANDS_ENDPOINT}/${command}`,
    csrfToken,
    { scope, target, reason },
    idempotencyKey,
    SESSION_REQUEST_TIMEOUT_MS,
  )
  if (result.ok) {
    const body = objectOf(result.payload)
    const impact = body === null ? null : impactOf(body.impact)
    if (
      body !== null &&
      impact !== null &&
      body.command === command &&
      typeof body.operationId === 'string' &&
      typeof body.scope === 'string' &&
      typeof body.target === 'string' &&
      typeof body.reason === 'string' &&
      typeof body.confirmationPhrase === 'string' &&
      body.confirmationPhrase !== ''
    ) {
      return {
        outcome: 'prepared',
        prepared: {
          operationId: body.operationId,
          command,
          scope: body.scope,
          target: body.target,
          reason: body.reason,
          confirmationPhrase: body.confirmationPhrase,
          impact,
        },
      }
    }
  }
  return { outcome: 'refused', status: result.status, code: result.code }
}

/** 제한 확정. 같은 키의 재전송은 서버가 같은 결과를 재생한다. */
export async function confirmRestriction(
  csrfToken: string | null,
  command: RestrictionCommand,
  operationId: string,
  confirmation: string,
  idempotencyKey: string,
): Promise<RestrictionConfirmOutcome> {
  const result = await post(
    `${OPERATOR_COMMANDS_ENDPOINT}/${command}/confirm`,
    csrfToken,
    { operationId, confirmation },
    idempotencyKey,
    SESSION_REQUEST_TIMEOUT_MS,
  )
  if (!result.received) {
    return csrfToken === null
      ? { outcome: 'refused', status: 0, code: null }
      : { outcome: 'unanswered' }
  }
  if (result.ok) {
    const body = objectOf(result.payload)
    if (
      body !== null &&
      body.outcome === 'COMPLETED' &&
      body.operationId === operationId &&
      typeof body.restrictionId === 'string' &&
      typeof body.feature === 'string' &&
      typeof body.scope === 'string' &&
      isInstant(body.effectiveFrom)
    ) {
      return {
        outcome: 'completed',
        completed: {
          operationId,
          restrictionId: body.restrictionId,
          feature: body.feature,
          scope: body.scope,
          effectiveFrom: body.effectiveFrom,
        },
      }
    }
    return { outcome: 'unanswered' }
  }
  // 5xx는 커밋 여부를 단정하지 않는다(내보내기와 같은 근거).
  if (result.status >= 500) {
    return { outcome: 'unanswered' }
  }
  return { outcome: 'refused', status: result.status, code: result.code }
}

/** 제한 준비를 되돌린다. 최선 노력이며 결과를 돌려주지 않는다. */
export async function cancelRestriction(
  csrfToken: string | null,
  command: RestrictionCommand,
): Promise<void> {
  await post(
    `${OPERATOR_COMMANDS_ENDPOINT}/${command}/cancel`,
    csrfToken,
    null,
    null,
    SESSION_REQUEST_TIMEOUT_MS,
  )
}

// ------------------------------------------------------------------ 복구(Story 3.4)

/** 복구 뒤에도 그 대상을 막는 다른 활성 제한 하나. 식별자가 없다. */
export interface RemainingRestriction {
  readonly feature: string
  readonly scope: string
  readonly origin: string
}

/** 복구의 미리보기. 식별자가 없다. */
export interface RecoveryImpact {
  readonly reopenedStages: readonly string[]
  readonly remainingRestrictions: readonly RemainingRestriction[]
  readonly linkedSignals: number
  /** 연결된 신호의 최신 감지 시각. 신호가 없으면 `null`이다. */
  readonly latestSignalAt: string | null
}

export interface PreparedRecovery {
  readonly operationId: string
  /** 원장의 범위(`<기능>:<scope>`). */
  readonly scope: string
  /** 제한 ID. */
  readonly target: string
  readonly reason: string
  readonly confirmationPhrase: string
  readonly impact: RecoveryImpact
}

export interface RecoverySmoke {
  /**
   * `PASSED`·`REVERTED`·`PENDING`(재전송에서 원장에 결말이 아직 없다). 모르는 값은 그대로 두고 화면이 결말을 단정하지
   * 않는 문구로 접는다.
   */
  readonly status: string
  readonly reopenedStages: readonly string[]
  readonly stillBlockedStages: readonly string[]
}

export interface CompletedRecovery {
  readonly operationId: string
  readonly restrictionId: string
  readonly feature: string
  readonly scope: string
  readonly liftedAt: string
  readonly smoke: RecoverySmoke
}

/** 복구 준비의 입력. 원인은 사유 자리다. */
export interface RecoveryInput {
  readonly restrictionId: string
  readonly reason: string
  readonly verification: string
  readonly evidenceRef: string
  readonly deployVersion: string
  readonly conditions: readonly RecoveryCondition[]
}

export type RecoveryPrepareOutcome =
  | { readonly outcome: 'prepared'; readonly prepared: PreparedRecovery }
  | { readonly outcome: 'refused'; readonly status: number; readonly code: string | null }

export type RecoveryConfirmOutcome =
  | { readonly outcome: 'completed'; readonly completed: CompletedRecovery }
  | { readonly outcome: 'refused'; readonly status: number; readonly code: string | null }
  /** 응답을 받지 못했거나 서버가 커밋 여부를 말하지 못했다. 자동으로 다시 실행하지 않는다. */
  | { readonly outcome: 'unanswered' }

const RECOVERY_ENDPOINT = `${OPERATOR_COMMANDS_ENDPOINT}/${LIFT_RESTRICTION_COMMAND}`

/** 복구 준비. 아직 아무것도 열지 않는다 — 허가를 발급하고 무장한 뒤 미리보기와 확인 문구를 준다. */
export async function prepareRecovery(
  csrfToken: string | null,
  input: RecoveryInput,
  idempotencyKey: string,
): Promise<RecoveryPrepareOutcome> {
  const result = await post(
    RECOVERY_ENDPOINT,
    csrfToken,
    {
      target: input.restrictionId,
      reason: input.reason,
      verification: input.verification,
      evidenceRef: input.evidenceRef,
      deployVersion: input.deployVersion,
      conditions: input.conditions,
    },
    idempotencyKey,
    SESSION_REQUEST_TIMEOUT_MS,
  )
  if (result.ok) {
    const body = objectOf(result.payload)
    const impact = body === null ? null : recoveryImpactOf(body.impact)
    if (
      body !== null &&
      impact !== null &&
      body.command === LIFT_RESTRICTION_COMMAND &&
      typeof body.operationId === 'string' &&
      typeof body.scope === 'string' &&
      body.target === input.restrictionId &&
      typeof body.reason === 'string' &&
      typeof body.confirmationPhrase === 'string' &&
      body.confirmationPhrase !== ''
    ) {
      return {
        outcome: 'prepared',
        prepared: {
          operationId: body.operationId,
          scope: body.scope,
          target: body.target,
          reason: body.reason,
          confirmationPhrase: body.confirmationPhrase,
          impact,
        },
      }
    }
  }
  return { outcome: 'refused', status: result.status, code: result.code }
}

/** 복구 확정. 같은 키의 재전송은 서버가 같은 결과와 smoke 결말을 재생한다. */
export async function confirmRecovery(
  csrfToken: string | null,
  operationId: string,
  confirmation: string,
  idempotencyKey: string,
): Promise<RecoveryConfirmOutcome> {
  const result = await post(
    `${RECOVERY_ENDPOINT}/confirm`,
    csrfToken,
    { operationId, confirmation },
    idempotencyKey,
    SESSION_REQUEST_TIMEOUT_MS,
  )
  if (!result.received) {
    return csrfToken === null
      ? { outcome: 'refused', status: 0, code: null }
      : { outcome: 'unanswered' }
  }
  if (result.ok) {
    const body = objectOf(result.payload)
    const smoke = body === null ? null : smokeOf(body.smoke)
    if (
      body !== null &&
      smoke !== null &&
      body.outcome === 'COMPLETED' &&
      body.operationId === operationId &&
      typeof body.restrictionId === 'string' &&
      typeof body.feature === 'string' &&
      typeof body.scope === 'string' &&
      isInstant(body.liftedAt)
    ) {
      return {
        outcome: 'completed',
        completed: {
          operationId,
          restrictionId: body.restrictionId,
          feature: body.feature,
          scope: body.scope,
          liftedAt: body.liftedAt,
          smoke,
        },
      }
    }
    return { outcome: 'unanswered' }
  }
  // 5xx는 커밋 여부를 단정하지 않는다(내보내기와 같은 근거).
  if (result.status >= 500) {
    return { outcome: 'unanswered' }
  }
  return { outcome: 'refused', status: result.status, code: result.code }
}

/** 복구 준비를 되돌린다. 최선 노력이며 결과를 돌려주지 않는다. */
export async function cancelRecovery(csrfToken: string | null): Promise<void> {
  await post(`${RECOVERY_ENDPOINT}/cancel`, csrfToken, null, null, SESSION_REQUEST_TIMEOUT_MS)
}

function recoveryImpactOf(value: unknown): RecoveryImpact | null {
  const body = objectOf(value)
  if (
    body === null ||
    !stringArray(body.reopenedStages) ||
    !Array.isArray(body.remainingRestrictions) ||
    typeof body.linkedSignals !== 'number'
  ) {
    return null
  }
  const latest = body.latestSignalAt
  if (latest !== null && latest !== undefined && !isInstant(latest)) {
    return null
  }
  const remaining: RemainingRestriction[] = []
  for (const item of body.remainingRestrictions as unknown[]) {
    const other = objectOf(item)
    if (
      other === null ||
      typeof other.feature !== 'string' ||
      typeof other.scope !== 'string' ||
      typeof other.origin !== 'string'
    ) {
      return null
    }
    remaining.push({ feature: other.feature, scope: other.scope, origin: other.origin })
  }
  return {
    reopenedStages: body.reopenedStages,
    remainingRestrictions: remaining,
    linkedSignals: body.linkedSignals,
    latestSignalAt: typeof latest === 'string' ? latest : null,
  }
}

function smokeOf(value: unknown): RecoverySmoke | null {
  const body = objectOf(value)
  if (
    body === null ||
    typeof body.status !== 'string' ||
    !stringArray(body.reopenedStages) ||
    !stringArray(body.stillBlockedStages)
  ) {
    return null
  }
  return {
    status: body.status,
    reopenedStages: body.reopenedStages,
    stillBlockedStages: body.stillBlockedStages,
  }
}

function impactOf(value: unknown): RestrictionImpact | null {
  const body = objectOf(value)
  if (
    body === null ||
    !stringArray(body.blockedStages) ||
    !stringArray(body.remainingFeatures) ||
    typeof body.affectedPlans !== 'number' ||
    typeof body.affectedConnections !== 'number'
  ) {
    return null
  }
  return {
    blockedStages: body.blockedStages,
    affectedPlans: body.affectedPlans,
    affectedConnections: body.affectedConnections,
    remainingFeatures: body.remainingFeatures,
  }
}

function restrictionOf(value: unknown): ActiveRestriction | null {
  const body = objectOf(value)
  if (
    body === null ||
    typeof body.restrictionId !== 'string' ||
    typeof body.feature !== 'string' ||
    typeof body.scope !== 'string' ||
    typeof body.targetErased !== 'boolean' ||
    !isInstant(body.effectiveFrom)
  ) {
    return null
  }
  const target = typeof body.target === 'string' ? body.target : null
  if (target === null && !body.targetErased) {
    return null
  }
  const origin = typeof body.origin === 'string' ? body.origin : ''
  return {
    restrictionId: body.restrictionId,
    feature: body.feature,
    scope: body.scope,
    target: body.targetErased ? null : target,
    targetErased: body.targetErased,
    effectiveFrom: body.effectiveFrom,
    reason: typeof body.reason === 'string' ? body.reason : null,
    actorDigest: typeof body.actorDigest === 'string' ? body.actorDigest : null,
    origin,
    requiresVerification: origin !== 'OPERATOR' || body.requiresVerification !== false,
    reinstated: body.reinstated === true,
  }
}

function stringArray(value: unknown): value is readonly string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

// ------------------------------------------------------------------ 전송

type Body = Readonly<Record<string, unknown>>

async function read(path: string, signal: AbortSignal): Promise<ReadResult<Body>> {
  const controller = new AbortController()
  const abort = (): void => {
    controller.abort()
  }
  signal.addEventListener('abort', abort)
  const deadline = setTimeout(abort, SESSION_REQUEST_TIMEOUT_MS)
  try {
    const response = await fetch(path, {
      method: 'GET',
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    })
    const payload = await readJson(response)
    if (response.status === 404) {
      return { status: 'notFound' }
    }
    if (response.status === 401) {
      return { status: 'unauthenticated' }
    }
    const body = objectOf(payload)
    if (!response.ok || body === null) {
      return { status: 'failed', code: codeIn(payload) }
    }
    return { status: 'loaded', value: body }
  } catch {
    return { status: 'failed', code: null }
  } finally {
    clearTimeout(deadline)
    signal.removeEventListener('abort', abort)
  }
}

interface PostResult {
  readonly ok: boolean
  readonly payload: unknown
  readonly code: string | null
  readonly received: boolean
  readonly status: number
}

async function post(
  path: string,
  csrfToken: string | null,
  body: object | null,
  idempotencyKey: string | null,
  timeoutMs: number,
): Promise<PostResult> {
  if (csrfToken === null) {
    // 토큰이 없으면 보내지 않는다. 보내도 서버가 403으로 거절한다.
    return { ok: false, payload: null, code: null, received: false, status: 0 }
  }
  const controller = new AbortController()
  const deadline = setTimeout(() => {
    controller.abort()
  }, timeoutMs)
  try {
    const headers: Record<string, string> = { [CSRF_HEADER]: csrfToken, Accept: 'application/json' }
    if (idempotencyKey !== null) {
      headers['Idempotency-Key'] = idempotencyKey
    }
    if (body !== null) {
      headers['Content-Type'] = 'application/json'
    }
    const response = await fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      headers,
      body: body === null ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
    const payload = await readJson(response)
    return {
      ok: response.ok,
      payload,
      code: codeIn(payload),
      received: true,
      status: response.status,
    }
  } catch {
    return { ok: false, payload: null, code: null, received: false, status: 0 }
  } finally {
    clearTimeout(deadline)
  }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

function objectOf(value: unknown): Body | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Body)
    : null
}

function codeIn(payload: unknown): string | null {
  const body = objectOf(payload)
  return body !== null && typeof body.code === 'string' ? body.code : null
}

/** 행 배열. 하나라도 모양이 어긋나면 묶음 전체를 믿지 않는다({@code null}). */
function entriesIn(value: unknown): readonly AuditEntry[] | null {
  if (!Array.isArray(value)) {
    return null
  }
  const entries: AuditEntry[] = []
  for (const item of value) {
    const entry = entryOf(item)
    if (entry === null) {
      return null
    }
    entries.push(entry)
  }
  return entries
}

function entryOf(value: unknown): AuditEntry | null {
  const body = objectOf(value)
  if (
    body === null ||
    typeof body.sequence !== 'number' ||
    typeof body.operationId !== 'string' ||
    typeof body.actorKind !== 'string' ||
    typeof body.actorDigest !== 'string' ||
    typeof body.command !== 'string' ||
    typeof body.scope !== 'string' ||
    typeof body.target !== 'string' ||
    typeof body.reason !== 'string' ||
    typeof body.outcome !== 'string' ||
    typeof body.policyVersion !== 'string' ||
    !isInstant(body.requestedAt) ||
    !isInstant(body.recordedAt) ||
    typeof body.correlationId !== 'string'
  ) {
    return null
  }
  const decidedAt = body.decidedAt
  if (decidedAt !== undefined && decidedAt !== null && !isInstant(decidedAt)) {
    return null
  }
  return {
    sequence: body.sequence,
    operationId: body.operationId,
    actorKind: body.actorKind,
    actorDigest: body.actorDigest,
    command: body.command,
    scope: body.scope,
    target: body.target,
    reason: body.reason,
    outcome: body.outcome,
    code: typeof body.code === 'string' ? body.code : null,
    policyVersion: body.policyVersion,
    requestedAt: body.requestedAt,
    decidedAt: typeof decidedAt === 'string' ? decidedAt : null,
    recordedAt: body.recordedAt,
    correlationId: body.correlationId,
  }
}
