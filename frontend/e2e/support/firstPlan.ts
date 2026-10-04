/*
 * The way to a first official result on the injected same-origin stack, shared by the suites that start from
 * one (Story 2.7's first result, Story 2.10's history, Story 2.11's sync): sign in with the local OIDC stub,
 * connect the Bybit stub with a scenario, take the first official snapshot, save plan conditions, collect and
 * classify the initial inputs, convert them and publish the first plan. Every step is started the way a
 * person would start it; nothing here asserts more than the step needs to go on.
 *
 * Not a test file: Playwright collects `*.spec.ts` only, so the suites import these steps rather than each
 * other (importing a spec would register its tests twice).
 */
import { expect, type Page } from '@playwright/test'

import { authContent } from '../../src/content/auth'
import { connectionContent } from '../../src/content/connection'
import { firstSnapshotContent } from '../../src/content/firstSnapshot'
import { planConditionContent } from '../../src/content/planConditions'
import { planPublicationContent } from '../../src/content/planResult'

const STUB_CONSENT = '동의하고 계속하기'

/**
 * Passes the local OIDC stub's consent page as the given subject: the same person across a sign-in, a
 * reauthentication or a second sign-in (the stub's default is a new subject per request).
 */
export async function consent(page: Page, subject: string): Promise<void> {
  await expect(page.getByRole('link', { name: STUB_CONSENT })).toBeVisible()
  const url = new URL(page.url())
  url.searchParams.set('dev_subject', subject)
  await page.goto(url.toString())
  await page.getByRole('link', { name: STUB_CONSENT }).click()
}

/**
 * Signs in, connects the stub with the given scenario, takes the first official snapshot and saves
 * plan conditions. Resolves with the credentials used, the host time just after the snapshot
 * appeared (the snapshot's measured time, t0, is not later than that) and the stub subject the person
 * signed in as (a later reauthentication must be the same person). `firstValue` is the first official
 * value the scenario's wallet reports — a constant for most scenarios; one that moves with time names a
 * pattern.
 */
export async function connectAndSavePlan(
  page: Page,
  scenario: string,
  firstValue = /1,234\.56 USD/,
): Promise<{ key: string; secret: string; snapshotSeenAt: number; subject: string }> {
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
  await expect(page.getByText(firstValue)).toBeVisible()
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
  return { key, secret, snapshotSeenAt, subject }
}

/** Collects the initial inputs and classifies them, both started from the keyboard at 320px. */
export async function collectAndClassify(page: Page, summary: RegExp): Promise<void> {
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

export async function startConversionByKeyboard(page: Page) {
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
 * session in these suites is a first login, whose landing screen is the onboarding screen: the session
 * gate sends it there from `/dashboard`, and that screen shows the same result panel. The assertion
 * names that one destination — accepting either path would hide a gate that stopped redirecting.
 */
export async function publishAndOpenResult(page: Page, landing = '/onboarding'): Promise<void> {
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
