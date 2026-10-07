import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { App } from './App'
import { authContent, OPERATOR_PATH } from './content/auth'

/*
 * 따로 받는 운영자 화면 chunk를 받지 못한 경우(배포 교체·네트워크 단절). 모듈 적재가 실패하도록 대체한다 — 이 파일 안에서만
 * 그렇고, 정상 적재는 `App.test.tsx`가 본다.
 */
vi.mock('./screens/OperatorScreen', () => {
  throw new Error('chunk를 받지 못했다')
})

afterEach(() => {
  window.history.pushState({}, '', '/')
})

describe('App · 운영자 chunk 적재 실패', () => {
  it('앱 전체가 비지 않고 다시 불러오기 안내 하나를 그린다', async () => {
    // 오류 경계가 잡은 오류를 React가 콘솔에 남긴다. 이 시험에서는 기대한 출력이다.
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    window.history.pushState({}, '', OPERATOR_PATH)

    render(<App />)

    expect(await screen.findByText(authContent.session.screenLoadFailed)).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(authContent.session.screenLoadFailed)
  })
})
