/**
 * 최초 계획 발행과 공식 대시보드의 화면 카피(Story 2.7·2.8).
 *
 * 값을 판정하지 않는다. 사실(무엇을 언제 계산했는가), 원인, 기준 시각과 다음 행동만 말한다. 수익
 * 축하·손실 공포·행동 지시·성과수익률로 읽힐 표현을 쓰지 않으며, 지표 이름은 PRD §4의 이름
 * 그대로다. 상태명은 EXPERIENCE의 소스 용어다: 신선도 `최신`·`동기화 중`·`최신 아님`, 입력 신뢰도
 * `확인됨`·`검토 필요`·`사용 불가`, 계산 성격 `공식`·`추정`·`계산 불가`.
 *
 * 세 축의 상태·원인·다음 행동은 서버가 코드로 판정한다(Story 2.8). 화면은 아래 닫힌 맵으로만 코드를
 * 카피에 잇고, 모르는 코드는 글자 그대로 보이지 않고 안전한 카피로 접는다. 시간 기준은 서버의 것이다 —
 * 화면은 "몇 분 전" 같은 상대 시각을 만들지 않는다.
 */

/** 공식 대시보드. 조회 모델 하나이며 경로는 식별자를 받지 않는다(Story 2.8). */
export const DASHBOARD_PATH = '/api/dashboard'

/** 최초 계획 발행 시도. */
export const PLAN_PUBLICATION_PATH = '/api/snapshots/first/plan-publication'

export type MetricName =
  | 'CURRENT_VALUE'
  | 'PLAN_AMOUNT'
  | 'PLAN_DIFFERENCE'
  | 'ADJUSTED_TARGET'
  | 'REQUIRED_RATE'
  | 'GOAL_PROGRESS'

/** 화면의 정보 순서. 서버도 같은 순서로 보낸다. */
export const METRIC_ORDER: readonly MetricName[] = [
  'CURRENT_VALUE',
  'PLAN_AMOUNT',
  'PLAN_DIFFERENCE',
  'ADJUSTED_TARGET',
  'REQUIRED_RATE',
  'GOAL_PROGRESS',
]

/** 두 상태 이름. 상태 리전의 문장과 시험이 같은 값을 쓴다. */
const NO_PLAN_LABEL = '계획 없음'
const PREPARING_LABEL = '결과 준비 중'

/** 세션 만료 문장. 발행 섹션과 대시보드가 같은 문장을 쓴다. */
const SESSION_EXPIRED = '세션이 만료되었습니다. 로그인 상태를 확인한 뒤 다시 시도하세요.'

