import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { planConditionContent } from '../content/planConditions'
import { atFirstSight, deferred } from '../test/firstSight'
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

  it('attaches no machine value to a last-checked time in an offset format the server never sends', async () => {
    const offset = '2026-09-26T15:06:05+09:00'
    vi.stubGlobal(
      'fetch',
      vi
        .fn<typeof fetch>()
        .mockImplementation((input) =>
          Promise.resolve(
            requestUrl(input).includes('/cashflow-classification')
              ? response(null)
              : response({ job: { ...job('INPUTS_COMPLETE'), lastCheckedAt: offset } }),
          ),
        ),
    )
    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-offset-time" />)

    const line = await screen.findByText(
      new RegExp(`${planConditionContent.collection.lastChecked}:`),
    )
    // eslint-disable-next-line testing-library/no-node-access -- 기계 값은 `time` 요소의 속성에만 있다.
    const time = line.querySelector('time')
    expect(time).not.toBeNull()
    expect(time).not.toHaveAttribute('datetime')
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

const completeClassification = {
  jobId: 'classification-1',
  status: 'COMPLETE',
  depositCount: 2,
  withdrawalCount: 1,
  excludedCount: 0,
  reviewCount: 0,
}

function conversion(overrides: Record<string, unknown> = {}) {
  return {
    jobId: 'conversion-1',
    status: 'CONVERTED_ESTIMATED',
    retryable: false,
    processedAt: '2026-09-27T01:00:00Z',
    flowCount: 3,
    convertedCount: 1,
    estimatedCount: 2,
    conversionRequiredCount: 0,
    reviewCount: 0,
    calculable: true,
    pricePolicyVersion: 'BYBIT-PRICE-1.0',
    calculationPolicyVersion: 'CALC-1.0',
    pendingAssets: [],
    ...overrides,
  }
}

const heldAssets = [
  {
    assetCode: 'GAP',
    count: 2,
    flows: [
      {
        direction: 'WITHDRAWAL',
        quantity: '2.000000000000000001',
        occurredAt: '2026-09-26T15:06:05Z',
        status: 'CONVERSION_REQUIRED',
        reasonCode: 'PRICE_CANDLE_MISSING',
        retryable: false,
      },
      {
        direction: 'DEPOSIT',
        quantity: '0.5',
        occurredAt: '2026-09-26T15:07:00Z',
        status: 'CONVERSION_REQUIRED',
        reasonCode: 'PRICE_CANDLE_MISSING',
        retryable: false,
      },
    ],
  },
  {
    assetCode: 'HALT',
    count: 1,
    flows: [
      {
        direction: 'DEPOSIT',
        quantity: '4',
        occurredAt: '2026-09-26T15:01:00Z',
        status: 'CONVERSION_REQUIRED',
        reasonCode: 'PRICE_ROUTE_UNAVAILABLE',
        retryable: false,
      },
    ],
  },
]

/**
 * Complete inputs and classification; the conversion endpoint answers from the handler. The
 * publication endpoint (Story 2.7) has no attempt and would admit one unless a handler says otherwise.
 */
function completeFetch(
  onConversion: (init: RequestInit | undefined) => Promise<Response>,
  onPublication: (init: RequestInit | undefined) => Promise<Response> = () =>
    Promise.resolve(response({ startable: true })),
): ReturnType<typeof vi.fn<typeof fetch>> {
  return vi.fn<typeof fetch>().mockImplementation((input, init) => {
    const url = requestUrl(input)
    if (url.includes('/plan-publication')) return onPublication(init)
    if (url.includes('/cashflow-conversion')) return onConversion(init)
    if (url.includes('/cashflow-classification'))
      return Promise.resolve(response(completeClassification))
    return Promise.resolve(response({ job: job('INPUTS_COMPLETE') }))
  })
}

describe('InitialInputsCollection conversion', () => {
  it('reads the conversion only after a complete classification and starts it same-origin', async () => {
    const fetchMock = completeFetch((init) =>
      Promise.resolve(
        init?.method === 'POST'
          ? response(conversion({ status: 'QUEUED', calculable: false, estimatedCount: 0 }), 202)
          : new Response(null, { status: 204 }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'conversion-key-001' })

    render(<InitialInputsCollection csrfToken="csrf-value" intentId="intent-123" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    fireEvent.click(await within(section).findByRole('button', { name: '사건 시점 환산 시작' }))

    expect(await within(section).findByText('환산 중')).toBeInTheDocument()
    const urls = fetchMock.mock.calls.map(([url]) => requestUrl(url))
    expect(urls.slice(0, 2)).toEqual([
      '/api/snapshots/first/inputs?intentId=intent-123',
      '/api/snapshots/first/cashflow-classification?intentId=intent-123',
    ])
    expect(urls[2]).toBe('/api/snapshots/first/cashflow-conversion?intentId=intent-123')
    const postCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(postCall?.[0]).toBe('/api/snapshots/first/cashflow-conversion')
    expect(postCall?.[1]?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'csrf-value',
      'Idempotency-Key': 'conversion-key-001',
    })
    expect(postCall?.[1]?.body).toBe(JSON.stringify({ intentId: 'intent-123' }))
  })

  it('does not read the conversion while the classification needs review', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation((input) => {
      const url = requestUrl(input)
      if (url.includes('/cashflow-classification'))
        return Promise.resolve(response({ ...completeClassification, status: 'NEEDS_REVIEW' }))
      return Promise.resolve(response({ job: job('INPUTS_COMPLETE') }))
    })
    vi.stubGlobal('fetch', fetchMock)

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    expect(await screen.findByText('검토 필요', { selector: 'p' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '사건 시점 USD 환산' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).not.toContainEqual(
      expect.stringContaining('/cashflow-conversion'),
    )
  })

  it('shows an estimated conversion with its policies and no amount, rate or exclusion wording', async () => {
    vi.stubGlobal(
      'fetch',
      completeFetch(() => Promise.resolve(response(conversion()))),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })

    const status = await within(section).findByRole('status')
    await waitFor(() => {
      expect(status).toHaveTextContent('추정값 포함')
    })
    expect(status).toHaveTextContent('체결가격이 아니므로')
    expect(
      within(section).getByText(
        '입출금 3건 · 직접 정규화 1건 · 추정 환산 2건 · 환산 보류 0건 · 검토 0건',
      ),
    ).toBeInTheDocument()
    expect(within(section).getByText('적용 정책: BYBIT-PRICE-1.0 · CALC-1.0')).toBeInTheDocument()
    expect(
      within(section).queryByRole('button', { name: '환산 다시 시도' }),
    ).not.toBeInTheDocument()
    expect(section).not.toHaveTextContent(/\d\s*USD|USD\s*\d|수익률|목표 달성률|계산에서 제외/)
  })

  it('groups held flows by asset with direction, unrounded quantity, Seoul time and impact', async () => {
    vi.stubGlobal(
      'fetch',
      completeFetch(() =>
        Promise.resolve(
          response(
            conversion({
              status: 'CONVERSION_REQUIRED',
              reasonCode: 'PRICE_ROUTE_UNAVAILABLE',
              calculable: false,
              flowCount: 4,
              convertedCount: 0,
              estimatedCount: 1,
              conversionRequiredCount: 3,
              pendingAssets: heldAssets,
            }),
          ),
        ),
      ),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })

    const status = await within(section).findByRole('status')
    await waitFor(() => {
      expect(status).toHaveTextContent('환산 필요')
    })
    expect(status).toHaveTextContent('공식 갱신은 보류되고 마지막 정상 결과가 유지됩니다')
    expect(status).not.toHaveTextContent('GAP')
    const gap = within(section).getByText('GAP 2건')
    expect(gap.tagName).toBe('SUMMARY')
    expect(within(section).getByText('HALT 1건')).toBeInTheDocument()
    fireEvent.click(gap)
    // Native disclosure widgets, one per asset in asset order.
    const groups = within(section).getAllByRole('group')
    expect(groups).toHaveLength(2)
    const gapGroup = groups[0] ?? section
    expect(gapGroup).toHaveTextContent('GAP 2건')
    const detail = within(gapGroup)
    expect(detail.getByText('2.000000000000000001 GAP')).toBeInTheDocument()
    expect(detail.getByText('출금')).toBeInTheDocument()
    expect(detail.getByText('입금')).toBeInTheDocument()
    expect(detail.getAllByText('공식 갱신 보류 · 마지막 정상 결과 유지')).toHaveLength(2)
    const seoul = new Intl.DateTimeFormat('ko-KR', {
      timeZone: 'Asia/Seoul',
      dateStyle: 'medium',
      timeStyle: 'medium',
    }).format(new Date('2026-09-26T15:06:05Z'))
    expect(detail.getByText(seoul)).toHaveAttribute('datetime', '2026-09-26T15:06:05Z')
    expect(
      within(section).getByText(/아래 “조건 수정”으로 조건을 다시 정하세요/),
    ).toBeInTheDocument()
    expect(
      within(section).queryByRole('button', { name: '환산 다시 시도' }),
    ).not.toBeInTheDocument()
    expect(within(section).queryByRole('link')).not.toBeInTheDocument()
    expect(section).not.toHaveTextContent(/계산에서 제외|수익률|\d\s*USD/)
  })

  it('offers a retry only for a retryable reason and reuses the key after an uncertain failure', async () => {
    let posts = 0
    const fetchMock = completeFetch((init) => {
      if (init?.method !== 'POST') {
        return Promise.resolve(
          response(
            conversion({
              status: 'CONVERSION_REQUIRED',
              reasonCode: 'PRICE_PROVIDER_UNAVAILABLE',
              retryable: true,
              calculable: false,
              estimatedCount: 0,
              conversionRequiredCount: 2,
            }),
          ),
        )
      }
      posts += 1
      return posts === 1
        ? Promise.reject(new Error('network'))
        : Promise.resolve(response(conversion({ status: 'QUEUED', calculable: false }), 202))
    })
    vi.stubGlobal('fetch', fetchMock)
    let generated = 0
    vi.stubGlobal('crypto', {
      randomUUID: () => {
        generated += 1
        return `conversion-retry-${String(generated)}`
      },
    })

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    expect(
      await within(section).findByText(/Bybit 공개 시세를 조회하지 못했습니다/),
    ).toBeInTheDocument()
    fireEvent.click(within(section).getByRole('button', { name: '환산 다시 시도' }))
    expect(await within(section).findByRole('alert')).toHaveTextContent(
      '환산을 접수하지 못했습니다',
    )
    fireEvent.click(within(section).getByRole('button', { name: '환산 다시 시도' }))

    expect(await within(section).findByText('환산 중')).toBeInTheDocument()
    const keys = fetchMock.mock.calls
      .filter(([, init]) => init?.method === 'POST')
      .map(([, init]) => (init?.headers as Record<string, string>)['Idempotency-Key'])
    expect(keys).toEqual(['conversion-retry-1', 'conversion-retry-1'])
  })

  it('keeps review-required conversions without retry and maps unknown codes to safe copy', async () => {
    vi.stubGlobal(
      'fetch',
      completeFetch(() =>
        Promise.resolve(
          response(
            conversion({
              status: 'REVIEW_REQUIRED',
              reasonCode: 'raw-provider-detail',
              calculable: false,
              estimatedCount: 0,
              reviewCount: 2,
            }),
          ),
        ),
      ),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    const status = await within(section).findByRole('status')
    await waitFor(() => {
      expect(status).toHaveTextContent('검토 필요')
    })
    expect(status).toHaveTextContent('환산 상태를 확인하지 못했습니다')
    expect(section).not.toHaveTextContent('raw-provider-detail')
    expect(
      within(section).queryByRole('button', { name: '환산 다시 시도' }),
    ).not.toBeInTheDocument()
  })

  it('rejects a response that carries an unexpected shape', async () => {
    vi.stubGlobal(
      'fetch',
      completeFetch(() =>
        Promise.resolve(
          response(conversion({ pendingAssets: [{ assetCode: 'btc', count: 1, flows: [] }] })),
        ),
      ),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    expect(await within(section).findByRole('alert')).toHaveTextContent(
      '환산 상태를 불러오지 못했습니다',
    )
    expect(within(section).queryByText('추정값 포함')).not.toBeInTheDocument()
  })

  it('keeps the refusal visible after a 409 and starts over with a new key', async () => {
    let generated = 0
    vi.stubGlobal('crypto', {
      randomUUID: () => {
        generated += 1
        return `conversion-refused-${String(generated)}`
      },
    })
    const fetchMock = completeFetch((init) =>
      Promise.resolve(
        init?.method === 'POST' ? response({}, 409) : new Response(null, { status: 204 }),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    fireEvent.click(await within(section).findByRole('button', { name: '사건 시점 환산 시작' }))

    // The state refresh that follows the refusal must not clear it.
    expect(await within(section).findByRole('alert')).toHaveTextContent(
      '판정, 계정 또는 연결 상태가 바뀌어 환산할 수 없습니다',
    )
    await waitFor(() => {
      expect(
        fetchMock.mock.calls.filter(([url]) => requestUrl(url).includes('cashflow-conversion?')),
      ).toHaveLength(2)
    })
    expect(within(section).getByRole('alert')).toHaveTextContent('판정, 계정 또는 연결 상태')
    fireEvent.click(within(section).getByRole('button', { name: '사건 시점 환산 시작' }))
    await waitFor(() => {
      expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(2)
    })
    const keys = fetchMock.mock.calls
      .filter(([, init]) => init?.method === 'POST')
      .map(([, init]) => (init?.headers as Record<string, string>)['Idempotency-Key'])
    expect(keys).toEqual(['conversion-refused-1', 'conversion-refused-2'])
  })

  it('offers a retry for a cancelled attempt and posts it', async () => {
    const cancelled = withoutCounts({
      status: 'CANCELLED',
      reasonCode: 'NOT_PERMITTED',
      calculable: false,
    })
    const fetchMock = completeFetch((init) =>
      Promise.resolve(
        init?.method === 'POST'
          ? response(withoutCounts({ status: 'QUEUED', calculable: false }), 202)
          : response(cancelled),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'conversion-cancelled-retry' })

    render(<InitialInputsCollection csrfToken="csrf-value" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    expect(await within(section).findByText('환산이 중단되었습니다')).toBeInTheDocument()
    expect(section).not.toHaveTextContent(/입출금 \d+건/)
    fireEvent.click(within(section).getByRole('button', { name: '환산 다시 시도' }))

    expect(await within(section).findByText('환산 중')).toBeInTheDocument()
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(post?.[0]).toBe('/api/snapshots/first/cashflow-conversion')
    expect(post?.[1]?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'csrf-value',
      'Idempotency-Key': 'conversion-cancelled-retry',
    })
  })

  it('says there was nothing to convert when the classification had no external flows', async () => {
    vi.stubGlobal(
      'fetch',
      completeFetch(() =>
        Promise.resolve(
          response(
            conversion({ status: 'CONVERTED', flowCount: 0, convertedCount: 0, estimatedCount: 0 }),
          ),
        ),
      ),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    // The status region exists before the conversion is read: wait for its text, not its presence.
    const status = await within(section).findByRole('status')
    await waitFor(() => {
      expect(status).toHaveTextContent('환산 완료')
    })
    expect(status).toHaveTextContent('판정된 외부 입출금이 없어 환산할 항목이 없습니다')
    expect(status).not.toHaveTextContent('직접 정규화 근거로 환산을 마쳤습니다')
  })

  it('lists review-required flows with their status and offers no support path', async () => {
    vi.stubGlobal(
      'fetch',
      completeFetch(() =>
        Promise.resolve(
          response(
            conversion({
              status: 'REVIEW_REQUIRED',
              reasonCode: 'PRICE_RESPONSE_MALFORMED',
              calculable: false,
              flowCount: 2,
              convertedCount: 0,
              estimatedCount: 0,
              conversionRequiredCount: 1,
              reviewCount: 1,
              pendingAssets: [
                {
                  assetCode: 'IDOWN',
                  count: 1,
                  flows: [
                    {
                      direction: 'DEPOSIT',
                      quantity: '1',
                      occurredAt: '2026-09-26T15:01:00Z',
                      status: 'CONVERSION_REQUIRED',
                      reasonCode: 'PRICE_PROVIDER_UNAVAILABLE',
                      retryable: true,
                    },
                  ],
                },
                {
                  assetCode: 'IDUP',
                  count: 1,
                  flows: [
                    {
                      direction: 'WITHDRAWAL',
                      quantity: '3.5',
                      occurredAt: '2026-09-26T15:02:00Z',
                      status: 'REVIEW_REQUIRED',
                      reasonCode: 'PRICE_RESPONSE_MALFORMED',
                      retryable: false,
                    },
                  ],
                },
              ],
            }),
          ),
        ),
      ),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    const reviewGroup = await within(section).findByText('IDUP 1건')
    fireEvent.click(reviewGroup)
    const groups = within(section).getAllByRole('group')
    expect(groups).toHaveLength(2)
    const idup = within(groups[1] ?? section)
    expect(idup.getByText('검토 필요')).toBeInTheDocument()
    expect(idup.getByText('3.5 IDUP')).toBeInTheDocument()
    expect(idup.getByText('가격 응답의 형식을 확인할 수 없습니다.')).toBeInTheDocument()
    expect(within(groups[0] ?? section).getByText('환산 필요')).toBeInTheDocument()
    expect(
      within(section).getByText(/원인을 검토하기 전까지 공식 갱신이 보류됩니다/),
    ).toBeInTheDocument()
    expect(
      within(section).getByText(/아래 “조건 수정”으로 조건을 다시 정하세요/),
    ).toBeInTheDocument()
    expect(section).not.toHaveTextContent(/지원/)
    expect(within(section).queryByRole('link')).not.toBeInTheDocument()
    expect(
      within(section).queryByRole('button', { name: '환산 다시 시도' }),
    ).not.toBeInTheDocument()
  })

  it('explains an interrupted attempt without outcome counts and offers a retry', async () => {
    vi.stubGlobal(
      'fetch',
      completeFetch(() =>
        Promise.resolve(
          response(
            withoutCounts({
              status: 'CONVERSION_REQUIRED',
              reasonCode: 'CONVERSION_INTERRUPTED',
              retryable: true,
              calculable: false,
              flowCount: 2,
            }),
          ),
        ),
      ),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    const status = await within(section).findByRole('status')
    await waitFor(() => {
      expect(status).toHaveTextContent('처리가 중간에 멈춰 환산 결과를 만들지 못했습니다')
    })
    expect(status).not.toHaveTextContent('승인된 가격 근거가 없는')
    expect(section).not.toHaveTextContent(/입출금 \d+건/)
    expect(within(section).getByRole('button', { name: '환산 다시 시도' })).toBeInTheDocument()
  })

  it('rejects outcome counts that are only partly present', async () => {
    const partial: Record<string, unknown> = withoutCounts({ status: 'CONVERTED' })
    partial.convertedCount = 1
    vi.stubGlobal(
      'fetch',
      completeFetch(() => Promise.resolve(response(partial))),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    expect(await within(section).findByRole('alert')).toHaveTextContent(
      '환산 상태를 불러오지 못했습니다',
    )
  })
})

function publicationJob(status: string, overrides: Record<string, unknown> = {}) {
  return {
    jobId: 'publication-1',
    status,
    retryable: false,
    createdAt: '2026-09-27T01:00:00Z',
    ...overrides,
  }
}

describe('InitialInputsCollection publication', () => {
  it('appears only as a sibling of a calculable conversion and posts same-origin', async () => {
    const fetchMock = completeFetch(
      () => Promise.resolve(response(conversion())),
      (init) =>
        Promise.resolve(
          init?.method === 'POST'
            ? response(publicationJob('QUEUED'), 202)
            : response({ startable: true }),
        ),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('crypto', { randomUUID: () => 'publication-key-001' })

    render(<InitialInputsCollection csrfToken="csrf-value" intentId="intent-123" />)
    const publication = await screen.findByRole('region', { name: '최초 계획 계산·발행' })
    const conversionSection = screen.getByRole('region', { name: '사건 시점 USD 환산' })
    // A sibling, not a descendant: the conversion section keeps exactly one status region.
    expect(within(conversionSection).queryByRole('region')).not.toBeInTheDocument()
    expect(within(conversionSection).getAllByRole('status')).toHaveLength(1)
    fireEvent.click(await within(publication).findByRole('button', { name: '최초 계획 계산·발행' }))

    expect(await within(publication).findByText('계산·발행 중')).toBeInTheDocument()
    const postCall = fetchMock.mock.calls.find(
      ([url, init]) => init?.method === 'POST' && requestUrl(url).includes('/plan-publication'),
    )
    expect(postCall?.[0]).toBe('/api/snapshots/first/plan-publication')
    expect(postCall?.[1]?.headers).toMatchObject({
      'X-CSRF-TOKEN': 'csrf-value',
      'Idempotency-Key': 'publication-key-001',
    })
    expect(postCall?.[1]?.body).toBe(JSON.stringify({ intentId: 'intent-123' }))
    expect(publication).not.toHaveTextContent(/\d\s*USD|USD\s*\d|%/)
  })

  it('does not appear or read anything while the conversion is not calculable', async () => {
    const fetchMock = completeFetch(() =>
      Promise.resolve(
        response(
          conversion({
            status: 'CONVERSION_REQUIRED',
            reasonCode: 'PRICE_ROUTE_UNAVAILABLE',
            calculable: false,
            estimatedCount: 1,
            conversionRequiredCount: 1,
            convertedCount: 1,
          }),
        ),
      ),
    )
    vi.stubGlobal('fetch', fetchMock)

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    expect(await within(section).findByText('환산 필요', { selector: 'p' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '최초 계획 계산·발행' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).not.toContainEqual(
      expect.stringContaining('/plan-publication'),
    )
  })

  /**
   * A conversion section that goes away takes its "calculable" with it. Without that, the next
   * lineage's publication sibling would mount on a stale `true` and read that intent's publication
   * before its own conversion was known.
   */
  it('forgets a calculable conversion once its section unmounts', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockImplementation((input) => {
      const url = requestUrl(input)
      const second = url.includes('intent-2')
      if (url.includes('/plan-publication')) return Promise.resolve(response({ startable: true }))
      if (url.includes('/cashflow-conversion'))
        return Promise.resolve(
          response(
            second
              ? conversion({
                  status: 'CONVERSION_REQUIRED',
                  reasonCode: 'PRICE_ROUTE_UNAVAILABLE',
                  calculable: false,
                  estimatedCount: 1,
                  conversionRequiredCount: 1,
                  convertedCount: 1,
                })
              : conversion(),
          ),
        )
      if (url.includes('/cashflow-classification'))
        return Promise.resolve(
          response({
            ...completeClassification,
            jobId: second ? 'classification-2' : 'classification-1',
          }),
        )
      return Promise.resolve(response({ job: job('INPUTS_COMPLETE') }))
    })
    vi.stubGlobal('fetch', fetchMock)

    const { rerender } = render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    expect(await screen.findByRole('region', { name: '최초 계획 계산·발행' })).toBeInTheDocument()

    rerender(<InitialInputsCollection csrfToken="csrf" intentId="intent-2" />)
    const section = await screen.findByRole('region', { name: '사건 시점 USD 환산' })
    expect(await within(section).findByText('환산 필요', { selector: 'p' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '최초 계획 계산·발행' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.map(([url]) => requestUrl(url))).not.toContainEqual(
      expect.stringContaining('/plan-publication?intentId=intent-2'),
    )
  })

  it('links to the dashboard once published and offers no second publication', async () => {
    vi.stubGlobal(
      'fetch',
      completeFetch(
        () => Promise.resolve(response(conversion())),
        () =>
          Promise.resolve(
            response({
              job: publicationJob('PUBLISHED', { planId: 'plan-1' }),
              startable: false,
              reason: 'ACTIVE_PLAN_EXISTS',
            }),
          ),
      ),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const publication = await screen.findByRole('region', { name: '최초 계획 계산·발행' })
    const status = within(publication).getByRole('status')
    await waitFor(() => {
      expect(status).toHaveTextContent('발행 완료')
    })
    // Its own success is not reported back as "an active plan already exists".
    expect(status).not.toHaveTextContent('이미 활성 계획이 있어')
    expect(within(publication).queryByText(/다음 행동: /)).not.toBeInTheDocument()
    expect(within(publication).getByRole('link', { name: '결과 보기' })).toHaveAttribute(
      'href',
      '/dashboard',
    )
    expect(
      within(publication).queryByRole('button', { name: '최초 계획 계산·발행' }),
    ).not.toBeInTheDocument()
  })

  it('explains a 409 refusal with its reason and next action, and points at the active plan', async () => {
    let refused = false
    vi.stubGlobal(
      'fetch',
      completeFetch(
        () => Promise.resolve(response(conversion())),
        (init) => {
          if (init?.method === 'POST') {
            refused = true
            return Promise.resolve(
              response({ code: 'ACTIVE_PLAN_EXISTS', message: 'x', correlationId: 'c' }, 409),
            )
          }
          return Promise.resolve(
            response(
              refused ? { startable: false, reason: 'ACTIVE_PLAN_EXISTS' } : { startable: true },
            ),
          )
        },
      ),
    )
    vi.stubGlobal('crypto', { randomUUID: () => 'publication-key-002' })

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const publication = await screen.findByRole('region', { name: '최초 계획 계산·발행' })
    fireEvent.click(await within(publication).findByRole('button', { name: '최초 계획 계산·발행' }))

    const status = within(publication).getByRole('status')
    await waitFor(() => {
      expect(status).toHaveTextContent('이미 활성 계획이 있어 새 계획을 만들지 않았습니다.')
    })
    expect(within(publication).getByRole('link', { name: '결과 보기' })).toBeInTheDocument()
    expect(within(publication).getByText(/다음 행동: /)).toBeInTheDocument()
    expect(
      within(publication).queryByRole('button', { name: '최초 계획 계산·발행' }),
    ).not.toBeInTheDocument()
  })

  it.each([
    // An ending that says "publish again" gets a button exactly when the server would take it.
    [
      'BLOCKED',
      'INPUTS_CHANGED',
      '입력을 고정한 뒤 수집·판정·환산 결과가 바뀌었습니다.',
      true,
      undefined,
    ],
    [
      'BLOCKED',
      'POINTER_CONFLICT',
      '공식 현재가치가 그사이 다른 값으로 바뀌었습니다.',
      true,
      undefined,
    ],
    ['BLOCKED', 'NOT_PERMITTED', '운영 제한이 발행을 허용하지 않았습니다.', true, undefined],
    [
      'BLOCKED',
      'MEMBERSHIP_CHANGED',
      '포트폴리오의 연결 구성이 바뀌었습니다.',
      false,
      'PLAN_CONDITIONS_REQUIRED',
    ],
    [
      'BLOCKED',
      'INPUTS_CHANGED',
      '입력을 고정한 뒤 수집·판정·환산 결과가 바뀌었습니다.',
      false,
      'INPUTS_NOT_READY',
    ],
    [
      'FAILED',
      'CALCULATION_RANGE_EXCEEDED',
      '계산 값이 허용된 정밀도 범위를 벗어나',
      false,
      'CALCULATION_RANGE_EXCEEDED',
    ],
    [
      'FAILED',
      'CALCULATION_POLICY_UNVERIFIED',
      '계산 정책의 고정 예제를 확인하지 못해',
      false,
      'CALCULATION_POLICY_UNVERIFIED',
    ],
    [
      'FAILED',
      'PUBLICATION_INTERRUPTED',
      '처리가 중간에 멈춰 아무것도 발행하지 않았습니다.',
      true,
      undefined,
    ],
    ['BLOCKED', 'SOMETHING_NEW', '발행 상태를 확인하지 못했습니다.', false, 'SOMETHING_ELSE'],
  ])(
    'explains a %s %s ending and offers a new attempt only when the server would take it (%#)',
    async (status, code, cause, startable, reason) => {
      vi.stubGlobal(
        'fetch',
        completeFetch(
          () => Promise.resolve(response(conversion())),
          () =>
            Promise.resolve(
              response({
                job: publicationJob(status, {
                  failureCode: code,
                  retryable: code === 'PUBLICATION_INTERRUPTED',
                }),
                startable,
                ...(reason === undefined ? {} : { reason }),
              }),
            ),
        ),
      )

      render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
      const publication = await screen.findByRole('region', { name: '최초 계획 계산·발행' })
      const live = within(publication).getByRole('status')
      await waitFor(() => {
        expect(live).toHaveTextContent(cause)
      })
      expect(publication).not.toHaveTextContent(code)
      for (const hidden of reason === undefined ? [] : [reason]) {
        expect(publication).not.toHaveTextContent(hidden)
      }
      expect(within(publication).queryByRole('button', { name: '발행 다시 시도' }) !== null).toBe(
        startable,
      )
      // Whatever the ending, there is a next step, and a refusal never leaves the reader stranded.
      expect(within(publication).getByText(/다음 행동: /)).toBeInTheDocument()
    },
  )

  it('says why nothing can start yet when there is no attempt and the server would refuse one', async () => {
    vi.stubGlobal(
      'fetch',
      completeFetch(
        () => Promise.resolve(response(conversion())),
        () => Promise.resolve(response({ startable: false, reason: 'PLAN_PERIOD_ENDED' })),
      ),
    )

    render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
    const publication = await screen.findByRole('region', { name: '최초 계획 계산·발행' })
    const live = within(publication).getByRole('status')
    await waitFor(() => {
      expect(live).toHaveTextContent('지금은 발행할 수 없습니다')
    })
    expect(live).toHaveTextContent('계획 종료 시각이 지나')
    expect(within(publication).getByText(/새 종료 시각을 정하세요/)).toBeInTheDocument()
    expect(
      within(publication).queryByRole('button', { name: '최초 계획 계산·발행' }),
    ).not.toBeInTheDocument()
  })

  it('stops following an attempt once its state read is refused (403)', async () => {
    /*
     * This test leans on the interval being set and cleared by a layout effect (`PlanPublicationSection`): the
     * clock is advanced right after `계산·발행 중` is seen, and the reads are counted right after the refusal is
     * seen. As a passive effect the interval could be set after the first advance, or still be set during the
     * second (the baseline's `reads` 7 ≠ 2 under load).
     */
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    try {
      let reads = 0
      vi.stubGlobal(
        'fetch',
        completeFetch(
          () => Promise.resolve(response(conversion())),
          () => {
            reads += 1
            return Promise.resolve(
              reads === 1
                ? response({ job: publicationJob('RUNNING'), startable: false })
                : new Response(null, { status: 403 }),
            )
          },
        ),
      )

      render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
      const publication = await screen.findByRole('region', { name: '최초 계획 계산·발행' })
      await within(publication).findByText('계산·발행 중')
      vi.advanceTimersByTime(2000)
      await waitFor(() => {
        expect(within(publication).getByRole('status')).toHaveTextContent(
          '지금은 발행할 수 없습니다',
        )
      })
      const afterRefusal = reads
      vi.advanceTimersByTime(10_000)
      expect(reads).toBe(afterRefusal)
      expect(
        within(publication).queryByRole('button', { name: '최초 계획 계산·발행' }),
      ).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  /*
   * A deliberate first-sight guard (`DEVELOPMENT.md`, `시험의 대기`): it records the intervals at the first
   * sight of an attempt in progress, without CPU load (`test/firstSight.ts`). It fails if the polling goes
   * back to a passive `useEffect` — the test above only shows it as a flake under load.
   */
  it('already follows an attempt when `계산·발행 중` first appears (first sight)', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    try {
      const first = deferred<Response>()
      vi.stubGlobal(
        'fetch',
        completeFetch(
          () => Promise.resolve(response(conversion())),
          () => first.promise,
        ),
      )
      render(<InitialInputsCollection csrfToken="csrf" intentId="intent-1" />)
      const publication = await screen.findByRole('region', { name: '최초 계획 계산·발행' })

      const intervals = await atFirstSight(
        () => within(publication).queryByText('계산·발행 중') !== null,
        () => vi.getTimerCount(),
        () => {
          first.resolve(response({ job: publicationJob('RUNNING'), startable: false }))
        },
      )

      // The publication's polling is the only interval, set in the commit that shows the attempt.
      expect(intervals).toBe(1)
    } finally {
      vi.useRealTimers()
    }
  })
})

/** A conversion view as the server sends it without a result bundle: no outcome counts. */
function withoutCounts(overrides: Record<string, unknown>): Record<string, unknown> {
  const view: Record<string, unknown> = conversion(overrides)
  delete view.convertedCount
  delete view.estimatedCount
  delete view.conversionRequiredCount
  delete view.reviewCount
  return view
}
