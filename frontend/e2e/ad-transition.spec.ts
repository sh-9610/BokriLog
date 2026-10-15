import { expect, test, type Page, type Request } from '@playwright/test'

import { AD_CONSENT_PATH, AD_CONSENT_POLICY_VERSION } from '../src/content/adConsent'
import {
  AD_ELIGIBILITY_PATH,
  AD_TRANSITION_CLAIM_PATH,
  AD_TRANSITION_PATH,
} from '../src/content/adTransition'
import { authContent } from '../src/content/auth'
import { planConditionContent } from '../src/content/planConditions'
import { planResultContent } from '../src/content/planResult'
import { planSyncContent } from '../src/content/planSync'
import {
  collectAndClassify,
  connectAndSavePlan,
  consent,
  publishAndOpenResult,
  startConversionByKeyboard,
} from './support/firstPlan'

/**
 * 금융정보 없는 중립 전환 화면의 실제 Chromium 검증(Story 4.3).
 *
 * 백엔드와 dev-oidc 스텁이 함께 떠 있는 주입 origin에서만 돈다. 이 스택은 기본 구성이다 — 광고 공급자가 꺼져 있다
 * (`BOKRILOG_AD_PROVIDER_ENABLED`·`BOKRILOG_AD_UNIT_PATH`를 주지 않는다). 그래서 GPT는 어느 경우에도 load되지 않고, 두 시험
 * 모두 브라우저의 요청이 동일 출처뿐이며(제3자 요청 0건) 쿠키·`localStorage`·`sessionStorage`·IndexedDB에 쓰지 않는다.
 * 실제 광고 소재·request field 검사는 출시 게이트 G-4이고 이 시험이 대신하지 않는다.
 */

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

/** 실행을 가르는 짧은 표지. */
function letters(): string {
  return Array.from({ length: 8 }, () =>
    String.fromCharCode(97 + Math.floor(Math.random() * 26)),
  ).join('')
}

/** 참조 모양(43자 base64url)의 위조 값. 리터럴로 쓰지 않는다(gitleaks) — 실행마다 만든다. */
function forgedReference(): string {
  return `${letters().repeat(5)}${letters().slice(0, 3)}`
}

/** 이 페이지가 보낸 모든 요청을 모은다. */
function recordRequests(page: Page): Request[] {
  const requests: Request[] = []
  page.on('request', (request) => {
    requests.push(request)
  })
  return requests
}

/** 동일 출처가 아닌 요청. `data:`·`blob:`은 요청이 아니다. */
function external(requests: readonly Request[], origin: string): string[] {
  return requests
    .map((request) => request.url())
    .filter((url) => {
      if (url.startsWith('data:') || url.startsWith('blob:')) return false
      try {
        return new URL(url).origin !== origin
      } catch {
        return true
      }
    })
}

function paths(requests: readonly Request[], method: string, path: string): Request[] {
  return requests.filter(
    (request) => request.method() === method && new URL(request.url()).pathname === path,
  )
}

/**
 * 앞 문서 요청 뒤, 뒤 문서 요청 앞에 보낸 요청들 — 그 사이에 열려 있던 문서가 보낸 것이다. 앞 문서보다 먼저 나간 요청(직전
 * 화면의 늦은 세션 확인 등)은 그 문서의 것이 아니므로 세지 않는다. 둘 중 하나가 없으면 단정이 비지 않도록 전부다.
 */
function requestsBetween(
  requests: readonly Request[],
  from: Request | undefined,
  to: Request | undefined,
): Request[] {
  const start = from === undefined ? -1 : requests.indexOf(from)
  const end = to === undefined ? -1 : requests.indexOf(to)
  return start < 0 || end < 0 ? [...requests] : requests.slice(start + 1, end)
}

