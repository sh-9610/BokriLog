import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import { authContent, AUTHORIZE_PATH } from '../src/content/auth'

/**
 * 로그인 진입과 로그인 상태 경로의 실제 Chromium 검증.
 *
 * 두 환경에서 같은 본문이 돈다. 기본 `npm run test:e2e`는 `vite preview`가 산출물을 띄우고,
 * `PLAYWRIGHT_BASE_URL`이 주입된 실행은 Nginx 진입점을 본다. 두 경우 모두 `/api/auth/session`이
 * 미인증으로 답하거나(Nginx + 백엔드) JSON이 아닌 응답으로 실패하고(preview), 화면은 어느
 * 쪽에서도 인증 화면으로 되돌린다. 그 수렴이 이 시험이 고정하는 계약이다.
 *
 * 제공자 자격정보를 요구하지 않는다. 실제 Google 왕복은 Epic 1의 <b>실연동 미검증</b> 게이트가
 * 소유하며 이 시험이 대신하지 않는다.
 */

const FOCUSABLE_SELECTOR =
  'a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"])'

/**
 * Nginx 진입점이 주입된 실행인가.
 *
 * `same-origin-csp.spec.ts`와 같은 판정이다. 성공 왕복은 백엔드와 `dev-oidc` 스텁이 함께 떠 있는
 * origin에서만 성립하고, 기본 `npm run test:e2e`의 `vite preview`에는 `/api`가 없다.
 */
const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

/**
 * 개발용 OIDC 스텁의 동의 화면 카피.
 *
 * 백엔드 `DevOidcStubConfig`가 소유하는 문자열이다. 화면 카피 정본(`src/content/`)에 두지 않는
 * 이유는 이것이 제품 화면이 아니라 제공자 자리에 서는 개발 전용 표면이기 때문이다.
 */
const STUB_CONSENT = { heading: '개발용 로그인 스텁', link: '동의하고 계속하기' } as const

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

const NARROW_VIEWPORT = { width: 320, height: 640 } as const

/** 화면이 살아 있는지 확인하는 canary. body가 비면 대부분의 단정이 조용히 통과한다. */
async function expectAuthScreenMounted(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { level: 1, name: '복리 계획 비교' })).toBeVisible()
  await expect(page.getByRole('link', { name: authContent.signIn.googleLabel })).toBeVisible()
}

test.describe('로그인 진입', () => {
  test('Google 버튼이 서버 인증 시작 경로로 가는 링크다', async ({ page }) => {
    await page.goto('/')
    await expectAuthScreenMounted(page)

    const button = page.getByRole('link', { name: authContent.signIn.googleLabel })

    // 최상위 내비게이션이다. 새 창을 열지 않고 CSP를 넓히지 않는다.
    await expect(button).toHaveAttribute('href', AUTHORIZE_PATH)
    await expect(button).not.toHaveAttribute('target', /.*/)
    await expect(button).not.toHaveAttribute('rel', /.*/)
  })

  test('Google 버튼이 최소 44×44px이고 좁은 폭에서도 유지된다', async ({ page }) => {
    await page.setViewportSize(NARROW_VIEWPORT)
    await page.goto('/')
    await expectAuthScreenMounted(page)

    const box = await page.getByRole('link', { name: authContent.signIn.googleLabel }).boundingBox()

    expect(box).not.toBeNull()
    expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
  })

  test('Google 버튼이 전역 포커스 링을 받고 Tab 순서가 문서 순서와 같다', async ({ page }) => {
    await page.goto('/')
    await expectAuthScreenMounted(page)

    const documentOrder = await page.evaluate(
      (selector) =>
        Array.from(document.querySelectorAll(selector)).map((element) =>
          element.textContent.trim(),
        ),
      FOCUSABLE_SELECTOR,
    )

    // 건너뛰기 링크 하나였던 목록에 로그인 버튼이 더해졌다.
    expect(documentOrder).toContain(authContent.signIn.googleLabel)

    const reached: string[] = []
    while (reached.length < documentOrder.length) {
      await page.keyboard.press('Tab')
      reached.push(await page.evaluate(() => document.activeElement?.textContent.trim() ?? ''))
    }
    expect(reached).toEqual(documentOrder)

    // 버튼 자신의 포커스 표시. DESIGN.md Components 마지막 문단의 focus-ring 값이다.
    const focus = await page.evaluate((label) => {
      const button = Array.from(document.querySelectorAll<HTMLAnchorElement>('a[href]')).find(
        (element) => element.textContent.trim() === label,
      )
      if (button === undefined) {
        return null
      }
      button.focus()
      const style = getComputedStyle(button)
      return {
        outlineStyle: style.outlineStyle,
        outlineWidth: style.outlineWidth,
        outlineOffset: style.outlineOffset,
        outlineColor: style.outlineColor,
      }
    }, authContent.signIn.googleLabel)

    expect(focus).not.toBeNull()
    expect(focus?.outlineStyle).toBe('solid')
    expect(focus?.outlineWidth).toBe('2px')
    expect(focus?.outlineOffset).toBe('2px')
    expect(focus?.outlineColor).toBe('rgb(29, 79, 145)')
  })

  test('로그인 진입을 더해도 `/`가 브라우저 저장소에 아무것도 남기지 않는다', async ({ page }) => {
    await page.goto('/')
    // eslint-disable-next-line playwright/no-networkidle -- "요청 0건" 단정은 네트워크가 잠잠해진 뒤에만 의미가 있다. 이 화면은 외부 요청이 없어 idle 대기가 흔들리지 않는다.
    await page.waitForLoadState('networkidle')
    await expectAuthScreenMounted(page)

    const stored = await page.evaluate(async () => ({
      localStorage: window.localStorage.length,
      sessionStorage: window.sessionStorage.length,
      cookie: document.cookie,
      indexedDb: (await window.indexedDB.databases()).length,
    }))

    // 세션 쿠키조차 없다. 인증 화면은 서버 세션을 만들지 않는다.
    expect(stored).toEqual({ localStorage: 0, sessionStorage: 0, cookie: '', indexedDb: 0 })
  })
})

