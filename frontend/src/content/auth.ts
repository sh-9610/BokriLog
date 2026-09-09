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
 * 화면이 여는 제공자. 고정 순서다.
 *
 * DESIGN.md가 Google → 네이버 → 카카오 순서를 정했고 Story 1.7이 세 번째를 채웠다. 배열의 순서가
 * 곧 문서 순서이므로 이 목록 하나가 그 계약을 나른다 — 화면이 순서를 다시 적으면 두 곳이 갈라진다.
 *
 * 서버가 아는 이름과 같은 값이다(`AuthSecurityConfig`의 registration id). 갈라지면 인증 시작이
 * 404로 끝난다.
 */
export const SIGN_IN_PROVIDERS = ['google', 'naver', 'kakao'] as const

export type SignInProvider = (typeof SIGN_IN_PROVIDERS)[number]

/**
 * 인증 시작 경로의 접두사. 뒤에 제공자 등록 id가 붙는다.
 *
 * `fetch`가 아니라 링크인 것이 설계다. 서버가 302로 제공자에 보내는 흐름이므로 CSP의
 * `connect-src 'self'`·`form-action 'none'`을 넓히지 않아도 되고, 외부 스크립트도 iframe도 필요 없다.
 */
export const AUTHORIZE_BASE_PATH = '/api/auth/authorize'

/** 그 제공자의 인증 시작 경로. 경로를 조립하는 유일한 자리다. */
export function authorizePath(provider: SignInProvider): string {
  return `${AUTHORIZE_BASE_PATH}/${provider}`
}

/** 세션 확인 경로. 로그인 상태 경로에서만 부른다. 진입 화면은 이 요청을 보내지 않는다. */
export const SESSION_ENDPOINT = '/api/auth/session'

/**
 * 로그아웃 경로. 상태 변경이므로 `POST`이며 세션 귀속 CSRF 토큰과 동일 출처 `Origin`을 함께
 * 요구한다. `Origin`은 브라우저가 싣는다 — 스크립트가 그 헤더를 만들 수 없다는 것이 이 검사의
 * 근거다.
 */
export const LOGOUT_ENDPOINT = '/api/auth/logout'

/**
 * CSRF 토큰을 싣는 요청 헤더 이름.
 *
 * 백엔드 `AuthSecurityConfig.CSRF_HEADER_NAME`과 같은 값이다. 두 계층이 한 계약을 나눠 갖는
 * 자리이며 갈라지면 로그아웃이 403으로만 끝난다. 토큰을 쿼리 문자열로 보내지 않는다 — URL은
 * 로그·`Referer`·히스토리에 남는다.
 */
export const CSRF_HEADER = 'X-CSRF-TOKEN'

/** 인증 화면의 경로. 로그아웃·실패는 모두 이 화면으로 수렴한다. */
export const ENTRY_PATH = '/'

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
 * 지금 경로에서 시작하는 그 제공자의 인증 시작 링크 `href`.
 *
 * 로그인 상태 경로에 미인증으로 들어와 인증 화면으로 되돌려진 사용자는 로그인 뒤 원래 보려던
 * 화면으로 돌아가야 한다("기존 사용자는 유효한 원래 목적지"). 그 목적지를 서버에 알려 주는 것이
 * `next` 하나이고, 이 함수가 저장소에서 그 값을 만드는 유일한 생산자다. 생산자가 없으면 서버의
 * 허용 목록 판정과 "원래 목적지" 분기가 제품 코드로는 도달할 수 없는 경로로 남는다.
 *
 * 제공자가 늘어도 `next`의 생산자는 계속 이 함수 하나다. 제공자마다 링크 조립을 따로 두면 그
 * 파라미터의 규칙이 갈라진다.
 *
 * 목록에 없는 경로에서는 `next`를 붙이지 않는다. 진입 화면(`/`)이 그 경우이며, 그때 목적지는
 * 서버가 신규·기존으로 갈라 정한다.
 */
export function authorizeHref(provider: SignInProvider, pathname: string): string {
  const path = authorizePath(provider)
  if (!RETURN_DESTINATIONS.includes(pathname)) {
    return path
  }
  const query = new URLSearchParams({ [RETURN_DESTINATION_PARAMETER]: pathname })
  return `${path}?${query.toString()}`
}

