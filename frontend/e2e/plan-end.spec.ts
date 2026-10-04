import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import { adjustmentHistoryContent } from '../src/content/adjustmentHistory'
import { authContent } from '../src/content/auth'
import { calculationEvidenceContent } from '../src/content/calculationEvidence'
import { entryContent } from '../src/content/entry'
import { nextPlanContent } from '../src/content/nextPlan'
import { planConditionContent } from '../src/content/planConditions'
import { planEndContent } from '../src/content/planEnd'
import { planResultContent } from '../src/content/planResult'
import { planSyncContent } from '../src/content/planSync'
import { planVerificationContent } from '../src/content/planVerification'
import {
  collectAndClassify,
  connectAndSavePlan,
  consent,
  publishAndOpenResult,
  startConversionByKeyboard,
} from './support/firstPlan'

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

/** What `GET /api/dashboard` says, asked from the page itself (same origin, no cache). */
interface Probe {
  readonly state: string
  readonly planId: string
  readonly pointerVersion: number
  readonly calculationId: string
  readonly measuredAt: string
  readonly planStatus: string
  readonly planEnd: string | null
  readonly end: {
    readonly reason: string
    readonly endedAt: string
    readonly lastNormalMeasuredAt: string
    readonly completion: string
    readonly uncollectedFrom: string | null
  } | null
}

async function probe(page: Page): Promise<Probe> {
  return page.evaluate(async () => {
    const answer = await fetch('/api/dashboard', { credentials: 'same-origin', cache: 'no-store' })
    const body = (await answer.json()) as {
      state: string
      result: {
        planId: string
        pointerVersion: number
        calculationId: string
        measuredAt: string
        planStatus: string
      }
      planEnd?: { availability: string }
      end?: Probe['end']
    }
    return {
      state: body.state,
      planId: body.result.planId,
      pointerVersion: body.result.pointerVersion,
      calculationId: body.result.calculationId,
      measuredAt: body.result.measuredAt,
      planStatus: body.result.planStatus,
      planEnd: body.planEnd?.availability ?? null,
      end: body.end ?? null,
    }
  })
}

/** What `startRecording` keeps on the page's window object, with the observers that fill it. */
interface Recording {
  texts: string[]
  blocked: boolean
  observers: MutationObserver[]
}

/**
 * Starts recording the screen's status region (each distinct text) and whether the dialog's confirm button was
 * ever blocked. Kept on the window object of this page only (never browser storage); `record` reads it back and
 * disconnects the observers.
 */
async function startRecording(page: Page): Promise<void> {
  await page.evaluate(() => {
    const region = document.querySelector('[role="status"]')
    const recording: Recording = { texts: [], blocked: false, observers: [] }
    ;(window as unknown as { __planEndRecord: Recording }).__planEndRecord = recording
    if (region !== null) {
      const status = new MutationObserver(() => {
        const text = region.textContent
        if (recording.texts.at(-1) !== text) recording.texts.push(text)
      })
      status.observe(region, { characterData: true, childList: true, subtree: true })
      recording.observers.push(status)
    }
    const dialog = new MutationObserver(() => {
      const confirm = document.querySelector('[role="dialog"] button[aria-describedby]')
      if (confirm?.getAttribute('aria-disabled') === 'true') recording.blocked = true
    })
    dialog.observe(document.body, { attributes: true, childList: true, subtree: true })
    recording.observers.push(dialog)
  })
}

/** Stops recording and reads back what was recorded. */
async function record(page: Page): Promise<{ texts: string[]; blocked: boolean }> {
  return page.evaluate(() => {
    const recording = (window as unknown as { __planEndRecord: Recording }).__planEndRecord
    for (const observer of recording.observers) observer.disconnect()
    return { texts: recording.texts, blocked: recording.blocked }
  })
}

/** No sideways scrolling of the page, or of the named element when given. */
async function expectNoOverflow(page: Page, id: string | null): Promise<void> {
  for (const width of [1024, 320]) {
    await page.setViewportSize({ width, height: 800 })
    const widths = await page.evaluate((target) => {
      const element = target === null ? null : document.getElementById(target)
      return {
        page: document.documentElement.scrollWidth,
        viewport: window.innerWidth,
        element: element === null ? 0 : element.scrollWidth - element.clientWidth,
        missing: target !== null && element === null,
      }
    }, id)
    expect(widths.missing, `${String(width)} CSS px: #${String(id)} is on the page`).toBe(false)
    expect(widths.page, `${String(width)} CSS px: no horizontal page scroll`).toBeLessThanOrEqual(
      widths.viewport,
    )
    expect(widths.element, `${String(width)} CSS px: #${String(id)} does not overflow`).toBe(0)
  }
}

