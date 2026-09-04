import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

import { entryContent, entryCopyStrings } from '../src/content/entry'

/**
 * 진입 화면의 실제 Chromium 검증.
 * 빌드 산출물을 `vite preview`로 띄운 뒤 접근성·키보드·리플로·모션·요청을 확인한다.
 * 백엔드·DB·제공자 자격정보를 요구하지 않는다.
 */

const FOCUSABLE_SELECTOR =
  'a[href], button, input, select, textarea, [tabindex]:not([tabindex="-1"])'

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']

/**
 * 확대 배율은 WCAG 1.4.10 표준 해석에 따라 뷰포트 폭 = 1280px ÷ 배율로 재현한다.
 * `tablet`은 이 화면이 2열로 바뀌는 768–1023px 구간의 대표값이다.
 */
const VIEWPORTS = {
  narrow: { width: 320, height: 640 },
  zoom400: { width: 320, height: 512 },
  zoom200: { width: 640, height: 512 },
  tablet: { width: 768, height: 1024 },
} as const

/**
 * 앱이 마운트에 실패해 body가 비면 대부분의 단정이 조용히 통과한다.
 * 각 시험은 실제 검증에 들어가기 전에 이 canary로 화면이 살아 있음을 확인한다.
 */
async function expectScreenMounted(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { level: 1, name: '복리 계획 비교' })).toBeVisible()
}

async function horizontalScrollMetrics(page: Page): Promise<{
  scrollWidth: number
  clientWidth: number
}> {
  return page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
}

/** 뷰포트 오른쪽 경계를 넘는 요소. 가로 스크롤·겹침의 원인이 된다. */
async function elementsOverflowingViewport(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const limit = document.documentElement.clientWidth + 1
    return Array.from(document.querySelectorAll('body *'))
      .filter((element) => {
        const rect = element.getBoundingClientRect()
        return rect.width > 0 && rect.height > 0 && rect.right > limit
      })
      .map((element) => element.tagName.toLowerCase() + (element.id === '' ? '' : `#${element.id}`))
  })
}

/** overflow를 잘라내면서 내용이 상자를 넘는 요소. 텍스트 잘림의 원인이 된다. */
async function clippedElements(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    Array.from(document.querySelectorAll('body *'))
      .filter((element) => {
        const style = getComputedStyle(element)
        const clipsX = style.overflowX === 'hidden' || style.overflowX === 'clip'
        const clipsY = style.overflowY === 'hidden' || style.overflowY === 'clip'
        return (
          (clipsX && element.scrollWidth > element.clientWidth + 1) ||
          (clipsY && element.scrollHeight > element.clientHeight + 1)
        )
      })
      .map(
        (element) => element.tagName.toLowerCase() + (element.id === '' ? '' : `#${element.id}`),
      ),
  )
}

