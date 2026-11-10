import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'

import { accountDeletionContent } from '../src/content/accountDeletion'
import { authContent } from '../src/content/auth'
import { consent, LANDING_TIMEOUT_MS, signIn, waitForLanding } from './support/navigation'

/**
 * 계정 삭제의 실제 Chromium 왕복(Epic 5 회고 AI-3 — 브라우저 층·동일 출처 스택에서 삭제 경로가 한 번도 돌지 않던 공백).
 *
 * 백엔드·dev-oidc 스텁·Worker가 함께 떠 있는 주입 origin에서만 돈다(`support-inquiry.spec.ts`와 같은 게이트). 이 스택의 백엔드는 역할
 * `all`이라 Worker를 겸하고, `verify-same-origin.sh`가 tombstone digest 키를 넣었을 때만 삭제 실행을 집는다 — 키가 없으면 이 시험은
 * 완료를 기다리다 시한으로 실패한다(그것이 이 시험이 지키는 사실이다).
 *
 * 한 사용자가 설정 화면에서 본인 확인 → `계정 삭제 요청` → 확인 대화상자(고정 문구 입력) → 확정한 뒤, 진행 화면의 `다시 확인`을 눌러
 * Worker가 운영 데이터를 지운 완료 화면을 보고, 로그아웃한 뒤 같은 로그인 수단으로 다시 로그인하면 재가입 차단 표지(`deleted`)를 받는다.
 * 진행 화면에는 주기 확인이 없으므로(사용자가 누를 때만 읽는다) 대기는 그 버튼을 누르는 반복이다. 시험마다 새 subject라 같은 스택에
 * 다시 돌려도 된다.
 */

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

/** Worker가 여덟 단계를 끝낼 때까지 `다시 확인`을 눌러 보는 상한(ms). dispatch는 1초 간격이고 단계는 짧은 트랜잭션이다. */
const ERASURE_TIMEOUT_MS = 60_000

const copy = accountDeletionContent

/**
 * 확인 문구 — 서버 정책 상수(`AccountDeletionPolicy`)와 화면이 함께 읽는 공유 표(`accountDeletionPolicy.fixture.json`)의 값이다. 대화상자에
 * 그려진 문자열을 다시 읽어 옮기지 않는다 — 그러면 서버가 다른 문구를 보내도 시험이 그대로 통과한다. e2e 타입 설정은 JSON import를
 * 열지 않으므로 파일을 읽는다.
 */
const CONFIRMATION_PHRASE = (
  JSON.parse(
    readFileSync(
      new URL('../src/content/accountDeletionPolicy.fixture.json', import.meta.url),
      'utf8',
    ),
  ) as { readonly confirmation: string }
).confirmation

/** 실행마다 다른 subject. 지운 identity는 재가입이 막히므로 같은 값을 다시 쓰지 않는다. */
function uniqueSubject(): string {
  const letters = Array.from({ length: 10 }, () =>
    String.fromCharCode(97 + Math.floor(Math.random() * 26)),
  ).join('')
  return `e2e-deletion-${letters}`
}

/** 설정 화면에서 같은 사람으로 본인 확인을 하고 설정 화면으로 돌아온다(`bybit-reconnect.spec.ts`와 같은 관용). */
async function reauthenticate(page: Page, subject: string): Promise<void> {
  await page.goto('/settings')
  await page.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
  await consent(page, subject)
  // 재인증은 세션의 목적지(착지 화면)로 돌아온다. 그 이동이 끝난 뒤에 설정 화면을 다시 연다.
  await waitForLanding(page)
  await page.goto('/settings')
  await expect(
    page.getByRole('heading', { level: 2, name: copy.heading, exact: true }),
  ).toBeVisible({ timeout: LANDING_TIMEOUT_MS })
}

