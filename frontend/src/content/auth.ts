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
 * 재인증 시작 경로의 접두사. 뒤에 제공자 등록 id가 붙는다.
 *
 * 인증 시작(`AUTHORIZE_BASE_PATH`)과 다른 경로이고 `POST`다. 무장은 상태 변경이라 세션 귀속
 * CSRF 토큰과 동일 출처 `Origin`을 지나야 하고, 그 다음 단계인 인가 시작은 제공자로 가는 최상위
 * 내비게이션이라 링크여야 한다. 두 단계를 한 경로에 합치면 표지가 붙은 링크 하나로 남의 세션에
 * 재인증 흐름을 심을 수 있다.
 */
export const REAUTHENTICATE_BASE_PATH = '/api/auth/reauthenticate'

/** 그 제공자의 재인증 시작 경로. 경로를 조립하는 유일한 자리다. */
export function reauthenticatePath(provider: SignInProvider): string {
  return `${REAUTHENTICATE_BASE_PATH}/${provider}`
}

/**
 * 연결 표면의 경로. **이 계층에서 그 경로를 정의하는 상수는 이것 하나다.**
 *
 * 두 표면이 한 접두사를 나눠 쓰고 메서드가 그것을 가른다 — `GET /api/auth/link`는 연결 상태
 * 조회이고 `POST /api/auth/link/<제공자>`는 연결 시작이다. 이름을 둘로 두면 "이 경로가 어디
 * 정의됐나"의 답이 둘이 된다.
 *
 * 조회 응답에 실리는 것은 제공자 이름뿐이며 `issuer`·`subject`·이메일·토큰·연결 시각은 오지
 * 않는다 — 서버가 그 값을 담지 않으므로 화면이 실수로 그릴 수도 없다. 시작이 `POST`인 근거는
 * 재인증 시작과 같다 — 허가를 발급하고 세션에 흐름을 심는 상태 변경이라 세션 귀속 CSRF 토큰과
 * 동일 출처 `Origin`을 지나야 한다.
 */
export const LINK_BASE_PATH = '/api/auth/link'

/** 그 제공자의 연결 시작 경로. 경로를 조립하는 유일한 자리다. */
export function linkPath(provider: SignInProvider): string {
  return `${LINK_BASE_PATH}/${provider}`
}

/**
 * 해제 표면의 경로. **이 계층에서 그 경로를 정의하는 상수는 이것 하나다.**
 *
 * 세 표면이 한 접두사를 나눠 쓰고 **경로가** 그것을 가른다 — `POST /api/auth/unlink/<제공자>`는
 * 확인 대화상자를 열 준비, `POST /api/auth/unlink/<제공자>/confirm`은 그것을 소비하는 최종 확인,
 * `POST /api/auth/unlink/<제공자>/cancel`은 그 준비를 되돌리는 취소다. 연결이 메서드로 두 표면을
 * 가른 것과 다른 이유는 셋 다 상태 변경이기 때문이다 — 안전한 메서드로 놓을 수 있는 것이 없다.
 *
 * 준비와 확인을 합치지 않는 이유가 이 스토리의 계약 하나다. 합치면 대화상자를 여는 순간 매핑이
 * 사라지고, "최종 확인 전에는 아무것도 바뀌지 않는다"가 성립하지 않는다.
 *
 * 셋 다 `POST`이고 `/api/auth/` 아래이므로 세션 귀속 CSRF 토큰과 동일 출처 `Origin`을 함께
 * 요구한다(로그아웃·재인증 시작·연결 시작과 같은 계약).
 */
export const UNLINK_BASE_PATH = '/api/auth/unlink'

/** 그 제공자의 해제 준비 경로. 경로를 조립하는 유일한 자리다. */
export function unlinkPath(provider: SignInProvider): string {
  return `${UNLINK_BASE_PATH}/${provider}`
}

/** 그 제공자의 해제 최종 확인 경로. 위 준비 경로 하나에서만 자란다. */
export function unlinkConfirmPath(provider: SignInProvider): string {
  return `${unlinkPath(provider)}/confirm`
}

/**
 * 그 제공자의 해제 취소 경로. 준비가 만든 무장과 허가를 되돌린다.
 *
 * 준비가 서버 상태를 만드는 설계라 되돌리는 짝이 있어야 한다 — 없으면 화면의 "바뀐 것은 없습니다"가
 * 거짓이 되고 아무도 참조하지 않는 허가가 수명을 산다. 연결에는 이 짝이 없다: 그쪽의 취소는 제공자
 * 콜백으로 돌아오므로 서버가 그 사실을 알지만, 해제의 취소는 브라우저 안에서만 일어난다.
 */
export function unlinkCancelPath(provider: SignInProvider): string {
  return `${unlinkPath(provider)}/cancel`
}

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

/**
 * 로그인 수단 설정 화면.
 *
 * 연결 조작이 사는 유일한 자리다. 이 경로를 더할 때 **다섯 곳**을 함께 고친다 — 아래
 * `RETURN_DESTINATIONS`, `App.tsx`의 경로 맵, `useSessionState`의 목적지 집합,
 * `deploy/nginx/bokrilog.conf`의 허용 목록 정규식, 그리고 서버의
 * `AuthFlowHandlers.ALLOWED_DESTINATIONS`. 다섯이 같은 집합이어야 하고 그 등가를 보는 검사는 아직
 * 없다 — 한 곳만 고치면 증상이 제각각이다.
 */