async function storage(page: Page): Promise<Record<string, unknown>> {
  return page.evaluate(async () => ({
    local: window.localStorage.length,
    session: window.sessionStorage.length,
    cookie: document.cookie,
    databases: (await window.indexedDB.databases()).length,
  }))
}

const EMPTY_STORAGE = { local: 0, session: 0, cookie: '', databases: 0 }

async function signIn(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByRole('link', { name: authContent.signIn.labels.google }).click()
  await consent(page, `e2e-ad-transition-${letters()}`)
  await page.waitForURL((next) => next.pathname !== '/' && !next.pathname.startsWith('/api/'))
}

/** 지금 정책 버전으로 광고에 동의한다 — 공급자 꺼짐이 건너뜀의 사유가 되게 한다(동의가 첫 관문이다). */
async function grantAdConsent(page: Page): Promise<void> {
  const status = await page.evaluate(
    async ({ consentPath, policyVersion }) => {
      const session = (await (
        await fetch('/api/auth/session', { credentials: 'same-origin', cache: 'no-store' })
      ).json()) as { csrfToken: string }
      const answer = await fetch(consentPath, {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': session.csrfToken,
          'Idempotency-Key': crypto.randomUUID(),
        },
        body: JSON.stringify({ choice: 'GRANTED', policyVersion }),
      })
      return answer.status
    },
    { consentPath: AD_CONSENT_PATH, policyVersion: AD_CONSENT_POLICY_VERSION },
  )
  expect(status).toBe(200)
}

