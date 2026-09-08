import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import {
  authContent,
  authorizePath,
  recoveryCopyStrings,
  SIGN_IN_PROVIDERS,
  signInCopyStrings,
} from '../content/auth'
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

  it('로그인: 두 제공자 버튼이 각자의 서버 인증 시작 경로로 가는 링크다', () => {
    render(<EntryScreen />)

    for (const provider of SIGN_IN_PROVIDERS) {
      const button = screen.getByRole('link', { name: authContent.signIn.labels[provider] })

      // `<a href>` 최상위 내비게이션이다. 버튼·form·스크립트가 아니어야 Nginx CSP를 넓히지 않는다.
      expect(button).toHaveAttribute('href', authorizePath(provider))
      expect(button.tagName.toLowerCase()).toBe('a')
    }

    // 접근 가능한 이름은 제공자명 + "계속하기"다(EXPERIENCE.md).
    expect(authContent.signIn.labels.google).toBe('Google로 계속하기')
    expect(authContent.signIn.labels.naver).toBe('네이버로 계속하기')
  })

  it('로그인: 제공자 순서가 Google → 네이버로 고정된다', () => {
    render(<EntryScreen />)

    const google = screen.getByRole('link', { name: authContent.signIn.labels.google })
    const naver = screen.getByRole('link', { name: authContent.signIn.labels.naver })

    /*
     * DESIGN.md가 정한 고정 순서다. 문서 순서가 곧 `Tab` 순서이므로 이 단정이 그 계약을
     * 함께 나른다. 추천 표지도, 마지막 사용 제공자 강조도, 로고만 있는 버튼도 없다.
     */
    expect(google.compareDocumentPosition(naver)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(SIGN_IN_PROVIDERS).toEqual(['google', 'naver'])
  })

  it('로그인: 두 버튼의 접근 가능한 이름이 텍스트 하나에서만 온다', () => {
    render(<EntryScreen />)

    for (const provider of SIGN_IN_PROVIDERS) {
      const button = screen.getByRole('link', { name: authContent.signIn.labels[provider] })
      // eslint-disable-next-line testing-library/no-node-access -- 로고 SVG가 이름에 기여하지 않는지는 그 요소의 속성으로만 확인할 수 있다. 역할 조회로는 `aria-hidden` 요소가 보이지 않는다.
      const mark = button.querySelector('svg')

      expect(mark).not.toBeNull()
      expect(mark).toHaveAttribute('aria-hidden', 'true')
      expect(mark).toHaveAttribute('focusable', 'false')
      // 이름은 옆의 `<span>` 텍스트 하나다. 로고를 함께 읽으면 이름이 두 번 들린다.
      expect(button.textContent).toBe(authContent.signIn.labels[provider])
    }
  })

  it('로그인: 데이터 경계 안내를 버튼 아래에 둔다', () => {
    render(<EntryScreen />)

    const boundary = screen.getByText(authContent.signIn.boundary)

    // "버튼 아래"는 문서 순서의 계약이다. 두 버튼 모두의 아래여야 한다.
    for (const provider of SIGN_IN_PROVIDERS) {
      const button = screen.getByRole('link', { name: authContent.signIn.labels[provider] })
      expect(button.compareDocumentPosition(boundary)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    }
    expect(boundary.textContent).toContain('본인 확인에만')
    expect(boundary.textContent).toContain('공유하지 않습니다')
  })

  it('로그인: 아직 없는 제공자를 준비 중이라고 밝힌다', () => {
    render(<EntryScreen />)

    // 카카오는 Story 1.7이 더한다. 그때까지 그 버튼은 없다.
    expect(screen.getByText(authContent.signIn.pending)).toBeInTheDocument()
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

  /*
   * 아래는 Story 1.5가 더한 표지 둘과 복구 안내다. 위 단정은 한 글자도 바뀌지 않았다.
   */

  it('안내: 로그아웃·만료·로그아웃 실패 표지가 서로 다른 문장이다', () => {
    for (const notice of ['loggedOut', 'expired', 'logoutFailed'] as const) {
      const { unmount } = render(<EntryScreen notice={notice} />)

      expect(screen.getByRole('status').textContent).toBe(authContent.notices[notice])
      // 계정의 존재 여부를 드러내지 않는다.
      expect(screen.getByRole('status').textContent).not.toContain('계정')

      unmount()
    }

    /*
     * 네 문장이 실제로 다르다. 같으면 만료와 로그아웃을 구분한 훅의 일이 화면에서 사라진다.
     * `signedOut`과도 달라야 한다 — 네 경우 모두 "지금 로그인 상태가 아니다"이지만 사용자가
     * 알아야 하는 사실이 다르다. 특히 로그아웃 실패는 세션이 남아 있을 수 있음을 말한다.
     */
    const sentences = new Set([
      authContent.notices.loggedOut,
      authContent.notices.expired,
      authContent.notices.logoutFailed,
      authContent.notices.signedOut,
    ])
    expect(sentences.size).toBe(4)
    expect(authContent.notices.logoutFailed).toContain('남아 있을 수 있습니다')
  })

  it('복구 안내: 실패 표지에서만 두 제공자의 공식 복구 경로를 안내한다', () => {
    /*
     * `failed`가 아닌 모든 경우에 이 블록이 없다. `cancelled`만 확인하면 정상적으로 로그아웃한
     * 사용자에게 계정 복구 패널을 보여 주는 상태가 통과한다 — 그 사람은 접근을 잃지 않았고, 그
     * 안내는 "로그인 수단에 다시 접근할 수 없다면"으로 시작한다.
     */
    for (const notice of [
      'cancelled',
      'loggedOut',
      'expired',
      'logoutFailed',
      'signedOut',
    ] as const) {
      const { unmount } = render(<EntryScreen notice={notice} />)

      expect(screen.queryByText(authContent.recovery.body)).not.toBeInTheDocument()
      for (const link of authContent.recovery.links) {
        expect(screen.queryByRole('link', { name: link.label })).not.toBeInTheDocument()
      }
      expect(
        screen.queryByRole('heading', { name: authContent.recovery.heading }),
      ).not.toBeInTheDocument()
      expect(document.body.textContent).not.toContain(authContent.recovery.caveat)

      unmount()
    }

    // 표지 없는 첫 방문도 마찬가지다.
    const { unmount } = render(<EntryScreen />)
    expect(screen.queryByText(authContent.recovery.body)).not.toBeInTheDocument()
    for (const link of authContent.recovery.links) {
      expect(screen.queryByRole('link', { name: link.label })).not.toBeInTheDocument()
    }
    unmount()

    render(<EntryScreen notice="failed" />)

    const rendered = document.body.textContent
    for (const copy of recoveryCopyStrings) {
      expect(rendered).toContain(copy)
    }
  })

  it('복구 안내: 두 제공자를 언제나 함께 보여 준다', () => {
    /*
     * 이 화면은 사용자가 어느 제공자로 가입했는지 알 수 없고 알아서도 안 된다 — 그 정보를 화면에
     * 도달시키는 경로가 곧 계정 존재·소속 신호다. 그래서 "당신이 쓴 제공자의 복구 절차"는 만들 수
     * 없고, 남는 배치는 둘을 무조건 함께 보여 주는 것 하나다.
     */
    render(<EntryScreen notice="failed" />)

    const links = authContent.recovery.links.map((link) =>
      screen.getByRole('link', { name: link.label }),
    )

    expect(links).toHaveLength(2)
    expect(authContent.recovery.links.map((link) => link.provider)).toEqual([...SIGN_IN_PROVIDERS])
    // 안내 순서가 버튼 순서와 같다. 다르면 사용자가 두 목록을 대응시킬 수 없다.
    expect(links[0]?.compareDocumentPosition(links[1] as Node)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    )
  })

  it('복구 안내: 새 창을 열지 않고 `rel`로 참조 유출을 막는다', () => {
    render(<EntryScreen notice="failed" />)

    for (const entry of authContent.recovery.links) {
      const link = screen.getByRole('link', { name: entry.label })

      // 주소는 각 제공자가 소유한다. 값이 바뀌면 여기서 드러난다.
      expect(link).toHaveAttribute('href', entry.href)
      expect(entry.href.startsWith('https://')).toBe(true)
      // 기본으로 새 창을 열지 않는다(EXPERIENCE.md).
      expect(link).not.toHaveAttribute('target')
      expect(link).toHaveAttribute('rel', 'noreferrer')
    }

    expect(authContent.recovery.links[0].href).toBe('https://accounts.google.com/signin/recovery')
    expect(authContent.recovery.links[1].href).toBe('https://nid.naver.com/user2/help/idInquiry')
  })

  it('복구 안내: 계정 존재 여부를 노출하지 않고 우회 경로를 만들지 않는다', () => {
    render(<EntryScreen notice="failed" />)

    const rendered = document.body.textContent

    // 조건문이다. 이 브라우저에 계정이 있었는지 말하지 않는다.
    expect(rendered).toContain('로그인 수단에 다시 접근할 수 없다면')
    expect(rendered).not.toContain('등록된 계정')
    expect(rendered).not.toContain('가입한 이메일')
    // 이메일 확인·문의로 인증을 우회하는 경로를 만들지 않으며 그 사실을 카피가 직접 말한다.
    expect(rendered).toContain(authContent.recovery.caveat)
    // 복구 안내에 입력 필드를 두지 않는다. 이메일을 받는 자리 자체가 없다.
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('접근성: 표지와 함께 열리면 포커스가 페이지 제목으로 이동한다', () => {
    render(<EntryScreen notice="loggedOut" />)

    const title = screen.getByRole('heading', { level: 1 })

    /*
     * 이 화면이 표지와 함께 나타나는 것은 언제나 다른 화면에서의 전환 뒤다. 포커스가 문서 처음에
     * 남아 있으면 키보드·보조기술 사용자는 무엇이 바뀌었는지 알 수 없다(EXPERIENCE.md: 라우트
     * 전환 후 제목 포커스).
     */
    expect(title).toHaveFocus()
    // `Tab` 순서에는 들어가지 않는다. 읽기만 하는 제목이 조작 하나로 세어지지 않는다.
    expect(title).toHaveAttribute('tabindex', '-1')
  })

  it('접근성: 표지가 없으면 포커스를 옮기지 않는다', () => {
    render(<EntryScreen />)

    // 첫 방문이다. 옮길 이유가 없고, 옮기면 건너뛰기 링크가 첫 `Tab`이라는 계약이 깨진다.
    expect(screen.getByRole('heading', { level: 1 })).not.toHaveFocus()
    expect(document.body).toHaveFocus()
  })

  it('복구 안내를 더해도 제목 구조가 유지된다', () => {
    render(<EntryScreen notice="failed" />)

    const levels = headingLevels()

    expect(levels.filter((level) => level === 1)).toHaveLength(1)
    expect(levels[0]).toBe(1)

    let previous = levels[0] ?? 0
    for (const level of levels.slice(1)) {
      expect(level - previous).toBeLessThanOrEqual(1)
      previous = level
    }
  })
})
