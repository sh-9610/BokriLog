/*
 * 화면의 키보드 순서를 공지 영역 밖에서 센다(Story 3.5).
 *
 * 대시보드 상단의 서비스 공지 영역은 스택 전체의 운영 상태를 따른다 — 같은 스택의 다른 시험(`operator.spec.ts`의 전역
 * 광고 제한과 그 복구 공지, 72시간 유지)이 그 영역에 문의 링크를 더할 수 있다. 화면 자신의 조작 순서를 고정하는 시험은
 * 그 영역 안의 정지점을 건너뛰고 센다. 영역 자체의 키보드·접근성은 `operator.spec.ts`가 본다.
 *
 * 시험 파일이 아니다: Playwright는 `*.spec.ts`만 모은다.
 */
import type { Page } from '@playwright/test'

import { serviceNoticeContent } from '../../src/content/serviceNotice'

/** Tab을 눌러 공지 영역 밖의 정지점 `count`개의 글자를 차례로 모은다. */
export async function tabStopsOutsideNotices(page: Page, count: number): Promise<string[]> {
  const reached: string[] = []
  // 공지 영역의 정지점은 몇 개든 건너뛴다. 무한히 돌지 않도록 넉넉한 상한을 둔다.
  for (let pressed = 0; reached.length < count && pressed < count + 20; pressed += 1) {
    await page.keyboard.press('Tab')
    const text = await page.evaluate((id) => {
      const active = document.activeElement
      if (active?.closest(`[aria-labelledby="${id}"]`) != null) return null
      return active?.textContent.trim() ?? ''
    }, serviceNoticeContent.id)
    if (text !== null) reached.push(text)
  }
  return reached
}
