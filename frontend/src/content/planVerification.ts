/**
 * 필요 구간 정밀 검증·장기 공백 이어가기와 늦은 사건의 새 버전 반영 카피(Story 2.12).
 *
 * 정밀 검증은 사용자가 누를 때만 접수된다. 전체 이력을 다시 읽지 않는다 — 이미 확인한 구간의 수집 증거는
 * 다시 쓰고, 마지막 확인 시각 앞 7일·설명되지 않은 저장 사건 주변·증거가 비어 있던 구간과 마지막 확인 뒤의
 * 새 구간만 다시 읽는다. 진행 중에는 마지막 정상 묶음이 그대로 보이고, 끝나면 대시보드 응답 하나로 한 번에
 * 바뀐다(2.11과 같은 원자적 갱신 계약). 그래서 이 파일의 어떤 문장도 "전체 이력을 다시 읽었다"고 말하지 않는다.
 *
 * 값을 판정하지 않는다. 사실(무엇을 언제 다시 읽었는가), 원인, 다음 행동만 말한다. "실시간"·"정확" 같은
 * 보장, 수익 축하·손실 공포, 투자 성공·실패로 읽힐 말, 스트릭·배지를 쓰지 않는다. 사용자가 풀 수 없는 원인은
 * 반복 요청을 권하지 않고 "다시 요청해도 바뀌지 않는다"고 말한다(EXPERIENCE UJ-3). 화면의 자리를 "위의"·
 * "아래의"처럼 위치로 가리키지 않고 이름으로 가리킨다(WCAG 1.3.3). 서버가 보낸 코드는 아래 닫힌 맵으로만
 * 카피에 잇고, 모르는 코드는 글자 그대로 보이지 않고 이 파일의 안전한 카피로 접는다.
 *
 * 동기화(`planSync.ts`)와 같은 작업 표를 쓰지만 문장은 목적마다 따로 둔다 — 정밀 검증이나 기존 계획 계속이
 * 끝나지 못했을 때 "다시 동기화할 수 있습니다"라고 말하지 않는다. 시각의 구분 기호와 가격 근거처럼 뜻이 같은
 * 것은 그것을 먼저 둔 파일(`calculationEvidence.ts`·`adjustmentHistory.ts`)의 값을 그대로 쓴다.
 */

import { adjustmentHistoryContent } from './adjustmentHistory'
import { planSyncContent } from './planSync'

/** 정밀 검증·기존 계획 계속 접수. 본문은 kind와 지금 보이는 묶음의 참조 세 값이며 금융값을 싣지 않는다. */
export const DASHBOARD_VERIFICATION_PATH = '/api/dashboard/verification'

/** 접수 종류. `PRECISION`은 정밀 검증, `CATCH_UP`은 장기 미동기화 뒤의 기존 계획 계속이다. */
export type VerificationKind = 'PRECISION' | 'CATCH_UP'

/** 상태 리전 문장이 공유하는 꼬리. 공식 값을 바꾸지 않은 모든 결과가 같은 말로 끝난다. */
const KEPT = '기존 공식 결과는 그대로 유지됩니다.'

/** 장기 미동기화 동안의 꼬리. 장기 미동기화 설명(`planSync.ts`의 `longGap`)과 같은 문장이다. */
const READ_ONLY = planSyncContent.longGap.readOnly

/** 작업의 종류(목적). 동기화·정밀 검증·기존 계획 계속이 같은 작업 표와 같은 엔진을 쓴다. */
const PURPOSES: Readonly<Record<string, string>> = {
  SYNC: '동기화',
  VERIFY: '정밀 검증',
  CATCH_UP: '기존 계획 계속',
}

/** 작업을 접수한 사유. 사용자 사유는 앞의 둘이고 나머지는 서비스가 접수한다. */
const JOB_REASONS: Readonly<Record<string, string>> = {
  USER_REQUEST: '사용자 요청',
  GAP_SUSPECTED: '설명되지 않은 저장 사건이 있어 늦게 기록된 입출금이나 기록 공백이 의심됨',
  UNRECOGNIZED_EVENT: '인식하지 못한 사건 유형',
  DUPLICATE_SUSPECTED: '같은 거래 기록 ID에 다른 내용',
  LONG_GAP_CONTINUATION: '장기 미동기화 뒤 기존 계획 계속',
  PLAN_END_PREPARATION: '계획 종료 준비',
  OPERATOR_INVESTIGATION: '서비스 운영 조사',
}

/** 다시 읽은 구간·다시 쓴 구간의 종류. */
const RANGE_KINDS: Readonly<Record<string, string>> = {
  UNCONFIRMED: '마지막 확인 시각 앞 7일',
  GAP: '수집 증거가 비어 있던 구간',
  ANOMALY: '설명되지 않은 저장 사건 앞뒤 24시간',
  EXPLICIT: '서비스 운영 조사 구간',
  NEW: '마지막 확인 뒤의 새 구간',
  FINAL_SWEEP: '발행 직전 최근 7일 재확인',
  REUSED: '기존 수집 증거 재사용',
}

/**
 * 대시보드 `verification.reason`(사용 불가 사유). 키 집합은 동기화의 사용 불가 사유(`planSync.ts`의 `reasons`)를
 * 그대로 따른다 — 타입이 두 집합을 하나로 묶는다. 문장은 정밀 검증과 기존 계획 계속에 함께 맞는 말이다.
 */
const UNAVAILABLE_REASONS: Readonly<Record<keyof typeof planSyncContent.reasons, string>> = {
  RESTRICTED: '운영 제한으로 지금은 받지 않습니다.',
  CONNECTION_UNAVAILABLE:
    'Bybit 연결을 확인할 수 없어 받을 수 없습니다. 설정 화면에서 연결 상태를 확인해 주세요.',
  PLAN_PERIOD_ENDED: '계획 종료 시각이 지나 이 계획은 더 받지 않습니다.',
}

