import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import { authContent, ONBOARDING_PATH } from '../src/content/auth'
import { connectionContent } from '../src/content/connection'
import { consent, signIn, waitForScreen } from './support/navigation'

/**
 * 읽기 전용 Bybit 연결의 실제 Chromium 검증(Story 2.1).
 *
 * 브라우저 층에서만 확인할 수 있는 것 넷을 본다 — 폼의 기하와 포커스, 성공 왕복이 실제로 지나는
 * 단계, 자격정보가 화면·주소·브라우저 저장소 어디에도 남지 않는다는 사실, 그리고 인증으로 막힌
 * 경로가 미인증 브라우저에서 어떻게 보이는가다. jsdom은 레이아웃도 실제 내비게이션도 구현하지
 * 않으므로 단위 시험은 속성과 호출만 본다.
 *
 * <b>주입된 origin에서만 돈다.</b> 성공 왕복은 백엔드와 두 스텁(`dev-oidc`·`dev-bybit`)이 함께
 * 떠 있는 origin에서만 성립하고, 기본 `npm run test:e2e`의 `vite preview`에는 `/api`가 없다
 * ({@code auth-entry.spec.ts}와 같은 게이트).
 *
 * 실제 Bybit 왕복은 Epic 2의 <b>실연동 미검증</b> 게이트가 소유하며 이 시험이 대신하지 않는다.
 */

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

const NARROW_VIEWPORT = { width: 320, height: 640 } as const

/**
 * 개발용 Bybit 스텁의 시나리오 선택자.
 *
 * API Key의 앞부분이 시나리오를 고르고 뒷부분이 메인 UID다({@code DevBybitStub}). 자격정보가
 * 아니라 선택자이며, Secret은 아래에서 실행 시점에 만든다.
 */
const SCENARIO = { supported: 'uta2', writePermission: 'write' } as const

/** 이 실행의 Bybit Secret. 시험 fixture에 자격정보 모양 리터럴을 두지 않는다. */
function newSecret(): string {
  return `s-${Math.random().toString(36).slice(2)}-${String(Date.now())}`
}

/** 그 시나리오의 API Key. UID를 시험마다 다르게 두어 전역 유일 제약과 부딪히지 않는다. */
function apiKey(scenario: string, uid: string): string {
  return `${scenario}.${uid}`
}

/**
 * 스텁 제공자를 지나 로그인하고 본인 확인까지 마친다. 연결의 전제가 정확히 이 상태다.
 *
 * 두 왕복이 **같은 식별자**를 지나야 한다. 첫 왕복이 스텁이 정한 임의 `subject`를 쓰면 두 번째는
 * 다른 사람이 되고, 재인증이 조용히 실패해 연결 준비가 403으로 끝난다.
 */
async function signInAndReauthenticate(page: Page): Promise<void> {
  const subject = `e2e-connection-${String(Date.now())}-${Math.random().toString(36).slice(2)}`

  await signIn(page, subject, { landing: ONBOARDING_PATH })

  // 본인 확인. 설정 화면의 조작이며 확인을 마치면 세션이 붙들어 둔 목적지로 돌아온다.
  await page.getByRole('link', { name: authContent.settings.link }).click()
  await expect(page.getByRole('heading', { name: authContent.settings.heading })).toBeVisible()
  await page.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
  await consent(page, subject)
  // 재인증은 세션의 목적지(신규 계정의 착지인 온보딩)로 돌아온다.
  await waitForScreen(page, ONBOARDING_PATH)
}

/** 설정 화면을 열고 연결 구역이 그려질 때까지 기다린다. */
async function openSettings(page: Page): Promise<void> {
  await page.goto('/settings')
  await expect(page.getByRole('heading', { name: connectionContent.heading })).toBeVisible()
}

