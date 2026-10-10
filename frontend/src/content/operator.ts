/**
 * 보호된 운영자 화면의 카피와 경로(Story 3.1·3.2·3.3·3.4·3.5).
 *
 * 화면과 시험이 같은 문자열을 참조한다. 이 모듈은 운영자 화면 chunk(`screens/OperatorScreen`)만 import한다 — 일반
 * 번들의 초기 화면에 운영자 제어의 카피가 섞이지 않는다. 경로 상수 `OPERATOR_PATH`는 예외로 `content/auth.ts`가
 * 소유한다(복귀 목적지 집합이 그것을 알아야 한다).
 *
 * 금지 표현(실시간·정확 보장, 수익 축하, 손실 공포)을 쓰지 않는다. 감사 데이터는 사실이지 평가가 아니다.
 */

import { OPERATOR_PATH } from './auth'
import { noticeFeatureCodesText, noticeTimeText } from './serviceNotice'

/** 운영자 표면의 API 경로. 백엔드 `OperatorGate`·`OperatorController`와 같은 값이다. */
export const OPERATOR_AUDIT_ENDPOINT = '/api/operator/audit'

export const OPERATOR_OPERATIONS_ENDPOINT = '/api/operator/operations'

export const OPERATOR_COMMANDS_ENDPOINT = '/api/operator/commands'

export const OPERATOR_EXPORT_ENDPOINT = `${OPERATOR_COMMANDS_ENDPOINT}/audit-export`

/** 효력 중인 제한의 목록(Story 3.2). command 권한이다. */
export const OPERATOR_RESTRICTIONS_ENDPOINT = '/api/operator/restrictions'

/** 공지 사건 목록(Story 3.5). command 권한이다. */
export const OPERATOR_NOTICES_ENDPOINT = '/api/operator/notices'

export const OPERATOR_EXPORT_CONFIRM_ENDPOINT = `${OPERATOR_EXPORT_ENDPOINT}/confirm`

export const OPERATOR_EXPORT_CANCEL_ENDPOINT = `${OPERATOR_EXPORT_ENDPOINT}/cancel`

/** 감사 내보내기가 받는 유일한 범위. 서버가 다른 값을 거절한다. */
export const AUDIT_LEDGER_SCOPE = 'AUDIT_LEDGER'

/** 사유의 최대 길이. 서버 규칙(`OperatorCommandApi.MAX_REASON_CHARS`)과 같다. */
export const REASON_MAX_LENGTH = 500

// ------------------------------------------------------------------ 제한(Story 3.2)

/** 제한이 막는 기능(NFR-17). 서버 `RestrictionFeature`와 같은 집합이다. */
export const RESTRICTION_FEATURES = [
  'NEW_CONNECTION',
  'SYNC',
  'CALCULATION_DISPLAY',
  'ADVERTISING',
] as const

export type RestrictionFeature = (typeof RESTRICTION_FEATURES)[number]

/** 기능별 제한 command. 백엔드 `OperatorCommandApi.RESTRICT_*`와 같은 값이다. */
export const RESTRICTION_COMMANDS = {
  NEW_CONNECTION: 'restrict-new-connection',
  SYNC: 'restrict-sync',
  CALCULATION_DISPLAY: 'restrict-calculation-display',
  ADVERTISING: 'restrict-advertising',
} as const satisfies Record<RestrictionFeature, string>

export type RestrictionCommand = (typeof RESTRICTION_COMMANDS)[RestrictionFeature]

export type RestrictionScope =
  'GLOBAL' | 'PLAN' | 'PORTFOLIO' | 'CALCULATION_POLICY_VERSION' | 'PROVIDER_CONNECTION'

/**
 * 기능이 받는 범위. 서버(`RestrictionFeature.scopes`)가 같은 조합을 검증하고 다른 조합은 `UNSUPPORTED_SCOPE`로
 * 거절한다 — 화면의 이 표는 선택지를 좁히는 편의일 뿐 권위가 아니다.
 */
export const RESTRICTION_SCOPES: Readonly<Record<RestrictionFeature, readonly RestrictionScope[]>> =
  {
    NEW_CONNECTION: ['GLOBAL'],
    SYNC: ['GLOBAL', 'PLAN', 'PORTFOLIO', 'PROVIDER_CONNECTION'],
    CALCULATION_DISPLAY: ['GLOBAL', 'PLAN', 'PORTFOLIO', 'CALCULATION_POLICY_VERSION'],
    ADVERTISING: ['GLOBAL'],
  }

/** 전역 제한의 고정 target. */
export const GLOBAL_TARGET = 'ALL'

/** 이 빌드가 아는 계산 정책 버전. 서버가 다시 확인한다. */
export const CALCULATION_POLICY_TARGET = 'CALC-1.0'

const FEATURE_LABELS: Readonly<Record<string, string>> = {
  NEW_CONNECTION: '신규 연결',
  SYNC: '동기화',
  CALCULATION_DISPLAY: '계산 표시',
  ADVERTISING: '광고',
}

const SCOPE_LABELS: Readonly<Record<string, string>> = {
  GLOBAL: '전역',
  PLAN: '계획',
  PORTFOLIO: '포트폴리오',
  CALCULATION_POLICY_VERSION: '계산 정책 버전',
  PROVIDER_CONNECTION: '연결',
}

// ------------------------------------------------------------------ 복구(Story 3.4)

/** 제한 해제 command. 백엔드 `OperatorCommandApi.LIFT_RESTRICTION`과 같은 값이다. 제한이 닫히는 경로는 이것 하나다. */
export const LIFT_RESTRICTION_COMMAND = 'lift-restriction'

/**
 * 복구 조건 네 code(`OPS-SIGNAL-1.0` §6.2). 서버 `OperatorCommandApi.RECOVERY_CONDITIONS`와 같은 집합·순서다. 넷을 모두
 * 확인해야 복구를 준비할 수 있다.
 */
