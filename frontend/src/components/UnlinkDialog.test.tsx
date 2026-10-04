import { fireEvent, render, screen } from '@testing-library/react'
import { StrictMode, useLayoutEffect, useRef, useState, type ReactElement } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { authContent, unlinkDialogCopyStrings } from '../content/auth'
import { getObservedStorageEntries } from '../test/setup'
import { UnlinkDialog } from './UnlinkDialog'

/*
 * 확인 대화상자.
 *
 * DESIGN.md `confirmation-dialog`가 규정한 넷을 이 층이 소유한다 — 기본 포커스가 취소에 있고,
 * 포커스가 대화상자 안에 갇히고, `Esc`가 닫고, 닫을 때 호출점으로 돌아간다. 기하(최소 44×44px)와
 * axe 위반 0건은 실제 브라우저에서만 잴 수 있으므로 e2e가 본다.
 */

/**
 * 호출점 버튼들. 시험이 끝나면 치운다.
 *
 * Testing Library의 `cleanup`은 자기가 만든 컨테이너만 치우므로 이 버튼은 직접 지워야 한다.
 * <b>시험이 끝난 뒤에 지운다</b> — 언마운트 직후에 지우면 방금 되돌아온 포커스가 요소와 함께
 * 사라져 "호출점으로 돌아온다"를 볼 수 없다.
 */
const openers: HTMLButtonElement[] = []

afterEach(() => {
  for (const opener of openers.splice(0)) {
    opener.remove()
  }
})

/** 이 대화상자를 연 버튼. 닫을 때 포커스가 여기로 돌아와야 한다. */
function renderWithOpener(
  options: { readonly pending?: boolean; readonly restoreFocus?: boolean } = {},
): {
  readonly onCancel: () => void
  readonly onConfirm: () => void
  readonly opener: HTMLButtonElement
  readonly unmount: () => void
} {
  const onCancel = vi.fn()
  const onConfirm = vi.fn()
  const opener = document.createElement('button')
  opener.textContent = authContent.settings.unlinkLabels.naver
  document.body.append(opener)
  openers.push(opener)
  opener.focus()
  // 호출점이 실제로 포커스를 얻었는지 먼저 본다. 아니면 아래 복귀 단정이 아무것도 관측하지 않는다.
  expect(opener).toHaveFocus()

  const { unmount } = render(
    <UnlinkDialog
      onCancel={onCancel}
      onConfirm={onConfirm}
      pending={options.pending ?? false}
      provider="naver"
      restoreFocus={options.restoreFocus ?? true}
    />,
  )
  return { onCancel, onConfirm, opener, unmount }
}

/** 문서에 붙인 호출점 버튼 하나. 포커스를 얹은 채로 돌려준다. */
function focusedOpener(): HTMLButtonElement {
  const opener = document.createElement('button')
  opener.textContent = authContent.settings.unlinkLabels.naver
  document.body.append(opener)
  openers.push(opener)
  opener.focus()
  expect(opener).toHaveFocus()
  return opener
}

