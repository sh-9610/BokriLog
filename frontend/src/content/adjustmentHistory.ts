/**
 * 계획에 반영된 외부 자금흐름 조정 이력의 카피(Story 2.10).
 *
 * 이력은 읽기 전용이다. 지금 화면에 보이는 공식 계산 버전이 반영한 외부 입출금만 보이고, 새로 동기화·재계산·
 * 재분류·재환산하지 않는다. 외부 입금·출금은 계획 기준선의 조정일 뿐 투자 손익·수익률이 아니며, 조정값을
 * 평가하지 않는다. 서버가 보낸 코드는 아래 닫힌 맵으로만 카피에 잇고, 모르는 코드는 글자 그대로 보이지 않고
 * 이 파일의 안전한 카피로 접는다(`lookup`). 규칙·정책·거래 기록 유형 같은 식별 코드는 해석하지 않고 mono
 * 전체값으로 보인다 — 거래소 기록과 맞춰 볼 열쇠이기 때문이다.
 */

import { planConditionContent } from './planConditions'

/** 조정 이력 조회. 지금 보이는 묶음의 공식 포인터 세 값과 유형 필터, 앞 페이지의 cursor만 쿼리로 보낸다. */
export const DASHBOARD_ADJUSTMENTS_PATH = '/api/dashboard/adjustments'

/** 유형 필터. 서버의 `AdjustmentDirection`과 같은 닫힌 집합이다. */
export type AdjustmentDirection = 'ALL' | 'IN' | 'OUT'

export const ADJUSTMENT_DIRECTIONS: readonly AdjustmentDirection[] = ['ALL', 'IN', 'OUT']

/** 이력을 요청하는 묶음의 참조. 대시보드 응답이 준 세 값 그대로다. */
export interface AdjustmentRequest {
  readonly planId: string
  readonly pointerVersion: number
  readonly calculationId: string
}

/**
 * 조정 이력 조회 URL. 참조 세 값, 필터, 그리고 다음 페이지면 서버가 준 불투명 cursor를 그대로 싣는다 —
 * 금융값·사건 ID·사용자 식별자는 URL에 두지 않고, 클라이언트는 cursor를 해석하지 않는다.
 */
export function adjustmentsPath(
  reference: AdjustmentRequest,
  direction: AdjustmentDirection,
  cursor: string | null,
): string {
  const query = new URLSearchParams({
    planId: reference.planId,
    pointerVersion: String(reference.pointerVersion),
    calculationId: reference.calculationId,
    direction,
  })
  if (cursor !== null) query.set('cursor', cursor)
  return `${DASHBOARD_ADJUSTMENTS_PATH}?${query.toString()}`
}

/**
 * 닫힌 맵에서 코드의 카피를 찾는다. 서버가 보낸 모르는 코드는 `fallback`으로 접혀 글자 그대로 보이지
 * 않는다.
 */
export function lookup<K extends string>(
  table: Readonly<Record<K, string>>,
  code: string,
  fallback: string,
): string {
  return Object.hasOwn(table, code) ? table[code as K] : fallback
}

const SESSION_EXPIRED = '세션이 만료되었습니다. 로그인 상태를 확인한 뒤 다시 시도하세요.'

/** 환산 보류 이유의 원인 문장. 환산 섹션과 같은 말을 쓴다. */
const CONVERSION_REASONS: Readonly<Record<string, string>> = Object.fromEntries(
  Object.entries(planConditionContent.conversion.reasons).map(([code, reason]) => [
    code,
    reason.cause,
  ]),
)

