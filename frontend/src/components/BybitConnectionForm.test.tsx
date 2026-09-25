import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { connectionContent, connectionFormCopyStrings } from '../content/connection'
import { type SubmitOutcome } from '../session/useBybitConnection'
import { getObservedRequests, getObservedStorageEntries } from '../test/setup'
import { BybitConnectionForm } from './BybitConnectionForm'

/*
 * 자격정보 입력 폼의 계약(Story 2.1).
 *
 * `secure-input-form`이 요구하는 것 여섯을 본다 — 영구 라벨, 도움말, 필드 오류, 오류 요약,
 * 붙여넣기 허용, 그리고 실패 시 Secret만 지우고 나머지 입력과 오류 설명을 유지한 채 포커스를
 * 비밀 필드로 보내는 것.
 *
 * 네트워크를 쓰지 않는다. 이 컴포넌트는 제출을 부모가 준 함수 하나에 넘기며, 그 함수가 실제로
 * 무엇을 보내는지는 화면 시험이 본다.
 */

const CONFIRMATION = '읽기 전용 Bybit 연결을 만듭니다. 확인 코드 abc123'

/**
 * 이 시험이 쓰는 값 둘. **실행 시점에 만든다.**
 *
 * 자격정보 모양 리터럴을 시험 fixture에 두지 않는다는 규율이 여기에도 그대로 적용된다 — 값이
 * 가짜여도 gitleaks가 잡고, 그때 규칙을 완화하고 싶어진다.
 */
const API_KEY = `k-${Math.random().toString(36).slice(2)}`

const API_SECRET = `s-${Math.random().toString(36).slice(2)}`

function renderForm(
  overrides: Partial<{
    pending: boolean
    onSubmit: (apiKey: string, apiSecret: string) => Promise<SubmitOutcome>
    onCancel: () => void
    rejection: string | null
  }> = {},
): {
  onSubmit: (apiKey: string, apiSecret: string) => Promise<SubmitOutcome>
  onCancel: () => void
} {
  const onSubmit = overrides.onSubmit ?? vi.fn(() => Promise.resolve<SubmitOutcome>('connected'))
  const onCancel = overrides.onCancel ?? vi.fn()
  render(
    <BybitConnectionForm
      confirmation={CONFIRMATION}
      onCancel={onCancel}
      onSubmit={onSubmit}
      pending={overrides.pending ?? false}
      rejection={overrides.rejection ?? null}
    />,
  )
  return { onSubmit, onCancel }
}

function secretField(): HTMLInputElement {
  return screen.getByLabelText<HTMLInputElement>(connectionContent.form.apiSecretLabel)
}

function keyField(): HTMLInputElement {
  return screen.getByLabelText<HTMLInputElement>(connectionContent.form.apiKeyLabel)
}