export const RECOVERY_CONDITIONS = [
  'CAUSE_REMOVED',
  'IMPACT_REPRODUCED',
  'REGRESSION_OWNERSHIP_POINTER_PASSED',
  'NEW_DEPLOY_EVIDENCE',
] as const

export type RecoveryCondition = (typeof RECOVERY_CONDITIONS)[number]

export const RECOVERY_CONDITION_LABELS: Readonly<Record<RecoveryCondition, string>> = {
  CAUSE_REMOVED: '원인을 제거했습니다',
  IMPACT_REPRODUCED: '영향 범위를 재현 시험으로 확인했습니다',
  REGRESSION_OWNERSHIP_POINTER_PASSED: '고정 회귀·소유권·포인터 검사를 통과했습니다',
  NEW_DEPLOY_EVIDENCE: '새 배포·버전 증거가 있습니다',
}

/** 배포 버전의 최대 길이. 서버 규칙(`OperatorCommandApi.MAX_DEPLOY_VERSION_CHARS`)과 같다. */
export const DEPLOY_VERSION_MAX_LENGTH = 64

// ------------------------------------------------------------------ 서비스 공지(Story 3.5)

/** 공지 발행 command. 백엔드 `OperatorCommandApi.PUBLISH_NOTICE`와 같은 값이다. 공지 행은 이것만 쓴다. */
export const PUBLISH_NOTICE_COMMAND = 'publish-notice'

/** 새 사건을 여는 공지 발행의 대상. */
export const NEW_INCIDENT = 'NEW'

/** 공지 메시지의 최대 길이. 서버 규칙(`ServiceNoticeApi.MAX_MESSAGE_CHARS`)과 같다. */
export const NOTICE_MESSAGE_MAX_LENGTH = 200

/** 기존 사건이 받는 상태. 새 사건은 언제나 `INVESTIGATING`이다(서버가 같은 조합을 검증한다). */
export const FOLLOW_UP_NOTICE_STATUSES = ['SCOPE_CHANGED', 'RECOVERED'] as const

export type FollowUpNoticeStatus = (typeof FOLLOW_UP_NOTICE_STATUSES)[number]

const NOTICE_STATUS_LABELS: Readonly<Record<string, string>> = {
  INVESTIGATING: '조사 중',
  SCOPE_CHANGED: '범위 변경',
  RECOVERED: '복구됨',
}

/** 공지 상태 → 화면 문장. 사용자 화면의 상태 표지와 같은 말이다. 모르는 값은 그대로 보인다(운영자 표면이다). */
export function noticeStatusLabel(status: string): string {
  return NOTICE_STATUS_LABELS[status] ?? status
}

/**
 * 공지의 기능 목록 문장. 사용자 화면의 서비스 공지와 같은 문장이다(`content/serviceNotice`) — 운영자가 미리 본 문장이 곧
 * 사용자가 볼 문장이다. 비었으면 `없음`이다.
 */
export function featuresText(features: readonly string[]): string {
  return noticeFeatureCodesText(features)
}

/** 공지 시각. 사용자 화면과 같은 서울 시각에 `KST`를 붙인다. */
export function noticeTime(value: string): string {
  return noticeTimeText(value)
}

/** 사건 선택지의 이름: 시작 시각·최신 버전·상태. 사건 ID 앞 8자로 같은 시각의 사건을 가른다. */
export function incidentOptionLabel(
  startedAt: string,
  version: number,
  status: string,
  incidentId: string,
): string {
  return `${noticeTimeText(startedAt)} 시작 · v${String(version)} ${noticeStatusLabel(status)} · ${incidentId.slice(0, 8)}`
}

const SMOKE_LABELS: Readonly<Record<string, string>> = {
  PASSED: '통과 — 해제한 제한이 더는 보고되지 않고 남는 제한은 그대로 막습니다.',
  REVERTED:
    '되돌림 — 확인이 어긋나 같은 범위를 새 제한으로 다시 막았습니다. 해제와 되돌림이 모두 감사 원장에 남았습니다.',
}

/**
 * 복구 smoke의 결말 → 화면 문장. `PENDING`(원장에 결말이 아직 없다)과 모르는 값은 결말을 단정하지 않는 "확인 중" 문장
 * 하나로 접는다 — 통과로도 되돌림으로도 말하지 않는다.
 */
export function smokeStatusLabel(status: string): string {
  return SMOKE_LABELS[status] ?? SMOKE_PENDING_LABEL
}

export const SMOKE_PENDING_LABEL =
  '확인 중 — 복구 확인의 결말이 아직 감사 원장에 없습니다. 아래 operation 조회로 확인해 주세요.'

/** smoke 되돌림 행(Story 3.4)의 사유 표지. 서버 `OperationalRestrictionApi.REINSTATED_REASON`과 같은 값이다. */
export const REINSTATED_REASON = 'SMOKE_REVERTED'

/**
 * 행마다의 복구 버튼의 접근 가능한 이름. 보이는 글자 `복구`로 시작하고 기능·범위·대상을 붙여 행끼리 갈린다. 전역은
 * 대상이 없다.
 */
export function recoverButtonLabel(feature: string, scope: string, target: string): string {
  const base = `복구: ${featureLabel(feature)} ${scopeLabel(scope)}`
  return scope === 'GLOBAL' ? base : `${base} ${target}`
}

/**
 * 운영자 화면의 시각 표시(KST). 운영자 chunk가 다른 화면의 카피 모듈에 기대지 않도록 여기 둔다. 읽을 수 없는 값은 빈
 * 문자열이다.
 */
export function formatOperatorTime(value: string): string {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return ''
  }
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(parsed)
}

