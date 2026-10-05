import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import { adjustmentHistoryContent } from '../src/content/adjustmentHistory'
import { authContent } from '../src/content/auth'
import { calculationEvidenceContent } from '../src/content/calculationEvidence'
import { entryContent } from '../src/content/entry'
import { nextPlanContent } from '../src/content/nextPlan'
import { planConditionContent } from '../src/content/planConditions'
import { planResultContent } from '../src/content/planResult'
import { planSyncContent } from '../src/content/planSync'
import { planVerificationContent } from '../src/content/planVerification'
import {
  collectAndClassify,
  connectAndSavePlan,
  publishAndOpenResult,
  startConversionByKeyboard,
} from './support/firstPlan'

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

/** What `GET /api/dashboard` says about the shown bundle, asked from the page itself (same origin, no cache). */
interface Probe {
  readonly pointerVersion: number
  readonly calculationId: string
  readonly flowCount: number
  readonly currentValue: string
  readonly basisAt: string
  readonly availability: string
  readonly latestStatus: string | null
}

async function probe(page: Page): Promise<Probe> {
  return page.evaluate(async () => {
    const answer = await fetch('/api/dashboard', { credentials: 'same-origin', cache: 'no-store' })
    const body = (await answer.json()) as {
      result: {
        pointerVersion: number
        calculationId: string
        flowCount: number
        basisAt: string
        metrics: { metric: string; value?: string }[]
      }
      sync: { availability: string; latest: { status: string } | null }
    }
    return {
      pointerVersion: body.result.pointerVersion,
      calculationId: body.result.calculationId,
      flowCount: body.result.flowCount,
      currentValue: body.result.metrics[0]?.value ?? '',
      basisAt: body.result.basisAt,
      availability: body.sync.availability,
      latestStatus: body.sync.latest?.status ?? null,
    }
  })
}

/** The basis time as this page formats it (Asia/Seoul), read from the calculation-nature axis that vouches for it. */
async function shownBasis(page: Page, basisAt: string): Promise<string> {
  const trust = page
    .getByRole('region', { name: planResultContent.heading })
    .getByRole('region', { name: planResultContent.trustHeading })
  return trust.getByRole('group').nth(2).locator(`time[datetime="${basisAt}"]`).first().innerText()
}

/**
 * Starts recording the screen's status region: each distinct text it shows, and whether it was ever busy.
 * Kept on the window object of this page only (never browser storage) and read back by `statusRecord`.
 */
async function recordStatus(page: Page): Promise<void> {
  await page.evaluate(() => {
    const region = document.querySelector('[role="status"]')
    const record = { texts: [] as string[], busy: false }
    ;(window as unknown as { __statusRecord: typeof record }).__statusRecord = record
    if (region === null) return
    new MutationObserver(() => {
      const text = region.textContent
      if (record.texts.at(-1) !== text) record.texts.push(text)
      if (region.getAttribute('aria-busy') === 'true') record.busy = true
    }).observe(region, { attributes: true, characterData: true, childList: true, subtree: true })
  })
}

async function statusRecord(page: Page): Promise<{ texts: string[]; busy: boolean }> {
  return page.evaluate(
    () =>
      (window as unknown as { __statusRecord: { texts: string[]; busy: boolean } }).__statusRecord,
  )
}

