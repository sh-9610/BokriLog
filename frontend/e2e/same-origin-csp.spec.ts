import { expect, test } from '@playwright/test'

/**
 * Nginx 진입점의 CSP가 화면을 깨뜨리지 않는지 브라우저에서 확인한다.
 *
 * 왜 따로 있는가: Playwright는 CSP 위반에 대해 실패하지 않는다. 위반은 콘솔 경고와
 * `securitypolicyviolation` 이벤트로만 나타나고, 차단된 리소스는 그냥 없는 것처럼 다뤄진다.
 * 그래서 `style-src`가 번들 CSS를 막아도 Story 1.1의 시험은 전부 통과한다. 텍스트 단정은
 * 스타일 없이도 참이고, axe의 대비 검사는 계산된 색을 볼 뿐 그 색이 어디서 왔는지 묻지 않는다.
 * 정책이 화면을 깬 것을 이 저장소에서 실패로 만드는 곳이 여기 하나다.
 *
 * 두 가지를 본다.
 *   1. 위반이 0건인가 — `securitypolicyviolation` 이벤트와 콘솔 오류.
 *   2. 스타일시트가 실제로 적용되었는가 — 번들에서만 나올 수 있는 계산 값.
 *      `index.html`의 noscript 폴백은 인라인 `style` 속성에 같은 값의 폴백을 두고 있으므로,
 *      "화면이 그럴듯해 보인다"로는 번들 적용 여부를 구분할 수 없다. 그래서 인라인 폴백이
 *      만들 수 없는 값만 고른다. CSS 사용자 정의 속성은 tokens.css에만 있고, `body`의 여백 0은
 *      global.css의 reset에만 있다(브라우저 기본값은 8px).
 *
 * `PLAYWRIGHT_BASE_URL`이 없으면 건너뛴다. 기본 `npm run test:e2e`는 `vite preview`가 띄우고
 * preview에는 Nginx의 응답 헤더가 없어 이 시험이 검증할 대상 자체가 없다.
 */

const injectedOrigin = process.env.PLAYWRIGHT_BASE_URL?.trim() ?? ''

test.describe('Nginx 진입점의 CSP', () => {
  // eslint-disable-next-line playwright/no-skipped-test -- 주입된 origin에서만 성립하는 시험이다. 파일째 사라지는 것보다 보고서에 "건너뜀"으로 남는 편이 낫다.
  test.skip(
    injectedOrigin === '',
    'PLAYWRIGHT_BASE_URL로 Nginx origin을 주입한 실행에서만 의미가 있다.',
  )

  test('CSP 위반 없이 진입 화면이 번들 스타일로 렌더된다', async ({ page }) => {
    const consoleMessages: { text: string; type: string }[] = []
    page.on('console', (message) => {
      consoleMessages.push({ text: message.text(), type: message.type() })
    })
    page.on('pageerror', (error) => {
      consoleMessages.push({ text: error.message, type: 'error' })
    })

    // 문서의 어떤 스크립트보다 먼저 등록해야 첫 리소스의 위반도 놓치지 않는다.
    await page.addInitScript(() => {
      const collected: string[] = []
      // 페이지 안에서 위반을 모아 두는 자리. 아래 evaluate가 이 배열을 그대로 읽는다.
      ;(window as Window & { __cspViolations?: string[] }).__cspViolations = collected
      document.addEventListener('securitypolicyviolation', (event) => {
        collected.push(`${event.effectiveDirective} <- ${event.blockedURI}`)
      })
    })

    await page.goto('/')
    await expect(page.getByRole('heading', { level: 1, name: '복리 계획 비교' })).toBeVisible()

    const applied = await page.evaluate(() => ({
      bodyBackground: getComputedStyle(document.body).backgroundColor,
      bodyMarginTop: getComputedStyle(document.body).marginTop,
      styleSheetCount: document.styleSheets.length,
      surfaceToken: getComputedStyle(document.documentElement)
        .getPropertyValue('--color-surface-base')
        .trim(),
    }))

    // 차단된 스타일시트는 이 목록에 아예 오르지 않는다.
    expect(applied.styleSheetCount).toBeGreaterThan(0)
    // tokens.css가 적용되지 않으면 사용자 정의 속성은 빈 문자열이다.
    expect(applied.surfaceToken).not.toBe('')
    // global.css의 reset. 브라우저 기본값은 8px이고 인라인 폴백은 body에 손대지 않는다.
    expect(applied.bodyMarginTop).toBe('0px')
    // 배경이 투명하면 global.css의 `background-color`가 적용되지 않은 것이다.
    expect(applied.bodyBackground).not.toBe('rgba(0, 0, 0, 0)')

    const violations = await page.evaluate(
      () => (window as Window & { __cspViolations?: string[] }).__cspViolations,
    )
    expect(violations).toEqual([])

    const consoleErrors = consoleMessages.filter((message) => message.type === 'error')
    expect(consoleErrors).toEqual([])
  })
})