export const adjustmentHistoryContent = {
  /** 펼침 버튼. 이동이 아니라 같은 화면의 상태 변경이라 링크가 아니라 버튼이다. */
  open: '조정 이력 보기',
  close: '조정 이력 닫기',
  retry: '조정 이력 다시 불러오기',
  more: '더 보기',
  heading: '조정 이력',
  intro:
    '지금 표시된 공식 계산 버전에 반영된 외부 입출금입니다. 각 사건이 어떤 규칙과 가격으로 몇 USD로 계획 기준선에 들어갔는지 보여 줍니다. 읽기 전용이며 새로 동기화하거나 다시 계산하지 않습니다.',
  /** AC-7: 금액 옆 도움말. 조정값을 평가하지 않는다. */
  term: '외부 입금·출금은 계획 기준선을 조정할 뿐이며 투자 손익이나 수익률이 아닙니다.',
  /**
   * 화면의 단일 상태 리전이 대시보드·근거 문장 뒤에 이어 읽는 문장. 이력 영역에는 live region이 없고,
   * 읽는 중에는 영역도 같은 문장을 보인다.
   */
  status: {
    loading: '조정 이력을 불러오는 중입니다.',
    loadingMore: '조정 이력을 더 불러오는 중입니다.',
    ready: (shown: number, total: number) =>
      `조정 이력을 표시합니다. ${String(total)}건 가운데 ${String(shown)}건입니다.`,
    retryable: '조정 이력을 불러오지 못했습니다. 대시보드의 값은 바뀌지 않았습니다.',
    /** 다음 페이지 읽기가 실패했을 때. 이미 받은 행은 그대로 있다. */
    moreRetryable:
      '조정 이력의 다음 페이지를 불러오지 못했습니다. 이미 받은 행과 대시보드의 값은 바뀌지 않았습니다.',
    stale: '이 결과의 조정 이력을 찾지 못했습니다. 대시보드의 값은 바뀌지 않았습니다.',
    denied: '이 계정의 조정 이력을 지금 볼 수 없습니다.',
    signedOut: '세션이 만료되어 조정 이력을 불러오지 못했습니다.',
    /** 계산 표시 제한(Story 3.2). */
    restricted: '운영상 제한으로 이 결과의 조정 이력을 지금 표시하지 않습니다.',
  },
  failure: {
    /** 첫 페이지 읽기가 실패했을 때. 받은 행이 아직 없다. */
    retryable: '조정 이력을 불러오지 못했습니다. 대시보드의 카드와 신뢰 상태는 바뀌지 않았습니다.',
    /** 다음 페이지 읽기가 실패했을 때. 이미 받은 행은 그대로 보인다. */
    moreRetryable:
      '조정 이력의 다음 페이지를 불러오지 못했습니다. 대시보드의 카드와 신뢰 상태, 이미 받은 행은 그대로입니다.',
    retryableHelp: '재시도 가능: 조정 이력 다시 불러오기로 다시 요청할 수 있습니다.',
    /** 다음 페이지 읽기가 실패했을 때. `더 보기`가 제자리에 남아 같은 페이지를 다시 요청한다. */
    moreRetryableHelp: '재시도 가능: 더 보기로 같은 페이지를 다시 요청할 수 있습니다.',
    stale:
      '서버가 이 결과를 현재 공식 결과로 확인하지 않아 조정 이력을 보내지 않았습니다. 대시보드의 카드와 신뢰 상태는 바뀌지 않았습니다.',
    staleHelp:
      '같은 요청을 다시 보내도 답은 같습니다. 대시보드를 다시 불러온 뒤 조정 이력을 다시 열어 주세요.',
    denied:
      '이 계정의 조정 이력을 볼 수 없습니다. 계정 삭제가 진행 중이면 이력이 표시되지 않습니다.',
    signedOut: SESSION_EXPIRED,
    signInLink: '로그인 화면으로 이동',
    /** 계산 표시 제한(Story 3.2). 다시 요청해도 같으므로 재시도를 권하지 않는다. */
    restricted:
      '운영상 제한으로 이 결과의 조정 이력을 지금 표시하지 않습니다. 기존 공식 기록은 바뀌거나 지워지지 않으며, 제한이 풀리면 다시 볼 수 있습니다.',
  },
  summary: {
    heading: '합계',
    count: '반영된 외부 자금흐름',
    countValue: (count: number) => `${String(count)}건`,
    net: '부호 있는 USD 합계',
    netHelp:
      '저장된 값을 반올림하지 않고 더한 뒤, 표시할 때만 소수 둘째 자리로 한 번 반올림했습니다.',
    coverage: '확인 구간',
    appliedAt: '공식 반영 시각',
  },
  /** 판정에서 제외한 원천 사건의 수. 유형 필터와 관계없는 판정 전체의 수라서 필터의 합계 묶음 밖에 둔다. */
  excluded: {
    text: (count: number) => `유형과 관계없이 판정에서 제외한 원천 사건 ${String(count)}건`,
    help: 'UTA 안의 손익·수수료·내부 이동처럼 외부 자금흐름이 아니라고 판정한 사건입니다. 아래 행과 합계에 들어가지 않습니다.',
  },
  /** 입력 신뢰도가 `검토 필요`일 때 행과 분리해 붙이는 안내(AC-3). */
  review: {
    heading: '입력 신뢰도: 검토 필요',
    cause: '원인',
    range: '영향 구간',
    where:
      '원인과 다음 행동은 이 화면 위쪽 신뢰 상태의 입력 신뢰도에서 확인할 수 있습니다. 아래 행은 공식 계산에 반영된 사건만이며 검토 항목은 섞이지 않습니다.',
  },
  unknownReviewCause: '입력을 검토해야 하는 원인을 확인하지 못했습니다.',
  filter: {
    legend: '유형',
    options: { ALL: '전체', IN: '외부 입금', OUT: '외부 출금' } as Record<
      AdjustmentDirection,
      string
    >,
  },
  /** 반영된 흐름이 아예 없을 때와 필터 결과만 빌 때. 수집되지 않음·검토 필요와 구분한다. */
  empty: {
    none: '확인된 외부 자금흐름이 없습니다',
    noneHelp: '아래 확인 구간에서 계획에 반영할 외부 입출금이 확인되지 않았습니다.',
    filtered: (type: string) => `${type}으로 반영된 외부 자금흐름이 없습니다.`,
  },
  list: '반영된 외부 자금흐름 목록',
  row: {
    occurredAt: '사건 시각',
    type: '유형',
    quantity: '원수량',
    usd: 'USD 가치',
    classification: '분류',
    price: '가격',
    applied: '반영 버전',
    appliedValue: (sequence: number) => `계산 안 순번 ${String(sequence)}`,
    changed: '변경 표지: 이전 환산 버전과 값이 달라 새 환산 버전으로 반영했습니다.',
    /** 행 제목과 상세 토글의 앞머리. 같은 밀리초·같은 유형의 행도 순번으로 갈린다. */
    title: (sequence: number) => `순번 ${String(sequence)}`,
    detailOpen: (sequence: number, time: string, type: string) =>
      `순번 ${String(sequence)} ${time} ${type} 상세 보기`,
  },
  detail: {
    event: '원 사건',
    sourceId: 'Bybit 거래 기록 ID',
    sourceEventId: '원천 사건 ID',
    transactionType: 'Bybit 거래 기록 유형',
    discoveredAt: '발견 시각',
    discoveredHelp: 'BokriLog가 이 사건을 처음 관측한 시각입니다.',
    classification: '판정',
    classificationId: '판정 ID',
    ruleId: '규칙 ID',
    policy: '정책 버전',
    conversion: '환산',
    conversionId: '환산 ID',
    evidenceType: '근거 종류',
    estimated: '추정 여부',
    unitPrice: '단가',
    route: '경로',
    legs: '가격 구간',
    symbol: '심볼',
    candle: '캔들 시각',
    close: '종가',
    selectionRule: '선택 규칙',
    calculation: '계산',
    calculationId: '계산 ID',
    sequence: '계산 안 순번',
    appliedAt: '반영 시각',
    appliedHelp: '이 계산 버전이 공식 결과가 된 시각입니다.',
    revisions: '변경 이력',
    revisionsHelp: '과거 버전은 덮어쓰지 않고 그대로 남깁니다. 최신 버전부터 나열합니다.',
    earlier: '공식 계산에 쓰이지 않은 과거 버전',
    versionId: '버전 ID',
    processedAt: '처리 시각',
    status: '상태',
    reason: '이유',
    previousValue: '이전값',
    currentValue: '현재 버전의 값',
    /** 반올림하지 않은 전체값. 둘째 자리 밑에서 바뀐 값도 이전값과 현재값이 달라 보이게 한다. */
    fullValue: (value: string) => `전체값 ${value} USD`,
    noValue: '값이 없습니다.',
    changeReason: '바뀐 원인',
    changeDiscoveredAt: '발견 시각',
  },
  estimated: { true: '추정', false: '추정 아님' },
  /** 가격 근거 종류. BYBIT-PRICE-1.0의 두 근거다. */
  evidenceTypes: {
    DIRECT_NORMALIZATION: 'USD·USDT 1단위 = 1 USD 정규화',
    SPOT_KLINE_CLOSE: 'Bybit 현물 1분봉 종가',
  },
  unknownEvidenceType: '확인할 수 없는 가격 근거',
  routes: {
    DIRECT: '직접 정규화',
    SPOT_ASSET_USDT: '현물 자산/USDT',
    SPOT_ASSET_USDC_USDCUSDT: '현물 자산/USDC × USDC/USDT',
  },
  unknownRoute: '확인할 수 없는 경로',
  selectionRules: {
    EVENT_MINUTE: '사건이 속한 종료된 1분봉',
    PRIOR_CLOSED_MINUTE: '사건 전 가장 가까운 종료된 1분봉',
  },
  unknownSelectionRule: '확인할 수 없는 선택 규칙',
  lag: (minutes: number) => `${String(minutes)}분 전`,
  statuses: {
    CONVERTED: '환산됨',
    CONVERTED_ESTIMATED: '추정 환산됨',
    CONVERSION_REQUIRED: '환산 필요',
    REVIEW_REQUIRED: '검토 필요',
  },
  unknownStatus: '확인할 수 없는 상태',
  reasons: CONVERSION_REASONS,
  noReason: '없음',
  unknownReason: '확인할 수 없는 이유',
  changeReasons: {
    PRICE_REVISION: '가격 근거에 새 revision이 생겼습니다.',
    POLICY_CHANGE: '가격 정책 버전이 바뀌었습니다.',
    EVIDENCE_CHANGE: '가격 근거가 바뀌었습니다.',
  },
  unknownChangeReason: '바뀐 원인을 확인할 수 없습니다.',
  units: { usd: 'USD' },
  separators: { range: '–', item: '·' },
} as const
