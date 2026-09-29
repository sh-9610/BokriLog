import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import { adjustmentHistoryContent } from '../src/content/adjustmentHistory'
import { authContent } from '../src/content/auth'
import { calculationEvidenceContent } from '../src/content/calculationEvidence'
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
import { planSyncContent } from '../src/content/planSync'

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
 * The keyboard reaches the header and then the dashboard's four controls, in reading order, from a fresh
 * load of the landing screen: the calculation evidence button beside the metrics heading (Story 2.9), the
 * adjustment history button right after it (Story 2.10), then the manual sync (Story 2.11) and the reload in
 * the next-action section.
 */
async function expectKeyboardOrder(page: Page): Promise<void> {
  await page.reload()
  await expect(
    page
      .getByRole('region', { name: planResultContent.heading })
      .getByRole('region', { name: planResultContent.currentHeading }),
  ).toBeVisible({ timeout: 15_000 })
  const reached: string[] = []
  for (let step = 0; step < 7; step += 1) {
    await page.keyboard.press('Tab')
    reached.push(await page.evaluate(() => document.activeElement?.textContent.trim() ?? ''))
  }
  expect(reached).toEqual([
    entryContent.skipLink,
    authContent.settings.link,
    authContent.signOut.label,
    calculationEvidenceContent.open,
    adjustmentHistoryContent.open,
    planSyncContent.button,
    planResultContent.reload,
  ])
  // The controls are real 44px targets.
  for (const name of [
    calculationEvidenceContent.open,
    adjustmentHistoryContent.open,
    planSyncContent.button,
    planResultContent.reload,
  ]) {
    const box = await page.getByRole('button', { name }).boundingBox()
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)
  }
}

/**
 * Story 2.11: the manual sync of the active plan, from the keyboard at 320 CSS px. The press says
 * `동기화 중` within the second the screen promises, keeps the button's name, holds focus on it and blocks a
 * second request; the end swaps the whole bundle to the next official version at once and moves focus to the
 * result heading. The attempt, the dashboard and the evidence agree on the new version: a NORMAL collection
 * with its overlap start, the previous version kept as history. Right after, the server names the cooldown:
 * the line says its seconds, a press starts nothing, and the button opens again once the seconds pass.
 */
