import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Locator, type Page } from '@playwright/test'

import { authContent } from '../src/content/auth'
import {
  operatorContent,
  recoverButtonLabel,
  RECOVERY_CONDITION_LABELS,
} from '../src/content/operator'
import { serviceNoticeContent } from '../src/content/serviceNotice'
import { consent, signIn, waitForScreen, type LandingPath } from './support/navigation'

/**
 * 보호된 운영자 화면의 실제 Chromium 검증(Story 3.1·3.2·3.4).
 *
 * 두 층이다. 기본 `vite preview`에서도 성립하는 것 — 운영자 화면 chunk가 초기 로드에 없고 `/operator`에서만 받아진다는
 * 사실, 미인증 진입의 `next` — 은 언제나 돈다. 운영자 왕복(로그인 → 재인증 → 감사 내보내기, 그리고 Story 3.2의 전역 광고
 * 제한 `restrict-advertising`과 Story 3.4의 그 제한 복구 `lift-restriction`)과 비운영자의 404 화면은
 * 백엔드와 dev-oidc 스텁이 함께 떠 있는 주입 origin에서만 돈다(`auth-entry.spec.ts`와 같은 게이트). Story 3.5의 서비스
 * 공지(`publish-notice`)는 그 광고 제한을 덮어 발행하고, 같은 사용자의 대시보드에서 공지를 본 뒤 복구 공지로 맺는다. 그
 * 스택의 지원 연락처는 `verify-same-origin.sh`가 넣는 개발용 주소다. 그 스택의 운영자
 * allowlist는 Google 스텁의 subject `e2e-operator`이고, compose 기본값(운영자 없음)이 아니라 `verify-same-origin.sh`가
 * 명시적으로 넣는다.
 */

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

/** compose 스택의 운영자 subject. allowlist가 이 값을 운영자로 둔다. */
const OPERATOR_SUBJECT = 'e2e-operator'

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

const NARROW_VIEWPORT = { width: 320, height: 640 } as const

/** 운영자 화면 chunk의 요청인가. Vite가 모듈 이름으로 chunk 파일을 짓는다. */
function isOperatorChunk(url: string): boolean {
  return /\/assets\/OperatorScreen-[^/]+\.js$/.test(new URL(url).pathname)
}

/** 스택의 지원 연락처(`verify-same-origin.sh`가 넣는 개발용 주소). */
const SUPPORT_CONTACT = 'support@bokrilog.test'

/** 숫자 없는 짧은 표지. 공지 메시지는 숫자 6자리 이상 연속을 받지 않으므로 시각 대신 이것으로 실행을 가른다. */
function letters(): string {
  return Array.from({ length: 8 }, () =>
    String.fromCharCode(97 + Math.floor(Math.random() * 26)),
  ).join('')
}

/**
 * 운영 명령 하나를 준비해 확인 대화상자를 연다. 최근 본인 확인이 없으면 화면이 세 로그인 수단을 보이고, 확인을 마친 뒤
 * 다시 준비한다 — 자동으로 이어 하지 않는다. 방금 로그인한 세션은 본인 확인을 요구받지 않을 수 있으므로 두 갈래를 다
 * 받는다. 재인증은 세션의 목적지 — 그 세션을 연 로그인의 착지(`returnTo`) — 로 돌아오므로 그 화면에 착지한 뒤 운영자 화면을
 * 다시 연다.
 */