export const SETTINGS_PATH = '/settings'

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
const RETURN_DESTINATIONS: readonly string[] = [ONBOARDING_PATH, DASHBOARD_PATH, SETTINGS_PATH]

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
 * `cancelled`·`failed`는 **서버만** 만든다. `reauthCancelled`는 **서버만** 만들고,
 * `reauthFailed`는 **양쪽이** 만든다 — 콜백을 지난 모든 재인증 실패는 서버가 붙이고, 무장 요청
 * 자체가 성립하지 않은 경우(403·네트워크 단절·시한 초과)는 콜백에 닿지 않으므로 클라이언트가
 * 같은 값을 붙인다(`reauthNoticePath`). 재인증 표지 둘이 로그인 표지 둘과 값을 나누는 이유는
 * 목적지다 — 앞의 둘은 인증 화면으로, 뒤의 둘은 재인증이 붙들어 둔 목적지로 온다. 같은 값을 쓰면
 * 인증 화면의 "다시 로그인해 주세요" 문장이 이미 로그인한 사용자에게 렌더된다.
 *
 * `loggedOut`·`expired`·`logoutFailed`는 **클라이언트만** 만든다 — 로그아웃 성공·세션 만료·
 * 로그아웃 실패를 서버가 응답으로 갈라 주면 세션의 존재 이력이 새므로, 그 구분은 브라우저의
 * 메모리에만 있다(`useSessionState`, `signOut`). `signedOut`은 로그인 상태 경로에 처음부터
 * 미인증으로 들어온 경우이며 URL 표지 없이 화면이 직접 고른다.
 *
 * `linkCancelled`·`linkFailed`는 재인증 표지와 같은 규칙으로 갈린다 — 콜백을 지난 모든 연결
 * 실패는 서버가 붙이고, 시작 요청 자체가 성립하지 않은 경우(403·네트워크 단절·시한 초과)는
 * 클라이언트가 같은 값을 붙인다(`linkNoticePath`). `linkSucceeded`는 **서버만** 만든다.
 * 셋 다 목적지가 설정 화면이다.
 *
 * `linkSucceeded`가 실패가 아닌 유일한 표지인 이유는 비대칭 때문이다. 화면은 조작의 시작을
 * `role="status"`로 알리는데 결과를 알릴 재료가 없으면 스크린리더 사용자에게 그 조작은 끝나지
 * 않은 것과 같고, 왕복 뒤의 문서는 새로 열리므로 "방금 연결했다"를 아는 값은 주소의 표지뿐이다.
 *
 * `unlinkCancelled`·`unlinkFailed`·`unlinkSucceeded`는 **클라이언트만** 만들고 URL에 실리지
 * 않는다. 해제에는 제공자 왕복이 없어 문서를 떠나지 않으므로 표지를 주소에 나를 이유가 없고,
 * 결과는 같은 문서의 상태 하나로 남는다 — `signedOut`이 URL 집합에서 빠진 것과 같은 자리이며
 * 아래 `URL_NOTICE_KINDS`가 그 사실을 소유한다.
 *
 * 열네 값 중 어느 것도 계정의 존재·상태를 알려 주지 않는다. 재인증 실패 표지 하나에 타 사용자
 * 식별자·미연결 식별자·증거 부재·삭제 전환이 접혀 있고, 연결 실패 표지 하나에 이미 연결됨·다른
 * 계정에 연결됨·허가 만료·세션 불일치·삭제 상태가 접혀 있고, 해제 실패 표지 하나에 마지막 수단·
 * 미연결·허가 만료·세션 불일치·삭제 상태가 접혀 있으며, 두 성공 표지는 무엇을 연결·해제했는지
 * 말하지 않는다.
 */
export type AuthNoticeKind =
  | 'cancelled'
  | 'expired'
  | 'failed'
  | 'linkCancelled'
  | 'linkFailed'
  | 'linkSucceeded'
  | 'loggedOut'
  | 'logoutFailed'
  | 'reauthCancelled'
  | 'reauthFailed'
  | 'signedOut'
  | 'unlinkCancelled'
  | 'unlinkFailed'
  | 'unlinkSucceeded'

/**
 * 클라이언트가 **인증 화면** URL에 실을 수 있는 표지.
 *
 * `noticePath`의 입력이며, 서버가 만드는 `cancelled`·`failed`와 화면이 직접 고르는 `signedOut`이
 * 여기 없다. 셋을 섞으면 어느 계층이 어떤 표지를 만드는지가 타입에서 사라진다.
 *
 * `reauthFailed`도 여기 없다. 클라이언트가 그 값을 만들기는 하지만(`reauthNoticePath`) 목적지가
 * 인증 화면이 아니라 **지금 경로**다 — 그 표지를 보는 사용자는 이미 로그인한 상태이고, 이
 * 타입에 넣으면 `noticePath('reauthFailed')`가 타입 검사를 통과해 그 사람을 로그인 화면으로
 * 보내는 경로가 열린다.
 */
export type ClientNoticeKind = 'expired' | 'loggedOut' | 'logoutFailed'

/**
 * 재인증 패널이 보여 줄 수 있는 표지.
 *
 * `AuthNoticeKind`의 진부분집합이다. 패널이 `AuthNoticeKind`를 그대로 받으면 재인증과 무관한
 * 표지도 그 안에서 렌더되고, `/dashboard?auth=loggedOut`이 "로그아웃했습니다 … 다시 로그인해
 * 주세요"를 아직 로그인해 있는 사용자에게 보여 준다. 타입을 좁혀 두면 두 화면이 URL 값을
 * 거르는 일을 타입 검사가 강제한다(`reauthNoticeKindOf`).
 */
export type ReauthNoticeKind = 'reauthCancelled' | 'reauthFailed'

