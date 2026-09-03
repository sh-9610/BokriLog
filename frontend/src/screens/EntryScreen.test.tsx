import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { entryContent, entryCopyStrings } from '../content/entry'
import { getObservedRequests, getObservedStorageEntries } from '../test/setup'
import { EntryScreen } from './EntryScreen'

function headingLevels(): number[] {
  return Array.from(document.querySelectorAll('h1, h2, h3, h4, h5, h6')).map((heading) =>
    Number(heading.tagName.slice(1)),
  )
}

describe('EntryScreen', () => {
  it('이용 범위: 읽기 전용 Bybit UTA 복리 계획 비교 서비스 설명을 렌더한다', () => {
    render(<EntryScreen />)

    expect(screen.getByRole('heading', { level: 1, name: '복리 계획 비교' })).toBeInTheDocument()
    expect(screen.getByText(entryContent.lead)).toBeInTheDocument()
    expect(screen.getByText(entryContent.lead).textContent).toContain('읽기 전용')
    expect(screen.getByText(entryContent.lead).textContent).toContain('Bybit UTA')
  })

  it('이용 범위: 거래 실행·투자 추천을 제공하지 않는다는 안내를 렌더한다', () => {
    render(<EntryScreen />)

    expect(screen.getByText('거래 실행·투자 추천을 제공하지 않습니다.')).toBeInTheDocument()
  })

  /*
   * 공유 상수만 쓰면 화면과 시험이 같은 값을 읽어 동어반복이 된다. 렌더 누락은 잡아도
   * 문구 변경은 잡지 못하므로, UX-DR23이 정하는 두 문자열은 리터럴로 단정한다.
   */
  it('UX-DR23: `복리 계획 비교` 제목과 비추천 고지를 인접 배치한다', () => {
    render(<EntryScreen />)

    const title = screen.getByRole('heading', { level: 1 })

    expect(title.textContent).toBe('복리 계획 비교')
    expect(title.nextElementSibling?.textContent).toBe(
      '투자성과·계좌 수익률·투자 추천이 아닙니다.',
    )

    // 카피 정본이 같은 값을 들고 있는지도 함께 고정한다.
    expect(entryContent.pageTitle).toBe('복리 계획 비교')
    expect(entryContent.disclaimer).toBe('투자성과·계좌 수익률·투자 추천이 아닙니다.')
    expect(title.textContent).toBe(entryContent.pageTitle)
    expect(title.nextElementSibling?.textContent).toBe(entryContent.disclaimer)
  })

  it('인증 경계: 소셜 계정의 사용 범위와 비공유 대상을 렌더한다', () => {
    render(<EntryScreen />)

    const boundary = screen.getByText(
      'Google·네이버·카카오는 본인 확인에만 사용하며 Bybit 자격정보·금융 데이터·계획은 공유하지 않습니다.',
    )

    expect(boundary).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 2, name: '로그인 정보의 사용 범위' }),
    ).toBeInTheDocument()
  })

  it('문구 표류 방지: 카피 정본의 모든 문자열이 화면에 렌더된다', () => {
    render(<EntryScreen />)

    const rendered = document.body.textContent ?? ''

    for (const copy of entryCopyStrings) {
      expect(rendered).toContain(copy)
    }
  })

  it('제목 구조: h1이 정확히 1개이고 레벨 건너뜀이 없다', () => {
    render(<EntryScreen />)

    const levels = headingLevels()

    expect(levels.filter((level) => level === 1)).toHaveLength(1)
    expect(levels[0]).toBe(1)

    let previous = levels[0] ?? 0
    for (const level of levels.slice(1)) {
      expect(level - previous).toBeLessThanOrEqual(1)
      previous = level
    }
  })

  it('본문 건너뛰기 링크가 main 요소를 가리킨다', () => {
    render(<EntryScreen />)

    const skipLink = screen.getByRole('link', { name: entryContent.skipLink })
    const target = skipLink.getAttribute('href')?.slice(1) ?? ''

    expect(document.getElementById(target)?.tagName.toLowerCase()).toBe('main')
  })

  it('렌더만으로 네트워크 요청이 발생하지 않는다', () => {
    render(<EntryScreen />)

    expect(getObservedRequests()).toEqual([])
  })

  it('렌더만으로 브라우저 저장소 항목이 생기지 않는다', () => {
    render(<EntryScreen />)

    expect(getObservedStorageEntries()).toEqual([])
  })
})