/**
 * 작업이 공식 결과를 바꾸지 못하고 끝난 원인. 목적과 무관한 사실만 말하고, 다시 요청할 수 있는 원인에만
 * 목적의 꼬리를 붙인다(`codesFor`). 검토가 필요한 원인에는 붙이지 않는다 — 같은 기준 계산에서 다시 요청해도
 * 바뀌지 않는다.
 */
const CAUSES: Readonly<Record<string, string>> = {
  PROVIDER_UNAVAILABLE: 'Bybit 응답을 받지 못했습니다(시간 초과·요청 한도·일시 장애).',
  SOURCE_REJECTED:
    'Bybit가 조회를 거절했습니다. 설정 화면에서 연결의 API 키 권한과 상태를 확인해 주세요.',
  CONVERSION_REQUIRED:
    '사건 시점의 USD 가격을 확인하지 못해 환산을 보류했습니다. 사건 분이 아직 닫히지 않았을 수 있습니다.',
  CALCULATION_RANGE_EXCEEDED:
    '계산 값이 허용된 정밀도 범위를 벗어나 공식 결과를 바꾸지 않았습니다.',
  CALCULATION_INPUT_INVALID: '계산 입력의 형식을 확인할 수 없어 공식 결과를 바꾸지 않았습니다.',
  CALCULATION_POLICY_UNVERIFIED: '계산 정책의 고정 예제를 확인하지 못해 계산을 멈췄습니다.',
  SYNC_INTERRUPTED: '처리가 중간에 멈춰 아무것도 발행하지 않았습니다.',
  SYNC_FAILED: '처리를 끝내지 못해 아무것도 발행하지 않았습니다.',
  PROVIDER_TIME_UNTRUSTED: 'Bybit 기준 시각을 확인하지 못해 진행하지 않았습니다.',
  SOURCE_REQUIRES_REVIEW: 'Bybit 응답의 형식을 확인할 수 없어 입력을 검토해야 합니다.',
  CLASSIFICATION_REQUIRES_REVIEW:
    '외부 자금흐름으로 판정할 수 없는 사건이 있어 입력을 검토해야 합니다.',
  CONVERSION_REQUIRES_REVIEW:
    '사건 시점 USD 환산을 확인할 수 없는 사건이 있어 입력을 검토해야 합니다.',
  NOT_PERMITTED: '계정·연결 상태나 운영 제한이 이 작업을 허용하지 않았습니다.',
  PLAN_CHANGED: '처리하는 사이 활성 계획이 바뀌어 발행하지 않았습니다.',
  MEMBERSHIP_CHANGED: '포트폴리오의 연결 구성이 바뀌어 발행하지 않았습니다.',
  POINTER_CONFLICT: '공식 결과가 그사이 다른 버전으로 바뀌어 발행하지 않았습니다.',
  INPUTS_CHANGED: '입력이 그사이 바뀌어 발행하지 않았습니다.',
  LONG_GAP: '마지막 외부 자금흐름 확인 뒤 30일이 넘게 지나 진행하지 않았습니다.',
  PLAN_PERIOD_ENDED: '계획 종료 시각이 지나 진행하지 않았습니다.',
  SOURCE_EVENT_MISSING:
    '이미 공식 계산에 쓴 입출금 기록이 다시 읽은 구간에서 보이지 않아 입력을 검토해야 합니다.',
  HISTORY_OUT_OF_RANGE:
    '다시 읽어야 할 구간이 Bybit에서 조회할 수 있는 최근 2년보다 이르러 다시 구성할 수 없습니다.',
}

/** 잠시 뒤 다시 요청하면 달라질 수 있는 원인. */
const RETRIABLE: readonly string[] = [
  'PROVIDER_UNAVAILABLE',
  'CONVERSION_REQUIRED',
  'SYNC_INTERRUPTED',
  'SYNC_FAILED',
  'PROVIDER_TIME_UNTRUSTED',
  'INPUTS_CHANGED',
]

/** 원인 맵에 목적의 꼬리를 붙인다. 다시 요청할 수 있는 원인만 `retry`로 끝난다. */
function codesFor(retry: string): Readonly<Record<string, string>> {
  return Object.fromEntries(
    Object.entries(CAUSES).map(([code, cause]) => [
      code,
      RETRIABLE.includes(code) ? `${cause} ${retry}` : cause,
    ]),
  )
}

/** 작업이 멈춘 단계. 수집 단계의 이름만 목적마다 다르다. */
function stagesFor(collection: string): Readonly<Record<string, string>> {
  return {
    TIME: '기준 시각 확인',
    COLLECTION: collection,
    FINAL_SWEEP: '발행 직전 최근 7일 재확인',
    CLASSIFICATION: '외부 자금흐름 판정',
    CONVERSION: '사건 시점 USD 환산',
    CALCULATION: '계획 계산',
    PUBLICATION: '공식 결과 발행',
  }
}

/** 요약의 머리 이름. 두 목적이 같은 이름을 쓴다. */
const CAUSE_LABEL = '원인'
const STAGE_LABEL = '멈춘 단계'
const UNKNOWN_STAGE = '확인할 수 없는 단계'

/**
 * 작업이 확인하려던 구간의 이름. 공식 결과를 내지 못한 작업은 그 구간을 다 읽었다고 말할 수 없다 — 조회 범위
 * 밖(`HISTORY_OUT_OF_RANGE`)이면 서명 호출 전에 끝났고, 실패는 수집 중간에 멈출 수 있다.
 */
const PLANNED_TARGETS = '확인 대상 구간'

