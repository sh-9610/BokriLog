import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ACCOUNT_DELETION_PATH,
  ACCOUNT_DELETION_POLICY_VERSION,
  ACCOUNT_DELETION_REFUSAL_CODES,
  ACCOUNT_DELETION_TARGETS,
  accountDeletionContent,
  accountDeletionDialogCopyStrings,
  accountDeletionPanelCopyStrings,
  accountDeletionRefusalMessage,
  accountDeletionTargetSentence,
} from '../content/accountDeletion'
import accountDeletionPolicy from '../content/accountDeletionPolicy.fixture.json'
import { CSRF_HEADER, SETTINGS_PATH } from '../content/auth'
import { FORBIDDEN_COPY_WORDS } from '../content/copyRules'
import { replaceLocation } from '../session/navigation'
import { FIRST_MOUNT_TIMEOUT_MS } from '../test/firstMount'
import { expectReauthGateRejection } from '../test/reauthGate'
import {
  getObservedAccountDeletionRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubAccountDeletionCancel,
  stubAccountDeletionConfirm,
  stubAccountDeletionPrepare,
  stubAccountDeletionState,
  stubConnectionStatusRequest,
  stubLinkStatusRequest,
  stubSessionRequest,
} from '../test/setup'
import { SettingsScreen } from './SettingsScreen'

/*
 * 설정 화면의 계정 삭제 요청(Story 5.1).
 *
 * 화면의 마지막 영역이 서버의 삭제 정책을 구분해 보인다 — 즉시 차단, 삭제하는 운영 데이터, 백업 파기 기한, 법정 분리 보존(없음),
 * 연결 해제와의 차이. `계정 삭제 요청`을 누르면 준비가 허가를 무장한 뒤 확인 대화상자가 열리고, 고정 문구를 그대로 입력하기 전에는
 * 실행 버튼이 `aria-disabled`다. 확정에 성공하면 같은 문서를 다시 요청해 진행 화면으로 간다.
 */

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const CSRF_TOKEN = 'settings-deletion-csrf-token'
const PHRASE = accountDeletionPolicy.confirmation
const REQUESTED_AT = '2026-11-05T01:02:03.456Z'

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function refusal(code: string, status: number): Promise<Response> {
  return Promise.resolve(jsonResponse({ code, message: 'x', correlationId: 'c' }, status))
}

function prepared(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({ policyVersion: ACCOUNT_DELETION_POLICY_VERSION, confirmation: PHRASE }),
  )
}

function requested(): Promise<Response> {
  return Promise.resolve(
    jsonResponse({
      requestId: 'del_abc',
      requestedAt: REQUESTED_AT,
      state: 'DELETION_IN_PROGRESS',
      policyVersion: ACCOUNT_DELETION_POLICY_VERSION,
      // 확정 응답은 언제나 이 고정 진행을 싣는다(Story 5.2).
      progress: { status: 'IN_PROGRESS', failedSteps: [] },
    }),
  )
}

function stateResponse(available: boolean): Promise<Response> {
  return Promise.resolve(
    jsonResponse({
      available,
      policy: {
        version: accountDeletionPolicy.version,
        targets: accountDeletionPolicy.targets,
        backupDestroyDays: accountDeletionPolicy.backupDestroyDays,
        legalRetention: accountDeletionPolicy.legalRetention,
      },
      request: null,
    }),
  )
}

function noContent(): Promise<Response> {
  return Promise.resolve(new Response(null, { status: 204 }))
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  window.history.pushState({}, '', SETTINGS_PATH)
  stubSessionRequest(() =>
    Promise.resolve(
      jsonResponse({ authenticated: true, destination: 'settings', csrfToken: CSRF_TOKEN }),
    ),
  )
  stubLinkStatusRequest(() => Promise.resolve(jsonResponse({ providers: ['google', 'naver'] })))
  stubConnectionStatusRequest(() => Promise.resolve(jsonResponse({ connected: false })))
})

