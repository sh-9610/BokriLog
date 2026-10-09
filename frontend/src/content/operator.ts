/**
 * 보호된 운영자 화면의 카피와 경로(Story 3.1·3.2).
 *
 * 화면과 시험이 같은 문자열을 참조한다. 이 모듈은 운영자 화면 chunk(`screens/OperatorScreen`)만 import한다 — 일반
 * 번들의 초기 화면에 운영자 제어의 카피가 섞이지 않는다. 경로 상수 `OPERATOR_PATH`는 예외로 `content/auth.ts`가
 * 소유한다(복귀 목적지 집합이 그것을 알아야 한다).
 *
 * 금지 표현(실시간·정확 보장, 수익 축하, 손실 공포)을 쓰지 않는다. 감사 데이터는 사실이지 평가가 아니다.
 */

import { OPERATOR_PATH } from './auth'

/** 운영자 표면의 API 경로. 백엔드 `OperatorGate`·`OperatorController`와 같은 값이다. */
export const OPERATOR_AUDIT_ENDPOINT = '/api/operator/audit'

export const OPERATOR_OPERATIONS_ENDPOINT = '/api/operator/operations'

export const OPERATOR_COMMANDS_ENDPOINT = '/api/operator/commands'

export const OPERATOR_EXPORT_ENDPOINT = `${OPERATOR_COMMANDS_ENDPOINT}/audit-export`

/** 효력 중인 제한의 목록(Story 3.2). command 권한이다. */
export const OPERATOR_RESTRICTIONS_ENDPOINT = '/api/operator/restrictions'

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
  UNAUTHENTICATED: '로그인이 필요합니다. 다시 로그인해 주세요.',
  NOT_FOUND: '요청한 운영 명령을 찾지 못했습니다.',
}

const UNKNOWN_REFUSAL =
  '운영 명령을 처리하지 못했습니다. 바뀐 것은 없습니다. 잠시 후 다시 시도해 주세요.'

/** 감사 내보내기의 `TARGET_INVALID` 문장. 대상이 기간이다(서버 `OperatorController.EXPORT_TARGET_INVALID`와 같다). */
const EXPORT_TARGET_INVALID =
  '대상 기간이 올바르지 않습니다. 시작일과 종료일을 366일 이내로 지정해 주세요.'

/**
 * 거절 code → 화면 문장. `TARGET_INVALID`는 command별로 다르다(Story 3.2) — 감사 내보내기는 기간 안내, 제한 command는
 * 대상 안내다. command를 모르면 제한 쪽 문장이다.
 */
export function operatorRefusalMessage(code: string | null, command?: string): string {
  if (code === null) {
    return UNKNOWN_REFUSAL
  }
  if (code === 'TARGET_INVALID' && command === 'audit-export') {
    return EXPORT_TARGET_INVALID
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
      body: '지금 효력 중인 제한입니다. 구체적인 범위가 먼저 보입니다. 해제는 이 화면에서 하지 않습니다.',
      empty: '효력 중인 제한이 없습니다.',
      failed: '활성 제한을 불러오지 못했습니다. 잠시 후 다시 조회해 주세요.',
      reload: '활성 제한 다시 조회',
      erased: '삭제됨',
      noReason: '기록 없음',
      columns: {
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
      '확정하면 위 단계가 바로 멈추고 그 사실이 감사 원장에 남습니다. 이 화면에서는 제한을 해제할 수 없습니다.',
    kept: '기존 공식 기록과 포인터는 바뀌거나 지워지지 않습니다.',
    phraseLabel: '확인 문구를 그대로 입력하세요',
    cancel: '취소',
    confirm: '제한 실행',
    confirming: '제한을 실행하고 있습니다.',
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
]