test.describe('진입 화면', () => {
  test('접근성: 데스크톱·320px 모두 WCAG 2.2 AA 자동 검사 위반이 0건이다', async ({ page }) => {
    await page.goto('/')
    await expectScreenMounted(page)

    const desktop = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(
      desktop.violations.map((violation) => `desktop ${violation.id}: ${violation.help}`),
    ).toEqual([])

    // 좁은 폭에서만 나타나는 대비·목표 크기 위반을 잡는다.
    await page.setViewportSize(VIEWPORTS.narrow)
    await expectScreenMounted(page)

    const narrow = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze()
    expect(
      narrow.violations.map((violation) => `320px ${violation.id}: ${violation.help}`),
    ).toEqual([])
  })

  test('키보드 탐색: 문서 순서로 도달하고 focus-visible 외곽선이 보인다', async ({ page }) => {
    await page.goto('/')
    await expectScreenMounted(page)

    const expectedOrder = await page.evaluate(
      (selector) =>
        Array.from(document.querySelectorAll(selector)).map(
          (element) => `${element.tagName.toLowerCase()}:${element.textContent.trim()}`,
        ),
      FOCUSABLE_SELECTOR,
    )

    expect(expectedOrder.length).toBeGreaterThan(0)

    const reachedOrder: string[] = []

    for (let index = 0; index < expectedOrder.length; index += 1) {
      await page.keyboard.press('Tab')

      const focused = await page.evaluate(() => {
        const element = document.activeElement
        if (element === null || element === document.body) {
          return null
        }
        const style = getComputedStyle(element)
        const rect = element.getBoundingClientRect()
        return {
          key: `${element.tagName.toLowerCase()}:${element.textContent.trim()}`,
          outlineStyle: style.outlineStyle,
          outlineWidth: style.outlineWidth,
          outlineOffset: style.outlineOffset,
          outlineColor: style.outlineColor,
          width: rect.width,
          height: rect.height,
        }
      })

      // eslint-disable-next-line playwright/no-conditional-in-test -- 타입 좁히기 가드. 도달 실패는 단정이 아니라 예외로 즉시 종료한다.
      if (focused === null) {
        throw new Error(`Tab ${String(index + 1)}회 뒤 focusable 요소에 도달하지 못했습니다.`)
      }

      // focus-ring 2px 두께, 2px 간격 (DESIGN.md Components 마지막 문단).
      expect(focused.outlineStyle).toBe('solid')
      expect(focused.outlineWidth).toBe('2px')
      expect(focused.outlineOffset).toBe('2px')
      expect(focused.outlineColor).toBe('rgb(29, 79, 145)')

      // 상호작용 목표 최소 44×44px.
      expect(focused.width).toBeGreaterThanOrEqual(44)
      expect(focused.height).toBeGreaterThanOrEqual(44)

      reachedOrder.push(focused.key)
    }

    expect(reachedOrder).toEqual(expectedOrder)
  })

  test('좁은 뷰포트 320px: 가로 스크롤과 넘침이 없다', async ({ page }) => {
    await page.setViewportSize(VIEWPORTS.narrow)
    await page.goto('/')
    await expectScreenMounted(page)

    const metrics = await horizontalScrollMetrics(page)

    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)
    expect(await elementsOverflowingViewport(page)).toEqual([])
    expect(await clippedElements(page)).toEqual([])
  })

  test('태블릿 768px: 2열로 바뀌어도 가로 스크롤과 잘림이 없다', async ({ page }) => {
    await page.setViewportSize(VIEWPORTS.tablet)
    await page.goto('/')
    await expectScreenMounted(page)

    const metrics = await horizontalScrollMetrics(page)

    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)
    expect(await elementsOverflowingViewport(page)).toEqual([])
    expect(await clippedElements(page)).toEqual([])
  })

  test('확대 400%(320×512): 양방향 스크롤 없이 전체 내용을 확인한다', async ({ page }) => {
    await page.setViewportSize(VIEWPORTS.zoom400)
    await page.goto('/')
    await expectScreenMounted(page)

    const metrics = await horizontalScrollMetrics(page)

    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)
    // innerText만으로는 overflow로 잘린 텍스트도 그대로 반환되므로 잘림·넘침을 함께 본다.
    expect(await clippedElements(page)).toEqual([])
    expect(await elementsOverflowingViewport(page)).toEqual([])

    const visibleText = await page.locator('body').innerText()
    for (const copy of entryCopyStrings) {
      expect(visibleText).toContain(copy)
    }
  })

  test('확대 200%(640×512): 텍스트가 잘리지 않는다', async ({ page }) => {
    await page.setViewportSize(VIEWPORTS.zoom200)
    await page.goto('/')
    await expectScreenMounted(page)

    const metrics = await horizontalScrollMetrics(page)

    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth)
    expect(await clippedElements(page)).toEqual([])
    expect(await elementsOverflowingViewport(page)).toEqual([])
  })

  test('인증 경계 섹션이 service-notice 표면으로 구분된다', async ({ page }) => {
    await page.goto('/')
    await expectScreenMounted(page)

    const noticeSection = entryContent.sections.find((section) => section.tone === 'notice')
    const plainSection = entryContent.sections.find((section) => section.tone === 'plain')

    // eslint-disable-next-line playwright/no-conditional-in-test -- 타입 좁히기 가드. 카피 정본에 두 tone이 없으면 시험 전제가 깨진 것이라 예외로 종료한다.
    if (noticeSection === undefined || plainSection === undefined) {
      throw new Error('notice 섹션과 plain 섹션이 모두 있어야 한다.')
    }

    const surfaceOf = async (headingId: string): Promise<{ background: string; border: string }> =>
      page.evaluate((id) => {
        const section = document.getElementById(id)?.closest('section')
        if (section === null || section === undefined) {
          throw new Error(`섹션을 찾지 못했습니다: ${id}`)
        }
        const style = getComputedStyle(section)
        return { background: style.backgroundColor, border: style.borderTopColor }
      }, headingId)

    const notice = await surfaceOf(noticeSection.id)
    const plain = await surfaceOf(plainSection.id)

    expect(notice.background).not.toBe(plain.background)
    expect(notice.border).not.toBe(plain.border)
  })

  test('문서·번들 외 네트워크 요청이 발생하지 않는다', async ({ page, baseURL }) => {
    const requests: { url: string; resourceType: string }[] = []
    page.on('request', (request) => {
      requests.push({ url: request.url(), resourceType: request.resourceType() })
    })

    await page.goto('/')
    // eslint-disable-next-line playwright/no-networkidle -- "요청 0건" 단정은 네트워크가 잠잠해진 뒤에만 의미가 있다. 이 화면은 외부 요청이 없어 idle 대기가 흔들리지 않는다.
    await page.waitForLoadState('networkidle')
    await expectScreenMounted(page)

    const origin = new URL(baseURL ?? page.url()).origin

    /*
     * `startsWith(origin)` 비교는 포트 41830처럼 접두사가 겹치는 진짜 외부 주소를 통과시킨다.
     * origin을 정규화해 비교하고, 번들에 인라인된 asset(`data:`)과 런타임 생성 객체(`blob:`)만 허용한다.
     */
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

    expect(external).toEqual([])
    expect(
      requests.filter((request) =>
        ['xhr', 'fetch', 'websocket', 'eventsource'].includes(request.resourceType),
      ),
    ).toEqual([])
    expect(requests.length).toBeGreaterThan(0)
  })

  test('동일 출처 응답에 4xx·5xx가 없고 파비콘은 요청을 만들지 않는다', async ({ page }) => {
    const failed: string[] = []
    page.on('response', (response) => {
      if (response.status() >= 400) {
        failed.push(`${String(response.status())} ${response.url()}`)
      }
    })

    await page.goto('/')
    // eslint-disable-next-line playwright/no-networkidle -- "요청 0건" 단정은 네트워크가 잠잠해진 뒤에만 의미가 있다. 이 화면은 외부 요청이 없어 idle 대기가 흔들리지 않는다.
    await page.waitForLoadState('networkidle')
    await expectScreenMounted(page)

    expect(failed).toEqual([])

    /*
     * headless Chromium은 탭 UI가 없어 `/favicon.ico`를 스스로 요청하지 않는다.
     * 그래서 위의 응답 감시만으로는 파비콘 404를 관측할 수 없다.
     * 대신 문서가 인라인 `data:` 아이콘을 선언했는지를 직접 단정한다.
     * 선언이 있으면 헤드풀 브라우저도 `/favicon.ico`로 폴백하지 않고, `data:`는 네트워크를 타지 않는다.
     */
    const iconHrefs = await page.evaluate(() =>
      Array.from(document.querySelectorAll('link[rel~="icon"]')).map(
        (link) => link.getAttribute('href') ?? '',
      ),
    )

    expect(iconHrefs.length).toBeGreaterThan(0)
    for (const href of iconHrefs) {
      expect(href.startsWith('data:')).toBe(true)
    }
  })

  test('브라우저 저장소에 항목이 생기지 않는다', async ({ page }) => {
    await page.goto('/')
    // eslint-disable-next-line playwright/no-networkidle -- "요청 0건" 단정은 네트워크가 잠잠해진 뒤에만 의미가 있다. 이 화면은 외부 요청이 없어 idle 대기가 흔들리지 않는다.
    await page.waitForLoadState('networkidle')
    await expectScreenMounted(page)

    const stored = await page.evaluate(async () => ({
      localStorage: window.localStorage.length,
      sessionStorage: window.sessionStorage.length,
      cookie: document.cookie,
      indexedDb: (await window.indexedDB.databases()).length,
    }))

    expect(stored).toEqual({
      localStorage: 0,
      sessionStorage: 0,
      cookie: '',
      indexedDb: 0,
    })
  })

  test('기본 설정에서는 전환이 정의되어 있어 모션 감소 검증이 유효하다', async ({ page }) => {
    await page.goto('/')
    await expectScreenMounted(page)

    const animated = await page.evaluate(
      () =>
        Array.from(document.querySelectorAll('body *')).filter((element) => {
          const style = getComputedStyle(element)
          return style.transitionDuration
            .split(',')
            .some((duration) => Number.parseFloat(duration) > 0)
        }).length,
    )

    expect(animated).toBeGreaterThan(0)
  })
})

