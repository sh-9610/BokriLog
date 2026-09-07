/*
 * 인증 화면 한국어 카피 정본.
 *
 * 화면과 시험이 같은 문자열을 참조해 문구 표류를 막는다.
 * 근거: epics.md Story 1.4, DESIGN.md Components/social-sign-in, EXPERIENCE.md Voice and Tone,
 *      UX-DR23.
 *
 * 규칙:
 *   - 판정 대신 사실·원인·다음 행동을 짧은 완전문으로 쓴다.
 *   - "실시간", "정확", "안전 보장", 수익 축하·손실 공포·스트릭 표현을 쓰지 않는다.
 *   - 금융값을 넣지 않는다.
 *   - 실패 안내가 계정의 존재 여부를 드러내지 않는다. 서버가 주는 표지는 닫힌 두 값뿐이고
 *     그 어느 것도 "그런 계정이 있다/없다"를 말하지 않는다.
 */

import { entryContent } from './entry'

/**
 * 인증 시작 경로. 최상위 내비게이션 링크의 `href`다.
 *
 * `fetch`가 아니라 링크인 것이 설계다. 서버가 302로 제공자에 보내는 흐름이므로 CSP의
 * `connect-src 'self'`·`form-action 'none'`을 넓히지 않아도 되고, 외부 스크립트도 iframe도 필요 없다.
 */
export const AUTHORIZE_PATH = '/api/auth/authorize/google'

/** 세션 확인 경로. 로그인 상태 경로에서만 부른다. 진입 화면은 이 요청을 보내지 않는다. */
export const SESSION_ENDPOINT = '/api/auth/session'

/** 서버 허용 목록과 같은 내부 경로. 라우터 없이 이 두 값만 화면에 대응한다. */
export const ONBOARDING_PATH = '/onboarding'

export const DASHBOARD_PATH = '/dashboard'

/** 서버가 실패 표지로 쓰는 쿼리 이름. `AuthFlowHandlers`와 같은 값이다. */
export const NOTICE_PARAMETER = 'auth'

/** 서버가 복귀 목적지를 읽는 쿼리 이름. `AuthFlowHandlers`의 `next`와 같은 값이다. */
export const RETURN_DESTINATION_PARAMETER = 'next'

/**
 * 복귀 목적지로 보낼 수 있는 경로.
 *
 * 서버가 가진 닫힌 허용 목록과 같은 집합이다(`AuthFlowHandlers.ALLOWED_DESTINATIONS`). 목록에 없는
 * 값은 서버가 버리므로 여기서도 보내지 않는다 — 보내면 화면이 지키지 못할 약속을 URL에 싣는 것이
 * 된다.
 */
const RETURN_DESTINATIONS: readonly string[] = [ONBOARDING_PATH, DASHBOARD_PATH]

/**
 * 지금 경로에서 시작하는 인증 시작 링크의 `href`.
 *
 * 로그인 상태 경로에 미인증으로 들어와 인증 화면으로 되돌려진 사용자는 로그인 뒤 원래 보려던
 * 화면으로 돌아가야 한다("기존 사용자는 유효한 원래 목적지"). 그 목적지를 서버에 알려 주는 것이
 * `next` 하나이고, 이 함수가 저장소에서 그 값을 만드는 유일한 생산자다. 생산자가 없으면 서버의
 * 허용 목록 판정과 "원래 목적지" 분기가 제품 코드로는 도달할 수 없는 경로로 남는다.
 *
 * 목록에 없는 경로에서는 `next`를 붙이지 않는다. 진입 화면(`/`)이 그 경우이며, 그때 목적지는
 * 서버가 신규·기존으로 갈라 정한다.
 */
export function authorizeHref(pathname: string): string {
  if (!RETURN_DESTINATIONS.includes(pathname)) {
    return AUTHORIZE_PATH
  }
  const query = new URLSearchParams({ [RETURN_DESTINATION_PARAMETER]: pathname })
  return `${AUTHORIZE_PATH}?${query.toString()}`
}

