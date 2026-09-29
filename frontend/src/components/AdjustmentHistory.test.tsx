import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { type ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'

import {
  adjustmentHistoryContent as copy,
  DASHBOARD_ADJUSTMENTS_PATH,
} from '../content/adjustmentHistory'
import { ENTRY_PATH } from '../content/auth'
import { planResultContent } from '../content/planResult'
import { formatSeoulInstant } from '../format/decimal'
import {
  adjustmentAnnouncement,
  appendPage,
  parseAdjustmentPage,
  useDashboardAdjustments,
} from '../session/useAdjustmentHistory'
import { useDashboardEvidence } from '../session/useCalculationEvidence'
import { isPlanResult, type DashboardView, type PlanResult } from '../session/useDashboard'
import {
  ADJUSTMENT_IDS,
  adjustmentPageFixture,
  adjustmentRowFixture,
  adjustmentTime,
  adjustmentWithdrawalFixture,
  EVIDENCE_IDS,
  evidenceResultFixture,
  trustFixture,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubAdjustmentHistory,
} from '../test/setup'
import { PlanResultPanel } from './PlanResultPanel'

/** The value, or a failed test when it is missing. */
function present<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) throw new Error('a missing value')
  return value
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** The shown result: 23 applied flows (five withdrawals), UUID-shaped ids. */
function shownResult(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return evidenceResultFixture({ flowCount: 23, ...overrides })
}

function parsed(body: Record<string, unknown>): PlanResult {
  expect(isPlanResult(body)).toBe(true)
  return body as unknown as PlanResult
}

function ready(body: Record<string, unknown> = shownResult()): DashboardView {
  return { kind: 'READY', nextAction: 'NONE', result: parsed(body) }
}

/** Rows `from..to`: every fourth a withdrawal of -2.5 USDT, the rest deposits of 10.25 USDT. */
function rows(from: number, to: number): Record<string, unknown>[] {
  const list: Record<string, unknown>[] = []
  for (let sequence = from; sequence <= to; sequence += 1) {
    list.push(
      sequence % 4 === 0
        ? adjustmentWithdrawalFixture(sequence, EVIDENCE_IDS.calculation)
        : adjustmentRowFixture(sequence, EVIDENCE_IDS.calculation),
    )
  }
  return list
}

const CURSOR = 'AdjustmentCursorFromTheServer_opaque-0123456789abcd'
const SUMMARY = { count: 23, netUsdValue: '172', excludedCount: 2 }

function firstPage(result = shownResult()): Record<string, unknown> {
  return adjustmentPageFixture(result, { rows: rows(1, 20), nextCursor: CURSOR, summary: SUMMARY })
}

function lastPage(result = shownResult()): Record<string, unknown> {
  return adjustmentPageFixture(result, { rows: rows(21, 23), summary: SUMMARY })
}

/** The dashboard panel with the evidence and the history of the bundle it shows, wired as the screens wire it. */
function Harness({
  view,
  reload = vi.fn(),
}: {
  readonly view: DashboardView
  readonly reload?: () => void
}): ReactElement {
  const dashboard = { view, loading: false, reload }
  const evidence = useDashboardEvidence(dashboard)
  const adjustments = useDashboardAdjustments(dashboard)
  return (
    <>
      <p data-testid="announcement">{adjustmentAnnouncement(adjustments.view) ?? ''}</p>
      <PlanResultPanel adjustments={adjustments} dashboard={dashboard} evidence={evidence} />
    </>
  )
}

/** Answers the history reads in order, recording the URL and the request options of each. */
function answers(...queue: (() => Promise<Response>)[]): {
  urls: string[]
  inits: (RequestInit | undefined)[]
} {
  const urls: string[] = []
  const inits: (RequestInit | undefined)[] = []
  stubAdjustmentHistory((url, init) => {
    urls.push(url)
    inits.push(init)
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected history read')
    return next()
  })
  return { urls, inits }
}

function ok(body: Record<string, unknown>): () => Promise<Response> {
  return () => Promise.resolve(json(body))
}

function query(url: string | undefined): Record<string, string> {
  return Object.fromEntries(new URL(url ?? '', 'http://history.test').searchParams)
}

function toggle(): HTMLElement {
  return screen.getByRole('button', { name: copy.open })
}

function region(): HTMLElement {
  return screen.getByRole('region', { name: copy.heading })
}

function list(): HTMLElement {
  return within(region()).getByRole('list', { name: copy.list })
}

function listed(): HTMLElement[] {
  return within(list()).getAllByRole('listitem')
}

function cards(): HTMLElement[] {
  return within(screen.getByRole('region', { name: planResultContent.heading })).getAllByRole(
    'article',
  )
}

async function openHistory(): Promise<void> {
  fireEvent.click(toggle())
  await within(region()).findByRole('list', { name: copy.list })
}

function timeLabel(sequence: number): string {
  return formatSeoulInstant(adjustmentTime(sequence), planResultContent.timeUnavailable)
}

function detailToggle(sequence: number, type: string): HTMLElement {
  return within(region()).getByRole('button', {
    name: copy.row.detailOpen(sequence, timeLabel(sequence), type),
  })
}

