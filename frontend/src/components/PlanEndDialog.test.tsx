import { fireEvent, render, screen, within } from '@testing-library/react'
import { StrictMode, useLayoutEffect, useRef, useState, type ReactElement } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import type { PlanEndDialogView } from '../session/usePlanEnd'

import { planEndContent, planEndCopyStrings } from '../content/planEnd'
import { planResultContent } from '../content/planResult'
import { formatSeoulTime } from '../format/decimal'
import { getObservedRequests, getObservedStorageEntries } from '../test/setup'
import { PlanEndDialog } from './PlanEndDialog'

const copy = planEndContent.dialog

/** The wording the dashboard never uses (the panel and control tests share it). */
const FORBIDDEN =
  /실시간|정확|보장|성공|실패|축하|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치/

const START = '2026-09-25T00:00:00Z'
const END = '2026-10-25T04:00:00Z'

/* The buttons that opened a dialog: removed after the test, so focus returned to them can be observed first. */
const openers: HTMLButtonElement[] = []

afterEach(() => {
  for (const opener of openers.splice(0)) opener.remove()
})

function view(overrides: Partial<PlanEndDialogView> = {}): PlanEndDialogView {
  return {
    reason: 'USER_REQUEST',
    startAt: START,
    endAt: END,
    confirming: false,
    uncertain: false,
    ...overrides,
  }
}

function renderWithOpener(
  dialog: PlanEndDialogView = view(),
  restoreFocus = true,
): {
  readonly onCancel: () => void
  readonly onConfirm: () => void
  readonly opener: HTMLButtonElement
  readonly unmount: () => void
} {
  const onCancel = vi.fn()
  const onConfirm = vi.fn()
  const opener = document.createElement('button')
  opener.textContent = planEndContent.area.action
  document.body.append(opener)
  openers.push(opener)
  opener.focus()
  expect(opener).toHaveFocus()
  const { unmount } = render(
    <PlanEndDialog
      dialog={dialog}
      onCancel={onCancel}
      onConfirm={onConfirm}
      restoreFocus={restoreFocus}
    />,
  )
  return { onCancel, onConfirm, opener, unmount }
}

function cancelButton(): HTMLElement {
  return screen.getByRole('button', { name: copy.cancel })
}

function confirmButton(): HTMLElement {
  return screen.getByRole('button', { name: copy.confirm })
}