test.describe('진입 화면 · JavaScript 비활성', () => {
  test.use({ javaScriptEnabled: false })

  test('noscript 대체 안내가 이용 범위·비추천 고지·인증 경계를 전달한다', async ({ page }) => {
    await page.goto('/')

    const text = await page.locator('body').innerText()

    expect(text).toContain('복리 계획 비교')
    expect(text).toContain('투자성과·계좌 수익률·투자 추천이 아닙니다.')
    expect(text).toContain('거래 실행·투자 추천을 제공하지 않습니다.')
    expect(text).toContain(
      'Google·네이버·카카오는 본인 확인에만 사용하며 Bybit 자격정보·금융 데이터·계획은 공유하지 않습니다.',
    )
  })
})

test.describe('진입 화면 · 모션 감소', () => {
  test('prefers-reduced-motion: reduce에서 전환·애니메이션이 남지 않는다', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/')
    await expectScreenMounted(page)

    const stillAnimated = await page.evaluate(() => {
      const hasDuration = (value: string): boolean =>
        value.split(',').some((duration) => Number.parseFloat(duration) > 0)

      return Array.from(document.querySelectorAll('body *'))
        .filter((element) => {
          const style = getComputedStyle(element)
          return hasDuration(style.transitionDuration) || hasDuration(style.animationDuration)
        })
        .map((element) => element.tagName.toLowerCase())
    })

    expect(stillAnimated).toEqual([])
  })
})
