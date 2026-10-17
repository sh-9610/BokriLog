/*
 * 문서 이동을 넘는 e2e 대기 — 앞 문서 내리기와 최종 착지 대기, 그리고 스텁 동의·로그인(Epic 4 회고 AI-6). 스텁 동의와 로그인의
 * 정식 경로는 이 파일 하나다. spec 파일에 사본을 만들지 않는다.
 *
 * 주입 origin e2e의 간헐 실패는 같은 계열이었다(회고 P-1·P-2, `deferred-work.md` 155). 로그인·재인증 뒤 서버가 고른 첫 착지
 * 문서는 세션 확인을 마친 뒤 다른 착지 화면으로 다시 덮어쓸 수 있고(온보딩 ↔ 대시보드 밀어내기), 그 문서가 늦게 보낸 요청은
 * 다음 문서의 측정 구간에 섞일 수 있다. 그 사이에 다음 `goto`를 걸면 진행 중인 이동에 끊기거나, 세션 확인 전의 문서에서 다음
 * 단계를 시작한다. 그래서 문서를 넘는 단계는 이 파일의 대기를 거친다.
 *
 * - **앞 문서 내리기**(`unloadDocument`): 요청을 세기 전에 지금 문서를 빈 문서로 완전히 내린다.
 * - **화면 착지 대기**(`waitForScreen`·`waitForLanding`): 주소가 기대 경로이고 그 화면의 제목이 보일 때까지 기다린다. 로그인
 *   상태 화면은 세션 게이트가 `ready`일 때만 그 제목을 그리므로(`useSessionGate`), 제목이 보이면 그 문서는 더 이동하지 않는다.
 *   주소만 보는 `waitForURL(pathname !== '/')`은 첫 착지에서 풀리므로 쓰지 않는다.
 *
 * 시험 파일이 아니다. Playwright는 `*.spec.ts`만 시험으로 모은다.
 */
import { expect, type Page } from '@playwright/test'

import {
  authContent,
  DASHBOARD_PATH,
  ONBOARDING_PATH,
  OPERATOR_PATH,
  SETTINGS_PATH,
  type SignInProvider,
} from '../../src/content/auth'
import { operatorContent } from '../../src/content/operator'

/**
 * 이 파일의 문서 이동 대기 시한(ms).
 *
 * 로그인·재인증 한 번은 진입 화면 → 우리 인가 시작 → 스텁 동의 → 콜백(OIDC 검증·세션 회전) → 착지 문서 → 세션 확인까지 문서
 * 서너 개와 백엔드 왕복 여럿이다. 새로 띄운 스택의 첫 왕복은 콜드 JVM(클래스 적재·JIT·연결 풀)을 함께 지나고, 155가 실패한
 * 것도 새 스택 첫 실행이었다. 기본 expect 5초에 기대면 그 첫 왕복이 원인과 무관하게 시한으로 실패할 수 있으므로 왕복 하나를
 * 담는 값을 이름으로 준다. 주입 origin 전체 실행의 `globalTimeout`(7분) 안이다.
 */
export const LANDING_TIMEOUT_MS = 30_000

/** 개발용 OIDC 스텁의 동의 링크. 백엔드 `DevOidcStub`이 소유하는 문자열이다(제품 카피가 아니다). */
export const STUB_CONSENT_LINK = '동의하고 계속하기'

/** 착지를 기다릴 수 있는 로그인 상태 화면과, 그 화면이 준비됐을 때만 그리는 제목. */
const SCREEN_HEADINGS = {
  [ONBOARDING_PATH]: { level: 2, name: authContent.onboarding.heading },
  [DASHBOARD_PATH]: { level: 2, name: authContent.dashboard.heading },
  [SETTINGS_PATH]: { level: 2, name: authContent.settings.heading },
  // 운영자에게 운영자 제어가 보일 때의 제목이다. 비운영자는 없는 화면(`notFound`)이라 이 대기를 쓰지 않는다.
  [OPERATOR_PATH]: { level: 1, name: operatorContent.heading },
} as const

export type ScreenPath = keyof typeof SCREEN_HEADINGS

/** 서버가 로그인 뒤 고르는 착지 화면 둘. */
export type LandingPath = typeof ONBOARDING_PATH | typeof DASHBOARD_PATH

const LANDING_PATHS: readonly LandingPath[] = [ONBOARDING_PATH, DASHBOARD_PATH]

function isLandingPath(pathname: string): pathname is LandingPath {
  return (LANDING_PATHS as readonly string[]).includes(pathname)
}

function screenHeading(page: Page, path: ScreenPath) {
  return page.getByRole('heading', { ...SCREEN_HEADINGS[path], exact: true })
}

