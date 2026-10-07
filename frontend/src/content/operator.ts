/**
 * 보호된 운영자 화면의 카피와 경로(Story 3.1).
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

export const OPERATOR_EXPORT_ENDPOINT = '/api/operator/commands/audit-export'

export const OPERATOR_EXPORT_CONFIRM_ENDPOINT = `${OPERATOR_EXPORT_ENDPOINT}/confirm`

export const OPERATOR_EXPORT_CANCEL_ENDPOINT = `${OPERATOR_EXPORT_ENDPOINT}/cancel`

/** 감사 내보내기가 받는 유일한 범위. 서버가 다른 값을 거절한다. */
export const AUDIT_LEDGER_SCOPE = 'AUDIT_LEDGER'

/** 사유의 최대 길이. 서버 규칙(`OperatorCommandApi.MAX_REASON_CHARS`)과 같다. */
export const REASON_MAX_LENGTH = 500

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
  TARGET_INVALID: '대상 기간이 올바르지 않습니다. 시작일과 종료일을 366일 이내로 지정해 주세요.',
  ACTOR_NOT_ACCEPTED: '요청에 수행자를 담을 수 없습니다. 수행자는 로그인 세션이 정합니다.',
  UNAUTHENTICATED: '로그인이 필요합니다. 다시 로그인해 주세요.',
  NOT_FOUND: '요청한 운영 명령을 찾지 못했습니다.',
}

const UNKNOWN_REFUSAL =
  '운영 명령을 처리하지 못했습니다. 바뀐 것은 없습니다. 잠시 후 다시 시도해 주세요.'

export function operatorRefusalMessage(code: string | null): string {
  if (code === null) {
    return UNKNOWN_REFUSAL
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
  TARGET_INVALID: '대상 형식 오류',
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
]