/**
 * 설정 화면이 보여 줄 수 있는 표지.
 *
 * `ReauthNoticeKind`와 같은 근거의 같은 배치다. 설정 화면이 `AuthNoticeKind`를 그대로 받으면
 * `/settings?auth=loggedOut`이 "로그아웃했습니다 … 다시 로그인해 주세요"를 아직 로그인해 있는
 * 사용자에게 보여 준다. 타입을 좁혀 두면 화면이 URL 값을 거르는 일을 타입 검사가 강제한다
 * (`linkNoticeKindOf`).
 */
export type LinkNoticeKind = 'linkCancelled' | 'linkFailed' | 'linkSucceeded'

/**
 * 설정 화면이 **자기 상태로** 만드는 해제 표지.
 *
 * `LinkNoticeKind`와 갈라 두는 이유는 출처다. 연결 표지는 왕복 뒤의 새 문서가 주소에서 읽고, 해제
 * 표지는 같은 문서가 자기 요청의 결과로 만든다. 한 타입으로 합치면 `linkNoticeKindOf`가 주소에서
 * 해제 표지를 읽을 수 있게 되고, 그 순간 아무 해제도 하지 않은 사용자가 `/settings?auth=
 * unlinkSucceeded`에서 "해제했습니다"를 본다 — 연결 표지와 달리 그 문장을 만들 수 있는 서버 경로가
 * 아예 없으므로 그것은 순수한 거짓이다.
 */
export type UnlinkNoticeKind = 'unlinkCancelled' | 'unlinkFailed' | 'unlinkSucceeded'

/**
 * **인증 화면**이 보여 줄 수 있는 표지.
 *
 * 재인증·연결 표지가 여기 없다. 그 문장들은 로그인한 사용자에게만 참이고, 진입 화면에서
 * 렌더되면 거짓을 말한다 — `/?auth=linkFailed`가 본인 확인도 연결도 없는 화면에서 "아래에서
 * 본인 확인을 마친 뒤 다시 연결해 주세요"를 보여 주는 것이 그 모양이다. `ReauthNoticeKind`·
 * `LinkNoticeKind`가 두 인증 화면과 설정 화면을 좁히는 것과 같은 방식으로, 이 타입이 진입
 * 화면을 좁힌다.
 */
export type EntryNoticeKind =
  'cancelled' | 'expired' | 'failed' | 'loggedOut' | 'logoutFailed' | 'signedOut'

/**
 * URL 표지로 인정하는 값 전부. 목록에 없는 값은 무시한다.
 *
 * 한 목록이다. 값마다 분기를 두면 다음 표지가 한쪽에만 추가되는 자리가 생기고, 그때 그 값은
 * 조용히 무시된다 — 로그아웃한 사용자가 아무 안내도 없는 진입 화면을 보는 결함이 정확히 그 모양이다.
 *
 * **해제 표지 셋이 여기 없다.** 그 값들은 URL을 지나지 않는다 — 해제에는 제공자 왕복이 없어 문서가
 * 바뀌지 않으므로 표지를 주소에 실어 나를 이유가 없고, 여기 넣으면 어떤 서버 경로도 만들지 않는
 * 문장을 주소창이 만들어 낼 수 있게 된다. `signedOut`이 같은 이유로 빠져 있다(그 값은 세션 확인
 * 결과가 고른다). 조용히 무시되는 값이 생기지 않는다는 이 목록의 목적은 그대로다 — 세 값의 소비자는
 * 설정 화면의 상태 하나이고 타입이 그 연결을 강제한다(`UnlinkNoticeKind`).
 */
const URL_NOTICE_KINDS: readonly AuthNoticeKind[] = [
  'cancelled',
  'expired',
  'failed',
  'linkCancelled',
  'linkFailed',
  'linkSucceeded',
  'loggedOut',
  'logoutFailed',
  'reauthCancelled',
  'reauthFailed',
]

/**
 * 그중 **인증 화면**이 보여 줄 수 있는 값. 위 목록의 진부분집합이다.
 *
 * 재인증·연결 표지를 여기서 뺀다. 화면마다 보여 줄 수 있는 표지를 좁히는 일을 세 거름망이
 * 나눠 갖고(`noticeKindOf`·`reauthNoticeKindOf`·`linkNoticeKindOf`), 그래야 어느 주소도 그
 * 화면에서 거짓인 문장을 만들 수 없다.
 */