function section(): HTMLElement {
  return screen.getByRole('region', { name: accountDeletionContent.heading })
}

/** 화면의 status region 하나. 대화상자는 페이지의 뒤 형제라 화면의 region이 문서 순서에서 먼저다. */
function status(): HTMLElement {
  const [region] = screen.getAllByRole('status')
  if (region === undefined) throw new Error('no screen status region')
  return region
}

async function loadStartButton(): Promise<HTMLElement> {
  render(<SettingsScreen />)
  return screen.findByRole(
    'button',
    { name: accountDeletionContent.start },
    { timeout: FIRST_MOUNT_TIMEOUT_MS },
  )
}

async function openDialog(): Promise<HTMLElement> {
  const start = await loadStartButton()
  stubAccountDeletionPrepare(prepared)
  fireEvent.click(start)
  return screen.findByRole('dialog', { name: accountDeletionContent.dialog.title })
}

describe('SettingsScreen의 계정 삭제', () => {
  it('화면의 마지막 영역이 정책을 구분해 보이고 실행 버튼을 둔다', async () => {
    await loadStartButton()
    const region = section()
    for (const copy of accountDeletionPanelCopyStrings) {
      expect(region.textContent).toContain(copy)
    }
    expect(region.textContent).toContain(accountDeletionContent.backup(30))
    // 백업 약속의 기준점은 운영 데이터 삭제를 마친 날이고(D-1) 복원 단서를 함께 말한다(D-2). 문장은 공유 표의 `notice`에서 온다.
    expect(region.textContent).toContain('운영 데이터 삭제를 마친 다음 날부터 최대 30일')
    expect(region.textContent).toContain(
      '삭제 요청 기록이 서비스 밖 보관소에 남은 뒤에는 백업으로 복원하더라도 삭제한 계정을 되살리지 않습니다',
    )
    expect(region.textContent).not.toContain('요청일부터')
    expect(accountDeletionContent.backup(30)).toBe(
      accountDeletionPolicy.notice.backup.replace('{days}', '30'),
    )
    expect(ACCOUNT_DELETION_POLICY_VERSION).toBe('ACCOUNT-DELETION-1.1')
    for (const code of accountDeletionPolicy.targets) {
      const sentence = accountDeletionTargetSentence(code)
      expect(sentence).not.toBeNull()
      expect(within(region).getByText(sentence ?? '')).toBeInTheDocument()
    }
    expect(region.textContent).toContain(ACCOUNT_DELETION_POLICY_VERSION)
    // 마지막 영역이다 — 문서 순서에서 다른 어느 영역보다 뒤다.
    const regions = screen.getAllByRole('region')
    expect(regions[regions.length - 1]).toBe(region)
    expect(getObservedAccountDeletionRequests()).toEqual([`GET ${ACCOUNT_DELETION_PATH}`])
    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('production(정책 승인 전)이면 실행 버튼을 열지 않는다', async () => {
    stubAccountDeletionState(() => stateResponse(false))
    render(<SettingsScreen />)
    expect(
      await screen.findByText(accountDeletionContent.closed, undefined, {
        timeout: FIRST_MOUNT_TIMEOUT_MS,
      }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: accountDeletionContent.start })).toBeNull()
  })

  it('모르는 대상 code는 그리지 않고 정책 버전이 다르면 실행하지 않는다', async () => {
    stubAccountDeletionState(() =>
      Promise.resolve(
        jsonResponse({
          available: true,
          policy: {
            version: 'ACCOUNT-DELETION-9.9',
            targets: ['SESSIONS', 'SOMETHING_NEW'],
            backupDestroyDays: 30,
            legalRetention: [],
          },
          request: null,
        }),
      ),
    )
    render(<SettingsScreen />)
    expect(
      await screen.findByText(accountDeletionContent.outdated, undefined, {
        timeout: FIRST_MOUNT_TIMEOUT_MS,
      }),
    ).toBeInTheDocument()
    expect(section().textContent).not.toContain('SOMETHING_NEW')
    expect(screen.queryByRole('button', { name: accountDeletionContent.start })).toBeNull()
  })

  it('준비는 화면이 본 정책 버전과 CSRF를 싣고, 대화상자는 결과를 먼저 말하며 취소가 기본 초점이다', async () => {
    const start = await loadStartButton()
    let csrf: string | null = null
    let body: unknown = 'unset'
    stubAccountDeletionPrepare((init) => {
      csrf = new Headers(init?.headers).get(CSRF_HEADER)
      body = init?.body
      return prepared()
    })
    fireEvent.click(start)

    const dialog = await screen.findByRole('dialog', { name: accountDeletionContent.dialog.title })
    expect(csrf).toBe(CSRF_TOKEN)
    expect(JSON.parse(body as string)).toEqual({ policyVersion: ACCOUNT_DELETION_POLICY_VERSION })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    for (const copy of accountDeletionDialogCopyStrings) {
      expect(dialog.textContent).toContain(copy)
    }
    expect(dialog.textContent).toContain(PHRASE)
    const cancel = within(dialog).getByRole('button', {
      name: accountDeletionContent.dialog.cancel,
    })
    expect(cancel).toHaveFocus()
  })

  it('문구를 그대로 입력하기 전에는 실행하지 않고, 입력하면 같은 키로 확정한 뒤 문서를 다시 요청한다', async () => {
    const dialog = await openDialog()
    const confirm = within(dialog).getByRole('button', {
      name: accountDeletionContent.dialog.confirm,
    })
    const input = within(dialog).getByLabelText(accountDeletionContent.dialog.phraseLabel, {
      exact: false,
    })
    let sent: unknown = null
    let key: string | null = null
    stubAccountDeletionConfirm((init) => {
      sent = JSON.parse(init?.body as string)
      key = new Headers(init?.headers).get('Idempotency-Key')
      return requested()
    })

    expect(confirm).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(confirm)
    fireEvent.change(input, { target: { value: '계정을 삭제' } })
    expect(confirm).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(confirm)
    expect(sent).toBeNull()

    fireEvent.change(input, { target: { value: ` ${PHRASE} ` } })
    expect(confirm).toHaveAttribute('aria-disabled', 'false')
    fireEvent.click(confirm)

    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(SETTINGS_PATH)
    })
    expect(sent).toEqual({
      policyVersion: ACCOUNT_DELETION_POLICY_VERSION,
      confirmation: ` ${PHRASE} `,
    })
    expect(key).toMatch(/^[A-Za-z0-9._:-]{16,128}$/)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('서버의 문구 불일치는 대화상자 안에서 말하고, 응답 없는 확정은 같은 키로 다시 보낸다', async () => {
    const dialog = await openDialog()
    const input = within(dialog).getByLabelText(accountDeletionContent.dialog.phraseLabel, {
      exact: false,
    })
    fireEvent.change(input, { target: { value: PHRASE } })
    const keys: (string | null)[] = []
    stubAccountDeletionConfirm((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      return refusal('CONFIRMATION_MISMATCH', 403)
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: accountDeletionContent.dialog.confirm }),
    )
    expect(
      await within(dialog).findByText(accountDeletionRefusalMessage('CONFIRMATION_MISMATCH')),
    ).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBe(dialog)

    stubAccountDeletionConfirm((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      return Promise.reject(new Error('offline'))
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: accountDeletionContent.dialog.confirm }),
    )
    expect(
      await within(dialog).findByText(accountDeletionContent.dialog.uncertain),
    ).toBeInTheDocument()

    stubAccountDeletionConfirm((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      return requested()
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: accountDeletionContent.dialog.confirm }),
    )
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(SETTINGS_PATH)
    })
    expect(keys).toHaveLength(3)
    expect(new Set(keys).size).toBe(1)
  })

  it('Esc는 대화상자를 닫고 준비를 되돌리며 초점을 실행 버튼으로 돌려준다', async () => {
    const dialog = await openDialog()
    let cancelled = 0
    stubAccountDeletionCancel(() => {
      cancelled += 1
      return noContent()
    })
    fireEvent.keyDown(document, { key: 'Escape' })

    await waitFor(() => {
      expect(dialog).not.toBeInTheDocument()
    })
    expect(await within(status()).findByText(accountDeletionContent.cancelled)).toBeInTheDocument()
    await waitFor(() => {
      expect(cancelled).toBe(1)
    })
    expect(screen.getByRole('button', { name: accountDeletionContent.start })).toHaveFocus()
  })

  it('정책이 바뀌었다는 거절은 대화상자를 닫고 정책을 다시 읽는다', async () => {
    const dialog = await openDialog()
    stubAccountDeletionCancel(noContent)
    stubAccountDeletionConfirm(() => refusal('ACCOUNT_DELETION_POLICY_CHANGED', 409))
    fireEvent.change(
      within(dialog).getByLabelText(accountDeletionContent.dialog.phraseLabel, { exact: false }),
      { target: { value: PHRASE } },
    )
    fireEvent.click(
      within(dialog).getByRole('button', { name: accountDeletionContent.dialog.confirm }),
    )
    expect(
      await within(status()).findByText(
        accountDeletionRefusalMessage('ACCOUNT_DELETION_POLICY_CHANGED'),
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => {
      expect(getObservedAccountDeletionRequests()).toHaveLength(2)
    })
  })

  it('본인 확인 없는 준비는 거절 문구를 말하고 대화상자를 열지 않으며 초점은 그대로다', async () => {
    const start = await loadStartButton()
    stubAccountDeletionPrepare(() => refusal('REAUTHENTICATION_REQUIRED', 403))
    start.focus()
    fireEvent.click(start)

    await expectReauthGateRejection(
      accountDeletionRefusalMessage('REAUTHENTICATION_REQUIRED'),
      start,
    )
    expect(start).toHaveAttribute('aria-disabled', 'false')
  })

  it('정책 code·버전·경로와 거절 code가 공유 표와 같고 카피에 금지 표현이 없다', () => {
    expect(ACCOUNT_DELETION_PATH).toBe(accountDeletionPolicy.path)
    expect(ACCOUNT_DELETION_POLICY_VERSION).toBe(accountDeletionPolicy.version)
    expect([...ACCOUNT_DELETION_TARGETS]).toEqual(accountDeletionPolicy.targets)
    // 고지 문장의 원본은 공유 표의 `notice` 하나다 — 화면 카피가 그 문장을 그대로 쓴다(서버 고지 hash가 같은 문장을 덮는다).
    expect(Object.keys(accountDeletionPolicy.notice.targets)).toEqual(accountDeletionPolicy.targets)
    const noticeTargets: Readonly<Record<string, string>> = accountDeletionPolicy.notice.targets
    for (const code of accountDeletionPolicy.targets) {
      expect(accountDeletionTargetSentence(code)).toBe(noticeTargets[code])
    }
    expect([...accountDeletionContent.kept]).toEqual(accountDeletionPolicy.notice.kept)
    expect(accountDeletionContent.legalNone).toBe(accountDeletionPolicy.notice.legalNone)
    expect([...ACCOUNT_DELETION_REFUSAL_CODES].sort()).toEqual(
      [...accountDeletionPolicy.refusalCodes].sort(),
    )
    const texts = [
      ...accountDeletionPanelCopyStrings,
      ...accountDeletionDialogCopyStrings,
      ...Object.values(accountDeletionContent.progress),
      ...ACCOUNT_DELETION_TARGETS.map((code) => accountDeletionTargetSentence(code) ?? ''),
      ...ACCOUNT_DELETION_REFUSAL_CODES.map((code) => accountDeletionRefusalMessage(code)),
      accountDeletionContent.backup(30),
    ]
    for (const text of texts) {
      for (const word of FORBIDDEN_COPY_WORDS) {
        expect(text).not.toContain(word)
      }
    }
  })
})