export const planResultContent = {
  heading: '복리 계획 결과',
  intro:
    '활성 계획의 공식 계산 결과입니다. 아래 값은 모두 같은 기준 시각·계산 버전에서 한 번에 계산한 한 묶음입니다.',
  /**
   * 화면의 단일 상태 리전이 읽는 문장. 상태가 바뀔 때 한 문장으로 한 번 알린다. 이 리전은 화면에도
   * 보이므로 패널은 같은 문장을 되풀이하지 않는다 — `계획 없음`·`결과 준비 중`은 여기서만 말한다.
   */
  status: {
    checking: '복리 계획 결과를 확인하는 중입니다.',
    reloading: '복리 계획 결과를 다시 불러오는 중입니다.',
    noPlan: `${NO_PLAN_LABEL}: 이 계정에는 아직 활성 계획이 없습니다.`,
    preparing: `${PREPARING_LABEL}: 최초 계획과 공식 결과를 발행하고 있습니다.`,
    ready: (basis: string) => `복리 계획 결과를 표시합니다. 계산 기준 시각은 ${basis}입니다.`,
    /** 입력 신뢰도가 확인됨이 아니면 그 사실이 같은 문장 안에서 먼저 나온다. */
    readyWithReliability: (reliability: string, basis: string) =>
      `복리 계획 결과를 표시합니다. 입력 신뢰도는 ${reliability}이며 계산 기준 시각은 ${basis}입니다.`,
    lastGood: '다시 불러오지 못했습니다. 보이던 결과를 마지막 정상 결과로 그대로 둡니다.',
    versionConflict:
      '새 응답의 버전이 화면의 결과와 맞지 않아 바꾸지 않았습니다. 다시 불러오기로 재시도할 수 있습니다.',
    retryable: '복리 계획 결과를 불러오지 못했습니다.',
    denied: '이 계정의 복리 계획 결과를 지금 볼 수 없습니다.',
    /** 세션이 끝난 읽기(401). 발행 섹션이 쓰는 세션 만료 문장과 같은 문장이다. */
    signedOut: SESSION_EXPIRED,
  },
  noPlanLabel: NO_PLAN_LABEL,
  preparingLabel: PREPARING_LABEL,
  /** 패널이 값 곁에 두는 상태 이름(AC-8). */
  lastGoodLabel: '마지막 정상 결과',
  /** @param measured 서울 시각으로 쓴 측정 시각. 표시할 수 없으면 `null`이고 시각 없는 문장이 된다. */
  lastGoodHelp: (measured: string | null) =>
    measured === null
      ? '아래 값은 마지막 정상 결과이며 바뀌지 않았습니다. 다시 불러오기로 재시도할 수 있습니다.'
      : `아래 값은 ${measured}에 측정한 마지막 정상 결과이며 바뀌지 않았습니다. 다시 불러오기로 재시도할 수 있습니다.`,
  retryableLabel: '재시도 가능',
  retryableHelp: '금융값은 표시하지 않았습니다. 다시 불러오기로 재시도할 수 있습니다.',
  /** 버전이 맞지 않는 응답을 받지 않았을 때(AC-9). 보이던 값을 그대로 두고 재시도 가능으로 둔다. */
  versionConflictHelp: (measured: string | null) =>
    measured === null
      ? '표시된 값은 마지막 정상 결과입니다. 새 응답은 버전이 맞지 않아 받지 않았습니다.'
      : `표시된 값은 ${measured}에 측정한 마지막 정상 결과입니다. 새 응답은 버전이 맞지 않아 받지 않았습니다.`,
  deniedHelp: '이 계정의 결과를 볼 수 없습니다. 계정 삭제가 진행 중이면 결과가 표시되지 않습니다.',
  /** 세션이 끝난 읽기(401)에서 로그인 화면으로 가는 문서 링크. */
  signInLink: '로그인 화면으로 이동',
  reload: '다시 불러오기',
  currentHeading: '마지막 정상 현재가치',
  conditionsHeading: '계획 조건',
  principal: '시작 현재가치 (P0)',
  planPeriod: '계획 기간',
  period: '복리 주기',
  targetRate: '목표 주기율',
  timeZone: 'Asia/Seoul',
  timeUnavailable: '시각을 표시할 수 없습니다.',
  periods: { DAILY: '매일', WEEKLY: '매주', MONTHLY: '매월' } as Record<string, string>,
  periodUnit: { DAILY: '일', WEEKLY: '주', MONTHLY: '월' } as Record<string, string>,
  trustHeading: '신뢰 상태',
  metricsHeading: '지표',
  metricsIntro: '현재가치와 같은 기준 시각·계산 버전에서 계산한 지표입니다.',
  nextActionHeading: '다음 행동',
  metrics: {
    CURRENT_VALUE: {
      name: '현재가치',
      help: 'Bybit가 측정한 계좌 총자산 값입니다. BokriLog가 다시 계산하지 않습니다.',
    },
    PLAN_AMOUNT: {
      name: '현재 시점 계획금액',
      help: '시작 현재가치와 확인된 입출금을 목표 주기율로 기준 시각까지 복리 적용한 계획선입니다.',
    },
    PLAN_DIFFERENCE: {
      name: '계획 대비 차이',
      help: '현재가치에서 현재 시점 계획금액을 뺀 값입니다.',
    },
    ADJUSTED_TARGET: {
      name: '조정 최종 목표',
      help: '확인된 입출금을 반영한 계획 종료 시각의 목표 금액입니다. 미래 입출금은 예측하지 않습니다.',
    },
    REQUIRED_RATE: {
      name: '남은 필요 등가수익률',
      help: '남은 기간에 조정 최종 목표에 닿는 주기당 등가 비율입니다.',
    },
    GOAL_PROGRESS: {
      name: '목표 수익 달성도',
      help: '목표 이익(조정 최종 목표 − 조정 원금) 가운데 현재 이익(현재가치 − 조정 원금)의 비율입니다. 음수와 100% 초과를 그대로 보입니다.',
    },
  } as Record<MetricName, { name: string; help: string }>,
  /** 필요율 옆에 인접한 경계 문장. EXPERIENCE의 Do 목록 문장이다. */
  requiredRateBoundary: (unit: string) =>
    `남은 필요 등가수익률은 ${unit} 주기 계획 비교값이며 투자 추천이 아닙니다.`,
  basisLine: (unit: string, basis: string) => `${unit} 주기 · 기준 ${basis}`,
  measuredLine: (measured: string) => `측정 ${measured}`,
  natureLabel: '계산 성격',
  natures: { OFFICIAL: '공식', ESTIMATED: '추정', NOT_COMPUTABLE: '계산 불가' } as Record<
    string,
    string
  >,
  notComputable: '계산 불가',
  reasonLabel: '이유',
  reasons: {
    CURRENT_VALUE_NOT_POSITIVE:
      '현재가치가 0 이하라 남은 필요 등가수익률을 정의할 수 없습니다. 금액 지표는 그대로 보입니다.',
    TARGET_NOT_POSITIVE:
      '조정 최종 목표가 0 이하라 목표 기반 비율을 정의할 수 없습니다. 계획 입력 신뢰도가 검토 필요입니다.',
    GOAL_PROFIT_NOT_POSITIVE: '목표 이익이 0 이하라 목표 수익 달성도를 정의할 수 없습니다.',
    REMAINING_PERIOD_NOT_POSITIVE:
      '남은 계획 기간이 없어 남은 필요 등가수익률을 정의할 수 없습니다.',
  } as Record<string, string>,
  unknownReason: '이 지표를 정의할 수 없습니다.',
  estimatedEvidence: (count: number) =>
    `추정 근거: Bybit 현물 1분봉 종가로 환산한 입출금 ${String(count)}건이 반영됐습니다. 종가는 실제 체결가격이 아닙니다.`,
  axes: {
    freshness: {
      name: '신선도',
      states: { FRESH: '최신', SYNCING: '동기화 중', STALE: '최신 아님' } as Record<string, string>,
      causes: {
        BOTH_WITHIN_WINDOW: '현재가치 측정과 외부 자금흐름 확인이 모두 15분 이내입니다.',
        JOB_IN_PROGRESS: '동기화 작업이 진행 중입니다. 기존 공식 값은 그대로 유지됩니다.',
        MEASUREMENT_OUTDATED:
          '현재가치 측정이 15분보다 오래됐습니다. 기존 공식 값은 그대로 유지됩니다.',
        CASHFLOW_VERIFICATION_OUTDATED:
          '외부 자금흐름 확인이 15분보다 오래됐습니다. 기존 공식 값은 그대로 유지됩니다.',
        BOTH_OUTDATED:
          '현재가치 측정과 외부 자금흐름 확인이 모두 15분보다 오래됐습니다. 기존 공식 값은 그대로 유지됩니다.',
      } as Record<string, string>,
      lastNormal: '마지막 정상 시각 (현재가치 측정 시각)',
      verifiedThrough: '외부 자금흐름 확인 시각',
      /** AC-4: 두 시각을 하나의 최신 시각처럼 말하지 않고, 아직 반영되지 않았을 수 있는 사건을 알린다. */
      timesNoteLabel: '두 시각',
      timesNote:
        '현재가치 측정 시각과 외부 자금흐름 확인 시각은 서로 다른 기준이며, 어느 한쪽도 전체 데이터의 최신 시각이 아닙니다. 외부 자금흐름 확인 시각 뒤에 생긴 입출금은 아직 반영되지 않았을 수 있습니다.',
    },
    reliability: {
      name: '입력 신뢰도',
      states: {
        CONFIRMED: '확인됨',
        REVIEW_REQUIRED: '검토 필요',
        UNUSABLE: '사용 불가',
      } as Record<string, string>,
      causes: {
        INPUTS_VERIFIED: '수집 완전성, 외부 자금흐름 판정과 사건 시점 환산을 확인한 입력입니다.',
        TARGET_NOT_POSITIVE: '조정 최종 목표가 0 이하라 계획 입력을 검토해야 합니다.',
      } as Record<string, string>,
      lastNormal: '마지막 정상 시각 (외부 자금흐름 확인 시각)',
    },
    nature: {
      name: '계산 성격',
      states: { OFFICIAL: '공식', ESTIMATED: '추정', NOT_COMPUTABLE: '계산 불가' } as Record<
        string,
        string
      >,
      causes: {
        NO_ESTIMATED_INPUT: '추정 가격 없이 계산했습니다.',
        ESTIMATED_CASHFLOW_PRICE:
          '추정 환산가를 쓴 입출금이 있어 현재가치를 뺀 다섯 지표가 추정입니다.',
        METRIC_UNDEFINED:
          '정의되지 않는 지표가 있습니다. 0으로 대체하지 않고 이유를 함께 보입니다.',
      } as Record<string, string>,
      estimatedToo: '현재가치를 뺀 지표에는 추정 환산가가 포함돼 있습니다.',
      unavailableMetrics: (names: string) => `계산 불가 지표: ${names}`,
      lastNormal: '마지막 정상 시각 (계산 기준 시각)',
    },
    cause: '원인',
    affected: '영향 지표',
    noneAffected: '없음',
    action: '다음 행동',
    /** 모르는 코드는 글자 그대로 보이지 않고 이 문장들로 접는다. */
    unknownState: '확인할 수 없음',
    unknownCause: '상태를 확인하지 못했습니다.',
    unknownMetric: '확인할 수 없는 지표',
  },
  /** 알 수 없는 주기 코드. 코드 문자열 그대로 보이지 않는다. */
  unknownPeriod: '확인할 수 없는 주기',
  unknownPeriodUnit: '해당',
  policies: '적용 정책',
} as const