/**
 * 인증 화면이 보여 줄 안내의 종류.
 *
 * `cancelled`·`failed`는 **서버가** URL에 실어 보내는 닫힌 두 값이다. `loggedOut`·`expired`·
 * `logoutFailed`는 **클라이언트가** 만든다 — 로그아웃 성공·세션 만료·로그아웃 실패를 서버가 응답으로
 * 갈라 주면 세션의 존재 이력이 새므로, 그 구분은 브라우저의 메모리에만 있다(`useSessionState`,
 * `signOut`). `signedOut`은 로그인 상태 경로에 처음부터 미인증으로 들어온 경우이며 URL 표지 없이
 * 화면이 직접 고른다.
 *
 * 여섯 값 중 어느 것도 계정의 존재·상태를 알려 주지 않는다.
 */
export type AuthNoticeKind =
  'cancelled' | 'expired' | 'failed' | 'loggedOut' | 'logoutFailed' | 'signedOut'

/**
 * 클라이언트가 URL에 실을 수 있는 표지.
 *
 * `noticePath`의 입력이며, 서버가 만드는 `cancelled`·`failed`와 화면이 직접 고르는 `signedOut`이
 * 여기 없다. 셋을 섞으면 어느 계층이 어떤 표지를 만드는지가 타입에서 사라진다.
 */
export type ClientNoticeKind = 'expired' | 'loggedOut' | 'logoutFailed'

/**
 * URL 표지로 인정하는 값 전부. 목록에 없는 값은 무시한다.
 *
 * 한 목록이다. 값마다 분기를 두면 다음 표지가 한쪽에만 추가되는 자리가 생기고, 그때 그 값은
 * 조용히 무시된다 — 로그아웃한 사용자가 아무 안내도 없는 진입 화면을 보는 결함이 정확히 그 모양이다.
 */
const URL_NOTICE_KINDS: readonly AuthNoticeKind[] = [
  'cancelled',
  'expired',
  'failed',
  'loggedOut',
  'logoutFailed',
]

/**
 * URL 표지 → 화면 안내 종류. 목록에 없는 값은 무시한다.
 *
 * `signedOut`만 이 집합에서 빠진다. 그 표지는 URL이 아니라 세션 확인 결과가 고르며, 여기 넣으면
 * 화면이 자기 확인 결과와 무관한 문장을 URL만으로 띄운다. 나머지 다섯은 주소창에서 설정할 수
 * 있다 — 서버가 만드는 `cancelled`·`failed`도, 클라이언트가 만드는 `expired`·`loggedOut`·
 * `logoutFailed`도 그렇다. 어느 값도 계정의 존재·상태를 알려 주지 않고 로그인 상태를 바꾸지도
 * 않으므로, 그 설정 가능성 자체는 위험이 아니다. 표지는 문장 하나를 고를 뿐이다.
 */
export function noticeKindOf(search: string): AuthNoticeKind | null {
  const marker = new URLSearchParams(search).get(NOTICE_PARAMETER)
  return URL_NOTICE_KINDS.find((kind) => kind === marker) ?? null
}

/**
 * 그 표지를 달고 인증 화면을 여는 경로.
 *
 * 표지를 URL에 싣는 유일한 생산자다. 문자열을 직접 조립하는 자리가 늘면 `noticeKindOf`의 닫힌
 * 집합과 갈라져 조용히 무시되는 값이 생긴다.
 */
export function noticePath(kind: ClientNoticeKind): string {
  const query = new URLSearchParams({ [NOTICE_PARAMETER]: kind })
  return `${ENTRY_PATH}?${query.toString()}`
}