async function preparedDialog(
  page: Page,
  returnTo: LandingPath,
  title: string,
  reauthLabel: string,
  prepare: () => Promise<void>,
): Promise<Locator> {
  await prepare()
  const dialog = page.getByRole('dialog', { name: title })
  const reauth = page.getByText(reauthLabel, { exact: true })
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

/** 대시보드의 서비스 공지 영역에서 그 메시지의 항목. 접근성·320px 검사를 함께 한다. */
async function dashboardNotice(page: Page, message: string): Promise<Locator> {
  await page.goto('/dashboard')
  const region = page.getByRole('region', { name: serviceNoticeContent.heading })
  await expect(region).toBeVisible()
  const item = region.getByRole('listitem').filter({ hasText: message })
  await expect(item).toHaveCount(1)
  await expect(item.getByRole('link', { name: SUPPORT_CONTACT })).toHaveAttribute(
    'href',
    `mailto:${SUPPORT_CONTACT}`,
  )
  // 공지 영역은 정적이다 — live region을 더하지 않는다. 재시도 버튼이 없다.
  await expect(region.getByRole('status')).toHaveCount(0)
  await expect(region.locator('[aria-live]')).toHaveCount(0)
  await expect(region.getByRole('button')).toHaveCount(0)
  const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
  expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  await page.setViewportSize(NARROW_VIEWPORT)
  await expect(item).toBeVisible()
  const metrics = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)
  await page.setViewportSize({ width: 1280, height: 720 })
  return item
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
    const landing = await signIn(page, OPERATOR_SUBJECT)
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
    const reason = page.getByLabel(operatorContent.export.reasonLabel, { exact: true })
    await reason.fill('e2e 정기 점검')
    await page.getByRole('button', { name: operatorContent.export.start }).click()
    await expect(page.getByText(operatorContent.export.reauthLabel, { exact: true })).toBeVisible()
    await page.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
    await consent(page, OPERATOR_SUBJECT)
    // 재인증은 세션의 목적지(이 세션을 연 로그인의 착지)로 돌아온다. 거기 착지한 뒤 운영자 화면을 다시 연다.
    await waitForScreen(page, landing)
    await openOperator(page)

    await page.getByLabel(operatorContent.export.reasonLabel, { exact: true }).fill('e2e 정기 점검')
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

  /**
   * Story 3.2·3.4·3.5: 전역 광고 제한을 만들고, 그 제한을 덮는 서비스 공지를 발행해 같은 사용자의 대시보드에서 본 뒤,
   * 제한을 복구하고 복구 공지로 맺는다. 광고는 아직 소비자가 없어(Epic 4) 다른 기능에 영향이 없다 — 같은 스택의 다른 시험을
   * 흔들지 않는 유일한 전역 제한이다. 확인 대화상자는 미리보기를 먼저 말하고, 대상명 `전역`을 담은 문구를 다시 입력해야
   * 확정된다. 만든 제한을 끝에서 복구하므로 같은 스택에 이 시험을 다시 돌려도 준비가 `ALREADY_RESTRICTED`로 막히지 않는다.
   * 복구 공지는 72시간 동안 남으므로 항목은 이 실행의 메시지로 가른다.
   */
  test('운영자가 전역 광고 제한을 확정하고 공지한 뒤 검증 증거와 함께 복구하고 복구를 공지한다', async ({
    page,
  }) => {
    // 본인 확인 왕복이 여럿이다(제한·공지·복구·복구 공지).
    test.slow()
    let landing = await signIn(page, OPERATOR_SUBJECT)
    await openOperator(page)
    const copy = operatorContent.restriction
    await expect(page.getByRole('heading', { level: 2, name: copy.heading })).toBeVisible()

    const prepare = async (): Promise<void> => {
      await page.getByLabel(copy.featureLabel).selectOption('ADVERTISING')
      await expect(page.getByLabel(copy.targetLabel)).toHaveValue(copy.globalTarget)
      await page.getByLabel(copy.reasonLabel).fill('e2e 광고 오류 범위 제한')
      await page.getByRole('button', { name: copy.start }).click()
    }

    // 준비 → 최근 본인 확인 요구 → 재인증. 자동으로 이어 하지 않는다.
    await prepare()
    await expect(page.getByText(copy.reauthLabel, { exact: true })).toBeVisible()
    await page.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
    await consent(page, OPERATOR_SUBJECT)
    await waitForScreen(page, landing)
    await openOperator(page)

    await prepare()
    const dialog = page.getByRole('dialog', { name: operatorContent.restrictionDialog.title })
    await expect(dialog).toBeVisible()
    await expect(
      dialog.getByRole('button', { name: operatorContent.restrictionDialog.cancel }),
    ).toBeFocused()
    await expect(dialog).toContainText(operatorContent.restrictionDialog.affectedPlans(0))
    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])

    await expect(dialog.locator('label strong')).toHaveText('광고 제한 전역')
    await dialog.getByRole('textbox').fill('광고 제한 전역')
    await dialog.getByRole('button', { name: operatorContent.restrictionDialog.confirm }).click()

    const result = page.getByRole('heading', { level: 3, name: copy.result.heading })
    await expect(result).toBeFocused()
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('status')).toContainText('광고 제한(전역)이 효력을 가졌습니다')
    const active = page.getByRole('list', { name: copy.active.heading })
    await expect(active.getByRole('listitem').filter({ hasText: '광고' })).toHaveCount(1)

    const after = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(after.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
    expect(new URL(page.url()).search).toBe('')

    // Story 3.5: 그 제한을 덮는 새 사건 공지. 영향·계속 가능 기능은 서버가 그 제한에서 계산한다.
    const notice = operatorContent.notice
    const noticeDialog = operatorContent.noticeDialog
    const marker = letters()
    const investigating = `e2e 광고 오류 조사 ${marker}`
    // 다음 안내 시각은 서울 시각(KST)으로 입력한다 — 브라우저 시간대와 무관하다.
    const nextUpdate = new Intl.DateTimeFormat('sv-SE', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .format(new Date(Date.now() + 2 * 60 * 60 * 1000))
      .replace(' ', 'T')
    const publishNew = async (): Promise<void> => {
      const section = page.getByRole('region', { name: notice.heading })
      await section.getByLabel(notice.incidentLabel, { exact: true }).selectOption('NEW')
      await section.getByRole('checkbox', { name: /광고 · 전역/ }).check()
      await section.getByLabel(notice.messageLabel).fill(investigating)
      await section.getByLabel(notice.nextUpdateLabel).fill(nextUpdate)
      await section.getByRole('button', { name: notice.start }).click()
    }
    const opened = await preparedDialog(
      page,
      landing,
      noticeDialog.title,
      notice.reauthLabel,
      publishNew,
    )
    await expect(opened.getByRole('button', { name: noticeDialog.cancel })).toBeFocused()
    await expect(opened).toContainText(`${noticeDialog.affected}: 광고`)
    await expect(opened).toContainText(
      `${noticeDialog.continuing}: 새 거래소 연결, 동기화와 새 계산 반영, 계산 결과 표시`,
    )
    const noticeScan = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(noticeScan.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual(
      [],
    )
    await expect(opened.locator('label strong')).toHaveText('공지 발행 새 사건 제한 1건')
    await opened.getByRole('textbox').fill('공지 발행 새 사건 제한 1건')
    await opened.getByRole('button', { name: noticeDialog.confirm }).click()
    const published = page.getByRole('group', { name: notice.result.heading })
    await expect(page.getByRole('heading', { level: 3, name: notice.result.heading })).toBeFocused()
    const incidentText = await published.getByText(`${notice.result.incident}:`).innerText()
    const incident = incidentText.split(':')[1]?.trim() ?? ''
    expect(incident).toMatch(/^[0-9a-f-]{36}$/)

    // 같은 사용자가 다시 로그인해 대시보드에서 공지를 본다(첫 로그인 세션은 온보딩으로 밀린다).
    await page.getByRole('button', { name: authContent.signOut.label }).click()
    await page.waitForURL((url) => url.pathname === '/')
    landing = await signIn(page, OPERATOR_SUBJECT)
    const shown = await dashboardNotice(page, investigating)
    await expect(shown).toContainText(`${serviceNoticeContent.stateLabel}: 조사 중`)
    await openOperator(page)

    // Story 3.4: 활성 목록의 그 행에서 복구한다. 최근 본인 확인이 없으면 다시 거친다.
    const recovery = operatorContent.recovery
    const recover = async (): Promise<void> => {
      // 행마다의 복구 버튼은 기능·범위·대상을 붙인 이름으로 갈린다.
      await page
        .getByRole('list', { name: copy.active.heading })
        .getByRole('button', {
          name: recoverButtonLabel('ADVERTISING', 'GLOBAL', '전역'),
          exact: true,
        })
        .click()
      const group = page.getByRole('group', { name: recovery.heading })
      await expect(page.getByRole('heading', { level: 3, name: recovery.heading })).toBeFocused()
      for (const label of Object.values(RECOVERY_CONDITION_LABELS)) {
        await group.getByLabel(label).check()
      }
      await group.getByLabel(recovery.reasonLabel).fill('e2e 광고 SDK 오류 원인 제거')
      await group.getByLabel(recovery.verificationLabel).fill('e2e 광고 영향 범위 재현 시험 통과')
      await group.getByLabel(recovery.evidenceLabel).fill('e2e 회귀 기록')
      await group.getByLabel(recovery.deployVersionLabel).fill('e2e-1.0.0')
      await group.getByRole('button', { name: recovery.start }).click()
    }

    const recoveryDialog = await preparedDialog(
      page,
      landing,
      operatorContent.recoveryDialog.title,
      recovery.reauthLabel,
      recover,
    )
    await expect(
      recoveryDialog.getByRole('button', { name: operatorContent.recoveryDialog.cancel }),
    ).toBeFocused()
    await expect(recoveryDialog).toContainText(operatorContent.recoveryDialog.reopenedStages)
    const dialogScan = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(dialogScan.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual(
      [],
    )

    await expect(recoveryDialog.locator('label strong')).toHaveText('복구 광고 전역')
    await recoveryDialog.getByRole('textbox').fill('복구 광고 전역')
    await recoveryDialog
      .getByRole('button', { name: operatorContent.recoveryDialog.confirm })
      .click()

    const recovered = page.getByRole('heading', { level: 3, name: recovery.result.heading })
    await expect(recovered).toBeFocused()
    await expect(recoveryDialog).toHaveCount(0)
    await expect(page.getByRole('status')).toContainText('광고 제한(전역)을 복구했습니다')
    await expect(
      page
        .getByRole('list', { name: copy.active.heading })
        .getByRole('listitem')
        .filter({ hasText: '광고' }),
    ).toHaveCount(0)

    const recoveredScan = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(
      recoveredScan.violations.map((violation) => `${violation.id}: ${violation.help}`),
    ).toEqual([])
    expect(new URL(page.url()).search).toBe('')

    // Story 3.5: 같은 사건의 복구 공지. 덮는 제한은 비워 두면 직전 버전의 것을 잇는다(닫힌 제한은 목록에 없다).
    const fixed = `e2e 광고 오류 원인 수정 ${marker}`
    const publishRecovered = async (): Promise<void> => {
      const section = page.getByRole('region', { name: notice.heading })
      await section.getByLabel(notice.incidentLabel, { exact: true }).selectOption(incident)
      await section.getByLabel(notice.statusLabel).selectOption('RECOVERED')
      await expect(section.getByLabel(notice.nextUpdateLabel)).toHaveCount(0)
      await section.getByLabel(notice.messageLabel).fill(fixed)
      await section.getByRole('button', { name: notice.start }).click()
    }
    const closing = await preparedDialog(
      page,
      landing,
      noticeDialog.title,
      notice.reauthLabel,
      publishRecovered,
    )
    await expect(closing.locator('label strong')).toHaveText(
      `공지 복구됨 ${incident.slice(0, 8)} v2`,
    )
    await closing.getByRole('textbox').fill(`공지 복구됨 ${incident.slice(0, 8)} v2`)
    await closing.getByRole('button', { name: noticeDialog.confirm }).click()
    await expect(page.getByRole('heading', { level: 3, name: notice.result.heading })).toBeFocused()
    await expect(page.getByRole('status')).toContainText('공지 v2(복구됨)를 발행했습니다')
    const history = page.getByRole('list', { name: notice.history.heading })
    await expect(history.getByRole('listitem').filter({ hasText: incident }).first()).toContainText(
      notice.history.closed,
    )

    // 같은 사용자의 대시보드: 복구 공지가 복구 시각과 함께 남는다.
    const closed = await dashboardNotice(page, fixed)
    await expect(closed).toContainText(`${serviceNoticeContent.stateLabel}: 복구됨`)
    await expect(closed).toContainText(serviceNoticeContent.recoveredAt)
    expect(new URL(page.url()).search).toBe('')
    const storage = await page.evaluate(() => ({
      local: window.localStorage.length,
      session: window.sessionStorage.length,
    }))
    expect(storage).toEqual({ local: 0, session: 0 })
  })
})
