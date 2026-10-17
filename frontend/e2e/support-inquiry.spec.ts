import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Locator, type Page } from '@playwright/test'

import { authContent } from '../src/content/auth'
import { operatorContent } from '../src/content/operator'
import { supportContent } from '../src/content/support'
import { consent, signIn, waitForScreen, type LandingPath } from './support/navigation'

/**
 * 사용자 문의의 실제 Chromium 검증(Story 3.6).
 *
 * 백엔드와 dev-oidc 스텁이 함께 떠 있는 주입 origin에서만 돈다(`operator.spec.ts`와 같은 게이트). 한 사용자가 설정 화면의
 * `문의` 영역에서 접수하고(접수 번호·답변 목표 확인 블록과 status 한 문장), 운영자가 보호 표면에서 그 문의를 답변 완료로
 * 기록한 뒤(재인증·확인 문구 재입력), 같은 사용자가 다시 로그인해 목록에서 답변을 본다. 설정 화면은 axe·320px 검사를 함께
 * 한다. 운영자 subject는 `verify-same-origin.sh`가 allowlist에 넣는 `e2e-operator`다.
 */

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

const OPERATOR_SUBJECT = 'e2e-operator'

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

const NARROW_VIEWPORT = { width: 320, height: 640 } as const

/** 숫자 없는 짧은 표지. 문의 입력은 6자리 이상 숫자를 받지 않으므로 시각 대신 이것으로 실행을 가른다. */
function letters(): string {
  return Array.from({ length: 8 }, () =>
    String.fromCharCode(97 + Math.floor(Math.random() * 26)),
  ).join('')
}

async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: authContent.signOut.label }).click()
  await page.waitForURL((url) => url.pathname === '/')
}

async function openSettingsSupport(page: Page): Promise<Locator> {
  await page.goto('/settings')
  const region = page.getByRole('region', { name: supportContent.heading })
  await expect(region).toBeVisible()
  return region
}

async function openOperator(page: Page): Promise<void> {
  await page.goto(operatorContent.path)
  await expect(
    page.getByRole('heading', { level: 2, name: operatorContent.inquiry.heading, exact: true }),
  ).toBeVisible()
}

/**
 * 문의 처리를 준비해 확인 대화상자를 연다. 최근 본인 확인이 없으면 화면이 세 로그인 수단을 보이고, 확인을 마친 뒤 다시
 * 준비한다 — 자동으로 이어 하지 않는다. 방금 로그인한 세션은 본인 확인을 요구받지 않을 수 있으므로 두 갈래를 다 받는다
 * (`operator.spec.ts`의 `preparedDialog`와 같은 관용). 재인증은 그 세션을 연 로그인의 착지(`returnTo`)로 돌아온다.
 */
async function preparedInquiryDialog(
  page: Page,
  returnTo: LandingPath,
  prepare: () => Promise<void>,
): Promise<Locator> {
  await prepare()
  const dialog = page.getByRole('dialog', { name: operatorContent.inquiryDialog.title })
  const reauth = page.getByText(operatorContent.inquiry.reauthLabel, { exact: true })
  await expect(dialog.or(reauth)).toBeVisible()
  if (await reauth.isVisible()) {
    await page.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
    await consent(page, OPERATOR_SUBJECT)
    await waitForScreen(page, returnTo)
    await openOperator(page)
    await prepare()
  }
  await expect(dialog).toBeVisible()
  return dialog
}