async function expectManualSync(page: Page): Promise<void> {
  await page.setViewportSize({ width: 320, height: 800 })
  const panel = page.getByRole('region', { name: planResultContent.heading })
  const next = panel.getByRole('region', { name: planResultContent.nextActionHeading })
  const control = next.getByRole('button', { name: planSyncContent.button })
  await expect(control).toHaveAttribute('aria-disabled', 'false')
  await expect(next.getByText(planSyncContent.lateEvents)).toBeVisible()
  const before = await page.evaluate(async () => {
    const shown = (await (
      await fetch('/api/dashboard', { credentials: 'same-origin', cache: 'no-store' })
    ).json()) as { result: { pointerVersion: number } }
    return shown.result.pointerVersion
  })

  await control.focus()
  await page.keyboard.press('Enter')
  await expect(next.getByText(planSyncContent.syncing, { exact: true })).toBeVisible({
    timeout: 1_000,
  })
  await expect(control).toBeFocused()
  await expect(control).toHaveAccessibleName(planSyncContent.button)
  await expect(page.getByRole('status')).toContainText(planSyncContent.status.syncing)

  await expect(page.getByRole('status')).toContainText(planSyncContent.status.published, {
    timeout: 30_000,
  })
  await expect(
    panel.getByRole('heading', { level: 2, name: planResultContent.heading }),
  ).toBeFocused()
  await expect(page.getByRole('status')).toHaveCount(1)
  await expect(page.locator('[aria-live]')).toHaveCount(1)

  const probe = await page.evaluate(async () => {
    const read = async (
      path: string,
    ): Promise<{ status: number; cache: string; body: unknown }> => {
      const answer = await fetch(path, { credentials: 'same-origin', cache: 'no-store' })
      return {
        status: answer.status,
        cache: answer.headers.get('cache-control') ?? '',
        body: (await answer.json()) as unknown,
      }
    }
    const dashboard = (await read('/api/dashboard')).body as {
      result: { planId: string; pointerVersion: number; calculationId: string }
    }
    const sync = await read('/api/dashboard/sync')
    const job = (sync.body as { job?: Record<string, unknown> }).job ?? {}
    const { planId, pointerVersion, calculationId } = dashboard.result
    const evidence = (
      await read(
        `/api/dashboard/evidence?planId=${planId}&pointerVersion=${String(pointerVersion)}&calculationId=${calculationId}`,
      )
    ).body as {
      collection: {
        mode: string
        rangeStart: string
        inherited: { previousPointerVersion: number; flowCount: number } | null
      }
      times: { t0: string }
      versions: {
        official: { current: boolean; supersededAt: string | null }[]
        officialCount: number
      }
    }
    return {
      pointerVersion,
      syncStatus: sync.status,
      syncCache: sync.cache,
      job: {
        trigger: job.trigger,
        status: job.status,
        mode: job.mode,
        pointerVersion: job.pointerVersion,
      },
      jobId: job.jobId,
      jobKeys: Object.keys(job),
      mode: evidence.collection.mode,
      startsAtT0: evidence.collection.rangeStart === evidence.times.t0,
      inherited: evidence.collection.inherited,
      officialCount: evidence.versions.officialCount,
      current: evidence.versions.official.map((version) => version.current),
      superseded: evidence.versions.official.map((version) => version.supersededAt !== null),
    }
  })
  expect(probe).toMatchObject({
    pointerVersion: before + 1,
    syncStatus: 200,
    job: { trigger: 'MANUAL', status: 'PUBLISHED', mode: 'NORMAL', pointerVersion: before + 1 },
    mode: 'NORMAL',
    // A plan this young overlaps back to its own start: the collection begins at t0.
    startsAtT0: true,
    inherited: { previousPointerVersion: before, flowCount: 0 },
    officialCount: before + 1,
    current: [true, false],
    superseded: [false, true],
  })
  expect(probe.syncCache).toContain('no-store')
  // The attempt carries codes and times and its own id only.
  expect(probe.jobKeys).toEqual([
    'jobId',
    'trigger',
    'status',
    'stage',
    'mode',
    'failureCode',
    'retryable',
    'createdAt',
    'finishedAt',
    'pointerVersion',
  ])

  // Right after: the attempt's last state read carried the server's cooldown. The button is unavailable
  // under its own name, the line says the seconds, and a press starts nothing while focus stays on it.
  const cooldown = new RegExp(
    planSyncContent.cooldown(99).replace(/\./g, '\\.').replace('99', '\\d+'),
  )
  await expect(next.getByText(cooldown)).toBeVisible({ timeout: 5_000 })
  await expect(control).toHaveAttribute('aria-disabled', 'true')
  await expect(control).toHaveAccessibleDescription(cooldown)
  await control.focus()
  await page.keyboard.press('Enter')
  await expect(control).toBeFocused()
  const latest = await page.evaluate(async () => {
    const sync = (await (
      await fetch('/api/dashboard/sync', { credentials: 'same-origin', cache: 'no-store' })
    ).json()) as { job?: { jobId: string } }
    return sync.job?.jobId ?? ''
  })
  expect(latest, 'the press during the cooldown started no attempt').toBe(probe.jobId)

  const text = await next.innerText()
  expect(text).not.toMatch(/실시간|정확|축하|손실|스트릭|배지|NaN|undefined/)
  const axe = await new AxeBuilder({ page })
    .include('[aria-labelledby="plan-result-next"]')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  expect(axe.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  )
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
  expect(page.url()).not.toMatch(/[?&](planId|calculationId|snapshotId|jobId)=/)
  // The cooldown ends on its own: the button opens again without a reload (the server decides the press).
  await expect(control).toHaveAttribute('aria-disabled', 'false', { timeout: 35_000 })
  await expect(next.getByText(cooldown)).toHaveCount(0)
}