/**
 * 인증 화면이 보여 줄 안내의 종류.
 *
 * `cancelled`·`failed`는 서버가 URL에 실어 보내는 닫힌 두 값이고, `signedOut`은 로그인 상태 경로에서
 * 세션 확인이 미인증으로 온 경우다. 세 값 중 어느 것도 계정의 존재·상태를 알려 주지 않는다.
 */
export type AuthNoticeKind = 'cancelled' | 'failed' | 'signedOut'

/** 서버 표지 → 화면 안내 종류. 목록에 없는 값은 무시한다. */
export function noticeKindOf(search: string): AuthNoticeKind | null {
  const marker = new URLSearchParams(search).get(NOTICE_PARAMETER)
  if (marker === 'cancelled') {
    return 'cancelled'
  }
  if (marker === 'failed') {
    return 'failed'
  }
  return null
}

const NOTICES: Readonly<Record<AuthNoticeKind, string>> = {
  cancelled: '로그인을 끝내지 않았습니다. 아래 버튼으로 다시 시도할 수 있습니다.',
  failed: '로그인을 끝내지 못했습니다. 아래 버튼으로 다시 시도해 주세요.',
  signedOut: '로그인 상태가 아닙니다. 아래 버튼으로 로그인해 주세요.',
}

export const authContent = {
  /** UX-DR23: 모든 핵심 화면에서 이 제목과 바로 아래 고지를 인접 배치한다. */
  pageTitle: entryContent.pageTitle,
  disclaimer: entryContent.disclaimer,
  signIn: {
    id: 'auth-sign-in',
    heading: '로그인 수단 선택',
    /** 접근 가능한 이름은 제공자명 + "계속하기"다(EXPERIENCE.md). */
    googleLabel: 'Google로 계속하기',
    /** DESIGN.md가 버튼 아래에 두라고 정한 안내. */
    boundary:
      '소셜 계정은 본인 확인에만 사용하며 Bybit 자격정보·금융 데이터·계획은 공유하지 않습니다.',
    /** 네이버·카카오는 Story 1.6·1.7이 더한다. 준비 중임을 숨기지 않는다. */
    pending: '네이버·카카오 로그인은 아직 준비 중입니다.',
  },
  notices: NOTICES,
  session: {
    checking: '로그인 상태를 확인하고 있습니다.',
    /** 서버가 다른 목적지를 골랐다. 그 화면으로 문서를 다시 요청하는 동안 보여 준다. */
    redirecting: '로그인 상태에 맞는 화면으로 이동합니다.',
  },
  onboarding: {
    id: 'auth-onboarding',
    heading: '가입이 끝났습니다',
    items: [
      '읽기 전용 Bybit 연결과 복리 계획 만들기는 아직 준비 중입니다.',
      '이 화면에는 계좌·계획·금융 데이터가 아직 없습니다.',
    ],
  },
  dashboard: {
    id: 'auth-dashboard',
    heading: '로그인 상태',
    items: [
      '로그인이 확인됐습니다. 이 화면에는 계좌·계획·금융 데이터가 아직 없습니다.',
      '복리 계획과 기록의 비교는 이후 단계에서 제공합니다.',
    ],
  },
} as const

/** 진입 화면의 로그인 블록에 렌더되어야 하는 카피. 시험이 문구 표류를 감지하는 데 쓴다. */
export const signInCopyStrings: readonly string[] = [
  authContent.signIn.heading,
  authContent.signIn.googleLabel,
  authContent.signIn.boundary,
  authContent.signIn.pending,
]

/** 온보딩 화면의 카피 전부. */
export const onboardingCopyStrings: readonly string[] = [
  authContent.pageTitle,
  authContent.disclaimer,
  authContent.onboarding.heading,
  ...authContent.onboarding.items,
]

/** 로그인 상태 화면의 카피 전부. */
export const dashboardCopyStrings: readonly string[] = [
  authContent.pageTitle,
  authContent.disclaimer,
  authContent.dashboard.heading,
  ...authContent.dashboard.items,
]
