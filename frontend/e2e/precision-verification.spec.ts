import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import { adjustmentHistoryContent } from '../src/content/adjustmentHistory'
import { authContent } from '../src/content/auth'
import { calculationEvidenceContent } from '../src/content/calculationEvidence'
import { entryContent } from '../src/content/entry'
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
  readonly basisAt: string
  readonly verification: string
  readonly change: string
  readonly lateChangeCount: number
  readonly lateAddedCount: number
  readonly valueChangedCount: number
  readonly removedCount: number
  readonly firstAffectedAt: string | null
}

/** The `lateevent` scenario's one late deposit: found late, no value changed, nothing removed (Story 2.12 R8). */
const ONE_LATE = { lateAddedCount: 1, valueChangedCount: 0, removedCount: 0 }

async function probe(page: Page): Promise<Probe> {
  return page.evaluate(async () => {
    const answer = await fetch('/api/dashboard', { credentials: 'same-origin', cache: 'no-store' })
    const body = (await answer.json()) as {
      result: { pointerVersion: number; calculationId: string; flowCount: number; basisAt: string }
      verification: { availability: string }
      change: {
        kind: string
        lateChangeCount: number
        lateAddedCount: number
        valueChangedCount: number
        removedCount: number
        firstAffectedAt: string | null
      }
    }
    return {
      pointerVersion: body.result.pointerVersion,
      calculationId: body.result.calculationId,
      flowCount: body.result.flowCount,
      basisAt: body.result.basisAt,
      verification: body.verification.availability,
      change: body.change.kind,
      lateChangeCount: body.change.lateChangeCount,
      lateAddedCount: body.change.lateAddedCount,
      valueChangedCount: body.change.valueChangedCount,
      removedCount: body.change.removedCount,
      firstAffectedAt: body.change.firstAffectedAt,
    }
  })
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

test.describe('필요 구간 정밀 검증과 늦은 사건의 새 버전', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 실제 동일 출처 스텁에서만 성공 왕복을 검증한다.
  test.skip(injectedOrigin === '', 'PLAYWRIGHT_BASE_URL의 동일 출처 스텁이 필요하다.')

  test('정밀 검증: 1초 안에 진행을 보이고, 늦게 확인된 입금을 담은 v2로 한 번에 바뀌며 표지·변경 근거·조정 이력이 같은 사건과 영향 시작을 가리킨다', async ({
    page,
  }) => {
    // Well under the suite's global limit: the serial injected-origin run must stay under its budget.
    test.setTimeout(100_000)
    /*
     * The stub's `lateevent` scenario: the UID's first TRANSFER_IN read (the initial collection) fixes an event
     * time inside its own window (a second after its start, or its midpoint when the collection runs within two
     * seconds of the plan's start, as it does here) and sees nothing; from the second read on, a deposit
     * `late-event-01` of +25 USDT at that time appears. The first publication therefore holds no flow, and only a
     * verification that reads that range again finds it — as a late event. The wallet stays at 1,234.56 USDT.
     */
    await connectAndSavePlan(page, 'lateevent')
    await collectAndClassify(page, /입금 사건: 0 · 출금 사건: 0 · 제외 사건: 0 · 검토 필요: 0/)
    const conversion = await startConversionByKeyboard(page)
    await expect(conversion.getByRole('status')).toContainText(
      planConditionContent.conversion.converted,
      { timeout: 15_000 },
    )
    await page.setViewportSize({ width: 1024, height: 900 })
    await publishAndOpenResult(page)

    const panel = page.getByRole('region', { name: planResultContent.heading })
    await expect(panel.getByRole('region', { name: planResultContent.currentHeading })).toBeVisible(
      { timeout: 15_000 },
    )
    const first = await probe(page)
    expect(first.pointerVersion).toBe(1)
    expect(first.flowCount).toBe(0)
    expect(first.change).toBe('NONE')
    expect(first.verification).toBe('AVAILABLE')
    // No marker on the first version.
    await expect(panel.getByText(planVerificationContent.change.correctedHelp)).toHaveCount(0)

    const control = panel.getByRole('button', { name: planVerificationContent.action })
    await expect(control).toHaveAttribute('aria-disabled', 'false')
    // Its purpose, cost and expected range are beside it.
    const verification = page.locator('#plan-verification')
    await expect(verification).toContainText(planVerificationContent.purpose)
    await expect(verification).toContainText(planVerificationContent.cost)
    await expect(verification).toContainText(planVerificationContent.preview.rangeStart)

    const cards = panel.getByRole('article')
    const planAmountBefore = (await cards.nth(1).innerText()).trim()
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await recordStatus(page)
    await control.focus()
    await page.keyboard.press('Enter')
    // Within a second, before any answer is needed: 정밀 검증 중, the button blocked and still focused.
    await expect(page.getByText(planVerificationContent.running, { exact: true })).toBeVisible({
      timeout: 1_000,
    })
    await expect(control).toHaveAttribute('aria-disabled', 'true')
    await expect(control).toBeFocused()
    // The values stay while it runs; the marker is still under reduced motion.
    expect((await cards.nth(1).innerText()).trim()).toBe(planAmountBefore)
    const animation = await page.evaluate(() => {
      const marker = document.querySelector('#plan-verification [aria-hidden="true"]')
      return marker === null ? 'missing' : getComputedStyle(marker).animationName
    })
    expect(animation).toBe('none')
    // The sync button waits too: one job at a time.
    await expect(panel.getByRole('button', { name: planSyncContent.action })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    // A second press while it runs asks nothing more.
    await page.keyboard.press('Enter')

    // It ends with v2, swapped in at once, and focus on the dashboard heading. (The end is read from the status
    // sentence: `toHaveText` compares textContent, which never equals the innerText captured above, so a negated
    // text match on the card would pass at once.)
    await expect(page.getByRole('status')).toContainText(
      planVerificationContent.change.correctedSentence(ONE_LATE),
      { timeout: 45_000 },
    )
    expect((await cards.nth(1).innerText()).trim()).not.toBe(planAmountBefore)
    const second = await probe(page)
    expect(second.pointerVersion).toBe(2)
    expect(second.calculationId).not.toBe(first.calculationId)
    expect(second.flowCount).toBe(1)
    expect(second.change).toBe('CORRECTED')
    expect(second.lateChangeCount).toBe(1)
    expect({
      lateAddedCount: second.lateAddedCount,
      valueChangedCount: second.valueChangedCount,
      removedCount: second.removedCount,
    }).toEqual(ONE_LATE)
    expect(second.firstAffectedAt).not.toBeNull()
    const impact = second.firstAffectedAt ?? ''
    await expect(
      page.getByRole('heading', { level: 2, name: planResultContent.heading }),
    ).toBeFocused()
    const record = await statusRecord(page)
    // One sentence when it started, one when it ended: never "reading again", never busy while it polled.
    expect(record.texts).toHaveLength(2)
    expect(record.texts[0]).toBe(planVerificationContent.status.running)
    expect(record.texts[1]).toContain(planVerificationContent.change.correctedSentence(ONE_LATE))
    expect(record.busy).toBe(false)
    await expect(page.getByRole('status')).toHaveCount(1)
    await expect(page.locator('[aria-live]')).toHaveCount(1)

    // The result marker: one late deposit and where its impact starts, as a group named by that line.
    const marker = panel.locator('p', {
      hasText: planVerificationContent.change.corrected(ONE_LATE),
    })
    await expect(marker).toContainText(planVerificationContent.change.impactStart)
    await expect(marker.locator(`time[datetime="${impact}"]`)).toHaveCount(1)
    await expect(
      panel.getByRole('group', {
        name: new RegExp(`^${planVerificationContent.change.corrected(ONE_LATE)}`),
      }),
    ).toHaveAttribute('id', 'plan-version-change')

    // The evidence of v2: the change part first, naming the late deposit and the same impact start.
    await panel.getByRole('button', { name: calculationEvidenceContent.open }).focus()
    await page.keyboard.press('Enter')
    const evidence = panel.getByRole('region', { name: calculationEvidenceContent.heading })
    const change = evidence.getByRole('group', { name: planVerificationContent.evidence.title })
    await expect(change).toBeVisible({ timeout: 15_000 })
    const parts = await evidence.getByRole('heading', { level: 5 }).allInnerTexts()
    expect(parts[0]).toBe(planVerificationContent.evidence.title)
    await expect(change).toContainText('late-event-01')
    await expect(change).toContainText(planVerificationContent.evidence.eventKinds.ADDED ?? '')
    await expect(change).toContainText(planVerificationContent.evidence.eventNotes.lateAdded)
    // The same three counts as the marker: one found late, nothing else.
    await expect(change).toContainText(planVerificationContent.evidence.countsValue(ONE_LATE))
    // The type in words (the history's own name), never Bybit's raw code.
    await expect(change).toContainText(adjustmentHistoryContent.filter.options.IN)
    await expect(change).not.toContainText('TRANSFER_IN')
    await expect(change).toContainText(planVerificationContent.evidence.previous)
    await expect(change.locator(`time[datetime="${impact}"]`).first()).toBeVisible()
    await evidence.getByRole('button', { name: calculationEvidenceContent.close }).click()

    // The adjustment history of v2: the one flow, marked as found late in this version, at the same time.
    await panel.getByRole('button', { name: adjustmentHistoryContent.open }).focus()
    await page.keyboard.press('Enter')
    const history = panel.getByRole('region', { name: adjustmentHistoryContent.heading })
    const rows = history
      .getByRole('list', { name: adjustmentHistoryContent.list })
      .getByRole('listitem')
    await expect(rows).toHaveCount(1, { timeout: 15_000 })
    await expect(rows.nth(0)).toContainText(planVerificationContent.history.lateAdded)
    await expect(rows.nth(0).locator(`time[datetime="${impact}"]`).first()).toBeVisible()
    await rows
      .nth(0)
      .getByRole('button', { name: /상세 보기$/ })
      .click()
    await expect(rows.nth(0)).toContainText('late-event-01')
    await history.getByRole('button', { name: adjustmentHistoryContent.close }).click()
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)

    // Accessibility of the control and the result marker after the verification, and no sideways scrolling at
    // 1024 and 320 CSS px.
    const axe = await new AxeBuilder({ page })
      .include('#plan-verification')
      .include('#plan-version-change')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
      .analyze()
    expect(axe.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
    for (const width of [1024, 320]) {
      await page.setViewportSize({ width, height: 800 })
      const widths = await page.evaluate(() => {
        const overflow = (id: string): number => {
          const element = document.getElementById(id)
          return element === null
            ? Number.POSITIVE_INFINITY
            : element.scrollWidth - element.clientWidth
        }
        return {
          page: document.documentElement.scrollWidth,
          viewport: window.innerWidth,
          verification: overflow('plan-verification'),
          sync: overflow('plan-sync'),
          marker: overflow('plan-version-change'),
        }
      })
      expect(widths.page, `${String(width)} CSS px: no horizontal page scroll`).toBeLessThanOrEqual(
        widths.viewport,
      )
      expect(widths.verification, `${String(width)} CSS px: the control does not overflow`).toBe(0)
      expect(widths.sync, `${String(width)} CSS px: the sync control does not overflow`).toBe(0)
      expect(widths.marker, `${String(width)} CSS px: the result marker does not overflow`).toBe(0)
    }
    const box = await control.boundingBox()
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)

    /*
     * The keyboard order from a fresh load at 320 CSS px: the header, then the sync button right after the
     * freshness axis, the verification button right after it, the evidence and history buttons, then the
     * reload. The fresh load's entry sync is not due (a verification was admitted moments ago).
     */
    await page.reload()
    await expect(panel.getByRole('region', { name: planResultContent.currentHeading })).toBeVisible(
      { timeout: 15_000 },
    )
    const reached: string[] = []
    for (let step = 0; step < 8; step += 1) {
      await page.keyboard.press('Tab')
      reached.push(await page.evaluate(() => document.activeElement?.textContent.trim() ?? ''))
    }
    expect(reached).toEqual([
      entryContent.skipLink,
      authContent.settings.link,
      authContent.signOut.label,
      planSyncContent.action,
      planVerificationContent.action,
      calculationEvidenceContent.open,
      adjustmentHistoryContent.open,
      planResultContent.reload,
    ])
    const reloaded = await probe(page)
    expect(reloaded.pointerVersion).toBe(2)
    // The marker belongs to the version: it is still there after the reload.
    await expect(marker).toContainText(planVerificationContent.change.impactStart)
  })
})
