import { expect, test, type Page } from '@playwright/test'

import { authContent } from '../src/content/auth'
import { connectionContent } from '../src/content/connection'
import { firstSnapshotContent } from '../src/content/firstSnapshot'
import { planConditionContent } from '../src/content/planConditions'

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''
const STUB_CONSENT = '동의하고 계속하기'
const MINUTE_MS = 60_000

async function consent(page: Page, subject: string): Promise<void> {
  await expect(page.getByRole('link', { name: STUB_CONSENT })).toBeVisible()
  const url = new URL(page.url())
  url.searchParams.set('dev_subject', subject)
  await page.goto(url.toString())
  await page.getByRole('link', { name: STUB_CONSENT }).click()
}

/**
 * Signs in, connects the stub with the given scenario, takes the first official snapshot and saves
 * plan conditions. Resolves with the credentials used and the host time just after the snapshot
 * appeared (the snapshot's measured time, t0, is not later than that).
 */
async function connectAndSavePlan(
  page: Page,
  scenario: string,
): Promise<{ key: string; secret: string; snapshotSeenAt: number }> {
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
  const key = `${scenario}.${uid}`
  const secret = `s-${Math.random().toString(36).slice(2)}-${String(Date.now())}`
  await page.getByLabel(connectionContent.form.apiKeyLabel).fill(key)
  await page.getByLabel(connectionContent.form.apiSecretLabel).fill(secret)
  await page.getByRole('button', { name: connectionContent.form.submit }).click()
  await expect(page.getByText(connectionContent.succeeded)).toBeVisible()

  await page.goto('/onboarding')
  await expect(page.getByRole('heading', { name: firstSnapshotContent.heading })).toBeVisible()
  await page.getByRole('button', { name: firstSnapshotContent.start }).click()
  await expect(page.getByText(firstSnapshotContent.succeeded)).toBeVisible({ timeout: 15_000 })
  const snapshotSeenAt = Date.now()
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
  const part = (type: string): string => dateParts.find((value) => value.type === type)?.value ?? ''
  const endAt = `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`
  await page.getByLabel(planConditionContent.endAt).fill(endAt)
  await page.getByLabel(`${planConditionContent.rate} (%)`).fill('10')
  await page.getByRole('button', { name: planConditionContent.review }).click()
  await expect(page.getByRole('heading', { name: '검토할 조건' })).toBeVisible()
  await page.getByRole('button', { name: planConditionContent.confirm }).click()
  await expect(page.getByRole('heading', { name: planConditionContent.saved })).toBeVisible()
  await expect(page.getByText(planConditionContent.collection.notStarted)).toBeVisible()
  return { key, secret, snapshotSeenAt }
}

/** Collects the initial inputs and classifies them, both started from the keyboard at 320px. */
async function collectAndClassify(page: Page, summary: RegExp): Promise<void> {
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
  await expect(page.getByText(planConditionContent.collection.classificationComplete)).toBeVisible({
    timeout: 15_000,
  })
  await expect(page.getByText(summary)).toBeVisible()
  // The classification summary itself carries no asset or amount detail. The conversion section
  // below names the USD unit in its heading, so the check is scoped to the classification status.
  const classifiedText = await page
    .getByRole('status')
    .filter({ hasText: planConditionContent.collection.classificationComplete })
    .innerText()
  expect(classifiedText).not.toContain('BTC')
  expect(classifiedText).not.toContain('USDT')
}

async function startConversionByKeyboard(page: Page) {
  const conversion = page.getByRole('region', { name: planConditionContent.conversion.heading })
  const startConversion = conversion.getByRole('button', {
    name: planConditionContent.conversion.start,
  })
  await expect(startConversion).toBeVisible({ timeout: 15_000 })
  await startConversion.focus()
  await page.keyboard.press('Enter')
  return conversion
}

async function expectNoHorizontalScroll(page: Page): Promise<void> {
  for (const [zoom, width] of [
    ['320px', 320],
    ['200%', 640],
    ['400%', 320],
  ] as const) {
    // Browser zoom narrows the CSS viewport; use the corresponding CSS widths without scaling the DOM.
    await page.setViewportSize({ width, height: 800 })
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      `${zoom} (${String(width)} CSS px) should not require horizontal scrolling`,
    ).toBe(true)
  }
}

