import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import { authContent } from '../src/content/auth'
import { operatorContent } from '../src/content/operator'

/**
 * 보호된 운영자 화면의 실제 Chromium 검증(Story 3.1).
 *
 * 두 층이다. 기본 `vite preview`에서도 성립하는 것 — 운영자 화면 chunk가 초기 로드에 없고 `/operator`에서만 받아진다는
 * 사실, 미인증 진입의 `next` — 은 언제나 돈다. 운영자 왕복(로그인 → 재인증 → 감사 내보내기)과 비운영자의 404 화면은
 * 백엔드와 dev-oidc 스텁이 함께 떠 있는 주입 origin에서만 돈다(`auth-entry.spec.ts`와 같은 게이트). 그 스택의 운영자
 * allowlist는 Google 스텁의 subject `e2e-operator`이고, compose 기본값(운영자 없음)이 아니라 `verify-same-origin.sh`가
 * 명시적으로 넣는다.
 */

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

/** 개발용 OIDC 스텁의 동의 링크. `auth-entry.spec.ts`와 같은 값이다(제품 카피가 아니다). */
const STUB_CONSENT_LINK = '동의하고 계속하기'

/** compose 스택의 운영자 subject. allowlist가 이 값을 운영자로 둔다. */
const OPERATOR_SUBJECT = 'e2e-operator'

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

const NARROW_VIEWPORT = { width: 320, height: 640 } as const

/** 운영자 화면 chunk의 요청인가. Vite가 모듈 이름으로 chunk 파일을 짓는다. */
function isOperatorChunk(url: string): boolean {
  return /\/assets\/OperatorScreen-[^/]+\.js$/.test(new URL(url).pathname)
}

/** 스텁 동의 화면에서 `subject`를 고정한 채 동의한다(`bybit-connection.spec.ts`와 같은 관용). */
async function consentWithSubject(page: Page, subject: string): Promise<void> {
  await expect(page.getByRole('link', { name: STUB_CONSENT_LINK })).toBeVisible()
  const url = new URL(page.url())
  url.searchParams.set('dev_subject', subject)
  await page.goto(url.toString())
  await page.getByRole('link', { name: STUB_CONSENT_LINK }).click()
}

/** 그 subject로 Google 스텁 로그인을 지난다. 착지 화면은 신규·기존에 따라 다르다. */
async function signIn(page: Page, subject: string): Promise<void> {
  await page.goto('/')
  await page.getByRole('link', { name: authContent.signIn.labels.google }).click()
  await consentWithSubject(page, subject)
  await page.waitForURL((url) => url.pathname !== '/' && !url.pathname.startsWith('/api/'))
}

/** 운영자 화면을 열고 감사 원장 구역이 그려질 때까지 기다린다. */
async function openOperator(page: Page): Promise<void> {
  await page.goto(operatorContent.path)
  await expect(
    page.getByRole('heading', { level: 2, name: operatorContent.audit.heading, exact: true }),
  ).toBeVisible()
}

test.describe('운영자 화면 · chunk 분리', () => {
  test('초기 화면은 운영자 chunk를 받지 않고 /operator에서만 받는다', async ({ page }) => {
    const chunks: string[] = []
    page.on('request', (request) => {
      if (isOperatorChunk(request.url())) {
        chunks.push(request.url())
      }
    })

    for (const path of ['/', '/onboarding', '/dashboard', '/settings']) {
      await page.goto(path)
      await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible()
    }
    expect(chunks).toEqual([])

    await page.goto(operatorContent.path)
    await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible()
    expect(chunks).toHaveLength(1)
  })

  test('미인증으로 /operator에 오면 그 경로를 next로 실은 인증 화면이다', async ({ page }) => {
    await page.goto(operatorContent.path)
    const link = page.getByRole('link', { name: authContent.signIn.labels.google })
    await expect(link).toBeVisible()
    await expect(link).toHaveAttribute('href', '/api/auth/authorize/google?next=%2Foperator')
    await expect(page.locator('body')).not.toContainText(operatorContent.audit.heading)

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  })
})