test.describe('Bybit 연결', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 주입된 origin에서만 성립하는 시험이다. 파일째 사라지는 것보다 보고서에 "건너뜀"으로 남는 편이 낫다.
  test.skip(
    injectedOrigin === '',
    'PLAYWRIGHT_BASE_URL로 Nginx origin과 dev-oidc·dev-bybit 스텁을 주입한 실행에서만 의미가 있다.',
  )

  test('폼을 열고 연결까지 지나며 자격정보가 어디에도 남지 않는다', async ({ page, context }) => {
    await page.setViewportSize(NARROW_VIEWPORT)
    await signInAndReauthenticate(page)
    await openSettings(page)

    const secret = newSecret()
    const key = apiKey(SCENARIO.supported, `81${String(Date.now() % 100000)}`)

    // 확인 전에는 연결이 없다.
    await expect(page.getByText(connectionContent.notConnected)).toBeVisible()

    await page.getByRole('button', { name: connectionContent.start }).click()
    await expect(
      page.getByRole('heading', { level: 3, name: connectionContent.form.heading }),
    ).toBeVisible()

    /*
     * 조작 대상은 모두 최소 44×44px이다(EXPERIENCE.md Accessibility Floor). 입력 칸도 조작
     * 대상이며, 폼이 좁은 폭에서 접히는지도 아래 가로 스크롤 단정이 함께 본다.
     */
    for (const target of [
      page.getByLabel(connectionContent.form.apiKeyLabel),
      page.getByLabel(connectionContent.form.apiSecretLabel),
      page.getByRole('button', { name: connectionContent.form.submit }),
      page.getByRole('button', { name: connectionContent.form.cancel }),
    ]) {
      const box = await target.boundingBox()
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    }

    const metrics = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)

    await page.getByLabel(connectionContent.form.apiKeyLabel).fill(key)
    await page.getByLabel(connectionContent.form.apiSecretLabel).fill(secret)
    await page.getByRole('button', { name: connectionContent.form.submit }).click()

    await expect(page.getByText(connectionContent.succeeded)).toBeVisible()
    await expect(page.getByText(connectionContent.connectedBoundary)).toBeVisible()

    /*
     * 자격정보가 화면·주소·브라우저 저장소 어디에도 없다. 성공 뒤에는 마스킹 값조차 재표시하지
     * 않으므로 본문 전체에서 두 값이 0건이어야 한다.
     */
    const text = await page.locator('body').innerText()
    expect(text).not.toContain(secret)
    expect(text).not.toContain(key)
    expect(page.url()).not.toContain(secret)
    expect(page.url()).not.toContain(key)

    const stored = await page.evaluate(async () => ({
      localStorage: window.localStorage.length,
      sessionStorage: window.sessionStorage.length,
      cookie: document.cookie,
      indexedDb: (await window.indexedDB.databases()).length,
    }))
    expect(stored).toEqual({ localStorage: 0, sessionStorage: 0, cookie: '', indexedDb: 0 })

    // 브라우저에 남는 것은 여전히 불투명 세션 쿠키 하나뿐이다.
    const cookies = await context.cookies()
    expect(cookies.map((cookie) => cookie.name)).toEqual(['BOKRILOG_SESSION'])

    // 새로 고쳐도 연결 사실이 남고 자격정보는 돌아오지 않는다.
    await openSettings(page)
    await expect(page.getByText(connectionContent.verifiedAtLabel)).toBeVisible()
    expect(await page.locator('body').innerText()).not.toContain(secret)
  })

  test('쓰기 권한 키는 폼을 남긴 채 다음 행동을 말하고 Secret만 지운다', async ({ page }) => {
    await signInAndReauthenticate(page)
    await openSettings(page)

    const secret = newSecret()
    const key = apiKey(SCENARIO.writePermission, `82${String(Date.now() % 100000)}`)

    await page.getByRole('button', { name: connectionContent.start }).click()
    await page.getByLabel(connectionContent.form.apiKeyLabel).fill(key)
    await page.getByLabel(connectionContent.form.apiSecretLabel).fill(secret)
    await page.getByRole('button', { name: connectionContent.form.submit }).click()

    // 오류 요약이 나타나고 그 문장이 사용자가 할 일을 말한다.
    await expect(page.getByRole('alert')).toContainText(
      '이 API Key에 쓰기 권한이 있습니다. Bybit에서 읽기 전용 키를 새로 발급해 주세요.',
    )

    // Secret만 지워지고 API Key는 남는다. 포커스는 비밀 필드다.
    await expect(page.getByLabel(connectionContent.form.apiSecretLabel)).toHaveValue('')
    await expect(page.getByLabel(connectionContent.form.apiKeyLabel)).toHaveValue(key)
    await expect(page.getByLabel(connectionContent.form.apiSecretLabel)).toBeFocused()
    // 연결되지 않았다.
    await expect(page.getByText(connectionContent.succeeded)).toHaveCount(0)
  })

  test('폼이 열린 화면도 WCAG 2.2 AA 자동 검사 위반이 0건이다', async ({ page }) => {
    await page.setViewportSize(NARROW_VIEWPORT)
    await signInAndReauthenticate(page)
    await openSettings(page)

    await page.getByRole('button', { name: connectionContent.start }).click()
    await expect(
      page.getByRole('heading', { level: 3, name: connectionContent.form.heading }),
    ).toBeVisible()

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()

    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  })

  test('취소는 폼을 닫고 바뀐 것이 없다고 말한다', async ({ page }) => {
    await signInAndReauthenticate(page)
    await openSettings(page)

    await page.getByRole('button', { name: connectionContent.start }).click()
    await page.getByRole('button', { name: connectionContent.form.cancel }).click()

    await expect(page.getByText(connectionContent.cancelled)).toBeVisible()
    await expect(
      page.getByRole('heading', { level: 3, name: connectionContent.form.heading }),
    ).toHaveCount(0)
    await expect(page.getByText(connectionContent.notConnected)).toBeVisible()
  })
})
