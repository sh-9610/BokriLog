import { expect, test, type Page } from '@playwright/test'

import { authContent } from '../src/content/auth'
import { connectionContent, revocationContent } from '../src/content/connection'
import { planConditionContent } from '../src/content/planConditions'
import { planResultContent } from '../src/content/planResult'
import { planSyncContent } from '../src/content/planSync'
import {
  collectAndClassify,
  connectAndSavePlan,
  publishAndOpenResult,
  startConversionByKeyboard,
} from './support/firstPlan'
import { consent, waitForLanding } from './support/navigation'

/*
 * Story 2.17(재연결, `RECONNECT-1.0`)의 실제 Chromium 왕복 하나: 첫 공식 결과 → 설정에서 연결 해제(Story 2.16) → 해제 기간에
 * 입금 하나 → 같은 Bybit 계정(같은 UID)의 새 키로 같은 흐름(Story 2.1)으로 다시 연결 → 대시보드의 해제 표시가 거둬지고
 * 동기화가 해제 기간의 입금까지 반영한 다음 공식 버전을 같은 계획에 발행한다.
 *
 * <b>주입된 origin에서만 돈다</b>(dev-oidc·dev-bybit 스텁과 백엔드가 있는 동일 출처). 실제 Bybit 왕복은 Epic 2의 실연동
 * 게이트가 소유한다.
 */

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

/** `GET /api/dashboard`가 보이는 묶음에 대해 말하는 것 — 페이지 자신이 같은 출처로 묻는다. */
interface Probe {
  readonly pointerVersion: number
  readonly planId: string
  readonly flowCount: number
  readonly basisAt: string
  readonly availability: string
  readonly reason: string | null
}

async function probe(page: Page): Promise<Probe> {
  return page.evaluate(async () => {
    const answer = await fetch('/api/dashboard', { credentials: 'same-origin', cache: 'no-store' })
    const body = (await answer.json()) as {
      result: { pointerVersion: number; planId: string; flowCount: number; basisAt: string }
      sync: { availability: string; reason?: string | null }
    }
    return {
      pointerVersion: body.result.pointerVersion,
      planId: body.result.planId,
      flowCount: body.result.flowCount,
      basisAt: body.result.basisAt,
      availability: body.sync.availability,
      reason: body.sync.reason ?? null,
    }
  })
}

/** 설정 화면에서 같은 사람으로 본인 확인을 다시 하고 설정 화면으로 돌아온다. 민감 조작 하나가 본인 확인 하나를 쓴다. */
async function reauthenticate(page: Page, subject: string): Promise<void> {
  await page.goto('/settings')
  await page.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
  await consent(page, subject)
  // 재인증은 세션의 목적지(착지 화면)로 돌아온다. 그 이동이 끝난 뒤에 설정 화면을 다시 연다.
  await waitForLanding(page)
  await page.goto('/settings')
  await expect(page.getByRole('heading', { name: connectionContent.heading })).toBeVisible()
}

