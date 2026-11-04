import {
  LIFT_RESTRICTION_COMMAND,
  OPERATOR_AUDIT_ENDPOINT,
  OPERATOR_COMMANDS_ENDPOINT,
  OPERATOR_INQUIRIES_ENDPOINT,
  OPERATOR_INQUIRY_METRICS_ENDPOINT,
  OPERATOR_NOTICES_ENDPOINT,
  OPERATOR_OPERATIONS_ENDPOINT,
  OPERATOR_RESTRICTIONS_ENDPOINT,
  PUBLISH_NOTICE_COMMAND,
  UPDATE_INQUIRY_COMMAND,
  type RecoveryCondition,
  type RestrictionCommand,
} from '../content/operator'
import { objectOf, postJson, readJson, type JsonObject } from './http'
import { isInstant } from './instant'
import { SESSION_REQUEST_TIMEOUT_MS } from './useSessionState'

/**
 * 보호된 운영자 표면의 전송(Story 3.1). 이 모듈은 운영자 화면 chunk만 import한다.
 *
 * 응답 값은 메모리에만 둔다 — 브라우저 저장소·URL에 쓰지 않는다. 멱등 키는 헤더로만 나간다. 수행자를 본문에 싣지
 * 않는다(서버가 세션으로 정하고, 실으면 거절한다).
 */

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

// ------------------------------------------------------------------ 운영 command 공통(Epic 3 회고 AI-5)

/** 준비의 결말. 준비는 아무것도 실행하지 않는다 — 서버가 허가를 발급하고 무장한 뒤 확인 문구를 준다. */
export type CommandPrepareOutcome<P> =
  | { readonly outcome: 'prepared'; readonly prepared: P }
  | { readonly outcome: 'refused'; readonly status: number; readonly code: string | null }

/** 확정의 결말. 같은 키의 재전송은 서버가 같은 결과를 재생한다. */
export type CommandConfirmOutcome<C> =
  | { readonly outcome: 'completed'; readonly completed: C }
  | { readonly outcome: 'refused'; readonly status: number; readonly code: string | null }
  /** 응답을 받지 못했거나 서버가 커밋 여부를 말하지 못했다. 자동으로 다시 실행하지 않는다. */
  | { readonly outcome: 'unanswered' }

/** 준비 응답의 공통 머리. 모든 command가 같은 필드를 준다. */
export interface PreparedHead {
  readonly operationId: string
  readonly scope: string
  readonly target: string
  readonly reason: string
  readonly confirmationPhrase: string
}

/** 준비 응답의 공통 머리. 하나라도 모양이 아니면 `null`이다(확인 문구가 비어 있어도). */
export function preparedHeadOf(body: JsonObject): PreparedHead | null {
  if (
    typeof body.operationId !== 'string' ||
    typeof body.scope !== 'string' ||
    typeof body.target !== 'string' ||
    typeof body.reason !== 'string' ||
    typeof body.confirmationPhrase !== 'string' ||
    body.confirmationPhrase === ''
  ) {
    return null
  }
  return {
    operationId: body.operationId,
    scope: body.scope,
    target: body.target,
    reason: body.reason,
    confirmationPhrase: body.confirmationPhrase,
  }
}

function commandEndpoint(command: string): string {
  return `${OPERATOR_COMMANDS_ENDPOINT}/${command}`
}

/**
 * 운영 command 준비 — 일곱 command가 이 한 벌이다. 해석기가 그 command의 응답 모양(미리보기·대상 대조)을 본다. 해석기가
 * 받지 않은 성공 응답은 거절로 접는다(무장된 것을 화면이 단정하지 않는다).
 */
export async function prepareCommand<P>(
  csrfToken: string | null,
  command: string,
  body: object,
  idempotencyKey: string,
  parse: (body: JsonObject) => P | null,
): Promise<CommandPrepareOutcome<P>> {
  const result = await post(
    commandEndpoint(command),
    csrfToken,
    body,
    idempotencyKey,
    SESSION_REQUEST_TIMEOUT_MS,
  )
  if (result.ok) {
    const payload = objectOf(result.payload)
    const prepared = payload === null ? null : parse(payload)
    if (prepared !== null) {
      return { outcome: 'prepared', prepared }
    }
  }
  return { outcome: 'refused', status: result.status, code: result.code }
}