// Twenty rows of named groups make role queries heavy in jsdom; under a loaded parallel run a test can pass 5s.
describe('AdjustmentHistory', { timeout: 20_000 }, () => {
  it('AC-1: 조정 이력 보기 opens the region on the same screen, moves focus to its title and reads description → summary → filter → rows', async () => {
    const { urls } = answers(ok(firstPage()))
    render(<Harness view={ready()} />)

    const button = toggle()
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(button).toHaveAttribute('aria-controls', 'adjustment-history')
    expect(screen.queryByRole('region', { name: copy.heading })).not.toBeInTheDocument()
    expect(urls).toEqual([])

    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('heading', { level: 4, name: copy.heading })).toHaveFocus()
    expect(region()).toHaveAttribute('aria-busy', 'true')
    expect(within(region()).getByText(copy.status.loading)).toBeInTheDocument()

    await within(region()).findByRole('list', { name: copy.list })
    expect(region()).toHaveAttribute('id', 'adjustment-history')
    expect(region()).toHaveAttribute('aria-busy', 'false')
    // The reading order: description and the term's help, the summary, the filter, then the rows.
    const text = region().textContent
    const order = [
      copy.intro,
      copy.term,
      copy.summary.heading,
      copy.excluded.text(2),
      copy.filter.legend,
      copy.list,
      timeLabel(1),
    ].map((part) => text.indexOf(part))
    expect(order.every((at) => at >= 0)).toBe(true)
    expect([...order].sort((left, right) => left - right)).toEqual(order)
    // The filter is a named group of three radios, `전체` checked.
    const filter = within(region()).getByRole('group', { name: copy.filter.legend })
    expect(
      within(filter)
        .getAllByRole('radio')
        .map((radio) => radio.getAttribute('value')),
    ).toEqual(['ALL', 'IN', 'OUT'])
    expect(within(filter).getByRole('radio', { name: copy.filter.options.ALL })).toBeChecked()
    // One list, twenty rows, each a named group with its own title, facts in the reading order.
    expect(listed()).toHaveLength(20)
    const [first] = listed()
    if (first === undefined) throw new Error('a first row')
    const group = within(first).getByRole('group')
    expect(within(group).getByRole('heading', { level: 5 })).toHaveTextContent(
      `${copy.row.title(1)} · ${timeLabel(1)} · ${copy.filter.options.IN}`,
    )
    expect(
      within(group)
        .getAllByRole('term')
        .slice(0, 7)
        .map((term) => term.textContent),
    ).toEqual([
      copy.row.occurredAt,
      copy.row.type,
      copy.row.quantity,
      copy.row.usd,
      copy.row.classification,
      copy.row.price,
      copy.row.applied,
    ])
    // No live region of its own and nothing that navigates.
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 live region은 속성으로만 찾을 수 있다.
    expect(region().querySelector('[aria-live]')).toBeNull()
    expect(within(region()).queryByRole('status')).not.toBeInTheDocument()
    expect(within(region()).queryAllByRole('link')).toEqual([])
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.ready(20, 23))
  })

  it('AC-1: a row opens its lineage — source event → classification → conversion → calculation — from a toggle named by its time and type', async () => {
    answers(ok(firstPage()))
    render(<Harness view={ready()} />)
    await openHistory()

    const detail = detailToggle(4, copy.filter.options.OUT)
    expect(detail).toHaveAttribute('aria-expanded', 'false')
    const controlled = detail.getAttribute('aria-controls') ?? ''
    // eslint-disable-next-line testing-library/no-node-access -- 펼침이 가리키는 id가 실제 요소인지 본다.
    const target = document.getElementById(controlled)
    expect(target).not.toBeNull()
    expect(target).not.toBeVisible()
    fireEvent.click(detail)
    expect(detail).toHaveAttribute('aria-expanded', 'true')
    expect(target).toBeVisible()
    const shown = target?.textContent ?? ''
    const order = [
      copy.detail.event,
      'adj-04',
      copy.detail.classification,
      ADJUSTMENT_IDS.classification,
      'EXTERNAL_TRANSFER_OUT',
      copy.detail.conversion,
      ADJUSTMENT_IDS.conversion,
      copy.detail.calculation,
      EVIDENCE_IDS.calculation,
    ].map((part) => shown.indexOf(part))
    expect(order.every((at) => at >= 0)).toBe(true)
    expect([...order].sort((left, right) => left - right)).toEqual(order)
    // Ids and codes whole, in the mono style.
    expect(within(present(target)).getByText('adj-04').tagName).toBe('CODE')
    expect(within(present(target)).getByText(EVIDENCE_IDS.calculation).tagName).toBe('CODE')
    expect(shown).toContain(copy.evidenceTypes.DIRECT_NORMALIZATION)
    expect(shown).toContain(copy.routes.DIRECT)
    fireEvent.click(detail)
    expect(detail).toHaveAttribute('aria-expanded', 'false')
  })

  it('AC-1: 조정 이력 닫기 closes the region and returns focus to the button; the button toggles too', async () => {
    answers(ok(firstPage()), ok(firstPage()))
    render(<Harness view={ready()} />)
    await openHistory()

    fireEvent.click(within(region()).getByRole('button', { name: copy.close }))
    expect(toggle()).toHaveFocus()
    expect(toggle()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: copy.heading })).not.toBeInTheDocument()
    expect(screen.getByTestId('announcement')).toHaveTextContent('')

    await openHistory()
    fireEvent.click(toggle())
    expect(toggle()).toHaveAttribute('aria-expanded', 'false')
  })

  it('sends exactly the reference and the filter, then the cursor for the next page — nothing else', async () => {
    const { urls, inits } = answers(ok(firstPage()), ok(lastPage()))
    render(<Harness view={ready()} />)
    await openHistory()

    const first = new URL(urls[0] ?? '', 'http://history.test')
    expect(first.pathname).toBe(DASHBOARD_ADJUSTMENTS_PATH)
    expect([...first.searchParams.keys()]).toEqual([
      'planId',
      'pointerVersion',
      'calculationId',
      'direction',
    ])
    expect(query(urls[0])).toEqual({
      planId: EVIDENCE_IDS.plan,
      pointerVersion: '1',
      calculationId: EVIDENCE_IDS.calculation,
      direction: 'ALL',
    })
    expect(inits[0]).toMatchObject({ credentials: 'same-origin', cache: 'no-store' })

    fireEvent.click(within(region()).getByRole('button', { name: copy.more }))
    await waitFor(() => {
      expect(listed()).toHaveLength(23)
    })
    expect(query(urls[1])).toEqual({
      planId: EVIDENCE_IDS.plan,
      pointerVersion: '1',
      calculationId: EVIDENCE_IDS.calculation,
      direction: 'ALL',
      cursor: CURSOR,
    })
  })

  it('AC-4: amounts keep their sign and are rounded once for display, quantities and prices are whole, and the total is the server’s exact sum rounded once', async () => {
    const result = shownResult({ flowCount: 4 })
    const btc = adjustmentWithdrawalFixture(4, EVIDENCE_IDS.calculation, {
      asset: 'BTC',
      signedQuantity: '-0.00012',
      usdValue: '-7.80003',
      price: {
        policyVersion: 'BYBIT-PRICE-1.0',
        evidenceType: 'SPOT_KLINE_CLOSE',
        estimated: true,
        unitPrice: '65000.25',
        routeId: 'SPOT_ASSET_USDT',
        legs: [
          {
            symbol: 'BTCUSDT',
            candleStartAt: '2026-09-26T00:00:00Z',
            candleEndAt: '2026-09-26T00:01:00Z',
            closePrice: '65000.25',
            selectionRule: 'EVENT_MINUTE',
            lagMinutes: 0,
          },
        ],
      },
    })
    const cents = [1, 2, 3].map((sequence) =>
      adjustmentRowFixture(sequence, EVIDENCE_IDS.calculation, {
        signedQuantity: '0.005',
        usdValue: '0.005',
      }),
    )
    answers(
      ok(
        adjustmentPageFixture(result, {
          rows: [...cents, btc],
          summary: { count: 4, netUsdValue: '-7.78503', excludedCount: 0 },
        }),
      ),
    )
    render(<Harness view={ready(result)} />)
    await openHistory()

    const summary = within(region()).getByRole('group', { name: copy.summary.heading })
    // The exact sum -7.78503, rounded once: never the sum of the rounded rows (0.01 × 3 − 7.80 = −7.77).
    expect(summary).toHaveTextContent('-7.79 USD')
    expect(summary).not.toHaveTextContent('-7.77')
    expect(summary).toHaveTextContent(copy.summary.netHelp)
    const [one, , , withdrawal] = listed()
    expect(one).toHaveTextContent('0.005 USDT')
    expect(one).toHaveTextContent('0.01 USD')
    expect(withdrawal).toHaveTextContent('-0.00012 BTC')
    expect(withdrawal).toHaveTextContent('-7.80 USD')
    expect(withdrawal).toHaveTextContent(copy.estimated.true)
    // The term's help describes the amounts; it says an adjustment is no gain or return.
    const usd = within(present(withdrawal)).getByText('-7.80 USD')
    // eslint-disable-next-line testing-library/no-node-access -- 금액이 도움말을 가리키는지 본다.
    expect(usd.closest('dd')).toHaveAttribute('aria-describedby', 'adjustment-history-term')
    expect(within(region()).getByText(copy.term)).toHaveAttribute('id', 'adjustment-history-term')

    fireEvent.click(detailToggle(4, copy.filter.options.OUT))
    const detail = region().textContent
    expect(detail).toContain('65,000.25 USD')
    expect(detail).toContain(copy.selectionRules.EVENT_MINUTE)
    // The event's own minute names no lag.
    expect(detail).not.toContain(copy.lag(0))
    expect(detail).toContain(copy.evidenceTypes.SPOT_KLINE_CLOSE)
    expect(detail).toContain(copy.routes.SPOT_ASSET_USDT)
    expect(within(region()).getByText('BTCUSDT').tagName).toBe('CODE')
  })

  it('FILTER: choosing 외부 출금 reads that filter’s first page and replaces the rows and the summary', async () => {
    const result = shownResult()
    const { urls } = answers(
      ok(firstPage(result)),
      ok(
        adjustmentPageFixture(result, {
          direction: 'OUT',
          rows: [4, 8, 12, 16, 20].map((sequence) =>
            adjustmentWithdrawalFixture(sequence, EVIDENCE_IDS.calculation),
          ),
          summary: { count: 5, netUsdValue: '-12.5', excludedCount: 2 },
        }),
      ),
    )
    render(<Harness view={ready(result)} />)
    await openHistory()

    fireEvent.click(within(region()).getByRole('radio', { name: copy.filter.options.OUT }))
    await waitFor(() => {
      expect(listed()).toHaveLength(5)
    })
    expect(query(urls[1])).toMatchObject({ direction: 'OUT' })
    expect(query(urls[1]).cursor).toBeUndefined()
    expect(within(region()).getByRole('radio', { name: copy.filter.options.OUT })).toBeChecked()
    expect(within(region()).getByRole('group', { name: copy.summary.heading })).toHaveTextContent(
      '-12.50 USD',
    )
    for (const row of listed()) expect(row).toHaveTextContent(copy.filter.options.OUT)
    expect(within(region()).queryByRole('button', { name: copy.more })).not.toBeInTheDocument()
  })

  it('EXCLUDED: the excluded count stands apart from the filtered summary and says it does not depend on the type', async () => {
    const result = shownResult()
    answers(
      ok(firstPage(result)),
      ok(
        adjustmentPageFixture(result, {
          direction: 'IN',
          rows: rows(1, 3),
          summary: { count: 3, netUsdValue: '30.75', excludedCount: 2 },
        }),
      ),
    )
    render(<Harness view={ready(result)} />)
    await openHistory()

    const summary = within(region()).getByRole('group', { name: copy.summary.heading })
    const excluded = within(region()).getByText(copy.excluded.text(2))
    expect(summary).not.toHaveTextContent(copy.excluded.text(2))
    expect(summary.contains(excluded)).toBe(false)
    expect(excluded.textContent).toMatch(/유형과 관계없이/)
    expect(region()).toHaveTextContent(copy.excluded.help)

    fireEvent.click(within(region()).getByRole('radio', { name: copy.filter.options.IN }))
    await waitFor(() => {
      expect(listed()).toHaveLength(3)
    })
    // Another filter, another summary — and the same excluded count, still apart from it.
    expect(within(region()).getByRole('group', { name: copy.summary.heading })).toHaveTextContent(
      '30.75 USD',
    )
    expect(within(region()).getByText(copy.excluded.text(2))).toBeInTheDocument()
  })

  it('an earlier candle names how far back it lies, whatever lag the price policy allowed', async () => {
    const result = shownResult({ flowCount: 1 })
    const lagged = adjustmentRowFixture(1, EVIDENCE_IDS.calculation, {
      asset: 'BTC',
      signedQuantity: '0.01',
      usdValue: '650.0025',
      price: {
        policyVersion: 'BYBIT-PRICE-1.0',
        evidenceType: 'SPOT_KLINE_CLOSE',
        estimated: true,
        unitPrice: '65000.25',
        routeId: 'SPOT_ASSET_USDT',
        legs: [
          {
            symbol: 'BTCUSDT',
            candleStartAt: '2026-09-25T23:53:00Z',
            candleEndAt: '2026-09-25T23:54:00Z',
            closePrice: '65000.25',
            selectionRule: 'PRIOR_CLOSED_MINUTE',
            lagMinutes: 7,
          },
        ],
      },
    })
    answers(ok(adjustmentPageFixture(result, { rows: [lagged] })))
    render(<Harness view={ready(result)} />)
    await openHistory()
    fireEvent.click(detailToggle(1, copy.filter.options.IN))
    expect(region()).toHaveTextContent(
      `${copy.selectionRules.PRIOR_CLOSED_MINUTE} ${copy.separators.item} ${copy.lag(7)}`,
    )
  })

  it('SAME_TIME: two flows of one instant and one type differ by their sequence, in the title and the toggle', async () => {
    const result = shownResult({ flowCount: 2 })
    const twins = [1, 2].map((sequence) =>
      adjustmentRowFixture(sequence, EVIDENCE_IDS.calculation, { occurredAt: adjustmentTime(1) }),
    )
    answers(ok(adjustmentPageFixture(result, { rows: twins })))
    render(<Harness view={ready(result)} />)
    await openHistory()

    const titles = listed().map((row) => within(row).getByRole('heading', { level: 5 }).textContent)
    expect(new Set(titles).size).toBe(2)
    expect(titles[0]).toContain(copy.row.title(1))
    expect(titles[1]).toContain(copy.row.title(2))
    for (const sequence of [1, 2]) {
      expect(
        within(region()).getByRole('button', {
          name: copy.row.detailOpen(sequence, timeLabel(1), copy.filter.options.IN),
        }),
      ).toBeInTheDocument()
    }
  })

  it('PAGED: 더 보기 appends the next page and moves focus to the first new row; the last page has no 더 보기', async () => {
    answers(ok(firstPage()), ok(lastPage()))
    render(<Harness view={ready()} />)
    await openHistory()

    const more = within(region()).getByRole('button', { name: copy.more })
    more.focus()
    fireEvent.click(more)
    await waitFor(() => {
      expect(listed()).toHaveLength(23)
    })
    await waitFor(() => {
      expect(within(present(listed()[20])).getByRole('heading', { level: 5 })).toHaveFocus()
    })
    expect(within(region()).queryByRole('button', { name: copy.more })).not.toBeInTheDocument()
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.ready(23, 23))
    // No duplicate: 23 distinct rows in order.
    expect(
      listed().map((row) => within(row).getByRole('heading', { level: 5 }).textContent),
    ).toHaveLength(new Set(listed().map((row) => row.textContent)).size)
  })

  it('AC-2 (READ_FAIL): a failed 더 보기 keeps the rows and the dashboard as they were, and 더 보기 stays in place to ask again', async () => {
    const reload = vi.fn()
    answers(
      ok(firstPage()),
      () =>
        Promise.resolve(
          json({ code: 'ADJUSTMENT_HISTORY_UNAVAILABLE', message: 'x', correlationId: 'c' }, 503),
        ),
      ok(lastPage()),
    )
    render(<Harness reload={reload} view={ready()} />)
    const before = cards().map((card) => card.textContent)
    await openHistory()

    const more = within(region()).getByRole('button', { name: copy.more })
    more.focus()
    fireEvent.click(more)
    // While it reads, the control stays in place, busy, and keeps focus.
    expect(within(region()).getByRole('button', { name: copy.more })).toBe(more)
    expect(more).toHaveAttribute('aria-disabled', 'true')
    expect(more).toHaveFocus()
    await within(region()).findByText(copy.failure.moreRetryable)
    expect(listed()).toHaveLength(20)
    expect(cards().map((card) => card.textContent)).toEqual(before)
    expect(region()).toHaveTextContent(copy.failure.moreRetryableHelp)
    expect(region()).not.toHaveTextContent(copy.failure.retryable)
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.moreRetryable)
    expect(within(region()).getByRole('button', { name: copy.more })).toBe(more)
    expect(more).toHaveAttribute('aria-disabled', 'false')
    expect(reload).not.toHaveBeenCalled()

    fireEvent.click(more)
    await waitFor(() => {
      expect(listed()).toHaveLength(23)
    })
    expect(within(region()).queryByText(copy.failure.moreRetryable)).not.toBeInTheDocument()
  })

  it.each([
    ['repeats the last sequence', 20],
    ['goes back', 5],
  ])(
    'a next page that %s is a failed read, and the rows already received stay',
    async (_name, sequence) => {
      const result = shownResult()
      answers(
        ok(firstPage(result)),
        ok(
          adjustmentPageFixture(result, {
            rows: [sequence, 21, 22].map((each) =>
              adjustmentRowFixture(each, EVIDENCE_IDS.calculation),
            ),
            summary: SUMMARY,
          }),
        ),
      )
      render(<Harness view={ready(result)} />)
      await openHistory()
      fireEvent.click(within(region()).getByRole('button', { name: copy.more }))
      await within(region()).findByText(copy.failure.moreRetryable)
      expect(listed()).toHaveLength(20)
    },
  )

  it('a first page that fails offers a retry that keeps its place and focus', async () => {
    answers(() => Promise.reject(new Error('network')), ok(firstPage()))
    render(<Harness view={ready()} />)
    fireEvent.click(toggle())
    await within(region()).findByText(copy.failure.retryable)
    expect(region()).toHaveTextContent(copy.failure.retryableHelp)
    expect(region()).not.toHaveTextContent(copy.failure.moreRetryable)
    expect(region().textContent).not.toMatch(/이미 받은 행/)
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.retryable)
    expect(within(region()).queryByRole('list', { name: copy.list })).not.toBeInTheDocument()

    const retry = within(region()).getByRole('button', { name: copy.retry })
    retry.focus()
    fireEvent.click(retry)
    expect(within(region()).getByRole('button', { name: copy.retry })).toBe(retry)
    expect(retry).toHaveAttribute('aria-disabled', 'true')
    expect(retry).toHaveFocus()
    await within(region()).findByRole('list', { name: copy.list })
    // The retry control is gone with the failure: focus moves to the region's title, not the body.
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 4, name: copy.heading })).toHaveFocus()
    })
  })

  it('a 404 says the shown result is not the current one, offers no retry, and points to reloading the dashboard', async () => {
    const reload = vi.fn()
    answers(() =>
      Promise.resolve(json({ code: 'NOT_FOUND', message: 'x', correlationId: 'c' }, 404)),
    )
    render(<Harness reload={reload} view={ready()} />)
    const before = cards().map((card) => card.textContent)
    fireEvent.click(toggle())
    await within(region()).findByText(copy.failure.stale)
    expect(region()).toHaveTextContent(copy.failure.staleHelp)
    expect(within(region()).queryByRole('button', { name: copy.retry })).not.toBeInTheDocument()
    expect(within(region()).queryByRole('button', { name: copy.more })).not.toBeInTheDocument()
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.stale)
    expect(cards().map((card) => card.textContent)).toEqual(before)
    expect(reload).not.toHaveBeenCalled()
  })

  it('a 403 drops the history and asks the dashboard to read again; a 401 does too, with a link back to the entry screen', async () => {
    const reload = vi.fn()
    answers(
      ok(firstPage()),
      () => Promise.resolve(json({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403)),
      () =>
        Promise.resolve(json({ code: 'UNAUTHENTICATED', message: 'x', correlationId: 'c' }, 401)),
    )
    render(<Harness reload={reload} view={ready()} />)
    await openHistory()

    fireEvent.click(within(region()).getByRole('button', { name: copy.more }))
    await within(region()).findByText(copy.failure.denied)
    expect(within(region()).queryByRole('list')).toBeNull()
    expect(region()).not.toHaveTextContent('adj-01')
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.denied)
    expect(reload).toHaveBeenCalledTimes(1)

    fireEvent.click(toggle())
    fireEvent.click(toggle())
    await within(region()).findByText(copy.failure.signedOut)
    expect(within(region()).getByRole('link', { name: copy.failure.signInLink })).toHaveAttribute(
      'href',
      ENTRY_PATH,
    )
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.signedOut)
    expect(reload).toHaveBeenCalledTimes(2)
  })

  it('discards the history and closes when the shown bundle is replaced, returning focus to the button', async () => {
    answers(ok(firstPage()))
    const { rerender } = render(<Harness view={ready()} />)
    await openHistory()
    within(region()).getByRole('button', { name: copy.close }).focus()

    rerender(
      <Harness
        view={ready(
          shownResult({ pointerVersion: 2, calculationId: EVIDENCE_IDS.newerCalculation }),
        )}
      />,
    )
    expect(toggle()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: copy.heading })).not.toBeInTheDocument()
    expect(toggle()).toHaveFocus()
    expect(document.body.textContent).not.toContain('adj-01')
  })

  it('EMPTY: with no applied flow at all it says 확인된 외부 자금흐름이 없습니다 beside the verified range', async () => {
    const result = shownResult({ flowCount: 0 })
    answers(ok(adjustmentPageFixture(result, { rows: [] })))
    render(<Harness view={ready(result)} />)
    fireEvent.click(toggle())
    await within(region()).findByText(copy.empty.none)
    expect(region()).toHaveTextContent(copy.empty.noneHelp)
    expect(within(region()).getByRole('group', { name: copy.summary.heading })).toHaveTextContent(
      copy.summary.coverage,
    )
    expect(
      // eslint-disable-next-line testing-library/no-node-access -- 확인 구간의 두 시각이 기계 값을 갖는지 본다.
      [...region().querySelectorAll('time')].map((time) => time.getAttribute('datetime')),
    ).toEqual(
      expect.arrayContaining(['2026-09-25T00:00:00Z', result.cashflowVerifiedThrough as string]),
    )
    expect(within(region()).queryByRole('list', { name: copy.list })).not.toBeInTheDocument()
  })

  it('EMPTY: a filter with nothing says that type has none, not that nothing was confirmed', async () => {
    const result = shownResult({ flowCount: 3 })
    answers(
      ok(adjustmentPageFixture(result, { rows: rows(1, 3) })),
      ok(
        adjustmentPageFixture(result, {
          direction: 'OUT',
          rows: [],
          summary: { count: 0, netUsdValue: '0', excludedCount: 0 },
        }),
      ),
    )
    render(<Harness view={ready(result)} />)
    await openHistory()
    fireEvent.click(within(region()).getByRole('radio', { name: copy.filter.options.OUT }))
    await within(region()).findByText(copy.empty.filtered(copy.filter.options.OUT))
    expect(region()).not.toHaveTextContent(copy.empty.none)
  })

  it('AC-3: an input reliability of 검토 필요 adds the cause, the range and where to look — apart from the rows', async () => {
    const result = shownResult({
      trust: trustFixture({
        inputReliability: {
          state: 'REVIEW_REQUIRED',
          cause: 'TARGET_NOT_POSITIVE',
          affectedMetrics: ['ADJUSTED_TARGET', 'GOAL_PROGRESS'],
        },
      }),
    })
    answers(ok(firstPage(result)))
    render(<Harness view={ready(result)} />)
    await openHistory()

    const guidance = within(region()).getByText(copy.review.heading)
    // eslint-disable-next-line testing-library/no-node-access -- 안내 묶음 전체를 행 목록과 대조한다.
    const box = present(guidance.parentElement)
    expect(box).toHaveTextContent(
      planResultContent.axes.reliability.causes.TARGET_NOT_POSITIVE ?? 'missing copy',
    )
    expect(box).toHaveTextContent(copy.review.range)
    expect(box).toHaveTextContent(copy.review.where)
    expect(list().contains(box)).toBe(false)
    expect(box.contains(list())).toBe(false)
  })

  it('AC-3: a confirmed input reliability adds no review guidance', async () => {
    answers(ok(firstPage()))
    render(<Harness view={ready()} />)
    await openHistory()
    expect(within(region()).queryByText(copy.review.heading)).not.toBeInTheDocument()
  })

  it('AC-6 (REVISED): a changed row says so in words, and its detail lists the earlier version as never used officially, with its value or none', async () => {
    const result = shownResult({ flowCount: 1 })
    const changed = adjustmentRowFixture(1, EVIDENCE_IDS.calculation, {
      changed: true,
      revisions: [
        {
          conversionId: ADJUSTMENT_IDS.earlierConversion,
          processedAt: '2026-09-26T00:10:00Z',
          status: 'CONVERSION_REQUIRED',
          reasonCode: 'PRICE_CANDLE_NOT_CLOSED',
          usdValue: null,
          changeReason: 'EVIDENCE_CHANGE',
          discoveredAt: '2026-09-26T00:12:00Z',
        },
        {
          conversionId: '5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d',
          processedAt: '2026-09-26T00:05:00Z',
          status: 'CONVERTED_ESTIMATED',
          reasonCode: null,
          usdValue: '10.2504',
          changeReason: 'PRICE_REVISION',
          discoveredAt: '2026-09-26T00:10:00Z',
        },
      ],
    })
    answers(ok(adjustmentPageFixture(result, { rows: [changed] })))
    render(<Harness view={ready(result)} />)
    await openHistory()

    const [row] = listed()
    expect(row).toHaveTextContent(copy.row.changed)
    fireEvent.click(detailToggle(1, copy.filter.options.IN))
    const revisions = within(present(row)).getAllByText(copy.detail.earlier)
    expect(revisions).toHaveLength(2)
    const shown = row?.textContent ?? ''
    // Newest first: the version the current one replaced, then the one before it.
    expect(shown.indexOf(ADJUSTMENT_IDS.earlierConversion)).toBeLessThan(
      shown.indexOf('5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d'),
    )
    expect(shown).toContain(copy.detail.noValue)
    // A change below the second decimal: the rounded values alike, the whole values apart.
    expect(shown).toContain(copy.detail.currentValue)
    expect(shown).toContain(copy.detail.fullValue('10.25'))
    expect(shown).toContain(copy.detail.fullValue('10.2504'))
    expect(shown.split('10.25 USD').length - 1).toBeGreaterThanOrEqual(2)
    expect(shown).toContain(copy.statuses.CONVERSION_REQUIRED)
    expect(shown).toContain(copy.reasons.PRICE_CANDLE_NOT_CLOSED ?? 'missing copy')
    expect(shown).toContain(copy.changeReasons.EVIDENCE_CHANGE)
    expect(shown).toContain(copy.changeReasons.PRICE_REVISION)
    expect(shown).not.toContain('현재 공식 버전')
  })

  it('an unchanged row carries no marker and no earlier versions', async () => {
    answers(ok(firstPage()))
    render(<Harness view={ready()} />)
    await openHistory()
    expect(region()).not.toHaveTextContent(copy.row.changed)
    fireEvent.click(detailToggle(1, copy.filter.options.IN))
    expect(region()).not.toHaveTextContent(copy.detail.earlier)
  })

  it('folds unknown codes to its own safe copy and never shows them as they are', async () => {
    const result = shownResult({ flowCount: 1 })
    const odd = adjustmentRowFixture(1, EVIDENCE_IDS.calculation, {
      price: {
        policyVersion: 'BYBIT-PRICE-1.0',
        evidenceType: 'SOMETHING_NEW',
        estimated: false,
        unitPrice: '1',
        routeId: 'ELSEWHERE',
        legs: [],
      },
    })
    answers(ok(adjustmentPageFixture(result, { rows: [odd] })))
    render(<Harness view={ready(result)} />)
    await openHistory()
    fireEvent.click(detailToggle(1, copy.filter.options.IN))
    expect(region()).toHaveTextContent(copy.unknownEvidenceType)
    expect(region()).toHaveTextContent(copy.unknownRoute)
    expect(region()).not.toHaveTextContent('SOMETHING_NEW')
    expect(region()).not.toHaveTextContent('ELSEWHERE')
  })

  it('writes nothing to browser storage and uses no performance, recommendation, celebration or certainty wording', async () => {
    answers(ok(firstPage()))
    render(<Harness view={ready()} />)
    await openHistory()
    fireEvent.click(detailToggle(1, copy.filter.options.IN))

    expect(getObservedStorageEntries()).toEqual([])
    expect(region().textContent).not.toMatch(
      /축하|성공|실패|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치|정확/,
    )
    // The copy itself: the term help says what an adjustment is not.
    expect(copy.term).toMatch(/투자 손익/)
  })

  it('reads nothing while the region stays closed', async () => {
    // No history stub: a hidden read would be refused and recorded by the global guard.
    render(<Harness view={ready()} />)
    await waitFor(() => {
      expect(toggle()).toBeInTheDocument()
    })
    await new Promise((settle) => setTimeout(settle, 20))
    expect(
      getObservedRequests().filter((entry) => entry.includes(DASHBOARD_ADJUSTMENTS_PATH)),
    ).toEqual([])
    expect(
      getObservedAllowedRequests().filter((entry) => entry.includes(DASHBOARD_ADJUSTMENTS_PATH)),
    ).toEqual([])
  })

  it('announces each state in one sentence for the screen’s status region, and nothing while closed', () => {
    expect(adjustmentAnnouncement({ kind: 'closed' })).toBeNull()
    const open = { kind: 'open', direction: 'ALL', list: null } as const
    expect(
      adjustmentAnnouncement({ ...open, read: { kind: 'loading', more: false, retrying: false } }),
    ).toBe(copy.status.loading)
    expect(
      adjustmentAnnouncement({ ...open, read: { kind: 'loading', more: true, retrying: false } }),
    ).toBe(copy.status.loadingMore)
    expect(
      adjustmentAnnouncement({ ...open, read: { kind: 'failed', more: false, retryable: true } }),
    ).toBe(copy.status.retryable)
    expect(
      adjustmentAnnouncement({ ...open, read: { kind: 'failed', more: true, retryable: true } }),
    ).toBe(copy.status.moreRetryable)
    expect(
      adjustmentAnnouncement({ ...open, read: { kind: 'failed', more: true, retryable: false } }),
    ).toBe(copy.status.stale)
    expect(adjustmentAnnouncement({ kind: 'denied', cause: 'refused' })).toBe(copy.status.denied)
    expect(adjustmentAnnouncement({ kind: 'denied', cause: 'signedOut' })).toBe(
      copy.status.signedOut,
    )
  })

  describe('parseAdjustmentPage and appendPage: a page is accepted whole, and only for the shown bundle', () => {
    const shown = parsed(shownResult())
    const base = (): Record<string, unknown> => firstPage()
    const change = (fields: Record<string, unknown>): Record<string, unknown> => ({
      ...base(),
      ...fields,
    })
    const firstRow = (fields: Record<string, unknown>): Record<string, unknown> => {
      const page = base()
      const [head, ...tail] = page.rows as Record<string, unknown>[]
      return { ...page, rows: [{ ...head, ...fields }, ...tail] }
    }

    it('accepts the fixture', () => {
      expect(parseAdjustmentPage(base(), shown, 'ALL')).not.toBeNull()
    })

    it.each([
      ['another plan', change({ planId: EVIDENCE_IDS.newerCalculation })],
      ['another version', change({ pointerVersion: 2 })],
      ['another calculation', change({ calculationId: EVIDENCE_IDS.newerCalculation })],
      ['another filter', change({ direction: 'IN' })],
      ['another publication time', change({ appliedAt: '2026-09-27T01:00:02Z' })],
      [
        'another range',
        change({ coverage: { from: '2026-09-25T00:00:01Z', through: '2026-09-27T01:00:00Z' } }),
      ],
      ['a count that is not the shown flow count', change({ summary: { ...SUMMARY, count: 22 } })],
      ['a total that is not canonical', change({ summary: { ...SUMMARY, netUsdValue: '172.0' } })],
      ['a next page after a short page', change({ rows: rows(1, 19) })],
      ['more rows than a page', change({ rows: rows(1, 21) })],
      ['an empty cursor', change({ nextCursor: '' })],
      ['falling sequences', change({ rows: [...rows(2, 20), ...rows(1, 1)] })],
      ['a withdrawal with a positive value', firstRow({ direction: 'OUT' })],
      ['a quantity that is rounded display text', firstRow({ signedQuantity: '10.250' })],
      [
        'a row of another calculation',
        firstRow({
          refs: {
            sourceEventId: 'x',
            sourceId: 'y',
            classificationId: 'z',
            conversionId: 'w',
            calculationId: 'other',
          },
        }),
      ],
      ['a change marker without earlier versions', firstRow({ changed: true })],
      [
        'earlier versions without a change marker',
        firstRow({
          revisions: [
            {
              conversionId: 'x',
              processedAt: '2026-09-26T00:05:00Z',
              status: 'CONVERTED',
              reasonCode: null,
              usdValue: '1',
              changeReason: 'PRICE_REVISION',
              discoveredAt: '2026-09-26T00:06:00Z',
            },
          ],
        }),
      ],
      ['an event time that is not an instant', firstRow({ occurredAt: 'yesterday' })],
    ])('rejects %s', (_name, page) => {
      expect(parseAdjustmentPage(page, shown, 'ALL')).toBeNull()
    })

    it('rejects a deposit under the withdrawal filter', () => {
      const page = adjustmentPageFixture(shownResult(), {
        direction: 'OUT',
        rows: rows(1, 1),
        summary: { count: 1, netUsdValue: '10.25', excludedCount: 0 },
      })
      expect(parseAdjustmentPage(page, shown, 'OUT')).toBeNull()
    })

    it('appends a page that continues what is held, and nothing else', () => {
      const held = present(parseAdjustmentPage(base(), shown, 'ALL'))
      const next = present(parseAdjustmentPage(lastPage(), shown, 'ALL'))
      expect(appendPage(held, next)?.rows).toHaveLength(23)
      expect(
        appendPage(held, { ...next, summary: { ...next.summary, netUsdValue: '171' } }),
      ).toBeNull()
      expect(appendPage(held, { ...next, rows: [] })).toBeNull()
      // A last page whose rows do not add up to the count.
      expect(appendPage(held, { ...next, rows: next.rows.slice(0, 2) })).toBeNull()
      // An event already listed.
      const [head, ...tail] = next.rows
      const [listedFirst] = held.rows
      if (head === undefined || listedFirst === undefined) throw new Error('rows')
      expect(
        appendPage(held, { ...next, rows: [{ ...head, refs: listedFirst.refs }, ...tail] }),
      ).toBeNull()
    })
  })

  it('keeps the history while the same bundle stays shown after a failed dashboard reload', async () => {
    answers(ok(firstPage()))
    const result = parsed(shownResult())
    const { rerender } = render(<Harness view={{ kind: 'READY', nextAction: 'NONE', result }} />)
    await openHistory()
    rerender(<Harness view={{ kind: 'lastGood', nextAction: 'NONE', result }} />)
    expect(listed()).toHaveLength(20)
  })
})