/**
 * Story 2.9: the calculation evidence of the shown result, opened and closed from the keyboard on the same
 * screen. The API answers the shown pointer only — a reference that is not the current pointer, or none,
 * is the common 404 — without caching and without anything internal to the account. The region reads
 * snapshots → times → input versions → calculation version → metric impact → versions, keeps long ids
 * whole, and has no live region of its own. With the region open, the page does not scroll sideways at 320
 * and 640 CSS px — the widths that 400% and 200% zoom of a 1280px window come to — and the region itself
 * fits each of them.
 */
async function expectEvidence(page: Page, derived: 'OFFICIAL' | 'ESTIMATED'): Promise<void> {
  const probe = await page.evaluate(async () => {
    const shown = (await (
      await fetch('/api/dashboard', { credentials: 'same-origin', cache: 'no-store' })
    ).json()) as { result: { planId: string; pointerVersion: number; calculationId: string } }
    const at = (planId: string, version: number, calculationId: string): string =>
      `/api/dashboard/evidence?planId=${planId}&pointerVersion=${String(version)}&calculationId=${calculationId}`
    const { planId, pointerVersion, calculationId } = shown.result
    const answer = await fetch(at(planId, pointerVersion, calculationId), {
      credentials: 'same-origin',
      cache: 'no-store',
    })
    const text = await answer.text()
    const body = JSON.parse(text) as {
      snapshots: { portfolioSnapshotId: string }
      times: { measuredAt: string; cashflowVerifiedThrough: string }
      calculation: { inputHash: string }
    }
    const stale = await fetch(at(planId, pointerVersion + 1, calculationId), {
      credentials: 'same-origin',
      cache: 'no-store',
    })
    const bare = await fetch('/api/dashboard/evidence', {
      credentials: 'same-origin',
      cache: 'no-store',
    })
    return {
      status: answer.status,
      cacheControl: answer.headers.get('cache-control') ?? '',
      keys: Object.keys(JSON.parse(text) as object),
      text,
      calculationId,
      snapshotId: body.snapshots.portfolioSnapshotId,
      inputHash: body.calculation.inputHash,
      sameTimes: body.times.measuredAt === body.times.cashflowVerifiedThrough,
      stale: stale.status,
      staleCode: ((await stale.json()) as { code?: string }).code ?? '',
      bare: bare.status,
    }
  })
  expect(probe).toMatchObject({ status: 200, stale: 404, staleCode: 'NOT_FOUND', bare: 404 })
  expect(probe.cacheControl).toContain('no-store')
  expect(probe.keys).toEqual([
    'planId',
    'pointerVersion',
    'calculationId',
    'snapshots',
    'times',
    'inputs',
    'collection',
    'calculation',
    'metrics',
    'versions',
  ])
  expect(probe.text).not.toMatch(/userId|providerAccount|secret|apiKey|credential/i)

  const panel = page.getByRole('region', { name: planResultContent.heading })
  const toggle = panel.getByRole('button', { name: calculationEvidenceContent.open })
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await toggle.focus()
  await page.keyboard.press('Enter')
  await expect(toggle).toHaveAttribute('aria-expanded', 'true')
  const evidence = panel.getByRole('region', { name: calculationEvidenceContent.heading })
  await expect(evidence.getByRole('heading', { level: 4 })).toBeFocused()
  const { sections, times, metrics, versions } = calculationEvidenceContent
  await expect(evidence.getByRole('heading', { level: 5 })).toHaveText(
    [
      sections.snapshots,
      sections.times,
      sections.inputs,
      sections.calculation,
      sections.metrics,
      sections.versions,
    ],
    { timeout: 15_000 },
  )
  // Long ids whole, in the mono style.
  for (const id of [probe.calculationId, probe.snapshotId, probe.inputHash]) {
    await expect(evidence.locator('code', { hasText: id }).first()).toHaveText(id)
  }
  await expect(evidence).toContainText(times.noCommonSnapshot)
  await expect(evidence).toContainText(probe.sameTimes ? times.same : times.different)
  const impact = evidence.getByRole('group', { name: sections.metrics }).getByRole('listitem')
  await expect(impact).toHaveCount(METRIC_ORDER.length)
  await expect(impact.nth(0)).toContainText(metrics.currentValueCause)
  for (const index of [1, 2, 3, 4, 5]) {
    await expect(impact.nth(index)).toContainText(
      derived === 'ESTIMATED'
        ? metrics.estimatedCause(calculationEvidenceContent.evidenceTypes.SPOT_KLINE_CLOSE, 1)
        : metrics.noEstimatedCause,
    )
  }
  const versionList = evidence.getByRole('group', { name: sections.versions })
  await expect(versionList).toContainText(versions.current)
  await expect(versionList.getByText(versions.none, { exact: true })).toBeVisible()
  await expect(evidence.getByRole('link')).toHaveCount(0)
  // The screen's one status region says what the evidence read did; the region has none of its own.
  await expect(page.getByRole('status')).toHaveCount(1)
  await expect(page.locator('[aria-live]')).toHaveCount(1)
  await expect(page.getByRole('status')).toContainText(calculationEvidenceContent.status.ready)
  const evidenceText = await evidence.innerText()
  expect(evidenceText).not.toMatch(
    /축하|손실|추천합니다|투자성과|계좌 수익률|스트릭|배지|NaN|undefined/,
  )
  expect(page.url()).not.toMatch(/[?&](planId|calculationId|snapshotId)=/)
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
  const axe = await new AxeBuilder({ page })
    .include('#calculation-evidence')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  expect(axe.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  for (const width of [320, 640]) {
    await page.setViewportSize({ width, height: 800 })
    const widths = await page.evaluate(() => {
      const open = document.getElementById('calculation-evidence')
      return {
        page: document.documentElement.scrollWidth,
        viewport: window.innerWidth,
        region: open === null ? Number.POSITIVE_INFINITY : open.scrollWidth - open.clientWidth,
      }
    })
    expect(widths.page, `${String(width)} CSS px: no horizontal page scroll`).toBeLessThanOrEqual(
      widths.viewport,
    )
    expect(widths.region, `${String(width)} CSS px: the region does not overflow`).toBe(0)
  }

  await page.setViewportSize({ width: 320, height: 800 })
  const close = evidence.getByRole('button', { name: calculationEvidenceContent.close })
  const box = await close.boundingBox()
  expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
  await close.focus()
  await page.keyboard.press('Enter')
  await expect(toggle).toBeFocused()
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await expect(panel.getByRole('region', { name: calculationEvidenceContent.heading })).toHaveCount(
    0,
  )
  await expect(page.getByRole('status')).not.toContainText(calculationEvidenceContent.status.ready)
}

/**
 * Story 2.10: the adjustment history of the shown result, from the keyboard on the same screen. The stub's
 * `adjustments` scenario gives 23 USDT flows — deposits of 10.005 and, every fourth, withdrawals of -2.5 —
 * so the history reads twenty rows, then `더 보기` brings the last three. The API answers the shown pointer
 * only, never caches and carries nothing internal to the account; the region reads description → summary →
 * filter → rows, filters by type, opens a row's lineage, and does not scroll sideways at 320 and 640 CSS px.
 */
async function expectAdjustments(page: Page): Promise<void> {
  const probe = await page.evaluate(async () => {
    const shown = (await (
      await fetch('/api/dashboard', { credentials: 'same-origin', cache: 'no-store' })
    ).json()) as { result: { planId: string; pointerVersion: number; calculationId: string } }
    const at = (version: number, extra = ''): string =>
      `/api/dashboard/adjustments?planId=${shown.result.planId}&pointerVersion=${String(version)}&calculationId=${shown.result.calculationId}${extra}`
    const answer = await fetch(at(shown.result.pointerVersion), {
      credentials: 'same-origin',
      cache: 'no-store',
    })
    const text = await answer.text()
    const body = JSON.parse(text) as {
      summary: { count: number; netUsdValue: string; excludedCount: number }
      rows: unknown[]
      nextCursor: string | null
    }
    const next = await fetch(
      at(shown.result.pointerVersion, `&cursor=${encodeURIComponent(body.nextCursor ?? '')}`),
      { credentials: 'same-origin', cache: 'no-store' },
    )
    const nextBody = (await next.json()) as { rows: unknown[]; nextCursor: string | null }
    const otherFilter = await fetch(
      at(
        shown.result.pointerVersion,
        `&direction=IN&cursor=${encodeURIComponent(body.nextCursor ?? '')}`,
      ),
      { credentials: 'same-origin', cache: 'no-store' },
    )
    const stale = await fetch(at(shown.result.pointerVersion + 1), {
      credentials: 'same-origin',
      cache: 'no-store',
    })
    return {
      status: answer.status,
      cacheControl: answer.headers.get('cache-control') ?? '',
      keys: Object.keys(JSON.parse(text) as object),
      text,
      summary: body.summary,
      rows: body.rows.length,
      hasNext: body.nextCursor !== null,
      nextRows: nextBody.rows.length,
      nextCursor: nextBody.nextCursor,
      otherFilter: otherFilter.status,
      stale: stale.status,
      staleCode: ((await stale.json()) as { code?: string }).code ?? '',
    }
  })
  expect(probe).toMatchObject({
    status: 200,
    rows: 20,
    hasNext: true,
    nextRows: 3,
    nextCursor: null,
    otherFilter: 404,
    stale: 404,
    staleCode: 'NOT_FOUND',
  })
  expect(probe.summary).toEqual({ count: 23, netUsdValue: '167.59', excludedCount: 0 })
  expect(probe.cacheControl).toContain('no-store')
  expect(probe.keys).toEqual([
    'planId',
    'pointerVersion',
    'calculationId',
    'direction',
    'appliedAt',
    'coverage',
    'summary',
    'rows',
    'nextCursor',
  ])
  expect(probe.text).not.toMatch(/userId|providerAccount|secret|apiKey|credential|semantic/i)

  const copy = adjustmentHistoryContent
  const panel = page.getByRole('region', { name: planResultContent.heading })
  const toggle = panel.getByRole('button', { name: copy.open })
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await toggle.focus()
  await page.keyboard.press('Enter')
  await expect(toggle).toHaveAttribute('aria-expanded', 'true')
  const history = panel.getByRole('region', { name: copy.heading })
  await expect(history.getByRole('heading', { level: 4 })).toBeFocused()
  const list = history.getByRole('list', { name: copy.list })
  await expect(list.getByRole('listitem')).toHaveCount(20, { timeout: 15_000 })
  const summary = history.getByRole('group', { name: copy.summary.heading })
  await expect(summary).toContainText('167.59 USD')
  await expect(summary).toContainText(copy.summary.countValue(23))
  await expect(history).toContainText(copy.term)
  // The excluded count stands apart from the filtered summary (the stub excludes nothing).
  await expect(history).toContainText(copy.excluded.text(0))
  await expect(summary).not.toContainText(copy.excluded.text(0))
  // The first two flows share one millisecond and one type: their sequences tell them apart, and their
  // Bybit transaction ids keep the source-id order.
  const first = list.getByRole('listitem').nth(0)
  const second = list.getByRole('listitem').nth(1)
  await expect(first.getByRole('heading', { level: 5 })).toContainText(copy.filter.options.IN)
  await expect(first.getByRole('heading', { level: 5 })).toContainText(copy.row.title(1))
  await expect(second.getByRole('heading', { level: 5 })).toContainText(copy.row.title(2))
  const instant = async (row: typeof first): Promise<string> =>
    (await row.getByRole('heading', { level: 5 }).locator('time').getAttribute('datetime')) ?? ''
  expect(await instant(first)).toBe(await instant(second))
  await expect(first).toContainText('10.005 USDT')
  await expect(first).toContainText('10.01 USD')
  for (const [row, id] of [
    [first, 'adjustment-01'],
    [second, 'adjustment-02'],
  ] as const) {
    const open = row.getByRole('button', {
      name: new RegExp(`^${copy.row.title(row === first ? 1 : 2)} `),
    })
    await open.focus()
    await page.keyboard.press('Enter')
    await expect(open).toHaveAttribute('aria-expanded', 'true')
    await expect(row.locator('code', { hasText: id })).toHaveText(id)
  }

  // From 768px the same row is laid out across it — still one list and no horizontal scroll.
  await page.setViewportSize({ width: 1024, height: 800 })
  const wide = await page.evaluate(() => {
    const open = document.getElementById('adjustment-history')
    const facts = open?.querySelector('li dl')
    const columns =
      facts === null || facts === undefined ? '' : getComputedStyle(facts).gridTemplateColumns
    return {
      page: document.documentElement.scrollWidth,
      viewport: window.innerWidth,
      region: open === null ? Number.POSITIVE_INFINITY : open.scrollWidth - open.clientWidth,
      columns: columns.split(' ').filter((part) => part !== '').length,
      lists: open?.querySelectorAll('ol[aria-labelledby]').length ?? 0,
    }
  })
  expect(wide.page, '1024 CSS px: no horizontal page scroll').toBeLessThanOrEqual(wide.viewport)
  expect(wide.region, '1024 CSS px: the region does not overflow').toBe(0)
  expect(wide.columns, '1024 CSS px: a row lays its facts across').toBeGreaterThan(1)
  expect(wide.lists, 'one DOM list at every width').toBe(1)
  await page.setViewportSize({ width: 320, height: 800 })
  const narrow = await page.evaluate(() => {
    const facts = document.getElementById('adjustment-history')?.querySelector('li dl')
    return facts === null || facts === undefined
      ? 0
      : getComputedStyle(facts)
          .gridTemplateColumns.split(' ')
          .filter((part) => part !== '').length
  })
  expect(narrow, '320 CSS px: a row is one card column').toBe(1)

  // 더 보기 from the keyboard: the next page is appended and focus moves to its first row.
  const more = history.getByRole('button', { name: copy.more })
  await more.focus()
  await page.keyboard.press('Enter')
  await expect(list.getByRole('listitem')).toHaveCount(23, { timeout: 15_000 })
  await expect(list.getByRole('listitem').nth(20).getByRole('heading', { level: 5 })).toBeFocused()
  await expect(history.getByRole('button', { name: copy.more })).toHaveCount(0)

  // The filter from the keyboard: arrows move through the radios, each reading its first page again.
  const all = history.getByRole('radio', { name: copy.filter.options.ALL })
  await all.focus()
  await page.keyboard.press('ArrowRight')
  await expect(history.getByRole('radio', { name: copy.filter.options.IN })).toBeChecked()
  await expect(list.getByRole('listitem')).toHaveCount(18, { timeout: 15_000 })
  await expect(summary).toContainText('180.09 USD')
  await page.keyboard.press('ArrowRight')
  await expect(history.getByRole('radio', { name: copy.filter.options.OUT })).toBeChecked()
  await expect(list.getByRole('listitem')).toHaveCount(5, { timeout: 15_000 })
  await expect(summary).toContainText('-12.50 USD')

  // A row's lineage from its own toggle, named by its time and type.
  const withdrawal = list.getByRole('listitem').nth(0)
  const detail = withdrawal.getByRole('button', { name: new RegExp(copy.filter.options.OUT) })
  await detail.focus()
  await page.keyboard.press('Enter')
  await expect(detail).toHaveAttribute('aria-expanded', 'true')
  await expect(withdrawal).toContainText('adjustment-04')
  await expect(withdrawal).toContainText(copy.evidenceTypes.DIRECT_NORMALIZATION)
  await expect(withdrawal.locator('code', { hasText: 'adjustment-04' })).toHaveText('adjustment-04')

  // One status region for the screen, which says what the history read did; the region has none.
  await expect(page.getByRole('status')).toHaveCount(1)
  await expect(page.locator('[aria-live]')).toHaveCount(1)
  await expect(page.getByRole('status')).toContainText(copy.status.ready(5, 5))
  const historyText = await history.innerText()
  expect(historyText).not.toMatch(
    /축하|손실|추천합니다|투자성과|계좌 수익률|스트릭|배지|NaN|undefined/,
  )
  expect(page.url()).not.toMatch(/[?&](planId|calculationId|cursor|direction)=/)
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
  const axe = await new AxeBuilder({ page })
    .include('#adjustment-history')
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  expect(axe.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  for (const width of [320, 640]) {
    await page.setViewportSize({ width, height: 800 })
    const widths = await page.evaluate(() => {
      const open = document.getElementById('adjustment-history')
      return {
        page: document.documentElement.scrollWidth,
        viewport: window.innerWidth,
        region: open === null ? Number.POSITIVE_INFINITY : open.scrollWidth - open.clientWidth,
      }
    })
    expect(widths.page, `${String(width)} CSS px: no horizontal page scroll`).toBeLessThanOrEqual(
      widths.viewport,
    )
    expect(widths.region, `${String(width)} CSS px: the region does not overflow`).toBe(0)
  }
  // The row controls are real 44px targets.
  const box = await detail.boundingBox()
  expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)

  await page.setViewportSize({ width: 320, height: 800 })
  const close = history.getByRole('button', { name: copy.close })
  await close.focus()
  await page.keyboard.press('Enter')
  await expect(toggle).toBeFocused()
  await expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await expect(panel.getByRole('region', { name: copy.heading })).toHaveCount(0)
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
    // The chain now runs through the first publication, its result and one manual sync.
    test.setTimeout(150_000)
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
    await expectEvidence(page, 'OFFICIAL')
    await expectNoHorizontalScroll(page)
    await page.setViewportSize({ width: 320, height: 800 })
    await expectKeyboardOrder(page)
    await expectManualSync(page)
    await page.goto(planConditionContent.path)
    await expect(page.getByRole('heading', { name: activePlanContent.heading })).toBeVisible()
    await expect(page.getByRole('link', { name: activePlanContent.dashboardLink })).toBeVisible()
    await expect(page.getByLabel(planConditionContent.endAt)).toHaveCount(0)
  })

  test('조정 이력: 반영된 입출금 스물셋을 키보드로 펼쳐 페이지·필터·상세를 같은 화면에서 확인한다', async ({
    page,
  }) => {
    test.setTimeout(120_000)
    await connectAndSavePlan(page, 'adjustments')
    await collectAndClassify(page, /입금 사건: 18 · 출금 사건: 5 · 제외 사건: 0 · 검토 필요: 0/)
    const conversion = await startConversionByKeyboard(page)
    await expect(conversion.getByRole('status')).toContainText(
      planConditionContent.conversion.converted,
      { timeout: 15_000 },
    )
    await page.setViewportSize({ width: 320, height: 800 })
    await publishAndOpenResult(page)
    await expectFirstResult(page, 'OFFICIAL')
    await expectAdjustments(page)
    await expectNoHorizontalScroll(page)
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
      await expectEvidence(estimated, 'ESTIMATED')
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
