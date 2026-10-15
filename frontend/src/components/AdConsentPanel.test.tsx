import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  AD_CONSENT_POLICY_VERSION,
  adConsentContent,
  adConsentCopyStrings,
  adConsentStateSentence,
} from '../content/adConsent'
import { CSRF_HEADER, noticePath } from '../content/auth'
import { FORBIDDEN_COPY_WORDS } from '../content/copyRules'
import { supportContent } from '../content/support'
import { SettingsScreen } from '../screens/SettingsScreen'
import { replaceLocation } from '../session/navigation'
import { adConsentSentence, parseAdConsent, type AdConsentControl } from '../session/useAdConsent'
import {
  getObservedAdConsentRequests,
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubAdConsent,
  stubAdConsentChange,
  stubConnectionStatusRequest,
  stubLinkStatusRequest,
  stubSessionRequest,
} from '../test/setup'
import { AdConsentPanel } from './AdConsentPanel'

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const CSRF_TOKEN = 'ad-consent-csrf-token'

const DECIDED_AT = '2026-10-15T01:02:03.456Z'

const copy = adConsentContent

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function consent(
  state: 'UNDECIDED' | 'GRANTED' | 'DENIED',
  policyVersion = AD_CONSENT_POLICY_VERSION,
): Response {
  return json({ state, policyVersion, decidedAt: state === 'UNDECIDED' ? null : DECIDED_AT })
}

function signedIn(): void {
  stubSessionRequest(() =>
    Promise.resolve(json({ authenticated: true, destination: 'settings', csrfToken: CSRF_TOKEN })),
  )
  stubLinkStatusRequest(() => Promise.resolve(json({ providers: ['google'] })))
  stubConnectionStatusRequest(() => Promise.resolve(json({ connected: false })))
}

/** 헤더 값 하나. */
function headerOf(init: RequestInit | undefined, name: string): string | undefined {
  const headers = init?.headers as Record<string, string> | undefined
  return headers?.[name]
}

async function openPanel(): Promise<HTMLElement> {
  render(<SettingsScreen />)
  const region = await screen.findByRole('region', { name: copy.heading })
  await within(region).findByText(copy.stateLabel)
  return region
}

/** 키보드·포인터 사용자처럼 버튼에 초점을 둔 채 누른다(jsdom의 click은 초점을 옮기지 않는다). */
function press(region: HTMLElement, name: string): void {
  const button = within(region).getByRole('button', { name })
  button.focus()
  fireEvent.click(button)
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  window.history.pushState({}, '', '/settings')
})