export const planVerificationContent = {
  /** 목록이 비었을 때, 값이 없을 때. 한 벌씩만 둔다. */
  none: '없습니다.',
  absent: '없음',
  /** 서버가 보낸 구간 목록을 읽을 수 없을 때. 빈 목록처럼 보이지 않는다. */
  rangesUnreadable: '구간을 확인하지 못했습니다.',
  /** 정밀 검증 버튼. 이름은 언제나 동작이다 — 진행 중임은 버튼 옆 문구와 상태 리전이 말한다. */
  action: '정밀 검증',
  /** 목적. 필요한 구간만 다시 읽는다는 사실과, 전체 이력을 다시 읽지 않는다는 사실. */
  purpose:
    '필요한 구간만 다시 읽어 늦게 기록된 입출금이나 설명되지 않은 기록이 있는지 확인합니다. 이미 확인한 구간의 수집 증거는 다시 쓰고, 전체 이력을 다시 읽지 않습니다.',
  /** 비용. 여러 번 조회하므로 동기화보다 오래 걸린다. */
  cost: 'Bybit 입출금 기록을 여러 번 조회하므로 수십 초 이상 걸릴 수 있습니다. 끝날 때까지 표시된 공식 결과는 바뀌지 않습니다.',
  running: '정밀 검증 중',
  runningHelp:
    '필요한 구간의 입출금 기록을 다시 읽고 공식 기록과 대조하고 있습니다. 끝날 때까지 표시된 공식 결과는 바뀌지 않습니다.',
  /** 예상 범위(대시보드 `verification.preview`). 접수 전 서버가 계산한 구간 계획의 요지다. */
  preview: {
    label: '예상 범위',
    /** 서버가 이 요청에 기록할 사유가 `GAP_SUSPECTED`일 때 예상 범위 앞에 붙이는 한 줄. */
    gapSuspected:
      '설명되지 않은 저장 사건이 있어 늦게 기록된 입출금이나 기록 공백이 의심됩니다. 정밀 검증은 그 사건 앞뒤를 다시 읽어 확인합니다.',
    rangeStart: '다시 읽기 시작 시각',
    rangeStartHelp: '이 시각부터 필요한 구간과 마지막 확인 뒤의 새 기록을 읽습니다.',
    unconfirmedFrom: '마지막 확인 시각 앞 7일의 시작',
    unconfirmedHelp: '마지막 확인 시각 앞 7일은 아직 늦은 기록이 들어올 수 있어 늘 다시 읽습니다.',
    anomalies: '설명되지 않은 저장 사건',
    anomalyCount: (count: number) => `${String(count)}건`,
    anomalyHelp: '각 사건 시각 앞뒤 24시간을 다시 읽습니다.',
    reusedUntil: '기존 수집 증거 재사용 끝',
    reusedHelp: '이 시각 앞의 나머지 구간은 이미 확인한 수집 증거를 다시 쓰고 다시 읽지 않습니다.',
    noReuse: '다시 쓰는 기존 수집 증거가 없습니다.',
  },
  /** 정밀 검증을 다시 받을 때까지 남은 시간. 서버가 준 상대 초에서 이 기기가 센다. */
  cooldown: (seconds: number) => `${String(seconds)}초 뒤 다시 정밀 검증을 요청할 수 있습니다.`,
  unavailableLabel: '지금은 정밀 검증을 요청할 수 없습니다',
  reasons: UNAVAILABLE_REASONS,
  unknownReason:
    '정밀 검증을 요청할 수 있는지 확인하지 못했습니다. 다시 불러오기로 다시 확인할 수 있습니다.',
  /**
   * 장기 미동기화에서는 정밀 검증을 받지 않는다. 그동안 이 영역은 목적·비용 대신 이 한 문장만 둔다. 이어 가는
   * 길은 동기화 자리의 `기존 계획 계속` 버튼이며, 그 버튼이 막혀 있으면 버튼 곁에 이유가 보인다.
   */
  longGap: {
    continuation:
      '장기 미동기화 상태에서는 정밀 검증을 받지 않습니다. 공백 기간은 기존 계획 계속 버튼으로 이어 가도록 요청할 수 있고, 지금 요청할 수 없으면 그 버튼 곁에 이유가 보입니다.',
    /** 같은 기준 계산에서 기존 계획 계속도 쓸모없을 때(검토로 끝난 작업이 결과를 보류 중). */
    held: '장기 미동기화 상태에서는 정밀 검증을 받지 않습니다.',
  },
  /** 버튼이 다른 작업 때문에 막혔을 때의 이유(`aria-describedby`). */
  inProgressOther:
    '다른 동기화 작업이 진행 중이라 정밀 검증을 요청할 수 없습니다. 끝나면 이 화면이 결과를 표시합니다.',
  inProgressElsewhere:
    '다른 곳에서 시작한 정밀 검증이 진행 중입니다. 끝날 때까지 새로 요청할 수 없고, 끝나면 이 화면이 결과를 표시합니다.',
  inProgressUnfollowed:
    '작업이 아직 진행 중이라 정밀 검증을 요청할 수 없습니다. 다시 불러오기로 진행 상태를 확인할 수 있습니다.',
  /**
   * 재요청이 쓸모없을 때 버튼 대신 두는 안내(대시보드 `NOT_USEFUL`). 같은 기준 계산의 정밀 검증이 입력 검토가
   * 필요한 상태로 끝났다. 사용자가 풀 수 없는 원인이라 반복 요청을 권하지 않는다. 입력 신뢰도 축의 안내
   * (`held`)와 문장이 다르다 — 이쪽은 요청을 받지 않는 이유를, 저쪽은 결과가 보류된 상태를 말한다.
   */
  notUseful: {
    label: '정밀 검증을 다시 요청해도 바뀌지 않습니다',
    text: '같은 기준 계산의 정밀 검증이 이미 입력 검토가 필요한 상태로 끝났습니다. 원인과 확인 대상 구간은 신뢰 상태의 입력 신뢰도에 있습니다.',
  },
  /** 마지막 정밀 검증의 결과(대시보드 `verification.latest`). 실패로 읽힐 말 대신 무엇을 확인했는지를 말한다. */
  latest: {
    heading: '마지막 정밀 검증',
    statuses: {
      SUCCEEDED: '정밀 검증 완료',
      FAILED: '정밀 검증을 끝내지 못했습니다',
      NEEDS_REVIEW: '정밀 검증 입력 검토 필요',
      BLOCKED: '정밀 검증을 마치지 못했습니다',
    } as Record<string, string>,
    unknownStatus: '정밀 검증 상태를 확인하지 못했습니다',
    kept: KEPT,
    causeLabel: CAUSE_LABEL,
    codes: codesFor('잠시 뒤 다시 정밀 검증을 요청할 수 있습니다.'),
    unknownCode: '정밀 검증을 끝내지 못한 원인을 확인하지 못했습니다.',
    stageLabel: STAGE_LABEL,
    stages: stagesFor('필요한 구간의 입출금 기록 다시 읽기'),
    unknownStage: UNKNOWN_STAGE,
    reasonLabel: '사유',
    /** 공식 결과를 낸 검증의 구간: 실제로 다시 읽었다. */
    targetsLabel: '다시 읽은 구간',
    /** 공식 결과를 내지 못한 검증의 구간: 확인하려던 구간일 뿐 다 읽었다고 말하지 않는다. */
    plannedTargetsLabel: PLANNED_TARGETS,
    reusedLabel: '다시 쓴 기존 수집 증거',
    /** 공식 결과를 내지 못한 검증의 재사용 구간: 계획했을 뿐 그 증거로 결과를 내지 않았다. */
    plannedReusedLabel: '재사용하려던 기존 수집 증거',
    countsLabel: '대조 결과',
    finishedLabel: '종료 시각',
    /** 대조 다섯 갈래. 원천 기록을 고치지 않고, 무엇이 관측됐는지만 센다. */
    counts: {
      new: '새로 확인된 사건',
      same: '이미 반영된 사건',
      missing: '다시 보이지 않은 공식 사건',
      superseded: '다시 보이지 않아 제외한 사건',
      conflict: '같은 ID에 다른 내용',
    },
    countValue: (count: number) => `${String(count)}건`,
  },
  purposes: PURPOSES,
  unknownPurpose: '확인할 수 없는 작업',
  jobReasons: JOB_REASONS,
  unknownJobReason: '확인할 수 없는 사유',
  rangeKinds: RANGE_KINDS,
  unknownRangeKind: '확인할 수 없는 구간',
  /**
   * 입력 신뢰도가 검토로 끝난 작업에 보류됐을 때(`VERIFICATION_REVIEW_REQUIRED`·`HISTORY_OUT_OF_RANGE`) 입력
   * 신뢰도 축에 붙이는 안내와 확인 대상 구간. 안내는 보류를 일으킨 작업의 목적마다 다르다 — 기존 계획 계속이
   * 보류를 일으켰으면 정밀 검증을 다시 요청하라고 말하지 않는다.
   */
  held: {
    /** 보류를 일으킨 작업이 확인하려던 구간. 조회 범위 밖이면 실제로 읽지 못했으므로 "읽은"이라 하지 않는다. */
    targetsLabel: PLANNED_TARGETS,
    notices: {
      VERIFY:
        '같은 기준 계산 동안에는 정밀 검증을 다시 요청해도 이 상태가 바뀌지 않습니다. 표시된 값은 마지막 정상 결과입니다.',
      CATCH_UP:
        '같은 기준 계산 동안에는 기존 계획 계속을 다시 요청해도 이 상태가 바뀌지 않습니다. 표시된 값은 마지막 정상 결과입니다.',
    } as Record<string, string>,
    notice:
      '같은 기준 계산 동안에는 다시 요청해도 이 상태가 바뀌지 않습니다. 표시된 값은 마지막 정상 결과입니다.',
  },
  /** 접수 자체가 성립하지 않았거나 결과를 끝까지 따라가지 못했을 때의 요약(이 화면이 판정한 것). */
  local: {
    cooldown: {
      label: '아직 다시 정밀 검증을 요청할 수 없습니다',
      text: `최근 요청이 있어 잠시 뒤에 다시 요청할 수 있습니다. ${KEPT}`,
    },
    unavailable: {
      label: '지금은 정밀 검증을 요청할 수 없습니다',
      text: `서버가 지금은 정밀 검증을 받지 않았습니다. ${KEPT}`,
    },
    error: {
      label: '정밀 검증 요청을 처리하지 못했습니다',
      text: `${KEPT} 잠시 뒤 다시 요청할 수 있습니다.`,
    },
    stalled: {
      label: '정밀 검증 결과를 아직 확인하지 못했습니다',
      text: `이 화면이 진행 상태를 더 확인하지 않습니다. 다시 불러오기로 결과를 확인해 주세요. ${KEPT}`,
    },
    notUseful: {
      label: '정밀 검증을 다시 요청해도 바뀌지 않습니다',
      text: `같은 기준 계산의 정밀 검증이 이미 입력 검토가 필요한 상태로 끝나 새로 시작하지 않았습니다. ${KEPT}`,
    },
    stale: {
      label: '표시된 결과가 현재 공식 결과와 달라 시작하지 않았습니다',
      text: '이 화면이 현재 공식 결과를 다시 불러옵니다. 필요하면 그 결과에서 다시 요청할 수 있습니다.',
    },
    busy: {
      label: '다른 작업이 진행 중이라 시작하지 않았습니다',
      text: `진행 중인 작업이 끝나면 이 화면이 결과를 표시합니다. ${KEPT}`,
    },
    /** 서버가 이 요청과 맞지 않는 이유로 거절했을 때. 화면이 보는 상태가 지난 것이라 한 번 다시 불러온다. */
    refused: {
      label: '정밀 검증 요청을 받지 않았습니다',
      text: `서버가 지금 상태에서는 이 요청을 받지 않았습니다. 이 화면이 현재 상태를 다시 불러옵니다. ${KEPT}`,
    },
  },
  /**
   * 화면의 단일 상태 리전이 대시보드 문장 자리에서 읽는 정밀 검증 문장. 한 정밀 검증의 시작과 끝에 한 번씩만
   * 바뀐다 — 진행 중 폴링은 이 문장도, "다시 불러오는 중"도 만들지 않는다.
   */
  status: {
    running: '정밀 검증 중입니다. 끝날 때까지 표시된 공식 결과는 바뀌지 않습니다.',
    /**
     * @param change 늦게 확인된 변경의 문장 또는 필요 구간 검증 완료 문장. 없으면 `null`.
     * @param reliability 입력 신뢰도가 확인됨이 아니면 그 상태 이름, 아니면 `null`.
     */
    succeeded: (change: string | null, reliability: string | null, basis: string) =>
      [
        '정밀 검증을 마쳐 새 공식 결과를 표시합니다.',
        change,
        reliability === null
          ? `계산 기준 시각은 ${basis}입니다.`
          : `입력 신뢰도는 ${reliability}이며 계산 기준 시각은 ${basis}입니다.`,
      ]
        .filter((part) => part !== null)
        .join(' '),
    failed: `정밀 검증을 끝내지 못했습니다. ${KEPT}`,
    review: `정밀 검증에서 검토가 필요한 입력이 나와 공식 결과를 바꾸지 않았습니다. ${KEPT}`,
    blocked: `정밀 검증을 마치지 못해 공식 결과를 바꾸지 않았습니다. ${KEPT}`,
    cooldown: `최근 요청이 있어 아직 정밀 검증을 다시 요청할 수 없습니다. ${KEPT}`,
    unavailable: `지금은 정밀 검증을 받지 않습니다. ${KEPT}`,
    error: `정밀 검증 요청을 처리하지 못했습니다. ${KEPT}`,
    stalled: '정밀 검증 결과를 아직 확인하지 못했습니다. 다시 불러오기로 확인할 수 있습니다.',
    notUseful: `같은 기준 계산에서 정밀 검증을 다시 요청해도 결과가 바뀌지 않아 시작하지 않았습니다. ${KEPT}`,
    stale: '표시된 결과가 현재 공식 결과와 달라 정밀 검증을 시작하지 않았습니다.',
    busy: `다른 작업이 진행 중이라 정밀 검증을 시작하지 않았습니다. ${KEPT}`,
    refused: `서버가 지금 상태에서는 정밀 검증 요청을 받지 않았습니다. ${KEPT}`,
    /**
     * 표시된 결과가 지난 것이라 시작하지 않은 요청 뒤, 이 화면이 스스로 다시 불러온 현재 공식 결과로 바꿨을 때
     * 한 번. 정밀 검증과 기존 계획 계속이 같은 문장을 쓴다.
     */
    refreshed: (reliability: string | null, basis: string) =>
      reliability === null
        ? `현재 공식 결과를 다시 불러와 표시합니다. 계산 기준 시각은 ${basis}입니다.`
        : `현재 공식 결과를 다시 불러와 표시합니다. 입력 신뢰도는 ${reliability}이며 계산 기준 시각은 ${basis}입니다.`,
  },
  /**
   * 장기 미동기화 뒤 `기존 계획 계속`의 진행·결과(버튼과 범위·비용 설명은 `planSync.ts`의 `longGap`이 소유한다).
   * 공백 기간을 7일 창으로 이어 읽고 발행 직전 최근 7일을 한 번 더 읽은 뒤 한 번 발행한다.
   */
  catchUp: {
    running: '기존 계획 계속 중',
    runningHelp:
      '공백 기간의 입출금 기록을 7일씩 차례로 읽고 있습니다. 끝날 때까지 표시된 결과는 읽기 전용으로 유지됩니다.',
    cooldown: (seconds: number) =>
      `${String(seconds)}초 뒤 다시 기존 계획 계속을 요청할 수 있습니다.`,
    unavailableLabel: '지금은 기존 계획 계속을 요청할 수 없습니다',
    unknownReason:
      '기존 계획 계속을 요청할 수 있는지 확인하지 못했습니다. 다시 불러오기로 다시 확인할 수 있습니다.',
    inProgressOther:
      '다른 동기화 작업이 진행 중이라 기존 계획 계속을 요청할 수 없습니다. 끝나면 이 화면이 결과를 표시합니다.',
    inProgressElsewhere:
      '다른 곳에서 시작한 기존 계획 계속이 진행 중입니다. 끝날 때까지 새로 요청할 수 없고, 끝나면 이 화면이 결과를 표시합니다.',
    inProgressUnfollowed:
      '작업이 아직 진행 중이라 기존 계획 계속을 요청할 수 없습니다. 다시 불러오기로 진행 상태를 확인할 수 있습니다.',
    /**
     * 같은 기준 계산에서 기존 계획 계속이나 정밀 검증이 입력 검토가 필요한 상태로 끝나 결과를 보류하고 있을 때,
     * `기존 계획 계속` 버튼 대신 두는 안내(장기 미동기화 + 대시보드 `NOT_USEFUL`).
     */
    notUseful: {
      label: '기존 계획 계속을 다시 요청해도 바뀌지 않습니다',
      text: '같은 기준 계산에서 입력 검토가 필요한 상태로 끝난 작업이 결과를 보류하고 있습니다. 원인과 확인 대상 구간은 신뢰 상태의 입력 신뢰도에 있습니다.',
    },
    summary: {
      statuses: {
        FAILED: '기존 계획 계속을 끝내지 못했습니다',
        NEEDS_REVIEW: '기존 계획 계속 입력 검토 필요',
        BLOCKED: '기존 계획 계속을 마치지 못했습니다',
      } as Record<string, string>,
      unknownStatus: '기존 계획 계속 상태를 확인하지 못했습니다',
      kept: READ_ONLY,
      causeLabel: CAUSE_LABEL,
      codes: codesFor('잠시 뒤 다시 기존 계획 계속을 요청할 수 있습니다.'),
      unknownCode: '기존 계획 계속을 끝내지 못한 원인을 확인하지 못했습니다.',
      stageLabel: STAGE_LABEL,
      stages: stagesFor('공백 기간의 입출금 기록 수집'),
      unknownStage: UNKNOWN_STAGE,
    },
    local: {
      cooldown: {
        label: '아직 다시 기존 계획 계속을 요청할 수 없습니다',
        text: `최근 요청이 있어 잠시 뒤에 다시 요청할 수 있습니다. ${READ_ONLY}`,
      },
      unavailable: {
        label: '지금은 기존 계획 계속을 요청할 수 없습니다',
        text: `서버가 지금은 기존 계획 계속을 받지 않았습니다. ${READ_ONLY}`,
      },
      error: {
        label: '기존 계획 계속 요청을 처리하지 못했습니다',
        text: `${READ_ONLY} 잠시 뒤 다시 요청할 수 있습니다.`,
      },
      stalled: {
        label: '기존 계획 계속 결과를 아직 확인하지 못했습니다',
        text: '이 화면이 진행 상태를 더 확인하지 않습니다. 다시 불러오기로 결과를 확인해 주세요.',
      },
      notLongGap: {
        label: '장기 미동기화가 아니어서 시작하지 않았습니다',
        text: `마지막 확인 뒤 30일이 지나지 않아 기존 계획 계속이 필요하지 않습니다. 동기화로 공식 결과를 새로 확인할 수 있습니다. ${KEPT}`,
      },
      notUseful: {
        label: '기존 계획 계속을 다시 요청해도 바뀌지 않습니다',
        text: `같은 기준 계산에서 입력 검토가 필요한 상태로 끝난 작업이 있어 새로 시작하지 않았습니다. ${READ_ONLY}`,
      },
      stale: {
        label: '표시된 결과가 현재 공식 결과와 달라 시작하지 않았습니다',
        text: '이 화면이 현재 공식 결과를 다시 불러옵니다. 필요하면 그 결과에서 다시 요청할 수 있습니다.',
      },
      busy: {
        label: '다른 작업이 진행 중이라 시작하지 않았습니다',
        text: `진행 중인 작업이 끝나면 이 화면이 결과를 표시합니다. ${READ_ONLY}`,
      },
      refused: {
        label: '기존 계획 계속 요청을 받지 않았습니다',
        text: `서버가 지금 상태에서는 이 요청을 받지 않았습니다. 이 화면이 현재 상태를 다시 불러옵니다. ${READ_ONLY}`,
      },
    },
    status: {
      running: '기존 계획 계속 중입니다. 끝날 때까지 표시된 결과는 읽기 전용으로 유지됩니다.',
      succeeded: (reliability: string | null, basis: string) =>
        reliability === null
          ? `기존 계획 계속을 마쳐 공백 기간을 반영한 새 공식 결과를 표시합니다. 계산 기준 시각은 ${basis}입니다.`
          : `기존 계획 계속을 마쳐 공백 기간을 반영한 새 공식 결과를 표시합니다. 입력 신뢰도는 ${reliability}이며 계산 기준 시각은 ${basis}입니다.`,
      failed: `기존 계획 계속을 끝내지 못했습니다. ${READ_ONLY}`,
      review: `기존 계획 계속에서 검토가 필요한 입력이 나와 공식 결과를 바꾸지 않았습니다. ${READ_ONLY}`,
      blocked: `기존 계획 계속을 마치지 못해 공식 결과를 바꾸지 않았습니다. ${READ_ONLY}`,
      cooldown: `최근 요청이 있어 아직 기존 계획 계속을 다시 요청할 수 없습니다. ${READ_ONLY}`,
      unavailable: `지금은 기존 계획 계속을 받지 않습니다. ${READ_ONLY}`,
      error: `기존 계획 계속 요청을 처리하지 못했습니다. ${READ_ONLY}`,
      stalled:
        '기존 계획 계속 결과를 아직 확인하지 못했습니다. 다시 불러오기로 확인할 수 있습니다.',
      notLongGap: `장기 미동기화가 아니어서 기존 계획 계속을 시작하지 않았습니다. ${KEPT}`,
      notUseful: `같은 기준 계산에서 기존 계획 계속을 다시 요청해도 결과가 바뀌지 않아 시작하지 않았습니다. ${READ_ONLY}`,
      stale: '표시된 결과가 현재 공식 결과와 달라 기존 계획 계속을 시작하지 않았습니다.',
      busy: `다른 작업이 진행 중이라 기존 계획 계속을 시작하지 않았습니다. ${READ_ONLY}`,
      refused: `서버가 지금 상태에서는 기존 계획 계속 요청을 받지 않았습니다. ${READ_ONLY}`,
    },
  },
  /**
   * 현재 공식 버전의 결과 표지(대시보드 `change`). 늦게 확인된 변경을 담았으면 그 수와 영향 시작을, 변경 없는
   * 검증이면 검증 구간·재사용 구간·시각을 보인다. 전체 이력을 다시 읽었다고 말하지 않는다. 표지의 첫 줄이 그
   * 묶음의 이름이다.
   */
  change: {
    /**
     * 표지의 앞머리. 영향 시작 시각이 있으면 `impactStart`와 그 시각이 뒤따른다. 늦게 확인된 입출금이 없이
     * 값만 바뀐 버전(가격 정정·제외)은 건수 대신 변경 사실만 말한다 — 0건이라고 말하면 아무것도 바뀌지 않은
     * 것처럼 읽힌다.
     */
    corrected: (count: number) =>
      count > 0 ? `늦게 확인된 입출금 ${String(count)}건 반영` : '입출금 변경 반영',
    impactStart: '영향 시작',
    correctedHelp:
      '이전 공식 버전은 그대로 남아 있고, 이 버전이 그 입출금을 반영한 새 공식 버전입니다. 무엇이 바뀌었는지는 계산 근거의 변경 근거에서 볼 수 있습니다.',
    verified: '필요 구간 검증 완료',
    verifiedHelp:
      '다시 읽은 구간에서 입출금 변경은 없었습니다. 계산 기준 시각과 현재가치는 이번에 새로 확인한 값입니다.',
    purposeLabel: '작업',
    reasonLabel: '사유',
    targetsLabel: '다시 읽은 구간',
    reusedLabel: '다시 쓴 기존 수집 증거',
    verifiedAtLabel: '검증 시각',
    /** 상태 리전 문장에 끼우는 짧은 형태. */
    correctedSentence: (count: number) =>
      count > 0
        ? `늦게 확인된 입출금 ${String(count)}건을 반영했습니다.`
        : '입출금 변경을 반영했습니다.',
    verifiedSentence: '필요 구간 검증을 마쳤고 입출금 변경은 없었습니다.',
  },
  /** 계산 근거의 `변경 근거` 부분(근거 `change`). 직전 공식 버전과 비교해 무엇이 왜 바뀌었는지를 보인다. */
  evidence: {
    title: '변경 근거',
    kinds: {
      CORRECTED: '늦게 확인되거나 값이 바뀐 입출금을 반영한 버전입니다.',
      VERIFIED_NO_CHANGE: '필요 구간을 다시 읽어 검증했고 입출금 변경은 없는 버전입니다.',
      NONE: '직전 공식 버전과 비교해 늦게 확인된 변경이 없는 버전입니다.',
    } as Record<string, string>,
    unknownKind: '변경 종류를 확인할 수 없습니다.',
    first: '첫 공식 버전이라 비교할 직전 버전이 없습니다.',
    purpose: '만든 작업',
    reason: '사유',
    targets: '다시 읽은 구간',
    reused: '다시 쓴 기존 수집 증거',
    previous: '직전 공식 버전',
    previousOrder: '발행 순서',
    previousCalculation: '계산 ID',
    previousBasis: '직전 계산 기준 시각',
    firstAffected: '영향 시작 시각',
    firstAffectedHelp:
      '늦게 확인됐거나 값이 바뀐 입출금 가운데 가장 이른 사건 시각입니다. 이 시각부터의 계획선이 변경을 반영합니다.',
    metrics: '지표 이전 값 → 새 값',
    /** 두 버전의 기준 시각이 함께 바뀌었다는 사실. 가상의 세 번째 계산값을 만들지 않는다. */
    metricsHelp: (previous: string, next: string) =>
      `두 버전은 계산 기준 시각도 다릅니다(${previous} → ${next}). 입출금 변경이 없어도 기준 시각과 현재가치가 바뀌어 값이 달라질 수 있습니다.`,
    previousValue: '이전 값',
    newValue: '새 값',
    events: '변경된 입출금',
    noEvents: '변경된 입출금이 없습니다.',
    eventKinds: {
      ADDED: '새로 반영',
      REMOVED: '반영에서 제외',
      VALUE_CHANGED: '값 변경',
    } as Record<string, string>,
    unknownEventKind: '확인할 수 없는 변경',
    /** 변경 사건마다 한 문장. 늦게 확인된 것은 새로 반영한 사건만이다. */
    eventNotes: {
      lateAdded: '늦게 확인돼 이 버전에서 새로 반영한 사건입니다.',
      added: '마지막 확인 뒤의 새 사건입니다.',
      valueChanged: 'USD 가치나 추정 여부가 바뀐 사건입니다.',
      removed: '이 버전의 반영에서 빠진 사건입니다.',
      unknown: '직전 공식 버전과 달라진 사건입니다.',
    },
    eventTitle: (kind: string, sourceId: string) => `${kind} · ${sourceId}`,
    sourceId: adjustmentHistoryContent.detail.sourceId,
    occurredAt: '사건 시각',
    /** 사건의 유형. 조정 이력의 유형 이름(외부 입금·외부 출금)을 그대로 쓴다. */
    transactionType: adjustmentHistoryContent.row.type,
    transactionTypes: {
      TRANSFER_IN: adjustmentHistoryContent.filter.options.IN,
      TRANSFER_OUT: adjustmentHistoryContent.filter.options.OUT,
    } as Record<string, string>,
    unknownTransactionType: '확인할 수 없는 유형',
    usd: 'USD 가치 (이전 → 새)',
    estimated: '추정 여부 (이전 → 새)',
    evidenceType: '가격 근거',
    discoveredAt: adjustmentHistoryContent.detail.discoveredAt,
    discoveredHelp: adjustmentHistoryContent.detail.discoveredHelp,
    appliedAt: '반영 시각',
    appliedHelp: '이 변경을 담은 공식 버전이 발행된 시각입니다.',
    estimatedValues: adjustmentHistoryContent.estimated,
    evidenceTypes: adjustmentHistoryContent.evidenceTypes,
    unknownEvidenceType: adjustmentHistoryContent.unknownEvidenceType,
    arrow: '→',
  },
  /** 조정 이력 행의 버전 변경 표지(이력 행 `versionChange`). 색만으로 말하지 않고 문장으로 말한다. */
  history: {
    lateAdded: '늦게 확인된 표지: 이 버전에서 늦게 확인돼 새로 반영한 입출금입니다.',
    added: '새로 반영한 표지: 이 버전에서 새로 반영한 입출금입니다.',
    valueChanged: '값 변경 표지: 이 버전에서 USD 가치나 추정 여부가 바뀌었습니다.',
    unknown: '버전 변경 표지: 이 버전에서 바뀐 입출금입니다.',
    previousValue: '직전 공식 버전의 값',
    discoveredAt: adjustmentHistoryContent.detail.discoveredAt,
    appliedAt: '반영 시각',
  },
} as const