describe('BybitConnectionForm', () => {
  it('영구 라벨과 도움말을 렌더하고 확인 문구를 그대로 보여 준다', () => {
    renderForm()

    const rendered = document.body.textContent
    for (const copy of connectionFormCopyStrings) {
      expect(rendered).toContain(copy)
    }
    // 확인 문구는 서버가 만든 값이며 화면이 그대로 렌더한다.
    expect(rendered).toContain(CONFIRMATION)

    // 라벨이 placeholder가 아니라 요소다. 값이 들어와도 사라지지 않는다.
    expect(keyField()).toBeInTheDocument()
    expect(secretField()).toBeInTheDocument()
    expect(keyField()).not.toHaveAttribute('placeholder')
    expect(secretField()).not.toHaveAttribute('placeholder')
  })

  it('도움말이 필드에 연결돼 있고 Secret은 가려진 채 자동 저장되지 않는다', () => {
    renderForm()

    /*
     * 도움말이 필드에 **연결돼** 있어야 한다. 화면 어딘가에 문장이 있는 것만으로는 보조기술이
     * 그 둘의 관계를 모른다.
     */
    const help = screen.getByText(connectionContent.form.apiSecretHelp)
    expect(secretField().getAttribute('aria-describedby')?.split(' ')).toContain(help.id)

    expect(secretField().type).toBe('password')
    expect(secretField()).toHaveAttribute('autocomplete', 'off')
    expect(keyField()).toHaveAttribute('autocomplete', 'off')
  })

  it('빈 입력은 제출하지 않고 오류 요약·필드 오류를 내며 포커스를 비밀 필드로 보낸다', () => {
    const onSubmit = vi.fn(() => Promise.resolve<SubmitOutcome>('connected'))
    renderForm({ onSubmit })

    fireEvent.click(screen.getByRole('button', { name: connectionContent.form.submit }))

    expect(onSubmit).not.toHaveBeenCalled()
    const summary = screen.getByRole('alert')
    expect(summary.textContent).toContain(connectionContent.form.errorSummaryHeading)
    expect(summary.textContent).toContain(connectionContent.form.apiKeyRequired)
    expect(summary.textContent).toContain(connectionContent.form.apiSecretRequired)
    expect(keyField()).toHaveAttribute('aria-invalid', 'true')
    expect(secretField()).toHaveAttribute('aria-invalid', 'true')
    expect(secretField()).toHaveFocus()
  })

  it('제출은 다듬은 API Key와 원본 Secret을 넘긴다', async () => {
    const onSubmit = vi.fn(() => Promise.resolve<SubmitOutcome>('connected'))
    renderForm({ onSubmit })

    fireEvent.change(keyField(), { target: { value: `  ${API_KEY}  ` } })
    fireEvent.change(secretField(), { target: { value: ` ${API_SECRET} ` } })
    fireEvent.click(screen.getByRole('button', { name: connectionContent.form.submit }))

    // Secret은 다듬지 않는다. 양 끝 공백이 값의 일부일 수 있다.
    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledWith(API_KEY, ` ${API_SECRET} `)
    })
  })

  it('실패하면 Secret만 지우고 API Key와 오류 설명은 유지하며 포커스를 비밀 필드로 보낸다', async () => {
    const onSubmit = vi.fn(() => Promise.resolve<SubmitOutcome>('rejected'))
    renderForm({ onSubmit, rejection: '거절 문장' })

    fireEvent.change(keyField(), { target: { value: API_KEY } })
    fireEvent.change(secretField(), { target: { value: API_SECRET } })
    fireEvent.click(screen.getByRole('button', { name: connectionContent.form.submit }))
    await screen.findByText(connectionContent.form.apiSecretCleared)

    expect(secretField().value).toBe('')
    expect(keyField().value).toBe(API_KEY)
    expect(screen.getByRole('alert').textContent).toContain('거절 문장')
    expect(secretField()).toHaveFocus()
  })

  it('성공하면 두 값을 모두 지운다', async () => {
    const onSubmit = vi.fn(() => Promise.resolve<SubmitOutcome>('connected'))
    renderForm({ onSubmit })

    fireEvent.change(keyField(), { target: { value: API_KEY } })
    fireEvent.change(secretField(), { target: { value: API_SECRET } })
    fireEvent.click(screen.getByRole('button', { name: connectionContent.form.submit }))

    await waitFor(() => {
      expect(secretField().value).toBe('')
    })
    expect(keyField().value).toBe('')
  })

  it('붙여넣기를 막지 않는다', () => {
    renderForm()

    /*
     * 붙여넣기 차단은 `paste` 이벤트의 기본 동작을 막는 것으로만 성립한다. 막는 코드가 없다는
     * 사실을 "핸들러가 없다"로 단정하면 상위 요소의 핸들러를 놓치므로, 실제 이벤트를 보내고
     * 기본 동작이 살아 있는지를 본다.
     */
    const pasted = fireEvent.paste(secretField())

    expect(pasted).toBe(true)
  })

  it('진행 중에는 두 조작이 눌리지 않지만 탭 순서에 남는다', () => {
    const onCancel = vi.fn()
    renderForm({ onCancel, pending: true })

    const cancel = screen.getByRole('button', { name: connectionContent.form.cancel })
    fireEvent.click(cancel)

    expect(onCancel).not.toHaveBeenCalled()
    // `aria-disabled`이지 `disabled`가 아니다. 포커스를 잃지 않는다.
    expect(cancel).toHaveAttribute('aria-disabled', 'true')
    expect(cancel).not.toBeDisabled()
  })

  it('요청도 브라우저 저장소 기록도 남기지 않는다', () => {
    renderForm()

    fireEvent.change(keyField(), { target: { value: API_KEY } })
    fireEvent.change(secretField(), { target: { value: API_SECRET } })

    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
  })
})