test.describe('운영자 화면 · 주입 origin', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 주입된 origin에서만 성립하는 시험이다. 파일째 사라지는 것보다 보고서에 "건너뜀"으로 남는 편이 낫다.
  test.skip(
    injectedOrigin === '',
    'PLAYWRIGHT_BASE_URL로 Nginx origin과 dev-oidc 스텁을 주입한 실행에서만 의미가 있다.',
  )

  test('비운영자에게는 없는 화면과 같은 문장 하나뿐이다', async ({ page }) => {
    await signIn(page, `e2e-not-operator-${String(Date.now())}`)
    await page.goto(operatorContent.path)
    await expect(
      page.getByRole('heading', { level: 1, name: operatorContent.notFound.heading }),
    ).toBeVisible()
    await expect(page.getByRole('button')).toHaveCount(0)
    await expect(page.locator('body')).not.toContainText('운영자')

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  })

  test('운영자가 재인증 뒤 감사 원장을 내보낸다', async ({ page }) => {
    await page.setViewportSize(NARROW_VIEWPORT)
    await signIn(page, OPERATOR_SUBJECT)
    await openOperator(page)

    // 일반 사용자와 구분되는 제목과 인접 고지.
    await expect(
      page.getByRole('heading', { level: 1, name: operatorContent.heading }),
    ).toBeVisible()

    // 접근성: 자동 검사 위반 0건, 조작 대상 44px 이상, 320px에서 가로 스크롤 없음.
    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
    for (const target of await page.locator('main button, main input, main select').all()) {
      const box = await target.boundingBox()
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    }
    const metrics = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)

    // 준비 → 최근 본인 확인 요구 → 재인증. 자동으로 이어 하지 않는다.
    const reason = page.getByLabel(operatorContent.export.reasonLabel)
    await reason.fill('e2e 정기 점검')
    await page.getByRole('button', { name: operatorContent.export.start }).click()
    await expect(page.getByText(operatorContent.export.reauthLabel)).toBeVisible()
    await page.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
    await consentWithSubject(page, OPERATOR_SUBJECT)
    await page.waitForURL((url) => url.pathname !== '/' && !url.pathname.startsWith('/api/'))
    // 재인증은 세션의 목적지로 돌아온다(첫 로그인이면 온보딩, `next=/operator` 로그인이면 이 화면). 어느 쪽이든 다시 연다.
    await openOperator(page)

    await page.getByLabel(operatorContent.export.reasonLabel).fill('e2e 정기 점검')
    await page.getByRole('button', { name: operatorContent.export.start }).click()
    const dialog = page.getByRole('dialog', { name: operatorContent.dialog.title })
    await expect(dialog).toBeVisible()
    // 취소가 기본 포커스다.
    await expect(dialog.getByRole('button', { name: operatorContent.dialog.cancel })).toBeFocused()
    const phrase = await dialog.locator('label strong').innerText()
    expect(phrase).toMatch(/^감사 내보내기 AUDIT_LEDGER \d{4}-\d{2}-\d{2}~\d{4}-\d{2}-\d{2}$/)
    await dialog.getByRole('textbox').fill(phrase)
    await dialog.getByRole('button', { name: operatorContent.dialog.confirm }).click()

    const result = page.getByRole('heading', {
      level: 3,
      name: operatorContent.export.result.heading,
    })
    await expect(result).toBeFocused()
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('status')).toContainText('감사 내보내기를 마쳤습니다')

    // 주소에 식별자·키가 없다. 브라우저 저장소에 아무것도 남지 않는다.
    expect(new URL(page.url()).search).toBe('')
    const storage = await page.evaluate(() => ({
      local: window.localStorage.length,
      session: window.sessionStorage.length,
    }))
    expect(storage).toEqual({ local: 0, session: 0 })
  })
})
