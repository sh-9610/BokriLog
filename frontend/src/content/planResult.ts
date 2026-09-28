/**
 * 최초 계획 발행과 공식 결과의 화면 카피(Story 2.7).
 *
 * 값을 판정하지 않는다. 사실(무엇을 언제 계산했는가), 원인, 기준 시각과 다음 행동만 말한다. 수익
 * 축하·손실 공포·행동 지시·성과수익률로 읽힐 표현을 쓰지 않으며, 지표 이름은 PRD §4의 이름
 * 그대로다. 상태명은 EXPERIENCE의 소스 용어다: 신선도 `최신`·`동기화 중`·`최신 아님`, 입력 신뢰도
 * `확인됨`·`검토 필요`·`사용 불가`, 계산 성격 `공식`·`추정`·`계산 불가`.
 */

/** 활성 계획의 공식 결과. 공식 포인터 하나에서 읽은 묶음이다. */
export const PLAN_RESULT_PATH = '/api/plans/active'

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

export const planResultContent = {
  heading: '복리 계획 결과',
  intro:
    '활성 계획의 공식 계산 결과입니다. 여섯 지표는 같은 기준 시각·계산 버전에서 한 번에 계산했습니다.',
  announce: (basis: string) => `복리 계획 결과를 표시합니다. 계산 기준 시각은 ${basis}입니다.`,
  conditionsHeading: '계획 조건',
  principal: '시작 현재가치 (P0)',
  planPeriod: '계획 기간',
  period: '복리 주기',
  targetRate: '목표 주기율',
  timesHeading: '기준 시각',
  basisAt: '계산 기준 시각',
  measuredAt: '현재가치 측정 시각',
  verifiedThrough: '외부 자금흐름 확인 시각',
  timeZone: 'Asia/Seoul',
  timeUnavailable: '시각을 표시할 수 없습니다.',
  periods: { DAILY: '매일', WEEKLY: '매주', MONTHLY: '매월' } as Record<string, string>,
  periodUnit: { DAILY: '일', WEEKLY: '주', MONTHLY: '월' } as Record<string, string>,
  trustHeading: '신뢰 상태',
  metricsHeading: '지표',
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
        FRESH: '현재가치 측정과 외부 자금흐름 확인이 모두 15분 이내입니다.',
        SYNCING: '동기화 작업이 진행 중입니다. 기존 공식 값은 그대로 유지됩니다.',
        STALE:
          '현재가치 측정이나 외부 자금흐름 확인이 15분보다 오래됐습니다. 기존 공식 값은 그대로 유지됩니다.',
      } as Record<string, string>,
      actions: {
        FRESH: '추가로 할 일이 없습니다.',
        // 이 화면은 스스로 다시 읽지 않는다(ERD §4: 목적 없는 반복 조회 금지). 사용자가 다시 연다.
        SYNCING: '작업이 끝난 뒤 이 화면을 새로 열면 새 결과를 확인할 수 있습니다.',
        STALE: '동기화 조작은 이후 단계에서 제공합니다. 마지막 공식 결과가 유지됩니다.',
      } as Record<string, string>,
      impact: '여섯 지표',
    },
    reliability: {
      name: '입력 신뢰도',
      states: {
        CONFIRMED: '확인됨',
        REVIEW_REQUIRED: '검토 필요',
        UNAVAILABLE: '사용 불가',
      } as Record<string, string>,
      causes: {
        CONFIRMED: '수집 완전성, 외부 자금흐름 판정과 사건 시점 환산을 확인한 입력입니다.',
        REVIEW_REQUIRED: '조정 최종 목표가 0 이하라 계획 입력을 검토해야 합니다.',
        UNAVAILABLE: '계산 입력을 사용할 수 없습니다.',
      } as Record<string, string>,
      actions: {
        CONFIRMED: '추가로 할 일이 없습니다.',
        REVIEW_REQUIRED: '계획 조건과 입출금을 확인해 주세요.',
        UNAVAILABLE: '연결과 계획 조건을 확인해 주세요.',
      } as Record<string, string>,
      impact: '조정 최종 목표 · 남은 필요 등가수익률 · 목표 수익 달성도',
    },
    nature: {
      name: '계산 성격',
      states: { OFFICIAL: '공식', ESTIMATED: '추정', NOT_COMPUTABLE: '계산 불가' } as Record<
        string,
        string
      >,
      causes: {
        OFFICIAL: '추정 가격 없이 계산했습니다.',
        ESTIMATED: '추정 환산가를 쓴 입출금이 있어 현재가치를 뺀 다섯 지표가 추정입니다.',
        NOT_COMPUTABLE: '정의되지 않는 지표가 있습니다. 0으로 대체하지 않고 이유를 함께 보입니다.',
      } as Record<string, string>,
      estimatedToo: '현재가치를 뺀 지표에는 추정 환산가가 포함돼 있습니다.',
      unavailableMetrics: (names: string) => `계산 불가 지표: ${names}`,
      actions: {
        OFFICIAL: '추가로 할 일이 없습니다.',
        ESTIMATED: '각 지표의 추정 근거를 확인해 주세요.',
        NOT_COMPUTABLE: '지표별 이유를 확인해 주세요.',
      } as Record<string, string>,
      /** 성격이 닿는 지표. 현재가치는 늘 공식이라 추정이면 다섯 지표다. */
      impacts: {
        OFFICIAL: '여섯 지표',
        ESTIMATED: '현재가치를 뺀 다섯 지표',
      } as Record<string, string>,
    },
    cause: '원인',
    impact: '영향',
    action: '다음 행동',
    /** 모르는 상태 코드는 글자 그대로 보이지 않고 이 문장들로 접는다. */
    unknownState: '확인할 수 없음',
    unknownCause: '상태를 확인하지 못했습니다.',
    unknownAction: '화면을 새로 열어 다시 확인해 주세요.',
    unknownImpact: '지표',
  },
  /** 알 수 없는 주기 코드. 코드 문자열 그대로 보이지 않는다. */
  unknownPeriod: '확인할 수 없는 주기',
  unknownPeriodUnit: '해당',
  policies: '적용 정책',
} as const

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
  sessionError: '세션이 만료되었습니다. 로그인 상태를 확인한 뒤 다시 시도하세요.',
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