describe('AdConsentPanel', () => {
  it('공급자·문맥형 범위·전달될 수 있는 것과 전달하지 않는 것·핵심 기능 독립을 설명하고 미결정에서 둘을 고르게 한다', async () => {
    signedIn()
    const region = await openPanel()

    expect(within(region).getByText(copy.provider)).toBeInTheDocument()
    expect(within(region).getByText(copy.scope)).toBeInTheDocument()
    const shared = within(region).getByRole('list', { name: copy.sharedHeading })
    for (const item of copy.shared) {
      expect(within(shared).getByText(item)).toBeInTheDocument()
    }
    const notShared = within(region).getByRole('list', { name: copy.notSharedHeading })
    for (const item of copy.notShared) {
      expect(within(notShared).getByText(item)).toBeInTheDocument()
    }
    expect(within(region).getByText(copy.independence)).toBeInTheDocument()
    expect(within(region).getByText(adConsentStateSentence('UNDECIDED'))).toBeInTheDocument()
    const buttons = within(region)
      .getAllByRole('button')
      .map((button) => button.textContent)
    expect(buttons).toEqual([copy.grant, copy.deny])
    // 동의와 비동의는 같은 모양이다.
    const [grant, deny] = within(region).getAllByRole('button')
    expect(grant?.className).toBe(deny?.className)

    // 이 영역은 live region을 더하지 않는다 — 화면의 status 하나뿐이다.
    expect(within(region).queryByRole('status')).toBeNull()
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 aria-live 영역은 역할 조회로 보이지 않는다.
    expect(region.querySelector('[aria-live]')).toBeNull()
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(getObservedAdConsentRequests()).toEqual(['GET /api/engagement/ad-consent'])
    expect(getObservedRequests()).toEqual([])
  })

  it('동의하면 정책 버전·CSRF·멱등 키를 실어 보내고 상태를 바꾸며 status가 한 번 말하고 초점이 상태 문장으로 간다', async () => {
    signedIn()
    const sent: RequestInit[] = []
    stubAdConsentChange((init) => {
      if (init !== undefined) sent.push(init)
      return Promise.resolve(consent('GRANTED'))
    })
    const region = await openPanel()
    press(region, copy.grant)

    expect(await within(region).findByRole('button', { name: copy.withdraw })).toBeInTheDocument()
    expect(within(region).getByText(adConsentStateSentence('GRANTED'))).toHaveFocus()
    expect(within(region).getByText(/KST/)).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(copy.savedGranted)
    expect(sent).toHaveLength(1)
    expect(headerOf(sent[0], CSRF_HEADER)).toBe(CSRF_TOKEN)
    expect(headerOf(sent[0], 'Idempotency-Key')).toMatch(/^[A-Za-z0-9._:-]{16,128}$/)
    expect(JSON.parse(sent[0]?.body as string)).toEqual({
      choice: 'GRANTED',
      policyVersion: AD_CONSENT_POLICY_VERSION,
    })
    // 동의 뒤에도 외부 요청·저장소 쓰기가 없다.
    expect(getObservedRequests()).toEqual([])
    expect(getObservedStorageEntries()).toEqual([])
    expect(getObservedAllowedRequests().filter((request) => request.startsWith('POST'))).toEqual([
      'POST /api/engagement/ad-consent',
    ])
  })

  it('동의한 사용자는 철회할 수 있고 철회 뒤에는 다시 동의할 수 있다', async () => {
    signedIn()
    stubAdConsent(() => Promise.resolve(consent('GRANTED')))
    const choices: string[] = []
    stubAdConsentChange((init) => {
      const body = JSON.parse(init?.body as string) as { choice: 'GRANTED' | 'DENIED' }
      choices.push(body.choice)
      return Promise.resolve(consent(body.choice))
    })
    const region = await openPanel()
    expect(
      within(region)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual([copy.withdraw])
    press(region, copy.withdraw)
    expect(await within(region).findByRole('button', { name: copy.regrant })).toBeInTheDocument()
    expect(within(region).getByText(adConsentStateSentence('DENIED'))).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(copy.withdrawn)

    press(region, copy.regrant)
    expect(await within(region).findByRole('button', { name: copy.withdraw })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(copy.savedGranted)
    expect(choices).toEqual(['DENIED', 'GRANTED'])
  })

  it('변경 중에는 이 영역의 버튼만 잠그고 두 번 눌러도 한 번만 보낸다', async () => {
    signedIn()
    let release: (response: Response) => void = () => undefined
    let calls = 0
    stubAdConsentChange(() => {
      calls += 1
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    })
    const region = await openPanel()
    press(region, copy.deny)
    await waitFor(() => {
      expect(within(region).getByRole('button', { name: copy.deny })).toHaveAttribute(
        'aria-disabled',
        'true',
      )
    })
    expect(within(region).getByRole('button', { name: copy.grant })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    expect(screen.getByRole('status')).toHaveTextContent(copy.saving)
    // 다른 영역의 조작은 잠기지 않는다.
    expect(screen.getByRole('button', { name: supportContent.submit })).toHaveAttribute(
      'aria-disabled',
      'false',
    )
    press(region, copy.grant)
    press(region, copy.deny)
    expect(calls).toBe(1)

    release(consent('DENIED'))
    expect(await within(region).findByRole('button', { name: copy.regrant })).toHaveAttribute(
      'aria-disabled',
      'false',
    )
    expect(screen.getByRole('status')).toHaveTextContent(copy.savedDenied)
  })

  it('5xx·네트워크 실패면 이전 상태를 그대로 보이고 성공을 말하지 않으며 다시 누르면 같은 키다', async () => {
    signedIn()
    const keys: string[] = []
    let answer: 'network' | 'server' | 'ok' = 'server'
    stubAdConsentChange((init) => {
      keys.push(headerOf(init, 'Idempotency-Key') ?? '')
      if (answer === 'network') return Promise.reject(new TypeError('Failed to fetch'))
      if (answer === 'server') return Promise.resolve(json({ code: 'INTERNAL_ERROR' }, 500))
      return Promise.resolve(consent('GRANTED'))
    })
    const region = await openPanel()
    press(region, copy.grant)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(copy.failed)
    })
    expect(within(region).getByText(adConsentStateSentence('UNDECIDED'))).toBeInTheDocument()
    expect(within(region).getByRole('button', { name: copy.grant })).toHaveAttribute(
      'aria-disabled',
      'false',
    )
    expect(screen.getByRole('status')).not.toHaveTextContent(copy.savedGranted)
    // 기다려도 다시 보내지 않는다.
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(keys).toHaveLength(1)

    answer = 'network'
    press(region, copy.grant)
    await waitFor(() => {
      expect(keys).toHaveLength(2)
    })
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(copy.failed)
    })
    expect(within(region).getByText(adConsentStateSentence('UNDECIDED'))).toBeInTheDocument()

    answer = 'ok'
    press(region, copy.grant)
    expect(await within(region).findByRole('button', { name: copy.withdraw })).toBeInTheDocument()
    expect(keys).toHaveLength(3)
    // 같은 선택의 재전송은 언제나 같은 키다.
    expect(new Set(keys).size).toBe(1)
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('알 수 없는 모양의 성공 응답도 이전 상태를 유지하고 성공을 말하지 않는다', async () => {
    signedIn()
    stubAdConsentChange(() => Promise.resolve(json({ state: 'MAYBE' })))
    const region = await openPanel()
    press(region, copy.grant)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(copy.failed)
    })
    expect(within(region).getByText(adConsentStateSentence('UNDECIDED'))).toBeInTheDocument()
  })

  it('정책 버전이 바뀌었다는 409면 상태를 다시 읽고 바뀐 안내를 말한다', async () => {
    signedIn()
    let reads = 0
    stubAdConsent(() => {
      reads += 1
      return Promise.resolve(
        reads === 1 ? consent('UNDECIDED') : consent('UNDECIDED', 'AD-CONSENT-2.0'),
      )
    })
    stubAdConsentChange(() =>
      Promise.resolve(
        json({ code: 'AD_CONSENT_POLICY_CHANGED', message: 'm', correlationId: 'c' }, 409),
      ),
    )
    const region = await openPanel()
    press(region, copy.grant)
    await waitFor(() => {
      expect(reads).toBe(2)
    })
    expect(screen.getByRole('status')).toHaveTextContent(copy.outdated)
    // 서버의 정책이 이 화면의 설명과 다르면 고를 수 없다.
    await waitFor(() => {
      expect(within(region).queryByRole('button', { name: copy.grant })).toBeNull()
    })
    expect(within(region).getByText(copy.outdated)).toBeInTheDocument()
    // 누른 버튼이 사라졌다 — 초점은 body로 떨어지지 않고 바뀐 안내 문장이 받는다.
    expect(within(region).getByText(copy.outdated)).toHaveFocus()
  })

  it('저장 응답의 상태가 고른 것과 다르면 그 상태로 바꾸되 저장 성공을 말하지 않는다', async () => {
    signedIn()
    // 같은 키의 재전송 사이에 다른 탭이 비동의로 바꿨다 — 서버는 지금 상태(DENIED)를 돌려준다.
    stubAdConsentChange(() => Promise.resolve(consent('DENIED')))
    const region = await openPanel()
    press(region, copy.grant)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(copy.changedElsewhere)
    })
    expect(screen.getByRole('status')).not.toHaveTextContent(copy.savedGranted)
    expect(within(region).getByText(adConsentStateSentence('DENIED'))).toHaveFocus()
    expect(within(region).getByRole('button', { name: copy.regrant })).toBeInTheDocument()
  })

  it.each([403, 404])(
    '변경이 %i로 거절되면 이전 선택을 지우고 거절을 말하며 초점을 영역 안에 둔다',
    async (status) => {
      signedIn()
      stubAdConsent(() => Promise.resolve(consent('GRANTED')))
      stubAdConsentChange(() =>
        Promise.resolve(json({ code: 'NOT_FOUND', message: 'm', correlationId: 'c' }, status)),
      )
      const region = await openPanel()
      press(region, copy.withdraw)
      await waitFor(() => {
        expect(screen.getByRole('status')).toHaveTextContent(copy.refused)
      })
      expect(within(region).queryByText(adConsentStateSentence('GRANTED'))).toBeNull()
      expect(within(region).queryByRole('button', { name: copy.withdraw })).toBeNull()
      expect(within(region).getByText(copy.unavailable)).toHaveFocus()
    },
  )

  it('다시 읽어 상태를 새로 받으면 실패한 변경의 키를 버리고 다음 누름은 새 키다', async () => {
    signedIn()
    let reads = 0
    stubAdConsent(() => {
      reads += 1
      // 1: 처음, 2: 정책 409 뒤 다시 읽기 실패, 3: 다시 확인 성공.
      return reads === 2
        ? Promise.reject(new TypeError('Failed to fetch'))
        : Promise.resolve(consent('UNDECIDED'))
    })
    const keys: string[] = []
    stubAdConsentChange((init) => {
      keys.push(headerOf(init, 'Idempotency-Key') ?? '')
      if (keys.length === 1) {
        return Promise.resolve(
          json({ code: 'AD_CONSENT_POLICY_CHANGED', message: 'm', correlationId: 'c' }, 409),
        )
      }
      if (keys.length === 2) return Promise.resolve(json({ code: 'INTERNAL_ERROR' }, 500))
      return Promise.resolve(consent('GRANTED'))
    })
    const region = await openPanel()
    press(region, copy.grant)
    // 다시 읽기가 실패하면 마지막 상태 아래에 그 사실을 따로 말한다(확인하지 못했다는 처음 문장과 다르다).
    expect(await within(region).findByText(copy.refreshFailed)).toBeInTheDocument()
    expect(within(region).queryByText(copy.unavailable)).toBeNull()
    expect(within(region).getByText(adConsentStateSentence('UNDECIDED'))).toBeInTheDocument()

    press(region, copy.grant)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(copy.failed)
    })
    press(region, copy.retry)
    await waitFor(() => {
      expect(reads).toBe(3)
    })
    await waitFor(() => {
      expect(within(region).queryByText(copy.refreshFailed)).toBeNull()
    })
    press(region, copy.grant)
    expect(await within(region).findByRole('button', { name: copy.withdraw })).toBeInTheDocument()
    expect(keys).toHaveLength(3)
    // 실패한 두 번째 변경의 키는 새로 읽은 상태 뒤에 다시 쓰이지 않는다.
    expect(keys[2]).not.toBe(keys[1])
  })

  it('응답을 기다리는 동안 초점을 영역 밖으로 옮겼으면 늦은 저장 성공이 초점을 뺏지 않는다', async () => {
    signedIn()
    let release: (response: Response) => void = () => undefined
    stubAdConsentChange(
      () =>
        new Promise<Response>((resolve) => {
          release = resolve
        }),
    )
    const region = await openPanel()
    press(region, copy.grant)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(copy.saving)
    })
    const elsewhere = screen.getByRole('button', { name: supportContent.submit })
    elsewhere.focus()
    release(consent('GRANTED'))
    expect(await within(region).findByRole('button', { name: copy.withdraw })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(copy.savedGranted)
    expect(elsewhere).toHaveFocus()
  })

  it('충돌 거절은 이전 상태를 유지하고 다음 누름은 새 키다', async () => {
    signedIn()
    const keys: string[] = []
    stubAdConsentChange((init) => {
      keys.push(headerOf(init, 'Idempotency-Key') ?? '')
      return Promise.resolve(
        json({ code: 'IDEMPOTENCY_CONFLICT', message: 'm', correlationId: 'c' }, 409),
      )
    })
    const region = await openPanel()
    press(region, copy.grant)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(copy.refused)
    })
    press(region, copy.grant)
    await waitFor(() => {
      expect(keys).toHaveLength(2)
    })
    expect(keys[0]).not.toBe(keys[1])
    expect(within(region).getByText(adConsentStateSentence('UNDECIDED'))).toBeInTheDocument()
  })

  it('변경이 401이면 만료 이동 하나이고 상태·결과 문장을 바꾸지 않는다', async () => {
    signedIn()
    stubAdConsentChange(() => Promise.resolve(json({ code: 'UNAUTHENTICATED' }, 401)))
    const region = await openPanel()
    press(region, copy.grant)
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(noticePath('expired'))
    })
    expect(vi.mocked(replaceLocation)).toHaveBeenCalledTimes(1)
    await waitFor(() => {
      expect(within(region).getByRole('button', { name: copy.grant })).toHaveAttribute(
        'aria-disabled',
        'false',
      )
    })
    expect(within(region).getByText(adConsentStateSentence('UNDECIDED'))).toBeInTheDocument()
    expect(within(region).getByRole('button', { name: copy.deny })).toBeInTheDocument()
    for (const sentence of [copy.refused, copy.failed, copy.savedGranted, copy.unavailable]) {
      expect(screen.queryByText(sentence)).toBeNull()
    }
  })

  it('조회가 401이면 만료 이동 하나이고 상태를 주장하거나 지우는 문장을 그리지 않는다', async () => {
    signedIn()
    stubAdConsent(() => Promise.resolve(json({ code: 'UNAUTHENTICATED' }, 401)))
    render(<SettingsScreen />)
    const region = await screen.findByRole('region', { name: copy.heading })
    await waitFor(() => {
      expect(vi.mocked(replaceLocation)).toHaveBeenCalledWith(noticePath('expired'))
    })
    expect(vi.mocked(replaceLocation)).toHaveBeenCalledTimes(1)
    // 확인 중 그대로다 — 확인하지 못했다는 문장도, 다시 확인 조작도 없다.
    expect(within(region).getByText(copy.checking)).toBeInTheDocument()
    expect(within(region).queryByText(copy.unavailable)).toBeNull()
    expect(within(region).queryByRole('button', { name: copy.retry })).toBeNull()
  })

  it('화면의 다른 흐름이 도는 동안에는 선택과 다시 확인이 모두 잠긴다', () => {
    const reload = vi.fn()
    const decide = vi.fn(() => Promise.resolve())
    const unavailable: AdConsentControl = {
      view: { kind: 'unavailable' },
      loading: false,
      reload,
      notice: null,
      pending: false,
      decide,
    }
    const { rerender } = render(<AdConsentPanel blocked consent={unavailable} onAction={vi.fn()} />)
    const retry = screen.getByRole('button', { name: copy.retry })
    expect(retry).toHaveAttribute('aria-disabled', 'true')
    fireEvent.click(retry)
    expect(reload).not.toHaveBeenCalled()

    rerender(
      <AdConsentPanel
        blocked
        consent={{
          ...unavailable,
          view: {
            kind: 'loaded',
            consent: {
              state: 'UNDECIDED',
              policyVersion: AD_CONSENT_POLICY_VERSION,
              decidedAt: null,
            },
            refreshFailed: true,
          },
        }}
        onAction={vi.fn()}
      />,
    )
    for (const name of [copy.retry, copy.grant, copy.deny]) {
      const button = screen.getByRole('button', { name })
      expect(button).toHaveAttribute('aria-disabled', 'true')
      fireEvent.click(button)
    }
    expect(reload).not.toHaveBeenCalled()
    expect(decide).not.toHaveBeenCalled()
  })

  it('상태를 확인하지 못하면 상태를 주장하지 않고 다시 확인을 준다', async () => {
    signedIn()
    let reads = 0
    stubAdConsent(() => {
      reads += 1
      return reads === 1
        ? Promise.reject(new TypeError('Failed to fetch'))
        : Promise.resolve(consent('DENIED'))
    })
    render(<SettingsScreen />)
    const region = await screen.findByRole('region', { name: copy.heading })
    expect(await within(region).findByText(copy.unavailable)).toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: copy.grant })).toBeNull()
    press(region, copy.retry)
    expect(await within(region).findByRole('button', { name: copy.regrant })).toBeInTheDocument()
    expect(reads).toBe(2)
  })
})