/**
 * 닫힌 맵에서 코드의 카피를 찾는다. 모르는 코드는 `fallback`으로 접고 글자 그대로 보이지 않는다.
 * 패널과 상태 리전이 같은 함수를 쓴다.
 */
export function copyFor(
  table: Readonly<Record<string, string>>,
  code: string,
  fallback: string,
): string {
  return Object.hasOwn(table, code) ? (table[code] ?? fallback) : fallback
}

/** 모르는 다음 행동 코드의 문장. */
export const NEXT_ACTION_FALLBACK = '다시 불러와 상태를 확인해 주세요.'

/**
 * 화면 단위 다음 행동 코드 → 문장(Story 2.8의 닫힌 집합: `CREATE_PLAN`·`WAIT_FOR_RESULT`·
 * `WAIT_FOR_SYNC`·`NONE`). 동기화 조작은 Story 2.11이 코드를 더한다. 그전까지 `최신 아님`의 다음
 * 행동은 `NONE`이고, 자동 복구나 이후 기능을 약속하지 않는다. `NONE`의 승인 문장은 화면에 한 번,
 * 다음 행동 섹션에만 나온다.
 *
 * @param measured 서울 시각으로 쓴 현재가치 측정 시각. 결과가 없거나 시각을 표시할 수 없으면 `null`이다.
 */
