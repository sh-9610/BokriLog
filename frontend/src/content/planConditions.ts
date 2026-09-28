export const planConditionContent = {
  path: '/plan-conditions',
  openConditions: '계획 조건 확인',
  heading: '복리 계획 조건 확인',
  intro:
    '첫 공식 현재가치를 기준으로 조건을 검토합니다. 입력한 조건을 확인하기 전에는 저장되지 않습니다.',
  blocked: '현재 계획 조건을 확인할 수 없습니다. 계정, 연결 또는 기준 스냅샷 상태를 확인해 주세요.',
  p0: '시작 현재가치 (P0)',
  t0: '시작 시각 (t0)',
  source: '기준 근거',
  age: '측정 후 경과',
  endAt: '종료 시각 (T)',
  endHelp:
    '서울 시각 (Asia/Seoul, UTC+09:00)으로 입력합니다. 형식: YYYY-MM-DDTHH:mm. 계획 기간은 1일 이상 1년 이하입니다.',
  period: '복리 주기',
  rate: '목표 주기율',
  rateHelp:
    '퍼센트(%)로 입력합니다. 예: 10%는 API에 비율 문자열 0.1로 변환됩니다. 허용 범위는 주기당 0%부터 1000%, 소수점은 최대 10자리입니다.',
  daily: '매일',
  weekly: '매주',
  monthly: '매월',
  review: '조건 검토',
  cancel: '조건 수정',
  confirm: '이 조건 확인',
  saved: '계획 조건 검증 완료',
  savedHelp: '이 조건은 최초 계획을 발행하기 전까지 활성 계획이나 공식 계산 지표가 아닙니다.',
  savedAgain:
    '새 조건으로 바꾸려면 입력을 수정한 뒤 다시 확인하세요. 이전 조건은 수정되지 않고 대체됩니다.',
  collection: {
    heading: '초기 입력 수집',
    intro:
      'Bybit 현재가치와 원천 사건을 수집합니다. 수집 후보는 공식 현재가치나 계산 지표가 아니며, 완전성과 검토가 확인되기 전에는 계산에 사용되지 않습니다.',
    notStarted: '수집을 아직 시작하지 않았습니다.',
    start: '초기 입력 수집 시작',
    submitting: '접수 중',
    running: '수집 중',
    runningHelp:
      '요청한 기간의 페이지를 확인하고 있습니다. 완료 전까지 일부 입력은 후보 상태입니다.',
    complete: '입력 수집 완료',
    completeHelp:
      '필수 입력과 수집 근거가 준비되었습니다. 이 후보는 아직 공식 지표나 활성 계획이 아닙니다.',
    review: '검토 필요',
    reviewHelp:
      '원천 응답에서 확인이 필요한 항목을 찾았습니다. 후보 입력은 공식 결과에 반영되지 않습니다.',
    retry: '재시도 필요',
    retryHelp: '일부 페이지를 확인하지 못했습니다. 다시 시도해 전체 입력을 확인할 수 있습니다.',
    cancelled: '수집을 다시 확인해야 합니다',
    cancelledHelp:
      '계정이나 연결 상태가 바뀌어 작업이 중단되었습니다. 최신 상태를 확인한 뒤 다시 시작하세요.',
    lastChecked: '마지막 확인',
    timeUnavailable: '확인 시각을 표시할 수 없습니다.',
    failureReview:
      '원천 자료 확인이 필요해 후보를 만들지 않았습니다. 연결과 계획 조건을 확인한 뒤 다시 수집해 주세요.',
    failureRetry: '수집을 마치지 못했습니다. 연결 상태를 확인한 뒤 아래에서 다시 시도해 주세요.',
    failureCancelled:
      '계정이나 연결 상태가 바뀌어 수집이 중단되었습니다. 최신 상태를 확인한 뒤 다시 시작해 주세요.',
    failureUnknown:
      '수집 상태를 확인하지 못했습니다. 연결과 계획 조건을 확인한 뒤 상태를 새로고침해 주세요.',
    retryAction: '수집 다시 시도',
    refresh: '상태 새로고침',
    loadError: '수집 상태를 불러오지 못했습니다. 연결을 확인하고 다시 시도하세요.',
    startError: '수집을 접수하지 못했습니다. 연결을 확인하고 다시 시도하세요.',
    ineligible: '계정, 연결 또는 계획 조건 상태가 바뀌었습니다. 최신 상태를 확인하세요.',
    sessionError: '세션이 만료되었습니다. 로그인 상태를 확인한 뒤 다시 시도하세요.',
    classificationHeading: '외부 자금흐름 판정',
    classificationIntro:
      '완전 수집된 사건을 승인된 판정표로 확인합니다. 금액과 거래 상세는 표시하지 않으며, 판정 후보는 아직 공식 계산에 사용되지 않습니다.',
    classify: '원천 사건 판정 시작',
    classificationRunning: '판정 중',
    classificationComplete: '판정 완료',
    classificationReview: '검토 필요',
    classificationCancelled: '판정이 중단되었습니다',
    deposits: '입금 사건',
    withdrawals: '출금 사건',
    excluded: '제외 사건',
    needsReview: '검토 필요',
    unknownTypeReason:
      '등록되지 않은 사건 유형이 있어 입력 신뢰도를 확인해야 합니다. 지원 상태를 확인한 뒤 진행해 주세요.',
    genericReviewReason:
      '필수 근거나 사건 방향을 확인할 수 없습니다. 연결 상태를 확인한 뒤 다시 검토해 주세요.',
    classificationLoadError: '판정 상태를 불러오지 못했습니다. 연결을 확인하고 다시 시도하세요.',
    classificationStartError:
      '판정을 시작하지 못했습니다. 입력 수집 완료와 연결 상태를 확인하세요.',
  },
  /**
   * 사건 시점 USD 환산(Story 2.6).
   *
   * 환산 결과는 아직 공식 계획 지표가 아니므로 금액을 보이지 않는다. 자동 환산이 불가능한 흐름은
   * 제외된 것이 아니라 보류된 것이며, 그 영향은 "공식 갱신 보류·마지막 정상 결과 유지"로만 말한다.
   * 지원 경로가 아직 확정되지 않았으므로 링크도 지원 안내 문장도 두지 않는다 — 다음 행동은
   * 재시도(재시도 가능한 이유만)와 "조건 수정"을 통한 계획 전환, 그리고 검토 전까지의 보류 안내뿐이다.
   */
  conversion: {
    heading: '사건 시점 USD 환산',
    intro:
      '판정된 입출금을 사건이 일어난 시점의 승인된 가격 근거로 환산합니다. 환산 값은 아직 공식 계획 지표가 아니며, 이 화면은 계산에 쓸 수 있는지와 그 근거만 보여 줍니다.',
    notStarted: '환산을 아직 시작하지 않았습니다.',
    start: '사건 시점 환산 시작',
    submitting: '접수 중',
    running: '환산 중',
    runningHelp: '사건 시점의 가격 근거를 확인하고 있습니다. 완료 전까지 계산에 쓰지 않습니다.',
    converted: '환산 완료',
    convertedHelp:
      '추정 가격 없이 승인된 직접 정규화 근거로 환산을 마쳤습니다. 계산에 쓸 수 있는 자금흐름 묶음이 준비되었습니다.',
    noFlowsHelp:
      '판정된 외부 입출금이 없어 환산할 항목이 없습니다. 계산에 쓸 수 있는 빈 자금흐름 묶음이 준비되었습니다.',
    estimated: '추정값 포함',
    estimatedHelp:
      'Bybit 현물 1분봉 종가로 환산한 입출금이 있습니다. 종가는 실제 체결가격이 아니므로 이후 계산 결과는 추정으로 표시됩니다.',
    required: '환산 필요',
    requiredHelp:
      '사건 시점의 승인된 가격 근거가 없는 입출금이 있습니다. 공식 갱신은 보류되고 마지막 정상 결과가 유지됩니다.',
    interruptedHelp:
      '처리가 중간에 멈춰 환산 결과를 만들지 못했습니다. 아래에서 다시 시도할 수 있습니다. 공식 갱신은 보류되고 마지막 정상 결과가 유지됩니다.',
    review: '검토 필요',
    reviewHelp:
      '가격 근거를 확인할 수 없어 검토가 필요합니다. 원인을 검토하기 전까지 공식 갱신은 보류되고 마지막 정상 결과가 유지됩니다.',
    cancelled: '환산이 중단되었습니다',
    cancelledHelp:
      '계정, 연결 또는 판정 상태가 바뀌어 환산을 중단했습니다. 최신 상태를 확인한 뒤 다시 시작하세요.',
    counts: (
      flows: number,
      converted: number,
      estimated: number,
      required: number,
      review: number,
    ) =>
      `입출금 ${String(flows)}건 · 직접 정규화 ${String(converted)}건 · 추정 환산 ${String(estimated)}건 · 환산 보류 ${String(required)}건 · 검토 ${String(review)}건`,
    policies: '적용 정책',
    calculable:
      '계산에 쓸 수 있는 자금흐름 묶음입니다. 공식 계획 지표는 아래 최초 계획 계산·발행에서 계산합니다.',
    pendingHeading: '환산 필요·검토 필요 자산',
    pendingAsset: (asset: string, count: number) => `${asset} ${String(count)}건`,
    flowStatus: '상태',
    direction: '방향',
    deposit: '입금',
    withdrawal: '출금',
    quantity: '원수량',
    occurredAt: '사건 시각 (Asia/Seoul)',
    reason: '이유',
    impact: '영향',
    impactText: '공식 갱신 보류 · 마지막 정상 결과 유지',
    timeUnavailable: '사건 시각을 표시할 수 없습니다.',
    nextAction: '다음 행동',
    retryAction: '환산 다시 시도',
    planSwitch:
      '자동 환산이 불가능한 입출금이 남아 있으면 이 조건으로는 공식 갱신이 보류됩니다. 새 시작 기준으로 계획을 전환하려면 아래 “조건 수정”으로 조건을 다시 정하세요. 새 조건은 새 시작 기준에서 다시 수집·판정·환산합니다.',
    loadError: '환산 상태를 불러오지 못했습니다. 연결을 확인하고 다시 시도하세요.',
    startError: '환산을 접수하지 못했습니다. 판정 완료와 연결 상태를 확인하세요.',
    ineligible: '판정, 계정 또는 연결 상태가 바뀌어 환산할 수 없습니다. 최신 상태를 확인하세요.',
    reasons: {
      PRICE_PROVIDER_UNAVAILABLE: {
        cause: 'Bybit 공개 시세를 조회하지 못했습니다.',
        action: '잠시 후 아래에서 환산을 다시 시도하세요. 자동으로 다시 시도하지 않습니다.',
      },
      PRICE_CANDLE_NOT_CLOSED: {
        cause: '사건이 속한 1분 구간이 아직 끝나지 않았습니다.',
        action: '1분 뒤 아래에서 환산을 다시 시도하세요.',
      },
      PRICE_ROUTE_UNAVAILABLE: {
        cause: '이 자산에는 승인된 Bybit 현물 가격 경로가 없습니다.',
        action: '자동으로 환산할 수 없습니다. 아래 안내에 따라 계획을 전환할 수 있습니다.',
      },
      PRICE_CANDLE_MISSING: {
        cause: '사건 시각과 그 전 5분 안에 종료된 1분봉 가격이 없습니다.',
        action: '자동으로 환산할 수 없습니다. 아래 안내에 따라 계획을 전환할 수 있습니다.',
      },
      PRICE_NON_POSITIVE: {
        cause: '사건 시점 가격이 0 이하로 기록되어 사용할 수 없습니다.',
        action: '자동으로 환산할 수 없습니다. 아래 안내에 따라 계획을 전환할 수 있습니다.',
      },
      PRICE_RESPONSE_MALFORMED: {
        cause: '가격 응답의 형식을 확인할 수 없습니다.',
        action:
          '자동으로 다시 시도하지 않습니다. 원인을 검토하기 전까지 공식 갱신이 보류됩니다. 아래 안내에 따라 계획을 전환할 수 있습니다.',
      },
      PRICE_CANDLE_CONFLICT: {
        cause: '같은 가격 구간에 서로 다른 값이 있어 확인이 필요합니다.',
        action:
          '자동으로 다시 시도하지 않습니다. 원인을 검토하기 전까지 공식 갱신이 보류됩니다. 아래 안내에 따라 계획을 전환할 수 있습니다.',
      },
      CALCULATION_RANGE_EXCEEDED: {
        cause: '환산 값이 허용된 정밀도 범위를 벗어났습니다.',
        action:
          '자동으로 다시 시도하지 않습니다. 원인을 검토하기 전까지 공식 갱신이 보류됩니다. 아래 안내에 따라 계획을 전환할 수 있습니다.',
      },
      INPUT_INVALID: {
        cause: '판정된 입출금의 수량이나 자산 형식을 확인할 수 없습니다.',
        action:
          '자동으로 다시 시도하지 않습니다. 원인을 검토하기 전까지 공식 갱신이 보류됩니다. 아래 안내에 따라 계획을 전환할 수 있습니다.',
      },
      NOT_PERMITTED: {
        cause: '계정, 연결 또는 판정 상태가 바뀌었습니다.',
        action: '최신 상태를 확인한 뒤 환산을 다시 시작하세요.',
      },
      CONVERSION_INTERRUPTED: {
        cause: '처리가 중간에 멈췄습니다.',
        action: '아래에서 환산을 다시 시도하세요.',
      },
    },
    unknownReason: {
      cause: '환산 상태를 확인하지 못했습니다.',
      action: '상태를 새로고침한 뒤 다시 확인하세요.',
    },
  },
  errors: {
    requiredEnd: '종료 시각을 입력하세요.',
    requiredRate: '목표 주기율을 입력하세요.',
    time: '서울 시각을 YYYY-MM-DDTHH:mm 형식으로 입력하고 기간을 확인하세요.',
    unavailable: '계정, 연결 또는 기준 스냅샷 상태가 바뀌었습니다. 최신 상태를 확인해 주세요.',
    rate: '0%부터 1000% 사이의 숫자를 최대 10자리 소수로 입력하세요.',
    network: '요청을 완료하지 못했습니다. 연결을 확인하고 다시 시도하세요.',
    stale: '기준 스냅샷이나 정책이 바뀌었습니다. 최신 상태를 불러와 다시 검토하세요.',
    conflict: '다른 탭에서 먼저 조건을 확인했습니다. 저장된 조건을 불러왔습니다.',
  },
  fieldErrors: '입력 오류',
} as const
