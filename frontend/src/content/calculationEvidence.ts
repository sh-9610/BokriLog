/**
 * 현재 공식 계산의 근거 카피(Story 2.9).
 *
 * 근거는 읽기 전용이다. 새로 계산하거나 동기화하지 않고, 지금 화면에 보이는 공식 결과 한 묶음이 어느
 * 스냅샷·시각·입력·정책·계산 버전에서 나왔는지만 말한다. 값을 해석하지 않으며 투자성과·추천으로 읽힐
 * 문장, 원천 입력이 없는 추측을 쓰지 않는다. 서버가 보낸 코드는 아래 닫힌 맵으로만 카피에 잇고, 모르는
 * 코드는 글자 그대로 보이지 않고 이 파일의 안전한 카피로 접는다(`lookup`).
 *
 * 시각은 넷을 각자 이름으로 둔다. Bybit는 잔고와 입출금 기록을 한 시점으로 묶는 공통 스냅샷 토큰을 주지
 * 않으므로 현재가치 측정 시각과 외부 자금흐름 확인 시각은 서로 다른 두 사실이다 — 하나의 "최신 시각"처럼
 * 말하지 않는다(AD-8).
 */

import { planPublicationContent } from './planResult'

/** 근거 조회. 지금 보이는 묶음의 공식 포인터 세 값을 쿼리로 보내고, 서버는 그것이 현재 포인터인지만 판정한다. */
export const DASHBOARD_EVIDENCE_PATH = '/api/dashboard/evidence'

/** 근거를 요청하는 묶음의 참조. 대시보드 응답이 준 세 값 그대로다. */
export interface EvidenceRequest {
  readonly planId: string
  readonly pointerVersion: number
  readonly calculationId: string
}

/** 근거 조회 URL. 세 값만 싣는다 — 금융값·사용자 식별자는 URL에 두지 않는다. */
export function evidencePath(reference: EvidenceRequest): string {
  const query = new URLSearchParams({
    planId: reference.planId,
    pointerVersion: String(reference.pointerVersion),
    calculationId: reference.calculationId,
  })
  return `${DASHBOARD_EVIDENCE_PATH}?${query.toString()}`
}

const SESSION_EXPIRED = '세션이 만료되었습니다. 로그인 상태를 확인한 뒤 다시 시도하세요.'

/**
 * 닫힌 맵에서 코드의 카피를 찾는다. 맵은 제 키 집합을 타입으로 지닌 채이고, 서버가 보낸 모르는 코드는
 * `fallback`으로 접혀 글자 그대로 보이지 않는다.
 */
export function lookup<K extends string>(
  table: Readonly<Record<K, string>>,
  code: string,
  fallback: string,
): string {
  return Object.hasOwn(table, code) ? table[code as K] : fallback
}

