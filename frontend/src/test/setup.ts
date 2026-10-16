/*
 * Vitest 전역 설정.
 *
 * 1. Testing Library 매처 등록과 렌더 정리.
 * 2. 네트워크 진입점(fetch, XMLHttpRequest, sendBeacon)을 감시 훅으로 교체해
 *    "외부 요청 0건"을 관측 가능하게 만든다. 요청이 발생하면 기록하고 즉시 실패시킨다.
 * 3. 브라우저 저장소(localStorage, sessionStorage, cookie, IndexedDB) 사용을 관측한다.
 *    금지 목록은 DEVELOPMENT.md의 `## 프런트엔드` 절과 같아야 한다.
 * 4. 시험이 (메서드, URL) 쌍 단위로 명시적으로 열 수 있는 탈출구를 둔다(`stubSessionRequest`,
 *    `stubLogoutRequest`, `stubReauthenticationRequest`, `stubLinkStatusRequest`,
 *    `stubLinkRequest`, `stubUnlinkPrepareRequest`, `stubUnlinkConfirmRequest`,
 *    `stubUnlinkCancelRequest`, `stubConnectionStatusRequest`, `stubConnectionPrepareRequest`,
 *    `stubConnectRequest`, `stubConnectionCancelRequest`, `stubCalculationEvidence`,
 *    `stubAdjustmentHistory`, `stubDashboardSync`, `stubDashboardVerification`, `stubPlanEndPrepare`,
 *    `stubPlanEndConfirm`, `stubPlanEndCancel`).
 *    전역 거부는 그대로다. 아래 `responders` 절의 근거를 참고한다.
 * 5. 서비스 공지 조회(`GET /api/notices`, Story 3.5)만 예외로 기본 응답(빈 공지)이 있다. 아래
 *    `defaultServiceNotices`의 근거를 참고한다. 설정 화면의 문의 목록(`GET /api/support/inquiries`, Story 3.6)도
 *    같은 근거로 기본 응답(빈 목록)이 있다(`defaultSupportInquiries`).
 *    설정 화면의 광고 동의 조회(`GET /api/engagement/ad-consent`, Story 4.1)도 같은 근거로 기본 응답(미결정)이 있다
 *    (`defaultAdConsent`). 수동 동기화 성공 뒤의 광고 자격 판정(`POST /api/engagement/ad-eligibility`, Story 4.3)도
 *    기본 응답(공급자 꺼짐으로 건너뜀 — 기본 구성의 답)이 있다(`defaultAdEligibility`).
 */

import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'

import { AD_CONSENT_PATH, AD_CONSENT_POLICY_VERSION } from '../content/adConsent'
import { DASHBOARD_ADJUSTMENTS_PATH } from '../content/adjustmentHistory'
import { AD_ELIGIBILITY_PATH, AD_TRANSITION_CLAIM_PATH } from '../content/adTransition'
import {
  LINK_BASE_PATH,
  linkPath,
  LOGOUT_ENDPOINT,
  reauthenticatePath,
  SESSION_ENDPOINT,
  unlinkCancelPath,
  unlinkConfirmPath,
  unlinkPath,
  type SignInProvider,
} from '../content/auth'
import { DASHBOARD_EVIDENCE_PATH } from '../content/calculationEvidence'
import {
  BYBIT_CANCEL_PATH,
  BYBIT_CONNECT_PATH,
  BYBIT_CONNECTION_PATH,
  BYBIT_REVOCATION_CANCEL_PATH,
  BYBIT_REVOCATION_CONFIRM_PATH,
  BYBIT_REVOCATION_PATH,
} from '../content/connection'
import { FIRST_SNAPSHOT_PATH } from '../content/firstSnapshot'
import {
  OPERATOR_AUDIT_ENDPOINT,
  OPERATOR_COMMANDS_ENDPOINT,
  OPERATOR_EXPORT_CANCEL_ENDPOINT,
  OPERATOR_EXPORT_CONFIRM_ENDPOINT,
  OPERATOR_EXPORT_ENDPOINT,
  OPERATOR_INQUIRIES_ENDPOINT,
  OPERATOR_INQUIRY_METRICS_ENDPOINT,
  OPERATOR_NOTICES_ENDPOINT,
  OPERATOR_OPERATIONS_ENDPOINT,
  OPERATOR_RESTRICTIONS_ENDPOINT,
} from '../content/operator'
import { PLAN_END_CANCEL_PATH, PLAN_END_CONFIRM_PATH, PLAN_END_PATH } from '../content/planEnd'
import { DASHBOARD_PATH } from '../content/planResult'
import { DASHBOARD_SYNC_PATH } from '../content/planSync'
import { DASHBOARD_VERIFICATION_PATH } from '../content/planVerification'
import { SERVICE_NOTICES_PATH } from '../content/serviceNotice'
import { SUPPORT_INQUIRIES_PATH } from '../content/support'