/** 막히는 단계 code → 화면 문장. 모르는 code는 그대로 보인다. */
const STAGE_LABELS: Readonly<Record<string, string>> = {
  CONNECTION_CREATE: '새 연결 시작·저장',
  FIRST_SNAPSHOT: '첫 현재가치 스냅샷',
  INITIAL_COLLECTION: '초기 원천 사건 수집',
  PLAN_CONDITIONS: '계획 조건 미리보기·확정',
  FIRST_PUBLICATION: '최초 계획 발행',
  NEXT_PLAN_BASELINE: '후속 계획 기준 현재가치',
  INCREMENTAL_SYNC: '증분 동기화',
  INCREMENTAL_PUBLICATION: '새 계산 버전 발행',
  PLAN_END: '계획 종료',
  DASHBOARD_RESULT: '대시보드 계산 결과 표시',
  EVIDENCE: '계산 근거',
  ADJUSTMENTS: '조정 이력',
  ADVERTISING: '광고',
}

export function featureLabel(feature: string): string {
  return FEATURE_LABELS[feature] ?? feature
}

export function scopeLabel(scope: string): string {
  return SCOPE_LABELS[scope] ?? scope
}

export function stageLabel(stage: string): string {
  return STAGE_LABELS[stage] ?? stage
}

/**
 * 자동 정지(Story 3.3)의 사유인 신호 종류 code → 화면 문장. 판정표 `OPS-SIGNAL-1.0`의 아홉과 판정표를 읽지 못한 경우다.
 * 모르는 code는 그대로 보인다 — 운영자 표면이라 원 code가 단서다.
 */
const SIGNAL_REASON_LABELS: Readonly<Record<string, string>> = {
  WRITE_KEY_STORED: '쓰기 가능 키가 저장 단계에 도달',
  CROSS_USER_ACCESS: '교차 사용자 접근 또는 공식 포인터 혼합',
  SECRET_EXPOSED: '비밀 값 노출 검출',
  RESULT_HASH_MISMATCH: '같은 입력의 다른 계산 결과',
  PORTFOLIO_INVARIANT_VIOLATION: 'portfolio 포인터 불변식 위반',
  SOURCE_ID_CONFLICT: '같은 원천 ID의 의미 충돌',
  UNKNOWN_TRANSACTION_ENUM: '미인식 거래 기록 종류',
  SCHEMA_VALIDATION_BURST: '여러 계정의 응답 형식 검증 실패',
  CREDENTIAL_DECRYPTION_BURST: '여러 사용자의 자격정보 복호화 실패',
  POLICY_UNAVAILABLE: '판정표를 읽지 못해 전역 정지',
}

export function signalReasonLabel(reason: string): string {
  return SIGNAL_REASON_LABELS[reason] ?? reason
}

/** 제한의 출처(Story 3.3) → 화면 문장. 모르는 출처는 원 값이 아니라 안전한 문장 하나로 접는다. */
const ORIGIN_LABELS: Readonly<Record<string, string>> = {
  OPERATOR: '운영자',
  SIGNAL: '자동 정지',
}

export const UNKNOWN_ORIGIN_LABEL = '확인되지 않은 출처'

export function originLabel(origin: string): string {
  return ORIGIN_LABELS[origin] ?? UNKNOWN_ORIGIN_LABEL
}

/** 감사 결과 값 → 화면 문장. 색만으로 결과를 가르지 않는다 — 글자가 결과를 말한다. */
const OUTCOMES: Readonly<Record<string, string>> = {
  REQUESTED: '요청',
  APPROVED: '승인',
  DENIED: '거절',
  FAILED: '실패',
  CANCELLED: '취소',
  COMPLETED: '완료',
}

/** 조회 필터의 결과 선택지. 빈 값은 "전체"다. */
export const OUTCOME_FILTERS = [
  'REQUESTED',
  'APPROVED',
  'DENIED',
  'FAILED',
  'CANCELLED',
  'COMPLETED',
] as const

export function outcomeLabel(outcome: string): string {
  return OUTCOMES[outcome] ?? outcome
}