describe('광고 동의 카피와 응답 해석', () => {
  it('카피에 금지 표현이 없다', () => {
    for (const text of adConsentCopyStrings) {
      for (const word of FORBIDDEN_COPY_WORDS) {
        expect(text).not.toContain(word)
      }
    }
  })

  it('모르는 모양의 상태는 믿지 않는다', () => {
    expect(
      parseAdConsent({
        state: 'UNDECIDED',
        policyVersion: AD_CONSENT_POLICY_VERSION,
        decidedAt: null,
      }),
    ).toEqual({ state: 'UNDECIDED', policyVersion: AD_CONSENT_POLICY_VERSION, decidedAt: null })
    expect(
      parseAdConsent({
        state: 'GRANTED',
        policyVersion: AD_CONSENT_POLICY_VERSION,
        decidedAt: DECIDED_AT,
      }),
    ).toEqual({ state: 'GRANTED', policyVersion: AD_CONSENT_POLICY_VERSION, decidedAt: DECIDED_AT })
    expect(
      parseAdConsent({
        state: 'GRANTED',
        policyVersion: AD_CONSENT_POLICY_VERSION,
        decidedAt: null,
      }),
    ).toBeNull()
    expect(
      parseAdConsent({
        state: 'UNDECIDED',
        policyVersion: AD_CONSENT_POLICY_VERSION,
        decidedAt: DECIDED_AT,
      }),
    ).toBeNull()
    expect(
      parseAdConsent({ state: 'MAYBE', policyVersion: AD_CONSENT_POLICY_VERSION, decidedAt: null }),
    ).toBeNull()
    expect(parseAdConsent({ state: 'DENIED', policyVersion: '', decidedAt: DECIDED_AT })).toBeNull()
    expect(
      parseAdConsent({ state: 'DENIED', policyVersion: 'x', decidedAt: '2026-10-15' }),
    ).toBeNull()
    expect(parseAdConsent([])).toBeNull()
  })

  it('결과 문장은 철회와 비동의를 가른다', () => {
    expect(adConsentSentence({ kind: 'saved', previous: 'GRANTED', next: 'DENIED' })).toBe(
      copy.withdrawn,
    )
    expect(adConsentSentence({ kind: 'saved', previous: 'UNDECIDED', next: 'DENIED' })).toBe(
      copy.savedDenied,
    )
    expect(adConsentSentence({ kind: 'saved', previous: 'DENIED', next: 'GRANTED' })).toBe(
      copy.savedGranted,
    )
    expect(adConsentSentence(null)).toBeNull()
  })
})