const observedRequests: string[] = []
const observedIndexedDbNames: string[] = []
const observedSessionRequests: string[] = []
const observedAllowedRequests: string[] = []
/** 기본 응답이 답한 공지 조회(Story 3.5). 허용 기록과 따로 센다 — 진입 화면의 "요청 0건"은 이것도 0이어야 한다. */
const observedNoticeRequests: string[] = []
/** 기본 응답이 답한 문의 목록 조회(Story 3.6). 공지와 같은 근거로 허용 기록과 따로 센다. */
const observedSupportRequests: string[] = []
/** 기본 응답이 답한 광고 동의 조회(Story 4.1). 공지와 같은 근거로 허용 기록과 따로 센다. */
const observedAdConsentRequests: string[] = []
/** 기본 응답이 답한 광고 자격 판정(Story 4.3). 본문(`syncJobId`)도 함께 남긴다 — 어느 job을 물었는지가 계약이다. */
const observedAdEligibilityRequests: string[] = []

/**
 * 열린 요청과 그것에 답할 함수. 키는 `"<메서드> <URL>"`이다.
 *
 * 범위를 좁힌 탈출구다. 전역 거부를 걷어내지 않는 이유는 그 훅이 이 저장소의 브라우저 경계
 * (외부 폰트·CDN·분석 금지, 진입 화면 요청 0건)를 관측 가능하게 만드는 유일한 장치이기 때문이다.
 *
 * 키가 URL 하나가 아니라 (메서드, URL) 쌍인 것이 Story 1.5의 변화다. URL만 보면 `/api/auth/session`
 * 을 여는 것이 그 경로의 모든 메서드를 함께 여는 것이 되고, 그러면 시험이 `POST`를 실수로 통과시켜도
 * 아무도 모른다. 쌍으로 좁히면 시험이 연 것만 통과한다. `afterEach`가 매번 전부 닫는다.
 */
const responders = new Map<string, RequestResponder>()

/**
 * 열린 요청에 답하는 함수.
 *
 * `init`을 받는다. 로그아웃이 세션 귀속 CSRF 토큰을 헤더로 실었는지, 쿠키를 동일 출처로 제한했는지가
 * 그 인자에만 있고, 시험이 `globalThis.fetch`를 다시 감싸 그 값을 훔쳐보는 대신 여기서 넘긴다.
 * 인자를 쓰지 않는 기존 응답 함수는 그대로 통과한다.
 */
export type RequestResponder = (init?: RequestInit) => Promise<Response>

/** 열린 요청의 키. 메서드는 대문자로 정규화한다. */
function requestKey(method: string, url: string): string {
  return `${method.toUpperCase()} ${url}`
}

export function stubFirstSnapshotState(responder: RequestResponder): void {
  responders.set(requestKey('GET', FIRST_SNAPSHOT_PATH), responder)
}
export function stubFirstSnapshotStart(responder: RequestResponder): void {
  responders.set(requestKey('POST', FIRST_SNAPSHOT_PATH), responder)
}
export function stubPlanConditionState(responder: RequestResponder): void {
  responders.set(requestKey('GET', '/api/plans/conditions'), responder)
}

/**
 * 공식 대시보드 조회 하나를 이 시험 동안만 허용한다(Story 2.8).
 *
 * 두 로그인 화면(`/dashboard`·`/onboarding`)이 이 요청을 보낸다 — 첫 로그인 세션은 대시보드에서
 * 온보딩으로 밀려나므로 대시보드 패널이 두 화면에 있다. 열지 않은 시험에서는 전역 거부가 그 요청을
 * 기록하고 거절한다. 그 거절은 "계획 없음"이 아니라 읽기 실패다: 이전 결과가 없으니 `재시도 가능`이고
 * 금융값이 없다. "계획 없음" 경로를 보려는 시험은 `{state: 'NO_PLAN'}`을 답하게 연다.
 *
 * 다시 불러오기가 같은 요청을 다시 보내므로 응답 함수는 부를 때마다 새 `Response`를 돌려줘야 한다.
 */
export function stubNextBaseline(responder: RequestResponder, method = 'GET'): void {
  responders.set(requestKey(method, '/api/plans/next/baseline'), responder)
}
export function stubPlanHistory(responder: RequestResponder): void {
  responders.set(requestKey('GET', '/api/dashboard/history'), responder)
}
export function stubHistoricalDashboard(planId: string, responder: RequestResponder): void {
  responders.set(
    requestKey('GET', `/api/dashboard?planId=${encodeURIComponent(planId)}`),
    responder,
  )
}
export function stubDashboard(responder: RequestResponder): void {
  responders.set(requestKey('GET', DASHBOARD_PATH), responder)
}