/**
 * 운영 command 확정 — 일곱 command가 이 한 벌이다. 결과가 `COMPLETED`이고 operation이 같을 때만 해석기에 넘긴다. 해석기가
 * 받지 않은 성공 응답은 커밋 여부를 단정하지 않는다(`unanswered`).
 *
 * @param timeoutMs 확정의 시한. 추출은 최대 10,000행을 읽고 직렬화하므로 다른 command보다 길다
 */
export async function confirmCommand<C>(
  csrfToken: string | null,
  command: string,
  operationId: string,
  confirmation: string,
  idempotencyKey: string,
  parse: (body: JsonObject, operationId: string) => C | null,
  timeoutMs: number = SESSION_REQUEST_TIMEOUT_MS,
): Promise<CommandConfirmOutcome<C>> {
  const result = await post(
    `${commandEndpoint(command)}/confirm`,
    csrfToken,
    { operationId, confirmation },
    idempotencyKey,
    timeoutMs,
  )
  if (!result.received) {
    return csrfToken === null
      ? { outcome: 'refused', status: 0, code: null }
      : { outcome: 'unanswered' }
  }
  if (result.ok) {
    const payload = objectOf(result.payload)
    const completed =
      payload !== null && payload.outcome === 'COMPLETED' && payload.operationId === operationId
        ? parse(payload, operationId)
        : null
    return completed === null ? { outcome: 'unanswered' } : { outcome: 'completed', completed }
  }
  /*
   * 5xx는 모두 커밋 여부를 단정하지 않는다. 서버가 공통 본문으로 답한 500도 실행이 커밋된 **뒤**의 실패(행 재조회·smoke·
   * 세션 회전)일 수 있다 — "바뀐 것은 없습니다"로 접으면 실행된 command를 실패로 말하게 된다. 같은 키로 다시 확정하면
   * 원장이 같은 결과를 재생한다.
   */
  if (result.status >= 500) {
    return { outcome: 'unanswered' }
  }
  return { outcome: 'refused', status: result.status, code: result.code }
}

/** 준비를 되돌린다. 최선 노력이며 결과를 돌려주지 않는다. */
export async function cancelCommand(csrfToken: string | null, command: string): Promise<void> {
  await post(
    `${commandEndpoint(command)}/cancel`,
    csrfToken,
    null,
    null,
    SESSION_REQUEST_TIMEOUT_MS,
  )
}

// ------------------------------------------------------------------ 감사 내보내기(Story 3.1)

/** 확정의 시한. 추출은 최대 10,000행을 읽고 직렬화하므로 다른 요청보다 길다. */
export const EXPORT_CONFIRM_TIMEOUT_MS = 30_000

/** 내보내기 준비의 응답. 정책 버전까지 있어야 한다. */
export function preparedExportOf(body: JsonObject): PreparedExport | null {
  const head = preparedHeadOf(body)
  if (head === null || typeof body.policyVersion !== 'string') {
    return null
  }
  return { ...head, policyVersion: body.policyVersion }
}

