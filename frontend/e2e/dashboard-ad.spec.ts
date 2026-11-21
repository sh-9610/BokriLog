import { expect, test, type Page } from '@playwright/test'

import { GPT_SCRIPT_SRC } from '../src/ads/gptAdapter'
import { CSRF_HEADER, DASHBOARD_PATH } from '../src/content/auth'
import { DASHBOARD_PATH as DASHBOARD_API } from '../src/content/planResult'
import {
  DASHBOARD_AD_CONFIG_PATH,
  DASHBOARD_SYNC_PATH,
  DASHBOARD_SYNC_RETURN_PATH,
  planSyncContent,
} from '../src/content/planSync'
import { dashboardFixture, syncFixture, syncJobFixture } from '../src/test/planResultFixture'

const SIGNED_IN = {
  authenticated: true,
  destination: 'dashboard',
  csrfToken: 'dashboard-test-csrf',
}
const NOT_DUE = { outcome: 'NOT_DUE', job: null, retryAfterSeconds: 240 }
const UNIT = '/1234567/bokrilog_test'
const JOB = syncJobFixture({ status: 'RUNNING', trigger: 'MANUAL', stage: 'COLLECTION' })

async function fixture(page: Page, adEnabled: boolean) {
  const manualHeaders: Record<string, string>[] = []
  const automatic: string[] = []
  let afterClickReads = 0
  let releaseAdmission: (() => void) | undefined
  const landingRead = new Promise<void>((resolve) => {
    releaseAdmission = resolve
  })
  await page.route('**/api/auth/session', (route) => route.fulfill({ json: SIGNED_IN }))
  await page.route(`**${DASHBOARD_AD_CONFIG_PATH}`, (route) =>
    adEnabled ? route.fulfill({ json: { adUnitPath: UNIT } }) : route.fulfill({ status: 204 }),
  )
  await page.route(`**${DASHBOARD_API}`, (route) => {
    if (manualHeaders.length > 0) {
      afterClickReads += 1
      releaseAdmission?.()
      if (afterClickReads === 1 && adEnabled) {
        return route.fulfill({ status: 503, json: { code: 'TEMPORARY_FAILURE' } })
      }
    }
    const sync =
      afterClickReads > 1
        ? syncFixture({ latest: JOB, availability: 'IN_PROGRESS' })
        : syncFixture()
    return route.fulfill({ json: dashboardFixture(undefined, undefined, sync) })
  })
  await page.route(`**${DASHBOARD_SYNC_PATH}`, async (route) => {
    const request = route.request()
    const { trigger } = request.postDataJSON() as { trigger: string }
    if (trigger !== 'MANUAL') {
      automatic.push(trigger)
      await route.fulfill({ json: NOT_DUE })
      return
    }
    manualHeaders.push(await request.allHeaders())
    if (adEnabled) await landingRead
    await route.fulfill({
      status: 202,
      json: { outcome: 'ACCEPTED', job: JOB, retryAfterSeconds: null },
    })
  })
  return { manualHeaders, automatic, afterClickReads: () => afterClickReads }
}

test('준비된 링크를 GPT 대역이 감지하고 늦은 수동 접수를 복귀 화면이 따라간다', async ({
  page,
}) => {
  const { manualHeaders, automatic, afterClickReads } = await fixture(page, true)
  const eligibleClicks: boolean[] = []
  await page.exposeBinding('observeAdClick', (_source, trusted: boolean) => {
    eligibleClicks.push(trusted)
  })
  await page.route(GPT_SCRIPT_SRC, (route) =>
    route.fulfill({
      contentType: 'text/javascript',
      body: `
        const queue = googletag.cmd;
        const listeners = new Map();
        const pubads = {
          setPrivacySettings() {},
          addEventListener(type, listener) { listeners.set(type, listener); },
          removeEventListener(type) { listeners.delete(type); },
        };
        const slot = { addService() {}, setConfig() {} };
        window.googletag = {
          cmd: { push(callback) { callback(); } },
          enums: { OutOfPageFormat: { INTERSTITIAL: 1 } },
          pubads() { return pubads; },
          defineOutOfPageSlot() { return slot; },
          enableServices() {},
          destroySlots() {},
          display() { listeners.get('slotOnload')({ slot }); },
        };
        for (const callback of queue) callback();
        document.addEventListener('click', (event) => {
          const link = event.target.closest('a[href]');
          if (link && !link.closest('[data-google-interstitial="false"]') && !event.defaultPrevented) {
            window.observeAdClick(event.isTrusted);
          }
        });
      `,
    }),
  )

  await page.goto(DASHBOARD_PATH)
  const link = page.getByRole('link', { name: planSyncContent.action })
  await expect(link).toHaveAttribute('href', DASHBOARD_SYNC_RETURN_PATH)
  await expect(page.locator('a[href]:not([data-google-interstitial="false"])')).toHaveCount(1)
  const returning = page.waitForRequest(
    (request) =>
      request.isNavigationRequest() && request.url().endsWith(DASHBOARD_SYNC_RETURN_PATH),
  )
  await link.click()
  await returning

  await expect.poll(afterClickReads).toBeGreaterThan(1)
  await expect(page.getByRole('button', { name: planSyncContent.action })).toHaveAttribute(
    'aria-disabled',
    'true',
  )
  expect(eligibleClicks).toEqual([true])
  expect(manualHeaders).toHaveLength(1)
  expect(manualHeaders[0]?.[CSRF_HEADER.toLowerCase()]).toBe(SIGNED_IN.csrfToken)
  expect(manualHeaders[0]?.['idempotency-key']).toBeTruthy()
  expect(automatic).toEqual(['ENTRY'])

  await page.reload()
  await expect(page.getByRole('button', { name: planSyncContent.action })).toBeVisible()
  await page.goBack()
  await expect(page.getByRole('button', { name: planSyncContent.action })).toBeVisible()
  expect(manualHeaders).toHaveLength(1)
})

test('광고 운영 중단에서는 직접 복귀 주소를 열거나 새로고침해도 수동 요청이 없고 버튼은 동작한다', async ({
  page,
}) => {
  const { manualHeaders } = await fixture(page, false)
  const external: string[] = []
  page.on('request', (request) => {
    if (request.url() === GPT_SCRIPT_SRC) external.push(request.url())
  })
  await page.goto(DASHBOARD_SYNC_RETURN_PATH)
  const button = page.getByRole('button', { name: planSyncContent.action })
  await expect(button).toBeVisible()
  expect(manualHeaders).toHaveLength(0)

  await page.reload()
  await expect(button).toBeVisible()
  expect(manualHeaders).toHaveLength(0)
  expect(external).toEqual([])

  await button.click()
  await expect.poll(() => manualHeaders.length).toBe(1)
})