export const calculationEvidenceContent = {
  /** 펼침 버튼. 이동이 아니라 같은 화면의 상태 변경이라 링크가 아니라 버튼이다. */
  open: '계산 근거 보기',
  close: '계산 근거 닫기',
  retry: '계산 근거 다시 불러오기',
  heading: '계산 근거',
  intro:
    '지금 표시된 공식 결과 한 묶음이 어느 스냅샷·시각·입력·정책·계산 버전에서 나왔는지 보여 줍니다. 읽기 전용이며 새로 계산하지 않습니다.',
  /**
   * 화면의 단일 상태 리전이 대시보드 문장 뒤에 이어 읽는 문장. 근거 영역에는 live region이 없고, 읽는
   * 중에는 영역도 같은 `loading` 문장을 보인다.
   */
  status: {
    loading: '계산 근거를 불러오는 중입니다.',
    ready: '계산 근거를 표시합니다.',
    retryable: '계산 근거를 불러오지 못했습니다. 대시보드의 값은 바뀌지 않았습니다.',
    stale: '이 결과의 계산 근거를 찾지 못했습니다. 대시보드의 값은 바뀌지 않았습니다.',
    denied: '이 계정의 계산 근거를 지금 볼 수 없습니다.',
    signedOut: '세션이 만료되어 계산 근거를 불러오지 못했습니다.',
  },
  /** 읽기가 끝나지 않았을 때. 대시보드 카드·세 축·상태는 그대로이고, 메모리의 최소 참조만 보인다. */
  failure: {
    retryable: '계산 근거를 불러오지 못했습니다. 대시보드의 카드와 신뢰 상태는 바뀌지 않았습니다.',
    retryableHelp: '재시도 가능: 계산 근거 다시 불러오기로 다시 요청할 수 있습니다.',
    stale:
      '서버가 이 결과를 현재 공식 결과로 확인하지 않아 계산 근거를 보내지 않았습니다. 대시보드의 카드와 신뢰 상태는 바뀌지 않았습니다.',
    staleHelp:
      '같은 요청을 다시 보내도 답은 같습니다. 대시보드를 다시 불러온 뒤 계산 근거를 다시 열어 주세요.',
    minimalReference: '최소 참조',
    snapshotId: '공식 현재가치 스냅샷 ID',
    calculationId: '계산 ID',
    denied:
      '이 계정의 계산 근거를 볼 수 없습니다. 계정 삭제가 진행 중이면 근거가 표시되지 않습니다.',
    signedOut: SESSION_EXPIRED,
    signInLink: '로그인 화면으로 이동',
  },
  /** 여섯 절. 이 순서가 AC-1·AC-7의 읽기 순서다. */
  sections: {
    snapshots: '스냅샷',
    times: '시각',
    inputs: '입력 버전',
    calculation: '계산 버전',
    metrics: '지표 영향',
    versions: '버전 목록',
  },
  snapshots: {
    portfolio: '공식 현재가치 스냅샷 ID',
    portfolioSequence: '발행 순번',
    basis: '계획 기준 스냅샷 ID',
    basisSource: '계획 시작 시각의 원천 스냅샷 ID',
    basisSourceHelp: '계획 시작 시각(t0)에 Bybit 조회에서 만든 원천 스냅샷입니다.',
    source: '계산 기준 시각의 원천 스냅샷 ID',
    sourceHelp: '계산 기준 시각(tc)에 Bybit 조회에서 만든 원천 스냅샷입니다.',
  },
  times: {
    t0: '계획 시작 시각 (t0)',
    t0Help: '계획의 시작 현재가치를 고정한 시각입니다.',
    tc: '계산 기준 시각 (tc)',
    tcHelp:
      '이 계산이 기준으로 삼은 시각입니다. 계획금액과 입출금 반영은 이 시각까지로 계산했습니다.',
    measuredAt: '현재가치 측정 시각',
    measuredAtHelp: 'Bybit 지갑 조회가 현재가치를 측정한 시각입니다.',
    verifiedThrough: '외부 자금흐름 확인 시각',
    verifiedThroughHelp: '이 시각까지의 입출금 기록을 확인했습니다.',
    /** AC-2: 공통 스냅샷 토큰이 있는 것처럼 말하지 않는다. */
    noCommonSnapshot:
      'Bybit는 잔고와 입출금 기록을 한 시점으로 묶는 공통 스냅샷을 제공하지 않습니다. 그래서 현재가치 측정 시각과 외부 자금흐름 확인 시각을 따로 기록합니다.',
    same: '이 결과에서는 두 시각이 같습니다. 같은 시각이어도 두 값은 서로 다른 조회에서 따로 확인한 것입니다.',
    different:
      '이 결과에서는 두 시각이 다릅니다. 두 시각 사이에 생긴 입출금은 두 기준 가운데 한쪽에만 반영됐을 수 있습니다.',
  },
  inputs: {
    connection: '연결 출처',
    provider: '제공자',
    connectionId: '연결 ID',
    connectionPolicy: '연결 정책 버전',
    connectionPolicyHelp: '첫 스냅샷이 이 연결의 정책과 대조해 기록한 버전입니다.',
    sourceRange: '원천 범위',
    range: '수집 구간',
    /** Story 2.11: 이 계산 버전을 만든 동기화가 Bybit 기록을 다시 읽기 시작한 시각. */
    windowStart: '이번 수집 시작 시각',
    windowStartHelp: '이 계산 버전을 만든 수집이 Bybit 입출금 기록을 다시 읽기 시작한 시각입니다.',
    /** 창 시작이 t0보다 뒤일 때만 붙는다. */
    windowStartCarried: '그 앞 구간은 직전 공식 계산의 수집 결과를 이어받았습니다.',
    /** 창 시작이 t0일 때(최초 발행, 또는 계획 시작부터 다시 읽은 동기화). */
    windowStartFromT0: '계획 시작 시각(t0)부터 읽었습니다.',
    collectionPolicy: '수집 정책',
    completenessHash: '완전성 hash',
    classification: '외부 자금흐름 판정',
    classificationId: '판정 ID',
    classificationPolicy: '판정 정책',
    inputScopeHash: '입력 범위 hash',
    conversion: '사건 시점 USD 환산',
    conversionId: '환산 ID',
    pricePolicy: '가격 정책',
    resultFingerprint: '결과 fingerprint',
    cashflows: '외부 자금흐름',
    cashflowCounts: (applied: number, estimated: number) =>
      `반영 ${String(applied)}건 · 그중 추정 환산 ${String(estimated)}건`,
    plan: '계획 버전',
    planId: '계획 ID',
  },
  calculation: {
    calculationId: '계산 ID',
    calculationPolicy: '계산 정책',
    calendarPolicy: '달력 정책',
    displayPolicy: '표시 정책',
    precisionPolicy: '정밀도 정책',
    timeZone: '시간대',
    implementationVersion: '구현 버전',
    inputHash: '입력 hash',
    resultHash: '결과 hash',
    createdAt: '생성 시각',
  },
  metrics: {
    nature: '계산 성격',
    reason: '계산 불가 이유',
    cause: '추정 원인',
    /** 현재가치는 Bybit가 측정한 값이라 입출금 환산이 닿지 않는다. */
    currentValueCause: 'Bybit가 측정한 값이라 입출금 환산이 전파되지 않습니다.',
    noEstimatedCause: '추정 가격으로 환산한 입출금이 없습니다.',
    estimatedCause: (evidence: string, count: number) =>
      `${evidence}(추정 가격 근거)로 환산한 입출금 ${String(count)}건이 반영됐습니다.`,
  },
  /** 추정 가격 근거 종류. BYBIT-PRICE-1.0의 추정 근거는 종료된 현물 1분봉 종가 하나다. */
  evidenceTypes: { SPOT_KLINE_CLOSE: 'Bybit 현물 1분봉 종가' },
  unknownEvidenceType: '확인할 수 없는 가격 근거',
  providers: { BYBIT: 'Bybit' },
  unknownProvider: '확인할 수 없는 제공자',
  /** 지표의 계산 성격. 계산 불가는 성격이 아니라 따로 적는 이유다. */
  natures: { OFFICIAL: '공식', ESTIMATED: '추정' },
  unknownNature: '확인할 수 없는 계산 성격',
  /** 발행되지 않은 시도의 상태. 대시보드 발행 섹션과 같은 말을 쓴다. */
  attemptStatuses: {
    FAILED: planPublicationContent.failed,
    BLOCKED: planPublicationContent.blocked,
  },
  unknownAttemptStatus: '확인할 수 없는 상태',
  /** 금액·비율의 단위와 구간·항목의 구분 기호. */
  units: { usd: 'USD', percent: '%' },
  separators: { range: '–', item: '·' },
  versions: {
    official: '공식 버전',
    /** Story 2.11: 공식 버전은 동기화가 발행할 때마다 하나씩 늘고, 앞 버전은 다음 버전이 발행된 시각에 끝난다. */
    officialHelp:
      '발행 순서대로 보입니다. 이전 공식 버전은 다음 버전이 발행되면서 적용이 끝났고, 지금 표시된 값은 현재 공식 버전의 것입니다.',
    order: '발행 순서',
    effectiveFrom: '적용 시작',
    effectiveUntil: '적용 끝',
    current: '현재 공식 버전입니다.',
    earlier: '이전 공식 버전입니다.',
    calculationId: '계산 ID',
    unpublished: '발행되지 않은 시도',
    unpublishedHelp:
      '공식 결과가 아닙니다. 아무 값도 발행하지 않은 시도의 읽기 전용 기록이며 위 공식 버전과 섞이지 않습니다.',
    none: '없습니다.',
    status: '상태',
    reason: '이유',
    finishedAt: '종료 시각',
  },
} as const
