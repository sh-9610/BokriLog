import { fireEvent, render, screen } from '@testing-library/react'
import {
  createRef,
  StrictMode,
  useLayoutEffect,
  useRef,
  useState,
  type ReactElement,
  type RefObject,
} from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { onBackdropMouseDown, useConfirmationDialog } from './useConfirmationDialog'

/*
 * 확인 대화상자의 메커니즘 하나.
 *
 * 세 대화상자(`UnlinkDialog`·`PlanEndDialog`·`ConnectionRevokeDialog`)가 이 훅에 위임하는 넷 — 기본
 * 포커스, 포커스 가둠, `Esc`, 호출점 복귀 — 과 문서 스크롤 잠금·겹 클릭 방어를 마크업 없이 본다. 각
 * 대화상자의 시험은 자기 마크업과 카피를 보고, 메커니즘이 갈리는 자리(대체 대상, `restoreFocus`의 뜻)는
 * 매개변수로 여기서 고정한다.
 */

const CANCEL = '취소'
const CONFIRM = '확인'
const FALLBACK_ID = 'confirmation-dialog-test-fallback'

/** 호출점 버튼들. 시험이 끝난 뒤에 지운다 — 언마운트 직후에 지우면 되돌아온 포커스를 볼 수 없다. */
const openers: HTMLButtonElement[] = []

afterEach(() => {
  for (const opener of openers.splice(0)) {
    opener.remove()
  }
})

/** 문서에 붙인 호출점 버튼 하나. 포커스를 얹은 채로 돌려준다. */
function focusedOpener(): HTMLButtonElement {
  const opener = document.createElement('button')
  opener.textContent = '열기'
  document.body.append(opener)
  openers.push(opener)
  opener.focus()
  expect(opener).toHaveFocus()
  return opener
}

/** 대체 대상 버튼 하나. 호출점을 모를 때 복귀가 여기로 온다. */
function fallbackButton(): HTMLButtonElement {
  const fallback = document.createElement('button')
  fallback.id = FALLBACK_ID
  fallback.textContent = '대체'
  document.body.append(fallback)
  openers.push(fallback)
  return fallback
}

interface HostProps {
  readonly fallbackTargetId?: string
  readonly onCancel?: () => void
  readonly pending?: boolean
  readonly restoreFocus?: boolean
}

/** 훅을 쓰는 최소 대화상자. 버튼 둘뿐이고 카피는 시험의 것이다. */
function Host({
  fallbackTargetId,
  onCancel = vi.fn(),
  pending = false,
  restoreFocus = true,
}: HostProps): ReactElement {
  const surface = useRef<HTMLDivElement>(null)
  const cancelButton = useRef<HTMLButtonElement>(null)
  useConfirmationDialog({
    surface,
    cancelButton,
    isPending: pending,
    onCancel,
    restoreFocus,
    fallbackTargetId,
  })
  return (
    <div data-testid="backdrop" onMouseDown={onBackdropMouseDown}>
      <div aria-label="시험 대화상자" aria-modal="true" ref={surface} role="dialog">
        <button aria-disabled={pending} ref={cancelButton} type="button">
          {CANCEL}
        </button>
        <button aria-disabled={pending} type="button">
          {CONFIRM}
        </button>
      </div>
    </div>
  )
}

/**
 * ref 상자를 밖에서 받는 호출자. 시험이 렌더마다 새 상자를 넘기면 여는 effect가 다시 돈다 — 호출점과
 * 스크롤 복원값이 첫 실행에만 기록되는지 보는 자리다.
 */
function ChurningHost({
  cancelButton,
  surface,
}: {
  readonly cancelButton: RefObject<HTMLButtonElement | null>
  readonly surface: RefObject<HTMLDivElement | null>
}): ReactElement {
  useConfirmationDialog({
    surface,
    cancelButton,
    isPending: false,
    onCancel: vi.fn(),
    restoreFocus: true,
  })
  return (
    <div onMouseDown={onBackdropMouseDown}>
      <div aria-label="시험 대화상자" aria-modal="true" ref={surface} role="dialog">
        <button ref={cancelButton} type="button">
          {CANCEL}
        </button>
        <button type="button">{CONFIRM}</button>
      </div>
    </div>
  )
}