/** 거절·실패 code → 화면 문장. 닫힌 맵이며 모르는 code는 하나로 접는다. 백엔드 `OperatorController.REFUSALS`와 같은 집합이다. */
const REFUSALS: Readonly<Record<string, string>> = {
  REAUTHENTICATION_REQUIRED:
    '운영 명령을 준비하려면 먼저 본인 확인이 필요합니다. 아래 로그인 수단으로 본인 확인을 마친 뒤 다시 준비해 주세요.',
  OPERATOR_COMMAND_NOT_CONFIRMED:
    '확인된 운영 명령이 아니어서 실행하지 않았습니다. 명령을 다시 준비해 주세요.',
  CONFIRMATION_MISMATCH:
    '확인 문구가 일치하지 않아 실행하지 않았습니다. 화면의 문구를 그대로 입력해 주세요.',
  IDEMPOTENCY_CONFLICT: '요청이 겹쳐 실행하지 않았습니다. 명령을 새로 준비해 주세요.',
  IDEMPOTENCY_KEY_REQUIRED: '요청을 받지 않았습니다. 화면을 새로 열어 다시 시도해 주세요.',
  REASON_REQUIRED: '사유를 입력해 주세요.',
  REASON_INVALID: '사유는 500자 이내의 한 줄 문장이어야 하고 이메일 주소를 담을 수 없습니다.',
  UNSUPPORTED_SCOPE: '이 운영 명령이 지원하지 않는 범위입니다.',
  TARGET_INVALID:
    '대상이 올바르지 않거나 지금 이 명령의 대상이 될 수 없습니다. 대상을 확인해 주세요.',
  ALREADY_RESTRICTED:
    '같은 기능·범위·대상의 제한이 이미 효력 중입니다. 활성 제한 목록을 확인해 주세요.',
  ACTOR_NOT_ACCEPTED: '요청에 수행자를 담을 수 없습니다. 수행자는 로그인 세션이 정합니다.',
  RESTRICTION_NOT_ACTIVE:
    '이 제한은 지금 효력 중이 아니어서 복구하지 않았습니다. 활성 제한 목록을 확인해 주세요.',
  RECOVERY_CONDITION_MISSING: '복구 조건 네 가지를 모두 확인해야 복구를 준비할 수 있습니다.',
  RECOVERY_EVIDENCE_REQUIRED: '수행한 검증·증거 참조·배포 버전을 모두 입력해 주세요.',
  RECOVERY_EVIDENCE_INVALID:
    '수행한 검증과 증거 참조는 500자 이내의 한 줄 문장이어야 하고 이메일 주소를 담을 수 없습니다. 배포 버전은 영문·숫자와 . _ + - 만으로 64자 이내이고, 복구 조건은 아는 조건을 한 번씩만 보냅니다.',
  NEW_SIGNAL_SINCE_PREPARE:
    '준비 뒤 이 제한에 새 자동 정지 신호가 연결되어 복구하지 않았습니다. 다시 검증한 뒤 새로 준비해 주세요.',
  SUPPORT_CONTACT_UNCONFIGURED:
    '지원 연락처가 설정되지 않아 공지를 발행하지 않았습니다. 배포 설정을 확인해 주세요.',
  NOTICE_VERSION_CONFLICT:
    '준비 뒤 같은 사건의 공지가 먼저 발행되어 발행하지 않았습니다. 사건 목록을 확인하고 새로 준비해 주세요.',
  INCIDENT_CLOSED: '복구로 닫힌 사건이라 새 공지를 발행하지 않았습니다. 새 사건으로 발행해 주세요.',
  NEXT_UPDATE_INVALID:
    '다음 안내 시각은 지금보다 미래이고 7일 이내여야 합니다. 복구 공지에는 다음 안내 시각을 넣지 않습니다.',
  UNAUTHENTICATED: '로그인이 필요합니다. 다시 로그인해 주세요.',
  NOT_FOUND: '요청한 운영 명령을 찾지 못했습니다.',
}

const UNKNOWN_REFUSAL =
  '운영 명령을 처리하지 못했습니다. 바뀐 것은 없습니다. 잠시 후 다시 시도해 주세요.'

/** 감사 내보내기의 `TARGET_INVALID` 문장. 대상이 기간이다(서버 `OperatorController.EXPORT_TARGET_INVALID`와 같다). */
const EXPORT_TARGET_INVALID =
  '대상 기간이 올바르지 않습니다. 시작일과 종료일을 366일 이내로 지정해 주세요.'

/** 공지 발행의 거절 문장(서버 `OperatorController.NOTICE_*`와 같다). 대상이 사건과 덮는 제한이고 사유가 메시지다. */
const NOTICE_REFUSALS: Readonly<Record<string, string>> = {
  TARGET_INVALID:
    '사건이 없거나, 덮을 제한이 없거나 지금 효력 중이 아닙니다. 사건 목록과 활성 제한 목록을 확인해 주세요.',
  REASON_INVALID:
    '공지 메시지는 200자 이내의 한 줄 문장이어야 하고 이메일 주소·숫자 6자리 이상 연속·URL·경로를 담을 수 없습니다.',
  REASON_REQUIRED: '공지 메시지를 입력해 주세요.',
}

/** 복구 공지의 `TARGET_INVALID` 문장(서버 `OperatorController.NOTICE_RECOVERY_TARGET_INVALID`와 같다). */
const NOTICE_RECOVERY_TARGET_INVALID =
  '사건이 없거나 아직 효력 중인 제한이 있어 복구 공지를 낼 수 없습니다. 제한을 먼저 복구하고 사건 목록을 확인해 주세요.'

/**
 * 거절 code → 화면 문장. `TARGET_INVALID`는 command별로 다르다(Story 3.2) — 감사 내보내기는 기간 안내, 제한 command는
 * 대상 안내다. command를 모르면 제한 쪽 문장이다.
 */
export function operatorRefusalMessage(
  code: string | null,
  command?: string,
  noticeStatus?: string,
): string {
  if (code === null) {
    return UNKNOWN_REFUSAL
  }
  if (code === 'TARGET_INVALID' && command === 'audit-export') {
    return EXPORT_TARGET_INVALID
  }
  if (command === PUBLISH_NOTICE_COMMAND) {
    if (code === 'TARGET_INVALID' && noticeStatus === 'RECOVERED') {
      return NOTICE_RECOVERY_TARGET_INVALID
    }
    const notice = NOTICE_REFUSALS[code]
    if (notice !== undefined) return notice
  }
  return REFUSALS[code] ?? UNKNOWN_REFUSAL
}

/** 원장 행의 거절·실패 code → 짧은 설명. 모르는 code는 그대로 보인다(감사 사실이다). */
const LEDGER_CODES: Readonly<Record<string, string>> = {
  REAUTHENTICATION_REQUIRED: '본인 확인 필요',
  CONFIRMATION_MISMATCH: '확인 문구 불일치',
  OPERATOR_COMMAND_NOT_CONFIRMED: '확인되지 않은 확정',
  ACCESS_REVOKED: '권한 회수',
  EXECUTION_FAILED: '실행 실패',
  CANCELLED: '사용자 취소',
  SUPERSEDED: '새 준비로 대체',
  IDEMPOTENCY_CONFLICT: '요청 키 충돌',
  REASON_REQUIRED: '사유 없음',
  REASON_INVALID: '사유 형식 오류',
  UNSUPPORTED_SCOPE: '미지원 범위',
  TARGET_INVALID: '대상 형식·상태 오류',
  ALREADY_RESTRICTED: '이미 제한됨',
  ACTOR_NOT_ACCEPTED: '수행자 주장 거절',
  RESTRICTION_NOT_ACTIVE: '효력 중이 아닌 제한',
  RECOVERY_CONDITION_MISSING: '복구 조건 누락',
  RECOVERY_EVIDENCE_REQUIRED: '복구 증거 누락',
  RECOVERY_EVIDENCE_INVALID: '복구 증거 형식 오류',
  NEW_SIGNAL_SINCE_PREPARE: '준비 뒤 새 신호',
  SMOKE_FAILED: '복구 확인 실패로 다시 제한',
  SMOKE_PASSED: '복구 확인 통과',
  SUPPORT_CONTACT_UNCONFIGURED: '지원 연락처 미설정',
  NOTICE_VERSION_CONFLICT: '공지 버전 충돌',
  INCIDENT_CLOSED: '닫힌 사건',
  NEXT_UPDATE_INVALID: '다음 안내 시각 오류',
}

