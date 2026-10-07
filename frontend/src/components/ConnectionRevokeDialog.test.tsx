import { fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { RevocationDialogView } from '../session/useBybitConnection'

import { revocationContent, revocationDialogCopyStrings } from '../content/connection'
import { getObservedStorageEntries } from '../test/setup'
import { ConnectionRevokeDialog } from './ConnectionRevokeDialog'

/*
 * Bybit 연결 해제의 확인 대화상자.
 *
 * 이 파일이 보는 것은 이 대화상자의 몫이다 — 마크업과 카피, 대화상자 안의 status 하나, `restoreFocus`의 뜻
 * (해제됨이면 화면이 결과 문장으로 옮긴다), 호출점을 모를 때의 대체 대상(`revocationContent.buttonId`).
 * 기본 포커스·가둠·`Esc`·스크롤 잠금·호출점 복귀 같은 메커니즘은 `useConfirmationDialog.test.tsx`가 본다.
 */

const copy = revocationContent.dialog

/* 대화상자를 연 버튼들. 시험이 끝난 뒤에 지운다 — 되돌아온 포커스를 먼저 봐야 한다. */
const openers: HTMLButtonElement[] = []

afterEach(() => {
  for (const opener of openers.splice(0)) opener.remove()
})

function view(overrides: Partial<RevocationDialogView> = {}): RevocationDialogView {
  return {
    connectionId: '0b9c3f7e-1d2a-4c5b-8e6f-7a8b9c0d1e2f',
    confirmation: 'bybit-connection-revoke|0b9c3f7e-1d2a-4c5b-8e6f-7a8b9c0d1e2f',
    confirming: false,
    uncertain: false,
    ...overrides,
  }
}

function renderDialog(
  dialog: RevocationDialogView = view(),
  restoreFocus = true,
): {
  readonly onCancel: () => void
  readonly onConfirm: () => void
  readonly rerender: (dialog: RevocationDialogView) => void
  readonly unmount: () => void
} {
  const onCancel = vi.fn()
  const onConfirm = vi.fn()
  const { rerender, unmount } = render(
    <ConnectionRevokeDialog
      dialog={dialog}
      onCancel={onCancel}
      onConfirm={onConfirm}
      restoreFocus={restoreFocus}
    />,
  )
  return {
    onCancel,
    onConfirm,
    rerender: (next) => {
      rerender(
        <ConnectionRevokeDialog
          dialog={next}
          onCancel={onCancel}
          onConfirm={onConfirm}
          restoreFocus={restoreFocus}
        />,
      )
    },
    unmount,
  }
}

/** 문서에 붙인 호출점 버튼 하나. 포커스를 얹은 채로 돌려준다. */
function focusedOpener(): HTMLButtonElement {
  const opener = document.createElement('button')
  opener.textContent = revocationContent.start
  document.body.append(opener)
  openers.push(opener)
  opener.focus()
  expect(opener).toHaveFocus()
  return opener
}

function cancelButton(): HTMLElement {
  return screen.getByRole('button', { name: copy.cancel })
}

function confirmButton(): HTMLElement {
  return screen.getByRole('button', { name: copy.confirm })
}

describe('ConnectionRevokeDialog', () => {
  it('제목이 대상과 결과를 말하고 설명이 즉시 삭제·작업 취소·이후·보존·원격 키·재연결을 차례로 말한다', () => {
    const { unmount } = renderDialog()
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName(copy.title)
    expect(dialog).toHaveAccessibleDescription(
      [
        copy.credentials,
        copy.jobs,
        copy.future,
        copy.kept,
        revocationContent.remoteKey,
        copy.reconnect,
      ].join(' '),
    )
    for (const text of revocationDialogCopyStrings) expect(dialog.textContent).toContain(text)
    // 확인 문구·연결 식별자는 그리지 않는다.
    expect(dialog.textContent).not.toContain(view().confirmation)
    expect(dialog.textContent).not.toContain(view().connectionId)
    unmount()
  })

  it('취소가 문서 순서에서 먼저이고 취소는 취소를, 확인은 확인을 부른다', () => {
    const { onCancel, onConfirm, unmount } = renderDialog()
    expect(
      within(screen.getByRole('dialog'))
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual([copy.cancel, copy.confirm])
    fireEvent.click(cancelButton())
    expect(onCancel).toHaveBeenCalledTimes(1)
    fireEvent.click(confirmButton())
    expect(onConfirm).toHaveBeenCalledTimes(1)
    unmount()
  })

  it('확정 중에는 두 버튼이 막히되 탭 순서에 남고, 진행 문장이 확인을 설명한다', () => {
    const { onCancel, onConfirm, unmount } = renderDialog(view({ confirming: true }))
    for (const button of [cancelButton(), confirmButton()]) {
      expect(button).toHaveAttribute('aria-disabled', 'true')
      expect(button).toBeEnabled()
    }
    expect(confirmButton()).toHaveAccessibleDescription(copy.confirming)
    fireEvent.click(cancelButton())
    fireEvent.click(confirmButton())
    expect(onCancel).not.toHaveBeenCalled()
    expect(onConfirm).not.toHaveBeenCalled()
    unmount()
  })

  it('대화상자 안의 status 하나가 진행과 무응답을 말하고, 무응답 뒤의 확인은 다시 누를 수 있다', () => {
    const { rerender, unmount } = renderDialog()
    const region = within(screen.getByRole('dialog')).getByRole('status')
    expect(region).toHaveAttribute('aria-live', 'polite')
    expect(region).toBeEmptyDOMElement()

    rerender(view({ confirming: true }))
    expect(within(region).getByText(copy.confirming)).toBeInTheDocument()

    rerender(view({ uncertain: true }))
    expect(within(region).getByText(copy.uncertain)).toBeInTheDocument()
    expect(confirmButton()).toHaveAccessibleDescription(copy.uncertain)
    expect(confirmButton()).toHaveAttribute('aria-disabled', 'false')
    expect(screen.getAllByRole('status')).toHaveLength(1)
    unmount()
  })

  it('돌려주지 말라고 하면(해제됨: 화면이 결과 문장으로 옮긴다) 호출점으로 옮기지 않는다', () => {
    const opener = focusedOpener()
    const { unmount } = renderDialog(view(), false)
    unmount()
    expect(opener).not.toHaveFocus()
  })

  it('호출점을 모르면 해제 버튼의 id로 돌려준다', () => {
    const fallback = document.createElement('button')
    fallback.id = revocationContent.buttonId
    fallback.textContent = revocationContent.start
    document.body.append(fallback)
    openers.push(fallback)
    // 세션 재확인 뒤의 재마운트: 아무것도 포커스를 쥐고 있지 않아 기록된 호출점이 `<body>`다.
    fallback.focus()
    fallback.blur()
    expect(document.body).toHaveFocus()
    const { unmount } = renderDialog()
    unmount()
    expect(fallback).toHaveFocus()
  })

  it('저장소에 아무것도 남기지 않는다', () => {
    const { unmount } = renderDialog()
    expect(getObservedStorageEntries()).toEqual([])
    unmount()
  })
})