/**
 * 동기화 접수 요청 하나를 이 시험 동안만 허용한다(Story 2.11).
 *
 * 두 로그인 화면은 첫 `READY` 응답에서 진입 동기화(`ENTRY`)를 한 번 보내고, 사용자가 `동기화`를 누르면
 * 수동 동기화(`MANUAL`)를 보낸다. 열지 않은 시험에서 이 요청이 나가면 전역 거부가 그것을 기록하고
 * 거절한다 — 화면이 몰래 동기화를 요청하지 않는다는 단정이 그 기록이다. 응답 함수는 `init`으로 본문
 * (트리거)과 헤더(CSRF 토큰·멱등 키)를 받는다.
 */
export function stubDashboardSync(responder: RequestResponder): void {
  responders.set(requestKey('POST', DASHBOARD_SYNC_PATH), responder)
}

/**
 * 정밀 검증·기존 계획 계속 접수 요청 하나를 이 시험 동안만 허용한다(Story 2.12).
 *
 * 두 요청은 사용자가 `정밀 검증`이나 `기존 계획 계속`을 누를 때만 나간다. 열지 않은 시험에서 이 요청이
 * 나가면 전역 거부가 그것을 기록하고 거절한다 — 화면이 스스로 정밀 검증을 요청하지 않는다는 단정이 그
 * 기록이다. 응답 함수는 `init`으로 본문(kind와 지금 보이는 묶음의 참조 세 값)과 헤더(CSRF 토큰·멱등 키)를
 * 받는다.
 */
export function stubDashboardVerification(responder: RequestResponder): void {
  responders.set(requestKey('POST', DASHBOARD_VERIFICATION_PATH), responder)
}

/**
 * 계획 중도 종료 **준비** 요청 하나를 이 시험 동안만 허용한다(Story 2.13).
 *
 * 확정·취소와 따로 연다. 세 경로를 한 번에 열면 "대화상자를 여는 요청은 아무것도 끝내지 않는다"를 확인하려는
 * 시험이 확정 요청까지 통과시키고, 그때 그 시험은 자기가 무엇을 본 것인지 알 수 없다(해제 스텁과 같은 근거).
 * 응답 함수는 `init`으로 본문(사유와 참조 세 값)과 헤더(CSRF 토큰)를 받는다.
 */
export function stubPlanEndPrepare(responder: RequestResponder): void {
  responders.set(requestKey('POST', PLAN_END_PATH), responder)
}

/**
 * 계획 중도 종료 **확정** 요청 하나를 이 시험 동안만 허용한다. 계획이 끝나는 유일한 경로다.
 *
 * 열지 않은 시험에서 이 요청이 나가면 전역 거부가 그것을 기록하고 거절한다 — 화면이 확인 없이 계획을 끝내는
 * 회귀가 그 기록으로 드러난다. 응답 함수는 `init`으로 본문(확인 문구 포함)과 헤더(멱등 키)를 받는다.
 */
export function stubPlanEndConfirm(responder: RequestResponder): void {
  responders.set(requestKey('POST', PLAN_END_CONFIRM_PATH), responder)
}

/** 계획 중도 종료 **취소** 요청 하나를 이 시험 동안만 허용한다. 준비가 만든 흐름과 허가를 되돌린다. */
export function stubPlanEndCancel(responder: RequestResponder): void {
  responders.set(requestKey('POST', PLAN_END_CANCEL_PATH), responder)
}

/**
 * 쿼리를 싣는 요청에 답하는 함수(Story 2.9). 경로가 같아도 쿼리가 요청마다 다르므로, 시험이 무엇을
 * 보냈는지 단정할 수 있게 URL 전체를 함께 받는다.
 */
export type QueryResponder = (url: string, init?: RequestInit) => Promise<Response>

/** 경로 단위로 연 쿼리 요청. 키는 `"<메서드> <경로>"`이며 쿼리가 없는 같은 경로는 열지 않는다. */
const queryResponders = new Map<string, QueryResponder>()

/**
 * 계산 근거 조회 하나를 이 시험 동안만 허용한다(Story 2.9). `stubDashboard`와 같은 모양이다.
 *
 * 근거는 지금 보이는 묶음의 공식 포인터 세 값을 쿼리로 보낸다. 그래서 여기는 경로만 보고 열며 응답
 * 함수가 URL 전체를 받는다. 열지 않은 시험에서 근거 요청이 나가면 전역 거부가 그것을 기록하고
 * 거절한다 — 사용자가 펼치지 않은 근거를 화면이 몰래 읽지 않는다는 단정이 그 기록이다.
 */
export function stubCalculationEvidence(responder: QueryResponder): void {
  queryResponders.set(requestKey('GET', DASHBOARD_EVIDENCE_PATH), responder)
}

/**
 * 조정 이력 조회 하나를 이 시험 동안만 허용한다(Story 2.10). 근거와 같은 모양이다.
 *
 * 이력은 지금 보이는 묶음의 공식 포인터 세 값과 유형 필터, 다음 페이지면 cursor를 쿼리로 보낸다. 그래서
 * 경로만 보고 열며 응답 함수가 URL 전체를 받는다. 열지 않은 시험에서 이력 요청이 나가면 전역 거부가 그것을
 * 기록하고 거절한다 — 사용자가 펼치지 않은 이력을 화면이 몰래 읽지 않는다는 단정이 그 기록이다.
 */