/**
 * 앞 문서를 빈 문서로 완전히 내린다. 이 뒤에 기록하는 요청에는 앞 문서(세션 확인·대시보드·첫 스냅샷 조회)의 늦은 요청이
 * 섞이지 않는다(PR #111).
 */
export async function unloadDocument(page: Page): Promise<void> {
  await page.goto('about:blank')
}

/**
 * 그 화면에 착지할 때까지 기다린다 — 주소가 `path`가 되고 그 화면의 제목이 보일 때까지. 재인증 복귀처럼 서버가 붙들어 둔
 * 목적지로 돌아오는 이동 뒤에, 그 목적지를 코드로 확인해 넘긴다.
 */
export async function waitForScreen(page: Page, path: ScreenPath): Promise<void> {
  await page.waitForURL((url) => url.pathname === path, { timeout: LANDING_TIMEOUT_MS })
  await expect(screenHeading(page, path)).toBeVisible({ timeout: LANDING_TIMEOUT_MS })
}

/**
 * 최종 착지를 기다린다 — 주소가 착지 경로(`expected`를 주면 그 경로)가 되고 그 화면의 제목이 보일 때까지. 착지한 경로를
 * 돌려준다. 중간 문서(결과 문서·첫 착지)의 주소만 보고 다음 단계를 걸지 않게 한다.
 */
export async function waitForLanding(page: Page, expected?: LandingPath): Promise<LandingPath> {
  if (expected !== undefined) {
    await waitForScreen(page, expected)
    return expected
  }
  // 어느 쪽이든 제목이 보이면 게이트가 `ready`이므로 그 순간의 주소가 최종 착지다.
  await expect(
    screenHeading(page, ONBOARDING_PATH).or(screenHeading(page, DASHBOARD_PATH)),
  ).toBeVisible({ timeout: LANDING_TIMEOUT_MS })
  const pathname = new URL(page.url()).pathname
  expect(isLandingPath(pathname), `착지 경로가 아닌 곳에서 착지 제목을 보았다: ${pathname}`).toBe(
    true,
  )
  const landed = pathname as LandingPath
  await expect(screenHeading(page, landed)).toBeVisible({ timeout: LANDING_TIMEOUT_MS })
  return landed
}

/**
 * 스텁 동의 화면에서 `subject`를 고정한 채 동의한다 — 로그인·재인증·두 번째 로그인이 같은 사람이게.
 *
 * 스텁은 기본적으로 요청마다 새 `subject`를 만든다(`DevOidcStub`). 같은 제공자로 두 번 왕복하면 두 번째는 **다른 사람**이고,
 * 재인증은 "이 식별자가 현재 사용자의 것인가"를 보므로 조용히 실패한다 — 그 실패는 붙들어 둔 목적지로 돌아가는 모양이 성공과
 * 같아서 화면만 보면 구분되지 않는다. 고정 수단은 스텁의 인가 요청 파라미터 하나(`dev_subject`)이며 검증 스크립트가 쓰는 것과
 * 같다. 동의 화면에 도착한 뒤 그 주소에 파라미터를 더해 다시 연다 — 스텁의 인가 화면은 상태를 만들지 않으므로 다시 여는 것이
 * 안전하고, 우리 세션의 `state`·PKCE는 그대로 남는다. 제품 코드는 이 파라미터를 알지 못한다.
 */
export async function consent(page: Page, subject: string): Promise<void> {
  // 동의 화면에 도착할 때까지 기다린다. 그 전에 주소를 읽으면 우리 인가 시작 경로가 잡힌다.
  await expect(page.getByRole('link', { name: STUB_CONSENT_LINK })).toBeVisible({
    timeout: LANDING_TIMEOUT_MS,
  })
  const url = new URL(page.url())
  url.searchParams.set('dev_subject', subject)
  await page.goto(url.toString())
  await page.getByRole('link', { name: STUB_CONSENT_LINK }).click()
}

/**
 * 진입 화면에서 스텁 제공자를 지나 `subject`로 로그인하고 **최종 착지**까지 기다린다. 착지한 경로를 돌려준다 — 그 세션의
 * 목적지이고, 같은 세션의 재인증은 그 경로로 돌아온다(`ReauthenticationFlow`가 시작 시각에 붙든다). `landing`을 주면 그
 * 경로에 착지해야 한다(신규 계정은 온보딩).
 */
export async function signIn(
  page: Page,
  subject: string,
  options: { readonly provider?: SignInProvider; readonly landing?: LandingPath } = {},
): Promise<LandingPath> {
  await page.goto('/')
  await page
    .getByRole('link', { name: authContent.signIn.labels[options.provider ?? 'google'] })
    .click()
  await consent(page, subject)
  return waitForLanding(page, options.landing)
}