test.describe('중립 전환 화면 · 주입 origin', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 주입된 origin에서만 성립하는 시험이다. 파일째 사라지는 것보다 보고서에 "건너뜀"으로 남는 편이 낫다.
  test.skip(
    injectedOrigin === '',
    'PLAYWRIGHT_BASE_URL로 Nginx origin과 dev-oidc 스텁을 주입한 실행에서만 의미가 있다.',
  )

  test('참조 없음은 요청 없이, 위조 참조는 소비 404 뒤에 결과로 가고 GPT를 load하지 않는다', async ({
    page,
  }) => {
    await signIn(page)
    const origin = new URL(page.url()).origin
    // 로그인 뒤 착지한 화면(세션 확인·대시보드·첫 스냅샷 조회)의 늦은 요청 이벤트는 다음 문서 요청보다 뒤에 기록될 수 있다.
    // 기록 전에 그 문서를 빈 문서로 완전히 내려 두어야 아래 셈이 중립 화면의 요청만 본다(PR #111 CI 두 번의 실패 원인).
    await page.goto('about:blank')
    const requests = recordRequests(page)

    // 참조 없음(새로고침·뒤로 가기 재진입): API 요청 없이(세션 확인도 없이) 결과 문서로 덮어쓴다. 결과 문서가 뜬 뒤의 요청은
    // 그 화면의 것이다.
    await page.goto(AD_TRANSITION_PATH)
    await expect.poll(() => paths(requests, 'GET', '/dashboard').length).toBe(1)
    const transitionDocument = paths(requests, 'GET', AD_TRANSITION_PATH)[0]
    const resultDocument = paths(requests, 'GET', '/dashboard')[0]
    expect(transitionDocument).toBeDefined()
    expect(resultDocument).toBeDefined()
    const onTransition = requestsBetween(requests, transitionDocument, resultDocument)
    expect(
      onTransition
        .map((request) => new URL(request.url()).pathname)
        .filter((path) => path.startsWith('/api/')),
    ).toEqual([])

    // 위조 참조: 주소의 참조를 지우고, 세션 확인 뒤 소비가 404로 거절되면 결과 문서로 간다.
    const claimed = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === AD_TRANSITION_CLAIM_PATH &&
        response.request().method() === 'POST',
    )
    await page.goto(`${AD_TRANSITION_PATH}?t=${forgedReference()}`)
    expect((await claimed).status()).toBe(404)
    await expect.poll(() => paths(requests, 'GET', '/dashboard').length).toBe(2)
    // 결과 문서 요청에 참조가 실리지 않는다.
    for (const request of paths(requests, 'GET', '/dashboard')) {
      expect(new URL(request.url()).search).toBe('')
    }
    await page.waitForURL((next) => next.pathname !== AD_TRANSITION_PATH)

    expect(external(requests, origin)).toEqual([])
    expect(await storage(page)).toEqual(EMPTY_STORAGE)
  })

  test('기본 구성(공급자 꺼짐)에서 수동 동기화가 성공하면 판정은 건너뜀이고 화면은 제자리에서 갱신된다', async ({
    page,
  }) => {
    // 첫 공식 결과까지 가는 길이 길다. 주입 origin의 직렬 실행 전체 한도 안에 머문다.
    test.setTimeout(100_000)
    await connectAndSavePlan(page, 'incremental', /1,[0-4]\d0\.00 USD/)
    await collectAndClassify(page, /입금 사건: \d+ · 출금 사건: 0 · 제외 사건: 0 · 검토 필요: 0/)
    const conversion = await startConversionByKeyboard(page)
    await expect(conversion.getByRole('status')).toContainText(
      planConditionContent.conversion.converted,
      { timeout: 15_000 },
    )
    await page.setViewportSize({ width: 1024, height: 900 })
    await publishAndOpenResult(page)
    await grantAdConsent(page)
    // 첫 tc 뒤에 입금이 하나 이상 생기게 기다린다(스텁이 10초마다 하나) — 수동 동기화가 새 공식 결과로 끝나게 한다.
    const firstTc = await page.evaluate(async () => {
      const answer = await fetch('/api/dashboard', {
        credentials: 'same-origin',
        cache: 'no-store',
      })
      return Date.parse(((await answer.json()) as { result: { basisAt: string } }).result.basisAt)
    })
    await expect
      .poll(() => Date.now(), { timeout: 25_000, intervals: [500] })
      .toBeGreaterThanOrEqual(firstTc + 11_000)

    const panel = page.getByRole('region', { name: planResultContent.heading })
    await expect(panel.getByRole('region', { name: planResultContent.currentHeading })).toBeVisible(
      { timeout: 15_000 },
    )
    const landing = new URL(page.url())
    const requests = recordRequests(page)
    const judged = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === AD_ELIGIBILITY_PATH &&
        response.request().method() === 'POST',
      { timeout: 45_000 },
    )

    const control = panel
      .getByRole('region', { name: planResultContent.trustHeading })
      .getByRole('button', { name: planSyncContent.action })
    await expect(control).toHaveAttribute('aria-disabled', 'false')
    await control.focus()
    await page.keyboard.press('Enter')

    // 수동 동기화가 새 공식 결과로 끝나면 화면이 판정을 한 번 묻고, 기본 구성의 답은 건너뜀이다.
    const answer = await judged
    expect(answer.status()).toBe(200)
    expect(await answer.json()).toMatchObject({
      outcome: 'SKIPPED',
      reason: 'PROVIDER_DISABLED',
      transition: null,
    })
    await expect(
      page.getByRole('heading', { level: 2, name: planResultContent.heading }),
    ).toBeFocused()

    // 이동이 없다 — 같은 문서에 머물고 중립 화면 문서를 요청하지 않는다. 소비 요청도 없다.
    await expect.poll(() => paths(requests, 'POST', AD_ELIGIBILITY_PATH).length).toBe(1)
    expect(new URL(page.url()).pathname).toBe(landing.pathname)
    expect(new URL(page.url()).search).toBe('')
    expect(paths(requests, 'GET', AD_TRANSITION_PATH)).toEqual([])
    expect(paths(requests, 'POST', AD_TRANSITION_CLAIM_PATH)).toEqual([])
    expect(external(requests, landing.origin)).toEqual([])
    expect(await storage(page)).toEqual(EMPTY_STORAGE)
  })
})