export function stubAdjustmentHistory(responder: QueryResponder): void {
  queryResponders.set(requestKey('GET', DASHBOARD_ADJUSTMENTS_PATH), responder)
}

/**
 * 세션 확인 요청 하나를 이 시험 동안만 허용한다.
 *
 * 로그인 상태 화면(`/onboarding`·`/dashboard`)은 서버 세션으로 상태를 확인하므로, 그 분기를
 * 단위 시험에서 다루려면 이 요청 하나가 성립해야 한다. 진입 화면은 이 함수를 부르지 않으며
 * 그 화면의 "요청 0건" 단정은 그대로 유지된다.
 */
export function stubSessionRequest(responder: RequestResponder): void {
  responders.set(requestKey('GET', SESSION_ENDPOINT), responder)
}

/**
 * 로그아웃 요청 하나를 이 시험 동안만 허용한다.
 *
 * 로그아웃은 `POST`이고 위 세션 확인과 다른 경로다. 이 함수를 부르지 않은 시험에서 로그아웃이
 * 나가면 전역 거부가 그것을 기록하고 실패시킨다 — 화면이 몰래 상태 변경 요청을 보내지 않는다는
 * 단정이 그 기록이다.
 */
export function stubLogoutRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', LOGOUT_ENDPOINT), responder)
}

/**
 * 그 제공자의 재인증 시작 요청 하나를 이 시험 동안만 허용한다.
 *
 * 제공자마다 경로가 다르므로 여는 것도 제공자 단위다. 시험이 열지 않은 제공자로 요청이 나가면
 * 전역 거부가 그것을 기록하고 실패시킨다 — 패널이 사용자가 고른 것과 다른 제공자를 여는 회귀가
 * 그 기록으로 드러난다.
 */
export function stubReauthenticationRequest(
  provider: SignInProvider,
  responder: RequestResponder,
): void {
  responders.set(requestKey('POST', reauthenticatePath(provider)), responder)
}

/**
 * 연결 상태 조회 하나를 이 시험 동안만 허용한다.
 *
 * 설정 화면만 이 요청을 보낸다. 다른 화면에서 나가면 전역 거부가 그것을 기록하고 실패시킨다 —
 * 계정의 사실을 필요 없는 화면이 물어보지 않는다는 단정이 그 기록이다.
 */
export function stubLinkStatusRequest(responder: RequestResponder): void {
  responders.set(requestKey('GET', LINK_BASE_PATH), responder)
}

/**
 * 그 제공자의 연결 시작 요청 하나를 이 시험 동안만 허용한다.
 *
 * 제공자마다 경로가 다르므로 여는 것도 제공자 단위다({@code stubReauthenticationRequest}와 같은
 * 근거). 시험이 열지 않은 제공자로 요청이 나가면 전역 거부가 그것을 기록하고 실패시킨다 —
 * 화면이 사용자가 고른 것과 다른 제공자를 연결하려는 회귀가 그 기록으로 드러난다.
 */
export function stubLinkRequest(provider: SignInProvider, responder: RequestResponder): void {
  responders.set(requestKey('POST', linkPath(provider)), responder)
}

/**
 * 그 제공자의 해제 **준비** 요청 하나를 이 시험 동안만 허용한다.
 *
 * 최종 확인과 따로 연다. 두 경로를 한 번에 열면 "대화상자를 여는 요청은 아무것도 바꾸지 않는다"를
 * 확인하려는 시험이 확인 요청까지 통과시키고, 그때 그 시험은 자기가 무엇을 본 것인지 알 수 없다.
 */
export function stubUnlinkPrepareRequest(
  provider: SignInProvider,
  responder: RequestResponder,
): void {
  responders.set(requestKey('POST', unlinkPath(provider)), responder)
}

/**
 * 그 제공자의 해제 **최종 확인** 요청 하나를 이 시험 동안만 허용한다.
 *
 * 열지 않은 시험에서 이 요청이 나가면 전역 거부가 그것을 기록하고 실패시킨다 — 화면이 확인 없이
 * 해제를 보내는 회귀가 그 기록으로 드러난다.
 */
export function stubUnlinkConfirmRequest(
  provider: SignInProvider,
  responder: RequestResponder,
): void {
  responders.set(requestKey('POST', unlinkConfirmPath(provider)), responder)
}

/**
 * 그 제공자의 해제 **취소** 요청 하나를 이 시험 동안만 허용한다.
 *
 * 취소는 준비가 만든 무장과 허가를 되돌린다. 열지 않은 시험에서 이 요청이 나가면 전역 거부가 그것을
 * 기록하고 실패시킨다 — 대화상자를 닫기만 해도 서버로 나가는 요청이 있다는 사실이 그 기록이다.
 */
