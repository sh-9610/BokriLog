import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { CSRF_HEADER } from '../content/auth'
import { FORBIDDEN_COPY_WORDS } from '../content/copyRules'
import {
  operatorContent,
  operatorCopyStrings,
  operatorRefusalMessage,
  UPDATE_INQUIRY_COMMAND,
} from '../content/operator'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubOperatorAudit,
  stubOperatorInquiries,
  stubOperatorInquiryDetail,
  stubOperatorInquiryMetrics,
  stubOperatorRestrictionCommand,
  stubOperatorRestrictions,
  stubSessionRequest,
} from '../test/setup'
import { OperatorScreen } from './OperatorScreen'

vi.mock('../session/navigation', () => ({ navigateTo: vi.fn(), replaceLocation: vi.fn() }))

/**
 * Story 3.6: 운영자 화면의 문의 처리 영역. 최소화한 목록 → 상세(본문) → 상태·답변·사유 → 준비 → 확인 대화상자(전이
 * 미리보기, 문구 재입력, 취소 기본 포커스) → 확정·취소, 그리고 기간 집계.
 */

const copy = operatorContent.inquiry

const dialogCopy = operatorContent.inquiryDialog

const CSRF_TOKEN = 'operator-inquiry-csrf'

const REFERENCE = 'Q-ABCDEFGH23'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function summary(status = 'RECEIVED'): Record<string, unknown> {
  const answered = status === 'ANSWERED' || status === 'RESOLVED'
  return {
    reference: REFERENCE,
    category: 'SYNC_PLAN',
    title: '동기화가 멈췄어요',
    status,
    receivedAt: '2026-10-12T01:00:00Z',
    targetAt: '2026-10-15T09:00:00Z',
    targetState: 'SET',
    firstAnsweredAt: answered ? '2026-10-13T02:00:00Z' : null,
    targetMet: answered ? true : null,
    contact: 'EMAIL',
    replyEmail: 'a***@e***',
    resolvedAt: null,
  }
}

async function open(): Promise<HTMLElement> {
  stubSessionRequest(() =>
    Promise.resolve(json({ authenticated: true, destination: 'operator', csrfToken: CSRF_TOKEN })),
  )
  stubOperatorAudit(() => Promise.resolve(json({ items: [], nextCursor: null })))
  stubOperatorRestrictions(() => Promise.resolve(json({ items: [] })))
  render(<OperatorScreen />)
  await screen.findByRole('heading', { level: 2, name: copy.heading })
  return screen.getByRole('region', { name: copy.heading })
}

beforeEach(() => {
  window.history.pushState({}, '', '/operator')
})