const NOTICES: Readonly<Record<AuthNoticeKind, string>> = {
  cancelled: '로그인을 끝내지 않았습니다. 아래 버튼으로 다시 시도할 수 있습니다.',
  expired: '로그인 상태가 만료되어 화면을 닫았습니다. 아래 버튼으로 다시 로그인해 주세요.',
  failed: '로그인을 끝내지 못했습니다. 아래 버튼으로 다시 시도해 주세요.',
  loggedOut: '로그아웃했습니다. 다시 이용하려면 아래 버튼으로 로그인해 주세요.',
  /**
   * 로그아웃 요청이 성립하지 않았다.
   *
   * 세션이 아직 살아 있을 수 있다는 사실을 숨기지 않는다. 로그아웃했다고 말하면 사용자는 끝나지
   * 않은 세션을 끝난 것으로 믿고, 아무 문장도 없으면 무엇이 일어났는지 알 수 없다.
   */
  logoutFailed:
    '로그아웃을 끝내지 못했습니다. 로그인 상태가 남아 있을 수 있습니다. 아래 버튼으로 다시 로그인해 확인해 주세요.',
  signedOut: '로그인 상태가 아닙니다. 아래 버튼으로 로그인해 주세요.',
}

export const authContent = {
  /** UX-DR23: 모든 핵심 화면에서 이 제목과 바로 아래 고지를 인접 배치한다. */
  pageTitle: entryContent.pageTitle,
  disclaimer: entryContent.disclaimer,
  signIn: {
    id: 'auth-sign-in',
    heading: '로그인 수단 선택',
    /**
     * 제공자별 버튼 라벨. 접근 가능한 이름은 제공자명 + "계속하기"다(EXPERIENCE.md).
     *
     * 두 문구가 서로 달라야 한다. 브라우저 시험이 `Tab` 도달 순서를 요소의 텍스트로 비교하므로
     * 같은 문구를 두면 어느 버튼에 닿았는지 그 층이 구분하지 못한다.
     *
     * 네이버 공식 버튼 가이드는 문구 변경을 허용한다. 그래서 "네이버로 계속하기"가 가이드와
     * 충돌하지 않으며, 세 제공자의 라벨 형태가 하나로 유지된다.
     *
     * 카카오 가이드는 `카카오 로그인`·`로그인`을 **권장 문구**로 공표하지만 문구 변경을 금지
     * 목록에 넣지 않았다(금지는 심볼의 형태·비율·색 변경, CI 대체, 기능 아이콘 사용, 심볼 제거,
     * 지정 팔레트 밖 색이다). `epics.md` AC-1과 DESIGN.md의 접근 가능한 이름 규칙이 제공자명 +
     * "계속하기" 형태를 요구하므로 "카카오로 계속하기"를 쓴다(2026-09-09 사람의 결정). 심볼과
     * 색은 공표값을 그대로 지킨다.
     */
    labels: {
      google: 'Google로 계속하기',
      naver: '네이버로 계속하기',
      kakao: '카카오로 계속하기',
    },
    /** DESIGN.md가 버튼 아래에 두라고 정한 안내. */
    boundary:
      '소셜 계정은 본인 확인에만 사용하며 Bybit 자격정보·금융 데이터·계획은 공유하지 않습니다.',
  },
  notices: NOTICES,
  session: {
    checking: '로그인 상태를 확인하고 있습니다.',
    /** 서버가 다른 목적지를 골랐다. 그 화면으로 문서를 다시 요청하는 동안 보여 준다. */
    redirecting: '로그인 상태에 맞는 화면으로 이동합니다.',
  },
  /** 두 인증 화면이 공유하는 로그아웃 조작. 화면마다 문구를 따로 두지 않는다. */
  signOut: {
    /**
     * 버튼의 접근 가능한 이름. 진행 중에도 이 값이다.
     *
     * 동작을 말하고 판정·보장 표현을 쓰지 않는다. 진행 중 문구로 이름을 바꾸면 컨트롤의 이름이
     * 동작 아닌 상태가 된다.
     */
    label: '로그아웃',
    /**
     * 진행 중 상태.
     *
     * 화면당 하나뿐인 `role="status"` 영역이 이 문장을 말한다. 버튼 안의 문구를 바꾸는 것은
     * live region이 아니어서 보조기술에 전달되지 않는다. 중복 제출을 막는 것은 이 문구가 아니라
     * `useSignOut`의 가드다.
     */
    pending: '로그아웃하는 중입니다.',
  },
  /**
   * 로그인 수단에 접근을 잃은 사용자의 복구 경로.
   *
   * **세 제공자를 언제나 함께 보여 준다.** 진입 화면은 사용자가 어느 제공자로 가입했는지 알 수
   * 없고 알아서도 안 된다 — 그 정보를 화면에 도달시키는 경로가 곧 계정 존재·소속 신호다(서버가
   * 실패 표지를 닫힌 두 값으로 둔 것과 같은 이유). 그래서 "당신이 쓴 제공자의 복구 절차"는 만들
   * 수 없고, 남는 배치는 전부를 무조건 함께 보여 주는 것 하나다. 이 배치는 아무것도 누출하지
   * 않는다.
   *
   * 이메일 확인이나 지원 담당자 확인으로 인증을 우회하는 경로를 만들지 않으며, 그 사실을 카피가
   * 직접 말한다. 어떤 문장도 계정의 존재 여부를 드러내지 않는다.
   */
  recovery: {
    id: 'auth-recovery',
    heading: '로그인 수단에 접근할 수 없다면',
    body: '로그인 수단에 다시 접근할 수 없다면 해당 제공자의 공식 복구 절차를 이용해 주세요.',
    /**
     * 제공자별 공식 복구 안내. 주소는 각 제공자가 소유한다.
     *
     * 같은 창에서 열고 `rel`로 참조 유출을 막는다. 순서는 위 `SIGN_IN_PROVIDERS`와 같다 —
     * 버튼과 안내가 다른 순서로 나오면 사용자가 두 목록을 대응시킬 수 없다.
     *
     * 세 주소는 실측한 값이다. 네이버 계정 찾기(`https://nid.naver.com/user2/help/idInquiry`)와
     * 카카오 계정 찾기(`https://accounts.kakao.com/weblogin/find_account`)는 2026-09-09에
     * **HTTP 200**으로 해석된다(각 SPEC의 "검증된 외부 사실"에 같은 사실을 적었다). 이 링크는
     * 화면이 외부로 내보내는 유일한 자리이므로 주소를 추측으로 적지 않는다 — 죽은 주소는 "복구
     * 절차를 이용해 주세요"라는 문장을 지키지 못하는 약속으로 만든다.
     */
    links: [
      {
        provider: 'google',
        label: 'Google 공식 계정 복구 안내 열기',
        href: 'https://accounts.google.com/signin/recovery',
      },
      {
        provider: 'naver',
        label: '네이버 공식 계정 찾기 안내 열기',
        href: 'https://nid.naver.com/user2/help/idInquiry',
      },
      {
        provider: 'kakao',
        label: '카카오 공식 계정 찾기 안내 열기',
        href: 'https://accounts.kakao.com/weblogin/find_account',
      },
    ],
    caveat: '이메일 확인이나 문의만으로는 로그인할 수 없습니다.',
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
  ...SIGN_IN_PROVIDERS.map((provider) => authContent.signIn.labels[provider]),
  authContent.signIn.boundary,
]

/**
 * 인증 실패 표지에서만 렌더되는 복구 안내.
 *
 * `signInCopyStrings`와 따로 둔다. 그 배열은 표지 없는 진입 화면에서도 전부 렌더돼야 하는 카피의
 * 목록이고, 이 문장들은 `failed` 표지에서만 나타난다. 한 배열에 섞으면 표지 없는 화면의 문구
 * 단정이 늘 실패한다.
 */
export const recoveryCopyStrings: readonly string[] = [
  authContent.recovery.heading,
  authContent.recovery.body,
  ...authContent.recovery.links.map((link) => link.label),
  authContent.recovery.caveat,
]

/** 온보딩 화면의 카피 전부. */
export const onboardingCopyStrings: readonly string[] = [
  authContent.pageTitle,
  authContent.disclaimer,
  authContent.onboarding.heading,
  ...authContent.onboarding.items,
  authContent.signOut.label,
]

/** 로그인 상태 화면의 카피 전부. */
export const dashboardCopyStrings: readonly string[] = [
  authContent.pageTitle,
  authContent.disclaimer,
  authContent.dashboard.heading,
  ...authContent.dashboard.items,
  authContent.signOut.label,
]
