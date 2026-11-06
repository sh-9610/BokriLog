import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ACCOUNT_DELETION_POLICY_VERSION,
  accountDeletionContent,
  accountDeletionRefusalMessage,
  accountDeletionTargetSentence,
  normalizedPhrase,
} from '../content/accountDeletion'
import accountDeletionPolicy from '../content/accountDeletionPolicy.fixture.json'
import { authContent, SETTINGS_PATH } from '../content/auth'
import { replaceLocation } from '../session/navigation'
import { FIRST_MOUNT_TIMEOUT_MS } from '../test/firstMount'
import {
  getObservedAccountDeletionRequests,
  getObservedAllowedRequests,
  getObservedRequests,
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
 * 설정 화면 계정 삭제(Story 5.1)의 경계 — 화면이 본 정책 버전, 진입점 오류의 불확실 처리, 불확실 뒤 취소의 다시 읽기, 진행 중 거절의
 * 이동, Java `strip()`과 같은 문구 대조, 불일치 안내의 연결, 대화상자가 열린 동안의 배경 차단, 법정 보존 항목 표시.
 */

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const CSRF_TOKEN = 'settings-deletion-edge-csrf-token'
const PHRASE = accountDeletionPolicy.confirmation

// 공백 문자는 코드 포인트로 만든다(소스에 보이지 않는 문자를 두지 않는다).
const NBSP = String.fromCharCode(0xa0)
const NARROW_NBSP = String.fromCharCode(0x202f)
const BOM = String.fromCharCode(0xfeff)
const EM_SPACE = String.fromCharCode(0x2003)
const IDEOGRAPHIC_SPACE = String.fromCharCode(0x3000)

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function refusal(code: string, status: number): Promise<Response> {
  return Promise.resolve(json({ code, message: 'x', correlationId: 'c' }, status))
}

function policyState(
  request: Record<string, unknown> | null = null,
  legalRetention: readonly unknown[] = [],
): Promise<Response> {
  return Promise.resolve(
    json({
      available: true,
      policy: {
        version: accountDeletionPolicy.version,
        targets: accountDeletionPolicy.targets,
        backupDestroyDays: accountDeletionPolicy.backupDestroyDays,
        legalRetention,
      },
      request,
    }),
  )
}

const REQUEST = {
  requestId: 'del_edge',
  requestedAt: '2026-11-05T01:02:03.456Z',
  state: 'DELETION_IN_PROGRESS',
  policyVersion: ACCOUNT_DELETION_POLICY_VERSION,
  progress: { status: 'IN_PROGRESS', failedSteps: [] },
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  window.history.pushState({}, '', SETTINGS_PATH)
  stubSessionRequest(() =>
    Promise.resolve(json({ authenticated: true, destination: 'settings', csrfToken: CSRF_TOKEN })),
  )
  stubLinkStatusRequest(() => Promise.resolve(json({ providers: ['google', 'naver'] })))
  stubConnectionStatusRequest(() => Promise.resolve(json({ connected: false })))
  stubAccountDeletionCancel(() => Promise.resolve(new Response(null, { status: 204 })))
})

function status(): HTMLElement {
  const [region] = screen.getAllByRole('status')
  if (region === undefined) throw new Error('no screen status region')
  return region
}

async function startButton(): Promise<HTMLElement> {
  render(<SettingsScreen />)
  return screen.findByRole(
    'button',
    { name: accountDeletionContent.start },
    { timeout: FIRST_MOUNT_TIMEOUT_MS },
  )
}

async function openWith(prepareVersion = ACCOUNT_DELETION_POLICY_VERSION): Promise<HTMLElement> {
  const start = await startButton()
  stubAccountDeletionPrepare(() =>
    Promise.resolve(json({ policyVersion: prepareVersion, confirmation: PHRASE })),
  )
  fireEvent.click(start)
  return screen.findByRole('dialog', { name: accountDeletionContent.dialog.title })
}

function typeAndConfirm(dialog: HTMLElement, value: string): void {
  fireEvent.change(
    within(dialog).getByLabelText(accountDeletionContent.dialog.phraseLabel, { exact: false }),
    { target: { value } },
  )
  fireEvent.click(
    within(dialog).getByRole('button', { name: accountDeletionContent.dialog.confirm }),
  )
}

