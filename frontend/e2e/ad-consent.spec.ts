import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Locator, type Page } from '@playwright/test'

import { adConsentContent, adConsentStateSentence } from '../src/content/adConsent'
import { authContent } from '../src/content/auth'

/**
 * 문맥형 광고 동의의 실제 Chromium 검증(Story 4.1).
 *
 * 백엔드와 dev-oidc 스텁이 함께 떠 있는 주입 origin에서만 돈다(`support-inquiry.spec.ts`와 같은 게이트). 새 사용자가 설정
 * 화면의 `광고 동의` 영역에서 키보드만으로 동의하고 철회한다. 그동안 브라우저의 요청은 동일 출처뿐이고(광고 SDK·외부 요청
 * 0건), 쿠키·`localStorage`·`sessionStorage`·IndexedDB에 쓰지 않는다. axe 위반 0건과 320px 배치를 함께 본다.
 */

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

const STUB_CONSENT_LINK = '동의하고 계속하기'

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

const NARROW_VIEWPORT = { width: 320, height: 640 } as const

const copy = adConsentContent

/** 실행을 가르는 짧은 표지. */
function letters(): string {
  return Array.from({ length: 8 }, () =>
    String.fromCharCode(97 + Math.floor(Math.random() * 26)),
  ).join('')
}

async function signIn(page: Page, subject: string): Promise<void> {
  await page.goto('/')
  await page.getByRole('link', { name: authContent.signIn.labels.google }).click()
  await expect(page.getByRole('link', { name: STUB_CONSENT_LINK })).toBeVisible()
  const url = new URL(page.url())
  url.searchParams.set('dev_subject', subject)
  await page.goto(url.toString())
  await page.getByRole('link', { name: STUB_CONSENT_LINK }).click()
  await page.waitForURL((next) => next.pathname !== '/' && !next.pathname.startsWith('/api/'))
}

async function openConsent(page: Page): Promise<Locator> {
  await page.goto('/settings')
  const region = page.getByRole('region', { name: copy.heading })
  await expect(region).toBeVisible()
  await expect(region.getByText(copy.stateLabel, { exact: true })).toBeVisible()
  return region
}

/** `Tab`만 눌러 그 이름의 버튼에 닿는다. 무한히 돌지 않도록 상한을 둔다. */
async function tabTo(page: Page, name: string): Promise<void> {
  for (let pressed = 0; pressed < 80; pressed += 1) {
    await page.keyboard.press('Tab')
    const text = await page.evaluate(() => {
      const active = document.activeElement
      return active instanceof HTMLButtonElement ? active.textContent.trim() : ''
    })
    if (text === name) return
  }
  throw new Error(`Tab으로 ${name} 버튼에 닿지 못했습니다.`)
}

test.describe('광고 동의 · 주입 origin', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 주입된 origin에서만 성립하는 시험이다. 파일째 사라지는 것보다 보고서에 "건너뜀"으로 남는 편이 낫다.
  test.skip(
    injectedOrigin === '',
    'PLAYWRIGHT_BASE_URL로 Nginx origin과 dev-oidc 스텁을 주입한 실행에서만 의미가 있다.',
  )

  test('키보드만으로 동의하고 철회하며 그동안 외부 요청과 브라우저 저장소 쓰기가 없다', async ({
    page,
  }) => {
    const requests: { url: string; resourceType: string }[] = []
    page.on('request', (request) => {
      requests.push({ url: request.url(), resourceType: request.resourceType() })
    })
    await signIn(page, `e2e-ad-consent-${letters()}`)
    const region = await openConsent(page)

    // 처음에는 미결정이고 데이터 흐름 설명이 있다.
    await expect(region.getByText(adConsentStateSentence('UNDECIDED'))).toBeVisible()
    await expect(region.getByText(copy.provider)).toBeVisible()
    await expect(region.getByText(copy.independence)).toBeVisible()
    // 이 영역은 live region을 더하지 않는다.
    await expect(region.locator('[aria-live]')).toHaveCount(0)
    const scan = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(scan.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])

    // 키보드만으로 동의한다. 결과는 화면의 status가 한 번 말하고 초점은 지금 상태 문장으로 간다.
    await tabTo(page, copy.grant)
    await page.keyboard.press('Enter')
    await expect(page.getByRole('status')).toContainText(copy.savedGranted)
    await expect(region.getByText(adConsentStateSentence('GRANTED'))).toBeFocused()
    await expect(region).toContainText('KST')

    // 바로 다음 정지점이 철회다.
    await page.keyboard.press('Tab')
    await expect(region.getByRole('button', { name: copy.withdraw })).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('status')).toContainText(copy.withdrawn)
    await expect(region.getByText(adConsentStateSentence('DENIED'))).toBeFocused()
    await expect(region.getByRole('button', { name: copy.regrant })).toBeVisible()

    // 상태는 서버의 것이다 — 다시 열어도 같다.
    const reopened = await openConsent(page)
    await expect(reopened.getByText(adConsentStateSentence('DENIED'))).toBeVisible()

    // 다시 동의한 뒤에도 외부 요청이 없다(광고 SDK·preconnect·pixel 0건).
    await tabTo(page, copy.regrant)
    await page.keyboard.press('Enter')
    await expect(page.getByRole('status')).toContainText(copy.savedGranted)

    const origin = new URL(page.url()).origin
    const external = requests.filter((request) => {
      if (request.url.startsWith('data:') || request.url.startsWith('blob:')) return false
      try {
        return new URL(request.url).origin !== origin
      } catch {
        return true
      }
    })
    expect(external).toEqual([])
    // 화면이 보낸 데이터 요청은 동일 출처 `/api/`뿐이다.
    const data = requests.filter((request) =>
      ['xhr', 'fetch', 'websocket', 'eventsource'].includes(request.resourceType),
    )
    expect(data.length).toBeGreaterThan(0)
    for (const request of data) {
      expect(new URL(request.url).pathname).toMatch(/^\/api\//)
    }
    expect(new URL(page.url()).search).toBe('')

    // 브라우저 저장소 쓰기 0건. 세션 쿠키는 `HttpOnly`라 문서에서 보이지 않는다 — 스크립트가 쓴 쿠키가 없다.
    const storage = await page.evaluate(async () => ({
      local: window.localStorage.length,
      session: window.sessionStorage.length,
      cookie: document.cookie,
      databases: (await window.indexedDB.databases()).length,
    }))
    expect(storage).toEqual({ local: 0, session: 0, cookie: '', databases: 0 })

    // 320px에서 가로 스크롤이 없고 조작 대상은 최소 44px이다.
    await page.setViewportSize(NARROW_VIEWPORT)
    await expect(region.getByRole('button', { name: copy.withdraw })).toBeVisible()
    for (const target of await region.getByRole('button').all()) {
      const box = await target.boundingBox()
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
      expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)
    }
    const metrics = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)
    const narrowScan = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(narrowScan.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual(
      [],
    )
  })
})