/** 화면에 나올 수 있는 정밀 검증 카피 전부. 시험이 금지어를 한 번에 검사한다. */
export const planVerificationCopyStrings: readonly string[] = [
  planVerificationContent.none,
  planVerificationContent.absent,
  planVerificationContent.rangesUnreadable,
  planVerificationContent.action,
  planVerificationContent.purpose,
  planVerificationContent.cost,
  planVerificationContent.running,
  planVerificationContent.runningHelp,
  ...Object.values(planVerificationContent.preview).map((entry) =>
    typeof entry === 'function' ? entry(2) : entry,
  ),
  planVerificationContent.cooldown(30),
  planVerificationContent.unavailableLabel,
  ...Object.values(planVerificationContent.reasons),
  planVerificationContent.unknownReason,
  ...Object.values(planVerificationContent.longGap),
  planVerificationContent.inProgressOther,
  planVerificationContent.inProgressElsewhere,
  planVerificationContent.inProgressUnfollowed,
  planVerificationContent.notUseful.label,
  planVerificationContent.notUseful.text,
  planVerificationContent.latest.heading,
  ...Object.values(planVerificationContent.latest.statuses),
  planVerificationContent.latest.unknownStatus,
  planVerificationContent.latest.kept,
  planVerificationContent.latest.causeLabel,
  ...Object.values(planVerificationContent.latest.codes),
  planVerificationContent.latest.unknownCode,
  planVerificationContent.latest.stageLabel,
  ...Object.values(planVerificationContent.latest.stages),
  planVerificationContent.latest.unknownStage,
  planVerificationContent.latest.reasonLabel,
  planVerificationContent.latest.targetsLabel,
  planVerificationContent.latest.plannedTargetsLabel,
  planVerificationContent.latest.reusedLabel,
  planVerificationContent.latest.countsLabel,
  planVerificationContent.latest.finishedLabel,
  ...Object.values(planVerificationContent.latest.counts),
  planVerificationContent.latest.countValue(3),
  ...Object.values(planVerificationContent.purposes),
  planVerificationContent.unknownPurpose,
  ...Object.values(planVerificationContent.jobReasons),
  planVerificationContent.unknownJobReason,
  ...Object.values(planVerificationContent.rangeKinds),
  planVerificationContent.unknownRangeKind,
  planVerificationContent.held.targetsLabel,
  ...Object.values(planVerificationContent.held.notices),
  planVerificationContent.held.notice,
  ...Object.values(planVerificationContent.local).flatMap((entry) => [entry.label, entry.text]),
  planVerificationContent.status.running,
  planVerificationContent.status.succeeded(null, null, '2026-09-29 10:00'),
  planVerificationContent.status.succeeded(
    planVerificationContent.change.correctedSentence(1),
    '검토 필요',
    '2026-09-29 10:00',
  ),
  planVerificationContent.status.failed,
  planVerificationContent.status.review,
  planVerificationContent.status.blocked,
  planVerificationContent.status.cooldown,
  planVerificationContent.status.unavailable,
  planVerificationContent.status.error,
  planVerificationContent.status.stalled,
  planVerificationContent.status.notUseful,
  planVerificationContent.status.stale,
  planVerificationContent.status.busy,
  planVerificationContent.status.refused,
  planVerificationContent.status.refreshed(null, '2026-09-29 10:00'),
  planVerificationContent.status.refreshed('검토 필요', '2026-09-29 10:00'),
  planVerificationContent.catchUp.running,
  planVerificationContent.catchUp.runningHelp,
  planVerificationContent.catchUp.cooldown(30),
  planVerificationContent.catchUp.unavailableLabel,
  planVerificationContent.catchUp.unknownReason,
  planVerificationContent.catchUp.inProgressOther,
  planVerificationContent.catchUp.inProgressElsewhere,
  planVerificationContent.catchUp.inProgressUnfollowed,
  planVerificationContent.catchUp.notUseful.label,
  planVerificationContent.catchUp.notUseful.text,
  ...Object.values(planVerificationContent.catchUp.summary.statuses),
  planVerificationContent.catchUp.summary.unknownStatus,
  planVerificationContent.catchUp.summary.kept,
  planVerificationContent.catchUp.summary.causeLabel,
  ...Object.values(planVerificationContent.catchUp.summary.codes),
  planVerificationContent.catchUp.summary.unknownCode,
  planVerificationContent.catchUp.summary.stageLabel,
  ...Object.values(planVerificationContent.catchUp.summary.stages),
  planVerificationContent.catchUp.summary.unknownStage,
  ...Object.values(planVerificationContent.catchUp.local).flatMap((entry) => [
    entry.label,
    entry.text,
  ]),
  planVerificationContent.catchUp.status.running,
  planVerificationContent.catchUp.status.succeeded(null, '2026-09-29 10:00'),
  planVerificationContent.catchUp.status.succeeded('검토 필요', '2026-09-29 10:00'),
  planVerificationContent.catchUp.status.failed,
  planVerificationContent.catchUp.status.review,
  planVerificationContent.catchUp.status.blocked,
  planVerificationContent.catchUp.status.cooldown,
  planVerificationContent.catchUp.status.unavailable,
  planVerificationContent.catchUp.status.error,
  planVerificationContent.catchUp.status.stalled,
  planVerificationContent.catchUp.status.notLongGap,
  planVerificationContent.catchUp.status.notUseful,
  planVerificationContent.catchUp.status.stale,
  planVerificationContent.catchUp.status.busy,
  planVerificationContent.catchUp.status.refused,
  planVerificationContent.change.corrected(1),
  planVerificationContent.change.corrected(0),
  planVerificationContent.change.correctedSentence(0),
  planVerificationContent.change.impactStart,
  planVerificationContent.change.correctedHelp,
  planVerificationContent.change.verified,
  planVerificationContent.change.verifiedHelp,
  planVerificationContent.change.purposeLabel,
  planVerificationContent.change.reasonLabel,
  planVerificationContent.change.targetsLabel,
  planVerificationContent.change.reusedLabel,
  planVerificationContent.change.verifiedAtLabel,
  planVerificationContent.change.correctedSentence(1),
  planVerificationContent.change.verifiedSentence,
  ...Object.values(planVerificationContent.evidence).flatMap((entry) => {
    if (typeof entry === 'string') return [entry]
    if (typeof entry === 'function') return [entry('2026-09-28 10:00', '2026-09-29 10:00')]
    return Object.values(entry)
  }),
  ...Object.values(planVerificationContent.history),
]