describe('설정 화면 계정 삭제의 경계', () => {
  it('확정은 준비 응답이 아니라 사용자가 본 정책 버전을 싣는다', async () => {
    const dialog = await openWith('ACCOUNT-DELETION-9.9')
    let sent: unknown = null
    stubAccountDeletionConfirm((init) => {
      sent = JSON.parse(init?.body as string)
      return refusal('ACCOUNT_DELETION_POLICY_CHANGED', 409)
    })
    typeAndConfirm(dialog, PHRASE)
    await waitFor(() => {
      expect(sent).toEqual({ policyVersion: ACCOUNT_DELETION_POLICY_VERSION, confirmation: PHRASE })
    })
  })

  it('서버 정책이 바뀌었다는 준비 거절은 그 문구를 말하고 정책을 다시 읽는다', async () => {
    const start = await startButton()
    stubAccountDeletionPrepare(() => refusal('ACCOUNT_DELETION_POLICY_CHANGED', 409))
    fireEvent.click(start)
    expect(
      await within(status()).findByText(
        accountDeletionRefusalMessage('ACCOUNT_DELETION_POLICY_CHANGED'),
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('진입점 오류(502 UPSTREAM_UNAVAILABLE)는 실패가 아니라 불확실이고 같은 키로 다시 보낸다', async () => {
    const dialog = await openWith()
    const keys: (string | null)[] = []
    stubAccountDeletionConfirm((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      return refusal('UPSTREAM_UNAVAILABLE', 502)
    })
    typeAndConfirm(dialog, PHRASE)
    expect(
      await within(dialog).findByText(accountDeletionContent.dialog.uncertain),
    ).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBe(dialog)

    stubAccountDeletionConfirm((init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      return refusal('UPSTREAM_UNAVAILABLE', 504)
    })
    fireEvent.click(
      within(dialog).getByRole('button', { name: accountDeletionContent.dialog.confirm }),
    )
    await waitFor(() => {
      expect(keys).toHaveLength(2)
    })
    expect(new Set(keys).size).toBe(1)
    expect(screen.getByRole('dialog')).toBe(dialog)
  })

  it('불확실 뒤 취소: 다시 읽은 상태에 요청이 있으면 진행 화면으로 간다', async () => {
    const dialog = await openWith()
    stubAccountDeletionConfirm(() => Promise.reject(new Error('offline')))
    typeAndConfirm(dialog, PHRASE)
    await within(dialog).findByText(accountDeletionContent.dialog.uncertain)

    stubAccountDeletionState(() => policyState(REQUEST))
    fireEvent.click(
      within(dialog).getByRole('button', { name: accountDeletionContent.dialog.cancel }),
    )
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(SETTINGS_PATH)
    })
  })

  it('불확실 뒤 취소: 요청이 없으면 결과를 모른다고 말한다', async () => {
    const dialog = await openWith()
    stubAccountDeletionConfirm(() => Promise.reject(new Error('offline')))
    typeAndConfirm(dialog, PHRASE)
    await within(dialog).findByText(accountDeletionContent.dialog.uncertain)

    fireEvent.click(
      within(dialog).getByRole('button', { name: accountDeletionContent.dialog.cancel }),
    )
    expect(await within(status()).findByText(accountDeletionContent.unknown)).toBeInTheDocument()
    expect(getObservedAccountDeletionRequests()).toHaveLength(2)
    expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()
  })

  it('준비가 이미 진행 중이라고 답하면 진행 화면으로 간다', async () => {
    const start = await startButton()
    stubAccountDeletionPrepare(() => refusal('ACCOUNT_DELETION_IN_PROGRESS', 409))
    fireEvent.click(start)
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(SETTINGS_PATH)
    })
  })

  it('확정이 이미 진행 중이라고 답하면 진행 화면으로 간다', async () => {
    const dialog = await openWith()
    stubAccountDeletionConfirm(() => refusal('ACCOUNT_DELETION_IN_PROGRESS', 409))
    typeAndConfirm(dialog, PHRASE)
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(SETTINGS_PATH)
    })
  })

  it('삭제가 이미 끝났다는 거절(409 ACCOUNT_DELETION_COMPLETED)은 문서를 다시 열어 완료 화면으로 간다', async () => {
    const dialog = await openWith()
    stubAccountDeletionConfirm(() => refusal('ACCOUNT_DELETION_COMPLETED', 409))
    typeAndConfirm(dialog, PHRASE)
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(SETTINGS_PATH)
    })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('같은 키·다른 본문의 충돌(409 IDEMPOTENCY_CONFLICT)도 이미 진행 중이므로 진행 화면으로 간다', async () => {
    const dialog = await openWith()
    stubAccountDeletionConfirm(() => refusal('IDEMPOTENCY_CONFLICT', 409))
    typeAndConfirm(dialog, PHRASE)
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(SETTINGS_PATH)
    })
    expect(accountDeletionRefusalMessage('IDEMPOTENCY_CONFLICT')).not.toBe(
      accountDeletionRefusalMessage(null),
    )
  })

  it.each([
    ['공통 오류 본문이 없는 500', () => Promise.resolve(new Response(null, { status: 500 }))],
    ['모양을 알 수 없는 200', () => Promise.resolve(json({}))],
  ] as const)(
    '%s은 불확실이고 대화상자를 유지하며 같은 키로 다시 보낸다',
    async (_name, answer) => {
      const dialog = await openWith()
      const keys: (string | null)[] = []
      stubAccountDeletionConfirm((init) => {
        keys.push(new Headers(init?.headers).get('Idempotency-Key'))
        return answer()
      })
      typeAndConfirm(dialog, PHRASE)
      expect(
        await within(dialog).findByText(accountDeletionContent.dialog.uncertain),
      ).toBeInTheDocument()
      expect(screen.getByRole('dialog')).toBe(dialog)
      expect(vi.mocked(replaceLocation)).not.toHaveBeenCalled()

      fireEvent.click(
        within(dialog).getByRole('button', { name: accountDeletionContent.dialog.confirm }),
      )
      await waitFor(() => {
        expect(keys).toHaveLength(2)
      })
      expect(keys[0]).not.toBeNull()
      expect(new Set(keys).size).toBe(1)
      expect(screen.getByRole('dialog')).toBe(dialog)
    },
  )

  it('지금 정책 버전이어도 이 화면이 문장을 갖지 못한 대상 code가 있으면 낡은 정책으로 보고 실행하지 않는다', async () => {
    stubAccountDeletionState(() =>
      Promise.resolve(
        json({
          available: true,
          policy: {
            version: ACCOUNT_DELETION_POLICY_VERSION,
            targets: [...accountDeletionPolicy.targets, 'SOMETHING_NEW'],
            backupDestroyDays: accountDeletionPolicy.backupDestroyDays,
            legalRetention: [],
          },
          request: null,
        }),
      ),
    )
    render(<SettingsScreen />)
    const region = await screen.findByRole(
      'region',
      { name: accountDeletionContent.heading },
      { timeout: FIRST_MOUNT_TIMEOUT_MS },
    )
    expect(await within(region).findByText(accountDeletionContent.outdated)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: accountDeletionContent.start })).toBeNull()
    expect(region.textContent).not.toContain('SOMETHING_NEW')
    // 아는 code의 문장은 그대로 그린다.
    const known = accountDeletionPolicy.targets[0]
    if (known === undefined) throw new Error('no target in the shared table')
    expect(region.textContent).toContain(accountDeletionTargetSentence(known))
  })

  it('같은 필드 이름의 법정 보존 항목 둘도 겹치지 않는 key로 둘 다 그린다', async () => {
    const errors = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    try {
      stubAccountDeletionState(() =>
        policyState(null, [
          { field: '거래 기록', basis: '법령 예시', purpose: '분쟁 대응', period: '5년' },
          { field: '거래 기록', basis: '다른 법령', purpose: '세무 대응', period: '7년' },
        ]),
      )
      render(<SettingsScreen />)
      const region = await screen.findByRole(
        'region',
        { name: accountDeletionContent.heading },
        { timeout: FIRST_MOUNT_TIMEOUT_MS },
      )
      await within(region).findByText(accountDeletionContent.legalHeading)
      for (const text of ['법령 예시', '다른 법령', '5년', '7년']) {
        expect(region.textContent).toContain(text)
      }
      expect(
        errors.mock.calls.some((call) => call.some((part) => String(part).includes('same key'))),
      ).toBe(false)
    } finally {
      errors.mockRestore()
    }
  })

  it('문구 대조는 Java strip()과 같다 — 줄 바꿈 없는 공백은 지우지 않는다', async () => {
    expect(normalizedPhrase(`\t ${PHRASE}${IDEOGRAPHIC_SPACE}\n`)).toBe(PHRASE)
    expect(normalizedPhrase(`${PHRASE}${NBSP}`)).not.toBe(PHRASE)
    expect(normalizedPhrase(`${NARROW_NBSP}${PHRASE}`)).not.toBe(PHRASE)
    expect(normalizedPhrase(`${PHRASE}${BOM}`)).not.toBe(PHRASE)
    expect(normalizedPhrase(PHRASE.normalize('NFD'))).toBe(PHRASE)

    const dialog = await openWith()
    const input = within(dialog).getByLabelText(accountDeletionContent.dialog.phraseLabel, {
      exact: false,
    })
    const confirm = within(dialog).getByRole('button', {
      name: accountDeletionContent.dialog.confirm,
    })
    fireEvent.change(input, { target: { value: `${PHRASE}${NBSP}` } })
    expect(confirm).toHaveAttribute('aria-disabled', 'true')
    fireEvent.change(input, { target: { value: `${PHRASE}${EM_SPACE}` } })
    expect(confirm).toHaveAttribute('aria-disabled', 'false')
  })

  it('서버의 문구 불일치는 입력 칸이 aria-invalid로 그 문장을 가리킨다', async () => {
    const dialog = await openWith()
    stubAccountDeletionConfirm(() => refusal('CONFIRMATION_MISMATCH', 403))
    const input = within(dialog).getByLabelText(accountDeletionContent.dialog.phraseLabel, {
      exact: false,
    })
    expect(input).toHaveAttribute('aria-invalid', 'false')
    typeAndConfirm(dialog, PHRASE)
    const sentence = await within(dialog).findByText(
      accountDeletionRefusalMessage('CONFIRMATION_MISMATCH'),
    )
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', sentence.id)
    expect(input).toHaveAccessibleDescription(
      accountDeletionRefusalMessage('CONFIRMATION_MISMATCH'),
    )
  })

  it('대화상자가 열려 있는 동안 배경 조작이 막힌다', async () => {
    await openWith()
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.linkLabels.kakao }))
    fireEvent.click(screen.getByRole('button', { name: authContent.settings.reauth.labels.google }))
    fireEvent.click(screen.getByRole('button', { name: authContent.signOut.label }))

    expect(getObservedRequests()).toEqual([])
    expect(getObservedAllowedRequests().filter((entry) => entry.startsWith('POST'))).toEqual([
      'POST /api/account/deletion',
    ])
  })

  it('법정 분리 보존 항목이 있으면 항목마다 필드·근거·목적·기간을 보인다', async () => {
    stubAccountDeletionState(() =>
      policyState(null, [
        { field: '거래 기록', basis: '법령 예시', purpose: '분쟁 대응', period: '5년' },
      ]),
    )
    render(<SettingsScreen />)
    const region = await screen.findByRole(
      'region',
      { name: accountDeletionContent.heading },
      { timeout: FIRST_MOUNT_TIMEOUT_MS },
    )
    await within(region).findByText(accountDeletionContent.legalHeading)
    for (const text of ['거래 기록', '법령 예시', '분쟁 대응', '5년']) {
      expect(region.textContent).toContain(text)
    }
    expect(region.textContent).not.toContain(accountDeletionContent.legalNone)
    // 남기는 것도 따로 보인다.
    for (const item of accountDeletionContent.kept) {
      expect(region.textContent).toContain(item)
    }
  })
})
