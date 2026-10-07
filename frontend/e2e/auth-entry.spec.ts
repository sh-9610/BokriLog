import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import {
  authContent,
  authorizePath,
  SIGN_IN_PROVIDERS,
  type SignInProvider,
} from '../src/content/auth'
import { firstSnapshotContent } from '../src/content/firstSnapshot'
import { planResultContent } from '../src/content/planResult'

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

/**
 * 엔드포인트를 손으로 적은 제공자.
 *
 * Google을 뺀 나머지다. 그 제공자의 정본 주소는 `CommonOAuth2Provider`가 들고 오고 이 스토리들이
 * 적은 상수가 아니므로, "등록의 엔드포인트가 실수로 정본 주소로 되돌아간다"는 위험을 갖지 않는다.
 *
 * 목록을 다시 적지 않고 `SIGN_IN_PROVIDERS`에서 걸러 낸다. 손으로 적으면 다음 제공자가 들어오는
 * 날 이 배열만 옛 값으로 남고, 그때 그 제공자의 왕복은 붉게 나오지 않고 **없어진다**.
 */
const HAND_ASSEMBLED_PROVIDERS = SIGN_IN_PROVIDERS.filter((provider) => provider !== 'google')

/**
 * 화면이 살아 있는지 확인하는 canary. body가 비면 대부분의 단정이 조용히 통과한다.
 *
 * 세 버튼을 함께 본다. 하나만 보면 나머지 제공자의 버튼이 사라져도 이 층의 모든 시험이 통과한다.
 */
async function expectAuthScreenMounted(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { level: 1, name: '복리 계획 비교' })).toBeVisible()
  for (const provider of SIGN_IN_PROVIDERS) {
    await expect(
      page.getByRole('link', { name: authContent.signIn.labels[provider] }),
    ).toBeVisible()
  }
}

/**
 * 스텁 제공자를 지나 온보딩까지 로그인한다.
 *
 * 아래 `스텁 제공자 성공 왕복` 절의 시험들이 이 상태에서 시작한다. 왕복을 각 시험에 복사하면
 * 단계 하나가 갈라져도 아무도 모른다. 스텁의 `subject`는 요청마다 새로 만들어지므로 이 호출은
 * 늘 신규 가입이고 목적지는 온보딩이다(`DevOidcStub`).
 */
async function signInThroughStub(page: Page, provider: SignInProvider = 'google'): Promise<void> {
  await page.goto('/')
  await expectAuthScreenMounted(page)
  await page.getByRole('link', { name: authContent.signIn.labels[provider] }).click()
  await page.getByRole('link', { name: STUB_CONSENT.link }).click()
  await expect(page.getByRole('heading', { name: authContent.onboarding.heading })).toBeVisible()
  /*
   * The landing screen settles in two steps (Story 2.8): the dashboard answers first, and only then —
   * with no plan to show — does the first-snapshot panel mount and read its own state (a new account
   * has no connection). Waiting for both keeps a test that counts requests from here on counting only
   * what its own action causes.
   */
  await expect(page.getByRole('status')).toHaveText(planResultContent.status.noPlan)
  await expect(page.getByText(firstSnapshotContent.unconnected)).toBeVisible()
}

/**
 * 스텁 동의 화면에서 `subject`를 고정한 채 동의한다.
 *
 * 스텁은 기본적으로 요청마다 새 `subject`를 만든다(`DevOidcStub`). 그래서 같은 제공자로 두 번
 * 왕복하면 두 번째는 **다른 사람**이고, 재인증은 "이 식별자가 현재 사용자의 것인가"를 보므로
 * 조용히 실패한다 — 그 실패는 붙들어 둔 목적지로 돌아가는 모양이 성공과 같아서 화면만 보면
 * 구분되지 않는다.
 *
 * 고정 수단은 스텁의 인가 요청 파라미터 하나이며(`dev_subject`) 검증 스크립트가 쓰는 것과 같다.
 * 여기서는 동의 화면에 도착한 뒤 그 주소에 파라미터를 더해 다시 연다 — 스텁의 인가 화면은 상태를
 * 만들지 않으므로 다시 여는 것이 안전하고, 우리 세션의 `state`·PKCE는 그대로 남는다.
 *
 * 제품 코드는 이 파라미터를 알지 못한다. 붙이는 대상은 제공자 자리에 선 개발 전용 표면이다.
 */
async function consentWithSubject(page: Page, subject: string): Promise<void> {
  // 동의 화면에 도착할 때까지 기다린다. 그 전에 주소를 읽으면 우리 인가 시작 경로가 잡힌다.
  await expect(page.getByRole('link', { name: STUB_CONSENT.link })).toBeVisible()
  const url = new URL(page.url())
  url.searchParams.set('dev_subject', subject)
  await page.goto(url.toString())
  await page.getByRole('link', { name: STUB_CONSENT.link }).click()
}

