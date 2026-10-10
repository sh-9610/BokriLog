import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CSRF_HEADER, noticePath } from '../content/auth'
import { FORBIDDEN_COPY_WORDS } from '../content/copyRules'
import rulesFixture from '../content/inquiryRules.fixture.json'
import {
  inquiryRefusalMessage,
  sensitiveRuleMessage,
  sensitiveRuleOf,
  supportContent,
  supportCopyStrings,
} from '../content/support'
import { SettingsScreen } from '../screens/SettingsScreen'
import { replaceLocation } from '../session/navigation'
import { parseInquiryList, parseReceipt } from '../session/useSupportInquiries'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  getObservedSupportRequests,
  stubConnectionStatusRequest,
  stubLinkStatusRequest,
  stubSessionRequest,
  stubSupportInquiries,
  stubSupportSubmit,
} from '../test/setup'

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

const CSRF_TOKEN = 'support-csrf-token'

const REFERENCE = 'Q-ABCDEFGH23'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function signedIn(): void {
  stubSessionRequest(() =>
    Promise.resolve(json({ authenticated: true, destination: 'settings', csrfToken: CSRF_TOKEN })),
  )
  stubLinkStatusRequest(() => Promise.resolve(json({ providers: ['google'] })))
  stubConnectionStatusRequest(() => Promise.resolve(json({ connected: false })))
}

function receipt(targetAt: string | null = '2026-10-15T09:00:00Z'): Response {
  return json(
    {
      reference: REFERENCE,
      receivedAt: '2026-10-12T01:00:00Z',
      targetAt,
      targetState: targetAt === null ? 'CALENDAR_PENDING' : 'SET',
      status: 'RECEIVED',
    },
    201,
  )
}

/** 헤더 값 하나. */
function headerOf(init: RequestInit | undefined, name: string): string | undefined {
  const headers = init?.headers as Record<string, string> | undefined
  return headers?.[name]
}

async function openPanel(): Promise<HTMLElement> {
  render(<SettingsScreen />)
  return screen.findByRole('region', { name: supportContent.heading })
}

/** 폼을 채운다. 값은 화면의 영구 라벨로 찾는다. */
function fill(region: HTMLElement, title: string, description: string): void {
  fireEvent.change(within(region).getByLabelText(supportContent.categoryLabel), {
    target: { value: 'SYNC_PLAN' },
  })
  fireEvent.change(within(region).getByLabelText(supportContent.titleLabel), {
    target: { value: title },
  })
  fireEvent.change(within(region).getByLabelText(supportContent.descriptionLabel), {
    target: { value: description },
  })
}

/** 목록의 n번째 요소. 없으면 시험을 실패시킨다. */
function nth(elements: readonly HTMLElement[], index: number): HTMLElement {
  const element = elements[index]
  if (element === undefined) throw new Error(`${String(index)}번째 요소가 없다.`)
  return element
}

function submit(region: HTMLElement): void {
  fireEvent.click(within(region).getByRole('button', { name: supportContent.submit }))
}

beforeEach(() => {
  vi.mocked(replaceLocation).mockClear()
  window.history.pushState({}, '', '/settings')
})

