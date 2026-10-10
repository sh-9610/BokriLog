import { act, render, renderHook, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { serviceNoticeContent, serviceNoticeCopyStrings } from '../content/serviceNotice'
import {
  NOTICE_RETRY_MS,
  parseServiceNotices,
  useServiceNotices,
  type ServiceNoticeView,
} from '../session/useServiceNotices'
import {
  getObservedAllowedRequests,
  getObservedStorageEntries,
  stubServiceNotices,
} from '../test/setup'
import { ServiceNotice } from './ServiceNotice'

/**
 * Story 3.5 AC-4·AC-5: 서비스 공지 영역과 그 조회. 상태 표지는 문장이고, 마지막 정상값과 분리된 정적 영역이며, 재시도
 * 버튼이 없다. 조회 실패는 만료 전에만 마지막 응답을 유지하고 아니면 "확인하지 못했습니다" 한 문장이다.
 */

const copy = serviceNoticeContent

const CONTACT = 'support@bokrilog.test'

const NOTICE = {
  kind: 'NOTICE',
  incidentId: '7d0c2b1a-4e5f-4a6b-8c9d-0e1f2a3b4c5d',
  version: 2,
  status: 'SCOPE_CHANGED',
  startedAt: '2026-10-10T00:00:00Z',
  affectedFeatures: ['SYNC', 'CALCULATION_DISPLAY'],
  continuingFeatures: ['NEW_CONNECTION', 'ADVERTISING'],
  message: '동기화 지연 원인 조사 중',
  nextUpdateAt: '2026-10-10T03:00:00Z',
  supportContact: CONTACT,
}

const RECOVERED = {
  ...NOTICE,
  version: 3,
  status: 'RECOVERED',
  message: '원인 수정 배포 완료',
  nextUpdateAt: undefined,
  recoveredAt: '2026-10-10T05:00:00Z',
}

const RESTRICTED = {
  kind: 'RESTRICTED_WITHOUT_NOTICE',
  affectedFeatures: ['NEW_CONNECTION'],
  continuingFeatures: ['SYNC', 'CALCULATION_DISPLAY', 'ADVERTISING'],
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function body(items: unknown[], expiresInMs = 5 * 60_000): Record<string, unknown> {
  const now = Date.now()
  return {
    asOf: new Date(now).toISOString(),
    expiresAt: new Date(now + expiresInMs).toISOString(),
    items,
  }
}

function ready(items: unknown[]): ServiceNoticeView {
  const parsed = parseServiceNotices(body(items))
  if (parsed === null) throw new Error('공지 응답이 아니다')
  return { kind: 'ready', notices: parsed }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('ServiceNotice', () => {
  it('확인 중이거나 공지가 없으면 아무것도 그리지 않는다', () => {
    const { container, rerender } = render(<ServiceNotice view={{ kind: 'checking' }} />)
    expect(container).toBeEmptyDOMElement()
    rerender(<ServiceNotice view={ready([])} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('공지는 상태 표지 문장·시각·영향·계속 가능 기능·다음 안내·문의 링크를 정적 영역에 보인다', () => {
    render(<ServiceNotice view={ready([NOTICE])} />)
    const region = screen.getByRole('region', { name: copy.heading })
    expect(within(region).getByText(`${copy.stateLabel}: 범위 변경`)).toBeInTheDocument()
    expect(within(region).getByText(NOTICE.message)).toBeInTheDocument()
    expect(region.textContent).toContain(copy.startedAt)
    expect(region.textContent).toContain(`${copy.affected}동기화와 새 계산 반영, 계산 결과 표시`)
    expect(region.textContent).toContain(`${copy.continuing}새 거래소 연결, 광고`)
    expect(region.textContent).toContain(copy.nextUpdateAt)
    expect(region.textContent).not.toContain(copy.recoveredAt)
    const link = within(region).getByRole('link', { name: CONTACT })
    expect(link).toHaveAttribute('href', `mailto:${CONTACT}`)
    // 재시도 버튼이 없고, live region을 늘리지 않는다.
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(region).not.toHaveAttribute('aria-live')
    expect(within(region).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('다음 안내 시각이 지난 공지는 늦어지고 있다고 말하고 시각에 KST를 붙인다', () => {
    render(<ServiceNotice view={ready([{ ...NOTICE, overdue: true }])} />)
    const region = screen.getByRole('region', { name: copy.heading })
    expect(region.textContent).toContain('다음 안내가 늦어지고 있습니다(예정 ')
    expect(region.textContent).toContain('KST)')
    expect(region.textContent).toMatch(/KST/)
  })

  it('복구 공지는 복구 시각을 보이고 다음 안내를 보이지 않는다', () => {
    render(<ServiceNotice view={ready([RECOVERED])} />)
    const region = screen.getByRole('region', { name: copy.heading })
    expect(within(region).getByText(`${copy.stateLabel}: 복구됨`)).toBeInTheDocument()
    expect(region.textContent).toContain(copy.recoveredAt)
    expect(region.textContent).not.toContain(copy.nextUpdateAt)
  })

  it('공지 없는 제한은 운영 제한 표지와 기본 문장이고 연락처가 없으면 링크가 없다', () => {
    render(<ServiceNotice view={ready([RESTRICTED])} />)
    const region = screen.getByRole('region', { name: copy.heading })
    expect(within(region).getByText(`${copy.stateLabel}: 운영 제한`)).toBeInTheDocument()
    expect(within(region).getByText(copy.restrictedBody)).toBeInTheDocument()
    expect(region.textContent).toContain(`${copy.affected}새 거래소 연결`)
    expect(within(region).queryByRole('link')).not.toBeInTheDocument()
    expect(region.textContent).not.toContain(copy.support)
  })

  it('확인하지 못했으면 그 한 문장뿐이다', () => {
    render(<ServiceNotice view={{ kind: 'unavailable' }} />)
    const region = screen.getByRole('region', { name: copy.heading })
    expect(region.textContent).toBe(`${copy.heading}${copy.unavailable}`)
    expect(within(region).queryByRole('button')).not.toBeInTheDocument()
  })

  it('카피에 금지 표현이 없다', () => {
    for (const text of serviceNoticeCopyStrings) {
      expect(text).not.toMatch(/실시간|정확|보장|축하|손실|스트릭|배지|다시 시도/)
    }
  })
})

describe('parseServiceNotices', () => {
  it('모르는 모양이 하나라도 있으면 묶음 전체를 믿지 않는다', () => {
    expect(parseServiceNotices(body([NOTICE, RECOVERED, RESTRICTED]))?.items).toHaveLength(3)
    expect(parseServiceNotices(body([{ ...NOTICE, kind: 'BANNER' }]))).toBeNull()
    expect(parseServiceNotices(body([{ ...NOTICE, affectedFeatures: ['PAYMENTS'] }]))).toBeNull()
    expect(parseServiceNotices(body([{ ...NOTICE, affectedFeatures: [] }]))).toBeNull()
    expect(parseServiceNotices(body([{ ...NOTICE, status: 'PAUSED' }]))).toBeNull()
    // 상태별 다음 시각: 진행 중에는 다음 안내 시각만, 복구에는 복구 시각만.
    expect(parseServiceNotices(body([{ ...NOTICE, nextUpdateAt: null }]))).toBeNull()
    expect(
      parseServiceNotices(body([{ ...NOTICE, recoveredAt: '2026-10-10T05:00:00Z' }])),
    ).toBeNull()
    expect(
      parseServiceNotices(body([{ ...RECOVERED, nextUpdateAt: '2026-10-10T06:00:00Z' }])),
    ).toBeNull()
    // 연락처는 이메일 모양만 — 링크에 다른 스킴·쿼리가 실리지 않는다.
    expect(
      parseServiceNotices(body([{ ...NOTICE, supportContact: 'javascript:alert(1)' }])),
    ).toBeNull()
    expect(parseServiceNotices(body([{ ...NOTICE, supportContact: 'a@b.test?cc=x' }]))).toBeNull()
    expect(parseServiceNotices({ items: [] })).toBeNull()
    // 늦어짐은 진행 중인 공지에만 있다.
    expect(parseServiceNotices(body([{ ...RECOVERED, overdue: true }]))).toBeNull()
    expect(parseServiceNotices(body([{ ...NOTICE, overdue: 'yes' }]))).toBeNull()
    expect(parseServiceNotices(body([{ ...NOTICE, overdue: true }]))?.items[0]).toMatchObject({
      overdue: true,
    })
  })
})

describe('useServiceNotices', () => {
  it('세션이 준비되면 읽고 검증한 응답을 보인다', async () => {
    stubServiceNotices(() => Promise.resolve(json(body([NOTICE]))))
    const { result } = renderHook(() => useServiceNotices(true))
    expect(result.current.kind).toBe('checking')
    await vi.waitFor(() => {
      expect(result.current.kind).toBe('ready')
    })
    expect(getObservedAllowedRequests()).toEqual(['GET /api/notices'])
    expect(getObservedStorageEntries()).toEqual([])
  })

  it('세션이 준비되지 않으면 묻지 않는다', () => {
    stubServiceNotices(() => Promise.resolve(json(body([NOTICE]))))
    const { result } = renderHook(() => useServiceNotices(false))
    expect(result.current.kind).toBe('checking')
    expect(getObservedAllowedRequests()).toEqual([])
  })

  it('만료 전 실패는 마지막 응답을 유지하고 만료 뒤 실패는 확인하지 못했다고 말한다', async () => {
    vi.useFakeTimers()
    // 첫 응답만 정상이고 그 뒤는 500 하나와 모르는 모양들이다.
    const answers: (() => Response)[] = [
      () => json(body([NOTICE], 60_000)),
      () => json({ code: 'INTERNAL_ERROR', message: '오류', correlationId: 'c' }, 500),
    ]
    stubServiceNotices(() =>
      Promise.resolve((answers.shift() ?? (() => json({ unknown: true })))()),
    )
    const { result } = renderHook(() => useServiceNotices(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.kind).toBe('ready')

    // 남은 신뢰 시간(60초)의 절반에 다시 묻고, 500이지만 만료 전이라 유지한다.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000)
    })
    expect(getObservedAllowedRequests()).toHaveLength(2)
    expect(result.current.kind).toBe('ready')

    // 만료 전의 모르는 모양도 유지한다(15초 뒤). 만료가 지난 다시 읽기의 실패는 믿을 응답이 없으니 확인하지 못했다.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15_000)
    })
    expect(getObservedAllowedRequests()).toHaveLength(3)
    expect(result.current.kind).toBe('ready')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(16_000)
    })
    expect(getObservedAllowedRequests()).toHaveLength(4)
    expect(result.current.kind).toBe('unavailable')
  })

  it('401·403은 메모리의 응답을 지우고 다시 묻지 않는다', async () => {
    vi.useFakeTimers()
    const answers = [json(body([NOTICE])), json({ code: 'UNAUTHENTICATED' }, 401)]
    stubServiceNotices(() => Promise.resolve(answers.shift() ?? json({}, 401)))
    const { result } = renderHook(() => useServiceNotices(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.kind).toBe('ready')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5 * 60_000)
    })
    expect(result.current.kind).toBe('unavailable')
    const asked = getObservedAllowedRequests().length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(NOTICE_RETRY_MS * 3)
    })
    expect(getObservedAllowedRequests()).toHaveLength(asked)
  })

  it('첫 응답이 모르는 모양이면 확인하지 못했다고 말하고 일정 간격 뒤에 다시 묻는다', async () => {
    vi.useFakeTimers()
    const answers = [json({ items: 'x' }), json(body([RESTRICTED]))]
    stubServiceNotices(() => Promise.resolve(answers.shift() ?? json(body([]))))
    const { result } = renderHook(() => useServiceNotices(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.kind).toBe('unavailable')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(NOTICE_RETRY_MS)
    })
    expect(result.current.kind).toBe('ready')
  })
})