describe('OperatorScreen 문의 처리', () => {
  it('목록은 최소화한 정보이고 상세에서만 본문이 보인다', async () => {
    stubOperatorInquiries(() => Promise.resolve(json({ items: [summary()] })))
    stubOperatorInquiryDetail(REFERENCE, () =>
      Promise.resolve(
        json({
          summary: summary(),
          description: '어제부터 대시보드가 비어 있습니다.',
          answer: null,
          replyEmail: 'alice@example.test',
        }),
      ),
    )
    const section = await open()
    const list = await within(section).findByRole('list', { name: copy.listHeading })
    const row = within(list).getByRole('listitem')
    expect(row).toHaveTextContent('동기화가 멈췄어요')
    expect(row).toHaveTextContent('접수됨')
    expect(row).toHaveTextContent('a***@e***')
    expect(row).not.toHaveTextContent('대시보드가 비어')
    expect(within(section).queryByLabelText(copy.reasonLabel)).not.toBeInTheDocument()

    fireEvent.click(within(row).getByRole('button', { name: copy.select(REFERENCE) }))
    const heading = await within(section).findByRole('heading', {
      level: 3,
      name: `${copy.detail.heading} ${REFERENCE}`,
    })
    await waitFor(() => {
      expect(heading).toHaveFocus()
    })
    expect(
      await within(section).findByText('어제부터 대시보드가 비어 있습니다.'),
    ).toBeInTheDocument()
    // 상세에서만 회신 이메일 원문이 보인다(목록은 가림).
    expect(within(section).getByText('alice@example.test')).toBeInTheDocument()
    // 접수 상태에서 갈 수 있는 다음 상태만 고를 수 있다.
    const options = within(within(section).getByLabelText(copy.statusLabel)).getAllByRole('option')
    expect(options.map((option) => option.textContent)).toEqual(['처리 중', '답변 완료'])
  })

  it('상태로 거르고 접수 번호로 바로 열며, 해결된 문의는 옮길 상태가 없다', async () => {
    const urls: string[] = []
    stubOperatorInquiries((url) => {
      urls.push(url)
      return Promise.resolve(
        json({ items: url.includes('status=RESOLVED') ? [summary('RESOLVED')] : [] }),
      )
    })
    stubOperatorInquiryDetail(REFERENCE, () =>
      Promise.resolve(
        json({
          summary: summary('RESOLVED'),
          description: '본문',
          answer: '답변',
          replyEmail: null,
        }),
      ),
    )
    const section = await open()
    expect(await within(section).findByText(copy.empty)).toBeInTheDocument()
    fireEvent.change(within(section).getByLabelText(copy.filterLabel), {
      target: { value: 'RESOLVED' },
    })
    await waitFor(() => {
      expect(urls).toContain('/api/operator/inquiries?status=RESOLVED')
    })
    fireEvent.change(within(section).getByLabelText(copy.lookupLabel), {
      target: { value: REFERENCE.toLowerCase() },
    })
    fireEvent.click(within(section).getByRole('button', { name: copy.lookupButton }))
    expect(await within(section).findByText(copy.noNextStatus)).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: copy.start })).not.toBeInTheDocument()
  })

  it('목록·상세·집계 요청이 전송층에서 거부돼도 실패로 접는다', async () => {
    stubOperatorInquiries(() => Promise.reject(new TypeError('Failed to fetch')))
    stubOperatorInquiryMetrics(() => Promise.reject(new TypeError('Failed to fetch')))
    const section = await open()
    expect(await within(section).findByText(copy.failed)).toBeInTheDocument()
    const metrics = within(section).getByRole('group', { name: copy.metrics.heading })
    fireEvent.click(within(metrics).getByRole('button', { name: copy.metrics.run }))
    expect(await within(metrics).findByText(copy.metrics.failed)).toBeInTheDocument()
  })

  it('답변 완료를 준비하고 미리보기를 확인한 뒤 문구를 다시 입력해 기록한다', async () => {
    let listed = 0
    stubOperatorInquiries(() => {
      listed += 1
      return Promise.resolve(json({ items: [summary(listed > 1 ? 'ANSWERED' : 'RECEIVED')] }))
    })
    stubOperatorInquiryDetail(REFERENCE, () =>
      Promise.resolve(json({ summary: summary(), description: '본문입니다', answer: null })),
    )
    const bodies: unknown[] = []
    const keys: (string | null)[] = []
    stubOperatorRestrictionCommand(UPDATE_INQUIRY_COMMAND, 'prepare', (init) => {
      bodies.push(JSON.parse(init?.body as string))
      const headers = new Headers(init?.headers)
      keys.push(headers.get('Idempotency-Key'))
      expect(headers.get(CSRF_HEADER)).toBe(CSRF_TOKEN)
      return Promise.resolve(
        json({
          operationId: 'op_inquiry_prepared',
          command: UPDATE_INQUIRY_COMMAND,
          scope: 'INQUIRY:ANSWERED',
          target: REFERENCE,
          reason: '문의 답변 기록',
          confirmationPhrase: `문의 답변 완료 ${REFERENCE}`,
          policyVersion: 'OPERATOR-COMMAND-1.5',
          requestedAt: '2026-10-13T00:00:00Z',
          impact: {
            reference: REFERENCE,
            currentStatus: 'RECEIVED',
            nextStatus: 'ANSWERED',
            targetAt: '2026-10-15T09:00:00Z',
            answerProvided: true,
          },
        }),
      )
    })
    stubOperatorRestrictionCommand(UPDATE_INQUIRY_COMMAND, 'confirm', (init) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      expect(JSON.parse(init?.body as string)).toEqual({
        operationId: 'op_inquiry_prepared',
        confirmation: `문의 답변 완료 ${REFERENCE}`,
      })
      return Promise.resolve(
        json({
          operationId: 'op_inquiry_prepared',
          outcome: 'COMPLETED',
          command: UPDATE_INQUIRY_COMMAND,
          status: 'ANSWERED',
          transitionedAt: '2026-10-13T02:00:00Z',
        }),
      )
    })
    const section = await open()
    const list = await within(section).findByRole('list', { name: copy.listHeading })
    fireEvent.click(within(list).getByRole('button', { name: copy.select(REFERENCE) }))
    await within(section).findByText('본문입니다')

    const start = within(section).getByRole('button', { name: copy.start })
    expect(start).toHaveAttribute('aria-disabled', 'true')
    fireEvent.change(within(section).getByLabelText(copy.statusLabel), {
      target: { value: 'ANSWERED' },
    })
    fireEvent.change(within(section).getByLabelText(copy.reasonLabel), {
      target: { value: '문의 답변 기록' },
    })
    // 답변이 없으면 답변 완료를 준비할 수 없다.
    expect(start).toHaveAttribute('aria-disabled', 'true')
    fireEvent.change(within(section).getByLabelText(copy.answerLabel), {
      target: { value: '확인 후 조치했습니다.' },
    })
    expect(start).toHaveAttribute('aria-disabled', 'false')
    fireEvent.click(start)

    const dialog = await screen.findByRole('dialog', { name: dialogCopy.title })
    expect(within(dialog).getByRole('button', { name: dialogCopy.cancel })).toHaveFocus()
    expect(dialog.textContent).toContain(`${dialogCopy.transition}: 접수됨 → 답변 완료`)
    expect(dialog.textContent).toContain(`${dialogCopy.answer}: 확인 후 조치했습니다.`)
    expect(bodies).toEqual([
      {
        reference: REFERENCE,
        status: 'ANSWERED',
        reason: '문의 답변 기록',
        answer: '확인 후 조치했습니다.',
      },
    ])
    const confirm = within(dialog).getByRole('button', { name: dialogCopy.confirm })
    expect(confirm).toHaveAttribute('aria-disabled', 'true')
    fireEvent.change(within(dialog).getByRole('textbox'), {
      target: { value: `문의 답변 완료 ${REFERENCE}` },
    })
    fireEvent.click(confirm)

    const result = await screen.findByRole('heading', { level: 3, name: copy.result.heading })
    await waitFor(() => {
      expect(result).toHaveFocus()
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('status').textContent).toBe(
      operatorContent.status.inquiryCompleted('답변 완료'),
    )
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
    // 기록 뒤 목록을 다시 읽는다.
    await waitFor(() => {
      expect(within(list).getByRole('listitem')).toHaveTextContent('답변 완료')
    })
    expect(getObservedStorageEntries()).toEqual([])
    expect(new URL(window.location.href).search).toBe('')
  })

  it('전이 거절은 문장 하나이고 목록을 다시 읽는다', async () => {
    let listed = 0
    stubOperatorInquiries(() => {
      listed += 1
      return Promise.resolve(json({ items: [summary('ANSWERED')] }))
    })
    stubOperatorInquiryDetail(REFERENCE, () =>
      Promise.resolve(
        json({ summary: summary('ANSWERED'), description: '본문', answer: '답변했습니다' }),
      ),
    )
    stubOperatorRestrictionCommand(UPDATE_INQUIRY_COMMAND, 'prepare', () =>
      Promise.resolve(
        json({ code: 'INQUIRY_TRANSITION_INVALID', message: '…', correlationId: 'c' }, 409),
      ),
    )
    const section = await open()
    const list = await within(section).findByRole('list', { name: copy.listHeading })
    fireEvent.click(within(list).getByRole('button', { name: copy.select(REFERENCE) }))
    expect(await within(section).findByText('답변했습니다')).toBeInTheDocument()
    fireEvent.change(within(section).getByLabelText(copy.reasonLabel), {
      target: { value: '되돌리기 시도' },
    })
    fireEvent.click(within(section).getByRole('button', { name: copy.start }))
    await waitFor(() => {
      expect(screen.getByRole('status').textContent).toBe(
        operatorRefusalMessage('INQUIRY_TRANSITION_INVALID', UPDATE_INQUIRY_COMMAND),
      )
    })
    await waitFor(() => {
      expect(listed).toBe(2)
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('기간 집계는 수만 보인다', async () => {
    stubOperatorInquiries(() => Promise.resolve(json({ items: [] })))
    const urls: string[] = []
    stubOperatorInquiryMetrics((url) => {
      urls.push(url)
      return Promise.resolve(
        json({
          from: '2026-09-01',
          to: '2026-09-30',
          asOf: '2026-10-10T00:00:00Z',
          calendarVersion: 'KR-HOLIDAY-2026.1',
          due: 4,
          metTarget: 3,
          ratePercent: 75.0,
          calendarPending: 1,
          measured: 3,
          totalBusinessMinutes: 2730,
          medianBusinessMinutes: 600.0,
          purgeHorizon: '2026-07-12T00:00:00Z',
        }),
      )
    })
    const section = await open()
    expect(await within(section).findByText(copy.empty)).toBeInTheDocument()
    const metrics = within(section).getByRole('group', { name: copy.metrics.heading })
    fireEvent.change(within(metrics).getByLabelText(copy.metrics.fromLabel), {
      target: { value: '2026-09-01' },
    })
    fireEvent.change(within(metrics).getByLabelText(copy.metrics.toLabel), {
      target: { value: '2026-09-30' },
    })
    fireEvent.click(within(metrics).getByRole('button', { name: copy.metrics.run }))
    expect(await within(metrics).findByText('75.0%')).toBeInTheDocument()
    expect(within(metrics).getByText('10.0시간')).toBeInTheDocument()
    expect(within(metrics).getByText('45.5시간')).toBeInTheDocument()
    expect(metrics).toHaveTextContent(
      '이전에 해결된 문의는 보존 기간이 지나 삭제돼 집계에 없습니다',
    )
    expect(urls).toEqual(['/api/operator/inquiries/metrics?from=2026-09-01&to=2026-09-30'])
    expect(getObservedAllowedRequests().filter((request) => request.startsWith('POST'))).toEqual([])
  })

  it('운영자 카피에 금지 표현이 없다', () => {
    for (const text of operatorCopyStrings) {
      for (const word of FORBIDDEN_COPY_WORDS) {
        expect(text).not.toContain(word)
      }
    }
  })
})