export function nextActionCopy(code: string, measured: string | null): string {
  switch (code) {
    case 'NONE':
      return measured === null
        ? '지금 이 화면에서 새로 확인할 수 없습니다. 표시된 값은 마지막 정상 결과입니다.'
        : `지금 이 화면에서 새로 확인할 수 없습니다. 표시된 값은 ${measured}의 마지막 정상 결과입니다.`
    case 'WAIT_FOR_SYNC':
      return '동기화 작업이 진행 중입니다. 작업이 끝난 뒤 다시 불러와 확인해 주세요. 그전까지 표시된 값은 바뀌지 않습니다.'
    case 'WAIT_FOR_RESULT':
      return '발행이 끝난 뒤 다시 불러와 결과를 확인해 주세요.'
    case 'CREATE_PLAN':
      // 계획을 만들 수 있다고 단정하지 않는다. 연결이 없거나 현재가치가 0 이하인 계정도 이 답을 받는다.
      return '계획 조건 화면에서 지금 계획을 준비할 수 있는지 확인해 주세요.'
    default:
      return NEXT_ACTION_FALLBACK
  }
}

/**
 * 축 하나의 다음 행동 코드 → 짧은 문장. 축은 `WAIT_FOR_SYNC`(신선도, 작업 진행 중)와 `NONE`만
 * 만든다. 시각을 담지 않는다 — 축마다 자기 마지막 정상 시각이 따로 있고, 측정 시각을 되풀이하면 두
 * 기준이 한 묶음에 섞여 읽힌다.
 */