/** 내보내기 확정의 응답. 행 묶음이 하나라도 모양이 아니면 받지 않는다. */
export function completedExportOf(body: JsonObject, operationId: string): CompletedExport | null {
  const rows = entriesIn(body.rows)
  if (
    rows === null ||
    typeof body.target !== 'string' ||
    typeof body.rowCount !== 'number' ||
    typeof body.truncated !== 'boolean'
  ) {
    return null
  }
  return {
    operationId,
    target: body.target,
    rowCount: body.rowCount,
    truncated: body.truncated,
    rows,
  }
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

/** 제한 준비의 응답. 영향 미리보기가 있고 command가 요청한 것과 같아야 한다. */
export function preparedRestrictionOf(
  body: JsonObject,
  command: RestrictionCommand,
): PreparedRestriction | null {
  const head = preparedHeadOf(body)
  const impact = impactOf(body.impact)
  if (head === null || impact === null || body.command !== command) {
    return null
  }
  return { ...head, command, impact }
}

/** 제한 확정의 응답. */
export function completedRestrictionOf(
  body: JsonObject,
  operationId: string,
): CompletedRestriction | null {
  if (
    typeof body.restrictionId !== 'string' ||
    typeof body.feature !== 'string' ||
    typeof body.scope !== 'string' ||
    !isInstant(body.effectiveFrom)
  ) {
    return null
  }
  return {
    operationId,
    restrictionId: body.restrictionId,
    feature: body.feature,
    scope: body.scope,
    effectiveFrom: body.effectiveFrom,
  }
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

/** 복구 준비의 본문. 원인은 사유 자리다. */
export function recoveryBodyOf(input: RecoveryInput): object {
  return {
    target: input.restrictionId,
    reason: input.reason,
    verification: input.verification,
    evidenceRef: input.evidenceRef,
    deployVersion: input.deployVersion,
    conditions: input.conditions,
  }
}

/** 복구 준비의 응답. 미리보기가 있고 대상이 고른 제한이어야 한다. */
export function preparedRecoveryOf(
  body: JsonObject,
  restrictionId: string,
): PreparedRecovery | null {
  const head = preparedHeadOf(body)
  const impact = recoveryImpactOf(body.impact)
  if (
    head === null ||
    impact === null ||
    body.command !== LIFT_RESTRICTION_COMMAND ||
    head.target !== restrictionId
  ) {
    return null
  }
  return { ...head, impact }
}

/** 복구 확정의 응답. 커밋 뒤 smoke 결말이 있어야 한다. */
export function completedRecoveryOf(
  body: JsonObject,
  operationId: string,
): CompletedRecovery | null {
  const smoke = smokeOf(body.smoke)
  if (
    smoke === null ||
    typeof body.restrictionId !== 'string' ||
    typeof body.feature !== 'string' ||
    typeof body.scope !== 'string' ||
    !isInstant(body.liftedAt)
  ) {
    return null
  }
  return {
    operationId,
    restrictionId: body.restrictionId,
    feature: body.feature,
    scope: body.scope,
    liftedAt: body.liftedAt,
    smoke,
  }
}

// ------------------------------------------------------------------ 서비스 공지(Story 3.5)

/** 공지 버전 하나(운영자 목록). 영향·계속 가능 기능은 서버가 덮는 제한에서 계산했다. */
export interface NoticeVersionEntry {
  readonly version: number
  readonly previousVersion: number | null
  readonly status: string
  readonly affectedFeatures: readonly string[]
  readonly continuingFeatures: readonly string[]
  readonly message: string
  readonly nextUpdateAt: string | null
  readonly recoveredAt: string | null
  readonly covers: readonly string[]
  /**
   * 덮는 제한의 되돌림 계보 가운데 지금 열린 제한(Epic 3 회고 AI-1). 사건의 최신 버전에만 오고 그 밖은 `null`이다. 해제
   * smoke가 같은 범위를 새 제한으로 되돌렸으면(닫힌 중간 행을 지나도) 그 새 제한이다.
   */
  readonly liveCovers: readonly string[] | null
  readonly operationId: string
  readonly createdAt: string
}

/** 사건 하나와 그 버전 이력(오래된 순). `closed`면 새 버전을 받지 않는다. */
export interface NoticeIncident {
  readonly incidentId: string
  readonly startedAt: string
  readonly closed: boolean
  readonly versions: readonly NoticeVersionEntry[]
}

export interface NoticeIncidentList {
  readonly supportContactConfigured: boolean
  readonly items: readonly NoticeIncident[]
}

/** 공지 발행 준비의 입력. 사건은 `NEW`이거나 기존 사건 ID다. */
export interface NoticeInput {
  readonly incident: string
  readonly status: string
  readonly covers: readonly string[]
  readonly message: string
  /** 진행 중인 공지의 다음 안내 시각(ISO-8601). 복구 공지는 `null`이다. */
  readonly nextUpdateAt: string | null
}

/** 공지 발행의 미리보기. 영향·계속 가능 기능은 서버가 계산했다. */
export interface NoticeImpact {
  readonly incident: string
  readonly version: number
  readonly status: string
  readonly affectedFeatures: readonly string[]
  readonly continuingFeatures: readonly string[]
  readonly covers: readonly string[]
  readonly nextUpdateAt: string | null
}

export interface PreparedNotice {
  readonly operationId: string
  readonly scope: string
  readonly target: string
  /** 운영자 한 줄 메시지(원장의 사유 자리). */
  readonly reason: string
  readonly confirmationPhrase: string
  readonly impact: NoticeImpact
}

export interface CompletedNotice {
  readonly operationId: string
  readonly incidentId: string
  readonly version: number
  readonly status: string
  readonly publishedAt: string
}

/** 공지 사건 목록. 하나라도 모양이 어긋나면 목록 전체를 믿지 않는다. */
export async function fetchNoticeIncidents(
  signal: AbortSignal,
): Promise<ReadResult<NoticeIncidentList>> {
  const result = await read(OPERATOR_NOTICES_ENDPOINT, signal)
  if (result.status !== 'loaded') {
    return result
  }
  const body = result.value
  if (typeof body.supportContactConfigured !== 'boolean' || !Array.isArray(body.items)) {
    return { status: 'failed', code: null }
  }
  const items: NoticeIncident[] = []
  for (const item of body.items as unknown[]) {
    const incident = incidentOf(item)
    if (incident === null) {
      return { status: 'failed', code: null }
    }
    items.push(incident)
  }
  return {
    status: 'loaded',
    value: { supportContactConfigured: body.supportContactConfigured, items },
  }
}

/** 공지 발행 준비의 본문. 복구 공지는 다음 안내 시각을 싣지 않는다. */
export function noticeBodyOf(input: NoticeInput): object {
  return {
    incident: input.incident,
    status: input.status,
    covers: input.covers,
    message: input.message,
    ...(input.nextUpdateAt === null ? {} : { nextUpdateAt: input.nextUpdateAt }),
  }
}

/** 공지 발행 준비의 응답. 미리보기가 있고 사건이 고른 것이어야 한다. */
export function preparedNoticeOf(body: JsonObject, incident: string): PreparedNotice | null {
  const head = preparedHeadOf(body)
  const impact = noticeImpactOf(body.impact)
  if (
    head === null ||
    impact === null ||
    body.command !== PUBLISH_NOTICE_COMMAND ||
    head.target !== incident
  ) {
    return null
  }
  return { ...head, impact }
}

/** 공지 발행 확정의 응답. */
export function completedNoticeOf(body: JsonObject, operationId: string): CompletedNotice | null {
  if (
    typeof body.incidentId !== 'string' ||
    typeof body.version !== 'number' ||
    typeof body.status !== 'string' ||
    !isInstant(body.publishedAt)
  ) {
    return null
  }
  return {
    operationId,
    incidentId: body.incidentId,
    version: body.version,
    status: body.status,
    publishedAt: body.publishedAt,
  }
}

// ------------------------------------------------------------------ 문의 처리(Story 3.6)

/** 운영자 목록의 문의 하나. 본문·답변이 없고 회신 이메일은 마스킹한 값이다. 사용자 식별자가 없다. */
export interface OperatorInquiry {
  readonly reference: string
  readonly category: string
  readonly title: string
  readonly status: string
  readonly receivedAt: string
  readonly targetAt: string | null
  readonly targetState: string
  readonly firstAnsweredAt: string | null
  /** 첫 답변이 목표 안이었는가. 답변 전이거나 목표 미정이면 `null`이다. */
  readonly targetMet: boolean | null
  readonly contact: string
  /** 마스킹한 회신 이메일(`a***@d***`). 서비스 안 회신이면 `null`이다. */
  readonly replyEmail: string | null
  readonly resolvedAt: string | null
}

/** 운영자 상세: 요약에 본문·답변과 회신 이메일 원문을 더한다(운영자가 자기 메일로 회신하는 용도). */
export interface OperatorInquiryDetail {
  readonly summary: OperatorInquiry
  readonly description: string
  readonly answer: string | null
  /** 회신 이메일 원문. 서비스 안 회신이면 `null`이다. */
  readonly replyEmail: string | null
}

/** 기간 집계. 수만 담는다 — 달성 선언 문장이 없다. */
export interface InquiryMetrics {
  readonly from: string
  readonly to: string
  readonly asOf: string
  readonly calendarVersion: string
  readonly due: number
  readonly metTarget: number
  /** 달성률(%). 분모가 0이면 `null`이다. */
  readonly ratePercent: number | null
  readonly calendarPending: number
  readonly measured: number
  readonly totalBusinessMinutes: number
  readonly medianBusinessMinutes: number | null
  /** 기간이 보존 경계보다 앞서면 그 경계(그 이전 해결 문의는 지워져 집계에 없다). 아니면 `null`이다. */
  readonly purgeHorizon: string | null
}

/** 문의 처리 준비의 입력. 답변은 답변 완료 전이에만 싣는다. */
export interface InquiryUpdateInput {
  readonly reference: string
  readonly status: string
  readonly reason: string
  readonly answer: string | null
}

export interface InquiryUpdateImpact {
  readonly reference: string
  readonly currentStatus: string | null
  readonly nextStatus: string
  readonly targetAt: string | null
  readonly answerProvided: boolean
}

export interface PreparedInquiryUpdate {
  readonly operationId: string
  readonly scope: string
  readonly target: string
  readonly reason: string
  readonly confirmationPhrase: string
  readonly impact: InquiryUpdateImpact
}

export interface CompletedInquiryUpdate {
  readonly operationId: string
  readonly status: string
  readonly transitionedAt: string
}

const INQUIRY_REFERENCE = /^Q-[A-Z2-7]{10}$/

/**
 * 문의 목록. 미해결이 먼저다. 상태를 주면 그 상태만이다(쿼리에는 상태 code만 싣는다). 하나라도 모양이 어긋나면 목록 전체를
 * 믿지 않는다.
 */
export async function fetchInquiries(
  signal: AbortSignal,
  status: string | null = null,
): Promise<ReadResult<readonly OperatorInquiry[]>> {
  const path =
    status === null
      ? OPERATOR_INQUIRIES_ENDPOINT
      : `${OPERATOR_INQUIRIES_ENDPOINT}?${new URLSearchParams({ status }).toString()}`
  const result = await read(path, signal)
  if (result.status !== 'loaded') {
    return result
  }
  const items = result.value.items
  if (!Array.isArray(items)) {
    return { status: 'failed', code: null }
  }
  const inquiries: OperatorInquiry[] = []
  for (const item of items) {
    const inquiry = operatorInquiryOf(item)
    if (inquiry === null) {
      return { status: 'failed', code: null }
    }
    inquiries.push(inquiry)
  }
  return { status: 'loaded', value: inquiries }
}

/** 문의 상세. 본문은 여기에서만 온다. reference가 모양이 아니면 묻지 않는다. */
export async function fetchInquiryDetail(
  reference: string,
  signal: AbortSignal,
): Promise<ReadResult<OperatorInquiryDetail>> {
  if (!INQUIRY_REFERENCE.test(reference)) {
    return { status: 'notFound' }
  }
  const result = await read(`${OPERATOR_INQUIRIES_ENDPOINT}/${reference}`, signal)
  if (result.status !== 'loaded') {
    return result
  }
  const summary = operatorInquiryOf(result.value.summary)
  const answer = result.value.answer
  const replyEmail = result.value.replyEmail
  if (
    summary === null ||
    typeof result.value.description !== 'string' ||
    (answer !== undefined && answer !== null && typeof answer !== 'string') ||
    (replyEmail !== undefined && replyEmail !== null && typeof replyEmail !== 'string')
  ) {
    return { status: 'failed', code: null }
  }
  return {
    status: 'loaded',
    value: {
      summary,
      description: result.value.description,
      answer: typeof answer === 'string' ? answer : null,
      replyEmail: typeof replyEmail === 'string' ? replyEmail : null,
    },
  }
}

/** 기간 집계. 기간은 KST 날짜(`YYYY-MM-DD`)이고 양 끝을 포함한다. */
export async function fetchInquiryMetrics(
  from: string,
  to: string,
  signal: AbortSignal,
): Promise<ReadResult<InquiryMetrics>> {
  const params = new URLSearchParams({ from, to })
  const result = await read(`${OPERATOR_INQUIRY_METRICS_ENDPOINT}?${params.toString()}`, signal)
  if (result.status !== 'loaded') {
    return result
  }
  const body = result.value
  const counts = [
    body.due,
    body.metTarget,
    body.calendarPending,
    body.measured,
    body.totalBusinessMinutes,
  ]
  if (
    typeof body.from !== 'string' ||
    typeof body.to !== 'string' ||
    !isInstant(body.asOf) ||
    typeof body.calendarVersion !== 'string' ||
    !counts.every((count) => typeof count === 'number' && Number.isInteger(count) && count >= 0) ||
    (body.ratePercent !== null && typeof body.ratePercent !== 'number') ||
    (body.medianBusinessMinutes !== null && typeof body.medianBusinessMinutes !== 'number') ||
    (body.purgeHorizon !== undefined && body.purgeHorizon !== null && !isInstant(body.purgeHorizon))
  ) {
    return { status: 'failed', code: null }
  }
  return {
    status: 'loaded',
    value: {
      from: body.from,
      to: body.to,
      asOf: body.asOf,
      calendarVersion: body.calendarVersion,
      due: body.due as number,
      metTarget: body.metTarget as number,
      ratePercent: body.ratePercent,
      calendarPending: body.calendarPending as number,
      measured: body.measured as number,
      totalBusinessMinutes: body.totalBusinessMinutes as number,
      medianBusinessMinutes: body.medianBusinessMinutes,
      purgeHorizon: typeof body.purgeHorizon === 'string' ? body.purgeHorizon : null,
    },
  }
}

/** 문의 처리 준비의 본문. 답변은 답변 완료 전이에만 싣는다. */
export function inquiryUpdateBodyOf(input: InquiryUpdateInput): object {
  return {
    reference: input.reference,
    status: input.status,
    reason: input.reason,
    ...(input.answer === null ? {} : { answer: input.answer }),
  }
}

/** 문의 처리 준비의 응답. 미리보기가 있고 대상이 고른 문의여야 한다. */
export function preparedInquiryUpdateOf(
  body: JsonObject,
  reference: string,
): PreparedInquiryUpdate | null {
  const head = preparedHeadOf(body)
  const impact = inquiryImpactOf(body.impact)
  if (
    head === null ||
    impact === null ||
    body.command !== UPDATE_INQUIRY_COMMAND ||
    head.target !== reference
  ) {
    return null
  }
  return { ...head, impact }
}

/** 문의 처리 확정의 응답. reference·답변 원문은 오지 않는다. */
export function completedInquiryUpdateOf(
  body: JsonObject,
  operationId: string,
): CompletedInquiryUpdate | null {
  if (typeof body.status !== 'string' || !isInstant(body.transitionedAt)) {
    return null
  }
  return { operationId, status: body.status, transitionedAt: body.transitionedAt }
}

function nullableInstant(value: unknown): value is string | null | undefined {
  return value === undefined || value === null || isInstant(value)
}

function operatorInquiryOf(value: unknown): OperatorInquiry | null {
  const body = objectOf(value)
  if (
    body === null ||
    typeof body.reference !== 'string' ||
    !INQUIRY_REFERENCE.test(body.reference) ||
    typeof body.category !== 'string' ||
    typeof body.title !== 'string' ||
    typeof body.status !== 'string' ||
    !isInstant(body.receivedAt) ||
    typeof body.targetState !== 'string' ||
    typeof body.contact !== 'string' ||
    !nullableInstant(body.targetAt) ||
    !nullableInstant(body.firstAnsweredAt) ||
    !nullableInstant(body.resolvedAt) ||
    (body.targetMet !== undefined &&
      body.targetMet !== null &&
      typeof body.targetMet !== 'boolean') ||
    (body.replyEmail !== undefined &&
      body.replyEmail !== null &&
      typeof body.replyEmail !== 'string')
  ) {
    return null
  }
  return {
    reference: body.reference,
    category: body.category,
    title: body.title,
    status: body.status,
    receivedAt: body.receivedAt,
    targetAt: typeof body.targetAt === 'string' ? body.targetAt : null,
    targetState: body.targetState,
    firstAnsweredAt: typeof body.firstAnsweredAt === 'string' ? body.firstAnsweredAt : null,
    targetMet: typeof body.targetMet === 'boolean' ? body.targetMet : null,
    contact: body.contact,
    replyEmail: typeof body.replyEmail === 'string' ? body.replyEmail : null,
    resolvedAt: typeof body.resolvedAt === 'string' ? body.resolvedAt : null,
  }
}

function inquiryImpactOf(value: unknown): InquiryUpdateImpact | null {
  const body = objectOf(value)
  if (
    body === null ||
    typeof body.reference !== 'string' ||
    typeof body.nextStatus !== 'string' ||
    typeof body.answerProvided !== 'boolean' ||
    (body.currentStatus !== undefined &&
      body.currentStatus !== null &&
      typeof body.currentStatus !== 'string') ||
    !nullableInstant(body.targetAt)
  ) {
    return null
  }
  return {
    reference: body.reference,
    currentStatus: typeof body.currentStatus === 'string' ? body.currentStatus : null,
    nextStatus: body.nextStatus,
    targetAt: typeof body.targetAt === 'string' ? body.targetAt : null,
    answerProvided: body.answerProvided,
  }
}

function noticeImpactOf(value: unknown): NoticeImpact | null {
  const body = objectOf(value)
  if (
    body === null ||
    typeof body.incident !== 'string' ||
    typeof body.version !== 'number' ||
    typeof body.status !== 'string' ||
    !stringArray(body.affectedFeatures) ||
    !stringArray(body.continuingFeatures) ||
    !stringArray(body.covers)
  ) {
    return null
  }
  const next = body.nextUpdateAt
  if (next !== undefined && next !== null && !isInstant(next)) {
    return null
  }
  return {
    incident: body.incident,
    version: body.version,
    status: body.status,
    affectedFeatures: body.affectedFeatures,
    continuingFeatures: body.continuingFeatures,
    covers: body.covers,
    nextUpdateAt: typeof next === 'string' ? next : null,
  }
}

function incidentOf(value: unknown): NoticeIncident | null {
  const body = objectOf(value)
  if (
    body === null ||
    typeof body.incidentId !== 'string' ||
    !isInstant(body.startedAt) ||
    typeof body.closed !== 'boolean' ||
    !Array.isArray(body.versions) ||
    body.versions.length === 0
  ) {
    return null
  }
  const versions: NoticeVersionEntry[] = []
  for (const item of body.versions as unknown[]) {
    const version = objectOf(item)
    if (
      version === null ||
      typeof version.version !== 'number' ||
      typeof version.status !== 'string' ||
      !stringArray(version.affectedFeatures) ||
      !stringArray(version.continuingFeatures) ||
      typeof version.message !== 'string' ||
      !stringArray(version.covers) ||
      typeof version.operationId !== 'string' ||
      !isInstant(version.createdAt)
    ) {
      return null
    }
    const previous = version.previousVersion
    const next = version.nextUpdateAt
    const recovered = version.recoveredAt
    const live = version.liveCovers
    if (
      (live !== undefined && live !== null && !stringArray(live)) ||
      (previous !== undefined && previous !== null && typeof previous !== 'number') ||
      (next !== undefined && next !== null && !isInstant(next)) ||
      (recovered !== undefined && recovered !== null && !isInstant(recovered))
    ) {
      return null
    }
    versions.push({
      version: version.version,
      previousVersion: typeof previous === 'number' ? previous : null,
      status: version.status,
      affectedFeatures: version.affectedFeatures,
      continuingFeatures: version.continuingFeatures,
      message: version.message,
      nextUpdateAt: typeof next === 'string' ? next : null,
      recoveredAt: typeof recovered === 'string' ? recovered : null,
      covers: version.covers,
      liveCovers: stringArray(live) ? live : null,
      operationId: version.operationId,
      createdAt: version.createdAt,
    })
  }
  return {
    incidentId: body.incidentId,
    startedAt: body.startedAt,
    closed: body.closed,
    versions,
  }
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

async function read(path: string, signal: AbortSignal): Promise<ReadResult<JsonObject>> {
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
    const response = await postJson(path, {
      csrfToken,
      body: body ?? undefined,
      idempotencyKey: idempotencyKey ?? undefined,
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