test.describe('활성 계획의 명시적 중도 종료와 읽기 전용 기록', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 실제 동일 출처 스텁에서만 성공 왕복을 검증한다.
  test.skip(injectedOrigin === '', 'PLAYWRIGHT_BASE_URL의 동일 출처 스텁이 필요하다.')

  test('계획 중도 종료: 대시보드에서 본인 확인 → 확인 대화상자 → 확정 → 같은 패널의 중도 종료 기록', async ({
    page,
  }) => {
    // Well under the suite's global limit: the serial injected-origin run must stay under its budget.
    test.setTimeout(120_000)
    const { subject } = await connectAndSavePlan(page, 'uta2')
    await collectAndClassify(page, /입금 사건: 0 · 출금 사건: 0 · 제외 사건: 0 · 검토 필요: 0/)
    const conversion = await startConversionByKeyboard(page)
    await expect(conversion.getByRole('status')).toContainText(
      planConditionContent.conversion.converted,
      { timeout: 15_000 },
    )
    await page.setViewportSize({ width: 1024, height: 900 })
    await publishAndOpenResult(page)

    /*
     * A new session without a reauthentication: sign out and sign in again as the same person. The plan-end
     * prepare then asks for a recent reauthentication, which this screen starts from the end area itself.
     */
    await page.getByRole('button', { name: authContent.signOut.label }).click()
    await page.getByRole('link', { name: authContent.signIn.labels.google }).click()
    await consent(page, subject)
    const panel = page.getByRole('region', { name: planResultContent.heading })
    await expect(panel.getByRole('region', { name: planResultContent.currentHeading })).toBeVisible(
      { timeout: 15_000 },
    )
    const before = await probe(page)
    expect(before.state).toBe('READY')
    expect(before.planEnd).toBe('AVAILABLE')

    // The 계획 종료 area closes the panel, after the plan terms; its button is a real 44px target.
    const area = panel.getByRole('region', { name: planEndContent.area.heading })
    await expect(area).toContainText(planEndContent.area.text)
    const end = area.getByRole('button', { name: planEndContent.area.action, exact: true })
    await expect(end).toHaveAttribute('aria-disabled', 'false', { timeout: 15_000 })
    const box = await end.boundingBox()
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)

    // No recent reauthentication: no dialog, the guidance and the three provider buttons in the end area.
    await end.focus()
    await page.keyboard.press('Enter')
    await expect(area.getByText(planEndContent.reauth.text)).toBeVisible()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByRole('status')).toContainText(planEndContent.status.reauthRequired)
    await area.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
    await consent(page, subject)
    // Back on the landing screen: nothing continued by itself — the user presses again.
    await expect(panel.getByRole('region', { name: planResultContent.currentHeading })).toBeVisible(
      { timeout: 15_000 },
    )
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(end).toHaveAttribute('aria-disabled', 'false', { timeout: 15_000 })
    await end.focus()
    await page.keyboard.press('Enter')

    // The dialog: its name, cancel first and focused, focus trapped, both targets at least 44px.
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toHaveAccessibleName(planEndContent.dialog.titles.USER_REQUEST)
    await expect(dialog).toContainText(planEndContent.dialog.irreversible)
    await expect(dialog).toContainText(planEndContent.dialog.endTime)
    await expect(dialog).not.toContainText(planEndContent.dialog.longGap)
    const cancel = dialog.getByRole('button', { name: planEndContent.dialog.cancel, exact: true })
    const confirm = dialog.getByRole('button', {
      name: planEndContent.dialog.confirm,
      exact: true,
    })
    await expect(cancel).toBeFocused()
    for (const target of [cancel, confirm]) {
      const size = await target.boundingBox()
      expect(size?.width ?? 0).toBeGreaterThanOrEqual(44)
      expect(size?.height ?? 0).toBeGreaterThanOrEqual(44)
    }
    await page.keyboard.press('Tab')
    await expect(confirm).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(cancel).toBeFocused()
    // The background is inert while the dialog is open; the dialog is its sibling.
    await expect(page.locator('[inert]')).toHaveCount(1)
    await expect(page.locator('[inert] [role="dialog"]')).toHaveCount(0)
    const dialogAxe = await new AxeBuilder({ page })
      .include('[role="dialog"]')
      .withTags(WCAG_TAGS)
      .analyze()
    expect(dialogAxe.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual(
      [],
    )
    await expectNoOverflow(page, null)

    // `Esc` cancels: focus returns to the button, nothing changed, and the status says so.
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(end).toBeFocused()
    await expect(page.getByRole('status')).toContainText(planEndContent.status.cancelled)
    expect((await probe(page)).state).toBe('READY')

    // Again, and confirm from the keyboard.
    await page.setViewportSize({ width: 1024, height: 900 })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await end.focus()
    await page.keyboard.press('Enter')
    await expect(dialog).toBeVisible()
    await startRecording(page)
    const syncRequests: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/dashboard/sync')) syncRequests.push(request.url())
    })
    await confirm.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('dialog')).toHaveCount(0, { timeout: 15_000 })
    const recordRegion = panel.getByRole('region', { name: planEndContent.record.heading })
    await expect(recordRegion).toBeVisible({ timeout: 15_000 })
    // Focus goes to the panel heading, not back to the button that opened the dialog.
    await expect(
      page.getByRole('heading', { level: 2, name: planResultContent.heading }),
    ).toBeFocused()
    const recorded = await record(page)
    // The dialog was blocked while confirming.
    expect(recorded.blocked).toBe(true)
    // The end is said once: the record's sentence, never "reading again".
    // The record's sentence up to its end time, taken from the content itself.
    const [sentenceStart = ''] = planEndContent.status.ended('\u0000').split('\u0000')
    expect(sentenceStart).not.toBe('')
    const ended = recorded.texts.filter((text) => text.includes(sentenceStart))
    expect(ended).toHaveLength(1)
    expect(recorded.texts.some((text) => text.includes(planResultContent.status.reloading))).toBe(
      false,
    )
    await expect(page.getByRole('status')).toHaveCount(1)
    await expect(page.locator('[aria-live]')).toHaveCount(1)

    // The record: the server's end time, the last normal measurement unchanged, no completion comparison.
    const after = await probe(page)
    expect(after.state).toBe('ENDED')
    expect(after.planStatus).toBe('ENDED_EARLY')
    expect(after.planId).toBe(before.planId)
    expect(after.pointerVersion).toBe(before.pointerVersion)
    expect(after.calculationId).toBe(before.calculationId)
    expect(after.measuredAt).toBe(before.measuredAt)
    expect(after.end?.reason).toBe('USER_REQUEST')
    expect(after.end?.lastNormalMeasuredAt).toBe(before.measuredAt)
    expect(after.end?.completion).toBe('NOT_APPLICABLE')
    expect(after.end?.uncollectedFrom).toBeNull()
    const endedAt = after.end?.endedAt ?? ''
    expect(Date.parse(endedAt)).toBeGreaterThanOrEqual(Date.parse(before.measuredAt))
    await expect(recordRegion).toContainText(
      `${planEndContent.record.statusLabel}: ${planEndContent.record.marker}`,
    )
    await expect(recordRegion.locator(`time[datetime="${endedAt}"]`)).toHaveCount(1)
    await expect(recordRegion).toContainText(planEndContent.record.reasons.USER_REQUEST ?? '')
    // No sync, verification or end control on the record; the new-plan area explains only.
    for (const name of [
      planSyncContent.action,
      planVerificationContent.action,
      planEndContent.area.action,
    ]) {
      await expect(panel.getByRole('button', { name, exact: true })).toHaveCount(0)
    }
    await expect(panel.getByRole('region', { name: planEndContent.nextPlan.heading })).toBeVisible()

    // The record's evidence: the same version, applied until the end time.
    await panel.getByRole('button', { name: calculationEvidenceContent.open }).focus()
    await page.keyboard.press('Enter')
    const evidence = panel.getByRole('region', { name: calculationEvidenceContent.heading })
    const versions = evidence.getByRole('group', {
      name: calculationEvidenceContent.sections.versions,
    })
    await expect(versions).toBeVisible({ timeout: 15_000 })
    await expect(versions).toContainText(calculationEvidenceContent.versions.endedCurrent)
    await expect(versions).toContainText(before.calculationId)
    await evidence.getByRole('button', { name: calculationEvidenceContent.close }).click()
    // And its adjustment history, read-only.
    await panel.getByRole('button', { name: adjustmentHistoryContent.open }).focus()
    await page.keyboard.press('Enter')
    const history = panel.getByRole('region', { name: adjustmentHistoryContent.heading })
    await expect(history).toBeVisible({ timeout: 15_000 })
    await history.getByRole('button', { name: adjustmentHistoryContent.close }).click()

    // Accessibility of the record, and no sideways scrolling at 1024 and 320 CSS px.
    const recordAxe = await new AxeBuilder({ page })
      .include(`#${planEndContent.record.id}`)
      .withTags(WCAG_TAGS)
      .analyze()
    expect(recordAxe.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual(
      [],
    )
    await expectNoOverflow(page, planEndContent.record.id)

    /*
     * The keyboard order from a fresh load at 320 CSS px: the header, then the evidence and history buttons and
     * the reload — no sync, verification or end control. The fresh load sends no sync for the record.
     */
    await page.reload()
    await expect(recordRegion).toBeVisible({ timeout: 15_000 })
    const reached: string[] = []
    for (let step = 0; step < 8; step += 1) {
      await page.keyboard.press('Tab')
      reached.push(await page.evaluate(() => document.activeElement?.textContent.trim() ?? ''))
    }
    expect(reached).toEqual([
      entryContent.skipLink,
      authContent.settings.link,
      authContent.signOut.label,
      nextPlanContent.historyOpen,
      nextPlanContent.inspect,
      calculationEvidenceContent.open,
      adjustmentHistoryContent.open,
      planResultContent.reload,
    ])
    expect(syncRequests).toEqual([])
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)

    // The plan-condition screen does not take new conditions from the old basis (Story 2.15 opens the next plan).
    await page.goto(planConditionContent.path)
    await expect(page.getByText(planConditionContent.planEnded.label)).toBeVisible({
      timeout: 15_000,
    })
    await expect(page.getByLabel(planConditionContent.endAt)).toHaveCount(0)
  })
})
