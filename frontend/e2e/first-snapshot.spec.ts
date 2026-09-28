import { expect, test, type Page } from '@playwright/test'

import { authContent } from '../src/content/auth'
import { connectionContent } from '../src/content/connection'
import { entryContent } from '../src/content/entry'
import { firstSnapshotContent } from '../src/content/firstSnapshot'
import { planConditionContent } from '../src/content/planConditions'
import {
  activePlanContent,
  METRIC_ORDER,
  NEXT_ACTION_FALLBACK,
  nextActionCopy,
  planPublicationContent,
  planResultContent,
} from '../src/content/planResult'

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
  // A random suffix: parallel local workers started in the same millisecond must not share a
  // Bybit UID (the second connection would be refused as an account another user already holds).
  const uid = `snapshot${String(Date.now())}${Math.random().toString(36).slice(2, 8)}`
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

/**
 * Starts the first publication from the keyboard and follows its result link to the dashboard. Every
 * session in this suite is a first login, whose landing screen is the onboarding screen: the session
 * gate sends it there from `/dashboard`, and that screen shows the same result panel. The assertion
 * names that one destination — accepting either path would hide a gate that stopped redirecting.
 */
async function publishAndOpenResult(page: Page, landing = '/onboarding'): Promise<void> {
  const publication = page.getByRole('region', { name: planPublicationContent.heading })
  const start = publication.getByRole('button', { name: planPublicationContent.start })
  await expect(start).toBeVisible({ timeout: 15_000 })
  await start.focus()
  await page.keyboard.press('Enter')
  await expect(publication.getByRole('status')).toContainText(planPublicationContent.published, {
    timeout: 15_000,
  })
  const link = publication.getByRole('link', { name: planPublicationContent.dashboardLink })
  await expect(link).toHaveAttribute('href', '/dashboard')
  await link.focus()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(new RegExp(`${landing}$`))
}

/**
 * The official dashboard (Story 2.8), read from one `GET /api/dashboard` answer. The order is mobile
 * first: the last normal current value with its measurement time, the three trust axes (with the note
 * that separates the two times), the other five metrics, the next action, then the plan terms. Six
 * cards in order, each with its nature; one status region and no other live region; the title and its
 * disclaimer adjacent; the old 2.7 surface is gone.
 */