test.describe('해제한 Bybit 연결의 재연결', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 실제 동일 출처 스텁에서만 성공 왕복을 검증한다.
  test.skip(injectedOrigin === '', 'PLAYWRIGHT_BASE_URL의 동일 출처 스텁이 필요하다.')

  test('해제 → 같은 계정 재연결 → 동기화가 해제 기간의 사건을 반영한 다음 버전을 같은 계획에 발행한다', async ({
    page,
  }) => {
    test.setTimeout(120_000)
    // 스텁의 `incremental` 시나리오: 연결 확인부터 10초마다 USDT 10 입금(UID별).
    const { key, subject } = await connectAndSavePlan(page, 'incremental', /1,[0-4]\d0\.00 USD/)
    await collectAndClassify(page, /입금 사건: \d+ · 출금 사건: 0 · 제외 사건: 0 · 검토 필요: 0/)
    const conversion = await startConversionByKeyboard(page)
    await expect(conversion.getByRole('status')).toContainText(
      planConditionContent.conversion.converted,
      { timeout: 15_000 },
    )
    await page.setViewportSize({ width: 1024, height: 900 })
    await publishAndOpenResult(page)
    await expect(page.getByRole('region', { name: planResultContent.heading })).toBeVisible({
      timeout: 15_000,
    })
    const first = await probe(page)
    expect(first.pointerVersion).toBe(1)

    // 해제(Story 2.16). 연결의 본인 확인은 연결이 썼으므로 새로 한다.
    await reauthenticate(page, subject)
    await page.getByRole('button', { name: revocationContent.start, exact: true }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await dialog
      .getByRole('button', { name: revocationContent.dialog.confirm, exact: true })
      .click()
    await expect(page.getByText(revocationContent.revokedNotice)).toBeVisible({ timeout: 15_000 })
    // 해제 상태에 재연결 안내와 시작 조작이 있다(Story 2.17 AC-7).
    await expect(page.getByText(revocationContent.reconnectSameAccount)).toBeVisible()
    await expect(page.getByText(revocationContent.reconnectOtherAccount)).toBeVisible()
    const revoked = await probe(page)
    expect(revoked.availability).toBe('UNAVAILABLE')
    expect(revoked.reason).toBe('CONNECTION_REVOKED')

    // 해제 기간에 입금이 하나 생긴다(첫 tc 뒤 10초 간격).
    const firstTc = Date.parse(first.basisAt)
    await expect
      .poll(() => Date.now(), { timeout: 25_000, intervals: [500] })
      .toBeGreaterThanOrEqual(firstTc + 11_000)

    // 같은 UID의 새 Secret으로 다시 연결한다 — Story 2.1과 같은 준비·입력·검증 흐름.
    await reauthenticate(page, subject)
    await page.getByRole('button', { name: revocationContent.reconnectStart, exact: true }).click()
    await expect(
      page.getByRole('heading', { level: 3, name: connectionContent.form.heading }),
    ).toBeVisible()
    const secret = `s-${Math.random().toString(36).slice(2)}-${String(Date.now())}`
    await page.getByLabel(connectionContent.form.apiKeyLabel).fill(key)
    await page.getByLabel(connectionContent.form.apiSecretLabel).fill(secret)
    await page.getByRole('button', { name: connectionContent.form.submit, exact: true }).click()
    await expect(page.getByText(connectionContent.resumed)).toBeVisible({ timeout: 25_000 })
    const text = await page.locator('body').innerText()
    expect(text).not.toContain(secret)
    expect(text).not.toContain(key)
    expect(page.url()).not.toContain(secret)
    expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)

    // 대시보드: 해제 표시가 거둬지고 동기화 조작이 돌아온다.
    await page.goto('/onboarding')
    const panel = page.getByRole('region', { name: planResultContent.heading })
    await expect(panel).toBeVisible({ timeout: 15_000 })
    const resumed = await probe(page)
    expect(resumed.reason).toBeNull()
    expect(resumed.availability).toBe('AVAILABLE')
    expect(resumed.pointerVersion).toBe(1)
    const trust = panel.getByRole('region', { name: planResultContent.trustHeading })
    const control = trust.getByRole('button', { name: planSyncContent.action })
    await expect(control).toHaveAttribute('aria-disabled', 'false')
    await control.focus()
    await page.keyboard.press('Enter')

    await expect
      .poll(async () => (await probe(page)).pointerVersion, { timeout: 40_000, intervals: [1_000] })
      .toBe(2)
    const second = await probe(page)
    expect(second.planId).toBe(first.planId)
    expect(second.flowCount).toBeGreaterThan(first.flowCount)
    expect(Date.parse(second.basisAt)).toBeGreaterThan(firstTc + 11_000)
    // 대시보드 어디에도 해제 사유 문장이 남지 않는다(동기화·검증·신선도 축의 해제 문장이 모두 "Bybit 연결을 해제해"로 시작한다).
    await expect(page.getByText(planSyncContent.reasons.CONNECTION_REVOKED)).toHaveCount(0)
    await expect(panel.getByText(/Bybit 연결을 해제해/)).toHaveCount(0)
  })
})
