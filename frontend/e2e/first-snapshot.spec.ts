import { expect, test, type Page } from '@playwright/test'

import { authContent } from '../src/content/auth'
import { connectionContent } from '../src/content/connection'
import { firstSnapshotContent } from '../src/content/firstSnapshot'
import { planConditionContent } from '../src/content/planConditions'

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''
const STUB_CONSENT = '동의하고 계속하기'

async function consent(page: Page, subject: string): Promise<void> {
  await expect(page.getByRole('link', { name: STUB_CONSENT })).toBeVisible()
  const url = new URL(page.url())
  url.searchParams.set('dev_subject', subject)
  await page.goto(url.toString())
  await page.getByRole('link', { name: STUB_CONSENT }).click()
}

test.describe('첫 공식 현재가치', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 실제 동일 출처 스텁에서만 성공 왕복을 검증한다.
  test.skip(injectedOrigin === '', 'PLAYWRIGHT_BASE_URL의 동일 출처 스텁이 필요하다.')

  test('연결 후 비동기 첫 확인을 접수하고 측정 시각과 세 신뢰 축을 보여 준다', async ({ page }) => {
    const subject = `e2e-snapshot-${String(Date.now())}-${Math.random().toString(36).slice(2)}`
    await page.goto('/')
    await page.getByRole('link', { name: authContent.signIn.labels.google }).click()
    await consent(page, subject)
    await page.getByRole('link', { name: authContent.settings.link }).click()
    await page.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
    await consent(page, subject)
    await page.goto('/settings')
    await expect(page.getByRole('heading', { name: connectionContent.heading })).toBeVisible()
    await page.getByRole('button', { name: connectionContent.start }).click()
    const uid = `snapshot${String(Date.now())}`
    const key = `uta2.${uid}`
    const secret = `s-${Math.random().toString(36).slice(2)}-${String(Date.now())}`
    await page.getByLabel(connectionContent.form.apiKeyLabel).fill(key)
    await page.getByLabel(connectionContent.form.apiSecretLabel).fill(secret)
    await page.getByRole('button', { name: connectionContent.form.submit }).click()
    await expect(page.getByText(connectionContent.succeeded)).toBeVisible()

    await page.goto('/onboarding')
    await expect(page.getByRole('heading', { name: firstSnapshotContent.heading })).toBeVisible()
    await page.getByRole('button', { name: firstSnapshotContent.start }).click()
    await expect(page.getByText(firstSnapshotContent.succeeded)).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText(/1,234\.56 USD/)).toBeVisible()
    await expect(page.getByText(/Bybit 측정 시각/)).toBeVisible()
    for (const axis of ['신선도', '입력 신뢰도', '계산 성격']) {
      await expect(page.getByText(new RegExp(axis))).toBeVisible()
    }
    const text = await page.locator('body').innerText()
    expect(text).not.toContain(key)
    expect(text).not.toContain(secret)
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)

    await page.getByRole('link', { name: planConditionContent.openConditions }).click()
    await expect(page.getByRole('heading', { name: planConditionContent.heading })).toBeVisible()
    const future = new Date(Date.now() + 45 * 24 * 60 * 60 * 1000)
    const dateParts = new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    }).formatToParts(future)
    const part = (type: string): string =>
      dateParts.find((value) => value.type === type)?.value ?? ''
    const endAt = `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`
    await page.getByLabel(planConditionContent.endAt).fill(endAt)
    await page.getByLabel(`${planConditionContent.rate} (%)`).fill('10')
    await page.getByRole('button', { name: planConditionContent.review }).click()
    await expect(page.getByRole('heading', { name: '검토할 조건' })).toBeVisible()
    await page.getByRole('button', { name: planConditionContent.confirm }).click()
    await expect(page.getByRole('heading', { name: planConditionContent.saved })).toBeVisible()
    await expect(page.getByText(planConditionContent.collection.notStarted)).toBeVisible()

    await page.setViewportSize({ width: 320, height: 800 })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    const startCollection = page.getByRole('button', {
      name: planConditionContent.collection.start,
    })
    await expect(startCollection).toBeVisible()
    await startCollection.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByText(planConditionContent.collection.complete)).toBeVisible({
      timeout: 15_000,
    })
    await expect(page.getByText(planConditionContent.collection.completeHelp)).toBeVisible()
    const startClassification = page.getByRole('button', {
      name: planConditionContent.collection.classify,
    })
    await expect(startClassification).toBeVisible()
    await startClassification.focus()
    await page.keyboard.press('Enter')
    await expect(
      page.getByText(planConditionContent.collection.classificationComplete),
    ).toBeVisible({
      timeout: 15_000,
    })
    await expect(
      page.getByText(/입금 사건: 0 · 출금 사건: 0 · 제외 사건: 0 · 검토 필요: 0/),
    ).toBeVisible()
    const classifiedText = await page.locator('body').innerText()
    expect(classifiedText).not.toContain('BTC')
    expect(classifiedText).not.toContain('USDT')
    for (const [zoom, width] of [
      ['200%', 640],
      ['400%', 320],
    ] as const) {
      // Browser zoom narrows the CSS viewport; use the corresponding CSS widths without scaling the DOM.
      await page.setViewportSize({ width, height: 800 })
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
        `${zoom} zoom equivalent (${String(width)} CSS px) should not require horizontal scrolling`,
      ).toBe(true)
    }
  })
})