export function ledgerCodeLabel(code: string): string {
  return LEDGER_CODES[code] ?? code
}

export const operatorContent = {
  /** 화면 경로. 정본은 `content/auth.ts`의 `OPERATOR_PATH`다. */
  path: OPERATOR_PATH,
  mainId: 'operator-main',
  brand: 'BokriLog 운영자',
  /**
   * 일반 사용자 화면과 구분되는 제목. `복리 계획 비교`를 담고 바로 아래 고지를 인접 배치한다(UX-DR23).
   */
  heading: '복리 계획 비교 운영자 표면',
  surfaceNote:
    '운영자 allowlist에 등록된 계정에만 열리는 보호 화면입니다. 운영 명령의 요청·결정·결과가 감사 원장에 남습니다.',
  /** 첫 조회가 실패했을 때. 운영자임이 확인되지 않았으므로 운영자 표면임도 말하지 않는다. */
  loadFailed: '화면을 불러오지 못했습니다. 잠시 후 페이지를 새로 고쳐 다시 시도해 주세요.',
  /** 운영자가 아니거나 권한이 없을 때. 권한 유무를 말하지 않는다 — 없는 경로와 같은 문장이다. */
  notFound: {
    heading: '찾을 수 없는 화면입니다',
    body: '이 주소에서 열 수 있는 화면이 없습니다.',
    link: '대시보드로 이동',
  },
  status: {
    loading: '감사 기록을 불러오고 있습니다.',
    loaded: (count: number): string => `감사 기록 ${String(count)}건을 표시합니다.`,
    failed: '감사 기록을 불러오지 못했습니다. 다시 조회해 주세요.',
    preparing: '감사 내보내기를 준비하고 있습니다.',
    prepared: '확인 대화상자를 열었습니다.',
    confirming: '감사 내보내기를 실행하고 있습니다.',
    completed: (count: number): string =>
      `감사 내보내기를 마쳤습니다. ${String(count)}행을 받았습니다.`,
    cancelled: '감사 내보내기를 취소했습니다. 바뀐 것은 없습니다.',
    operationLoading: 'operation 기록을 불러오고 있습니다.',
    restrictionPreparing: '제한을 준비하고 있습니다.',
    restrictionPrepared: '제한 확인 대화상자를 열었습니다.',
    restrictionConfirming: '제한을 실행하고 있습니다.',
    restrictionCompleted: (feature: string, scope: string): string =>
      `${feature} 제한(${scope})이 효력을 가졌습니다.`,
    restrictionCancelled: '제한을 취소했습니다. 바뀐 것은 없습니다.',
    /** 운영자임을 아직 확인하지 못했다. 운영자 표면임을 말하지 않는 일반 문장이다. */
    checking: '화면을 확인하고 있습니다.',
    restrictionsLoading: '활성 제한을 불러오고 있습니다.',
    restrictionsLoaded: (count: number): string => `활성 제한 ${String(count)}건을 표시합니다.`,
    recoveryPreparing: '복구를 준비하고 있습니다.',
    recoveryPrepared: '복구 확인 대화상자를 열었습니다.',
    recoveryConfirming: '복구를 실행하고 있습니다.',
    recoveryCompleted: (feature: string, scope: string): string =>
      `${feature} 제한(${scope})을 복구했습니다. 확인 결과를 아래에 표시합니다.`,
    recoveryReverted: (feature: string, scope: string): string =>
      `${feature} 제한(${scope})을 복구했지만 확인이 어긋나 같은 범위를 다시 제한했습니다.`,
    recoveryPending: (feature: string, scope: string): string =>
      `${feature} 제한(${scope})을 복구했습니다. 복구 확인의 결말은 아직 확인 중입니다. operation 조회로 확인해 주세요.`,
    recoveryCancelled: '복구를 취소했습니다. 바뀐 것은 없습니다.',
    noticePreparing: '공지 발행을 준비하고 있습니다.',
    noticePrepared: '공지 확인 대화상자를 열었습니다.',
    noticeConfirming: '공지를 발행하고 있습니다.',
    noticeCompleted: (version: number, status: string): string =>
      `공지 v${String(version)}(${status})를 발행했습니다. 사용자 화면의 서비스 공지에 보입니다.`,
    noticeCancelled: '공지 발행을 취소했습니다. 바뀐 것은 없습니다.',
  },
  audit: {
    id: 'operator-audit',
    heading: '감사 원장',
    body: '운영 명령의 요청·거절·실패·취소·완료가 추가 전용으로 기록됩니다. 수행자는 원식별자가 아니라 digest로만 보입니다.',
    fromLabel: '시작일(KST)',
    toLabel: '종료일(KST)',
    outcomeLabel: '결과',
    outcomeAll: '전체',
    search: '조회',
    more: '더 보기',
    empty: '조건에 맞는 감사 기록이 없습니다.',
    columns: {
      recordedAt: '기록 시각',
      outcome: '결과',
      command: '명령',
      scope: '범위',
      target: '대상',
      reason: '사유',
      actor: '수행자 digest',
      operation: 'operation ID',
    },
  },
  export: {
    id: 'operator-export',
    /** 준비 버튼의 id. 대화상자가 호출점을 모를 때 포커스를 돌려줄 자리다. */
    startId: 'operator-export-start',
    heading: '감사 원장 내보내기',
    body: '위 기간의 감사 기록을 최대 10,000행까지 한 번에 받습니다. 내보내기는 그 자체가 별도 감사 사건으로 남고, 최근 본인 확인과 확인 문구 재입력이 필요합니다.',
    periodLabel: '대상 기간',
    reasonLabel: '사유(필수)',
    reasonHelp: '500자 이내 한 줄. 이메일 주소·금융 정보를 적지 않습니다.',
    start: '내보내기 준비',
    reauthLabel: '본인 확인 필요',
    result: {
      heading: '내보내기 결과',
      operation: 'operation ID',
      rows: '받은 행',
      truncated:
        '기간의 기록이 10,000행을 넘어 앞쪽 10,000행만 받았습니다. 기간을 나눠 다시 내보내 주세요.',
      download: 'JSON 파일로 저장',
    },
    /** 응답을 받지 못한 확정 뒤 대화상자를 닫았다. 취소가 아니다 — 실행됐을 수 있다. */
    uncertainClosed:
      '응답을 받지 못한 내보내기는 실행됐을 수 있습니다. 아래 operation 조회로 상태를 확인해 주세요.',
    uncertain:
      '응답을 받지 못해 실행 여부를 알 수 없습니다. 자동으로 다시 실행하지 않습니다. 같은 문구로 다시 실행하면 같은 요청으로 처리되며, 대화상자를 닫은 뒤 operation 조회로 상태를 확인할 수도 있습니다.',
  },
  dialog: {
    titleId: 'operator-export-title',
    descriptionId: 'operator-export-description',
    progressId: 'operator-export-progress',
    inputId: 'operator-export-phrase',
    title: '감사 원장을 내보낼까요?',
    target: '대상',
    scope: '범위',
    reason: '사유',
    effect:
      '이 기간의 감사 기록 전체가 이 화면으로 전달되고, 내보내기 사실이 감사 원장에 남습니다. 되돌릴 수 없습니다.',
    kept: '감사 원장의 기존 기록은 바뀌거나 지워지지 않습니다.',
    phraseLabel: '확인 문구를 그대로 입력하세요',
    cancel: '취소',
    confirm: '내보내기 실행',
    confirming: '감사 내보내기를 실행하고 있습니다.',
  },
  restriction: {
    id: 'operator-restriction',
    /** 준비 버튼의 id. 대화상자가 호출점을 모를 때 포커스를 돌려줄 자리다. */
    startId: 'operator-restriction-start',
    heading: '영향 범위 제한',
    body: '문제의 영향 범위만 수동으로 제한합니다. 신규 연결·동기화·계산 표시·광고는 서로 독립적으로 제한되고, 제한은 기존 공식 기록을 지우거나 되돌리지 않습니다. 최근 본인 확인과 확인 문구 재입력이 필요합니다.',
    featureLabel: '기능',
    scopeLabel: '범위',
    targetLabel: '대상',
    targetHelp:
      '계획·포트폴리오·연결은 ID를, 계산 정책 버전은 버전 이름을 입력합니다. 전역은 대상이 없습니다.',
    globalTarget: '전역(대상 없음)',
    reasonLabel: '제한 사유(필수)',
    reasonHelp: '500자 이내 한 줄. 이메일 주소·금융 정보를 적지 않습니다.',
    start: '제한 준비',
    reauthLabel: '본인 확인 필요',
    result: {
      heading: '제한 결과',
      operation: 'operation ID',
      restriction: '제한 ID',
      effectiveFrom: '효력 시작',
    },
    uncertainClosed:
      '응답을 받지 못한 제한은 실행됐을 수 있습니다. 아래 operation 조회와 활성 제한 목록으로 상태를 확인해 주세요.',
    uncertain:
      '응답을 받지 못해 실행 여부를 알 수 없습니다. 자동으로 다시 실행하지 않습니다. 같은 문구로 다시 실행하면 같은 요청으로 처리되며, 대화상자를 닫은 뒤 operation 조회로 상태를 확인할 수도 있습니다.',
    active: {
      id: 'operator-restrictions-active',
      heading: '활성 제한',
      body: '지금 효력 중인 제한입니다. 구체적인 범위가 먼저 보입니다. 제한은 스스로 풀리지 않습니다. 각 행의 복구로 검증 증거를 확인한 뒤 그 제한 하나만 수동으로 해제합니다.',
      empty: '효력 중인 제한이 없습니다.',
      failed: '활성 제한을 불러오지 못했습니다. 잠시 후 다시 조회해 주세요.',
      reload: '활성 제한 다시 조회',
      /** 행마다의 복구 동작(Story 3.4). */
      recover: '복구',
      erased: '삭제됨',
      noReason: '기록 없음',
      /** smoke 되돌림 행의 사유(Story 3.4). 해제 원인이 아니라 되돌림이라는 사실을 말한다. */
      reinstated: '복구 확인 실패로 같은 범위를 다시 제한(smoke 되돌림)',
      /** 상태(Story 3.3). 신호가 만들었거나 신호가 연결된 제한은 스스로 풀리지 않는다. */
      states: {
        active: '효력 중',
        verificationRequired: '수동 검증 필요',
      },
      columns: {
        origin: '출처',
        state: '상태',
        feature: '기능',
        scope: '범위',
        target: '대상',
        effectiveFrom: '효력 시작',
        reason: '사유',
        actor: '수행자 digest',
      },
    },
  },
  restrictionDialog: {
    titleId: 'operator-restriction-title',
    descriptionId: 'operator-restriction-description',
    progressId: 'operator-restriction-progress',
    inputId: 'operator-restriction-phrase',
    title: '이 범위를 제한할까요?',
    feature: '기능',
    scope: '범위',
    target: '대상',
    reason: '사유',
    impactHeading: '영향 미리보기',
    blockedStages: '막히는 단계',
    affectedPlans: (count: number): string => `영향을 받는 계획 ${String(count)}개`,
    affectedConnections: (count: number): string => `영향을 받는 연결 ${String(count)}개`,
    remainingFeatures: '계속 쓸 수 있는 기능',
    noneRemaining: '없음',
    effect:
      '확정하면 위 단계가 바로 멈추고 그 사실이 감사 원장에 남습니다. 제한은 스스로 풀리지 않고, 검증 증거를 확인한 뒤 활성 제한 목록의 복구로만 해제합니다.',
    kept: '기존 공식 기록과 포인터는 바뀌거나 지워지지 않습니다.',
    phraseLabel: '확인 문구를 그대로 입력하세요',
    cancel: '취소',
    confirm: '제한 실행',
    confirming: '제한을 실행하고 있습니다.',
  },
  recovery: {
    id: 'operator-recovery',
    /** 준비 버튼의 id. 대화상자가 호출점을 모를 때 포커스를 돌려줄 자리다. */
    startId: 'operator-recovery-start',
    heading: '제한 복구',
    body: '복구는 아래 제한 하나만 닫습니다. 더 넓은 범위의 제한과 다른 기능의 제한은 그대로 막습니다. 복구 조건 네 가지와 원인·수행한 검증·증거 참조·배포 버전이 필요하고, 최근 본인 확인과 확인 문구 재입력을 거칩니다.',
    selected: '복구할 제한',
    verificationNote:
      '자동 정지 신호가 연결된 제한입니다. 확인 대화상자에서 연결된 신호의 수와 최신 감지 시각을 확인하세요. 준비 뒤 새 신호가 연결되면 복구하지 않습니다.',
    conditionsLegend: '복구 조건(모두 필수)',
    reasonLabel: '원인(필수)',
    verificationLabel: '수행한 검증(필수)',
    evidenceLabel: '증거 참조(필수)',
    deployVersionLabel: '배포 버전(필수)',
    textHelp: '500자 이내 한 줄. 이메일 주소·금융 정보를 적지 않습니다.',
    deployVersionHelp: '영문·숫자와 . _ + - 만으로 64자 이내(예: 1.4.2+build.7).',
    start: '복구 준비',
    close: '복구 닫기',
    reauthLabel: '본인 확인 필요',
    result: {
      id: 'operator-recovery-result',
      heading: '복구 결과',
      operation: 'operation ID',
      restriction: '제한 ID',
      liftedAt: '해제 시각',
      smoke: '복구 확인',
      reopenedStages: '다시 열린 단계',
      stillBlockedStages: '여전히 막힌 단계',
      none: '없음',
    },
    uncertainClosed:
      '응답을 받지 못한 복구는 실행됐을 수 있습니다. 아래 operation 조회와 활성 제한 목록으로 상태를 확인해 주세요.',
    uncertain:
      '응답을 받지 못해 실행 여부를 알 수 없습니다. 자동으로 다시 실행하지 않습니다. 같은 문구로 다시 실행하면 같은 요청으로 처리되며, 대화상자를 닫은 뒤 operation 조회로 상태를 확인할 수도 있습니다.',
  },
  recoveryDialog: {
    titleId: 'operator-recovery-title',
    descriptionId: 'operator-recovery-description',
    progressId: 'operator-recovery-progress',
    inputId: 'operator-recovery-phrase',
    title: '이 제한을 복구할까요?',
    feature: '기능',
    scope: '범위',
    target: '대상',
    reason: '원인',
    impactHeading: '복구 미리보기',
    reopenedStages: '다시 열리는 단계',
    noneReopened: '없음(남는 제한이 계속 막습니다)',
    remaining: '남는 제한',
    noneRemaining: '없음',
    linkedSignals: (count: number): string => `연결된 자동 정지 신호 ${String(count)}건`,
    latestSignalAt: '최신 신호 감지',
    effect:
      '확정하면 이 제한 하나가 바로 닫히고 그 사실과 증거가 감사 원장에 남습니다. 닫은 뒤 확인이 어긋나면 같은 범위를 새 제한으로 다시 막습니다.',
    kept: '다른 제한·신호 기록·감사 원장의 기존 기록은 바뀌거나 지워지지 않습니다.',
    phraseLabel: '확인 문구를 그대로 입력하세요',
    cancel: '취소',
    confirm: '복구 실행',
    confirming: '복구를 실행하고 있습니다.',
  },
  notice: {
    id: 'operator-notice',
    /** 준비 버튼의 id. 대화상자가 호출점을 모를 때 포커스를 돌려줄 자리다. */
    startId: 'operator-notice-start',
    heading: '서비스 공지',
    body: '효력 중인 제한을 사용자 화면의 서비스 공지로 안내합니다. 영향을 받는 기능과 계속 쓸 수 있는 기능은 고른 제한에서 서버가 계산하고, 운영자는 원인 수준의 한 줄만 씁니다. 공지는 버전으로 쌓이며 고치거나 지울 수 없고, 제한을 풀지 않습니다.',
    contactMissing:
      '지원 연락처가 설정되지 않아 공지를 발행할 수 없습니다. 배포 설정의 지원 연락처를 먼저 채워 주세요.',
    incidentLabel: '사건',
    newIncident: '새 사건(조사 중)',
    statusLabel: '상태',
    coversLegend: '덮을 활성 제한',
    coversHelp: '진행 중인 공지는 지금 효력 중인 제한만 덮습니다.',
    /** 복구 공지는 덮는 제한을 고르지 않는다 — 직전 버전이 덮던 제한을 서버가 잇는다. */
    recoveredCovers:
      '복구 공지는 직전 버전이 덮던 제한을 그대로 잇습니다. 그 제한이 모두 복구(해제)된 뒤에만 낼 수 있습니다.',
    /** 복구 공지를 고른 사건이 덮던 제한 가운데 아직 효력 중인 것이 있다. */
    recoveredStillOpen: (count: number): string =>
      `이 사건이 덮던 제한 가운데 ${String(count)}건이 아직 효력 중이라 복구 공지를 낼 수 없습니다. 활성 제한 목록에서 먼저 복구해 주세요.`,
    /** 메시지에 화면 카피 금지 표현이 있다(`content/copyRules`). 서버도 같은 목록으로 거절한다. */
    messageForbidden:
      '공지 메시지에 화면에 쓰지 않는 표현이 있습니다. 시점·결과를 단정하거나 손익을 강조하는 표현을 빼 주세요.',
    noActive: '덮을 수 있는 활성 제한이 없습니다.',
    coverLabel: (feature: string, scope: string, target: string): string =>
      `${feature} · ${scope}${target === '' ? '' : ` · ${target}`}`,
    messageLabel: '공지 메시지(필수)',
    messageHelp:
      '200자 이내 한 줄. 이메일 주소·숫자 6자리 이상 연속·URL·경로·금융 정보를 적지 않습니다.',
    nextUpdateLabel: '다음 안내 시각(KST)',
    nextUpdateHelp:
      '서울 시각(KST)으로 입력합니다. 지금보다 미래이고 7일 이내여야 합니다. 복구 공지에는 넣지 않습니다.',
    start: '공지 발행 준비',
    reauthLabel: '본인 확인 필요',
    result: {
      id: 'operator-notice-result',
      heading: '공지 발행 결과',
      operation: 'operation ID',
      incident: '사건 ID',
      version: '버전',
      publishedAt: '발행 시각',
    },
    history: {
      id: 'operator-notice-history',
      heading: '사건 이력',
      empty: '발행한 공지가 없습니다.',
      failed: '사건 목록을 불러오지 못했습니다. 잠시 후 다시 조회해 주세요.',
      reload: '사건 목록 다시 조회',
      closed: '닫힘(복구됨)',
      open: '진행 중',
      columns: {
        incident: '사건 ID',
        startedAt: '시작 시각',
        state: '상태',
        version: '버전',
        affected: '멈춘 기능',
        continuing: '계속 쓸 수 있는 기능',
        message: '메시지',
        nextUpdateAt: '다음 안내 예정',
        recoveredAt: '복구 시각',
        covers: '덮는 제한',
        createdAt: '발행 시각',
      },
    },
    uncertainClosed:
      '응답을 받지 못한 공지 발행은 실행됐을 수 있습니다. 아래 operation 조회와 사건 이력으로 상태를 확인해 주세요.',
    uncertain:
      '응답을 받지 못해 실행 여부를 알 수 없습니다. 자동으로 다시 실행하지 않습니다. 같은 문구로 다시 실행하면 같은 요청으로 처리되며, 대화상자를 닫은 뒤 operation 조회로 상태를 확인할 수도 있습니다.',
  },
  noticeDialog: {
    titleId: 'operator-notice-title',
    descriptionId: 'operator-notice-description',
    progressId: 'operator-notice-progress',
    inputId: 'operator-notice-phrase',
    title: '이 공지를 발행할까요?',
    incident: '사건',
    newIncident: '새 사건',
    version: '버전',
    status: '상태',
    message: '메시지',
    impactHeading: '공지 미리보기(서버 계산)',
    affected: '멈춘 기능',
    continuing: '계속 쓸 수 있는 기능',
    covers: (count: number): string => `덮는 제한 ${String(count)}건`,
    nextUpdateAt: '다음 안내 예정',
    effect:
      '확정하면 이 버전이 사용자 화면의 서비스 공지에 바로 보이고 그 사실이 감사 원장에 남습니다. 발행한 버전은 고치거나 지울 수 없습니다.',
    kept: '공지는 제한을 풀거나 바꾸지 않습니다. 앞 버전은 그대로 남습니다.',
    phraseLabel: '확인 문구를 그대로 입력하세요',
    cancel: '취소',
    confirm: '공지 발행',
    confirming: '공지를 발행하고 있습니다.',
  },
  operation: {
    id: 'operator-operation',
    heading: 'operation 조회',
    body: '명령 결과가 불분명하면 operation ID로 원장의 기록을 확인합니다. 자기 operation만 조회됩니다.',
    label: 'operation ID',
    search: 'operation 조회',
    notFound: '그 operation의 기록을 찾지 못했습니다.',
    failed: 'operation 기록을 불러오지 못했습니다. 잠시 후 다시 조회해 주세요.',
  },
} as const

/** 시험과 화면이 함께 쓰는 금지 표현 검사 대상. */
export const operatorCopyStrings: readonly string[] = [
  operatorContent.heading,
  operatorContent.surfaceNote,
  operatorContent.audit.body,
  operatorContent.export.body,
  operatorContent.dialog.effect,
  operatorContent.dialog.kept,
  operatorContent.operation.body,
  operatorContent.restriction.body,
  operatorContent.restriction.active.body,
  operatorContent.restrictionDialog.effect,
  operatorContent.restrictionDialog.kept,
  operatorContent.recovery.body,
  operatorContent.recovery.verificationNote,
  operatorContent.recoveryDialog.effect,
  operatorContent.recoveryDialog.kept,
  operatorContent.notice.body,
  operatorContent.notice.contactMissing,
  operatorContent.notice.coversHelp,
  operatorContent.notice.recoveredCovers,
  operatorContent.notice.messageForbidden,
  operatorContent.noticeDialog.effect,
  operatorContent.noticeDialog.kept,
]