describe('useServiceNotices 신뢰 기한', () => {
  it('404도 메모리를 지우고 다시 묻지 않는다', async () => {
    vi.useFakeTimers()
    const answers = [json(body([NOTICE])), json({ code: 'NOT_FOUND' }, 404)]
    stubServiceNotices(() => Promise.resolve(answers.shift() ?? json({}, 404)))
    const { result } = renderHook(() => useServiceNotices(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.kind).toBe('ready')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5 * 60_000)
    })
    expect(result.current.kind).toBe('unavailable')
    const asked = getObservedAllowedRequests().length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(NOTICE_RETRY_MS * 3)
    })
    expect(getObservedAllowedRequests()).toHaveLength(asked)
  })

  it('신뢰 기간은 서버의 상대 기간이다 — 브라우저 시계가 서버와 달라도 받은 뒤 그 기간만큼 믿는다', async () => {
    vi.useFakeTimers()
    // 서버 시각이 브라우저보다 하루 앞서도(절대 시각을 비교하면 이미 지난 응답) 60초를 믿는다.
    const serverNow = Date.now() - 24 * 60 * 60_000
    const skewed = {
      asOf: new Date(serverNow).toISOString(),
      expiresAt: new Date(serverNow + 60_000).toISOString(),
      items: [NOTICE],
    }
    const answers = [json(skewed)]
    stubServiceNotices(() =>
      answers.length > 0
        ? Promise.resolve(answers.shift() ?? json({}))
        : new Promise<Response>(() => undefined),
    )
    const { result } = renderHook(() => useServiceNotices(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.kind).toBe('ready')
    // 다시 읽기가 답하지 않아도, 받은 뒤 60초가 지나면 기한 타이머가 그 응답을 접는다.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(59_000)
    })
    expect(result.current.kind).toBe('ready')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000)
    })
    expect(result.current.kind).toBe('unavailable')
  })

  it('늦어진 공지만 있는 응답은 5분 기한으로 받아 바닥 간격으로 몰아 묻지 않는다', async () => {
    vi.useFakeTimers()
    stubServiceNotices(() => Promise.resolve(json(body([{ ...NOTICE, overdue: true }]))))
    const { result } = renderHook(() => useServiceNotices(true))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.kind).toBe('ready')
    // 5분 기한의 절반(2분 30초) 전에는 다시 묻지 않는다.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(149_000)
    })
    expect(getObservedAllowedRequests()).toHaveLength(1)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000)
    })
    expect(getObservedAllowedRequests()).toHaveLength(2)
  })
})