describe('PlanEndDialog', () => {
  it('names the target and the result, then the period, the irreversible result, what the server decides, what stays and what follows', () => {
    const { unmount } = renderWithOpener()
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName(copy.titles.USER_REQUEST)
    const period = `${copy.periodLabel}: ${formatSeoulTime(START, planResultContent.timeUnavailable)} – ${formatSeoulTime(END, planResultContent.timeUnavailable)} · ${planResultContent.timeZone}`
    // The whole description in order, to the letter.
    expect(dialog).toHaveAccessibleDescription(
      [
        period,
        copy.irreversible,
        copy.endTime,
        copy.lastNormal,
        copy.kept,
        copy.after,
        copy.distinct,
      ].join(' '),
    )
    // The long-gap paragraph is said only for the long-gap choice.
    expect(dialog).not.toHaveTextContent(copy.longGap)
    // Machine values of the period, never spliced.
    // eslint-disable-next-line testing-library/no-node-access -- 기계 값은 `time` 요소의 속성에만 있다.
    expect([...dialog.querySelectorAll('time')].map((time) => time.dateTime)).toEqual([START, END])
    unmount()
  })

  it('for the long-gap choice says the gap is not collected and the comparison cannot be calculated', () => {
    const { unmount } = renderWithOpener(view({ reason: 'LONG_GAP_NEW_PLAN' }))
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAccessibleName(copy.titles.LONG_GAP_NEW_PLAN)
    expect(dialog).toHaveTextContent(copy.longGap)
    expect(copy.longGap).toContain(
      planEndContent.record.completions.UNAVAILABLE_DATA_GAP ?? 'missing',
    )
    expect(dialog).toHaveTextContent(copy.after)
    unmount()
  })

  it('has its default focus on cancel, which comes first in the document', () => {
    const { unmount } = renderWithOpener()
    expect(cancelButton()).toHaveFocus()
    expect(
      within(screen.getByRole('dialog'))
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual([copy.cancel, copy.confirm])
    unmount()
  })

  it('traps focus inside, and pulls focus that left back in', () => {
    const { unmount } = renderWithOpener()
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

  it('`Esc` cancels and asks nothing', () => {
    const { onCancel, onConfirm, unmount } = renderWithOpener()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
    expect(getObservedRequests()).toEqual([])
    unmount()
  })

  it('cancel cancels; confirm confirms', () => {
    const { onCancel, onConfirm, unmount } = renderWithOpener()
    fireEvent.click(cancelButton())
    expect(onCancel).toHaveBeenCalledTimes(1)
    fireEvent.click(confirmButton())
    expect(onConfirm).toHaveBeenCalledTimes(1)
    unmount()
  })

  it('while confirming: both buttons blocked but in the tab order, a progress line on confirm, and `Esc` does nothing', () => {
    const { onCancel, onConfirm, unmount } = renderWithOpener(view({ confirming: true }))
    for (const button of [cancelButton(), confirmButton()]) {
      expect(button).toHaveAttribute('aria-disabled', 'true')
      expect(button).toBeEnabled()
    }
    expect(confirmButton()).toHaveAccessibleDescription(copy.confirming)
    fireEvent.click(cancelButton())
    fireEvent.click(confirmButton())
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onCancel).not.toHaveBeenCalled()
    expect(onConfirm).not.toHaveBeenCalled()
    // No live region of its own: the screen's single one is behind the inert background.
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 live region은 속성으로만 찾을 수 있다.
    expect(document.querySelector('[aria-live]')).toBeNull()
    unmount()
  })

  it('after a confirmation without an answer: stays open and says pressing again sends the same request', () => {
    const { onConfirm, unmount } = renderWithOpener(view({ uncertain: true }))
    expect(confirmButton()).toHaveAccessibleDescription(copy.uncertain)
    expect(confirmButton()).toHaveAttribute('aria-disabled', 'false')
    fireEvent.click(confirmButton())
    expect(onConfirm).toHaveBeenCalledTimes(1)
    unmount()
  })

  it('returns focus to the opener on close', () => {
    const { opener, unmount } = renderWithOpener()
    unmount()
    expect(opener).toHaveFocus()
  })

  it('does not return focus when told not to (a confirmation in flight: the screen moves it itself)', () => {
    const { opener, unmount } = renderWithOpener(view(), false)
    unmount()
    expect(opener).not.toHaveFocus()
  })

  it.each([
    ['USER_REQUEST', planEndContent.area.actionId],
    ['LONG_GAP_NEW_PLAN', planEndContent.newPlanActionId],
  ] as const)(
    'F4: mounted again without its opener (%s), closing returns focus to the button of its reason',
    (reason, id) => {
      const fallback = document.createElement('button')
      fallback.id = id
      document.body.append(fallback)
      openers.push(fallback)
      // A remount after a session re-check: nothing held focus, so the captured opener is the body.
      fallback.focus()
      fallback.blur()
      expect(document.body).toHaveFocus()
      const { unmount } = render(
        <PlanEndDialog
          dialog={view({ reason })}
          onCancel={vi.fn()}
          onConfirm={vi.fn()}
          restoreFocus
        />,
      )
      unmount()
      expect(fallback).toHaveFocus()
    },
  )

  it('F4: an opener that left the document is replaced by the button of the reason', () => {
    const fallback = document.createElement('button')
    fallback.id = planEndContent.area.actionId
    document.body.append(fallback)
    openers.push(fallback)
    const { opener, unmount } = renderWithOpener()
    opener.remove()
    unmount()
    expect(fallback).toHaveFocus()
  })

  it('under `StrictMode` (a development build) `Esc` still returns focus to the opener', () => {
    const opener = document.createElement('button')
    opener.textContent = planEndContent.area.action
    document.body.append(opener)
    openers.push(opener)
    opener.focus()
    /*
     * `StrictMode` cleans the opening effect up and runs it again, after it has moved focus to cancel. The opener is
     * recorded once, so the dialog's own button never takes its place (it is gone after the close).
     */
    function Screen(): ReactElement | null {
      const [open, setOpen] = useState(true)
      return open ? (
        <PlanEndDialog
          dialog={view()}
          onCancel={() => {
            setOpen(false)
          }}
          onConfirm={vi.fn()}
          restoreFocus
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

  it('leaves focus where the screen moved it in the same commit (a read that showed another state)', () => {
    const opener = document.createElement('button')
    opener.textContent = planEndContent.area.action
    document.body.append(opener)
    openers.push(opener)
    /*
     * A read that shows another state closes the dialog and sends focus to the end area's notice in that commit's
     * layout effect, while the dialog's last render still asked for the return. The return is only for focus that has
     * nowhere to be; otherwise it would take focus away from the notice.
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
            {open ? '' : planEndContent.status.cancelled}
          </p>
          {open ? (
            <PlanEndDialog
              dialog={view()}
              onCancel={vi.fn()}
              onConfirm={() => {
                setOpen(false)
              }}
              restoreFocus
            />
          ) : null}
        </>
      )
    }
    opener.focus()
    render(<Screen />)

    fireEvent.click(confirmButton())

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText(planEndContent.status.cancelled)).toHaveFocus()
    expect(opener).not.toHaveFocus()
  })

  it('locks the document scroll and gives it back', () => {
    const before = document.body.style.overflow
    const { unmount } = renderWithOpener()
    expect(document.body.style.overflow).toBe('hidden')
    unmount()
    expect(document.body.style.overflow).toBe(before)
  })

  it('never calls the end a success, failure or completion, and stores nothing', () => {
    for (const reason of ['USER_REQUEST', 'LONG_GAP_NEW_PLAN'] as const) {
      const { unmount } = renderWithOpener(view({ reason, uncertain: true }))
      const text = screen.getByRole('dialog').textContent
      expect(text).not.toMatch(FORBIDDEN)
      // "완료" appears only as the name of the comparison, never as what the end is.
      expect(text.replaceAll(planEndContent.record.completion, '')).not.toContain('완료')
      unmount()
    }
    for (const text of planEndCopyStrings) expect(text).not.toMatch(FORBIDDEN)
    expect(getObservedStorageEntries()).toEqual([])
  })
})