export function axisActionCopy(code: string): string {
  switch (code) {
    case 'NONE':
      return '지금 할 수 있는 조치가 없습니다.'
    case 'WAIT_FOR_SYNC':
      return '동기화 작업이 끝난 뒤 다시 불러와 확인해 주세요.'
    default:
      return NEXT_ACTION_FALLBACK
  }
}

/**
 * 최초 계획 발행 섹션(환산 섹션 밖의 형제). 금액과 지표를 보이지 않는다 — 결과는 대시보드가
 * 공식 포인터 하나에서 보인다.
 */
export const planPublicationContent = {
  heading: '최초 계획 계산·발행',
  intro:
    '환산까지 확인된 입력으로 여섯 지표를 계산하고, 성공하면 활성 계획과 공식 결과를 한 번에 발행합니다. 발행한 계획 조건은 바꿀 수 없습니다.',
  notStarted: '아직 발행하지 않았습니다.',
  start: '최초 계획 계산·발행',
  submitting: '접수 중',
  running: '계산·발행 중',
  runningHelp: '입력을 다시 확인하고 계산하고 있습니다. 완료 전까지 활성 계획은 없습니다.',
  published: '발행 완료',
  publishedHelp: '활성 계획과 공식 결과를 함께 발행했습니다.',
  blocked: '발행하지 않았습니다',
  failed: '계산하지 못했습니다',
  /** 시도가 없고 서버가 지금 발행을 받지 않을 때. 이유와 다음 행동을 함께 보인다. */
  notStartable: '지금은 발행할 수 없습니다',
  retryAction: '발행 다시 시도',
  refresh: '상태 새로고침',
  dashboardLink: '결과 보기',
  loadError: '발행 상태를 불러오지 못했습니다. 연결을 확인하고 다시 시도하세요.',
  startError: '발행을 접수하지 못했습니다. 연결을 확인하고 다시 시도하세요.',
  sessionError: SESSION_EXPIRED,
  reasonLabel: '이유',
  /** 지난 시도의 이유와 다른, 지금 새 시도를 받지 않는 이유. */
  currentReasonLabel: '지금 발행할 수 없는 이유',
  actionLabel: '다음 행동',
  reasons: {
    ACTIVE_PLAN_EXISTS: {
      cause: '이미 활성 계획이 있어 새 계획을 만들지 않았습니다.',
      action: '결과 보기에서 현재 계획의 공식 결과를 확인하세요.',
    },
    PLAN_CONDITIONS_REQUIRED: {
      cause: '저장된 계획 조건을 더 쓸 수 없습니다.',
      action: '“조건 수정”으로 계획 조건을 다시 정하세요.',
    },
    PLAN_CONDITIONS_CHANGED: {
      cause: '계획 조건이나 기준 스냅샷이 바뀌었습니다.',
      action: '“조건 수정”으로 계획 조건을 다시 정하세요.',
    },
    PLAN_PERIOD_ENDED: {
      cause: '계획 종료 시각이 지나 이 조건으로는 계획을 만들 수 없습니다.',
      action: '“조건 수정”으로 새 종료 시각을 정하세요.',
    },
    INPUTS_NOT_READY: {
      cause: '계산에 쓸 입력이 아직 준비되지 않았습니다.',
      action: '수집·판정·환산을 먼저 완료하세요.',
    },
    INPUTS_CHANGED: {
      cause: '입력을 고정한 뒤 수집·판정·환산 결과가 바뀌었습니다.',
      action: '입력을 다시 준비한 뒤 발행하세요.',
    },
    MEMBERSHIP_CHANGED: {
      cause: '포트폴리오의 연결 구성이 바뀌었습니다.',
      action: '연결 상태를 확인한 뒤 새 조건으로 다시 시작하세요.',
    },
    POINTER_CONFLICT: {
      cause: '공식 현재가치가 그사이 다른 값으로 바뀌었습니다.',
      action: '입력을 다시 준비한 뒤 발행하세요.',
    },
    NOT_PERMITTED: {
      cause: '계정·연결 상태나 운영 제한이 발행을 허용하지 않았습니다.',
      action: '잠시 뒤 상태를 새로고침해 다시 확인하세요.',
    },
    CALCULATION_RANGE_EXCEEDED: {
      cause: '계산 값이 허용된 정밀도 범위를 벗어나 계획을 만들지 않았습니다.',
      action: '“조건 수정”으로 계획 조건을 다시 정하세요.',
    },
    CALCULATION_INPUT_INVALID: {
      cause: '계산 입력의 형식을 확인할 수 없어 계획을 만들지 않았습니다.',
      action: '입력을 다시 준비한 뒤 발행하세요.',
    },
    CALCULATION_POLICY_UNVERIFIED: {
      cause: '계산 정책의 고정 예제를 확인하지 못해 발행을 멈췄습니다.',
      action: '서비스 상태를 확인한 뒤 다시 시도하세요.',
    },
    PUBLICATION_INTERRUPTED: {
      cause: '처리가 중간에 멈춰 아무것도 발행하지 않았습니다.',
      action: '아래에서 발행을 다시 시도하세요.',
    },
    IDEMPOTENCY_CONFLICT: {
      cause: '같은 요청 키가 다른 계획 조건에 쓰였습니다.',
      action: '상태를 새로고침한 뒤 다시 시도하세요.',
    },
  } as Record<string, { cause: string; action: string }>,
  unknownReason: {
    cause: '발행 상태를 확인하지 못했습니다.',
    action: '상태를 새로고침한 뒤 다시 확인하세요.',
  },
} as const

/** 조건 화면의 활성 계획 요약. */
export const activePlanContent = {
  heading: '활성 계획',
  help: '이미 활성 계획이 있어 새 조건을 받지 않습니다. 발행한 계획 조건은 바꿀 수 없습니다.',
  dashboardLink: '결과 보기',
  /** 조건 저장이 활성 계획 때문에 거절됐을 때. 누가 먼저 발행했는지는 이 화면이 알 수 없다. */
  conflict: '활성 계획이 이미 있어 새 조건을 받지 않았습니다. 활성 계획을 불러왔습니다.',
} as const
