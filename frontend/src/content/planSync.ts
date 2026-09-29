/**
 * 활성 계획의 증분 동기화 카피(Story 2.11, SYNC-UX-1.0).
 *
 * 값을 판정하지 않는다. 무엇을 했는가(동기화 중·끝남), 기존 정상값이 그대로인가, 원인과 다음 행동만
 * 말한다. 결과를 축하하거나 실패를 공포로 말하지 않으며 "실시간"·"정확"을 약속하지 않는다. 서버가
 * 보낸 코드는 아래 닫힌 맵으로만 카피에 잇고, 모르는 코드는 글자 그대로 보이지 않고 안전한 카피로
 * 접는다(`copyFor`). 재시도 가능 여부는 서버가 닫힌 표로 정하며 화면은 그 값을 옮길 뿐이다.
 */

import { copyFor } from './planResult'

/** 동기화 접수(`POST`)와 상태(`GET`). 식별자도 금융값도 받지 않는다. */
export const DASHBOARD_SYNC_PATH = '/api/dashboard/sync'

/** 탭이 이만큼 이상 숨었다가 보일 때만 재활성화 동기화를 요청한다(SYNC-UX-1.0). */
export const REACTIVATION_HIDDEN_MS = 5 * 60 * 1000

/** 진행 중인 작업의 상태를 읽는 간격. 진행 중일 때만 읽는다. */
export const SYNC_POLL_MS = 2000

/** 상태 읽기가 연달아 이만큼 실패하면 읽기를 멈춘다. 자동 재시도 루프를 만들지 않는다. */
export const SYNC_POLL_FAILURES = 3

/** 접수·상태 요청 하나의 시간 상한. 넘으면 그 요청을 끊고 실패로 센다. 끝나지 않는 응답이 조작을 막지 않게 한다. */
export const SYNC_REQUEST_TIMEOUT_MS = 10_000

/** 동기화 요청의 계기. 서버가 작업에 기록한다. */
export type SyncTrigger = 'DASHBOARD_ENTRY' | 'TAB_REACTIVATION' | 'MANUAL'

const KEPT = '기존 정상값은 그대로 유지됩니다.'

/** 끝난 작업의 닫힌 이유 → 원인과 다음 행동. 모르는 코드는 `unknownFailure`로 접는다. */
const FAILURES: Readonly<Record<string, { readonly cause: string; readonly action: string }>> = {
  NOT_PERMITTED: {
    cause: '계정 상태나 운영 제한이 동기화를 허용하지 않았습니다.',
    action: '잠시 뒤 다시 불러와 상태를 확인해 주세요.',
  },
  PLAN_CHANGED: {
    cause: '동기화하는 동안 활성 계획이 바뀌었습니다.',
    action: '다시 불러와 현재 계획을 확인해 주세요.',
  },
  MEMBERSHIP_CHANGED: {
    cause: '동기화하는 동안 포트폴리오의 연결 구성이 바뀌었습니다.',
    action: '설정에서 Bybit 연결 상태를 확인해 주세요.',
  },
  SOURCE_CHANGED: {
    cause: '동기화하는 동안 Bybit 연결이나 그 연결 정책이 바뀌었습니다.',
    action: '설정에서 Bybit 연결 상태를 확인해 주세요. 같은 요청을 다시 보내도 답은 같습니다.',
  },
  POINTER_CONFLICT: {
    cause: '동기화하는 동안 공식 결과가 다른 버전으로 바뀌었습니다.',
    action: '다시 불러온 뒤 수동 동기화를 다시 요청할 수 있습니다.',
  },
  LONG_GAP_CHOICE_REQUIRED: {
    cause: '30일 넘게 동기화하지 않아 자동으로 이어서 동기화하지 않았습니다.',
    action: '아래 장기 미동기화 안내에서 두 선택의 차이를 확인해 주세요.',
  },
  PLAN_PERIOD_ENDED: {
    cause: '계획 종료 시각이 지나 동기화하지 않았습니다.',
    action: '표시된 값은 마지막 정상 결과로 남습니다.',
  },
  COLLECTION_REVIEW_REQUIRED: {
    cause:
      '입출금 기록에 누락되거나 알 수 없는 사건, 또는 같은 ID의 서로 다른 기록이 있어 새 결과를 발행하지 않았습니다.',
    action:
      '입력 신뢰도의 원인을 확인해 주세요. 거래소 기록이 정리된 뒤 다시 동기화할 수 있습니다.',
  },
  CLASSIFICATION_REVIEW_REQUIRED: {
    cause: '외부 입출금으로 판정할 수 없는 사건이 있어 새 결과를 발행하지 않았습니다.',
    action: '입력 신뢰도의 원인을 확인해 주세요.',
  },
  CONVERSION_REQUIRED: {
    cause: '사건 시점의 USD 가격을 확인할 수 없는 입출금이 있어 새 결과를 발행하지 않았습니다.',
    action: '입력 신뢰도의 원인을 확인해 주세요.',
  },
  CONVERSION_REVIEW_REQUIRED: {
    cause: '사건 시점 가격 근거가 서로 맞지 않아 새 결과를 발행하지 않았습니다.',
    action: '입력 신뢰도의 원인을 확인해 주세요.',
  },
  APPLIED_FLOW_CHANGED: {
    cause: '이미 반영한 입출금이 다시 확인한 기록과 달라 새 결과를 발행하지 않았습니다.',
    action: '입력 신뢰도의 원인을 확인해 주세요. 반영된 값은 바뀌지 않았습니다.',
  },
  REFERENCE_TIME_UNAVAILABLE: {
    cause: 'Bybit 기준 시각을 받지 못했습니다.',
    action: '잠시 뒤 수동 동기화를 다시 요청할 수 있습니다.',
  },
  REFERENCE_TIME_INVALID: {
    cause: 'Bybit 기준 시각을 확인할 수 없었습니다.',
    action: '잠시 뒤 수동 동기화를 다시 요청할 수 있습니다.',
  },
  SOURCE_UNAVAILABLE: {
    cause: 'Bybit가 제때 응답하지 않았거나 요청을 잠시 제한했습니다.',
    action: '잠시 뒤 수동 동기화를 다시 요청할 수 있습니다.',
  },
  CONVERSION_PENDING: {
    cause: '최근 입출금의 사건 시점 가격이 아직 확정되지 않았습니다.',
    action: '몇 분 뒤 수동 동기화를 다시 요청할 수 있습니다.',
  },
  SYNC_INTERRUPTED: {
    cause: '처리가 중간에 멈춰 아무것도 발행하지 않았습니다.',
    action: '수동 동기화를 다시 요청할 수 있습니다.',
  },
  SOURCE_REJECTED: {
    cause: 'Bybit가 저장된 읽기 전용 키를 받지 않았습니다.',
    action: '설정에서 Bybit 연결 상태를 확인해 주세요. 같은 요청을 다시 보내도 답은 같습니다.',
  },
  CALCULATION_RANGE_EXCEEDED: {
    cause: '계산 값이 허용된 정밀도 범위를 벗어나 새 결과를 만들지 않았습니다.',
    action: '같은 입력으로 다시 동기화해도 답은 같습니다.',
  },
  CALCULATION_INPUT_INVALID: {
    cause: '계산 입력의 형식을 확인할 수 없어 새 결과를 만들지 않았습니다.',
    action: '같은 입력으로 다시 동기화해도 답은 같습니다.',
  },
  CALCULATION_POLICY_UNVERIFIED: {
    cause: '계산 정책의 고정 예제를 확인하지 못해 새 결과를 만들지 않았습니다.',
    action: '서비스 상태를 확인한 뒤 다시 시도해 주세요.',
  },
}

