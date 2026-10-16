/**
 * 금융정보 없는 중립 전환 화면(Story 4.3)의 경로·카피·요청 경로.
 *
 * 화면(`screens/AdTransitionScreen`)과 시험이 같은 문자열을 참조한다. 금지 표현 목록은 `content/copyRules`가 소유하고 시험이
 * 이 모듈의 문자열을 그것으로 훑는다.
 *
 * 이 화면에는 금융값·계획값·신뢰 상태·사용자/결과 식별이 없다 — 문서 제목도 정적 진입 문서의 것 그대로다. 화면이 아는 것은 서버가
 * 보관하는 불투명 transition 참조 하나뿐이고, 그 값은 마운트 직후 주소에서 지운다.
 */

/** 중립 전환 화면 경로. Nginx 허용 목록과 `App.tsx` 경로 맵이 같은 값을 쓴다. 로그인 복귀 목적지가 아니다. */
export const AD_TRANSITION_PATH = '/ad-transition'

/** 화면 경로가 transition 참조를 받는 쿼리 이름. 문서 경계를 넘는 유일한 수단이다(저장소 금지). */
export const AD_TRANSITION_PARAMETER = 't'

/** 광고 자격 판정(Story 4.2). 백엔드 `AdEligibilityController.AD_ELIGIBILITY_PATH`와 같은 값이다. */
export const AD_ELIGIBILITY_PATH = '/api/engagement/ad-eligibility'

/** transition 소비(Story 4.3). 백엔드 `AdTransitionController.AD_TRANSITION_PATH`와 같은 값이다. */
export const AD_TRANSITION_CLAIM_PATH = '/api/engagement/ad-transition'

/**
 * 소비 창(초). 백엔드 `AdTransitionApi.CLAIM_WINDOW_SECONDS`와 같은 값이다(`AdClaimWindowTest`가 대조한다). 중립 화면은 준비된
 * 광고를 이 문서가 시작된 뒤 이 시간까지만 띄울 수 있게 두고, 그때까지 `결과 보기`를 누르지 않았으면 광고를 취소하고 결과로 간다.
 */
export const AD_CLAIM_WINDOW_SECONDS = 120

/** transition 참조의 모양 — 256비트 base64url 무패딩 43자. 서버 스키마 CHECK와 같다. */
export const TRANSITION_PATTERN = /^[A-Za-z0-9_-]{43}$/

/** 그 transition 참조로 중립 화면을 여는 주소. */
export function adTransitionHref(transition: string): string {
  return `${AD_TRANSITION_PATH}?${AD_TRANSITION_PARAMETER}=${encodeURIComponent(transition)}`
}

export const adTransitionContent = {
  /** 광고 표지. 닫기 버튼과 함께 문서의 첫 조작 영역이다. */
  adLabel: '광고',
  /** iframe 밖 네이티브 닫기. 소비 응답 전에도 동작한다. */
  close: '광고 닫기',
  heading: '결과를 준비했습니다',
  /** "광고를 시청하면 결과가 열립니다"가 아니다 — 광고는 결과 조회의 조건이 아니다. */
  body: '광고를 건너뛰어도 최신 결과를 확인할 수 있습니다.',
  /** 공식 trigger인 결과 보기 anchor. 이 링크만 interstitial을 띄울 수 있다. */
  viewResult: '결과 보기',
} as const

/** 화면에 렌더되는 이 모듈의 카피. 금지 표현 시험이 훑는다. */
export const adTransitionCopyStrings: readonly string[] = [
  adTransitionContent.adLabel,
  adTransitionContent.close,
  adTransitionContent.heading,
  adTransitionContent.body,
  adTransitionContent.viewResult,
]