test.describe('실패 표지', () => {
  test('취소 표지는 재시도 경로를 안내한다', async ({ page }) => {
    await page.goto('/?auth=cancelled')
    await expectAuthScreenMounted(page)

    const status = page.getByRole('status')

    await expect(status).toHaveText(authContent.notices.cancelled)
    // 계정의 존재 여부를 드러내지 않는다.
    await expect(status).not.toContainText('계정')
  })

  test('실패 표지도 원인을 드러내지 않는다', async ({ page }) => {
    await page.goto('/?auth=failed')
    await expectAuthScreenMounted(page)

    await expect(page.getByRole('status')).toHaveText(authContent.notices.failed)
  })

  test('닫힌 집합 밖의 표지는 화면에 문장을 만들지 않는다', async ({ page }) => {
    await page.goto('/?auth=invalid_id_token')
    await expectAuthScreenMounted(page)

    // 서버가 정한 두 값만 안내가 된다. 그 밖의 값으로 원인을 노출할 통로를 열지 않는다.
    await expect(page.getByRole('status')).toHaveText('')
    await expect(page.locator('body')).not.toContainText('invalid_id_token')
  })
})

test.describe('로그인 상태 경로 · 미인증', () => {
  for (const path of ['/onboarding', '/dashboard']) {
    test(`${path}는 문서를 받지만 인증 화면으로 되돌린다`, async ({ page }) => {
      const response = await page.goto(path)

      // 문서 자체는 200이다. 로그인 상태를 문서 상태 코드로 감추지 않는다.
      expect(response?.status()).toBe(200)
      await expectAuthScreenMounted(page)
      await expect(page.getByRole('status')).toHaveText(authContent.notices.signedOut)

      /*
       * 사용자 데이터 0건.
       *
       * 제목은 역할로 센다. 본문 문자열 포함으로 보면 안내 문장("로그인 상태가 아닙니다")이
       * 대시보드 제목("로그인 상태")을 부분 문자열로 담아 이 단정이 늘 실패한다. 확인하려는 것은
       * 그 화면의 섹션이 렌더됐는지이므로 제목의 역할과 고유한 본문 문장을 본다.
       */
      await expect(page.getByRole('heading', { name: authContent.onboarding.heading })).toHaveCount(
        0,
      )
      await expect(page.getByRole('heading', { name: authContent.dashboard.heading })).toHaveCount(
        0,
      )

      const text = await page.locator('body').innerText()
      for (const item of [...authContent.onboarding.items, ...authContent.dashboard.items]) {
        expect(text).not.toContain(item)
      }
    })
  }

  for (const path of ['/onboarding', '/dashboard', '/?auth=cancelled', '/?auth=failed']) {
    test(`${path}의 미인증 화면 변형도 WCAG 2.2 AA 자동 검사 위반이 0건이다`, async ({ page }) => {
      // 이 스토리가 만든 화면 변형은 다섯이다 — 진입, 실패 표지 둘, 로그인 상태 경로의 미인증
      // 복귀 둘. 하나만 검사하면 나머지에 들어간 마크업은 아무도 보지 않는다.
      await page.goto(path)
      await expectAuthScreenMounted(page)

      const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()

      expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual(
        [],
      )
    })
  }

  test('로그인 상태 경로에서 세션 확인은 동일 출처 요청 하나뿐이다', async ({ page, baseURL }) => {
    const requests: { url: string; resourceType: string }[] = []
    page.on('request', (request) => {
      requests.push({ url: request.url(), resourceType: request.resourceType() })
    })

    await page.goto('/dashboard')
    await expectAuthScreenMounted(page)

    const origin = new URL(baseURL ?? page.url()).origin
    const external = requests.filter((request) => {
      if (request.url.startsWith('data:') || request.url.startsWith('blob:')) {
        return false
      }
      try {
        return new URL(request.url).origin !== origin
      } catch {
        return true
      }
    })

    // 외부 폰트·CDN·분석 요청은 없다. 세션 확인도 동일 출처다.
    expect(external).toEqual([])
    const dataRequests = requests.filter((request) =>
      ['xhr', 'fetch', 'websocket', 'eventsource'].includes(request.resourceType),
    )
    expect(dataRequests.map((request) => new URL(request.url).pathname)).toEqual([
      '/api/auth/session',
    ])
  })
})