test.describe('계정 삭제 · 주입 origin', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 주입된 origin에서만 성립하는 시험이다. 파일째 사라지는 것보다 보고서에 "건너뜀"으로 남는 편이 낫다.
  test.skip(
    injectedOrigin === '',
    'PLAYWRIGHT_BASE_URL로 Nginx origin·dev-oidc 스텁·Worker를 주입한 실행에서만 의미가 있다.',
  )

  test('요청 → 진행 → 완료 → 같은 로그인 수단의 재가입 거부', async ({ page }) => {
    // 로그인 왕복 셋(로그인·본인 확인·재로그인)과 Worker 대기. 주입 origin 전체 실행의 globalTimeout 안이다.
    test.setTimeout(120_000)
    const subject = uniqueSubject()

    // 1. 로그인과 본인 확인. 삭제 요청은 최근 본인 확인 위에서만 준비된다.
    await signIn(page, subject)
    await reauthenticate(page, subject)

    // 2. 요청: 설정 화면의 `계정 삭제` 영역 → 확인 대화상자(기본 초점은 취소) → 고정 문구 입력 → 확정.
    const region = page.getByRole('region', { name: copy.heading, exact: true })
    const start = region.getByRole('button', { name: copy.start, exact: true })
    await expect(start).toBeVisible({ timeout: LANDING_TIMEOUT_MS })
    await start.click()
    const dialog = page.getByRole('dialog', { name: copy.dialog.title })
    await expect(dialog).toBeVisible()
    await expect(
      dialog.getByRole('button', { name: copy.dialog.cancel, exact: true }),
    ).toBeFocused()
    // 대화상자가 공유 표의 확인 문구를 보이고, 사용자는 그 문구를 그대로 입력한다.
    expect(CONFIRMATION_PHRASE).not.toBe('')
    await expect(dialog).toContainText(CONFIRMATION_PHRASE)
    await dialog.getByRole('textbox').fill(CONFIRMATION_PHRASE)
    await dialog.getByRole('button', { name: copy.dialog.confirm, exact: true }).click()

    // 3. 진행: 어느 경로에서든 진행 화면 하나다. Worker가 이미 끝냈으면 곧바로 완료 화면일 수 있다.
    const progressHeading = page.getByRole('heading', {
      level: 2,
      name: copy.progress.title,
      exact: true,
    })
    const completionHeading = page.getByRole('heading', {
      level: 2,
      name: copy.completion.title,
      exact: true,
    })
    await expect(progressHeading.or(completionHeading)).toBeVisible({
      timeout: LANDING_TIMEOUT_MS,
    })
    await expect(page.getByRole('dialog')).toHaveCount(0)

    /*
     * 4. 완료까지 다시 확인을 누른다 — 진행 화면은 스스로 다시 읽지 않는다. 다시 확인 버튼은 진행 화면(`삭제 진행 상태 다시 확인`)과
     * 완료 쪽 화면(`삭제 결과 다시 확인`)에서 이름이 다르므로 둘 중 보이는 것을 누르고, 누름은 1초 안에 끝나지 않으면 이번 반복을
     * 버린다(긴 기본 대기에 빠지지 않게). 실행이 재시도 필요로 멈추면(`삭제 다시 시도`가 보인다) 기다려도 끝나지 않으므로 그 자리에서
     * 실패 범위 문장과 함께 실패한다.
     */
    const recheck = page
      .getByRole('button', { name: copy.progress.retry, exact: true })
      .or(page.getByRole('button', { name: copy.completion.retry, exact: true }))
    const retryErasure = page.getByRole('button', { name: copy.progress.retryErasure, exact: true })
    const failedScope = page.getByRole('list', { name: copy.progress.failedScopeHeading })
    let stuck = null as string | null
    await expect(async () => {
      if (await retryErasure.isVisible()) {
        stuck = await failedScope
          .innerText({ timeout: 1_000 })
          .catch(() => '(실패 범위를 읽지 못함)')
        return
      }
      if (!(await completionHeading.isVisible())) {
        await recheck.click({ timeout: 1_000 })
      }
      await expect(completionHeading.or(retryErasure)).toBeVisible({ timeout: 2_000 })
    }).toPass({ timeout: ERASURE_TIMEOUT_MS, intervals: [1_000] })
    expect(stuck, `삭제 실행이 재시도 필요로 멈췄다 — 실패 범위: ${String(stuck)}`).toBeNull()
    await expect(completionHeading).toBeVisible()

    // 완료 화면: 운영 데이터 삭제·백업 사본 파기·전체 삭제를 서로 다른 상태로 말한다(백업 파기는 아직이다).
    const completion = page.getByRole('region', { name: copy.completion.title, exact: true })
    await expect(completion).toContainText(copy.completion.operationalErasedAtLabel)
    await expect(completion).toContainText(copy.completion.backupPending)
    await expect(completion).toContainText(copy.completion.completionPending)
    await expect(completion).toContainText(copy.completion.legalNone)
    await expect(
      completion.getByRole('button', { name: copy.progress.retryErasure, exact: true }),
    ).toHaveCount(0)

    // 다른 경로로 가도 완료 화면만 그린다.
    await page.goto('/dashboard')
    await expect(completionHeading).toBeVisible({ timeout: LANDING_TIMEOUT_MS })

    // 5. 로그아웃 뒤 같은 로그인 수단으로 다시 로그인하면 새 계정을 만들지 않고 재가입 차단 표지로 돌아온다.
    await page.getByRole('button', { name: authContent.signOut.label }).click()
    await page.waitForURL((url) => url.pathname === '/', { timeout: LANDING_TIMEOUT_MS })
    await page.getByRole('link', { name: authContent.signIn.labels.google }).click()
    await consent(page, subject)
    await page.waitForURL(
      (url) => url.pathname === '/' && url.searchParams.get('auth') === 'deleted',
      { timeout: LANDING_TIMEOUT_MS },
    )
    await expect(page.getByRole('status')).toHaveText(authContent.notices.deleted)
    // 세션을 열지 않았다 — 착지 화면이 아니라 진입 화면이다.
    await expect(page.getByRole('link', { name: authContent.signIn.labels.google })).toBeVisible()
  })
})