describe('UnlinkDialog', () => {
  it('대상과 결과를 제목에 쓰고 삭제되는 것과 보존되는 것을 나눈다', () => {
    const { unmount } = renderWithOpener()

    const dialog = screen.getByRole('dialog')

    expect(dialog).toHaveAttribute('aria-modal', 'true')
    // 접근 가능한 이름이 대상과 결과를 함께 말한다.
    expect(dialog).toHaveAccessibleName(authContent.settings.unlinkDialog.titles.naver)
    /*
     * 설명은 세 문단 묶음이고 순서가 계약이다 — 삭제되는 것, 보존되는 것, 세션에 일어나는 일.
     * 제목만 읽히면 대상만 알고 결과는 모르는 상태가 된다.
     *
     * 전문으로 고정한다. 문자열 인자는 전문 비교이며(부분 문자열이 아니다) 정규식으로 만들면 카피의
     * 마침표가 메타 문자가 되어 단정이 조용히 느슨해진다. 한 문단만 보면 나머지가 사라져도 통과한다.
     */
    expect(dialog).toHaveAccessibleDescription(
      [
        authContent.settings.unlinkDialog.removed,
        authContent.settings.unlinkDialog.kept,
        authContent.settings.unlinkDialog.sessions,
      ].join(' '),
    )
    for (const copy of unlinkDialogCopyStrings) {
      expect(dialog.textContent).toContain(copy)
    }
    unmount()
  })

  it('기본 포커스가 취소에 있다', () => {
    const { unmount } = renderWithOpener()

    /*
     * 비가역 조작의 대화상자에서 `Enter` 한 번이 실행이 되면 안 된다. DESIGN.md
     * `confirmation-dialog`가 이것을 직접 규정한다.
     */
    expect(
      screen.getByRole('button', { name: authContent.settings.unlinkDialog.cancel }),
    ).toHaveFocus()
    unmount()
  })

  it('취소가 확인보다 문서 순서에서 앞이다', () => {
    const { unmount } = renderWithOpener()

    const labels = screen
      .getAllByRole('button')
      .map((button) => button.textContent)
      .filter(
        (text) =>
          text === authContent.settings.unlinkDialog.cancel ||
          text === authContent.settings.unlinkDialog.confirm,
      )

    // 문서 순서가 곧 탭 순서다. 먼저 닿는 조작이 되돌릴 수 있는 쪽이어야 한다.
    expect(labels).toEqual([
      authContent.settings.unlinkDialog.cancel,
      authContent.settings.unlinkDialog.confirm,
    ])
    unmount()
  })

  it('포커스가 대화상자 안에 갇힌다', () => {
    const { unmount } = renderWithOpener()
    const cancel = screen.getByRole('button', { name: authContent.settings.unlinkDialog.cancel })
    const confirm = screen.getByRole('button', { name: authContent.settings.unlinkDialog.confirm })

    // 마지막 요소에서 `Tab`을 누르면 첫 요소로 돌아온다.
    confirm.focus()
    fireEvent.keyDown(confirm, { key: 'Tab' })
    expect(cancel).toHaveFocus()

    // 첫 요소에서 `Shift+Tab`을 누르면 마지막 요소로 간다.
    fireEvent.keyDown(cancel, { key: 'Tab', shiftKey: true })
    expect(confirm).toHaveFocus()
    unmount()
  })

  it('`Esc`가 닫기를 부르고 아무 요청도 보내지 않는다', () => {
    const { onCancel, onConfirm, unmount } = renderWithOpener()

    fireEvent.keyDown(
      screen.getByRole('button', { name: authContent.settings.unlinkDialog.cancel }),
      { key: 'Escape' },
    )

    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
    unmount()
  })

  it('취소는 아무것도 실행하지 않는다', () => {
    const { onCancel, onConfirm, unmount } = renderWithOpener()

    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkDialog.cancel }))

    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
    unmount()
  })

  it('확인은 최종 실행을 부른다', () => {
    const { onCancel, onConfirm, unmount } = renderWithOpener()

    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkDialog.confirm }))

    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onCancel).not.toHaveBeenCalled()
    unmount()
  })

  it('겹을 눌러도 `Esc`와 포커스 가둠이 살아 있다', () => {
    const { onCancel, unmount } = renderWithOpener()
    const cancel = screen.getByRole('button', { name: authContent.settings.unlinkDialog.cancel })
    const confirm = screen.getByRole('button', { name: authContent.settings.unlinkDialog.confirm })

    /*
     * 겹은 포커스를 받을 수 없다. 처리기를 그 요소에 두면 한 번 클릭하는 것만으로 포커스가
     * `<body>`로 떨어지고 이후 keydown이 그 처리기에 닿지 않는다 — `Esc`가 먹지 않고 `Tab`이
     * 배경으로 빠져나간다. `document`에 붙이면 포커스가 어디에 있든 답한다.
     */
    cancel.blur()
    expect(document.body).toHaveFocus()

    fireEvent.keyDown(document, { key: 'Tab' })

    // 밖에 있던 포커스를 안으로 끌어온다.
    expect(cancel).toHaveFocus()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(confirm).toBeInTheDocument()
    unmount()
  })

  it('진행 중에는 `Esc`도 닫지 않는다', () => {
    const { onCancel, unmount } = renderWithOpener({ pending: true })

    fireEvent.keyDown(document, { key: 'Escape' })

    // 최종 확인이 도는 중이다. 여기서 닫히면 그 결과를 받을 자리가 사라진다.
    expect(onCancel).not.toHaveBeenCalled()
    unmount()
  })

  it('문서 스크롤을 잠갔다가 되돌린다', () => {
    const before = document.body.style.overflow
    const { unmount } = renderWithOpener()

    expect(document.body.style.overflow).toBe('hidden')

    unmount()

    expect(document.body.style.overflow).toBe(before)
  })

  it('닫으면 호출점으로 포커스가 돌아온다', () => {
    const { opener, unmount } = renderWithOpener()
    expect(opener).not.toHaveFocus()

    unmount()

    /*
     * 되돌리지 않으면 키보드 사용자는 문서 처음으로 떨어지고 방금 어디에 있었는지 다시 찾아야
     * 한다. 호출점이 사라진 경우(해제 성공)의 복구는 화면이 결과 문장으로 옮겨 받는다.
     */
    expect(opener).toHaveFocus()
  })

  it('돌려주지 말라고 하면 호출점으로 포커스를 옮기지 않는다', () => {
    const { opener, unmount } = renderWithOpener({ restoreFocus: false })

    unmount()

    /*
     * 해제에 성공한 경우다. 호출점 버튼은 목록이 갱신되며 곧 사라지므로, 여기서 포커스를 얹으면
     * 그 직후 `<body>`로 떨어진다 — 화면이 결과 문장으로 옮겨 받는 것이 그 자리다.
     */
    expect(opener).not.toHaveFocus()
    unmount()
  })

  it('`StrictMode`가 effect를 다시 돌려도 `Esc`로 닫으면 호출점으로 돌아온다', () => {
    const opener = focusedOpener()
    /*
     * 앱의 진입점(`main.tsx`)이 `StrictMode`로 감싼다. 개발 빌드는 열린 effect를 정리했다가 다시 돌리고,
     * 그때 포커스는 이미 취소에 있다. 호출점을 다시 기록하면 돌려줄 곳이 대화상자 자신의 버튼이 된다.
     */
    function Screen(): ReactElement | null {
      const [open, setOpen] = useState(true)
      return open ? (
        <UnlinkDialog
          onCancel={() => {
            setOpen(false)
          }}
          onConfirm={vi.fn()}
          pending={false}
          provider="naver"
          restoreFocus
        />
      ) : null
    }
    render(
      <StrictMode>
        <Screen />
      </StrictMode>,
    )
    expect(
      screen.getByRole('button', { name: authContent.settings.unlinkDialog.cancel }),
    ).toHaveFocus()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  it('화면이 같은 커밋에서 결과 문장으로 포커스를 옮겨 받으면 호출점으로 되돌리지 않는다', () => {
    const opener = focusedOpener()
    /*
     * 해제 성공이 대화상자를 닫는 커밋이다. 대화상자는 그 커밋에서 바로 사라지므로 마지막 렌더의
     * `restoreFocus`는 아직 참이고, 화면은 자기 layout effect에서 결과 문장으로 포커스를 옮긴다
     * (`SettingsScreen`). 복귀는 포커스가 갈 곳을 잃었을 때만이다 — 아니면 그 문장에서 포커스를 빼앗는다.
     */
    function Screen(): ReactElement {
      const [open, setOpen] = useState(true)
      const [done, setDone] = useState(false)
      const notice = useRef<HTMLParagraphElement>(null)
      useLayoutEffect(() => {
        if (done) notice.current?.focus()
      }, [done])
      return (
        <>
          <p ref={notice} tabIndex={-1}>
            {done ? authContent.notices.unlinkSucceeded : ''}
          </p>
          {open ? (
            <UnlinkDialog
              onCancel={vi.fn()}
              onConfirm={() => {
                setOpen(false)
                setDone(true)
              }}
              pending={false}
              provider="naver"
              restoreFocus
            />
          ) : null}
        </>
      )
    }
    render(<Screen />)

    fireEvent.click(screen.getByRole('button', { name: authContent.settings.unlinkDialog.confirm }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText(authContent.notices.unlinkSucceeded)).toHaveFocus()
    expect(opener).not.toHaveFocus()
  })

  it('진행 중에는 두 조작이 모두 막히고 탭 순서에는 남는다', () => {
    const { onCancel, onConfirm, unmount } = renderWithOpener({ pending: true })
    const cancel = screen.getByRole('button', { name: authContent.settings.unlinkDialog.cancel })
    const confirm = screen.getByRole('button', { name: authContent.settings.unlinkDialog.confirm })

    expect(cancel).toHaveAttribute('aria-disabled', 'true')
    expect(confirm).toHaveAttribute('aria-disabled', 'true')
    // `disabled`가 아니다. 그 속성은 방금 이 버튼을 실행한 키보드 사용자의 포커스를 빼앗는다.
    expect(cancel).toBeEnabled()
    expect(confirm).toBeEnabled()

    fireEvent.click(cancel)
    fireEvent.click(confirm)

    expect(onCancel).not.toHaveBeenCalled()
    expect(onConfirm).not.toHaveBeenCalled()
    unmount()
  })

  it('저장소에 아무것도 남기지 않는다', () => {
    const { unmount } = renderWithOpener()

    expect(getObservedStorageEntries()).toEqual([])
    unmount()
  })

  it('금융값도 외부 식별자도 담지 않는다', () => {
    const { unmount } = renderWithOpener()

    const rendered = screen.getByRole('dialog').textContent

    expect(rendered).not.toMatch(/\d+(\.\d+)?\s*%/)
    expect(rendered).not.toContain('http')
    unmount()
  })
})