export function stubUnlinkCancelRequest(
  provider: SignInProvider,
  responder: RequestResponder,
): void {
  responders.set(requestKey('POST', unlinkCancelPath(provider)), responder)
}

/**
 * Bybit 연결 상태 조회 하나를 이 시험 동안만 허용한다(Story 2.1).
 *
 * 설정 화면만 이 요청을 보내고, 그것도 게이트가 `ready`일 때만이다. 다른 화면에서 나가면 전역
 * 거부가 그것을 기록하고 실패시킨다 — 계정의 사실을 필요 없는 화면이 묻지 않는다는 단정이다.
 */
export function stubConnectionStatusRequest(responder: RequestResponder): void {
  responders.set(requestKey('GET', BYBIT_CONNECTION_PATH), responder)
}

/**
 * 연결 **준비** 요청 하나를 이 시험 동안만 허용한다.
 *
 * 최종 확인과 따로 연다. 두 경로를 한 번에 열면 "폼을 여는 요청은 아무것도 바꾸지 않는다"를
 * 확인하려는 시험이 확인 요청까지 통과시키고, 그때 그 시험은 자기가 무엇을 본 것인지 알 수 없다
 * (`stubUnlinkPrepareRequest`와 같은 근거).
 */
export function stubConnectionPrepareRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', BYBIT_CONNECTION_PATH), responder)
}

/**
 * 연결 **최종 확인** 요청 하나를 이 시험 동안만 허용한다. <b>자격정보가 나가는 유일한 경로다.</b>
 *
 * 열지 않은 시험에서 이 요청이 나가면 전역 거부가 그것을 기록하고 실패시킨다 — 화면이 확인 없이
 * 자격정보를 보내는 회귀가 그 기록으로 드러난다.
 */
export function stubConnectRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', BYBIT_CONNECT_PATH), responder)
}

/** 연결 **취소** 요청 하나를 이 시험 동안만 허용한다. 준비가 만든 무장과 허가를 되돌린다. */
export function stubConnectionCancelRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', BYBIT_CANCEL_PATH), responder)
}

/**
 * 연결 해제(Story 2.16)의 세 요청 — 준비·확정·취소 — 을 각각 이 시험 동안만 허용한다. 따로 여는 이유는 연결
 * 준비·확인과 같다: 준비만 연 시험에서 확정이 나가면 전역 거부가 그것을 기록한다.
 */
export function stubRevocationPrepareRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', BYBIT_REVOCATION_PATH), responder)
}
export function stubRevocationConfirmRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', BYBIT_REVOCATION_CONFIRM_PATH), responder)
}
export function stubRevocationCancelRequest(responder: RequestResponder): void {
  responders.set(requestKey('POST', BYBIT_REVOCATION_CANCEL_PATH), responder)
}

/**
 * 운영자 표면(Story 3.1)의 요청들을 각각 이 시험 동안만 허용한다. 감사 조회는 기간·결과·cursor를 쿼리로 보내므로
 * 근거·이력과 같이 경로로 열고 응답 함수가 URL 전체를 받는다. 준비·확정·취소는 따로 연다 — 준비만 연 시험에서 확정이
 * 나가면 전역 거부가 그것을 기록한다.
 */
export function stubOperatorAudit(responder: QueryResponder): void {
  queryResponders.set(requestKey('GET', OPERATOR_AUDIT_ENDPOINT), responder)
  responders.set(requestKey('GET', OPERATOR_AUDIT_ENDPOINT), (init) =>
    responder(OPERATOR_AUDIT_ENDPOINT, init),
  )
}
export function stubOperatorOperation(operationId: string, responder: RequestResponder): void {
  responders.set(requestKey('GET', `${OPERATOR_OPERATIONS_ENDPOINT}/${operationId}`), responder)
}
export function stubOperatorExportPrepare(responder: RequestResponder): void {
  responders.set(requestKey('POST', OPERATOR_EXPORT_ENDPOINT), responder)
}
export function stubOperatorExportConfirm(responder: RequestResponder): void {
  responders.set(requestKey('POST', OPERATOR_EXPORT_CONFIRM_ENDPOINT), responder)
}
export function stubOperatorExportCancel(responder: RequestResponder): void {
  responders.set(requestKey('POST', OPERATOR_EXPORT_CANCEL_ENDPOINT), responder)
}

/**
 * 서비스 공지 조회 하나를 이 시험 동안만 연다(Story 3.5). 연 응답이 기본 응답을 이기고, 연 요청은 허용 기록에 남는다.
 *
 * 대시보드와 설정 화면은 세션이 준비되면 언제나 이 요청을 보낸다. 그래서 열지 않은 시험에는 기록에 남지 않는 기본 응답
 * (빈 공지)이 답한다(`defaultServiceNotices`) — 공지와 무관한 시험의 요청 목록 단정이 이 요청 하나 때문에 모두 바뀌지
 * 않게 한다. 그 요청도 따로 기록된다(`getObservedNoticeRequests`) — 진입 화면은 그것도 0건이다. 다른 요청의 전역 거부는
 * 그대로다.
 */
