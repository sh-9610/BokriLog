import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { useLayoutEffect, useState, type ReactElement } from 'react'
import { describe, expect, it, vi } from 'vitest'

import { ENTRY_PATH } from '../content/auth'
import {
  calculationEvidenceContent as copy,
  DASHBOARD_EVIDENCE_PATH,
  evidencePath,
} from '../content/calculationEvidence'
import { METRIC_ORDER, planPublicationContent, planResultContent } from '../content/planResult'
import { planVerificationContent } from '../content/planVerification'
import { formatSeoulTime } from '../format/decimal'
import { useDashboardAdjustments } from '../session/useAdjustmentHistory'
import {
  evidenceAnnouncement,
  parseEvidence,
  useDashboardEvidence,
} from '../session/useCalculationEvidence'
import {
  isPlanResult,
  NO_CHANGE,
  type DashboardView,
  type PlanResult,
} from '../session/useDashboard'
import {
  AVAILABLE_SYNC,
  AVAILABLE_VERIFICATION,
  calculationEvidenceFixture,
  comparedMetricsFixture,
  estimatedUndefinedFixture,
  EVIDENCE_IDS,
  evidenceChangeFixture,
  evidenceResultFixture,
  idleSyncState,
  MEASURED_AT,
  PREVIOUS_BASIS_AT,
  trustFixture,
} from '../test/planResultFixture'
import {
  getObservedAllowedRequests,
  getObservedRequests,
  getObservedStorageEntries,
  stubCalculationEvidence,
} from '../test/setup'
import { PlanResultPanel } from './PlanResultPanel'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function parsed(body: Record<string, unknown>): PlanResult {
  expect(isPlanResult(body)).toBe(true)
  return body as unknown as PlanResult
}

function ready(body: Record<string, unknown> = evidenceResultFixture()): DashboardView {
  return {
    kind: 'READY',
    nextAction: 'NONE',
    result: parsed(body),
    sync: AVAILABLE_SYNC,
    verification: AVAILABLE_VERIFICATION,
    change: NO_CHANGE,
  }
}

/** The estimated, partly undefined bundle with UUID-shaped ids. */
function estimatedResult(): Record<string, unknown> {
  const { trust, metrics, flowCount, estimatedFlowCount } = estimatedUndefinedFixture()
  return evidenceResultFixture({ trust, metrics, flowCount, estimatedFlowCount })
}

/** The evidence answer of the default UUID-shaped bundle. */
function evidenceOk(): Promise<Response> {
  return Promise.resolve(json(calculationEvidenceFixture(evidenceResultFixture())))
}

/**
 * The dashboard panel with the evidence of the bundle it shows, wired as the two screens wire it. `reload`
 * is the dashboard's: an evidence refusal asks the dashboard to read again.
 */
function Harness({
  view,
  reload = vi.fn(),
}: {
  readonly view: DashboardView
  readonly reload?: () => void
}): ReactElement {
  const dashboard = { view, loading: false, reload, refresh: vi.fn() }
  const evidence = useDashboardEvidence(dashboard)
  const adjustments = useDashboardAdjustments(dashboard)
  return (
    <>
      <p data-testid="announcement">{evidenceAnnouncement(evidence.view) ?? ''}</p>
      <PlanResultPanel
        adjustments={adjustments}
        dashboard={dashboard}
        evidence={evidence}
        sync={idleSyncState()}
      />
    </>
  )
}

/** Answers the evidence reads in order, recording the URL and the request options of each. */
function answers(...queue: (() => Promise<Response>)[]): {
  urls: string[]
  inits: (RequestInit | undefined)[]
} {
  const urls: string[] = []
  const inits: (RequestInit | undefined)[] = []
  stubCalculationEvidence((url, init) => {
    urls.push(url)
    inits.push(init)
    const next = queue.shift()
    if (next === undefined) throw new Error('an unexpected evidence read')
    return next()
  })
  return { urls, inits }
}