describe('SupportInquiryPanel', () => {
  it('영구 라벨·도움말·민감값 안내와 내 문의 목록, 설정된 지원 이메일을 보인다', async () => {
    signedIn()
    stubSupportInquiries(() =>
      Promise.resolve(
        json({
          supportContact: 'support@bokrilog.test',
          items: [
            {
              reference: REFERENCE,
              category: 'ACCOUNT',
              title: '로그인 문의',
              description: '로그인 화면에서\n멈춥니다.',
              receivedAt: '2026-10-12T01:00:00Z',
              targetAt: '2026-10-15T09:00:00Z',
              targetState: 'SET',
              status: 'ANSWERED',
              answer: '확인했습니다.\n다시 로그인해 주세요.',
              answeredAt: '2026-10-13T02:00:00Z',
            },
            {
              reference: 'Q-ZZZZZZZZ22',
              category: 'OTHER',
              title: '연말 문의',
              description: '연말에 보낸 내용',
              receivedAt: '2027-12-30T01:00:00Z',
              targetAt: null,
              targetState: 'CALENDAR_PENDING',
              status: 'RECEIVED',
              answer: null,
              answeredAt: null,
            },
          ],
        }),
      ),
    )

    const region = await openPanel()
    expect(within(region).getByText(supportContent.sensitiveNotice)).toBeInTheDocument()
    expect(within(region).getByLabelText(supportContent.titleLabel)).toHaveAccessibleDescription(
      supportContent.titleHelp,
    )
    const list = await within(region).findByRole('list', { name: supportContent.listHeading })
    const rows = within(list).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('답변 완료')
    expect(rows[0]).toHaveTextContent('다시 로그인해 주세요.')
    expect(rows[0]).toHaveTextContent('KST')
    // 목표 미정은 문장으로 말한다.
    expect(rows[1]).toHaveTextContent(supportContent.targetPending)
    expect(rows[1]).toHaveTextContent('접수됨')
    // 내가 쓴 본문은 접힌 채로 있고 펼쳐 본다.
    const toggle = within(nth(rows, 0)).getByText(supportContent.descriptionToggle)
    expect(within(nth(rows, 0)).getByText(/로그인 화면에서/)).not.toBeVisible()
    fireEvent.click(toggle)
    expect(within(nth(rows, 0)).getByText(/로그인 화면에서/)).toBeVisible()
    expect(within(region).getByRole('link', { name: 'support@bokrilog.test' })).toHaveAttribute(
      'href',
      'mailto:support@bokrilog.test',
    )
    // 이 영역은 live region을 더하지 않는다 — 화면의 status 하나뿐이다.
    expect(within(region).queryByRole('status')).toBeNull()
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })

  it('지원 이메일 설정이 없으면 링크를 두지 않는다', async () => {
    signedIn()
    const region = await openPanel()
    expect(await within(region).findByText(supportContent.listEmpty)).toBeInTheDocument()
    expect(within(region).queryByRole('link')).toBeNull()
    expect(getObservedSupportRequests()).toEqual(['GET /api/support/inquiries'])
  })

  it('보내기 전 검사가 민감값을 막고 그 칸으로 초점을 옮기며 요청을 보내지 않는다', async () => {
    signedIn()
    const region = await openPanel()
    const token = 'Kq7'.repeat(7)
    fill(region, `키 ${token}`, '동기화가 멈췄어요')
    submit(region)

    const title = within(region).getByLabelText(supportContent.titleLabel)
    await waitFor(() => {
      expect(title).toHaveFocus()
    })
    expect(title).toHaveAttribute('aria-invalid', 'true')
    expect(within(region).getByText(sensitiveRuleMessage('SECRET'))).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(supportContent.invalid)
    // 입력은 그대로다.
    expect(title).toHaveValue(`키 ${token}`)
    expect(getObservedAllowedRequests().filter((request) => request.startsWith('POST'))).toEqual([])
    expect(getObservedRequests()).toEqual([])
  })

  it('접수하면 접수 번호·시각·답변 목표를 확인 블록과 status 한 문장으로 보이고 목록을 다시 읽는다', async () => {
    signedIn()
    let listReads = 0
    stubSupportInquiries(() => {
      listReads += 1
      return Promise.resolve(json({ supportContact: null, items: [] }))
    })
    const sent: RequestInit[] = []
    stubSupportSubmit((init) => {
      if (init !== undefined) sent.push(init)
      return Promise.resolve(receipt())
    })

    const region = await openPanel()
    await waitFor(() => {
      expect(listReads).toBe(1)
    })
    fill(region, '동기화가 멈췄어요', '10월 12일 오후 3시부터 대시보드가 비어 있습니다.\n둘째 줄')
    submit(region)

    const block = await within(region).findByRole('group', {
      name: supportContent.receipt.heading,
    })
    expect(block).toHaveTextContent(REFERENCE)
    expect(block).toHaveTextContent('KST')
    expect(screen.getByRole('status')).toHaveTextContent(supportContent.received(REFERENCE))
    expect(sent).toHaveLength(1)
    expect(headerOf(sent[0], CSRF_HEADER)).toBe(CSRF_TOKEN)
    expect(headerOf(sent[0], 'Idempotency-Key')).toMatch(/^[A-Za-z0-9._:-]{16,128}$/)
    expect(JSON.parse(sent[0]?.body as string)).toEqual({
      category: 'SYNC_PLAN',
      title: '동기화가 멈췄어요',
      description: '10월 12일 오후 3시부터 대시보드가 비어 있습니다.\n둘째 줄',
      contact: 'IN_APP',
    })
    // 다음 문의를 위해 글은 비운다.
    expect(within(region).getByLabelText(supportContent.titleLabel)).toHaveValue('')
    await waitFor(() => {
      expect(listReads).toBe(2)
    })
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('목표를 정하지 못한 접수는 미정 문장이다', async () => {
    signedIn()
    stubSupportSubmit(() => Promise.resolve(receipt(null)))
    const region = await openPanel()
    fill(region, '연말 문의', '내용입니다')
    submit(region)
    const block = await within(region).findByRole('group', {
      name: supportContent.receipt.heading,
    })
    expect(block).toHaveTextContent(supportContent.targetPending)
  })

  it('네트워크가 끊기거나 5xx면 입력을 보존하고 자동으로 다시 보내지 않으며 다시 누르면 같은 키다', async () => {
    signedIn()
    const keys: string[] = []
    let answer: 'network' | 'server' | 'ok' = 'network'
    stubSupportSubmit((init) => {
      keys.push(headerOf(init, 'Idempotency-Key') ?? '')
      if (answer === 'network') return Promise.reject(new TypeError('Failed to fetch'))
      if (answer === 'server') return Promise.resolve(json({ code: 'INTERNAL_ERROR' }, 500))
      return Promise.resolve(receipt())
    })
    const region = await openPanel()
    fill(region, '연결 문의', '연결이 끊겼어요')
    submit(region)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(supportContent.unanswered)
    })
    expect(within(region).getByLabelText(supportContent.titleLabel)).toHaveValue('연결 문의')
    expect(within(region).getByLabelText(supportContent.descriptionLabel)).toHaveValue(
      '연결이 끊겼어요',
    )
    // 기다려도 다시 보내지 않는다.
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(keys).toHaveLength(1)

    answer = 'server'
    submit(region)
    await waitFor(() => {
      expect(keys).toHaveLength(2)
    })
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(supportContent.unanswered)
    })
    answer = 'ok'
    submit(region)
    await within(region).findByRole('group', { name: supportContent.receipt.heading })
    expect(keys).toHaveLength(3)
    // 같은 입력의 재전송은 언제나 같은 키다.
    expect(new Set(keys).size).toBe(1)
  })

  it('응답 없는 접수 뒤 입력이 바뀌면 먼저 목록을 다시 읽어 확인을 청하고, 다시 누를 때 새 키로 보낸다', async () => {
    signedIn()
    let listReads = 0
    stubSupportInquiries(() => {
      listReads += 1
      return Promise.resolve(json({ supportContact: null, items: [] }))
    })
    const keys: string[] = []
    stubSupportSubmit((init) => {
      keys.push(headerOf(init, 'Idempotency-Key') ?? '')
      return Promise.reject(new TypeError('Failed to fetch'))
    })
    const region = await openPanel()
    await waitFor(() => {
      expect(listReads).toBe(1)
    })
    fill(region, '첫 제목', '내용')
    submit(region)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(supportContent.unanswered)
    })
    expect(keys).toHaveLength(1)
    fill(region, '고친 제목', '내용')
    submit(region)
    // 보내지 않고 목록을 다시 읽어 중복 접수 여부를 보인다.
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(supportContent.checkFirst)
    })
    await waitFor(() => {
      expect(listReads).toBe(2)
    })
    expect(keys).toHaveLength(1)
    expect(within(region).getByLabelText(supportContent.titleLabel)).toHaveValue('고친 제목')
    submit(region)
    await waitFor(() => {
      expect(keys).toHaveLength(2)
    })
    expect(keys[0]).not.toBe(keys[1])
  })

  it('재전송 응답이 처리된 상태여도 접수로 보고, 모양이 어긋난 지원 연락처는 그 필드만 버린다', async () => {
    signedIn()
    stubSupportInquiries(() =>
      Promise.resolve(
        json({
          supportContact: 'not an email',
          items: [
            {
              reference: REFERENCE,
              category: 'OTHER',
              title: '목록은 남는다',
              description: '내용',
              receivedAt: '2026-10-12T01:00:00Z',
              targetAt: '2026-10-15T09:00:00Z',
              targetState: 'SET',
              status: 'RECEIVED',
              answer: null,
              answeredAt: null,
            },
          ],
        }),
      ),
    )
    stubSupportSubmit(() =>
      Promise.resolve(
        json(
          {
            reference: REFERENCE,
            receivedAt: '2026-10-12T01:00:00Z',
            targetAt: '2026-10-15T09:00:00Z',
            targetState: 'SET',
            status: 'ANSWERED',
          },
          200,
        ),
      ),
    )
    const region = await openPanel()
    expect(await within(region).findByText('목록은 남는다')).toBeInTheDocument()
    expect(within(region).queryByRole('link')).toBeNull()
    fill(region, '문의', '내용')
    submit(region)
    expect(
      await within(region).findByRole('group', { name: supportContent.receipt.heading }),
    ).toHaveTextContent(REFERENCE)
  })

  it('서버의 민감값 거절은 그 칸에 붙고 초점을 옮기며, 상한 거절은 문장 하나이고 입력은 남는다', async () => {
    signedIn()
    let next: Response = json(
      {
        code: 'INQUIRY_SENSITIVE_CONTENT',
        message: '…',
        correlationId: 'c',
        field: 'description',
        rule: 'FINANCIAL',
      },
      422,
    )
    stubSupportSubmit(() => Promise.resolve(next))
    const region = await openPanel()
    fill(region, '문의', '내용입니다')
    submit(region)
    const description = within(region).getByLabelText(supportContent.descriptionLabel)
    await waitFor(() => {
      expect(description).toHaveFocus()
    })
    expect(description).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('status')).toHaveTextContent(sensitiveRuleMessage('FINANCIAL'))

    next = json({ code: 'INQUIRY_RATE_LIMITED', message: '…', correlationId: 'c' }, 429)
    submit(region)
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(
        inquiryRefusalMessage('INQUIRY_RATE_LIMITED'),
      )
    })
    expect(within(region).getByLabelText(supportContent.titleLabel)).toHaveValue('문의')
  })

  it('세션이 끝났으면(401) 만료 이동 하나다', async () => {
    signedIn()
    stubSupportSubmit(() =>
      Promise.resolve(json({ code: 'UNAUTHENTICATED', message: '…', correlationId: 'c' }, 401)),
    )
    const region = await openPanel()
    fill(region, '문의', '내용')
    submit(region)
    await waitFor(() => {
      expect(replaceLocation).toHaveBeenCalledWith(noticePath('expired'))
    })
  })

  it('이메일 답변을 고르면 주소 칸이 생기고 비어 있으면 보내지 않는다', async () => {
    signedIn()
    const region = await openPanel()
    fill(region, '문의', '내용')
    fireEvent.change(within(region).getByLabelText(supportContent.contactLabel), {
      target: { value: 'EMAIL' },
    })
    const email = within(region).getByLabelText(supportContent.emailLabel)
    submit(region)
    await waitFor(() => {
      expect(email).toHaveFocus()
    })
    expect(email).toHaveAttribute('aria-invalid', 'true')
    expect(getObservedRequests()).toEqual([])
  })
})