export function stubServiceNotices(responder: RequestResponder): void {
  responders.set(requestKey('GET', SERVICE_NOTICES_PATH), responder)
}

/**
 * 문의 목록 조회 하나를 이 시험 동안만 연다(Story 3.6). 연 응답이 기본 응답(빈 목록)을 이기고 허용 기록에 남는다. 설정 화면은
 * 세션이 준비되면 언제나 이 요청을 보내므로, 열지 않은 시험에는 기록에 남지 않는 기본 응답이 답한다 — 공지 조회와 같은
 * 근거다.
 */
export function stubSupportInquiries(responder: RequestResponder): void {
  responders.set(requestKey('GET', SUPPORT_INQUIRIES_PATH), responder)
}

/** 문의 접수(Story 3.6). 기본 응답이 없다 — 열지 않은 접수는 전역 거부다. */
export function stubSupportSubmit(responder: RequestResponder): void {
  responders.set(requestKey('POST', SUPPORT_INQUIRIES_PATH), responder)
}

/**
 * 광고 동의 조회 하나를 이 시험 동안만 연다(Story 4.1). 연 응답이 기본 응답(미결정)을 이기고 허용 기록에 남는다. 설정 화면은
 * 세션이 준비되면 언제나 이 요청을 보내므로, 열지 않은 시험에는 기록에 남지 않는 기본 응답이 답한다 — 공지 조회와 같은
 * 근거다.
 */
export function stubAdConsent(responder: RequestResponder): void {
  responders.set(requestKey('GET', AD_CONSENT_PATH), responder)
}

/** 광고 동의 변경(Story 4.1). 기본 응답이 없다 — 열지 않은 변경은 전역 거부다. */
export function stubAdConsentChange(responder: RequestResponder): void {
  responders.set(requestKey('POST', AD_CONSENT_PATH), responder)
}

/**
 * 광고 자격 판정 하나를 이 시험 동안만 연다(Story 4.3). 연 응답이 기본 응답(공급자 꺼짐으로 건너뜀)을 이기고 허용 기록에 남는다.
 * 대시보드는 자기 화면에서 접수한 수동 동기화가 성공하면 이 요청을 보내므로, 열지 않은 시험에는 기본 응답이 답한다 — 기록은
 * `observedAdEligibilityRequests`에 남는다.
 */
export function stubAdEligibility(responder: RequestResponder): void {
  responders.set(requestKey('POST', AD_ELIGIBILITY_PATH), responder)
}

/** transition 소비(Story 4.3, 중립 전환 화면). 기본 응답이 없다 — 열지 않은 소비는 전역 거부다. */
export function stubAdTransitionClaim(responder: RequestResponder): void {
  responders.set(requestKey('POST', AD_TRANSITION_CLAIM_PATH), responder)
}

/** 문의 목록(Story 3.6, 운영자 화면). 상태 필터는 쿼리로 가므로 쿼리가 있는 요청도 같은 응답 함수가 받는다. */
export function stubOperatorInquiries(responder: QueryResponder): void {
  queryResponders.set(requestKey('GET', OPERATOR_INQUIRIES_ENDPOINT), responder)
  responders.set(requestKey('GET', OPERATOR_INQUIRIES_ENDPOINT), (init) =>
    responder(OPERATOR_INQUIRIES_ENDPOINT, init),
  )
}

/** 문의 상세 하나(Story 3.6, 운영자 화면). */
export function stubOperatorInquiryDetail(reference: string, responder: RequestResponder): void {
  responders.set(requestKey('GET', `${OPERATOR_INQUIRIES_ENDPOINT}/${reference}`), responder)
}

/** 문의 기간 집계(Story 3.6, 운영자 화면). 기간은 쿼리로 가므로 경로만 보고 연다. */
export function stubOperatorInquiryMetrics(responder: QueryResponder): void {
  queryResponders.set(requestKey('GET', OPERATOR_INQUIRY_METRICS_ENDPOINT), responder)
}

/** 공지 사건 목록(Story 3.5, 운영자 화면). */
export function stubOperatorNotices(responder: RequestResponder): void {
  responders.set(requestKey('GET', OPERATOR_NOTICES_ENDPOINT), responder)
}

/** 활성 제한 목록(Story 3.2). */
export function stubOperatorRestrictions(responder: RequestResponder): void {
  responders.set(requestKey('GET', OPERATOR_RESTRICTIONS_ENDPOINT), responder)
}

/**
 * 제한 command 하나의 준비·확정·취소(Story 3.2). `step`이 `prepare`면 command 경로 자체, 아니면 그 아래 단계다.
 */