const UNKNOWN_FAILURE = {
  cause: '동기화 상태를 확인하지 못했습니다.',
  action: '다시 불러와 상태를 확인해 주세요.',
}

/** 수동 동기화를 지금 받지 않는 이유(서버의 `manual.reason`). 모르는 코드는 `unknownReason`으로 접는다. */
const UNAVAILABLE: Readonly<Record<string, string>> = {
  IN_PROGRESS: '동기화가 이미 진행 중입니다.',
  LONG_GAP: '장기 미동기화·선택 필요 상태라 수동 동기화를 받지 않습니다.',
  NO_ACTIVE_PLAN: '활성 계획이 없어 동기화할 수 없습니다.',
  SYNC_RESTRICTED: '운영 제한으로 지금은 동기화할 수 없습니다. 자동으로 다시 시작하지 않습니다.',
  SOURCE_CHANGED:
    'Bybit 연결 구성이 공식 결과의 연결과 달라 동기화할 수 없습니다. 설정에서 연결 상태를 확인해 주세요.',
  PLAN_PERIOD_ENDED: '계획 종료 시각이 지나 더 동기화하지 않습니다.',
}

export const planSyncContent = {
  /** 수동 동기화 조작. 명시적 사용자 행동에만 쓰는 primary 조작이다(DESIGN.md sync-control). */
  button: '수동 동기화',
  syncing: '동기화 중',
  syncingHelp: `최신 Bybit 값을 확인하고 있습니다. ${KEPT} 끝나면 결과가 한 번에 바뀝니다.`,
  /** 마지막 정상 시각. 조작 곁에 둔다(AC-7). */
  lastNormal: (measured: string) => `마지막 정상 시각: ${measured} (현재가치 측정 시각)`,
  lastNormalUnknown: '마지막 정상 시각을 표시할 수 없습니다.',
  /** 거래소 기록 지연 안내(AC-7, DESIGN.md). 자동 재시도를 약속하지 않는다. */
  lateEvents:
    '최근 입출금은 거래소 기록에 늦게 보일 수 있습니다. 방금 입출금했다면 잠시 뒤 다시 동기화해 주세요.',
  cooldown: (seconds: number) =>
    `방금 동기화를 요청했습니다. ${String(seconds)}초 뒤 다시 요청할 수 있습니다.`,
  unavailable: (reason: string) => copyFor(UNAVAILABLE, reason, UNKNOWN_FAILURE.cause),
  /** 끝난 작업의 요약. 오류 요약은 사용자가 누른 동기화가 끝나면 초점을 받는다. */
  summary: {
    failed: `동기화하지 못했습니다. ${KEPT}`,
    review: `검토 필요: 새 결과를 발행하지 않았습니다. ${KEPT}`,
    blocked: `동기화를 진행하지 않았습니다. ${KEPT}`,
    submitFailed: `동기화를 요청하지 못했습니다. ${KEPT}`,
    submitFailedCause: '연결을 확인하고 다시 시도해 주세요.',
    stalled: '동기화 진행 상태를 확인하지 못했습니다.',
    stalledAction: '다시 불러오기로 결과를 확인해 주세요. 작업이 끝났다면 결과가 바뀌어 있습니다.',
    causeLabel: '원인',
    actionLabel: '다음 행동',
    retryable: '다시 요청할 수 있습니다.',
    notRetryable: '같은 요청을 다시 보내도 답은 같습니다.',
    /** 검토의 영향 지표와 신뢰 축 안내(AC-9). */
    reviewImpact:
      '영향 지표: 여섯 지표 모두 마지막 정상 결과로 남습니다. 신뢰 상태의 입력 신뢰도에서 원인과 영향을 확인해 주세요.',
  },
  failure: (code: string) =>
    Object.hasOwn(FAILURES, code) ? (FAILURES[code] ?? UNKNOWN_FAILURE) : UNKNOWN_FAILURE,
  /**
   * 장기 미동기화 설명(AC-1). 두 선택의 데이터 범위·결과 차이만 말하고 조작은 두지 않는다 — `기존 계획
   * 계속`의 catch-up(Story 2.12)과 `새 계획 시작`(Story 2.13·2.15)은 이 화면에 아직 없다.
   */
  longGap: {
    heading: '장기 미동기화·선택 필요',
    intro:
      '30일 넘게 외부 자금흐름을 확인하지 않아 자동으로 동기화하지 않습니다. 선택하기 전에는 Bybit를 호출하지 않으며, 마지막 정상 결과는 읽기 전용으로 남습니다.',
    choices: [
      {
        name: '기존 계획 계속',
        text: '마지막 확인 시각의 7일 전부터(계획 시작보다 앞서지 않게) 공백 기간 전체의 입출금을 이어서 수집해(catch-up) 같은 계획으로 비교를 잇습니다. 그 앞의 입출금은 반영된 그대로 둡니다. 긴 기간을 수집하므로 시간이 걸릴 수 있습니다.',
      },
      {
        name: '새 계획 시작',
        text: '공백 기간의 입출금은 수집하지 않습니다. 기존 계획의 비교는 데이터 공백으로 계산 불가로 남고, 새 기준 현재가치로 새 계획을 시작합니다.',
      },
    ],
    notYet: '두 선택은 아직 이 화면에서 제공하지 않습니다.',
  },
  /**
   * 화면의 단일 상태 리전이 대시보드·근거·이력 문장 뒤에 넷째로 읽는 문장. 변화마다 한 번 말한다 — 진행 중
   * 상태를 읽는 동안 같은 문장이 그대로 남으므로 되풀이되지 않는다.
   */
  status: {
    syncing: `동기화 중입니다. ${KEPT}`,
    published: '동기화를 마쳤습니다. 새 공식 결과를 표시합니다.',
    failed: (cause: string) => `동기화하지 못했습니다. ${KEPT} ${cause}`,
    review: `검토 필요: 새 결과를 발행하지 않았습니다. ${KEPT}`,
    blocked: (cause: string) => `동기화를 진행하지 않았습니다. ${KEPT} ${cause}`,
    longGap: '장기 미동기화·선택 필요: 30일 넘게 동기화하지 않아 자동으로 동기화하지 않습니다.',
    cooldown: (seconds: number) =>
      `방금 동기화를 요청했습니다. ${String(seconds)}초 뒤 다시 요청할 수 있습니다.`,
    unavailable: (reason: string) => copyFor(UNAVAILABLE, reason, UNKNOWN_FAILURE.cause),
    submitFailed: `동기화를 요청하지 못했습니다. ${KEPT}`,
    stalled: '동기화 진행 상태를 확인하지 못했습니다. 다시 불러오기로 결과를 확인해 주세요.',
    denied: '이 계정의 동기화를 지금 요청할 수 없습니다.',
    signedOut: '세션이 만료되어 동기화를 요청하지 못했습니다.',
  },
  /** 조작 아래의 이유 줄. 요청이 거절된 뒤에는 버튼이 왜 동작하지 않는지 보이는 글로 말한다. */
  deniedLine: '이 계정의 동기화를 지금 요청할 수 없습니다. 화면을 새로 열어 상태를 확인해 주세요.',
  signedOutLine: '세션이 만료되어 동기화를 요청할 수 없습니다. 다시 로그인해 주세요.',
} as const