function cancelButton(): HTMLElement {
  return screen.getByRole('button', { name: CANCEL })
}

function confirmButton(): HTMLElement {
  return screen.getByRole('button', { name: CONFIRM })
}

describe('useConfirmationDialog', () => {
  it('열리는 커밋에서 취소에 기본 포커스를 두고 문서 스크롤을 잠갔다가 되돌린다', () => {
    focusedOpener()
    const before = document.body.style.overflow
    const { unmount } = render(<Host />)

    expect(cancelButton()).toHaveFocus()
    expect(document.body.style.overflow).toBe('hidden')

    unmount()

    expect(document.body.style.overflow).toBe(before)
  })

  it('포커스를 대화상자 안에 가두고 밖으로 나간 포커스를 끌어온다', () => {
    focusedOpener()
    const { unmount } = render(<Host />)

    confirmButton().focus()
    fireEvent.keyDown(confirmButton(), { key: 'Tab' })
    expect(cancelButton()).toHaveFocus()
    fireEvent.keyDown(cancelButton(), { key: 'Tab', shiftKey: true })
    expect(confirmButton()).toHaveFocus()

    confirmButton().blur()
    expect(document.body).toHaveFocus()
    fireEvent.keyDown(document, { key: 'Tab' })
    expect(cancelButton()).toHaveFocus()
    unmount()
  })

  it('`Esc`가 취소를 부르고, 진행 중에는 부르지 않는다', () => {
    focusedOpener()
    const onCancel = vi.fn()
    const { rerender, unmount } = render(<Host onCancel={onCancel} />)

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(1)

    // 상자는 layout effect가 채우므로 다시 그린 커밋의 `pending`을 같은 처리기가 본다.
    rerender(<Host onCancel={onCancel} pending />)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(1)
    unmount()
  })

  it('겹의 조작이 아닌 자리를 눌러도 포커스가 `<body>`로 떨어지지 않는다', () => {
    focusedOpener()
    const { unmount } = render(<Host />)

    // `fireEvent`는 기본 동작이 막혔으면 거짓을 돌려준다.
    expect(fireEvent.mouseDown(screen.getByTestId('backdrop'))).toBe(false)
    expect(fireEvent.mouseDown(confirmButton())).toBe(true)
    unmount()
  })

  it('닫으면 호출점으로 포커스가 돌아온다', () => {
    const opener = focusedOpener()
    const { unmount } = render(<Host />)
    expect(opener).not.toHaveFocus()

    unmount()

    expect(opener).toHaveFocus()
  })

  it('돌려주지 말라고 하면 호출점으로 포커스를 옮기지 않는다', () => {
    const opener = focusedOpener()
    const { unmount } = render(<Host restoreFocus={false} />)

    unmount()

    expect(opener).not.toHaveFocus()
  })

  it('`StrictMode`가 여는 effect를 다시 돌려도 호출점은 한 번만 기록된다', () => {
    const opener = focusedOpener()
    /*
     * 개발 빌드는 열린 effect를 정리했다가 다시 돌리고, 그때 포커스는 이미 취소에 있다. 호출점을 다시
     * 기록하면 돌려줄 곳이 대화상자 자신의 버튼이 되어 닫힌 뒤 포커스가 `<body>`로 떨어진다.
     */
    function Screen(): ReactElement | null {
      const [open, setOpen] = useState(true)
      return open ? (
        <Host
          onCancel={() => {
            setOpen(false)
          }}
        />
      ) : null
    }
    render(
      <StrictMode>
        <Screen />
      </StrictMode>,
    )
    expect(cancelButton()).toHaveFocus()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(opener).toHaveFocus()
  })

  it('`activeElement`가 `null`이었던 열기에서도 호출점은 한 번만 기록된다', () => {
    /*
     * `??=`였다면 `null`은 "아직 기록하지 않았다"와 같아서 재실행이 다시 기록한다 — 그때의 활성 요소를
     * 호출점으로 삼아 닫힌 뒤 그리로 돌려준다. 재실행 시점의 활성 요소를 문서에 남아 있는 다른 버튼으로
     * 흉내 내어 그 잘못된 복귀를 관측한다(실제로는 대화상자 자신의 취소 버튼이고 닫히며 사라진다).
     */
    const decoy = document.createElement('button')
    decoy.textContent = '재실행 시점의 활성 요소'
    document.body.append(decoy)
    openers.push(decoy)
    // 원래 getter는 프로토타입의 것이다. 흉내 내지 않는 읽기는 그리로 보낸다.
    const real = (): Element | null => Reflect.get(Document.prototype, 'activeElement', document)
    let reads = 0
    const spy = vi.spyOn(document, 'activeElement', 'get').mockImplementation(() => {
      reads += 1
      if (reads === 1) return null
      // 대화상자가 열려 있는 동안의 두 번째 읽기만 흉내 낸다. 닫힌 뒤의 읽기(`lost` 판정)는 실제 값이다.
      if (reads === 2 && screen.queryByRole('dialog') !== null) return decoy
      return real()
    })
    function Screen(): ReactElement | null {
      const [open, setOpen] = useState(true)
      return open ? (
        <Host
          onCancel={() => {
            setOpen(false)
          }}
        />
      ) : null
    }
    render(
      <StrictMode>
        <Screen />
      </StrictMode>,
    )
    expect(cancelButton()).toHaveFocus()

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(decoy).not.toHaveFocus()
    spy.mockRestore()
  })

  it('호출자가 렌더마다 새 ref 상자를 넘겨도 스크롤 복원값은 첫 실행의 값이다', () => {
    focusedOpener()
    document.body.style.overflow = 'auto'
    const { rerender, unmount } = render(
      <ChurningHost cancelButton={createRef()} surface={createRef()} />,
    )
    expect(document.body.style.overflow).toBe('hidden')

    rerender(<ChurningHost cancelButton={createRef()} surface={createRef()} />)
    expect(document.body.style.overflow).toBe('hidden')

    unmount()

    expect(document.body.style.overflow).toBe('auto')
    document.body.style.overflow = ''
  })

  it('호출점을 모르면 대체 대상으로 돌려준다', () => {
    const fallback = fallbackButton()
    // 세션 재확인 뒤의 재마운트: 아무것도 포커스를 쥐고 있지 않아 기록된 호출점이 `<body>`다.
    fallback.focus()
    fallback.blur()
    expect(document.body).toHaveFocus()
    const { unmount } = render(<Host fallbackTargetId={FALLBACK_ID} />)

    unmount()

    expect(fallback).toHaveFocus()
  })

  it('문서를 떠난 호출점은 대체 대상으로 갈음한다', () => {
    const fallback = fallbackButton()
    const opener = focusedOpener()
    const { unmount } = render(<Host fallbackTargetId={FALLBACK_ID} />)
    opener.remove()

    unmount()

    expect(fallback).toHaveFocus()
  })

  it('호출점도 대체 대상도 없으면 돌려주지 않는다', () => {
    const fallback = fallbackButton()
    fallback.focus()
    fallback.blur()
    expect(document.body).toHaveFocus()
    const { unmount } = render(<Host />)

    unmount()

    expect(document.body).toHaveFocus()
    expect(fallback).not.toHaveFocus()
  })

  it('화면이 같은 커밋에서 포커스를 옮겨 받았으면 되돌리지 않는다', () => {
    const opener = focusedOpener()
    /*
     * 응답이 대화상자를 닫는 커밋이다. 대화상자는 그 커밋에서 바로 사라지므로 마지막 렌더의
     * `restoreFocus`는 아직 참이고, 화면은 자기 layout effect에서 결과 문장으로 포커스를 옮긴다.
     * 복귀는 포커스가 갈 곳을 잃었을 때만이다 — 아니면 그 문장에서 포커스를 빼앗는다.
     */
    function Screen(): ReactElement {
      const [open, setOpen] = useState(true)
      const notice = useRef<HTMLParagraphElement>(null)
      useLayoutEffect(() => {
        if (!open) notice.current?.focus()
      }, [open])
      return (
        <>
          <p ref={notice} tabIndex={-1}>
            {open ? '' : '결과'}
          </p>
          {open ? (
            <Host
              onCancel={() => {
                setOpen(false)
              }}
            />
          ) : null}
        </>
      )
    }
    render(<Screen />)

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('결과')).toHaveFocus()
    expect(opener).not.toHaveFocus()
  })
})