test.describe('활성 계획의 증분 동기화', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 실제 동일 출처 스텁에서만 성공 왕복을 검증한다.
  test.skip(injectedOrigin === '', 'PLAYWRIGHT_BASE_URL의 동일 출처 스텁이 필요하다.')

  test('수동 동기화: 1초 안에 동기화 중을 보이고, 끝나면 v2 묶음으로 한 번에 바뀌며 초점은 제목으로 간다', async ({
    page,
  }) => {
    // Well under the suite's global limit: the serial injected-origin run must stay under 180 seconds.
    test.setTimeout(100_000)
    /*
     * The stub's `incremental` scenario: from the connection check on, one USDT deposit of 10 every 10 seconds
     * (48 in all, an 8-minute horizon), and a wallet of 1000 + 10 per deposit already made. The first
     * publication therefore holds the deposits up to its tc, and a sync a little later finds more.
     */
    await connectAndSavePlan(page, 'incremental', /1,[0-4]\d0\.00 USD/)
    await collectAndClassify(page, /입금 사건: \d+ · 출금 사건: 0 · 제외 사건: 0 · 검토 필요: 0/)
    const conversion = await startConversionByKeyboard(page)
    await expect(conversion.getByRole('status')).toContainText(
      planConditionContent.conversion.converted,
      { timeout: 15_000 },
    )
    await page.setViewportSize({ width: 1024, height: 900 })
    await publishAndOpenResult(page)

    const panel = page.getByRole('region', { name: planResultContent.heading })
    await expect(panel.getByRole('region', { name: planResultContent.currentHeading })).toBeVisible(
      {
        timeout: 15_000,
      },
    )
    const first = await probe(page)
    expect(first.pointerVersion).toBe(1)
    // The entry sync right after the first publication is not due yet: nothing was admitted.
    expect(first.latestStatus).toBeNull()
    expect(first.availability).toBe('AVAILABLE')
    const cards = panel.getByRole('article')
    const firstCurrent = (await cards.nth(0).innerText()).trim()
    // The current value itself (the card's first amount); the card's text also holds its times.
    const firstValue = /[\d,]+\.\d{2} USD/.exec(firstCurrent)?.[0] ?? firstCurrent
    const firstBasis = await shownBasis(page, first.basisAt)
    await expect(page.getByRole('status')).toHaveText(planResultContent.status.ready(firstBasis))

    // At least one deposit happens after the first tc (the stub adds one every 10 seconds).
    const firstTc = Date.parse(first.basisAt)
    await expect
      .poll(() => Date.now(), { timeout: 25_000, intervals: [500] })
      .toBeGreaterThanOrEqual(firstTc + 11_000)

    await page.emulateMedia({ reducedMotion: 'reduce' })
    await recordStatus(page)
    const trust = panel.getByRole('region', { name: planResultContent.trustHeading })
    const control = trust.getByRole('button', { name: planSyncContent.action })
    await expect(control).toHaveAttribute('aria-disabled', 'false')
    await control.focus()
    await page.keyboard.press('Enter')
    // Within a second, before any answer is needed: 동기화 중, the button blocked and still focused.
    await expect(page.getByText(planSyncContent.running, { exact: true })).toBeVisible({
      timeout: 1_000,
    })
    await expect(control).toHaveAttribute('aria-disabled', 'true')
    await expect(control).toBeFocused()
    // The values stay while it runs; the marker is still under reduced motion. (The screen follows the job
    // with a read every 2 seconds, so 동기화 중 and its marker stay at least until the first one.)
    expect((await cards.nth(0).innerText()).trim()).toBe(firstCurrent)
    const animation = await page.evaluate(() => {
      const marker = document.querySelector('#plan-sync [aria-hidden="true"]')
      return marker === null ? 'missing' : getComputedStyle(marker).animationName
    })
    expect(animation).toBe('none')
    // A second press while it runs asks nothing more.
    await page.keyboard.press('Enter')

    // It ends with a new bundle: v2, swapped in at once, and focus on the dashboard heading.
    await expect(cards.nth(0)).not.toContainText(firstValue, { timeout: 30_000 })
    const second = await probe(page)
    expect(second.pointerVersion).toBe(2)
    expect(second.calculationId).not.toBe(first.calculationId)
    expect(second.latestStatus).toBe('SUCCEEDED')
    expect(second.flowCount).toBeGreaterThan(first.flowCount)
    expect(Number(second.currentValue)).toBeGreaterThan(Number(first.currentValue))
    await expect(
      page.getByRole('heading', { level: 2, name: planResultContent.heading }),
    ).toBeFocused()
    const secondBasis = await shownBasis(page, second.basisAt)
    await expect(page.getByRole('status')).toHaveText(planSyncContent.status.synced(secondBasis))
    // One sentence when it started, one when it ended: never "reading again", never busy while it polled.
    const record = await statusRecord(page)
    expect(record.texts).toEqual([
      planSyncContent.status.syncing,
      planSyncContent.status.synced(secondBasis),
    ])
    expect(record.texts).not.toContain(planResultContent.status.reloading)
    expect(record.busy).toBe(false)
    await expect(page.getByRole('status')).toHaveCount(1)
    await expect(page.locator('[aria-live]')).toHaveCount(1)
    // The plan's terms did not change.
    const conditions = panel.getByRole('region', { name: planResultContent.conditionsHeading })
    await expect(conditions.getByText('10%')).toBeVisible()
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)

    // The evidence of v2: v1 with its applied end, then the current v2; the collected range is (t0, tc2].
    const evidenceToggle = panel.getByRole('button', { name: calculationEvidenceContent.open })
    await evidenceToggle.focus()
    await page.keyboard.press('Enter')
    const evidence = panel.getByRole('region', { name: calculationEvidenceContent.heading })
    const versions = evidence.getByRole('group', {
      name: calculationEvidenceContent.sections.versions,
    })
    await expect(versions).toBeVisible({ timeout: 15_000 })
    const official = versions.getByRole('list').first().getByRole('listitem')
    await expect(official).toHaveCount(2)
    await expect(official.nth(0)).toContainText(calculationEvidenceContent.versions.earlier)
    await expect(official.nth(1)).toContainText(calculationEvidenceContent.versions.current)
    await expect(
      evidence.getByRole('group', { name: calculationEvidenceContent.sections.inputs }),
    ).toContainText(calculationEvidenceContent.inputs.windowStart)
    await evidence.getByRole('button', { name: calculationEvidenceContent.close }).click()

    // The adjustment history of v2: every applied flow of (t0, tc2], numbered from 1.
    const historyToggle = panel.getByRole('button', { name: adjustmentHistoryContent.open })
    await historyToggle.focus()
    await page.keyboard.press('Enter')
    const history = panel.getByRole('region', { name: adjustmentHistoryContent.heading })
    const summary = history.getByRole('group', { name: adjustmentHistoryContent.summary.heading })
    await expect(summary).toContainText(
      adjustmentHistoryContent.summary.countValue(second.flowCount),
      {
        timeout: 15_000,
      },
    )
    const rows = history
      .getByRole('list', { name: adjustmentHistoryContent.list })
      .getByRole('listitem')
    await expect(rows.nth(0).getByRole('heading', { level: 5 })).toContainText(
      adjustmentHistoryContent.row.title(1),
    )
    await history.getByRole('button', { name: adjustmentHistoryContent.close }).click()

    // Accessibility of the control after the sync, and no sideways scrolling at 1024 and 320 CSS px.
    const axe = await new AxeBuilder({ page })
      .include('#plan-sync')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(axe.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
    for (const width of [1024, 320]) {
      await page.setViewportSize({ width, height: 800 })
      const widths = await page.evaluate(() => {
        const sync = document.getElementById('plan-sync')
        return {
          page: document.documentElement.scrollWidth,
          viewport: window.innerWidth,
          control: sync === null ? Number.POSITIVE_INFINITY : sync.scrollWidth - sync.clientWidth,
        }
      })
      expect(widths.page, `${String(width)} CSS px: no horizontal page scroll`).toBeLessThanOrEqual(
        widths.viewport,
      )
      expect(widths.control, `${String(width)} CSS px: the control does not overflow`).toBe(0)
    }
    const box = await control.boundingBox()
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)

    /*
     * The keyboard order from a fresh load at 320 CSS px: the header, then the sync button right after the
     * freshness axis, the precision verification button right after it (Story 2.12), the evidence and history
     * buttons, then the reload. The fresh load's entry sync is not due (a manual one was admitted moments
     * ago), so nothing changes on screen.
     */
    await page.reload()
    await expect(panel.getByRole('region', { name: planResultContent.currentHeading })).toBeVisible(
      {
        timeout: 15_000,
      },
    )
    const reached: string[] = []
    for (let step = 0; step < 9; step += 1) {
      await page.keyboard.press('Tab')
      reached.push(await page.evaluate(() => document.activeElement?.textContent.trim() ?? ''))
    }
    expect(reached).toEqual([
      entryContent.skipLink,
      authContent.settings.link,
      authContent.signOut.label,
      nextPlanContent.historyOpen,
      planSyncContent.action,
      planVerificationContent.action,
      calculationEvidenceContent.open,
      adjustmentHistoryContent.open,
      planResultContent.reload,
    ])
    const reloaded = await probe(page)
    expect(reloaded.pointerVersion).toBe(2)
    await expect(page.getByRole('status')).toHaveText(
      planResultContent.status.ready(await shownBasis(page, reloaded.basisAt)),
    )
  })
})