export function stubOperatorRestrictionCommand(
  command: string,
  step: 'cancel' | 'confirm' | 'prepare',
  responder: RequestResponder,
): void {
  const base = `${OPERATOR_COMMANDS_ENDPOINT}/${command}`
  responders.set(requestKey('POST', step === 'prepare' ? base : `${base}/${step}`), responder)
}

/** 운영 command 하나의 준비·확정·취소(command 이름을 가리지 않는다 — 공통 상태 기계 시험이 쓴다). */
export function stubOperatorCommand(
  command: string,
  step: 'cancel' | 'confirm' | 'prepare',
  responder: RequestResponder,
): void {
  stubOperatorRestrictionCommand(command, step, responder)
}

/** 허용된 세션 확인 요청의 기록. 몇 번 불렸는지 단정하는 데 쓴다. */
export function getObservedSessionRequests(): readonly string[] {
  return [...observedSessionRequests]
}

/**
 * 허용된 요청 전부의 기록. `"<메서드> <URL>"` 형태이며 순서가 보존된다.
 *
 * 로그아웃이 정확히 한 번 나갔는지, 세션 확인 뒤에 나갔는지를 이 목록으로 본다.
 */
export function getObservedAllowedRequests(): readonly string[] {
  return [...observedAllowedRequests]
}

/** 기본 응답(빈 공지)이 답한 공지 조회의 기록. 시험이 연 응답의 기록은 `getObservedAllowedRequests`에 남는다. */
export function getObservedNoticeRequests(): readonly string[] {
  return [...observedNoticeRequests]
}

/** 기본 응답(빈 목록)이 답한 문의 목록 조회의 기록(Story 3.6). */
export function getObservedSupportRequests(): readonly string[] {
  return [...observedSupportRequests]
}

/** 기본 응답(미결정)이 답한 광고 동의 조회의 기록(Story 4.1). */
export function getObservedAdConsentRequests(): readonly string[] {
  return [...observedAdConsentRequests]
}

/** 기본 응답이 답한 광고 자격 판정의 본문들(Story 4.3). 열린 판정은 허용 기록(`getObservedAllowedRequests`)에 남는다. */
export function getObservedAdEligibilityRequests(): readonly string[] {
  return [...observedAdEligibilityRequests]
}

/** 지금까지 관측된 네트워크 요청. 비어 있어야 한다. */
export function getObservedRequests(): readonly string[] {
  return [...observedRequests]
}

/** 브라우저 저장소에 남은 항목과 시도된 IndexedDB 접근. 비어 있어야 한다. */
export function getObservedStorageEntries(): readonly string[] {
  const entries: string[] = []

  for (let index = 0; index < window.localStorage.length; index += 1) {
    entries.push(`localStorage:${window.localStorage.key(index) ?? ''}`)
  }
  for (let index = 0; index < window.sessionStorage.length; index += 1) {
    entries.push(`sessionStorage:${window.sessionStorage.key(index) ?? ''}`)
  }
  if (document.cookie !== '') {
    entries.push(`cookie:${document.cookie}`)
  }
  for (const name of observedIndexedDbNames) {
    entries.push(`indexedDB:${name}`)
  }

  return entries
}

/** 현재 문서에 남은 모든 쿠키를 만료시킨다. jsdom에는 일괄 삭제 API가 없다. */
function clearCookies(): void {
  for (const pair of document.cookie.split(';')) {
    const name = pair.split('=')[0]?.trim() ?? ''
    if (name !== '') {
      document.cookie = `${name}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT`
    }
  }
}

