/**
 * 서비스 공지(Story 3.5)의 카피와 경로.
 *
 * 화면(`components/ServiceNotice`)과 시험이 같은 문자열을 참조한다. 대시보드와 설정 화면이 쓴다 — 운영자 카피 모듈
 * (`content/operator`)에 기대지 않는다(그 모듈은 운영자 chunk 전용이다).
 *
 * 금지 표현(실시간·정확 보장·수익 축하·손실 공포·스트릭·배지)을 쓰지 않는다. 공지는 무엇이 멈췄고 무엇은 계속 쓸 수
 * 있는지만 말한다 — 재시도를 권하지 않고, 기능을 허용한다고 주장하지 않는다.
 */

/** 인증 사용자의 공지 조회. 백엔드 `AuthSecurityConfig.NOTICES_PATH`와 같은 값이다. */
export const SERVICE_NOTICES_PATH = '/api/notices'

/** 공지 상태. 서버 `ServiceNoticeApi.NoticeStatus`와 같은 집합이다. */
export const NOTICE_STATUSES = ['INVESTIGATING', 'SCOPE_CHANGED', 'RECOVERED'] as const

export type NoticeStatus = (typeof NOTICE_STATUSES)[number]

/** 공지가 말하는 기능. 서버 `RestrictionFeature`와 같은 닫힌 집합이다. */
export const NOTICE_FEATURES = [
  'NEW_CONNECTION',
  'SYNC',
  'CALCULATION_DISPLAY',
  'ADVERTISING',
] as const

export type NoticeFeature = (typeof NOTICE_FEATURES)[number]

/** 공지 항목의 종류. 공지이거나, 효력 중인 제한을 덮는 공지가 없는 기본 항목이다. */
export type NoticeKind = 'NOTICE' | 'RESTRICTED_WITHOUT_NOTICE'

/** 상태 표지. 색이 아니라 이 문장이 상태를 말한다. */
const STATE_LABELS: Readonly<Record<NoticeStatus | 'RESTRICTED_WITHOUT_NOTICE', string>> = {
  INVESTIGATING: '조사 중',
  SCOPE_CHANGED: '범위 변경',
  RECOVERED: '복구됨',
  RESTRICTED_WITHOUT_NOTICE: '운영 제한',
}

const FEATURE_LABELS: Readonly<Record<NoticeFeature, string>> = {
  NEW_CONNECTION: '새 거래소 연결',
  SYNC: '동기화와 새 계산 반영',
  CALCULATION_DISPLAY: '계산 결과 표시',
  ADVERTISING: '광고',
}

export const serviceNoticeContent = {
  /** 공지 영역의 제목 id. landmark `region`의 이름이 된다. */
  id: 'service-notice-heading',
  heading: '서비스 공지',
  stateLabel: '상태',
  startedAt: '시작 시각',
  affected: '멈춘 기능',
  continuing: '계속 쓸 수 있는 기능',
  nextUpdateAt: '다음 안내 예정',
  recoveredAt: '복구 시각',
  support: '문의',
  none: '없음',
  /** 효력 중인 제한을 덮는 공지가 아직 없다. 무엇이 멈췄는지만 말한다. */
  restrictedBody:
    '운영상 일부 기능을 잠시 멈췄습니다. 자세한 안내를 준비하고 있습니다. 지금까지의 기록은 그대로 보존됩니다.',
  /** 공지를 읽지 못했고 믿을 수 있는 마지막 응답도 없다. 기능 허용을 주장하지 않는다. */
  unavailable: '서비스 상태를 확인하지 못했습니다.',
  /** 다음 안내 시각이 지났는데 새 버전이 없다. 지난 시각을 예정으로만 말한다. */
  overdue: (time: string): string => `다음 안내가 늦어지고 있습니다(예정 ${time})`,
} as const

/** 상태 → 화면 문장. */
export function noticeStateLabel(kind: NoticeStatus | 'RESTRICTED_WITHOUT_NOTICE'): string {
  return STATE_LABELS[kind]
}

/** 기능 목록 → 화면 문장. 비었으면 `없음`이다. */
export function noticeFeaturesText(features: readonly NoticeFeature[]): string {
  return features.length === 0
    ? serviceNoticeContent.none
    : features.map((feature) => FEATURE_LABELS[feature]).join(', ')
}

/**
 * 기능 code 목록 → 화면 문장(운영자 화면의 미리보기·이력이 사용자 화면과 같은 문장을 쓴다). 모르는 code는 그대로 보인다
 * (운영자 표면이다). 비었으면 `없음`이다.
 */
export function noticeFeatureCodesText(features: readonly string[]): string {
  return features.length === 0
    ? serviceNoticeContent.none
    : features
        .map((feature) =>
          feature in FEATURE_LABELS ? FEATURE_LABELS[feature as NoticeFeature] : feature,
        )
        .join(', ')
}

/**
 * 공지 시각. 서울 시각으로 보이고 `KST`를 붙인다 — 보는 사람의 시간대와 무관하게 같은 글자다. 읽을 수 없는 값은 빈
 * 문자열이다(파서가 이미 걸렀으므로 오지 않는다).
 */
export function noticeTimeText(value: string): string {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return ''
  }
  const text = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(parsed)
  return `${text} KST`
}

/**
 * `datetime-local` 입력(`YYYY-MM-DDTHH:mm`)을 서울 시각(KST, UTC+9)으로 읽어 ISO-8601로 바꾼다. 브라우저의 시간대를 쓰지
 * 않는다. 모양이 아니면 `null`이다.
 */
export function kstInputToIso(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return null
  const parsed = new Date(`${value}:00+09:00`)
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString()
}

/** 시험과 화면이 함께 쓰는 금지 표현 검사 대상. */
export const serviceNoticeCopyStrings: readonly string[] = [
  serviceNoticeContent.heading,
  serviceNoticeContent.restrictedBody,
  serviceNoticeContent.unavailable,
  serviceNoticeContent.overdue(''),
  ...Object.values(STATE_LABELS),
  ...Object.values(FEATURE_LABELS),
]