/**
 * 성공 왕복.
 *
 * `epics.md`가 Story 1.4에 요구한 "브라우저 시험: … 성공"이다. 인가 요청은 브라우저가 보내고 code
 * 교환과 JWKS 조회는 백엔드가 보내므로 `page.route()`로는 대신할 수 없다. 그래서 제공자 자리에
 * 서는 것은 백엔드의 `dev-oidc` 스텁이고, 이 시험은 그 스텁이 함께 뜬 origin에서만 돈다.
 *
 * 실제 Google 왕복은 Epic 1의 <b>실연동 미검증</b> 게이트가 소유하며 이 시험이 대신하지 않는다.
 */
test.describe('스텁 제공자 성공 왕복', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 주입된 origin에서만 성립하는 시험이다. 파일째 사라지는 것보다 보고서에 "건너뜀"으로 남는 편이 낫다.
  test.skip(
    injectedOrigin === '',
    'PLAYWRIGHT_BASE_URL로 Nginx origin과 dev-oidc 스텁을 주입한 실행에서만 의미가 있다.',
  )

  test('버튼 → 동의 → 온보딩까지 도달하고 세션 쿠키만 남는다', async ({ page, context }) => {
    await page.goto('/')
    await expectAuthScreenMounted(page)

    // 최상위 내비게이션이다. 새 창도 iframe도 form POST도 없다.
    await page.getByRole('link', { name: authContent.signIn.googleLabel }).click()

    // 제공자의 동의 화면 자리. 사용자가 실제로 이 단계를 지나야 성공 경로를 검증한 것이 된다.
    await expect(page.getByRole('heading', { level: 1, name: STUB_CONSENT.heading })).toBeVisible()
    await page.getByRole('link', { name: STUB_CONSENT.link }).click()

    // 미연결 식별자는 신규 가입이므로 목적지는 온보딩이다.
    await expect(page.getByRole('heading', { name: authContent.onboarding.heading })).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/onboarding')
    // 인증 화면으로 되돌리지 않았다. 세션 확인이 인증됐다고 답했다는 뜻이다.
    await expect(page.getByRole('status')).not.toHaveText(authContent.notices.signedOut)

    const session = (await context.cookies()).find((cookie) => cookie.name === 'BOKRILOG_SESSION')

    // 브라우저에 남는 것은 이 불투명 쿠키 하나뿐이고 속성 세 가지가 계약이다.
    expect(session).toBeDefined()
    expect(session?.httpOnly).toBe(true)
    expect(session?.secure).toBe(true)
    expect(session?.sameSite).toBe('Lax')
    expect(session?.path).toBe('/')

    const stored = await page.evaluate(async () => ({
      localStorage: window.localStorage.length,
      sessionStorage: window.sessionStorage.length,
      // HttpOnly 쿠키는 여기 보이지 않는다. 스크립트가 세션을 들고 나가는 경로가 없다.
      cookie: document.cookie,
      indexedDb: (await window.indexedDB.databases()).length,
    }))

    expect(stored).toEqual({ localStorage: 0, sessionStorage: 0, cookie: '', indexedDb: 0 })
  })

  test('로그인된 온보딩 화면도 건너뛰기 링크와 WCAG 2.2 AA를 지킨다', async ({ page }) => {
    /*
     * 인증된 화면 변형은 여기서만 도달한다. 미인증 복귀 화면은 진입 화면을 렌더하므로 그 검사가
     * 온보딩·로그인 상태 화면의 마크업을 한 번도 보지 않는다 — 두 화면의 본문·건너뛰기 링크·
     * live region이 검사 대상이 되는 것은 이 왕복 뒤다.
     */
    await page.goto('/')
    await expectAuthScreenMounted(page)
    await page.getByRole('link', { name: authContent.signIn.googleLabel }).click()
    await page.getByRole('link', { name: STUB_CONSENT.link }).click()
    await expect(page.getByRole('heading', { name: authContent.onboarding.heading })).toBeVisible()

    // 건너뛰기 링크가 문서의 첫 focusable 요소이고 본문 랜드마크를 가리킨다.
    const skipLink = page.getByRole('link', { name: '본문으로 건너뛰기' })
    await expect(skipLink).toHaveAttribute('href', '#onboarding-main')
    await page.keyboard.press('Tab')
    await expect(skipLink).toBeFocused()

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()

    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  })
})