/** 열지 않은 시험의 문의 목록 응답: 문의가 없고 공개 지원 이메일도 없다. 기록은 `observedSupportRequests`에 남긴다. */
function defaultSupportInquiries(): Response {
  return new Response(JSON.stringify({ supportContact: null, items: [] }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** 열지 않은 시험의 광고 동의 응답: 아직 선택하지 않았다. 기록은 `observedAdConsentRequests`에 남긴다. */
function defaultAdConsent(): Response {
  return new Response(
    JSON.stringify({
      state: 'UNDECIDED',
      policyVersion: AD_CONSENT_POLICY_VERSION,
      decidedAt: null,
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

/** 열지 않은 시험의 광고 자격 판정 응답: 공급자 꺼짐으로 건너뛴다(기본 구성의 답). 기록은 `observedAdEligibilityRequests`에 남긴다. */
function defaultAdEligibility(): Response {
  return new Response(
    JSON.stringify({
      outcome: 'SKIPPED',
      reason: 'PROVIDER_DISABLED',
      transition: null,
      replayed: false,
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

/**
 * 열지 않은 시험의 공지 조회 응답: 공지가 없다. 허용 기록이 아니라 `observedNoticeRequests`에 남긴다 — 위
 * `stubServiceNotices`의 근거와 같다. 만료는
 * 지금부터 5분 뒤다(서버 상한과 같다).
 */
function defaultServiceNotices(): Response {
  const now = Date.now()
  return new Response(
    JSON.stringify({
      asOf: new Date(now).toISOString(),
      expiresAt: new Date(now + 5 * 60_000).toISOString(),
      items: [],
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

function refuse(kind: string, url: string): Error {
  observedRequests.push(`${kind} ${url}`)
  return new Error(`진입 화면은 네트워크 요청을 보내지 않습니다: ${kind} ${url}`)
}

function toUrl(input: unknown): string {
  if (typeof input === 'string') {
    return input
  }
  if (input instanceof URL) {
    return input.href
  }
  if (input instanceof Request) {
    return input.url
  }
  return String(input)
}

/** 요청의 메서드. `init`이 우선이고, `Request` 객체면 그것이 들고 있는 값이다. */
function toMethod(input: unknown, init?: RequestInit): string {
  if (typeof init?.method === 'string') {
    return init.method
  }
  if (input instanceof Request) {
    return input.method
  }
  return 'GET'
}

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const url = toUrl(input)
  const method = toMethod(input, init).toUpperCase()
  const responder = responders.get(requestKey(method, url))
  if (responder !== undefined) {
    observedAllowedRequests.push(requestKey(method, url))
    if (method === 'GET' && url === SESSION_ENDPOINT) {
      observedSessionRequests.push(url)
    }
    return responder(init)
  }
  if (method === 'GET' && url === SERVICE_NOTICES_PATH) {
    observedNoticeRequests.push(requestKey(method, url))
    return Promise.resolve(defaultServiceNotices())
  }
  if (method === 'GET' && url === SUPPORT_INQUIRIES_PATH) {
    observedSupportRequests.push(requestKey(method, url))
    return Promise.resolve(defaultSupportInquiries())
  }
  if (method === 'GET' && url === AD_CONSENT_PATH) {
    observedAdConsentRequests.push(requestKey(method, url))
    return Promise.resolve(defaultAdConsent())
  }
  if (method === 'POST' && url === AD_ELIGIBILITY_PATH) {
    observedAdEligibilityRequests.push(typeof init?.body === 'string' ? init.body : '')
    return Promise.resolve(defaultAdEligibility())
  }
  const queryAt = url.indexOf('?')
  const queryResponder =
    queryAt < 0 ? undefined : queryResponders.get(requestKey(method, url.slice(0, queryAt)))
  if (queryResponder !== undefined) {
    observedAllowedRequests.push(requestKey(method, url))
    return queryResponder(url, init)
  }
  return Promise.reject(refuse(`fetch:${method}`, url))
}

XMLHttpRequest.prototype.open = function open(method: string, url: string | URL): void {
  throw refuse(`xhr:${method}`, toUrl(url))
}

Object.defineProperty(navigator, 'sendBeacon', {
  configurable: true,
  writable: true,
  value: (url: string | URL): boolean => {
    throw refuse('sendBeacon', toUrl(url))
  },
})

/*
 * IndexedDB도 금지 목록에 있다. e2e만 검사하고 단위 시험이 비어 있으면 구멍이 남는다.
 * 접근을 기록한 뒤 거부해 `getObservedStorageEntries()`가 관측할 수 있게 한다.
 */
Object.defineProperty(window, 'indexedDB', {
  configurable: true,
  writable: true,
  value: {
    open: (name: string): never => {
      observedIndexedDbNames.push(name)
      throw new Error(`진입 화면은 IndexedDB를 사용하지 않습니다: ${name}`)
    },
    deleteDatabase: (name: string): never => {
      observedIndexedDbNames.push(name)
      throw new Error(`진입 화면은 IndexedDB를 사용하지 않습니다: ${name}`)
    },
    databases: (): Promise<never[]> => Promise.resolve([]),
    cmp: (): number => 0,
  },
})

beforeEach(() => {
  observedRequests.length = 0
  observedIndexedDbNames.length = 0
  observedSessionRequests.length = 0
  observedAllowedRequests.length = 0
  observedNoticeRequests.length = 0
  observedSupportRequests.length = 0
  observedAdConsentRequests.length = 0
  observedAdEligibilityRequests.length = 0
})

afterEach(() => {
  cleanup()
  // 탈출구는 시험 하나의 범위다. 다음 시험은 다시 전역 거부에서 시작한다.
  responders.clear()
  queryResponders.clear()
  observedRequests.length = 0
  observedIndexedDbNames.length = 0
  observedSessionRequests.length = 0
  observedAllowedRequests.length = 0
  observedNoticeRequests.length = 0
  observedSupportRequests.length = 0
  observedAdConsentRequests.length = 0
  observedAdEligibilityRequests.length = 0
  window.localStorage.clear()
  window.sessionStorage.clear()
  // 한 시험이 남긴 쿠키가 다음 시험의 저장소 단정을 엉뚱하게 실패시키지 않게 한다.
  clearCookies()
})