test.describe('첫 공식 현재가치', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 실제 동일 출처 스텁에서만 성공 왕복을 검증한다.
  test.skip(injectedOrigin === '', 'PLAYWRIGHT_BASE_URL의 동일 출처 스텁이 필요하다.')

  test('연결 후 비동기 첫 확인을 접수하고 측정 시각과 세 신뢰 축을 보여 준다', async ({ page }) => {
    await connectAndSavePlan(page, 'uta2')
    await collectAndClassify(page, /입금 사건: 0 · 출금 사건: 0 · 제외 사건: 0 · 검토 필요: 0/)

    const conversion = await startConversionByKeyboard(page)
    const conversionStatus = conversion.getByRole('status')
    await expect(conversionStatus).toContainText(planConditionContent.conversion.converted, {
      timeout: 15_000,
    })
    // No external flows: the copy says there was nothing to convert, not "by direct normalization".
    await expect(conversionStatus).toContainText(planConditionContent.conversion.noFlowsHelp)
    await expect(conversionStatus).not.toContainText(planConditionContent.conversion.convertedHelp)
    await expect(
      conversion.getByText(planConditionContent.conversion.counts(0, 0, 0, 0, 0)),
    ).toBeVisible()
    await expect(conversion.getByText(/BYBIT-PRICE-1\.0 · CALC-1\.0/)).toBeVisible()
    await expect(
      conversion.getByRole('button', { name: planConditionContent.conversion.retryAction }),
    ).toHaveCount(0)
    const convertedText = await conversion.innerText()
    expect(convertedText).not.toMatch(/\d\s*USD|USD\s*\d|수익률|목표 달성률|계산에서 제외/)
    await expectNoHorizontalScroll(page)
  })

  test('비-USDT 흐름의 추정 환산과 보류 목록을 키보드와 확대 환경에서 확인한다', async ({
    page,
  }) => {
    // The stub's flows sit just after t0, so their event minute must close before prices exist.
    test.setTimeout(180_000)
    const { snapshotSeenAt } = await connectAndSavePlan(page, 'marketflows')
    await collectAndClassify(page, /입금 사건: 1 · 출금 사건: 1 · 제외 사건: 0 · 검토 필요: 0/)

    // Wait until the minute that holds t0 has closed on the stack's clock (with a margin for skew),
    // so the BTC candle exists and the run is deterministic rather than "not closed yet".
    const closed = Math.floor(snapshotSeenAt / MINUTE_MS) * MINUTE_MS + MINUTE_MS + 5_000
    await expect
      .poll(() => Date.now(), { timeout: 2 * MINUTE_MS, intervals: [1_000] })
      .toBeGreaterThanOrEqual(closed)

    const conversion = await startConversionByKeyboard(page)
    const conversionStatus = conversion.getByRole('status')
    await expect(conversionStatus).toContainText(planConditionContent.conversion.required, {
      timeout: 15_000,
    })
    await expect(conversionStatus).toContainText(planConditionContent.conversion.requiredHelp)
    await expect(
      conversion.getByText(planConditionContent.conversion.counts(2, 0, 1, 1, 0)),
    ).toBeVisible()
    await expect(
      conversion.getByRole('button', { name: planConditionContent.conversion.retryAction }),
    ).toHaveCount(0)
    await expect(conversion.getByText(planConditionContent.conversion.planSwitch)).toBeVisible()

    // The held list: one asset group, opened from the keyboard.
    await expect(conversion.getByText(planConditionContent.conversion.pendingHeading)).toBeVisible()
    const summary = conversion.getByText(planConditionContent.conversion.pendingAsset('HALT', 1))
    await expect(summary).toBeVisible()
    await expect(conversion.getByText('2.5 HALT')).toBeHidden()
    await summary.focus()
    await page.keyboard.press('Enter')
    const group = conversion.getByRole('group').filter({ hasText: 'HALT' })
    await expect(group.getByText('2.5 HALT')).toBeVisible()
    await expect(group.getByText(planConditionContent.conversion.withdrawal)).toBeVisible()
    await expect(group.getByText(planConditionContent.conversion.impactText)).toBeVisible()
    await expect(group.getByText(/Asia\/Seoul/)).toBeVisible()
    await expect(
      group.getByText(planConditionContent.conversion.reasons.PRICE_ROUTE_UNAVAILABLE.cause),
    ).toBeVisible()

    // The estimated BTC deposit is not itemized, and no USD amount appears anywhere.
    const heldText = await conversion.innerText()
    expect(heldText).not.toContain('BTC')
    expect(heldText).not.toMatch(/\d\s*USD|USD\s*\d|수익률|목표 달성률|계산에서 제외/)
    await expectNoHorizontalScroll(page)
  })
})