const ENTRY_NOTICE_KINDS: readonly EntryNoticeKind[] = [
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
 * 화면이 자기 확인 결과와 무관한 문장을 URL만으로 띄운다. 나머지 아홉은 주소창에서 설정할 수
 * 있다 — 서버가 만드는 `cancelled`·`failed`·`reauthCancelled`·`reauthFailed`·`linkCancelled`·
 * `linkFailed`도, 클라이언트가 만드는 `expired`·`loggedOut`·`logoutFailed`도 그렇다. 어느 값도
 * 계정의 존재·상태를 알려 주지 않고 로그인 상태를 바꾸지도 않으므로, 그 설정 가능성 자체는
 * 위험이 아니다. 표지는 문장 하나를 고를 뿐이다.
 */
function urlNoticeKindOf(search: string): AuthNoticeKind | null {
  const marker = new URLSearchParams(search).get(NOTICE_PARAMETER)
  return URL_NOTICE_KINDS.find((kind) => kind === marker) ?? null
}

/**
 * 주소의 표지 중 **인증 화면에 속하는 것만** 돌려준다.
 *
 * 진입 화면이 쓰는 거름망이다. 재인증·연결 표지는 로그인한 사용자의 화면으로 오는 값이라 여기서
 * 걸러진다 — 걸러 내지 않으면 `/?auth=linkFailed`가 본인 확인도 연결도 없는 화면에서 그 안내를
 * 렌더한다.
 */
export function noticeKindOf(search: string): EntryNoticeKind | null {
  const kind = urlNoticeKindOf(search)
  return ENTRY_NOTICE_KINDS.find((candidate) => candidate === kind) ?? null
}

/** 재인증 패널이 보여 줄 수 있는 표지 둘. 위 목록의 진부분집합이다. */
const REAUTH_NOTICE_KINDS: readonly ReauthNoticeKind[] = ['reauthCancelled', 'reauthFailed']

/**
 * 주소의 표지 중 **재인증에 속하는 것만** 돌려준다.
 *
 * 두 인증 화면이 패널에 넘기기 전에 지나는 거름망이다. `noticeKindOf`를 그대로 넘기면
 * `/dashboard?auth=loggedOut`이 "로그아웃했습니다"를 아직 로그인해 있는 사용자에게 보여 주고,
 * 그 문장은 자기 화면에 앉아 있는 사람에게 거짓이다. 거름을 화면마다 손으로 쓰지 않는 이유는
 * 두 화면이 갈라질 자리를 만들지 않기 위해서다.
 */
export function reauthNoticeKindOf(search: string): ReauthNoticeKind | null {
  const kind = urlNoticeKindOf(search)
  return REAUTH_NOTICE_KINDS.find((candidate) => candidate === kind) ?? null
}

/** 설정 화면이 보여 줄 수 있는 표지 셋. 위 목록의 진부분집합이다. */
const LINK_NOTICE_KINDS: readonly LinkNoticeKind[] = [
  'linkCancelled',
  'linkFailed',
  'linkSucceeded',
]

/**
 * 주소의 표지 중 **연결에 속하는 것만** 돌려준다.
 *
 * `reauthNoticeKindOf`와 같은 근거의 같은 거름망이다. `noticeKindOf`를 그대로 넘기면
 * `/settings?auth=loggedOut`이 "로그아웃했습니다"를 아직 로그인해 있는 사용자에게 보여 준다.
 */
export function linkNoticeKindOf(search: string): LinkNoticeKind | null {
  const kind = urlNoticeKindOf(search)
  return LINK_NOTICE_KINDS.find((candidate) => candidate === kind) ?? null
}

/**
 * 그 표지를 달고 **인증 화면**을 여는 경로.
 *
 * 표지를 URL에 싣는 생산자는 이 모듈에 둘뿐이고 이것이 그중 하나다(다른 하나는 아래
 * `reauthNoticePath`이며 목적지가 지금 경로다). 문자열을 직접 조립하는 자리가 늘면
 * `noticeKindOf`의 닫힌 집합과 갈라져 조용히 무시되는 값이 생긴다.
 */
export function noticePath(kind: ClientNoticeKind): string {
  const query = new URLSearchParams({ [NOTICE_PARAMETER]: kind })
  return `${ENTRY_PATH}?${query.toString()}`
}

/**
 * 재인증 실패 표지를 달고 지금 화면을 다시 여는 경로.
 *
 * 인증 화면이 아니라 <b>지금 경로</b>다. 이 표지를 보는 사용자는 이미 로그인한 상태이고 화면은
 * 자기 목적지이므로, 로그인 화면으로 보내면 표지가 설명하려는 상황과 화면이 어긋난다. 서버도
 * 같은 값을 붙들어 둔 목적지에 달아 보낸다(`AuthFlowHandlers`) — 두 생산자가 같은 모양을 만드는
 * 것이 요점이고, 그래서 화면은 표지 하나만 읽으면 된다.
 *
 * 재인증 시작이 성립하지 않은 경우에만 클라이언트가 이 값을 만든다(`startReauthentication`).
 * 그 밖의 모든 재인증 실패는 콜백을 지나므로 서버가 만든다.
 */
export function reauthNoticePath(pathname: string): string {
  const kind: ReauthNoticeKind = 'reauthFailed'
  const query = new URLSearchParams({ [NOTICE_PARAMETER]: kind })
  return `${pathname}?${query.toString()}`
}

/**
 * 연결 실패 표지를 달고 설정 화면을 다시 여는 경로.
 *
 * `reauthNoticePath`와 같은 근거의 같은 모양이다. 목적지가 고정 경로인 것이 다른 점이며, 연결
 * 조작이 사는 화면이 하나이기 때문이다 — 서버도 같은 경로에 같은 표지를 달아 보낸다
 * (`AuthFlowHandlers`).
 *
 * 연결 시작이 성립하지 않은 경우에만 클라이언트가 이 값을 만든다(`startLinking`). 그 밖의 모든
 * 연결 실패는 콜백을 지나므로 서버가 만든다.
 */
export function linkNoticePath(): string {
  const kind: LinkNoticeKind = 'linkFailed'
  const query = new URLSearchParams({ [NOTICE_PARAMETER]: kind })
  return `${SETTINGS_PATH}?${query.toString()}`
}

const NOTICES: Readonly<Record<AuthNoticeKind, string>> = {
  cancelled: '로그인을 끝내지 않았습니다. 아래 버튼으로 다시 시도할 수 있습니다.',
  expired: '로그인 상태가 만료되어 화면을 닫았습니다. 아래 버튼으로 다시 로그인해 주세요.',
  failed: '로그인을 끝내지 못했습니다. 아래 버튼으로 다시 시도해 주세요.',
  /**
   * 사용자가 연결을 스스로 끝내지 않았다.
   *
   * 재인증 취소와 문장을 나누는 이유는 사용자가 한 일이 다르기 때문이다. 이 문장을 보는 사람은
   * 로그인 수단을 연결하려다 그만둔 것이고, "본인 확인을 끝내지 않았습니다"는 그 상황을 틀리게
   * 설명한다.
   */
  linkCancelled: '로그인 수단 연결을 끝내지 않았습니다. 아래에서 다시 시도할 수 있습니다.',
  /**
   * 그 밖의 모든 연결 실패.
   *
   * 무엇이 어긋났는지 말하지 않는다. 이미 연결된 수단, 다른 계정에 연결된 식별자, 만료된 허가,
   * 세션 불일치가 모두 이 한 문장으로 접히며, 값을 나누는 순간 그 표지가 **다른 계정의 존재와
   * 소유자**를 알려 주는 신호가 된다.
   *
   * 다음 행동을 함께 말한다. 연결에는 최근 본인 확인이 필요하고 그것이 가장 흔한 원인이므로,
   * 이유를 특정하지 않으면서 사용자가 할 수 있는 일 하나를 가리킨다.
   */
  linkFailed: '로그인 수단을 연결하지 못했습니다. 아래에서 본인 확인을 마친 뒤 다시 연결해 주세요.',
  /**
   * 연결이 끝났다.
   *
   * 무엇을 연결했는지 말하지 않는다. 대상 제공자를 표지에 실으면 그 값이 주소·`Referer`·
   * 히스토리에 남고, 아래 목록이 어차피 지금 상태를 보여 준다.
   */
  linkSucceeded: '로그인 수단을 연결했습니다. 아래 목록에서 확인할 수 있습니다.',
  loggedOut: '로그아웃했습니다. 다시 이용하려면 아래 버튼으로 로그인해 주세요.',
  /**
   * 로그아웃 요청이 성립하지 않았다.
   *
   * 세션이 아직 살아 있을 수 있다는 사실을 숨기지 않는다. 로그아웃했다고 말하면 사용자는 끝나지
   * 않은 세션을 끝난 것으로 믿고, 아무 문장도 없으면 무엇이 일어났는지 알 수 없다.
   */
  logoutFailed:
    '로그아웃을 끝내지 못했습니다. 로그인 상태가 남아 있을 수 있습니다. 아래 버튼으로 다시 로그인해 확인해 주세요.',
  /**
   * 사용자가 본인 확인을 스스로 끝내지 않았다.
   *
   * 로그인 취소와 문장을 나누는 이유는 사용자가 있는 자리가 다르기 때문이다. 이 문장을 보는
   * 사람은 이미 로그인한 상태이고 화면은 자기 목적지이며, "다시 로그인해 주세요"는 그 상황을
   * 틀리게 설명한다.
   */
  reauthCancelled: '본인 확인을 끝내지 않았습니다. 아래 버튼으로 다시 시도할 수 있습니다.',
  /**
   * 그 밖의 모든 재인증 실패.
   *
   * 무엇이 어긋났는지 말하지 않는다. 타 사용자의 식별자, 어느 계정에도 없는 식별자, 제공자가
   * 증명하지 않은 인증 시각이 모두 이 한 문장으로 접히며, 값을 나누는 순간 그 표지가 계정의
   * 존재나 연결 상태를 알려 주는 신호가 된다.
   */
  reauthFailed: '본인 확인을 끝내지 못했습니다. 아래 버튼으로 다시 시도해 주세요.',
  signedOut: '로그인 상태가 아닙니다. 아래 버튼으로 로그인해 주세요.',
  /**
   * 사용자가 확인 대화상자를 닫았다.
   *
   * 무엇도 바뀌지 않았다는 사실을 함께 말한다. 대화상자를 여는 요청이 서버에 이미 나갔으므로
   * "아무 일도 없었다"가 참인지 사용자가 스스로 알 방법이 없고, 그 침묵은 불안으로 남는다.
   */
  unlinkCancelled: '로그인 수단 연결 해제를 취소했습니다. 바뀐 것은 없습니다.',
  /**
   * 그 밖의 모든 해제 실패.
   *
   * 무엇이 어긋났는지 말하지 않는다. 마지막 수단, 연결되지 않은 제공자, 만료된 허가, 세션
   * 불일치가 모두 이 한 문장으로 접힌다 — 마지막 수단만은 화면이 목록에서 **미리** 설명하므로
   * 정상 경로에서는 이 문장에 닿지 않는다.
   *
   * 다음 행동을 함께 말한다. 해제에는 최근 본인 확인이 필요하고 그것이 가장 흔한 원인이므로,
   * 이유를 특정하지 않으면서 사용자가 할 수 있는 일 하나를 가리킨다(`linkFailed`와 같은 판단).
   */
  unlinkFailed:
    '로그인 수단 연결을 해제하지 못했습니다. 아래에서 본인 확인을 마친 뒤 다시 시도해 주세요.',
  /**
   * 해제가 끝났다.
   *
   * 무엇을 해제했는지 말하지 않는다. 아래 목록이 어차피 지금 상태를 보여 준다.
   *
   * 다른 기기에 일어난 일을 함께 말한다. 그것은 사용자가 관측할 수 없는 곳에서 일어난 변화이고,
   * 말하지 않으면 다른 기기에서 갑자기 로그아웃된 이유를 알 수 없다.
   *
   * "모두 종료됐습니다"라고 쓰지 않는다. 서버의 폐기는 조회와 삭제 사이에 커밋된 로그인을 놓칠 수
   * 있으므로(`SessionGuard.SessionStore.REVOKE_ATTEMPTS`) 그 문장은 드물게 거짓이 된다. 대신
   * 사용자가 할 일을 말한다 — 그것은 어느 경우에도 참이다.
   */
  unlinkSucceeded:
    '로그인 수단 연결을 해제했습니다. 아래 목록에서 확인할 수 있습니다. 다른 기기에서는 다시 로그인해야 합니다.',
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
  /**
   * 두 인증 화면이 공유하는 재인증 패널의 카피.
   *
   * 연결된 제공자를 묻지도 말하지도 않는다. 어느 수단으로 가입했는지를 화면에 도달시키는 경로가
   * 곧 계정 소속 신호이므로(진입 화면의 복구 안내가 세 제공자를 언제나 함께 보여 주는 것과 같은
   * 이유) 여기서도 세 버튼을 그대로 보이고, 연결되지 않은 제공자를 고른 결과는 다른 실패와
   * 구분되지 않는 하나로 수렴한다.
   */
  reauth: {
    id: 'auth-reauth',
    heading: '본인 확인이 필요합니다',
    body: '이 로그인 상태를 계속 사용하려면 아래 로그인 수단 중 하나로 본인 확인을 다시 해 주세요.',
    /**
     * 확인이 성립하는 조건.
     *
     * 어느 제공자인지 말하지 않으면서 "아무거나 눌러도 된다"는 오해만 막는다. 이 문장이 없으면
     * 연결되지 않은 수단을 고른 사용자는 실패 표지 하나만 보고 이유를 짐작할 수 없다.
     */
    boundary: '이 계정에 연결된 로그인 수단으로만 본인 확인을 끝낼 수 있습니다.',
    /** 화면당 하나뿐인 `role="status"` 영역이 말할 문장. */
    status: '본인 확인이 필요합니다.',
    /**
     * 진행 중 상태.
     *
     * 같은 live region이 말한다. 버튼 안의 문구를 바꾸는 것은 live region이 아니어서 보조기술에
     * 전달되지 않고, 그 문구가 버튼의 접근 가능한 이름이 되면 컨트롤의 이름이 동작 아닌 상태가
     * 된다. 중복 제출을 막는 것은 이 문구가 아니라 `useReauthentication`의 가드다.
     */
    pending: '본인 확인 화면으로 이동하고 있습니다.',
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
  /*
   * 두 목록은 계획이 있든 없든 참이어야 한다(Story 2.7). 첫 공식 현재가치와 활성 계획의 결과가 같은
   * 화면에 나오므로 "금융 데이터가 아직 없습니다" 같은 부재 문장은 쓰지 않는다.
   */
  onboarding: {
    id: 'auth-onboarding',
    heading: '가입이 끝났습니다',
    items: [
      '설정에서 읽기 전용 Bybit 연결을 마치면 첫 공식 현재가치를 확인할 수 있습니다.',
      '복리 계획은 첫 공식 현재가치를 기준으로 계획 조건을 확인한 뒤 만듭니다.',
    ],
  },
  dashboard: {
    id: 'auth-dashboard',
    heading: '로그인 상태',
    items: [
      '로그인이 확인됐습니다.',
      '활성 계획의 공식 결과는 최초 계획을 발행한 뒤 이 화면에 표시됩니다.',
    ],
  },
  /**
   * 로그인 수단 설정 화면.
   *
   * 여기에는 계정의 사실 하나만 있다 — 세 제공자 각각이 이 계정에 연결됐는가. `issuer`·
   * `subject`·이메일·연결 시각은 서버가 보내지 않으므로 화면이 그릴 수도 없다.
   *
   * 연결된 수단에는 상태와 해제 조작이 함께 있고, 남은 수단이 하나뿐이면 그 조작은 비활성이며
   * 이유를 문장으로 말한다(Story 1.11). 마지막 수단 보호의 권위는 서버의 삭제 문장이고, 화면이
   * 그것을 미리 설명하는 이유는 접근성이다 — 비활성화된 버튼에 이유가 없으면 조작할 수 없는
   * 까닭을 알 길이 없다.
   */
  settings: {
    id: 'auth-settings',
    heading: '로그인 수단',
    /**
     * 두 로그인 상태 화면이 이 화면으로 가는 길.
     *
     * 이 링크가 없으면 주소를 직접 쳐야만 닿는 화면이 되고, 이 스토리가 없애려던 문제("사용자가
     * 자기 로그인 수단을 볼 자리가 없다")가 그대로 남는다. 전역 내비게이션은 아직 없으므로
     * (Epic 1의 범위가 아니다) 머리말의 링크 하나가 그 자리다.
     */
    link: '로그인 수단 설정',
    body: '이 계정에 연결된 로그인 수단입니다. 연결된 수단 중 어느 것으로도 이 계정에 로그인할 수 있습니다.',
    /**
     * 제공자의 표시 이름.
     *
     * 로그인 버튼의 라벨에서 잘라 쓰지 않는다. 잘라 쓰면 그 라벨의 문구를 바꾸는 날 이 화면의
     * 이름이 조용히 함께 바뀌고, 두 자리의 규칙이 문자열 조작 하나에 묶인다.
     */
    providerNames: {
      google: 'Google',
      naver: '네이버',
      kakao: '카카오',
    },
    /** 연결된 수단의 상태. 연결 시각도 식별자도 말하지 않는다. */
    linked: '연결됨',
    /**
     * 연결 조작의 라벨.
     *
     * 로그인 버튼의 "계속하기"와 문구를 나눈다. 이 조작은 로그인이 아니라 지금 계정에 수단을
     * 더하는 일이고, 같은 문구를 쓰면 화면에 같은 이름의 컨트롤이 둘 생겨 보조기술 사용자가
     * 어느 것이 무엇인지 구분할 수 없다.
     */
    linkLabels: {
      google: 'Google 계정 연결하기',
      naver: '네이버 계정 연결하기',
      kakao: '카카오 계정 연결하기',
    },
    /**
     * 해제 조작의 라벨.
     *
     * 제공자마다 다른 문구다. 화면에 해제 버튼이 최대 셋 있고 이름이 같으면 보조기술 사용자가
     * 어느 것이 무엇인지 구분할 수 없다 — `linkLabels`와 같은 근거의 같은 배치다.
     */
    unlinkLabels: {
      google: 'Google 계정 연결 해제',
      naver: '네이버 계정 연결 해제',
      kakao: '카카오 계정 연결 해제',
    },
    /**
     * 마지막 남은 수단이라 해제할 수 없다.
     *
     * 이 문장이 없으면 비활성화된 버튼에 이유가 없다. 서버 응답은 마지막 수단을 다른 거부와
     * 구분하지 않지만(구분하면 그 차이가 계정의 수단 구성을 알려 준다) 이 사실은 화면이 자기
     * 목록에서 직접 만든다 — 사용자가 이미 보고 있는 것이다.
     *
     * 다음 행동을 함께 말한다. 해제하려면 수단을 하나 더 연결해야 하고, 그 조작은 같은 화면에
     * 있다.
     */
    lastMethod: '마지막 남은 접근 수단이라 해제할 수 없습니다. 다른 수단을 먼저 연결해 주세요.',
    /** 해제 준비·실행이 도는 중. `role="status"` 영역이 같은 자리에서 말한다. */
    unlinkPending: '로그인 수단 연결 해제를 처리하고 있습니다.',
    /**
     * 확인 대화상자.
     *
     * DESIGN.md `confirmation-dialog`가 규정한 형태다 — 제목에 **대상과 결과**를 쓰고, **삭제되는
     * 것과 보존되는 것을 분리**하며, 기본 포커스는 취소다. 대상명 재입력은 요구하지 않는다: 그
     * 요구는 같은 표가 "계정 삭제처럼 범위가 큰 작업"에만 건 것이고(Epic 5), 해제는 남은 수단으로
     * 계정에 그대로 닿을 수 있는 조작이다.
     *
     * 제공자 이름을 문자열 조작으로 끼워 넣지 않는다. 닫힌 맵이 제목 셋을 직접 들고 있으므로
     * 제공자가 늘 때 문구를 사람이 쓰게 되고, 조사·띄어쓰기가 어색해지는 경로가 없다.
     */
    unlinkDialog: {
      titleId: 'auth-unlink-dialog-title',
      descriptionId: 'auth-unlink-dialog-description',
      titles: {
        google: 'Google 계정 연결을 해제할까요?',
        naver: '네이버 계정 연결을 해제할까요?',
        kakao: '카카오 계정 연결을 해제할까요?',
      },
      /** 비가역 결과. 무엇이 사라지는지 먼저 말한다. */
      removed:
        '해제하면 이 수단으로는 더 이상 이 계정에 로그인할 수 없습니다. 같은 수단을 다시 쓰려면 본인 확인을 거쳐 다시 연결해야 하며, 자동으로 다시 연결되지 않습니다.',
      /** 보존되는 것. 삭제되는 것과 문단을 나눈다(DESIGN.md `confirmation-dialog`). */
      kept: '계정과 남은 로그인 수단은 그대로 유지됩니다. 이 조작은 계정을 삭제하지 않습니다.',
      /**
       * 세션에 일어나는 일. 사용자가 관측할 수 없는 변화라 미리 말한다.
       *
       * 결과 표지와 같은 규율로 "모두"를 약속하지 않는다 — 말하는 것은 이 화면이 남는다는 사실과
       * 다른 기기에서 해야 할 일이다.
       */
      sessions: '해제를 마치면 이 화면의 로그인만 남고 다른 기기에서는 다시 로그인해야 합니다.',
      /**
       * 최종 확인 조작.
       *
       * 문구가 동작을 말한다. "확인"처럼 무엇을 하는지 말하지 않는 라벨을 쓰지 않는 것은 이
       * 대화상자가 비가역 조작을 다루기 때문이다.
       */
      confirm: '연결 해제',
      /** 취소. **기본 포커스가 여기 있다**(DESIGN.md `confirmation-dialog`). */
      cancel: '취소',
    },
    /** 연결·해제가 요구하는 것과 요구하지 않는 것. 목록 아래에 둔다. */
    boundary:
      '연결과 해제에는 최근 본인 확인이 필요합니다. 연결된 수단은 본인 확인에만 사용하며 Bybit 자격정보·금융 데이터·계획은 공유하지 않습니다.',
    /** 화면당 하나뿐인 `role="status"` 영역이 말할 문장. 연결 상태를 확인하는 동안이다. */
    status: '로그인 수단을 확인하고 있습니다.',
    /**
     * 연결 상태를 확인하지 못했다.
     *
     * 빈 목록으로 접지 않는다. 접으면 일시적 실패에서 **이미 연결된 수단에도 연결 버튼이
     * 그려지고**, 그 클릭은 서버가 거절해 "본인 확인을 마친 뒤 다시 연결해 주세요"라는 엉뚱한
     * 안내로 끝난다. 확인하지 못한 것과 연결된 것이 없는 것은 다른 사실이다.
     */
    unavailable: '로그인 수단을 확인하지 못했습니다. 아래 버튼으로 다시 확인해 주세요.',
    /** 다시 확인하는 조작. 자동 재시도하지 않는 것이 이 버튼이 있는 이유다. */
    retry: '로그인 수단 다시 확인',
    /** 연결 시작이 도는 중. `role="status"` 영역이 같은 자리에서 말한다. */
    pending: '로그인 수단 연결 화면으로 이동하고 있습니다.',
    /**
     * 연결 전에 지나야 하는 본인 확인.
     *
     * 이 자리가 있어야 "두 클릭"이 성립한다. 재인증이 신선하지 않으면 연결 시작이 거부되고,
     * 그때 사용자가 할 수 있는 일은 본인 확인 하나뿐이다. 자동으로 이어 하지 않는 것이 계약이며
     * (AC-6) 그래서 이 조작은 사용자가 직접 누른다.
     *
     * 연결된 제공자를 묻지 않는다. 세 버튼을 그대로 보이고, 연결되지 않은 수단을 고른 결과는
     * 다른 실패와 구분되지 않는 표지 하나로 수렴한다 — 재인증 패널과 같은 판단이다.
     */
    reauth: {
      id: 'auth-settings-reauth',
      heading: '본인 확인',
      /**
       * 확인 뒤 어디로 가는지를 사실대로 적는다.
       *
       * 재인증의 복귀 목적지는 세션이 로그인 시각에 붙들어 둔 값이고(Story 1.8) 이 화면이 아니다.
       * 미인증으로 `/settings`를 열어 로그인한 경우에만 그 값이 이 화면이 된다. "돌아옵니다"로
       * 적으면 대개 거짓이므로, 로그인 상태 화면의 설정 링크로 다시 오면 된다고 말한다.
       */
      body: '로그인 수단을 연결하거나 해제하려면 먼저 이 계정에 연결된 수단으로 본인 확인을 해 주세요. 확인을 마치면 로그인 상태 화면으로 돌아가며, 거기서 이 설정 화면을 다시 열어 이어서 진행할 수 있습니다.',
      labels: {
        google: 'Google로 본인 확인하기',
        naver: '네이버로 본인 확인하기',
        kakao: '카카오로 본인 확인하기',
      },
    },
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

/**
 * 재인증 패널에 렌더되어야 하는 카피.
 *
 * 두 화면의 `*CopyStrings`와 따로 둔다. 그 배열들은 게이트가 `ready`일 때의 카피 목록이고 이
 * 문장들은 표시된 세션에서만 나타난다. 한 배열에 섞으면 정상 화면의 문구 단정이 늘 실패한다.
 *
 * UX-DR23의 두 문장과 제공자 버튼 셋, 로그아웃 라벨이 함께 든다 — 패널이 화면을 통째로 차지하는
 * 상태이므로 그 화면에도 제목과 고지가 인접해 있어야 한다.
 */
export const reauthCopyStrings: readonly string[] = [
  authContent.pageTitle,
  authContent.disclaimer,
  authContent.reauth.heading,
  authContent.reauth.body,
  authContent.reauth.boundary,
  ...SIGN_IN_PROVIDERS.map((provider) => authContent.signIn.labels[provider]),
  authContent.signOut.label,
]

/** 온보딩 화면의 카피 전부. */
export const onboardingCopyStrings: readonly string[] = [
  authContent.pageTitle,
  authContent.disclaimer,
  authContent.onboarding.heading,
  ...authContent.onboarding.items,
  authContent.settings.link,
  authContent.signOut.label,
]

/** 로그인 상태 화면의 카피 전부. */
export const dashboardCopyStrings: readonly string[] = [
  authContent.pageTitle,
  authContent.disclaimer,
  authContent.dashboard.heading,
  ...authContent.dashboard.items,
  authContent.settings.link,
  authContent.signOut.label,
]

/**
 * 설정 화면에서 **언제나** 렌더되는 카피.
 *
 * 연결 라벨은 여기 없다. 그 버튼은 아직 연결되지 않은 제공자에만 나타나므로 계정 상태에 따라
 * 렌더 여부가 갈리고, 이 배열에 섞으면 모든 수단이 연결된 계정의 문구 단정이 늘 실패한다.
 * 연결 여부에 따른 렌더는 화면 시험이 제공자마다 따로 본다.
 */
export const settingsCopyStrings: readonly string[] = [
  authContent.pageTitle,
  authContent.disclaimer,
  authContent.settings.heading,
  authContent.settings.body,
  authContent.settings.boundary,
  authContent.settings.reauth.heading,
  authContent.settings.reauth.body,
  ...SIGN_IN_PROVIDERS.map((provider) => authContent.settings.reauth.labels[provider]),
  authContent.signOut.label,
]

/**
 * 확인 대화상자에 렌더되어야 하는 카피 전부.
 *
 * `settingsCopyStrings`와 따로 둔다. 그 배열은 화면이 언제나 그리는 카피의 목록이고 이 문장들은
 * 대화상자가 열렸을 때만 나타난다 — 한 배열에 섞으면 대화상자를 열지 않은 화면의 문구 단정이 늘
 * 실패한다(`reauthCopyStrings`와 같은 근거).
 *
 * 제목은 여기 없다. 제공자마다 다른 값이라 대화상자 시험이 그 제공자의 제목을 직접 본다.
 */
export const unlinkDialogCopyStrings: readonly string[] = [
  authContent.settings.unlinkDialog.removed,
  authContent.settings.unlinkDialog.kept,
  authContent.settings.unlinkDialog.sessions,
  authContent.settings.unlinkDialog.confirm,
  authContent.settings.unlinkDialog.cancel,
]
