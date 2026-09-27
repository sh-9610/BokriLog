import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { InitialInputsCollection } from './InitialInputsCollection'

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function job(status: string) {
  return {
    jobId: 'job-1',
    status,
    lastCheckedAt: '2026-09-27T00:00:00Z',
  }
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input
  if (input instanceof URL) return input.href
  return input.url
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('InitialInputsCollection', () => {
  it('accepts an empty classification response without reporting a load error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = requestUrl(input)
        if (url.includes('/cashflow-classification')) return Promise.resolve(response(null, 204))
        return Promise.resolve(response({ job: job('INPUTS_COMPLETE') }))
      }),
    )

    render(<InitialInputsCollection intentId="intent-1" csrfToken="csrf-token" />)

    expect(await screen.findByRole('button', { name: '원천 사건 판정 시작' })).toBeInTheDocument()
    expect(screen.queryByText(/판정 상태를 불러오지 못했습니다/)).not.toBeInTheDocument()
  })

  it.each([
    ['RUNNING', '수집 중'],
    ['QUEUED', '수집 중'],
    ['INPUTS_COMPLETE', '입력 수집 완료'],
    ['NEEDS_REVIEW', '검토 필요'],
    ['RETRY_REQUIRED', '재시도 필요'],
  ])('announces %s with its safe status label', async (status, label) => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockImplementation((input) =>
          Promise.resolve(
            requestUrl(input).includes('/cashflow-classification')
              ? response(null)
              : response({ job: job(status) }),
          ),
        ),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)

    expect(await screen.findByRole('status')).toHaveTextContent(label)
    expect(screen.getByText(/마지막 확인/)).toBeInTheDocument()
    expect(screen.queryByText(/USD|수익률|후보 금액/)).not.toBeInTheDocument()
    expect(screen.getByText(/공식 현재가치나 계산 지표가 아니며/)).toBeInTheDocument()
  })

  it('submits the intent id with same-origin CSRF and idempotency headers', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ job: null }))
      .mockResolvedValueOnce(response(null))
      .mockResolvedValueOnce(response(job('QUEUED'), 202))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'initial-inputs-key-001' })

    render(<InitialInputsCollection csrfToken="csrf-value" intentId="intent-123" />)
    fireEvent.click(await screen.findByRole('button', { name: '초기 입력 수집 시작' }))

    await screen.findByText('수집 중')
    const postCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    const [url, init] = postCall ?? []
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/snapshots/first/inputs?intentId=intent-123')
    expect(url).toBe('/api/snapshots/first/inputs')
    expect(init?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'csrf-value',
      'Idempotency-Key': 'initial-inputs-key-001',
    })
    expect(init?.body).toBe(JSON.stringify({ intentId: 'intent-123' }))
  })

  it('offers an accessible retry after a retryable failure', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(response({ job: job('RETRY_REQUIRED') }))
      .mockResolvedValueOnce(response(null))
      .mockResolvedValueOnce(response(job('QUEUED'), 202))
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'retry-key-001' })

    render(<InitialInputsCollection csrfToken="csrf-value" intentId="intent-123" />)
    fireEvent.click(await screen.findByRole('button', { name: '수집 다시 시도' }))

    expect(await screen.findByText('수집 중')).toBeInTheDocument()
    await waitFor(() => {
      expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    })
  })

  it('keeps retryable work blocked when the session has no CSRF token', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockImplementation((input) =>
          Promise.resolve(
            requestUrl(input).includes('/cashflow-classification')
              ? response(null)
              : response({ job: job('RETRY_REQUIRED') }),
          ),
        ),
    )
    render(<InitialInputsCollection csrfToken={null} intentId="intent-123" />)

    fireEvent.click(await screen.findByRole('button', { name: '수집 다시 시도' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('세션이 만료되었습니다')
  })

  it('loads the selected intent after a prop change and hides the previous intent retry', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation((input) => {
      const url = requestUrl(input)
      if (url.includes('/cashflow-classification')) return Promise.resolve(response(null))
      if (url.includes('intent-old'))
        return Promise.resolve(response({ job: job('RETRY_REQUIRED') }))
      return Promise.resolve(response({ job: null }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<InitialInputsCollection csrfToken="csrf" intentId="intent-old" />)

    expect(await screen.findByRole('button', { name: '수집 다시 시도' })).toBeInTheDocument()
    view.rerender(<InitialInputsCollection csrfToken="csrf" intentId="intent-current" />)

    expect(await screen.findByText('수집을 아직 시작하지 않았습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '수집 다시 시도' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.map(([url]) => url)).toContain(
      '/api/snapshots/first/inputs?intentId=intent-old',
    )
    expect(fetchMock.mock.calls.map(([url]) => url)).toContain(
      '/api/snapshots/first/inputs?intentId=intent-current',
    )
  })

  it('maps allowlisted and unknown failure codes to localized safe copy', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation((input) => {
      const url = requestUrl(input)
      if (url.includes('/cashflow-classification')) return Promise.resolve(response(null))
      if (url.includes('intent-known')) {
        return Promise.resolve(
          response({ job: { ...job('RETRY_REQUIRED'), failureCode: 'PROVIDER_UNAVAILABLE' } }),
        )
      }
      return Promise.resolve(
        response({ job: { ...job('NEEDS_REVIEW'), failureCode: 'raw-provider-detail' } }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<InitialInputsCollection csrfToken="csrf" intentId="intent-known" />)

    expect(await screen.findByText(/연결 상태를 확인한 뒤 아래에서 다시 시도/)).toBeInTheDocument()
    expect(screen.queryByText('PROVIDER_UNAVAILABLE')).not.toBeInTheDocument()
    view.rerender(<InitialInputsCollection csrfToken="csrf" intentId="intent-unknown" />)

    expect(await screen.findByText(/수집 상태를 확인하지 못했습니다/)).toBeInTheDocument()
    expect(screen.queryByText('raw-provider-detail')).not.toBeInTheDocument()
  })

  it('renders a safe fallback for an invalid last-checked timestamp', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockImplementation((input) =>
        Promise.resolve(
          requestUrl(input).includes('/cashflow-classification')
            ? response(null)
            : response({
                job: { ...job('INPUTS_COMPLETE'), lastCheckedAt: 'not-a-timestamp' },
              }),
        ),
      ),
    )
    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-time" />)

    expect(await screen.findByText('확인 시각을 표시할 수 없습니다.')).toBeInTheDocument()
  })

  it('starts classification for a complete candidate and renders only safe counts', async () => {
    const summary = {
      jobId: 'classification-1',
      status: 'NEEDS_REVIEW',
      depositCount: 2,
      withdrawalCount: 1,
      excludedCount: 4,
      reviewCount: 1,
      reasonCode: 'UNRECOGNIZED_EVENT_TYPE',
    }
    const fetchMock = vi.fn<typeof fetch>().mockImplementation((input, init) => {
      const url = requestUrl(input)
      if (url.includes('/cashflow-classification') && init?.method === 'POST') {
        return Promise.resolve(response(summary, 202))
      }
      if (url.includes('/cashflow-classification')) return Promise.resolve(response(null))
      return Promise.resolve(response({ job: job('INPUTS_COMPLETE') }))
    })
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'classification-key-001' })

    render(<InitialInputsCollection csrfToken="csrf-value" intentId="intent-123" />)
    fireEvent.click(await screen.findByRole('button', { name: '원천 사건 판정 시작' }))

    expect(
      await screen.findByText('입금 사건: 2 · 출금 사건: 1 · 제외 사건: 4 · 검토 필요: 1'),
    ).toBeInTheDocument()
    expect(screen.getByText(/등록되지 않은 사건 유형/)).toBeInTheDocument()
    expect(screen.queryByText(/BTC|USDT|거래 시각|원수량|USD/)).not.toBeInTheDocument()
    const postCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(postCall?.[0]).toBe('/api/snapshots/first/cashflow-classification')
    expect(postCall?.[1]?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'csrf-value',
      'Idempotency-Key': 'classification-key-001',
    })
    expect(postCall?.[1]?.body).toBe(JSON.stringify({ intentId: 'intent-123' }))
  })
})
