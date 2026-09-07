import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { authContent, AUTHORIZE_PATH, signInCopyStrings } from '../content/auth'
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
    expect(title.nextElementSibling?.textContent).toBe('투자성과·계좌 수익률·투자 추천이 아닙니다.')

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

    const rendered = document.body.textContent

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

    // eslint-disable-next-line testing-library/no-node-access -- href가 가리키는 id를 문서에서 직접 해석해야 한다. 역할 조회로는 "그 id의 요소가 main인지"를 검증할 수 없다.
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

  /*
   * 아래는 Story 1.4가 더한 로그인 진입이다. 위 아홉 개 단정은 한 글자도 바뀌지 않았다.
   */

  it('로그인: Google 버튼이 서버 인증 시작 경로로 가는 링크다', () => {
    render(<EntryScreen />)

    const button = screen.getByRole('link', { name: authContent.signIn.googleLabel })

    // `<a href>` 최상위 내비게이션이다. 버튼·form·스크립트가 아니어야 Nginx CSP를 넓히지 않는다.
    expect(button).toHaveAttribute('href', AUTHORIZE_PATH)
    expect(button.tagName.toLowerCase()).toBe('a')
    // 접근 가능한 이름은 제공자명 + "계속하기"다(EXPERIENCE.md).
    expect(authContent.signIn.googleLabel).toBe('Google로 계속하기')
  })

  it('로그인: 데이터 경계 안내를 버튼 아래에 둔다', () => {
    render(<EntryScreen />)

    const button = screen.getByRole('link', { name: authContent.signIn.googleLabel })
    const boundary = screen.getByText(authContent.signIn.boundary)

    // "버튼 아래"는 문서 순서의 계약이다.
    expect(button.compareDocumentPosition(boundary)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(boundary.textContent).toContain('본인 확인에만')
    expect(boundary.textContent).toContain('공유하지 않습니다')
  })

  it('로그인: 아직 없는 제공자를 준비 중이라고 밝힌다', () => {
    render(<EntryScreen />)

    // 추천 표지도, 마지막 사용 제공자 강조도 없다. 실제 버튼은 Google 하나뿐이다.
    expect(screen.getByText(authContent.signIn.pending)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /네이버/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /카카오/ })).not.toBeInTheDocument()
  })

  it('로그인: 카피 정본의 모든 문자열이 렌더된다', () => {
    render(<EntryScreen />)

    const rendered = document.body.textContent

    for (const copy of signInCopyStrings) {
      expect(rendered).toContain(copy)
    }
  })

  it('안내: 표지가 없으면 안내 문장을 띄우지 않는다', () => {
    render(<EntryScreen />)

    const status = screen.getByRole('status')

    expect(status.textContent).toBe('')
    for (const notice of Object.values(authContent.notices)) {
      expect(screen.queryByText(notice)).not.toBeInTheDocument()
    }
  })

  it('안내: 취소 표지는 재시도 경로를 알려 준다', () => {
    render(<EntryScreen notice="cancelled" />)

    const status = screen.getByRole('status')

    expect(status.textContent).toBe(authContent.notices.cancelled)
    expect(status.textContent).toContain('다시 시도')
    // 계정의 존재 여부를 드러내지 않는다.
    expect(status.textContent).not.toContain('계정')
  })

  it('안내: 실패·로그아웃 표지도 계정 존재를 드러내지 않는다', () => {
    for (const notice of ['failed', 'signedOut'] as const) {
      const { unmount } = render(<EntryScreen notice={notice} />)

      expect(screen.getByRole('status').textContent).toBe(authContent.notices[notice])
      expect(screen.getByRole('status').textContent).not.toContain('계정')

      unmount()
    }
  })

  it('접근성: polite live region이 화면당 하나다', () => {
    render(<EntryScreen notice="failed" />)

    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite')
  })

  it('로그인 블록을 더해도 렌더만으로 네트워크 요청이 발생하지 않는다', () => {
    render(<EntryScreen notice="failed" />)

    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
  })
})