async function expectFirstResult(page: Page, derived: 'OFFICIAL' | 'ESTIMATED'): Promise<void> {
  const panel = page.getByRole('region', { name: planResultContent.heading })
  await expect(panel.getByRole('region', { name: planResultContent.currentHeading })).toBeVisible({
    timeout: 15_000,
  })
  /*
   * The one surface, asked from the page itself (the session cookie is `Secure`, which the browser sends
   * to this loopback origin): it answers READY without caching, and the 2.7 path is gone. Its codes and
   * times anchor the assertions below to the content copy instead of to literals.
   */
  const probe = await page.evaluate(async () => {
    const dashboard = await fetch('/api/dashboard', {
      credentials: 'same-origin',
      cache: 'no-store',
    })
    const body = (await dashboard.json()) as {
      state?: string
      nextAction?: string
      result?: {
        measuredAt: string
        basisAt: string
        trust: { freshness: { state: string }; inputReliability: { state: string } }
      }
    }
    const old = await fetch('/api/plans/active', { credentials: 'same-origin', cache: 'no-store' })
    return {
      status: dashboard.status,
      cacheControl: dashboard.headers.get('cache-control') ?? '',
      state: body.state,
      nextAction: body.nextAction ?? '',
      measuredAt: body.result?.measuredAt ?? '',
      basisAt: body.result?.basisAt ?? '',
      freshness: body.result?.trust.freshness.state ?? '',
      reliability: body.result?.trust.inputReliability.state ?? '',
      old: old.status,
    }
  })
  expect(probe).toMatchObject({ status: 200, state: 'READY', reliability: 'CONFIRMED', old: 404 })
  expect(probe.cacheControl).toContain('no-store')
  expect(['NONE', 'WAIT_FOR_SYNC']).toContain(probe.nextAction)

  await expect(panel.getByRole('heading', { level: 3 })).toHaveText([
    planResultContent.currentHeading,
    planResultContent.trustHeading,
    planResultContent.metricsHeading,
    planResultContent.nextActionHeading,
    planResultContent.conditionsHeading,
  ])
  const trust = panel.getByRole('region', { name: planResultContent.trustHeading })
  const axes = trust.getByRole('group')
  await expect(axes).toHaveCount(3)
  // The times as this page formats them (Asia/Seoul), read from the axes that vouch for them.
  const measured = await axes
    .nth(0)
    .locator(`time[datetime="${probe.measuredAt}"]`)
    .first()
    .innerText()
  const basis = await axes.nth(2).locator(`time[datetime="${probe.basisAt}"]`).first().innerText()

  const cards = panel.getByRole('article')
  await expect(cards).toHaveCount(METRIC_ORDER.length)
  for (const [index, metric] of METRIC_ORDER.entries()) {
    const card = cards.nth(index)
    await expect(card.getByRole('heading')).toHaveText(planResultContent.metrics[metric].name)
    await expect(card).toContainText(
      `${planResultContent.natureLabel}: ${planResultContent.natures[index === 0 ? 'OFFICIAL' : derived] ?? ''}`,
    )
  }
  await expect(cards.nth(0)).toContainText('1,234.56 USD')
  await expect(cards.nth(0)).toContainText(planResultContent.measuredLine(measured))
  // The current value is shown once: the first-snapshot panel does not repeat it.
  await expect(page.getByRole('heading', { name: firstSnapshotContent.heading })).toHaveCount(0)
  const conditions = panel.getByRole('region', { name: planResultContent.conditionsHeading })
  await expect(conditions.getByText(planResultContent.periods.DAILY ?? '')).toBeVisible()
  await expect(conditions.getByText('10%')).toBeVisible()
  const { freshness, reliability, nature } = planResultContent.axes
  await expect(axes.nth(0)).toHaveAccessibleName(
    `${freshness.name}: ${freshness.states[probe.freshness] ?? ''}`,
  )
  await expect(axes.nth(1)).toHaveAccessibleName(
    `${reliability.name}: ${reliability.states.CONFIRMED ?? ''}`,
  )
  await expect(axes.nth(2)).toHaveAccessibleName(`${nature.name}: ${nature.states[derived] ?? ''}`)
  for (const label of [freshness.lastNormal, freshness.verifiedThrough, freshness.timesNote]) {
    await expect(axes.nth(0).getByText(label, { exact: true })).toBeVisible()
  }
  await expect(
    panel.getByRole('region', { name: planResultContent.nextActionHeading }),
  ).toContainText(nextActionCopy(probe.nextAction, measured))
  await expect(page.getByRole('status')).toHaveCount(1)
  await expect(page.locator('[aria-live]')).toHaveCount(1)
  await expect(page.getByRole('status')).toHaveText(planResultContent.status.ready(basis))
  const title = page.getByRole('heading', { level: 1 })
  await expect(title).toHaveText(authContent.pageTitle)
  await expect(title.locator('xpath=following-sibling::*[1]')).toHaveText(authContent.disclaimer)
  const panelText = await panel.innerText()
  expect(panelText).not.toMatch(
    /축하|손실|추천합니다|투자성과|계좌 수익률|스트릭|배지|NaN|undefined/,
  )
  // Every code the server sent had its own copy: no fallback sentence is on the panel.
  for (const fallback of [
    planResultContent.axes.unknownState,
    planResultContent.axes.unknownCause,
    planResultContent.axes.unknownMetric,
    planResultContent.unknownReason,
    planResultContent.unknownPeriod,
    NEXT_ACTION_FALLBACK,
    planResultContent.timeUnavailable,
  ]) {
    expect(panelText).not.toContain(fallback)
  }
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
  expect(page.url()).not.toMatch(/[?&](planId|calculationId|snapshotId)=/)
}

/**
 * The keyboard reaches the header and then the dashboard's one control, in reading order, from a fresh
 * load of the landing screen.
 */