describe('문의 규칙과 카피', () => {
  it('공유 표의 모든 입력에 서버와 같은 규칙 code를 고른다', () => {
    // 서버(`InquiryRulesTest`)가 같은 파일을 읽는다. `{{repeat:X:N}}`은 X를 N번 이은 값이다.
    const expand = (input: string): string =>
      input.replace(/\{\{repeat:([^:}]*):(\d+)\}\}/g, (_, unit: string, count: string) =>
        unit.repeat(Number(count)),
      )
    expect(rulesFixture.cases.length).toBeGreaterThan(30)
    for (const row of rulesFixture.cases) {
      expect({ input: row.input, rule: sensitiveRuleOf(expand(row.input)) }).toEqual({
        input: row.input,
        rule: row.rule,
      })
    }
  })

  it('카피에 금지 표현과 보장·SLA·24시간 약속이 없다', () => {
    for (const text of supportCopyStrings) {
      for (const word of FORBIDDEN_COPY_WORDS) {
        expect(text).not.toContain(word)
      }
      expect(text).not.toMatch(/SLA|보장|24시간 (대응|당직)|약속/)
    }
  })

  it('모르는 모양의 응답은 믿지 않는다', () => {
    expect(parseInquiryList({ items: [{ reference: 'nope' }] })).toBeNull()
    expect(
      parseInquiryList({
        supportContact: null,
        items: [
          {
            reference: REFERENCE,
            category: 'ACCOUNT',
            title: 't',
            receivedAt: '2026-10-12T01:00:00Z',
            targetAt: null,
            targetState: 'SET',
            status: 'RECEIVED',
            answer: null,
            answeredAt: null,
          },
        ],
      }),
    ).toBeNull()
    expect(
      parseInquiryList({
        supportContact: null,
        items: [
          {
            reference: REFERENCE,
            category: 'ACCOUNT',
            title: 't',
            receivedAt: '2026-10-12T01:00:00Z',
            targetAt: '2026-10-15T09:00:00Z',
            targetState: 'SET',
            status: 'RECEIVED',
            answer: '먼저 온 답변',
            answeredAt: null,
          },
        ],
      }),
    ).toBeNull()
    expect(parseReceipt({ reference: REFERENCE, receivedAt: 'x', status: 'RECEIVED' })).toBeNull()
  })
})