function deferred(): { promise: Promise<Response>; resolve: (response: Response) => void } {
  let resolve: (response: Response) => void = () => undefined
  const promise = new Promise<Response>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

function toggle(): HTMLElement {
  return screen.getByRole('button', { name: copy.open })
}

function region(): HTMLElement {
  return screen.getByRole('region', { name: copy.heading })
}

function part(name: string): HTMLElement {
  return within(region()).getByRole('group', { name })
}

async function openEvidence(): Promise<void> {
  fireEvent.click(toggle())
  await screen.findByRole('group', { name: copy.sections.versions })
}

function cards(): HTMLElement[] {
  return within(screen.getByRole('region', { name: planResultContent.heading })).getAllByRole(
    'article',
  )
}

function newerBundle(): Record<string, unknown> {
  return evidenceResultFixture({ pointerVersion: 2, calculationId: EVIDENCE_IDS.newerCalculation })
}

/** The change part of v2 (Story 2.12): v1 right before it, the six metrics compared, no changed flow. */
function changeFromV1(result: Record<string, unknown>): Record<string, unknown> {
  return evidenceChangeFixture({
    purpose: 'SYNC',
    previous: {
      pointerVersion: 1,
      calculationId: EVIDENCE_IDS.calculation,
      basisAt: PREVIOUS_BASIS_AT,
    },
    metrics: comparedMetricsFixture(result),
  })
}

describe('CalculationEvidence', () => {
  it('AC-1/AC-7: 계산 근거 보기 opens the region on the same screen, moves focus to its title and reads in order', async () => {
    const { urls } = answers(evidenceOk)
    render(<Harness view={ready()} />)

    const button = toggle()
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(button).toHaveAttribute('aria-controls', 'calculation-evidence')
    // Nothing is read, and nothing of the region is exposed, before the user opens it.
    expect(screen.queryByRole('region', { name: copy.heading })).not.toBeInTheDocument()
    expect(urls).toEqual([])

    fireEvent.click(button)
    expect(button).toHaveAttribute('aria-expanded', 'true')
    const title = screen.getByRole('heading', { level: 4, name: copy.heading })
    expect(title).toHaveFocus()
    expect(region()).toHaveAttribute('aria-busy', 'true')
    expect(within(region()).getByText(copy.status.loading)).toBeInTheDocument()

    await screen.findByRole('group', { name: copy.sections.versions })
    expect(region()).toHaveAttribute('id', 'calculation-evidence')
    expect(region()).toHaveAttribute('aria-busy', 'false')
    expect(
      within(region())
        .getAllByRole('heading', { level: 5 })
        .map((heading) => heading.textContent),
    ).toEqual([
      // Story 2.12: what the version changed comes first, before the snapshots.
      planVerificationContent.evidence.title,
      copy.sections.snapshots,
      copy.sections.times,
      copy.sections.inputs,
      copy.sections.calculation,
      copy.sections.metrics,
      copy.sections.versions,
    ])
    // The panel's own sections are unchanged: the evidence sits inside the metrics section.
    expect(
      within(screen.getByRole('region', { name: planResultContent.heading }))
        .getAllByRole('heading', { level: 3 })
        .map((heading) => heading.textContent),
    ).toEqual([
      planResultContent.currentHeading,
      planResultContent.trustHeading,
      planResultContent.metricsHeading,
      planResultContent.nextActionHeading,
      planResultContent.conditionsHeading,
    ])
    // No live region of its own and nothing that navigates.
    // eslint-disable-next-line testing-library/no-node-access -- 역할 없는 live region은 역할 조회로 보이지 않는다. 속성으로만 찾을 수 있다.
    expect(region().querySelector('[aria-live]')).toBeNull()
    expect(within(region()).queryByRole('status')).not.toBeInTheDocument()
    expect(within(region()).queryAllByRole('link')).toEqual([])
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.ready)
  })

  it('sends exactly the three values of the shown pointer, URL-encoded, and nothing else', async () => {
    const { urls, inits } = answers(evidenceOk)
    render(<Harness view={ready()} />)
    await openEvidence()

    expect(urls).toHaveLength(1)
    const url = new URL(urls[0] ?? '', 'http://evidence.test')
    expect(url.pathname).toBe(DASHBOARD_EVIDENCE_PATH)
    expect([...url.searchParams.keys()]).toEqual(['planId', 'pointerVersion', 'calculationId'])
    expect(Object.fromEntries(url.searchParams)).toEqual({
      planId: EVIDENCE_IDS.plan,
      pointerVersion: '1',
      calculationId: EVIDENCE_IDS.calculation,
    })
    expect(inits[0]).toMatchObject({ credentials: 'same-origin', cache: 'no-store' })
    // The query is built by URLSearchParams: a value is encoded, never spliced in raw.
    expect(evidencePath({ planId: 'a&b=c', pointerVersion: 2, calculationId: 'd e' })).toBe(
      `${DASHBOARD_EVIDENCE_PATH}?planId=a%26b%3Dc&pointerVersion=2&calculationId=d+e`,
    )
  })

  it('AC-1: 계산 근거 닫기 closes the region and returns focus to the button; the button toggles too', async () => {
    answers(evidenceOk, evidenceOk)
    render(<Harness view={ready()} />)
    await openEvidence()

    fireEvent.click(within(region()).getByRole('button', { name: copy.close }))
    expect(toggle()).toHaveFocus()
    expect(toggle()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: copy.heading })).not.toBeInTheDocument()
    expect(screen.getByTestId('announcement')).toHaveTextContent('')

    // Opening again reads again; the button closes it as well.
    await openEvidence()
    fireEvent.click(toggle())
    expect(toggle()).toHaveAttribute('aria-expanded', 'false')
    expect(
      getObservedAllowedRequests().filter((entry) => entry.includes(DASHBOARD_EVIDENCE_PATH)),
    ).toHaveLength(2)
  })

  it.each([
    ['different', '2026-09-27T01:00:00Z', copy.times.different, copy.times.same],
    ['the same', MEASURED_AT, copy.times.same, copy.times.different],
  ])(
    'AC-2: the times keep their own names and meanings, say Bybit has no common snapshot, and say when they are %s',
    async (_name, verified, sentence, other) => {
      const result = evidenceResultFixture({
        cashflowVerifiedThrough: verified,
        trust: trustFixture({
          freshness: { cashflowVerifiedThrough: verified },
          inputReliability: { lastNormalAt: verified },
        }),
      })
      answers(() => Promise.resolve(json(calculationEvidenceFixture(result))))
      render(<Harness view={ready(result)} />)
      await openEvidence()

      const times = part(copy.sections.times)
      for (const [label, help] of [
        [copy.times.t0, copy.times.t0Help],
        [copy.times.tc, copy.times.tcHelp],
        [copy.times.measuredAt, copy.times.measuredAtHelp],
        [copy.times.verifiedThrough, copy.times.verifiedThroughHelp],
      ] as const) {
        expect(within(times).getByText(label)).toBeInTheDocument()
        expect(within(times).getByText(help)).toBeInTheDocument()
      }
      expect(
        within(times)
          .getAllByRole('time')
          .map((each) => each.getAttribute('datetime')),
      ).toEqual(['2026-09-25T00:00:00Z', '2026-09-27T01:00:00Z', MEASURED_AT, verified])
      // To the second in the times section: a second apart does not look alike.
      expect(times).toHaveTextContent(/09:59:59\.000/)
      expect(times).toHaveTextContent(copy.times.noCommonSnapshot)
      expect(times).toHaveTextContent(sentence)
      expect(times).not.toHaveTextContent(other)
      // Never a common snapshot token, and never one "latest" time.
      expect(region().textContent).not.toMatch(/공통 스냅샷 토큰|스냅샷 토큰|실시간|최신 시각/)
    },
  )

  it('shows only the times section to the millisecond; the plan period and the other times like the plan terms', async () => {
    answers(evidenceOk)
    render(<Harness view={ready()} />)
    await openEvidence()

    const precise = /\d{2}:\d{2}:\d{2}\.\d{3}/
    expect(part(copy.sections.times).textContent).toMatch(precise)
    for (const name of [copy.sections.inputs, copy.sections.calculation, copy.sections.versions]) {
      expect(part(name).textContent).not.toMatch(precise)
    }
  })

  it('AC-3: long identifiers are shown whole in the mono style, and assistive technology reads the same value', async () => {
    answers(evidenceOk)
    render(<Harness view={ready()} />)
    await openEvidence()

    const snapshots = part(copy.sections.snapshots)
    for (const value of [
      EVIDENCE_IDS.snapshot,
      EVIDENCE_IDS.basis,
      '1e4d3b5f-0c2e-4d9f-b8a7-6e3c2f1d0b94',
      '0f3c2a4e-9b1d-4c8e-a7f6-5d2b1e0c9a83',
    ]) {
      const id = within(snapshots).getByText(value)
      expect(id.tagName).toBe('CODE')
      expect(id).toHaveTextContent(value)
      expect(id).not.toHaveAttribute('aria-label')
    }
    // Both source snapshots, each with what it is.
    expect(snapshots).toHaveTextContent(copy.snapshots.basisSourceHelp)
    expect(snapshots).toHaveTextContent(copy.snapshots.sourceHelp)
    const calculation = part(copy.sections.calculation)
    expect(within(calculation).getByText(EVIDENCE_IDS.calculation).tagName).toBe('CODE')
    for (const hash of ['a'.repeat(64), 'b'.repeat(64)]) {
      expect(within(calculation).getByText(hash)).toHaveTextContent(hash)
    }
    const inputs = part(copy.sections.inputs)
    for (const value of [
      EVIDENCE_IDS.plan,
      '6a1f0c2e-3d4b-4e5f-8a9b-0c1d2e3f4a5b',
      'c'.repeat(64),
      'BYBIT-CASHFLOW-1.0',
      'd'.repeat(64),
      'BYBIT-PRICE-1.0',
      'e'.repeat(64),
    ]) {
      expect(within(inputs).getAllByText(value).length).toBeGreaterThan(0)
    }
    expect(inputs).toHaveTextContent(copy.inputs.cashflowCounts(0, 0))
    expect(inputs).toHaveTextContent(`${copy.inputs.provider}${copy.providers.BYBIT}`)
    expect(region().textContent).not.toContain('…')
  })

  it('AC-3: the connection policy and the collection policy are two facts, each shown once under its own name', async () => {
    const evidence = calculationEvidenceFixture(evidenceResultFixture())
    const inputs = evidence.inputs as Record<string, Record<string, unknown>>
    const connection = { ...inputs.connection, policyVersion: 'bybit-connection-policy-0.9' }
    answers(() => Promise.resolve(json({ ...evidence, inputs: { ...inputs, connection } })))
    render(<Harness view={ready()} />)
    await openEvidence()

    const section = part(copy.sections.inputs)
    expect(within(section).getAllByText('bybit-connection-policy-0.9')).toHaveLength(1)
    expect(within(section).getAllByText('bybit-readonly-uta-1.0')).toHaveLength(1)
    expect(within(section).getAllByText(copy.inputs.connectionPolicy)).toHaveLength(1)
    expect(within(section).getAllByText(copy.inputs.collectionPolicy)).toHaveLength(1)
  })

  it('AC-4: each metric names its character, an undefined one its reason and no cause, and a defined estimated one the price evidence and flow count — with no value', async () => {
    const result = estimatedResult()
    answers(() => Promise.resolve(json(calculationEvidenceFixture(result))))
    render(<Harness view={ready(result)} />)
    await openEvidence()

    const impact = part(copy.sections.metrics)
    const rows = within(impact).getAllByRole('listitem')
    expect(rows).toHaveLength(METRIC_ORDER.length)
    METRIC_ORDER.forEach((metric, index) => {
      expect(rows[index]?.textContent.startsWith(planResultContent.metrics[metric].name)).toBe(true)
    })
    const [current, ...derived] = rows
    expect(current).toHaveTextContent(`${copy.metrics.nature}${copy.natures.OFFICIAL}`)
    expect(current).toHaveTextContent(copy.metrics.currentValueCause)
    const cause = copy.metrics.estimatedCause(copy.evidenceTypes.SPOT_KLINE_CLOSE, 1)
    const undefinedRate = derived[3]
    for (const row of derived) {
      expect(row).toHaveTextContent(`${copy.metrics.nature}${copy.natures.ESTIMATED}`)
    }
    for (const row of derived.filter((each) => each !== undefinedRate)) {
      expect(row).toHaveTextContent(cause)
    }
    // The undefined required rate: its reason and its nature, and no "reflected" sentence at all.
    expect(undefinedRate).toHaveTextContent(
      `${copy.metrics.reason}${planResultContent.reasons.CURRENT_VALUE_NOT_POSITIVE ?? ''}`,
    )
    expect(undefinedRate).not.toHaveTextContent(copy.metrics.cause)
    expect(undefinedRate).not.toHaveTextContent(/반영/)
    expect(undefinedRate).not.toHaveTextContent(/0\.00|%/)
    // The evidence explains the character; the values stay on the cards, never repeated here.
    expect(impact.textContent).not.toMatch(/USD|\d%/)
    expect(part(copy.sections.inputs)).toHaveTextContent(copy.inputs.cashflowCounts(2, 1))
  })

  it('AC-4: an official version says no estimated price reached the derived metrics', async () => {
    answers(evidenceOk)
    render(<Harness view={ready()} />)
    await openEvidence()

    const rows = within(part(copy.sections.metrics)).getAllByRole('listitem')
    for (const row of rows.slice(1)) {
      expect(row).toHaveTextContent(copy.metrics.noEstimatedCause)
    }
    expect(part(copy.sections.metrics)).not.toHaveTextContent('추정 가격 근거')
  })

  it('has fallback copy of its own for an unknown nature and an unknown attempt status', () => {
    expect(copy.unknownNature).not.toBe(planResultContent.axes.unknownState)
    expect(copy.unknownAttemptStatus).not.toBe(planPublicationContent.unknownReason.cause)
  })

  it('AC-5: the current official version is named, and attempts that never published are a separate read-only list', async () => {
    const evidence = calculationEvidenceFixture(evidenceResultFixture(), {
      versions: {
        official: [
          {
            pointerVersion: 1,
            calculationId: EVIDENCE_IDS.calculation,
            publishedAt: '2026-09-27T01:00:01Z',
            current: true,
          },
        ],
        unpublished: [
          { status: 'BLOCKED', reasonCode: 'NOT_PERMITTED', finishedAt: '2026-09-27T00:40:00Z' },
          {
            status: 'FAILED',
            reasonCode: 'PUBLICATION_INTERRUPTED',
            finishedAt: '2026-09-27T00:50:00Z',
          },
          { status: 'FAILED', reasonCode: 'SOMETHING_NEW', finishedAt: '2026-09-27T00:55:00Z' },
        ],
      },
    })
    answers(() => Promise.resolve(json(evidence)))
    render(<Harness view={ready()} />)
    await openEvidence()

    const versions = part(copy.sections.versions)
    const lists = within(versions).getAllByRole('list')
    expect(lists).toHaveLength(2)
    const [official, unpublished] = lists
    if (official === undefined || unpublished === undefined) throw new Error('two lists')
    expect(within(official).getAllByRole('listitem')).toHaveLength(1)
    expect(official).toHaveTextContent(copy.versions.current)
    expect(official).toHaveTextContent(EVIDENCE_IDS.calculation)
    const attempts = within(unpublished).getAllByRole('listitem')
    expect(attempts).toHaveLength(3)
    expect(attempts[0]).toHaveTextContent(copy.attemptStatuses.BLOCKED)
    expect(attempts[0]).toHaveTextContent(
      planPublicationContent.reasons.NOT_PERMITTED?.cause ?? 'missing copy',
    )
    expect(attempts[1]).toHaveTextContent(copy.attemptStatuses.FAILED)
    expect(attempts[1]).toHaveTextContent(
      planPublicationContent.reasons.PUBLICATION_INTERRUPTED?.cause ?? 'missing copy',
    )
    expect(attempts[2]).toHaveTextContent(planPublicationContent.unknownReason.cause)
    for (const attempt of attempts) {
      // Read-only: never marked current, never an official value, nothing to press or follow.
      expect(attempt).not.toHaveTextContent(copy.versions.current)
      expect(within(attempt).queryAllByRole('link')).toEqual([])
      expect(within(attempt).queryAllByRole('button')).toEqual([])
    }
    expect(versions).toHaveTextContent(copy.versions.unpublishedHelp)
    expect(unpublished).not.toHaveTextContent('SOMETHING_NEW')
    expect(unpublished).not.toHaveTextContent(EVIDENCE_IDS.calculation)
  })

  it('Story 2.11: an incremental version shows where its sync read again, and every official version with its applied end', async () => {
    const shownV2 = newerBundle()
    const windowStart = '2026-09-26T01:00:00Z'
    const base = calculationEvidenceFixture(shownV2)
    const inputs = base.inputs as Record<string, Record<string, unknown>>
    const evidence = {
      ...base,
      inputs: { ...inputs, sourceRange: { ...inputs.sourceRange, windowStart } },
      versions: {
        official: [
          {
            pointerVersion: 1,
            calculationId: EVIDENCE_IDS.calculation,
            publishedAt: '2026-09-25T00:00:05Z',
            appliedUntil: '2026-09-27T01:00:01Z',
            current: false,
          },
          {
            pointerVersion: 2,
            calculationId: EVIDENCE_IDS.newerCalculation,
            publishedAt: '2026-09-27T01:00:01Z',
            appliedUntil: null,
            current: true,
          },
        ],
        unpublished: [],
      },
      change: changeFromV1(shownV2),
    }
    answers(() => Promise.resolve(json(evidence)))
    render(<Harness view={ready(shownV2)} />)
    await openEvidence()

    // The collected range is still (t0, tc]; the window start is its own fact beside it.
    const inputsPart = part(copy.sections.inputs)
    expect(inputsPart).toHaveTextContent(copy.inputs.windowStart)
    expect(inputsPart).toHaveTextContent(copy.inputs.windowStartHelp)
    // P13: this window starts after t0, so the part before it was carried over.
    expect(inputsPart).toHaveTextContent(copy.inputs.windowStartCarried)
    expect(inputsPart).not.toHaveTextContent(copy.inputs.windowStartFromT0)
    expect(inputsPart).toHaveTextContent(
      `${copy.inputs.windowStart}${formatSeoulTime(windowStart, planResultContent.timeUnavailable)} ${copy.separators.item} ${planResultContent.timeZone}`,
    )
    const [official] = within(part(copy.sections.versions)).getAllByRole('list')
    if (official === undefined) throw new Error('the official list')
    const rows = within(official).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    const [earlier, current] = rows
    expect(earlier).toHaveTextContent(EVIDENCE_IDS.calculation)
    expect(earlier).toHaveTextContent(
      formatSeoulTime('2026-09-27T01:00:01Z', planResultContent.timeUnavailable),
    )
    expect(earlier).toHaveTextContent(copy.versions.earlier)
    expect(earlier).not.toHaveTextContent(copy.versions.current)
    expect(current).toHaveTextContent(EVIDENCE_IDS.newerCalculation)
    expect(current).toHaveTextContent(copy.versions.current)
    expect(part(copy.sections.versions)).toHaveTextContent(copy.versions.officialHelp)
    expect(within(part(copy.sections.versions)).queryAllByRole('link')).toEqual([])
  })

  it('P13: a window that starts at t0 carries nothing over, and says it read from t0', async () => {
    answers(evidenceOk)
    render(<Harness view={ready()} />)
    await openEvidence()
    const inputsPart = part(copy.sections.inputs)
    expect(inputsPart).toHaveTextContent(copy.inputs.windowStartFromT0)
    expect(inputsPart).not.toHaveTextContent(copy.inputs.windowStartCarried)
  })

  it('P15: accepts an unbroken history whose publications share a millisecond', () => {
    const shownV2 = parsed(newerBundle())
    const base = calculationEvidenceFixture(newerBundle())
    const same = '2026-09-27T01:00:01Z'
    const history = {
      ...base,
      versions: {
        official: [
          {
            pointerVersion: 1,
            calculationId: EVIDENCE_IDS.calculation,
            publishedAt: same,
            appliedUntil: same,
            current: false,
          },
          {
            pointerVersion: 2,
            calculationId: EVIDENCE_IDS.newerCalculation,
            publishedAt: same,
            appliedUntil: null,
            current: true,
          },
        ],
        unpublished: [],
      },
      change: changeFromV1(newerBundle()),
    }
    expect(parseEvidence(history, shownV2)).not.toBeNull()
  })

  it('AC-5: with no unpublished attempt the list says so', async () => {
    answers(evidenceOk)
    render(<Harness view={ready()} />)
    await openEvidence()

    expect(part(copy.sections.versions)).toHaveTextContent(copy.versions.unpublished)
    expect(within(part(copy.sections.versions)).getByText(copy.versions.none)).toBeInTheDocument()
  })

  it.each([
    ['a network failure', () => Promise.reject(new Error('network'))],
    [
      'a broken lineage (503)',
      () =>
        Promise.resolve(
          json({ code: 'EVIDENCE_UNAVAILABLE', message: 'x', correlationId: 'c' }, 503),
        ),
    ],
    ['a server error', () => Promise.resolve(json({}, 500))],
    ['an answer that is not JSON', () => Promise.resolve(new Response('nope', { status: 200 }))],
    [
      'another bundle’s lineage',
      () =>
        Promise.resolve(
          json(
            calculationEvidenceFixture(
              evidenceResultFixture({ calculationId: EVIDENCE_IDS.newerCalculation }),
            ),
          ),
        ),
    ],
  ])(
    'AC-6 (READ_FAIL): %s leaves the dashboard as it was, names the minimal reference and offers a retry that keeps focus',
    async (_name, failure) => {
      const reload = vi.fn()
      answers(failure, evidenceOk)
      render(<Harness reload={reload} view={ready()} />)
      const before = cards().map((card) => card.textContent)

      fireEvent.click(toggle())
      await within(region()).findByText(copy.failure.retryable)
      expect(cards().map((card) => card.textContent)).toEqual(before)
      expect(region()).toHaveTextContent(`${copy.failure.snapshotId}${EVIDENCE_IDS.snapshot}`)
      expect(region()).toHaveTextContent(`${copy.failure.calculationId}${EVIDENCE_IDS.calculation}`)
      expect(region()).toHaveTextContent(copy.failure.retryableHelp)
      expect(within(region()).queryByRole('group', { name: copy.sections.snapshots })).toBeNull()
      expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.retryable)

      const retry = within(region()).getByRole('button', { name: copy.retry })
      retry.focus()
      fireEvent.click(retry)
      // While it reads again the control stays in place, busy, and keeps the keyboard user's focus.
      expect(within(region()).getByRole('button', { name: copy.retry })).toBe(retry)
      expect(retry).toHaveAttribute('aria-disabled', 'true')
      expect(retry).toHaveFocus()
      await screen.findByRole('group', { name: copy.sections.versions })
      // The retry control is gone with the failure: focus moves to the region's title, not the body. The
      // move is the region's passive effect after the commit that removed the control, so it is awaited.
      await waitFor(() => {
        expect(screen.getByRole('heading', { level: 4, name: copy.heading })).toHaveFocus()
      })
      expect(cards().map((card) => card.textContent)).toEqual(before)
      // A plain failure never asks the dashboard for anything.
      expect(reload).not.toHaveBeenCalled()
    },
  )

  it('AC-6: a 404 says the shown result is not the current one, offers no retry, and points to reloading the dashboard', async () => {
    const reload = vi.fn()
    answers(() =>
      Promise.resolve(json({ code: 'NOT_FOUND', message: 'x', correlationId: 'c' }, 404)),
    )
    render(<Harness reload={reload} view={ready()} />)
    const before = cards().map((card) => card.textContent)

    fireEvent.click(toggle())
    await within(region()).findByText(copy.failure.stale)
    expect(region()).toHaveTextContent(copy.failure.staleHelp)
    expect(region()).toHaveTextContent(`${copy.failure.calculationId}${EVIDENCE_IDS.calculation}`)
    expect(within(region()).queryByRole('button', { name: copy.retry })).not.toBeInTheDocument()
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.stale)
    expect(cards().map((card) => card.textContent)).toEqual(before)
    expect(reload).not.toHaveBeenCalled()
  })

  it('AC-8: a 403 drops the evidence and asks the dashboard to read again; a 401 does too, with a link back to the entry screen', async () => {
    const reload = vi.fn()
    answers(
      evidenceOk,
      () => Promise.resolve(json({ code: 'FORBIDDEN', message: 'x', correlationId: 'c' }, 403)),
      () =>
        Promise.resolve(json({ code: 'UNAUTHENTICATED', message: 'x', correlationId: 'c' }, 401)),
    )
    render(<Harness reload={reload} view={ready()} />)
    await openEvidence()
    expect(region()).toHaveTextContent('d'.repeat(64))
    expect(reload).not.toHaveBeenCalled()

    fireEvent.click(toggle())
    fireEvent.click(toggle())
    await within(region()).findByText(copy.failure.denied)
    expect(region()).not.toHaveTextContent('d'.repeat(64))
    expect(within(region()).queryByRole('group')).toBeNull()
    expect(within(region()).queryByRole('button', { name: copy.retry })).not.toBeInTheDocument()
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.denied)
    // The dashboard's own read decides what it keeps: it is asked to read again, once.
    expect(reload).toHaveBeenCalledTimes(1)

    fireEvent.click(toggle())
    fireEvent.click(toggle())
    await within(region()).findByText(copy.failure.signedOut)
    const link = within(region()).getByRole('link', { name: copy.failure.signInLink })
    expect(link).toHaveAttribute('href', ENTRY_PATH)
    // A link reads as a link, not as one of the region's buttons.
    expect(link.className).not.toBe(toggle().className)
    expect(screen.getByTestId('announcement')).toHaveTextContent(copy.status.signedOut)
    expect(reload).toHaveBeenCalledTimes(2)
  })

  it('discards the evidence and closes when the shown bundle is replaced, returning focus to the button', async () => {
    answers(evidenceOk)
    const { rerender } = render(<Harness view={ready()} />)
    await openEvidence()
    within(region()).getByRole('button', { name: copy.close }).focus()

    rerender(<Harness view={ready(newerBundle())} />)

    expect(toggle()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: copy.heading })).not.toBeInTheDocument()
    expect(toggle()).toHaveFocus()
    expect(document.body.textContent).not.toContain('d'.repeat(64))
  })

  it('keeps the evidence while the same bundle stays shown after a failed dashboard reload', async () => {
    answers(evidenceOk)
    const result = parsed(evidenceResultFixture())
    const { rerender } = render(
      <Harness
        view={{
          kind: 'READY',
          nextAction: 'NONE',
          result,
          sync: AVAILABLE_SYNC,
          verification: AVAILABLE_VERIFICATION,
          change: NO_CHANGE,
        }}
      />,
    )
    await openEvidence()

    rerender(
      <Harness
        view={{
          kind: 'lastGood',
          nextAction: 'NONE',
          result,
          sync: AVAILABLE_SYNC,
          verification: AVAILABLE_VERIFICATION,
          change: NO_CHANGE,
        }}
      />,
    )
    expect(region()).toHaveTextContent('d'.repeat(64))
  })

  it('aborts a read in flight when the shown bundle is replaced, and its late answer renders nothing', async () => {
    const pending = deferred()
    const { inits } = answers(() => pending.promise)
    const { rerender } = render(<Harness view={ready()} />)
    fireEvent.click(toggle())
    rerender(<Harness view={ready(newerBundle())} />)
    expect(inits[0]?.signal?.aborted).toBe(true)

    await act(async () => {
      pending.resolve(json(calculationEvidenceFixture(evidenceResultFixture())))
      await Promise.resolve()
    })
    expect(toggle()).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: copy.heading })).not.toBeInTheDocument()
    expect(screen.getByTestId('announcement')).toHaveTextContent('')
  })

  it('never renders an answer for the old bundle under a newer one, even when it lands right after the commit that replaces it', async () => {
    const pending = deferred()
    answers(() => pending.promise)
    const handle: { show: (view: DashboardView) => void } = { show: () => undefined }
    const newer = ready(newerBundle())
    /*
     * The answer is released from a layout effect of the commit that shows the newer bundle, so it lands
     * in the microtasks after that commit — before React runs the commit's passive effects in a later
     * task. That is the window a guard in a passive effect would leave open.
     */
    function Probe({ view }: { readonly view: DashboardView }): null {
      useLayoutEffect(() => {
        if (view === newer)
          pending.resolve(json(calculationEvidenceFixture(evidenceResultFixture())))
      }, [view])
      return null
    }
    function Switchable(): ReactElement {
      const [view, setView] = useState<DashboardView>(ready())
      useLayoutEffect(() => {
        handle.show = setView
      }, [])
      return (
        <>
          <Probe view={view} />
          <Harness view={view} />
        </>
      )
    }
    render(<Switchable />)
    fireEvent.click(toggle())
    const environment = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }
    environment.IS_REACT_ACT_ENVIRONMENT = false
    try {
      // A default-priority update from outside any event, like the dashboard's own reload settling.
      handle.show(newer)
      for (let tick = 0; tick < 5; tick += 1) await new Promise((settle) => setTimeout(settle, 10))
    } finally {
      environment.IS_REACT_ACT_ENVIRONMENT = true
    }
    expect(toggle()).toHaveAttribute('aria-expanded', 'false')
    expect(document.body.textContent).not.toContain('d'.repeat(64))
    expect(screen.queryByRole('region', { name: copy.heading })).not.toBeInTheDocument()
  })

  it('aborts a read in flight when the region is closed; the abort is not a failure', async () => {
    const pending = deferred()
    const { inits } = answers(() => pending.promise)
    render(<Harness view={ready()} />)
    fireEvent.click(toggle())
    fireEvent.click(within(region()).getByRole('button', { name: copy.close }))
    expect(inits[0]?.signal?.aborted).toBe(true)
    await act(async () => {
      pending.resolve(json({}, 503))
      await Promise.resolve()
    })
    expect(screen.queryByRole('region', { name: copy.heading })).not.toBeInTheDocument()
    expect(screen.getByTestId('announcement')).toHaveTextContent('')
  })

  it.each([
    ['a refusal', { kind: 'denied', cause: 'refused' }],
    // The next-action block stays here, so only the bundle's going away can move focus.
    ['no plan any more', { kind: 'NO_PLAN', nextAction: 'CREATE_PLAN' }],
  ] as const)(
    'moves focus to the dashboard heading when the bundle goes away (%s) while focus is in the evidence',
    async (_name, next) => {
      answers(evidenceOk)
      const { rerender } = render(<Harness view={ready()} />)
      await openEvidence()
      within(region()).getByRole('button', { name: copy.close }).focus()

      rerender(<Harness view={next} />)

      expect(screen.queryByRole('button', { name: copy.open })).not.toBeInTheDocument()
      expect(
        screen.getByRole('heading', { level: 2, name: planResultContent.heading }),
      ).toHaveFocus()
    },
  )

  it('writes nothing to browser storage and uses no recommendation, celebration, fear, streak or badge wording', async () => {
    answers(() => Promise.resolve(json(calculationEvidenceFixture(estimatedResult()))))
    render(<Harness view={ready(estimatedResult())} />)
    await openEvidence()

    expect(getObservedStorageEntries()).toEqual([])
    expect(region().textContent).not.toMatch(
      /축하|성공|실패|손실|수익을 내|투자성과|계좌 수익률|추천합니다|스트릭|배지|달성하세요|서두르|놓치|정확/,
    )
  })

  describe('parseEvidence: the lineage is accepted whole, and only for the shown bundle', () => {
    const shown = parsed(evidenceResultFixture())
    const base = (): Record<string, unknown> => calculationEvidenceFixture(evidenceResultFixture())

    /** The fixture with one top-level section replaced by a copy that changes some fields. */
    function changed(section: string, fields: Record<string, unknown>): Record<string, unknown> {
      const evidence = base()
      return { ...evidence, [section]: { ...(evidence[section] as object), ...fields } }
    }

    function changedInput(
      section: string,
      fields: Record<string, unknown>,
    ): Record<string, unknown> {
      const evidence = base()
      const inputs = evidence.inputs as Record<string, Record<string, unknown>>
      return { ...evidence, inputs: { ...inputs, [section]: { ...inputs[section], ...fields } } }
    }

    function changedTerms(fields: Record<string, unknown>): Record<string, unknown> {
      const evidence = base()
      const inputs = evidence.inputs as Record<string, Record<string, unknown>>
      const plan = inputs.plan ?? {}
      return {
        ...evidence,
        inputs: {
          ...inputs,
          plan: { ...plan, conditions: { ...(plan.conditions as object), ...fields } },
        },
      }
    }

    function officialVersions(...versions: Record<string, unknown>[]): Record<string, unknown> {
      return { ...base(), versions: { official: versions, unpublished: [] } }
    }

    const current = {
      pointerVersion: 1,
      calculationId: EVIDENCE_IDS.calculation,
      publishedAt: '2026-09-27T01:00:01Z',
      current: true,
    }

    it('accepts the fixture', () => {
      expect(parseEvidence(base(), shown)).not.toBeNull()
    })

    it.each([
      ['another plan', { ...base(), planId: EVIDENCE_IDS.newerCalculation }],
      ['another version', { ...base(), pointerVersion: 2 }],
      ['another calculation', { ...base(), calculationId: EVIDENCE_IDS.newerCalculation }],
      ['a missing section', { ...base(), inputs: undefined }],
      ['metrics out of order', { ...base(), metrics: [] }],
      ['another measurement time', changed('times', { measuredAt: '2026-09-27T00:00:00Z' })],
      ['another snapshot', changed('snapshots', { portfolioSnapshotId: EVIDENCE_IDS.basis })],
      [
        'a missing basis source snapshot',
        changed('snapshots', { basisSourceSnapshotId: undefined }),
      ],
      [
        'a range starting after t0',
        changedInput('sourceRange', { rangeStart: '2026-09-25T00:00:01Z' }),
      ],
      [
        'a range ending before tc',
        changedInput('sourceRange', { rangeEnd: '2026-09-27T00:59:00Z' }),
      ],
      ['another principal', changedTerms({ principal: '1234.57' })],
      ['another end', changedTerms({ endAt: '2026-10-26T04:00:00Z' })],
      ['another period', changedTerms({ period: 'WEEKLY' })],
      ['another target rate', changedTerms({ targetRate: '0.2' })],
      [
        'a creation time that is not an instant',
        changed('calculation', { createdAt: 'yesterday' }),
      ],
      [
        'a creation time in another format',
        changed('calculation', { createdAt: '2026-09-27 01:00:01' }),
      ],
      [
        'a publication time that is not an instant',
        officialVersions({ ...current, publishedAt: 'x' }),
      ],
      ['two current versions', officialVersions(current, current)],
      [
        'a current version that is not the shown one',
        officialVersions({ ...current, pointerVersion: 2 }),
      ],
      [
        'an attempt shown as published',
        {
          ...base(),
          versions: {
            official: [current],
            unpublished: [
              { status: 'PUBLISHED', reasonCode: 'X', finishedAt: '2026-09-27T00:50:00Z' },
            ],
          },
        },
      ],
      [
        'a window start before t0',
        changedInput('sourceRange', { windowStart: '2026-09-24T23:59:59Z' }),
      ],
      [
        'a window start after tc',
        changedInput('sourceRange', { windowStart: '2026-09-27T01:00:01Z' }),
      ],
      ['no window start', changedInput('sourceRange', { windowStart: undefined })],
      ['a window start that is not an instant', changedInput('sourceRange', { windowStart: 'x' })],
      [
        'a current version with an applied end',
        officialVersions({ ...current, appliedUntil: '2026-09-28T00:00:00Z' }),
      ],
      [
        'an earlier version without an applied end',
        officialVersions(
          { ...current, pointerVersion: 0, calculationId: 'x', current: false, appliedUntil: null },
          current,
        ),
      ],
      [
        'versions out of publication order',
        officialVersions(
          {
            ...current,
            pointerVersion: 3,
            calculationId: 'x',
            current: false,
            appliedUntil: '2026-09-27T01:00:01Z',
          },
          current,
        ),
      ],
      [
        'P15: a gap between an earlier version and the next',
        officialVersions(
          {
            ...current,
            pointerVersion: 0,
            calculationId: 'x',
            publishedAt: '2026-09-26T00:00:00Z',
            current: false,
            appliedUntil: '2026-09-27T00:00:00Z',
          },
          current,
        ),
      ],
      [
        'P15: an applied end before its own publication',
        officialVersions(
          {
            ...current,
            pointerVersion: 0,
            calculationId: 'x',
            publishedAt: '2026-09-27T01:00:02Z',
            current: false,
            appliedUntil: '2026-09-27T01:00:01Z',
          },
          current,
        ),
      ],
      [
        'a current version that is not the latest',
        officialVersions(current, {
          ...current,
          pointerVersion: 2,
          calculationId: 'x',
          current: false,
          appliedUntil: '2026-09-27T01:00:01Z',
        }),
      ],
      [
        'an attempt without an instant',
        {
          ...base(),
          versions: {
            official: [current],
            unpublished: [{ status: 'FAILED', reasonCode: 'X', finishedAt: 'later' }],
          },
        },
      ],
    ])('rejects %s', (_name, evidence) => {
      expect(parseEvidence(evidence, shown)).toBeNull()
    })

    it('rejects a metric impact that disagrees with the shown metrics, gives the current value a cause, or gives an undefined metric one', () => {
      const evidence = base()
      const metrics = evidence.metrics as Record<string, unknown>[]
      const estimatedCurrent = metrics.map((metric, index) =>
        index === 0
          ? { ...metric, estimatedCause: { evidenceType: 'SPOT_KLINE_CLOSE', flowCount: 0 } }
          : metric,
      )
      expect(parseEvidence({ ...evidence, metrics: estimatedCurrent }, shown)).toBeNull()
      const otherNature = metrics.map((metric, index) =>
        index === 2 ? { ...metric, nature: 'ESTIMATED' } : metric,
      )
      expect(parseEvidence({ ...evidence, metrics: otherNature }, shown)).toBeNull()
      const invented = metrics.map((metric, index) =>
        index === 4 ? { ...metric, unavailableReason: 'CURRENT_VALUE_NOT_POSITIVE' } : metric,
      )
      expect(parseEvidence({ ...evidence, metrics: invented }, shown)).toBeNull()

      const estimated = parsed(estimatedResult())
      const estimatedEvidence = calculationEvidenceFixture(estimatedResult())
      expect(parseEvidence(estimatedEvidence, estimated)).not.toBeNull()
      const estimatedMetrics = estimatedEvidence.metrics as Record<string, unknown>[]
      const withoutCause = estimatedMetrics.map((metric) => ({
        ...metric,
        estimatedCause: undefined,
      }))
      expect(parseEvidence({ ...estimatedEvidence, metrics: withoutCause }, estimated)).toBeNull()
      const causeOnUndefined = estimatedMetrics.map((metric) =>
        metric.metric === 'REQUIRED_RATE'
          ? { ...metric, estimatedCause: { evidenceType: 'SPOT_KLINE_CLOSE', flowCount: 1 } }
          : metric,
      )
      expect(
        parseEvidence({ ...estimatedEvidence, metrics: causeOnUndefined }, estimated),
      ).toBeNull()
    })
  })

  it('announces each state in one sentence for the screen’s status region, and nothing while closed', () => {
    expect(evidenceAnnouncement({ kind: 'closed' })).toBeNull()
    expect(evidenceAnnouncement({ kind: 'loading', retrying: false })).toBe(copy.status.loading)
    expect(evidenceAnnouncement({ kind: 'failed', retryable: true })).toBe(copy.status.retryable)
    expect(evidenceAnnouncement({ kind: 'failed', retryable: false })).toBe(copy.status.stale)
    expect(evidenceAnnouncement({ kind: 'denied', cause: 'refused' })).toBe(copy.status.denied)
    expect(evidenceAnnouncement({ kind: 'denied', cause: 'signedOut' })).toBe(copy.status.signedOut)
  })

  it('reads nothing while the region stays closed', async () => {
    // No evidence stub: a hidden read would be refused and recorded by the global guard.
    render(<Harness view={ready()} />)
    await waitFor(() => {
      expect(toggle()).toBeInTheDocument()
    })
    await new Promise((settle) => setTimeout(settle, 20))
    expect(
      getObservedRequests().filter((entry) => entry.includes(DASHBOARD_EVIDENCE_PATH)),
    ).toEqual([])
    expect(
      getObservedAllowedRequests().filter((entry) => entry.includes(DASHBOARD_EVIDENCE_PATH)),
    ).toEqual([])
  })
})