test.describe('로그인 진입', () => {
  test('세 제공자 버튼이 각자의 서버 인증 시작 경로로 가는 링크다', async ({ page }) => {
    await page.goto('/')
    await expectAuthScreenMounted(page)

    for (const provider of SIGN_IN_PROVIDERS) {
      const button = page.getByRole('link', { name: authContent.signIn.labels[provider] })

      // 최상위 내비게이션이다. 새 창을 열지 않고 CSP를 넓히지 않는다.
      await expect(button).toHaveAttribute('href', authorizePath(provider))
      await expect(button).not.toHaveAttribute('target', /.*/)
      await expect(button).not.toHaveAttribute('rel', /.*/)
    }
  })

  test('제공자 순서가 Google → 네이버 → 카카오로 고정된다', async ({ page }) => {
    await page.goto('/')
    await expectAuthScreenMounted(page)

    // 문서 순서가 곧 고정 순서다(DESIGN.md). 추천 표지도 마지막 사용 강조도 없다.
    const labels = await page
      .locator('a[href^="/api/auth/authorize/"]')
      .evaluateAll((elements) => elements.map((element) => element.textContent.trim()))

    expect(labels).toEqual(SIGN_IN_PROVIDERS.map((provider) => authContent.signIn.labels[provider]))
  })

  test('세 버튼이 최소 44×44px이고 바깥 크기가 서로 같다', async ({ page }) => {
    await page.setViewportSize(NARROW_VIEWPORT)
    await page.goto('/')
    await expectAuthScreenMounted(page)

    const boxes: { width: number; height: number }[] = []
    for (const provider of SIGN_IN_PROVIDERS) {
      const box = await page
        .getByRole('link', { name: authContent.signIn.labels[provider] })
        .boundingBox()

      expect(box).not.toBeNull()
      expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
      boxes.push({ width: box?.width ?? 0, height: box?.height ?? 0 })
    }

    /*
     * 동등한 위계는 **바깥 크기**로 성립한다(DESIGN.md AC-3). 전체 폭 세로 목록이므로 세 버튼의
     * 폭이 같아야 하고, 높이가 갈리면 한 제공자가 시각적으로 추천처럼 보인다.
     *
     * 타이포그래피의 동일성은 그 계약이 아니다. 네이버 레이블만 20px·700인 것은 브랜드 취향이
     * 아니라 공표 색 조합의 대비가 3.095:1이라 큰 텍스트 문턱을 넘어야 하기 때문이고, 카카오는
     * 12.52:1이라 그 확대가 필요 없다. 그래서 셋 중 네이버만 레이블이 크되 바깥 크기는 같다 —
     * 이 단정이 그 사실을 고정하므로 다음 사람이 "네이버만 커 보인다"를 회귀로 착각하지 않는다.
     */
    for (const box of boxes.slice(1)) {
      expect(box.width).toBe(boxes[0]?.width)
      expect(box.height).toBe(boxes[0]?.height)
    }

    /*
     * 추천 표지가 없다. 어느 버튼도 나머지와 다른 표식을 달지 않는다.
     *
     * 마크업의 **모양**을 잰다. 약속된 속성 이름(`data-recommended` 같은 것)을 찾는 단정은 이
     * 화면에 그런 속성이 애초에 없으므로 언제나 통과하고, 실제 추천 표지가 들어오는 모양 — 배지
     * 형제 요소, 강조 클래스를 단 세 번째 자식, 목록 안의 구분선 — 은 하나도 걸리지 않는다.
     * 목록의 자식 수와 버튼 안쪽의 자식 구성은 그 셋 모두에서 달라진다.
     */
    const shape = await page.locator('a[href^="/api/auth/authorize/"]').evaluateAll((elements) => ({
      // 목록에는 버튼만 있다. 배지·안내 문구·구분선이 하나라도 끼면 이 수가 달라진다.
      listChildren: elements[0]?.parentElement?.children.length ?? 0,
      // 버튼 안쪽도 마크 하나와 레이블 하나뿐이다. 강조 표지는 대개 세 번째 자식으로 들어온다.
      childTags: elements.map((element) =>
        Array.from(element.children).map((child) => child.tagName.toLowerCase()),
      ),
    }))

    expect(shape.listChildren).toBe(SIGN_IN_PROVIDERS.length)
    expect(shape.childTags).toEqual(SIGN_IN_PROVIDERS.map(() => ['svg', 'span']))
  })

  test('카카오 버튼은 공표된 색 둘과 모서리 12px을 그대로 쓴다', async ({ page }) => {
    /*
     * 카카오 가이드가 본문으로 공표한 값은 넷이다 — 컨테이너 #FEE500, 심볼 #000000, 레이블
     * #000000 85% 불투명도, 컨테이너 모서리 반경 12px. 그중 색 둘과 반경을 여기서 이름으로
     * 고정한다(레이블 색은 합성값이라 아래에서 따로 본다).
     *
     * 반경이 이 단정에 있는 이유는 그것이 카카오와 네이버를 가르는 축이기 때문이다. 네이버는
     * 반경을 공표하지 않아 BokriLog의 `--radius-md`(10px)를 쓰고 카카오는 공표해 12px을 쓴다.
     * 두 값이 같아지는 회귀는 "토큰을 하나로 합쳤다"는 좋은 의도로 일어나며 axe도 크기 단정도
     * 그것을 보지 않는다.
     *
     * 심볼의 비율도 함께 본다. 가이드가 금지하는 첫 항목이 심볼의 형태·비율 변경이고, 공식
     * 애셋에서 추출한 잉크의 가로세로비는 25.39 : 24다. CSS가 두 축을 같은 값으로 묶으면 렌더된
     * 잉크가 그 비율을 잃거나(늘어남) 선언한 크기보다 작아진다(여백).
     */
    await page.setViewportSize(NARROW_VIEWPORT)
    await page.goto('/')
    await expectAuthScreenMounted(page)

    const measured = await page
      .getByRole('link', { name: authContent.signIn.labels.kakao })
      .evaluate((element) => {
        const label = element.querySelector('span')
        const symbol = element.querySelector('svg')
        const ink = symbol?.querySelector('path') ?? null
        if (label === null || symbol === null || ink === null) {
          return null
        }
        const buttonStyle = getComputedStyle(element)
        const labelStyle = getComputedStyle(label)
        const range = document.createRange()
        range.selectNodeContents(label)
        const box = symbol.getBoundingClientRect()
        const viewBox = symbol.viewBox.baseVal
        const scale = Math.min(box.width / viewBox.width, box.height / viewBox.height)
        const bbox = ink.getBBox()

        /*
         * 심볼 토큰의 값을 **색으로** 읽는다.
         *
         * 문자열 그대로 비교할 수 없다. 산출물에서는 minifier가 `#000000`을 `#000`으로 줄이므로
         * 개발 소스와 빌드 결과가 다른 문자열을 주고, 이 시험은 두 환경에서 같은 본문이 돌아야
         * 한다. 그래서 표기가 아니라 CSS 엔진이 해석한 색을 본다.
         *
         * 토큰이 사라지면 `color`가 빈 값이 되어 버튼의 레이블 색(85% 검정)을 물려받으므로 그
         * 경우도 여기서 갈린다 — 그것이 `fill` 계산값으로는 볼 수 없는 축이다.
         */
        const probe = document.createElement('span')
        probe.style.color = buttonStyle.getPropertyValue('--component-social-sign-in-kakao-logo')
        element.append(probe)
        const symbolToken = getComputedStyle(probe).color
        probe.remove()

        return {
          background: buttonStyle.backgroundColor,
          labelColor: labelStyle.color,
          fontSize: parseFloat(labelStyle.fontSize),
          fontWeight: parseFloat(labelStyle.fontWeight),
          labelTokenFontSize: buttonStyle.getPropertyValue('--type-label-font-size').trim(),
          labelTokenFontWeight: buttonStyle.getPropertyValue('--type-label-font-weight').trim(),
          radius: buttonStyle.borderTopLeftRadius,
          symbolFill: getComputedStyle(ink).fill,
          symbolFillReference: ink.getAttribute('fill') ?? '',
          symbolToken,
          inkWidth: bbox.width * scale,
          inkHeight: bbox.height * scale,
          boxWidth: box.width,
          boxHeight: box.height,
          lineBoxes: range.getClientRects().length,
        }
      })

    // eslint-disable-next-line playwright/no-conditional-in-test -- 타입 좁히기 가드. 구조가 다르면 단정이 아니라 예외로 즉시 종료한다.
    if (measured === null) {
      throw new Error('카카오 버튼에서 레이블·심볼을 찾지 못했습니다.')
    }

    // 지정 팔레트 밖의 색을 쓰지 않는다(카카오 공식 가이드). #FEE500 · 심볼 #000000.
    expect(measured.background).toBe('rgb(254, 229, 0)')
    /*
     * 심볼 색은 사슬 전체를 본다 — 경로가 토큰을 참조하고, 그 토큰이 공표값을 들고 있고, 렌더가
     * 그 값으로 해석된다.
     *
     * 계산된 `fill`만 보면 이 단정은 실패할 수 없다. SVG의 `fill` 기본값이 검정이라 토큰 이름이
     * 바뀌거나 지워지거나 `var()` 참조에 오타가 나도 심볼은 그대로 검정으로 그려지고 값이 같다.
     * 네이버 쪽 같은 단정이 뜻을 갖는 것은 `#FFFFFF`가 기본값이 아니어서다.
     */
    expect(measured.symbolFillReference).toBe('var(--component-social-sign-in-kakao-logo)')
    expect(measured.symbolToken).toBe('rgb(0, 0, 0)')
    expect(measured.symbolFill).toBe('rgb(0, 0, 0)')
    // 레이블은 #000000 85% 불투명도다. 합성값이 아니라 알파를 그대로 들고 있어야 한다.
    expect(measured.labelColor).toBe('rgba(0, 0, 0, 0.85)')
    // 컨테이너 모서리 반경 12px(공표값). 네이버의 10px과 다른 값이어야 한다.
    expect(measured.radius).toBe('12px')

    /*
     * 레이블은 base type 그대로다. 대비가 12.52:1이라 일반 텍스트 하한 4.5:1을 넘으므로 네이버가
     * 치른 확대를 되풀 이유가 없고, 하면 공표되지 않은 값을 지어내는 것이 된다.
     *
     * 값을 리터럴로 적지 않고 토큰과 비교한다. 14px·600은 카카오가 공표한 값이 아니라 BokriLog의
     * `--type-label-*`이고, 그 토큰은 언제든 바뀔 수 있다 — 리터럴로 고정하면 전역 타이포그래피
     * 조정이 **카카오 브랜드 시험**을 엉뚱한 이유로 붉게 만든다. 여기서 지키려는 것은 "이 버튼이
     * 자기 크기를 지어내지 않고 base type을 그대로 쓴다"이고, 그것은 토큰과의 동일성이다.
     */
    expect(`${String(measured.fontSize)}px`).toBe(measured.labelTokenFontSize)
    expect(String(measured.fontWeight)).toBe(measured.labelTokenFontWeight)

    // 심볼의 비율. 잉크가 요소 박스를 두 축 모두 채우고 그 비율이 공식 애셋과 같다.
    expect(measured.inkWidth).toBeCloseTo(measured.boxWidth, 1)
    expect(measured.inkHeight).toBeCloseTo(measured.boxHeight, 1)
    expect(measured.inkWidth / measured.inkHeight).toBeCloseTo(25.39 / 24, 2)

    // 320px에서도 한 줄이다.
    expect(measured.lineBoxes).toBe(1)
  })

  test('네이버 버튼은 공표된 색을 그대로 쓰고 레이블 크기로 대비 하한을 만족한다', async ({
    page,
  }) => {
    /*
     * 이 화면의 대비 결과는 위·아래 axe 시험들이 이미 본다. 그런데 axe 위반은 "왜 그 값이어야
     * 하는가"를 말해 주지 않으므로, 그 통과를 지탱하는 사실 셋을 여기서 이름으로 고정한다.
     *
     * 네이버가 공표한 조합(#03A94D 배경 + 흰 레이블)의 대비는 3.095:1이다. 일반 텍스트 하한
     * 4.5:1에는 못 미치고 큰 텍스트 하한 3:1은 넘으므로, 색을 바꿀 수 없는 이 버튼이 통과하는
     * 유일한 길은 레이블을 큰 텍스트로 만드는 것이다. 큰 텍스트 판정은 24px 이상이거나
     * 18.67px 이상이면서 굵기 700 이상이고 굵기 600은 세지 않는다(axe `color-contrast`의
     * `boldValue: 700`·`boldTextPt: 14`·`largeTextPt: 18`).
     *
     * 그래서 색을 바꾸는 회귀와 크기·굵기를 되돌리는 회귀가 둘 다 여기서 먼저 걸린다.
     *
     * 로고 높이는 `<svg>` **요소 박스**가 아니라 **렌더된 잉크**를 잰다. 요소 박스는 CSS 크기
     * 하나로 정해지므로 경로가 박스의 절반만 채워도 24px로 읽히고, 그때 "텍스트는 로고 높이보다
     * 작은 크기"는 렌더 결과가 아니라 CSS 선언만 확인한 것이 된다. 실제로 손으로 그린 첫 경로가
     * 그 상태였다 — `viewBox="0 0 20 20"` 안에서 잉크가 10×9만 차지해 실측 잉크 높이는 10.8px,
     * 레이블 20px보다 **작았다**. 공식 애셋에서 추출한 경로는 bbox가 정사각형이라 잉크가 박스를
     * 가득 채운다. `getBBox()`와 `viewBox` 스케일이 그 사실을 재는 유일한 길이다.
     *
     * 로고 `fill`과 두 간격도 함께 본다. 이 셋 중 어느 것도 단정이 없으면 토큰 이름 오타 하나로
     * 로고가 검정으로 그려지거나 제공자 버튼들이 맞붙어도 전 층이 green이다.
     */
    await page.setViewportSize(NARROW_VIEWPORT)
    await page.goto('/')
    await expectAuthScreenMounted(page)

    const measured = await page
      .getByRole('link', { name: authContent.signIn.labels.naver })
      .evaluate((element) => {
        const label = element.querySelector('span')
        const logo = element.querySelector('svg')
        const ink = logo?.querySelector('path') ?? null
        const list = element.parentElement
        if (label === null || logo === null || ink === null || list === null) {
          return null
        }
        const labelStyle = getComputedStyle(label)
        const range = document.createRange()
        range.selectNodeContents(label)

        /*
         * 렌더된 잉크 높이. `preserveAspectRatio`의 기본값(`xMidYMid meet`)에서 스케일은 두 축
         * 비율의 최솟값이며, 여기서는 정사각 viewBox와 정사각 CSS 크기라 두 값이 같다.
         */
        const viewBox = logo.viewBox.baseVal
        const logoBox = logo.getBoundingClientRect()
        const scale = Math.min(logoBox.width / viewBox.width, logoBox.height / viewBox.height)

        return {
          background: getComputedStyle(element).backgroundColor,
          labelColor: labelStyle.color,
          fontSize: parseFloat(labelStyle.fontSize),
          fontWeight: parseFloat(labelStyle.fontWeight),
          logoFill: getComputedStyle(ink).fill,
          logoBoxHeight: logoBox.height,
          inkHeight: ink.getBBox().height * scale,
          logoLabelGap: getComputedStyle(element).columnGap,
          providerListRowGap: getComputedStyle(list).rowGap,
          lineBoxes: range.getClientRects().length,
        }
      })

    // eslint-disable-next-line playwright/no-conditional-in-test -- 타입 좁히기 가드. 구조가 다르면 단정이 아니라 예외로 즉시 종료한다.
    if (measured === null) {
      throw new Error('네이버 버튼에서 레이블·로고·제공자 목록을 찾지 못했습니다.')
    }

    // 지정 컬러는 변경할 수 없다(네이버 공식 가이드). #03A94D · #FFFFFF · 로고 #FFFFFF.
    expect(measured.background).toBe('rgb(3, 169, 77)')
    expect(measured.labelColor).toBe('rgb(255, 255, 255)')
    expect(measured.logoFill).toBe('rgb(255, 255, 255)')

    // 큰 텍스트 판정. 24px 이상이거나, 18.67px 이상이면서 굵기 700 이상.
    expect(
      measured.fontSize >= 24 || (measured.fontSize >= 18.67 && measured.fontWeight >= 700),
    ).toBe(true)

    /*
     * "텍스트는 로고 높이보다 작은 크기를 사용해야 해요"(같은 가이드).
     *
     * 잉크 높이로 잰다. 공식 애셋의 bbox가 정사각형이라 잉크는 요소 박스를 가득 채우며, 부동
     * 소수 반올림 여유만 두고 두 값이 같은지도 함께 본다 — 어느 날 경로가 다시 박스보다 작게
     * 줄어들면 그 사실이 이 단정에서 먼저 붉게 나온다.
     */
    expect(measured.fontSize).toBeLessThan(measured.inkHeight)
    expect(measured.inkHeight).toBeCloseTo(measured.logoBoxHeight, 1)

    // 가운데 정렬 시 로고-레이블 간격 8px(공표값), 제공자 버튼 사이 간격 12px(BokriLog 토큰).
    expect(measured.logoLabelGap).toBe('8px')
    expect(measured.providerListRowGap).toBe('12px')

    // 320px에서도 한 줄이다. 두 줄로 접히면 로고보다 커진 레이블이 버튼 모양을 바꾼다.
    expect(measured.lineBoxes).toBe(1)
  })

  test('세 버튼이 전역 포커스 링을 받고 Tab 순서가 문서 순서와 같다', async ({ page }) => {
    await page.goto('/')
    await expectAuthScreenMounted(page)

    const documentOrder = await page.evaluate(
      (selector) =>
        Array.from(document.querySelectorAll(selector)).map((element) =>
          element.textContent.trim(),
        ),
      FOCUSABLE_SELECTOR,
    )

    // 건너뛰기 링크 하나였던 목록에 로그인 버튼 셋이 더해졌다.
    for (const provider of SIGN_IN_PROVIDERS) {
      expect(documentOrder).toContain(authContent.signIn.labels[provider])
    }

    const reached: string[] = []
    while (reached.length < documentOrder.length) {
      await page.keyboard.press('Tab')
      reached.push(await page.evaluate(() => document.activeElement?.textContent.trim() ?? ''))
    }
    expect(reached).toEqual(documentOrder)

    // 버튼 자신의 포커스 표시. DESIGN.md Components 마지막 문단의 focus-ring 값이다.
    // 세 버튼 모두 전역 `:focus-visible`을 쓰고 자기 포커스 규칙을 선언하지 않는다.
    for (const provider of SIGN_IN_PROVIDERS) {
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
      }, authContent.signIn.labels[provider])

      expect(focus).not.toBeNull()
      expect(focus?.outlineStyle).toBe('solid')
      expect(focus?.outlineWidth).toBe('2px')
      expect(focus?.outlineOffset).toBe('2px')
      expect(focus?.outlineColor).toBe('rgb(29, 79, 145)')
    }
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
  // 로그인 상태 경로 셋 전부를 돈다. Story 1.10이 `/settings`를 더했고, 그 경로도 문서는
  // 200이지만 미인증에서는 사용자 데이터를 한 글자도 그리지 않아야 한다.
  for (const path of ['/onboarding', '/dashboard', '/settings']) {
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

  for (const path of [
    '/onboarding',
    '/dashboard',
    '/settings',
    '/?auth=cancelled',
    '/?auth=failed',
  ]) {
    test(`${path}의 미인증 화면 변형도 WCAG 2.2 AA 자동 검사 위반이 0건이다`, async ({ page }) => {
      // 화면 변형 여섯이다 — 진입, 실패 표지 둘, 로그인 상태 경로 셋의 미인증 복귀.
      // 하나만 검사하면 나머지에 들어간 마크업은 아무도 보지 않는다.
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
    /*
     * 세션 확인 하나뿐이다. 로그아웃은 사용자가 버튼을 누를 때만 나가므로 이 목록에 없다 —
     * 그 요청이 더해지는 것은 아래 `로그아웃 왕복`이며 그 절이 자기 목록을 따로 고정한다.
     *
     * 가시성 재확인도 이 목록을 늘리지 않는다. 그 재확인은 인증을 관측한 화면만 듣고(이 경로는
     * 미인증으로 수렴한다) 가시성이 바뀔 때만 도는데, 문서를 처음 여는 것은 가시성 변화가 아니다.
     */
    expect(dataRequests.map((request) => new URL(request.url).pathname)).toEqual([
      '/api/auth/session',
    ])
  })

  test('미인증 복귀 화면은 로그아웃 버튼을 그리지 않는다', async ({ page }) => {
    await page.goto('/dashboard')
    await expectAuthScreenMounted(page)

    // 이 화면은 인증 화면이다. 끝낼 세션이 없으므로 로그아웃할 대상도 없다.
    await expect(page.getByRole('button', { name: authContent.signOut.label })).toHaveCount(0)
  })
})

test.describe('유일 제공자 접근 상실 안내', () => {
  test('실패 표지에 세 제공자의 공식 복구 경로가 붙는다', async ({ page }) => {
    await page.goto('/?auth=failed')
    await expectAuthScreenMounted(page)

    await expect(page.getByText(authContent.recovery.body)).toBeVisible()

    /*
     * 전부를 언제나 함께 보여 준다. 이 화면은 사용자가 어느 제공자로 가입했는지 알 수 없고
     * 알아서도 안 되므로 하나만 고를 수 없다.
     */
    for (const entry of authContent.recovery.links) {
      const link = page.getByRole('link', { name: entry.label })

      await expect(link).toBeVisible()
      await expect(link).toHaveAttribute('href', entry.href)
      // 기본으로 새 창을 열지 않고 `rel`로 참조 유출을 막는다.
      await expect(link).not.toHaveAttribute('target', /.*/)
      await expect(link).toHaveAttribute('rel', 'noreferrer')
    }

    // 이메일 확인·문의로 인증을 우회하는 경로를 만들지 않는다.
    await expect(page.getByText(authContent.recovery.caveat)).toBeVisible()
    await expect(page.getByRole('textbox')).toHaveCount(0)
  })

  test('취소 표지에는 복구 안내가 붙지 않는다', async ({ page }) => {
    await page.goto('/?auth=cancelled')
    await expectAuthScreenMounted(page)

    // 취소는 재시도하면 되는 경우다. 접근을 잃었다고 단정하지 않는다.
    await expect(page.getByText(authContent.recovery.body)).toHaveCount(0)
  })

  test('복구 안내 링크가 최소 44×44px이고 전역 포커스 링을 받는다', async ({ page }) => {
    await page.setViewportSize(NARROW_VIEWPORT)
    await page.goto('/?auth=failed')
    await expectAuthScreenMounted(page)

    for (const entry of authContent.recovery.links) {
      const link = page.getByRole('link', { name: entry.label })
      const box = await link.boundingBox()

      expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)

      const focus = await link.evaluate((element) => {
        element.focus()
        const style = getComputedStyle(element)
        return {
          outlineStyle: style.outlineStyle,
          outlineWidth: style.outlineWidth,
          outlineOffset: style.outlineOffset,
          outlineColor: style.outlineColor,
        }
      })

      expect(focus.outlineStyle).toBe('solid')
      expect(focus.outlineWidth).toBe('2px')
      expect(focus.outlineOffset).toBe('2px')
      expect(focus.outlineColor).toBe('rgb(29, 79, 145)')
    }
  })

  test('표지와 함께 열린 화면은 포커스를 페이지 제목으로 보낸다', async ({ page }) => {
    await page.goto('/?auth=loggedOut')
    await expectAuthScreenMounted(page)

    // 다른 화면에서의 전환 뒤다. 포커스가 문서 처음에 남아 있으면 무엇이 바뀌었는지 알 수 없다.
    const focusedText = await page.evaluate(() => document.activeElement?.textContent.trim() ?? '')

    expect(focusedText).toBe('복리 계획 비교')
    await expect(page.getByRole('status')).toHaveText(authContent.notices.loggedOut)
  })

  test('로그아웃·만료 표지 화면도 WCAG 2.2 AA 위반이 0건이다', async ({ page }) => {
    for (const path of ['/?auth=loggedOut', '/?auth=expired', '/?auth=failed']) {
      await page.goto(path)
      await expectAuthScreenMounted(page)

      const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()

      expect(
        results.violations.map((violation) => `${path} ${violation.id}: ${violation.help}`),
      ).toEqual([])
    }
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

  for (const provider of SIGN_IN_PROVIDERS) {
    test(`${provider} 버튼 → 동의 → 온보딩까지 도달하고 세션 쿠키만 남는다`, async ({
      page,
      context,
    }) => {
      /*
       * 세 제공자를 각각 지난다. 왕복 하나만 보면 나머지 제공자의 등록·콜백·세션 개설이 실제로
       * 성립하는지 아무 층도 확인하지 않는다.
       */
      await page.goto('/')
      await expectAuthScreenMounted(page)

      // 최상위 내비게이션이다. 새 창도 iframe도 form POST도 없다.
      await page.getByRole('link', { name: authContent.signIn.labels[provider] }).click()

      // 제공자의 동의 화면 자리. 사용자가 실제로 이 단계를 지나야 성공 경로를 검증한 것이 된다.
      await expect(
        page.getByRole('heading', { level: 1, name: STUB_CONSENT.heading }),
      ).toBeVisible()
      // 그 제공자의 스텁이 응답했다. 경로 세그먼트가 곧 어느 스텁인지의 답이다.
      expect(new URL(page.url()).pathname).toBe(`/api/dev-oidc/${provider}/authorize`)
      await page.getByRole('link', { name: STUB_CONSENT.link }).click()

      // 미연결 식별자는 신규 가입이므로 목적지는 온보딩이다.
      await expect(
        page.getByRole('heading', { name: authContent.onboarding.heading }),
      ).toBeVisible()
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
  }

  for (const provider of HAND_ASSEMBLED_PROVIDERS) {
    test(`${provider} 왕복도 외부 origin으로 나가지 않는다`, async ({ page, baseURL }) => {
      /*
       * 실제 제공자 주소로 나가는 요청이 0건이다. 개발·CI 스택의 등록은 스텁을 가리키므로 그
       * 왕복 전체가 동일 출처여야 하고, 등록의 엔드포인트가 실수로 정본 주소로 되돌아가면 여기서
       * 드러난다(그 경로는 CI에서 판정으로 삼을 수 없는 실연동이다).
       *
       * Google을 빼는 것은 그 제공자의 정본 주소가 `CommonOAuth2Provider`에서 오고 이 스토리들이
       * 손으로 적은 상수가 아니기 때문이다. 손으로 적은 엔드포인트를 가진 나머지가 이 위험을
       * 갖는다(`HAND_ASSEMBLED_PROVIDERS`).
       */
      const requests: string[] = []
      page.on('request', (request) => {
        requests.push(request.url())
      })

      await signInThroughStub(page, provider)

      const origin = new URL(baseURL ?? page.url()).origin
      const external = requests.filter((url) => {
        if (url.startsWith('data:') || url.startsWith('blob:')) {
          return false
        }
        try {
          return new URL(url).origin !== origin
        } catch {
          return true
        }
      })

      expect(external).toEqual([])
    })
  }

  test('로그인된 온보딩 화면도 건너뛰기 링크와 WCAG 2.2 AA를 지킨다', async ({ page }) => {
    /*
     * 인증된 화면 변형은 여기서만 도달한다. 미인증 복귀 화면은 진입 화면을 렌더하므로 그 검사가
     * 온보딩·로그인 상태 화면의 마크업을 한 번도 보지 않는다 — 두 화면의 본문·건너뛰기 링크·
     * live region이 검사 대상이 되는 것은 이 왕복 뒤다.
     */
    await signInThroughStub(page)

    // 건너뛰기 링크가 문서의 첫 focusable 요소이고 본문 랜드마크를 가리킨다.
    const skipLink = page.getByRole('link', { name: '본문으로 건너뛰기' })
    await expect(skipLink).toHaveAttribute('href', '#onboarding-main')
    await page.keyboard.press('Tab')
    await expect(skipLink).toBeFocused()

    /*
     * 설정 링크가 그 다음이고 로그아웃이 마지막이다. 반복되는 머리말을 지나치려는 사용자가 먼저
     * 만나는 것은 건너뛰기여야 하며, 그 뒤 순서는 문서 순서가 정한다.
     */
    await page.keyboard.press('Tab')
    await expect(page.getByRole('link', { name: authContent.settings.link })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(page.getByRole('button', { name: authContent.signOut.label })).toBeFocused()

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()

    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  })

  test('설정 화면이 게이트 안쪽에서 WCAG 2.2 AA와 목표 크기를 지킨다', async ({ page }) => {
    /*
     * 이 스토리가 더한 가장 큰 마크업 표면이다. e2e의 `/settings` 축은 미인증뿐이고 그 상태는
     * 진입 화면을 렌더하므로, 스텁 왕복으로 로그인한 뒤 열지 않으면 연결 목록·연결 버튼·본인 확인
     * 버튼·다시 확인 조작이 저장소의 접근성 검증 밖에 남는다.
     */
    await page.setViewportSize(NARROW_VIEWPORT)
    await signInThroughStub(page)

    await page.getByRole('link', { name: authContent.settings.link }).click()

    await expect(page.getByRole('heading', { name: authContent.settings.heading })).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/settings')

    // 방금 가입한 계정이므로 연결된 수단은 왕복이 쓴 제공자 하나뿐이다.
    await expect(page.getByText(authContent.settings.linked)).toBeVisible()

    /*
     * 조작 대상은 모두 최소 44×44px이다. 연결 버튼은 미연결 제공자에만, 본인 확인 버튼은 셋 다
     * 있으므로 화면에 있는 것을 역할과 이름으로 찾아 전부 잰다.
     */
    const targets = [
      ...SIGN_IN_PROVIDERS.map((provider) => authContent.settings.reauth.labels[provider]),
      authContent.settings.linkLabels.naver,
      authContent.settings.linkLabels.kakao,
      authContent.signOut.label,
    ]
    for (const name of targets) {
      const box = await page.getByRole('button', { name }).boundingBox()
      expect(box?.width ?? 0, name).toBeGreaterThanOrEqual(44)
      expect(box?.height ?? 0, name).toBeGreaterThanOrEqual(44)
    }

    // 320px에서 가로 스크롤이 생기지 않는다.
    const metrics = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)

    // 외부 식별자는 화면에 도달하지 않는다. 서버가 제공자 이름만 돌려주기 때문이다.
    const text = await page.locator('body').innerText()
    expect(text).not.toContain('http://')
    expect(text).not.toContain('sub=')

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()

    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  })

  test('해제 확인 대화상자가 WCAG 2.2 AA·목표 크기·키보드 계약을 지킨다', async ({ page }) => {
    /*
     * 이 스토리가 더한 가장 큰 마크업 표면이자, 기하와 포커스를 실제로 잴 수 있는 유일한 층이다.
     * jsdom은 레이아웃도 `Tab` 활성화도 구현하지 않으므로 단위 시험은 속성과 호출만 본다.
     *
     * 해제가 성립하려면 세 단계를 지나야 한다 — 로그인 → 본인 확인 → 두 번째 수단 연결. 마지막
     * 수단 보호 때문에 수단이 둘이어야 하고, 준비는 최근 본인 확인을 요구한다.
     */
    await page.setViewportSize(NARROW_VIEWPORT)
    /*
     * 같은 사람이 세 번 왕복한다 — 로그인, 본인 확인, 연결. 앞의 둘은 같은 Google 식별자여야 하고,
     * 스텁의 기본값은 요청마다 새 `subject`라 고정하지 않으면 본인 확인이 조용히 실패한다.
     *
     * 표지는 실행마다 다르다. 고정 문자열을 쓰면 앞선 실행이 남긴 계정에 붙어 "신규 가입"을
     * 전제한 아래 단정이 흔들린다.
     */
    const marker = `unlink-${Date.now().toString()}`
    await page.goto('/')
    await expectAuthScreenMounted(page)
    await page.getByRole('link', { name: authContent.signIn.labels.google }).click()
    await consentWithSubject(page, `google-${marker}`)
    await expect(page.getByRole('heading', { name: authContent.onboarding.heading })).toBeVisible()

    await page.getByRole('link', { name: authContent.settings.link }).click()
    await expect(page.getByRole('heading', { name: authContent.settings.heading })).toBeVisible()

    // 본인 확인. 복귀 목적지는 세션이 로그인 시각에 붙들어 둔 값이라 온보딩이다(Story 1.8).
    await page.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
    await consentWithSubject(page, `google-${marker}`)
    await expect(page.getByRole('heading', { name: authContent.onboarding.heading })).toBeVisible()
    /*
     * 성공한 재인증은 표지 없이 돌아온다. 이 단정이 없으면 실패한 재인증도 같은 화면으로
     * 수렴하므로(`?auth=reauthFailed`) 아래 연결 시작이 거부될 때까지 아무도 그것을 모른다.
     */
    expect(new URL(page.url()).search).toBe('')

    // 두 번째 수단 연결. 여기까지가 해제의 전제다.
    await page.getByRole('link', { name: authContent.settings.link }).click()
    await page.getByRole('button', { name: authContent.settings.linkLabels.naver }).click()
    await consentWithSubject(page, `naver-${marker}`)
    await expect(page.getByText(authContent.notices.linkSucceeded)).toBeVisible()
    await expect(page.getByText(authContent.settings.linked)).toHaveCount(2)
    // 수단이 둘이므로 마지막 수단 설명이 사라진다.
    await expect(page.getByText(authContent.settings.lastMethod)).toHaveCount(0)

    /*
     * 본인 확인 없이 해제 준비를 누르면 거절이다. 연결은 민감 command 하나이고 본인 확인 1회는
     * command 1건이다 — 연결 성공이 세션 회전과 함께 재인증 시각을 지웠으므로(`SessionRotation`)
     * 해제 준비는 403이고, 화면은 대화상자 대신 성공 문구가 예고한 그 거절을 말한다. 실제 서버가
     * 이 계약을 지키는지 보는 유일한 자리이며, 이 묶음의 다른 단계처럼 `PLAYWRIGHT_BASE_URL`로 주입한
     * origin에서만 돈다(자체 preview에는 서버가 없다).
     */
    await page.getByRole('button', { name: authContent.settings.unlinkLabels.naver }).click()
    await expect(page.getByRole('status')).toHaveText(authContent.notices.unlinkFailed)
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByText(authContent.settings.linked)).toHaveCount(2)

    /*
     * 본인 확인을 한 번 더. 해제 준비는 새 본인 확인 위에서만 허가를 받는다. 복귀 목적지는 여전히
     * 세션이 붙들어 둔 온보딩이다.
     */
    await page.getByRole('button', { name: authContent.settings.reauth.labels.google }).click()
    await consentWithSubject(page, `google-${marker}`)
    await expect(page.getByRole('heading', { name: authContent.onboarding.heading })).toBeVisible()
    expect(new URL(page.url()).search).toBe('')
    await page.getByRole('link', { name: authContent.settings.link }).click()
    await expect(page.getByText(authContent.settings.linked)).toHaveCount(2)

    const unlinkButton = page.getByRole('button', {
      name: authContent.settings.unlinkLabels.naver,
    })
    await unlinkButton.click()

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toHaveAccessibleName(authContent.settings.unlinkDialog.titles.naver)

    const cancel = dialog.getByRole('button', { name: authContent.settings.unlinkDialog.cancel })
    const confirm = dialog.getByRole('button', { name: authContent.settings.unlinkDialog.confirm })

    // 기본 포커스가 취소에 있다. 비가역 조작에서 `Enter` 한 번이 실행이 되면 안 된다.
    await expect(cancel).toBeFocused()

    // 두 조작 모두 최소 44×44px이다.
    for (const target of [cancel, confirm]) {
      const box = await target.boundingBox()
      expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)
    }

    // 320px에서 가로 스크롤이 생기지 않는다. 대화상자가 열린 상태에서도 그렇다.
    const metrics = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])

    // 포커스가 대화상자 안에 갇힌다. `Tab` 두 번이면 취소로 돌아온다.
    await page.keyboard.press('Tab')
    await expect(confirm).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(cancel).toBeFocused()

    /*
     * 겹을 눌러도 방어가 살아 있다. 겹은 포커스를 받을 수 없으므로 아무 방어도 없으면 이 클릭
     * 하나로 포커스가 `<body>`로 떨어지고, 그 뒤의 keydown은 React 루트에 닿지 않아 `Esc`와
     * 포커스 가둠이 함께 죽는다 — 실제 브라우저에서만 재현되는 경로다.
     *
     * 방어가 둘이고 둘 다 본다. 먼저 `onMouseDown`이 포커스를 잃는 것 자체를 막고,
     */
    await page.mouse.click(5, 5)
    await expect(cancel).toBeFocused()

    // 그래도 포커스를 잃는 경로가 남으면 `document` 처리기가 다음 `Tab`에 안으로 끌어온다.
    await page.evaluate(() => {
      const active = document.activeElement
      if (active instanceof HTMLElement) {
        active.blur()
      }
    })
    await page.keyboard.press('Tab')
    await expect(cancel).toBeFocused()

    // `Esc`가 닫고 포커스가 호출점으로 돌아간다. 아무것도 바뀌지 않았다는 사실을 문장이 말한다.
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(unlinkButton).toBeFocused()
    await expect(page.getByRole('status')).toHaveText(authContent.notices.unlinkCancelled)
    await expect(page.getByText(authContent.settings.linked)).toHaveCount(2)

    /*
     * 다시 열어 확인까지 지난다. 브라우저 층에서 해제 왕복 전체가 성립하는지 보는 유일한 자리다.
     *
     * 최종 확인 버튼은 대화상자 안에서 찾는다. "연결 해제"는 목록의 "<제공자> 계정 연결 해제"의
     * 부분 문자열이라 문서 전체에서 찾으면 셋에 걸린다.
     */
    await unlinkButton.click()
    const reopened = page.getByRole('dialog')
    await expect(reopened).toBeVisible()
    await reopened.getByRole('button', { name: authContent.settings.unlinkDialog.confirm }).click()

    const result = page.getByRole('status')
    await expect(result).toHaveText(authContent.notices.unlinkSucceeded)
    await expect(page.getByRole('dialog')).toHaveCount(0)
    /*
     * 포커스가 결과 문장으로 간다. 호출점 버튼은 목록이 갱신되며 사라지므로 거기로 돌려주면 포커스가
     * 잠시 뒤 `<body>`로 떨어진다 — jsdom은 포인터 조작으로 포커스를 옮기지 않아 이 층만 그것을 본다.
     */
    await expect(page.getByText(authContent.notices.unlinkSucceeded)).toBeFocused()
    // 목록을 다시 읽어 지금 상태를 그린다. 이제 남은 수단이 하나뿐이다.
    await expect(page.getByText(authContent.settings.linked)).toHaveCount(1)
    await expect(page.getByText(authContent.settings.lastMethod)).toBeVisible()
    // 마지막 수단이 된 행의 해제 조작은 비활성이고 이유를 자기 설명으로 가리킨다.
    const lastUnlink = page.getByRole('button', { name: authContent.settings.unlinkLabels.google })
    await expect(lastUnlink).toHaveAttribute('aria-disabled', 'true')
    await expect(lastUnlink).toHaveAccessibleDescription(authContent.settings.lastMethod)
    /*
     * 주소는 그대로다. 해제는 문서를 떠나지 않는 조작이므로 결과를 주소에 싣지 않으며, 해제한
     * 제공자 이름도 주소·히스토리에 남지 않는다.
     */
    expect(new URL(page.url()).pathname).toBe('/settings')
    expect(new URL(page.url()).search).not.toContain('unlink')
    expect(new URL(page.url()).search).not.toContain('naver')

    const afterResults = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(
      afterResults.violations.map((violation) => `${violation.id}: ${violation.help}`),
    ).toEqual([])
  })

  test('로그아웃 버튼이 최소 44×44px이고 좁은 폭에서도 유지된다', async ({ page }) => {
    await page.setViewportSize(NARROW_VIEWPORT)
    await signInThroughStub(page)

    const box = await page.getByRole('button', { name: authContent.signOut.label }).boundingBox()

    expect(box?.width ?? 0).toBeGreaterThanOrEqual(44)
    expect(box?.height ?? 0).toBeGreaterThanOrEqual(44)

    // 320px에서 가로 스크롤이 생기지 않는다. 머리말에 조작이 하나 늘어난 뒤에도 그렇다.
    const metrics = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)
  })

  test('키보드만으로 로그아웃하면 세션 쿠키가 사라지고 표지가 나온다', async ({
    page,
    context,
  }) => {
    await signInThroughStub(page)

    const requests: string[] = []
    page.on('request', (request) => {
      if (['xhr', 'fetch'].includes(request.resourceType())) {
        requests.push(`${request.method()} ${new URL(request.url()).pathname}`)
      }
    })

    // `Tab` 세 번이면 로그아웃에 닿고 `Enter`가 실행한다(건너뛰기 → 설정 링크 → 로그아웃).
    // jsdom이 구현하지 않는 활성화 동작이라 이 층이 그것을 확인하는 유일한 자리다.
    await page.keyboard.press('Tab')
    await page.keyboard.press('Tab')
    await page.keyboard.press('Tab')
    await expect(page.getByRole('button', { name: authContent.signOut.label })).toBeFocused()
    await page.keyboard.press('Enter')

    // 표지를 달고 진입 화면으로 이동한다.
    await expect(page.getByRole('status')).toHaveText(authContent.notices.loggedOut)
    expect(new URL(page.url()).search).toBe('?auth=loggedOut')
    await expectAuthScreenMounted(page)

    // 브라우저에 남는 것이 없다. 세션 쿠키도 저장소 항목도 0건이다.
    expect(
      (await context.cookies()).filter((cookie) => cookie.name === 'BOKRILOG_SESSION'),
    ).toEqual([])
    const stored = await page.evaluate(async () => ({
      localStorage: window.localStorage.length,
      sessionStorage: window.sessionStorage.length,
      cookie: document.cookie,
      indexedDb: (await window.indexedDB.databases()).length,
    }))
    expect(stored).toEqual({ localStorage: 0, sessionStorage: 0, cookie: '', indexedDb: 0 })

    // 데이터 요청은 로그아웃 하나뿐이다. 자동 재시도도, 다른 상태 변경도 없다.
    expect(requests).toEqual(['POST /api/auth/logout'])

    // 포커스가 새 문서의 페이지 제목으로 갔다. 실행 뒤 무엇이 바뀌었는지 알 수 있어야 한다.
    const focusedText = await page.evaluate(() => document.activeElement?.textContent.trim() ?? '')
    expect(focusedText).toBe('복리 계획 비교')

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  })

  test('로그아웃 뒤 뒤로 가기로 이전 인증 화면이 복원되지 않는다', async ({ page }) => {
    await signInThroughStub(page)

    await page.getByRole('button', { name: authContent.signOut.label }).click()
    await expect(page.getByRole('status')).toHaveText(authContent.notices.loggedOut)

    // 뒤로 가기. 문서가 캐시·bfcache에서 복원되지 않고 재요청된다(진입점의 `no-store`).
    await page.goBack()
    await expectAuthScreenMounted(page)

    /*
     * 표지는 냉시작(`signedOut`) 하나다.
     *
     * 진입 문서의 `Cache-Control: no-store`가 그 문서를 Chromium의 bfcache 대상에서 빼므로 뒤로
     * 가기는 문서 재요청이 되고, 새로 마운트된 화면의 첫 확인은 미인증이다 — 인증을 관측한 적이
     * 없으니 만료라고 말할 근거가 없다. 둘 중 하나로 받아 주면 bfcache가 어느 쪽으로 동작해도
     * 통과해 이 층이 아무것도 고정하지 않는다. 만료 경로는 아래 가시성 시험이 따로 덮는다.
     */
    await expect(page.getByRole('status')).toHaveText(authContent.notices.signedOut)

    await expect(page.getByRole('heading', { name: authContent.onboarding.heading })).toHaveCount(0)
    await expect(page.getByRole('button', { name: authContent.signOut.label })).toHaveCount(0)
    const text = await page.locator('body').innerText()
    for (const item of [...authContent.onboarding.items, ...authContent.dashboard.items]) {
      expect(text).not.toContain(item)
    }
  })

  test('탭이 다시 보이게 되면 만료를 감지해 표지를 달고 진입 화면으로 보낸다', async ({
    page,
    context,
  }) => {
    await signInThroughStub(page)

    const requests: string[] = []
    page.on('request', (request) => {
      if (['xhr', 'fetch'].includes(request.resourceType())) {
        requests.push(`${request.method()} ${new URL(request.url()).pathname}`)
      }
    })

    /*
     * 세션이 이 브라우저에서 끝난 상태를 만든다. 쿠키를 지우면 다음 세션 확인이 미인증으로
     * 답하고, 그것이 다른 기기에서의 폐기·유휴 만료가 이 탭에 도착하는 모양과 같다.
     */
    await context.clearCookies()

    /*
     * 만료 감지의 실제 생산자다. 인증된 화면을 열어 둔 탭은 다시 마운트되지 않으므로 첫 확인의
     * 답을 계속 주장하고, bfcache 재확인은 위 시험이 고정한 대로 `no-store` 때문에 오지 않는다.
     * `visibilityState`는 이미 `visible`이므로 이벤트 하나가 그 순간을 재현한다.
     */
    await page.evaluate(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    await expect(page.getByRole('status')).toHaveText(authContent.notices.expired)
    expect(new URL(page.url()).search).toBe('?auth=expired')
    await expectAuthScreenMounted(page)

    // 이전 화면의 항목 문자열이 0건이다.
    await expect(page.getByRole('heading', { name: authContent.onboarding.heading })).toHaveCount(0)
    await expect(page.getByRole('button', { name: authContent.signOut.label })).toHaveCount(0)

    /*
     * 재확인 하나뿐이다. 폴링이 아니고, 진입 화면으로 수렴한 뒤에는 그 화면이 세션을 묻지 않는다
     * (진입 화면의 "문서·번들 외 요청 0건").
     */
    expect(requests).toEqual(['GET /api/auth/session'])

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(results.violations.map((violation) => `${violation.id}: ${violation.help}`)).toEqual([])
  })

  test('진입 문서와 번들 자산의 캐시 지시가 갈린다', async ({ page }) => {
    /*
     * 진입점이 계산하는 두 갈래다(`bokrilog.conf`의 `$cache_control` map). 문서가 캐시되면
     * 로그아웃 뒤 뒤로 가기가 이전 화면을 되살리고, 해시 자산이 캐시되지 않으면 매 방문이
     * 번들을 다시 내려받는다. 보안 헤더는 두 응답 모두에 그대로 실려야 한다 — 캐시 지시를 하위
     * location에서 내면 그 헤더들이 사라진다.
     */
    const documentResponse = await page.goto('/onboarding')
    expect(documentResponse?.headers()['cache-control']).toContain('no-store')
    expect(documentResponse?.headers()['content-security-policy']).toContain("default-src 'none'")

    const assetPath = await page
      .locator('script[src^="/assets/"]')
      .first()
      .evaluate((element: HTMLScriptElement) => element.src)
    expect(assetPath).not.toBe('')

    const asset = await page.request.get(assetPath)
    expect(asset.headers()['cache-control']).toBe('public, max-age=31536000, immutable')
    expect(asset.headers()['content-security-policy']).toContain("default-src 'none'")
  })
})