async function expectKeyboardOrder(page: Page): Promise<void> {
  await page.reload()
  await expect(
    page
      .getByRole('region', { name: planResultContent.heading })
      .getByRole('region', { name: planResultContent.currentHeading }),
  ).toBeVisible({ timeout: 15_000 })
  const reached: string[] = []
  for (let step = 0; step < 4; step += 1) {
    await page.keyboard.press('Tab')
    reached.push(await page.evaluate(() => document.activeElement?.textContent.trim() ?? ''))
  }
  expect(reached).toEqual([
    entryContent.skipLink,
    authContent.settings.link,
    authContent.signOut.label,
    planResultContent.reload,
  ])
  // The control is a real 44px target.
  const box = await page.getByRole('button', { name: planResultContent.reload }).boundingBox()
  expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
  expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)
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
    // The chain now runs through the first publication and its result.
    test.setTimeout(90_000)
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

    // Story 2.7: the calculable conversion admits the first publication; the dashboard then shows
    // the official result from one pointer, every derived metric official (no estimated flow).
    await page.setViewportSize({ width: 320, height: 800 })
    await publishAndOpenResult(page)
    await expectFirstResult(page, 'OFFICIAL')
    await expectNoHorizontalScroll(page)
    await page.setViewportSize({ width: 320, height: 800 })
    await expectKeyboardOrder(page)
    await page.goto(planConditionContent.path)
    await expect(page.getByRole('heading', { name: activePlanContent.heading })).toBeVisible()
    await expect(page.getByRole('link', { name: activePlanContent.dashboardLink })).toBeVisible()
    await expect(page.getByLabel(planConditionContent.endAt)).toHaveCount(0)
  })

  test('비-USDT 흐름의 추정 환산과 보류 목록을 확인하고, 추정 환산만 있는 계획을 발행해 다섯 지표를 추정으로 보여 준다', async ({
    page,
    browser,
    baseURL,
  }) => {
    // The stub's flows sit just after t0, so their event minute must close before prices exist.
    // Both users below need that wait; they share one so the serial CI suite waits once, not twice.
    test.setTimeout(180_000)
    await connectAndSavePlan(page, 'marketflows')
    await collectAndClassify(page, /입금 사건: 1 · 출금 사건: 1 · 제외 사건: 0 · 검토 필요: 0/)
    // Story 2.7: a second user whose only flow is one BTC deposit — calculable once estimated.
    // Its context is closed whatever happens, so a failing assertion never leaks a browser context.
    const estimatedContext = await browser.newContext({ baseURL })
    try {
      const estimated = await estimatedContext.newPage()
      const { snapshotSeenAt } = await connectAndSavePlan(estimated, 'btcdeposit')
      await collectAndClassify(
        estimated,
        /입금 사건: 1 · 출금 사건: 0 · 제외 사건: 0 · 검토 필요: 0/,
      )

      // Wait until the minute that holds the later t0 has closed on the stack's clock (with a margin
      // for skew), so every BTC candle exists and the run is deterministic rather than "not closed yet".
      const closed = Math.floor(snapshotSeenAt / MINUTE_MS) * MINUTE_MS + MINUTE_MS + 5_000
      await expect
        .poll(() => Date.now(), { timeout: 2 * MINUTE_MS, intervals: [1_000] })
        .toBeGreaterThanOrEqual(closed)

      const estimatedConversion = await startConversionByKeyboard(estimated)
      await expect(estimatedConversion.getByRole('status')).toContainText(
        planConditionContent.conversion.estimated,
        { timeout: 15_000 },
      )
      await publishAndOpenResult(estimated)
      await expectFirstResult(estimated, 'ESTIMATED')
      // Six cards: the lead current value, then the five metrics of the same version.
      const cards = estimated
        .getByRole('region', { name: planResultContent.heading })
        .getByRole('article')
      await expect(cards.nth(0)).not.toContainText('추정 근거')
      for (const index of [1, 2, 3, 4, 5]) {
        await expect(cards.nth(index)).toContainText(planResultContent.estimatedEvidence(1))
      }
      await expectNoHorizontalScroll(estimated)
    } finally {
      await estimatedContext.close()
    }

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