test.describe('사용자 문의 · 주입 origin', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 주입된 origin에서만 성립하는 시험이다. 파일째 사라지는 것보다 보고서에 "건너뜀"으로 남는 편이 낫다.
  test.skip(
    injectedOrigin === '',
    'PLAYWRIGHT_BASE_URL로 Nginx origin과 dev-oidc 스텁을 주입한 실행에서만 의미가 있다.',
  )

  test('사용자가 접수하고 운영자가 답변하면 사용자 목록에 답변이 보인다', async ({ page }) => {
    // 로그인 왕복이 넷이다(사용자·운영자·재인증·사용자).
    test.slow()
    const userSubject = `e2e-inquiry-${letters()}`
    const marker = letters()
    const title = `e2e 동기화 문의 ${marker}`
    const answer = `e2e 답변 ${marker} 연결을 다시 확인해 주세요.`

    // 1. 사용자 접수.
    await signIn(page, userSubject)
    let region = await openSettingsSupport(page)
    await expect(region.getByText(supportContent.sensitiveNotice)).toBeVisible()
    await region.getByLabel(supportContent.categoryLabel).selectOption('SYNC_PLAN')
    await region.getByLabel(supportContent.titleLabel).fill(title)
    await region
      .getByLabel(supportContent.descriptionLabel)
      .fill('10월 12일 오후부터 대시보드가 갱신되지 않습니다.')
    await region.getByRole('button', { name: supportContent.submit }).click()

    const receipt = region.getByRole('group', { name: supportContent.receipt.heading })
    await expect(receipt).toBeVisible()
    const reference = (await receipt.locator('dd').first().innerText()).trim()
    expect(reference).toMatch(/^Q-[A-Z2-7]{10}$/)
    await expect(page.getByRole('status')).toContainText(supportContent.received(reference))
    await expect(receipt).toContainText('KST')
    const row = region.getByRole('listitem').filter({ hasText: title })
    await expect(row).toContainText('접수됨')

    // 접근성: 자동 검사 위반 0건, 320px에서 가로 스크롤 없음. 이 영역은 live region을 더하지 않는다.
    await expect(region.locator('[aria-live]')).toHaveCount(0)
    const scan = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(scan.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
    await page.setViewportSize(NARROW_VIEWPORT)
    await expect(region.getByRole('button', { name: supportContent.submit })).toBeVisible()
    for (const target of await region.locator('button, input, select').all()) {
      const box = await target.boundingBox()
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    }
    const metrics = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)
    await page.setViewportSize({ width: 1280, height: 720 })
    // 주소에 입력이 없다. 브라우저 저장소에 아무것도 남지 않는다.
    expect(new URL(page.url()).search).toBe('')
    const storage = await page.evaluate(() => ({
      local: window.localStorage.length,
      session: window.sessionStorage.length,
    }))
    expect(storage).toEqual({ local: 0, session: 0 })
    await signOut(page)

    // 2. 운영자 답변.
    const operatorLanding = await signIn(page, OPERATOR_SUBJECT)
    await openOperator(page)
    const copy = operatorContent.inquiry
    const dialogCopy = operatorContent.inquiryDialog
    const prepare = async (): Promise<void> => {
      const section = page.getByRole('region', { name: copy.heading })
      await section.getByRole('button', { name: copy.select(reference) }).click()
      await expect(
        section.getByRole('heading', { level: 3, name: `${copy.detail.heading} ${reference}` }),
      ).toBeFocused()
      await section.getByLabel(copy.statusLabel).selectOption('ANSWERED')
      await section.getByLabel(copy.answerLabel).fill(answer)
      await section.getByLabel(copy.reasonLabel).fill('e2e 문의 답변 기록')
      await section.getByRole('button', { name: copy.start }).click()
    }
    const dialog = await preparedInquiryDialog(page, operatorLanding, prepare)
    await expect(dialog.getByRole('button', { name: dialogCopy.cancel })).toBeFocused()
    await expect(dialog).toContainText(`${dialogCopy.transition}: 접수됨 → 답변 완료`)
    const dialogScan = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(dialogScan.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual(
      [],
    )
    await expect(dialog.locator('label strong')).toHaveText(`문의 답변 완료 ${reference}`)
    await dialog.getByRole('textbox').fill(`문의 답변 완료 ${reference}`)
    await dialog.getByRole('button', { name: dialogCopy.confirm }).click()
    await expect(page.getByRole('heading', { level: 3, name: copy.result.heading })).toBeFocused()
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('status')).toContainText('답변 완료')
    expect(new URL(page.url()).search).toBe('')
    await signOut(page)

    // 3. 같은 사용자가 목록에서 답변을 본다.
    await signIn(page, userSubject)
    region = await openSettingsSupport(page)
    const answered = region.getByRole('listitem').filter({ hasText: title })
    await expect(answered).toContainText('답변 완료')
    await expect(answered).toContainText(answer)
  })
})
